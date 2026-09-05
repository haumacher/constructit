package constructit.geom

import constructit.l10n.Msg
import constructit.l10n.Msgs
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * One curve of an intersection, and OP-15's class of it.
 *
 * The class is per curve rather than per answer because a section genuinely mixes the two: a bored plate cut
 * across its bore is four exact arcs beside a twisted band's chords, and saying "this whole answer is
 * approximate" would claim less than is true of the one and more than is true of the other.
 *
 * - neither flag — **exact**: every piece was a straight cut or a cubic, carried into space by an isometry,
 *   so the curve's numbers are the solid's own.
 * - [fitted] — a piece was a **conic** (a circle, an arc, an ellipse), for which [Curve3Element] has no case,
 *   so it is a chain of cubics within [Intersect3.FIT_TOL_MM] of the true curve.
 * - [sampled] — a piece was already **chords** where the section itself draws chords ([DrawnPiece]): a ruled
 *   face's cut, a mesh body's. Its error is the section's own tessellation ([GeomMath.TESS_TOL_MM]) and is
 *   not this step's to state or to improve.
 */
data class IntersectionCurve(
    val path: Path3,
    val fitted: Boolean,
    val sampled: Boolean,
) {
    /** How this curve is spoken of in a status line — the honesty line, in one phrase. */
    val exactnessWord: Msg
        get() =
            when {
                sampled -> Msgs.refusalIntersectChordsSectionOwnTessellationMm(mm = Frames3.mm(GeomMath.TESS_TOL_MM))
                fitted -> Msgs.refusalIntersectFittedM(mm = Frames3.mm(Intersect3.FIT_TOL_MM * 1000.0))
                else -> Msgs.wordExactnessExact()
            }
}

/**
 * The **ordered solution set** of an intersection: OP-1's `PointSet` one dimension up, and read the same way
 * — an ordered set plus a separate `Select`, never a list whose members are addressed by what they happen to
 * look like.
 *
 * The ordering is [Intersect3]'s and is stated there. How many members there are is a **value**: a plane
 * sliding along a bent bar cuts it once, then twice, then once again. So an index past the end is node
 * invalidity with a reason that heals (OP-3), exactly as branch 3 of a two-solution quartic is.
 */
data class Path3Set(val curves: List<IntersectionCurve>)

/**
 * **Intersection curves** (OP-26, step 6): the curve where a plane meets a solid, promoted from the existing
 * section machinery into a first-class [Path3].
 *
 * The section already exists — [Section3.sectionOf] computes plane ∩ solid, exactly where the vocabulary has
 * a name for the cut and in chords where it does not. What is added here is only the two things that turn a
 * *drawing in a plane* into a *curve in space*: the pieces are **chained** into connected runs, and each run
 * is **lifted** through the plane's own frame. Nothing is re-derived, so there are not two answers to the
 * question "where does this plane cut this solid" — there is one, read twice.
 *
 * **The chaining is a fact about the solid, not a guess.** The pieces are the cut of one body's boundary, so
 * two of them share an endpoint exactly when the faces they came from share an edge the plane crosses; the
 * two faces compute that crossing independently and agree to floating-point noise. The match is therefore
 * made within [GeomMath.JOIN_TOL], which is the tolerance the section's own ring assembly already uses, and
 * where three or more pieces meet at one point the continuation with the **lowest index in the section's own
 * (structural) piece order** is taken — deterministic, and stated rather than left to a hash order.
 *
 * **The ordering rule, and why it is stable.** The curves come back ordered by their **lowest point in the
 * cutting plane's own coordinates** — smallest `v`, then smallest `u`, and finally the section's own piece
 * order as a last tie-break. Three things recommend it over the obvious alternative of ordering by which
 * structural face each run touches:
 * - it is a property of the **geometry** alone, so the mesh route — where there is no structure at all — is
 *   ordered by the same rule as the analytic one, and one question keeps one answer;
 * - it is **continuous** in the parameters. Each run's lowest point moves continuously as the drawing moves,
 *   so the order can only change where two runs have the *same* lowest point — a genuine tie, which is
 *   precisely the class of degeneracy OP-1's own rules (the side of the directed centre line, the ascending
 *   parametric angle) are continuous everywhere except at;
 * - it is stated in the **plane's** frame, which is where this set is parented anyway: the curves are drawn
 *   in that plane's coordinates and picked there, so the rule turns with the construction rather than with
 *   the viewport.
 * The extremes are computed **exactly per piece kind** — a segment's endpoints, an arc's lowest point where
 * the arc contains it, an ellipse's stationary parameter, a cubic's from the roots of its derivative — never
 * from a tessellation, so the order does not depend on how finely anything is drawn.
 *
 * **Each run is canonical in itself**, for the same reason: a closed run is oriented counter-clockwise in the
 * plane's coordinates (OP-14's normalisation) and rotated to begin at its lowest corner (the translation-
 * invariant rule [Section3]'s own face sections already use); an open run is traversed from whichever end is
 * lower. So the same geometry gives the same curve bit for bit, whichever face the section happened to list
 * first.
 *
 * **Closure is read off the operands here, and that is not a breach of [Path3]'s structural rule.** For a
 * *constructed* curve `closed` is a claim the user made and must not drift; for a **derived** one — OP-26's
 * second provenance — whether the cut comes back to itself is a fact about the solid and the plane, read off
 * the very same geometry that decides *how many* curves there are. The two are told apart by provenance,
 * which the design already distinguishes, not by a flag.
 */
object Intersect3 {
    /**
     * How close a fitted conic piece is driven to the true curve, in millimetres — **1e-4 mm**, deliberately
     * the same number [Combine3.FIT_TOL_MM] states, so OP-26 has one fitting tolerance rather than two.
     *
     * The claim it supports is stronger here than in step 5, and it is worth one line: the map from the
     * cutting plane's (u, v) into the world is an **isometry** (the frame is orthonormal), so a fit that is
     * within this in the plane is within *exactly* this in space — no Lipschitz argument needed, no factor
     * anywhere. A tenth of a micron is two hundred times finer than [GeomMath.TESS_TOL_MM], so the fit is
     * never what a made part is wrong by.
     */
    const val FIT_TOL_MM = Combine3.FIT_TOL_MM

    /** How many fixed samples per fitted span the error is measured at — deterministic, never adaptive. */
    private const val FIT_SAMPLES = 8

    /** The most a conic's parameter range may be halved before the fit gives up (it never has to). */
    private const val MAX_SPANS = 1 shl 12

    /**
     * The curves where [plane] meets the solid whose section is [section] — the ordered set, in the world.
     *
     * [section] is the value of the very node a working plane's context is drawn from ([Section3.sectionOf]),
     * so what is on screen is what this promotes, piece for piece.
     */
    fun curvesOf(
        section: PlaneSection,
        plane: Plane3,
    ): Path3Set {
        val runs = chains(section.pieces)
        val curves =
            runs.map { run ->
                val elements = ArrayList<Curve3Element>()
                var fitted = false
                for (p in run.pieces) {
                    val made = lifted(p.piece, plane)
                    if (made.second) fitted = true
                    elements.addAll(made.first)
                }
                Triple(
                    IntersectionCurve(Path3(elements, run.closed), fitted, run.pieces.any { it.approximated }),
                    run.lowest,
                    run.order,
                )
            }
        val ordered =
            curves.sortedWith(
                compareBy({ it.second.y }, { it.second.x }, { it.third }),
            )
        return Path3Set(ordered.map { it.first })
    }

    /**
     * **A drawing read as the run it already is** (OP-26, step 1's missing source — *the lift*): the chain
     * [pieces], drawn in [plane]'s own (u, v), as the curve in space it describes there.
     *
     * OP-26 gave a curve in space seven sources — through points, a helix, two views combined, a section, a
     * connection, a projection onto a face, an imported wireframe — and left out the one that needs no new
     * geometry at all: **the curve already drawn, lying in the plane it was drawn in**. A plan outline *is* a
     * route round a building; a filleted profile *is* the path a bead runs on. This is that reading, and it is
     * one line of geometry because [lifted] already exists: the lift of a whole chain is the lift of each
     * piece, in order, and the exactness contract is the one [lifted] states — a segment, a cubic, a circle
     * and a circular arc exactly, an ellipse fitted to [FIT_TOL_MM] and **said** to be.
     *
     * **[closed] is structure and is stated by the caller** (OP-21's rule), never measured here: a boundary
     * that closes is one the drawing said closes — an outline, an area, a circle — and a chain whose last
     * piece happens to end where the first begins is not the same object as one the user closed. **Direction
     * and seam are the drawing's own**: the run starts where the chain's first piece starts and goes the way
     * the chain is stored, which for an outline is its own normalized (counter-clockwise) traversal and for a
     * hand-picked chain is the order of the clicks. So two lifts of one outline are the same run, and nothing
     * about where a sweep begins depends on where a click landed.
     *
     * The pieces are taken **in the order given**, already chained ([GeomMath.chainRun] is the caller's, since
     * only the caller knows whether a gap is a refusal or a value's business). What comes back is the run and
     * whether any piece of it had to be fitted, which is what a status line reports (`exactnessWord`).
     */
    fun liftedRun(
        pieces: List<ProfileElement>,
        plane: Plane3,
        closed: Boolean,
    ): Pair<Path3, Boolean> {
        val elements = ArrayList<Curve3Element>()
        var fitted = false
        for (p in pieces) {
            val made = lifted(p, plane)
            if (made.second) fitted = true
            elements.addAll(made.first)
        }
        return Path3(elements, closed) to fitted
    }

    // ---- chaining: the pieces of one cut, joined where the solid's own faces meet ----

    private class Run(val pieces: List<DrawnPiece>, val closed: Boolean, val lowest: Vec2, val order: Int)

    /** Whether two points in the cutting plane are the same crossing — see the note on [Intersect3]. */
    private fun same(
        a: Vec2,
        b: Vec2,
    ): Boolean = (a - b).length() <= GeomMath.JOIN_TOL

    /**
     * The pieces of a section, joined into maximal runs and each one canonicalised.
     *
     * Greedy from the lowest unused index, forwards then backwards, taking the lowest-indexed continuation at
     * a junction — one pass, no search, and a pure function of the list it is handed.
     */
    private fun chains(all: List<DrawnPiece>): List<Run> {
        val pieces = all.filter { reach(it.piece) > GeomMath.JOIN_TOL }
        val used = BooleanArray(pieces.size)
        val out = ArrayList<Run>()
        for (seed in pieces.indices) {
            if (used[seed]) continue
            val closedAlone = pieces[seed].piece.let { it is ProfileElement.CircleE || it is ProfileElement.EllipseE }
            used[seed] = true
            val chain = ArrayDeque<DrawnPiece>()
            chain.add(pieces[seed])
            var order = seed
            var closed = closedAlone
            if (!closedAlone) {
                var head = GeomMath.startOf(pieces[seed].piece)
                var tail = GeomMath.endOf(pieces[seed].piece)
                // forwards from the tail, then backwards from the head — flipping a piece that hands over the
                // other way round, exactly as [GeomMath.chainLoop] does one dimension down
                while (true) {
                    val next = continuation(pieces, used, tail) ?: break
                    used[next.first] = true
                    order = min(order, next.first)
                    chain.addLast(DrawnPiece(next.second, pieces[next.first].approximated))
                    tail = GeomMath.endOf(next.second)
                    if (same(tail, head)) {
                        closed = true
                        break
                    }
                }
                while (!closed) {
                    val prev = continuation(pieces, used, head) ?: break
                    used[prev.first] = true
                    order = min(order, prev.first)
                    // [continuation] hands the piece back *starting* at the point asked for, so prepending it
                    // means turning it round: it then ends at the old head and begins at the new one
                    chain.addFirst(DrawnPiece(GeomMath.reverse(prev.second), pieces[prev.first].approximated))
                    head = GeomMath.endOf(prev.second)
                    if (same(head, tail)) {
                        closed = true
                        break
                    }
                }
            }
            out.add(canonical(chain.toList(), closed, order))
        }
        return out
    }

    /**
     * The lowest-indexed unused piece that hands over at [at], already turned so that it *starts* there —
     * null when the run ends.
     */
    private fun continuation(
        pieces: List<DrawnPiece>,
        used: BooleanArray,
        at: Vec2,
    ): Pair<Int, ProfileElement>? {
        for (i in pieces.indices) {
            if (used[i]) continue
            val e = pieces[i].piece
            if (e is ProfileElement.CircleE || e is ProfileElement.EllipseE) continue
            if (same(GeomMath.startOf(e), at)) return i to e
            if (same(GeomMath.endOf(e), at)) return i to GeomMath.reverse(e)
        }
        return null
    }

    /** One run, oriented and started by the rules stated on [Intersect3]. */
    private fun canonical(
        chain: List<DrawnPiece>,
        closed: Boolean,
        order: Int,
    ): Run {
        val lowest = chain.map { lowestOf(it.piece) }.minWithOrNull(compareBy({ it.y }, { it.x })) ?: Vec2(0.0, 0.0)
        if (!closed) {
            val a = GeomMath.startOf(chain.first().piece)
            val b = GeomMath.endOf(chain.last().piece)
            val flip = (b.y < a.y) || (b.y == a.y && b.x < a.x)
            val run = if (!flip) chain else chain.reversed().map { DrawnPiece(GeomMath.reverse(it.piece), it.approximated) }
            return Run(run, false, lowest, order)
        }
        val area = GeomMath.signedArea(Loop(chain.map { it.piece }))
        val ccw = if (area >= 0.0) chain else chain.reversed().map { DrawnPiece(GeomMath.reverse(it.piece), it.approximated) }
        if (ccw.size < 2) return Run(ccw, true, lowest, order)
        val starts = ccw.map { GeomMath.startOf(it.piece) }
        val loY = starts.minOf { it.y }
        // the same rule (and the same tolerance) [Section3.rotatedToFirstCorner] states: translation-
        // invariant, so moving a space's origin never renumbers what it cuts
        val best = ccw.indices.filter { starts[it].y <= loY + Geom3.WELD_TOL }.minByOrNull { starts[it].x } ?: 0
        return Run(List(ccw.size) { ccw[(best + it) % ccw.size] }, true, lowest, order)
    }

    // ---- the exact extremes the ordering is taken on ----

    /** The point of [e] that is lowest in (v, then u) — exact, per piece kind, never from a tessellation. */
    fun lowestOf(e: ProfileElement): Vec2 {
        val cands = ArrayList<Vec2>()
        when (e) {
            is ProfileElement.Seg -> {
                cands.add(e.segment.a)
                cands.add(e.segment.b)
            }
            is ProfileElement.ArcE -> {
                cands.add(GeomMath.arcStart(e.arc))
                cands.add(GeomMath.arcEnd(e.arc))
                val bottom = -kotlin.math.PI / 2.0
                if (GeomMath.arcContains(e.arc, bottom)) cands.add(e.arc.center + Vec2(0.0, -e.arc.radius))
            }
            is ProfileElement.CircleE -> cands.add(e.circle.center + Vec2(0.0, -e.circle.radius))
            is ProfileElement.EllipseE -> cands.addAll(ellipseExtremes(e.ellipse))
            is ProfileElement.EllipticArcE -> {
                cands.add(Conics.start(e.arc))
                cands.add(Conics.end(e.arc))
                for (t in ellipseExtremeParams(e.arc.ellipse)) {
                    if (Conics.contains(e.arc, t)) cands.add(Conics.pointAt(e.arc.ellipse, t))
                }
            }
            // a function's own extremes have no closed form, so the candidates are its tessellation's —
            // exact at every sample, and the samples are the ones every consumer draws and picks against
            is ProfileElement.FuncE -> cands.addAll(FuncCurves.sample(e.curve, FuncCurves.RENDER_STEPS))
            is ProfileElement.BezierE -> {
                val b = e.bezier
                cands.add(b.p0)
                cands.add(b.p3)
                // y'(t) = 0 on a cubic is a quadratic in t — the exact stationary points, no sampling
                val c0 = 3.0 * (b.p1.y - b.p0.y)
                val c1 = 6.0 * (b.p2.y - 2.0 * b.p1.y + b.p0.y)
                val c2 = 3.0 * (b.p3.y - 3.0 * b.p2.y + 3.0 * b.p1.y - b.p0.y)
                for (t in quadraticRootsIn01(c2, c1, c0)) cands.add(GeomMath.bezierPointAt(b, t))
            }
        }
        return cands.minWithOrNull(compareBy({ it.y }, { it.x })) ?: Vec2(0.0, 0.0)
    }

    /** The two parameters at which an ellipse's `v` is stationary — `dy/dt = 0`, solved in closed form. */
    private fun ellipseExtremeParams(e: Ellipse): List<Double> {
        // y(t) = cy + a·cos t·sin r + b·sin t·cos r  ⇒  y'(t) = −a·sin t·sin r + b·cos t·cos r
        val t0 = atan2(e.b * cos(e.rotation), e.a * sin(e.rotation))
        return listOf(Conics.norm(t0), Conics.norm(t0 + kotlin.math.PI))
    }

    private fun ellipseExtremes(e: Ellipse): List<Vec2> = ellipseExtremeParams(e).map { Conics.pointAt(e, it) }

    /** The real roots of `a·t² + b·t + c` in `(0, 1)` — the same guarded form [Combine3] uses. */
    private fun quadraticRootsIn01(
        a: Double,
        b: Double,
        c: Double,
    ): List<Double> {
        val out = ArrayList<Double>()
        if (abs(a) <= 1e-14) {
            if (abs(b) > 1e-14) out.add(-c / b)
        } else {
            val disc = b * b - 4.0 * a * c
            if (disc >= 0.0) {
                val s = kotlin.math.sqrt(disc)
                out.add((-b - s) / (2.0 * a))
                out.add((-b + s) / (2.0 * a))
            }
        }
        return out.filter { it > 0.0 && it < 1.0 }
    }

    /**
     * How far a piece reaches — an **upper bound** on its length, and only ever compared against
     * [GeomMath.JOIN_TOL] to drop a piece that is a point.
     *
     * A bound rather than the length itself because that is all the question needs and it is exact for every
     * kind: the control polygon bounds a Bézier, the arc's own sweep bounds an arc, and each of them is zero
     * exactly when the piece is degenerate.
     */
    private fun reach(e: ProfileElement): Double =
        when (e) {
            is ProfileElement.Seg -> (e.segment.b - e.segment.a).length()
            is ProfileElement.ArcE -> abs(GeomMath.sweep(e.arc)) * e.arc.radius
            is ProfileElement.CircleE -> 2.0 * kotlin.math.PI * e.circle.radius
            is ProfileElement.EllipseE -> 2.0 * kotlin.math.PI * e.ellipse.major
            is ProfileElement.EllipticArcE -> abs(Conics.sweep(e.arc)) * e.arc.ellipse.major
            is ProfileElement.BezierE ->
                (e.bezier.p1 - e.bezier.p0).length() + (e.bezier.p2 - e.bezier.p1).length() + (e.bezier.p3 - e.bezier.p2).length()
            is ProfileElement.FuncE -> FuncCurves.arcLength(e.curve)
        }

    // ---- lifting one piece into space: exact where there is a case for it, fitted where there is not ----

    /**
     * One piece of the section as pieces of a curve in space, and whether it had to be fitted.
     *
     * **Exact for every case [Curve3Element] has a name for.** A segment maps to a [Curve3Element.Seg3]
     * because [Plane3.toWorld] is affine; a cubic maps to a [Curve3Element.Bezier3] with its four control
     * points carried one for one, because a Bézier is affine-invariant — the identical argument
     * [Curves3.projectedOnto] already makes in the other direction. A **circle and a circular arc** map to a
     * [Curve3Element.Arc3] with their centre, radius and angles untouched, because a plane's frame is
     * orthonormal and an isometry carries a circle to a circle. No tolerance appears in any of the three
     * statements.
     *
     * **The circular case was fitted until the lift asked for it**, and the earlier record said so in as many
     * words: *"adding an `Arc3` would make the circular half exact and is deliberately not done in this step —
     * a case is added with the producer that needs it"*. The lift (OP-26's step 1 completed) is that producer,
     * and it needs it whole: a rounded outline is arcs, a fillet's radius is a typed parameter, and the sweep's
     * self-intersection criterion reads the run's curvature station by station. So this reversal is the rule
     * working rather than an exception to it, and it pays out **here** — the section curve of a plane through a
     * cylinder is now exact too, for free and with the same words.
     *
     * **Fitted still for the ellipses, and that is a fact about the vocabulary rather than a shortcut.** A
     * plane through a cylinder cuts a circle *or* an ellipse; `Curve3Element` has no elliptic case, and there
     * is no degrading here that OP-15 would allow — calling an ellipse a cubic is exactly what it forbids. So
     * it is fitted, in the plane, to a **stated** [FIT_TOL_MM], and the isometry carries that number into
     * space unchanged.
     *
     * **Shared rather than copied** (OP-26, step 8): this is *the* lift from a plane's own (u, v) into space,
     * not the intersection's private one, so [Project3] — which projects a drawing onto a face and then has
     * exactly the same lifting to do — calls it rather than restating it. One lift, one exactness contract,
     * one fitting tolerance for the whole of OP-26.
     */
    fun lifted(
        e: ProfileElement,
        plane: Plane3,
    ): Pair<List<Curve3Element>, Boolean> =
        when (e) {
            is ProfileElement.Seg ->
                listOf<Curve3Element>(Curve3Element.Seg3(plane.toWorld(e.segment.a), plane.toWorld(e.segment.b))) to false
            is ProfileElement.BezierE ->
                listOf<Curve3Element>(
                    Curve3Element.Bezier3(
                        plane.toWorld(e.bezier.p0),
                        plane.toWorld(e.bezier.p1),
                        plane.toWorld(e.bezier.p2),
                        plane.toWorld(e.bezier.p3),
                    ),
                ) to false
            is ProfileElement.ArcE ->
                listOf<Curve3Element>(
                    Curve3Element.Arc3.about(
                        plane.toWorld(e.arc.center),
                        plane.u,
                        plane.v,
                        e.arc.radius,
                        e.arc.startAngle,
                        GeomMath.sweep(e.arc),
                    ),
                ) to false
            is ProfileElement.CircleE ->
                listOf<Curve3Element>(
                    Curve3Element.Arc3.about(
                        plane.toWorld(e.circle.center),
                        plane.u,
                        plane.v,
                        e.circle.radius,
                        0.0,
                        if (e.ccw) 2.0 * kotlin.math.PI else -2.0 * kotlin.math.PI,
                    ),
                ) to false
            is ProfileElement.EllipticArcE ->
                fit(e.arc.ellipse, e.arc.startT, e.arc.startT + Conics.sweep(e.arc), plane) to true
            is ProfileElement.EllipseE ->
                fit(e.ellipse, 0.0, if (e.ccw) 2.0 * kotlin.math.PI else -2.0 * kotlin.math.PI, plane) to true
            // `Curve3Element` has no name for an arbitrary function, and calling one a cubic is exactly what
            // OP-15 forbids — so it is **fitted**, to the stated [FIT_TOL_MM], and flagged as fitted
            is ProfileElement.FuncE -> fitPolyline(FuncCurves.sample(e.curve, FuncCurves.chordSteps(e.curve, FIT_TOL_MM)), plane) to true
        }

    /** A sampled curve as segments in space — the honest lift for a piece the vocabulary cannot name. */
    private fun fitPolyline(
        pts: List<Vec2>,
        plane: Plane3,
    ): List<Curve3Element> =
        (0 until maxOf(0, pts.size - 1)).map {
            Curve3Element.Seg3(plane.toWorld(pts[it]), plane.toWorld(pts[it + 1]))
        }

    /**
     * The arc of [e] from parameter [t0] to [t1] as a chain of cubics in space, within [FIT_TOL_MM].
     *
     * **The construction is the classical one and it is exact in its tangents**: on a span of parametric
     * width `Δ` the cubic leaves each end along the ellipse's own derivative there, scaled by
     * `k = (4/3)·tan(Δ/4)` — the value that makes the approximation exact at the span's midpoint for a
     * circle. It carries over to an ellipse with no change at all, because an ellipse *is* an affine image of
     * a circle in this parameter and a cubic Bézier's image under an affine map is the cubic through the
     * mapped control points. So one formula covers both, and the circle is not a special case of anything.
     *
     * **The span count is found by halving, and the error is measured rather than assumed.** Starting from
     * quarter-turn spans, the range is halved until every span stands within [FIT_TOL_MM] of the true curve
     * at [FIT_SAMPLES] fixed interior parameters — a deterministic bisection with a fixed sample count, so
     * the answer is the same bit on every machine and every reload (the reason [GeomMath.BEZIER_STEPS] is
     * fixed too). Measuring at *matched* parameters overstates the geometric distance, so the stated number
     * is an upper bound and not a hope. A quarter-turn of a 30 mm bore is met in four cubics.
     */
    private fun fit(
        e: Ellipse,
        t0: Double,
        t1: Double,
        plane: Plane3,
    ): List<Curve3Element> {
        val span = t1 - t0
        if (abs(span) <= 1e-12) return emptyList()
        var n = max(1, ceil(abs(span) / (kotlin.math.PI / 2.0)).toInt())
        while (n < MAX_SPANS && !within(e, t0, span, n)) n *= 2
        val out = ArrayList<Curve3Element>(n)
        for (i in 0 until n) {
            val a = t0 + span * i / n
            val b = t0 + span * (i + 1) / n
            val c = cubicOf(e, a, b)
            out.add(
                Curve3Element.Bezier3(
                    plane.toWorld(c[0]),
                    plane.toWorld(c[1]),
                    plane.toWorld(c[2]),
                    plane.toWorld(c[3]),
                ),
            )
        }
        return out
    }

    /** The four control points of the classical cubic through [e] from [a] to [b]. */
    private fun cubicOf(
        e: Ellipse,
        a: Double,
        b: Double,
    ): List<Vec2> {
        val k = (4.0 / 3.0) * tan((b - a) / 4.0)
        val pa = Conics.pointAt(e, a)
        val pb = Conics.pointAt(e, b)
        return listOf(pa, pa + Conics.tangentAt(e, a) * k, pb - Conics.tangentAt(e, b) * k, pb)
    }

    /** Whether [n] equal spans of [e] all stand within [FIT_TOL_MM] of the curve — the halving's test. */
    private fun within(
        e: Ellipse,
        t0: Double,
        span: Double,
        n: Int,
    ): Boolean {
        for (i in 0 until n) {
            val a = t0 + span * i / n
            val b = t0 + span * (i + 1) / n
            val c = cubicOf(e, a, b)
            val bez = Bezier(c[0], c[1], c[2], c[3])
            for (j in 1 until FIT_SAMPLES) {
                val s = j.toDouble() / FIT_SAMPLES
                val exact = Conics.pointAt(e, a + (b - a) * s)
                if ((GeomMath.bezierPointAt(bez, s) - exact).length() > FIT_TOL_MM) return false
            }
        }
        return true
    }
}
