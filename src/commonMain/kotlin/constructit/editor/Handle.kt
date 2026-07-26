package constructit.editor

import constructit.core.CircleValue
import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.FrameValue
import constructit.core.LineValue
import constructit.core.Node
import constructit.core.ParameterNode
import constructit.core.PointValue
import constructit.core.ScalarValue
import constructit.core.SourceNode
import constructit.dsl.CircleRef
import constructit.dsl.LineRef
import constructit.dsl.PointRef
import constructit.dsl.ScalarRef
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
     *
     * Typed as [Node] rather than [SourceNode] because a **named parameter** (OP-7) is as much a
     * grabbable DOF as an anonymous coordinate is: an opening's position is a panel row *and* what
     * dragging its jamb writes (OP-21), and the two kinds of mutable literal must answer alike.
     */
    val dragNodes: List<Node>

    /** False when every node [drag] would write is driven, making the drag inert. */
    val dragMovable: Boolean get() = dragNodes.any { isFreeSource(it) }
}

/**
 * Whether [n] still owns its own value: a **mutable literal** with nothing bound over it.
 *
 * The one question a handle's writability asks, over both kinds of source the engine has — an anonymous
 * [SourceNode] and a named [ParameterNode] (OP-7). Anything else is derived by construction, and a field
 * over it reads but cannot write.
 */
fun isFreeSource(n: Node?): Boolean =
    when (n) {
        is SourceNode -> n.boundTo == null
        is ParameterNode -> n.boundTo == null
        else -> false
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
    /**
     * Null only for a field over a value that is **derived by construction** and therefore read-only —
     * a dimension's measured value (OP-4). Such a field has no node to write, which is exactly why it
     * reports [writable] as false.
     */
    val node: Node?,
    val dim: Dimension,
    private val getter: (Evaluator) -> Quantity?,
    private val setter: (Quantity) -> Unit,
    /**
     * Overridable because an ortho coordinate is writable *through* its binding chain: it is bound to
     * its neighbour's to hold the leg axis-aligned, and writing it means writing the master they both
     * resolve to. Only a chain ending in derived geometry is truly unwritable.
     */
    private val writableWhen: () -> Boolean = { isFreeSource(node) },
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
 * The dimension a scalar node currently yields, defaulting to a length when it cannot be evaluated —
 * the other half of [quantityOf], and the reason a panel value and a typed value are read alike.
 */
fun dimensionOf(ref: ScalarRef): Dimension =
    ((Evaluator().eval(ref.node) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.dim ?: Dimension.LENGTH

/**
 * Why grabbing [el] cannot move it, in the user's terms.
 *
 * An element with no writable field is immovable *by construction*, not by accident: an attached or
 * welded end binds a coordinate node, and because that node is **shared** with the neighbour, the
 * adjacent leg's single DOF goes with it. That is the intended consequence of the connection — but it
 * is invisible, so a silent dead drag reads as a bug. Name the driven values instead; the inspector
 * greys out exactly the same ones.
 */
fun explainImmovable(
    el: Element,
    name: String = el.id,
): String {
    val handle = el.handle
    val fields = handle?.fields().orEmpty()
    val dragged: Set<Node> = handle?.dragNodes.orEmpty().toSet()
    // Normally the reason is a field the drag would have written. When the drag can write *nothing* —
    // its whole binding chain ends in derived geometry, so there is no master to name — every
    // unwritable field is the reason instead.
    val candidates = fields.filter { it.node != null && it.node in dragged }.ifEmpty { fields }
    val driven = candidates.filter { !it.writable }.map { it.label }
    if (driven.isEmpty()) return "$name can't be moved: it is fully determined by the construction."
    val verb = if (driven.size == 1) "is" else "are"
    val editable = fields.filter { it.writable }.map { it.label }
    val alternative = if (editable.isEmpty()) "" else " You can still set ${editable.joinToString(", ")} in the panel."
    return "$name has no free direction: ${driven.joinToString(", ")} $verb driven by the construction " +
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

/** The effective frame value of [node] — a placed group's frame (OP-16). */
private fun frameOf(
    node: SourceNode,
    ev: Evaluator,
): FrameValue? = (ev.eval(node) as? EvalResult.Ok)?.value as? FrameValue

/**
 * A placed group's **frame** (OP-16 step 2): the group's own coordinate system, and the whole of what
 * moving the group writes.
 *
 * Its three fields are the group's three degrees of freedom in 2D, so a group is movable by drag *and*
 * by typed number for free (OP-13) — and since the members' points are bound to `frameApply` nodes over
 * this one source, a move is a single literal write whatever the group contains.
 */
class FrameHandle(private val node: SourceNode) : Handle {
    override val dragNodes: List<SourceNode> get() = listOf(node)

    /** Where the frame sits now — the grab anchor, so dragging a group never makes it jump. */
    fun origin(ev: Evaluator): Vec2? = frameOf(node, ev)?.origin

    override fun drag(
        world: Vec2,
        ev: Evaluator,
    ) {
        // a drag moves the origin and leaves the angle alone: rotation has its own field, and mixing the
        // two into one gesture would make a move unable to preserve orientation
        val f = frameOf(node, ev) ?: return
        node.value = FrameValue(world, f.angle)
    }

    override fun fields(): List<HandleField> =
        listOf(frameCoordField("x", node, 0), frameCoordField("y", node, 1), frameAngleField("angle", node))
}

/** A field over one coordinate of a frame's origin: [axis] 0 = x, 1 = y. */
fun frameCoordField(
    label: String,
    node: SourceNode,
    axis: Int,
) = HandleField(
    label,
    node,
    Dimension.LENGTH,
    { ev -> frameOf(node, ev)?.let { Quantity.mm(if (axis == 0) it.origin.x else it.origin.y) } },
    { q ->
        val f = frameOf(node, Evaluator()) ?: return@HandleField
        node.value = FrameValue(if (axis == 0) Vec2(q.mm, f.origin.y) else Vec2(f.origin.x, q.mm), f.angle)
    },
)

/** A field over a frame's rotation. Base unit rad, shown in degrees like every other angle. */
fun frameAngleField(
    label: String,
    node: SourceNode,
) = HandleField(
    label,
    node,
    Dimension.ANGLE,
    { ev -> frameOf(node, ev)?.let { Quantity.rad(it.angle) } },
    { q ->
        val f = frameOf(node, Evaluator()) ?: return@HandleField
        node.value = FrameValue(f.origin, q.base)
    },
)

/**
 * A free point that has been placed under a group's frame (OP-16 step 2): its world position is
 * `frameApply(frame, local)`, and its one DOF is now [local].
 *
 * Dragging **inverse-maps the cursor into the frame** and writes the local source, so the point still
 * lands under the pointer and nothing else in the group moves. The fields stay the *world* coordinates
 * they were before placing — the same numbers the panel showed, only written through the frame — so
 * placing a group changes no value the user can see (OP-13).
 */
class FramedPointHandle(private val frame: SourceNode, private val local: SourceNode) : Handle {
    override val dragNodes: List<SourceNode> get() = listOf(local)

    override fun drag(
        world: Vec2,
        ev: Evaluator,
    ) {
        local.value = PointValue(frameOf(frame, ev)?.toLocal(world) ?: world)
    }

    override fun fields(): List<HandleField> = listOf(framedCoordField("x", 0), framedCoordField("y", 1))

    private fun framedCoordField(
        label: String,
        axis: Int,
    ) = HandleField(
        label,
        local,
        Dimension.LENGTH,
        { ev -> worldOf(ev)?.let { Quantity.mm(if (axis == 0) it.x else it.y) } },
        { q ->
            val ev = Evaluator()
            val w = worldOf(ev) ?: Vec2(0.0, 0.0)
            val want = if (axis == 0) Vec2(q.mm, w.y) else Vec2(w.x, q.mm)
            local.value = PointValue(frameOf(frame, ev)?.toLocal(want) ?: want)
        },
    )

    /** This point's *world* position: the local literal seen through the frame. */
    private fun worldOf(ev: Evaluator): Vec2? {
        val f = frameOf(frame, ev) ?: return null
        return pointOf(local, ev)?.let { f.toWorld(it) }
    }
}

/** The effective value of [node] in base units — its literal, or whatever drives it. */
private fun baseOf(
    node: Node,
    ev: Evaluator,
): Double? = ((ev.eval(node) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.base

/**
 * Write [q] into whichever kind of mutable literal [node] is — the two the engine has, an anonymous
 * [SourceNode] and a named [ParameterNode] (OP-7). A driven or derived node is left alone; its field
 * reports itself unwritable ([isFreeSource]), which is the same answer dragging gives.
 */
fun writeScalar(
    node: Node,
    q: Quantity,
) {
    when (node) {
        is SourceNode -> if (node.boundTo == null) node.value = ScalarValue(q)
        is ParameterNode -> if (node.boundTo == null) node.literal = ScalarValue(q)
        else -> {}
    }
}

/**
 * A field over any scalar-valued mutable literal, of either source kind — the general form of
 * [coordField] and [angleField], needed because a tool's scalar may be a **named parameter** (a typed or
 * panel-picked value, OP-13) as easily as an anonymous coordinate.
 */
fun scalarField(
    label: String,
    node: Node,
    dim: Dimension,
) = HandleField(
    label,
    node,
    dim,
    { ev -> baseOf(node, ev)?.let { Quantity(it, dim) } },
    { q -> writeScalar(node, Quantity(q.base, dim)) },
)

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

/**
 * Point riding a host that is axis-aligned **by construction**: its one DOF is the world coordinate the
 * host does *not* determine ([axis] 0 = x on a vertical host, 1 = y on a horizontal one).
 *
 * The absolute counterpart of [OnLineHandle], and the reason to prefer it wherever it applies (OP-20): a
 * distance along the line is measured from the line's *origin*, which is one of the host's own corners, so
 * stretching the host slid everything attached to it. A world coordinate is anchored to nothing that the
 * host can move. The field is that coordinate, so the panel names it "x"/"y" instead of "along line" —
 * which is also the number the user was thinking of.
 */
class OnAxisHandle(private val node: SourceNode, private val axis: Int) : Handle {
    override val dragNodes: List<SourceNode> get() = listOf(node)

    override fun drag(
        world: Vec2,
        ev: Evaluator,
    ) {
        node.value = ScalarValue(Quantity.mm(if (axis == 0) world.x else world.y))
    }

    override fun fields(): List<HandleField> = listOf(coordField(if (axis == 0) "x" else "y", node))
}

/**
 * Point on a line: the handle's one DOF is the signed distance along the line's direction, measured from
 * the point of the line **nearest the world origin** — `world · dir`, since that point is perpendicular to
 * `dir` and so contributes nothing to the projection.
 *
 * The anchor is deliberately a property of the *line*, not of the host that carried it (OP-20). Measuring
 * from the line's own `origin` — which for a segment's carrier is one of its endpoints — made the position
 * relative to the host's extent: dragging that endpoint *along the line*, which changes nothing one can
 * see, slid everything riding it. Where the host is axis-aligned by construction there is a better
 * parameter still, a world coordinate ([OnAxisHandle]).
 */
class OnLineHandle(private val line: LineRef, private val t: SourceNode) : Handle {
    override val dragNodes: List<SourceNode> get() = listOf(t)

    override fun drag(
        world: Vec2,
        ev: Evaluator,
    ) {
        val l = (ev.valueOf(line) as? LineValue)?.line ?: return
        t.value = ScalarValue(Quantity.mm(world.dot(l.dir)))
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
    /**
     * The frame this corner's path is placed under (OP-16), or null.
     *
     * When set, the two coordinate nodes hold the group's **local** coordinates: a drag inverse-maps the
     * cursor into the frame before writing them (so the corner still lands under the pointer, and every leg
     * stays axis-aligned in the *group*), while the panel keeps showing world x/y — the same numbers as
     * before placing, only written through the frame (OP-13).
     */
    private val frameNode: SourceNode? get() = doc?.pathFrameOf(this)

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
        // in the path's own space: the cursor (grab offset and axis lock already applied to it in world
        // coordinates, which a rigid map carries over unchanged) inverse-mapped through the frame
        val at = local(world, ev)
        mx?.value = ScalarValue(Quantity.mm(at.x))
        my?.value = ScalarValue(Quantity.mm(at.y))
        // A coordinate this corner does not own belongs to the junction it meets at, so hand the gesture
        // there rather than dropping it (OP-20) — but **only that coordinate**. Handing over the whole
        // cursor made the junction jump to the pointer, so dragging an outer corner along its own arm
        // dragged the shared centre sideways with it and collapsed the figure.
        val j = junction ?: return
        when {
            mx == null && my == null -> j.handle?.drag(world, ev) // owns nothing: follow as closely as it can
            mx == null -> j.place(0, world.x)
            my == null -> j.place(1, world.y)
        }
    }

    override fun fields(): List<HandleField> {
        val frame = frameNode
        val coords =
            if (frame == null) {
                listOf(orthoCoordField("x", xNode, doc, 0), orthoCoordField("y", yNode, doc, 1))
            } else {
                listOf(framedCoordField("x", frame, 0), framedCoordField("y", frame, 1))
            }
        // a leg length is a distance between two of this path's own coordinates, so a frame leaves it alone —
        // a placement changes the coordinates' origin, and rotation preserves distance
        return coords + (legAnchor?.let { listOf(lengthField("leg length", ownNode, it)) } ?: emptyList())
    }

    /** [world] in the space this corner's coordinates live in: the frame's, when it has one. */
    private fun local(
        world: Vec2,
        ev: Evaluator,
    ): Vec2 = frameNode?.let { frameOf(it, ev)?.toLocal(world) } ?: world

    /**
     * A **world** coordinate of a corner whose path is placed (OP-16): read through the frame, written by
     * inverse-mapping the world position it asks for back into it.
     *
     * Under a turned frame a world x depends on *both* local coordinates, so the write lands on both
     * masters — exactly the pair the drag writes, which is what keeps typing and dragging one operation
     * (OP-13). With the frame unturned only the axis' own master changes, as before.
     */
    private fun framedCoordField(
        label: String,
        frame: SourceNode,
        axis: Int,
    ) = HandleField(
        label,
        if (axis == 0) xNode else yNode,
        Dimension.LENGTH,
        { ev -> worldOf(frame, ev)?.let { Quantity.mm(if (axis == 0) it.x else it.y) } },
        { q ->
            val ev = Evaluator()
            val w = worldOf(frame, ev) ?: Vec2(0.0, 0.0)
            val want = if (axis == 0) Vec2(q.mm, w.y) else Vec2(w.x, q.mm)
            val at = frameOf(frame, ev)?.toLocal(want) ?: want
            writableMaster(xNode)?.value = ScalarValue(Quantity.mm(at.x))
            writableMaster(yNode)?.value = ScalarValue(Quantity.mm(at.y))
        },
        // both coordinates have to be writable, since the inverse map needs both to place one of them
        writableWhen = { writableMaster(xNode) != null && writableMaster(yNode) != null },
    )

    /** This corner's world position: its two local coordinates seen through [frame]. */
    private fun worldOf(
        frame: SourceNode,
        ev: Evaluator,
    ): Vec2? {
        val f = frameOf(frame, ev) ?: return null
        val lx = baseOf(xNode, ev) ?: return null
        val ly = baseOf(yNode, ev) ?: return null
        return f.toWorld(Vec2(lx, ly))
    }
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
        // in the path's own space: under a frame (OP-16) the leg's one degree of freedom is a *local*
        // perpendicular offset, so the cursor is inverse-mapped before the axis component is taken — which
        // is what keeps the leg moving across itself as it appears on screen, whatever the frame's angle
        val at = path.frame?.let { frameOf(it, ev)?.toLocal(world) } ?: world
        val want = if (axis == 0) at.y else at.x
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
        //
        // A *placed* path's leg offset is a coordinate in the group's axes and has no world counterpart at
        // all — a leg of a turned group is neither horizontal nor vertical in the world — so the label says
        // which space the number is in rather than quietly meaning something else (unlike a corner, which
        // has a world position and reports it).
        val name = (if (axis == 0) "y" else "x") + (if (path.frame != null) " in group" else "")
        val position = orthoCoordField(name, perpendicular ?: return emptyList(), doc, 1 - (axis ?: 0))
        val along = alongNodes() ?: return listOf(position)
        val (start, end) = along
        return listOf(
            position,
            lengthField("length (move end)", end, start),
            lengthField("length (move start)", start, end),
        )
    }
}

/**
 * A point re-parameterized as an offset from an [anchor] point (OP-4 case b): its two degrees of freedom are
 * the [distance] and the [angle] of that offset, which is what makes a *radius* something one can type.
 *
 * Dragging inverts the offset from the cursor, so the point still lands under the pointer and the anchor
 * stays put — the same discipline as a framed point's drag (OP-13). A cursor *on* the anchor leaves the angle
 * alone rather than snapping it to whatever `atan2(0, 0)` returns, so passing through the anchor and out the
 * far side does not spin the point.
 */
class RelativePointHandle(private val anchor: PointRef, private val distance: SourceNode, private val angle: SourceNode) : Handle {
    override val dragNodes: List<SourceNode> get() = listOf(distance, angle)

    override fun drag(
        world: Vec2,
        ev: Evaluator,
    ) {
        val a = (ev.valueOf(anchor) as? PointValue)?.p ?: return
        val v = world - a
        distance.value = ScalarValue(Quantity.mm(v.length()))
        if (v.length() >= Vec2.EPS) angle.value = ScalarValue(Quantity.rad(v.angle()))
    }

    override fun fields(): List<HandleField> = listOf(coordField("distance", distance), angleField("angle", angle))
}

/**
 * A field over a **named parameter** (OP-7) that is also a handle's DOF — an opening's position, width,
 * sill or head (OP-21).
 *
 * The same row the parameters panel already shows, reached from the selection instead: a value must not be
 * typeable in one place and not in the other (OP-13). [set] is passed in rather than writing the literal
 * here, because the write may be *clamped* by the geometry the parameter lives in, and a typed value has
 * to be clamped exactly as the drag is — they are one operation.
 */
fun parameterField(
    label: String,
    ref: ScalarRef,
    set: (Quantity) -> Unit,
) = HandleField(
    label,
    ref.node,
    Dimension.LENGTH,
    { ev -> ((ev.eval(ref.node) as? EvalResult.Ok)?.value as? ScalarValue)?.q },
    set,
)

/**
 * A **jamb** of an opening (OP-21): the reveal line the plan convention draws at one edge of a
 * [PathInterval], and the grabbable form of that interval's two degrees of freedom.
 *
 * - the **leading** jamb ([atEnd] false) slides the whole opening: it writes `pos`, and because the width
 *   is measured *from* the position, the opening keeps its size and travels along the leg.
 * - the **trailing** jamb ([atEnd] true) writes `width` from where the cursor falls, so the leading edge
 *   stays put. That is OP-13's "which end moves?" answered by construction: two jambs, two drags, two
 *   different nodes — no anchor picker, and no gesture called *drag the width*.
 *
 * Both are 1-DOF **along the leg**: the cursor is projected onto the carrier leg, which is also why this
 * needs no inverse frame map. A distance along a leg is exactly what a rigid placement (OP-16) leaves
 * unchanged, so a jamb of a wall inside a placed group drags with no case of its own.
 *
 * The clamps live in the document ([Document.setIntervalPosition] / [Document.setIntervalWidth]) so the
 * drag and the typed fields below are clamped by the same rule, and both say so through the same note.
 */
class JambHandle(
    private val doc: Document,
    private val path: ThickPath,
    private val interval: PathInterval,
    val atEnd: Boolean,
) : Handle {
    override val dragNodes: List<Node> get() = listOf((if (atEnd) interval.width else interval.position).node)

    override fun drag(
        world: Vec2,
        ev: Evaluator,
    ) {
        val along = doc.positionAlongLeg(path, interval.legIndex, world, ev) ?: return
        if (atEnd) doc.setIntervalEnd(path, interval, along) else doc.setIntervalPosition(path, interval, along)
    }

    /**
     * The interval's four values, in one **stable** order whichever jamb was grabbed: the inspector is a
     * view of the opening, and rows that reshuffled as the user moved from one jamb to the other would be
     * a worse answer than saying which node the *drag* writes ([dragNodes]) — which is where that
     * distinction belongs.
     *
     * Sill and head are ordinary writable rows here too. They are free parameters (OP-7), and a row that
     * read a value but refused to write it would be a claim about the model that is not true.
     */
    override fun fields(): List<HandleField> =
        listOf(
            parameterField("position", interval.position) { doc.setIntervalPosition(path, interval, it.mm) },
            parameterField("width", interval.width) { doc.setIntervalWidth(path, interval, it.mm) },
            parameterField("sill", interval.sill) { doc.setIntervalHeight(interval.sill, it.mm) },
            parameterField("head", interval.head) { doc.setIntervalHeight(interval.head, it.mm) },
        )
}

/**
 * A **ratio point**: the point dividing `a → b` in the ratio [t] (`cx.pointAtRatio`). Its single DOF is
 * that dimensionless share, so it slides along the span — and typing the *factor* is the same write
 * (OP-13), which is what makes "a third of the way along" a number rather than a gesture.
 *
 * The drag projects the cursor onto the span, exactly as a rider on a line does; past either end the
 * factor simply leaves `[0, 1]` and the point extrapolates, which is what the construction says.
 */
class RatioPointHandle(private val a: PointRef, private val b: PointRef, private val t: Node) : Handle {
    override val dragNodes: List<Node> get() = listOf(t)

    override fun drag(
        world: Vec2,
        ev: Evaluator,
    ) {
        val pa = (ev.valueOf(a) as? PointValue)?.p ?: return
        val pb = (ev.valueOf(b) as? PointValue)?.p ?: return
        val ab = pb - pa
        val len2 = ab.dot(ab)
        if (len2 < Vec2.EPS * Vec2.EPS) return
        writeScalar(t, Quantity.number((world - pa).dot(ab) / len2))
    }

    override fun fields(): List<HandleField> = listOf(scalarField("factor", t, Dimension.NONE))
}

/**
 * A rider whose position along its carrier is stated **relative to another point of that carrier**: its
 * own parameter is `base + d`, and [d] — a signed distance along the carrier — is the whole of its
 * freedom (OP-4 case b, on a shared carrier).
 *
 * [base] is the derived parameter the offset is measured from (the base point's own position along the
 * carrier); [paramOf] turns a cursor into a value of the rider's parameter — `world · dir` for a
 * distance along a line, a world coordinate for a rider on an axis-aligned host — so the drag lands the
 * rider under the pointer by writing `d = wanted − base`, which is exact.
 */
class CarrierOffsetHandle(
    private val base: Node,
    private val d: SourceNode,
    private val paramOf: (Vec2, Evaluator) -> Double?,
) : Handle {
    override val dragNodes: List<Node> get() = listOf(d)

    override fun drag(
        world: Vec2,
        ev: Evaluator,
    ) {
        val want = paramOf(world, ev) ?: return
        val from = baseOf(base, ev) ?: return
        d.value = ScalarValue(Quantity.mm(want - from))
    }

    override fun fields(): List<HandleField> = listOf(coordField("distance", d))
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
