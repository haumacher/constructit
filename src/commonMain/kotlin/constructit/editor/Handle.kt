package constructit.editor

import constructit.core.CircleValue
import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.LineValue
import constructit.core.PointValue
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

    /**
     * The source nodes [drag] writes. Deliberately not defaulted: a handle that cannot say what its
     * drag touches cannot be told apart from one whose drag is inert, and an inert drag that still
     * accepts the grab is indistinguishable from a bug.
     *
     * Note this is *not* the union of [fields]' nodes — a field may re-parameterize a different node
     * than the drag writes. A leg's drag moves it perpendicular (one shared node) while its length
     * fields write the nodes along it, so a leg can be immovable and still have editable lengths.
     */
    val dragNodes: List<SourceNode>

    /** False when every node [drag] would write is driven, making the drag inert. */
    val dragMovable: Boolean get() = dragNodes.any { it.boundTo == null }
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

/**
 * Interpret a number typed in a UI field as a [Quantity] of [dim]. The display units are mm and
 * degrees; base units stay mm and radians (see the units policy).
 */
fun quantityOf(
    dim: Dimension,
    value: Double,
): Quantity =
    when (dim) {
        Dimension.ANGLE -> Quantity.deg(value)
        Dimension.LENGTH -> Quantity.mm(value)
        else -> Quantity.number(value)
    }

/**
 * Why grabbing [el] cannot move it, in the user's terms.
 *
 * An element with no writable field is immovable *by construction*, not by accident: an attached or
 * welded end binds a coordinate node, and because that node is **shared** with the neighbour, the
 * adjacent leg's single DOF goes with it. That is the intended consequence of the connection — but it
 * is invisible, so a silent dead drag reads as a bug. Name the driven values instead; the inspector
 * greys out exactly the same ones.
 */
fun explainImmovable(el: Element): String {
    val handle = el.handle
    val dragged = handle?.dragNodes.orEmpty().toSet()
    val driven = handle?.fields().orEmpty().filter { it.node in dragged && !it.writable }.map { it.label }
    if (driven.isEmpty()) return "${el.id} can't be moved: it is fully determined by the construction."
    val verb = if (driven.size == 1) "is" else "are"
    val editable = handle?.fields().orEmpty().filter { it.writable }.map { it.label }
    val alternative = if (editable.isEmpty()) "" else " You can still set ${editable.joinToString(", ")} in the panel."
    return "${el.id} has no free direction: ${driven.joinToString(", ")} $verb driven by the construction " +
        "(a welded or attached end, or a closed loop). Move what drives it instead.$alternative"
}

/** The effective point value of [node] — its literal, or whatever drives it. */
private fun pointOf(
    node: SourceNode,
    ev: Evaluator,
): Vec2? = ((ev.eval(node) as? EvalResult.Ok)?.value as? PointValue)?.p

/**
 * A free point. Its two coordinates are the only DOF, and giving it a handle like everything else is
 * what lets a point's position be typed as well as dragged (OP-13) — and saved by the same mechanism
 * that saves every other value.
 */
class FreePointHandle(private val node: SourceNode) : Handle {
    override val dragNodes: List<SourceNode> get() = listOf(node)

    override fun drag(
        world: Vec2,
        ev: Evaluator,
    ) {
        node.value = PointValue(world)
    }

    override fun fields(): List<HandleField> = listOf(pointCoordField("x", node, 0), pointCoordField("y", node, 1))
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

/** A field over one component of a point-valued source node: [axis] 0 = x, 1 = y. */
fun pointCoordField(
    label: String,
    node: SourceNode,
    axis: Int,
) = HandleField(
    label,
    node,
    Dimension.LENGTH,
    { ev -> pointOf(node, ev)?.let { Quantity.mm(if (axis == 0) it.x else it.y) } },
    { q ->
        val p = pointOf(node, Evaluator()) ?: Vec2(0.0, 0.0)
        node.value = PointValue(if (axis == 0) Vec2(q.mm, p.y) else Vec2(p.x, q.mm))
    },
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
    override val dragNodes: List<SourceNode> get() = listOf(t)

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

    /** Which coordinate is this vertex's *own* (not shared with a neighbour): 0 = x, 1 = y — the axis
     *  its incoming leg runs along, and so the one its leg length is measured on. */
    var ownCoord: Int = 0

    /** The far end of the leg that created this vertex: the node its own coordinate is measured
     *  from, so the leg's length is a field of *this* handle. Null for a path's start vertex. */
    var legAnchor: SourceNode? = null

    /** This vertex's own coordinate node — the one its incoming leg runs along. */
    val ownNode: SourceNode get() = if (ownCoord == 0) xNode else yNode

    override val dragNodes: List<SourceNode> get() = listOf(xNode, yNode)

    override fun drag(
        world: Vec2,
        ev: Evaluator,
    ) {
        xNode.value = ScalarValue(Quantity.mm(world.x))
        yNode.value = ScalarValue(Quantity.mm(world.y))
    }

    override fun fields(): List<HandleField> =
        listOf(coordField("x", xNode), coordField("y", yNode)) +
            (legAnchor?.let { listOf(lengthField("leg length", ownNode, it)) } ?: emptyList())
}

/**
 * A whole leg of an ortho path. Its two endpoints *share* the coordinate perpendicular to it (that
 * sharing is what keeps the leg axis-aligned), so the leg has exactly one DOF of its own: dragging
 * it writes that one node, moving both endpoints together and stretching the two neighbouring legs.
 * Nothing else in the document moves — the same locality a vertex drag has.
 *
 * Its [fields] are that perpendicular position plus the leg's **length from either end**. A length
 * spans two vertices, so there is no single write for it: each end is a separate field, labelled by
 * which end moves, matching the two drags (of either endpoint, along the leg) that already exist.
 */
class OrthoEdgeHandle(private val path: OrthoPath, private val leg: Element) : Handle {
    /** 0 = horizontal leg (shared coordinate is y), 1 = vertical (shared x), null if detached. */
    val axis: Int? get() = path.legIndexOf(leg).takeIf { it >= 0 }?.let { path.legAxis(it) }

    /** The coordinate node both endpoints share — the leg's own single DOF. */
    val sharedNode: SourceNode?
        get() {
            val i = path.legIndexOf(leg).takeIf { it >= 0 } ?: return null
            val a = path.legEnds(i).first.corner
            return if (path.legAxis(i) == 0) a.yNode else a.xNode
        }

    /** The two nodes along the leg, in draw order — their difference is the leg's length. */
    private fun alongNodes(): Pair<SourceNode, SourceNode>? {
        val i = path.legIndexOf(leg).takeIf { it >= 0 } ?: return null
        val (a, b) = path.legEnds(i)
        return if (path.legAxis(i) == 0) a.corner.xNode to b.corner.xNode else a.corner.yNode to b.corner.yNode
    }

    override val dragNodes: List<SourceNode> get() = listOfNotNull(sharedNode)

    override fun drag(
        world: Vec2,
        ev: Evaluator,
    ) {
        val node = sharedNode ?: return
        node.value = ScalarValue(Quantity.mm(if (axis == 0) world.y else world.x))
    }

    override fun fields(): List<HandleField> {
        val shared = sharedNode ?: return emptyList()
        val position = coordField(if (axis == 0) "y" else "x", shared)
        val along = alongNodes() ?: return listOf(position)
        val (start, end) = along
        return listOf(
            position,
            lengthField("length (move end)", end, start),
            lengthField("length (move start)", start, end),
        )
    }
}

/** Point on a circle: the handle's one DOF is the angle around the centre. */
class OnCircleHandle(val circle: CircleRef, private val angle: SourceNode) : Handle {
    override val dragNodes: List<SourceNode> get() = listOf(angle)

    override fun drag(
        world: Vec2,
        ev: Evaluator,
    ) {
        val c = (ev.valueOf(circle) as? CircleValue)?.circle ?: return
        angle.value = ScalarValue(Quantity.rad((world - c.center).angle()))
    }

    override fun fields(): List<HandleField> = listOf(angleField("angle", angle))
}
