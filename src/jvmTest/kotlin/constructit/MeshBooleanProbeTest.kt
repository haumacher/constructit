package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.resultOf
import constructit.dsl.solid
import constructit.geom.Geom3
import constructit.geom.MeshBool
import constructit.geom.Vec3
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Probes past the spike's own cases: a BLIND bore (its end cap lives inside the material — a
 * different face topology from a through-hole), and a boolean CHAINED onto a mesh-boolean result
 * (the general engine's outputs must be as good an operand as anyone's).
 */
class MeshBooleanProbeTest {
    private fun requireEngine() = assumeTrue(MeshBool.available, "mesh boolean engine unavailable on this host")

    private fun Construction.rect(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        tag: String,
    ) = region(
        loop(
            segment(freePoint("a$tag", x0.mm, y0.mm), freePoint("b$tag", x1.mm, y0.mm)),
            segment(freePoint("b2$tag", x1.mm, y0.mm), freePoint("c$tag", x1.mm, y1.mm)),
            segment(freePoint("c2$tag", x1.mm, y1.mm), freePoint("d$tag", x0.mm, y1.mm)),
            segment(freePoint("d2$tag", x0.mm, y1.mm), freePoint("a2$tag", x0.mm, y0.mm)),
        ),
    )

    private fun Construction.disc(
        u: Double,
        v: Double,
        r: constructit.dsl.ScalarRef,
        tag: String,
    ) = region(loop(circleCR(freePoint("c$tag", u.mm, v.mm), r)))

    /** The user's literal ask: a 10 mm deep, 5 mm diameter hole into the back side — BLIND. */
    @Test
    fun aBlindBoreLeavesItsEndCapInsideTheMaterial() {
        requireEngine()
        val c = Construction()
        val plate = c.extrude(c.sketchOn(c.planeXY(), c.rect(-40.0, -25.0, 40.0, 25.0, "p")), c.parameter("t", 20.mm))
        val back = c.plane(Vec3(0.0, 25.0, 0.0), Vec3.X, Vec3.Z) // normal -Y: into the material from the back
        val bore = c.extrude(c.sketchOn(back, c.disc(0.0, 10.0, c.parameter("r", 2.5.mm), "b")), c.parameter("d", 10.mm))
        val part = c.subtract(plate, bore)

        val r = Evaluator().resultOf(part)
        assertTrue(r is EvalResult.Ok, "blind cross-axis bore should build: $r")
        val mesh = Evaluator().solid(part).mesh
        assertManifold(mesh, "blind bore")
        val expected = 80.0 * 50.0 * 20.0 - PI * 2.5 * 2.5 * 10.0
        assertTrue(abs(Geom3.volume(mesh) - expected) / expected < 1e-3, "volume ${Geom3.volume(mesh)} vs $expected")
    }

    /** (plate − horizontal bore) − vertical bore: the mesh result is an ordinary operand. */
    @Test
    fun aBooleanChainsOntoAMeshBooleanResult() {
        requireEngine()
        val c = Construction()
        val plate = c.extrude(c.sketchOn(c.planeXY(), c.rect(-40.0, -25.0, 40.0, 25.0, "p")), c.parameter("t", 20.mm))
        val front = c.plane(Vec3(0.0, -25.0, 0.0), Vec3.Z, Vec3.X)
        val horiz = c.extrude(c.sketchOn(front, c.disc(10.0, -25.0, c.parameter("r1", 4.mm), "h")), c.parameter("d1", 60.mm))
        val step1 = c.subtract(plate, horiz)
        // a vertical through-bore far from the horizontal one: volumes stay analytic
        val vert = c.extrude(c.sketchOn(c.planeXY(), c.disc(25.0, 10.0, c.parameter("r2", 3.mm), "v")), c.parameter("d2", 30.mm))
        val part = c.subtract(step1, vert)

        val r = Evaluator().resultOf(part)
        assertTrue(r is EvalResult.Ok, "chained boolean should build: $r")
        val mesh = Evaluator().solid(part).mesh
        assertManifold(mesh, "chained mesh boolean")
        val expected = 80.0 * 50.0 * 20.0 - PI * 4.0 * 4.0 * 50.0 - PI * 3.0 * 3.0 * 20.0
        assertTrue(abs(Geom3.volume(mesh) - expected) / expected < 2e-3, "volume ${Geom3.volume(mesh)} vs $expected")
    }
}
