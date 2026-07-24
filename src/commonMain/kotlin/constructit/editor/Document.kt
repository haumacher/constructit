package constructit.editor

import constructit.core.PointValue
import constructit.core.SourceNode
import constructit.dsl.CircleRef
import constructit.dsl.Construction
import constructit.dsl.LineRef
import constructit.dsl.PointRef
import constructit.dsl.PointSetRef
import constructit.dsl.Ref
import constructit.geom.Vec2
import constructit.units.Quantity

enum class ElementKind { POINT, DERIVED_POINT, LINE, CIRCLE, SEGMENT, ARC }

/** A retained, displayable/selectable graph output with style + kind (the editor's unit of work). */
class Element(
    val id: String,
    val ref: Ref<*>,
    val kind: ElementKind,
    var style: Style,
    var visible: Boolean = true,
) {
    val draggable: Boolean get() = kind == ElementKind.POINT
}

/**
 * A retained construction document: owns the [Construction] DAG plus display metadata, and
 * exposes enumeration (for rendering/hit-testing) and mutation (for tools). This is the
 * backbone the editor and, later, the file format hang off of.
 */
class Document {
    val cx = Construction()
    val elements = ArrayList<Element>()
    private var counter = 0
    private fun nextId(prefix: String) = "$prefix${++counter}"

    val freePoints: List<Element> get() = elements.filter { it.kind == ElementKind.POINT }

    private fun add(ref: Ref<*>, kind: ElementKind, style: Style): Element {
        val el = Element(nextId("e"), ref, kind, style)
        elements.add(el)
        return el
    }

    fun freePoint(x: Quantity, y: Quantity): PointRef {
        val ref = cx.freePoint("P${counter + 1}", x, y)
        add(ref, ElementKind.POINT, Styles.FREE_POINT)
        return ref
    }

    fun line(a: PointRef, b: PointRef): LineRef =
        cx.lineThrough(a, b).also { add(it, ElementKind.LINE, Styles.CURVE) }

    fun circle(center: PointRef, through: PointRef): CircleRef =
        cx.circleCP(center, through).also { add(it, ElementKind.CIRCLE, Styles.CURVE) }

    fun segment(a: PointRef, b: PointRef) =
        cx.segment(a, b).also { add(it, ElementKind.SEGMENT, Styles.CURVE) }

    /**
     * Intersect two curve elements, adding one derived point per solution *branch*.
     * Branch count is a property of the pair type: line∩line has a single solution;
     * line∩circle and circle∩circle have two. (This avoids two coincident points for lines.)
     */
    fun intersect(a: Element, b: Element): List<PointRef> {
        val lineLine = a.kind == ElementKind.LINE && b.kind == ElementKind.LINE
        @Suppress("UNCHECKED_CAST")
        val set: PointSetRef = when {
            lineLine ->
                cx.intersectLL(a.ref as LineRef, b.ref as LineRef)
            a.kind == ElementKind.CIRCLE && b.kind == ElementKind.CIRCLE ->
                cx.intersectCC(a.ref as CircleRef, b.ref as CircleRef)
            a.kind == ElementKind.LINE && b.kind == ElementKind.CIRCLE ->
                cx.intersectLC(a.ref as LineRef, b.ref as CircleRef)
            a.kind == ElementKind.CIRCLE && b.kind == ElementKind.LINE ->
                cx.intersectLC(b.ref as LineRef, a.ref as CircleRef)
            else -> return emptyList()
        }
        val refs = ArrayList<PointRef>()
        refs.add(cx.select(set, +1))
        if (!lineLine) refs.add(cx.select(set, -1))
        refs.forEach { add(it, ElementKind.DERIVED_POINT, Styles.DERIVED_POINT) }
        return refs
    }

    /** Move a free point (mutates its source node) for a parametric recompute. */
    fun moveFreePoint(el: Element, world: Vec2) {
        require(el.kind == ElementKind.POINT) { "not a free point" }
        (el.ref.node as SourceNode).value = PointValue(world)
    }

    fun remove(el: Element) { elements.remove(el) }
}

/** Default element styles. */
object Styles {
    val FREE_POINT = Style(stroke = "#1f77b4", width = 1.0)
    val DERIVED_POINT = Style(stroke = "#2ca02c", width = 1.0)
    val CURVE = Style(stroke = "#333333", width = 1.5)
    val INVALID = Style(stroke = "#dddddd", width = 1.0)
    val PREVIEW = Style(stroke = "#ff7f0e", width = 1.0)
}
