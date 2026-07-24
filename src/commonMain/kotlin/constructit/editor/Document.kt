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

enum class ElementKind { POINT, DERIVED_POINT, ON_CURVE, LINE, RAY, CIRCLE, SEGMENT, ARC }

/** A retained, displayable/selectable graph output with style + kind. */
class Element(
    val id: String,
    val ref: Ref<*>,
    val kind: ElementKind,
    var style: Style,
    var visible: Boolean = true,
    /** For [ElementKind.ON_CURVE]: how a drag updates the hidden position parameter. */
    val constraint: PointConstraint? = null,
) {
    val draggable: Boolean get() = kind == ElementKind.POINT || kind == ElementKind.ON_CURVE
    val isCurve: Boolean get() = kind == ElementKind.LINE || kind == ElementKind.CIRCLE || kind == ElementKind.SEGMENT || kind == ElementKind.RAY || kind == ElementKind.ARC
    val isPoint: Boolean get() = kind == ElementKind.POINT || kind == ElementKind.DERIVED_POINT || kind == ElementKind.ON_CURVE
}

/** A named scalar: an editable parameter (OP-7) or a read-only measurement (OP-4). */
class ScalarEntry(val id: String, var name: String, val ref: ScalarRef, val editable: Boolean)

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

    // ---- free points & scalars ----

    fun freePoint(x: Quantity, y: Quantity): PointRef =
        cx.freePoint("P${counter + 1}", x, y).also { add(it, ElementKind.POINT, Styles.FREE_POINT) }

    fun newParameter(name: String, value: Quantity): ScalarEntry {
        val node = ParameterNode(nextId("pn"), ScalarValue(value))
        val e = ScalarEntry(nextId("s"), name, Ref<ScalarValue>(node), editable = true)
        scalars.add(e); return e
    }

    private fun measurement(name: String, ref: ScalarRef): ScalarEntry {
        val e = ScalarEntry(nextId("m"), name, ref, editable = false)
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

    fun remove(el: Element) { elements.remove(el) }

    // ---- points ----

    fun midpoint(a: PointRef, b: PointRef) = addDerived(cx.midpoint(a, b))
    fun projectToLine(p: PointRef, line: Element) = addDerived(cx.projectToLine(p, line.ref as LineRef))

    private fun addConstrained(ref: PointRef, constraint: PointConstraint): PointRef {
        elements.add(Element(nextId("e"), ref, ElementKind.ON_CURVE, Styles.ON_CURVE, constraint = constraint))
        return ref
    }

    /** Point that slides along a line; created at the projection of [at], draggable along the line. */
    fun pointOnLine(line: Element, at: Vec2): PointRef {
        val lineRef = line.ref as LineRef
        val l = (Evaluator().eval(lineRef.node) as? EvalResult.Ok)?.value as? LineValue
        val t0 = if (l != null) (at - l.line.origin).dot(l.line.dir) else 0.0
        val tNode = SourceNode(nextId("t"), ScalarValue(Quantity.mm(t0)))
        return addConstrained(cx.pointOnLineAt(lineRef, Ref<ScalarValue>(tNode)), OnLineConstraint(lineRef, tNode))
    }

    /** Fully-determined point on a line at [distance] from [from]; direction from the click side of [at]. */
    fun pointAlongLine(line: Element, from: PointRef, distance: ScalarRef, at: Vec2): PointRef {
        val lineRef = line.ref as LineRef
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

    /** Intersect two curves; branch count follows the pair type (line-line: 1, else: 2). */
    fun intersect(a: Element, b: Element): List<PointRef> {
        val lineLine = a.kind == ElementKind.LINE && b.kind == ElementKind.LINE
        @Suppress("UNCHECKED_CAST")
        val set: PointSetRef = when {
            lineLine -> cx.intersectLL(a.ref as LineRef, b.ref as LineRef)
            a.kind == ElementKind.CIRCLE && b.kind == ElementKind.CIRCLE -> cx.intersectCC(a.ref as CircleRef, b.ref as CircleRef)
            a.kind == ElementKind.LINE && b.kind == ElementKind.CIRCLE -> cx.intersectLC(a.ref as LineRef, b.ref as CircleRef)
            a.kind == ElementKind.CIRCLE && b.kind == ElementKind.LINE -> cx.intersectLC(b.ref as LineRef, a.ref as CircleRef)
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
    fun ray(a: PointRef, b: PointRef) = add(cx.ray(a, b), ElementKind.RAY, Styles.CURVE)
    fun circle(center: PointRef, through: PointRef) = add(cx.circleCP(center, through), ElementKind.CIRCLE, Styles.CURVE)
    fun circleCR(center: PointRef, radius: ScalarRef) = add(cx.circleCR(center, radius), ElementKind.CIRCLE, Styles.CURVE)
    fun circle3(a: PointRef, b: PointRef, c: PointRef) = add(cx.circle3(a, b, c), ElementKind.CIRCLE, Styles.CURVE)
    fun arc3(a: PointRef, b: PointRef, c: PointRef) = add(cx.arc3(a, b, c), ElementKind.ARC, Styles.CURVE)

    // ---- relational constructions ----

    fun perpBisector(a: PointRef, b: PointRef) = add(cx.perpBisector(a, b), ElementKind.LINE, Styles.CONSTRUCT)
    fun angleBisector(a: PointRef, v: PointRef, b: PointRef) = add(cx.angleBisector(a, v, b), ElementKind.LINE, Styles.CONSTRUCT)
    fun perpendicularThrough(line: Element, p: PointRef) = add(cx.perpendicularThrough(line.ref as LineRef, p), ElementKind.LINE, Styles.CONSTRUCT)
    /** Tangent at a point-on-circle — the circle is inferred from the point's constraint. */
    fun tangentAtPointOnCircle(pointEl: Element) {
        val c = pointEl.constraint
        if (c is OnCircleConstraint) add(cx.tangentAtCircle(c.circle, pointEl.ref as PointRef), ElementKind.LINE, Styles.CONSTRUCT)
    }
    fun parallelThrough(line: Element, p: PointRef) = add(cx.parallelThrough(line.ref as LineRef, p), ElementKind.LINE, Styles.CONSTRUCT)

    // ---- transforms (preserve source kind & style) ----

    @Suppress("UNCHECKED_CAST")
    fun mirror(geom: Element, axis: Element) = add(cx.mirror(geom.ref as Ref<Value>, axis.ref as LineRef), geom.kind, geom.style)

    @Suppress("UNCHECKED_CAST")
    fun rotate(geom: Element, center: PointRef, angle: ScalarRef) = add(cx.rotate(geom.ref as Ref<Value>, center, angle), geom.kind, geom.style)

    @Suppress("UNCHECKED_CAST")
    fun scale(geom: Element, center: PointRef, factor: ScalarRef) = add(cx.scaleGeom(geom.ref as Ref<Value>, center, factor), geom.kind, geom.style)

    // ---- measurements ----

    fun measureDistance(a: PointRef, b: PointRef) = measurement("dist", cx.measureDistance(a, b))
    fun measureAngle(a: PointRef, v: PointRef, b: PointRef) = measurement("angle", cx.measureAngle(a, v, b))
    fun measureLength(seg: Element) = measurement("len", cx.measureLength(seg.ref as SegmentRef))
    fun measureRadius(circle: Element) = measurement("radius", cx.measureRadius(circle.ref as CircleRef))
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
}
