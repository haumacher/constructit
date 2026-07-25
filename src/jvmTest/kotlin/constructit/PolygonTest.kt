package constructit

import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.PointRef
import constructit.dsl.scalar
import constructit.dsl.segment
import constructit.svg.Drawable
import constructit.svg.Svg
import constructit.units.deg
import constructit.units.mm
import kotlin.test.Test

/** Showcase: regular hexagon built by rotating one vertex about the centre (Tier 1 transforms). */
class PolygonTest {
    private fun hexagon(c: Construction): Pair<PointRef, List<PointRef>> {
        val center = c.freePoint("O", 0.mm, 0.mm)
        val p0 = c.translate(center, c.parameter("r", 40.mm), c.const(0.mm)) // (40,0)
        val pts =
            (0 until 6).map { k ->
                if (k == 0) p0 else c.rotate(p0, center, c.const((k * 60.0).deg))
            }
        return center to pts
    }

    @Test
    fun equalSidesAndInteriorAngles() {
        val c = Construction()
        val (_, pts) = hexagon(c)
        val ev = Evaluator()
        for (k in 0 until 6) {
            // side length: for a hexagon inscribed in r, the side equals r = 40
            val side = ev.scalar(c.measureLength(c.segment(pts[k], pts[(k + 1) % 6]))).mm
            assertClose(side, 40.0, tol = 1e-6, msg = "side $k")
            // interior angle = (6-2)*180/6 = 120 deg
            val prev = pts[(k + 5) % 6]
            val next = pts[(k + 1) % 6]
            assertClose(ev.scalar(c.measureAngle(prev, pts[k], next)).deg, 120.0, tol = 1e-6, msg = "angle $k")
        }
    }

    @Test
    fun svgGolden() {
        val c = Construction()
        val (_, pts) = hexagon(c)
        val items = ArrayList<Drawable>()
        for (k in 0 until 6) items.add(Drawable(c.segment(pts[k], pts[(k + 1) % 6])))
        pts.forEach { items.add(Drawable(it, stroke = "#2ca02c")) }
        Golden.check("hexagon", Svg.render(Evaluator(), items))
    }
}
