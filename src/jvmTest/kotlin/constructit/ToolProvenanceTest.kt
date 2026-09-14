package constructit

import constructit.core.Evaluator
import constructit.dsl.solid
import constructit.geom.BlendKind
import constructit.geom.BoolFace3
import constructit.geom.FaceName
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.MeshBool
import constructit.geom.Revolve3
import constructit.geom.Section3
import constructit.geom.Surface3
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Every tool the dressing builds states the surfaces it is swept from** (OP-31, slice 5w) — asked of the
 * matrix's own 288 two-edge cells, in both gesture routes, and answered with the number it actually is.
 */
class ToolProvenanceTest {
    private val L = LBlock()

    private val kinds = listOf(BlendKind.FILLET, BlendKind.CHAMFER)

    class Tally(var tools: Int = 0, var without: Int = 0, var strayTriangles: Int = 0, val why: MutableMap<String, Int> = LinkedHashMap())

    private fun sweep(pairs: List<Pair<Int, Int>>): Tally {
        val t = Tally()
        Geom3.combined = { _, _, b ->
            val f = b.feature
            if (f is Feature3.MeshBoolean) {
                t.tools++
                val prov = f.provenance
                if (prov == null) {
                    t.without++
                    val key = f.provenanceRefusal?.render() ?: "no reason at all"
                    t.why[key] = (t.why[key] ?: 0) + 1
                } else {
                    // **and a carrier for every triangle, asked of the triangles** — a face list is a
                    // statement about the mesh, so the claim is put to every facet of it: each one has to
                    // stand on one of the surfaces the tool names, within that face's own stated slack.
                    var scale = 1.0
                    for (v in b.mesh.vertices) scale = max(scale, max(abs(v.x), max(abs(v.y), abs(v.z))))
                    val tol = max(1e-6, 64.0 * 1.1920929e-7 * scale)
                    for (tri in b.mesh.triangles) {
                        val vs = listOf(b.mesh.vertices[tri.a], b.mesh.vertices[tri.b], b.mesh.vertices[tri.c])
                        val on =
                            prov.faces.any { p ->
                                (p.plane != null || p.surface != null || p.pipe != null || p.strip != null) &&
                                    vs.all { abs(BoolFace3.offSurface(p, it)) <= tol + p.slack }
                            }
                        if (!on) t.strayTriangles++
                    }
                }
            }
        }
        try {
            for ((a, b) in pairs) {
                for (ka in kinds) {
                    for (kb in kinds) {
                        for (route in listOf(Route.ONE_PASS, Route.STACKED)) {
                            val (refs, _) = L.run(listOf(Rounding(a, ka, 4.0), Rounding(b, kb, 4.0)), route, together = false)
                            refs?.forEach { Evaluator().eval(it.node) }
                        }
                    }
                }
            }
        } finally {
            Geom3.combined = null
        }
        return t
    }

    @Test
    fun everyToolOfTheMatrixNamesItsOwnFaces() {
        val t = sweep(L.block.pairs.map { (a, b, _) -> a to b })
        println("== tool provenance: ${t.tools} tools handed over, ${t.without} without a carrier, ${t.strayTriangles} triangles on none of the faces their tool names")
        for ((k, n) in t.why.entries.sortedByDescending { it.value }) println("   $n × $k")
        assertTrue(t.tools > 0, "the matrix hands over tools at all")
        assertEquals(0, t.without, "the tools that state no carrier")
        assertEquals(0, t.strayTriangles, "the triangles standing on no face their own tool names")
    }

    @Test
    fun theSameHoldsUnderTheFromSourceEngine() {
        assumeTrue(MeshBool.isNative, "not the from-source engine: ${MeshBool.status}")
        val t = sweep(L.block.pairs.map { (a, b, _) -> a to b })
        println("== tool provenance (${MeshBool.status}): ${t.tools} tools, ${t.without} without a carrier, ${t.strayTriangles} stray triangles")
        assertEquals(0, t.without, "the tools that state no carrier under ${MeshBool.status}")
        assertEquals(0, t.strayTriangles, "the triangles standing on no face their own tool names under ${MeshBool.status}")
    }

    /**
     * **The tool's band and the body's band are one surface** (OP-31, slice 5w, item (b)) — asked of the
     * two statements rather than of the two meshes.
     *
     * The whole design of this slice in one assertion: a tool is swept from the *same* surfaces the body's
     * dressed faces are derived from, on the tool's side of the skin — and the blend's own curve is the one
     * part of the section the step-off leaves alone ([sectionOf] steps the two legs and nothing else). So
     * the cylinder the tool states along a straight run is the very cylinder the dressed body's band face
     * states: same axis, same radius, to the last bits. Where a boolean later trims that band — a bore
     * through a dressed body — the result's triangles there can therefore be traced to either operand's
     * carrier and get the same surface, which is what makes the trace single-valued.
     */
    @Test
    fun theToolsOwnBandIsTheBandTheBodyKeeps() {
        val cylinders = ArrayList<Surface3>()
        Geom3.combined = { _, _, b ->
            val f = b.feature
            if (f is Feature3.MeshBoolean) {
                f.provenance?.faces?.forEach { p ->
                    val s = p.surface
                    if (s != null && s.band is Revolve3.Band.Cylinder) cylinders.add(s)
                }
            }
        }
        val (refs, why) =
            try {
                L.run(listOf(Rounding(0, BlendKind.FILLET, 4.0)), Route.ONE_PASS, together = false)
            } finally {
                Geom3.combined = null
            }
        assertTrue(refs != null, "the rounding builds: $why")
        assertTrue(cylinders.isNotEmpty(), "the tool states a cylinder of its own")
        val body = Evaluator().solid(refs!!.first())
        val faces = Section3.faces(body.feature).first
        assertTrue(faces != null, "the dressed body names its faces")
        val band = faces!!.first { it.name is FaceName.BlendBand }.surface
        assertTrue(band != null, "the dressed body's band states its cylinder")
        val r = (band!!.band as Revolve3.Band.Cylinder).r
        val agreed =
            cylinders.filter { s ->
                val b2 = s.band as Revolve3.Band.Cylinder
                abs(b2.r - r) <= 1e-9 &&
                    abs(abs(s.axis.dot(band.axis)) - 1.0) <= 1e-9 &&
                    (s.origin - band.origin).let { d -> (d - band.axis * d.dot(band.axis)).length() } <= 1e-9
            }
        assertTrue(agreed.isNotEmpty(), "the tool's cylinder is the body's: r = $r, tool radii = ${cylinders.map { (it.band as Revolve3.Band.Cylinder).r }}")
    }
}
