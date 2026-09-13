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
import constructit.geom.MeshBool
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.l10n.contains
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A bevel along a crease with no rigid section** (OP-31, slice 5n; GitHub #36).
 *
 * *The rule, in session 84's own words.* *"A constant-setback chamfer along a crease of changing dihedral is
 * the ruled strip between the two setback traces on the two walls, which is a loft between two fitted curves
 * and not the ball's canal — its own tool, its own cut reader and its own figure."* Until this slice such a
 * chamfer was refused outright (`refusal.blend.carriesNoRigidSection`, which now keeps only the drawn
 * profile's case).
 *
 * *What is exact and what is fitted.* Every ruling is a closed reading of the crease point under it: the
 * crease's own point on both walls, the frame square to the crease there, and the point of each wall standing
 * a setback away **along that wall's own trace in that plane** — which is `FilletMath.setback`'s rule one
 * dimension up, and session 76's convention (*the setback runs along the carrier*) unchanged. The ruling
 * between the two is the bevel face itself and is exact. What is fitted is the two rails, chains of cubics
 * through points every one of which is exact on its wall, and the strip's own chords between two rulings.
 *
 * *The figure.* At each station the removed section is the region between the ruling and the two wall
 * traces — at a plane pair the triangle with two sides `d` and the dihedral `α(s)` between them, `½ d² sin
 * α` — carried through Pappus' own volume element `∫A(s)(1 − κ x̄) ds` over the crease and bracketed by the
 * tessellation, exactly as slice 5f brackets the canal.
 */
class BlendBevelCanalTest {
    private val width = 40.0
    private val depth = 30.0
    private val height = 20.0

    /**
     * **A 40 × 30 × 20 block, two adjacent top edges rounded at 4 mm, and the elliptical mitre they cross in
     * bevelled at a setback of 1 mm.** The body is one manifold shell; the band slot is a named **ruled
     * strip**; its two rails are fitted chains in the edge list with the tolerance they reached and every
     * knot exact on the cylinder it is measured along; the removal stands inside the quadrature's own
     * bracket; and a sketch space on the strip is refused in the words that say it is one.
     */
    @Test
    fun theMitreOfTwoEqualRoundsIsBevelledAsARuledStrip() {
        val cx = Construction()
        val two = twoRounds(cx, 4.0, 4.0)
        val rounded = Evaluator().solid(two)
        assertManifold(rounded.mesh, "two equal rounds crossing")
        val before = Geom3.volume(rounded.mesh)
        val mitre = mitreOf(rounded)

        val sec = BlendSection(BlendKind.CHAMFER, 1.0)
        val (choices, why) = Blend3.choicesFor(rounded, listOf(mitre), sec)
        val choice = assertNotNull(choices, "the mitre is scored for a chamfer: ${why?.render()}")[0]
        assertTrue(choice.convex, "the mitre of two convex rounds is a ridge, so the strip is subtracted")
        val body = Evaluator().solid(bevel(cx, two, mitre, 1.0))
        assertManifold(body.mesh, "the ruled strip along the elliptical mitre")
        val took = before - Geom3.volume(body.mesh)
        val (lo, hi) = assertNotNull(Blend3.canalRemoval(rounded.feature, mitre, sec, choice), "the algebra states the strip's figure")
        assertTrue(took in lo..hi, "the bevel takes $took, outside its own bracket [$lo, $hi]")

        val band = assertNotNull(facesOf(body).firstOrNull { it.name == FaceName.BlendBand(mitre, 0) }, "the strip is a face of the body")
        val reason = assertNotNull(band.reason, "…which is no plane, and says so")
        assertTrue(reason.contains("ruled strip"), "…in those words: ${reason.render()}")
        assertTrue(band.ruled, "…and the slot says it is a ruled strip rather than a plane, a revolution or a pipe")
        assertEquals(null, band.plane, "a ruled strip is no plane")
        assertEquals(null, band.surface, "…and no surface of revolution")
        assertEquals(null, band.pipe, "…and no pipe")
        assertNotNull(band.fitted, "…and it carries the tolerance its own statement was fitted to")

        val rails = edgesOf(body).filter { it.name is EdgeName.BlendRail && (it.name as EdgeName.BlendRail).edge == mitre }
        assertEquals(2, rails.size, "a ruled strip has two rails")
        for (rail in rails) {
            val chain = assertNotNull(rail.geom as? EdgeGeom.InSpace, "${rail.name.label.render()} is a curve in space").chain
            val tol = assertNotNull(rail.fitted, "…and it says how far it may be")
            assertTrue(chain.size >= 2, "…and it took more than one span to get there")
            var worstKnot = 0.0
            for (span in chain) worstKnot = kotlin.math.max(worstKnot, offCylinders(span.start))
            worstKnot = kotlin.math.max(worstKnot, offCylinders(chain.last().end))
            assertTrue(worstKnot <= 1e-8, "every knot is exact on the cylinder the setback is measured along — worst $worstKnot")
            var worstSpan = 0.0
            for (span in chain) {
                for (k in 1 until 16) worstSpan = kotlin.math.max(worstSpan, offCylinders(Frames3.pointAt(span, k / 16.0)))
            }
            assertTrue(worstSpan <= tol + 1e-8, "…and the spans between stand within the stated $tol mm — worst $worstSpan")
        }
        println("bevel | block 40x30x20, two rounds R=4, mitre setback 1 | took $took in [$lo, $hi], rails fitted ${rails[0].fitted}")
    }

    /**
     * **Three sections through the strip close, and the face the two roundings share still opens.** The
     * strip's own cut is the ruled reader: the plane against each ruling is exact, and the chain between two
     * rulings is the strip's own chord.
     */
    @Test
    fun sectionsThroughTheRuledStripCloseAndTheSharedFaceStillOpens() {
        val cx = Construction()
        val two = twoRounds(cx, 4.0, 4.0)
        val mitre = mitreOf(Evaluator().solid(two))
        val body = Evaluator().solid(bevel(cx, two, mitre, 1.0))
        assertManifold(body.mesh, "the ruled strip")
        for (cut in listOf(
            Plane3(Vec3(0.0, 0.0, 19.5), Vec3.X, Vec3.Y) to "level at z = 19.5",
            Plane3(Vec3(0.0, 0.0, 17.5), Vec3.X, Vec3.Y) to "level at z = 17.5",
            Plane3(Vec3(width - 2.0, 0.0, 0.0), Vec3.Y, Vec3.Z) to "vertical through the strip",
            Plane3(Vec3(width - 3.0, depth - 3.0, height - 3.0), Vec3.X, Vec3(0.0, cos(PI / 5), sin(PI / 5))) to "tilted through the strip",
        )) {
            val (regions, whyCut) = Section3.regionsOf(body.feature, cut.first)
            val rs = assertNotNull(regions, "the section ${cut.second} closes: ${whyCut?.render()}")
            assertEquals(1, rs.size, "…in one area")
            println("bevel | section ${cut.second} | closes")
        }
        val top = facesOf(body).first { it.plane?.let { p -> abs(p.normal.normalized().z - 1.0) < 1e-9 && abs(p.origin.z - height) < 1e-9 } == true }
        assertEquals(null, top.reason?.render(), "the face the two roundings share is still one a sketch can be put on")
        assertTrue(top.outline.isNotEmpty(), "…and it still states its own outline")
    }

    /**
     * **The two gesture routes give one body**: the mitre bevelled in the same gesture as the two rounds and
     * the mitre bevelled after them stand at the same volume to the engine's own resolution.
     */
    @Test
    fun bothGestureRoutesGiveOneBody() {
        val one = Construction()
        val twoOne = twoRounds(one, 4.0, 4.0, oneGesture = true)
        val a = Evaluator().solid(bevel(one, twoOne, mitreOf(Evaluator().solid(twoOne)), 1.0))
        val step = Construction()
        val twoStep = twoRounds(step, 4.0, 4.0, oneGesture = false)
        val b = Evaluator().solid(bevel(step, twoStep, mitreOf(Evaluator().solid(twoStep)), 1.0))
        assertManifold(a.mesh, "one gesture")
        assertManifold(b.mesh, "one at a time")
        assertClose(Geom3.volume(a.mesh), Geom3.volume(b.mesh), 1e-7 * Geom3.volume(a.mesh), "the two routes give one body")
    }

    /**
     * **Through the general boolean the strip refuses by name** — the fifth carrier this drawing has not
     * built. A `sits` predicate on a ruled surface is a bilinear solve and the chart is `(station, t)`; until
     * that is there the whole face list says so rather than letting the result's faces go emergent in
     * silence (queued as (5r)).
     */
    @Test
    fun throughTheGeneralBooleanTheStripRefusesByNameAsTheUnbuiltFifthCarrier() {
        assumeTrue(MeshBool.available, "no general boolean engine: ${MeshBool.status}")
        val cx = Construction()
        val two = twoRounds(cx, 4.0, 4.0)
        val mitre = mitreOf(Evaluator().solid(two))
        val bevelled = bevel(cx, two, mitre, 1.0)
        val bored = Evaluator().solid(cx.subtract(bevelled, drill(cx, Vec2(12.0, 12.0), 3.0)))
        assertManifold(bored.mesh, "the bevelled body bored")
        val (faces, whyFaces) = Section3.faces(bored.feature)
        assertEquals(null, faces, "a boolean over a body carrying a ruled strip states no face list yet")
        val said = assertNotNull(whyFaces, "…and it says why").render()
        assertTrue(said.contains("ruled strip"), "…naming the strip: $said")
        assertTrue(said.contains("#") || said.contains("band"), "…and naming the face: $said")
        println("bevel | through the boolean | $said")
    }

    /**
     * **The file is a fixed point and one delete takes the bevel back off** — the gesture is an ordinary
     * `filletedge` step carrying the chamfer's own kind, so the strip costs the format nothing at all.
     */
    @Test
    fun theBevelRoundTripsAndOneUndoGivesItBack() {
        val script = blockScript()
        val once = DocumentFormat.save(DocumentFormat.load(script))
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the whole drawing round-trips byte-equal")

        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(script))
        val both = volumeOf(bodyRef(ed.doc), "the two rounds and the bevel along their mitre")
        val solids = ed.doc.elements.filter { it.kind == ElementKind.SOLID }
        assertEquals(4, solids.size, "the block, each round, and the bevel along the mitre")
        ed.selectElement(solids.last())
        assertTrue(ed.deleteSelection(), "the bevel comes off: ${ed.statusHint}")
        val without = volumeOf(bodyRef(ed.doc), "the two rounds alone")
        assertTrue(without > both, "…and its own removal with it: $without against $both")
        assertTrue(ed.undo(), "the removal is one undo step")
        assertClose(volumeOf(bodyRef(ed.doc), "restored"), both, 1e-9 * both, "one undo gives it back")
    }

    /**
     * **A drawn profile along such a crease is still refused**, and that is what is left of
     * `refusal.blend.carriesNoRigidSection`: a shape drawn in the crease's own normal section has no one
     * section to be stated in, while the two this drawing *does* state there — the ball's canal and the
     * bevel's ruled strip — are named in the same sentence.
     */
    @Test
    fun aDrawnProfileAlongTheSameMitreIsStillRefusedByName() {
        val cx = Construction()
        val two = twoRounds(cx, 4.0, 4.0)
        val rounded = Evaluator().solid(two)
        val mitre = mitreOf(rounded)
        val drawn =
            BlendSection(
                BlendKind.PROFILE,
                1.0,
                listOf(ProfileElement.Seg(constructit.geom.Segment(Vec2(1.0, 0.0), Vec2(0.0, 1.0)))),
            )
        val (choices, why) = Blend3.choicesFor(rounded, listOf(mitre), drawn)
        assertEquals(null, choices, "a drawn profile along a crease with no rigid section is not stated")
        val said = assertNotNull(why, "…and it says so").render()
        assertTrue(said.contains("no rigid section"), "…in the sentence that names the two that are: $said")
        assertTrue(said.contains("canal band") && said.contains("ruled strip"), "…and names them both: $said")
    }

    // ---- the fixtures ----

    private fun twoRounds(
        cx: Construction,
        ra: Double,
        rb: Double,
        oneGesture: Boolean = true,
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
        if (ra == rb && oneGesture) {
            val body = Evaluator().solid(box)
            val choices = assertNotNull(Blend3.choicesFor(body, tops, BlendSection(BlendKind.FILLET, ra)).first, "the two top edges are scored")
            return cx.blendAll(box, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(ra.mm), null, tops, choices)))
        }
        var on = box
        for ((k, e) in tops.withIndex()) on = round(cx, on, e, if (k == 0) ra else rb)
        return on
    }

    private fun mitreOf(solid: Solid3): Int {
        val es = edgesOf(solid)
        return assertNotNull(
            es.indices.firstOrNull { es[it].name is EdgeName.BlendMitre && es[it].reason == null && es[it].geom is EdgeGeom.OnPlane },
            "the two bands cross in a mitre",
        )
    }

    private fun offCylinders(p: Vec3): Double {
        val a = abs(hypot(p.y - (depth - 4.0), p.z - (height - 4.0)) - 4.0)
        val b = abs(hypot(p.x - (width - 4.0), p.z - (height - 4.0)) - 4.0)
        return kotlin.math.min(a, b)
    }

    private fun round(
        cx: Construction,
        on: SolidRef,
        address: Int,
        size: Double,
    ): SolidRef = blend(cx, on, address, size, BlendKind.FILLET)

    private fun bevel(
        cx: Construction,
        on: SolidRef,
        address: Int,
        size: Double,
    ): SolidRef = blend(cx, on, address, size, BlendKind.CHAMFER)

    private fun blend(
        cx: Construction,
        on: SolidRef,
        address: Int,
        size: Double,
        kind: BlendKind,
    ): SolidRef {
        val body = Evaluator().solid(on)
        val (choices, why) = Blend3.choicesFor(body, listOf(address), BlendSection(kind, size))
        assertNotNull(choices, "edge $address is scored: ${why?.render()}")
        return cx.blendAll(on, cx.planeXY(), listOf(Construction.BlendRun(kind, cx.const(size.mm), null, listOf(address), choices)))
    }

    private fun drill(
        cx: Construction,
        at: Vec2,
        r: Double,
    ): SolidRef {
        val c = cx.freePoint("bc", at.x.mm, at.y.mm)
        val circle = cx.region(cx.loop(cx.circleCR(c, cx.const(r.mm))))
        return cx.extrude(cx.sketchOn(cx.plane(Vec3(0.0, 0.0, -5.0), Vec3.X, Vec3.Y), circle), cx.const((height + 10.0).mm))
    }

    /** The block, its two top edges rounded one gesture at a time, and the mitre they cross in bevelled — as a file. */
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
        val choice = assertNotNull(Blend3.choicesFor(Evaluator().solid(on), listOf(mitre), BlendSection(BlendKind.CHAMFER, 1.0)).first, "the mitre is scored")[0]
        sb.append("param \"sc\" = 1mm\n")
        sb.append("tool chamferedge els=e$at clicks=0,0 scalar=\"sc\" signs=$mitre;${choice.signs().joinToString(";")} -> e${at + 1},e${at + 2}\n")
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
