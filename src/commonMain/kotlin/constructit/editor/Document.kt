package constructit.editor

import constructit.core.CircleValue
import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.LineValue
import constructit.core.Node
import constructit.core.ParameterNode
import constructit.core.PointValue
import constructit.core.ScalarValue
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
    /** For [ElementKind.ON_CURVE]: how a drag updates the hidden position parameter. */
    var constraint: PointConstraint? = null,
) {
    val draggable: Boolean get() =
        (kind == ElementKind.POINT && (ref.node as? SourceNode)?.boundTo == null) || kind == ElementKind.ON_CURVE
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
 * start, which owns both) — the safe one to bind when closing a loop.
 */
class OrthoVertex(val ref: PointRef, val corner: OrthoCornerConstraint, val ownAxis: Int)

/** A gap in a wall leg: [position] = distance from the leg start, [width] along the leg. */
class Opening(val legIndex: Int, val position: ScalarRef, val width: ScalarRef)

/**
 * A retained wall: a centerline through [vertices] with a [thickness], plus [openings]. Its face /
 * cap / jamb geometry is derived and regenerated (into [ownedIds]) whenever openings change — the
 * centerline and its length parameters live outside the wall, so editing them reshapes it too.
 */
class Wall(val vertices: List<PointRef>, val thickness: ScalarRef, val closed: Boolean = false) {
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
    private fun nextId(prefix: String) = "$prefix${++counter}"

    val freePoints: List<Element> get() = elements.filter { it.kind == ElementKind.POINT }

    private fun add(ref: Ref<*>, kind: ElementKind, style: Style): Element {
        val el = Element(nextId("e"), ref, kind, style)
        elements.add(el)
        return el
    }

    private fun addDerived(ref: PointRef): PointRef {
        add(ref, ElementKind.DERIVED_POINT, Styles.DERIVED_POINT)
        return ref
    }

    /** Coerce a line/segment/ray element to its infinite carrier line. */
    @Suppress("UNCHECKED_CAST")
    private fun carrierLine(el: Element): LineRef = when (el.kind) {
        ElementKind.SEGMENT -> cx.lineOfSegment(el.ref as SegmentRef)
        ElementKind.RAY -> cx.lineOfRay(el.ref as RayRef)
        else -> el.ref as LineRef
    }

    // ---- free points & scalars ----

    fun freePoint(x: Quantity, y: Quantity): PointRef =
        cx.freePoint("P${counter + 1}", x, y).also { add(it, ElementKind.POINT, Styles.FREE_POINT) }

    /** Ensure scalar names are unique so the wiring dropdown is never ambiguous. */
    private fun uniqueScalarName(base: String): String {
        val b = base.ifBlank { "p" }
        if (scalars.none { it.name == b }) return b
        var i = 2
        while (scalars.any { it.name == "$b$i" }) i++
        return "$b$i"
    }

    fun newParameter(name: String, value: Quantity): ScalarEntry {
        val node = ParameterNode(nextId("pn"), ScalarValue(value))
        val e = ScalarEntry(nextId("s"), uniqueScalarName(name), Ref<ScalarValue>(node), editable = true)
        scalars.add(e); return e
    }

    private fun measurement(name: String, ref: ScalarRef): ScalarEntry {
        val e = ScalarEntry(nextId("m"), uniqueScalarName(name), ref, editable = false)
        scalars.add(e); return e
    }

    fun setParameter(e: ScalarEntry, value: Quantity) {
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

    private fun dependsOn(from: Node, target: Node, seen: MutableSet<String>): Boolean {
        if (from === target) return true
        if (!seen.add(from.id)) return false
        for (i in from.inputs) if (dependsOn(i, target, seen)) return true
        return false
    }

    /** Wire parameter [e] to track [target]. Rejected on type mismatch or if it would cycle. */
    fun wireParameter(e: ScalarEntry, target: ScalarEntry): Boolean {
        val node = e.ref.node as? ParameterNode ?: return false
        if (target.ref.node === node) return false
        val myDim = dimOf(node); val tgtDim = dimOf(target.ref.node)
        if (myDim != null && tgtDim != null && myDim != tgtDim) return false      // same type only
        if (dependsOn(target.ref.node, node, HashSet())) return false             // no cycles
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

    fun moveFreePoint(el: Element, world: Vec2) {
        require(el.kind == ElementKind.POINT) { "not a free point" }
        (el.ref.node as SourceNode).value = PointValue(world)
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
    fun weld(alias: Element, master: Element): Boolean {
        val node = alias.ref.node as? SourceNode ?: return false
        if (alias.kind != ElementKind.POINT || node.boundTo != null) return false
        if (!master.isPoint || master === alias) return false
        val masterNode = master.ref.node
        if (masterNode === node || dependsOn(masterNode, node, HashSet())) return false   // no cycles
        node.boundTo = masterNode
        alias.visible = false
        return true
    }

    /** Un-weld / detach: the point resumes as an independent free point at its current position. */
    fun unweld(alias: Element) {
        val node = alias.ref.node as? SourceNode ?: return
        val cur = (Evaluator().eval(node) as? EvalResult.Ok)?.let { (it.value as? PointValue)?.p }
        node.boundTo = null
        if (cur != null) node.value = PointValue(cur)
        alias.kind = ElementKind.POINT
        alias.constraint = null
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
    fun attachTargetPos(pt: Element, curve: Element): Vec2? {
        val node = pt.ref.node as? SourceNode ?: return null
        if (pt.kind != ElementKind.POINT || node.boundTo != null) return null
        return curveProjection(pt, curve)
    }

    /**
     * Where point-element [pt] projects onto [curve] (foot on a line, nearest point on a circle), or
     * null if [curve] is built from [pt] (would cycle) or isn't a line/circle. Works for any point,
     * so both free points and ortho endpoints can use it for the drag magnet.
     */
    fun curveProjection(pt: Element, curve: Element): Vec2? {
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
                if (len < Vec2.EPS) c.circle.center + Vec2(c.circle.radius, 0.0)
                else c.circle.center + d * (c.circle.radius / len)
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
    fun attachToCurve(pt: Element, curve: Element): Boolean {
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
                pt.constraint = OnLineConstraint(lr, tNode)
            }
            else -> {   // circle
                val cr = curve.ref as CircleRef
                val c = (ev.eval(cr.node) as EvalResult.Ok).value as CircleValue
                val aNode = SourceNode(nextId("a"), ScalarValue(Quantity.rad((p - c.circle.center).angle())))
                node.boundTo = cx.pointOnCircle(cr, Ref<ScalarValue>(aNode)).node
                pt.constraint = OnCircleConstraint(cr, aNode)
            }
        }
        pt.kind = ElementKind.ON_CURVE
        pt.style = Styles.ON_CURVE
        return true
    }

    /** The ortho-corner constraint of [el] if it is a draggable *end* of an open path, else null. */
    fun orthoEndpoint(el: Element): OrthoCornerConstraint? =
        (el.constraint as? OrthoCornerConstraint)?.takeIf { it.isEndpoint }

    /**
     * Attach an ortho path endpoint [el] onto [curve]: both its coordinate nodes are bound to a fresh
     * point-on-curve, so the endpoint — and the neighbour sharing one coordinate — follow the curve,
     * and dragging it now slides along the curve. The ortho analogue of [attachToCurve].
     */
    fun attachOrthoEndpointToCurve(el: Element, curve: Element): Boolean {
        val corner = orthoEndpoint(el) ?: return false
        val ev = Evaluator()
        val p = (ev.eval(el.ref.node) as? EvalResult.Ok)?.let { (it.value as? PointValue)?.p } ?: return false
        val pol: PointRef = when {
            curve.isLinear -> {
                val lr = carrierLine(curve)
                if (dependsOn(lr.node, el.ref.node, HashSet())) return false
                val l = (ev.eval(lr.node) as EvalResult.Ok).value as LineValue
                val tNode = SourceNode(nextId("t"), ScalarValue(Quantity.mm((p - l.line.origin).dot(l.line.dir))))
                el.constraint = OnLineConstraint(lr, tNode)
                cx.pointOnLineAt(lr, Ref<ScalarValue>(tNode))
            }
            curve.kind == ElementKind.CIRCLE -> {
                val cr = curve.ref as CircleRef
                if (dependsOn(cr.node, el.ref.node, HashSet())) return false
                val c = (ev.eval(cr.node) as EvalResult.Ok).value as CircleValue
                val aNode = SourceNode(nextId("a"), ScalarValue(Quantity.rad((p - c.circle.center).angle())))
                el.constraint = OnCircleConstraint(cr, aNode)
                cx.pointOnCircle(cr, Ref<ScalarValue>(aNode))
            }
            else -> return false
        }
        corner.xNode.boundTo = cx.measureX(pol).node
        corner.yNode.boundTo = cx.measureY(pol).node
        return true
    }

    /** Weld an ortho path endpoint [el] onto point [target]: its coordinates track the target. */
    fun weldOrthoEndpointToPoint(el: Element, target: Element): Boolean {
        val corner = orthoEndpoint(el) ?: return false
        val tref = target.ref as? PointRef ?: return false
        if (!target.isPoint || target === el || dependsOn(tref.node, el.ref.node, HashSet())) return false
        corner.xNode.boundTo = cx.measureX(tref).node
        corner.yNode.boundTo = cx.measureY(tref).node
        corner.isEndpoint = false
        return true
    }

    fun remove(el: Element) { elements.remove(el) }

    // ---- points ----

    fun midpoint(a: PointRef, b: PointRef) = addDerived(cx.midpoint(a, b))

    /** The centre of a circle or arc as a derived point (works on 3-point circles etc.). */
    fun centerOf(el: Element): PointRef? = when (el.kind) {
        ElementKind.CIRCLE -> addDerived(cx.circleCenter(el.ref as CircleRef))
        ElementKind.ARC -> addDerived(cx.arcCenter(el.ref as ArcRef))
        else -> null
    }
    fun projectToLine(p: PointRef, line: Element) = addDerived(cx.projectToLine(p, carrierLine(line)))

    private fun addConstrained(ref: PointRef, constraint: PointConstraint): PointRef {
        elements.add(Element(nextId("e"), ref, ElementKind.ON_CURVE, Styles.ON_CURVE, constraint = constraint))
        return ref
    }

    /** Point that slides along a line; created at the projection of [at], draggable along the line. */
    fun pointOnLine(line: Element, at: Vec2): PointRef {
        val lineRef = carrierLine(line)
        val l = (Evaluator().eval(lineRef.node) as? EvalResult.Ok)?.value as? LineValue
        val t0 = if (l != null) (at - l.line.origin).dot(l.line.dir) else 0.0
        val tNode = SourceNode(nextId("t"), ScalarValue(Quantity.mm(t0)))
        return addConstrained(cx.pointOnLineAt(lineRef, Ref<ScalarValue>(tNode)), OnLineConstraint(lineRef, tNode))
    }

    /** Fully-determined point on a line at [distance] from [from]; direction from the click side of [at]. */
    fun pointAlongLine(line: Element, from: PointRef, distance: ScalarRef, at: Vec2): PointRef {
        val lineRef = carrierLine(line)
        val ev = Evaluator()
        val l = (ev.eval(lineRef.node) as? EvalResult.Ok)?.value as? LineValue
        val fromP = (ev.eval(from.node) as? EvalResult.Ok)?.value as? PointValue
        val sign = if (l != null && fromP != null) {
            val geom = l.line
            val proj = geom.origin + geom.dir * (fromP.p - geom.origin).dot(geom.dir)
            if ((at - proj).dot(geom.dir) >= 0) 1 else -1
        } else 1
        return addDerived(cx.pointAlongLine(lineRef, from, distance, sign))
    }

    /** Point that slides along a circle; created at the click angle, draggable around the circle. */
    fun pointOnCircle(circle: Element, at: Vec2): PointRef {
        val circleRef = circle.ref as CircleRef
        val c = (Evaluator().eval(circleRef.node) as? EvalResult.Ok)?.value as? CircleValue
        val a0 = if (c != null) (at - c.circle.center).angle() else 0.0
        val aNode = SourceNode(nextId("a"), ScalarValue(Quantity.rad(a0)))
        return addConstrained(cx.pointOnCircle(circleRef, Ref<ScalarValue>(aNode)), OnCircleConstraint(circleRef, aNode))
    }

    /**
     * Intersect two curves. Segments/rays are treated as their carrier line. Branch count
     * follows the pair type (line-like ∩ line-like: 1 point, else: 2).
     */
    fun intersect(a: Element, b: Element): List<PointRef> {
        val aLin = a.isLinear; val bLin = b.isLinear
        val aCirc = a.kind == ElementKind.CIRCLE; val bCirc = b.kind == ElementKind.CIRCLE
        val lineLine = aLin && bLin
        @Suppress("UNCHECKED_CAST")
        val set: PointSetRef = when {
            lineLine -> cx.intersectLL(carrierLine(a), carrierLine(b))
            aCirc && bCirc -> cx.intersectCC(a.ref as CircleRef, b.ref as CircleRef)
            aLin && bCirc -> cx.intersectLC(carrierLine(a), b.ref as CircleRef)
            aCirc && bLin -> cx.intersectLC(carrierLine(b), a.ref as CircleRef)
            else -> return emptyList()
        }
        val refs = ArrayList<PointRef>()
        refs.add(cx.select(set, +1))
        if (!lineLine) refs.add(cx.select(set, -1))
        refs.forEach { addDerived(it) }
        return refs
    }

    /** Materialize a curve's defining points as derived points (works on transformed geometry too). */
    fun extractPoints(el: Element): List<PointRef> {
        val refs: List<PointRef> = when (el.kind) {
            ElementKind.SEGMENT -> listOf(cx.segmentStart(el.ref as SegmentRef), cx.segmentEnd(el.ref as SegmentRef))
            ElementKind.CIRCLE -> listOf(cx.circleCenter(el.ref as CircleRef))
            ElementKind.ARC -> listOf(cx.arcCenter(el.ref as ArcRef), cx.arcStart(el.ref as ArcRef), cx.arcEnd(el.ref as ArcRef))
            ElementKind.RAY -> listOf(cx.rayOrigin(el.ref as RayRef))
            else -> emptyList()
        }
        refs.forEach { addDerived(it) }
        return refs
    }

    fun tangentFromPoint(p: PointRef, circle: Element): List<PointRef> {
        val set = cx.tangentPointsFromPoint(p, circle.ref as CircleRef)
        val refs = listOf(cx.select(set, +1), cx.select(set, -1))
        refs.forEach { addDerived(it) }
        return refs
    }

    // ---- curves ----

    fun line(a: PointRef, b: PointRef) = add(cx.lineThrough(a, b), ElementKind.LINE, Styles.CURVE)
    fun segment(a: PointRef, b: PointRef) = add(cx.segment(a, b), ElementKind.SEGMENT, Styles.CURVE)

    // ---- architectural: ortho path (shared-coordinate rectilinear polyline) ----

    private fun scalarSource(value: Double): SourceNode = SourceNode(nextId("oc"), ScalarValue(value.mm))

    private fun orthoVertex(x: SourceNode, y: SourceNode, ownAxis: Int): OrthoVertex {
        val corner = OrthoCornerConstraint(x, y)
        val ref = cx.pointXY(Ref<ScalarValue>(x), Ref<ScalarValue>(y))
        addConstrained(ref, corner)
        return OrthoVertex(ref, corner, ownAxis)
    }

    /** Start an ortho path at [at] with a fresh, draggable vertex owning both coordinates. */
    fun startOrthoVertex(at: Vec2): OrthoVertex = orthoVertex(scalarSource(at.x), scalarSource(at.y), -1)

    /**
     * Append an axis-aligned vertex from [prev] toward [to]: the dominant delta picks a horizontal or
     * vertical edge, and the new vertex **shares** the perpendicular coordinate node with [prev] (so
     * the edge stays axis-aligned and a later drag of either endpoint moves only it and its
     * neighbours). Returns the new vertex, or null for a zero-length step.
     */
    fun addOrthoVertex(prev: OrthoVertex, to: Vec2): OrthoVertex? {
        val p = (Evaluator().eval(prev.ref.node) as? EvalResult.Ok)?.value as? PointValue ?: return null
        val dx = to.x - p.p.x; val dy = to.y - p.p.y
        if (abs(dx) < Vec2.EPS && abs(dy) < Vec2.EPS) return null
        // horizontal edge: new x node, share prev's y node (ownAxis 0); vertical: share x, new y (ownAxis 1)
        val xNode: SourceNode; val yNode: SourceNode; val ownAxis: Int
        if (abs(dx) >= abs(dy)) { xNode = scalarSource(to.x); yNode = prev.corner.yNode; ownAxis = 0 }
        else                    { xNode = prev.corner.xNode; yNode = scalarSource(to.y); ownAxis = 1 }
        if (prev.ownAxis != -1) prev.corner.isEndpoint = false   // prev now has two edges (unless it is the start)
        return orthoVertex(xNode, yNode, ownAxis).also { segment(prev.ref, it.ref) }
    }

    /** Where an ortho leg from [from] toward [to] lands (rubber-band preview): snapped to H or V. */
    fun orthoLegPreview(from: PointRef, to: Vec2): Pair<Vec2, Vec2>? {
        val p = (Evaluator().eval(from.node) as? EvalResult.Ok)?.value as? PointValue ?: return null
        val end = if (abs(to.x - p.p.x) >= abs(to.y - p.p.y)) Vec2(to.x, p.p.y) else Vec2(p.p.x, to.y)
        return p.p to end
    }

    /**
     * Close an ortho loop so the closing edge is axis-aligned. The last vertex's own coordinate is
     * **shared** with the start's matching coordinate: its source node is bound to the start's (so
     * the geometry snaps to fit), and its drag-constraint is redirected to write the start's node —
     * so dragging the last vertex moves the start with it (2 DOF, symmetric with every other corner)
     * rather than being pinned. Both vertices stop being endpoints.
     */
    fun closeOrthoPath(first: OrthoVertex, last: OrthoVertex) {
        val el = elements.firstOrNull { it.ref === last.ref } ?: return
        val redirect = when (last.ownAxis) {
            0 -> { last.corner.xNode.boundTo = first.corner.xNode      // own x -> vertical closing edge
                   OrthoCornerConstraint(first.corner.xNode, last.corner.yNode) }
            1 -> { last.corner.yNode.boundTo = first.corner.yNode      // own y -> horizontal closing edge
                   OrthoCornerConstraint(last.corner.xNode, first.corner.yNode) }
            else -> return
        }
        redirect.isEndpoint = false
        first.corner.isEndpoint = false
        el.constraint = redirect
    }

    val walls = ArrayList<Wall>()

    /**
     * Build a retained wall of [thickness] along the centerline through [vertices]: two offset faces
     * whose interior corners are the intersections of adjacent offset lines (miter joints), closed
     * by end caps. Fully parametric — faces track the centerline vertices and the thickness. Returns
     * the [Wall] so openings can be added later. A straight run (collinear legs) yields parallel
     * offsets whose miter is undefined and simply renders invalid.
     */
    fun buildWall(vertices: List<PointRef>, thickness: ScalarRef, closed: Boolean = false): Wall? {
        if (vertices.size < 2) return null
        val w = Wall(vertices.toList(), thickness, closed && vertices.size >= 3)
        walls.add(w)
        regenerateWall(w)
        return w
    }

    private fun evalMm(ref: ScalarRef): Double =
        (Evaluator().eval(ref.node) as? EvalResult.Ok)?.let { (it.value as? ScalarValue)?.q?.mm } ?: 0.0

    /** The face point at centerline distance [dist] from leg [legI]'s start, on face line [faceLine]. */
    private fun facePointAt(legLine: LineRef, legStart: PointRef, dist: ScalarRef, faceLine: LineRef): PointRef =
        cx.projectToLine(cx.pointAlongLine(legLine, legStart, dist, +1), faceLine)

    /** (Re)build a wall's face/cap/jamb geometry from its centerline, thickness and openings. */
    fun regenerateWall(w: Wall) {
        elements.removeAll { it.id in w.ownedIds }
        w.ownedIds.clear()
        fun own(ref: SegmentRef) { w.ownedIds.add(add(ref, ElementKind.SEGMENT, Styles.WALL).id) }

        val v = w.vertices
        val closed = w.closed && v.size >= 3
        val legCount = if (closed) v.size else v.size - 1
        val half = cx.scale(w.thickness, 0.5)
        val legLines = (0 until legCount).map { cx.lineThrough(v[it], v[(it + 1) % v.size]) }
        val flBySide = intArrayOf(+1, -1).map { s -> legLines.map { cx.parallelAtDistance(it, half, s) } }

        // corner points per side: closed -> one miter per vertex (wraps); open -> start cap, miters, end cap
        val cornersBySide = flBySide.map { fl ->
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
            val fl = flBySide[side]; val corners = cornersBySide[side]
            for (legI in 0 until legCount) {
                val ops = w.openings.filter { it.legIndex == legI }.sortedBy { evalMm(it.position) }
                var prev = corners[legI]
                for (op in ops) {
                    val js = facePointAt(legLines[legI], v[legI], op.position, fl[legI])
                    val je = facePointAt(legLines[legI], v[legI], cx.add(op.position, op.width), fl[legI])
                    own(cx.segment(prev, js)); prev = je   // solid piece then gap
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
            val leg = legLines[op.legIndex]; val start = v[op.legIndex]
            val sEnd = cx.add(op.position, op.width)
            own(cx.segment(facePointAt(leg, start, op.position, flBySide[0][op.legIndex]),
                           facePointAt(leg, start, op.position, flBySide[1][op.legIndex])))
            own(cx.segment(facePointAt(leg, start, sEnd, flBySide[0][op.legIndex]),
                           facePointAt(leg, start, sEnd, flBySide[1][op.legIndex])))
        }
    }

    /**
     * Add an opening (door/window gap) of width [width] to whichever wall leg is nearest [at]. The
     * opening is positioned by a new parameter (distance along the leg from its start), so both its
     * position and width are editable and the wall regenerates around them. No-op if no wall leg is
     * within tolerance.
     */
    fun addOpeningAt(at: Vec2, width: ScalarRef, tol: Double): Boolean {
        val ev = Evaluator()
        var best: Wall? = null; var bestLeg = -1; var bestPos = 0.0; var bestD = Double.MAX_VALUE
        for (w in walls) {
            val threshold = tol + evalMm(w.thickness) / 2   // clicking anywhere on the wall body counts
            for (i in 0 until w.vertices.size - 1) {
                val a = (ev.eval(w.vertices[i].node) as? EvalResult.Ok)?.value as? PointValue ?: continue
                val b = (ev.eval(w.vertices[i + 1].node) as? EvalResult.Ok)?.value as? PointValue ?: continue
                val ab = b.p - a.p; val len = ab.length()
                if (len < Vec2.EPS) continue
                val t = ((at - a.p).dot(ab) / (len * len)).coerceIn(0.0, 1.0)
                val d = (at - (a.p + ab * t)).length()
                if (d <= threshold && d < bestD) { bestD = d; best = w; bestLeg = i; bestPos = t * len }
            }
        }
        val w = best ?: return false
        val widthVal = evalMm(width)
        val legLen = run {
            val a = (ev.eval(w.vertices[bestLeg].node) as EvalResult.Ok).value as PointValue
            val b = (ev.eval(w.vertices[bestLeg + 1].node) as EvalResult.Ok).value as PointValue
            (b.p - a.p).length()
        }
        val pos = (bestPos - widthVal / 2).coerceIn(0.0, maxOf(0.0, legLen - widthVal))   // centre on the click
        val posRef = newParameter("op", pos.mm).ref
        w.openings.add(Opening(bestLeg, posRef, width))
        regenerateWall(w)
        return true
    }
    fun ray(a: PointRef, b: PointRef) = add(cx.ray(a, b), ElementKind.RAY, Styles.CURVE)
    fun circle(center: PointRef, through: PointRef) = add(cx.circleCP(center, through), ElementKind.CIRCLE, Styles.CURVE)
    fun circleCR(center: PointRef, radius: ScalarRef) = add(cx.circleCR(center, radius), ElementKind.CIRCLE, Styles.CURVE)
    fun circle3(a: PointRef, b: PointRef, c: PointRef) = add(cx.circle3(a, b, c), ElementKind.CIRCLE, Styles.CURVE)
    fun arc3(a: PointRef, b: PointRef, c: PointRef) = add(cx.arc3(a, b, c), ElementKind.ARC, Styles.CURVE)
    fun arcCenterStartEnd(center: PointRef, start: PointRef, end: PointRef) = add(cx.arcCenterStartEnd(center, start, end), ElementKind.ARC, Styles.CURVE)

    // ---- relational constructions ----

    fun perpBisector(a: PointRef, b: PointRef) = add(cx.perpBisector(a, b), ElementKind.LINE, Styles.CONSTRUCT)
    fun angleBisector(a: PointRef, v: PointRef, b: PointRef) = add(cx.angleBisector(a, v, b), ElementKind.LINE, Styles.CONSTRUCT)
    fun perpendicularThrough(line: Element, p: PointRef) = add(cx.perpendicularThrough(carrierLine(line), p), ElementKind.LINE, Styles.CONSTRUCT)
    /** Tangent at a point-on-circle — the circle is inferred from the point's constraint. */
    fun tangentAtPointOnCircle(pointEl: Element) {
        val c = pointEl.constraint
        if (c is OnCircleConstraint) add(cx.tangentAtCircle(c.circle, pointEl.ref as PointRef), ElementKind.LINE, Styles.CONSTRUCT)
    }
    fun parallelThrough(line: Element, p: PointRef) = add(cx.parallelThrough(carrierLine(line), p), ElementKind.LINE, Styles.CONSTRUCT)

    /**
     * Fillet between two legs (lines/segments/rays). The corner is their intersection; the
     * quadrant is chosen by which side of the corner each leg was clicked ([clickA]/[clickB]).
     */
    fun filletBetweenLines(leg1: Element, leg2: Element, radius: ScalarRef, clickA: Vec2, clickB: Vec2): Element {
        val l1 = carrierLine(leg1); val l2 = carrierLine(leg2)
        val ev = Evaluator()
        val la = (ev.eval(l1.node) as? EvalResult.Ok)?.value as? LineValue
        val lb = (ev.eval(l2.node) as? EvalResult.Ok)?.value as? LineValue
        var sign1 = 1; var sign2 = 1
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
    fun commonTangents(c1: Element, c2: Element, inner: Boolean): List<Element> {
        val a = c1.ref as CircleRef; val b = c2.ref as CircleRef
        return listOf(+1, -1).map {
            add(if (inner) cx.innerTangent(a, b, it) else cx.outerTangent(a, b, it), ElementKind.LINE, Styles.CONSTRUCT)
        }
    }

    /** Concentric circle offset by [distance]; shrinks if [at] is inside the circle, else grows. */
    fun concentricCircle(circle: Element, distance: ScalarRef, at: Vec2): Element {
        val ref = circle.ref as CircleRef
        val c = (Evaluator().eval(ref.node) as? EvalResult.Ok)?.value as? CircleValue
        val sign = if (c != null && (at - c.circle.center).length() < c.circle.radius) -1 else 1
        return add(cx.concentricCircle(ref, distance, sign), ElementKind.CIRCLE, Styles.CURVE)
    }

    /** Parallel to [line] offset by [distance]; side chosen by which side of the line [at] is on. */
    fun parallelAtDistance(line: Element, distance: ScalarRef, at: Vec2): Element {
        val lineRef = carrierLine(line)
        val l = (Evaluator().eval(lineRef.node) as? EvalResult.Ok)?.value as? LineValue
        val sign = if (l != null && (at - l.line.origin).dot(l.line.dir.perp()) < 0) -1 else 1
        return add(cx.parallelAtDistance(lineRef, distance, sign), ElementKind.LINE, Styles.CONSTRUCT)
    }

    // ---- transforms (preserve source kind & style) ----

    @Suppress("UNCHECKED_CAST")
    fun mirror(geom: Element, axis: Element) = add(cx.mirror(geom.ref as Ref<Value>, axis.ref as LineRef), geom.kind, geom.style)

    @Suppress("UNCHECKED_CAST")
    fun rotate(geom: Element, center: PointRef, angle: ScalarRef) = add(cx.rotate(geom.ref as Ref<Value>, center, angle), geom.kind, geom.style)

    @Suppress("UNCHECKED_CAST")
    fun scale(geom: Element, center: PointRef, factor: ScalarRef) = add(cx.scaleGeom(geom.ref as Ref<Value>, center, factor), geom.kind, geom.style)

    @Suppress("UNCHECKED_CAST")
    fun translateByVector(geom: Element, from: PointRef, to: PointRef) = add(cx.translateByVector(geom.ref as Ref<Value>, from, to), geom.kind, geom.style)

    // ---- measurements ----

    fun measureDistance(a: PointRef, b: PointRef) = measurement("dist", cx.measureDistance(a, b))
    fun measureAngle(a: PointRef, v: PointRef, b: PointRef) = measurement("angle", cx.measureAngle(a, v, b))
    fun measureLength(seg: Element) = measurement("len", cx.measureLength(seg.ref as SegmentRef))
    fun measureRadius(circle: Element) = measurement("radius", cx.measureRadius(circle.ref as CircleRef))
    fun measureX(p: PointRef) = measurement("x", cx.measureX(p))
    fun measureY(p: PointRef) = measurement("y", cx.measureY(p))
    fun measureAngleLines(l1: Element, l2: Element) = measurement("angle", cx.measureAngleLines(carrierLine(l1), carrierLine(l2)))
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
