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
    var statusHint: String = ""
        private set

    private val tolPx = 10.0
    private fun tolWorld() = tolPx / camera.scale
    private fun ev() = Evaluator()

    // transient state
    private var dragPoint: Element? = null
    private var weldTarget: Element? = null      // a point to weld onto
    private var attachTarget: Element? = null    // a curve to attach onto
    private var haloPos: Vec2? = null            // where the magnet ring is drawn
    private var panning = false
    private var lastScreen = Vec2(0.0, 0.0)
    private val pickedPoints = ArrayList<PointRef>()
    private val pickedElements = ArrayList<Element>()
    private val pickedClicks = ArrayList<Vec2>()
    private var filledSlots = 0

    // ortho-path (turtle) state
    private val pathVertices = ArrayList<PointRef>()
    private var pathActive = false
    private var previewSeg: Pair<Vec2, Vec2>? = null
    private var pathThickness: constructit.dsl.ScalarRef? = null   // set for the WALL tool

    val pendingCount: Int get() = filledSlots

    fun setTool(id: String) {
        toolId = id
        resetPicks()
        statusHint = ""
        onChange()
    }

    private fun resetPicks() {
        pickedPoints.clear(); pickedElements.clear(); pickedClicks.clear(); filledSlots = 0
        dragPoint = null; weldTarget = null; attachTarget = null; haloPos = null; panning = false
        pathVertices.clear(); pathActive = false; previewSeg = null; pathThickness = null
    }

    /** Set a transient status-bar note (e.g. panel feedback). */
    fun note(message: String) { statusHint = message }

    /** Help line for the active tool — shown in the status bar whenever there's no transient hint. */
    fun currentHelp(): String =
        if (toolId == Tools.SELECT) Tools.SELECT_HELP else Tools.byId(toolId)?.help ?: ""

    fun render(target: DrawTarget) {
        SceneRenderer.render(doc, Evaluator(), camera, target, canvasW, canvasH, showGrid, haloPos, previewSeg)
    }

    fun wheel(screen: Vec2, deltaY: Double) {
        camera = camera.zoomAt(screen, if (deltaY < 0) 1.1 else 1.0 / 1.1)
        onChange()
    }

    fun pointerDown(screen: Vec2) {
        if (toolId == Tools.SELECT) {
            val world = camera.screenToWorld(screen)
            val hit = HitTest.nearestFreePoint(doc, ev(), world, tolWorld())
            if (hit != null) dragPoint = hit else { panning = true; lastScreen = screen }
            return
        }
        if (toolId == Tools.ORTHO_PATH || toolId == Tools.WALL) {
            if (toolId == Tools.WALL && !pathActive && activeScalar == null) {
                statusHint = "Wall: select a thickness parameter in the panel first"; onChange(); return
            }
            pathClick(camera.screenToWorld(screen)); return
        }
        runToolClick(screen)
    }

    /** One click of a path tool (ortho path / wall): start a chain, or append an axis-aligned leg. */
    private fun pathClick(world: Vec2) {
        if (!pathActive) {
            pathVertices.clear(); pathVertices.add(pointOrCreate(world)); pathActive = true
            if (toolId == Tools.WALL) pathThickness = activeScalar?.ref
            statusHint = "${if (toolId == Tools.WALL) "Wall" else "Ortho path"}: click the next point (Esc or double-click to finish)"
        } else {
            doc.addOrthoLeg(pathVertices.last(), world)?.let { pathVertices.add(it) }
        }
        previewSeg = null
        onChange()
    }

    /** Finish the current path (Esc / double-click / tool switch); for the WALL tool, build faces. */
    fun finishPath() {
        if (!pathActive && pathVertices.isEmpty()) return
        val t = pathThickness
        if (t != null && pathVertices.size >= 2) doc.buildWall(pathVertices.toList(), t)
        pathActive = false; pathVertices.clear(); previewSeg = null; pathThickness = null; statusHint = ""
        onChange()
    }

    fun pointerMove(screen: Vec2) {
        if (toolId == Tools.ORTHO_PATH) {
            previewSeg = if (pathActive) doc.orthoLegPreview(pathVertices.last(), camera.screenToWorld(screen)) else null
            onChange()
            return
        }
        when {
            dragPoint != null -> {
                val el = dragPoint!!
                val world = camera.screenToWorld(screen)
                val c = el.constraint
                if (c != null) {
                    c.update(world, ev()); clearMagnet()
                } else {
                    doc.moveFreePoint(el, world)
                    updateMagnet(el, world)   // highlight a point to weld onto, or a curve to attach to
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

    fun pointerUp(@Suppress("UNUSED_PARAMETER") screen: Vec2) {
        val dragged = dragPoint
        val weld = weldTarget
        val attach = attachTarget
        dragPoint = null
        clearMagnet()                // clear before rendering so the magnet halo doesn't linger
        panning = false
        if (dragged != null) {
            if (weld != null && doc.weld(dragged, weld)) {
                statusHint = "Joined ${dragged.id} onto ${weld.id}"; onChange()
            } else if (attach != null && doc.attachToCurve(dragged, attach)) {
                statusHint = "Attached ${dragged.id} to ${attach.id}"; onChange()
            }
        }
    }

    private fun clearMagnet() { weldTarget = null; attachTarget = null; haloPos = null }

    /**
     * Magnet: prefer a nearby point to weld onto; otherwise a nearby curve to attach onto. Sets
     * [haloPos] to the point (or the projection onto the curve) where the drag will land.
     */
    private fun updateMagnet(dragged: Element, world: Vec2) {
        val ev = ev()
        var best: Element? = null
        var bestPos: Vec2? = null
        var bestD = tolWorld()
        for (el in doc.elements) {   // points win over curves at equal distance (checked first)
            if (el === dragged || !el.visible || !el.isPoint) continue
            val p = (ev.valueOf(el.ref) as? PointValue)?.p ?: continue
            val d = (p - world).length()
            if (d <= bestD) { bestD = d; best = el; bestPos = p }
        }
        if (best != null) { weldTarget = best; attachTarget = null; haloPos = bestPos; return }

        bestD = tolWorld()
        for (el in doc.elements) {
            if (el === dragged || !el.visible || !el.isCurve) continue
            val pos = doc.attachTargetPos(dragged, el) ?: continue
            val d = (pos - world).length()
            if (d <= bestD) { bestD = d; best = el; bestPos = pos }
        }
        if (best != null) { attachTarget = best; weldTarget = null; haloPos = bestPos } else clearMagnet()
    }

    private fun runToolClick(screen: Vec2) {
        val tool = Tools.byId(toolId) ?: return
        val world = camera.screenToWorld(screen)
        val slot = tool.slots[filledSlots]
        val picked = when (slot) {
            SlotKind.PLACE_POINT -> { pickedPoints.add(doc.freePoint(world.x.mm, world.y.mm)); true }
            SlotKind.POINT -> { pickedPoints.add(pointOrCreate(world)); true }
            SlotKind.EXISTING_POINT -> pickElement(world) { it.isPoint }
            SlotKind.CURVE -> pickElement(world) { it.isCurve }
            SlotKind.LINE -> pickElement(world) { it.isLinear }   // a segment or ray also carries a line
            SlotKind.CIRCLE -> pickElement(world) { it.kind == ElementKind.CIRCLE }
            SlotKind.SEGMENT -> pickElement(world) { it.kind == ElementKind.SEGMENT }
            SlotKind.GEOMETRY -> pickElement(world) { true }
            SlotKind.ON_CIRCLE_POINT -> pickElement(world) { it.constraint is OnCircleConstraint }
            SlotKind.CENTRIC -> pickElement(world) { it.kind == ElementKind.CIRCLE || it.kind == ElementKind.ARC }
            SlotKind.SIDE -> true   // captures the click position only; creates nothing
        }
        // existing-only slots do NOT create anything on a miss — just hint and wait
        if (!picked) { statusHint = tool.help; onChange(); return }
        filledSlots++
        pickedClicks.add(world)

        if (filledSlots == tool.slots.size) {
            if (tool.scalar && activeScalar == null) {
                statusHint = "${tool.label}: select a parameter or measurement in the panel first"
                resetPicks(); onChange(); return
            }
            tool.build(doc, Picks(pickedPoints.toList(), pickedElements.toList(), world, pickedClicks.toList()), activeScalar?.ref)
            resetPicks()
            statusHint = ""
        } else {
            statusHint = "${tool.help} (${tool.slots.size - filledSlots} more)"
        }
        onChange()
    }

    private fun pickElement(world: Vec2, filter: (Element) -> Boolean): Boolean {
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
