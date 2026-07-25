package constructit

import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.arc
import constructit.dsl.segment
import constructit.svg.Drawable
import constructit.svg.Svg
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test

/** Showcase: fillet arc tangent to both legs of a corner (Tier 2). */
class FilletTest {
    @Test
    fun filletIsTangentToBothLegs() {
        val c = Construction()
        val v = c.freePoint("V", 0.mm, 0.mm)
        val p1 = c.freePoint("P1", 50.mm, 0.mm) // leg along +x
        val p2 = c.freePoint("P2", 0.mm, 50.mm) // leg along +y  (right angle)
        val arc = c.filletCorner(p1, v, p2, c.parameter("r", 10.mm))

        val a = Evaluator().arc(arc)
        assertClose(a.radius, 10.0)
        // right-angle corner => centre at (10,10); distance to each leg (the axes) equals r
        assertClose(abs(a.center.y), 10.0) // distance to x-axis
        assertClose(abs(a.center.x), 10.0) // distance to y-axis
        assertClose(a.center.x, 10.0)
        assertClose(a.center.y, 10.0)
    }

    @Test
    fun svgGolden() {
        val c = Construction()
        val v = c.freePoint("V", 0.mm, 0.mm)
        val p1 = c.freePoint("P1", 50.mm, 0.mm)
        val p2 = c.freePoint("P2", 0.mm, 40.mm)
        val arc = c.filletCorner(p1, v, p2, c.parameter("r", 12.mm))
        val svg =
            Svg.render(
                Evaluator(),
                listOf(
                    Drawable(c.segment(v, p1), stroke = "#999999"),
                    Drawable(c.segment(v, p2), stroke = "#999999"),
                    Drawable(arc, stroke = "#d62728"),
                ),
            )
        Golden.check("fillet_corner", svg)
    }
}
