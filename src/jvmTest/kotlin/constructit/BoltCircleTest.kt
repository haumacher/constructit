package constructit

import constructit.core.Evaluator
import constructit.dsl.BoltCircleArgs
import constructit.dsl.Construction
import constructit.dsl.boltCircle
import constructit.dsl.circle
import constructit.dsl.instance
import constructit.dsl.point
import constructit.svg.Drawable
import constructit.svg.Svg
import constructit.units.deg
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertEquals

/** DoD example 3: bolt circle — N equally spaced holes on a pitch-circle. */
class BoltCircleTest {
    @Test
    fun holesAtExpectedRadiusAndAngles() {
        val c = Construction()
        val center = c.freePoint("C", 0.mm, 0.mm)
        val bc =
            c.instance(
                boltCircle,
                "bc",
                BoltCircleArgs(center, pitchDiameter = c.parameter("pcd", 100.mm), count = 6, startAngle = c.parameter("a0", 0.deg), holeDiameter = c.parameter("hd", 8.mm)),
            )
        val ev = Evaluator()
        val origin = ev.point(center)

        assertEquals(6, bc.points.size)
        bc.points.forEachIndexed { i, pRef ->
            val p = ev.point(pRef)
            // radius = pcd/2 = 50
            assertClose((p - origin).length(), 50.0)
            // angle = i*60 degrees
            val expected = i * 60.0 * PI / 180.0
            val actual = ((atan2(p.y - origin.y, p.x - origin.x) % (2 * PI)) + 2 * PI) % (2 * PI)
            assertClose(actual, expected, tol = 1e-6, msg = "hole $i angle")
        }
        // hole circles have radius = hd/2 = 4
        bc.holes.forEach { assertClose(ev.circle(it).radius, 4.0) }
    }

    @Test
    fun svgGolden() {
        val c = Construction()
        val center = c.freePoint("C", 0.mm, 0.mm)
        val bc =
            c.instance(
                boltCircle,
                "bc",
                BoltCircleArgs(center, c.parameter("pcd", 100.mm), 6, c.parameter("a0", 0.deg), c.parameter("hd", 8.mm)),
            )
        val items = ArrayList<Drawable>()
        // pitch circle (construction geometry) + holes + hole centres
        items.add(Drawable(c.circleCR(center, c.parameter("pr", 50.mm)), stroke = "#bbbbbb"))
        bc.holes.forEach { items.add(Drawable(it)) }
        bc.points.forEach { items.add(Drawable(it, stroke = "#2ca02c")) }
        Golden.check("bolt_circle", Svg.render(Evaluator(), items))
    }
}
