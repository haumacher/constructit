package constructit.dsl

import constructit.core.ArcValue
import constructit.core.CircleValue
import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.LineValue
import constructit.core.Node
import constructit.core.OpNode
import constructit.core.PointSetValue
import constructit.core.PointValue
import constructit.core.ScalarValue
import constructit.core.SegmentValue
import constructit.core.SourceNode
import constructit.core.Value
import constructit.geom.Arc
import constructit.geom.Circle
import constructit.geom.GeomMath
import constructit.geom.Line
import constructit.geom.Segment
import constructit.geom.Vec2
import constructit.units.Dimension
import constructit.units.Quantity

/** A typed handle to a node's output. Compile-time typing over the generic graph (OP-5). */
class Ref<out V : Value>(val node: Node)

typealias ScalarRef = Ref<ScalarValue>
typealias PointRef = Ref<PointValue>
typealias LineRef = Ref<LineValue>
typealias SegmentRef = Ref<SegmentValue>
typealias CircleRef = Ref<CircleValue>
typealias ArcRef = Ref<ArcValue>
typealias PointSetRef = Ref<PointSetValue>

/**
 * Builder for a construction DAG. Generates stable ids; supports macro instantiation with
 * derived path-ids `M/nk` (OP-6). Source nodes (parameters, free points) are mutable so a
 * parameter edit + a fresh [Evaluator] pass re-propagates through the graph.
 */
class Construction {

    private data class Scope(val prefix: String, var counter: Int)
    private val scopes = ArrayDeque<Scope>().apply { addLast(Scope("", 0)) }

    private fun freshId(hint: String? = null): String {
        val scope = scopes.last()
        val local = hint ?: "n${++scope.counter}"
        val path = scopes.filter { it.prefix.isNotEmpty() }.joinToString("/") { it.prefix }
        return if (path.isEmpty()) local else "$path/$local"
    }

    /** Run [body] inside a macro instance namespace, so its nodes get ids `instanceId/nk` (OP-6). */
    fun <R> withInstance(instanceId: String, body: () -> R): R {
        scopes.addLast(Scope(instanceId, 0))
        try {
            return body()
        } finally {
            scopes.removeLast()
        }
    }

    private fun <V : Value> op(vararg inputs: Ref<*>, fn: (List<Value>) -> EvalResult): Ref<V> {
        val node = OpNode(freshId(), inputs.map { it.node }, fn)
        return Ref(node)
    }

    // ---- source nodes (adjustable parameters, constants, free points) ----

    /** An adjustable or constant parameter (OP-7). [constant] is a presentation flag (OP-6). */
    @Suppress("UNUSED_PARAMETER")
    fun parameter(name: String, value: Quantity, constant: Boolean = false): ScalarRef =
        Ref(SourceNode(freshId(name), ScalarValue(value)))

    fun const(value: Quantity): ScalarRef = Ref(SourceNode(freshId(), ScalarValue(value)))

    fun freePoint(name: String, x: Quantity, y: Quantity): PointRef =
        Ref(SourceNode(freshId(name), PointValue(Vec2(x.mm, y.mm))))

    /** Mutate a source scalar for a parametric recompute. */
    fun set(ref: ScalarRef, value: Quantity) {
        (ref.node as SourceNode).value = ScalarValue(value)
    }

    /** Mutate a source point for a parametric recompute. */
    fun set(ref: PointRef, x: Quantity, y: Quantity) {
        (ref.node as SourceNode).value = PointValue(Vec2(x.mm, y.mm))
    }

    // ---- scalar arithmetic (graph-level expressions; OP-7 string parser deferred) ----

    fun scale(a: ScalarRef, factor: Double): ScalarRef =
        op(a) { EvalResult.Ok(ScalarValue((it[0] as ScalarValue).q * factor)) }

    fun add(a: ScalarRef, b: ScalarRef): ScalarRef =
        op(a, b) { EvalResult.Ok(ScalarValue((it[0] as ScalarValue).q + (it[1] as ScalarValue).q)) }

    fun sub(a: ScalarRef, b: ScalarRef): ScalarRef =
        op(a, b) { EvalResult.Ok(ScalarValue((it[0] as ScalarValue).q - (it[1] as ScalarValue).q)) }

    fun neg(a: ScalarRef): ScalarRef =
        op(a) { EvalResult.Ok(ScalarValue(-(it[0] as ScalarValue).q)) }

    // ---- point construction ----

    fun pointXY(x: ScalarRef, y: ScalarRef): PointRef =
        op(x, y) {
            val px = (it[0] as ScalarValue).q.mm
            val py = (it[1] as ScalarValue).q.mm
            EvalResult.Ok(PointValue(Vec2(px, py)))
        }

    fun translate(p: PointRef, dx: ScalarRef, dy: ScalarRef): PointRef =
        op(p, dx, dy) {
            val base = (it[0] as PointValue).p
            EvalResult.Ok(PointValue(base + Vec2((it[1] as ScalarValue).q.mm, (it[2] as ScalarValue).q.mm)))
        }

    fun polarPoint(center: PointRef, radius: ScalarRef, angle: ScalarRef): PointRef =
        op(center, radius, angle) {
            val c = (it[0] as PointValue).p
            val r = (it[1] as ScalarValue).q.mm
            val a = (it[2] as ScalarValue).q.requireDim(Dimension.ANGLE, "angle").base
            EvalResult.Ok(PointValue(c + Vec2(r * Math.cos(a), r * Math.sin(a))))
        }

    fun midpoint(a: PointRef, b: PointRef): PointRef =
        op(a, b) {
            EvalResult.Ok(PointValue(((it[0] as PointValue).p + (it[1] as PointValue).p) * 0.5))
        }

    // ---- curves ----

    fun lineThrough(a: PointRef, b: PointRef): LineRef =
        op(a, b) {
            val pa = (it[0] as PointValue).p
            val pb = (it[1] as PointValue).p
            if ((pb - pa).length() < Vec2.EPS) EvalResult.Invalid("line through coincident points")
            else EvalResult.Ok(LineValue(Line(pa, (pb - pa).normalized())))
        }

    fun segment(a: PointRef, b: PointRef): SegmentRef =
        op(a, b) { EvalResult.Ok(SegmentValue(Segment((it[0] as PointValue).p, (it[1] as PointValue).p))) }

    fun circleCR(center: PointRef, radius: ScalarRef): CircleRef =
        op(center, radius) {
            val r = (it[1] as ScalarValue).q.mm
            if (r <= 0.0) EvalResult.Invalid("non-positive radius")
            else EvalResult.Ok(CircleValue(Circle((it[0] as PointValue).p, r)))
        }

    fun arc(center: PointRef, radius: ScalarRef, startAngle: ScalarRef, endAngle: ScalarRef, ccw: Boolean = true): ArcRef =
        op(center, radius, startAngle, endAngle) {
            val r = (it[1] as ScalarValue).q.mm
            val a0 = (it[2] as ScalarValue).q.requireDim(Dimension.ANGLE, "startAngle").base
            val a1 = (it[3] as ScalarValue).q.requireDim(Dimension.ANGLE, "endAngle").base
            if (r <= 0.0) EvalResult.Invalid("non-positive radius")
            else EvalResult.Ok(ArcValue(Arc((it[0] as PointValue).p, r, a0, a1, ccw)))
        }

    // ---- intersections & selection (OP-1) ----

    fun intersectCC(c1: CircleRef, c2: CircleRef): PointSetRef =
        op(c1, c2) { EvalResult.Ok(PointSetValue(GeomMath.intersectCC((it[0] as CircleValue).circle, (it[1] as CircleValue).circle))) }

    fun intersectLL(l1: LineRef, l2: LineRef): PointSetRef =
        op(l1, l2) { EvalResult.Ok(PointSetValue(GeomMath.intersectLL((it[0] as LineValue).line, (it[1] as LineValue).line))) }

    fun intersectLC(line: LineRef, c: CircleRef): PointSetRef =
        op(line, c) { EvalResult.Ok(PointSetValue(GeomMath.intersectLC((it[0] as LineValue).line, (it[1] as CircleValue).circle))) }

    /** Pick a branch from a solution set. sign >= 0 -> first (left), sign < 0 -> last (right). */
    fun select(set: PointSetRef, sign: Int): PointRef =
        op(set) {
            val pts = (it[0] as PointSetValue).set.points
            when {
                pts.isEmpty() -> EvalResult.Invalid("empty intersection")
                sign >= 0 -> EvalResult.Ok(PointValue(pts.first()))
                else -> EvalResult.Ok(PointValue(pts.last()))
            }
        }

    // ---- measurements (OP-4) ----

    fun measureDistance(a: PointRef, b: PointRef): ScalarRef =
        op(a, b) { EvalResult.Ok(ScalarValue(Quantity.mm(((it[1] as PointValue).p - (it[0] as PointValue).p).length()))) }
}

// ---- typed accessors over an Evaluator pass ----

fun Evaluator.resultOf(ref: Ref<*>): EvalResult = eval(ref.node)
fun Evaluator.isValid(ref: Ref<*>): Boolean = eval(ref.node) is EvalResult.Ok
fun Evaluator.valueOf(ref: Ref<*>): Value? = (eval(ref.node) as? EvalResult.Ok)?.value

fun Evaluator.point(ref: PointRef): Vec2 = (valueOf(ref) as PointValue).p
fun Evaluator.scalar(ref: ScalarRef): Quantity = (valueOf(ref) as ScalarValue).q
fun Evaluator.line(ref: LineRef): Line = (valueOf(ref) as LineValue).line
fun Evaluator.circle(ref: CircleRef): Circle = (valueOf(ref) as CircleValue).circle
fun Evaluator.arc(ref: ArcRef): Arc = (valueOf(ref) as ArcValue).arc
