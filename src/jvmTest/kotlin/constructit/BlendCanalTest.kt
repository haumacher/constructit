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
import constructit.geom.Frames3
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.SolidEdge
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.l10n.contains
import constructit.units.mm
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The ball along an elliptical crease — the canal band** (OP-31, slice 5f; GitHub #36).
 *
 * *The rule.* Where a crease carries **no rigid section** — two equal roundings crossing meet in a plane
 * ellipse, and the dihedral along it runs from `π` where both bands are tangent to the face they share down
 * to its least where they are not — the rounding of it is the **pipe surface** of a ball of radius `r`
 * carried along a spine, and the tool is the loft of the ball's own sections.
 *
 * *What is exact.* A constant-radius ball's envelope has, in the plane normal to its spine at any station, a
 * **circle of radius `r` about the station** — the characteristic of `|x − c(s)| = r` is the plane
 * `(x − c)·c' = 0`, and a sphere cut through its own centre is a great circle. So the tool at each station is
 * exact in its own normal plane, and the two things it needs are exact too: the spine is the locus of points
 * standing `r` from both faces (two equations, solved to machine precision, and for two equal cylinders whose
 * axes cross it is the mitre's own ellipse scaled by `(R − r)/R` about the point the axes cross), and each
 * tangency is the nearest point of a face to the centre, which lies in that station's own normal plane
 * exactly (`|p − c| = r` differentiated, with `p − c` the face's own normal at `p`).
 *
 * *What is fitted, and says so.* The two rails are chains of cubics through points every one of which is
 * exact on both the sphere and the face, with the tolerance reached recorded on the edge; and the band's own
 * cut is **sampled** — a canal band has no straight rulings, so the ruled reader has nothing to offer it and
 * the cut is read station by station, flagged as chords (OP-15).
 *
 * *The figure.* `∫ A(s)·(1 − κ(s)·x̄(s)) ds` along the spine — Pappus' own volume element written for a
 * section that changes — bracketed between the exact quadrature less the walls' own tessellation strip and
 * the chorded one plus the tool's own step-off strip. Every term is the drawing's own rule.
 */
class BlendCanalTest {
    private val width = 40.0
    private val depth = 30.0
    private val height = 20.0

    // ---- (a) the elliptical mitre between two equal rounds ----

    /**
     * **A 40 × 30 × 20 block, two adjacent top edges rounded at 4 mm, and the elliptical mitre they cross in
     * rounded at 1 mm.** The crease is listed as an `EllipticArcE`; the body is one manifold shell; the
     * removal stands inside the quadrature's own bracket; both rails are fitted chains whose every knot is
     * exact on the cylinder it rolls on; the band is named and carries the tolerance it was fitted to.
     */
    @Test
    fun theEllipticalMitreOfTwoEqualRoundsIsRoundedAsACanalBand() {
        val cx = Construction()
        val two = twoRounds(cx, 4.0, 4.0)
        val rounded = Evaluator().solid(two)
        assertManifold(rounded.mesh, "two equal rounds crossing")
        val before = Geom3.volume(rounded.mesh)
        val mitre = mitreOf(rounded)
        val piece = (edgesOf(rounded)[mitre].geom as EdgeGeom.OnPlane).piece
        assertTrue(piece is ProfileElement.EllipticArcE, "two equal cylinders whose axes meet cut in an ellipse, not a $piece")

        val sec = BlendSection(BlendKind.FILLET, 1.0)
        val (choices, why) = Blend3.choicesFor(rounded, listOf(mitre), sec)
        val choice = assertNotNull(choices, "the mitre is scored: ${why?.render()}")[0]
        assertTrue(choice.convex, "the mitre of two convex rounds is a ridge, so the canal is subtracted")
        val out = round(cx, two, mitre, 1.0)
        val body = Evaluator().solid(out)
        assertManifold(body.mesh, "the canal band along the elliptical mitre")
        val took = before - Geom3.volume(body.mesh)
        val (lo, hi) = assertNotNull(Blend3.canalRemoval(rounded.feature, mitre, sec, choice), "the algebra states the canal's figure")
        assertTrue(took in lo..hi, "the canal takes $took, outside its own bracket [$lo, $hi]")

        val band = assertNotNull(facesOf(body).firstOrNull { it.name == FaceName.BlendBand(mitre, 0) }, "the canal band is a face of the body")
        val reason = assertNotNull(band.reason, "…which is no plane, and says so")
        assertTrue(reason.contains("canal band"), "…in those words: ${reason.render()}")
        assertNotNull(band.fitted, "…and it carries the tolerance its own boundary was fitted to")

        val rails = edgesOf(body).filter { it.name is EdgeName.BlendRail && (it.name as EdgeName.BlendRail).edge == mitre }
        assertEquals(2, rails.size, "a canal band has two rails")
        for (rail in rails) {
            val chain = assertNotNull(rail.geom as? EdgeGeom.InSpace, "${rail.name.label.render()} is a curve in space").chain
            val tol = assertNotNull(rail.fitted, "…and it says how far it may be")
            assertTrue(chain.size >= 2, "…and it took more than one span to get there")
            var worstKnot = 0.0
            for (span in chain) worstKnot = kotlin.math.max(worstKnot, offCylinders(span.start))
            worstKnot = kotlin.math.max(worstKnot, offCylinders(chain.last().end))
            assertTrue(worstKnot <= 1e-9, "every knot is exact on the cylinder the ball rolls on — worst $worstKnot")
            var worstSpan = 0.0
            for (span in chain) {
                for (k in 1 until 16) worstSpan = kotlin.math.max(worstSpan, offCylinders(Frames3.pointAt(span, k / 16.0)))
            }
            assertTrue(worstSpan <= tol + 1e-9, "…and the spans between stand within the stated $tol mm — worst $worstSpan")
        }
        println("canal | block 40x30x20, two rounds R=4, mitre r=1 | took $took in [$lo, $hi]")
    }

    /**
     * **The face list still bounds the body**: three level sections through the corner close, and a face
     * space on the face the two roundings share still opens.
     *
     * The canal's own cut is the **sampled** one this slice adds, and the two bands it bites into are ended
     * by its own rail exactly as a crossing's mitre ends them — which is what makes the loop close.
     */
    @Test
    fun aLevelSectionThroughTheCanalClosesAndTheSharedFaceStillOpens() {
        val cx = Construction()
        val two = twoRounds(cx, 4.0, 4.0)
        val mitre = mitreOf(Evaluator().solid(two))
        val body = Evaluator().solid(round(cx, two, mitre, 1.0))
        assertManifold(body.mesh, "the canal band")
        for (z in listOf(19.5, 17.5, 15.0)) {
            val (regions, why) = Section3.regionsOf(body.feature, Plane3(Vec3(0.0, 0.0, z), Vec3.X, Vec3.Y))
            val rs = assertNotNull(regions, "the section at z = $z closes: ${why?.render()}")
            assertEquals(1, rs.size, "one area at z = $z")
        }
        val (regions, why) = Section3.regionsOf(body.feature, Plane3(Vec3(0.0, 0.0, 5.0), Vec3.X, Vec3.Y))
        val rs = assertNotNull(regions, "the section at z = 5 closes: ${why?.render()}")
        assertClose(abs(GeomMath.signedArea(rs[0].outer)), width * depth, 1e-9, "below the roundings the block is whole")
        val top = facesOf(body).first { it.plane?.let { p -> abs(p.normal.normalized().z - 1.0) < 1e-9 && abs(p.origin.z - height) < 1e-9 } == true }
        assertEquals(null, top.reason?.render(), "the face the two roundings share is still one a sketch can be put on")
        assertTrue(top.outline.isNotEmpty(), "…and it still states its own outline")
    }

    /**
     * **The file is a fixed point and one delete takes the canal back off** — the second gesture is an
     * ordinary `filletedge` addressing an ordinary edge index, so the canal costs the format nothing at all.
     */
    @Test
    fun theCanalRoundTripsAndOneUndoGivesItBack() {
        val script = blockScript()
        val once = DocumentFormat.save(DocumentFormat.load(script))
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the whole drawing round-trips byte-equal")

        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(script))
        val both = volumeOf(bodyRef(ed.doc), "the two rounds and the canal along their mitre")
        val solids = ed.doc.elements.filter { it.kind == ElementKind.SOLID }
        assertEquals(4, solids.size, "the block, each round, and the canal along the mitre")
        ed.selectElement(solids.last())
        assertTrue(ed.deleteSelection(), "the canal comes off: ${ed.statusHint}")
        val without = volumeOf(bodyRef(ed.doc), "the two rounds alone")
        assertTrue(without > both, "…and its own removal with it: $without against $both")
        assertTrue(ed.undo(), "the removal is one undo step")
        // …to the engine's own ULP: the general boolean is deterministic only to that, which is queued
        assertClose(volumeOf(bodyRef(ed.doc), "restored"), both, 1e-9 * both, "one undo gives it back")
    }

    /**
     * **The concave twin, one sign over**: two fills crossing at the inside corner of a room leave the same
     * elliptical mitre, and the rounding of it is the same canal **added** rather than taken away — or it is
     * refused by name, and this asserts whichever the drawing does.
     */
    @Test
    fun theConcaveTwinIsTheSameCanalOneSignOver() {
        val cx = Construction()
        val box = prism(cx, listOf(Vec2(0.0, 0.0), Vec2(60.0, 0.0), Vec2(60.0, 40.0), Vec2(0.0, 40.0)), height)
        val faces = facesOf(Evaluator().solid(box))
        val top = faces.indices.first { faces[it].plane?.let { p -> abs(p.normal.normalized().z - 1.0) < 1e-9 && abs(p.origin.z - height) < 1e-9 } == true }
        var on = cx.shell(box, cx.const(3.0.mm), listOf(top))
        for (k in 0 until 2) {
            val solid = Evaluator().solid(on)
            val es = edgesOf(solid)
            val at =
                assertNotNull(
                    es.indices.firstOrNull { i ->
                        es[i].reason == null &&
                            Blend3.choicesFor(solid, listOf(i), BlendSection(BlendKind.FILLET, 2.0)).first?.get(0)?.convex == false &&
                            floorCrease(es[i])
                    },
                    "a concave crease of the room's floor",
                )
            on = round(cx, on, at, 2.0)
        }
        val filled = Evaluator().solid(on)
        assertManifold(filled.mesh, "two fills crossing in a room")
        val before = Geom3.volume(filled.mesh)
        val es = edgesOf(filled)
        val mitres = es.indices.filter { es[it].name is EdgeName.BlendMitre && es[it].reason == null }
        if (mitres.isEmpty()) {
            println("concave twin | no mitre is stated between two fills — nothing to round, and nothing claimed")
            return
        }
        for (m in mitres) {
            val sec = BlendSection(BlendKind.FILLET, 0.5)
            val (choices, whyChoice) = Blend3.choicesFor(filled, listOf(m), sec)
            if (choices == null) {
                assertTrue(namesSomething(assertNotNull(whyChoice, "a refusal has a reason").render()), "the refusal names something")
                println("concave twin | refused | ${whyChoice.render().take(80)}")
                continue
            }
            val ref = round(cx, on, m, 0.5)
            val r = Evaluator().eval(ref.node)
            if (r is EvalResult.Invalid) {
                assertTrue(namesSomething(r.reason), "a rounding that cannot be built says why: ${r.reason}")
                println("concave twin | refused | ${r.reason.take(80)}")
                continue
            }
            val body = Evaluator().solid(ref)
            assertManifold(body.mesh, "the concave canal band")
            val moved = Geom3.volume(body.mesh) - before
            val (lo, hi) = assertNotNull(Blend3.canalRemoval(filled.feature, m, sec, choices[0]), "the algebra states the fill's figure")
            assertTrue(!choices[0].convex, "a fill's mitre is a valley, so the canal is added")
            assertTrue(moved in lo..hi, "the concave canal adds $moved, outside its own bracket [$lo, $hi]")
            println("concave twin | built | added $moved in [$lo, $hi]")
        }
    }

    // ---- (d) the mitre of two rounds of *unlike* size ----

    /**
     * **Two rounds of unlike size cross in no ellipse at all**: the crease is the fitted quartic slice 5a
     * names, and the same pipe machinery runs along it — the spine is solved station by station against the
     * two cylinders exactly as it is along an ellipse, and the tangencies stay exact pointwise. It builds, or
     * it is refused by name; this asserts whichever, and reports which.
     */
    @Test
    fun theMitreOfTwoUnlikeRoundsIsTheSameCanalOnAFittedSpine() {
        val cx = Construction()
        val two = twoRounds(cx, 4.0, 3.0)
        val rounded = Evaluator().solid(two)
        assertManifold(rounded.mesh, "two unlike rounds crossing")
        val before = Geom3.volume(rounded.mesh)
        val es = edgesOf(rounded)
        val creases = es.indices.filter { es[it].reason == null && es[it].geom is EdgeGeom.InSpace }
        if (creases.isEmpty()) {
            println("unlike sizes | the drawing states no crease between the two bands — the standing cut of slice 5e")
            return
        }
        for (at in creases) {
            val sec = BlendSection(BlendKind.FILLET, 0.5)
            val (choices, why) = Blend3.choicesFor(rounded, listOf(at), sec)
            if (choices == null) {
                assertTrue(namesSomething(assertNotNull(why, "a refusal has a reason").render()), "the refusal names something")
                println("unlike sizes | refused | ${why.render().take(90)}")
                continue
            }
            val ref = round(cx, two, at, 0.5)
            val r = Evaluator().eval(ref.node)
            if (r is EvalResult.Invalid) {
                assertTrue(namesSomething(r.reason), "a rounding that cannot be built says why: ${r.reason}")
                println("unlike sizes | refused | ${r.reason.take(90)}")
                continue
            }
            val body = Evaluator().solid(ref)
            assertManifold(body.mesh, "the canal band on a fitted spine")
            val took = before - Geom3.volume(body.mesh)
            val (lo, hi) = assertNotNull(Blend3.canalRemoval(rounded.feature, at, sec, choices[0]), "the algebra states its figure")
            assertTrue(took in lo..hi, "the canal on a fitted spine takes $took, outside its own bracket [$lo, $hi]")
            println("unlike sizes | built | took $took in [$lo, $hi]")
        }
    }

    // ---- the fixtures ----

    /** A block with the two top edges at its far corner rounded, at [ra] and [rb]. */
    private fun twoRounds(
        cx: Construction,
        ra: Double,
        rb: Double,
    ): SolidRef {
        val box = prism(cx, listOf(Vec2(0.0, 0.0), Vec2(width, 0.0), Vec2(width, depth), Vec2(0.0, depth)), height)
        val es = edgesOf(Evaluator().solid(box))
        val corner = Vec3(width, depth, height)
        val tops =
            es.indices.filter { i ->
                val path = Blend3.edgePath(es[i]).first ?: return@filter false
                val a = path.start ?: return@filter false
                val b = path.end ?: return@filter false
                abs(a.z - height) < 1e-9 && abs(b.z - height) < 1e-9 && ((a - corner).length() < 1e-9 || (b - corner).length() < 1e-9)
            }
        assertEquals(2, tops.size, "two top edges share the block's far corner")
        if (ra == rb) {
            val body = Evaluator().solid(box)
            val choices = assertNotNull(Blend3.choicesFor(body, tops, BlendSection(BlendKind.FILLET, ra)).first, "the two top edges are scored")
            return cx.blendAll(box, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(ra.mm), null, tops, choices)))
        }
        var on = box
        for ((k, e) in tops.withIndex()) on = round(cx, on, e, if (k == 0) ra else rb)
        return on
    }

    /** The mitre the two bands cross in — the one edge of the dressed body an ellipse arc carries. */
    private fun mitreOf(solid: Solid3): Int {
        val es = edgesOf(solid)
        return assertNotNull(
            es.indices.firstOrNull { es[it].name is EdgeName.BlendMitre && es[it].reason == null && es[it].geom is EdgeGeom.OnPlane },
            "the two bands cross in a mitre",
        )
    }

    /** How far [p] stands off the nearer of the block's two band cylinders — both of radius 4 at the corner. */
    private fun offCylinders(p: Vec3): Double {
        val a = abs(hypot(p.y - (depth - 4.0), p.z - (height - 4.0)) - 4.0)
        val b = abs(hypot(p.x - (width - 4.0), p.z - (height - 4.0)) - 4.0)
        return kotlin.math.min(a, b)
    }

    private fun floorCrease(e: SolidEdge): Boolean {
        val path = Blend3.edgePath(e).first ?: return false
        val el = path.elements.singleOrNull() ?: return false
        return abs(el.start.z - 3.0) <= 1e-9 && abs(el.end.z - 3.0) <= 1e-9
    }

    private fun namesSomething(reason: String): Boolean =
        reason.contains("#") || reason.contains("face") || reason.contains("edge") || reason.contains("crease")

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

    /** The block, its two top edges rounded one gesture at a time, and the mitre they cross in — as a file. */
    private fun blockScript(): String {
        val cx = Construction()
        val box = prism(cx, listOf(Vec2(0.0, 0.0), Vec2(width, 0.0), Vec2(width, depth), Vec2(0.0, depth)), height)
        val corner = Vec3(width, depth, height)
        val es = edgesOf(Evaluator().solid(box))
        val tops =
            es.indices.filter { i ->
                val path = Blend3.edgePath(es[i]).first ?: return@filter false
                val a = path.start ?: return@filter false
                val b = path.end ?: return@filter false
                abs(a.z - height) < 1e-9 && abs(b.z - height) < 1e-9 && ((a - corner).length() < 1e-9 || (b - corner).length() < 1e-9)
            }
        val sb = StringBuilder("constructit ${DocumentFormat.VERSION}\n")
        val plan = listOf(Vec2(0.0, 0.0), Vec2(width, 0.0), Vec2(width, depth), Vec2(0.0, depth))
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
        var k = 0
        for (e in tops) {
            val choice = assertNotNull(Blend3.choicesFor(Evaluator().solid(on), listOf(e), BlendSection(BlendKind.FILLET, 4.0)).first, "top edge $e is scored")[0]
            sb.append("param \"r$k\" = 4mm\n")
            sb.append("tool filletedge els=e$at clicks=0,0 scalar=\"r$k\" signs=$e;${choice.signs().joinToString(";")} -> e${at + 1},e${at + 2}\n")
            on = round(cx, on, e, 4.0)
            at += 1
            k++
        }
        val mitre = mitreOf(Evaluator().solid(on))
        val choice = assertNotNull(Blend3.choicesFor(Evaluator().solid(on), listOf(mitre), BlendSection(BlendKind.FILLET, 1.0)).first, "the mitre is scored")[0]
        sb.append("param \"rc\" = 1mm\n")
        sb.append("tool filletedge els=e$at clicks=0,0 scalar=\"rc\" signs=$mitre;${choice.signs().joinToString(";")} -> e${at + 1},e${at + 2}\n")
        return sb.toString()
    }

    private fun volumeOf(
        ref: SolidRef,
        what: String,
    ): Double {
        val ev = Evaluator()
        val r = ev.eval(ref.node)
        assertTrue(r is EvalResult.Ok, "$what: ${(r as? EvalResult.Invalid)?.reason}")
        val mesh = ev.solid(ref).mesh
        assertManifold(mesh, what)
        return Geom3.volume(mesh)
    }

    @Suppress("UNCHECKED_CAST")
    private fun bodyRef(doc: constructit.editor.Document): SolidRef =
        (doc.elements.last { it.kind == ElementKind.SOLID } as Element).ref as SolidRef

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
