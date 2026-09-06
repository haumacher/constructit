package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Loop
import constructit.geom.ProfileElement
import constructit.geom.Section3
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of OP-31 slice 5d** (the drawing's composition gaps): the outline a face space on a
 * *base* face of a dressed body now carries — the top face of the reporter's L-block with one top edge rounded
 * loses a 4 mm strip, the side face the band ends in loses the ball's wedge — and a boss extruded from the top
 * face's space is that trimmed outline carried out of the face.
 */
class BandOutlineCompositionProbeTest {
    private val script = """constructit 7
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
tool filletedge els=e13 clicks=-31.252365457721638,11.31587462139538 scalar="r" signs=13;-1;1;0;1 -> e14,e15
"""

    private fun area(outline: List<ProfileElement>) = abs(GeomMath.signedArea(Loop(outline)))

    @Test
    fun aBaseFaceSpaceCarriesTheTrimmedOutlineAndABossIsThatOutlineCarriedOut() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(script))
        val el = ed.doc.elements.last { it.kind == ElementKind.SOLID }

        @Suppress("UNCHECKED_CAST")
        val ref = el.ref as SolidRef
        val solid = Evaluator().solid(ref)
        val plan = 40611.44527914345 / 20.0
        // the top face: the base address whose plane faces +z at z = 20
        var top = -1
        var side = -1
        for (i in 0 until 8) {
            val p = Section3.facePatchOfFootprintPiece(solid.feature, i).first ?: continue
            val pl = p.plane ?: continue
            if (abs(pl.normal.z - 1.0) < 1e-9) top = i
            if (abs(pl.normal.y + 1.0) < 1e-9 && abs(pl.distanceTo(constructit.geom.Vec3(0.0, -32.375, 10.0))) < 1e-9) side = i
        }
        assertTrue(top >= 0 && side >= 0, "the top face and the side face y = -32.375 have base addresses ($top, $side)")
        val topPatch = assertNotNull(Section3.facePatchOfFootprintPiece(solid.feature, top).first)
        assertClose(plan - 4.0 * 32.75, area(topPatch.outline), 1e-6, "the top face's space shows the 4 mm strip the band took")
        assertTrue(topPatch.outline.all { it is ProfileElement.Seg }, "…as straight pieces")
        val sidePatch = assertNotNull(Section3.facePatchOfFootprintPiece(solid.feature, side).first)
        val sideArea = area(sidePatch.outline)
        val w = raspWedgeArea(4.0)
        val full = (-5.521648428788623 + 26.875) * 20.0
        assertTrue(sideArea < full - w + 1e-6 && sideArea > full - w - 0.2, "the side face the band ends in loses the ball's wedge: $sideArea vs ${full - w}")
        assertTrue(sidePatch.outline.any { it !is ProfileElement.Seg }, "…as the notch's arc")
        // a boss from the top face's space: the trimmed outline carried 3 mm out of the face
        val cx = ed.doc.cx
        val fr = assertNotNull(topPatch.plane)
        val plane = cx.plane(fr.origin, fr.u, fr.v)
        val pts = topPatch.outline.map { GeomMath.startOf(it) }
        val nodes = pts.mapIndexed { k, q -> cx.freePoint("boss$k", q.x.mm, q.y.mm) }
        val loop = cx.loop(*nodes.indices.map { cx.segment(nodes[it], nodes[(it + 1) % nodes.size]) }.toTypedArray())
        val boss = cx.extrude(cx.sketchOn(plane, cx.region(loop)), cx.const(3.0.mm))
        val r = Evaluator().eval(boss.node)
        assertTrue(r !is EvalResult.Invalid, "the boss builds: ${(r as? EvalResult.Invalid)?.reason}")
        val mesh = Evaluator().solid(boss).mesh
        assertManifold(mesh, "boss on the rounded block's top face")
        assertClose((plan - 4.0 * 32.75) * 3.0, Geom3.volume(mesh), 1e-6 * plan * 3.0, "the boss is the trimmed top carried 3 mm")
        // and fused back onto the block it stands on, the union keeps every face named (item 4 + 5c) and is one body
        val fused = cx.union(ref, boss)
        val rf = Evaluator().eval(fused.node)
        assertTrue(rf !is EvalResult.Invalid, "the boss fuses onto its own body: ${(rf as? EvalResult.Invalid)?.reason}")
        val fusedMesh = Evaluator().solid(fused).mesh
        assertManifold(fusedMesh, "block with its boss")
        assertClose(Geom3.volume(solid.mesh) + (plan - 4.0 * 32.75) * 3.0, Geom3.volume(fusedMesh), 1e-6 * plan * 20.0, "the fused body is block plus boss")
    }
}
