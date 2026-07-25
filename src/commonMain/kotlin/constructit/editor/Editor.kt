package constructit.editor

import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.dsl.PointRef
import constructit.dsl.valueOf
import constructit.geom.Vec2
import constructit.units.mm

/**
 * Pure interaction controller. In SELECT mode it drags free points (live recompute) or pans;
 * otherwise it runs the active [ToolDef] as a generic slot-collector, picking existing
 * geometry or creating points per click, and consuming the active parameter for scalar slots.
 * No platform APIs — fully headless-testable by simulating gestures.
 */
class Editor(
    doc: Document = Document(),
    var canvasW: Double = 800.0,
    var canvasH: Double = 600.0,
) {
    var doc: Document = doc
        private set

    /** Swap in a loaded document, dropping every reference into the old one. */
    fun replaceDocument(fresh: Document) {
        doc = fresh
        selection = null
        activeScalar = null
        resetPicks()
        statusHint = ""
        onChange()
    }

    var camera: Camera = Camera.centered(canvasW, canvasH)
    var toolId: String = Tools.SELECT
        private set
    var activeScalar: ScalarEntry? = null
    var onChange: () -> Unit = {}
    var showGrid: Boolean = false

    /**
     * Dim the construction that the results are built from (OP-14), so the drawing reads on its own.
     * A *view* setting: which elements are scaffolding is derived from the graph, so nothing is
     * flagged and nothing can drift out of date.
     */
    var dimScaffolding: Boolean = false

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

    /**
     * The element last picked in SELECT mode. Selecting is what makes a handle's numeric fields
     * addressable: the drag is on the canvas, the typed form is in the inspector, and both write the
     * same nodes (OP-13).
     */
    var selection: Element? = null
        private set
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
    private var dragStart: Vec2? = null // where on the geometry the drag began — the axis-lock origin
    private var grabOffset: Vec2 = Vec2(0.0, 0.0) // cursor minus that, so a grab never jumps
    private var joinHints: List<Vec2> = emptyList() // corners this drag has flattened, marked on canvas
    private var weldTarget: Element? = null // a point to weld onto
    private var attachTarget: Element? = null // a curve to attach onto
    private var haloPos: Vec2? = null // where the magnet ring is drawn
    private var panning = false
    private var lastScreen = Vec2(0.0, 0.0)
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
        dragStart = null
        grabOffset = Vec2(0.0, 0.0)
        joinHints = emptyList()
        weldTarget = null
        attachTarget = null
        haloPos = null
        panning = false
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

    /** The typed views of the selection's handle — the same writes its drag performs (OP-13). */
    fun selectionFields(): List<HandleField> = selection?.handle?.fields() ?: emptyList()

    /** Short name for the selection, for the inspector header. */
    fun selectionLabel(): String {
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
        return if (toolId == Tools.SELECT) Tools.SELECT_HELP else Tools.byId(toolId)?.help ?: ""
    }

    fun render(target: DrawTarget) {
        SceneRenderer.render(
            doc, Evaluator(), camera, target, canvasW, canvasH, showGrid, haloPos, previewSeg, selection,
            snapHint?.pos, joinHints, closePreview,
            dimmed = if (dimScaffolding) doc.scaffoldingElements().toHashSet() else emptySet(),
        )
    }

    fun wheel(
        screen: Vec2,
        deltaY: Double,
    ) {
        camera = camera.zoomAt(screen, if (deltaY < 0) 1.1 else 1.0 / 1.1)
        onChange()
    }

    fun pointerDown(screen: Vec2) {
        if (toolId == Tools.SELECT) {
            val world = camera.screenToWorld(screen)
            // a vertex wins over the legs meeting at it; a leg drags perpendicular (OrthoEdgeHandle)
            val movable =
                HitTest.nearestFreePoint(doc, ev(), world, tolWorld())
                    ?: HitTest.nearestDraggableCurve(doc, ev(), world, tolWorld())
            // an immovable element is still selectable, so its values can be read and the reason shown
            val hit = movable ?: HitTest.nearestSelectable(doc, ev(), world, tolWorld())
            selection = hit // a miss clears it, so clicking empty space deselects
            when {
                movable != null -> {
                    dragTarget = movable
                    // drag by the *offset* from where the grab landed, not to the cursor outright:
                    // picking has a tolerance, so writing the cursor position made the geometry jump to
                    // it on the first move and then follow from there
                    val anchor = grabAnchor(movable, world)
                    grabOffset = world - anchor
                    dragStart = anchor
                    statusHint = ""
                }
                hit != null -> statusHint = explainImmovable(hit)
                else -> {
                    panning = true
                    lastScreen = screen
                }
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

    /** One click of the opening tool: cut a door/window gap into the wall under the cursor. */
    private fun openingClick(world: Vec2) {
        val w = activeScalar
        if (w == null) {
            statusHint = "Opening: select a width parameter in the panel first"
            onChange()
            return
        }
        statusHint = if (doc.addOpeningAtRecorded(world, w.ref, tolWorld() * 2)) "Opening added" else "Click on a wall to place an opening"
        onChange()
    }

    /** Finish the current path (Esc / double-click / close / tool switch); for WALL, build faces. */
    fun finishPath() {
        val path = activePath ?: return
        if (pathClosed) doc.closeOrthoPath(path) // snaps the last coordinate to fit, adds the closing leg
        val t = pathThickness
        if (t != null && path.vertices.size >= 2) doc.buildWall(path, t)
        doc.discardOrthoPath(path) // a path that never got a second vertex isn't a path
        activePath = null
        pathAtEnd = true
        pathClosed = false
        previewSeg = null
        closePreview = emptyList()
        pathThickness = null
        statusHint = ""
        onChange()
    }

    fun pointerMove(screen: Vec2) {
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
            panning -> {
                camera = camera.pan(screen.x - lastScreen.x, screen.y - lastScreen.y)
                lastScreen = screen
                onChange()
            }
        }
    }

    fun pointerUp(
        @Suppress("UNUSED_PARAMETER") screen: Vec2,
    ) {
        val dragged = dragTarget
        val weld = weldTarget
        val attach = attachTarget
        dragTarget = null
        dragStart = null
        joinHints = emptyList()
        clearMagnet() // clear before rendering so the magnet halo doesn't linger
        panning = false
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
        if (dragged != null) {
            joinFlattenedEnds(dragged)?.let {
                selection = it
                statusHint = "Joined into ${it.id} — the flattened corner is gone"
                onChange()
            }
        }
    }

    /**
     * Apply [axisLock]: keep only the component the gesture is dominated by, relative to where the
     * drag started. A leg already moves on a single axis of its own, so the lock leaves it alone
     * rather than making it inert when the cursor happens to travel along it.
     */
    private fun axisLocked(
        world: Vec2,
        el: Element,
    ): Vec2 {
        val start = dragStart
        if (!axisLock || start == null || el.handle is OrthoEdgeHandle) return world
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
    ): Vec2 = (ev().valueOf(el.ref) as? PointValue)?.p ?: Snap.legPoint(ev(), el, world) ?: world

    /** Where a (possibly zero-length) leg sits, for marking it on the canvas. */
    private fun legPoint(el: Element): Vec2? =
        (ev().valueOf(el.ref) as? constructit.core.SegmentValue)?.seg?.let { Vec2((it.a.x + it.b.x) / 2, (it.a.y + it.b.y) / 2) }

    /** Whether dropping [el] can join it to something: a free point, or an open path end. */
    private fun canConnect(el: Element): Boolean =
        el.kind == ElementKind.POINT || (el.handle as? OrthoCornerHandle)?.isEndpoint == true

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
        if (filledSlots >= 2) {
            doc.recordingTool(tool.id, picks, activeScalar) { tool.build(doc, picks, activeScalar?.ref) }
            statusHint = ""
        } else {
            statusHint = "${tool.label}: needs at least two curves"
        }
        resetPicks()
        onChange()
        return true
    }

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
        val slot = if (tool.repeating) tool.slots.last() else tool.slots[filledSlots]
        val picked =
            when (slot) {
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
        filledSlots++
        pickedClicks.add(world)

        if (tool.repeating) {
            statusHint = "${tool.help} ($filledSlots picked)"
            onChange()
            return
        }
        if (filledSlots == tool.slots.size) {
            if (tool.scalar && activeScalar == null) {
                statusHint = "${tool.label}: select a parameter or measurement in the panel first"
                resetPicks()
                onChange()
                return
            }
            val picks = Picks(pickedPoints.toList(), pickedElements.toList(), world, pickedClicks.toList())
            doc.recordingTool(tool.id, picks, activeScalar) { tool.build(doc, picks, activeScalar?.ref) }
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
