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
import constructit.dsl.PointRef
import constructit.dsl.ScalarRef
import constructit.geom.Arc
import constructit.geom.GeomMath
import constructit.geom.Segment
import constructit.geom.Vec2
import constructit.units.Dimension
import constructit.units.Quantity

/** Which technical-drawing dimension an annotation is. */
enum class DimensionKind { LINEAR, RADIAL, ANGULAR }

/** An arrowhead: its [tip], and the direction it points *along* (from the shaft toward the tip). */
class DimArrow(val tip: Vec2, val along: Vec2)

/**
 * The **world-space skeleton** of a dimension's graphic: extension/witness lines, the dimension line or
 * arc, where the arrowheads sit, and where the value text goes.
 *
 * World space, because the graphic follows the geometry it names; the *sizes that must not scale* —
 * arrowhead length, the gap that lifts the text off its line, the font — are screen pixels applied by
 * [SceneRenderer], which is the layer that knows about pixels at all.
 */
class DimensionGraphic(
    val lines: List<Segment>,
    val arrows: List<DimArrow>,
    /** Where the text's anchor sits, and the direction to lift it off the line along. */
    val textAt: Vec2,
    val textUp: Vec2,
    val text: String,
    val textAnchor: TextAnchor = TextAnchor.MIDDLE,
    /** The dimension arc of an angular dimension; null for the straight kinds. */
    val arc: Arc? = null,
)

/**
 * A **dimension**: a displayable annotation whose value is an ordinary measurement node (OP-4).
 *
 * The graphic shows that node's *current* value, so it recomputes with the geometry like everything else
 * — and it asserts nothing. A dimension never drives geometry; making a measured value drive is
 * parameter wiring, a separate and deliberately separate feature, because a quantity is driving XOR
 * driven (OP-4) and a dimension is the driven side.
 *
 * Its own degrees of freedom — where the dimension line, leader or arc is placed — belong to the
 * annotation, not to the drawing, and are a [Handle] like any other: draggable on canvas *and* typeable
 * in the inspector, writing the same source nodes either way (OP-13).
 */
sealed class DimensionAnnotation(
    val kind: DimensionKind,
    val measurement: ScalarRef,
) : Handle {
    /** The measured value as it is now, or null while the construction is invalid (OP-3). */
    fun value(ev: Evaluator): Quantity? = (ev.eval(measurement.node) as? EvalResult.Ok)?.let { (it.value as? ScalarValue)?.q }

    /** What the graphic reads: the live value with its unit, at the inspector's precision. */
    open fun label(ev: Evaluator): String = value(ev)?.let { Format.quantity(it) } ?: "—"

    /** The world-space graphic, or null when what it measures has no value (then nothing is drawn). */
    abstract fun graphic(ev: Evaluator): DimensionGraphic?

    /**
     * Where a grab holds on: the point this dimension's own DOF puts under the pointer. Held as the
     * grab's offset for the rest of the drag, so grabbing a dimension line never makes it jump.
     */
    abstract fun anchor(ev: Evaluator): Vec2?

    /**
     * A dimension's own degrees of freedom are always **anonymous** sources: its placement is state of the
     * annotation, never a named parameter (OP-7) the panel offers. Narrowing the handle's declaration to
     * that here is what lets [dofValues] read their literals directly.
     */
    abstract override val dragNodes: List<SourceNode>

    /**
     * The literals this annotation owns, in [dragNodes] order — its own state, restated on save so a
     * dragged dimension reloads where it now is (OP-18). The *clicks* that placed it stay verbatim,
     * since what they encode is a discrete choice (which side, which sector), not a value.
     */
    fun dofValues(): List<Quantity> = dragNodes.mapNotNull { (it.value as? ScalarValue)?.q }

    /** The read-only view of the measured value — a field with no node, because nothing can write it. */
    protected fun measuredField(
        label: String,
        dim: Dimension,
    ) = HandleField(label, null, dim, { ev -> value(ev) }, { }, writableWhen = { false })

    protected fun pointOf(
        ref: PointRef,
        ev: Evaluator,
    ): Vec2? = (ev.eval(ref.node) as? EvalResult.Ok)?.let { (it.value as? PointValue)?.p }

    /** The literal a DOF node holds, in base units (mm, rad) — never bound, so this *is* its value. */
    protected fun literal(node: SourceNode): Double = ((node.value as? ScalarValue)?.q?.base) ?: 0.0
}

/**
 * An **aligned linear dimension** between two points: extension lines out to a dimension line parallel to
 * the span, arrowheads at its ends, the measured distance centred on it.
 *
 * Its one DOF is the signed distance from the span to the dimension line, measured along the span's left
 * normal — so the sign *is* which side the dimension sits on, and a drag across the span flips it
 * continuously rather than through a stored choice.
 */
class LinearDimension(
    measurement: ScalarRef,
    private val a: PointRef,
    private val b: PointRef,
    val offset: SourceNode,
) : DimensionAnnotation(DimensionKind.LINEAR, measurement) {
    override val dragNodes: List<SourceNode> get() = listOf(offset)

    /** The span, or null while it is degenerate — a zero-length span has no direction to align to. */
    private fun span(ev: Evaluator): Pair<Vec2, Vec2>? {
        val pa = pointOf(a, ev) ?: return null
        val pb = pointOf(b, ev) ?: return null
        return if ((pb - pa).length() < Vec2.EPS) null else pa to pb
    }

    override fun drag(
        world: Vec2,
        ev: Evaluator,
    ) {
        val (pa, pb) = span(ev) ?: return
        offset.value = ScalarValue(Quantity.mm((world - pa).dot((pb - pa).normalized().perp())))
    }

    override fun fields(): List<HandleField> = listOf(coordField("offset", offset), measuredField("distance", Dimension.LENGTH))

    override fun anchor(ev: Evaluator): Vec2? {
        val (pa, pb) = span(ev) ?: return null
        return (pa + pb) * 0.5 + (pb - pa).normalized().perp() * literal(offset)
    }

    override fun graphic(ev: Evaluator): DimensionGraphic? {
        val (pa, pb) = span(ev) ?: return null
        return graphicOf(pa, pb, literal(offset), label(ev))
    }

    companion object {
        /**
         * The graphic of a linear dimension **as values** — the span, the offset and the text.
         *
         * Split out so the *preview* draws the very graphic the placing click will leave (see
         * [Previews.linearDimension]): the annotation is nodes and the preview must own none, and one
         * implementation is the only way the two cannot drift apart.
         */
        fun graphicOf(
            pa: Vec2,
            pb: Vec2,
            offset: Double,
            text: String,
        ): DimensionGraphic {
            val dir = (pb - pa).normalized()
            val n = dir.perp()
            val ea = pa + n * offset
            val eb = pb + n * offset
            return DimensionGraphic(
                lines = listOf(Segment(pa, ea), Segment(pb, eb), Segment(ea, eb)),
                arrows = listOf(DimArrow(ea, -dir), DimArrow(eb, dir)),
                textAt = (ea + eb) * 0.5,
                textUp = n,
                text = text,
            )
        }
    }
}

/**
 * A **radial dimension** on a circle (an arc reaches this through its carrier circle): a leader from the
 * circle outward, with the radius beside it.
 *
 * Two DOF, both the annotation's own: where the leader leaves the circle, and how far past it the text
 * sits. Negative reach puts the text inside the circle, which is a legitimate placement for a big circle,
 * so it is not clamped.
 */
class RadialDimension(
    measurement: ScalarRef,
    private val circle: CircleRef,
    val leaderAngle: SourceNode,
    val leaderReach: SourceNode,
) : DimensionAnnotation(DimensionKind.RADIAL, measurement) {
    override val dragNodes: List<SourceNode> get() = listOf(leaderAngle, leaderReach)

    /** "R" is the drawing's own word for a radius, and the sign a reader looks for first. */
    override fun label(ev: Evaluator): String = value(ev)?.let { "R " + Format.quantity(it) } ?: "R —"

    private fun circleOf(ev: Evaluator): constructit.geom.Circle? =
        (ev.eval(circle.node) as? EvalResult.Ok)?.let { (it.value as? CircleValue)?.circle }

    private fun direction(): Vec2 {
        val a = literal(leaderAngle)
        return Vec2(kotlin.math.cos(a), kotlin.math.sin(a))
    }

    override fun drag(
        world: Vec2,
        ev: Evaluator,
    ) {
        val c = circleOf(ev) ?: return
        val d = world - c.center
        if (d.length() < Vec2.EPS) return
        leaderAngle.value = ScalarValue(Quantity.rad(d.angle()))
        leaderReach.value = ScalarValue(Quantity.mm(d.length() - c.radius))
    }

    override fun fields(): List<HandleField> =
        listOf(
            angleField("leader angle", leaderAngle),
            coordField("leader reach", leaderReach),
            measuredField("radius", Dimension.LENGTH),
        )

    override fun anchor(ev: Evaluator): Vec2? {
        val c = circleOf(ev) ?: return null
        return c.center + direction() * (c.radius + literal(leaderReach))
    }

    override fun graphic(ev: Evaluator): DimensionGraphic? {
        val c = circleOf(ev) ?: return null
        return graphicOf(c, literal(leaderAngle), literal(leaderReach), label(ev))
    }

    companion object {
        /** The graphic of a radial dimension as values — see [LinearDimension.graphicOf] for why. */
        fun graphicOf(
            c: constructit.geom.Circle,
            leaderAngle: Double,
            leaderReach: Double,
            text: String,
        ): DimensionGraphic {
            val u = Vec2(kotlin.math.cos(leaderAngle), kotlin.math.sin(leaderAngle))
            val on = c.center + u * c.radius
            val end = c.center + u * (c.radius + leaderReach)
            return DimensionGraphic(
                lines = listOf(Segment(on, end)),
                arrows = listOf(DimArrow(on, -u)),
                textAt = end,
                textUp = u,
                text = text,
                // the text runs away from the circle, so the leader never crosses it
                textAnchor = if (u.x >= 0.0) TextAnchor.START else TextAnchor.END,
            )
        }
    }
}

/**
 * An **angular dimension** between two lines: an arc across one sector of the crossing, with that
 * sector's opening beside it.
 *
 * *Which* sector is a stored discrete choice ([sign1]/[sign2] — OP-1), fixed by the click that placed the
 * dimension and never re-derived: the same signs feed the measurement node, so the number shown and the
 * arc drawn are always the same sector. The one DOF is the arc's radius from the vertex.
 */
class AngularDimension(
    measurement: ScalarRef,
    private val l1: LineRef,
    private val l2: LineRef,
    val sign1: Int,
    val sign2: Int,
    val radius: SourceNode,
) : DimensionAnnotation(DimensionKind.ANGULAR, measurement) {
    override val dragNodes: List<SourceNode> get() = listOf(radius)

    /** Vertex plus the two chosen leg directions, or null while the lines are parallel (no crossing). */
    private fun sector(ev: Evaluator): Triple<Vec2, Vec2, Vec2>? {
        val a = (ev.eval(l1.node) as? EvalResult.Ok)?.let { (it.value as? LineValue)?.line } ?: return null
        val b = (ev.eval(l2.node) as? EvalResult.Ok)?.let { (it.value as? LineValue)?.line } ?: return null
        val v = GeomMath.intersectLL(a, b).points.firstOrNull() ?: return null
        return Triple(v, a.dir * sign1.toDouble(), b.dir * sign2.toDouble())
    }

    override fun drag(
        world: Vec2,
        ev: Evaluator,
    ) {
        val (v, _, _) = sector(ev) ?: return
        radius.value = ScalarValue(Quantity.mm((world - v).length()))
    }

    override fun fields(): List<HandleField> = listOf(coordField("arc radius", radius), measuredField("angle", Dimension.ANGLE))

    override fun anchor(ev: Evaluator): Vec2? {
        val (v, d1, d2) = sector(ev) ?: return null
        return v + bisectorOf(d1, d2) * literal(radius)
    }

    override fun graphic(ev: Evaluator): DimensionGraphic? {
        val (v, d1, d2) = sector(ev) ?: return null
        return graphicOf(v, d1, d2, literal(radius), label(ev))
    }

    companion object {
        /** The direction the text sits along: into the sector, so it reads inside the arc it belongs to. */
        private fun bisectorOf(
            d1: Vec2,
            d2: Vec2,
        ): Vec2 {
            val sum = d1 + d2
            // opposite directions: the sector is a straight angle and has no bisector of its own
            return if (sum.length() < Vec2.EPS) d1.perp() else sum.normalized()
        }

        /** The graphic of an angular dimension as values — see [LinearDimension.graphicOf] for why. */
        fun graphicOf(
            v: Vec2,
            d1: Vec2,
            d2: Vec2,
            r: Double,
            text: String,
        ): DimensionGraphic {
            val ccw = d1.cross(d2) >= 0.0
            val start = v + d1 * r
            val end = v + d2 * r
            val sweep = if (ccw) 1.0 else -1.0
            return DimensionGraphic(
                // the legs are extended out to the arc, so the sector being named is unambiguous
                lines = listOf(Segment(v, start), Segment(v, end)),
                arrows = listOf(DimArrow(start, d1.perp() * -sweep), DimArrow(end, d2.perp() * sweep)),
                textAt = v + bisectorOf(d1, d2) * r,
                textUp = bisectorOf(d1, d2),
                text = text,
                arc = Arc(v, r, d1.angle(), d2.angle(), ccw),
            )
        }

        /**
         * The signs naming the sector of the [d1]/[d2] crossing that contains [toward] — the click that
         * placed the dimension, resolved once into the discrete choice that is then stored (OP-1).
         *
         * Resolved in the crossing's own (non-orthogonal) basis: `toward = a*d1 + b*d2`, and the signs of
         * `a` and `b` are the sector. Testing the dot products instead is wrong whenever the lines are not
         * perpendicular, which is the interesting case.
         */
        fun signsToward(
            d1: Vec2,
            d2: Vec2,
            toward: Vec2,
        ): Pair<Int, Int> {
            val det = d1.cross(d2)
            if (kotlin.math.abs(det) < Vec2.EPS) return 1 to 1
            val a = toward.cross(d2) / det
            val b = d1.cross(toward) / det
            return (if (a >= 0.0) 1 else -1) to (if (b >= 0.0) 1 else -1)
        }
    }
}
