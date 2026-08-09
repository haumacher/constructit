package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.Point3Ref
import constructit.dsl.solid
import constructit.geom.Circle
import constructit.geom.Curve3Element
import constructit.geom.Curves3
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
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A corner mitres away only as much run as there is** (OP-9's *watertight or refused*; OP-26, step 2's
 * corner treatment; OP-3's refusal that heals).
 *
 * The third way a swept body folds, and the one neither term of the embedding criterion can see. At a corner
 * of turn `θ` the mitred join trims `u·tan(θ/2)` off **both** legs it joins, where `u` is how far the profile
 * stands to the **inside** of that turn; two corners whose trims together exceed the run between them hand
 * the band back past where it started. It is not a proximity — a triangle's three legs all *touch*, so it has
 * no non-neighbouring pair to be a bottleneck at — and it is not a curvature, since a polyline corner has
 * none on either side. Every fixture here was **silent** before: edge-manifold, `assertManifold`-clean, and
 * for a symmetric section positively volumed, because a mitre adds outside exactly what it removes inside.
 *
 * The decision the fix rests on is asserted here too, from both sides: **the refusals speak about the curve.**
 * A corner is a place where the run's own tangent jumps, read off the analytic pieces, so a segment and an arc
 * joined tangentially have none however finely the arc is sampled — and a station sampled along a smooth bend
 * never generates a refusal of its own, because doing so would refuse at `h ≥ R·cos(Δ/2)`, inside the analytic
 * limit the local term is stated at and by an amount that shrinks as the mesh is refined.
 */
class SweepCornerTest {
    // ---- the fixtures ----

    private val up = Vec3.Z

    private fun polyline(
        vararg p: Vec3,
        closed: Boolean = false,
    ) = Path3(Curves3.straightThrough(p.toList(), closed), closed)

    /** A circle of radius [r] drawn [cy] mm off the section's own origin — the off-centre section, plainly. */
    private fun circleAt(
        cy: Double,
        r: Double,
    ) = Region(Loop(listOf(ProfileElement.CircleE(Circle(Vec2(0.0, cy), r)))), emptyList())

    /**
     * A rectangle of the stated half-extents standing [cy] mm off the run — a **polygon**, so its support in
     * any direction is a vertex and the boundary of the criterion can be asserted to the last bit rather than
     * to a tessellated circle's nearest chord.
     */
    private fun rectAt(
        cy: Double,
        halfX: Double,
        halfY: Double,
    ): Region {
        val pts =
            listOf(
                Vec2(-halfX, cy - halfY),
                Vec2(halfX, cy - halfY),
                Vec2(halfX, cy + halfY),
                Vec2(-halfX, cy + halfY),
            )
        return Region(Loop(pts.indices.map { ProfileElement.Seg(Segment(pts[it], pts[(it + 1) % pts.size])) }), emptyList())
    }

    /** How far a section reaches from the run in *any* direction — the isotropic number, for the contrast. */
    private fun reachOf(region: Region): Double =
        region.outer.elements.maxOf { e -> (e as ProfileElement.Seg).segment.let { maxOf(it.a.length(), it.b.length()) } }

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

    /** A body that builds, checked for the one thing every solid in this project owes (OP-9). */
    private fun goodBody(
        path: Path3,
        profile: SweepProfile,
        what: String,
    ): Double {
        val mesh = sweptOrFail(path, profile).mesh
        assertManifold(mesh, what)
        val v = Geom3.volume(mesh)
        assertTrue(v > 0.0, "$what is a solid the right way out, not a shell turned through itself: $v mm^3")
        return v
    }

    /** How many stations of this run the curve itself turns at — the corner set, read off the frame. */
    private fun cornersOf(
        path: Path3,
        reach: Double = 3.0,
    ): List<Double> {
        val (frame, why) = Frames3.along(path, up, reach = reach)
        return assertNotNull(frame, why).stations.filter { it.corner }.map { it.s }
    }

    // ---- 1. the queue's own route: two corners closer together than the two mitres eating into them ----

    /**
     * **Session 40's reproduction, verbatim.** `(0,0,0) → (300,0,0) → (302.62,30,0) → (0,56.24,0)` with an
     * 18 mm tube: two ~85° corners **30.11 mm** apart, each mitre eating about 16.5 mm, so the two rings
     * cross and the band between them is handed back past where it started.
     *
     * It was silent for two sessions and it is the worst class of output this project can produce: the shell
     * was edge-manifold, `assertManifold` passed it and it reported **+644255 mm³**, because a symmetric
     * section's mitre adds outside exactly what it removes inside — which is also why the signed-volume guard
     * session 59 considered would not have caught it (see *the embedding criterion corrected* under OP-26).
     *
     * The refusal names **both corners**, what each takes, and the run there is between them, because
     * "this sweep folds" is not something anyone can act on and "these two corners take 34.5 mm off the
     * 30.1 mm between them" is.
     */
    @Test
    fun theRouteWhoseTwoMitresEatTheSpanBetweenThemIsRefusedNamingBothCorners() {
        val route = polyline(Vec3(0.0, 0.0, 0.0), Vec3(300.0, 0.0, 0.0), Vec3(302.62, 30.0, 0.0), Vec3(0.0, 56.24, 0.0))

        // neither existing term can see it: a polyline has no curvature anywhere, and its legs all touch
        val frame = assertNotNull(Frames3.along(route, up, reach = 18.0).first)
        assertEquals(0.0, frame.stations.maxOf { it.curvature }, "every station's curvature is exactly zero")

        val why = refusal(route, SweepProfile.Round(18.0))
        assertTrue(
            why.contains("the corners 300 mm and 330.114 mm along the path"),
            "the refusal names both corners by where they stand: $why",
        )
        assertTrue(
            why.contains("mitre 34.508 mm off the 30.114 mm of run between them"),
            "…what the two of them take together, and what there was to take it from: $why",
        )
        assertTrue(why.contains("fold back on itself"), "…what would go wrong: $why")
        assertTrue(
            why.contains("thin the section, move it towards the outside of the turn, or open the corners out"),
            "…and three ways out: $why",
        )
    }

    /**
     * **…and it heals by the first cure the refusal names** (OP-3): the tube's radius is an ordinary
     * parameter, so thinning the wire makes the very same node a body, with nothing rebuilt.
     */
    @Test
    fun theFoldedRouteHealsWhenTheTubeIsThinned() {
        val cx = Construction()
        val corners =
            listOf(0.0 to 0.0, 300.0 to 0.0, 302.62 to 30.0, 0.0 to 56.24).map { (x, y) -> cx.planPoint(x, y) }
        val path = cx.pathThrough(corners)
        val radius = cx.parameter("wire", 18.0.mm)
        val tube = cx.tube(path, cx.planeXY(), radius, cx.const(0.0.deg), cx.const(0.0.deg))

        val bad = Evaluator().eval(tube.node)
        assertTrue(bad is EvalResult.Invalid, "the 18 mm tube folds at its two corners: $bad")
        assertTrue((bad as EvalResult.Invalid).reason.contains("fold back on itself"), "and says so: ${bad.reason}")

        cx.set(radius, 8.0.mm)
        assertTrue(Evaluator().eval(tube.node) is EvalResult.Ok, "the same node is a solid once the section thins")
        val mesh = Evaluator().solid(tube).mesh
        assertManifold(mesh, "the thinned tube through the same two corners")
        assertTrue(Geom3.volume(mesh) > 0.0, "the right way out: ${Geom3.volume(mesh)} mm^3")
    }

    // ---- 2. the open elbow: one corner, one cap, and a leg shorter than the trim ----

    /**
     * **Session 59's open elbow, verbatim.** `(0,0,0) → (80,0,0) → (80,80,0)` with a circle of radius 3
     * standing 100 mm to the **inside** of the corner: `103·tan 45° = 103 mm` against an 80 mm leg, so the
     * corner eats more than the whole leg. It built **−1121.5 mm³** — an asymmetric section's fold comes out
     * plainly inside out, where the symmetric one above keeps a positive volume.
     *
     * An end leg of an open run has **one** corner and a cap, and the refusal says so rather than quoting a
     * trim of zero: the station at the end of an open path has its own tangent for a mitre plane, so its trim
     * is exactly zero and the cap falls out of the same formula with no case for it.
     */
    @Test
    fun theOpenElbowWhoseSectionStandsFurtherInThanTheLegIsLongIsRefused() {
        val elbow = polyline(Vec3(0.0, 0.0, 0.0), Vec3(80.0, 0.0, 0.0), Vec3(80.0, 80.0, 0.0))

        val why = refusal(elbow, SweepProfile.Section(circleAt(-100.0, 3.0)))
        assertTrue(
            why.contains("the corner 80 mm along the path mitres 103 mm off the 80 mm of run before it"),
            "the one corner, what it takes, and the leg it takes it from: $why",
        )
        assertTrue(why.contains("fold back on itself"), "…and what would go wrong: $why")
    }

    /**
     * **`u` is a direction and not a size** — the half of this the record found first (session 59). The very
     * same section of the very same **reach**, standing 100 mm to the *outside* of the same corner, is an
     * ordinary body: a mitre eats what stands inside the turn and gives back what stands outside it, so a
     * criterion stated in the section's reach could not tell these two drawings apart.
     */
    @Test
    fun theSameSectionOnTheOutsideOfTheTurnIsAnOrdinaryBody() {
        val elbow = polyline(Vec3(0.0, 0.0, 0.0), Vec3(80.0, 0.0, 0.0), Vec3(80.0, 80.0, 0.0))
        goodBody(elbow, SweepProfile.Section(circleAt(100.0, 3.0)), "the section standing outside the turn")
    }

    /** …and it heals by the second cure: bring the section in towards the run (OP-3). */
    @Test
    fun theFoldedElbowHealsWhenTheSectionComesNearerTheRun() {
        val cx = Construction()
        val corners = listOf(0.0 to 0.0, 80.0 to 0.0, 80.0 to 80.0).map { (x, y) -> cx.planPoint(x, y) }
        val path = cx.pathThrough(corners)
        val centre = cx.freePoint("section", 0.0.mm, (-100.0).mm)
        val section = cx.region(cx.loop(cx.circleCR(centre, cx.const(3.0.mm))))
        val body = cx.sweep(path, cx.planeXY(), section, cx.const(0.0.deg), cx.const(0.0.deg))

        val bad = Evaluator().eval(body.node)
        assertTrue(bad is EvalResult.Invalid, "a section 100 mm inside a corner with 80 mm legs is not a body: $bad")
        assertTrue((bad as EvalResult.Invalid).reason.contains("fold back on itself"), "and says why: ${bad.reason}")

        cx.set(centre, 0.0.mm, (-20.0).mm)
        assertTrue(Evaluator().eval(body.node) is EvalResult.Ok, "the same node is a solid once the section comes in")
        val mesh = Evaluator().solid(body).mesh
        assertManifold(mesh, "the elbow whose section came nearer its run")
        assertTrue(Geom3.volume(mesh) > 0.0, "the right way out: ${Geom3.volume(mesh)} mm^3")
    }

    // ---- 3. the closed triangle: every leg has two corners, and none of them is a proximity ----

    /**
     * **Session 59's closed triangle, verbatim.** `(0,0,0) → (300,0,0) → (150,260,0)`, closed, with the same
     * circle of radius 3 standing 120 mm inside: each corner eats about `123·tan 60° = 213 mm` out of a
     * 300 mm leg, so the two of them take 426 mm of a 300 mm leg and the ring comes out at **−9721.9 mm³**.
     *
     * A triangle is also the shape that shows why the global term can never reach this: all three of its legs
     * *touch*, so it has no non-neighbouring pair to be a bottleneck at at all. Asserted here rather than
     * argued — the criterion's own report finds nothing.
     */
    @Test
    fun theClosedTriangleWhoseCornersEatTheirLegsIsRefused() {
        val triangle = polyline(Vec3(0.0, 0.0, 0.0), Vec3(300.0, 0.0, 0.0), Vec3(150.0, 260.0, 0.0), closed = true)

        val why = refusal(triangle, SweepProfile.Section(circleAt(-120.0, 3.0)))
        assertTrue(
            why.contains("the corners 0 mm and 300 mm along the path"),
            "the refusal names the closed run's first leg by its two corners: $why",
        )
        assertTrue(
            why.contains("mitre 425.927 mm off the 300 mm of run between them"),
            "…the two corners each taking `123·tan 60°` of a 300 mm leg: $why",
        )
        assertTrue(why.contains("fold back on itself"), "…and what would go wrong: $why")
    }

    /** …and it heals by moving the section out towards the run, the same drag one dimension along (OP-3). */
    @Test
    fun theFoldedTriangleHealsWhenTheSectionComesNearerTheRun() {
        val cx = Construction()
        val corners = listOf(0.0 to 0.0, 300.0 to 0.0, 150.0 to 260.0).map { (x, y) -> cx.planPoint(x, y) }
        val path = cx.pathThrough(corners, closed = true)
        val centre = cx.freePoint("section", 0.0.mm, (-120.0).mm)
        val section = cx.region(cx.loop(cx.circleCR(centre, cx.const(3.0.mm))))
        val ring = cx.sweep(path, cx.planeXY(), section, cx.const(0.0.deg), cx.const(0.0.deg))

        val bad = Evaluator().eval(ring.node)
        assertTrue(bad is EvalResult.Invalid, "a section 120 mm inside a triangle of 300 mm sides is not a body: $bad")
        assertTrue((bad as EvalResult.Invalid).reason.contains("fold back on itself"), "and says why: ${bad.reason}")

        cx.set(centre, 0.0.mm, (-20.0).mm)
        assertTrue(Evaluator().eval(ring.node) is EvalResult.Ok, "the same node is a solid once the section comes in")
        val mesh = Evaluator().solid(ring).mesh
        assertManifold(mesh, "the triangular ring whose section came nearer its run")
        assertTrue(Geom3.volume(mesh) > 0.0, "the right way out: ${Geom3.volume(mesh)} mm^3")
    }

    // ---- 4. the boundary, from both sides ----

    /**
     * **The boundary is `≥`, asserted from both sides at a hundredth of a millimetre.** A right-angle corner
     * mitres exactly what the section stands to the inside of it (`tan 45° = 1`), so a rectangular section
     * reaching 80 mm inside an 80 mm leg is the exact equality — and at equality the two rings are one ring,
     * the band between them is a sheet of degenerate triangles, and the watertightness the sweep is built on
     * (consecutive bands sharing one ring, each band a prism of positive length) has no body left to be
     * about. The limit of bodies is not a body, so equality refuses; a hundredth of a millimetre inside it
     * is an ordinary solid.
     *
     * A **polygon** section is used rather than a circle because its support in any direction *is* a vertex,
     * so the arithmetic on both sides of the limit is exact rather than resolved to a tessellated chord.
     */
    @Test
    fun aLegExactlyAsLongAsItsTrimRefusesAndAHundredthLessBuilds() {
        val elbow = polyline(Vec3(0.0, 0.0, 0.0), Vec3(80.0, 0.0, 0.0), Vec3(80.0, 80.0, 0.0))

        val why = refusal(elbow, SweepProfile.Section(rectAt(-78.0, 2.0, 2.0)))
        assertTrue(
            why.contains("mitres 80 mm off the 80 mm of run before it"),
            "the trim exactly meets the leg, and that refuses: $why",
        )

        val v = goodBody(elbow, SweepProfile.Section(rectAt(-77.99, 2.0, 2.0)), "the section a hundredth further out")
        assertTrue(v > 0.0, "a hundredth of a millimetre inside the limit is an ordinary body: $v mm^3")
    }

    /**
     * **Two sections of the *same* reach, one of which folds** — the whole content of *`u` is a direction*,
     * stated where a reach-based criterion is blind. Both reach 90.022 mm from the run; the one that reaches
     * it **along the frame's reference** (upwards, out of the plan) stands 2 mm to the inside of the turn and
     * is an ordinary body, while the one that reaches it **into the turn** takes 90 mm off an 80 mm leg.
     */
    @Test
    fun anAsymmetricSectionFoldsWhereASymmetricOneOfTheSameReachDoesNot() {
        val elbow = polyline(Vec3(0.0, 0.0, 0.0), Vec3(80.0, 0.0, 0.0), Vec3(80.0, 80.0, 0.0))
        val standing = rectAt(0.0, 90.0, 2.0)
        val leaning = rectAt(-88.0, 2.0, 2.0)
        assertClose(reachOf(standing), reachOf(leaning), 1e-9, "the two sections reach exactly as far from the run")

        goodBody(elbow, SweepProfile.Section(standing), "the section standing up out of the plan")
        val why = refusal(elbow, SweepProfile.Section(leaning))
        assertTrue(why.contains("mitres 90 mm off the 80 mm of run before it"), "the leaning one folds: $why")
    }

    // ---- 5. the corners are the curve's, not the mesh's ----

    /**
     * **A mitre exists where the *curve* turns, not where two pieces meet.** A segment handing over to an arc
     * that leaves along the segment's own direction is not a corner however finely the arc is sampled — the
     * chord leaving a sampled arc stands half a sampling step off the arc's own tangent, so a chord-based
     * reading would call a fillet's tangent join a corner, and would call it one by an amount that changes
     * when the mesh is refined.
     *
     * The same three points joined by three **segments** instead turn discontinuously at both of them, and
     * both are corners. Same piece boundaries, opposite answers, which is what "keys on tangent discontinuity"
     * has to mean.
     */
    @Test
    fun aTangentJoinIsNoCornerWhileTheSameJoinBetweenSegmentsIs() {
        val mixed =
            Path3(
                listOf(
                    Curve3Element.Seg3(Vec3(0.0, 0.0, 0.0), Vec3(100.0, 0.0, 0.0)),
                    Curve3Element.Arc3(Vec3(100.0, 50.0, 0.0), Vec3.X, Vec3.Y, 50.0, -PI / 2.0, PI / 2.0),
                    Curve3Element.Seg3(Vec3(150.0, 50.0, 0.0), Vec3(150.0, 150.0, 0.0)),
                ),
            )
        assertTrue(cornersOf(mixed).isEmpty(), "a segment, an arc and a segment joined tangentially turn nowhere")

        val kinked = polyline(Vec3(0.0, 0.0, 0.0), Vec3(100.0, 0.0, 0.0), Vec3(150.0, 50.0, 0.0), Vec3(150.0, 150.0, 0.0))
        val at = cornersOf(kinked)
        assertEquals(2, at.size, "the same three joins made of segments turn at two of them: $at")
        assertClose(at[0], 100.0, 1e-9, "the first corner stands where the run kinks")
        assertClose(at[1], 100.0 + 50.0 * kotlin.math.sqrt(2.0), 1e-9, "…and the second where it kinks again")
    }

    /**
     * **…and the two runs are refused, or not, in two different voices.** With a section standing 100 mm to
     * the inside, the kinked run folds at a **corner** and says so, while the smooth one is the *local*
     * term's business — it reaches 100 mm into a 50 mm bend — and keeps the words it always had. That is the
     * curve-versus-mesh decision made visible: a fold on a smooth run is a statement about an analytic
     * curvature, and a fold at a corner is a statement about a join.
     */
    @Test
    fun theSmoothRunIsTheLocalTermsBusinessAndTheKinkedOneIsTheCornerTerms() {
        val mixed =
            Path3(
                listOf(
                    Curve3Element.Seg3(Vec3(0.0, 0.0, 0.0), Vec3(100.0, 0.0, 0.0)),
                    Curve3Element.Arc3(Vec3(100.0, 50.0, 0.0), Vec3.X, Vec3.Y, 50.0, -PI / 2.0, PI / 2.0),
                    Curve3Element.Seg3(Vec3(150.0, 50.0, 0.0), Vec3(150.0, 150.0, 0.0)),
                ),
            )
        val smoothWhy = refusal(mixed, SweepProfile.Section(circleAt(-100.0, 3.0)))
        assertTrue(smoothWhy.contains("reach into the bend"), "the smooth run is judged by its bend: $smoothWhy")
        assertTrue(smoothWhy.contains("pass through itself"), "…in the local term's own words: $smoothWhy")

        val kinked = polyline(Vec3(0.0, 0.0, 0.0), Vec3(100.0, 0.0, 0.0), Vec3(150.0, 50.0, 0.0), Vec3(150.0, 150.0, 0.0))
        val kinkedWhy = refusal(kinked, SweepProfile.Section(circleAt(-100.0, 3.0)))
        assertTrue(kinkedWhy.contains("mitre"), "the kinked one is judged by its corners: $kinkedWhy")
        assertTrue(kinkedWhy.contains("fold back on itself"), "…in the corner term's own words: $kinkedWhy")
    }

    /**
     * **The user's rounded pillar has no corners, seam and all.** A closed run of four segments and four
     * fillets — the `roundrect` footprint the lift was built for (`LiftedRunTest`) — turns tangentially at
     * every one of its eight joins *and* where its last piece hands back to its first, so nothing here is a
     * corner and the foundation swept round it is untouched by this criterion.
     */
    @Test
    fun aClosedRoundedRectangleTurnsNowhereAndSweepsAsItAlwaysDid() {
        val run = roundrect(96.0, 81.0, 10.0)
        assertTrue(cornersOf(run).isEmpty(), "a rounded rectangle's joins and its seam are all tangent")
        goodBody(run, SweepProfile.Section(rectAt(20.0, 13.5, 9.0)), "a foundation swept round the rounded pillar")
    }

    /** **A helix turns nowhere either** — one analytic piece, no joins, so no corner and no leg. */
    @Test
    fun aHelixHasNoCornersAtAll() {
        val coil =
            Path3(listOf(Curve3Element.Helix3.about(Vec3.ZERO, Vec3.Z, Vec3.X, 20.0, 20.0, 3.0, Handedness.RIGHT)))
        assertTrue(cornersOf(coil).isEmpty(), "a helix is one smooth piece from end to end")
        goodBody(coil, SweepProfile.Round(3.0), "the coil this criterion must not touch")
    }

    /**
     * **Two corners turning opposite ways *shear* a band rather than shortening it**, which is why the two
     * trims are added as **vectors** and not as sizes. The queue's own route with its second corner turning
     * back instead of on — the same 18 mm tube, the same ~85° corners, the same 30 mm between them — is a
     * perfectly ordinary zig-zag: the vertex the first mitre cuts back is the one the second mitre lets run
     * on. Adding the two supports separately would refuse it, which would be a new silent-wrong-output bug
     * pointing the other way.
     */
    @Test
    fun aZigZagWhoseCornersTurnOppositeWaysKeepsBuilding() {
        val zigzag =
            polyline(
                Vec3(0.0, 0.0, 0.0),
                Vec3(300.0, 0.0, 0.0),
                Vec3(302.62, 30.0, 0.0),
                Vec3(600.0, 33.0, 0.0),
            )
        assertEquals(2, cornersOf(zigzag, 18.0).size, "it has the same two corners, 30 mm apart")
        goodBody(zigzag, SweepProfile.Round(18.0), "the zig-zag whose mitres shear instead of biting")
    }

    /** A rounded rectangle in the plan, as segments and exact quarter arcs — the lift's own everyday run. */
    private fun roundrect(
        w: Double,
        h: Double,
        r: Double,
    ): Path3 {
        val x = w / 2.0
        val y = h / 2.0

        fun arc(
            cx: Double,
            cy: Double,
            from: Double,
        ) = Curve3Element.Arc3(Vec3(cx, cy, 0.0), Vec3.X, Vec3.Y, r, from, PI / 2.0)
        return Path3(
            listOf(
                Curve3Element.Seg3(Vec3(-x + r, -y, 0.0), Vec3(x - r, -y, 0.0)),
                arc(x - r, -y + r, -PI / 2.0),
                Curve3Element.Seg3(Vec3(x, -y + r, 0.0), Vec3(x, y - r, 0.0)),
                arc(x - r, y - r, 0.0),
                Curve3Element.Seg3(Vec3(x - r, y, 0.0), Vec3(-x + r, y, 0.0)),
                arc(-x + r, y - r, PI / 2.0),
                Curve3Element.Seg3(Vec3(-x, y - r, 0.0), Vec3(-x, -y + r, 0.0)),
                arc(-x + r, -y + r, PI),
            ),
            closed = true,
        )
    }
}
