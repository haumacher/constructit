package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.Node
import constructit.core.RegionValue
import constructit.core.ScalarValue
import constructit.core.Value
import constructit.dsl.Construction
import constructit.dsl.ExprLaw
import constructit.dsl.FamilyLaw
import constructit.dsl.PointRef
import constructit.dsl.RegionRef
import constructit.dsl.ScalarRef
import constructit.dsl.SectionFamilies
import constructit.dsl.SectionFamily
import constructit.expr.ExprParser
import constructit.geom.Affine
import constructit.geom.Curve3Element
import constructit.geom.Curves3
import constructit.geom.Frames3
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Handedness
import constructit.geom.Loop
import constructit.geom.MeshQuality
import constructit.geom.Path3
import constructit.geom.ProfileElement
import constructit.geom.Region
import constructit.geom.Segment
import constructit.geom.SizeLaw
import constructit.geom.Solid3
import constructit.geom.SweepProfile
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Dimension
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The four-piece closed region over four points of one sketch, in order. */
private fun quad(
    cx: Construction,
    a: PointRef,
    b: PointRef,
    c: PointRef,
    d: PointRef,
): RegionRef = cx.region(cx.loop(cx.segment(a, b), cx.segment(b, c), cx.segment(c, d), cx.segment(d, a)))

/**
 * **The function-family section, at the level of the geometry itself** (OP-26, session 79 — queue entry 2,
 * the wing's route).
 *
 * The tier above session 77's rigid `scale(t)`, and what makes it a tier rather than a variant: a rigid law
 * scales *one* outline, and **no factor** turns a 200 mm chord with a 12% thickness into an 80 mm chord with
 * a 12% thickness of *that*. What does is re-reading the section's own 2D drawing once per station with
 * `chord` substituted, which is what a family states — one graph, `n` readings, values out
 * ([SectionFamilies]).
 *
 * The fixtures are the design pass's own:
 *
 * - **the rigid tier is reproduced** — two laws that happen to be one factor build the body a `law=` builds,
 *   corner for corner, so the general tier is a superset and not a second answer;
 * - **the tier is genuinely left behind** — two *different* laws build a body no rigid scaling of any section
 *   can, and its volume is the exact integral of what the laws state;
 * - **the wing** — a chord law, a thickness *bound* to it, a twist law and a quarter-chord pivot, all four by
 *   construction rather than by compensation;
 * - **a drafted rib** — a flank at a stated angle, to a billionth of a degree (which is why the *Draft*
 *   dressing stays parked: it is constructible today);
 * - **a blade** — three laws over one drawing, on a straight run and on a helix, and a coil too tight for the
 *   family's own widest station refusing in the bend's words;
 * - **every per-station verdict**, each in its own words and each on the family's fixed grid, so refining the
 *   picture moves neither a verdict nor a sentence (session 65).
 *
 * **The frame's axes, once**: for a run along world x with `up = Z`, a section's own `(u, v)` lands on
 * `(z, −y)` — see [ringUV]. Everything below reads a body's rings in those axes, which is what makes an
 * exact ring assertion readable.
 *
 * Every solid goes through [assertManifold]: watertight or refused (OP-9), no exception for the newest
 * reading of the oldest feature.
 */
class SectionFamilyTest {
    private val up = Vec3.Z

    private fun straight(
        a: Vec3,
        b: Vec3,
    ) = Path3(Curves3.straightThrough(listOf(a, b)))

    /** A run of [length] mm along the world x axis. */
    private fun run(length: Double) = straight(Vec3.ZERO, Vec3(length, 0.0, 0.0))

    // ---- the sketch a family reads ----

    /**
     * A **rectangle sketch** whose width and height are named parameters, centred on the section's own
     * origin — the stand-in most fixtures here are stated on, built at the level of the graph so a test can
     * hand it any tolerance.
     *
     * Centred, because the origin is what rides the run when nothing else is stated: so a rigid `scale(t)`
     * about that origin and a family driving both parameters by one factor describe the identical outline at
     * every station, which is what the first fixture asserts.
     */
    private class Sketch(
        wmm: Double,
        hmm: Double,
    ) {
        val cx = Construction()
        val w: ScalarRef = cx.parameter("w", wmm.mm)
        val h: ScalarRef = cx.parameter("h", hmm.mm)
        val region: RegionRef

        init {
            val xhi = cx.scale(w, 0.5)
            val xlo = cx.neg(xhi)
            val yhi = cx.scale(h, 0.5)
            val ylo = cx.neg(yhi)
            region = quad(cx, cx.pointXY(xlo, ylo), cx.pointXY(xhi, ylo), cx.pointXY(xhi, yhi), cx.pointXY(xlo, yhi))
        }
    }

    /** The wing sketch: `chord` free, `thickness = 0.12 · chord`, and the quarter-chord point to ride on. */
    private class WingSketch {
        val cx = Construction()
        val chord: ScalarRef = cx.parameter("chord", 200.0.mm)
        val thickness: ScalarRef = cx.scale(chord, 0.12)
        val quarterChord: PointRef
        val region: RegionRef

        init {
            val nought = cx.scale(chord, 0.0)
            quarterChord = cx.pointXY(cx.scale(chord, 0.25), nought)
            region =
                quad(
                    cx,
                    cx.pointXY(nought, nought),
                    cx.pointXY(chord, nought),
                    cx.pointXY(chord, thickness),
                    cx.pointXY(nought, thickness),
                )
        }
    }

    /** One law of a family: [name] driven by [text], read in `t`. */
    private fun law(
        name: String,
        target: ScalarRef,
        text: String,
    ) = FamilyLaw(name, target.node, ExprLaw(ExprParser.parse(text), emptyList(), emptyList(), text))

    /** A law over the run with no target — the run's own twist (the design pass's F12). */
    private fun over(text: String) = ExprLaw(ExprParser.parse(text), emptyList(), emptyList(), text)

    private fun family(
        section: Node,
        laws: List<FamilyLaw>,
        anchor: Node? = null,
        twist: ExprLaw? = null,
        unresolved: List<String> = emptyList(),
    ) = SectionFamily(section, anchor, laws, twist, emptyList(), unresolved)

    /** The family built and swept — the whole route `Construction.sweep` takes, at a stated tolerance. */
    private fun sweep(
        fam: SectionFamily,
        path: Path3,
        tolMm: Double = GeomMath.TESS_TOL_MM,
        rigid: SizeLaw? = null,
    ): Pair<Solid3?, String?> {
        val length = path.elements.sumOf { Curves3.arcLength(it) }
        val (built, why) =
            SectionFamilies.build(fam, emptyList(), 0, length, tolMm) { region, at ->
                if (at == null) {
                    region
                } else {
                    val x = Affine.translation(at * -1.0)
                    Region(GeomMath.transform(region.outer, x), region.holes.map { GeomMath.transform(it, x) })
                }
            }
        if (built == null) return null to why
        return Geom3.sweep(path, up, built.profile.copy(law = rigid), tolMm = tolMm, twistLaw = built.twist)
    }

    private fun volumeOf(solid: Solid3): Double = Geom3.volume(solid.mesh)

    /** The corners of [solid] standing on the plane `x = [x]` — one station's own ring. */
    private fun ringAt(
        solid: Solid3,
        x: Double,
    ): List<Vec3> {
        val on = solid.mesh.vertices.filter { abs(it.x - x) < 1e-9 }
        assertTrue(on.size >= 3, "there is a ring at x = $x: ${on.size} corners")
        return on
    }

    /**
     * [solid]'s ring at `x = [x]` in the **section's own axes**: `(z, −y)`, which is exactly the section's
     * `(u, v)` where the run is along world x, `up` is Z and nothing is turned.
     */
    private fun ringUV(
        solid: Solid3,
        x: Double,
    ): List<Vec2> = ringAt(solid, x).map { Vec2(it.z, -it.y) }

    /** [p] turned by [deg] degrees, the way a station's own twist turns its ring. */
    private fun turned(
        p: Vec2,
        deg: Double,
    ): Vec2 {
        val a = deg * PI / 180.0
        return Vec2(p.x * cos(a) - p.y * sin(a), p.x * sin(a) + p.y * cos(a))
    }

    /** Every corner of [want] is a corner of [ring], and there are no others — the ring, exactly. */
    private fun assertRingIs(
        ring: List<Vec2>,
        want: List<Vec2>,
        tol: Double,
        msg: String,
    ) {
        assertEquals(want.size, ring.size, "$msg: how many corners")
        for (p in want) {
            val near = ring.minOf { (it - p).length() }
            assertTrue(near <= tol, "$msg: $p is a corner of the ring (nearest is $near mm off)")
        }
    }

    // ---- 1. the rigid tier, reproduced ----

    /**
     * **Two laws that happen to be one factor build the body the factor builds** — corner for corner.
     *
     * The claim the whole tier rests on: the general reading is a *superset* of the rigid one, so a drawing
     * that could have been stated either way is one body and not two. The vertices are compared to the last
     * bit and in order; the triangles are compared as a set, because a family triangulates its **far** cap in
     * a pass of its own (its two ends are two different outlines in general, so they cannot interleave) —
     * which is a fact about the emission order and not about the body.
     */
    @Test
    fun theRigidTierIsReproducedCornerForCorner() {
        val fam = Sketch(20.0, 10.0)
        val byLaws =
            assertNotNull(
                sweep(
                    family(
                        fam.region.node,
                        listOf(law("w", fam.w, "20mm * (1 - t/2)"), law("h", fam.h, "10mm * (1 - t/2)")),
                    ),
                    run(100.0),
                ).first,
                "the family builds",
            )
        assertManifold(byLaws.mesh, "the family-swept rectangle")

        val rigid = Sketch(20.0, 10.0)
        val scale = SizeLaw(ExprParser.parse("1 - t/2"), emptyMap(), Dimension.NONE, "t", "1 - t/2")
        val drawn = (Evaluator().eval(rigid.region.node) as EvalResult.Ok).value.let { (it as RegionValue).region }
        val byFactor =
            assertNotNull(
                Geom3.sweep(run(100.0), up, SweepProfile.Section(drawn, scale)).first,
                "the rigid law builds",
            )
        assertManifold(byFactor.mesh, "the rigidly scaled rectangle")

        assertEquals(
            byFactor.mesh.vertices.size,
            byLaws.mesh.vertices.size,
            "the two tiers agree about how many corners the body has",
        )
        for (i in byFactor.mesh.vertices.indices) {
            val a = byFactor.mesh.vertices[i]
            val b = byLaws.mesh.vertices[i]
            assertTrue((a - b).length() == 0.0, "corner $i is the same corner: rigid $a, family $b")
        }
        assertEquals(
            byFactor.mesh.triangles.toSet(),
            byLaws.mesh.triangles.toSet(),
            "and the same triangles over them",
        )

        // ∫₀¹ 200 mm² · (1 − t/2)² · 100 mm dt = 20 000 · 7/12 mm³ — and the mesh is that body exactly,
        // because a rectangle scaled linearly in both directions has planar flanks
        assertClose(volumeOf(byLaws), 20000.0 * 7.0 / 12.0, 1e-6, msg = "the tapered rectangle's volume")
    }

    // ---- 2. beyond the rigid tier ----

    /**
     * **A rectangle whose width tapers and whose height does not** is a body no rigid scaling of any section
     * can be — and its volume is the exact integral of the areas its laws state.
     *
     * The end-ring assertion is the one that says *why this feature exists*: the drawn section is 2 : 1 and
     * the far one is 1 : 1, and no factor `k` applied to 20 × 10 gives 10 × 10. So the section is genuinely
     * re-read rather than re-scaled, and the tier above the rigid one is not a convenience.
     */
    @Test
    fun aWidthThatTapersAloneIsNoScalingOfAnySection() {
        val fam = Sketch(20.0, 10.0)
        val solid =
            assertNotNull(
                sweep(family(fam.region.node, listOf(law("w", fam.w, "20mm * (1 - t/2)"))), run(100.0)).first,
                "the family builds",
            )
        assertManifold(solid.mesh, "the rectangle tapered in one direction only")

        // ∫₀¹ (20 − 10t) · 10 · 100 dt = 15 000 mm³, exactly
        assertClose(volumeOf(solid), 15000.0, 1e-6, msg = "the one-way-tapered rectangle's volume")

        assertRingIs(
            ringUV(solid, 0.0),
            listOf(Vec2(-10.0, -5.0), Vec2(10.0, -5.0), Vec2(10.0, 5.0), Vec2(-10.0, 5.0)),
            1e-12,
            "the drawn section at the start of the run",
        )
        assertRingIs(
            ringUV(solid, 100.0),
            listOf(Vec2(-5.0, -5.0), Vec2(5.0, -5.0), Vec2(5.0, 5.0), Vec2(-5.0, 5.0)),
            1e-12,
            "the section at the end of the run",
        )
    }

    // ---- 3. the wing ----

    /**
     * **The wing** (the design pass's own fixture): a chord that tapers, a thickness that is 12% of
     * *whatever the chord is there*, a linear twist, and a pivot line a quarter of the chord back.
     *
     * All four by construction. The thickness is not a law at all — it is `0.12 · chord` in the drawing, and
     * a family substituting `chord` gets it at every station for free, which is sharing-is-equality one level
     * up (the design pass's F3). The pivot is the section's own quarter-chord point, **read per station under
     * the same substitution** (F11), so the run passes through the quarter chord of the section that is
     * actually there rather than of the one that was drawn.
     *
     * The volume is exact — `∫₀¹ 0.12 · chord(t)² · 1000 dt = 2 496 000 mm³` — and it is asserted on the
     * **untwisted** wing, which is where the family's own claim lives. A twisted body's *facets* lose volume
     * to the warp of their own quads far beyond the tessellation tolerance, and that is a property of every
     * twisted sweep in this engine rather than of a family: the control below sweeps the wing's own drawn
     * section along the same run with the same stated twist, and the twisted wing loses the same fraction it
     * does. (Recorded in DESIGN.md as a parked item of its own.)
     */
    @Test
    fun theWingTapersItsChordItsThicknessAndItsTwistAboutTheQuarterChord() {
        fun wing(twist: String?): Solid3 {
            val w = WingSketch()
            return assertNotNull(
                sweep(
                    family(
                        w.region.node,
                        listOf(law("chord", w.chord, "200mm * (1 - 0.6*t)")),
                        anchor = w.quarterChord.node,
                        twist = twist?.let { over(it) },
                    ),
                    run(1000.0),
                ).first,
                "the wing builds (twist $twist)",
            )
        }

        val flat = wing(null)
        assertManifold(flat.mesh, "the untwisted wing")
        assertEquals(2_496_000.0, volumeOf(flat), "the wing's volume is what its laws state, exactly")

        val washed = wing("15deg * t")
        assertManifold(washed.mesh, "the washed wing")

        // the root is the drawn section, read from its own quarter chord: 200 of chord, 24 thick
        assertRingIs(
            ringUV(washed, 0.0),
            listOf(Vec2(-50.0, 0.0), Vec2(150.0, 0.0), Vec2(150.0, 24.0), Vec2(-50.0, 24.0)),
            1e-12,
            "the wing's root section",
        )
        // …and the tip is 80 of chord, 9.6 thick — 12% of *that* chord, which no factor of the root gives —
        // read from its own quarter chord (20 ahead, not 50) and turned by exactly 15°
        assertRingIs(
            ringUV(washed, 1000.0),
            listOf(
                turned(Vec2(-20.0, 0.0), 15.0),
                turned(Vec2(60.0, 0.0), 15.0),
                turned(Vec2(60.0, 9.6), 15.0),
                turned(Vec2(-20.0, 9.6), 15.0),
            ),
            1e-9,
            "the wing's tip section, turned by its own twist law",
        )

        // the control: the wing's *drawn* section, swept along the same run with the same stated twist. What
        // a facetted twist costs is the same fraction of the body either way, which is what says the family
        // itself adds nothing to it.
        val drawn =
            Region(
                Loop(
                    listOf(Vec2(-50.0, 0.0), Vec2(150.0, 0.0), Vec2(150.0, 24.0), Vec2(-50.0, 24.0)).let { pts ->
                        pts.indices.map { ProfileElement.Seg(Segment(pts[it], pts[(it + 1) % pts.size])) }
                    },
                ),
                emptyList(),
            )
        val control = assertNotNull(Geom3.sweep(run(1000.0), up, SweepProfile.Section(drawn)).first, "the control builds")
        val controlTwisted =
            assertNotNull(
                Geom3.sweep(run(1000.0), up, SweepProfile.Section(drawn), twistRad = 15.0 * PI / 180.0).first,
                "the twisted control builds",
            )
        val lostByTwisting = volumeOf(controlTwisted) / volumeOf(control)
        assertClose(
            volumeOf(washed) / volumeOf(flat),
            lostByTwisting,
            0.01,
            msg = "a washed family loses to its facets exactly what a washed constant section loses",
        )
    }

    /**
     * **The wing is the same body at two tessellation tolerances** — the family owns its grid, and the grid
     * is a fact about the laws rather than about the picture (the design pass's F7 and F13).
     *
     * A linear family answers *two rings* to the sagitta rule at any tolerance whatever, so the body's
     * corners are the laws' own values at both ends and nothing between them is invented. The volume is
     * therefore the same number, not merely a near one.
     */
    @Test
    fun theWingIsTheSameBodyAtTwoTessellationTolerances() {
        fun volumeAt(tol: Double): Double {
            val w = WingSketch()
            return volumeOf(
                assertNotNull(
                    sweep(
                        family(
                            w.region.node,
                            listOf(law("chord", w.chord, "200mm * (1 - 0.6*t)")),
                            anchor = w.quarterChord.node,
                        ),
                        run(1000.0),
                        tolMm = tol,
                    ).first,
                    "the wing builds at tol $tol",
                ),
            )
        }
        assertEquals(volumeAt(0.2), volumeAt(0.002), "the wing's volume is the laws' own, not the mesh's")
    }

    // ---- 4. the drafted rib ----

    /**
     * **A flank at a stated draft angle**, to a billionth of a degree — which is why the *Draft* dressing
     * stays parked (DESIGN.md): with a family it is one formula, and a formula is exact.
     *
     * A centred rectangle whose width grows by `2 · tan(3°)` per millimetre of run puts each of its two
     * flanks at exactly 3° to the run, since each side takes half of the growth. The angle is measured off
     * the body's own corners and nothing else.
     */
    @Test
    fun aRibWhoseWidthGrowsByTheTangentHasFlanksAtThatExactAngle() {
        val rib = Sketch(20.0, 10.0)
        val solid =
            assertNotNull(
                sweep(
                    family(rib.region.node, listOf(law("w", rib.w, "20mm + 100mm * tan(3deg) * t"))),
                    run(50.0),
                ).first,
                "the rib builds",
            )
        assertManifold(solid.mesh, "the drafted rib")

        val root = ringUV(solid, 0.0)
        val end = ringUV(solid, 50.0)
        val grew = (end.maxOf { it.x } - end.minOf { it.x }) - (root.maxOf { it.x } - root.minOf { it.x })
        // each flank takes half the growth over 50 mm of run
        val angle = kotlin.math.atan2(grew / 2.0, 50.0) * 180.0 / PI
        assertClose(angle, 3.0, 1e-9, msg = "the rib's flank angle")
    }

    // ---- 5. the blade ----

    /**
     * **A blade: chord, thickness and twist, each a law of its own** — on a straight run and on a helix.
     *
     * Three laws rather than one is the point: a propeller's chord, its thickness and its wash are three
     * independent statements about the same drawing, and a family reads all three at every station. On a
     * helix the criteria have a real bend to judge, and the body is still watertight.
     */
    @Test
    fun aBladeWithThreeLawsBuildsOnAStraightRunAndOnAHelix() {
        fun laws(s: Sketch) =
            listOf(
                law("w", s.w, "60mm * (1 - 0.5*t)"),
                law("h", s.h, "8mm * (1 - 0.7*t)"),
            )

        val blade = Sketch(60.0, 8.0)
        val straightRun =
            assertNotNull(
                sweep(family(blade.region.node, laws(blade), twist = over("40deg * t")), run(300.0)).first,
                "the blade builds on a straight run",
            )
        assertManifold(straightRun.mesh, "the blade on a straight run")
        assertRingIs(
            ringUV(straightRun, 300.0),
            listOf(
                turned(Vec2(-15.0, -1.2), 40.0),
                turned(Vec2(15.0, -1.2), 40.0),
                turned(Vec2(15.0, 1.2), 40.0),
                turned(Vec2(-15.0, 1.2), 40.0),
            ),
            1e-9,
            "the blade's tip: 30 by 2.4, turned by exactly 40°",
        )

        val coiled = Sketch(60.0, 8.0)
        val helix = Path3(listOf(Curve3Element.Helix3(Vec3.ZERO, Vec3.Z, Vec3.X, 400.0, 600.0, 0.5, Handedness.RIGHT)))
        val onHelix =
            assertNotNull(
                sweep(family(coiled.region.node, laws(coiled), twist = over("40deg * t")), helix).first,
                "the blade builds on a helix",
            )
        assertManifold(onHelix.mesh, "the blade on a helix")
    }

    /**
     * **A run too tight for the family's own widest station refuses, quoting that station** — the criteria
     * read the family (the design pass's *criteria* paragraph) and never the picture.
     */
    @Test
    fun aHelixTooTightForTheFamilysWidestStationRefusesQuotingIt() {
        val blade = Sketch(200.0, 8.0)
        val (solid, why) =
            sweep(
                family(blade.region.node, listOf(law("w", blade.w, "200mm * (1 - 0.2*t)"))),
                Path3(listOf(Curve3Element.Helix3(Vec3.ZERO, Vec3.Z, Vec3.X, 40.0, 200.0, 0.75, Handedness.RIGHT))),
            )
        assertNull(solid, "a section 200 mm across cannot follow a 40 mm coil")
        val said = assertNotNull(why, "and it says so")
        assertTrue(
            said.contains("the section the family states there"),
            "the refusal names the section the family states at that station: $said",
        )
        assertTrue(said.contains("along the path"), "and how far along the run the bend it will not fit is: $said")
    }

    /**
     * **A circle is a section of one piece that comes back to itself** — and it is still a section.
     *
     * The regression for a real defect: the vanishing-piece verdict measured a piece by the distance between
     * its **ends**, which is zero for every closed piece there is, so a circle-sectioned family refused at
     * `t = 0` with a sentence about a piece that had not vanished. A piece is as long as its own polyline.
     */
    @Test
    fun aCircleSectionIsOnePieceThatComesBackToItselfAndIsStillASection() {
        val cx = Construction()
        val r = cx.parameter("r", 12.0.mm)
        val region = cx.region(cx.loop(cx.circleCR(cx.pointXY(cx.scale(r, 0.0), cx.scale(r, 0.0)), r)))
        val solid =
            assertNotNull(
                sweep(family(region.node, listOf(law("r", r, "12mm * (1 - 0.5*t)"))), run(100.0)).first,
                "a circle-sectioned family builds",
            )
        assertManifold(solid.mesh, "the tapered circle-sectioned family")
        assertClose(ringUV(solid, 0.0).maxOf { it.length() }, 12.0, 1e-9, msg = "the drawn radius at the start")
        assertClose(ringUV(solid, 100.0).maxOf { it.length() }, 6.0, 1e-9, msg = "the law's radius at the end")
    }

    // ---- the mesh levels: a family has one (F14) ----

    /**
     * **A family has one mesh level**, and it is [constructit.geom.Skin3]'s own reason: the ring's point count
     * comes from the family's own grid, so coarsening the picture would mean re-deciding the family to save
     * the cheap half of the work. Asserted as identity — the coarse ask hands back the fine mesh.
     */
    @Test
    fun aFamilyHasOneMeshLevelAndTheCoarseAskGetsIt() {
        val fam = Sketch(20.0, 10.0)
        val solid =
            assertNotNull(
                sweep(family(fam.region.node, listOf(law("w", fam.w, "20mm * (1 - t/2)"))), run(100.0)).first,
                "the family builds",
            )
        assertTrue(
            solid.meshAt(MeshQuality.COARSE) === solid.meshAt(MeshQuality.FINE),
            "the coarse level of a family is the fine one, the same object",
        )
    }

    // ---- the per-station verdicts (F9), each in its own words ----

    /** A quad one of whose corners walks onto its neighbour — a **piece that vanishes** part-way along. */
    private class Collapsing {
        val cx = Construction()
        val tip: ScalarRef = cx.parameter("tip", 10.0.mm)
        val region: RegionRef

        init {
            val base = cx.parameter("base", 20.0.mm)
            val high = cx.parameter("high", 10.0.mm)
            val nought = cx.scale(base, 0.0)
            region =
                quad(
                    cx,
                    cx.pointXY(nought, nought),
                    cx.pointXY(base, nought),
                    cx.pointXY(base, tip),
                    cx.pointXY(nought, high),
                )
        }
    }

    /**
     * **Refining the mesh changes neither the verdict nor the words it is spoken in** — session 65's law,
     * over two decades of tolerance and asserted character for character.
     *
     * The grid every verdict is decided on is [SectionFamilies.FAMILY_STEPS], a constant; the grid the body
     * is *drawn* on is the sagitta of the family's own rings. The two are deliberately different things, and
     * this is the assertion that they stay different.
     */
    @Test
    fun refiningTheMeshChangesNeitherTheVerdictNorTheWords() {
        for (tol in listOf(0.5, 0.05, 0.005)) {
            val shape = Collapsing()
            val (no, why) =
                sweep(
                    family(shape.region.node, listOf(law("tip", shape.tip, "10mm * (1 - t)"))),
                    run(100.0),
                    tolMm = tol,
                )
            assertNull(no, "a corner that walks onto its neighbour leaves the section a piece short (tol $tol)")
            assertEquals(
                "piece #2 of the section has no length ${Frames3.mm(100.0)} mm along the run " +
                    "(t = ${Frames3.mm(1.0)}), where tip = ${Frames3.mm(0.0)} mm — a family carries one section " +
                    "through the whole run, and a piece that vanishes part-way along leaves it with fewer. Hold " +
                    "that piece off zero, or draw the two sections you want and skin them with *Loft (ruled)*",
                why,
                "and the words are the same words at tol $tol",
            )
        }
    }

    /**
     * **A section that would cross itself** part-way along the run is a body that would pass through itself,
     * and it is named with the two corners whose edges meet — the 2D twin of the rails criterion, and the one
     * check this feature genuinely added.
     */
    @Test
    fun anOutlineThatCrossesItselfPartWayAlongNamesTheTwoEdges() {
        val cx = Construction()
        val back = cx.parameter("back", 20.0.mm)
        val nought = cx.scale(back, 0.0)
        val high = cx.parameter("high", 10.0.mm)
        // (0,0) → (20,0) → (back,10) → (0,10): once `back` walks past zero the third edge crosses the fourth
        val region =
            cx.region(
                cx.loop(
                    cx.segment(cx.pointXY(nought, nought), cx.pointXY(cx.parameter("wide", 20.0.mm), nought)),
                    cx.segment(cx.pointXY(cx.parameter("wide2", 20.0.mm), nought), cx.pointXY(back, high)),
                    cx.segment(cx.pointXY(back, high), cx.pointXY(nought, high)),
                    cx.segment(cx.pointXY(nought, high), cx.pointXY(nought, nought)),
                ),
            )
        val (no, why) =
            sweep(family(region.node, listOf(law("back", back, "20mm - 50mm * t"))), run(100.0))
        assertNull(no, "an outline that crosses itself is no section")
        val said = assertNotNull(why, "and it says so")
        assertTrue(said.contains("crosses itself"), "in those words: $said")
        assertTrue(said.contains("corner #"), "naming the corners whose edges meet: $said")
        assertTrue(said.contains("mm along the run"), "and where along the run: $said")
        assertTrue(said.contains("back = "), "and what the law said there: $said")
    }

    /**
     * A **computed** section, whose very shape is a value: how many pieces its boundary has, and which way
     * round it runs, decided by a scalar rather than drawn.
     *
     * This is the design pass's F9 discovery made testable. The queue's premise — *structure fixed, therefore
     * count fixed* — is false for a region that is **computed**: a boolean's loop count is a value, so a law
     * can genuinely change how many pieces a section has, and a producer that does not normalize orientation
     * can hand back a section running the other way. `Construction.region` does neither (it orients at build
     * time and its loops have a fixed element count), so the two verdicts those cases need are asserted
     * against exactly the kind of node they exist for.
     */
    private class ComputedSection(
        private val size: Node,
        private val what: What,
    ) : Node("computed-section") {
        enum class What { COUNT, WINDING }

        override val inputs: List<Node> get() = listOf(size)

        override fun compute(args: List<Value>): EvalResult {
            val s = (args[0] as ScalarValue).q.mm
            val square = listOf(Vec2(-10.0, -10.0), Vec2(10.0, -10.0), Vec2(10.0, 10.0), Vec2(-10.0, 10.0))
            val pts =
                when {
                    what == What.COUNT && s <= 15.0 -> listOf(Vec2(-10.0, -10.0), Vec2(10.0, -10.0), Vec2(0.0, 10.0))
                    what == What.WINDING && s <= 15.0 -> square.reversed()
                    else -> square
                }
            return EvalResult.Ok(
                RegionValue(
                    Region(
                        Loop(pts.indices.map { ProfileElement.Seg(Segment(pts[it], pts[(it + 1) % pts.size])) }),
                        emptyList(),
                    ),
                ),
            )
        }
    }

    /**
     * **A section whose piece count changes part-way along is invalid, naming both counts, both stations and
     * the law's values there — and pointing at the loft.**
     *
     * The two queue entries are each other's cure, which is what the refusal says: *a family whose pieces
     * must change is a loft; a loft over computed sections is a family.*
     */
    @Test
    fun aPieceCountThatChangesPointsAtTheLoftNamingBothCounts() {
        val cx = Construction()
        val size = cx.parameter("size", 20.0.mm)
        val section = ComputedSection(size.node, ComputedSection.What.COUNT)
        val (no, why) = sweep(family(section, listOf(law("size", size, "20mm * (1 - 0.5*t)"))), run(100.0))
        assertNull(no, "a section that loses a piece part-way along carries nothing through the run")
        val said = assertNotNull(why, "and it says so")
        assertTrue(said.contains("4 pieces"), "naming the count it started with: $said")
        assertTrue(said.contains("3 "), "and the count it changed to: $said")
        assertTrue(said.contains("mm along the run"), "naming both stations by distance: $said")
        assertTrue(said.contains("size = "), "and the law's value there: $said")
        assertTrue(said.contains("*Loft (ruled)*"), "and pointing at the loft: $said")
        assertTrue(said.contains("*Break*"), "and at Break, which is the other cure: $said")
    }

    /**
     * **A section that runs the other way round part-way along has been pulled through itself**, and it is a
     * fold rather than a convention — deliberately *not* normalized the way a drawn skin's sections are.
     *
     * A skin's sections are separate drawings and each one's orientation is a fact about it; a family is
     * **one** drawing read many times, so a station whose area has changed sign is a body folded through
     * itself and is said so.
     */
    @Test
    fun aWindingThatTurnsOverIsAFoldRatherThanAConvention() {
        val cx = Construction()
        val size = cx.parameter("size", 20.0.mm)
        val section = ComputedSection(size.node, ComputedSection.What.WINDING)
        val (no, why) = sweep(family(section, listOf(law("size", size, "20mm * (1 - 0.5*t)"))), run(100.0))
        assertNull(no, "a section that turns over builds nothing")
        val said = assertNotNull(why, "and it says so")
        assertTrue(said.contains("turns inside out"), "in those words: $said")
        assertTrue(said.contains("folded through itself"), "naming what the body would be: $said")
        assertTrue(said.contains("mm along the run"), "and where: $said")
    }

    /** A law that cannot be **read** — an angle where a length was written — is named, never silently zero. */
    @Test
    fun aTwistLawThatIsNotAnAngleRefusesInTheRunsOwnWords() {
        val fam = Sketch(20.0, 10.0)
        val (no, why) =
            sweep(family(fam.region.node, listOf(law("w", fam.w, "20mm")), twist = over("5mm * t")), run(100.0))
        assertNull(no, "a twist that is a length is no turn")
        val said = assertNotNull(why, "and it says so")
        assertTrue(said.contains("the run's own twist must be an angle"), "in the run's own words: $said")
    }

    /** An **invalid 2D drawing** at some station quotes the failing element upstream (`CASCADE_PREFIX`). */
    @Test
    fun anInvalid2DDrawingAtSomeStationQuotesWhatFailedThere() {
        val fam = Sketch(20.0, 10.0)
        val (no, why) =
            sweep(family(fam.region.node, listOf(law("h", fam.h, "10mm * (1 - t)"))), run(100.0))
        assertNull(no, "a height that reaches zero is no section")
        val said = assertNotNull(why, "and it says so")
        assertTrue(said.contains("the section has no shape"), "in those words: $said")
        assertTrue(said.contains("mm along the run"), "naming where: $said")
        assertTrue(said.contains("h = "), "and what the law said there: $said")
        assertTrue(said.contains("encloses no area"), "quoting what failed in the drawing: $said")
    }

    /** A law driving a name the drawing carries nothing for is named rather than guessed, and heals (OP-3). */
    @Test
    fun aLawThatNamesNothingTheDrawingHasIsNamedRatherThanGuessed() {
        val fam = Sketch(20.0, 10.0)
        val (no, why) = sweep(family(fam.region.node, emptyList(), unresolved = listOf("chord")), run(100.0))
        assertNull(no, "a law driving a name the drawing has not got builds nothing")
        val said = assertNotNull(why, "and it says so")
        assertTrue(said.contains("'chord'"), "naming the name: $said")
        assertTrue(said.contains("add a parameter called chord"), "and the cure: $said")
    }

    // ---- composition with the rigid law (F6) ----

    /**
     * **A family and a rigid `scale(t)` on one step are one body**: the family supplies the outline and the
     * factor multiplies what it supplied, so what the run carries is the product of the two statements.
     *
     * And the run is cut for the **product's** own curvature ([SweepProfile.Family.composedSpans]), which
     * neither grid sees on its own: a linear ring path times a linear factor is a quadratic vertex path, and
     * a body drawn on the family's two rings alone would be visibly wrong in the middle. So the volume is
     * asserted against the closed form `∫₀¹ (200 − 100t)·10·(1 + t)² · 100 dt`, in OP-15's honesty class —
     * the mesh is a polyhedron and is allowed to be inside its own sagitta of the body, and no further.
     */
    @Test
    fun aFamilyComposesWithTheRigidFactorRatherThanCompetingWithIt() {
        val fam = Sketch(20.0, 10.0)
        val rigid = SizeLaw(ExprParser.parse("1 + t"), emptyMap(), Dimension.NONE, "t", "1 + t")
        val solid =
            assertNotNull(
                sweep(
                    family(fam.region.node, listOf(law("w", fam.w, "20mm * (1 - 0.5*t)"))),
                    run(100.0),
                    rigid = rigid,
                ).first,
                "the composed body builds",
            )
        assertManifold(solid.mesh, "the family scaled by a rigid factor")

        // 100 · ∫₀¹ (200 − 100t)(1 + t)² dt = 100 · ∫ (200 + 300t − 100t³) dt = 100 · 325 mm³
        val want = 100.0 * 325.0
        assertClose(volumeOf(solid), want, want * 2e-3, msg = "the composed volume")

        // …and at the far end the section is 10 by 10, then doubled: 20 by 20, exactly
        assertRingIs(
            ringUV(solid, 100.0),
            listOf(Vec2(-10.0, -10.0), Vec2(10.0, -10.0), Vec2(10.0, 10.0), Vec2(-10.0, 10.0)),
            1e-9,
            "the composed section at the end of the run",
        )
    }

    // ---- holes: each loop a family of its own (F15) ----

    /**
     * **A hole is a family of its own** — a tube whose bore tapers differently from its wall is one body.
     *
     * The pairing a drawn skin needs (which loop goes with which) never arises here: the correspondence is
     * the *vertex index*, and it is a fact of the fixed count, so a family carries holes where a drawn skin
     * refuses them.
     */
    @Test
    fun aHoleTapersOnALawOfItsOwn() {
        val cx = Construction()
        val wall = cx.parameter("wall", 40.0.mm)
        val bore = cx.parameter("bore", 10.0.mm)

        fun square(size: ScalarRef) =
            run {
                val hi = cx.scale(size, 0.5)
                val lo = cx.neg(hi)
                cx.loop(
                    cx.segment(cx.pointXY(lo, lo), cx.pointXY(hi, lo)),
                    cx.segment(cx.pointXY(hi, lo), cx.pointXY(hi, hi)),
                    cx.segment(cx.pointXY(hi, hi), cx.pointXY(lo, hi)),
                    cx.segment(cx.pointXY(lo, hi), cx.pointXY(lo, lo)),
                )
            }

        val region = cx.region(square(wall), square(bore))
        val solid =
            assertNotNull(
                sweep(
                    family(
                        region.node,
                        listOf(
                            law("wall", wall, "40mm * (1 - 0.5*t)"),
                            law("bore", bore, "10mm * (1 + 0.5*t)"),
                        ),
                    ),
                    run(100.0),
                ).first,
                "the bored body builds",
            )
        assertManifold(solid.mesh, "a wall and a bore on two laws")

        // 100 · ∫₀¹ [(40 − 20t)² − (10 + 5t)²] dt = 100 · (933⅓ − 158⅓) = 77 500 mm³
        assertClose(volumeOf(solid), 77500.0, 1e-6, msg = "the bored body's volume")
    }
}
