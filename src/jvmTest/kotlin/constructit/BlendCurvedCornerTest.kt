package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.LoftPart
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.FaceName
import constructit.geom.Geom3
import constructit.geom.Revolve3
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.units.Dimension
import constructit.units.Quantity
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The corner where a curved edge takes part, and the two uprights the pivot cannot follow** (OP-31, slice
 * 5e — the fitted tier's fifth slice; GitHub #36). Session 79's two named cuts, met at last.
 *
 * *The rolling ball is one ball.* Session 80 wrote the inside corner as the band's **own section revolved**
 * about the sharp upright; the ball's own statement is more general and still exact — the corner surface is
 * the **horn torus** the ball sweeps about that upright (tube `r`, centre circle `r`), and each band's end
 * section is an arc of that torus's own meridian circle between its two tangencies. Nothing about the two
 * runs enters it: a band along a **circular** crease ends on the meridian plane just as a straight one ends
 * on the plane square to its run, so the two lie on the same torus and the patch between them is exact. That
 * is why a circular crease is a corner participant here, and why nothing about a congruent pair moved.
 *
 * *And session 79's cut (2) is retired by an argument rather than by a construction.* At a **sharp** upright
 * square to the face two roundings share, the two wedges are congruent **by construction**: each one's plane
 * is the meridian plane through the upright's own axis, in which the shared face cuts a line square to that
 * axis and the other face — containing the whole axis — cuts the axis itself. So two roundings of one size
 * and kind cannot be incongruent there. *"Two wedges that are not congruent"* at an inside corner is
 * therefore either two roundings of **unlike size or kind** (slice 5a's ledge, built) or an upright that is
 * **not** one straight run square to the shared face — the slanted one a loft's inside corner has and the
 * **ring** a revolve's cap corner has, which session 81 parked and which this slice refuses by name, in one
 * sentence, with the canal surface slice (5f) owes named in it.
 *
 * *What is exact and what is not.* The corner is exact — a horn torus, cut by `Revolve3`'s own table. A
 * curved band's **free end** notches the face it stands in, exactly, through the rigid map the meridian
 * plane gives (session 81's cut said that map is not rigid; at a circular edge it is). The one thing left
 * unnamed is the **crease** where a torus band and a cylinder band cross at a *convex* corner: the body is
 * right and the boolean trims it exactly, as it always did, and stating the quartic between them is the
 * slice's own recorded cut.
 */
class BlendCurvedCornerTest {
    private var ids = 0

    private fun ang(deg: Double) = Quantity(deg * PI / 180.0, Dimension.ANGLE)

    // ---- the fixtures ----

    /** A 90° pie slice of a 30 mm disc, 20 mm deep: two radius edges and an arc on each cap. */
    private fun sector(cx: Construction): SolidRef {
        val c = cx.freePoint("c${ids++}", 0.0.mm, 0.0.mm)
        val arc = cx.arc(c, cx.const(RADIUS.mm), cx.const(ang(0.0)), cx.const(ang(90.0)), true)
        val p0 = cx.freePoint("p${ids++}", RADIUS.mm, 0.0.mm)
        val p1 = cx.freePoint("p${ids++}", 0.0.mm, RADIUS.mm)
        return extruded(cx, cx.region(cx.loop(cx.segment(c, p0), arc, cx.segment(p1, c))), HEIGHT)
    }

    /**
     * A **keyhole**: a 15 mm disc about `(0, 20)` standing on a 10 mm wide stem, 20 mm deep.
     *
     * Where the stem's side runs into the disc the plan turns a **reflex** corner between a straight edge and
     * a circular one — the ordinary shape of a boss meeting a wall, and the fixture the inside corner of this
     * slice is asserted on.
     */
    private fun keyhole(cx: Construction): SolidRef {
        val yj = 20.0 - sqrt(DISC * DISC - 25.0)
        val a = cx.freePoint("k${ids++}", (-5.0).mm, 0.0.mm)
        val b = cx.freePoint("k${ids++}", 5.0.mm, 0.0.mm)
        val c = cx.freePoint("k${ids++}", 5.0.mm, yj.mm)
        val d = cx.freePoint("k${ids++}", (-5.0).mm, yj.mm)
        val centre = cx.freePoint("k${ids++}", 0.0.mm, 20.0.mm)
        val a0 = atan2(yj - 20.0, 5.0) * 180.0 / PI
        val a1 = atan2(yj - 20.0, -5.0) * 180.0 / PI
        val arc = cx.arc(centre, cx.const(DISC.mm), cx.const(ang(a0)), cx.const(ang(a1)), true)
        return extruded(cx, cx.region(cx.loop(cx.segment(a, b), cx.segment(b, c), arc, cx.segment(d, a))), HEIGHT)
    }

    /** A loft between two L-shaped sections: its cap's reflex corner stands on a **slanted** upright. */
    private fun lofted(cx: Construction): SolidRef {
        val lo = listOf(Vec2(0.0, 0.0), Vec2(60.0, 0.0), Vec2(60.0, 30.0), Vec2(30.0, 30.0), Vec2(30.0, 60.0), Vec2(0.0, 60.0))
        return cx.loft(
            listOf(
                LoftPart.Area(cx.sketchOn(cx.planeXY(), polygon(cx, lo))),
                LoftPart.Area(cx.sketchOn(cx.planeOffset(cx.planeXY(), cx.const(HEIGHT.mm)), polygon(cx, lo.map { it * 0.6 }))),
            ),
        )
    }

    /** An L-shaped meridian turned 90°: its cap's reflex corner stands on a **ring**. */
    private fun turned(cx: Construction): SolidRef {
        val prof = listOf(Vec2(0.0, 10.0), Vec2(0.0, 30.0), Vec2(10.0, 30.0), Vec2(10.0, 20.0), Vec2(20.0, 20.0), Vec2(20.0, 10.0))
        val o = cx.freePoint("Ro${ids++}", 0.mm, 0.mm)
        val axis = cx.direction(o, cx.freePoint("Rx${ids++}", 1.mm, 0.mm))
        return cx.revolve(cx.sketchOn(cx.planeXY(), polygon(cx, prof)), o, axis, cx.const(ang(90.0)))
    }

    private fun polygon(
        cx: Construction,
        pts: List<Vec2>,
    ): RegionRef {
        val ps = pts.map { cx.freePoint("L${ids++}", it.x.mm, it.y.mm) }
        return cx.region(cx.loop(*ps.indices.map { cx.segment(ps[it], ps[(it + 1) % ps.size]) }.toTypedArray()))
    }

    private fun extruded(
        cx: Construction,
        region: RegionRef,
        h: Double,
    ): SolidRef = cx.extrude(cx.sketchOn(cx.planeXY(), region), cx.const(h.mm))

    // ---- running one rounding ----

    private fun round(
        cx: Construction,
        on: SolidRef,
        address: List<Int>,
        size: Double,
        kind: BlendKind = BlendKind.FILLET,
    ): Pair<SolidRef?, String?> {
        val body = Evaluator().solid(on)
        val (choices, why) = Blend3.choicesFor(body, address, BlendSection(kind, size))
        if (choices == null) return null to (why?.render() ?: "no choice")
        val ref = cx.blendAll(on, cx.planeXY(), listOf(Construction.BlendRun(kind, cx.const(size.mm), null, address, choices)))
        val r = Evaluator().eval(ref.node)
        if (r is EvalResult.Invalid) return null to r.why.render()
        return ref to null
    }

    /** The body [address] makes, asserted watertight, with its volume — or the test fails with the refusal. */
    private fun built(
        on: (Construction) -> SolidRef,
        address: List<Int>,
        size: Double,
        kind: BlendKind = BlendKind.FILLET,
        what: String,
    ): Pair<Solid3, Double> {
        val cx = Construction()
        val (ref, why) = round(cx, on(cx), address, size, kind)
        assertNotNull(ref, "$what builds: $why")
        val solid = Evaluator().solid(ref)
        assertManifold(solid.mesh, what)
        return solid to Geom3.volume(solid.mesh)
    }

    /** The reason [address] is refused, or the test fails because it built. */
    private fun refused(
        on: (Construction) -> SolidRef,
        address: List<Int>,
        size: Double,
        what: String,
    ): String {
        val cx = Construction()
        val (ref, why) = round(cx, on(cx), address, size)
        assertTrue(ref == null, "$what is refused rather than built")
        return assertNotNull(why, "$what is refused *by name*")
    }

    private fun volumeOf(on: (Construction) -> SolidRef): Double = Geom3.volume(Evaluator().solid(on(Construction())).mesh)

    private fun facesOf(s: Solid3) = assertNotNull(Section3.faces(s.feature).first, "it names its faces")

    private fun edgesOf(s: Solid3) = assertNotNull(Section3.edges(s.feature).first, "it names its edges")

    // ---- (a) the sector: the arc as a band, and the convex corner where it hands over ----

    /**
     * **A band along the sector's own rim builds, and it is Pappus' revolution** — which before this slice
     * it was not: the tool's flat cap stands in the radial face the meridian plane *is* there, two sheets
     * facing against each other, and the boolean answered with a zero-thickness flap rather than a body.
     * The cure is the step-off a straight band's cap has had since GitHub #33, said for a circular run too:
     * where the cap stands **in** a face of the body the tube overshoots it by a micron.
     *
     * The figure is the containment bracket a rounding along a circular crease always gets — the exact
     * section carried at the nearest radius it reaches below, the chorded one at the farthest above.
     */
    @Test
    fun theSectorsRimRoundsAndTakesItsOwnRevolution() {
        val base = volumeOf(::sector)
        // a quarter disc, up to the chords its own rim reaches the mesh as — never above the true figure
        val disc = RADIUS * RADIUS * PI / 4.0 * HEIGHT
        assertTrue(base <= disc + 1e-9 && base >= disc * 0.998, "the fixture is a quarter disc, chorded: $base against $disc")
        val (solid, v) = built(::sector, listOf(ARC_TOP), 3.0, what = "the sector's rim at 3 mm")
        val lo = Figures.wedgeArea(3.0, BlendKind.FILLET) * (PI / 2.0) * (RADIUS - 3.0)
        val hi = Figures.wedgeAreaByChords(3.0, BlendKind.FILLET) * (PI / 2.0) * RADIUS
        assertTrue(v in Bracket(base - hi, base - lo), "$v is inside [${base - hi}, ${base - lo}]")
        // …and the band it leaves is the torus it is, about the sector's own axis
        val band = assertNotNull(facesOf(solid).firstOrNull { it.name is FaceName.BlendBand }, "the band is a face")
        val torus = assertNotNull(band.surface?.band as? Revolve3.Band.Torus, "and it is a torus: ${band.surface?.band}")
        assertClose(torus.minor, 3.0, 1e-9, "of the rounding's own radius")
        assertClose(torus.rc, RADIUS - 3.0, 1e-9, "on the rim's own offset circle")
    }

    /**
     * **The free end of a curved crease notches the face it stands in** (the slice's answer to session 81's
     * cut *"only a straight run has a cap that stands in one plane at all"*).
     *
     * A band along an arc ends on the **meridian** plane. For a pie slice that plane *is* the radial face, so
     * the end is a notch in that face's own outline and not a face of its own — exactly what a straight
     * band's end is, and exact for the same reason: the map from the wedge's own frame into that plane is
     * rigid, because the meridian plane is square to the run. Both radial faces gain it, the flat-end slots
     * say in their own words that the notch owns them, and the notch curve itself gets **no address** —
     * how many notch slots an entry owns is a function of its base edge alone, so stating one for a curved
     * crease would move every appended address after it (OP-30, slice 5g). That is the slice's own cut.
     */
    @Test
    fun aCurvedFreeEndNotchesTheFaceItStandsIn() {
        val (solid, _) = built(::sector, listOf(ARC_TOP), 3.0, what = "the sector's rim at 3 mm")
        val radial = facesOf(solid).filter { it.plane != null && it.reason == null && it.outline.size > 3 && it.name !is FaceName.BlendBand }
        assertEquals(2, radial.size, "both radial faces carry a notch: ${facesOf(solid).map { it.name.label.render() to it.outline.size }}")
        for (f in radial) {
            assertEquals(5, f.outline.size, "${f.name.label.render()}: four sides with the corner replaced by the wedge's two legs and its arc")
            // the notch is the wedge itself: an arc of the rounding's own radius between two straight legs
            val arcs = f.outline.filterIsInstance<constructit.geom.ProfileElement.ArcE>()
            assertEquals(1, arcs.size, "one arc in the outline — the notch's own")
            assertClose(arcs[0].arc.radius, 3.0, 1e-9, "of the rounding's radius")
            assertClose(abs(constructit.geom.GeomMath.sweep(arcs[0].arc)), PI / 2.0, 1e-9, "a quarter of it")
        }
        // …and the two flat-end slots say who owns their end rather than claiming the body has no face there
        val caps = facesOf(solid).filter { it.name is FaceName.BlendCap }
        assertEquals(2, caps.size, "one flat-end slot per end, as ever")
        for (c in caps) assertTrue(c.reason!!.render().contains("stands in a face the body already has"), c.reason!!.render())
    }

    /**
     * **The convex corner where the rim hands over to a radius edge**: the two tools overlap and the boolean
     * trims them, which is session 79's cut (1) and stays — the surface equidistant from a straight edge and
     * a curved one is a curved medial one, not a plane, so there is no mitre to build and nothing to gain by
     * pretending otherwise. The body is right, and the figure is the containment bracket the matrix gives an
     * overlapping pair: no more than the two removals together, no less than the larger of them.
     *
     * What the corner does **not** get is a name. The crease between a torus band and a cylinder band is a
     * quartic in general, and stating it as a fitted chain is this slice's recorded cut — the drawing is
     * silent there rather than wrong, which is the honesty class session 79 left it in.
     */
    @Test
    fun theRimHandsOverToARadiusEdgeAndTheBooleanTrimsThem() {
        val base = volumeOf(::sector)
        val (_, alone) = built(::sector, listOf(ARC_TOP), 3.0, what = "the rim alone")
        val (_, radius) = built(::sector, listOf(RADIUS_TOP), 3.0, what = "the radius edge alone")
        val (solid, both) = built(::sector, listOf(RADIUS_TOP, ARC_TOP), 3.0, what = "the rim and a radius edge together")
        assertTrue(both <= minOf(alone, radius) + 1e-6, "the pair removes at least what either alone does")
        assertTrue(both >= alone + radius - base - 1e-6, "…and no more than the two together")
        // no corner face and no mitre crease: the pair is a crossing the boolean found, not one built
        assertTrue(facesOf(solid).none { it.name is FaceName.BlendCorner }, "the convex corner adds no face")
        assertTrue(edgesOf(solid).none { it.name is constructit.geom.EdgeName.BlendMitre }, "and states no crease — the slice's own cut")
    }

    /**
     * **The apex is the crossing it always was.** The sector's two radius edges meet at its own angle between
     * two straight runs, so the mitre is built exactly as it has been since session 79 — a curved neighbour
     * elsewhere on the body changes nothing about it — and the figure is the exact one.
     */
    @Test
    fun theSectorsApexIsStillTheMitreItWas() {
        val base = volumeOf(::sector)
        val (solid, v) = built(::sector, listOf(RADIUS_TOP, RADIUS_TOP2), 3.0, BlendKind.CHAMFER, "the sector's two radius edges bevelled")
        val (_, one) = built(::sector, listOf(RADIUS_TOP), 3.0, BlendKind.CHAMFER, "one of the two alone")
        // the mitre is what the pair takes **off** their own sum, and it is the exact figure at a right
        // angle; the containment bound above it is the two bands run whole, which is what a pair with no
        // corner between them would take
        val crossing = Figures.crossingTakes(3.0, BlendKind.CHAMFER, PI / 2.0)
        val naive = 2.0 * (base - one)
        assertTrue(v in Bracket(base - naive + crossing - 1e-3, base - naive + crossing + 1e-3), "$v against ${base - naive + crossing}")
        assertTrue(edgesOf(solid).any { it.name is constructit.geom.EdgeName.BlendMitre }, "and the mitre crease is named")
    }

    // ---- (b) the keyhole: the inside corner between an arc and a straight edge ----

    /**
     * **The inside corner between a circular edge and a straight one is the ball's own pivot**, and it comes
     * out as the **horn torus** the ball sweeps about the sharp upright — tube `r`, centre circle `r`, which
     * is what says the hole closes to the point where the ball touches the upright.
     *
     * Nothing about the construction is new: it is session 80's turn, reached at last by a pair one of whose
     * runs is an arc. What made it reachable is that a corner no longer asks its two runs to be straight —
     * a band along a circular crease ends on the meridian plane, which is the plane square to its own run,
     * so its end section is an arc of the very meridian circle the straight one's is.
     */
    @Test
    fun theKeyholesInsideCornerIsTheBallsPivotAboutASharpUpright() {
        val (solid, _) = built(::keyhole, listOf(STEM_TOP, DISC_TOP), 3.0, what = "the keyhole's inside corner")
        val corner = assertNotNull(facesOf(solid).firstOrNull { it.name is FaceName.BlendCorner }, "the corner is a face of its own")
        val torus = assertNotNull(corner.surface?.band as? Revolve3.Band.Torus, "and it is a torus: ${corner.surface?.band}")
        assertClose(torus.minor, 3.0, 1e-9, "tube r")
        assertClose(torus.rc, 3.0, 1e-9, "…and centre circle r — the horn torus, whose hole closes to a point")
        // the two bands are what they are: a cylinder along the stem's side, a torus along the disc's rim
        val bands = facesOf(solid).filter { it.name is FaceName.BlendBand }.mapNotNull { it.surface?.band }
        assertEquals(1, bands.count { it is Revolve3.Band.Cylinder }, "one cylinder band along the stem's straight edge: $bands")
        assertEquals(1, bands.count { it is Revolve3.Band.Torus }, "…and one torus band along the disc's own rim: $bands")
    }

    /**
     * **The pivot adds, and by Pappus' own figure.** At an inside corner the two bands never overlap, so what
     * stands between them is added to their sum rather than taken off it: `w·φ·δ̄` over the corner's own
     * exterior angle. The bracket is the containment one — every band exact at its nearest radius below,
     * chorded at its farthest above — and the corner is bracketed with them.
     */
    @Test
    fun theKeyholesPivotAddsPappusOwnFigure() {
        val base = volumeOf(::keyhole)
        val (_, v) = built(::keyhole, listOf(STEM_TOP, DISC_TOP), 3.0, what = "the keyhole's inside corner")
        val yj = 20.0 - sqrt(DISC * DISC - 25.0)
        // the corner's own exterior angle: the stem's edge reaches into the face square to itself, the
        // disc's rim reaches along its own radial, and the two stand `π/2 − atan(5/√(R²−25))` apart
        val phi = PI / 2.0 - atan2(5.0, sqrt(DISC * DISC - 25.0))
        val arcSweep = 2.0 * PI - 2.0 * atan2(5.0, 20.0 - yj)
        val w = Figures.wedgeArea(3.0, BlendKind.FILLET)
        val wc = Figures.wedgeAreaByChords(3.0, BlendKind.FILLET)
        val pivot = Figures.pivotTakes(3.0, BlendKind.FILLET, phi, 0.0)
        val lo = w * yj + w * arcSweep * (DISC - 3.0) + pivot
        val hi = wc * yj + Figures.chordSurplus(3.0, yj) + wc * arcSweep * DISC + wc * phi * Figures.centroidReach(3.0, BlendKind.FILLET)
        assertTrue(v in Bracket(base - hi, base - lo), "$v is inside [${base - hi}, ${base - lo}]")
        // …and the pivot really adds: the pair takes more than the two bands' own naive sum
        assertTrue(base - v > w * yj + w * arcSweep * (DISC - 3.0), "the corner adds rather than overlapping")
    }

    /**
     * **Both of the keyhole's two corners build, and the body is its own mirror.** The disc meets the stem on
     * each side, so the plan has two reflex corners of the same angle; rounding the stem's other side gives
     * the mirror body, and the two agree to the boolean's own noise. Rounding all three edges gives one body
     * with two corner faces.
     */
    @Test
    fun bothOfTheKeyholesCornersBuildAndAgreeByMirror() {
        val (_, left) = built(::keyhole, listOf(STEM_TOP, DISC_TOP), 3.0, what = "the near corner")
        val (_, right) = built(::keyhole, listOf(DISC_TOP, STEM_TOP2), 3.0, what = "the far corner")
        assertClose(left, right, 1e-6, "the two corners are one body mirrored")
        val (solid, _) = built(::keyhole, listOf(STEM_TOP, DISC_TOP, STEM_TOP2), 3.0, what = "both corners in one dressing")
        assertEquals(2, facesOf(solid).count { it.name is FaceName.BlendCorner }, "two corner faces")
        assertEquals(
            2,
            edgesOf(solid).count { it.name is constructit.geom.EdgeName.BlendCornerRail },
            "…and a rail apiece: ${edgesOf(solid).map { it.name.label.render() }}",
        )
    }

    // ---- (c) and (e): the two uprights the pivot cannot follow ----

    /**
     * **A loft's inside corner refuses by name, and the reason is the upright** (session 81's parked case (a),
     * reached through session 79's cut (2)).
     *
     * The ball at an inside corner keeps its centre on the shared face's own offset plane and at `r` from the
     * upright. Where the upright stands square to that face those two are a plane and a cylinder about its
     * normal, meeting in a **circle** — the pivot, exact. A loft's side faces slant, so the upright between
     * two of them slants too, the two conditions meet in an **ellipse**, and the corner is a swept sphere
     * along it: the canal surface slice (5f) owes the elliptical mitre, and this drawing has no carrier for
     * one. So it is refused whole, in one sentence, naming the two faces whose crossing the upright is.
     */
    @Test
    fun aLoftsInsideCornerRefusesByNamingItsSlantedUpright() {
        val why = refused(::lofted, listOf(LOFT_CAP_A, LOFT_CAP_B), 3.0, "a loft's inside corner")
        assertTrue(why.contains("neither one straight run nor square to"), why)
        assertTrue(why.contains("section 2's own face"), "…and it names the face the two roundings share: $why")
        assertTrue(why.contains("canal surface"), "…and says what the corner would be: $why")
        // and it heals the way every refusal here does: leave one of the two sharp and the other builds
        built(::lofted, listOf(LOFT_CAP_A), 3.0, what = "one of the two alone")
        built(::lofted, listOf(LOFT_CAP_B), 3.0, what = "the other alone")
    }

    /**
     * **A revolve's cap corner refuses by naming its ring upright** (session 81's parked case (b)).
     *
     * The upright at an inside corner of a revolve's cap is the **ring** the profile's own corner traces, so
     * the ball's centre stands at `r` from a circle rather than from a line: a plane against a torus, a
     * spiric quartic, and not even (5f)'s ellipse. Before this slice the pair was refused with slice 5a's
     * *"they are not congruent"* — which names the symptom, since two wedges at a ring upright are **bound**
     * to differ, and offers a cure (give both edges the same rounding) that cannot work. The upright is
     * asked about first now.
     *
     * The **convex** corner of the same cap is untouched and builds, which is what says the refusal is about
     * the inside corner and not about the fixture.
     */
    @Test
    fun aRevolvesCapCornerRefusesByNamingItsRingUpright() {
        val why = refused(::turned, listOf(TURN_CAP_A, TURN_CAP_B), 2.0, "a revolve's cap inside corner")
        assertTrue(why.contains("neither one straight run nor square to"), why)
        assertTrue(why.contains("the cap at the start of the sweep"), "…and names the shared face: $why")
        assertTrue(!why.contains("not congruent"), "…and no longer names the symptom instead: $why")
        built(::turned, listOf(TURN_CAP_B, TURN_CAP_C), 2.0, what = "the same cap's convex corner")
    }

    /**
     * **A vertex with a curved edge among the three refuses by name rather than breaking the shell.**
     *
     * The ball still stands still there and its patch is still the spherical triangle between the three band
     * ends; what is not stated is the **solve**, which crosses two tangency *lines* on each shared face and
     * has no line to cross where a face is curved. Left alone the three tubes butt and the general boolean
     * answers with a tangent contact rather than a body — which is what the sector's own top corner did
     * before this slice — so the trio is named. The cure the sentence offers works: round two of the three.
     */
    @Test
    fun aVertexWithACurvedEdgeRefusesRatherThanBreakingTheShell() {
        val why = refused(::sector, listOf(UPRIGHT_AT_RIM, RADIUS_TOP, ARC_TOP), 3.0, "the sector's own top rim vertex")
        assertTrue(why.contains("has a curved edge among them"), why)
        assertTrue(why.contains("Round two of the three edges"), "…and says what does work: $why")
        built(::sector, listOf(RADIUS_TOP, ARC_TOP), 3.0, what = "two of the three")
    }

    // ---- (f) nothing recorded changed ----

    /**
     * **No stored form moved.** This slice appends no slot, renumbers none and writes nothing new into a
     * file: a curved crease's notch is a *boundary piece* and not an address, the corner face a curved pair
     * makes is the very `BlendCorner` slot the catalogue already had, and no format version rose. The
     * fixtures assert it the only way a construction can — the same dressing built twice is the same body to
     * the last bit, and the two gesture orders of one corner agree.
     */
    @Test
    fun theSameDressingIsTheSameBodyAndBothOrdersAgree() {
        val (_, once) = built(::keyhole, listOf(STEM_TOP, DISC_TOP), 3.0, what = "once")
        val (_, twice) = built(::keyhole, listOf(STEM_TOP, DISC_TOP), 3.0, what = "twice")
        assertClose(once, twice, 1e-9 * once, "the same construction is the same body — to the general engine's own ULP noise, which slice 5g measured at a part in 1e12 between two evaluations")
        val (_, other) = built(::keyhole, listOf(DISC_TOP, STEM_TOP), 3.0, what = "the other order")
        assertClose(once, other, 1e-9 * once, "and the corner does not care which edge was picked first — to the same ULP noise")
    }

    private companion object {
        const val RADIUS = 30.0
        const val HEIGHT = 20.0
        const val DISC = 15.0

        // the sector's edge list: three uprights, then the bottom cap's three, then the top cap's three
        const val UPRIGHT_AT_RIM = 1
        const val RADIUS_TOP = 6
        const val ARC_TOP = 7
        const val RADIUS_TOP2 = 8

        // the keyhole's: four uprights, four on each cap
        const val STEM_TOP = 9
        const val DISC_TOP = 10
        const val STEM_TOP2 = 11

        // the loft's cap: six rails, then each section's six edges
        const val LOFT_CAP_A = 14
        const val LOFT_CAP_B = 15

        // the revolve's: six rings, then each cap's six profile edges
        const val TURN_CAP_A = 8
        const val TURN_CAP_B = 9
        const val TURN_CAP_C = 10
    }
}
