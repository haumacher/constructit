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
    val doc: Document = Document(),
    var canvasW: Double = 800.0,
    var canvasH: Double = 600.0,
) {
    var camera: Camera = Camera.centered(canvasW, canvasH)
    var toolId: String = Tools.SELECT
        private set
    var activeScalar: ScalarEntry? = null
    var onChange: () -> Unit = {}
    var showGrid: Boolean = false

    /**
     * While set, a drag is restricted to the single axis its gesture is dominated by, measured from
     * where the drag started — so a corner can be moved in x *or* y without disturbing the other.
     * Read live, so it can be engaged or released mid-drag.
     */
    var axisLock: Boolean = false

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

    // transient state
    private var dragTarget: Element? = null // a point, or a whole ortho leg
    private var dragStart: Vec2? = null // where the drag began, in world space — the axis-lock origin
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
    private var pathClosed = false
    private var previewSeg: Pair<Vec2, Vec2>? = null
    private var pathThickness: constructit.dsl.ScalarRef? = null // set for the WALL tool

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
        weldTarget = null
        attachTarget = null
        haloPos = null
        panning = false
        activePath = null
        pathClosed = false
        previewSeg = null
        pathThickness = null
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
    fun currentHelp(): String =
        if (toolId == Tools.SELECT) Tools.SELECT_HELP else Tools.byId(toolId)?.help ?: ""

    fun render(target: DrawTarget) {
        SceneRenderer.render(doc, Evaluator(), camera, target, canvasW, canvasH, showGrid, haloPos, previewSeg, selection)
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
            val hit =
                HitTest.nearestFreePoint(doc, ev(), world, tolWorld())
                    ?: HitTest.nearestDraggableCurve(doc, ev(), world, tolWorld())
            selection = hit // a miss clears it, so clicking empty space deselects
            if (hit != null) {
                dragTarget = hit
                dragStart = world
            } else {
                panning = true
                lastScreen = screen
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
        runToolClick(screen)
    }

    /** One click of a path tool (ortho path / wall): start a chain, append a leg, or close the loop. */
    private fun pathClick(world: Vec2) {
        val path = activePath
        if (path == null) {
            activePath = doc.startOrthoPath(world)
            if (toolId == Tools.WALL) pathThickness = activeScalar?.ref
            statusHint = "${if (toolId == Tools.WALL) "Wall" else "Ortho path"}: click the next point; click the start to close (Esc/double-click to finish)"
        } else {
            val v0 = (ev().valueOf(path.vertices.first().ref) as? PointValue)?.p
            if (v0 != null && path.vertices.size >= 3 && (world - v0).length() <= tolWorld() * 2) {
                pathClosed = true
                finishPath()
                return // clicked the start -> close the loop
            }
            doc.addOrthoVertex(path, world)
        }
        previewSeg = null
        onChange()
    }

    /** One click of the opening tool: cut a door/window gap into the wall under the cursor. */
    private fun openingClick(world: Vec2) {
        val w = activeScalar
        if (w == null) {
            statusHint = "Opening: select a width parameter in the panel first"
            onChange()
            return
        }
        statusHint = if (doc.addOpeningAt(world, w.ref, tolWorld() * 2)) "Opening added" else "Click on a wall to place an opening"
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
        pathClosed = false
        previewSeg = null
        pathThickness = null
        statusHint = ""
        onChange()
    }

    fun pointerMove(screen: Vec2) {
        if (toolId == Tools.ORTHO_PATH) {
            previewSeg = activePath?.let { doc.orthoLegPreview(it, camera.screenToWorld(screen)) }
            onChange()
            return
        }
        when {
            dragTarget != null -> {
                val el = dragTarget!!
                val world = axisLocked(camera.screenToWorld(screen), el)
                val c = el.handle
                when {
                    // an open ortho-path end drags normally AND shows the weld/attach magnet
                    c is OrthoCornerHandle && c.isEndpoint -> {
                        c.drag(world, ev())
                        updateMagnet(el, world)
                    }
                    c != null -> {
                        c.drag(world, ev())
                        clearMagnet()
                    }
                    else -> {
                        doc.moveFreePoint(el, world)
                        updateMagnet(el, world)
                    }
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
        var best: Element? = null
        var bestPos: Vec2? = null
        var bestD = tolWorld()
        for (el in doc.elements) { // points win over curves at equal distance (checked first)
            if (el === dragged || !el.visible || !el.isPoint) continue
            val p = (ev.valueOf(el.ref) as? PointValue)?.p ?: continue
            val d = (p - world).length()
            if (d <= bestD) {
                bestD = d
                best = el
                bestPos = p
            }
        }
        if (best != null) {
            weldTarget = best
            attachTarget = null
            haloPos = bestPos
            return
        }

        bestD = tolWorld()
        for (el in doc.elements) {
            if (el === dragged || !el.visible || !el.isCurve) continue
            val pos = doc.curveProjection(dragged, el) ?: continue
            val d = (pos - world).length()
            if (d <= bestD) {
                bestD = d
                best = el
                bestPos = pos
            }
        }
        if (best != null) {
            attachTarget = best
            weldTarget = null
            haloPos = bestPos
        } else {
            clearMagnet()
        }
    }

    private fun runToolClick(screen: Vec2) {
        val tool = Tools.byId(toolId) ?: return
        val world = camera.screenToWorld(screen)
        val slot = tool.slots[filledSlots]
        val picked =
            when (slot) {
                SlotKind.PLACE_POINT -> {
                    pickedPoints.add(doc.freePoint(world.x.mm, world.y.mm))
                    true
                }
                SlotKind.POINT -> {
                    pickedPoints.add(pointOrCreate(world))
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

        if (filledSlots == tool.slots.size) {
            if (tool.scalar && activeScalar == null) {
                statusHint = "${tool.label}: select a parameter or measurement in the panel first"
                resetPicks()
                onChange()
                return
            }
            tool.build(doc, Picks(pickedPoints.toList(), pickedElements.toList(), world, pickedClicks.toList()), activeScalar?.ref)
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

    private fun pointOrCreate(world: Vec2): PointRef {
        val hit = HitTest.nearestAnyPoint(doc, ev(), world, tolWorld())
        @Suppress("UNCHECKED_CAST")
        if (hit != null) return hit.ref as PointRef
        return doc.freePoint(world.x.mm, world.y.mm)
    }
}
