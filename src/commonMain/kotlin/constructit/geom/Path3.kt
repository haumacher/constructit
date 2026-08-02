package constructit.geom

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Which way a [Curve3Element.Helix3] turns as it rises — **chirality**, and the one discrete choice a helix
 * carries.
 *
 * A helix's handedness is not a number that can drift: it is the same kind of thing an intersection's branch
 * is (OP-1), one dimension up, so it is **structural** — decided by the construction that builds the curve,
 * persisted by the tool id that made it, and never re-derived from the sign of anything. [turnSign] is how it
 * enters the formula and nothing reads it back to ask what handedness this is.
 *
 * [RIGHT] follows the right-hand rule about the axis: point a right thumb along the axis and the curve turns
 * the way the fingers curl while it rises. That is the ordinary screw, and it is why negative *pitch* is
 * refused rather than allowed — descending while turning right is the left-handed curve traced backwards, so
 * it would be a second way to say what this enum says (see [Curve3Element.Helix3]).
 */
enum class Handedness(val turnSign: Double) {
    RIGHT(1.0),
    LEFT(-1.0),
    ;

    /** The word a refusal and a status line use. */
    val word: String get() = if (this == RIGHT) "right-hand" else "left-hand"
}

/**
 * One analytic piece of a curve **in space** (OP-26) — the 3D twin of [ProfileElement], deliberately the
 * same shape one dimension up.
 *
 * **Analytic pieces, not "everything is a spline"**, for the two reasons OP-26 records. *Exactness*: a
 * bend that stays an arc has a radius a bender can make, and a helix that stays a helix has a pitch that
 * can be dimensioned — collapsing every piece into one sampled curve throws away the fact that it was
 * parameterized. *Consistency*: it is the rule `Feature3` already follows one layer up, keeping analytic
 * descriptions beside their meshes and dispatching by predicate rather than degrading silently.
 *
 * **Three cases today, and the hierarchy is open on purpose.** [Seg3] and [Bezier3] are what a path through
 * 3D points produces; [Helix3] arrived with OP-26's step 3, which is the rule this hierarchy is grown by — a
 * case with no producer is a case with no test, because every consumer (`sample`, the projection, the
 * drawing, the picking, the moving frame's sampling and its curvature) would have to guess at behaviour
 * nothing exercises. `Arc3` (a circle in an arbitrary plane) is still absent for exactly that reason. Adding
 * one is adding a branch to six exhaustive `when`s — [Curves3.sample], [Curves3.projectedOnto],
 * [Path3.movedBy], and [Frames3]'s step count, point and curvature — so that a new case cannot be silently
 * dropped.
 */
sealed interface Curve3Element {
    /** Where this piece begins — the chain's hand-over point from the piece before it. */
    val start: Vec3

    /** Where it ends. Consecutive pieces of a [Path3] carry the *identical* value here, by construction. */
    val end: Vec3

    /** A straight run between two points in space. */
    data class Seg3(override val start: Vec3, override val end: Vec3) : Curve3Element

    /**
     * A cubic Bézier in space, over four control points — the 3D twin of [Bezier] (OP-15), and what an
     * interpolating fit through points is expressed in (see [Curves3.smoothThrough]).
     *
     * Cubic rather than a general-degree spline for the reason the 2D one is: a cubic is the lowest degree
     * that can carry a stated tangent at both ends, which is all an interpolation needs, and it is the piece
     * every consumer of this engine — the renderer, the projection, a later sweep — already knows.
     */
    data class Bezier3(val p0: Vec3, val p1: Vec3, val p2: Vec3, val p3: Vec3) : Curve3Element {
        override val start: Vec3 get() = p0
        override val end: Vec3 get() = p3
    }

    /**
     * A **helix** about an axis (OP-26, step 3) — the first piece in this vocabulary that lies in **no**
     * plane, and the reason the moving frame has an honest test at all.
     *
     * **Closed form, from what a spring is actually specified by**: an [axis] through [origin], a [radius],
     * a [pitch] (the rise per turn), a number of [turns] that may be fractional, and a [hand]. Nothing here
     * is sampled and nothing is fitted — which is the whole point of keeping the pieces analytic (OP-26): a
     * helix that stays a helix has a pitch a spring-maker can order, its curvature is a closed form rather
     * than a derivative of a polynomial, and even its **arc length** is exact ([arcLength]), which a cubic's
     * never is.
     *
     * The parameter runs `t ∈ [0, 1]` over the whole curve, and at `t` the point is
     *
     * ```
     * origin + radius·(cos θ · u + sin θ · bi) + axis · (pitch · turns · t),   θ = hand.turnSign · 2π · turns · t
     * ```
     *
     * so [u] is where the curve *starts* — the phase, a real part of the geometry rather than a convention:
     * `start` is `origin + u·radius`, directly "beside" the axis point in the u direction. [u] is unit and
     * perpendicular to [axis], and [bi] = `axis × u` completes a right-handed frame, so `hand` alone decides
     * chirality and no sign anywhere else can quietly do it instead.
     *
     * **Every one of the three numbers is required to be positive, and that is a decision rather than
     * defensiveness** — each of the negatives is a *second way to say something the value already says*, and
     * two ways to say one thing is what makes a stored model stop being a normal form:
     * - a **negative pitch** is the other [Handedness]: descending while turning right is, read backwards,
     *   a left-handed helix rising, and chirality is invariant under reversing the traversal;
     * - a **negative turn count** is the same curve on the other side of [origin], which is said by pointing
     *   the axis the other way — an input the construction already has;
     * - a **zero pitch** is a circle traversed [turns] times, and a circle is drawn in a space;
     * - **zero turns** is a point.
     *
     * They are conditions on *values*, so they are refused where values live — inside the node's `compute`
     * ([constructit.dsl.Construction.helix]), by name and healing (OP-3) — and never by this class, which is
     * the value they produce.
     */
    data class Helix3(
        /** The point on the axis the curve starts level with — its `t = 0` height. */
        val origin: Vec3,
        /** Unit; the direction the curve rises along. */
        val axis: Vec3,
        /** Unit, perpendicular to [axis]; the phase — `start` is [origin] + [u]·[radius]. */
        val u: Vec3,
        val radius: Double,
        /** The rise per whole turn. */
        val pitch: Double,
        /** How many turns, possibly fractional. */
        val turns: Double,
        val hand: Handedness,
    ) : Curve3Element {
        /** The frame's second in-plane axis, so (u, bi, axis) is right-handed. */
        val bi: Vec3 get() = axis.cross(u)

        /** The **reduced pitch** `p / 2π` — the rise per radian, which is what every formula below is in. */
        val b: Double get() = pitch / (2.0 * PI)

        /** The signed total turn, in radians — [Handedness] is the only thing that puts a sign on it. */
        val sweepAngle: Double get() = hand.turnSign * 2.0 * PI * turns

        /** The total rise from [start] to [end], along [axis]. */
        val rise: Double get() = pitch * turns

        /**
         * The **curvature**, `r / (r² + b²)` — **constant** along the whole curve, and closed form.
         *
         * This is what makes the helix the piece OP-26 wanted here: the sweep's self-intersection criterion
         * is stated against the path's local radius of curvature, and on every other piece that number is a
         * derivative of a polynomial evaluated at a sample. Here it is a fact about the curve, the same at
         * every station, and it is what [Frames3] reads (see `curvatureAt`).
         */
        val curvature: Double get() = radius / (radius * radius + b * b)

        /**
         * The **arc length**, exactly `|sweepAngle| · sqrt(r² + b²)` — a helix travels at constant speed, so
         * its length is a multiplication rather than a numeric integral.
         *
         * Used here for the chord sampling and by the tests that check a spring's volume. It is deliberately
         * *not* offered as a measurable dimension: that was named as a cut in step 1 and stays one, because
         * offering it for a helix alone would mean a `MEASURABLE` slot that answers for one piece kind.
         */
        val arcLength: Double get() = kotlin.math.abs(sweepAngle) * sqrt(radius * radius + b * b)

        /** The point at parameter [t] — the closed form above, written out. */
        fun at(t: Double): Vec3 {
            val theta = sweepAngle * t
            return origin + u * (radius * cos(theta)) + bi * (radius * sin(theta)) + axis * (rise * t)
        }

        /**
         * The derivative at [t] with respect to the parameter — the tangent direction, unnormalized.
         *
         * Constant in magnitude (`|sweepAngle|·sqrt(r² + b²)`, which is [arcLength]), which is the same fact
         * as "a helix is parameterized proportionally to arc length".
         */
        fun tangentAt(t: Double): Vec3 {
            val theta = sweepAngle * t
            return u * (-radius * sin(theta) * sweepAngle) + bi * (radius * cos(theta) * sweepAngle) + axis * rise
        }

        override val start: Vec3 get() = at(0.0)
        override val end: Vec3 get() = at(1.0)

        companion object {
            /**
             * A helix about the axis through [origin] in direction [axisDir], phased by [phase] —
             * orthonormalizing as it goes, so a caller may hand over any two independent directions.
             *
             * The same courtesy `Construction.plane` does for a plane's frame: the invariants the formulas
             * above rest on ([axis] unit, [u] unit and perpendicular to it) are established **once**, here,
             * rather than being re-checked at every use.
             */
            fun about(
                origin: Vec3,
                axisDir: Vec3,
                phase: Vec3,
                radius: Double,
                pitch: Double,
                turns: Double,
                hand: Handedness,
            ): Helix3 {
                val a = axisDir.normalized()
                val p = phase - a * phase.dot(a)
                // a phase parallel to the axis says nothing about where the curve starts; the fixed X, Y, Z
                // order is the moving frame's own tie-break (Frames3.startReference), for the same reason —
                // a deterministic answer on every machine and every reload
                val u =
                    if (p.length() > Vec3.EPS) {
                        p.normalized()
                    } else {
                        val axisLeast = listOf(Vec3.X, Vec3.Y, Vec3.Z).minByOrNull { kotlin.math.abs(a.dot(it)) } ?: Vec3.X
                        (axisLeast - a * axisLeast.dot(a)).normalized()
                    }
                return Helix3(origin, a, u, radius, pitch, turns, hand)
            }
        }
    }
}

/**
 * A **curve in space** (OP-26): a piecewise chain of [Curve3Element]s, open or [closed].
 *
 * Deliberately the same shape [Profile] has one dimension down, and deliberately **not** plane-resident:
 * *a curve's value is world-space geometry, a curve's construction is always parented*. A path built through
 * points that all live in one sketch space is planar **structurally** — it needs no fitting and no tolerance
 * to say so — while a path through points on two spaces is not, and neither fact is recorded anywhere: both
 * are read off the construction.
 *
 * [closed] is **structure**, fixed when the node is built (OP-21's rule) and never derived from the values:
 * a chain whose last piece happens to end where the first begins is not the same object as one the user
 * said should close, and a value that drifts must not silently change what the drawing *is*. What the
 * closure means geometrically is exactly that the last piece hands over to the first.
 */
data class Path3(val elements: List<Curve3Element>, val closed: Boolean = false) {
    val isEmpty: Boolean get() = elements.isEmpty()

    /** Where the chain begins, or null when it has no pieces. */
    val start: Vec3? get() = elements.firstOrNull()?.start

    /** Where it ends — for a [closed] path, [start] again. */
    val end: Vec3? get() = elements.lastOrNull()?.end
}

/** Curves in space: the constructions that make a [Path3], and the samplers every consumer shares. */
object Curves3 {
    /**
     * A **polyline** through [points]: one [Curve3Element.Seg3] per consecutive pair, plus the closing
     * one when [closed].
     *
     * Exactly the polyline and nothing else — no smoothing, no fitting — so the everyday case (a route
     * stated by the points it passes) is the geometry the user drew, to the last bit.
     */
    fun straightThrough(
        points: List<Vec3>,
        closed: Boolean = false,
    ): List<Curve3Element> {
        val out = ArrayList<Curve3Element>(points.size)
        for (i in 0 until points.size - 1) out.add(Curve3Element.Seg3(points[i], points[i + 1]))
        if (closed && points.size >= 3) out.add(Curve3Element.Seg3(points.last(), points.first()))
        return out
    }

    /**
     * A **smooth interpolating** curve through [points]: one cubic Bézier per span, meeting the points
     * exactly and joining C1 at every interior one.
     *
     * **The scheme is uniform Catmull–Rom, written out as Bézier control points.** The tangent at an
     * interior point is the central difference of its neighbours, `m_i = (P_{i+1} − P_{i−1}) / 2`, and the
     * span from `P_i` to `P_{i+1}` is the cubic with controls `P_i + m_i/3` and `P_{i+1} − m_{i+1}/3`. Two
     * properties follow *by construction* rather than by assertion: the curve passes through every input
     * point (the end control points are the points), and it is C1 at every interior knot (both spans use the
     * same `m_i` there). It is one closed-form pass over the points — no iteration, no search, no ambiguity
     * — which is why OP-26 states that an interpolating spline is **not** a solver: what the no-solver
     * stance forbids is iterative search for a configuration satisfying asserted relations, and a
     * deterministic linear formula has none of those properties.
     *
     * **The end condition is the chord**, and it is a real decision rather than an incidental: for an open
     * path `m_0 = P_1 − P_0` and `m_{n−1} = P_{n−1} − P_{n−2}`, so the curve leaves its first point along
     * the first chord and arrives at its last along the last chord. Three reasons, in order of weight:
     * - It is **local**. Every control point depends only on a point and its immediate neighbours, so
     *   dragging one point changes the curve near it and nowhere else — which is what "edit a source,
     *   recompute the downstream cone" ought to *look* like. A natural spline (zero end curvature) is the
     *   textbook alternative and needs a tridiagonal solve over the whole chain, which is permitted by
     *   doctrine but makes every point's tangent a function of every other point: moving the first point
     *   would visibly move the curve at the far end.
     * - It is **stateable**: "it starts off along the line to the next point" is one sentence a user can
     *   predict. "Its curvature is zero at the ends" is a property nobody can see.
     * - It makes a two-point smooth path **exactly the straight segment** between them, which is the answer
     *   a degenerate case ought to give.
     *
     * Rejected with them: a *phantom* end point (reflect `P_1` about `P_0`), which invents a point that is
     * not in the drawing; and a zero end tangent, which flattens the curve into its first chord and reads as
     * a defect. Also not done, and named so it is not looked for: **centripetal** parameterization, the
     * standard cure for the overshoot uniform Catmull–Rom shows when consecutive spans differ wildly in
     * length. It is a change of one formula here, and it would make the tangent something other than the
     * plain central difference — a decision worth taking when a drawing asks for it, not before.
     *
     * For a [closed] path there are no ends: every point is interior and the central difference wraps, so
     * the curve is C1 at the seam like everywhere else.
     */
    fun smoothThrough(
        points: List<Vec3>,
        closed: Boolean = false,
    ): List<Curve3Element> {
        val n = points.size
        if (n < 2) return emptyList()
        if (closed && n < 3) return emptyList()
        val m = ArrayList<Vec3>(n)
        for (i in 0 until n) {
            m.add(
                when {
                    closed -> (points[(i + 1) % n] - points[(i - 1 + n) % n]) * 0.5
                    i == 0 -> points[1] - points[0]
                    i == n - 1 -> points[n - 1] - points[n - 2]
                    else -> (points[i + 1] - points[i - 1]) * 0.5
                },
            )
        }
        val spans = if (closed) n else n - 1
        val out = ArrayList<Curve3Element>(spans)
        for (i in 0 until spans) {
            val j = (i + 1) % n
            out.add(
                Curve3Element.Bezier3(
                    points[i],
                    points[i] + m[i] * (1.0 / 3.0),
                    points[j] - m[j] * (1.0 / 3.0),
                    points[j],
                ),
            )
        }
        return out
    }

    /** A point on a cubic Bézier in space at parameter [t] — de Casteljau's weights, written out. */
    fun bezierPointAt(
        b: Curve3Element.Bezier3,
        t: Double,
    ): Vec3 {
        val u = 1.0 - t
        return b.p0 * (u * u * u) + b.p1 * (3.0 * u * u * t) + b.p2 * (3.0 * u * t * t) + b.p3 * (t * t * t)
    }

    /** The tangent (derivative) of a cubic Bézier in space at [t] — what a C1 assertion is made against. */
    fun bezierTangentAt(
        b: Curve3Element.Bezier3,
        t: Double,
    ): Vec3 {
        val u = 1.0 - t
        return (b.p1 - b.p0) * (3.0 * u * u) + (b.p2 - b.p1) * (6.0 * u * t) + (b.p3 - b.p2) * (3.0 * t * t)
    }

    /**
     * One piece as world-space points, at the renderer's **fixed** step count — [GeomMath.BEZIER_STEPS],
     * the same number and the same reason as the 2D Bézier's: an adaptive count would make a golden depend
     * on curvature, and determinism is worth more than a few saved points.
     *
     * **Fixed *per turn* on a helix**, which is the same rule and not an exception to it. A cubic piece
     * covers a bounded amount of curve, so a fixed count per piece is a fixed density; one helix piece can
     * be twenty turns long, and twenty-four chords across twenty turns is not a drawing of anything. The
     * count is still a pure function of the piece's own numbers ([drawSteps]) and still curvature-free, so a
     * golden is as deterministic as before.
     */
    fun sample(el: Curve3Element): List<Vec3> =
        when (el) {
            is Curve3Element.Seg3 -> listOf(el.start, el.end)
            is Curve3Element.Bezier3 ->
                (0..GeomMath.BEZIER_STEPS).map { bezierPointAt(el, it.toDouble() / GeomMath.BEZIER_STEPS) }
            is Curve3Element.Helix3 -> {
                val n = drawSteps(el)
                (0..n).map { el.at(it.toDouble() / n) }
            }
        }

    /**
     * How many chords the *drawing* cuts a helix into: [GeomMath.BEZIER_STEPS] per whole turn, at least
     * one — the presentation count [sample] and [projectedOnto] share, so that what is on screen is exactly
     * what the pointer reaches (the picking rule of OP-26's step 1).
     *
     * Capped, because the count is a function of a *value* a user can type: a helix of ten thousand turns is
     * a legal number and a hundred thousand screen chords is not a drawing. The cap is a presentation limit
     * and nothing geometric depends on it — the mesh's own sampling is [Frames3]'s, in millimetres.
     */
    fun drawSteps(el: Curve3Element.Helix3): Int =
        max(1, minOf(1 shl 14, ceil(kotlin.math.abs(el.turns) * GeomMath.BEZIER_STEPS).toInt()))

    /**
     * The whole path as **one** world-space polyline, consecutive pieces sharing their hand-over point.
     *
     * What the 3D view draws and what a 3D pick is measured against — one sampling, so what is on screen is
     * what the pointer reaches.
     */
    fun polyline(path: Path3): List<Vec3> {
        val out = ArrayList<Vec3>()
        for (el in path.elements) {
            val pts = sample(el)
            for (i in (if (out.isEmpty()) 0 else 1) until pts.size) out.add(pts[i])
        }
        return out
    }

    /**
     * The path **projected onto [plane]**, in that plane's own (u, v) — as an ordinary chain of
     * [ProfileElement]s, which is what makes a curve in space visible and pickable in the 2D canvas
     * without inventing anything.
     *
     * **Exact, piece for piece, for a segment and a cubic.** An orthographic projection onto a plane is an
     * affine map of space, and a cubic Bézier is affine-invariant — the image of the curve is the curve
     * through the mapped control points — which is the identical argument OP-15 already makes for
     * transforming a 2D Bézier. A segment's image is a segment for the same reason. So a path whose points
     * all lie in one space projects onto that space's plane as exactly the 2D chain those points describe,
     * with no tolerance anywhere in the statement.
     *
     * **A helix is where that claim honestly stops, and OP-15 requires saying so rather than quietly
     * degrading.** Its image is `x(θ) = A cos θ + B sin θ + Cθ`, `y(θ)` likewise — a **trochoid**, and there
     * is no word for one in the 2D vocabulary: not a segment, not an arc, not an ellipse, not a cubic. (Only
     * in the one special case of looking straight down the axis, where `C = 0`, is it a conic — and
     * special-casing that would mean the plan of a helix changed *kind* as a datum was tilted, which is a
     * worse property than approximating uniformly.) So the plan shows the polyline the 3D view shows,
     * projected, at exactly [drawSteps]'s count — the two views sample the identical points, which is what
     * keeps picking a mirror of drawing. The chord error is that of a circle of the helix's radius at
     * [GeomMath.BEZIER_STEPS] steps per turn, `r·(1 − cos(π/24))` ≈ `r/1100`, and it is a *drawing* error:
     * nothing measured, meshed or exported reads this projection.
     *
     * The plane's frame is orthonormal (`Construction.plane` orthonormalises every one it builds), so
     * [Plane3.toLocal] is two dot products and the projection is along the plane's own normal — which is
     * precisely what the 2D camera looks along.
     */
    fun projectedOnto(
        path: Path3,
        plane: Plane3,
    ): List<ProfileElement> =
        path.elements.flatMap { el ->
            when (el) {
                is Curve3Element.Seg3 ->
                    listOf(ProfileElement.Seg(Segment(plane.toLocal(el.start), plane.toLocal(el.end))))
                is Curve3Element.Bezier3 ->
                    listOf(
                        ProfileElement.BezierE(
                            Bezier(plane.toLocal(el.p0), plane.toLocal(el.p1), plane.toLocal(el.p2), plane.toLocal(el.p3)),
                        ),
                    )
                is Curve3Element.Helix3 -> {
                    val pts = sample(el).map { plane.toLocal(it) }
                    (0 until pts.size - 1).map { ProfileElement.Seg(Segment(pts[it], pts[it + 1])) }
                }
            }
        }

    /**
     * Axis-aligned bounds of [path]'s drawn polyline, or null when it has no pieces.
     *
     * Measured on the sampled polyline rather than on the control polygon: a control point of an
     * interpolating cubic can stand well outside the curve, and what this sizes is the *view* — the ground
     * grid under a drawing and the frame a double-click reaches for.
     */
    fun bounds(path: Path3): Pair<Vec3, Vec3>? {
        var lo: Vec3? = null
        var hi: Vec3? = null
        for (p in polyline(path)) {
            val l = lo
            val h = hi
            lo = if (l == null) p else Vec3(minOf(l.x, p.x), minOf(l.y, p.y), minOf(l.z, p.z))
            hi = if (h == null) p else Vec3(maxOf(h.x, p.x), maxOf(h.y, p.y), maxOf(h.z, p.z))
        }
        val l = lo ?: return null
        return l to (hi ?: l)
    }
}
