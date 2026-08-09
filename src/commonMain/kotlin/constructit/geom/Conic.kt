package constructit.geom

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * An **ellipse**, as a frame and two semi-axes (OP-24): the point set
 * `C + cos t · R(rotation)·(a, 0) + sin t · R(rotation)·(0, b)`.
 *
 * [a] is the semi-axis along the ellipse's own +u — the direction [rotation] names — and [b] the one
 * along +v, perpendicular to it. **Neither is required to be the larger.** That is a decision, and it is
 * the one thing about this type worth arguing:
 *
 * - *Not* normalised to `a ≥ b` by swapping and turning the frame, because the frame is what the
 *   **parametric angle** is measured in, and that angle is a stored degree of freedom: a rider records
 *   its `t` (OP-16's on-circle angle, one conic up) and an elliptic arc records its interval. A swap
 *   would reinterpret every one of those by 90° at the instant `b` grew past `a` — a stored value
 *   silently meaning something else, which is exactly what the no-solver stance forbids.
 * - *Not* refused when `b > a` either, because there is nothing wrong with such an ellipse: typing 80
 *   into the `b` of an `a = 60` ellipse asks for a taller one, and answering "invalid" would make the
 *   parameter panel a dead end rather than a control.
 *
 * So the frame is **structural** (the axis-end point picks it) and the two semi-axes are **values**.
 * Where a canonical form is genuinely wanted — a *derived* ellipse nobody picked a frame for, such as an
 * inclined section of a cylinder — [canonical] produces the major-first reading, and
 * [Conics.cylinderSection] emits it.
 */
data class Ellipse(
    val center: Vec2,
    val a: Double,
    val b: Double,
    val rotation: Double,
) {
    /** The longer semi-axis. */
    val major: Double get() = max(a, b)

    /** The shorter semi-axis. */
    val minor: Double get() = min(a, b)

    /** The direction of the **major** axis, in `[0, π)` — what a dimension names, not what `t` is measured in. */
    val majorAngle: Double get() = Conics.norm(if (a >= b) rotation else rotation + PI / 2.0, PI)

    /** The ellipse's own +u axis as a unit world vector — the direction `t = 0` lies in. */
    val uAxis: Vec2 get() = Vec2(cos(rotation), sin(rotation))

    /** The ellipse's own +v axis as a unit world vector — the direction `t = π/2` lies in. */
    val vAxis: Vec2 get() = Vec2(-sin(rotation), cos(rotation))

    /** True when this is a circle to within floating-point noise — the degenerate case with no unique frame. */
    val isCircular: Boolean get() = abs(a - b) <= 1e-12 * max(a, b)

    /**
     * The same point set with `a ≥ b` and the frame turned to match — the reading for an ellipse **no one
     * picked a frame for**. Never applied to a constructed ellipse; see the type's own note.
     */
    fun canonical(): Ellipse =
        if (a >= b) copy(rotation = Conics.norm(rotation, PI)) else Ellipse(center, b, a, Conics.norm(rotation + PI / 2.0, PI))
}

/**
 * An **elliptic arc** (OP-24): the piece of [ellipse] from [startT] to [endT] in **parametric angle**,
 * swept counter-clockwise in the parameter if [ccw].
 *
 * The parameter, not the arc length, is what an arc is trimmed by — the same choice [Arc] makes and the
 * reason position-along an elliptic arc is **exact**: point, tangent and normal at `t` are plain
 * trigonometry (see [Conics.pointAt]). What is genuinely metric — the arc's measured *length*, and
 * spacing points at equal *distances* along it — is computed numerically to a stated tolerance
 * ([Conics.LENGTH_TOL_MM]), because an elliptic integral has no closed form. Construction exact,
 * measurement approximate.
 */
data class EllipticArc(
    val ellipse: Ellipse,
    val startT: Double,
    val endT: Double,
    val ccw: Boolean,
)

/**
 * The maths of conics: points, tangents, tessellation, area, length, nearest-parameter and the
 * intersections — line ∩ ellipse (a quadratic) and conic ∩ ellipse (a **quartic**, up to four solutions).
 *
 * Everything here is a pure function of values, exactly as [GeomMath] is, and returns ordered solution
 * sets (OP-1). The ordering conventions are stated on the functions that establish them and recorded in
 * DESIGN.md, because a persisted branch index means nothing without them.
 */
object Conics {
    private const val EPS = Vec2.EPS

    /**
     * The stated tolerance of a **measured** elliptic arc length, in millimetres (OP-15's approximated
     * class). The adaptive integrator below reaches far better than this in practice; the number is what
     * the dimension *promises*, and it is a documented constant rather than a knob for the same reason
     * [GeomMath.TESS_TOL_MM] is.
     */
    const val LENGTH_TOL_MM = 1e-9

    /** How close two solutions may be, in parametric radians, before they are the same solution. */
    const val SAME_PARAM = 1e-7

    /** How far a candidate may miss the second conic, in mm, and still count as an intersection. */
    private const val HIT_TOL_MM = 1e-6

    /** `x` folded into `[0, period)`. */
    fun norm(
        x: Double,
        period: Double = 2.0 * PI,
    ): Double {
        var r = x % period
        if (r < 0.0) r += period
        return r
    }

    // ---- position along: exact, by parametric angle ----

    /** The point of [e] at parametric angle [t] — `C + a·cos t·u + b·sin t·v`, exact. */
    fun pointAt(
        e: Ellipse,
        t: Double,
    ): Vec2 {
        val c = cos(e.rotation)
        val s = sin(e.rotation)
        val x = e.a * cos(t)
        val y = e.b * sin(t)
        return Vec2(e.center.x + x * c - y * s, e.center.y + x * s + y * c)
    }

    /** The derivative `dP/dt` at [t] — the tangent direction, **not** normalised. */
    fun tangentAt(
        e: Ellipse,
        t: Double,
    ): Vec2 {
        val c = cos(e.rotation)
        val s = sin(e.rotation)
        val x = -e.a * sin(t)
        val y = e.b * cos(t)
        return Vec2(x * c - y * s, x * s + y * c)
    }

    /** The second derivative `d²P/dt²` — what the chord-tolerance bound and Newton's step are stated in. */
    private fun curvatureVec(
        e: Ellipse,
        t: Double,
    ): Vec2 {
        val c = cos(e.rotation)
        val s = sin(e.rotation)
        val x = -e.a * cos(t)
        val y = -e.b * sin(t)
        return Vec2(x * c - y * s, x * s + y * c)
    }

    /**
     * The **outward** unit normal of [e] at [t] — the gradient of `x²/a² + y²/b²`, which points away from
     * the centre for every `t` and does not flip where the tangent does.
     */
    fun normalAt(
        e: Ellipse,
        t: Double,
    ): Vec2 {
        val c = cos(e.rotation)
        val s = sin(e.rotation)
        val x = e.b * cos(t)
        val y = e.a * sin(t)
        val n = Vec2(x * c - y * s, x * s + y * c)
        return if (n.length() < EPS) Vec2(c, s) else n.normalized()
    }

    /** [p] read in [e]'s own frame — the local coordinates every implicit test is stated in. */
    private fun toLocal(
        e: Ellipse,
        p: Vec2,
    ): Vec2 {
        val c = cos(e.rotation)
        val s = sin(e.rotation)
        val dx = p.x - e.center.x
        val dy = p.y - e.center.y
        return Vec2(dx * c + dy * s, -dx * s + dy * c)
    }

    /** `x²/a² + y²/b² − 1` at [p]: zero on the ellipse, negative inside, positive outside. */
    fun implicit(
        e: Ellipse,
        p: Vec2,
    ): Double {
        val q = toLocal(e, p)
        return (q.x / e.a) * (q.x / e.a) + (q.y / e.b) * (q.y / e.b) - 1.0
    }

    /**
     * The parametric angle of the point of [e] **nearest** [p] — what a click on an ellipse means, and what
     * a rider's drag writes (the OP-16 on-circle angle, one conic up).
     *
     * Seeded from the radial reading `atan2(qy/b, qx/a)` (exact for a circle, and never more than a
     * quadrant out for an ellipse), then Newton on `g(t) = (P(t) − p) · P'(t)`, whose zeros are the
     * stationary distances. A pure function of the curve and the click, exactly as
     * [GeomMath.bezierNearestParam] is: what a break or a drag records is the parameter it returned —
     * state, restated on save (OP-18) — so a reload never re-runs the search.
     */
    fun paramOf(
        e: Ellipse,
        p: Vec2,
    ): Double {
        val q = toLocal(e, p)
        var t = atan2(q.y / e.b, q.x / e.a)
        if (!t.isFinite()) t = 0.0
        repeat(60) {
            val d = pointAt(e, t) - p
            val d1 = tangentAt(e, t)
            val d2 = curvatureVec(e, t)
            val g = d.dot(d1)
            val gp = d1.dot(d1) + d.dot(d2)
            if (abs(gp) < 1e-18) return@repeat
            val step = g / gp
            if (!step.isFinite()) return@repeat
            t -= step.coerceIn(-0.5, 0.5)
        }
        return norm(t)
    }

    /** The point of [e] nearest [p] — [paramOf] evaluated. */
    fun nearestPoint(
        e: Ellipse,
        p: Vec2,
    ): Vec2 = pointAt(e, paramOf(e, p))

    // ---- elliptic arcs ----

    /** Signed sweep of [arc] in **parametric** radians: positive counter-clockwise, in (−2π, 2π). */
    fun sweep(arc: EllipticArc): Double {
        val twoPi = 2 * PI
        val raw = (arc.endT - arc.startT) % twoPi
        return if (arc.ccw) {
            if (raw < 0) raw + twoPi else raw
        } else {
            if (raw > 0) raw - twoPi else raw
        }
    }

    /** Whether parametric angle [t] lies within [arc]'s own sweep (ends included, to [GeomMath.ANGLE_TOL]). */
    fun contains(
        arc: EllipticArc,
        t: Double,
    ): Boolean {
        val total = sweep(arc)
        val twoPi = 2 * PI
        val along = if (total >= 0) (t - arc.startT) % twoPi else (arc.startT - t) % twoPi
        val x = if (along < 0) along + twoPi else along
        return x <= abs(total) + GeomMath.ANGLE_TOL
    }

    fun start(arc: EllipticArc): Vec2 = pointAt(arc.ellipse, arc.startT)

    fun end(arc: EllipticArc): Vec2 = pointAt(arc.ellipse, arc.endT)

    /** The unit tangent at [t] **in the arc's own walk direction** — what a wall's left side is measured from. */
    fun walkTangent(
        arc: EllipticArc,
        t: Double,
    ): Vec2 {
        val d = tangentAt(arc.ellipse, t)
        val u = if (d.length() < EPS) Vec2(1.0, 0.0) else d.normalized()
        return if (sweep(arc) >= 0.0) u else -u
    }

    // ---- tessellation ----

    /**
     * How many chords a parametric sweep of [sweep] on [e] needs to stay within [tolMm] of the curve.
     *
     * The bound is the circle's, stated at the **larger** semi-axis: the sagitta of a chord over a
     * parametric step Δt is at most `|P''|·Δt²/8` and `|P''(t)| ≤ max(a, b)`, which is exactly the number
     * [GeomMath.chordSteps] already turns into a step count for a circle of that radius. So one rule, two
     * curve families, and it is conservative rather than fitted.
     */
    fun chordSteps(
        e: Ellipse,
        sweep: Double,
        tolMm: Double,
        quality: MeshQuality = MeshQuality.FINE,
    ): Int = GeomMath.chordSteps(e.major, sweep, tolMm, quality)

    /** Points along [arc] at [steps] equal **parametric** steps, both ends included. */
    fun sample(
        arc: EllipticArc,
        steps: Int,
    ): List<Vec2> {
        val sw = sweep(arc)
        return (0..steps).map { pointAt(arc.ellipse, arc.startT + sw * it / steps) }
    }

    /** Points round the whole ellipse at [steps] steps from `t = 0`, closing back onto the first. */
    fun sampleWhole(
        e: Ellipse,
        steps: Int,
        ccw: Boolean,
    ): List<Vec2> {
        val sw = (if (ccw) 2.0 else -2.0) * PI
        return (0..steps).map { pointAt(e, sw * it / steps) }
    }

    /**
     * The renderer's step count for an elliptic arc: a **fixed** 64 per full turn of parameter, for the
     * reason [GeomMath.renderArcSteps] gives — an adaptive count would make every SVG golden depend on
     * the camera.
     */
    fun renderSteps(sweep: Double): Int = max(6, ceil(abs(sweep) / (2.0 * PI) * 64).toInt())

    /** How many chords a **whole** ellipse draws as — the circle's 64, stated once. */
    const val RENDER_STEPS = 64

    /**
     * An elliptic arc as the polyline every 2D consumer draws and picks against — one policy, so what the
     * canvas draws, what an SVG golden records and what [constructit.editor.HitTest] measures agree.
     */
    fun renderSample(arc: EllipticArc): List<Vec2> = sample(arc, renderSteps(sweep(arc)))

    /** The same for a whole ellipse, closed back onto its first point. */
    fun renderSampleWhole(
        e: Ellipse,
        ccw: Boolean,
    ): List<Vec2> = sampleWhole(e, RENDER_STEPS, ccw)

    // ---- area: exact ----

    /**
     * Twice the signed area swept by an elliptic arc, as the line integral `∮ (x·dy − y·dx)` — **exact**.
     *
     * In the ellipse's own frame the rotation cancels out of `x y' − y x'` entirely and what is left is
     * the constant `a·b` plus the centre's contribution, so the integral is `a·b·Δt` plus two boundary
     * terms. A whole ellipse therefore encloses `π·a·b` to the last bit, and a region whose boundary
     * mixes segments, arcs and elliptic arcs has an exact area just as one of segments and arcs does.
     */
    fun doubleSignedArea(arc: EllipticArc): Double {
        val e = arc.ellipse
        val sw = sweep(arc)
        val p0 = pointAt(e, arc.startT) - e.center
        val p1 = pointAt(e, arc.endT) - e.center
        return e.a * e.b * sw + e.center.x * (p1.y - p0.y) - e.center.y * (p1.x - p0.x)
    }

    /** Twice the signed area of a whole ellipse traversed once (`2π·a·b`, signed by [ccw]). */
    fun doubleSignedArea(
        e: Ellipse,
        ccw: Boolean,
    ): Double = (if (ccw) 1.0 else -1.0) * 2.0 * PI * e.a * e.b

    // ---- length: numeric, to a stated tolerance (OP-15) ----

    /** The speed `|dP/dt|` — the integrand of the elliptic arc length. */
    private fun speed(
        e: Ellipse,
        t: Double,
    ): Double {
        val s = e.a * sin(t)
        val c = e.b * cos(t)
        return sqrt(s * s + c * c)
    }

    /**
     * The **measured length** of [arc], in mm — computed numerically to [LENGTH_TOL_MM] and *flagged as
     * such* wherever it is shown (OP-15).
     *
     * This is the one honestly approximate reading of an elliptic arc: `∫ √(a²sin²t + b²cos²t) dt` is a
     * complete elliptic integral of the second kind and has no closed form in elementary functions. The
     * integrand is smooth and bounded, so adaptive Simpson converges fast and predictably — and
     * deterministically, which is what a golden and a byte-equal save need.
     */
    fun arcLength(arc: EllipticArc): Double {
        val e = arc.ellipse
        val sw = sweep(arc)
        if (abs(sw) < 1e-15) return 0.0
        val t0 = arc.startT
        val t1 = arc.startT + sw
        return abs(simpson(e, min(t0, t1), max(t0, t1)))
    }

    /** The whole circumference — the same integral over a full turn. */
    fun circumference(e: Ellipse): Double = abs(simpson(e, 0.0, 2.0 * PI))

    private fun simpson(
        e: Ellipse,
        lo: Double,
        hi: Double,
    ): Double {
        // A fixed outer split keeps the recursion shallow and the result independent of where the arc
        // happens to start: the integrand has two maxima and two minima per turn, so a panel that never
        // spans more than an eighth of a turn is already well inside Simpson's asymptotic regime.
        val panels = max(8, ceil(abs(hi - lo) / (PI / 4.0)).toInt())
        var total = 0.0
        for (i in 0 until panels) {
            val a = lo + (hi - lo) * i / panels
            val b = lo + (hi - lo) * (i + 1) / panels
            total += adaptive(e, a, b, simpsonOn(e, a, b), LENGTH_TOL_MM / panels, 24)
        }
        return total
    }

    private fun simpsonOn(
        e: Ellipse,
        a: Double,
        b: Double,
    ): Double = (b - a) / 6.0 * (speed(e, a) + 4.0 * speed(e, (a + b) / 2.0) + speed(e, b))

    private fun adaptive(
        e: Ellipse,
        a: Double,
        b: Double,
        whole: Double,
        tol: Double,
        depth: Int,
    ): Double {
        val m = (a + b) / 2.0
        val left = simpsonOn(e, a, m)
        val right = simpsonOn(e, m, b)
        val delta = left + right - whole
        if (depth <= 0 || abs(delta) <= 15.0 * tol) return left + right + delta / 15.0
        return adaptive(e, a, m, left, tol / 2.0, depth - 1) + adaptive(e, m, b, right, tol / 2.0, depth - 1)
    }

    /**
     * The parametric angle at signed arc **distance** [s] from [from] along [e] — the sampled
     * arc-length→parameter map, and OP-15's approximated class stated where it belongs.
     *
     * Exactly the Bézier-leg bargain: the *map* is numeric (monotone bisection on [arcLength]), and the
     * point it lands on is then the curve's own, exact. So equal-distance spacing along an ellipse is
     * approximate in *where* the marks fall and exact in *that they are on the curve*.
     */
    fun paramAtDistance(
        e: Ellipse,
        from: Double,
        s: Double,
    ): Double {
        if (abs(s) < 1e-15) return from
        val dir = if (s >= 0.0) 1.0 else -1.0
        var lo = 0.0
        var hi = 2.0 * PI
        val target = abs(s)
        repeat(80) {
            val mid = 0.5 * (lo + hi)
            val len = arcLength(EllipticArc(e, from, from + dir * mid, dir >= 0.0))
            if (len < target) lo = mid else hi = mid
        }
        return from + dir * 0.5 * (lo + hi)
    }

    // ---- transforms ----

    /**
     * An ellipse under an affine map. Built through its two **conjugate semi-diameters** (the images of
     * the axes), which is exact for any affine map at all rather than only for a similarity: an affine
     * image of an ellipse is an ellipse, and [fromConjugate] recovers its axes in closed form.
     *
     * A reflection turns the frame the other way round, which is why the [ccw] of a whole-ellipse boundary
     * piece flips with `det < 0` exactly as a circle's does (OP-14).
     */
    fun transform(
        e: Ellipse,
        t: Affine,
    ): Ellipse {
        val c = cos(e.rotation)
        val s = sin(e.rotation)
        val ua = t.linear(Vec2(e.a * c, e.a * s))
        val vb = t.linear(Vec2(-e.b * s, e.b * c))
        return fromConjugate(t.apply(e.center), ua, vb)
    }

    /**
     * An elliptic arc under an affine map. The **parametric interval is remapped**, not carried over: the
     * image ellipse has its own frame, so the arc's ends are re-read as parameters of it — which keeps
     * the arc the image of exactly the piece it was, endpoints and all.
     */
    fun transform(
        arc: EllipticArc,
        t: Affine,
    ): EllipticArc {
        val e = transform(arc.ellipse, t)
        val a = paramOf(e, t.apply(start(arc)))
        val b = paramOf(e, t.apply(end(arc)))
        val flip = t.det < 0
        return EllipticArc(e, a, b, if (flip) !arc.ccw else arc.ccw)
    }

    /**
     * The ellipse through `C + cos φ·[A] + sin φ·[B]` for two **conjugate semi-diameters** — Rytz's
     * construction, in closed form.
     *
     * Its use is everywhere an ellipse is *derived* rather than drawn: an affine image, and above all the
     * inclined section of a cylinder, which falls out of the geometry as two conjugate diameters and has
     * to be turned into axes to be a value. The result is [Ellipse.canonical] — major first — because
     * nobody picked a frame for it.
     */
    fun fromConjugate(
        center: Vec2,
        A: Vec2,
        B: Vec2,
    ): Ellipse {
        val aa = A.dot(A)
        val bb = B.dot(B)
        val ab = A.dot(B)
        // the extrema of |A cos φ + B sin φ|² are at tan 2φ = 2(A·B)/(|A|²−|B|²)
        val phi = if (abs(ab) < 1e-300 && abs(aa - bb) < 1e-300) 0.0 else 0.5 * atan2(2.0 * ab, aa - bb)
        val m = A * cos(phi) + B * sin(phi)
        val n = A * (-sin(phi)) + B * cos(phi)
        return if (m.length() >= n.length()) {
            Ellipse(center, m.length(), n.length(), norm(m.angle(), PI))
        } else {
            Ellipse(center, n.length(), m.length(), norm(n.angle(), PI))
        }
    }

    /**
     * The ellipse a general conic `A x² + B xy + C y² + D x + E y + F = 0` **is**, or null when it is not
     * one — the inverse of [implicit], and the door through which a surface with a quadratic equation
     * hands its plane section back as a value (OP-24, and OP-15's line moved outward once more).
     *
     * [Conics.cylinderSection] does not need this because a cylinder's section falls out already
     * parametrized, as two conjugate semi-diameters. A **cone's** does not: substituting a plane's frame
     * into `|P−apex|² = (1+tan²α)((P−apex)·axis)²` leaves a quadratic in `(x, y)` and nothing more, so the
     * reduction has to be done rather than read off. It is the textbook one and it is exact: translate to
     * the centre (the stationary point of the quadratic form, which exists exactly when `B² − 4AC ≠ 0`),
     * rotate by `½·atan2(B, A−C)` to kill the cross term, and read the two semi-axes off the diagonal.
     *
     * Null — never an approximation — when the conic is a parabola or a hyperbola (`B² − 4AC ≥ 0`, no
     * centre or the wrong signs), when it is imaginary or a single point, or when it degenerates to a pair
     * of lines. Those sections are real curves this drawing simply has no name for, and the caller's
     * business is to say so rather than to fit something (the *watertight or refused* doctrine's second
     * half: exact paths never degrade silently).
     */
    fun ellipseFromImplicit(
        A: Double,
        B: Double,
        C: Double,
        D: Double,
        E: Double,
        F: Double,
    ): Ellipse? {
        val disc = B * B - 4.0 * A * C
        // an ellipse is the negative-discriminant case, and the margin keeps a near-parabola out rather
        // than letting it come back as an ellipse the size of the sky
        val scale = max(max(abs(A), abs(B)), abs(C))
        if (scale <= 0.0 || disc >= -1e-12 * scale * scale) return null
        // the centre solves the gradient — [2A B; B 2C](x, y) = (−D, −E) — whose determinant is `−disc`
        val det = -disc
        val xc = (B * E - 2.0 * C * D) / det
        val yc = (B * D - 2.0 * A * E) / det
        if (!xc.isFinite() || !yc.isFinite()) return null
        val fc = (D * xc + E * yc) / 2.0 + F
        val theta = if (abs(B) < 1e-300 && abs(A - C) < 1e-300) 0.0 else 0.5 * atan2(B, A - C)
        val c = cos(theta)
        val s = sin(theta)
        val a2 = A * c * c + B * c * s + C * s * s
        val c2 = A * s * s - B * c * s + C * c * c
        if (abs(a2) <= 0.0 || abs(c2) <= 0.0) return null
        val ra = -fc / a2
        val rb = -fc / c2
        if (ra <= 0.0 || rb <= 0.0 || !ra.isFinite() || !rb.isFinite()) return null
        return Ellipse(Vec2(xc, yc), sqrt(ra), sqrt(rb), norm(theta, PI)).canonical()
    }

    // ---- intersections (OP-1): ordered solution sets ----

    /**
     * **Line ∩ ellipse**: the ordinary two-branch set, ordered **along the line's own direction** —
     * exactly [GeomMath.intersectLC]'s convention, so a `Select` sign means the same thing on a circle
     * and on an ellipse.
     *
     * A quadratic, solved in the ellipse's own frame where the curve is `x²/a² + y²/b² = 1`. A tangency
     * comes back as the single point it is, and a miss as the empty set (OP-3: the dependent `Select`
     * then says so in the caller's words).
     */
    fun intersectLE(
        line: Line,
        e: Ellipse,
    ): PointSet {
        if (e.a <= EPS || e.b <= EPS) return PointSet(emptyList())
        val c = cos(e.rotation)
        val s = sin(e.rotation)
        val dx = line.dir.x * c + line.dir.y * s
        val dy = -line.dir.x * s + line.dir.y * c
        val o = toLocal(e, line.origin)
        val ia = e.a * e.a
        val ib = e.b * e.b
        val qa = dx * dx / ia + dy * dy / ib
        if (qa <= EPS * EPS) return PointSet(emptyList())
        val qb = 2.0 * (o.x * dx / ia + o.y * dy / ib)
        val qc = o.x * o.x / ia + o.y * o.y / ib - 1.0
        val disc = qb * qb - 4.0 * qa * qc
        // the tangent case: a discriminant this small is a double root, and one point is the honest answer
        val scale = max(abs(qb * qb), abs(4.0 * qa * qc))
        if (disc < -1e-12 * max(scale, 1.0)) return PointSet(emptyList())
        if (disc <= 1e-12 * max(scale, 1.0)) return PointSet(listOf(line.origin + line.dir * (-qb / (2.0 * qa))))
        val r = sqrt(disc)
        val t1 = (-qb - r) / (2.0 * qa)
        val t2 = (-qb + r) / (2.0 * qa)
        return PointSet(listOf(line.origin + line.dir * t1, line.origin + line.dir * t2))
    }

    /** A circle as an ellipse — the coercion that makes circle ∩ ellipse one case of [intersect]. */
    fun ofCircle(c: Circle): Ellipse = Ellipse(c.center, c.radius, c.radius, 0.0)

    /**
     * **Ellipse ∩ ellipse** (and, through [ofCircle], circle ∩ ellipse): up to **four** solutions, as an
     * ordered set (OP-1).
     *
     * *The maths.* Substituting the first ellipse's own parametrization `P(t)` into the second's implicit
     * form gives a trigonometric polynomial of degree two,
     * `f(t) = k₀ + k₁cos t + k₂sin t + k₃cos 2t + k₄sin 2t`, and the half-angle substitution
     * `z = tan(t/2)` turns it into a **quartic in z** — which is where "four solutions" comes from. The
     * quartic loses its leading term exactly when `t = π` is a root (its leading coefficient *is* `f(π)`),
     * so that one parameter is tested directly and the degree drop is the ordinary case, not a guard.
     * Every candidate is then polished by Newton on `f` itself — the quartic is only ever an isolator —
     * and finally **verified geometrically**: a candidate whose point does not lie on the second ellipse
     * within [HIT_TOL_MM] is discarded, so a numerically spurious root cannot become a point of the
     * drawing. That is why the solver may be replaced without moving the answer.
     *
     * *The ordering convention*, which a stored branch index is meaningless without: **ascending
     * parametric angle on the first operand, folded into `[0, 2π)`**. It is a property of the operands
     * alone (not of the viewport, not of the click), it turns with the pair under a rigid motion — the
     * same stability [GeomMath.intersectCC]'s left/right rule has — and it needs no tie-breaking, since
     * two solutions at one parameter are one solution.
     *
     * Two conics that coincide have a whole curve in common rather than a solution set, and come back
     * **empty**; the caller's `Select` then reports that in its own words (OP-3).
     */
    fun intersect(
        e1: Ellipse,
        e2: Ellipse,
    ): PointSet {
        if (e1.a <= EPS || e1.b <= EPS || e2.a <= EPS || e2.b <= EPS) return PointSet(emptyList())
        val k = trigCoefficients(e1, e2)
        // `f ≡ 0` means every point of the first ellipse lies on the second, i.e. the two **coincide**: they
        // share a whole curve, which is not a solution set at all, so the honest answer is the empty one and
        // the caller's `Select` says so in its own words (OP-3). The coefficients are dimensionless and O(1)
        // for any pair that genuinely crosses, so this threshold separates "identically zero" from "small".
        if (k.scale <= 1e-9) return PointSet(emptyList())
        val k0 = k.k0
        val k1 = k.k1
        val k2 = k.k2
        val k3 = k.k3
        val k4 = k.k4
        val quartic =
            doubleArrayOf(
                k0 + k1 + k3,
                2.0 * k2 + 4.0 * k4,
                2.0 * k0 - 6.0 * k3,
                2.0 * k2 - 4.0 * k4,
                k0 - k1 + k3,
            )
        val candidates = ArrayList<Double>(5)
        for (z in Roots.real(quartic)) candidates.add(2.0 * kotlin.math.atan(z))
        // z → ∞, i.e. t = π: the one parameter the substitution cannot name
        candidates.add(PI)
        val found = ArrayList<Double>(4)
        for (seed in candidates) {
            val t = polish(k, seed) ?: continue
            val p = pointAt(e1, t)
            if ((nearestPoint(e2, p) - p).length() > HIT_TOL_MM) continue
            val n = norm(t)
            if (found.none { same(it, n) }) found.add(n)
        }
        found.sort()
        return PointSet(found.map { pointAt(e1, it) })
    }

    /** Two parametric angles that are the same angle, wrap included. */
    private fun same(
        x: Double,
        y: Double,
    ): Boolean {
        val d = abs(x - y) % (2.0 * PI)
        return d <= SAME_PARAM || d >= 2.0 * PI - SAME_PARAM
    }

    /** `f(t) = k₀ + k₁cos t + k₂sin t + k₃cos 2t + k₄sin 2t` — [e2]'s implicit form along [e1]. */
    private data class Trig(
        val k0: Double,
        val k1: Double,
        val k2: Double,
        val k3: Double,
        val k4: Double,
    ) {
        val scale: Double get() = maxOf(abs(k0), abs(k1), abs(k2), abs(k3), abs(k4))

        fun at(t: Double): Double = k0 + k1 * cos(t) + k2 * sin(t) + k3 * cos(2 * t) + k4 * sin(2 * t)

        fun slope(t: Double): Double = -k1 * sin(t) + k2 * cos(t) - 2 * k3 * sin(2 * t) + 2 * k4 * cos(2 * t)
    }

    private fun trigCoefficients(
        e1: Ellipse,
        e2: Ellipse,
    ): Trig {
        val c1 = cos(e1.rotation)
        val s1 = sin(e1.rotation)
        val vecA = Vec2(e1.a * c1, e1.a * s1)
        val vecB = Vec2(-e1.b * s1, e1.b * c1)
        val d = e1.center - e2.center
        val c2 = cos(e2.rotation)
        val s2 = sin(e2.rotation)
        val u2 = Vec2(c2, s2)
        val v2 = Vec2(-s2, c2)

        fun alpha(w: Vec2) = w.dot(u2) / e2.a

        fun beta(w: Vec2) = w.dot(v2) / e2.b
        val ad = alpha(d)
        val bd = beta(d)
        val aa = alpha(vecA)
        val ba = beta(vecA)
        val ab = alpha(vecB)
        val bb = beta(vecB)
        val konst = ad * ad + bd * bd - 1.0
        val p = aa * aa + ba * ba
        val q = ab * ab + bb * bb
        val m = 2.0 * (ad * aa + bd * ba)
        val n = 2.0 * (ad * ab + bd * bb)
        val kk = 2.0 * (aa * ab + ba * bb)
        return Trig(konst + (p + q) / 2.0, m, n, (p - q) / 2.0, kk / 2.0)
    }

    /** Newton on `f`, from [seed]; null when it does not settle on a zero. */
    private fun polish(
        k: Trig,
        seed: Double,
    ): Double? {
        var t = seed
        repeat(40) {
            val f = k.at(t)
            val g = k.slope(t)
            if (abs(g) < 1e-18) return@repeat
            val step = f / g
            if (!step.isFinite()) return null
            t -= step.coerceIn(-0.5, 0.5)
        }
        return if (t.isFinite()) t else null
    }

    // ---- the section of a cylinder: exact (OP-24, and OP-15's line moved outward) ----

    /**
     * The ellipse an **inclined plane** cuts from the cylinder of radius [r] round the axis through
     * [axisPoint] in direction [axis] — in the cutting plane's own (u, v), and **exact**.
     *
     * The derivation is one substitution and no fitting. A point of the cylinder is
     * `Q(φ) = C + r·cos φ·u + r·sin φ·v`, and the ruling through it meets the plane at
     * `Q(φ) − axis·dist(Q(φ))/(axis·n)`. Because `dist` is affine in `cos φ` and `sin φ`, the whole
     * expression is `C₀ + cos φ·A + sin φ·B` — an ellipse given by two conjugate semi-diameters, which
     * [fromConjugate] turns into centre, semi-axes and orientation. For the classic case (a right
     * cylinder cut at θ to its axis) it comes out as `r` and `r/cos θ` to the last bit.
     *
     * Null when the plane is parallel to the axis, where the section is a pair of rulings and not an
     * ellipse at all.
     */
    fun cylinderSection(
        axisPoint: Vec3,
        axis: Vec3,
        u: Vec3,
        v: Vec3,
        r: Double,
        cut: Plane3,
    ): Ellipse? {
        val k = axis.normalized().dot(cut.normal.normalized())
        if (abs(k) < 1e-12) return null
        val ax = axis.normalized()
        val n = cut.normal.normalized()
        val c0 = axisPoint - ax * (cut.distanceTo(axisPoint) / k)
        val da = u - ax * (n.dot(u) / k)
        val db = v - ax * (n.dot(v) / k)
        val centre = cut.toLocal(c0)
        val vecA = Vec2(da.dot(cut.u), da.dot(cut.v)) * r
        val vecB = Vec2(db.dot(cut.u), db.dot(cut.v)) * r
        return fromConjugate(centre, vecA, vecB)
    }
}
