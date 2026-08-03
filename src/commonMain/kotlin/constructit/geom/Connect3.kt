package constructit.geom

/**
 * Which **end** of a curve in space a connection is made at (OP-26, step 7) — the one discrete choice the
 * *Connect* gesture carries, and structural for OP-1's own reason.
 *
 * A curve has two ends and a click says which. That is a **choice**, not a measurement, so it is scored once
 * from the click that made it and thereafter taken verbatim from the step's `signs=` (OP-18) — the identical
 * treatment the kept side of a chain cut and the chosen curve of an intersection already get. Re-scoring on
 * reload would make the joining piece jump to the other end of a run as soon as an edit moved the curve past
 * the remembered click, which is the fillets-came-back-inverted defect one dimension up.
 */
enum class CurveEnd {
    START,
    END,
    ;

    /** The word a refusal and a status line use. */
    val word: String get() = if (this == START) "start" else "end"

    /** The parameter of this end on the piece that owns it. */
    val t: Double get() = if (this == START) 0.0 else 1.0

    /**
     * How the curve's own tangent turns into the direction the join **leaves in**.
     *
     * `+1` at the [END], where the connection continues the way the run was already going; `−1` at the
     * [START], where the connection runs **against** the curve's parameter direction. This single sign is the
     * whole of the direction rule, and it is why a piece that doubles back onto the curve it joins cannot be
     * produced: the joining piece always leaves *away* from the curve it leaves.
     */
    val outSign: Double get() = if (this == START) -1.0 else 1.0
}

/**
 * How smoothly a connection meets the two curves it joins (OP-26, step 7) — *"G1, then G2 as a mode"*, and
 * the mode is stated by **which tool was used** (OP-18), exactly as a helix's handedness is.
 */
enum class Continuity(
    /** How many cubic pieces the joining run is made of — see [Connect3]. */
    val spans: Int,
    /** The word a status line uses. */
    val word: String,
) {
    /** Position and **tangent** matched at both ends: one cubic. */
    G1(1, "G1"),

    /** Position, tangent and **curvature** matched at both ends: three cubics, C2 among themselves. */
    G2(3, "G2"),
}

/**
 * **Connect** (OP-26, step 7): the joining piece between the end of one curve in space and the end of
 * another, derived from the two endpoint tangents plus two stated tensions.
 *
 * What it is for is the sentence the order of work uses: it is what makes a routed run look *manufactured*
 * rather than kinked. Two lengths of conduit that meet at a corner meet at a corner; a bend joins them, and
 * the bend is not a third thing somebody draws — it is a consequence of where the two runs end and which way
 * they are pointing when they get there.
 *
 * ### It is a formula, not a search
 *
 * The classical construction, and the reason it belongs in a kernel with no solver: the joining piece is a
 * **cubic Bézier whose two outer control points are the two curve ends** and whose two inner ones stand
 * **along the endpoint tangents**, at distances the tensions state. A Bézier leaves its first control point
 * along `b1 − b0` and arrives at its last along `b3 − b2`, so tangent continuity with both curves is *true by
 * construction* — the same category of fact as "an interpolating spline passes through its points", and the
 * same category the fillet's tangency is. Nothing is asserted after the fact, nothing is iterated, and there
 * is no configuration to search for: given the two ends, the two tangents and the two numbers, there is
 * exactly one answer and it is written down below.
 *
 * ### Which end, and which way the tangent points
 *
 * A curve has two ends, so the connection has to say which one it joins ([CurveEnd]) — a discrete choice,
 * persisted, never re-scored. From it follows the only sign in the whole construction: the joining piece
 * leaves each end along the direction that points **away from that curve** — the curve's own tangent at its
 * [CurveEnd.END], the **reversed** tangent at its [CurveEnd.START], because joining a curve's start means
 * running against its parameter direction. Getting that sign wrong gives a piece that doubles back on the
 * very run it joins, and stating the rule as *away from the curve* is what makes that unrepresentable rather
 * than merely unlikely.
 *
 * The curvature the G2 mode matches needs **no** such sign: `κ·N` is invariant under reversing a curve
 * ([Curves3.curvatureVectorAt]), so only the tangent has a direction to get right.
 *
 * ### The tensions, and why 1 is the default
 *
 * Two ordinary scalars, **dimensionless**, and what they scale is stated once for both modes: the joining
 * run, read over its own parameter `[0, 1]`, leaves each end at speed `tension × gap`, where `gap` is the
 * straight-line distance between the two ends. So a tension is a **fraction of the gap** — which is why it is
 * a plain number and not a length: scale the whole drawing and the connection scales with it, and a tension
 * wired to a parameter keeps meaning the same thing after the two runs have moved apart.
 *
 * **The default is 1, and it is the value at which the connection reproduces the obvious answer exactly.**
 * When the two ends face each other along the gap, tension 1 puts the inner control points at exactly a third
 * and two thirds of the way across, so the joining piece *is* the straight segment between them, uniformly
 * parameterized — in **both** modes, which is what makes the two comparable. It is also, and not by
 * coincidence, the constant this kernel's own interpolating spline already uses: [Curves3.smoothThrough]
 * places its control points at `m/3` with the chord for `m` at an open end, so a connection at tension 1
 * between two ends a chord apart is the curve *Smooth curve through points* would have drawn through them.
 * One constant, one reading, stated in two places rather than invented twice.
 *
 * Raising a tension pulls the piece **towards that curve's tangent** — it leaves along it for longer before
 * turning — which is the whole of what the number does and is asserted as such.
 *
 * ### G2 is exact, and it costs three cubics rather than a new case
 *
 * A single cubic cannot carry curvature at both ends: once the four control points have spent themselves on
 * two positions and two tangent directions, the two tensions are all that is left, and a curvature vector is
 * two more conditions at each end. The textbook answer is a **quintic**, and this vocabulary has no quintic —
 * `Curve3Element`'s only free-form case is a cubic, and a case is added with the producer that needs *it*
 * (OP-26's own rule for this hierarchy).
 *
 * It does not need one. A **chain** of cubics carries what one cubic cannot, and the count that makes the
 * system square is three:
 *
 * - each end fixes three of its span's control points — the position, the first inner point (the tangent
 *   direction and the tension), and the second inner point (the curvature);
 * - the two interior joins are C1 **and** C2 in the run's own parameterization, which is six more vector
 *   equations;
 * - that is ten control points constrained by ten vector conditions. Two cubics would be over-determined by
 *   one once both tensions are stated, and four would be under-determined and so would need a choice nobody
 *   made. **Three is the count at which the answer exists and is unique** — and being square and linear, it
 *   is a substitution rather than a solve: the formulas below are written out, in order, with nothing to
 *   invert.
 *
 * So the G2 mode is **exact, at zero tolerance**, and it needed nothing added to the vocabulary. It is not a
 * fitted chain with a stated error (step 5's other answer) and it is not cut: it is the same closed form the
 * G1 mode is, over three pieces instead of one. The piece is C2 with itself as well as with both curves,
 * which is worth stating because a joining run that was G2 at its ends and kinked in curvature in the middle
 * would be a worse object than the G1 one it replaced.
 *
 * **One number in it is a choice and is named as such**: the component of the end acceleration *along* the
 * tangent is set to **zero**. Nothing states it — the curvature fixes only the perpendicular part — and zero
 * is the value that says nothing: the run leaves at constant speed to second order, which is what keeps the
 * straight case exactly the straight segment and keeps the two modes agreeing there.
 *
 * ### What it refuses, and where
 *
 * Every one of the failures is a property of **values**, so every one is node invalidity with a named reason
 * that heals (OP-3, and *The station*'s rule that a gesture refused on a value makes replay depend on one):
 * the two ends standing in the same place, a **closed** run (which has no end to join — and for a *derived*
 * curve closure is itself a value, since a plane sliding across a body cuts it in an open run or a loop), a
 * curve with no direction at the end asked for, a curvature the G2 mode cannot read there, and a **tension of
 * zero or less**. Only the structural things — a pick that is not a curve in space, and the same end of the
 * same curve clicked twice — are refused by the gesture, in `Document.connectCurves`.
 */
object Connect3 {
    /**
     * What the connection reads at one joined end: where it is, the direction the joining piece **leaves**
     * in, and the curvature the G2 mode matches there.
     *
     * Three numbers off the curve and nothing else — which is the whole of why this is a derivation. Nothing
     * about the rest of either run enters the answer, so moving a curve's far end changes the connection only
     * through what it does to the end being joined.
     */
    data class Landing(
        val at: Vec3,
        /** Unit, and pointing **away** from the curve — see [CurveEnd.outSign]. */
        val out: Vec3,
        /** `κ·N` there, null when the piece has no direction and therefore no normal. */
        val curvature: Vec3?,
    )

    /**
     * The joining run between [endA] of [a] and [endB] of [b] at the stated tensions, or null with the reason
     * there is none.
     *
     * The run goes **from the first curve's end to the second's**, which is the reading a user can predict
     * from the order of the two clicks, and is what a station's distance along it and a sweep's frame are
     * measured from.
     */
    fun connected(
        a: Path3,
        endA: CurveEnd,
        tensionA: Double,
        b: Path3,
        endB: CurveEnd,
        tensionB: Double,
        mode: Continuity,
    ): Pair<Path3?, String?> {
        val (la, whyA) = landingOf(a, endA, "first")
        if (la == null) return null to whyA
        val (lb, whyB) = landingOf(b, endB, "second")
        if (lb == null) return null to whyB
        for ((t, which) in listOf(tensionA to "first", tensionB to "second")) {
            if (t <= 0.0) {
                return null to
                    "the $which tension is ${Frames3.mm(t)}, and a tension is how far the join runs on along " +
                    "that curve's own direction before it turns — nothing, or backwards, is no join at all; " +
                    "1 leaves it a third of the gap, which is a straight run when the two ends face each other"
            }
        }
        val gap = (lb.at - la.at).length()
        if (gap <= Vec3.EPS) {
            return null to
                "the two ends being joined are in the same place, so there is no gap to bridge — move one of " +
                "the runs, or join their other ends"
        }
        // the speed at each end in the joining run's own parameterization over [0, 1]: a tension is a
        // fraction of the gap, so the whole construction scales with the drawing
        val sA = tensionA * gap
        val sB = tensionB * gap
        // one span or three, and the first inner control point stands at speed/(3·spans) either way — the
        // per-span derivative is the run's own divided by the span count, which is what keeps "tension 1 is
        // the straight segment" true in both modes
        val reach = 1.0 / (3.0 * mode.spans)
        val b0 = la.at
        val b1 = b0 + la.out * (sA * reach)
        val bLast = lb.at
        val bLastInner = bLast + lb.out * (sB * reach)
        if (mode == Continuity.G1) return Path3(listOf(Curve3Element.Bezier3(b0, b1, bLastInner, bLast))) to null

        val kA = la.curvature ?: return null to curvatureless("first", endA)
        val kB = lb.curvature ?: return null to curvatureless("second", endB)
        // The second control point of each end span carries the curvature. In that span's own parameter the
        // second derivative is 6(b0 − 2b1 + b2) and the run's is nine times it (three spans over [0, 1]), and
        // what it must be is κ·s² — the curvature times the squared speed — with **no** component along the
        // tangent (see this object's note). So b2 = 2b1 − b0 + κs²/54, and the far end is its mirror.
        val b2 = b1 * 2.0 - b0 + kA * (sA * sA / 54.0)
        val b7 = bLastInner * 2.0 - bLast + kB * (sB * sB / 54.0)
        // …and the four inner control points are what C1 and C2 at the two joins then force. Eliminating b3,
        // b5 and b6 leaves one equation for b4, and everything else is a substitution back into it.
        val b4 = (b1 * -2.0 + b2 * 4.0 + b7 * 2.0 - bLastInner) * (1.0 / 3.0)
        val b3 = (b2 + b4) * 0.5
        val b5 = b1 - b2 * 2.0 + b4 * 2.0
        val b6 = (b5 + b7) * 0.5
        return Path3(
            listOf(
                Curve3Element.Bezier3(b0, b1, b2, b3),
                Curve3Element.Bezier3(b3, b4, b5, b6),
                Curve3Element.Bezier3(b6, b7, bLastInner, bLast),
            ),
        ) to null
    }

    /** What [end] of [path] offers a connection, or null with the reason it offers none. */
    fun landingOf(
        path: Path3,
        end: CurveEnd,
        which: String,
    ): Pair<Landing?, String?> {
        if (path.isEmpty) return null to "the $which curve has no pieces, so it has no end to join"
        if (path.closed) {
            return null to
                "the $which curve is a closed run — it comes back to where it started, so it has no end to " +
                "join; connect two open runs, or open this one"
        }
        val el = if (end == CurveEnd.START) path.elements.first() else path.elements.last()
        val tangent =
            Curves3.tangentAt(el, end.t)
                ?: return null to
                    "the $which curve has no direction at its ${end.word}, so there is nothing for the join to " +
                    "leave along"
        return Landing(
            at = if (end == CurveEnd.START) el.start else el.end,
            out = tangent * end.outSign,
            curvature = Curves3.curvatureVectorAt(el, end.t),
        ) to null
    }

    private fun curvatureless(
        which: String,
        end: CurveEnd,
    ): String =
        "the $which curve has no curvature at its ${end.word} — it stands still there, so there is nothing " +
            "for a curvature-continuous join to match; connect them G1 instead"
}
