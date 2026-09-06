package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.plane
import constructit.dsl.region
import constructit.dsl.solid
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.EdgeGeom
import constructit.geom.Geom3
import constructit.geom.MeshBool
import constructit.geom.Section3
import constructit.geom.Vec3
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of OP-31 slice 5c** (curved faces through the boolean) on what item 4 named as an
 * exposure: a result face's *pieces* are ordered by their smallest canonical triangle, and geometry can reorder
 * that. A pin through a plate has its cylinder split into two pieces and two circular creases; a fill on one
 * side, then the plate moved along the pin, must stay on that side — and the file must say the same body.
 */
class BooleanCurvedFaceProbeTest {
    private fun requireEngine() = assumeTrue(MeshBool.available, "no general boolean engine: ${MeshBool.status}")

    private fun Construction.rect(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        tag: String,
    ): Pair<RegionRef, List<constructit.dsl.PointRef>> {
        val a = freePoint("$tag.a", x0.mm, y0.mm)
        val b = freePoint("$tag.b", x1.mm, y0.mm)
        val d = freePoint("$tag.c", x1.mm, y1.mm)
        val e = freePoint("$tag.d", x0.mm, y1.mm)
        return region(loop(segment(a, b), segment(b, d), segment(d, e), segment(e, a))) to listOf(a, b, d, e)
    }

    private fun sideOf(
        ref: SolidRef,
        edge: Int,
    ): Double {
        val edges = assertNotNull(Section3.edges(Evaluator().solid(ref).feature).first)
        val g = edges[edge].geom
        // the crease's centre along x: read any point of the curve
        return when (g) {
            is EdgeGeom.Straight -> (g.a.x + g.b.x) / 2.0
            else -> {
                val p = Blend3.edgePath(edges[edge]).first ?: error("no path for $g")
                p.elements.first().start.x
            }
        }
    }

    @Test
    fun aFillOnOneSideOfAPinStaysOnThatSideWhenThePlateMoves() {
        requireEngine()
        val cx = Construction()
        // the plate: 40 × 40, 10 thick, z ∈ [0, 10], x ∈ [-20, 20] — its corners are free points we will move
        val (plateRegion, corners) = cx.rect(-20.0, -20.0, 20.0, 20.0, "plate")
        val plate = cx.extrude(cx.sketchOn(cx.planeXY(), plateRegion), cx.const(10.mm))
        // the pin: radius 4 along +x, from x = -40 to 40, centred at y = 0, z = 5
        val c = cx.freePoint("pin.c", 0.mm, 5.mm)
        val disc = cx.region(cx.loop(cx.circleCR(c, cx.const(4.mm))))
        val pin = cx.extrude(cx.sketchOn(cx.plane(Vec3(-40.0, 0.0, 0.0), Vec3.Y, Vec3.Z), disc), cx.const(80.mm))
        val part = cx.union(plate, pin)
        val ev0 = Evaluator().eval(part.node)
        assertTrue(ev0 !is EvalResult.Invalid, "the pinned plate builds: ${(ev0 as? EvalResult.Invalid)?.reason}")
        val (edges0, whyEdges) = Section3.edges(Evaluator().solid(part).feature)
        val edges = assertNotNull(edges0, "the pinned plate names its edges: ${whyEdges?.render()}")
        val rims = edges.indices.filter { i -> edges[i].reason == null && edges[i].geom !is EdgeGeom.Straight && abs(abs(sideOf(part, i)) - 20.0) < 1e-6 }
        assertEquals(2, rims.size, "the pin meets the plate in two circular creases: ${edges.map { it.name to it.geom::class.simpleName }}")
        val right = rims.first { sideOf(part, it) > 0 }
        val (choices, why) = Blend3.choicesFor(Evaluator().solid(part), listOf(right), BlendSection(BlendKind.FILLET, 1.5))
        assertNotNull(choices, "the pin's foot on the +x side is roundable: ${why?.render()}")
        val filled = cx.blendAll(part, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(1.5.mm), null, listOf(right), choices)))
        val base0 = Geom3.volume(Evaluator().solid(part).mesh)
        val v0 = Evaluator().solid(filled).mesh.also { assertManifold(it, "pin foot filled") }.let { Geom3.volume(it) }
        val added0 = v0 - base0
        assertTrue(added0 > 0.5, "a concave foot is filled: $added0")
        // move the plate 6 mm along the pin: x ∈ [-14, 26]
        cx.set(corners[0], (-14.0).mm, (-20.0).mm)
        cx.set(corners[3], (-14.0).mm, 20.0.mm)
        cx.set(corners[1], 26.0.mm, (-20.0).mm)
        cx.set(corners[2], 26.0.mm, 20.0.mm)
        val edgesAfter = assertNotNull(Section3.edges(Evaluator().solid(part).feature).first)
        assertEquals(edges.size, edgesAfter.size, "the edge count does not move")
        assertEquals(edges[right].name, edgesAfter[right].name, "the same index names the same crease")
        assertClose(26.0, sideOf(part, right), 1e-6, "…which is the +x foot, now at x = 26")
        val base1 = Geom3.volume(Evaluator().solid(part).mesh)
        val v1 = Evaluator().solid(filled).mesh.also { assertManifold(it, "pin foot filled, plate moved") }.let { Geom3.volume(it) }
        assertClose(added0, v1 - base1, 1e-6 * base1, "the fill is the same fill on the same side")
        // and it stands on the +x side: some of the filled body's material lies beyond x = 26 outside the pin's radius
        val mesh = Evaluator().solid(filled).mesh
        val beyond = mesh.vertices.count { it.x > 26.0 + 1e-6 && it.x < 27.6 && kotlin.math.hypot(it.y, it.z - 5.0) > 4.0 + 1e-6 }
        assertTrue(beyond > 0, "the fill's material stands on the +x face, not the −x one")
    }
}
