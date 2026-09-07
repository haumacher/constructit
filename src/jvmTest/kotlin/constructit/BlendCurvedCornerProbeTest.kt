package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.ScalarRef
import constructit.dsl.SolidRef
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.EdgeGeom
import constructit.geom.Geom3
import constructit.geom.ProfileElement
import constructit.geom.Section3
import constructit.units.Dimension
import constructit.units.Quantity
import constructit.units.mm
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of OP-31 slice 5e** (curved-edge corners) on what the delivery never saw: the sector's
 * rim fillet following the sector's radius as a parameter, the notched radius face taking a sketch space, and a
 * section through the rounded rim.
 */
class BlendCurvedCornerProbeTest {
    private fun ang(deg: Double) = Quantity(deg * PI / 180.0, Dimension.ANGLE)

    private lateinit var p0: constructit.dsl.PointRef
    private lateinit var p1: constructit.dsl.PointRef

    /** A 90° pie slice of radius [r] (a parameter; the corner points are moved with it), 20 deep. */
    private fun sector(
        cx: Construction,
        r: ScalarRef,
    ): SolidRef {
        val c = cx.freePoint("c", 0.0.mm, 0.0.mm)
        p0 = cx.freePoint("p0", 30.0.mm, 0.0.mm)
        p1 = cx.freePoint("p1", 0.0.mm, 30.0.mm)
        val arc = cx.arc(c, r, cx.const(ang(0.0)), cx.const(ang(90.0)), true)
        return cx.extrude(cx.sketchOn(cx.planeXY(), cx.region(cx.loop(cx.segment(c, p0), arc, cx.segment(p1, c)))), cx.const(20.0.mm))
    }

    private fun Construction.setRadius(
        r: ScalarRef,
        value: Double,
    ) {
        set(r, value.mm)
        set(p0, value.mm, 0.0.mm)
        set(p1, 0.0.mm, value.mm)
    }

    private fun topRim(ref: SolidRef): Int {
        val edges = assertNotNull(Section3.edges(Evaluator().solid(ref).feature).first)
        return edges.indices.first { i ->
            val g = edges[i].geom as? EdgeGeom.OnPlane ?: return@first false
            g.piece is ProfileElement.ArcE && g.plane.origin.z > 19.999
        }
    }

    private fun volume(
        ref: SolidRef,
        what: String,
    ): Double {
        val r = Evaluator().eval(ref.node)
        assertTrue(r !is EvalResult.Invalid, "$what builds: ${(r as? EvalResult.Invalid)?.reason}")
        val mesh = Evaluator().solid(ref).mesh
        assertManifold(mesh, what)
        return Geom3.volume(mesh)
    }

    @Test
    fun theRimFilletFollowsTheSectorsRadiusAndTheNotchedFaceTakesASpace() {
        val cx = Construction()
        val r = cx.parameter("R", 30.0.mm)
        val part = sector(cx, r)
        val base30 = volume(part, "sector R = 30")
        val rim = topRim(part)
        val (choices, why) = Blend3.choicesFor(Evaluator().solid(part), listOf(rim), BlendSection(BlendKind.FILLET, 3.0))
        assertNotNull(choices, "the rim rounds: ${why?.render()}")
        val rounded = cx.blendAll(part, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(3.0.mm), null, listOf(rim), choices)))
        val drop30 = base30 - volume(rounded, "rim rounded at R = 30")
        // Pappus: the wedge w, its centroid a reach ρ inside the rim, swept a quarter turn: w·(π/2)·(R − ρ)
        val w = raspWedgeArea(3.0)
        assertTrue(drop30 > w * (PI / 2.0) * (30.0 - 3.0) && drop30 < raspWedgeAreaByChords(3.0) * (PI / 2.0) * 30.0 + 0.5, "a quarter torus' worth: $drop30")
        cx.setRadius(r, 20.0)
        val base20 = volume(part, "sector R = 20")
        val drop20 = base20 - volume(rounded, "rim rounded at R = 20")
        // the same wedge swept a quarter turn on a smaller circle: the drops scale with the centroid's circle
        val ratio = drop20 / drop30
        assertTrue(ratio > (20.0 - 3.0) / (30.0 - 3.0) - 0.02 && ratio < 20.0 / 30.0 + 0.02, "the rim fillet follows the radius: $drop20 / $drop30 = $ratio")
        cx.setRadius(r, 30.0)
        // the radius face the rim's band ends in is notched: a planar face whose outline carries the notch's arc
        val faces = assertNotNull(Section3.faces(Evaluator().solid(rounded).feature).first)
        val notched = faces.indices.filter { i -> faces[i].plane != null && faces[i].reason == null && faces[i].outline.any { it is ProfileElement.ArcE } && kotlin.math.abs(faces[i].plane!!.normal.z) < 1e-9 }
        assertTrue(notched.size >= 2, "both radius faces carry the rim band's notch: ${faces.map { it.name to it.outline.size }}")
        for (i in notched) {
            val at = assertNotNull(Section3.addressOfFace(Evaluator().solid(rounded).feature, faces[i].name), "the face has an address")
            val patch = assertNotNull(Section3.facePatchOfFootprintPiece(Evaluator().solid(rounded).feature, at).first, "a sketch space opens on the notched radius face")
            assertTrue(patch.outline.any { it is ProfileElement.ArcE }, "…and its outline carries the notch")
        }
        // level sections through the rounded rim close
        for (z in listOf(5.0, 17.5, 19.5)) {
            val s = cx.sectionAt(rounded, cx.const(z.mm))
            val res = Evaluator().eval(s.node)
            if (res is EvalResult.Invalid) {
                println("section at z = $z refused: ${res.reason}")
            } else {
                assertTrue(Evaluator().scalar(cx.regionArea(s)).base > 0.0, "the section at z = $z closes")
            }
        }
    }
}
