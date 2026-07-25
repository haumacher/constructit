package constructit

import constructit.core.Evaluator
import constructit.core.SegmentValue
import constructit.dsl.Construction
import constructit.dsl.RoundedRectArgs
import constructit.dsl.StandardRectArgs
import constructit.dsl.arc
import constructit.dsl.instance
import constructit.dsl.roundedRect
import constructit.dsl.standardRect
import constructit.dsl.valueOf
import constructit.svg.Drawable
import constructit.svg.Svg
import constructit.units.mm
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * DoD example 2: rounded-rectangle macro (OP-6) + standardRect specialization (partial application).
 */
class RoundedRectTest {
    private fun bboxExtents(
        ev: Evaluator,
        segments: List<constructit.dsl.SegmentRef>,
    ): Pair<Double, Double> {
        var maxX = 0.0
        var maxY = 0.0
        for (s in segments) {
            val seg = (ev.valueOf(s) as SegmentValue).seg
            maxX = max(maxX, max(abs(seg.a.x), abs(seg.b.x)))
            maxY = max(maxY, max(abs(seg.a.y), abs(seg.b.y)))
        }
        return (2 * maxX) to (2 * maxY)
    }

    @Test
    fun dimensionsRadiusAndPathIds() {
        val c = Construction()
        val center = c.freePoint("C", 0.mm, 0.mm)
        val rr =
            c.instance(
                roundedRect,
                "rr",
                RoundedRectArgs(center, c.parameter("w", 40.mm), c.parameter("h", 30.mm), c.parameter("r", 5.mm)),
            )
        val ev = Evaluator()

        // bounding box equals width x height
        val (w, h) = bboxExtents(ev, rr.segments)
        assertClose(w, 40.0)
        assertClose(h, 30.0)

        // every corner arc has the requested radius
        for (a in rr.arcs) assertClose(ev.arc(a).radius, 5.0)

        // OP-6: instance internals carry derived path-ids "rr/..."
        assertTrue(rr.arcs[0].node.id.startsWith("rr/"), "expected path-id, got ${rr.arcs[0].node.id}")
    }

    @Test
    fun standardRectIsRoundedWith2mm() {
        val c = Construction()
        val center = c.freePoint("C", 0.mm, 0.mm)
        val std = c.instance(standardRect, "std", StandardRectArgs(center, c.parameter("w", 40.mm), c.parameter("h", 30.mm)))
        val ref = c.instance(roundedRect, "ref", RoundedRectArgs(c.freePoint("C2", 0.mm, 0.mm), c.parameter("w2", 40.mm), c.parameter("h2", 30.mm), c.parameter("r2", 2.mm)))
        val ev = Evaluator()
        for (i in std.arcs.indices) {
            assertClose(ev.arc(std.arcs[i]).radius, 2.0)
            val a = ev.arc(std.arcs[i])
            val b = ev.arc(ref.arcs[i])
            assertClose(a.center.x, b.center.x)
            assertClose(a.center.y, b.center.y)
        }
    }

    @Test
    fun svgGolden() {
        val c = Construction()
        val center = c.freePoint("C", 0.mm, 0.mm)
        val rr =
            c.instance(
                roundedRect,
                "rr",
                RoundedRectArgs(center, c.parameter("w", 40.mm), c.parameter("h", 30.mm), c.parameter("r", 5.mm)),
            )
        val items = ArrayList<Drawable>()
        rr.segments.forEach { items.add(Drawable(it)) }
        rr.arcs.forEach { items.add(Drawable(it)) }
        Golden.check("rounded_rect", Svg.render(ev = Evaluator(), items = items))
    }
}
