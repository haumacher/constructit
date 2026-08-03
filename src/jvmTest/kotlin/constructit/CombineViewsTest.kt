package constructit

import constructit.geom.Arc
import constructit.geom.Bezier
import constructit.geom.Combine3
import constructit.geom.Curve3Element
import constructit.geom.Curves3
import constructit.geom.GeomMath
import constructit.geom.Path3
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Segment
import constructit.geom.Vec2
import constructit.geom.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Combine two views** (OP-26, step 5) at the level of the geometry: two planar curves in two non-parallel
 * spaces, and the curve in space whose projection into each of them is the one drawn there.
 *
 * One assertion runs through the whole file and everything else is a special case of it: **sample the run
 * densely, project each sample into each parent space, and measure how far it stands from the curve drawn
 * there.** That is the *definition* of the operation rather than a restatement of the code — the measurement
 * knows nothing about how the run was built, only about where the two drawings are — and it is what the
 * stated error ([Combine3.FIT_TOL_MM]) is a promise about.
 *
 * The exact cases are asserted as *exact*: two straight views give one straight segment, and a cubic view
 * against a straight one gives one cubic, both to a thousandth of a nanometre rather than to a tolerance.
 *
 * The gesture, the parenting and everything built on a combined run are [CombineViewsToolTest]'s.
 */
class CombineViewsTest {
    /** The plan: the world's XY plane, seen from +Z. */
    private val plan = Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)

    /**
     * The elevation: the world's XZ plane, whose normal is −Y — the ordinary second view of a drawing board,
     * folded up about the x axis. The two spaces meet along **+X**, which is therefore the common direction
     * every match below is made by.
     */
    private val elevation = Plane3(Vec3.ZERO, Vec3.X, Vec3.Z)

    private fun seg(
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
    ) = ProfileElement.Seg(Segment(Vec2(ax, ay), Vec2(bx, by)))

    private fun run(
        a: List<ProfileElement>,
        b: List<ProfileElement>,
        pa: Plane3 = plan,
        pb: Plane3 = elevation,
    ): Path3 {
        val (path, why) = Combine3.combined(pa, a, pb, b)
        return assertNotNull(path, "the two views were refused: $why")
    }

    private fun refusal(
        a: List<ProfileElement>,
        b: List<ProfileElement>,
        pa: Plane3 = plan,
        pb: Plane3 = elevation,
    ): String {
        val (path, why) = Combine3.combined(pa, a, pb, b)
        assertNull(path, "these two views were expected to be refused")
        return assertNotNull(why, "a refusal says why")
    }

    // ---- the independent measurement: how far a point stands from a drawn curve ----

    /**
     * The distance from [p] to the piece [e], in the plane's own coordinates — **exact** for a segment and an
     * arc, and to about 1e-12 mm for a cubic (a sampled bracket, ternary-bisected; a cubic's foot point is a
     * quintic and has no closed form).
     *
     * Deliberately written here rather than borrowed: this is what the operation *claims*, so it must be
     * measured by arithmetic that knows nothing about how the claim was produced.
     */
    private fun distanceTo(
        p: Vec2,
        e: ProfileElement,
    ): Double =
        when (e) {
            is ProfileElement.Seg -> {
                val a = e.segment.a
                val d = e.segment.b - a
                val t = ((p - a).dot(d) / d.dot(d)).coerceIn(0.0, 1.0)
                (p - (a + d * t)).length()
            }
            is ProfileElement.ArcE -> {
                val v = p - e.arc.center
                if (GeomMath.arcContains(e.arc, kotlin.math.atan2(v.y, v.x))) {
                    abs(v.length() - e.arc.radius)
                } else {
                    minOf((p - GeomMath.arcStart(e.arc)).length(), (p - GeomMath.arcEnd(e.arc)).length())
                }
            }
            is ProfileElement.BezierE -> {
                val t = GeomMath.bezierNearestParam(e.bezier, p, 400, 80)
                (p - GeomMath.bezierPointAt(e.bezier, t)).length()
            }
            else -> error("this suite draws no such view")
        }

    private fun distanceTo(
        p: Vec2,
        view: List<ProfileElement>,
    ): Double = view.minOf { distanceTo(p, it) }

    /** [n] points along the whole run, at equal parameter steps within every piece. */
    private fun samples(
        path: Path3,
        n: Int = 400,
    ): List<Vec3> {
        val out = ArrayList<Vec3>()
        val per = maxOf(2, n / path.elements.size)
        for (e in path.elements) {
            for (i in 0..per) {
                val t = i.toDouble() / per
                out.add(
                    when (e) {
                        is Curve3Element.Seg3 -> e.start + (e.end - e.start) * t
                        is Curve3Element.Bezier3 -> Curves3.bezierPointAt(e, t)
                        is Curve3Element.Helix3 -> e.at(t)
                    },
                )
            }
        }
        return out
    }

    /**
     * **The defining property, asserted directly**: every sample of the run projects into each parent space
     * onto the curve drawn there, to [tol].
     *
     * The one assertion this whole step is about. Everything else in the file — exactness, direction,
     * overlap, the refusals — is a statement about *when* this holds and what it costs.
     */
    private fun assertProjectsOntoBothViews(
        path: Path3,
        a: List<ProfileElement>,
        b: List<ProfileElement>,
        pa: Plane3 = plan,
        pb: Plane3 = elevation,
        tol: Double = Combine3.FIT_TOL_MM,
    ) {
        var worstA = 0.0
        var worstB = 0.0
        for (p in samples(path)) {
            worstA = maxOf(worstA, distanceTo(pa.toLocal(p), a))
            worstB = maxOf(worstB, distanceTo(pb.toLocal(p), b))
        }
        assertTrue(worstA <= tol, "the run's projection into the first view stands $worstA mm off the curve drawn there")
        assertTrue(worstB <= tol, "the run's projection into the second view stands $worstB mm off the curve drawn there")
    }

    // ---- 1. the exact cases ----

    /**
     * **Two straight views give one straight run, exactly.** A 100 mm run along +X at y = 20 in the plan, and
     * a 1-in-2 grade in the elevation: the answer is the single segment from (0, 20, 0) to (100, 20, 50), and
     * it is asserted with **no tolerance at all**, because an affine map of a segment is a segment and there
     * is nothing in this case that could be approximate.
     */
    @Test
    fun twoStraightViewsGiveOneExactSegment() {
        val path = run(listOf(seg(0.0, 20.0, 100.0, 20.0)), listOf(seg(0.0, 0.0, 100.0, 50.0)))
        val piece = assertNotNull(path.elements.single() as? Curve3Element.Seg3, "one straight piece: ${path.elements}")
        assertEquals(Vec3(0.0, 20.0, 0.0), piece.start, "the run starts where both views start")
        assertEquals(Vec3(100.0, 20.0, 50.0), piece.end, "and ends where both views end — exactly, not to a tolerance")
        assertTrue(!path.closed, "a combined run is open: a closed view could never be monotone along the common direction")
    }

    /**
     * **A cubic plan against a straight elevation is exact too**, and it is the case that shows why: the
     * straight view's point is an affine function of the coordinate along the common direction, so the whole
     * map is affine, and an affine map carries a cubic's four control points to a cubic's.
     *
     * One [Curve3Element.Bezier3] out, and its projection into each view is the drawing itself to a
     * **picometre** — which is floating-point noise, not a fit.
     */
    @Test
    fun aCubicPlanAndAStraightElevationCombineExactly() {
        val a = listOf(ProfileElement.BezierE(Bezier(Vec2(0.0, 0.0), Vec2(30.0, 60.0), Vec2(70.0, -40.0), Vec2(100.0, 20.0))))
        val b = listOf(seg(0.0, 5.0, 100.0, 45.0))
        val path = run(a, b)
        assertNotNull(path.elements.single() as? Curve3Element.Bezier3, "one cubic piece, not a fitted chain: ${path.elements}")
        assertProjectsOntoBothViews(path, a, b, tol = 1e-12)
    }

    // ---- 2. the defining property, where the answer is fitted ----

    /**
     * **A quarter bend in the plan against a straight elevation**, which is the everyday route: the plan
     * leaves its end running *square* to the common direction, so the matching by that direction is the worst
     * conditioned it can be without doubling back.
     *
     * It is the case the fit's own choice of parameter exists for — the run is halved along the view that
     * runs closest to square, so the bend costs a handful of pieces instead of unboundedly many — and the
     * defining property holds to the stated tolerance across the whole of it.
     */
    @Test
    fun aQuarterBendInThePlanAgainstAStraightElevation() {
        val a = listOf(ProfileElement.ArcE(Arc(Vec2(0.0, 20.0), 50.0, 0.0, PI / 2.0, true)))
        val b = listOf(seg(0.0, 10.0, 50.0, 40.0))
        val path = run(a, b)
        assertTrue(path.elements.size in 1..32, "a bend costs a few pieces, not hundreds: ${path.elements.size}")
        assertProjectsOntoBothViews(path, a, b)
    }

    /**
     * **Two curved views**, where nothing is exact and the fit is the whole answer: an arc in the plan and an
     * arc in the elevation, matched by the common direction.
     */
    @Test
    fun twoCurvedViewsAreFittedWithinTheStatedTolerance() {
        val a = listOf(ProfileElement.ArcE(Arc(Vec2(50.0, 20.0), 80.0, 0.05 * PI, 0.95 * PI, true)))
        val b = listOf(ProfileElement.ArcE(Arc(Vec2(40.0, 0.0), 100.0, 0.1 * PI, 0.9 * PI, true)))
        val path = run(a, b)
        assertProjectsOntoBothViews(path, a, b)
    }

    /**
     * The same claim in a space that is **not** an elevation: a second view drawn on a plane tilted 35° out of
     * the plan about a line that is not an axis. Nothing about the construction is special to a plan and an
     * elevation — those are the everyday reading, and the arithmetic only ever asks for the direction the two
     * spaces have in common.
     */
    @Test
    fun theSecondSpaceNeedNotBeAnElevation() {
        val t = 35.0 * PI / 180.0
        val axis = Vec3(1.0, 0.4, 0.0).normalized()
        val perp = Vec3.Z.cross(axis).normalized()
        val oblique = Plane3(Vec3(0.0, 0.0, 7.0), axis, perp * cos(t) + Vec3.Z * sin(t))
        val a = listOf(ProfileElement.BezierE(Bezier(Vec2(0.0, 0.0), Vec2(30.0, 40.0), Vec2(70.0, -20.0), Vec2(110.0, 30.0))))
        val b = listOf(ProfileElement.ArcE(Arc(Vec2(40.0, 30.0), 70.0, 1.1 * PI, 1.45 * PI, true)))
        val path = run(a, b, plan, oblique)
        assertProjectsOntoBothViews(path, a, b, plan, oblique)
    }

    // ---- 3. direction, and how much of the two views becomes a run ----

    /**
     * **The run goes the way the first view goes.** A run has a direction — a sweep's frame starts at its
     * beginning and a station's distance is measured from it — so the reading has to be predictable, and the
     * one a user can predict is "the way the curve you picked first is drawn".
     */
    @Test
    fun theRunGoesTheWayTheFirstViewGoes() {
        val forward = run(listOf(seg(0.0, 20.0, 100.0, 20.0)), listOf(seg(0.0, 0.0, 100.0, 50.0)))
        val backward = run(listOf(seg(100.0, 20.0, 0.0, 20.0)), listOf(seg(0.0, 0.0, 100.0, 50.0)))
        assertEquals(Vec3(0.0, 20.0, 0.0), assertNotNull(forward.start))
        assertEquals(Vec3(100.0, 20.0, 50.0), assertNotNull(backward.start), "drawn the other way, the run starts at the other end")
        assertEquals(Vec3(0.0, 20.0, 0.0), assertNotNull(backward.end))
    }

    /**
     * **Only the stretch both views cover becomes a run**, and the rest of either drawing is simply not part
     * of it: the plan runs from 0 to 100 along the common direction, the elevation from 50 to 200, and the
     * run is the 50 mm they share.
     */
    @Test
    fun onlyTheStretchBothViewsCoverBecomesARun() {
        val path = run(listOf(seg(0.0, 20.0, 100.0, 20.0)), listOf(seg(50.0, 25.0, 200.0, 100.0)))
        assertClose(assertNotNull(path.start).x, 50.0, 1e-12, "the run starts where the second view starts")
        assertClose(assertNotNull(path.end).x, 100.0, 1e-12, "and stops where the first one stops")
        assertClose(assertNotNull(path.start).z, 25.0, 1e-12, "at the height the elevation states there")
        assertClose(assertNotNull(path.end).z, 50.0, 1e-12)
    }

    /** The same two views always give the same run, bit for bit — a stored model is a pure function (OP-21). */
    @Test
    fun theSameViewsGiveTheSameRunBitForBit() {
        val a = listOf(ProfileElement.ArcE(Arc(Vec2(50.0, 20.0), 80.0, 0.05 * PI, 0.95 * PI, true)))
        val b = listOf(ProfileElement.ArcE(Arc(Vec2(40.0, 0.0), 100.0, 0.1 * PI, 0.9 * PI, true)))
        assertEquals(run(a, b), run(a, b), "the same inputs, the same pieces, the same numbers")
    }

    // ---- 4. the refusals, each by name ----

    /**
     * **Parallel spaces**: there is no common direction, so there is nothing to match by and nothing to
     * combine. Two curves drawn in the *same* space are the same refusal, which is the point — it is a
     * statement about the spaces, not about the drawings.
     */
    @Test
    fun parallelSpacesAreRefusedByName() {
        val why = refusal(listOf(seg(0.0, 0.0, 100.0, 20.0)), listOf(seg(0.0, 0.0, 100.0, 50.0)), plan, plan)
        assertTrue(why.contains("parallel"), "the refusal names it: $why")
        assertTrue(why.contains("common direction"), "and says what is missing: $why")
        val above = Plane3(Vec3(0.0, 0.0, 60.0), Vec3.X, Vec3.Y)
        assertTrue(refusal(listOf(seg(0.0, 0.0, 100.0, 20.0)), listOf(seg(0.0, 0.0, 100.0, 50.0)), plan, above).contains("parallel"))
    }

    /** **Ranges that do not overlap**: the two drawings do not describe the same run, and both ranges are said. */
    @Test
    fun rangesThatDoNotOverlapAreRefusedByNameWithBothRanges() {
        val why = refusal(listOf(seg(0.0, 20.0, 100.0, 20.0)), listOf(seg(200.0, 0.0, 300.0, 50.0)))
        assertTrue(why.contains("do not describe the same run"), "the refusal names it: $why")
        assertTrue(why.contains("0 to 100"), "and states the first view's range: $why")
        assertTrue(why.contains("200 to 300"), "and the second's: $why")
    }

    /**
     * **A view that doubles back** along the common direction: a place on the other view would answer to two
     * places on it, so the two drawings no longer describe one run.
     *
     * Refused **by name** rather than resolved by a persisted sign (OP-1's other answer), and the refusal
     * names the cure — break the view where it turns. The reason is in the count: a curve doubling back once
     * has one, two or three partners depending on where along the common direction you ask, so there is no
     * ordered solution set of fixed size for a sign to index.
     */
    @Test
    fun aViewThatDoublesBackIsRefusedByName() {
        val doublesBack = listOf(ProfileElement.ArcE(Arc(Vec2(50.0, 0.0), 50.0, -PI / 2.0, PI / 2.0, true)))
        val why = refusal(doublesBack, listOf(seg(0.0, 0.0, 120.0, 50.0)))
        assertTrue(why.contains("first view doubles back"), "the refusal names the view: $why")
        assertTrue(why.contains("100 mm"), "and where it turns: $why")
        assertTrue(why.contains("break the view"), "and the cure: $why")
        // and the same drawing as the *second* view is the same refusal, named as the second
        assertTrue(refusal(listOf(seg(0.0, 20.0, 120.0, 20.0)), doublesBack).contains("second view doubles back"))
    }

    /**
     * **A view square to the common direction**: every point of it stands at one place along that direction —
     * a riser drawn in an elevation — so there is nothing for the other view to be matched against.
     */
    @Test
    fun aViewSquareToTheCommonDirectionIsRefusedByName() {
        val why = refusal(listOf(seg(0.0, 20.0, 100.0, 20.0)), listOf(seg(50.0, 0.0, 50.0, 80.0)))
        assertTrue(why.contains("second view runs square"), "the refusal names it: $why")
    }

    /**
     * **A cusp**: both views turn to run square to the common direction at the *same* place, so the run in
     * space genuinely comes to a point there and no chain of cubics passes through it. Refused by name, with
     * the tolerance it could not be stated to.
     *
     * This is the one thing the fit's own limit reports, and it reports a fact about the geometry rather than
     * an internal count: everywhere else the halving reaches [Combine3.FIT_TOL_MM] in a handful of steps.
     */
    @Test
    fun aCuspWhereBothViewsTurnSquareIsRefusedByName() {
        val a = listOf(ProfileElement.ArcE(Arc(Vec2(0.0, 20.0), 50.0, 0.0, PI / 2.0, true)))
        val b = listOf(ProfileElement.ArcE(Arc(Vec2(0.0, 0.0), 50.0, 0.0, PI / 2.0, true)))
        val why = refusal(a, b)
        assertTrue(why.contains("cannot be matched into one run"), "the refusal names it: $why")
        assertTrue(why.contains("turn to run square"), "and says what makes it one: $why")
    }

    /**
     * The refusal is a property of the **values**, so it heals: the same two drawings, one of them moved back
     * into range, combine.
     */
    @Test
    fun aRefusedPairCombinesAsSoonAsTheDrawingMoves() {
        val plan1 = listOf(seg(0.0, 20.0, 100.0, 20.0))
        assertTrue(refusal(plan1, listOf(seg(200.0, 0.0, 300.0, 50.0))).contains("do not describe the same run"))
        val healed = run(plan1, listOf(seg(0.0, 0.0, 300.0, 150.0)))
        assertClose(assertNotNull(healed.end).x, 100.0, 1e-12, "moved back over the plan, the two views make a run")
    }

    /**
     * A view whose curve is **closed** — a circle, an ellipse — always doubles back, so it is refused by the
     * monotonicity rule rather than by a case of its own. Named here so it is not looked for elsewhere.
     */
    @Test
    fun aClosedViewIsRefusedByTheSameRule() {
        val circle = listOf(ProfileElement.CircleE(constructit.geom.Circle(Vec2(50.0, 20.0), 40.0)))
        assertTrue(refusal(circle, listOf(seg(0.0, 0.0, 100.0, 50.0))).contains("first view doubles back"))
    }

    /**
     * The turning point is found from the **closed form** of each piece kind, not by sampling — so a reversal
     * narrow enough to fall between two samples is still refused. A cubic that runs 100 mm forward along the
     * common direction and backs up by a tenth of a millimetre in the middle of it is exactly that curve.
     */
    @Test
    fun aNarrowReversalIsStillFound() {
        val wiggle =
            listOf(
                ProfileElement.BezierE(
                    Bezier(Vec2(0.0, 0.0), Vec2(60.0, 30.0), Vec2(-10.0, 30.0), Vec2(50.0, 0.0)),
                ),
            )
        val why = refusal(wiggle, listOf(seg(-20.0, 0.0, 120.0, 70.0)))
        assertTrue(why.contains("doubles back"), "a reversal inside one piece is still a reversal: $why")
    }

    // ---- 5. what the stated error is a promise about ----

    /**
     * The stated error is a promise about **the projections**, and that is what makes it worth stating: an
     * orthogonal projection never increases a distance, so a run within [Combine3.FIT_TOL_MM] of the exact
     * combined curve projects within the same [Combine3.FIT_TOL_MM] of each view it was drawn from.
     *
     * Asserted here at the number itself rather than at a looser one, on the fitted case, so the constant in
     * the source is the constant the drawing gets.
     */
    @Test
    fun theStatedErrorIsWhatTheProjectionIsWorth() {
        assertClose(Combine3.FIT_TOL_MM, 1e-4, 0.0, "the stated fit tolerance, in millimetres")
        val a = listOf(ProfileElement.ArcE(Arc(Vec2(20.0, 30.0), 90.0, 0.08 * PI, 0.92 * PI, true)))
        val b = listOf(ProfileElement.ArcE(Arc(Vec2(0.0, 10.0), 110.0, 0.12 * PI, 0.88 * PI, true)))
        val path = run(a, b)
        var worst = 0.0
        for (p in samples(path, 2000)) {
            worst = maxOf(worst, distanceTo(plan.toLocal(p), a), distanceTo(elevation.toLocal(p), b))
        }
        assertTrue(worst <= Combine3.FIT_TOL_MM, "the worst projection error over 2000 samples is $worst mm")
    }

    /** A sanity check on the fixtures: the elevation plane really is the XZ plane and the common direction +X. */
    @Test
    fun theTwoFixturesMeetAlongTheXAxis() {
        assertEquals(Vec3.Z, plan.normal, "the plan is seen from +Z")
        assertEquals(Vec3(0.0, -1.0, 0.0), elevation.normal, "the elevation from −Y")
        val d = plan.normal.cross(elevation.normal).normalized()
        assertClose(abs(d.x), 1.0, 1e-15, "so the two spaces meet along the x axis")
    }
}
