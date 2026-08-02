package constructit.dsl

import constructit.core.ArcValue
import constructit.core.BezierValue
import constructit.core.CircleValue
import constructit.core.DirectionValue
import constructit.core.EllipseValue
import constructit.core.EllipticArcValue
import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.FrameValue
import constructit.core.IndirectNode
import constructit.core.InstanceNode
import constructit.core.LineValue
import constructit.core.LoopValue
import constructit.core.Node
import constructit.core.OpNode
import constructit.core.Path3Value
import constructit.core.PlaneValue
import constructit.core.Point3Value
import constructit.core.PointSetValue
import constructit.core.PointValue
import constructit.core.ProfileValue
import constructit.core.RayValue
import constructit.core.RegionValue
import constructit.core.ScalarValue
import constructit.core.SectionValue
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
import constructit.geom.CarrierCurve
import constructit.geom.Circle
import constructit.geom.Conics
import constructit.geom.Curves3
import constructit.geom.Direction
import constructit.geom.Ellipse
import constructit.geom.EllipticArc
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Justification
import constructit.geom.Line
import constructit.geom.LoftGuide
import constructit.geom.LoftSection
import constructit.geom.Loop
import constructit.geom.Mesh3
import constructit.geom.MeshBool
import constructit.geom.Path3
import constructit.geom.Plane3
import constructit.geom.Profile
import constructit.geom.ProfileElement
import constructit.geom.Ray
import constructit.geom.Region
import constructit.geom.Section3
import constructit.geom.Segment
import constructit.geom.Silhouette
import constructit.geom.Sketch3
import constructit.geom.Solid3
import constructit.geom.SolidFace
import constructit.geom.SweepProfile
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.geom.Watertight
import constructit.geom.Xform3
import constructit.geom.movedBy
import constructit.geom.thickNetwork
import constructit.units.Dimension
import constructit.units.DimensionError
import constructit.units.Quantity
import kotlin.math.abs
import kotlin.math.pow

/**
 * How finely an opening on a **curved** wall leg is sampled into its plan rectangle's curved twin (the
 * OP-21 extension). One per degree of a full turn at a metre radius, which is far below the tolerance the
 * boolean it feeds tessellates at anyway (OP-22).
 */
private const val CURVED_INTERVAL_STEPS = 24

/**
 * How far a cutter on a **curved** wall leg overhangs the wall's faces (mm) — see [ThickLeg.cutterOffsets].
 *
 * Ten times the tessellation tolerance, which is the whole of the reasoning: the wall's face and the
 * cutter's are both chord polylines inscribed in their arcs, each within `TESS_TOL_MM` of it, so anything
 * comfortably above that tolerance puts the cutter's boundary *clear* of the material rather than
 * *almost on* it — and "almost on" is the one input a boolean kernel cannot resolve without slivers.
 */
private const val CUTTER_MARGIN_MM = 10 * GeomMath.TESS_TOL_MM

/** A typed handle to a node's output. Compile-time typing over the generic graph (OP-5). */
class Ref<out V : Value>(val node: Node)

typealias ScalarRef = Ref<ScalarValue>
typealias PointRef = Ref<PointValue>
typealias LineRef = Ref<LineValue>
typealias SegmentRef = Ref<SegmentValue>
typealias CircleRef = Ref<CircleValue>
typealias ArcRef = Ref<ArcValue>

/** A first-class conic (OP-24): an ellipse, and a piece of one trimmed by parametric angle. */
typealias EllipseRef = Ref<EllipseValue>
typealias EllipticArcRef = Ref<EllipticArcValue>
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

/** A **section** of a solid at a plane: a compound value with accessors (OP-6, OP-17). */
typealias SectionRef = Ref<SectionValue>

/** A point in space: a plane point plus a height along that plane's normal — see [Construction.heightPoint]. */
typealias Point3Ref = Ref<Point3Value>

/** A **curve in space** (OP-26) — see [Construction.pathThrough] and [constructit.geom.Path3]. */
typealias Path3Ref = Ref<Path3Value>

/**
 * One input of a [Construction.loft] — a section of the run, or a guide that shapes it (OP-17).
 *
 * Which part each input *is* is **structure**, fixed when the node is built, and the values are read inside
 * `compute` (OP-21's rule): the loft node's input list is this list flattened, and the loft's own closure
 * walks the same layout back. So a three-section loft with two guides is one node with one recompute, and the
 * count of sections is not something a value can change.
 */
sealed interface LoftPart {
    /** An area section: [sketch] is one region on the plane of the space it was drawn in. */
    class Area(val sketch: SketchRef) : LoftPart

    /**
     * A **point** section: one [Construction.heightPoint] — a base point on a plane, lifted by a scalar.
     *
     * *One node*, not the (plane, point, height) triple this used to inline: "the apex of an extruded outline
     * is exactly such a point" (the user's words), so the apex is a **consumer** of the general height point
     * rather than a construction welded into the loft. What that buys is everything the height point is:
     * drag the base and the pyramid leans, retype the height — an ordinary named scalar, wireable through
     * `boundTo` — and it grows, drag the apex *in the 3D view* and the height follows the ray (OP-25), and
     * sharing the node makes two solids hang off one apex (OP-5 — sharing a node is equality).
     */
    class Apex(val point: Point3Ref) : LoftPart

    /**
     * A **guide** curve: [curve] read in [plane]'s coordinates — a segment, an arc, a Bézier, a profile or a
     * loop. Where it attaches is not stated here; it is the boundary point it passes through, found inside
     * `compute` (see [constructit.geom.Geom3.loft]).
     */
    class Guide(val plane: PlaneRef, val curve: Ref<*>) : LoftPart
}

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

    /**
     * The point dividing `a → b` in ratio [t]: `P = a + t·(b − a)`.
     *
     * [t] is **dimensionless** — a share of the span, not a length — which is what makes one `t` node feeding
     * several pairs mean *equal proportions by construction* (OP-5: sharing a node is equality). `t = 0.5` is
     * exactly [midpoint]; outside `[0, 1]` the point extrapolates beyond `a` or `b`, which is honest rather
     * than clamped: a construction that reaches past its span is a construction, not an error.
     */
    fun pointAtRatio(
        a: PointRef,
        b: PointRef,
        t: ScalarRef,
    ): PointRef =
        op(a, b, t) {
            val pa = pt(it[0])
            val pb = pt(it[1])
            val f = sc(it[2]).requireDim(Dimension.NONE, "ratio").value
            EvalResult.Ok(PointValue(pa + (pb - pa) * f))
        }

    /**
     * Where [point] sits along [line], as the line's own parameter: `point · dir`.
     *
     * The inverse view of [pointOnLineAt]/`alongLine` — the number a rider on that line stores (OP-20's
     * carrier-anchored form, measured from the point of the line nearest the world origin). Its use is to
     * express one position along a carrier **relative to another**: `t = lineParam(line, base) + d` is the
     * point at signed distance `d` from `base`, stated in the rider's own parameter, so nothing that already
     * refers to the rider is rewired (OP-5).
     */
    fun lineParam(
        line: LineRef,
        point: PointRef,
    ): ScalarRef =
        op(line, point) { EvalResult.Ok(ScalarValue(Quantity.mm(pt(it[1]).dot(ln(it[0]).dir)))) }

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

    // ---- conics (OP-24) ----
    // Every input a node, exactly as a circle's centre and radius are: the centre is a point, the
    // orientation *and* the first semi-axis come from a second point, and the second semi-axis is
    // either a scalar or a third point. So binding the axis-end point onto a line's direction turns
    // the ellipse with that line, and sharing the `b` parameter makes two ellipses equally tall by
    // construction (OP-5 — sharing a node is equality).

    /**
     * The ellipse centred at [center] whose own +u axis runs through [axisEnd] — which fixes both the
     * orientation and the semi-axis `a` — with semi-axis `b` given as a scalar.
     *
     * The frame is deliberately the *picked* one and is never renormalised to `a ≥ b`; see [Ellipse].
     */
    fun ellipseCAB(
        center: PointRef,
        axisEnd: PointRef,
        b: ScalarRef,
    ): EllipseRef =
        op(center, axisEnd, b) {
            val c = pt(it[0])
            val d = pt(it[1]) - c
            val a = d.length()
            val bb = sc(it[2]).requireDim(Dimension.LENGTH, "semi-axis").mm
            when {
                a < Vec2.EPS -> EvalResult.Invalid("an ellipse's axis end coincides with its centre, so it has no size and no direction")
                bb <= 0.0 -> EvalResult.Invalid("an ellipse needs a positive second semi-axis")
                else -> EvalResult.Ok(EllipseValue(Ellipse(c, a, bb, d.angle())))
            }
        }

    /**
     * The same ellipse with `b` read off a **third point**: the distance of [bPoint] from the axis through
     * [center] and [axisEnd]. A point rather than a number, so the third click is as much a node as the
     * first two — drag it and the ellipse gets taller.
     */
    fun ellipseCAP(
        center: PointRef,
        axisEnd: PointRef,
        bPoint: PointRef,
    ): EllipseRef =
        op(center, axisEnd, bPoint) {
            val c = pt(it[0])
            val d = pt(it[1]) - c
            val a = d.length()
            if (a < Vec2.EPS) {
                return@op EvalResult.Invalid("an ellipse's axis end coincides with its centre, so it has no size and no direction")
            }
            val u = d * (1.0 / a)
            val bb = abs((pt(it[2]) - c).cross(u))
            if (bb <= Vec2.EPS) {
                EvalResult.Invalid("the third point lies on the ellipse's own axis, so there is no second semi-axis")
            } else {
                EvalResult.Ok(EllipseValue(Ellipse(c, a, bb, d.angle())))
            }
        }

    /**
     * The point of [ellipse] at **parametric angle** [t] — the exact position-along an ellipse offers
     * (OP-24), and the DOF a rider on one stores. Plain trigonometry, so it is as exact as a point on a
     * circle at a given angle.
     */
    fun pointOnEllipse(
        ellipse: EllipseRef,
        t: ScalarRef,
    ): PointRef =
        op(ellipse, t) {
            val e = (it[0] as EllipseValue).ellipse
            val a = sc(it[1]).requireDim(Dimension.ANGLE, "parameter").base
            EvalResult.Ok(PointValue(Conics.pointAt(e, a)))
        }

    /** The tangent line of [ellipse] at parametric angle [t] — exact, and a line like any other. */
    fun tangentOnEllipse(
        ellipse: EllipseRef,
        t: ScalarRef,
    ): LineRef =
        op(ellipse, t) {
            val e = (it[0] as EllipseValue).ellipse
            val a = sc(it[1]).requireDim(Dimension.ANGLE, "parameter").base
            val d = Conics.tangentAt(e, a)
            if (d.length() < Vec2.EPS) {
                EvalResult.Invalid("the ellipse is degenerate, so it has no tangent there")
            } else {
                EvalResult.Ok(LineValue(Line(Conics.pointAt(e, a), d.normalized())))
            }
        }

    /** The (outward) normal line of [ellipse] at parametric angle [t] — exact. */
    fun normalOnEllipse(
        ellipse: EllipseRef,
        t: ScalarRef,
    ): LineRef =
        op(ellipse, t) {
            val e = (it[0] as EllipseValue).ellipse
            val a = sc(it[1]).requireDim(Dimension.ANGLE, "parameter").base
            EvalResult.Ok(LineValue(Line(Conics.pointAt(e, a), Conics.normalAt(e, a))))
        }

    private fun carrierEllipse(v: Value): Ellipse? =
        when (v) {
            is EllipseValue -> v.ellipse
            is EllipticArcValue -> v.arc.ellipse
            else -> null
        }

    /**
     * The piece of [curve] between [from] and [to], swept counter-clockwise **in the parameter** unless
     * [ccw] is false (OP-24) — the exact twin of [arcBetween], accepting an ellipse or an elliptic arc
     * as the carrier.
     *
     * Both cut points are **projected to the nearest point of the ellipse** rather than required to lie
     * on it, for the reason [segmentBetween] gives: a cut point is normally constructed *from* the curve,
     * so exact incidence is unattainable in floating point. [ccw] is a stored discrete branch choice
     * exactly like a `Select` sign (OP-1) — which of the two ways round is meant is decided once.
     */
    fun ellipticArcBetween(
        curve: Ref<*>,
        from: PointRef,
        to: PointRef,
        ccw: Boolean = true,
    ): EllipticArcRef =
        op(curve, from, to) {
            val e = carrierEllipse(it[0]) ?: return@op EvalResult.Invalid("not an elliptic curve")
            val t0 = Conics.paramOf(e, pt(it[1]))
            val t1 = Conics.paramOf(e, pt(it[2]))
            val arc = EllipticArc(e, t0, t1, ccw)
            if (abs(Conics.sweep(arc)) < Vec2.EPS) {
                EvalResult.Invalid("degenerate trim: the cut points coincide")
            } else {
                EvalResult.Ok(EllipticArcValue(arc))
            }
        }

    /** The whole ellipse an elliptic arc lies on — the carrier coercion a measurement or a rider needs. */
    fun ellipseOfArc(a: EllipticArcRef): EllipseRef =
        op(a) { EvalResult.Ok(EllipseValue((it[0] as EllipticArcValue).arc.ellipse)) }

    fun ellipseCenter(e: EllipseRef): PointRef =
        op(e) { EvalResult.Ok(PointValue((it[0] as EllipseValue).ellipse.center)) }

    /**
     * The point of [e] at parametric angle `k·π/2` — its four **axis endpoints** (OP-24's key points):
     * `k = 0` and `2` are the ends of the `a` axis, `1` and `3` of the `b` axis. `k` is structural, like
     * every other sub-entity index (OP-8).
     */
    fun ellipseAxisPoint(
        e: EllipseRef,
        k: Int,
    ): PointRef =
        op(e) { EvalResult.Ok(PointValue(Conics.pointAt((it[0] as EllipseValue).ellipse, k * kotlin.math.PI / 2.0))) }

    /** The semi-axis of [e] along its own +u — the one its axis-end point sets (a length, exact). */
    fun measureSemiAxisA(e: EllipseRef): ScalarRef =
        op(e) { EvalResult.Ok(ScalarValue(Quantity.mm((it[0] as EllipseValue).ellipse.a))) }

    /** The semi-axis of [e] perpendicular to its own +u (a length, exact). */
    fun measureSemiAxisB(e: EllipseRef): ScalarRef =
        op(e) { EvalResult.Ok(ScalarValue(Quantity.mm((it[0] as EllipseValue).ellipse.b))) }

    /**
     * The **measured length** of an elliptic arc (OP-4) — numeric, to [Conics.LENGTH_TOL_MM], and the one
     * genuinely approximate reading of a conic in this package (OP-15). Everything else about an elliptic
     * arc — where a point on it is, which way it leaves, what area it encloses — is exact.
     */
    fun measureEllipticArcLength(a: EllipticArcRef): ScalarRef =
        op(a) { EvalResult.Ok(ScalarValue(Quantity.mm(Conics.arcLength((it[0] as EllipticArcValue).arc)))) }

    /** The whole circumference of [e] — the same numeric integral over a full turn (OP-15). */
    fun measureCircumference(e: EllipseRef): ScalarRef =
        op(e) { EvalResult.Ok(ScalarValue(Quantity.mm(Conics.circumference((it[0] as EllipseValue).ellipse)))) }

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

    /** Line ∩ ellipse (OP-24): the ordinary two-branch set, ordered along the line's own direction. */
    fun intersectLE(
        line: LineRef,
        e: EllipseRef,
    ): PointSetRef =
        op(line, e) { EvalResult.Ok(PointSetValue(Conics.intersectLE((it[0] as LineValue).line, (it[1] as EllipseValue).ellipse))) }

    /**
     * Circle ∩ ellipse (OP-24): **up to four** solutions, ordered by ascending parametric angle on the
     * **circle** — the first operand, which is the convention [Conics.intersect] states.
     */
    fun intersectCE(
        c: CircleRef,
        e: EllipseRef,
    ): PointSetRef =
        op(c, e) {
            EvalResult.Ok(PointSetValue(Conics.intersect(Conics.ofCircle((it[0] as CircleValue).circle), (it[1] as EllipseValue).ellipse)))
        }

    /**
     * Ellipse ∩ ellipse (OP-24): **up to four** solutions, ordered by ascending parametric angle on the
     * first operand — see [Conics.intersect] for the quartic and for why that ordering is the one.
     */
    fun intersectEE(
        e1: EllipseRef,
        e2: EllipseRef,
    ): PointSetRef =
        op(e1, e2) {
            EvalResult.Ok(PointSetValue(Conics.intersect((it[0] as EllipseValue).ellipse, (it[1] as EllipseValue).ellipse)))
        }

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

    /**
     * Pick branch [index] of a solution set — the **four-branch** form of [select] (OP-1, OP-24).
     *
     * An *index*, not a pair of composed binary signs, and the difference is not cosmetic. The
     * session-17 LLL circle stores two signs because its construction genuinely factors into two
     * independent binary choices, one per bisector, each with a geometric meaning of its own. A quartic
     * intersection factors into nothing: its four points are one ordered set (the ordering convention is
     * [Conics.intersect]'s), so two binary signs would only re-encode an index — and would re-encode it
     * *worse*, because when a parameter edit leaves two solutions instead of four, "which composed pair
     * survived" has no answer while "index 2 of a two-element set" has a clean one: invalid, with a
     * reason, healing the moment the fourth solution comes back (OP-3). A step's `signs=` already carries
     * arbitrary integers, so this needed no new file argument.
     *
     * The index is **structural** and taken verbatim on replay; the *ordering* is a stated function of
     * the operands' values. So a branch never silently becomes another branch — but an edit that
     * re-sorts the set does move which point index 1 names, which is what an ordered solution set means
     * and is recorded as such.
     */
    fun selectAt(
        set: PointSetRef,
        index: Int,
        emptyReason: String = "empty intersection",
    ): PointRef =
        op(set) {
            val pts = (it[0] as PointSetValue).set.points
            when {
                pts.isEmpty() -> EvalResult.Invalid(emptyReason)
                index < 0 || index >= pts.size ->
                    EvalResult.Invalid(
                        "this crossing has ${pts.size} solution(s) now, so branch ${index + 1} is gone — " +
                            "move the curves back until it exists, or take the intersection again",
                    )
                else -> EvalResult.Ok(PointValue(pts[index]))
            }
        }

    /** How many solutions [set] currently holds — what a four-branch intersection asks before it selects. */
    fun solutionCount(
        set: PointSetRef,
        ev: Evaluator,
    ): Int = ((ev.eval(set.node) as? EvalResult.Ok)?.value as? PointSetValue)?.set?.points?.size ?: 0

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

    /** The arc length of a circular arc — **exact**, `r·|sweep|`, unlike its elliptic cousin (OP-15). */
    fun measureArcLength(a: ArcRef): ScalarRef =
        op(a) {
            val arc = (it[0] as ArcValue).arc
            EvalResult.Ok(ScalarValue(Quantity.mm(abs(GeomMath.sweep(arc)) * arc.radius)))
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
                        is EllipticArcValue -> ProfileElement.EllipticArcE(v.arc)
                        else -> throw IllegalArgumentException("profile element must be a segment, arc, Bézier or elliptic arc")
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

    /**
     * Control point [i] (0..3) of [b] — the sub-entity accessor (OP-8) for the two *inner* controls, which
     * [bezierStart] and [bezierEnd] only cover the ends of.
     *
     * It exists because a **split** has to be a construction over the curve's own controls: de Casteljau's
     * intermediate points are ratio points between them, and a Bézier whose controls are not points of the
     * drawing (a mirrored spline, a curve a macro built) has no other way to name them.
     */
    fun bezierControl(
        b: BezierRef,
        i: Int,
    ): PointRef =
        op(b) {
            val z = (it[0] as BezierValue).bezier
            EvalResult.Ok(
                PointValue(
                    when (i) {
                        0 -> z.p0
                        1 -> z.p1
                        2 -> z.p2
                        else -> z.p3
                    },
                ),
            )
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
                        is BezierValue -> ProfileElement.BezierE(v.bezier)
                        is EllipticArcValue -> ProfileElement.EllipticArcE(v.arc)
                        is EllipseValue -> ProfileElement.EllipseE(v.ellipse)
                        else -> return@op EvalResult.Invalid("a loop piece must be a segment, an arc, a circle, an ellipse or a Bézier")
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
     * The footprint of a **thick network** (the OP-21 extension): the offset region of [thickness] around a
     * *connected graph* of carrier [curves], each thickened to the side [sides] gives it.
     *
     * The same purity rule as [thickFootprint], one level harder: for a polyline only the mitres depend on
     * where the carrier is, but for a network the **topology** does — which endpoints coincide decides the
     * cyclic order at every vertex and hence what the boundary even is. So the welding, the connectivity
     * check and the whole walk happen inside `compute`, and only the *count* of carrier curves and their
     * sides are structural. Pulling two carrier ends apart therefore makes this invalid with a reason
     * (OP-3), and pushing them back together heals it.
     *
     * Junctions are shared carrier vertices and need no merge: a branch of *k* curves is resolved as *k*
     * ordinary corners, one per angularly adjacent pair (see `thickNetwork`).
     */
    fun thickNetworkFootprint(
        curves: List<Ref<*>>,
        sides: List<Justification>,
        thickness: ScalarRef,
    ): RegionRef =
        op(*(curves + thickness).toTypedArray()) { args ->
            val carriers = carrierCurves(args.dropLast(1), sides) ?: return@op EvalResult.Invalid("a wall's carrier must be curves")
            val t = (args.last() as ScalarValue).q.requireDim(Dimension.LENGTH, "thickness").mm
            val (body, why) = thickNetwork(carriers, t)
            if (body == null) EvalResult.Invalid(why ?: "no footprint") else EvalResult.Ok(RegionValue(body.region))
        }

    /**
     * The footprint of **one interval feature** of a thick network (the OP-21 extension's 3D half): the plan
     * shape of the box a 3D opening subtracts, spanning [width] from [position] **in arc length** along leg
     * [legIndex], across the wall's whole thickness.
     *
     * The twin of [intervalFootprint] for the general carrier, and the reason a *Cut opening* works on a
     * curved wall with no case of its own: on an arc leg the two faces come out as concentric arcs closed by
     * two radial segments, because they are read off the same [ThickLeg] the jamb is.
     */
    fun networkIntervalFootprint(
        curves: List<Ref<*>>,
        sides: List<Justification>,
        thickness: ScalarRef,
        legIndex: Int,
        position: ScalarRef,
        width: ScalarRef,
    ): RegionRef =
        op(*(curves + listOf(thickness, position, width)).toTypedArray()) { args ->
            val carriers =
                carrierCurves(args.dropLast(3), sides) ?: return@op EvalResult.Invalid("a wall's carrier must be curves")
            val t = (args[args.size - 3] as ScalarValue).q.requireDim(Dimension.LENGTH, "thickness").mm
            val pos = (args[args.size - 2] as ScalarValue).q.requireDim(Dimension.LENGTH, "position").mm
            val w = (args[args.size - 1] as ScalarValue).q.requireDim(Dimension.LENGTH, "width").mm
            if (w <= 0.0) return@op EvalResult.Invalid("an opening needs a positive width")
            val (body, why) = thickNetwork(carriers, t)
            if (body == null) return@op EvalResult.Invalid(why ?: "no footprint")
            if (legIndex < 0 || legIndex >= body.legCount) return@op EvalResult.Invalid("leg ${legIndex + 1} is not a leg of this wall")
            val leg = body.legs[legIndex]
            // A straight leg's opening is the rectangle it always was, sharing the wall's own faces exactly
            // (OP-21). A curved one is sampled — and *overhangs* the wall instead of sharing its faces, so
            // that two tessellations of one arc never end up near-coincident: see [ThickLeg.cutterOffsets].
            val steps = if (leg.piece is ProfileElement.Seg) 1 else CURVED_INTERVAL_STEPS
            val across = leg.cutterOffsets(CUTTER_MARGIN_MM)
            val near = (0..steps).map { leg.offsetPoint(pos + w * it / steps, across[0]) }
            val far = (0..steps).map { leg.offsetPoint(pos + w * (steps - it) / steps, across[1]) }
            val ring = near + far
            val pieces = ring.indices.map { ProfileElement.Seg(Segment(ring[it], ring[(it + 1) % ring.size])) }
            EvalResult.Ok(RegionValue(Region(GeomMath.orient(Loop(pieces), ccw = true), emptyList())))
        }

    /** Values as carrier curves with their per-curve sides, or null if any pick is not a curve. */
    private fun carrierCurves(
        args: List<Value>,
        sides: List<Justification>,
    ): List<CarrierCurve>? =
        args.mapIndexed { i, v ->
            val piece =
                when (v) {
                    is SegmentValue -> ProfileElement.Seg(v.seg)
                    is ArcValue -> ProfileElement.ArcE(v.arc)
                    is BezierValue -> ProfileElement.BezierE(v.bezier)
                    is CircleValue -> ProfileElement.CircleE(v.circle)
                    is EllipticArcValue -> ProfileElement.EllipticArcE(v.arc)
                    is EllipseValue -> ProfileElement.EllipseE(v.ellipse)
                    else -> return null
                }
            CarrierCurve(piece, sides.getOrElse(i) { Justification.CENTER })
        }

    /**
     * Corner [index] of the region [r] — an **OP-6 provenance accessor** over a footprint (the OP-21
     * extension's *key points*).
     *
     * Corners are numbered outer boundary first, then each hole, in the region's own order, and the index is
     * **structural**: it is fixed when the accessor is created, exactly as the count of a Bézier's control
     * points or of an array's copies is. An edit that leaves the footprint with fewer corners makes this
     * invalid **with a reason** (OP-3) rather than silently pointing at a different corner — which is the
     * honest trade for not regenerating a set of elements whose size is a function of values.
     */
    fun regionCorner(
        r: RegionRef,
        index: Int,
    ): PointRef =
        op(r) {
            val region = (it[0] as RegionValue).region
            val corners = (listOf(region.outer) + region.holes).flatMap { l -> l.elements.map { e -> GeomMath.startOf(e) } }
            if (index < 0 || index >= corners.size) {
                EvalResult.Invalid("this area has ${corners.size} corners, so corner ${index + 1} is gone — extract its key points again")
            } else {
                EvalResult.Ok(PointValue(corners[index]))
            }
        }

    /** How many corners [r] currently has — what a *Key points* extraction asks before it creates any. */
    fun regionCornerCount(
        r: RegionRef,
        ev: Evaluator,
    ): Int {
        val region = ((ev.eval(r.node) as? EvalResult.Ok)?.value as? RegionValue)?.region ?: return 0
        return (listOf(region.outer) + region.holes).sumOf { it.elements.size }
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

    /**
     * A **datum plane**: the plane containing the carrier of [line] — a 2D line read in [base]'s own
     * (u, v) — rotated by [angle] about it, out of [base] (OP-17's datum extension, GitHub #6).
     *
     * The general answer to "sketch somewhere else", of which sketch-on-face is the special case (the line
     * a boundary segment, the angle 90°) and [planeOffset] is the *parallel* one. Every input is a node:
     * the line is the drawn line the user picked, so the datum follows it, and the angle is an ordinary
     * scalar parameter, so retyping it tilts the plane and every feature sketched on it. Which side counts
     * as "out" is the **sign of the angle** — see [constructit.geom.Geom3.datumPlane] for the frame, the
     * origin's anchoring rule and what is refused.
     */
    fun datumPlane(
        base: PlaneRef,
        line: LineRef,
        angle: ScalarRef,
    ): PlaneRef =
        op(base, line, angle) {
            val a = sc(it[2]).requireDim(Dimension.ANGLE, "sketch plane angle").base
            val (p, why) = Geom3.datumPlane((it[0] as PlaneValue).plane, ln(it[1]), a)
            if (p == null) EvalResult.Invalid(why ?: "cannot place that sketch plane") else EvalResult.Ok(PlaneValue(p))
        }

    /** [plane] with its normal reversed (and its in-plane frame mirrored, as it must be). */
    fun planeFlipped(plane: PlaneRef): PlaneRef =
        op(plane) { EvalResult.Ok(PlaneValue((it[0] as PlaneValue).plane.flipped())) }

    /**
     * [plane] with its **origin moved inside itself** — to the in-plane point [at], plus the in-plane offset
     * ([dx], [dy]) — keeping its axes and its normal exactly as they are (OP-17's space origin).
     *
     * The two layers a sketch space's origin is made of, as one node. [at] is the *anchor*: an ordinary point
     * node in this plane's own coordinates, so anchoring on a corner of the part means the frame follows that
     * corner through every edit, and the default (0, 0) is the frame's own intrinsic origin. [dx] and [dy]
     * are ordinary scalars, hence nameable, editable and wireable like any parameter.
     *
     * Only the origin moves, deliberately: a *rotation* would change what every recorded coordinate on the
     * plane means, while a translation moves the drawing rigidly with the frame — which is exactly the
     * "re-anchoring translates the sketch" reading the space work is built on.
     */
    fun planeAnchored(
        plane: PlaneRef,
        at: PointRef,
        dx: ScalarRef,
        dy: ScalarRef,
    ): PlaneRef =
        op(plane, at, dx, dy) {
            val p = (it[0] as PlaneValue).plane
            val a = pt(it[1])
            val x = sc(it[2]).requireDim(Dimension.LENGTH, "space origin dx").mm
            val y = sc(it[3]).requireDim(Dimension.LENGTH, "space origin dy").mm
            EvalResult.Ok(PlaneValue(Plane3(p.toWorld(Vec2(a.x + x, a.y + y)), p.u, p.v)))
        }

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

    /**
     * The same embedding as [sketchOn], seen **from behind**: the very same points in the world, on
     * [plane]'s flipped frame — so an [extrude] of it sweeps the plane's **−normal**.
     *
     * This is how *Cut* goes into the material (`Document.cutOnFace`), and the reason it is a sketch rather
     * than an offset plane the sweep runs back from is **exactness**: starting `depth` behind the plane and
     * sweeping forward lands the tool's cap on the face only up to rounding (the in-plane terms are added
     * before the offset cancels), and a cap a femtometre off a face it is meant to be flush with is exactly
     * the near-tangency the general boolean cannot close. Flipping the frame and mirroring the drawing in it
     * is bit-exact — a negation and a product of negations — so the cap lies *on* the face, as it did when a
     * face plane still pointed inwards.
     *
     * Mirroring keeps each loop's own orientation ([GeomMath.transform] re-orients after a reflection), so
     * the sketch is a legal one: outer boundaries still run counter-clockwise in the frame they are read in.
     */
    fun sketchBehind(
        plane: PlaneRef,
        vararg regions: RegionRef,
    ): SketchRef =
        op(plane, *regions) { args ->
            if (regions.isEmpty()) return@op EvalResult.Invalid("a sketch needs at least one region")
            val p = (args[0] as PlaneValue).plane
            val m = Affine(1.0, 0.0, 0.0, -1.0, 0.0, 0.0)
            val rs =
                args.drop(1).map { v ->
                    val r = (v as RegionValue).region
                    Region(GeomMath.transform(r.outer, m), r.holes.map { GeomMath.transform(it, m) })
                }
            EvalResult.Ok(SketchValue(Sketch3(p.flipped(), rs)))
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

    /**
     * A **height point** (OP-25): the point standing [height] mm off [plane] above the plane point [base] —
     * `embed(base) + h · n̂`, the user's own formula.
     *
     * *"One could construct an arbitrary free point in 3D from a point in 2D and a given height parameter.
     * This 3D point has 1 dof over its base point — the height."* That is the whole node: a pure function of
     * three inputs, so it needs no new machinery anywhere. The height is an **ordinary scalar** — a named
     * parameter in the panel, renameable, wireable onto another parameter through `boundTo` (OP-7/OP-16) —
     * and the base is a point like any other, draggable wherever it lives. Nothing here is a constraint: the
     * point *is* the formula.
     *
     * [sign] is ±1 and **structural**, decided by the operation that builds this and never by a value: it
     * says which way "up" is for the space this was built in, exactly as *Extrude*'s does. A face plane's
     * normal points into the material (OP-8), so a face-space height point takes −1 and the user's typed
     * height stays a positive number standing *out* of the face. It is re-derived from the space on replay,
     * so nothing has to be persisted for it.
     */
    fun heightPoint(
        plane: PlaneRef,
        base: PointRef,
        height: ScalarRef,
        sign: Int = 1,
    ): Point3Ref =
        op(plane, base, height) {
            val pl = (it[0] as PlaneValue).plane
            val h = sc(it[2]).requireDim(Dimension.LENGTH, "height").mm
            EvalResult.Ok(Point3Value(pl.toWorld(pt(it[1])) + pl.normal.normalized() * (sign * h)))
        }

    /**
     * A **curve in space through [points]** (OP-26's first source): the path that passes through every one of
     * them, in order — straight from point to point, or [smooth] (an interpolating cubic), and [closed] or
     * open.
     *
     * **One node over the points themselves**, which is the whole of the parenting rule paying out. Each
     * input is an existing point in space — a height point (OP-25) today — so clicking one *shares its node*:
     * drag the base of a point the curve runs through, or retype its height, and the curve follows, exactly as
     * every other consumer of a shared node does. Nothing is copied, so there is nothing to keep in step.
     *
     * **[closed] and [smooth] are structural**, decided when the node is built and never read out of a value
     * (OP-21's rule): they say *how many pieces there are and of what kind*, so a different answer is a
     * different construction, re-run rather than edited. The positions are the values, read inside `compute`,
     * which is why dragging a point recomputes this one node and rebuilds nothing.
     *
     * Invalid, with a reason that heals (OP-3), when two consecutive points **coincide**: a zero-length piece
     * has no direction, so an interpolation through it has no tangent and a sweep along it would have no
     * frame. That is a condition on *values* — drag the two points apart and the curve comes back — which is
     * exactly why it is checked here rather than refused at build time (the gesture's own refusals, about how
     * many points there are, live in `Document.curveThroughPoints`).
     */
    fun pathThrough(
        points: List<Point3Ref>,
        closed: Boolean = false,
        smooth: Boolean = false,
    ): Path3Ref =
        op(*points.toTypedArray()) { args ->
            val pts = args.map { (it as Point3Value).p }
            val n = pts.size
            val spans = if (closed) n else n - 1
            for (i in 0 until spans) {
                val a = pts[i]
                val b = pts[(i + 1) % n]
                if ((b - a).length() <= Vec3.EPS) {
                    return@op EvalResult.Invalid(
                        "points ${i + 1} and ${(i + 1) % n + 1} of this curve are in the same place, " +
                            "so the piece between them has no direction — move one of them",
                    )
                }
            }
            val elements = if (smooth) Curves3.smoothThrough(pts, closed) else Curves3.straightThrough(pts, closed)
            if (elements.isEmpty()) return@op EvalResult.Invalid("a curve needs at least two points")
            EvalResult.Ok(Path3Value(Path3(elements, closed)))
        }

    /**
     * A **tube along [path]**: a circle of [radius] carried along the curve in its moving frame (OP-26,
     * step 2).
     *
     * The tube first, because it is what proves the frame: a circular section is the one profile whose
     * *shape* cannot hide a frame that flips, so a tube that comes out watertight along a line–arc–line run
     * is the frame's own test rather than the profile's. Everything else about it is [sweep]'s.
     */
    fun tube(
        path: Path3Ref,
        space: PlaneRef,
        radius: ScalarRef,
        roll: ScalarRef,
        twist: ScalarRef,
    ): SolidRef =
        op(path, space, radius, roll, twist) {
            val r = sc(it[2]).requireDim(Dimension.LENGTH, "tube radius").mm
            sweptSolid(it, SweepProfile.Round(r))
        }

    /**
     * A **sweep**: the closed area [profile] carried along [path] in its moving frame (OP-26, step 2).
     *
     * One node for the whole solid, exactly as [extrude] is one, and every input a node: drag a point the
     * path runs through and the sweep follows it, retype the profile's dimensions and the section changes
     * along the whole run, tilt the datum [space] was built on and the whole thing rolls with it — one
     * recompute, no rebuild (OP-21).
     *
     * **[space] is the curve's own sketch space, and it is read twice.** Its normal is where the start
     * reference direction comes from — *derived by construction* rather than guessed, which is what makes
     * "which way up is the section" an answer the drawing already contains (OP-26, [Frames3.startReference])
     * — and its plane is what the resulting body's plan is projected onto, since a sweep has no prismatic
     * reading and therefore no sketch to show as a footprint. One input, because they are one statement:
     * *the space this run belongs to*.
     *
     * [roll] turns the section about the tangent at the start, and is a **real degree of freedom stated
     * rather than compensated** — the frame has to start somewhere, and having the algorithm pick and the
     * user discover the choice by its consequences is exactly what OP-26 rejects. [twist] is the total
     * rotation about the tangent from one end to the other, spread linearly in arc length.
     *
     * Invalid with a reason that heals (OP-3) for everything geometric — see [Geom3.sweep] for the list, of
     * which the two that matter are the profile outgrowing the path's bend and a closed path whose frame
     * does not come back to itself.
     */
    fun sweep(
        path: Path3Ref,
        space: PlaneRef,
        profile: RegionRef,
        roll: ScalarRef,
        twist: ScalarRef,
    ): SolidRef =
        op(path, space, profile, roll, twist) {
            sweptSolid(it, SweepProfile.Section((it[2] as RegionValue).region))
        }

    /** The half [tube] and [sweep] share: the frame's inputs read, and the solid or the reason it is not one. */
    private fun sweptSolid(
        args: List<Value>,
        profile: SweepProfile,
    ): EvalResult {
        val plane = (args[1] as PlaneValue).plane
        val roll = sc(args[3]).requireDim(Dimension.ANGLE, "sweep roll").base
        val twist = sc(args[4]).requireDim(Dimension.ANGLE, "sweep twist").base
        val (solid, why) =
            Geom3.sweep(
                (args[0] as Path3Value).path,
                plane.normal.normalized(),
                profile,
                roll,
                twist,
                plane,
            )
        return if (solid == null) EvalResult.Invalid(why ?: "cannot sweep along this curve") else EvalResult.Ok(SolidValue(solid))
    }

    /**
     * A **loft**: [parts] — the sections in order, plus any guides — with [seams] saying where each section's
     * boundary correspondence starts (OP-17's third feature).
     *
     * One node for the whole solid, exactly as [extrude] is one: the sections are its inputs, so the pyramid
     * follows its apex and the frustum follows both its outlines with no rebuild anywhere (OP-21). What is
     * structural is *which* parts there are — a section added is a different construction, so the tool that
     * built this is re-run rather than the node edited — and what is a value is everything else, including
     * where a guide meets a section.
     *
     * [seams] is the one discrete choice, one entry per section: the index of the boundary piece the
     * correspondence starts at. Scored once from the gesture and thereafter taken verbatim from the tool step's
     * `signs=` (OP-1/OP-18), never re-scored, because the vertex nearest a click moves when the section does.
     */
    fun loft(
        parts: List<LoftPart>,
        seams: List<Int> = emptyList(),
    ): SolidRef {
        val refs = ArrayList<Ref<*>>()
        for (p in parts) {
            when (p) {
                is LoftPart.Area -> refs.add(p.sketch)
                is LoftPart.Apex -> refs.add(p.point)
                is LoftPart.Guide -> {
                    refs.add(p.plane)
                    refs.add(p.curve)
                }
            }
        }
        val layout = parts.toList()
        return op(*refs.toTypedArray()) { args ->
            var i = 0
            val sections = ArrayList<LoftSection>()
            val guides = ArrayList<LoftGuide>()
            for (p in layout) {
                when (p) {
                    is LoftPart.Area -> sections.add(LoftSection.Area((args[i++] as SketchValue).sketch))
                    is LoftPart.Apex -> sections.add(LoftSection.Apex((args[i++] as Point3Value).p))
                    is LoftPart.Guide -> {
                        val plane = (args[i++] as PlaneValue).plane
                        val curve = args[i++]
                        val pieces =
                            guidePieces(curve)
                                ?: return@op EvalResult.Invalid(
                                    "a loft's guide must be a curve — a segment, an arc, a Bézier or a chain of them",
                                )
                        guides.add(LoftGuide(plane, pieces))
                    }
                }
            }
            val (solid, why) = Geom3.loft(sections, seams, guides)
            if (solid == null) EvalResult.Invalid(why ?: "cannot loft these sections") else EvalResult.Ok(SolidValue(solid))
        }
    }

    /** A guide's value as boundary pieces — the curve kinds a loft can follow. */
    private fun guidePieces(v: Value): List<ProfileElement>? =
        when (v) {
            is SegmentValue -> listOf(ProfileElement.Seg(v.seg))
            is ArcValue -> listOf(ProfileElement.ArcE(v.arc))
            is BezierValue -> listOf(ProfileElement.BezierE(v.bezier))
            is CircleValue -> listOf(ProfileElement.CircleE(v.circle))
            is EllipticArcValue -> listOf(ProfileElement.EllipticArcE(v.arc))
            is EllipseValue -> listOf(ProfileElement.EllipseE(v.ellipse))
            is ProfileValue -> v.profile.elements
            is LoopValue -> v.loop.elements
            else -> null
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
            // **An open shell is not an operand** (the JT import note under OP-9). A boolean asks what is
            // *inside* each solid, and a surface that does not close has no inside — every answer the engine
            // could give would be a guess dressed as a result. Refused **at eval time**, because the flag is
            // a property of a *value*: OP-21's rule is structure at build time and values inside `compute`,
            // so the node exists and is invalid with a reason (OP-3) rather than the graph having depended on
            // a number. `Document.combineSolids` refuses the *gesture* on top of this, which is where the
            // body has a name to be refused by.
            openShellOf(sa)?.let { return@op EvalResult.Invalid(it) }
            openShellOf(sb)?.let { return@op EvalResult.Invalid(it) }
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
     * Why [solid] cannot be a boolean operand — an imported **open shell** — or null when it can.
     *
     * Reads the *feature*, not the mesh: the flag was derived once where the literal was built, so asking it
     * here is a field read rather than a second pass over the triangles on every recompute.
     */
    private fun openShellOf(solid: Solid3): String? {
        val f = solid.feature as? Feature3.Imported ?: return null
        f.openShell ?: return null
        return "an imported open shell cannot be a boolean operand — a boolean needs watertight solids " +
            "(the body from ${f.source} is a surface that does not close)"
    }

    // ---- imported bodies, and the placement that is generic over every solid (the JT import, OP-9) ----

    /**
     * A **solid literal**: the [mesh] a file gave us, named by the [source] it came from.
     *
     * A node with **no inputs** — which is what "literal" means in a graph whose every other solid is a
     * function of parameters. Deliberately not a [SourceNode]: a source node is a *degree of freedom*, the
     * thing a drag writes and a weld re-points, and a mesh is none of those. This is the same kind of
     * constant a pattern's count is — recorded once, replayed verbatim, never rediscovered (OP-23). The
     * memo (OP-5) then does the rest for free: an argument list that is empty is trivially the same
     * argument list next pass, so the value object is built once and handed out by pointer ever after,
     * which is exactly what the 3D view and the preview key their re-uploads on.
     *
     * The **mesh is what the step stores**, never the file's bytes: replaying a drawing must not re-run a
     * reader, or a library upgrade could silently change a drawing somebody drew a year ago.
     *
     * [pose] is the file's own placement of this body. It is applied here rather than multiplied into the
     * stored vertices, so the step keeps the file's two statements apart — these triangles, at that pose.
     */
    fun importedSolid(
        source: String,
        mesh: Mesh3,
        pose: Xform3 = Xform3.IDENTITY,
    ): SolidRef {
        val posed = mesh.movedBy(pose)
        // the **open-shell flag**, derived here because here is where the literal's value is built: it is a
        // pure function of these triangles, so a reload derives the same answer and no stored flag can drift
        // from the geometry it describes (see [Feature3.Imported.openShell])
        val value = SolidValue(Solid3(Feature3.Imported(source, openShell = Watertight.defect(posed)), posed))
        return op { EvalResult.Ok(value) }
    }

    /**
     * **Placement**: [solid] moved so that its own coordinates are read in the frame [plane] gives, at the
     * in-plane point [at], turned by [angle] about that plane's normal.
     *
     * Generic over solids by construction — an extruded part, a revolve, a boolean's result and an imported
     * body are all just *a solid* here — and that genericity is the point rather than a bonus: an import
     * merely happens to be the first caller. The four inputs are ordinary nodes, so a placement is
     * parametric like everything else: weld [at] onto a constructed point and the body follows the
     * construction, wire [angle] to a parameter two other things read and they turn together (OP-5 — sharing
     * a node *is* equality).
     *
     * **Rigid, so nothing in the honesty ledger degrades.** The map is built from an orthonormal plane frame
     * and a rotation, so it preserves lengths, angles and winding; [Solid3.movedBy] therefore moves the
     * *feature* as well as the mesh, and a placed extrusion is still an exact extrusion whose faces can be
     * sketched on. A frame that has gone degenerate makes this node invalid with a reason, and it heals when
     * the frame stops being (OP-3).
     *
     * The identity case costs nothing at all: a body placed at its own plane's origin with no turn hands its
     * input's very value object on, so placing a solid "where it already is" adds no mesh and no work.
     */
    fun placeSolid(
        solid: SolidRef,
        plane: PlaneRef,
        at: PointRef,
        angle: ScalarRef,
    ): SolidRef =
        op(solid, plane, at, angle) {
            val p = (it[1] as PlaneValue).plane
            val a = pt(it[2])
            val t = sc(it[3]).requireDim(Dimension.ANGLE, "placement angle").base
            val x = placementFrame(p, a, t)
            val (moved, why) = (it[0] as SolidValue).solid.movedBy(x)
            if (moved == null) EvalResult.Invalid(why ?: "cannot place this solid") else EvalResult.Ok(SolidValue(planned(moved, p)))
        }

    /**
     * A placed solid with its **plan** filled in when it has no analytic one — i.e. for an imported body.
     *
     * This is the placement's second job and the reason it is where the mesh-only footprint question gets
     * answered (OP-9/OP-17's long-parked item): a projection needs a plane, and the placement is the one node
     * that holds both a mesh-only solid and the plane it is being shown in. A constructed solid passes
     * straight through — its feature carries its own plan and the move already took it along.
     *
     * Done **once per recompute** rather than on every read, because `Feature3.footprint` is asked on every
     * repaint and of every element on every click.
     */
    private fun planned(
        solid: Solid3,
        plane: Plane3,
    ): Solid3 =
        when (val f = solid.feature) {
            is Feature3.Imported -> Solid3(Feature3.Imported(f.source, Silhouette.of(solid.mesh, plane), f.openShell), solid.mesh)
            // …and a **sweep**, for the identical reason (OP-26): its plan is a silhouette rather than a
            // sketch, so it is stated in some plane's coordinates and the move dropped it. Re-projected here,
            // in the plane the body is now being shown in, which is the only place that knows one.
            is Feature3.Sweep ->
                Solid3(Feature3.Sweep(f.path, f.profile, f.up, f.roll, f.twist, Silhouette.of(solid.mesh, plane)), solid.mesh)
            else -> solid
        }

    /**
     * The rigid map a placement applies: the plane's frame turned by [angle] about its own normal, with its
     * origin moved to where the in-plane point [at] is.
     *
     * The body's local x/y/z therefore mean "along the plane's u, along its v, out along its normal", which
     * is what makes the plan space's identity placement (the XY plane, the origin, no turn) leave a solid
     * exactly where it was — and what makes re-anchoring the same body to a tilted datum plane tilt it,
     * with no second concept and no stored orientation.
     */
    private fun placementFrame(
        plane: Plane3,
        at: Vec2,
        angle: Double,
    ): Xform3 {
        val c = kotlin.math.cos(angle)
        val s = kotlin.math.sin(angle)
        val u = plane.u
        val v = plane.v
        return Xform3.frame(plane.toWorld(at), u * c + v * s, u * -s + v * c, plane.normal)
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
     *
     * The **name** is a prism's ("side face") and the answer is no longer only a prism's: it resolves through
     * [Section3.facePatchOfFootprintPiece], so a *flat* face of a **loft** — every face of a polygon→apex
     * pyramid — is a face space too, at the same stored address (the footprint boundary piece). A ruled one
     * refuses by name. That closes the loft's *"no named end faces"* cut for the faces that are planes,
     * without a single recorded file changing meaning.
     */
    fun sideFacePlane(
        solid: SolidRef,
        piece: Int,
    ): PlaneRef =
        op(solid) {
            val (face, why) = Section3.facePatchOfFootprintPiece((it[0] as SolidValue).solid.feature, piece)
            val plane = face?.plane
            if (plane == null) EvalResult.Invalid(why ?: "no such side face") else EvalResult.Ok(PlaneValue(plane))
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

    // ---- the section of a solid at a plane: a working plane's context, and its inputs (OP-17) ----

    /**
     * The **section of [solid] at [plane]** — one node, and a *compound* value with accessors (OP-6, the
     * `PointSet` + `Select` pattern one type up).
     *
     * This is what a non-plan working plane draws as its context, and — the load-bearing half — what its
     * construction may be **anchored on**: [sectionSegment], [sectionArc], [sectionCircle] and
     * [sectionCorner] each address one member of the ordered set by index, which is a stored discrete choice
     * (OP-1/OP-18) taken verbatim on replay. Since both inputs are nodes, the whole section is a pure
     * function of the solid and the plane: retype the plane's offset and every anchored construction follows
     * by recompute, with nothing rebuilt (OP-21).
     *
     * The *face* case needs nothing of its own: a plane lying on one of the solid's faces sections to that
     * face's own boundary — see [constructit.geom.Section3.sectionOf].
     */
    fun section(
        solid: SolidRef,
        plane: PlaneRef,
    ): SectionRef =
        op(solid, plane) {
            EvalResult.Ok(SectionValue(Section3.sectionOf((it[0] as SolidValue).solid, (it[1] as PlaneValue).plane)))
        }

    /**
     * Curve [index] of [section] as a **segment** — a construction input in the plane's own coordinates.
     *
     * The kind is part of the accessor and not of the value, exactly as `Select`'s sign and `facePlane`'s
     * `which` are: it is decided when the input is taken and then stored, so a section curve that has since
     * become an arc makes this invalid **with a reason** (OP-3) instead of quietly handing back a different
     * kind of thing. Same rule, same reason, as *Key points*' structural count.
     */
    fun sectionSegment(
        section: SectionRef,
        index: Int,
    ): SegmentRef =
        op(section) {
            when (val e = sectionEdgeAt(it[0], index, "segment")) {
                is EvalResult.Invalid -> e
                else ->
                    when (val c = (e as EvalResult.Ok).value) {
                        is SegmentValue -> EvalResult.Ok(c)
                        else -> EvalResult.Invalid("section curve ${index + 1} is not a straight edge any more — take the input again")
                    }
            }
        }

    /** Curve [index] of [section] as an **arc** — see [sectionSegment] for the kind rule. */
    fun sectionArc(
        section: SectionRef,
        index: Int,
    ): ArcRef =
        op(section) {
            when (val e = sectionEdgeAt(it[0], index, "arc")) {
                is EvalResult.Invalid -> e
                else ->
                    when (val c = (e as EvalResult.Ok).value) {
                        is ArcValue -> EvalResult.Ok(c)
                        else -> EvalResult.Invalid("section curve ${index + 1} is not an arc any more — take the input again")
                    }
            }
        }

    /**
     * Curve [index] of [section] as an **ellipse** (OP-24) — see [sectionSegment] for the kind rule.
     *
     * This is the accessor the conics package exists to make possible: an inclined plane through a
     * cylinder cuts a true ellipse, and since session 27 that ellipse is computed analytically rather
     * than sampled, so it is a legal construction input like any other section curve.
     */
    fun sectionEllipse(
        section: SectionRef,
        index: Int,
    ): EllipseRef =
        op(section) {
            when (val e = sectionEdgeAt(it[0], index, "ellipse")) {
                is EvalResult.Invalid -> e
                else ->
                    when (val c = (e as EvalResult.Ok).value) {
                        is EllipseValue -> EvalResult.Ok(c)
                        else -> EvalResult.Invalid("section curve ${index + 1} is not an ellipse any more — take the input again")
                    }
            }
        }

    /** Curve [index] of [section] as a **circle** — see [sectionSegment] for the kind rule. */
    fun sectionCircle(
        section: SectionRef,
        index: Int,
    ): CircleRef =
        op(section) {
            when (val e = sectionEdgeAt(it[0], index, "circle")) {
                is EvalResult.Invalid -> e
                else ->
                    when (val c = (e as EvalResult.Ok).value) {
                        is CircleValue -> EvalResult.Ok(c)
                        else -> EvalResult.Invalid("section curve ${index + 1} is not a circle any more — take the input again")
                    }
            }
        }

    /**
     * Curve [index] of a section, as whichever value its kind is — with every refusal this accessor family
     * shares stated once: an index past the set, a face the plane no longer cuts, a face cut in several
     * pieces, a section with no structural pedigree at all (the mesh route), and the **conic** line: a curve
     * that is exact only at its samples is refused as an input by name, because a chord is not the curve and
     * a construction anchored on one would be tangent to something that is not there (OP-15). Since the
     * conics package (OP-24) an inclined cylinder cut is **not** in that class any more — it is an exact
     * ellipse and an ordinary input — so what is left there is the genuinely unnameable: a twisted band's
     * cut, and a cylinder's cut that runs off the ends of the material.
     */
    private fun sectionEdgeAt(
        v: Value,
        index: Int,
        want: String,
    ): EvalResult {
        val section = (v as SectionValue).section
        section.inputsRefusal?.let { return EvalResult.Invalid(it) }
        val e =
            section.edges.getOrNull(index)
                ?: return EvalResult.Invalid(
                    "this section has ${section.edges.size} named curves, so curve ${index + 1} is gone — take the input again",
                )
        e.reason?.let { return EvalResult.Invalid(it) }
        if (e.sampled != null) {
            return EvalResult.Invalid(
                "${e.provenance} is cut into a curve this drawing has no name for, so it draws but cannot be used " +
                    "as a $want — an inclined cut of a cylinder is an exact ellipse and can be anchored on, but a " +
                    "cut that leaves the material through its ends, or one through a ruled face, cannot",
            )
        }
        return when (val c = e.curve) {
            is ProfileElement.Seg -> EvalResult.Ok(SegmentValue(c.segment))
            is ProfileElement.ArcE -> EvalResult.Ok(ArcValue(c.arc))
            is ProfileElement.CircleE -> EvalResult.Ok(CircleValue(c.circle))
            is ProfileElement.EllipseE -> EvalResult.Ok(EllipseValue(c.ellipse))
            is ProfileElement.EllipticArcE -> EvalResult.Ok(EllipticArcValue(c.arc))
            else -> EvalResult.Invalid("${e.provenance} has no curve to take as a $want")
        }
    }

    /**
     * Corner [index] of [section] — the point where the plane crosses one structurally named edge of the
     * solid (which is what the queue entry means by a corner carrying "the two faces" as its identity).
     *
     * Exact wherever the edge is: the cut of a straight edge by a plane is a linear solve, so a pyramid's
     * section corners are exact rational functions of its base and its apex.
     */
    fun sectionCorner(
        section: SectionRef,
        index: Int,
    ): PointRef =
        op(section) {
            val s = (it[0] as SectionValue).section
            s.inputsRefusal?.let { why -> return@op EvalResult.Invalid(why) }
            val c =
                s.corners.getOrNull(index)
                    ?: return@op EvalResult.Invalid(
                        "this section has ${s.corners.size} named corners, so corner ${index + 1} is gone — take the input again",
                    )
            val at = c.at ?: return@op EvalResult.Invalid(c.reason ?: "the plane does not cross ${c.provenance}")
            EvalResult.Ok(PointValue(at))
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

    fun ellipticArcStart(a: EllipticArcRef): PointRef =
        op(a) { EvalResult.Ok(PointValue(Conics.start((it[0] as EllipticArcValue).arc))) }

    fun ellipticArcEnd(a: EllipticArcRef): PointRef =
        op(a) { EvalResult.Ok(PointValue(Conics.end((it[0] as EllipticArcValue).arc))) }

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

/** A point in space — the value of a height point ([Construction.heightPoint]). */
fun Evaluator.point3(ref: Point3Ref): Vec3 = (valueOf(ref) as Point3Value).p

/** A curve in space — the value of a path through points ([Construction.pathThrough], OP-26). */
fun Evaluator.path3(ref: Path3Ref): Path3 = (valueOf(ref) as Path3Value).path

fun Evaluator.frame(ref: FrameRef): FrameValue = valueOf(ref) as FrameValue

fun Evaluator.scalar(ref: ScalarRef): Quantity = (valueOf(ref) as ScalarValue).q

fun Evaluator.line(ref: LineRef): Line = (valueOf(ref) as LineValue).line

fun Evaluator.segment(ref: SegmentRef): Segment = (valueOf(ref) as SegmentValue).seg

fun Evaluator.circle(ref: CircleRef): Circle = (valueOf(ref) as CircleValue).circle

fun Evaluator.arc(ref: ArcRef): Arc = (valueOf(ref) as ArcValue).arc

fun Evaluator.ellipse(ref: EllipseRef): Ellipse = (valueOf(ref) as EllipseValue).ellipse

fun Evaluator.ellipticArc(ref: EllipticArcRef): EllipticArc = (valueOf(ref) as EllipticArcValue).arc

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
