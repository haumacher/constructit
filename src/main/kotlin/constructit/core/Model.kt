package constructit.core

import constructit.geom.Arc
import constructit.geom.Circle
import constructit.geom.Line
import constructit.geom.PointSet
import constructit.geom.Segment
import constructit.geom.Vec2
import constructit.units.Quantity

/** A typed value flowing through the graph (OP-5). Strongly typed, one output per node. */
sealed interface Value
data class ScalarValue(val q: Quantity) : Value
data class PointValue(val p: Vec2) : Value
data class LineValue(val line: Line) : Value
data class SegmentValue(val seg: Segment) : Value
data class CircleValue(val circle: Circle) : Value
data class ArcValue(val arc: Arc) : Value
data class PointSetValue(val set: PointSet) : Value

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

/** A source node holding a mutable literal (free point / parameter). No inputs. */
class SourceNode(id: String, var value: Value) : Node(id) {
    override val inputs: List<Node> = emptyList()
    override fun compute(args: List<Value>): EvalResult = EvalResult.Ok(value)
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
