package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.ScalarValue
import constructit.core.SourceNode
import constructit.dsl.Construction
import constructit.dsl.Point3Ref
import constructit.dsl.Sphere3Ref
import constructit.dsl.isValid
import constructit.dsl.path3
import constructit.dsl.point3
import constructit.dsl.point3Set
import constructit.dsl.resultOf
import constructit.dsl.sphere3
import constructit.geom.Curve3Element
import constructit.geom.Curves3
import constructit.geom.Plane3
import constructit.geom.Sphere3
import constructit.geom.SphereMeet
import constructit.geom.Spheres3
import constructit.geom.Trilateration
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The sphere as a locus** (OP-28) — the geometry half: the composition table, the sign convention, and
 * every way of not meeting.
 *
 * What this file is about is the concept the plane has had since OP-1 and space had never had: **distance,
 * carried by a locus a construction intersects**. So every assertion here is analytic — a circle's centre and
 * radius read off the algebra, a trilateration point's three distances to the last bits of a double — rather
 * than a comparison against a sampled picture. The gesture half, the branch persistence and the composition
 * with the rest of the drawing are [SphereLocusToolTest]'s.
 *
 * The fixture that recurs: two loci **50 apart with radii 40 and 30** meet in a circle whose numbers are
 * whole (`x = 40`, `h = 24`), which makes the exactness claim readable rather than merely asserted.
 */
class SphereLocusTest {
    private fun assertVec3(
        actual: Vec3,
        expected: Vec3,
        tol: Double = 1e-9,
        msg: String = "",
    ) {
        assertClose(actual.x, expected.x, tol, "$msg (x)")
        assertClose(actual.y, expected.y, tol, "$msg (y)")
        assertClose(actual.z, expected.z, tol, "$msg (z)")
    }

    private fun reasonOf(
        ev: Evaluator,
        ref: constructit.dsl.Ref<*>,
    ): String? = (ev.resultOf(ref) as? EvalResult.Invalid)?.reason

    /** A locus about a stated place, built the way the drawing builds one: a point node and a radius node. */
    private fun locus(
        cx: Construction,
        at: Vec3,
        r: Double,
    ): Sphere3Ref = cx.sphere(pointAt(cx, at), cx.parameter("r", r.mm))

    /** A point in space at a stated place — a height point over the plan, which is how OP-25 makes one. */
    private fun pointAt(
        cx: Construction,
        at: Vec3,
    ): Point3Ref =
        cx.heightPoint(
            cx.plane(Vec3.ZERO, Vec3.X, Vec3.Y),
            cx.freePoint("b", at.x.mm, at.y.mm),
            cx.parameter("h", at.z.mm),
        )

    /** A straight run through the stated places — the polyline every `PATH3` consumer already understands. */
    private fun run(
        cx: Construction,
        vararg through: Vec3,
    ) = cx.pathThrough(through.map { pointAt(cx, it) })

    /** Retype a parameter, which is the one mutation point a live edit has (OP-5). */
    private fun retype(
        ref: constructit.dsl.ScalarRef,
        q: constructit.units.Quantity,
    ) {
        (ref.node as SourceNode).value = ScalarValue(q)
    }

    // ---- 1. the locus itself ----

    /** **A locus is its centre and its radius, and nothing else** — and a non-positive radius refuses by name. */
    @Test
    fun aLocusIsACentreAndARadiusAndRefusesANonPositiveOne() {
        val cx = Construction()
        val ev = Evaluator()
        val s = locus(cx, Vec3(10.0, 20.0, 30.0), 40.0)
        assertVec3(ev.sphere3(s).center, Vec3(10.0, 20.0, 30.0), msg = "the locus stands where its centre does")
        assertClose(ev.sphere3(s).radius, 40.0, 1e-12, "and carries the radius it was given")

        val bad = cx.sphere(pointAt(cx, Vec3.ZERO), cx.parameter("r0", 0.0.mm))
        assertTrue(!Evaluator().isValid(bad), "a radius of zero is not a locus")
        val why = assertNotNull(reasonOf(Evaluator(), bad), "and it says why")
        assertTrue(why.contains("greater than zero"), "in words a reader can act on: $why")
    }

    /**
     * **A locus stated by a point on it** is the same locus, by construction — the *(centre, surface point)*
     * spelling, and the reason it exists: the distance is an input of the drawing rather than a number.
     */
    @Test
    fun aLocusStatedByAPointOnItTakesItsDistanceFromTheDrawing() {
        val cx = Construction()
        val plane = cx.plane(Vec3.ZERO, Vec3.X, Vec3.Y)
        val c = cx.heightPoint(plane, cx.freePoint("c", 0.0.mm, 0.0.mm), cx.parameter("h", 0.0.mm))
        val surface = cx.heightPoint(plane, cx.freePoint("s", 30.0.mm, 40.0.mm), cx.parameter("h2", 0.0.mm))
        val s = cx.sphereThrough(c, surface)
        assertClose(Evaluator().sphere3(s).radius, 50.0, 1e-12, "the radius is the distance between the two points")

        val degenerate = cx.sphereThrough(c, c)
        assertTrue(!Evaluator().isValid(degenerate), "a locus through its own centre is no locus")
        assertTrue(
            assertNotNull(reasonOf(Evaluator(), degenerate)).contains("same place"),
            "and it says so rather than producing a radius of zero",
        )
        assertNotNull(surface, "the surface point is a node of the drawing, shared like any other")
    }

    // ---- 2. sphere ∩ sphere: a circle in space ----

    /**
     * **Two loci meet in an exact circle**, and every number of it is read off the algebra rather than
     * measured: 50 apart with radii 40 and 30 gives a circle of radius 24 centred 32 along the centre line.
     */
    @Test
    fun twoLociMeetInAnExactCircle() {
        val cx = Construction()
        val a = locus(cx, Vec3.ZERO, 40.0)
        val b = locus(cx, Vec3(50.0, 0.0, 0.0), 30.0)
        val path = Evaluator().path3(cx.sphereCircle(a, b))
        assertTrue(path.closed, "a circle is a closed run")
        assertEquals(1, path.elements.size, "and one exact piece, not a chain of fitted cubics")
        val arc = assertNotNull(path.elements[0] as? Curve3Element.Arc3, "the piece is an exact circular arc")
        assertClose(kotlin.math.abs(arc.sweepAngle), 2.0 * PI, 1e-12, "sweeping a whole turn")
        // x = (d² + r1² − r2²) / 2d = (2500 + 1600 − 900) / 100 = 32; h = sqrt(1600 − 1024) = 24
        assertVec3(arc.center, Vec3(32.0, 0.0, 0.0), 1e-9, "the circle stands where the algebra puts it")
        assertClose(arc.radius, 24.0, 1e-9, "with the radius the algebra gives")
        assertVec3(arc.normal.normalized(), Vec3.X, 1e-9, "and its plane is square to the centre line")
    }

    /** **Every point of that circle is at both distances** — which is what a locus intersection means. */
    @Test
    fun everyPointOfTheCircleIsAtBothDistances() {
        val cx = Construction()
        val c1 = Vec3(10.0, -20.0, 5.0)
        val c2 = Vec3(40.0, 10.0, 35.0)
        val a = locus(cx, c1, 40.0)
        val b = locus(cx, c2, 45.0)
        val path = Evaluator().path3(cx.sphereCircle(a, b))
        val points = Curves3.polyline(path)
        assertTrue(points.size >= 16, "the circle is sampled at all (${points.size} points)")
        for (p in points) {
            assertClose((p - c1).length(), 40.0, 1e-9, "a point of the circle is 40 from the first centre")
            assertClose((p - c2).length(), 45.0, 1e-9, "...and 45 from the second")
        }
    }

    /**
     * **A touch is not a circle**, and it says so — the one refusal in this table that is about a *kind*
     * rather than about a distance: two loci that touch meet at a point, and handing back a circle of radius
     * zero would be handing back a different thing under the same name.
     *
     * ...and it **heals** the moment a radius overlaps again (OP-3), with nothing rebuilt.
     */
    @Test
    fun twoLociThatMerelyTouchRefuseByNameAndHeal() {
        val cx = Construction()
        val r2 = cx.parameter("r2", 30.0.mm)
        val a = locus(cx, Vec3.ZERO, 40.0)
        val b = cx.sphere(pointAt(cx, Vec3(70.0, 0.0, 0.0)), r2)
        val circle = cx.sphereCircle(a, b)
        assertTrue(!Evaluator().isValid(circle), "40 + 30 = 70 exactly: they touch, and a point is not a circle")
        val why = assertNotNull(reasonOf(Evaluator(), circle), "and it says why")
        assertTrue(why.contains("touch"), "naming the tangency: $why")

        retype(r2, 35.0.mm)
        assertTrue(Evaluator().isValid(circle), "and it heals the moment they overlap")
        assertClose(Evaluator().path3(circle).elements.size.toDouble(), 1.0, 0.0, "still one exact piece")
    }

    /** The three other ways of not meeting, each **named** so a reader knows which one they are in. */
    @Test
    fun theThreeOtherWaysOfNotMeetingAreEachNamed() {
        val cx = Construction()
        val apart = cx.sphereCircle(locus(cx, Vec3.ZERO, 10.0), locus(cx, Vec3(100.0, 0.0, 0.0), 10.0))
        assertTrue(assertNotNull(reasonOf(Evaluator(), apart)).contains("do not meet"), "too far apart says so")
        val nested = cx.sphereCircle(locus(cx, Vec3.ZERO, 100.0), locus(cx, Vec3(5.0, 0.0, 0.0), 10.0))
        assertTrue(assertNotNull(reasonOf(Evaluator(), nested)).contains("inside"), "one inside the other says so")
        val same = cx.sphereCircle(locus(cx, Vec3.ZERO, 10.0), locus(cx, Vec3.ZERO, 20.0))
        assertTrue(assertNotNull(reasonOf(Evaluator(), same)).contains("centre"), "concentric says so")
    }

    // ---- 3. three spheres: the trilateration pair, and its sign ----

    /**
     * **The point at three stated distances**, both branches, each at all three distances to 1e-9 — which is
     * the whole promise of the composition.
     */
    @Test
    fun threeLociMeetAtAPairOfPointsAtAllThreeDistances() {
        val cx = Construction()
        val c1 = Vec3.ZERO
        val c2 = Vec3(70.0, 0.0, 0.0)
        val c3 = Vec3(20.0, 60.0, 0.0)
        val set = cx.trilaterate(locus(cx, c1, 40.0), locus(cx, c2, 55.0), locus(cx, c3, 45.0))
        val points = Evaluator().point3Set(set).points
        assertEquals(2, points.size, "three loci in general position meet at a pair of points")
        for (p in points) {
            assertClose((p - c1).length(), 40.0, 1e-9, "at 40 from the first centre")
            assertClose((p - c2).length(), 55.0, 1e-9, "at 55 from the second")
            assertClose((p - c3).length(), 45.0, 1e-9, "at 45 from the third")
        }
        assertVec3(points[0], Vec3(points[1].x, points[1].y, -points[1].z), 1e-9, "mirror images in the centres' plane")
    }

    /**
     * **The sign convention, stated and asserted**: branch `+1` is the solution on the side the right-hand
     * normal `(C₂ − C₁) × (C₃ − C₁)` points to, and `−1` the other.
     *
     * This is the load-bearing assertion of the whole package: it is what makes a stored sign mean the same
     * thing for as long as the drawing exists.
     */
    @Test
    fun thePlusBranchIsTheSideTheRightHandNormalPointsTo() {
        val cx = Construction()
        val c1 = Vec3.ZERO
        val c2 = Vec3(70.0, 0.0, 0.0)
        val c3 = Vec3(20.0, 60.0, 0.0)
        val set = cx.trilaterate(locus(cx, c1, 40.0), locus(cx, c2, 55.0), locus(cx, c3, 45.0))
        val n = (c2 - c1).cross(c3 - c1)
        val plus = Evaluator().point3(cx.selectPoint3(set, 1))
        val minus = Evaluator().point3(cx.selectPoint3(set, -1))
        assertTrue(n.dot(plus - c1) > 0.0, "branch +1 stands on the side the right-hand normal points to")
        assertTrue(n.dot(minus - c1) < 0.0, "and branch −1 on the other")
    }

    /**
     * **The convention is a property of the operands' order**, which is structural: state the same three loci
     * in the other order and the normal turns over, so the branches exchange — and that is not a defect, it
     * is what makes the rule readable at all.
     */
    @Test
    fun statingTheLociInTheOtherOrderExchangesTheBranches() {
        val cx = Construction()
        val a = locus(cx, Vec3.ZERO, 40.0)
        val b = locus(cx, Vec3(70.0, 0.0, 0.0), 55.0)
        val c = locus(cx, Vec3(20.0, 60.0, 0.0), 45.0)
        val one = Evaluator().point3(cx.selectPoint3(cx.trilaterate(a, b, c), 1))
        val other = Evaluator().point3(cx.selectPoint3(cx.trilaterate(a, c, b), 1))
        assertVec3(other, Vec3(one.x, one.y, -one.z), 1e-9, "the same branch of the reversed statement is the other point")
    }

    /**
     * **The convention turns with the construction**: move the whole thing rigidly and branch `+1` is the
     * rigidly moved branch `+1` — the property OP-1's own 2D rules were chosen for.
     */
    @Test
    fun theBranchSurvivesARigidMotionOfTheWholeDrawing() {
        val shift = Vec3(120.0, -35.0, 60.0)
        val here = Construction()
        val plusHere =
            Evaluator().point3(
                here.selectPoint3(
                    here.trilaterate(
                        locus(here, Vec3.ZERO, 40.0),
                        locus(here, Vec3(70.0, 0.0, 0.0), 55.0),
                        locus(here, Vec3(20.0, 60.0, 0.0), 45.0),
                    ),
                    1,
                ),
            )
        val there = Construction()
        val plusThere =
            Evaluator().point3(
                there.selectPoint3(
                    there.trilaterate(
                        locus(there, shift, 40.0),
                        locus(there, Vec3(70.0, 0.0, 0.0) + shift, 55.0),
                        locus(there, Vec3(20.0, 60.0, 0.0) + shift, 45.0),
                    ),
                    1,
                ),
            )
        assertVec3(plusThere, plusHere + shift, 1e-9, "the translated drawing rides the translated branch")
    }

    /** **A tangency collapses the pair**, and both signs then answer the same point — OP-1's own rule. */
    @Test
    fun aTangentTrilaterationAnswersOnePointForBothSigns() {
        val cx = Construction()
        // three loci of radius 50 about three points 50 from the origin's own plane point: the pair collapses
        // onto the plane of the centres exactly when the point they meet at lies in it
        val c1 = Vec3(-30.0, 0.0, 0.0)
        val c2 = Vec3(30.0, 0.0, 0.0)
        val c3 = Vec3(0.0, 40.0, 0.0)
        val set = cx.trilaterate(locus(cx, c1, 30.0), locus(cx, c2, 30.0), locus(cx, c3, 40.0))
        val points = Evaluator().point3Set(set).points
        assertEquals(1, points.size, "the two solutions have coincided at the origin")
        assertVec3(Evaluator().point3(cx.selectPoint3(set, 1)), points[0], 1e-9, "branch +1 is it")
        assertVec3(Evaluator().point3(cx.selectPoint3(set, -1)), points[0], 1e-9, "...and so is branch −1")
    }

    /** **Collinear centres have no pair to choose between**, and the refusal says exactly that. */
    @Test
    fun collinearCentresRefuseByNameBecauseThereIsNoPlaneToTakeASideOf() {
        val cx = Construction()
        val set =
            cx.trilaterate(
                locus(cx, Vec3.ZERO, 40.0),
                locus(cx, Vec3(50.0, 0.0, 0.0), 40.0),
                locus(cx, Vec3(100.0, 0.0, 0.0), 40.0),
            )
        assertEquals(emptyList(), Evaluator().point3Set(set).points, "three centres on a line meet in a circle, not a pair")
        val picked = cx.selectPoint3(set, 1, "no point is at all three of those distances")
        assertTrue(!Evaluator().isValid(picked), "so there is no point to select")
        assertTrue(
            assertNotNull(reasonOf(Evaluator(), picked)).contains("three"),
            "and the reason is the caller's own sentence",
        )
    }

    /** **Loci that do not overlap have no point at all**, and that is the empty set, not a wrong answer. */
    @Test
    fun lociThatDoNotOverlapYieldNoPoint() {
        val cx = Construction()
        val set =
            cx.trilaterate(
                locus(cx, Vec3.ZERO, 10.0),
                locus(cx, Vec3(70.0, 0.0, 0.0), 10.0),
                locus(cx, Vec3(20.0, 60.0, 0.0), 10.0),
            )
        assertEquals(emptyList(), Evaluator().point3Set(set).points, "nothing is at all three distances")
        assertTrue(!Evaluator().isValid(cx.selectPoint3(set, 1)), "so the selection is invalid, with a reason")
    }

    // ---- 4. sphere ∩ curve: the points at a stated distance along a run ----

    /**
     * **A run crosses a locus where it is at that distance**, in order along the run, and the crossing is as
     * exact as the curve's own formula.
     *
     * A straight run from the centre outwards: the crossing is at exactly the radius, which is a number that
     * can be read rather than trusted.
     */
    @Test
    fun aRunCrossesALocusExactlyWhereItIsAtThatDistance() {
        val cx = Construction()
        val centre = Vec3.ZERO
        val s = locus(cx, centre, 40.0)
        val route = run(cx, centre, Vec3(100.0, 0.0, 0.0))
        val set = cx.sphereMeetsRun(s, route)
        val points = Evaluator().point3Set(set).points
        assertEquals(1, points.size, "the run leaves the locus once")
        assertVec3(points[0], Vec3(40.0, 0.0, 0.0), 1e-9, "exactly at the radius")
    }

    /**
     * **Several crossings come back ordered along the run**, which is the arc-length order the sweep's own
     * crossings have used since OP-26 — and each is at the stated distance.
     */
    @Test
    fun severalCrossingsComeBackInTheOrderTheRunMeetsThem() {
        val cx = Construction()
        val centre = Vec3(50.0, 0.0, 0.0)
        val s = locus(cx, centre, 20.0)
        // a run that passes straight through the locus: in at 30, out at 70
        val route = run(cx, Vec3.ZERO, Vec3(100.0, 0.0, 0.0))
        val points = Evaluator().point3Set(cx.sphereMeetsRun(s, route)).points
        assertEquals(2, points.size, "in and out")
        assertVec3(points[0], Vec3(30.0, 0.0, 0.0), 1e-9, "the near crossing comes first")
        assertVec3(points[1], Vec3(70.0, 0.0, 0.0), 1e-9, "...and the far one second")
        for ((i, p) in points.withIndex()) {
            assertClose((p - centre).length(), 20.0, 1e-9, "crossing ${i + 1} stands at the stated distance")
        }
    }

    /**
     * **A run that grazes the locus and turns back crosses it nowhere** — the touch rule, inherited from the
     * very walk a plane crossing uses (`Pierce3.crossingsOf`) rather than restated here.
     */
    @Test
    fun aRunThatGrazesTheLocusCrossesItNowhere() {
        val cx = Construction()
        val s = locus(cx, Vec3.ZERO, 40.0)
        // a run that comes down to the sphere at exactly 40 and turns back — it changes no side
        val route = run(cx, Vec3(-60.0, 40.0, 0.0), Vec3(0.0, 40.0, 0.0), Vec3(60.0, 40.0, 0.0))
        assertEquals(emptyList(), Evaluator().point3Set(cx.sphereMeetsRun(s, route)).points, "a touch is not a crossing")
    }

    /** **A run out of reach crosses nothing**, and the selection says so and heals when the radius grows. */
    @Test
    fun aRunOutOfReachSaysSoAndHeals() {
        val cx = Construction()
        val r = cx.parameter("r", 10.0.mm)
        val s = cx.sphere(pointAt(cx, Vec3.ZERO), r)
        val route = run(cx, Vec3(40.0, -50.0, 0.0), Vec3(40.0, 50.0, 0.0))
        val picked = cx.selectPoint3At(cx.sphereMeetsRun(s, route), 0)
        assertTrue(!Evaluator().isValid(picked), "a locus of 10 does not reach a run 40 away")
        assertTrue(assertNotNull(reasonOf(Evaluator(), picked)).contains("does not reach"), "and it says so")
        retype(r, 50.0.mm)
        assertTrue(Evaluator().isValid(picked), "and it heals when the locus grows to reach it")
        assertClose((Evaluator().point3(picked) - Vec3.ZERO).length(), 50.0, 1e-9, "at the new distance")
    }

    /** **An index the run no longer has is invalid with a reason**, exactly as a vanished curve branch is. */
    @Test
    fun anIndexTheRunNoLongerHasIsInvalidWithAReason() {
        val cx = Construction()
        val r = cx.parameter("r", 5.0.mm)
        val s = cx.sphere(pointAt(cx, Vec3(50.0, 0.0, 0.0)), r)
        // the run *ends* inside the big locus, so growing the radius drops the second crossing rather than both
        val route = run(cx, Vec3.ZERO, Vec3(60.0, 0.0, 0.0))
        val second = cx.selectPoint3At(cx.sphereMeetsRun(s, route), 1)
        assertTrue(Evaluator().isValid(second), "a small locus is entered and left: two crossings")
        retype(r, 20.0.mm)
        assertTrue(!Evaluator().isValid(second), "grow it past the run's end and the second crossing is gone")
        assertTrue(assertNotNull(reasonOf(Evaluator(), second)).contains("crossing 2"), "named by its own index")
        retype(r, 5.0.mm)
        assertTrue(Evaluator().isValid(second), "and it comes back — the choice is recorded, not re-scored")
    }

    // ---- 5. the kernel's own laws, asserted where they are stated ----

    /** **The circle's frame is deterministic**: the same two loci always give the identical numbers. */
    @Test
    fun theCirclesFrameIsDeterministic() {
        val a = Sphere3(Vec3(3.0, -7.0, 11.0), 40.0)
        val b = Sphere3(Vec3(43.0, -7.0, 11.0), 30.0)
        val one = assertNotNull(Spheres3.meet(a, b) as? SphereMeet.Circle).circle
        val two = assertNotNull(Spheres3.meet(a, b) as? SphereMeet.Circle).circle
        assertEquals(one, two, "identical inputs give identical numbers, which is what a byte-equal save rests on")
    }

    /** **A locus meets a plane in a circle too** — the shared final step, offered as itself (kernel-only in this cut). */
    @Test
    fun aLocusMeetsAPlaneInACircle() {
        val s = Sphere3(Vec3(0.0, 0.0, 30.0), 50.0)
        val plan = Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)
        val circle = assertNotNull(Spheres3.meetPlane(s, plan) as? SphereMeet.Circle).circle
        assertVec3(circle.center, Vec3.ZERO, 1e-9, "centred where the centre drops onto the plane")
        assertClose(circle.radius, 40.0, 1e-9, "with the radius Pythagoras gives")
        val far = Spheres3.meetPlane(Sphere3(Vec3(0.0, 0.0, 80.0), 50.0), plan)
        assertTrue(far is SphereMeet.Apart, "a plane the locus does not reach meets it nowhere")
    }

    /** **The trilateration kernel names its degeneracies** rather than returning a plausible-looking point. */
    @Test
    fun theTrilaterationKernelNamesItsDegeneracies() {
        val on = Sphere3(Vec3.ZERO, 40.0)
        assertTrue(
            Spheres3.trilaterate(on, Sphere3(Vec3(50.0, 0.0, 0.0), 40.0), Sphere3(Vec3(100.0, 0.0, 0.0), 40.0))
                is Trilateration.Collinear,
            "centres on a line",
        )
        assertTrue(
            Spheres3.trilaterate(on, Sphere3(Vec3(200.0, 0.0, 0.0), 40.0), Sphere3(Vec3(0.0, 200.0, 0.0), 40.0))
                is Trilateration.None,
            "loci that never overlap",
        )
    }
}
