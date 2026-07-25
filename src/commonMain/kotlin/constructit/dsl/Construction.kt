package constructit.dsl

import constructit.core.ArcValue
import constructit.core.CircleValue
import constructit.core.DirectionValue
import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.LineValue
import constructit.core.LoopValue
import constructit.core.Node
import constructit.core.OpNode
import constructit.core.PointSetValue
import constructit.core.PointValue
import constructit.core.ProfileValue
import constructit.core.RayValue
import constructit.core.RegionValue
import constructit.core.ScalarValue
import constructit.core.SegmentValue
import constructit.core.SourceNode
import constructit.core.Value
import constructit.core.transformValue
import constructit.geom.Affine
import constructit.geom.Arc
import constructit.geom.Circle
import constructit.geom.Direction
import constructit.geom.GeomMath
import constructit.geom.Line
import constructit.geom.Loop
import constructit.geom.Profile
import constructit.geom.ProfileElement
import constructit.geom.Ray
import constructit.geom.Region
import constructit.geom.Segment
import constructit.geom.Vec2
import constructit.units.Dimension
import constructit.units.DimensionError
import constructit.units.Quantity
import kotlin.math.abs
import kotlin.math.pow

/** A typed handle to a node's output. Compile-time typing over the generic graph (OP-5). */
class Ref<out V : Value>(val node: Node)

typealias ScalarRef = Ref<ScalarValue>
typealias PointRef = Ref<PointValue>
typealias LineRef = Ref<LineValue>
typealias SegmentRef = Ref<SegmentValue>
typealias CircleRef = Ref<CircleValue>
typealias ArcRef = Ref<ArcValue>
typealias PointSetRef = Ref<PointSetValue>
typealias RayRef = Ref<RayValue>
typealias DirectionRef = Ref<DirectionValue>
typealias ProfileRef = Ref<ProfileValue>
typealias LoopRef = Ref<LoopValue>
typealias RegionRef = Ref<RegionValue>

/**
 * Builder for a construction DAG. Generates stable ids; supports macro instantiation with
 * derived path-ids `M/nk` (OP-6). Source nodes (parameters, free points) are mutable so a
 * parameter edit + a fresh [Evaluator] pass re-propagates through the graph.
 */
class Construction {
    private data class Scope(val prefix: String, var counter: Int)

    private val scopes = ArrayDeque<Scope>().apply { addLast(Scope("", 0)) }

    private fun freshId(hint: String? = null): String {
        val scope = scopes.last()
        val local = hint ?: "n${++scope.counter}"
        val path = scopes.filter { it.prefix.isNotEmpty() }.joinToString("/") { it.prefix }
        return if (path.isEmpty()) local else "$path/$local"
    }

    /** Run [body] inside a macro instance namespace, so its nodes get ids `instanceId/nk` (OP-6). */
    fun <R> withInstance(
        instanceId: String,
        body: () -> R,
    ): R {
        scopes.addLast(Scope(instanceId, 0))
        try {
            return body()
        } finally {
            scopes.removeLast()
        }
    }

    private fun <V : Value> op(
        vararg inputs: Ref<*>,
        fn: (List<Value>) -> EvalResult,
    ): Ref<V> {
        val node = OpNode(freshId(), inputs.map { it.node }, fn)
        return Ref(node)
    }

    // ---- source nodes (adjustable parameters, constants, free points) ----

    /** An adjustable or constant parameter (OP-7). [constant] is a presentation flag (OP-6). */
    @Suppress("UNUSED_PARAMETER")
    fun parameter(
        name: String,
        value: Quantity,
        constant: Boolean = false,
    ): ScalarRef =
        Ref(SourceNode(freshId(name), ScalarValue(value)))

    fun const(value: Quantity): ScalarRef = Ref(SourceNode(freshId(), ScalarValue(value)))

    fun freePoint(
        name: String,
        x: Quantity,
        y: Quantity,
    ): PointRef =
        Ref(SourceNode(freshId(name), PointValue(Vec2(x.mm, y.mm))))

    /** Mutate a source scalar for a parametric recompute. */
    fun set(
        ref: ScalarRef,
        value: Quantity,
    ) {
        (ref.node as SourceNode).value = ScalarValue(value)
    }

    /** Mutate a source point for a parametric recompute. */
    fun set(
        ref: PointRef,
        x: Quantity,
        y: Quantity,
    ) {
        (ref.node as SourceNode).value = PointValue(Vec2(x.mm, y.mm))
    }

    // ---- scalar arithmetic (graph-level expressions; OP-7 string parser deferred) ----

    fun scale(
        a: ScalarRef,
        factor: Double,
    ): ScalarRef =
        op(a) { EvalResult.Ok(ScalarValue((it[0] as ScalarValue).q * factor)) }

    fun add(
        a: ScalarRef,
        b: ScalarRef,
    ): ScalarRef =
        op(a, b) { EvalResult.Ok(ScalarValue((it[0] as ScalarValue).q + (it[1] as ScalarValue).q)) }

    fun sub(
        a: ScalarRef,
        b: ScalarRef,
    ): ScalarRef =
        op(a, b) { EvalResult.Ok(ScalarValue((it[0] as ScalarValue).q - (it[1] as ScalarValue).q)) }

    fun neg(a: ScalarRef): ScalarRef =
        op(a) { EvalResult.Ok(ScalarValue(-(it[0] as ScalarValue).q)) }

    // ---- point construction ----

    fun pointXY(
        x: ScalarRef,
        y: ScalarRef,
    ): PointRef =
        op(x, y) {
            val px = (it[0] as ScalarValue).q.mm
            val py = (it[1] as ScalarValue).q.mm
            EvalResult.Ok(PointValue(Vec2(px, py)))
        }

    fun translate(
        p: PointRef,
        dx: ScalarRef,
        dy: ScalarRef,
    ): PointRef =
        op(p, dx, dy) {
            val base = (it[0] as PointValue).p
            EvalResult.Ok(PointValue(base + Vec2((it[1] as ScalarValue).q.mm, (it[2] as ScalarValue).q.mm)))
        }

    fun polarPoint(
        center: PointRef,
        radius: ScalarRef,
        angle: ScalarRef,
    ): PointRef =
        op(center, radius, angle) {
            val c = (it[0] as PointValue).p
            val r = (it[1] as ScalarValue).q.mm
            val a = (it[2] as ScalarValue).q.requireDim(Dimension.ANGLE, "angle").base
            EvalResult.Ok(PointValue(c + Vec2(r * kotlin.math.cos(a), r * kotlin.math.sin(a))))
        }

    fun midpoint(
        a: PointRef,
        b: PointRef,
    ): PointRef =
        op(a, b) {
            EvalResult.Ok(PointValue(((it[0] as PointValue).p + (it[1] as PointValue).p) * 0.5))
        }

    // ---- curves ----

    fun lineThrough(
        a: PointRef,
        b: PointRef,
    ): LineRef =
        op(a, b) {
            val pa = (it[0] as PointValue).p
            val pb = (it[1] as PointValue).p
            if ((pb - pa).length() < Vec2.EPS) {
                EvalResult.Invalid("line through coincident points")
            } else {
                EvalResult.Ok(LineValue(Line(pa, (pb - pa).normalized())))
            }
        }

    fun segment(
        a: PointRef,
        b: PointRef,
    ): SegmentRef =
        op(a, b) { EvalResult.Ok(SegmentValue(Segment((it[0] as PointValue).p, (it[1] as PointValue).p))) }

    fun circleCR(
        center: PointRef,
        radius: ScalarRef,
    ): CircleRef =
        op(center, radius) {
            val r = (it[1] as ScalarValue).q.mm
            if (r <= 0.0) {
                EvalResult.Invalid("non-positive radius")
            } else {
                EvalResult.Ok(CircleValue(Circle((it[0] as PointValue).p, r)))
            }
        }

    fun arc(
        center: PointRef,
        radius: ScalarRef,
        startAngle: ScalarRef,
        endAngle: ScalarRef,
        ccw: Boolean = true,
    ): ArcRef =
        op(center, radius, startAngle, endAngle) {
            val r = (it[1] as ScalarValue).q.mm
            val a0 = (it[2] as ScalarValue).q.requireDim(Dimension.ANGLE, "startAngle").base
            val a1 = (it[3] as ScalarValue).q.requireDim(Dimension.ANGLE, "endAngle").base
            if (r <= 0.0) {
                EvalResult.Invalid("non-positive radius")
            } else {
                EvalResult.Ok(ArcValue(Arc((it[0] as PointValue).p, r, a0, a1, ccw)))
            }
        }

    // ---- intersections & selection (OP-1) ----

    fun intersectCC(
        c1: CircleRef,
        c2: CircleRef,
    ): PointSetRef =
        op(c1, c2) { EvalResult.Ok(PointSetValue(GeomMath.intersectCC((it[0] as CircleValue).circle, (it[1] as CircleValue).circle))) }

    fun intersectLL(
        l1: LineRef,
        l2: LineRef,
    ): PointSetRef =
        op(l1, l2) { EvalResult.Ok(PointSetValue(GeomMath.intersectLL((it[0] as LineValue).line, (it[1] as LineValue).line))) }

    fun intersectLC(
        line: LineRef,
        c: CircleRef,
    ): PointSetRef =
        op(line, c) { EvalResult.Ok(PointSetValue(GeomMath.intersectLC((it[0] as LineValue).line, (it[1] as CircleValue).circle))) }

    /** Pick a branch from a solution set. sign >= 0 -> first (left), sign < 0 -> last (right). */
    fun select(
        set: PointSetRef,
        sign: Int,
    ): PointRef =
        op(set) {
            val pts = (it[0] as PointSetValue).set.points
            when {
                pts.isEmpty() -> EvalResult.Invalid("empty intersection")
                sign >= 0 -> EvalResult.Ok(PointValue(pts.first()))
                else -> EvalResult.Ok(PointValue(pts.last()))
            }
        }

    // ---- measurements (OP-4) ----

    fun measureDistance(
        a: PointRef,
        b: PointRef,
    ): ScalarRef =
        op(a, b) { EvalResult.Ok(ScalarValue(Quantity.mm(((it[1] as PointValue).p - (it[0] as PointValue).p).length()))) }

    private fun pt(v: Value) = (v as PointValue).p

    private fun ln(v: Value) = (v as LineValue).line

    private fun cir(v: Value) = (v as CircleValue).circle

    private fun sc(v: Value) = (v as ScalarValue).q

    // ================= Tier 1: relational construction =================

    /** Line through [point] perpendicular to [line]. */
    fun perpendicularThrough(
        line: LineRef,
        point: PointRef,
    ): LineRef =
        op(line, point) { EvalResult.Ok(LineValue(Line(pt(it[1]), ln(it[0]).dir.perp()))) }

    /** Line through [point] parallel to [line]. */
    fun parallelThrough(
        line: LineRef,
        point: PointRef,
    ): LineRef =
        op(line, point) { EvalResult.Ok(LineValue(Line(pt(it[1]), ln(it[0]).dir))) }

    /** Perpendicular bisector of two points (direct construction). */
    fun perpBisector(
        a: PointRef,
        b: PointRef,
    ): LineRef =
        op(a, b) {
            val pa = pt(it[0])
            val pb = pt(it[1])
            if ((pb - pa).length() < Vec2.EPS) {
                EvalResult.Invalid("bisector of coincident points")
            } else {
                EvalResult.Ok(LineValue(Line((pa + pb) * 0.5, (pb - pa).perp().normalized())))
            }
        }

    /** Line parallel to [line], offset by [distance] along its normal; [sign] (+1/-1) picks the side. */
    fun parallelAtDistance(
        line: LineRef,
        distance: ScalarRef,
        sign: Int,
    ): LineRef =
        op(line, distance) {
            val l = ln(it[0])
            val d = sc(it[1]).mm
            EvalResult.Ok(LineValue(Line(l.origin + l.dir.perp() * (sign * d), l.dir)))
        }

    /** Internal angle bisector at [vertex] of the angle opening toward [a] and [b]. */
    fun angleBisector(
        a: PointRef,
        vertex: PointRef,
        b: PointRef,
    ): LineRef =
        op(a, vertex, b) {
            val v = pt(it[1])
            val ua = (pt(it[0]) - v).normalized()
            val ub = (pt(it[2]) - v).normalized()
            val bis = ua + ub
            if (bis.length() < Vec2.EPS) {
                EvalResult.Invalid("degenerate angle bisector (straight/opposite)")
            } else {
                EvalResult.Ok(LineValue(Line(v, bis.normalized())))
            }
        }

    /** Foot of the perpendicular from [point] onto [line]. */
    fun projectToLine(
        point: PointRef,
        line: LineRef,
    ): PointRef =
        op(point, line) {
            val l = ln(it[1])
            val p = pt(it[0])
            EvalResult.Ok(PointValue(l.origin + l.dir * (p - l.origin).dot(l.dir)))
        }

    /** Point at signed [distance] (a length) along [line] from its origin. */
    fun pointOnLineAt(
        line: LineRef,
        distance: ScalarRef,
    ): PointRef =
        op(line, distance) { EvalResult.Ok(PointValue(ln(it[0]).origin + ln(it[0]).dir * sc(it[1]).mm)) }

    /**
     * Fully-determined point on [line] at [distance] from the projection of [from], in the
     * direction sign*line.dir. [sign] (+1/-1) is captured at creation (OP-1-style branch).
     */
    fun pointAlongLine(
        line: LineRef,
        from: PointRef,
        distance: ScalarRef,
        sign: Int,
    ): PointRef =
        op(line, from, distance) {
            val l = ln(it[0])
            val p = pt(it[1])
            val d = sc(it[2]).mm
            val proj = l.origin + l.dir * (p - l.origin).dot(l.dir)
            EvalResult.Ok(PointValue(proj + l.dir * (sign * d)))
        }

    /** Point on [circle] at the given [angle]. */
    fun pointOnCircle(
        circle: CircleRef,
        angle: ScalarRef,
    ): PointRef =
        op(circle, angle) {
            val c = cir(it[0])
            val a = sc(it[1]).requireDim(Dimension.ANGLE, "angle").base
            EvalResult.Ok(PointValue(c.center + Vec2(c.radius * kotlin.math.cos(a), c.radius * kotlin.math.sin(a))))
        }

    /** Circle by centre and a point it passes through (compass). */
    fun circleCP(
        center: PointRef,
        through: PointRef,
    ): CircleRef =
        op(center, through) {
            val r = (pt(it[1]) - pt(it[0])).length()
            if (r < Vec2.EPS) EvalResult.Invalid("zero-radius circle") else EvalResult.Ok(CircleValue(Circle(pt(it[0]), r)))
        }

    // ================= Tier 1: general transforms (any geometry) =================

    fun <V : Value> mirror(
        g: Ref<V>,
        axis: LineRef,
    ): Ref<V> =
        op(g, axis) { EvalResult.Ok(transformValue(Affine.reflection(ln(it[1])), it[0])) }

    fun <V : Value> rotate(
        g: Ref<V>,
        center: PointRef,
        angle: ScalarRef,
    ): Ref<V> =
        op(g, center, angle) {
            EvalResult.Ok(transformValue(Affine.rotation(pt(it[1]), sc(it[2]).requireDim(Dimension.ANGLE, "angle").base), it[0]))
        }

    fun <V : Value> scaleGeom(
        g: Ref<V>,
        center: PointRef,
        factor: ScalarRef,
    ): Ref<V> =
        op(g, center, factor) { EvalResult.Ok(transformValue(Affine.scaling(pt(it[1]), sc(it[2]).base), it[0])) }

    fun <V : Value> translateGeom(
        g: Ref<V>,
        dx: ScalarRef,
        dy: ScalarRef,
    ): Ref<V> =
        op(g, dx, dy) { EvalResult.Ok(transformValue(Affine.translation(Vec2(sc(it[1]).mm, sc(it[2]).mm)), it[0])) }

    /** Translate any geometry by the vector [from] -> [to]. */
    fun <V : Value> translateByVector(
        g: Ref<V>,
        from: PointRef,
        to: PointRef,
    ): Ref<V> =
        op(g, from, to) { EvalResult.Ok(transformValue(Affine.translation(pt(it[2]) - pt(it[1])), it[0])) }

    /** Concentric circle whose radius is offset by sign*distance. */
    fun concentricCircle(
        circle: CircleRef,
        distance: ScalarRef,
        sign: Int,
    ): CircleRef =
        op(circle, distance) {
            val c = cir(it[0])
            val r = c.radius + sign * sc(it[1]).mm
            if (r <= 0.0) EvalResult.Invalid("non-positive radius") else EvalResult.Ok(CircleValue(Circle(c.center, r)))
        }

    // ================= Tier 1: scalar functions =================

    fun mul(
        a: ScalarRef,
        b: ScalarRef,
    ): ScalarRef = op(a, b) { EvalResult.Ok(ScalarValue(sc(it[0]) * sc(it[1]))) }

    fun div(
        a: ScalarRef,
        b: ScalarRef,
    ): ScalarRef = op(a, b) { EvalResult.Ok(ScalarValue(sc(it[0]) / sc(it[1]))) }

    fun absS(a: ScalarRef): ScalarRef = op(a) { EvalResult.Ok(ScalarValue(Quantity(kotlin.math.abs(sc(it[0]).base), sc(it[0]).dim))) }

    fun minS(
        a: ScalarRef,
        b: ScalarRef,
    ): ScalarRef =
        op(a, b) {
            if (sc(it[0]).dim != sc(it[1]).dim) throw DimensionError("min of ${sc(it[0]).dim} and ${sc(it[1]).dim}")
            EvalResult.Ok(ScalarValue(Quantity(minOf(sc(it[0]).base, sc(it[1]).base), sc(it[0]).dim)))
        }

    fun maxS(
        a: ScalarRef,
        b: ScalarRef,
    ): ScalarRef =
        op(a, b) {
            if (sc(it[0]).dim != sc(it[1]).dim) throw DimensionError("max of ${sc(it[0]).dim} and ${sc(it[1]).dim}")
            EvalResult.Ok(ScalarValue(Quantity(maxOf(sc(it[0]).base, sc(it[1]).base), sc(it[0]).dim)))
        }

    fun modS(
        a: ScalarRef,
        b: ScalarRef,
    ): ScalarRef =
        op(a, b) {
            if (sc(it[0]).dim != sc(it[1]).dim) throw DimensionError("mod of ${sc(it[0]).dim} and ${sc(it[1]).dim}")
            EvalResult.Ok(ScalarValue(Quantity(sc(it[0]).base % sc(it[1]).base, sc(it[0]).dim)))
        }

    fun powS(
        a: ScalarRef,
        n: Int,
    ): ScalarRef =
        op(a) {
            val q = sc(it[0])
            EvalResult.Ok(ScalarValue(Quantity(q.base.pow(n.toDouble()), Dimension(q.dim.length * n, q.dim.angle * n))))
        }

    fun sqrtS(a: ScalarRef): ScalarRef =
        op(a) {
            val q = sc(it[0])
            if (q.dim.length % 2 != 0 || q.dim.angle % 2 != 0) throw DimensionError("sqrt of odd dimension ${q.dim}")
            if (q.base < 0) throw ArithmeticException("sqrt of negative")
            EvalResult.Ok(ScalarValue(Quantity(kotlin.math.sqrt(q.base), Dimension(q.dim.length / 2, q.dim.angle / 2))))
        }

    fun sinS(a: ScalarRef): ScalarRef = op(a) { EvalResult.Ok(ScalarValue(constructit.units.sin(sc(it[0])))) }

    fun cosS(a: ScalarRef): ScalarRef = op(a) { EvalResult.Ok(ScalarValue(constructit.units.cos(sc(it[0])))) }

    fun tanS(a: ScalarRef): ScalarRef = op(a) { EvalResult.Ok(ScalarValue(constructit.units.tan(sc(it[0])))) }

    fun atan2S(
        y: ScalarRef,
        x: ScalarRef,
    ): ScalarRef =
        op(y, x) {
            if (sc(it[0]).dim != sc(it[1]).dim) throw DimensionError("atan2 of ${sc(it[0]).dim} and ${sc(it[1]).dim}")
            EvalResult.Ok(ScalarValue(Quantity.rad(kotlin.math.atan2(sc(it[0]).base, sc(it[1]).base))))
        }

    // ================= Tier 1: measurements =================

    /** Angle at [vertex] between rays to [a] and [b], in [0, PI]. */
    fun measureAngle(
        a: PointRef,
        vertex: PointRef,
        b: PointRef,
    ): ScalarRef =
        op(a, vertex, b) {
            val va = pt(it[0]) - pt(it[1])
            val vb = pt(it[2]) - pt(it[1])
            if (va.length() < Vec2.EPS || vb.length() < Vec2.EPS) {
                EvalResult.Invalid("zero-length arm")
            } else {
                EvalResult.Ok(ScalarValue(Quantity.rad(kotlin.math.acos((va.dot(vb) / (va.length() * vb.length())).coerceIn(-1.0, 1.0)))))
            }
        }

    /** Acute angle between two (undirected) lines, in [0, PI/2]. */
    fun measureAngleLines(
        l1: LineRef,
        l2: LineRef,
    ): ScalarRef =
        op(l1, l2) { EvalResult.Ok(ScalarValue(Quantity.rad(kotlin.math.acos(kotlin.math.abs(ln(it[0]).dir.dot(ln(it[1]).dir)).coerceIn(0.0, 1.0))))) }

    fun measureLength(segment: SegmentRef): ScalarRef =
        op(segment) {
            val s = (it[0] as SegmentValue).seg
            EvalResult.Ok(ScalarValue(Quantity.mm((s.b - s.a).length())))
        }

    fun measureRadius(circle: CircleRef): ScalarRef =
        op(circle) { EvalResult.Ok(ScalarValue(Quantity.mm(cir(it[0]).radius))) }

    fun measureX(point: PointRef): ScalarRef = op(point) { EvalResult.Ok(ScalarValue(Quantity.mm(pt(it[0]).x))) }

    fun measureY(point: PointRef): ScalarRef = op(point) { EvalResult.Ok(ScalarValue(Quantity.mm(pt(it[0]).y))) }

    // ================= Tier 2: mechanical constructions =================

    /** Fillet arc of [radius] in the corner at [corner] opening toward [p1] and [p2]. */
    fun filletCorner(
        p1: PointRef,
        corner: PointRef,
        p2: PointRef,
        radius: ScalarRef,
    ): ArcRef =
        op(p1, corner, p2, radius) {
            val v = pt(it[1])
            val r = sc(it[3]).mm
            val u1 = (pt(it[0]) - v).normalized()
            val u2 = (pt(it[2]) - v).normalized()
            val bis = u1 + u2
            if (bis.length() < Vec2.EPS) return@op EvalResult.Invalid("degenerate corner")
            val bisU = bis.normalized()
            val half = kotlin.math.acos(u1.dot(bisU).coerceIn(-1.0, 1.0))
            val sinH = kotlin.math.sin(half)
            val tanH = kotlin.math.tan(half)
            if (sinH < Vec2.EPS || tanH < Vec2.EPS) return@op EvalResult.Invalid("degenerate corner angle")
            val center = v + bisU * (r / sinH)
            val t1 = v + u1 * (r / tanH)
            val t2 = v + u2 * (r / tanH)
            val start = (t1 - center).angle()
            val end = (t2 - center).angle()
            EvalResult.Ok(ArcValue(Arc(center, r, start, end, (t1 - center).cross(t2 - center) > 0)))
        }

    /** Tangent to [circle] at [point] — the line through the point perpendicular to the radius. */
    fun tangentAtCircle(
        circle: CircleRef,
        point: PointRef,
    ): LineRef =
        op(circle, point) {
            val c = cir(it[0])
            val p = pt(it[1])
            val radial = p - c.center
            if (radial.length() < Vec2.EPS) {
                EvalResult.Invalid("point at circle centre")
            } else {
                EvalResult.Ok(LineValue(Line(p, radial.perp().normalized())))
            }
        }

    /**
     * Fillet arc of [radius] tangent to two lines, in the corner (their intersection) chosen by
     * [sign1]/[sign2] (which way along each line's direction the fillet opens). Captured at creation.
     */
    fun filletBetweenLines(
        l1: LineRef,
        l2: LineRef,
        radius: ScalarRef,
        sign1: Int,
        sign2: Int,
    ): ArcRef =
        op(l1, l2, radius) {
            val la = ln(it[0])
            val lb = ln(it[1])
            val r = sc(it[2]).mm
            val denom = la.dir.cross(lb.dir)
            if (kotlin.math.abs(denom) < Vec2.EPS) return@op EvalResult.Invalid("parallel legs")
            val corner = la.origin + la.dir * ((lb.origin - la.origin).cross(lb.dir) / denom)
            val u1 = la.dir * sign1.toDouble()
            val u2 = lb.dir * sign2.toDouble()
            val bis = u1 + u2
            if (bis.length() < Vec2.EPS) return@op EvalResult.Invalid("degenerate corner")
            val bisU = bis.normalized()
            val half = kotlin.math.acos(u1.dot(bisU).coerceIn(-1.0, 1.0))
            val sinH = kotlin.math.sin(half)
            val tanH = kotlin.math.tan(half)
            if (sinH < Vec2.EPS || tanH < Vec2.EPS) return@op EvalResult.Invalid("degenerate corner angle")
            val center = corner + bisU * (r / sinH)
            val t1 = corner + u1 * (r / tanH)
            val t2 = corner + u2 * (r / tanH)
            EvalResult.Ok(ArcValue(Arc(center, r, (t1 - center).angle(), (t2 - center).angle(), (t1 - center).cross(t2 - center) > 0)))
        }

    /** The two tangent points on [circle] of the tangents from external [point] (via Thales' circle). */
    fun tangentPointsFromPoint(
        point: PointRef,
        circle: CircleRef,
    ): PointSetRef =
        op(point, circle) {
            val p = pt(it[0])
            val c = cir(it[1])
            val thales = Circle((p + c.center) * 0.5, (p - c.center).length() * 0.5)
            EvalResult.Ok(PointSetValue(GeomMath.intersectCC(thales, c)))
        }

    /** External (outer) common tangent of two circles; [sign] >= 0 picks the first, else the second. */
    fun outerTangent(
        c1: CircleRef,
        c2: CircleRef,
        sign: Int,
    ): LineRef = commonTangent(c1, c2, inner = false, sign = sign)

    fun innerTangent(
        c1: CircleRef,
        c2: CircleRef,
        sign: Int,
    ): LineRef = commonTangent(c1, c2, inner = true, sign = sign)

    private fun commonTangent(
        c1: CircleRef,
        c2: CircleRef,
        inner: Boolean,
        sign: Int,
    ): LineRef =
        op(c1, c2) {
            val lines = GeomMath.commonTangents(cir(it[0]), cir(it[1]), inner)
            val idx = if (sign >= 0) 0 else 1
            if (idx >= lines.size) EvalResult.Invalid("no such common tangent") else EvalResult.Ok(LineValue(lines[idx]))
        }

    fun ray(
        origin: PointRef,
        through: PointRef,
    ): RayRef =
        op(origin, through) {
            val d = pt(it[1]) - pt(it[0])
            if (d.length() < Vec2.EPS) EvalResult.Invalid("ray through coincident points") else EvalResult.Ok(RayValue(Ray(pt(it[0]), d.normalized())))
        }

    /** Circumcircle through three points. */
    fun circle3(
        a: PointRef,
        b: PointRef,
        c: PointRef,
    ): CircleRef =
        op(a, b, c) {
            val cc =
                GeomMath.circumcenter(pt(it[0]), pt(it[1]), pt(it[2]))
                    ?: return@op EvalResult.Invalid("collinear points")
            EvalResult.Ok(CircleValue(Circle(cc, (pt(it[0]) - cc).length())))
        }

    /** Arc from [start] to [end] about [center]; radius = |start-center|, sweeps counter-clockwise. */
    fun arcCenterStartEnd(
        center: PointRef,
        start: PointRef,
        end: PointRef,
    ): ArcRef =
        op(center, start, end) {
            val c = pt(it[0])
            val s = pt(it[1])
            val e = pt(it[2])
            val r = (s - c).length()
            if (r < Vec2.EPS) {
                EvalResult.Invalid("start coincides with centre")
            } else {
                EvalResult.Ok(ArcValue(Arc(c, r, (s - c).angle(), (e - c).angle(), ccw = true)))
            }
        }

    /** Arc through three points (from a, through b, to c). */
    fun arc3(
        a: PointRef,
        b: PointRef,
        c: PointRef,
    ): ArcRef =
        op(a, b, c) {
            val pa = pt(it[0])
            val pb = pt(it[1])
            val pc = pt(it[2])
            val cc = GeomMath.circumcenter(pa, pb, pc) ?: return@op EvalResult.Invalid("collinear points")
            val r = (pa - cc).length()
            val ccw = (pb - pa).cross(pc - pa) > 0
            EvalResult.Ok(ArcValue(Arc(cc, r, (pa - cc).angle(), (pc - cc).angle(), ccw)))
        }

    fun direction(
        from: PointRef,
        to: PointRef,
    ): DirectionRef =
        op(from, to) {
            val d = pt(it[1]) - pt(it[0])
            if (d.length() < Vec2.EPS) EvalResult.Invalid("zero direction") else EvalResult.Ok(DirectionValue(Direction(d.normalized())))
        }

    // ================= Tier 3: profile (bridge to 3D) =================

    /** Assemble an ordered profile (chain) from segment and arc refs. */
    fun profile(vararg parts: Ref<*>): ProfileRef =
        op(*parts) { args ->
            val elems =
                args.map { v ->
                    when (v) {
                        is SegmentValue -> ProfileElement.Seg(v.seg)
                        is ArcValue -> ProfileElement.ArcE(v.arc)
                        else -> throw IllegalArgumentException("profile element must be a segment or arc")
                    }
                }
            EvalResult.Ok(ProfileValue(Profile(elems)))
        }

    // ================= Tier 4: the result layer (OP-14) =================
    // Trimming is what separates the *drawing* from the construction that produced it: a drawn line
    // is infinite and a circle is whole, but an outline needs the piece between two cut points. The
    // result is therefore constructed, not flagged — and for line/circle/arc it needs no new value
    // type, because a trimmed line *is* a segment and a trimmed circle *is* an arc.

    private fun carrierLine(v: Value): Line? =
        when (v) {
            is LineValue -> v.line
            is RayValue -> Line(v.ray.origin, v.ray.dir)
            is SegmentValue ->
                if ((v.seg.b - v.seg.a).length() < Vec2.EPS) {
                    null
                } else {
                    Line(v.seg.a, (v.seg.b - v.seg.a).normalized())
                }
            else -> null
        }

    private fun carrierCircle(v: Value): Circle? =
        when (v) {
            is CircleValue -> v.circle
            is ArcValue -> Circle(v.arc.center, v.arc.radius)
            else -> null
        }

    /**
     * The piece of [curve] between [from] and [to] (OP-14). Accepts a line, segment or ray — the
     * carrier-line coercion, so any linear element can be trimmed.
     *
     * Both cut points are **projected** onto the carrier rather than required to lie exactly on it.
     * A cut point is normally constructed *from* the curve (an intersection, a projection, a key
     * point), so exact incidence is unattainable in floating point anyway; projecting keeps the trim
     * well-defined as parameters move, instead of failing on noise.
     */
    fun segmentBetween(
        curve: Ref<*>,
        from: PointRef,
        to: PointRef,
    ): SegmentRef =
        op(curve, from, to) {
            val l = carrierLine(it[0]) ?: return@op EvalResult.Invalid("not a linear curve")
            val a = l.origin + l.dir * (pt(it[1]) - l.origin).dot(l.dir)
            val b = l.origin + l.dir * (pt(it[2]) - l.origin).dot(l.dir)
            if ((b - a).length() < Vec2.EPS) {
                EvalResult.Invalid("degenerate trim: the cut points coincide")
            } else {
                EvalResult.Ok(SegmentValue(Segment(a, b)))
            }
        }

    /**
     * The arc of [curve] from [from] to [to], sweeping counter-clockwise unless [ccw] is false
     * (OP-14). Accepts a circle or an arc (its carrier circle). [ccw] is a stored *discrete* branch
     * choice, exactly like the sign on a `Select` — which of the two arcs between two points is
     * meant is a choice, not something to be tracked (OP-1).
     *
     * Cut points are projected radially onto the circle, for the reason given on [segmentBetween].
     */
    fun arcBetween(
        curve: Ref<*>,
        from: PointRef,
        to: PointRef,
        ccw: Boolean = true,
    ): ArcRef =
        op(curve, from, to) {
            val c = carrierCircle(it[0]) ?: return@op EvalResult.Invalid("not a circular curve")
            val d0 = pt(it[1]) - c.center
            val d1 = pt(it[2]) - c.center
            if (d0.length() < Vec2.EPS || d1.length() < Vec2.EPS) {
                return@op EvalResult.Invalid("a cut point coincides with the centre")
            }
            val arc = Arc(c.center, c.radius, d0.angle(), d1.angle(), ccw)
            if (abs(GeomMath.sweep(arc)) < Vec2.EPS) {
                EvalResult.Invalid("degenerate trim: the cut points coincide")
            } else {
                EvalResult.Ok(ArcValue(arc))
            }
        }

    /**
     * Chain [parts] (segments, arcs, or a single whole circle) into a closed loop, normalised to
     * counter-clockwise (OP-14).
     *
     * The loop stores **which nodes, in which order** — a stable identity (OP-8), so a parameter
     * edit only moves the cut points and never re-decides what the boundary *is*. A chain that stops
     * meeting up makes this node invalid, which hides it and heals automatically (OP-3). Each piece
     * after the first is flipped if that is what continues the chain, so pieces may be named in
     * traversal order without regard to how their own endpoints happened to be ordered.
     */
    fun loop(vararg parts: Ref<*>): LoopRef =
        op(*parts) { args ->
            val elems = ArrayList<ProfileElement>(args.size)
            for (v in args) {
                val e =
                    when (v) {
                        is SegmentValue -> ProfileElement.Seg(v.seg)
                        is ArcValue -> ProfileElement.ArcE(v.arc)
                        is CircleValue -> ProfileElement.CircleE(v.circle)
                        else -> return@op EvalResult.Invalid("a loop piece must be a segment, an arc or a circle")
                    }
                elems.add(e)
            }
            val (chained, reason) = GeomMath.chainLoop(elems)
            if (chained == null) {
                EvalResult.Invalid(reason ?: "not a closed loop")
            } else {
                EvalResult.Ok(LoopValue(GeomMath.orient(chained, ccw = true)))
            }
        }

    /**
     * An area bounded by [outer] with [holes] removed (OP-14). Orientation is normalised here
     * (outer counter-clockwise, holes clockwise) so the signed areas add up, which is the form the
     * 2D→3D seam consumes (OP-17).
     *
     * Note the deliberate limit: containment is *not* verified. A hole placed outside the outer
     * boundary, or two overlapping holes, are accepted — only the degenerate case where the holes
     * remove more than the boundary encloses is rejected (in [regionArea]). Real containment
     * testing belongs with the point-in-region predicate, which this slice does not need.
     */
    fun region(
        outer: LoopRef,
        vararg holes: LoopRef,
    ): RegionRef =
        op(outer, *holes) { args ->
            val o = GeomMath.orient((args[0] as LoopValue).loop, ccw = true)
            val h = args.drop(1).map { GeomMath.orient((it as LoopValue).loop, ccw = false) }
            EvalResult.Ok(RegionValue(Region(o, h)))
        }

    /** Enclosed area of a loop (OP-4 measurement, dimension L²). Exact for segments and arcs. */
    fun loopArea(l: LoopRef): ScalarRef =
        op(l) {
            EvalResult.Ok(ScalarValue(Quantity(abs(GeomMath.signedArea((it[0] as LoopValue).loop)), Dimension.AREA)))
        }

    /** Area of a region: its outer boundary less its holes (OP-4 measurement, dimension L²). */
    fun regionArea(r: RegionRef): ScalarRef =
        op(r) {
            val reg = (it[0] as RegionValue).region
            val a = GeomMath.signedArea(reg.outer) + reg.holes.sumOf { h -> GeomMath.signedArea(h) }
            if (a <= 0.0) {
                EvalResult.Invalid("the holes remove more area than the outer boundary encloses")
            } else {
                EvalResult.Ok(ScalarValue(Quantity(a, Dimension.AREA)))
            }
        }

    // ================= sub-entity accessors (provenance-based points on a curve) =================
    // These let *derived* geometry (e.g. a mirrored segment) expose usable points for further
    // construction — the compound-value+accessor principle (OP-6/OP-8).

    /** The infinite line carrying a segment (through its endpoints). */
    fun lineOfSegment(s: SegmentRef): LineRef =
        op(s) {
            val seg = (it[0] as SegmentValue).seg
            if ((seg.b - seg.a).length() < Vec2.EPS) {
                EvalResult.Invalid("degenerate segment")
            } else {
                EvalResult.Ok(LineValue(Line(seg.a, (seg.b - seg.a).normalized())))
            }
        }

    /** The infinite line carrying a ray. */
    fun lineOfRay(r: RayRef): LineRef =
        op(r) {
            val ray = (it[0] as RayValue).ray
            EvalResult.Ok(LineValue(Line(ray.origin, ray.dir)))
        }

    fun segmentStart(s: SegmentRef): PointRef = op(s) { EvalResult.Ok(PointValue((it[0] as SegmentValue).seg.a)) }

    fun segmentEnd(s: SegmentRef): PointRef = op(s) { EvalResult.Ok(PointValue((it[0] as SegmentValue).seg.b)) }

    fun circleCenter(c: CircleRef): PointRef = op(c) { EvalResult.Ok(PointValue((it[0] as CircleValue).circle.center)) }

    fun rayOrigin(r: RayRef): PointRef = op(r) { EvalResult.Ok(PointValue((it[0] as RayValue).ray.origin)) }

    fun arcCenter(a: ArcRef): PointRef = op(a) { EvalResult.Ok(PointValue((it[0] as ArcValue).arc.center)) }

    fun arcStart(a: ArcRef): PointRef =
        op(a) {
            val arc = (it[0] as ArcValue).arc
            EvalResult.Ok(PointValue(arc.center + Vec2(arc.radius * kotlin.math.cos(arc.startAngle), arc.radius * kotlin.math.sin(arc.startAngle))))
        }

    fun arcEnd(a: ArcRef): PointRef =
        op(a) {
            val arc = (it[0] as ArcValue).arc
            EvalResult.Ok(PointValue(arc.center + Vec2(arc.radius * kotlin.math.cos(arc.endAngle), arc.radius * kotlin.math.sin(arc.endAngle))))
        }
}

// ---- typed accessors over an Evaluator pass ----

fun Evaluator.resultOf(ref: Ref<*>): EvalResult = eval(ref.node)

fun Evaluator.isValid(ref: Ref<*>): Boolean = eval(ref.node) is EvalResult.Ok

fun Evaluator.valueOf(ref: Ref<*>): Value? = (eval(ref.node) as? EvalResult.Ok)?.value

fun Evaluator.point(ref: PointRef): Vec2 = (valueOf(ref) as PointValue).p

fun Evaluator.scalar(ref: ScalarRef): Quantity = (valueOf(ref) as ScalarValue).q

fun Evaluator.line(ref: LineRef): Line = (valueOf(ref) as LineValue).line

fun Evaluator.segment(ref: SegmentRef): Segment = (valueOf(ref) as SegmentValue).seg

fun Evaluator.circle(ref: CircleRef): Circle = (valueOf(ref) as CircleValue).circle

fun Evaluator.arc(ref: ArcRef): Arc = (valueOf(ref) as ArcValue).arc

fun Evaluator.ray(ref: RayRef): Ray = (valueOf(ref) as RayValue).ray

fun Evaluator.direction(ref: DirectionRef): Direction = (valueOf(ref) as DirectionValue).dir

fun Evaluator.profile(ref: ProfileRef): Profile = (valueOf(ref) as ProfileValue).profile

fun Evaluator.loop(ref: LoopRef): Loop = (valueOf(ref) as LoopValue).loop

fun Evaluator.region(ref: RegionRef): Region = (valueOf(ref) as RegionValue).region

fun Evaluator.pointSet(ref: PointSetRef): constructit.geom.PointSet = (valueOf(ref) as PointSetValue).set
