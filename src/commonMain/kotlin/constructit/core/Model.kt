package constructit.core

import constructit.geom.Arc
import constructit.geom.Bezier
import constructit.geom.Circle
import constructit.geom.Direction
import constructit.geom.Line
import constructit.geom.Loop
import constructit.geom.Plane3
import constructit.geom.PointSet
import constructit.geom.Profile
import constructit.geom.Ray
import constructit.geom.Region
import constructit.geom.Segment
import constructit.geom.Sketch3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.units.Quantity

/** A typed value flowing through the graph (OP-5). Strongly typed, one output per node. */
sealed interface Value

data class ScalarValue(val q: Quantity) : Value

data class PointValue(val p: Vec2) : Value

data class LineValue(val line: Line) : Value

data class RayValue(val ray: Ray) : Value

data class SegmentValue(val seg: Segment) : Value

data class CircleValue(val circle: Circle) : Value

data class ArcValue(val arc: Arc) : Value

data class PointSetValue(val set: PointSet) : Value

data class DirectionValue(val dir: Direction) : Value

data class ProfileValue(val profile: Profile) : Value

/** A cubic Bézier — a pure function of its (possibly constructed) control points (OP-15). */
data class BezierValue(val bezier: Bezier) : Value

/** A closed, oriented boundary — the result layer's own value type (OP-14). */
data class LoopValue(val loop: Loop) : Value

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
}

/**
 * A source node holding a mutable literal (free point / parameter). It is an independent DOF
 * while [boundTo] is null (it outputs [value]); **welding** it onto another node makes it track
 * that node's value, removing the DOF — the point-level analog of parameter wiring (see
 * [ParameterNode]). Because binding mutates this node in place, every reference already pointing
 * here transparently follows the new master; no immutable input list is ever rewired.
 */
class SourceNode(id: String, var value: Value, var boundTo: Node? = null) : Node(id) {
    override val inputs: List<Node> get() = boundTo?.let { listOf(it) } ?: emptyList()

    override fun compute(args: List<Value>): EvalResult =
        if (boundTo != null) EvalResult.Ok(args[0]) else EvalResult.Ok(value)
}

/**
 * A named scalar parameter (OP-7). It is an independent DOF while [boundTo] is null (it outputs
 * [literal]); **wiring** it to another scalar node makes it track that node's value, removing
 * the DOF — the constructive form of an equality constraint (shared reference, OP-5).
 */
class ParameterNode(id: String, var literal: ScalarValue, var boundTo: Node? = null) : Node(id) {
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
class IndirectNode(id: String, val target: Node, var boundTo: Node? = null) : Node(id) {
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
}

/**
 * Evaluates the DAG with per-pass memoization (OP-5). Invalidity propagates transitively:
 * a node depending on any invalid input is itself invalid (OP-3). Shared sub-expressions
 * (e.g. a PointSet feeding two Selects) are computed once.
 *
 * A fresh Evaluator is used per pass; parametric recompute = mutate a SourceNode + new pass.
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
                    node.compute(argResults.map { (it as EvalResult.Ok).value })
                } catch (e: Exception) {
                    EvalResult.Invalid(e.message ?: e.toString())
                }
            }
        cache[node.id] = result
        return result
    }
}
