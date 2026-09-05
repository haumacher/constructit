package constructit

import constructit.geom.Connect3
import constructit.geom.Continuity
import constructit.geom.Curve3Element
import constructit.geom.CurveEnd
import constructit.geom.Curves3
import constructit.geom.DrawnPiece
import constructit.geom.Handedness
import constructit.geom.Intersect3
import constructit.geom.Path3
import constructit.geom.Plane3
import constructit.geom.PlaneSection
import constructit.geom.ProfileElement
import constructit.geom.Segment
import constructit.geom.Vec2
import constructit.geom.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Connect** (OP-26, step 7) at the level of the geometry: the joining piece between the end of one curve in
 * space and the end of another, derived from the two endpoint tangents plus two tensions.
 *
 * Two assertions run through the whole file and nearly everything else is a special case of one of them.
 * **It arrives where the runs are**: the joining piece's own ends are the two curve ends, to the last bit,
 * because they *are* the control points. And **it leaves the way they were going**: its tangent at each end
 * is the joined curve's own tangent there — the direction, not merely the angle, and with the sign the
 * `START` case reverses — asserted at zero tolerance wherever the arithmetic is exact and to a bit or two
 * where a normalization has intervened.
 *
 * The G2 mode is asserted the same way one derivative further on: the **curvature vector** at each end is the
 * joined curve's own, and the three cubics the mode is made of are C2 with each other, so the join has no
 * curvature break anywhere — including in its own middle, which is the thing a G2 join built out of two
 * pieces would quietly have.
 *
 * The gesture, the end choice's persistence and everything built on a join are [ConnectToolTest]'s.
 */
class ConnectTest {
    // ---- fixtures: one run of each kind this vocabulary has ----

    /** A straight run along +X, from the origin to (100, 0, 0). */
    private val alongX = Path3(listOf(Curve3Element.Seg3(Vec3.ZERO, Vec3(100.0, 0.0, 0.0))))

    /** A straight run along +Y, from (200, 0, 0) to (200, 100, 0) — its **start** faces the first one. */
    private val alongY = Path3(listOf(Curve3Element.Seg3(Vec3(200.0, 0.0, 0.0), Vec3(200.0, 100.0, 0.0))))

    /** A two-piece polyline, so that "the end" is the end of the *last* piece rather than of the only one. */
    private val bentRun =
        Path3(
            listOf(
                Curve3Element.Seg3(Vec3(0.0, 0.0, 50.0), Vec3(60.0, 0.0, 50.0)),
                Curve3Element.Seg3(Vec3(60.0, 0.0, 50.0), Vec3(100.0, 30.0, 50.0)),
            ),
        )

    /** A smooth run through four points — cubic pieces, so its ends carry a real curvature. */
    private val smoothRun =
        Path3(
            Curves3.smoothThrough(
                listOf(
                    Vec3(0.0, 0.0, 0.0),
                    Vec3(40.0, 30.0, 10.0),
                    Vec3(90.0, 20.0, 30.0),
                    Vec3(140.0, 60.0, 20.0),
                ),
            ),
        )

    /** A right-hand helix: radius 20, pitch 30, two turns, about +Z at (300, 0, 0). */
    private val coil =
        Path3(
            listOf(
                Curve3Element.Helix3.about(
                    origin = Vec3(300.0, 0.0, 0.0),
                    axisDir = Vec3.Z,
                    phase = Vec3.X,
                    radius = 20.0,
                    pitch = 30.0,
                    turns = 2.0,
                    hand = Handedness.RIGHT,
                ),
            ),
        )

    // ---- the two measurements, written independently of the construction ----

    private fun join(
        a: Path3,
        endA: CurveEnd,
        b: Path3,
        endB: CurveEnd,
        tensionA: Double = 1.0,
        tensionB: Double = 1.0,
        mode: Continuity = Continuity.G1,
    ): Path3 {
        val (path, why) = Connect3.connected(a, endA, tensionA, b, endB, tensionB, mode)
        return assertNotNull(path, "the join was refused: $why")
    }

    private fun refusal(
        a: Path3,
        endA: CurveEnd,
        b: Path3,
        endB: CurveEnd,
        tensionA: Double = 1.0,
        tensionB: Double = 1.0,
        mode: Continuity = Continuity.G1,
    ): String {
        val (path, why) = Connect3.connected(a, endA, tensionA, b, endB, tensionB, mode)
        assertNull(path, "this join was expected to be refused")
        return assertNotNull(why, "a refusal says why").render()
    }

    /** The unit direction the run [p] leaves its own start in — read off its first piece, not off the join. */
    private fun leavesAlong(p: Path3): Vec3 = assertNotNull(Curves3.tangentAt(p.elements.first(), 0.0))

    /** The unit direction the run [p] arrives at its own end in. */
    private fun arrivesAlong(p: Path3): Vec3 = assertNotNull(Curves3.tangentAt(p.elements.last(), 1.0))

    /** What a curve's own tangent is at [end] — the thing a G1 join has to agree with. */
    private fun tangentOf(
        p: Path3,
        end: CurveEnd,
    ): Vec3 =
        assertNotNull(
            Curves3.tangentAt(if (end == CurveEnd.START) p.elements.first() else p.elements.last(), end.t),
        )

    private fun curvatureOf(
        p: Path3,
        end: CurveEnd,
    ): Vec3 =
        assertNotNull(
            Curves3.curvatureVectorAt(if (end == CurveEnd.START) p.elements.first() else p.elements.last(), end.t),
        )

    private fun assertVec3(
        actual: Vec3,
        expected: Vec3,
        tol: Double,
        msg: String,
    ) {
        assertClose(actual.x, expected.x, tol, "$msg (x)")
        assertClose(actual.y, expected.y, tol, "$msg (y)")
        assertClose(actual.z, expected.z, tol, "$msg (z)")
    }

    private fun pointAt(
        p: Path3,
        x: Double,
    ): Vec3 {
        val n = p.elements.size
        val i = minOf(n - 1, (x * n).toInt())
        return Curves3.bezierPointAt(p.elements[i] as Curve3Element.Bezier3, (x * n) - i)
    }

    // ---- 1. it passes through both ends, exactly ----

    /**
     * **The joining piece begins and ends at the two curve ends, to the last bit** — because those *are* its
     * outer control points, so this is a fact about the construction rather than a tolerance it meets.
     */
    @Test
    fun theJoinStandsOnBothCurveEndsExactly() {
        val piece = join(alongX, CurveEnd.END, alongY, CurveEnd.START).elements.single() as Curve3Element.Bezier3
        assertEquals(Vec3(100.0, 0.0, 0.0), piece.p0, "it starts at the first run's end")
        assertEquals(Vec3(200.0, 0.0, 0.0), piece.p3, "and finishes at the second run's start")

        // …and the same for a three-piece G2 join, whose outer control points are the same two points
        val g2 = join(smoothRun, CurveEnd.END, coil, CurveEnd.START, mode = Continuity.G2)
        assertEquals(3, g2.elements.size, "G2 is three cubics")
        assertEquals(smoothRun.end, g2.start, "exactly on the first run's end")
        assertEquals(coil.start, g2.end, "exactly on the second run's start")
    }

    // ---- 2. G1 by construction: the direction, not the angle ----

    /**
     * **The join leaves along the first run's own tangent and arrives along the second's** — asserted as a
     * *direction*, at **zero** tolerance, in a fixture whose arithmetic is exact.
     *
     * The first run goes along +X and is joined at its end, so the join must leave along +X. The second goes
     * along +Y and is joined at its **start**, so the join must arrive along +Y — which is the second run's
     * own direction, since the connection runs into it.
     */
    @Test
    fun theJoinLeavesAndArrivesAlongTheRunsOwnDirections() {
        val piece = join(alongX, CurveEnd.END, alongY, CurveEnd.START)
        assertEquals(Vec3.X, leavesAlong(piece), "exactly the first run's direction")
        assertEquals(Vec3.Y, arrivesAlong(piece), "exactly the second run's direction")
    }

    /**
     * **Joining a curve's *start* runs against its parameter direction**, and that is the whole of the sign
     * rule: the join leaves *away* from the curve it leaves, which at a start is the reversed tangent.
     *
     * A wrong sign here would double the join back over the run it joins, which must not be producible; the
     * assertion is the direction itself, at zero tolerance.
     */
    @Test
    fun joiningAStartRunsAgainstThatCurvesDirection() {
        val piece = join(alongX, CurveEnd.START, alongY, CurveEnd.END)
        assertVec3(leavesAlong(piece), -Vec3.X, 0.0, "away from the run, which at a start is backwards along it")
        assertVec3(arrivesAlong(piece), -Vec3.Y, 0.0, "and into the second run's end against its direction")
        // the join therefore leaves the first run behind rather than lying back over it
        assertTrue(pointAt(piece, 0.2).x < 0.0, "it runs off the far side of the first run's start")
    }

    /**
     * **Every pairing of the three piece kinds, on either end, is G1** — which is what makes `tangentAt`'s
     * three cases the load-bearing part of this step. Nine combinations, and each is asserted as a direction.
     */
    @Test
    fun everyKindOfRunOnEitherEndIsJoinedTangentContinuously() {
        val runs = listOf("polyline" to bentRun, "smooth" to smoothRun, "helix" to coil)
        for ((na, a) in runs) {
            for ((nb, b) in runs) {
                if (na == nb) continue
                for (endA in CurveEnd.entries) {
                    for (endB in CurveEnd.entries) {
                        val piece = join(a, endA, b, endB)
                        val wantOut = tangentOf(a, endA) * endA.outSign
                        val wantIn = tangentOf(b, endB) * -endB.outSign
                        assertVec3(leavesAlong(piece), wantOut, 1e-15, "$na ${endA.word} -> $nb ${endB.word}: leaves")
                        assertVec3(arrivesAlong(piece), wantIn, 1e-15, "$na ${endA.word} -> $nb ${endB.word}: arrives")
                    }
                }
            }
        }
    }

    // ---- 3. the tensions do what they say ----

    /**
     * **Tension 1 is the straight segment**, exactly, when the two ends face each other along the gap — which
     * is the argument for the default and the reason the two modes are comparable at all.
     *
     * The run along +X is joined at its end to a run that starts 100 mm further along +X and goes on in the
     * same direction: the four control points come out at 0, ⅓, ⅔ and 1 of the gap, so the join *is* the
     * straight segment between the two ends, uniformly parameterized. It is also the constant
     * [Curves3.smoothThrough] uses at an open end, so the join is the curve a smooth run through those points
     * would have drawn.
     */
    @Test
    fun tensionOneIsExactlyTheStraightSegmentWhenTheEndsFaceEachOther() {
        val ahead = Path3(listOf(Curve3Element.Seg3(Vec3(200.0, 0.0, 0.0), Vec3(300.0, 0.0, 0.0))))
        val piece = join(alongX, CurveEnd.END, ahead, CurveEnd.START).elements.single() as Curve3Element.Bezier3
        assertEquals(Vec3(100.0, 0.0, 0.0), piece.p0)
        assertVec3(piece.p1, Vec3(400.0 / 3.0, 0.0, 0.0), 1e-12, "a third of the gap along the first tangent")
        assertVec3(piece.p2, Vec3(500.0 / 3.0, 0.0, 0.0), 1e-12, "and a third back along the second")
        assertEquals(Vec3(200.0, 0.0, 0.0), piece.p3)

        // …and the G2 mode agrees with it: ten control points, evenly spaced, so the same straight segment
        val g2 = join(alongX, CurveEnd.END, ahead, CurveEnd.START, mode = Continuity.G2)
        val controls =
            g2.elements.flatMap { (it as Curve3Element.Bezier3).let { b -> listOf(b.p0, b.p1, b.p2) } } + Vec3(200.0, 0.0, 0.0)
        for ((i, p) in controls.withIndex()) {
            assertVec3(p, Vec3(100.0 + i * (100.0 / 9.0), 0.0, 0.0), 1e-12, "control $i of the G2 join")
        }
    }

    /**
     * **Raising a tension pulls the join towards that curve's tangent** — the number's whole meaning, and it
     * is asserted against an independently computed distance rather than against a control point.
     *
     * **Measured 40 mm along the join's own run**, so that the comparison is between two curves at the same
     * physical distance from the end rather than at the same parameter — the distance from that point to the
     * first run's tangent line falls, strictly, as the tension is raised. The geometry also moves
     * **continuously** with the number, so there is nothing that could jump.
     */
    @Test
    fun raisingATensionPullsTheJoinTowardsThatRunsTangent() {
        fun offTangent(t: Double): Double =
            distanceToTangentLine(
                join(alongX, CurveEnd.END, alongY, CurveEnd.START, tensionA = t),
                Vec3(100.0, 0.0, 0.0),
                Vec3.X,
                40.0,
                fromEnd = false,
            )
        val d = listOf(0.25, 0.5, 1.0, 2.0, 4.0).map { offTangent(it) }
        for (i in 0 until d.size - 1) assertTrue(d[i] > d[i + 1], "a higher tension hugs the tangent: $d")
        assertTrue(d.first() > 4.0 * d.last(), "and it is a real difference, not a rounding one: $d")

        // continuous in the parameter: a thousandth of a change moves the curve by a hundredth of a millimetre
        val a = offTangent(1.0)
        val b = offTangent(1.001)
        assertTrue(abs(a - b) < 0.02, "the join moves continuously with the tension: $a vs $b")

        // and the far tension does the same thing at the other end, independently
        fun offFar(t: Double): Double =
            distanceToTangentLine(
                join(alongX, CurveEnd.END, alongY, CurveEnd.START, tensionB = t),
                Vec3(200.0, 0.0, 0.0),
                Vec3.Y,
                40.0,
                fromEnd = true,
            )
        assertTrue(offFar(2.0) < offFar(0.5), "the second tension pulls the far end towards the second run")
    }

    /**
     * How far the join stands from the line through [from] along [dir], measured [s] millimetres along the
     * join from whichever end — the independent reading of what a tension does.
     */
    private fun distanceToTangentLine(
        piece: Path3,
        from: Vec3,
        dir: Vec3,
        s: Double,
        fromEnd: Boolean,
    ): Double {
        val el = if (fromEnd) piece.elements.last() else piece.elements.first()
        val at = if (fromEnd) Curves3.arcLength(el) - s else s
        val p = Curves3.bezierPointAt(el as Curve3Element.Bezier3, Curves3.paramAtLength(el, at))
        val v = p - from
        return (v - dir * v.dot(dir)).length()
    }

    /** **A tension of nothing, or less, is refused by name** — and it is a value, so it heals. */
    @Test
    fun aTensionOfNothingOrLessIsRefusedByName() {
        val zero = refusal(alongX, CurveEnd.END, alongY, CurveEnd.START, tensionA = 0.0)
        assertTrue(zero.contains("first tension"), "it says which of the two: $zero")
        assertTrue(zero.contains("no join at all"), "…and why nothing is not a join: $zero")
        val negative = refusal(alongX, CurveEnd.END, alongY, CurveEnd.START, tensionB = -1.0)
        assertTrue(negative.contains("second tension"), "the far one names itself too: $negative")
        // …and the very next value is a join again, which is what healing means one layer down
        assertNotNull(Connect3.connected(alongX, CurveEnd.END, 1e-6, alongY, CurveEnd.START, 1.0, Continuity.G1).first)
    }

    // ---- 4. the degenerate cases, each a property of values ----

    /** **Two ends in the same place state no gap**, and the refusal says so rather than building a point. */
    @Test
    fun endsInTheSamePlaceAreRefusedByName() {
        val touching = Path3(listOf(Curve3Element.Seg3(Vec3(100.0, 0.0, 0.0), Vec3(160.0, 40.0, 0.0))))
        val why = refusal(alongX, CurveEnd.END, touching, CurveEnd.START)
        assertTrue(why.contains("same place"), "it says what is wrong: $why")
        assertTrue(why.contains("no gap"), "…and what a join is for: $why")
        // the same curve's own end joined to itself is that same condition, and it is caught here
        assertTrue(refusal(alongX, CurveEnd.END, alongX, CurveEnd.END).contains("same place"))
        // …while its **two** ends are an ordinary join, which is how a run is closed
        assertNotNull(join(alongX, CurveEnd.END, alongX, CurveEnd.START), "a run may be joined to its own start")
    }

    /** **A closed run has no end to join**, and closure is a value for a derived curve, so it is said here. */
    @Test
    fun aClosedRunHasNoEndToJoin() {
        val loop =
            Path3(
                Curves3.straightThrough(
                    listOf(Vec3.ZERO, Vec3(50.0, 0.0, 0.0), Vec3(50.0, 50.0, 0.0)),
                    closed = true,
                ),
                closed = true,
            )
        val why = refusal(loop, CurveEnd.END, alongY, CurveEnd.START)
        assertTrue(why.contains("closed run"), "it names what a closed run is: $why")
        assertTrue(why.contains("no end to join"), "…and why it cannot be joined: $why")
        assertTrue(refusal(alongX, CurveEnd.END, loop, CurveEnd.START).contains("second curve"), "either side")
    }

    /** A run with no pieces at all states nothing to join, and says so rather than throwing. */
    @Test
    fun aRunWithNoPiecesIsRefusedByName() {
        assertTrue(refusal(Path3(emptyList()), CurveEnd.END, alongY, CurveEnd.START).contains("no pieces"))
    }

    // ---- 5. G2: the curvature, and it is exact ----

    /**
     * **The G2 join's curvature at each end is the joined run's own curvature vector** — magnitude *and*
     * direction — and it is exact rather than fitted: the construction places the second control point of
     * each end span at the point that makes it so.
     *
     * A helix is the honest fixture for this: its curvature is a closed form and a constant, so the number the
     * join has to match is one this test can state (`r / (r² + b²)`) rather than one it has to measure.
     */
    @Test
    fun theG2JoinMatchesEachRunsCurvatureAtItsEnd() {
        val helix = coil.elements.single() as Curve3Element.Helix3
        val piece = join(smoothRun, CurveEnd.END, coil, CurveEnd.START, mode = Continuity.G2)

        val startK = assertNotNull(Curves3.curvatureVectorAt(piece.elements.first(), 0.0))
        assertVec3(startK, curvatureOf(smoothRun, CurveEnd.END), 1e-12, "the join bends as the first run bends")
        val endK = assertNotNull(Curves3.curvatureVectorAt(piece.elements.last(), 1.0))
        assertVec3(endK, curvatureOf(coil, CurveEnd.START), 1e-12, "…and as the second run bends")
        assertClose(endK.length(), helix.curvature, 1e-12, "which for a helix is its own stated constant")
        assertTrue(helix.curvature > 1e-3, "and it is a curvature worth matching, not a rounding artefact")

        // the G1 join of the same two runs agrees on the tangent and does *not* on the curvature, which is
        // what makes the mode worth having rather than a second spelling of the same curve
        val g1 = join(smoothRun, CurveEnd.END, coil, CurveEnd.START)
        assertVec3(leavesAlong(g1), leavesAlong(piece), 1e-12, "both modes leave the same way")
        val g1k = assertNotNull(Curves3.curvatureVectorAt(g1.elements.first(), 0.0))
        assertTrue((g1k - startK).length() > 1e-4, "…and only the G2 one matches the curvature: $g1k vs $startK")
    }

    /**
     * **The G2 join has no curvature break inside itself either.** Its three cubics are C2 in the run's own
     * parameterization — equal first *and* second derivatives across both interior joins — so the piece is
     * one smooth object rather than a smooth pair of ends with a kink hidden in the middle.
     */
    @Test
    fun theG2JoinIsItselfCurvatureContinuousAtItsOwnJoins() {
        val piece = join(smoothRun, CurveEnd.END, coil, CurveEnd.START, tensionA = 1.4, tensionB = 0.8, mode = Continuity.G2)
        assertEquals(3, piece.elements.size)
        for (i in 0 until piece.elements.size - 1) {
            val left = piece.elements[i]
            val right = piece.elements[i + 1]
            assertEquals(left.end, right.start, "join $i hands over the same point")
            assertVec3(Curves3.derivativeAt(left, 1.0), Curves3.derivativeAt(right, 0.0), 1e-9, "join $i is C1")
            assertVec3(Curves3.secondDerivativeAt(left, 1.0), Curves3.secondDerivativeAt(right, 0.0), 1e-8, "join $i is C2")
        }
    }

    /**
     * **A run with no curvature is joined G2 just as happily**, and the answer is the one that says so: a
     * straight run's curvature is exactly zero, so the join leaves it perfectly straight before it turns.
     */
    @Test
    fun aStraightRunIsJoinedWithZeroCurvatureRatherThanASmallOne() {
        val piece = join(alongX, CurveEnd.END, coil, CurveEnd.START, mode = Continuity.G2)
        val k = assertNotNull(Curves3.curvatureVectorAt(piece.elements.first(), 0.0))
        assertVec3(k, Vec3.ZERO, 0.0, "exactly zero, because a segment's second derivative is exactly zero")
    }

    /** The G2 mode refuses by name where a piece has no direction, since there is then no curvature to match. */
    @Test
    fun aRunThatStandsStillHasNoCurvatureForTheG2ModeToMatch() {
        val stalled =
            Path3(
                listOf(
                    Curve3Element.Bezier3(
                        Vec3(400.0, 0.0, 0.0),
                        Vec3(400.0, 0.0, 0.0),
                        Vec3(460.0, 20.0, 0.0),
                        Vec3(500.0, 0.0, 0.0),
                    ),
                ),
            )
        val why = refusal(alongX, CurveEnd.END, stalled, CurveEnd.START, mode = Continuity.G2)
        assertTrue(why.contains("no curvature"), "it says what is missing: $why")
        assertTrue(why.contains("G1"), "…and what does work instead: $why")
        // …while the G1 mode joins it, because a direction is all *it* needs and the chord supplies one
        assertNotNull(join(alongX, CurveEnd.END, stalled, CurveEnd.START))
    }

    // ---- 6. the curvature reader itself, against arithmetic that knows nothing about it ----

    /**
     * **A helix's curvature vector points at its axis and has the length the closed form states** — the
     * reader the G2 mode rests on, checked against the piece's own analytic constant and against the geometry.
     */
    @Test
    fun theCurvatureVectorOfAHelixPointsAtItsAxis() {
        val helix = coil.elements.single() as Curve3Element.Helix3
        for (t in listOf(0.0, 0.17, 0.5, 0.83, 1.0)) {
            val k = assertNotNull(Curves3.curvatureVectorAt(helix, t))
            assertClose(k.length(), helix.curvature, 1e-12, "the closed form, at t = $t")
            val onAxis = helix.origin + helix.axis * (helix.at(t) - helix.origin).dot(helix.axis)
            val inwards = (onAxis - helix.at(t)).normalized()
            assertVec3(k.normalized(), inwards, 1e-12, "and it points straight at the axis at t = $t")
        }
    }

    /** A cubic's curvature, against a difference quotient of its own unit tangent — an independent formula. */
    @Test
    fun theCurvatureVectorOfACubicAgreesWithADifferenceQuotient() {
        val el = smoothRun.elements[1] as Curve3Element.Bezier3
        for (t in listOf(0.2, 0.5, 0.8)) {
            val h = 1e-5
            val t0 = assertNotNull(Curves3.tangentAt(el, t - h))
            val t1 = assertNotNull(Curves3.tangentAt(el, t + h))
            val speed = Curves3.derivativeAt(el, t).length()
            val numeric = (t1 - t0) * (1.0 / (2.0 * h * speed))
            assertVec3(assertNotNull(Curves3.curvatureVectorAt(el, t)), numeric, 1e-6, "dT/ds at t = $t")
        }
    }

    /**
     * **An open intersection run is joined like any other** — the third provenance (OP-26's *derived*), and
     * the point of the test is that nothing in the join knows where its operand came from.
     *
     * An open run is what a plane cuts an **open shell** in (session 34's imported lid), so the fixture is the
     * chain such a cut produces, lifted through a plane by the step-6 machinery itself rather than hand-built
     * in space. Its closed sibling has no end at all, which is the refusal above.
     */
    @Test
    fun anOpenIntersectionRunIsJoinedLikeAnyOther() {
        val pts = listOf(Vec2(0.0, 0.0), Vec2(40.0, 0.0), Vec2(70.0, 30.0), Vec2(120.0, 30.0))
        val chain = (0 until 3).map { ProfileElement.Seg(Segment(pts[it], pts[it + 1])) }
        val section = PlaneSection(emptyList(), emptyList(), null, null, chain.map { DrawnPiece(it, false) }, false)
        val plane = Plane3(Vec3(0.0, 0.0, 60.0), Vec3.X, Vec3.Y)
        val cut = Intersect3.curvesOf(section, plane).curves.single().path
        assertTrue(!cut.closed, "the fixture is the open case")

        val piece = join(cut, CurveEnd.END, coil, CurveEnd.START)
        assertEquals(cut.end, piece.start, "on the cut's own far end")
        assertVec3(leavesAlong(piece), tangentOf(cut, CurveEnd.END), 1e-15, "leaving the way the cut runs")
        assertVec3(arrivesAlong(piece), tangentOf(coil, CurveEnd.START), 1e-15, "and arriving along the coil")
    }

    // ---- 7. it is a pure function of its inputs ----

    /** The same two runs and the same two numbers give the same join, bit for bit. */
    @Test
    fun theJoinIsDeterministic() {
        val once = join(smoothRun, CurveEnd.END, coil, CurveEnd.START, 1.3, 0.7, Continuity.G2)
        val twice = join(smoothRun, CurveEnd.END, coil, CurveEnd.START, 1.3, 0.7, Continuity.G2)
        assertEquals(once, twice, "one construction, one answer")
        assertTrue(
            join(smoothRun, CurveEnd.END, coil, CurveEnd.START, 1.3, 0.7) != once,
            "…and the two modes are different curves",
        )
    }

    /** The join runs **from** the first pick's end **to** the second's, which is the order of the two clicks. */
    @Test
    fun theJoinRunsFromTheFirstRunToTheSecond() {
        val piece = join(coil, CurveEnd.END, alongX, CurveEnd.START)
        assertEquals(coil.end, piece.start, "it starts where the first pick ends")
        assertEquals(alongX.start, piece.end, "and finishes where the second pick starts")
        assertTrue(Curves3.length(piece) > 0.0, "and it is a run of positive length")
    }
}
