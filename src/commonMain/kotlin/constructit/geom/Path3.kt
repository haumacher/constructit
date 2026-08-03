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
 * one is adding a branch to eight exhaustive `when`s — [Curves3.sample], [Curves3.projectedOnto],
 * [Curves3.derivativeAt], [Curves3.secondDerivativeAt], [Path3.movedBy], and [Frames3]'s step count, point
 * and curvature — so that a new case cannot be silently dropped.
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

    /**
     * The **direction one piece leaves in** at parameter [t] — unit, or null where the piece has none.
     *
     * What an *unbounded* directrix is stated by (OP-22's extension, step 2): a run that continues out of
     * each end **in the direction it was already going**, which is the identical rule a chain's two rays
     * follow one dimension down ([Chains.through]) and needs no input of its own. A cubic's derivative can
     * vanish at an end where two control points coincide, so the chord to the far control point is the
     * fallback — the direction the piece actually leaves in, and the same answer the curve has a hair later.
     */
    fun tangentAt(
        el: Curve3Element,
        t: Double,
    ): Vec3? {
        val v = derivativeAt(el, t)
        val d = if (v.length() > Vec3.EPS || el !is Curve3Element.Bezier3) v else el.p3 - el.p0
        return if (d.length() > Vec3.EPS) d.normalized() else null
    }

    /**
     * The **first derivative** of one piece at [t], with respect to that piece's own parameter — unnormalized,
     * and zero where the piece genuinely stands still.
     *
     * Split out of [tangentAt] because the two consumers want different things from it: a *direction* wants
     * the fallback [tangentAt] applies when a cubic's derivative vanishes at an end, while a **curvature**
     * ([curvatureVectorAt]) needs the derivative itself — it divides by its square, and substituting a chord
     * there would hand back a number that is not this curve's curvature at all.
     */
    fun derivativeAt(
        el: Curve3Element,
        t: Double,
    ): Vec3 =
        when (el) {
            is Curve3Element.Seg3 -> el.end - el.start
            is Curve3Element.Bezier3 -> bezierTangentAt(el, t)
            is Curve3Element.Helix3 -> el.tangentAt(t)
        }

    /**
     * The **second derivative** of one piece at [t], with respect to that piece's own parameter — closed form
     * for each of the three kinds, and what a curvature is read from (OP-26, step 7's G2 mode).
     *
     * A segment has none: its derivative is constant, so a straight run's curvature is exactly zero rather
     * than a small number. A cubic's is the linear interpolation of its two second differences, which is the
     * derivative of [bezierTangentAt] written out. A helix's is `−ω²r` times the radial direction — the
     * centripetal term of a curve travelling at constant speed, whose length divided by the squared speed is
     * exactly the constant [Curve3Element.Helix3.curvature] the sweep's refusal is stated against, so the two
     * cannot disagree.
     */
    fun secondDerivativeAt(
        el: Curve3Element,
        t: Double,
    ): Vec3 =
        when (el) {
            is Curve3Element.Seg3 -> Vec3.ZERO
            is Curve3Element.Bezier3 -> {
                val u = 1.0 - t
                (el.p2 - el.p1 * 2.0 + el.p0) * (6.0 * u) + (el.p3 - el.p2 * 2.0 + el.p1) * (6.0 * t)
            }
            is Curve3Element.Helix3 -> {
                val theta = el.sweepAngle * t
                val w = el.sweepAngle
                el.u * (-el.radius * cos(theta) * w * w) + el.bi * (-el.radius * sin(theta) * w * w)
            }
        }

    /**
     * The **curvature vector** `κ·N` of one piece at [t] — magnitude the curvature, direction the way the
     * curve is bending — or null where the piece has no well-defined one.
     *
     * `(r″ − (r″·û)û) / |r′|²`, which is the definition with the parameterization divided out: the component
     * of the acceleration that is not a change of speed, per unit squared speed. Two properties make it the
     * right thing for a **connect** to match (OP-26, step 7): it is independent of how the piece happens to be
     * parameterized, and it is independent of the **direction of travel** — reversing a curve leaves `κ·N`
     * alone — so joining at a curve's *start* needs no sign here, and only the tangent does.
     *
     * Null when the derivative vanishes: there the curve has no direction, so it has no normal either, and a
     * zero (or a chord's) answer would be a claim about a curvature nobody can read off the geometry.
     */
    fun curvatureVectorAt(
        el: Curve3Element,
        t: Double,
    ): Vec3? {
        val d1 = derivativeAt(el, t)
        val speed = d1.length()
        if (speed <= Vec3.EPS) return null
        val u = d1 * (1.0 / speed)
        val d2 = secondDerivativeAt(el, t)
        return (d2 - u * d2.dot(u)) * (1.0 / (speed * speed))
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

    // ---- arc length: the parameterization a station is stated in (OP-26, step 4) ----

    /**
     * How many subintervals a cubic's length integral is cut into before the Gauss rule is applied, and how
     * many nodes that rule has.
     *
     * **A composite 8-point Gauss–Legendre quadrature, over 16 fixed subintervals.** A cubic's speed
     * `|B'(t)|` is the square root of a quartic — smooth wherever the derivative does not vanish — so a rule
     * exact for polynomials of degree 15 on each of sixteen pieces is far past the accuracy anything here
     * reads. Measured on a 248 mm drawing-scale cubic: it agrees with a **200 000-chord polyline to 2e-9 mm**
     * (which is what [constructit.StationTest] asserts) and with a twenty-million-chord one to **5e-12 mm** —
     * so the residual at the tested count is the *polyline's* own truncation, a chord always undercutting its
     * arc, rather than anything this integral does. The counts are **fixed rather than adaptive** for the
     * reason the renderer's step count is fixed (OP-15): an adaptive count is a function of curvature, and a
     * station's position must be the same bit on every machine and every reload.
     */
    private const val QUAD_SPANS = 16

    private val GAUSS_X =
        doubleArrayOf(
            -0.9602898564975363,
            -0.7966664774136267,
            -0.5255324099163290,
            -0.1834346424956498,
            0.1834346424956498,
            0.5255324099163290,
            0.7966664774136267,
            0.9602898564975363,
        )

    private val GAUSS_W =
        doubleArrayOf(
            0.1012285362903763,
            0.2223810344533745,
            0.3137066458778873,
            0.3626837833783620,
            0.3626837833783620,
            0.3137066458778873,
            0.2223810344533745,
            0.1012285362903763,
        )

    /** How close to the wanted arc length the inversion below is driven, in millimetres. */
    private const val LENGTH_TOL_MM = 1e-9

    /**
     * The **arc length of one piece**, in millimetres — exact where the piece has a closed form, and a
     * deterministic numeric integral where it does not.
     *
     * Three cases and three different honesties, which is the whole reason the pieces are kept analytic
     * (OP-26): a [Curve3Element.Seg3] is a subtraction, a [Curve3Element.Helix3] travels at **constant
     * speed** so its length is a multiplication ([Curve3Element.Helix3.arcLength]), and only a
     * [Curve3Element.Bezier3] needs an integral — `∫|B'(t)|dt`, which has no elementary antiderivative for a
     * cubic. See [QUAD_SPANS] for what that integral is and what it costs.
     */
    fun arcLength(el: Curve3Element): Double = lengthTo(el, 1.0)

    /**
     * The arc length of [el] from its start up to parameter [t] — [arcLength] is this at `t = 1`, so the two
     * agree at the ends by construction rather than by tolerance.
     */
    fun lengthTo(
        el: Curve3Element,
        t: Double,
    ): Double {
        val u = t.coerceIn(0.0, 1.0)
        return when (el) {
            is Curve3Element.Seg3 -> (el.end - el.start).length() * u
            is Curve3Element.Helix3 -> el.arcLength * u
            is Curve3Element.Bezier3 -> {
                var sum = 0.0
                val h = u / QUAD_SPANS
                for (k in 0 until QUAD_SPANS) {
                    val mid = h * (k + 0.5)
                    for (i in GAUSS_X.indices) {
                        sum += GAUSS_W[i] * bezierTangentAt(el, mid + 0.5 * h * GAUSS_X[i]).length()
                    }
                }
                sum * 0.5 * h
            }
        }
    }

    /**
     * The parameter at which [el] has run [s] millimetres — the inverse of [lengthTo], clamped to the piece.
     *
     * **Exact for the two constant-speed pieces**: a segment and a helix are both parameterized
     * proportionally to arc length, so this is a division and nothing is iterated. A cubic is not, and its
     * inversion is a **bisection-safeguarded Newton** on [lengthTo] — monotone by construction (the speed is
     * non-negative), so the bracket can never be lost, and driven to [LENGTH_TOL_MM] or sixty-four steps,
     * whichever comes first. Both bounds are fixed, so the answer is a pure function of the inputs and a
     * reload lands on the same bit.
     */
    fun paramAtLength(
        el: Curve3Element,
        s: Double,
    ): Double {
        val total = arcLength(el)
        if (total <= 0.0) return 0.0
        val target = s.coerceIn(0.0, total)
        if (el is Curve3Element.Seg3 || el is Curve3Element.Helix3) return target / total
        var lo = 0.0
        var hi = 1.0
        var t = target / total
        repeat(64) {
            val f = lengthTo(el, t) - target
            if (kotlin.math.abs(f) <= LENGTH_TOL_MM) return t
            if (f > 0.0) hi = t else lo = t
            val speed = bezierSpeedAt(el, t)
            val newton = if (speed > Vec3.EPS) t - f / speed else Double.NaN
            t = if (newton.isNaN() || newton <= lo || newton >= hi) 0.5 * (lo + hi) else newton
        }
        return t
    }

    /** `|B'(t)|` — Newton's derivative of [lengthTo], which is the fundamental theorem written out. */
    private fun bezierSpeedAt(
        el: Curve3Element,
        t: Double,
    ): Double = if (el is Curve3Element.Bezier3) bezierTangentAt(el, t).length() else 0.0

    /**
     * The **total arc length of [path]**, the sum of its pieces' — the domain `[0, L]` a station's distance
     * is stated in (OP-26, step 4).
     *
     * A closed path is covered exactly **once**: the closing piece is one of the elements, and there is no
     * wrap, so the far end is the same place as the start and is reached by stating `L`.
     */
    fun length(path: Path3): Double = path.elements.sumOf { arcLength(it) }

    /**
     * Which piece of [path] the distance [s] belongs to, and at what parameter — or null when [s] is off
     * the run.
     *
     * **Half-open intervals, and that is the whole of the corner question** (OP-26, step 4): a distance in
     * `[pieceStart, pieceEnd)` belongs to that piece, and the path's far end belongs to the **last** piece.
     * A total function on `[0, L]` — no bisection, no two-tangent case at a vertex, and no tolerance
     * anywhere in the statement. A zero-length piece has an empty interval and is therefore never selected,
     * which is the same rule and not an exception to it.
     */
    fun pieceAtLength(
        path: Path3,
        s: Double,
    ): Pair<Int, Double>? {
        if (path.elements.isEmpty()) return null
        val lengths = path.elements.map { arcLength(it) }
        val total = lengths.sum()
        if (s < 0.0 || s > total) return null
        var start = 0.0
        for (i in path.elements.indices) {
            val end = start + lengths[i]
            if (s < end) return i to paramAtLength(path.elements[i], s - start)
            start = end
        }
        // s is the far end of the run: the last piece owns it, which is the half-open rule's one closure
        val last = path.elements.size - 1
        return last to 1.0
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

    // ---- the plane a *frozen* run lies in (OP-26, step 9: the sketch made from an imported wireframe) ----

    /**
     * How far a run's points may stand off one plane and still be called flat, **in millimetres**.
     *
     * This number exists for exactly one kind of curve and must not be read as a general tolerance: a
     * **constructed** path's planarity is a *fact of its construction* — points in one space are in one
     * plane, structurally, with nothing measured (OP-26's parenting rule, consequence 3). An **imported** run
     * has no construction: it is a list of numbers a file states, so the only way to ask whether it is flat is
     * to measure, and a measurement needs a limit.
     *
     * **Why 0.01 mm**, argued from both sides, because both of them decide it:
     * - *Above the file's own noise.* JT stores positions as `float`, so a point a CAD system authored exactly
     *   in a plane arrives rounded: half an ULP per coordinate, which is ≈1e-4 mm out of plane at a metre from
     *   the origin and ≈1e-3 mm at ten metres — the largest model anybody routes a wireframe through. A tighter
     *   limit would refuse flat sketches for being written down in single precision, which is a fact about the
     *   format and not about the geometry.
     * - *Below anything a drawing calls flat.* 0.01 mm is the tightest general tolerance a shop quotes (ISO
     *   2768-f is ±0.05 mm at 30 mm), so a run that stays inside it cannot be moved by transcribing it into a
     *   plane by an amount that part's own drawing distinguishes. And a run that is genuinely *in* space — a
     *   routed centreline, a spring, a hemmed edge — misses a plane by whole millimetres, so nothing that is
     *   really a curve in space slips through: the gap between the two cases is three orders of magnitude wide
     *   and this number sits in the middle of it.
     */
    const val FLAT_TOL_MM = 0.01

    /**
     * The plane [path] lies in, or the **named reason** it has none — the question OP-26's step 9 asks before
     * it will make a sketch out of an imported wireframe, and the only place in this engine where planarity is
     * *measured* rather than known.
     *
     * **Newell's normal, not a least-squares fit.** The area-weighted sum over the run's edges is a closed
     * form — one pass, no iteration, no eigenproblem — and it is *exact* for points that really are coplanar,
     * which is the case that has to be exact. A least-squares plane would answer the same thing for flat input
     * and differ only in how it apportions a residual that is about to be refused anyway, at the cost of a
     * solve whose answer would have to be bit-identical on every machine and every reload.
     *
     * Two refusals, and each names what is actually wrong:
     * - a run whose points sweep **no area** — a straight one, or one that doubles back on itself — lies in
     *   infinitely many planes, so there is no plane to pick and picking one would be inventing a rotation;
     * - a run that misses every plane by more than [tolMm], which is a curve in space and is said to be one,
     *   with the number it missed by.
     *
     * The frame is the run's own: the origin is where the run **starts**, the x axis is the way it leaves
     * that point, and the normal is Newell's — so the sketch that comes out of it is stated in the coordinates
     * the file's own geometry suggests rather than in a frame this function invented.
     */
    fun planeOfRun(
        path: Path3,
        tolMm: Double = FLAT_TOL_MM,
    ): Pair<Plane3?, String?> {
        val pts = polyline(path)
        if (pts.size < 3) {
            return null to "it is a single straight run, and a straight line lies in infinitely many planes — there is no one plane to sketch in"
        }
        var n = Vec3.ZERO
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            n += Vec3((a.y - b.y) * (a.z + b.z), (a.z - b.z) * (a.x + b.x), (a.x - b.x) * (a.y + b.y))
        }
        val (lo, hi) = bounds(path) ?: return null to "it has no points"
        val span = (hi - lo).length()
        // |n| is twice the area the run's points span, so this says "its area is no more than the tolerance
        // times its length" — i.e. it is as good as straight, whichever way it is turned
        if (n.length() <= 2.0 * tolMm * maxOf(span, 1.0)) {
            return null to "its points sweep no area — a straight run lies in infinitely many planes, so there is no one plane to sketch in"
        }
        val normal = n.normalized()
        var c = Vec3.ZERO
        for (p in pts) c += p
        val centroid = c * (1.0 / pts.size)
        var dev = 0.0
        for (p in pts) dev = maxOf(dev, kotlin.math.abs((p - centroid).dot(normal)))
        if (dev > tolMm) {
            return null to
                "it is not flat: its points stand up to ${Frames3.mm(dev)} mm off the best plane through them, " +
                "and the limit is ${Frames3.mm(tolMm)} mm — this is a curve in space, not a sketch"
        }
        val origin = pts[0] - normal * (pts[0] - centroid).dot(normal)
        val along = pts[1] - pts[0]
        val flat = along - normal * along.dot(normal)
        // a first chord along the normal cannot happen for a run this flat, but the fallback is the moving
        // frame's own tie-break (`Frames3.startReference`) rather than a guess: the least-aligned world axis
        val u =
            if (flat.length() > Vec3.EPS) {
                flat.normalized()
            } else {
                val axis = listOf(Vec3.X, Vec3.Y, Vec3.Z).minByOrNull { kotlin.math.abs(normal.dot(it)) } ?: Vec3.X
                (axis - normal * axis.dot(normal)).normalized()
            }
        return Plane3(origin, u, normal.cross(u)) to null
    }
}
