package constructit.geom

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
 * **Two cases today, and the hierarchy is open on purpose.** [Seg3] and [Bezier3] are what the one source
 * that exists — a path through 3D points — produces. `Arc3` (a circle in an arbitrary plane) and `Helix3`
 * arrive with the steps that need them (OP-26's order of work puts the helix at step 3), because a case with
 * no producer is a case with no test: every consumer here (`sample`, the projection, the drawing, the
 * picking) would have to guess at behaviour nothing exercises. Adding one is adding a branch to the four
 * `when`s below, all of which are exhaustive so that a new case cannot be silently dropped.
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
     */
    fun sample(el: Curve3Element): List<Vec3> =
        when (el) {
            is Curve3Element.Seg3 -> listOf(el.start, el.end)
            is Curve3Element.Bezier3 ->
                (0..GeomMath.BEZIER_STEPS).map { bezierPointAt(el, it.toDouble() / GeomMath.BEZIER_STEPS) }
        }

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
     * **Exact, piece for piece, and not a resampling.** An orthographic projection onto a plane is an affine
     * map of space, and a cubic Bézier is affine-invariant — the image of the curve is the curve through the
     * mapped control points — which is the identical argument OP-15 already makes for transforming a 2D
     * Bézier. A segment's image is a segment for the same reason. So a path whose points all lie in one
     * space projects onto that space's plane as exactly the 2D chain those points describe, with no
     * tolerance anywhere in the statement.
     *
     * The plane's frame is orthonormal (`Construction.plane` orthonormalises every one it builds), so
     * [Plane3.toLocal] is two dot products and the projection is along the plane's own normal — which is
     * precisely what the 2D camera looks along.
     */
    fun projectedOnto(
        path: Path3,
        plane: Plane3,
    ): List<ProfileElement> =
        path.elements.map { el ->
            when (el) {
                is Curve3Element.Seg3 ->
                    ProfileElement.Seg(Segment(plane.toLocal(el.start), plane.toLocal(el.end)))
                is Curve3Element.Bezier3 ->
                    ProfileElement.BezierE(
                        Bezier(plane.toLocal(el.p0), plane.toLocal(el.p1), plane.toLocal(el.p2), plane.toLocal(el.p3)),
                    )
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
