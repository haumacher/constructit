package constructit

import constructit.core.Evaluator
import constructit.dsl.CircleRef
import constructit.dsl.LineRef
import constructit.dsl.circle
import constructit.dsl.line
import constructit.dsl.point
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.SvgDrawTarget
import constructit.editor.Tool
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Headless tests of the pure interaction core: simulate pointer gestures in screen space,
 * assert document/scene changes, and snapshot the rendered scene as SVG. No GUI required.
 */
class EditorTest {

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s); pointerUp(s)
    }

    @Suppress("UNCHECKED_CAST")
    private fun Editor.firstLine() = Evaluator().line(doc.elements.first { it.kind == ElementKind.LINE }.ref as LineRef)

    @Test
    fun lineToolCreatesLineAndEndpoints() {
        val ed = Editor()
        ed.setTool(Tool.LINE)
        ed.click(Vec2(-20.0, 0.0))
        ed.click(Vec2(20.0, 0.0))

        assertEquals(2, ed.doc.freePoints.size)
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.LINE })
        val l = ed.firstLine()
        assertClose(l.dir.y, 0.0, tol = 1e-9)          // horizontal
        assertClose(l.origin.x, -20.0); assertClose(l.origin.y, 0.0)
    }

    @Test
    fun draggingAFreePointRecomputesDependents() {
        val ed = Editor()
        // two free points, then a line reusing them
        ed.setTool(Tool.POINT); ed.click(Vec2(0.0, 0.0)); ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tool.LINE); ed.click(Vec2(0.0, 0.0)); ed.click(Vec2(30.0, 0.0))
        assertEquals(2, ed.doc.freePoints.size, "line endpoints should reuse existing points")

        // drag the first point up to (0,20); the line must follow
        ed.setTool(Tool.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(0.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(0.0, 20.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(0.0, 20.0)))

        val l = ed.firstLine()
        assertClose(l.origin.x, 0.0); assertClose(l.origin.y, 20.0)
        assertTrue(kotlin.math.abs(l.dir.y) > 1e-6, "line should no longer be horizontal after the drag")
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun intersectToolProducesTwoDerivedPoints() {
        val ed = Editor()
        // centres and a shared through-point at (0,20): both circles get radius 25
        ed.setTool(Tool.POINT); ed.click(Vec2(-15.0, 0.0)); ed.click(Vec2(15.0, 0.0)); ed.click(Vec2(0.0, 20.0))
        ed.setTool(Tool.CIRCLE)
        ed.click(Vec2(-15.0, 0.0)); ed.click(Vec2(0.0, 20.0))   // circle 1
        ed.click(Vec2(15.0, 0.0)); ed.click(Vec2(0.0, 20.0))    // circle 2
        ed.setTool(Tool.INTERSECT)
        ed.click(Vec2(10.0, 0.0))    // on circle 1 (centre -15, r 25)
        ed.click(Vec2(-10.0, 0.0))   // on circle 2 (centre  15, r 25)

        val derived = ed.doc.elements.filter { it.kind == ElementKind.DERIVED_POINT }
        assertEquals(2, derived.size)
        val ev = Evaluator()
        val ys = derived.map { ev.point(it.ref as constructit.dsl.PointRef).y }.sorted()
        // circles r=25 centred at (+/-15,0) meet at (0, +/-20)
        assertClose(ys[0], -20.0); assertClose(ys[1], 20.0)
    }

    @Test
    fun lineToolSnapsToExistingPoint() {
        val ed = Editor()
        ed.setTool(Tool.POINT); ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tool.LINE)
        ed.click(Vec2(0.4, 0.0))     // within tolerance of the existing point -> reuse
        ed.click(Vec2(30.0, 0.0))    // new point
        assertEquals(2, ed.doc.freePoints.size, "should reuse the nearby point, not duplicate it")
    }

    @Test
    fun wheelZoomsAndEmptyDragPans() {
        val ed = Editor()
        val s0 = ed.camera.scale
        ed.wheel(Vec2(400.0, 300.0), -1.0)
        assertTrue(ed.camera.scale > s0, "wheel up should zoom in")

        ed.setTool(Tool.SELECT)
        val panBefore = ed.camera.panX
        ed.pointerDown(Vec2(400.0, 300.0)) // empty -> pan
        ed.pointerMove(Vec2(415.0, 300.0))
        ed.pointerUp(Vec2(415.0, 300.0))
        assertClose(ed.camera.panX, panBefore + 15.0)
    }

    @Test
    fun sceneSvgGolden() {
        val ed = Editor(canvasW = 400.0, canvasH = 300.0)
        ed.camera = constructit.editor.Camera.centered(400.0, 300.0, scale = 4.0)
        // a small live construction: two points, a line, a circle
        ed.setTool(Tool.POINT); ed.click(Vec2(-30.0, -10.0)); ed.click(Vec2(30.0, 10.0))
        ed.setTool(Tool.LINE); ed.click(Vec2(-30.0, -10.0)); ed.click(Vec2(30.0, 10.0))
        ed.setTool(Tool.CIRCLE); ed.click(Vec2(0.0, 0.0)); ed.click(Vec2(25.0, 0.0))

        val target = SvgDrawTarget()
        ed.render(target)
        Golden.check("editor_scene", target.svg())
    }
}
