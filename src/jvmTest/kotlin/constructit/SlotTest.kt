package constructit

import constructit.core.Evaluator
import constructit.dsl.*
import constructit.svg.Drawable
import constructit.svg.Svg
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test

/** Showcase: slot / obround — outer common tangents of two equal circles + mirror symmetry (Tier 2). */
class SlotTest {

    @Test
    fun outerTangentsAndMirrorSymmetry() {
        val c = Construction()
        val c1 = c.circleCR(c.freePoint("C1", (-30).mm, 0.mm), c.parameter("r1", 10.mm))
        val c2 = c.circleCR(c.freePoint("C2", 30.mm, 0.mm), c.parameter("r2", 10.mm))
        val t0 = c.outerTangent(c1, c2, +1)
        val t1 = c.outerTangent(c1, c2, -1)

        val ev = Evaluator()
        val l0 = ev.line(t0); val l1 = ev.line(t1)

        // equal circles => outer tangents are horizontal, offset by +/-10
        assertClose(l0.dir.y, 0.0, tol = 1e-6)
        assertClose(l1.dir.y, 0.0, tol = 1e-6)
        assertClose(abs(l0.origin.y), 10.0)
        assertClose(abs(l1.origin.y), 10.0)
        assertClose(l0.origin.y, -l1.origin.y)  // symmetric about the x-axis

        // mirroring one tangent across the x-axis yields the other
        val xAxis = c.lineThrough(c.freePoint("o", 0.mm, 0.mm), c.freePoint("x", 1.mm, 0.mm))
        val mirrored = ev.line(c.mirror(t0, xAxis))
        assertClose(mirrored.origin.y, l1.origin.y)
    }

    @Test
    fun svgGolden() {
        val c = Construction()
        val c1 = c.circleCR(c.freePoint("C1", (-30).mm, 0.mm), c.parameter("r1", 10.mm))
        val c2 = c.circleCR(c.freePoint("C2", 30.mm, 0.mm), c.parameter("r2", 10.mm))
        val svg = Svg.render(
            Evaluator(),
            listOf(
                Drawable(c1), Drawable(c2),
                Drawable(c.outerTangent(c1, c2, +1), stroke = "#d62728"),
                Drawable(c.outerTangent(c1, c2, -1), stroke = "#d62728"),
            ),
        )
        Golden.check("slot", svg)
    }
}
