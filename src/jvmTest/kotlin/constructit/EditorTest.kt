package constructit

import constructit.core.Evaluator
import constructit.dsl.CircleRef
import constructit.dsl.LineRef
import constructit.dsl.circle
import constructit.dsl.line
import constructit.dsl.point
import constructit.dsl.scalar
import constructit.editor.Editor
import constructit.editor.Tools
import constructit.editor.ElementKind
import constructit.editor.SvgDrawTarget
import constructit.geom.Vec2
import constructit.units.mm
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
        ed.setTool(Tools.LINE)
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
        ed.setTool(Tools.POINT); ed.click(Vec2(0.0, 0.0)); ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.LINE); ed.click(Vec2(0.0, 0.0)); ed.click(Vec2(30.0, 0.0))
        assertEquals(2, ed.doc.freePoints.size, "line endpoints should reuse existing points")

        // drag the first point up to (0,20); the line must follow
        ed.setTool(Tools.SELECT)
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
        ed.setTool(Tools.POINT); ed.click(Vec2(-15.0, 0.0)); ed.click(Vec2(15.0, 0.0)); ed.click(Vec2(0.0, 20.0))
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(-15.0, 0.0)); ed.click(Vec2(0.0, 20.0))   // circle 1
        ed.click(Vec2(15.0, 0.0)); ed.click(Vec2(0.0, 20.0))    // circle 2
        ed.setTool(Tools.INTERSECT)
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
    fun lineLineIntersectionYieldsExactlyOnePoint() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-30.0, -20.0)); ed.click(Vec2(30.0, 20.0))   // line 1 endpoints
        ed.click(Vec2(-30.0, 20.0)); ed.click(Vec2(30.0, -20.0))   // line 2 endpoints
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-30.0, -20.0)); ed.click(Vec2(30.0, 20.0))
        ed.click(Vec2(-30.0, 20.0)); ed.click(Vec2(30.0, -20.0))
        ed.setTool(Tools.INTERSECT)
        ed.click(Vec2(15.0, 10.0))    // on line 1 only
        ed.click(Vec2(15.0, -10.0))   // on line 2 only

        val derived = ed.doc.elements.filter { it.kind == ElementKind.DERIVED_POINT }
        assertEquals(1, derived.size, "two lines meet in a single point")
        val p = Evaluator().point(derived[0].ref as constructit.dsl.PointRef)
        assertClose(p.x, 0.0); assertClose(p.y, 0.0)   // the two lines cross at the origin
    }

    @Test
    fun lineToolSnapsToExistingPoint() {
        val ed = Editor()
        ed.setTool(Tools.POINT); ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.LINE)
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

        ed.setTool(Tools.SELECT)
        val panBefore = ed.camera.panX
        ed.pointerDown(Vec2(400.0, 300.0)) // empty -> pan
        ed.pointerMove(Vec2(415.0, 300.0))
        ed.pointerUp(Vec2(415.0, 300.0))
        assertClose(ed.camera.panX, panBefore + 15.0)
    }

    @Test
    fun scalarToolUsesActiveParameter() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("r", 15.0.mm)
        ed.setTool(Tools.CIRCLE_R)
        ed.click(Vec2(0.0, 0.0))   // single slot (centre) -> builds circle with the active radius
        val circleEl = ed.doc.elements.first { it.kind == ElementKind.CIRCLE }
        assertClose(Evaluator().circle(circleEl.ref as CircleRef).radius, 15.0)
    }

    @Test
    fun measurementToolAddsReadonlyScalar() {
        val ed = Editor()
        ed.setTool(Tools.POINT); ed.click(Vec2(0.0, 0.0)); ed.click(Vec2(3.0, 4.0))
        ed.setTool(Tools.DISTANCE); ed.click(Vec2(0.0, 0.0)); ed.click(Vec2(3.0, 4.0))
        val m = ed.doc.scalars.first { !it.editable }
        assertClose(Evaluator().scalar(m.ref).mm, 5.0)
    }

    @Test
    fun pointOnLineIsCreatedByClickAndDragsAlongTheLine() {
        val ed = Editor()
        ed.setTool(Tools.POINT); ed.click(Vec2(-50.0, 0.0)); ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.LINE); ed.click(Vec2(-50.0, 0.0)); ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.POINT_ON_LINE); ed.click(Vec2(20.0, 0.0))   // just click the line, no parameter

        val ptEl = ed.doc.elements.first { it.kind == ElementKind.ON_CURVE }
        val p1 = Evaluator().point(ptEl.ref as constructit.dsl.PointRef)
        assertClose(p1.x, 20.0); assertClose(p1.y, 0.0)

        // drag it toward (35,25): it must stay on the line, projecting to (35,0)
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(20.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(35.0, 25.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(35.0, 25.0)))
        val p2 = Evaluator().point(ptEl.ref as constructit.dsl.PointRef)
        assertClose(p2.x, 35.0); assertClose(p2.y, 0.0)
    }

    @Test
    fun keyPointsExposesMirroredSegmentEndpoints() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT); ed.click(Vec2(10.0, 10.0)); ed.click(Vec2(30.0, 10.0))
        ed.setTool(Tools.POINT); ed.click(Vec2(-40.0, 0.0)); ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.LINE); ed.click(Vec2(-40.0, 0.0)); ed.click(Vec2(40.0, 0.0))   // x-axis
        ed.setTool(Tools.MIRROR); ed.click(Vec2(20.0, 10.0)); ed.click(Vec2(0.0, 0.0))  // segment across x-axis
        ed.setTool(Tools.KEY_POINTS); ed.click(Vec2(20.0, -10.0))                       // on the mirrored segment

        val derived = ed.doc.elements.filter { it.kind == ElementKind.DERIVED_POINT }
        assertEquals(2, derived.size, "mirrored segment should expose its two endpoints")
        val ev = Evaluator()
        val pts = derived.map { ev.point(it.ref as constructit.dsl.PointRef) }.sortedBy { it.x }
        assertClose(pts[0].x, 10.0); assertClose(pts[0].y, -10.0)
        assertClose(pts[1].x, 30.0); assertClose(pts[1].y, -10.0)
    }

    @Test
    fun sceneSvgGolden() {
        val ed = Editor(canvasW = 400.0, canvasH = 300.0)
        ed.camera = constructit.editor.Camera.centered(400.0, 300.0, scale = 4.0)
        // a small live construction: two points, a line, a circle
        ed.setTool(Tools.POINT); ed.click(Vec2(-30.0, -10.0)); ed.click(Vec2(30.0, 10.0))
        ed.setTool(Tools.LINE); ed.click(Vec2(-30.0, -10.0)); ed.click(Vec2(30.0, 10.0))
        ed.setTool(Tools.CIRCLE); ed.click(Vec2(0.0, 0.0)); ed.click(Vec2(25.0, 0.0))

        val target = SvgDrawTarget()
        ed.render(target)
        Golden.check("editor_scene", target.svg())
    }
}
