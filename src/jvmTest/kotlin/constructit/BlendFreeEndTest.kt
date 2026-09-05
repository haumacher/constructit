package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.ProfileValue
import constructit.dsl.Construction
import constructit.dsl.PointRef
import constructit.dsl.ProfileRef
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.FaceName
import constructit.geom.Feature3
import constructit.geom.GeomMath
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Section3
import constructit.geom.SolidFace
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.l10n.contains
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A band's free end notches the face its cap stands in** (session 81; the queued section limit (a)).
 *
 * A band that ends without a corner — a fillet along **one** rim edge of a plate — closes on a flat cap
 * standing in the plane square to its own edge, and that plane is a **third** face: the side face at the
 * edge's end, which is neither of the two the band runs between. The cap takes the wedge's own section out
 * of that face — two setbacks and the arc between them, cut from one corner of its outline — and
 * `Blend3.dressedFaces` corrected the outlines of the band's *own* two faces only. So the end face kept a
 * stale rectangle, a level section crossed a boundary that is not where the drawing said it was, and the
 * loop did not close: *"the plane's section of this solid does not close into an area"*.
 *
 * The cure is the same analytic correction, one face further out. The wedge's section is already stated
 * exactly in the crease's own frame, and at a free end of a straight edge that frame **lies in** the end
 * face's plane — the cap is square to the edge and so is the face — so the notch is that section carried
 * through one rigid map, and the correction is the ring's corner replaced by *setback → section → setback*:
 * line against line and line against circle, nothing sampled, nothing new.
 *
 * Two more things came with it, and both are the same limit read one edge further along.
 *
 * - **A notch composes with the trims, and only at the tip.** A trim is a strip of constant width, so two
 *   of them on one piece are one strip and each level can take its own off the level below's answer. A
 *   notch is a *corner* replaced by a curve, and a later level offsetting the piece beside it would have to
 *   re-solve a junction against an arc **tangent** to that piece — two solutions equally far from the corner
 *   they replace. So the trims compose down the chain and the notches are cut once, at the tip, over the
 *   whole chain's free ends. Which is also the only right reading: an end that a later gesture turns into a
 *   corner never gets a notch at all.
 * - **Two bands that meet at a free vertex trim each other**, and the drawing has to say so. Two adjacent
 *   rim edges rounded to sizes that are not congruent build no corner (session 79's cut 2), so the boolean
 *   trims the two bands against each other — exactly, every time — while `Blend3.spanOf` still ran each of
 *   them the whole length of its edge. That is the other half of what kept the *"two sizes"* section open.
 */
class BlendFreeEndTest {
    // ---- the plumbing ----

    private var ids = 0

    private fun prism(
        cx: Construction,
        xy: List<Vec2>,
        h: Double,
    ): SolidRef {
        val pts = xy.map { cx.freePoint("p${ids++}", it.x.mm, it.y.mm) }
        val segs = xy.indices.map { cx.segment(pts[it], pts[(it + 1) % xy.size]) }
        return cx.extrude(cx.sketchOn(cx.planeXY(), cx.region(cx.loop(*segs.toTypedArray()))), cx.const(h.mm))
    }

    private fun pt(
        cx: Construction,
        p: Vec2,
    ): PointRef = cx.freePoint("q${ids++}", p.x.mm, p.y.mm)

    private fun polyline(
        cx: Construction,
        xy: List<Vec2>,
    ): ProfileRef {
        val pts = xy.map { pt(cx, it) }
        return cx.profile(*xy.indices.drop(1).map { cx.segment(pts[it - 1], pts[it]) }.toTypedArray())
    }

    private fun sectionOf(profile: ProfileRef): List<ProfileElement> =
        ((Evaluator().eval(profile.node) as EvalResult.Ok).value as ProfileValue).profile.elements

    /** The index of the one-piece edge running from [a] to [b] (either way round). */
    private fun edgeIndex(
        base: SolidRef,
        a: Vec3,
        b: Vec3,
    ): Int {
        val edges = assertNotNull(Section3.edges(Evaluator().solid(base).feature).first, "the solid names its edges")
        val at =
            edges.indices.firstOrNull { i ->
                val el = Blend3.edgePath(edges[i]).first?.elements?.singleOrNull() ?: return@firstOrNull false
                ((el.start - a).length() <= 1e-6 && (el.end - b).length() <= 1e-6) ||
                    ((el.start - b).length() <= 1e-6 && (el.end - a).length() <= 1e-6)
            }
        assertNotNull(at, "an edge runs from $a to $b")
        return at
    }

    /** One rounding, scored the way the tool scores it and then handed to the node verbatim (OP-1/OP-18). */
    private fun round(
        cx: Construction,
        base: SolidRef,
        edge: Int,
        kind: BlendKind,
        size: Double,
    ): SolidRef {
        val sec = BlendSection(kind, size)
        val (choices, why) = Blend3.choicesFor(Evaluator().solid(base), listOf(edge), sec)
        assertNotNull(choices, why?.render())
        return cx.blend(base, base, cx.planeXY(), cx.const(size.mm), kind, whole = false, address = edge, choices = choices)
    }

    private fun rounded(
        cx: Construction,
        base: SolidRef,
        profile: ProfileRef,
        edge: Int,
    ): SolidRef {
        val sec = BlendSection(BlendKind.PROFILE, 0.0, sectionOf(profile))
        val (choices, why) = Blend3.choicesFor(Evaluator().solid(base), listOf(edge), sec)
        assertNotNull(choices, why?.render())
        return cx.blend(base, base, cx.planeXY(), null, BlendKind.PROFILE, false, edge, choices, run = listOf(edge), profile = profile)
    }

    private fun featureOf(ref: SolidRef): Feature3 = Evaluator().solid(ref).feature

    /** The area the level section at [z] closes into — refusing to guess if it does not close. */
    private fun areaAt(
        f: Feature3,
        z: Double,
    ): Double {
        val (regions, why) = Section3.regionsOf(f, Plane3(Vec3(0.0, 0.0, z), Vec3.X, Vec3.Y))
        val areas = assertNotNull(regions, "the section at z=$z closes into an area: ${why?.render()}")
        assertEquals(1, areas.size, "one area at z=$z")
        // **exactly**, which is the whole point: [GeomMath.signedArea] integrates an arc in closed form,
        // so a chord anywhere in the answer would show up as a shortfall rather than hide in a tolerance
        var area = 0.0
        for (r in areas) {
            area += abs(GeomMath.signedArea(r.outer))
            for (h in r.holes) area -= abs(GeomMath.signedArea(h))
        }
        return area
    }

    /** What a round of radius [r] has taken off the side face at depth [d] under the rim it runs along. */
    private fun cutBack(
        r: Double,
        d: Double,
    ): Double = r - sqrt(r * r - (r - d) * (r - d))

    private fun faceNamed(
        f: Feature3,
        name: FaceName,
    ) = assertNotNull(
        assertNotNull(Section3.faces(f).first, "the body names its faces").firstOrNull { it.name == name },
        "$name is one of them",
    )

    private val plate60 = listOf(Vec2(0.0, 0.0), Vec2(60.0, 0.0), Vec2(60.0, 40.0), Vec2(0.0, 40.0))

    // ---- the report itself ----

    /**
     * **One free-ended fillet, and the level section closes on the figure the radius states.**
     *
     * A 60 × 40 × 20 plate with a 4 mm round along the rim edge over `y = 0`. At `z = 18` — two millimetres
     * under the rim — the band has taken `r − √(r² − (r−2)²)` off the side face, so the section is
     * `60 × (40 − (4 − 2√3))` and nothing else: the two end faces are notched exactly where the caps stand,
     * and the ends of the band's own rulings land on them.
     */
    @Test
    fun oneFreeEndedFilletSectionsExactly() {
        val cx = Construction()
        val plate = prism(cx, plate60, 20.0)
        val rim = edgeIndex(plate, Vec3(0.0, 0.0, 20.0), Vec3(60.0, 0.0, 20.0))
        val f = featureOf(round(cx, plate, rim, BlendKind.FILLET, 4.0))

        assertClose(areaAt(f, 18.0), 60.0 * (40.0 - cutBack(4.0, 2.0)), 1e-9, "2 mm under a 4 mm rim")
        assertClose(areaAt(f, 17.0), 60.0 * (40.0 - cutBack(4.0, 3.0)), 1e-9, "…and 3 mm under it")
        assertClose(areaAt(f, 10.0), 60.0 * 40.0, 1e-9, "…and below the band the plate is whole")

        // the notch itself: the cap at each end takes a quarter of the ball out of the side face's corner,
        // stated as the arc it is and not as a chord anywhere
        for (side in listOf(FaceName.Side(1), FaceName.Side(3))) {
            val patch = faceNamed(f, side)
            assertEquals(null, patch.reason?.render(), "$side is drawable")
            val arcs = patch.outline.filterIsInstance<ProfileElement.ArcE>()
            assertEquals(1, arcs.size, "$side carries the cap's own arc: ${patch.outline}")
            assertClose(arcs[0].arc.radius, 4.0, 1e-12, "…of the rounding's own radius")
            assertClose(abs(GeomMath.sweep(arcs[0].arc)), PI / 2.0, 1e-12, "…a quarter of it")
            assertEquals(5, patch.outline.size, "…spliced into the ring, four pieces becoming five")
        }
    }

    /** The same free end **bevelled**: the notch is the bevel, so the side faces gain a straight run. */
    @Test
    fun aChamferFreeEndSectionsExactly() {
        val cx = Construction()
        val plate = prism(cx, plate60, 20.0)
        val rim = edgeIndex(plate, Vec3(0.0, 0.0, 20.0), Vec3(60.0, 0.0, 20.0))
        val f = featureOf(round(cx, plate, rim, BlendKind.CHAMFER, 4.0))

        // a bevel's inset at depth d under the rim is `c − d`, so it runs out exactly at the setback
        assertClose(areaAt(f, 18.0), 60.0 * (40.0 - 2.0), 1e-9, "2 mm under a 4 mm bevel")
        assertClose(areaAt(f, 17.5), 60.0 * (40.0 - 1.5), 1e-9, "…and 2.5 mm under it")

        val patch = faceNamed(f, FaceName.Side(3))
        assertEquals(null, patch.reason?.render(), "the end face is drawable")
        assertEquals(5, patch.outline.size, "the bevel is spliced into the ring: ${patch.outline}")
        assertTrue(patch.outline.all { it is ProfileElement.Seg }, "…and every piece of it is straight")
    }

    /** …and a **drawn** free end: the notch is whatever chain the user drew, piece for piece. */
    @Test
    fun aDrawnProfileFreeEndSectionsExactly() {
        val cx = Construction()
        val plate = prism(cx, plate60, 20.0)
        val rim = edgeIndex(plate, Vec3(0.0, 0.0, 20.0), Vec3(60.0, 0.0, 20.0))
        // a two-piece rasp: 6 mm along the top face, 3 mm down the side, with a step half way
        val drawn = listOf(Vec2(6.0, 0.0), Vec2(2.0, 1.0), Vec2(0.0, 3.0))
        val f = featureOf(rounded(cx, plate, polyline(cx, drawn), rim))

        // the drawn chain read in the corner's own frame: x is the setback along the top face, y the depth
        // down the side, so at 1 mm down the profile stands at x = 2 and at 2 mm down half way to (0, 3)
        assertClose(areaAt(f, 19.0), 60.0 * (40.0 - 2.0), 1e-9, "1 mm under the rim the rasp stands at 2 mm in")
        assertClose(areaAt(f, 18.0), 60.0 * (40.0 - 1.0), 1e-9, "…and 2 mm under it, half way down the second run")

        val patch = faceNamed(f, FaceName.Side(1))
        assertEquals(null, patch.reason?.render(), "the end face is drawable")
        assertEquals(6, patch.outline.size, "both of the drawn chain's pieces are spliced in: ${patch.outline}")
    }

    /**
     * **Two adjacent rim edges at 4 mm and 3 mm**, which is what the *"two sizes"* symptom always was. No
     * corner is built between two sections that are not congruent, so each band has a free end at the
     * vertex — and the two bands **run into each other** there, which the drawing now says.
     *
     * At `z = 18` the answer is the rectangle the two radii state: `(60 − (3 − 2√2)) × (40 − (4 − 2√3))`,
     * to the last bit, and the chain answers exactly what one pass does.
     */
    @Test
    fun twoAdjacentRimEdgesOfDifferentSizesSectionExactly() {
        val cx = Construction()
        val plate = prism(cx, plate60, 20.0)
        val alongY = edgeIndex(plate, Vec3(0.0, 0.0, 20.0), Vec3(60.0, 0.0, 20.0))
        val alongX = edgeIndex(plate, Vec3(60.0, 0.0, 20.0), Vec3(60.0, 40.0, 20.0))
        val chain = featureOf(round(cx, round(cx, plate, alongY, BlendKind.FILLET, 4.0), alongX, BlendKind.FILLET, 3.0))

        val exact = (60.0 - cutBack(3.0, 2.0)) * (40.0 - cutBack(4.0, 2.0))
        assertClose(areaAt(chain, 18.0), exact, 1e-9, "the two radii's own figure")

        // …and the other order, which must be the same drawing
        val cx2 = Construction()
        val plate2 = prism(cx2, plate60, 20.0)
        val y2 = edgeIndex(plate2, Vec3(0.0, 0.0, 20.0), Vec3(60.0, 0.0, 20.0))
        val x2 = edgeIndex(plate2, Vec3(60.0, 0.0, 20.0), Vec3(60.0, 40.0, 20.0))
        val other = featureOf(round(cx2, round(cx2, plate2, x2, BlendKind.FILLET, 3.0), y2, BlendKind.FILLET, 4.0))
        assertEquals(areaAt(chain, 18.0), areaAt(other, 18.0), "the other order answers the same, to the last bit")
    }

    /**
     * **A whole-face rounding is untouched**: a closed chain has no free end at all, so no notch is derived
     * and every face's boundary is the one it had. The level section is exact, as it already was.
     */
    @Test
    fun aWholeFaceRoundingIsUntouched() {
        val cx = Construction()
        val plate = prism(cx, plate60, 20.0)
        val rim =
            listOf(
                Vec3(0.0, 0.0, 20.0) to Vec3(60.0, 0.0, 20.0),
                Vec3(60.0, 0.0, 20.0) to Vec3(60.0, 40.0, 20.0),
                Vec3(60.0, 40.0, 20.0) to Vec3(0.0, 40.0, 20.0),
                Vec3(0.0, 40.0, 20.0) to Vec3(0.0, 0.0, 20.0),
            ).map { edgeIndex(plate, it.first, it.second) }
        var out = plate
        for (e in rim) out = round(cx, out, e, BlendKind.FILLET, 4.0)
        val f = featureOf(out)

        val back = cutBack(4.0, 2.0)
        assertClose(areaAt(f, 18.0), (60.0 - 2 * back) * (40.0 - 2 * back), 1e-9, "the rounded rectangle the balls say")
        for (side in listOf(FaceName.Side(0), FaceName.Side(1), FaceName.Side(2), FaceName.Side(3))) {
            val patch = faceNamed(f, side)
            assertEquals(4, patch.outline.size, "$side is the rectangle it always was: ${patch.outline}")
            assertTrue(patch.outline.all { it is ProfileElement.Seg }, "…with no arc spliced into it")
        }
    }

    /**
     * **Where two notches reach past each other the face says so, with the size that fits** (OP-3).
     *
     * A plate only 5 mm thick with a 4 mm round on the top rim over one side and a 3 mm round on the bottom
     * rim over the same side: the two caps both notch the end face, at the two ends of the same 5 mm
     * upright, and `4 + 3` does not fit in `5`. The refusal names the rounding and the fraction of its own
     * size the boundary leaves room for, and it heals when the sizes come down.
     */
    @Test
    fun twoNotchesThatReachPastEachOtherSayHowMuchFits() {
        val cx = Construction()
        val plate = prism(cx, plate60, 5.0)
        val top = edgeIndex(plate, Vec3(0.0, 0.0, 5.0), Vec3(60.0, 0.0, 5.0))
        val bottom = edgeIndex(plate, Vec3(0.0, 0.0, 0.0), Vec3(60.0, 0.0, 0.0))
        val f = featureOf(round(cx, round(cx, plate, top, BlendKind.FILLET, 4.0), bottom, BlendKind.FILLET, 3.0))

        val said = assertNotNull(faceNamed(f, FaceName.Side(3)).reason?.render(), "the end face says why it is not drawable")
        assertTrue("ends free in a face of its own" in said, "it names the free end: $said")
        assertTrue("the largest that fits there is" in said, "…and the size that would: $said")
        val fits = assertNotNull(Regex("about ([0-9.]+) mm").find(said)?.groupValues?.get(1)?.toDoubleOrNull(), "a number: $said")
        assertTrue(fits > 0.0 && fits < 3.0, "…smaller than the one asked for: $fits")

        // …and it heals: 1 mm and 1 mm leave 3 mm of the upright standing between the two notches
        val cx2 = Construction()
        val plate2 = prism(cx2, plate60, 5.0)
        val t2 = edgeIndex(plate2, Vec3(0.0, 0.0, 5.0), Vec3(60.0, 0.0, 5.0))
        val b2 = edgeIndex(plate2, Vec3(0.0, 0.0, 0.0), Vec3(60.0, 0.0, 0.0))
        val healed = featureOf(round(cx2, round(cx2, plate2, t2, BlendKind.FILLET, 1.0), b2, BlendKind.FILLET, 1.0))
        val patch = faceNamed(healed, FaceName.Side(3))
        assertEquals(null, patch.reason?.render(), "at 1 mm the end face is drawable")
        assertEquals(2, patch.outline.filterIsInstance<ProfileElement.ArcE>().size, "…and carries both notches")
    }

    // ---- a face notched at more than one of its corners ----

    private fun Editor.click(world: Vec2) {
        val at = camera.worldToScreen(world)
        pointerMove(at)
        pointerDown(at)
        pointerUp(at)
    }

    /**
     * The plate with its rim rounded by **one dressing of several entries**, the way the tool makes them
     * (OP-30) — one `Feature3.Blend` with a section per target, not a chain.
     */
    private fun onePass(vararg gestures: Pair<Vec2, Double>): Feature3 {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 40.0))
        ed.activeScalar = ed.doc.newParameter("depth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(30.0, 0.0))
        for ((k, g) in gestures.withIndex()) {
            ed.activeScalar = ed.doc.newParameter("r$k", g.second.mm)
            ed.setTool(Tools.BLEND_EDGE)
            ed.click(g.first)
        }
        val solid = ed.doc.elements.last { it.kind == ElementKind.SOLID }
        val f = Evaluator().solid(solid.ref as SolidRef).feature
        val blend = assertNotNull(f as? Feature3.Blend, "one dressing")
        assertEquals(gestures.size, blend.targets.size, "…of ${gestures.size} entries, and no chain: ${blend.base}")
        assertTrue(blend.base !is Feature3.Blend, "…in one pass")
        return f
    }

    /** How far the level section at [z] reaches across the plate in `y` — the two insets, told apart. */
    private fun spanAt(
        f: Feature3,
        z: Double,
    ): Pair<Double, Double> {
        val (regions, why) = Section3.regionsOf(f, Plane3(Vec3(0.0, 0.0, z), Vec3.X, Vec3.Y))
        val areas = assertNotNull(regions, "the section at z=$z closes: ${why?.render()}")
        val pts = areas.flatMap { it.outer.elements }.flatMap { listOf(GeomMath.startOf(it), GeomMath.endOf(it)) }
        return pts.minOf { it.y } to pts.maxOf { it.y }
    }

    /**
     * **A face notched at two of its corners.** Round the rim over `y = 0` at 4 mm and the rim over
     * `y = 40` at 3 mm: the two bands are parallel and make no corner with each other, so **each** of the
     * two end faces (`x = 0` and `x = 60`) is notched at *both* of its top corners by two different
     * sections, and the two notches share the piece between them. The section at `z = 18` is the plate
     * less both insets; at `z = 16.5`, below the 3 mm band's own foot, only the 4 mm one is left, and
     * which side it is on is asserted so the two cannot trade places.
     *
     * Both routes, because they are different code: one dressing of two entries (what the tool makes) and
     * a chain of two, which must answer the same to the last bit (OP-30).
     */
    @Test
    fun aFaceNotchedAtTwoCornersSectionsExactly() {
        val pass = onePass(Vec2(30.0, 0.0) to 4.0, Vec2(30.0, 40.0) to 3.0)

        val exact = 60.0 * (40.0 - cutBack(4.0, 2.0) - cutBack(3.0, 2.0))
        assertClose(areaAt(pass, 18.0), exact, 1e-9, "both insets, 2 mm under the two rims")
        assertClose(spanAt(pass, 18.0).first, cutBack(4.0, 2.0), 1e-9, "the 4 mm band is the one over y = 0")
        assertClose(spanAt(pass, 18.0).second, 40.0 - cutBack(3.0, 2.0), 1e-9, "…and the 3 mm band the one over y = 40")

        val lower = 60.0 * (40.0 - cutBack(4.0, 3.5))
        assertClose(areaAt(pass, 16.5), lower, 1e-9, "below the 3 mm band's foot only the 4 mm inset is left")
        assertClose(spanAt(pass, 16.5).second, 40.0, 1e-9, "…and the far side is the plate's own edge again")

        // …and the same body as a chain of two roundings, to the last bit
        val cx = Construction()
        val plate = prism(cx, plate60, 20.0)
        val near = edgeIndex(plate, Vec3(0.0, 0.0, 20.0), Vec3(60.0, 0.0, 20.0))
        val far = edgeIndex(plate, Vec3(0.0, 40.0, 20.0), Vec3(60.0, 40.0, 20.0))
        val chain = featureOf(round(cx, round(cx, plate, near, BlendKind.FILLET, 4.0), far, BlendKind.FILLET, 3.0))
        assertEquals(areaAt(pass, 18.0), areaAt(chain, 18.0), "the chain answers what one pass does")
        assertEquals(areaAt(pass, 16.5), areaAt(chain, 16.5), "…at the lower height too")

        // the face itself: four pieces became six, one arc per notch, and nothing else moved
        for (side in listOf(FaceName.Side(1), FaceName.Side(3))) {
            val patch = faceNamed(pass, side)
            assertEquals(null, patch.reason?.render(), "$side is drawable")
            assertEquals(6, patch.outline.size, "$side carries both notches: ${patch.outline}")
            val radii = patch.outline.filterIsInstance<ProfileElement.ArcE>().map { it.arc.radius }.sorted()
            assertEquals(listOf(3.0, 4.0), radii, "…one arc of each radius")
        }
    }

    /** The same two notches with the radii **swapped**, which must move the inset to the other side. */
    @Test
    fun theSameTwoNotchesWithTheRadiiSwapped() {
        val pass = onePass(Vec2(30.0, 0.0) to 3.0, Vec2(30.0, 40.0) to 4.0)

        val exact = 60.0 * (40.0 - cutBack(3.0, 2.0) - cutBack(4.0, 2.0))
        assertClose(areaAt(pass, 18.0), exact, 1e-9, "the same two insets, the other way round")
        assertClose(spanAt(pass, 18.0).first, cutBack(3.0, 2.0), 1e-9, "the 3 mm band is now the one over y = 0")
        assertClose(spanAt(pass, 18.0).second, 40.0 - cutBack(4.0, 2.0), 1e-9, "…and the 4 mm band the one over y = 40")

        assertClose(areaAt(pass, 16.5), 60.0 * (40.0 - cutBack(4.0, 3.5)), 1e-9, "below the 3 mm foot, only the 4 mm inset")
        assertClose(spanAt(pass, 16.5).first, 0.0, 1e-9, "…and it is on the far side now")
        assertClose(spanAt(pass, 16.5).second, 40.0 - cutBack(4.0, 3.5), 1e-9, "…measured from y = 40")
    }

    /**
     * **Three of one face's four corners notched.** The two end faces of a plate have four corners each, one
     * for every rim edge running across them: round three of those rims — the top over `y = 0` at 4 mm, the
     * top over `y = 40` at 3 mm and the **bottom** over `y = 0` at 2 mm — and each end face is spliced three
     * times, at three corners of one ring, with two of the notches sharing the upright between them.
     */
    @Test
    fun aFaceNotchedAtThreeOfItsFourCornersSectionsExactly() {
        val cx = Construction()
        val plate = prism(cx, plate60, 20.0)
        var out = plate
        for (
        (a, b, r) in
        listOf(
            Triple(Vec3(0.0, 0.0, 20.0), Vec3(60.0, 0.0, 20.0), 4.0),
            Triple(Vec3(0.0, 40.0, 20.0), Vec3(60.0, 40.0, 20.0), 3.0),
            Triple(Vec3(0.0, 0.0, 0.0), Vec3(60.0, 0.0, 0.0), 2.0),
        )
        ) {
            out = round(cx, out, edgeIndex(out, a, b), BlendKind.FILLET, r)
        }
        val f = featureOf(out)

        assertClose(areaAt(f, 18.0), 60.0 * (40.0 - cutBack(4.0, 2.0) - cutBack(3.0, 2.0)), 1e-9, "the two top insets")
        assertClose(areaAt(f, 1.0), 60.0 * (40.0 - cutBack(2.0, 1.0)), 1e-9, "…the bottom one, 1 mm over its rim")
        assertClose(spanAt(f, 1.0).first, cutBack(2.0, 1.0), 1e-9, "…on the y = 0 side, where its rim is")
        assertClose(areaAt(f, 10.0), 60.0 * 40.0, 1e-9, "…and between the bands the plate is whole")

        for (side in listOf(FaceName.Side(1), FaceName.Side(3))) {
            val patch = faceNamed(f, side)
            assertEquals(null, patch.reason?.render(), "$side is drawable")
            assertEquals(7, patch.outline.size, "$side carries all three notches: ${patch.outline}")
            assertEquals(
                listOf(2.0, 3.0, 4.0),
                patch.outline.filterIsInstance<ProfileElement.ArcE>().map { it.arc.radius }.sorted(),
                "…one arc of each radius",
            )
        }
    }

    // ---- the reporter's own chevron (GitHub #33), whose tip fillet ends in the two caps ----

    /** GitHub #33's script, verbatim — the 19.4° tip upright rounded at 2 mm. */
    private val issue33 =
        """
constructit 4
point -69.47203733974055,-45.25896945666625 -> e1
point -26.451147843545805,-97.56526864553598 -> e2
tool segment pts=e1,e2 clicks=-70.875,-36.375;-45.625,-85.625 -> e3
point -23.282652808360613,-41.472580496979674 -> e4
tool segment pts=e1,e4 clicks=-70.875,-36.375;2.875,-30.625 -> e5
point -54.515412407715694,-55.42163010985958 -> e6
tool segment pts=e4,e6 clicks=2.125,-31.375;3.125,-64.125 -> e7
tool segment pts=e6,e2 clicks=1.625,-63.125;-47.625,-84.375 -> e8
param "r" = 5mm
tool fillet els=e3,e5 clicks=-64.875,-47.625;-46.875,-35.125 scalar="r" signs=1;1 -> e9
tool fillet els=e7,e8 clicks=2.375,-57.375;-8.125,-69.875 scalar="r" signs=-1;1 -> e10
tool outline els=e3,e8,e10,e7,e5,e9 clicks=-68.375,-57.625;-53.875,-62.875;-60.10651566019921,-43.78172408508907;-26.874580888061868,-35.96175687686606;-37.43412097231523,-30.847088820784105;-82.21156720852939,-33.881151691321946 -> e11,e12,e13,e14,e15,e16,e17
param "h" = 20mm
tool extrude els=e17 clicks=-71.625,-51.625 scalar="h" -> e18
param "r2" = 2mm
tool filletedge els=e18 clicks=-28.62800083557761,-21.44546916378542 scalar="r2" signs=4;-1;1;0;1 -> e19
"""
            .trimStart()

    private fun featureNamed(
        text: String,
        name: String,
    ): Feature3 {
        val doc = DocumentFormat.load(text)
        val el = assertNotNull(doc.elements.firstOrNull { doc.nameOf(it) == name }, "$name is in the drawing")
        return Evaluator().solid(el.ref as SolidRef).feature
    }

    /**
     * **The chevron's tip fillet ends in the two caps, and both are notched.**
     *
     * The reporter rounded an **upright**, so its band's two free ends stand in the top and the bottom cap
     * rather than in a side face — the same construction, one face family over. The tip's own wedge comes
     * out of each cap's outline as the 2D fillet it is, so the caps' area is the plan's own less
     * `r²(cot(θ/2) − (π−θ)/2)` at the 19.4° tip; and because the band is a vertical cylinder the level
     * section is that same figure at every height, which is asserted at two of them.
     */
    @Test
    fun theChevronsTipFilletNotchesBothCapsAndSectionsExactly() {
        val plate = featureNamed(issue33, "e18")
        val rounded = featureNamed(issue33, "e19")

        // the tip's interior angle, read off the reporter's own three points
        val tip = Vec2(-23.282652808360613, -41.472580496979674)
        val toA = Vec2(-69.47203733974055, -45.25896945666625) - tip
        val toB = Vec2(-54.515412407715694, -55.42163010985958) - tip
        val theta = kotlin.math.acos(toA.dot(toB) / (toA.length() * toB.length()))
        val wedge = 2.0 * 2.0 * (1.0 / kotlin.math.tan(theta / 2.0) - (PI - theta) / 2.0)

        // the band is a vertical cylinder, so a level plane cuts it **square to its own rulings** — the one
        // cut this drawing states as the sampled family it is (OP-15's approximated class, 64 steps), so the
        // figure is two-sided: never more than the wedge, never less by more than the chords. What *is* exact
        // is that it does not move with the height, which is the statement that the band is that cylinder.
        val chords = 2.0 * 2.0 * (PI - theta) * (PI - theta) * (PI - theta) / (12.0 * 63.0 * 63.0)
        for (z in listOf(5.0, 15.0)) {
            val took = areaAt(plate, z) - areaAt(rounded, z)
            assertTrue(took >= wedge - 1e-9, "the tip's own wedge at z=$z: $took against $wedge")
            assertTrue(took <= wedge + chords, "…and nothing else at z=$z: $took against ${wedge + chords}")
        }
        assertEquals(areaAt(rounded, 5.0), areaAt(rounded, 15.0), "the same figure at every height, to the last bit")

        for (cap in listOf(FaceName.Cap(SolidFace.TOP), FaceName.Cap(SolidFace.BOTTOM))) {
            val patch = faceNamed(rounded, cap)
            assertEquals(null, patch.reason?.render(), "$cap is drawable")
            val arcs = patch.outline.filterIsInstance<ProfileElement.ArcE>()
            assertTrue(arcs.any { abs(it.arc.radius - 2.0) <= 1e-9 }, "$cap carries the tip's own 2 mm arc: ${patch.outline}")
        }
    }

    /** …and the reporter's file is still a fixed point of save (OP-18), which the notch must not disturb. */
    @Test
    fun theReportersFileStillRoundTripsByteForByte() {
        val once = DocumentFormat.save(DocumentFormat.load(issue33))
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save -> load -> save is byte-equal")
    }
}
