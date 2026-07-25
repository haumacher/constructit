package constructit.editor

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.FrameValue
import constructit.core.PointValue
import constructit.dsl.PointRef
import constructit.dsl.valueOf
import constructit.geom.Justification
import constructit.geom.Vec2
import constructit.units.mm

/** Undo depth bound: snapshots are whole scripts, so the stack must not grow with the session. */
private const val UNDO_CAP = 100

/** Below this screen distance a press-and-release is a *click*, not a drag. */
private const val CLICK_SLOP_PX = 3.0

/** How many panel picks are remembered — no tool asks for more scalars than this. */
private const val SCALAR_PICK_MEMORY = 4

/** Upper bound on a structural count, so a slip of the keyboard cannot build a million nodes. */
private const val MAX_COUNT = 512

/**
 * Which button a pointer gesture uses. In SELECT mode they mean different things (OP-16): PRIMARY
 * picks, rubber-bands a marquee and drags geometry; MIDDLE pans, in *every* tool — panning is a
 * button, not a mode, so the view can be moved without putting the tool down. The browser shell
 * reports MIDDLE for Space+drag as well, so a one-button mouse can still pan.
 */
enum class PointerButton { PRIMARY, MIDDLE }

/**
 * Pure interaction controller. In SELECT mode it drags free points (live recompute), rubber-bands a
 * selection, or pans on the middle button; otherwise it runs the active [ToolDef] as a generic
 * slot-collector, picking existing geometry or creating points per click, and consuming the active
 * parameter for scalar slots. No platform APIs — fully headless-testable by simulating gestures.
 */
class Editor(
    doc: Document = Document(),
    var canvasW: Double = 800.0,
    var canvasH: Double = 600.0,
) {
    var doc: Document = doc
        private set

    /** Swap in a loaded document, dropping every reference into the old one and its history. */
    fun replaceDocument(fresh: Document) {
        adopt(fresh)
        // a loaded file starts its own history — earlier snapshots describe a different document
        undoStack.clear()
        redoStack.clear()
        lastCommitted = DocumentFormat.save(fresh)
        statusHint = ""
        onChange()
    }

    /** Swap [fresh] in, resetting every transient reference into the old document (selection, picks). */
    private fun adopt(fresh: Document) {
        doc = fresh
        clearSelection()
        activeScalar = null
        scalarPicks.clear() // they name entries of the document being replaced
        resetPicks()
    }

    // ---- undo/redo: the saved construction script is the undo substrate (OP-18) ----
    //
    // One snapshot of the saved script per committed user-level operation; undo replays the previous
    // snapshot into a fresh document. Prefix-replay over the journal was rejected: a drag or a typed
    // value mutates a source node's literal without adding a step, so journal length does not delimit
    // an operation — the saved text (which restates those literals) does.
    //
    // [checkpoint] is called where an operation *commits*, not where the document mutates: a tool's
    // build, a drag's release (welds, attaches and joins included), a typed field write, a path's
    // finish (its start/vertex/close steps are one gesture), a break click, an opening insertion, a
    // delete, and the panel edits the browser shell routes here. A snapshot is pushed only when the
    // text changed, so a cancelled or no-op gesture never becomes an undo step.
    private val undoStack = ArrayList<String>()
    private val redoStack = ArrayList<String>()
    private var lastCommitted: String = DocumentFormat.save(doc)

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Record the document as one committed operation — the seam every user-level edit funnels through. */
    fun checkpoint() {
        val now = DocumentFormat.save(doc)
        if (now == lastCommitted) return
        undoStack.add(lastCommitted)
        if (undoStack.size > UNDO_CAP) undoStack.removeAt(0)
        redoStack.clear()
        lastCommitted = now
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        if (DocumentFormat.save(doc) != lastCommitted) {
            // uncommitted work in progress — a half-drawn path, a cancelled tool's stray points —
            // is discarded as the first undo, not popped past: it was never a step, so it has no
            // snapshot of its own and cannot be redone
            adopt(DocumentFormat.load(lastCommitted))
        } else {
            redoStack.add(lastCommitted)
            lastCommitted = undoStack.removeLast()
            adopt(DocumentFormat.load(lastCommitted))
        }
        statusHint = "Undone"
        onChange()
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        undoStack.add(lastCommitted)
        lastCommitted = redoStack.removeLast()
        adopt(DocumentFormat.load(lastCommitted))
        statusHint = "Redone"
        onChange()
        return true
    }

    /**
     * Delete the whole selection at the granularity of the steps that created it (OP-18): each
     * selected element's creating step is dropped together with every later step depending on what the
     * dropped steps made, and the remaining script is replayed into a fresh document — so what
     * survives is exactly what still constructs, never a graph with holes in it.
     *
     * A multi-selection is **one** closure over all those steps (not a union of per-element closures —
     * see [Document.dependentSteps]), dropped and replayed once, so it is one operation and one undo
     * step. All-or-nothing: if any selected element has no step to remove, the delete is refused rather
     * than half-performed, and says which element that was.
     */
    fun deleteSelection(): Boolean {
        val targets = selectedElements
        if (targets.isEmpty()) return false
        val roots = ArrayList<Step>()
        for (el in targets) {
            val root = doc.creatingStep(el)
            if (root == null) {
                statusHint = "${el.id} has no construction step to remove"
                onChange()
                return false
            }
            roots.add(root)
        }
        // one closure over all the roots at once — see [Document.dependentSteps]
        val droppedSteps = doc.dependentSteps(roots.toHashSet())
        val removed = droppedSteps.flatMapTo(HashSet()) { it.creates }
        val dependents = removed.count { r -> targets.none { it === r } && r in doc.elements }
        val journalBefore = doc.journal.toList()
        doc.journal.removeAll(droppedSteps)
        val fresh =
            try {
                DocumentFormat.load(DocumentFormat.save(doc))
            } catch (e: Exception) {
                doc.journal.clear()
                doc.journal.addAll(journalBefore)
                statusHint = "Delete failed: ${e.message}"
                onChange()
                return false
            }
        val what = if (targets.size == 1) targets[0].id else "${targets.size} elements"
        adopt(fresh)
        checkpoint()
        statusHint = if (dependents == 0) "Deleted $what" else "Deleted $what and $dependents dependent${if (dependents == 1) "" else "s"}"
        onChange()
        return true
    }

    var camera: Camera = Camera.centered(canvasW, canvasH)
    var toolId: String = Tools.SELECT
        private set

    /**
     * The scalar most recently picked in the panel — the "active parameter". Setting it also appends to
     * [scalarPicks], which is what lets a tool ask for **several** scalars in order without a second
     * mechanism: one pick is one click on a parameter row, whatever the tool needs.
     */
    var activeScalar: ScalarEntry? = null
        set(value) {
            field = value
            // The *same* entry picked again is one pick, not two: the panel selects a scalar on every click
            // and on every focus of its value field, so counting a re-visit would silently corrupt a
            // half-collected pair. A point whose x and y are meant to be one value is made by wiring the two
            // parameters together, which is how sharing a DOF is expressed everywhere else (OP-5).
            if (value != null && scalarPicks.lastOrNull() !== value) {
                scalarPicks.add(value)
                // bounded: only the last few can ever be consumed, and an unbounded list would grow with
                // the session for no benefit
                while (scalarPicks.size > SCALAR_PICK_MEMORY) scalarPicks.removeAt(0)
            }
        }

    /**
     * The panel picks, oldest first. A tool needing *k* scalars consumes the **last k** in pick order, so
     * a single-scalar tool means exactly "the active parameter" as before, a two-scalar tool means "x, then
     * y", and picking a wrong row is corrected by simply picking the right ones again. Deliberately not
     * cleared by [setTool]: the usual order is to pick the parameter and then the tool.
     */
    private val scalarPicks = ArrayList<ScalarEntry>()

    /**
     * How many instances a structural-count tool builds (a polygon's sides, an array's copies).
     *
     * **Structural, not a parameter**: it decides how many nodes the tool creates, exactly as an ortho
     * path's vertex count does, so it belongs to the *gesture* and is recorded in the tool step (OP-18)
     * rather than being a value that can be edited afterwards. Editing it later means re-running the tool.
     */
    var count: Int = 6
        set(value) {
            field = value.coerceIn(2, MAX_COUNT)
        }

    var onChange: () -> Unit = {}
    var showGrid: Boolean = false

    /**
     * Dim the construction that the results are built from (OP-14), so the drawing reads on its own.
     * A *view* setting: which elements are scaffolding is derived from the graph, so nothing is
     * flagged and nothing can drift out of date.
     */
    var dimScaffolding: Boolean = false

    /**
     * Which side of its carrier a new thick path's material sits on (OP-21). A property of the *tool*
     * rather than of a pick, like the active parameter: the WALL tool has no slot to click it into.
     */
    var justification: Justification = Justification.CENTER

    /**
     * While set, a drag is restricted to the single axis its gesture is dominated by, measured from
     * where the drag started — so a corner can be moved in x *or* y without disturbing the other.
     * Read live, so it can be engaged or released mid-drag.
     */
    var axisLock: Boolean = false

    /**
     * While set, a click being *placed* resolves through [Snap] — so geometry can be put onto other
     * geometry as it is drawn, with a real dependency, instead of only being attached afterwards by
     * dragging. Cleared (Alt in the browser shell) to place at the raw cursor.
     */
    var snapEnabled: Boolean = true

    /** Where the last hover would land, for the snap marker and the status bar. */
    var snapHint: SnapResult? = null
        private set

    // ---- selection: a set with a primary element (OP-16 step 0) ----
    //
    // A set, because bulk delete / hide / grouping all operate on "what is selected"; a *primary*,
    // because the inspector addresses exactly one handle (OP-13) and a click has to say which one it
    // meant. Click replaces the set, Shift+click toggles one member, a marquee replaces it wholesale.
    private val selected = LinkedHashSet<Element>()

    /**
     * The primary element of the selection — the one a click landed on, hence the one whose handle the
     * inspector addresses: the drag is on the canvas, the typed form is in the inspector, and both
     * write the same nodes (OP-13). Null when nothing is selected.
     */
    var selection: Element? = null
        private set

    /** Everything selected, in pick order. */
    val selectedElements: List<Element> get() = selected.toList()
    val selectionCount: Int get() = selected.size

    fun isSelected(el: Element): Boolean = el in selected

    /**
     * The group the selection currently addresses *as a whole* — set when a click (or the panel) selected
     * every member, cleared when a second click reached a member alone.
     *
     * Which of the two the selection means is a fact about the gesture, not about the set: a one-member
     * group selects the same elements either way, and for a **placed** group the difference is what the
     * inspector shows and what a drag writes (the frame, or the member's local point).
     */
    var selectedGroup: Group? = null
        private set

    /** Replace the selection with [els], making [primary] the inspector's subject. */
    private fun select(
        els: Collection<Element>,
        primary: Element?,
    ) {
        selected.clear()
        selected.addAll(els)
        selection = primary?.takeIf { it in selected } ?: selected.firstOrNull()
        selectedGroup = null
    }

    fun clearSelection() {
        selected.clear()
        selection = null
        selectedGroup = null
    }

    /** The frame the selection addresses: a **placed** group selected as a whole (OP-16 step 2). */
    fun selectedFrame(): Group? = selectedGroup?.takeIf { it.placed }

    var statusHint: String = ""
        private set

    private val tolPx = 10.0

    private fun tolWorld() = tolPx / camera.scale

    private fun ev() = Evaluator()

    /** Resolve a click position through [Snap] (grid included only while the grid is shown). */
    private fun snap(
        world: Vec2,
        exclude: (Element) -> Boolean = { false },
    ): SnapResult =
        if (!snapEnabled) {
            SnapResult(world, SnapKind.FREE)
        } else {
            Snap.resolve(doc, ev(), world, tolWorld(), if (showGrid) SceneRenderer.gridStep(camera.scale) else null, exclude)
        }

    /** The geometry of the path being drawn — never a snap target for its own next vertex. */
    private fun activePathParts(): Set<Element> {
        val p = activePath ?: return emptySet()
        return (p.legs + p.vertices.mapNotNull { doc.elementFor(it.ref) }).toSet()
    }

    /**
     * Resolve a path click. For a vertex after the first, a curve snap is refined to where the *leg*
     * meets the curve: the leg can't bend to reach the cursor's projection, it runs on until it hits —
     * which is also the endpoint the attach then derives, so preview and result agree.
     */
    private fun pathSnap(raw: Vec2): SnapResult {
        val own = activePathParts()
        val s = snap(raw) { it in own }
        val last = activePath?.let { if (pathAtEnd) it.vertices.lastOrNull() else it.vertices.firstOrNull() } ?: return s
        if (s.kind != SnapKind.ON_CURVE) return s
        val from = (ev().valueOf(last.ref) as? PointValue)?.p ?: return s
        val axis = if (kotlin.math.abs(raw.x - from.x) >= kotlin.math.abs(raw.y - from.y)) 0 else 1
        val hit = Snap.axisCrossing(ev(), s.target!!, from, axis, raw) ?: return s
        return SnapResult(hit, SnapKind.ON_CURVE, s.target)
    }

    /**
     * Connect a just-placed path vertex to whatever the snap found: weld onto a point (materializing
     * an intersection first), or attach onto a curve. These are the very operations the drag magnet
     * performs, so a connection made *while drawing* is the same construction as one made afterwards.
     */
    private fun linkPathVertex(
        vertex: PointRef,
        s: SnapResult,
    ): Boolean {
        val el = doc.elementFor(vertex) ?: return false
        return when (s.kind) {
            SnapKind.POINT -> doc.weldOrthoEndpointToPoint(el, s.target!!)
            SnapKind.INTERSECTION -> {
                val ip = doc.intersectNear(s.target!!, s.other!!, s.pos) ?: return false
                doc.elementFor(ip)?.let { doc.weldOrthoEndpointToPoint(el, it) } ?: false
            }
            SnapKind.ON_CURVE -> doc.attachOrthoEndpointToCurve(el, s.target!!)
            else -> false
        }
    }

    /**
     * The point a placing click should use: reuse the snapped point, materialize the intersection, or
     * attach to the curve — so the placed point *depends* on what it was placed on. Only a miss (or a
     * grid snap) makes a new free point.
     */
    private fun placePoint(world: Vec2): PointRef {
        val s = snap(world)
        val ref =
            when (s.kind) {
                SnapKind.POINT -> s.target?.ref as? PointRef
                SnapKind.INTERSECTION -> doc.intersectNear(s.target!!, s.other!!, s.pos)
                SnapKind.ON_CURVE -> doc.pointOnCurve(s.target!!, s.pos)
                else -> null
            }
        if (ref != null) statusHint = "Snapped to ${s.label}"
        return ref ?: doc.freePoint(s.pos.x.mm, s.pos.y.mm)
    }

    // transient state
    private var dragTarget: Element? = null // a point, or a whole ortho leg
    private var dragFrame: Group? = null // a placed group being moved by its frame (OP-16 step 2)
    private var dragStart: Vec2? = null // where on the geometry the drag began — the axis-lock origin
    private var grabOffset: Vec2 = Vec2(0.0, 0.0) // cursor minus that, so a grab never jumps
    private var joinHints: List<Vec2> = emptyList() // corners this drag has flattened, marked on canvas
    private var weldTarget: Element? = null // a point to weld onto
    private var attachTarget: Element? = null // a curve to attach onto
    private var haloPos: Vec2? = null // where the magnet ring is drawn
    private var panning = false
    private var lastScreen = Vec2(0.0, 0.0)
    private var downScreen: Vec2? = null // where the press landed, so a release can tell click from drag
    private var pendingToggle: Element? = null // Shift+click's toggle, applied on release (see [pointerUp])
    private var pendingCycle: Element? = null // group/member cycle of a placed group's member, likewise on release
    private var marqueeFrom: Vec2? = null // rubber-band origin in world coordinates
    private var marqueeTo: Vec2? = null
    private var marqueeAdds = false // Shift+marquee adds to the selection instead of replacing it
    private val pickedPoints = ArrayList<PointRef>()
    private val pickedElements = ArrayList<Element>()
    private val pickedClicks = ArrayList<Vec2>()
    private var filledSlots = 0

    // ortho-path (turtle) state — the path itself is retained in the document while being drawn
    private var activePath: OrthoPath? = null
    private var pathAtEnd = true // which end of a resumed path is growing
    private var pathClosed = false
    private var previewSeg: Pair<Vec2, Vec2>? = null
    private var closePreview: List<Pair<Vec2, Vec2>> = emptyList() // the shape a closing click will make
    private var pathThickness: constructit.dsl.ScalarRef? = null // set for the WALL tool
    private var hoverWorld: Vec2? = null // last cursor position, so a typed length keeps its direction

    /**
     * Direct distance entry: digits typed while a leg is being previewed. The mouse supplies the
     * leg's *direction*, the keyboard its *length* — the same construction either way, so a leg
     * placed by typing is indistinguishable from one placed by clicking (OP-13).
     */
    var numericEntry: String = ""
        private set

    val pendingCount: Int get() = filledSlots

    fun setTool(id: String) {
        // an active path is a pending operation: switching tools finishes it (its thickness included)
        // rather than silently abandoning half-drawn state that no gesture ever committed
        finishPath()
        toolId = id
        resetPicks()
        statusHint = ""
        onChange()
    }

    private fun resetPicks() {
        pickedPoints.clear()
        pickedElements.clear()
        pickedClicks.clear()
        filledSlots = 0
        dragTarget = null
        dragFrame = null
        dragStart = null
        grabOffset = Vec2(0.0, 0.0)
        joinHints = emptyList()
        weldTarget = null
        attachTarget = null
        haloPos = null
        panning = false
        downScreen = null
        pendingToggle = null
        pendingCycle = null
        marqueeFrom = null
        marqueeTo = null
        marqueeAdds = false
        activePath = null
        pathAtEnd = true
        pathClosed = false
        previewSeg = null
        closePreview = emptyList()
        pathThickness = null
        hoverWorld = null
        numericEntry = ""
        snapHint = null
    }

    /**
     * The typed views of the selection's handle — the same writes its drag performs (OP-13). Only for a
     * selection of *one*: a field addresses one node, and with several elements selected there is no
     * single answer to show (nor to write back), so the inspector stays empty.
     */
    fun selectionFields(): List<HandleField> {
        // a placed group selected as a whole addresses its *frame* — the group's three degrees of freedom
        // (OP-16 step 2), which is exactly what dragging it writes, so drag and panel stay one operation
        selectedFrame()?.frameHandle?.let { return it.fields() }
        return if (selected.size == 1) selection?.handle?.fields() ?: emptyList() else emptyList()
    }

    /** Short name for the selection, for the inspector header. */
    fun selectionLabel(): String {
        selectedFrame()?.let { return "frame of ${it.name}" }
        if (selected.size > 1) return "${selected.size} elements"
        val el = selection ?: return ""
        val kind =
            when (el.handle) {
                is OrthoEdgeHandle -> "leg"
                is OrthoCornerHandle -> "corner"
                else -> el.kind.name.lowercase()
            }
        return "$kind ${el.id}"
    }

    /**
     * Hide or show the selected elements. Visibility is a **view** state: the saved file is a
     * construction (OP-18) and has no viewing section, so hiding is deliberately neither persisted nor
     * an undo step. A welded alias stays hidden — it is hidden *by construction*, and showing it would
     * draw a second point on top of its master.
     */
    fun setSelectionVisible(visible: Boolean): Int {
        var n = 0
        for (el in selected) {
            if (visible && doc.isWelded(el)) continue
            if (el.visible != visible) {
                el.visible = visible
                n++
            }
        }
        statusHint = if (n == 0) "Nothing to ${if (visible) "show" else "hide"}" else "${if (visible) "Shown" else "Hidden"} $n element${if (n == 1) "" else "s"}"
        onChange()
        return n
    }

    // ---- flat named groups (OP-16 step 1) ----

    /**
     * Group the selection under [name] (auto-numbered when blank). Organizational only: no geometry,
     * no node and no handle changes — a member drags exactly as it did before.
     */
    fun groupSelection(name: String = ""): Group? {
        if (selected.isEmpty()) {
            statusHint = "Select the elements to group first (Shift+click to add, or drag a box)"
            onChange()
            return null
        }
        val clash = selected.firstNotNullOfOrNull { el -> doc.groupOf(el)?.let { el to it } }
        if (clash != null) {
            statusHint = "${clash.first.id} is already in group ${clash.second.name} — an element is in at most one group; ungroup it first"
            onChange()
            return null
        }
        val g = doc.createGroup(name, selectedElements)
        if (g == null) {
            statusHint = "Could not group that selection"
            onChange()
            return null
        }
        selectedGroup = g // the selection now addresses the group it just became
        checkpoint()
        statusHint = "Grouped ${g.members.size} elements as ${g.name}"
        onChange()
        return g
    }

    /** Dissolve [g] — its elements stay, and stay selected. A placed group is unplaced first. */
    fun ungroup(g: Group): Boolean {
        val n = doc.groupMembers(g).size
        val wasPlaced = g.placed
        if (!doc.ungroup(g)) return false
        checkpoint()
        val note = if (wasPlaced) " (its frame went with it)" else ""
        statusHint = "Ungrouped ${g.name}$note — its $n element${if (n == 1) "" else "s"} stay"
        onChange()
        return true
    }

    // ---- placed groups (OP-16 step 2) ----

    /**
     * Place [g]: give it a frame and make the free points it owns frame-relative, so moving the group is
     * one write on the frame. Geometry is unchanged by construction — the retrofit is world-invariant.
     *
     * Refused, with the ambiguity named, when a free point the group owns is also used from outside: that
     * group cannot move independently, and which of the two should own the point is a modelling decision
     * the editor must not make silently (OP-16).
     */
    fun placeGroup(g: Group): Boolean {
        if (g.placed) {
            statusHint = "${g.name} is already placed — drag any member to move it, or Unplace it first"
            onChange()
            return false
        }
        val analysis = doc.analysePlacement(g)
        if (analysis.conflicts.isNotEmpty()) {
            val points = analysis.conflicts.map { it.point }.distinct()
            val consumers = analysis.conflicts.map { it.consumer.id }.distinct()
            val verb = if (points.size == 1) "is" else "are"
            statusHint =
                "Can't place ${g.name}: ${points.joinToString(", ")} $verb also used by " +
                "${consumers.joinToString(", ")} — include ${if (points.size == 1) "it" else "them"} in the group, " +
                "or this group cannot move independently"
            onChange()
            return false
        }
        if (analysis.candidates.isEmpty()) {
            statusHint = "Can't place ${g.name}: it owns no free point, so a frame would have nothing to move"
            onChange()
            return false
        }
        val result = doc.placeGroup(g)
        if (result == null) {
            statusHint = "Could not place ${g.name}"
            onChange()
            return false
        }
        checkpoint()
        // the deformable boundary is stated at placement time, because it is invisible on canvas: those
        // members are driven from outside the group and the frame does not move them (OP-16)
        val deforms =
            if (result.unfollowed.isEmpty()) {
                ""
            } else {
                " — ${result.unfollowed.joinToString(", ") { it.id }} " +
                    "${if (result.unfollowed.size == 1) "is" else "are"} driven from outside and will not follow it"
            }
        statusHint = "Placed ${g.name}: ${result.captured} point${if (result.captured == 1) "" else "s"} are now frame-relative$deforms"
        onChange()
        return true
    }

    /** Unplace [g]: its points become free again where they are, and the frame goes. */
    fun unplaceGroup(g: Group): Boolean {
        if (!doc.unplaceGroup(g)) {
            statusHint = "${g.name} is not placed"
            onChange()
            return false
        }
        checkpoint()
        statusHint = "Unplaced ${g.name} — its points are free again, exactly where they were"
        onChange()
        return true
    }

    /** Select every member of [g] — what clicking a member on the canvas does. */
    fun selectGroup(g: Group) {
        val members = doc.groupMembers(g)
        select(members, members.firstOrNull())
        selectedGroup = g
        val what = if (g.placed) " (placed — the panel shows its frame)" else ""
        statusHint = "Group ${g.name}: ${members.size} element${if (members.size == 1) "" else "s"} selected$what"
        onChange()
    }

    /** Hide/show a whole group. A view state like [setSelectionVisible], and not persisted either. */
    fun setGroupVisible(
        g: Group,
        visible: Boolean,
    ) {
        for (el in doc.groupMembers(g)) {
            if (visible && doc.isWelded(el)) continue
            el.visible = visible
        }
        statusHint = "Group ${g.name} ${if (visible) "shown" else "hidden"}"
        onChange()
    }

    /** Whether every live member of [g] is currently drawn — the panel's toggle state. */
    fun isGroupVisible(g: Group): Boolean = doc.groupMembers(g).all { it.visible || doc.isWelded(it) }

    /**
     * Write [value] (in the display unit of the field's dimension) into selection field [index].
     * False when there is no such field or it is driven by another node, which is the same answer
     * dragging gives.
     */
    fun writeSelectionField(
        index: Int,
        value: Double,
    ): Boolean {
        val f = selectionFields().getOrNull(index) ?: return false
        if (!f.writable) return false
        f.write(quantityOf(f.dim, value))
        checkpoint()
        onChange()
        return true
    }

    /** Set a transient status-bar note (e.g. panel feedback). */
    fun note(message: String) {
        statusHint = message
    }

    /** Help line for the active tool — shown in the status bar whenever there's no transient hint. */
    fun currentHelp(): String {
        snapHint?.let { if (it.linked) return "Snap: ${it.label} — Alt to place freely" }
        if (toolId == Tools.SELECT) return Tools.SELECT_HELP
        val tool = Tools.byId(toolId) ?: return ""
        // the panel is as much an input as the canvas (OP-13), so a tool still waiting for a scalar says
        // which one it wants next rather than describing clicks that will be thrown away
        if (toolScalars(tool) == null) return scalarPrompt(tool)
        val n = toolCount(tool)
        return if (n == 0) tool.help else "${tool.help} (count $n)"
    }

    fun render(target: DrawTarget) {
        SceneRenderer.render(
            doc, Evaluator(), camera, target, canvasW, canvasH, showGrid, haloPos, previewSeg, selected,
            snapHint?.pos, joinHints, closePreview,
            dimmed = if (dimScaffolding) doc.scaffoldingElements().toHashSet() else emptySet(),
            marquee = marqueeFrom?.let { f -> marqueeTo?.let { t -> f to t } },
            frames = selectedFrames(),
        )
    }

    /**
     * The frames to draw: those of placed groups with a selected member (OP-16 step 2). Shown on
     * selection only — the frame is a handle, not geometry, so it appears when it is addressable.
     */
    private fun selectedFrames(): List<FrameValue> {
        val ev = ev()
        return doc.groups
            .filter { g -> g.placed && doc.groupMembers(g).any { it in selected } }
            .mapNotNull { g -> g.frameNode?.let { (ev.eval(it) as? EvalResult.Ok)?.value as? FrameValue } }
    }

    fun wheel(
        screen: Vec2,
        deltaY: Double,
    ) {
        camera = camera.zoomAt(screen, if (deltaY < 0) 1.1 else 1.0 / 1.1)
        onChange()
    }

    /**
     * Press. [additive] is Shift held: in SELECT mode it makes the *click* toggle one element's
     * membership — which is why the toggle is applied on release and not here, since Shift held during
     * a drag means axis lock and must leave the selection alone.
     */
    fun pointerDown(
        screen: Vec2,
        button: PointerButton = PointerButton.PRIMARY,
        additive: Boolean = false,
    ) {
        downScreen = screen
        pendingToggle = null
        // panning is a button, not a mode: it works in every tool, and the shell reports Space+drag here
        if (button == PointerButton.MIDDLE) {
            panning = true
            lastScreen = screen
            return
        }
        if (toolId == Tools.SELECT) {
            val world = camera.screenToWorld(screen)
            // a vertex wins over the legs meeting at it; a leg drags perpendicular (OrthoEdgeHandle)
            val movable =
                HitTest.nearestFreePoint(doc, ev(), world, tolWorld())
                    ?: HitTest.nearestDraggableCurve(doc, ev(), world, tolWorld())
                    // last, so a dimension lying over the geometry it names never steals its grab
                    ?: HitTest.nearestDraggableAnnotation(doc, ev(), world, tolWorld())
            // an immovable element is still selectable, so its values can be read and the reason shown
            val hit = movable ?: HitTest.nearestSelectable(doc, ev(), world, tolWorld())
            // a press on nothing starts a rubber band; what it covers is selected on release (OP-16)
            if (hit == null) {
                marqueeFrom = world
                marqueeTo = world
                marqueeAdds = additive
                onChange()
                return
            }
            // A member of a **placed** group is addressed by the *gesture*, not by what the press does to
            // the selection: a drag moves the frame — or the member, when that member was the one already
            // reached alone — while the group/member cycle is a click semantic and is therefore applied on
            // release, exactly as Shift's toggle is. Deciding it after re-picking would make a click
            // followed by a drag of the same member move the member rather than the group.
            val placedGroup = if (additive) null else doc.placedGroupOf(hit)
            val reachedAlone = selected.size == 1 && selection === hit && selectedGroup == null
            var note = ""
            when {
                additive -> pendingToggle = hit
                placedGroup != null -> pendingCycle = hit
                else -> note = pickOnCanvas(hit)
            }
            // dragging the frame is one literal write, whatever the group contains, and the whole of it
            // follows rigidly — derived geometry included, since it is downstream (OP-16 step 2)
            val frameGroup = placedGroup?.takeIf { !reachedAlone }
            if (frameGroup != null) {
                val anchor = frameGroup.frameHandle?.origin(ev()) ?: world
                dragFrame = frameGroup
                grabOffset = world - anchor
                dragStart = anchor
                statusHint = note
                onChange()
                return
            }
            if (movable != null) {
                dragTarget = movable
                // drag by the *offset* from where the grab landed, not to the cursor outright:
                // picking has a tolerance, so writing the cursor position made the geometry jump to
                // it on the first move and then follow from there
                val anchor = grabAnchor(movable, world)
                grabOffset = world - anchor
                dragStart = anchor
                statusHint = note
            } else {
                statusHint = explainImmovable(hit)
            }
            onChange()
            return
        }
        if (toolId == Tools.ORTHO_PATH || toolId == Tools.WALL) {
            if (toolId == Tools.WALL && activePath == null && activeScalar == null) {
                statusHint = "Wall: select a thickness parameter in the panel first"
                onChange()
                return
            }
            pathClick(camera.screenToWorld(screen))
            return
        }
        if (toolId == Tools.OPENING) {
            openingClick(camera.screenToWorld(screen))
            return
        }
        if (toolId == Tools.BREAK_LEG) {
            val world = camera.screenToWorld(screen)
            statusHint =
                if (doc.breakOrthoLegNear(world, tolWorld())) {
                    "Segment broken — drag either half to open the corner"
                } else {
                    "Click a segment of an ortho path"
                }
            checkpoint()
            onChange()
            return
        }
        runToolClick(screen)
    }

    /** One click of a path tool (ortho path / wall): start a chain, append a leg, or close the loop. */
    private fun pathClick(raw: Vec2) {
        val path = activePath
        val s = pathSnap(raw)
        val world = s.pos
        val what = if (toolId == Tools.WALL) "Wall" else "Ortho path"
        if (path == null) {
            // clicking an open end of an existing path *continues* that path. Starting a separate path
            // welded there instead left a phantom corner: two paths cannot coalesce a straight-on step,
            // so extending produced a corner where the drawing read as one straight run.
            val resume = if (s.kind == SnapKind.POINT) s.target?.let { doc.resumableEnd(it) } else null
            if (resume != null) {
                val (existing, atEnd) = resume
                activePath = doc.resumeOrthoPath(existing, atEnd)
                pathAtEnd = atEnd
                if (toolId == Tools.WALL) pathThickness = activeScalar?.ref
                statusHint = "$what: extending this path — a step straight on lengthens the last segment"
            } else {
                val started = doc.startOrthoPath(world)
                activePath = started
                pathAtEnd = true
                if (toolId == Tools.WALL) pathThickness = activeScalar?.ref
                // starting *on* something should mean starting *at* it — link, so the path follows that
                // geometry instead of merely beginning at its coordinates
                val linked = s.linked && linkPathVertex(started.vertices.first().ref, s)
                statusHint =
                    if (linked) {
                        "$what starts on ${s.target?.id} (${s.label}); click the next point"
                    } else {
                        "$what: click the next point; click the start to close (Esc/double-click to finish)"
                    }
            }
        } else {
            // clicked the far end -> close the loop. Through the shared search like every other pick,
            // with a doubled tolerance because closing is a deliberate act worth making easy to hit.
            val far = (if (pathAtEnd) path.vertices.first() else path.vertices.last()).ref
            val onFar = HitTest.nearest(doc, ev(), world, tolWorld() * 2) { it.ref === far } != null
            if (onFar && path.vertices.size >= 3) {
                pathClosed = true
                finishPath()
                return
            }
            // a click back on the growing end is a repeat — the second half of a double-click, whose
            // dblclick then finishes the path — and must not leave a hairline leg behind
            val growing = (if (pathAtEnd) path.vertices.last() else path.vertices.first()).ref
            if (HitTest.nearest(doc, ev(), world, tolWorld()) { it.ref === growing } != null) {
                onChange()
                return
            }
            val v = doc.extendOrthoPath(path, pathAtEnd, world)
            // reaching other geometry ends the run: connect this end and finish, the open analogue of
            // closing a loop by clicking the start
            if (v != null && s.linked && linkPathVertex(v.ref, s)) {
                snapHint = null
                finishPath()
                statusHint = "$what ends on ${s.target?.id} (${s.label})"
                onChange()
                return
            }
        }
        hoverWorld = world
        previewSeg = null
        snapHint = null
        onChange()
    }

    /**
     * A key pressed while the canvas has focus, as a pure controller entry point. Digits feed the
     * direct distance entry; Enter places the previewed leg at the typed length (or finishes the
     * path); Escape cancels a pending entry first, then finishes. Returns true when consumed.
     */
    fun key(key: String): Boolean {
        val pathActive = activePath != null
        return when {
            pathActive && key.length == 1 && (key[0].isDigit() || key == ".") -> {
                numericEntry += key
                refreshPreview()
                onChange()
                true
            }
            key == "Backspace" && numericEntry.isNotEmpty() -> {
                numericEntry = numericEntry.dropLast(1)
                refreshPreview()
                onChange()
                true
            }
            key == "Escape" && numericEntry.isNotEmpty() -> {
                numericEntry = ""
                refreshPreview()
                onChange()
                true
            }
            key == "Enter" && numericEntry.isNotEmpty() -> commitTypedLeg()
            // a repeating tool (Outline) commits on Enter and abandons on Escape
            key == "Enter" && !pathActive -> finishRepeatingTool()
            key == "Escape" && !pathActive && filledSlots > 0 -> {
                resetPicks()
                statusHint = ""
                onChange()
                true
            }
            // with nothing pending, Escape is "select nothing" — the keyboard form of clicking empty space
            key == "Escape" && !pathActive && selected.isNotEmpty() -> {
                clearSelection()
                statusHint = "Selection cleared"
                onChange()
                true
            }
            key == "Escape" || key == "Enter" -> {
                finishPath()
                true
            }
            else -> false
        }
    }

    /**
     * Place the previewed leg. [previewSeg] already carries the exact typed length, so this is the
     * very same call a click makes — only the endpoint came from the keyboard.
     */
    private fun commitTypedLeg(): Boolean {
        val path = activePath ?: return false
        val end = previewSeg?.second ?: return false
        numericEntry = ""
        val placed = doc.addOrthoVertex(path, end) != null
        refreshPreview()
        statusHint = if (placed) "" else "That length would make a zero-length leg"
        onChange()
        return true
    }

    /**
     * The rubber-band leg: direction from the cursor (snapped to an axis), length from the typed
     * entry when there is one, so the preview shows exactly what Enter will place.
     */
    private fun refreshPreview() {
        val path = activePath
        val hover = hoverWorld
        if (path == null || hover == null) {
            previewSeg = null
            return
        }
        // hovering the far end offers to close: preview the *closed* shape, because closing moves a
        // corner into line and a band merely reaching for the start would promise something else
        val far = (if (pathAtEnd) path.vertices.first() else path.vertices.last()).ref
        if (path.vertices.size >= 3 && HitTest.nearest(doc, ev(), hover, tolWorld() * 2) { it.ref === far } != null) {
            closePreview = doc.orthoClosePreview(path)
            previewSeg = null
            if (closePreview.isNotEmpty()) statusHint = "Click to close the loop — the last corner moves into line with the start"
            return
        }
        closePreview = emptyList()
        val base = doc.orthoLegPreview(path, hover, pathAtEnd) ?: return
        val typed = numericEntry.toDoubleOrNull()
        previewSeg =
            if (typed == null) {
                base
            } else {
                val (from, to) = base
                val horizontal = kotlin.math.abs(to.x - from.x) >= kotlin.math.abs(to.y - from.y)
                val sign = if ((if (horizontal) to.x - from.x else to.y - from.y) < 0) -1.0 else 1.0
                from to if (horizontal) Vec2(from.x + sign * typed, from.y) else Vec2(from.x, from.y + sign * typed)
            }
        if (numericEntry.isNotEmpty()) statusHint = "Leg length $numericEntry mm — Enter to place, Esc to cancel"
    }

    /**
     * One click of the opening tool: add an interval feature to the thick path under the cursor. It adds
     * a description, not geometry — the footprint stays whole and the plan gap is drawn from it (OP-21).
     */
    private fun openingClick(world: Vec2) {
        val w = activeScalar
        if (w == null) {
            statusHint = "Opening: select a width parameter in the panel first"
            onChange()
            return
        }
        statusHint = if (doc.addIntervalAt(world, w.ref, tolWorld() * 2)) "Opening added" else "Click on a wall to place an opening"
        checkpoint()
        onChange()
    }

    /** Finish the current path (Esc / double-click / close / tool switch); for WALL, thicken it. */
    fun finishPath() {
        val path = activePath ?: return
        if (pathClosed) doc.closeOrthoPath(path) // snaps the last coordinate to fit, adds the closing leg
        val t = pathThickness
        if (t != null && path.vertices.size >= 2) doc.buildThickPath(path, t, justification)
        doc.discardOrthoPath(path) // a path that never got a second vertex isn't a path
        activePath = null
        pathAtEnd = true
        pathClosed = false
        previewSeg = null
        closePreview = emptyList()
        pathThickness = null
        checkpoint() // the whole path — start, legs, close, footprint — commits as one operation
        statusHint = ""
        onChange()
    }

    /**
     * A plain click's selection semantics, returning the note for the status bar.
     *
     * A grouped element selects **its whole group** (OP-16), with the clicked element as primary — that
     * is what a group is for. **Clicking it again reaches the member alone**, so its fields stay
     * addressable and no degree of freedom becomes unreachable through grouping (OP-13); a further
     * click goes back to the group. One mechanism, no modifier, and the status line says so — Alt was
     * rejected here because it already means "place freely / keep flattened corners" during a gesture.
     */
    private fun pickOnCanvas(hit: Element): String {
        val group = doc.groupOf(hit)
        if (group == null) {
            select(listOf(hit), hit)
            return ""
        }
        val members = doc.groupMembers(group)
        // the *same* element clicked again, while its group is what is selected — so clicking a different
        // member keeps addressing the group, as it did before frames existed
        val reachedAlone = selection === hit && (selectedGroup === group || selected.size > 1)
        if (reachedAlone) {
            select(listOf(hit), hit)
            val what = if (group.placed) " — dragging it moves it inside the frame" else ""
            return "${hit.id} alone (of group ${group.name})$what — click again for the whole group"
        }
        select(members, hit)
        selectedGroup = group
        if (group.placed) {
            return "Group ${group.name} is placed: dragging moves its frame (x / y / angle in the panel) — " +
                "click ${hit.id} again to reach it alone"
        }
        return "Group ${group.name}: ${members.size} elements — click ${hit.id} again to reach it alone"
    }

    /** Take everything the rubber band covers (OP-16); Shift adds it to what was already selected. */
    private fun finishMarquee(
        from: Vec2,
        to: Vec2,
    ) {
        val hits = HitTest.within(doc, ev(), from, to)
        val kept = if (marqueeAdds) selectedElements else emptyList()
        select(kept + hits.filter { it !in kept }, hits.lastOrNull() ?: selection)
        statusHint =
            when {
                selected.isEmpty() -> "Nothing in the box"
                selected.size == 1 -> selectionLabel() + " selected"
                else -> "${selected.size} elements selected"
            }
    }

    fun pointerMove(screen: Vec2) {
        // panning first: it is a button, so it works under every tool — including the ones whose own
        // move handler returns early to show a preview
        if (panning) {
            camera = camera.pan(screen.x - lastScreen.x, screen.y - lastScreen.y)
            lastScreen = screen
            onChange()
            return
        }
        if (toolId == Tools.ORTHO_PATH || toolId == Tools.WALL) {
            val world = camera.screenToWorld(screen)
            val s = pathSnap(world)
            snapHint = s.takeIf { it.linked }
            // a lingering note would otherwise outrank the snap label, which is about the *next* click
            if (snapHint != null) statusHint = ""
            hoverWorld = snapHint?.pos ?: world
            refreshPreview()
            onChange()
            return
        }
        if (placesAPoint()) {
            snapHint = snap(camera.screenToWorld(screen)).takeIf { it.linked }
            if (snapHint != null) statusHint = ""
            onChange()
            return
        }
        when {
            dragFrame != null -> {
                // one write on the frame source moves the whole group, derived geometry included — the
                // O(1) move OP-16 is built around, and axis lock applies to it exactly as to a point
                val g = dragFrame!!
                g.frameHandle?.drag(axisLockedFrom(camera.screenToWorld(screen) - grabOffset), ev())
                onChange()
            }
            dragTarget != null -> {
                val el = dragTarget!!
                val world = axisLocked(camera.screenToWorld(screen) - grabOffset, el)
                el.handle?.drag(world, ev())
                // a free point and an open path end can connect on release; nothing else can
                if (canConnect(el)) updateMagnet(el, world) else clearMagnet()
                // a jog dragged shut is *visually* already a single leg, so nothing needs to change
                // yet — but mark the corners that releasing will remove, and say so (OP-19)
                val flattened = flattenedEnds(el)
                joinHints = flattened.mapNotNull { (path, i) -> legPoint(path.legs[i]) }
                if (flattened.isNotEmpty()) {
                    val n = flattened.size
                    statusHint = "Release to join — ${if (n == 1) "the flattened corner" else "$n flattened corners"} will be removed (Alt to keep)"
                }
                onChange()
            }
            marqueeFrom != null -> {
                marqueeTo = camera.screenToWorld(screen)
                onChange()
            }
        }
    }

    fun pointerUp(screen: Vec2) {
        val from = marqueeFrom
        marqueeFrom = null
        marqueeTo = null
        if (from != null) {
            // a press-and-release on empty space is a click, and a plain click on nothing deselects —
            // a Shift+click there adds nothing and so leaves the selection alone
            when {
                movedSince(screen) -> finishMarquee(from, camera.screenToWorld(screen))
                !marqueeAdds -> clearSelection()
            }
            downScreen = null
            onChange()
            return
        }
        // whether the gesture *moved* — read while [downScreen] still holds where the press landed, since
        // the deferred click semantics below clear it
        val moved = movedSince(screen)
        val toggle = pendingToggle
        val cycle = pendingCycle
        pendingToggle = null
        pendingCycle = null
        val dragged = dragTarget
        val movedFrame = dragFrame
        val weld = weldTarget
        val attach = attachTarget
        dragTarget = null
        dragFrame = null
        dragStart = null
        joinHints = emptyList()
        clearMagnet() // clear before rendering so the magnet halo doesn't linger
        panning = false
        // Shift+click toggles membership — but only when the gesture was a *click*: the same Shift is
        // axis lock, so a Shift-drag reshapes geometry and leaves the selection exactly as it was
        if (toggle != null && !moved) {
            if (toggle in selected) {
                selected.remove(toggle)
                if (selection === toggle) selection = selected.lastOrNull()
            } else {
                selected.add(toggle)
                selection = toggle
            }
            statusHint = if (selected.isEmpty()) "Nothing selected" else "${selected.size} element${if (selected.size == 1) "" else "s"} selected"
            onChange() // a release otherwise repaints only when the drag changed the model
        }
        // the deferred group/member cycle: a click on a member already reached alone goes back to the whole
        // group, while the very same press that *moved* left the member selected and edited it
        if (cycle != null && !moved) {
            statusHint = pickOnCanvas(cycle)
            onChange()
        }
        downScreen = null
        if (dragged != null) {
            val ortho = dragged.handle is OrthoCornerHandle
            if (weld != null) {
                val ok = if (ortho) doc.weldOrthoEndpointToPoint(dragged, weld) else doc.weld(dragged, weld)
                if (ok) {
                    statusHint = "Joined ${dragged.id} onto ${weld.id}"
                    onChange()
                }
            } else if (attach != null) {
                val ok = if (ortho) doc.attachOrthoEndpointToCurve(dragged, attach) else doc.attachToCurve(dragged, attach)
                if (ok) {
                    statusHint = "Attached ${dragged.id} to ${attach.id}"
                    onChange()
                }
            }
        }
        if (movedFrame != null && moved) {
            // a frame move commits like any other drag: one operation, one undo step. It also *selects* the
            // group it moved — the press deferred its selection change, and having moved a group without it
            // ending up selected would leave the frame's fields unreachable right after using them.
            val members = doc.groupMembers(movedFrame)
            select(members, members.firstOrNull())
            selectedGroup = movedFrame
            checkpoint()
            statusHint = "Moved ${movedFrame.name}"
            onChange()
        }
        if (dragged != null) {
            joinFlattenedEnds(dragged)?.let {
                select(listOf(it), it)
                statusHint = "Joined into ${it.id} — the flattened corner is gone"
                onChange()
            }
            // the release is where a drag commits — moves, welds, attaches and joins are one operation
            checkpoint()
        }
    }

    /** Whether the pointer travelled far enough since the press for the gesture to count as a drag. */
    private fun movedSince(screen: Vec2): Boolean = downScreen?.let { (screen - it).length() > CLICK_SLOP_PX } ?: false

    /**
     * Apply [axisLock]: keep only the component the gesture is dominated by, relative to where the
     * drag started. A leg already moves on a single axis of its own, so the lock leaves it alone
     * rather than making it inert when the cursor happens to travel along it.
     */
    private fun axisLocked(
        world: Vec2,
        el: Element,
    ): Vec2 {
        if (el.handle is OrthoEdgeHandle) return world
        return axisLockedFrom(world)
    }

    /** [axisLocked] without an element: what a frame drag (OP-16) is restricted to. */
    private fun axisLockedFrom(world: Vec2): Vec2 {
        val start = dragStart
        if (!axisLock || start == null) return world
        return if (kotlin.math.abs(world.x - start.x) >= kotlin.math.abs(world.y - start.y)) {
            Vec2(world.x, start.y)
        } else {
            Vec2(start.x, world.y)
        }
    }

    /**
     * The flattened jogs at [el]'s own ends — see [Document.collapseCandidates]. Both ends can be
     * flattened by one drag (reverting a section that was broken out twice), so this is a list.
     * Suppressed while [snapEnabled] is off: Alt means "leave the model as I put it".
     */
    private fun flattenedEnds(el: Element): List<Pair<OrthoPath, Int>> =
        if (!snapEnabled) emptyList() else doc.collapseCandidates(el).filter { (path, i) -> doc.canJoinLeg(path, i, tolWorld()) }

    /**
     * Join away the jogs the drag flattened, at the dragged segment's own ends only. A leg has two
     * ends, so at most two joins; each one replaces the dragged leg with the merged one, which is then
     * what the other end is checked against.
     */
    private fun joinFlattenedEnds(dragged: Element): Element? {
        var target = dragged
        var merged: Element? = null
        var guard = 0
        while (guard++ < 2) {
            val (path, i) = flattenedEnds(target).firstOrNull() ?: break
            // keep the value of the half that did *not* move, so the dragged section snaps to it
            val draggedLeg = doc.legOf(target)?.second
            val stationary = listOf(i - 1, i + 1).firstOrNull { it != draggedLeg && it in 0 until path.legCount }
            val m = doc.joinCollapsedLeg(path, i, if (draggedLeg == null) null else stationary?.let { legPerp(path, it) }) ?: break
            merged = m
            target = m
        }
        return merged
    }

    /** Leg [i]'s perpendicular coordinate — the value a join keeps when this is the stationary half. */
    private fun legPerp(
        path: OrthoPath,
        i: Int,
    ): Double? {
        val seg = (ev().valueOf(path.legs[i].ref) as? constructit.core.SegmentValue)?.seg ?: return null
        return if (path.legAxis(i) == 0) seg.a.y else seg.a.x
    }

    /**
     * Where on [el] a grab at [world] landed: the point itself, or the point of the curve under the
     * cursor. The difference between that and the cursor is held for the rest of the drag, so geometry
     * moves *with* the pointer instead of snapping to it.
     */
    private fun grabAnchor(
        el: Element,
        world: Vec2,
    ): Vec2 =
        (ev().valueOf(el.ref) as? PointValue)?.p
            ?: el.annotation?.anchor(ev())
            ?: Snap.legPoint(ev(), el, world)
            ?: world

    /** Where a (possibly zero-length) leg sits, for marking it on the canvas. */
    private fun legPoint(el: Element): Vec2? =
        (ev().valueOf(el.ref) as? constructit.core.SegmentValue)?.seg?.let { Vec2((it.a.x + it.b.x) / 2, (it.a.y + it.b.y) / 2) }

    /**
     * Whether dropping [el] can join it to something: a free point, or an open path end. A point held
     * frame-relative by a placed group (OP-16) is neither — its position is already derived, so the weld
     * would be refused on release and the magnet must not offer it.
     */
    private fun canConnect(el: Element): Boolean =
        (el.kind == ElementKind.POINT && !doc.isFramed(el)) || (el.handle as? OrthoCornerHandle)?.isEndpoint == true

    /** True when the active tool's next slot creates a point — the case a snap marker is useful for. */
    private fun placesAPoint(): Boolean {
        val tool = Tools.byId(toolId) ?: return false
        val slot = tool.slots.getOrNull(filledSlots) ?: return false
        return slot == SlotKind.PLACE_POINT || slot == SlotKind.POINT
    }

    private fun clearMagnet() {
        weldTarget = null
        attachTarget = null
        haloPos = null
    }

    /**
     * Magnet: prefer a nearby point to weld onto; otherwise a nearby curve to attach onto. Sets
     * [haloPos] to the point (or the projection onto the curve) where the drag will land.
     */
    private fun updateMagnet(
        dragged: Element,
        world: Vec2,
    ) {
        val ev = ev()
        // points win over curves at equal distance, so they are asked for first. Both go through the
        // one shared search, so a position past a segment's end is as much a miss here as it is when
        // placing geometry — the magnet used to measure to the infinite carrier line and match anyway.
        HitTest.nearest(doc, ev, world, tolWorld()) { it !== dragged && it.isPoint }?.let { point ->
            // a circular join is refused on release, so it must not be offered here either — and say why,
            // because a point that lights up for everything else and not for this one reads as a glitch
            if (doc.joinWouldCycle(dragged, point)) {
                clearMagnet()
                statusHint = "Can't join ${dragged.id} onto ${point.id}: ${point.id} already follows ${dragged.id}."
                return
            }
            weldTarget = point
            attachTarget = null
            haloPos = (ev.valueOf(point.ref) as? PointValue)?.p
            return
        }
        val curve =
            HitTest.nearest(doc, ev, world, tolWorld()) {
                it !== dragged && it.isCurve && doc.curveProjection(dragged, it) != null
            }
        if (curve != null) {
            attachTarget = curve
            weldTarget = null
            haloPos = doc.curveProjection(dragged, curve)
        } else {
            clearMagnet()
        }
    }

    /**
     * Finish a repeating tool (Enter, or clicking the first pick again). Builds from whatever has been
     * collected; too few picks just cancels, since a boundary of one curve is not a boundary.
     */
    fun finishRepeatingTool(): Boolean {
        val tool = Tools.byId(toolId) ?: return false
        if (!tool.repeating || filledSlots == 0) return false
        val picks = Picks(pickedPoints.toList(), pickedElements.toList(), pickedClicks.lastOrNull() ?: Vec2(0.0, 0.0), pickedClicks.toList())
        val scalars = toolScalars(tool)
        when {
            scalars == null -> statusHint = scalarPrompt(tool)
            filledSlots >= 2 -> {
                doc.recordingTool(tool.id, picks, scalars) { tool.build(doc, picks, scalars.map { it.ref }) }
                checkpoint()
                statusHint = ""
            }
            else -> statusHint = "${tool.label}: needs at least two curves"
        }
        resetPicks()
        onChange()
        return true
    }

    /**
     * The scalars [tool] consumes: the last of the panel picks, in pick order. Null when too few have been
     * picked — the caller then says which ones are still wanted ([scalarPrompt]).
     */
    private fun toolScalars(tool: ToolDef): List<ScalarEntry>? {
        val need = tool.scalars.size
        if (scalarPicks.size < need) return null
        return scalarPicks.takeLast(need)
    }

    /** What is still missing for [tool]'s scalar inputs, in the user's terms. */
    private fun scalarPrompt(tool: ToolDef): String {
        val have = scalarPicks.size
        val wanted = tool.scalars
        val missing = wanted.drop(have)
        val had = if (have == 0 || wanted.size == 1) "" else " (${wanted.take(have).joinToString(", ")} picked)"
        return "${tool.label}: click a parameter or measurement in the panel for ${missing.joinToString(", then ")}$had"
    }

    /** The structural count [tool] will build with — see [count]. Zero for a tool that needs none. */
    private fun toolCount(tool: ToolDef): Int = if (tool.minCount == 0) 0 else maxOf(count, tool.minCount)

    private fun runToolClick(screen: Vec2) {
        val tool = Tools.byId(toolId) ?: return
        val world = camera.screenToWorld(screen)
        // a repeating tool closes when the first pick is clicked again — the boundary is complete
        if (tool.repeating && filledSlots >= 2) {
            val again = HitTest.nearest(doc, ev(), world, tolWorld()) { it === pickedElements.firstOrNull() }
            if (again != null) {
                finishRepeatingTool()
                return
            }
        }
        // A tool may have *no* geometry slots at all: its inputs are scalars (point from coordinates), so
        // the click only says "now". Handled by the same completion path below rather than as a special
        // case, which is why the slot lookup is allowed to come back empty.
        val slot = if (tool.repeating) tool.slots.lastOrNull() else tool.slots.getOrNull(filledSlots)
        val picked =
            when (slot) {
                null -> true
                SlotKind.PLACE_POINT, SlotKind.POINT -> {
                    pickedPoints.add(placePoint(world))
                    true
                }
                SlotKind.EXISTING_POINT -> pickElement(world) { it.isPoint }
                SlotKind.CURVE -> pickElement(world) { it.isCurve }
                SlotKind.LINE -> pickElement(world) { it.isLinear } // a segment or ray also carries a line
                SlotKind.CIRCLE -> pickElement(world) { it.kind == ElementKind.CIRCLE }
                SlotKind.SEGMENT -> pickElement(world) { it.kind == ElementKind.SEGMENT }
                SlotKind.GEOMETRY -> pickElement(world) { true }
                SlotKind.ON_CIRCLE_POINT -> pickElement(world) { it.handle is OnCircleHandle }
                SlotKind.CENTRIC -> pickElement(world) { it.kind == ElementKind.CIRCLE || it.kind == ElementKind.ARC }
                SlotKind.SIDE -> true // captures the click position only; creates nothing
            }
        // existing-only slots do NOT create anything on a miss — just hint and wait
        if (!picked) {
            statusHint = tool.help
            onChange()
            return
        }
        if (slot != null) {
            filledSlots++
            pickedClicks.add(world)
        }

        if (tool.repeating) {
            statusHint = "${tool.help} ($filledSlots picked)"
            onChange()
            return
        }
        if (filledSlots >= tool.slots.size) {
            val scalars = toolScalars(tool)
            if (scalars == null) {
                statusHint = scalarPrompt(tool)
                resetPicks()
                onChange()
                return
            }
            val picks = Picks(pickedPoints.toList(), pickedElements.toList(), world, pickedClicks.toList(), count = toolCount(tool))
            doc.recordingTool(tool.id, picks, scalars) { tool.build(doc, picks, scalars.map { it.ref }) }
            checkpoint() // the tool application — earlier slot clicks were only halves of it
            resetPicks()
            statusHint = ""
        } else {
            statusHint = "${tool.help} (${tool.slots.size - filledSlots} more)"
        }
        onChange()
    }

    private fun pickElement(
        world: Vec2,
        filter: (Element) -> Boolean,
    ): Boolean {
        val el = HitTest.nearest(doc, ev(), world, tolWorld(), filter) ?: return false
        pickedElements.add(el)
        return true
    }
}
