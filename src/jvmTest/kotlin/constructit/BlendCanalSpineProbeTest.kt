package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.EdgeGeom
import constructit.geom.EdgeName
import constructit.geom.Geom3
import constructit.geom.Plane3
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Adversarial probe of slice 5m** (OP-31), written after the delivery and never seen by it. The marched
 * spine is a construction, so the body has to be a pure function of its parameters *along a live edit*: the
 * canal's radius typed from 1 to the tight 2.5 in the panel gives the very body a fresh build at 2.5 gives,
 * back to 1 gives the first body back, and undo walks the same two steps. And the spine has to march a
 * crease it was not tuned on — the fitted quartic between two rounds of **unlike** size — at the tight size
 * too: built inside its own figure, or refused in a sentence of the drawing's own.
 */
class BlendCanalSpineProbeTest {
    private val width = 40.0
    private val depth = 30.0
    private val height = 20.0

    private fun edgesOf(s: Solid3) = assertNotNull(Section3.edges(s.feature).first, "it names its edges")

    private fun prism(cx: Construction): SolidRef {
        val plan = listOf(Vec2(0.0, 0.0), Vec2(width, 0.0), Vec2(width, depth), Vec2(0.0, depth))
        val pts = plan.mapIndexed { i, p -> cx.freePoint("p$i", p.x.mm, p.y.mm) }
        return cx.extrude(cx.sketchOn(cx.planeXY(), cx.region(cx.loop(*plan.indices.map { cx.segment(pts[it], pts[(it + 1) % plan.size]) }.toTypedArray()))), cx.const(height.mm))
    }

    /** The two top edges that share the block's far corner, in edge-list order. */
    private fun topsAtFarCorner(s: Solid3): List<Int> {
        val corner = Vec3(width, depth, height)
        val es = edgesOf(s)
        return es.indices.filter { i ->
            val path = Blend3.edgePath(es[i]).first ?: return@filter false
            val a = path.start ?: return@filter false
            val b = path.end ?: return@filter false
            abs(a.z - height) < 1e-9 && abs(b.z - height) < 1e-9 && ((a - corner).length() < 1e-9 || (b - corner).length() < 1e-9)
        }
    }

    private fun round(
        cx: Construction,
        on: SolidRef,
        address: Int,
        size: Double,
    ): Pair<SolidRef?, String?> {
        val (choices, why) = Blend3.choicesFor(Evaluator().solid(on), listOf(address), BlendSection(BlendKind.FILLET, size))
        if (choices == null) return null to (why?.render() ?: "no choice")
        val ref = cx.blendAll(on, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(size.mm), null, listOf(address), choices)))
        val r = Evaluator().eval(ref.node)
        if (r is EvalResult.Invalid) return null to r.why.render()
        return ref to null
    }

    private fun mitreOf(s: Solid3): Int? {
        val es = edgesOf(s)
        return es.indices.firstOrNull { es[it].name is EdgeName.BlendMitre && es[it].reason == null }
    }

    /** The block with its two far top edges rounded one at a time at [r0] and [r1], and the mitre between them. */
    private fun twoRounds(
        cx: Construction,
        r0: Double,
        r1: Double,
    ): SolidRef {
        val box = prism(cx)
        val tops = topsAtFarCorner(Evaluator().solid(box))
        assertEquals(2, tops.size, "two top edges share the far corner")
        var on = box
        for ((k, e) in tops.withIndex()) {
            val (next, why) = round(cx, on, e, if (k == 0) r0 else r1)
            on = assertNotNull(next, "top edge $e rounds at ${if (k == 0) r0 else r1}: $why")
        }
        return on
    }

    /** `BlendCanalTest`'s file: the block, the two rounds at 4, the canal along their mitre at [rc]. */
    private fun canalScript(rc: Double): String {
        val cx = Construction()
        val box = prism(cx)
        val tops = topsAtFarCorner(Evaluator().solid(box))
        val plan = listOf(Vec2(0.0, 0.0), Vec2(width, 0.0), Vec2(width, depth), Vec2(0.0, depth))
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
            on = assertNotNull(round(cx, on, e, 4.0).first, "top edge $e rounds")
            at += 1
        }
        val mitre = assertNotNull(mitreOf(Evaluator().solid(on)), "the two bands cross in a mitre")
        val choice = assertNotNull(Blend3.choicesFor(Evaluator().solid(on), listOf(mitre), BlendSection(BlendKind.FILLET, rc)).first, "the mitre is scored")[0]
        sb.append("param \"rc\" = ${rc}mm\n")
        sb.append("tool filletedge els=e$at clicks=0,0 scalar=\"rc\" signs=$mitre;${choice.signs().joinToString(";")} -> e${at + 1},e${at + 2}\n")
        return sb.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun volumeOf(
        ed: Editor,
        what: String,
    ): Double {
        val ref = ed.doc.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef
        val r = Evaluator().eval(ref.node)
        assertTrue(r !is EvalResult.Invalid, "$what builds: ${(r as? EvalResult.Invalid)?.why?.render()} / ${ed.statusHint}")
        val mesh = Evaluator().solid(ref).mesh
        assertManifold(mesh, what)
        return Geom3.volume(mesh)
    }

    /** A fresh build of the canal at [rc], the DSL way — the reference a live edit has to reproduce. */
    private fun freshVolume(rc: Double): Double {
        val cx = Construction()
        val two = twoRounds(cx, 4.0, 4.0)
        val mitre = assertNotNull(mitreOf(Evaluator().solid(two)), "the mitre")
        val (ref, why) = round(cx, two, mitre, rc)
        val body = Evaluator().solid(assertNotNull(ref, "the canal at $rc builds fresh: $why"))
        assertManifold(body.mesh, "the canal at $rc, built fresh")
        return Geom3.volume(body.mesh)
    }

    /**
     * **A live edit of the canal's radius is the fresh body, both ways, and undo walks it back.** The file is
     * written at 1 mm; the panel takes it to the tight 2.5 (the size that refused before this slice) and back.
     */
    @Test
    fun theCanalsRadiusEditedLiveIsTheFreshBodyAndUndoWalksItBack() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(canalScript(1.0)))
        val atOne = volumeOf(ed, "the canal at 1 mm from the file")
        val freshOne = freshVolume(1.0)
        assertClose(atOne, freshOne, 1e-7 * freshOne, "the file's canal at 1 mm is the fresh one")
        val rc = assertNotNull(ed.doc.scalars.firstOrNull { it.name == "rc" }, "the canal's radius is a parameter: ${ed.doc.scalars.map { it.name }}")

        assertTrue(ed.setParameter(rc, 2.5), "the radius takes 2.5: ${ed.statusHint}")
        val atTight = volumeOf(ed, "the canal at 2.5 mm by a live edit")
        val freshTight = freshVolume(2.5)
        assertClose(atTight, freshTight, 1e-7 * freshTight, "the live edit to 2.5 is the body a fresh build at 2.5 gives")
        assertTrue(atTight < atOne, "the larger ball takes more: $atTight vs $atOne")
        // the band the edit made is the pipe the drawing states, with a spine tolerance inside the chord tolerance
        @Suppress("UNCHECKED_CAST")
        val tight = Evaluator().solid(ed.doc.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef)
        val pipes = assertNotNull(Section3.faces(tight.feature).first, "it names its faces").filter { it.pipe != null }
        assertEquals(1, pipes.size, "one canal band")
        val (regions, why) = Section3.regionsOf(tight.feature, Plane3(Vec3(0.0, 0.0, 17.5), Vec3.X, Vec3.Y))
        assertNotNull(regions, "the level section through the tight canal closes: ${why?.render()}")

        val edited = ed.doc.scalars.first { it.name == "rc" }
        assertTrue(ed.setParameter(edited, 1.0), "…and back to 1: ${ed.statusHint}")
        assertClose(volumeOf(ed, "back at 1 mm"), atOne, 1e-7 * atOne, "back at 1 mm it is the first body again")

        assertTrue(ed.undo(), "undo the edit back to 1")
        assertClose(volumeOf(ed, "after one undo"), atTight, 1e-7 * atTight, "one undo is the tight body")
        assertTrue(ed.undo(), "undo the edit to 2.5")
        assertClose(volumeOf(ed, "after two undos"), atOne, 1e-7 * atOne, "two undos are the file's body")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the file after the round of edits is a fixed point")
    }

    /**
     * **The fitted quartic between two rounds of unlike size, at the tight size.** The spine marched off the
     * two offset cylinders does not know it is on an ellipse or a quartic; at every size it either builds
     * inside its own figure or refuses in the drawing's words — never in the engine's.
     */
    @Test
    fun theUnlikeRoundsMitreMarchesAtEverySizeOrRefusesInTheDrawingsWords() {
        var built = 0
        var refused = 0
        for (rc in listOf(0.5, 1.0, 1.5, 2.0, 2.5)) {
            val cx = Construction()
            val two = twoRounds(cx, 4.0, 3.0)
            val body = Evaluator().solid(two)
            assertManifold(body.mesh, "two unlike rounds crossing")
            val before = Geom3.volume(body.mesh)
            val es = edgesOf(body)
            val crease = es.indices.firstOrNull { es[it].reason == null && es[it].geom is EdgeGeom.InSpace && es[it].name is EdgeName.BlendMitre }
            if (crease == null) {
                println("unlike spine | rc=$rc | no stated crease between the two bands")
                return
            }
            val sec = BlendSection(BlendKind.FILLET, rc)
            val (choices, why) = Blend3.choicesFor(body, listOf(crease), sec)
            val (ref, whyBuild) = if (choices == null) null to why?.render() else round(cx, two, crease, rc)
            if (ref == null) {
                val words = assertNotNull(whyBuild, "a refusal has words")
                assertTrue("Manifold" !in words && "status" !in words && "closed shell" !in words, "rc=$rc: refused in the drawing's words, not the engine's: $words")
                assertTrue("mitre" in words || "crease" in words || "ball" in words || "rounding" in words, "rc=$rc: the refusal names the crease or the ball: $words")
                println("unlike spine | rc=$rc | refused | ${words.take(120)}")
                refused++
                continue
            }
            val rounded = Evaluator().solid(ref)
            assertManifold(rounded.mesh, "the unlike-size canal at $rc")
            val took = before - Geom3.volume(rounded.mesh)
            val (lo, hi) = assertNotNull(Blend3.canalRemoval(body.feature, crease, sec, assertNotNull(choices)[0]), "rc=$rc: the algebra states its figure")
            assertTrue(took in lo..hi, "rc=$rc: the canal takes $took, outside its own bracket [$lo, $hi]")
            // below both bands the block is whole and exact; through them the section closes **or says where it
            // breaks** — a band trimmed by the fitted quartic states its trim only as curves there, which is the
            // open ground slices 5l and 5m both record (a section through a band a fitted crease has bitten)
            val (below, wb) = Section3.regionsOf(rounded.feature, Plane3(Vec3(0.0, 0.0, 15.0), Vec3.X, Vec3.Y))
            assertClose(abs(constructit.geom.GeomMath.signedArea(assertNotNull(below, "rc=$rc: below the bands the section closes: ${wb?.render()}")[0].outer)), width * depth, 1e-9, "rc=$rc: below the bands the block is whole")
            val (regions, w) = Section3.regionsOf(rounded.feature, Plane3(Vec3(0.0, 0.0, 17.5), Vec3.X, Vec3.Y))
            val through = if (regions != null) "closes" else "breaks: ${assertNotNull(w, "a section that does not close says why").render().also { assertTrue("breaks at" in it, "…naming the face: $it") }.take(90)}"
            println("unlike spine | rc=$rc | built | took $took in [$lo, $hi] | level section through the bands $through")
            built++
        }
        println("unlike spine | $built built, $refused refused")
        assertTrue(built > 0, "the unlike-size mitre rounds at some size")
    }
}
