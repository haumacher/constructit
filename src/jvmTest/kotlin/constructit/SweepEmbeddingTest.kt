package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.Point3Ref
import constructit.dsl.solid
import constructit.geom.Circle
import constructit.geom.Curve3Element
import constructit.geom.Curves3
import constructit.geom.Embedding
import constructit.geom.EmbeddingReport
import constructit.geom.Frames3
import constructit.geom.Geom3
import constructit.geom.Handedness
import constructit.geom.Loop
import constructit.geom.Path3
import constructit.geom.ProfileElement
import constructit.geom.Region
import constructit.geom.Segment
import constructit.geom.Solid3
import constructit.geom.SweepProfile
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.deg
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The sweep is refused unless the swept body is embedded** (OP-26, step 2's criterion completed; OP-9's
 * *watertight or refused*).
 *
 * The gap this closes was named under step 3 and left open: the self-intersection refusal was **local** by
 * the concept's own words — the profile's reach against the path's radius of curvature at a station — and a
 * body can be geometric nonsense with every station's curvature perfectly comfortable. A spring whose wire is
 * thicker than half its pitch has each turn passing through the turn below; the shell that came out was
 * edge-manifold, enclosed a positive volume, and was silently wrong — which is the worst kind of wrong, since
 * a boolean against it, a volume from it and a 3MF written from it are all wrong with nothing saying so.
 *
 * The criterion is now the spine's **reach** (Federer's), of which the old one was the first half:
 *
 * ```
 * reach(path) = min( 1/κ_max , ½·min{ |P(s) − P(t)| : |s − t| ≥ δ } )
 * ```
 *
 * and the whole difficulty is δ — see [Embedding], where it is derived from the curvature bound rather than
 * chosen. These tests hold it from every side: the reported spring, its **non-helical twin** (which is what
 * proves the fix is general and not aimed at the report), the six shapes that must keep building, the
 * boundary asserted from both sides, the closed path whose exclusion has to wrap, and the cost.
 */
class SweepEmbeddingTest {
    // ---- the fixtures ----

    private val up = Vec3.Z

    private fun polyline(vararg p: Vec3) = Path3(Curves3.straightThrough(p.toList()))

    private fun closedPolyline(vararg p: Vec3) = Path3(Curves3.straightThrough(p.toList(), closed = true), closed = true)

    private fun smooth(
        vararg p: Vec3,
        closed: Boolean = false,
    ) = Path3(Curves3.smoothThrough(p.toList(), closed), closed)

    private fun coil(
        radius: Double,
        pitch: Double,
        turns: Double,
    ) = Path3(listOf(Curve3Element.Helix3.about(Vec3.ZERO, Vec3.Z, Vec3.X, radius, pitch, turns, Handedness.RIGHT)))

    private fun rectangle(
        w: Double,
        h: Double,
    ): Region {
        val pts = listOf(Vec2(-w / 2, -h / 2), Vec2(w / 2, -h / 2), Vec2(w / 2, h / 2), Vec2(-w / 2, h / 2))
        return Region(Loop(pts.indices.map { ProfileElement.Seg(Segment(pts[it], pts[(it + 1) % pts.size])) }), emptyList())
    }

    /** A circle of radius [r] drawn [cy] mm off the section's own origin — the off-centre section, plainly. */
    private fun circleAt(
        cy: Double,
        r: Double,
    ) = Region(Loop(listOf(ProfileElement.CircleE(Circle(Vec2(0.0, cy), r)))), emptyList())

    /** A point of the plan as a graph node, so a drag of it is an ordinary recompute. */
    private fun Construction.planPoint(
        x: Double,
        y: Double,
    ): Point3Ref = heightPoint(planeXY(), freePoint("p", x.mm, y.mm), const(0.0.mm))

    private fun sweptOrFail(
        path: Path3,
        profile: SweepProfile,
    ): Solid3 {
        val (solid, why) = Geom3.sweep(path, up, profile)
        return assertNotNull(solid, "the sweep was refused: $why")
    }

    private fun refusal(
        path: Path3,
        profile: SweepProfile,
    ): String {
        val (solid, why) = Geom3.sweep(path, up, profile)
        assertNull(solid, "this sweep was expected to be refused")
        return assertNotNull(why, "a refusal says why")
    }

    /** What the criterion itself says about this path with a profile reaching [reach] — the report, not the sweep. */
    private fun report(
        path: Path3,
        reach: Double,
    ): EmbeddingReport {
        val (frame, why) = Frames3.along(path, up, reach = reach)
        return Embedding.check(assertNotNull(frame, why), reach, "the tube's radius (${Frames3.mm(reach)} mm)")
    }

    /** The distance a refusal names, in mm — the `passes within …` figure, read back out of the sentence. */
    private fun namedDistance(why: String): Double =
        assertNotNull(
            Regex("""passes within ([\d.]+) mm""").find(why),
            "the refusal names the distance: $why",
        ).groupValues[1].toDouble()

    /** The two arc positions a refusal names, in mm — both of them, which is what makes it actionable. */
    private fun namedStations(why: String): Pair<Double, Double> {
        val m =
            assertNotNull(
                Regex("""between ([\d.]+) mm and ([\d.]+) mm along the path""").find(why),
                "the refusal names both positions along the path: $why",
            )
        return m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
    }

    // ---- 1. which pairs count: a double normal, and nothing that merely lies close along the run ----

    /**
     * **The pairs that count are the double normals, and the shapes that must never produce one.** A run is
     * always close to itself *along* itself; what says two parts of it genuinely approach is that the segment
     * joining them stands square to the run at **both** ends. Three shapes make the point, and each is one
     * that an arc-length exclusion had to be tuned for and this needs no tuning for:
     *
     * - a **straight run**, where every pair's joining segment lies along the run — no bottleneck at any
     *   length, so no tube on a straight run can ever be refused for proximity;
     * - a **mitred right-angle elbow**, where the pairs either side of the corner are `reach·√2` apart, well
     *   inside their own clearance, and are still not bottlenecks because the segment leaves the corner at
     *   45° to the run;
     * - a **planar loop**, whose only square approach is across it and therefore never inside its section.
     *
     * Asserted through the report's own [EmbeddingReport.closest], so it is the criterion being read and not
     * the sweep's verdict.
     */
    @Test
    fun onlyASquareApproachCounts() {
        val reach = 12.0
        val straight = report(polyline(Vec3.ZERO, Vec3(400.0, 0.0, 0.0)), reach)
        assertNull(straight.defect, "a straight run is never near itself")
        assertEquals(Double.MAX_VALUE, straight.closest, "and has no bottleneck at all, at any length")

        val elbow = report(polyline(Vec3.ZERO, Vec3(200.0, 0.0, 0.0), Vec3(200.0, 200.0, 0.0)), reach)
        assertNull(elbow.defect, "a mitred elbow is not near itself either")
        assertEquals(Double.MAX_VALUE, elbow.closest, "though its two legs pass within ${reach * 1.415} mm of each other")

        val loop =
            report(
                smooth(Vec3(0.0, 0.0, 0.0), Vec3(200.0, 0.0, 0.0), Vec3(200.0, 160.0, 0.0), Vec3(0.0, 160.0, 0.0), closed = true),
                reach,
            )
        assertNull(loop.defect, "and a loop's only square approach is across it")
        assertTrue(loop.closest > 2.0 * reach, "which is wider than the section, not the width of a chord: ${loop.closest}")
    }

    // ---- 2. the reported case: a spring whose wire is thicker than half its pitch ----

    /**
     * **The reported body is refused, and the refusal is actionable**: a 20 mm coil of 6 mm pitch carrying a
     * 5 mm wire has each turn passing through the turn below, while every station's curvature is comfortable
     * (the radius of curvature is 20.05 mm against a 5 mm reach — the local term is nowhere near firing).
     *
     * The message names **both** arc positions and the distance, because "this sweep self-intersects" is not
     * something anybody can act on and "it passes within 6 mm of itself between 84 mm and 210 mm along" is.
     * Asserted as numbers rather than as a substring: the two positions really are a whole loop apart along
     * the spine, and the distance really is the approach.
     */
    @Test
    fun theSpringWhoseWireIsThickerThanHalfItsPitchIsRefusedNamingBothPositions() {
        val path = coil(radius = 20.0, pitch = 6.0, turns = 3.0)
        val wire = SweepProfile.Round(5.0)

        // the local term is nowhere near firing — this is exactly the case it cannot see
        val frame = assertNotNull(Frames3.along(path, up, reach = 5.0).first)
        val kMax = frame.stations.maxOf { it.curvature }
        assertTrue(kMax * 5.0 < 0.26, "every station's curvature is comfortable: 1/κ = ${1.0 / kMax} mm against a 5 mm reach")

        val why = refusal(path, wire)
        assertTrue(why.contains("the tube's radius (5 mm)"), "the refusal names the section: $why")
        assertTrue(why.contains("cut into itself"), "and what would go wrong: $why")
        assertTrue(why.contains("needs 10 mm between them"), "and the clearance it needed: $why")

        val d = namedDistance(why)
        assertClose(d, 5.993, 0.01, "the approach it names is the one the geometry has")
        assertTrue(d < 10.0, "and it is inside the clearance the wire needs: $d mm")

        val (a, b) = namedStations(why)
        assertTrue(a >= 0.0 && b <= frame.length, "both positions are on the path: $a mm and $b mm of ${frame.length} mm")
        assertTrue(b - a > 100.0, "and they are genuinely not neighbours along the spine: $a mm to $b mm")
        assertClose(b - a, 125.5, 5.0, "which is one turn of this coil apart")
    }

    /**
     * **…and it heals both ways** (OP-3, invalidity that heals — a property of *values*, so it is node
     * invalidity and never a gesture refusal): thin the wire and the very same coil is a solid; open the
     * pitch and the very same wire fits.
     */
    @Test
    fun theRefusedSpringHealsWhenTheWireThinsOrThePitchOpens() {
        val tight = coil(radius = 20.0, pitch = 6.0, turns = 3.0)
        refusal(tight, SweepProfile.Round(5.0))

        val thinned = sweptOrFail(tight, SweepProfile.Round(2.0))
        assertManifold(thinned.mesh, "the coil whose wire was thinned")

        val opened = sweptOrFail(coil(radius = 20.0, pitch = 12.0, turns = 3.0), SweepProfile.Round(5.0))
        assertManifold(opened.mesh, "the coil whose pitch was opened")
    }

    // ---- 3. the non-helical twin — what proves the criterion is general ----

    /**
     * **A serpentine whose legs run closer than the tube is wide is refused in exactly the same words**, and
     * this is the test that matters most: the criterion is a statement about *the sweep*, never a case per
     * `Curve3Element` kind. This path is a flat zig-zag of straight segments — no helix anywhere, every
     * station's curvature exactly zero — and it fails for the same reason the spring does.
     *
     * It heals for the same reason too: a section that fits between the legs sweeps a watertight solid along
     * the identical route.
     */
    @Test
    fun aFlatSerpentineWhoseLegsRunCloserThanTheTubeIsWideIsRefusedToo() {
        val route =
            polyline(
                Vec3(0.0, 0.0, 0.0),
                Vec3(100.0, 0.0, 0.0),
                Vec3(100.0, 16.0, 0.0),
                Vec3(0.0, 16.0, 0.0),
                Vec3(0.0, 32.0, 0.0),
                Vec3(100.0, 32.0, 0.0),
            )
        val frame = assertNotNull(Frames3.along(route, up, reach = 10.0).first)
        assertTrue(frame.stations.all { it.curvature == 0.0 }, "a polyline has no curvature at all — the local term is blind here")

        val why = refusal(route, SweepProfile.Round(10.0))
        assertTrue(why.contains("cut into itself"), "the refusal is the sweep's own: $why")
        assertClose(namedDistance(why), 16.0, 0.5, "and the approach it names is the gap between the legs")
        val (a, b) = namedStations(why)
        assertTrue(b - a > 20.0, "the two positions are far apart along the run: $a mm and $b mm")

        val fits = sweptOrFail(route, SweepProfile.Round(5.0))
        assertManifold(fits.mesh, "the serpentine that fits between its own legs")
    }

    /**
     * **Legs that run alongside each other with nothing lined up are still caught**, which is what makes the
     * criterion a statement about the *run* rather than about where it happened to be sampled. A straight
     * piece is cut into exactly **one** span, so this route's two parallel legs have no two *stations* near
     * one another at all — they are staggered, and the near one ends where the far one is still running. The
     * approach is measured span against span, so it is found where it actually is, in the middle of a
     * straight.
     */
    @Test
    fun anApproachInTheMiddleOfAStraightIsFoundThoughNothingIsSampledThere() {
        val route =
            polyline(
                Vec3(0.0, 0.0, 0.0),
                Vec3(300.0, 0.0, 0.0),
                Vec3(300.0, 400.0, 0.0),
                Vec3(-100.0, 400.0, 0.0),
                Vec3(-100.0, 30.0, 0.0),
                Vec3(180.0, 30.0, 0.0),
            )
        val why = refusal(route, SweepProfile.Round(18.0))
        assertClose(namedDistance(why), 30.0, 1e-3, "the gap between the two legs, found between their stations: $why")
        assertManifold(sweptOrFail(route, SweepProfile.Round(8.0)).mesh, "and the same route with a section that fits")
    }

    /**
     * **A run that crosses itself exactly is refused as the zero approach it is** — the degenerate end of the
     * criterion, where there is no direction for a segment of no length to stand square to.
     */
    @Test
    fun aRunThatCrossesItselfIsRefusedAtZero() {
        val crossing =
            polyline(
                Vec3(-100.0, 0.0, 0.0),
                Vec3(100.0, 0.0, 0.0),
                Vec3(100.0, 60.0, 0.0),
                Vec3(0.0, 60.0, 0.0),
                Vec3(0.0, -60.0, 0.0),
            )
        val why = refusal(crossing, SweepProfile.Round(5.0))
        assertClose(namedDistance(why), 0.0, 1e-6, "the run meets itself: $why")
        assertTrue(why.contains("cut into itself"), "and says so: $why")
    }

    // ---- 4. nothing that should pass is refused ----

    /**
     * **Six shapes that must keep building**, one per way the criterion could have been over-strict: a
     * straight run (where δ is exactly the arc between two sections that touch, so a bare inequality would
     * have refused it), a gentle S-curve through an inflection, a slender spring with a generous pitch, a
     * mitred polyline, a closed planar loop, and a rectangular section rather than a round one.
     *
     * Every one of them watertight, because that is the only thing "it built" is allowed to mean (OP-9).
     */
    @Test
    fun theShapesThatShouldPassAreNotRefused() {
        val cases =
            listOf<Pair<String, Pair<Path3, SweepProfile>>>(
                "a straight run" to (polyline(Vec3.ZERO, Vec3(200.0, 0.0, 0.0)) to SweepProfile.Round(12.0)),
                "a gentle S-curve" to
                    (
                        smooth(Vec3(0.0, 0.0, 0.0), Vec3(80.0, 40.0, 0.0), Vec3(160.0, -40.0, 0.0), Vec3(240.0, 0.0, 0.0)) to
                            SweepProfile.Round(4.0)
                    ),
                "a slender spring" to (coil(radius = 20.0, pitch = 20.0, turns = 10.0) to SweepProfile.Round(3.0)),
                "a mitred polyline" to
                    (
                        polyline(Vec3(0.0, 0.0, 0.0), Vec3(120.0, 0.0, 0.0), Vec3(120.0, 90.0, 0.0), Vec3(120.0, 90.0, 70.0)) to
                            SweepProfile.Round(6.0)
                    ),
                "a closed planar loop" to
                    (
                        smooth(
                            Vec3(0.0, 0.0, 0.0),
                            Vec3(90.0, 0.0, 0.0),
                            Vec3(90.0, 70.0, 0.0),
                            Vec3(0.0, 70.0, 0.0),
                            closed = true,
                        ) to SweepProfile.Round(6.0)
                    ),
                "a rectangular section round a bend" to
                    (
                        smooth(Vec3(0.0, 0.0, 0.0), Vec3(120.0, 70.0, 0.0), Vec3(240.0, 70.0, 60.0)) to
                            SweepProfile.Section(rectangle(18.0, 6.0))
                    ),
            )
        for ((name, case) in cases) {
            val (path, profile) = case
            val solid = sweptOrFail(path, profile)
            assertManifold(solid.mesh, name)
        }
    }

    // ---- 5. the boundary, from both sides ----

    /**
     * **The limit sits exactly where the two legs are a section apart, and it is asserted from both sides** —
     * one case just inside builds, one just outside refuses, with nothing between them but the arithmetic.
     * That is how step 3 asserted the local criterion, and the global one earns the same treatment.
     *
     * The fixture is a run that leaves along `+x`, takes a long detour and comes back along `+x` **40 mm
     * away**, with every corner hundreds of millimetres from the approach so nothing but the two straight
     * legs is being measured. A tube of 19.99 mm clears its own return by 0.02 mm and is a solid; one of
     * 20.01 mm does not, and says so.
     */
    @Test
    fun theLimitIsWhereTheRunClearsItselfAndBothSidesOfItAreAsserted() {
        val gap = 40.0
        val route =
            polyline(
                Vec3(0.0, 0.0, 0.0),
                Vec3(300.0, 0.0, 0.0),
                Vec3(300.0, 300.0, 0.0),
                Vec3(0.0, 300.0, gap),
                Vec3(0.0, 0.0, gap),
                Vec3(300.0, 0.0, gap),
            )

        val inside = sweptOrFail(route, SweepProfile.Round(gap / 2.0 - 0.1))
        assertManifold(inside.mesh, "the tube that clears its own return by 0.2 mm")

        val why = refusal(route, SweepProfile.Round(gap / 2.0 + 0.1))
        assertClose(namedDistance(why), gap, 1e-3, "the refusal names the gap it measured")
        assertTrue(why.contains("cut into itself"), "and what would go wrong: $why")
    }

    // ---- 6. the closed path, and the seam that is a trap for anything measured along the run ----

    /**
     * **A closed loop must not refuse itself at its own seam** — the trap, and one of the two reasons the
     * criterion asks about a *double normal* rather than about a distance along the run.
     *
     * The first and last stations of a closed run are neighbours through the closing span, a chord apart in
     * space; read as a plain arc difference their separation is the whole loop. Any criterion that excluded
     * neighbours by arc length would therefore have to make that distance **wrap**, and getting it wrong
     * refuses **every closed loop ever drawn**. Asking whether the approach is square to the run instead
     * disposes of the seam without knowing it is there: those two stations are joined by a segment that runs
     * *along* the spine, so they are rejected exactly as every other neighbour pair is.
     *
     * Both halves are asserted here rather than only the happy answer: the seam pair really is closer than
     * the section is wide, its arc difference really is a whole loop, and the loop is still a solid.
     */
    @Test
    fun aClosedLoopIsNotRefusedAtItsOwnSeam() {
        val reach = 6.0
        val loop =
            smooth(
                Vec3(0.0, 0.0, 0.0),
                Vec3(90.0, 0.0, 0.0),
                Vec3(90.0, 70.0, 0.0),
                Vec3(0.0, 70.0, 0.0),
                closed = true,
            )
        val frame = assertNotNull(Frames3.along(loop, up, reach = reach).first)
        assertTrue(frame.closed, "the frame knows the path closes")

        val first = frame.stations.first()
        val last = frame.stations.last()
        assertTrue(
            (last.at - first.at).length() < 2.0 * reach,
            "the seam's two stations are closer than the section is wide: ${(last.at - first.at).length()} mm",
        )
        assertTrue(last.s - first.s > 2.0 * reach, "and read as a plain arc difference they are a whole loop apart: ${last.s} mm")

        assertNull(Embedding.check(frame, reach, "the tube's radius (6 mm)").defect, "so the loop is embedded")
        assertManifold(sweptOrFail(loop, SweepProfile.Round(reach)).mesh, "the closed ring of tube")
    }

    /**
     * **A closed loop that genuinely runs alongside itself is still refused** — what the seam disposes of is
     * neighbours, not the question. A long thin closed circuit has its two legs 30 mm apart with a whole half-loop of
     * spine between them, so they are not neighbours by any reading: a tube fat enough to bridge the gap is
     * refused where the same circuit with a slimmer one is a solid.
     */
    @Test
    fun aClosedLoopThatRunsAlongsideItselfIsStillRefused() {
        val gap = 30.0
        val circuit =
            closedPolyline(
                Vec3(0.0, 0.0, 0.0),
                Vec3(200.0, 0.0, 0.0),
                Vec3(200.0, gap, 0.0),
                Vec3(0.0, gap, 0.0),
            )

        val slim = sweptOrFail(circuit, SweepProfile.Round(4.0))
        assertManifold(slim.mesh, "the thin circuit with a slim tube")

        val why = refusal(circuit, SweepProfile.Round(16.0))
        assertTrue(why.contains("cut into itself"), "a closed circuit whose legs meet is refused: $why")
        assertClose(namedDistance(why), gap, 1e-3, "and the approach it names is the gap between the legs: $why")
    }

    // ---- 7. what it costs ----

    /**
     * **The test is near-linear in the stations, and the count says so rather than a stopwatch.** A 40-turn
     * coil is sampled into a few thousand stations; an all-pairs proximity test would be millions of pairs on
     * every recompute of the body. The grid's cell is the query radius itself, so the pairs actually offered
     * are the ones that could possibly be within it — a couple of dozen per station rather than the whole run.
     *
     * Asserted as a **count**, which is deterministic, rather than as a time, which is not. The bound is
     * generous on purpose: what is being claimed is the complexity class, not a benchmark.
     */
    @Test
    fun aFortyTurnCoilIsNotAnAllPairsTest() {
        val path = coil(radius = 20.0, pitch = 20.0, turns = 40.0)
        val reach = 3.0
        val frame = assertNotNull(Frames3.along(path, up, reach = reach).first)
        val n = frame.stations.size
        assertTrue(n > 2000, "a 40-turn coil really is sampled into thousands of stations: $n")

        val report = Embedding.check(frame, reach, "the tube's radius (3 mm)")
        assertNull(report.defect, "and it is embedded")
        assertTrue(
            report.pairsExamined < 100 * n,
            "the grid offered ${report.pairsExamined} pairs for $n stations — an all-pairs test would be ${n.toLong() * (n - 1) / 2}",
        )
        assertTrue(
            report.pairsExamined.toLong() * 40 < n.toLong() * (n - 1) / 2,
            "which is more than an order of magnitude below quadratic: ${report.pairsExamined}",
        )
        assertManifold(sweptOrFail(path, SweepProfile.Round(reach)).mesh, "the 40-turn coil")
    }

    // ---- 8. two legs running side by side, and which way the section leans (session 59) ----

    /**
     * **The reported inside-out ring, and its twin that is a perfectly good ring** (the session-58 queue
     * entry, verbatim). A 100 × 60 closed plan polyline with a plain circular section drawn 100 mm off the
     * plan's origin used to build a shell of **−13458 mm³** — a ring turned inside out — with the node valid
     * and nothing refused, while the same drawing offset the other way is **+31402 mm³** of honest ring. The
     * sign of the offset was the whole difference and the criterion could not see it, for two reasons that
     * are both fixed here:
     *
     * - the two opposite legs of the loop are **antiparallel**, so the segment-to-segment solve was singular
     *   and its clamped answer landed on the loop's own **corner**, where the normal-cone test threw the pair
     *   out — the one bottleneck that matters was never offered ([Embedding], the overlap interval);
     * - and once offered, `2 × reach` would have refused **both** rings, because a reach is what the section
     *   reaches in *any* direction. What a pair of legs needs is what the two sections reach **towards each
     *   other**, which is 206 mm inwards and −194 mm outwards on this very drawing.
     *
     * The section's own y runs across the plan (the frame's `bi`), and this loop is drawn anticlockwise, so
     * `−100` is the section standing *inside* the loop and `+100` outside it.
     */
    @Test
    fun aSectionStandingFurtherInsideTheLoopThanItsInradiusIsRefusedAndTheSameSectionOutsideIsNot() {
        val plan =
            closedPolyline(
                Vec3(0.0, 0.0, 0.0),
                Vec3(100.0, 0.0, 0.0),
                Vec3(100.0, 60.0, 0.0),
                Vec3(0.0, 60.0, 0.0),
            )

        val why = refusal(plan, SweepProfile.Section(circleAt(-100.0, 3.0)))
        assertTrue(why.contains("cut into itself"), "the ring that would fold through itself is refused: $why")
        assertClose(namedDistance(why), 60.0, 1e-3, "and the approach it names is the loop's own width: $why")
        assertTrue(why.contains("needs 206 mm between them"), "with what the two sections reach towards each other: $why")
        val (a, b) = namedStations(why)
        assertTrue(b - a > 60.0, "the two positions are a half-loop apart along the run: $a mm and $b mm")

        val ring = sweptOrFail(plan, SweepProfile.Section(circleAt(100.0, 3.0)))
        assertManifold(ring.mesh, "the same section standing outside the loop")
        val v = Geom3.volume(ring.mesh)
        assertTrue(v > 0.0, "and it is a solid the right way out, not a shell turned through itself: $v mm^3")
        assertClose(v, 31402.154, 0.01, "the ring the queue entry measured")
    }

    /**
     * **…and it heals by the parameter the refusal names** (OP-3). *"Thin the section, or open the run out"* —
     * here the section's own centre is a point of the drawing, so bringing it nearer the run makes the very
     * same node a solid, with no rebuild and nothing restated.
     */
    @Test
    fun theInsideOutRingHealsWhenTheSectionComesNearerTheRun() {
        val cx = Construction()
        val corners =
            listOf(0.0 to 0.0, 100.0 to 0.0, 100.0 to 60.0, 0.0 to 60.0).map { (x, y) -> cx.planPoint(x, y) }
        val path = cx.pathThrough(corners, closed = true)
        val centre = cx.freePoint("section", 0.0.mm, (-100.0).mm)
        val section = cx.region(cx.loop(cx.circleCR(centre, cx.const(3.0.mm))))
        val ring = cx.sweep(path, cx.planeXY(), section, cx.const(0.0.deg), cx.const(0.0.deg))

        val bad = Evaluator().eval(ring.node)
        assertTrue(bad is EvalResult.Invalid, "a section 100 mm inside a loop 60 mm wide is not a body: $bad")
        assertTrue((bad as EvalResult.Invalid).reason.contains("cut into itself"), "and says why: ${bad.reason}")

        cx.set(centre, 0.0.mm, (-20.0).mm)
        assertTrue(Evaluator().eval(ring.node) is EvalResult.Ok, "the same node is a solid once the section comes in")
        val mesh = Evaluator().solid(ring).mesh
        assertManifold(mesh, "the ring whose section came nearer its run")
        assertTrue(Geom3.volume(mesh) > 0.0, "the right way out: ${Geom3.volume(mesh)} mm^3")
    }

    /**
     * **A U-shaped *open* run is judged by the gap between its legs**, which is what says this is a statement
     * about parallel legs and not about closed loops. Two antiparallel legs, no seam anywhere, and the same
     * lines of code: a section that fits between them sweeps a solid, one that does not is refused by name.
     *
     * The second half is the degenerate case at its purest — the legs stand far enough apart that each is
     * sampled into a **single** span, so there is no interior vertex for a clamped solve to land on and the
     * overlap interval is the only thing that can answer.
     */
    @Test
    fun aUShapedOpenRunIsJudgedByTheGapBetweenItsLegs() {
        val gap = 50.0
        val u = polyline(Vec3(0.0, 0.0, 0.0), Vec3(200.0, 0.0, 0.0), Vec3(200.0, gap, 0.0), Vec3(0.0, gap, 0.0))
        val fits = sweptOrFail(u, SweepProfile.Round(20.0))
        assertManifold(fits.mesh, "the U whose section fits between its own legs")
        assertTrue(Geom3.volume(fits.mesh) > 0.0, "and it is a solid: ${Geom3.volume(fits.mesh)} mm^3")

        val why = refusal(u, SweepProfile.Round(30.0))
        assertTrue(why.contains("cut into itself"), "the U whose section does not fit is refused: $why")
        assertClose(namedDistance(why), gap, 1e-3, "and the approach it names is the gap between the legs: $why")

        // …and again where each leg is one span, so only the overlap interval can find the bottleneck. Its
        // two legs run 0…200 mm and 450…650 mm along the path, and the pair reported must stand in the
        // **middle** of them: a clamped answer to the singular solve can only ever name an end.
        val wide = polyline(Vec3(0.0, 0.0, 0.0), Vec3(200.0, 0.0, 0.0), Vec3(200.0, 250.0, 0.0), Vec3(0.0, 250.0, 0.0))
        assertManifold(sweptOrFail(wide, SweepProfile.Round(100.0)).mesh, "the wide U with a section that fits")
        val wideWhy = refusal(wide, SweepProfile.Round(140.0))
        assertClose(namedDistance(wideWhy), 250.0, 1e-3, "one span per leg, and the gap is still found: $wideWhy")
        val (out, home) = namedStations(wideWhy)
        assertTrue(out > 20.0 && out < 180.0, "the approach is measured along the leg, not off its end: $out mm")
        assertTrue(home > 470.0 && home < 630.0, "and along the other leg too: $home mm")
    }

    /**
     * **Legs that overlap only in part are compared inside that part.** The two legs here run past each other
     * with 100 mm of shared span out of 300 — the rest of each leg is alongside nothing at all — and the
     * bottleneck must be reported from **inside** the overlap, which is the only place the two are actually
     * side by side.
     */
    @Test
    fun legsThatOverlapOnlyPartlyAreComparedInsideTheOverlap() {
        // out along +x at z = 0, down and back the long way, up again well clear of the outbound leg, and
        // home along +x at z = 40 — so the only two things anywhere near each other are the first and last
        // legs, which share exactly x ∈ [0, 100] of their 400 mm and 300 mm spans
        val route =
            polyline(
                Vec3(0.0, 0.0, 0.0),
                Vec3(400.0, 0.0, 0.0),
                Vec3(400.0, 0.0, -400.0),
                Vec3(-200.0, 0.0, -400.0),
                Vec3(-200.0, 0.0, 40.0),
                Vec3(100.0, 0.0, 40.0),
            )
        val why = refusal(route, SweepProfile.Round(21.0))
        assertClose(namedDistance(why), 40.0, 1e-3, "the gap between the two legs: $why")
        val (a, b) = namedStations(why)
        // the outbound leg runs 0…400 mm along the path and the homeward one 1840…2140 mm; the shared
        // stretch is 0…100 mm along the path on the way out and 2040…2140 mm on the way home
        // strictly inside, at both ends: the edges of the shared stretch are exactly what a clamped answer to
        // the singular solve names, and the two legs are side by side across the whole of it
        assertTrue(a > 0.0 && a < 100.0, "the approach is inside the shared span on the way out: $a mm")
        assertTrue(b > 2040.0 && b < 2140.0, "and inside it on the way home: $b mm")

        val fits = sweptOrFail(route, SweepProfile.Round(15.0))
        assertManifold(fits.mesh, "the same route with a section that fits between the legs")
        assertTrue(Geom3.volume(fits.mesh) > 0.0, "a solid: ${Geom3.volume(fits.mesh)} mm^3")
    }

    /**
     * **Two legs a nanoradian off parallel are the same line**, because the spine is a polyline within
     * [constructit.geom.GeomMath.TESS_TOL_MM] of the curve it samples and a direction difference smaller than
     * that tolerance *over the pieces' own length* is not a difference the polyline has. Asserted as an
     * identity rather than as a tolerance: the skewed U and the exactly parallel one report the same approach.
     *
     * This is the case a bare `den > 1e-12` guard gets wrong in the other direction — the solve is not
     * singular, merely hopelessly ill-conditioned, and its answer runs away to the ends of the segments.
     */
    @Test
    fun legsSkewedByANanoradianAreStillTheSameLine() {
        val square = polyline(Vec3(0.0, 0.0, 0.0), Vec3(200.0, 0.0, 0.0), Vec3(200.0, 50.0, 0.0), Vec3(0.0, 50.0, 0.0))
        val skewed =
            polyline(
                Vec3(0.0, 0.0, 0.0),
                Vec3(200.0, 0.0, 0.0),
                Vec3(200.0, 50.0, 0.0),
                Vec3(0.0, 50.0 + 2e-6, 0.0),
            )
        val straight = namedDistance(refusal(square, SweepProfile.Round(30.0)))
        val askew = namedDistance(refusal(skewed, SweepProfile.Round(30.0)))
        assertClose(askew, straight, 1e-6, "a 1e-8 rad skew is invisible to the criterion, as it must be")
        assertClose(askew, 50.0, 1e-3, "and both name the gap between the legs")
    }

    /**
     * **Collinear pieces of one straight leg overlap in nothing and are never a bottleneck.** The overlap
     * interval is what separates *side by side* from *one after the other*: two pieces cut from the same
     * straight run project onto disjoint stretches of their common axis, so there is no family of double
     * normals, the nearest points are the pair of ends that face each other, and the normal-cone test rejects
     * them for running **along** the spine — exactly as it always did. Asserted through the report's own
     * sentinel, so it is the criterion being read and not the sweep's verdict.
     */
    @Test
    fun collinearPiecesOfOneLegAreNotSideBySide() {
        val reach = 12.0
        val long = report(polyline(Vec3.ZERO, Vec3(900.0, 0.0, 0.0)), reach)
        assertNull(long.defect, "a straight run cut into dozens of collinear pieces is still never near itself")
        assertEquals(Double.MAX_VALUE, long.closest, "and has no bottleneck at all: ${long.closest}")

        val zigzag =
            report(
                polyline(Vec3.ZERO, Vec3(300.0, 0.0, 0.0), Vec3(300.0, 300.0, 0.0), Vec3(600.0, 300.0, 0.0)),
                reach,
            )
        assertNull(zigzag.defect, "nor is a run of straight legs that never comes back alongside itself")
        assertEquals(Double.MAX_VALUE, zigzag.closest, "with no bottleneck anywhere: ${zigzag.closest}")
    }

    /** The reach's two halves are one statement, so the local failure keeps its own words where both fire. */
    @Test
    fun whereBothHalvesOfTheReachFireTheLocalOneSpeaks() {
        val hairpin = smooth(Vec3(0.0, 0.0, 0.0), Vec3(20.0, 14.0, 0.0), Vec3(40.0, 0.0, 0.0))
        val tightest = assertNotNull(Frames3.along(hairpin, up, reach = 1.0).first).stations.maxOf { it.curvature }
        val why = refusal(hairpin, SweepProfile.Round(1.0 / tightest * 1.5))
        assertTrue(why.contains("pass through itself"), "the local term is the one that speaks: $why")
        assertTrue(why.contains("is larger than the bend"), "in its own words: $why")
        assertTrue(!why.contains("cut into itself"), "and the global term does not talk over it: $why")
    }
}
