package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.FaceName
import constructit.geom.Geom3
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Dimension
import constructit.units.Quantity
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Probe of slice 5h, the canal corner at a ring upright** (OP-31; GitHub #36) — through the **file** and the
 * editor, which the delivery asserted only structurally: a partial revolve drawn as a `.cit`, its cap's reflex
 * corner rounded by two recorded gestures, saved, loaded, deleted, undone, retyped, and composed with a boolean.
 * The body the file gives must be the body the DSL builds in one gesture.
 */
class BlendCornerCanalProbeTest {
    private val ring = 20.0
    private val inner = 10.0
    private var ids = 0

    /** The revolved L, its cap's reflex pair rounded at 2 mm in two recorded gestures — as a file. */
    private fun turnedScript(r: Double = 2.0): String {
        val sb = StringBuilder("constructit ${DocumentFormat.VERSION}\n")
        val prof = profile()
        sb.append("orthostart ${prof[0].x},${prof[0].y} -> e1\n")
        var n = 2
        for (i in 1 until prof.size) {
            sb.append("orthovertex ${prof[i].x},${prof[i].y} -> e$n,e${n + 1}\n")
            n += 2
        }
        val region = n - 1
        sb.append("orthoclose -> e$n\n")
        n++
        sb.append("point 0,0 -> e$n\n")
        sb.append("point 1,0 -> e${n + 1}\n")
        sb.append("tool line pts=e$n,e${n + 1} clicks=0,0;1,0 -> e${n + 2}\n")
        val axis = n + 2
        n += 3
        sb.append("param \"a\" = 90deg\n")
        sb.append("tool revolve els=e$region,e$axis clicks=5,20;50,0 scalar=\"a\" -> e$n\n")
        var at = n
        sb.append("param \"r\" = ${r}mm\n")
        for (k in 0 until 2) {
            val ed = Editor()
            ed.replaceDocument(DocumentFormat.load(sb.toString()))
            val body = Evaluator().solid(bodyOf(ed).ref as SolidRef)
            val pair = reflexPair(body)
            assertEquals(2 - k, pair.size, "the reflex pair still has ${2 - k} sharp edge(s) to round")
            val e = pair.first()
            val choice = assertNotNull(Blend3.choicesFor(body, listOf(e), BlendSection(BlendKind.FILLET, r)).first, "edge $e is scored")[0]
            sb.append("tool filletedge els=e$at clicks=0,0 scalar=\"r\" signs=$e;${choice.signs().joinToString(";")} -> e${at + 1},e${at + 2}\n")
            at += 1
        }
        return sb.toString()
    }

    /**
     * **The file is a fixed point and gives the DSL's own body.** Two recorded gestures of one size make the
     * pivot; the drawing saves → loads → saves byte-equal; the body is manifold, has exactly one corner face
     * that says what it is, and its volume is the one-gesture DSL body's to the engine's ULP.
     */
    @Test
    fun theRingPivotRoundTripsAndIsTheDslsOwnBody() {
        val script = turnedScript()
        val once = DocumentFormat.save(DocumentFormat.load(script))
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the drawing round-trips byte-equal")
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(script))
        val body = Evaluator().solid(bodyOf(ed).ref as SolidRef)
        assertManifold(body.mesh, "the ring pivot from the file")
        val corners = facesOf(body).filter { it.name is FaceName.BlendCorner }
        assertEquals(1, corners.size, "one corner face between the two bands")
        val reason = assertNotNull(corners[0].reason, "…which is no plane and says so").render()
        assertTrue(reason.contains("canal"), "…in the canal's own words: $reason")

        val cx = Construction()
        val turned = turned(cx)
        val solid = Evaluator().solid(turned)
        val pair = reflexPair(solid)
        assertEquals(2, pair.size)
        val sec = BlendSection(BlendKind.FILLET, 2.0)
        val choices = assertNotNull(Blend3.choicesFor(solid, pair, sec).first, "the pair is scored together")
        val dsl = Evaluator().solid(cx.blendAll(turned, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(2.0.mm), null, pair, choices))))
        assertManifold(dsl.mesh, "the one-gesture body")
        val vf = Geom3.volume(body.mesh)
        val vd = Geom3.volume(dsl.mesh)
        assertClose(vf, vd, 1e-9 * vd, "two recorded gestures and one DSL gesture are the same body")
        println("probe | ring pivot | file $vf, dsl $vd")
    }

    /**
     * **Delete, undo, retype.** Removing the second rounding leaves one band and no corner; undo brings the
     * corner back; retyping the radius recomputes the pivot at the new size; a radius the pivot cannot carry
     * refuses in words that name the edge and never an engine status.
     */
    @Test
    fun deleteUndoAndRetypeMoveTheRingPivot() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(turnedScript()))
        val both = volumeOf(bodyOf(ed), "both roundings")
        val solids = ed.doc.elements.filter { it.kind == ElementKind.SOLID }
        assertEquals(3, solids.size, "the revolve and its two roundings")
        ed.selectElement(solids.last())
        assertTrue(ed.deleteSelection(), "the second rounding comes off: ${ed.statusHint}")
        val one = Evaluator().solid(bodyOf(ed).ref as SolidRef)
        assertManifold(one.mesh, "one band alone")
        assertTrue(facesOf(one).none { it.name is FaceName.BlendCorner }, "one band has no corner")
        assertTrue(Geom3.volume(one.mesh) > both, "…and gives its material back")
        assertTrue(ed.undo(), "one undo")
        assertClose(volumeOf(bodyOf(ed), "restored"), both, 1e-9 * both, "the corner is back")

        val r = ed.doc.scalars.first { it.name == "r" }
        assertTrue(ed.setParameter(r, 1.5), ed.statusHint)
        val smaller = Evaluator().solid(bodyOf(ed).ref as SolidRef)
        assertManifold(smaller.mesh, "r = 1.5")
        assertEquals(1, facesOf(smaller).filter { it.name is FaceName.BlendCorner }.size, "the corner follows the radius")
        assertTrue(Geom3.volume(smaller.mesh) > both, "a smaller ball takes less")

        assertTrue(ed.setParameter(r, 8.0), ed.statusHint)
        val res = Evaluator().eval(bodyOf(ed).ref.node)
        if (res is EvalResult.Invalid) {
            assertTrue(res.reason.contains("#") || res.reason.contains("edge"), "the refusal names the edge: ${res.reason}")
            assertTrue(!res.reason.contains("status") && !res.reason.contains("Manifold"), "no engine diagnostic leaks: ${res.reason}")
            println("probe | r = 8 | refused | ${res.reason.take(160)}")
        } else {
            val big = Evaluator().solid(bodyOf(ed).ref as SolidRef)
            assertManifold(big.mesh, "r = 8")
            println("probe | r = 8 | built")
        }
        assertTrue(ed.setParameter(r, 2.0), ed.statusHint)
        assertClose(volumeOf(bodyOf(ed), "back at 2"), both, 1e-9 * both, "back to the first body")
    }

    /**
     * **Both caps of a 270° turn carry a pivot, in one gesture.** Four cap edges, two reflex corners, two
     * corner faces, one manifold body inside the sum of what the two pivots and four bands take.
     */
    @Test
    fun bothCapsOfAThreeQuarterTurnPivotInOneGesture() {
        val cx = Construction()
        val turned = turned(cx, sweep = 270.0)
        val solid = Evaluator().solid(turned)
        assertManifold(solid.mesh, "the 270° turn")
        val start = reflexPair(solid, Vec3(inner, ring, 0.0))
        // +Y turned 270° about +X is −Z
        val end = reflexPair(solid, Vec3(inner, 0.0, -ring))
        assertEquals(2, start.size, "the start cap's reflex pair")
        assertEquals(2, end.size, "the end cap's reflex pair")
        val all = start + end
        val sec = BlendSection(BlendKind.FILLET, 2.0)
        val (choices, why) = Blend3.choicesFor(solid, all, sec)
        val cs = assertNotNull(choices, "four cap edges are scored together: ${why?.render()}")
        val out = cx.blendAll(turned, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(2.0.mm), null, all, cs)))
        val r = Evaluator().eval(out.node)
        assertTrue(r is EvalResult.Ok, "two pivots in one gesture build: ${(r as? EvalResult.Invalid)?.reason}")
        val body = Evaluator().solid(out)
        assertManifold(body.mesh, "two pivots")
        assertEquals(2, facesOf(body).filter { it.name is FaceName.BlendCorner }.size, "two corner faces")
        // the two caps are congruent, so each pivot alone leaves the same body as the other — and that
        // comparison is exact, because both bodies went through the general boolean once from the same mesh
        val v0 = Geom3.volume(solid.mesh)
        val vStart = pivotedAlone(Vec3(inner, ring, 0.0))
        val vEnd = pivotedAlone(Vec3(inner, 0.0, -ring))
        assertClose(vStart, vEnd, 1e-9 * v0, "the start cap's pivot alone and the end cap's alone are congruent bodies")
        // …while "both take twice one" is measured across the general boolean's float32 mesh: the first
        // boolean re-snaps the revolve's own tessellation by a constant δ (1.7e-4 mm³ here, the same for two
        // plain fillets with no pivot at all) and the difference of the two takes counts δ once — so this is
        // held to the snap and not to the pivot, and the snap itself is queued as (5q)
        val tookOne = v0 - vStart
        val tookBoth = v0 - Geom3.volume(body.mesh)
        assertClose(tookBoth, 2 * tookOne, 1e-7 * v0, "two congruent pivots take twice one, to the float32 snap of the general boolean")
        println("probe | both caps | took $tookBoth against 2 × $tookOne | snap ${2 * tookOne - tookBoth}")
    }

    /** The 270° turn with the one reflex pair at [at] pivoted, as a fresh construction; its volume. */
    private fun pivotedAlone(at: Vec3): Double {
        val cx = Construction()
        val one = turned(cx, sweep = 270.0)
        val s = Evaluator().solid(one)
        val pair = reflexPair(s, at)
        assertEquals(2, pair.size, "the reflex pair at $at")
        val c = assertNotNull(Blend3.choicesFor(s, pair, BlendSection(BlendKind.FILLET, 2.0)).first)
        val ref = cx.blendAll(one, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(2.0.mm), null, pair, c)))
        val r = Evaluator().eval(ref.node)
        assertTrue(r is EvalResult.Ok, "the pivot at $at alone builds: ${(r as? EvalResult.Invalid)?.reason}")
        val b = Evaluator().solid(ref)
        assertManifold(b.mesh, "the pivot at $at alone")
        return Geom3.volume(b.mesh)
    }

    /**
     * **The pivoted body through a general boolean.** A bore clear of the corner takes its own cylinder and
     * nothing else, the result is manifold, and its face list is either given or refused by name.
     */
    @Test
    fun theRingPivotSurvivesAGeneralBoolean() {
        val cx = Construction()
        val turned = turned(cx)
        val solid = Evaluator().solid(turned)
        val pair = reflexPair(solid)
        val sec = BlendSection(BlendKind.FILLET, 2.0)
        val choices = assertNotNull(Blend3.choicesFor(solid, pair, sec).first)
        val pivoted = cx.blendAll(turned, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(2.0.mm), null, pair, choices)))
        val before = Geom3.volume(Evaluator().solid(pivoted).mesh)
        // a 2 mm bore along +X through the outer ring at 45°, radius 25 — clear of the corner at the start cap
        val c = cx.freePoint("d.c", (25.0 / kotlin.math.sqrt(2.0)).mm, (25.0 / kotlin.math.sqrt(2.0)).mm)
        val circle = cx.region(cx.loop(cx.circleCR(c, cx.const(2.0.mm))))
        val drill = cx.extrude(cx.sketchOn(cx.plane(Vec3(-5.0, 0.0, 0.0), Vec3.Y, Vec3.Z), circle), cx.const(30.0.mm))
        val bored = cx.subtract(pivoted, drill)
        val r = Evaluator().eval(bored.node)
        if (r is EvalResult.Invalid) {
            assertTrue(r.reason.contains("#") || r.reason.contains("face") || r.reason.contains("edge"), "a boolean that cannot be built says why: ${r.reason}")
            println("probe | pivot through a boolean | refused | ${r.reason.take(160)}")
            return
        }
        val result = Evaluator().solid(bored)
        assertManifold(result.mesh, "the pivoted body bored")
        val took = before - Geom3.volume(result.mesh)
        val bore = PI * 4.0 * 10.0
        assertTrue(abs(took - bore) < 0.02 * bore, "the bore takes its own cylinder and nothing else: $took against $bore")
        val (faces, why) = Section3.faces(result.feature)
        if (faces == null) {
            val reason = assertNotNull(why, "an ungiven face list has a reason").render()
            assertTrue(reason.contains("#") || reason.contains("face") || reason.contains("edge") || reason.contains("canal"), "…that names something: $reason")
            println("probe | pivot through a boolean | built, faces refused | ${reason.take(160)}")
        } else {
            println("probe | pivot through a boolean | built with ${faces.size} faces")
        }
    }

    // ---- fixtures ----

    private fun profile(): List<Vec2> =
        listOf(Vec2(0.0, inner), Vec2(0.0, ring + 10.0), Vec2(10.0, ring + 10.0), Vec2(10.0, ring), Vec2(20.0, ring), Vec2(20.0, inner))

    private fun turned(
        cx: Construction,
        sweep: Double = 90.0,
    ): SolidRef {
        val o = cx.freePoint("Ro${ids++}", 0.mm, 0.mm)
        val axis = cx.direction(o, cx.freePoint("Rx${ids++}", 1.mm, 0.mm))
        return cx.revolve(cx.sketchOn(cx.planeXY(), polygon(cx, profile())), o, axis, cx.const(Quantity(sweep * PI / 180.0, Dimension.ANGLE)))
    }

    private fun polygon(
        cx: Construction,
        pts: List<Vec2>,
    ): RegionRef {
        val ps = pts.map { cx.freePoint("L${ids++}", it.x.mm, it.y.mm) }
        return cx.region(cx.loop(*ps.indices.map { cx.segment(ps[it], ps[(it + 1) % ps.size]) }.toTypedArray()))
    }

    /** The **convex** sharp edges meeting at the cap's reflex vertex [at] — the two cap edges, never the ring. */
    private fun reflexPair(
        solid: Solid3,
        at: Vec3 = Vec3(inner, ring, 0.0),
    ): List<Int> {
        val es = edgesOf(solid)
        return es.indices.filter { i ->
            val e = es[i]
            if (e.reason != null) return@filter false
            val path = Blend3.edgePath(e).first ?: return@filter false
            val s = path.start ?: return@filter false
            val t = path.end ?: return@filter false
            if ((s - at).length() > 1e-6 && (t - at).length() > 1e-6) return@filter false
            Blend3.choicesFor(solid, listOf(i), BlendSection(BlendKind.FILLET, 2.0)).first?.get(0)?.convex == true
        }
    }

    private fun volumeOf(
        el: Element,
        what: String,
    ): Double {
        val ev = Evaluator()
        val r = ev.eval(el.ref.node)
        assertTrue(r is EvalResult.Ok, "$what: ${(r as? EvalResult.Invalid)?.reason}")
        val mesh = ev.solid(el.ref as SolidRef).mesh
        assertManifold(mesh, what)
        return Geom3.volume(mesh)
    }

    private fun bodyOf(ed: Editor): Element = ed.doc.elements.last { it.kind == ElementKind.SOLID }

    private fun facesOf(s: Solid3) = assertNotNull(Section3.faces(s.feature).first, "it names its faces")

    private fun edgesOf(s: Solid3) = assertNotNull(Section3.edges(s.feature).first, "it names its edges")
}
