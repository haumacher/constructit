package constructit.editor

import constructit.core.CircleValue
import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.LineValue
import constructit.core.Node
import constructit.core.ParameterNode
import constructit.core.PointValue
import constructit.core.ScalarValue
import constructit.core.SegmentValue
import constructit.core.SourceNode
import constructit.core.Value
import constructit.dsl.ArcRef
import constructit.dsl.CircleRef
import constructit.dsl.Construction
import constructit.dsl.LineRef
import constructit.dsl.PointRef
import constructit.dsl.PointSetRef
import constructit.dsl.RayRef
import constructit.dsl.Ref
import constructit.dsl.ScalarRef
import constructit.dsl.SegmentRef
import constructit.geom.Vec2
import constructit.units.Quantity
import constructit.units.mm
import kotlin.math.abs

enum class ElementKind { POINT, DERIVED_POINT, ON_CURVE, LINE, RAY, CIRCLE, SEGMENT, ARC }

/** A retained, displayable/selectable graph output with style + kind. */
class Element(
    val id: String,
    val ref: Ref<*>,
    /** Mutable: a free point can become an on-curve point in place when attached to a curve. */
    var kind: ElementKind,
    var style: Style,
    var visible: Boolean = true,
    /** For [ElementKind.ON_CURVE] (and draggable legs): the grabbable DOF — see [Handle]. */
    var handle: Handle? = null,
) {
    /**
     * Whether grabbing this element can actually move anything. An on-curve point qualifies only
     * while its handle still has a writable field: once every coordinate is driven — welded onto a
     * point, or shared by a loop closure — dragging it is inert, and a dead handle must not steal the
     * grab from the geometry that *can* move (which sits at the same place, being what drives it).
     */
    val draggable: Boolean get() =
        when (kind) {
            ElementKind.POINT, ElementKind.ON_CURVE -> hasFreeDof
            else -> false
        }

    /**
     * True while dragging this element can still change something. Note a leg can be immovable and
     * yet have editable *lengths*: its drag writes the one coordinate shared by its ends, which the
     * length fields do not touch — see [Handle.dragNodes] and [explainImmovable].
     */
    val hasFreeDof: Boolean get() = handle?.dragMovable ?: false

    /** Anything a pointer can address: a point, or a curve carrying a handle (an ortho leg). */
    val selectable: Boolean get() = isPoint || handle != null
    val isCurve: Boolean get() = kind == ElementKind.LINE || kind == ElementKind.CIRCLE || kind == ElementKind.SEGMENT || kind == ElementKind.RAY || kind == ElementKind.ARC
    val isPoint: Boolean get() = kind == ElementKind.POINT || kind == ElementKind.DERIVED_POINT || kind == ElementKind.ON_CURVE

    /** Line / segment / ray — anything that determines an infinite line. */
    val isLinear: Boolean get() = kind == ElementKind.LINE || kind == ElementKind.SEGMENT || kind == ElementKind.RAY
}

/** A named scalar: an editable parameter (OP-7) or a read-only measurement (OP-4). */
class ScalarEntry(val id: String, var name: String, val ref: ScalarRef, val editable: Boolean)

/**
 * A vertex of an ortho path, carrying the two coordinate source nodes so drags/closure can write
 * them. [ownAxis] is the coordinate introduced by the edge that created it (0 = x, 1 = y, -1 = the
 * start, which owns both) — the safe one to bind when closing a loop. [corner] is a `var` because
 * closing a loop replaces the live handle (see [Document.closeOrthoPath]).
 */
class OrthoVertex(val ref: PointRef, var corner: OrthoCornerHandle, val ownAxis: Int)

/**
 * A retained rectilinear path: [vertices] in draw order plus the [legs] between them (the closing
 * leg last when [closed]). Retaining the topology is what makes a *leg* addressable: a leg's length
 * is the difference of two consecutive nodes in one coordinate chain, so a handle can only offer
 * that length as a numeric field if it can find the neighbour that supplies the other end.
 */
class OrthoPath {
    val vertices = ArrayList<OrthoVertex>()
    val legs = ArrayList<Element>()

    /**
     * Axis per leg, kept beside [legs]. Held explicitly rather than derived from a vertex's introduced
     * coordinate: that derivation assumed every leg was drawn forward, which a break does not honour
     * when the leg's endpoints follow each other the other way round (a loop's closing leg).
     */
    val legAxes = ArrayList<Int>()
    var closed: Boolean = false

    /** One leg per vertex when closed, one fewer when open. */
    val legCount: Int get() = if (closed) vertices.size else (vertices.size - 1).coerceAtLeast(0)

    /** Axis of leg [i] (from `vertices[i]` toward the next): 0 = horizontal, 1 = vertical. */
    fun legAxis(i: Int): Int = legAxes[i]

    /** The vertex at each end of leg [i], in draw order. */
    fun legEnds(i: Int): Pair<OrthoVertex, OrthoVertex> = vertices[i] to vertices[(i + 1) % vertices.size]

    /** Index of the leg drawn as [el], or -1. */
    fun legIndexOf(el: Element): Int = legs.indexOfFirst { it === el }

    /** The legs either side of leg [i], wrapping around a closed loop. */
    fun neighbourLegs(i: Int): List<Int> =
        if (closed) {
            listOf((i - 1 + legCount) % legCount, (i + 1) % legCount).filter { it != i }
        } else {
            listOf(i - 1, i + 1).filter { it in 0 until legCount }
        }
}

/** A gap in a wall leg: [position] = distance from the leg start, [width] along the leg. */
class Opening(val legIndex: Int, val position: ScalarRef, val width: ScalarRef)

/**
 * A retained wall: a centerline through [vertices] with a [thickness], plus [openings]. Its face /
 * cap / jamb geometry is derived and regenerated (into [ownedIds]) whenever openings change — the
 * centerline and its length parameters live outside the wall, so editing them reshapes it too.
 */
class Wall(
    val vertices: List<PointRef>,
    val thickness: ScalarRef,
    val closed: Boolean = false,
    /** The centerline path this wall was built from, when it came from the ortho-path tool. */
    val path: OrthoPath? = null,
) {
    val openings = ArrayList<Opening>()
    val ownedIds = HashSet<String>()
}

/**
 * A retained construction document: owns the [Construction] DAG plus display metadata, and
 * exposes enumeration (rendering/hit-testing/panels) and mutation (tools). Every op is wrapped
 * as an element- or scalar-adder so the whole 2D algebra is reachable from the UI.
 */
class Document {
    val cx = Construction()
    val elements = ArrayList<Element>()
    val scalars = ArrayList<ScalarEntry>()
    private var counter = 0

    /**
     * The construction steps that built this document, in order — see [DocumentFormat]. Steps are the
     * save format: replaying them rebuilds the graph *and* everything synthetic around it.
     */
    val journal = ArrayList<Step>()
    private var recordDepth = 0

    /**
     * Run [body] as one journal step. Nested calls are absorbed into the outermost one, so a tool that
     * calls several document operations is recorded as the single tool application the user performed —
     * which is also the only granularity that replays correctly.
     */
    private fun <T> recording(
        kind: String,
        vararg args: Arg,
        skipIfEmpty: Boolean = false,
        body: () -> T,
    ): T {
        if (recordDepth > 0) return body()
        recordDepth++
        // an identity snapshot, not a count: a step may *remove* elements too (a break replaces one
        // leg with three), and then a count would mistake shifted survivors for new ones
        val before = elements.toHashSet()
        val scalarsBefore = scalars.size
        try {
            val result = body()
            val created = elements.filter { it !in before }
            // a tool whose build had no effect is not part of the construction
            if (skipIfEmpty && created.isEmpty() && scalars.size == scalarsBefore) return result
            val step = Step(kind, args.toList())
            step.creates.addAll(created)
            journal.add(step)
            return result
        } finally {
            recordDepth--
        }
    }

    /**
     * Record [body] as a tool application, so replay re-runs the same [ToolDef] — which is what keeps
     * the format tool-agnostic: adding a tool needs no work here.
     */
    fun <T> recordingTool(
        toolId: String,
        picks: Picks,
        scalar: ScalarEntry?,
        body: () -> T,
    ): T =
        recording(
            "tool",
            *listOfNotNull(
                Arg.Text(toolId),
                Arg.Keyed("pts", Arg.Els(picks.points.mapNotNull { elementFor(it) })).takeIf { picks.points.isNotEmpty() },
                Arg.Keyed("els", Arg.Els(picks.elements)).takeIf { picks.elements.isNotEmpty() },
                Arg.Keyed("clicks", Arg.Positions(picks.clicks)).takeIf { picks.clicks.isNotEmpty() },
                scalar?.let { Arg.Keyed("scalar", Arg.Sc(it)) },
            ).toTypedArray(),
            skipIfEmpty = true,
            body = body,
        )

    private fun nextId(prefix: String) = "$prefix${++counter}"

    val freePoints: List<Element> get() = elements.filter { it.kind == ElementKind.POINT }

    private fun add(
        ref: Ref<*>,
        kind: ElementKind,
        style: Style,
    ): Element {
        val el = Element(nextId("e"), ref, kind, style)
        elements.add(el)
        return el
    }

    /** The element displaying [ref], if any — the inverse of the adders below. */
    fun elementFor(ref: Ref<*>): Element? = elements.lastOrNull { it.ref === ref }

    private fun addDerived(ref: PointRef): PointRef {
        add(ref, ElementKind.DERIVED_POINT, Styles.DERIVED_POINT)
        return ref
    }

    /** Coerce a line/segment/ray element to its infinite carrier line. */
    @Suppress("UNCHECKED_CAST")
    private fun carrierLine(el: Element): LineRef =
        when (el.kind) {
            ElementKind.SEGMENT -> cx.lineOfSegment(el.ref as SegmentRef)
            ElementKind.RAY -> cx.lineOfRay(el.ref as RayRef)
            else -> el.ref as LineRef
        }

    // ---- free points & scalars ----

    fun freePoint(
        x: Quantity,
        y: Quantity,
    ): PointRef =
        recording("point", Arg.Pos(Vec2(x.mm, y.mm))) {
            cx.freePoint("P${counter + 1}", x, y).also { ref ->
                val el = add(ref, ElementKind.POINT, Styles.FREE_POINT)
                el.handle = FreePointHandle(ref.node as SourceNode) // its position is a handle field too
            }
        }

    /** Ensure scalar names are unique so the wiring dropdown is never ambiguous. */
    private fun uniqueScalarName(base: String): String {
        val b = base.ifBlank { "p" }
        if (scalars.none { it.name == b }) return b
        var i = 2
        while (scalars.any { it.name == "$b$i" }) i++
        return "$b$i"
    }

    fun newParameter(
        name: String,
        value: Quantity,
    ): ScalarEntry {
        val node = ParameterNode(nextId("pn"), ScalarValue(value))
        val e = ScalarEntry(nextId("s"), uniqueScalarName(name), Ref<ScalarValue>(node), editable = true)
        scalars.add(e)
        recording("param", Arg.Sc(e), Arg.Text("="), Arg.Num(value)) {}
        return e
    }

    private fun measurement(
        name: String,
        ref: ScalarRef,
    ): ScalarEntry {
        val e = ScalarEntry(nextId("m"), uniqueScalarName(name), ref, editable = false)
        scalars.add(e)
        return e
    }

    fun setParameter(
        e: ScalarEntry,
        value: Quantity,
    ) {
        require(e.editable) { "not an editable parameter" }
        (e.ref.node as ParameterNode).literal = ScalarValue(value)
    }

    // ---- wiring: reduce a parameter's DOF by binding it to another scalar (equality by reference) ----

    fun isBound(e: ScalarEntry): Boolean = (e.ref.node as? ParameterNode)?.boundTo != null

    fun boundEntry(e: ScalarEntry): ScalarEntry? {
        val bt = (e.ref.node as? ParameterNode)?.boundTo ?: return null
        return scalars.firstOrNull { it.ref.node === bt }
    }

    private fun dimOf(node: Node): constructit.units.Dimension? =
        (Evaluator().eval(node) as? EvalResult.Ok)?.let { (it.value as? ScalarValue)?.q?.dim }

    private fun dependsOn(
        from: Node,
        target: Node,
        seen: MutableSet<String>,
    ): Boolean {
        if (from === target) return true
        if (!seen.add(from.id)) return false
        for (i in from.inputs) if (dependsOn(i, target, seen)) return true
        return false
    }

    /** Wire parameter [e] to track [target]. Rejected on type mismatch or if it would cycle. */
    fun wireParameter(
        e: ScalarEntry,
        target: ScalarEntry,
    ): Boolean = recording("wire", Arg.Sc(e), Arg.Text("="), Arg.Sc(target)) { wireParameterNow(e, target) }

    private fun wireParameterNow(
        e: ScalarEntry,
        target: ScalarEntry,
    ): Boolean {
        val node = e.ref.node as? ParameterNode ?: return false
        if (target.ref.node === node) return false
        val myDim = dimOf(node)
        val tgtDim = dimOf(target.ref.node)
        if (myDim != null && tgtDim != null && myDim != tgtDim) return false // same type only
        if (dependsOn(target.ref.node, node, HashSet())) return false // no cycles
        node.boundTo = target.ref.node
        return true
    }

    /** Free the parameter again, keeping its current (last driven) value. */
    fun unwireParameter(e: ScalarEntry) {
        val node = e.ref.node as? ParameterNode ?: return
        val cur = (Evaluator().eval(node) as? EvalResult.Ok)?.let { (it.value as ScalarValue).q }
        if (cur != null) node.literal = ScalarValue(cur)
        node.boundTo = null
    }

    fun moveFreePoint(
        el: Element,
        world: Vec2,
    ) {
        require(el.kind == ElementKind.POINT) { "not a free point" }
        el.handle?.drag(world, Evaluator())
    }

    // ---- welding: join two points by aliasing one onto the other (point-level wiring) ----

    /** True if [el] is a free point currently welded onto a master. */
    fun isWelded(el: Element): Boolean =
        el.kind == ElementKind.POINT && (el.ref.node as? SourceNode)?.boundTo != null

    /**
     * Weld free point [alias] onto [master] so they coincide: [alias] becomes a driven alias of
     * [master] ([SourceNode.boundTo]). Everything already referencing [alias] transparently follows
     * [master]; [alias] loses its DOF and is hidden so the pair reads as a single point. Reversible
     * via [unweld]. Rejected unless [alias] is an un-welded free point, differs from [master], and
     * welding would not create a cycle.
     */
    fun weld(
        alias: Element,
        master: Element,
    ): Boolean = recording("weld", Arg.El(alias), Arg.El(master)) { weldNow(alias, master) }

    private fun weldNow(
        alias: Element,
        master: Element,
    ): Boolean {
        val node = alias.ref.node as? SourceNode ?: return false
        if (alias.kind != ElementKind.POINT || node.boundTo != null) return false
        if (!master.isPoint || master === alias) return false
        val masterNode = master.ref.node
        if (masterNode === node || dependsOn(masterNode, node, HashSet())) return false // no cycles
        node.boundTo = masterNode
        alias.visible = false
        return true
    }

    /** Un-weld / detach: the point resumes as an independent free point at its current position. */
    fun unweld(alias: Element) = recording("unweld", Arg.El(alias)) { unweldNow(alias) }

    private fun unweldNow(alias: Element) {
        val node = alias.ref.node as? SourceNode ?: return
        val cur = (Evaluator().eval(node) as? EvalResult.Ok)?.let { (it.value as? PointValue)?.p }
        node.boundTo = null
        if (cur != null) node.value = PointValue(cur)
        alias.kind = ElementKind.POINT
        alias.handle = FreePointHandle(node) // an independent free point again, handle included
        alias.style = Styles.FREE_POINT
        alias.visible = true
    }

    // ---- drag-to-attach: weld a free point onto a curve so it slides along it (1 DOF) ----

    /**
     * Where free point [pt] would land if attached to [curve] (its projection onto the line, or
     * the nearest point on the circle), or null if the attach is invalid — [pt] is not an
     * un-welded free point, [curve] is not a line/segment/ray/circle, or [curve] is built from
     * [pt] (which would cycle). Used for the drag magnet's eligibility + halo position.
     */
    fun attachTargetPos(
        pt: Element,
        curve: Element,
    ): Vec2? {
        val node = pt.ref.node as? SourceNode ?: return null
        if (pt.kind != ElementKind.POINT || node.boundTo != null) return null
        return curveProjection(pt, curve)
    }

    /**
     * Where point-element [pt] projects onto [curve] (foot on a line, nearest point on a circle), or
     * null if [curve] is built from [pt] (would cycle) or isn't a line/circle. Works for any point,
     * so both free points and ortho endpoints can use it for the drag magnet.
     */
    fun curveProjection(
        pt: Element,
        curve: Element,
    ): Vec2? {
        val p = (Evaluator().eval(pt.ref.node) as? EvalResult.Ok)?.let { (it.value as? PointValue)?.p } ?: return null
        return when {
            curve.isLinear -> {
                val lr = carrierLine(curve)
                if (dependsOn(lr.node, pt.ref.node, HashSet())) return null
                val l = (Evaluator().eval(lr.node) as? EvalResult.Ok)?.value as? LineValue ?: return null
                l.line.origin + l.line.dir * (p - l.line.origin).dot(l.line.dir)
            }
            curve.kind == ElementKind.CIRCLE -> {
                val cr = curve.ref as CircleRef
                if (dependsOn(cr.node, pt.ref.node, HashSet())) return null
                val c = (Evaluator().eval(cr.node) as? EvalResult.Ok)?.value as? CircleValue ?: return null
                val d = p - c.circle.center
                val len = d.length()
                if (len < Vec2.EPS) {
                    c.circle.center + Vec2(c.circle.radius, 0.0)
                } else {
                    c.circle.center + d * (c.circle.radius / len)
                }
            }
            else -> null
        }
    }

    /**
     * Attach free point [pt] onto [curve]: it becomes a 1-DOF on-curve point (draggable along the
     * curve). The point's node is welded ([SourceNode.boundTo]) onto a fresh point-on-curve node
     * driven by a hidden parameter, so everything already referencing the point now slides with it.
     * Reversible via [unweld]. Same validity rules as [attachTargetPos].
     */
    fun attachToCurve(
        pt: Element,
        curve: Element,
    ): Boolean = recording("attach", Arg.El(pt), Arg.El(curve)) { attachToCurveNow(pt, curve) }

    private fun attachToCurveNow(
        pt: Element,
        curve: Element,
    ): Boolean {
        val node = pt.ref.node as? SourceNode ?: return false
        if (attachTargetPos(pt, curve) == null) return false
        val ev = Evaluator()
        val p = (ev.eval(node) as EvalResult.Ok).let { (it.value as PointValue).p }
        when {
            curve.isLinear -> {
                val lr = carrierLine(curve)
                val l = (ev.eval(lr.node) as EvalResult.Ok).value as LineValue
                val t0 = (p - l.line.origin).dot(l.line.dir)
                val tNode = SourceNode(nextId("t"), ScalarValue(Quantity.mm(t0)))
                node.boundTo = cx.pointOnLineAt(lr, Ref<ScalarValue>(tNode)).node
                pt.handle = OnLineHandle(lr, tNode)
            }
            else -> { // circle
                val cr = curve.ref as CircleRef
                val c = (ev.eval(cr.node) as EvalResult.Ok).value as CircleValue
                val aNode = SourceNode(nextId("a"), ScalarValue(Quantity.rad((p - c.circle.center).angle())))
                node.boundTo = cx.pointOnCircle(cr, Ref<ScalarValue>(aNode)).node
                pt.handle = OnCircleHandle(cr, aNode)
            }
        }
        pt.kind = ElementKind.ON_CURVE
        pt.style = Styles.ON_CURVE
        return true
    }

    /** The ortho-corner handle of [el] if it is a draggable *end* of an open path, else null. */
    fun orthoEndpoint(el: Element): OrthoCornerHandle? =
        (el.handle as? OrthoCornerHandle)?.takeIf { it.isEndpoint }

    /**
     * Attach an ortho path endpoint [el] onto [curve]: both its coordinate nodes are bound to a fresh
     * point-on-curve, so the endpoint — and the neighbour sharing one coordinate — follow the curve,
     * and dragging it now slides along the curve. The ortho analogue of [attachToCurve].
     *
     * For a **line**, exactly one coordinate is bound: the one the line *determines*. A line that
     * crosses every horizontal fixes x once y is known, so x is derived from the (free) y and y stays
     * the 1 DOF that slides along the line; a horizontal line is the mirror image.
     *
     * Keying this on the **line's** orientation rather than on the vertex's own/shared split is what
     * makes the two ends of a path attach *symmetrically*. A path's start attaches before it has any
     * leg, so its own coordinate is not yet defined — deciding from the leg therefore had to pin both
     * of the start's coordinates, which silently robbed its first leg of the perpendicular DOF that
     * the same connection at the other end left intact. The line's orientation is always defined.
     *
     * A consequence that *is* geometry: if the leg at the attached end runs parallel to the line, the
     * bound coordinate is the one shared with the neighbour, so the neighbour moves onto the line too.
     * An axis-aligned leg starting on a parallel line has to be collinear with it.
     */
    fun attachOrthoEndpointToCurve(
        el: Element,
        curve: Element,
    ): Boolean = recording("attachortho", Arg.El(el), Arg.El(curve)) { attachOrthoEndpointToCurveNow(el, curve) }

    private fun attachOrthoEndpointToCurveNow(
        el: Element,
        curve: Element,
    ): Boolean {
        val corner = orthoEndpoint(el) ?: return false
        val ev = Evaluator()
        if (curve.isLinear) {
            val lr = carrierLine(curve)
            if (dependsOn(lr.node, el.ref.node, HashSet())) return false
            val dir = ((ev.eval(lr.node) as? EvalResult.Ok)?.value as? LineValue)?.line?.dir ?: return false
            // a line crossing every horizontal determines x from y; a horizontal line determines y from x
            val bindsX = abs(dir.y) > Vec2.EPS
            // bind the *master* of the chain: binding the local node would discard the binding that
            // holds this leg axis-aligned, and the whole chain must follow the curve anyway
            val bound = writableMaster(if (bindsX) corner.xNode else corner.yNode) ?: return false
            val free = if (bindsX) corner.yNode else corner.xNode
            val alongFree = Ref<ScalarValue>(free)
            val cut =
                if (bindsX) {
                    cx.lineThrough(cx.pointXY(cx.const(0.0.mm), alongFree), cx.pointXY(cx.const(1.0.mm), alongFree))
                } else {
                    cx.lineThrough(cx.pointXY(alongFree, cx.const(0.0.mm)), cx.pointXY(alongFree, cx.const(1.0.mm)))
                }
            val crossing = cx.select(cx.intersectLL(lr, cut), +1)
            bound.boundTo = (if (bindsX) cx.measureX(crossing) else cx.measureY(crossing)).node
            corner.isEndpoint = false
            return true
        }
        if (curve.kind == ElementKind.CIRCLE) {
            val cr = curve.ref as CircleRef
            if (dependsOn(cr.node, el.ref.node, HashSet())) return false
            val p = (ev.eval(el.ref.node) as? EvalResult.Ok)?.let { (it.value as? PointValue)?.p } ?: return false
            val c = (ev.eval(cr.node) as EvalResult.Ok).value as CircleValue
            val aNode = SourceNode(nextId("a"), ScalarValue(Quantity.rad((p - c.circle.center).angle())))
            val pol = cx.pointOnCircle(cr, Ref<ScalarValue>(aNode))
            el.handle = OnCircleHandle(cr, aNode)
            corner.xNode.boundTo = cx.measureX(pol).node
            corner.yNode.boundTo = cx.measureY(pol).node
            return true
        }
        return false
    }

    /** Weld an ortho path endpoint [el] onto point [target]: its coordinates track the target. */
    fun weldOrthoEndpointToPoint(
        el: Element,
        target: Element,
    ): Boolean = recording("weldortho", Arg.El(el), Arg.El(target)) { weldOrthoEndpointToPointNow(el, target) }

    private fun weldOrthoEndpointToPointNow(
        el: Element,
        target: Element,
    ): Boolean {
        val corner = orthoEndpoint(el) ?: return false
        val tref = target.ref as? PointRef ?: return false
        if (!target.isPoint || target === el || dependsOn(tref.node, el.ref.node, HashSet())) return false
        val mx = writableMaster(corner.xNode) ?: return false
        val my = writableMaster(corner.yNode) ?: return false
        mx.boundTo = cx.measureX(tref).node
        my.boundTo = cx.measureY(tref).node
        corner.isEndpoint = false
        return true
    }

    fun remove(el: Element) {
        elements.remove(el)
    }

    // ---- points ----

    fun midpoint(
        a: PointRef,
        b: PointRef,
    ) = addDerived(cx.midpoint(a, b))

    /** The centre of a circle or arc as a derived point (works on 3-point circles etc.). */
    fun centerOf(el: Element): PointRef? =
        when (el.kind) {
            ElementKind.CIRCLE -> addDerived(cx.circleCenter(el.ref as CircleRef))
            ElementKind.ARC -> addDerived(cx.arcCenter(el.ref as ArcRef))
            else -> null
        }

    fun projectToLine(
        p: PointRef,
        line: Element,
    ) = addDerived(cx.projectToLine(p, carrierLine(line)))

    private fun addConstrained(
        ref: PointRef,
        handle: Handle,
    ): PointRef {
        elements.add(Element(nextId("e"), ref, ElementKind.ON_CURVE, Styles.ON_CURVE, handle = handle))
        return ref
    }

    /** Point that slides along a line; created at the projection of [at], draggable along the line. */
    fun pointOnLine(
        line: Element,
        at: Vec2,
    ): PointRef {
        val lineRef = carrierLine(line)
        val l = (Evaluator().eval(lineRef.node) as? EvalResult.Ok)?.value as? LineValue
        val t0 = if (l != null) (at - l.line.origin).dot(l.line.dir) else 0.0
        val tNode = SourceNode(nextId("t"), ScalarValue(Quantity.mm(t0)))
        return addConstrained(cx.pointOnLineAt(lineRef, Ref<ScalarValue>(tNode)), OnLineHandle(lineRef, tNode))
    }

    /** Fully-determined point on a line at [distance] from [from]; direction from the click side of [at]. */
    fun pointAlongLine(
        line: Element,
        from: PointRef,
        distance: ScalarRef,
        at: Vec2,
    ): PointRef {
        val lineRef = carrierLine(line)
        val ev = Evaluator()
        val l = (ev.eval(lineRef.node) as? EvalResult.Ok)?.value as? LineValue
        val fromP = (ev.eval(from.node) as? EvalResult.Ok)?.value as? PointValue
        val sign =
            if (l != null && fromP != null) {
                val geom = l.line
                val proj = geom.origin + geom.dir * (fromP.p - geom.origin).dot(geom.dir)
                if ((at - proj).dot(geom.dir) >= 0) 1 else -1
            } else {
                1
            }
        return addDerived(cx.pointAlongLine(lineRef, from, distance, sign))
    }

    /** Point that slides along a circle; created at the click angle, draggable around the circle. */
    fun pointOnCircle(
        circle: Element,
        at: Vec2,
    ): PointRef {
        val circleRef = circle.ref as CircleRef
        val c = (Evaluator().eval(circleRef.node) as? EvalResult.Ok)?.value as? CircleValue
        val a0 = if (c != null) (at - c.circle.center).angle() else 0.0
        val aNode = SourceNode(nextId("a"), ScalarValue(Quantity.rad(a0)))
        return addConstrained(cx.pointOnCircle(circleRef, Ref<ScalarValue>(aNode)), OnCircleHandle(circleRef, aNode))
    }

    /**
     * Intersect two curves. Segments/rays are treated as their carrier line. Branch count
     * follows the pair type (line-like ∩ line-like: 1 point, else: 2).
     */
    fun intersect(
        a: Element,
        b: Element,
    ): List<PointRef> {
        val (set, lineLine) = intersectionSet(a, b) ?: return emptyList()
        val refs = ArrayList<PointRef>()
        refs.add(cx.select(set, +1))
        if (!lineLine) refs.add(cx.select(set, -1))
        refs.forEach { addDerived(it) }
        return refs
    }

    /** The intersection solution set of [a] and [b], plus whether it holds a single branch. */
    @Suppress("UNCHECKED_CAST")
    private fun intersectionSet(
        a: Element,
        b: Element,
    ): Pair<PointSetRef, Boolean>? {
        val aLin = a.isLinear
        val bLin = b.isLinear
        val aCirc = a.kind == ElementKind.CIRCLE
        val bCirc = b.kind == ElementKind.CIRCLE
        val lineLine = aLin && bLin
        val set: PointSetRef =
            when {
                lineLine -> cx.intersectLL(carrierLine(a), carrierLine(b))
                aCirc && bCirc -> cx.intersectCC(a.ref as CircleRef, b.ref as CircleRef)
                aLin && bCirc -> cx.intersectLC(carrierLine(a), b.ref as CircleRef)
                aCirc && bLin -> cx.intersectLC(carrierLine(b), a.ref as CircleRef)
                else -> return null
            }
        return set to lineLine
    }

    /**
     * The single intersection of [a] and [b] nearest [near], as a derived point — the branch the
     * click indicated, persisted as its `Select(sign)` (OP-1), never re-guessed later.
     */
    fun intersectNear(
        a: Element,
        b: Element,
        near: Vec2,
    ): PointRef? = recording("intersectnear", Arg.El(a), Arg.El(b), Arg.Pos(near)) { intersectNearNow(a, b, near) }

    private fun intersectNearNow(
        a: Element,
        b: Element,
        near: Vec2,
    ): PointRef? {
        val (set, lineLine) = intersectionSet(a, b) ?: return null
        val ev = Evaluator()
        val candidates = if (lineLine) listOf(+1) else listOf(+1, -1)
        val best =
            candidates
                .map { it to cx.select(set, it) }
                .mapNotNull { (sign, ref) ->
                    ((ev.eval(ref.node) as? EvalResult.Ok)?.value as? PointValue)?.let { Triple(sign, ref, (it.p - near).length()) }
                }.minByOrNull { it.third } ?: return null
        return addDerived(best.second)
    }

    /** A point that slides along [el] at [at] — the on-curve form of a click landing on a curve. */
    fun pointOnCurve(
        el: Element,
        at: Vec2,
    ): PointRef? = recording("pointoncurve", Arg.El(el), Arg.Pos(at)) { pointOnCurveNow(el, at) }

    private fun pointOnCurveNow(
        el: Element,
        at: Vec2,
    ): PointRef? =
        when {
            el.isLinear -> pointOnLine(el, at)
            el.kind == ElementKind.CIRCLE -> pointOnCircle(el, at)
            else -> null
        }

    /** Materialize a curve's defining points as derived points (works on transformed geometry too). */
    fun extractPoints(el: Element): List<PointRef> {
        val refs: List<PointRef> =
            when (el.kind) {
                ElementKind.SEGMENT -> listOf(cx.segmentStart(el.ref as SegmentRef), cx.segmentEnd(el.ref as SegmentRef))
                ElementKind.CIRCLE -> listOf(cx.circleCenter(el.ref as CircleRef))
                ElementKind.ARC -> listOf(cx.arcCenter(el.ref as ArcRef), cx.arcStart(el.ref as ArcRef), cx.arcEnd(el.ref as ArcRef))
                ElementKind.RAY -> listOf(cx.rayOrigin(el.ref as RayRef))
                else -> emptyList()
            }
        refs.forEach { addDerived(it) }
        return refs
    }

    fun tangentFromPoint(
        p: PointRef,
        circle: Element,
    ): List<PointRef> {
        val set = cx.tangentPointsFromPoint(p, circle.ref as CircleRef)
        val refs = listOf(cx.select(set, +1), cx.select(set, -1))
        refs.forEach { addDerived(it) }
        return refs
    }

    // ---- curves ----

    fun line(
        a: PointRef,
        b: PointRef,
    ) = add(cx.lineThrough(a, b), ElementKind.LINE, Styles.CURVE)

    fun segment(
        a: PointRef,
        b: PointRef,
    ) = add(cx.segment(a, b), ElementKind.SEGMENT, Styles.CURVE)

    // ---- architectural: ortho path (shared-coordinate rectilinear polyline) ----

    private fun scalarSource(value: Double): SourceNode = SourceNode(nextId("oc"), ScalarValue(value.mm))

    private fun orthoVertex(
        x: SourceNode,
        y: SourceNode,
        ownAxis: Int,
    ): OrthoVertex {
        val corner = OrthoCornerHandle(x, y)
        corner.ownCoord = if (ownAxis == -1) 0 else ownAxis // start: fixed once its first edge is drawn
        val ref = cx.pointXY(Ref<ScalarValue>(x), Ref<ScalarValue>(y))
        addConstrained(ref, corner)
        return OrthoVertex(ref, corner, ownAxis)
    }

    val orthoPaths = ArrayList<OrthoPath>()

    /** Start a retained ortho path at [at] with a fresh, draggable vertex owning both coordinates. */
    fun startOrthoPath(at: Vec2): OrthoPath =
        recording("orthostart", Arg.Pos(at)) {
            val path = OrthoPath()
            path.vertices.add(orthoVertex(scalarSource(at.x), scalarSource(at.y), -1))
            orthoPaths.add(path)
            path
        }

    /** Forget a path that never got a second vertex (its lone vertex element stays as a free corner). */
    fun discardOrthoPath(path: OrthoPath) {
        if (path.vertices.size < 2) recording("orthodiscard") { orthoPaths.remove(path) }
    }

    /**
     * Append a leg to [path] toward [to] (see the [prev]-based overload); records the leg segment.
     *
     * A step continuing along the *previous* leg's axis would leave two collinear legs meeting at a
     * straight "corner" — whose wall miter is the intersection of two parallel offsets, i.e.
     * undefined. Such a step extends the previous leg instead, which is also what it looks like it
     * should do. Returns the vertex the leg now ends at, or null if the step is degenerate or that
     * vertex's coordinate is driven and cannot be extended.
     */
    fun addOrthoVertex(
        path: OrthoPath,
        to: Vec2,
        // skipIfEmpty: a step that creates nothing here *extended* the previous leg, which changes no
        // topology — only a value, and values already travel with the step that introduced the node
    ): OrthoVertex? = recording("orthovertex", Arg.Pos(to), skipIfEmpty = true) { addOrthoVertexNow(path, to) }

    private fun addOrthoVertexNow(
        path: OrthoPath,
        to: Vec2,
    ): OrthoVertex? {
        val last = path.vertices.last()
        if (!path.closed && path.vertices.size >= 2 && stepAxis(last.ref, to) == last.ownAxis) {
            val node = writableMaster(last.corner.ownNode) ?: return null
            node.value = ScalarValue(Quantity.mm(if (last.ownAxis == 0) to.x else to.y))
            return last
        }
        val v = addOrthoVertex(last, to) ?: return null
        path.vertices.add(v)
        path.legs.add(dragLeg(path, lastSegment()))
        path.legAxes.add(v.ownAxis) // a leg drawn forward runs along the coordinate its far vertex introduced
        return v
    }

    /** Which axis a step from [from] to [to] runs along: 0 = horizontal, 1 = vertical, -1 = neither. */
    private fun stepAxis(
        from: PointRef,
        to: Vec2,
    ): Int {
        val p = ((Evaluator().eval(from.node) as? EvalResult.Ok)?.value as? PointValue)?.p ?: return -1
        val dx = abs(to.x - p.x)
        val dy = abs(to.y - p.y)
        if (dx < Vec2.EPS && dy < Vec2.EPS) return -1
        return if (dx >= dy) 0 else 1
    }

    /** Make [leg] a draggable leg of [path] (moves perpendicular; see [OrthoEdgeHandle]). */
    private fun dragLeg(
        path: OrthoPath,
        leg: Element,
    ): Element = leg.also { it.handle = OrthoEdgeHandle(path, it) }

    /** The most recently added segment element — the leg [addOrthoVertex] just drew. */
    private fun lastSegment(): Element = elements.last { it.kind == ElementKind.SEGMENT }

    /**
     * Append an axis-aligned vertex from [prev] toward [to]: the dominant delta picks a horizontal or
     * vertical edge, and the new vertex **shares** the perpendicular coordinate node with [prev] (so
     * the edge stays axis-aligned and a later drag of either endpoint moves only it and its
     * neighbours). Returns the new vertex, or null for a zero-length step.
     */
    fun addOrthoVertex(
        prev: OrthoVertex,
        to: Vec2,
    ): OrthoVertex? {
        val p = (Evaluator().eval(prev.ref.node) as? EvalResult.Ok)?.value as? PointValue ?: return null
        val dx = to.x - p.p.x
        val dy = to.y - p.p.y
        if (abs(dx) < Vec2.EPS && abs(dy) < Vec2.EPS) return null
        // Every vertex owns both coordinates; the leg binds the perpendicular one to the previous
        // vertex's, which keeps it axis-aligned and — unlike sharing one node — stays re-pointable, so
        // the topology can later be broken or joined (OP-19).
        val xNode: SourceNode
        val yNode: SourceNode
        val ownAxis: Int
        if (abs(dx) >= abs(dy)) {
            xNode = scalarSource(to.x)
            yNode = scalarSource(p.p.y).also { it.boundTo = prev.corner.yNode }
            ownAxis = 0
        } else {
            xNode = scalarSource(p.p.x).also { it.boundTo = prev.corner.xNode }
            yNode = scalarSource(to.y)
            ownAxis = 1
        }
        if (prev.ownAxis != -1) {
            prev.corner.isEndpoint = false // prev now has two edges (unless it is the start)
        } else {
            prev.corner.ownCoord = ownAxis // the start's own coord = the one V1 didn't share
        }
        return orthoVertex(xNode, yNode, ownAxis).also {
            // the far end of the new leg: what its length is measured from, so the length is a field
            // of the new vertex's handle (the end that moves when you write it)
            it.corner.legAnchor = if (ownAxis == 0) prev.corner.xNode else prev.corner.yNode
            segment(prev.ref, it.ref)
        }
    }

    /**
     * Split leg [legIndex] of [path] at [mPos], inserting two vertices with a **zero-length
     * perpendicular** leg between them — the break half of OP-19. The jog then opens by dragging
     * either half; [nPos] carries how far it is already open (equal to [mPos] for a fresh break).
     *
     * The two halves must be able to hold *different* perpendicular values, which is exactly what the
     * bound-coordinate representation buys: the far endpoint's binding is **re-pointed** from the near
     * endpoint onto the new jog node. Sharing one node could not express this at all.
     *
     * Works in either binding direction, which is what makes a loop's **closing** leg breakable too:
     * there the *near* endpoint is the one following, so the jog is introduced on that side instead and
     * the roles simply mirror. (Leg axes are stored per leg for the same reason — deriving them from a
     * vertex's introduced coordinate assumed every leg was drawn forward.)
     */
    fun breakOrthoLeg(
        path: OrthoPath,
        legIndex: Int,
        mPos: Vec2,
        nPos: Vec2,
    ): Boolean {
        val leg = path.legs.getOrNull(legIndex) ?: return false
        return recording("orthobreak", Arg.El(leg), Arg.Pos(mPos), Arg.Pos(nPos)) {
            breakOrthoLegNow(path, legIndex, mPos, nPos)
        }
    }

    private fun breakOrthoLegNow(
        path: OrthoPath,
        legIndex: Int,
        mPos: Vec2,
        nPos: Vec2,
    ): Boolean {
        if (legIndex < 0 || legIndex >= path.legCount) return false
        val axis = path.legAxis(legIndex)
        val (a, b) = path.legEnds(legIndex)
        val perpA = if (axis == 0) a.corner.yNode else a.corner.xNode
        val perpB = if (axis == 0) b.corner.yNode else b.corner.xNode
        // One endpoint follows the other; the jog is introduced on the *following* side, so that side's
        // binding can be re-pointed onto it while the followed side keeps whatever it already follows.
        val farFollows = perpB.boundTo === perpA
        if (!farFollows && perpA.boundTo !== perpB) return false
        val along = if (axis == 0) mPos.x else mPos.y
        val perp = if (axis == 0) nPos.y else nPos.x

        // the vertex on the followed side keeps that binding and introduces the along coordinate at the
        // click; the one on the following side introduces the jog — free, and equal to the followed
        // value, hence zero length to begin with
        val keeper = scalarSource(along) // introduces along
        val keeperPerp = scalarSource(perp).also { it.boundTo = if (farFollows) perpA else perpB }
        val jog = scalarSource(perp) // introduces the perpendicular freedom
        val jogAlong = scalarSource(along).also { it.boundTo = keeper }

        fun vertex(
            alongNode: SourceNode,
            perpNode: SourceNode,
            ownAxis: Int,
        ) = if (axis == 0) orthoVertex(alongNode, perpNode, ownAxis) else orthoVertex(perpNode, alongNode, ownAxis)
        val m = if (farFollows) vertex(keeper, keeperPerp, axis) else vertex(jogAlong, jog, 1 - axis)
        val n = if (farFollows) vertex(jogAlong, jog, 1 - axis) else vertex(keeper, keeperPerp, axis)
        if (farFollows) perpB.boundTo = jog else perpA.boundTo = jog
        m.corner.isEndpoint = false
        n.corner.isEndpoint = false
        m.corner.legAnchor = if (axis == 0) a.corner.xNode else a.corner.yNode
        n.corner.legAnchor = if (farFollows) perpA else perpB
        if (b.ownAxis == axis) b.corner.legAnchor = if (axis == 0) n.corner.xNode else n.corner.yNode

        remove(path.legs[legIndex])
        path.vertices.add(legIndex + 1, m)
        path.vertices.add(legIndex + 2, n)
        path.legs[legIndex] = dragLeg(path, segment(a.ref, m.ref))
        path.legs.add(legIndex + 1, dragLeg(path, segment(m.ref, n.ref)))
        path.legs.add(legIndex + 2, dragLeg(path, segment(n.ref, b.ref)))
        path.legAxes[legIndex] = axis
        path.legAxes.add(legIndex + 1, 1 - axis) // the inserted jog runs across the leg it splits
        path.legAxes.add(legIndex + 2, axis)
        return true
    }

    /**
     * Whether leg [i] of [path] is a jog that has been flattened and can be joined away: shorter than
     * [tol], interior (collapsing an end leg would shorten the path — a different edit), clear of a
     * loop's closing leg, and separating two legs of one run.
     */
    fun canJoinLeg(
        path: OrthoPath,
        i: Int,
        tol: Double,
    ): Boolean {
        if (i < 1 || i + 1 >= path.legCount) return false
        if (path.legAxis(i - 1) != path.legAxis(i + 1)) return false
        val seg = (Evaluator().eval(path.legs[i].ref.node) as? EvalResult.Ok)?.value as? SegmentValue ?: return false
        return (seg.seg.b - seg.seg.a).length() <= tol
    }

    /**
     * The legs a drag of [el] can flatten: the perpendicular legs at the **ends of the dragged leg**,
     * or the legs meeting at the dragged vertex.
     *
     * Only those. Dragging anything on a path used to consider every interior leg, so a jog left flat
     * on purpose — a fresh break not yet pulled open — was joined away by an unrelated drag elsewhere
     * on the same path.
     */
    fun collapseCandidates(el: Element): List<Pair<OrthoPath, Int>> {
        legOf(el)?.let { (path, i) -> return path.neighbourLegs(i).map { path to it } }
        val path = pathOf(el) ?: return emptyList()
        val vi = path.vertices.indexOfFirst { it.ref === el.ref }
        if (vi < 0) return emptyList()
        return path.neighbourLegs(vi + 1).map { path to it }
    }

    /**
     * Collapse the zero-length leg [legIndex] of [path], joining the two legs it separated into one —
     * the join half of OP-19, and the exact inverse of [breakOrthoLeg]: the far endpoint's binding is
     * re-pointed off the jog and back onto what the near half already follows. Returns the merged leg.
     *
     * [keepPerp] is the perpendicular value the joined run should end up at — the **stationary** half's,
     * so the section the user dragged snaps to what it was aimed at rather than dragging the untouched
     * half over to meet it. Null keeps whatever the surviving node already holds.
     */
    fun joinCollapsedLeg(
        path: OrthoPath,
        legIndex: Int,
        keepPerp: Double? = null,
    ): Element? {
        val leg = path.legs.getOrNull(legIndex) ?: return null
        return recording("orthojoin", Arg.El(leg)) { joinCollapsedLegNow(path, legIndex, keepPerp) }
    }

    private fun joinCollapsedLegNow(
        path: OrthoPath,
        legIndex: Int,
        keepPerp: Double?,
    ): Element? {
        if (legIndex < 1 || legIndex + 1 >= path.legCount) return null
        val axis = path.legAxis(legIndex - 1)
        if (path.legAxis(legIndex + 1) != axis) return null // not two legs of one run separated by a jog
        val a = path.vertices[legIndex - 1]
        val m = path.vertices[legIndex]
        val n = path.vertices[legIndex + 1]
        val b = path.vertices[(legIndex + 2) % path.vertices.size] // wraps when the jog abuts the closing leg
        val perpOf = { c: OrthoCornerHandle -> if (axis == 0) c.yNode else c.xNode }
        val mPerp = perpOf(m.corner)
        val nPerp = perpOf(n.corner)
        val aPerp = perpOf(a.corner)
        val bPerp = perpOf(b.corner)
        // mirror of the break: whichever outer endpoint follows the jog is re-pointed at what the other
        // side of the jog follows
        val master: Node
        if (bPerp.boundTo === nPerp && mPerp.boundTo != null) {
            master = mPerp.boundTo!!
            bPerp.boundTo = master
        } else if (aPerp.boundTo === mPerp && nPerp.boundTo != null) {
            master = nPerp.boundTo!!
            aPerp.boundTo = master
        } else {
            return null
        }
        b.corner.legAnchor = if (axis == 0) a.corner.xNode else a.corner.yNode
        // land the joined run on the stationary half's value, so the dragged section moves to it. The
        // binding direction alone would decide this, which is arbitrary: it happens to be right when the
        // dragged half is the follower and wrong when it is the one being followed.
        if (keepPerp != null) (master as? SourceNode)?.let { writableMaster(it)?.value = ScalarValue(Quantity.mm(keepPerp)) }

        listOf(path.legs[legIndex - 1], path.legs[legIndex], path.legs[legIndex + 1]).forEach { remove(it) }
        elementFor(m.ref)?.let { remove(it) }
        elementFor(n.ref)?.let { remove(it) }
        repeat(3) { path.legs.removeAt(legIndex - 1) }
        repeat(3) { path.legAxes.removeAt(legIndex - 1) }
        repeat(2) { path.vertices.removeAt(legIndex) }
        val merged = dragLeg(path, segment(a.ref, b.ref))
        path.legs.add(legIndex - 1, merged)
        path.legAxes.add(legIndex - 1, axis)
        return merged
    }

    /** The ortho path [el] belongs to — as one of its legs or as one of its vertices. */
    fun pathOf(el: Element): OrthoPath? =
        orthoPaths.firstOrNull { p -> p.legIndexOf(el) >= 0 || p.vertices.any { it.ref === el.ref } }

    /** The path and leg index of [el] if it is an ortho leg, else null. */
    fun legOf(el: Element): Pair<OrthoPath, Int>? {
        for (path in orthoPaths) {
            val i = path.legIndexOf(el)
            if (i >= 0) return path to i
        }
        return null
    }

    /** Break the ortho leg nearest [world] (within [tol]) at that point — see [breakOrthoLeg]. */
    fun breakOrthoLegNear(
        world: Vec2,
        tol: Double,
    ): Boolean {
        val leg = HitTest.nearest(this, Evaluator(), world, tol) { legOf(it) != null } ?: return false
        val (path, i) = legOf(leg) ?: return false
        val at = Snap.legPoint(Evaluator(), leg, world) ?: world
        return breakOrthoLeg(path, i, at, at)
    }

    /** Where the next leg of [path] would land (rubber-band preview). */
    fun orthoLegPreview(
        path: OrthoPath,
        to: Vec2,
    ): Pair<Vec2, Vec2>? = orthoLegPreview(path.vertices.last().ref, to)

    /** Where an ortho leg from [from] toward [to] lands (rubber-band preview): snapped to H or V. */
    fun orthoLegPreview(
        from: PointRef,
        to: Vec2,
    ): Pair<Vec2, Vec2>? {
        val p = (Evaluator().eval(from.node) as? EvalResult.Ok)?.value as? PointValue ?: return null
        val end = if (abs(to.x - p.p.x) >= abs(to.y - p.p.y)) Vec2(to.x, p.p.y) else Vec2(p.p.x, to.y)
        return p.p to end
    }

    /**
     * Close an ortho loop so the closing edge is axis-aligned. The last vertex's own coordinate is
     * **shared** with the start's matching coordinate: its source node is bound to the start's (so
     * the geometry snaps to fit), and its drag handle is redirected to write the start's node —
     * so dragging the last vertex moves the start with it (2 DOF, symmetric with every other corner)
     * rather than being pinned. Both vertices stop being endpoints.
     */
    fun closeOrthoPath(path: OrthoPath): Boolean = recording("orthoclose") { closeOrthoPathNow(path) }

    private fun closeOrthoPathNow(path: OrthoPath): Boolean {
        if (path.vertices.size < 3 || path.closed) return false
        closeOrthoPath(path.vertices.first(), path.vertices.last())
        path.closed = true // before the leg handle resolves its index, which depends on closure
        path.legs.add(dragLeg(path, segment(path.vertices.last().ref, path.vertices.first().ref)))
        path.legAxes.add(1 - path.vertices.last().ownAxis) // the closing leg runs across the last one
        return true
    }

    fun closeOrthoPath(
        first: OrthoVertex,
        last: OrthoVertex,
    ) {
        when (last.ownAxis) {
            0 -> last.corner.xNode.boundTo = first.corner.xNode // own x -> vertical closing edge
            1 -> last.corner.yNode.boundTo = first.corner.yNode // own y -> horizontal closing edge
            else -> return
        }
        last.corner.isEndpoint = false
        first.corner.isEndpoint = false
    }

    val walls = ArrayList<Wall>()

    /**
     * Build a retained wall of [thickness] along the centerline through [vertices]: two offset faces
     * whose interior corners are the intersections of adjacent offset lines (miter joints), closed
     * by end caps. Fully parametric — faces track the centerline vertices and the thickness. Returns
     * the [Wall] so openings can be added later. A straight run (collinear legs) yields parallel
     * offsets whose miter is undefined and simply renders invalid.
     */
    fun buildWall(
        vertices: List<PointRef>,
        thickness: ScalarRef,
        closed: Boolean = false,
        path: OrthoPath? = null,
    ): Wall? {
        if (vertices.size < 2) return null
        val w = Wall(vertices.toList(), thickness, closed && vertices.size >= 3, path)
        walls.add(w)
        regenerateWall(w)
        return w
    }

    /** Build a wall along the centerline of [path], keeping the path as the wall's editable spine. */
    fun buildWall(
        path: OrthoPath,
        thickness: ScalarRef,
    ): Wall? =
        recording("wall", Arg.Sc(scalarEntryFor(thickness))) {
            buildWall(path.vertices.map { it.ref }, thickness, path.closed, path)
        }

    /** The named entry driving [ref] — every scalar a tool consumes came from the panel. */
    private fun scalarEntryFor(ref: ScalarRef): ScalarEntry =
        scalars.firstOrNull { it.ref.node === ref.node }
            ?: newParameter("v", (Evaluator().eval(ref.node) as? EvalResult.Ok)?.let { (it.value as? ScalarValue)?.q } ?: 0.0.mm)

    private fun evalMm(ref: ScalarRef): Double =
        (Evaluator().eval(ref.node) as? EvalResult.Ok)?.let { (it.value as? ScalarValue)?.q?.mm } ?: 0.0

    /** The face point at centerline distance [dist] from leg [legI]'s start, on face line [faceLine]. */
    private fun facePointAt(
        legLine: LineRef,
        legStart: PointRef,
        dist: ScalarRef,
        faceLine: LineRef,
    ): PointRef =
        cx.projectToLine(cx.pointAlongLine(legLine, legStart, dist, +1), faceLine)

    /** (Re)build a wall's face/cap/jamb geometry from its centerline, thickness and openings. */
    fun regenerateWall(w: Wall) {
        elements.removeAll { it.id in w.ownedIds }
        w.ownedIds.clear()

        fun own(ref: SegmentRef) {
            w.ownedIds.add(add(ref, ElementKind.SEGMENT, Styles.WALL).id)
        }

        val v = w.vertices
        val closed = w.closed && v.size >= 3
        val legCount = if (closed) v.size else v.size - 1
        val half = cx.scale(w.thickness, 0.5)
        val legLines = (0 until legCount).map { cx.lineThrough(v[it], v[(it + 1) % v.size]) }
        val flBySide = intArrayOf(+1, -1).map { s -> legLines.map { cx.parallelAtDistance(it, half, s) } }

        // corner points per side: closed -> one miter per vertex (wraps); open -> start cap, miters, end cap
        val cornersBySide =
            flBySide.map { fl ->
                val c = ArrayList<PointRef>()
                if (closed) {
                    for (j in 0 until legCount) c.add(cx.select(cx.intersectLL(fl[(j - 1 + legCount) % legCount], fl[j]), +1))
                } else {
                    c.add(cx.projectToLine(v.first(), fl.first()))
                    for (j in 1 until legCount) c.add(cx.select(cx.intersectLL(fl[j - 1], fl[j]), +1))
                    c.add(cx.projectToLine(v.last(), fl.last()))
                }
                c
            }

        // face pieces per side, split by the openings on each leg
        for (side in 0..1) {
            val fl = flBySide[side]
            val corners = cornersBySide[side]
            for (legI in 0 until legCount) {
                val ops = w.openings.filter { it.legIndex == legI }.sortedBy { evalMm(it.position) }
                var prev = corners[legI]
                for (op in ops) {
                    val js = facePointAt(legLines[legI], v[legI], op.position, fl[legI])
                    val je = facePointAt(legLines[legI], v[legI], cx.add(op.position, op.width), fl[legI])
                    own(cx.segment(prev, js))
                    prev = je // solid piece then gap
                }
                own(cx.segment(prev, corners[if (closed) (legI + 1) % legCount else legI + 1]))
            }
        }
        // end caps only for an open wall (a closed loop has none)
        if (!closed) {
            own(cx.segment(cornersBySide[0].first(), cornersBySide[1].first()))
            own(cx.segment(cornersBySide[0].last(), cornersBySide[1].last()))
        }
        // jambs (reveal lines) across the wall at each opening edge
        for (op in w.openings) {
            val leg = legLines[op.legIndex]
            val start = v[op.legIndex]
            val sEnd = cx.add(op.position, op.width)
            own(
                cx.segment(
                    facePointAt(leg, start, op.position, flBySide[0][op.legIndex]),
                    facePointAt(leg, start, op.position, flBySide[1][op.legIndex]),
                ),
            )
            own(
                cx.segment(
                    facePointAt(leg, start, sEnd, flBySide[0][op.legIndex]),
                    facePointAt(leg, start, sEnd, flBySide[1][op.legIndex]),
                ),
            )
        }
    }

    /**
     * Add an opening (door/window gap) of width [width] to whichever wall leg is nearest [at]. The
     * opening is positioned by a new parameter (distance along the leg from its start), so both its
     * position and width are editable and the wall regenerates around them. No-op if no wall leg is
     * within tolerance.
     */
    fun addOpeningAtRecorded(
        world: Vec2,
        width: ScalarRef,
        tol: Double,
    ): Boolean = recording("opening", Arg.Pos(world), Arg.Sc(scalarEntryFor(width)), Arg.Num(Quantity.mm(tol))) { addOpeningAt(world, width, tol) }

    fun addOpeningAt(
        at: Vec2,
        width: ScalarRef,
        tol: Double,
    ): Boolean {
        val ev = Evaluator()
        var best: Wall? = null
        var bestLeg = -1
        var bestPos = 0.0
        var bestD = Double.MAX_VALUE
        for (w in walls) {
            val threshold = tol + evalMm(w.thickness) / 2 // clicking anywhere on the wall body counts
            for (i in 0 until w.vertices.size - 1) {
                val a = (ev.eval(w.vertices[i].node) as? EvalResult.Ok)?.value as? PointValue ?: continue
                val b = (ev.eval(w.vertices[i + 1].node) as? EvalResult.Ok)?.value as? PointValue ?: continue
                val ab = b.p - a.p
                val len = ab.length()
                if (len < Vec2.EPS) continue
                val t = ((at - a.p).dot(ab) / (len * len)).coerceIn(0.0, 1.0)
                val d = (at - (a.p + ab * t)).length()
                if (d <= threshold && d < bestD) {
                    bestD = d
                    best = w
                    bestLeg = i
                    bestPos = t * len
                }
            }
        }
        val w = best ?: return false
        val widthVal = evalMm(width)
        val legLen =
            run {
                val a = (ev.eval(w.vertices[bestLeg].node) as EvalResult.Ok).value as PointValue
                val b = (ev.eval(w.vertices[bestLeg + 1].node) as EvalResult.Ok).value as PointValue
                (b.p - a.p).length()
            }
        val pos = (bestPos - widthVal / 2).coerceIn(0.0, maxOf(0.0, legLen - widthVal)) // centre on the click
        val posRef = newParameter("op", pos.mm).ref
        w.openings.add(Opening(bestLeg, posRef, width))
        regenerateWall(w)
        return true
    }

    fun ray(
        a: PointRef,
        b: PointRef,
    ) = add(cx.ray(a, b), ElementKind.RAY, Styles.CURVE)

    fun circle(
        center: PointRef,
        through: PointRef,
    ) = add(cx.circleCP(center, through), ElementKind.CIRCLE, Styles.CURVE)

    fun circleCR(
        center: PointRef,
        radius: ScalarRef,
    ) = add(cx.circleCR(center, radius), ElementKind.CIRCLE, Styles.CURVE)

    fun circle3(
        a: PointRef,
        b: PointRef,
        c: PointRef,
    ) = add(cx.circle3(a, b, c), ElementKind.CIRCLE, Styles.CURVE)

    fun arc3(
        a: PointRef,
        b: PointRef,
        c: PointRef,
    ) = add(cx.arc3(a, b, c), ElementKind.ARC, Styles.CURVE)

    fun arcCenterStartEnd(
        center: PointRef,
        start: PointRef,
        end: PointRef,
    ) = add(cx.arcCenterStartEnd(center, start, end), ElementKind.ARC, Styles.CURVE)

    // ---- relational constructions ----

    fun perpBisector(
        a: PointRef,
        b: PointRef,
    ) = add(cx.perpBisector(a, b), ElementKind.LINE, Styles.CONSTRUCT)

    fun angleBisector(
        a: PointRef,
        v: PointRef,
        b: PointRef,
    ) = add(cx.angleBisector(a, v, b), ElementKind.LINE, Styles.CONSTRUCT)

    fun perpendicularThrough(
        line: Element,
        p: PointRef,
    ) = add(cx.perpendicularThrough(carrierLine(line), p), ElementKind.LINE, Styles.CONSTRUCT)

    /** Tangent at a point-on-circle — the circle is inferred from the point's handle. */
    fun tangentAtPointOnCircle(pointEl: Element) {
        val c = pointEl.handle
        if (c is OnCircleHandle) add(cx.tangentAtCircle(c.circle, pointEl.ref as PointRef), ElementKind.LINE, Styles.CONSTRUCT)
    }

    fun parallelThrough(
        line: Element,
        p: PointRef,
    ) = add(cx.parallelThrough(carrierLine(line), p), ElementKind.LINE, Styles.CONSTRUCT)

    /**
     * Fillet between two legs (lines/segments/rays). The corner is their intersection; the
     * quadrant is chosen by which side of the corner each leg was clicked ([clickA]/[clickB]).
     */
    fun filletBetweenLines(
        leg1: Element,
        leg2: Element,
        radius: ScalarRef,
        clickA: Vec2,
        clickB: Vec2,
    ): Element {
        val l1 = carrierLine(leg1)
        val l2 = carrierLine(leg2)
        val ev = Evaluator()
        val la = (ev.eval(l1.node) as? EvalResult.Ok)?.value as? LineValue
        val lb = (ev.eval(l2.node) as? EvalResult.Ok)?.value as? LineValue
        var sign1 = 1
        var sign2 = 1
        if (la != null && lb != null) {
            val denom = la.line.dir.cross(lb.line.dir)
            if (kotlin.math.abs(denom) > Vec2.EPS) {
                val corner = la.line.origin + la.line.dir * ((lb.line.origin - la.line.origin).cross(lb.line.dir) / denom)
                sign1 = if ((clickA - corner).dot(la.line.dir) < 0) -1 else 1
                sign2 = if ((clickB - corner).dot(lb.line.dir) < 0) -1 else 1
            }
        }
        return add(cx.filletBetweenLines(l1, l2, radius, sign1, sign2), ElementKind.ARC, Styles.CURVE)
    }

    /** Both external (or internal) common tangents of two circles. */
    fun commonTangents(
        c1: Element,
        c2: Element,
        inner: Boolean,
    ): List<Element> {
        val a = c1.ref as CircleRef
        val b = c2.ref as CircleRef
        return listOf(+1, -1).map {
            add(if (inner) cx.innerTangent(a, b, it) else cx.outerTangent(a, b, it), ElementKind.LINE, Styles.CONSTRUCT)
        }
    }

    /** Concentric circle offset by [distance]; shrinks if [at] is inside the circle, else grows. */
    fun concentricCircle(
        circle: Element,
        distance: ScalarRef,
        at: Vec2,
    ): Element {
        val ref = circle.ref as CircleRef
        val c = (Evaluator().eval(ref.node) as? EvalResult.Ok)?.value as? CircleValue
        val sign = if (c != null && (at - c.circle.center).length() < c.circle.radius) -1 else 1
        return add(cx.concentricCircle(ref, distance, sign), ElementKind.CIRCLE, Styles.CURVE)
    }

    /** Parallel to [line] offset by [distance]; side chosen by which side of the line [at] is on. */
    fun parallelAtDistance(
        line: Element,
        distance: ScalarRef,
        at: Vec2,
    ): Element {
        val lineRef = carrierLine(line)
        val l = (Evaluator().eval(lineRef.node) as? EvalResult.Ok)?.value as? LineValue
        val sign = if (l != null && (at - l.line.origin).dot(l.line.dir.perp()) < 0) -1 else 1
        return add(cx.parallelAtDistance(lineRef, distance, sign), ElementKind.LINE, Styles.CONSTRUCT)
    }

    // ---- transforms (preserve source kind & style) ----

    @Suppress("UNCHECKED_CAST")
    fun mirror(
        geom: Element,
        axis: Element,
    ) = add(cx.mirror(geom.ref as Ref<Value>, axis.ref as LineRef), geom.kind, geom.style)

    @Suppress("UNCHECKED_CAST")
    fun rotate(
        geom: Element,
        center: PointRef,
        angle: ScalarRef,
    ) = add(cx.rotate(geom.ref as Ref<Value>, center, angle), geom.kind, geom.style)

    @Suppress("UNCHECKED_CAST")
    fun scale(
        geom: Element,
        center: PointRef,
        factor: ScalarRef,
    ) = add(cx.scaleGeom(geom.ref as Ref<Value>, center, factor), geom.kind, geom.style)

    @Suppress("UNCHECKED_CAST")
    fun translateByVector(
        geom: Element,
        from: PointRef,
        to: PointRef,
    ) = add(cx.translateByVector(geom.ref as Ref<Value>, from, to), geom.kind, geom.style)

    // ---- measurements ----

    fun measureDistance(
        a: PointRef,
        b: PointRef,
    ) = measurement("dist", cx.measureDistance(a, b))

    fun measureAngle(
        a: PointRef,
        v: PointRef,
        b: PointRef,
    ) = measurement("angle", cx.measureAngle(a, v, b))

    fun measureLength(seg: Element) = measurement("len", cx.measureLength(seg.ref as SegmentRef))

    fun measureRadius(circle: Element) = measurement("radius", cx.measureRadius(circle.ref as CircleRef))

    fun measureX(p: PointRef) = measurement("x", cx.measureX(p))

    fun measureY(p: PointRef) = measurement("y", cx.measureY(p))

    fun measureAngleLines(
        l1: Element,
        l2: Element,
    ) = measurement("angle", cx.measureAngleLines(carrierLine(l1), carrierLine(l2)))
}

/** Default element styles. */
object Styles {
    val FREE_POINT = Style(stroke = "#1f77b4", width = 1.0)
    val DERIVED_POINT = Style(stroke = "#2ca02c", width = 1.0)
    val ON_CURVE = Style(stroke = "#ff7f0e", width = 1.0)
    val CURVE = Style(stroke = "#333333", width = 1.5)
    val CONSTRUCT = Style(stroke = "#9467bd", width = 1.2)
    val INVALID = Style(stroke = "#dddddd", width = 1.0)
    val PREVIEW = Style(stroke = "#ff7f0e", width = 1.0)
    val WALL = Style(stroke = "#333333", width = 2.4)
}
