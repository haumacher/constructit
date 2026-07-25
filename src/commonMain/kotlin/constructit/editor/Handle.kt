package constructit.editor

import constructit.core.CircleValue
import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.LineValue
import constructit.core.ScalarValue
import constructit.core.SourceNode
import constructit.dsl.CircleRef
import constructit.dsl.LineRef
import constructit.dsl.valueOf
import constructit.geom.Vec2
import constructit.units.Dimension
import constructit.units.Quantity

/**
 * A grabbable degree of freedom of an [Element]: what dragging it writes, and what typing a value
 * writes. Those are the *same* operation on the same source nodes — [drag] is the continuous
 * binding, [fields] the discrete one — so no geometry is reachable by mouse but not by number, or
 * the reverse.
 *
 * (Deliberately not called a *constraint*: nothing here is asserted and solved. A handle only ever
 * writes free source nodes, and what stays invariant — a leg's axis, a point's curve — is invariant
 * *by construction*, because the nodes it does not write are shared with the geometry that must
 * follow. See OP-5.)
 */
interface Handle {
    /** Continuous binding: write what this handle can express toward the cursor at [world]. */
    fun drag(
        world: Vec2,
        ev: Evaluator,
    )

    /** Discrete binding: the numeric views of the very same write. */
    fun fields(): List<HandleField> = emptyList()
}

/**
 * One numeric view of a [Handle]'s write, over a single free [node].
 *
 * A field is always **affine in [node]** — a coordinate, a distance from a fixed anchor, an angle —
 * which is what lets [write] invert by exact arithmetic instead of a solve. It is also what answers
 * *"which end moves?"* for a quantity spanning two vertices: the field belongs to the handle that
 * moves, so a leg is editable from either end as two fields writing different nodes.
 */
class HandleField(
    val label: String,
    val node: SourceNode,
    val dim: Dimension,
    private val getter: (Evaluator) -> Quantity?,
    private val setter: (Quantity) -> Unit,
) {
    /**
     * False when [node] is driven by another node — welded, attached, or shared by a loop closure.
     * The value is then derived by construction, so it reads but cannot be written (and dragging
     * the handle along this field's direction is equally inert).
     */
    val writable: Boolean get() = node.boundTo == null

    fun read(ev: Evaluator): Quantity? = getter(ev)

    fun write(value: Quantity) {
        if (writable) setter(value)
    }
}

/** The effective value of [node] in base units — its literal, or whatever drives it. */
private fun baseOf(
    node: SourceNode,
    ev: Evaluator,
): Double? = ((ev.eval(node) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.base

/** A field reading and writing a length-valued coordinate [node] directly. */
fun coordField(
    label: String,
    node: SourceNode,
) = HandleField(
    label,
    node,
    Dimension.LENGTH,
    { ev -> baseOf(node, ev)?.let { Quantity.mm(it) } },
    { q -> node.value = ScalarValue(Quantity.mm(q.mm)) },
)

/** A field for an angle-valued [node]. */
fun angleField(
    label: String,
    node: SourceNode,
) = HandleField(
    label,
    node,
    Dimension.ANGLE,
    { ev -> baseOf(node, ev)?.let { Quantity.rad(it) } },
    { q -> node.value = ScalarValue(Quantity.rad(q.base)) },
)

/**
 * A field for the distance from [anchor] to [node] along the axis they share — a leg length, written
 * by moving [node]. Reported unsigned, and written keeping the leg's **current** direction, so
 * typing a length shortens or extends the leg where it already points instead of flipping it.
 */
fun lengthField(
    label: String,
    node: SourceNode,
    anchor: SourceNode,
) = HandleField(
    label,
    node,
    Dimension.LENGTH,
    { ev ->
        val a = baseOf(anchor, ev)
        val n = baseOf(node, ev)
        if (a == null || n == null) null else Quantity.mm(kotlin.math.abs(n - a))
    },
    { q ->
        val ev = Evaluator()
        val a = baseOf(anchor, ev) ?: 0.0
        val n = baseOf(node, ev) ?: a
        val dir = if (n < a) -1.0 else 1.0 // keep the direction the leg already runs in
        node.value = ScalarValue(Quantity.mm(a + dir * q.mm))
    },
)

/** Point on a line: the handle's one DOF is the signed distance along the line's direction. */
class OnLineHandle(private val line: LineRef, private val t: SourceNode) : Handle {
    override fun drag(
        world: Vec2,
        ev: Evaluator,
    ) {
        val l = (ev.valueOf(line) as? LineValue)?.line ?: return
        t.value = ScalarValue(Quantity.mm((world - l.origin).dot(l.dir)))
    }

    override fun fields(): List<HandleField> = listOf(coordField("along line", t))
}

/**
 * A corner of an ortho path/wall. The vertex is `pointXY(xNode, yNode)`; each coordinate node is
 * *shared* with one neighbour (a horizontal edge shares y, a vertical edge shares x), so writing the
 * dragged cursor into both nodes moves this vertex and exactly its two neighbours while keeping every
 * edge axis-aligned — no downstream cascade, no solver. A coordinate welded to the start (loop
 * closure) is bound and simply ignores the write.
 */
class OrthoCornerHandle(val xNode: SourceNode, val yNode: SourceNode) : Handle {
    /** True while this vertex terminates its path (degree 1) — the case that may weld/attach. */
    var isEndpoint: Boolean = true

    /** Which coordinate is this vertex's *own* (not shared with a neighbour): 0 = x, 1 = y. The one
     *  to bind when attaching to a line, so the shared coordinate stays free for the neighbour. */
    var ownCoord: Int = 0

    override fun drag(
        world: Vec2,
        ev: Evaluator,
    ) {
        xNode.value = ScalarValue(Quantity.mm(world.x))
        yNode.value = ScalarValue(Quantity.mm(world.y))
    }

    override fun fields(): List<HandleField> = listOf(coordField("x", xNode), coordField("y", yNode))
}

/** Point on a circle: the handle's one DOF is the angle around the centre. */
class OnCircleHandle(val circle: CircleRef, private val angle: SourceNode) : Handle {
    override fun drag(
        world: Vec2,
        ev: Evaluator,
    ) {
        val c = (ev.valueOf(circle) as? CircleValue)?.circle ?: return
        angle.value = ScalarValue(Quantity.rad((world - c.center).angle()))
    }

    override fun fields(): List<HandleField> = listOf(angleField("angle", angle))
}
