package constructit.geom

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
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

    /** How many spans a piece is sampled into before the twist has its say — one for a straight run. */
    private fun baseSteps(
        el: Curve3Element,
        tolMm: Double,
    ): Int =
        when (el) {
            is Curve3Element.Seg3 -> 1
            is Curve3Element.Bezier3 -> max(1, bezierSteps3(el, tolMm))
        }

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
        }

    /**
     * The **curvature** of [el] at [t] — `|B' × B''| / |B'|³`, which is where the self-intersection refusal
     * gets its radius of curvature (`1 / κ`) from.
     *
     * Exactly zero on a [Curve3Element.Seg3], and that is a fact rather than a tolerance: a straight run has
     * no bend a profile could fold through, however wide the profile is. This is also the number the Frenet
     * frame would have had to divide by (see this object's note), which is why a straight path is the case
     * that decides the frame.
     */
    private fun curvatureAt(
        el: Curve3Element,
        t: Double,
    ): Double =
        when (el) {
            is Curve3Element.Seg3 -> 0.0
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
