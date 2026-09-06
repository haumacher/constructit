package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.plane
import constructit.dsl.region
import constructit.dsl.resultOf
import constructit.dsl.solid
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.EdgeGeom
import constructit.geom.FaceName
import constructit.geom.Geom3
import constructit.geom.MeshBool
import constructit.geom.Section3
import constructit.geom.Vec3
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of OP-31 item 4** (face provenance through the general boolean) on what the delivery
 * never saw: a *corner* between two creases of a fused body, a boolean of a boolean keeping the first result's
 * slots, and an address that survives a parameter change.
 */
class BooleanProvenanceProbeTest {
    private fun requireEngine() = assumeTrue(MeshBool.available, "no general boolean engine: ${MeshBool.status}")

    private fun Construction.rect(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        tag: String,
    ): RegionRef {
        val a = freePoint("$tag.a", x0.mm, y0.mm)
        val b = freePoint("$tag.b", x1.mm, y0.mm)
        val d = freePoint("$tag.c", x1.mm, y1.mm)
        val e = freePoint("$tag.d", x0.mm, y1.mm)
        return region(loop(segment(a, b), segment(b, d), segment(d, e), segment(e, a)))
    }

    private fun Construction.post(): SolidRef = extrude(sketchOn(planeXY(), rect(-10.0, -10.0, 10.0, 10.0, "post")), const(20.mm))

    private fun Construction.bar(depth: constructit.dsl.ScalarRef): SolidRef =
        extrude(sketchOn(plane(Vec3(0.0, -30.0, 0.0), Vec3.Z, Vec3.X), rect(5.0, -5.0, 15.0, 5.0, "bar")), depth)

    private fun volume(
        ref: SolidRef,
        what: String,
    ): Double {
        val ev = Evaluator()
        val r = ev.resultOf(ref)
        assertTrue(r is EvalResult.Ok, "$what builds: ${(r as? EvalResult.Invalid)?.reason}")
        val s = ev.solid(ref)
        assertManifold(s.mesh, what)
        return Geom3.volume(s.mesh)
    }

    /** The indices of the creases along the post's top cap (z = 20), in list order. */
    private fun topCreases(ref: SolidRef): List<Int> {
        val edges = assertNotNull(Section3.edges(Evaluator().solid(ref).feature).first, "edges are named")
        return edges.indices.filter { i ->
            val g = edges[i].geom as? EdgeGeom.Straight ?: return@filter false
            g.a.z > 19.999 && g.b.z > 19.999
        }
    }

    private fun Construction.round(
        on: SolidRef,
        edges: List<Int>,
        r: Double,
    ): SolidRef {
        val (choices, why) = Blend3.choicesFor(Evaluator().solid(on), edges, BlendSection(BlendKind.FILLET, r))
        assertNotNull(choices, "the choice scores: ${why?.render()}")
        return blendAll(on, planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, const(r.mm), null, edges, choices)))
    }

    @Test
    fun twoCreasesOfAFusedBodySharingACornerBuildTheMitre() {
        requireEngine()
        val c = Construction()
        val part = c.union(c.post(), c.bar(c.const(60.mm)))
        val before = volume(part, "the cross bar")
        val top = topCreases(part)
        assertEquals(4, top.size, "the post's top cap keeps its four creases: $top")
        // two creases of the cap that share a corner: the ones whose ends touch
        val edges = assertNotNull(Section3.edges(Evaluator().solid(part).feature).first)

        fun ends(i: Int) = (edges[i].geom as EdgeGeom.Straight).let { listOf(it.a, it.b) }
        val pair =
            top.flatMap { i -> top.filter { j -> j > i && ends(i).any { p -> ends(j).any { q -> (p - q).length() < 1e-9 } } }.map { i to it } }
                .first()
        val r = 4.0
        val l = 20.0
        val rounded = c.round(part, listOf(pair.first, pair.second), r)
        val drop = before - volume(rounded, "two top creases rounded")
        val w = raspWedgeArea(r)
        val wChords = raspWedgeAreaByChords(r)
        val corner = r * r * r * (5.0 / 3.0 - PI / 2.0)
        val noise = 1e-6 * before
        assertTrue(drop < 2 * w * l - noise, "a corner is built: the drop $drop is below the no-corner figure ${2 * w * l}")
        assertTrue(drop <= 2 * wChords * l - corner + noise, "…and never more than the chords less the exact mitre: $drop")
        assertTrue(drop >= 2 * w * l - 1.2 * corner - noise, "…nor less than the wedges less the mitre: $drop")
        // a crossing adds a crease between the two bands and no face (session 79), so the figure is the whole proof
    }

    @Test
    fun aBooleanOfABooleanKeepsTheFirstResultsSlots() {
        requireEngine()
        val c = Construction()
        val fused = c.union(c.post(), c.bar(c.const(60.mm)))
        val fusedFaces = assertNotNull(Section3.faces(Evaluator().solid(fused).feature).first)
        // a 6 × 6 pocket, 5 deep, in the middle of the post's top cap
        val pocket = c.extrude(c.sketchOn(c.plane(Vec3(0.0, 0.0, 15.0), Vec3.X, Vec3.Y), c.rect(-3.0, -3.0, 3.0, 3.0, "pocket")), c.const(10.mm))
        val part = c.subtract(fused, pocket)
        val before = volume(part, "the pocketed cross bar")
        val faces = assertNotNull(Section3.faces(Evaluator().solid(part).feature).first, "a boolean of a boolean names its faces")
        for (i in fusedFaces.indices) {
            val n = faces[i].name as FaceName.BoolFace
            assertEquals(0, n.operand, "slot $i is the first operand's")
            assertEquals(fusedFaces[i].name, n.of, "slot $i is the fused body's own face $i, in its order")
        }
        assertEquals(fusedFaces.size + 6, faces.size, "the pocket adds its six faces after them")
        // the pocket's creases at a height: its rim (z = 20, convex: the top cap against a wall) and its floor
        // (z = 15, concave: the floor against a wall), each 6 mm
        val edges = assertNotNull(Section3.edges(Evaluator().solid(part).feature).first)

        fun creaseAt(z: Double) =
            edges.indices.first { i ->
                val g = edges[i].geom as? EdgeGeom.Straight ?: return@first false
                kotlin.math.abs(g.a.z - z) < 1e-6 && kotlin.math.abs(g.b.z - z) < 1e-6 && (g.b - g.a).length() in 5.999..6.001
            }
        val r = 2.0
        val noise = 1e-6 * before
        val removed = before - volume(c.round(part, listOf(creaseAt(20.0)), r), "the pocket's rim rounded")
        assertTrue(removed >= raspWedgeArea(r) * 6.0 - noise, "the rim is convex: a rounding takes the ball's wedge: $removed")
        assertTrue(removed <= raspWedgeAreaByChords(r) * 6.0 + noise, "…and at most its chords: $removed")
        val added = volume(c.round(part, listOf(creaseAt(15.0)), r), "the pocket's floor crease filled") - before
        assertTrue(added >= raspWedgeArea(r) * 6.0 - noise, "the floor crease is concave: a fill adds the ball's wedge: $added")
        assertTrue(added <= raspWedgeAreaByChords(r) * 6.0 + noise, "…and at most its chords: $added")
    }

    @Test
    fun anEdgeAddressSurvivesAParameterChange() {
        requireEngine()
        val c = Construction()
        val depth = c.parameter("d", 60.mm)
        val part = c.union(c.post(), c.bar(depth))
        val edgesBefore = assertNotNull(Section3.edges(Evaluator().solid(part).feature).first)
        // a crease of the bar's far end: both ends at y = 30
        val far = edgesBefore.indices.first { i -> (edgesBefore[i].geom as EdgeGeom.Straight).let { it.a.y > 29.999 && it.b.y > 29.999 } }
        val name = edgesBefore[far].name
        val rounded = c.round(part, listOf(far), 2.0)
        val v60 = volume(rounded, "far-end crease rounded at 60")
        c.set(depth, 80.mm)
        val edgesAfter = assertNotNull(Section3.edges(Evaluator().solid(part).feature).first)
        assertEquals(edgesBefore.size, edgesAfter.size, "the count does not move")
        assertEquals(name, edgesAfter[far].name, "the same index names the same crease")
        val g = edgesAfter[far].geom as EdgeGeom.Straight
        assertTrue(g.a.y > 49.999 && g.b.y > 49.999, "…which has moved with the bar's end: $g")
        val v80 = volume(rounded, "far-end crease rounded at 80")
        // the bar grew 20 mm × 10 × 10; the fill/wedge along a 10 mm crease is unchanged
        assertClose(2000.0, v80 - v60, tol = 0.05, msg = "only the bar's growth changes the volume")
    }
}
