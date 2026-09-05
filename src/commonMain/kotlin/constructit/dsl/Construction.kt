package constructit.dsl

import constructit.core.ArcValue
import constructit.core.BezierValue
import constructit.core.ChainValue
import constructit.core.CircleValue
import constructit.core.DirectionValue
import constructit.core.EllipseValue
import constructit.core.EllipticArcValue
import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.FrameValue
import constructit.core.FuncCurveValue
import constructit.core.IndirectNode
import constructit.core.InstanceNode
import constructit.core.LineValue
import constructit.core.LoopValue
import constructit.core.Node
import constructit.core.OpNode
import constructit.core.Path3SetValue
import constructit.core.Path3Value
import constructit.core.PlaneValue
import constructit.core.Point3SetValue
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
import constructit.core.Sphere3Value
import constructit.core.Value
import constructit.core.transformValue
import constructit.expr.Derive
import constructit.expr.DeriveError
import constructit.expr.Expr
import constructit.expr.ExprError
import constructit.geom.Affine
import constructit.geom.Arc
import constructit.geom.Axis3
import constructit.geom.Bezier
import constructit.geom.Blend3
import constructit.geom.BlendChoice
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.BoolOp
import constructit.geom.CarrierCurve
import constructit.geom.CarryMode
import constructit.geom.Chain
import constructit.geom.Chains
import constructit.geom.Circle
import constructit.geom.Combine3
import constructit.geom.Conics
import constructit.geom.Connect3
import constructit.geom.Continuity
import constructit.geom.CornerCut
import constructit.geom.Curve3Element
import constructit.geom.CurveEnd
import constructit.geom.Curves3
import constructit.geom.Direction
import constructit.geom.Ellipse
import constructit.geom.EllipticArc
import constructit.geom.Feature3
import constructit.geom.FrameSeed
import constructit.geom.Frames3
import constructit.geom.FuncCurve
import constructit.geom.FuncCurves
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Handedness
import constructit.geom.Intersect3
import constructit.geom.IntersectionCurve
import constructit.geom.Justification
import constructit.geom.Line
import constructit.geom.LoftGuide
import constructit.geom.LoftSection
import constructit.geom.Loop
import constructit.geom.Mesh3
import constructit.geom.MeshBool
import constructit.geom.Path3
import constructit.geom.Pierce3
import constructit.geom.Plane3
import constructit.geom.Point3Set
import constructit.geom.Profile
import constructit.geom.ProfileElement
import constructit.geom.Project3
import constructit.geom.Ray
import constructit.geom.Region
import constructit.geom.Section3
import constructit.geom.Segment
import constructit.geom.Shell3
import constructit.geom.Silhouette
import constructit.geom.SizeLaw
import constructit.geom.Sketch3
import constructit.geom.Skin3
import constructit.geom.SkinMatch
import constructit.geom.SkinRow
import constructit.geom.SkinSection
import constructit.geom.Solid3
import constructit.geom.SolidFace
import constructit.geom.Sphere3
import constructit.geom.SphereMeet
import constructit.geom.Spheres3
import constructit.geom.Stations3
import constructit.geom.SweepProfile
import constructit.geom.Trilateration
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

/** A **function curve** (the session-71 entry, curve half) — see [Construction.funcCurve]. */
typealias FuncCurveRef = Ref<FuncCurveValue>
typealias PointSetRef = Ref<PointSetValue>
typealias RayRef = Ref<RayValue>
typealias DirectionRef = Ref<DirectionValue>
typealias BezierRef = Ref<BezierValue>
typealias ProfileRef = Ref<ProfileValue>
typealias LoopRef = Ref<LoopValue>
typealias RegionRef = Ref<RegionValue>

/** A curve that separates its plane into two sides — what a cut cuts with (OP-22's extension). */
typealias ChainRef = Ref<ChainValue>

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

/** An **ordered set of curves in space** (OP-26, step 6) — OP-1's solution set one dimension up. */
typealias Path3SetRef = Ref<Path3SetValue>

/** A **sphere as a locus** (OP-28) — a distance carried in space, not a body. See [Construction.sphere]. */
typealias Sphere3Ref = Ref<Sphere3Value>

/** An **ordered set of points in space** (OP-28) — OP-1's `PointSet` one dimension up. */
typealias Point3SetRef = Ref<Point3SetValue>

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
 * One section of a [Construction.skin] (OP-26's hull route): the area [sketch] as it is drawn, together with
 * [at] — the distance along the run of the **station** it is drawn on.
 *
 * Both are nodes, which is the whole reason this is a pair rather than a plane: the station's distance is an
 * ordinary parameter, so retyping it slides the station and the skin follows by recompute, and two stations
 * sharing a pitch is one parameter node feeding both (OP-5 — sharing *is* equality).
 */
class SkinPart(val sketch: SketchRef, val at: ScalarRef)

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

    /**
     * One **coordinate of a point**, as a length — the accessor `P.x` in an expression reads (OP-7's naming
     * authority extended to coordinates, the session-76 entry). [axis] is 0 for x and 1 for y.
     *
     * The inverse of [pointXY], and deliberately one direction: this *reads* a point that the construction
     * already places, so it adds an ordinary DAG edge and takes no freedom away from anything.
     */
    fun pointCoordinate(
        p: PointRef,
        axis: Int,
    ): ScalarRef =
        op(p) {
            val v = (it[0] as PointValue).p
            EvalResult.Ok(ScalarValue(Quantity.mm(if (axis == 0) v.x else v.y)))
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

    // ---- function curves: the same expressions, plus a parameter (the session-71 entry, curve half) ----

    /**
     * The curve traced by [xText] and [yText] as the dimensionless parameter runs from [t0] to [t1] — the
     * **whole** of the new curve vocabulary, and deliberately no primitive per curve family: an involute, a
     * cycloid and a spiral are this one op with three different texts (the user's own design).
     *
     * [refs] are the named scalars the two texts read, in the order [names] gives them, and they are
     * ordinary DAG inputs — so editing one moves the curve by nothing but the recompute every other edit
     * uses, and no input list is rewired. [t0] and [t1] are scalars too, so the domain is draggable and
     * typeable like everything else (OP-13).
     *
     * The **derivatives are symbolic and computed once, here** ([constructit.expr.Derive]); a function whose
     * derivative the vocabulary cannot state still draws, still carries a rider and still bounds an area,
     * and only the tangent-dependent constructions refuse — by name, quoting the function that stopped it.
     */
    fun funcCurve(
        xText: Expr,
        yText: Expr,
        names: List<String>,
        refs: List<ScalarRef>,
        t0: ScalarRef,
        t1: ScalarRef,
        param: String = "t",
        text: String = "",
    ): FuncCurveRef {
        var dx: Expr? = null
        var dy: Expr? = null
        var why: String? = null
        try {
            dx = Derive.d(xText, param)
            dy = Derive.d(yText, param)
        } catch (e: DeriveError) {
            dx = null
            dy = null
            why = e.message
        }
        val fdx = dx
        val fdy = dy
        val fwhy = why
        return op(*(refs + listOf(t0, t1)).toTypedArray()) { args ->
            val env = HashMap<String, Quantity>(names.size)
            for ((k, n) in names.withIndex()) {
                val q = (args[k] as? ScalarValue)?.q ?: return@op EvalResult.Invalid("$n is not a number")
                env[n] = q
            }
            val a = (args[names.size] as ScalarValue).q
            val b = (args[names.size + 1] as ScalarValue).q
            if (a.dim != Dimension.NONE || b.dim != Dimension.NONE) {
                return@op EvalResult.Invalid("the domain of $param is a pair of plain numbers, and this one is ${a.dim} to ${b.dim}")
            }
            val curve = FuncCurve(xText, yText, fdx, fdy, fwhy, env, a.base, b.base, param, text = text)
            FuncCurves.invalidity(curve)?.let { return@op EvalResult.Invalid(it) }
            EvalResult.Ok(FuncCurveValue(curve))
        }
    }

    /**
     * The point of [curve] at parameter [t] — the **exact** position-along a function curve offers (OP-24's
     * honesty line, one curve family on): the point is the expression itself, evaluated there.
     */
    fun pointOnFuncCurve(
        curve: FuncCurveRef,
        t: ScalarRef,
    ): PointRef =
        op(curve, t) {
            val c = (it[0] as FuncCurveValue).curve
            val at = (it[1] as ScalarValue).q
            if (at.dim != Dimension.NONE) return@op EvalResult.Invalid("a function curve's parameter is a plain number, and this is ${at.dim}")
            val p = FuncCurves.pointAt(c, at.base) ?: return@op EvalResult.Invalid("the curve has no point at ${c.param} = ${at.base}")
            EvalResult.Ok(PointValue(p))
        }

    /** The tangent line of [curve] at [t] — exact, from the symbolic derivative, or refused by name. */
    fun tangentOnFuncCurve(
        curve: FuncCurveRef,
        t: ScalarRef,
    ): LineRef = tangentLine(curve, t, normal = false)

    /** The normal line of [curve] at [t] — exact, from the symbolic derivative, or refused by name. */
    fun normalOnFuncCurve(
        curve: FuncCurveRef,
        t: ScalarRef,
    ): LineRef = tangentLine(curve, t, normal = true)

    private fun tangentLine(
        curve: FuncCurveRef,
        t: ScalarRef,
        normal: Boolean,
    ): LineRef =
        op(curve, t) {
            val c = (it[0] as FuncCurveValue).curve
            val at = (it[1] as ScalarValue).q
            if (at.dim != Dimension.NONE) return@op EvalResult.Invalid("a function curve's parameter is a plain number, and this is ${at.dim}")
            c.noTangent?.let { why -> return@op EvalResult.Invalid(why) }
            val p = FuncCurves.pointAt(c, at.base) ?: return@op EvalResult.Invalid("the curve has no point at ${c.param} = ${at.base}")
            val d = FuncCurves.tangentAt(c, at.base) ?: return@op EvalResult.Invalid("the curve has no tangent at ${c.param} = ${at.base}")
            if (d.length() < Vec2.EPS) return@op EvalResult.Invalid("the curve stands still at ${c.param} = ${at.base}, so it has no direction there")
            EvalResult.Ok(LineValue(Line(p, if (normal) d.normalized().perp() else d.normalized())))
        }

    /** A function curve's start point — one of its two key points, and what a loop joins onto. */
    fun funcCurveStart(curve: FuncCurveRef): PointRef = funcEnd(curve, atStart = true)

    /** A function curve's end point — the other key point. */
    fun funcCurveEnd(curve: FuncCurveRef): PointRef = funcEnd(curve, atStart = false)

    private fun funcEnd(
        curve: FuncCurveRef,
        atStart: Boolean,
    ): PointRef =
        op(curve) {
            val c = (it[0] as FuncCurveValue).curve
            val p = (if (atStart) FuncCurves.start(c) else FuncCurves.end(c)) ?: return@op EvalResult.Invalid("the curve has no end there")
            EvalResult.Ok(PointValue(p))
        }

    /** The **measured** length of a function curve — numeric to a stated tolerance, and flagged (OP-15). */
    fun measureFuncCurveLength(curve: FuncCurveRef): ScalarRef =
        op(curve) { EvalResult.Ok(ScalarValue(Quantity.mm(FuncCurves.arcLength((it[0] as FuncCurveValue).curve)))) }

    /**
     * **Line ∩ function curve**: numeric but *deterministic*, ordered by ascending parameter along the
     * **curve** — OP-1's canonical rule for parametric curves, with the function curve as the first operand
     * so the rule reads directly (see [FuncCurves.intersectImplicit] for the fixed seeding).
     */
    fun intersectFL(
        curve: FuncCurveRef,
        line: LineRef,
    ): PointSetRef =
        op(curve, line) {
            val c = (it[0] as FuncCurveValue).curve
            EvalResult.Ok(PointSetValue(FuncCurves.intersectImplicit(c, FuncCurves.lineImplicit((it[1] as LineValue).line))))
        }

    /** **Circle ∩ function curve**: the same mechanism over the circle's implicit form, same ordering. */
    fun intersectFC(
        curve: FuncCurveRef,
        circle: CircleRef,
    ): PointSetRef =
        op(curve, circle) {
            val c = (it[0] as FuncCurveValue).curve
            EvalResult.Ok(PointSetValue(FuncCurves.intersectImplicit(c, FuncCurves.circleImplicit((it[1] as CircleValue).circle))))
        }

    /** **Ellipse ∩ function curve**: the same mechanism over the ellipse's implicit form, same ordering. */
    fun intersectFE(
        curve: FuncCurveRef,
        e: EllipseRef,
    ): PointSetRef =
        op(curve, e) {
            val c = (it[0] as FuncCurveValue).curve
            val el = (it[1] as EllipseValue).ellipse
            EvalResult.Ok(PointSetValue(FuncCurves.intersectImplicit(c) { p -> Conics.implicit(el, p) }))
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

    /**
     * The point of [circle] at arc distance [distance] from [from] — [pointAlongLine]'s round-leg twin, and
     * the whole of what the **chamfer-on-arc convention** needs (session 76, item c: a chamfer's setback is
     * measured *along the carrier*, see [constructit.geom.FilletMath.setback] for why the arc and not the
     * chord). [sign] turns counter-clockwise for `+1`.
     *
     * Closed form and exact: the travel is an angle, `distance / R`, added to the angle [from] already stands
     * at (which is *on* the circle by construction — it is a crossing of the two carriers). Invalid with a
     * reason where there is no angle to start from, which heals like everything else (OP-3).
     */
    fun pointAlongCircle(
        circle: CircleRef,
        from: PointRef,
        distance: ScalarRef,
        sign: Int,
    ): PointRef =
        op(circle, from, distance) {
            val c = cir(it[0])
            val p = pt(it[1])
            val d = sc(it[2]).mm
            if (c.radius <= Vec2.EPS) return@op EvalResult.Invalid("a chamfer cannot run along a circle of no radius")
            val r = p - c.center
            if (r.length() < Vec2.EPS) return@op EvalResult.Invalid("the corner is at the circle's own centre, so there is no way along it")
            val a = r.angle() + sign * d / c.radius
            EvalResult.Ok(PointValue(c.center + Vec2(c.radius * kotlin.math.cos(a), c.radius * kotlin.math.sin(a))))
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

    /**
     * Any geometry turned a **half turn about [center]** — the point reflection (OP-14: a structural intent
     * gets its own spelling).
     *
     * The capability was always here — this is [rotate] at 180° — and that is precisely why it needed a node
     * of its own: a rotation carries an **angle**, and an angle is a freedom, so a half turn spelled as one
     * can be dragged or retyped to 175° and quietly stop being a point reflection. There is no angle node in
     * this graph at all, so there is nothing to offer and nothing to drift. The same argument `Turn3.Full`
     * won one dimension up (session 63): what is structural is a *kind*, never a value.
     */
    fun <V : Value> pointReflect(
        g: Ref<V>,
        center: PointRef,
    ): Ref<V> =
        op(g, center) { EvalResult.Ok(transformValue(Affine.pointReflection(pt(it[1])), it[0])) }

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
                        is FuncCurveValue -> ProfileElement.FuncE(v.curve)
                        else -> throw IllegalArgumentException("profile element must be a segment, arc, Bézier, elliptic arc or function curve")
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
     * The piece of the **segment** [piece] that survives a rounding: from its own end nearest [keep] to the
     * tangency [cut] (GitHub #25, the user's design — *"the fillet tool … does not supersede [the corner]
     * with its filleted version"*).
     *
     * The twin of [segmentBetween], and it exists for the one thing that trim cannot say: a trim of a
     * boundary is stated by two cut points anywhere on the carrier, while a **leg** is trimmed *back from its
     * own corner* and therefore has an amount of itself to give. So the cut point is required to lie on the
     * piece, between the end that is kept and the end the corner was at, and a rounding that overruns is
     * **invalid with the reason and the number that would fit** (OP-3) rather than a leg quietly turned round
     * the other way. The two corners of one leg compose: trim the far end first and this one is trimmed off
     * what is left, so *"longer than either adjacent leg allows, counting the neighbouring corner's own
     * radius"* needs no arithmetic about the neighbour — the neighbour has already taken its share.
     *
     * [keep] and [cut] are **projected** onto the carrier, for [segmentBetween]'s own reason: a cut point is
     * constructed *from* the leg (a tangency is a projection of the rounding's centre), so exact incidence is
     * unattainable in floating point and projecting keeps the trim well defined as the parameters move.
     */
    fun trimmedLeg(
        piece: SegmentRef,
        keep: PointRef,
        cut: PointRef,
    ): SegmentRef =
        op(piece, keep, cut) {
            val seg = (it[0] as SegmentValue).seg
            val d = seg.b - seg.a
            val len = d.length()
            if (len < Vec2.EPS) return@op EvalResult.Invalid("this leg has no length to round")
            val dir = d * (1.0 / len)
            val keepAtA = (pt(it[1]) - seg.a).length() <= (pt(it[1]) - seg.b).length()
            val from = if (keepAtA) seg.a else seg.b
            val along = if (keepAtA) dir else dir * -1.0
            val t = (pt(it[2]) - from).dot(along)
            if (t < Vec2.EPS) {
                // the handover has passed the end that is kept: the rounding wants more of the leg than the
                // leg has, and how much it has *is* the largest rounding it can host
                EvalResult.Invalid(
                    "the rounding overruns this leg: it reaches ${fmtMm(len - t)} back from the corner and the leg " +
                        "is only ${fmtMm(len)} long, so the largest that fits here is ${fmtMm(len)} from the corner",
                )
            } else if (t > len + Vec2.EPS) {
                EvalResult.Invalid(
                    "the rounding's handover lies ${fmtMm(t - len)} past this leg's far end, so none of the leg is " +
                        "left between them",
                )
            } else {
                EvalResult.Ok(SegmentValue(Segment(from, from + along * t)))
            }
        }

    /**
     * [trimmedLeg]'s round twin: the piece of the **arc** [piece] from its own end nearest [keep] to the
     * tangency [cut], measured as the arc's own sweep so a rounding that overruns says so in the same words.
     *
     * The cut point is projected radially onto the carrier circle, exactly as [arcBetween] projects its own.
     */
    fun trimmedArcLeg(
        piece: ArcRef,
        keep: PointRef,
        cut: PointRef,
    ): ArcRef =
        op(piece, keep, cut) {
            val arc = (it[0] as ArcValue).arc
            val sweep = GeomMath.sweep(arc)
            if (abs(sweep) < Vec2.EPS) return@op EvalResult.Invalid("this leg has no length to round")
            val start = GeomMath.arcStart(arc)
            val keepAtStart = (pt(it[1]) - start).length() <= (pt(it[1]) - GeomMath.arcEnd(arc)).length()
            val from = if (keepAtStart) arc.startAngle else arc.endAngle
            val ccw = if (keepAtStart) arc.ccw else !arc.ccw
            val d = pt(it[2]) - arc.center
            if (d.length() < Vec2.EPS) return@op EvalResult.Invalid("the rounding's tangency is at this leg's own centre")
            val turn = if (ccw) norm2pi(d.angle() - from) else norm2pi(from - d.angle())
            val room = abs(sweep)
            val reach = turn * arc.radius
            val left = room * arc.radius
            if (turn < Vec2.EPS) {
                EvalResult.Invalid(
                    "the rounding overruns this leg: it reaches ${fmtMm(left - reach)} back from the corner and the " +
                        "leg is only ${fmtMm(left)} long, so the largest that fits here is ${fmtMm(left)} from the corner",
                )
            } else if (turn > room + Vec2.EPS) {
                EvalResult.Invalid(
                    "the rounding's handover lies ${fmtMm(reach - left)} past this leg's far end, so none of the leg " +
                        "is left between them",
                )
            } else {
                EvalResult.Ok(ArcValue(Arc(arc.center, arc.radius, from, if (ccw) from + turn else from - turn, ccw)))
            }
        }

    /** An angle in `[0, 2π)` — the turn from one bearing to another, one way round. */
    private fun norm2pi(a: Double): Double {
        val twoPi = 2.0 * kotlin.math.PI
        var r = a % twoPi
        if (r < 0) r += twoPi
        return r
    }

    /** A length in a refusal's own words: millimetres, at most two decimals and never a trailing zero. */
    private fun fmtMm(v: Double): String {
        val r = kotlin.math.round(v * 100.0) / 100.0
        val text = if (r == kotlin.math.floor(r)) r.toLong().toString() else r.toString()
        return "$text mm"
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
                        is FuncCurveValue -> ProfileElement.FuncE(v.curve)
                        else -> return@op EvalResult.Invalid("a loop piece must be a segment, an arc, a circle, an ellipse, a Bézier or a function curve")
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
                // …and where there is no hole at all, the outer boundary is simply flat: saying that a hole
                // removed the area would be a refusal about something the drawing has not got, which is the
                // one thing a refusal may not do (session 65)
                EvalResult.Invalid(
                    if (h.isEmpty()) {
                        "this boundary encloses no area, so it bounds nothing"
                    } else {
                        "the holes remove more area than the outer boundary encloses"
                    },
                )
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
        /**
         * Each carrier point's **corner radius** and **bevel setback**, one entry per vertex (GitHub #25) —
         * an ortho path's own corner cuts, so *"both faces of a wall follow the rounded corner"*.
         *
         * Inputs from the moment the wall is built, and zero until a *Fillet* or a *Chamfer* binds one
         * (`OrthoVertex.round`): that is what lets a corner rounded **after** the wall was thickened be
         * followed with nothing rewired (OP-5), while a wall over a carrier with no cuts is the mitred wall
         * it always was, down to the arithmetic ([GeomMath.thickRegion]).
         */
        rounds: List<ScalarRef> = emptyList(),
        bevels: List<ScalarRef> = emptyList(),
    ): RegionRef =
        op(*(vertices + rounds + bevels + thickness).toTypedArray()) { args ->
            val n = vertices.size
            val pts = args.take(n).map { (it as PointValue).p }
            val t = (args.last() as ScalarValue).q.mm
            val cuts = cornerCuts(args, n, rounds.size, bevels.size)
            val (faces, why) = GeomMath.thickFaces(pts, closed, justification.offsets(t), cuts)
            if (faces == null) return@op EvalResult.Invalid(why ?: "no footprint")
            val (region, reason) = GeomMath.thickRegion(faces)
            if (region == null) EvalResult.Invalid(reason ?: "no footprint") else EvalResult.Ok(RegionValue(region))
        }

    /**
     * The corner cuts a thick footprint's arguments carry: a positive radius rounds, a positive setback
     * bevels, and zero is the mitred corner every wall had before there were any (GitHub #25).
     *
     * A rounding wins over a bevel where both are somehow set, because only one of the two is ever bound and
     * a file that carried both would be one that had been hand-edited — the drawing's own answer then is the
     * corner piece the path holds, which is the rounding.
     */
    private fun cornerCuts(
        args: List<Value>,
        vertices: Int,
        rounds: Int,
        bevels: Int,
    ): List<CornerCut?> {
        if (rounds == 0 && bevels == 0) return emptyList()
        return (0 until vertices).map { i ->
            val r = (args.getOrNull(vertices + i) as? ScalarValue)?.q?.mm ?: 0.0
            val d = (args.getOrNull(vertices + rounds + i) as? ScalarValue)?.q?.mm ?: 0.0
            when {
                r > Vec2.EPS -> CornerCut.Round(r)
                d > Vec2.EPS -> CornerCut.Bevel(d)
                else -> null
            }
        }
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
                    is FuncCurveValue -> ProfileElement.FuncE(v.curve)
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

    /**
     * A **station plane**: the plane [path] pierces [distance] millimetres along itself, with its in-plane
     * axes the moving frame's there (OP-26, step 4).
     *
     * The same kind of node [datumPlane] is, and deliberately so — a station *is* a sketch space, so nothing
     * downstream learns a new concept. Origin: the point on the path at that arc length. Normal: the tangent
     * there. Axes: the parallel-transport frame's, which is why this arrives after the frame (step 2) and
     * after the helix (step 3), the first curve that made the frame work for its living.
     *
     * **Every input is a node**, which is the whole feature: the path is the drawn curve, so dragging a point
     * it runs through carries the station and everything sketched on it along; [space] is the curve's own
     * sketch space, whose normal starts the frame exactly as it does for a sweep ([Frames3.startReference]);
     * and [distance] is an ordinary length parameter. So *relative to another station* costs nothing — it is
     * `base + d` in the expression language (OP-7) — and two stations sharing a pitch is one parameter node
     * feeding both. Sharing **is** equality; there is nothing here to build for it.
     *
     * **Out of range is invalidity, not a refusal** (OP-3, and OP-26 makes the point doctrinally): the
     * distance is a live value, so a station past the end of the run — or before its start — makes this node
     * invalid with a named reason, everything sketched on it hides while it is, and retyping the number
     * brings all of it back. Refusing the *gesture* would make replay depend on a value.
     */
    fun stationPlane(
        path: Path3Ref,
        space: PlaneRef,
        distance: ScalarRef,
    ): PlaneRef =
        op(path, space, distance) {
            val pl = (it[1] as PlaneValue).plane
            val s = sc(it[2]).requireDim(Dimension.LENGTH, "station distance").mm
            val (station, why) = Stations3.at((it[0] as Path3Value).path, pl.normal.normalized(), s)
            if (station == null) {
                EvalResult.Invalid(why ?: "cannot stand a plane across this curve there")
            } else {
                EvalResult.Ok(PlaneValue(station.plane))
            }
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
     * both **in the sketch plane** (OP-17 slice 2) — starting [offset] from the sketch plane.
     *
     * The axis is given by ordinary 2D nodes, so it can be *constructed* (a symmetry line, a
     * centreline through two key points) and moves with the profile. A profile touching the axis is
     * legal, a profile crossing it is invalid with a reason and heals (OP-3) — see [Geom3.revolve].
     *
     * The body occupies `[offset, offset + angle]` about the axis, with the profile as the generator at
     * angle 0 and **either sign** allowed for both. A stated [offset] is a node like the angle, so it is a
     * live parameter that can be shared, wired and dragged; no offset at all is the same construction with
     * one node fewer, standing at zero.
     *
     * A **complete** revolution is [revolveFull], not an angle of 360°, and it takes no offset — a body
     * with no start has nowhere to put one ([Turn3]).
     */
    fun revolve(
        sketch: SketchRef,
        axisOrigin: PointRef,
        axisDir: DirectionRef,
        angle: ScalarRef,
        offset: ScalarRef? = null,
    ): SolidRef =
        op(*listOfNotNull(sketch, axisOrigin, axisDir, angle, offset).toTypedArray()) {
            val a = sc(it[3]).requireDim(Dimension.ANGLE, "revolve angle").base
            val o = if (it.size > 4) sc(it[4]).requireDim(Dimension.ANGLE, "revolve offset").base else 0.0
            val (solid, why) =
                Geom3.revolve(
                    (it[0] as SketchValue).sketch,
                    pt(it[1]),
                    (it[2] as DirectionValue).dir.v,
                    a,
                    o,
                )
            if (solid == null) EvalResult.Invalid(why ?: "cannot revolve") else EvalResult.Ok(SolidValue(solid))
        }

    /**
     * Take [sketch] the **whole way round** the axis through [axisOrigin] in direction [axisDir] (OP-17
     * slice 2).
     *
     * A construction of its own rather than [revolve] at 360°, and the graph is what says so: there is no
     * angle input here, so the body is watertight by *structure* and no parameter edit can open it — the
     * statement OP-14 makes about a circle against a full-turn arc, one dimension up ([Turn3]).
     */
    fun revolveFull(
        sketch: SketchRef,
        axisOrigin: PointRef,
        axisDir: DirectionRef,
    ): SolidRef =
        op(sketch, axisOrigin, axisDir) {
            val (solid, why) =
                Geom3.revolveFull(
                    (it[0] as SketchValue).sketch,
                    pt(it[1]),
                    (it[2] as DirectionValue).dir.v,
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
     * A **2D point of [plane]**, the perpendicular foot of the point in space [point] on it (GitHub #14) — the
     * projection that anchors a construction on one pane to a point defined on another.
     *
     * The value is the point's world position dropped **along [plane]'s normal** onto the plane, read in the
     * plane's own (u, v): [Plane3.toLocal] is exactly that drop, since it keeps only the two in-plane
     * components and discards the one along the normal. So the result is an ordinary plane point — usable as a
     * circle's centre, a coil's axis, an anchor — whose world position (lifted back by a zero height, OP-25) is
     * the foot itself.
     *
     * **Parented, never copied** — OP-26's rule once more. [point] is *the other pane's point, shared by node*
     * (fed through `Document.pointInSpace`, the one seam every point's world position flows through since
     * session 53), so dragging it, retyping what defines it, or tilting either plane moves the projection with
     * it: same-node-is-equality, applied to anchoring across planes rather than to a shared radius. Invalid with
     * the source's own reason when the source has none (OP-3), because a projection of nothing is nothing.
     */
    fun projectToPlane(
        plane: PlaneRef,
        point: Point3Ref,
    ): PointRef =
        op(plane, point) {
            val pl = (it[0] as PlaneValue).plane
            EvalResult.Ok(PointValue(pl.toLocal((it[1] as Point3Value).p)))
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
     * A **helix** about the axis through [center] along [plane]'s normal (OP-26, step 3) — a spring, a coil,
     * the path a thread runs on.
     *
     * **The value is world geometry; the construction is parented** — OP-26's rule, and here it is the whole
     * of the design. The axis is not three typed numbers: it is *the space's own normal through a point that
     * already stands in the drawing*, so tilting the datum tilts the coil, moving the point moves it, and
     * moving the plane's origin carries its phase along. That is the same statement a height point makes
     * (OP-25: a plane, a point on it, and a rise along its normal), one dimension of freedom further on, and
     * it is why this needs no 3D manipulator: everything it takes is already draggable where it lives.
     *
     * **[plane] is read twice, because it is one statement — the space this coil stands in.** Its normal is
     * the axis, so the helix rises out of the space exactly the way a height point does; and its **u
     * direction is the phase**, so the curve starts at [center] + `u·radius`, beside the axis point along
     * the space's own x. Nothing about where the curve begins is a convention this file invents.
     *
     * That last reading is what makes this **the spelling that states no phase**: it says a radius and takes
     * the space's own x for where the coil begins. A drawing that wants to *say* where it begins states it
     * the way everything else in this kernel is stated — with a point ([helixThrough]).
     *
     * [hand] is **structural** (OP-1, one dimension up): chirality is discrete, so it is decided by the
     * construction — which in the editor means by *which tool was used*, the same way straight and smooth
     * curves are two tool ids (OP-18) — and is never re-derived from the sign of a number that could drift.
     *
     * Invalid with a reason that heals (OP-3) for each of the four value conditions, and each refusal names
     * what the other statement of the same thing would be, because every one of them is a *second way to say
     * something the drawing can already say* (see [Curve3Element.Helix3]):
     * - a **non-positive radius** — a helix of no radius is a straight line along the axis;
     * - a **zero pitch** — that is a circle, and a circle is drawn in a space;
     * - a **negative pitch** — that is the other handedness, which is the other tool;
     * - a **non-positive turn count** — zero turns is a point, and a negative count is this same coil on the
     *   other side of [center], which is said by pointing the axis the other way.
     */
    fun helix(
        plane: PlaneRef,
        center: Point3Ref,
        radius: ScalarRef,
        pitch: ScalarRef,
        turns: ScalarRef,
        hand: Handedness,
    ): Path3Ref =
        op(plane, center, radius, pitch, turns) {
            val pl = (it[0] as PlaneValue).plane
            val at = (it[1] as Point3Value).p
            val r = sc(it[2]).requireDim(Dimension.LENGTH, "helix radius").mm
            val p = sc(it[3]).requireDim(Dimension.LENGTH, "helix pitch").mm
            val n = sc(it[4]).requireDim(Dimension.NONE, "helix turns").value
            if (r <= Vec3.EPS) {
                return@op EvalResult.Invalid(
                    "a helix needs a positive radius — this one is ${Frames3.mm(r)} mm, and a coil of no " +
                        "radius is a straight line along its own axis",
                )
            }
            helixRising(at, pl.normal, pl.u, r, p, n, hand)
        }

    /**
     * A **helix about [center] that begins at [start]** (OP-26, step 3) — the second spelling of the coil,
     * and the one that says where it starts.
     *
     * The relation to [helix] is exactly [circleCP]'s to [circleCR]: a centre and a point on it, rather than a
     * centre and a typed number. Here the point buys two things at once, because a coil's base *is* a circle
     * — the **radius** is how far [start] stands from the axis, and the **phase** is which way it stands. The
     * phase is a real degree of freedom of a coil (where the thread begins, which side of a boss the spring
     * comes off), and before this it could only be the space's own x; a drawing that wanted another one had
     * to turn the space, which is compensation where an anchor belongs (OP-26's rule).
     *
     * **No angle input, deliberately.** With a picked start point, a *stated* bearing is an ordinary relative
     * point at a polar offset — a construction this kernel already has — so an angle here would be a second
     * way to say what the drawing can already say, which is the fault a negative pitch is refused for.
     *
     * **[start] is read across the axis only**: what it contributes is its offset from [center] with the
     * axial part taken out, so a start point that has been lifted says the same thing as one that has not,
     * and the coil still begins level with [center] — which is what [Curve3Element.Helix3.origin] means. In
     * the everyday gesture both points are drawn in the same space and there is nothing to take out.
     *
     * The phase is carried as that **vector**, never as an angle: nothing here computes an `atan2` and feeds
     * a `cos`/`sin` back, so the curve's first point is [start] to within the one normalization it passes
     * through, not to within a trigonometric round trip.
     *
     * Invalid with a reason that heals (OP-3) for the same four conditions [helix] refuses, and the radius
     * one reads for *this* spelling: a start point standing on the axis states no radius and no phase.
     */
    fun helixThrough(
        plane: PlaneRef,
        center: Point3Ref,
        start: Point3Ref,
        pitch: ScalarRef,
        turns: ScalarRef,
        hand: Handedness,
    ): Path3Ref =
        op(plane, center, start, pitch, turns) {
            val pl = (it[0] as PlaneValue).plane
            val at = (it[1] as Point3Value).p
            val from = (it[2] as Point3Value).p
            val p = sc(it[3]).requireDim(Dimension.LENGTH, "helix pitch").mm
            val n = sc(it[4]).requireDim(Dimension.NONE, "helix turns").value
            val axis = pl.normal.normalized()
            val out = (from - at).let { d -> d - axis * d.dot(axis) }
            val r = out.length()
            if (r <= Vec3.EPS) {
                return@op EvalResult.Invalid(
                    "a helix needs a positive radius, and here that is how far its start point stands from " +
                        "the axis — this one stands on the axis itself (${Frames3.mm(r)} mm off it), which " +
                        "states neither a radius nor a direction to start in: move it off the centre",
                )
            }
            helixRising(at, axis, out, r, p, n, hand)
        }

    /**
     * The half of a helix that is the same however the coil was spelled: the three conditions on its
     * **rise** — and, once they hold, the curve.
     *
     * Shared rather than written twice because it is one statement of one doctrine: each of these is a
     * *second way to say something the drawing can already say* (see [Curve3Element.Helix3]), and a refusal
     * that drifted between the two spellings would make which tool was used change what a pitch means. The
     * **radius** condition is not here, because it is the one thing the two spellings say differently — a
     * typed number that is not positive, or a start point standing on the axis.
     */
    private fun helixRising(
        origin: Vec3,
        axis: Vec3,
        phase: Vec3,
        r: Double,
        p: Double,
        n: Double,
        hand: Handedness,
    ): EvalResult {
        if (p == 0.0) {
            return EvalResult.Invalid(
                "a helix rises: with a pitch of nothing it closes back onto itself, which is a circle — " +
                    "draw one in this space instead, or state the rise per turn",
            )
        }
        if (p < 0.0) {
            return EvalResult.Invalid(
                "a helix's pitch is its rise per turn and cannot be negative (${Frames3.mm(p)} mm): a coil " +
                    "that descends while it turns ${hand.word} *is* the ${
                        (if (hand == Handedness.RIGHT) Handedness.LEFT else Handedness.RIGHT).word
                    } coil, so state that handedness rather than a negative pitch",
            )
        }
        if (n <= 0.0) {
            return EvalResult.Invalid(
                "a helix needs a positive number of turns — no turns is a point, and a negative count is " +
                    "this same coil on the other side of its axis point, which is said by turning the " +
                    "axis round",
            )
        }
        return EvalResult.Ok(Path3Value(Path3(listOf(Curve3Element.Helix3.about(origin, axis, phase, r, p, n, hand)))))
    }

    /**
     * **Two views combined** (OP-26, step 5): the run in space whose projection onto [planeA] is the curve
     * [viewA] drawn there and onto [planeB] is [viewB] — the drawing board's own construction, made
     * parametric.
     *
     * A route drawn twice, in a plan and in an elevation, *is* a route in space, and it has been read off
     * two drawings that way for as long as there have been drawings. So this needs no editing surface of its
     * own: both inputs are ordinary sketch curves picked in ordinary spaces, and what the operation adds is
     * the arithmetic that says where their projection lines cross ([Combine3]).
     *
     * **Four inputs, and the run rides all four** — which is the parenting rule paying out twice over
     * (OP-26). Drag a point of the plan and the run follows; drag the elevation and it follows; tilt or
     * re-anchor either *space* and it follows that too, because a space is a node like anything else. There
     * is nothing copied anywhere, so there is nothing to keep in step.
     *
     * The views are taken as **untyped refs** for the reason a loft's guide is: what a view has to be is any
     * drawn curve, and the 2D curve values are six different types with one thing in common. A pick that is
     * not one of them is refused here, by name, where the value is.
     *
     * Everything else it can refuse — parallel spaces, a view that doubles back along the common direction,
     * two views whose ranges do not overlap — is a condition on *values*, so each is node invalidity with a
     * reason that heals (OP-3). See [Combine3] for what each one means and for where the answer is exact.
     */
    fun combinedViews(
        planeA: PlaneRef,
        viewA: Ref<*>,
        planeB: PlaneRef,
        viewB: Ref<*>,
    ): Path3Ref =
        op(planeA, viewA, planeB, viewB) { args ->
            val a =
                guidePieces(args[1])
                    ?: return@op EvalResult.Invalid("the first view must be a curve — a segment, an arc, a Bézier or a conic")
            val b =
                guidePieces(args[3])
                    ?: return@op EvalResult.Invalid("the second view must be a curve — a segment, an arc, a Bézier or a conic")
            val (path, why) =
                Combine3.combined((args[0] as PlaneValue).plane, a, (args[2] as PlaneValue).plane, b)
            if (path == null) {
                EvalResult.Invalid(why ?: "these two views cannot be combined into one run")
            } else {
                EvalResult.Ok(Path3Value(path))
            }
        }

    /**
     * The **joining piece** between [endA] of [a] and [endB] of [b] (OP-26, step 7) — a *connect*, derived
     * from the two endpoint tangents plus the two tensions, and never solved for.
     *
     * **Both curves are nodes, so the connection rides both of them** — and, through them, everything either
     * was built on: drag a point one run passes through, retype a helix's pitch, tilt the datum a combined
     * view was drawn in, and the joining piece follows by recompute, still meeting both runs smoothly. That
     * is the parenting rule paying out at one remove, which is the only interesting thing about a *derived*
     * curve's provenance (OP-26's second kind).
     *
     * [endA], [endB] and [mode] are **structural**: which end of a curve is joined is a discrete choice
     * scored once from the click and then persisted in the step's `signs=` (OP-1/OP-18), and G1-or-G2 is
     * stated by which tool was used, exactly as a helix's handedness is. The tensions are ordinary scalars,
     * so they dimension, take expressions, and can be shared — one parameter node feeding both ends of a
     * bend, or every bend of a run, is what "sharing is equality" means here.
     *
     * Everything it can refuse is a condition on values, so each is invalidity with a reason that heals
     * (OP-3) — see [Connect3] for the list and for why the answer is exact in both modes.
     */
    fun connect(
        a: Path3Ref,
        endA: CurveEnd,
        b: Path3Ref,
        endB: CurveEnd,
        tensionA: ScalarRef,
        tensionB: ScalarRef,
        mode: Continuity,
    ): Path3Ref =
        op(a, b, tensionA, tensionB) {
            val ta = sc(it[2]).requireDim(Dimension.NONE, "connect tension").value
            val tb = sc(it[3]).requireDim(Dimension.NONE, "connect tension").value
            val (path, why) =
                Connect3.connected((it[0] as Path3Value).path, endA, ta, (it[1] as Path3Value).path, endB, tb, mode)
            if (path == null) {
                EvalResult.Invalid(why ?: "these two ends cannot be joined")
            } else {
                EvalResult.Ok(Path3Value(path))
            }
        }

    /**
     * A drawing **projected onto a face** (OP-26, step 8): the curve [view], drawn in the space [from],
     * thrown along that space's own normal onto face [face] of [solid] — the engraved line, the trimmed edge,
     * the route that has to follow a surface.
     *
     * **Three inputs, and the run rides all three** — the parenting rule paying out again (OP-26). Drag a
     * point of the drawing and the projection follows; tilt or re-anchor the *space* it is drawn in and the
     * direction turns with it; stretch the **solid** and the face moves under it, taking the engraving along.
     * Nothing is copied, so there is nothing to keep in step.
     *
     * [face] is **structural**: an index into [Section3.faces]'s provenance order (OP-8's own kind of address
     * — a name built from the feature's parameters, never re-identified from mesh topology), scored once from
     * the gesture and thereafter restated by the step's `signs=` and taken verbatim (OP-1/OP-18). The view is
     * taken as an **untyped ref** for the reason a loft's guide and a combined view are: what it has to be is
     * any drawn curve, and the 2D curve values are six types with one thing in common.
     *
     * Everything it can refuse is a condition on **values**, so each is invalidity with a reason that heals
     * (OP-3): a body whose faces are emergent rather than named (an import, a general boolean — the sentence
     * [Section3.faces] already writes), a face index the body no longer has, a face that is not a plane, and a
     * direction lying in the face. See [Project3] for where the answer is exact, where it is fitted, and why a
     * run that hangs over the edge of the face is reported rather than clipped or refused.
     */
    fun projectedOntoFace(
        view: Ref<*>,
        from: PlaneRef,
        solid: SolidRef,
        face: Int,
    ): Path3Ref =
        op(view, from, solid) { args ->
            val pieces =
                guidePieces(args[0])
                    ?: return@op EvalResult.Invalid("what is projected must be a curve — a segment, an arc, a Bézier, a conic or an outline")
            val feature = (args[2] as SolidValue).solid.feature
            val (faces, why) = Section3.faces(feature)
            if (faces == null) {
                return@op EvalResult.Invalid(why ?: "this body has no named faces to project onto")
            }
            val patch =
                faces.getOrNull(face)
                    ?: return@op EvalResult.Invalid(
                        "this body now has ${faces.size} face(s), so the face this curve was thrown at " +
                            "(#${face + 1}) is gone — put it back, or project onto one it still has",
                    )
            val (made, whyNot) = Project3.projectedOnto(pieces, (args[1] as PlaneValue).plane, patch)
            if (made == null) {
                EvalResult.Invalid(whyNot ?: "this drawing cannot be projected onto that face")
            } else {
                EvalResult.Ok(Path3Value(made.path))
            }
        }

    /**
     * A **drawing lifted into space** (OP-26, step 1's missing source): the drawn curves [views], read in the
     * plane [from] they are drawn in, as the one curve in space they already describe.
     *
     * **The trivial source, and the one that was missing.** A curve's construction is always parented (OP-26),
     * so a drawing in a space *is* geometry in the world — a plan outline is a route round a building and a
     * filleted profile is the path a bead runs on. Nothing is sampled, nothing is fitted that was not already
     * a conic ([Intersect3.liftedRun] states the contract), and nothing is copied: the drawn curves and the
     * plane are inputs, so dragging a corner, retyping a fillet radius or tilting the datum moves the run by
     * recompute, with nothing rebuilt (OP-21).
     *
     * [closed] is **structural** and comes from what was picked — an outline, an area, a circle, or a chain
     * the gesture closed — never from measuring whether the last piece happens to meet the first (OP-21's
     * rule, and [Path3.closed]'s own). The views are taken as **untyped refs** for the reason a loft's guide
     * and a projected drawing are: what they have to be is any drawn curve, and the 2D curve values are six
     * types with one thing in common.
     *
     * Everything else is a condition on **values**, so each is invalidity with a reason that heals (OP-3): a
     * pick whose value is not a drawn curve at all, a chain whose pieces do not meet (with the gap), and a
     * closed pick that does not close.
     */
    fun liftedRun(
        views: List<Ref<*>>,
        from: PlaneRef,
        closed: Boolean,
    ): Path3Ref =
        op(from, *views.toTypedArray()) { args ->
            val pieces = ArrayList<ProfileElement>()
            for (i in 1 until args.size) {
                pieces.addAll(
                    liftablePieces(args[i])
                        ?: return@op EvalResult.Invalid(
                            "what is lifted must be a drawn curve — a segment, an arc, a Bézier, a conic, an " +
                                "outline or an area",
                        ),
                )
            }
            if (pieces.isEmpty()) return@op EvalResult.Invalid("there is nothing drawn here to lift")
            val (chained, why) =
                if (closed) {
                    val (loop, reason) = GeomMath.chainLoop(pieces)
                    loop?.elements to reason
                } else {
                    GeomMath.chainRun(pieces)
                }
            if (chained == null) return@op EvalResult.Invalid(why ?: "these pieces do not make one run")
            EvalResult.Ok(Path3Value(Intersect3.liftedRun(chained, (args[0] as PlaneValue).plane, closed).first))
        }

    /**
     * Whether lifting [views] into [plane] would have to **fit** a conic — what a status line reports about a
     * lift's exactness (OP-15), asked of the same pieces the node itself lifts rather than of a second reading.
     *
     * Takes the plane as a **value** rather than as a node, so that a sentence about a build never has to
     * build anything: a status line is a reader.
     */
    fun liftIsFitted(
        views: List<Ref<*>>,
        plane: Plane3,
        ev: Evaluator,
    ): Boolean {
        val pieces = views.flatMap { liftablePieces(it, ev) ?: return false }
        return Intersect3.liftedRun(pieces, plane, closed = false).second
    }

    /**
     * The drawn pieces a **lift** reads off one value — [guidePieces] plus the one case it does not have,
     * an **area**, whose run is its outer boundary.
     *
     * Separate from [guidePieces] rather than folded into it, because the two questions are different: a
     * loft's *guide* or a projected drawing is a curve, and an area handed to either of those is a mistake
     * worth naming. What a *route* is, on the other hand, is exactly a boundary — a footprint is the commonest
     * thing anybody sweeps along — so an area answers with the boundary it has and its holes are no more part
     * of the route than they are part of the outline.
     */
    private fun liftablePieces(v: Value): List<ProfileElement>? =
        guidePieces(v) ?: (v as? RegionValue)?.region?.outer?.elements

    /** The same, read through [ev] from a ref — what a gesture asks before it builds. */
    fun liftablePieces(
        view: Ref<*>,
        ev: Evaluator,
    ): List<ProfileElement>? {
        val v = (ev.eval(view.node) as? EvalResult.Ok)?.value ?: return null
        return liftablePieces(v)
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
        law: ExprLaw? = null,
    ): SolidRef =
        op(*(listOf<Ref<*>>(path, space, radius, roll, twist) + (law?.refs ?: emptyList())).toTypedArray()) {
            // **A radius that is a law of the station** (OP-26, session 77): `r(t)` is a *length*, so it
            // supersedes the typed radius rather than scaling it — the radius the region is built at is the
            // law read at the start of the run ([SweepProfile.of]). With no law the node is exactly the node
            // it always was, arity and arithmetic alike (OP-18: nothing changes meaning).
            if (law == null) {
                val r = sc(it[2]).requireDim(Dimension.LENGTH, "tube radius").mm
                sweptSolid(it, SweepProfile.Round(r))
            } else {
                val profile =
                    try {
                        SweepProfile.of(SizeLaw(law.ast, law.env(it, 5), Dimension.LENGTH, law.param, law.text))
                    } catch (e: DimensionError) {
                        return@op EvalResult.Invalid("${law.what(Dimension.LENGTH)}: ${e.message}")
                    } catch (e: ExprError) {
                        return@op EvalResult.Invalid("${law.what(Dimension.LENGTH)}: ${e.message}")
                    }
                sweptSolid(it, profile)
            }
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
     * **[anchor] is the point of the section that rides the path** (GitHub issue #15), and null is the
     * reading that existed before it: the profile's own origin. When it is given, the section is read
     * *relative to it* — every coordinate of the area minus the anchor's — so an area drawn **in place**,
     * 5 mm off its space's origin because that is where the part is, rides the run by the point the user
     * named instead of orbiting 5 mm out from it. An **input node** and not a number, which is the whole
     * argument for a point: the anchor is shared with whatever was clicked, so dragging that point — a
     * corner of the section, the coil's own start — moves the swept body by recompute, with no rebuild and
     * nothing to restate (OP-21). What it is *not* is a compensation the tool works out for the user:
     * DESIGN.md's *"explicit anchors beat compensation"*, said one feature further.
     *
     * **[section] is the *in-place* reading, and [pierce] is which crossing of it the section rides.** Where a
     * section is drawn in a plane the run goes **through** — a foundation drawn against the wall it sits by, in
     * a plane cut through the building — the point of it that travels is not something the user should have to
     * pick either: it is the point the run passes through the drawing at, and the frame there is the drawing's
     * own. So [section] is the *profile's* plane (never the run's), [pierce] the index of its crossing in
     * arc-length order, and what comes out is the section swept from exactly where it is drawn ([inPlace]).
     * The index is a **recorded** choice — scored once from the gesture and taken verbatim ever after — while
     * the crossing's *position* stays a live value, so the body follows the run and the plane and the node says
     * so by name when the crossing it rides goes away (OP-1, OP-3, OP-18).
     *
     * The three readings are exclusive and ordered by how explicit they are: a stated [anchor] wins, then the
     * in-place crossing, then the profile's own origin — the reading every drawing written before either had.
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
        anchor: PointRef? = null,
        section: PlaneRef? = null,
        pierce: Int = 0,
        law: ExprLaw? = null,
        /**
         * **The section as a family of sections** (OP-26, session 79): its own named scalars driven by laws
         * over the run, one 2D drawing read once per station.
         *
         * It **composes** with [law] rather than competing with it (the design pass's F6): the family
         * supplies the ring and the rigid law multiplies it afterwards, so a wing whose chord is a law and
         * whose whole section is then scaled is one body. Absent, this node is the node it always was —
         * arity, arithmetic and mesh alike.
         */
        family: SectionFamily? = null,
    ): SolidRef {
        require(anchor == null || section == null) { "a section rides a stated point or the run's own crossing, never both" }
        val rides = anchor ?: section
        // where the law's own inputs start, which is after the optional riding slot — held as a number
        // because that slot's presence is what decides it, and it is decided here once
        val lawFrom = if (rides == null) 5 else 6
        // …and the family's own inputs come after the law's, which is the order [SectionFamily.refs] states
        val familyFrom = lawFrom + (law?.refs?.size ?: 0)
        // What the section rides on is **one** input, in one slot, because it is one statement: a point the
        // user picked, the plane the run pierces, or — with neither — the profile's own origin, which is the
        // node this always was, arity and value alike (OP-18's rule one level down: nothing changes meaning).
        return op(
            *(
                listOfNotNull<Ref<*>>(path, space, profile, roll, twist, rides) +
                    (law?.refs ?: emptyList()) + (family?.refs ?: emptyList())
            ).toTypedArray(),
        ) {
            val region = (it[2] as RegionValue).region
            // **The section's uniform scale as a law of the station** (OP-26, session 77): dimensionless,
            // about the anchor, never a re-evaluation of the section's own sketch.
            val sizing =
                if (law == null) {
                    null
                } else {
                    try {
                        SizeLaw(law.ast, law.env(it, lawFrom), Dimension.NONE, law.param, law.text)
                    } catch (e: DimensionError) {
                        return@op EvalResult.Invalid("${law.what(Dimension.NONE)}: ${e.message}")
                    } catch (e: ExprError) {
                        return@op EvalResult.Invalid("${law.what(Dimension.NONE)}: ${e.message}")
                    }
                }
            val at = if (rides == null) null else it[5]
            if (family != null) {
                return@op familySolid(it, family, familyFrom, sizing, at, pierce)
            }
            when (at) {
                is PointValue -> sweptSolid(it, SweepProfile.Section(movedBy(region, -at.p), sizing))
                is PlaneValue -> inPlace(it, region, at.plane, pierce, sizing)
                else -> sweptSolid(it, SweepProfile.Section(region, sizing))
            }
        }
    }

    /**
     * The **family** half of [sweep]: the section re-read per station, and the body those rings carry
     * (OP-26, session 79).
     *
     * The three readings of *what rides the run* are exactly [sweep]'s own, one level in — a stated point
     * (read **per station** under the same substitutions, which is what gives a blade its pivot line by
     * construction: `qc.x = 0.25 * chord` and every station's ring is measured from its own quarter chord),
     * the in-place crossing (whose reading is a fact about the *run* and the plane, so it is taken once and
     * applied to every station, exactly as it is for a rigid law), and the section's own origin.
     */
    private fun familySolid(
        args: List<Value>,
        family: SectionFamily,
        from: Int,
        sizing: SizeLaw?,
        at: Value?,
        pierce: Int,
    ): EvalResult {
        val path = (args[0] as Path3Value).path
        val runLength = path.elements.sumOf { Curves3.arcLength(it) }
        // the in-place reading, where that is what rides the run: solved once, since where a run crosses a
        // plane is a fact about the two of them and not about the section's size there
        var seed: FrameSeed? = null
        var inPlaceAnchor: Vec2? = null
        var fromBehind = false
        if (at is PlaneValue) {
            val (reading, why) = Pierce3.readingAt(path, at.plane, pierce)
            if (reading == null) return EvalResult.Invalid(why ?: "this section does not cross the run's own plane")
            seed = reading.seed
            inPlaceAnchor = reading.anchor
            fromBehind = reading.fromBehind
        }
        val (built, noFamily) =
            SectionFamilies.build(family, args, from, runLength, GeomMath.TESS_TOL_MM) { region, anchorAt ->
                when {
                    inPlaceAnchor != null -> readFrom(region, inPlaceAnchor, fromBehind)
                    anchorAt != null -> movedBy(region, -anchorAt)
                    else -> region
                }
            }
        if (built == null) return EvalResult.Invalid(noFamily ?: "cannot read this section along the run")
        return sweptSolid(args, built.profile.copy(law = sizing), seed, built.twist)
    }

    /**
     * The **in-place** reading of a sweep: the section carried by the point the run goes through its own plane
     * at, in the frame that plane's axes state there (OP-26, the in-place sweep — the user's own design).
     *
     * Two things, and they are one statement rather than two: the crossing is the *anchor* (so a section drawn
     * where the material is rides the run from there, with nothing moved and nothing picked), and it is where
     * the frame is *seeded* (so the drawing is literally the run's section there, standing the way it was
     * drawn). Either alone would be half an answer — the anchor without the frame puts the section on the run
     * lying the wrong way up, and the frame without the anchor stands the drawing correctly somewhere it is not.
     *
     * Everything about *where* the run and the plane are is a **value**, so it is reported as node invalidity
     * that heals (OP-3): the crossing the section rides can go away when the run is dragged clear of the plane,
     * and it comes back the moment the run does. What is never re-decided is *which* crossing — that is a
     * recorded choice ([Pierce3.readingAt]).
     */
    private fun inPlace(
        args: List<Value>,
        region: Region,
        section: Plane3,
        pierce: Int,
        law: SizeLaw? = null,
    ): EvalResult {
        val (reading, why) = Pierce3.readingAt((args[0] as Path3Value).path, section, pierce)
        if (reading == null) return EvalResult.Invalid(why ?: "this section does not cross the run's own plane")
        return sweptSolid(args, SweepProfile.Section(readFrom(region, reading.anchor, reading.fromBehind), law), reading.seed)
    }

    /** [region] with [d] added to every one of its coordinates — how an anchored [sweep] reads its section. */
    private fun movedBy(
        region: Region,
        d: Vec2,
    ): Region {
        val t = Affine.translation(d)
        // a translation cannot turn a loop round, so the windings are carried over rather than re-oriented
        return Region(GeomMath.transform(region.outer, t), region.holes.map { GeomMath.transform(it, t) })
    }

    /**
     * [region] read from [anchor], and — when the run crosses the plane the other way ([fromBehind]) — from
     * the drawing's other side.
     *
     * The mirror is not a decoration: a right-handed moving frame whose reference is the plane's x axis has
     * the plane's y axis for its second **only** where the run crosses the way the plane faces, so on the
     * other kind of crossing reading the drawing straight would stand it upside down about the crossing. The
     * reflection is what puts every point of it back exactly where it was drawn (see [Pierce3.InPlaceReading]),
     * and the loop is re-wound with it — `GeomMath.transform` keeps the orientation a loop had, which is what
     * keeps a reflected area an area (OP-14).
     */
    private fun readFrom(
        region: Region,
        anchor: Vec2,
        fromBehind: Boolean,
    ): Region {
        if (!fromBehind) return movedBy(region, -anchor)
        val t = Affine(1.0, 0.0, 0.0, -1.0, -anchor.x, anchor.y)
        return Region(GeomMath.transform(region.outer, t), region.holes.map { GeomMath.transform(it, t) })
    }

    /** The half [tube] and [sweep] share: the frame's inputs read, and the solid or the reason it is not one. */
    private fun sweptSolid(
        args: List<Value>,
        profile: SweepProfile,
        seed: FrameSeed? = null,
        /** The run's own twist as a law of the station, where one is stated (OP-26, session 79). */
        twistLaw: SizeLaw? = null,
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
                seed = seed,
                twistLaw = twistLaw,
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

    /**
     * The **skin over drawn sections** (OP-26's hull route, session 78): [parts] in station order, run
     * [row]-wise, with the correspondence [matches] states.
     *
     * One node for the whole body, exactly as the loft is one: every section's own sketch and every station's
     * own distance are inputs, so retyping a distance slides that station and the skin follows it, and
     * dragging a corner of any section reshapes the skin with nothing rebuilt (OP-21). What is **structural**
     * is which sections there are and the order they stand in — read once from the stated distances by the
     * gesture that recorded them — and what is a value is everything else, the distances included: a slide
     * that put two stations in one plane, or one past its neighbour, is a fold and the body says so by naming
     * the two distances (OP-3, and the session-65 law about what a refusal may speak of).
     *
     * [matches] are **piece indices**, in each section's own boundary order — the durable name a stored face
     * address already uses. The *file* records them as the curves' script names, which is the naming
     * authority's business and not this node's (see `Document.skinSolid`).
     */
    fun skin(
        parts: List<SkinPart>,
        row: SkinRow,
        matches: List<SkinMatch> = emptyList(),
    ): SolidRef {
        val refs = ArrayList<Ref<*>>(parts.size * 2)
        for (p in parts) {
            refs.add(p.sketch)
            refs.add(p.at)
        }
        val n = parts.size
        return op(*refs.toTypedArray()) { args ->
            val sections = ArrayList<SkinSection>(n)
            for (i in 0 until n) {
                val sketch = (args[2 * i] as SketchValue).sketch
                val at = sc(args[2 * i + 1]).requireDim(Dimension.LENGTH, "station distance").mm
                sections.add(SkinSection(sketch, at))
            }
            val (solid, why) = Skin3.skin(sections, row, matches)
            if (solid == null) EvalResult.Invalid(why ?: "cannot skin these sections") else EvalResult.Ok(SolidValue(solid))
        }
    }

    // ---- booleans between prismatic solids (OP-22) ----
    // One op node each; the slab algebra lives inside `compute`, which is where value-dependent work
    // belongs (OP-21's rule). The operands are ordinary solid nodes, so a boolean's result is an operand
    // of the next boolean — prisms are closed under these three operations, which is the point.

    /**
     * A drawn **chain**'s own finite pieces, read as a [Profile] — the open run a blend's section is drawn
     * as (GitHub #30).
     *
     * One accessor node and nothing else: the chain's two rays are not part of a section (a profile has two
     * ends, and they are the two setbacks), so what is taken is exactly its finite run, in its own order.
     */
    fun chainProfile(chain: ChainRef): ProfileRef =
        op(chain) { EvalResult.Ok(ProfileValue(Profile((it[0] as ChainValue).chain.pieces))) }

    // ---- the edge blend: the 2D fillet, one dimension up (session 71, slice 2) ----

    /**
     * A **blend along the edges of [base]** — the 2D fillet construction run in the edge's own normal
     * section, swept along the edge, applied by a boolean ([Blend3]).
     *
     * One node for the whole feature, exactly as an extrude is one, and both its inputs are nodes: retype
     * [size] and the rounding changes with nothing rebuilt; edit anything the body is built from and the
     * blend follows it round (OP-21). **The radius is an ordinary parameter**, so *"the same radius on all
     * these edges"* is one parameter feeding many blends — equality by sharing, the no-solver stance's own
     * answer — and it is expression-bindable like any other scalar.
     *
     * Everything else is **structure** and therefore an argument rather than a value: [kind] is which tool
     * row was used, [whole] and [address] are what the click named (one edge, or a face's whole boundary
     * chain), and [choices] are the discrete choices that click scored — which sector the blend fills, which
     * branch its centre is, and whether that sector is material. All of them are recorded in the step's
     * `signs=` and taken verbatim on replay, never re-scored (OP-1/OP-18).
     *
     * **Two tiers, decided by the graph and not by a value** (session 71, slice 3). When [applyTo] and
     * [base] are the same node the result is a `Feature3.Blend` — the dress-up feature, whose face list
     * extends the base's — and that is the ordinary case, a first blend and every blend of a blend. When
     * they differ, an ordinary boolean stands between the two and the body being cut has no face list to
     * extend, so the result stays a `Feature3.MeshBoolean` with a silhouette plan. **No stored byte moved
     * for this**: the step is the same `tool` row with the same address and the same `signs=`, and what
     * changed is the feature the same step builds, which is eval-time (OP-18 protects stored literals).
     *
     * Invalid with a reason that heals (OP-3) for everything geometric — see [Blend3] for the list, of which
     * the two that matter are a size that reaches past one of the two faces (the message names the largest
     * that fits) and an edge whose normal section is not one this rounding can say.
     */
    fun blend(
        applyTo: SolidRef,
        base: SolidRef,
        space: PlaneRef,
        size: ScalarRef?,
        kind: BlendKind,
        whole: Boolean,
        address: Int,
        choices: List<BlendChoice>,
        /**
         * The **drawn section** a [BlendKind.PROFILE] blend runs along the edge, and null for the two
         * built-ins, whose section is stated by [size] alone (GitHub #30).
         *
         * An ordinary operand rather than a stored literal, which is what makes the general tier follow the
         * DAG like everything else: drag an end of the profile and the body re-blends on the same recompute
         * a retyped radius uses, and deleting the profile cascades because the step names it (OP-21/OP-18).
         */
        profile: ProfileRef? = null,
        /**
         * The **tangent-continuous run** the picked address stands for, resolved by the editor from the 2D
         * joint registry (GitHub #29) — empty for the plain reading, one edge per address.
         *
         * Structure at build time (OP-21), and that is the whole reason it is an argument rather than a
         * lookup inside `compute`: *which* edges a pick names is decided when the step runs — by the
         * registry, which a replay rebuilds exactly — so the number of wedges swept can never move with the
         * numbers. Nothing about it is stored: the step still records the picked edge index in `signs=`, and
         * a replay resolves the run again from the drawing it just rebuilt.
         */
        run: List<Int> = emptyList(),
    ): SolidRef {
        // structure at build time (OP-21): whether this blend chains onto an earlier one is a fact about the
        // *graph*, so it decides the arity here rather than being read off a value inside compute
        val chained = applyTo !== base
        // **the chain's undressed root, as an operand** (session 81). Rounding an upright that an earlier
        // corner pivots about *changes* that corner, and the material the old one took cannot be given back
        // by another cut — so the chain is rebuilt from the body it all started on. Which node that is, is a
        // fact this builder already knows: every blend records the root of the chain it makes, so the next
        // one reads it off the graph rather than looking for it in a value. The arity is structural, exactly
        // as [chained] is, and nothing stored changes — no step, no `signs=`, no version.
        val chainRoot = if (chained) null else blendChainRoot[base.node]
        // the operand indices are computed here, once, because three of them are optional: a chained blend
        // carries its base, a blend is stated by a size **or** by a drawn profile, and a blend of a blend
        // carries the root of its chain
        val iBase = if (chained) 1 else 0
        val iSpace = iBase + 1
        val iSize = if (size == null) -1 else iSpace + 1
        val iProfile = if (profile == null) -1 else maxOf(iSpace, iSize) + 1
        val iRoot = if (chainRoot == null) -1 else maxOf(iSpace, iSize, iProfile) + 1
        val made: SolidRef =
            op(*listOfNotNull<Ref<*>>(applyTo, base.takeIf { chained }, space, size, profile, chainRoot).toTypedArray()) {
                val tip = (it[0] as SolidValue).solid
                val body = if (chained) (it[iBase] as SolidValue).solid else tip
                openShellOf(tip)?.let { why -> return@op EvalResult.Invalid(why) }
                val plane = (it[iSpace] as PlaneValue).plane
                val r = if (iSize < 0) 0.0 else sc(it[iSize]).requireDim(Dimension.LENGTH, "${kind.word} ${kind.sizeWord}").mm
                val drawn = if (iProfile < 0) emptyList() else (it[iProfile] as ProfileValue).profile.elements
                val sec = BlendSection(kind, r, drawn)
                val (resolved, whyTargets) =
                    if (!whole && run.isNotEmpty()) run to null else Blend3.targets(body.feature, whole, address)
                val targets = resolved
                if (targets == null) return@op EvalResult.Invalid(whyTargets ?: "this solid has no edge to blend there")
                val rootBody = if (iRoot < 0) null else (it[iRoot] as SolidValue).solid
                val (out, why) = Blend3.blended(body, tip, targets, sec, choices, rootBody)
                if (out == null) return@op EvalResult.Invalid(why ?: "cannot blend that edge")
                val dressed =
                    if (!chained) {
                        // **The dress-up feature** (session 71, slice 3): the body addressed *is* the body cut,
                        // so the result is the base with its blend on it — a feature whose face list extends the
                        // base's ([Feature3.Blend]). The triangles are the ones the boolean just made, restated
                        // under the analytic feature and sharing the very same derivation, which is the whole
                        // point of `Solid3.restated`: one mesh, two statements of the same body.
                        val f = Feature3.Blend(body.feature, targets, kind, r, choices.take(targets.size), drawn)
                        // it must still say why, if the dressed list cannot be stated — otherwise a body would
                        // claim faces it cannot produce, and every reader downstream would meet the refusal
                        // instead of this node (OP-3: the refusal belongs where the decision is)
                        val (faces, whyFaces) = Section3.faces(f)
                        if (faces == null) return@op EvalResult.Invalid(whyFaces ?: "this blend has no faces to name")
                        out.restated(f)
                    } else {
                        // **The mesh tier, unchanged and stated**: the body cut is *not* the body addressed — a
                        // blend after an ordinary boolean — so there is no face list to extend (the union's own
                        // faces are emergent, OP-9's sink rule) and the result stays a mesh boolean. It gets a
                        // plan ([Feature3.MeshBoolean.plan]) so it is drawn and clickable, computed once here
                        // because this is the node that knows which plane the body is shown in (`Silhouette`).
                        (out.feature as? Feature3.MeshBoolean)?.let { g -> out.restated(g.copy(plan = Silhouette.of(out.mesh, plane))) } ?: out
                    }
                EvalResult.Ok(SolidValue(dressed))
            }
        // …and this blend's own chain root, for whatever blends on it next: its base's, or its base itself
        if (!chained) blendChainRoot[made.node] = chainRoot ?: base
        return made
    }

    /**
     * The **undressed root** of each blend node's chain — the body its first rounding was cut out of.
     *
     * Recorded here, at build time, because that is where the answer *is*: a blend knows which node it
     * chains on, so the root is an ordinary structural fact about the graph rather than something to be
     * rediscovered from a feature or guessed at from a mesh (OP-21). Keyed by node identity, so a replay
     * that rebuilds the same construction rebuilds the same map, and nothing about it is stored.
     */
    private val blendChainRoot = HashMap<Node, SolidRef>()

    // ---- shelling: a wall of a stated thickness, hollowed out by construction (session 75) ----

    /**
     * [base] **hollowed to a wall of [thickness]**, with the faces [openFaces] names left open ([Shell3]).
     *
     * One node for the whole feature, exactly as an extrude is one, and both its inputs are nodes: retype the
     * thickness and the wall changes with nothing rebuilt; edit anything the body is built from and the shell
     * follows it. **The thickness is an ordinary parameter**, so *"the same wall everywhere on this part"* is
     * one parameter feeding many shells — equality by sharing, the no-solver stance's own answer — and it is
     * expression-bindable like any other scalar (`d/8` is a test of this).
     *
     * [openFaces] is **structure**, not a value: which face the click named is a discrete choice scored once
     * and thereafter taken verbatim from the step's `signs=` (OP-1/OP-18). Re-scoring it on replay would open a
     * different face as soon as an edit slid the body under the recorded click, which is the fillet's own
     * lesson two features back.
     *
     * **There is no second tier here, and that is a decision** (see DESIGN.md, session 75). A blend has one —
     * a blend applied *after* an ordinary boolean addresses the analytic body under the part and cuts the tip —
     * because a rounding is a local operation on an edge that survives the fusion. A shell is not: its cavity
     * is a function of the *whole* body being hollowed, so hollowing a fused part with one operand's cavity
     * would leave a wall that is nowhere near one thickness. The tip is therefore addressed **and** hollowed,
     * and a tip with no offset profile of its own refuses by name ([Shell3.shellable]) rather than quietly
     * shelling something else.
     *
     * Invalid with a reason that heals (OP-3) for everything geometric — the thickness the body cannot host
     * names the thickest that fits, and a face the cavity cannot open says why.
     */
    fun shell(
        base: SolidRef,
        thickness: ScalarRef,
        openFaces: List<Int>,
    ): SolidRef =
        op(base, thickness) {
            val body = (it[0] as SolidValue).solid
            openShellOf(body)?.let { why -> return@op EvalResult.Invalid(why) }
            val t = sc(it[1]).requireDim(Dimension.LENGTH, "wall thickness").mm
            val (out, why) = Shell3.shelled(body, t, openFaces)
            if (out == null) return@op EvalResult.Invalid(why ?: "cannot hollow this body")
            // **The dress-up feature**: the triangles are the boolean's, restated under the analytic shell and
            // sharing the very same derivation ([Solid3.restated]) — one mesh, two statements of one body. It
            // must still say why if the shelled face list cannot be stated, or a body would claim faces it
            // cannot produce and every reader downstream would meet the refusal instead of this node (OP-3).
            val f = Feature3.Shell(body.feature, t, openFaces)
            val (faces, whyFaces) = Section3.faces(f)
            if (faces == null) return@op EvalResult.Invalid(whyFaces ?: "this shell has no faces to name")
            EvalResult.Ok(SolidValue(out.restated(f)))
        }

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
            val (solid, why) = booleanValue(kind, sa, sb)
            if (solid == null) EvalResult.Invalid(why ?: "cannot combine these solids") else EvalResult.Ok(SolidValue(solid))
        }

    /**
     * The dispatch itself, as a function of two **values** — extracted so that every operation whose removed
     * operand is a solid takes the *same* route, and there is one place that decides which engine runs.
     *
     * The cut by an unbounded chain ([splitSolid]) is the second caller, and it is exactly why this is not
     * inlined in [booleanOf]: a second dispatch would be a second thing to keep in step, and "which path did
     * this take" is the one question about a boolean that must have a single answer.
     */
    private fun booleanValue(
        kind: BoolOp,
        a: Solid3,
        b: Solid3,
    ): Pair<Solid3?, String?> =
        // **The one operation whose mesh stays eager, and the reason is doctrinal** ([Solid3]). Every
        // constructed feature can say *in advance* why it cannot be built — a degenerate profile, an empty
        // area, a bend the section outgrows — so its triangles wait for somebody to want them while its
        // refusal stays where OP-3 and OP-9 put it, at evaluation time. A general boolean cannot: its
        // operands *are* triangles, and whether they intersect in a solid at all is the engine's verdict on
        // them. Deferring it would mean an invalid body first discovered while drawing — either thrown
        // where nothing catches it or silently absent — which is exactly the outcome watertight-or-refused
        // exists to forbid. So it runs now, and forcing its operands' meshes is part of running it.
        // The dispatch itself is [Geom3.combine] — one authority for "which engine ran", shared with the
        // swept cut and (since session 71) with the edge blend.
        Geom3.combine(kind, a, b)

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

    // ---- cutting with an unbounded chain (OP-22's extension, step 1) ----
    // A cut does not need the removed operand to be bounded: *a surface and a side* is the honest tool, and
    // the bound is derived from the target at evaluation time. Two node kinds, and the second one is a
    // **split** — a cut is split keeping one side, which is why there is no separate cut node.

    /**
     * The **unbounded chain** through [points]: straight spans between them, with a ray running out of each
     * end (see [Chains.through] for how the two ray directions are stated and what that costs).
     *
     * **One node over the points themselves**, so a chain is live exactly as a curve in space is: click an
     * existing point and the chain *shares its node*, drag any of them and everything cut with the chain
     * recomputes. Nothing is copied, so there is nothing to keep in step.
     *
     * Invalid with a reason that heals (OP-3) when two consecutive points **coincide** — a span with no
     * direction has no ray to continue it — and when the chain **meets itself**, which is the properness
     * condition rather than a tidiness rule: a curve that crosses itself does not separate its plane into two
     * sides, so "the side to keep" has no referent (see [Chain]). Both are conditions on *values*: drag the
     * points clear and the cut comes back.
     */
    fun chainThrough(points: List<PointRef>): ChainRef =
        op(*points.toTypedArray()) { args ->
            val (chain, why) = Chains.through(args.map { (it as PointValue).p })
            if (chain == null) return@op EvalResult.Invalid(why ?: "cannot build a chain through these points")
            Chains.defect(chain)?.let { return@op EvalResult.Invalid(it) }
            EvalResult.Ok(ChainValue(chain))
        }

    /**
     * A **line as a chain**: the degenerate open case, and hence again the same operator (see
     * [Chains.ofLine]).
     *
     * A coercion node exactly like [closedChain] is: the drawing already holds infinite lines — drawn,
     * constructed as a perpendicular or a bisector, *mirrored* — and every one of them separates its plane,
     * so nothing has to be drawn a second time to cut with one. Live like everything else: move a point the
     * line is built on and every cut made with it recomputes.
     */
    fun lineChain(line: LineRef): ChainRef =
        op(line) { args ->
            val (chain, why) = Chains.ofLine((args[0] as LineValue).line)
            if (chain == null) return@op EvalResult.Invalid(why ?: "cannot read this line as a cutting chain")
            EvalResult.Ok(ChainValue(chain))
        }

    /**
     * An area's boundary **as a chain**: the closed case of the same value, and hence of the same operator.
     *
     * A closed loop separates its plane too — a bounded inside, an unbounded outside — so a cut by one is the
     * through-slot and the through-bore, reached with no special case anywhere. This is a coercion node like
     * [region] is: the drawing already holds closed curves, so nothing new has to be *drawn* to make one.
     */
    fun closedChain(area: RegionRef): ChainRef =
        op(area) { args ->
            val chain = Chain.Closed((args[0] as RegionValue).region)
            Chains.defect(chain)?.let { return@op EvalResult.Invalid(it) }
            EvalResult.Ok(ChainValue(chain))
        }

    /**
     * **Split** [solid] with [chain] drawn on [plane], and keep the half [side] names: `+1` the one to the
     * left of the chain's direction of travel, `-1` the one to its right (see [Chain]).
     *
     * [side] is **structural** — decided once by the gesture that built this node and then persisted as a
     * sign (OP-1/OP-18), never re-scored on replay. That is the whole reason it is an argument here rather
     * than something computed from a click position inside `compute`: an edit that moves the geometry must
     * not be able to change which half a drawing keeps.
     *
     * Everything else is value-dependent and therefore lives in here (OP-21's rule): the tool is bounded to
     * the target's own extent plus a margin, closed strictly outside it, and handed to the ordinary boolean
     * engine — the *same* dispatch every other boolean takes ([booleanValue]), so a chain cut on a common
     * axis is exact (OP-22) and a cross-axis one goes to Manifold (OP-9), and neither is a new path.
     *
     * **Both halves are computed, and that is the point rather than a cost.** The discarded half is what
     * makes the two refusals exact instead of a comparison against a tolerance: an empty *kept* half means
     * the cut removes the whole body, an empty *discarded* half means it removes nothing — and that silence
     * is precisely what picking the wrong side looks like, so it is said (OP-3: invalid with a reason,
     * healing the moment the chain crosses the material).
     *
     * **[along] is the directrix, and it is the operator's second operand rather than a variant of it**
     * (OP-22's extension, step 2). With none, the cut runs straight through along [plane]'s normal — which
     * *is* the degenerate directrix, not a different operation, and [Chains.sweptTools] says so by handing a
     * straight-along-the-normal directrix back to the same code the null case takes. [carry] is how the
     * section travels while it does ([CarryMode]): **structural**, decided by the construction — in the
     * editor by which tool row was used, exactly as a helix's handedness is — and never inferred from the
     * geometry. Both are arguments here and not values inside `compute` for that reason: they say what is
     * built, and only *how big* it has to be is derived from the target.
     */
    fun splitSolid(
        solid: SolidRef,
        chain: ChainRef,
        plane: PlaneRef,
        side: Int,
        along: Path3Ref? = null,
        carry: CarryMode = CarryMode.ROTATING,
    ): SolidRef =
        op(*listOfNotNull(solid, chain, plane, along).toTypedArray()) {
            val target = (it[0] as SolidValue).solid
            openShellOf(target)?.let { why -> return@op EvalResult.Invalid(why) }
            // **This op reads its target's triangles, and it cannot wait for a demand** ([Solid3]): the tool is
            // bounded to the body, so *how big* it has to be comes out of the body's own extent, and two of
            // this node's refusals compare volumes. Both are the node's *value* rather than its picture, so
            // the mesh is forced here — a cut is one of the two places (the other is the general boolean) where
            // deferral would only move the same work later and take a named refusal with it.
            val directrix = if (along == null) null else (it[3] as Path3Value).path
            val (tools, whyTools) =
                if (directrix == null) {
                    Chains.tools((it[1] as ChainValue).chain, (it[2] as PlaneValue).plane, target.mesh)
                } else {
                    Chains.sweptTools((it[1] as ChainValue).chain, (it[2] as PlaneValue).plane, directrix, carry, target.mesh)
                }
            if (tools == null) return@op EvalResult.Invalid(whyTools ?: "cannot bound the cutting tool to this solid")
            val kept = if (side >= 0) tools.first else tools.second
            val dropped = if (side >= 0) tools.second else tools.first
            val (keptSolid, whyKept) = booleanValue(BoolOp.INTERSECT, target, kept)
            val (droppedSolid, _) = booleanValue(BoolOp.INTERSECT, target, dropped)
            val whole = Geom3.volume(target.mesh)
            when {
                keptSolid != null && droppedSolid == null && sameVolume(Geom3.volume(keptSolid.mesh), whole) ->
                    EvalResult.Invalid(
                        "this cut leaves the solid untouched — the chain passes it by on the side that is kept, " +
                            "which is exactly what picking the wrong side looks like: keep the other side, or move " +
                            "the chain across the body",
                    )
                keptSolid == null && droppedSolid != null && sameVolume(Geom3.volume(droppedSolid.mesh), whole) ->
                    EvalResult.Invalid(
                        "this cut removes the whole solid — an empty result is not a body, so it is said rather " +
                            "than shown as nothing: keep the other side, or move the chain into the material",
                    )
                keptSolid == null -> EvalResult.Invalid(whyKept ?: "cannot cut this solid with this chain")
                else -> EvalResult.Ok(SolidValue(keptSolid))
            }
        }

    /**
     * Whether two volumes are the same body's (mm³), relatively.
     *
     * Used only to **classify a refusal that has already happened** — never to decide one — which is why a
     * relative comparison is honest here: the exact path (OP-22) agrees to the last bits and the general one
     * (OP-9) carries float32 positions, so 1e-6 relative is far above the second and far below any cut that
     * removes material worth speaking of.
     */
    private fun sameVolume(
        a: Double,
        b: Double,
    ): Boolean = abs(a - b) <= 1e-6 * maxOf(1.0, abs(b))

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
        // The one solid whose mesh was never derived from a feature: a file said what the triangles are, so
        // there is nothing to defer and nothing that could refuse later ([Solid3.of]).
        val value = SolidValue(Solid3.of(Feature3.Imported(source, openShell = Watertight.defect(posed)), posed))
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
            // An imported body's triangles are the only thing it has, and they are already in hand, so
            // projecting them costs a pass over them and no derivation ([Solid3.of]).
            is Feature3.Imported ->
                solid.restated(Feature3.Imported(f.source, Silhouette.of(solid.mesh, plane), f.openShell))
            // …and a **sweep**, for the identical reason (OP-26): its plan is a silhouette rather than a
            // sketch, so it is stated in some plane's coordinates and the move dropped it. Re-projected here,
            // in the plane the body is now being shown in, which is the only place that knows one — and off
            // the *run* rather than off the triangles ([Geom3.sweptPlan]), because a placed sweep must not be
            // the one body a plan drag has to mesh.
            is Feature3.Sweep -> solid.restated(f.copy(plan = Geom3.sweptPlan(f, plane)))
            else -> solid
        }

    // ---- imported curves: the same two nodes one dimension down (OP-26, step 9) ----

    /**
     * A **run literal**: the polyline a file gave us, named by the [source] it came from (OP-26, step 9).
     *
     * The exact twin of [importedSolid], and deliberately not a generalisation of it: a node with **no
     * inputs**, so it is a constant of the graph rather than a degree of freedom, and the memo (OP-5) builds
     * its value once and hands it out by pointer ever after. What the step stores is the points
     * ([constructit.exchange.PathText]), never the file's bytes, for the reason a mesh literal does — replay
     * must not re-run a reader.
     *
     * **A polyline and nothing else.** The file listed points and said which are joined; this is that chain of
     * [Curve3Element.Seg3]. No fitting, no smoothing, no arc recognition — the drawing says exactly what the
     * file said, which is what makes it a *literal* at all.
     *
     * [pose] is the file's own placement of this run, applied here rather than multiplied into the stored
     * points, so the step keeps the file's two statements apart — these points, at that pose.
     */
    fun importedPath(
        source: String,
        path: Path3,
        pose: Xform3 = Xform3.IDENTITY,
    ): Path3Ref {
        val value = Path3Value(path.movedBy(pose))
        return op { EvalResult.Ok(value) }
    }

    /**
     * **Placement** of a curve in space: [path] moved so that its own coordinates are read in the frame
     * [plane] gives, at the in-plane point [at], turned by [angle] about that plane's normal.
     *
     * [placeSolid] one dimension down, sharing its whole argument (a rigid map from an orthonormal frame, four
     * ordinary nodes, parametric like everything else) and its one implementation of what "placed" means
     * ([placementFrame]) — so an imported run is moved by exactly the map an imported body is, and a run and
     * a body welded to the same anchor point can never drift apart.
     *
     * Generic over runs rather than special to imported ones, for [placeSolid]'s reason: an import merely
     * happens to be the only caller today.
     */
    fun placeCurve(
        path: Path3Ref,
        plane: PlaneRef,
        at: PointRef,
        angle: ScalarRef,
    ): Path3Ref =
        op(path, plane, at, angle) {
            val p = (it[1] as PlaneValue).plane
            val a = pt(it[2])
            val t = sc(it[3]).requireDim(Dimension.ANGLE, "placement angle").base
            EvalResult.Ok(Path3Value((it[0] as Path3Value).path.movedBy(placementFrame(p, a, t))))
        }

    // ---- what a curve in space is defined by: its own points, as accessors (OP-26 × the key-point rule) ----

    /**
     * Where the run [path] **begins** — an accessor on the curve node, not a copy of a position.
     *
     * The 3D twin of [segmentStart] and [arcStart], and it exists for their reason: a *key point* is a
     * derived point that hangs off the geometry it belongs to, so retyping a coil's pitch, dragging a point a
     * run passes through or tilting the space it was drawn in moves this point too (the OP-21 extension's
     * *key points*, one dimension up). Nothing about which piece the run starts with is read here — [Path3]
     * answers that — so this serves a helix, a curve through points, a connect, a combined view, an
     * intersection curve and an imported wireframe with one node kind and no cases.
     *
     * Invalid with a reason that heals (OP-3) for a run with no pieces at all, which is the only way a path
     * can fail to have a beginning.
     */
    fun pathStart(path: Path3Ref): Point3Ref =
        op(path) {
            val p = (it[0] as Path3Value).path.start
            if (p == null) EvalResult.Invalid("this curve has no pieces, so it has no start point") else EvalResult.Ok(Point3Value(p))
        }

    /**
     * Where the run [path] **ends** — [pathStart]'s twin, and for a **closed** run the same place it starts,
     * because that is what closure means (the last piece hands over to the first).
     */
    fun pathEnd(path: Path3Ref): Point3Ref =
        op(path) {
            val p = (it[0] as Path3Value).path.end
            if (p == null) EvalResult.Invalid("this curve has no pieces, so it has no end point") else EvalResult.Ok(Point3Value(p))
        }

    /**
     * The **axis point of a coil** — [Curve3Element.Helix3.origin], the point the curve starts level with,
     * which is what gives a helix exactly the arc's triple of key points (centre, start, end).
     *
     * Refused as a *value* (OP-3) rather than at build time for a run that is not a single helix: which
     * pieces a path has is a fact about its own construction, and the honest place to say "this run has no
     * centre" is where the run's value is. The *gesture* says it too, by name, before it builds anything
     * (`Document.extractPoints`), so the ordinary case never produces a permanently invalid point.
     */
    fun helixCentre(path: Path3Ref): Point3Ref =
        op(path) {
            val h =
                helixPiece(it[0])
                    ?: return@op EvalResult.Invalid(
                        "only a helix has a centre: this run is a chain of segments and curves, whose defining " +
                            "points are its own — take its start and end instead",
                    )
            EvalResult.Ok(Point3Value(h.origin))
        }

    /**
     * A point **riding the coil [path]**, [angle] along it from its start (the queue's own design) — the
     * point-on-a-circle one dimension up, and the first rider whose position is not in a plane.
     *
     * **The angle is deliberately not modular, and that is the whole content of the parameter**: `450°` *is*
     * the second winding, one whole pitch above `90°`, because a coil's angle carries the rise with it
     * ([Curve3Element.Helix3.atAngle]). Reducing it into `[0, 360°)` would throw away the only thing that
     * distinguishes the fifth turn of a spring from the first, and no other input could put it back.
     *
     * **Absolute in the coil's own frame** — measured from the stored phase [Curve3Element.Helix3.u] about
     * the axis — so nothing re-anchors it when the centre moves, the radius changes or the pitch is retyped:
     * exactly [pointOnCircle]'s argument (OP-20), and [pointOnEllipse]'s (OP-24). What it *does* follow is
     * the coil: every one of those edits moves this point, because the coil is its input.
     *
     * **The sign convention**: the angle is how far the point has travelled **the way the coil turns**, so it
     * runs from 0 to `turns · 360°` for a left-hand coil exactly as for a right-hand one, and the direction
     * that is about the axis is the curve's [Handedness] — structural, never a sign on this number (OP-1, and
     * [Curve3Element.Helix3]'s own rule that nothing but `hand` decides chirality).
     *
     * Invalid with a reason that heals (OP-3), never clamped and never refused at the gesture, for the three
     * conditions on values:
     * - a run that is **not a helix** — a spline through points has no angle about anything;
     * - an angle **past the end** of the coil, which is where the curve stops rather than where the formula
     *   does: raise the turn count and the point comes back at the very place it named;
     * - a **negative** angle, which is off the near end for the same reason.
     */
    fun pointOnHelix(
        path: Path3Ref,
        angle: ScalarRef,
    ): Point3Ref =
        op(path, angle) {
            val h =
                helixPiece(it[0])
                    ?: return@op EvalResult.Invalid(
                        "a point on a helix is stated by an angle about the axis, and this run is not a helix — " +
                            "state a position along a run of any shape with a Station instead",
                    )
            val theta = sc(it[1]).requireDim(Dimension.ANGLE, "helix angle").base
            val total = h.totalAngle
            if (theta < 0.0) {
                return@op EvalResult.Invalid(
                    "this angle is ${Frames3.deg(theta)}° along the coil, and a coil starts at 0°: " +
                        "the angle runs the way the curve turns, so a negative one is off its near end",
                )
            }
            if (theta > total) {
                return@op EvalResult.Invalid(
                    "this angle is ${Frames3.deg(theta)}° along the coil, which has ${
                        Frames3.mm(h.turns)
                    } turns and therefore ends at ${Frames3.deg(total)}° — raise the turn count, or bring the angle back",
                )
            }
            EvalResult.Ok(Point3Value(h.atAngle(theta)))
        }

    /** The single [Curve3Element.Helix3] a path is, or null — what the two coil accessors above dispatch on. */
    private fun helixPiece(v: Value): Curve3Element.Helix3? =
        ((v as? Path3Value)?.path?.elements?.singleOrNull()) as? Curve3Element.Helix3

    /**
     * The **plane a flat run lies in** (OP-26, step 9) — the sketch space a wireframe's own geometry states.
     *
     * The only node in this engine that *measures* planarity, and it is confined to the one provenance where
     * planarity cannot be known instead: a constructed path through points in one space is planar
     * structurally, an imported one is a list of numbers. See [Curves3.planeOfRun] for the measurement, for
     * the tolerance and for the argument behind it.
     *
     * A run with no plane is **invalid with the reason**, and it heals (OP-3) — a placement that tilts a run
     * back into flatness brings the space and everything drawn on it straight back. The *gesture* refuses too
     * ([constructit.editor.Document.sketchFromWireframe]), which is only safe because the run it refuses on is
     * a frozen literal moved by a rigid placement, and neither of those can change the answer.
     */
    fun runPlane(path: Path3Ref): PlaneRef =
        op(path) {
            val (plane, why) = Curves3.planeOfRun((it[0] as Path3Value).path)
            if (plane == null) EvalResult.Invalid(why ?: "this run lies in no plane") else EvalResult.Ok(PlaneValue(plane))
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
     * The **curves in space where [plane] meets the solid [section] is the section of** (OP-26, step 6) — an
     * ordered solution set, OP-1's own shape one dimension up.
     *
     * **The existing section machinery, promoted rather than paralleled.** [section] is the very node a
     * working plane's context is drawn from, so the pieces this chains and lifts are the pieces on screen —
     * there is one answer to "where does this plane cut this solid", read twice. What is added is chaining
     * (the cut of one body's boundary joins where its faces meet) and lifting (through the plane's own
     * orthonormal frame, an isometry). See [constructit.geom.Intersect3] for the ordering rule, for why it is
     * stable, and for where the answer is exact.
     *
     * Both inputs are nodes, so the set is a pure function of the solid and the plane: move either and every
     * curve follows by recompute, with nothing rebuilt (OP-21).
     */
    fun intersectionCurves(
        section: SectionRef,
        plane: PlaneRef,
    ): Path3SetRef =
        op(section, plane) {
            EvalResult.Ok(
                Path3SetValue(Intersect3.curvesOf((it[0] as SectionValue).section, (it[1] as PlaneValue).plane)),
            )
        }

    /**
     * Curve [index] of an intersection's ordered set (OP-26, step 6) — the `Select` beside the set, and OP-1's
     * doctrine unchanged one dimension up.
     *
     * The index is **structural** and taken verbatim on replay; the *ordering* is a stated function of the
     * operands' values ([constructit.geom.Intersect3]). So a branch never silently becomes another branch —
     * but an edit that re-sorts the set does move which curve index 1 names, which is what an ordered solution
     * set means and is recorded as such.
     *
     * When the geometry no longer has that many curves — a plane slid off the second leg of a bent bar — this
     * is invalid **with a reason** and heals the moment the curve comes back (OP-3), which is the identical
     * answer [selectAt] gives a quartic that has dropped to two solutions.
     */
    fun selectCurve(
        set: Path3SetRef,
        index: Int,
    ): Path3Ref =
        op(set) {
            val curves = (it[0] as Path3SetValue).set.curves
            when {
                curves.isEmpty() -> EvalResult.Invalid("the plane does not cut that solid, so there is no curve where they meet")
                index < 0 || index >= curves.size ->
                    EvalResult.Invalid(
                        "the plane now cuts that solid in ${curves.size} curve(s), so curve ${index + 1} is gone — " +
                            "move the plane back until it exists, or take the intersection again",
                    )
                else -> EvalResult.Ok(Path3Value(curves[index].path))
            }
        }

    // ---- the sphere as a locus: distance carried in space (OP-28) ----

    /**
     * A **sphere locus** about [centre] at [radius] (OP-28) — the carrier of *"this far from there"*.
     *
     * The node is the whole of the concept: two inputs, both of them ordinary nodes of the drawing, so the
     * locus is a pure function of a point in space and a length. Share the radius node with a second sphere
     * and the two are *equal by construction* (OP-5's same-node-is-equality); drag the corner the centre sits
     * on and every point built on the locus follows by recompute, with nothing rebuilt and nothing re-solved.
     *
     * A **non-positive radius** is refused here, by name and healing (OP-3), because it is a condition on a
     * value: zero is the centre itself and negative is a second way to say what the positive number already
     * says, and two ways to say one thing is what stops a stored model being a normal form (the identical
     * argument [constructit.geom.Curve3Element.Helix3] makes about its own three numbers).
     */
    fun sphere(
        centre: Point3Ref,
        radius: ScalarRef,
    ): Sphere3Ref =
        op(centre, radius) {
            val r = sc(it[1]).requireDim(Dimension.LENGTH, "radius").mm
            if (r <= 0.0) {
                EvalResult.Invalid("a sphere locus needs a radius greater than zero, and this one is ${Frames3.mm(r)} mm")
            } else {
                EvalResult.Ok(Sphere3Value(Sphere3((it[0] as Point3Value).p, r)))
            }
        }

    /**
     * A **sphere locus through [surface]**, centred on [centre] (OP-28) — the second spelling, and the one
     * that states the distance *by construction* rather than by typing it.
     *
     * The pair is deliberate and is exactly the pair the circle has had since the beginning — *Circle (centre,
     * radius)* beside *(centre, point)* — read one dimension up, and the ball repeated it in session 68. The
     * difference is one node, and it is the difference between a distance the drawing **states** and one it
     * **measures**: this radius is `|surface − centre|`, so moving either point moves the locus, and *"as far
     * as that corner"* is a shared input rather than an asserted relation.
     */
    fun sphereThrough(
        centre: Point3Ref,
        surface: Point3Ref,
    ): Sphere3Ref =
        op(centre, surface) {
            val c = (it[0] as Point3Value).p
            val r = ((it[1] as Point3Value).p - c).length()
            if (r <= 0.0) {
                EvalResult.Invalid("a sphere locus needs two different points: its centre and the point on it are the same place")
            } else {
                EvalResult.Ok(Sphere3Value(Sphere3(c, r)))
            }
        }

    /**
     * The **circle where two sphere loci meet** (OP-28's first composition) — an exact circle in space, and
     * the one line of the table that has no branch to choose: two spheres meet in *one* circle or in none.
     *
     * A [Path3Ref] rather than a value of its own, because that is what the drawing already means by a circle
     * in space: one closed path of one exact `Arc3` of a full turn — the same object a drawn circle becomes
     * when it is lifted (OP-26). So it sweeps, it stations, it carries a point that rides it, and it is picked
     * and drawn by the code every other curve in space is.
     *
     * Every way of *not* meeting refuses **by name** and heals (OP-3): too far apart, one inside the other,
     * concentric — and **tangency**, which is the interesting one. Two spheres that touch meet at a point, and
     * a point is not a circle; handing back a circle of radius zero would be handing back a different kind of
     * thing under the same name, which is the very thing [sectionSegment] refuses to do. So a touch is
     * invalid, it says so, and it heals the moment the radii overlap again.
     */
    fun sphereCircle(
        a: Sphere3Ref,
        b: Sphere3Ref,
    ): Path3Ref =
        op(a, b) {
            val s1 = (it[0] as Sphere3Value).sphere
            val s2 = (it[1] as Sphere3Value).sphere
            when (val m = Spheres3.meet(s1, s2)) {
                is SphereMeet.Circle -> EvalResult.Ok(Path3Value(Path3(listOf(m.circle), closed = true)))
                is SphereMeet.Touch ->
                    EvalResult.Invalid(
                        "those two sphere loci touch at a single point rather than meeting in a circle — " +
                            "change a radius until they overlap",
                    )
                SphereMeet.Apart ->
                    EvalResult.Invalid(
                        "those two sphere loci are ${Frames3.mm((s2.center - s1.center).length())} mm apart and " +
                            "reach ${Frames3.mm(s1.radius + s2.radius)} mm between them, so they do not meet",
                    )
                SphereMeet.Nested ->
                    EvalResult.Invalid("one of those sphere loci runs entirely inside the other, so they do not meet")
                SphereMeet.Concentric ->
                    EvalResult.Invalid("those two sphere loci share a centre, so there is no circle where they meet")
            }
        }

    /**
     * The **trilateration pair** of three sphere loci (OP-28's second composition) — the ordered solution set
     * behind *"40 from that corner, 55 from that one and 30 from the third"*.
     *
     * Ordered by **which side of the plane through the three centres** each solution stands on, positive being
     * the side the right-hand normal `(C₂ − C₁) × (C₃ − C₁)` points to. That rule, and the three properties it
     * was chosen for, are stated once where the arithmetic is ([constructit.geom.Spheres3.trilaterate]); what
     * matters here is that it is a function of the operands alone, so the branch [selectPoint3] stores means
     * the same thing on every recompute and on every reload.
     *
     * A **tangency** collapses the pair onto the plane and comes back as a one-element set, so both signs
     * answer the same point — OP-1's own rule for two coincident circle crossings, unchanged. **Collinear
     * centres** have no plane to take a side of, so there is no pair: an empty set, refused by name where it
     * is selected.
     */
    fun trilaterate(
        a: Sphere3Ref,
        b: Sphere3Ref,
        c: Sphere3Ref,
    ): Point3SetRef =
        op(a, b, c) {
            val s1 = (it[0] as Sphere3Value).sphere
            val s2 = (it[1] as Sphere3Value).sphere
            val s3 = (it[2] as Sphere3Value).sphere
            val points =
                when (val t = Spheres3.trilaterate(s1, s2, s3)) {
                    is Trilateration.Pair -> listOf(t.plus, t.minus)
                    is Trilateration.Touch -> listOf(t.at)
                    Trilateration.None, Trilateration.Collinear -> emptyList()
                }
            EvalResult.Ok(Point3SetValue(Point3Set(points)))
        }

    /**
     * Every place the run [path] **enters or leaves** the sphere locus [sphere] (OP-28's third composition) —
     * the points at a stated distance along a run, ordered along the run.
     *
     * The ordering is **arc length from the run's start**, which is the order the sweep's own crossings have
     * used since OP-26's step 2 and is the only one that is a property of the drawing rather than of the
     * arithmetic: it survives re-tessellation, it survives the sphere moving, and it is the order the user
     * sees when they follow the run with their eye. A **touch is not a crossing** — a run that comes tangent
     * to the locus and turns back changes no side and pierces nothing — and a closed run's **seam** is
     * compared across, both because this walk *is* that walk over a different field
     * ([constructit.geom.Pierce3.crossingsOf]).
     *
     * [path] may be a lifted drawing exactly as any other `PATH3` may (`Document.spaceCurveRef`), which is
     * what makes *"where does the footprint's own outline pass 40 mm from that corner"* one gesture.
     */
    fun sphereMeetsRun(
        sphere: Sphere3Ref,
        path: Path3Ref,
    ): Point3SetRef =
        op(sphere, path) {
            val s = (it[0] as Sphere3Value).sphere
            val p = (it[1] as Path3Value).path
            EvalResult.Ok(Point3SetValue(Point3Set(Spheres3.crossings(s, p).map { hit -> hit.at })))
        }

    /**
     * Pick a branch from an **ordered set of points in space** by sign (OP-28) — [select]'s own shape one
     * dimension up, down to the meaning of the sign: `>= 0` is the first member, `< 0` the last.
     *
     * Two branches, so a **sign** and not an index, and that is the same distinction OP-1 draws in the plane:
     * a trilateration factors into exactly one binary geometric choice (which side of the centres' plane), so
     * a sign says it with its meaning attached, while an index would say it by counting.
     *
     * [emptyReason] is what an empty set is called in the caller's own terms, for [select]'s reason: "there is
     * no such point" is always the same fact and only ever needs a better sentence.
     */
    fun selectPoint3(
        set: Point3SetRef,
        sign: Int,
        emptyReason: String = "no point in space is at all of those distances",
    ): Point3Ref =
        op(set) {
            val pts = (it[0] as Point3SetValue).set.points
            when {
                pts.isEmpty() -> EvalResult.Invalid(emptyReason)
                sign >= 0 -> EvalResult.Ok(Point3Value(pts.first()))
                else -> EvalResult.Ok(Point3Value(pts.last()))
            }
        }

    /**
     * Pick branch [index] of an ordered set of points in space (OP-28) — [selectAt]'s own shape one dimension
     * up, for the producer whose count is not two.
     *
     * A run threaded through a sphere locus can cross it any number of times, so the branch is an **index**
     * into an arc-length-ordered set and not a sign, for exactly the reason a quartic's branch is: a set whose
     * size is a value factors into nothing, and "branch 3 of a two-element set" has a clean answer — invalid,
     * with a reason, healing the moment the third crossing comes back (OP-3) — where a composed pair of signs
     * would have none.
     */
    fun selectPoint3At(
        set: Point3SetRef,
        index: Int,
        emptyReason: String = "the run does not reach that sphere locus, so it crosses it nowhere",
    ): Point3Ref =
        op(set) {
            val pts = (it[0] as Point3SetValue).set.points
            when {
                pts.isEmpty() -> EvalResult.Invalid(emptyReason)
                index < 0 || index >= pts.size ->
                    EvalResult.Invalid(
                        "the run crosses that sphere locus ${pts.size} time(s) now, so crossing ${index + 1} is gone — " +
                            "move it back until it exists, or take the intersection again",
                    )
                else -> EvalResult.Ok(Point3Value(pts[index]))
            }
        }

    /**
     * The points [set] currently holds — what a click scores its branch against, exactly as [solutionCount]
     * is what a four-branch intersection asks before it selects (OP-1's creation UX, one dimension up).
     */
    fun solutionPoints3(
        set: Point3SetRef,
        ev: Evaluator,
    ): List<Vec3> = ((ev.eval(set.node) as? EvalResult.Ok)?.value as? Point3SetValue)?.set?.points ?: emptyList()

    /**
     * The **drawn pieces** of [view] as this construction reads them — the same coercion
     * [projectedOntoFace] and [combinedViews] apply inside their own `compute`.
     *
     * Offered so that a gesture scoring *which face a drawing lands on* (OP-26, step 8) measures against
     * exactly what the node will then build from, rather than against a second reading of the same curve.
     */
    fun drawnPieces(
        view: Ref<*>,
        ev: Evaluator,
    ): List<ProfileElement>? {
        val v = (ev.eval(view.node) as? EvalResult.Ok)?.value ?: return null
        return guidePieces(v)
    }

    /**
     * The curves [set] currently holds — what a click scores its branch against, exactly as [solutionCount]
     * is what a four-branch intersection asks before it selects.
     */
    fun solutionCurves(
        set: Path3SetRef,
        ev: Evaluator,
    ): List<IntersectionCurve> =
        ((ev.eval(set.node) as? EvalResult.Ok)?.value as? Path3SetValue)?.set?.curves ?: emptyList()

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

/** A **sphere locus** (OP-28) — see [Construction.sphere]. */
fun Evaluator.sphere3(ref: Sphere3Ref): Sphere3 = (valueOf(ref) as Sphere3Value).sphere

/** An **ordered set of points in space** (OP-28) — see [Construction.trilaterate]. */
fun Evaluator.point3Set(ref: Point3SetRef): Point3Set = (valueOf(ref) as Point3SetValue).set
