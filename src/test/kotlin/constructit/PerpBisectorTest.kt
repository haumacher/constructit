package constructit

import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.svg.Drawable
import constructit.dsl.isValid
import constructit.dsl.line
import constructit.dsl.point
import constructit.svg.Svg
import constructit.units.mm
import constructit.geom.Vec2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DoD example 1: perpendicular bisector of two points via two equal circles + intersection.
 * Demonstrates OP-1 (Select), OP-3 (invalid propagation), and parametric recompute.
 */
class PerpBisectorTest {

    private fun build(c: Construction, radiusMm: Double) = c.run {
        val p1 = freePoint("P1", (-30).mm, 0.mm)
        val p2 = freePoint("P2", 30.mm, 0.mm)
        val r = parameter("R", radiusMm.mm)
        val c1 = circleCR(p1, r)
        val c2 = circleCR(p2, r)
        val pair = intersectCC(c1, c2)
        val x1 = select(pair, +1)
        val x2 = select(pair, -1)
        val line = lineThrough(x1, x2)
        Triple(Triple(p1, p2, r), Triple(c1, c2, pair), Triple(x1, x2, line))
    }

    @Test
    fun geometryIsCorrect() {
        val c = Construction()
        val (pts, _, out) = build(c, 40.0)
        val (p1, p2, _) = pts
        val (x1, x2, line) = out
        val ev = Evaluator()

        val vp1 = ev.point(p1); val vp2 = ev.point(p2)
        val vx1 = ev.point(x1); val vx2 = ev.point(x2)

        // intersection points equidistant from both centres (equal circles => on the bisector)
        assertClose((vx1 - vp1).length(), (vx1 - vp2).length())
        assertClose((vx2 - vp1).length(), (vx2 - vp2).length())

        // Select(+1) is the left of directed P1->P2 (i.e. +y here)
        assertTrue(vx1.y > 0 && vx2.y < 0)

        val l = ev.line(line)
        // passes through the midpoint (origin): perpendicular distance ~ 0
        assertClose(abs((Vec2(0.0, 0.0) - l.origin).cross(l.dir)), 0.0)
        // perpendicular to P1P2 (x-axis) => vertical direction
        assertClose(l.dir.x, 0.0)
    }

    @Test
    fun invalidWhenCirclesDoNotMeet() {
        val c = Construction()
        val (_, _, out) = build(c, 20.0) // R=20 < |P1P2|/2=30 => empty intersection
        val (_, _, line) = out
        val ev = Evaluator()
        assertFalse(ev.isValid(line), "line must be invalid when circles do not intersect (OP-3)")
    }

    @Test
    fun parametricRecompute() {
        val c = Construction()
        val (pts, _, out) = build(c, 40.0)
        val (_, _, r) = pts
        val (x1, _, _) = out

        val y40 = Evaluator().point(x1).y
        c.set(r, 50.mm)                 // edit parameter
        val y50 = Evaluator().point(x1).y   // fresh pass re-propagates
        assertTrue(y50 > y40, "increasing R must move the intersection outward ($y40 -> $y50)")
        assertClose(y50, 40.0)          // sqrt(50^2 - 30^2) = 40
    }

    @Test
    fun svgGolden() {
        val c = Construction()
        val (pts, circ, out) = build(c, 40.0)
        val (p1, p2, _) = pts
        val (c1, c2, _) = circ
        val (x1, x2, line) = out
        val svg = Svg.render(
            Evaluator(),
            listOf(
                Drawable(c1, stroke = "#bbbbbb"),
                Drawable(c2, stroke = "#bbbbbb"),
                Drawable(line, stroke = "#d62728"),
                Drawable(p1), Drawable(p2),
                Drawable(x1, stroke = "#2ca02c"), Drawable(x2, stroke = "#2ca02c"),
            ),
        )
        Golden.check("perp_bisector", svg)
    }
}
