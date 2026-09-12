package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.EdgeGeom
import constructit.geom.EdgeName
import constructit.geom.FaceName
import constructit.geom.Geom3
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Probe of slice 5f, the canal band** (OP-31; GitHub #36) — composed with what stood before it and which the
 * delivery's own tests did not touch: several canals in one gesture, a radius retyped after the fact, a bevel
 * section along the same crease, the dressed body through the general boolean, and section planes that are
 * not level. Each asks whether the mechanism is general, not whether the happy path works.
 */
class BlendCanalProbeTest {
    private val width = 40.0
    private val depth = 30.0
    private val height = 20.0

    /**
     * **All four top edges rounded in one gesture leave four elliptical mitres, and one gesture rounds all
     * four.** One entry, four addresses, four canal bands — the removal is the sum of the four brackets and
     * every band is a named face of its own.
     */
    @Test
    fun fourMitresRoundInOneGesture() {
        val cx = Construction()
        val box = prism(cx, listOf(Vec2(0.0, 0.0), Vec2(width, 0.0), Vec2(width, depth), Vec2(0.0, depth)), height)
        val es0 = edgesOf(Evaluator().solid(box))
        val tops = es0.indices.filter { i -> topEdge(es0[i]) }
        assertEquals(4, tops.size, "a block has four top edges")
        val topSec = BlendSection(BlendKind.FILLET, 4.0)
        val topChoices = assertNotNull(Blend3.choicesFor(Evaluator().solid(box), tops, topSec).first, "the four top edges are scored")
        val four = cx.blendAll(box, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(4.0.mm), null, tops, topChoices)))
        val rounded = Evaluator().solid(four)
        assertManifold(rounded.mesh, "four rounds")
        val before = Geom3.volume(rounded.mesh)

        val es = edgesOf(rounded)
        val mitres = es.indices.filter { es[it].name is EdgeName.BlendMitre && es[it].reason == null && es[it].geom is EdgeGeom.OnPlane }
        assertEquals(4, mitres.size, "four rounds meet in four mitres")
        for (m in mitres) assertTrue((es[m].geom as EdgeGeom.OnPlane).piece is ProfileElement.EllipticArcE, "each mitre is an ellipse")

        val sec = BlendSection(BlendKind.FILLET, 1.0)
        val (choices, why) = Blend3.choicesFor(rounded, mitres, sec)
        val cs = assertNotNull(choices, "the four mitres are scored together: ${why?.render()}")
        var lo = 0.0
        var hi = 0.0
        for ((k, m) in mitres.withIndex()) {
            val (l, h) = assertNotNull(Blend3.canalRemoval(rounded.feature, m, sec, cs[k]), "the figure of mitre $m")
            lo += l
            hi += h
        }
        val out = cx.blendAll(four, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(1.0.mm), null, mitres, cs)))
        val r = Evaluator().eval(out.node)
        assertTrue(r is EvalResult.Ok, "four canals in one gesture build: ${(r as? EvalResult.Invalid)?.reason}")
        val body = Evaluator().solid(out)
        assertManifold(body.mesh, "four canal bands")
        val took = before - Geom3.volume(body.mesh)
        assertTrue(took in lo..hi, "four canals take $took, outside the sum of their brackets [$lo, $hi]")
        val faces = facesOf(body)
        for (m in mitres) {
            val band = assertNotNull(faces.firstOrNull { it.name == FaceName.BlendBand(m, 0) }, "the canal along mitre $m is a face")
            assertNotNull(band.reason, "…which is no plane and says so")
        }
        val rails = edgesOf(body).filter { it.name is EdgeName.BlendRail && (it.name as EdgeName.BlendRail).edge in mitres && it.reason == null }
        assertEquals(8, rails.size, "four canals have eight rails")
        for (z in listOf(19.0, 17.0)) {
            val (regions, whyCut) = Section3.regionsOf(body.feature, Plane3(Vec3(0.0, 0.0, z), Vec3.X, Vec3.Y))
            assertEquals(1, assertNotNull(regions, "the level section at z = $z through four canals closes: ${whyCut?.render()}").size)
        }
        println("probe | four canals | took $took in [$lo, $hi]")
    }

    /**
     * **A radius retyped after the fact moves the canal with it.** The canal's own size and the rounds' size
     * are parameters; changing either recomputes the canal — the mitre it runs along moves when the rounds
     * grow — and the body stays inside the bracket the algebra states for the *new* geometry. Two undos give
     * the first body back.
     */
    @Test
    fun aRetypedRadiusRecomputesTheCanal() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(blockScript()))
        val first = volumeOf(bodyOf(ed), "as loaded")
        val rc = ed.doc.scalars.first { it.name == "rc" }
        assertTrue(ed.setParameter(rc, 1.5), ed.statusHint)
        val bigger = volumeOf(bodyOf(ed), "canal 1.5")
        assertTrue(bigger < first, "a larger canal takes more: $bigger against $first")
        checkBracket(ed, 1.5)

        val r0 = ed.doc.scalars.first { it.name == "r0" }
        assertTrue(ed.setParameter(r0, 5.0), ed.statusHint)
        val r1 = ed.doc.scalars.first { it.name == "r1" }
        assertTrue(ed.setParameter(r1, 5.0), ed.statusHint)
        volumeOf(bodyOf(ed), "rounds 5, canal 1.5")
        checkBracket(ed, 1.5)

        // …and the drawing still round-trips as a fixed point with the new values
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the retyped drawing round-trips byte-equal")

        assertTrue(ed.undo(), "undo r1")
        assertTrue(ed.undo(), "undo r0")
        assertClose(volumeOf(bodyOf(ed), "back to rounds 4"), bigger, 1e-9 * bigger, "rounds back at 4")
        assertTrue(ed.undo(), "undo rc")
        assertClose(volumeOf(bodyOf(ed), "back to canal 1"), first, 1e-9 * first, "the first body")
    }

    /**
     * **A bevel along the elliptical mitre.** A chamfer is a section like any other; the canal is the ball's
     * construction and a bevel has no ball. The drawing builds it or refuses it by name — this asserts
     * whichever it does, and that a refusal is not silent.
     */
    @Test
    fun aChamferAlongTheMitreBuildsOrRefusesByName() {
        val cx = Construction()
        val two = twoRounds(cx)
        val rounded = Evaluator().solid(two)
        val before = Geom3.volume(rounded.mesh)
        val mitre = mitreOf(rounded)
        val sec = BlendSection(BlendKind.CHAMFER, 1.0)
        val (choices, why) = Blend3.choicesFor(rounded, listOf(mitre), sec)
        if (choices == null) {
            val reason = assertNotNull(why, "a refusal has a reason").render()
            assertTrue(namesSomething(reason), "the refusal names something: $reason")
            println("probe | chamfer along the mitre | refused | ${reason.take(120)}")
            return
        }
        val out = cx.blendAll(two, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.CHAMFER, cx.const(1.0.mm), null, listOf(mitre), choices)))
        val r = Evaluator().eval(out.node)
        if (r is EvalResult.Invalid) {
            assertTrue(namesSomething(r.reason), "a bevel that cannot be built says why: ${r.reason}")
            println("probe | chamfer along the mitre | refused | ${r.reason.take(120)}")
            return
        }
        val body = Evaluator().solid(out)
        assertManifold(body.mesh, "a bevel along the elliptical mitre")
        val took = before - Geom3.volume(body.mesh)
        assertTrue(took > 0.0, "a bevel on a ridge takes material: $took")
        assertNotNull(facesOf(body).firstOrNull { it.name == FaceName.BlendBand(mitre, 0) }, "the bevel band is a face")
        println("probe | chamfer along the mitre | built | took $took")
    }

    /**
     * **The dressed body goes through the general boolean.** A bore square to the block's extrusion axis and
     * clear of the corner: the result keeps the canal band as a face of its own (slice 5c's rule that a
     * boolean never moves a surface), or the drawing says by name what it cannot keep. The bore's own volume
     * is the cylinder it is, so the canal is untouched either way.
     */
    @Test
    fun theCanalBodySurvivesAGeneralBoolean() {
        val cx = Construction()
        val two = twoRounds(cx)
        val rounded = Evaluator().solid(two)
        val mitre = mitreOf(rounded)
        val canal = round(cx, two, mitre, 1.0)
        val body = Evaluator().solid(canal)
        val before = Geom3.volume(body.mesh)
        // a 3 mm bore along +X through the block at (y, z) = (15, 8), well below the roundings
        val c = cx.freePoint("d.c", 15.0.mm, 8.0.mm)
        val circle = cx.region(cx.loop(cx.circleCR(c, cx.const(3.0.mm))))
        val drill = cx.extrude(cx.sketchOn(cx.plane(Vec3(-5.0, 0.0, 0.0), Vec3.Y, Vec3.Z), circle), cx.const(50.0.mm))
        val bored = cx.subtract(canal, drill)
        val r = Evaluator().eval(bored.node)
        if (r is EvalResult.Invalid) {
            assertTrue(namesSomething(r.reason), "a boolean that cannot be built says why: ${r.reason}")
            println("probe | canal through a boolean | refused | ${r.reason.take(120)}")
            return
        }
        val result = Evaluator().solid(bored)
        assertManifold(result.mesh, "the canal body bored")
        val took = before - Geom3.volume(result.mesh)
        val bore = Math.PI * 9.0 * width
        assertTrue(abs(took - bore) < 0.02 * bore, "the bore takes its own cylinder and nothing else: $took against $bore")
        val (faces, why) = Section3.faces(result.feature)
        if (faces == null) {
            val reason = assertNotNull(why, "a face list that is not given has a reason").render()
            assertTrue(namesSomething(reason), "…that names something: $reason")
            println("probe | canal through a boolean | built, faces refused | ${reason.take(120)}")
            return
        }
        val band = faces.firstOrNull { f -> f.reason?.render()?.contains("canal") == true }
        assertNotNull(band, "the canal band is still a face of the bored body: ${faces.map { it.name.label.render() }}")
        val (regions, whyCut) = Section3.regionsOf(result.feature, Plane3(Vec3(0.0, 0.0, 17.5), Vec3.X, Vec3.Y))
        assertEquals(1, assertNotNull(regions, "a level section through the canal of the bored body closes: ${whyCut?.render()}").size)
        println("probe | canal through a boolean | built with ${faces.size} faces, bore took $took")
    }

    /**
     * **Section planes that are not level.** The canal's cut is the zero isoline on its own chart; a vertical
     * plane parallel to the mitre's own plane crosses the band obliquely along its whole run, and a tilted
     * plane crosses it once. Both must close.
     */
    @Test
    fun sectionsThatAreNotLevelCloseThroughTheCanal() {
        val cx = Construction()
        val two = twoRounds(cx)
        val mitre = mitreOf(Evaluator().solid(two))
        val body = Evaluator().solid(round(cx, two, mitre, 1.0))
        val s = 1.0 / sqrt(2.0)
        // the mitre lies in x − y = 10; this plane stands 2 mm beside it, parallel, and cuts the canal along its run
        val beside = Plane3(Vec3(42.0, 30.0, 0.0), Vec3(s, s, 0.0), Vec3.Z)
        val (r1, why1) = Section3.regionsOf(body.feature, beside)
        val rs1 = assertNotNull(r1, "the vertical section beside the mitre closes: ${why1?.render()}")
        assertEquals(1, rs1.size, "one area")
        // a plane tilted through the corner, normal (1, 1, 1)
        val n = Vec3(1.0, 1.0, 1.0).normalized()
        val u = Vec3(1.0, -1.0, 0.0).normalized()
        val v = n.cross(u)
        val tilted = Plane3(Vec3(width - 2.0, depth - 2.0, height - 2.0), u, v)
        val (r2, why2) = Section3.regionsOf(body.feature, tilted)
        val rs2 = assertNotNull(r2, "the tilted section through the corner closes: ${why2?.render()}")
        assertTrue(rs2.isNotEmpty(), "…with an area")
        println("probe | sections | beside: ${rs1.size} region(s), tilted: ${rs2.size} region(s)")
    }

    /**
     * **A ball too large for the bend refuses in the editor's own words.** Retyped past the spine's least
     * radius of curvature, the canal's body is invalid with a reason that names the crease and says why — never
     * an engine status — and retyping back gives the body again.
     */
    @Test
    fun aBallTooLargeForTheBendRefusesByNameInTheEditor() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(blockScript()))
        val first = volumeOf(bodyOf(ed), "as loaded")
        val rc = ed.doc.scalars.first { it.name == "rc" }
        assertTrue(ed.setParameter(rc, 2.5), ed.statusHint)
        val r = Evaluator().eval(bodyOf(ed).ref.node)
        assertTrue(r is EvalResult.Invalid, "a 2.5 mm ball does not roll along the mitre of two 4 mm rounds")
        val reason = (r as EvalResult.Invalid).reason
        assertTrue(namesSomething(reason), "the refusal names the crease: $reason")
        assertTrue(!reason.contains("status") && !reason.contains("Manifold"), "…and no engine diagnostic leaks: $reason")
        assertTrue(ed.setParameter(rc, 1.0), ed.statusHint)
        assertClose(volumeOf(bodyOf(ed), "back at 1 mm"), first, 1e-9 * first, "the body is back")
        println("probe | too large | $reason")
    }

    // ---- fixtures ----

    private fun checkBracket(
        ed: Editor,
        rc: Double,
    ) {
        val solids = ed.doc.elements.filter { it.kind == ElementKind.SOLID }
        assertEquals(4, solids.size)
        val twoRounds = Evaluator().solid(solids[2].ref as SolidRef)
        val canal = Evaluator().solid(solids[3].ref as SolidRef)
        assertManifold(canal.mesh, "canal $rc")
        val mitre = mitreOf(twoRounds)
        val sec = BlendSection(BlendKind.FILLET, rc)
        val choice = assertNotNull(Blend3.choicesFor(twoRounds, listOf(mitre), sec).first)[0]
        val (lo, hi) = assertNotNull(Blend3.canalRemoval(twoRounds.feature, mitre, sec, choice), "the algebra states the figure")
        val took = Geom3.volume(twoRounds.mesh) - Geom3.volume(canal.mesh)
        assertTrue(took in lo..hi, "the retyped canal takes $took, outside its bracket [$lo, $hi]")
        println("probe | retyped rc = $rc | took $took in [$lo, $hi]")
    }

    private fun topEdge(e: constructit.geom.SolidEdge): Boolean {
        val path = Blend3.edgePath(e).first ?: return false
        val a = path.start ?: return false
        val b = path.end ?: return false
        return abs(a.z - height) < 1e-9 && abs(b.z - height) < 1e-9
    }

    private fun topsAtFarCorner(es: List<constructit.geom.SolidEdge>): List<Int> {
        val corner = Vec3(width, depth, height)
        return es.indices.filter { i ->
            val path = Blend3.edgePath(es[i]).first ?: return@filter false
            val a = path.start ?: return@filter false
            val b = path.end ?: return@filter false
            topEdge(es[i]) && ((a - corner).length() < 1e-9 || (b - corner).length() < 1e-9)
        }
    }

    private fun twoRounds(cx: Construction): SolidRef {
        val box = prism(cx, listOf(Vec2(0.0, 0.0), Vec2(width, 0.0), Vec2(width, depth), Vec2(0.0, depth)), height)
        val tops = topsAtFarCorner(edgesOf(Evaluator().solid(box)))
        assertEquals(2, tops.size)
        val choices = assertNotNull(Blend3.choicesFor(Evaluator().solid(box), tops, BlendSection(BlendKind.FILLET, 4.0)).first)
        return cx.blendAll(box, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(4.0.mm), null, tops, choices)))
    }

    private fun mitreOf(solid: Solid3): Int {
        val es = edgesOf(solid)
        return assertNotNull(
            es.indices.firstOrNull { es[it].name is EdgeName.BlendMitre && es[it].reason == null && es[it].geom is EdgeGeom.OnPlane },
            "the two bands cross in a mitre",
        )
    }

    private fun round(
        cx: Construction,
        on: SolidRef,
        address: Int,
        size: Double,
    ): SolidRef {
        val body = Evaluator().solid(on)
        val (choices, why) = Blend3.choicesFor(body, listOf(address), BlendSection(BlendKind.FILLET, size))
        assertNotNull(choices, "edge $address is scored: ${why?.render()}")
        return cx.blendAll(on, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(size.mm), null, listOf(address), choices)))
    }

    /** The block, its two far top edges rounded one gesture at a time at `r0`, `r1`, and the mitre at `rc` — as a file. */
    private fun blockScript(): String {
        val cx = Construction()
        val box = prism(cx, listOf(Vec2(0.0, 0.0), Vec2(width, 0.0), Vec2(width, depth), Vec2(0.0, depth)), height)
        val tops = topsAtFarCorner(edgesOf(Evaluator().solid(box)))
        val sb = StringBuilder("constructit ${DocumentFormat.VERSION}\n")
        sb.append("orthostart 0.0,0.0 -> e1\n")
        sb.append("orthovertex $width,0.0 -> e2,e3\n")
        sb.append("orthovertex $width,$depth -> e4,e5\n")
        sb.append("orthovertex 0.0,$depth -> e6,e7\n")
        sb.append("orthoclose -> e8\n")
        sb.append("param \"h\" = ${height}mm\n")
        sb.append("tool extrude els=e7 clicks=-10,-10 scalar=\"h\" -> e9\n")
        var at = 9
        var on = box
        for ((k, e) in tops.withIndex()) {
            val choice = assertNotNull(Blend3.choicesFor(Evaluator().solid(on), listOf(e), BlendSection(BlendKind.FILLET, 4.0)).first)[0]
            sb.append("param \"r$k\" = 4mm\n")
            sb.append("tool filletedge els=e$at clicks=0,0 scalar=\"r$k\" signs=$e;${choice.signs().joinToString(";")} -> e${at + 1},e${at + 2}\n")
            on = round(cx, on, e, 4.0)
            at += 1
        }
        val mitre = mitreOf(Evaluator().solid(on))
        val choice = assertNotNull(Blend3.choicesFor(Evaluator().solid(on), listOf(mitre), BlendSection(BlendKind.FILLET, 1.0)).first)[0]
        sb.append("param \"rc\" = 1mm\n")
        sb.append("tool filletedge els=e$at clicks=0,0 scalar=\"rc\" signs=$mitre;${choice.signs().joinToString(";")} -> e${at + 1},e${at + 2}\n")
        return sb.toString()
    }

    private fun namesSomething(reason: String): Boolean =
        reason.contains("#") || reason.contains("face") || reason.contains("edge") || reason.contains("crease") || reason.contains("canal")

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

    private fun prism(
        cx: Construction,
        xy: List<Vec2>,
        h: Double,
    ): SolidRef {
        val pts = xy.mapIndexed { i, p -> cx.freePoint("p$i", p.x.mm, p.y.mm) }
        val segs = xy.indices.map { cx.segment(pts[it], pts[(it + 1) % xy.size]) }
        return cx.extrude(cx.sketchOn(cx.planeXY(), cx.region(cx.loop(*segs.toTypedArray()))), cx.const(h.mm))
    }
}
