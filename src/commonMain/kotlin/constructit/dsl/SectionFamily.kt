package constructit.dsl

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.Node
import constructit.core.PointValue
import constructit.core.RegionValue
import constructit.core.ScalarValue
import constructit.core.Value
import constructit.expr.ExprError
import constructit.expr.ExprEval
import constructit.geom.FamilyRings
import constructit.geom.Frames3
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Region
import constructit.geom.SizeLaw
import constructit.geom.SizeLaws
import constructit.geom.Skin3
import constructit.geom.SweepProfile
import constructit.geom.Vec2
import constructit.units.Dimension
import constructit.units.DimensionError
import constructit.units.Quantity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One **driven scalar of a function family**: the parameter node whose value is substituted, and the law
 * over `t` that supplies it (OP-26, session 79).
 *
 * [name] is the scalar's own name under the drawing's current naming (OP-18's naming authority) — what a
 * refusal quotes and what the step stores on the left of the `=`.
 */
class FamilyLaw(
    val name: String,
    val target: Node,
    val law: ExprLaw,
)

/**
 * **A section that is a family of sections** — one 2D drawing read once per station, with its own named
 * scalars supplied by laws over the run (OP-26, session 79 — queue entry 2, the wing's route).
 *
 * The general tier above the rigid `law=`: a rigid law scales *one* outline, and no factor turns a 200 mm
 * chord with a 12% thickness into an 80 mm chord with a 12% thickness of *that*. What does is re-reading the
 * section's own drawing with `chord` substituted per station, which is what this states.
 *
 * **What it holds is the graph; what it produces is values.** [section] is the region node whose subgraph is
 * re-evaluated, [anchor] the point of the section that rides the run (read per station under the same
 * substitutions, so a quarter-chord pivot line is a construction rather than a compensation), [laws] the
 * substitutions and [twist] the run's own turn as a law. [watched] is the section's transitive **free**
 * sources, carried as ordinary inputs of the node that takes this — see [refs].
 */
class SectionFamily(
    val section: Node,
    val anchor: Node?,
    val laws: List<FamilyLaw>,
    val twist: ExprLaw?,
    /**
     * The section's transitive free sources and parameters, as ordinary inputs of the taking node (the
     * design pass's F2).
     *
     * **Why they are inputs at all**, since the section's *region* is already one: the family reads nodes
     * that are not this node's inputs, and a memo keyed only on the region value would be relying on every
     * intermediate node handing on a fresh value object whenever anything upstream moves. That is true today
     * and it is an argument about the engine rather than about this feature, so the freedoms the family
     * actually reads are listed and the memo is sound *by construction*: any change to any of them changes
     * this node's own arguments. They cost one pointer compare each per pass and nothing else — a free
     * source is never invalid, so nothing new can ever cascade in through them either.
     */
    val watched: List<Ref<*>>,
    /**
     * Driven names the drawing carries **nothing** for — the hand-edited file's case, and the one reason a
     * family exists that cannot be built (session 79's storage rule).
     *
     * A `laws=` naming a parameter that is not in the drawing is neither a hard load error (the file would
     * not open, and OP-18 says a load reports rather than refuses) nor a silent drop (the body would be
     * built constant while the file says it tapers). So the law is kept verbatim, the *element* is invalid
     * with a reason naming the name, and the load names the element in its notes — and the moment a
     * parameter of that name exists the body comes back (OP-3).
     */
    val unresolved: List<String> = emptyList(),
) {
    /** Every ref this family adds to the taking node's inputs, in the order [envOf] reads them back. */
    val refs: List<Ref<*>> get() = laws.flatMap { it.law.refs } + (twist?.refs ?: emptyList()) + watched

    /** The named values law [i] reads, taken from [args] at the offset this family's own order gives it. */
    internal fun envOf(
        i: Int,
        args: List<Value>,
        from: Int,
    ): Map<String, Quantity> {
        var at = from
        for (k in 0 until i) at += laws[k].law.refs.size
        return laws[i].law.env(args, at)
    }

    /** The named values the twist law reads — after every driven scalar's, which is where [refs] puts them. */
    internal fun twistEnv(
        args: List<Value>,
        from: Int,
    ): Map<String, Quantity> = twist!!.env(args, from + laws.sumOf { it.law.refs.size })
}

/**
 * **The family, built** (OP-26, session 79): the rings the sweep carries and the twist law the frame reads,
 * or the reason there is no body — and every reason is node invalidity that heals (OP-3).
 *
 * The whole of the station-wise evaluation lives here, and everything it decides is decided **before a
 * triangle exists**, on grids that are the family's own and never the mesh's (session 65's law):
 *
 * - **The verdict grid is fixed** ([FAMILY_STEPS]): every per-station verdict — the count, a vanished piece,
 *   a winding flip, a self-intersection, an empty area, an invalid 2D DAG — is decided on `t = i/64`, so
 *   refining the picture can change neither a verdict nor the words it is spoken in.
 * - **The refinement grid is the family's own**: the sagitta of its *rings'* own second difference, read on
 *   that same grid ([SizeLaws.worstSecond], [SizeLaws.sagittaSpans]), rounded up to a **divisor or a
 *   multiple** of the verdict grid so that the two grids are one grid and no station is ever evaluated
 *   twice. A linear family answers zero and is carried by the two rings it needs, having been checked on
 *   sixty-five.
 * - **The tessellation count is fixed per boundary piece**, taken from the largest that piece ever is on the
 *   grid ([Skin3]'s own rule, and its `resample`): so every station's ring has the same length, a vertex is
 *   a **rail** running the length of the body, and two adjacent bands share one ring rather than agreeing
 *   about one. That is where watertightness comes from, and it is not a repair pass.
 *
 * **The count is required everywhere, and that is the discovery this feature made** (the design pass's F9).
 * The queue's premise — *structure fixed, therefore count fixed* — is false for a **computed** region: a
 * boolean's loop count is a value, so a law can genuinely change how many pieces a section has. It is
 * therefore read at the first sample and required at every one, and a station where it differs is invalid
 * **naming both counts, both stations and the law values there**, and pointing at the loft: *a family whose
 * pieces must change is a loft; a loft over computed sections is a family.*
 */
object SectionFamilies {
    /**
     * How many parametric steps every station-wise verdict is decided on — the fixed grid of the design
     * pass's F13, and [SizeLaws.STEPS]' own discipline one tier up.
     *
     * Sixty-four rather than the laws' two hundred and fifty-six because each step here is a re-evaluation
     * of a **2D drawing** rather than of one expression, and the cost of the feature is stated in exactly
     * this number (`computeCount`: one family build costs 65 evaluations of the affected cone, and a family
     * refined past that costs one per station). What it exposes is stated rather than hidden, the way
     * [SizeLaws]' own grid states it: an excursion narrower than one step of this grid is not seen here —
     * and where such an excursion reaches a *station* the sweep refuses there too, since a section that
     * really does turn inside out is no section at any density.
     */
    const val FAMILY_STEPS = 64

    /** Below this area (mm²) a station's outline encloses nothing — [Geom3]'s own threshold. */
    private const val AREA_EPS = 1e-9

    /** What a family evaluates to: the profile the sweep carries, and the twist law the frame reads. */
    class Built(
        val profile: SweepProfile.Family,
        val twist: SizeLaw?,
    )

    /**
     * [family] evaluated over the run — see this object's note for every grid and every verdict.
     *
     * [args] are the taking node's own argument values and [from] where this family's inputs start in them.
     * [runLength] is how long the run is, in mm, so that every refusal can name **where** along it a station
     * stands as well as the `t` it stands at (session 65: a refusal speaks about the drawing). [read] is how
     * the station's region is read relative to what rides the run — the anchor's own value at that station,
     * or the in-place crossing's — which is the one thing this cannot know for itself.
     */
    fun build(
        family: SectionFamily,
        args: List<Value>,
        from: Int,
        runLength: Double,
        tolMm: Double,
        read: (Region, Vec2?) -> Region,
    ): Pair<Built?, String?> {
        // ---- a name the drawing does not carry: kept, named, and healed the moment it exists ----
        if (family.unresolved.isNotEmpty()) {
            val which = family.unresolved.joinToString(", ") { "'$it'" }
            return null to
                "this section's laws drive $which, and the drawing carries no value of that name — add a " +
                "parameter called ${family.unresolved.first()} in the panel and the body comes back"
        }
        // ---- the twist law: an angle over the run, checked for readability and nothing else ----
        val twist =
            if (family.twist == null) {
                null
            } else {
                val law =
                    try {
                        SizeLaw(family.twist.ast, family.twistEnv(args, from), Dimension.ANGLE, family.twist.param, family.twist.text)
                    } catch (e: ExprError) {
                        return null to "${family.twist.what(Dimension.ANGLE)}: ${e.message}"
                    }
                SizeLaws.unreadable(law)?.let { return null to it }
                law
            }

        // ---- the laws' own environments, resolved once ----
        val envs = ArrayList<Map<String, Quantity>>(family.laws.size)
        for (i in family.laws.indices) {
            try {
                envs.add(family.envOf(i, args, from))
            } catch (e: ExprError) {
                return null to "${family.laws[i].name}($PARAM_HINT): ${e.message}"
            }
        }

        // ---- the verdict grid: one evaluation of the section per station, cached by station ----
        val stations = ArrayList<Station>(FAMILY_STEPS + 1)
        for (i in 0..FAMILY_STEPS) {
            val t = i.toDouble() / FAMILY_STEPS
            val (st, why) = stationAt(family, envs, t, runLength, tolMm, read)
            if (st == null) return null to why
            stations.add(st)
        }
        // …the count, read at the first sample and required at every one (F9)
        val first = stations.first()
        for (st in stations) {
            countDiffers(family, envs, first, st, runLength)?.let { return null to it }
        }
        // …and the shape of every one of them: a piece with no length, a winding turned over, an outline
        // that crosses itself, an area that has gone
        for (st in stations) {
            shapeDefect(family, envs, first, st, runLength, tolMm)?.let { return null to it }
        }

        // ---- the counts: one per boundary piece, the largest that piece ever is on the grid (F8) ----
        val outerCounts = IntArray(first.pieces) { 1 }
        val holeCounts = first.holes.indices.map { h -> IntArray(first.holes[h]) { 1 } }
        for (st in stations) {
            for (p in 0 until st.pieces) outerCounts[p] = max(outerCounts[p], st.outerSteps[p])
            for (h in st.holes.indices) {
                for (p in 0 until st.holes[h]) holeCounts[h][p] = max(holeCounts[h][p], st.holeSteps[h][p])
            }
        }

        // ---- the refinement: the sagitta of the rings' own motion, on the very grid they were checked on --
        val gridRings = stations.map { ringOf(it, outerCounts, holeCounts) }
        val spans = refinement(gridRings, tolMm)

        // ---- the samples: the two grids made one, so no station is ever evaluated twice ----
        val samples = ArrayList<Double>(spans + 1)
        val rings = ArrayList<FamilyRings>(spans + 1)
        for (i in 0..spans) {
            val t = i.toDouble() / spans
            samples.add(t)
            val onGrid = i.toLong() * FAMILY_STEPS % spans == 0L
            if (onGrid) {
                rings.add(gridRings[(i.toLong() * FAMILY_STEPS / spans).toInt()])
            } else {
                val (st, why) = stationAt(family, envs, t, runLength, tolMm, read)
                if (st == null) return null to why
                countDiffers(family, envs, first, st, runLength)?.let { return null to it }
                shapeDefect(family, envs, first, st, runLength, tolMm)?.let { return null to it }
                rings.add(ringOf(st, outerCounts, holeCounts))
            }
        }
        return Built(SweepProfile.Family(samples, rings), twist) to null
    }

    /** How a refusal names the station parameter, where it has no law of its own to quote. */
    private const val PARAM_HINT = "t"

    /**
     * How many spans the family asks the run to be cut into — the sagitta of every vertex's own path,
     * rounded so that the refinement grid and the verdict grid are **one** grid.
     *
     * Rounded up to a divisor of [FAMILY_STEPS] below it and to a multiple of it above, which is what keeps
     * the cost at one evaluation per station: below sixty-four every sample is a station already checked, and
     * above it every station checked is a sample. What that costs is at most a factor of two more rings than
     * the rule asks for, which errs towards drawing the drawing.
     */
    private fun refinement(
        rings: List<FamilyRings>,
        tolMm: Double,
    ): Int {
        var worst = 0.0
        val h = 1.0 / FAMILY_STEPS
        for (i in 1 until FAMILY_STEPS) {
            val a = rings[i - 1].outer
            val b = rings[i].outer
            val c = rings[i + 1].outer
            for (j in b.indices) {
                val second = (c[j] - b[j] * 2.0 + a[j]).length() / (h * h)
                if (second > worst) worst = second
            }
        }
        // the vertices' own paths are in millimetres already, so the lever arm of the sagitta rule is 1
        val want = SizeLaws.sagittaSpans(worst, 1.0, tolMm)
        if (want <= 1) return 1
        if (want >= FAMILY_STEPS) return FAMILY_STEPS * ((want + FAMILY_STEPS - 1) / FAMILY_STEPS)
        var n = 1
        while (n < want) n *= 2
        return min(n, FAMILY_STEPS)
    }

    /** One station of a family, resolved to values: the region as it is read on the run, and its counts. */
    private class Station(
        val t: Double,
        val region: Region,
        val outer: List<List<Vec2>>,
        val holeLoops: List<List<List<Vec2>>>,
        val area: Double,
    ) {
        val pieces: Int get() = outer.size
        val holes: List<Int> get() = holeLoops.map { it.size }
        val outerSteps: IntArray get() = IntArray(outer.size) { max(1, outer[it].size - 1) }
        val holeSteps: List<IntArray> get() = holeLoops.map { loop -> IntArray(loop.size) { max(1, loop[it].size - 1) } }
    }

    /**
     * The section at station [t], read under the laws' substituted values — the one place the 2D DAG is
     * re-evaluated, and the reason a fresh [Evaluator] is all it takes (see [Evaluator]'s `overrides`).
     */
    private fun stationAt(
        family: SectionFamily,
        envs: List<Map<String, Quantity>>,
        t: Double,
        runLength: Double,
        tolMm: Double,
        read: (Region, Vec2?) -> Region,
    ): Pair<Station?, String?> {
        val overrides = HashMap<Node, Value>(family.laws.size * 2)
        for (i in family.laws.indices) {
            val q =
                try {
                    valueAt(family.laws[i], envs[i], t)
                } catch (e: DimensionError) {
                    return null to "${lawText(family.laws[i])} cannot be read ${where(t, runLength)}: ${e.message}"
                } catch (e: ExprError) {
                    return null to "${lawText(family.laws[i])} cannot be read ${where(t, runLength)}: ${e.message}"
                }
            overrides[family.laws[i].target] = ScalarValue(q)
        }
        val ev = Evaluator(overrides)
        val region =
            when (val r = ev.eval(family.section)) {
                is EvalResult.Ok -> (r.value as? RegionValue)?.region
                is EvalResult.Invalid ->
                    return null to
                        "the section has no shape ${where(t, runLength)}, where ${values(family, envs, t)} — ${r.reason}"
            } ?: return null to "the section is no closed area ${where(t, runLength)}, where ${values(family, envs, t)}"
        val at =
            if (family.anchor == null) {
                null
            } else {
                when (val a = ev.eval(family.anchor)) {
                    is EvalResult.Ok -> (a.value as? PointValue)?.p
                    is EvalResult.Invalid ->
                        return null to
                            "the point the section rides on has no place ${where(t, runLength)}, where " +
                            "${values(family, envs, t)} — ${a.reason}"
                } ?: return null to "the point the section rides on is no point of the section's plane"
            }
        val read2 = read(region, at)
        val outer = read2.outer.elements.map { GeomMath.tessellatePiece(it, tolMm) }
        val holes = read2.holes.map { loop -> loop.elements.map { GeomMath.tessellatePiece(it, tolMm) } }
        val area = Geom3.polygonArea(Geom3.tessellateLoop(read2.outer, tolMm))
        return Station(t, read2, outer, holes, area) to null
    }

    /** Law [l]'s value at [t] — an ordinary expression, in whatever dimension it comes out in. */
    private fun valueAt(
        l: FamilyLaw,
        env: Map<String, Quantity>,
        t: Double,
    ): Quantity {
        val p = Quantity.number(t)
        // the station parameter is a **binder** and outranks a drawing scalar of that name — the size law's
        // own rule ([SizeLaw.at]) and the function curves' before it
        return ExprEval.eval(l.law.ast) { n -> if (n == l.law.param) p else env[n] }
    }

    /** Why [st]'s piece count is not [first]'s, naming both stations, both counts and the way out (F9). */
    private fun countDiffers(
        family: SectionFamily,
        envs: List<Map<String, Quantity>>,
        first: Station,
        st: Station,
        runLength: Double,
    ): String? {
        if (st.pieces == first.pieces && st.holes == first.holes) return null
        val what =
            if (st.pieces != first.pieces) {
                "${first.pieces} pieces ${where(first.t, runLength)} and ${st.pieces} ${where(st.t, runLength)}"
            } else {
                "${first.holes.size} holes ${where(first.t, runLength)} and ${st.holes.size} ${where(st.t, runLength)}"
            }
        return "the section has $what, where ${values(family, envs, st.t)} — a family carries one section " +
            "through the whole run, so how many pieces it has is the same everywhere. Draw the two sections " +
            "you want and skin them with *Loft (ruled)*, or split a curve with *Break* so the counts agree"
    }

    /**
     * Why [st] is not a section — a piece with no length, a winding turned over, an outline that crosses
     * itself, an area that has gone — each naming where along the run and what the laws said there.
     *
     * The **winding flip** is deliberately not normalized the way [Skin3] normalizes its drawn sections': a
     * skin's sections are separate drawings and their orientation is a fact about each one, while a family is
     * *one* drawing read many times, so a station whose area has changed sign is a section that has been
     * pulled through itself and is a fold rather than a convention.
     */
    private fun shapeDefect(
        family: SectionFamily,
        envs: List<Map<String, Quantity>>,
        first: Station,
        st: Station,
        runLength: Double,
        tolMm: Double,
    ): String? {
        for ((i, piece) in st.outer.withIndex()) {
            // **how long the piece is**, walked along its own polyline — not the distance between its ends,
            // which is zero for every *closed* piece there is (a circle's loop has exactly one, and it comes
            // back to where it started). Measuring the ends was wrong the moment a circle was a section.
            val length =
                if (piece.size < 2) {
                    0.0
                } else {
                    (1 until piece.size).sumOf { (piece[it] - piece[it - 1]).length() }
                }
            if (length <= Geom3.WELD_TOL) {
                return "piece #${i + 1} of the section has no length ${where(st.t, runLength)}, where " +
                    "${values(family, envs, st.t)} — a family carries one section through the whole run, and a " +
                    "piece that vanishes part-way along leaves it with fewer. Hold that piece off zero, or " +
                    "draw the two sections you want and skin them with *Loft (ruled)*"
            }
        }
        if (abs(st.area) <= AREA_EPS) {
            return "the section encloses no area ${where(st.t, runLength)}, where ${values(family, envs, st.t)} — " +
                "there is nothing to sweep there"
        }
        if (st.area * first.area < 0.0) {
            return "the section turns inside out ${where(st.t, runLength)}, where ${values(family, envs, st.t)} — " +
                "it runs the other way round there than it does at the start of the run, so the body would be " +
                "folded through itself. Hold the section's own size off zero"
        }
        crossing(Geom3.tessellateLoop(st.region.outer, tolMm))?.let { (a, b) ->
            return "the section's outline crosses itself ${where(st.t, runLength)}, where " +
                "${values(family, envs, st.t)} — the edge from corner #${a + 1} meets the one from corner " +
                "#${b + 1}, so the body would pass through itself. Hold the section's own dimensions apart"
        }
        return null
    }

    /**
     * Which two edges of a closed outline **cross**, or null when none do — the 2D twin of
     * [Geom3.crossingRails], and the one criterion of this family that is genuinely new.
     *
     * A segment-pair sweep over the station's own ring: `O(n²)` on a ring of a few dozen corners, decided
     * once per station of the fixed verdict grid and never during a repaint. Neighbours are skipped, since
     * two edges that share a corner meet there by construction.
     */
    private fun crossing(ring: List<Vec2>): Pair<Int, Int>? {
        val n = ring.size
        if (n < 4) return null
        for (i in 0 until n) {
            val a0 = ring[i]
            val a1 = ring[(i + 1) % n]
            for (j in i + 1 until n) {
                if (j == i || (j + 1) % n == i || (i + 1) % n == j) continue
                val b0 = ring[j]
                val b1 = ring[(j + 1) % n]
                if (segmentsCross(a0, a1, b0, b1)) return i to j
            }
        }
        return null
    }

    /** Whether the two segments cross **properly** — sharing an endpoint is not a crossing. */
    private fun segmentsCross(
        a0: Vec2,
        a1: Vec2,
        b0: Vec2,
        b1: Vec2,
    ): Boolean {
        val d1 = a1 - a0
        val d2 = b1 - b0
        val den = d1.cross(d2)
        if (abs(den) <= 1e-12) return false
        val s = (b0 - a0).cross(d2) / den
        val u = (b0 - a0).cross(d1) / den
        val eps = 1e-9
        return s > eps && s < 1.0 - eps && u > eps && u < 1.0 - eps
    }

    /** One station's ring, at the family's own fixed counts — one shared point list, [Skin3]'s rule. */
    private fun ringOf(
        st: Station,
        outerCounts: IntArray,
        holeCounts: List<IntArray>,
    ): FamilyRings =
        FamilyRings(
            sampled(st.outer, outerCounts),
            st.holeLoops.mapIndexed { h, loop -> sampled(loop, holeCounts.getOrElse(h) { IntArray(loop.size) { 1 } }) },
        )

    /**
     * The pieces of one loop resampled to their stated counts and strung into **one** point list — so the
     * corner where two pieces meet is one vertex rather than two that agree ([Skin3]'s `ring2` rule, which
     * is where a swept family's watertightness comes from).
     */
    private fun sampled(
        pieces: List<List<Vec2>>,
        counts: IntArray,
    ): List<Vec2> {
        val out = ArrayList<Vec2>()
        for ((p, piece) in pieces.withIndex()) {
            val n = counts.getOrElse(p) { 1 }
            val re = Skin3.resample(piece, n)
            for (j in 0 until n) out.add(re[j])
        }
        return out
    }

    /** How a refusal names a station: the `t` it stands at and how far along the run that is (session 65). */
    private fun where(
        t: Double,
        runLength: Double,
    ): String = "${Frames3.mm(t * runLength)} mm along the run (t = ${Frames3.mm(t)})"

    /** What the laws said at [t] — `chord = 200 mm, twist = 7.5°`, so a refusal can be checked. */
    private fun values(
        family: SectionFamily,
        envs: List<Map<String, Quantity>>,
        t: Double,
    ): String {
        if (family.laws.isEmpty()) return "the section is read as it is drawn"
        return family.laws.indices.joinToString(", ") { i ->
            val v =
                try {
                    printed(valueAt(family.laws[i], envs[i], t))
                } catch (_: DimensionError) {
                    "no value"
                } catch (_: ExprError) {
                    "no value"
                }
            "${family.laws[i].name} = $v"
        }
    }

    /** A quantity as a refusal prints it — millimetres, degrees or a bare number. */
    private fun printed(q: Quantity): String =
        when (q.dim) {
            Dimension.LENGTH -> "${Frames3.mm(q.base)} mm"
            Dimension.ANGLE -> "${Frames3.deg(q.base)}°"
            else -> Frames3.mm(q.base)
        }

    /** How a refusal quotes one law — `chord(t) = 200mm * (1 - 0.6*t)`. */
    private fun lawText(l: FamilyLaw): String = "${l.name}(${l.law.param}) = ${l.law.text}"
}
