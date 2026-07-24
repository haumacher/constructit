package constructit.editor

import constructit.core.Evaluator
import constructit.dsl.PointRef
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
    private var panning = false
    private var lastScreen = Vec2(0.0, 0.0)
    private val pickedPoints = ArrayList<PointRef>()
    private val pickedElements = ArrayList<Element>()

    val pendingCount: Int get() = pickedPoints.size + pickedElements.size

    fun setTool(id: String) {
        toolId = id
        resetPicks()
        statusHint = ""
        onChange()
    }

    private fun resetPicks() {
        pickedPoints.clear(); pickedElements.clear(); dragPoint = null; panning = false
    }

    /** Set a transient status-bar note (e.g. panel feedback). */
    fun note(message: String) { statusHint = message }

    /** Help line for the active tool — shown in the status bar whenever there's no transient hint. */
    fun currentHelp(): String =
        if (toolId == Tools.SELECT) Tools.SELECT_HELP else Tools.byId(toolId)?.help ?: ""

    fun render(target: DrawTarget) = SceneRenderer.render(doc, Evaluator(), camera, target, canvasW, canvasH, showGrid)

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
        runToolClick(screen)
    }

    fun pointerMove(screen: Vec2) {
        when {
            dragPoint != null -> {
                val el = dragPoint!!
                val world = camera.screenToWorld(screen)
                val c = el.constraint
                if (c != null) c.update(world, ev()) else doc.moveFreePoint(el, world)
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
        dragPoint = null
        panning = false
    }

    private fun runToolClick(screen: Vec2) {
        val tool = Tools.byId(toolId) ?: return
        val world = camera.screenToWorld(screen)
        val slot = tool.slots[pendingCount]
        val picked = when (slot) {
            SlotKind.PLACE_POINT -> { pickedPoints.add(doc.freePoint(world.x.mm, world.y.mm)); true }
            SlotKind.POINT -> { pickedPoints.add(pointOrCreate(world)); true }
            SlotKind.CURVE -> pickElement(world) { it.isCurve }
            SlotKind.LINE -> pickElement(world) { it.kind == ElementKind.LINE }
            SlotKind.CIRCLE -> pickElement(world) { it.kind == ElementKind.CIRCLE }
            SlotKind.SEGMENT -> pickElement(world) { it.kind == ElementKind.SEGMENT }
            SlotKind.GEOMETRY -> pickElement(world) { true }
            SlotKind.ON_CIRCLE_POINT -> pickElement(world) { it.constraint is OnCircleConstraint }
        }
        // existing-only slots do NOT create anything on a miss — just hint and wait
        if (!picked) { statusHint = tool.help; onChange(); return }

        if (pendingCount == tool.slots.size) {
            if (tool.scalar && activeScalar == null) {
                statusHint = "${tool.label}: select a parameter or measurement in the panel first"
                resetPicks(); onChange(); return
            }
            tool.build(doc, Picks(pickedPoints.toList(), pickedElements.toList(), world), activeScalar?.ref)
            resetPicks()
            statusHint = ""
        } else {
            statusHint = "${tool.help} (${tool.slots.size - pendingCount} more)"
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
