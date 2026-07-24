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

    /** Un-weld: [alias] resumes as an independent free point at its current (master's) position. */
    fun unweld(alias: Element) {
        val node = alias.ref.node as? SourceNode ?: return
        val cur = (Evaluator().eval(node) as? EvalResult.Ok)?.let { (it.value as? PointValue)?.p }
        node.boundTo = null
        if (cur != null) node.value = PointValue(cur)
        alias.visible = true
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
}
