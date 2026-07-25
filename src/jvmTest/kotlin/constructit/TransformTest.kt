package constructit

import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.arc
import constructit.dsl.circle
import constructit.dsl.point
import constructit.dsl.segment
import constructit.units.deg
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** General affine transforms applied to any geometry value (Tier 1). */
class TransformTest {
    private fun xAxis(c: Construction) = c.lineThrough(c.freePoint("o", 0.mm, 0.mm), c.freePoint("x", 1.mm, 0.mm))

    private fun yAxis(c: Construction) = c.lineThrough(c.freePoint("o2", 0.mm, 0.mm), c.freePoint("y", 0.mm, 1.mm))

    @Test
    fun mirrorPointAcrossXAxis() {
        val c = Construction()
        val p = c.freePoint("P", 3.mm, 4.mm)
        val m = Evaluator().point(c.mirror(p, xAxis(c)))
        assertClose(m.x, 3.0)
        assertClose(m.y, -4.0)
    }

    @Test
    fun rotateSegment90AboutOrigin() {
        val c = Construction()
        val seg = c.segment(c.freePoint("A", 10.mm, 0.mm), c.freePoint("B", 20.mm, 0.mm))
        val r = c.rotate(seg, c.freePoint("O", 0.mm, 0.mm), c.const(90.0.deg))
        val s = Evaluator().segment(r)
        assertClose(s.a.x, 0.0)
        assertClose(s.a.y, 10.0)
        assertClose(s.b.x, 0.0)
        assertClose(s.b.y, 20.0)
    }

    @Test
    fun scaleCircleAboutOrigin() {
        val c = Construction()
        val circle = c.circleCR(c.freePoint("C", 10.mm, 0.mm), c.parameter("r", 5.mm))
        val s = Evaluator().circle(c.scaleGeom(circle, c.freePoint("O", 0.mm, 0.mm), c.const(constructit.units.Quantity.number(2.0))))
        assertClose(s.center.x, 20.0)
        assertClose(s.center.y, 0.0)
        assertClose(s.radius, 10.0)
    }

    @Test
    fun mirrorArcFlipsOrientation() {
        val c = Construction()
        // ccw arc, centre (10,0)
        val arc = c.arc(c.freePoint("C", 10.mm, 0.mm), c.parameter("r", 5.mm), c.const(0.0.deg), c.const(90.0.deg), ccw = true)
        val original = Evaluator().arc(arc)
        val mirrored = Evaluator().arc(c.mirror(arc, yAxis(c)))
        assertTrue(original.ccw)
        assertFalse(mirrored.ccw, "reflection flips arc orientation")
        assertClose(mirrored.center.x, -10.0)
        assertClose(mirrored.center.y, 0.0)
        assertClose(mirrored.radius, 5.0)
    }
}
