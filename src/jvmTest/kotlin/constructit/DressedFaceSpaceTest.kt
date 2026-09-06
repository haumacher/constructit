package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.geom.BlendKind
import constructit.geom.FaceName
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Section3
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A face space on a dressing's own face** (OP-31, item 3b — the demand item 3b's brief made and its first
 * delivery left standing).
 *
 * *The address rule, and it is the shell's and the revolution's read once more.* A dressed body was
 * addressable *"exactly where its base was"* (session 71, slice 3) and no further, so a bevel's band and the
 * flat slide of a pivot — planes with exact outlines and no reason — could not carry a sketch. They can now,
 * by the extension [Section3.FACE_ADDRESS_CONVENTION] already makes twice: **base-then-added**, the added
 * ones in the dressed list's own order (bands, then corner patches), *"every one of which was a refusal
 * before, so no stored byte changes meaning"* (OP-18). It composes level by level for a chain, because a
 * dressed base's own count already includes what it added.
 */
class DressedFaceSpaceTest {
    private val L = LBlock()

    private fun bevelledPivot(): Pair<constructit.geom.Solid3, List<constructit.geom.FacePatch>> {
        val (stages, why) = L.run(listOf(2, 13, 14).map { Rounding(it, BlendKind.CHAMFER, 4.0) }, Route.ONE_PASS)
        val solid = Evaluator().solid(assertNotNull(stages, why).last())
        return solid to assertNotNull(Section3.faces(solid.feature).first, "it names its faces")
    }

    /** The area a closed outline encloses, by the shoelace over its own tessellation. */
    private fun areaOf(outline: List<constructit.geom.ProfileElement>): Double {
        val pts = outline.flatMap { GeomMath.tessellatePiece(it) }
        var a = 0.0
        for (i in pts.indices) {
            val p = pts[i]
            val q = pts[(i + 1) % pts.size]
            a += p.x * q.y - q.x * p.y
        }
        return abs(a) / 2.0
    }

    /**
     * **A bevel's band and a walk's slide take a space; a cone does not, and says so in its own words.**
     */
    @Test
    fun everyPlanarFaceOfTheDressingTakesASpaceAndEveryCurvedOneRefusesByName() {
        val (solid, faces) = bevelledPivot()
        var opened = 0
        var refused = 0
        for (i in faces.indices) {
            val f = faces[i]
            if (f.name !is FaceName.BlendBand && f.name !is FaceName.BlendCorner) continue
            val at = assertNotNull(Section3.addressOfFace(solid.feature, f.name), "${f.name.label.render()} has an address")
            val (patch, why) = Section3.facePatchOfFootprintPiece(solid.feature, at)
            if (f.plane != null && f.reason == null) {
                assertNotNull(patch, "${f.name.label.render()} is a plane, so a space opens on it: ${why?.render()}")
                assertTrue(patch.outline.isNotEmpty(), "…and its own outline as the section input")
                // the intrinsic rule: the first boundary piece runs from the origin along +x
                assertClose(GeomMath.startOf(patch.outline.first()).length(), 0.0, 1e-9, "the first piece starts at the origin")
                assertClose(GeomMath.startOf(patch.outline.first()).y, 0.0, 1e-9, "…on the x axis")
                assertTrue(GeomMath.endOf(patch.outline.first()).x > 0.0, "…running the +x way")
                assertClose(GeomMath.endOf(patch.outline.first()).y, 0.0, 1e-9, "…and staying on it")
                // the frame's normal still points out of the material
                val fr = assertNotNull(patch.plane, "…with a frame")
                val n = fr.normal.normalized()
                val o = fr.toWorld(GeomMath.startOf(patch.outline.first()))
                val inward = areaOf(patch.outline)
                assertTrue(inward > 0.0, "…and the outline encloses an area: $inward")
                assertTrue(Geom3.encloses(solid.mesh, o - n * 0.05 + fr.u * 0.5 + fr.v * 0.5), "the normal points out of the material")
                opened++
            } else {
                assertTrue(patch == null, "${f.name.label.render()} is not a plane, so no space opens on it")
                val words = assertNotNull(why, "…and the refusal speaks").render()
                assertTrue(words.isNotBlank(), "…in words")
                assertTrue(f.name.label.render().split(",").first() in words || "plane" in words, "…naming the face: '$words'")
                refused++
            }
        }
        assertEquals(4, opened, "three bevel bands and the walk's own slide")
        assertEquals(2, refused, "the walk's two turning legs are cones")
        println("dressed face spaces: $opened opened, $refused refused by name")
    }

    /**
     * **A boss extruded from a band's own space** is the band's outline carried out of the face: its volume is
     * that outline's area times the depth, to the boolean's own float noise.
     */
    @Test
    fun aBossOnABandsSpaceIsItsOutlineCarriedOutOfTheFace() {
        val (solid, faces) = bevelledPivot()
        val at =
            faces.indices.first { faces[it].name == FaceName.BlendBand(13, 0) }
                .let { assertNotNull(Section3.addressOfFace(solid.feature, faces[it].name), "the band has an address") }
        val patch = assertNotNull(Section3.facePatchOfFootprintPiece(solid.feature, at).first, "the band takes a space")
        val area = areaOf(patch.outline)
        // the band of a 4 mm bevel on a 32.75 mm edge, ended by the pivot at 28.75: its own rectangle
        assertClose(area, 4.0 * kotlin.math.sqrt(2.0) * 28.75, 1e-6, "the band's outline is its own rectangle")
        val cx = L.cx
        val depth = 3.0
        val fr = assertNotNull(patch.plane, "the band's own frame")
        val plane = cx.plane(fr.origin, fr.u, fr.v)
        val pts = patch.outline.map { GeomMath.startOf(it) }
        val nodes = pts.mapIndexed { k, q -> cx.freePoint("boss$k", q.x.mm, q.y.mm) }
        val loop = cx.loop(*nodes.indices.map { cx.segment(nodes[it], nodes[(it + 1) % nodes.size]) }.toTypedArray())
        val boss = cx.extrude(cx.sketchOn(plane, cx.region(loop)), cx.const(depth.mm))
        val r = Evaluator().eval(boss.node)
        assertTrue(r !is EvalResult.Invalid, "the boss builds: ${(r as? EvalResult.Invalid)?.reason}")
        val mesh = Evaluator().solid(boss).mesh
        assertManifold(mesh, "a boss on the band's own space")
        assertClose(Geom3.volume(mesh), area * depth, 1e-6 * area * depth, "the boss is the outline carried $depth mm")
    }

    /**
     * **A base face's address does not move**, and the file that records one is a fixed point — the whole
     * point of putting the dressing's own faces *past* the base's own count (OP-18).
     */
    @Test
    fun aBaseFacesAddressIsUnchangedAndTheFileIsAFixedPoint() {
        val (solid, faces) = bevelledPivot()
        for (i in 0 until Section3.faceAddressCount(L.block.solid.feature)) {
            val below = Section3.facePatchOfFootprintPiece(L.block.solid.feature, i)
            val above = Section3.facePatchOfFootprintPiece(solid.feature, i)
            assertEquals(below.first?.name, above.first?.name, "base address #$i still names the same face")
            if (below.first != null && above.first != null) {
                assertEquals(below.first!!.plane, above.first!!.plane, "…in the same frame")
            }
        }
        val script =
            SCRIPT + "sketchspace \"f\" el=e14 piece=${
                assertNotNull(Section3.addressOfFace(solid.feature, FaceName.BlendBand(13, 0)), "the band has an address")
            }\n"
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(script))
        val once = DocumentFormat.save(ed.doc)
        val twice = DocumentFormat.save(DocumentFormat.load(once))
        assertEquals(once, twice, "a file with a space on a band is a fixed point:\n$once")
        assertTrue(ed.doc.spaces.any { it.name == "f" }, "…and the space is there: ${ed.doc.spaces.map { it.name }}")
    }

    /** **A 3D click on a band produces that very address**, so what the file records is what the click meant. */
    @Test
    fun aClickOnABandRecordsTheAddressThatOpensTheSpace() {
        val (solid, faces) = bevelledPivot()
        val band = faces.first { it.name == FaceName.BlendBand(13, 0) }
        val plane = assertNotNull(band.plane, "a bevel's band is a plane")
        val mid = plane.toWorld(band.outline.flatMap { GeomMath.tessellatePiece(it) }.fold(constructit.geom.Vec2(0.0, 0.0)) { a, q -> a + q } * (1.0 / band.outline.flatMap { GeomMath.tessellatePiece(it) }.size))
        val n = plane.normal.normalized()
        val (pick, why) = Section3.faceAt(solid.feature, mid + n * 1e-6, n * -1.0, 1e-3)
        assertNotNull(pick, "a ray onto the band hits it: ${why?.render()}")
        assertEquals(band.name, pick.patch.name, "…and it is the band")
        assertEquals(Section3.addressOfFace(solid.feature, band.name), pick.piece, "…recorded at the address a space opens on")
        assertNotNull(Section3.facePatchOfFootprintPiece(solid.feature, assertNotNull(pick.piece, "the pick records an address")).first, "…which opens")
    }

    companion object {
        private val SCRIPT =
            """constructit 7
orthostart -26.875,-32.375 -> e1
orthovertex -26.875,15.375 -> e2,e3
orthovertex 61.875,15.375 -> e4,e5
orthovertex 61.875,0.375 -> e6,e7
orthovertex -5.521648428788623,0.375 -> e8,e9
orthovertex -5.521648428788623,-32.375 -> e10,e11
orthoclose -> e12
param "h" = 20mm
tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
param "r" = 4mm
tool chamferedge els=e13 clicks=-12.581664043342087,5.018353790754986 scalar="r" signs=2;-1;1;0;-1 -> e14,e15
tool chamferedge els=e14 clicks=-36.10499303384047,0.8875707537249014 scalar="r" signs=13;-1;1;0;1 -> e16
tool chamferedge els=e14 clicks=-6.480180051294639,24.048959979858537 scalar="r" signs=14;-1;1;0;1 -> e17
"""
    }
}
