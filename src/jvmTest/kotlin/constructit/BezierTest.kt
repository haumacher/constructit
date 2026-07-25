package constructit

import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.bezier
import constructit.dsl.line
import constructit.dsl.loop
import constructit.dsl.scalar
import constructit.geom.Bezier
import constructit.geom.GeomMath
import constructit.geom.Loop
import constructit.geom.ProfileElement
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OP-15 — cubic Béziers. A spline is a pure function of its control points, so it needs no new
 * evaluation machinery; what needs proving is that the *exact* piece maths (area, orientation,
 * transforms) holds for splines as it does for segments and arcs, so a spline may sit in a boundary.
 */
class BezierTest {
    /** Numerical ∮(x·dy − y·dx) over one piece, for checking the closed forms against. */
    private fun integrateNumerically(
        b: Bezier,
        steps: Int = 200_000,
    ): Double {
        var acc = 0.0
        for (i in 0 until steps) {
            val t = (i + 0.5) / steps
            val p = GeomMath.bezierPointAt(b, t)
            val d = GeomMath.bezierTangentAt(b, t)
            acc += (p.x * d.y - p.y * d.x) / steps
        }
        return acc
    }

    private fun doubleArea(b: Bezier) = GeomMath.signedArea(Loop(listOf(ProfileElement.BezierE(b)))) * 2.0

    /** The derived closed form must agree with brute-force integration. */
    @Test
    fun signedAreaClosedFormMatchesNumericIntegration() {
        val cases =
            listOf(
                Bezier(Vec2(0.0, 0.0), Vec2(10.0, 30.0), Vec2(40.0, -20.0), Vec2(50.0, 5.0)),
                Bezier(Vec2(-7.0, 3.0), Vec2(-2.0, 25.0), Vec2(18.0, 25.0), Vec2(23.0, 3.0)),
                Bezier(Vec2(5.0, 5.0), Vec2(5.0, 5.0), Vec2(60.0, 40.0), Vec2(-10.0, 12.0)),
            )
        for (b in cases) {
            assertClose(doubleArea(b), integrateNumerically(b), tol = 1e-6, msg = "for $b")
        }
    }

    /** A Bézier whose controls are evenly spaced along a line *is* that segment, area included. */
    @Test
    fun degenerateBezierAgreesWithTheSegmentItIs() {
        val a = Vec2(3.0, -4.0)
        val d = Vec2(21.0, 9.0)
        val straight = Bezier(a, a + (d - a) * (1.0 / 3.0), a + (d - a) * (2.0 / 3.0), d)
        assertClose(doubleArea(straight), a.cross(d), tol = 1e-9)
        // and it really is straight: the midpoint sits on the chord
        val mid = GeomMath.bezierPointAt(straight, 0.5)
        assertClose((mid - (a + d) * 0.5).length(), 0.0, tol = 1e-12)
    }

    /**
     * Four Béziers with the standard handle length approximate a circle to ~0.02% — a well-known
     * constant, so it doubles as a check that the pieces are oriented and chained consistently.
     */
    @Test
    fun fourBeziersApproximateACircle() {
        val r = 20.0
        val k = 4.0 / 3.0 * (kotlin.math.sqrt(2.0) - 1.0) * r

        fun quadrant(
            from: Vec2,
            to: Vec2,
            h1: Vec2,
            h2: Vec2,
        ) = ProfileElement.BezierE(Bezier(from, from + h1, to + h2, to))
        val e = Vec2(r, 0.0)
        val n = Vec2(0.0, r)
        val w = Vec2(-r, 0.0)
        val s = Vec2(0.0, -r)
        val loop =
            Loop(
                listOf(
                    quadrant(e, n, Vec2(0.0, k), Vec2(k, 0.0)),
                    quadrant(n, w, Vec2(-k, 0.0), Vec2(0.0, k)),
                    quadrant(w, s, Vec2(0.0, -k), Vec2(-k, 0.0)),
                    quadrant(s, e, Vec2(k, 0.0), Vec2(0.0, -k)),
                ),
            )
        val exact = PI * r * r
        val err = abs(GeomMath.signedArea(loop) - exact) / exact
        assertTrue(err < 3e-4, "circle approximation error was $err")
        assertTrue(GeomMath.signedArea(loop) > 0.0, "built counter-clockwise")
    }

    /** Reversing a piece negates its contribution and swaps its ends — the loop machinery relies on it. */
    @Test
    fun reverseNegatesAreaAndSwapsEnds() {
        val b = Bezier(Vec2(0.0, 0.0), Vec2(10.0, 30.0), Vec2(40.0, -20.0), Vec2(50.0, 5.0))
        val e = ProfileElement.BezierE(b)
        val r = GeomMath.reverse(e)
        assertEquals(GeomMath.startOf(e), GeomMath.endOf(r))
        assertEquals(GeomMath.endOf(e), GeomMath.startOf(r))
        assertClose(GeomMath.signedArea(Loop(listOf(r))), -GeomMath.signedArea(Loop(listOf(e))), tol = 1e-9)
    }

    /** Béziers are affine invariant: mapping the control points maps the curve, exactly. */
    @Test
    fun affineInvariance() {
        val c = Construction()
        val p0 = c.freePoint("p0", 0.mm, 0.mm)
        val p1 = c.freePoint("p1", 10.mm, 30.mm)
        val p2 = c.freePoint("p2", 40.mm, (-20).mm)
        val p3 = c.freePoint("p3", 50.mm, 5.mm)
        val b = c.bezier(p0, p1, p2, p3)
        val axis = c.lineThrough(c.freePoint("o", 0.mm, 0.mm), c.freePoint("x", 1.mm, 0.mm))
        val mirrored = c.mirror(b, axis)

        val ev = Evaluator()
        val src = ev.bezier(b)
        val dst = ev.bezier(mirrored)
        // sampling the mirrored curve equals mirroring the sample
        for (i in 0..10) {
            val t = i / 10.0
            val direct = GeomMath.bezierPointAt(dst, t)
            val viaSource = GeomMath.bezierPointAt(src, t).let { Vec2(it.x, -it.y) }
            assertClose((direct - viaSource).length(), 0.0, tol = 1e-9, msg = "at t=$t")
        }
    }

    /**
     * The completeness claim: a spline sits in a boundary next to a segment and an arc, and the
     * resulting area is exact. Here a "D" shape — straight back, semicircular front — is built once
     * with the arc and once with a Bézier standing in for it, and the areas differ only by the
     * spline's known approximation error.
     */
    @Test
    fun aLoopMayMixSegmentsArcsAndSplines() {
        val c = Construction()
        val a = c.freePoint("A", 0.mm, (-10).mm)
        val b = c.freePoint("B", 0.mm, 10.mm)
        val centre = c.freePoint("O", 0.mm, 0.mm)
        val r = c.parameter("r", 10.mm)
        val circle = c.circleCR(centre, r)
        val withArc = c.loop(c.segment(b, a), c.arcBetween(circle, a, b, ccw = false))

        val ev = Evaluator()
        val exact = 0.5 * PI * 100.0
        assertClose(ev.scalar(c.loopArea(withArc)).base, exact, tol = 1e-9)

        // the same boundary with the semicircle spanned by two Béziers instead of the arc
        val k = 4.0 / 3.0 * (kotlin.math.sqrt(2.0) - 1.0) * 10.0
        val east = c.freePoint("E", 10.mm, 0.mm)
        val h1 = c.freePoint("h1", 10.mm, (-k).mm)
        val h2 = c.freePoint("h2", k.mm, (-10).mm)
        val h3 = c.freePoint("h3", k.mm, 10.mm)
        val h4 = c.freePoint("h4", 10.mm, k.mm)
        val withSplines =
            c.loop(
                c.segment(b, a),
                c.bezier(a, h2, h1, east),
                c.bezier(east, h4, h3, b),
            )
        val splineArea = ev.scalar(c.loopArea(withSplines)).base
        assertTrue(
            abs(splineArea - exact) / exact < 3e-4,
            "spline boundary area $splineArea should match the arc's $exact to within the known error",
        )
        assertEquals(3, ev.loop(withSplines).elements.size)
        assertTrue(ev.loop(withSplines).elements.any { it is ProfileElement.BezierE })
    }

    /**
     * Tangency by construction (OP-15): the control point is *placed on* the tangent line, so the
     * curve leaves along it. Nothing is asserted and solved — and it stays true when the line moves.
     */
    @Test
    fun tangencyIsStructural() {
        val c = Construction()
        val origin = c.freePoint("O", 0.mm, 0.mm)
        val dirPt = c.freePoint("D", 3.mm, 4.mm)
        val tangent = c.lineThrough(origin, dirPt)
        val handle = c.parameter("handle", 12.mm)
        val p1 = c.bezierTangentControl(origin, tangent, handle)
        val p2 = c.freePoint("p2", 30.mm, (-5).mm)
        val p3 = c.freePoint("p3", 40.mm, 10.mm)
        val b = c.bezier(origin, p1, p2, p3)

        fun assertLeavesAlongTheLine(ev: Evaluator) {
            val lineDir = ev.line(tangent).dir
            val startTangent = GeomMath.bezierTangentAt(ev.bezier(b), 0.0).normalized()
            assertClose(abs(startTangent.cross(lineDir)), 0.0, tol = 1e-12, msg = "not tangent")
        }
        assertLeavesAlongTheLine(Evaluator())

        // rotate the tangent line and lengthen the handle: still tangent, with no re-solve
        c.set(dirPt, (-2).mm, 7.mm)
        c.set(handle, 25.mm)
        assertLeavesAlongTheLine(Evaluator())
    }
}
