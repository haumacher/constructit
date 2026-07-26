package constructit

import constructit.core.CircleValue
import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.PointRef
import constructit.dsl.point
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **An arc is a circle operand** — the user report *"intersect between arc and circle not working"*.
 *
 * It was one filter and nothing else: every circle slot demanded `ElementKind.CIRCLE`, so an arc was refused
 * although every op it would have fed is about the *carrier* circle. Now a `CIRCLE` slot takes an arc exactly
 * as a `LINE` slot takes a segment, through one coercion in one place (`Document.carrierCircle`, the twin of
 * `carrierLine`).
 *
 * The honest consequence, asserted here rather than only stated: a point derived from an arc's carrier may
 * land **off the drawn arc**, just as an intersection on a segment's carrier line may land beyond its ends.
 */
class ArcCarrierTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.pointsAt(vararg ps: Vec2) {
        setTool(Tools.POINT)
        ps.forEach { click(it) }
    }

    /** A quarter-ish arc of radius 40 about the origin, from (40,0) up through (0,40) — its own carrier is R=40. */
    private fun Editor.arcAboutOrigin() {
        pointsAt(Vec2(40.0, 0.0), Vec2(sqrt(800.0), sqrt(800.0)), Vec2(0.0, 40.0))
        setTool(Tools.ARC_3)
        click(Vec2(40.0, 0.0))
        click(Vec2(sqrt(800.0), sqrt(800.0)))
        click(Vec2(0.0, 40.0))
    }

    private fun derivedPoints(ed: Editor): List<Vec2> {
        val ev = Evaluator()
        return ed.doc.elements.filter { it.kind == ElementKind.DERIVED_POINT }.map { ev.point(it.ref as PointRef) }
    }

    @Test
    fun anArcIntersectsACircle() {
        val ed = Editor()
        ed.arcAboutOrigin()
        // a circle of R=40 about (40,0): it crosses the arc's carrier where x=20, y=±sqrt(1200)
        ed.setTool(Tools.CIRCLE_R)
        ed.activeScalar = ed.doc.newParameter("R", 40.0.mm)
        ed.click(Vec2(40.0, 0.0))

        ed.setTool(Tools.INTERSECT)
        ed.click(Vec2(sqrt(800.0), sqrt(800.0))) // on the arc
        ed.click(Vec2(40.0, 40.0)) // on the circle
        val pts = derivedPoints(ed)
        assertEquals(2, pts.size, "an arc and a circle meet twice on their carriers")
        for (p in pts) {
            assertClose(p.x, 20.0, msg = "both hits are on x = 20")
            assertClose(p.length(), 40.0, msg = "on the arc's carrier")
            assertClose((p - Vec2(40.0, 0.0)).length(), 40.0, msg = "and on the circle")
        }
        // one of them is *below* the arc's sweep — the carrier is the construction, and that is honest
        assertTrue(pts.any { it.y < 0.0 } && pts.any { it.y > 0.0 }, "got $pts")

        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save -> load -> save must be identical")
        assertEquals(2, DocumentFormat.load(text).elements.count { it.kind == ElementKind.DERIVED_POINT })
    }

    @Test
    fun anArcIntersectsASegmentOnItsCarriers() {
        val ed = Editor()
        ed.arcAboutOrigin()
        ed.pointsAt(Vec2(-60.0, 20.0), Vec2(60.0, 20.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-60.0, 20.0))
        ed.click(Vec2(60.0, 20.0)) // the line y = 20 cuts the carrier at x = ±sqrt(1200)

        ed.setTool(Tools.INTERSECT)
        ed.click(Vec2(0.0, 20.0)) // the segment
        ed.click(Vec2(sqrt(800.0), sqrt(800.0))) // the arc
        val pts = derivedPoints(ed)
        assertEquals(2, pts.size)
        for (p in pts) {
            assertClose(p.y, 20.0)
            assertClose(p.length(), 40.0)
        }
    }

    /** The branch is the click's, and stored as a `Select` sign (OP-1) — on an arc leg like on any other. */
    @Test
    fun theClickedBranchIsTheOneKept() {
        fun hit(near: Vec2): Vec2 {
            val ed = Editor()
            ed.arcAboutOrigin()
            ed.pointsAt(Vec2(-60.0, 20.0), Vec2(60.0, 20.0))
            ed.setTool(Tools.SEGMENT)
            ed.click(Vec2(-60.0, 20.0))
            ed.click(Vec2(60.0, 20.0))
            // drag-to-attach's own route: `intersectnear` keeps a single branch, the one clicked
            val seg = ed.doc.elements.last { it.kind == ElementKind.SEGMENT }
            val arc = ed.doc.elements.last { it.kind == ElementKind.ARC }
            ed.doc.intersectNear(seg, arc, near)
            return derivedPoints(ed).last()
        }
        assertTrue(hit(Vec2(35.0, 20.0)).x > 0.0, "clicked right, kept right")
        assertTrue(hit(Vec2(-35.0, 20.0)).x < 0.0, "clicked left, kept left")
    }

    @Test
    fun anArcFeedsConcentricAndTangentTools() {
        val ed = Editor()
        ed.arcAboutOrigin()

        ed.activeScalar = ed.doc.newParameter("d", 10.0.mm)
        ed.setTool(Tools.CONCENTRIC)
        ed.click(Vec2(0.0, 40.0)) // the arc
        ed.click(Vec2(0.0, 60.0)) // outside it
        val circle = ed.doc.elements.last { it.kind == ElementKind.CIRCLE }
        val c = (Evaluator().eval(circle.ref.node) as? EvalResult.Ok)?.value as? CircleValue
        assertClose(c!!.circle.radius, 50.0, msg = "the arc's carrier offset outward by d")

        // a tangent from a point outside: two tangent points, both on the arc's carrier
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 100.0))
        ed.setTool(Tools.TANGENT)
        ed.click(Vec2(0.0, 100.0))
        ed.click(Vec2(sqrt(800.0), sqrt(800.0))) // the arc
        val tangencies = derivedPoints(ed).takeLast(2)
        assertEquals(2, tangencies.size)
        for (t in tangencies) assertClose(t.length(), 40.0, msg = "a tangency lies on the carrier circle")

        // and a radius measurement reads the carrier's radius
        ed.setTool(Tools.RADIUS)
        ed.click(Vec2(0.0, 40.0))
        assertClose(Evaluator().eval(ed.doc.scalars.last().ref.node).let { (it as EvalResult.Ok).value }.let { (it as constructit.core.ScalarValue).q.mm }, 40.0)

        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "all of it round-trips")
    }

    /** A point placed on an arc rides its carrier circle — one degree of freedom, draggable as ever. */
    @Test
    fun aPointOnAnArcRidesItsCarrier() {
        val ed = Editor()
        ed.arcAboutOrigin()
        ed.setTool(Tools.POINT_ON_CIRCLE)
        ed.click(Vec2(0.0, 40.0))
        val rider = ed.doc.elements.last { it.kind == ElementKind.ON_CURVE }
        assertClose(Evaluator().point(rider.ref as PointRef).length(), 40.0, msg = "it sits on the carrier")
        assertTrue(rider.draggable, "and it still has its one degree of freedom")
    }
}
