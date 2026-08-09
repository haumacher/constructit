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
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.PointerButton
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

    /**
     * Panning moved from "drag empty space" to the middle button when the marquee took that gesture
     * over (OP-16) — and gained reach in exchange: it now works in *every* tool, not just SELECT.
     */
    @Test
    fun wheelZoomsAndMiddleDragPans() {
        val ed = Editor()
        val s0 = ed.camera.scale
        ed.wheel(Vec2(400.0, 300.0), -1.0)
        assertTrue(ed.camera.scale > s0, "wheel up should zoom in")

        ed.setTool(Tools.SELECT)
        val panBefore = ed.camera.panX
        ed.pointerDown(Vec2(400.0, 300.0), PointerButton.MIDDLE)
        ed.pointerMove(Vec2(415.0, 300.0))
        ed.pointerUp(Vec2(415.0, 300.0))
        assertClose(ed.camera.panX, panBefore + 15.0)

        // and while a drawing tool is in hand, where it used to be impossible
        ed.setTool(Tools.LINE)
        ed.pointerDown(Vec2(400.0, 300.0), PointerButton.MIDDLE)
        ed.pointerMove(Vec2(390.0, 300.0))
        ed.pointerUp(Vec2(390.0, 300.0))
        assertClose(ed.camera.panX, panBefore + 5.0)
        assertTrue(ed.doc.elements.isEmpty(), "a middle-drag must not place geometry")
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
        // (0,0) lies exactly on that line, so the centre snaps onto it and is drawn as an on-curve
        // point rather than a free one — the golden records that
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
    fun placingAPointOnACurveAttachesItInsteadOfLeavingItFloating() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-50.0, 0.0))
        ed.click(Vec2(50.0, 20.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-50.0, 0.0))
        ed.click(Vec2(50.0, 20.0))

        // place a point right on the line: it must become an on-curve slider, i.e. actually depend on
        // the line, not merely start out coincident with it
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 10.0))
        val placed = ed.doc.elements.last()
        assertEquals(ElementKind.ON_CURVE, placed.kind)
        val p = Evaluator().point(placed.ref as constructit.dsl.PointRef)
        assertClose(p.x, 0.0)
        assertClose(p.y, 10.0)

        // proof of the dependency: move the line's far end and the placed point follows it
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(50.0, 20.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(50.0, 60.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(50.0, 60.0)))
        val moved = Evaluator().point(placed.ref as constructit.dsl.PointRef)
        assertTrue(kotlin.math.abs(moved.y - 10.0) > 1.0, "the attached point rides the line it was placed on")
    }

    @Test
    fun placingAPointWhereTwoCurvesCrossMaterializesTheIntersection() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 0.0)) // horizontal line
        ed.click(Vec2(0.0, -40.0))
        ed.click(Vec2(0.0, 40.0)) // vertical line
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(0.0, -40.0))
        ed.click(Vec2(0.0, 40.0))

        // click where they cross: one derived point, and only the branch clicked
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.6, -0.4))
        val derived = ed.doc.elements.filter { it.kind == ElementKind.DERIVED_POINT }
        assertEquals(1, derived.size)
        val p = Evaluator().point(derived[0].ref as constructit.dsl.PointRef)
        assertClose(p.x, 0.0)
        assertClose(p.y, 0.0)
    }

    @Test
    fun snappingIsSuppressibleAndReportsWhatItWouldHit() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-50.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-50.0, 0.0))
        ed.click(Vec2(50.0, 0.0)) // horizontal line y=0

        // hovering over the line reports the snap before any click commits to it
        ed.setTool(Tools.POINT)
        ed.pointerMove(ed.camera.worldToScreen(Vec2(10.0, 0.0)))
        assertEquals(constructit.editor.SnapKind.ON_CURVE, ed.snapHint?.kind)
        assertTrue(ed.currentHelp().contains("on curve"))

        // with snapping off, the same click places an ordinary free point
        ed.snapEnabled = false
        ed.click(Vec2(10.0, 0.0))
        assertEquals(ElementKind.POINT, ed.doc.elements.last().kind)
    }

    @Test
    fun anOrthoPathStartedOnACurveIsAttachedToIt() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(30.0, -60.0))
        ed.click(Vec2(30.0, 60.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(30.0, -60.0))
        ed.click(Vec2(30.0, 60.0)) // a vertical wall to build off

        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(28.0, 10.0)) // near the segment: must land on it AND link to it
        ed.click(Vec2(90.0, 12.0))
        ed.finishPath()

        val path = ed.doc.orthoPaths.single()
        val start = Evaluator().point(path.vertices[0].ref)
        assertClose(start.x, 30.0) // exactly on the segment

        // linked, not merely coincident: move the segment and the path start follows it
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(30.0, -60.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(0.0, -60.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(0.0, -60.0)))
        val moved = Evaluator().point(path.vertices[0].ref)
        assertTrue(kotlin.math.abs(moved.x - 30.0) > 1.0, "the start rides the curve it was placed on")
    }

    @Test
    fun aPathEndReachingACurveAttachesAndFinishesTheRun() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(120.0, -60.0))
        ed.click(Vec2(120.0, 60.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(120.0, -60.0))
        ed.click(Vec2(120.0, 60.0)) // the wall to run into

        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(0.0, 40.0)) // a vertical leg
        ed.pointerMove(ed.camera.worldToScreen(Vec2(118.0, 41.0))) // aim at the wall
        assertEquals(constructit.editor.SnapKind.ON_CURVE, ed.snapHint?.kind, "later vertices must show the snap too")
        assertClose(ed.snapHint!!.pos.x, 120.0)
        assertClose(ed.snapHint!!.pos.y, 40.0) // where the leg meets the wall, not the cursor's projection

        ed.click(Vec2(118.0, 41.0))
        val path = ed.doc.orthoPaths.single()
        assertEquals(3, path.vertices.size)
        val end = Evaluator().point(path.vertices[2].ref)
        assertClose(end.x, 120.0)
        assertClose(end.y, 40.0)

        // the run is finished: a further click starts a new path rather than extending this one
        ed.click(Vec2(200.0, 200.0))
        assertEquals(2, ed.doc.orthoPaths.size, "reaching the wall ended the run")
        assertEquals(3, path.vertices.size)
    }

    @Test
    fun aRunWeldedToADerivedPointIsImmovableAndSaysSo() {
        // a junction can own *no* freedom: welded to a derived point, the meeting place is fixed by
        // construction. That is the honest immovable case, and it must explain itself.
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(0.0, -40.0))
        ed.click(Vec2(0.0, 40.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(0.0, -40.0))
        ed.click(Vec2(0.0, 40.0))
        ed.setTool(Tools.INTERSECT)
        ed.click(Vec2(20.0, 0.0))
        ed.click(Vec2(0.0, 20.0))
        val crossing = ed.doc.elements.last { it.kind == ElementKind.DERIVED_POINT }

        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(60.0, 60.0))
        ed.click(Vec2(60.0, 20.0))
        ed.finishPath()
        val path = ed.doc.orthoPaths.single()
        val end = ed.doc.elementFor(path.vertices[1].ref)!!
        assertTrue(ed.doc.weldOrthoEndpointToPoint(end, crossing))

        assertFalse(end.hasFreeDof, "welded to a fixed point, it owns nothing and nothing drives it")
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(0.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(20.0, 20.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(20.0, 20.0)))
        assertTrue(ed.statusHint.contains("no free direction") || ed.statusHint.contains("fully determined"), "got: '${ed.statusHint}'")
    }

    @Test
    fun bothEndsOfAConnectingPathAttachSymmetrically() {
        // the reported case: a Z-shaped run joining the left and right walls of a closed room. Its
        // first and last legs are symmetric to the eye, so they must be equally movable — they were
        // not, because the start attached before it had a leg and so had *both* coordinates pinned
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(-40.0, 70.0))
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(55.0, 0.0))
        ed.click(Vec2(55.0, 70.0))
        ed.click(Vec2(-40.0, 70.0)) // close the room
        val room = ed.doc.orthoPaths.single()
        val leftWall = room.legs[0]
        val rightWall = room.legs[2]

        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(-39.0, 32.0)) // starts on the left wall
        ed.click(Vec2(10.0, 32.0))
        ed.click(Vec2(10.0, 21.0))
        ed.click(Vec2(54.0, 21.0)) // ends on the right wall -> finishes the run
        val run = ed.doc.orthoPaths[1]

        val startHandle = ed.doc.elementFor(run.vertices.first().ref)!!.handle as constructit.editor.OrthoCornerHandle
        assertTrue(ed.doc.junctionOf(startHandle.xNode) != null, "the start is attached, via a junction")
        assertEquals(3, run.legCount)
        assertTrue(run.legs[0].hasFreeDof, "the first leg must be as movable as the last")
        assertTrue(run.legs[2].hasFreeDof, "the last leg was always movable")

        // and both really move, in the same way: perpendicular, carrying both of their own ends
        fun y(i: Int) = Evaluator().point(run.vertices[i].ref).y
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(-10.0, y(0))))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(-10.0, 45.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(-10.0, 45.0)))
        assertClose(y(0), 45.0)
        assertClose(y(1), 45.0)
        assertTrue(leftWall.hasFreeDof && rightWall.hasFreeDof, "the room's own walls stay movable")
    }

    @Test
    fun aPathBeingDrawnDoesNotSnapToItself() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 2.0)) // leg 0 along y=0

        // hovering back over the leg just drawn must not offer it as a target — attaching a path to
        // its own leg could only ever be refused as a cycle
        ed.pointerMove(ed.camera.worldToScreen(Vec2(30.0, 0.0)))
        assertEquals(null, ed.snapHint)
    }

    @Test
    fun anOrthoPathStartedOnAPointIsWeldedToIt() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(20.0, 30.0)) // a corner to build from
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(20.4, 29.6)) // near enough to snap onto it
        ed.click(Vec2(120.0, 32.0))
        ed.finishPath()

        val path = ed.doc.orthoPaths.single()
        val start = Evaluator().point(path.vertices[0].ref)
        assertClose(start.x, 20.0) // exactly on the point, not merely near it
        assertClose(start.y, 30.0)

        // and it is welded, not just coincident: drag the point and the path start follows
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(20.0, 30.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(-10.0, 55.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(-10.0, 55.0)))
        val moved = Evaluator().point(path.vertices[0].ref)
        assertClose(moved.x, -10.0)
        assertClose(moved.y, 55.0)
    }

    @Test
    fun aLegContinuingTheSameDirectionExtendsInsteadOfDoublingTheCorner() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 2.0)) // horizontal leg to (40,0)
        ed.click(Vec2(90.0, -1.0)) // straight on: must extend, not add a collinear leg
        ed.finishPath()

        val path = ed.doc.orthoPaths.single()
        assertEquals(2, path.vertices.size, "no straight-through corner was created")
        assertEquals(1, path.legCount)
        assertClose(Evaluator().point(path.vertices[1].ref).x, 90.0) // the leg simply got longer

        // every element still evaluates — a collinear pair would have produced an undefined miter
        val ev = Evaluator()
        assertTrue(ed.doc.elements.all { ev.eval(it.ref.node) is constructit.core.EvalResult.Ok })
    }

    @Test
    fun aWeldedVertexDragsWhatItIsWeldedTo() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(20.0, 30.0))
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(20.0, 30.0)) // snaps + welds the start onto that point
        ed.click(Vec2(120.0, 32.0))
        ed.finishPath()
        val master = ed.doc.elements.first { it.kind == ElementKind.POINT }
        val path = ed.doc.orthoPaths.single()
        val start = ed.doc.elementFor(path.vertices[0].ref)!!

        // it owns nothing of its own, but the junction it meets at is that point — so the gesture is
        // delegated rather than swallowed, and either element drags the pair
        assertTrue(start.hasFreeDof)
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(20.0, 30.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(-10.0, 55.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(-10.0, 55.0)))
        val ev = Evaluator()
        assertClose(ev.point(master.ref as constructit.dsl.PointRef).x, -10.0)
        assertClose(ev.point(path.vertices[0].ref).x, -10.0)
        assertClose(ev.point(path.vertices[0].ref).y, 55.0)
    }

    @Test
    fun typingALengthWhileDrawingPlacesTheLegExactly() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0)) // start
        // the mouse only supplies the direction — roughly to the right, a long way off
        ed.pointerMove(ed.camera.worldToScreen(Vec2(17.0, 2.0)))
        "350".forEach { assertTrue(ed.key(it.toString()), "digits feed the length entry") }
        assertEquals("350", ed.numericEntry)
        assertTrue(ed.key("Enter"))
        assertEquals("", ed.numericEntry, "committing clears the entry")

        val path = ed.doc.orthoPaths.single()
        assertEquals(2, path.vertices.size)
        val p1 = Evaluator().point(path.vertices[1].ref)
        assertClose(p1.x, 350.0) // exactly the typed length, in the direction the cursor indicated
        assertClose(p1.y, 0.0)

        // and the placed leg reports that length back, from either end
        val fields = path.legs[0].handle!!.fields()
        assertClose(fields.first { it.label == "length (move end)" }.read(Evaluator())!!.mm, 350.0)
    }

    @Test
    fun aTypedLengthFollowsTheCursorDirectionAndCanBeCancelled() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        // cursor below the start -> a downward vertical leg
        ed.pointerMove(ed.camera.worldToScreen(Vec2(-3.0, -20.0)))
        ed.key("8")
        ed.key("0")
        assertTrue(ed.key("Enter"))
        val path = ed.doc.orthoPaths.single()
        val p1 = Evaluator().point(path.vertices[1].ref)
        assertClose(p1.x, 0.0)
        assertClose(p1.y, -80.0) // sign taken from the cursor side

        // Escape cancels a pending entry rather than finishing the path
        ed.pointerMove(ed.camera.worldToScreen(Vec2(40.0, -78.0)))
        ed.key("5")
        assertEquals("5", ed.numericEntry)
        assertTrue(ed.key("Escape"))
        assertEquals("", ed.numericEntry)
        assertEquals(2, path.vertices.size, "no leg was placed")
        assertTrue(ed.doc.orthoPaths.isNotEmpty(), "and the path is still being drawn")

        // a second Escape finishes it
        assertTrue(ed.key("Escape"))
        ed.setTool(Tools.SELECT)
        assertEquals(2, path.vertices.size)
    }

    @Test
    fun backspaceEditsAPendingLengthEntry() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(10.0, 0.0)))
        ed.key("1")
        ed.key("2")
        ed.key("3")
        assertTrue(ed.key("Backspace"))
        assertEquals("12", ed.numericEntry)
        ed.key("Enter")
        assertClose(Evaluator().point(ed.doc.orthoPaths.single().vertices[1].ref).x, 12.0)
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
        assertEquals("leg ${ed.doc.nameOf(path.legs[0])}", ed.selectionLabel())
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
    fun breakingALegInsertsAZeroLengthCornerThatCanBePulledIntoAJog() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 2.0)) // one horizontal leg, 0..100 at y=0
        ed.finishPath()
        val path = ed.doc.orthoPaths.single()

        ed.setTool(Tools.BREAK_LEG)
        ed.click(Vec2(60.0, 1.0)) // click on the leg
        assertTrue(ed.statusHint.contains("broken"), "got: '${ed.statusHint}'")

        // one leg became three, with the middle one zero-length: the drawing has not changed shape
        assertEquals(4, path.vertices.size)
        assertEquals(3, path.legCount)
        val ev = Evaluator()
        val p = path.vertices.map { ev.point(it.ref) }
        assertClose(p[1].x, 60.0)
        assertClose(p[1].y, 0.0)
        assertClose(p[2].x, 60.0)
        assertClose(p[2].y, 0.0) // coincident with its neighbour — the jog starts closed
        assertClose(p[3].x, 100.0)
        assertEquals(0, path.legAxis(1).let { 1 - it }, "the inserted leg runs perpendicular to the one broken")

        // pull the far half down: only that half moves, and every leg stays axis-aligned
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(80.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(80.0, -25.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(80.0, -25.0)))
        val q = Evaluator().let { e -> path.vertices.map { e.point(it.ref) } }
        assertClose(q[0].y, 0.0) // near half untouched
        assertClose(q[1].y, 0.0)
        assertClose(q[2].y, -25.0) // far half dropped
        assertClose(q[3].y, -25.0)
        assertClose(q[1].x, 60.0) // the jog is vertical: the corner kept its x
        assertClose(q[2].x, 60.0)
    }

    @Test
    fun draggingAJogShutJoinsTheTwoSegmentsOnRelease() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0)) // V0
        ed.click(Vec2(50.0, 2.0)) // V1  horizontal at y=0
        ed.click(Vec2(48.0, -30.0)) // V2  vertical jog down
        ed.click(Vec2(110.0, -28.0)) // V3  horizontal at y=-30
        ed.finishPath()
        val path = ed.doc.orthoPaths.single()
        assertEquals(3, path.legCount)

        // drag the far horizontal leg back up level with the near one: the jog is now flat
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(80.0, -30.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(80.0, 0.0)))
        assertTrue(ed.statusHint.contains("Release to join"), "got: '${ed.statusHint}'")
        assertEquals(3, path.legCount, "nothing changes while dragging — a flat jog already looks joined")

        ed.pointerUp(ed.camera.worldToScreen(Vec2(80.0, 0.0)))
        assertEquals(1, path.legCount, "one leg survives")
        assertEquals(2, path.vertices.size, "the two corners of the flattened jog are gone")
        val ev = Evaluator()
        assertClose(ev.point(path.vertices[0].ref).x, 0.0)
        assertClose(ev.point(path.vertices[1].ref).x, 110.0)
        assertClose(ev.point(path.vertices[1].ref).y, 0.0)

        // and the survivor is a proper leg: dragging it moves the whole joined run
        ed.pointerDown(ed.camera.worldToScreen(Vec2(60.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(60.0, 20.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(60.0, 20.0)))
        val q = Evaluator()
        assertClose(q.point(path.vertices[0].ref).y, 20.0)
        assertClose(q.point(path.vertices[1].ref).y, 20.0)
    }

    @Test
    fun breakAndJoinAreInverses() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 2.0))
        ed.finishPath()
        val path = ed.doc.orthoPaths.single()

        ed.setTool(Tools.BREAK_LEG)
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(80.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(80.0, -25.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(80.0, -25.0)))
        assertEquals(3, path.legCount, "broken and pulled open")

        // drag it flat again: back to exactly one leg, geometry as it started
        ed.pointerDown(ed.camera.worldToScreen(Vec2(80.0, -25.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(80.0, 0.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(80.0, 0.0)))
        assertEquals(1, path.legCount)
        assertEquals(2, path.vertices.size)
        val ev = Evaluator()
        assertClose(ev.point(path.vertices[0].ref).x, 0.0)
        assertClose(ev.point(path.vertices[1].ref).x, 100.0)
        assertClose(ev.point(path.vertices[1].ref).y, 0.0)
    }

    @Test
    fun breakingNeedsASegmentButAnyOfThemWillDo() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 3.0))
        ed.click(Vec2(98.0, 60.0))
        ed.click(Vec2(0.0, 0.0)) // closed loop: 3 vertices, 3 legs
        val path = ed.doc.orthoPaths.single()

        ed.setTool(Tools.BREAK_LEG)
        ed.click(Vec2(300.0, 300.0)) // nothing there
        assertTrue(ed.statusHint.contains("Click a segment"), "got: '${ed.statusHint}'")
        assertEquals(3, path.legCount)

        // reported: the closing segment could not be broken. Its endpoints follow each other the other
        // way round, which used to be refused; now the jog is introduced on that side instead.
        val closing = path.legs.last()
        val seg = Evaluator().segment(closing.ref as SegmentRef)
        ed.click(Vec2((seg.a.x + seg.b.x) / 2, (seg.a.y + seg.b.y) / 2))
        assertEquals(5, path.vertices.size, "the closing segment breaks like any other: got '${ed.statusHint}'")
        assertEquals(5, path.legCount)
        assertTrue(path.closed, "and the loop is still closed")

        // the inserted corner opens like any other, and the loop stays rectilinear
        ed.setTool(Tools.SELECT)
        val mid = Evaluator().segment(path.legs[3].ref as SegmentRef)
        ed.pointerDown(ed.camera.worldToScreen(Vec2((mid.a.x + mid.b.x) / 2, (mid.a.y + mid.b.y) / 2)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(-30.0, (mid.a.y + mid.b.y) / 2)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(-30.0, (mid.a.y + mid.b.y) / 2)))
        val ev = Evaluator()
        for (i in 0 until path.legCount) {
            val l = ev.segment(path.legs[i].ref as SegmentRef)
            val horizontal = kotlin.math.abs(l.b.y - l.a.y) < 1e-6
            val vertical = kotlin.math.abs(l.b.x - l.a.x) < 1e-6
            assertTrue(horizontal || vertical, "leg $i is still axis-aligned")
        }
    }

    @Test
    fun aFlatJogElsewhereOnThePathSurvivesAnUnrelatedDrag() {
        // reported: break one side twice and another side once, pull a section of the first side out,
        // and the third break point vanished — an unrelated flat jog was being joined away
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 3.0)) // leg 0, bottom
        ed.click(Vec2(98.0, 60.0)) // leg 1, right
        ed.click(Vec2(2.0, 58.0)) // leg 2, top
        ed.click(Vec2(0.0, 0.0)) // close
        val path = ed.doc.orthoPaths.single()

        ed.setTool(Tools.BREAK_LEG)
        ed.click(Vec2(30.0, 0.0)) // break the bottom twice
        ed.click(Vec2(70.0, 0.0))
        ed.click(Vec2(50.0, 60.0)) // and the top once
        assertEquals(10, path.vertices.size)
        assertEquals(10, path.legCount)

        // pull a section of the bottom out; the top's flat jog must be left alone
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(50.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(50.0, -30.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(50.0, -30.0)))
        assertEquals(10, path.vertices.size, "the untouched break point on the top must survive")
        assertEquals(10, path.legCount)
    }

    @Test
    fun revertingADoubleBreakoutJoinsBothEndsInOneDrag() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(120.0, 3.0))
        ed.finishPath()
        val path = ed.doc.orthoPaths.single()

        ed.setTool(Tools.BREAK_LEG)
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(80.0, 0.0)) // two breaks -> a middle section between two flat jogs
        assertEquals(5, path.legCount)

        // pull the middle section out, then push it straight back: *both* jogs flatten, and one drag
        // must join both of them
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(60.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(60.0, -35.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(60.0, -35.0)))
        assertEquals(5, path.legCount, "pulled out")

        ed.pointerDown(ed.camera.worldToScreen(Vec2(60.0, -35.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(60.0, 0.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(60.0, 0.0)))
        assertEquals(1, path.legCount, "both flattened corners join in the one drag")
        assertEquals(2, path.vertices.size)
    }

    @Test
    fun theDraggedSectionSnapsToTheStationaryOneNotViceVersa() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 2.0))
        ed.finishPath()
        val path = ed.doc.orthoPaths.single()
        ed.setTool(Tools.BREAK_LEG)
        ed.click(Vec2(60.0, 0.0))

        // pull the *near* half up, so the half being dragged is the one others follow
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(30.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(30.0, 30.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(30.0, 30.0)))

        // drag it back to *nearly* level: the join must land on the stationary half's y (0), not on the
        // dragged half's y (-1) — the section fits to what it was aimed at, not the other way round
        ed.pointerDown(ed.camera.worldToScreen(Vec2(30.0, 30.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(30.0, -1.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(30.0, -1.0)))
        assertEquals(1, path.legCount)
        val ev = Evaluator()
        assertClose(ev.point(path.vertices[0].ref).y, 0.0)
        assertClose(ev.point(path.vertices[1].ref).y, 0.0)
    }

    @Test
    fun altKeepsAFlattenedCornerInsteadOfJoiningIt() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 2.0))
        ed.finishPath()
        val path = ed.doc.orthoPaths.single()
        ed.setTool(Tools.BREAK_LEG)
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(80.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(80.0, -30.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(80.0, -30.0)))

        ed.snapEnabled = false // Alt: leave the model as I put it
        ed.pointerDown(ed.camera.worldToScreen(Vec2(80.0, -30.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(80.0, 0.0)))
        assertFalse(ed.statusHint.contains("Release to join"), "no join is offered while Alt is held")
        ed.pointerUp(ed.camera.worldToScreen(Vec2(80.0, 0.0)))
        assertEquals(3, path.legCount, "the flattened corner is kept")

        // and releasing Alt makes the very same drag join again
        ed.snapEnabled = true
        ed.pointerDown(ed.camera.worldToScreen(Vec2(80.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(80.0, 1.0)))
        assertTrue(ed.statusHint.contains("Release to join"), "got: '${ed.statusHint}'")
        ed.pointerUp(ed.camera.worldToScreen(Vec2(80.0, 1.0)))
        assertEquals(1, path.legCount)
    }

    @Test
    fun draggingAndPlacingAgreeOnWhatCountsAsNearASegment() {
        // reported: placing a point correctly ignored positions past a segment's end, but *dragging* a
        // point there matched anyway — the magnet had its own distance rule, measuring to the infinite
        // carrier line instead of to the segment
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(0.0, 0.0)) // a segment spanning x -40..0 at y=0
        ed.setTool(Tools.POINT)
        ed.click(Vec2(60.0, 60.0)) // a free point to drag about
        val loose = ed.doc.freePoints.last()

        // well beyond the segment's end, but exactly on its infinite extension
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(60.0, 60.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(60.0, 0.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(60.0, 0.0)))
        assertEquals(ElementKind.POINT, loose.kind, "past the end is not on the segment, so no attach")
        assertClose(Evaluator().point(loose.ref as constructit.dsl.PointRef).x, 60.0)

        // and placing agrees: the same position offers no snap either
        ed.setTool(Tools.POINT)
        ed.pointerMove(ed.camera.worldToScreen(Vec2(90.0, 0.0)))
        assertEquals(null, ed.snapHint, "placement must judge nearness the same way")

        // over the segment's body, both do attach
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(60.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(-20.0, 0.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(-20.0, 0.0)))
        assertEquals(ElementKind.ON_CURVE, loose.kind, "on the segment itself, the magnet attaches")
    }

    @Test
    fun aGrabDoesNotJumpTheGeometryToTheCursor() {
        // reported: clicking an end and moving made it jump away before following the mouse. Picking has
        // a tolerance, so writing the cursor position outright moved the geometry by the grab offset.
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(80.0, 2.0))
        ed.finishPath()
        val end = ed.doc.orthoPaths.single().vertices[1].ref

        // grab it 2mm off-centre and move the cursor exactly 10mm: the endpoint must move 10mm too
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(82.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(92.0, 0.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(92.0, 0.0)))
        assertClose(Evaluator().point(end).x, 90.0, tol = 1e-6)

        // and a leg grabbed off its axis keeps that offset rather than snapping under the cursor
        val leg = ed.doc.orthoPaths.single().legs[0]
        ed.pointerDown(ed.camera.worldToScreen(Vec2(40.0, 2.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(40.0, 22.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(40.0, 22.0)))
        assertClose(Evaluator().segment(leg.ref as SegmentRef).a.y, 20.0, tol = 1e-6)
    }

    @Test
    fun clickingADanglingEndContinuesThatPathInsteadOfStartingAnother() {
        // reported: extending an open end added a segment that was not perpendicular to the one extended.
        // A separate path was being started there, and two paths cannot coalesce a straight-on step.
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 2.0)) // horizontal
        ed.click(Vec2(58.0, 40.0)) // then vertical
        ed.finishPath()
        val path = ed.doc.orthoPaths.single()
        assertEquals(listOf(0, 1), path.legAxes.toList())

        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(60.0, 40.0)) // the dangling end
        assertEquals(1, ed.doc.orthoPaths.size, "the same path is continued, not a second one begun")
        ed.click(Vec2(60.0, 90.0)) // straight on: lengthens the last leg rather than adding a corner
        assertEquals(2, path.legCount, "no phantom corner")
        assertEquals(listOf(0, 1), path.legAxes.toList())
        assertClose(Evaluator().point(path.vertices.last().ref).y, 90.0)

        ed.click(Vec2(120.0, 88.0)) // now perpendicular: a real corner
        assertEquals(3, path.legCount)
        assertEquals(listOf(0, 1, 0), path.legAxes.toList())
        ed.finishPath()
    }

    @Test
    fun aPathCanBeContinuedFromItsStartToo() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 2.0))
        ed.click(Vec2(58.0, 40.0))
        ed.finishPath()
        val path = ed.doc.orthoPaths.single()

        // clicking the *other* dangling end extends the same path from the front, symmetrically
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        assertEquals(1, ed.doc.orthoPaths.size)
        ed.click(Vec2(-40.0, 1.0)) // straight on -> lengthens the first leg
        assertEquals(2, path.legCount)
        assertClose(Evaluator().point(path.vertices.first().ref).x, -40.0)
        ed.click(Vec2(-38.0, -50.0)) // perpendicular -> a corner at the front
        assertEquals(3, path.legCount)
        assertEquals(listOf(1, 0, 1), path.legAxes.toList())
        ed.finishPath()

        // every leg is still axis-aligned after growing from the front
        val ev = Evaluator()
        for (i in 0 until path.legCount) {
            val l = ev.segment(path.legs[i].ref as SegmentRef)
            assertTrue(kotlin.math.abs(l.b.y - l.a.y) < 1e-6 || kotlin.math.abs(l.b.x - l.a.x) < 1e-6, "leg $i")
        }
    }

    @Test
    fun hoveringTheStartPreviewsTheClosedShapeItWillMakeNotABandToIt() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 3.0)) // -> (60,0)
        ed.click(Vec2(58.0, 40.0)) // -> (60,40)
        ed.click(Vec2(-4.0, 44.0)) // -> (-4,40): its own coordinate is x, and closing will align it to 0
        val path = ed.doc.orthoPaths.single()

        // closing moves that last corner into line with the start, so the preview must show it there
        val closing = ed.doc.orthoClosePreview(path)
        assertEquals(2, closing.size, "the leg into the moved corner, and the closing leg")
        assertClose(closing[0].second.x, 0.0) // the corner previewed *already aligned*
        assertClose(closing[0].second.y, 40.0)
        assertClose(closing[1].second.x, 0.0) // and the closing leg reaching the start
        assertClose(closing[1].second.y, 0.0)

        // hovering there says so, and clicking produces exactly the previewed shape
        ed.pointerMove(ed.camera.worldToScreen(Vec2(0.0, 0.0)))
        assertTrue(ed.statusHint.contains("close the loop"), "got: '${ed.statusHint}'")
        ed.click(Vec2(0.0, 0.0))
        assertTrue(path.closed)
        val ev = Evaluator()
        assertClose(ev.point(path.vertices.last().ref).x, 0.0)
        assertClose(ev.point(path.vertices.last().ref).y, 40.0)
    }

    @Test
    fun aDoubleClickFinishesWithoutLeavingAHairlineSegment() {
        // reported: ending a path with a double-click dropped two points instead of one. The second
        // click of the pair landed a near-zero leg before dblclick finished the path.
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 2.0))
        ed.click(Vec2(58.0, 40.0))
        // the double-click: two clicks a pixel apart, then the dblclick that finishes
        ed.click(Vec2(60.0, 40.0))
        ed.click(Vec2(60.2, 40.1))
        ed.finishPath()

        val path = ed.doc.orthoPaths.single()
        assertEquals(3, path.vertices.size, "no extra vertex from the repeat click")
        assertEquals(2, path.legCount)
        assertClose(Evaluator().point(path.vertices.last().ref).y, 40.0)
    }

    /**
     * A symmetric cross: four runs, each drawn *inward* and welded at the shared centre on arrival. The
     * first run introduces the centre's y (its own leg is horizontal), which is what makes it the arm
     * every cycle question is about.
     */
    private val cross =
        """
        constructit 1
        orthostart -80,30 -> e1
        orthovertex -24.875,30 -> e2,e3
        orthostart 30,30 -> e4
        orthovertex -24.875,30 -> e5,e6
        weldortho e5 e2
        orthostart -24.875,-21 -> e7
        orthovertex -24.875,30 -> e8,e9
        weldortho e8 e5
        orthostart -24.875,70.25 -> e10
        orthovertex -24.875,30 -> e11,e12
        weldortho e11 e8
        """.trimIndent()

    @Test
    fun outerCornersOfACrossDragSymmetrically() {
        // reported: in a symmetric cross of four runs welded at one centre, dragging *legs* behaved but
        // dragging outer corners did not — pulling one along its own arm dragged the shared centre
        // sideways and collapsed the figure. Delegating a driven coordinate handed the junction the whole
        // cursor instead of just the axis it owns.

        /** Drag the outer corner at [at] by [by], and report every vertex position afterwards. */
        fun dragOuter(
            at: Vec2,
            by: Vec2,
        ): List<Vec2> {
            val ed = Editor(constructit.editor.DocumentFormat.load(cross))
            ed.pointerDown(ed.camera.worldToScreen(at))
            ed.pointerMove(ed.camera.worldToScreen(at + by))
            ed.pointerUp(ed.camera.worldToScreen(at + by))
            val ev = Evaluator()
            return ed.doc.orthoPaths.flatMap { p -> p.vertices.map { ev.point(it.ref) } }
        }

        val left = Vec2(-80.0, 30.0)
        val right = Vec2(30.0, 30.0)
        val down = Vec2(-24.875, -21.0)
        val up = Vec2(-24.875, 70.25)
        val along = 20.0

        // along its own arm, an outer corner moves alone — the centre must not budge
        for ((name, at) in listOf("left" to left, "right" to right)) {
            val after = dragOuter(at, Vec2(along, 0.0))
            assertClose(after[1].x, -24.875, tol = 1e-6) // the centre, reached from the left arm
            assertClose(after[1].y, 30.0, tol = 1e-6)
            assertEquals(1, after.count { kotlin.math.abs(it.x - (at.x + along)) < 1e-6 }, "only $name moved")
        }
        for ((name, at) in listOf("down" to down, "up" to up)) {
            val after = dragOuter(at, Vec2(0.0, along))
            assertClose(after[1].x, -24.875, tol = 1e-6)
            assertClose(after[1].y, 30.0, tol = 1e-6)
            assertEquals(1, after.count { kotlin.math.abs(it.y - (at.y + along)) < 1e-6 }, "only $name moved")
        }

        // across its arm, it can only move by taking the centre with it — and opposite arms agree exactly
        val leftAcross = dragOuter(left, Vec2(0.0, along))
        val rightAcross = dragOuter(right, Vec2(0.0, along))
        assertClose(leftAcross[1].y, 50.0, tol = 1e-6)
        assertEquals(leftAcross.map { "${it.x},${it.y}" }, rightAcross.map { "${it.x},${it.y}" }, "left and right agree")

        val downAcross = dragOuter(down, Vec2(along, 0.0))
        val upAcross = dragOuter(up, Vec2(along, 0.0))
        assertClose(downAcross[1].x, -4.875, tol = 1e-6)
        assertClose(downAcross[1].y, 30.0, tol = 1e-6) // the centre moved in x only
        assertEquals(downAcross.map { "${it.x},${it.y}" }, upAcross.map { "${it.x},${it.y}" }, "down and up agree")
    }

    @Test
    fun droppingARunEndOntoGeometryItAlreadyDrivesIsRefusedInsteadOfCyclic() {
        // reported: the cross still "crashed the drawing". Not a wrong position — a *dead* editor. Dragging
        // the first run's far end anywhere into the figure welded it onto the shared centre, but the centre's
        // y IS that run's y, so the graph became cyclic and the evaluator recursed until the stack died.
        //
        // The cycle guard asked whether the target depended on the dragged *point*. A connection binds the
        // corner's coordinate **masters**, which sit upstream of that point, so the dependency that closes
        // the loop was invisible to it. 66 of 154 drop positions for that one arm killed the editor.
        val left = Vec2(-80.0, 30.0)
        val centre = Vec2(-24.875, 30.0)
        val drops =
            listOf(
                "the shared centre" to centre,
                "within the magnet's reach of it" to centre + Vec2(6.0, 6.0),
                "the far arm's leg" to Vec2(-24.875, 5.0),
                "the far arm's end" to Vec2(-24.875, 70.25),
            )
        for ((what, drop) in drops) {
            val ed = Editor(constructit.editor.DocumentFormat.load(cross))
            ed.pointerDown(ed.camera.worldToScreen(left))
            for (i in 1..4) ed.pointerMove(ed.camera.worldToScreen(left + (drop - left) * (i / 4.0)))
            ed.pointerUp(ed.camera.worldToScreen(drop))

            // evaluating at all is the assertion: a cyclic graph throws StackOverflowError here
            val ev = Evaluator()
            val vertices = ed.doc.orthoPaths.flatMap { p -> p.vertices.map { ev.point(it.ref) } }
            assertEquals(8, vertices.size, "the four arms survive dropping the first arm's end on $what")
            assertTrue(vertices.all { it.x.isFinite() && it.y.isFinite() }, "finite after dropping on $what")
            // refused, so the arm just stands where it was dragged and the junction stays as it was
            assertEquals(1, ed.doc.junctions.size, "no second junction invented for $what")
        }

        // and the refusal is visible while dragging, rather than a halo promising a join that never happens
        val ed = Editor(constructit.editor.DocumentFormat.load(cross))
        ed.pointerDown(ed.camera.worldToScreen(left))
        ed.pointerMove(ed.camera.worldToScreen(centre))
        assertTrue(ed.statusHint.contains("Can't join"), "got: '${ed.statusHint}'")

        // the same loop was reachable while *drawing*: continuing that arm from its dangling end and
        // clicking the centre welds through the very same funnel (a snapped path vertex links there too)
        val drawn = Editor(constructit.editor.DocumentFormat.load(cross))
        drawn.setTool(Tools.ORTHO_PATH)
        drawn.click(left)
        drawn.click(centre)
        drawn.finishPath()
        val after = Evaluator()
        assertTrue(
            drawn.doc.orthoPaths.flatMap { p -> p.vertices.map { after.point(it.ref) } }.all { it.x.isFinite() },
            "extending the first arm through the centre leaves an evaluable drawing",
        )
    }

    @Test
    fun anEndStillWeldsOntoAPointItDoesNotDrive() {
        // the other half of the guard: it must reject only what is actually circular. Two unrelated runs
        // still join, so the fix above is not a blanket "ortho ends never weld".
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.finishPath()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 60.0))
        ed.click(Vec2(50.0, 60.0))
        ed.finishPath()

        ed.setTool(Tools.SELECT)
        val from = Vec2(50.0, 0.0)
        val onto = Vec2(50.0, 60.0)
        ed.pointerDown(ed.camera.worldToScreen(from))
        for (i in 1..4) ed.pointerMove(ed.camera.worldToScreen(from + (onto - from) * (i / 4.0)))
        ed.pointerUp(ed.camera.worldToScreen(onto))

        assertTrue(ed.statusHint.contains("Joined"), "got: '${ed.statusHint}'")
        assertEquals(1, ed.doc.junctions.size)
        val ev = Evaluator()
        val ends = ed.doc.orthoPaths.map { ev.point(it.vertices.last().ref) }
        assertClose(ends[0].x, ends[1].x, tol = 1e-6)
        assertClose(ends[0].y, ends[1].y, tol = 1e-6)
    }

    @Test
    fun bothRunsAtAJunctionDragTheSameWay() {
        // reported twice: a horizontal run ending on a slanted segment with a vertical run hanging off
        // that junction behaved asymmetrically — first the legs, then the corners. The cause was that
        // whichever run connected first owned the junction's one DOF. Now the junction owns it (OP-20).
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-43.375, 83.75))
        ed.click(Vec2(110.125, -12.75))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-43.375, 83.75))
        ed.click(Vec2(110.125, -12.75))

        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(-68.625, 39.25))
        ed.click(Vec2(30.0, 39.0)) // aimed at the segment: the leg runs on until it meets it
        val across = ed.doc.orthoPaths[0]
        assertEquals(1, ed.doc.junctions.size, "meeting the segment made a junction")

        val j = Evaluator().point(across.vertices[1].ref)
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(j.x, j.y)) // second run starts at the junction
        ed.click(Vec2(j.x, j.y - 76.5))
        ed.finishPath()
        val down = ed.doc.orthoPaths[1]
        assertEquals(1, ed.doc.junctions.size, "and joins the same junction rather than inventing another")

        // symmetry: each run's far corner has one coordinate of its own and reaches the shared one
        // through the junction, so both offer two drag directions
        val farAcross = ed.doc.elementFor(across.vertices[0].ref)!!
        val farDown = ed.doc.elementFor(down.vertices[1].ref)!!
        assertTrue(farAcross.hasFreeDof)
        assertTrue(farDown.hasFreeDof)
        assertEquals(2, farAcross.handle!!.fields().count { it.writable && it.label in setOf("x", "y") })
        assertEquals(2, farDown.handle!!.fields().count { it.writable && it.label in setOf("x", "y") })

        // and both legs drag: the vertical one sideways, which slides the junction along the segment and
        // pushes the horizontal run in y — the mirror of what dragging the horizontal leg always did
        assertTrue(across.legs[0].hasFreeDof)
        assertTrue(down.legs[0].hasFreeDof)
        val yBefore = Evaluator().point(across.vertices[0].ref).y
        val target = j.x - 30.0
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(j.x, j.y - 40.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(target, j.y - 40.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(target, j.y - 40.0)))

        val ev = Evaluator()
        assertClose(ev.point(down.vertices[1].ref).x, target, tol = 1e-6) // exactly under the cursor
        assertTrue(kotlin.math.abs(ev.point(across.vertices[0].ref).y - yBefore) > 1.0, "the other run followed")
        // the junction is still exactly on the segment: re-parameterised, never forced
        val seg = ev.segment(ed.doc.elements.first { it.kind == ElementKind.SEGMENT }.ref as SegmentRef)
        val jNow = ev.point(across.vertices[1].ref)
        assertClose((jNow - seg.a).cross((seg.b - seg.a).normalized()), 0.0, tol = 1e-6)
    }

    @Test
    fun aLegIsAxisAlignedByBindingNotBySharingOneNode() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0)) // V0
        ed.click(Vec2(40.0, 3.0)) // V1 — horizontal leg, so y is bound
        ed.click(Vec2(38.0, 30.0)) // V2 — vertical leg, so x is bound
        ed.finishPath()
        val v = ed.doc.orthoPaths.single().vertices

        // each vertex owns its own pair of nodes; alignment is a *binding*, which can be re-pointed in
        // place — that is what makes breaking and joining a run possible at all (OP-19)
        assertTrue(v[1].corner.yNode !== v[0].corner.yNode, "not the same node")
        assertTrue(v[1].corner.yNode.boundTo === v[0].corner.yNode, "V1's y follows V0's")
        assertTrue(v[2].corner.xNode.boundTo === v[1].corner.xNode, "V2's x follows V1's")
        assertTrue(v[1].corner.xNode.boundTo == null, "the coordinate a leg introduces stays free")

        // a write resolves along the chain to the node that owns the value
        assertTrue(constructit.editor.writableMaster(v[1].corner.yNode) === v[0].corner.yNode)
        assertTrue(constructit.editor.writableMaster(v[0].corner.yNode) === v[0].corner.yNode)

        // and the geometry is unchanged by all that: the leg is still exactly horizontal
        val ev = Evaluator()
        assertClose(ev.point(v[0].ref).y, ev.point(v[1].ref).y)
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

    /**
     * **A driven coordinate is not read-only — as far as the junction can reach, and no further** (OP-13,
     * OP-20). Both halves matter, and the second one was missing: a field the panel offered whose write did
     * nothing at all is the typed twin of a drag that does nothing (GitHub issue #4).
     *
     * On a **slanted** host the junction's slide changes both world coordinates, so both are settable and it
     * solves for its own parameter. On a host that runs along one axis it owns *that* coordinate and the host
     * determines the other outright, so the other one reads and cannot be written — and says so by being
     * greyed out, exactly as the drag along that axis moves nothing.
     */
    @Test
    fun anAttachedCoordinateIsDrivenYetStillSettableThroughItsJunction() {
        fun attachedTo(
            from: Vec2,
            to: Vec2,
        ): Pair<Editor, Element> {
            val ed = Editor()
            ed.setTool(Tools.LINE)
            ed.click(from)
            ed.click(to)
            ed.setTool(Tools.ORTHO_PATH)
            ed.click(Vec2(0.0, 0.0))
            ed.click(Vec2(20.0, 2.0)) // a horizontal leg ending short of the line
            ed.finishPath()
            val v2 = ed.doc.elementFor(ed.doc.orthoPaths.single().vertices[1].ref)!!
            ed.setTool(Tools.SELECT)
            ed.pointerDown(ed.camera.worldToScreen(Vec2(20.0, 0.0)))
            ed.pointerMove(ed.camera.worldToScreen(Vec2(30.0, 0.0)))
            ed.pointerUp(ed.camera.worldToScreen(Vec2(30.0, 0.0))) // drag it onto the line -> attach
            return ed to v2
        }

        // a slanted host: the junction reaches both coordinates, and typing one solves for its parameter
        val (slanted, w) = attachedTo(Vec2(20.0, -50.0), Vec2(40.0, 50.0))
        val wh = w.handle as constructit.editor.OrthoCornerHandle
        assertEquals(null, constructit.editor.writableMaster(wh.xNode), "x is derived, not owned")
        assertTrue(slanted.doc.junctionOf(wh.xNode) != null, "it is owned by the junction it meets at")
        val wx = w.handle!!.fields().first { it.label == "x" }
        assertTrue(wx.writable, "a driven coordinate the junction can reach is not read-only")
        wx.write(32.0.mm)
        assertClose(Evaluator().point(w.ref as constructit.dsl.PointRef).x, 32.0, msg = "and the write lands exactly")

        // a vertical host: it fixes x at 30, so x reads and cannot be written — the honest answer
        val (vertical, v2) = attachedTo(Vec2(30.0, -50.0), Vec2(30.0, 50.0))
        val h = v2.handle as constructit.editor.OrthoCornerHandle
        assertEquals(null, constructit.editor.writableMaster(h.xNode), "x is derived, not owned")
        assertTrue(vertical.doc.junctionOf(h.xNode) != null, "it is owned by the junction it meets at")
        val x = v2.handle!!.fields().first { it.label == "x" }
        assertFalse(x.writable, "the host determines x, so no value can move it — and the panel must say so")
        // …while y, the coordinate that junction *does* own, is driven and still settable
        val y = v2.handle!!.fields().first { it.label == "y" }
        assertTrue(y.writable, "the junction owns y along this host")
        y.write(17.0.mm)
        assertClose(Evaluator().point(v2.ref as constructit.dsl.PointRef).y, 17.0)
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
    fun aWallKeepsItsCarrierPath() {
        val ed = Editor()
        ed.setTool(Tools.WALL)
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 2.0))
        ed.finishPath()
        val tp = ed.doc.thickNetworks.single()
        assertTrue(tp.path === ed.doc.orthoPaths.single(), "the thick path's carrier is the retained path")
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

    // The thick path's own geometry — mitres, rings, the plan gap at an interval — is pinned in
    // ThickPathTest, at the model level where it now lives (OP-21).

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
        // a free point carries a handle too, so its position is typeable as well as draggable
        assertEquals(listOf("x", "y"), p.handle!!.fields().map { it.label })
        assertTrue(p.handle!!.fields().all { it.writable })
        val pf = Evaluator().point(p.ref as constructit.dsl.PointRef)
        assertClose(pf.y, 0.0)
        assertClose(pf.x, -20.0)
    }

    /**
     * **Arming the tool that is already armed keeps its picks.** Found by the probe of the ghost layer: a
     * cross-space *Sweep* had reported "1 pick kept across the switch" and one further click on the live
     * tool's own palette button threw that pick away without a word — so the promise the sweep's help makes
     * ("switch the plane between clicks and the picks are kept") depended on the user not touching the
     * palette. Arming a *different* tool still abandons the half-finished one; abandoning deliberately is
     * still Escape.
     */
    @Test
    fun reArmingTheLiveToolKeepsWhatItHasCollected() {
        val ed = Editor()
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-20.0, 0.0))
        ed.click(Vec2(20.0, 0.0))
        ed.setTool(Tools.MIRROR)
        ed.click(Vec2(0.0, 0.0))
        assertEquals(1, ed.toolPicks.size, "one half of the mirror is collected")

        ed.setTool(Tools.MIRROR)
        assertEquals(1, ed.toolPicks.size, "re-arming the same tool keeps it")
        assertTrue(ed.statusLine.contains("1 pick so far"), "and says where the gesture stands: ${ed.statusLine}")

        ed.setTool(Tools.CIRCLE)
        assertTrue(ed.toolPicks.isEmpty(), "arming a different tool abandons the half-finished one")

        // …and Escape is still how a gesture is abandoned deliberately
        ed.setTool(Tools.MIRROR)
        ed.click(Vec2(0.0, 0.0))
        assertEquals(1, ed.toolPicks.size)
        ed.key("Escape")
        assertTrue(ed.toolPicks.isEmpty(), "Escape abandons the picks")
    }
}
