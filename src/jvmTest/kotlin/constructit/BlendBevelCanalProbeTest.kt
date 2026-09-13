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
 * **Adversarial probe of slice 5n** (OP-31), written after the delivery and never seen by it. A bevel along the
 * mitre between two rounds is a construction, so the body has to be a pure function of its parameters along a
 * **live edit** of the setback in the panel, and undo has to walk it back; the block's **top face** — a plane the
 * strip has bitten a fitted rail into — still has to state its outline and take a sketch space; and the strip has
 * to take **more** than the ball of the same size does at the same crease, because a chamfer's triangle contains
 * the fillet's segment.
 */
class BlendBevelCanalProbeTest {
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

    private fun blend(
        cx: Construction,
        on: SolidRef,
        address: Int,
        kind: BlendKind,
        size: Double,
    ): Pair<SolidRef?, String?> {
        val (choices, why) = Blend3.choicesFor(Evaluator().solid(on), listOf(address), BlendSection(kind, size))
        if (choices == null) return null to (why?.render() ?: "no choice")
        val ref = cx.blendAll(on, cx.planeXY(), listOf(Construction.BlendRun(kind, cx.const(size.mm), null, listOf(address), choices)))
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

    /** `BlendCanalTest`'s file with the last step a **chamfer**: the block, the two rounds at 4, the bevel along their mitre at [sc]. */
    private fun bevelScript(sc: Double): String {
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
        val choice = assertNotNull(Blend3.choicesFor(Evaluator().solid(on), listOf(mitre), BlendSection(BlendKind.CHAMFER, sc)).first, "the mitre is scored for a chamfer")[0]
        sb.append("param \"sc\" = ${sc}mm\n")
        sb.append("tool chamferedge els=e$at clicks=0,0 scalar=\"sc\" signs=$mitre;${choice.signs().joinToString(";")} -> e${at + 1},e${at + 2}\n")
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

    /** A fresh build of the mitre's dressing of [kind] at [size], the DSL way — the reference a live edit has to reproduce. */
    private fun freshVolume(
        kind: BlendKind,
        size: Double,
    ): Double {
        val cx = Construction()
        val two = twoRounds(cx, 4.0, 4.0)
        val mitre = assertNotNull(mitreOf(Evaluator().solid(two)), "the mitre")
        val (ref, why) = blend(cx, two, mitre, kind, size)
        val body = Evaluator().solid(assertNotNull(ref, "the $kind at $size builds fresh: $why"))
        assertManifold(body.mesh, "the $kind at $size, built fresh")
        return Geom3.volume(body.mesh)
    }

    /**
     * **A live edit of the bevel's setback is the fresh body, both ways, and undo walks it back.** The file is
     * written at 1 mm; the panel takes the setback to 2 and back.
     */
    @Test
    fun theBevelsSetbackEditedLiveIsTheFreshBodyAndUndoWalksItBack() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(bevelScript(1.0)))
        val atOne = volumeOf(ed, "the bevel at 1 mm from the file")
        val freshOne = freshVolume(BlendKind.CHAMFER, 1.0)
        assertClose(atOne, freshOne, 1e-7 * freshOne, "the file's bevel at 1 mm is the fresh one")
        // a chamfer's triangle contains the fillet's segment, so the strip takes more than the ball of one size
        val ballOne = freshVolume(BlendKind.FILLET, 1.0)
        assertTrue(atOne < ballOne, "the bevel at 1 takes more than the ball at 1: $atOne vs $ballOne")
        val sc = assertNotNull(ed.doc.scalars.firstOrNull { it.name == "sc" }, "the setback is a parameter: ${ed.doc.scalars.map { it.name }}")

        assertTrue(ed.setParameter(sc, 2.0), "the setback takes 2: ${ed.statusHint}")
        val atTwo = volumeOf(ed, "the bevel at 2 mm by a live edit")
        val freshTwo = freshVolume(BlendKind.CHAMFER, 2.0)
        assertClose(atTwo, freshTwo, 1e-7 * freshTwo, "the live edit to 2 is the body a fresh build at 2 gives")
        assertTrue(atTwo < atOne, "the larger setback takes more: $atTwo vs $atOne")

        @Suppress("UNCHECKED_CAST")
        val body = Evaluator().solid(ed.doc.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef)
        val faces = assertNotNull(Section3.faces(body.feature).first, "it names its faces")
        val strips = faces.filter { it.ruled }
        assertEquals(1, strips.size, "one ruled strip: ${faces.map { it.name.label.render() }}")
        // **the top face the strip bit into still states its outline and takes a sketch** — a plane whose
        // boundary now carries a fitted rail is still a plane with an outline (Tier B: fitted where it must be)
        val top = assertNotNull(faces.firstOrNull { f -> f.plane?.let { abs(it.normal.normalized().z - 1.0) < 1e-9 && abs(it.origin.z - height) < 1e-9 } == true }, "the block's top face is a plane of the body")
        assertTrue(top.reason == null, "…a sketch space opens on it: ${top.reason?.render()}")
        assertTrue(top.outline.isNotEmpty(), "…and it states its outline")
        val topArea = abs(constructit.geom.GeomMath.signedArea(constructit.geom.Loop(top.outline)))
        assertTrue(topArea < width * depth && topArea > 0.5 * width * depth, "the top's outline is the block's top less what the rounds and the strip took: $topArea")
        // the strip's two rails are in the edge list, fitted, with a stated tolerance
        val es = assertNotNull(Section3.edges(body.feature).first, "it names its edges")
        val rails = es.filter { it.reason == null && it.fitted != null && it.geom is EdgeGeom.InSpace && it.name.label.render().contains("#21") }
        assertTrue(rails.isNotEmpty(), "the strip's rails are fitted chains in the edge list: ${es.map { it.name.label.render() }.take(40)}")
        for (rail in rails) assertTrue(assertNotNull(rail.fitted) <= 0.02, "…each within the chord tolerance: ${rail.fitted}")
        // the level section through the strip closes and is smaller than the two-round body's there
        val (regions, why) = Section3.regionsOf(body.feature, Plane3(Vec3(0.0, 0.0, 18.0), Vec3.X, Vec3.Y))
        assertNotNull(regions, "the level section through the strip closes: ${why?.render()}")

        val edited = ed.doc.scalars.first { it.name == "sc" }
        assertTrue(ed.setParameter(edited, 1.0), "…and back to 1: ${ed.statusHint}")
        assertClose(volumeOf(ed, "back at 1 mm"), atOne, 1e-7 * atOne, "back at 1 mm it is the first body again")
        assertTrue(ed.undo(), "undo the edit back to 1")
        assertClose(volumeOf(ed, "after one undo"), atTwo, 1e-7 * atTwo, "one undo is the 2 mm body")
        assertTrue(ed.undo(), "undo the edit to 2")
        assertClose(volumeOf(ed, "after two undos"), atOne, 1e-7 * atOne, "two undos are the file's body")
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the file after the round of edits is a fixed point")
    }

    /**
     * **A bevel and a ball of every size at the same mitre**: the strip always takes more than the ball, and
     * where the ball fits the strip either fits too or refuses in the drawing's words.
     */
    @Test
    fun theStripTakesMoreThanTheBallAtEverySizeOrSaysWhyNot() {
        var compared = 0
        for (d in listOf(0.5, 1.0, 1.5, 2.0, 2.5)) {
            val cx = Construction()
            val two = twoRounds(cx, 4.0, 4.0)
            val body = Evaluator().solid(two)
            val mitre = assertNotNull(mitreOf(body), "the mitre")
            val before = Geom3.volume(body.mesh)
            val (ball, whyBall) = blend(cx, two, mitre, BlendKind.FILLET, d)
            val (strip, whyStrip) = blend(cx, two, mitre, BlendKind.CHAMFER, d)
            if (strip == null) {
                val words = assertNotNull(whyStrip)
                assertTrue("Manifold" !in words && "status" !in words && "closed shell" !in words, "d=$d: refused in the drawing's words: $words")
                println("bevel probe | d=$d | strip refused | ${words.take(110)}")
                continue
            }
            val stripBody = Evaluator().solid(strip)
            assertManifold(stripBody.mesh, "the strip at $d")
            val tookStrip = before - Geom3.volume(stripBody.mesh)
            if (ball != null) {
                val tookBall = before - Geom3.volume(Evaluator().solid(ball).mesh)
                assertTrue(tookStrip > tookBall, "d=$d: the strip takes more than the ball: $tookStrip vs $tookBall")
                println("bevel probe | d=$d | strip $tookStrip > ball $tookBall")
                compared++
            } else {
                println("bevel probe | d=$d | strip $tookStrip, ball refused: ${whyBall?.take(80)}")
            }
        }
        assertTrue(compared >= 3, "the strip and the ball were compared at most sizes: $compared")
    }
}
