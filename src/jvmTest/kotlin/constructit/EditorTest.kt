package constructit

import constructit.core.Evaluator
import constructit.dsl.ArcRef
import constructit.dsl.CircleRef
import constructit.dsl.LineRef
import constructit.dsl.SegmentRef
import constructit.dsl.arc
import constructit.dsl.circle
import constructit.dsl.line
import constructit.dsl.point
import constructit.dsl.scalar
import constructit.dsl.segment
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Styles
import constructit.editor.SvgDrawTarget
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.deg
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Headless tests of the pure interaction core: simulate pointer gestures in screen space,
 * assert document/scene changes, and snapshot the rendered scene as SVG. No GUI required.
 */
class EditorTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
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
        assertClose(l.dir.y, 0.0, tol = 1e-9) // horizontal
        assertClose(l.origin.x, -20.0)
        assertClose(l.origin.y, 0.0)
    }

    @Test
    fun draggingAFreePointRecomputesDependents() {
        val ed = Editor()
        // two free points, then a line reusing them
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        assertEquals(2, ed.doc.freePoints.size, "line endpoints should reuse existing points")

        // drag the first point up to (0,20); the line must follow
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(0.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(0.0, 20.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(0.0, 20.0)))

        val l = ed.firstLine()
        assertClose(l.origin.x, 0.0)
        assertClose(l.origin.y, 20.0)
        assertTrue(kotlin.math.abs(l.dir.y) > 1e-6, "line should no longer be horizontal after the drag")
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun intersectToolProducesTwoDerivedPoints() {
        val ed = Editor()
        // centres and a shared through-point at (0,20): both circles get radius 25
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-15.0, 0.0))
        ed.click(Vec2(15.0, 0.0))
        ed.click(Vec2(0.0, 20.0))
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(-15.0, 0.0))
        ed.click(Vec2(0.0, 20.0)) // circle 1
        ed.click(Vec2(15.0, 0.0))
        ed.click(Vec2(0.0, 20.0)) // circle 2
        ed.setTool(Tools.INTERSECT)
        ed.click(Vec2(10.0, 0.0)) // on circle 1 (centre -15, r 25)
        ed.click(Vec2(-10.0, 0.0)) // on circle 2 (centre  15, r 25)

        val derived = ed.doc.elements.filter { it.kind == ElementKind.DERIVED_POINT }
        assertEquals(2, derived.size)
        val ev = Evaluator()
        val ys = derived.map { ev.point(it.ref as constructit.dsl.PointRef).y }.sorted()
        // circles r=25 centred at (+/-15,0) meet at (0, +/-20)
        assertClose(ys[0], -20.0)
        assertClose(ys[1], 20.0)
    }

    @Test
    fun lineLineIntersectionYieldsExactlyOnePoint() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-30.0, -20.0))
        ed.click(Vec2(30.0, 20.0)) // line 1 endpoints
        ed.click(Vec2(-30.0, 20.0))
        ed.click(Vec2(30.0, -20.0)) // line 2 endpoints
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-30.0, -20.0))
        ed.click(Vec2(30.0, 20.0))
        ed.click(Vec2(-30.0, 20.0))
        ed.click(Vec2(30.0, -20.0))
        ed.setTool(Tools.INTERSECT)
        ed.click(Vec2(15.0, 10.0)) // on line 1 only
        ed.click(Vec2(15.0, -10.0)) // on line 2 only

        val derived = ed.doc.elements.filter { it.kind == ElementKind.DERIVED_POINT }
        assertEquals(1, derived.size, "two lines meet in a single point")
        val p = Evaluator().point(derived[0].ref as constructit.dsl.PointRef)
        assertClose(p.x, 0.0)
        assertClose(p.y, 0.0) // the two lines cross at the origin
    }

    @Test
    fun lineToolSnapsToExistingPoint() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.4, 0.0)) // within tolerance of the existing point -> reuse
        ed.click(Vec2(30.0, 0.0)) // new point
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
        ed.click(Vec2(0.0, 0.0)) // single slot (centre) -> builds circle with the active radius
        val circleEl = ed.doc.elements.first { it.kind == ElementKind.CIRCLE }
        assertClose(Evaluator().circle(circleEl.ref as CircleRef).radius, 15.0)
    }

    @Test
    fun measurementToolAddsReadonlyScalar() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(3.0, 4.0))
        ed.setTool(Tools.DISTANCE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(3.0, 4.0))
        val m = ed.doc.scalars.first { !it.editable }
        assertClose(Evaluator().scalar(m.ref).mm, 5.0)
    }

    @Test
    fun pointOnLineIsCreatedByClickAndDragsAlongTheLine() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-50.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-50.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.POINT_ON_LINE)
        ed.click(Vec2(20.0, 0.0)) // just click the line, no parameter

        val ptEl = ed.doc.elements.first { it.kind == ElementKind.ON_CURVE }
        val p1 = Evaluator().point(ptEl.ref as constructit.dsl.PointRef)
        assertClose(p1.x, 20.0)
        assertClose(p1.y, 0.0)

        // drag it toward (35,25): it must stay on the line, projecting to (35,0)
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(20.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(35.0, 25.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(35.0, 25.0)))
        val p2 = Evaluator().point(ptEl.ref as constructit.dsl.PointRef)
        assertClose(p2.x, 35.0)
        assertClose(p2.y, 0.0)
    }

    @Test
    fun tangentAtPointOnCircleInfersTheCircleFromOneClick() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(25.0, 0.0)) // centre + through
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(25.0, 0.0)) // circle r=25
        ed.setTool(Tools.POINT_ON_CIRCLE)
        ed.click(Vec2(25.0, 0.0)) // point ON the circle
        ed.setTool(Tools.TANGENT_AT)
        ed.click(Vec2(25.0, 0.0)) // single click the point

        val lineEl = ed.doc.elements.last { it.kind == ElementKind.LINE }
        val l = Evaluator().line(lineEl.ref as LineRef)
        assertClose(l.dir.x, 0.0, tol = 1e-9) // tangent at (25,0) is vertical
        val dist = kotlin.math.abs((Vec2(0.0, 0.0) - l.origin).cross(l.dir))
        assertClose(dist, 25.0) // distance centre->line == radius
    }

    @Test
    fun tangentAtCreatesNothingOnEmptyClick() {
        val ed = Editor()
        ed.setTool(Tools.TANGENT_AT)
        val before = ed.doc.elements.size
        ed.click(Vec2(120.0, 120.0)) // empty space
        assertEquals(before, ed.doc.elements.size, "must not create a stray point")
    }

    @Test
    fun pointAtDistanceChoosesDirectionByClickSide() {
        fun buildAndReturnX(clickLineAt: Double): Double {
            val ed = Editor()
            ed.setTool(Tools.POINT)
            ed.click(Vec2(-50.0, 0.0))
            ed.click(Vec2(50.0, 0.0))
            ed.click(Vec2(0.0, 0.0))
            ed.setTool(Tools.LINE)
            ed.click(Vec2(-50.0, 0.0))
            ed.click(Vec2(50.0, 0.0)) // x-axis
            ed.activeScalar = ed.doc.newParameter("d", 15.0.mm)
            ed.setTool(Tools.POINT_AT_DIST)
            ed.click(Vec2(0.0, 0.0)) // reference point (origin)
            ed.click(Vec2(clickLineAt, 0.0)) // line + direction side
            val ptEl = ed.doc.elements.last { it.kind == ElementKind.DERIVED_POINT }
            return Evaluator().point(ptEl.ref as constructit.dsl.PointRef).x
        }
        assertClose(buildAndReturnX(30.0), 15.0) // clicked +x side -> 15 mm right of origin
        assertClose(buildAndReturnX(-30.0), -15.0) // clicked -x side -> 15 mm left
    }

    @Test
    fun lineSlotsAcceptSegmentsAsCarrierLine() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 0.0)) // horizontal segment
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 20.0))
        ed.setTool(Tools.PERPENDICULAR)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(0.0, 20.0)) // click the segment as the "line"

        val l = Evaluator().line(ed.doc.elements.last { it.kind == ElementKind.LINE }.ref as LineRef)
        assertClose(l.dir.x, 0.0, tol = 1e-9) // perpendicular to a horizontal carrier line is vertical
        assertClose(l.origin.x, 0.0)
        assertClose(l.origin.y, 20.0)
    }

    @Test
    fun intersectingTwoSegmentsGivesOnePoint() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-30.0, -20.0))
        ed.click(Vec2(30.0, 20.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-30.0, 20.0))
        ed.click(Vec2(30.0, -20.0))
        ed.setTool(Tools.INTERSECT)
        ed.click(Vec2(15.0, 10.0))
        ed.click(Vec2(15.0, -10.0))

        val derived = ed.doc.elements.filter { it.kind == ElementKind.DERIVED_POINT }
        assertEquals(1, derived.size, "two segments' carrier lines meet in one point")
        val p = Evaluator().point(derived[0].ref as constructit.dsl.PointRef)
        assertClose(p.x, 0.0)
        assertClose(p.y, 0.0)
    }

    @Test
    fun arcByCentreStartEnd() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(20.0, 0.0))
        ed.click(Vec2(0.0, 20.0))
        ed.setTool(Tools.ARC_CS)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(20.0, 0.0))
        ed.click(Vec2(0.0, 20.0))
        val a = Evaluator().arc(ed.doc.elements.last { it.kind == ElementKind.ARC }.ref as ArcRef)
        assertClose(a.radius, 20.0)
        assertClose(a.center.x, 0.0)
        assertClose(a.center.y, 0.0)
        assertClose(a.startAngle, 0.0) // start (20,0)
        assertClose(a.endAngle, kotlin.math.PI / 2) // end   (0,20)
        assertTrue(a.ccw)
    }

    @Test
    fun parallelAtDistanceOffsetsToChosenSide() {
        fun originYFor(sideY: Double): Double {
            val ed = Editor()
            ed.setTool(Tools.POINT)
            ed.click(Vec2(-50.0, 0.0))
            ed.click(Vec2(50.0, 0.0))
            ed.setTool(Tools.LINE)
            ed.click(Vec2(-50.0, 0.0))
            ed.click(Vec2(50.0, 0.0)) // x-axis
            ed.activeScalar = ed.doc.newParameter("d", 10.0.mm)
            ed.setTool(Tools.PARALLEL_AT)
            ed.click(Vec2(0.0, 0.0)) // base line
            ed.click(Vec2(0.0, sideY)) // side
            val l = Evaluator().line(ed.doc.elements.last { it.kind == ElementKind.LINE }.ref as LineRef)
            assertClose(l.dir.y, 0.0, tol = 1e-9) // parallel stays horizontal
            return l.origin.y
        }
        assertClose(originYFor(30.0), 10.0) // side above -> +10 mm
        assertClose(originYFor(-30.0), -10.0) // side below -> -10 mm
    }

    @Test
    fun keyPointsOfAFilletArcGivesCentreAndTangentPoints() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(0.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("r", 10.0.mm)
        ed.setTool(Tools.FILLET)
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(0.0, 30.0)) // fillet, centre (10,10)
        ed.setTool(Tools.KEY_POINTS)
        ed.click(Vec2(2.93, 2.93)) // click the fillet arc

        val pts =
            ed.doc.elements.filter { it.kind == ElementKind.DERIVED_POINT }
                .map { Evaluator().point(it.ref as constructit.dsl.PointRef) }
        assertEquals(3, pts.size) // centre + two tangent endpoints
        assertClose(pts[0].x, 10.0)
        assertClose(pts[0].y, 10.0) // centre
        assertClose(pts[1].x, 10.0)
        assertClose(pts[1].y, 0.0) // tangent point on +x leg
        assertClose(pts[2].x, 0.0)
        assertClose(pts[2].y, 10.0) // tangent point on +y leg
    }

    @Test
    fun keyPointsExposesMirroredSegmentEndpoints() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(10.0, 10.0))
        ed.click(Vec2(30.0, 10.0))
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 0.0)) // x-axis
        ed.setTool(Tools.MIRROR)
        ed.click(Vec2(20.0, 10.0))
        ed.click(Vec2(0.0, 0.0)) // segment across x-axis
        ed.setTool(Tools.KEY_POINTS)
        ed.click(Vec2(20.0, -10.0)) // on the mirrored segment

        val derived = ed.doc.elements.filter { it.kind == ElementKind.DERIVED_POINT }
        assertEquals(2, derived.size, "mirrored segment should expose its two endpoints")
        val ev = Evaluator()
        val pts = derived.map { ev.point(it.ref as constructit.dsl.PointRef) }.sortedBy { it.x }
        assertClose(pts[0].x, 10.0)
        assertClose(pts[0].y, -10.0)
        assertClose(pts[1].x, 30.0)
        assertClose(pts[1].y, -10.0)
    }

    @Test
    fun wiringAParameterTracksTargetAndRejectsBadWires() {
        val ed = Editor()
        val a = ed.doc.newParameter("a", 10.0.mm)
        val b = ed.doc.newParameter("b", 25.0.mm)
        assertClose(Evaluator().scalar(a.ref).mm, 10.0)

        // wire a -> b: a now tracks b (DOF reduced)
        assertTrue(ed.doc.wireParameter(a, b))
        assertClose(Evaluator().scalar(a.ref).mm, 25.0)
        ed.doc.setParameter(b, 40.0.mm)
        assertClose(Evaluator().scalar(a.ref).mm, 40.0)

        // type mismatch is rejected
        val ang = ed.doc.newParameter("ang", 30.0.deg)
        assertFalse(ed.doc.wireParameter(a, ang))

        // unwire keeps the last driven value, then a is independent again
        ed.doc.unwireParameter(a)
        assertClose(Evaluator().scalar(a.ref).mm, 40.0)
        ed.doc.setParameter(a, 5.0.mm)
        assertClose(Evaluator().scalar(a.ref).mm, 5.0)
        assertClose(Evaluator().scalar(b.ref).mm, 40.0)
    }

    @Test
    fun wiringRejectsCycles() {
        val ed = Editor()
        val r = ed.doc.newParameter("r", 20.0.mm)
        val center = ed.doc.freePoint(0.mm, 0.mm)
        ed.doc.circleCR(center, r.ref) // circle driven by r
        val circleEl = ed.doc.elements.last { it.kind == ElementKind.CIRCLE }
        val m = ed.doc.measureRadius(circleEl) // measurement depends on r
        assertFalse(ed.doc.wireParameter(r, m), "wiring r to a measurement of its own circle is a cycle")
    }

    @Test
    fun centreOfThreePointCircle() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(0.0, 40.0))
        ed.setTool(Tools.CIRCLE_3)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(0.0, 40.0))
        ed.setTool(Tools.CENTRE)
        ed.click(Vec2(48.284, 20.0)) // on the circle boundary (centre 20,20 r≈28.28)
        val p = Evaluator().point(ed.doc.elements.last { it.kind == ElementKind.DERIVED_POINT }.ref as constructit.dsl.PointRef)
        assertClose(p.x, 20.0, tol = 1e-3)
        assertClose(p.y, 20.0, tol = 1e-3) // circumcentre
    }

    @Test
    fun scalarNamesAreUnique() {
        val ed = Editor()
        val a = ed.doc.newParameter("d", 10.0.mm)
        val b = ed.doc.newParameter("d", 20.0.mm)
        assertEquals("d", a.name)
        assertEquals("d2", b.name)
    }

    @Test
    fun filletBetweenTwoLegsRoundsTheCorner() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(50.0, 0.0)) // +x leg
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(0.0, 50.0)) // +y leg
        ed.activeScalar = ed.doc.newParameter("r", 10.0.mm)
        ed.setTool(Tools.FILLET)
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(0.0, 30.0)) // click each leg on the +x/+y side
        val a = Evaluator().arc(ed.doc.elements.last { it.kind == ElementKind.ARC }.ref as ArcRef)
        assertClose(a.radius, 10.0)
        assertClose(a.center.x, 10.0)
        assertClose(a.center.y, 10.0) // right-angle corner -> centre (r,r)
    }

    @Test
    fun filletCreatesNoStrayGeometryWithoutLegs() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("r", 10.0.mm)
        ed.setTool(Tools.FILLET)
        val before = ed.doc.elements.size
        ed.click(Vec2(120.0, 120.0)) // empty space
        assertEquals(before, ed.doc.elements.size, "must not create stray points/geometry")
    }

    @Test
    fun concentricCircleGrowsOutsideShrinksInside() {
        fun radiusFor(sideX: Double): Double {
            val ed = Editor()
            ed.setTool(Tools.POINT)
            ed.click(Vec2(0.0, 0.0))
            ed.click(Vec2(30.0, 0.0))
            ed.setTool(Tools.CIRCLE)
            ed.click(Vec2(0.0, 0.0))
            ed.click(Vec2(30.0, 0.0)) // r=30
            ed.activeScalar = ed.doc.newParameter("d", 10.0.mm)
            ed.setTool(Tools.CONCENTRIC)
            ed.click(Vec2(30.0, 0.0))
            ed.click(Vec2(sideX, 0.0))
            return Evaluator().circle(ed.doc.elements.last { it.kind == ElementKind.CIRCLE }.ref as CircleRef).radius
        }
        assertClose(radiusFor(60.0), 40.0) // click outside -> grow
        assertClose(radiusFor(5.0), 20.0) // click inside  -> shrink
    }

    @Test
    fun translateByVectorMovesGeometry() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(10.0, 0.0))
        ed.setTool(Tools.POINT)
        ed.click(Vec2(20.0, 5.0)) // 'to' point; 'from' reuses (0,0)
        ed.setTool(Tools.TRANSLATE_V)
        ed.click(Vec2(5.0, 0.0))
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(20.0, 5.0))
        val s = Evaluator().segment(ed.doc.elements.last { it.kind == ElementKind.SEGMENT }.ref as SegmentRef)
        assertClose(s.a.x, 20.0)
        assertClose(s.a.y, 5.0)
        assertClose(s.b.x, 30.0)
        assertClose(s.b.y, 5.0)
    }

    @Test
    fun outerTangentsOfEqualCirclesAreParallelFlanks() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-30.0, 0.0))
        ed.click(Vec2(-20.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(-30.0, 0.0))
        ed.click(Vec2(-20.0, 0.0)) // c1 r10
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(40.0, 0.0)) // c2 r10
        ed.setTool(Tools.OUTER_TANGENTS)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(20.0, 0.0))
        val lines = ed.doc.elements.filter { it.kind == ElementKind.LINE }
        assertEquals(2, lines.size)
        lines.forEach { assertClose(kotlin.math.abs(Evaluator().line(it.ref as LineRef).origin.y), 10.0) }
    }

    @Test
    fun sceneSvgGolden() {
        val ed = Editor(canvasW = 400.0, canvasH = 300.0)
        ed.camera = constructit.editor.Camera.centered(400.0, 300.0, scale = 4.0)
        // a small live construction: two points, a line, a circle
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-30.0, -10.0))
        ed.click(Vec2(30.0, 10.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-30.0, -10.0))
        ed.click(Vec2(30.0, 10.0))
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(25.0, 0.0))

        val target = SvgDrawTarget()
        ed.render(target)
        Golden.check("editor_scene", target.svg())
    }

    @Test
    fun joinToolWeldsSecondPointOntoFirstAndFollowsMaster() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        // a segment that reuses the second (soon-to-be-welded) point
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(30.0, 20.0))
        val a = ed.doc.freePoints[0]
        val b = ed.doc.freePoints[1]
        val seg = ed.doc.elements.first { it.kind == ElementKind.SEGMENT }

        // Join: keep A (first click), weld B (second click) onto it
        ed.setTool(Tools.JOIN)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))

        assertTrue(ed.doc.isWelded(b), "B should be welded onto A")
        assertFalse(b.visible, "a welded point is hidden so the pair reads as one")
        assertFalse(b.draggable, "a welded point has no DOF and cannot be dragged")
        var s = Evaluator().segment(seg.ref as SegmentRef)
        assertClose(s.a.x, 0.0)
        assertClose(s.a.y, 0.0) // the segment endpoint now coincides with A

        // dragging the master A drags the welded endpoint with it
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(0.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(0.0, 10.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(0.0, 10.0)))
        s = Evaluator().segment(seg.ref as SegmentRef)
        assertClose(s.a.x, 0.0)
        assertClose(s.a.y, 10.0)

        // unweld restores an independent free point at the current position
        ed.doc.unweld(b)
        assertFalse(ed.doc.isWelded(b))
        assertTrue(b.visible)
        assertTrue(b.draggable)
        val pb = Evaluator().point(b.ref as constructit.dsl.PointRef)
        assertClose(pb.x, 0.0)
        assertClose(pb.y, 10.0)
    }

    @Test
    fun draggingAPointOntoAnotherWeldsThem() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        val b = ed.doc.freePoints[1]

        // drag B (30,0) directly onto A (0,0); on release the magnet welds them
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(30.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(0.0, 0.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(0.0, 0.0)))

        assertTrue(ed.doc.isWelded(b), "dropping B on A should weld it")
        assertFalse(b.visible)
        val pb = Evaluator().point(b.ref as constructit.dsl.PointRef)
        assertClose(pb.x, 0.0)
        assertClose(pb.y, 0.0)
    }

    @Test
    fun orthoPathEdgesAreAxisAlignedAndVerticesDraggable() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0)) // V0
        ed.click(Vec2(40.0, 6.0)) // snaps horizontal -> (40,0)
        ed.click(Vec2(37.0, 30.0)) // snaps vertical -> (40,30)
        ed.finishPath()

        val segs = ed.doc.elements.filter { it.kind == ElementKind.SEGMENT }
        assertEquals(2, segs.size)
        val s1 = Evaluator().segment(segs[0].ref as SegmentRef)
        assertClose(s1.b.x, 40.0)
        assertClose(s1.b.y, 0.0) // horizontal
        val s2 = Evaluator().segment(segs[1].ref as SegmentRef)
        assertClose(s2.b.x, 40.0)
        assertClose(s2.b.y, 30.0) // vertical (x stays 40)
        assertEquals(3, ed.doc.elements.count { it.kind == ElementKind.ON_CURVE }, "every vertex is draggable")
    }

    @Test
    fun clickingSelectsAndTheInspectorWritesTheSameNodeAsTheDrag() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0)) // V0
        ed.click(Vec2(40.0, 3.0)) // V1 (40,0)  leg 0 horizontal
        ed.click(Vec2(38.0, 30.0)) // V2 (40,30)
        ed.finishPath()
        val path = ed.doc.orthoPaths.single()
        val verts = ed.doc.elements.filter { it.kind == ElementKind.ON_CURVE }

        fun p(i: Int) = Evaluator().point(verts[i].ref as constructit.dsl.PointRef)

        // click the middle of leg 0 -> the leg is selected and offers its three fields
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(20.0, 0.0))
        assertTrue(ed.selection === path.legs[0], "clicking a leg selects it")
        assertEquals("leg ${path.legs[0].id}", ed.selectionLabel())
        assertEquals(listOf("y", "length (move end)", "length (move start)"), ed.selectionFields().map { it.label })

        // typing into "length (move end)" is the same write as dragging V1 along the leg
        assertTrue(ed.writeSelectionField(1, 65.0))
        assertClose(p(1).x, 65.0)
        assertClose(p(2).x, 65.0)

        // clicking a corner selects that instead; clicking empty space clears the selection
        ed.click(Vec2(0.0, 0.0))
        assertTrue(ed.selection === verts[0], "clicking a corner selects the corner")
        ed.click(Vec2(-200.0, -200.0))
        assertEquals(null, ed.selection)
        assertTrue(ed.selectionFields().isEmpty())
        assertFalse(ed.writeSelectionField(0, 1.0), "nothing selected -> nothing to write")
    }

    @Test
    fun axisLockRestrictsAVertexDragToOneAxis() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0)) // V0
        ed.click(Vec2(40.0, 3.0)) // V1 (40,0)
        ed.click(Vec2(38.0, 30.0)) // V2 (40,30)
        ed.finishPath()
        val verts = ed.doc.elements.filter { it.kind == ElementKind.ON_CURVE }

        fun p(i: Int) = Evaluator().point(verts[i].ref as constructit.dsl.PointRef)

        // drag V1 mostly sideways with the lock on: x follows, y must not budge
        ed.setTool(Tools.SELECT)
        ed.axisLock = true
        ed.pointerDown(ed.camera.worldToScreen(Vec2(40.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(60.0, -8.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(60.0, -8.0)))
        assertClose(p(1).x, 60.0)
        assertClose(p(1).y, 0.0)

        // and the other way round: a mostly-vertical gesture keeps x
        ed.pointerDown(ed.camera.worldToScreen(Vec2(60.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(66.0, 25.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(66.0, 25.0)))
        assertClose(p(1).x, 60.0)
        assertClose(p(1).y, 25.0)
    }

    @Test
    fun axisLockLeavesLegDraggingAlone() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 3.0)) // leg 0 horizontal
        ed.click(Vec2(38.0, 30.0)) // leg 1 vertical at x=40
        ed.finishPath()
        val verts = ed.doc.elements.filter { it.kind == ElementKind.ON_CURVE }

        // a leg has one axis of its own already; the lock must not be able to make the drag inert,
        // even when the gesture is dominated by the direction the leg runs in
        ed.setTool(Tools.SELECT)
        ed.axisLock = true
        ed.pointerDown(ed.camera.worldToScreen(Vec2(40.0, 15.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(48.0, 40.0))) // mostly along the leg
        ed.pointerUp(ed.camera.worldToScreen(Vec2(48.0, 40.0)))
        assertClose(Evaluator().point(verts[1].ref as constructit.dsl.PointRef).x, 48.0)
    }

    @Test
    fun draggingAnOrthoLegMovesOnlyThatLegPerpendicular() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0)) // V0
        ed.click(Vec2(40.0, 3.0)) // V1 (40,0)   leg 0 horizontal, shares y
        ed.click(Vec2(38.0, 30.0)) // V2 (40,30)  leg 1 vertical, shares x
        ed.click(Vec2(80.0, 28.0)) // V3 (80,30)  leg 2 horizontal
        ed.finishPath()
        val verts = ed.doc.elements.filter { it.kind == ElementKind.ON_CURVE } // [V0..V3]

        fun p(i: Int) = Evaluator().point(verts[i].ref as constructit.dsl.PointRef)

        // grab leg 1 (the vertical one at x=40, spanning y 0..30) at its middle and drag sideways
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(40.0, 15.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(55.0, 22.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(55.0, 22.0)))

        assertClose(p(1).x, 55.0) // both ends of the leg moved together...
        assertClose(p(2).x, 55.0)
        assertClose(p(1).y, 0.0) // ...and only perpendicular: the along-leg coordinates are untouched
        assertClose(p(2).y, 30.0)
        assertClose(p(0).x, 0.0) // the neighbouring legs just stretched
        assertClose(p(0).y, 0.0)
        assertClose(p(3).x, 80.0)
        assertClose(p(3).y, 30.0)
    }

    @Test
    fun aVertexWinsOverTheLegsMeetingAtIt() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 3.0)) // V1 (40,0)
        ed.click(Vec2(38.0, 30.0)) // V2 (40,30)
        ed.finishPath()
        val verts = ed.doc.elements.filter { it.kind == ElementKind.ON_CURVE }

        // press exactly on V1, where leg 0 and leg 1 also pass: the vertex must be picked, so the
        // drag moves it in both axes rather than sliding a leg
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(40.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(50.0, -10.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(50.0, -10.0)))
        val p1 = Evaluator().point(verts[1].ref as constructit.dsl.PointRef)
        assertClose(p1.x, 50.0)
        assertClose(p1.y, -10.0)
    }

    @Test
    fun aLegOffersItsPositionAndItsLengthFromEitherEnd() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0)) // V0
        ed.click(Vec2(40.0, 3.0)) // V1 (40,0)  leg 0 horizontal, length 40
        ed.click(Vec2(38.0, 30.0)) // V2 (40,30)
        ed.finishPath()
        val path = ed.doc.orthoPaths.single()
        val verts = ed.doc.elements.filter { it.kind == ElementKind.ON_CURVE }

        fun p(i: Int) = Evaluator().point(verts[i].ref as constructit.dsl.PointRef)

        val fields = path.legs[0].handle!!.fields()
        assertEquals(listOf("y", "length (move end)", "length (move start)"), fields.map { it.label })
        assertClose(fields[0].read(Evaluator())!!.mm, 0.0)
        assertClose(fields[1].read(Evaluator())!!.mm, 40.0)

        // "move end" writes V1's own x: V1 goes to 25 and V2 follows (it shares that node)
        fields[1].write(25.0.mm)
        assertClose(p(1).x, 25.0)
        assertClose(p(2).x, 25.0)
        assertClose(p(0).x, 0.0)

        // "move start" holds V1 and writes V0's x instead — same leg, the other end moves
        fields[2].write(10.0.mm)
        assertClose(p(0).x, 15.0) // 25 - 10, keeping the leg's direction
        assertClose(p(1).x, 25.0)
    }

    @Test
    fun aVertexOffersTheLengthOfTheLegThatCreatedIt() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 3.0)) // V1: horizontal leg of length 40
        ed.finishPath()
        val verts = ed.doc.elements.filter { it.kind == ElementKind.ON_CURVE }

        assertTrue(verts[0].handle!!.fields().none { it.label == "leg length" }, "the start has no incoming leg")
        val len = verts[1].handle!!.fields().first { it.label == "leg length" }
        assertClose(len.read(Evaluator())!!.mm, 40.0)

        len.write(150.0.mm)
        assertClose(Evaluator().point(verts[1].ref as constructit.dsl.PointRef).x, 150.0)
        assertClose(Evaluator().point(verts[0].ref as constructit.dsl.PointRef).x, 0.0)
    }

    @Test
    fun typingAVertexCoordinateIsTheSameWriteAsDraggingIt() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0)) // V0
        ed.click(Vec2(40.0, 3.0)) // V1 (40,0)
        ed.click(Vec2(38.0, 30.0)) // V2 (40,30) — shares x with V1
        ed.finishPath()
        val verts = ed.doc.elements.filter { it.kind == ElementKind.ON_CURVE }

        fun p(i: Int) = Evaluator().point(verts[i].ref as constructit.dsl.PointRef)

        val fields = verts[1].handle!!.fields()
        assertEquals(listOf("x", "y", "leg length"), fields.map { it.label })
        assertClose(fields[0].read(Evaluator())!!.mm, 40.0)
        assertClose(fields[1].read(Evaluator())!!.mm, 0.0)

        // typing x = 50 must do exactly what dragging V1 to x=50 does: V1 moves, and V2 follows
        // because it shares that very node
        fields[0].write(50.0.mm)
        assertClose(p(1).x, 50.0)
        assertClose(p(2).x, 50.0)
        assertClose(p(0).x, 0.0)
    }

    @Test
    fun aCoordinateDrivenByAnAttachIsReportedUnwritable() {
        val ed = Editor()
        ed.setTool(Tools.LINE)
        ed.click(Vec2(30.0, -50.0))
        ed.click(Vec2(30.0, 50.0)) // vertical line x=30
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(3.0, 40.0))
        ed.click(Vec2(20.0, 40.0)) // V2's own coordinate is x (its leg is horizontal)
        ed.finishPath()
        val v2 = ed.doc.elements.filter { it.kind == ElementKind.ON_CURVE }[2]

        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(20.0, 40.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(30.0, 40.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(30.0, 40.0))) // attach: x becomes derived

        val fields = v2.handle!!.fields()
        val x = fields.first { it.label == "x" }
        assertFalse(x.writable, "x is now determined by the line — typing it must be refused, as dragging it is")
        assertTrue(fields.first { it.label == "y" }.writable, "y is still free")
        x.write(999.0.mm) // no-op
        assertClose(Evaluator().point(v2.ref as constructit.dsl.PointRef).x, 30.0)
    }

    @Test
    fun anOrthoPathIsRetainedWithItsLegTopology() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0)) // V0
        ed.click(Vec2(40.0, 3.0)) // V1 (40,0)  leg 0 horizontal
        ed.click(Vec2(38.0, 30.0)) // V2 (40,30) leg 1 vertical
        ed.finishPath()

        assertEquals(1, ed.doc.orthoPaths.size)
        val path = ed.doc.orthoPaths[0]
        assertEquals(3, path.vertices.size)
        assertFalse(path.closed)
        assertEquals(2, path.legCount)
        assertEquals(2, path.legs.size, "each leg keeps its segment element")
        assertEquals(0, path.legAxis(0), "first leg horizontal")
        assertEquals(1, path.legAxis(1), "second leg vertical")
        assertEquals(0, path.legIndexOf(path.legs[0]))
    }

    @Test
    fun aClosedOrthoPathRecordsTheClosingLeg() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 3.0)) // leg 0 horizontal
        ed.click(Vec2(58.0, 40.0)) // leg 1 vertical
        ed.click(Vec2(2.0, 40.0)) // leg 2 horizontal
        ed.click(Vec2(0.0, 0.0)) // close

        val path = ed.doc.orthoPaths.single()
        assertTrue(path.closed)
        assertEquals(4, path.vertices.size)
        assertEquals(4, path.legCount, "closed: one leg per vertex")
        assertEquals(4, path.legs.size)
        assertEquals(1, path.legAxis(3), "the closing leg runs vertically back to the start")
        val (a, b) = path.legEnds(3)
        assertTrue(a === path.vertices[3] && b === path.vertices[0], "closing leg joins last to first")
    }

    @Test
    fun abandoningAPathBeforeItsSecondVertexRetainsNoPath() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(5.0, 5.0))
        ed.finishPath()
        assertTrue(ed.doc.orthoPaths.isEmpty(), "a single click is not a path")
    }

    @Test
    fun aWallKeepsItsCenterlinePath() {
        val ed = Editor()
        ed.setTool(Tools.WALL)
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 2.0))
        ed.finishPath()
        val wall = ed.doc.walls.single()
        assertTrue(wall.path === ed.doc.orthoPaths.single(), "the wall's spine is the retained path")
    }

    @Test
    fun draggingAnOrthoVertexMovesOnlyItAndItsTwoNeighbours() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0)) // V0
        ed.click(Vec2(40.0, 3.0)) // V1 -> (40,0)   [edge V0-V1 horizontal: shares y]
        ed.click(Vec2(38.0, 30.0)) // V2 -> (40,30)  [edge V1-V2 vertical: shares x]
        ed.click(Vec2(80.0, 28.0)) // V3 -> (80,30)  [edge V2-V3 horizontal: shares y]
        ed.finishPath()
        val verts = ed.doc.elements.filter { it.kind == ElementKind.ON_CURVE } // [V0,V1,V2,V3]

        fun p(i: Int) = Evaluator().point(verts[i].ref as constructit.dsl.PointRef)

        // drag V1 (40,0) to (50,-10)
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(40.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(50.0, -10.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(50.0, -10.0)))

        assertClose(p(1).x, 50.0)
        assertClose(p(1).y, -10.0) // dragged vertex follows cursor
        assertClose(p(0).x, 0.0)
        assertClose(p(0).y, -10.0) // neighbour V0 followed in y (shared)
        assertClose(p(2).x, 50.0)
        assertClose(p(2).y, 30.0) // neighbour V2 followed in x (shared)
        assertClose(p(3).x, 80.0)
        assertClose(p(3).y, 30.0) // V3 (not a neighbour) did NOT move
    }

    @Test
    fun draggingTheVertexBeforeAClosingEdgeHasTwoDof() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 3.0))
        ed.click(Vec2(58.0, 40.0))
        ed.click(Vec2(2.0, 40.0))
        ed.click(Vec2(0.0, 0.0)) // close -> V3 shares x with V0
        val verts = ed.doc.elements.filter { it.kind == ElementKind.ON_CURVE } // [V0,V1,V2,V3]

        fun p(i: Int) = Evaluator().point(verts[i].ref as constructit.dsl.PointRef)

        // drag V3 (the vertex before the closing edge) diagonally: it must move in BOTH axes
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(p(3)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(20.0, 25.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(20.0, 25.0)))

        assertClose(p(3).x, 20.0)
        assertClose(p(3).y, 25.0) // 2 DOF, not pinned to the start
        assertClose(p(0).x, 20.0) // start followed on the shared (closing) axis, keeping the edge axis-aligned
        assertClose(p(2).y, 25.0) // the other neighbour followed too
    }

    @Test
    fun openOrthoEndpointAttachesToALineByDragging() {
        val ed = Editor()
        ed.setTool(Tools.LINE)
        ed.click(Vec2(30.0, -50.0))
        ed.click(Vec2(30.0, 50.0)) // vertical line x=30
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 3.0))
        ed.click(Vec2(38.0, 30.0)) // V0,V1(40,0),V2(40,30)
        ed.finishPath()
        val end = ed.doc.elements.filter { it.kind == ElementKind.ON_CURVE }[2] // V2, the open end

        // drag the endpoint onto the line
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(40.0, 30.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(30.0, 30.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(30.0, 30.0)))
        assertClose(Evaluator().point(end.ref as constructit.dsl.PointRef).x, 30.0) // landed on the line

        // it now slides along the line: dragging it stays on x=30
        ed.pointerDown(ed.camera.worldToScreen(Vec2(30.0, 30.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(25.0, 10.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(25.0, 10.0)))
        val q = Evaluator().point(end.ref as constructit.dsl.PointRef)
        assertClose(q.x, 30.0)
        assertClose(q.y, 10.0) // constrained to the line, y follows
    }

    @Test
    fun neighbourKeepsTwoDofAfterEndpointAttachesToACrossingLine() {
        val ed = Editor()
        ed.setTool(Tools.LINE)
        ed.click(Vec2(30.0, -50.0))
        ed.click(Vec2(30.0, 50.0)) // vertical line x=30
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(3.0, 40.0))
        ed.click(Vec2(20.0, 40.0)) // V0, V1(0,40), V2(20,40)
        ed.finishPath()
        val v = ed.doc.elements.filter { it.kind == ElementKind.ON_CURVE } // [V0,V1,V2]

        fun p(i: Int) = Evaluator().point(v[i].ref as constructit.dsl.PointRef)

        // attach the endpoint V2 (its edge V1-V2 is horizontal, crossing the vertical line) onto the line
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(20.0, 40.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(30.0, 40.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(30.0, 40.0)))
        assertClose(p(2).x, 30.0) // V2 on the line

        // the neighbour V1 must still have 2 DOF: drag it diagonally
        ed.pointerDown(ed.camera.worldToScreen(Vec2(0.0, 40.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(10.0, 60.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(10.0, 60.0)))
        assertClose(p(1).x, 10.0)
        assertClose(p(1).y, 60.0) // neighbour moved in BOTH axes (2 DOF)
        assertClose(p(2).x, 30.0)
        assertClose(p(2).y, 60.0) // endpoint stayed on the line and slid with it
    }

    @Test
    fun closedWallLoopMitersEveryCornerWithNoCaps() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        // a rectangular room, then click the start to close
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 3.0))
        ed.click(Vec2(58.0, 40.0))
        ed.click(Vec2(2.0, 40.0))
        ed.click(Vec2(0.0, 0.0)) // clicking the start closes the loop and finishes

        val wall = ed.doc.walls.last()
        assertTrue(wall.closed, "loop should be closed")
        val walls = ed.doc.elements.count { it.kind == ElementKind.SEGMENT && it.style == Styles.WALL }
        assertEquals(8, walls, "4 legs x 2 faces, mitred all round, no end caps (an open 4-leg wall would be 10)")
    }

    @Test
    fun wallBuildsMiteredOffsetFacesFromACenterline() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm) // wall thickness
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0)) // start
        ed.click(Vec2(50.0, 4.0)) // +X -> corner (50,0)
        ed.click(Vec2(47.0, 40.0)) // +Y -> end (50,40)
        ed.finishPath()

        val walls = ed.doc.elements.filter { it.kind == ElementKind.SEGMENT && it.style == Styles.WALL }
        assertEquals(6, walls.size, "2 legs -> 2 face-segments per side + 2 end caps")

        // collect all wall endpoints; the mitred corner at centerline (50,0) with t=10 must appear
        val ev = Evaluator()
        val pts =
            walls.flatMap {
                val s = ev.segment(it.ref as SegmentRef)
                listOf(s.a, s.b)
            }

        fun has(
            x: Double,
            y: Double,
        ) = pts.any { kotlin.math.abs(it.x - x) < 1e-6 && kotlin.math.abs(it.y - y) < 1e-6 }
        assertTrue(has(45.0, 5.0), "inner miter corner") // offset intersection, not a plain offset
        assertTrue(has(55.0, -5.0), "outer miter corner")

        // parametric: doubling the thickness moves the miter to (40,10)/(60,-10)
        ed.doc.setParameter(ed.doc.scalars.first { it.name == "t" }, 20.0.mm)
        val pts3 =
            walls.flatMap {
                val s = Evaluator().segment(it.ref as SegmentRef)
                listOf(s.a, s.b)
            }
        assertTrue(pts3.any { kotlin.math.abs(it.x - 40.0) < 1e-6 && kotlin.math.abs(it.y - 10.0) < 1e-6 }, "miter follows thickness")
    }

    @Test
    fun openingCutsAParametricGapWithJambsIntoAWall() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 3.0)) // one straight leg -> (100,0)
        ed.finishPath()

        fun wallSegs() = ed.doc.elements.filter { it.kind == ElementKind.SEGMENT && it.style == Styles.WALL }
        assertEquals(4, wallSegs().size, "straight wall: 1 face seg/side + 2 caps")

        // cut a 20-wide opening near x=50
        ed.activeScalar = ed.doc.newParameter("w", 20.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(50.0, 0.0))
        assertEquals(8, wallSegs().size, "each face splits in two (+2) plus two jambs (+2)")

        val ev = Evaluator()
        val segs = wallSegs().map { ev.segment(it.ref as SegmentRef) }

        fun seg(
            ax: Double,
            ay: Double,
            bx: Double,
            by: Double,
        ) =
            segs.any {
                fun eq(
                    p: Double,
                    q: Double,
                ) = kotlin.math.abs(p - q) < 1e-6
                (eq(it.a.x, ax) && eq(it.a.y, ay) && eq(it.b.x, bx) && eq(it.b.y, by)) ||
                    (eq(it.a.x, bx) && eq(it.a.y, by) && eq(it.b.x, ax) && eq(it.b.y, ay))
            }
        // opening centred on 50, width 20 -> spans 40..60; jambs span the 10-thick wall at both edges
        assertTrue(seg(40.0, 5.0, 40.0, -5.0), "jamb at opening start")
        assertTrue(seg(60.0, 5.0, 60.0, -5.0), "jamb at opening end")
        assertTrue(seg(0.0, 5.0, 40.0, 5.0), "solid face piece up to the opening")
        assertTrue(seg(60.0, 5.0, 100.0, 5.0), "solid face piece after the opening")

        // parametric: position is anchored at the start edge (40); widening to 40 extends the end to 80
        ed.doc.setParameter(ed.doc.scalars.first { it.name == "w" }, 40.0.mm)
        val segs2 = wallSegs().map { Evaluator().segment(it.ref as SegmentRef) }
        assertTrue(
            segs2.any { kotlin.math.abs(it.a.x - 80.0) < 1e-6 && kotlin.math.abs(it.b.x - 80.0) < 1e-6 },
            "the end jamb follows the width parameter",
        )
    }

    @Test
    fun draggingAFreePointOntoALineAttachesItAsSlidingOnCurve() {
        val ed = Editor()
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 0.0)) // horizontal line
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 30.0)) // a free point above it
        val p = ed.doc.freePoints.last()

        // drag the free point down onto the line -> it should attach as an on-curve point
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(0.0, 30.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(10.0, 0.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(10.0, 0.0)))

        assertEquals(ElementKind.ON_CURVE, p.kind, "point should become on-curve after attaching")
        assertTrue(p.draggable, "an attached point still slides along the curve")
        assertClose(Evaluator().point(p.ref as constructit.dsl.PointRef).y, 0.0) // landed on the line

        // now it is constrained: dragging it away (up-left) keeps it on the line
        ed.pointerDown(ed.camera.worldToScreen(Vec2(10.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(-20.0, 25.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(-20.0, 25.0)))
        val pv = Evaluator().point(p.ref as constructit.dsl.PointRef)
        assertClose(pv.y, 0.0, tol = 1e-6)
        assertClose(pv.x, -20.0, tol = 1e-6) // slid along, stayed on the line

        // detaching restores an independent free point at the current position
        ed.doc.unweld(p)
        assertEquals(ElementKind.POINT, p.kind)
        assertEquals(null, p.handle)
        val pf = Evaluator().point(p.ref as constructit.dsl.PointRef)
        assertClose(pf.y, 0.0)
        assertClose(pf.x, -20.0)
    }
}
