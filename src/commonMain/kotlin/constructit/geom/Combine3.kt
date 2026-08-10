package constructit.geom

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **Combine two views** (OP-26, step 5): two planar curves drawn in two non-parallel sketch spaces, and the
 * one curve in space whose projection into each of those spaces *is* the curve drawn there.
 *
 * This is the drafting board's own construction, made parametric. A route is drawn twice — its **plan** in
 * one space and its **elevation** in another — and the run in space is read off the two drawings by the rule
 * every draughtsman knows: stand a projection line up from the plan, run one across from the elevation, and
 * the point is where the two cross. Nothing new is drawn to do it: both inputs are ordinary sketch curves in
 * ordinary spaces, which is why this step adds no editing surface at all.
 *
 * **The geometry, said twice.** Each planar curve, extruded perpendicular to its own space, is a surface; the
 * combined curve is where the two surfaces meet. Equivalently and more usefully: a point `X` belongs to the
 * run exactly when `X` projects onto space A's plane at a point of curve A *and* onto space B's plane at a
 * point of curve B. Since a projection moves a point only along its own plane's normal, `X` is the meeting of
 * the line through `a` along `n_A` with the line through `b` along `n_B` — the two projection lines, which is
 * the board's construction verbatim ([meetOf]).
 *
 * ### The correspondence rule, and what it excludes
 *
 * Two drawings do not say by themselves which point of one belongs to which point of the other, and that is
 * the crux of this step. The classical answer is the one taken here, because it is the only one that needs no
 * input of its own: **the two views are matched by the coordinate along the common direction** — the direction
 * `d = n_A × n_B` of the line where the two spaces meet, which on a drawing board is the *folding line*
 * between the plan and the elevation.
 *
 * It works because `d` is perpendicular to both normals, so a point's coordinate along it, `w = X · d`, is
 * unchanged by either projection: the plan and the elevation *agree* about `w`, which is why a draughtsman can
 * carry a distance from one to the other with dividers. Two consequences follow, and both are properties of
 * values, so both are node invalidity that heals (OP-3) rather than gesture refusals:
 *
 * - **Each view must be monotone in `w`.** A view that doubles back along the common direction offers two or
 *   more places for one projection line to cross, and the run is no longer one curve. It is refused **by
 *   name**, and refused rather than resolved by a persisted sign (OP-1's other option), because the count of
 *   candidates is not structural: a curve doubling back once has one, two or three partners depending on
 *   *where* along `w` you ask, so "branch 2" names nothing at the places where there is only one. A sign can
 *   only choose inside an ordered solution set of fixed size, and this is not one. The cure is a construction
 *   the drawing can already state — break the view at its turning point and combine each part — which is what
 *   the board does too.
 * - **The two `w` ranges must overlap.** Where only one view has been drawn there is nothing to combine, and
 *   the refusal states both ranges so the drawing says what is missing.
 *
 * ### What comes out, and where it is exact
 *
 * A [Path3] of [Curve3Element.Seg3] and [Curve3Element.Bezier3] pieces, **exact wherever the combination of
 * two pieces has a name in that vocabulary and honestly approximated with a stated error where it has not**
 * (OP-15's rule: never degrade silently).
 *
 * The dividing line is a fact about the maths rather than a convenience. `X` is a **linear** function of the
 * pair `(a, b)`; what is not linear is the *matching* — finding the parameter of one view at the `w` the other
 * has reached, which inverts a cubic or a trigonometric function. But when one of the two pieces is a
 * **straight** segment, that inversion is a division: the straight view's point is an affine function of `w`,
 * hence of the other view's point, and so the whole map from one view's piece to the run in space is
 * **affine**. Affine maps carry control points to control points, so:
 *
 * - **segment × segment → an exact [Curve3Element.Seg3]** — the case a straight run in the plan and a
 *   constant grade in the elevation state, and the answer is exact to the last bit;
 * - **cubic × segment → an exact [Curve3Element.Bezier3]**, the four control points mapped one for one;
 * - **everything else is fitted**, because it has no name here: two curved views meet in a curve of no
 *   standard kind at all, and even an arc against a straight elevation combines into an *elliptic* arc in
 *   space, for which this vocabulary has no case (`Arc3` has no producer — see [Curve3Element]).
 *
 * The fit is a chain of cubic Béziers, each one the **Hermite** through the exact positions and exact tangents
 * at its ends, halved until the fitted curve is within [FIT_TOL_MM] of the exact one at the midpoint of every
 * span — the standard flatness test, driven by a deterministic bisection. **What that error means is stated in
 * the unit a run is wrong by**: [FIT_TOL_MM] is 1e-4 mm, and because an orthogonal projection never increases
 * a distance, the projection of the built curve into either parent space is within that same 1e-4 mm of the
 * curve drawn there. So the defining property of this operation holds to a tenth of a micron — two hundred
 * times finer than [GeomMath.TESS_TOL_MM], the tolerance any solid swept along the run is meshed at, so the
 * fit is never what a made part is wrong by. It is not tighter still only because the piece count is what pays
 * for it (the count grows as the fourth root, so every further decade costs about 1.8× the pieces).
 *
 * ### Parenting
 *
 * Every input is a node — both planes and both curves — so the run **rides both parents**: drag a point of the
 * plan and it follows, drag the elevation and it follows, re-anchor or tilt either *space* and it follows
 * that too. That is OP-26's parenting rule with nothing added: the value is world geometry, the construction
 * is parented, twice over.
 */
object Combine3 {
    /**
     * How far the fitted run may stand from the exact combined curve, in **millimetres** — the stated error
     * of this operation, and the only place in it that is not exact.
     *
     * See this object's note for why it is 1e-4 mm and for the argument that carries it onto the property that
     * matters: an orthogonal projection is 1-Lipschitz, so a run within this of the exact curve projects
     * within this of each view it was drawn from.
     */
    const val FIT_TOL_MM = 1e-4

    /**
     * How many times one span may be halved before the fit gives up — 2^8 = 256 pieces from a single pair of
     * drawn pieces, which no ordinary route comes near (a right-angle bend against a straight elevation is
     * met in eight).
     *
     * The cap is not a quality knob but the one place a genuine **cusp** shows itself: where *both* views turn
     * to run square to the common direction at the same `w`, the run in space really does come to a point, no
     * cubic chain approaches it, and the honest answer is a refusal that says so ([tooSharp]).
     */
    private const val MAX_DEPTH = 8

    /**
     * The run in space whose projection onto [planeA] is [viewA] and onto [planeB] is [viewB], or null with
     * the reason there is none.
     *
     * Each view is a chain of ordinary 2D boundary pieces **in its own plane's coordinates** — exactly what a
     * picked sketch curve is worth (`Construction.guidePieces`), so nothing about a curve has to be restated
     * to be used as a view. The result runs **the way the first view runs**: a run has a direction, a sweep's
     * frame and a station's distance both start at its beginning, and taking that from the first pick is the
     * one reading a user can predict.
     */
    fun combined(
        planeA: Plane3,
        viewA: List<ProfileElement>,
        planeB: Plane3,
        viewB: List<ProfileElement>,
    ): Pair<Path3?, String?> {
        if (viewA.isEmpty() || viewB.isEmpty()) return null to "a view with no curve in it states no run"
        val nA = planeA.normal.normalized()
        val nB = planeB.normal.normalized()
        val axis = nA.cross(nB)
        val sine = axis.length()
        if (sine <= Geom3.PARALLEL_EPS) {
            return null to
                "these two sketch spaces are parallel, so they have no common direction and there is nothing " +
                "to combine — the second view would have to be drawn in a space that meets the first one, " +
                "such as an elevation across the plan"
        }
        val d = axis.normalized()
        val k = nA.dot(nB)
        // 1 − k² is sin² of the angle between the normals; written as the square of the length already
        // computed, which is the better-conditioned of the two spellings at a shallow angle
        val denom = sine * sine

        val (a, whyA) = viewOf(planeA, viewA, d, "first")
        if (a == null) return null to whyA
        val (b, whyB) = viewOf(planeB, viewB, d, "second")
        if (b == null) return null to whyB

        val lo = max(a.wLo, b.wLo)
        val hi = min(a.wHi, b.wHi)
        if (hi <= lo + Vec3.EPS) {
            return null to
                "the two views do not describe the same run: along their common direction the first covers " +
                "${Frames3.mm(a.wLo)} to ${Frames3.mm(a.wHi)} mm and the second ${Frames3.mm(b.wLo)} to " +
                "${Frames3.mm(b.wHi)} mm, and those ranges do not overlap — move one of them so that they do"
        }

        val meet = Meeting(nA, nB, k, denom, d)
        val cuts = breakpoints(a, b, lo, hi)
        val out = ArrayList<List<Vec3>>()
        for (i in 0 until cuts.size - 1) {
            val wl = cuts[i]
            val wr = cuts[i + 1]
            val (pieces, why) = span(a, b, wl, wr, meet)
            if (pieces == null) return null to why
            out.addAll(pieces)
        }
        if (out.isEmpty()) return null to "the two views share too little of the common direction to make a run"
        val ordered = if (a.reversed) out.reversed().map { it.reversed() } else out
        return Path3(stitched(ordered)) to null
    }

    // ---- where the two projection lines cross ----

    /**
     * The two spaces' shared arithmetic: given a point [a] of the first view and a point [b] of the second at
     * the *same* coordinate along the common direction, where their projection lines meet.
     *
     * Writing `a − b` in the basis `(d, n_A, n_B)` — a basis, since `d` is perpendicular to two independent
     * normals — the `d` component is `(a − b)·d`, which the matching has already made **zero**; the other two
     * coefficients come out of a 2×2 solve whose determinant is `1 − (n_A·n_B)²`. The meeting point is then
     * `a` slid back along `n_A` by the first coefficient, and equally `b` slid along `n_B` by the second: the
     * same point, said from either side, which is why only one of the two spellings is written.
     *
     * It is **linear in the pair** `(a, b)`, and that is the fact the exact cases rest on.
     */
    private class Meeting(
        val nA: Vec3,
        val nB: Vec3,
        val k: Double,
        val denom: Double,
        val d: Vec3,
    ) {
        fun at(
            a: Vec3,
            b: Vec3,
        ): Vec3 {
            val g = a - b
            return a - nA * ((g.dot(nA) - k * g.dot(nB)) / denom)
        }
    }

    // ---- a view: one drawn curve, read as a function of the coordinate along the common direction ----

    /**
     * One piece of a view, parameterized over `t ∈ [0, 1]` in its **own plane's** coordinates.
     *
     * [controls] is what makes the exact cases reachable: two points for a straight piece and four for a cubic
     * — the kinds whose image under an affine map is a piece of the same kind — and null for an arc or a
     * conic, which have no case in [Curve3Element] and are therefore always fitted.
     */
    private class Piece(
        val point: (Double) -> Vec2,
        val deriv: (Double) -> Vec2,
        val controls: List<Vec2>?,
    ) {
        val straight: Boolean get() = controls?.size == 2
    }

    /**
     * A drawn view, normalized so that the coordinate along the common direction **increases** with the
     * parameter — [reversed] remembering whether that meant turning the drawing round, so the run can be
     * handed back running the way the first view was drawn.
     *
     * [g] is the common direction seen *in this plane's own 2D coordinates*, and it is a **unit** vector
     * because `d` lies in both planes by construction (it is perpendicular to both normals). So the whole of
     * the matching is one dot product: `w = plane.origin·d + p·g`. That is the folding line of the drawing
     * board, and `w` is the distance along it.
     */
    private class View(
        val plane: Plane3,
        val g: Vec2,
        val base: Double,
        val pieces: List<Piece>,
        /** The coordinate along the common direction at every piece boundary — strictly increasing. */
        val cuts: List<Double>,
        val reversed: Boolean,
    ) {
        val wLo: Double get() = cuts.first()
        val wHi: Double get() = cuts.last()

        fun wAt(
            i: Int,
            t: Double,
        ): Double = base + pieces[i].point(t).dot(g)

        fun slopeAt(
            i: Int,
            t: Double,
        ): Double {
            val v = pieces[i].deriv(t)
            val len = v.length()
            return if (len <= Vec2.EPS) 0.0 else abs(v.dot(g)) / len
        }

        fun worldAt(
            i: Int,
            t: Double,
        ): Vec3 = plane.toWorld(pieces[i].point(t))

        fun worldDeriv(
            i: Int,
            t: Double,
        ): Vec3 = dirOf(plane, pieces[i].deriv(t))

        /** Which piece owns [w], by the same half-open rule a station's piece lookup follows (OP-26). */
        fun pieceAt(w: Double): Int {
            for (i in pieces.indices) if (w < cuts[i + 1]) return i
            return pieces.size - 1
        }
    }

    /** A 2D direction in [plane]'s own frame, as the world vector it stands for. */
    private fun dirOf(
        plane: Plane3,
        v: Vec2,
    ): Vec3 = plane.u * v.x + plane.v * v.y

    /**
     * [pieces] as a [View], or null with the reason the drawing cannot be matched — [which] naming the view
     * so the message points at a drawing rather than at an argument.
     *
     * Three things are checked, and all three are the correspondence rule stated as conditions on values:
     * a piece that runs **square** to the common direction (it stands still in `w`, so no projection line
     * reaches it), a piece that **turns** somewhere inside itself, and a chain whose pieces disagree about
     * which way along the common direction the drawing goes. The turning points are found **exactly**, from
     * the closed form of each piece kind ([turningParams]), and not by sampling: a reversal narrow enough to
     * fall between two samples is exactly the case that would come out as a silently wrong curve.
     */
    private fun viewOf(
        plane: Plane3,
        elements: List<ProfileElement>,
        d: Vec3,
        which: String,
    ): Pair<View?, String?> {
        val g = Vec2(plane.u.dot(d), plane.v.dot(d))
        val base = plane.origin.dot(d)
        val pieces = ArrayList<Piece>(elements.size)
        for (e in elements) pieces.add(pieceOf(e))
        var sign = 0
        for ((i, e) in elements.withIndex()) {
            val turns = turningParams(e, g)
            if (turns.isNotEmpty()) {
                val t = turns.first()
                val w = base + pieces[i].point(t).dot(g)
                val before = pieces[i].deriv(max(0.0, t - 1e-6)).dot(g)
                val after = pieces[i].deriv(min(1.0, t + 1e-6)).dot(g)
                return null to
                    if (before * after < 0.0) {
                        "the $which view doubles back along the two spaces' common direction at " +
                            "${Frames3.mm(w)} mm, so a place on the other view answers to more than one place " +
                            "on it and the two drawings no longer describe one run — break the view there and " +
                            "combine each part"
                    } else {
                        "the $which view turns to run square to the two spaces' common direction at " +
                            "${Frames3.mm(w)} mm, where it states no position along that direction at all — " +
                            "break the view there and combine each part"
                    }
            }
            val dw = pieces[i].point(1.0).dot(g) - pieces[i].point(0.0).dot(g)
            if (abs(dw) <= Vec3.EPS) {
                return null to
                    "the $which view runs square to the two spaces' common direction, so every point of it " +
                    "stands at the same place along that direction and there is nothing for the other view " +
                    "to be matched against — draw it across the run instead"
            }
            val s = if (dw > 0.0) 1 else -1
            if (sign == 0) {
                sign = s
            } else if (s != sign) {
                return null to
                    "the $which view doubles back along the two spaces' common direction between its pieces, " +
                    "so a place on the other view answers to more than one place on it — break the view where " +
                    "it turns and combine each part"
            }
        }
        val ordered = if (sign < 0) pieces.reversed().map { reversedPiece(it) } else pieces
        val cuts = ArrayList<Double>(ordered.size + 1)
        cuts.add(base + ordered.first().point(0.0).dot(g))
        for (p in ordered) cuts.add(base + p.point(1.0).dot(g))
        // every piece advances the same way, but a chain whose pieces do not *meet* could still step
        // backwards between them — checked rather than assumed, since the whole matching below reads this
        // list as the run's own ordering
        for (i in 1 until cuts.size) {
            if (cuts[i] <= cuts[i - 1]) {
                return null to
                    "the $which view steps back along the two spaces' common direction between its pieces, " +
                    "so a place on the other view answers to more than one place on it — break the view " +
                    "where it turns and combine each part"
            }
        }
        return View(plane, g, base, ordered, cuts, sign < 0) to null
    }

    /** The same piece traversed the other way — `t ↦ 1 − t`, which is all that turning a drawing round is. */
    private fun reversedPiece(p: Piece): Piece =
        Piece({ t -> p.point(1.0 - t) }, { t -> -p.deriv(1.0 - t) }, p.controls?.reversed())

    /** One drawn boundary piece as a parameterized [Piece] — the six kinds a sketch curve can be. */
    private fun pieceOf(e: ProfileElement): Piece =
        when (e) {
            is ProfileElement.Seg -> {
                val a = e.segment.a
                val b = e.segment.b
                Piece({ t -> a + (b - a) * t }, { _ -> b - a }, listOf(a, b))
            }
            is ProfileElement.BezierE -> {
                val z = e.bezier
                Piece(
                    { t -> GeomMath.bezierPointAt(z, t) },
                    { t -> GeomMath.bezierTangentAt(z, t) },
                    listOf(z.p0, z.p1, z.p2, z.p3),
                )
            }
            is ProfileElement.ArcE -> arcPiece(e.arc.center, e.arc.radius, e.arc.startAngle, GeomMath.sweep(e.arc))
            is ProfileElement.CircleE ->
                arcPiece(e.circle.center, e.circle.radius, 0.0, if (e.ccw) 2.0 * PI else -2.0 * PI)
            is ProfileElement.EllipticArcE -> ellipsePiece(e.arc.ellipse, e.arc.startT, Conics.sweep(e.arc))
            is ProfileElement.EllipseE -> ellipsePiece(e.ellipse, 0.0, if (e.ccw) 2.0 * PI else -2.0 * PI)
            // the function itself, reparametrized onto `0..1` — exact in the point, and exact in the
            // derivative wherever the AST can state one. A curve with no statable derivative is refused
            // **before** it reaches here (`Combine3.unnamedCurve`, the session-69 predicate rule), so this
            // is never the place a missing tangent is discovered.
            is ProfileElement.FuncE -> {
                val c = e.curve
                Piece(
                    { t -> FuncCurves.pointAt(c, c.t0 + c.span * t) ?: Vec2(0.0, 0.0) },
                    { t -> (FuncCurves.tangentAt(c, c.t0 + c.span * t) ?: Vec2(0.0, 0.0)) * c.span },
                    null,
                )
            }
        }

    private fun arcPiece(
        c: Vec2,
        r: Double,
        start: Double,
        sweep: Double,
    ): Piece =
        Piece(
            { t -> c + Vec2(r * cos(start + sweep * t), r * sin(start + sweep * t)) },
            { t -> Vec2(-r * sweep * sin(start + sweep * t), r * sweep * cos(start + sweep * t)) },
            null,
        )

    private fun ellipsePiece(
        e: Ellipse,
        start: Double,
        sweep: Double,
    ): Piece =
        Piece(
            { t -> Conics.pointAt(e, start + sweep * t) },
            { t -> Conics.tangentAt(e, start + sweep * t) * sweep },
            null,
        )

    /**
     * Every parameter strictly inside a piece at which it stops advancing along [g] — the exact turning
     * points, from each kind's own closed form.
     *
     * A segment has none. A cubic's advance is a **quadratic** in the parameter, so its roots are the formula.
     * A circular piece stops advancing where its tangent is perpendicular to [g], which is where its radius is
     * parallel to it: two angles, half a turn apart. An elliptic one is the same statement read in the
     * ellipse's own frame, where the tangent is `(−a sin t, b cos t)`.
     */
    private fun turningParams(
        e: ProfileElement,
        g: Vec2,
    ): List<Double> =
        when (e) {
            is ProfileElement.Seg -> emptyList()
            is ProfileElement.BezierE -> {
                val z = e.bezier
                val c0 = (z.p1 - z.p0).dot(g)
                val c1 = (z.p2 - z.p1).dot(g)
                val c2 = (z.p3 - z.p2).dot(g)
                quadraticRootsIn01(c0 - 2.0 * c1 + c2, 2.0 * (c1 - c0), c0)
            }
            is ProfileElement.ArcE -> angleParams(atan2(g.y, g.x), e.arc.startAngle, GeomMath.sweep(e.arc))
            is ProfileElement.CircleE ->
                angleParams(atan2(g.y, g.x), 0.0, if (e.ccw) 2.0 * PI else -2.0 * PI)
            is ProfileElement.EllipticArcE ->
                angleParams(ellipseTurn(e.arc.ellipse, g), e.arc.startT, Conics.sweep(e.arc))
            is ProfileElement.EllipseE ->
                angleParams(ellipseTurn(e.ellipse, g), 0.0, if (e.ccw) 2.0 * PI else -2.0 * PI)
            // an arbitrary function's turning points have no closed form, so they are the sign changes of
            // its own derivative along the parametric grid it tessellates on — bracketed exactly where a
            // chord turns, which is the same seeding every function-curve root uses
            is ProfileElement.FuncE -> funcTurnParams(e.curve, g)
        }

    /** Where [c]'s advance along [g] changes sign, in `0..1` — the sampled bracket, bisected. */
    private fun funcTurnParams(
        c: FuncCurve,
        g: Vec2,
    ): List<Double> {
        val steps = FuncCurves.RENDER_STEPS
        val f = { u: Double -> FuncCurves.tangentAt(c, c.t0 + c.span * u)?.dot(g) ?: 0.0 }
        val out = ArrayList<Double>()
        var prev = f(0.0)
        for (i in 1..steps) {
            val u = i.toDouble() / steps
            val cur = f(u)
            if (prev != 0.0 && cur != 0.0 && (prev > 0.0) != (cur > 0.0)) {
                var lo = (i - 1).toDouble() / steps
                var hi = u
                var flo = prev
                repeat(60) {
                    val m = 0.5 * (lo + hi)
                    val fm = f(m)
                    if ((fm > 0.0) == (flo > 0.0)) {
                        lo = m
                        flo = fm
                    } else {
                        hi = m
                    }
                }
                out.add(0.5 * (lo + hi))
            }
            prev = cur
        }
        return out
    }

    /** The parametric angle at which [e]'s tangent is perpendicular to [g] (the other is half a turn on). */
    private fun ellipseTurn(
        e: Ellipse,
        g: Vec2,
    ): Double = atan2(e.b * g.dot(e.vAxis), e.a * g.dot(e.uAxis))

    /**
     * The parameters in `(0, 1)` at which a piece swept from [start] through [sweep] stands at [base] or half
     * a turn from it — every whole turn of either, since a piece may sweep more than one.
     */
    private fun angleParams(
        base: Double,
        start: Double,
        sweep: Double,
    ): List<Double> {
        if (abs(sweep) <= Vec2.EPS) return emptyList()
        val out = ArrayList<Double>(2)
        val turns = kotlin.math.ceil(abs(sweep) / (2.0 * PI)).toInt() + 1
        for (half in 0..1) {
            for (m in -turns..turns) {
                val t = (base + half * PI + 2.0 * PI * m - start) / sweep
                if (t > 1e-9 && t < 1.0 - 1e-9) out.add(t)
            }
        }
        out.sort()
        return out
    }

    /** The roots of `A t² + B t + C` strictly inside `(0, 1)`, in order. */
    private fun quadraticRootsIn01(
        qa: Double,
        qb: Double,
        qc: Double,
    ): List<Double> {
        val out = ArrayList<Double>(2)
        if (abs(qa) <= 1e-15) {
            if (abs(qb) > 1e-15) out.add(-qc / qb)
        } else {
            val disc = qb * qb - 4.0 * qa * qc
            if (disc >= 0.0) {
                val r = sqrt(disc)
                out.add((-qb - r) / (2.0 * qa))
                out.add((-qb + r) / (2.0 * qa))
            }
        }
        return out.filter { it > 1e-9 && it < 1.0 - 1e-9 }.sorted()
    }

    // ---- the run, span by span ----

    /**
     * Where the run is cut into spans: the overlap's two ends, plus every piece boundary of either view that
     * falls inside it — so that within one span each view stands on **one** analytic piece and the exact cases
     * can be recognised at all.
     */
    private fun breakpoints(
        a: View,
        b: View,
        lo: Double,
        hi: Double,
    ): List<Double> {
        val eps = 1e-9 * (hi - lo)
        val all = ArrayList<Double>()
        all.add(lo)
        for (w in a.cuts + b.cuts) if (w > lo + eps && w < hi - eps) all.add(w)
        all.add(hi)
        all.sort()
        val out = ArrayList<Double>(all.size)
        for (w in all) if (out.isEmpty() || w > out.last() + eps) out.add(w)
        return out
    }

    /**
     * One span of the run, as control points — exact where one of the two pieces is straight, fitted where
     * neither is (see this object's note on where exactness stops).
     */
    private fun span(
        a: View,
        b: View,
        wl: Double,
        wr: Double,
        meet: Meeting,
    ): Pair<List<List<Vec3>>?, String?> {
        val wm = 0.5 * (wl + wr)
        val ia = a.pieceAt(wm)
        val ib = b.pieceAt(wm)
        val pa = a.pieces[ia]
        val pb = b.pieces[ib]
        // exact only where the *result* has a name here: the other piece straight (so the map is affine) and
        // this one a segment or a cubic (so its image is one). An arc's affine image is an elliptic arc in
        // space, and [Curve3Element] has no case for one — so it is fitted like everything else, rather than
        // being called a cubic it is not.
        if (pb.straight && pa.controls != null) {
            val line = straightLine(b, ib, meet.d)
            return exact(a, ia, wl, wr) { p -> meet.at(p, line(p.dot(meet.d))) } to null
        }
        if (pa.straight && pb.controls != null) {
            val line = straightLine(a, ia, meet.d)
            return exact(b, ib, wl, wr) { p -> meet.at(line(p.dot(meet.d)), p) } to null
        }
        return fitted(a, ia, b, ib, wl, wr, meet)
    }

    /**
     * The exact answer where the *other* view's piece is straight: [map] is then an **affine** map of space
     * (the other view's point is an affine function of the coordinate along the common direction, hence of
     * this view's point), and an affine map carries a segment to a segment and a cubic's control points to a
     * cubic's.
     *
     * So the whole span is one piece, computed by mapping two or four points and nothing else — no sampling,
     * no tolerance, and the projections of the result are the drawn curves themselves.
     */
    private fun exact(
        v: View,
        i: Int,
        wl: Double,
        wr: Double,
        map: (Vec3) -> Vec3,
    ): List<List<Vec3>> {
        val t0 = paramAtW(v, i, wl)
        val t1 = paramAtW(v, i, wr)
        val controls =
            if (v.pieces[i].straight) {
                listOf(v.worldAt(i, t0), v.worldAt(i, t1))
            } else {
                subCubic(v, i, t0, t1)
            }
        return listOf(controls.map { map(it) })
    }

    /**
     * A straight piece as the **whole line it lies on**, addressed by the coordinate along the common
     * direction: `w ↦ the world point of that line at w`.
     *
     * Extending the segment to its line is what makes the map affine rather than piecewise, and it costs
     * nothing: only the part of it inside the span is ever asked for, and the control points of a cubic —
     * which may stand well outside the curve — are exactly what an affine map has to be free to take.
     */
    private fun straightLine(
        v: View,
        i: Int,
        d: Vec3,
    ): (Double) -> Vec3 {
        val p0 = v.worldAt(i, 0.0)
        val p1 = v.worldAt(i, 1.0)
        val w0 = p0.dot(d)
        val w1 = p1.dot(d)
        val e = (p1 - p0) * (1.0 / (w1 - w0))
        return { w -> p0 + e * (w - w0) }
    }

    /**
     * The sub-curve of a cubic between two parameters, as its own four control points — the Hermite reading
     * of a Bézier, which is exact for a cubic and needs no de Casteljau bookkeeping.
     */
    private fun subCubic(
        v: View,
        i: Int,
        t0: Double,
        t1: Double,
    ): List<Vec3> {
        val h = (t1 - t0) / 3.0
        val a0 = v.worldAt(i, t0)
        val a1 = v.worldAt(i, t1)
        return listOf(a0, a0 + v.worldDeriv(i, t0) * h, a1 - v.worldDeriv(i, t1) * h, a1)
    }

    /**
     * The fitted answer where neither piece is straight: cubic Hermites through the exact points and tangents,
     * halved until the midpoint of every span is within [FIT_TOL_MM] of the exact run.
     *
     * **The span is halved in the parameter of whichever view runs closer to square with the common
     * direction**, and that choice is what keeps the fit honest on an ordinary drawing. A view about to turn
     * square advances slowly in `w`, so *its* own parameter is the well-behaved one and the other view's
     * partner parameter is a smooth function of it — while the opposite choice would need unboundedly many
     * pieces at a place where nothing is wrong with the run at all (a quarter bend in the plan, whose tangent
     * is square to the common direction at its end, is the everyday case).
     */
    private fun fitted(
        a: View,
        ia: Int,
        b: View,
        ib: Int,
        wl: Double,
        wr: Double,
        meet: Meeting,
    ): Pair<List<List<Vec3>>?, String?> {
        val slopeA = minOf(a.slopeAt(ia, paramAtW(a, ia, wl)), a.slopeAt(ia, paramAtW(a, ia, wr)))
        val slopeB = minOf(b.slopeAt(ib, paramAtW(b, ib, wl)), b.slopeAt(ib, paramAtW(b, ib, wr)))
        val masterIsA = slopeA <= slopeB
        val master = if (masterIsA) a else b
        val im = if (masterIsA) ia else ib
        val slave = if (masterIsA) b else a
        val ise = if (masterIsA) ib else ia
        val at = { t: Double ->
            val w = master.wAt(im, t)
            val s = paramAtW(slave, ise, w)
            val pm = master.worldAt(im, t)
            val ps = slave.worldAt(ise, s)
            if (masterIsA) meet.at(pm, ps) else meet.at(ps, pm)
        }
        val tangent = { t: Double ->
            val w = master.wAt(im, t)
            val s = paramAtW(slave, ise, w)
            val dm2 = master.pieces[im].deriv(t)
            val ds2 = slave.pieces[ise].deriv(s)
            val dws = ds2.dot(slave.g)
            if (abs(dws) <= Vec3.EPS * max(1.0, ds2.length())) {
                null
            } else {
                val dm = dirOf(master.plane, dm2)
                val ds = dirOf(slave.plane, ds2) * (dm2.dot(master.g) / dws)
                val g3 = if (masterIsA) dm - ds else ds - dm
                val da = if (masterIsA) dm else ds
                da - meet.nA * ((g3.dot(meet.nA) - meet.k * g3.dot(meet.nB)) / meet.denom)
            }
        }
        val t0 = paramAtW(master, im, wl)
        val t1 = paramAtW(master, im, wr)
        val out = ArrayList<List<Vec3>>()
        return if (refine(t0, t1, at, tangent, 0, out)) out to null else null to tooSharp(master.wAt(im, 0.5 * (t0 + t1)))
    }

    /** The refusal a cusp comes out as — see [MAX_DEPTH]. */
    private fun tooSharp(w: Double): String =
        "the two views cannot be matched into one run near ${Frames3.mm(w)} mm along their common direction: " +
            "the run turns too sharply there to be stated to ${Frames3.mm(FIT_TOL_MM * 1000.0)} µm, which is " +
            "what happens where both views turn to run square to that direction at the same place — the run " +
            "in space comes to a point there, and no curve passes through it"

    /**
     * One span, halved until it fits: the cubic through the exact ends and tangents, accepted when its own
     * midpoint stands within [FIT_TOL_MM] of the exact run's.
     *
     * False when the halving reached [MAX_DEPTH] without getting there, or where the exact run has no tangent
     * at all — both of which are the cusp, said once by [tooSharp].
     */
    private fun refine(
        t0: Double,
        t1: Double,
        at: (Double) -> Vec3,
        tangent: (Double) -> Vec3?,
        depth: Int,
        out: MutableList<List<Vec3>>,
    ): Boolean {
        val p0 = at(t0)
        val p1 = at(t1)
        val d0 = tangent(t0) ?: return false
        val d1 = tangent(t1) ?: return false
        val h = (t1 - t0) / 3.0
        val controls = listOf(p0, p0 + d0 * h, p1 - d1 * h, p1)
        val tm = 0.5 * (t0 + t1)
        val fitted = cubicAt(controls, 0.5)
        if ((fitted - at(tm)).length() <= FIT_TOL_MM) {
            out.add(controls)
            return true
        }
        if (depth >= MAX_DEPTH) return false
        return refine(t0, tm, at, tangent, depth + 1, out) && refine(tm, t1, at, tangent, depth + 1, out)
    }

    /** de Casteljau's weights over four control points — used to test a fit against the curve it stands for. */
    private fun cubicAt(
        c: List<Vec3>,
        t: Double,
    ): Vec3 {
        val u = 1.0 - t
        return c[0] * (u * u * u) + c[1] * (3.0 * u * u * t) + c[2] * (3.0 * u * t * t) + c[3] * (t * t * t)
    }

    /**
     * The parameter at which piece [i] of [v] stands at coordinate [w] along the common direction.
     *
     * **Exact for a straight piece** — it advances at a constant rate, so this is a division — and a
     * bisection-safeguarded Newton for every other kind, which is the same shape a station's arc-length
     * inversion takes (OP-26, step 4) and is safe for the same reason: the advance is strictly monotone by the
     * time this is called (see [viewOf]), so the bracket cannot be lost.
     */
    private fun paramAtW(
        v: View,
        i: Int,
        w: Double,
    ): Double {
        val w0 = v.wAt(i, 0.0)
        val w1 = v.wAt(i, 1.0)
        val span = w1 - w0
        if (span == 0.0) return 0.0
        var t = ((w - w0) / span).coerceIn(0.0, 1.0)
        if (v.pieces[i].straight) return t
        val tol = 1e-12 * max(1.0, abs(span))
        var lo = 0.0
        var hi = 1.0
        repeat(60) {
            val f = v.wAt(i, t) - w
            if (abs(f) <= tol) return t
            if (f > 0.0) hi = t else lo = t
            val slope = v.pieces[i].deriv(t).dot(v.g)
            val next = if (abs(slope) > Vec2.EPS) t - f / slope else Double.NaN
            t = if (next.isNaN() || next <= lo || next >= hi) 0.5 * (lo + hi) else next
        }
        return t
    }

    /**
     * The control-point lists as a chain of [Curve3Element]s, each piece **handed the previous one's end
     * point** so consecutive pieces carry the identical value there (which is what [Path3] means by a chain).
     *
     * Two spans meeting at a breakpoint compute that point from two different pieces' arithmetic and agree to
     * the last few bits rather than exactly; taking one of the two answers costs a femtometre and buys an
     * invariant, which is the same trade `Geom3.WELD_TOL` makes one dimension up.
     */
    private fun stitched(spans: List<List<Vec3>>): List<Curve3Element> {
        val out = ArrayList<Curve3Element>(spans.size)
        var prev: Vec3? = null
        for (c in spans) {
            val start = prev ?: c.first()
            val piece =
                if (c.size == 2) {
                    Curve3Element.Seg3(start, c[1])
                } else {
                    Curve3Element.Bezier3(start, c[1], c[2], c[3])
                }
            out.add(piece)
            prev = piece.end
        }
        return out
    }
}
