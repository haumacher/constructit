package constructit.core

import constructit.geom.Arc
import constructit.geom.Bezier
import constructit.geom.Chain
import constructit.geom.Circle
import constructit.geom.Direction
import constructit.geom.Ellipse
import constructit.geom.EllipticArc
import constructit.geom.Line
import constructit.geom.Loop
import constructit.geom.Path3
import constructit.geom.Path3Set
import constructit.geom.Plane3
import constructit.geom.PlaneSection
import constructit.geom.PointSet
import constructit.geom.Profile
import constructit.geom.Ray
import constructit.geom.Region
import constructit.geom.Segment
import constructit.geom.Sketch3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Quantity

/** A typed value flowing through the graph (OP-5). Strongly typed, one output per node. */
sealed interface Value

data class ScalarValue(val q: Quantity) : Value

data class PointValue(val p: Vec2) : Value

/**
 * A point **in space** — the value of a height point (`Construction.heightPoint`): a 2D point on a sketch
 * plane, lifted along that plane's normal by a scalar.
 *
 * A value kind of its own rather than a [PointValue] with a third number bolted on, for the reason the
 * whole 2D/3D seam is built on (OP-17): 2D geometry is *not* plane-resident, so a `Vec2` means "in some
 * plane's own coordinates" everywhere it appears, and every 2D consumer — the intersections, the offsets,
 * the region engine — would have to start asking which plane. A point with no plane is a different thing,
 * so it is a different type, and the type system keeps the two apart at every slot.
 *
 * The evaluator needed no change for it: [Value] is opaque to [Evaluator], which only ever passes values
 * from a node's inputs into its `compute`.
 */
data class Point3Value(val p: Vec3) : Value

/**
 * A **curve in space** (OP-26): a piecewise chain of analytic pieces, open or closed — see [Path3].
 *
 * A value kind of its own for exactly the reason [Point3Value] is one (OP-17/OP-25): a `Profile` in this
 * engine means "in some plane's own coordinates", so widening it with a third number would make every 2D
 * consumer start asking which plane. A curve with no plane is a different thing, so it is a different type,
 * and the type system keeps the two apart at every slot — which is also what stops a path in space from
 * silently filling a slot that wants a drawn 2D curve.
 *
 * Its *value* is world-space geometry; its *construction* is always parented (OP-26's parenting rule), so
 * planarity is a structural fact about the inputs rather than a measurement of the output.
 */
data class Path3Value(val path: Path3) : Value

data class LineValue(val line: Line) : Value

data class RayValue(val ray: Ray) : Value

data class SegmentValue(val seg: Segment) : Value

data class CircleValue(val circle: Circle) : Value

data class ArcValue(val arc: Arc) : Value

/**
 * An **ellipse** (OP-24): a first-class curve value, exact in every one of its numbers.
 *
 * It exists because the same mathematical object arrives from two directions — a *drawn* ellipse and the
 * *inclined section of a cylinder* — and shipping one as a flagged polyline while the other was exact
 * would make one object two citizens. See [Ellipse] for why the frame is not normalised to `a ≥ b`.
 */
data class EllipseValue(val ellipse: Ellipse) : Value

/** A piece of an ellipse, trimmed by **parametric angle** (OP-24) — see [EllipticArc]. */
data class EllipticArcValue(val arc: EllipticArc) : Value

data class PointSetValue(val set: PointSet) : Value

/**
 * An **ordered set of curves in space** (OP-26, step 6): OP-1's [PointSetValue] one dimension up — see
 * [Path3Set].
 *
 * A compound value with a `Select` beside it rather than one node per curve, for the reason [SectionValue]
 * is one: *how many* curves an intersection has is a function of the operands' values (a plane sliding along
 * a bent bar cuts it once, then twice, then once again), so a set of nodes sized by it would have to be
 * regenerated on every edit. The index is structural and taken verbatim on replay; an index the geometry no
 * longer has is invalid with a reason and heals (OP-3).
 */
data class Path3SetValue(val set: Path3Set) : Value

data class DirectionValue(val dir: Direction) : Value

data class ProfileValue(val profile: Profile) : Value

/** A cubic Bézier — a pure function of its (possibly constructed) control points (OP-15). */
data class BezierValue(val bezier: Bezier) : Value

/** A closed, oriented boundary — the result layer's own value type (OP-14). */
data class LoopValue(val loop: Loop) : Value

/**
 * A curve that **separates its plane into two sides** (OP-22's extension) — see [Chain].
 *
 * A value kind of its own rather than a [ProfileValue] with two rays bolted on, and the reason is what the
 * type is *for*: every other 2D curve value is a thing to be drawn, intersected or bounded, while this one
 * is a thing to be **cut with**, and it is legal only while it is properly embedded. Keeping it apart is
 * what lets that condition be a property of the value — a self-intersecting chain is an invalid node with a
 * reason (OP-3), so it hides everything cut with it and heals when a point is dragged clear — instead of a
 * refusal each consumer would have to remember to make.
 */
data class ChainValue(val chain: Chain) : Value

/** An area (outer boundary + holes): what the 2D→3D seam consumes (OP-14, OP-17). */
data class RegionValue(val region: Region) : Value

/**
 * A **placement frame** (OP-16): the local→world map of a placed group, as an origin plus a rotation
 * [angle] (radians, the base unit for angles).
 *
 * One value, not three scalars, because moving a group must be *one* literal write on *one* source
 * node — that is what makes a move O(1) and a single undo entry, structurally identical to dragging a
 * free point. Rotation is part of the same value for the same reason. The same concept one dimension
 * up is a sketch plane (OP-17).
 */
data class FrameValue(val origin: Vec2, val angle: Double) : Value {
    /** Where local point [p] lands in world coordinates: rotate by [angle], then translate. */
    fun toWorld(p: Vec2): Vec2 {
        val c = kotlin.math.cos(angle)
        val s = kotlin.math.sin(angle)
        return Vec2(origin.x + p.x * c - p.y * s, origin.y + p.x * s + p.y * c)
    }

    /** The inverse: which local point [w] is. What a drag of a frame-relative point inverts through. */
    fun toLocal(w: Vec2): Vec2 {
        val c = kotlin.math.cos(angle)
        val s = kotlin.math.sin(angle)
        val dx = w.x - origin.x
        val dy = w.y - origin.y
        return Vec2(dx * c + dy * s, -dx * s + dy * c)
    }
}

/**
 * A **sketch plane** (OP-17): the frame a 2D construction is embedded on. The same concept as a
 * placement frame ([FrameValue], OP-16) one dimension up, which is why 2D geometry is not made
 * plane-resident — one region can be embedded on several planes.
 */
data class PlaneValue(val plane: Plane3) : Value

/** **The seam** (OP-17): result-layer regions (OP-14) embedded on a plane. */
data class SketchValue(val sketch: Sketch3) : Value

/**
 * A solid: an analytic feature description **plus** its derived mesh (OP-9). A distinct type from a
 * future mesh-only value — that partition is what the type system has to enforce, since a mesh never
 * lifts back to analytic geometry while a solid's faces and edges remain exact.
 */
data class SolidValue(val solid: Solid3) : Value

/**
 * The **section of a solid at a plane** (OP-17's section-inputs package): a *compound* value with
 * accessors, exactly as [PointSetValue] is (OP-6).
 *
 * One value rather than a node per curve, for the reason OP-21 states and the *Key points* tool already
 * demonstrates one dimension down: how many curves a section has is a function of the solid's **values** (a
 * plane slid past a corner cuts one face fewer), so a set of nodes sized by it would have to be regenerated
 * on every edit. The set is ordered by the feature's own structure, an accessor addresses it by index, and an
 * index the plane misses is invalid with a reason and heals (OP-3).
 */
data class SectionValue(val section: PlaneSection) : Value

/** Result of evaluating a node: valid value or invalid with a reason (OP-3). */
sealed interface EvalResult {
    data class Ok(val value: Value) : EvalResult

    data class Invalid(val reason: String) : EvalResult
}

/** A node in the construction DAG. Stable [id]; pure function of its [inputs] (OP-5). */
abstract class Node(val id: String) {
    abstract val inputs: List<Node>

    /** Compute this node's value from already-evaluated (valid) input values. */
    abstract fun compute(args: List<Value>): EvalResult

    // ---- persistent memo: the OP-5 dirty-marking, kept (see the note on [Evaluator]) ----

    private var memoArgs: List<Value>? = null
    private var memoResult: EvalResult.Ok? = null

    /**
     * Whether this node's result may be memoized against the *identity* of its arguments — i.e. whether
     * [compute] is a function of `args` alone, or of anything else it can read.
     *
     * True for everything derived. [SourceNode] and [ParameterNode] read their own mutable literal, which
     * no argument carries, and are memoized anyway because *they invalidate themselves* on every write
     * (see their setters); [InstanceNode] is the one kind that must opt out, because it evaluates
     * *another* node's `compute` and cannot see that node's writes.
     */
    open val cacheable: Boolean get() = true

    /**
     * How often [compute] actually ran on this node — the instrument the incremental-recompute
     * acceptance is stated in (a repaint that changes nothing upstream must leave every count where
     * it was). Per node, so it is document-scoped like the memo itself, with no shared static.
     */
    var computeCount: Int = 0
        private set

    /**
     * How often a **mesh** was actually derived from a solid this node produced — the same instrument one
     * axis further on ([constructit.geom.Solid3]).
     *
     * A solid's triangles are derived on first demand, so `computeCount` alone can no longer say what a
     * gesture cost: the acceptance of the deferral is that a plan drag recomputes features and builds *no*
     * triangles, while the 3D view builds them the moment it is asked. Per node, document-scoped, no shared
     * static, for [computeCount]'s reason.
     *
     * Counted where it happens rather than where it is triggered: the node hands its value a note saying
     * *tell me when you build*, and the consumer that forces the mesh — the 3D scene, a volume, an export —
     * is charged against the node that owns the body. A value handed straight on (an identity placement) keeps
     * counting against the node that made it, since the note is set once.
     */
    var meshCount: Int = 0
        private set

    /**
     * Drop this node's memo. Called at every **mutation point** (OP-5): a literal write, a weld or wire
     * or capture re-pointing [SourceNode.boundTo] / [ParameterNode.boundTo] / [IndirectNode.boundTo].
     *
     * Only *this* node is marked — nothing walks the dependents, and nothing has to. A recomputed node
     * hands its consumers a **new value instance**, and a consumer's memo is keyed on the identity of the
     * values it last consumed, so the dirty mark travels downstream by itself, through exactly the
     * affected cone, with no reverse-dependency index to keep in step with a re-pointed edge.
     */
    fun invalidate() {
        memoArgs = null
        memoResult = null
    }

    /**
     * [compute], skipped when this node already holds the result for these very argument values.
     *
     * Identity (`===`), not equality: an unchanged upstream node returns its memoized value *object*, so
     * the check is O(1) per input however large the value is (a revolve's mesh compares as one pointer),
     * and it is conservative in the safe direction — a recomputed input that lands on an equal-but-new
     * value costs a recompute, it never yields a stale one.
     *
     * **Invalidity is never memoized (OP-3).** A node can be invalid for a reason outside its arguments —
     * the general boolean engine not being loaded yet is the standing case — and OP-3 promises it heals
     * the moment that changes. Recomputing an invalid node every pass is what keeps that promise; it also
     * costs little, since an invalid node's inputs are already known good and the failure is usually fast.
     */
    fun computeMemoized(args: List<Value>): EvalResult {
        if (cacheable) {
            val prev = memoResult
            if (prev != null && sameValues(memoArgs, args)) return prev
        }
        computeCount++
        val result = compute(args)
        // The mesh half of the instrument: a solid this node just produced counts its derivation here, if it
        // is not already counting it somewhere upstream (see [meshCount] and [constructit.geom.Solid3.meterTo]).
        ((result as? EvalResult.Ok)?.value as? SolidValue)?.solid?.meterTo { meshCount++ }
        if (cacheable && result is EvalResult.Ok) {
            memoArgs = args
            memoResult = result
        } else {
            invalidate()
        }
        return result
    }
}

/** Argument lists that are the same values — the memo's freshness test (OP-5). */
private fun sameValues(
    a: List<Value>?,
    b: List<Value>,
): Boolean {
    if (a == null || a.size != b.size) return false
    for (i in b.indices) if (a[i] !== b[i]) return false
    return true
}

/**
 * A source node holding a mutable literal (free point / parameter). It is an independent DOF
 * while [boundTo] is null (it outputs [value]); **welding** it onto another node makes it track
 * that node's value, removing the DOF — the point-level analog of parameter wiring (see
 * [ParameterNode]). Because binding mutates this node in place, every reference already pointing
 * here transparently follows the new master; no immutable input list is ever rewired.
 */
class SourceNode(id: String, value: Value, boundTo: Node? = null) : Node(id) {
    /** The literal this node carries while free. Writing it is a **mutation point** (OP-5). */
    var value: Value = value
        set(v) {
            field = v
            invalidate()
        }

    /** The master this node tracks, or null while it is a DOF. Re-pointing is a mutation point (OP-5). */
    var boundTo: Node? = boundTo
        set(v) {
            field = v
            invalidate()
        }

    override val inputs: List<Node> get() = boundTo?.let { listOf(it) } ?: emptyList()

    override fun compute(args: List<Value>): EvalResult =
        if (boundTo != null) EvalResult.Ok(args[0]) else EvalResult.Ok(value)
}

/**
 * A named scalar parameter (OP-7). It is an independent DOF while [boundTo] is null (it outputs
 * [literal]); **wiring** it to another scalar node makes it track that node's value, removing
 * the DOF — the constructive form of an equality constraint (shared reference, OP-5).
 */
class ParameterNode(id: String, literal: ScalarValue, boundTo: Node? = null) : Node(id) {
    /** The number this parameter carries while free. Retyping it is a **mutation point** (OP-5). */
    var literal: ScalarValue = literal
        set(v) {
            field = v
            invalidate()
        }

    /** The scalar this parameter is wired to, or null while it is a DOF — a mutation point (OP-5). */
    var boundTo: Node? = boundTo
        set(v) {
            field = v
            invalidate()
        }

    override val inputs: List<Node> get() = boundTo?.let { listOf(it) } ?: emptyList()

    override fun compute(args: List<Value>): EvalResult =
        if (boundTo != null) EvalResult.Ok(args[0]) else EvalResult.Ok(literal)
}

/**
 * A **re-pointable indirection**: it yields [target]'s value until [boundTo] is set, and the bound
 * node's value from then on.
 *
 * This is [SourceNode]'s binding substrate (OP-5) generalized from a mutable *literal* to a *derived*
 * value, and it exists for one reason: a position held as two independent scalar coordinates cannot be
 * captured per axis. Placing an ortho path under a group frame (OP-16) has to insert
 * `frameApply(frame, …)` between a vertex and everything that consumes it — a turned frame mixes x into
 * y, so no per-axis binding can express `world = f(frame, lx, ly)` — while the vertex's own `pointXY`
 * inputs stay exactly as they were.
 *
 * Binding *in place* is what makes that a retrofit rather than a rebuild: every segment, region,
 * footprint and measurement already pointing here follows the frame with no input list rewired, exactly
 * as a welded point's consumers follow its master. Unbinding restores the original view, which is what
 * makes the capture invertible.
 */
class IndirectNode(id: String, val target: Node, boundTo: Node? = null) : Node(id) {
    /** The node this view is captured onto, or null for the original target — a mutation point (OP-5). */
    var boundTo: Node? = boundTo
        set(v) {
            field = v
            invalidate()
        }

    override val inputs: List<Node> get() = listOf(boundTo ?: target)

    override fun compute(args: List<Value>): EvalResult = EvalResult.Ok(args[0])
}

/** A derived node whose value is computed by [fn] from its inputs. */
class OpNode(
    id: String,
    override val inputs: List<Node>,
    private val fn: (List<Value>) -> EvalResult,
) : Node(id) {
    override fun compute(args: List<Value>): EvalResult = fn(args)
}

/**
 * One node of a macro **instance** (OP-6): definition node [defNode] evaluated under this instance's
 * argument bindings, addressed by the derived path-id `M/nk`.
 *
 * **Virtual addressing, not copying.** The wrapper holds no computation of its own — it delegates to
 * the definition node's [compute] and only substitutes the inputs — so a definition edit is seen by
 * every instance on the next evaluation pass. That is what makes edit-propagation automatic instead of
 * a synchronization problem, and it is why an instance is a *function* of its arguments rather than a
 * stamped copy of the geometry.
 */
class InstanceNode(id: String, val defNode: Node, override val inputs: List<Node>) : Node(id) {
    override fun compute(args: List<Value>): EvalResult = defNode.compute(args)

    /**
     * **The one kind that opts out of the memo** (OP-5), and only over a *source* definition node: this
     * wrapper runs somebody else's `compute`, and a [SourceNode] / [ParameterNode] reads its own literal
     * and its own bound-or-free state — neither of which is in this instance's arguments, so a write over
     * there would leave a memo here that nothing invalidates. Nothing is lost: such a delegation is a
     * pass-through, so it costs a field read and still hands its consumers the same value object, which is
     * what their memos are keyed on.
     */
    override val cacheable: Boolean
        get() = defNode !is SourceNode && defNode !is ParameterNode && defNode.cacheable
}

/**
 * Evaluates the DAG (OP-5). Invalidity propagates transitively: a node depending on any invalid input is
 * itself invalid (OP-3). Shared sub-expressions (e.g. a PointSet feeding two Selects) are visited once.
 *
 * **Two memos, one behaviour.** The per-pass map keyed by node id collapses the pass's own repeats and is
 * what makes a diamond-shaped graph a linear walk. Under it sits each node's **persistent memo**
 * ([Node.computeMemoized]), which survives the pass: a fresh Evaluator per repaint, per hit-test, per
 * handle read stays the API — hundreds of call sites depend on it — while the expensive work behind it,
 * a revolve's tessellation above all, happens only when something the node actually depends on has
 * changed. That is OP-5's dirty-marking: a drag mutates one literal, which invalidates that one node
 * (see [Node.invalidate]), and the new value object it produces carries the mark down its own cone and
 * nowhere else. A pass over untouched geometry does a pointer-compare per edge and no arithmetic.
 *
 * Parametric recompute is still: mutate a source + a new pass.
 */
class Evaluator {
    private val cache = HashMap<String, EvalResult>()

    fun eval(node: Node): EvalResult {
        cache[node.id]?.let { return it }
        val argResults = node.inputs.map { eval(it) }
        val invalid = argResults.firstOrNull { it is EvalResult.Invalid } as EvalResult.Invalid?
        val result: EvalResult =
            if (invalid != null) {
                EvalResult.Invalid("depends on invalid input (${invalid.reason})")
            } else {
                try {
                    node.computeMemoized(argResults.map { (it as EvalResult.Ok).value })
                } catch (e: Exception) {
                    EvalResult.Invalid(e.message ?: e.toString())
                }
            }
        cache[node.id] = result
        return result
    }
}
