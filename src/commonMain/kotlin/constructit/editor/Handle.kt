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
    /**
     * Overridable because an ortho coordinate is writable *through* its binding chain: it is bound to
     * its neighbour's to hold the leg axis-aligned, and writing it means writing the master they both
     * resolve to. Only a chain ending in derived geometry is truly unwritable.
     */
    private val writableWhen: () -> Boolean = { node.boundTo == null },
) {
    /**
     * False when the value is derived by construction — welded or attached — so it reads but cannot be
     * written (and dragging the handle along this field's direction is equally inert).
     */
    val writable: Boolean get() = writableWhen()

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
    val fields = handle?.fields().orEmpty()
    val dragged = handle?.dragNodes.orEmpty().toSet()
    // Normally the reason is a field the drag would have written. When the drag can write *nothing* —
    // its whole binding chain ends in derived geometry, so there is no master to name — every
    // unwritable field is the reason instead.
    val candidates = fields.filter { it.node in dragged }.ifEmpty { fields }
    val driven = candidates.filter { !it.writable }.map { it.label }
    if (driven.isEmpty()) return "${el.id} can't be moved: it is fully determined by the construction."
    val verb = if (driven.size == 1) "is" else "are"
    val editable = fields.filter { it.writable }.map { it.label }
    val alternative = if (editable.isEmpty()) "" else " You can still set ${editable.joinToString(", ")} in the panel."
    return "${el.id} has no free direction: ${driven.joinToString(", ")} $verb driven by the construction " +
        "(a welded or attached end, or a closed loop). Move what drives it instead.$alternative"
}

/**
 * Where a write to [node] must actually go: the end of its chain of [SourceNode.boundTo] links to
 * other source nodes.
 *
 * An ortho leg is axis-aligned because one endpoint's coordinate is *bound* to the other's, so a value
 * lives at the end of such a chain and every vertex along it follows. Null when the chain ends in
 * derived geometry (an attach, a weld) — then the coordinate is determined by construction and no
 * write is possible at all.
 */
fun writableMaster(node: SourceNode): SourceNode? {
    var n = node
    var guard = 0
    while (guard++ < 64) {
        val next = n.boundTo ?: return n
        n = next as? SourceNode ?: return null
    }
    return null
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

/**
 * A coordinate of an ortho vertex. Reads the value it evaluates to; writes go to the master of its
 * binding chain, so moving a vertex carries the neighbours whose coordinate is bound to the same node
 * — which is exactly what keeps their shared leg axis-aligned.
 */
fun orthoCoordField(
    label: String,
    node: SourceNode,
    /** The document, to find the junction owning [node] when this coordinate is driven. */
    doc: Document? = null,
    /** Which coordinate this is (0 = x, 1 = y), for asking that junction to place itself. */
    axis: Int = 0,
) = HandleField(
    label,
    node,
    Dimension.LENGTH,
    { ev -> baseOf(node, ev)?.let { Quantity.mm(it) } },
    { q ->
        val master = writableMaster(node)
        // typing must reach exactly as far as dragging does, or the two stop being one operation (OP-13)
        if (master != null) master.value = ScalarValue(Quantity.mm(q.mm)) else doc?.junctionOf(node)?.place?.invoke(axis, q.mm)
    },
    writableWhen = { writableMaster(node) != null || doc?.junctionOf(node) != null },
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
        writableMaster(node)?.value = ScalarValue(Quantity.mm(a + dir * q.mm))
    },
    writableWhen = { writableMaster(node) != null },
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
 * A corner of an ortho path. The vertex is `pointXY(xNode, yNode)` and owns both nodes; a leg is
 * axis-aligned because one endpoint's coordinate is **bound** to the other's (a horizontal leg binds
 * y, a vertical one binds x). Dragging writes the *master* of each chain, so the vertex and exactly the
 * neighbours resolving to the same node move together, every leg stays axis-aligned by construction,
 * and nothing downstream cascades — no solver anywhere.
 *
 * Binding rather than sharing one node is what makes the topology editable: a binding can be
 * re-pointed in place, which is what break and join do (OP-19). Loop closure is just another binding.
 */
class OrthoCornerHandle(val xNode: SourceNode, val yNode: SourceNode, private val doc: Document? = null) : Handle {
    /** True while this vertex terminates its path (degree 1) — the case that may weld/attach. */
    var isEndpoint: Boolean = true

    /** Which coordinate this vertex *introduced* (not bound to a neighbour's): 0 = x, 1 = y — the axis
     *  its incoming leg runs along, and so the one its leg length is measured on. */
    var ownCoord: Int = 0

    /** The far end of the leg that created this vertex: the node its own coordinate is measured
     *  from, so the leg's length is a field of *this* handle. Null for a path's start vertex. */
    var legAnchor: SourceNode? = null

    /** This vertex's own coordinate node — the one its incoming leg runs along. */
    val ownNode: SourceNode get() = if (ownCoord == 0) xNode else yNode

    /** The junction owning whichever of this corner's coordinates is driven — see [Junction]. */
    private val junction: Junction? get() = doc?.let { it.junctionOf(xNode) ?: it.junctionOf(yNode) }

    override val dragNodes: List<SourceNode> get() = listOfNotNull(writableMaster(xNode), writableMaster(yNode))

    override val dragMovable: Boolean get() = dragNodes.isNotEmpty() || junction?.handle != null

    override fun drag(
        world: Vec2,
        ev: Evaluator,
    ) {
        val mx = writableMaster(xNode)
        val my = writableMaster(yNode)
        mx?.value = ScalarValue(Quantity.mm(world.x))
        my?.value = ScalarValue(Quantity.mm(world.y))
        // a coordinate this corner does not own is owned by the junction it meets at; hand the gesture
        // there rather than dropping it, so every corner at a junction drags the same way (OP-20)
        if (mx == null || my == null) junction?.handle?.drag(world, ev)
    }

    override fun fields(): List<HandleField> =
        listOf(orthoCoordField("x", xNode, doc, 0), orthoCoordField("y", yNode, doc, 1)) +
            (legAnchor?.let { listOf(lengthField("leg length", ownNode, it)) } ?: emptyList())
}

/**
 * A whole leg of an ortho path. Its two endpoints resolve to the *same* node on the perpendicular
 * coordinate (one is bound to the other, which is what keeps the leg axis-aligned), so the leg has
 * exactly one DOF of its own: dragging it writes that master node, moving both endpoints together and
 * stretching the two neighbouring legs. Nothing else moves — the same locality a vertex drag has.
 *
 * Its [fields] are that perpendicular position plus the leg's **length from either end**. A length
 * spans two vertices, so there is no single write for it: each end is a separate field, labelled by
 * which end moves, matching the two drags (of either endpoint, along the leg) that already exist.
 */
class OrthoEdgeHandle(private val doc: Document, private val path: OrthoPath, private val leg: Element) : Handle {
    /** 0 = horizontal leg (shared coordinate is y), 1 = vertical (shared x), null if detached. */
    val axis: Int? get() = path.legIndexOf(leg).takeIf { it >= 0 }?.let { path.legAxis(it) }

    /** This leg's perpendicular coordinate, as held by its first endpoint (may be bound onward). */
    private val perpendicular: SourceNode?
        get() {
            val i = path.legIndexOf(leg).takeIf { it >= 0 } ?: return null
            val a = path.legEnds(i).first.corner
            return if (path.legAxis(i) == 0) a.yNode else a.xNode
        }

    /** The node both endpoints resolve to on the perpendicular coordinate — the leg's single DOF. */
    val sharedNode: SourceNode? get() = perpendicular?.let { writableMaster(it) }

    /** The two nodes along the leg, in draw order — their difference is the leg's length. */
    private fun alongNodes(): Pair<SourceNode, SourceNode>? {
        val i = path.legIndexOf(leg).takeIf { it >= 0 } ?: return null
        val (a, b) = path.legEnds(i)
        return if (path.legAxis(i) == 0) a.corner.xNode to b.corner.xNode else a.corner.yNode to b.corner.yNode
    }

    /** The junction owning this leg's perpendicular coordinate when the leg does not — see [Junction]. */
    private val junction: Junction? get() = if (sharedNode != null) null else perpendicular?.let { doc.junctionOf(it) }

    override val dragNodes: List<SourceNode> get() = listOfNotNull(sharedNode)

    override val dragMovable: Boolean get() = sharedNode != null || junction?.handle != null

    override fun drag(
        world: Vec2,
        ev: Evaluator,
    ) {
        val want = if (axis == 0) world.y else world.x
        sharedNode?.let {
            it.value = ScalarValue(Quantity.mm(want))
            return
        }
        // this leg's own coordinate belongs to the junction it meets at; ask the junction to *place* that
        // one coordinate rather than projecting the whole cursor, so the leg lands exactly under it.
        // Dragging a leg whose far end rides a slanted line slides the junction along that line — the
        // same motion the run beyond the junction already produced, now reachable from both sides.
        junction?.place?.invoke(if (axis == 0) 1 else 0, want)
    }

    override fun fields(): List<HandleField> {
        // over the leg's *own* node, not its master: when the chain ends in derived geometry there is
        // no master, and the leg must still report its position (as unwritable) and its lengths
        val position = orthoCoordField(if (axis == 0) "y" else "x", perpendicular ?: return emptyList(), doc, 1 - (axis ?: 0))
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
