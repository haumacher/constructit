package constructit.geom

import constructit.expr.Expr
import constructit.expr.ExprError
import constructit.expr.ExprEval
import constructit.units.Dimension
import constructit.units.DimensionError
import constructit.units.Quantity
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
 * **How a swept section's size varies with the station** (OP-26, the variable-section sweep — session 77):
 * one [Expr] over the dimensionless run parameter [param], which is `t` running 0 → 1 along the whole run.
 *
 * The same AST, the same dimension check and the same evaluator the function curves already use
 * ([constructit.geom.FuncCurve]), which is the whole reason this feature became statable at all: session 42
 * parked the variable-section sweep as *"the only thing that would relax the single derived reach"* with no
 * way to **say** that a section changes size, and the expression language is that missing vocabulary.
 *
 * **`t` is the same letter and the same contract the function curves carry** — a *binder*, context-local,
 * outranking any drawing scalar of that name (see [at]) — and it rides the sampled **arc-length** map: one
 * `t` over the whole run, by arc length, never one per piece (per-piece native parameters do not survive a
 * multi-piece run). That is the metric tier, and it is consistent rather than a concession: a swept surface
 * is already the approximated tier by nature (`SWEEP_ONLY`, no analytic faces).
 *
 * **What varies is rigid-per-station scaling and nothing else.** A tube's radius is a law of dimension
 * [Dimension.LENGTH]; an arbitrary section's uniform scale about its anchor is a law of dimension
 * [Dimension.NONE]. One mechanism serves both, and neither is ever a re-evaluation of the section's own
 * sketch — a whole 2D DAG per station is a *function family of regions*, which is recorded as the future
 * extension it is and wants its own design.
 *
 * [env] holds the named scalars the text reads, resolved to values by the node that built this — so a
 * parameter a law reads is an ordinary DAG input and editing it re-tapers the body by the recompute every
 * other edit uses. [text] is the law under the **current** names of what it reads (OP-18's naming
 * authority), which is what a refusal quotes and what the step stores verbatim.
 */
data class SizeLaw(
    val expr: Expr,
    val env: Map<String, Quantity>,
    /** [Dimension.LENGTH] for a tube's `r(t)`, [Dimension.NONE] for a section's `scale(t)`. */
    val dim: Dimension,
    val param: String = "t",
    val text: String = "",
) {
    /**
     * The law's value at station [t], in base units — mm for a radius law, a plain factor for a scale law.
     *
     * Throws [DimensionError] or [ExprError] exactly as the panel's own formula field does, which is what
     * keeps an angle-valued radius the ordinary named invalidity that heals (OP-3) rather than a special case.
     */
    fun at(t: Double): Double {
        val p = Quantity.number(t)
        // the parameter **shadows** a drawing scalar of the same name: it is a binder, the way a lambda's
        // argument is — the function curves' own rule ([constructit.geom.FuncCurves]), read one feature on
        val q = ExprEval.eval(expr) { n -> if (n == param) p else env[n] }
        if (q.dim != dim) throw DimensionError("a section's size must be ${word()}, and this is ${q.dim}")
        return q.base
    }

    /** How a refusal names this law — `r(t) = 5mm * (1 - t/2)`. */
    fun what(): String = "${if (dim == Dimension.LENGTH) "r" else "scale"}($param) = $text"

    private fun word(): String = if (dim == Dimension.LENGTH) "a length" else "a plain number"

    /** The word a refusal uses for the thing that has to stay positive. */
    internal fun subject(): String = if (dim == Dimension.LENGTH) "a tube needs a positive radius" else "a swept section needs a positive scale"

    /** How a value of this law prints in a refusal — with its unit where it has one. */
    internal fun value(v: Double): String = if (dim == Dimension.LENGTH) "${Frames3.mm(v)} mm" else Frames3.mm(v)
}

/**
 * The **law's own arithmetic**: is it positive over the whole run, and how large does the section ever get
 * (OP-26, session 77).
 *
 * **The grid is the law's own and fixed** ([STEPS]), for the reason [Embedding]'s bend term's is: a refusal
 * is a claim about the *drawing*, so nothing that changes when the same drawing is meshed more finely may
 * change what refuses (the session-65 rule). It is the identical device
 * [constructit.geom.FuncCurves.VALIDATE_STEPS] already uses to decide that a function curve's expressions
 * stay on their own domain over a whole span, and it carries the same stated exposure: an excursion narrower
 * than one grid step is not seen here. Where such an excursion reaches a **station** the sweep refuses there
 * too rather than building a fold — a body whose radius really does go non-positive is not a body at any
 * density, so that is the honest side of the same line.
 */
object SizeLaws {
    /**
     * How many parametric steps a law is checked on before the sweep is built — both ends included.
     *
     * [constructit.geom.FuncCurves.VALIDATE_STEPS]'s own number, deliberately: it is the same question asked
     * of the same expression language over the same kind of dimensionless span, so there is one resolution
     * for "does this formula stay sane over its domain" rather than two.
     */
    const val STEPS = 256

    /**
     * Why [law] is not a size over the whole run, naming the station and the value there — or null when it is.
     *
     * Worded on the constant refusal's own model (*"a tube needs a positive radius — this one is 0 mm"*),
     * which heals per OP-3: move the parameter the law reads and the body comes back.
     */
    fun invalidity(law: SizeLaw): String? {
        for (i in 0..STEPS) {
            val t = i.toDouble() / STEPS
            val v =
                try {
                    law.at(t)
                } catch (e: DimensionError) {
                    return "${law.what()} cannot be read at ${law.param} = ${Frames3.mm(t)}: ${e.message}"
                } catch (e: ExprError) {
                    return "${law.what()} cannot be read at ${law.param} = ${Frames3.mm(t)}: ${e.message}"
                }
            if (v <= Geom3.WELD_TOL) {
                return "${law.subject()} — ${law.what()} is ${law.value(v)} at ${law.param} = ${Frames3.mm(t)} along the run"
            }
        }
        return null
    }

    /**
     * The largest the section ever is along the run, as a dimensionless factor — read on the same fixed grid
     * [invalidity] is, and never on the stations.
     *
     * What it is *for* is the sampling refinement and the proximity grid's cell size, both of which need one
     * number that no station outgrows. Reading it off the stations would make the mesh decide it, and a mesh
     * that decided a criterion's own resolution is what the session-65 rule forbids.
     */
    fun maxScale(profile: SweepProfile): Double {
        profile.law ?: return 1.0
        var k = 0.0
        for (i in 0..STEPS) {
            // A sample the law cannot evaluate is skipped rather than thrown: every route that *builds*
            // asks [invalidity] first, so nothing here is ever reached with a broken law — and the one route
            // that does not (a plan hint rebuilt for a moved body, `Geom3.sweptPlan`) is a hint and must not
            // throw out of a repaint.
            val v =
                try {
                    profile.scaleAt(i.toDouble() / STEPS)
                } catch (_: DimensionError) {
                    continue
                } catch (_: ExprError) {
                    continue
                }
            if (v > k) k = v
        }
        return if (k > 0.0) k else 1.0
    }

    /**
     * How many **spans the run needs** for the law itself to be drawn to [tolMm], or 0 where it needs none.
     *
     * The reason it is asked at all: a straight piece is one span (a chord of a line *is* the line), so a
     * horn stated on a straight run would come out as a **cone** — the two rings at the ends joined by one
     * band, with the law's own curve between them lost. That is not a picture of what the drawing says, so
     * the station count is refined for the law exactly as it already is for the twist ([Frames3.along]).
     *
     * The rule is the sagitta rule everything else here uses: a section point [reach] mm off the axis rides
     * `reach · k(t)`, so over a span of `1/n` of the run its chord falls short of the law by
     * `reach · |k″| / (8 n²)`, and `n` is what makes that the tolerance. `k″` is read off the law's **own**
     * fixed grid ([STEPS]) — a linear law answers exactly zero and is drawn with the two rings it needs, a
     * quadratic answers its constant, and nothing about the answer is the mesh's.
     *
     * This is a *compute-time* decision and a pure function of values (OP-21's rule), so a reload rebuilds
     * the identical mesh.
     */
    fun spans(
        profile: SweepProfile,
        reach: Double,
        tolMm: Double,
    ): Int {
        profile.law ?: return 0
        if (reach <= 0.0 || tolMm <= 0.0) return 0
        val h = 1.0 / STEPS
        var worst = 0.0
        for (i in 1 until STEPS) {
            val a = at(profile, (i - 1) * h) ?: continue
            val b = at(profile, i * h) ?: continue
            val c = at(profile, (i + 1) * h) ?: continue
            val second = abs(c - 2.0 * b + a) / (h * h)
            if (second > worst) worst = second
        }
        if (worst <= 0.0) return 0
        return ceil(sqrt(reach * worst / (8.0 * tolMm))).toInt().coerceIn(0, MAX_SPANS)
    }

    /** How far the law's own refinement will ever go — a bound, so a wild formula cannot mesh for ever. */
    private const val MAX_SPANS = 4096

    /** [profile]'s scale at [t], or null where the law cannot be read there (see [maxScale]). */
    private fun at(
        profile: SweepProfile,
        t: Double,
    ): Double? =
        try {
            profile.scaleAt(t)
        } catch (_: DimensionError) {
            null
        } catch (_: ExprError) {
            null
        }

    /**
     * The scale each station of [frame] carries, or the reason one of them has none — the per-station reading
     * every refusal criterion below is generalized to (OP-26, session 77's ruling (c)).
     *
     * `t` is the station's own arc length over the run's, which is the arc-length map stated once here so
     * that the mesh, the plan hint and all three refusal terms read the identical number.
     */
    fun scalesAlong(
        profile: SweepProfile,
        frame: MovingFrame,
    ): Pair<List<Double>?, String?> {
        val law = profile.law ?: return null to null
        val span = if (frame.length > Geom3.WELD_TOL) frame.length else 1.0
        val out = ArrayList<Double>(frame.stations.size)
        for (st in frame.stations) {
            val t = (st.s / span).coerceIn(0.0, 1.0)
            val v =
                try {
                    profile.scaleAt(t)
                } catch (e: DimensionError) {
                    return null to "${law.what()} cannot be read at ${law.param} = ${Frames3.mm(t)}: ${e.message}"
                } catch (e: ExprError) {
                    return null to "${law.what()} cannot be read at ${law.param} = ${Frames3.mm(t)}: ${e.message}"
                }
            if (v <= 0.0) {
                return null to
                    "${law.subject()} — ${law.what()} is ${law.value(law.at(t))} at ${law.param} = " +
                    "${Frames3.mm(t)} along the run"
            }
            out.add(v)
        }
        return out to null
    }
}

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
 *
 * [law] is how the section's **size varies with the station** (OP-26, session 77) and null is a section of
 * one size — the reading every drawing written before this one keeps for ever, down to the node's inputs and
 * the step's own words.
 */
sealed interface SweepProfile {
    /** This profile as an ordinary 2D region, in the moving frame's own coordinates. */
    val region: Region

    /** How this section's size varies along the run, or null for a section of one size. */
    val law: SizeLaw?

    /**
     * The dimensionless factor the section is scaled by, about its anchor, at station [t] — exactly 1.0
     * where there is no law, so a constant section is carried by the identical arithmetic it always was.
     */
    fun scaleAt(t: Double): Double

    /** A circle of [radius] centred on the path — the tube, and what proves the frame. */
    data class Round(val radius: Double, override val law: SizeLaw? = null) : SweepProfile {
        override val region: Region
            get() = Region(Loop(listOf(ProfileElement.CircleE(Circle(Vec2(0.0, 0.0), radius)))), emptyList())

        /**
         * A radius law is a **length**, so the factor is the law over the radius the region was built at —
         * which is the law's own value at the start ([of]). The division is safe because a non-positive
         * radius anywhere on the run, `t = 0` included, is refused before a station is asked (see
         * [SizeLaws.invalidity] and [Geom3.sweep]).
         */
        override fun scaleAt(t: Double): Double = law?.let { it.at(t) / radius } ?: 1.0
    }

    /** An arbitrary closed area, drawn in a sketch space and read in the frame's coordinates. */
    data class Section(override val region: Region, override val law: SizeLaw? = null) : SweepProfile {
        override fun scaleAt(t: Double): Double = law?.at(t) ?: 1.0
    }

    companion object {
        /**
         * A tube whose radius is [law] — the circle is built at the law's value **at the start of the run**,
         * and every station scales that ([Round.scaleAt]).
         *
         * The start rather than, say, the largest value, because *the tube's radius* is a number a fitter can
         * order and the one he orders is the one the run begins with; and because it keeps
         * `(path, profile, up, roll, twist)` enough to rebuild the identical body (OP-9) with the law as one
         * more field of the profile rather than a second parameter beside it.
         */
        fun of(law: SizeLaw): Round = Round(law.at(0.0), law)
    }
}

/**
 * **How a section is carried along a run** — the sweep's one discrete mode (OP-22's extension, step 2).
 *
 * The two are the two honest answers to *"and what does the section do while it travels"*, and they
 * **coincide exactly** while the run is straight, which is why the mode arrives with the curved directrix
 * and not before: with nothing to turn about there is nothing to distinguish.
 *
 * - [ROTATING] — the section rides the moving frame ([Frames3]) and turns with the run, so it stays
 *   perpendicular to it. This is what a swept solid does and what *"the cut follows the feature"* means.
 * - [TRANSLATIONAL] — the section stays **parallel to its own space** and only its origin travels, so what
 *   moves along the run is a rigid translation. This is the mitre-free reading: a slot cut by a saw held at
 *   one angle while the work is moved along a curve.
 *
 * It is **structural** in exactly the sense [Handedness] is (OP-1, OP-21): a discrete choice, decided by the
 * construction that states it — in the editor by which tool row was used — recorded in the step and never
 * inferred from geometry that could drift.
 */
enum class CarryMode {
    ROTATING,
    TRANSLATIONAL,
    ;

    /** The word a status line and a refusal use. */
    val word: String get() = if (this == ROTATING) "rotating" else "translational"
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
 *
 * [bend] is **which way** that curvature bends: the unit direction from the station towards its centre of
 * curvature, read from the same analytic piece and zero where the piece is straight. It is carried beside the
 * magnitude because the local half of the embedding criterion is a statement about the section's reach *in
 * that direction* and not about its reach at all ([Embedding.check]) — a foundation standing wholly outside a
 * bend cannot fold through the inside of it, however wide it is.
 *
 * [corner] is whether this station is a **corner of the curve** — a place where the run's own tangent jumps,
 * which is a property of the *path* and never of how finely it was sampled. It is read from the analytic
 * pieces either side (see [Frames3]) and not from the chords, and it is what [Embedding.cornerFold] keys on:
 * a mitre trims a band only where there is a real turn to mitre, and a station sampled along a smooth bend
 * has no mitre of its own however sharply the bend turns. That distinction is the whole of the answer to
 * *"do the sweep's refusals speak about the curve or about the mesh"* — see [Embedding].
 */
data class Frame3(
    /** Arc length from the path's start, along the sampled spine — what a refusal names a station by. */
    val s: Double,
    val at: Vec3,
    val tangent: Vec3,
    val ref: Vec3,
    val mitre: Vec3,
    val curvature: Double,
    /** Unit, towards the centre of curvature — `Vec3.ZERO` where the piece is straight. */
    val bend: Vec3 = Vec3.ZERO,
    /** Whether the *curve* turns discontinuously here — a join of two pieces whose tangents differ. */
    val corner: Boolean = false,
    /**
     * **Where on the path this station was sampled** — the piece and its parameter — or null for a station
     * that no piece stands behind (a run-on beyond an open route's end, `Chains.runOn`).
     *
     * Carried per station rather than in one list beside them (GitHub #20), and that is the point: a caller
     * may take a **sub-run** of the stations, rotate a closed one about a new start, or add synthetic ones at
     * the ends, and a parallel list would silently fall out of step with any of those. Held here, the
     * position on the analytic curve travels with the station it belongs to, which is what lets
     * [Embedding.cornerFold] ask the *curve* what it does either side of a corner instead of asking the
     * chords the spine walks.
     */
    val param: Pair<Int, Double>? = null,
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
 * **Where the frame is stated from**, when it is not started at the run's beginning (OP-26, the in-place
 * sweep).
 *
 * A rotation-minimizing frame is one stated direction carried along the whole run, and *where* it is stated is
 * free: transport is reversible, so seeding the frame at a crossing part-way along and carrying it **both
 * ways** is the same rule, read from a different place. That is what lets a section drawn in a plane the run
 * pierces come out as the run's own section *there* — the drawing is the statement, and the rest of the run
 * follows it.
 *
 * [piece] and [t] say where along the path the seed stands, in the path's own vocabulary rather than in an arc
 * length, so nothing has to agree about how the spine was sampled. [tangent] is the **analytic** direction
 * there — the reference is carried across one last step from it onto the chord the spine actually walks, which
 * is [Stations3]'s rule, reached rather than re-derived. [ref] is the direction that is to stand as the
 * frame's reference at that point.
 */
data class FrameSeed(
    val piece: Int,
    val t: Double,
    val tangent: Vec3,
    val ref: Vec3,
)

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
    /**
     * The reference direction the first span was carried with — what [Frames3.startReference] returned, or,
     * where the frame was **seeded** part-way along ([FrameSeed]), the seed carried back to the start.
     *
     * Reported rather than kept private because it is what keeps a swept feature self-contained (OP-9,
     * [Feature3.Sweep]): `startReference(firstChord, this)` is this direction again, so a body rebuilt from
     * `(path, profile, up, roll, twist)` alone rebuilds the identical frame — seeded or not — and neither a
     * placement's re-projection nor a plan hint needs to know where the statement was made.
     */
    val startRef: Vec3,
    /**
     * The **analytic path** these stations were sampled from, where the frame's owner has one (GitHub #20).
     *
     * What [Embedding.cornerFold]'s bend term reads, together with [Frame3.param]: a corner's mitre is the
     * trim two *straight* tubes make of each other, so whether it is honest depends on what the run does over
     * the stretch the trim reaches — and that is a question for the curve, not for the spine that approximates
     * it. Optional, because a frame may be assembled from stations directly (a swept cut's run-on beyond the
     * body), and a frame with no path simply has no bend term to answer.
     */
    val path: Path3? = null,
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
     * **[lawSpans] is the run's own refinement for a varying section** (OP-26, session 77): a section whose
     * size is a law of the station has a *shape between the stations*, so a straight piece — one span by
     * nature — has to be cut into as many as the law needs to be drawn to [tolMm] ([SizeLaws.spans]). Each
     * piece takes its share of them, by arc length, since `t` is the arc-length map. Zero for every constant
     * section, which is what keeps every station count and every mesh this ever produced identical.
     *
     * **[seed] states the frame part-way along instead of at the start** (the in-place sweep). The transport
     * rule is unchanged and so is everything read off it — what changes is only *where* the one stated
     * direction is stated: the seeded span takes it, the spans after it are carried forward and the spans
     * before it are carried **backward**, by the same rotation read the other way. A frame is one direction
     * carried along a run, and a run has no preferred end to state it from; what the seed buys is that the
     * section can be stated where the drawing is (see [FrameSeed]). [MovingFrame.startRef] then reports the
     * direction the first span ended up with, which is the seed expressed at the start and hence what makes a
     * seeded sweep still a pure function of `(path, up, roll, twist)`.
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
        seed: FrameSeed? = null,
        lawSpans: Int = 0,
    ): Pair<MovingFrame?, String?> {
        if (path.elements.isEmpty()) return null to "this curve has no pieces, so there is nothing to sweep along"
        val sampled = spine(path, reach, twistRad, tolMm, lawSpans)
        val points = sampled.points
        val curvatures = sampled.curvatures
        val params = sampled.params
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

        // the transport: one reference per span, carried away from where it is stated, introducing no rotation
        // about the tangent — forward from the start, or both ways from a seed part-way along
        val seedSpan = if (seed == null) 0 else spanOf(params, seed, spans)
        val refs = MutableList(spans) { Vec3(0.0, 0.0, 0.0) }
        refs[seedSpan] =
            if (seed == null) {
                startReference(dirs[0], up)
            } else {
                transport(seed.ref, seed.tangent.normalized(), dirs[seedSpan]) ?: return null to reversalRefusal(cum[seedSpan])
            }
        for (k in seedSpan + 1 until spans) {
            refs[k] = transport(refs[k - 1], dirs[k - 1], dirs[k]) ?: return null to reversalRefusal(cum[k])
        }
        for (k in seedSpan - 1 downTo 0) {
            refs[k] = transport(refs[k + 1], dirs[k + 1], dirs[k]) ?: return null to reversalRefusal(cum[k + 1])
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
            stations.add(
                Frame3(
                    cum[k],
                    points[k],
                    a,
                    (turned - a * turned.dot(a)).normalized(),
                    mitre,
                    curvatures[k],
                    sampled.bends[k],
                    sampled.corners[k],
                    sampled.params[k],
                ),
            )
        }
        return MovingFrame(stations, length, path.closed, seam, refs[0], path) to null
    }

    /**
     * Which span of the sampled spine the [seed] falls in — the last one that starts at or before it, in the
     * path's own `(piece, parameter)` order rather than in an arc length.
     *
     * In the path's vocabulary because nothing then has to agree about the sampling: [params] is the very list
     * the spine was built from, so the comparison is exact and a reload lands on the same span to the bit. A
     * seed sitting **exactly** on a sample point rides the span *leaving* it, which is the only tie there is and
     * matters to nothing — the two spans meet there, and a station's own frame is the arriving span's.
     */
    private fun spanOf(
        params: List<Pair<Int, Double>>,
        seed: FrameSeed,
        spans: Int,
    ): Int {
        var found = 0
        for (k in params.indices) {
            val (piece, t) = params[k]
            if (piece < seed.piece || (piece == seed.piece && t <= seed.t)) found = k else break
        }
        return found.coerceIn(0, spans - 1)
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
     *
     * `internal` rather than private because **the station reads it** (OP-26, step 4, [Stations3]): a station
     * is a frame at a *stated* arc length rather than at a sampled one, so it walks its own chords — but it
     * must walk them by the same rule, or two frames on one curve would disagree about which way is up.
     */
    internal fun transport(
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
     * The sampled spine: the path's points in order (a closed path's returning duplicate dropped), per point
     * the **analytic** curvature of the piece it came from and the direction that curvature bends in, and per
     * point the `(piece, parameter)` it was sampled at.
     */
    private class Spine(
        val points: List<Vec3>,
        val curvatures: List<Double>,
        val bends: List<Vec3>,
        val params: List<Pair<Int, Double>>,
        val corners: List<Boolean>,
    )

    /**
     * The spine of [path], sampled — see [Spine] for what comes back.
     *
     * A point that two pieces hand over at gets the **larger** of their two curvatures, so a station on the
     * join of a straight run and a bend is judged by the bend — the answer that cannot let a fold through —
     * and it gets **that piece's** bend direction with it, since a magnitude and a direction taken from two
     * different curves would be a curvature no curve has.
     *
     * The parameters are carried because a **seeded** frame has to say which span it is stated on
     * ([FrameSeed], [spanOf]), and the only honest answer is the one the sampling itself used: matching a seed
     * to an arc length would compare a chord sum against a curve length and land a span out at the wrong end
     * of a coarse piece.
     */
    private fun spine(
        path: Path3,
        reach: Double,
        twistRad: Double,
        tolMm: Double,
        lawSpans: Int = 0,
    ): Spine {
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
        val bend = ArrayList<Vec3>()
        val params = ArrayList<Pair<Int, Double>>()
        for ((i, el) in path.elements.withIndex()) {
            val share = if (total > Geom3.WELD_TOL) lengths[i] / total else 0.0
            // …and the law's own share of the refinement (OP-26, session 77), which is what keeps a horn a
            // horn on a straight piece instead of the cone its two end rings would otherwise be joined into
            val steps = max(max(base[i], GeomMath.chordSteps(reach, abs(twistRad) * share, tolMm)), ceil(lawSpans * share).toInt())
            for (j in 0..steps) {
                val t = j.toDouble() / steps
                val p = pointAt(el, t)
                val k = curvatureAt(el, t)
                if (points.isNotEmpty() && (p - points.last()).length() <= Geom3.WELD_TOL) {
                    // the hand-over point two pieces share, and a sample a degenerate piece repeated
                    if (k > curvature.last()) {
                        curvature[curvature.size - 1] = k
                        bend[bend.size - 1] = bendAt(el, t)
                    }
                    continue
                }
                points.add(p)
                curvature.add(k)
                bend.add(bendAt(el, t))
                params.add(i to t)
            }
        }
        // a closed path's last piece ends where the first began: the ring closes through the span list, so
        // the returning duplicate is not a station of its own
        if (path.closed && points.size > 1 && (points.last() - points.first()).length() <= Geom3.WELD_TOL) {
            if (curvature.last() > curvature[0]) {
                curvature[0] = curvature.last()
                bend[0] = bend.last()
            }
            points.removeAt(points.size - 1)
            curvature.removeAt(curvature.size - 1)
            bend.removeAt(bend.size - 1)
            params.removeAt(params.size - 1)
        }
        return Spine(points, curvature, bend, params, cornersOf(path, params))
    }

    /**
     * Which stations are **corners of the curve** — read off the pieces the samples came from, so the answer
     * is a property of the path and not of how finely it was cut.
     *
     * A station is one of the samples' `(piece, parameter)` positions, and a piece hand-over is exactly a
     * sample at `t = 1` of a piece that is not the last: the coinciding first sample of the *next* piece is
     * dropped by [spine], so the shared point keeps the earlier piece's parameter. A **closed** path's seam
     * is the same question asked of the last piece against the first, and it lands on station 0 for the same
     * reason — the returning duplicate is dropped there.
     *
     * *"The tangents differ"* is decided against the **analytic** pieces ([Curves3.tangentAt]) rather than
     * against the chords the spine walks, and that is the load-bearing choice: the chord leaving a sampled arc
     * is half a sampling step off the arc's own tangent, so a chord-based test would call the perfectly
     * tangent join of a fillet and its leg a corner, and would call it one by an amount that changes when the
     * mesh is refined. A rounded rectangle lifted out of the plan has *no* corners by this reading, which is
     * the right answer and the one a finer mesh cannot alter.
     */
    private fun cornersOf(
        path: Path3,
        params: List<Pair<Int, Double>>,
    ): List<Boolean> {
        val els = path.elements
        return params.mapIndexed { k, (piece, t) ->
            when {
                t >= 1.0 && piece < els.lastIndex -> turnsAt(els[piece], els[piece + 1])
                path.closed && k == 0 && els.size > 1 -> turnsAt(els.last(), els.first())
                path.closed && k == 0 && els.size == 1 -> turnsAt(els.first(), els.first())
                else -> false
            }
        }
    }

    /**
     * Whether the run's own direction jumps where piece [a] hands over to piece [b].
     *
     * The tolerance is a **rounding** one and not a modelling one, in the same sense [Embedding]'s cone test
     * is: tangency at a join is a fact of how the run was constructed — a fillet is placed tangent to its
     * legs, a lift chains what the drawing already joined — so the two directions either agree to the last
     * few bits or differ by a visible angle. Nothing in between can change an answer either, since a join
     * turning by this little trims `u·tan(θ/2) ≤ u·5e-7` off its bands, which is under a micron for a section
     * reaching a metre and far below anything this kernel resolves (OP-15). A piece with no direction at its
     * end counts as a corner, because a run that stands still there has no tangent to be continuous with.
     */
    private fun turnsAt(
        a: Curve3Element,
        b: Curve3Element,
    ): Boolean {
        val ta = Curves3.tangentAt(a, 1.0) ?: return true
        val tb = Curves3.tangentAt(b, 0.0) ?: return true
        return ta.dot(tb) < 0.0 || ta.cross(tb).length() > CORNER_EPS
    }

    /** Below this |sin| between two pieces' analytic tangents their join is smooth — see [turnsAt]. */
    private const val CORNER_EPS = 1e-6

    /**
     * **Which way [el] bends at [t]** — the unit direction from the curve towards its centre of curvature, or
     * `Vec3.ZERO` where the piece is straight or has no direction at all.
     *
     * The direction of [Curves3.curvatureVectorAt], whose magnitude is the very number [curvatureAt] returns,
     * so the two halves of one fact cannot disagree. Zero is the honest answer for a straight run and not a
     * defensive one: there is no centre of curvature to point at, and the criterion that reads this multiplies
     * it by a curvature of zero anyway.
     */
    private fun bendAt(
        el: Curve3Element,
        t: Double,
    ): Vec3 {
        val v = Curves3.curvatureVectorAt(el, t) ?: return Vec3.ZERO
        return if (v.length() > Vec3.EPS) v.normalized() else Vec3.ZERO
    }

    /**
     * How many spans a piece is sampled into before the twist has its say — one for a straight run.
     *
     * A **helix** is the case where the chord tolerance is met exactly rather than bounded: the curve has one
     * constant radius of curvature `1/κ`, so the chord rule a circle of that radius obeys is the chord rule
     * *this* curve obeys, over its own total turn. That is [GeomMath.chordSteps] — the revolve's rule and the
     * twist refinement's rule — used for the third time and still not re-derived.
     *
     * `internal` for [Stations3]'s sake — see [transport]: one sampling rule, so the station's transport walks
     * the chords the sweep's frame walks. Which is also why **no tessellation quality reaches this count**
     * ([MeshQuality]): a station plane is geometry the user builds on, so where it lands is a modelling fact
     * and not a picture's business — and a swept body's plan hint reads the same stations
     * ([Silhouette.ofSwept]). A coarse sweep is the same run with a cheaper ring.
     */
    internal fun baseSteps(
        el: Curve3Element,
        tolMm: Double,
    ): Int =
        when (el) {
            is Curve3Element.Seg3 -> 1
            is Curve3Element.Bezier3 -> max(1, bezierSteps3(el, tolMm))
            is Curve3Element.Arc3 -> max(1, GeomMath.chordSteps(el.radius, el.sweepAngle, tolMm))
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

    /** A point on one piece at parameter [t] — `internal` for [Stations3], like [baseSteps] and [transport]. */
    internal fun pointAt(
        el: Curve3Element,
        t: Double,
    ): Vec3 =
        when (el) {
            is Curve3Element.Seg3 -> el.start + (el.end - el.start) * t
            is Curve3Element.Bezier3 -> Curves3.bezierPointAt(el, t)
            is Curve3Element.Arc3 -> el.at(t)
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
            is Curve3Element.Arc3 -> el.curvature
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
 * - **Locally**, on a bend tighter than the section reaches into it: the inner side of the section turns
 *   inside out. That is `κ·h(N) ≥ 1`, with `h(N)` the section's own reach **towards the centre of the bend**
 *   ([intoTheBend]) — the ball model's `κ·reach ≥ 1` is that statement maximized over directions, and it was
 *   corrected to the honest one for the same reason and by the same argument the global term was in session
 *   59: an off-centre section is the in-place sweep's everyday case, and what it reaches *away* from a bend
 *   cannot fold through the inside of it.
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
 * **Two legs that run side by side are compared where they are side by side** (the derivation corrected in
 * session 59, after a closed polyline loop swept an inside-out shell with nothing refusing). The closest
 * points of two segments are the solution of a 2×2 system whose determinant is `1 − (d₁·d₂)²`, and that
 * determinant vanishes for **parallel or antiparallel** pieces — not because the answer is ill-defined but
 * because there are many: the double normals of two parallel legs are the whole **overlap interval** of their
 * spans, every pair on it square to both legs and all of them the same distance apart. Clamping the singular
 * system instead answers with a *corner* of the run, where the adjoining leg's direction points across the
 * connector and the normal-cone test — correctly, for a corner — throws the pair out; so on a plan loop whose
 * legs are each sampled into a single span, the one bottleneck that matters was never offered and a section
 * standing further inside the loop than its inradius folded the ring through itself in silence. The overlap's
 * **middle** is taken, since it is interior on both pieces whenever the family has an interior, and such a
 * pair needs no normal-cone test at all: the stationarity holds identically along the overlap rather than at
 * a point. Where the projected spans do not overlap there is no family, the nearest points are a pair of ends
 * — two pieces cut from one straight leg, which never overlap — and the cone test rejects them for running
 * *along* the spine, exactly as before. It is a statement about parallel legs and not about closed loops: a
 * U-shaped open run is caught by the same lines of code.
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
     * How many points the bend term reads off each piece a corner joins ([cornerBend], GitHub #20).
     *
     * A **fixed** number, and that is the load-bearing part: it is the criterion's own resolution and owes
     * nothing to how finely the drawing is meshed, so no refusal here can ever appear because a picture was
     * refined. Sixteen because the wander over a trimmed span is monotone on anything convex and the last
     * sample sits exactly at the end of the span, where it is largest — the ones between it only catch a
     * piece that wanders and comes back.
     */
    private const val BEND_SAMPLES = 16

    /**
     * How deep a corner's cut may go into the wall behind it before it is a fold worth naming — twice
     * [GeomMath.TESS_TOL_MM], the very slack [check]'s global term compares against.
     *
     * A constant rather than the caller's `tolMm`, deliberately: tying it to the tessellation the caller asked
     * for would let a finer mesh lower the bar, which is the one thing this family's refusals may never do.
     */
    private const val BEND_SLACK = 2.0 * GeomMath.TESS_TOL_MM

    /**
     * **Which bend of the run station [i] is, in words** — read off the station's *identity*, not measured
     * against a tolerance, which is why the two ends can say so exactly.
     *
     * The refusal used to lead with the arc length alone, and *"the bend 0 mm along the path"* is the reading
     * that made a correct refusal unreadable (GitHub issue #15): the station that refuses a section is very
     * often the **first** one — a coil, an arc, any smooth run bends from the moment it starts — and a
     * distance that happens to be zero is exactly what a reader takes for "nowhere in particular". So the
     * place is named in words and the figure is kept beside the radius, where it is a measurement rather than
     * the sentence's subject.
     */
    private fun bendAt(
        frame: MovingFrame,
        i: Int,
    ): String =
        when (i) {
            0 -> "the run starts with"
            frame.stations.lastIndex -> "the run ends with"
            else -> "part-way along it"
        }

    /**
     * Whether a profile reaching [reach] from the path is embedded along [frame] — the `min` of the two
     * terms, in that order, so the local failure keeps its own words where both would fire.
     *
     * [what] is how the refusal names the profile's size ("the tube's radius (5 mm)"), passed in because the
     * profile's *kind* is the sweep's business while this is the *path's* statement. [subject] and [cure]
     * are the same courtesy for the *consequence*: the swept cut (OP-22's extension, step 2) fails in the
     * same geometry with a different name and a different way out, and both default to the sweep's own
     * words, so every message this ever produced is byte-identical.
     *
     * **[reach] need not be the profile's own**, and that is the whole of the bounded-reach form. An
     * *unbounded* profile — a chain that runs to infinity, which is what a cut is made with — has infinite
     * reach, and with it this criterion degenerates: `κ·∞ ≥ 1` refuses every bend there is. The fix is a
     * derived number rather than a different criterion: the **effective** reach is the distance to the far
     * edge of the target's extent, so what is asked becomes *"does the surface fold through itself **within
     * the region that matters**"* — and the same restriction applies to [frame], which the caller hands over
     * already clipped to the stations whose sections can reach the target. Neatly, non-self-intersection over
     * that region is exactly the condition under which *"which side"* is well defined, so the refusal and the
     * operator's semantics are one statement. See `Chains.sweptTools`.
     *
     * **[scales] is the section's own size at each station** (OP-26, session 77's ruling (c)) — one factor per
     * station of [frame], or null for a section of one size. Both terms then read the size *at their own
     * station* instead of one number for the whole run: the local term scales the reach it asks about, and
     * the global term scales each of the two supports it adds. That is the criteria **generalizing** rather
     * than two new criteria — the machinery already worked per station — and with null not a single
     * multiplication happens, so every message this ever produced stays byte-identical.
     */
    fun check(
        frame: MovingFrame,
        reach: Double,
        what: String,
        subject: String = "the sweep",
        cure: String = "thin the section, or open the run out",
        section: List<Vec2>? = null,
        inward: ((Double) -> String)? = null,
        scales: List<Double>? = null,
    ): EmbeddingReport {
        // ---- the first term: 1/κ_max, station by station, and in station order so the message is stable
        for ((i, st) in frame.stations.withIndex()) {
            val bare = intoTheBend(st, reach, section)
            val into = if (scales == null) bare else bare * (scales.getOrNull(i) ?: 1.0)
            if (st.curvature * into >= 1.0) {
                return EmbeddingReport(
                    "${inward?.invoke(into) ?: what} is larger than the bend ${bendAt(frame, i)} " +
                        "(radius ${Frames3.mm(1.0 / st.curvature)} mm, " +
                        "${Frames3.mm(st.s)} mm along the path), so $subject would pass through itself",
                    Double.MAX_VALUE,
                    0,
                )
            }
        }

        // ---- the second term: the closest bottleneck of the spine
        // The grid's cell is sized for the **largest** the section ever is, because a cell smaller than the
        // pair it has to offer would lose the bottleneck outright. What each pair then *needs* is still read
        // at the two stations' own sizes ([needed]) — the cell is bookkeeping, the criterion is per station.
        val clearance = if (scales == null) 2.0 * reach else 2.0 * reach * (scales.maxOrNull() ?: 1.0)
        val pieces = piecesOf(frame, clearance)
        if (pieces.size < 3) return EmbeddingReport(null, Double.MAX_VALUE, 0)
        val grid = HashMap<Long, MutableList<Int>>(pieces.size * 2)
        for (i in pieces.indices) {
            forEachCell(pieces[i], clearance) { k -> grid.getOrPut(k) { ArrayList() }.add(i) }
        }

        var nearest = Double.MAX_VALUE
        var worst = 0.0
        var bestD = 0.0
        var bestA = 0.0
        var bestB = 0.0
        var bestNeed = 0.0
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
                                if (hit != null) {
                                    if (hit.d < nearest) nearest = hit.d
                                    // **What this pair needs is what the two sections reach *towards each
                                    // other*, not what they reach at all** — the isotropic `2·reach` is the
                                    // same statement maximized over directions, and it is the one this falls
                                    // back to when the section is a disc or is not offered.
                                    val need = needed(frame, pieces, i, j, hit, reach, section, scales)
                                    if (need - hit.d > worst) {
                                        worst = need - hit.d
                                        bestD = hit.d
                                        bestA = hit.s
                                        bestB = hit.t
                                        bestNeed = need
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (worst > 2.0 * GeomMath.TESS_TOL_MM) {
            return EmbeddingReport(
                "the run passes within ${Frames3.mm(bestD)} mm of itself, between ${Frames3.mm(bestA)} mm and " +
                    "${Frames3.mm(bestB)} mm along the path, while $what needs ${Frames3.mm(bestNeed)} mm " +
                    "between them — so $subject would cut into itself; $cure",
                bestD,
                examined,
            )
        }
        return EmbeddingReport(null, nearest, examined)
    }

    /**
     * **Does a corner mitre away more run than there is?** — the third way a swept body folds, and the one
     * neither term of [check] can see (OP-9's *watertight or refused*; OP-26, step 2's corner treatment).
     *
     * **The mechanism.** At a station the ring is laid out in the plane perpendicular to the arriving chord
     * and then pushed along that chord onto the **mitre plane** ([Frame3.place]) — which is the whole of the
     * corner treatment and is exactly the trim two straight tubes make of each other. Written out, a profile
     * point standing `u` to the **inside** of a turn of `θ` is pushed back by `u·tan(θ/2)`, and the same point
     * on the leaving side stands `u·tan(θ/2)` **forward** along the next leg. So each corner eats
     * `u·tan(θ/2)` off both legs it joins, and a leg whose two corners eat more than its whole length has its
     * band handed back past where it started: the surface folds. It is not a proximity — a triangle's three
     * legs all *touch*, so there is no non-neighbouring pair to be a bottleneck at — and it is not a
     * curvature, since a polyline corner has none on either side. Both fixtures the record carries are silent
     * without this: an 18 mm tube through two 85° corners 30.11 mm apart comes out **edge-manifold and
     * positively volumed**, because a symmetric section's mitre adds outside exactly what it removes inside.
     *
     * **`u` is a direction and not a size, exactly as session 59's clearance is.** Each corner contributes a
     * 2D vector in the station's own axes,
     *
     * ```
     * g = tan(θ/2) · m ,   m the unit direction ⟂ the arriving chord towards the inside of the turn
     * ```
     *
     * and the leg between corners A and B advances for a profile point `w` exactly when
     * `w·g_A + w·g_B < L`. The whole section advances when that holds for **every** vertex, so what the leg
     * needs is `max over w of w·(g_A + g_B)` — the section's own support function in the direction the two
     * mitres jointly bite, and one number rather than two. Taking the two supports *separately* and adding
     * them would be a different and wronger claim: on a **serpentine** the two corners turn opposite ways,
     * `g_A + g_B` very nearly cancels, and an ordinary zig-zag tube keeps building — the mitres *shear* its
     * band instead of shortening it — while the sum of the two supports would refuse it. A **round** tube has
     * no outline to maximize over, and the support of a disc is its radius, so it falls back to
     * `reach·|g_A + g_B|` with no case of its own. A non-convex section is measured by its convex hull, which
     * errs towards refusing a body that would have fitted and never towards accepting a fold, which is the
     * same boundary [needed] already has.
     *
     * **The corners are the curve's, not the mesh's, and that is the decision this term forced.** The exact
     * condition applied at *every* sampled station would fire on a smooth run at `h ≥ R·cos(Δ/2)` for the
     * sampling step `Δ` — inside the analytic limit `h ≥ R` that [check]'s local term is asserted at, by an
     * amount that shrinks as the mesh is refined. A criterion that refuses more of a finer picture is not a
     * criterion, so the refusals speak about the **curve**: only a station where the run's own tangent jumps
     * ([Frame3.corner]) is a corner, a stretch of run between two of them is a **leg**, and everything smooth
     * stays the local term's business, where it is a statement about an analytic curvature. A mixed run of
     * segments and arcs joined tangentially — a lifted rounded rectangle — therefore has no corners at all and
     * is untouched here.
     *
     * **The boundary is `≥`, and the limit argument decides it.** As the two trims approach the leg's length
     * the band between them shrinks to nothing; at equality the two rings are one ring and the band is a
     * sheet of degenerate triangles, so the watertightness the sweep is built on — consecutive bands sharing
     * one ring, each band a prism of positive length — has no body left to be about. The limit of bodies is
     * not a body, so equality refuses. That is the same side [check]'s local term takes at `κ·h ≥ 1` and for
     * the same reason.
     *
     * **The resolution is the spine's** (OP-15). A leg's length is the sum of the chords between its two
     * corners — exact on a straight leg, and on a curved one an understatement of the true arc by less than
     * the tolerance the spine is built to, so the criterion errs by that much towards refusing. The turn is
     * read off the station's own mitre plane, which is the plane the ring is actually built on, so the
     * numbers a refusal quotes are the numbers the built geometry has rather than an idealization of them.
     *
     * **And the trim has to land on run the mitre's own arithmetic is true of** — the second term
     * ([cornerBend], GitHub #20), which is what session 65's own parked note above ("*a corner whose trim
     * exceeds one sampling step of a curved leg …*") turned out to be hiding. The mitre is the trim two
     * **straight** tubes make of each other: the curtain between the corner ring where it is built and where
     * it is pushed to lies exactly *on* a straight leg's own surface, which is why a mitred polyline is exact
     * and why the leg term above is the only question a polyline can raise. Where the leg **bends** inside the
     * trimmed span that curtain leaves the tube's wall and cuts across the run behind it, and the body folds —
     * watertight and positively volumed, and folded, which is the report that opened GitHub #20.
     *
     * The parked note read that as the mesh speaking, because the artefact appears in the picture exactly when
     * the trim reaches past the next station. It is not: refine the spine and the fold does not go away, it is
     * drawn with more triangles; coarsen it until no station stands inside the trim and the fold is merely
     * *hidden*. So the question is asked of the curve — how far the analytic run wanders off the straight line
     * the cut is made on, over the run that cut eats — and the answer is compared against the tolerance the
     * spine itself is built to. A fold shallower than that is under the picture's own accuracy and is not
     * named; a finer mesh cannot make this refuse more, because nothing in it reads the mesh.
     *
     * [subject] and [cure] are the caller's words, the same courtesy [check] does the swept cut, whose route
     * runs through the identical mitre and is judged by the identical arithmetic against its own clipped
     * section (`Chains.sweptTools`).
     */
    fun cornerFold(
        frame: MovingFrame,
        reach: Double,
        section: List<Vec2>? = null,
        subject: String = "the sweep",
        cure: String = "thin the section, move it towards the outside of the turn, or open the corners out",
        /**
         * The section's own size at each station (OP-26, session 77), or null for a section of one size.
         *
         * Both terms below read it at the corner they are about, which is exactly right rather than
         * conservative: the ring a corner mitres **is** the ring at that station, so what it bites off each
         * leg is `k·(w·g)` for that station's own `k`, and a leg between two corners is judged by the two
         * factors its two corners carry. Written into the bite vector rather than applied to the support
         * afterwards, because the two corners of a serpentine turn opposite ways and the cancellation the
         * whole term rests on has to happen *after* each side has its own size.
         */
        scales: List<Double>? = null,
    ): String? {
        val st = frame.stations
        if (st.size < 2) return null
        val corners = st.indices.filter { st[it].corner }
        if (corners.isEmpty()) return null
        // the legs: a stretch of run between two corners, and on an open run the two ends, which mitre
        // nothing (a station at the end of an open path has its own tangent for a mitre plane, so its trim
        // is exactly zero and the cap falls out with no case for it)
        val legs = ArrayList<Pair<Int, Int>>(corners.size + 1)
        if (frame.closed) {
            for (k in corners.indices) legs.add(corners[k] to corners[(k + 1) % corners.size])
        } else {
            // A corner standing **on** an end of an open run has no leg on that side to eat into — which is
            // not a hypothetical: a swept cut opens its closed route at the station furthest from the target
            // (`Chains.openedAwayFrom`), and that station keeps the mitre plane it had in the loop.
            if (corners.first() > 0) legs.add(-1 to corners.first())
            for (k in 0 until corners.size - 1) legs.add(corners[k] to corners[k + 1])
            if (corners.last() < st.lastIndex) legs.add(corners.last() to -1)
        }
        for ((a, b) in legs) {
            val ga = if (a < 0) Vec2(0.0, 0.0) else biteOf(st[a]) * sizeAt(scales, a)
            val gb = if (b < 0) Vec2(0.0, 0.0) else biteOf(st[b]) * sizeAt(scales, b)
            val v = ga + gb
            if (v.length() <= Vec2.EPS) continue
            val w = worstVertex(v, section, reach) ?: continue
            val need = w.dot(v)
            val len = legLength(frame, a, b)
            if (need < len) continue
            return foldRefusal(st, a, b, need, len, subject, cure)
        }
        return cornerBend(frame, reach, section, subject, cure, scales)
    }

    /** Station [i]'s own size factor, and exactly 1.0 where the section has one size (OP-26, session 77). */
    private fun sizeAt(
        scales: List<Double>?,
        i: Int,
    ): Double = if (scales == null || i < 0) 1.0 else scales.getOrNull(i) ?: 1.0

    /**
     * **Does a corner mitre into run that bends?** — [cornerFold]'s second term (GitHub #20), and the one that
     * reads the *curve* either side of the corner rather than the leg's bare length.
     *
     * What is measured, per corner: the trim reaches [trimOf] mm along the corner's own tangent, in both
     * directions, since the ring is pushed back on the inside of the turn and forward on the outside. Over
     * exactly that much run either side, the analytic piece is asked how far it wanders off the straight line
     * through the corner — the line the mitre cut is made on. A segment answers zero, to the bit, so a mitred
     * polyline is untouched by this and every fixture the leg term owns keeps its own words.
     *
     * **The sampling is this criterion's own and fixed** ([BEND_SAMPLES]), which is the whole of the answer to
     * *"is this the mesh speaking?"*: it does not read the spine's step, so meshing a drawing more finely
     * cannot make it refuse — the same discipline [Frames3.cornersOf] follows when it reads corners off the
     * analytic pieces instead of off the chords. It samples in **arc length** ([Curves3.paramAtLength]), so
     * the span asked about is the span the trim eats however the piece is parameterized, and it includes the
     * far end of that span, where the wander is largest on anything convex.
     *
     * **The threshold is the picture's own accuracy** ([GeomMath.TESS_TOL_MM], doubled — the slack
     * [check]'s global term already uses, and a constant rather than the caller's `tolMm` so that a finer
     * mesh cannot lower the bar). A curtain that cuts a hundredth of a millimetre into the wall is a fold no
     * mesh built to two hundredths can draw and no user can see; one that cuts millimetres in is the
     * artefact in the report.
     */
    private fun cornerBend(
        frame: MovingFrame,
        reach: Double,
        section: List<Vec2>?,
        subject: String,
        cure: String,
        scales: List<Double>? = null,
    ): String? {
        val path = frame.path ?: return null
        val els = path.elements
        val st = frame.stations
        for (c in st.indices) {
            if (!st[c].corner) continue
            val join = jointAt(frame, c) ?: continue
            val trim = trimOf(st[c], els[join.first], els[join.second], section, reach, sizeAt(scales, c))
            if (trim <= Vec3.EPS) continue
            val into = wanderOf(els[join.first], trim, fromEnd = true)
            val outOf = wanderOf(els[join.second], trim, fromEnd = false)
            val bend = maxOf(into, outOf)
            if (bend <= BEND_SLACK) continue
            return bendRefusal(st[c].s, trim, bend, subject, cure)
        }
        return null
    }

    /**
     * How much run a corner's mitre reaches over, either way along its own tangent — see [cornerBend].
     *
     * The turn is read from the **analytic** tangents of the two pieces the corner joins, not from the mitre
     * plane the chords built, and that is deliberate and the one place this term parts company with the leg
     * term above. The leg term quotes the numbers the built geometry has, which is right for a claim about
     * the built band; this term's whole business is *"what does the curve do here"*, and a chord-derived turn
     * would make the number — and, at the boundary, the refusal — move when the drawing was meshed more
     * finely. For a round tube nothing but the analytic turn enters at all; for an outlined section the
     * ring's own axes are the frame's, which is the orientation the section is actually built in.
     */
    private fun trimOf(
        st: Frame3,
        into: Curve3Element,
        outOf: Curve3Element,
        section: List<Vec2>?,
        reach: Double,
        /** The corner station's own size factor (OP-26, session 77) — 1.0 for a section of one size. */
        scale: Double = 1.0,
    ): Double {
        val g = analyticBite(st, into, outOf) ?: return 0.0
        if (g.length() <= Vec2.EPS) return 0.0
        // the trim is linear in the section's own (x, y), so a rigidly scaled ring reaches exactly `scale`
        // times as far along the tangent — one multiplication, and the analytic turn is untouched by it
        val back = worstVertex(g, section, reach)?.dot(g) ?: 0.0
        val forward = worstVertex(g * -1.0, section, reach)?.dot(g * -1.0) ?: 0.0
        return maxOf(back, forward) * scale
    }

    /**
     * [biteOf]'s analytic twin: `tan(θ/2)` towards the inside of the turn, in the ring's own axes, with the
     * turn taken from the two pieces' own end tangents rather than from the chords the spine walks.
     */
    private fun analyticBite(
        st: Frame3,
        into: Curve3Element,
        outOf: Curve3Element,
    ): Vec2? {
        val a = Curves3.tangentAt(into, 1.0) ?: return null
        val b = Curves3.tangentAt(outOf, 0.0) ?: return null
        val sum = a + b
        if (sum.length() <= Vec3.EPS) return null
        val mitre = sum.normalized()
        val c = mitre.dot(a)
        if (c <= 0.0) return null
        val m = mitre - a * c
        // the **length** is the analytic tan(θ/2) and nothing else; the ring's axes give only the direction,
        // and they stand perpendicular to the chord rather than to the tangent, so projecting onto them and
        // keeping the projection's length would let the mesh back into the number by the back door
        val inPlane = Vec2(m.dot(st.ref), m.dot(st.bi))
        if (inPlane.length() <= Vec2.EPS) return null
        return inPlane.normalized() * (m.length() / c)
    }

    /**
     * Which two pieces the corner at station [c] is the join of, or null where no join stands there.
     *
     * Read off the station's own [Frame3.param] by exactly the rule that marked it a corner
     * (`Frames3.cornersOf`): a join is a sample at the end of a piece that is not the last, and a closed
     * path's seam is its last piece against its first, which lands on the run's first station.
     */
    private fun jointAt(
        frame: MovingFrame,
        c: Int,
    ): Pair<Int, Int>? {
        val els = frame.path?.elements ?: return null
        val (piece, t) = frame.stations[c].param ?: return null
        return when {
            t >= 1.0 && piece < els.lastIndex -> piece to piece + 1
            frame.closed && c == 0 && els.size > 1 -> els.lastIndex to 0
            frame.closed && c == 0 -> 0 to 0
            else -> null
        }
    }

    /**
     * How far [el] wanders off its own end tangent over the last (or first) [span] mm of its arc length — the
     * bend the mitre's straight-tube arithmetic does not know about.
     *
     * Zero for a segment, exactly. Clamped to the piece, because a trim that reaches past a whole piece is the
     * leg term's business and not this one's.
     */
    private fun wanderOf(
        el: Curve3Element,
        span: Double,
        fromEnd: Boolean,
    ): Double {
        val len = Curves3.arcLength(el)
        if (len <= Vec3.EPS) return 0.0
        val reachSpan = min(span, len)
        val t0 = if (fromEnd) 1.0 else 0.0
        val origin = Frames3.pointAt(el, t0)
        val dir = Curves3.tangentAt(el, t0) ?: return 0.0
        var worst = 0.0
        for (i in 1..BEND_SAMPLES) {
            val at = reachSpan * i / BEND_SAMPLES
            val p = Frames3.pointAt(el, Curves3.paramAtLength(el, if (fromEnd) len - at else at))
            val v = p - origin
            worst = max(worst, (v - dir * v.dot(dir)).length())
        }
        return worst
    }

    /** The refusal a corner cutting into bent run speaks — the corner, both sizes, and the way out. */
    private fun bendRefusal(
        s: Double,
        trim: Double,
        bend: Double,
        subject: String,
        cure: String,
    ): String =
        "the corner ${Frames3.mm(s)} mm along the path mitres ${Frames3.mm(trim)} mm of run, and the path " +
            "bends ${Frames3.mm(bend)} mm off the straight line that cut is made on within that — so $subject " +
            "would fold back on itself; $cure"

    /**
     * What one corner **bites** off each of its two legs, as a 2D vector in that station's own axes:
     * `tan(θ/2)` long, pointing the way the turn closes.
     *
     * Read straight off the mitre plane the ring is built on rather than re-derived from the chords. With
     * `c = mitre·tangent = cos(θ/2)`, the mitre's own in-plane part is `sin(θ/2)` long and points to the
     * inside of the turn, so dividing it by `c` is `tan(θ/2)` in that direction and one division does the
     * whole of it. A station with no turn gives back the zero vector, which is what an end of an open run and
     * a smooth station both are.
     */
    private fun biteOf(st: Frame3): Vec2 {
        val c = st.mitre.dot(st.tangent)
        if (c <= 0.0) return Vec2(0.0, 0.0)
        val m = st.mitre - st.tangent * c
        return Vec2(m.dot(st.ref) / c, m.dot(st.bi) / c)
    }

    /**
     * The profile vertex that the two mitres bite hardest — the support point of the section in direction
     * [v], or the point of the disc of radius [reach] there when no outline is offered.
     *
     * The extreme is a vertex because the quantity is linear in the section's own (x, y), so the whole
     * section advances exactly when the outline's vertices do; a hole lies inside the outer loop's hull and
     * cannot reach further than it does.
     */
    private fun worstVertex(
        v: Vec2,
        section: List<Vec2>?,
        reach: Double,
    ): Vec2? {
        if (section == null || section.isEmpty()) return v.normalized() * reach
        return section.maxByOrNull { it.dot(v) }
    }

    /** How long the run is between the two ends of a leg — the wrap included, for a closed run. */
    private fun legLength(
        frame: MovingFrame,
        a: Int,
        b: Int,
    ): Double {
        val st = frame.stations
        if (a < 0) return st[b].s - st.first().s
        if (b < 0) return st.last().s - st[a].s
        if (frame.closed && b <= a) return frame.length - st[a].s + st[b].s
        return st[b].s - st[a].s
    }

    /**
     * The refusal a folded leg speaks (OP-3 — a property of *values*, so node invalidity that heals).
     *
     * Both corners are named by where they stand along the run, and what they take is quoted as **one**
     * figure: the two mitres bite the same profile vertex at once, and on a leg whose corners turn opposite
     * ways one of them *gives back* what the other takes, so a per-corner split would have to print a
     * negative trim to stay truthful. The figure quoted is exactly the number that was compared against the
     * leg, which is the rule the whole family follows — a message that quoted anything else would be a
     * correct refusal nobody could check.
     *
     * A leg with only one corner — an end leg of an open run, where the other end is a cap — says so rather
     * than naming a second corner that is not there.
     */
    private fun foldRefusal(
        st: List<Frame3>,
        a: Int,
        b: Int,
        need: Double,
        len: Double,
        subject: String,
        cure: String,
    ): String {
        fun tail(where: String) =
            "off the ${Frames3.mm(len)} mm of run $where, which is more than there is — so $subject would " +
                "fold back on itself; $cure"
        if (a < 0 || b < 0) {
            val i = if (a < 0) b else a
            val side = if (a < 0) "before" else "after"
            return "the corner ${Frames3.mm(st[i].s)} mm along the path mitres ${Frames3.mm(need)} mm " +
                tail("$side it")
        }
        return "the corners ${Frames3.mm(st[a].s)} mm and ${Frames3.mm(st[b].s)} mm along the path mitre " +
            "${Frames3.mm(need)} mm " + tail("between them")
    }

    /**
     * **How far the section reaches into the bend** at station [st], in mm — the local term's own version of
     * the correction session 59 made to the global one, and exactly as much of a correction.
     *
     * The local failure is the inner side of a section turning inside out on a bend, and the criterion for it
     * used to be `κ·reach ≥ 1` with [reach] the section's greatest distance from the path **in any
     * direction** — the ball model. That is sound and it is not what the geometry says. Written out, the sweep
     * carries a profile point `w` to `γ(s) + w.x·ref + w.y·bi`, and in a rotation-minimizing frame the
     * reference directions turn only *with the tangent* — `ref′` is parallel to the tangent, by construction
     * ([Frames3]) — so the derivative of that map along the run is `t·(1 − κ·(w·N))`, with `N` the unit normal
     * towards the centre of curvature. It vanishes, and the surface folds, exactly when `κ·(w·N) = 1`: what
     * decides a bend is the section's reach **towards the centre of that bend**, per station and in that
     * station's own axes, which is the 2D support function `h(N)` and nothing else. (No torsion appears, and
     * that is the rotation-minimizing frame paying out: a Frenet frame would spin the section about the
     * tangent and put the twist into this determinant.)
     *
     * The ball model is that statement maximized over directions, so it is never *wrong* — it refuses bodies
     * that fit, which is the same failure the isotropic global term had and the same cure. The user's case is
     * the ordinary one: a foundation drawn against the outside of a pillar reaches tens of millimetres
     * outwards and up, and **nothing** towards the pillar, so every 10 mm fillet in the plan refused a
     * foundation that could not touch itself.
     *
     * `null` or an empty [section] means *"a disc of radius [reach] about the run"* — a round tube, and the
     * derived reach of a swept cut — for which the support is [reach] in every direction, so this returns it
     * unchanged and every message the isotropic form ever produced is byte-identical.
     */
    private fun intoTheBend(
        st: Frame3,
        reach: Double,
        section: List<Vec2>?,
    ): Double {
        if (section == null || section.isEmpty()) return reach
        if (st.bend.length() <= Vec3.EPS) return reach
        return support(st, st.bend, section)
    }

    /**
     * **How much room this pair of legs actually needs**, in mm — the two sections' extents *towards each
     * other*, which is the honest generalization of `2·reach` and reduces to it exactly for a section that is
     * a disc about the run.
     *
     * The isotropic form asks whether two balls of radius `reach` about the spine overlap. That is sound but
     * it is a statement about the *greatest* the section reaches in *any* direction, and since the in-place
     * sweep made **off-centre** sections routine (a foundation drawn against the wall it sits by, a section
     * that pierces its own plane 100 mm from the run) it over-states a body badly: a plan loop with its
     * section standing 100 mm *outside* is a perfectly good ring, and the ball model refuses it for the same
     * reason it refuses the one standing 100 mm *inside*, which really does fold through itself. The sign of
     * the offset is the whole difference and a criterion that cannot see it is not measuring the body.
     *
     * What it measures instead is the **support function** of the section in the direction of the approach.
     * The section stands in the station's own axes, so a profile point `w` sits at `ref·w.x + bi·w.y` from
     * the run and its extent along the unit approach direction `u` is `w · (u·ref, u·bi)` — an ordinary 2D
     * support, maximized over the outline. Two convex sets whose shadows on `u` do not overlap cannot meet
     * (the separating-axis argument), so `h₁(u) + h₂(−u) < d` is a *proof* that this pair of legs is clear,
     * and the criterion is exact for a convex section rather than merely safe. A **non-convex** section is
     * measured by its convex hull, which errs the same way the ball did — towards refusing a body that would
     * have fitted — and never towards accepting a fold.
     *
     * [section] is the outline in the profile's own coordinates, or null for *"a disc of radius [reach]"*,
     * which is what a round tube analytically is and what the swept cut's derived reach means. Null gives
     * `2·reach` back to the last bit, so every message the isotropic form ever produced is byte-identical.
     *
     * The frame is read at the station whose **arriving** chord the span is ([Piece.st]) rather than
     * interpolated along it; a station apart the axes have turned by one sampling step, which is the same
     * resolution the whole of this criterion is stated at (OP-15).
     */
    private fun needed(
        frame: MovingFrame,
        pieces: List<Piece>,
        i: Int,
        j: Int,
        hit: Approach,
        reach: Double,
        section: List<Vec2>?,
        scales: List<Double>? = null,
    ): Double {
        val p = pieces[i]
        val q = pieces[j]
        // **the two sizes are the two stations' own** (OP-26, session 77): a run that comes back alongside
        // itself thin where it left thick needs the sum of what it is *there*, which is the same separating-
        // axis argument with one factor per side. Null scales leave both factors out of the arithmetic.
        val kp = if (scales == null) 1.0 else scales.getOrNull(p.st) ?: 1.0
        val kq = if (scales == null) 1.0 else scales.getOrNull(q.st) ?: 1.0
        if (section == null || section.isEmpty()) return if (scales == null) 2.0 * reach else reach * (kp + kq)
        val v = q.at(hit.t - q.s0) - p.at(hit.s - p.s0)
        val d = v.length()
        if (d <= Geom3.WELD_TOL) return if (scales == null) 2.0 * reach else reach * (kp + kq)
        val u = v * (1.0 / d)
        val a = support(frame.stations[p.st], u, section)
        val b = support(frame.stations[q.st], u * -1.0, section)
        return if (scales == null) a + b else a * kp + b * kq
    }

    /** How far [section] reaches from the run along [u], read in the station's own axes — the 2D support. */
    private fun support(
        st: Frame3,
        u: Vec3,
        section: List<Vec2>,
    ): Double {
        val a = u.dot(st.ref)
        val b = u.dot(st.bi)
        var h = -Double.MAX_VALUE
        for (w in section) {
            val e = w.x * a + w.y * b
            if (e > h) h = e
        }
        return h
    }

    /**
     * One piece of the sampled spine: a straight run from [a] to [b] carrying the arc position of its start,
     * cut short enough that its box never spans more than two cells of the grid.
     *
     * Arc *is* length here, and that is by construction rather than by approximation: [Frame3.s] is the
     * cumulative sum of the very chords these pieces are cut from, so a point [x] mm along a piece stands
     * exactly `s0 + x` along the spine.
     *
     * [st] is the station whose **arriving** chord this span is — the span's *far* end, not its start. A
     * [Frame3] is turned for the chord that reaches it ([Frame3.tangent]), so those are the axes the section
     * stands in along this span, and on a mitred polyline the difference is the whole answer: the station at
     * a span's *start* carries the previous leg's axes, turned a corner away from the one being measured.
     * It is what [needed] reads to ask what this leg reaches towards the other one.
     */
    private class Piece(val a: Vec3, val dir: Vec3, val s0: Double, val len: Double, val st: Int) {
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
                out.add(Piece(a + dir * (step * q), dir, st[k].s + step * q, step, (k + 1) % n))
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
        val near = closestParams(p, q)
        val x = near.x
        val y = near.y
        val v = q.at(y) - p.at(x)
        val d = v.length()
        if (d <= Geom3.WELD_TOL) return Approach(0.0, p.s0 + x, q.s0 + y)
        // **Two legs running side by side are a double normal all along their overlap**, so there is nothing
        // left to test (OP-26, the corrected derivation). Where the two directions are the same line, the
        // connector taken across the overlap is perpendicular to *both* by construction and the distance is
        // constant along it: the stationarity condition holds identically rather than at one point. Asking
        // the normal-cone test about it again is what used to lose it — the degenerate solve answered with
        // the loop's own **corner**, where the neighbouring leg's direction points away and the pair was
        // thrown out (session 58's inside-out ring).
        if (near.sideBySide) return Approach(d, p.s0 + x, q.s0 + y)
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
     * Where two pieces are closest, and whether they are **side by side** there — [sideBySide] meaning the
     * two run along one line over a stretch they share, so the approach is a double normal by construction
     * and [bottleneck] has nothing left to ask.
     */
    private class Closest(val x: Double, val y: Double, val sideBySide: Boolean)

    /**
     * How far along each piece the two are closest — the ordinary segment-to-segment closest points, written
     * out because both directions are unit here (arc *is* length), which reduces the usual algebra to three
     * dot products and a pair of clamps.
     *
     * **The parallel case is not a clamp, it is an interval** (OP-26, the derivation corrected in session
     * 59). The stationarity conditions of `|P(s) − Q(t)|` are `s − b·t = −c` and `t − b·s = −f`, whose
     * determinant is `1 − b²`; where the two directions are the same line that determinant vanishes because
     * the two equations are *the same equation*, and the double normals are not a point but the whole
     * segment `s − b·t = −c` inside the rectangle `[0, len_p] × [0, len_q]` — exactly the stretch over
     * which the two pieces overlap when each is projected on the other's axis. Every pair on it is a double
     * normal and they all stand the same distance apart, so any interior one of them answers; the **middle**
     * of the overlap is taken, because that is the one point of the family that is interior on both pieces
     * whenever the family has an interior at all. Solving the singular system by clamping instead — which is
     * what `den ≤ 1e-12 → x = 0` did — answers with a *corner* of the run, where the neighbouring leg's
     * direction points across the connector and the normal-cone test throws the pair away. That is the whole
     * of session 58's inside-out ring: a closed polyline's two opposite legs are the one bottleneck that
     * matters and they were never offered.
     *
     * Where the projected spans do **not** overlap the family is empty and the nearest points really are a
     * pair of ends — two pieces cut from one straight leg are the everyday instance — so those are answered
     * as the end pair they are and left to the normal-cone test, which rejects them for running *along* the
     * spine exactly as it always did.
     */
    private fun closestParams(
        p: Piece,
        q: Piece,
    ): Closest {
        val r = p.a - q.a
        val b = p.dir.dot(q.dir)
        val c = p.dir.dot(r)
        val f = q.dir.dot(r)
        val den = (1.0 - b * b).coerceAtLeast(0.0)
        if (sqrt(den) * max(p.len, q.len) <= GeomMath.TESS_TOL_MM) return alongOneLine(p, q, b, c)
        var x = ((b * f - c) / den).coerceIn(0.0, p.len)
        var y = (b * x + f).coerceIn(0.0, q.len)
        x = (b * y - c).coerceIn(0.0, p.len)
        y = (b * x + f).coerceIn(0.0, q.len)
        return Closest(x, y, false)
    }

    /**
     * The closest points of two pieces that run **along one line** — the overlap of their spans, taken across
     * the middle of it, or the pair of ends that face each other when the spans miss.
     *
     * *"Along one line"* is decided by [closestParams] against the spine's own resolution rather than against
     * a bare epsilon, and that is the honest reading of it (OP-15): the spine is a polyline within
     * [GeomMath.TESS_TOL_MM] of the curve it samples, so two pieces whose directions differ by less than that
     * tolerance **over their own length** are not pieces the polyline can tell apart in direction at all.
     * Taking the overlap's middle for such a pair overstates their distance by at most that same tolerance —
     * the distance grows by `(Δs·sinθ)²/2d` off the true foot, and `Δs·sinθ ≤ TESS_TOL_MM` is exactly the
     * test — which is inside the slack the refusal already carries, and it errs the permissive way.
     */
    private fun alongOneLine(
        p: Piece,
        q: Piece,
        b: Double,
        c: Double,
    ): Closest {
        val sgn = if (b >= 0.0) 1.0 else -1.0
        // q's own span, projected onto p's axis: a point t along q stands at s = −c + sgn·t along p
        val e0 = -c
        val e1 = -c + sgn * q.len
        val from = max(0.0, min(e0, e1))
        val to = min(p.len, max(e0, e1))
        if (to > from) {
            val x = 0.5 * (from + to)
            return Closest(x, ((x + c) * sgn).coerceIn(0.0, q.len), true)
        }
        val x = if (max(e0, e1) <= 0.0) 0.0 else p.len
        return Closest(x, ((x + c) * sgn).coerceIn(0.0, q.len), false)
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
