package constructit.editor

import constructit.core.Evaluator
import constructit.dsl.PointRef
import constructit.geom.Vec2
import constructit.units.mm

enum class Tool { SELECT, POINT, LINE, CIRCLE, INTERSECT }

/**
 * The pure interaction controller: a tool state machine over abstract pointer events (in
 * screen coordinates), driving hit-testing, free-point dragging (live parametric recompute),
 * and geometry creation. No platform APIs — fully headless-testable by simulating gestures.
 * The shell only forwards native events here and calls [render] on [onChange].
 */
class Editor(
    val doc: Document = Document(),
    var canvasW: Double = 800.0,
    var canvasH: Double = 600.0,
) {
    var camera: Camera = Camera.centered(canvasW, canvasH)
    var tool: Tool = Tool.SELECT
        private set
    var onChange: () -> Unit = {}

    private val tolPx = 10.0
    private fun tolWorld() = tolPx / camera.scale
    private fun ev() = Evaluator()

    // transient interaction state
    private var dragPoint: Element? = null
    private var panning = false
    private var lastScreen = Vec2(0.0, 0.0)
    private val pendingPoints = ArrayList<PointRef>()
    private val pendingCurves = ArrayList<Element>()

    /** Points/curves collected so far by a multi-click tool (for shells to show a hint). */
    val pendingCount: Int get() = pendingPoints.size + pendingCurves.size

    fun setTool(t: Tool) {
        tool = t
        resetPending()
        onChange()
    }

    private fun resetPending() {
        pendingPoints.clear(); pendingCurves.clear(); dragPoint = null; panning = false
    }

    fun render(target: DrawTarget) = SceneRenderer.render(doc, Evaluator(), camera, target, canvasW, canvasH)

    fun wheel(screen: Vec2, deltaY: Double) {
        camera = camera.zoomAt(screen, if (deltaY < 0) 1.1 else 1.0 / 1.1)
        onChange()
    }

    fun pointerDown(screen: Vec2) {
        val world = camera.screenToWorld(screen)
        when (tool) {
            Tool.SELECT -> {
                val hit = HitTest.nearestFreePoint(doc, ev(), world, tolWorld())
                if (hit != null) dragPoint = hit else { panning = true; lastScreen = screen }
            }
            Tool.POINT -> { doc.freePoint(world.x.mm, world.y.mm); onChange() }
            Tool.LINE -> {
                pendingPoints.add(pointOrCreate(world))
                if (pendingPoints.size == 2) { doc.line(pendingPoints[0], pendingPoints[1]); resetPending() }
                onChange()
            }
            Tool.CIRCLE -> {
                pendingPoints.add(pointOrCreate(world))
                if (pendingPoints.size == 2) { doc.circle(pendingPoints[0], pendingPoints[1]); resetPending() }
                onChange()
            }
            Tool.INTERSECT -> {
                val curve = HitTest.nearestCurve(doc, ev(), world, tolWorld()) ?: return
                pendingCurves.add(curve)
                if (pendingCurves.size == 2) { doc.intersect(pendingCurves[0], pendingCurves[1]); resetPending() }
                onChange()
            }
        }
    }

    fun pointerMove(screen: Vec2) {
        when {
            dragPoint != null -> { doc.moveFreePoint(dragPoint!!, camera.screenToWorld(screen)); onChange() }
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

    private fun pointOrCreate(world: Vec2): PointRef {
        val hit = HitTest.nearestAnyPoint(doc, ev(), world, tolWorld())
        @Suppress("UNCHECKED_CAST")
        if (hit != null) return hit.ref as PointRef
        return doc.freePoint(world.x.mm, world.y.mm)
    }
}
