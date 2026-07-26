package constructit.dsl

import constructit.core.ArcValue
import constructit.core.BezierValue
import constructit.core.CircleValue
import constructit.core.DirectionValue
import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.FrameValue
import constructit.core.IndirectNode
import constructit.core.InstanceNode
import constructit.core.LineValue
import constructit.core.LoopValue
import constructit.core.Node
import constructit.core.OpNode
import constructit.core.PlaneValue
import constructit.core.PointSetValue
import constructit.core.PointValue
import constructit.core.ProfileValue
import constructit.core.RayValue
import constructit.core.RegionValue
import constructit.core.ScalarValue
import constructit.core.SegmentValue
import constructit.core.SketchValue
import constructit.core.SolidValue
import constructit.core.SourceNode
import constructit.core.Value
import constructit.core.transformValue
import constructit.geom.Affine
import constructit.geom.Arc
import constructit.geom.Axis3
import constructit.geom.Bezier
import constructit.geom.BoolOp
import constructit.geom.Circle
import constructit.geom.Direction
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Justification
import constructit.geom.Line
import constructit.geom.Loop
import constructit.geom.MeshBool
import constructit.geom.Plane3
import constructit.geom.Profile
import constructit.geom.ProfileElement
import constructit.geom.Ray
import constructit.geom.Region
import constructit.geom.Segment
import constructit.geom.Sketch3
import constructit.geom.Solid3
import constructit.geom.SolidFace
import constructit.geom.Vec2
import constructit.geom.Vec3
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
typealias BezierRef = Ref<BezierValue>
typealias ProfileRef = Ref<ProfileValue>
typealias LoopRef = Ref<LoopValue>
typealias RegionRef = Ref<RegionValue>

/** A placed group's coordinate frame (OP-16) — origin + angle, held by one source node. */
typealias FrameRef = Ref<FrameValue>

// The 2D→3D seam (OP-17): a plane is the embedding frame, a sketch is regions on it, a solid is a
// feature built from a sketch. Three refs, one per node kind — the seam adds no new machinery.
typealias PlaneRef = Ref<PlaneValue>
typealias SketchRef = Ref<SketchValue>
typealias SolidRef = Ref<SolidValue>

/**
 * Builder for a construction DAG. Generates stable ids; supports macro instantiation with
 * derived path-ids `M/nk` (OP-6). Source nodes (parameters, free points) are mutable so a
 * parameter edit + a fresh [Evaluator] pass re-propagates through the graph.
 */
class Construction {
    private data class Scope(val prefix: String, var counter: Int)

    private val scopes = ArrayDeque<Scope>().apply { addLast(Scope("", 0)) }

    /**
     * How many nodes this construction has ever handed out an id to.
     *
     * Not bookkeeping for its own sake: it is the one observable that separates *computing* a feature
     * from *regenerating* it (OP-21). A parameter edit must recompute through the existing graph and
     * therefore leave this untouched; a feature that rebuilds its geometry on every edit grows it
     * monotonically, leaving the replaced nodes behind. A test asserts the former.
     */
    var nodesCreated: Int = 0
        private set

    private val issuedIds = HashSet<String>()

    private fun freshId(hint: String? = null): String {
        nodesCreated++
        val scope = scopes.last()
        val local = hint ?: "n${++scope.counter}"
        val path = scopes.filter { it.prefix.isNotEmpty() }.joinToString("/") { it.prefix }
        val base = if (path.isEmpty()) local else "$path/$local"
        // Evaluation memoizes by id (OP-5), so two nodes sharing one id silently alias each other —
        // a caller's name hint must therefore be uniquified, never trusted. Deterministic: the same
        // build order yields the same suffixes, which is what macro path-ids (OP-6) rely on.
        var id = base
        var n = 2
        while (!issuedIds.add(id)) id = "${base}_${n++}"
        return id
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

    // ---- macro instances: path-addressed views over a definition subgraph (OP-6) ----

    /**
     * One node of macro instance [instanceId] standing for definition node [defNode], with [inputs]
     * already mapped into the instance (OP-6).
     *
     * Its id is the **derived path-id** `instanceId/defNodeId`, so an instance is a namespace: every
     * internal node of the definition is addressable inside every instance, which is what makes macros
     * transparent groups rather than black boxes. Nothing is copied — see [InstanceNode].
     */
    fun instanceNode(
        instanceId: String,
        defNode: Node,
        inputs: List<Node>,
    ): Node = InstanceNode(freshId("$instanceId/${defNode.id}"), defNode, inputs)

    /**
     * A free point *inside* a definition — not designated an input, hence a **captured default**
     * (OP-6) — as seen by one instance: the definition's own position, offset by
     * (instance anchor − definition anchor).
     *
     * This is the anchor rule that makes a stamped instance land under the cursor: the captured points
     * are held *relative* to the first point input, so clicking elsewhere translates the whole instance.
     * It reads the definition's node live, so dragging that internal point still re-propagates to every
     * instance — the capture is of the *relative layout*, not of a frozen coordinate.
     */
    fun instanceCapturedPoint(
        instanceId: String,
        defSource: Node,
        defAnchor: Node,
        anchor: Node,
    ): Node =
        OpNode(freshId("$instanceId/${defSource.id}"), listOf(defSource, defAnchor, anchor)) {
            val p = (it[0] as PointValue).p
            val a = (it[1] as PointValue).p
            val w = (it[2] as PointValue).p
            EvalResult.Ok(PointValue(p - a + w))
        }

    /**
     * The same capture for a single **coordinate** source — an ortho vertex's x or y (OP-19/OP-20),
     * where a position is held as two shared scalars rather than as one point value. [axis] says which
     * of the anchor's coordinates it is measured against, which is the only extra thing needed to
     * translate it with the instance.
     */
    fun instanceCapturedCoord(
        instanceId: String,
        defSource: Node,
        defAnchor: Node,
        anchor: Node,
        axis: Int,
    ): Node =
        OpNode(freshId("$instanceId/${defSource.id}"), listOf(defSource, defAnchor, anchor)) {
            val v = (it[0] as ScalarValue).q
            val a = (it[1] as PointValue).p
            val w = (it[2] as PointValue).p
            val d = if (axis == 0) w.x - a.x else w.y - a.y
            EvalResult.Ok(ScalarValue(v + Quantity.mm(d)))
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

    /**
     * Pick a branch from a solution set. sign >= 0 -> first (left), sign < 0 -> last (right).
     *
     * [emptyReason] is what an *empty* set is called in the caller's own terms (OP-3). A default rather
     * than a new op, because "there is no such point" is always the same fact and only ever needs a better
     * sentence: a generalized fillet's centre is an intersection of two offsets, so its honest failure is
     * "no tangent circle of that radius", not "empty intersection" — and a reason nobody can act on is the
     * kind of invalidity that reads as a bug.
     */
    fun select(
        set: PointSetRef,
        sign: Int,
        emptyReason: String = "empty intersection",
    ): PointRef =
        op(set) {
            val pts = (it[0] as PointSetValue).set.points
            when {
                pts.isEmpty() -> EvalResult.Invalid(emptyReason)
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

    /**
     * A dimensionless number read as an **angle in radians** (OP-7) — the one conversion the dimension
     * system deliberately does not derive.
     *
     * `sin`/`cos`/`tan`/`atan2` already cross the angle boundary in the other direction, so without this
     * any closed-form angular formula that *mixes* the two is unstateable. The canonical example is the
     * involute function `inv(β) = tan β − β`, which subtracts an angle from a plain number and is what a
     * gear tooth's flank is made of: the addition is a units op, not a gear feature.
     */
    fun radians(x: ScalarRef): ScalarRef =
        op(x) { EvalResult.Ok(ScalarValue(Quantity.rad(sc(it[0]).requireDim(Dimension.NONE, "radians").base))) }

    /** An angle's measure in radians as a plain number — the exact inverse of [radians]. */
    fun radianMeasure(a: ScalarRef): ScalarRef =
        op(a) { EvalResult.Ok(ScalarValue(Quantity.number(sc(it[0]).requireDim(Dimension.ANGLE, "radianMeasure").base))) }

    /**
     * [value] itself, but **invalid with [what] as the reason** when it is not positive (OP-3) — a stated
     * precondition as a node.
     *
     * The point is *where* it sits: threaded through the chain that needs it (an angle a construction is
     * only valid for a positive sweep of, a length that must not run backwards), it makes the dependent
     * geometry disappear with an explanation instead of coming out folded through itself — and heal when
     * the parameters move back. A macro's own domain becomes an ordinary node rather than a comment.
     */
    fun requirePositive(
        value: ScalarRef,
        what: String,
    ): ScalarRef =
        op(value) {
            val q = sc(it[0])
            if (q.base > 0.0) EvalResult.Ok(ScalarValue(q)) else EvalResult.Invalid(what)
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

    /**
     * Angle between the rays [sign1]`*dir(l1)` and [sign2]`*dir(l2)`, in `[0, PI]` — the opening of **one
     * sector** of the crossing, rather than the acute angle of [measureAngleLines].
     *
     * The two signs are a stored discrete branch choice, exactly like a `Select` sign (OP-1): which of the
     * four sectors a dimension names is decided once, when it is placed, and never re-derived — so the
     * number shown and the arc drawn stay the same sector as the lines move.
     */
    fun measureAngleSector(
        l1: LineRef,
        l2: LineRef,
        sign1: Int,
        sign2: Int,
    ): ScalarRef =
        op(l1, l2) {
            val d1 = ln(it[0]).dir * sign1.toDouble()
            val d2 = ln(it[1]).dir * sign2.toDouble()
            EvalResult.Ok(ScalarValue(Quantity.rad(kotlin.math.acos(d1.dot(d2).coerceIn(-1.0, 1.0)))))
        }

    fun measureLength(segment: SegmentRef): ScalarRef =
        op(segment) {
            val s = (it[0] as SegmentValue).seg
            EvalResult.Ok(ScalarValue(Quantity.mm((s.b - s.a).length())))
        }

    fun measureRadius(circle: CircleRef): ScalarRef =
        op(circle) { EvalResult.Ok(ScalarValue(Quantity.mm(cir(it[0]).radius))) }

    fun measureX(point: PointRef): ScalarRef = op(point) { EvalResult.Ok(ScalarValue(Quantity.mm(pt(it[0]).x))) }

    fun measureY(point: PointRef): ScalarRef = op(point) { EvalResult.Ok(ScalarValue(Quantity.mm(pt(it[0]).y))) }

    // ---- placement (OP-16): a frame maps a group's local geometry into the world ----

    /**
     * The world position of [local] as seen through [frame] — the one op a placed group needs (OP-16).
     *
     * Everything else about placement is the existing weld substrate: the group's free point sources are
     * *bound* onto nodes of this kind (`SourceNode.boundTo`), so nothing that already referenced them is
     * rewired (OP-5) and moving the group is a single literal write on the frame.
     */
    fun frameApply(
        frame: Ref<FrameValue>,
        local: PointRef,
    ): PointRef =
        op(frame, local) {
            EvalResult.Ok(PointValue((it[0] as FrameValue).toWorld(pt(it[1]))))
        }

    /**
     * A **re-pointable view** of [ref] — an [IndirectNode]. Its value is [ref]'s until something binds it,
     * so a capture can be inserted *above* an already-referenced node without rewiring its consumers
     * (OP-5). What an ortho vertex is published through, so a placement can put the frame in front of it
     * (OP-16); see [IndirectNode] for why a per-axis binding cannot do that job.
     */
    fun <V : Value> indirect(ref: Ref<V>): Ref<V> = Ref(IndirectNode(freshId(), ref.node))

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

    /**
     * The point of [circle] on the ray from its centre through [toward] — a **scaled radial**.
     *
     * The circle leg's half of a generalized fillet (OP-14): a circle of radius r centred at [toward] and
     * tangent to [circle] touches it exactly there, whether it hugs it from outside (the centre sits at
     * R+r) or from inside (R−r), because both put the centre on that same ray. So one op covers every
     * variant, and it is to a circle leg what [projectToLine] is to a straight one — a *node*, hence a
     * tangency that keeps following the parameters instead of freezing into a coordinate.
     */
    fun radialPoint(
        circle: CircleRef,
        toward: PointRef,
    ): PointRef =
        op(circle, toward) {
            val c = cir(it[0])
            val d = pt(it[1]) - c.center
            if (d.length() < Vec2.EPS) {
                EvalResult.Invalid("no radial direction from the circle's own centre")
            } else {
                EvalResult.Ok(PointValue(c.center + d.normalized() * c.radius))
            }
        }

    /**
     * The arc round [center] running the **short way** from [from] to [to] — a fillet's own arc, whatever
     * curves it joins.
     *
     * The last step of a generalized fillet, and the only piece of it that is not an op that already
     * existed: the centre is an intersection of two offsets and each tangency is a projection or a
     * [radialPoint], so all this adds is "and the rounding is the minor arc between them". Minor because a
     * fillet fills a corner: its sweep is π minus the corner's opening, hence always under a half turn —
     * the same rule [filletBetweenLines] applies to its own tangent points, here stated once for every leg
     * kind. The radius is [from]'s distance, and a [to] that disagrees with it is invalid (OP-3) rather
     * than quietly rounded, since that means the two tangencies are not on one circle at all.
     */
    fun filletArc(
        center: PointRef,
        from: PointRef,
        to: PointRef,
    ): ArcRef =
        op(center, from, to) {
            val c = pt(it[0])
            val a = pt(it[1]) - c
            val b = pt(it[2]) - c
            val r = a.length()
            if (r < Vec2.EPS) return@op EvalResult.Invalid("zero-radius fillet")
            if (abs(b.length() - r) > GeomMath.JOIN_TOL) {
                return@op EvalResult.Invalid("fillet tangent points are not equidistant from its centre")
            }
            if ((a + b).length() < Vec2.EPS) return@op EvalResult.Invalid("degenerate fillet (tangent points opposite)")
            EvalResult.Ok(ArcValue(Arc(c, r, a.angle(), b.angle(), a.cross(b) > 0)))
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
                        is BezierValue -> ProfileElement.BezierE(v.bezier)
                        else -> throw IllegalArgumentException("profile element must be a segment, arc or Bézier")
                    }
                }
            EvalResult.Ok(ProfileValue(Profile(elems)))
        }

    // ================= Tier 3b: splines (OP-15) =================

    /**
     * A cubic Bézier from [p0] to [p3] shaped by [p1] and [p2] (OP-15).
     *
     * Nothing new is needed to evaluate this — a spline *is* a pure function of its control points.
     * The interesting part is that those control points are ordinary `PointRef`s, so each may itself
     * be **constructed**: an intersection, a projection, a point on a circle. That is the bridge from
     * technical construction to smooth geometry, and it is why splines fit this paradigm natively
     * rather than by concession. See [bezierTangentControl] for tangency by construction.
     */
    fun bezier(
        p0: PointRef,
        p1: PointRef,
        p2: PointRef,
        p3: PointRef,
    ): BezierRef =
        op(p0, p1, p2, p3) {
            EvalResult.Ok(BezierValue(Bezier(pt(it[0]), pt(it[1]), pt(it[2]), pt(it[3]))))
        }

    /**
     * The control point that makes a Bézier leave [from] along [line], at distance [handle] (OP-15).
     *
     * This is how tangency stops being a constraint. Every sketcher asserts "spline tangent to this
     * line" and solves it; here the first control leg is simply *placed on* the tangent line, so G1
     * continuity is not enforced — it is structurally impossible to violate. [side] picks which way
     * along the line the handle extends.
     */
    fun bezierTangentControl(
        from: PointRef,
        line: LineRef,
        handle: ScalarRef,
        side: Int = +1,
    ): PointRef =
        op(from, line, handle) {
            val p = pt(it[0])
            val l = ln(it[1])
            val d = sc(it[2]).mm * (if (side >= 0) 1.0 else -1.0)
            EvalResult.Ok(PointValue(p + l.dir * d))
        }

    /** Point on a Bézier at parameter [t] in [0,1] — a sub-entity accessor (OP-8). */
    fun bezierPointAt(
        b: BezierRef,
        t: Double,
    ): PointRef = op(b) { EvalResult.Ok(PointValue(GeomMath.bezierPointAt((it[0] as BezierValue).bezier, t))) }

    fun bezierStart(b: BezierRef): PointRef = op(b) { EvalResult.Ok(PointValue((it[0] as BezierValue).bezier.p0)) }

    fun bezierEnd(b: BezierRef): PointRef = op(b) { EvalResult.Ok(PointValue((it[0] as BezierValue).bezier.p3)) }

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
                        is BezierValue -> ProfileElement.BezierE(v.bezier)
                        else -> return@op EvalResult.Invalid("a loop piece must be a segment, an arc, a circle or a Bézier")
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
     * **The one thing checked here is degeneracy:** holes that remove at least as much area as the outer
     * boundary encloses leave no area at all, so the region is invalid *with a reason* rather than a value
     * nothing downstream can use (OP-3, and it heals). The check lives here and not only in [regionArea]
     * because it is a statement about the *region*, and a caller that never asks for the area — an extrude,
     * say — would otherwise meet it as a triangulation failure, which reports a symptom instead of the fault.
     *
     * **Containment is still deliberately not verified**, and the difference matters: a hole poking out
     * through the boundary while remaining *smaller* than it passes this check and is accepted (a gear with
     * a bore just outside its root circle is exactly that shape). Nothing here can catch it, which is why a
     * construction that can produce such a shape has to state its own domain — see `dsl.spurGear`. Real
     * containment testing belongs with the point-in-region predicate, which this slice does not need.
     */
    fun region(
        outer: LoopRef,
        vararg holes: LoopRef,
    ): RegionRef =
        op(outer, *holes) { args ->
            val o = GeomMath.orient((args[0] as LoopValue).loop, ccw = true)
            val h = args.drop(1).map { GeomMath.orient((it as LoopValue).loop, ccw = false) }
            val net = GeomMath.signedArea(o) + h.sumOf { GeomMath.signedArea(it) }
            if (net <= 0.0) {
                EvalResult.Invalid("the holes remove more area than the outer boundary encloses")
            } else {
                EvalResult.Ok(RegionValue(Region(o, h)))
            }
        }

    /**
     * The footprint of a **thick path** (OP-21): the offset region of [thickness] around the carrier
     * polyline through [vertices], justified by [justification], mitred at every interior corner and
     * capped at the ends of an open carrier. One node, one [RegionValue] — so the footprint is a value
     * things can depend on, not a bundle of loose face segments that has to be rebuilt.
     *
     * All of the geometry happens **inside** `compute` — the rule the first wall implementation broke:
     * leg directions, mitres and (for a ring) which face is the outer boundary are functions of where the
     * carrier currently is, so deriving them while assembling the graph freezes the shape the carrier had
     * at build time. Only the *count* of carrier vertices is structural, and that is what the input list
     * carries.
     *
     * Interval features along the path (openings) are deliberately **not** inputs: in plan an opening
     * does not interrupt the material — below a sill and above a head there is wall, and even a door
     * leaves a lintel — so the footprint is unbroken and the gap is a drawing convention (OP-21).
     */
    fun thickFootprint(
        vertices: List<PointRef>,
        thickness: ScalarRef,
        closed: Boolean,
        justification: Justification,
    ): RegionRef =
        op(*(vertices + thickness).toTypedArray()) { args ->
            val pts = args.dropLast(1).map { (it as PointValue).p }
            val t = (args.last() as ScalarValue).q.mm
            val (faces, why) = GeomMath.thickFaces(pts, closed, justification.offsets(t))
            if (faces == null) return@op EvalResult.Invalid(why ?: "no footprint")
            val (region, reason) = GeomMath.thickRegion(faces)
            if (region == null) EvalResult.Invalid(reason ?: "no footprint") else EvalResult.Ok(RegionValue(region))
        }

    /**
     * The footprint of **one interval feature** of a thick path (OP-21/OP-22): the rectangle spanning
     * [width] from [position] along leg [legIndex] of the carrier through [vertices], across the whole
     * [thickness] of the path.
     *
     * This is the plan shape of the box that a *3D* opening subtracts — the plan drawing still shows no
     * cut (OP-21: the gap is a convention). Everything is computed inside `compute` from the carrier's
     * current geometry, exactly as [thickFootprint] is, so dragging a wall corner or typing a new
     * position moves the cut instead of rebuilding it. The two faces are the wall's own faces, so the
     * subtraction's side walls are *coplanar* with the wall's — the degenerate case the 2D kernel is
     * built to handle honestly.
     */
    fun intervalFootprint(
        vertices: List<PointRef>,
        thickness: ScalarRef,
        closed: Boolean,
        justification: Justification,
        legIndex: Int,
        position: ScalarRef,
        width: ScalarRef,
    ): RegionRef =
        op(*(vertices + listOf(thickness, position, width)).toTypedArray()) { args ->
            val pts = args.dropLast(3).map { (it as PointValue).p }
            val t = (args[args.size - 3] as ScalarValue).q.requireDim(Dimension.LENGTH, "thickness").mm
            val pos = (args[args.size - 2] as ScalarValue).q.requireDim(Dimension.LENGTH, "position").mm
            val w = (args[args.size - 1] as ScalarValue).q.requireDim(Dimension.LENGTH, "width").mm
            if (w <= 0.0) return@op EvalResult.Invalid("an opening needs a positive width")
            val (faces, why) = GeomMath.thickFaces(pts, closed, justification.offsets(t))
            if (faces == null) return@op EvalResult.Invalid(why ?: "no footprint")
            if (legIndex < 0 || legIndex >= faces.legCount) return@op EvalResult.Invalid("leg ${legIndex + 1} is not a leg of this path")
            val a = GeomMath.facePoint(faces, legIndex, pos, 0)
            val b = GeomMath.facePoint(faces, legIndex, pos + w, 0)
            val c = GeomMath.facePoint(faces, legIndex, pos + w, 1)
            val d = GeomMath.facePoint(faces, legIndex, pos, 1)
            val loop =
                Loop(
                    listOf(
                        ProfileElement.Seg(Segment(a, b)),
                        ProfileElement.Seg(Segment(b, c)),
                        ProfileElement.Seg(Segment(c, d)),
                        ProfileElement.Seg(Segment(d, a)),
                    ),
                )
            EvalResult.Ok(RegionValue(Region(GeomMath.orient(loop, ccw = true), emptyList())))
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

    // ================= Tier 5: the 2D→3D seam (OP-17) =================
    // Upward only, and by one node kind at a time: a plane is a frame, a sketch is regions *on* a
    // frame, a feature is a sketch plus parameters. Nothing here changes 2D geometry — that is the whole
    // point of the seam being a separate embedding node rather than a coordinate system 2D lives in.

    /**
     * A sketch plane through [origin] spanned by [u] and [v] (OP-17).
     *
     * The two spanning vectors are **orthonormalised** here (`u` normalised, `v` made perpendicular to
     * it) rather than demanded exact, because a frame derived from real geometry is only orthogonal to
     * within floating point, and a plane whose axes are slightly skew would quietly shear every sketch
     * placed on it. Literal geometry: a *parametric* plane comes from [planeOffset] or from a face
     * accessor ([facePlane]), which is what the sketch→feature→sketch loop uses.
     */
    fun plane(
        origin: Vec3,
        u: Vec3,
        v: Vec3,
    ): PlaneRef =
        op {
            val uu = u.normalized()
            if (uu.length() < Vec3.EPS) return@op EvalResult.Invalid("a plane's first axis has no direction")
            val vv = (v - uu * v.dot(uu)).normalized()
            if (vv.length() < Vec3.EPS) return@op EvalResult.Invalid("a plane's axes are parallel")
            EvalResult.Ok(PlaneValue(Plane3(origin, uu, vv)))
        }

    /** The canonical XY plane at z = 0 (normal +Z) — the default sketch plane. */
    fun planeXY(): PlaneRef = plane(Vec3.ZERO, Vec3.X, Vec3.Y)

    /** The canonical XZ plane at y = 0. Its normal is −Y, so sketch v maps to world +Z. */
    fun planeXZ(): PlaneRef = plane(Vec3.ZERO, Vec3.X, Vec3.Z)

    /** The canonical YZ plane at x = 0 (normal +X). */
    fun planeYZ(): PlaneRef = plane(Vec3.ZERO, Vec3.Y, Vec3.Z)

    /**
     * [plane] moved [distance] along its own normal — the parametric datum plane, since the offset is an
     * ordinary scalar node and may itself be measured, wired or shared.
     */
    fun planeOffset(
        plane: PlaneRef,
        distance: ScalarRef,
    ): PlaneRef =
        op(plane, distance) {
            EvalResult.Ok(PlaneValue((it[0] as PlaneValue).plane.translated(sc(it[1]).mm)))
        }

    /** [plane] with its normal reversed (and its in-plane frame mirrored, as it must be). */
    fun planeFlipped(plane: PlaneRef): PlaneRef =
        op(plane) { EvalResult.Ok(PlaneValue((it[0] as PlaneValue).plane.flipped())) }

    /**
     * **The seam** (OP-17): [regions] (OP-14's result layer) embedded on [plane].
     *
     * The 2D regions are untouched and unaware — which is what lets the same region be sketched on
     * several planes, macro-instance semantics (OP-6) applied to the seam.
     */
    fun sketchOn(
        plane: PlaneRef,
        vararg regions: RegionRef,
    ): SketchRef =
        op(plane, *regions) { args ->
            if (regions.isEmpty()) return@op EvalResult.Invalid("a sketch needs at least one region")
            val p = (args[0] as PlaneValue).plane
            EvalResult.Ok(SketchValue(Sketch3(p, args.drop(1).map { (it as RegionValue).region })))
        }

    /** The plane a sketch is embedded on — the accessor a further datum is offset from. */
    fun sketchPlane(sketch: SketchRef): PlaneRef =
        op(sketch) { EvalResult.Ok(PlaneValue((it[0] as SketchValue).sketch.plane)) }

    /**
     * Extrude [sketch] by [depth] along its plane's normal (OP-17 slice 1).
     *
     * One node produces the whole solid — analytic feature *and* mesh — because the mesh is derived
     * data, not a second object (OP-9): there is no state in which a solid's triangles and its
     * parameters disagree. A parameter edit recomputes this node; it never rebuilds the graph (OP-21).
     */
    fun extrude(
        sketch: SketchRef,
        depth: ScalarRef,
    ): SolidRef =
        op(sketch, depth) {
            val d = sc(it[1]).requireDim(Dimension.LENGTH, "extrude depth")
            val (solid, why) = Geom3.extrude((it[0] as SketchValue).sketch, d.mm)
            if (solid == null) EvalResult.Invalid(why ?: "cannot extrude") else EvalResult.Ok(SolidValue(solid))
        }

    /**
     * Revolve [sketch] through [angle] about the axis through [axisOrigin] in direction [axisDir] —
     * both **in the sketch plane** (OP-17 slice 2).
     *
     * The axis is given by ordinary 2D nodes, so it can be *constructed* (a symmetry line, a
     * centreline through two key points) and moves with the profile. A profile touching the axis is
     * legal, a profile crossing it is invalid with a reason and heals (OP-3) — see [Geom3.revolve].
     */
    fun revolve(
        sketch: SketchRef,
        axisOrigin: PointRef,
        axisDir: DirectionRef,
        angle: ScalarRef,
    ): SolidRef =
        op(sketch, axisOrigin, axisDir, angle) {
            val a = sc(it[3]).requireDim(Dimension.ANGLE, "revolve angle").base
            val (solid, why) =
                Geom3.revolve(
                    (it[0] as SketchValue).sketch,
                    pt(it[1]),
                    (it[2] as DirectionValue).dir.v,
                    a,
                )
            if (solid == null) EvalResult.Invalid(why ?: "cannot revolve") else EvalResult.Ok(SolidValue(solid))
        }

    // ---- booleans between prismatic solids (OP-22) ----
    // One op node each; the slab algebra lives inside `compute`, which is where value-dependent work
    // belongs (OP-21's rule). The operands are ordinary solid nodes, so a boolean's result is an operand
    // of the next boolean — prisms are closed under these three operations, which is the point.

    /** Everything in either [a] or [b] (OP-22). */
    fun union(
        a: SolidRef,
        b: SolidRef,
    ): SolidRef = booleanOf(a, b, BoolOp.UNION)

    /** [a] with [b] removed — the counterbore, and the wall opening (OP-22). */
    fun subtract(
        a: SolidRef,
        b: SolidRef,
    ): SolidRef = booleanOf(a, b, BoolOp.SUBTRACT)

    /** Only what is in both [a] and [b] (OP-22). */
    fun intersect(
        a: SolidRef,
        b: SolidRef,
    ): SolidRef = booleanOf(a, b, BoolOp.INTERSECT)

    /**
     * The two paths of one operation, and the order matters (OP-22 first, then OP-9).
     *
     * **Same axis → the exact algebra**, always, and its refusals stay refusals: an empty result or an
     * inconsistent arrangement is reported as itself and is *not* retried on the mesh engine. A general
     * boolean that quietly answered where the exact one declined would make the exact path impossible to
     * trust, since nothing downstream could tell which one had run.
     *
     * **Otherwise → the general engine** (Manifold, OP-9, behind the [MeshBool] seam): a cross-axis pair, a
     * revolve operand, a mesh-only result feeding the next boolean. The result is a mesh with no analytic
     * form ([Feature3.MeshBoolean]) — which is the OP-9 partition, not a shortcut: what leaves the exact
     * path leaves the analytic layer with it. When the engine is not available (no native library, or a
     * WASM module still loading in the browser) the node is invalid *with that as the reason* and heals the
     * moment it becomes available (OP-3).
     */
    private fun booleanOf(
        a: SolidRef,
        b: SolidRef,
        kind: BoolOp,
    ): SolidRef =
        op(a, b) {
            val sa = (it[0] as SolidValue).solid
            val sb = (it[1] as SolidValue).solid
            if (Geom3.sameAxis(sa.feature, sb.feature)) {
                val (solid, why) = Geom3.boolean(kind, sa, sb)
                if (solid == null) {
                    EvalResult.Invalid(why ?: "cannot combine these solids")
                } else {
                    EvalResult.Ok(SolidValue(solid))
                }
            } else {
                val (mesh, why) = MeshBool.boolean(kind, sa.mesh, sb.mesh)
                if (mesh == null) {
                    EvalResult.Invalid(why ?: "cannot combine these solids")
                } else {
                    EvalResult.Ok(SolidValue(Solid3(Feature3.MeshBoolean(kind), mesh)))
                }
            }
        }

    /**
     * The plane of a solid's named face (OP-8) — enough to **sketch on a face**, which is the slice of
     * the seam that actually tests it (OP-17).
     *
     * An ordinary derived node: `which` is a stored discrete choice (exactly like a `Select` sign,
     * OP-1) and the plane is recomputed from the feature's parameters, so it stays *the top face*
     * across every edit. Nothing is re-identified from mesh topology, which is why the
     * topological-naming problem does not arise here.
     */
    fun facePlane(
        solid: SolidRef,
        which: SolidFace,
    ): PlaneRef =
        op(solid) {
            val (p, why) = Geom3.facePlane((it[0] as SolidValue).solid.feature, which)
            if (p == null) EvalResult.Invalid(why ?: "no such face") else EvalResult.Ok(PlaneValue(p))
        }

    /**
     * The plane of the **planar side face** over boundary piece [piece] of [solid] (OP-8) — what makes a
     * *vertical* face reachable, and with it the cross-axis features mechanical work needs (a drilled hole
     * in a plate's edge, a pocket on a flat).
     *
     * The twin of [facePlane] in every respect that matters: `piece` is a stored discrete choice (like a
     * `Select` sign, OP-1), the plane is recomputed from the feature's own parameters — so stretching the
     * part moves the face and everything sketched on it — and its normal points **out** of the material.
     * A sketch on the face therefore wants [planeFlipped], so that a positive extrude depth cuts inward;
     * see [constructit.geom.Geom3.SideFace] for the frame's axes and origin, and for what is refused
     * (a curved edge, a non-vertical axis, a solid with no prism form).
     */
    fun sideFacePlane(
        solid: SolidRef,
        piece: Int,
    ): PlaneRef =
        op(solid) {
            val (face, why) = Geom3.sideFace((it[0] as SolidValue).solid.feature, piece)
            if (face == null) EvalResult.Invalid(why ?: "no such side face") else EvalResult.Ok(PlaneValue(face.plane))
        }

    /**
     * The **cross-section** of [solid] at world height [height] — the downward half of the seam (OP-17),
     * and an ordinary 2D [RegionRef] from that moment on: it can be outlined, dimensioned, measured, and
     * extruded again, because nothing about it remembers where it came from except its inputs.
     *
     * Exact for a prism (OP-22) — the section *is* the slab there — and analytic for a plain extrude,
     * whose arcs survive the cut. A revolve, a non-vertical prism and a height outside the material are
     * refused with a reason and heal (OP-3); see [Geom3.sectionAt] for the boundary rule.
     *
     * A cut that falls into **several** disjoint areas is refused too, and that is the type talking rather
     * than a limitation of the geometry: a `Region` is one outer boundary with holes, so "the wall at floor
     * level, which the door splits in two" has no single-region answer. Cutting where the solid is
     * connected does, and the count of areas is a *value*, so this stays one node either way.
     */
    fun sectionAt(
        solid: SolidRef,
        height: ScalarRef,
    ): RegionRef =
        op(solid, height) {
            val h = sc(it[1]).requireDim(Dimension.LENGTH, "section height")
            val (regions, why) = Geom3.sectionAt((it[0] as SolidValue).solid.feature, h.mm)
            if (regions == null) {
                EvalResult.Invalid(why ?: "cannot section this solid")
            } else if (regions.size != 1) {
                EvalResult.Invalid(
                    "the section at ${h.mm} mm falls into ${regions.size} separate areas, and an area is one region — " +
                        "cut at a height where the solid is connected",
                )
            } else {
                EvalResult.Ok(RegionValue(regions[0]))
            }
        }

    /**
     * The volume of a solid (OP-4, dimension L³), computed from its mesh by the divergence theorem.
     *
     * A mesh-derived **scalar**, so it is free to drive a *new* construction forward — including a 2D
     * one, which is the 3D→2D half of the seam (a papercraft net's edge lengths will be built this
     * way). Feeding one back into the solid's own ancestors would be a cycle, and is forbidden (OP-4);
     * the value is exact for the mesh and approximate for the curved solid, by the tessellation
     * tolerance.
     */
    fun measureVolume(solid: SolidRef): ScalarRef =
        op(solid) {
            EvalResult.Ok(ScalarValue(Quantity(Geom3.volume((it[0] as SolidValue).solid.mesh), Dimension.VOLUME)))
        }

    /** The lower bound of a solid's bounding box along [axis] (OP-4). */
    fun measureBBoxMin(
        solid: SolidRef,
        axis: Axis3,
    ): ScalarRef = bboxMeasure(solid) { lo, _ -> lo.component(axis) }

    /** The upper bound of a solid's bounding box along [axis] (OP-4). */
    fun measureBBoxMax(
        solid: SolidRef,
        axis: Axis3,
    ): ScalarRef = bboxMeasure(solid) { _, hi -> hi.component(axis) }

    /** The extent of a solid's bounding box along [axis] (OP-4). */
    fun measureBBoxExtent(
        solid: SolidRef,
        axis: Axis3,
    ): ScalarRef = bboxMeasure(solid) { lo, hi -> hi.component(axis) - lo.component(axis) }

    private fun bboxMeasure(
        solid: SolidRef,
        pick: (Vec3, Vec3) -> Double,
    ): ScalarRef =
        op(solid) {
            val b = Geom3.bounds((it[0] as SolidValue).solid.mesh) ?: return@op EvalResult.Invalid("the solid has no mesh")
            EvalResult.Ok(ScalarValue(Quantity.mm(pick(b.first, b.second))))
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

    /**
     * A line's own origin point. Together with [lineDirection] this is what turns a *drawn* line into a
     * revolve axis (OP-17): the axis is then an ordinary pair of derived nodes, so it moves with the line
     * and the line stays the thing the user picked.
     */
    fun lineOrigin(l: LineRef): PointRef = op(l) { EvalResult.Ok(PointValue(ln(it[0]).origin)) }

    /** A line's unit direction — the other half of an axis built from a picked line. */
    fun lineDirection(l: LineRef): DirectionRef =
        op(l) { EvalResult.Ok(DirectionValue(Direction(ln(it[0]).dir))) }

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

    /** The full circle an arc lies on — the carrier coercion a radius measurement or a radial dimension needs. */
    fun circleOfArc(a: ArcRef): CircleRef =
        op(a) {
            val arc = (it[0] as ArcValue).arc
            EvalResult.Ok(CircleValue(Circle(arc.center, arc.radius)))
        }

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

fun Evaluator.frame(ref: FrameRef): FrameValue = valueOf(ref) as FrameValue

fun Evaluator.scalar(ref: ScalarRef): Quantity = (valueOf(ref) as ScalarValue).q

fun Evaluator.line(ref: LineRef): Line = (valueOf(ref) as LineValue).line

fun Evaluator.segment(ref: SegmentRef): Segment = (valueOf(ref) as SegmentValue).seg

fun Evaluator.circle(ref: CircleRef): Circle = (valueOf(ref) as CircleValue).circle

fun Evaluator.arc(ref: ArcRef): Arc = (valueOf(ref) as ArcValue).arc

fun Evaluator.ray(ref: RayRef): Ray = (valueOf(ref) as RayValue).ray

fun Evaluator.direction(ref: DirectionRef): Direction = (valueOf(ref) as DirectionValue).dir

fun Evaluator.bezier(ref: BezierRef): Bezier = (valueOf(ref) as BezierValue).bezier

fun Evaluator.profile(ref: ProfileRef): Profile = (valueOf(ref) as ProfileValue).profile

fun Evaluator.loop(ref: LoopRef): Loop = (valueOf(ref) as LoopValue).loop

fun Evaluator.region(ref: RegionRef): Region = (valueOf(ref) as RegionValue).region

fun Evaluator.plane(ref: PlaneRef): Plane3 = (valueOf(ref) as PlaneValue).plane

fun Evaluator.sketch(ref: SketchRef): Sketch3 = (valueOf(ref) as SketchValue).sketch

fun Evaluator.solid(ref: SolidRef): constructit.geom.Solid3 = (valueOf(ref) as SolidValue).solid

fun Evaluator.pointSet(ref: PointSetRef): constructit.geom.PointSet = (valueOf(ref) as PointSetValue).set
