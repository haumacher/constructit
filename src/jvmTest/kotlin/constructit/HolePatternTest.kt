package constructit

import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.svg.Drawable
import constructit.dsl.HolePatternArgs
import constructit.dsl.holePattern
import constructit.dsl.instance
import constructit.dsl.point
import constructit.svg.Svg
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals

/** DoD example 4: rectangular hole pattern (grid of points). */
class HolePatternTest {

    @Test
    fun gridPositions() {
        val c = Construction()
        val origin = c.freePoint("O", 0.mm, 0.mm)
        val hp = c.instance(
            holePattern, "hp",
            HolePatternArgs(origin, rows = 3, cols = 4, dx = c.parameter("dx", 20.mm), dy = c.parameter("dy", 15.mm)),
        )
        val ev = Evaluator()
        assertEquals(3, hp.points.size)
        assertEquals(4, hp.points[0].size)
        for (row in 0 until 3) {
            for (col in 0 until 4) {
                val p = ev.point(hp.points[row][col])
                assertClose(p.x, col * 20.0, msg = "x at [$row,$col]")
                assertClose(p.y, row * 15.0, msg = "y at [$row,$col]")
            }
        }
    }

    @Test
    fun svgGolden() {
        val c = Construction()
        val origin = c.freePoint("O", 0.mm, 0.mm)
        val hp = c.instance(
            holePattern, "hp",
            HolePatternArgs(origin, 3, 4, c.parameter("dx", 20.mm), c.parameter("dy", 15.mm)),
        )
        val items = ArrayList<Drawable>()
        hp.points.forEach { row -> row.forEach { items.add(Drawable(it)) } }
        Golden.check("hole_pattern", Svg.render(Evaluator(), items))
    }
}
