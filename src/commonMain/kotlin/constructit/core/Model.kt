package constructit.core

import constructit.geom.Arc
import constructit.geom.Bezier
import constructit.geom.Circle
import constructit.geom.Direction
import constructit.geom.Line
import constructit.geom.Loop
import constructit.geom.PointSet
import constructit.geom.Profile
import constructit.geom.Ray
import constructit.geom.Region
import constructit.geom.Segment
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

/** A derived node whose value is computed by [fn] from its inputs. */
class OpNode(
    id: String,
    override val inputs: List<Node>,
    private val fn: (List<Value>) -> EvalResult,
) : Node(id) {
    override fun compute(args: List<Value>): EvalResult = fn(args)
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
