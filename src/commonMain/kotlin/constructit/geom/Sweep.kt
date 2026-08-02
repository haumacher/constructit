package constructit.geom

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The **profile a sweep carries along a path** (OP-26, step 2), read in the moving frame's own (x, y).
 *
 * Two cases and one reading. Both are an ordinary [Region] — the same value an extrude takes — so the mesh
 * below has one code path, holes and all: a pipe is a profile with a hole in it, not a second feature.
 * [Round] keeps its **radius** analytically rather than being turned into a region once and forgotten, for
 * the reason every other feature keeps its parameters (OP-9): a tube that stays a tube has a diameter a
 * fitter can order, and a later B-rep writer reads the feature, not the triangles.
 *
 * **The profile's own origin sits on the path.** That is a statement rather than a convenience: it makes an
 * eccentric run (a handrail whose section stands to one side of its route) an ordinary construction — draw
 * the section off the space's origin and it sweeps off the path by exactly that much — and it is the frame
 * the self-intersection criterion measures against, since what can fold through itself on a bend is the
 * profile's greatest **reach** from the path.
 */
sealed interface SweepProfile {
    /** This profile as an ordinary 2D region, in the moving frame's own coordinates. */
    val region: Region

    /** A circle of [radius] centred on the path — the tube, and what proves the frame. */
    data class Round(val radius: Double) : SweepProfile {
        override val region: Region
            get() = Region(Loop(listOf(ProfileElement.CircleE(Circle(Vec2(0.0, 0.0), radius)))), emptyList())
    }

    /** An arbitrary closed area, drawn in a sketch space and read in the frame's coordinates. */
    data class Section(override val region: Region) : SweepProfile
}

/**
 * One **station of the moving frame**: where the frame stands along the path, and how it is turned there.
 *
 * A value and a function of the path, deliberately *not* a sketch space — a frame at a parameter **is** one
 * (OP-26 says so, and OP-17's spaces will generalize onto it), but the space, its origin node and the tool
 * that states a distance are step 4 of the order of work and are not built here. What exists now is the
 * geometry they will read.
 *
 * [tangent] is the direction of the spine chord **arriving** at this station, [ref] the transported
 * reference direction perpendicular to it (with the roll and the twist already in it), and [mitre] the
 * normal of the plane the profile is actually read in: the bisector of the arriving and leaving chords, so
 * that a corner in the path comes out mitred rather than pinched. At a smooth station and at both ends of
 * an open path the bisector *is* the tangent, so one formula covers all three and no case is special.
 *
 * [curvature] is the path's own curvature here — read from the **analytic piece** this station was sampled
 * from, never from the chords, because a chord-based estimate of a straight run is rounding noise and the
 * self-intersection refusal is a claim about the curve.
 */
data class Frame3(
    /** Arc length from the path's start, along the sampled spine — what a refusal names a station by. */
    val s: Double,
    val at: Vec3,
    val tangent: Vec3,
    val ref: Vec3,
    val mitre: Vec3,
    val curvature: Double,
) {
    /** The frame's second in-plane axis: `tangent × ref`, so (ref, bi, tangent) is right-handed. */
    val bi: Vec3 get() = tangent.cross(ref)

    /**
     * Where the profile point [p] stands in the world at this station.
     *
     * The profile is laid out in the plane perpendicular to [tangent] and then **pushed along the tangent
     * onto the mitre plane** — which is the whole of the corner treatment. Where the path is smooth the
     * mitre plane *is* that plane and the push is zero; at a kink the push is exactly the trim two straight
     * tubes make of each other, so the two bands meeting here share this ring vertex for vertex.
     */
    fun place(p: Vec2): Vec3 {
        val q = ref * p.x + bi * p.y
        val push = -mitre.dot(q) / mitre.dot(tangent)
        return at + q + tangent * push
    }
}

/**
 * The **moving frame along a path** (OP-26): the stations, how long the path is, and — for a closed path —
 * how far the frame is from coming back to itself.
 */
class MovingFrame(
    val stations: List<Frame3>,
    val length: Double,
    val closed: Boolean,
    /**
     * The residual rotation about the tangent after one full loop of a [closed] path, in radians, reduced
     * to `(−π, π]` — the **holonomy plus the stated twist**. Zero for an open path, and exactly zero for a
     * planar closed one (see [Frames3.along]).
     */
    val seam: Double,
)

/**
 * **The moving frame — parallel transport, never Frenet** (OP-26).
 *
 * The rejection is the reason this object exists, so it is stated here rather than only in the design
 * record. The Frenet frame's normal points where the curve *bends*, which makes it **undefined on a
 * straight piece** (there is no bending, so the normal is 0/0) and makes it **flip through an inflection**
 * (the bending changes side, so the normal jumps by π). A sweep along a line–arc–line run — the commonest
 * shape a real conduit has — therefore tears exactly where a real tube is perfectly fine, and a sweep
 * through an S-bend turns its section inside out at the inflection. Both are shipped bugs in more than one
 * kernel.
 *
 * What is carried instead is **one stated start direction, transported along the path introducing no
 * rotation about the tangent**. Discretely that is: for each pair of consecutive chords, rotate the
 * reference by the *same* rotation that takes one chord direction to the next — about their common
 * perpendicular, by the angle between them, and by nothing else. Three properties follow, and they are the
 * three the sweep needs:
 *
 * - **Defined everywhere.** Two equal chords give a zero rotation axis and the reference is carried through
 *   unchanged, so a straight run is the identity rather than a division by zero.
 * - **No flips.** The step is a rotation by the angle between consecutive chords, which is small wherever
 *   the sampling is fine; nothing in it can jump by π, because nothing in it reads which way the curve
 *   bends.
 * - **A pure function of the path and one stated start**, computed by a single forward pass — so a reload
 *   rebuilds the identical frame and the sweep's mesh is byte-identical, which is what a byte-equal
 *   save/load actually rests on.
 */
object Frames3 {
    /** Below this |sin| between two chord directions the two count as parallel and nothing is rotated. */
    private const val TURN_EPS = 1e-12

    /** Below this length a projected reference direction counts as degenerate (a unit-vector sine). */
    private const val REF_EPS = 1e-6

    /**
     * The sharpest corner a sweep will mitre, as the cosine of half the turn: `cos(85°)`, i.e. a turn of
     * 170°. Beyond it the mitre plane is nearly edge-on to the run and the trimmed section runs away to
     * infinity, which is not a shape — it is the path doubling back, and it is refused by name.
     */
    private const val MITRE_MIN = 0.08715574274765817

    /** A closed path's frame counts as closing when its seam residual is below this, in radians. */
    const val SEAM_EPS = 1e-6

    /**
     * The **start reference direction**: the [up] direction — the normal of the space the path is parented
     * to — projected perpendicular to the first tangent [t0].
     *
     * Derived by construction rather than guessed, which is the whole reason the sweep takes a plane at all:
     * a curve's construction is always parented (OP-26), so the space it was drawn in already says which way
     * is "up" there, and tilting that datum rolls the sweep with it — by construction, with nothing stored.
     * For a path drawn flat in its own space this is exactly the space's normal, unchanged, which is the
     * answer anybody would predict.
     *
     * **The degenerate case is stated, not hidden.** When the path leaves along the space's own normal — a
     * riser going straight up out of the plan — the projection is zero and there is no answer to derive. The
     * fallback is then deterministic and is deliberately *not* a small perturbation of the input: the
     * **world axis least parallel to the tangent**, tried in the fixed order X, Y, Z so a tie is broken the
     * same way on every machine and every reload. Its projection is at least `sqrt(2/3)` long, so it can
     * never be the degenerate case in turn. The consequence is worth knowing rather than discovering: on
     * such a path the initial roll is the world's choice and not the drawing's, so state the roll if it
     * matters.
     */
    fun startReference(
        t0: Vec3,
        up: Vec3,
    ): Vec3 {
        val t = t0.normalized()
        val n = up.normalized()
        val projected = n - t * n.dot(t)
        if (projected.length() > REF_EPS) return projected.normalized()
        val axis = listOf(Vec3.X, Vec3.Y, Vec3.Z).minByOrNull { abs(t.dot(it)) } ?: Vec3.X
        return (axis - t * axis.dot(t)).normalized()
    }

    /**
     * [v] rotated about the unit axis [axis] by [angle] radians — Rodrigues' formula, written out.
     */
    fun rotate(
        v: Vec3,
        axis: Vec3,
        angle: Double,
    ): Vec3 {
        val c = cos(angle)
        val s = sin(angle)
        return v * c + axis.cross(v) * s + axis * (axis.dot(v) * (1.0 - c))
    }

    /**
     * The frame along [path], starting from the reference [up] says, rolled by [rollRad] and twisted by
     * [twistRad] over the whole run — or null with the reason it has none.
     *
     * **The sampling rule, stated because it is a decision.** The spine is the path's pieces sampled to a
     * **chord tolerance in millimetres** ([tolMm]) rather than to the renderer's fixed step count: a mesh is
     * geometry, and OP-15 requires its error to be stated in the unit a made part is wrong by. A straight
     * piece therefore needs exactly one span (a chord of a straight line *is* the line), and a cubic needs
     * [GeomMath.bezierSteps]'s count from its own second derivative. That count is then **refined by the
     * twist**: a point [reach] mm off the axis turning by an angle needs as many chords as a circle of that
     * radius does over the same angle ([GeomMath.chordSteps] — the revolve's own rule), so a full turn of
     * twist along a single straight segment is resolved instead of vanishing between its two ends. Nothing
     * here reads a value the *graph's shape* depends on: the station count is computed inside one function
     * of values (OP-21) and is a pure function of them, so a reload rebuilds the identical mesh.
     *
     * **The twist is distributed linearly in arc length** — the only distribution that is stateable ("so
     * many degrees per metre") and the only one that does not need a second parameter to describe.
     *
     * Refused, by name and healing (OP-3): a path with no pieces, a path with no length, and a path that
     * doubles back on itself so sharply that no mitre exists there.
     */
    fun along(
        path: Path3,
        up: Vec3,
        rollRad: Double = 0.0,
        twistRad: Double = 0.0,
        reach: Double = 0.0,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Pair<MovingFrame?, String?> {
        if (path.elements.isEmpty()) return null to "this curve has no pieces, so there is nothing to sweep along"
        val (points, curvatures) = spine(path, reach, twistRad, tolMm)
        val n = points.size
        if (n < 2) return null to "this curve has no length, so there is nothing to sweep along"

        // the chords: one direction per span, plus the closing one when the path comes back to its start
        val spans = if (path.closed) n else n - 1
        val dirs = ArrayList<Vec3>(spans)
        val cum = ArrayList<Double>(n + 1)
        cum.add(0.0)
        for (k in 0 until spans) {
            val d = points[(k + 1) % n] - points[k]
            dirs.add(d.normalized())
            cum.add(cum[k] + d.length())
        }
        val length = cum[spans]
        if (length <= Geom3.WELD_TOL) return null to "this curve has no length, so there is nothing to sweep along"

        // the transport: one reference per span, carried forward introducing no rotation about the tangent
        val refs = ArrayList<Vec3>(spans)
        refs.add(startReference(dirs[0], up))
        for (k in 1 until spans) {
            val carried =
                transport(refs[k - 1], dirs[k - 1], dirs[k])
                    ?: return null to reversalRefusal(cum[k])
            refs.add(carried)
        }

        // the seam: for a closed path, how far the frame is from coming back to itself after the loop
        var seam = 0.0
        if (path.closed) {
            val back =
                transport(refs[spans - 1], dirs[spans - 1], dirs[0])
                    ?: return null to reversalRefusal(0.0)
            val r0 = refs[0]
            seam = wrapAngle(atan2(r0.cross(back).dot(dirs[0]), r0.dot(back)) + twistRad)
        }

        val stations = ArrayList<Frame3>(n)
        for (k in 0 until n) {
            // the chord arriving here and the chord leaving: at a closed path's seam the arriving one is the
            // closing span, at an open path's ends the two are the same, which is what makes a cap normal to
            // the tangent fall out rather than being a case
            val inIdx = if (k == 0) (if (path.closed) spans - 1 else 0) else k - 1
            val outIdx = if (k >= spans) spans - 1 else k
            val a = dirs[inIdx]
            val b = dirs[outIdx]
            val mitre = (a + b).normalized()
            if (mitre.dot(a) < MITRE_MIN) return null to reversalRefusal(cum[k])
            // The twist is read at the arc length the **arriving** chord brings, which for a closed path's
            // seam station is the whole loop and not zero: the ring there is built on the reference carried
            // all the way round, so it must carry all the way round's twist too. The two readings of that
            // one ring agree exactly when the seam closes, which is checked above and refused when it does
            // not — so this is not a compensation, it is the same statement read from the side it is built on.
            val sTwist = if (path.closed && k == 0) length else cum[k]
            val turned = rotate(refs[inIdx], a, rollRad + twistRad * (sTwist / length))
            stations.add(Frame3(cum[k], points[k], a, (turned - a * turned.dot(a)).normalized(), mitre, curvatures[k]))
        }
        return MovingFrame(stations, length, path.closed, seam) to null
    }

    private fun reversalRefusal(s: Double): String =
        "this curve doubles back on itself ${mm(s)} mm along, and a sweep has no section there — a run that " +
            "reverses is two runs, so split it or move the point that folds it"

    /**
     * [r] carried from chord direction [from] to chord direction [to] — the transport step, and null when
     * the two directions are opposite (which is the path reversing, not a rotation).
     *
     * Re-orthogonalized against [to] afterwards, so the reference cannot drift off perpendicular over a few
     * thousand stations of accumulated rounding — a correction of the same kind the frame's own construction
     * makes, not a repair of a wrong answer.
     */
    private fun transport(
        r: Vec3,
        from: Vec3,
        to: Vec3,
    ): Vec3? {
        val axis = from.cross(to)
        val sinA = axis.length()
        val cosA = from.dot(to)
        val carried =
            if (sinA <= TURN_EPS) {
                if (cosA < 0.0) return null
                r
            } else {
                rotate(r, axis * (1.0 / sinA), atan2(sinA, cosA))
            }
        val fixed = carried - to * carried.dot(to)
        if (fixed.length() <= REF_EPS) return null
        return fixed.normalized()
    }

    /**
     * The sampled spine: the path's points in order (a closed path's returning duplicate dropped) and, per
     * point, the **analytic** curvature of the piece it came from.
     *
     * A point that two pieces hand over at gets the **larger** of their two curvatures, so a station on the
     * join of a straight run and a bend is judged by the bend — the answer that cannot let a fold through.
     */
    private fun spine(
        path: Path3,
        reach: Double,
        twistRad: Double,
        tolMm: Double,
    ): Pair<List<Vec3>, List<Double>> {
        // pass one: how long each piece is, at its own base sampling — needed before the twist can say how
        // finely any of them has to be cut, since the twist is distributed in arc length
        val base = path.elements.map { baseSteps(it, tolMm) }
        val lengths =
            path.elements.mapIndexed { i, el ->
                var len = 0.0
                var prev = pointAt(el, 0.0)
                for (j in 1..base[i]) {
                    val p = pointAt(el, j.toDouble() / base[i])
                    len += (p - prev).length()
                    prev = p
                }
                len
            }
        val total = lengths.sum()

        val points = ArrayList<Vec3>()
        val curvature = ArrayList<Double>()
        for ((i, el) in path.elements.withIndex()) {
            val share = if (total > Geom3.WELD_TOL) lengths[i] / total else 0.0
            val steps = max(base[i], GeomMath.chordSteps(reach, abs(twistRad) * share, tolMm))
            for (j in 0..steps) {
                val t = j.toDouble() / steps
                val p = pointAt(el, t)
                val k = curvatureAt(el, t)
                if (points.isNotEmpty() && (p - points.last()).length() <= Geom3.WELD_TOL) {
                    // the hand-over point two pieces share, and a sample a degenerate piece repeated
                    curvature[curvature.size - 1] = max(curvature.last(), k)
                    continue
                }
                points.add(p)
                curvature.add(k)
            }
        }
        // a closed path's last piece ends where the first began: the ring closes through the span list, so
        // the returning duplicate is not a station of its own
        if (path.closed && points.size > 1 && (points.last() - points.first()).length() <= Geom3.WELD_TOL) {
            curvature[0] = max(curvature[0], curvature.last())
            points.removeAt(points.size - 1)
            curvature.removeAt(curvature.size - 1)
        }
        return points to curvature
    }

    /**
     * How many spans a piece is sampled into before the twist has its say — one for a straight run.
     *
     * A **helix** is the case where the chord tolerance is met exactly rather than bounded: the curve has one
     * constant radius of curvature `1/κ`, so the chord rule a circle of that radius obeys is the chord rule
     * *this* curve obeys, over its own total turn. That is [GeomMath.chordSteps] — the revolve's rule and the
     * twist refinement's rule — used for the third time and still not re-derived.
     */
    private fun baseSteps(
        el: Curve3Element,
        tolMm: Double,
    ): Int =
        when (el) {
            is Curve3Element.Seg3 -> 1
            is Curve3Element.Bezier3 -> max(1, bezierSteps3(el, tolMm))
            is Curve3Element.Helix3 ->
                min(
                    MAX_HELIX_SPANS,
                    max(1, GeomMath.chordSteps(if (el.curvature > 0.0) 1.0 / el.curvature else 0.0, el.sweepAngle, tolMm)),
                )
        }

    /**
     * The most chords one helix piece is cut into for a **mesh**, a cap on a count that is a function of a
     * typed value: a hundred-turn spring at [GeomMath.TESS_TOL_MM] would otherwise ask for stations by the
     * hundred thousand and a sweep by the million triangles. Nothing about the shape is decided here — the
     * cap is reached only where the honest chord error would already be far below what any machine can hold
     * — and it is stated rather than discovered, in the unit OP-15 asks for: at this count a 20 mm helix is
     * within 0.0002 mm of its chords over ten turns.
     */
    private const val MAX_HELIX_SPANS = 65536

    /**
     * How many chords a cubic in space needs to stay within [tolMm] — the 3D twin of
     * [GeomMath.bezierSteps], the identical bound on a cubic's second derivative one dimension up.
     */
    private fun bezierSteps3(
        b: Curve3Element.Bezier3,
        tolMm: Double,
    ): Int {
        val d0 = b.p0 - b.p1 * 2.0 + b.p2
        val d1 = b.p1 - b.p2 * 2.0 + b.p3
        val second = 6.0 * max(d0.length(), d1.length())
        if (second <= 0.0 || tolMm <= 0.0) return 1
        return max(1, min(1024, ceil(sqrt(second / (8.0 * tolMm))).toInt()))
    }

    private fun pointAt(
        el: Curve3Element,
        t: Double,
    ): Vec3 =
        when (el) {
            is Curve3Element.Seg3 -> el.start + (el.end - el.start) * t
            is Curve3Element.Bezier3 -> Curves3.bezierPointAt(el, t)
            is Curve3Element.Helix3 -> el.at(t)
        }

    /**
     * The **curvature** of [el] at [t] — `|B' × B''| / |B'|³`, which is where the self-intersection refusal
     * gets its radius of curvature (`1 / κ`) from.
     *
     * Exactly zero on a [Curve3Element.Seg3], and that is a fact rather than a tolerance: a straight run has
     * no bend a profile could fold through, however wide the profile is. This is also the number the Frenet
     * frame would have had to divide by (see this object's note), which is why a straight path is the case
     * that decides the frame.
     *
     * On a [Curve3Element.Helix3] it is `r / (r² + b²)` — **constant**, and read straight off the piece. That
     * is the first time this function returns a closed form rather than a derivative sampled at a parameter,
     * which is exactly what OP-26 said the helix would be worth here: the sweep's self-intersection refusal
     * fires at a radius that is *stated*, not estimated (see [Curve3Element.Helix3.curvature]).
     */
    private fun curvatureAt(
        el: Curve3Element,
        t: Double,
    ): Double =
        when (el) {
            is Curve3Element.Seg3 -> 0.0
            is Curve3Element.Helix3 -> el.curvature
            is Curve3Element.Bezier3 -> {
                val d1 = Curves3.bezierTangentAt(el, t)
                val d2 = (el.p2 - el.p1 * 2.0 + el.p0) * (6.0 * (1.0 - t)) + (el.p3 - el.p2 * 2.0 + el.p1) * (6.0 * t)
                val speed = d1.length()
                if (speed <= Vec3.EPS) 0.0 else d1.cross(d2).length() / (speed * speed * speed)
            }
        }

    /** [a] reduced to `(−π, π]` — how a residual rotation is reported and compared against zero. */
    fun wrapAngle(a: Double): Double {
        val twoPi = 2.0 * PI
        var x = a.mod(twoPi)
        if (x > PI) x -= twoPi
        return x
    }

    /** A millimetre figure for a refusal — three decimals, trailing zeros dropped, like the status line's. */
    internal fun mm(x: Double): String {
        val scaled = round(abs(x) * 1000.0).toLong()
        val i = scaled / 1000
        val f = (scaled % 1000).toString().padStart(3, '0').trimEnd('0')
        val s = if (f.isEmpty()) "$i" else "$i.$f"
        return if (x < 0 && scaled != 0L) "-$s" else s
    }

    /** A degree figure for a refusal, from an angle in radians. */
    internal fun deg(rad: Double): String = mm(rad * 180.0 / PI)
}

/**
 * What [Embedding.check] found: why the swept body is not embedded, what it cost, and how near the run came
 * to itself.
 *
 * [closest] and [pairsExamined] are not diagnostics for their own sake — they are the two numbers the
 * criterion's argument rests on (the approach it actually measured, and the work the grid did to find it),
 * so a test can assert them rather than describe them.
 */
class EmbeddingReport(
    /** Why the swept body would pass through itself, in the words a refusal uses — or null when it would not. */
    val defect: String?,
    /**
     * The closest **bottleneck** among the pairs the grid offered, in mm — `Double.MAX_VALUE` when the run
     * has none within reach of its own section, which is every run that is comfortably clear of itself.
     */
    val closest: Double,
    /** How many pairs of spine pieces the grid offered for comparison — the cost, and the claim it is not `n²`. */
    val pairsExamined: Int,
)

/**
 * **Is the swept body embedded?** — the sweep's whole *watertight or refused* obligation (OP-9), checked
 * before a triangle is emitted (OP-26, step 2's criterion, completed).
 *
 * **The criterion is the spine's reach** (Federer's reach, the local feature size):
 *
 * ```
 * reach(path) = min( 1/κ_max , ½·min{ |P − Q| : (P, Q) a double normal } )
 * ```
 *
 * and the sweep is embedded when the profile's own **reach** — its greatest distance from the path, hence
 * the radius of the tube that contains the body — stays below it. The two terms are the two ways a swept
 * body folds through itself, and they are genuinely different failures:
 *
 * - **Locally**, on a bend tighter than the profile is wide: the inner side of the section turns inside out.
 *   That is `κ·reach ≥ 1`, and it is the criterion the sweep shipped with.
 * - **Globally**, where the run comes back alongside itself: a spring whose wire is thicker than half its
 *   pitch has each turn passing through the turn below, while **every station's curvature is comfortable**.
 *   Nothing local can see it. It is not helix-specific either — a serpentine whose legs run closer than the
 *   tube is wide does exactly the same — so this is a statement about the *sweep*, never a case per piece
 *   kind, and it is made against the sampled spine rather than against the curve's vocabulary.
 *
 * **Which pairs count is the crux, and the answer is a double normal rather than an arc length.** Two points
 * of a run are always close when they are close *along* it, so a plain "refuse when two centres are nearer
 * than twice the reach" refuses every neighbouring pair — that naive form is recorded as rejected under
 * OP-26's step 3, and what replaces it has to say which pairs are comparable. A **double normal** (a
 * bottleneck) is the classical answer and is the one this uses: a pair `(P, Q)` whose connecting segment
 * stands **perpendicular to the run at both ends**. It is exactly the stationarity condition of the distance
 * between two points of the spine, so it is not a test bolted on afterwards — it *is* what "these two parts
 * of the run approach each other" means. A neighbour pair fails it because the segment joining two nearby
 * points runs nearly *along* the curve, not across it; the far side of a hairpin passes it because the
 * segment crosses the run square at both ends.
 *
 * **The arc-length exclusion was derived, and then rejected, and the reason is worth keeping.** The obvious
 * alternative is to compare only pairs at least δ apart along the spine, with δ derived from the curvature
 * bound: a curve of curvature at most `κ` spans, over an arc `h ≤ π/κ`, at least the chord a circle of
 * radius `R = 1/κ` spans (Schur's theorem), so `δ = 2R·arcsin(reach/R)` is exactly the arc at which the
 * tightest *allowed* bend first opens two sections out to their own clearance — `2·reach` on a straight run,
 * `πR` at the local criterion's own limit. That is a real derivation and it works on any smooth path. It
 * **cannot survive a corner**: a polyline route turns through a right angle at a vertex where the curvature
 * is *zero on both sides*, so every curvature-derived δ is far too small there and a perfectly ordinary
 * mitred elbow refuses itself — two points a section's width either side of the corner stand `reach·√2`
 * apart, well inside their clearance, with nothing in the arc length to explain it. Patching δ with the
 * turning angle across the corner was tried and abandoned in turn: the turning bound (`chord ≥ h·cos(Θ/2)`)
 * goes vacuous at half a turn, so a serpentine — two right angles, and the very case this must catch — falls
 * off the end of it. The double normal has no such seam: it reads the geometry the pair actually has.
 *
 * **It is measured on the spans, not on the stations, and that is not an optimization.** A straight piece is
 * sampled into exactly **one** span (a chord of a line *is* the line), so a station-to-station test would be
 * blind along the whole of it: two straight legs running side by side with their ends staggered have no two
 * *stations* near each other at all. So the spine is compared piece against piece as **segments** — the
 * closest points of two segments in closed form, then the four one-sided derivatives that say whether that
 * approach is stationary along the run. The one-sidedness is what makes a **kink** come out right without a
 * case for it: at a vertex the forward and backward directions differ, so the condition is that the segment
 * lies in the vertex's *normal cone*, which is the honest generalization of "perpendicular" and exactly what
 * a mitred elbow needs.
 *
 * **The seam of a closed path needs no special handling either**, which is worth saying because an
 * arc-length exclusion would have needed the arc distance to wrap: the pieces either side of the seam are
 * neighbours, their approach is not stationary, and they are rejected for the same reason every other
 * neighbour pair is. Only touching pieces are skipped outright, since a segment of zero length has no
 * direction to be perpendicular to.
 *
 * **The comparison carries the tessellation's own error as slack, and that is where the honest resolution of
 * this criterion is stated (OP-15).** The spine is a polyline within [GeomMath.TESS_TOL_MM] of the curve it
 * samples, so a distance measured on it is within twice that of the truth; the refusal fires only when the
 * run is inside its own clearance by **more than the mesh's stated error**. The limit is therefore resolved
 * to about four hundredths of a millimetre, which is the resolution the body itself has, and not to the
 * last bit.
 *
 * **What it does not claim.** The body is measured as the tube of radius `reach` about the spine, which
 * contains it exactly where the path is smooth. At a **mitred kink** the outer corner of the join stands
 * `reach / cos(half the turn)` from the spine — further than the tube — so two sharp corners aimed at each
 * other could touch while their spines still clear. The error is entirely in the permissive direction (the
 * criterion under-states the body, so it never refuses a run that fits), and closing it would mean a
 * per-station reach; it is left as a stated boundary of the claim rather than a hidden one.
 *
 * **Cost.** A grid of cell size `2·reach` — the query radius itself — with every span cut into pieces no
 * longer than a cell and registered in the cells its box covers, so two pieces within the query radius
 * always land within one cell of each other and the 27-cell neighbourhood is exact rather than heuristic.
 * The scan is O(pieces × neighbourhood occupancy), which is linear in the pieces for any run whose section
 * is not vast against its sampling: a 40-turn coil offers a few dozen pairs per piece where an all-pairs
 * test would offer millions. [EmbeddingReport.pairsExamined] reports the count so the claim is asserted.
 */
object Embedding {
    /**
     * How far from perpendicular a connecting segment may stand and still count as a double normal — a
     * dimensionless cosine, and a **rounding** tolerance rather than a modelling one: the condition is an
     * exact stationarity, and this only keeps the exactly-perpendicular case (two parallel legs, where the
     * dot product is a difference of equal numbers) from falling the wrong side of zero.
     */
    private const val CONE_EPS = 1e-9

    /**
     * Whether a profile reaching [reach] from the path is embedded along [frame] — the `min` of the two
     * terms, in that order, so the local failure keeps its own words where both would fire.
     *
     * [what] is how the refusal names the profile's size ("the tube's radius (5 mm)"), passed in because the
     * profile's *kind* is the sweep's business while this is the *path's* statement.
     */
    fun check(
        frame: MovingFrame,
        reach: Double,
        what: String,
    ): EmbeddingReport {
        // ---- the first term: 1/κ_max, station by station, and in station order so the message is stable
        for (st in frame.stations) {
            if (st.curvature * reach >= 1.0) {
                return EmbeddingReport(
                    "$what is larger than the bend ${Frames3.mm(st.s)} mm along " +
                        "the path (radius ${Frames3.mm(1.0 / st.curvature)} mm), so the sweep would pass through itself",
                    Double.MAX_VALUE,
                    0,
                )
            }
        }

        // ---- the second term: the closest bottleneck of the spine
        val clearance = 2.0 * reach
        val pieces = piecesOf(frame, clearance)
        if (pieces.size < 3) return EmbeddingReport(null, Double.MAX_VALUE, 0)
        val grid = HashMap<Long, MutableList<Int>>(pieces.size * 2)
        for (i in pieces.indices) {
            forEachCell(pieces[i], clearance) { k -> grid.getOrPut(k) { ArrayList() }.add(i) }
        }

        var bestD = Double.MAX_VALUE
        var bestA = 0.0
        var bestB = 0.0
        var examined = 0
        val seen = IntArray(pieces.size) { -1 }
        for (i in pieces.indices) {
            forEachCell(pieces[i], clearance) { k ->
                for (dx in -1..1) {
                    for (dy in -1..1) {
                        for (dz in -1..1) {
                            val bucket = grid[k + (dx.toLong() shl 42) + (dy.toLong() shl 21) + dz.toLong()] ?: continue
                            for (j in bucket) {
                                // each pair is offered once, from its lower index, and only once per pair of
                                // pieces however many cells the two happen to share; the pieces that touch
                                // are the ones with no approach to speak of
                                if (j <= i || seen[j] == i || touching(i, j, pieces.size, frame.closed)) continue
                                seen[j] = i
                                examined++
                                val hit = bottleneck(pieces, i, j, frame.closed)
                                if (hit != null && hit.d < bestD) {
                                    bestD = hit.d
                                    bestA = hit.s
                                    bestB = hit.t
                                }
                            }
                        }
                    }
                }
            }
        }

        if (bestD < clearance - 2.0 * GeomMath.TESS_TOL_MM) {
            return EmbeddingReport(
                "the run passes within ${Frames3.mm(bestD)} mm of itself, between ${Frames3.mm(bestA)} mm and " +
                    "${Frames3.mm(bestB)} mm along the path, while $what needs ${Frames3.mm(clearance)} mm " +
                    "between them — so the sweep would cut into itself; thin the section, or open the run out",
                bestD,
                examined,
            )
        }
        return EmbeddingReport(null, bestD, examined)
    }

    /**
     * One piece of the sampled spine: a straight run from [a] to [b] carrying the arc position of its start,
     * cut short enough that its box never spans more than two cells of the grid.
     *
     * Arc *is* length here, and that is by construction rather than by approximation: [Frame3.s] is the
     * cumulative sum of the very chords these pieces are cut from, so a point [x] mm along a piece stands
     * exactly `s0 + x` along the spine.
     */
    private class Piece(val a: Vec3, val dir: Vec3, val s0: Double, val len: Double) {
        fun at(x: Double): Vec3 = a + dir * x
    }

    /** A bottleneck: how near the run came to itself, and where along the spine the two ends of it stand. */
    private class Approach(val d: Double, val s: Double, val t: Double)

    /** Whether the two pieces meet end to end, which is the one pair with no approach to measure. */
    private fun touching(
        i: Int,
        j: Int,
        count: Int,
        closed: Boolean,
    ): Boolean = j == i + 1 || (closed && i == 0 && j == count - 1)

    /** The spine as pieces, each no longer than [cell] so its box covers at most two cells per axis. */
    private fun piecesOf(
        frame: MovingFrame,
        cell: Double,
    ): List<Piece> {
        val st = frame.stations
        val n = st.size
        val spans = if (frame.closed) n else n - 1
        val out = ArrayList<Piece>(spans)
        for (k in 0 until spans) {
            val a = st[k].at
            val b = st[(k + 1) % n].at
            val len = (b - a).length()
            if (len <= Geom3.WELD_TOL || cell <= 0.0) continue
            val dir = (b - a) * (1.0 / len)
            val cuts = max(1, ceil(len / cell).toInt())
            val step = len / cuts
            for (q in 0 until cuts) {
                out.add(Piece(a + dir * (step * q), dir, st[k].s + step * q, step))
            }
        }
        return out
    }

    /** Every grid cell the piece's box touches — at most eight, since no piece is longer than a cell. */
    private inline fun forEachCell(
        p: Piece,
        cell: Double,
        body: (Long) -> Unit,
    ) {
        val b = p.at(p.len)
        val x0 = cellIndex(min(p.a.x, b.x), cell)
        val x1 = cellIndex(max(p.a.x, b.x), cell)
        val y0 = cellIndex(min(p.a.y, b.y), cell)
        val y1 = cellIndex(max(p.a.y, b.y), cell)
        val z0 = cellIndex(min(p.a.z, b.z), cell)
        val z1 = cellIndex(max(p.a.z, b.z), cell)
        for (x in x0..x1) {
            for (y in y0..y1) {
                for (z in z0..z1) body(key(x, y, z))
            }
        }
    }

    /**
     * The **double normal** these two pieces carry, or null when their closest approach is not one.
     *
     * The closest points of two segments are a closed form (the squared distance is a convex quadratic on a
     * rectangle), and what is asked of them afterwards is the stationarity that makes the approach a
     * bottleneck: moving either end along the run, in either direction, must not bring the two closer. Where
     * the closest point is interior to a piece that is the ordinary perpendicularity; where it sits at a
     * piece's end the *neighbouring* piece's direction is used instead, which is what makes the condition
     * read as the vertex's **normal cone** at a corner and needs no case of its own. At the free end of an
     * open run there is no direction on that side, and nothing is asked.
     *
     * An exact crossing — two pieces that meet at a point without being neighbours — has no direction to be
     * perpendicular to and is reported as the approach of zero it is.
     */
    private fun bottleneck(
        pieces: List<Piece>,
        i: Int,
        j: Int,
        closed: Boolean,
    ): Approach? {
        val p = pieces[i]
        val q = pieces[j]
        val (x, y) = closestParams(p, q)
        val v = q.at(y) - p.at(x)
        val d = v.length()
        if (d <= Geom3.WELD_TOL) return Approach(0.0, p.s0 + x, q.s0 + y)
        val u = v * (1.0 / d)
        val ahead = if (x < p.len) p.dir else nextDir(pieces, i, closed)
        val behind = if (x > 0.0) p.dir else prevDir(pieces, i, closed)
        val onward = if (y < q.len) q.dir else nextDir(pieces, j, closed)
        val back = if (y > 0.0) q.dir else prevDir(pieces, j, closed)
        if (ahead != null && u.dot(ahead) > CONE_EPS) return null
        if (behind != null && u.dot(behind) < -CONE_EPS) return null
        if (onward != null && u.dot(onward) < -CONE_EPS) return null
        if (back != null && u.dot(back) > CONE_EPS) return null
        return Approach(d, p.s0 + x, q.s0 + y)
    }

    private fun nextDir(
        pieces: List<Piece>,
        i: Int,
        closed: Boolean,
    ): Vec3? =
        if (i + 1 < pieces.size) {
            pieces[i + 1].dir
        } else if (closed) {
            pieces[0].dir
        } else {
            null
        }

    private fun prevDir(
        pieces: List<Piece>,
        i: Int,
        closed: Boolean,
    ): Vec3? =
        if (i > 0) {
            pieces[i - 1].dir
        } else if (closed) {
            pieces[pieces.size - 1].dir
        } else {
            null
        }

    /**
     * How far along each piece the two are closest — the ordinary segment-to-segment closest points, written
     * out because both directions are unit here (arc *is* length), which reduces the usual algebra to three
     * dot products and a pair of clamps.
     */
    private fun closestParams(
        p: Piece,
        q: Piece,
    ): Pair<Double, Double> {
        val r = p.a - q.a
        val b = p.dir.dot(q.dir)
        val c = p.dir.dot(r)
        val f = q.dir.dot(r)
        val den = 1.0 - b * b
        var x = if (den > 1e-12) ((b * f - c) / den).coerceIn(0.0, p.len) else 0.0
        var y = (b * x + f).coerceIn(0.0, q.len)
        x = (b * y - c).coerceIn(0.0, p.len)
        y = (b * x + f).coerceIn(0.0, q.len)
        return x to y
    }

    /**
     * Which cell of the grid a coordinate falls in, clamped so a wild coordinate cannot overflow the index.
     *
     * Clamping merges far-apart cells, which costs a few extra candidates and can never cost an answer: the
     * true approach is computed for every pair the grid offers.
     */
    private fun cellIndex(
        v: Double,
        cell: Double,
    ): Int {
        val f = floor(v / cell)
        return when {
            f < -CELL_LIMIT -> -CELL_LIMIT
            f > CELL_LIMIT -> CELL_LIMIT
            else -> f.toInt()
        }
    }

    /**
     * How far the grid indexes before it starts folding distant cells together — the clamp [cellIndex]
     * applies, chosen so the packing below stays one-to-one with room to spare (`2·CELL_LIMIT < 2^21`).
     */
    private const val CELL_LIMIT = 500_000

    /**
     * Three cell indices packed into one key by **arithmetic** rather than by masking, 21 bits apiece, so
     * that adding `(dx shl 42) + (dy shl 21) + dz` to a key names the neighbouring cell without unpacking
     * it. One-to-one within [CELL_LIMIT], which is what that limit is for.
     */
    private fun key(
        x: Int,
        y: Int,
        z: Int,
    ): Long = (x.toLong() shl 42) + (y.toLong() shl 21) + z.toLong()
}
