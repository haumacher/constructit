package constructit.editor

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.FrameValue
import constructit.core.PointValue
import constructit.core.ScalarValue
import constructit.dsl.PointRef
import constructit.dsl.valueOf
import constructit.geom.Justification
import constructit.geom.Vec2
import constructit.units.Dimension
import constructit.units.mm

/** Undo depth bound: snapshots are whole scripts, so the stack must not grow with the session. */
private const val UNDO_CAP = 100

/** Below this screen distance a press-and-release is a *click*, not a drag. */
private const val CLICK_SLOP_PX = 3.0

/**
 * How near the previous click a new one must land to count as **the same spot** — the repeat that steps the
 * pick cycle on instead of starting a new pick (see [Editor.pickAt]).
 *
 * Deliberately *the same notion* as [CLICK_SLOP_PX], and one named constant: within it the pointer has not
 * travelled, so "click again" means exactly what it says, and click-vs-drag can never drift apart from
 * repeat-vs-new.
 */
private const val REPEAT_CLICK_PX = CLICK_SLOP_PX

/** What the status line says when the active parameter is switched off (see [Editor.clickScalar]). */
private const val NO_ACTIVE_PARAMETER = "no parameter active — tools use their defaults"

/** How many panel picks are remembered — no tool asks for more scalars than this. */
private const val SCALAR_PICK_MEMORY = 4

/**
 * Upper bound on a structural count, so a slip of the keyboard cannot build a million nodes — and, since a
 * whole group can fill a geometry slot (OP-16), the same bound on the **copies** one step may build:
 * `members × (count-1)`. For a single element the two are the same number, so nothing about the
 * single-element case changes.
 */
private const val MAX_COUNT = 512

/**
 * How every ending of an ortho run ends its sentence. One wording for weld, attach and close, because the
 * thing the user has to read off it is the same in all three: drawing stopped, and the next click starts
 * something new rather than continuing this (see [Editor.markTerminal]).
 */
private const val RUN_FINISHED = " — the run is finished; click a point or leg to start the next one"

/**
 * Which button a pointer gesture uses. In SELECT mode they mean different things (OP-16): PRIMARY
 * picks, rubber-bands a marquee and drags geometry; MIDDLE pans, in *every* tool — panning is a
 * button, not a mode, so the view can be moved without putting the tool down. The browser shell
 * reports MIDDLE for Space+drag as well, so a one-button mouse can still pan.
 */
enum class PointerButton { PRIMARY, MIDDLE }

/**
 * One thing a SELECT click could mean — an entry of the ranked pile [Editor.pickAt] collects.
 *
 * There is **one** cycle machine over this list, replacing the two hand-built two-element cycles that grew
 * separately: the group/member reach (OP-16) and jamb-vs-leg (OP-21). Each entry is a *complete* answer to
 * "what is selected", so applying one is history-free — which is what lets a click apply any of them without
 * a rule of its own.
 */
private sealed class Candidate {
    /** A whole group, as a click on one of its members addresses it first (OP-16), with the clicked member. */
    class Whole(
        val group: Group,
        val primary: Element,
    ) : Candidate()

    /** One element alone — ungrouped geometry, or a member deliberately reached past its group. */
    class One(
        val element: Element,
    ) : Candidate()

    /** An opening's jamb (OP-21): a selection that owns no [Element] at all. */
    class Opening(
        val jamb: Jamb,
    ) : Candidate()
}

/**
 * What one SELECT press found at a position: every [Candidate] within the pick tolerance, ranked, plus the
 * two facts the *press* needs and only it can decide — what a drag from here would move, and whether a jamb
 * took the grab.
 */
private class PickPile(
    val candidates: List<Candidate>,
    /** What a drag would move: a draggable point, an ortho leg, a dimension's offset — today's rule verbatim. */
    val movable: Element?,
    /** The jamb that took the grab, i.e. nothing movable outranked it (OP-21); null when one did. */
    val grabJamb: Jamb?,
    /** The element the press addresses at all, movable or merely selectable; null when it hit nothing. */
    val hit: Element?,
)

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
        // another drawing entirely: its spaces are its own, so the remembered views go with the old one
        spaceCameras.clear()
        adopt(fresh, keepSpace = false)
        // a loaded file starts its own history — earlier snapshots describe a different document
        undoStack.clear()
        redoStack.clear()
        lastCommitted = DocumentFormat.save(fresh)
        // What the *load* had to decide says so here, or it says it nowhere: a migration finding
        // (`Document.loadNotes`, OP-18's *Versioning & migration*) names an element whose stored literal was
        // ambiguous, and until now it was published into the document's one-shot note that only a *tool* run
        // reads — so it was overwritten by this very line. It stays until the next user action, like every
        // other status line.
        statusHint = loadNote(fresh) ?: ""
        onChange()
    }

    /** The loaded document's migration findings as one line — the first, and how many more (OP-18). */
    private fun loadNote(fresh: Document): String? {
        val notes = fresh.loadNotes
        fresh.takeNote() // it is being said here instead, so no later tool run repeats it
        if (notes.isEmpty()) return null
        val more = if (notes.size == 1) "" else " (and ${notes.size - 1} more)"
        return "Loaded with a note: ${notes.first()}$more"
    }

    /** Swap [fresh] in, resetting every transient reference into the old document (selection, picks). */
    private fun adopt(
        fresh: Document,
        keepSpace: Boolean = true,
    ) {
        // Stay in the space the user is looking at (OP-17). A replayed script ends in whatever space its
        // last step was built in, which for an undo is *not* where the user is: undoing a circle drawn on a
        // face must not also throw the view back to the plan. So the view wins where it still exists — and
        // when it does not (the undo removed the space itself) the replayed answer stands, with the camera
        // following it.
        val looking = doc.activeSpace.name
        doc = fresh
        if (keepSpace && looking != fresh.activeSpace.name) fresh.spaceNamed(looking)?.let { fresh.activeSpace = it }
        if (fresh.activeSpace.name != looking) {
            spaceCameras[looking] = camera
            camera = spaceCameras[fresh.activeSpace.name] ?: cameraFor(fresh.activeSpace)
        }
        clearSelection()
        // it names elements of the document being replaced (and a tool of it), so it cannot survive
        createDialog = null
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
    //
    // **A value typed into an armed tool is half of that tool's operation, never a step of its own.**
    // Typing it creates an ordinary parameter (OP-13, see [commitTypedScalar]) — but "circle of radius 7"
    // is *one* thing the user did, so the parameter is held pending and whichever checkpoint commits next
    // seals it: the tool's own build takes parameter and geometry as one snapshot, and one undo removes
    // both. Abandon the gesture instead — Esc, or arming another tool — and [resetPicks] **retracts** it,
    // so it leaves no step and no panel row, exactly as a cancelled tool's stray points leave none. The
    // invariant both halves protect is that no snapshot ever contains half an operation, and no
    // parameter that only existed to feed one can outlive it unaccounted for.
    private val undoStack = ArrayList<String>()
    private val redoStack = ArrayList<String>()
    private var lastCommitted: String = DocumentFormat.save(doc)

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Record the document as one committed operation — the seam every user-level edit funnels through. */
    fun checkpoint() {
        // whatever operation is committing now owns any parameters typed on the way to it: sealing them
        // here is what makes "type 7, click" one undo step (see commitTypedScalar / resetPicks)
        pendingTypedParams.clear()
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
        if (targets.isEmpty()) {
            // A selected **opening** (OP-21) owns no element, so Delete cannot reach it — and it has to say
            // so rather than do nothing, because the jamb is highlighted and its fields are live, which makes
            // the selection plainly there. Removing an interval is an ordinary step-unit delete and the
            // journal already supports it independently of its siblings (see [Document.dependentSteps]); what
            // is missing is only the route from a selection with no element to that step. Recorded as a cut.
            selectedJamb?.let {
                statusHint =
                    "Delete can't reach an opening yet: it has no element of its own. Delete the wall " +
                    "(${doc.nameOf(it.path.footprint)}) to remove it with its openings."
                onChange()
            }
            return false
        }
        val roots = ArrayList<Step>()
        for (el in targets) {
            val root = doc.creatingStep(el)
            if (root == null) {
                statusHint = "${doc.nameOf(el)} has no construction step to remove"
                onChange()
                return false
            }
            roots.add(root)
        }
        // one closure over all the roots at once — see [Document.dependentSteps]
        val droppedSteps = doc.dependentSteps(roots.toHashSet())
        // A macro definition with live instances is **refused**, not cascaded (OP-6): an instance's step
        // names the *tool*, not the definition's elements, so taking the instances too would delete work
        // the user did not select — and leaving them is not an option either, since their element count
        // is structural (replay would reject the file, which is not the same as OP-3 invalidity). So the
        // honest answer is to say which instances are in the way.
        val losses = doc.macroLosses(roots.toHashSet(), droppedSteps)
        if (losses.isNotEmpty()) {
            val (def, instances) = losses.first()
            statusHint =
                "Can't delete that: it defines tool ${def.name}, used by ${instances.size} instance " +
                "element${if (instances.size == 1) "" else "s"} (${instances.take(4).joinToString(", ") { doc.nameOf(it) }}) — " +
                "delete the instances first"
            onChange()
            return false
        }
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
        val what = if (targets.size == 1) doc.nameOf(targets[0]) else "${targets.size} elements"
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
                // A tool whose slots are all clicked was waiting for exactly this, so picking the row
                // finishes it — the same completion a typed number reaches (see [commitTypedScalar]).
                // Guarded on there being picks to finish, so arming a tool *after* picking a parameter (the
                // usual order) is untouched.
                if (filledSlots > 0) maybeCompleteTool(null)
            }
        }

    /**
     * A click on a parameter (or measurement) row — the panel's route into a scalar slot (OP-13: the panel is
     * as much an input as the canvas). One entry point, so the shell only routes and the decision stays here.
     *
     * Clicking the **active** row again switches it **off**. Without that there is no way to un-pick a
     * parameter, and a pick of the wrong dimension is not merely idle: a *defaulted* slot adopts any pick of
     * its own kind (see [toolScalars]), so one stray dimensionless pick shadows every dimensionless default
     * for the rest of the session — Midpoint keeps building the ratio point of the factor picked long ago.
     * Escape does the same with no gesture pending (see [key]).
     */
    fun clickScalar(entry: ScalarEntry?) {
        if (entry != null && activeScalar !== entry) {
            activeScalar = entry
            onChange()
            return
        }
        clearActiveScalar()
        onChange()
    }

    /**
     * Drop the active parameter, and forget it as a *pick* too — leaving it in the memory would keep it
     * feeding the next tool that wants a scalar of its kind, which is the whole thing being switched off.
     */
    private fun clearActiveScalar(): Boolean {
        val entry = activeScalar ?: return false
        activeScalar = null
        scalarPicks.removeAll { it === entry }
        statusHint = NO_ACTIVE_PARAMETER
        return true
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

    /**
     * The **opening jamb** the selection addresses (OP-21), or null.
     *
     * A selection that owns no [Element]: an opening is a description carried by a thick path, and its jambs
     * are a drawing, so there is nothing to put in [selected]. What it buys is exactly what selecting
     * anything else buys — the inspector's typed fields (OP-13) and an emphasis on the canvas — and it is
     * deliberately *not* accompanied by selecting the wall, because Delete would then remove the whole wall
     * after a click that pointed at one opening.
     */
    var selectedJamb: Jamb? = null
        private set

    /** Select the opening [jamb] addresses, replacing any element selection: they are alternatives. */
    private fun selectJamb(jamb: Jamb) {
        selected.clear()
        selection = null
        selectedGroup = null
        selectedJamb = jamb
    }

    /** Replace the selection with [els], making [primary] the inspector's subject. */
    private fun select(
        els: Collection<Element>,
        primary: Element?,
    ) {
        selected.clear()
        selected.addAll(els)
        selection = primary?.takeIf { it in selected } ?: selected.firstOrNull()
        selectedGroup = null
        selectedJamb = null
    }

    fun clearSelection() {
        selected.clear()
        selection = null
        selectedGroup = null
        selectedJamb = null
        // nothing is selected, so no pick cycle stands: the next click is a first click
        resetCycle()
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
     * Why the connection a path click asked for was refused, as a sentence. The document owns the reason
     * ([Document.connectRefusal]) so the same words serve the drag magnet, a release and this click; the
     * fallback covers a refusal the predicate cannot foresee (a geometric one, such as a leg parallel to
     * the curve it reached), because no route may end in silence.
     */
    private fun refusal(
        vertex: PointRef,
        s: SnapResult,
    ): String {
        val el = doc.elementFor(vertex)
        val target = s.target
        if (el == null || target == null) return "could not join here."
        val why = doc.connectRefusal(el, target)
        return "can't join ${doc.nameOf(el)} onto ${doc.nameOf(target)}${if (why == null) "" else " — $why"}."
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

    /**
     * The **jamb** of an opening this drag is sliding (OP-21). Held apart from [dragTarget] for the one
     * reason the whole feature turns on: a jamb is a line the plan *draws*, so there is no element to point
     * at — the pick resolved it into a handle over the thick path's own parameters instead.
     */
    private var dragJamb: Jamb? = null

    /**
     * The placed group whose **frame** the current jamb grab outranked (OP-16), for as long as that gesture
     * lasts. Held because the press clears the group selection — so by the time the drag reports itself, the
     * fact worth naming is no longer readable anywhere else.
     */
    private var jambStoleFrame: Group? = null
    private var dragStart: Vec2? = null // where on the geometry the drag began — the axis-lock origin
    private var grabOffset: Vec2 = Vec2(0.0, 0.0) // cursor minus that, so a grab never jumps

    /**
     * Where every carrier-anchored rider stood when this drag began (OP-20). Captured at the press and never
     * refreshed: the compensation each move applies is measured from the *grab*, so a gesture is a pure
     * function of where it started and where the cursor is now — see [Document.compensateRiders].
     */
    private var dragRiders: List<Document.RiderAnchor> = emptyList()
    private var joinHints: List<Vec2> = emptyList() // corners this drag has flattened, marked on canvas
    private var terminalHint: Vec2? = null // the vertex a run just ended *on* — see [markTerminal]
    private var weldTarget: Element? = null // a point to weld onto
    private var attachTarget: Element? = null // a curve to attach onto
    private var haloPos: Vec2? = null // where the magnet ring is drawn
    private var panning = false
    private var lastScreen = Vec2(0.0, 0.0)
    private var downScreen: Vec2? = null // where the press landed, so a release can tell click from drag
    private var pendingToggle: Element? = null // Shift+click's toggle, applied on release (see [pointerUp])

    // ---- the pick cycle: one machine, applied on release exactly as the group/member cycle always was ----
    //
    // A SELECT click collects **every** candidate within tolerance ([pickAt]) and selects the first — which
    // is, by construction, what the press has always selected. Clicking the same spot again steps to the
    // next, wrapping, so nothing under the cursor is unreachable: the group *and* the member, the leg *and*
    // the jamb that crosses it, the area *and* the outline over it. The ranking is unchanged (a jamb still
    // wins off the centreline and loses along it) — cycling only makes the loser reachable.
    //
    // Applied **on release and only when the gesture did not move**, which is the discipline the group cycle
    // already used and the reason it exists: deciding a drag's subject after re-picking made "click a member,
    // then drag it" move the member instead of the group (OP-16).
    private var pendingPile: PickPile? = null // what the press found, for the release to apply
    private var pendingIndex = 0 // which of it this gesture will select
    private var pendingApplied = false // whether the press already selected it (the release then keeps its note)
    private var pendingNote = "" // what the press said, so a first click's status survives the release
    private var cycleAt: Vec2? = null // where the click owning the live cycle landed, in screen pixels
    private var cycleWorld: Vec2? = null // …and in world coordinates, so Tab can re-pick there
    private var cycleIndex = 0 // which candidate that click selected
    private var cycleCount = 0 // how many it found there
    private var marqueeFrom: Vec2? = null // rubber-band origin in world coordinates
    private var marqueeTo: Vec2? = null
    private var marqueeAdds = false // Shift+marquee adds to the selection instead of replacing it
    private val pickedPoints = ArrayList<PointRef>()
    private val pickedElements = ArrayList<Element>()
    private val pickedClicks = ArrayList<Vec2>()

    /**
     * What each click landed *on*, in slot order — see [Picks.landings]. Resolved at click time because that
     * is when the cursor exists; a tool that does not join simply never reads it.
     */
    private val pickedLandings = ArrayList<SnapResult?>()
    private var filledSlots = 0

    /**
     * The group that filled the armed tool's geometry slot, if a whole group did (OP-16) — kept only so
     * the status line can name it, since the *step* records the members and nothing else.
     */
    private var pickedGroup: Group? = null

    /** Why the last pick was refused, when there is a better reason than "that click hit nothing". */
    private var pickRefusal: String? = null

    // ortho-path (turtle) state — the path itself is retained in the document while being drawn
    private var activePath: OrthoPath? = null
    private var pathAtEnd = true // which end of a resumed path is growing
    private var pathClosed = false
    private var previewSeg: Pair<Vec2, Vec2>? = null
    private var closePreview: List<Pair<Vec2, Vec2>> = emptyList() // the shape a closing click will make
    private var pathThickness: constructit.dsl.ScalarRef? = null // set for the WALL tool
    private var hoverWorld: Vec2? = null // last cursor position, so a typed length keeps its direction

    /**
     * Digits typed into the drawing flow, for the two things a number can mean here (OP-13):
     *
     * - **direct distance entry**, while a leg is being previewed: the mouse supplies the leg's
     *   *direction*, the keyboard its *length*, so a leg placed by typing is indistinguishable from one
     *   placed by clicking;
     * - **a scalar a tool wants**, when a path is not being drawn: Enter turns the number into an
     *   ordinary parameter and hands it to the slot (see [commitTypedScalar]).
     *
     * One buffer, because the two can never be pending at once — a path is either active or it is not.
     */
    var numericEntry: String = ""
        private set

    /**
     * How many scalars the armed tool has been *typed* since it was armed, so the next number is read as
     * the next slot ("depth", then "angle"). Reset with the picks, like every other per-application state.
     */
    private var typedScalars = 0

    val pendingCount: Int get() = filledSlots

    /**
     * The geometry the armed tool has collected so far — what the canvas draws as *picked* (OP-14).
     *
     * A pick is not a selection: it is a half-finished operation, so it gets a mark of its own rather than
     * the selection highlight (see [SceneRenderer]). Exposed read-only, because the collector owns it.
     */
    val toolPicks: List<Element> get() = pickedElements

    fun setTool(id: String) {
        // an active path is a pending operation: switching tools finishes it (its thickness included)
        // rather than silently abandoning half-drawn state that no gesture ever committed
        finishPath()
        // **Sketch on face picks in the plan, so arming it goes there** (OP-17). A side face is named by a
        // solid's *footprint edge*, and only a solid extruded **vertically** has a planar side face at all
        // (`Geom3.sideFace` refuses every other axis) — which means the only space such a solid's plan is
        // ever drawn in is the plan itself. Refusing the click where the user stands would refuse a pick
        // that *cannot* succeed there; switching is therefore the honest answer, and it is said out loud.
        // It also closes a silent trap: in a face view the tool used to find nothing, and the drawing that
        // followed went quietly into the old space.
        val backToPlan = id == Tools.SKETCH_ON_FACE && !doc.activeSpace.isPlan
        if (backToPlan) setActiveSpace(Document.PLAN_SPACE)
        toolId = id
        resetPicks()
        statusHint =
            if (backToPlan) {
                "Plan view — Sketch on face picks a solid's footprint edge, which is drawn here; click the edge you want"
            } else {
                ""
            }
        onChange()
    }

    // ---- sketch spaces: which 2D space this canvas is showing (OP-17) ----
    //
    // The active space is the document's (tools draw into it), and the *view* of each space is the
    // editor's: one camera per space, so switching back to the plan comes back to the plan's own zoom and
    // pan rather than to wherever the face view happened to be. Switching is view state — no step, no undo
    // entry (the file records which space each step was *built* in, see Document.noteSpace).

    private val spaceCameras = HashMap<String, Camera>()

    val activeSpace: SketchSpace get() = doc.activeSpace

    /**
     * Show [name] and draw into it. The selection and any half-collected picks are dropped, because they
     * name elements of a space whose coordinates this one does not share — the same reason a canvas shows
     * one space at a time.
     */
    fun setActiveSpace(name: String): Boolean {
        if (name == doc.activeSpace.name) return true
        val target = doc.spaceNamed(name) ?: return false
        spaceCameras[doc.activeSpace.name] = camera
        finishPath()
        doc.activeSpace = target
        clearSelection()
        resetPicks()
        camera = spaceCameras[name] ?: cameraFor(target)
        statusHint = spaceNote(target)
        onChange()
        return true
    }

    /** How a space introduces itself — the frame's convention, said where the user is about to use it. */
    private fun spaceNote(space: SketchSpace): String =
        if (space.isPlan) {
            "Plan view — the drawing's own space (world XY)."
        } else {
            "Sketching on ${space.name}, the side face of ${space.anchor?.let { doc.nameOf(it) }}: u along the edge from its start, " +
                "v down from the top face. Cut here drills into the material; Extrude builds outward, as a boss."
        }

    /**
     * The first view of a space: the plan centred as always, a face framed on the rectangle it *is*, so
     * switching over never lands on an empty view with the face off screen.
     */
    private fun cameraFor(space: SketchSpace): Camera {
        val r = doc.faceOutline(space, ev()) ?: return Camera.centered(canvasW, canvasH)
        val w = r[2].x
        val h = r[2].y
        if (w <= 0.0 || h <= 0.0) return Camera.centered(canvasW, canvasH)
        val scale = minOf(canvasW / (w * 1.6), canvasH / (h * 1.6)).coerceIn(0.02, 400.0)
        return Camera(canvasW / 2 - w / 2 * scale, canvasH / 2 + h / 2 * scale, scale)
    }

    /** One click of the *Sketch on face* tool: a solid's footprint edge names a side face (OP-17). */
    private fun faceClick(world: Vec2) {
        val from = doc.activeSpace.name
        val hit = doc.solidEdgeNear(world, tolWorld(), ev())
        if (hit == null) {
            statusHint = "Click a solid's footprint edge — that edge is the side face, seen from above"
        } else {
            val (solid, piece) = hit
            val why = doc.faceRefusal(solid, piece)
            val space = if (why == null) doc.createFaceSpace(solid, piece) else null
            if (space == null) {
                statusHint = "No sketch space on that edge of ${doc.nameOf(solid)}: ${why ?: "it has no planar side face there"}"
            } else {
                // the document already made it active; the *view* follows it here
                spaceCameras[from] = camera
                camera = cameraFor(space)
                clearSelection()
                checkpoint()
                statusHint = spaceNote(space)
            }
        }
        onChange()
    }

    /** Parameters typed for the pending tool, not yet sealed by its checkpoint — see [commitTypedScalar]. */
    private val pendingTypedParams = ArrayList<ScalarEntry>()

    private fun resetPicks() {
        // a typed parameter whose tool never completed is retracted with the picks it belonged to —
        // left in place, the next unrelated checkpoint would silently absorb it into a foreign undo step
        for (e in pendingTypedParams) {
            if (doc.retractParameter(e)) {
                scalarPicks.removeAll { it === e }
                if (activeScalar === e) activeScalar = scalarPicks.lastOrNull()
            }
        }
        pendingTypedParams.clear()
        pickedPoints.clear()
        pickedElements.clear()
        pickedClicks.clear()
        pickedLandings.clear()
        filledSlots = 0
        pickedGroup = null
        pickRefusal = null
        dragTarget = null
        dragFrame = null
        dragJamb = null
        jambStoleFrame = null
        dragStart = null
        grabOffset = Vec2(0.0, 0.0)
        joinHints = emptyList()
        terminalHint = null
        weldTarget = null
        attachTarget = null
        haloPos = null
        panning = false
        downScreen = null
        pendingToggle = null
        pendingPile = null
        resetCycle()
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
        typedScalars = 0
        snapHint = null
    }

    /**
     * The typed views of the selection's handle — the same writes its drag performs (OP-13). Only for a
     * selection of *one*: a field addresses one node, and with several elements selected there is no
     * single answer to show (nor to write back), so the inspector stays empty.
     */
    fun selectionFields(): List<HandleField> {
        // an opening's jamb addresses the interval's own parameters (OP-21) — position, width and the two
        // heights — which are what its drag writes, so the panel and the canvas stay one operation
        selectedJamb?.let { return it.handle(doc).fields() }
        // a placed group selected as a whole addresses its *frame* — the group's three degrees of freedom
        // (OP-16 step 2), which is exactly what dragging it writes, so drag and panel stay one operation
        selectedFrame()?.frameHandle?.let { return it.fields() }
        return if (selected.size == 1) selection?.handle?.fields() ?: emptyList() else emptyList()
    }

    /** Short name for the selection, for the inspector header. */
    fun selectionLabel(): String {
        selectedJamb?.let { return "opening on leg ${it.interval.legIndex + 1} of ${doc.nameOf(it.path.footprint)}" }
        selectedFrame()?.let { return "frame of ${it.name}" }
        if (selected.size > 1) return "${selected.size} elements"
        return elementLabel(selection ?: return "")
    }

    /** What one element is called in a sentence — the same words for the inspector and for the pick cycle. */
    private fun elementLabel(el: Element): String {
        val kind =
            when (el.handle) {
                is OrthoEdgeHandle -> "leg"
                is OrthoCornerHandle -> "corner"
                else -> el.kind.name.lowercase()
            }
        return "$kind ${doc.nameOf(el)}"
    }

    /**
     * Hide or show the selected elements — **one recorded step per gesture** (OP-18's visibility reversal;
     * see [Document.setElementsVisible]), so it survives save/load and undoes like any other operation.
     * A welded alias stays hidden: it is hidden *by construction*, and showing it would draw a second point
     * on top of its master.
     */
    fun setSelectionVisible(visible: Boolean): Int {
        val n = doc.setElementsVisible(selectedElements, visible)
        if (n > 0) checkpoint()
        statusHint = if (n == 0) "Nothing to ${if (visible) "show" else "hide"}" else "${if (visible) "Shown" else "Hidden"} $n element${if (n == 1) "" else "s"}"
        onChange()
        return n
    }

    // ---- flat named groups (OP-16 step 1) ----

    /**
     * Group the selection under [name] (auto-numbered when blank). Organizational only: no geometry,
     * no node and no handle changes — a member drags exactly as it did before.
     */
    fun groupSelection(
        name: String = "",
        commit: Boolean = true,
    ): Group? {
        if (selected.isEmpty()) {
            statusHint = "Select the elements to group first (Shift+click to add, or drag a box)"
            onChange()
            return null
        }
        val clash = selected.firstNotNullOfOrNull { el -> doc.groupOf(el)?.let { el to it } }
        if (clash != null) {
            statusHint = "${doc.nameOf(clash.first)} is already in group ${clash.second.name} — an element is in at most one group; ungroup it first"
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
        // [commit] is false when the caller is going to *place* this group as part of the same operation
        // (the create dialog's default): creating and placing are then one checkpoint, and one undo removes
        // both — see [confirmCreate].
        if (commit) checkpoint()
        statusHint = "Grouped ${g.members.size} elements as ${g.name}"
        onChange()
        return g
    }

    // ---- the shared create dialog: group, or tool (OP-16 / OP-6's UI half) ----
    //
    // One dialog with two defaults, because the two ask the same question about the same closure —
    // which of the free sources the selection reaches belong to the thing being made. Group → macro is
    // the promotion path, so duplicating the record UI would be duplicating the analysis behind it.

    /** The open create dialog, or null. The shell renders it; every decision in it is [CreateDialog]'s. */
    var createDialog: CreateDialog? = null
        private set

    /**
     * Open the create dialog over the current selection. Refused only when nothing is selected — a
     * *tool* may still be impossible (a dimension in the selection, a placed group), which the dialog
     * reports rather than the button refusing, so the user can see why.
     */
    fun beginCreate(mode: CreateMode): CreateDialog? {
        if (selected.isEmpty()) {
            statusHint = "Select the elements first (Shift+click to add, or drag a box)"
            onChange()
            return null
        }
        val members = selectedElements
        val d = CreateDialog.of(mode, members, doc.analyseMacro(members)) { doc.nameOf(it) }
        createDialog = d
        statusHint = d.help
        onChange()
        return d
    }

    fun cancelCreate() {
        createDialog = null
        statusHint = ""
        onChange()
    }

    /**
     * Confirm the open dialog: a group of the selection (plus any ticked point, which is OP-16's
     * "membership = closure or inputs" answered the group way), or a macro whose ticked candidates are
     * its input ports. One checkpoint either way — declaring a tool is one user-level operation, and so is
     * making a movable part, even though that is a `group` step *and* a `place` step (see [CreateDialog]).
     */
    fun confirmCreate(): Boolean {
        val d = createDialog ?: return false
        if (!d.ready) {
            statusHint = d.blocker ?: "Nothing to create"
            onChange()
            return false
        }
        val ok =
            if (d.mode == CreateMode.GROUP) {
                val members = d.members + d.checkedPoints.filter { p -> d.members.none { it === p } }
                select(members, d.members.firstOrNull())
                // held back: with the frame ticked, the placement below belongs to the same checkpoint
                val g = groupSelection(d.name, commit = false)
                // **Say at creation time what placement would refuse** (OP-16's honest-failure rule): the
                // freedom an unticked row leaves outside the group is exactly what makes the group immovable,
                // and it is invisible on canvas — so the reason belongs to the gesture that caused it, not to
                // a Place click much later.
                if (g != null) {
                    val n = doc.groupMembers(g).size
                    d.warnings = doc.placementWarnings(g)
                    val made = "Grouped $n element${if (n == 1) "" else "s"} as ${g.name}"
                    // The frame is the default, so the everyday group is movable the moment it exists. A
                    // refusal is **not** a failure of the gesture: the group is created flat and the reason is
                    // shown, which is the same honest answer Place gives — and a flat group is a purpose of
                    // its own (an array original), not a consolation. [Editor.placeGroup] says which of the
                    // two happened, and that is the half the canvas cannot show, so it is kept verbatim.
                    val placeNote =
                        if (!d.framed) {
                            null
                        } else {
                            placeGroup(g, commit = false)
                            statusHint
                        }
                    statusHint =
                        when {
                            placeNote != null -> "$made. $placeNote"
                            d.warnings.isEmpty() -> "$made — a named set, with no frame"
                            else -> "$made, but " + d.warnings.joinToString("; ")
                        }
                    checkpoint() // the whole operation: the group, and the frame it was born with
                }
                g != null
            } else {
                makeTool(d.name, d.members, d.checkedPoints, d.checkedScalars)
            }
        if (ok) createDialog = null
        onChange()
        return ok
    }

    /**
     * Declare [members] a macro named [name] and switch to the tool it becomes (OP-6). Separated from
     * the dialog so a test — and a future scripted path — can make a tool without one.
     */
    fun makeTool(
        name: String,
        members: List<Element>,
        pointInputs: List<Element>,
        scalarInputs: List<ScalarEntry>,
    ): Boolean {
        val def = doc.defineMacro(name, members, pointInputs, scalarInputs)
        if (def == null) {
            statusHint = "Could not make a tool from that selection"
            onChange()
            return false
        }
        checkpoint()
        setTool(def.toolId)
        val scalarNote = if (scalarInputs.isEmpty()) "" else " and ${scalarInputs.joinToString(", ") { it.name }} from the panel"
        statusHint =
            "Tool ${def.name}: click ${pointInputs.size} point${if (pointInputs.size == 1) "" else "s"}$scalarNote " +
            "to place an instance — editing the original updates every instance"
        onChange()
        return true
    }

    /**
     * Retire a custom tool. Refused while instances exist, naming them: an instance is a function of its
     * definition (OP-6), so there is no consistent document with the definition gone and the instances
     * left — the same rule delete follows.
     */
    fun deleteMacro(def: MacroDef): Boolean {
        val instances = doc.instancesOf(def).flatMap { it.elements }
        if (instances.isNotEmpty()) {
            statusHint =
                "Can't remove tool ${def.name}: ${instances.size} instance element${if (instances.size == 1) "" else "s"} " +
                "still use it (${instances.take(4).joinToString(", ") { doc.nameOf(it) }}) — delete the instances first"
            onChange()
            return false
        }
        if (!doc.removeMacro(def)) return false
        if (toolId == def.toolId) setTool(Tools.SELECT)
        checkpoint()
        statusHint = "Removed tool ${def.name} — the construction it was made from stays"
        onChange()
        return true
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
    fun placeGroup(
        g: Group,
        commit: Boolean = true,
    ): Boolean {
        if (g.placed) {
            statusHint = "${g.name} is already placed — drag any member to move it, or Unplace it first"
            onChange()
            return false
        }
        val analysis = doc.analysePlacement(g)
        if (analysis.conflicts.isNotEmpty()) {
            val points = analysis.conflicts.map { it.point }.distinct()
            val consumers = analysis.conflicts.map { doc.nameOf(it.consumer) }.distinct()
            val verb = if (points.size == 1) "is" else "are"
            statusHint =
                "Can't place ${g.name}: ${points.joinToString(", ")} $verb also used by " +
                "${consumers.joinToString(", ")} — include ${if (points.size == 1) "it" else "them"} in the group, " +
                "or this group cannot move independently"
            onChange()
            return false
        }
        // the refusal survives only for a group that owns no freedom **at all** — of any kind: ortho paths and
        // the walls riding them are carried (OP-16's ortho-path bonus), and so are riders, polar offsets and
        // on-circle angles (see [Document.analysePlacement]), so owning no free *point* is not a reason
        if (!analysis.carriesSomething) {
            statusHint =
                "Can't place ${g.name}: it owns no degree of freedom, so a frame would have nothing to move" +
                if (analysis.uncapturable.isEmpty()) "" else " (${analysis.uncapturable.joinToString("; ")})"
            onChange()
            return false
        }
        val result = doc.placeGroup(g)
        if (result == null) {
            statusHint = "Could not place ${g.name}"
            onChange()
            return false
        }
        // false only when the group was *created* by the same operation — one checkpoint for both halves
        if (commit) checkpoint()
        // the deformable boundary is stated at placement time, because it is invisible on canvas: those
        // members are driven from outside the group and the frame does not move them (OP-16)
        val deforms =
            if (result.unfollowed.isEmpty()) {
                ""
            } else {
                " — ${result.unfollowed.joinToString(", ") { doc.nameOf(it) }} " +
                    "${if (result.unfollowed.size == 1) "is" else "are"} driven from outside and will not follow it"
            }
        val carried =
            listOfNotNull(
                "${result.captured} point${if (result.captured == 1) "" else "s"}".takeIf { result.captured > 0 },
                "${result.capturedPaths} path${if (result.capturedPaths == 1) "" else "s"}".takeIf { result.capturedPaths > 0 },
                // a rider is carried by being measured from its own carrier instead of from the world, which is
                // a change of *anchor* rather than of coordinates — so it is named as such (OP-4 case b)
                "${result.capturedRiders} rider${if (result.capturedRiders == 1) "" else "s"} re-anchored to their carrier"
                    .takeIf { result.capturedRiders > 0 },
            ).joinToString(" and ")
        statusHint = "Placed ${g.name}: ${carried.ifEmpty { "its own freedom" }} now frame-relative$deforms"
        onChange()
        return true
    }

    /**
     * Unplace [g]: what it holds becomes free again where the frame's origin puts it, and the frame goes.
     *
     * A **turned** group comes back un-turned: an ortho path's legs are axis-aligned by construction, so
     * nothing but a frame can hold them at an angle, and unplacing gives back exactly what placing took
     * rather than tearing the group into the parts that can stay turned and the parts that cannot. Said out
     * loud, because it is the one part of unplacing that is not world-invariant (OP-16).
     */
    fun unplaceGroup(g: Group): Boolean {
        val unturns = doc.unturnsGroup(g)
        if (!doc.unplaceGroup(g)) {
            statusHint = "${g.name} is not placed"
            onChange()
            return false
        }
        checkpoint()
        statusHint =
            if (unturns) {
                "Unplaced ${g.name} — it is unturned again, exactly as the frame took it (only a frame can hold a group turned)"
            } else {
                "Unplaced ${g.name} — its points are free again, exactly where they were"
            }
        onChange()
        return true
    }

    /**
     * Select [el] outright — the panel's way in, as [selectGroup] is for a group.
     *
     * Needed because two elements can share a position exactly: a solid's footprint hint *is* the area
     * it was extruded from (OP-17), so a canvas click can only ever reach the topmost of the two. The
     * element tree addresses either one by name, which is the honest answer — biasing the pick would
     * just make the other one unreachable instead.
     *
     * Selecting an element of **another sketch space** (OP-17) shows the space it is in rather than a
     * selection the canvas cannot draw: the tree lists the whole document, so the honest answer to "that
     * one" is where to go and look at it.
     */
    fun selectElement(el: Element) {
        select(listOf(el), el)
        resetCycle() // the tree picked this, not a click on the canvas
        val space = doc.spaceOf(el)
        statusHint =
            // …and "another space" is asked as the panel asks it (OP-17, issue #2): a solid is listed in every
            // space because it is in none, so telling the user to switch spaces to see one would be wrong —
            // the 3D viewport is already showing it
            if (doc.listedIn(el)) {
                "${selectionLabel()} selected"
            } else {
                "${selectionLabel()} selected — it is drawn in ${space.name}, so switch the space to see it"
            }
        onChange()
    }

    /**
     * The groups panel's row click. A tool waiting for a geometry slot takes the **group** (OP-16 — the
     * panel is as much an input as the canvas, OP-13); otherwise the row selects it, as it always did.
     *
     * One entry point, so the shell only routes: which of the two a click means depends on the armed tool
     * and on how many slots are already filled, neither of which the DOM knows anything about.
     */
    fun clickGroup(g: Group) {
        if (!feedGroupToSlot(g)) selectGroup(g)
    }

    /**
     * Why a whole group must not fill [tool]'s geometry slot, as a sentence — or null when it may.
     *
     * The one reason is size: a group multiplies a structural count by its member count, so the bound that
     * protects a single element from a mistyped count ([MAX_COUNT]) has to be applied to the *copies*. Said
     * rather than clamped, because the count is structural: quietly building a different number of copies
     * from the one asked for would be a different construction (OP-18).
     */
    private fun groupFanRefusal(
        tool: ToolDef,
        g: Group,
        members: Int,
    ): String? {
        val instances = toolCount(tool)
        val copies = members * (instances - 1)
        if (instances < 2 || copies <= MAX_COUNT) return null
        return "Group ${g.name} has $members elements, so $instances instances would build $copies copies — " +
            "more than $MAX_COUNT; lower the count, or array fewer elements"
    }

    /**
     * Fill the armed tool's pending [SlotKind.GEOMETRY] slot with the whole of [g], or return false when no
     * such slot is waiting (or the tool cannot take a group — see [ToolDef.groupOperand]).
     *
     * The naming *is* the pick, so unlike the canvas route this needs no prior selection: it is the panel's
     * answer to the same question a click on a member answers by position. The remaining slots then proceed
     * by canvas clicks exactly as usual — and a tool whose slots this completes builds on the spot, which is
     * why the completion path is the shared one.
     *
     * A pick is not a selection, so the selection is deliberately left alone: the members get the *picked*
     * mark (see [toolPicks]), which is what every other half-finished operation gets.
     */
    private fun feedGroupToSlot(g: Group): Boolean {
        val tool = doc.toolDef(toolId) ?: return false
        if (!tool.groupOperand || tool.slots.getOrNull(filledSlots) != SlotKind.GEOMETRY) return false
        val members = doc.groupMembers(g)
        // the group's own centre stands in for the click this pick did not have — see [Document.groupCentre]
        val at = doc.groupCentre(g)
        if (members.isEmpty() || at == null) return false
        // a refusal still *consumes* the click: falling through to selecting the group would replace the
        // reason with a selection note, and the row would look as if it had done nothing
        groupFanRefusal(tool, g, members.size)?.let {
            statusHint = it
            onChange()
            return true
        }
        pickedElements.addAll(members)
        pickedGroup = g
        filledSlots++
        pickedClicks.add(at)
        if (filledSlots >= tool.slots.size) {
            if (!maybeCompleteTool(at)) statusHint = scalarPrompt(tool)
        } else {
            statusHint = "${groupFedNote(g)} ${tool.help} (${tool.slots.size - filledSlots} more)"
        }
        onChange()
        return true
    }

    /** How the status line says a whole group filled a geometry slot — one wording for both routes. */
    private fun groupFedNote(g: Group): String {
        val n = doc.groupMembers(g).size
        return "Group ${g.name} ($n element${if (n == 1) "" else "s"}) is the geometry — every member is copied."
    }

    /** Select every member of [g] — what clicking a member on the canvas does. */
    fun selectGroup(g: Group) {
        val members = doc.groupMembers(g)
        select(members, members.firstOrNull())
        selectedGroup = g
        resetCycle() // the panel picked this, not a click on the canvas
        val what = if (g.placed) " (placed — the panel shows its frame)" else ""
        statusHint = "Group ${g.name}: ${members.size} element${if (members.size == 1) "" else "s"} selected$what"
        onChange()
    }

    /**
     * Hide/show a whole group — the same **per-element** step as [setSelectionVisible], over the group's
     * members.
     *
     * One rule rather than two: a group flag would be a second thing a file could say about visibility, and
     * the two would then have to be reconciled every time membership changed. As members, the state also
     * stays with the elements when the group is dissolved.
     */
    fun setGroupVisible(
        g: Group,
        visible: Boolean,
    ) {
        if (doc.setElementsVisible(doc.groupMembers(g), visible) > 0) checkpoint()
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
        compensating { f.write(quantityOf(f.dim, value)) }
        checkpoint()
        // a write the geometry **bounded** has something to say — an opening clamped to its leg (OP-21) — and
        // it is the same note the drag reports, since typing and dragging are one operation (OP-13)
        doc.takeNote()?.let { statusHint = it }
        onChange()
        return true
    }

    /**
     * Run [edit] with rider compensation around it — the **discrete twin** of a drag's compensation (OP-13:
     * typing and dragging are one operation, so a typed value that turns a host must not catapult what rides
     * it either). The reference is where the riders stand before the write, which for a single write is the
     * same thing a drag's grab time is.
     */
    private fun <T> compensating(edit: () -> T): T {
        val anchors = doc.riderAnchors()
        val result = edit()
        doc.compensateRiders(anchors)
        return result
    }

    // ---- panel parameter edits (OP-7): the shell routes, the controller decides ----

    /**
     * Write [value] (in the display unit of the parameter's dimension) into [e] — the parameters panel's
     * value field, spinner included.
     *
     * [commit] is the **undo-granularity seam**. A native number field fires an event per spinner tick, and
     * the geometry has to follow every one of them (that is what makes a spinner useful: the drawing moves
     * while the value is nudged) — but an undo step is worth taking only where the edit *commits*, on
     * change/blur/Enter. So a tick writes with [commit] false, changing the model and repainting without a
     * snapshot, and the committing event takes the checkpoint: one undo step per committed change, however
     * many ticks it took. The intermediate values were never operations, exactly as the intermediate
     * positions of a drag are not (a drag checkpoints on release too).
     *
     * False when the entry is not writable — a measurement, or a parameter wired to another one — which is
     * the same answer the field's disabled state shows.
     */
    fun setParameter(
        e: ScalarEntry,
        value: Double,
        commit: Boolean = true,
    ): Boolean {
        if (!e.editable || doc.isBound(e)) return false
        // a parameter can drive a host's geometry (a leg length, an angle), so the same compensation applies
        compensating { doc.setParameter(e, quantityOf(dimensionOf(e.ref), value)) }
        if (commit) checkpoint()
        onChange()
        return true
    }

    /**
     * Rename parameter [e] to what was typed into the panel's name field (OP-7). One user-level operation,
     * hence one checkpoint; returns the name it actually **took** — uniquified, or the old one when the
     * field was blank — so the shell can show the result rather than the request.
     */
    fun renameParameter(
        e: ScalarEntry,
        name: String,
    ): String? {
        val was = e.name
        val now = doc.renameParameter(e, name)
        if (now == null) {
            statusHint =
                if (!e.editable) {
                    "${e.name} is a measurement — its name comes from the step that measures it, so it cannot be renamed"
                } else {
                    "${e.name} belongs to the step that created it, which has no place for a name in the file — " +
                        "so renaming it could not be saved"
                }
            onChange()
            return null
        }
        if (now != was) {
            checkpoint()
            val asked = name.trim()
            statusHint = "Renamed $was to $now" + if (asked.isNotEmpty() && now != asked) " (\"$asked\" was taken or not one word)" else ""
        }
        onChange()
        return now
    }

    /** Set a transient status-bar note (e.g. panel feedback). */
    fun note(message: String) {
        statusHint = message
    }

    /** Help line for the active tool — shown in the status bar whenever there's no transient hint. */
    fun currentHelp(): String {
        snapHint?.let { if (it.linked) return "Snap: ${it.label} — Alt to place freely" }
        if (toolId == Tools.SELECT) return Tools.SELECT_HELP
        val tool = doc.toolDef(toolId) ?: return ""
        // the panel and the keyboard are as much an input as the canvas (OP-13), so a tool still waiting
        // for a scalar says which one it wants next rather than describing clicks it cannot use yet
        if (toolScalars(tool) == null) return scalarPrompt(tool)
        val n = toolCount(tool)
        // Name the values it *will* consume. A tool takes the last picks in order, so with a parameter
        // already picked it is silently ready — which is convenient and invisible, and the invisible half
        // is what made people mis-size a feature and blame the tool.
        val using =
            if (tool.scalars.isEmpty()) {
                ""
            } else {
                val entries = toolScalars(tool).orEmpty()
                // a defaulted slot with nothing picked names the **default**, because that is what the tool
                // will use — the same promise as naming a picked parameter (see [ScalarSlot.default])
                " Using " +
                    tool.scalars.mapIndexed { i, s ->
                        val e = entries.getOrNull(i)
                        "${s.name} = " + (e?.name ?: s.default?.let { "${Format.quantity(it)} (default)" } ?: "?")
                    }.joinToString(", ") +
                    " — type a number for another."
            }
        return (if (n == 0) tool.help else "${tool.help} (count $n)") + using
    }

    fun render(target: DrawTarget) {
        val ev = ev()
        SceneRenderer.render(
            doc, ev, camera, target, canvasW, canvasH, showGrid, haloPos, previewSeg, selected,
            snapHint?.pos, joinHints, closePreview, terminalHint,
            dimmed = if (dimScaffolding) doc.scaffoldingElements().toHashSet() else emptySet(),
            marquee = marqueeFrom?.let { f -> marqueeTo?.let { t -> f to t } },
            frames = selectedFrames(),
            picked = pickedElements.toHashSet(),
            // the selected opening's own drawing (OP-21), since it has no element to emphasize
            emphasis = selectedJamb?.let { doc.intervalOutline(it.path, it.interval, ev) } ?: emptyList(),
        )
    }

    /**
     * The frames to draw: those of placed groups with a member that is selected **and drawn** (OP-16
     * step 2). Shown on selection only — the frame is a handle, not geometry, so it appears when it is
     * addressable; and gated on visibility because a marker for a group nothing of which is on screen is
     * an orphan: hiding a group (or the selected member alone) must take its origin marker with it.
     */
    private fun selectedFrames(): List<FrameValue> {
        val ev = ev()
        return doc.groups
            .filter { g -> g.placed && doc.groupMembers(g).any { it in selected && it.visible } }
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
        // the terminal cue lasts until the user's next *action*, which this is. Deliberately not cleared by
        // a hover: the mouse always moves right after a click, and a mark a stray move erases is not a mark
        terminalHint = null
        // panning is a button, not a mode: it works in every tool, and the shell reports Space+drag here
        if (button == PointerButton.MIDDLE) {
            panning = true
            lastScreen = screen
            return
        }
        if (toolId == Tools.SELECT) {
            val world = camera.screenToWorld(screen)
            // one search and one ranking: what this press grabs and what a repeat click cycles through are
            // the same list, so the precedence is written down once (see [pickAt])
            val pile = pickAt(world)
            // Where the live cycle stands here, if this press continues it — the one fact both halves of the
            // machine turn on: the *next* candidate is what a click takes, and the *standing* one is what a
            // drag moves (**the selection primes the drag**, see below).
            val standing = if (additive) null else standingIndex(screen, pile)
            // a Shift+click is a toggle, not a step of any cycle, so it takes no part in one
            pendingPile = if (additive) null else pile
            pendingIndex = if (standing == null) 0 else (standing + 1) % pile.candidates.size
            pendingApplied = false
            pendingNote = ""
            if (additive) resetCycle()
            // **Selection primes the drag.** A press that continues the cycle drags what the cycle selected,
            // overriding the ranking — which is what makes cycling worth having: step to the thing you want,
            // then drag exactly it. A *placed group selected as a whole* is the same rule and predates it (the
            // frame branch below), so it is left where it is rather than duplicated here.
            //
            // Predictability over convenience: when the primed selection cannot move, the press says why and
            // moves **nothing** — falling through to a different target would move something the user did not
            // point at. Clicking elsewhere, or Esc, gives the ranking back.
            //
            // Only the **primary** primes: a multi-selection has no single drag subject, and a bulk drag is a
            // separate feature (OP-16's "no bulk move" — that is the frame's job).
            val primed = standing?.let { pile.candidates[it] }
            val primedElement =
                when (primed) {
                    is Candidate.One -> primed.element
                    is Candidate.Whole -> primed.primary
                    else -> null
                }
            val jamb = (primed as? Candidate.Opening)?.jamb ?: pile.grabJamb.takeIf { primed == null }
            // The jamb took the grab. It is 1-DOF along its leg, so the offset the grab holds is the *along*
            // component of the cursor — the perpendicular one is projected onto the jamb's own line, which is
            // what stops the opening from jumping when the grab lands beside the line rather than on it.
            if (jamb != null) {
                // A jamb outranks even a *placed* group's frame (OP-16), which every other member drag moves
                // when the group is selected as a whole. It has to, or an opening in a placed wall would be
                // editable only after deselecting the group — the feature would work differently for the same
                // wall depending on how it was reached. What that costs is one invisible half, so it is said.
                jambStoleFrame = doc.placedGroupOf(jamb.path.footprint)?.takeIf { selectedGroup === it }
                selectJamb(jamb)
                dragJamb = jamb
                val anchor = onJambLine(jamb, world)
                grabOffset = world - anchor
                dragStart = anchor
                pendingApplied = true
                pendingNote = describeJamb(jamb)
                // where in the pile this jamb stands: the first candidate on a fresh press, and the one the
                // cycle is standing on when the press was primed by it
                statusHint = cycleNote(pendingNote, pile, standing ?: 0)
                onChange()
                return
            }
            val movable = pile.movable
            // an immovable element is still selectable, so its values can be read and the reason shown
            val hit = pile.hit
            // a press on nothing starts a rubber band; what it covers is selected on release (OP-16)
            if (hit == null) {
                marqueeFrom = world
                marqueeTo = world
                marqueeAdds = additive
                pendingPile = null
                resetCycle() // a press on empty space is nobody's repeat
                onChange()
                return
            }
            // A member of a **placed** group is addressed by the *gesture*, not by what the press does to
            // the selection, and the two halves of that split are decided at the two ends of the gesture:
            //
            // - **the drag's subject, here at press time**, from the selection this press *found* — the
            //   frame only when the group is already selected as a whole, otherwise the member's own
            //   handle. Grouping is invisible until something of it is selected (OP-16), so a drag on a
            //   member of a group nobody selected must move that member, exactly as if it were ungrouped;
            //   and a member deliberately reached alone keeps moving alone, as before. Decided *here* and
            //   never re-decided, because a release cannot undo what the drag has already moved.
            // - **the selection, at release** ([pointerUp]): a click applies the pick (cycling on a repeat), a
            //   drag leaves the member it moved selected. Both are click-vs-drag semantics, and only the
            //   release knows which of the two the gesture was (CLICK_SLOP_PX).
            val placedGroup = if (additive) null else doc.placedGroupOf(hit)
            var note = ""
            when {
                additive -> pendingToggle = hit
                // primed: what the press found is what the drag will move, so the press leaves it alone (the
                // release still steps the cycle on, exactly as for a group's deferred pick)
                primed != null -> {}
                // deferred whole: the frame decision below must read the selection this press *found*
                placedGroup != null -> {}
                else -> {
                    // the press selects the pile's **first** candidate — which is what it has always
                    // selected; a repeat click steps past it at release, never here
                    note = selectPick(pile.candidates.first())
                    pendingApplied = true
                }
            }
            // dragging the frame is one literal write, whatever the group contains, and the whole of it
            // follows rigidly — derived geometry included, since it is downstream (OP-16 step 2)
            val frameGroup = placedGroup?.takeIf { selectedGroup === it }
            if (frameGroup != null) {
                val anchor = frameGroup.frameHandle?.origin(ev()) ?: world
                dragFrame = frameGroup
                grabOffset = world - anchor
                dragStart = anchor
                statusHint = note
                pendingNote = note
                onChange()
                return
            }
            // the primed subject, when the frame did not already take it: the cycled selection moves, or says
            // why it cannot — and either way nothing else is grabbed instead
            if (primedElement != null) {
                if (!primedElement.hasFreeDof) {
                    statusHint = explainImmovable(primedElement, doc.nameOf(primedElement))
                    pendingNote = statusHint
                    onChange()
                    return
                }
                dragTarget = primedElement
                val anchor = grabAnchor(primedElement, world)
                grabOffset = world - anchor
                dragStart = anchor
                dragRiders = doc.riderAnchors()
                pendingNote = "Dragging ${elementLabel(primedElement)} — what is selected takes the grab"
                statusHint = cycleNote(pendingNote, pile, standing ?: 0)
                onChange()
                return
            }
            if (movable != null) {
                // a member of a placed group whose group is *not* what is selected drags on its own, which
                // is invisible on canvas until something moves — so it is said out loud, together with the
                // way to get the frame instead (OP-16)
                if (placedGroup != null && (selected.size != 1 || selection !== hit)) {
                    note =
                        "Dragging ${doc.nameOf(hit)} alone — group ${placedGroup.name} is not selected as a whole; " +
                        "click without moving to select it, then drag to move the frame"
                }
                dragTarget = movable
                // drag by the *offset* from where the grab landed, not to the cursor outright:
                // picking has a tolerance, so writing the cursor position made the geometry jump to
                // it on the first move and then follow from there
                val anchor = grabAnchor(movable, world)
                grabOffset = world - anchor
                dragStart = anchor
                // …and where everything riding a host stands right now, for the same reason: the drag may turn
                // a host, and a rider measured along that host has to be re-solved against where it *was*
                dragRiders = doc.riderAnchors()
                statusHint = note
            } else {
                statusHint = explainImmovable(hit, doc.nameOf(hit))
            }
            // …and what the press said is kept for the release: a first click must read exactly as it always
            // did (the reason an immovable element cannot be dragged included), with only the pile's position
            // appended when there is more than one thing here
            pendingNote = statusHint
            if (pendingApplied) statusHint = cycleNote(statusHint, pile, 0)
            onChange()
            return
        }
        if (toolId == Tools.ORTHO_PATH || toolId == Tools.WALL) {
            if (toolId == Tools.WALL && activePath == null && activeScalar == null) {
                statusHint = "Wall: type a thickness (or click a parameter in the panel) first"
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
        // one click, and a step of its own (OP-17): see [faceClick]
        if (toolId == Tools.SKETCH_ON_FACE) {
            faceClick(camera.screenToWorld(screen))
            return
        }
        if (toolId == Tools.BREAK_LEG) {
            breakClick(camera.screenToWorld(screen))
            return
        }
        runToolClick(screen)
    }

    /** Curve kinds a plain break splits — an ortho leg is a segment too, and is handled before these. */
    private fun breakableKind(el: Element): Boolean =
        el.kind == ElementKind.SEGMENT || el.kind == ElementKind.ARC || el.kind == ElementKind.BEZIER

    /**
     * One click of the Break tool, **dispatched by what it landed on**: an ortho leg keeps the jog logic
     * verbatim (OP-19 — a leg's break is a topology edit on its path, inserting a zero-length corner), and any
     * other segment, arc or Bézier is split as a construction ([Document.breakCurve]).
     *
     * The leg case is decided first and by the same search, so nothing about it changed: a leg *is* a segment,
     * so ranking the two searches together is the only way the two meanings cannot fight over one click.
     */
    private fun breakClick(world: Vec2) {
        val tol = tolWorld()
        val hit = HitTest.nearest(doc, ev(), world, tol) { doc.legOf(it) != null || breakableKind(it) }
        statusHint =
            when {
                hit == null -> "Click a segment, an arc or a Bézier to split it there (or a leg of an ortho path)"
                doc.legOf(hit) != null ->
                    if (doc.breakOrthoLegNear(world, tol)) {
                        "Segment broken — drag either half to open the corner"
                    } else {
                        // a break *replaces* the leg, so it is refused on a leg a tool is defined from
                        // (OP-6) — say which, since "click a segment" would be a lie there
                        if (doc.definesAMacro(listOf(hit))) {
                            "${doc.nameOf(hit)} is part of a tool's definition — breaking it would replace it; retire the tool first"
                        } else {
                            "${doc.nameOf(hit)} can't be broken there"
                        }
                    }
                else -> breakCurveAt(hit, world)
            }
        checkpoint()
        onChange()
    }

    /**
     * Split the plain curve [el] at [world], and — when the break made the original redundant — **replace the
     * step that drew it**: drop that step and replay the remaining script (OP-18), so the file reads as the
     * two halves the drawing now has, exactly as a delete leaves a script that still constructs.
     *
     * The rewrite is the caller's half of the consumer rule because replaying swaps the whole document
     * ([adopt]); the document decides *whether* to (see [Document.BreakResult.replacesOriginal]) and, when
     * not, hides the original itself with a recorded step. Returns the status line to show.
     */
    private fun breakCurveAt(
        el: Element,
        world: Vec2,
    ): String {
        val res = doc.breakCurve(el, world) ?: return doc.takeNote() ?: "${doc.nameOf(el)} can't be broken there"
        val note = doc.takeNote() ?: ""
        if (!res.replacesOriginal) return note
        val root = doc.creatingStep(el) ?: return note
        val journalBefore = doc.journal.toList()
        doc.journal.removeAll(doc.dependentSteps(setOf(root)))
        val fresh =
            try {
                DocumentFormat.load(DocumentFormat.save(doc))
            } catch (e: Exception) {
                // the same all-or-nothing discipline a delete keeps: put the script back and say so, rather
                // than leaving a document that cannot be saved
                doc.journal.clear()
                doc.journal.addAll(journalBefore)
                return "Break failed: ${e.message}"
            }
        adopt(fresh)
        return note
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
                val linked = s.linked && doc.linkPathEnd(started.vertices.first().ref, s)
                // a placed path is not extended in place (OP-16): its coordinates are its group's local
                // ones, so this click starts a new run joined to it — said out loud, since the same click
                // continues an unplaced path
                val placedEnd = s.target?.let { t -> (t.handle as? OrthoCornerHandle)?.let { doc.pathFrameOf(it) != null } } == true
                statusHint =
                    if (linked && placedEnd) {
                        "$what starts on ${s.target?.let { doc.nameOf(it) }} — a placed path is not extended in place; " +
                            "this is a new run joined to it (unplace its group to extend it)"
                    } else if (linked) {
                        "$what starts on ${s.target?.let { doc.nameOf(it) }} (${s.label}); click the next point"
                    } else if (s.linked) {
                        // the click landed on geometry and the link was refused: say why here too, or the
                        // run would start looking joined and only come apart when something else moved
                        "$what: ${refusal(started.vertices.first().ref, s)} The run starts here unjoined; click the next point."
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
            if (v != null && s.linked) {
                if (doc.linkPathEnd(v.ref, s)) {
                    snapHint = null
                    finishPath() // which marks the terminus and says the run is over — see [markTerminal]
                    // the same sentence, naming what was reached rather than the vertex that reached it
                    statusHint = "$what ends on ${s.target?.let { doc.nameOf(it) }} (${s.label})$RUN_FINISHED"
                    onChange()
                    return
                }
                // A connection that cannot be made **says so** (OP-20): the click looked exactly like the
                // end of a run, so refusing in silence left a leg placed, unjoined and still growing —
                // "the ending did not snap and did not finish the path". The rubber band is the honest
                // cue that drawing continues, and it is still there, so the sentence explains why.
                statusHint = "$what: ${refusal(v.ref, s)} The leg is placed but not joined; the run continues (Esc to finish)."
                hoverWorld = world
                previewSeg = null
                snapHint = null
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
     * numeric entry — a leg's length while a path is being drawn, otherwise the scalar the armed tool
     * wants; Enter commits it; Escape cancels a pending entry first, then finishes; a single letter arms
     * the tool that letter belongs to. Returns true when consumed.
     */
    fun key(key: String): Boolean {
        terminalHint = null // an action, like a press — see [markTerminal]
        val pathActive = activePath != null
        val digit = key.length == 1 && (key[0].isDigit() || key == ".")
        return when {
            pathActive && digit -> {
                numericEntry += key
                refreshPreview()
                onChange()
                true
            }
            // the same digits, for the scalar a tool is missing: one mechanism for every scalar slot
            // (OP-13), so no tool has to know that its value can be typed
            digit && typedScalarSlot() != null -> {
                numericEntry += key
                statusHint = typedScalarPrompt()
                onChange()
                true
            }
            key == "Backspace" && numericEntry.isNotEmpty() -> {
                numericEntry = numericEntry.dropLast(1)
                if (pathActive) refreshPreview() else statusHint = typedScalarPrompt()
                onChange()
                true
            }
            key == "Escape" && numericEntry.isNotEmpty() -> {
                numericEntry = ""
                if (pathActive) refreshPreview() else statusHint = ""
                onChange()
                true
            }
            key == "Enter" && numericEntry.isNotEmpty() -> if (pathActive) commitTypedLeg() else commitTypedScalar()
            // a repeating tool (Outline) commits on Enter and abandons on Escape
            key == "Enter" && !pathActive -> finishRepeatingTool()
            // Escape abandons the pending tool application — its picks *and* the parameters typed for it,
            // which [resetPicks] retracts, so cancelling leaves nothing behind (see [checkpoint])
            key == "Escape" && !pathActive && (filledSlots > 0 || pendingTypedParams.isNotEmpty()) -> {
                resetPicks()
                statusHint = ""
                onChange()
                true
            }
            // With nothing pending, Escape drops what a click would otherwise have to un-click: the active
            // parameter, and the selection. The parameter belongs here because a pick that cannot be dropped
            // shadows every *defaulted* scalar slot for the rest of the session (see [clickScalar]).
            key == "Escape" && !pathActive && (selected.isNotEmpty() || activeScalar != null) -> {
                val had = selected.isNotEmpty()
                val dropped = clearActiveScalar()
                if (had) clearSelection()
                statusHint =
                    when {
                        had && dropped -> "Selection cleared, and $NO_ACTIVE_PARAMETER"
                        had -> "Selection cleared"
                        else -> NO_ACTIVE_PARAMETER
                    }
                onChange()
                true
            }
            // The keyboard twin of clicking the same spot again (OP-13: nothing is reachable one way and not
            // the other). Only while a click's cycle is live — there is nothing to step otherwise.
            key == "Tab" && cycleWorld != null -> advanceCycle()
            key == "Escape" || key == "Enter" -> {
                finishPath()
                true
            }
            // a tool's own key: the palette without the round trip to it. Last, so nothing that was
            // already being typed loses a character to it.
            key.length == 1 && key[0].isLetter() -> {
                val id = Tools.byShortcut(key[0]) ?: return false
                if (id != toolId) setTool(id)
                statusHint = currentHelp()
                onChange()
                true
            }
            else -> false
        }
    }

    /**
     * The scalar slot a typed number would fill, or null when the armed tool takes none (and while a path
     * is being drawn, where digits are the leg's length).
     *
     * Deliberately offered **even when the panel picks would already satisfy the tool**: a tool consumes
     * the last picks in order, so without this a value could never be *overridden* by typing once anything
     * had been picked — and silently reusing yesterday's radius is the friction, not the fix. Typing simply
     * creates the parameter and makes it the newest pick.
     */
    private fun typedScalarSlot(): ScalarSlot? {
        if (activePath != null) return null
        val tool = doc.toolDef(toolId) ?: return null
        if (tool.scalars.isEmpty()) return null
        return tool.scalars[minOf(typedScalars, tool.scalars.size - 1)]
    }

    private fun typedScalarPrompt(): String {
        val slot = typedScalarSlot() ?: return ""
        if (numericEntry.isEmpty()) return currentHelp()
        return "${slot.name} = $numericEntry ${unitWord(slot.dim)} — Enter to use it, Esc to cancel"
    }

    private fun unitWord(dim: Dimension): String =
        when (dim) {
            Dimension.LENGTH -> "mm"
            Dimension.ANGLE -> "°"
            else -> ""
        }

    /**
     * Turn the typed number into an ordinary **parameter** and hand it to the tool's scalar slot — the
     * generalization of direct distance entry to every scalar input (OP-13: typing and picking are the
     * same operation, so a value that was typed is afterwards editable, wireable and shareable like any
     * other).
     *
     * It is named after the slot and uniquified exactly as a panel parameter is (`depth`, `depth2`), so
     * nothing downstream can tell it was typed: it appears in the panel, rides the `param` step (OP-18)
     * and can be dragged into by anything that consumes a scalar.
     */
    private fun commitTypedScalar(): Boolean {
        val slot = typedScalarSlot() ?: return false
        val value = numericEntry.toDoubleOrNull()
        numericEntry = ""
        if (value == null) {
            statusHint = "That is not a number"
            onChange()
            return true
        }
        typedScalars++
        val entry = doc.newParameter(slot.name, quantityOf(slot.dim, value))
        // Deliberately NOT a checkpoint of its own: the value was typed to feed the armed tool, so it is
        // *half* of that operation — the tool's own commit seals both as one snapshot, and an abandoned
        // gesture retracts it (see [checkpoint] for the whole rule). Registered here, before the pick is
        // published, because publishing may complete the tool outright when the clicks are already in —
        // and that completion's checkpoint is exactly what has to seal it.
        pendingTypedParams.add(entry)
        // through the ordinary setter, so a typed scalar *is* a panel pick as far as every tool is
        // concerned — including its completing a tool that was only waiting for this value
        activeScalar = entry
        // still pending means the tool has not consumed it yet, so say the value is in; a tool that
        // completed on the spot has already said its own thing
        if (pendingTypedParams.any { it === entry }) {
            statusHint = "${slot.name} = $value ${unitWord(slot.dim)} (parameter ${entry.name}) — edit it in the panel any time"
        }
        onChange()
        return true
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
            statusHint = "Opening: type a width (or click a parameter in the panel) first"
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
        val ended = markTerminal(path)
        doc.discardOrthoPath(path) // a path that never got a second vertex isn't a path
        activePath = null
        pathAtEnd = true
        pathClosed = false
        previewSeg = null
        closePreview = emptyList()
        pathThickness = null
        checkpoint() // the whole path — start, legs, close, footprint — commits as one operation
        statusHint = ended ?: ""
        onChange()
    }

    /**
     * Mark the vertex a run **ended on** and say so, when it ended on something at all.
     *
     * A run that reaches geometry stops there by design, and the only signal used to be a quiet status
     * line — so a click meant as an intermediate corner silently ended the drawing, and the *next* click
     * (which then started a fresh path) read as nothing having happened. The cue is therefore generic:
     * whatever ended the run — welded to a point, attached to a curve, or closed — the terminus is marked
     * and the words say the run is finished, because what has to be noticed is that drawing stopped, not
     * which construction stopped it. The rubber band disappearing is the second half of the same signal:
     * a band means "still drawing", and there is none after a terminal click.
     *
     * Returns the status line for that ending, or null when the run merely stopped in the air (Esc,
     * double-click, tool switch), which needs no mark: nothing was reached.
     */
    private fun markTerminal(path: OrthoPath): String? {
        terminalHint = null
        if (path.vertices.size < 2) return null
        val what = if (toolId == Tools.WALL) "Wall" else "Ortho path"
        val closed = pathClosed
        val v = if (closed || !pathAtEnd) path.vertices.first() else path.vertices.last()
        val el = doc.elementFor(v.ref) ?: return null
        // a still-dangling end is not a terminus — it is exactly the end a later click may continue
        if (!closed && doc.orthoEndpoint(el) != null) return null
        terminalHint = (ev().valueOf(v.ref) as? PointValue)?.p
        return "$what ${if (closed) "closed on" else "ends on"} ${doc.nameOf(el)}$RUN_FINISHED"
    }

    // ---- the one pick cycle (OP-16 / OP-13 / OP-21) ----

    /**
     * Everything a SELECT click at [world] could mean, **ranked by the precedence a press has always used**,
     * with the two facts only the press can decide beside them (see [PickPile]).
     *
     * The order, written down once instead of grown three times:
     *
     * 1. **every point first**, by precedence rather than by distance, and the reason is the user's: *a point
     *    cannot dodge, a curve can be clicked elsewhere.* That holds however the point was born, so a derived
     *    one — an intersection, a midpoint, a ratio point, a key point — outranks the curve it sits on just as
     *    a free vertex does. Among the points the **draggable** ones come first, which is the grab rule read as
     *    a ranking (a dead handle must not steal a grab from what drives it).
     * 2. then the draggable **curves** (an ortho leg), then a draggable **annotation** — last of the three,
     *    so a dimension lying over the geometry it names never steals its grab.
     * 3. then an opening's **jamb** (OP-21), which competes with the curves *by distance* instead of by
     *    precedence — the [reach] cap: along the wall the leg is nearer, across it the jamb is. That
     *    remains the **ranking**; what cycling adds is that the loser is now reachable.
     * 4. then everything else the pointer can address at all, nearest first, so nothing drawn is unreachable.
     *
     * A hit that belongs to a group contributes **two consecutive entries** — the whole group, then that
     * member alone (OP-16: a group is what a click means first, and a member must stay reachable past it).
     *
     * **Selection rank and drag rank are not the same thing, deliberately.** This ranks what a *click*
     * addresses; [PickPile.movable] is what a *drag* moves, and it keeps preferring a movable handle exactly
     * as the press always has. So a derived point now takes the click while the curve under it still takes
     * the drag, and a point with no freedom simply has no drag — which is the honest answer either way: a
     * selection is for reading and deleting (OP-13's fields), a grab is for moving.
     */
    private fun pickAt(world: Vec2): PickPile {
        val ev = ev()
        val tol = tolWorld()
        val all = HitTest.nearestAll(doc, ev, world, tol) { it.selectable }.map { it.first }
        val points = all.filter { it.isPoint }.sortedBy { !it.draggable }
        val draggablePoint = points.firstOrNull { it.draggable }
        // An opening's jamb is not an element, so it is picked on its own and then *competes by distance* with
        // the curves. It has to: a jamb crosses its own carrier leg, so a rule of precedence would make one of
        // the two unreachable near the crossing — which is why the curve searches are capped at the jamb's
        // distance rather than at the tolerance. A *draggable* point still wins the grab outright, so the cap
        // does not apply when one is there — the ranking the press has always used, unchanged.
        val jamb = HitTest.nearestJamb(doc, ev, world, tol)
        val reach =
            if (draggablePoint == null && jamb != null) minOf(tol, HitTest.distanceToSegment(world, jamb.seg)) else tol
        val curves = HitTest.nearestAll(doc, ev, world, reach) { it.isCurve && it.hasFreeDof }.map { it.first }
        val annotations = HitTest.nearestAll(doc, ev, world, reach) { it.annotation != null && it.hasFreeDof }.map { it.first }
        val movable = draggablePoint ?: curves.firstOrNull() ?: annotations.firstOrNull()
        val ranked = points + (curves + annotations).filter { el -> points.none { it === el } }
        // everything else that can be addressed at all — an area, a solid's footprint hint, a curve the jamb's
        // cap kept out of the grab. Immovable here means "selectable but not draggable", which is a reason to
        // *say* something (see [explainImmovable]), not a reason to be unreachable.
        val rest = all.filter { el -> ranked.none { it === el } }
        val out = ArrayList<Candidate>()

        fun offer(el: Element) {
            doc.groupOf(el)?.let { out.add(Candidate.Whole(it, el)) }
            out.add(Candidate.One(el))
        }
        ranked.forEach { offer(it) }
        if (jamb != null) out.add(Candidate.Opening(jamb))
        rest.forEach { offer(it) }
        // what the press addresses is what the first candidate names; what it *drags* may be something else
        return PickPile(out, movable, if (movable == null) jamb else null, ranked.firstOrNull() ?: rest.firstOrNull())
    }

    /**
     * Where the live cycle **stands** in [pile] — the candidate the previous click selected — or null when
     * this press does not continue it, which is the ordinary first click.
     *
     * A press continues the cycle when it lands within [REPEAT_CLICK_PX] of the click that owns it *and* that
     * click's selection still stands: if anything else has selected in between (a marquee, a drag, the panel),
     * the cursor is over a pile the cycle no longer describes, and starting over is the honest answer.
     *
     * This is the whole of the first-click invariant — a press with nothing standing selects and drags exactly
     * what it always did — and it is also what makes cycling *useful*: the standing candidate is what the drag
     * is primed with (see [pointerDown]).
     */
    private fun standingIndex(
        screen: Vec2,
        pile: PickPile,
    ): Int? {
        if (pile.candidates.isEmpty()) return null
        val at = cycleAt ?: return null
        if ((screen - at).length() > REPEAT_CLICK_PX) return null
        if (cycleIndex !in pile.candidates.indices || !addresses(pile.candidates[cycleIndex])) return null
        return cycleIndex
    }

    /** Whether the selection currently addresses [c] — how the cycle knows where it stands. */
    private fun addresses(c: Candidate): Boolean =
        when (c) {
            is Candidate.Whole -> selectedGroup === c.group && selection === c.primary
            is Candidate.One -> selectedGroup == null && selected.size == 1 && selection === c.element
            // a Jamb is derived fresh from the drawing on every pass, so it is identified by what it *is*
            is Candidate.Opening ->
                selectedJamb?.let { it.interval === c.jamb.interval && it.atEnd == c.jamb.atEnd } ?: false
        }

    /**
     * Select exactly what [c] names and return the note for the status bar.
     *
     * History-free by construction: each candidate is a complete answer, so this is the whole of "what a
     * click selects" — the group/member reach and the jamb-vs-leg alternative are entries in one list rather
     * than two remembered states (OP-16, OP-21).
     */
    private fun selectPick(c: Candidate): String =
        when (c) {
            is Candidate.Whole -> {
                val members = doc.groupMembers(c.group)
                select(members, c.primary)
                selectedGroup = c.group
                if (c.group.placed) {
                    "Group ${c.group.name} is placed: dragging moves its frame (x / y / angle in the panel)"
                } else {
                    "Group ${c.group.name}: ${members.size} element${if (members.size == 1) "" else "s"}"
                }
            }
            is Candidate.One -> {
                select(listOf(c.element), c.element)
                val g = doc.groupOf(c.element)
                when {
                    g == null -> ""
                    g.placed -> "${doc.nameOf(c.element)} alone (of group ${g.name}) — dragging it moves it inside the frame"
                    else -> "${doc.nameOf(c.element)} alone (of group ${g.name})"
                }
            }
            is Candidate.Opening -> {
                selectJamb(c.jamb)
                describeJamb(c.jamb)
            }
        }

    /**
     * How a pick says where it stands in the pile it came from — `segment e12 (3 of 5 here — click again for
     * the next)`. A lone candidate gets nothing appended, so the everyday click reads exactly as before.
     */
    private fun cycleNote(
        base: String,
        pile: PickPile,
        i: Int,
    ): String {
        val n = pile.candidates.size
        if (n <= 1) return base
        val head = base.ifEmpty { pickLabel(pile.candidates[i]) }
        return "$head (${i + 1} of $n here — click again for the next)"
    }

    /** What one candidate is called, for the cycle's own note. */
    private fun pickLabel(c: Candidate): String =
        when (c) {
            is Candidate.Whole -> "group ${c.group.name}"
            is Candidate.One -> elementLabel(c.element)
            is Candidate.Opening -> "opening on leg ${c.jamb.interval.legIndex + 1} of ${doc.nameOf(c.jamb.path.footprint)}"
        }

    /** Step the cycle on, from wherever the last click left it. */
    private fun advanceCycle(): Boolean {
        val world = cycleWorld ?: return false
        val pile = pickAt(world)
        if (pile.candidates.isEmpty()) return false
        val i =
            if (cycleIndex in pile.candidates.indices && addresses(pile.candidates[cycleIndex])) {
                (cycleIndex + 1) % pile.candidates.size
            } else {
                0
            }
        statusHint = cycleNote(selectPick(pile.candidates[i]), pile, i)
        cycleIndex = i
        cycleCount = pile.candidates.size
        onChange()
        return true
    }

    /** Forget the live cycle: the next click is a first click. */
    private fun resetCycle() {
        cycleAt = null
        cycleWorld = null
        cycleIndex = 0
        cycleCount = 0
    }

    /** How many things the last click found under the cursor — 0 when no cycle is live. */
    val pickCycleSize: Int get() = if (cycleAt == null) 0 else cycleCount

    /** Which of them it selected, counting from 1 — 0 when no cycle is live. */
    val pickCyclePosition: Int get() = if (cycleAt == null) 0 else cycleIndex + 1

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
            // one opening slides along its leg (OP-21). No axis lock: the handle already has a single
            // direction of its own, exactly as an ortho leg does, so a lock could only make it inert
            dragJamb != null -> {
                val j = dragJamb!!
                j.handle(doc).drag(camera.screenToWorld(screen) - grabOffset, ev())
                // the clamp is the document's to explain, and it appears and disappears as the drag crosses
                // the leg's end rather than lingering afterwards
                statusHint = doc.takeNote() ?: describeJamb(j)
                onChange()
            }
            dragTarget != null -> {
                val el = dragTarget!!
                val world = axisLocked(camera.screenToWorld(screen) - grabOffset, el)
                el.handle?.drag(world, ev())
                // one seam for every route that can turn a host — an endpoint, a whole leg, a junction the
                // handle delegated to (OP-20): whatever the drag wrote, the riders are re-solved after it
                doc.compensateRiders(dragRiders)
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
            resetCycle() // neither a marquee nor a deselect is a step of any pick cycle
            downScreen = null
            onChange()
            return
        }
        // whether the gesture *moved* — read while [downScreen] still holds where the press landed, since
        // the deferred click semantics below clear it
        val moved = movedSince(screen)
        val toggle = pendingToggle
        val pile = pendingPile
        // whether the press deferred its pick — a member of a placed group, whose frame decision had to read
        // the selection the press *found* (OP-16)
        val deferred = pile != null && !pendingApplied
        pendingToggle = null
        pendingPile = null
        val dragged = dragTarget
        val movedFrame = dragFrame
        val movedJamb = dragJamb
        val weld = weldTarget
        val attach = attachTarget
        dragTarget = null
        dragFrame = null
        dragJamb = null
        dragStart = null
        dragRiders = emptyList()
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
        // The click half of the pick: this is where the cycle steps (and where a placed group's deferred pick
        // is applied at all). A press that *moved* is a drag and takes none of it — the same discipline
        // Shift's toggle uses, and the reason the group/member cycle was a release semantic to begin with.
        if (pile != null && pile.candidates.isNotEmpty() && !moved) {
            val i = pendingIndex.coerceIn(0, pile.candidates.size - 1)
            // The press already selected the first candidate and said its own sentence, which may be more
            // specific than any label here (why an element cannot be dragged, for one) — so a first click's
            // note is kept and only the deferred pick and the cycle's steps produce a new one.
            if (deferred || i != 0) {
                statusHint = cycleNote(selectPick(pile.candidates[i]), pile, i)
                onChange()
            }
            cycleAt = screen
            cycleWorld = camera.screenToWorld(screen)
            cycleIndex = i
            cycleCount = pile.candidates.size
        } else if (moved) {
            // a drag is not a click, so it leaves no cycle behind: the next click starts from the top
            resetCycle()
        }
        // …and the drag half of the deferred decision (OP-16): a press that *moved* never reaches the group —
        // its subject was the member's own handle (see [pointerDown]) — so what stays selected is the
        // element it moved, exactly as dragging ungrouped geometry leaves the dragged element selected. A
        // later weld or join may still replace this with what it produced; both run below.
        if (deferred && moved && dragged != null) select(listOf(dragged), dragged)
        downScreen = null
        if (dragged != null) {
            val ortho = dragged.handle is OrthoCornerHandle
            if (weld != null) {
                val ok = if (ortho) doc.weldOrthoEndpointToPoint(dragged, weld) else doc.weld(dragged, weld)
                // the magnet promised this join, so a release that quietly does nothing is the worst of the
                // three outcomes — the reason is the document's, and the same one a path click reports
                statusHint = if (ok) "Joined ${doc.nameOf(dragged)} onto ${doc.nameOf(weld)}" else joinRefused(dragged, weld)
                onChange()
            } else if (attach != null) {
                val ok = if (ortho) doc.attachOrthoEndpointToCurve(dragged, attach) else doc.attachToCurve(dragged, attach)
                statusHint = if (ok) "Attached ${doc.nameOf(dragged)} to ${doc.nameOf(attach)}" else joinRefused(dragged, attach)
                onChange()
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
        // an opening's slide commits like every other drag: one operation, one undo step. What the status
        // says is left as the last move left it — either the opening's values or the clamp that stopped them
        if (movedJamb != null && moved) {
            checkpoint()
            onChange()
        }
        jambStoleFrame = null
        if (dragged != null) {
            joinFlattenedEnds(dragged)?.let {
                select(listOf(it), it)
                statusHint = "Joined into ${doc.nameOf(it)} — the flattened corner is gone"
                onChange()
            }
            // the release is where a drag commits — moves, welds, attaches and joins are one operation
            checkpoint()
        }
    }

    /** What to say when a release could not make the connection its magnet offered — see [refusal]. */
    private fun joinRefused(
        dragged: Element,
        target: Element,
    ): String {
        val why = doc.connectRefusal(dragged, target)
        return "Can't join ${doc.nameOf(dragged)} onto ${doc.nameOf(target)}${if (why == null) "" else ": $why"}"
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

    /**
     * Leg [i]'s perpendicular coordinate — the value a join keeps when this is the stationary half.
     *
     * Read from the path's own node ([Document.legPerpValue]), not off the drawn segment: under a frame
     * (OP-16) the drawn position is a world one while the node a join writes holds a local coordinate, and
     * asking the node is right in both cases.
     */
    private fun legPerp(
        path: OrthoPath,
        i: Int,
    ): Double? = doc.legPerpValue(path, i)

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

    /**
     * [world] projected onto [jamb]'s own (infinite) line — where the grab holds on.
     *
     * The jamb runs *across* the carrier, so this keeps the perpendicular component of the cursor and pins
     * the along-leg one to the jamb itself: the offset the grab then holds is purely along the leg, which is
     * the only component the drag can write anyway.
     */
    private fun onJambLine(
        jamb: Jamb,
        world: Vec2,
    ): Vec2 {
        val across = jamb.seg.b - jamb.seg.a
        if (across.length() < Vec2.EPS) return jamb.seg.a
        val dir = across.normalized()
        return jamb.seg.a + dir * (world - jamb.seg.a).dot(dir)
    }

    /** What an opening reads as in the status bar: its two leg-relative values, in the panel's units. */
    private fun describeJamb(jamb: Jamb): String {
        val ev = ev()
        val pos = ev.valueOf(jamb.interval.position) as? ScalarValue
        val width = ev.valueOf(jamb.interval.width) as? ScalarValue
        val where = "${Format.num(pos?.q?.mm ?: 0.0)} mm along leg ${jamb.interval.legIndex + 1}"
        val edge = if (jamb.atEnd) "far jamb sets the width" else "near jamb slides the opening"
        // what the grab took the gesture *from*, for the whole gesture — see [jambStoleFrame]
        val instead =
            jambStoleFrame?.let { " (this opening, not group ${it.name} — grab the wall away from a jamb to move the frame)" }
                ?: ""
        return "Opening at $where, ${Format.num(width?.q?.mm ?: 0.0)} mm wide — $edge$instead"
    }

    /** Where a (possibly zero-length) leg sits, for marking it on the canvas. */
    private fun legPoint(el: Element): Vec2? =
        (ev().valueOf(el.ref) as? constructit.core.SegmentValue)?.seg?.let { Vec2((it.a.x + it.b.x) / 2, (it.a.y + it.b.y) / 2) }

    /**
     * Whether dropping [el] can join it to something: a point that **still owns its own coordinates**, or an
     * open path end.
     *
     * The test is exactly the one the release performs — a bound point has nothing left to bind. That covers
     * every way a point can already follow something in one predicate: held frame-relative by a placed group
     * (OP-16), welded, attached, or *re-parameterized onto an anchor* (OP-4 case b). The last of those was the
     * hole: a relative point kept `ElementKind.POINT`, so the magnet offered an attach the release then
     * refused — and since a refused attach was still recorded, dragging such a point round the circle it
     * defines wrote a junk `attach` step per drag (the duplicated steps in the reported file). No halo may
     * offer a join that the release would refuse (OP-20's rule for the drag magnet).
     *
     * An end of a **placed** path is out for the same reason one level up: a junction is a world position and
     * that end's coordinates are its group's local ones (see `Document.bindCornerToJunction`).
     */
    private fun canConnect(el: Element): Boolean =
        (el.kind == ElementKind.POINT && isFreeSource(el.ref.node)) ||
            (el.handle as? OrthoCornerHandle)?.let { it.isEndpoint && doc.pathFrameOf(it) == null } == true

    /** True when the active tool's next slot creates a point — the case a snap marker is useful for. */
    private fun placesAPoint(): Boolean {
        val tool = doc.toolDef(toolId) ?: return false
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
                statusHint = "Can't join ${doc.nameOf(dragged)} onto ${doc.nameOf(point)}: ${doc.nameOf(point)} already follows ${doc.nameOf(dragged)}."
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
        val tool = doc.toolDef(toolId) ?: return false
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
     * **Follow the boundary** from the picks already collected, appending every piece whose continuation is
     * unique, and closing the outline when it comes back to the first piece (OP-14).
     *
     * Two picks fix a direction; from there, at the end of the current piece the document is asked which
     * pieces hand over there ([Document.continuationsFrom] — constructed joints and coincident endpoints,
     * never an intersection and never a seed point). Exactly one answer is not a decision, so it is taken;
     * anything else stops the follow and the user keeps clicking.
     *
     * **What this does *not* do is discover the loop at replay time.** The pieces it appends are appended to
     * the pick list, so the recorded step carries the full ordered boundary and replay re-runs the same
     * `ToolDef.build` over the same list — OP-14's rejection of *"the loop's identity would be discovered
     * rather than constructed"* is untouched, because nothing follows anything on load. The follow lives
     * strictly in the *gesture*: it saves clicks, and it is the click log that is stored.
     *
     * Stop conditions, all reported: a dead end (nothing continues), a fork (two or more do), a piece
     * already in the chain, a whole circle (which of its two arcs is meant is a genuine choice — OP-1), and
     * a pair with no constructed joint at all (two crossing lines, say), where there is nothing to follow.
     */
    private fun extendBoundaryPicks() {
        val ev = ev()
        val first = pickedElements.firstOrNull() ?: return
        val start = pickedElements.last()
        var entered = doc.handoverPosition(pickedElements[pickedElements.size - 2], start, ev) ?: return
        // (piece, where the boundary enters it) — collected first, because a piece's *click* needs both of
        // its joints and the second one is only known once the next piece is known
        val chain = ArrayList<Pair<Element, Vec2>>()
        var closed = false
        var stop: String? = null
        // a chain cannot visit more pieces than the document has
        var budget = doc.elements.size + 1
        while (budget-- > 0) {
            val cur = chain.lastOrNull()?.first ?: start
            val next = doc.continuationsFrom(cur, entered, ev)
            if (next.isEmpty()) {
                stop = "nothing continues past ${doc.nameOf(cur)}"
                break
            }
            if (next.size > 1) {
                stop = "${next.size} pieces meet past ${doc.nameOf(cur)}, so the boundary forks there — pick the one you mean"
                break
            }
            val step = next.single()
            if (step.piece === first) {
                // back where it started: that is the boundary closed — unless only two pieces are in, which
                // is the tracer's own two-meetings special case and stays a deliberate Enter ([buildOutline])
                if (pickedElements.size + chain.size >= 3) closed = true
                break
            }
            if (pickedElements.any { it === step.piece } || chain.any { it.first === step.piece }) {
                stop = "the boundary rejoins ${doc.nameOf(step.piece)}, which is already in it"
                break
            }
            if (step.piece.kind == ElementKind.CIRCLE) {
                stop = "${doc.nameOf(step.piece)} continues there, but which arc of a whole circle the boundary takes is a choice — click it"
                break
            }
            chain.add(step.piece to step.at)
            entered = step.at
        }
        if (chain.isEmpty()) {
            // a fork is worth saying even when nothing was followed: it is why the tool went quiet
            if (stop != null && stop.contains("forks")) statusHint = "$stop ($filledSlots picked)"
            return
        }
        for ((i, link) in chain.withIndex()) {
            val (piece, enter) = link
            // the far joint: the next piece's, the first piece's when the loop closed, else simply the far
            // end of the piece — which is where the user will click next anyway
            val exit =
                chain.getOrNull(i + 1)?.second
                    ?: (if (closed) doc.handoverPosition(piece, first, ev) else null)
                    ?: doc.farEndOf(piece, enter, ev)
                    ?: enter
            pickedElements.add(piece)
            pickedClicks.add(doc.pointBetweenOn(piece, enter, exit, ev) ?: enter)
            filledSlots++
        }
        if (closed) {
            val n = filledSlots
            finishRepeatingTool()
            statusHint = "Followed the boundary round through $n pieces and closed it"
            return
        }
        val last = pickedElements.last()
        val why = stop?.let { " — $it" } ?: ""
        statusHint =
            "Followed ${chain.size} piece${if (chain.size == 1) "" else "s"} to ${doc.nameOf(last)} " +
            "($filledSlots picked)$why; click the next piece, or Enter to close"
    }

    /**
     * The scalars [tool] consumes: the last of the panel picks, in pick order. Null when too few have been
     * picked — the caller then says which ones are still wanted ([scalarPrompt]).
     */
    private fun toolScalars(tool: ToolDef): List<ScalarEntry>? {
        val need = tool.scalars.size
        if (scalarPicks.size < need) return if (tool.scalarsOptional) emptyList() else null
        val picks = scalarPicks.takeLast(need)
        // A **defaulted** slot is not waiting for anything (ScalarSlot.default), so it must not silently
        // adopt a pick that was meant for something else — and a length picked into a dimensionless ratio
        // would only make the point invalid (OP-7). A pick counts here when it is the kind of number the
        // slot asks for; anything else means "use the default", which is what the status line then names.
        if (tool.scalarsOptional && picks.zip(tool.scalars).any { (e, s) -> dimensionOf(e.ref) != s.dim }) return emptyList()
        return picks
    }

    /** What is still missing for [tool]'s scalar inputs, in the user's terms. */
    private fun scalarPrompt(tool: ToolDef): String {
        val have = scalarPicks.size
        val wanted = tool.scalars
        val missing = wanted.drop(have)
        val had = if (have == 0 || wanted.size == 1) "" else " (${wanted.take(have).joinToString(", ") { it.name }} picked)"
        return "${tool.label}: type ${missing.joinToString(", then ") { it.name }} — or click a parameter or measurement in the panel$had"
    }

    /** The structural count [tool] will build with — see [count]. Zero for a tool that needs none. */
    private fun toolCount(tool: ToolDef): Int = if (tool.minCount == 0) 0 else maxOf(count, tool.minCount)

    private fun runToolClick(screen: Vec2) {
        val tool = doc.toolDef(toolId) ?: return
        val world = camera.screenToWorld(screen)
        // a tool that cuts *the part of this face space* (OP-17) has nothing to cut in the plan — refused
        // here rather than at completion, so the reason is the one the user reads
        if (tool.facePartOperand && doc.facePartTip() == null) {
            statusHint =
                "${tool.label} works on a face: use Sketch on face to pick a solid's edge first, " +
                "then draw and cut there"
            onChange()
            return
        }
        pickRefusal = null
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
                // ...and an arc also carries a circle: the twin coercion, so a circle slot takes one
                SlotKind.CIRCLE -> pickElement(world) { it.isCentric }
                SlotKind.SEGMENT -> pickElement(world) { it.kind == ElementKind.SEGMENT }
                SlotKind.GEOMETRY -> pickGeometry(world, tool)
                SlotKind.ON_CIRCLE_POINT -> pickElement(world) { it.handle is OnCircleHandle }
                SlotKind.CENTRIC -> pickElement(world) { it.isCentric }
                // a fillet leg: either carrier will do, since all the rounding needs is something to be
                // tangent to
                SlotKind.CARRIER -> pickElement(world) { it.isLinear || it.isCentric }
                // the seam's slot (OP-17): a traced outline or a thick path's footprint, both of which
                // bound an area — the coercion between them is the document's, not the pick's
                SlotKind.AREA -> pickElement(world, doc.areaPickFilter(ev()))
                // the boolean slot (OP-22): a solid, picked in 2D by the footprint hint it draws
                SlotKind.SOLID -> pickElement(world) { it.kind == ElementKind.SOLID }
                SlotKind.SIDE -> true // captures the click position only; creates nothing
            }
        // existing-only slots do NOT create anything on a miss — just hint and wait
        if (!picked) {
            // A miss must *say* it missed, and say where the operation stands. Silently keeping the old
            // count is the worst of the three possible answers: the drawing does not change, so nothing on
            // screen distinguishes "that curve is in" from "that click landed in space". A pick that hit
            // something and was *refused* has a better reason of its own, and says that instead.
            val refused = pickRefusal
            statusHint =
                when {
                    refused != null -> refused
                    tool.repeating -> "That click hit no curve — $filledSlots picked so far. ${tool.help}"
                    else -> "That click hit nothing pickable — ${tool.help}"
                }
            onChange()
            return
        }
        if (slot != null) {
            filledSlots++
            pickedClicks.add(world)
            // …and what this click landed on, for a build that joins to it (see [Picks.landings]). Recorded
            // beside the click rather than *instead* of it: what a click position means is the tool's business
            // (a side, a quadrant, a sector), and only a tool that joins reads the landing.
            pickedLandings.add(snap(world).takeIf { it.linked })
        }

        if (tool.repeating) {
            statusHint = "${tool.help} ($filledSlots picked)"
            // two picks fix the direction, so from there the boundary can be followed wherever it is not
            // a choice — see [extendBoundaryPicks]
            if (filledSlots >= 2) extendBoundaryPicks()
            onChange()
            return
        }
        if (filledSlots >= tool.slots.size) {
            // A tool still missing a scalar **keeps its picks** and says what it wants: the number can then
            // be typed (or a parameter picked) and the tool finishes with the clicks already in. Throwing
            // the picks away was the older answer, and it made the geometry pay for a value's absence.
            if (!maybeCompleteTool(world)) statusHint = scalarPrompt(tool)
        } else {
            val fed = pickedGroup?.let { groupFedNote(it) + " " } ?: ""
            statusHint = "$fed${tool.help} (${tool.slots.size - filledSlots} more)"
        }
        onChange()
    }

    /**
     * A geometry pick, which is the one slot a **whole group** can fill (OP-16).
     *
     * The rule is the one the drag subject already follows: *a group acts as a whole only when selected as a
     * whole.* With [selectedGroup] naming the group the click landed in, the slot takes every member and the
     * tool fans over them; without it — a member reached alone, or a group nobody selected — the click means
     * that element and nothing else, exactly as before. Grouping is invisible until something of it is
     * selected, so this is the only reading under which a click cannot copy more than the user can see.
     */
    private fun pickGeometry(
        world: Vec2,
        tool: ToolDef,
    ): Boolean {
        val hit = HitTest.nearest(doc, ev(), world, tolWorld()) { true } ?: return false
        val whole = if (tool.groupOperand) selectedGroup?.takeIf { doc.groupOf(hit) === it } else null
        val members = whole?.let { doc.groupMembers(it) }
        if (whole == null || members.isNullOrEmpty()) {
            pickedElements.add(hit)
            return true
        }
        // too many copies is a refusal, not a quiet fallback to the one element clicked: the user asked for
        // the group, and arraying one member instead would be a different construction
        val why = groupFanRefusal(tool, whole, members.size)
        if (why != null) {
            pickRefusal = why
            return false
        }
        pickedElements.addAll(members)
        pickedGroup = whole
        return true
    }

    /**
     * Build the armed tool if everything it needs is in: every slot picked and every scalar available.
     * The one place a non-repeating tool applies, so a click and a typed value reach it the same way.
     *
     * [at] is the click that completed it, for [Picks.at]; null when the *value* completed it, where the
     * last click is the honest answer instead.
     */
    private fun maybeCompleteTool(at: Vec2?): Boolean {
        val tool = doc.toolDef(toolId) ?: return false
        if (tool.repeating || filledSlots < tool.slots.size) return false
        val scalars = toolScalars(tool) ?: return false
        val where = at ?: pickedClicks.lastOrNull() ?: Vec2(0.0, 0.0)
        // OP-17's sequential-feature rule: the part this face space belongs to, as it stands *now*, fed in
        // as the first element so the step records which solid was cut (and replay never re-resolves it)
        val part = if (tool.facePartOperand) doc.facePartTip() ?: return false else null
        val picks =
            Picks(
                pickedPoints.toList(),
                listOfNotNull(part) + pickedElements.toList(),
                where,
                pickedClicks.toList(),
                count = toolCount(tool),
                landings = pickedLandings.toList(),
            )
        // read before [resetPicks] drops it: a group operand is worth reporting, because the one thing the
        // canvas cannot show is *how much* the tool just took (OP-16)
        val fedGroup = pickedGroup
        val members = picks.elements.size
        // a tool that records its own steps is *not* wrapped in a `tool` step (OP-18): what it builds has
        // degrees of freedom of its own that the steps it emits restate — see [ToolDef.recordsSteps]
        if (tool.recordsSteps) {
            tool.build(doc, picks, scalars.map { it.ref })
        } else {
            doc.recordingTool(tool.id, picks, scalars) { tool.build(doc, picks, scalars.map { it.ref }) }
        }
        checkpoint() // the tool application — earlier slot clicks were only halves of it
        resetPicks()
        // A tool that only *rewires* changes nothing the canvas can show (OP-4 case b: a point made relative
        // sits exactly where it did), so a silent success reads the same as a silent refusal. The document says
        // what happened — one channel, not a case per tool here.
        doc.takeNote()?.let {
            statusHint = it
            onChange()
            return true
        }
        statusHint =
            if (fedGroup == null) {
                ""
            } else {
                // the copies are deliberately *not* grouped (see OP-16's as-built note), so say it here
                // rather than leave the user to discover it by clicking one
                "${tool.label}: ${picks.count} instances of group ${fedGroup.name}'s $members element" +
                    "${if (members == 1) "" else "s"} — the copies are not grouped"
            }
        return true
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
