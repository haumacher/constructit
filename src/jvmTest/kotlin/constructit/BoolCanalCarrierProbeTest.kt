package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PlaneValue
import constructit.dsl.Construction
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.EdgeGeom
import constructit.geom.EdgeName
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.MeshBool
import constructit.geom.Plane3
import constructit.geom.Region
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Adversarial probe of slice 5l** (OP-31), written after the delivery and never seen by it: the canal body
 * as the **file** `BlendCanalTest` writes, a hole cut into its side **through the editor** (a face space on
 * the block's wall, a circle, `Cut`) — the route a user takes — and then everything a drawing owes a bored
 * body: the faces named with the canal band among them, a level section below the bore exact, a level
 * section through the bands **unchanged** by a bore that never reaches them, the file a fixed point, and
 * the reloaded body the same body.
 */
class BoolCanalCarrierProbeTest {
    private val width = 40.0
    private val depth = 30.0
    private val height = 20.0

    private fun requireEngine() = assumeTrue(MeshBool.available, "no general boolean engine: ${MeshBool.status}")

    private fun edgesOf(s: Solid3) = assertNotNull(Section3.edges(s.feature).first, "it names its edges")

    private fun round(
        cx: Construction,
        on: SolidRef,
        address: Int,
        size: Double,
    ): SolidRef {
        val (choices, why) = Blend3.choicesFor(Evaluator().solid(on), listOf(address), BlendSection(BlendKind.FILLET, size))
        return cx.blendAll(on, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(size.mm), null, listOf(address), assertNotNull(choices, "edge $address is scored: ${why?.render()}"))))
    }

    /** `BlendCanalTest.blockScript`, rebuilt here: the block, its two far top edges rounded at 4, the mitre at 1. */
    private fun canalScript(): String {
        val cx = Construction()
        val plan = listOf(Vec2(0.0, 0.0), Vec2(width, 0.0), Vec2(width, depth), Vec2(0.0, depth))
        val pts = plan.mapIndexed { i, p -> cx.freePoint("p$i", p.x.mm, p.y.mm) }
        val box = cx.extrude(cx.sketchOn(cx.planeXY(), cx.region(cx.loop(*plan.indices.map { cx.segment(pts[it], pts[(it + 1) % plan.size]) }.toTypedArray()))), cx.const(height.mm))
        val corner = Vec3(width, depth, height)
        val es = edgesOf(Evaluator().solid(box))
        val tops =
            es.indices.filter { i ->
                val path = Blend3.edgePath(es[i]).first ?: return@filter false
                val a = path.start ?: return@filter false
                val b = path.end ?: return@filter false
                abs(a.z - height) < 1e-9 && abs(b.z - height) < 1e-9 && ((a - corner).length() < 1e-9 || (b - corner).length() < 1e-9)
            }
        assertEquals(2, tops.size, "two top edges share the far corner")
        val sb = StringBuilder("constructit ${DocumentFormat.VERSION}\n")
        sb.append("orthostart ${plan[0].x},${plan[0].y} -> e1\n")
        var n = 2
        for (i in 1 until plan.size) {
            sb.append("orthovertex ${plan[i].x},${plan[i].y} -> e$n,e${n + 1}\n")
            n += 2
        }
        sb.append("orthoclose -> e$n\n")
        n++
        sb.append("param \"h\" = ${height}mm\n")
        sb.append("tool extrude els=e${n - 2} clicks=-10,-10 scalar=\"h\" -> e$n\n")
        var at = n
        var on = box
        for ((k, e) in tops.withIndex()) {
            val choice = assertNotNull(Blend3.choicesFor(Evaluator().solid(on), listOf(e), BlendSection(BlendKind.FILLET, 4.0)).first, "top edge $e is scored")[0]
            sb.append("param \"r$k\" = 4mm\n")
            sb.append("tool filletedge els=e$at clicks=0,0 scalar=\"r$k\" signs=$e;${choice.signs().joinToString(";")} -> e${at + 1},e${at + 2}\n")
            on = round(cx, on, e, 4.0)
            at += 1
        }
        val es2 = edgesOf(Evaluator().solid(on))
        val mitre = assertNotNull(es2.indices.firstOrNull { es2[it].name is EdgeName.BlendMitre && es2[it].reason == null && es2[it].geom is EdgeGeom.OnPlane }, "the two bands cross in a mitre")
        val choice = assertNotNull(Blend3.choicesFor(Evaluator().solid(on), listOf(mitre), BlendSection(BlendKind.FILLET, 1.0)).first, "the mitre is scored")[0]
        sb.append("param \"rc\" = 1mm\n")
        sb.append("tool filletedge els=e$at clicks=0,0 scalar=\"rc\" signs=$mitre;${choice.signs().joinToString(";")} -> e${at + 1},e${at + 2}\n")
        return sb.toString()
    }

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    @Suppress("UNCHECKED_CAST")
    private fun bodyOf(ed: Editor): Solid3 = Evaluator().solid(ed.doc.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef)

    private fun regionArea(regions: List<Region>): Double =
        regions.sumOf { r -> abs(GeomMath.signedArea(r.outer)) - r.holes.sumOf { abs(GeomMath.signedArea(it)) } }

    private fun level(
        s: Solid3,
        z: Double,
        what: String,
    ): Double {
        val (regions, why) = Section3.regionsOf(s.feature, Plane3(Vec3(0.0, 0.0, z), Vec3.X, Vec3.Y))
        return regionArea(assertNotNull(regions, "$what: the level section at z = $z closes: ${why?.render()}"))
    }

    @Test
    fun aHoleCutIntoTheCanalBodysWallThroughTheEditorIsNamedSectionedAndAFixedPoint() {
        requireEngine()
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(canalScript()))
        val canal = bodyOf(ed)
        assertManifold(canal.mesh, "the canal body from the file")
        val throughBands = level(canal, 17.5, "the unbored canal")
        assertTrue(throughBands < width * depth, "at z = 17.5 the two bands and the canal have taken material: $throughBands")

        // a face space on the block's wall x = 40 — a plan click on that edge, as a user opens it
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(width, 15.0))
        assertTrue(!ed.activeSpace.isPlan, "the wall opened as a space: ${ed.statusHint}")
        val plane = ((Evaluator().eval(assertNotNull(ed.activeSpace.plane, "the space has a plane").node) as EvalResult.Ok).value as PlaneValue).plane
        assertClose(abs(plane.normal.normalized().x), 1.0, 1e-9, "…and it is the x = 40 wall: ${plane.normal}")
        // the hole's centre, well below the roundings: world (40, 15, 8), in the wall's own coordinates
        val centre = Vec3(width, 15.0, 8.0)
        val local = Vec2((centre - plane.origin).dot(plane.u.normalized()), (centre - plane.origin).dot(plane.v.normalized()))
        ed.setTool(Tools.CIRCLE_R)
        ed.type("3")
        ed.click(local)
        val solidsBefore = ed.doc.elements.count { it.kind == ElementKind.SOLID }
        ed.setTool(Tools.CUT)
        ed.type("6")
        // a circle is clicked on its **outline** — its centre is a point, and the point would win (OP-16)
        ed.click(local + Vec2(3.0, 0.0))
        assertTrue(ed.doc.elements.count { it.kind == ElementKind.SOLID } > solidsBefore, "the cut is a new body: ${ed.statusHint}")
        assertTrue("cut 6 mm into" in ed.statusHint, "…cut into the drawing's tip, the canal body (OP-17): ${ed.statusHint}")
        val bored = bodyOf(ed)
        val r = Evaluator().eval(ed.doc.elements.last { it.kind == ElementKind.SOLID }.ref.node)
        assertTrue(r !is EvalResult.Invalid, "the cut builds: ${(r as? EvalResult.Invalid)?.why?.render()} / ${ed.statusHint}")
        assertManifold(bored.mesh, "the canal body with a hole in its wall")
        val taken = Geom3.volume(canal.mesh) - Geom3.volume(bored.mesh)
        val hole = Math.PI * 9.0 * 6.0
        assertTrue(taken > 0.95 * hole && taken <= hole + 1e-6, "the hole takes its own cylinder, less its chords: $taken vs $hole")

        // named, with the canal band among the faces
        val (faces, why) = Section3.faces(bored.feature)
        val fs = assertNotNull(faces, "the bored canal body names its faces: ${why?.render()}")
        val report = fs.map { "${it.name.label.render()} | pipe=${it.pipe != null} surface=${it.surface != null} plane=${it.plane != null} reason=${it.reason?.render()} outline=${it.outline.size}" }
        // a curved face carries its sketch-space refusal as its reason (it is no plane), so what says it is a
        // face of the body is its carrier and a stated trim, never a null reason
        assertTrue(fs.any { it.pipe != null && it.outline.isNotEmpty() }, "the canal band is a named face of the bored body:\n  ${report.joinToString("\n  ")}")
        assertTrue(fs.any { it.surface != null && it.outline.isNotEmpty() && "second operand" in it.name.label.render() }, "…and so is the bore's cylinder")
        // a flat-end slot the operand itself left empty — the end a mitre or a corner owns — comes through in
        // the operand's own words, never as a face "the boolean took away": there was none to take
        val tombstones = fs.filter { it.outline.isEmpty() && it.plane == null && "flat end" in it.name.label.render() && "first operand" in it.name.label.render() }
        assertTrue(tombstones.isNotEmpty(), "the operand's empty flat-end slots keep their places in the result")
        for (t in tombstones) {
            val words = assertNotNull(t.reason, "${t.name.label.render()} says why").render()
            assertTrue("took this face" !in words, "…in the operand's own words, not as a removal: $words")
            assertTrue("has no such face itself" in words && ("corner" in words || "notch" in words || "mitre" in words || "face" in words), "…naming what owns that end: $words")
        }
        // sections: below the hole exact, through the hole's axis a rectangle wide, through the bands untouched
        assertClose(level(bored, 3.0, "the bored body"), width * depth, 1e-6, "below the hole the block is whole")
        val atAxis = level(bored, 8.0, "the bored body")
        assertTrue(atAxis < width * depth - 30.0 && atAxis > width * depth - 36.0 - 1e-6, "through the hole's axis the section loses a 6 × 6 rectangle, less the chords: ${width * depth - atAxis}")
        // a bore that never reaches the bands leaves their section as it was — to the **fitted** tier's own
        // tolerance, not to a bit: the canal's level trace is a sampled curve in both readers (the dressing's
        // own and the boolean's), and two samplings of one curve agree to the chord tolerance over the trace's
        // length, 0.02 mm over at most 10 mm of canal here. The two bands' traces are rulings and exact in both.
        val bands17 = level(bored, 17.5, "the bored body")
        assertTrue(abs(bands17 - throughBands) <= 0.2, "a bore that never reaches the bands leaves their section as it was, to the chord tolerance: $bands17 vs $throughBands")

        // the file
        val once = DocumentFormat.save(ed.doc)
        val back = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(back), "save → load → save is byte-equal:\n$once")
        val ed2 = Editor()
        ed2.replaceDocument(back)
        val again = bodyOf(ed2)
        assertManifold(again.mesh, "the reloaded body")
        val v = Geom3.volume(bored.mesh)
        assertClose(Geom3.volume(again.mesh), v, 1e-7 * v, "the reloaded body is the same body, to the float32 snap")
        assertNotNull(Section3.faces(again.feature).first, "…and it names its faces too: ${Section3.faces(again.feature).second?.render()}")
        assertClose(level(again, 17.5, "the reloaded body"), bands17, 1e-9, "…with the bands' section as this body reads it")
    }
}
