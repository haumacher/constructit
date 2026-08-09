package constructit.geom

import kotlin.math.abs
import kotlin.math.max

/**
 * One **place where a run crosses a plane**: which piece of the path it happens on, at what parameter, how
 * far along the run that is, where it stands, and which way the run is going there.
 *
 * [tangent] is the piece's **analytic** direction at [t], never a chord's — it is what the section's own frame
 * is seeded against ([Frames3.along]), so a run that pierces a plane 340 mm along pierces it going the way the
 * *curve* goes there and not the way its sampling happened to.
 */
data class Pierce(
    /** Index of the path element the crossing is on. */
    val piece: Int,
    /** Parameter within that element, in `[0, 1]`. */
    val t: Double,
    /** Arc length from the start of the run — what orders the crossings, and what a message names one by. */
    val s: Double,
    val at: Vec3,
    val tangent: Vec3,
)

/**
 * **Where a run crosses a plane** (OP-26, step 2's *in-place* sweep) — an ordered solution set, exactly as an
 * intersection is one dimension down (OP-1).
 *
 * This is what makes a section drawn **in place** sweepable without being moved: the point of the section that
 * rides the run is *the point the run passes through the section's own plane at*, and where a run crosses that
 * plane several times the crossings are an ordered set with a **recorded** index — never a nearness re-scored
 * on every load (OP-18's rule: a scored choice is stored once, at the click).
 *
 * **The order is arc length along the run**, which is the only order that is a property of the drawing rather
 * than of the arithmetic: it survives re-tessellation, it survives the plane moving, and it is the order the
 * user sees when they follow the run with their eye.
 *
 * **A crossing is a change of side, and a touch is not a crossing.** The signed distance to the plane is
 * followed along the sampled parameter and a crossing is emitted where its **sign changes**; a run that starts
 * on the plane and leaves it, or that comes down to it and turns back, changes no side and pierces nothing.
 * That is the honest reading of *"the curve pierces this plane"*, and it is what keeps a run drawn **in** a
 * plane — a plan curve against the plan, the everyday sweep — from claiming to cross it at every point.
 *
 * **On a closed run the seam is not an end**, so the comparison wraps ([Path3.closed], which is *structure* and
 * never inferred from coincident endpoints). The seam is the one place a linear walk cannot see a change of
 * side: the run's last sample and its first are the same point, so where that point lies exactly *on* the plane
 * both carry no side and the walk hands over from one to the other without ever comparing them. A ring whose
 * start sits on the plane and passes through it therefore used to report one crossing where it plainly has two.
 * The wrap comparison is the same rule read round the corner — the last sample that was on a side against the
 * first one that was — so a run that comes down to the plane **at** the seam and turns back still crosses
 * nothing, and a seam crossing is attributed to the run's own start (piece 0, `t = 0`, `s = 0`) exactly as a
 * crossing at a piece hand-over is attributed to the piece that begins there.
 *
 * **Where the exactness lies** (OP-15). The samples only *bracket* a root; the root itself is bisected on the
 * analytic piece to the last bits of its parameter, and the point and the tangent are then read off that piece.
 * So the crossing is as exact as the curve's own formula, and only the *finding* of it is sampled: a crossing
 * hidden entirely between two samples — a run that dips through the plane and back inside one sampling step —
 * is not found. The sampling is therefore the piece's own chord count ([Frames3.baseSteps], the sweep's rule
 * rather than a second one) taken to at least [MIN_SAMPLES], which resolves any excursion longer than a few
 * per cent of a piece.
 */
object Pierce3 {
    /**
     * The fewest samples any one piece is examined at, whatever its chord count says.
     *
     * A straight piece needs exactly one chord to be *drawn* and that is what [Frames3.baseSteps] gives it,
     * but one chord finds only crossings a straight piece can have — which is fine for a segment and wrong for
     * a cubic, whose chord count is about its curvature and says nothing about how often it changes side. So
     * the count is raised here rather than there: the sweep's sampling rule is untouched and this one is stated
     * for what it is, a root-bracketing resolution.
     */
    private const val MIN_SAMPLES = 32

    /** Below this the parameter interval is done — a bisection floor, not a modelling tolerance. */
    private const val PARAM_EPS = 1e-15

    /**
     * Every place [path] crosses [plane], in order along the run.
     *
     * Deterministic and a pure function of its inputs, which is what lets an *index* into this list be the
     * durable name of one crossing (OP-1): the same drawing always yields the same list in the same order.
     */
    fun crossings(
        path: Path3,
        plane: Plane3,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): List<Pierce> = walk(path, tolMm, fieldOf(plane)).first

    /**
     * Whether the first of [crossings] is the run's own **seam** — the crossing a walk that stopped at the last
     * piece could not see.
     *
     * Geometry answering one question the *format* asks, and nothing more (OP-18): a `tool sweep` step written
     * before format 3 recorded an index into the seam-blind set, and this says by how much that numbering has
     * moved for this drawing — one, exactly, when the run crosses at its seam, and nothing otherwise. Stated
     * here rather than derived at the call site because the answer is a property of the walk, not of the list.
     */
    fun crossesAtSeam(
        path: Path3,
        plane: Plane3,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Boolean = walk(path, tolMm, fieldOf(plane)).second

    /**
     * Every place [path] changes the **sign of [field]**, in order along the run — this walk over a signed
     * field other than a plane's own (OP-28).
     *
     * The generalization is a refactor and not a second mechanism: [crossings] is exactly this over the field
     * `n·(P − p₀)`, and a sphere locus's crossings are exactly this over `|P − C| − r`
     * ([constructit.geom.Spheres3.crossings]). Everything the walk decides is therefore decided once — the
     * order is arc length along the run, a closed run's seam is compared across, a touch is not a crossing,
     * and the root is bisected on the analytic piece rather than on a chord — so the two questions cannot
     * drift apart, and a law repaired for one is repaired for both (the seam fix of session 66 is the case in
     * point).
     *
     * [field] must be continuous along the run and is evaluated at a piece and a parameter in `[0, 1]`.
     */
    fun crossingsOf(
        path: Path3,
        tolMm: Double = GeomMath.TESS_TOL_MM,
        field: (Curve3Element, Double) -> Double,
    ): List<Pierce> = walk(path, tolMm, field).first

    /** The signed field a plane crossing follows: the distance to [plane], along its own unit normal. */
    private fun fieldOf(plane: Plane3): (Curve3Element, Double) -> Double {
        val n = plane.normal.normalized()
        return { el, t -> (Frames3.pointAt(el, t) - plane.origin).dot(n) }
    }

    /**
     * The ordered crossings, together with whether the first of them is the **seam** one.
     *
     * One walk answering both, because the second fact is only knowable while walking: once the list exists,
     * a crossing standing at `s = 0` is indistinguishable from one the sampling happened to bisect there.
     */
    private fun walk(
        path: Path3,
        tolMm: Double,
        side: (Curve3Element, Double) -> Double,
    ): Pair<List<Pierce>, Boolean> {
        if (path.elements.isEmpty()) return emptyList<Pierce>() to false
        val out = ArrayList<Pierce>()
        var before = 0.0
        // the last sample that was *on a side*: zeros carry no side, so a run that touches the plane and turns
        // back is compared across the touch and found not to have crossed it
        var lastSign = 0
        var lastPiece = 0
        var lastT = 0.0
        // the *first* sample that was on a side, kept for the wrap comparison a closed run's seam needs
        var firstSign = 0
        for ((i, el) in path.elements.withIndex()) {
            val steps = max(Frames3.baseSteps(el, tolMm), MIN_SAMPLES)
            for (j in 0..steps) {
                val t = j.toDouble() / steps
                val d = side(el, t)
                val sg =
                    if (d > 0.0) {
                        1
                    } else if (d < 0.0) {
                        -1
                    } else {
                        0
                    }
                if (sg == 0) continue
                if (lastSign != 0 && sg != lastSign) {
                    // Where the two sides lie either side of a piece **hand-over**, the crossing is the
                    // hand-over point itself: the run passed through the plane exactly where one piece gives
                    // way to the next, and this piece's own start is that point.
                    val root = if (lastPiece == i) bisect(el, lastT, t, side) else 0.0
                    val tangent = Curves3.tangentAt(el, root)
                    if (tangent != null) {
                        out.add(Pierce(i, root, before + Curves3.lengthTo(el, root), Frames3.pointAt(el, root), tangent))
                    }
                }
                if (firstSign == 0) firstSign = sg
                lastSign = sg
                lastPiece = i
                lastT = t
            }
            before += Curves3.arcLength(el)
        }
        // **The seam** (OP-26): on a closed run the walk above compares every pair of samples except the one
        // that spans the run's own start, because the last piece hands over to the first there and the loop has
        // ended. Where the two sides lie either side of that hand-over the run crosses at the seam, and the
        // crossing is the seam itself — the run's start, at no arc length at all, which is why it takes index 0
        // of an arc-length-ordered set. Both signs must be *sides*: a run that touches the plane at its seam and
        // turns back leaves `firstSign == lastSign` and crosses nothing, exactly as a touch anywhere else does.
        var seam = false
        if (path.closed && firstSign != 0 && lastSign != 0 && firstSign != lastSign) {
            val first = path.elements[0]
            val tangent = Curves3.tangentAt(first, 0.0)
            if (tangent != null) {
                out.add(0, Pierce(0, 0.0, 0.0, Frames3.pointAt(first, 0.0), tangent))
                seam = true
            }
        }
        return out to seam
    }

    /**
     * **How a section drawn in a plane is read when it rides the run where the run pierces that plane** — the
     * whole of the in-place sweep, in one value.
     *
     * [anchor] is the crossing in the plane's own coordinates: the point of the section that travels, which is
     * *the point the run goes through the drawing at* and therefore needs no pick. [seed] states the frame
     * there from the plane's own axes, so the drawing **is** the run's section at that place rather than
     * something congruent to it standing somewhere else.
     *
     * [fromBehind] is the one thing that is not a free choice, and it is worth stating why it is not a
     * *recorded* one either. A moving frame is right-handed by construction — its second axis is
     * `tangent × ref` — so a frame whose reference is the plane's own x axis has the plane's own y axis for
     * its second **only when the run crosses the way the plane faces**. Where the run crosses the other way
     * (and a closed run round a body crosses a plane through it once each way, so this is half of all
     * crossings, not a corner case) the two cannot both hold, and the reading that puts the drawing where it
     * is drawn is the one that reads the section **from its other side**: mirrored, and wound the other way so
     * it is still an area. Derived, never stored, because it is a consequence of the geometry in the same way
     * the second axis itself is — freezing it would mirror a section that had merely been turned round.
     */
    class InPlaceReading(
        val anchor: Vec2,
        val fromBehind: Boolean,
        val seed: FrameSeed,
        /** Which crossing this is and how many there are — what a status line and a refusal name it by. */
        val index: Int,
        val count: Int,
    )

    /**
     * Below this |cos| between the plane's normal and the run's direction, the run **lies in** the plane where
     * it crosses it and there is no section to stand across it — a named node invalidity rather than a guess
     * (OP-3), and the only degeneracy this reading has: with the crossing's own sense taken into account the
     * seed rotation is never more than a quarter turn, so it can never be the ill-defined half one.
     */
    private const val GRAZE_EPS = 1e-9

    /**
     * The in-place reading of crossing [index] of [path] through [plane] — or null with the reason there is
     * none, in the words a refusal uses.
     *
     * **The index is taken verbatim and never re-scored** (OP-1/OP-18). Which crossing a section rides is a
     * choice made once, when the user swept it; re-deciding it on every recompute against whatever the drawing
     * has become since is precisely the defect that made fillets come back inverted. So a crossing that has
     * gone is *reported* — the node goes invalid with a reason, everything downstream hides, and it heals the
     * moment the run crosses there again — and never silently replaced by its neighbour.
     */
    fun readingAt(
        path: Path3,
        plane: Plane3,
        index: Int,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Pair<InPlaceReading?, String?> {
        val all = crossings(path, plane, tolMm)
        if (index < 0 || index >= all.size) {
            return null to
                "this section rides the run where it crosses the section's own plane, at crossing ${index + 1} — " +
                (
                    if (all.isEmpty()) {
                        "and the run does not cross that plane at all any more"
                    } else {
                        "and the run crosses it only ${count(all.size)} now"
                    }
                ) +
                "; move the run or the plane back, or sweep the section again to ride another crossing"
        }
        val hit = all[index]
        val n = plane.normal.normalized()
        val t = hit.tangent.normalized()
        val dot = n.dot(t)
        if (abs(dot) <= GRAZE_EPS) {
            return null to
                "the run lies in the section's own plane where it crosses it ${Frames3.mm(hit.s)} mm along, so " +
                "there is no direction to stand the section across — turn the plane, or pick the point of the " +
                "section that is to ride the run"
        }
        // the seed: the plane's own x axis carried onto the run by the **least** rotation there is — about
        // `normal × tangent`, by the angle between them, and by nothing else. Taking the crossing's own sense
        // (`facing`) rather than the raw tangent is what keeps that angle inside a quarter turn: a run crossing
        // against the plane's normal is not a run turned inside out, it is the same plane seen from behind.
        val facing = if (dot >= 0.0) t else t * -1.0
        val ref =
            Frames3.transport(plane.u.normalized(), n, facing)
                ?: return null to
                    "the section's plane cannot be turned onto the run where it crosses it ${Frames3.mm(hit.s)} mm " +
                    "along — turn the plane, or pick the point of the section that is to ride the run"
        return InPlaceReading(
            plane.toLocal(hit.at),
            dot < 0.0,
            FrameSeed(hit.piece, hit.t, t, ref),
            index,
            all.size,
        ) to null
    }

    /** "once" / "twice" / "3 times" — how a refusal counts crossings without reading like a log line. */
    private fun count(n: Int): String =
        when (n) {
            1 -> "once"
            2 -> "twice"
            else -> "$n times"
        }

    /**
     * The parameter of the crossing between [a] and [b], by bisection on the piece's own formula.
     *
     * Bisection rather than a Newton step because it cannot leave the bracket: the two ends are known to be on
     * opposite sides, so every iteration halves an interval that still contains a root, and the answer is a
     * parameter of the analytic piece rather than of a chord.
     */
    private fun bisect(
        el: Curve3Element,
        a: Double,
        b: Double,
        side: (Curve3Element, Double) -> Double,
    ): Double {
        fun f(t: Double): Double = side(el, t)
        var lo = a
        var hi = b
        val fLo = f(lo)
        var iter = 0
        while (abs(hi - lo) > PARAM_EPS && iter < 200) {
            val mid = 0.5 * (lo + hi)
            val fm = f(mid)
            if (fm == 0.0) return mid
            if ((fm > 0.0) == (fLo > 0.0)) lo = mid else hi = mid
            iter++
        }
        return 0.5 * (lo + hi)
    }
}
