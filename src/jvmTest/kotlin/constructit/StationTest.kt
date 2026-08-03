package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PlaneValue
import constructit.dsl.Construction
import constructit.dsl.PlaneRef
import constructit.dsl.Point3Ref
import constructit.dsl.PointRef
import constructit.geom.Curve3Element
import constructit.geom.Curves3
import constructit.geom.Frames3
import constructit.geom.GeomMath
import constructit.geom.Handedness
import constructit.geom.Path3
import constructit.geom.Plane3
import constructit.geom.Stations3
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The station** (OP-26, step 4) at the level of the geometry: *one stated position along a path together
 * with the plane the path pierces there*.
 *
 * Two claims are asserted here, and they are different in kind. That the station **is where it says it is** —
 * on a straight run, across a corner of a polyline (at exactly the piece boundary, which is where the
 * half-open rule either holds or does not), on a Bézier and on a helix, with the origin at the stated arc
 * length and the normal along the tangent. And that the **arc length is honest**: the position is checked
 * against a length computed independently, by summing a very fine polyline, which is the only way to test a
 * parameterization rather than to test it against itself.
 *
 * The gesture, the space it makes and everything drawn in it are [StationToolTest]'s.
 */
class StationTest {
    private val up = Vec3.Z

    private fun stationOrFail(
        path: Path3,
        s: Double,
        up: Vec3 = this.up,
    ) = assertNotNull(Stations3.at(path, up, s).first, "the station was refused: ${Stations3.at(path, up, s).second}")

    private fun refusal(
        path: Path3,
        s: Double,
    ): String {
        val (station, why) = Stations3.at(path, up, s)
        assertNull(station, "this station was expected to be refused")
        return assertNotNull(why, "a refusal says why")
    }

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

    /**
     * The length of a curve computed by **nothing but positions**: a polyline of [n] chords, summed.
     *
     * The independent measurement every arc-length claim below is checked against. It knows no formula and no
     * quadrature — it reads the points the curve puts in space and adds up the distances between them — which
     * is the only way an assertion about a parameterization can avoid comparing the code with itself.
     */
    private fun polylineLength(
        el: Curve3Element,
        from: Double = 0.0,
        to: Double = 1.0,
        n: Int = 200_000,
    ): Double {
        var sum = 0.0
        var prev = pointOn(el, from)
        for (i in 1..n) {
            val p = pointOn(el, from + (to - from) * i.toDouble() / n)
            sum += (p - prev).length()
            prev = p
        }
        return sum
    }

    private fun pointOn(
        el: Curve3Element,
        t: Double,
    ): Vec3 =
        when (el) {
            is Curve3Element.Seg3 -> el.start + (el.end - el.start) * t
            is Curve3Element.Bezier3 -> Curves3.bezierPointAt(el, t)
            is Curve3Element.Helix3 -> el.at(t)
        }

    // ---- the fixtures ----

    /** A straight run of 300 mm along +X, in the plan. */
    private fun straight() = Path3(Curves3.straightThrough(listOf(Vec3(0.0, 0.0, 0.0), Vec3(300.0, 0.0, 0.0))))

    /**
     * A right-angled polyline: 100 mm along +X, then 200 mm along +Y. Its one corner sits at exactly 100 mm,
     * which is the distance the half-open rule is asserted at.
     */
    private fun elbow() =
        Path3(
            Curves3.straightThrough(
                listOf(Vec3(0.0, 0.0, 0.0), Vec3(100.0, 0.0, 0.0), Vec3(100.0, 200.0, 0.0)),
            ),
        )

    /** A cubic in space, deliberately not planar — the piece whose length is an integral. */
    private fun bezier() =
        Curve3Element.Bezier3(
            Vec3(0.0, 0.0, 0.0),
            Vec3(60.0, 80.0, 20.0),
            Vec3(160.0, -40.0, 60.0),
            Vec3(220.0, 30.0, 10.0),
        )

    private fun coil(
        radius: Double = 20.0,
        pitch: Double = 30.0,
        turns: Double = 2.5,
    ) = Curve3Element.Helix3.about(Vec3.ZERO, Vec3.Z, Vec3.X, radius, pitch, turns, Handedness.RIGHT)

    // ---- 1. the station is where it says it is ----

    /** **On a straight run** the answer is arithmetic: the origin is the distance, the normal is the run. */
    @Test
    fun aStationOnAStraightRunStandsAtTheStatedDistanceFacingAlongIt() {
        val st = stationOrFail(straight(), 120.0)
        assertVec3(st.at, Vec3(120.0, 0.0, 0.0), msg = "the origin is 120 mm along")
        assertVec3(st.tangent, Vec3.X, msg = "and the normal is the direction the run goes")
        assertVec3(st.plane.normal, Vec3.X, 1e-9, "the plane's normal is the tangent, by construction")
        assertClose(st.ref.dot(st.tangent), 0.0, 1e-12, "the in-plane axes stand square to the run")
        assertClose(st.bi.dot(st.tangent), 0.0, 1e-12)
        assertClose(st.ref.length(), 1.0, 1e-12, "and they are unit")
        // and the frame is right-handed: ref × bi is the normal, not its opposite
        assertVec3(st.ref.cross(st.bi), st.tangent, 1e-9, "(ref, bi, tangent) is right-handed")
    }

    /**
     * **The half-open rule, asserted at exactly a piece boundary** — the one place it either holds or does
     * not (OP-26: *"a distance in `[pieceStart, pieceEnd)` belongs to that piece"*).
     *
     * The elbow turns at 100 mm. A station at 99.999 mm faces along the first leg; a station at **exactly**
     * 100 mm belongs to the *second* piece and faces along it, with no bisection anywhere and no second
     * tangent to choose between. That is the corner question dissolving rather than being deferred.
     */
    @Test
    fun aStationExactlyAtACornerBelongsToThePieceThatStartsThere() {
        val path = elbow()
        assertVec3(stationOrFail(path, 99.999).tangent, Vec3.X, msg = "just before the corner it runs along +X")
        val corner = stationOrFail(path, 100.0)
        assertVec3(corner.at, Vec3(100.0, 0.0, 0.0), msg = "the station stands on the corner")
        assertVec3(corner.tangent, Vec3.Y, msg = "and belongs to the piece that *starts* there — half-open")
        assertVec3(stationOrFail(path, 100.001).tangent, Vec3.Y, msg = "as does everything after it")
        // and the far end of the whole run belongs to the last piece, which is the rule's one closure
        val end = stationOrFail(path, 300.0)
        assertVec3(end.at, Vec3(100.0, 200.0, 0.0), msg = "the far end is reachable and is the run's end")
        assertVec3(end.tangent, Vec3.Y, msg = "on the last piece")
    }

    /** **On the second leg** the distance is still measured from the start of the *run*, not of the piece. */
    @Test
    fun theDistanceIsMeasuredFromTheStartOfTheWholeRun() {
        val st = stationOrFail(elbow(), 250.0)
        assertVec3(st.at, Vec3(100.0, 150.0, 0.0), msg = "150 mm up the second leg, 250 mm along the run")
    }

    /**
     * **On a Bézier** the position is the arc length inverted, and the tangent is the analytic derivative —
     * neither read off a chord.
     */
    @Test
    fun aStationOnABezierStandsAtTheStatedArcLengthWithTheCurvesOwnTangent() {
        val b = bezier()
        val path = Path3(listOf(b))
        val total = Curves3.arcLength(b)
        val st = stationOrFail(path, 0.4 * total)
        val t = Curves3.paramAtLength(b, 0.4 * total)
        assertVec3(st.at, Curves3.bezierPointAt(b, t), 1e-9, "the point is the cubic's own point at that parameter")
        assertVec3(
            st.tangent,
            Curves3.bezierTangentAt(b, t).normalized(),
            1e-9,
            "and the normal is the analytic tangent, not a chord direction",
        )
    }

    /** **On a helix** — the curve that lies in no plane, and whose length is exact. */
    @Test
    fun aStationOnAHelixStandsAtTheStatedArcLengthWithTheCurvesOwnTangent() {
        val h = coil()
        val path = Path3(listOf(h))
        val total = h.arcLength
        val st = stationOrFail(path, 0.6 * total)
        assertVec3(st.at, h.at(0.6), 1e-9, "a helix travels at constant speed, so 60 % of the length is t = 0.6")
        assertVec3(st.tangent, h.tangentAt(0.6).normalized(), 1e-9, "and the tangent is the closed form's")
        assertClose(st.ref.dot(st.tangent), 0.0, 1e-9, "the frame stays square to the run on a curve that twists")
    }

    // ---- 2. arc length is honest ----

    /**
     * **The arc length is checked against a measurement that knows no formula** — a 200 000-chord polyline,
     * summed — on a Bézier, whose length is a numeric integral, and on a helix, whose length is exact.
     *
     * The stated error is the point of this test. The cubic's integral is a composite 8-point
     * Gauss–Legendre over 16 subintervals, and it agrees with the polyline to **2e-9 mm** over a 248 mm run.
     * The polyline is the less accurate of the two — a chord always undercuts its arc — so that residual is
     * the *polyline's* truncation: at twenty million chords the two agree to 5e-12 mm, which is the honest
     * accuracy of the integral and is more than this test can see.
     *
     * The helix needs **ten times as many chords for the same bound**, and that is the same statement rather
     * than a weaker one: a coil of 2.5 turns bends about twelve times as hard as this cubic, and a polyline's
     * shortfall goes as the square of the chord's own turn. Its closed form is exact either way.
     */
    @Test
    fun theArcLengthAgreesWithAnIndependentlySummedPolyline() {
        val b = bezier()
        val fine = polylineLength(b)
        assertClose(Curves3.arcLength(b), fine, 1e-8, "the cubic's integral against 200 000 chords")

        val h = coil()
        assertClose(Curves3.arcLength(h), polylineLength(h, n = 2_000_000), 1e-8, "and the helix's closed form")
        assertClose(
            h.arcLength,
            abs(h.sweepAngle) * sqrt(h.radius * h.radius + h.b * h.b),
            1e-12,
            "which is |Δθ|·sqrt(r² + b²), exactly",
        )
    }

    /**
     * **A station's position is at the arc length it states, measured on the curve** — asserted the hard way:
     * the polyline from the curve's start to the station's own parameter is summed independently, and it is
     * the stated distance.
     *
     * This is the assertion that would fail if the distance were a *polyline* arc length at the mesh's
     * tessellation, which is the tempting shortcut: at `TESS_TOL_MM` a chord under-measures a bend by enough
     * to put a station most of a tenth of a millimetre out over a run this long.
     */
    @Test
    fun theStationStandsAtTheStatedArcLengthAlongTheCurveItself() {
        val b = bezier()
        val path = Path3(listOf(b))
        for (s in listOf(10.0, 60.0, 130.0, 240.0)) {
            val t = Curves3.paramAtLength(b, s)
            assertClose(polylineLength(b, 0.0, t), s, 1e-6, "$s mm along the cubic, measured by chords alone")
            assertVec3(stationOrFail(path, s).at, Curves3.bezierPointAt(b, t), 1e-9, "and the station stands there")
        }
        val h = coil()
        val coilPath = Path3(listOf(h))
        for (s in listOf(25.0, 90.0, 200.0)) {
            val t = Curves3.paramAtLength(h, s)
            assertClose(polylineLength(h, 0.0, t), s, 1e-6, "$s mm along the coil, measured by chords alone")
            assertVec3(stationOrFail(coilPath, s).at, h.at(t), 1e-9, "and the station stands there")
        }
    }

    /**
     * **The inversion and the integral are inverse**, to the tolerance the inversion is driven to: running
     * the length back out of the parameter gives the distance that went in.
     */
    @Test
    fun invertingTheArcLengthAndTakingItAgainIsTheIdentity() {
        val b = bezier()
        val total = Curves3.arcLength(b)
        for (i in 0..20) {
            val s = total * i / 20.0
            assertClose(Curves3.lengthTo(b, Curves3.paramAtLength(b, s)), s, 1e-9, "at $s mm")
        }
        assertEquals(0.0, Curves3.paramAtLength(b, 0.0), "the start is t = 0 exactly")
        assertEquals(1.0, Curves3.paramAtLength(b, total), "and the end is t = 1 exactly")
    }

    /** **Deterministic**: the same path and the same distance give the same station, bit for bit. */
    @Test
    fun theSameInputsGiveTheSameStationBitForBit() {
        val path = Path3(listOf(bezier()))
        val a = stationOrFail(path, 137.0)
        val b = stationOrFail(path, 137.0)
        assertEquals(a, b, "a station is a pure function of its path and its distance")
    }

    // ---- 3. the frame is the moving frame's ----

    /**
     * **The in-plane axes are the parallel-transport frame's**, which is why this step comes after the sweep:
     * along a straight run the frame is carried through unchanged, so every station on it has the *same*
     * axes — not a frame that rolls slowly along the run, which is what an up-vector convention would give.
     */
    @Test
    fun everyStationOnAStraightRunHasTheSameAxes() {
        val path = straight()
        val first = stationOrFail(path, 0.0)
        for (s in listOf(1.0, 75.0, 150.0, 299.0, 300.0)) {
            val st = stationOrFail(path, s)
            assertVec3(st.ref, first.ref, 1e-12, "the reference is carried through a straight run unchanged")
            assertVec3(st.bi, first.bi, 1e-12)
        }
        assertVec3(first.ref, Vec3.Z, 1e-12, "and it starts as the space's own normal, projected square to the run")
    }

    /**
     * **The station's frame introduces no rotation about the tangent** — parallel transport's defining
     * property, asserted directly on a frame sampled by *distance* rather than by the sweep's own chords.
     *
     * The same claim [assertTransportNeverFlips] makes about the moving frame, and it has to be made again
     * here because a station walks its own chords: between two stations the reference turns by no more than
     * the tangent does, which subsumes "it never flips" and is what a Frenet frame fails at an inflection.
     * Checked on a helix, where transport actually does something (after a full turn the tangent has come
     * back and the reference has not), and on a non-planar cubic.
     */
    @Test
    fun theStationsFrameIntroducesNoRotationAboutTheTangent() {
        for (path in listOf(Path3(listOf(coil())), Path3(listOf(bezier())))) {
            val total = Curves3.length(path)
            var prev = stationOrFail(path, 0.0)
            for (i in 1..200) {
                val st = stationOrFail(path, total * i / 200.0)
                val turnedTangent = angleBetween3(prev.tangent, st.tangent)
                val turnedRef = angleBetween3(prev.ref, st.ref)
                assertTrue(
                    turnedRef <= turnedTangent + 1e-9,
                    "the frame turned $turnedRef rad while the tangent turned $turnedTangent — " +
                        "that is a rotation about the tangent, which transport must not introduce",
                )
                assertClose(st.ref.dot(st.tangent), 0.0, 1e-9, "and it stays square to the run")
                prev = st
            }
        }
    }

    /**
     * **The station's frame is the sweep's frame, to the sampling's own chord angle** — one transport rule,
     * read at two different places along one curve.
     *
     * The bound is *derived* rather than picked, and it is the honest one. A sweep's station stands where its
     * chord tolerance put it and is named by the **polyline's** arc length; a station stands where a number
     * says and is found by the **curve's**. So the two are at slightly different points and their references
     * are square to slightly different directions — a chord's and the analytic tangent's — and the whole
     * difference is bounded by the angle one chord turns through at [GeomMath.TESS_TOL_MM], which for this
     * coil is 87 mrad. Measured: 2.7 mrad, comfortably inside it. What must *not* happen — and is what this
     * asserts — is the two rolling apart along the run, which is what two different frame conventions would do.
     */
    @Test
    fun theStationsFrameAgreesWithTheSweepsToTheChordAngle() {
        val h = coil()
        val path = Path3(listOf(h))
        val (frame, why) = Frames3.along(path, up)
        val moving = assertNotNull(frame, why)
        val chordAngle = 2.0 * kotlin.math.acos((1.0 - GeomMath.TESS_TOL_MM * h.curvature).coerceIn(-1.0, 1.0))
        assertTrue(chordAngle > 0.05, "the fixture is sampled coarsely enough for this to say something: $chordAngle")
        for (station in moving.stations.filter { it.s > 1.0 && it.s < moving.length - 1.0 }) {
            val st = stationOrFail(path, station.s)
            assertTrue(
                angleBetween3(st.ref, station.ref) < chordAngle,
                "at ${station.s} mm the station's reference and the sweep's disagree by " +
                    "${angleBetween3(st.ref, station.ref)} rad, more than one chord's own turn ($chordAngle)",
            )
        }
    }

    /**
     * **Continuity in the stated distance**, which matters because the distance is a number a user drags:
     * two stations a hundredth of a millimetre apart have frames a hundredth of a milliradian apart, right
     * across the sample points where the transport walk gains a chord.
     */
    @Test
    fun theFrameMovesContinuouslyAsTheDistanceMoves() {
        val path = Path3(listOf(bezier()))
        val total = Curves3.length(path)
        var prev = stationOrFail(path, 0.0)
        var s = 0.01
        while (s <= total) {
            val st = stationOrFail(path, s)
            assertTrue(
                angleBetween3(st.ref, prev.ref) < 1e-3,
                "the frame jumped by ${angleBetween3(st.ref, prev.ref)} rad over 0.01 mm at $s mm",
            )
            assertTrue((st.at - prev.at).length() < 0.02, "and so did the origin, at $s mm")
            prev = st
            s += 0.01
        }
    }

    // ---- 4. out of range, and the degenerate paths ----

    /** **Past the end of the run is refused by name**, and the message says the run's length and the range. */
    @Test
    fun aDistancePastTheEndOfTheRunIsRefusedAndSaysHowLongItIs() {
        val why = refusal(straight(), 400.0)
        assertTrue(why.contains("past the end"), why)
        assertTrue(why.contains("300"), "the run's length is in the message: $why")
    }

    /** **Before the start is refused too**, and it is a different sentence because it is a different mistake. */
    @Test
    fun aNegativeDistanceIsRefusedAndSaysWhereTheMeasurementStarts() {
        val why = refusal(straight(), -5.0)
        assertTrue(why.contains("measured from the start"), why)
        assertTrue(why.contains("300"), "with the range it could have been in: $why")
    }

    /** Both ends of the domain are **in** it: `0` and `L` are stations, so a run's own caps are stations. */
    @Test
    fun bothEndsOfTheRunAreThemselvesStations() {
        val path = straight()
        assertVec3(stationOrFail(path, 0.0).at, Vec3.ZERO, msg = "the start")
        assertVec3(stationOrFail(path, 300.0).at, Vec3(300.0, 0.0, 0.0), msg = "and the end")
    }

    /** A path with no pieces, and one with no length, each refused in its own words. */
    @Test
    fun aPathWithNothingToStandOnIsRefusedByName() {
        assertTrue(assertNotNull(Stations3.at(Path3(emptyList()), up, 0.0).second).contains("no pieces"))
        val degenerate = Path3(listOf(Curve3Element.Seg3(Vec3.ZERO, Vec3.ZERO)))
        assertTrue(assertNotNull(Stations3.at(degenerate, up, 0.0).second).contains("no length"))
    }

    /**
     * **A closed path is covered exactly once**: its domain is `[0, L]` where `L` includes the closing piece,
     * and there is no wrap — `L` is the start again because the curve comes back there, not because anything
     * wrapped it round.
     */
    @Test
    fun aClosedPathIsCoveredOnceAndDoesNotWrap() {
        val square =
            Path3(
                Curves3.straightThrough(
                    listOf(Vec3(0.0, 0.0, 0.0), Vec3(100.0, 0.0, 0.0), Vec3(100.0, 100.0, 0.0), Vec3(0.0, 100.0, 0.0)),
                    closed = true,
                ),
                closed = true,
            )
        assertClose(Curves3.length(square), 400.0, 1e-9, "four sides of a hundred")
        assertVec3(stationOrFail(square, 350.0).at, Vec3(0.0, 50.0, 0.0), msg = "on the closing piece")
        assertVec3(stationOrFail(square, 400.0).at, Vec3.ZERO, msg = "and the far end is the start again")
        assertTrue(refusal(square, 450.0).contains("past the end"), "past L is off the run, not round it again")
    }

    // ---- 5. the node: invalidity that heals, and two stations from one parameter ----

    /** A point in space for the DSL fixtures: an ordinary plan point lifted by a height, as the tools build. */
    private fun Construction.spacePoint(
        name: String,
        x: Double,
        y: Double,
        z: Double = 0.0,
    ): Pair<Point3Ref, PointRef> {
        val base = freePoint(name, x.mm, y.mm)
        return heightPoint(planeXY(), base, const(z.mm)) to base
    }

    private fun planeOf(
        ev: Evaluator,
        ref: PlaneRef,
    ): Plane3? = ((ev.eval(ref.node) as? EvalResult.Ok)?.value as? PlaneValue)?.plane

    private fun why(
        ev: Evaluator,
        ref: PlaneRef,
    ): String? = (ev.eval(ref.node) as? EvalResult.Invalid)?.reason

    /**
     * **Out of range is node invalidity, and it heals** (OP-3, and OP-26's doctrinal point): the distance is
     * a *live value*, so the plane goes invalid with a reason and comes back when the number does.
     */
    @Test
    fun aStationPastTheEndIsAnInvalidNodeThatHealsWhenTheNumberComesBack() {
        val cx = Construction()
        val path = cx.pathThrough(listOf(cx.spacePoint("a", 0.0, 0.0).first, cx.spacePoint("b", 300.0, 0.0).first))
        val d = cx.parameter("d", 500.0.mm)
        val station = cx.stationPlane(path, cx.planeXY(), d)

        assertNull(planeOf(Evaluator(), station), "500 mm along a 300 mm run is nowhere")
        assertTrue(assertNotNull(why(Evaluator(), station)).contains("past the end"), "and it says so by name")

        cx.set(d, 120.0.mm)
        val healed = assertNotNull(planeOf(Evaluator(), station), "and retyping the number brings it back")
        assertVec3(healed.origin, Vec3(120.0, 0.0, 0.0), msg = "where the number now says")
    }

    /**
     * **Two stations from one parameter**, which is the whole of what OP-26 settled about *relative*
     * stations: `base + d` in the expression language (OP-7), so retyping the base moves both and nothing was
     * built to make that work — sharing a node **is** equality.
     */
    @Test
    fun twoStationsFromOneParameterMoveTogether() {
        val cx = Construction()
        val path = cx.pathThrough(listOf(cx.spacePoint("a", 0.0, 0.0).first, cx.spacePoint("b", 300.0, 0.0).first))
        val base = cx.parameter("base", 50.0.mm)
        val pitch = cx.parameter("pitch", 80.0.mm)
        val first = cx.stationPlane(path, cx.planeXY(), base)
        val second = cx.stationPlane(path, cx.planeXY(), cx.add(base, pitch))

        assertVec3(assertNotNull(planeOf(Evaluator(), first)).origin, Vec3(50.0, 0.0, 0.0))
        assertVec3(assertNotNull(planeOf(Evaluator(), second)).origin, Vec3(130.0, 0.0, 0.0), msg = "base + pitch")

        cx.set(base, 120.0.mm)
        assertVec3(assertNotNull(planeOf(Evaluator(), first)).origin, Vec3(120.0, 0.0, 0.0), msg = "the base moved")
        assertVec3(
            assertNotNull(planeOf(Evaluator(), second)).origin,
            Vec3(200.0, 0.0, 0.0),
            msg = "and the one measured from it moved with it — one node, no machinery",
        )
    }

    /**
     * **The station rides the path**, which is the parenting rule and the point of the feature: move a point
     * the curve runs through and the plane follows, in one recompute and with nothing rewired.
     */
    @Test
    fun movingAPointTheCurveRunsThroughMovesTheStation() {
        val cx = Construction()
        val (far, farBase) = cx.spacePoint("far", 300.0, 0.0)
        val path = cx.pathThrough(listOf(cx.spacePoint("near", 0.0, 0.0).first, far))
        val station = cx.stationPlane(path, cx.planeXY(), cx.const(150.0.mm))
        assertVec3(assertNotNull(planeOf(Evaluator(), station)).origin, Vec3(150.0, 0.0, 0.0))

        cx.set(farBase, 0.0.mm, 300.0.mm)
        val moved = assertNotNull(planeOf(Evaluator(), station), "the station still stands")
        assertVec3(moved.origin, Vec3(0.0, 150.0, 0.0), msg = "the run turned, and the station turned with it")
        assertVec3(moved.normal, Vec3.Y, 1e-9, "including which way it faces")
    }

    /** The tessellation tolerance the transport carries is the mesh's own, and it is stated in millimetres. */
    @Test
    fun theTransportsToleranceIsTheMeshesOwn() {
        assertEquals(0.02, GeomMath.TESS_TOL_MM, "the chord tolerance a station's frame is transported at")
    }

    /** A quarter turn of a helix is a quarter of its length — the constant-speed fact, used as a check. */
    @Test
    fun aHelixIsParameterizedProportionallyToArcLength() {
        val h = coil(radius = 30.0, pitch = 20.0, turns = 1.0)
        val total = h.arcLength
        assertClose(Curves3.paramAtLength(h, total / 4.0), 0.25, 1e-15, "a quarter of the length is a quarter turn")
        assertClose(Curves3.lengthTo(h, 0.5), total / 2.0, 1e-12)
        assertClose(total, sqrt((2 * PI * 30.0) * (2 * PI * 30.0) + 20.0 * 20.0), 1e-9, "one turn of a coil")
    }
}
