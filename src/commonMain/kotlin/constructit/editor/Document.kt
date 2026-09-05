package constructit.editor

import constructit.core.ArcValue
import constructit.core.BezierValue
import constructit.core.CASCADE_PREFIX
import constructit.core.ChainValue
import constructit.core.CircleValue
import constructit.core.EllipseValue
import constructit.core.EllipticArcValue
import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.FrameValue
import constructit.core.FuncCurveValue
import constructit.core.IndirectNode
import constructit.core.LineValue
import constructit.core.LoopValue
import constructit.core.Node
import constructit.core.ParameterNode
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
import constructit.core.SolidValue
import constructit.core.SourceNode
import constructit.core.Value
import constructit.dsl.ArcRef
import constructit.dsl.BezierRef
import constructit.dsl.ChainRef
import constructit.dsl.CircleRef
import constructit.dsl.Construction
import constructit.dsl.EllipseRef
import constructit.dsl.EllipticArcRef
import constructit.dsl.ExprLaw
import constructit.dsl.FamilyLaw
import constructit.dsl.FrameRef
import constructit.dsl.FuncCurveRef
import constructit.dsl.LineRef
import constructit.dsl.LoftPart
import constructit.dsl.LoopRef
import constructit.dsl.Path3Ref
import constructit.dsl.PlaneRef
import constructit.dsl.Point3Ref
import constructit.dsl.PointRef
import constructit.dsl.PointSetRef
import constructit.dsl.ProfileRef
import constructit.dsl.RayRef
import constructit.dsl.Ref
import constructit.dsl.RegionRef
import constructit.dsl.RoundedRectArgs
import constructit.dsl.ScalarRef
import constructit.dsl.SectionFamily
import constructit.dsl.SectionRef
import constructit.dsl.SegmentRef
import constructit.dsl.SkinPart
import constructit.dsl.SolidRef
import constructit.dsl.Sphere3Ref
import constructit.dsl.instance
import constructit.dsl.resultOf
import constructit.dsl.roundedRect
import constructit.dsl.valueOf
import constructit.exchange.MeshText
import constructit.exchange.PathText
import constructit.expr.EXPR_CONSTANTS
import constructit.expr.EXPR_FUNCTIONS
import constructit.expr.Expr
import constructit.expr.ExprError
import constructit.expr.ExprNode
import constructit.expr.ExprParser
import constructit.expr.refNames
import constructit.expr.refs
import constructit.geom.Arc
import constructit.geom.Axis3
import constructit.geom.Blend3
import constructit.geom.BlendChoice
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.BoolOp
import constructit.geom.CarrierCurve
import constructit.geom.CarryMode
import constructit.geom.Chains
import constructit.geom.ChamferVariant
import constructit.geom.Conics
import constructit.geom.Continuity
import constructit.geom.CornerCut
import constructit.geom.Curve3Element
import constructit.geom.CurveEnd
import constructit.geom.Curves3
import constructit.geom.EdgeGeom
import constructit.geom.FaceName
import constructit.geom.Feature3
import constructit.geom.FilletLeg
import constructit.geom.FilletMath
import constructit.geom.FilletVariant
import constructit.geom.FuncCurves
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Handedness
import constructit.geom.IntersectionCurve
import constructit.geom.Justification
import constructit.geom.Mesh3
import constructit.geom.Path3
import constructit.geom.Pierce
import constructit.geom.Pierce3
import constructit.geom.Plane3
import constructit.geom.PlaneSection
import constructit.geom.ProfileElement
import constructit.geom.Project3
import constructit.geom.RegionBool
import constructit.geom.Revolve3
import constructit.geom.Section3
import constructit.geom.Segment
import constructit.geom.Shell3
import constructit.geom.SkinMatch
import constructit.geom.SkinRow
import constructit.geom.Solid3
import constructit.geom.SolidEdge
import constructit.geom.SolidFace
import constructit.geom.Stations3
import constructit.geom.ThickBody
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.geom.Xform3
import constructit.geom.thickBodyOf
import constructit.geom.thickNetwork
import constructit.units.Dimension
import constructit.units.Quantity
import constructit.units.deg
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class ElementKind {
    POINT,
    DERIVED_POINT,
    ON_CURVE,

    /**
     * A **height point** (OP-25): a base point on a sketch plane plus a height along that plane's normal —
     * the apex of a pyramid, generalized. A point kind like the three above, and the one whose position is
     * not *in* the working plane, which is why it is drawn, picked and dragged in the 3D view (see
     * [HeightPointHandle] and [HitTest.distanceTo]).
     */
    HEIGHT_POINT,
    LINE,
    RAY,
    CIRCLE,
    SEGMENT,
    ARC,

    /** A cubic Bézier (OP-15) — a curve like any other, pickable and trimmable-adjacent. */
    BEZIER,

    /**
     * A **curve in space** (OP-26): a `Path3` — a chain of pieces whose value is world-space geometry, built
     * through points that are themselves parented (height points today).
     *
     * Its own kind rather than a member of [Element.isCurve], deliberately, and the reason is the type
     * system's: the 2D curve kinds all carry a value stated in *some plane's* coordinates, so a slot that
     * takes a curve (an intersection, an outline, a fillet leg) would be handed something it cannot read.
     * What it *is* like is a solid — geometry whose home is the 3D view, drawn in the 2D canvas by a
     * projection so that it is visible and pickable there (see [SceneRenderer] and [HitTest]).
     */
    SPACE_CURVE,

    /**
     * A **sphere locus** (OP-28): every point at a stated distance from a point in space — the carrier of
     * *"40 from that corner"*, and the thing a construction intersects.
     *
     * Its own kind, and one that is deliberately **none of the other columns**. It is not a curve (2D or in
     * space) — nothing runs along it; it is not an area and it is not a **solid**, which is the distinction
     * this kind exists to enforce: a ball is a body this tool has built since session 68, with a mesh, a
     * volume, an export and a place in a boolean, while a locus has no interior at all. So a sphere locus
     * can fill no `SOLID`, `AREA`, `CURVE` or `PATH3` slot; the only slots that take one are the three
     * intersections it was made for, and the type of its value ([constructit.core.Sphere3Value]) is what
     * says so at every one of them.
     *
     * It is **scaffolding** in the ordinary sense the document already means by that word — the ancestor
     * closure of the results ([scaffoldingElements]) — and it needs no flag to be: nothing exports it,
     * because export filters on `SolidValue`; nothing cuts with it, because a boolean's slot wants material.
     * What it does need is to be **seen and picked in both views**, since a locus nobody can click is a
     * locus nothing can be built from — see [SceneRenderer] and [HitTest] for how each view draws it where
     * it honestly is.
     */
    SPHERE_LOCUS,

    /**
     * A **cutting chain** (OP-22's extension): a curve that separates its sketch plane into two sides — a
     * finite run of pieces with a ray at each end (`constructit.geom.Chain`).
     *
     * Its own kind rather than a member of [Element.isCurve], for the reason the slot kinds exist at all: a
     * chain is not something to intersect, trim or bound an area with — it is something to **cut with**, and
     * a curve slot handed one would be handed a value it cannot read. What it is like is a ray: unbounded,
     * drawn clipped to the view, and picked by the distance to what is drawn.
     */
    CHAIN,

    /**
     * A whole **ellipse** (OP-24) — a first-class conic, closed like a circle, so it bounds an area by
     * itself and can be picked wherever a curve is wanted.
     */
    ELLIPSE,

    /** A piece of an ellipse, trimmed by parametric angle (OP-24). */
    ELLIPTIC_ARC,

    /**
     * A **function curve** (the session-71 expressions entry, curve half): the piece traced by `x(t)`,
     * `y(t)` over a stated domain.
     *
     * One kind for every curve family a formula can write — an involute, a cycloid, a spiral — which is the
     * design decision the whole item turns on: *no new primitive curve type per curve family*. It is an
     * ordinary open curve in every other respect: pickable, riddable, a leg of a loop, traced, extruded.
     */
    FUNC_CURVE,

    /** A closed boundary: the result layer's own element (OP-14). */
    OUTLINE,

    /** An area — an outline with holes (OP-14), what the 2D→3D seam consumes. */
    AREA,

    /**
     * A **solid**: an extrusion or revolution of a sketch (OP-17). Its home is the 3D view; the 2D
     * canvas draws only the footprint of the sketch it came from — see [SceneRenderer].
     */
    SOLID,

    /**
     * A **dimension**: annotation, showing a measurement node's live value (OP-4). Neither scaffolding
     * nor result geometry — OP-14's third, organizational column — see [Element.isAnnotation].
     */
    DIMENSION,
}

/**
 * Which way a sketch plane's **front** turns, in the coordinates of the space it was hinged out of —
 * [Document.spaceFacing].
 *
 * [bearingDeg] is where the front leans in that base, measured from its +x the way every other angle in the
 * drawing is measured, in `[0, 360)`. It is null exactly when the plane lies **flat** on its base and its
 * front has no direction in it; then [outward] is the whole answer — the base's own front, or its reverse.
 */
class Facing(
    val bearingDeg: Double?,
    val outward: Boolean,
)

/** Below this much of a sideways component a plane lies flat on its base and its front has no bearing. */
private const val FACING_EPS = 1e-9

/**
 * A named **2D sketch space** (OP-17): a plane to embed on, and the coordinates the canvas draws in while
 * it is active.
 *
 * The point of OP-17 is that 2D geometry is *not* plane-resident, so this adds nothing to the engine: a
 * space is organizational + view state (OP-14's third column), and the only thing it contributes to a
 * construction is [plane] — the very argument `sketchOn` already takes. The default space is the **plan**
 * (world XY, [plane] null so every feature keeps building its own `planeXY` node exactly as before); a
 * *face* space names the solid and the boundary-piece index its plane is derived from (OP-8), and its plane
 * is the side face's own, whose normal points **out of the material** — you look at the face — so *Extrude*
 * follows the plane's normal and builds a boss while *Cut* goes the other way and drills
 * (`Document.cutOnFace`). One rule for every space, because a datum has the same one.
 *
 * A **datum** space is the general form of the same thing (GitHub #6): its plane contains the carrier of a
 * drawn line ([hinge]) and is rotated out of the space it was defined in by [angle], a live parameter. A
 * datum has no material side, so which way its features build is fixed by its own normal — see
 * `Document.createDatumSpace` for the conventions and `Geom3.datumPlane` for the frame.
 *
 * **Where the origin sits** is a property of the space rather than of the frame it is derived from
 * ([intrinsic]): every non-plan space publishes its plane as `planeAnchored(intrinsic, anchor, dx, dy)`, so
 * the origin can be moved without any consumer being rewired — see `Document.setSpaceOrigin`.
 */
class SketchSpace(
    val name: String,
    /** The sketch plane, or null for the plan space — which is the world XY plane by construction. */
    val plane: PlaneRef?,
    /**
     * The solid this space is a **face of** — or, for a datum, the part its features cut into (null when
     * its hinge belongs to no solid, and null for the plan). What `Document.facePartTip` chains from.
     */
    val anchor: Element? = null,
    /** Which of [anchor]'s boundary pieces this face is (OP-8); −1 for the plan and for a datum. */
    val piece: Int = -1,
    /**
     * DATUM only: the **line element** whose carrier this plane contains — the hinge it turns about, and
     * the datum's own u axis. Null for the plan and for a face space.
     */
    val hinge: Element? = null,
    /** DATUM only: the live angle between this plane and [from]'s — retyping it tilts the plane. */
    val angle: ScalarEntry? = null,
    /**
     * DATUM only: the live distance this plane is moved along its **own normal** after being tilted, or null
     * for one that goes through its hinge (which is every datum written before offsets existed).
     *
     * The parallel case, which the hinge-and-angle form cannot state at all: a datum at 0° *is* the space it
     * came from, so a plane parallel to the plan and 60 mm above it needs one more number. It is a parameter
     * like the angle — retype it and the plane slides, taking every feature sketched on it along — and it is
     * what makes a stack of loft sections reachable by clicking (see `Document.createDatumSpace`).
     */
    val offset: ScalarEntry? = null,
    /** DATUM only: the name of the space this plane was rotated out of (spaces compose). */
    val from: String = Document.PLAN_SPACE,
    /**
     * The frame **before** the origin control: the face's own intrinsic plane (the picked segment on the x
     * axis, its midpoint the origin) or the datum's hinged/offset one. Null for the plan.
     *
     * Held because it is what an origin *anchor* must be measured in: the anchor moves [plane]'s origin, so a
     * point stated in [plane]'s coordinates could not define it without depending on itself.
     */
    val intrinsic: PlaneRef? = null,
    /**
     * The **origin anchor** node: a free point at (0, 0) in [intrinsic]'s coordinates, welded onto a point of
     * the plane once one is chosen (`Document.setSpaceOrigin`). Re-pointing it in place is what lets the
     * origin move with no consumer of [plane] being rewired.
     */
    val originAnchor: PointRef? = null,
    /** The in-plane offset applied after the anchor — ordinary scalar sources, wireable to parameters. */
    val originDx: ScalarRef? = null,
    val originDy: ScalarRef? = null,
    /**
     * PARALLEL only: this plane is [from]'s plane moved along its own normal by [offset], with **no hinge**
     * at all — *Plane at height* (GitHub #9, the user's design: *"the section tool basically creates a plane
     * parallel to the base plane with a given distance; to create such plane, no solid selection is
     * necessary"*).
     *
     * A datum in every other respect — a plane that is not a face of anything — which is why [isDatum] takes
     * it in. What it has instead of a hinge is [Document.spaceAncestors]: its input geometry is the section
     * of every solid that existed before it, and that needs no pick at all.
     */
    val parallel: Boolean = false,
    /**
     * STATION only (OP-26, step 4): the **curve in space** this plane stands across — the run it is a station
     * of. Null for every other space.
     *
     * A station is *one stated position along a path together with the plane the path pierces there*, so it
     * has exactly the shape a datum has one construction over: a piece of geometry and a live number. What it
     * has instead of a hinge and an angle is this curve and [along], and everything else about it — the
     * origin control, the sections it cuts, which way *Extrude* and *Cut* build — is the datum's, unchanged.
     */
    val station: Element? = null,
    /**
     * STATION only: the live **distance from the start of [station]'s run**, in the plane's own parameter.
     *
     * One length and nothing else, which is the whole of OP-26's settled design for this: a relative station
     * is `base + d` in the expression language (OP-7), two stations sharing a pitch is one parameter node
     * feeding both, and normalized *t* is deliberately not offered. Retype it and the plane slides along the
     * curve, taking everything drawn on it; type a number past the end of the run and the plane is *invalid*
     * with a reason, which heals (OP-3).
     */
    val along: ScalarEntry? = null,
    /**
     * WIREFRAME only (OP-26, step 9): the **imported run** whose own plane this space is.
     *
     * The third space derived from a curve rather than from a solid, and it takes the shape the station
     * already established: a piece of geometry and nothing else — a wireframe states its plane completely, so
     * unlike a datum's angle or a station's distance there is no number left over for the user to type.
     *
     * It is only ever an *imported* run, and that is the whole of why measuring its plane is allowed: the
     * literal is frozen and its placement is rigid, so the plane is a pure function of numbers that cannot
     * change their answer (see [Document.sketchFromWireframe]).
     */
    val wire: Element? = null,
) {
    /**
     * Which corner of this space's own section the origin is anchored on, or null while it sits at the
     * frame's intrinsic origin — the *stored* form of the choice (OP-1/OP-18: an index, never a position).
     */
    var originCorner: Int? = null

    /**
     * Which **solid** [originCorner] indexes into, or null for this space's own [anchor] — since a plane's
     * section is one per ancestor solid (GitHub #9), a corner index only means something together with the
     * solid it is a corner of.
     */
    var originSolid: Element? = null

    /** The panel parameters the origin offsets read, when they are not the default zero. */
    var originDxEntry: ScalarEntry? = null
    var originDyEntry: ScalarEntry? = null

    /**
     * The document's element counter when this space was created — what makes *"created before"* a fact
     * rather than a search (GitHub #9). See [Document.spaceAncestors]: a solid is this plane's input geometry
     * exactly when it was born at or before this mark, which is what keeps the plane→section→solid edges
     * pointing backwards in creation order and the graph acyclic by construction.
     */
    var bornAt: Int = 0

    /** The plan is exactly the space with no plane node of its own: the world XY plane, by construction. */
    val isPlan: Boolean get() = plane == null

    /** A **station** across a curve in space (OP-26, step 4) — see [station]. */
    val isStation: Boolean get() = station != null

    /** The **plane of an imported wireframe** (OP-26, step 9) — see [wire]. */
    val isWire: Boolean get() = wire != null

    /**
     * A datum plane — a line and an angle, a bare height, or a station along a curve — as opposed to a
     * solid's face or the plan.
     *
     * A station belongs here rather than beside it: what this predicate is asked for is *"a plane that is not
     * a face of anything"*, and the two things that turn on it are which way a feature builds (a plane with no
     * material side states it on its own normal) and how the view introduces itself. Both hold for a station
     * word for word, which is the point of a station being a sketch space at all.
     */
    val isDatum: Boolean get() = hinge != null || parallel || isStation || isWire

    /** A space on a solid's planar side face (OP-8's `sideFacePlane`). */
    val isFace: Boolean get() = plane != null && hinge == null && !parallel && !isStation && !isWire
}

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
    /**
     * For [ElementKind.DIMENSION]: what this element *is*. Held here rather than looked up in the
     * document so drawing, picking and saving all reach it one hop from the element, as [handle] is.
     */
    var annotation: DimensionAnnotation? = null,
) {
    /**
     * The **sketch space** this element was drawn in, by name (OP-17) — the plan unless it was drawn on a
     * face. Stamped by [Document.add], which is the single place elements are created, so nothing has to
     * remember to set it; held as the name because that is what the file records and a space is never
     * renamed.
     *
     * It is a *view* fact, not a geometric one: a space's plane does the embedding at feature time, and the
     * 2D engine stays abstract-planar (OP-17's whole decision). What it buys is that a canvas shows one
     * space at a time, so geometry drawn on a face cannot be picked in the plan and vice versa.
     */
    var space: String = Document.PLAN_SPACE

    /**
     * **When** this element was born: the document's element counter at the moment [Document.add] created it,
     * which only ever grows (GitHub #9). What a sketch space compares its own [SketchSpace.bornAt] against to
     * answer *"was this solid created before me?"* — a question the element list's *order* cannot answer,
     * since ortho-leg surgery removes and appends elements mid-document.
     */
    var born: Int = 0

    /**
     * Whether this element's value is a point **in space** (`Point3Value`) rather than one of the working
     * plane — a height point (OP-25), a key point of a curve in space, or a point riding a coil (OP-26).
     *
     * A fact about the *frame the value is stated in*, deliberately kept apart from [kind], which says what
     * **role** the point plays (free, derived, riding a curve, lifted off a plane). The two are independent:
     * a rider is a rider whether it slides along a segment in the plan or round a coil in space, and every
     * route that asks "is this a point?" wants the role while every route that asks "can I read plane
     * coordinates off it?" wants this. Stamped by the three builders that make one, so no consumer has to
     * evaluate a node to find out — and a replay stamps it again for exactly the same reason it did first.
     */
    var inSpace: Boolean = false

    /**
     * Whether grabbing this element can actually move anything. An on-curve point qualifies only
     * while its handle still has a writable field: once every coordinate is driven — welded onto a
     * point, or shared by a loop closure — dragging it is inert, and a dead handle must not steal the
     * grab from the geometry that *can* move (which sits at the same place, being what drives it).
     */
    val draggable: Boolean get() =
        when (kind) {
            ElementKind.POINT, ElementKind.ON_CURVE, ElementKind.HEIGHT_POINT, ElementKind.DIMENSION -> hasFreeDof
            else -> false
        }

    /**
     * True while dragging this element can still change something. Note a leg can be immovable and
     * yet have editable *lengths*: its drag writes the one coordinate shared by its ends, which the
     * length fields do not touch — see [Handle.dragNodes] and [explainImmovable].
     */
    val hasFreeDof: Boolean get() = handle?.dragMovable ?: false

    /**
     * Anything a pointer can address. Every displayed element is selectable: selection is what makes
     * an element's values readable in the inspector and — since delete operates on the selection —
     * what makes it removable, so a curve with no handle must still take the pick.
     */
    val selectable: Boolean get() = true
    val isCurve: Boolean get() =
        kind == ElementKind.LINE || kind == ElementKind.CIRCLE || kind == ElementKind.SEGMENT ||
            kind == ElementKind.RAY || kind == ElementKind.ARC || kind == ElementKind.BEZIER ||
            kind == ElementKind.ELLIPSE || kind == ElementKind.ELLIPTIC_ARC || kind == ElementKind.FUNC_CURVE

    /** An output of the construction rather than scaffolding for it (OP-14). */
    val isResult: Boolean get() = kind == ElementKind.OUTLINE || kind == ElementKind.AREA || kind == ElementKind.SOLID

    /** A region-valued or loop-valued result — what the 2D→3D seam can consume (OP-17). */
    val isArea: Boolean get() = kind == ElementKind.OUTLINE || kind == ElementKind.AREA

    /**
     * Annotation: it says something *about* the drawing instead of being part of it (OP-14's third
     * column). Neither a result nor scaffolding — a dimension is not what the drawing is made of, and it
     * is never construction for anything, so the dim toggle leaves it alone: it is visible whenever it is
     * not hidden, full stop.
     */
    val isAnnotation: Boolean get() = kind == ElementKind.DIMENSION
    val isPoint: Boolean get() =
        kind == ElementKind.POINT || kind == ElementKind.DERIVED_POINT || kind == ElementKind.ON_CURVE ||
            kind == ElementKind.HEIGHT_POINT

    /** Line / segment / ray — anything that determines an infinite line. */
    val isLinear: Boolean get() = kind == ElementKind.LINE || kind == ElementKind.SEGMENT || kind == ElementKind.RAY

    /**
     * Circle / arc — anything that determines a **carrier circle** (centre + radius).
     *
     * The exact twin of [isLinear], and it exists for the same reason: a slot that wants a circle should
     * take an arc, because the construction a circle op describes is about the carrier and an arc *has* one
     * (`Document.carrierCircle`, mirroring `carrierLine`). The consequence is stated where it is picked: a
     * point derived that way may land off the arc's swept range, exactly as an intersection on a segment's
     * carrier line may land beyond its ends.
     */
    val isCentric: Boolean get() = kind == ElementKind.CIRCLE || kind == ElementKind.ARC

    /**
     * Ellipse / elliptic arc — anything that determines a **carrier ellipse** (OP-24).
     *
     * The third member of the [isLinear]/[isCentric] family, and it exists for the same reason: every
     * conic op is about the carrier, and an elliptic arc has one (`Document.carrierEllipse`). A point
     * derived that way may land off the arc's swept range, exactly as one on a segment's carrier line may
     * land beyond its ends.
     */
    val isElliptic: Boolean get() = kind == ElementKind.ELLIPSE || kind == ElementKind.ELLIPTIC_ARC

    /** Anything with a **centre**: a circle, an arc, an ellipse or an elliptic arc. */
    val hasCentre: Boolean get() = isCentric || isElliptic
}

/**
 * A **constructed joint** (OP-14): two boundary pieces that hand over at one point, plus the node that *is*
 * that point — a fillet's tangency, a chamfer's bevel end.
 *
 * Held as a node and not as a coordinate, so the joint keeps following the parameters (the whole reason the
 * Outline tool can trace a fillet at all: a tangency has no intersection to find). Registered by the
 * construction that made the joint, read by both the tracer and its boundary-follow — see
 * `Document.registerJoint`.
 */
class Joint(
    val a: Element,
    val b: Element,
    val at: PointRef,
    /**
     * Whether the two pieces meet **tangentially** — a fillet's rounding runs on smoothly into its leg, a
     * chamfer's bevel turns a corner (GitHub #29).
     *
     * Recorded rather than measured, and that is the whole point of it: whether two curves meet tangentially
     * is a property of *values*, so a construction that read it off the geometry would be discovery (OP-14) —
     * and one dimension up, a blend whose number of swept edges moved with the numbers would be structure
     * decided at eval time (OP-21). The construction that made the joint knows which it built, so it says so
     * here, and *Fillet edge* reads it to run one band along the whole tangent-continuous run.
     */
    val tangent: Boolean = false,
)

/**
 * A **stated incidence** (OP-14, GitHub #19): the construction says this point lies on that circle — because
 * the point is the circle's own radius-defining point, one of the three a circle was fitted through, an end
 * of an arc, a crossing that has the circle among its operands, or a tangency taken from a point.
 *
 * The circle is held as its **carrier** [CircleRef] and not as a coordinate, for [Joint]'s own reason: what
 * a tangent at the point needs is the node, so the line keeps following every later edit of the circle. The
 * [host] is the element the user sees and the refusals name (`e4`), which is why both are kept — a carrier
 * derived from an arc has no element of its own.
 *
 * Read by *Tangent at point*, which is the honest form of "click a point on a circle": lying on the circle is
 * a fact about the construction, not a measurement of two numbers agreeing (`Document.circlesThrough`).
 */
class OnCircle(val point: Node, val carrier: CircleRef, val host: Element?)

/** One way a boundary can carry on from a piece: the next [piece], and the position [at] which it takes over. */
class Continuation(val piece: Element, val at: Vec2)

/**
 * A corner a fillet or chamfer **replaced**: legs [a] and [b] no longer hand over to each other there,
 * because [by] — the arc or the bevel — took their meeting.
 *
 * Recorded by the construction that cut the corner off rather than re-derived from the picture, which is
 * OP-14's rule against discovering topology applied to the one fact the picture cannot show: that two curves
 * still crossing at a point are no longer *joined* there.
 */
class Supersession(val a: Element, val b: Element, val by: Element)

/**
 * The name a family law drives the **run's own turn** by (OP-26, session 79 — the design pass's F12).
 *
 * Reserved on the left of a law rather than resolved against the drawing, because a stored literal may not
 * change meaning when a parameter is added (OP-18): were a drawing scalar of this name allowed to win,
 * creating one would silently re-read every file that states a twist law. A section that genuinely reads a
 * parameter called `twist` is refused by name, with the cure (rename the row).
 */
internal const val FAMILY_TWIST_NAME = "twist"

/**
 * The other name a sweep's run carries, reserved for [FAMILY_TWIST_NAME]'s reason and **not** law-able: roll
 * is where the section starts, which is one angle for the whole body rather than a function of the station.
 */
internal const val FAMILY_ROLL_NAME = "roll"

/** A named scalar: an editable parameter (OP-7) or a read-only measurement (OP-4). */
class ScalarEntry(val id: String, var name: String, val ref: ScalarRef, val editable: Boolean)

/**
 * A **flat named group** of elements (OP-16, build order step 1): organizational only — no frame, no
 * transform, no closure analysis, and no effect whatsoever on geometry, nodes or handles. It buys
 * select-together, naming and bulk visibility; the frame (step 2) attaches to this container later.
 *
 * An element is in **at most one** group at this step. That is the simplest honest rule here and it
 * falls out of the save format: membership lives in the recorded `group` step's argument list, and a
 * recorded step's arguments are never rewritten — so an element cannot be moved between groups
 * without ungrouping first.
 */
class Group(val id: String, var name: String) {
    val members = ArrayList<Element>()

    /** The journal step that recorded this group — what [Document.ungroup] drops again. */
    internal var step: Step? = null

    /**
     * The group's own coordinate frame once it is **placed** (OP-16 step 2), else null. One
     * [SourceNode] holding a [FrameValue]: moving the group is a literal edit on it, nothing more.
     */
    var frame: FrameRef? = null
        internal set

    /** The frame's source node — what a drag and the typed x/y/angle fields write. */
    val frameNode: SourceNode? get() = frame?.node as? SourceNode

    /** The frame as a [Handle] (OP-13), so a group is movable by drag *and* by number. */
    var frameHandle: FrameHandle? = null
        internal set

    /** The free point sources this placement retrofitted — what [Document.unplaceGroup] inverts. */
    val captures = ArrayList<FrameCapture>()

    /**
     * The ortho paths this placement captured whole (OP-16's *ortho-path bonus*). A path is one unit of
     * freedom — its coordinate nodes are shared along each straight run — so it is captured or not at all,
     * never vertex by vertex. From then on its coordinates are the group's **local** ones, which is what
     * turns axis-alignment into alignment to the frame's axes: the rotated project frame.
     */
    val capturedPaths = ArrayList<OrthoPath>()

    /**
     * The riders this placement **re-anchored** to a point of their own carrier (OP-16 × OP-4 case b): a
     * position stated relative to member geometry is rigid under the frame, where a world-anchored one is not.
     * Inverted by unplacing, like every other part of the capture.
     */
    internal val capturedRiders = ArrayList<Document.RiderRecord>()

    /**
     * The **junctions** this placement re-anchored to a point of their own carrier (OP-16 × OP-20). A
     * connection owns a degree of freedom just as a rider does — its position along the wall it meets —
     * and that parameter is stated in the world, so a frame that moves the wall would leave the junction
     * standing and every run hanging on it would be torn off. Inverted by unplacing, like every other
     * part of the capture.
     */
    internal val capturedJunctions = ArrayList<JunctionCapture>()

    /** The journal step that recorded the placement — dropped again by unplace. */
    internal var placeStep: Step? = null

    val placed: Boolean get() = frame != null
}

/**
 * One retrofitted free point of a placed group (OP-16 step 2).
 *
 * [original] is the point's own source node, now **bound** onto a `frameApply` node (so everything that
 * already referenced it follows the frame without a single input list being rewired — OP-5); [local]
 * holds the same position in the group's own coordinates and is the DOF that remains. The pair is what
 * makes the retrofit invertible: unplacing writes [local] plus the frame's origin back into [original],
 * which is exactly what the capture took (see [Document.unplaceGroup]).
 */
class FrameCapture(
    val original: SourceNode,
    val local: SourceNode,
    /** The point element displaying [original], whose handle became a [FramedPointHandle]. */
    val element: Element?,
    private val priorHandle: Handle?,
) {
    fun restoreHandle() {
        element?.handle = priorHandle
    }
}

/** A free point of a placed group's closure that something *outside* the group also uses (OP-16). */
class SharedPoint(val point: String, val consumer: Element)

/** How many shared positions a refusal names before it starts counting — see [summarizeNames]. */
const val POINTS_NAMED = 5

/** How many consumers a refusal names before it starts counting: enough to look at, never a list. */
const val CONSUMERS_NAMED = 3

/**
 * Name at most [keep] of [names] and **count** the rest (OP-16's honest failure, said in one breath).
 *
 * A refusal that lists every element of the drawing is a wall rather than a report: the user's own message
 * named 27 consumers and 8 raw node ids, and what it had to say was *"this is shared with most of the
 * drawing"*. So the list is a sample plus a number — the sample is what to look at, the number is how big
 * the decision is.
 */
fun summarizeNames(
    names: List<String>,
    keep: Int,
    tail: String = "more",
): String {
    if (names.size <= keep) return names.joinToString(", ")
    return names.take(keep).joinToString(", ") + " and ${names.size - keep} $tail"
}

/**
 * One re-anchored **junction** of a placed group (OP-16 × OP-20) — the connection analogue of
 * [FrameCapture], and of the rider re-anchoring beside it.
 *
 * A junction riding a wall states its position in the world (a coordinate the host leaves free, or a
 * distance along the carrier line). That is right while the wall merely carries it — dragging the wall's
 * far corner then does not drag the branch — and wrong the moment the *group* is the thing being moved,
 * because the frame moves the wall and the parameter does not follow. So placing binds the parameter onto
 * `base's position along the carrier + offset`, exactly as *Make relative* does for a rider: one degree of
 * freedom before and after, nothing moves at the moment of the change, and unplacing gives the absolute
 * parameter back where the junction then stands.
 */
class JunctionCapture internal constructor(
    internal val junction: Junction,
    private val priorHandle: Handle?,
    private val priorPlace: (axis: Int, value: Double) -> Boolean,
) {
    /** Put the junction's own freedom back the way the capture found it — [Document.unplaceGroup]'s half. */
    internal fun restore() {
        junction.handle = priorHandle
        junction.place = priorPlace
    }
}

/**
 * Which **kind** of degree of freedom an element owns (OP-16's placement question, and the create dialog's
 * candidate labels). One entry per kind the engine has, because a placement has to carry each of them its
 * own way — see the table in [Document.analysePlacement].
 */
enum class FreedomKind {
    /** A free point: two coordinates of its own. */
    FREE_POINT,

    /** A rider on a curve: one parameter along its host ([Document.RiderForm]). */
    RIDER,

    /** A point re-parameterized as a polar offset from an anchor (OP-4 case b). */
    RELATIVE,

    /** A rider on a circle: an angle about the centre. */
    ON_CIRCLE,

    /** A ratio point: a dimensionless share of the span between two points. */
    SPAN_RATIO,
}

/**
 * One degree of freedom in a selection's closure: which element owns it, of which [kind], and how to
 * *name* it to the user.
 *
 * The closure question OP-16 and OP-6 both ask ("which of the free sources belong to the thing being
 * made?") used to be answered for plain free points only, which is why a figure whose actual freedom was a
 * rider plus a polar offset could be grouped but never placed: the dialog could not offer them and the
 * capture could not carry them.
 */
class Freedom internal constructor(
    val element: Element,
    val kind: FreedomKind,
    /** How the dialog names this row — the element's id plus what it rides, in the user's words. */
    val label: String,
    /** Whether the selection *displays* this element, as opposed to merely depending on it. */
    val owned: Boolean,
    internal val rider: Document.RiderRecord? = null,
)

/** What placing a group would do: where its frame lands, what it captures, and what forbids it. */
class Placement(
    /** The frame's default origin: the centre of the members' bounding box. */
    val origin: Vec2,
    /** The free point sources the frame would carry. */
    val candidates: List<SourceNode>,
    /** The ortho paths the frame would carry whole — see [Document.analysePlacement]. */
    val paths: List<OrthoPath>,
    /**
     * Free points (and captured path vertices) the group owns that a non-member also depends on. A group
     * moves independently only if this is empty — a real modelling ambiguity, reported concretely rather
     * than papered over.
     */
    val conflicts: List<SharedPoint>,
    /** Riders the placement would re-anchor to a point of their own carrier — see [Document.placeGroup]. */
    val ridersToAnchor: List<Freedom> = emptyList(),
    /**
     * **Junctions** the placement would re-anchor the same way (OP-16 × OP-20) — the freedom a *connection*
     * owns, which is a group's own whenever the wall it rides is a member. Listed separately from
     * [ridersToAnchor] only because a junction has no element of its own; it is carried identically.
     */
    val junctionsToAnchor: List<Junction> = emptyList(),
    /**
     * Freedoms the group owns that are **already** rigid under a frame — a polar offset from an anchor
     * inside the group, an angle about a circle inside it. They need no rebinding at all, and counting them
     * is what makes such a group placeable instead of "owning no free point".
     */
    val rigid: List<Freedom> = emptyList(),
    /** Freedoms the placement cannot carry, each with the reason, in the user's words. */
    val uncapturable: List<String> = emptyList(),
) {
    /** Whether the frame would carry any freedom at all — else it would have nothing to move. */
    val carriesSomething: Boolean
        get() =
            candidates.isNotEmpty() || paths.isNotEmpty() || ridersToAnchor.isNotEmpty() ||
                junctionsToAnchor.isNotEmpty() || rigid.isNotEmpty()
}

/** The outcome of a placement: how much the frame carries, and what it does *not*. */
class PlaceResult(
    val captured: Int,
    /** How many ortho paths the frame carries — each one whole (see [Group.capturedPaths]). */
    val capturedPaths: Int,
    /**
     * Members the frame does not move: their position is driven from outside the group (a weld or an
     * attach that leaves it), or held by an ortho path the frame could not capture (one whose freedom
     * leaves the group at a junction). The group deforms there, correctly — but invisibly, so it is
     * reported.
     */
    val unfollowed: List<Element>,
    /** How many riders the placement re-anchored to their own carrier — see [Group.capturedRiders]. */
    val capturedRiders: Int = 0,
    /** How many **junctions** it re-anchored the same way — see [Group.capturedJunctions]. */
    val capturedJunctions: Int = 0,
)

/**
 * A vertex of an ortho path, carrying the two coordinate source nodes so drags/closure can write
 * them. [ownAxis] is the coordinate introduced by the edge that created it (0 = x, 1 = y, -1 = the
 * start, which owns both) — the safe one to bind when closing a loop. [corner] is a `var` because
 * closing a loop replaces the live handle (see [Document.closeOrthoPath]).
 *
 * Two point nodes, one vertex: [local] is `pointXY(x, y)` over the coordinates the vertex owns, and
 * [ref] — what everything else references — is a re-pointable view of it ([IndirectNode]). Unplaced the
 * two are the same value; placed, [ref] is bound onto `frameApply(frame, local)`, so the coordinates
 * become the group's **local** ones and every consumer follows the frame untouched (OP-16, OP-5).
 */
class OrthoVertex(
    val ref: PointRef,
    var corner: OrthoCornerHandle,
    val ownAxis: Int,
    val local: PointRef,
    /**
     * This corner's **own radius** — zero for a sharp corner, and *bound* to a fillet's own parameter once
     * the corner is rounded (GitHub #25).
     *
     * A node from the moment the vertex exists, and that is the whole point of it: everything a path
     * publishes takes it as an input from the start, so rounding a corner is a **binding** and never a
     * rewiring (OP-5, CLAUDE.md's own sentence about how welding removes a degree of freedom). A wall built
     * before the corner was rounded therefore follows the rounding, with no step re-stamped and no element
     * replaced — and two corners sharing one parameter are equal by construction.
     */
    val round: SourceNode,
    /** The same for a chamfer's **setback** — [round]'s twin, and only one of the two is ever bound. */
    val bevel: SourceNode,
) {
    /** Where a placement inserts the frame: the node [ref] names, bound in place (OP-16). */
    val indirect: IndirectNode? get() = ref.node as? IndirectNode
}

/**
 * One coordinate of an ortho vertex re-parameterized as **an offset from another point** — OP-4 case (b)
 * applied to a path's coordinate chains rather than to a point literal (GitHub issue #23).
 *
 * A vertex is not one point holding two coordinates: it is *two scalar sources* behind a re-pointable
 * view, and one of them usually follows a neighbour so that the leg between them stays axis-aligned. So
 * the thing OP-4 case (b) can re-state here is a **coordinate chain**, one axis at a time: [owner] — the
 * source at the end of that chain, the one a drag actually writes ([writableMaster]) — is bound to
 * `anchor.coord(axis) + offset`, and from then on the whole chain follows the anchor. Which is exactly
 * what the report asked for: the closing leg of a loop keeps its length when the far side is dragged,
 * because its two ends no longer state their x independently.
 *
 * The other axis is normally left alone, and that is not a shortcut: a path's own junctions already relate
 * it (a closed loop binds the last vertex's own coordinate to the first's), and re-stating a relation the
 * construction already holds is what OP-4 forbids.
 *
 * [offset] is the degree of freedom that coordinate has from then on — a signed length, captured from the
 * geometry the vertex already has so nothing moves at the moment of the change. Everything that used to
 * write the coordinate writes the offset instead ([place]): the drag, the coordinate field, the leg-length
 * field, and the offset's own field. That is OP-13's rule, not four cases of it.
 */
class OrthoOffset(
    /** The source at the end of the coordinate chain — see [writableMaster]. */
    val owner: SourceNode,
    /** The point the coordinate is measured from. */
    val anchor: PointRef,
    /** Which coordinate this states: 0 = x, 1 = y. */
    val axis: Int,
    /**
     * `measureX`/`measureY` of [anchor] — **the very node the sum reads**, so the value a write is
     * measured against and the value the construction uses can never be two different measurements.
     */
    val anchorCoord: ScalarRef,
    /**
     * The signed offset from [anchorCoord]: this coordinate's only freedom from now on — and a **named
     * parameter** (OP-7) rather than an anonymous source.
     *
     * That is the report's third ask answered by the substrate that already exists: *"one could wish to fix
     * the length of an ortho leg — or bind it to a named parameter."* A panel row is exactly what can be
     * wired to another row, given a formula, renamed by nothing, and read by an expression — so the offset
     * is one, and *Wire* / the formula field reach it with no route of their own. Owned by the step that
     * made it, like an opening's `pos` (OP-21): the file names it nowhere, replay recreates it, and
     * [Document.makeAbsoluteOrtho] takes it back.
     */
    val offset: ParameterNode,
    /** The offset's panel row — what a formula or a wire addresses it by. */
    val entry: ScalarEntry,
) {
    /** [anchor]'s coordinate on [axis] right now, or null while its construction is invalid. */
    fun anchorAt(): Double? = ((Evaluator().eval(anchorCoord.node) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.mm

    /**
     * False once the offset is **driven** — wired to another parameter, or given a formula. Then this
     * coordinate has no freedom of its own any more and every write to it must refuse, exactly as a welded
     * one does: the whole point of a wire is that the value comes from somewhere else (OP-5).
     */
    val writable: Boolean get() = offset.boundTo == null

    /**
     * Put this coordinate at [value] by writing the offset — what a drag and every typed field do, since
     * the coordinate itself is derived from the anchor from here on (OP-13: typing and dragging are one
     * operation, so they must reach the same node).
     *
     * Read through a **fresh** evaluator, exactly as [Junction.place] is: by the time a corner's drag gets
     * here it has already written the other axis, and a pass-memoized anchor value could be one edit stale.
     */
    fun place(value: Double): Boolean {
        if (!writable) return false
        val a = anchorAt() ?: return false
        offset.literal = ScalarValue(Quantity.mm(value - a))
        return true
    }
}

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
     * The **corner piece** a fillet or chamfer put at a vertex of this path, by vertex index (GitHub #25).
     *
     * *"A fillet on two adjacent legs of one ortho path does not add an arc beside the corner: it makes that
     * corner's own radius"* — and this is where the path holds it. Structurally the *which corner* (the two
     * picked legs name one vertex) and as a value the radius, which is the fillet's own scalar parameter, so
     * one parameter on two corners keeps them equal by construction (OP-21's rule, OP-5's sharing).
     *
     * Synthetic like the joint registry (OP-18): the fillet step re-runs on replay and records it again, so
     * nothing about this is in the file — the step that already names the two legs *is* the record.
     */
    val corners = HashMap<Int, Element>()

    /**
     * The re-pointable view this path publishes its **closed loop** through, once something has asked for
     * one — see `Document.orthoLoopOf`.
     *
     * A view for [OrthoVertex.ref]'s own reason (OP-16): rounding a corner changes how many pieces the loop
     * has, and everything already built on the loop — an extrude, a revolve, an area measurement — must read
     * the rounded one. Binding the view in place is what makes that true with nothing rewired (OP-5).
     */
    var loop: LoopRef? = null
        internal set

    /** Which pieces [loop] is bound onto right now — what tells a rebind from a no-op. */
    var loopPieces: List<Element> = emptyList()
        internal set

    /**
     * Axis per leg, kept beside [legs]. Held explicitly rather than derived from a vertex's introduced
     * coordinate: that derivation assumed every leg was drawn forward, which a break does not honour
     * when the leg's endpoints follow each other the other way round (a loop's closing leg).
     */
    val legAxes = ArrayList<Int>()
    var closed: Boolean = false

    /**
     * The frame source node this path is placed under (OP-16), or null while it lives in world
     * coordinates.
     *
     * When set, **every coordinate this path holds is local**: the binding structure that keeps a leg
     * axis-aligned is untouched and now relates local coordinates, so the legs stay straight and
     * perpendicular *in the group* and a turned frame turns the whole path. Handles read it to
     * inverse-map the cursor, and break/join read it to convert the positions they are given.
     */
    var frame: SourceNode? = null
        internal set

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

/**
 * A point where things meet, and the **owner of that point's freedom** (OP-20).
 *
 * Without this, whichever run was connected first ended up owning the shared DOF and every later
 * arrival inherited none — so two runs meeting at one point behaved differently for no reason the user
 * could see. The total number of degrees of freedom was always right; only their *attribution* was
 * order-dependent, and the editor exposes attribution.
 *
 * A junction on a curve owns one DOF (a point-on-curve parameter); a junction at a free point owns its
 * two coordinates. Everything meeting there binds to it, so no participant owns the shared freedom and
 * all of them reach it the same way: through [handle], one structural hop away — no search, no probing.
 */
class Junction(val point: PointRef, handle: Handle?, val curve: Element?) {
    /**
     * How the junction's own freedom is reached by a drag. A `var` because a placement **re-anchors** that
     * freedom to a point of the carrier (OP-16 × OP-4 case b) and the offset is then what a drag writes —
     * the same swap a re-anchored rider's element handle makes.
     */
    var handle: Handle? = handle

    /**
     * This junction's one degree of freedom, when it rides a curve: the parameter node, and which of
     * [Document.RiderForm]'s forms it is stated in (null for a junction at a free point, whose freedom is
     * that point's own coordinates).
     *
     * Held because a **placement** has to carry it (OP-16): a junction's parameter is anchored to the
     * *world* — a coordinate the host leaves free, or a distance along the carrier line (OP-20) — so a
     * frame that moves the carrier leaves the junction where it was and the figure hung on it deforms.
     * That is the same question a rider's [Document.RiderRecord] answers, asked of a connection instead of
     * an element.
     */
    internal var param: SourceNode? = null
    internal var form: Document.RiderForm? = null
    internal var line: LineRef? = null
    internal var axis: Int? = null

    /** The carrier point this junction has been measured from since a placement captured it (OP-16). */
    internal var base: Element? = null

    /** Its signed offset from [base] — the junction's freedom in the re-anchored, frame-rigid form. */
    internal var offset: SourceNode? = null

    /** True while the position is stated **relative to [base]** rather than to the world (OP-4 case b). */
    val carrierRelative: Boolean get() = offset != null

    /**
     * Put this junction where its coordinate [axis] (0 = x, 1 = y) equals [value], exactly, by solving
     * for its own parameter. Closed form per curve kind — a line is affine in its parameter, a circle
     * has two solutions and the nearer is kept — so typing a driven coordinate stays as exact as
     * dragging it (OP-13), with no solver anywhere.
     */
    var place: (axis: Int, value: Double) -> Boolean = { _, _ -> false }

    /**
     * Whether [place] can reach coordinate [axis] **at all** — a structural question about the host, asked
     * before any value exists, so a panel field can say up front whether it is derived.
     *
     * A junction on a host that is axis-aligned *by construction* owns exactly one world coordinate; the
     * other one is the host's and no value can move it. Typing and dragging are one operation (OP-13), so
     * they have to **refuse together**: without this the panel offered a `y` on a junction riding a
     * horizontal wall, and writing it did nothing at all (GitHub issue #4).
     *
     * A value that is merely out of reach — an x beyond a circle's diameter — is still a [place] refusal;
     * this asks only whether the axis is one the junction has any say over.
     */
    var placeable: (axis: Int) -> Boolean = { _ -> false }
}

/**
 * A parametric interval along a thick path's carrier (OP-21) — what the UI calls an *opening*.
 *
 * [position] is the distance from leg [legIndex]'s start and [width] the extent along it; [sill] and
 * [head] are the two heights the interval carries for the solid (OP-17), which the plan drawing does
 * not use. Nothing here cuts the footprint: an interval is a *description*, and the plan gap it
 * produces is a drawing convention.
 */
class PathInterval(
    val legIndex: Int,
    val position: ScalarRef,
    val width: ScalarRef,
    val sill: ScalarRef,
    val head: ScalarRef,
)

/**
 * What a [ThickNetwork] is thickened over — **one type, two constructor cases** (the OP-21 extension).
 *
 * Unifying the retained model rather than delegating between two of them is what keeps the plan
 * convention, the jambs, the interval clamps, *Cut openings*, the inspector, the pick cycle and the file's
 * `opening` step written once. What is *not* unified is the geometry underneath, and that is the point of
 * the split: an [Ortho] carrier keeps computing its footprint with the very `thickFaces`/`thickRegion` it
 * always did, so every stored `wall` step replays to the identical region.
 */
sealed interface ThickCarrier {
    /**
     * The rectilinear case the *Wall* tool draws: an ortho polyline, one [justification] for the whole run.
     */
    class Ortho(
        val vertices: List<PointRef>,
        val closed: Boolean,
        val justification: Justification,
        /** The carrier path this was built over, when it came from the ortho-path tool. */
        val path: OrthoPath?,
        /**
         * Each carrier point's own corner radius and bevel setback (GitHub #25) — the very nodes the path's
         * vertices hold, so a corner rounded after this wall was built is followed with nothing rewired.
         * Empty for a wall the *Wall* tool drew over clicked points, which has no path to round.
         */
        val rounds: List<ScalarRef> = emptyList(),
        val bevels: List<ScalarRef> = emptyList(),
    ) : ThickCarrier

    /**
     * A **connected subgraph** of ordinary curves — segments, arcs, Béziers — each with its own [sides]
     * entry. Connectivity is by shared endpoints and is a function of *values*, so it is checked inside the
     * footprint node's `compute` and not here (see `Construction.thickNetworkFootprint`).
     */
    class Network(
        val curves: List<Element>,
        val sides: List<Justification>,
    ) : ThickCarrier
}

/**
 * A retained **thick network** (OP-21 and its generalization): the offset region of [thickness] around a
 * [carrier], with parametric [intervals] along its legs. A wall is one use of this and gives the tools
 * their names; the model deliberately says nothing about walls.
 *
 * The geometry is a single [footprint] element over one `Region` node, so editing the carrier, the
 * thickness or an interval **recomputes** rather than regenerates: no element is replaced, no node is
 * orphaned, and the carrier's own curves are untouched (they stay draggable exactly as they were).
 */
class ThickNetwork(
    val carrier: ThickCarrier,
    val thickness: ScalarRef,
    /** The one displayable output: the footprint region (OP-14). */
    val footprint: Element,
) {
    val intervals = ArrayList<PathInterval>()

    /** The ortho case's carrier vertices — empty for a curve network. */
    val vertices: List<PointRef> get() = (carrier as? ThickCarrier.Ortho)?.vertices ?: emptyList()

    /** Whether the ortho carrier is a ring. A curve network says so by enclosing area, not by a flag. */
    val closed: Boolean get() = (carrier as? ThickCarrier.Ortho)?.closed ?: false

    /** The ortho case's single justification; a network's side is per curve (`ThickCarrier.Network.sides`). */
    val justification: Justification get() = (carrier as? ThickCarrier.Ortho)?.justification ?: Justification.CENTER

    /** The ortho path this was built over, when there is one. */
    val path: OrthoPath? get() = (carrier as? ThickCarrier.Ortho)?.path

    val legCount: Int
        get() =
            when (carrier) {
                is ThickCarrier.Ortho -> if (carrier.closed) carrier.vertices.size else carrier.vertices.size - 1
                is ThickCarrier.Network -> carrier.curves.size
            }
}

/**
 * What an existing wall hands the *Thicken* tool when it is picked to be **extended** (GitHub #7): the
 * carrier [curves] it already has in order, the [clicks] that made them, their [sides], and the wall's own
 * [thickness] parameter — which the tool then shows and keeps, instead of the value typed into its own field.
 *
 * A description of the gesture so far, deliberately: the tool goes on collecting picks exactly as it does for
 * a new wall, and what makes the difference is only where the result is written (see
 * [Document.thickNetworkExtension]).
 */
class ThickExtension internal constructor(
    val curves: List<Element>,
    val clicks: List<Vec2>,
    val sides: List<Justification>,
    val thickness: ScalarEntry,
)

/**
 * One **jamb** (reveal) line of an interval, exactly as the plan convention draws it (OP-21): which
 * [interval] it belongs to, which of that interval's two edges ([atEnd]), and where the line runs *now*.
 *
 * Derived per pass along with the rest of the plan ([Document.jambsOf]) and owned by nobody: there is no
 * element for a jamb and no node under it — the footprint stays one whole region. Picking therefore
 * *resolves* a jamb into a [JambHandle] over the thick path's existing parameters, the same way an ortho
 * leg is addressed through its path, and nothing is stored that could go stale.
 */
class Jamb(
    val path: ThickNetwork,
    val interval: PathInterval,
    val atEnd: Boolean,
    val seg: Segment,
) {
    /** This jamb's grabbable DOF (OP-13). Cheap and stateless, so it is made where it is needed. */
    fun handle(doc: Document): JambHandle = JambHandle(doc, path, interval, atEnd)
}

/** Whether a pattern's members close a ring or run out as a row — see [Pattern] (OP-23). */
enum class PatternKind { CIRCULAR, LINEAR }

/**
 * One **orbit** of a pattern: the members one gesture produced, indexed by position (OP-23).
 *
 * The pattern's own ring is orbit 0 (the reference point and its copies); every replicated gesture adds
 * one orbit per element it builds per copy, which is what makes the orbit *grow* — geometry built on
 * replicated geometry is replicated too, because its outputs are members at their index in turn.
 */
class PatternOrbit internal constructor(
    val pattern: Pattern,
    val members: List<Element>,
) {
    val size: Int get() = members.size
}

/**
 * Where an element sits in a pattern (OP-23) — and, with nesting, it sits in one per level (#18).
 *
 * [orbitPos] is the orbit's position in its pattern's own list, which is the *copy-independent* name of it:
 * every copy of a ride builds the same construction in the same order, so orbit 3 of copy 0's pattern and
 * orbit 3 of copy 4's are the same orbit of the same rule.
 */
class MemberSite internal constructor(
    val pattern: Pattern,
    val orbit: PatternOrbit,
    val orbitPos: Int,
    val index: Int,
) {
    val level: Int get() = pattern.depth
}

/**
 * How a replicated gesture finds its pick again in every copy (OP-23), at every level it rides (#18).
 *
 * Either the one [fixed] element **invariant** under every level's transform — the plain outside input OP-23
 * admits — or a member, described by [anchor] (member 0 of its orbit in the all-zero copy, the element the
 * step names), the orbit's copy-independent position, and [offsets]: one index per level up to its own, being
 * a **copy shift** at each level outside it and *how far along its orbit* at its own.
 *
 * So a one-level pick is exactly what OP-23 recorded (`e2@1`), and one riding a pattern nested inside the
 * pattern says which copy that pattern belongs to before saying where in it the pick lies (`e2@0@3`).
 */
class OrbitPick internal constructor(
    val anchor: Element,
    /** Which orbit of its own level's pattern, by position — meaningless for a [fixed] pick. */
    val orbitPos: Int,
    val offsets: List<Int>,
    val fixed: Element?,
) {
    /** The level whose pattern owns this pick's orbit; -1 when it is [fixed] everywhere. */
    val level: Int get() = offsets.size - 1

    /** Whether an index shift moves this pick at all. */
    val rides: Boolean get() = fixed == null

    /** How far along its own orbit — the whole of a one-level pick, which most gestures are. */
    val offset: Int get() = offsets.lastOrNull() ?: 0
}

/**
 * One nesting level a replicated gesture rides (OP-23, #18): the **anchor** pattern at that depth — the one
 * reached through copy 0 of every level outside it.
 *
 * Level 0 is the pattern the user drew. A deeper level exists because a gesture riding that pattern
 * *carried a pattern of its own*: every copy of it built one, they are congruent by construction (copy *j*'s
 * is the outer transform of copy 0's), and copy 0's is therefore the one that can speak for all of them —
 * its count, whether it wraps, and the transform a cell-local click is carried by.
 */
class OrbitLevel internal constructor(
    val pattern: Pattern,
    /** How many copies the ride that built this level's patterns made *at* this level — 0 for level 0. */
    val hostCopies: Int = 0,
) {
    val count: Int get() = pattern.count

    val wraps: Boolean get() = pattern.wraps
}

/**
 * One **replicated gesture** (OP-23): the rule by which a tool application was stamped round a pattern.
 *
 * It is not a copy of geometry — it is the gesture itself, recorded as picks-by-index plus the clicks that
 * scored its choices, so re-running it at another count is re-running the same rule. [outputs] are the
 * orbits it produced, one per element each copy built — at *every* level it rides, which is what makes the
 * nesting compose (#18).
 */
class OrbitGesture internal constructor(
    /** The levels this gesture rides, outermost first; `levels[0].pattern` is [pattern]. */
    val levels: List<OrbitLevel>,
    val toolId: String,
    val points: List<OrbitPick>,
    val elements: List<OrbitPick>,
    /** One per slot of the tool: that click, carried back to the cell of index 0 at every level. */
    val cells: List<Vec2>,
    /** One per slot: the cell — one index per level — the click of that slot belongs to in the base copy. */
    val cellIndices: List<List<Int>>,
    val scalars: List<ScalarEntry>,
    /** The discrete choices scored **once**, at the click that made the gesture (OP-1, OP-18). */
    val signs: List<Int>,
    val count: Int,
    /**
     * Whether this gesture's first operand is **the part of the face space, re-resolved per copy** — which is
     * what makes a subtractive orbit a *chain*: copy *k* cuts the tip left by copy *k*-1 (OP-17's
     * sequential-feature rule, applied once per index). Declared by the tool ([ToolDef.facePartOperand]) and
     * recorded as `part=tip`, because the base of copy *k* is a different body for every *k* and for every
     * count — so there is no name to bake in.
     */
    val chainsPart: Boolean = false,
) {
    /** The pattern this gesture rides outermost — the one its `orbit` step names. */
    val pattern: Pattern get() = levels[0].pattern

    val outputs = ArrayList<PatternOrbit>()

    /**
     * The patterns this gesture's copies built, keyed by the copy's own index vector (#18) — the level a
     * later gesture rides one step further in. The all-zero copy's are the **anchors**.
     */
    val inner = LinkedHashMap<List<Int>, MutableList<Pattern>>()

    /** The `orbit` step that records this gesture — what a re-stamp replays at another count. */
    var step: Step? = null
        internal set

    /** How many copies it built at each level, outermost first — the fan as it stands (#18). */
    var fan: List<Int> = emptyList()
        internal set

    /** …and the whole of it, which is the product over the levels. */
    val fanTotal: Int get() = fan.fold(1) { a, b -> a * b }

    /** Every pick that rides an orbit — the ones an index shift moves, at any level. */
    val riding: List<OrbitPick> get() = (points + elements).filter { it.rides }

    /** The picks whose **own** level is [level] — the ones whose span that level's count has to hold. */
    internal fun ridingAt(level: Int): List<OrbitPick> = riding.filter { it.level == level }

    /** …and those that merely shift copies there, on their way to a deeper orbit. */
    internal fun shiftingAt(level: Int): List<OrbitPick> = riding.filter { it.level > level }

    /** What this gesture is called in a refusal: the tool it applied. */
    val label: String get() = toolId
}

/**
 * A **pattern**: a rule (a reference member, what it is repeated about, and a count) plus the list of
 * gestures that ride it (OP-23).
 *
 * The pattern owns no copied geometry of its own beyond its ring: what a replicated gesture builds is
 * built **on the shared members**, so adjacent copies genuinely share nodes and no seam exists to mend.
 */
class Pattern internal constructor(
    val id: String,
    var name: String,
    val kind: PatternKind,
    /** Member 0 — the point that was clicked as the reference, kept by every count. */
    val reference: Element,
    /** The centre (circular) or the far end of the step vector (linear). */
    val about: Element,
    val count: Int,
) {
    val orbits = ArrayList<PatternOrbit>()
    val gestures = ArrayList<OrbitGesture>()

    /** The step that declared this pattern — its `count=` is what a re-stamp rewrites. */
    var step: Step? = null
        internal set

    /**
     * The replicated gesture whose copy built this pattern (#18), or null for one the user drew directly —
     * *a pattern of a pattern*, which is what makes the addressing compose.
     *
     * A nested pattern has no `pattern` step of its own: it is one of the things a copy of [enclosing] does,
     * and that gesture's single `orbit` step is what re-runs the lot. Its count therefore lives in that
     * step's `count=` literal, which is the level's own literal exactly as OP-23 demands.
     */
    var enclosing: OrbitGesture? = null
        internal set

    /** Which copy of [enclosing] built it — one index per level of that gesture. */
    var enclosingIndex: List<Int> = emptyList()
        internal set

    /** Which of that copy's patterns it is, when a copy builds more than one. */
    var enclosingSlot: Int = 0
        internal set

    /** How deep the nesting is: 0 for a pattern the user drew, one more per level of its enclosing gesture. */
    val depth: Int get() = enclosing?.levels?.size ?: 0

    /** Orbit 0: the reference point and its count-1 copies. */
    val ring: PatternOrbit get() = orbits[0]

    /** Whether index arithmetic wraps: a full turn closes, a row does not. */
    val wraps: Boolean get() = kind == PatternKind.CIRCULAR

    /**
     * The elements a replicated gesture may use **besides** members: those the pattern's transform leaves
     * where they are. A rotation has exactly one fixed point, its centre; a translation has none — so a
     * linear pattern's gestures may only touch members and shared scalars.
     */
    val invariants: List<Element> get() = if (kind == PatternKind.CIRCULAR) listOf(about) else emptyList()
}

/**
 * What replicating a gesture over a pattern would do — or why it will not happen (OP-23).
 *
 * A refusal is not a failure of the gesture: the tool still applies once, as an ordinary step. What the
 * user must be told is that it did *not* fan out, and which input kept it from doing so.
 */
class Replication internal constructor(
    val pattern: Pattern,
    val gesture: OrbitGesture?,
    /** How many copies each level contributes, outermost first — their product is the whole fan (#18). */
    val copies: List<Int>,
    val refusal: String?,
    /**
     * Why the gesture does **not** reach one level deeper, when it touches a nested pattern but cannot be
     * stamped round it (#18). Not a refusal: the levels above it still fan, and this says what was left out.
     */
    val deeper: String? = null,
) {
    /** The whole fan — the product over the levels. */
    val total: Int get() = copies.fold(1) { a, b -> a * b }
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
     * How many **in-place rewirings** this document has seen: a weld, an attach, a re-parameterization
     * (OP-4 case b). Such an edit changes the construction while creating neither an element nor a scalar,
     * which is exactly what [recording]'s `skipIfEmpty` used to mistake for "the tool did nothing" — the
     * *Join points* tool welded two points and then had its step dropped as empty, so the join was lost on
     * save although the same weld performed by dragging was kept.
     */
    private var edits = 0

    /** Say that an in-place rewiring happened — see [edits]. */
    private fun noteEdit() {
        edits++
    }

    /** The step that performed each element's re-parameterization (OP-4 case b) — see [recording]. */
    private val reparamSteps = HashMap<String, Step>()
    private val pendingReparams = ArrayList<String>()

    /** Say that the operation being recorded re-parameterized [el] — the step it belongs to restates it. */
    private fun noteReparam(el: Element) {
        pendingReparams.add(el.id)
    }

    /**
     * The re-parameterization state [step] must restate (OP-18): the elements *it* re-anchored, and their
     * offsets — a distance and an angle for a polar offset, one signed distance along a carrier.
     */
    internal fun reparamDofs(
        step: Step,
        ev: Evaluator,
    ): List<Quantity> {
        val at = journal.indexOfFirst { it === step }
        return elements.filter { reparamSteps[it.id] === step }.flatMap { relativeDofs(it, ev, at) }
    }

    /**
     * What the operation just run has to say, in the user's terms — a result worth reading, or the reason it
     * refused. Consumed once, by whoever ran it ([takeNote]).
     *
     * An operation that only rewires ([makeRelative], [makeAbsolute]) changes nothing the canvas can show, so
     * a silent success is indistinguishable from a silent refusal. One channel rather than a per-tool case in
     * the controller, so the next such tool needs no work there either.
     */
    var note: String? = null
        private set

    /** Read [note] and clear it: a note is about the operation that produced it, and only about that one. */
    fun takeNote(): String? = note.also { note = null }

    /**
     * Run [body] as one journal step. Nested calls are absorbed into the outermost one, so a tool that
     * calls several document operations is recorded as the single tool application the user performed —
     * which is also the only granularity that replays correctly.
     *
     * **A step and the elements it creates are one thing** (OP-27). The step can only be written *after*
     * the body has run — it has to know what was created — so a body that leaves by any route other than
     * returning would leave its creations behind with no step to own them: unsaveable, undeletable,
     * invisible to undo. So the one non-returning route there is, a throw, takes them with it: what the
     * body created is removed and nothing is recorded, and the failure goes on up. That is the invariant
     * made structural at the seam that owns it rather than a rule every builder has to remember.
     */
    private fun <T> recording(
        kind: String,
        vararg args: Arg,
        skipIfEmpty: Boolean = false,
        /**
         * Arguments only the finished body can state — a replicated gesture's `signs=`, which are scored by
         * the first copy it builds and then handed to the others verbatim (OP-1: scoring happens once). Invoked
         * exactly when the step is kept, so a skipped step evaluates nothing.
         */
        argsAfter: (() -> List<Arg>)? = null,
        body: () -> T,
    ): T {
        if (recordDepth > 0) return body()
        recordDepth++
        note = null // a note is about the operation being run now, never about the one before it
        pendingReparams.clear()
        pendingDofs.clear()
        pendingExprBinding = null
        pendingFuncCurve = null
        // an identity snapshot, not a count: a step may *remove* elements too (a break replaces one
        // leg with three), and then a count would mistake shifted survivors for new ones
        val before = elements.toHashSet()
        pendingBefore = before
        // an identity snapshot for the same reason the element one is: a step may **remove** a scalar too —
        // freeing an anchored ortho coordinate takes the offset's panel row with it (issue #23) — and a
        // count would then read as "fewer than before" and index past the end of the list
        val scalarsBefore = scalars.toHashSet()
        val editsBefore = edits
        try {
            val result = body()
            val created = elements.filter { it !in before }
            // a tool whose build had no effect is not part of the construction — where "effect" counts an
            // in-place rewiring too, since a tool may bind rather than build (see [edits])
            if (skipIfEmpty && created.isEmpty() && scalars.toHashSet() == scalarsBefore && edits == editsBefore) return result
            val step = Step(kind, args.toList() + (argsAfter?.invoke() ?: emptyList()))
            step.creates.addAll(created)
            step.createsScalars.addAll(scalars.filter { it !in scalarsBefore })
            // …and the freedoms this operation's defaulted slots left it holding (see [toolScalarRefs]).
            // Only when there is an element to reach them through: a step that creates nothing has no field
            // to offer, and a value written to a file that nothing can edit is noise, not a freedom.
            if (created.isNotEmpty()) step.ownDofs.addAll(pendingDofs)
            pendingDofs.clear()
            noteSpace(kind)
            journal.add(step)
            // …and the expression this operation bound, owned by the step that states it (OP-7, session 71):
            // per step and not per parameter, since a re-bound parameter has two steps and each keeps the
            // text *it* stated — which is what the writer restates under the current names
            pendingExprBinding?.let { exprBindings[step] = it }
            pendingExprBinding = null
            // …and the two texts this operation's function curve states, owned by its step for the same
            // reason: the writer restates them under the current names, and nothing else may (session 71)
            pendingFuncCurve?.let { funcCurves[step] = it }
            pendingFuncCurve = null
            // …and the size law this operation's sweep or tube states, owned by its step for the same reason
            // (OP-26, session 77): the writer restates it under the current names, and nothing else may
            pendingSweepLaw?.let { sweepLaws[step] = it }
            pendingSweepLaw = null
            // …and the **family laws** this operation's sweep states (OP-26, session 79), by the identical
            // rule one tier up: the binding is the sole authority for the texts, so the writer restates the
            // step's own `laws=` from it and re-stating a row is an edit of this very step
            pendingSweepFamily?.let { sweepFamilies[step] = it }
            pendingSweepFamily = null
            // …and the correspondence this operation's skin states, owned by its step for the same reason
            // (session 78): the writer restates the pairs under the current names, and nothing else may —
            // which is what makes a rename re-stamp them and a *Match* an edit of this very step
            pendingSkinMatch?.let { skinMatches[step] = it }
            pendingSkinMatch = null
            // which step *owns* each re-parameterization this operation performed (OP-4 case b), so the writer
            // restates an offset on the step that made it and nowhere else — a step that merely *uses* a
            // relative point (a circle through it) must not carry its distance and angle
            for (id in pendingReparams) reparamSteps[id] = step
            pendingReparams.clear()
            return result
        } catch (t: Throwable) {
            // the half-built gesture leaves with the throw — see the invariant above. Elements and scalars
            // are exactly what a [Step] owns, hence exactly what an unwritten step must not leave behind;
            // whatever else the failed body touched is the caller's to restore ([Editor.transacted] reloads
            // the whole document from the last checkpoint, which is the complete answer).
            elements.retainAll(before)
            scalars.retainAll(scalarsBefore)
            pendingReparams.clear()
            pendingDofs.clear()
            pendingExprBinding = null
            pendingFuncCurve = null
            pendingSkinMatch = null
            throw t
        } finally {
            recordDepth--
            pendingBefore = null
        }
    }

    /**
     * The freedoms created for the operation being recorded now, adopted by its step in [recording].
     *
     * A field rather than a return value for [recording]'s own reason: the step object does not exist until
     * the body has run, and what created these is inside the body ([toolScalarRefs]).
     */
    private val pendingDofs = ArrayList<StepDof>()

    /**
     * Run [tool]'s build as one recorded `tool` step — **the one seam** a click, a replay, a re-stamp and a
     * break's internal application all go through, so what a tool receives is decided in exactly one place.
     *
     * That is what makes an untyped optional scalar a *freedom* rather than a baked constant: see
     * [toolScalarRefs].
     */
    fun runTool(
        tool: ToolDef,
        picks: Picks,
        scalars: List<ScalarEntry>,
    ) = recordingTool(tool.id, picks, scalars) { tool.build(this, picks, toolScalarRefs(tool, picks.dofs, scalars)) }

    /**
     * The scalar refs [tool]'s [build] receives: the parameters picked or typed for it, and then **one free
     * source node per defaulted slot nobody stated a value for** ([ToolDef.ownedSlots]) — a degree of freedom
     * the step owns, standing at the slot's default.
     *
     * The reversal this is (OP-13's typing contract, amended). A build handed no value used to make its own
     * anonymous constant (`turns ?: cx.const(1)`), and an anonymous constant is a value **nothing can ever
     * reach**: not a drag, not a field, not the panel, not the file. A coil built without typing a turn count
     * could never be given a second turn, which is precisely the state OP-13 calls a bug in the model — *"a
     * hidden internal parameter … means a DOF exists that the user can only reach by dragging"*, and here not
     * even that. So the node is the same kind of node it always was; what changed is that the step **knows
     * about it**, restates it (`dofs=`) and offers it as a field.
     *
     * [restated] is the step's `dofs=` as a replay hands it back, read from the **end** of that list because a
     * step's own state (a rider's parameter, a dimension's placement) comes first and is consumed by the
     * build. A value of the wrong dimension is ignored in favour of the default rather than trusted, which is
     * what keeps that positional reading honest for a tool that ever owns both. An **old file** carries none
     * of this, and then every freedom stands at its default — which is exactly what that file always meant,
     * so no literal changed meaning and no version bump is owed (OP-18).
     */
    private fun toolScalarRefs(
        tool: ToolDef,
        restated: List<Quantity>,
        picked: List<ScalarEntry>,
    ): List<ScalarRef> {
        val owned = tool.ownedSlots(picked.size)
        if (owned.isEmpty()) return picked.map { it.ref }
        val refs = ArrayList<ScalarRef>(picked.map { it.ref })
        val values = restated.takeLast(owned.size)
        for ((j, i) in owned.withIndex()) {
            val slot = tool.scalars[i]
            // unreachable — [ToolDef.ownedSlots] offers only slots that have one — and it *stops* rather than
            // skipping, because a hole here would shift every slot behind it by one
            val default = slot.default ?: break
            val q = values.getOrNull(j)?.takeIf { it.dim == slot.dim } ?: default
            val node = SourceNode(nextId("td"), ScalarValue(q))
            pendingDofs.add(StepDof(slot.name, node, slot.dim))
            refs.add(Ref(node))
        }
        return refs
    }

    /**
     * The step-owned degrees of freedom addressable through [el], as ordinary [HandleField]s (OP-13: typing
     * and dragging are one operation, and a field is the typed half).
     *
     * Offered on **every element that step created**, and that is a decision rather than a convenience: a
     * replicated gesture (OP-23) creates one element per copy from *one* freedom, so the coil's turn count is
     * as much a value of the fourth coil as of the first — and picking one of them to carry it would make the
     * others read as fully determined, which they are not. They share the node, so a write through any of them
     * is the same write.
     *
     * A step that created nothing at all can carry no freedom, because there would be nothing to reach it
     * through ([recording] drops them); the one tool in that position (*Space origin*) is named in DESIGN.md.
     */
    fun ownFields(el: Element): List<HandleField> {
        val step = creatingStep(el) ?: return emptyList()
        if (step.ownDofs.isEmpty()) return emptyList()
        return step.ownDofs.map { scalarField(it.name, it.node, it.dim) }
    }

    /**
     * Record [body] as a tool application, so replay re-runs the same [ToolDef] — which is what keeps
     * the format tool-agnostic: adding a tool needs no work here.
     */
    fun <T> recordingTool(
        toolId: String,
        picks: Picks,
        scalars: List<ScalarEntry>,
        body: () -> T,
    ): T =
        recording(
            "tool",
            *listOfNotNull(
                Arg.Text(toolId),
                Arg.Keyed("pts", Arg.Els(picks.points.mapNotNull { elementFor(it) })).takeIf { picks.points.isNotEmpty() },
                Arg.Keyed("els", Arg.Els(picks.elements)).takeIf { picks.elements.isNotEmpty() },
                Arg.Keyed("clicks", Arg.Positions(picks.clicks)).takeIf { picks.clicks.isNotEmpty() },
                // one ordered list, however many scalars the tool declares — a single-scalar tool writes
                // exactly what it always did (`scalar="r"`), so older files keep loading
                Arg.Keyed("scalar", Arg.Scs(scalars)).takeIf { scalars.isNotEmpty() },
                // the structural count (how many copies/vertices were built), so replay is exact and the
                // loader's element-count check can vouch for it — never re-derived from anything
                Arg.Keyed("count", Arg.Text(picks.count.toString())).takeIf { picks.count > 0 },
                // a **variable section's size law** (OP-26, session 77), stored verbatim and quoted so it may
                // breathe. A new *optional* argument on steps that already existed: absence is a section of
                // one size, which is what every file written before this carries — so no stored literal
                // changed meaning and no version bump is owed (OP-18, and the `tool sweep signs=` row).
                picks.law?.let { Arg.Keyed("law", Arg.Label(it)) },
            ).toTypedArray(),
            skipIfEmpty = true,
            body = body,
        )

    // ---- sketch spaces (OP-17): named 2D spaces, one of them active, the plan by default ----

    /**
     * Every sketch space, the plan first. The plan space always exists and is never removed: it is the
     * drawing, and a document with no space at all could not draw anything.
     */
    val spaces = ArrayList<SketchSpace>().also { it.add(SketchSpace(PLAN_SPACE, null)) }

    /** The plan — world XY, the space everything is drawn in until a face is chosen. */
    val planSpace: SketchSpace get() = spaces[0]

    /**
     * The space tools draw into and the canvas shows. **View state**: switching it is not a construction
     * step and not an undo step, exactly as panning is not — what the *file* records is which space each
     * step was made in, which is why the switch is written lazily by [noteSpace].
     */
    var activeSpace: SketchSpace = planSpace

    /** The space the journal is currently in — what [noteSpace] compares [activeSpace] against. */
    private var scriptSpace: SketchSpace = planSpace

    private var spaceCounter = 0
    private var datumCounter = 0
    private var stationCounter = 0

    fun spaceNamed(name: String): SketchSpace? = spaces.firstOrNull { it.name == name }

    /** The space [el] was drawn in (OP-17), the plan for anything drawn before spaces existed. */
    fun spaceOf(el: Element): SketchSpace = spaceNamed(el.space) ?: planSpace

    /**
     * Whether [el] is the *active* space's business: drawn in it, or — for the **part** this space is a face
     * of — addressable in it as that face. The second half is what makes a cut reachable by clicking: the
     * part has no plan in these coordinates, but it does have this face, and the face is where a `Subtract`
     * pick must land (see [faceOutlineOf]).
     *
     * [tip] is the part as it stands, from [facePartTip] — resolved once per search by the caller, because
     * resolving it is a graph walk and a pick asks this of every element.
     */
    fun addressableIn(
        el: Element,
        tip: Element? = facePartTip(),
    ): Boolean = el.space == activeSpace.name || (tip != null && el === tip)

    /**
     * Whether the **elements panel** lists [el] while this space is active (OP-17, GitHub issue #2).
     *
     * The rule, in one line: *an element belongs to one sketch space, except a solid, which belongs to none.*
     *
     * - drawn **in the active space** — every 2D element, scaffolding and result alike, including the outlines
     *   and areas that define a feature. One canvas shows one space, so this is what the drawing on screen is
     *   made of;
     * - a **solid**, always. A solid is not 2D: it has no position in any space's coordinates and it is shown
     *   in the 3D viewport, which is the same view whichever sketch space is active. Filtering solids by the
     *   space they happen to have been extruded in would hide the part exactly where the next feature is being
     *   drawn — and a boolean's operand on a face has to be reachable there (see [facePartTip]).
     *
     * The panel used to list the union of every space, which the reporter said "will get messy fast": a face
     * sketch showed the whole plan, and the row's `· space` suffix was the only thing distinguishing what the
     * canvas was drawing from what it was not. That suffix now only ever appears on a solid.
     */
    fun listedIn(el: Element): Boolean = el.space == activeSpace.name || el.kind == ElementKind.SOLID

    /**
     * The elements the panel lists for the active space — [listedIn], in document order.
     *
     * A query rather than a filter the shell applies: which elements a space owns is the document's rule, and
     * the browser shell renders whatever this returns (the same discipline as [Editor.selectionFields]).
     */
    fun listedElements(): List<Element> = elements.filter { listedIn(it) }

    /**
     * An element that cannot be built right now, and the node's own words for why (OP-3).
     *
     * The [name] is the drawing's one name for it (the naming authority, [nameOf]) rather than the internal
     * id, because this is what the status line and the panel say out loud. [own] separates the two kinds of
     * invalidity the evaluator produces: a node that failed *here* (an empty intersection, a sweep that would
     * cut into itself) from one that is only hidden because something upstream did — the cascade OP-3
     * propagates. A message that named a dependent would send the user to the wrong element.
     */
    class InvalidElement(
        val element: Element,
        val name: String,
        val reason: String,
        val own: Boolean,
    )

    /**
     * Every element whose value is [EvalResult.Invalid] right now, in document order (OP-3).
     *
     * The document's answer, not the shell's: invalidity is a property of *values*, so what is unbuildable
     * and why is asked here and merely rendered by whoever shows it — the status line's transition sentence
     * ([Editor.validityNote]), the panel's marked rows, an inspector's reason. Cheap enough to ask on every
     * change: a node that is still valid answers from its memo, and an invalid one is recomputed every pass
     * anyway (OP-3's healing promise, see `Node.computeMemoized`).
     */
    fun invalidElements(ev: Evaluator = Evaluator()): List<InvalidElement> =
        elements.mapNotNull { el ->
            val reason = (ev.eval(el.ref.node) as? EvalResult.Invalid)?.reason ?: return@mapNotNull null
            InvalidElement(el, nameOf(el), reason, own = !reason.startsWith(CASCADE_PREFIX))
        }

    /**
     * The **tip** of the part the active space cuts into: the most recent visible solid made *of* the
     * space's base solid (a boolean chain), or the base itself while nothing has consumed it yet. Null in
     * the plan space, which is a face of nothing — and null for a datum plane whose hinge belongs to no
     * solid, where there is likewise nothing to cut ([createDatumSpace]).
     *
     * **The sequential-feature rule** (OP-17), and the reason it exists: a second cut on a second face must
     * subtract from *the part*, not from the plate the part started as. Anchoring the boolean to the space's
     * original base forked the model instead of chaining it — two coincident one-hole solids, each claiming
     * to be the part. So a feature's operand is resolved *here*, at the moment the user asks for it (when
     * "the part" has one obvious answer, the newest solid the base's material has reached), and the tool
     * step then records **that solid by name** (OP-18), so replay is exact and re-resolution never happens.
     * The *plane* is a different question and keeps a different answer: it stays anchored to the original
     * base, because the face's geometry is that solid's face — only the boolean's operand advances.
     */
    fun facePartTip(ev: Evaluator = Evaluator()): Element? = activeSpace.anchor?.let { tipOfChain(it, ev) }

    /**
     * **The drawing's tip of [base]'s own chain**: the most recent visible solid made *of* [base]'s material —
     * through a boolean, a cut, a blend, anything that consumes a solid — or [base] itself while nothing has
     * consumed it yet.
     *
     * *The* sequential-feature rule (OP-17), extracted from [facePartTip] in session 71 so that its second
     * consumer reaches the same authority rather than a second reading of it. What it exists to prevent is the
     * **forked feature chain**: a feature anchored to the body a part *started* as leaves two coincident
     * solids, each claiming to be the part, and neither of them what the user was looking at when they
     * clicked. A cut on a second face asks it of the face space's anchor; an **edge blend** asks it of the
     * body the click landed on, so a fillet, a union with a pad, and then a chamfer are one body and not
     * three readings of one.
     *
     * Null only where [base] is itself hidden and nothing visible is made of it, which is the honest answer:
     * there is no tip to work on.
     */
    fun tipOfChain(
        base: Element,
        ev: Evaluator = Evaluator(),
    ): Element? =
        elements.lastOrNull { el ->
            el.kind == ElementKind.SOLID && el.visible && (el === base || madeOf(el, base, ev))
        }

    /** Whether [base] is part of [solid]'s **material** — a chain of solid-valued inputs ([isMaterial]). */
    private fun madeOf(
        solid: Element,
        base: Element,
        ev: Evaluator,
    ): Boolean {
        val target = base.ref.node
        val seen = HashSet<String>()

        fun walk(n: Node): Boolean {
            if (!seen.add(n.id)) return false
            for (i in n.inputs) {
                if (!isMaterial(ev, i)) continue
                if (i === target || walk(i)) return true
            }
            return false
        }
        return walk(solid.ref.node)
    }

    /**
     * The solids [space] takes its input geometry from: **every solid created before the space** (GitHub #9,
     * the user's design — *"a plane should use all intersections with ancestor solids (created 'before'
     * themselves) as input geometry"*). Empty for the plan, which is the drawing itself.
     *
     * This is *the* enumeration — the context the canvas draws ([spaceContext]), the members a click can take
     * as an input ([sectionCandidateNear]) and the corners an origin can be anchored on ([setSpaceOrigin]) all
     * come from it, so what is visible is what is pickable, on every kind of plane. Three rules make it:
     *
     * - **Ancestors only.** A solid counts when it was born at or before the space's own [SketchSpace.bornAt]
     *   mark. A solid created *afterwards* never appears here, however it is edited — which is what makes the
     *   plane → sketch → solid → cut chain acyclic *by construction*: the section nodes this list feeds can
     *   only ever take solids whose own inputs were fixed before the plane's node existed, so every
     *   plane→solid edge points backwards in creation order. It is also the recorded-not-discovered rule:
     *   a replay rebuilds the same list because it rebuilds the same history.
     * - **Outputs, not material** ([isMaterial]) — the 3D view's rule, applied *within the ancestor set*: a
     *   plate consumed by a boolean among the ancestors is that boolean's material and draws no section of
     *   its own. Restricted to the set so that a later boolean cannot retroactively empty an older plane's
     *   context, which is the same ancestors-only sentence read the other way round.
     * - **Valid and visible** (OP-3): a solid whose parameters make it invalid, or one the user has hidden,
     *   contributes nothing and comes back when it does.
     *
     * The space's own [anchor] is always in the list, first — the **face exception** the user states: a plane
     * on a face keeps that face as its input geometry, which is not an intersection but the degenerate
     * section [Section3.sectionOf] returns for a plane lying on a face, *in addition* to whatever else the
     * plane cuts there.
     */
    fun spaceAncestors(
        space: SketchSpace,
        ev: Evaluator = Evaluator(),
    ): List<Element> {
        if (space.plane == null) return emptyList()
        val anchor = space.anchor?.takeIf { it.kind == ElementKind.SOLID }
        val born =
            elements.filter { el ->
                el.kind == ElementKind.SOLID && el.visible && el.born <= space.bornAt && ev.valueOf(el.ref) is SolidValue
            }
        if (born.isEmpty()) return listOfNotNull(anchor)
        val consumed = consumedAmong(born, ev)
        val out = ArrayList<Element>()
        anchor?.let { out.add(it) }
        for (el in born) if (el !== anchor && el.ref.node.id !in consumed) out.add(el)
        return out
    }

    /**
     * The node ids among [candidates] that another candidate is made **of** — [isMaterial] followed to the
     * end, which is the very rule [constructit.editor.Scene3] tells an operand from an output by.
     */
    private fun consumedAmong(
        candidates: List<Element>,
        ev: Evaluator,
    ): Set<String> {
        val ids = candidates.mapTo(HashSet()) { it.ref.node.id }
        val consumed = HashSet<String>()
        val visited = HashSet<String>()

        fun walk(node: Node) {
            if (!visited.add(node.id)) return
            for (input in node.inputs) {
                if (!isMaterial(ev, input)) continue
                if (input.id in ids) consumed.add(input.id)
                walk(input)
            }
        }
        candidates.forEach { walk(it.ref.node) }
        return consumed
    }

    /**
     * The **section node** of [solid] at [space]'s plane (OP-17's section-inputs package) — one node per
     * (space, solid) pair, shared by every input taken from it.
     *
     * A real node, which is what makes the inputs a construction rather than a drawing: they are pure
     * functions of the solid and the plane, so retyping the plane's offset or dragging the part's corner
     * slides the section and everything anchored on it (OP-21: recompute, never rebuild).
     */
    @Suppress("UNCHECKED_CAST")
    fun spaceSectionNodeOf(
        space: SketchSpace,
        solid: Element,
    ): SectionRef? {
        val plane = space.plane ?: return null
        if (solid.kind != ElementKind.SOLID) return null
        val key = space.name to solid.id
        sectionNodes[key]?.let { return it }
        val node = cx.section(solid.ref as SolidRef, plane)
        sectionNodes[key] = node
        return node
    }

    /**
     * The section node of [space]'s own **anchor** — the solid the plane is derived from, or the part a datum
     * was resolved against at creation. Null for the plan and for a plane that belongs to no solid.
     *
     * Not [facePartTip]: the plane names a state of the model and its context is the section of *that*, while
     * which solid a *pick* of the part lands on is the tip's question and keeps the tip's answer
     * ([partOutlineOf]). Two questions, two answers, as the sequential-feature rule already says for the plane
     * itself. Since GitHub #9 the anchor is only the *first* of the plane's inputs ([spaceAncestors]) — it
     * stays singled out because it is what a face space is a face **of** and what a step's omitted `el=`
     * means.
     */
    fun spaceSectionNode(space: SketchSpace): SectionRef? {
        val anchor = space.anchor ?: return null
        return spaceSectionNodeOf(space, anchor)
    }

    /** Section nodes by (space name, solid id) — one each, so every input taken from it shares it (OP-5). */
    private val sectionNodes = HashMap<Pair<String, String>, SectionRef>()

    /**
     * The **section of the part at [space]'s plane**, as it stands — the space's own [SketchSpace.anchor],
     * which is the face a face space is a face of (OP-17).
     *
     * Null when there is nothing to cut. Otherwise this is the part's section in the space's own (u, v): a
     * face space's plane lies **on** a face, so its section is that face's boundary (which is what the face
     * view has always drawn, now derived rather than assumed); a datum plane's section is the curves the cut
     * produces. See [constructit.geom.Section3.sectionOf] for the exactness classes.
     *
     * Every *other* solid this plane cuts is in [spaceSections], which is what a click searches.
     */
    fun spaceSection(
        space: SketchSpace,
        ev: Evaluator,
    ): PlaneSection? {
        val node = spaceSectionNode(space) ?: return null
        return (ev.valueOf(node) as? SectionValue)?.section
    }

    /**
     * Every ancestor solid's section at [space]'s plane, in creation order, skipping the ones the plane
     * misses — the working plane's input geometry, whole (GitHub #9).
     *
     * One list, three readers: the context the canvas draws, the members a click can take, and the corners an
     * origin can be anchored on. A plane that cuts nothing answers with an empty list and says so.
     */
    fun spaceSections(
        space: SketchSpace,
        ev: Evaluator,
    ): List<Pair<Element, PlaneSection>> {
        if (space.plane == null) return emptyList()
        val out = ArrayList<Pair<Element, PlaneSection>>()
        for (solid in spaceAncestors(space, ev)) {
            val node = spaceSectionNodeOf(space, solid) ?: continue
            val section = (ev.valueOf(node) as? SectionValue)?.section ?: continue
            if (section.isEmpty) continue
            out.add(solid to section)
        }
        return out
    }

    /**
     * The boundary of the face [space] is a sketch on, in that space's **own (u, v) coordinates**. Null for
     * the plan, and null while the face's solid has no value (OP-3), where there is simply nothing to show.
     *
     * The **degenerate section** (OP-17's one rule): a face space's plane lies on a face, so the part's
     * section there *is* that face's boundary — a rectangle for an extrude's side face, exactly as before, and
     * a triangle for a pyramid's lateral face, which the hardcoded rectangle this replaces could not say.
     *
     * A *drawing* rather than a node **as far as the view is concerned** (like a thick path's plan convention,
     * [planOf]): it is the reference context that says where the face is, and it is the geometry a pick of the
     * base solid measures against — one rule, so what is visible is what is pickable. The *inputs* on the same
     * section are nodes ([spaceSectionNode]).
     */
    fun faceOutline(
        space: SketchSpace,
        ev: Evaluator,
    ): List<Vec2>? {
        if (!space.isFace) return null
        val section = spaceSection(space, ev) ?: return null
        if (section.onFace == null || section.isEmpty) return null
        return section.drawn.map { GeomMath.startOf(it) }
    }

    /**
     * The two ends of a **datum** space's hinge, in that space's own (u, v) — the reference context that
     * says where the drawing is (GitHub #6). Null for every other space, and null for a hinge with no
     * extent (see below).
     *
     * The hinge *is* the datum's u axis by construction (`Geom3.datumPlane`), so what is left to say is how
     * far along it the picked element reaches: a segment's or an ortho leg's two ends, projected onto the
     * carrier and measured from the datum's own anchor. For an **infinite** line or a ray there is no
     * extent to draw and this is null — the space's note says the hinge is the whole u axis, rather than a
     * length being invented for it.
     *
     * A *drawing* rather than a node, exactly like [faceOutline] — and, like it, the geometry a pick of the
     * part measures against ([partOutlineOf]).
     */
    fun datumHinge(
        space: SketchSpace,
        ev: Evaluator,
    ): List<Vec2>? {
        val hinge = space.hinge ?: return null
        // the segment's *own* carrier, computed here rather than through [carrierLine]: this is a drawing,
        // asked on every repaint and every pick, and an op node per call would grow the graph as the mouse moves
        val seg = (ev.valueOf(hinge.ref) as? SegmentValue)?.seg ?: return null
        val dir = (seg.b - seg.a).normalized()
        if ((seg.b - seg.a).length() < Vec2.EPS) return null
        val anchor = seg.a - dir * seg.a.dot(dir)
        return listOf(Vec2((seg.a - anchor).dot(dir), 0.0), Vec2((seg.b - anchor).dot(dir), 0.0))
    }

    /**
     * The **reference context** of [space] in its own (u, v): a face's rectangle ([faceOutline]) or a
     * datum's hinge ([datumHinge]). Null for the plan, which is the drawing itself and needs no context.
     *
     * One query, because one rule holds for both: what is drawn as context is also what a pick of the part
     * lands on ([partOutlineOf]), and it is what a first view of the space is framed on.
     */
    fun spaceOutline(
        space: SketchSpace,
        ev: Evaluator,
    ): List<Vec2>? = faceOutline(space, ev) ?: datumHinge(space, ev)

    /**
     * Everything [space] draws as **context**, in its own (u, v): the section of **every ancestor solid** at
     * this plane (GitHub #9), plus a datum's hinge (its own u axis, over the extent the picked line reaches).
     *
     * One query for the renderer, and the reason both are in it is that they answer different questions —
     * the hinge says *where on the drawing am I standing*, the sections say *where is the material*. A plane
     * that cuts nothing draws its hinge alone (or nothing at all) and says so in its note; a face space's
     * first section is the face itself.
     */
    fun spaceContext(
        space: SketchSpace,
        ev: Evaluator,
    ): List<ProfileElement> {
        val out = ArrayList<ProfileElement>()
        for ((_, section) in spaceSections(space, ev)) out.addAll(section.drawn)
        datumHinge(space, ev)?.let { h -> if (h.size == 2) out.add(ProfileElement.Seg(Segment(h[0], h[1]))) }
        return out
    }

    /**
     * [spaceOutline] of the active space, but only for the one element it stands for — the part at its
     * current [tip] — else null.
     *
     * The rectangle is the *base's* face (its geometry belongs to that solid) and it stands for the part as
     * it now is, which is what clicking it means: "this part, here". So once a cut exists, the rectangle
     * picks the cut and not the plate it came from — the same sequential-feature rule [facePartTip] states,
     * reaching the manual *Extrude → Subtract* path as well as the one-gesture *Cut*.
     */
    fun partOutlineOf(
        el: Element,
        ev: Evaluator,
        tip: Element? = facePartTip(ev),
    ): List<Vec2>? = if (tip != null && el === tip) spaceOutline(activeSpace, ev) else null

    // ---- section inputs: the load-bearing half of a working plane's context (OP-17) ----

    /** Which member of a section a click is reaching for: one of its curves, or one of its corners. */
    enum class SectionInput { EDGE, CORNER }

    /**
     * A click landing on a working plane's section: **which** member of the ordered set it is (OP-6), where,
     * and why it cannot be taken if it cannot.
     *
     * [refusal] is what makes this honest rather than silent: a curve the plane cuts into two pieces, a
     * sampled conic, a mesh-route section — each is *drawn*, so each can be clicked, and each has to say why
     * it is not an input rather than behaving like a miss.
     */
    class SectionCandidate(
        val space: SketchSpace,
        /**
         * **Which solid** this member is a section of (GitHub #9) — a plane cuts every ancestor, so an index
         * only addresses a member together with the solid it indexes into. Recorded with the pick.
         */
        val solid: Element,
        val kind: SectionInput,
        val index: Int,
        val at: Vec2,
        val provenance: String,
        val refusal: String?,
        /**
         * What kind of element taking this would create — asked *before* anything is created, so a slot that
         * cannot use an arc can decline without a node ever existing (the pick pipeline's own rule).
         */
        val elementKind: ElementKind,
    )

    /**
     * The member of the active space's sections nearest [at] within [tol] — corners first, then curves, which
     * is the same precedence a snap gives a point over the curve it lies on.
     *
     * Searched across **every ancestor solid's** section (GitHub #9), in creation order, nearest winning: the
     * plane's input geometry is all of them, so a click reaches whichever it lands on and the candidate says
     * which. Nothing is created here: this answers *what would be taken*, so a tool slot that cannot use it
     * can decline before a node exists (the pick pipeline's rule — an existing-only slot creates nothing on a
     * miss).
     */
    fun sectionCandidateNear(
        at: Vec2,
        tol: Double,
        ev: Evaluator,
        want: SectionInput? = null,
    ): SectionCandidate? {
        val space = activeSpace
        val sections = spaceSections(space, ev)
        if (sections.isEmpty()) return null
        var best: SectionCandidate? = null
        var bestDist = Double.MAX_VALUE
        if (want != SectionInput.EDGE) {
            for ((solid, section) in sections) {
                for ((i, c) in section.corners.withIndex()) {
                    val p = c.at ?: continue
                    val d = (p - at).length()
                    if (d <= tol && d < bestDist) {
                        bestDist = d
                        best =
                            SectionCandidate(
                                space, solid, SectionInput.CORNER, i, p, c.provenance, section.inputsRefusal,
                                ElementKind.DERIVED_POINT,
                            )
                    }
                }
            }
            if (best != null) return best
        }
        if (want == SectionInput.CORNER) return null
        for ((solid, section) in sections) {
            for ((i, e) in section.edges.withIndex()) {
                val piece = e.curve ?: e.sampled?.let { pts -> pts.firstOrNull()?.let { ProfileElement.Seg(Segment(it, pts.last())) } }
                val d =
                    if (e.curve != null) {
                        HitTest.distanceToPiece(at, e.curve)
                    } else {
                        e.sampled?.let { pts -> (0 until pts.size - 1).minOfOrNull { j -> HitTest.distanceToPiece(at, ProfileElement.Seg(Segment(pts[j], pts[j + 1]))) } }
                    } ?: continue
                if (piece == null) continue
                if (d <= tol && d < bestDist) {
                    bestDist = d
                    val why = section.inputsRefusal ?: sectionEdgeRefusal(section, i)
                    best = SectionCandidate(space, solid, SectionInput.EDGE, i, at, e.provenance, why, edgeElementKind(e))
                }
            }
        }
        // …and a piece no index names at all (a face the plane cuts twice) still has to answer for itself
        if (best == null) {
            for ((solid, section) in sections) {
                val hit = section.drawn.minByOrNull { HitTest.distanceToPiece(at, it) } ?: continue
                if (HitTest.distanceToPiece(at, hit) > tol) continue
                val why =
                    section.inputsRefusal
                        ?: section.edges.firstNotNullOfOrNull { it.reason?.takeIf { r -> r.contains("separate pieces") } }
                        ?: "that piece of the section has no single name to take as an input"
                best = SectionCandidate(space, solid, SectionInput.EDGE, -1, at, "a piece of the section", why, ElementKind.SEGMENT)
                break
            }
        }
        return best
    }

    /** Which element kind a section curve becomes — the accessor's kind is part of the stored choice. */
    private fun edgeElementKind(e: constructit.geom.SectionEdge): ElementKind =
        when (e.curve) {
            is ProfileElement.ArcE -> ElementKind.ARC
            is ProfileElement.CircleE -> ElementKind.CIRCLE
            // since session 27 an inclined cut through a cylinder is an exact ellipse, not a chord fan (OP-24)
            is ProfileElement.EllipseE -> ElementKind.ELLIPSE
            is ProfileElement.EllipticArcE -> ElementKind.ELLIPTIC_ARC
            else -> ElementKind.SEGMENT
        }

    /** Why curve [index] of [section] cannot be an input, or null when it can (the accessor's own rule). */
    private fun sectionEdgeRefusal(
        section: PlaneSection,
        index: Int,
    ): String? {
        val e = section.edges.getOrNull(index) ?: return "that curve is no longer part of the section"
        e.reason?.let { return it }
        if (e.sampled != null) {
            return "${e.provenance} is cut into a curve this drawing has no name for, so it draws but cannot be " +
                "anchored on — an inclined cut of a cylinder is an exact ellipse and *can* be, but a cut that " +
                "leaves the material through its ends, or one through a ruled face, cannot"
        }
        return null
    }

    /**
     * **Take** the member [candidate] addresses as a construction input: a real element, downstream of the
     * solid and the plane, recorded by index (OP-1/OP-18) and replayed verbatim.
     *
     * The precedent this follows is the rider's ([pointOnCurve]): a click that lands on something the drawing
     * only *draws* materializes the accessor it addresses, and the step remembers the choice rather than the
     * geometry. From then on it is an ordinary element — pickable, snappable, dimensionable, and usable by
     * every tool that takes a segment or a point, which is why no tool needed a case for sections.
     */
    fun takeSectionInput(candidate: SectionCandidate): Element? {
        if (candidate.refusal != null || candidate.index < 0) {
            note = candidate.refusal
            return null
        }
        return sectionInput(candidate.space, candidate.kind, candidate.index, candidate.solid)
    }

    /**
     * The recorded form of [takeSectionInput] — also the loader's entry point, so a replay creates the same
     * node from the same index and never re-scores which curve was meant.
     *
     * [solid] is **which ancestor** the index addresses (GitHub #9), written into the step as `el=` and
     * omitted when it is the space's own [SketchSpace.anchor] — which is what every `sectioninput` step
     * written before a plane had more than one section meant, so no stored literal changes meaning and no
     * version bump goes with the new argument (OP-18's doctrine). The *gesture* no longer picks a solid; the
     * *recording* still names one, so a replay re-discovers nothing.
     */
    @Suppress("UNCHECKED_CAST")
    fun sectionInput(
        space: SketchSpace,
        kind: SectionInput,
        index: Int,
        solid: Element? = null,
    ): Element? {
        val of = solid ?: space.anchor ?: return null
        val node = spaceSectionNodeOf(space, of) ?: return null
        val section = (Evaluator().valueOf(node) as? SectionValue)?.section
        return recording(
            "sectioninput",
            *listOfNotNull(
                Arg.Label(space.name),
                if (of === space.anchor) null else Arg.Keyed("el", Arg.El(of)),
                Arg.Keyed(if (kind == SectionInput.CORNER) "corner" else "edge", Arg.Text(index.toString())),
            ).toTypedArray(),
        ) {
            when (kind) {
                SectionInput.CORNER -> {
                    add(cx.sectionCorner(node, index), ElementKind.DERIVED_POINT, Styles.DERIVED_POINT)
                        .also { sectionInputAddress[it.id] = SectionAddress(space.name, of, kind, index) }
                }
                SectionInput.EDGE -> {
                    // the *kind* of the curve is part of the accessor and therefore of the step (three ids for
                    // one choice, the bbox-measurement precedent): a section curve that has since become an arc
                    // makes the input invalid with a reason rather than changing type under a construction
                    when (section?.edges?.getOrNull(index)?.curve) {
                        is ProfileElement.ArcE -> add(cx.sectionArc(node, index), ElementKind.ARC, Styles.CONSTRUCT)
                        is ProfileElement.CircleE -> add(cx.sectionCircle(node, index), ElementKind.CIRCLE, Styles.CONSTRUCT)
                        is ProfileElement.EllipseE -> add(cx.sectionEllipse(node, index), ElementKind.ELLIPSE, Styles.CONSTRUCT)
                        else -> add(cx.sectionSegment(node, index), ElementKind.SEGMENT, Styles.CONSTRUCT)
                    }
                }
            }
        }
    }

    // ---- the space origin: an anchor on the plane, plus an in-plane offset (OP-17) ----

    /** Where a materialized section input came from: its space, **its solid**, kind and index (OP-18). */
    class SectionAddress(
        val space: String,
        val solid: Element,
        val kind: SectionInput,
        val index: Int,
    )

    private val sectionInputAddress = HashMap<String, SectionAddress>()

    /** Section nodes at a space's **intrinsic** frame, one per (space, solid) — see [intrinsicSectionNode]. */
    private val intrinsicSectionNodes = HashMap<Pair<String, String>, SectionRef>()

    /**
     * [solid]'s section at [space]'s **intrinsic** plane — the frame before the origin control.
     *
     * The one thing an origin anchor may be built from, and the reason is a circle it would otherwise close:
     * the section a space *shows* is cut at the plane the space publishes, so a corner of it depends on the
     * origin and could not define it. The intrinsic section is the same section translated, so a corner index
     * addresses the same corner in both — guaranteed, because the face section's canonical ordering was made
     * translation-invariant for exactly this (`Section3.rotatedToFirstCorner`).
     *
     * Any ancestor solid may carry the anchor (GitHub #9), not only the space's own: an ancestor is stated
     * independently of this frame — it existed before it — so its corners can define the origin exactly as the
     * anchor's can.
     */
    @Suppress("UNCHECKED_CAST")
    fun intrinsicSectionNode(
        space: SketchSpace,
        solid: Element? = null,
    ): SectionRef? {
        val plane = space.intrinsic ?: return null
        val of = solid ?: space.anchor ?: return null
        if (of.kind != ElementKind.SOLID) return null
        val key = space.name to of.id
        intrinsicSectionNodes[key]?.let { return it }
        val node = cx.section(of.ref as SolidRef, plane)
        intrinsicSectionNodes[key] = node
        return node
    }

    /**
     * Move [space]'s origin: onto the section corner [corner] (null = back to the frame's own origin), plus
     * the in-plane offsets [dx] and [dy] (null = zero) — the two-layer origin control, generic over every
     * sketch space that has a plane (OP-17, the user's design).
     *
     * **Nothing is rewired.** The anchor is a source point and the offsets are source scalars, all three
     * inputs of the space's published plane from the moment it was created, so this is three in-place
     * bindings — the welding substrate (OP-5). Every feature ever sketched here holds that same plane node,
     * which is what makes re-anchoring **translate the whole sketch and everything built from it**: the 2D
     * numbers keep their meaning and the frame they are read in moves. That is the parametrically correct
     * reading rather than a limitation — it is how a sketch is moved on its face.
     *
     * The anchor is a *node*, so it tracks: drag the part's corner and the origin goes with it.
     */
    fun setSpaceOrigin(
        space: SketchSpace,
        corner: Int?,
        dx: ScalarRef? = null,
        dy: ScalarRef? = null,
        solid: Element? = null,
    ): Boolean =
        recording(
            "spaceorigin",
            *listOfNotNull(
                Arg.Label(space.name),
                solid?.takeIf { corner != null && it !== space.anchor }?.let { Arg.Keyed("el", Arg.El(it)) },
                corner?.let { Arg.Keyed("corner", Arg.Text(it.toString())) },
                dx?.let { Arg.Keyed("dx", Arg.Sc(scalarEntryFor(it))) },
                dy?.let { Arg.Keyed("dy", Arg.Sc(scalarEntryFor(it))) },
            ).toTypedArray(),
            skipIfEmpty = true,
        ) { setSpaceOriginNow(space, corner, dx, dy, solid) }

    private fun setSpaceOriginNow(
        space: SketchSpace,
        corner: Int?,
        dx: ScalarRef?,
        dy: ScalarRef?,
        solid: Element?,
    ): Boolean {
        val anchorNode = space.originAnchor?.node as? SourceNode
        if (anchorNode == null) {
            note = "the plan's origin is the world origin — it is what everything else is measured from"
            return false
        }
        val of = solid ?: space.anchor
        if (corner != null) {
            val section = intrinsicSectionNode(space, of)
            if (section == null) {
                note = "${space.name} has no part to take a corner from, so there is nothing to anchor its origin on"
                return false
            }
            val at = (Evaluator().valueOf(section) as? SectionValue)?.section?.corners?.getOrNull(corner)
            if (at?.at == null) {
                note = "${space.name}'s section has no corner #${corner + 1} to anchor on${at?.reason?.let { " — $it" } ?: ""}"
                return false
            }
            anchorNode.boundTo = cx.sectionCorner(section, corner).node
        } else {
            anchorNode.boundTo = null
        }
        space.originCorner = corner
        space.originSolid = if (corner == null) null else of
        (space.originDx?.node as? SourceNode)?.boundTo = dx?.node
        (space.originDy?.node as? SourceNode)?.boundTo = dy?.node
        // three in-place bindings and not one new element: without this the step would look empty and be
        // dropped, exactly as the *Join points* weld once was (see [edits])
        noteEdit()
        space.originDxEntry = dx?.let { scalarEntryFor(it) }
        space.originDyEntry = dy?.let { scalarEntryFor(it) }
        // said out loud, because moving an origin moves a whole drawing and nothing on screen would
        // otherwise distinguish "anchored here" from "anchored here, 10 mm along" (OP-3's speaking rule)
        val shift = listOfNotNull(space.originDxEntry, space.originDyEntry)
        note =
            "${space.name}'s origin is now " +
            // the solid is named only when it is *not* the space's own anchor, which is what a plane with more
            // than one section made possible (GitHub #9) — the ordinary case reads as it always did
            (corner?.let { "section corner #${it + 1}${if (of !== space.anchor) " of ${of?.let { s -> nameOf(s) }}" else ""}" } ?: "the frame's own origin") +
            (if (shift.isEmpty()) "" else ", offset by ${shift.joinToString(", ") { it.name }}") +
            " — everything drawn here moved with the frame"
        return true
    }

    /**
     * The gesture's form of [setSpaceOrigin]: anchor the active space's origin on the point [at], which must
     * be a **corner of a section on this plane** — the corners of the part, and of any other solid the plane
     * cuts (GitHub #9), which is the prime pick the user names ("a click, not a formula").
     *
     * Refused by name for anything else, and the reason is the one that makes the feature well-defined: a
     * point *drawn* in this space rides the frame it would be defining, so its own coordinates could not
     * anchor it. A corner of an ancestor solid is stated independently of the frame, which is why it can.
     */
    fun setSpaceOriginAt(
        at: Element,
        dx: ScalarRef? = null,
        dy: ScalarRef? = null,
    ): Boolean {
        val space = activeSpace
        val addr = sectionInputAddress[at.id]
        if (addr == null || addr.space != space.name || addr.kind != SectionInput.CORNER) {
            note =
                "${nameOf(at)} cannot fix ${space.name}'s origin: anchor on a corner of a section on this plane — " +
                "a point drawn on this plane moves with the frame it would define"
            return false
        }
        return setSpaceOrigin(space, addr.index, dx, dy, addr.solid)
    }

    /**
     * The plane the active space embeds on (OP-17) — what every feature built here sketches on.
     *
     * The plan answers with a fresh `planeXY` node, which is exactly what the seam's tools did before
     * spaces existed; a face space answers with its **one** stored plane node, shared by every feature
     * drawn on that face, so they all follow the same derived frame.
     */
    fun activePlane(): PlaneRef = activeSpace.plane ?: cx.planeXY()

    /**
     * The active working plane as a **frame**, or null when its own parameters make it invalid (OP-3) — a
     * datum hinged on a line that has been deleted, say.
     *
     * The plan needs no evaluation at all: it *is* the world XY plane by construction, so it is answered
     * without building a node, which keeps a repaint of the plan free of graph traffic. Every other space
     * asks the evaluator for its one stored plane node, and a null here is what makes the 3D view fall back
     * to being read-only instead of pointing gestures at a plane that does not exist (edit-in-3D slice 1).
     */
    fun activePlane3(ev: Evaluator): Plane3? = planeOf(activeSpace, ev)

    /**
     * The nearest solid boundary edge to [at]: the solid, and which of its boundary pieces (OP-8).
     *
     * The pick a plan-view editor can make: a **side face projects to exactly one footprint edge**, so
     * clicking that edge names the face, and the solid it belongs to, in one gesture. Measured against the
     * same footprint geometry the hint draws and [HitTest] picks by, so what looks like an edge is one.
     *
     * **A partial revolution's cap is picked by clicking the face itself, not an edge of it** (OP-17's item
     * 4). A revolve's footprint is its own profile, so its boundary edges name the *bands they sweep* — and
     * the cap standing in this very space is bounded by those same edges, which is the one ambiguity in the
     * whole address space. It is resolved by where the click lands rather than by a mode: on an edge means
     * the band that edge sweeps, inside the profile and clear of every edge means the cap the profile *is*.
     * Only a cap that actually lies in the space being clicked in can be reached this way, which is the
     * honest limit of a pick made in one plane; a cap standing elsewhere is reached by its stored address
     * or by a datum plane.
     */
    fun solidEdgeNear(
        at: Vec2,
        tol: Double,
        ev: Evaluator,
    ): Pair<Element, Int>? {
        // drawn *in this space*, deliberately not the space's own anchor: that solid shows its face here,
        // not its plan, so a click on it is not in the coordinates its boundary pieces live in
        val solid = HitTest.nearest(this, ev, at, tol) { it.kind == ElementKind.SOLID && it.space == activeSpace.name }
        if (solid == null) return capUnder(at, ev)
        val feature = (ev.valueOf(solid.ref) as? SolidValue)?.solid?.feature ?: return null
        val pieces = Geom3.boundaryPieces(feature)
        if (pieces.isEmpty()) return null
        val best = pieces.indices.minByOrNull { HitTest.distanceToPiece(at, pieces[it]) } ?: return null
        return solid to best
    }

    /**
     * The **cap of a partial revolution** the click [at] lands inside of, when the click reached no
     * boundary edge at all — the pick described on [solidEdgeNear].
     *
     * Only a cap standing in the plane being clicked in can be reached, and only from inside the profile it
     * *is*, so this never takes a click away from an edge: a boundary edge within tolerance has already
     * answered by the time this is asked. Everything about it is constructed — the cap's plane comes off the
     * turn interval ([Revolve3.capPlane]) and the containment off the profile's own rings — so what the
     * gesture records is an index like every other face address.
     */
    private fun capUnder(
        at: Vec2,
        ev: Evaluator,
    ): Pair<Element, Int>? {
        val here = planeOf(activeSpace, ev) ?: return null
        for (el in elements.asReversed()) {
            if (el.kind != ElementKind.SOLID || el.space != activeSpace.name || !el.visible) continue
            // a **dressed** revolve is still a revolve for this question: the blend trims its cap, it does
            // not move it, so the pick that reaches a cap must reach the body as it stands (session 71,
            // slice 3 — `Section3.undressed`). What the space then draws is the dressed cap, because the
            // face patch it opens on comes from the dressed list.
            val whole = (ev.valueOf(el.ref) as? SolidValue)?.solid?.feature ?: continue
            val feature = Section3.undressed(whole) as? Feature3.Revolution ?: continue
            val f = Revolve3.frameOf(feature) ?: continue
            if (f.full) continue
            val rings =
                feature.sketch.regions.flatMap { r ->
                    (listOf(r.outer) + r.holes).map { l -> l.elements.flatMap { GeomMath.tessellatePiece(it) } }
                }
            if (!RegionBool.contains(rings, at)) continue
            val n = Geom3.boundaryPieces(feature).size
            for ((k, which) in listOf(SolidFace.BOTTOM, SolidFace.TOP).withIndex()) {
                val p = Revolve3.capPlane(f, which)
                if (abs(abs(p.normal.normalized().dot(here.normal.normalized())) - 1.0) > 1e-9) continue
                if (abs(here.distanceTo(p.origin)) <= Section3.ON_PLANE_TOL) return el to (n + k)
            }
        }
        return null
    }

    /**
     * How a face space's frame is introduced, in the words of the face it actually is.
     *
     * A prism's or a loft's side face has no distinguished point, so its own picked edge is the anchor and
     * the note says so. A **face of revolution** has a centre — the axis pierces it — and that is where its
     * origin is put ([Revolve3]), which is what somebody sketching a boss on the end of a turned part is
     * measuring from; a *cap* of a partial turn is the profile itself, standing at one end of the sweep. A
     * **flat end** reached past the footprint's own pieces (edit-in-3D slice 2's addresses,
     * [Section3.FACE_ADDRESS_CONVENTION]) has no picked edge at all: it is the footprint's own coordinates,
     * which is the whole convenience of sketching on the top of a plate. A note that told all of them the
     * same story would be wrong about most of them (refusals and notes both name what a thing is — OP-3's
     * rule, applied to the status line).
     */
    fun faceFrameNote(space: SketchSpace): String {
        val whole = space.anchor?.let { (Evaluator().valueOf(it.ref) as? SolidValue)?.solid?.feature }
        val feature = whole?.let { Section3.undressed(it) }
        val piece = space.piece
        // **The inside of a wall** (session 75) — asked first, because an inner face's address stands past the
        // base's own ends and every sentence below would read it as one of those: a shelled revolve's inner
        // faces are not the caps a partial turn has, and a shelled plate's pocket floor is not its own cap.
        if (whole != null && Section3.facePatchOfFootprintPiece(whole, piece).first?.name is FaceName.ShellInner) {
            return "the coordinates the footprint was drawn in, standing on the inside of this wall — so what you " +
                "draw here lines up with the plan, one wall thickness in"
        }
        if (feature is Feature3.Revolution) {
            val n = Geom3.boundaryPieces(feature).size
            if (piece >= n) {
                return "the profile itself, standing at the ${if (piece == n) "start" else "end"} of the sweep, " +
                    "with the axis of revolution along v and the origin where the profile was drawn from"
            }
            return "u along the radius the profile is drawn at, the origin where the axis of revolution pierces " +
                "the face — so a circle at (0, 0) is concentric with the turned part"
        }
        if (whole != null && piece >= Geom3.boundaryPieces(whole).size) {
            return "the coordinates the footprint was drawn in, standing on this face — the origin under the " +
                "drawing's own, so what you draw here lines up with the plan"
        }
        return "u along the edge you picked, v up into the face, the origin at that edge's middle"
    }

    /**
     * **Which face of [solid] the point [at] names, aimed along [along]** — the *element*-level form of
     * [Section3.faceAt], and the one route a 3D face click takes (edit-in-3D slice 2).
     *
     * Returns the stored `sketchspace … piece=` address, or the reason there is none *in this drawing's own
     * names*: which body, which face, and — where the face is real but no plane — what does exist instead.
     * The geometry is Section3's and the words are this document's, which is the split every other refusal
     * here keeps.
     *
     * [tol] is the mesh's own tessellation sag ([Geom3.meshSag]), because the point comes off a chord.
     */
    fun faceAddressAt(
        solid: Element,
        at: Vec3,
        along: Vec3,
        tol: Double,
        ev: Evaluator = Evaluator(),
    ): Pair<Int?, String?> {
        val feature =
            (ev.valueOf(solid.ref) as? SolidValue)?.solid?.feature
                ?: return null to "${nameOf(solid)} has no solid to take a face from"
        val (pick, why) = Section3.faceAt(feature, at, along, tol)
        if (pick == null) return null to "${nameOf(solid)}: ${why ?: "nothing here is a named face"}"
        val piece = pick.piece
        if (piece == null) {
            return null to
                "${pick.patch.name.label} of ${nameOf(solid)} has no address a sketch can be stored at" +
                (pick.patch.reason?.let { " — $it" } ?: "") + flatFacesNote(solid, feature)
        }
        val refusal = Section3.facePatchOfFootprintPiece(feature, piece).second
        if (refusal != null) {
            return null to "${pick.patch.name.label} of ${nameOf(solid)}: $refusal${flatFacesNote(solid, feature)}"
        }
        return piece to null
    }

    /**
     * The faces of [solid] a sketch *can* be opened on, named — what a refusal points at, so declining a
     * barrel says where to go instead of only where not to (OP-3's speaking rule).
     */
    private fun flatFacesNote(
        solid: Element,
        feature: Feature3,
    ): String {
        val flat =
            (0 until Section3.faceAddressCount(feature)).mapNotNull { p ->
                Section3.facePatchOfFootprintPiece(feature, p).first?.let { p to it }
            }
        if (flat.isEmpty()) return " ${nameOf(solid)} has no flat face at all — put a datum plane where you want to sketch."
        return " The flat faces of ${nameOf(solid)} are ${flat.joinToString(", ") { it.second.name.label }}."
    }

    /**
     * What face address [piece] of [solid] **is**, in the words [FaceName] gives it — or null where the body
     * has no named faces.
     *
     * The one place a face is spoken of outside a refusal: a 3D click has no footprint edge on screen for the
     * user to read the answer off, so the status line says which face it opened (edit-in-3D slice 2).
     */
    fun faceLabel(
        solid: Element,
        piece: Int,
        ev: Evaluator = Evaluator(),
    ): String? {
        val feature = (ev.valueOf(solid.ref) as? SolidValue)?.solid?.feature ?: return null
        return Section3.facePatchOfFootprintPiece(feature, piece).first?.name?.label
    }

    /**
     * Why boundary piece [piece] of [solid] cannot carry a sketch, or null when it can.
     *
     * Asked through [Section3.facePatchOfFootprintPiece], so the answer is a *face*'s and not a prism's: a
     * flat face of a **loft** (every face of a polygon→apex pyramid) carries a sketch at the same stored
     * address, and a ruled one refuses with the plane that does work named in the message.
     */
    fun faceRefusal(
        solid: Element,
        piece: Int,
        ev: Evaluator = Evaluator(),
    ): String? {
        val feature = (ev.valueOf(solid.ref) as? SolidValue)?.solid?.feature ?: return "${nameOf(solid)} has no solid to take a face from"
        return Section3.facePatchOfFootprintPiece(feature, piece).second
    }

    /**
     * Create a sketch space on boundary piece [piece] of the solid [solid] (OP-17), and make it active.
     *
     * The plane is **derived**: `sideFacePlane` recomputes from the solid's own feature, so the frame is
     * parametric — stretch the part and the face, its sketch and everything built from it follow. That is
     * face-**relative** positioning, and it is the honest intent here: a hole is dimensioned from the part's
     * own edge, where a rider on a wall wants a world coordinate (OP-20's absolute rule).
     *
     * **The frame is intrinsic** (session 32, the user's rule): the picked segment lies on the x axis about
     * its own midpoint, `v` runs into the face's interior as seen from that segment, and the normal points
     * out of the material — at the viewer. Nothing is flipped here any more, and nothing needs a persisted
     * sign, because a face is locally on exactly one side of its own boundary edge. Which way an operation
     * builds is the operation's business and reads off that normal: *Extrude* follows it out of the material
     * as a boss ([extrudeSolid]), *Cut* goes the other way and drills ([cutOnFace]) — the same sentence that
     * already held for a datum plane, now holding for a face too.
     *
     * The plane the space *publishes* is that frame with its origin under the space's own control
     * ([setSpaceOrigin]): an anchor point and an in-plane (dx, dy), both zero to begin with.
     */
    @Suppress("UNCHECKED_CAST")
    fun createFaceSpace(
        solid: Element,
        piece: Int,
        named: String? = null,
    ): SketchSpace? {
        if (solid.kind != ElementKind.SOLID) return null
        if (faceRefusal(solid, piece) != null) return null
        val name = named ?: nextSpaceName()
        if (spaceNamed(name) != null) return null
        return recording("sketchspace", Arg.Label(name), Arg.Keyed("el", Arg.El(solid)), Arg.Keyed("piece", Arg.Text(piece.toString()))) {
            val intrinsic = cx.sideFacePlane(solid.ref as SolidRef, piece)
            val space = addSpace(SketchSpace(name, null, solid, piece), intrinsic)
            space
        }
    }

    /**
     * Register [proto] as a space whose plane is [intrinsic] under this document's **origin control** — the
     * one place a non-plan space is built, so face and datum get the same two layers (OP-17).
     *
     * The three nodes are ordinary sources, which is exactly what makes the origin re-pointable in place: the
     * anchor is a free point at the frame's own origin (welded onto a point on the plane when one is chosen)
     * and the offsets are free scalars (wired to panel parameters when they are typed). Every consumer of the
     * space holds the *published* plane, so moving the origin translates the whole sketch and everything
     * built from it, rather than rewiring anything.
     */
    private fun addSpace(
        proto: SketchSpace,
        intrinsic: PlaneRef,
    ): SketchSpace {
        val anchor = cx.freePoint("${proto.name}.origin", 0.0.mm, 0.0.mm)
        val dx = cx.const(0.0.mm)
        val dy = cx.const(0.0.mm)
        val space =
            SketchSpace(
                proto.name,
                cx.planeAnchored(intrinsic, anchor, dx, dy),
                proto.anchor,
                proto.piece,
                proto.hinge,
                proto.angle,
                proto.offset,
                proto.from,
                intrinsic,
                anchor,
                dx,
                dy,
                proto.parallel,
                proto.station,
                proto.along,
                proto.wire,
            )
        // where "created before" is decided (GitHub #9): every element already born is this plane's ancestor,
        // and nothing born after it ever becomes one — see [spaceAncestors]
        space.bornAt = counter
        spaces.add(space)
        activeSpace = space
        return space
    }

    private fun nextSpaceName(): String {
        var i = spaceCounter + 1
        while (spaceNamed("face$i") != null) i++
        spaceCounter = i
        return "face$i"
    }

    private fun nextDatumName(): String {
        var i = datumCounter + 1
        while (spaceNamed("plane$i") != null) i++
        datumCounter = i
        return "plane$i"
    }

    /** A station's own series (OP-26, step 4) — `station1`, `station2`: what it is, said in its name. */
    private fun nextStationName(): String {
        var i = stationCounter + 1
        while (spaceNamed("station$i") != null) i++
        stationCounter = i
        return "station$i"
    }

    /**
     * Create a **datum sketch space** on the line element [line], rotated [angle] out of the active space,
     * and make it active (OP-17's datum extension, GitHub #6 — *"any line in the base sketch can be used,
     * and any angle"*).
     *
     * The general case of which sketch-on-face is the special one (a boundary segment at 90°, [createFaceSpace])
     * and *Section* is the parallel one (an offset, no hinge). Everything here is a node, so nothing is
     * captured: the plane contains the line's **carrier** (a segment or an ortho leg counts as the line it
     * determines, exactly as every other line slot does), and the **angle is a live parameter** — retype it
     * and the plane tilts, taking every feature sketched on it along.
     *
     * The conventions, all three of them stated once:
     *
     * - **The frame.** `u` runs along the line, `v` rises out of the base plane as the angle grows, and the
     *   whole frame is the base space's rotated about the line by the right-hand rule — see
     *   [Geom3.datumPlane]. At 0° the datum *is* the space it came from; at 90° it stands upright on the line.
     * - **The origin is absolute.** It is the carrier's point nearest the base space's own origin (OP-20's
     *   anchoring rule), *not* the picked segment's start: stretching the host must not slide the datum's
     *   coordinates along it, and only the carrier's foot has that property. This is deliberately the
     *   opposite of a face space, whose frame is face-**relative** because a hole is dimensioned from the
     *   part's own edge — the same distinction OP-20 and OP-17 already draw between carrying a thing and
     *   being what it is measured from.
     * - **Which way a feature builds.** A face plane points into the material, so *Cut* goes in and
     *   *Extrude* goes out. A datum has **no material side**, so the rule is stated on the datum's own
     *   normal instead: **Extrude follows +normal, Cut follows −normal**, and the normal's sign is fixed by
     *   the right-hand rule about the line — so **the sign of the angle flips both**, deliberately and
     *   visibly (it is in the tool's help, the space's note and the status line).
     *
     * [part] is the solid a *Cut* here subtracts from. Resolved once, at creation, as the newest visible
     * solid the hinge is part of the construction of — so a datum on a footprint edge cuts the part that
     * footprint made, which is exactly what sketch-on-face does — and then **recorded in the step** (OP-18:
     * a choice is persisted at creation, never re-scored on replay), which is why a replay passes it back
     * rather than re-deriving it. Null when the hinge belongs to no solid: then this is a free-standing
     * sketch plane, *Extrude* works and *Cut* declines with a reason.
     */
    fun createDatumSpace(
        line: Element,
        angle: ScalarRef?,
        named: String? = null,
        part: Element? = null,
        offset: ScalarRef? = null,
    ): SketchSpace? {
        if (!line.isLinear) return null
        val name = named ?: nextDatumName()
        if (spaceNamed(name) != null) return null
        val base = activeSpace
        // the angle is a panel parameter either way: the tool's typed one, or the 90° its slot defaults to
        val entry = angle?.let { scalarEntryFor(it) } ?: newParameter("angle", Quantity.deg(90.0))
        // ...and the offset only exists when it was asked for: a datum through its hinge is the ordinary case
        // and must keep writing exactly the step it always wrote (OP-18)
        val shift = offset?.takeIf { evalMm(it) != 0.0 }?.let { scalarEntryFor(it) }
        // the part is the file's answer on a replay and the drawing's answer live — never re-derived on load
        val cuts = part ?: if (replayingVersion != null) null else datumPartOf(line)
        // the journal must be *in the base space* before this step, or a replay would rotate the datum out
        // of the wrong plane — the same lazy switch every other step gets ([noteSpace]), asked for early
        // because by the time this step is appended the new space is already the active one
        noteSpaceSwitch()
        return recording(
            "sketchspace",
            Arg.Label(name),
            Arg.Keyed("line", Arg.El(line)),
            Arg.Keyed("angle", Arg.Sc(entry)),
            *(if (shift == null) emptyArray() else arrayOf(Arg.Keyed("offset", Arg.Sc(shift)))),
            *(if (cuts == null) emptyArray() else arrayOf(Arg.Keyed("part", Arg.El(cuts)))),
        ) {
            val hinged = cx.datumPlane(activePlane(), carrierLine(line), entry.ref)
            val intrinsic = if (shift == null) hinged else cx.planeOffset(hinged, shift.ref)
            addSpace(SketchSpace(name, null, cuts, hinge = line, angle = entry, offset = shift, from = base.name), intrinsic)
        }
    }

    /**
     * Create a sketch space **parallel to the active one, [height] along its normal** — *Plane at height*
     * (GitHub #9, the user's design: *"the section tool basically creates a plane parallel to the base plane
     * with a given distance; to create such plane, no solid selection is necessary"*).
     *
     * The degenerate case the hinge-and-angle form could not state: a datum at 0° *is* the space it came
     * from, so a plane 60 mm over the plan needs a number and nothing else — no line to hinge on, no solid to
     * pick. Its input geometry arrives on its own, because a working plane's context is the section of every
     * solid created before it ([spaceAncestors]) rather than of one picked part.
     *
     * The height is a live parameter, like a datum's angle: retype it and the plane slides, taking every
     * feature sketched on it along. [part] is the solid a *Cut* here subtracts from — resolved once, at
     * creation, as the newest visible ancestor this plane actually cuts, and then **recorded in the step**
     * (OP-18: a choice is persisted at creation, never re-scored on replay), which is why a replay passes it
     * back rather than re-deriving it. Null when the plane cuts nothing: then this is a free-standing sketch
     * plane, *Extrude* works and *Cut* declines with a reason.
     */
    fun createParallelSpace(
        height: ScalarRef,
        named: String? = null,
        part: Element? = null,
    ): SketchSpace? {
        val name = named ?: nextDatumName()
        if (spaceNamed(name) != null) return null
        val base = activeSpace
        val entry = scalarEntryFor(height)
        val cuts = part ?: if (replayingVersion != null) null else parallelPartAt(evalMm(height))
        // the journal must be *in the base space* before this step, exactly as a datum's is ([noteSpaceSwitch])
        noteSpaceSwitch()
        return recording(
            "sketchspace",
            Arg.Label(name),
            Arg.Keyed("offset", Arg.Sc(entry)),
            *(if (cuts == null) emptyArray() else arrayOf(Arg.Keyed("part", Arg.El(cuts)))),
        ) {
            val intrinsic = cx.planeOffset(activePlane(), entry.ref)
            addSpace(SketchSpace(name, null, cuts, offset = entry, from = base.name, parallel = true), intrinsic)
        }
    }

    /**
     * Create a **station** across the curve in space [curve], [distance] along it, and make it active
     * (OP-26's step 4 — *"a station is a stated position along a path together with the plane the path
     * pierces there"*).
     *
     * The third kind of plane that is a face of nothing, and it is the general form of the one thing the
     * other two cannot say: *where along a run*. Origin on the path, normal along the tangent, in-plane axes
     * the moving frame's ([Construction.stationPlane]). What comes out is a **sketch space** and nothing more
     * exotic than that, which is the whole design: draw in it, dimension in it, extrude a fitting off it, cut
     * a mitre with it, place a group into it — every one of those already works on a datum plane and works
     * here for the same reason and through the same code.
     *
     * **The position is one length measured from the start of the run**, an ordinary parameter. So a station
     * *relative* to another costs nothing — `base + d` in the expression language (OP-7) — and two stations
     * sharing a pitch is one parameter node feeding both. Normalized *t* is deliberately not offered.
     *
     * **Out of range is invalidity, not a refusal**, and this is OP-26's own doctrinal point: the distance is
     * a live value, so a station past the end of the run makes the plane's node invalid with a named reason,
     * everything sketched on it hides while it is, and retyping the number brings it all back. What *is*
     * refused here, by name and building nothing, is the one structural thing — a pick that is not a curve in
     * space.
     *
     * [part] is the solid a *Cut* here subtracts from, resolved once at creation as the newest visible solid
     * this plane passes through and then **recorded in the step** (OP-18), which is the parallel plane's rule
     * unchanged: a choice is persisted at creation and never re-scored on replay.
     */
    @Suppress("UNCHECKED_CAST")
    fun createStationSpace(
        curve: Element,
        distance: ScalarRef,
        named: String? = null,
        part: Element? = null,
    ): SketchSpace? {
        val runRef = spaceCurveRef(curve, "Station") ?: return null
        val name = named ?: nextStationName()
        if (spaceNamed(name) != null) return null
        val base = activeSpace
        val entry = scalarEntryFor(distance)
        val cuts = part ?: if (replayingVersion != null) null else stationPartAt(runRef, curve.space, evalMm(distance))
        // the journal must name the base space *before* this step, exactly as a datum's does
        // ([noteSpaceSwitch]) — by the time the step is appended the station is already the active space
        noteSpaceSwitch()
        return recording(
            "sketchspace",
            Arg.Label(name),
            Arg.Keyed("path", Arg.El(curve)),
            Arg.Keyed("at", Arg.Sc(entry)),
            *(if (cuts == null) emptyArray() else arrayOf(Arg.Keyed("part", Arg.El(cuts)))),
        ) {
            val intrinsic = cx.stationPlane(runRef, planeOfSpace(curve.space), entry.ref)
            addSpace(SketchSpace(name, null, cuts, from = base.name, station = curve, along = entry), intrinsic)
        }
    }

    /**
     * The solid a *Cut* on a station of [curve] at [mm] subtracts from — [parallelPartAt]'s question, asked
     * about a plane that stands across a run instead of over a space.
     *
     * Computed from **values** rather than through a node, for [parallelPartAt]'s own reason: the station's
     * plane node does not exist yet at this point, and a throwaway one would put the live graph and the
     * replayed graph out of step. Which means the space's plane has to be read without building one either —
     * the plan *is* the world XY frame by construction, exactly as [activePlane3] says.
     */
    private fun stationPartAt(
        run: Path3Ref,
        space: String,
        mm: Double,
        ev: Evaluator = Evaluator(),
    ): Element? {
        val path = (ev.valueOf(run) as? Path3Value)?.path ?: return null
        val up = planeValueOfSpace(space, ev) ?: return null
        val station = Stations3.at(path, up.normal.normalized(), mm).first ?: return null
        return partCutBy(station.plane, ev)
    }

    /**
     * The frame of the space named [name], as a value and **without building a node** — the world XY plane
     * for the plan, which is what it is by construction, and null when a stored plane does not evaluate.
     */
    private fun planeValueOfSpace(
        name: String,
        ev: Evaluator,
    ): Plane3? {
        val ref = spaceNamed(name)?.plane ?: return Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)
        return ((ev.eval(ref.node) as? EvalResult.Ok)?.value as? PlaneValue)?.plane
    }

    /**
     * The solid a *Cut* on a plane [mm] over the active space subtracts from: the **newest visible solid the
     * plane actually cuts**, or null (GitHub #9).
     *
     * The parallel plane's answer to [datumPartOf]'s question, and it has to be a different one: there is no
     * hinge line whose construction names a part, so what names it is the geometry — which solid this plane
     * passes through. Asked once, at creation, and recorded; nothing is re-derived on load. Computed from
     * *values* rather than through a node, deliberately: the plane does not exist yet at this point, and a
     * throwaway node would put the live and the replayed graph out of step.
     */
    private fun parallelPartAt(
        mm: Double,
        ev: Evaluator = Evaluator(),
    ): Element? {
        val base = activePlane3(ev) ?: return null
        return partCutBy(base.translated(mm), ev)
    }

    /**
     * The newest visible solid the frame [plane] passes through, or null — the shared half of the question
     * [parallelPartAt] and [stationPartAt] both ask, and they ask it identically because a plane that is a
     * face of nothing names its part by the same fact either way: which body it cuts.
     */
    private fun partCutBy(
        plane: Plane3,
        ev: Evaluator,
    ): Element? =
        elements.lastOrNull { el ->
            el.kind == ElementKind.SOLID && el.visible &&
                (ev.valueOf(el.ref) as? SolidValue)?.let { !Section3.sectionOf(it.solid, plane).isEmpty } == true
        }

    /**
     * The solid a datum's features cut into: the **newest visible solid the line [line] is part of the
     * construction of**, or null (GitHub #6).
     *
     * Ancestry, not material — the opposite of [facePartTip]'s test, and for a reason: a face space *names*
     * its solid, while a datum names a line, and the only honest way from a line to "the part this line
     * belongs to" is the construction that used it (a footprint edge → the area → the extrude). Asked once,
     * at the moment the space is created, when the datum's own plane node does not exist yet — so the tool
     * solid a *Cut* is about to build cannot be mistaken for the part it is cutting. From there the ordinary
     * sequential-feature rule takes over: [facePartTip] chains onto whatever this resolves to.
     */
    fun datumPartOf(
        line: Element,
        ev: Evaluator = Evaluator(),
    ): Element? {
        val target = line.ref.node
        return elements.lastOrNull { el ->
            el.kind == ElementKind.SOLID && el.visible && (ev.valueOf(el.ref) is SolidValue) && descendsFrom(el.ref.node, target)
        }
    }

    /** Whether [target] is an ancestor of [node] — a plain input walk, every kind of input counting. */
    private fun descendsFrom(
        node: Node,
        target: Node,
    ): Boolean {
        val seen = HashSet<String>()

        fun walk(n: Node): Boolean {
            if (!seen.add(n.id)) return false
            for (i in n.inputs) if (i === target || walk(i)) return true
            return false
        }
        return walk(node)
    }

    /**
     * How a space names itself in the toolbar's list — the document's answer, because which spaces exist and
     * what they are *of* is a fact about the model and not about the DOM (the same discipline [listedIn]
     * follows). The shell renders whatever this returns.
     */
    fun spaceLabel(space: SketchSpace): String =
        when {
            space.isPlan -> "plan"
            // a station (OP-26, step 4): the one number it is, and the run it is measured along
            space.isStation ->
                "${space.name} (${Format.num(spaceAlongMm(space))} mm along ${space.station?.let { nameOf(it) }})"
            // the plane of an imported wireframe (OP-26, step 9): the run *is* the whole description
            space.isWire -> "${space.name} (plane of ${space.wire?.let { displayName(it) }})"
            // the hinge-less parallel case (GitHub #9): a height, and the space it is a height above
            space.parallel -> "${space.name} (${Format.num(spaceOffsetMm(space))} mm from ${space.from})"
            space.isDatum ->
                "${space.name} (${Format.num(spaceAngleDeg(space))}° on ${space.hinge?.let { nameOf(it) }}" +
                    (space.offset?.let { ", ${Format.num(evalMm(it.ref))} mm off" } ?: "") +
                    // …and which way it fronts, because a label that says everything about *where* a plane is
                    // and nothing about which way it faces describes only half of what a feature built on it
                    // will do ([spaceFacing])
                    facingSuffix(space) +
                    (if (space.from == PLAN_SPACE) ")" else ", from ${space.from})")
            else -> "${space.name} (face of ${space.anchor?.let { nameOf(it) }})"
        }

    /** A datum space's offset along its own normal in mm, as it stands (0 when it has none). */
    fun spaceOffsetMm(space: SketchSpace): Double = space.offset?.let { evalMm(it.ref) } ?: 0.0

    /** A station's distance along its run in mm, as it stands (0 for any other space) — OP-26, step 4. */
    fun spaceAlongMm(space: SketchSpace): Double = space.along?.let { evalMm(it.ref) } ?: 0.0

    /**
     * How long a station's run is, in mm — what a station's note measures its distance against, and what its
     * refusal names when the number walks off the end. Zero when the curve does not evaluate.
     */
    fun stationRunMm(
        space: SketchSpace,
        ev: Evaluator = Evaluator(),
    ): Double {
        val curve = space.station ?: return 0.0
        return (ev.valueOf(curve.ref) as? Path3Value)?.let { Curves3.length(it.path) } ?: 0.0
    }

    /** The facing clause a datum's label carries — short, because a label is a list entry ([spaceFacing]). */
    private fun facingSuffix(space: SketchSpace): String {
        val facing = spaceFacing(space) ?: return ""
        val bearing = facing.bearingDeg ?: return if (facing.outward) ", front with ${space.from}" else ", front against ${space.from}"
        return ", front toward ${Format.num(bearing)}°"
    }

    /**
     * Which way a **datum's front** turns, or null for a space this question adds nothing to.
     *
     * The front is the side a positive *Extrude* or *Revolve* builds toward (`Geom3.datumPlane`:
     * `normal = u × v`), and until session 64 it was **invisible state deciding a visible outcome** — the
     * user's revolve swept away from the line it was meant to meet because their upright plane fronted the
     * other way, and nothing in the label, the note or the canvas said so.
     *
     * **Stated as a bearing in the base space, and that is the decision.** The obvious spelling — "right of
     * the hinge as the hinge is drawn" — is exactly true (with `w = n × u` the normal is `n·cos θ − w·sin θ`,
     * and `w` is `u` turned a quarter turn counter-clockwise, so a positive angle fronts to the right of the
     * drawn direction) and yet says **nothing the label did not already say**: it is a restatement of the
     * sign of θ, because *which way the hinge is drawn* is itself invisible. The bearing is the piece of
     * information that is actually missing — it differs between the two drawn directions of one line — and it
     * is checkable against what the canvas shows, since a 2D view draws its space with +x to the right and
     * +y up ([Camera.worldToScreen]) and every other angle in the drawing is measured from that +x too.
     *
     * Read off the **plane node itself** rather than recomputed from the hinge and the angle, so the words
     * cannot drift from the geometry the features are built on: it is the same value `Extrude` sweeps along.
     */
    fun spaceFacing(
        space: SketchSpace,
        ev: Evaluator = Evaluator(),
    ): Facing? {
        if (!space.isDatum) return null
        val plane = planeOf(space, ev) ?: return null
        val base = spaceNamed(space.from)?.let { planeOf(it, ev) } ?: return null
        val n = plane.normal
        val du = n.dot(base.u)
        val dv = n.dot(base.v)
        val outward = n.dot(base.normal) >= 0.0
        if (Vec2(du, dv).length() <= FACING_EPS) return Facing(null, outward)
        val deg = atan2(dv, du) * 180.0 / PI
        return Facing((deg % 360.0 + 360.0) % 360.0, outward)
    }

    /** The 3D plane of [space] as it stands, or null when it does not evaluate (OP-3) — the plan's is world XY. */
    fun planeOf(
        space: SketchSpace,
        ev: Evaluator,
    ): Plane3? {
        val ref = space.plane ?: return Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)
        return ((ev.eval(ref.node) as? EvalResult.Ok)?.value as? PlaneValue)?.plane
    }

    /** A datum space's angle in degrees, as it stands (0 for any other space). */
    fun spaceAngleDeg(space: SketchSpace): Double =
        space.angle?.let { e ->
            ((Evaluator().eval(e.ref.node) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.let {
                if (it.dim == Dimension.ANGLE) it.deg else 0.0
            }
        } ?: 0.0

    /**
     * Switch the active space (view state — see [activeSpace]); false when there is no such space.
     *
     * [record] is what a **replay** passes: the file already contains the switch, so the step is put back
     * where it was instead of being re-derived by [noteSpace] — which is what makes `save → load → save`
     * byte-equal even for a switch that nothing follows (a delete can leave one).
     */
    fun switchSpace(
        name: String,
        record: Boolean = false,
    ): Boolean {
        val space = spaceNamed(name) ?: return false
        activeSpace = space
        if (record) {
            journal.add(Step("space", listOf(Arg.Label(name))))
            scriptSpace = space
        }
        return true
    }

    /**
     * Write a `space` step when the journal's space is not the active one — **lazily**, just before the
     * step that needs it, so switching views back and forth records nothing at all and only a step that is
     * actually *built* somewhere says where.
     *
     * The ordering rule is the ortho path's "current path" precedent exactly (`DocumentFormat.currentPath`):
     * steps belong to the last space named, and creating a face space names it too. That is what keeps the
     * file free of an addressing scheme for spaces.
     */
    private fun noteSpace(kind: String) {
        if (kind == "sketchspace") {
            scriptSpace = activeSpace
            return
        }
        noteSpaceSwitch()
    }

    /**
     * The lazy switch itself: a `space` step when the journal is not where the user is. Called by
     * [noteSpace] for an ordinary step, and *directly* by [createDatumSpace] — which needs the switch
     * written **before** its own step, since a datum is rotated out of the space it was defined in and by
     * the time the step is appended the new space is already the active one.
     */
    private fun noteSpaceSwitch() {
        if (activeSpace === scriptSpace) return
        journal.add(Step("space", listOf(Arg.Label(activeSpace.name))))
        scriptSpace = activeSpace
    }

    // ---- names: one authority, and it is the file's (OP-18) ----

    /**
     * **The** name of [el], everywhere a user can read one: its **script-local** name (`e1`, `e2`, …) —
     * derived from the journal exactly as [DocumentFormat.save] derives it, because it *is* what the save
     * writes.
     *
     * There used to be two numbering schemes for one thing. The panel, the status line and every dialog
     * showed the runtime [Element.id], which counts *everything* the document ever created (parameters,
     * measurements, frames, local coordinates — [nextId] is one counter), while the file numbers only the
     * elements the journal declares, from 1, gapless. Same `eN` shape, different numbers, so a drawing whose
     * file said `e17` had the user looking at `e21` — reported as a defect, and it garbled every conversation
     * *about* a drawing, which is the point of a name.
     *
     * The runtime id stays exactly what it was: an internal, stable key (the DOM rows, the per-element maps,
     * the undo snapshots all address elements by it). What changed is that nothing *shows* it.
     *
     * The map is cached and recomputed when the journal changes ([namesStamp]). An append is stable — a new
     * step names only new elements — and a delete replays the whole script into a fresh document anyway, so
     * names are stable in exactly the cases the user can observe.
     */
    fun nameOf(el: Element): String {
        val names = scriptNames()
        names[el.id]?.let { return it }
        // An element the operation *now recording* created already has its name determined: the journal grows
        // at the end, and its step will declare what it created in creation order — so the note an operation
        // writes about what it just built can name it, instead of falling back to the internal id.
        pendingBefore?.let { before ->
            val i = elements.filter { it !in before }.indexOfFirst { it === el }
            if (i >= 0) return "e${names.size + 1 + i}"
        }
        return el.id
    }

    /** The elements that existed when the operation now recording began — see [nameOf] and [recording]. */
    private var pendingBefore: Set<Element>? = null

    private var nameCache: Map<String, String>? = null
    private var namesStamp: Int = -1

    /**
     * Every element's script-local name, by runtime id — the *whole* map, since a panel asks for all of them.
     *
     * A retired element (a vertex a join coalesced) keeps its name: replay must still create it before the
     * later step removes it again, so it still occupies a number in the file. That is the one place where
     * "the name the file gives it" and "the name of something you can see" differ, and the file wins, because
     * the point of the name is to be the same on both sides.
     */
    private fun scriptNames(): Map<String, String> {
        var stamp = journal.size
        for (s in journal) stamp = stamp * 31 + s.creates.size
        nameCache?.let { if (stamp == namesStamp) return it }
        val names = HashMap<String, String>()
        for (step in journal) for (el in step.creates) names[el.id] = "e${names.size + 1}"
        nameCache = names
        namesStamp = stamp
        return names
    }

    // ---- a user-facing name for an element (OP-7's "nodes get names", recorded as its own step) ----

    /**
     * The names the user has given elements, by runtime id. Rebuilt by replay like everything else, because
     * a name is a **recorded step** (`name e7 "bore-axis"`) and not a field on the element: it is a decision
     * about the drawing, exactly as a hide is (OP-18's visibility reversal), so it belongs in the file.
     */
    private val elementNames = HashMap<String, String>()

    /** What the user calls [el], or null — [nameOf] stays the identity everywhere. */
    fun userNameOf(el: Element): String? = elementNames[el.id]

    /**
     * How [el] is written where a human reads it: `bore-axis (e7)` when it has been named, `e7` otherwise.
     *
     * The script name is never dropped from the display, and that is the point. It is the drawing's one
     * identity (see [nameOf]) — what the file says, what a refusal quotes, what two people say to each other
     * about a drawing — while a user name is a *label on top of it*. A panel that showed only "bore-axis"
     * would recreate exactly the two-numbering-schemes defect the naming authority was introduced to end.
     */
    fun displayName(el: Element): String = elementNames[el.id]?.let { "$it (${nameOf(el)})" } ?: nameOf(el)

    /**
     * Whether [el] can carry a name — **exactly when the file names it**, the same rule as a parameter's
     * ([canRenameParameter]): a `name` step refers to the element by its script name, so an element no step
     * declares could only be given a name the save would have to drop or, worse, write a reference to
     * nothing.
     */
    fun canNameElement(el: Element): Boolean = creatingStep(el) != null

    /**
     * Name [el] (blank clears the name), uniquified among element names exactly as a parameter's is. Returns
     * the name it actually **took** — `""` when it was cleared — or null when [el] cannot carry one.
     *
     * One step per named element, created on the first naming and **restated** at save from then on
     * ([DocumentFormat.restate]), which is the parameter-rename pattern one level up: the name is state, so
     * the writer re-reads it rather than the journal remembering what was typed first. Clearing drops the
     * step outright — there is nothing left for it to say — and deleting the element drops it through the
     * ordinary reference rule ([dependentSteps]), since the step names the element as an argument.
     */
    fun nameElement(
        el: Element,
        name: String,
    ): String? {
        if (!canNameElement(el)) return null
        val existing = journal.firstOrNull { s -> s.kind == "name" && s.args.any { a -> a is Arg.El && a.el === el } }
        val wanted = scalarWord(name)
        if (wanted.isEmpty()) {
            // **a name an expression spells cannot be taken away.** `P.x` reads the point through its name
            // (OP-7's naming authority, the session-76 entry), so clearing it would leave the formula live and
            // its *file* unloadable — the scalar half's own probe lesson. Refused by name, with the cure, which
            // is the same answer a rename that would capture a curve's parameter gets.
            expressionsReading(el).takeIf { it.isNotEmpty() }?.let { reading ->
                note = "Can't take ${nameOf(el)}'s name away: ${reading.joinToString(", ")} reads it as " +
                    "'${userNameOf(el)}.x' or '.y' — change those formulas first, or rename it instead"
                return null
            }
            elementNames.remove(el.id)
            existing?.let { s -> journal.removeAll { it === s } }
            return ""
        }
        val took = uniqueElementName(wanted, except = el)
        elementNames[el.id] = took
        if (existing == null) recording("name", Arg.El(el), Arg.Label(took)) { }
        // a coordinate an expression reads is a mention like any other, so it is re-stamped rather than
        // orphaned — the parameter rename's own rule ([restampExpressions])
        restampExpressions()
        return took
    }

    /** What reads a coordinate of [el] in a formula — a parameter by name, a curve by its own name. */
    private fun expressionsReading(el: Element): List<String> {
        val out = ArrayList<String>()
        for (b in exprBindings.values) if (b.refs.any { it.point === el }) out.add(b.entry.name)
        for (c in funcCurves.values) if (c.pointRefs().any { it === el }) out.add(nameOf(c.element))
        for (l in sweepLaws.values) if (l.pointRefs().any { it === el }) out.add(nameOf(l.element))
        return out.distinct()
    }

    // ---- appearance, Tier 1: a material per solid (one panel row, one recorded step) ----

    /**
     * The materials the user has assigned, by runtime id — the same shape as [elementNames] and for the same
     * reason: an appearance is a **decision about the drawing**, so it is a recorded step and not a field on
     * the element, and it is rebuilt by replay like everything else.
     */
    private val elementMaterials = HashMap<String, Appearance>()

    /**
     * What [el] is made to look like — the assigned material, or [Appearance.DEFAULT] when none was assigned.
     *
     * There is no "unset" answer, deliberately: both consumers (the GLB writer and the preview) need five
     * numbers per solid, and a solid nobody has dressed still has to look like an object. [assignedMaterial]
     * is the question the *panel* asks, since a row must know whether it is showing a default or a choice.
     */
    fun materialOf(el: Element): Appearance = elementMaterials[el.id] ?: Appearance.DEFAULT

    /** The material the user actually assigned to [el], or null while it is wearing the default. */
    fun assignedMaterial(el: Element): Appearance? = elementMaterials[el.id]

    /**
     * Whether [el] can carry a material: a **solid** the file names ([canNameElement]'s rule).
     *
     * Solids only, because Tier 1 is *a material per solid* — that is what a PBR viewer renders and what the
     * two consumers ask for. A material on a construction line would be a value with no consumer, which is
     * the definition of a setting nobody asked for.
     */
    fun canSetMaterial(el: Element): Boolean = el.kind == ElementKind.SOLID && creatingStep(el) != null

    /**
     * Give [el] a material (null clears it, back to the default). Returns what it now wears, or null when
     * [el] cannot carry one.
     *
     * One step per dressed solid, created on the first assignment and **restated** at save from then on
     * ([DocumentFormat.restate]) — the element-rename pattern verbatim, because a material is state in
     * exactly the same way a name is: the writer re-reads it rather than the journal remembering the first
     * value typed. Clearing drops the step outright, and deleting the solid drops it through the ordinary
     * reference rule ([dependentSteps]), since the step names the element as an argument.
     */
    fun setMaterial(
        el: Element,
        material: Appearance?,
    ): Appearance? {
        if (!canSetMaterial(el)) return null
        val existing = journal.firstOrNull { s -> s.kind == "material" && s.args.any { a -> a is Arg.El && a.el === el } }
        if (material == null) {
            elementMaterials.remove(el.id)
            existing?.let { s -> journal.removeAll { it === s } }
            return Appearance.DEFAULT
        }
        val took =
            Appearance(
                color = Appearance.parseHex(material.color)?.let { Appearance.hexOf(it) } ?: Appearance.DEFAULT_COLOR,
                roughness = material.roughnessClamped,
                metallic = material.metallicClamped,
            )
        elementMaterials[el.id] = took
        if (existing == null) {
            recording(
                "material",
                Arg.El(el),
                Arg.Keyed("color", Arg.Text(took.color)),
                Arg.Keyed("rough", Arg.Num(Quantity.number(took.roughness))),
                Arg.Keyed("metal", Arg.Num(Quantity.number(took.metallic))),
            ) { }
        }
        return took
    }

    private fun uniqueElementName(
        base: String,
        except: Element,
    ): String {
        fun taken(n: String) = elementNames.any { (id, v) -> id != except.id && v == n }
        if (!taken(base)) return base
        var i = 2
        while (taken("$base$i")) i++
        return "$base$i"
    }

    // ---- delete: the unit of removal is the journal step (OP-18) ----

    /** The journal step that created [el], if any — what a delete of [el] removes. */
    fun creatingStep(el: Element): Step? = journal.firstOrNull { s -> s.creates.any { it === el } }

    /**
     * **The elements no step created** — the breach of OP-27's invariant, and normally empty.
     *
     * The invariant is that every element a document holds was created by exactly one journal step, because
     * the journal *is* the model (see *Why the format records operations* in DESIGN.md): an element with no
     * step is written to no file, reached by no delete, and lost by the next undo — it exists on screen and
     * nowhere else. This is the query the enforcement is stated in: [Editor.transacted] refuses any gesture
     * that would produce one, and [DocumentFormat.saveFile] refuses to write a document that already has
     * one rather than dropping it silently.
     */
    fun steplessElements(): List<Element> = elements.filter { creatingStep(it) == null }

    /** Elements a step's arguments reference, keyed wrappers included. */
    internal fun referencedElements(step: Step): List<Element> {
        val out = ArrayList<Element>()

        fun walk(a: Arg) {
            when (a) {
                is Arg.El -> out.add(a.el)
                is Arg.Els -> out.addAll(a.els)
                // a replicated gesture's picks (OP-23): the orbit's member 0, so the cascade reaches the
                // gesture through the very element that names its rule
                is Arg.Member -> out.add(a.el)
                is Arg.Refs -> a.items.forEach { walk(it) }
                is Arg.Keyed -> walk(a.value)
                else -> {}
            }
        }
        step.args.forEach { walk(it) }
        // a **coordinate** an expression reads (`P.x` — the session-76 entry, item a) is a reference to the
        // point, living inside a text rather than in an argument of its own. So the step that stated it is
        // asked what it read, exactly as it is asked for the scalars ([referencedScalars]), and the delete
        // cascade reaches it like any other reference — which it must: a `bind` step left behind by a deleted
        // point would name a point on load and the file would not open (OP-18).
        exprBindings[step]?.let { b -> out.addAll(b.refs.mapNotNull { it.point }) }
        funcCurves[step]?.let { c -> out.addAll(c.pointRefs()) }
        sweepLaws[step]?.let { l -> out.addAll(l.pointRefs()) }
        sweepFamilies[step]?.let { l -> out.addAll(l.pointRefs()) }
        // …and the curves a skin's stated correspondence names (session 78), which live in the writer's own
        // registry rather than in the step's original arguments once a *Match* has re-stamped them: a step
        // left behind by a deleted curve would name it on load and the file would not open (OP-18).
        skinMatches[step]?.let { out.addAll(it) }
        return out
    }

    /** Scalars a step's arguments reference, keyed wrappers included. */
    private fun referencedScalars(step: Step): List<ScalarEntry> {
        val out = ArrayList<ScalarEntry>()

        fun walk(a: Arg) {
            when (a) {
                is Arg.Sc -> out.add(a.entry)
                is Arg.Scs -> out.addAll(a.entries)
                is Arg.Refs -> a.items.forEach { walk(it) }
                is Arg.Keyed -> walk(a.value)
                else -> {}
            }
        }
        step.args.forEach { walk(it) }
        // an expression's references are inside its text, not in an argument of their own — so the step
        // that binds it is asked what it read, and the delete cascade reaches it like any other reference
        exprBindings[step]?.let { b -> out.addAll(b.refs.mapNotNull { it.entry }) }
        // the same for a function curve: its references live inside two texts (and, since session 76, in its
        // two domain ends) rather than in arguments of their own, so the step is asked what it read
        funcCurves[step]?.let { c -> out.addAll(c.scalarRefs()) }
        // the same for a swept body's size law, whose references live inside one text (OP-26, session 77)
        sweepLaws[step]?.let { l -> out.addAll(l.scalarRefs()) }
        // …and a **family's** laws, which reference scalars on both sides of their `=` (OP-26, session 79):
        // the names inside each text *and* the parameter each law drives. Both, because a deleted parameter
        // must take the body with it either way — a `laws=` naming a row that has gone would name it on load,
        // and the file would not open (OP-18).
        sweepFamilies[step]?.let { l -> out.addAll(l.scalarRefs()) }
        return out
    }

    /**
     * [root] plus every later step that (transitively) depends on something the dropped steps made —
     * what a delete must remove for the remaining journal to replay as a valid script.
     *
     * Three dependency kinds, checked in one forward walk:
     * - **explicit** — an argument references a dropped element or scalar;
     * - **path context** — the ortho steps address the "current path" without an element argument, so
     *   a path's topology steps chain: dropping one drops the rest of that path's steps. Per-vertex
     *   surgery is deliberately not attempted — replay coalesces a straight-on step into the previous
     *   leg and a wall's face count follows the leg count, so removing one topology step changes how
     *   many elements later steps create, which the loader rejects as a count mismatch.
     *
     * There used to be a third: an opening *regenerated* the wall's faces, and their count depended on
     * every opening already there, so dropping any wall or opening step forced dropping every later
     * opening step. With the thick path (OP-21) an interval creates no geometry, names the footprint it
     * belongs to as an argument, and is independent of its siblings — so the explicit rule covers it and
     * the special case is gone. Deleting one opening now leaves the others alone.
     */
    fun dependentSteps(root: Step): Set<Step> = dependentSteps(setOf(root))

    /**
     * The same closure for a *set* of roots — a bulk delete (OP-16). Deliberately not the union of the
     * per-root closures: whether a step survives can depend on what the others took. A `group` step
     * whose members are dropped by two different roots is exactly that case — each root alone leaves it
     * a member, together they leave it none.
     */
    fun dependentSteps(roots: Set<Step>): Set<Step> {
        // one drawn path; mirrors the loader's "current path" resolution so the chain matches replay
        class PathChain {
            var dropped = false
        }

        val dropped = LinkedHashSet<Step>()
        val droppedEls = HashSet<Element>()
        val droppedScalars = HashSet<ScalarEntry>()
        // a `place` step names its group, not elements, so it follows the group step rather than any
        // dependency of its own: a placement whose group is gone has nothing left to place (OP-16)
        val droppedGroups = HashSet<String>()
        // likewise a macro instance's `tool` step names its *definition* (OP-6), not the definition's
        // elements: if the `macrodef` step goes, no instance of it can replay, so they go with it
        val droppedMacros = HashSet<String>()
        // a sketch space whose face is gone (OP-17): its plane cannot be derived any more, so everything
        // drawn in it goes with it — and unlike a group's membership that is not a matter of degree, since
        // the space's own coordinates are what those elements' literals mean
        val droppedSpaces = HashSet<String>()
        // a pattern whose ring is gone (OP-23): every gesture that rides it names it, and none of them can
        // replay without the rule — so they go with it, exactly as a macro's instances go with its definition
        val droppedPatterns = HashSet<String>()

        fun labelOfStep(step: Step): String? = step.args.filterIsInstance<Arg.Label>().firstOrNull()?.s

        fun drop(
            step: Step,
            chain: PathChain?,
        ) {
            dropped.add(step)
            droppedEls.addAll(step.creates)
            droppedScalars.addAll(step.createsScalars)
            if (step.kind == "pattern") labelOfStep(step)?.let { droppedPatterns.add(it) }
            // a thick path can go without taking its carrier path down with it — the dependency runs the
            // other way. Every other step belonging to a path's chain takes that chain's future with it.
            if (step.kind != "wall") chain?.dropped = true
        }

        var current: PathChain? = null
        val chainOfEl = HashMap<Element, PathChain>()
        var seenRoot = false
        var currentSpace = PLAN_SPACE
        for (step in journal) {
            val els = referencedElements(step)
            // which space the script is in here — the same ordering rule the loader follows, so a delete
            // and a replay agree on what belongs where
            if (step.kind == "sketchspace" || step.kind == "space") currentSpace = labelOfStep(step) ?: currentSpace
            val stepSpace = currentSpace
            val chain: PathChain? =
                when (step.kind) {
                    "orthostart" -> PathChain().also { current = it }
                    "orthoresume" -> (els.firstNotNullOfOrNull { chainOfEl[it] } ?: current).also { current = it }
                    "orthojoin", "orthobreak" -> els.firstNotNullOfOrNull { chainOfEl[it] } ?: current
                    "orthovertex", "orthoprepend", "orthoclose", "orthodiscard", "wall" -> current
                    else -> null
                }
            if (chain != null) step.creates.forEach { chainOfEl[it] = chain }
            if (roots.any { it === step }) {
                drop(step, chain)
                seenRoot = true
                continue
            }
            if (!seenRoot) continue
            // a `group` step (OP-16) *names* its members but is not built from them, so losing some of
            // them must not take the group with it: it goes only once nothing is left to group. This is
            // the one place the member-deletion rule lives — [groups] hides an all-dead group live, and
            // [DocumentFormat] writes only the surviving members, so replay and delete agree.
            if (step.kind == "group") {
                if (els.isNotEmpty() && els.all { it in droppedEls }) {
                    drop(step, chain)
                    labelOfStep(step)?.let { droppedGroups.add(it) }
                }
                continue
            }
            if (step.kind == "place") {
                if (labelOfStep(step) in droppedGroups) drop(step, chain)
                continue
            }
            // a replicated gesture cannot outlive its pattern's rule (OP-23), and it follows its picks
            // through the ordinary reference rule otherwise
            if (step.kind == "orbit" && labelOfStep(step) in droppedPatterns) {
                drop(step, chain)
                continue
            }
            // a visibility step *names* elements without being built from them, exactly as a group does
            // (OP-18's reversal — see [setElementsVisible]): it survives losing some of them, with the
            // survivors written by [DocumentFormat], and goes only once none are left to hide or show
            if (step.kind == "hide" || step.kind == "show") {
                if (els.isNotEmpty() && els.all { it in droppedEls }) drop(step, chain)
                continue
            }
            // an instance of a macro whose definition is going cannot replay (the tool would be unknown).
            // The editor refuses such a delete outright, naming the instances — this rule is what keeps
            // the *script* consistent whatever route a delete takes.
            val toolId = (step.args.firstOrNull() as? Arg.Text)?.s
            if (step.kind == "tool" && toolId != null && toolId.startsWith(MACRO_TOOL_PREFIX) &&
                toolId.removePrefix(MACRO_TOOL_PREFIX) in droppedMacros
            ) {
                drop(step, chain)
                continue
            }
            val depends =
                chain?.dropped == true ||
                    els.any { it in droppedEls } ||
                    referencedScalars(step).any { it in droppedScalars } ||
                    // drawn in a space that is going: the space *is* what its coordinates mean (OP-17)
                    (step.kind != "sketchspace" && stepSpace in droppedSpaces)
            if (depends) drop(step, chain)
            // ...and a face space goes when the solid it is a face of does, taking its geometry with it
            if (depends && step.kind == "sketchspace") labelOfStep(step)?.let { droppedSpaces.add(it) }
            // a definition is all-or-nothing: losing one of its elements changes how many elements an
            // instance creates, which replay checks (OP-18), so the whole declaration goes
            if (depends && step.kind == "macrodef") labelOfStep(step)?.let { droppedMacros.add(it) }
            // ...and so is a pattern: its ring's member count is what every gesture riding it indexes into
            if (depends && step.kind == "pattern") labelOfStep(step)?.let { droppedPatterns.add(it) }
        }
        return dropped
    }

    private fun nextId(prefix: String) = "$prefix${++counter}"

    val freePoints: List<Element> get() = elements.filter { it.kind == ElementKind.POINT }

    private fun add(
        ref: Ref<*>,
        kind: ElementKind,
        style: Style,
    ): Element {
        val el = Element(nextId("e"), publishedRef(ref, kind), kind, style)
        // the one place an element is born, hence the one place its sketch space is stamped (OP-17)
        el.space = activeSpace.name
        // ...and the one place its birth is stamped (GitHub #9): what a plane's ancestors are measured by
        el.born = counter
        elements.add(el)
        return el
    }

    /**
     * How a **trimmable** curve publishes its geometry: behind a re-pointable view ([IndirectNode]), so a
     * fillet can supersede the leg with its trimmed self **in place** (GitHub #25, the user's design).
     *
     * The precedent is exact — OP-16's ortho vertex is `pointXY` behind a view so a placement can put a
     * frame in front of it without rewiring one consumer — and the reason is the same one, read for a curve:
     * *"the fillet tool only creates the fillet arc, but does not supersede [the corner] with its filleted
     * version"*. Superseding means the leg the drawing shows, traces, extrudes and thickens ends at the
     * tangency, while **everything already built on that leg keeps meaning that leg** (an outline, a
     * dimension, a wall, a rider): the node consumers hold is the view, and the view is what the trim binds.
     * Nothing is rewired, which is OP-5's rule and the whole reason a view exists rather than a new element.
     *
     * Only the two **bounded** kinds a fillet trims get one — a segment and an arc. A line, a ray and a
     * circle are *carriers*: they have no ends to move, so a rounding against one leaves them exactly as
     * they were (see [trimLegTo]), and giving them a view would be a node with nothing to say.
     */
    private fun publishedRef(
        ref: Ref<*>,
        kind: ElementKind,
    ): Ref<*> = if (kind == ElementKind.SEGMENT || kind == ElementKind.ARC) cx.indirect(ref) else ref

    /**
     * [el] as it was **built** — behind the view [publishedRef] gave it, never the trim bound onto it.
     *
     * **Trimming moves the piece, never the carrier**, and that is the rule that makes a fillet's own
     * construction acyclic: the rounding is tangent to the *carrier* of each leg, and the trim it then binds
     * onto that leg is derived from the rounding. Read through the view instead and the leg's carrier would
     * depend on the trim which depends on the carrier. It is also the honest reading — a segment's carrier
     * line and an arc's carrier circle are the same line and the same circle before and after the trim
     * (that is what a trim *is*), so nothing built on a carrier moves when a corner is rounded.
     */
    private fun builtRef(el: Element): Ref<*> = (el.ref.node as? IndirectNode)?.let { Ref<Value>(it.target) } ?: el.ref

    /** [el]'s geometry **as it stands** — the trim already on it, or the curve it was built as. */
    private fun pieceRef(el: Element): Ref<*> = (el.ref.node as? IndirectNode)?.let { Ref<Value>(it.boundTo ?: it.target) } ?: el.ref

    /** Whether [el] has been trimmed by a rounding — its view is bound onto something other than the curve it was built as. */
    fun isTrimmed(el: Element): Boolean = (el.ref.node as? IndirectNode)?.boundTo != null

    /**
     * Every node [el] publishes its geometry **by**: the view it is displayed through, the curve it was
     * built as, and the trim currently bound onto it (GitHub #25).
     *
     * One element, one identity — which is what every question about *who reads this element* has to be
     * asked over. A carrier is taken off the built curve ([builtRef]) while a consumer that took the element
     * whole holds the view, so a lookup keyed on one of the three would miss the others and report a
     * dependency the drawing does not have (or miss one it does).
     */
    fun publishedNodes(el: Element): List<Node> {
        val view = el.ref.node as? IndirectNode ?: return listOf(el.ref.node)
        return listOfNotNull(view, view.target, view.boundTo)
    }

    /** The element displaying [ref], if any — the inverse of the adders below. */
    fun elementFor(ref: Ref<*>): Element? =
        elements.lastOrNull { it.ref === ref || it.ref.node === ref.node || (it.ref.node as? IndirectNode)?.target === ref.node }

    /**
     * Add [ref] as a **copy of [source]**: same kind, same style — and the same **sketch space** (OP-17).
     *
     * A copy belongs where its original is drawn, exactly as it keeps its original's kind: 2D coordinates
     * only mean something in one space, so a transform of the part a face space is a face *of* (the one
     * cross-space pick there is, [addressableIn]) must stay in the plan where its footprint is drawn rather
     * than being stamped into the face's coordinates.
     */
    private fun addLike(
        ref: Ref<*>,
        source: Element,
    ): Element = add(ref, source.kind, source.style).also { it.space = source.space }

    private fun addDerived(ref: PointRef): PointRef {
        add(ref, ElementKind.DERIVED_POINT, Styles.DERIVED_POINT)
        return ref
    }

    /** Coerce a line/segment/ray element to its infinite carrier line. */
    @Suppress("UNCHECKED_CAST")
    private fun carrierLine(el: Element): LineRef =
        when (el.kind) {
            ElementKind.SEGMENT -> cx.lineOfSegment(builtRef(el) as SegmentRef)
            ElementKind.RAY -> cx.lineOfRay(el.ref as RayRef)
            else -> el.ref as LineRef
        }

    /**
     * Coerce a circle/arc element to its whole **carrier circle** — the exact twin of [carrierLine], and the
     * one place an arc becomes a circle operand.
     *
     * Every circle op is about the carrier (an intersection, a concentric offset, a tangent, a fillet leg),
     * so refusing an arc was refusing the construction rather than protecting it: a user report, *"intersect
     * between arc and circle not working"*, was exactly this filter and nothing else. What the coercion does
     * *not* promise is that the result lands on the arc's swept range — see [Element.isCentric].
     */
    @Suppress("UNCHECKED_CAST")
    private fun carrierCircle(el: Element): CircleRef =
        if (el.kind == ElementKind.ARC) cx.circleOfArc(builtRef(el) as ArcRef) else el.ref as CircleRef

    /** Coerce an ellipse/elliptic-arc element to its whole **carrier ellipse** — [carrierCircle]'s twin (OP-24). */
    @Suppress("UNCHECKED_CAST")
    private fun carrierEllipse(el: Element): EllipseRef =
        if (el.kind == ElementKind.ELLIPTIC_ARC) cx.ellipseOfArc(el.ref as EllipticArcRef) else el.ref as EllipseRef

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

    /**
     * A scalar name the script can carry: one word, no quotes. A step's arguments are split on spaces and
     * a name is written quoted, so a space or a `"` in one would either split the step or come back
     * changed — the same normalisation a group's name goes through ([uniqueGroupName]).
     */
    private fun scalarWord(base: String): String = base.trim().replace(Regex("\\s+"), "-").replace('"', '\'')

    /**
     * Ensure scalar names are unique so the wiring dropdown is never ambiguous — and so the file, which
     * refers to a scalar *by name*, is unambiguous too. [except] is not counted as a clash, so renaming an
     * entry to a name only it holds is a no-op rather than a fresh suffix.
     */
    private fun uniqueScalarName(
        base: String,
        except: ScalarEntry? = null,
    ): String {
        val b = scalarWord(base).ifBlank { "p" }

        fun taken(n: String) = scalars.any { it !== except && it.name == n }
        if (!taken(b)) return b
        var i = 2
        while (taken("$b$i")) i++
        return "$b$i"
    }

    /**
     * Whether [e] can be renamed — **exactly when the file names it** (OP-7): a `param` step of its own
     * introduced it, so a save restates the new name there and every `scalar=` reference to it.
     *
     * False for the two kinds whose name the script has no place for, and both would silently lose it (or
     * worse, write a reference nothing declares):
     * - a **measurement** (OP-4), whose name is generated by the step that measures it;
     * - a parameter another step created as part of itself — an opening's `pos`, `sill` and `head`
     *   ([addInterval]) — which replay recreates under its own generated name.
     */
    fun canRenameParameter(e: ScalarEntry): Boolean =
        e.editable && journal.any { s -> s.kind == "param" && s.createsScalars.any { it === e } }

    /**
     * Rename [e], uniquified exactly as creating it is ([uniqueScalarName]): a clash gets a suffix and a
     * blank field keeps the old name. Returns the name it actually took, or null when [e] cannot be
     * renamed at all ([canRenameParameter]).
     *
     * The save format needs nothing for this. Every mention of a scalar in the script is written as its
     * *current* name (the `param` step's own label, `scalar=`, a wire's operands, an opening's `width=`)
     * and load resolves by that name, so one rename restates the whole file consistently.
     */
    fun renameParameter(
        e: ScalarEntry,
        name: String,
    ): String? {
        if (!canRenameParameter(e)) return null
        val wanted = scalarWord(name)
        if (wanted.isEmpty()) return e.name
        // **a re-stamp may not capture a binder.** Inside a function curve `t` is the curve's own parameter
        // and wins over every drawing scalar, so renaming a scalar a curve reads to `t` would rewrite its
        // text into one that means something else — live and, worse, in the file. Refused by name, with the
        // cure, exactly as a hyphenated name is: a name a rename allows must keep its reading, for ever.
        if (funcCurves.values.any { c -> c.param == wanted && c.refs.any { r -> r.entry === e } }) {
            note = "Can't rename ${e.name} to '$wanted': inside a function curve '$wanted' is the curve's own " +
                "parameter, so the curve would read its parameter where it now reads ${e.name} — pick another name"
            return null
        }
        // …and the identical rule for a swept section's size law, whose `t` is the run parameter and outranks
        // every drawing scalar of that name (OP-26, session 77): a re-stamp may not capture a binder
        sweepLaws.values.firstOrNull { l -> l.param == wanted && l.refs.any { r -> r.entry === e } }?.let { l ->
            note = "Can't rename ${e.name} to '$wanted': inside ${nameOf(l.element)}'s size law '$wanted' is the " +
                "run's own parameter, so the law would read the station where it now reads ${e.name} — pick another name"
            return null
        }
        e.name = uniqueScalarName(wanted, except = e)
        // an expression that reads it is a mention like any other, so it is re-stamped rather than orphaned
        restampExpressions()
        return e.name
    }

    fun newParameter(
        name: String,
        value: Quantity,
    ): ScalarEntry {
        val node = ParameterNode(nextId("pn"), ScalarValue(value))
        val e = ScalarEntry(nextId("s"), uniqueScalarName(name), Ref<ScalarValue>(node), editable = true)
        // added inside the recording, so the step *owns* the scalar it introduces — which is what
        // lets delete's dependency analysis follow scalar references the same way as element ones
        return recording("param", Arg.Sc(e), Arg.Text("="), Arg.Num(value)) {
            scalars.add(e)
            e
        }
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

    /**
     * Take [e] back — the inverse of [newParameter]: drop the `param` step that introduced it and the panel
     * row with it. True when that was possible, false when something already reads it (then it stays,
     * untouched, and can never end up an orphaned reference).
     *
     * The retraction half of a **pending typed value** (see `Editor.commitTypedScalar`): a number typed for
     * a tool whose gesture was then abandoned never became part of the construction, so it must leave no
     * step and no row — exactly as a cancelled tool's stray points leave none.
     *
     * Deliberately **not** a delete. Delete's unit is the step *plus its dependents* (OP-18), and it exists
     * to remove things that are used; a retraction is only ever valid when nothing uses this yet, which is
     * why the answer here is a refusal rather than a cascade. Refusing is also the safe direction: the value
     * simply stays in the panel as an ordinary parameter.
     */
    fun retractParameter(e: ScalarEntry): Boolean {
        if (scalars.none { it === e }) return false // not ours (a document swap took it)
        val own = journal.firstOrNull { s -> s.createsScalars.any { it === e } } ?: return false
        // any *other* step naming it — a tool that consumed it, a wire, a wall's thickness — means it is
        // in use, and use is a checkpointed operation, so it has already been sealed as part of one
        if (journal.any { it !== own && referencedScalars(it).any { r -> r === e } }) return false
        // ...and likewise any node that reads it, which is what a parameter wired to it looks like
        val node = e.ref.node
        if (elements.any { dependsOn(it.ref.node, node, HashSet()) }) return false
        if (scalars.any { it !== e && dependsOn(it.ref.node, node, HashSet()) }) return false
        journal.remove(own)
        scalars.remove(e)
        return true
    }

    // ---- flat named groups (OP-16 step 1): organizational membership, nothing geometric ----

    private val allGroups = ArrayList<Group>()

    // a counter of its own, so grouping does not shift the element ids the rest of the UI shows
    private var groupCounter = 0

    /**
     * The groups that still exist. A group with no surviving member **is** gone — filtering here rather
     * than deleting the object is what makes live delete and replay agree without either side knowing
     * about the other (see the `group` case in [dependentSteps]).
     */
    val groups: List<Group> get() = allGroups.filter { groupMembers(it).isNotEmpty() }

    /** [g]'s members that are still in the document — a join can retire an element under a group. */
    fun groupMembers(g: Group): List<Element> = g.members.filter { m -> elements.any { it === m } }

    /** The group [el] belongs to, or null. At most one at this step — see [Group]. */
    fun groupOf(el: Element): Group? = groups.firstOrNull { g -> g.members.any { it === el } }

    /**
     * Where [g] *is*: its members' bounding-box centre — the same point a placement puts the frame at.
     *
     * What a pick **by name** stands in for (OP-16): feeding a group to a tool slot from the panel has no
     * click position of its own, and the slot's click list is positional, so the group's own centre is
     * recorded rather than a fabricated coordinate or a hole in the list.
     */
    fun groupCentre(g: Group): Vec2? = boundsCentre(groupMembers(g))

    /** Names are unique so the panel is unambiguous; blank auto-numbers ("group1", "group2", …). */
    private fun uniqueGroupName(
        base: String,
        except: Group? = null,
    ): String {
        // one word, since a step's arguments are split on spaces (as for scalar names)
        val b = scalarWord(base)

        fun taken(n: String) = allGroups.any { it !== except && it.name == n }
        if (b.isNotEmpty() && !taken(b)) return b
        val stem = b.ifEmpty { "group" }
        // an unnamed group is "group1"; a name that clashes becomes "kitchen2", as for scalars
        var i = if (b.isEmpty()) 1 else 2
        while (taken("$stem$i")) i++
        return "$stem$i"
    }

    /** The group [step] declares (its `group` step), or null — how the writer restates a renamed one. */
    internal fun groupDeclaredBy(step: Step): Group? = allGroups.firstOrNull { it.step === step }

    /** The group [step] places (its `place` step), or null. Identity, never the name — see [renameGroup]. */
    internal fun groupPlacedBy(step: Step): Group? = allGroups.firstOrNull { it.placeStep === step }

    /**
     * Whether [g] can be renamed: **exactly when the file names it**, the parameter rule again
     * ([canRenameParameter]) — a group with no `group` step of its own carries no name into the script.
     */
    fun canRenameGroup(g: Group): Boolean = g.step != null

    /**
     * Rename [g], uniquified as creating it is; a blank field keeps the old name. Returns the name it
     * actually **took**, or null when [g] cannot be renamed.
     *
     * **The format needed one correction, not a new mechanism.** A group's name appears in exactly two
     * steps — the `group` step that declares it and the `place` step that gives it a frame (OP-16 step 2) —
     * and both now restate the *current* name ([DocumentFormat.restate]), which is precisely how a renamed
     * parameter restates its `param` step and every `scalar=` reference to it (OP-7). What had to change is
     * that the writer no longer looks a placement's group up **by name**: it asks which group this very step
     * placed. That lookup was already a latent defect — with the name changed under it, a placed group's
     * frame would have stopped being restated and its position would have been lost on the next save.
     *
     * A **pattern's** name (`pattern "P1"`, and the `orbit` steps that ride it, OP-23) is deliberately not
     * involved: patterns live in a namespace of their own and no pattern step ever names a group, so a
     * rename here cannot reach one. Patterns are not renameable at all, which is why their labels can stay
     * frozen in the args.
     */
    fun renameGroup(
        g: Group,
        name: String,
    ): String? {
        if (!canRenameGroup(g)) return null
        val wanted = scalarWord(name)
        if (wanted.isEmpty()) return g.name
        g.name = uniqueGroupName(wanted, except = g)
        return g.name
    }

    /**
     * Group [members] under [name] (auto-numbered when blank), recorded as a `group` step so the
     * membership survives save/load. Refused when a member is already grouped or the set is empty —
     * the caller says which, since only it knows how to phrase it.
     */
    fun createGroup(
        name: String,
        members: List<Element>,
    ): Group? {
        if (members.isEmpty() || members.any { groupOf(it) != null }) return null
        val g = Group("g${++groupCounter}", uniqueGroupName(name))
        g.members.addAll(members)
        recording("group", Arg.Label(g.name), Arg.Keyed("els", Arg.Els(members))) { allGroups.add(g) }
        // the step is appended by [recording] itself, so it can only be picked up afterwards
        g.step = journal.lastOrNull()?.takeIf { it.kind == "group" }
        return g
    }

    /**
     * Dissolve [g]; its elements stay. The recorded step is dropped outright — a `group` step creates
     * no geometry, so unlike a delete (OP-18) nothing has to be replayed for the script to stay valid.
     * A *placed* group is unplaced first, so its members keep their positions as free points again.
     */
    fun ungroup(g: Group): Boolean {
        if (g.placed) unplaceGroup(g)
        if (!allGroups.remove(g)) return false
        g.step?.let { s -> journal.removeAll { it === s } }
        return true
    }

    // ---- placed groups (OP-16 step 2): a frame source node; moving the group edits the frame ----

    /** The placed group [el] belongs to, if any — whose frame a drag of [el] moves. */
    fun placedGroupOf(el: Element): Group? = groups.firstOrNull { it.placed && it.members.any { m -> m === el } }

    /**
     * True when [el]'s position is held **frame-relative** by a placed group.
     *
     * Its node is bound, like a weld's is, but for a different reason — so the two must be told apart:
     * a framed point is not a welded alias (it stays visible and draggable, through its local node).
     */
    fun isFramed(el: Element): Boolean {
        val node = literalNode(el) ?: return false
        return allGroups.any { g -> g.captures.any { it.original === node } }
    }

    /**
     * The frame the ortho path containing [corner] is placed under (OP-16), or null.
     *
     * Asked structurally rather than remembered on the handle, so a vertex a break creates inside a
     * placed path is frame-aware the moment it joins the path, and unplacing needs no bookkeeping.
     */
    fun pathFrameOf(corner: OrthoCornerHandle): SourceNode? =
        orthoPaths.firstOrNull { p -> p.vertices.any { it.corner === corner } }?.frame

    /** The live value of a frame source node — a placed group's origin and angle. */
    fun frameValue(node: SourceNode): FrameValue? = (Evaluator().eval(node) as? EvalResult.Ok)?.value as? FrameValue

    /** [g]'s frame value, or null when it is not placed. */
    fun frameValueOf(g: Group): FrameValue? = g.frameNode?.let { frameValue(it) }

    /**
     * What placing [g] would do — computed without touching anything, so the caller can refuse first.
     *
     * The frame carries the free point sources in the members' closure that the group **owns**: a free
     * point displayed by a non-member is that non-member's degree of freedom, not the group's, so it is
     * left alone (a member bound to it simply does not follow the frame — OP-16's boundary-attachment
     * rule, which falls out of `boundTo` with no special case). A free point the group *does* own but a
     * non-member also depends on is a [conflict][Placement.conflicts]: placing would silently capture
     * something outside, so it is refused instead.
     */
    fun analysePlacement(g: Group): Placement = analysePlacement(groupMembers(g))

    /**
     * The same analysis over a **prospective** membership — what [placementClosure] asks repeatedly while it
     * works out what a group would still have to contain, and what the create dialog can therefore offer
     * before anything is created.
     */
    fun analysePlacement(members: List<Element>): Placement {
        val memberSet = members.toHashSet()
        val candidates =
            ancestors(members.map { it.ref.node })
                .filterIsInstance<SourceNode>()
                .filter { it.boundTo == null && it.value is PointValue && !isSpaceAnchor(it) && ownedBy(it, memberSet) }
        // the other three kinds of freedom the group may own (see [FreedomKind]): a rider to re-anchor, and
        // the two that are relative to member geometry already and therefore rigid as they stand
        val owned = freedoms(members).filter { it.owned }
        val ridersToAnchor = ArrayList<Freedom>()
        val rigid = ArrayList<Freedom>()
        val uncapturable = ArrayList<String>()
        for (f in owned) {
            when (f.kind) {
                FreedomKind.FREE_POINT -> {}
                FreedomKind.RIDER -> {
                    val rec = f.rider ?: continue
                    when {
                        // already stated relative to a point of its carrier: rigid, and nothing to do
                        rec.carrierRelative && rec.base?.let { it in memberSet || dependsOn(it.ref.node, rec.host.ref.node, HashSet()) } == true ->
                            rigid.add(f)
                        rec.host !in memberSet ->
                            uncapturable.add("${nameOf(f.element)} rides ${nameOf(rec.host)}, which is not in the group")
                        carrierBaseFor(rec) == null ->
                            uncapturable.add("${nameOf(f.element)} rides ${nameOf(rec.host)}, which has no point of its own to measure from")
                        else -> ridersToAnchor.add(f)
                    }
                }
                FreedomKind.RELATIVE -> {
                    val anchor = relativeOf(f.element)?.let { elementFor(it.anchor) }
                    if (anchor != null && anchor in memberSet) {
                        rigid.add(f)
                    } else {
                        uncapturable.add(
                            "${nameOf(f.element)} follows ${anchor?.let { nameOf(it) } ?: "a point"} outside the group, so it will not move with it",
                        )
                    }
                }
                // a *share* of a span is rigid under any rigid map — and under rotation too, which a polar
                // bearing is not: the factor is dimensionless, so it says nothing about the world's axes
                FreedomKind.SPAN_RATIO -> rigid.add(f)
                FreedomKind.ON_CIRCLE -> {
                    val host = f.rider?.host
                    if (host != null && host in memberSet) {
                        rigid.add(f)
                    } else {
                        uncapturable.add("${nameOf(f.element)} rides ${host?.let { nameOf(it) } ?: "a circle"}, which is not in the group")
                    }
                }
            }
        }
        val paths = orthoPaths.filter { it.frame == null && ownsPath(it, memberSet) && capturablePath(it) }
        // …and a path this group owns but cannot carry **because a coordinate is anchored** (OP-4 case b,
        // GitHub issue #23) says so by name, exactly as a relative point does above: a path is captured whole
        // or not at all, so one anchored coordinate is the whole reason — and "it owns no degree of freedom"
        // would be the wrong sentence about a run that owns several (session 65).
        for (path in orthoPaths.filter { it.frame == null && ownsPath(it, memberSet) && it !in paths }) {
            for (el in elements.filter { e -> orthoRelatives.containsKey(e.id) && path.vertices.any { it.ref === e.ref } }) {
                val recs = orthoRelatives[el.id] ?: continue
                val a = recs.firstNotNullOfOrNull { elementFor(it.anchor)?.let { e -> nameOf(e) } } ?: "a point"
                uncapturable.add(
                    "${nameOf(el)} follows $a along ${recs.joinToString(" and ") { axisName(it.axis) }}, so its " +
                        "path is not carried whole — free it (Make absolute) to place the group",
                )
            }
        }
        // …and the freedom the *connections* own (OP-20): a junction riding a member's wall is the group's
        // own degree of freedom exactly as a rider on a member's curve is, and is carried the same way
        val junctionsToAnchor = ArrayList<Junction>()
        for (j in ownedJunctions(memberSet)) {
            when {
                j.carrierRelative -> {}
                junctionBaseFor(j) == null ->
                    uncapturable.add("${junctionName(j)} meets ${nameOf(j.curve!!)}, which has no point of its own to measure from")
                else -> junctionsToAnchor.add(j)
            }
        }
        for (j in outsideJunctions(memberSet)) {
            uncapturable.add("${junctionName(j)} meets ${nameOf(j.curve!!)}, which is not in the group")
        }
        val conflicts = ArrayList<SharedPoint>()
        // what the capture would take over: the free point sources, plus each captured path's vertices
        // (their published node) and the coordinate masters behind them
        val captured = HashSet<Node>(candidates)
        for (p in paths) {
            p.vertices.forEach { captured.add(it.ref.node) }
            captured.addAll(coordMasters(p, 0) + coordMasters(p, 1))
        }
        for (j in junctionsToAnchor) j.param?.let { captured.add(it) }
        if (captured.isNotEmpty()) {
            for (el in elements) {
                if (el in memberSet) continue
                for (n in ancestors(listOf(el.ref.node))) if (n in captured) conflicts.add(SharedPoint(labelOf(n), el))
            }
        }
        return Placement(
            boundsCentre(members) ?: Vec2(0.0, 0.0),
            candidates,
            paths,
            conflicts,
            ridersToAnchor,
            junctionsToAnchor,
            rigid,
            uncapturable,
        )
    }

    /**
     * The junctions whose freedom is **the group's own** — those riding a curve that is a member (OP-16 ×
     * OP-20), and the one enumeration everything about placing a connection asks.
     *
     * A junction at a free point (no [Junction.curve]) is deliberately not one: its freedom *is* that
     * point's own coordinates, so the free-point capture already answers for it. A junction whose parameter
     * is already bound has none left to carry.
     */
    private fun ownedJunctions(members: Set<Element>): List<Junction> =
        junctions.filter { j ->
            val host = j.curve ?: return@filter false
            val param = j.param ?: return@filter false
            host in members && param.boundTo == null && drivesAnyOf(j, members)
        }

    /** Junctions a member hangs on whose carrier the frame does *not* move — the boundary, named. */
    private fun outsideJunctions(members: Set<Element>): List<Junction> =
        junctions.filter { j -> j.curve != null && j.param != null && j.curve !in members && drivesAnyOf(j, members) }

    /** Whether any of [members] leans on [j]'s position — else the junction is none of the group's business. */
    private fun drivesAnyOf(
        j: Junction,
        members: Set<Element>,
    ): Boolean = members.any { m -> dependsOn(m.ref.node, j.point.node, HashSet()) }

    /**
     * How to name a junction to the user: the member it drives, since a junction has no element of its own.
     * "e16 meets e15" is what the drawing shows; the junction's node id is not a thing the user has seen.
     */
    private fun junctionName(j: Junction): String {
        val el = elements.firstOrNull { it.isPoint && dependsOn(it.ref.node, j.point.node, HashSet()) }
        return el?.let { nameOf(it) } ?: "a connection"
    }

    /**
     * A point of [j]'s carrier its position can be measured from — an **end of the wall it meets**, taken
     * from the path's own topology where there is one, else any point the carrier is built from.
     *
     * Stated rather than derived for the same reason a rider's base is ([carrierBaseFor]): a distance from
     * *that* end is what a drawing dimensions, and it is what makes the junction rigid under the group's
     * frame, since the carrier's end follows the frame.
     */
    private fun junctionBaseFor(j: Junction): Element? {
        val host = j.curve ?: return null
        legOf(host)?.let { (path, i) ->
            val ends = path.legEnds(i)
            listOf(ends.first, ends.second).forEach { v ->
                elementFor(v.ref)?.let { el -> if (!dependsOn(el.ref.node, j.point.node, HashSet())) return el }
            }
        }
        return elements.firstOrNull { el ->
            el.isPoint && riderOf(el) == null &&
                dependsOn(host.ref.node, el.ref.node, HashSet()) &&
                !dependsOn(el.ref.node, j.point.node, HashSet())
        }
    }

    /**
     * Why [g] could not move as one rigid figure, in the user's words — empty when it can.
     *
     * The same three answers [analysePlacement] gives, phrased as sentences and asked **at creation time** as
     * well as at placement time (OP-16's honest-failure rule): what a group cannot carry is invisible on the
     * canvas, so the report belongs to the gesture that decided it.
     */
    fun placementWarnings(g: Group): List<String> {
        if (g.placed) return emptyList()
        val a = analysePlacement(g)
        val out = ArrayList<String>()
        // the positions the frame would *not* hold, named together with what holds them instead: this is what
        // an unticked candidate costs, and it is invisible on canvas until the group is moved
        val (stuck, pinned) = prospectiveDeformers(groupMembers(g))
        if (stuck.isNotEmpty()) {
            // every name here is the drawing's own (OP-18) and the lists are summarized: a report that
            // enumerates the whole drawing is a wall, not an answer (the user's message)
            val drivers = pinned.map { labelOf(it) }.distinct()
            out.add(
                "this group cannot move independently — ${summarizeNames(stuck.map { nameOf(it) }, POINTS_NAMED)} " +
                    "${if (stuck.size == 1) "is" else "are"} held by ${summarizeNames(drivers, CONSUMERS_NAMED)}, " +
                    "shared with the drawing outside the group (tick those in, or group them too)",
            )
        }
        if (a.conflicts.isNotEmpty()) {
            val points = a.conflicts.map { it.point }.distinct()
            val consumers = a.conflicts.map { nameOf(it.consumer) }.distinct()
            out.add(
                "this group cannot move independently — ${summarizeNames(points, POINTS_NAMED)} " +
                    "${if (points.size == 1) "is" else "are"} shared with " +
                    "${summarizeNames(consumers, CONSUMERS_NAMED, "more of the drawing")} outside it " +
                    "(tick ${if (points.size == 1) "it" else "them"} into the group, or group those too)",
            )
        }
        out.addAll(a.uncapturable)
        if (out.isEmpty() && !a.carriesSomething) out.add("it owns no degree of freedom, so a frame would have nothing to move")
        return out
    }

    /**
     * A point of [rec]'s carrier its position can be measured from: a point element the carrier is **built
     * from**, i.e. one of its own ends.
     *
     * Stated rather than derived (`lineOrigin` would do arithmetically) because the anchor is what the user
     * then sees and edits: a distance from *that* end is what a drawing dimensions, and it is also what makes
     * the rider rigid under the group's frame — the carrier's end follows the frame, so the offset does.
     */
    private fun carrierBaseFor(rec: RiderRecord): Element? {
        val host = rec.host.ref.node
        return elements.firstOrNull { el ->
            el.isPoint && el !== rec.element && riderOf(el) == null && dependsOn(host, el.ref.node, HashSet())
        }
    }

    /**
     * Every degree of freedom the closure of [members] reaches, one entry per element that owns one — the
     * closure question OP-16 (group membership) and OP-6 (input ports) both ask, now answered for **all four
     * kinds** of freedom the engine has rather than for plain free points alone (see [FreedomKind]).
     *
     * Ordered with the ones the selection *displays* first, for the same reason [analyseMacro] is: what a
     * selection owns is what "this thing's own freedom" means, and what it merely leans on comes after.
     */
    fun freedoms(members: List<Element>): List<Freedom> {
        val closure = ancestors(members.map { it.ref.node }).mapTo(HashSet()) { it.id }
        val memberSet = members.toHashSet()
        val out = ArrayList<Freedom>()
        for (el in elements) {
            if (el.ref.node.id !in closure) continue
            val owned = el in memberSet
            val rec = riderOf(el)
            val rel = relativeOf(el)
            val node = literalNode(el)
            when {
                // an angle in the **host's own frame** — a circle's about its centre, a coil's about its axis
                // (OP-26). One row, because the placement question they answer is the same: nothing about a
                // frame that moves the host can re-anchor such an angle, so it is rigid as it stands.
                rec != null && (rec.form == RiderForm.CIRCLE_ANGLE || rec.form == RiderForm.HELIX_ANGLE) ->
                    out.add(
                        Freedom(
                            el,
                            FreedomKind.ON_CIRCLE,
                            "${nameOf(el)} — ${if (rec.form == RiderForm.HELIX_ANGLE) "on coil" else "on circle"} ${nameOf(rec.host)}",
                            owned,
                            rider = rec,
                        ),
                    )
                rec != null ->
                    out.add(
                        Freedom(
                            el,
                            FreedomKind.RIDER,
                            "${nameOf(el)} — " + (rec.base?.let { "${nameOf(it)} + distance along ${nameOf(rec.host)}" } ?: "slides on ${nameOf(rec.host)}"),
                            owned,
                            rider = rec,
                        ),
                    )
                rel != null ->
                    out.add(
                        Freedom(
                            el,
                            FreedomKind.RELATIVE,
                            "${nameOf(el)} — relative to ${elementFor(rel.anchor)?.let { nameOf(it) } ?: "an anchor"}",
                            owned,
                        ),
                    )
                el.handle is RatioPointHandle ->
                    out.add(Freedom(el, FreedomKind.SPAN_RATIO, "${nameOf(el)} — a ratio along its span", owned))
                el.kind == ElementKind.POINT && node?.boundTo == null && node != null ->
                    out.add(Freedom(el, FreedomKind.FREE_POINT, el.id, owned))
            }
        }
        return out.filter { it.owned } + out.filter { !it.owned }
    }

    /**
     * Whether the group owns *all* of [path] — every one of its vertices is displayed by a member.
     *
     * A path is captured whole or not at all, unlike free points, which are captured one by one: the
     * coordinate nodes of a straight run are *shared* by its vertices (that sharing is what keeps the run
     * straight), so capturing half a path would put one end's coordinates in local space and the other's
     * in world space and bend it where nothing was moved. Its legs need not be members: a leg is derived
     * from the two vertices, so it follows for the same reason any derived geometry does.
     */
    private fun ownsPath(
        path: OrthoPath,
        members: Set<Element>,
    ): Boolean = path.vertices.isNotEmpty() && path.vertices.all { v -> elementFor(v.ref)?.let { it in members } == true }

    /**
     * Whether [path]'s coordinates can be re-read as **local** ones: every coordinate chain must end in a
     * free master the path itself holds ([writableMaster]).
     *
     * A chain that ends in derived geometry does not — an end welded or attached to something is driven by
     * a [Junction], and a junction's position is a *world* position, so a captured vertex reading it would
     * take a world coordinate for a local one. Such a path keeps its world coordinates, does not follow the
     * frame, and is reported at placement time (OP-16's boundary-attachment rule, one granularity up).
     */
    private fun capturablePath(path: OrthoPath): Boolean =
        path.vertices.isNotEmpty() &&
            path.vertices.all { writableMaster(it.corner.xNode) != null && writableMaster(it.corner.yNode) != null }

    /**
     * Place [g]: give it a frame at [origin] (its members' bounding-box centre by default) rotated by
     * [angle] (rad), and retrofit the free points it owns to frame-relative form.
     *
     * **World-invariant by construction:** each captured source keeps its position, expressed as a fresh
     * local source measured from the frame's origin, and is then *bound* onto `frameApply(frame, local)`. So
     * the retrofit preserves every evaluated position, preserves the DOF count (one local per captured free
     * point, plus the frame's own three), and is invertible ([unplaceGroup]). Refused when already placed or
     * when a free point is shared with a non-member.
     *
     * Ortho paths are captured too ([capturePath]), by the same substrate one level up. Both kinds follow one
     * rule: **a capture changes the origin, never the orientation.** It has to — an ortho path is
     * axis-aligned by construction, so re-reading its coordinates in a *turned* frame turns the path, which
     * is exactly the feature (the rotated project frame). Making that rule uniform keeps a mixed group rigid:
     * placing at a nonzero [angle] turns all of it rather than turning the paths and leaving the points.
     *
     * The gesture therefore always places at [angle] 0, where the retrofit is exactly world-invariant, and
     * rotation is a later edit on the frame. Only replay passes a nonzero [angle] — and the steps it replays
     * first restate the *pre-rotation* positions the frame then turns (see [restatedPosition]).
     */
    fun placeGroup(
        g: Group,
        origin: Vec2? = null,
        angle: Double = 0.0,
    ): PlaceResult? {
        if (g.placed) return null
        val analysis = analysePlacement(g)
        if (analysis.conflicts.isNotEmpty()) return null
        val at = origin ?: analysis.origin
        val result =
            recording(
                "place",
                Arg.Label(g.name),
                Arg.Keyed("at", Arg.Pos(at)),
                Arg.Keyed("angle", Arg.Num(Quantity.rad(angle))),
            ) { placeGroupNow(g, at, angle, analysis.candidates, analysis.paths, analysis.ridersToAnchor, analysis.junctionsToAnchor) }
        g.placeStep = journal.lastOrNull()?.takeIf { it.kind == "place" }
        return result
    }

    private fun placeGroupNow(
        g: Group,
        at: Vec2,
        angle: Double,
        candidates: List<SourceNode>,
        paths: List<OrthoPath>,
        riders: List<Freedom> = emptyList(),
        junctionsToAnchor: List<Junction> = emptyList(),
    ): PlaceResult {
        val f = FrameValue(at, angle)
        val node = SourceNode(nextId("fr"), f)
        val frame = Ref<FrameValue>(node)
        g.frame = frame
        g.frameHandle = FrameHandle(node) { turnRefusal(g) == null }
        // every world position is read *before* any binding: reading them as the retrofit proceeds would
        // describe a half-placed document
        val ev = Evaluator()
        val world = candidates.map { pointOf(it, ev) }
        // …and for exactly the same reason each rider's offset from its base is read here, in the geometry as
        // it stands **before** the frame turns anything. A rider's own parameter is a *world* quantity (a
        // coordinate, or `world·dir`), so deriving the offset after the capture would derive it against turned
        // geometry — and since only a *replay* places at a nonzero angle, the gesture and the replay of it
        // would then capture two different figures. That is what broke `save → load → save` on a turned group.
        val offsets =
            riders.mapNotNull { r ->
                val rec = r.rider ?: return@mapNotNull null
                val base = carrierBaseFor(rec) ?: return@mapNotNull null
                val here = ((ev.eval(rec.param) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.mm
                val there = carrierBaseParamValue(rec, base, ev)
                if (here == null || there == null) null else Triple(r.element, base, Quantity.mm(here - there))
            }
        // …and each junction's offset from the end of the wall it meets, read in the same untouched geometry
        // and for the same reason (OP-16 × OP-20): a connection owns a degree of freedom too
        val junctionOffsets =
            junctionsToAnchor.mapNotNull { j ->
                val base = junctionBaseFor(j) ?: return@mapNotNull null
                val param = j.param ?: return@mapNotNull null
                val line = j.line ?: return@mapNotNull null
                val form = j.form ?: return@mapNotNull null
                val here = ((ev.eval(param) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.mm
                val basePoint = base.ref as? PointRef
                val there =
                    basePoint?.let {
                        ((ev.eval(carrierBaseParam(form, j.axis, it, line).node) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.mm
                    }
                if (here == null || there == null) null else Triple(j, base, Quantity.mm(here - there))
            }
        for ((i, src) in candidates.withIndex()) {
            val w = world[i] ?: continue
            // a change of origin, not of orientation — the one rule both capture kinds follow (see the
            // method comment and [capturePath]): the local coordinate is the world one measured from the
            // frame's origin, and the frame's angle then *turns* what it carries
            val local = SourceNode(nextId("lp"), PointValue(w - f.origin))
            src.boundTo = cx.frameApply(frame, Ref<PointValue>(local)).node
            val el = elementOwning(src)
            val prior = el?.handle
            // its DOF is now the local point, so its handle must write *that* — by inverse-mapping the
            // cursor, which keeps the drag landing under the pointer and the fields reading world values
            if (el != null) el.handle = FramedPointHandle(node, local)
            g.captures.add(FrameCapture(src, local, el, prior))
        }
        for (path in paths) capturePath(g, path, frame, f)
        // …and the riders: a rider is *not* rigid under a frame while its parameter is anchored to the world
        // (a world coordinate, or a distance along the carrier line — OP-20), because a frame that moves the
        // carrier leaves the parameter's meaning where it was. Re-anchoring it to a point of its own carrier
        // states the motion instead of compensating for it, which is exactly the conversion OP-4 case (b) is:
        // DOF-preserving, world-invariant here, and undone again by [unplaceGroup].
        for ((element, base, d) in offsets) {
            val rec = riderOf(element) ?: continue
            if (anchorRiderTo(element, base, d)) g.capturedRiders.add(rec)
        }
        // …and the junctions, for the identical reason one level up: the freedom a *connection* owns is
        // stated in the world too, and a run hanging on an un-carried junction is what tore the reported
        // drawing apart when its frame moved (GitHub issue #11)
        for ((j, base, d) in junctionOffsets) anchorJunctionTo(j, base, d)?.let { g.capturedJunctions.add(it) }
        note = null // the capture's per-rider notes are not what the placement has to say
        return PlaceResult(
            g.captures.size,
            g.capturedPaths.size,
            deformingMembers(g),
            g.capturedRiders.size,
            g.capturedJunctions.size,
        )
    }

    /**
     * Capture [path] under [frame] (OP-16's *ortho-path bonus*): its coordinates become the group's local
     * ones, and each vertex is published through the frame.
     *
     * Two writes, and no rewiring (OP-5):
     * - every **master** coordinate the path holds moves by the frame's origin, once per master rather than
     *   once per vertex — the vertices of a straight run resolve to the same node, and writing it once is
     *   what keeps them straight. The binding structure (who follows whom) is not touched at all: it now
     *   relates *local* coordinates, so axis-alignment becomes alignment to the frame's own axes.
     * - each vertex's published node is **bound** onto `frameApply(frame, local)`, so its legs, the wall
     *   riding it, the openings' leg-relative parameters and anything else downstream follow the frame
     *   without a single input list being rewired.
     *
     * A capture changes the path's origin, never its orientation — the same rule a free point's capture
     * follows ([placeGroup]): with the frame's angle at 0 (the only angle the gesture places at) it is
     * exactly world-invariant, and turning the frame afterwards turns the path — legs still straight and
     * perpendicular *in the group*, rotated in the world.
     */
    private fun capturePath(
        g: Group,
        path: OrthoPath,
        frame: FrameRef,
        f: FrameValue,
    ) {
        for (n in coordMasters(path, 0)) shiftCoord(n, -f.origin.x)
        for (n in coordMasters(path, 1)) shiftCoord(n, -f.origin.y)
        path.frame = frame.node as? SourceNode
        for (v in path.vertices) captureVertex(path, v)
        g.capturedPaths.add(path)
    }

    /**
     * The free coordinate masters [path] holds on [axis] (0 = x, 1 = y) — the nodes a capture translates,
     * a drag writes and a frame therefore drives.
     *
     * One entry per master rather than per vertex: the vertices of a straight run resolve to the same node,
     * and that sharing is exactly what keeps the run straight (OP-19), so translating it once translates
     * the whole run.
     */
    private fun coordMasters(
        path: OrthoPath,
        axis: Int,
    ): Set<SourceNode> {
        val out = LinkedHashSet<SourceNode>()
        for (v in path.vertices) writableMaster(if (axis == 0) v.corner.xNode else v.corner.yNode)?.let { out.add(it) }
        return out
    }

    /** Publish [v] through [path]'s frame, if it has one — what makes a vertex a *framed* vertex. */
    private fun captureVertex(
        path: OrthoPath,
        v: OrthoVertex,
    ) {
        val frame = path.frame ?: return
        v.indirect?.boundTo = cx.frameApply(Ref<FrameValue>(frame), v.local).node
    }

    /** Move a free coordinate master by [by] mm — a master holds its literal, so this is one write. */
    private fun shiftCoord(
        node: SourceNode,
        by: Double,
    ) {
        val q = (node.value as? ScalarValue)?.q ?: return
        node.value = ScalarValue(Quantity.mm(q.mm + by))
    }

    /**
     * Members the frame does not carry *entirely*: they depend on a position that is pinned in world
     * coordinates and is not one of the group's own locals, so moving the frame stretches them.
     *
     * The pinned kinds are exactly three — a **free point source** owned by something outside the group (a
     * weld or an attach that left it), an **ortho vertex coordinate the capture did not take**, which stays
     * an absolute world coordinate, and a **junction parameter** the capture did not re-anchor, which pins a
     * connection's position on a wall that moves (GitHub issue #11). A curve parameter of a *rider* is
     * deliberately not one once it is carrier-relative: it is then relative to a curve that itself follows
     * the frame, so such a point is carried rigidly.
     */
    private fun deformingMembers(g: Group): List<Element> {
        // what the frame does drive: the captured points' locals, the captured paths' own coordinates, and
        // each re-anchored junction's offset from the wall it meets
        val carried = g.captures.mapTo(HashSet<SourceNode>()) { it.local }
        for (p in g.capturedPaths) carried.addAll(coordMasters(p, 0) + coordMasters(p, 1))
        for (c in g.capturedJunctions) c.junction.offset?.let { carried.add(it) }
        return deformingMembers(groupMembers(g), carried).first
    }

    /**
     * The same question **before** a placement: which of [members] depend on a position [carried] would not
     * hold, and which positions those are — so the report can name both the member that will not follow and
     * the point that keeps it where it is.
     *
     * Asked ahead of the gesture as well as after it because that is what lets the *creation* of a group say
     * what its placement will not manage ([placementWarnings]).
     */
    private fun deformingMembers(
        members: List<Element>,
        carried: Set<SourceNode>,
    ): Pair<List<Element>, List<SourceNode>> {
        val orthoCoords = HashSet<SourceNode>()
        for (p in orthoPaths) {
            for (v in p.vertices) {
                orthoCoords.add(v.corner.xNode)
                orthoCoords.add(v.corner.yNode)
            }
        }
        // a junction's parameter is a world quantity too (OP-20), so an un-carried one pins whatever hangs
        // on that connection just as surely as a coordinate does — the blind spot GitHub issue #11 found
        val junctionParams = junctions.mapNotNullTo(HashSet<SourceNode>()) { it.param }

        fun pinned(m: Element): List<SourceNode> =
            ancestors(listOf(m.ref.node)).filterIsInstance<SourceNode>().filter { s ->
                s.boundTo == null && s !in carried && !isSpaceAnchor(s) &&
                    (s.value is PointValue || s in orthoCoords || s in junctionParams)
            }
        val bad = members.filter { pinned(it).isNotEmpty() }
        // the *nodes* rather than their names, because two callers want two different things of them: the
        // report names them (OP-18, [labelOf]) and the one-click closure has to find the elements that hold
        // them ([placementClosure])
        val drivers = LinkedHashSet<SourceNode>()
        for (m in bad) drivers.addAll(pinned(m))
        return bad to drivers.toList()
    }

    /**
     * The positions a placement of [members] would **not** hold — the pinned sources behind
     * [placementWarnings]' first sentence, and the closure's starting point.
     */
    private fun prospectiveDeformers(members: List<Element>): Pair<List<Element>, List<SourceNode>> {
        val a = analysePlacement(members)
        val prospective = HashSet<SourceNode>(a.candidates)
        for (p in a.paths) prospective.addAll(coordMasters(p, 0) + coordMasters(p, 1))
        for (j in a.junctionsToAnchor) j.param?.let { prospective.add(it) }
        return deformingMembers(members, prospective)
    }

    /**
     * **The one click that makes a group placeable** (OP-16's honest failure, with a way through it): the
     * elements a group of [members] would additionally have to contain, so that no position it moves is
     * shared with the drawing outside it and nothing it leans on stays behind.
     *
     * The user's report is the specification — *"include them in the group, or this group cannot move
     * independently"* read as a demand to hand-pick dozens of elements, "almost impossible to do". The set
     * being asked for is computable, and it is a **fixpoint**, not one hop: pulling a consumer in gives the
     * group that consumer's own freedom, which may in turn be shared with something further out. Each round
     * adds at least one element and there are finitely many, so the sweep terminates.
     *
     * Two kinds are pulled in, one per honest-failure case:
     *
     * - a **conflict**'s consumer — a non-member built on a position the frame would take over;
     * - the element that **holds** a position a member leans on and the frame would not carry (for an ortho
     *   coordinate that is the whole path, since a path is one unit of freedom and is captured whole).
     *
     * Membership is recorded exactly as any other membership is (a `group` step's `els=`, OP-18) — this adds
     * no step semantics, only a computed selection.
     */
    fun placementClosure(members: List<Element>): List<Element> {
        val seed = members.toMutableSet()
        val set = LinkedHashSet(members)
        repeat(elements.size + 1) {
            val add = LinkedHashSet<Element>()
            val current = set.toList()
            for (c in analysePlacement(current).conflicts) add.add(c.consumer)
            for (n in prospectiveDeformers(current).second) add.addAll(elementsHolding(n))
            add.removeAll(set)
            if (add.isEmpty()) return set.filter { it !in seed }
            set.addAll(add)
        }
        return set.filter { it !in seed }
    }

    /**
     * The elements that must join a group for it to own [node] — the element displaying it, or, for an ortho
     * coordinate, **every vertex of its path**: a path's coordinates are shared along each run, so half a
     * path cannot be captured ([ownsPath]).
     */
    private fun elementsHolding(node: SourceNode): List<Element> {
        for (path in orthoPaths) {
            val holds =
                path.vertices.any { v ->
                    v.corner.xNode === node || v.corner.yNode === node ||
                        writableMaster(v.corner.xNode) === node || writableMaster(v.corner.yNode) === node
                }
            if (holds) return path.vertices.mapNotNull { elementFor(it.ref) }
        }
        return listOfNotNull(elementOwning(node))
    }

    /**
     * Unplace [g]: **exactly what the capture took, given back** — every captured source keeps the position
     * the frame's origin puts it at, and the frame is dropped. The group survives as a flat one, and its
     * `place` step goes (like [ungroup] drops the `group` step).
     *
     * The inverse of [placeGroup], hence world-invariant while the frame is unturned. A *turned* frame is the
     * one case where nothing could be: an ortho path's legs are axis-aligned by construction, so only a frame
     * can hold one turned, and un-turning the paths while leaving the points would tear the group apart.
     * Inverting the capture keeps the group rigid and gives back precisely what placing changed — the
     * rotation lived in the frame that is going. It is reported rather than hidden ([unturnsGroup]).
     */
    fun unplaceGroup(g: Group): Boolean {
        if (!g.placed) return false
        val f = frameValueOf(g)
        for (c in g.captures) {
            c.original.boundTo = null
            val local = (c.local.value as? PointValue)?.p
            if (f != null && local != null) c.original.value = PointValue(f.origin + local)
            c.restoreHandle()
        }
        g.captures.clear()
        for (path in g.capturedPaths) {
            for (v in path.vertices) v.indirect?.boundTo = null
            if (f != null) {
                for (n in coordMasters(path, 0)) shiftCoord(n, f.origin.x)
                for (n in coordMasters(path, 1)) shiftCoord(n, f.origin.y)
            }
            path.frame = null
        }
        g.capturedPaths.clear()
        // the riders come back to their world-anchored parameter, where they now stand — the inverse of the
        // re-anchoring the capture performed, and world-invariant for the same reason
        for (rec in g.capturedRiders) releaseRiderNow(rec.element)
        g.capturedRiders.clear()
        // and the junctions likewise, *after* the paths have been given their world coordinates back: a
        // junction's parameter must restate where it stands in the geometry the unplacing leaves behind
        for (c in g.capturedJunctions) {
            releaseJunction(c.junction)
            c.restore()
        }
        g.capturedJunctions.clear()
        g.frame = null
        g.frameHandle = null
        g.placeStep?.let { s -> journal.removeAll { it === s } }
        g.placeStep = null
        return true
    }

    /**
     * Why [g]'s frame may not be **turned**, in the user's words — null when it may.
     *
     * A group is rigid under rotation only where every run it holds is *captured*, because a captured path's
     * legs are axis-aligned in the group's own axes and turn with them (OP-16's *ortho-path bonus*). A run
     * that follows the frame through its **connections** instead — an interior wall whose ends are welded or
     * attached to the outline it meets (OP-20) — keeps its legs aligned to the **world's** axes, since that
     * is what the shared coordinate nodes still say. Such a group translates rigidly and cannot turn: the
     * axis lines its meetings are built on would run parallel to the walls they must cross.
     *
     * Stated rather than approximated, and refused at the one place a rotation can enter (the angle field,
     * OP-13) rather than discovered afterwards as vanished geometry.
     */
    fun turnRefusal(g: Group): String? {
        val frame = g.frameNode ?: return null
        val members = groupMembers(g).toHashSet()
        val stuck =
            orthoPaths.filter { p ->
                p.frame == null &&
                    p.vertices.any { v -> elementFor(v.ref)?.let { it in members } == true } &&
                    p.vertices.any { v -> dependsOn(v.ref.node, frame, HashSet()) }
            }
        if (stuck.isEmpty()) return null
        val names = stuck.mapNotNull { p -> p.vertices.firstNotNullOfOrNull { elementFor(it.ref) }?.let { nameOf(it) } }
        return "${g.name} cannot be turned: the run at ${names.joinToString(", ")} follows the frame through the walls it " +
            "meets, and its legs are aligned to the world's axes, not to the group's — move it, or unjoin that run first"
    }

    /**
     * Whether unplacing [g] would **un-turn** it — true only while its frame is rotated, and the one thing
     * about unplacing that is not world-invariant (see [unplaceGroup]).
     */
    fun unturnsGroup(g: Group): Boolean =
        (g.captures.isNotEmpty() || g.capturedPaths.isNotEmpty()) && (frameValueOf(g)?.angle ?: 0.0) != 0.0

    /**
     * The position the step at [stepIndex] must restate for [el] (OP-18) — its world position, or the
     * position it had **before its capture** when a *later* `place` step captured it.
     *
     * Why the step's place in the script matters. A captured source holds coordinates measured from its
     * frame's origin, and the step that created it replays **before** the placement that captures it — so
     * what it must restate is where that source stood unplaced: its local value plus the frame's origin,
     * which is exactly what the capture then subtracts off again. For an ortho path this is the *only*
     * restatement that works at all: under a turned frame the world positions describe a turned path, and the
     * drawing steps snap every leg to an axis, so they could not rebuild it. A step recorded *after* the
     * placement (a break inside a placed group) already runs on captured geometry and maps its own positions
     * into the frame, so there the world position is what has to be written.
     *
     * The file therefore still contains no local coordinates and no node names — only positions the drawing
     * steps can be replayed from.
     */
    fun restatedPosition(
        el: Element,
        stepIndex: Int,
        ev: Evaluator,
    ): Vec2? {
        val world = pointOf(el.ref.node, ev)
        val path = orthoPaths.firstOrNull { p -> p.vertices.any { it.ref === el.ref } }
        // the group that captured this element, and the local source that now holds its position
        val g: Group
        val localNode: Node
        if (path != null) {
            if (path.frame == null) return world
            g = allGroups.firstOrNull { grp -> grp.capturedPaths.any { it === path } } ?: return world
            localNode = path.vertices.first { it.ref === el.ref }.local.node
        } else {
            val node = literalNode(el) ?: return world
            g = allGroups.firstOrNull { grp -> grp.captures.any { it.original === node } } ?: return world
            localNode = g.captures.first { it.original === node }.local
        }
        val placedAt = g.placeStep?.let { s -> journal.indexOfFirst { it === s } } ?: return world
        if (placedAt <= stepIndex) return world // the capture has already happened by the time this replays
        val f = frameValueOf(g) ?: return world
        val local = pointOf(localNode, ev) ?: return world
        return f.origin + local
    }

    /** [node]'s effective point value — its literal, or whatever drives it. */
    private fun pointOf(
        node: Node,
        ev: Evaluator,
    ): Vec2? = ((ev.eval(node) as? EvalResult.Ok)?.value as? PointValue)?.p

    /**
     * The element that *displays* [node] — publishing it directly, or through the re-pointable view a
     * detached rider keeps ([detachRider]). One lookup, so a freed rider is as much the owner of its
     * coordinates as a point created free is.
     */
    private fun elementOwning(node: Node): Element? = elements.lastOrNull { node in publishedNodes(it) }

    /**
     * Whether [node] is a **sketch space's origin anchor** (OP-17) — a free point at (0, 0) *in that plane's
     * own coordinates*, and therefore never a group's degree of freedom.
     *
     * It looks exactly like a capturable free point to [analysePlacement] — an unbound `PointValue` source
     * that no element displays, so [ownedBy] claims it for whatever group's closure reaches it — and
     * capturing it is wrong three times over (a user report, session 55, found through the third one):
     *
     * - **it is not a world position.** It offsets the origin of a *plane*, so a frame that moves it slides
     *   the whole sketch on that plane sideways in u/v, not through space.
     * - **it is not the group's.** A space is shared: one group's frame would move every other drawing on
     *   that plane with it.
     * - **and no step restates it**, so it does not survive a reload. That is what the report showed: a
     *   placed group whose frame had been *dragged* wrote a rider's `dofs=` measured on a sketch the capture
     *   had slid by the frame's delta, while replay rebuilt the anchor at its own (0, 0) — so the file said
     *   one position and reloaded to another, and `save → load → save` was not byte-equal.
     *
     * A plane whose hinge is member geometry follows the frame *by construction*, which is the honest
     * mechanism and needs no capture; one that does not is the cross-space boundary named under OP-16. So the
     * anchor is excluded from the capture **and** from the pinned kinds ([deformingMembers]): it is not a
     * world position, so it can neither be carried nor hold anything back.
     */
    private fun isSpaceAnchor(node: SourceNode): Boolean = spaces.any { it.originAnchor?.node === node }

    /** Whether the element that *displays* [node] is one of [members] — see [analysePlacement]. */
    private fun ownedBy(
        node: SourceNode,
        members: Set<Element>,
    ): Boolean {
        val owner = elementOwning(node) ?: return true
        return owner in members
    }

    /**
     * How to name **any** node to the user (OP-18's naming authority: the file's script-local name is the
     * only user-visible name, so an internal node id is never one). In order:
     *
     * - the element that *displays* the node — which now includes a node an element publishes through an
     *   [IndirectNode] (an ortho vertex is exactly that, and it used to fall straight through to `n7`);
     * - an ortho **corner coordinate**, named as the corner element that holds it — a shared scalar has no
     *   element of its own, but the corner the user clicks does (failing that, a leg of its path);
     * - the meeting a junction parameter is the freedom of (OP-20), and a sketch space's own origin;
     * - and, for a node no element publishes at all, **what it is** of the drawing that leans on it —
     *   "a shared coordinate of e12". Never the id.
     */
    private fun labelOf(node: Node): String {
        elementOwning(node)?.let { return nameOf(it) }
        (node as? SourceNode)?.let { s ->
            orthoCoordOwner(s)?.let { return nameOf(it) }
            junctions.firstOrNull { it.param === s }?.let { return "the connection at ${junctionName(it)}" }
            spaces.firstOrNull { it.originAnchor?.node === s }?.let { return "the origin of ${it.name}" }
        }
        val user = elements.firstOrNull { dependsOn(it.ref.node, node, HashSet()) }
        return if (user == null) "a shared coordinate of the drawing" else "a shared coordinate of ${nameOf(user)}"
    }

    /**
     * The corner element an ortho coordinate node belongs to — how a shared `x`/`y` scalar is named (OP-18).
     *
     * Asked through [writableMaster] as well as directly, because the coordinates of a straight run are
     * *shared*: the node a capture or a conflict names is the run's **master**, and the corner that holds it
     * is what the user sees and clicks.
     */
    private fun orthoCoordOwner(node: SourceNode): Element? {
        for (path in orthoPaths) {
            for (v in path.vertices) {
                val holds =
                    v.corner.xNode === node || v.corner.yNode === node ||
                        writableMaster(v.corner.xNode) === node || writableMaster(v.corner.yNode) === node
                if (holds) return elementFor(v.ref) ?: path.legs.firstOrNull()
            }
        }
        return null
    }

    /** [roots] and every node they (transitively) depend on. */
    private fun ancestors(roots: List<Node>): List<Node> {
        val out = ArrayList<Node>()
        val seen = HashSet<String>()

        fun walk(n: Node) {
            if (!seen.add(n.id)) return
            out.add(n)
            n.inputs.forEach { walk(it) }
        }
        roots.forEach { walk(it) }
        return out
    }

    /**
     * The centre of [els]' bounding box — where a fresh frame starts.
     *
     * A deliberate choice, not the only one: the origin is where the group *rotates about* and what its
     * local coordinates are measured from, and the box centre is the one candidate that needs no extra
     * pick. Moving it afterwards is *relocate-origin*, a world-invariant refactoring rather than an edit
     * (OP-16), and belongs to step 3.
     */
    private fun boundsCentre(els: List<Element>): Vec2? {
        val ev = Evaluator()
        val box = GeomMath.bbox(els.flatMap { extentPoints(ev, it) }) ?: return null
        return (box.first + box.second) * 0.5
    }

    /** The extreme points of [el]'s geometry, per value kind — what its bounding box is taken over. */
    private fun extentPoints(
        ev: Evaluator,
        el: Element,
    ): List<Vec2> =
        when (val v = ev.valueOf(el.ref)) {
            is PointValue -> listOf(v.p)
            is SegmentValue -> listOf(v.seg.a, v.seg.b)
            is CircleValue -> GeomMath.bounds(ProfileElement.CircleE(v.circle)).toList()
            is ArcValue -> GeomMath.bounds(ProfileElement.ArcE(v.arc)).toList()
            is BezierValue -> GeomMath.bounds(ProfileElement.BezierE(v.bezier)).toList()
            // an infinite carrier has no extent of its own; its defining point stands for it
            is LineValue -> listOf(v.line.origin)
            is RayValue -> listOf(v.ray.origin)
            is LoopValue -> v.loop.elements.flatMap { GeomMath.bounds(it).toList() }
            // a cutting chain's extent is its **finite** run: its rays have none, exactly as a line's and a
            // ray's own extent above is the point that defines them
            is ChainValue -> v.chain.pieces.flatMap { GeomMath.bounds(it).toList() }
            is RegionValue ->
                (v.region.outer.elements + v.region.holes.flatMap { it.elements }).flatMap { GeomMath.bounds(it).toList() }
            else -> emptyList()
        }

    // ---- user-defined macros (OP-6): definition by example, instances by virtual addressing ----

    private val macroDefs = ArrayList<MacroDef>()
    private val macroInstanceList = ArrayList<MacroInstance>()
    private var macroCounter = 0
    private var instanceCounter = 0

    /** The macro definitions this document declares — each of them a tool in the palette (OP-6). */
    val macros: List<MacroDef> get() = macroDefs.toList()

    /** The live instances: one whose elements have all been deleted **is** gone, as an empty group is. */
    val macroInstances: List<MacroInstance>
        get() = macroInstanceList.filter { inst -> inst.elements.any { e -> elements.any { it === e } } }

    /**
     * Every tool this document can run: the static registry plus this document's own macros.
     *
     * The registry being *static* was the one thing user-defined tools needed changed (OP-6's UI half):
     * a macro is an ordinary [ToolDef], so the palette, the click collector and the `tool` step all work
     * on it unmodified — they only have to ask the document instead of [Tools] directly.
     */
    val toolDefs: List<ToolDef> get() = Tools.all + macroDefs.map { it.tool }

    fun toolDef(id: String): ToolDef? = Tools.byId(id) ?: macroDefs.firstOrNull { it.toolId == id }?.tool

    /**
     * What [members] could become (OP-6 by example): the free sources their closure reaches, which are
     * exactly the candidate input ports — the same closure analysis a placement performs
     * ([analysePlacement]), asking OP-16's question the other way round.
     *
     * A group asks *"do the ancestor points join the group?"*; a macro asks *"do they become inputs?"*.
     * So a point the selection merely *uses* is deliberately **not** filtered out here: that is exactly
     * what an input port is, while for a group it would be an outsider. Ownership still matters, but only
     * for *order* and hence for the dialog's default — see below.
     */
    fun analyseMacro(members: List<Element>): MacroAnalysis {
        val reachable = ancestors(members.map { it.ref.node })
        val closure = reachable.mapTo(HashSet()) { it.id }
        val memberSet = members.toHashSet()
        val free =
            elements.filter { el ->
                el.kind == ElementKind.POINT && el.ref.node.let { it is SourceNode && it.boundTo == null } && el.ref.node.id in closure
            }
        // The points the selection **owns** come first, so the anchor (the first point input) is by
        // default one of its own rather than something it merely leans on. That matters as soon as a
        // definition contains an *instance*: a macro is a transparent group (OP-6), so the inner
        // definition's free points are legitimately in the closure too — they are just not what "place
        // this here" means.
        val owned = free.filter { it in memberSet }
        val points = owned + free.filter { it !in memberSet }
        val parameters =
            scalars.filter { it.editable && (it.ref.node as? ParameterNode)?.boundTo == null && it.ref.node.id in closure }
        val problems = ArrayList<String>()
        if (members.any { it.isAnnotation }) {
            problems.add("A dimension can't be part of a tool yet — it annotates the drawing rather than being part of it")
        }
        // a placed group's positions live in its frame (OP-16), which an instance would have to carry a
        // copy of; until then the honest answer is to say so rather than stamp instances on top of it
        if (reachable.any { it is SourceNode && it.value is FrameValue }) {
            problems.add("Unplace the group first: a tool can't carry a placement frame yet")
        }
        if (points.isEmpty()) problems.add("This selection reaches no free point, so an instance would have nowhere to be placed")
        return MacroAnalysis(points, parameters, problems, owned.mapTo(HashSet()) { it.id }, freedoms(members))
    }

    /** One word (a step's arguments split on spaces) and unique, exactly as a group's name is. */
    private fun uniqueMacroName(base: String): String {
        val b = base.trim().replace(Regex("\\s+"), "-").replace("\"", "")
        if (b.isNotEmpty() && macroDefs.none { it.name == b }) return b
        val stem = b.ifEmpty { "tool" }
        var i = if (b.isEmpty()) 1 else 2
        while (macroDefs.any { it.name == "$stem$i" }) i++
        return "$stem$i"
    }

    /**
     * Declare the sub-construction behind [members] a macro named [name], with [pointInputs] as its
     * click slots (**the first is the anchor**) and [scalarInputs] as its panel inputs (OP-6).
     *
     * Recorded as a `macrodef` step (OP-18) that *creates nothing*: like a `group` step it is a
     * designation over what earlier steps built, so replaying it re-declares the tool without rebuilding
     * any geometry — and the custom tool is therefore part of the file rather than of the session.
     */
    fun defineMacro(
        name: String,
        members: List<Element>,
        pointInputs: List<Element>,
        scalarInputs: List<ScalarEntry>,
    ): MacroDef? {
        if (members.isEmpty() || pointInputs.isEmpty()) return null
        if (pointInputs.any { (it.ref.node as? SourceNode)?.boundTo != null || it.ref.node !is SourceNode }) return null
        val def =
            MacroDef(
                "mac${++macroCounter}",
                uniqueMacroName(name),
                members.toList(),
                pointInputs.toList(),
                scalarInputs.toList(),
            )
        recording(
            "macrodef",
            *listOfNotNull(
                Arg.Label(def.name),
                Arg.Keyed("els", Arg.Els(def.elements)),
                Arg.Keyed("pts", Arg.Els(def.pointInputs)),
                Arg.Keyed("scalar", Arg.Scs(def.scalarInputs)).takeIf { def.scalarInputs.isNotEmpty() },
            ).toTypedArray(),
        ) { macroDefs.add(def) }
        def.step = journal.lastOrNull()?.takeIf { it.kind == "macrodef" }
        return def
    }

    /**
     * Whether any of [els] is named by a macro definition (OP-6).
     *
     * A definition is a list of elements and an instance's element count is structural (OP-18), so an
     * operation that would **retire** one of them — an ortho break or join, the two edits that replace
     * path elements rather than moving them — has to be refused rather than leaving a definition
     * describing geometry that no longer exists.
     */
    fun definesAMacro(els: List<Element>): Boolean =
        macroDefs.any { d -> d.elements.any { e -> els.any { it === e } } }

    /** The live instances of [def] — what forbids removing it, and what an edit of it propagates to. */
    fun instancesOf(def: MacroDef): List<MacroInstance> = macroInstances.filter { it.def === def }

    /**
     * Retire the tool [def]. Refused while instances exist: they are *functions of it*, and dropping the
     * definition would leave their `tool` steps naming a tool the file no longer declares.
     *
     * Like [ungroup] this drops the recorded step outright rather than replaying — a `macrodef` step
     * creates no geometry, so nothing else has to change for the script to stay valid.
     */
    fun removeMacro(def: MacroDef): Boolean {
        if (instancesOf(def).isNotEmpty()) return false
        if (!macroDefs.remove(def)) return false
        def.step?.let { s -> journal.removeAll { it === s } }
        return true
    }

    /**
     * Instantiate [def] with the clicked [args] and the panel [scalarArgs] (OP-6).
     *
     * **The instance is a view, not a copy.** Every definition node is mapped once:
     * - a designated input maps to the *argument* node — nothing is bound and nothing is rewritten;
     * - an internal free point maps to a node holding the definition's position **offset by
     *   (this instance's anchor − the definition's anchor)**, which is what stamps the instance under
     *   the click while keeping it tied to the original's layout;
     * - any other free source (a parameter, a constant, a slider's own DOF) maps to the definition's own
     *   node — a captured default *shared* by every instance (OP-6);
     * - everything derived maps to an [constructit.core.InstanceNode] over the same computation with its
     *   inputs mapped, addressed `M/nk`.
     *
     * So editing the definition — dragging one of its internal points, retyping a captured parameter —
     * re-propagates to every instance on the next pass, with nothing to synchronize. And an instance has
     * no freedom of its own beyond its arguments, which is OP-6's purity rule made structural rather than
     * enforced: its elements carry no handle, because there is no node of theirs to write.
     */
    fun instantiateMacro(
        def: MacroDef,
        args: List<PointRef>,
        scalarArgs: List<ScalarRef>,
    ): List<Element> {
        if (args.size < def.pointInputs.size || scalarArgs.size < def.scalarInputs.size) return emptyList()
        val instanceId = "M${++instanceCounter}"
        val bound = HashMap<String, Node>()
        def.pointInputs.forEachIndexed { i, el -> bound[el.ref.node.id] = args[i].node }
        def.scalarInputs.forEachIndexed { i, e -> bound[e.ref.node.id] = scalarArgs[i].node }
        val defAnchor = def.pointInputs[0].ref.node
        val anchor = args[0].node
        val orthoAxes = orthoCoordinateAxes()
        val mapped = HashMap<String, Node>()

        fun map(n: Node): Node {
            bound[n.id]?.let { return it }
            mapped[n.id]?.let { return it }
            val free = n.takeIf { boundMaster(it) == null && (it is SourceNode || it is ParameterNode) }
            val out =
                when {
                    free is SourceNode && free.value is PointValue ->
                        cx.instanceCapturedPoint(instanceId, free, defAnchor, anchor)
                    free != null && orthoAxes[free.id] != null ->
                        cx.instanceCapturedCoord(instanceId, free, defAnchor, anchor, orthoAxes[free.id]!!)
                    // a shared captured default: the definition's own node, so an edit re-propagates
                    free != null -> free
                    else -> cx.instanceNode(instanceId, n, n.inputs.map { map(it) })
                }
            mapped[n.id] = out
            return out
        }

        val created =
            def.outputs.map { el ->
                val point = el.isPoint
                // purity (OP-6): an instance point is *derived* — its DOF is the definition's or an
                // argument's, so it must not present a handle of its own
                add(
                    Ref<Value>(map(el.ref.node)),
                    if (point) ElementKind.DERIVED_POINT else el.kind,
                    if (point) Styles.DERIVED_POINT else el.style,
                )
            }
        macroInstanceList.add(MacroInstance(instanceId, def, created))
        return created
    }

    /** The node a source is bound to (welded / wired / framed), or null while it is a free DOF. */
    private fun boundMaster(n: Node): Node? =
        when (n) {
            is SourceNode -> n.boundTo
            is ParameterNode -> n.boundTo
            else -> null
        }

    /**
     * An ortho vertex's coordinate sources, by axis (0 = x, 1 = y). A rectilinear path holds a position
     * as two *shared scalars* rather than as a point value (OP-19/OP-20), so those are the one other kind
     * of source an instance has to translate — otherwise a tool made from a wall would stamp every
     * instance back onto the original.
     */
    private fun orthoCoordinateAxes(): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (p in orthoPaths) {
            for (v in p.vertices) {
                out[v.corner.xNode.id] = 0
                out[v.corner.yNode.id] = 1
            }
        }
        return out
    }

    /**
     * The macro definitions the delete of [roots] (closure [dropped]) would take away, each with the
     * instance elements that would go down with them — what a delete has to refuse
     * (see `Editor.deleteSelection`).
     *
     * An instance the user selected **himself** is not a casualty: he asked for it to go, so deleting a
     * definition together with its instances is allowed in one operation. Only instances that would be
     * taken *silently* make the delete a refusal.
     */
    fun macroLosses(
        roots: Set<Step>,
        dropped: Set<Step>,
    ): List<Pair<MacroDef, List<Element>>> {
        val droppedEls = dropped.flatMapTo(HashSet()) { it.creates }
        return macroDefs.mapNotNull { def ->
            val hit = def.step in dropped || def.elements.any { it in droppedEls }
            val casualties =
                instancesOf(def).flatMap { it.elements }.filter { el -> creatingStep(el)?.let { it in roots } != true }
            if (hit && casualties.isNotEmpty()) def to casualties else null
        }
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

    /**
     * The source nodes that connecting [el] — welding or attaching it — would bind.
     *
     * For a free point that is the point's own node. For an **ortho corner it is not the corner's point
     * node at all** but the *masters* of its two coordinate chains, which sit upstream of it: a corner is
     * `pointXY(x, y)` and a connection re-points what `x` and `y` ultimately resolve to (see
     * [writableMaster]). Anything asking "would this connection cycle?" has to ask about *these* nodes.
     */
    private fun bindableNodes(el: Element): List<SourceNode> {
        (el.handle as? OrthoCornerHandle)?.let { corner ->
            return listOfNotNull(writableMaster(corner.xNode), writableMaster(corner.yNode)).distinct()
        }
        return listOfNotNull(literalNode(el)?.takeIf { it.boundTo == null })
    }

    /**
     * True when connecting [el] to something driven by [driver] would make the graph cyclic — because
     * [driver] already depends on a node the connection would bind.
     *
     * Testing the *dragged point* instead of what the connection binds let a real cycle through, and a
     * cyclic DAG is not a wrong drawing but a dead one: [Evaluator] recurses until the stack dies, taking
     * the whole editor with it. In a cross of four runs welded at one centre, the centre's y *is* the
     * first run's y (that run introduced it), so dropping that run's far end anywhere near the figure
     * welded it onto a point derived from itself and killed the drawing.
     */
    private fun joinWouldCycle(
        el: Element,
        driver: Node,
    ): Boolean = bindableNodes(el).any { dependsOn(driver, it, HashSet()) }

    /**
     * True when joining [el] onto [target] would be circular, so neither the magnet may offer it nor a
     * release perform it — the two must agree, or the halo promises a join that release refuses.
     */
    fun joinWouldCycle(
        el: Element,
        target: Element,
    ): Boolean = joinWouldCycle(el, target.ref.node)

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

    /**
     * Free the parameter again, keeping its current (last driven) value — recorded as an `unbind` step,
     * so the file says it happened.
     *
     * **The step is the fix to a hole this package found.** Unbinding used to record nothing at all, while
     * the `wire` step that created the bond stayed in the journal — so a parameter freed in the panel came
     * back wired on the next load, silently. A new step kind costs no version bump (OP-18: no stored literal
     * changed its meaning), and the value it restates is the parameter's own **literal**, for the reason the
     * `param` step restates its number: what the user typed after freeing it is state.
     *
     * [value] is what replay hands back; live callers pass null and get the value the binding last drove.
     */
    fun unwireParameter(
        e: ScalarEntry,
        value: Quantity? = null,
    ): Boolean =
        recording("unbind", Arg.Sc(e), Arg.Text("="), Arg.Num(value ?: literalOf(e)), skipIfEmpty = true) {
            val node = e.ref.node as? ParameterNode
            if (node == null || (node.boundTo == null && value == null)) {
                false
            } else {
                val cur = value ?: (Evaluator().eval(node) as? EvalResult.Ok)?.let { (it.value as ScalarValue).q }
                if (cur != null) node.literal = ScalarValue(cur)
                node.boundTo = null
                noteEdit()
                true
            }
        }

    // ---- expressions: the binding generalized to a pure function of named scalars (OP-7, session 71) ----

    /**
     * What one name inside an expression **resolved to** (OP-7/OP-18's naming authority): a named scalar's
     * panel row, or a named point's **coordinate** (`P.x` — the session-76 entry, item a).
     *
     * Held **by identity** rather than by the name it was written under, which is what makes a rename a
     * re-stamp rather than an orphaned reference: [currentName] asks the thing itself what it is called now.
     *
     * The coordinate case is deliberately **one direction only**. An expression *reads* a coordinate — the
     * node is an ordinary accessor over the point's own node, so a drag of the point recomputes the formula
     * like any other edit — and nothing here ever writes one. A point whose coordinate should *be* an
     * expression is a different feature (it would take a freedom away from the point), and it is reachable
     * from nowhere in this build: the panel's formula field takes a [ScalarEntry], and a point's x and y are
     * an element's *handle fields*, not rows. Recorded as a future extension rather than refused, because
     * there is no gesture to refuse.
     */
    class ExprRef internal constructor(
        /** The scalar row this name is, or null when it is a point's coordinate. */
        val entry: ScalarEntry?,
        /** The point whose coordinate this name is, or null when it is a scalar row. */
        val point: Element?,
        /** 0 for `.x`, 1 for `.y`; -1 for a scalar row. */
        val axis: Int,
        /** The scalar-valued node the expression reads — the row's own node, or the coordinate accessor. */
        internal val node: Node,
    )

    /** How [r] is written **now** — what a re-stamp puts back into the text, or null when it has no name left. */
    private fun currentName(r: ExprRef): String? =
        r.entry?.name ?: r.point?.let { p -> userNameOf(p)?.let { "$it.${if (r.axis == 0) "x" else "y"}" } }

    /** The coordinate suffixes a point publishes — see [resolveExprName]. */
    private val coordinateSuffixes = listOf("x", "y")

    /**
     * The **naming authority extended to coordinates** (the session-76 entry, item a): what the name [n]
     * inside an expression refers to, or null with [note] left unset for the caller to phrase.
     *
     * Two forms, and the precedence between them is a decision rather than a fallback order:
     * - one word is a **scalar row**, exactly as before;
     * - a **dotted** word is always `<point>.x` / `<point>.y` — a coordinate — *whatever the drawing carries*.
     *
     * The alternative was to look a dotted name up among the scalars first (a scalar may be *called* `wall.x`,
     * since [scalarWord] only forbids spaces and quotes) and to read it as a coordinate only when no such row
     * exists. That is rejected on the parser's own load-bearing ground: it would put the **drawing** into what
     * a stored text means, so renaming a row to `wall.x` would silently steal every text that reads the point
     * `wall`'s x — the frozen-literal hazard (OP-18) that the no-reserved-words rule exists to avoid, one
     * level up. This rule costs nothing stored: a dot has never parsed, so no loadable file can contain one.
     * A row whose own name carries a dot therefore stays unspellable, exactly as a hyphenated one is, and
     * [unknownName] says so with the cure.
     */
    private fun resolveExprName(n: String): ExprRef? {
        val dot = n.lastIndexOf('.')
        if (dot < 0) return scalars.firstOrNull { it.name == n }?.let { ExprRef(it, null, -1, it.ref.node) }
        val axis = coordinateSuffixes.indexOf(n.substring(dot + 1))
        if (axis < 0) return null
        val el = elements.firstOrNull { userNameOf(it) == n.substring(0, dot) } ?: return null
        val node = coordinateNode(el, axis) ?: return null
        return ExprRef(null, el, axis, node)
    }

    /**
     * [el]'s [axis] coordinate as a scalar node — a **length**, read off the point the drawing already has,
     * so the expression takes an ordinary DAG edge and no freedom is created or removed.
     *
     * Null when [el] is not a point of the plane. A point **in space** is the case worth naming: its
     * coordinates are in *world* space while every `Vec2` in this engine means "in some plane's own
     * coordinates" (OP-17), so answering `.x` for one would silently mix two frames. It refuses by name and
     * `.z` with a stated space is the future extension.
     */
    private fun coordinateNode(
        el: Element,
        axis: Int,
    ): Node? {
        if (!el.isPoint) return null
        val ref = el.ref as? PointRef ?: return null
        if (Evaluator().valueOf(ref) !is PointValue) return null
        return cx.pointCoordinate(ref, axis).node
    }

    /** Why the dotted name [n] resolves to nothing, with the cure — see [resolveExprName] and [unknownName]. */
    private fun unknownCoordinate(n: String): String {
        val dot = n.lastIndexOf('.')
        val what = n.substring(0, dot)
        val suffix = n.substring(dot + 1)
        if (coordinateSuffixes.indexOf(suffix) < 0) {
            return "there is no value named '$n' — a '.' in an expression reads a point's coordinate, and a " +
                "point has ${coordinateSuffixes.joinToString(" and ") { ".$it" }}, not '.$suffix'"
        }
        scalars.firstOrNull { it.name == n }?.let {
            return "there is no value named '$n' — the value called '$n' cannot be written in an expression (a " +
                "'.' in a name reads a point's coordinate), so rename it to one word of letters and digits first"
        }
        val named = elements.firstOrNull { userNameOf(it) == what }
        if (named == null) {
            return "there is no point named '$what' — '$n' reads the $suffix of a point, so name the point in " +
                "the panel first (its script name is not a name you can spell here)"
        }
        // a point **in space** is the case worth its own sentence, and it is the one every route that reads
        // plane coordinates already speaks: `.x` would have to answer in *world* coordinates while every
        // `Vec2` here means "in some plane's own" (OP-17), so the two frames would silently mix
        notInThePlane(
            named,
            "a coordinate read in a formula",
            "read the '.x' or '.y' of a point of the plane; a point in space is followed by *building* on it",
        )?.let { return it }
        return "${displayName(named)} has no $suffix to read — '$n' reads a coordinate of a point of the plane, " +
            "and ${nameOf(named)} is ${kindWord(named)}"
    }

    /**
     * One live expression binding: the [entry] it drives, the [node] under its `boundTo`, and what its
     * names resolved to — **by identity**, which is what makes a rename a re-stamp rather than an orphaned
     * reference.
     *
     * [refs] is parallel to `node.names` (the distinct names, in input order), not to the occurrences
     * in the text; an occurrence finds its entry through its name's index.
     */
    class ExprBinding internal constructor(
        val entry: ScalarEntry,
        val node: ExprNode,
        val refs: List<ExprRef>,
    )

    /** The binding each `bind` step made — per step, since a parameter may be re-bound later. */
    private val exprBindings = HashMap<Step, ExprBinding>()
    private var pendingExprBinding: ExprBinding? = null

    /** The binding [step] recorded, or null — how the writer restates that step's own text. */
    internal fun expressionBinding(step: Step): ExprBinding? = exprBindings[step]

    /**
     * The expression currently driving [e] — under the **current** names of everything it reads — or null
     * when [e] is free or plainly wired.
     */
    fun expressionOf(e: ScalarEntry): String? = liveBinding(e)?.node?.text

    /** The binding actually in force for [e]: the one whose node its parameter is bound to right now. */
    private fun liveBinding(e: ScalarEntry): ExprBinding? {
        val bt = (e.ref.node as? ParameterNode)?.boundTo ?: return null
        return exprBindings.values.firstOrNull { it.node === bt }
    }

    /**
     * The expression driving [node], as `name = text`, or null — what a refusal quotes when a drag or a
     * typed value lands on a derived parameter ("the wired height's own words", OP-25).
     */
    fun expressionDriving(node: Node): String? {
        val bound = (node as? ParameterNode)?.boundTo ?: return null
        val b = exprBindings.values.firstOrNull { it.node === bound } ?: return null
        return "${b.entry.name} = ${b.node.text}"
    }

    /** The literal [e] carries while free — what an `unbind` step restates (see [unwireParameter]). */
    internal fun restatedLiteral(e: ScalarEntry): Quantity = literalOf(e)

    private fun literalOf(e: ScalarEntry): Quantity =
        (e.ref.node as? ParameterNode)?.literal?.q
            ?: (Evaluator().eval(e.ref.node) as? EvalResult.Ok)?.let { (it.value as? ScalarValue)?.q }
            ?: Quantity.mm(0.0)

    /**
     * Bind [e] to the expression [text] — `boundTo` generalized from *one other node* to a pure function
     * of named scalars (OP-7, the session-71 entry). One direction: `a = b + c` **defines** `a`, it does
     * not assert an equation, so this is an ordinary set of DAG edges and no solver is anywhere near it.
     *
     * Everything it can refuse, it refuses **by name** and before anything is rewired:
     * - a text that is not an expression — the position and what was expected there;
     * - a name nothing in the drawing carries (and, for the one case a user will actually hit, a name that
     *   *is* there but cannot be written in an expression at all: a hyphenated one would read as a
     *   subtraction, so it is named and the cure is a rename);
     * - a **cycle**, at bind time rather than as a hang — the DAG's own rule, asked of the reference.
     *
     * A dimension violation is deliberately *not* refused here: it is a property of the **values**, so it
     * is the `DimensionError` the evaluator turns into named invalidity that heals (OP-3), exactly as a
     * degenerate intersection is. Binding `r = d/2 + 1deg` therefore succeeds and the circle says why it
     * cannot be built, and correcting `d` heals it.
     */
    fun bindParameter(
        e: ScalarEntry,
        text: String,
    ): Boolean =
        recording("bind", Arg.Sc(e), Arg.Text("="), Arg.Label(text), skipIfEmpty = true) {
            bindParameterNow(e, text)
        }

    private fun bindParameterNow(
        e: ScalarEntry,
        text: String,
    ): Boolean {
        val node = e.ref.node as? ParameterNode
        if (node == null || !e.editable) {
            note = "${e.name} is not a parameter that can be given a formula — it is measured by the construction (OP-4)"
            return false
        }
        val ast =
            try {
                ExprParser.parse(text)
            } catch (err: ExprError) {
                note = "Can't read '${text.trim()}': ${err.message}"
                return false
            }
        // the drawing's own names win over the constants, so a parameter named `PI` is read as itself and
        // a name nothing carries falls through to the evaluator, which knows what a constant is
        val bound = ArrayList<String>()
        val refs = ArrayList<ExprRef>()
        for (n in ast.refNames()) {
            val target = resolveExprName(n)
            if (target == null) {
                if (n in EXPR_CONSTANTS) continue
                note = "Can't bind ${e.name}: ${unknownName(n)}"
                return false
            }
            // one cycle rule for both reference kinds, over the same `dependsOn` walk every connection is
            // checked with: a coordinate read from a point that the value being bound already helps to place
            // (a rider on a circle of this very radius) would close a loop through *geometry*, and the DAG
            // refuses it here by name rather than as a hang
            if (dependsOn(target.node, node, HashSet())) {
                note = "Can't bind ${e.name} to '$text': $n already follows ${e.name}, and a value cannot be derived from itself"
                return false
            }
            bound.add(n)
            refs.add(target)
        }
        val exprNode = ExprNode(nextId("ex"), text, ast, bound, refs.map { it.node })
        node.boundTo = exprNode
        pendingExprBinding = ExprBinding(e, exprNode, refs)
        noteEdit()
        return true
    }

    /**
     * Why a name in an expression resolves to nothing, with the cure where there is one. Two cases are
     * worth saying out loud, because in both the bare sentence would send the user hunting for a typo he
     * did not make:
     *
     * - a **hyphenated** parameter — `wall-width` is one scalar name and two expression tokens, so the
     *   parser split it before anything could look it up;
     * - a **function written without its arguments** — `sqrt` is a name like any other here (the parser
     *   reserves nothing, see [ExprParser]), so nothing carries it and the answer is *this is the
     *   function, and a function is called*;
     * - a **dotted** name, which reads a point's coordinate and has three ways of missing — see
     *   [unknownCoordinate].
     */
    private fun unknownName(n: String): String {
        if (n.contains('.')) return unknownCoordinate(n)
        val hidden = scalars.firstOrNull { it.name.startsWith("$n-") || it.name.startsWith("$n.") }
        return when {
            hidden != null ->
                "there is no value named '$n' — '${hidden.name}' cannot be written in an expression (a '-' in a name reads as " +
                    "a subtraction), so rename it to one word of letters and digits first"
            n in EXPR_FUNCTIONS -> "there is no value named '$n' — '$n' is a function, so write '$n(…)' with its arguments"
            else -> "there is no value named '$n'"
        }
    }

    /**
     * Re-stamp every stored expression under the **current** names of what it reads (OP-18's naming
     * authority, and the move OP-23 makes for a pattern's count): a rename rewrites the reference *spans*
     * of the text and leaves every other character of it alone, so a saved expression is still the user's
     * own text and still resolves.
     *
     * The alternative — refusing the rename and naming the expressions that read the parameter — was
     * rejected because the file already restates every *other* mention of a scalar under its current name
     * (OP-7: "one rename restates the whole file consistently"), and an expression is a mention.
     */
    private fun restampExpressions() {
        for (b in exprBindings.values) {
            b.node.text = restamped(b.node.source, b.node.ast, b.node.names, b.refs)
        }
        restampFuncCurves()
        restampSweepLaws()
    }

    /**
     * The same re-stamp over a **variable section's size law** (OP-26, session 77) — extended here for the
     * reason the function curves were: a rename that reached a parameter binding but not a law would leave
     * the body live and its *file* unloadable, which is exactly the failure the scalar half's probe found.
     */
    private fun restampSweepLaws() {
        for (b in sweepLaws.values) {
            b.text = restamped(b.source, b.ast, b.names, b.refs)
        }
        // …and a **family's** laws on **both** sides (OP-26, session 79): the names inside each text, exactly
        // as above, *and* the driven name on the left of each `=`, which is a reference to a scalar row like
        // any other. One-sided would leave the body live and its file unloadable — the failure the scalar
        // half's own probe found, and the reason this pass exists at all.
        for (b in sweepFamilies.values) {
            for (e in b.entries) {
                e.target?.let { e.driven = it.name }
                val ast = e.ast ?: continue
                e.text = restamped(e.source, ast, e.names, e.refs)
            }
        }
    }

    /**
     * The same re-stamp over a **function curve's two texts** (session 71, curve half) — extended here
     * deliberately, because a rename that reached a parameter binding but not a curve would leave the curve
     * live and its *file* unloadable, which is precisely the failure the scalar half's probe found.
     */
    private fun restampFuncCurves() {
        for (b in funcCurves.values) {
            b.xText = restamped(b.xSource, b.xAst, b.names, b.refs)
            b.yText = restamped(b.ySource, b.yAst, b.names, b.refs)
            // ...and the two **domain** ends, which are expressions of the same kind (session-76 item b): a
            // rename that reached the coordinates but not the domain would leave the curve live and its
            // *file* unloadable, which is the scalar half's own probe lesson stated one argument on
            for (d in listOf(b.from, b.to)) {
                val ast = d.ast ?: continue
                d.text = restamped(d.source ?: continue, ast, d.names, d.refs)
            }
        }
    }

    /** [source] with every reference span rewritten under its target's current name, and nothing else. */
    private fun restamped(
        source: String,
        ast: Expr,
        names: List<String>,
        refs: List<ExprRef>,
    ): String {
        val out = StringBuilder()
        var at = 0
        for (r in ast.refs()) {
            val k = names.indexOf(r.name)
            val now = refs.getOrNull(k)?.let { currentName(it) } ?: continue
            out.append(source, at, r.start).append(now)
            at = r.end
        }
        out.append(source, at, source.length)
        return out.toString()
    }

    fun moveFreePoint(
        el: Element,
        world: Vec2,
    ) {
        require(el.kind == ElementKind.POINT) { "not a free point" }
        el.handle?.drag(world, Evaluator())
    }

    // ---- welding: join two points by aliasing one onto the other (point-level wiring) ----

    /**
     * True if [el] is a free point currently welded onto a master.
     *
     * A *framed* point is bound too (onto its frame — OP-16 step 2) but is not an alias of anything: it
     * stays visible and draggable, so the two cases must not be confused (hiding one is by construction,
     * placing one is not).
     */
    fun isWelded(el: Element): Boolean =
        el.kind == ElementKind.POINT && literalNode(el)?.boundTo != null && !isFramed(el) &&
            relativeOf(el) == null

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
    ): Boolean = recording("weld", Arg.El(alias), Arg.El(master), skipIfEmpty = true) { weldNow(alias, master) }

    private fun weldNow(
        alias: Element,
        master: Element,
    ): Boolean {
        // A **point in space** on either side is refused by name (see [notInThePlane]), and the master side is
        // the one that needed saying: a weld binds the alias's own literal to the master's node, so a master
        // whose value is a `Point3Value` would leave a plane point reading a point in space and every consumer
        // of it invalid, with nothing anywhere naming the cause. The pick is legitimate to *make* — a rider's
        // dot is drawn in the plan and clickable there (OP-26, session 53) — so the refusal belongs here.
        for (p in listOf(alias, master)) {
            notInThePlane(
                p,
                "a join",
                "join two points of the plane; a point in space is followed by *building* on it — a curve " +
                    "through it, or a height point over the base it stands on",
            )?.let {
                note = it
                return false
            }
        }
        val node = literalNode(alias) ?: return false
        if (alias.kind != ElementKind.POINT || node.boundTo != null) return false
        if (!master.isPoint || master === alias) return false
        val masterNode = master.ref.node
        if (masterNode === node || joinWouldCycle(alias, masterNode)) return false // no cycles
        node.boundTo = masterNode
        alias.visible = false
        noteEdit()
        // said here rather than only at the drag magnet's release, so the *Join points* tool speaks too
        // (GitHub #9's silent-success sweep) — one sentence, whichever route reached it
        note = "Joined ${nameOf(alias)} onto ${nameOf(master)}"
        return true
    }

    /**
     * Un-weld / detach: the point resumes as an independent free point **at its current position**.
     *
     * [dofs] is that position as a replay hands it back (OP-18) — the same `dofs=` seam every other
     * re-parameterization rides ([relativeDofs]). It is needed, and the need is not obvious: replay runs the
     * `weld` step first, so by the time this one runs the point *reads* its master's position, and re-deriving
     * from that would drag every point ever unlinked-and-then-moved back onto the master it left. So the freed
     * position is **state on this step**, exactly as a freed rider's coordinates are ([detachRider]).
     *
     * Absent — an older file, written before the step restated anything — the current value is read as before,
     * which is what such a file meant: nothing had moved since. No stored literal changed meaning, so no
     * format version bump is owed (OP-18's versioning rule).
     */
    fun unweld(
        alias: Element,
        dofs: List<Quantity> = emptyList(),
    ) = recording("unweld", Arg.El(alias), skipIfEmpty = true) { unweldNow(alias, dofs) }

    private fun unweldNow(
        alias: Element,
        dofs: List<Quantity> = emptyList(),
    ) {
        val node = literalNode(alias) ?: return
        val lengths = dofs.filter { it.dim == Dimension.LENGTH }
        val cur =
            if (lengths.size == 2) {
                Vec2(lengths[0].mm, lengths[1].mm)
            } else {
                (Evaluator().eval(node) as? EvalResult.Ok)?.let { (it.value as? PointValue)?.p }
            }
        val was = node.boundTo != null
        if (was) noteEdit()
        node.boundTo = null
        relatives.remove(alias.id)
        if (cur != null) node.value = PointValue(cur)
        alias.kind = ElementKind.POINT
        alias.handle = FreePointHandle(node) // an independent free point again, handle included
        alias.style = Styles.FREE_POINT
        // back to visible, pickable life — **unless it is hidden by construction for the other reason**: a
        // boundary's duplicate joint marker is hidden because a point element already stands there (OP-14),
        // which has nothing to do with the weld and does not end with it. One predicate, asked rather than
        // assumed ([hiddenByConstruction]), so the inverse of a weld cannot resurrect a marker.
        alias.visible = alias.id !in duplicateJointMarkers
        if (was) {
            // it rides nothing any more. A point *drag-attached* onto a curve carries a rider record and a
            // registration for the gesture-time compensation a carrier-anchored parameter needs (OP-20); both
            // describe a bond that has just ended, and leaving them behind left a free point still claiming a
            // host — the same clean-up [detachRider] does for the other shape a rider can have.
            riders.remove(alias.id)?.let { rec -> carrierRiders.removeAll { it.dof === rec.param } }
            // the position it now owns is state this step must restate (OP-18) — see [unweld], [relativeDofs]
            unwelded.add(alias.id)
            noteReparam(alias)
        }
    }

    /**
     * Points whose own literal was handed back by [unweldNow], by element id: what tells [relativeDofs] that
     * the step which freed them owns their coordinates from then on.
     *
     * A set rather than a flag on the element for the reason every other such record here is one — an element
     * is a view of the graph, and what a *step* must restate is the step's business (OP-18).
     */
    private val unwelded = HashSet<String>()

    /**
     * **Unlink — the inverse of Join** (GitHub issue #10): the point named leaves the bond that drives it and
     * is a free degree of freedom again, right where it stands.
     *
     * The bond is the `boundTo` substrate welding is built on (OP-16/OP-5): a point joined to another point
     * with *Join*, one drag-welded onto another by the magnet, or one drag-attached onto a curve. All three are
     * "this point's own source is driven by something else", and all three end the same way — the driven value
     * is read out and **restated as the node's own**, so nothing moves at the moment of the change ("a click is
     * a choice, state restates as a value"). Everything built on the point keeps working and simply stops
     * following.
     *
     * **Where several points were joined into one, only the named one leaves.** That falls out of the
     * substrate rather than being arranged: each alias holds its own `boundTo`, so clearing one says nothing
     * about the others. It is also why the gesture is *on the selection* — the merged dot is one visual point,
     * and a click cannot say which of the points under it is meant, while the element tree can.
     *
     * The two neighbours it must not be confused with, and why each is answered as it is:
     *
     * - A **rider** whose freedom is a parameter along its host (the *Point on line* / *Point on circle*
     *   tools) is not welded — its position is published through a re-pointable view, and freeing it is
     *   [detachRider]'s conversion. Unlink **delegates** there rather than refusing, because a rider created
     *   by a tool and a point drag-attached onto the same curve are indistinguishable on screen (both are
     *   `ON_CURVE`, both slide along it) and the second one *is* inside this substrate: refusing one and
     *   freeing the other would be a distinction the user has no way to see.
     * - A **relative point** (OP-4 case b) is bound too, and is *not* unlinked — it is refused, naming its
     *   anchor and the conversion that undoes it. Nothing drives it: its two degrees of freedom are still its
     *   own, written as a distance and an angle instead of as x and y. "Give this point plain coordinates
     *   back" is a different request from "let this point go", and *Make absolute* is the tool that says it.
     */
    fun unlink(
        pt: Element,
        dofs: List<Quantity> = emptyList(),
    ): Boolean {
        // Deliberately **not** wrapped in a `recording` of its own: what this performs is one of two existing
        // operations, and each records the step that replays *it* (`unweld`, or `absolute` for the rider it
        // delegates to). A wrapper here would record a third kind whose replay could only guess which of the
        // two was meant. A refusal records nothing at all, since it rewires nothing.
        if (!pt.isPoint) {
            note = "${nameOf(pt)} is ${kindWord(pt)}, not a point — Unlink frees a point that was joined to something"
            return false
        }
        val node = literalNode(pt)
        if (node != null && node.boundTo != null) {
            relativeOf(pt)?.let { r ->
                note =
                    "${nameOf(pt)} is not joined to anything: it is measured from ${nameOf(elementFor(r.anchor) ?: pt)} " +
                    "(a distance and an angle of its own) — Make absolute gives it plain coordinates back"
                return false
            }
            if (isFramed(pt)) {
                val g = placedGroupOf(pt) ?: allGroups.firstOrNull { grp -> grp.captures.any { it.original === node } }
                note =
                    "${nameOf(pt)} is placed by group ${g?.name ?: "it belongs to"}, not joined to another point — " +
                    "move or unplace the group to free it"
                return false
            }
            val to = node.boundTo?.let { labelOf(it) }
            unweld(pt, dofs)
            note = "${nameOf(pt)} is a free point again, where it stands${if (to == null) "" else " — it no longer follows $to"}"
            return true
        }
        // a rider the *tool* created: not welded, but the same request — see this function's note
        val rec = riderOf(pt)
        if (node == null && rec != null) {
            if (makeAbsolute(pt, dofs)) return true
            note = note ?: "${nameOf(pt)} rides ${nameOf(rec.host)} and could not be freed"
            return false
        }
        note =
            if (node != null) {
                "${nameOf(pt)} is already free — it is joined to nothing"
            } else {
                val from = Dependencies.inputsOf(this, pt).map { nameOf(it.element) }
                "${nameOf(pt)} is derived by the construction" +
                    (if (from.isEmpty()) "" else " from ${from.joinToString(" and ")}") +
                    ", so there is no bond to leave and no position of its own to hand back"
            }
        return false
    }

    /**
     * The word a refusal uses for what an element *is* — "a circle", "an elliptic arc".
     *
     * Public because healing speaks the same language as refusing (OP-3): the sentence that says a body is
     * back — *"e32 is a solid again"* — is the same sentence one word further on.
     */
    fun kindWord(el: Element): String {
        if (el.kind == ElementKind.FUNC_CURVE) return "a function curve"
        val w = el.kind.name.lowercase().replace('_', ' ')
        return (if (w.first() in "aeiou") "an " else "a ") + w
    }

    /**
     * Why [el] cannot stand where a point **of the working plane** is wanted, or null when it can — one
     * sentence for the whole class, in [linearDimension]'s own shape: name the element, say what it is, say
     * what to do [instead].
     *
     * **The rule this states: a route that reads plane coordinates off a point refuses a point in space by
     * name.** A point in space ([Element.inSpace] — a height point, a key point of a curve in space, a point
     * riding a coil) is *drawn* in the plan where it projects, and has been pickable there since OP-26's key
     * points (session 53) — which is right for everything that wants the point in space it is, and never right
     * for anything that would read the plane point at its projection, since that is a different point. Session
     * 53 stated the half a *placing* click needs (the snap resolver asks a 2D question and therefore cannot see
     * one at all); this is the half every *build* needs, and it is one helper rather than a habit so the next
     * such route cannot forget. [what] names the reading that cannot be made of it.
     */
    private fun notInThePlane(
        el: Element,
        what: String,
        instead: String,
    ): String? =
        if (!el.inSpace) {
            null
        } else {
            "${nameOf(el)} is a point in space, and $what is stated in the sketch plane's own coordinates — $instead"
        }

    // ---- relative points: re-parameterize a free point onto an anchor (OP-4 case b) ----

    /**
     * A point re-parameterized as **an offset from another point**: `P = anchor + PolarVector(distance,
     * angle)` (OP-4 case b). Its two degrees of freedom are those two scalars, so nothing is lost and
     * nothing is asserted — the point simply says what it always meant.
     */
    class RelativePoint(val anchor: PointRef, val distance: SourceNode, val angle: SourceNode)

    /** Points re-parameterized onto an anchor, by element id — see [makeRelative]. */
    private val relatives = HashMap<String, RelativePoint>()

    /** How [el] is anchored, if it has been made relative. */
    fun relativeOf(el: Element): RelativePoint? = relatives[el.id]

    // ---- relative ortho vertices: OP-4 case (b) on a path's coordinate chains (GitHub issue #23) ----

    /** Ortho vertices whose coordinates were re-anchored, by element id — see [makeRelativeOrtho]. */
    private val orthoRelatives = HashMap<String, List<OrthoOffset>>()

    /** The same records by the id of the coordinate source they own, for [orthoOffsetOf]. */
    private val orthoOffsetByOwner = HashMap<String, OrthoOffset>()

    /**
     * Coordinate sources an ortho vertex got its literal back for ([makeAbsoluteOrtho]), by element id.
     *
     * The freed literals are **state on the `absolute` step** for [unweld]'s reason: the `relative` step
     * replaying before it has just bound them, so a step that restated nothing would put the vertex back
     * where the anchor holds it.
     */
    private val orthoFreed = HashMap<String, List<SourceNode>>()

    /** How [el]'s coordinates are anchored, if it is an ortho vertex that was made relative. */
    fun orthoRelativeOf(el: Element): List<OrthoOffset> = orthoRelatives[el.id] ?: emptyList()

    /**
     * The offset owning coordinate [node]'s value, if its chain of bindings ends at one — the same
     * structural one-hop lookup [junctionOf] is, and for the same reason: a handle whose coordinate is
     * driven has to find the freedom that moves it without searching.
     *
     * It answers for **every vertex sharing that chain**, which is the point: the leg whose length the
     * offset now states has two ends, and dragging either of them along that axis is the same edit.
     */
    fun orthoOffsetOf(node: SourceNode): OrthoOffset? {
        var n = node
        var guard = 0
        while (guard++ < 64) {
            orthoOffsetByOwner[n.id]?.let { return it }
            n = n.boundTo as? SourceNode ?: return null
        }
        return null
    }

    /** The offsets reachable through [corner] — the coordinate freedoms an anchored vertex has. */
    fun orthoOffsetsOf(corner: OrthoCornerHandle): List<OrthoOffset> =
        listOf(corner.xNode, corner.yNode).mapIndexedNotNull { axis, n -> orthoOffsetOf(n)?.takeIf { it.axis == axis } }

    /**
     * The offsets of [corner] as typed fields, named after the anchor they are measured from — a relative
     * point's `distance` field, for the DOF a re-anchored coordinate has (OP-13).
     */
    fun orthoOffsetFields(corner: OrthoCornerHandle): List<HandleField> =
        orthoOffsetsOf(corner).map { o ->
            val a = elementFor(o.anchor)?.let { nameOf(it) } ?: "anchor"
            HandleField(
                "offset from $a along ${axisName(o.axis)}",
                o.offset,
                Dimension.LENGTH,
                { ev -> ((ev.eval(o.offset) as? EvalResult.Ok)?.value as? ScalarValue)?.q },
                { q -> o.offset.literal = ScalarValue(q) },
                // …and it refuses exactly as far as the drag does: a driven offset is not this vertex's
                // freedom any more, and a field that wrote a literal nothing reads would be a no-op (OP-13)
                writableWhen = { o.writable },
            )
        }

    /**
     * The offset's own panel row, created by the step that anchors the coordinate and owned by it — the
     * pattern an opening's `pos`/`sill`/`head` already follow (OP-21): the script names it nowhere, so
     * replay recreates it under the same generated name and no rename can orphan a reference.
     */
    private fun offsetParameter(
        axis: Int,
        d: Double,
    ): Pair<ParameterNode, ScalarEntry> {
        val node = ParameterNode(nextId("op"), ScalarValue(Quantity.mm(d)))
        val e = ScalarEntry(nextId("s"), uniqueScalarName(if (axis == 0) "dx" else "dy"), Ref<ScalarValue>(node), editable = true)
        scalars.add(e)
        return node to e
    }

    /**
     * What else reads [o]'s offset — a parameter wired to it, a formula, a curve whose text names it.
     *
     * Asked with the vertex's **own** owner seeded into the visited set, so the walk stops there: everything
     * downstream of the coordinate legitimately depends on the offset (that is what the anchoring means), and
     * only a reader that arrives by some *other* route is a reference [makeAbsoluteOrtho] would orphan.
     */
    private fun offsetReaders(o: OrthoOffset): List<String> =
        scalars.filter { it !== o.entry && dependsOn(it.ref.node, o.offset, hashSetOf(o.owner.id)) }.map { it.name } +
            elements.filter { dependsOn(it.ref.node, o.offset, hashSetOf(o.owner.id)) }.map { nameOf(it) }

    private fun axisName(axis: Int): String = if (axis == 0) "x" else "y"

    /**
     * Re-parameterize free point [pt] as an offset from [anchor]: **the demand OP-4 case (b) deferred.**
     *
     * Reported on a circle whose centre rides a segment and whose rim goes through a free point: dragging the
     * segment moved the centre and *changed the radius*, because the free point stayed where it was. What the
     * user meant was that the rim point belongs to the centre, and saying so is a re-parameterization, not a
     * constraint: the point's literal position gives way to `polarPoint(anchor, d, θ)` with `d` and `θ` read
     * off the geometry it already has, so nothing moves at the moment of the change and the radius now follows
     * the centre.
     *
     * Two degrees of freedom before, two after — and both still draggable and typeable, now as a distance and
     * an angle (which is how a radius becomes a number one can type, OP-13). The binding goes through
     * [SourceNode.boundTo], so every existing reference to [pt] follows without a single input list being
     * rewired (OP-5), and [makeAbsolute] gives the point its own coordinates back.
     *
     * [dofs] is the offset a replay hands back (OP-18); absent, it is captured from the current geometry.
     * Refused — with a reason, and recording nothing, since a refusal rewires nothing and `skipIfEmpty` reads
     * that off [edits] — when [pt] is not an unbound free point, when [anchor] is not a point, or when
     * [anchor] already depends on [pt]: that would be a cycle, and OP-4's acyclicity applies to a
     * *re*-parameterization exactly as it does to a measurement.
     */
    fun makeRelative(
        pt: Element,
        anchor: Element,
        dofs: List<Quantity> = emptyList(),
    ): Boolean = recording("relative", Arg.El(pt), Arg.El(anchor), skipIfEmpty = true) { makeRelativeNow(pt, anchor, dofs) }

    private fun makeRelativeNow(
        pt: Element,
        anchor: Element,
        dofs: List<Quantity>,
    ): Boolean {
        // **The shared-carrier reading comes first**, because it is the more specific answer to the very same
        // two picks: both of them lie on one carrier, so the offset the user means is a distance *along* it and
        // the point still has exactly the one degree of freedom it had (see [anchorRiderTo]). A polar offset
        // there would be two DOF where the construction allows one, i.e. it would have to leave the curve.
        if (onSharedCarrier(pt, anchor)) return anchorRiderTo(pt, anchor, dofs.firstOrNull { it.dim == Dimension.LENGTH })
        // …and an **ortho vertex** owns no point literal at all — it is two coordinate chains behind a
        // re-pointable view — so the polar form below cannot reach it and OP-4 case (b) applies one axis at a
        // time instead (GitHub issue #23). Before the refusal, because that refusal was the bug.
        (pt.handle as? OrthoCornerHandle)?.let { return makeRelativeOrtho(pt, it, anchor, dofs) }
        val node = literalNode(pt)
        if (node == null || pt.kind != ElementKind.POINT || node.boundTo != null) {
            val rec = riderOf(pt)
            note =
                if (rec?.base != null) {
                    // the same rule the polar form follows: one anchor at a time, freed before it is replaced
                    "${nameOf(pt)} is already measured from ${rec.base?.let { nameOf(it) }} — free it first (Make absolute), then measure it from something else"
                } else if (rec != null && rec.line != null && !rec.carrierRelative) {
                    // it *could* be re-anchored, but not to this point: the offset of a rider is a distance
                    // along its own carrier, so the base has to be a position on that carrier
                    "${nameOf(pt)} rides ${nameOf(rec.host)}: to measure it from something, pick a point on ${nameOf(rec.host)} — " +
                        "one of its ends, or another point riding it"
                } else if (node?.boundTo != null) {
                    "${nameOf(pt)} already follows something — free it first (Make absolute), then anchor it"
                } else {
                    "${nameOf(pt)} is not a free point: only a point that owns its coordinates can be re-anchored"
                }
            return false
        }
        // …and a point in **space** for the anchor is refused by name (see [notInThePlane]). It could not be
        // caught by the cast below: `PointRef` is `Ref<PointValue>`, whose type argument is erased, so `as?`
        // accepts a `Point3Ref` and the polar offset would then be measured from a point that has no position
        // in this plane at all.
        notInThePlane(
            anchor,
            "the anchor an offset is measured from",
            "measure ${nameOf(pt)} from a point of the plane — or give it a height, and it is a point in space too",
        )?.let {
            note = it
            return false
        }
        @Suppress("UNCHECKED_CAST")
        val anchorRef = anchor.ref as? PointRef
        if (anchorRef == null || !anchor.isPoint || anchor === pt) {
            note = "${nameOf(anchor)} is not a point to anchor ${nameOf(pt)} to"
            return false
        }
        if (anchorRef.node === node || joinWouldCycle(pt, anchorRef.node)) {
            note = "Can't anchor ${nameOf(pt)} to ${nameOf(anchor)}: ${nameOf(anchor)} already follows ${nameOf(pt)}"
            return false
        }
        val ev = Evaluator()
        val here = pointOf(node, ev) ?: return false
        val there = pointOf(anchorRef.node, ev) ?: return false
        val offset = here - there
        val d = dofs.firstOrNull { it.dim == Dimension.LENGTH }?.mm ?: offset.length()
        val a = dofs.firstOrNull { it.dim == Dimension.ANGLE }?.base ?: offset.angle()
        val dNode = SourceNode(nextId("rd"), ScalarValue(Quantity.mm(d)))
        val aNode = SourceNode(nextId("ra"), ScalarValue(Quantity.rad(a)))
        node.boundTo = cx.polarPoint(anchorRef, Ref<ScalarValue>(dNode), Ref<ScalarValue>(aNode)).node
        relatives[pt.id] = RelativePoint(anchorRef, dNode, aNode)
        noteReparam(pt)
        pt.handle = RelativePointHandle(anchorRef, dNode, aNode)
        pt.style = Styles.ON_CURVE // derived-but-draggable, the same reading an attached point gets
        noteEdit()
        note = "${nameOf(pt)} now follows ${nameOf(anchor)} — distance and angle are its degrees of freedom (drag it, or type them)"
        return true
    }

    /**
     * Re-anchor **an ortho vertex** onto [anchor] — OP-4 case (b) where the thing that owns the freedom is
     * not a point literal but a *coordinate chain* (GitHub issue #23).
     *
     * The report: a closed rectilinear loop, and the closing leg changes length whenever the far side of the
     * figure is dragged. The user's own reading of it is the right one — *"in an ortho path the y coordinate
     * of e1 and e10 already depend on each other, but it is a valid requirement to also make the x dependent"*
     * — and it is a re-parameterization, not a constraint: the leg's length was always what the user meant,
     * and the drawing simply never said so.
     *
     * *Make relative* refused it because [literalNode] finds no point literal here at all: a vertex is
     * `pointXY(x, y)` behind a re-pointable view, and each coordinate is a **chain** of `boundTo` links —
     * usually one link to a neighbour, which is what keeps the leg between them axis-aligned. So this works
     * one axis at a time, and per axis it re-parameterizes the source at the **end** of that chain
     * ([writableMaster]), which is the node a drag already writes and therefore the node that owns the DOF.
     *
     * An axis is **left exactly as it is** when the anchor's coordinate on it already depends on that owner.
     * That is one test doing two honest jobs: it is OP-4's acyclicity (a sum reading a value that reads the
     * sum is a dead graph, not a wrong drawing), *and* it is the recognition that the path's own junctions may
     * already relate this pair — a closed loop binds the last vertex's own coordinate to the first's, so
     * "make them depend on each other" is already true there and restating it is what OP-4 forbids. Only when
     * **no** axis can be bound is there nothing to do, and then it refuses by name (session 65).
     *
     * [dofs] is the offsets a replay hands back (OP-18), one signed length per bound axis in axis order;
     * absent, each is captured from the geometry the vertex already has, so nothing moves at the moment of
     * the change. [makeAbsoluteOrtho] is the inverse, which is what makes this a conversion.
     */
    private fun makeRelativeOrtho(
        pt: Element,
        corner: OrthoCornerHandle,
        anchor: Element,
        dofs: List<Quantity>,
    ): Boolean {
        // one anchor at a time, freed before it is replaced — the polar form's own rule
        orthoRelativeOf(pt).firstOrNull()?.let { o ->
            val was = elementFor(o.anchor)?.let { nameOf(it) } ?: "another point"
            note = "${nameOf(pt)} already follows $was — free it first (Make absolute), then anchor it"
            return false
        }
        // A **placed** path holds the group's own local coordinates (OP-16) while an anchor's are the
        // world's, so their sum would state a relation in neither space. Refused by name with the cure, the
        // same rule that stops a placed path being extended in place ([resumableEnd]).
        if (pathFrameOf(corner) != null) {
            note = "${nameOf(pt)} belongs to a placed group: its coordinates are the group's own while an " +
                "anchor's are the world's — take the path out of the group first, then anchor it"
            return false
        }
        // …and a point in **space** cannot be the anchor, for the reason [notInThePlane] states: what would
        // be read off it is the plane point at its projection, which is a different point.
        notInThePlane(
            anchor,
            "the anchor a coordinate is measured from",
            "measure ${nameOf(pt)} from a point of the plane — or give it a height, and it is a point in space too",
        )?.let {
            note = it
            return false
        }

        @Suppress("UNCHECKED_CAST")
        val anchorRef = anchor.ref as? PointRef
        if (anchorRef == null || !anchor.isPoint || anchor === pt) {
            note = "${nameOf(anchor)} is not a point to anchor ${nameOf(pt)} to"
            return false
        }
        val ev = Evaluator()
        val here = pointOf(pt.ref.node, ev)
        val there = pointOf(anchorRef.node, ev)
        if (here == null || there == null) {
            note = "Can't anchor ${nameOf(pt)} to ${nameOf(anchor)} yet — one of them has no position right now"
            return false
        }
        val lengths = dofs.filter { it.dim == Dimension.LENGTH }
        val made = ArrayList<OrthoOffset>()
        val reasons = ArrayList<String>()
        for (axis in 0..1) {
            val name = axisName(axis)
            val node = if (axis == 0) corner.xNode else corner.yNode
            val owner = writableMaster(node)
            if (owner == null) {
                // named for what actually drives it: an *anchoring* is not a weld, and telling the user to
                // free a welded end they never made is a true-sounding sentence about the wrong thing
                val already = orthoOffsetOf(node)?.takeIf { it.axis == axis }
                reasons.add(
                    if (already != null) {
                        "its $name already follows ${elementFor(already.anchor)?.let { nameOf(it) } ?: "another point"}"
                    } else {
                        "its $name is driven by the construction (a welded or attached end)"
                    },
                )
                continue
            }
            val coord = if (axis == 0) cx.measureX(anchorRef) else cx.measureY(anchorRef)
            // the one test, doing both jobs — see the header
            if (dependsOn(coord.node, owner, HashSet())) {
                reasons.add("${nameOf(anchor)}'s $name already follows ${nameOf(pt)}'s")
                continue
            }
            // read positionally off the axes that actually bind, which replay reproduces in the same order
            // because it replays the same construction (OP-18)
            val d = lengths.getOrNull(made.size)?.mm ?: (if (axis == 0) here.x - there.x else here.y - there.y)
            val (offset, entry) = offsetParameter(axis, d)
            // in place, so every vertex on this chain — and every leg between them — follows the anchor
            // without one input list being rewired (OP-5)
            owner.boundTo = cx.add(coord, Ref<ScalarValue>(offset)).node
            val rec = OrthoOffset(owner, anchorRef, axis, coord, offset, entry)
            orthoOffsetByOwner[owner.id] = rec
            made.add(rec)
        }
        if (made.isEmpty()) {
            note = "Can't anchor ${nameOf(pt)} to ${nameOf(anchor)}: ${reasons.joinToString(", and ")} — there is " +
                "nothing left to state. Pick an anchor whose coordinates ${nameOf(pt)}'s do not already follow."
            return false
        }
        orthoRelatives[pt.id] = made
        orthoFreed.remove(pt.id)
        noteReparam(pt)
        noteEdit()
        val axes = made.map { axisName(it.axis) }
        val names = made.joinToString(" and ") { it.entry.name }
        note =
            if (made.size == 1) {
                "${nameOf(pt)} now follows ${nameOf(anchor)} along ${axes[0]} — the offset is its degree of " +
                    "freedom (drag it, type it, or give $names a formula)"
            } else {
                "${nameOf(pt)} now follows ${nameOf(anchor)} along ${axes.joinToString(" and ")} — the offsets " +
                    "are its degrees of freedom (drag it, type them, or give $names a formula)"
            }
        return true
    }

    /**
     * Give an anchored ortho vertex its coordinates back, where it now stands — [makeRelativeOrtho]'s
     * inverse, and the reason that re-parameterization is a **conversion** rather than a commitment
     * (OP-4 case b).
     *
     * Every value is read **before** any binding is cleared: two axes of one vertex can sit on chains that
     * reach each other, and freeing the first would then make the second read a coordinate that has already
     * moved. [dofs] is the freed literals as a replay hands them back — see [orthoFreed].
     */
    private fun makeAbsoluteOrtho(
        pt: Element,
        dofs: List<Quantity>,
    ): Boolean {
        val recs = orthoRelatives[pt.id] ?: return false
        // A row something else reads may not simply vanish, [retractParameter]'s own rule: freeing the
        // coordinate takes the parameter with it, and a `wire` step naming a row that no longer exists is a
        // file that will not load. Refused by name, with the cure (session 65).
        recs.firstOrNull { offsetReaders(it).isNotEmpty() }?.let { o ->
            note = "Can't free ${nameOf(pt)}: its offset ${o.entry.name} drives " +
                "${offsetReaders(o).joinToString(", ")} — free that first, then free ${nameOf(pt)}"
            return false
        }
        val ev = Evaluator()
        val lengths = dofs.filter { it.dim == Dimension.LENGTH }
        val at = recs.mapIndexed { i, r -> lengths.getOrNull(i)?.mm ?: scalarOf(r.owner, ev)?.mm }
        if (at.any { it == null }) {
            note = "Can't free ${nameOf(pt)} yet — its coordinates have no value right now"
            return false
        }
        for ((i, r) in recs.withIndex()) {
            r.owner.boundTo = null
            r.owner.value = ScalarValue(Quantity.mm(at[i] ?: 0.0))
            orthoOffsetByOwner.remove(r.owner.id)
            // the freedom is the vertex's own again, so the row that stood for it goes with it
            scalars.remove(r.entry)
        }
        orthoRelatives.remove(pt.id)
        orthoFreed[pt.id] = recs.map { it.owner }
        noteReparam(pt)
        noteEdit()
        val axes = recs.map { axisName(it.axis) }
        note =
            "${nameOf(pt)} keeps its position and owns its ${axes.joinToString(" and ")} again — " +
            "drag it, or type ${if (recs.size == 1) "it" else "them"}"
        return true
    }

    /**
     * Give a relative point its own coordinates back, at the position it currently has — the inverse of
     * [makeRelative], and the reason the re-parameterization is a *conversion* rather than a commitment
     * (OP-4 case b). Anything else that binds a point ([unweld] — a weld, an attach) is undone here too, so
     * one affordance answers "give this point its freedom back" however it lost it.
     */
    fun makeAbsolute(
        pt: Element,
        dofs: List<Quantity> = emptyList(),
    ): Boolean = recording("absolute", Arg.El(pt), skipIfEmpty = true) { makeAbsoluteNow(pt, dofs) }

    private fun makeAbsoluteNow(
        pt: Element,
        dofs: List<Quantity>,
    ): Boolean {
        // An **ortho vertex** anchored by [makeRelativeOrtho] publishes no point literal, so every read
        // below is blind to it: what it owns is two coordinate chains, and this is their inverse (issue #23).
        if (orthoRelatives.containsKey(pt.id)) return makeAbsoluteOrtho(pt, dofs)
        val node = literalNode(pt)
        // A rider whose parameter was re-anchored to a base of its own carrier has an absolute form to go back
        // to — its position along the world-anchored carrier (OP-20) — so that case is answered first: it is
        // one step of the same progression, *measured from a base* → *riding the world* → *free of the curve*.
        if (node?.boundTo == null && riderOf(pt)?.carrierRelative == true) return releaseRider(pt)
        // A rider the *tool* created publishes its position through a re-pointable view (see [addRider]), so
        // there is a substrate to hand a literal back after all: re-point it at a free source where the point
        // now stands. Uniform with the drag-attached case below, which unbinds its own `SourceNode`.
        if (node?.boundTo == null && riderOf(pt) != null) return detachRider(pt, dofs)
        // A projected point (GitHub #14) publishes its position through the same kind of view, so *Make
        // absolute* frees it in place exactly as it frees a rider — see [detachProjected].
        if (node?.boundTo == null && projected.containsKey(pt.id)) return detachProjected(pt, dofs)
        if (node?.boundTo == null) {
            note =
                if (node != null) {
                    "${nameOf(pt)} is already a free point"
                } else if (freedInSpace.containsKey(pt.id)) {
                    // it *is* free, in the two freedoms a point in space has here (OP-25): saying "derived by
                    // the construction" would be true of the formula and false about the point
                    "${nameOf(pt)} is already free of the curve it rode: it is a height point now, so its own " +
                        "freedoms are its base in ${pt.space} and its height — drag or type either"
                } else {
                    // a path corner, an intersection: it has no literal of its own to hand back
                    "${nameOf(pt)} is derived by the construction, not a point holding its own coordinates"
                }
            return false
        }
        val wasRelative = relativeOf(pt) != null
        riderOf(pt)?.takeIf { it.carrierRelative }?.let { releaseRiderNow(pt) }
        unweld(pt)
        note = if (wasRelative) "${nameOf(pt)} keeps its position and is free again" else "${nameOf(pt)} is a free point again"
        return true
    }

    /**
     * Free rider [pt] from its host: its view ([IndirectNode]) is re-pointed at a fresh free source holding
     * the position it has right now, so **nothing moves at the moment of the change** and everything built on
     * it — a perpendicular through it, a fillet, an arrayed copy — keeps working, following the point instead
     * of the curve from here on (OP-5's bind-in-place, one level up: OP-16's view).
     *
     * [dofs] is the freed position as a replay hands it back (OP-18): the point is an ordinary free point from
     * now on and may be dragged anywhere, so its coordinates are **state** and ride the same `dofs=` seam
     * every other re-parameterization uses ([relativeDofs]) rather than being re-derived from the rider the
     * step detached.
     */
    private fun detachRider(
        pt: Element,
        dofs: List<Quantity>,
    ): Boolean {
        val view = pt.ref.node as? IndirectNode ?: return false
        val rec = riderOf(pt) ?: return false
        if (pt.inSpace) return detachSpaceRider(pt, view, rec, dofs)
        val here = pointOf(view, Evaluator()) ?: return false
        val lengths = dofs.filter { it.dim == Dimension.LENGTH }
        val at = if (lengths.size == 2) Vec2(lengths[0].mm, lengths[1].mm) else here
        val free = SourceNode(nextId("fp"), PointValue(at))
        view.boundTo = free
        // it rides nothing any more: its record goes, and with it its registration for the gesture-time
        // compensation a carrier-anchored parameter needs (OP-20) — there is no longer anything to compensate
        riders.remove(pt.id)
        carrierRiders.removeAll { it.dof === rec.param }
        detached[pt.id] = free
        pt.kind = ElementKind.POINT
        pt.style = Styles.FREE_POINT
        pt.handle = FreePointHandle(free)
        noteReparam(pt)
        noteEdit()
        note = "${nameOf(pt)} keeps its position and is off ${nameOf(rec.host)} — it is an ordinary free point again"
        return true
    }

    /**
     * Free a rider **in space** (OP-26's coil rider) from its host: its view is re-pointed at a **height
     * point** standing exactly where it stood — a free base point on the host's own sketch plane, plus a free
     * height along that plane's normal.
     *
     * That is *Make absolute*'s own sentence one dimension up (OP-4 case b): the point stops following the
     * coil and keeps its position, nothing moves at the moment of the change, and everything built on it
     * follows the point instead of the curve from here on. What it is freed **into** is the pair of freedoms
     * this editor already knows how to edit — drag the base in the plan, drag the height in the 3D view, type
     * either — rather than three bare coordinates with no handle, which would be "free" in name only.
     *
     * [dofs] is the freed position as a replay hands it back (OP-18), three lengths: the base's two
     * coordinates in the plane, then the height. Its own step restates them ([relativeDofs]), because from
     * this step on they are the point's own state.
     */
    private fun detachSpaceRider(
        pt: Element,
        view: IndirectNode,
        rec: RiderRecord,
        dofs: List<Quantity>,
    ): Boolean {
        val ev = Evaluator()
        val planeRef = planeOfSpace(pt.space)
        val plane = (ev.valueOf(planeRef) as? PlaneValue)?.plane ?: return false
        val here = (ev.eval(view) as? EvalResult.Ok)?.let { (it.value as? Point3Value)?.p } ?: return false
        val lengths = dofs.filter { it.dim == Dimension.LENGTH }
        val local = if (lengths.size == 3) Vec2(lengths[0].mm, lengths[1].mm) else plane.toLocal(here)
        val lift = if (lengths.size == 3) lengths[2].mm else plane.distanceTo(here)
        // through [freePoint], so the base is an ordinary point of the drawing — drawn, pickable, draggable,
        // shareable. Its `point` step is absorbed into the *absolute* step this runs inside (see [recording]),
        // so what the file gains is one restated position and not a second step.
        val base = freePoint(Quantity.mm(local.x), Quantity.mm(local.y))
        val height = SourceNode(nextId("fh"), ScalarValue(Quantity.mm(lift)))
        view.boundTo = cx.heightPoint(planeRef, base, Ref<ScalarValue>(height)).node
        riders.remove(pt.id)
        carrierRiders.removeAll { it.dof === rec.param }
        freedInSpace[pt.id] = FreedInSpace(base.node as SourceNode, height)
        pt.kind = ElementKind.HEIGHT_POINT
        pt.style = Styles.DERIVED_POINT
        pt.handle = HeightPointHandle(planeRef, base, height)
        noteReparam(pt)
        noteEdit()
        note =
            "${nameOf(pt)} keeps its position and is off ${nameOf(rec.host)} — it is a height point on ${pt.space} " +
            "now: drag its base ${nameOf(elementFor(base) ?: pt)} in the plan, or its height in the 3D view"
        return true
    }

    /** The two free literals a rider freed in space owns from then on — see [detachSpaceRider]. */
    private class FreedInSpace(val base: SourceNode, val height: SourceNode)

    private val freedInSpace = HashMap<String, FreedInSpace>()

    /** Riders freed by [detachRider], by element id: the free source their view now points at. */
    private val detached = HashMap<String, SourceNode>()

    /**
     * The **literal-holding node** [el] publishes, seen through a re-pointable view: a free point's own
     * source, or the source a detached rider's view now points at ([detachRider]).
     *
     * One question — *does this element own its coordinates?* — asked of both shapes an element's point can
     * have, so a freed rider is a free point in every sense (weldable, attachable, re-anchorable) and not
     * merely a point that happens to draw in the right place.
     */
    private fun literalNode(el: Element): SourceNode? =
        when (val n = el.ref.node) {
            is SourceNode -> n
            is IndirectNode -> n.boundTo as? SourceNode
            else -> null
        }

    // ---- relative on a shared carrier: OP-4 case (b) for a rider, where the offset is along the host ----

    /**
     * Whether [pt] and [base] are two positions on **one carrier**: [pt] rides a line-like host, and [base] is
     * either another rider on that same host or a point the host is *built from* (an endpoint).
     *
     * The question is asked about the **host element**, not about a carrier-line node: each rider coerces its
     * own `lineOfSegment`, so two riders of one segment hold different line nodes while riding the same drawn
     * curve — and what the user picked is the drawn curve.
     */
    private fun onSharedCarrier(
        pt: Element,
        base: Element,
    ): Boolean {
        val rec = riderOf(pt) ?: return false
        if (rec.line == null || rec.carrierRelative) return false
        if (!base.isPoint || base === pt) return false
        if (riderOf(base)?.host === rec.host) return true
        return dependsOn(rec.host.ref.node, base.ref.node, HashSet())
    }

    /**
     * Re-anchor rider [pt] so its position is stated as a **signed distance [d] from [base]** along their
     * shared carrier: the rider's own parameter is bound onto `base's position along the carrier + d`
     * (OP-4 case b, OP-5's bind-in-place substrate).
     *
     * One degree of freedom before, one after, and nothing moves at the moment of the change — [d] is read off
     * the geometry the rider already has. What changes is *what it is measured from*: the position was anchored
     * to the world (a coordinate the host leaves free, or a distance along the carrier line — OP-20), and is
     * now anchored to a point of the carrier the user named. So editing the host carries the rider: turn the
     * segment and the offset holds, which is what a **stated** anchor buys over the gesture-time compensation
     * OP-20 needs for a world-anchored one — and such a rider is therefore no longer registered for
     * compensation at all.
     *
     * The rider's *point* is untouched: the binding sits one level down, in the parameter, so the carrier
     * construction, the element and everything referring to it stay exactly as they were — and the file keeps
     * restating the same node ([riderParam]).
     */
    private fun anchorRiderTo(
        pt: Element,
        base: Element,
        d: Quantity?,
    ): Boolean {
        val rec = riderOf(pt) ?: return false
        val line = rec.line ?: return false

        @Suppress("UNCHECKED_CAST")
        val basePoint = base.ref as? PointRef ?: return false
        val baseParam = carrierBaseParam(rec, basePoint, line)
        val ev = Evaluator()
        val here = ((ev.eval(rec.param) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.mm
        val there = ((ev.eval(baseParam.node) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.mm
        if (here == null || there == null) {
            note = "Can't measure ${nameOf(pt)} from ${nameOf(base)} yet — the carrier has no position of its own"
            return false
        }
        val offset = SourceNode(nextId("cd"), ScalarValue(Quantity.mm(d?.mm ?: (here - there))))
        val bound = cx.add(baseParam, Ref<ScalarValue>(offset))
        // the acyclicity every connection is checked for (OP-4): a base measured from *this* rider would put
        // the rider inside its own input cone, and a cyclic graph is a dead one rather than a wrong drawing
        if (dependsOn(bound.node, rec.param, HashSet())) {
            note = "Can't measure ${nameOf(pt)} from ${nameOf(base)}: ${nameOf(base)} is already measured from ${nameOf(pt)}"
            return false
        }
        rec.param.boundTo = bound.node
        rec.base = base
        rec.offset = offset
        noteReparam(pt)
        pt.handle = CarrierOffsetHandle(baseParam.node, offset, paramOfCarrier(rec))
        // its motion under an edit of the host is now fully stated, so there is nothing to compensate (OP-20)
        carrierRiders.removeAll { it.dof === rec.param }
        noteEdit()
        note =
            "${nameOf(pt)} is now ${Format.num(abs(here - there))} mm from ${nameOf(base)} along ${nameOf(rec.host)} — " +
            "that distance is its degree of freedom (drag it along, or type it)"
        return true
    }

    /**
     * Re-anchor **junction** [j] so its position is stated as a signed distance from [base] along the wall
     * it meets — the very same conversion [anchorRiderTo] performs on a rider, applied to the freedom a
     * *connection* owns (OP-16 × OP-20 × OP-4 case b).
     *
     * This is the DOF kind a placement used to miss. A junction's parameter is a **world** quantity — the
     * coordinate an axis-aligned host leaves free, or a distance along the carrier line — so a frame that
     * moves the wall leaves the junction standing in world space and the run hanging on it slides along the
     * moving wall to keep that coordinate. Binding the parameter onto `base's position along the carrier +
     * offset` states the motion instead: one degree of freedom before and after, nothing moves at the moment
     * of the change, and the whole figure travels with the frame.
     *
     * [d] is read off the geometry **before** any binding, for the reason the free-point capture already
     * states: a parameter derived after the points are bound would be derived against turned geometry.
     */
    private fun anchorJunctionTo(
        j: Junction,
        base: Element,
        d: Quantity?,
    ): JunctionCapture? {
        val param = j.param ?: return null
        val line = j.line ?: return null
        val form = j.form ?: return null

        @Suppress("UNCHECKED_CAST")
        val basePoint = base.ref as? PointRef ?: return null
        val baseParam = carrierBaseParam(form, j.axis, basePoint, line)
        val ev = Evaluator()
        val here = ((ev.eval(param) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.mm ?: return null
        val there = ((ev.eval(baseParam.node) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.mm ?: return null
        val offset = SourceNode(nextId("jd"), ScalarValue(Quantity.mm(d?.mm ?: (here - there))))
        val bound = cx.add(baseParam, Ref<ScalarValue>(offset))
        // the acyclicity every connection is checked for (OP-4): a base that already follows this junction
        // would put it inside its own input cone
        if (dependsOn(bound.node, param, HashSet())) return null
        val capture = JunctionCapture(j, j.handle, j.place)
        param.boundTo = bound.node
        j.base = base
        j.offset = offset
        // typing a driven coordinate and dragging one stay the same operation (OP-13): both now write the
        // offset, since the parameter itself is derived from the base from here on
        val axis = j.axis
        j.handle = CarrierOffsetHandle(baseParam.node, offset, paramOfCarrier(form, line, axis))
        j.place = { a, value ->
            val want = paramForCoord(form, line, axis, a, value)
            val from = ((Evaluator().eval(baseParam.node) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.mm
            if (want == null || from == null) {
                false
            } else {
                offset.value = ScalarValue(Quantity.mm(want - from))
                true
            }
        }
        // its motion under an edit of the host is now fully stated, so there is nothing to compensate (OP-20)
        carrierRiders.removeAll { it.dof === param }
        return capture
    }

    /** Give a re-anchored junction its absolute parameter back, where it now stands — the inverse above. */
    private fun releaseJunction(j: Junction) {
        val param = j.param ?: return
        val now = ((Evaluator().eval(param) as? EvalResult.Ok)?.value as? ScalarValue)?.q ?: return
        param.boundTo = null
        param.value = ScalarValue(now)
        j.base = null
        j.offset = null
        if (j.form == RiderForm.ALONG_LINE) j.line?.let { noteCarrierRider(j.point, it, param) }
    }

    /**
     * The parameter value that puts a rider of [form] at coordinate [value] on [axis] — the closed-form
     * inverse [Junction.place] needs once the parameter is measured from a base rather than from the world.
     */
    private fun paramForCoord(
        form: RiderForm,
        line: LineRef?,
        riderAxis: Int?,
        axis: Int,
        value: Double,
    ): Double? =
        when (form) {
            RiderForm.AXIS_COORD -> if (axis == riderAxis) value else null
            RiderForm.ALONG_LINE -> {
                // a line is affine in its parameter: t = (value - anchor) / dir, exactly (see [riderOn])
                val l = line?.let { ((Evaluator().eval(it.node) as? EvalResult.Ok)?.value as? LineValue)?.line }
                val dir = if (axis == 0) l?.dir?.x else l?.dir?.y
                val anchor = l?.let { it.origin - it.dir * it.origin.dot(it.dir) }
                val o = if (axis == 0) anchor?.x else anchor?.y
                if (dir == null || o == null || abs(dir) < Vec2.EPS) null else (value - o) / dir
            }
            else -> null
        }

    /**
     * Where [base] sits in [rec]'s **own** parameter — the quantity an offset from it is added to: its position
     * along the carrier, or its coordinate on the axis the host leaves free (see [riderOn]'s two forms).
     */
    private fun carrierBaseParam(
        rec: RiderRecord,
        base: PointRef,
        line: LineRef,
    ): ScalarRef = carrierBaseParam(rec.form, rec.axis, base, line)

    /** The same question asked of a bare form — what a [Junction]'s re-anchoring needs, having no record. */
    private fun carrierBaseParam(
        form: RiderForm,
        axis: Int?,
        base: PointRef,
        line: LineRef,
    ): ScalarRef =
        when (form) {
            RiderForm.AXIS_COORD -> if (axis == 0) cx.measureX(base) else cx.measureY(base)
            else -> cx.lineParam(line, base)
        }

    /** [carrierBaseParam]'s current value, for reading an offset off the geometry as it stands. */
    @Suppress("UNCHECKED_CAST")
    private fun carrierBaseParamValue(
        rec: RiderRecord,
        base: Element,
        ev: Evaluator,
    ): Double? {
        val point = base.ref as? PointRef ?: return null
        val line = rec.line ?: return null
        return ((ev.eval(carrierBaseParam(rec, point, line).node) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.mm
    }

    /** How a cursor becomes a value of [rec]'s own parameter — the inverse the offset handle writes through. */
    private fun paramOfCarrier(rec: RiderRecord): (Vec2, Evaluator) -> Double? = paramOfCarrier(rec.form, rec.line, rec.axis)

    /** The same inverse for a bare form — what a re-anchored [Junction]'s handle writes through. */
    private fun paramOfCarrier(
        form: RiderForm,
        line: LineRef?,
        axis: Int?,
    ): (Vec2, Evaluator) -> Double? {
        return { world, ev ->
            if (form == RiderForm.AXIS_COORD) {
                if (axis == 0) world.x else world.y
            } else {
                ((ev.eval(line!!.node) as? EvalResult.Ok)?.value as? LineValue)?.line?.dir?.let { world.dot(it) }
            }
        }
    }

    /** Give a re-anchored rider its absolute parameter back, where it now stands — [anchorRiderTo]'s inverse. */
    private fun releaseRider(pt: Element): Boolean {
        val rec = riderOf(pt) ?: return false
        val base = rec.base?.let { nameOf(it) } ?: return false
        if (!releaseRiderNow(pt)) return false
        noteEdit()
        note = "${nameOf(pt)} keeps its place on ${nameOf(rec.host)} and is measured from the world again, not from $base"
        return true
    }

    private fun releaseRiderNow(pt: Element): Boolean {
        val rec = riderOf(pt) ?: return false
        if (rec.offset == null) return false
        val now = ((Evaluator().eval(rec.param) as? EvalResult.Ok)?.value as? ScalarValue)?.q ?: return false
        rec.param.boundTo = null
        rec.param.value = ScalarValue(now)
        rec.offset = null
        rec.base = null
        pt.handle =
            when (rec.form) {
                RiderForm.AXIS_COORD -> OnAxisHandle(rec.param, rec.axis ?: 0)
                else -> OnLineHandle(rec.line!!, rec.param)
            }
        // only a carrier-anchored (2D) rider is registered for compensation, so the cast is the form's own
        // guarantee rather than a hope about an erased type — see [noteCarrierRider]
        @Suppress("UNCHECKED_CAST")
        if (rec.form == RiderForm.ALONG_LINE) noteCarrierRider(rec.point as PointRef, rec.line!!, rec.param)
        return true
    }

    /**
     * The degrees of freedom a **re-parameterization** step must restate (OP-18): a polar offset's distance
     * and angle, or a carrier-relative rider's signed distance. One question, both forms of OP-4 case (b).
     */
    fun relativeDofs(
        el: Element,
        ev: Evaluator,
        stepIndex: Int = journal.size,
    ): List<Quantity> {
        relativeOf(el)?.let { r ->
            return listOfNotNull(scalarOf(r.distance, ev), scalarOf(r.angle, ev))
        }
        // an **ortho vertex**'s offsets, in axis order — the order [makeRelativeOrtho] binds them in, so the
        // list a replay consumes positionally says exactly what it said when it was written (issue #23)
        orthoRelatives[el.id]?.let { rs -> return rs.map { it.offset.literal.q } }
        // …and the coordinates [makeAbsoluteOrtho] handed back, read off the **literals** for [unwelded]'s
        // reason: what that step restored is the literal, and the `relative` step replaying before it has
        // just bound the same node — so reading the value would describe the anchor instead
        orthoFreed[el.id]?.let { fs -> return fs.mapNotNull { (it.value as? ScalarValue)?.q } }
        riderOf(el)?.offset?.let { o -> return listOfNotNull(scalarOf(o, ev)) }
        // a **freed** rider (OP-16's view re-pointed, see [detachRider]) owns its coordinates from then on, so
        // what its step restates is the position it now has — through [restatedPosition], because a later
        // placement capture must not make the step describe post-capture geometry
        if (detached.containsKey(el.id)) {
            restatedPosition(el, stepIndex, ev)?.let { return listOf(Quantity.mm(it.x), Quantity.mm(it.y)) }
        }
        // ...and a rider freed **in space** (OP-26) owns a base and a height from then on ([detachSpaceRider]).
        // Read off the two literals rather than off the value, for [unwelded]'s reason: a group placement binds
        // a captured base, and what this step restored is the literal, not what the capture derives from it.
        freedInSpace[el.id]?.let { f ->
            val b = (f.base.value as? PointValue)?.p
            val h = (f.height.value as? ScalarValue)?.q
            if (b != null && h != null) return listOf(Quantity.mm(b.x), Quantity.mm(b.y), Quantity.mm(h.mm))
        }
        // an **unlinked** point (a weld or an attach left behind, see [unweld]) owns its coordinates from the
        // moment its step ran, for exactly the same reason — and its step must restate them, or replay would
        // re-derive them from the master the earlier `weld` step has just re-bound it to and put the point
        // back where it was joined.
        if (el.id in unwelded) {
            val n = literalNode(el)
            // free now: the same placement-capture correction every restated position gets. Bound *again*
            // since (unlinked, moved, re-joined elsewhere): its own literal is what this step restored, and
            // the later weld step re-binds it — reading the value would describe the new master instead.
            val p = if (n != null && n.boundTo != null) (n.value as? PointValue)?.p else restatedPosition(el, stepIndex, ev)
            p?.let { return listOf(Quantity.mm(it.x), Quantity.mm(it.y)) }
        }
        return emptyList()
    }

    /**
     * The value a rider's creating step restates (OP-18) — its **own** parameter ([riderParam]), which stays
     * meaningful in either form: re-anchored to a base of its carrier, the parameter is driven but still
     * evaluates to the rider's absolute position along that carrier, which is exactly what replaying the
     * creating step needs before the later step that re-anchors it runs.
     *
     * The one correction is a **turned** placed group (OP-16): the steps replay before the placement that
     * turns the group, so what they must restate is the pre-rotation geometry — the same rule
     * [restatedPosition] follows for a captured point, here applied to a parameter.
     */
    fun restatedRiderParam(
        el: Element,
        ev: Evaluator,
    ): Quantity? {
        val node = riderParam(el) ?: return null
        val q = ((ev.eval(node) as? EvalResult.Ok)?.value as? ScalarValue)?.q ?: return null
        val rec = riderOf(el) ?: return q
        val g = allGroups.firstOrNull { grp -> grp.capturedRiders.any { it === rec } } ?: return q
        return unturned(q, g, rec.form, rec.axis, rec.line, rec.point.node, ev)
    }

    /**
     * The value the `attachortho` step restates (OP-18) — the **junction's** own parameter, which is the one
     * freedom an ortho run's end gains when it lands on a curve ([junctionOnCurve]).
     *
     * The same rule as [restatedRiderParam], for the same reason: the run's corner is *derived* from the
     * junction from the moment it is bound, so re-deriving the junction from the corner's restated position was
     * a projection reading its own output — the session-63 creep.
     *
     * **Both** coordinates must answer the *same* junction, and that is the whole test for "this step owns a
     * freedom". A meeting that is fully **determined** ([bindCornerToDeterminedMeeting]) owns none: it binds the
     * one free coordinate outright, while the other still follows a junction further back along the run — so
     * asking either coordinate on its own would restate a value belonging to a different step, and replay would
     * (rightly) ignore it. Null there, which is exactly what such a step always meant.
     */
    fun restatedJunctionParam(
        el: Element,
        ev: Evaluator,
    ): Quantity? {
        val corner = el.handle as? OrthoCornerHandle ?: return null
        val j = junctionOf(corner.xNode) ?: return null
        if (junctionOf(corner.yNode) !== j) return null
        val param = j.param ?: return null
        val q = scalarOf(param, ev) ?: return null
        val g = allGroups.firstOrNull { grp -> grp.capturedJunctions.any { it.junction === j } } ?: return q
        return unturned(q, g, j.form, j.axis, j.line, j.point.node, ev)
    }

    /**
     * [q] measured on the geometry a **turned** placed group's steps replay against (OP-16): they run before
     * the placement that turns the group, so what they must restate is the pre-rotation position along the
     * host — the same rule [restatedPosition] follows for a captured point, here applied to a parameter.
     *
     * One helper for both freedoms that can be captured this way (a rider's and a junction's), because the
     * correction is a property of the *form* the parameter is in and of nothing else.
     */
    private fun unturned(
        q: Quantity,
        g: Group,
        form: RiderForm?,
        axis: Int?,
        line: LineRef?,
        point: Node,
        ev: Evaluator,
    ): Quantity {
        val f = frameValueOf(g) ?: return q
        if (f.angle == 0.0) return q
        val here = pointOf(point, ev) ?: return q
        val pre = f.origin + f.toLocal(here)
        if (form == RiderForm.AXIS_COORD) return Quantity.mm(if (axis == 0) pre.x else pre.y)
        val dir = ((ev.eval((line ?: return q).node) as? EvalResult.Ok)?.value as? LineValue)?.line?.dir ?: return q
        // the carrier un-turned: a direction has no origin, so only the rotation is undone
        val c = cos(-f.angle)
        val s = sin(-f.angle)
        return Quantity.mm(pre.dot(Vec2(dir.x * c - dir.y * s, dir.x * s + dir.y * c)))
    }

    private fun scalarOf(
        node: SourceNode,
        ev: Evaluator,
    ): Quantity? = ((ev.eval(node) as? EvalResult.Ok)?.value as? ScalarValue)?.q

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
        val node = literalNode(pt) ?: return null
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
                if (joinWouldCycle(pt, lr.node)) return null
                val l = (Evaluator().eval(lr.node) as? EvalResult.Ok)?.value as? LineValue ?: return null
                l.line.origin + l.line.dir * (p - l.line.origin).dot(l.line.dir)
            }
            curve.kind == ElementKind.CIRCLE -> {
                val cr = curve.ref as CircleRef
                if (joinWouldCycle(pt, cr.node)) return null
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

    // ---- replaying an older format version: what the file meant, not what today's writer means (OP-18) ----

    /**
     * The **format version being replayed**, or null outside a load (OP-18, *Versioning & migration*).
     *
     * A load-time flag rather than a parameter threaded through every route, because what a version bump
     * changes is the meaning of a *stored literal*, and the same literal reaches [pointOnLine] through two
     * different steps — `pointoncurve` and the point-on-line **tool** — which must not be able to disagree
     * about it. Set by [DocumentFormat.replay] around the whole script.
     */
    internal var replayingVersion: Int? = null

    /**
     * What a **migration** had to decide, or could not (OP-18). Notes rather than silence: where a v1 literal
     * is genuinely ambiguous the load says which element it was unsure about and which reading it took, so a
     * drawing never comes back quietly different from the one that was saved.
     *
     * Kept as a list on the document (and mirrored into [note]) because a load is one operation that may have
     * several such findings, and the user must be able to read all of them.
     */
    val loadNotes = ArrayList<String>()

    /** Record one migration finding — see [loadNotes]. */
    internal fun noteLoad(message: String) {
        loadNotes.add(message)
    }

    /**
     * Put what the load found on the ordinary note channel, once the replay is over.
     *
     * At the end rather than as they happen, because every step clears [note] before it runs ([recording]) —
     * a note is about the operation being performed — and a load is *one* operation from the user's chair.
     */
    internal fun publishLoadNotes() {
        if (loadNotes.isNotEmpty()) note = loadNotes.joinToString(" · ")
    }

    // ---- scored discrete choices: decided once from the clicks, then persisted (OP-1, OP-18) ----

    /**
     * The signs each element's construction **scored from its clicks** — a fillet's variant, a chamfer's
     * quadrant, an `intersectnear` branch — by element id.
     *
     * The point of the registry is the file: a scored choice used to live only in the `Select` nodes of the
     * session that made it, so a *reload* re-ran the scoring against whatever the geometry had become since,
     * and a fillet could come back as a different one of its eight variants than the user chose (reported as
     * *"fillets inverted, producing sharp corners"*). OP-1 says a branch is stored; storing it in a node the
     * file does not carry stores it only until the next save. So the step restates these signs
     * ([storedSigns]) and replay consumes them verbatim, never re-scoring.
     */
    private val scoredSigns = HashMap<String, List<Int>>()

    /** Say that [el]'s construction chose [signs] — see [scoredSigns]. */
    private fun registerSigns(
        el: Element,
        signs: List<Int>,
    ) {
        scoredSigns[el.id] = signs
    }

    /**
     * The scored signs [step] must restate (OP-18): those of the elements **it** created, in creation order.
     * Empty for every step that scores nothing, which is why the writer needs no per-tool case.
     */
    internal fun storedSigns(step: Step): List<Int> = step.creates.flatMap { scoredSigns[it.id] ?: emptyList() }

    /** The same, for a set of elements — one **copy** of a replicated gesture (OP-23). */
    private fun storedSigns(els: List<Element>): List<Int> = els.flatMap { scoredSigns[it.id] ?: emptyList() }

    /**
     * The panel entries behind [refs] — what a build that **records its own steps** needs in order to write
     * its own `scalar=` argument (OP-18), since [ToolDef.build] is handed refs and a step names entries.
     */
    private fun entriesOf(refs: List<ScalarRef>): List<ScalarEntry> =
        refs.mapNotNull { r -> scalars.firstOrNull { it.ref.node === r.node } }

    // ---- riding a curve: one DOF along a host, absolute wherever the host offers one (OP-20) ----

    /** Which of the parameter forms a rider's single degree of freedom is stated in — see [riderOn]. */
    enum class RiderForm {
        /** A world coordinate the host leaves free — a host axis-aligned *by construction* (OP-20). */
        AXIS_COORD,

        /** A signed distance along the carrier line, anchored to the line itself (OP-20). */
        ALONG_LINE,

        /** An angle about a circle's centre — already relative to the circle, so nothing re-anchors it. */
        CIRCLE_ANGLE,

        /**
         * The **parametric angle** on an ellipse (OP-24) — the same kind of freedom [CIRCLE_ANGLE] is, and
         * absolute for the same reason: it is measured in the ellipse's own frame, which no edit to the
         * curve's *extent* can move, because an ellipse has no ends to stretch.
         */
        ELLIPSE_PARAM,

        /**
         * A **function curve's own parameter** (the session-71 entry, curve half) — the same kind of freedom
         * [ELLIPSE_PARAM] is, and absolute for the same reason: it is measured in the function's own
         * parametrization, which no edit to the curve's extent can re-anchor. It is the one form whose
         * quantity is a **plain number**, since the parameter is dimensionless by construction.
         */
        FUNC_PARAM,

        /**
         * The **angle along a helix** (OP-26), measured from the coil's start the way it turns — the first
         * form whose point is not in the working plane, and the first whose parameter is **unbounded**.
         *
         * Absolute in the coil's own frame for [CIRCLE_ANGLE]'s exact argument, one dimension up: it is
         * measured from the stored phase about the axis, and no edit to the centre, the radius or the pitch
         * can re-anchor it. What is new is that the angle is *not* modular — 450° is the second winding — so
         * the range it is honest over is `[0, turns · 360°]` and an angle outside that is a **value**
         * condition, named and healing (OP-3), never clamped.
         *
         * The **sign** convention is the curve's, not the number's: the angle counts the way the coil turns,
         * so a left-hand coil's rider runs 0 → `turns · 360°` exactly as a right-hand one's does and
         * chirality stays structural ([constructit.geom.Handedness], OP-1).
         */
        HELIX_ANGLE,
    }

    /**
     * What an element **rides**, and the node its one degree of freedom lives in.
     *
     * Kept because "which freedom does this element own, and of what kind" is a question three features now
     * ask structurally rather than by guessing from a handle's class: re-anchoring a rider onto a base of its
     * own carrier (OP-4 case b), a group's placement capture (OP-16 — a rider is not rigid under a frame while
     * its parameter is anchored to the world), and the create dialog's candidate list, which has to *name* the
     * kinds it offers.
     */
    class RiderRecord internal constructor(
        val element: Element,
        /** The curve it rides — the element, so "the same carrier" is a question about the drawing. */
        val host: Element,
        val form: RiderForm,
        /** The rider's own parameter: where its position along [host] is stated. */
        val param: SourceNode,
        internal val line: LineRef?,
        internal val axis: Int?,
        /**
         * The constructed point riding the host — the element may be a free point *bound* onto it.
         *
         * Untyped, because a rider's point is a point of the **plane** for the three 2D forms and a point in
         * **space** for [RiderForm.HELIX_ANGLE] (OP-26): what this record answers is *which freedom does this
         * element own*, and that question is the same one dimension up.
         */
        internal val point: Ref<*>,
    ) {
        /** The point of the carrier this rider's position is measured from, once it has been re-anchored. */
        var base: Element? = null
            internal set

        /** The signed offset from [base] — this rider's freedom in the re-anchored form. */
        internal var offset: SourceNode? = null

        /** True while the position is stated **relative to [base]** rather than to the world (OP-4 case b). */
        val carrierRelative: Boolean get() = offset != null
    }

    private val riders = HashMap<String, RiderRecord>()

    /** How [el] rides its host, or null when it is not a rider. */
    fun riderOf(el: Element): RiderRecord? = riders[el.id]

    /**
     * The node whose value *is* [el]'s position along its host — what the file restates for a rider (OP-18).
     *
     * Deliberately the rider's **own** parameter and not "whatever its handle writes": once the parameter has
     * been re-anchored to a base of the same carrier, the handle writes the offset while the parameter still
     * holds (and evaluates to) the absolute position along the carrier, which is exactly what a replay of the
     * creating step needs. One node, whichever form the rider is in.
     */
    fun riderParam(el: Element): SourceNode? = riders[el.id]?.param

    private fun noteRider(
        el: Element,
        host: Element,
        rider: Rider<*>,
    ) {
        riders[el.id] = RiderRecord(el, host, rider.form, rider.dof, rider.line, rider.axis, rider.point)
    }

    /**
     * A point that **rides** a curve: the point itself, the [handle] over its single degree of freedom, and
     * how to [place] that freedom so a wanted world coordinate comes out exactly (see [Junction.place]).
     */
    private class Rider<V : Value>(
        val point: Ref<V>,
        val handle: Handle,
        /** The single source node carrying this rider's freedom — its parameter, whatever kind it is. */
        val dof: SourceNode,
        /** Which of the three forms the parameter is in — see [RiderForm]. */
        val form: RiderForm,
        /** The carrier, for the two linear forms; the coordinate axis for [RiderForm.AXIS_COORD]. */
        val line: LineRef? = null,
        val circle: CircleRef? = null,
        val axis: Int? = null,
        /** Whether [point] is a point in **space** rather than in the plane — see [Element.inSpace]. */
        val inSpace: Boolean = false,
        /** Which axes [place] has any say over at all — see [Junction.placeable]. */
        val placeable: (axis: Int) -> Boolean,
        val place: (axis: Int, value: Double) -> Boolean,
    )

    /**
     * Which coordinate a point riding [curve] is free to choose, when the host determines the other **by
     * construction**: 0 = x on a horizontal leg, 1 = y on a vertical one. Null when nothing in the
     * construction keeps the host axis-aligned.
     *
     * Only an ortho path's leg qualifies, and only while its path is not placed in a group: a placed path's
     * legs are axis-aligned in the *group's* space (OP-16) rather than the world's, and a segment a user
     * happened to draw horizontally is aligned by coincidence, which the next drag undoes. That distinction
     * is exactly what decides whether a rider's position can be stored as a world coordinate — see [riderOn].
     */
    private fun sliderAxisOf(curve: Element): Int? {
        val (path, i) = legOf(curve) ?: return null
        return if (path.frame != null) null else path.legAxis(i)
    }

    /**
     * The point of [line] at signed distance [t] from the point of the line **nearest the world origin**.
     *
     * That anchor is a property of the line alone, which is the whole point (OP-20): `pointOnLineAt` measures
     * from the line's `origin`, and a segment's carrier line takes its origin from one of the segment's
     * endpoints, so a position measured that way moves when that endpoint is dragged *along the line* —
     * an edit that changes nothing visible about the host.
     */
    private fun alongLine(
        line: LineRef,
        t: ScalarRef,
    ): PointRef {
        val zero = Ref<ScalarValue>(scalarSource(0.0))
        return cx.pointAlongLine(line, cx.pointXY(zero, zero), t, 1)
    }

    /** The line of every point whose [axis] coordinate equals [value] — vertical for axis 0. */
    private fun axisLineAt(
        value: ScalarRef,
        axis: Int,
    ): LineRef {
        val zero = Ref<ScalarValue>(scalarSource(0.0))
        val one = Ref<ScalarValue>(scalarSource(1.0))
        return if (axis == 0) {
            cx.lineThrough(cx.pointXY(value, zero), cx.pointXY(value, one))
        } else {
            cx.lineThrough(cx.pointXY(zero, value), cx.pointXY(one, value))
        }
    }

    /**
     * Put a rider on [curve] at [at], with [prefix] naming its parameter node.
     *
     * **Where the host makes an absolute quantity available, that quantity is the parameter.** A host that is
     * axis-aligned by construction determines one of the rider's coordinates and leaves the other free, so
     * the free one — a plain world coordinate — is what the rider stores, and where it crosses the host is
     * an intersection like any other.
     *
     * The alternative, a distance *along* the line, is measured from the line's origin, which is one of the
     * host's own corners. That made every position along a host **relative to the host's extent**: dragging a
     * wall's far corner, or a neighbouring wall that owns it, slid every attachment along with it — reported
     * as a T-branch following a wall it was nowhere near. Which corner a segment-attached point happens to be
     * anchored to is invisible to the user, so nothing they can see may depend on it (OP-20, as built).
     *
     * A host that is *not* axis-aligned by construction keeps a distance **along** the line — a slanted line
     * has no single world coordinate to offer, and one that is only incidentally axis-aligned can be turned,
     * which would send the crossing of a fixed axis line off toward infinity. That distance is measured from
     * an anchor belonging to the line itself ([alongLine]), so it does not re-anchor either; what it cannot
     * survive is the host being *turned*, which no parameter along a curve can. DESIGN.md records that limit.
     */
    private fun riderOn(
        curve: Element,
        at: Vec2,
        prefix: String,
    ): Rider<PointValue>? {
        val ev = Evaluator()
        if (curve.isLinear) {
            val lr = carrierLine(curve)
            val axis = sliderAxisOf(curve)
            if (axis != null) {
                val cNode = SourceNode(nextId(prefix + "c"), ScalarValue(Quantity.mm(if (axis == 0) at.x else at.y)))
                val point = cx.select(cx.intersectLL(axisLineAt(Ref<ScalarValue>(cNode), axis), lr), 1)
                if (ev.eval(point.node) is EvalResult.Ok) {
                    return Rider(
                        point,
                        OnAxisHandle(cNode, axis),
                        cNode,
                        RiderForm.AXIS_COORD,
                        line = lr,
                        axis = axis,
                        // the host determines the other coordinate outright, so this rider has no say over it
                        placeable = { a -> a == axis },
                    ) { a, value ->
                        // the host fixes the other coordinate, so this one is all there is to place — exactly
                        if (a != axis) {
                            false
                        } else {
                            cNode.value = ScalarValue(Quantity.mm(value))
                            true
                        }
                    }
                }
            }
            val l = (ev.eval(lr.node) as? EvalResult.Ok)?.value as? LineValue ?: return null
            val tNode = SourceNode(nextId(prefix + "t"), ScalarValue(Quantity.mm(at.dot(l.line.dir))))
            val along = alongLine(lr, Ref<ScalarValue>(tNode))
            noteCarrierRider(along, lr, tNode)
            return Rider(
                along,
                OnLineHandle(lr, tNode),
                tNode,
                RiderForm.ALONG_LINE,
                line = lr,
                // a slanted line moves in both coordinates as its parameter runs, so both are reachable; a
                // line that happens to run along one axis has no say over the other, exactly as above
                placeable = { a ->
                    val line = ((Evaluator().eval(lr.node) as? EvalResult.Ok)?.value as? LineValue)?.line
                    val d = if (a == 0) line?.dir?.x else line?.dir?.y
                    d != null && abs(d) >= Vec2.EPS
                },
            ) { a, value ->
                // a line is affine in its parameter: t = (value - anchor) / dir, exactly
                val line = ((Evaluator().eval(lr.node) as? EvalResult.Ok)?.value as? LineValue)?.line
                val d = if (a == 0) line?.dir?.x else line?.dir?.y
                val anchor = line?.let { it.origin - it.dir * it.origin.dot(it.dir) }
                val o = if (a == 0) anchor?.x else anchor?.y
                if (line == null || d == null || o == null || abs(d) < Vec2.EPS) {
                    false
                } else {
                    tNode.value = ScalarValue(Quantity.mm((value - o) / d))
                    true
                }
            }
        }
        if (curve.isCentric) {
            val cr = carrierCircle(curve)
            val c = (ev.eval(cr.node) as? EvalResult.Ok)?.value as? CircleValue ?: return null
            // an angle about the centre is already absolute: it re-anchors on nothing an edit to the
            // circle's extent can move, since a circle has no ends to stretch
            val aNode = SourceNode(nextId(prefix + "a"), ScalarValue(Quantity.rad((at - c.circle.center).angle())))
            return Rider(
                cx.pointOnCircle(cr, Ref<ScalarValue>(aNode)),
                OnCircleHandle(cr, aNode),
                aNode,
                RiderForm.CIRCLE_ANGLE,
                circle = cr,
                // a circle sweeps both coordinates; a value beyond its diameter is a *value* refusal below
                placeable = { true },
            ) { axis, value ->
                // a circle has two angles per coordinate; keep the one nearer where it already sits
                val circle = ((Evaluator().eval(cr.node) as? EvalResult.Ok)?.value as? CircleValue)?.circle
                val centre = if (axis == 0) circle?.center?.x else circle?.center?.y
                val ratio = if (circle == null || centre == null) 2.0 else (value - centre) / circle.radius
                if (circle == null || abs(ratio) > 1.0) {
                    false
                } else {
                    val base = if (axis == 0) acos(ratio) else asin(ratio)
                    val current = (aNode.value as ScalarValue).q.base
                    val options = if (axis == 0) listOf(base, -base) else listOf(base, PI - base)
                    val pick = options.minByOrNull { abs(atan2(sin(it - current), cos(it - current))) } ?: base
                    aNode.value = ScalarValue(Quantity.rad(pick))
                    true
                }
            }
        }
        if (curve.kind == ElementKind.FUNC_CURVE) {
            @Suppress("UNCHECKED_CAST")
            val fr = curve.ref as FuncCurveRef
            val c = (ev.eval(fr.node) as? EvalResult.Ok)?.value as? FuncCurveValue ?: return null
            // the curve's own parameter: absolute for [RiderForm.ELLIPSE_PARAM]'s exact reason — it is
            // measured in the function's own parametrization, which no edit to the extent re-anchors
            val tNode = SourceNode(nextId(prefix + "f"), ScalarValue(Quantity.number(FuncCurves.nearestParam(c.curve, at))))
            return Rider(
                cx.pointOnFuncCurve(fr, Ref<ScalarValue>(tNode)),
                OnFuncCurveHandle(fr, tNode),
                tNode,
                RiderForm.FUNC_PARAM,
                // an arbitrary function has no closed-form inverse for "put this coordinate at that value",
                // so this rider has no *say* over a placed coordinate — it says so rather than solving
                // numerically for a position the user did not ask to be approximate
                placeable = { false },
            ) { _, _ -> false }
        }
        if (curve.isElliptic) {
            val er = carrierEllipse(curve)
            val e = (ev.eval(er.node) as? EvalResult.Ok)?.value as? EllipseValue ?: return null
            // the parametric angle in the ellipse's own frame: absolute, exactly as a circle's polar angle
            // is, and the DOF that makes position-along a conic exact (OP-24)
            val tNode = SourceNode(nextId(prefix + "p"), ScalarValue(Quantity.rad(Conics.paramOf(e.ellipse, at))))
            return Rider(
                cx.pointOnEllipse(er, Ref<ScalarValue>(tNode)),
                OnEllipseHandle(er, tNode),
                tNode,
                RiderForm.ELLIPSE_PARAM,
                placeable = { true },
            ) { axis, value ->
                // one coordinate of P(t) is `A cos t + B sin t + c`, so placing it is a closed-form solve —
                // the ellipse's version of the circle's two-angles-per-coordinate rule, keeping the branch
                // nearer where the rider already sits
                val el2 = ((Evaluator().eval(er.node) as? EvalResult.Ok)?.value as? EllipseValue)?.ellipse
                if (el2 == null) {
                    false
                } else {
                    val co = cos(el2.rotation)
                    val si = sin(el2.rotation)
                    val ca = if (axis == 0) el2.a * co else el2.a * si
                    val cb = if (axis == 0) -el2.b * si else el2.b * co
                    val k = value - (if (axis == 0) el2.center.x else el2.center.y)
                    val r = kotlin.math.hypot(ca, cb)
                    if (r < Vec2.EPS || abs(k) > r) {
                        false
                    } else {
                        val phase = atan2(cb, ca)
                        val d = acos((k / r).coerceIn(-1.0, 1.0))
                        val current = (tNode.value as ScalarValue).q.base
                        val pick =
                            listOf(phase + d, phase - d)
                                .minByOrNull { abs(atan2(sin(it - current), cos(it - current))) } ?: (phase + d)
                        tNode.value = ScalarValue(Quantity.rad(pick))
                        true
                    }
                }
            }
        }
        return null
    }

    // ---- gesture-time compensation: a rider keeps its place while its host turns (OP-20) ----

    /**
     * A rider whose stored parameter is **carrier-anchored** — a distance along a line, the form OP-20 keeps
     * for a host with no world coordinate to offer. Its parameter's *meaning* turns with the carrier, which
     * is the one thing no parameter along a curve survives, so an edit that turns the carrier compensates it.
     *
     * Only this form is registered. A rider on a host that is axis-aligned by construction stores a world
     * coordinate and one about a circle's centre stores an angle; both are already anchored to something the
     * host cannot move, so compensating them would be a write with nothing to correct (see [riderOn]).
     */
    internal class CarrierRider(val point: PointRef, val line: LineRef, val dof: SourceNode)

    private val carrierRiders = ArrayList<CarrierRider>()

    private fun noteCarrierRider(
        point: PointRef,
        line: LineRef,
        dof: SourceNode,
    ) {
        carrierRiders.add(CarrierRider(point, line, dof))
    }

    /**
     * Where one carrier-anchored rider stood when the gesture began, and what its parameter held then — the
     * reference [compensateRiders] re-solves against on every move.
     */
    class RiderAnchor internal constructor(
        internal val rider: CarrierRider,
        internal val world: Vec2,
        internal val dir: Vec2,
        internal val t: Double,
    ) {
        /** The literal this compensation last left in place — how a rider driven by the gesture is spotted. */
        internal var expected: Double = t

        /** True once the gesture itself wrote this rider's parameter: its own drag wins, forever after. */
        internal var driven: Boolean = false
    }

    /**
     * Snapshot every carrier-anchored rider: taken once, where the gesture starts.
     *
     * The reference is deliberately the **grab-time** world position rather than the previous move's, so a
     * gesture is a pure function of where it started and where the cursor is now: a stretch that does not turn
     * the host writes nothing at all, a rotation moves each rider continuously, and dragging back to the start
     * restores every rider exactly instead of accumulating a walk.
     *
     * The list comes back in dependency order, once: a gesture writes literals and never rewires, so the order
     * cannot change while it lasts — and the graph walk that finds it has no business running per move.
     */
    fun riderAnchors(): List<RiderAnchor> {
        if (carrierRiders.isEmpty()) return emptyList()
        val ev = Evaluator()
        return inRiderOrder(
            carrierRiders.mapNotNull { r ->
                val p = pointOf(r.point.node, ev) ?: return@mapNotNull null
                val l = ((ev.eval(r.line.node) as? EvalResult.Ok)?.value as? LineValue)?.line ?: return@mapNotNull null
                val t = ((ev.eval(r.dof) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.base ?: return@mapNotNull null
                RiderAnchor(r, p, l.dir, t)
            },
        )
    }

    /**
     * Re-solve each rider's parameter so it sits at the **projection of its grab-time position onto its
     * host's current geometry** — the compensation OP-20's as-built limit asked for: the parameter's meaning
     * turns with the carrier, so an edit that turns the carrier must restate it.
     *
     * Three rules make this safe to run after *every* move of *every* gesture:
     * - a host whose direction is unchanged is left completely alone. Projection is the identity there
     *   (`t = world · dir` is what the parameter already holds), so writing would only churn a literal — and
     *   a stretch, a perpendicular move and a translation must be exactly as stable as they were.
     * - a rider the gesture *itself* wrote is never touched again: its own drag wins. It is recognised by its
     *   parameter no longer holding what this compensation last left there, which needs no knowledge of which
     *   handle the gesture is driving — endpoint, leg, junction delegation or typed field alike.
     * - riders are compensated **outer to inner** — the order [riderAnchors] already put them in: a rider that
     *   carries another rider's host has to be put right first, or the inner one would be projected onto
     *   geometry that is still about to move.
     */
    fun compensateRiders(anchors: List<RiderAnchor>) {
        if (anchors.isEmpty()) return
        var ev = Evaluator()
        for (a in anchors) {
            if (a.driven) continue
            val now = ((ev.eval(a.rider.dof) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.base ?: continue
            if (now != a.expected) {
                a.driven = true
                continue
            }
            val line = ((ev.eval(a.rider.line.node) as? EvalResult.Ok)?.value as? LineValue)?.line ?: continue
            // the grab-time value *is* the projection onto the grab-time line, exactly — so an unturned host
            // restores it bit for bit rather than through arithmetic that could drift
            val want = if (line.dir == a.dir) a.t else a.world.dot(line.dir)
            if (want == now) continue
            a.rider.dof.value = ScalarValue(Quantity.mm(want))
            a.expected = want
            ev = Evaluator() // what a compensated rider carries has just moved; the next one must see it
        }
    }

    /**
     * [anchors] ordered so a rider comes after every rider its own host is built from — the dependency order
     * a chain of riders needs (see [compensateRiders]). The graph is acyclic, so the pick always succeeds;
     * the fallback only keeps a corrupted graph from looping.
     */
    private fun inRiderOrder(anchors: List<RiderAnchor>): List<RiderAnchor> {
        if (anchors.size < 2) return anchors
        val rest = ArrayList(anchors)
        val out = ArrayList<RiderAnchor>(anchors.size)
        while (rest.isNotEmpty()) {
            val next =
                rest.firstOrNull { a ->
                    rest.none { b -> b !== a && dependsOn(a.rider.line.node, b.rider.point.node, HashSet()) }
                } ?: rest.first()
            out.add(next)
            rest.remove(next)
        }
        return out
    }

    /**
     * Attach free point [pt] onto [curve]: it becomes a 1-DOF on-curve point (draggable along the
     * curve). The point's node is welded ([SourceNode.boundTo]) onto a fresh point-on-curve node
     * driven by a hidden parameter, so everything already referencing the point now slides with it.
     * Reversible via [unweld]. Same validity rules as [attachTargetPos].
     *
     * The parameter is a world coordinate on a host that is axis-aligned by construction, and a distance
     * along the line otherwise — see [riderOn]; a free point attached to a wall must not slide when that
     * wall is stretched any more than a run's end does.
     *
     * [at] is that parameter as a replay hands it back (OP-18), exactly as it is for [pointOnCurve]: where the
     * point sits along the curve is **state** — dragged, and compensated while the host turns — while the two
     * elements this step names are the choice replay must repeat. Without it, replay re-derived the parameter
     * by projecting the point's *own restated position*, which is derived geometry once the point rides the
     * curve, so the projection read its own output and the last digit moved on every save (session 63's creep).
     */
    fun attachToCurve(
        pt: Element,
        curve: Element,
        at: Quantity? = null,
    ): Boolean = recording("attach", Arg.El(pt), Arg.El(curve), skipIfEmpty = true) { attachToCurveNow(pt, curve, at) }

    private fun attachToCurveNow(
        pt: Element,
        curve: Element,
        at: Quantity?,
    ): Boolean {
        val node = literalNode(pt) ?: return false
        if (attachTargetPos(pt, curve) == null) return false
        val p = (Evaluator().eval(node) as EvalResult.Ok).let { (it.value as PointValue).p }
        val rider = riderOn(curve, p, "") ?: return false
        // no migration to make ([migratedRiderDof]): `dofs=` on this step is an argument no earlier build ever
        // wrote, so a file that carries one was written by a build that measures it the way this one reads it
        restateDof(rider.dof, at)
        node.boundTo = rider.point.node
        pt.handle = rider.handle
        pt.kind = ElementKind.ON_CURVE
        pt.style = Styles.ON_CURVE
        noteRider(pt, curve, rider)
        noteEdit()
        return true
    }

    /** The ortho-corner handle of [el] if it is a draggable *end* of an open path, else null. */
    fun orthoEndpoint(el: Element): OrthoCornerHandle? =
        (el.handle as? OrthoCornerHandle)?.takeIf { it.isEndpoint }

    /**
     * Attach an ortho path endpoint [el] onto [curve] by making that meeting point a [Junction]: the
     * junction owns the freedom (one parameter along the curve) and **both** of the endpoint's
     * coordinates are bound to it, so the endpoint owns none of it.
     *
     * That is what makes two runs meeting here symmetric (OP-20). The previous scheme derived one
     * coordinate from the other, which handed the shared DOF to whichever run arrived first: its far end
     * kept two directions to drag while the other run's kept one, though the two are the same thing to
     * the user. Now every participant reaches the shared freedom the same way — through the junction.
     *
     * The *master* of each coordinate chain is bound, not the local node, so the rest of the run follows
     * the junction and every leg stays axis-aligned. A leg parallel to the line still ends up collinear
     * with it: that is geometry, not attribution.
     *
     * How much freedom the corner still has decides **what kind of meeting point this is**: two free
     * coordinates make a junction owning one DOF; **one** free coordinate makes a point that is fully
     * determined — see [bindCornerToDeterminedMeeting].
     */
    fun attachOrthoEndpointToCurve(
        el: Element,
        curve: Element,
        at: Quantity? = null,
    ): Boolean = recording("attachortho", Arg.El(el), Arg.El(curve)) { attachOrthoEndpointToCurveNow(el, curve, at) }

    private fun attachOrthoEndpointToCurveNow(
        el: Element,
        curve: Element,
        at: Quantity?,
    ): Boolean {
        val corner = orthoEndpoint(el) ?: return false
        // before making the junction, not after: a rejected bind would otherwise leave a stray junction
        // (and its parameter node) behind in a document that never got the attach it was created for
        if (joinWouldCycle(el, carrierNodeOf(curve) ?: return false)) return false
        val mx = writableMaster(corner.xNode)
        val my = writableMaster(corner.yNode)
        if (mx == null && my == null) return false // nothing left to give — see [connectRefusal]
        if (mx == null || my == null) {
            val free = mx ?: my ?: return false
            // a determined meeting owns no freedom, so there is nothing for a restated [at] to say
            return bindCornerToDeterminedMeeting(corner, curve, free, if (mx != null) 0 else 1)
        }
        val junction = junctionOnCurve(curve, el.ref.node) ?: return false
        // where the meeting sits along the host is state, restated by this step (see [restatedJunctionParam]):
        // set before the bind, so the corner is derived from the position the file states and from nothing else
        restateDof(junction.param, at)
        return bindCornerToJunction(corner, junction)
    }

    /**
     * Attach a corner that has **one** coordinate left to give: the meeting point is then not a junction
     * but *fully determined* — where the axis line through the coordinate the corner no longer owns crosses
     * [curve] — and the one remaining coordinate ([free], along [freeAxis]) is bound to it.
     *
     * This is the second end of a T-web's middle run (OP-20). That run's first end already ends on the top
     * wall, so its x belongs to *that* junction; reaching the bottom wall leaves only its y free. A second
     * junction cannot express this — it would own a slide along the bottom wall that the leg's x already
     * fixes, one DOF too many — so the attach was refused, and refused *silently*: the run neither joined
     * nor finished, which is precisely what "the ending did not snap and did not finish the path" looked
     * like. Deriving the meeting point keeps the count right (1 free -> 0), and a meeting point that owns
     * nothing is the honestly immovable case the junction model already names.
     *
     * Composed from existing primitives (`pointXY` + `lineThrough` + `intersect*` + `Select`), so it is an
     * ordinary construction: it replays from the same `attachortho` step, undoes, and needs no solver. The
     * axis line is built from the given **coordinate** alone and never from the corner's point, which
     * depends on both coordinates and would put the binding inside its own input cone.
     */
    private fun bindCornerToDeterminedMeeting(
        corner: OrthoCornerHandle,
        curve: Element,
        free: SourceNode,
        freeAxis: Int,
    ): Boolean {
        // a placed corner holds its group's *local* coordinates while a meeting point is a world one (OP-16)
        if (pathFrameOf(corner) != null) return false
        val given = if (freeAxis == 0) corner.yNode else corner.xNode
        if (dependsOn(given, free, HashSet())) return false
        // the line along which the free coordinate still varies: the axis line at the *given* one, so the
        // meeting point is where it crosses the curve — a position that depends on the host's carrier line
        // and on nothing about where the host happens to start or end (see [riderOn])
        val axisLine = axisLineAt(Ref<ScalarValue>(given), 1 - freeAxis)
        val meet =
            when {
                // two lines that are not parallel cross exactly once, so there is no branch to choose
                curve.isLinear -> cx.select(cx.intersectLL(axisLine, carrierLine(curve)), 1)
                curve.kind == ElementKind.CIRCLE -> {
                    val set = cx.intersectLC(axisLine, curve.ref as CircleRef)
                    cx.select(set, branchAt(set, corner))
                }
                else -> return false
            }
        // parallel, or a circle the axis line misses: they never meet, so there is nothing to bind to —
        // refused whole rather than leaving the corner bound to an invalid point (OP-3 would only hide it)
        if (Evaluator().eval(meet.node) !is EvalResult.Ok) return false
        free.boundTo = (if (freeAxis == 0) cx.measureX(meet) else cx.measureY(meet)).node
        corner.isEndpoint = false
        return true
    }

    /**
     * Which branch of [set] [corner] currently sits on: +1 for the first crossing, -1 for the last — a
     * persisted discrete choice (OP-1), captured once at the attach, never re-derived by continuity.
     */
    private fun branchAt(
        set: PointSetRef,
        corner: OrthoCornerHandle,
    ): Int {
        val ev = Evaluator()
        val pts = ((ev.eval(set.node) as? EvalResult.Ok)?.value as? PointSetValue)?.set?.points ?: return 1
        if (pts.size < 2) return 1
        val x = ((ev.eval(corner.xNode) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.mm ?: return 1
        val y = ((ev.eval(corner.yNode) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.mm ?: return 1
        val here = Vec2(x, y)
        return if ((pts.first() - here).length() <= (pts.last() - here).length()) 1 else -1
    }

    /**
     * Why connecting [el] onto [target] cannot be done, in the user's terms — null when it can.
     *
     * The refusals themselves live in the operations; this states them, and the two must stay in step:
     * OP-20's rule is that a connection the editor will not make **explains itself**, and it has to hold on
     * every route that can bind — the drag magnet, a release, and a *click while drawing*, which used to
     * refuse in complete silence. A silent refusal on the drawing route is the worst of the three: the
     * gesture looks like the end of a run, so nothing joining and nothing finishing reads as the tool being
     * broken rather than as the model saying no.
     */
    fun connectRefusal(
        el: Element,
        target: Element,
    ): String? {
        if (el === target) return "${nameOf(el)} cannot be joined onto itself"
        val corner = el.handle as? OrthoCornerHandle
        if (corner != null) {
            if (!corner.isEndpoint) {
                return "${nameOf(el)} is already connected — a run that ends on something is a terminus, not a loose end"
            }
            if (pathFrameOf(corner) != null) {
                return "${nameOf(el)}'s path is placed in a group, so its coordinates are the frame's local ones — unplace the group first"
            }
        } else if (isFramed(el)) {
            return "${nameOf(el)} is held by a placed group's frame, so its position is already derived"
        }
        val driver = (if (target.isPoint) target.ref.node else carrierNodeOf(target)) ?: return "${nameOf(target)} cannot carry a connection"
        if (joinWouldCycle(el, driver)) return "${nameOf(target)} already follows ${nameOf(el)}"
        if (corner != null) {
            val free = listOfNotNull(writableMaster(corner.xNode), writableMaster(corner.yNode)).distinct()
            if (free.isEmpty()) {
                return "both of ${nameOf(el)}'s coordinates are already driven, so it has no freedom left to give"
            }
            if (free.size < 2 && target.isPoint) {
                val driven = if (writableMaster(corner.xNode) == null) 0 else 1
                val axis = if (driven == 0) "x" else "y"
                return "${nameOf(el)}'s $axis is already held by ${heldBy(corner, driven)}, and welding onto a point pins both — " +
                    "reach the leg through that point instead, which needs only the one coordinate"
            }
        }
        return null
    }

    /** What already drives [corner]'s [axis] coordinate (0 = x, 1 = y), named for a refusal message. */
    private fun heldBy(
        corner: OrthoCornerHandle,
        axis: Int,
    ): String {
        val j = junctionOf(if (axis == 0) corner.xNode else corner.yNode)
        return j?.curve?.let { "the junction on ${nameOf(it)}" } ?: "another connection"
    }

    /** The node a junction on [curve] would ride — its carrier line, or the circle itself. */
    private fun carrierNodeOf(curve: Element): Node? =
        when {
            curve.isLinear -> carrierLine(curve).node
            curve.kind == ElementKind.CIRCLE -> curve.ref.node
            else -> null
        }

    /**
     * A junction riding [curve], placed where [near] currently is. Null if it would cycle.
     *
     * Its one degree of freedom is whatever [riderOn] found the honest parameter to be — a world coordinate
     * on a host that is axis-aligned by construction, a distance along the line otherwise — and its
     * [Junction.place] solves for that parameter in closed form, which is what keeps a driven coordinate
     * typeable as well as draggable (OP-13, OP-20).
     */
    private fun junctionOnCurve(
        curve: Element,
        near: Node,
    ): Junction? {
        val ev = Evaluator()
        val p = (ev.eval(near) as? EvalResult.Ok)?.let { (it.value as? PointValue)?.p } ?: return null
        val driver = carrierNodeOf(curve) ?: return null
        if (dependsOn(driver, near, HashSet())) return null
        val rider = riderOn(curve, p, "j") ?: return null
        val junction = Junction(rider.point, rider.handle, curve)
        junction.place = rider.place
        junction.placeable = rider.placeable
        // the freedom this junction *is*, kept so a placement can carry it (OP-16): without this the
        // parameter stayed a world quantity and a frame drag left every run hanging on the junction behind
        junction.param = rider.dof
        junction.form = rider.form
        junction.line = rider.line
        junction.axis = rider.axis
        junctions.add(junction)
        return junction
    }

    /**
     * Bind both of [corner]'s coordinates (via their masters) to [junction], so it owns neither.
     *
     * Refused for a corner of a **placed** path (OP-16): that corner's coordinates are the group's local
     * ones while a junction is a *world* position, so the bind would feed a world value into a local
     * coordinate and move the corner off the point it was joined to. The connection is refused rather than
     * approximated — the other direction (something outside joining *onto* a placed corner) is fine, and is
     * how a run reaches a placed wall.
     */
    private fun bindCornerToJunction(
        corner: OrthoCornerHandle,
        junction: Junction,
    ): Boolean = bindCornerToMeeting(corner, junction.point, junction, junction)

    /**
     * Bind both of [corner]'s coordinates (via their masters) to [point], registering each with the junction
     * that owns *that* coordinate — [xJunction] for x, [yJunction] for y, either of which may be null when the
     * coordinate is determined by construction and no freedom answers for it.
     *
     * One junction owning both is the ordinary meeting, and then [point] is that junction's own point: the
     * flat one-hop reach OP-20 asks for. The two differ when the target's coordinates come from **two
     * different places** — the corner between the legs of a T-web's middle run, which meets one wall at each
     * end (GitHub issue #4) — and then no single junction *is* the meeting point, so the meeting point is the
     * target's own vertex and each coordinate follows whatever drives it there. Binding both to one of the two
     * junctions instead put the arriving run at that junction's position rather than at the point clicked.
     */
    private fun bindCornerToMeeting(
        corner: OrthoCornerHandle,
        point: PointRef,
        xJunction: Junction?,
        yJunction: Junction?,
    ): Boolean {
        if (pathFrameOf(corner) != null) return false
        val mx = writableMaster(corner.xNode) ?: return false
        val my = writableMaster(corner.yNode) ?: return false
        // the definitive cycle test, against the freedom that will actually drive the corner: a weld onto
        // an *existing* junction binds to that junction's point, which need not be the point clicked
        if (dependsOn(point.node, mx, HashSet()) || dependsOn(point.node, my, HashSet())) return false
        driveByJunction(mx, xJunction, point, 0)
        if (my !== mx) driveByJunction(my, yJunction, point, 1)
        corner.isEndpoint = false
        return true
    }

    /**
     * Weld an ortho path endpoint [el] onto point [target]: the meeting point becomes a [Junction] too,
     * so a second run arriving at a junction reaches the shared freedom exactly as the first one does.
     * When [target] is already driven by a junction, that same junction is joined rather than a new one
     * invented on top of it.
     */
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
        if (!target.isPoint || target === el || joinWouldCycle(el, tref.node)) return false
        val tc = target.handle as? OrthoCornerHandle
        val tx = tc?.let { junctionOf(it.xNode) }
        val ty = tc?.let { junctionOf(it.yNode) }
        // The target's two coordinates may be driven from two *different* places — a corner of a T-web's
        // middle run meets one wall at each end, and a second end with one coordinate left is a determined
        // meeting owned by nobody (issue #4). No single junction is the meeting point then, so bind to the
        // target's own vertex and let each coordinate follow whatever drives it there.
        if (tx !== ty) return bindCornerToMeeting(corner, tref, tx, ty)
        // a target with no handle of its own — a derived point such as an intersection — makes a junction
        // that owns nothing: the meeting point is then fixed by construction, and honestly immovable
        val junction =
            tx ?: Junction(tref, target.handle, null).also {
                it.place = { axis, value ->
                    // a plain point owns its coordinates outright, so placing one is just a write
                    val field = it.handle?.fields()?.getOrNull(axis)
                    field?.write(Quantity.mm(value))
                    field?.writable == true
                }
                it.placeable = { axis -> it.handle?.fields()?.getOrNull(axis)?.writable == true }
                junctions.add(it)
            }
        return bindCornerToJunction(corner, junction)
    }

    /**
     * Connect an ortho path end [vertex] to whatever a click's snap [s] found: weld onto a point
     * (materializing an intersection first), or attach onto a curve. False when the snap found nothing to
     * join, or when the connection was refused ([connectRefusal] says why).
     *
     * **One helper for every route that joins while placing.** A path click uses it, and so does the rectangle
     * tool's pair of corners (GitHub issue #4) — which is what makes "the rectangle snaps like the ortho path"
     * a fact rather than a second implementation. The drag magnet's release performs the same two operations
     * from the other side, so a connection is the same construction however it was made.
     */
    fun linkPathEnd(
        vertex: PointRef,
        s: SnapResult,
    ): Boolean {
        val el = elementFor(vertex) ?: return false
        return when (s.kind) {
            SnapKind.POINT -> weldOrthoEndpointToPoint(el, s.target!!)
            SnapKind.INTERSECTION -> {
                val ip = intersectNear(s.target!!, s.other!!, s.pos) ?: return false
                elementFor(ip)?.let { weldOrthoEndpointToPoint(el, it) } ?: false
            }
            SnapKind.ON_CURVE -> attachOrthoEndpointToCurve(el, s.target!!)
            else -> false
        }
    }

    fun remove(el: Element) {
        elements.remove(el)
    }

    // ---- points ----

    /**
     * The point of the span `a → b` at [factor] of the way along it, or its **midpoint** when no factor was
     * given — one tool, because a midpoint *is* the ratio point at 0.5 (see [ScalarSlot.default]).
     *
     * With no factor this is exactly what it always was: a derived `cx.midpoint`, no parameter, no handle,
     * nothing added to the file. With one it is `cx.pointAtRatio`, whose dimensionless [factor] is an
     * ordinary parameter — so it is typed, dragged along the span ([RatioPointHandle]), wired, and **shared**:
     * one `t` node feeding several pairs is equal proportions by construction (OP-5), not a constraint.
     *
     * A factor outside `[0, 1]` extrapolates past an end, which is said in the note rather than clamped.
     */
    fun midpoint(
        a: PointRef,
        b: PointRef,
        factor: ScalarRef? = null,
    ): PointRef = if (factor == null) addDerived(cx.midpoint(a, b)) else ratioPoint(a, b, factor)

    /** The ratio point of `a → b` as a 1-DOF element over [t] — see [midpoint]. */
    private fun ratioPoint(
        a: PointRef,
        b: PointRef,
        t: ScalarRef,
    ): PointRef {
        val ref = addConstrained(cx.pointAtRatio(a, b, t), RatioPointHandle(a, b, t.node))
        val f = ((Evaluator().eval(t.node) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.value
        if (f != null && (f < 0.0 || f > 1.0)) {
            val end = if (f < 0.0) "the first point" else "the second"
            note = "Factor ${Format.num(f)} is outside 0…1, so the point sits beyond $end — drag it back, or type a factor between 0 and 1"
        }
        return ref
    }

    /** The centre of a circle or arc as a derived point (works on 3-point circles etc.). */
    fun centerOf(el: Element): PointRef? =
        when (el.kind) {
            ElementKind.CIRCLE -> addDerived(cx.circleCenter(el.ref as CircleRef))
            ElementKind.ARC -> addDerived(cx.arcCenter(el.ref as ArcRef))
            // an ellipse has a centre like any conic (OP-24); what it has *not* got is a radius, which is
            // why the radial dimension declines one by name rather than casting
            ElementKind.ELLIPSE, ElementKind.ELLIPTIC_ARC -> addDerived(cx.ellipseCenter(carrierEllipse(el)))
            else -> null
        }

    fun projectToLine(
        p: PointRef,
        line: Element,
    ) = addDerived(cx.projectToLine(p, carrierLine(line)))

    /**
     * Put a freedom back where a replay says it stood (OP-18) — the one write every route that restates a
     * *position along a host* goes through, whether the freedom belongs to a rider, to a point attached to a
     * curve or to a junction.
     *
     * The **dimension** decides whether the stored number belongs to this freedom at all: the forms of
     * [RiderForm] are a length, a bare number and an angle, so a file whose host has since changed kind states
     * a value this parameter cannot mean — and then the click's own placement stands, which is what a load
     * without a `dofs=` does anyway. Checked rather than trusted, because that mismatch is a *file*, not a bug.
     */
    private fun restateDof(
        dof: SourceNode?,
        restated: Quantity?,
    ) {
        if (dof != null && restated != null && restated.dim == (dof.value as? ScalarValue)?.q?.dim) {
            dof.value = ScalarValue(restated)
        }
    }

    /**
     * Add a rider element over [ref]. [dof] is the rider's own parameter node and [restated] the value a
     * replay hands back for it (OP-18): a rider's position is **state**, since dragging it writes that
     * parameter, so the step restates the parameter itself rather than leaving the file describing the click
     * that first placed the rider. Given verbatim, so a saved drawing reloads bit for bit.
     */
    private fun <V : Value> addConstrained(
        ref: Ref<V>,
        handle: Handle,
        dof: SourceNode? = null,
        restated: Quantity? = null,
        inSpace: Boolean = false,
    ): Ref<V> {
        restateDof(dof, restated)
        // through [add], because that is where an element's sketch space is stamped (OP-17): building the
        // Element here instead left every constrained point in the **plan** whatever space it was drawn in,
        // and an ortho path drawn on a face came out as legs on the face with their corners in the plan
        add(ref, ElementKind.ON_CURVE, Styles.ON_CURVE).also {
            it.handle = handle
            it.inSpace = inSpace
        }
        return ref
    }

    /**
     * Point that slides along a line; created at the projection of [at], draggable along the line.
     *
     * Its stored parameter follows the same rule as every other position along a host ([riderOn]): the world
     * coordinate the host leaves free where the host is axis-aligned by construction, so stretching that host
     * does not drag the point; a distance along the line where there is no such coordinate.
     */
    fun pointOnLine(
        line: Element,
        at: Vec2,
        dof: Quantity? = null,
    ): PointRef {
        val rider = riderOn(line, at, "")
        if (rider != null) return addRider(line, rider, dof, at)
        // the line cannot be evaluated (invalid upstream, OP-3): the point still exists and still slides, it
        // simply has nowhere to be until its line does
        val lineRef = carrierLine(line)
        val tNode = SourceNode(nextId("t"), ScalarValue(Quantity.mm(0.0)))
        val along = alongLine(lineRef, Ref<ScalarValue>(tNode))
        noteCarrierRider(along, lineRef, tNode)
        return addRider(
            line,
            Rider(along, OnLineHandle(lineRef, tNode), tNode, RiderForm.ALONG_LINE, line = lineRef, placeable = { false }) { _, _ -> false },
            dof,
        )
    }

    /**
     * Add a rider element over [rider] and record what it rides — see [RiderRecord].
     *
     * The single seam through which every rider's stored parameter arrives, which is why the format
     * migration ([migratedRiderDof]) is applied here and not in the two routes that call it.
     */
    private fun <V : Value> addRider(
        host: Element,
        rider: Rider<V>,
        dof: Quantity?,
        at: Vec2? = null,
    ): Ref<V> {
        val (value, finding) = migratedRiderDof(rider, at, dof)
        // Published through a **re-pointable view** ([IndirectNode], OP-16's substrate), never as the derived
        // on-curve node itself. A rider has no literal of its own, so *Make absolute* had nothing to hand back
        // and refused — while the very same point created by *dragging* a free point onto the curve detached,
        // because there the element still published its own `SourceNode`. The view is what makes the two
        // uniform: [detachRider] re-points it at a free source and every consumer follows in place, exactly as
        // a welded point's consumers follow its master (OP-5).
        val ref = addConstrained(cx.indirect(rider.point), rider.handle, rider.dof, value, rider.inSpace)
        elements.lastOrNull()?.let {
            noteRider(it, host, rider)
            if (finding != null) noteLoad("${nameOf(it)} on ${nameOf(host)}: $finding")
        }
        return ref
    }

    /**
     * What a **format-1** `dofs=` names for a rider along a carrier, and what had to be decided (OP-18,
     * *Versioning & migration*).
     *
     * This is the stored literal the anchoring rework put at risk (OP-20, *as built*): a distance along a
     * carrier used to be measured from the carrier **line's** `origin` — for a segment, one of the segment's
     * own endpoints — and is now measured from the point of the line nearest the world origin. Both readings
     * are a plain length in mm, so nothing in the file itself tells them apart, and reading one as the other
     * slides the rider along its host by the anchor's own offset.
     *
     * The arbiter is the step's **recorded position**, because a click is creation-time truth: whichever
     * reading reproduces it is the one the writer meant. Three outcomes, and only the first is silent —
     * - the position lies on the carrier and one reading lands on it: that reading, decided rather than
     *   guessed (and only the legacy one is a change);
     * - the position lies on the carrier and neither reading lands on it: the rider has been *moved* since it
     *   was created, so its position cannot arbitrate. Today's reading is kept, and the load says so;
     * - the position no longer lies on the carrier at all: an edit upstream has since turned or moved the
     *   host, which is ordinary (the position stays verbatim while the parameter is restated — OP-18) and
     *   leaves nothing to arbitrate with. Today's reading is kept, and the load says so.
     *
     * Only [RiderForm.ALONG_LINE] is affected: a world coordinate and an angle about a centre never changed
     * meaning, which is exactly why OP-20 chose them.
     */
    private fun migratedRiderDof(
        rider: Rider<*>,
        at: Vec2?,
        dof: Quantity?,
    ): Pair<Quantity?, String?> {
        val version = replayingVersion ?: return dof to null
        if (version >= 2 || dof == null || at == null) return dof to null
        if (rider.form != RiderForm.ALONG_LINE || dof.dim != Dimension.LENGTH) return dof to null
        val l =
            ((Evaluator().eval(rider.line?.node ?: return dof to null)) as? EvalResult.Ok)
                ?.let { (it.value as? LineValue)?.line } ?: return dof to null
        // the two anchors, as points: the line's nearest-world-origin point (today) and its own origin (v1)
        val today = l.origin - l.dir * l.origin.dot(l.dir) + l.dir * dof.mm
        val legacy = l.origin + l.dir * dof.mm
        val foot = l.origin + l.dir * (at - l.origin).dot(l.dir)
        val off = (at - foot).length()
        val kept = "kept its stored distance along the carrier, read the way this build writes it"
        if (off > RIDER_MIGRATION_TOL) {
            return dof to "its recorded position is ${Format.num(off)} mm off that carrier now, so it cannot say " +
                "which anchor the file meant — $kept"
        }
        if ((today - at).length() <= RIDER_MIGRATION_TOL) return dof to null
        if ((legacy - at).length() <= RIDER_MIGRATION_TOL) {
            // the same place, restated against the anchor that belongs to the line rather than to the host
            return Quantity.mm(dof.mm + l.origin.dot(l.dir)) to
                "its distance was measured from the carrier's own end (format 1) — re-anchored to the carrier, " +
                "in the place the file put it"
        }
        return dof to "it has been moved since it was created, so its recorded position cannot say which " +
            "anchor the file meant — $kept"
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

    /**
     * Point that slides along a circle; created at the click angle, draggable around the circle. The angle is
     * measured about the centre and so is already absolute — a circle has no ends whose move could re-anchor
     * it (OP-20).
     */
    fun pointOnCircle(
        circle: Element,
        at: Vec2,
        dof: Quantity? = null,
    ): PointRef {
        val rider = riderOn(circle, at, "")
        if (rider != null) return addRider(circle, rider, dof)
        val circleRef = carrierCircle(circle)
        val aNode = SourceNode(nextId("a"), ScalarValue(Quantity.rad(0.0)))
        val point = cx.pointOnCircle(circleRef, Ref<ScalarValue>(aNode))
        return addRider(
            circle,
            Rider(point, OnCircleHandle(circleRef, aNode), aNode, RiderForm.CIRCLE_ANGLE, circle = circleRef, placeable = { false }) { _, _ -> false },
            dof,
        )
    }

    /**
     * Point that slides along an **ellipse** (OP-24), created at the parametric angle of the click.
     *
     * The exact twin of [pointOnCircle], and exact in the same sense: the parameter is measured in the
     * ellipse's own frame, so it is already absolute — an ellipse has no ends whose move could re-anchor
     * it — and the point, the tangent and the normal at it are plain trigonometry.
     */
    fun pointOnEllipse(
        ellipse: Element,
        at: Vec2,
        dof: Quantity? = null,
    ): PointRef? {
        val rider = riderOn(ellipse, at, "") ?: return null
        return addRider(ellipse, rider, dof)
    }

    /**
     * A point that **rides a function curve** at the parameter the click states (session 71, curve half) —
     * the exact position-along a function curve offers, since nothing forces arc length to be the parameter
     * (OP-24's correction, quoted one curve family on).
     */
    fun pointOnFuncCurve(
        curve: Element,
        at: Vec2,
        dof: Quantity? = null,
    ): PointRef? {
        val rider = riderOn(curve, at, "") ?: return null
        return addRider(curve, rider, dof)
    }

    /**
     * A point that **rides the coil [curve]**, at the angle the click at [at] states (OP-26, the queue's own
     * design) — the point-on-a-circle gesture one dimension up.
     *
     * **The pick resolves the winding; the point does not.** [view] is the projection the click came through,
     * and it is the whole of the 2D/3D split ([HitTest.helixAngleAt]): in the plan every winding projects onto
     * the same image, so a click there states an angle in `[0°, 360°)` — the first winding — and typing reaches
     * any other one afterwards (OP-13); in the 3D view the pointer's ray meets the drawn curve on a known
     * winding, so the angle comes back past 360° directly. Same rider, two resolutions — the split a `PATH3`
     * pick already has.
     *
     * [dof] is the angle a replay hands back (OP-18) and wins over the click, for the reason every rider's
     * does: *which* curve is the choice replay repeats, while where the rider sits along it is state — and for
     * this rider it is state a click could not restate at all, since the plan cannot say which winding.
     *
     * Refused **by name**, building nothing, for the two structural things — a pick that is not a curve in
     * space, and a curve in space that is **not a helix**. The second is the deliberate scope of this rider:
     * a parameter along a spline through points would re-anchor whenever those points moved (`ALONG_LINE`'s
     * problem one dimension up), so it is declined with that reason and the alternative named. Everything about
     * *where* the angle is — past the end of the coil, below zero — is the node's business and comes back as
     * the reason it is invalid, healing when the number or the turn count moves (OP-3).
     */
    @Suppress("UNCHECKED_CAST")
    fun pointOnHelix(
        curve: Element,
        at: Vec2,
        view: PlaneProjection? = null,
        dof: Quantity? = null,
    ): Point3Ref? {
        if (curve.kind != ElementKind.SPACE_CURVE) {
            note = "Point on helix: ${nameOf(curve)} is ${kindWord(curve)}, and a rider rides a coil — click a coil"
            return null
        }
        val ev = Evaluator()
        val path = (ev.valueOf(curve.ref) as? Path3Value)?.path
        val helix = path?.elements?.singleOrNull() as? Curve3Element.Helix3
        if (helix == null) {
            note =
                if (path == null) {
                    "Point on helix: ${nameOf(curve)} has no value right now, so there is no coil to put a point on — " +
                        "${(ev.eval(curve.ref.node) as? EvalResult.Invalid)?.reason ?: "fix what it is built from"}"
                } else {
                    // the scope, named as a scope rather than as a limit (DESIGN.md records it as a future
                    // extension): the angle exists because a helix *is* an angle about an axis
                    "Point on helix: ${nameOf(curve)} is a curve in space but not a coil, and a point on it would " +
                        "need a parameter measured from the points it is built through — which re-anchors " +
                        "whenever they move. Use a Station to state a position along a run of any shape"
                }
            return null
        }
        val plane = planeOfSpace(curve.space)
        val angle =
            dof?.takeIf { it.dim == Dimension.ANGLE }?.base
                ?: (ev.valueOf(plane) as? PlaneValue)?.plane?.let { HitTest.helixAngleAt(helix, it, at, view) }
                ?: 0.0
        val aNode = SourceNode(nextId("h"), ScalarValue(Quantity.rad(angle)))
        val point = cx.pointOnHelix(curve.ref as Path3Ref, Ref<ScalarValue>(aNode))
        val ref =
            addRider(
                curve,
                Rider(
                    point,
                    OnHelixHandle(curve.ref as Path3Ref, plane, aNode),
                    aNode,
                    RiderForm.HELIX_ANGLE,
                    inSpace = true,
                    // a coil sweeps all three coordinates, and where an angle can put the point is the node's
                    // own business — a junction has nothing to place on a curve that leaves the plane
                    placeable = { false },
                ) { _, _ -> false },
                dof,
            )
        val el = elements.lastOrNull()
        note =
            "${el?.let { nameOf(it) }}: ${Format.num(Quantity.rad(angle).deg)}° along ${nameOf(curve)} — " +
            "winding ${1 + (angle / (2.0 * PI)).toInt()} of ${Format.num(helix.turns)}; drag it in the 3D view to " +
            "slide it along the whole coil, in the plan to move it round the winding it is on, or type the angle"
        return ref
    }

    /**
     * Intersect two curves. Segments/rays are treated as their carrier line. Branch count
     * follows the pair type (line-like ∩ line-like: 1 point, a conic pair: up to 4, else 2).
     */
    fun intersect(
        a: Element,
        b: Element,
    ): List<PointRef> {
        val c = intersectionSet(a, b) ?: return emptyList()
        val refs = ArrayList<PointRef>()
        if (c.byIndex) {
            // The count is **structural per extraction**, exactly as *Key points* over a footprint's corners
            // is (OP-21) and a Bézier's controls are: four crossed ellipses give four points, two give two,
            // and taking the intersection again after a reshape gives the branches there are then. Creating
            // four accessors regardless would put two permanently invalid points in the drawing.
            val n = cx.solutionCount(c.set, Evaluator())
            for (i in 0 until n) refs.add(cx.selectAt(c.set, i))
        } else {
            refs.add(cx.select(c.set, +1))
            if (c.branches > 1) refs.add(cx.select(c.set, -1))
        }
        refs.forEach { addDerived(it) }
        stateCrossingOnCircles(refs, a, b)
        return refs
    }

    /**
     * A crossing lies on **both** its operands, so where either is a circle or an arc the incidence is stated
     * (GitHub #19): a circle ∩ line point carries its circle exactly as a rider does, and a circle ∩ circle
     * point carries *two*, which is the one case where a tangent there has to be told which.
     */
    private fun stateCrossingOnCircles(
        refs: List<PointRef>,
        a: Element,
        b: Element,
    ) {
        for (el in listOf(a, b)) {
            if (el.isCentric) stateAllOnCircle(refs, carrierCircle(el), el)
        }
    }

    /**
     * One crossing's ordered solution set, how many branches its pair type admits, and **how a branch is
     * addressed** — by sign for the one- and two-branch families, by index for the quartic ones (OP-1,
     * OP-24; see `Construction.selectAt` for why an index rather than composed binary signs).
     *
     * Which of the two a step's `signs=` integer means is decided by the *pair kinds*, which are structural
     * (the step names both elements), so the two encodings can never be confused: a sign is ±1, an index is
     * 0…3.
     */
    private class Crossing(val set: PointSetRef, val branches: Int, val byIndex: Boolean)

    /**
     * How many branches a **function curve's** intersection is addressed over — its set is as long as the
     * function's own crossings are, which is a value, so the index discipline is the conic quartic's
     * (`byIndex`) and the declared count is only what the branch cycler offers. An index the geometry no
     * longer has is invalid with a reason and heals (OP-3).
     */
    private val FUNC_BRANCHES = 8

    /** [el]'s own function-curve ref — a function curve carries no simpler curve to be coerced onto. */
    @Suppress("UNCHECKED_CAST")
    private fun funcCurveRef(el: Element): FuncCurveRef = el.ref as FuncCurveRef

    /** The intersection solution set of [a] and [b], with its branch discipline. */
    @Suppress("UNCHECKED_CAST")
    private fun intersectionSet(
        a: Element,
        b: Element,
    ): Crossing? {
        val aLin = a.isLinear
        val bLin = b.isLinear
        // an arc intersects through its carrier circle, exactly as a segment does through its carrier line
        val aCirc = a.isCentric
        val bCirc = b.isCentric
        // ...and an elliptic arc through its carrier ellipse, the same coercion a third time (OP-24)
        val aEll = a.isElliptic
        val bEll = b.isElliptic
        // ...and a function curve, which has no carrier coercion at all: it is the curve itself (session 71)
        val aFn = a.kind == ElementKind.FUNC_CURVE
        val bFn = b.kind == ElementKind.FUNC_CURVE
        // function curve ∩ function curve is **not built**, and is named where it is refused rather than
        // silently returning nothing — see [intersectNearNow]
        if (aFn && bFn) return null
        return when {
            aLin && bLin -> Crossing(cx.intersectLL(carrierLine(a), carrierLine(b)), 1, false)
            aCirc && bCirc -> Crossing(cx.intersectCC(carrierCircle(a), carrierCircle(b)), 2, false)
            aLin && bCirc -> Crossing(cx.intersectLC(carrierLine(a), carrierCircle(b)), 2, false)
            aCirc && bLin -> Crossing(cx.intersectLC(carrierLine(b), carrierCircle(a)), 2, false)
            // a line meets an ellipse in a quadratic: the ordinary two-branch set, ordered along the line's
            // own direction — the very convention `intersectLC` uses, so a sign means one thing
            aLin && bEll -> Crossing(cx.intersectLE(carrierLine(a), carrierEllipse(b)), 2, false)
            aEll && bLin -> Crossing(cx.intersectLE(carrierLine(b), carrierEllipse(a)), 2, false)
            // ...and a conic meets an ellipse in a **quartic**: up to four, addressed by index
            aCirc && bEll -> Crossing(cx.intersectCE(carrierCircle(a), carrierEllipse(b)), 4, true)
            aEll && bCirc -> Crossing(cx.intersectCE(carrierCircle(b), carrierEllipse(a)), 4, true)
            aEll && bEll -> Crossing(cx.intersectEE(carrierEllipse(a), carrierEllipse(b)), 4, true)
            // …and a **function curve** meets a line, a circle or an ellipse numerically but deterministically
            // (session 71, curve half). Whichever way round the two were clicked, the *curve* is the first
            // operand of the set, because the ordering rule is OP-1's parametric one — ascending parameter
            // along it — and an index means nothing unless the set is always ordered the same way.
            aFn && bLin -> Crossing(cx.intersectFL(funcCurveRef(a), carrierLine(b)), FUNC_BRANCHES, true)
            aLin && bFn -> Crossing(cx.intersectFL(funcCurveRef(b), carrierLine(a)), FUNC_BRANCHES, true)
            aFn && bCirc -> Crossing(cx.intersectFC(funcCurveRef(a), carrierCircle(b)), FUNC_BRANCHES, true)
            aCirc && bFn -> Crossing(cx.intersectFC(funcCurveRef(b), carrierCircle(a)), FUNC_BRANCHES, true)
            aFn && bEll -> Crossing(cx.intersectFE(funcCurveRef(a), carrierEllipse(b)), FUNC_BRANCHES, true)
            aEll && bFn -> Crossing(cx.intersectFE(funcCurveRef(b), carrierEllipse(a)), FUNC_BRANCHES, true)
            else -> null
        }
    }

    /**
     * The single intersection of [a] and [b] nearest [near], as a derived point — the branch the
     * click indicated, persisted as its `Select(sign)` (OP-1), never re-guessed later.
     *
     * *Never re-guessed* includes a reload (OP-18): the branch is scored from [near] against the geometry as
     * it stood when the user pointed there, so the step restates the resolved [sign] and replay takes it
     * verbatim. Which branch is nearer a fixed position is exactly the sort of answer an edit elsewhere can
     * change, and a `Select` sign that lives only in the session's nodes is not stored at all.
     */
    fun intersectNear(
        a: Element,
        b: Element,
        near: Vec2,
        sign: Int? = null,
    ): PointRef? =
        recording("intersectnear", Arg.El(a), Arg.El(b), Arg.Pos(near)) {
            intersectNearNow(a, b, near, sign, remember = true)
        }

    private fun intersectNearNow(
        a: Element,
        b: Element,
        near: Vec2,
        stored: Int? = null,
        remember: Boolean = false,
    ): PointRef? {
        val crossing =
            intersectionSet(a, b) ?: run {
                if (a.kind == ElementKind.FUNC_CURVE && b.kind == ElementKind.FUNC_CURVE) {
                    // **cut whole, and named**: two arbitrary functions cross where a two-dimensional
                    // system of expressions vanishes, and seeding that honestly needs a subdivision this
                    // package did not build. What *does* exist is said, so the way forward is one click on:
                    note = "Intersect: ${nameOf(a)} and ${nameOf(b)} are both function curves, and a crossing " +
                        "of two of those is not built — a function curve meets a line, a circle, an arc or " +
                        "an ellipse, so cross it with one of those"
                }
                return null
            }
        val set = crossing.set

        // only where a *step* can restate them: the same helper serves the Outline tracer's handovers, which
        // are re-derived from that tool's own clicks and own no `signs=` of their own
        fun keep(
            sign: Int,
            ref: PointRef,
        ): PointRef {
            val out = addDerived(ref)
            if (remember) elements.lastOrNull()?.let { registerSigns(it, listOf(sign)) }
            stateCrossingOnCircles(listOf(out), a, b)
            return out
        }
        // a restated branch is taken as it stands — invalid included, which is ordinary invalidity (OP-3) and
        // not licence to pick the other one
        if (stored != null) {
            return keep(stored, if (crossing.byIndex) cx.selectAt(set, stored) else cx.select(set, stored))
        }
        val ev = Evaluator()
        val candidates =
            when {
                crossing.byIndex -> (0 until cx.solutionCount(set, ev)).toList()
                crossing.branches > 1 -> listOf(+1, -1)
                else -> listOf(+1)
            }
        val best =
            candidates
                .map { it to (if (crossing.byIndex) cx.selectAt(set, it) else cx.select(set, it)) }
                .mapNotNull { (sign, ref) ->
                    ((ev.eval(ref.node) as? EvalResult.Ok)?.value as? PointValue)?.let { Triple(sign, ref, (it.p - near).length()) }
                }.minByOrNull { it.third } ?: return null
        return keep(best.first, best.second)
    }

    /**
     * A point that slides along [el] at [at] — the on-curve form of a click landing on a curve.
     *
     * [dof] is the rider's parameter as a replay hands it back (OP-18): the click position stays what it was,
     * because *which* curve and which side of it are choices replay must repeat, while where the rider now
     * sits along that curve is state — dragged, typed, or compensated while its host turned (OP-20).
     */
    fun pointOnCurve(
        el: Element,
        at: Vec2,
        dof: Quantity? = null,
    ): PointRef? = recording("pointoncurve", Arg.El(el), Arg.Pos(at)) { pointOnCurveNow(el, at, dof) }

    private fun pointOnCurveNow(
        el: Element,
        at: Vec2,
        dof: Quantity?,
    ): PointRef? =
        when {
            el.isLinear -> pointOnLine(el, at, dof)
            el.kind == ElementKind.CIRCLE -> pointOnCircle(el, at, dof)
            el.kind == ElementKind.ELLIPSE -> pointOnEllipse(el, at, dof)
            el.kind == ElementKind.FUNC_CURVE -> pointOnFuncCurve(el, at, dof)
            else -> null
        }

    /**
     * Materialize a curve's defining points as derived points (works on transformed geometry too).
     *
     * **An area's defining points are its corners** (the OP-21 extension's *key points*): a wall footprint
     * hands back one `regionCorner` accessor per corner it has *right now*, which is what makes a wall's
     * corners pickable, snappable and dimensionable without the wall owning a set of elements whose size is
     * a function of its values — the thing OP-21 exists to forbid. The count is **structural per
     * extraction**, exactly as it is for a Bézier's controls: extract again after reshaping the carrier and
     * you get the corners there are then, while a surplus accessor goes invalid with a reason (OP-3).
     */
    @Suppress("UNCHECKED_CAST")
    fun extractPoints(el: Element): List<Ref<*>> {
        // A **curve in space** (OP-26) hands back the points that define it — the path's start and end, and a
        // coil's centre, which gives a helix exactly the arc's triple. Accessors, not copies: each one hangs
        // off the curve node ([Construction.pathStart]), so retyping a pitch or dragging a point the run
        // passes through moves it, and one route serves the coil, the curve through points, the connect, the
        // combined view, the intersection curve and the imported wireframe alike.
        if (el.kind == ElementKind.SPACE_CURVE) return spaceCurveKeyPoints(el)
        // **Anything that bounds an area hands back its corners** — a thick path's footprint *and* a traced
        // outline, through the one coercion that already exists for the seam's own slot ([regionOf]). It used
        // to be `kind == AREA` alone, so a traced outline — which [Element.isArea] accepts, and which the pick
        // therefore took — reached the `when` below, found no branch, and **created nothing while saying
        // nothing** (the session-33 class: an unspoken null becomes an empty status line). One predicate, both
        // kinds, no case per element.
        if (el.isArea) {
            val region = regionOf(el)
            val n = region?.let { cx.regionCornerCount(it, Evaluator()) } ?: 0
            if (region == null || n == 0) {
                note = "${nameOf(el)} has no corners to extract right now"
                return emptyList()
            }
            return (0 until n).map { cx.regionCorner(region, it) }.onEach { addDerived(it) }
        }
        val refs: List<PointRef> =
            when (el.kind) {
                ElementKind.SEGMENT -> listOf(cx.segmentStart(el.ref as SegmentRef), cx.segmentEnd(el.ref as SegmentRef))
                ElementKind.CIRCLE -> listOf(cx.circleCenter(el.ref as CircleRef))
                ElementKind.ARC -> listOf(cx.arcCenter(el.ref as ArcRef), cx.arcStart(el.ref as ArcRef), cx.arcEnd(el.ref as ArcRef))
                ElementKind.RAY -> listOf(cx.rayOrigin(el.ref as RayRef))
                // a spline's defining points *are* its four controls (OP-15), inner ones included — which is
                // what lets a derived Bézier be split as a construction over its own controls (see [breakCurve])
                ElementKind.BEZIER -> (0..3).map { cx.bezierControl(el.ref as BezierRef, it) }
                // an ellipse's defining points are its centre and its four axis endpoints — the vertices
                // and co-vertices, which is what a conic's key points are (OP-24)
                ElementKind.ELLIPSE ->
                    listOf(cx.ellipseCenter(el.ref as EllipseRef)) + (0..3).map { cx.ellipseAxisPoint(el.ref as EllipseRef, it) }
                // a function curve's defining points are its own two ends — the parameter's two extremes,
                // which is what a piece built onto its neighbours publishes (OP-15's rule, one family on)
                ElementKind.FUNC_CURVE ->
                    listOf(cx.funcCurveStart(el.ref as FuncCurveRef), cx.funcCurveEnd(el.ref as FuncCurveRef))
                // an elliptic arc adds its own two ends, exactly as a circular arc does
                ElementKind.ELLIPTIC_ARC ->
                    listOf(
                        cx.ellipseCenter(carrierEllipse(el)),
                        cx.ellipticArcStart(el.ref as EllipticArcRef),
                        cx.ellipticArcEnd(el.ref as EllipticArcRef),
                    )
                else -> emptyList()
            }
        // **A pick that yields nothing says so** (OP-3's rule about refusals, and the session-33 class named
        // above): an *infinite* line and a ray reaching past their origin have no ends to take, and a silent
        // no-op there is indistinguishable from a missed click. Named rather than guessed at, and stated once
        // for every kind that reaches here rather than per branch, so a kind added later cannot be silent.
        if (refs.isEmpty()) {
            note =
                "${nameOf(el)}: ${kindWord(el)} has no defining points to take — its own points are the ones " +
                "it was drawn through, which are already in the drawing; a segment, an arc, a circle, a " +
                "spline, a conic, a function curve, an outline or an area each hand back theirs"
            return emptyList()
        }
        refs.forEach { addDerived(it) }
        // an arc's own two ends lie on its carrier circle by construction (GitHub #19) — its centre does not,
        // which is why the incidence is stated per point rather than for everything an extraction hands back
        if (el.kind == ElementKind.ARC) stateAllOnCircle(refs.drop(1), carrierCircle(el), el)
        return refs
    }

    /**
     * The defining points of the curve in space [el]: its **start** and **end**, preceded by the **centre**
     * when it is a coil — a helix's own triple, which is the arc's (`ARC → centre, start, end`) one dimension
     * up.
     *
     * **A closed run hands back one point, not two.** Its last piece hands over to its first, so start and end
     * are the same place — and saying that with two coincident dots would put two elements where the drawing
     * has one distinguished point. The count is read off [Path3.closed], which is *structure* (OP-21's rule:
     * fixed when the node was built and never derived from the values), so it does not depend on two positions
     * happening to agree; and it is **structural per extraction**, exactly as a region's corner count and a
     * Bézier's controls are — extract again after the run is rebuilt and you get the points there are then.
     *
     * Refused by name, building nothing, when the run has no value to read that structure from: a curve with no
     * pieces has neither a start nor an end, and creating accessors regardless would leave permanently invalid
     * points in the drawing.
     */
    @Suppress("UNCHECKED_CAST")
    private fun spaceCurveKeyPoints(el: Element): List<Ref<*>> {
        val ref = el.ref as Path3Ref
        val ev = Evaluator()
        val path = (ev.valueOf(ref) as? Path3Value)?.path
        if (path == null || path.isEmpty) {
            note =
                "${nameOf(el)} has no run to take key points from right now" +
                ((ev.eval(el.ref.node) as? EvalResult.Invalid)?.let { " — ${it.reason}" } ?: "")
            return emptyList()
        }
        val isHelix = path.elements.singleOrNull() is Curve3Element.Helix3
        val refs =
            listOfNotNull(
                if (isHelix) cx.helixCentre(ref) else null,
                cx.pathStart(ref),
                if (path.closed) null else cx.pathEnd(ref),
            )
        refs.forEach { addSpacePoint(it, el.space) }
        val what =
            (if (isHelix) "centre, " else "") + "start" + (if (path.closed) " (it is closed, so its end is its start)" else " and end")
        note = "${nameOf(el)}: its $what — ${refs.size} point${if (refs.size == 1) "" else "s"} that follow the curve through every edit"
        return refs
    }

    /**
     * Add [ref] as a **derived point in space** (OP-26) — a key point of a curve in space, drawn in the plan
     * where it projects and in the 3D view where it stands ([Element.inSpace], [HitTest.distanceTo]).
     *
     * Stamped with the curve's own [space] rather than the active one: a point of a run belongs where the run
     * is addressed, which is the same rule a derived point of a 2D curve follows by being created in the space
     * that curve was drawn in.
     */
    private fun addSpacePoint(
        ref: Point3Ref,
        space: String,
    ): Point3Ref {
        add(ref, ElementKind.DERIVED_POINT, Styles.DERIVED_POINT).also {
            it.inSpace = true
            it.space = space
        }
        return ref
    }

    fun tangentFromPoint(
        p: PointRef,
        circle: Element,
    ): List<PointRef> {
        val carrier = carrierCircle(circle)
        val set = cx.tangentPointsFromPoint(p, carrier)
        val refs = listOf(cx.select(set, +1), cx.select(set, -1))
        refs.forEach { addDerived(it) }
        // a tangency is on the circle by construction, like every other point derived onto it (GitHub #19)
        stateAllOnCircle(refs, carrier, circle)
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
    ) = add(cx.segment(a, b), ElementKind.SEGMENT, Styles.CURVE).also { inheritTangency(it, a, b) }

    /**
     * Register the tangency a new segment **inherits** from a leg it lies along (GitHub #29).
     *
     * The reporter's own construction is the case: a fillet rounds the corner of `e3` and `e5`, they take its
     * key points, and they draw two fresh segments from those tangencies to the legs' far points — so the
     * drawing they extrude and blend is built from `e10` and `e11`, which nothing had ever said were tangent
     * to the arc, although by construction they could not be anything else. One pick then took one edge
     * where the run `e11 → arc → e10` is one ribbon.
     *
     * The rule, and it is structural throughout: **a segment whose two ends both lie on a tangent leg by
     * construction is that leg, as far as the rounding is concerned**, so it inherits the leg's tangency.
     * One end is the registered handover itself (identified by position, which is [sharedEndBetween]'s own
     * rule — the tangency has no node this segment shares) and the other lies on the leg by a fact the
     * construction stated ([liesOnLegByConstruction]). Two points on a line's carrier make the segment
     * collinear with the leg, so the claim is exact and, being structural, is invariant under every later
     * edit of the numbers.
     *
     * Nothing is inherited from a **chamfer**: its joints are not tangent, so there is no smoothness to pass
     * on — the same one fact, read by the one registry.
     */
    private fun inheritTangency(
        seg: Element,
        a: PointRef,
        b: PointRef,
    ) {
        val ev = Evaluator()
        val pa = (ev.valueOf(a) as? PointValue)?.p ?: return
        val pb = (ev.valueOf(b) as? PointValue)?.p ?: return
        for (j in jointRegistry.toList()) {
            if (!j.tangent) continue
            val at = (ev.valueOf(j.at) as? PointValue)?.p ?: continue
            // the joint is (rounding, leg); which is which is asked rather than assumed
            for ((leg, rounding) in listOf(j.a to j.b, j.b to j.a)) {
                if (!leg.isLinear || rounding === seg || leg === seg) continue
                val far =
                    when {
                        (pa - at).length() <= GeomMath.JOIN_TOL -> b
                        (pb - at).length() <= GeomMath.JOIN_TOL -> a
                        else -> continue
                    }
                if (!liesOnLegByConstruction(far.node, leg)) continue
                if (jointRegistry.any { it.a === seg && it.b === rounding || it.a === rounding && it.b === seg }) continue
                registerJoint(seg, rounding, j.at, tangent = true)
            }
        }
    }

    /**
     * Whether [point] lies on [leg] **because the construction says so** — never because it looks as if it
     * does (OP-14's rule, the [OnCircle] registry's own argument one curve kind over).
     *
     * Three statements count, and they are the three a drawing can make: the point is one of the two [leg]
     * was built from (a segment's own end), it is a handover some construction registered on [leg] (a
     * tangency, a bevel end), or it is a rider attached to [leg] (a gesture, recorded as a rider).
     */
    private fun liesOnLegByConstruction(
        point: Node,
        leg: Element,
    ): Boolean {
        if (builtRef(leg).node.inputs.any { it === point }) return true
        if (jointRegistry.any { (it.a === leg || it.b === leg) && it.at.node === point }) return true
        return elementOwning(point)?.let { riderOf(it)?.host === leg } == true
    }

    // ---- architectural: ortho path (shared-coordinate rectilinear polyline) ----

    /** Junctions, and the coordinate nodes each one drives — see [Junction] and [junctionOf]. */
    val junctions = ArrayList<Junction>()
    private val junctionByNode = HashMap<String, Junction>()

    /**
     * The junction driving [node], if its chain of bindings ends at one. This is how a handle whose
     * coordinate is driven finds the freedom that moves it: structurally, in one lookup.
     */
    fun junctionOf(node: SourceNode): Junction? {
        var n = node
        var guard = 0
        while (guard++ < 64) {
            junctionByNode[n.id]?.let { return it }
            n = n.boundTo as? SourceNode ?: return null
        }
        return null
    }

    /**
     * Make [node] follow coordinate [axis] of [driver], and record which [junction] answers for it — null when
     * that coordinate is determined by construction, so nothing can be asked to place it (see [Junction]).
     */
    private fun driveByJunction(
        node: SourceNode,
        junction: Junction?,
        driver: Ref<PointValue>,
        axis: Int,
    ) {
        node.boundTo = (if (axis == 0) cx.measureX(driver) else cx.measureY(driver)).node
        if (junction != null) junctionByNode[node.id] = junction
    }

    private fun scalarSource(value: Double): SourceNode = SourceNode(nextId("oc"), ScalarValue(value.mm))

    private fun orthoVertex(
        x: SourceNode,
        y: SourceNode,
        ownAxis: Int,
    ): OrthoVertex {
        val corner = OrthoCornerHandle(x, y, this)
        corner.ownCoord = if (ownAxis == -1) 0 else ownAxis // start: fixed once its first edge is drawn
        // the coordinates make the vertex's *own* position; it is published through a re-pointable view, so
        // a placement can put the frame in front of it without rewiring a single consumer (OP-16, OP-5)
        val local = cx.pointXY(Ref<ScalarValue>(x), Ref<ScalarValue>(y))
        val ref = cx.indirect(local)
        addConstrained(ref, corner)
        return OrthoVertex(ref, corner, ownAxis, local, scalarSource(0.0), scalarSource(0.0))
    }

    val orthoPaths = ArrayList<OrthoPath>()

    /** The path being drawn or extended — what a following vertex step belongs to. */
    var currentOrthoPath: OrthoPath? = null
        private set

    /**
     * The open ortho path that [el] terminates, and whether it is that path's *last* vertex.
     *
     * Clicking an open end continues that path rather than starting a new one welded onto it. Two paths
     * meeting head-on could not coalesce a straight-on step, so extending produced a phantom corner
     * where the drawing looked like one straight run.
     */
    fun resumableEnd(el: Element): Pair<OrthoPath, Boolean>? {
        // only a *dangling* end continues. An end already connected to something is a terminus — a run
        // meeting a wall — and clicking it starts a branch there, which is the other thing a click on an
        // endpoint can mean and the only way to get a T-junction.
        if (orthoEndpoint(el) == null) return null
        for (path in orthoPaths) {
            if (path.closed || path.vertices.size < 2) continue
            // A **placed** path is not extended in place (OP-16): drawing works in world coordinates while
            // the path holds local ones, and a rubber band that snapped to the world axes would promise a
            // leg the frame cannot hold. Clicking its end starts a new run joined there instead, which is
            // what clicking an already-connected end has always done.
            if (path.frame != null) continue
            if (path.vertices.last().ref === el.ref) return path to true
            if (path.vertices.first().ref === el.ref) return path to false
        }
        return null
    }

    /** Continue [path] from one of its ends — see [resumableEnd]. */
    fun resumeOrthoPath(
        path: OrthoPath,
        atEnd: Boolean,
    ): OrthoPath {
        val end = if (atEnd) path.vertices.last() else path.vertices.first()
        recording("orthoresume", Arg.El(elementFor(end.ref) ?: return path)) { currentOrthoPath = path }
        return path
    }

    /** Add a leg at either end of [path]: appending, or prepending when resumed at its start. */
    fun extendOrthoPath(
        path: OrthoPath,
        atEnd: Boolean,
        to: Vec2,
    ): OrthoVertex? = if (atEnd) addOrthoVertex(path, to) else prependOrthoVertex(path, to)

    /**
     * Prepend a leg before [path]'s first vertex — the mirror of [addOrthoVertex], including its
     * coalescing: a step continuing along the first leg's axis extends that leg instead of leaving a
     * straight-through corner. The new vertex follows the old start on the perpendicular coordinate,
     * exactly as an appended one follows the old end, so every invariant holds either way.
     */
    fun prependOrthoVertex(
        path: OrthoPath,
        to: Vec2,
    ): OrthoVertex? = recording("orthoprepend", Arg.Pos(to), skipIfEmpty = true) { prependOrthoVertexNow(path, to) }

    private fun prependOrthoVertexNow(
        path: OrthoPath,
        to: Vec2,
    ): OrthoVertex? {
        val first = path.vertices.first()
        val p = ((Evaluator().eval(first.ref.node) as? EvalResult.Ok)?.value as? PointValue)?.p ?: return null
        val dx = to.x - p.x
        val dy = to.y - p.y
        if (abs(dx) < Vec2.EPS && abs(dy) < Vec2.EPS) return null
        val axis = if (abs(dx) >= abs(dy)) 0 else 1
        if (path.legCount > 0 && path.legAxis(0) == axis) { // straight on: lengthen the first leg
            val node = writableMaster(if (axis == 0) first.corner.xNode else first.corner.yNode) ?: return null
            node.value = ScalarValue(Quantity.mm(if (axis == 0) to.x else to.y))
            return first
        }
        val xNode: SourceNode
        val yNode: SourceNode
        if (axis == 0) {
            xNode = scalarSource(to.x)
            yNode = scalarSource(p.y).also { it.boundTo = first.corner.yNode }
        } else {
            xNode = scalarSource(p.x).also { it.boundTo = first.corner.xNode }
            yNode = scalarSource(to.y)
        }
        val v = orthoVertex(xNode, yNode, axis)
        v.corner.legAnchor = if (axis == 0) first.corner.xNode else first.corner.yNode
        first.corner.isEndpoint = false
        path.vertices.add(0, v)
        path.legs.add(0, dragLeg(path, segment(v.ref, first.ref)))
        path.legAxes.add(0, axis)
        refreshOrthoLoop(path)
        return v
    }

    /** Start a retained ortho path at [at] with a fresh, draggable vertex owning both coordinates. */
    fun startOrthoPath(at: Vec2): OrthoPath =
        recording("orthostart", Arg.Pos(at)) {
            val path = OrthoPath()
            path.vertices.add(orthoVertex(scalarSource(at.x), scalarSource(at.y), -1))
            orthoPaths.add(path)
            currentOrthoPath = path
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
        refreshOrthoLoop(path)
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
    ): Element = leg.also { it.handle = OrthoEdgeHandle(this, path, it) }

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
        // a break *replaces* the leg, and a macro definition names its elements (OP-6): retiring one
        // would leave a definition — and every instance's element count — describing geometry that is
        // gone, so the topology edit is refused instead
        if (definesAMacro(listOf(leg))) return false
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
        // a placed path holds *local* coordinates (OP-16), so the world positions this break was clicked at
        // are mapped into the frame first — and the vertices it creates are published through it below
        val f = path.frame?.let { frameValue(it) }
        val m0 = f?.toLocal(mPos) ?: mPos
        val n0 = f?.toLocal(nPos) ?: nPos
        val along = if (axis == 0) m0.x else m0.y
        val perp = if (axis == 0) n0.y else n0.x

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
        captureVertex(path, m)
        captureVertex(path, n)
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
        refreshOrthoLoop(path)
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
     * half over to meet it. Null keeps whatever the surviving node already holds. It is a value in the
     * path's *own* space (local under a frame, OP-16), which is why callers read it with [legPerpValue]
     * from the node rather than off the drawn segment: the two cannot then drift apart.
     */
    fun joinCollapsedLeg(
        path: OrthoPath,
        legIndex: Int,
        keepPerp: Double? = null,
    ): Element? {
        val leg = path.legs.getOrNull(legIndex) ?: return null
        // as for a break: a join retires the jog's legs and corner points, which a macro definition may
        // name (OP-6). Refusing leaves the jog exactly as a drag with Alt would.
        val retired =
            (legIndex - 1..legIndex + 1).mapNotNull { path.legs.getOrNull(it) } +
                listOfNotNull(
                    path.vertices.getOrNull(legIndex)?.let { elementFor(it.ref) },
                    path.vertices.getOrNull(legIndex + 1)?.let { elementFor(it.ref) },
                )
        if (definesAMacro(retired)) return null
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
        refreshOrthoLoop(path)
        return merged
    }

    /**
     * Leg [i] of [path]'s perpendicular coordinate **as the path holds it** — local when the path is placed
     * (OP-16), world otherwise. What a join keeps when this is the stationary half ([joinCollapsedLeg]).
     */
    fun legPerpValue(
        path: OrthoPath,
        i: Int,
    ): Double? {
        val corner = path.legEnds(i).first.corner
        val node = if (path.legAxis(i) == 0) corner.yNode else corner.xNode
        return ((Evaluator().eval(node) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.mm
    }

    // ---- the loop an ortho path publishes: its legs, rounded at the corners a fillet gave a radius ----

    /**
     * The closed boundary of [path] **as the path now reads**: every leg in draw order, each followed by the
     * corner piece at the vertex it ends at where a fillet or chamfer gave that corner one (GitHub #25).
     *
     * One authority, read by everything: the region an extrude/revolve/loft takes, the wall's own reading,
     * the area's key points, the boundary follow, the area pick filter and the picture (a leg *is* its
     * trimmed self, so the canvas and hit-testing need no case at all). The order is the path's own — a
     * retained ordered chain that knows it is closed — so nothing here is discovered (OP-14).
     */
    fun roundedPiecesOf(path: OrthoPath): List<Element> {
        if (path.corners.isEmpty()) return path.legs.toList()
        val out = ArrayList<Element>(path.legs.size + path.corners.size)
        for (i in 0 until path.legCount) {
            out.add(path.legs[i])
            val at = (i + 1) % path.vertices.size
            path.corners[at]?.let { piece -> if (elements.any { it === piece }) out.add(piece) }
        }
        return out
    }

    /**
     * The loop [path] publishes — created on first ask, and **rebound** whenever the pieces it is made of
     * change: a corner rounded ([recordOrthoCorner]), a leg broken or joined (OP-19), a vertex appended.
     *
     * One view rather than a fresh node per ask, so that everything already built on the loop follows the
     * path's own edits; the piece list it is bound to is compared with the path's current one here, which is
     * the one place that can be asked without a mutator having to remember to say so.
     */
    fun orthoLoopOf(path: OrthoPath): LoopRef {
        val view = path.loop
        if (view == null) {
            val pieces = roundedPiecesOf(path)
            return cx.indirect(cx.loop(*pieces.map { it.ref }.toTypedArray())).also {
                path.loop = it
                path.loopPieces = pieces
            }
        }
        return view
    }

    /** Rebind [path]'s published loop when its pieces have changed — see [orthoLoopOf]. */
    private fun refreshOrthoLoop(path: OrthoPath) {
        val view = path.loop?.node as? IndirectNode ?: return
        val pieces = roundedPiecesOf(path)
        if (pieces.size == path.loopPieces.size && pieces.indices.all { pieces[it] === path.loopPieces[it] }) return
        view.boundTo = cx.loop(*pieces.map { it.ref }.toTypedArray()).node
        path.loopPieces = pieces
    }

    /**
     * Record a rounding as **this corner's own radius** when [leg1] and [leg2] are two adjacent legs of one
     * ortho path and the corner between them was actually superseded ([trimmed] both legs).
     *
     * Both conditions are structural and both matter. Two legs of *one* path naming *one* vertex is what
     * makes this that corner's radius rather than an arc beside it; and a rounding that trimmed only one leg
     * (or neither) is a rounding against the legs' carriers somewhere else — its arc is not in this corner,
     * so the loop must not be told it is (see [supersedeWithTrim]).
     */
    private fun recordOrthoCorner(
        leg1: Element,
        leg2: Element,
        by: Element,
        trimmed: Int,
        size: ScalarRef,
        rounded: Boolean,
    ) {
        if (trimmed < 2) return
        val (path, at) = orthoCornerBetween(leg1, leg2) ?: return
        path.corners[at] = by
        // **the corner's own number, bound in place** (OP-5): every reader that took this path's corners as
        // inputs when it was built — a wall's footprint, above all — now sees the radius without one node
        // being rewired, which is why the number is a node from the vertex's first moment ([OrthoVertex.round])
        val cut = path.vertices[at]
        if (rounded) cut.round.boundTo = size.node else cut.bevel.boundTo = size.node
        // **A deliberate semantic change, said out loud on load** (GitHub #25, OP-18's versioning doctrine).
        // No stored literal changed shape and none is re-read: the same `tool fillet els=eA,eB` step creates
        // the same one element, and whether its two legs are adjacent legs of one path is a fact of the
        // *drawing*. What is different is what such a file **means** — the corner is now the path's own
        // radius rather than an arc drawn beside it — which is exactly the fix the report asked for. The
        // version is what lets the load say so **once**, corner by corner, instead of a note that would go
        // on firing for ever on drawings that always meant this (`DocumentFormat.SUPERSEDING_FILLET_VERSION`).
        if (replayingVersion == null) {
            // ...and a live gesture says what it did, for the reason every solid tool does (GitHub #9's
            // silent-success sweep): the arc looks the same either way, and what changed is the *loop*
            val cutWord = if (rounded) "radius" else "setback"
            note =
                "${nameOf(by)} is the corner $cutWord of ${nameOf(leg1)} and ${nameOf(leg2)} — the path's own " +
                "loop is cut there, so an extrude, a wall and an outline over it follow this corner; " +
                "the $cutWord stays an ordinary parameter"
        } else if (replayingVersion!! < DocumentFormat.SUPERSEDING_FILLET_VERSION) {
            noteLoad(
                "${nameOf(by)} ${if (rounded) "rounds" else "bevels"} the corner of ${nameOf(leg1)} and " +
                    "${nameOf(leg2)} — it is now that corner's own ${if (rounded) "radius" else "setback"}, so " +
                    "the path's loop and everything built on it (an extrude, a wall, an outline) follow it",
            )
        }
        // everything that already holds this path's loop follows the rounded one, with nothing rewired
        refreshOrthoLoop(path)
    }

    /** The path and vertex index two picked legs share, or null when they are not two adjacent legs of one path. */
    private fun orthoCornerBetween(
        leg1: Element,
        leg2: Element,
    ): Pair<OrthoPath, Int>? {
        val (p1, i) = legOf(leg1) ?: return null
        val (p2, j) = legOf(leg2) ?: return null
        if (p1 !== p2 || i == j) return null
        val n = p1.vertices.size
        val shared = (setOf(i, (i + 1) % n) intersect setOf(j, (j + 1) % n)).singleOrNull() ?: return null
        return p1 to shared
    }

    /** The closed ortho path [el] is a **corner piece** of — a fillet's arc, a chamfer's bevel — or null. */
    private fun orthoCornerOf(el: Element): OrthoPath? = orthoPaths.firstOrNull { p -> p.corners.values.any { it === el } }

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

    // ---- break a plain curve: a segment, an arc or a Bézier (OP-19 generalized off the ortho path) ----
    //
    // The gesture is the ortho break's: click a curve, get two curves that together *are* the one you
    // clicked, plus the freedom at the joint. What differs is that there is no path topology to edit, so the
    // split has to be an ordinary **construction** — and therefore has to say what it is built on:
    //
    // - a segment splits at a **free point** on it (the projection of the click), and the halves are two
    //   plain segments over the same two endpoints. The point is the whole purpose: it bends the joint.
    // - an arc splits at a **rider** at the click's angle, and the halves are `arcBetween` on the arc's own
    //   carrier circle, so both stay exactly on it whatever the arc's parameters do (OP-14).
    // - a Bézier splits by **de Casteljau, as a construction**: every intermediate point is a `pointAtRatio`
    //   over one shared, live `t` parameter, so the split is exact *and* slides — dragging any of the ratio
    //   points, or typing `t`, re-splits the curve (OP-15, OP-5: sharing a node is equality).
    //
    // **The consumer rule (OP-5).** Nothing is ever rewired, so the original curve's node keeps whatever
    // meaning it had. If nothing reads it, the break *replaces* it: the step that drew it is dropped and the
    // script replayed, so the file reads as the two halves the drawing now has (the caller performs that —
    // see `Editor.breakCurveAt`). If something does read it — a fillet leg, a rider, an outline piece, a
    // dimension — the original **stays**, hidden by a recorded `hide` step (OP-18), and the status says which
    // element is why. Never a silent change to what a consumer means.

    /**
     * What a break of [el] produced: the point at the joint, the two halves, and whether the original is now
     * redundant — see [breakCurve].
     */
    class BreakResult internal constructor(
        val original: Element,
        val split: Element,
        val halves: List<Element>,
        /**
         * True when the original's creating step is to be **dropped**: nothing read its node, and the halves
         * do not either (they are built from the same points it was). The journal rewrite is the caller's,
         * because replaying a rewritten script swaps the whole document (`Editor.adopt`).
         */
        val replacesOriginal: Boolean,
    )

    /**
     * Everything that already reads [el] — the break's **consumer test**, and the one thing that decides
     * whether the original may go (OP-5).
     *
     * Three ways to be a consumer, because there are three ways to depend on an element: a **node** built
     * over it (a fillet leg, an outline piece, a trim, a rider), a **scalar** measured from it (OP-4), and a
     * later **step naming it** without being built from it (a group's membership, a visibility decision).
     * The last counts because dropping the creating step would change what that step says.
     */
    fun consumersOf(el: Element): List<String> {
        // all three of them (GitHub #25): a rider took the leg's carrier, an outline took the leg itself
        val nodes = publishedNodes(el)
        val own = creatingStep(el)
        val out = ArrayList<String>()
        val reads = { n: Node -> nodes.any { dependsOn(n, it, HashSet()) } }
        elements.filter { it !== el && publishedNodes(it).none { n -> n in nodes } && reads(it.ref.node) }.forEach { out.add(it.id) }
        scalars.filter { reads(it.ref.node) }.forEach { out.add(it.name) }
        if (journal.any { s -> s !== own && referencedElements(s).any { it === el } }) out.add("a later step")
        return out.distinct()
    }

    /**
     * Split the curve [el] at [world] — a segment, an arc or a cubic Bézier. Null with a [note] saying why
     * when the break is impossible.
     *
     * The split lands **exactly** on the curve at the click (the projection, the click's angle, the click's
     * nearest parameter), so the drawing does not change shape at the moment of the break; every freedom the
     * break introduces is a source node or a parameter, so it is draggable and typeable from that instant on.
     */
    fun breakCurve(
        el: Element,
        world: Vec2,
    ): BreakResult? {
        // a break *replaces* a curve, and a macro definition names its elements (OP-6) — the same refusal
        // [breakOrthoLeg] makes, for the same reason
        if (definesAMacro(listOf(el))) {
            note = "${nameOf(el)} is part of a tool's definition — breaking it would replace it; retire the tool first"
            return null
        }
        if (creatingStep(el) == null) {
            note = "${nameOf(el)} has no construction step, so a break has nothing to build the halves from"
            return null
        }
        // A **placed** group holds its members' positions frame-relative (OP-16), and the freedom a break
        // introduces is a new world point — which the frame would then not carry, so the halves would come
        // apart the moment the group moved. Refused rather than half-joined, exactly as extending a placed
        // path in place is: membership is recorded in the group's step and a step's arguments are never
        // rewritten, so the new point cannot simply join the group.
        placedGroupOf(el)?.let { g ->
            note = "${nameOf(el)} belongs to placed group ${g.name} — unplace it to break it, or the joint would not follow the frame"
            return null
        }
        return when (el.kind) {
            ElementKind.SEGMENT -> breakSegmentAt(el, world)
            ElementKind.ARC -> breakArcAt(el, world)
            ElementKind.BEZIER -> breakBezierAt(el, world)
            ElementKind.ELLIPTIC_ARC, ElementKind.ELLIPSE -> breakEllipticAt(el, world)
            // deliberately not built, and refused with the thing that *does* do it: a function curve's
            // extent **is** its domain, so narrowing `from`/`to` in its fields is the trim, and two pieces
            // are two curves over two domains (recorded as a future extension in DESIGN.md)
            ElementKind.FUNC_CURVE -> {
                note = "${nameOf(el)} is a function curve — its extent is its domain, so change its from/to " +
                    "fields instead of breaking it"
                null
            }
            else -> {
                note = "${nameOf(el)} is not a segment, an arc, a Bézier or a conic"
                null
            }
        }
    }

    /** How close to an end (as a share of the curve) a click is too close to split at. */
    private val breakEndSlack = 1e-3

    /**
     * The point elements the step that **drew** [el] picked — its own defining points, when it has any.
     *
     * Building the halves from *these* is what makes the original redundant: the halves then descend from the
     * same points, not from the curve, so the step that drew it can be dropped outright. When the curve was
     * not drawn from points (a rectangle's side, a mirror, a trimmed piece) the break falls back to its **key
     * points** ([extractPoints]), which *are* built on it — so the original stays, hidden.
     */
    private fun drawnFromPoints(
        el: Element,
        toolId: String,
        n: Int,
    ): List<Element>? {
        val step = creatingStep(el) ?: return null
        if (step.kind != "tool" || (step.args.firstOrNull() as? Arg.Text)?.s != toolId) return null
        val pts = (step.args.filterIsInstance<Arg.Keyed>().firstOrNull { it.key == "pts" }?.value as? Arg.Els) ?: return null
        if (pts.els.size != n) return null
        if (pts.els.any { p -> elements.none { it === p } || !p.isPoint }) return null
        return pts.els
    }

    /** [el]'s key points, materialized as a recorded `keypoints` application so replay rebuilds them. */
    private fun keyPointsOf(
        el: Element,
        n: Int,
    ): List<Element>? = runAsTool(Tools.KEY_POINTS, emptyList(), listOf(el))?.takeIf { it.size == n }

    /**
     * Run tool [toolId] over already-known picks and record it as an ordinary `tool` step (OP-18).
     *
     * The break builds its halves this way rather than through step kinds of its own: a half of a segment
     * *is* a segment, a half of a Bézier *is* a Bézier, and a de Casteljau point *is* the ratio point the
     * Midpoint tool makes — so the file says exactly that, and replays through the same [ToolDef.build] a
     * click would have run.
     */
    private fun runAsTool(
        toolId: String,
        points: List<PointRef>,
        els: List<Element> = emptyList(),
        scalars: List<ScalarEntry> = emptyList(),
    ): List<Element>? {
        val def = Tools.byId(toolId) ?: return null
        val picks = Picks(points, els, Vec2(0.0, 0.0), emptyList())
        val before = elements.toHashSet()
        runTool(def, picks, scalars)
        return elements.filter { it !in before }.ifEmpty { null }
    }

    /** Split a plain segment at the projection of [world]: a free point there, and the two halves. */
    private fun breakSegmentAt(
        el: Element,
        world: Vec2,
    ): BreakResult? {
        val seg =
            (Evaluator().eval(el.ref.node) as? EvalResult.Ok)?.value as? SegmentValue ?: run {
                note = "${nameOf(el)} has no position to split (its construction is invalid)"
                return null
            }
        val ab = seg.seg.b - seg.seg.a
        if (ab.length() < Vec2.EPS) {
            note = "${nameOf(el)} has no length to split"
            return null
        }
        // the projection of the click onto the segment — so the two halves *are* the one segment, exactly
        val t = (world - seg.seg.a).dot(ab) / ab.dot(ab)
        if (t <= breakEndSlack || t >= 1.0 - breakEndSlack) {
            note = "Click away from ${nameOf(el)}'s ends — a break there would leave a zero-length piece"
            return null
        }
        val at = seg.seg.a + ab * t
        val own = drawnFromPoints(el, Tools.SEGMENT, 2)
        val consumers = consumersOf(el)
        val ends =
            own ?: keyPointsOf(el, 2) ?: run {
                note = "${nameOf(el)} has no endpoints to hang the halves on"
                return null
            }
        val p = freePoint(Quantity.mm(at.x), Quantity.mm(at.y))
        val split = elementFor(p) ?: return null
        val h1 = runAsTool(Tools.SEGMENT, listOf(ends[0].ref as PointRef, p))?.single() ?: return null
        val h2 = runAsTool(Tools.SEGMENT, listOf(p, ends[1].ref as PointRef))?.single() ?: return null
        return settle(el, split, listOf(h1, h2), consumers, detached = own != null, why = "the halves are built on it")
    }

    /**
     * Split an arc at the click's angle: a rider there, and the two arcs between it and the arc's ends.
     *
     * Both halves are `arcBetween` on the **same carrier** — the arc's own circle — so they stay on it by
     * construction however its centre or radius moves, and the sweep direction is the carrier's own, stored
     * verbatim in the step (a discrete branch choice, OP-1, never re-guessed from the click).
     */
    private fun breakArcAt(
        el: Element,
        world: Vec2,
    ): BreakResult? {
        val arc =
            ((Evaluator().eval(el.ref.node) as? EvalResult.Ok)?.value as? ArcValue)?.arc ?: run {
                note = "${nameOf(el)} has no position to split (its construction is invalid)"
                return null
            }
        val angle = (world - arc.center).angle()
        if (!GeomMath.arcContains(arc, angle)) {
            note = "That point is not on ${nameOf(el)}'s sweep — click on the arc itself"
            return null
        }
        val sweep = abs(GeomMath.sweep(arc))
        val from = abs(atan2(sin(angle - arc.startAngle), cos(angle - arc.startAngle)))
        val to = abs(atan2(sin(angle - arc.endAngle), cos(angle - arc.endAngle)))
        if (sweep < Vec2.EPS || from / sweep <= breakEndSlack || to / sweep <= breakEndSlack) {
            note = "Click away from ${nameOf(el)}'s ends — a break there would leave a zero-length piece"
            return null
        }
        val consumers = consumersOf(el)
        val made =
            breakArc(el, Quantity.rad(angle), arc.ccw) ?: run {
                note = "${nameOf(el)} could not be split there"
                return null
            }
        // an arc's halves are trims of *it* (OP-14: a trimmed circle is an arc), so the original is always a
        // consumer of the break — it is the carrier both halves share, and it therefore always stays
        return settle(el, made[0], made.drop(1), consumers, detached = false, why = "the two arcs share it as their carrier")
    }

    /**
     * The recorded half of an arc break — one step, because everything it makes hangs off the arc it names
     * and its own [angle] (the rider's position along the carrier: **state**, hence restated on save, OP-18).
     * [ccw] is the carrier's sweep direction, stored verbatim (OP-1).
     */
    fun breakArc(
        el: Element,
        angle: Quantity,
        ccw: Boolean,
    ): List<Element>? =
        recording("breakarc", Arg.El(el), Arg.Num(angle), Arg.Text(if (ccw) "ccw" else "cw")) {
            breakArcNow(el, angle, ccw)
        }

    private fun breakArcNow(
        el: Element,
        angle: Quantity,
        ccw: Boolean,
    ): List<Element>? {
        if (el.kind != ElementKind.ARC) return null
        @Suppress("UNCHECKED_CAST")
        val ref = el.ref as ArcRef
        val arc = ((Evaluator().eval(ref.node) as? EvalResult.Ok)?.value as? ArcValue)?.arc ?: return null
        val a = angle.requireDim(Dimension.ANGLE, "split angle").base
        val at = arc.center + Vec2(arc.radius * cos(a), arc.radius * sin(a))
        // a rider on the carrier circle: the click is *where*, the angle is *what it holds* — the same
        // split of choice and state every other rider's step makes (OP-18)
        val rider = pointOnCircle(el, at, angle)
        val split = elementFor(rider) ?: return null
        val h1 = add(cx.arcBetween(ref, cx.arcStart(ref), rider, ccw), ElementKind.ARC, Styles.CURVE)
        val h2 = add(cx.arcBetween(ref, rider, cx.arcEnd(ref), ccw), ElementKind.ARC, Styles.CURVE)
        return listOf(split, h1, h2)
    }

    /**
     * Split an **ellipse or an elliptic arc** at the click's parametric angle (OP-24) — the exact mirror of
     * [breakArcAt], with one thing said about the closed case.
     *
     * An *arc* splits into the two pieces between the cut and its own ends. A *whole ellipse* has no ends,
     * so one cut would leave one piece that closes on itself; it is therefore cut at the click **and at
     * its antipode**, `t + π` — which is not a second freedom but the *same* one, since the antipodal
     * point is a construction over the rider's own parameter. Dragging the rider swings both cuts
     * together and the two half-ellipses stay a partition of the whole.
     *
     * Both halves are `ellipticArcBetween` on the **same carrier**, so they follow the ellipse however its
     * centre, axes or orientation move, and the sweep direction is stored verbatim (OP-1).
     */
    private fun breakEllipticAt(
        el: Element,
        world: Vec2,
    ): BreakResult? {
        val ev = Evaluator()
        val ellipse =
            when (val v = ev.valueOf(el.ref)) {
                is EllipseValue -> v.ellipse
                is EllipticArcValue -> v.arc.ellipse
                else -> null
            } ?: run {
                note = "${nameOf(el)} has no position to split (its construction is invalid)"
                return null
            }
        val t = Conics.paramOf(ellipse, world)
        val arcValue = (ev.valueOf(el.ref) as? EllipticArcValue)?.arc
        if (arcValue != null) {
            if (!Conics.contains(arcValue, t)) {
                note = "That point is not on ${nameOf(el)}'s sweep — click on the arc itself"
                return null
            }
            val sweep = abs(Conics.sweep(arcValue))
            val from = abs(atan2(sin(t - arcValue.startT), cos(t - arcValue.startT)))
            val to = abs(atan2(sin(t - arcValue.endT), cos(t - arcValue.endT)))
            if (sweep < Vec2.EPS || from / sweep <= breakEndSlack || to / sweep <= breakEndSlack) {
                note = "Click away from ${nameOf(el)}'s ends — a break there would leave a zero-length piece"
                return null
            }
        }
        val consumers = consumersOf(el)
        val made =
            breakEllipse(el, Quantity.rad(t), arcValue?.ccw ?: true) ?: run {
                note = "${nameOf(el)} could not be split there"
                return null
            }
        return settle(el, made[0], made.drop(1), consumers, detached = false, why = "the two pieces share it as their carrier")
    }

    /**
     * The recorded half of a conic break — one step, because everything it makes hangs off the conic it
     * names and its own [t] (the rider's parametric angle: **state**, hence restated on save, OP-18).
     * [ccw] is the sweep direction, stored verbatim (OP-1).
     */
    fun breakEllipse(
        el: Element,
        t: Quantity,
        ccw: Boolean,
    ): List<Element>? =
        recording("breakellipse", Arg.El(el), Arg.Num(t), Arg.Text(if (ccw) "ccw" else "cw")) {
            breakEllipseNow(el, t, ccw)
        }

    private fun breakEllipseNow(
        el: Element,
        t: Quantity,
        ccw: Boolean,
    ): List<Element>? {
        if (!el.isElliptic) return null
        val carrier = carrierEllipse(el)
        val ev = Evaluator()
        val ellipse = (ev.valueOf(carrier) as? EllipseValue)?.ellipse ?: return null
        val a = t.requireDim(Dimension.ANGLE, "split parameter").base
        val at = Conics.pointAt(ellipse, a)
        // a rider on the carrier ellipse: the click is *where*, the parameter is *what it holds* — the same
        // split of choice and state every other rider's step makes (OP-18)
        val rider = pointOnEllipse(el, at, t) ?: return null
        val split = elementFor(rider) ?: return null
        val param = riderParam(split) ?: return null
        return if (el.kind == ElementKind.ELLIPSE) {
            // the antipode is the *same* freedom, half a turn on — see this function's own note
            val other = cx.pointOnEllipse(carrier, cx.add(Ref<ScalarValue>(param), cx.const(Quantity.rad(kotlin.math.PI))))
            val h1 = add(cx.ellipticArcBetween(carrier, rider, other, ccw), ElementKind.ELLIPTIC_ARC, Styles.CURVE)
            val h2 = add(cx.ellipticArcBetween(carrier, other, rider, ccw), ElementKind.ELLIPTIC_ARC, Styles.CURVE)
            listOf(split, h1, h2)
        } else {
            @Suppress("UNCHECKED_CAST")
            val ref = el.ref as EllipticArcRef
            val h1 = add(cx.ellipticArcBetween(carrier, cx.ellipticArcStart(ref), rider, ccw), ElementKind.ELLIPTIC_ARC, Styles.CURVE)
            val h2 = add(cx.ellipticArcBetween(carrier, rider, cx.ellipticArcEnd(ref), ccw), ElementKind.ELLIPTIC_ARC, Styles.CURVE)
            listOf(split, h1, h2)
        }
    }

    /**
     * Split a cubic Bézier at the click's nearest parameter — **de Casteljau as a construction** (OP-15).
     *
     * The five intermediate points and the split point are `pointAtRatio` nodes over the four controls, all
     * sharing **one** `t` parameter, and the two halves are ordinary cubics over them. Three things follow
     * that no approximation would give: the halves are exact (they *are* the subdivision formula), they stay
     * exact under any drag of the controls (the formula is re-evaluated, not re-fitted), and `t` is live — so
     * typing it or dragging any of the ratio points slides the split along the curve and re-splits it.
     */
    private fun breakBezierAt(
        el: Element,
        world: Vec2,
    ): BreakResult? {
        val bez =
            ((Evaluator().eval(el.ref.node) as? EvalResult.Ok)?.value as? BezierValue)?.bezier ?: run {
                note = "${nameOf(el)} has no position to split (its construction is invalid)"
                return null
            }
        val t0 = GeomMath.bezierNearestParam(bez, world)
        if (t0 <= breakEndSlack || t0 >= 1.0 - breakEndSlack) {
            note = "Click away from ${nameOf(el)}'s ends — a break there would leave a zero-length piece"
            return null
        }
        val own = drawnFromPoints(el, Tools.BEZIER, 4)
        val consumers = consumersOf(el)
        val c =
            own ?: keyPointsOf(el, 4) ?: run {
                note = "${nameOf(el)} has no control points to hang the halves on"
                return null
            }
        val t = newParameter("t", Quantity.number(t0))

        fun at(
            a: Element,
            b: Element,
        ): Element? = runAsTool(Tools.MIDPOINT, listOf(a.ref as PointRef, b.ref as PointRef), scalars = listOf(t))?.single()
        val l1 = at(c[0], c[1]) ?: return null
        val m = at(c[1], c[2]) ?: return null
        val r1 = at(c[2], c[3]) ?: return null
        val l2 = at(l1, m) ?: return null
        val r2 = at(m, r1) ?: return null
        val s = at(l2, r2) ?: return null
        val refs = { xs: List<Element> -> xs.map { it.ref as PointRef } }
        val h1 = runAsTool(Tools.BEZIER, refs(listOf(c[0], l1, l2, s)))?.single() ?: return null
        val h2 = runAsTool(Tools.BEZIER, refs(listOf(s, r2, r1, c[3])))?.single() ?: return null
        return settle(el, s, listOf(h1, h2), consumers, detached = own != null, why = "the halves are built on it")
    }

    /**
     * Apply the consumer rule to a finished break: drop the original (the caller's journal rewrite) when
     * nothing read it and the halves stand on their own, otherwise **hide** it and say what kept it.
     */
    private fun settle(
        el: Element,
        split: Element,
        halves: List<Element>,
        consumers: List<String>,
        detached: Boolean,
        why: String,
    ): BreakResult {
        val replaces = detached && consumers.isEmpty()
        if (!replaces) setElementsVisible(listOf(el), false)
        // set last: every recording above clears the note, since a note belongs to the operation being run
        note =
            if (replaces) {
                "${nameOf(el)} split into ${nameOf(halves[0])} and ${nameOf(halves[1])} — drag ${nameOf(split)} to bend the joint"
            } else {
                val by = consumers.firstOrNull()?.let { "$it is built on it" } ?: why
                "${nameOf(el)} stays (hidden): $by — ${nameOf(halves[0])} and ${nameOf(halves[1])} are new, " +
                    "so nothing it feeds changes meaning; drag ${nameOf(split)} to bend the joint"
            }
        return BreakResult(el, split, halves, replaces)
    }

    /** Where the next leg of [path] would land (rubber-band preview), from whichever end is growing. */
    fun orthoLegPreview(
        path: OrthoPath,
        to: Vec2,
        atEnd: Boolean = true,
    ): Pair<Vec2, Vec2>? = orthoLegPreview(if (atEnd) path.vertices.last().ref else path.vertices.first().ref, to)

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
     * What closing [path] will actually look like: the leg into its last vertex once that vertex snaps
     * into line with the start, plus the closing leg itself.
     *
     * Closing *moves* a vertex — binding its own coordinate to the start's is what makes the closing leg
     * axis-aligned — so a rubber band merely reaching for the start would promise a shape the click does
     * not produce, and the drawing appeared to jump on close.
     */
    fun orthoClosePreview(path: OrthoPath): List<Pair<Vec2, Vec2>> {
        if (path.closed || path.vertices.size < 3) return emptyList()
        val ev = Evaluator()

        fun pos(v: OrthoVertex): Vec2? = ((ev.eval(v.ref.node) as? EvalResult.Ok)?.value as? PointValue)?.p

        val last = path.vertices.last()
        val axis = last.ownAxis
        if (axis != 0 && axis != 1) return emptyList()
        val start = pos(path.vertices.first()) ?: return emptyList()
        val here = pos(last) ?: return emptyList()
        val prev = pos(path.vertices[path.vertices.size - 2]) ?: return emptyList()
        val moved = if (axis == 0) Vec2(start.x, here.y) else Vec2(here.x, start.y)
        return listOf(prev to moved, moved to start)
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
        refreshOrthoLoop(path)
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

    /** The retained thick paths (OP-21) — a *wall* is one use of the concept, not the concept. */
    val thickNetworks = ArrayList<ThickNetwork>()

    // ---- the result layer (OP-14) ----

    /**
     * A cubic Bézier through four control points (OP-15). The control points are ordinary points, so
     * they may be free *or* constructed — which is what lets technical geometry drive a smooth curve.
     */
    fun bezierCurve(
        p0: PointRef,
        p1: PointRef,
        p2: PointRef,
        p3: PointRef,
    ): BezierRef = cx.bezier(p0, p1, p2, p3).also { add(it, ElementKind.BEZIER, Styles.CURVE) }

    /**
     * Build a closed **outline** by walking the picked curves in order (OP-14).
     *
     * This is what separates the drawing from the construction that produced it. Each consecutive
     * pair of picks is intersected — with the branch chosen from where the user clicked and then
     * *stored*, never re-derived (OP-1) — and each pick is trimmed between the two joints that fall on
     * it. The loop records **which curves in which order**, a stable identity (OP-8), so later
     * parameter edits move the cut points without ever re-deciding what the boundary is.
     *
     * A Bézier cannot be trimmed by intersection, so it contributes its **own endpoint** as the joint
     * — the constructive way round: build the spline onto the points where it should meet its
     * neighbours (drag-to-attach or a shared derived point) instead of trimming it afterwards. If it
     * does not actually reach them, the loop reports the gap and stays invalid (OP-3), which is the
     * useful answer rather than a silently mended boundary.
     */
    fun buildOutline(
        picks: List<Element>,
        clicks: List<Vec2>,
    ): Element? {
        val n = picks.size
        if (n < 2 || clicks.size < n) return null
        if (picks.any { !it.isCurve }) return null
        val ev = Evaluator()

        // joint[i] = where picks[i] hands over to picks[i+1]
        val joints =
            if (n == 2) {
                // Two picks are adjacent on *both* sides, so they must hand over at two *different*
                // places — taking the nearest meeting twice would collapse both pieces to a point.
                bothJointsBetween(picks[0], picks[1], ev) ?: return null
            } else {
                val js = ArrayList<PointRef>(n)
                for (i in 0 until n) {
                    js.add(jointBetween(picks[i], picks[(i + 1) % n], clicks[i], clicks[(i + 1) % n], ev) ?: return null)
                }
                js
            }

        // the element layer follows the node layer: where the handover is a spot a point element already
        // marks, this boundary publishes no second marker over it (OP-14 — see [quietDuplicateMarker])
        joints.forEach { quietDuplicateMarker(it, ev) }

        val pieces = ArrayList<Ref<*>>(n)
        for (i in 0 until n) {
            val from = joints[(i - 1 + n) % n]
            val to = joints[i]
            pieces.add(trimPiece(picks[i], from, to, clicks[i], ev) ?: return null)
        }
        return add(cx.loop(*pieces.toTypedArray()), ElementKind.OUTLINE, Styles.RESULT)
    }

    /**
     * Point markers a boundary's handover published **where a point element already stood** — hidden by
     * construction, and this is the set that says so (OP-14).
     *
     * Synthetic, like the joint registry and the handles beside it (OP-18): the step that traced the
     * boundary re-runs on replay and hides them again, so nothing about this is in the file — and, crucially,
     * *nothing about the file changes*. See [quietDuplicateMarker] for why that is the mechanism.
     */
    private val duplicateJointMarkers = HashSet<String>()

    /**
     * Whether [el] is hidden **by construction** and must therefore never be shown: a welded alias
     * ([isWelded]), or a duplicate joint marker ([quietDuplicateMarker]).
     *
     * One predicate for one rule, because the two cases are the same rule: showing either would draw a
     * second point on top of a point that is already there.
     */
    fun hiddenByConstruction(el: Element): Boolean = isWelded(el) || el.id in duplicateJointMarkers

    /**
     * Hide the marker [ref] just published, if a point element **already marks that spot** (OP-14).
     *
     * A boundary hands over at the *existing* shared point wherever it can ([sharedEndBetween]) — that is
     * the construction being honest about where two pieces meet. What the element layer did with it was not:
     * it published a fresh green `DERIVED_POINT` at every joint, so tracing a rectangle laid four green
     * markers exactly over its four blue corners. The free points were still there and still draggable, but
     * a user could no longer *see* which points carry the drawing's degrees of freedom — reported as
     * *"free points are rendered blue … if you e.g. have a polygon extruded, then all points on the corners
     * are rendered in green making the free one undistinguishable"*.
     *
     * **The marker is hidden rather than never created**, and that is a format decision, not a shortcut. The
     * file names elements by the order the journal's steps create them (`-> e9,e10,e11,e12,e13`), and a load
     * refuses outright when a step creates a different number of elements than the script declares — *"the
     * file was written by a different version"*. Publishing one element fewer per joint would therefore make
     * every stored drawing containing an outline fail to load, and would renumber everything after it. So the
     * node graph, the element list and the file all stay exactly as they were; what changes is one boolean,
     * the same one a weld sets on the alias it hides ([weldNow]) — and for the same reason.
     *
     * The rule is about *any* joint that lands on an existing point, not about corners: a fillet's tangency
     * or a genuine re-intersection marks a place nothing marked before, so it keeps its visible point.
     */
    private fun quietDuplicateMarker(
        ref: PointRef,
        ev: Evaluator,
    ) {
        val el = elementFor(ref) ?: return
        if (!el.isPoint || !el.visible) return
        val at = (ev.valueOf(ref) as? PointValue)?.p ?: return
        val already =
            elements.any { other ->
                other !== el && other.isPoint && other.space == el.space &&
                    ((ev.valueOf(other.ref) as? PointValue)?.p?.let { (it - at).length() <= GeomMath.JOIN_TOL } == true)
            }
        if (!already) return
        el.visible = false
        duplicateJointMarkers.add(el.id)
    }

    /** Where two picks hand over, chosen as the meeting nearest to where *both* were clicked. */
    private fun jointBetween(
        a: Element,
        b: Element,
        nearA: Vec2,
        nearB: Vec2,
        ev: Evaluator,
    ): PointRef? {
        // a joint the *construction* stated (a fillet's tangency, a chamfer's bevel end) is the handover,
        // before anything is re-derived: it is exact where an intersection does not even exist — which a
        // tangency does not — see [registerJoint]
        registeredJoint(a, b)?.let { return addDerived(it) }
        // then pieces that *already* meet: they hand over there instead of being re-intersected
        sharedEndBetween(a, b, ev)?.let { return it }
        if (a.kind == ElementKind.BEZIER) return bezierEndNear(a, nearB, ev)
        if (b.kind == ElementKind.BEZIER) return bezierEndNear(b, nearA, ev)
        // a function curve is joined exactly as a spline is: it is *built onto* its neighbours rather than
        // trimmed to them, so the handover is whichever of its own ends is nearer the other pick (OP-15)
        if (a.kind == ElementKind.FUNC_CURVE) return funcEndNear(a, nearB, ev)
        if (b.kind == ElementKind.FUNC_CURVE) return funcEndNear(b, nearA, ev)
        return intersectNearNow(a, b, (nearA + nearB) * 0.5)
    }

    // ---- the joint registry: where the construction says two pieces hand over (OP-14) ----

    /**
     * Every joint the constructions in this document **stated**: a fillet's two tangencies, a chamfer's two
     * bevel ends — see [registerJoint].
     *
     * Synthetic like handles and styles (OP-18): the step that built the fillet re-runs on replay and
     * registers the joint again, so nothing about this is in the file.
     */
    private val jointRegistry = ArrayList<Joint>()

    /**
     * Corners a fillet or chamfer has **replaced**: those two legs no longer hand over to each other, even
     * though their endpoints may still coincide there.
     *
     * This is what makes a bevelled corner unambiguous for boundary-follow: at a chamfered triangle vertex
     * the two legs still meet, but the boundary does not go that way any more — it goes round the bevel. The
     * fact belongs to the construction that cut the corner off, so it is recorded there and not re-derived
     * from the picture (OP-14's rule against discovering topology).
     */
    private val supersededCorners = ArrayList<Supersession>()

    /**
     * Record that [a] and [b] hand over at [at] — the generalization of "these two happen to share an
     * endpoint" to "**this construction says they meet here**".
     *
     * [at] is a node, never a coordinate: a fillet's tangency is `radialPoint`/`projectToLine` over the
     * fillet's own centre, so the joint follows every later edit of radius or legs. It is what
     * [jointBetween] hands the trim ops, and what [continuationsFrom] reads to walk a boundary — one fact,
     * two readers, which is why it is registered rather than re-derived on each side.
     */
    private fun registerJoint(
        a: Element,
        b: Element,
        at: PointRef,
        tangent: Boolean = false,
    ) {
        jointRegistry.add(Joint(a, b, at, tangent))
    }

    /** Every joint this document's constructions stated — read by the blend's tangent run (GitHub #29). */
    val joints: List<Joint> get() = jointRegistry

    private fun supersedeCorner(
        a: Element,
        b: Element,
        by: Element,
    ) {
        supersededCorners.add(Supersession(a, b, by))
    }

    /** The registered joint between [a] and [b], or null. */
    fun registeredJoint(
        a: Element,
        b: Element,
    ): PointRef? = jointRegistry.firstOrNull { (it.a === a && it.b === b) || (it.a === b && it.b === a) }?.at

    // ---- the incidence registry: which points the construction put on a circle (OP-14, GitHub #19) ----

    /**
     * Every point this document's constructions **stated** to be on a circle — see [OnCircle].
     *
     * The joint registry's twin, and for the same reason: whether a point lies on a circle is a fact the
     * construction knows and the picture cannot be asked. Measuring `|p − c| = r` would accept a point that
     * merely happens to sit there today and would drop it the moment a parameter moved, which is the very
     * thing OP-5 says a drawing must not do. So the fact is recorded where it is made, by the routes that
     * make it, and read by *Tangent at point*.
     *
     * Synthetic like the joints (OP-18): every one of those routes is a step, so a replay states them again
     * and nothing about this is in the file.
     */
    private val onCircleRegistry = ArrayList<OnCircle>()

    /**
     * Record that [point] lies on [host]'s carrier circle [carrier] — because the construction that just ran
     * put it there.
     *
     * Keyed on the **node** rather than on an element, so it is recorded at the construction (where the
     * carrier is in hand) whether or not the point has become an element yet, and so a second element over
     * the same node reads the same fact.
     */
    private fun stateOnCircle(
        point: PointRef,
        carrier: CircleRef,
        host: Element?,
    ) {
        onCircleRegistry.add(OnCircle(point.node, carrier, host))
    }

    /** [refs] all lie on [host]'s carrier — the plural of [stateOnCircle], for the fits and the crossings. */
    private fun stateAllOnCircle(
        refs: List<PointRef>,
        carrier: CircleRef,
        host: Element?,
    ) {
        refs.forEach { stateOnCircle(it, carrier, host) }
    }

    /**
     * The circles [el] lies on **by construction** — empty for a point that merely looks as if it does.
     *
     * Two sources, one answer. The registry above holds what a construction stated; a **rider** carries its
     * circle in its own handle instead ([OnCircleHandle]), because attaching a point to a curve by dragging
     * it is a gesture rather than a construction and there is no build to record anything. Deduplicated by
     * the carrier node, since sharing a node *is* equality here (OP-5) — the same circle reached twice is one
     * candidate, and only genuinely different circles make the tangent ambiguous.
     */
    fun circlesThrough(el: Element): List<OnCircle> {
        if (!el.isPoint) return emptyList()
        val out = ArrayList<OnCircle>()
        for (on in onCircleRegistry) {
            if (on.point === el.ref.node && out.none { it.carrier.node === on.carrier.node }) out.add(on)
        }
        (el.handle as? OnCircleHandle)?.let { h ->
            if (out.none { it.carrier.node === h.circle.node }) out.add(OnCircle(el.ref.node, h.circle, elementFor(h.circle)))
        }
        return out
    }

    /**
     * Where [a] and [b] still meet end to end, **the corners a fillet or chamfer took excluded**.
     *
     * Position by position rather than pair by pair, because two curves can meet *twice* — a chord and its
     * arc do — and a rounding replaces only the corner it sits in. Which one that is needs no tolerance and
     * no guess: it is the meeting nearest the rounding, so each superseding piece removes exactly one.
     */
    private fun sharedMeetings(
        a: Element,
        b: Element,
        ev: Evaluator,
    ): List<Vec2> {
        val endsB = endpointPositions(b, ev)
        if (endsB.isEmpty()) return emptyList()
        val meetings = endpointPositions(a, ev).filter { p -> endsB.any { (it - p).length() <= GeomMath.JOIN_TOL } }
        if (meetings.isEmpty()) return meetings
        val left = meetings.toMutableList()
        for (s in supersededCorners) {
            if (!((s.a === a && s.b === b) || (s.a === b && s.b === a))) continue
            if (elements.none { it === s.by }) continue
            val corner = supersededCorner(s, ev) ?: continue
            // **the corner it replaced, and only that one.** Position by position, because two curves can
            // meet twice — a chord and its arc — and since a rounding now *trims* its legs (GitHub #25) the
            // pair may still meet at their **other** corner while meeting nowhere near this one. Dropping
            // "the nearest" unconditionally took that surviving corner away and left an outline that could
            // not close.
            left.firstOrNull { (it - corner).length() <= GeomMath.JOIN_TOL }?.let { left.remove(it) }
        }
        return left
    }

    /**
     * The corner a superseding fillet/chamfer **replaced**: the crossing of the two legs' carriers nearest
     * the rounding, which is where they used to hand over.
     *
     * Read off the carriers rather than remembered as a coordinate, for [Joint]'s own reason: the corner
     * moves with the legs, so the fact has to keep following them.
     */
    private fun supersededCorner(
        s: Supersession,
        ev: Evaluator,
    ): Vec2? {
        val t1 = registeredJoint(s.by, s.a)?.let { (ev.valueOf(it) as? PointValue)?.p } ?: return null
        val t2 = registeredJoint(s.by, s.b)?.let { (ev.valueOf(it) as? PointValue)?.p } ?: return null
        return cornerBetween(s.a, s.b, (t1 + t2) * 0.5, ev)
    }

    /**
     * Where [a] and [b] hand over **as a position**, or null when nothing says they do.
     *
     * The follow's question, asked without building anything: only constructed joints and shared endpoints
     * count, never an intersection — an intersection is a place two curves cross, which is not by itself a
     * statement that a boundary turns there.
     */
    fun handoverPosition(
        a: Element,
        b: Element,
        ev: Evaluator,
    ): Vec2? {
        registeredJoint(a, b)?.let { j -> (ev.valueOf(j) as? PointValue)?.let { return it.p } }
        return sharedMeetings(a, b, ev).firstOrNull()
    }

    /**
     * How a boundary can continue from [piece] when it was entered at [enteredAt]: every *other* piece that
     * hands over to it somewhere else, and where.
     *
     * The generalization the Outline tool's follow reads (OP-14): [sharedEndBetween] answers "do these two
     * touch?", this answers "**which pieces continue here?**" — the same two sources of truth (the joint
     * registry, then coincident endpoints), asked over the whole document instead of over one pair.
     */
    fun continuationsFrom(
        piece: Element,
        enteredAt: Vec2,
        ev: Evaluator,
    ): List<Continuation> =
        handoverPlaces(piece, ev)
            .filter { (_, at) -> (at - enteredAt).length() > GeomMath.JOIN_TOL }
            .map { (other, at) -> Continuation(other, at) }

    /** Every (piece, position) [piece] hands over at — the registry's entries, then coincident endpoints. */
    private fun handoverPlaces(
        piece: Element,
        ev: Evaluator,
    ): List<Pair<Element, Vec2>> {
        val out = ArrayList<Pair<Element, Vec2>>()
        for (j in jointRegistry) {
            val other =
                if (j.a === piece) {
                    j.b
                } else if (j.b === piece) {
                    j.a
                } else {
                    continue
                }
            if (elements.none { it === other } || !other.visible) continue
            val at = (ev.valueOf(j.at) as? PointValue)?.p ?: continue
            out.add(other to at)
        }
        if (endpointPositions(piece, ev).isEmpty()) return out
        for (other in elements) {
            if (other === piece || !other.isCurve || !other.visible) continue
            // a registered joint is the construction's own statement about this pair, so it wins outright
            if (out.any { it.first === other }) continue
            for (meet in sharedMeetings(piece, other, ev)) out.add(other to meet)
        }
        return out
    }

    /**
     * The end of [piece] farthest from [from] — the far side of a followed piece when its second joint is
     * not known yet (the follow stopped there), so its recorded click still lands on the right part of it.
     */
    fun farEndOf(
        piece: Element,
        from: Vec2,
        ev: Evaluator,
    ): Vec2? = endpointPositions(piece, ev).maxByOrNull { (it - from).length() }

    /** Where a bounded piece ends, as positions — empty for a line, a ray or a whole circle. */
    private fun endpointPositions(
        el: Element,
        ev: Evaluator,
    ): List<Vec2> =
        when (val v = ev.valueOf(el.ref)) {
            is SegmentValue -> listOf(v.seg.a, v.seg.b)
            is ArcValue -> listOf(GeomMath.arcStart(v.arc), GeomMath.arcEnd(v.arc))
            is BezierValue -> listOf(v.bezier.p0, v.bezier.p3)
            is EllipticArcValue -> listOf(Conics.start(v.arc), Conics.end(v.arc))
            is FuncCurveValue -> listOfNotNull(FuncCurves.start(v.curve), FuncCurves.end(v.curve))
            else -> emptyList()
        }

    /**
     * A point **on** [piece] between the two joints [from] and [to] — the click a followed pick records.
     *
     * A followed piece needs a click position for the same two reasons a clicked one does: an arc's branch
     * (which way round) is read off it, and the step restates it so replay makes the same choice (OP-18). So
     * the follow has to name a point the user *would* have clicked, and the honest one is between the two
     * joints: for a straight piece their midpoint, for an arc the mid-angle of whichever way round stays
     * within the arc.
     *
     * Null for a whole circle — there both ways round are inside the piece, so which arc is meant is
     * genuinely a choice, and the follow must stop and let the user make it rather than guess (OP-1).
     */
    fun pointBetweenOn(
        piece: Element,
        from: Vec2,
        to: Vec2,
        ev: Evaluator,
    ): Vec2? {
        if (piece.isLinear) return (from + to) * 0.5
        if (piece.kind == ElementKind.BEZIER) {
            val b = (ev.valueOf(piece.ref) as? BezierValue)?.bezier ?: return null
            return (b.p0 + b.p1 * 3.0 + b.p2 * 3.0 + b.p3) * 0.125
        }
        // a function curve is built onto its own two ends, so the point "between" them is its own
        // mid-parameter — the same answer a Bézier gives, in the curve's own parametrization
        (ev.valueOf(piece.ref) as? FuncCurveValue)?.curve?.let { c ->
            return FuncCurves.pointAt(c, FuncCurves.paramOfFraction(c, 0.5))
        }
        (ev.valueOf(piece.ref) as? EllipticArcValue)?.arc?.let { ea ->
            // the same rule in the conic's own parameter: the mid-parameter of whichever way round stays
            // inside the arc's sweep (OP-24)
            val e = ea.ellipse
            val t0 = Conics.paramOf(e, from)
            val t1 = Conics.paramOf(e, to)
            val ccwMid = t0 + norm2pi(t1 - t0) * 0.5
            val cwMid = t0 - norm2pi(t0 - t1) * 0.5
            return when {
                Conics.contains(ea, ccwMid) -> Conics.pointAt(e, ccwMid)
                Conics.contains(ea, cwMid) -> Conics.pointAt(e, cwMid)
                else -> null
            }
        }
        val arc = (ev.valueOf(piece.ref) as? ArcValue)?.arc ?: return null
        val a0 = (from - arc.center).angle()
        val a1 = (to - arc.center).angle()
        val ccwMid = a0 + norm2pi(a1 - a0) * 0.5
        val cwMid = a0 - norm2pi(a0 - a1) * 0.5
        return when {
            GeomMath.arcContains(arc, ccwMid) -> GeomMath.arcPointAt(arc, ccwMid)
            GeomMath.arcContains(arc, cwMid) -> GeomMath.arcPointAt(arc, cwMid)
            else -> null
        }
    }

    /**
     * The endpoint two bounded pieces already share, as an accessor node on one of them — or null when
     * they do not touch end to end.
     *
     * **This is what makes a rounded shape traceable at all.** A rounded rectangle's side meets its corner
     * arc *tangentially*, and a tangent line and circle have no intersection to find (in floating point,
     * usually none at all), so deriving the joint by intersection refused to trace the commonest outline in
     * mechanical CAD — and refused silently, since a loop that cannot be built simply is not built. The
     * same holds for a fillet, a chamfer's bevel and any two pieces built onto a shared point.
     *
     * Recognition is by **position** (within [Geom.JOIN_TOL], the tolerance a loop chains with), because a
     * shared *node* is not available in general: the rounded rectangle's arcs are built from a centre and
     * two angles, so they own no endpoint node to compare. What is constructed from it is an accessor
     * ([Construction.arcStart] and friends), so the joint stays a pure function of the parameters and moves
     * with them — nothing is frozen into a literal.
     */
    private fun sharedEndBetween(
        a: Element,
        b: Element,
        ev: Evaluator,
    ): PointRef? {
        val endsA = endpointAccessors(a, ev)
        val endsB = endpointAccessors(b, ev)
        if (endsA.isEmpty() || endsB.isEmpty()) return null
        val best =
            endsA
                .flatMap { pa -> endsB.map { pb -> Triple(pa, pb, (pa.first - pb.first).length()) } }
                .minByOrNull { it.third } ?: return null
        if (best.third > GeomMath.JOIN_TOL) return null
        return addDerived(best.first.second())
    }

    /** A bounded curve's endpoints: where each is now, and how to construct it as a node. */
    private fun endpointAccessors(
        el: Element,
        ev: Evaluator,
    ): List<Pair<Vec2, () -> PointRef>> =
        when (val v = ev.valueOf(el.ref)) {
            is SegmentValue -> {
                @Suppress("UNCHECKED_CAST")
                val ref = el.ref as SegmentRef
                listOf(v.seg.a to { cx.segmentStart(ref) }, v.seg.b to { cx.segmentEnd(ref) })
            }
            is ArcValue -> {
                @Suppress("UNCHECKED_CAST")
                val ref = el.ref as ArcRef
                listOf(GeomMath.arcStart(v.arc) to { cx.arcStart(ref) }, GeomMath.arcEnd(v.arc) to { cx.arcEnd(ref) })
            }
            // a spline built onto its neighbours' points shares an endpoint like anything else, and saying
            // so here is better than choosing one of its ends from a click ([bezierEndNear]): the accessor
            // is exact and needs no click at all
            is BezierValue -> {
                @Suppress("UNCHECKED_CAST")
                val ref = el.ref as BezierRef
                listOf(v.bezier.p0 to { cx.bezierStart(ref) }, v.bezier.p3 to { cx.bezierEnd(ref) })
            }
            // an elliptic arc is trimmed to two cut points, so it publishes them as accessors exactly as
            // a circular arc does (OP-24)
            is EllipticArcValue -> {
                @Suppress("UNCHECKED_CAST")
                val ref = el.ref as EllipticArcRef
                listOf(Conics.start(v.arc) to { cx.ellipticArcStart(ref) }, Conics.end(v.arc) to { cx.ellipticArcEnd(ref) })
            }
            else -> emptyList()
        }

    /**
     * Both places two picks meet, for the two-piece boundary where each is the other's neighbour on
     * both sides — a chord and its arc, or a chord and a spline arching over it.
     *
     * For two curves that is the pair of intersection branches, taken in the canonical order (OP-1) so
     * the choice is deterministic rather than click-dependent; for a Bézier it is simply its own two
     * endpoints, since a spline is built onto its neighbours instead of trimmed to them.
     */
    private fun bothJointsBetween(
        a: Element,
        b: Element,
        ev: Evaluator,
    ): List<PointRef>? {
        val spline =
            if (a.kind == ElementKind.BEZIER) {
                a
            } else if (b.kind == ElementKind.BEZIER) {
                b
            } else {
                null
            }
        if (spline != null) {
            @Suppress("UNCHECKED_CAST")
            val ref = spline.ref as BezierRef
            if (ev.valueOf(ref) !is BezierValue) return null
            return listOf(addDerived(cx.bezierStart(ref)), addDerived(cx.bezierEnd(ref)))
        }
        // an **elliptic arc** is the same case: it is trimmed to two cut points of its own, so the two
        // meetings are its own ends rather than an intersection to be found (OP-24)
        val func =
            if (a.kind == ElementKind.FUNC_CURVE) {
                a
            } else if (b.kind == ElementKind.FUNC_CURVE) {
                b
            } else {
                null
            }
        if (func != null) {
            @Suppress("UNCHECKED_CAST")
            val ref = func.ref as FuncCurveRef
            if (ev.valueOf(ref) !is FuncCurveValue) return null
            return listOf(addDerived(cx.funcCurveStart(ref)), addDerived(cx.funcCurveEnd(ref)))
        }
        val conic =
            if (a.kind == ElementKind.ELLIPTIC_ARC) {
                a
            } else if (b.kind == ElementKind.ELLIPTIC_ARC) {
                b
            } else {
                null
            }
        if (conic != null) {
            @Suppress("UNCHECKED_CAST")
            val ref = conic.ref as EllipticArcRef
            if (ev.valueOf(ref) !is EllipticArcValue) return null
            return listOf(addDerived(cx.ellipticArcStart(ref)), addDerived(cx.ellipticArcEnd(ref)))
        }
        val crossing = intersectionSet(a, b) ?: return null
        if (crossing.branches < 2) return null // two lines meet once: they cannot bound an area on their own
        val set = crossing.set
        // the boundary tracer names *two* meetings of a pair, which is all a handover needs — the first and
        // the last of the ordered set, whichever discipline addresses its branches
        val first = if (crossing.byIndex) cx.selectAt(set, 0) else cx.select(set, +1)
        val second = if (crossing.byIndex) cx.selectAt(set, cx.solutionCount(set, ev) - 1) else cx.select(set, -1)
        val p1 = (ev.valueOf(first) as? PointValue)?.p ?: return null
        val p2 = (ev.valueOf(second) as? PointValue)?.p ?: return null
        if ((p1 - p2).length() < GeomMath.JOIN_TOL) return null // tangent: one meeting only
        return listOf(addDerived(first), addDerived(second))
    }

    /** Whichever end of a function curve is nearer [near] — the joint it offers a neighbouring piece. */
    private fun funcEndNear(
        el: Element,
        near: Vec2,
        ev: Evaluator,
    ): PointRef? {
        @Suppress("UNCHECKED_CAST")
        val ref = el.ref as FuncCurveRef
        val c = (ev.valueOf(ref) as? FuncCurveValue)?.curve ?: return null
        val s = FuncCurves.start(c) ?: return null
        val e = FuncCurves.end(c) ?: return null
        return addDerived(if ((s - near).length() <= (e - near).length()) cx.funcCurveStart(ref) else cx.funcCurveEnd(ref))
    }

    /** Whichever end of a Bézier is nearer [near] — the joint it offers a neighbouring piece. */
    private fun bezierEndNear(
        el: Element,
        near: Vec2,
        ev: Evaluator,
    ): PointRef? {
        @Suppress("UNCHECKED_CAST")
        val ref = el.ref as BezierRef
        val b = (ev.valueOf(ref) as? BezierValue)?.bezier ?: return null
        val start = (b.p0 - near).length() <= (b.p3 - near).length()
        return addDerived(if (start) cx.bezierStart(ref) else cx.bezierEnd(ref))
    }

    /**
     * The piece of [el] between the two joints. For a circle or arc the *branch* — which of the two
     * arcs between the joints is meant — is decided here from where the user clicked and then stored
     * on the node, so it is a persisted discrete choice and not continuity tracking (OP-1).
     */
    private fun trimPiece(
        el: Element,
        from: PointRef,
        to: PointRef,
        near: Vec2,
        ev: Evaluator,
    ): Ref<*>? {
        if (el.isLinear) return cx.segmentBetween(el.ref, from, to)
        if (el.kind == ElementKind.BEZIER) return el.ref
        // built onto its ends, never trimmed to them — the spline's bargain (OP-15), and the reason its
        // *domain* is the trim: change `from`/`to` and the piece moves with them
        if (el.kind == ElementKind.FUNC_CURVE) return el.ref
        if (el.isElliptic) {
            // the conic twin of the arc trim below: which way round is decided by where the user clicked,
            // in the ellipse's **own parameter**, and then stored (OP-1)
            val e =
                when (val v = ev.valueOf(el.ref)) {
                    is EllipseValue -> v.ellipse
                    is EllipticArcValue -> v.arc.ellipse
                    else -> return null
                }
            val t0 = Conics.paramOf(e, (ev.valueOf(from) as? PointValue)?.p ?: return null)
            val t1 = Conics.paramOf(e, (ev.valueOf(to) as? PointValue)?.p ?: return null)
            val ccwSweep = norm2pi(t1 - t0)
            val toClick = norm2pi(Conics.paramOf(e, near) - t0)
            return cx.ellipticArcBetween(el.ref, from, to, ccw = toClick <= ccwSweep)
        }
        if (el.kind != ElementKind.CIRCLE && el.kind != ElementKind.ARC) return null
        val centre =
            when (val v = ev.valueOf(el.ref)) {
                is CircleValue -> v.circle.center
                is ArcValue -> v.arc.center
                else -> return null
            }
        val a0 = ((ev.valueOf(from) as? PointValue)?.p ?: return null) - centre
        val a1 = ((ev.valueOf(to) as? PointValue)?.p ?: return null) - centre
        val ccwSweep = norm2pi(a1.angle() - a0.angle())
        val toClick = norm2pi((near - centre).angle() - a0.angle())
        return cx.arcBetween(el.ref, from, to, ccw = toClick <= ccwSweep)
    }

    private fun norm2pi(a: Double): Double {
        val twoPi = 2.0 * kotlin.math.PI
        var r = a % twoPi
        if (r < 0) r += twoPi
        return r
    }

    /**
     * Every element the results are built *from* — the scaffolding (OP-14).
     *
     * Derived, not flagged: it is the ancestor closure of the result elements' nodes. So "this is
     * construction geometry" means exactly "something in the output depends on it", which is a graph
     * fact rather than bookkeeping that could drift out of date.
     */
    fun scaffoldingElements(): List<Element> {
        val results = elements.filter { it.isResult }
        if (results.isEmpty()) return emptyList()
        val seen = HashSet<String>()

        fun walk(node: Node) {
            if (!seen.add(node.id)) return
            node.inputs.forEach { walk(it) }
        }
        results.forEach { walk(it.ref.node) }
        // Annotation is excluded outright (OP-14): a dimension is never scaffolding, whatever the graph
        // says. Its node *can* end up in the closure — wire a parameter to a measured value and the
        // measurement becomes an ancestor of the result — and dimming the dimension then would be
        // exactly backwards: the drawing is what it names.
        return elements.filter { !it.isResult && !it.isAnnotation && it.ref.node.id in seen }
    }

    /**
     * Hide or show [els], **recorded as a journal step** (`hide` / `show`, batched over the whole selection)
     * — returns how many elements actually changed.
     *
     * This reverses a decision OP-18 recorded: *"no handles, styles … all of it is created by the methods
     * that create the geometry, hence recreated by replay"*, and with it the line that hiding is a view
     * state because *"the file is a construction"*. It is — and a construction the user has *arranged* to
     * read a certain way. From the user's chair, reopening a drawing with every hidden helper line back on
     * top of the result is data loss, not purity; and a hide is a decision, exactly like which intersection
     * branch a click meant (OP-1), so it belongs in the file for the same reason. What stays out is
     * visibility that is *not* a decision: a welded alias hides **by construction**, so nothing records it
     * (and [setElementsVisible] refuses to show one — it would draw a second point on its master).
     *
     * One rule for one encoding: **per-element steps everywhere.** A group's toggle records the same step
     * over the group's members rather than a flag on the group, so there is exactly one thing a file can say
     * about visibility, and a member that leaves the group keeps the state the user gave it.
     */
    fun setElementsVisible(
        els: List<Element>,
        visible: Boolean,
    ): Int {
        // what is hidden by construction is never *shown* and never named in a step: a welded alias, and a
        // boundary's duplicate joint marker (OP-14) — showing either draws a second point on the first
        val subject = els.filter { el -> elements.any { it === el } && !(visible && hiddenByConstruction(el)) }
        val changed = subject.count { it.visible != visible }
        if (changed == 0) return 0
        // the step asserts the state of everything the gesture named, not only what moved: replaying it must
        // reach the same configuration whatever the elements' state was when it runs
        recording(if (visible) "show" else "hide", Arg.Keyed("els", Arg.Els(subject))) {
            subject.forEach { it.visible = visible }
        }
        return changed
    }

    /**
     * Build a retained thick path of [thickness] around the carrier [path] (OP-21). One node computes
     * the whole footprint region — offset faces, mitred corners, end caps — so this creates exactly one
     * element and never has to be rebuilt: editing the carrier, the thickness or any interval simply
     * recomputes it. The carrier stays a plain ortho path, draggable and typeable as before.
     */
    fun buildThickPath(
        path: OrthoPath,
        thickness: ScalarRef,
        justification: Justification = Justification.CENTER,
    ): ThickNetwork? =
        recording("wall", Arg.Sc(scalarEntryFor(thickness)), Arg.Text(justification.name.lowercase())) {
            buildThickNetworkNow(path.vertices.map { it.ref }, thickness, justification, path.closed, path)
        }

    private fun buildThickNetworkNow(
        vertices: List<PointRef>,
        thickness: ScalarRef,
        justification: Justification,
        closed: Boolean,
        carrier: OrthoPath?,
    ): ThickNetwork? {
        if (vertices.size < 2) return null
        val ring = closed && vertices.size >= 3
        // the path's own corner numbers, as nodes (GitHub #25): zero today, bound when a fillet rounds one
        val rounds = carrier?.vertices?.map { Ref<ScalarValue>(it.round) } ?: emptyList()
        val bevels = carrier?.vertices?.map { Ref<ScalarValue>(it.bevel) } ?: emptyList()
        val ref = cx.thickFootprint(vertices, thickness, ring, justification, rounds, bevels)
        val el = add(ref, ElementKind.AREA, Styles.FOOTPRINT)
        val tp = ThickNetwork(ThickCarrier.Ortho(vertices.toList(), ring, justification, carrier, rounds, bevels), thickness, el)
        thickNetworks.add(tp)
        return tp
    }

    /**
     * Thicken an **arbitrary connected curve network** into a wall (the OP-21 extension): every curve of
     * [curves] gets the side [sides] gives it, and their shared endpoints are the junctions.
     *
     * The pick is refused **by name** when the curves do not form one connected graph, when one of them is a
     * whole circle (nothing to join at), or when the thickness makes the footprint degenerate — asked here
     * so a bad pick never leaves a node behind, and asked again inside the node so a later edit that pulls
     * the network apart is invalid with the same reason rather than silently wrong.
     *
     * The side of each curve is a **discrete choice scored at creation** and therefore rides the tool step's
     * existing `signs=` (OP-1/OP-18) — no new file argument, because the file already knows how to say this.
     */
    fun buildThickNetwork(
        curves: List<Element>,
        sides: List<Justification>,
        thickness: ScalarRef,
    ): ThickNetwork? {
        if (curves.isEmpty() || curves.any { !it.isCurve }) {
            note = "Thicken: pick curves — segments, arcs or Béziers — that share their endpoints"
            return null
        }
        val ref = cx.thickNetworkFootprint(curves.map { it.ref }, sides, thickness)
        (Evaluator().eval(ref.node) as? EvalResult.Invalid)?.let {
            note = "Thicken: ${it.reason}"
            return null
        }
        val el = add(ref, ElementKind.AREA, Styles.FOOTPRINT)
        val tn = ThickNetwork(ThickCarrier.Network(curves.toList(), sides.toList()), thickness, el)
        thickNetworks.add(tn)
        // the per-curve sides, restated by the step that made them (OP-18) — see [scoredSigns]
        registerSigns(el, sides.map { it.ordinal })
        val body = bodyOf(tn, Evaluator())
        note =
            if (body?.approximated == true) {
                "Wall over ${curves.size} curve${if (curves.size == 1) "" else "s"} — its offsets are " +
                    "approximated (a Bézier's offset is not a Bézier, and an ellipse's is not an ellipse — " +
                    "OP-15), so its area and its solid are too"
            } else {
                "Wall over ${curves.size} curve${if (curves.size == 1) "" else "s"}"
            }
        return tn
    }

    // ---- extending a wall: the same step, re-stamped over more carrier curves (GitHub #7, OP-23) ----

    /**
     * What a wall was built from, as the *Thicken* tool needs it to keep collecting: the carrier [curves] in
     * order, the [clicks] that scored their sides, the [sides] themselves and the wall's own [thickness].
     *
     * Null — with [note] set, by name — when this wall cannot be extended by *Thicken* at all: an **ortho
     * carrier** (one the *Wall* tool drew) has no `tool thicken` step to re-stamp, and its carrier is a
     * polyline of vertices rather than a set of picked curves, so growing it is the *Wall* tool's gesture.
     */
    fun thickNetworkBase(tn: ThickNetwork): ThickExtension? {
        val carrier =
            tn.carrier as? ThickCarrier.Network ?: run {
                note =
                    "Can't extend ${nameOf(tn.footprint)} with Thicken: it is a rectilinear path drawn with the " +
                    "Wall tool — extend that path with the Wall tool (click its open end to resume), or thicken " +
                    "its legs with Thicken to get a wall this can grow"
                return null
            }
        val step = creatingStep(tn.footprint)
        if (step == null || step.kind != "tool" || (step.args.firstOrNull() as? Arg.Text)?.s != Tools.THICKEN) {
            note =
                "Can't extend ${nameOf(tn.footprint)}: no `tool thicken` step declares it, so there is none to " +
                "re-stamp — thicken the new curves as a wall of their own"
            return null
        }
        val ev = Evaluator()
        // one click per curve, which is what the step records. A hand-written script may carry fewer; a
        // curve's own start then stands in for the click, because what a thicken click encodes is the side
        // (and the side is carried separately, in `signs=`).
        val recorded = (step.args.firstOrNull { it is Arg.Keyed && it.key == "clicks" } as? Arg.Keyed)?.let { (it.value as? Arg.Positions)?.ps }.orEmpty()
        val clicks =
            carrier.curves.mapIndexed { i, el ->
                recorded.getOrNull(i) ?: (ev.valueOf(el.ref)?.let { carrierPieceOf(it) }?.let { GeomMath.startOf(it) } ?: Vec2(0.0, 0.0))
            }
        return ThickExtension(
            carrier.curves.toList(),
            clicks,
            carrier.sides.toList(),
            scalarEntryFor(tn.thickness),
        )
    }

    /**
     * The script [tn]'s own `tool thicken` step **re-stamped** over [curves] with [sides] and [clicks] — the
     * whole of what extending a wall is (GitHub #7). Null with [note] set when it is refused.
     *
     * A carrier-set change is an **edit**, not a new feature: the same reasoning OP-23 applied to a pattern's
     * count applies here, and it buys the same three things. The footprint element keeps its identity (its
     * step still declares it, so its script name, its style and everything built on it follow the enlarged
     * wall), the wall's own thickness parameter stays the wall's (the step's `scalar=` is untouched), and the
     * whole thing is one undo step, because the substrate of undo is the saved script (OP-18).
     *
     * **Journal ordering.** A curve may have been drawn *after* the wall, and a step may only name what an
     * earlier step declared — so the re-stamped step moves to just after the last element it now references.
     * It cannot move past anything built *on* the wall, and if a curve was drawn after such a thing the
     * extension is refused by name rather than producing a script that will not load.
     *
     * This document is left exactly as it was: what comes back is text, and the caller decides to adopt it
     * (which is also what makes a failure to re-load harmless).
     */
    fun thickNetworkExtension(
        tn: ThickNetwork,
        curves: List<Element>,
        sides: List<Justification>,
        clicks: List<Vec2>,
    ): String? {
        val base = thickNetworkBase(tn) ?: return null
        if (curves.size <= base.curves.size) {
            note = "Extending ${nameOf(tn.footprint)}: click at least one more curve than it already has"
            return null
        }
        if (curves.any { !it.isCurve }) {
            note = "Extending ${nameOf(tn.footprint)}: a wall's carrier is curves — segments, arcs or Béziers"
            return null
        }
        // a step may only name what an earlier step **declared** (OP-18), so a curve the file does not
        // declare cannot join a carrier set — refused here rather than written as a dangling reference
        curves.firstOrNull { creatingStep(it) == null }?.let {
            note =
                "Can't extend ${nameOf(tn.footprint)} with ${nameOf(it)}: no step declares it, so the file " +
                "would have nothing to call it"
            return null
        }
        val step = creatingStep(tn.footprint) ?: return null
        // A curve **built on this very wall** (through its key points, say) cannot be one of its carriers: the
        // step would have to come both before and after the wall's own. Refused by name, since the drawing
        // makes it look perfectly reachable.
        val downstream = dependentSteps(step) - step
        curves.firstOrNull { el -> downstream.any { s -> s.creates.any { it === el } } }?.let {
            note =
                "Can't extend ${nameOf(tn.footprint)} over ${nameOf(it)}: ${nameOf(it)} is built on that wall, " +
                "so the wall cannot be built on it in turn"
            return null
        }
        val ev = Evaluator()
        // Refused **before** anything is rewritten, and with the reason the footprint node itself would give:
        // the added curve has to be part of the same connected network (a T counts), and the thickness has to
        // still fit the arcs it follows (OP-3's reason, asked up front).
        val pieces =
            curves.mapIndexed { i, el ->
                val piece =
                    ev.valueOf(el.ref)?.let { carrierPieceOf(it) } ?: run {
                        note = "Extending ${nameOf(tn.footprint)}: ${nameOf(el)} has no curve to follow right now"
                        return null
                    }
                CarrierCurve(piece, sides.getOrElse(i) { Justification.CENTER })
            }
        val (body, why) = thickNetwork(pieces, scalarMm(tn.thickness, ev))
        if (body == null) {
            note = "Can't extend ${nameOf(tn.footprint)}: ${why ?: "the wall has no footprint then"}"
            return null
        }
        val at = journal.indexOfFirst { it === step }
        val moveTo = curves.mapNotNull { el -> journal.indexOfFirst { s -> s.creates.any { it === el } }.takeIf { it >= 0 } }.maxOrNull() ?: at
        val grown = Step("tool", step.args.map { rewritten(it, curves, clicks) })
        grown.creates.addAll(step.creates)
        grown.createsScalars.addAll(step.createsScalars)
        val previous = journal.toList()
        val signsBefore = storedSigns(step)
        val was = nameOf(tn.footprint)
        // which curve dragged the step forward, for the note — the one no earlier step declares
        val afterEl = curves.firstOrNull { el -> journal.indexOfFirst { s -> s.creates.any { it === el } } == moveTo }
        journal[at] = grown
        if (moveTo > at) {
            // The wall's step moves **with everything built on it**, in order. It has to move at all because a
            // step may only name what an earlier one declared; it has to take its dependents along because
            // they may only name *it* after it — and nothing outside that set can be waiting on them, since
            // anything that were would itself be one of them. So this is one block move, not a reshuffle.
            val block = (listOf(grown) + (dependentSteps(grown) - grown).sortedBy { s -> journal.indexOfFirst { it === s } })
            journal.removeAll { s -> block.any { it === s } }
            val landing = journal.indexOfFirst { s -> previous.indexOfFirst { it === s } == moveTo } + 1
            journal.addAll(landing, block)
        }
        // the per-curve sides ride the step's `signs=` (OP-18), so the grown list has to be in place for the
        // save exactly as it is for a fresh wall
        registerSigns(tn.footprint, sides.map { it.ordinal })
        nameCache = null // the names are the journal's order, and the order just changed
        try {
            val now = nameOf(tn.footprint)
            val moved =
                if (now == was) {
                    ""
                } else {
                    " — its step moved after ${afterEl?.let { nameOf(it) } ?: "the curves it now names"}, " +
                        "so the wall is $now from here on"
                }
            note =
                "Wall $was: ${base.curves.size} -> ${curves.size} carrier curves$moved" +
                if (body.approximated) " (its offsets are approximated — OP-15)" else ""
            return DocumentFormat.save(this)
        } finally {
            journal.clear()
            journal.addAll(previous)
            registerSigns(tn.footprint, signsBefore)
            nameCache = null
        }
    }

    /** One argument of a `tool thicken` step, with the carrier set and the clicks it names grown. */
    private fun rewritten(
        arg: Arg,
        curves: List<Element>,
        clicks: List<Vec2>,
    ): Arg =
        when {
            arg is Arg.Keyed && arg.key == "els" -> Arg.Keyed("els", Arg.Els(curves))
            arg is Arg.Keyed && arg.key == "clicks" -> Arg.Keyed("clicks", Arg.Positions(clicks))
            else -> arg
        }

    // ---- the 2D->3D seam as tools (OP-17) ----

    /**
     * The region [el] hands to a sketch, or null when it bounds no area at all.
     *
     * An `AREA` element (a thick path's footprint) already *is* a region; an `OUTLINE` is a single loop,
     * so it is wrapped by the ordinary [Construction.region] op — a coercion exactly like the
     * line-carrier one above, and one that creates a node but no element, so the tool step still
     * accounts for precisely one creation. Anything else goes through [boundaryPiecesOf]: **a curve that
     * already bounds an area can be picked where an area is wanted.**
     */
    @Suppress("UNCHECKED_CAST")
    private fun regionOf(el: Element): RegionRef? =
        when (el.kind) {
            ElementKind.AREA -> el.ref as RegionRef
            ElementKind.OUTLINE -> cx.region(el.ref as LoopRef)
            // a closed ortho path publishes **one** loop, through a view, so an extrude made before a corner
            // was rounded reads the rounded loop afterwards (GitHub #25 — see [orthoLoopOf])
            else ->
                (legOf(el)?.first ?: orthoCornerOf(el))?.takeIf { it.closed }?.let { cx.region(orthoLoopOf(it)) }
                    ?: boundaryPiecesOf(el)?.let { pieces -> cx.region(cx.loop(*pieces.map { it.ref }.toTypedArray())) }
        }

    /**
     * The ordered pieces of the closed boundary [el] is part of, or null when it is not part of one.
     *
     * **A curve that already bounds an area needs no boundary tracing.** Two cases, one rule:
     *
     * - a **closed curve** is a boundary by itself (a circle) — and before this, a circle could not become
     *   an area *at all*: the Outline tool needs at least two pieces, so a plain cylindrical hole was
     *   unreachable through the tools;
     * - a **closed chain one step built** (a rounded rectangle, a polygon) is a boundary in
     *   the order that step created it. The order is the *construction's*, not something detected from the
     *   picture: OP-14 rejects seed-point region finding precisely because the loop's identity would be
     *   discovered, and here it is read off the step that built the pieces, so the same step always yields
     *   the same loop. What is checked (below) is only whether that chain currently closes.
     * - a **leg of a closed ortho path** yields that path's legs, in path order. The same rule, reached
     *   through a different record: a path *is* a retained ordered chain and it *knows* it is closed, so the
     *   loop's identity is read off the construction here too — nothing is discovered. It arrived with the
     *   rectangle tool (which now draws a closed path, GitHub issue #4) and generalises: any closed ortho
     *   path can be extruded without first tracing an outline over it.
     *
     * Deliberately **not** extended to "curves that happen to touch": that is region detection, and a
     * drawing where two constructions cross would then acquire areas the user never built.
     */
    fun boundaryPiecesOf(el: Element): List<Element>? {
        // a whole ellipse closes by itself, exactly as a circle does (OP-24)
        if (el.kind == ElementKind.CIRCLE || el.kind == ElementKind.ELLIPSE) return listOf(el)
        if (!el.isCurve) return null
        legOf(el)?.let { (path, _) -> if (path.closed) return roundedPiecesOf(path) }
        // ...and so does a click on the corner piece itself: a rounded corner is part of the boundary, so
        // the arc is as good a place to pick the area by as any leg (GitHub #25)
        orthoCornerOf(el)?.let { path -> if (path.closed) return roundedPiecesOf(path) }
        val step = creatingStep(el) ?: return null
        val pieces = step.creates.filter { c -> c.isCurve && elements.any { it === c } }
        if (pieces.size < 2 || pieces.none { it === el }) return null
        return pieces
    }

    /**
     * Whether [pieces] chain into a closed loop **as they stand** — asked before a pick is accepted, so an
     * area slot never takes geometry the extrude would then quietly refuse.
     *
     * Answered on *values*, with no node built: the filter runs over every candidate element of every
     * click, and a graph that grew a throwaway loop node per candidate would be the wrong kind of cheap.
     */
    fun closesALoop(
        pieces: List<Element>,
        ev: Evaluator,
    ): Boolean {
        val parts = pieces.map { profilePieceOf(ev.valueOf(it.ref) ?: return false) ?: return false }
        return GeomMath.chainLoop(parts).first != null
    }

    private fun profilePieceOf(v: Value): ProfileElement? =
        when (v) {
            is SegmentValue -> ProfileElement.Seg(v.seg)
            is ArcValue -> ProfileElement.ArcE(v.arc)
            is CircleValue -> ProfileElement.CircleE(v.circle)
            is BezierValue -> ProfileElement.BezierE(v.bezier)
            is EllipseValue -> ProfileElement.EllipseE(v.ellipse)
            is EllipticArcValue -> ProfileElement.EllipticArcE(v.arc)
            is FuncCurveValue -> ProfileElement.FuncE(v.curve)
            else -> null
        }

    /**
     * The predicate an `AREA` slot picks with (OP-17): a result-layer area, or a curve that bounds one.
     *
     * Returned as a **closure with a memo**, because the chain test is per *step* and a click asks it of
     * every element in the document — so a rounded rectangle's eight pieces answer it once.
     */
    fun areaPickFilter(ev: Evaluator = Evaluator()): (Element) -> Boolean {
        val closes = HashMap<String, Boolean>()
        return { el ->
            el.isArea ||
                (
                    boundaryPiecesOf(el)?.let { pieces ->
                        closes.getOrPut(pieces.first().id) { closesALoop(pieces, ev) }
                    } ?: false
                )
        }
    }

    /**
     * Extrude the area [el] by [depth] into a solid (OP-17 slice 1).
     *
     * **The sketch plane is the active space's** ([activePlane]) — one rule, and for the plan space it is
     * the world XY plane exactly as before. That is the whole of "sketch on a face" at feature time: the
     * space did the choosing, the tool did not grow an argument, and a 2D drawing still *is* the plan
     * wherever the plan is what is being drawn.
     *
     * The depth stays a **panel parameter**: it is the feature's degree of freedom, and OP-13 is
     * satisfied through the parameter rather than through a 3D drag handle, which there is no picking in
     * this view to grab (see [Viewport3]).
     *
     * **It sweeps the plane's own +normal**, in every space, and that one sentence covers both cases the
     * editor has (GitHub #1, and the session-32 frame rule that made the two agree). A datum plane has no
     * material side, so its normal is all there is — the **sign of its angle** turns it round
     * ([createDatumSpace]). A **face** plane's normal points *out of the material* (you look at the face), so
     * the very same sweep builds a boss outward, which is what an *Extrude* on a face means; *Cut* is the
     * operation that goes inward ([cutOnFace]). The offset this used to need on a face — sketching `depth`
     * behind it and sweeping inward, because the frame pointed into the material — is gone with the flip it
     * was compensating for.
     */
    fun extrudeSolid(
        el: Element,
        depth: ScalarRef,
    ): Element? {
        val region = regionOf(el) ?: return null
        val plane = activeSpace.plane ?: activePlane()
        return add(cx.extrude(cx.sketchOn(plane, region), depth), ElementKind.SOLID, Styles.SOLID)
            .also { madeSolid(it, "${nameOf(el)} extruded ${lengthWord(depth)}") }
    }

    /**
     * Say that [solid] was made, and out of what — **the solid tools' success message** (GitHub #9's
     * silent-success sweep, OP-3's speaking rule read the other way round).
     *
     * A solid is the one result the 2D canvas cannot show: it appears in the 3D viewport, which is a
     * different pane and may not even be open, so a status line that stays empty is the same signal a
     * refusal gives. One helper rather than a sentence per tool, so every one of them says the same shape of
     * thing: what was made, out of what, and where it can be seen.
     */
    private fun madeSolid(
        solid: Element,
        what: String,
    ) {
        // ...and when the result is *invalid* it says so with the node's own reason (OP-3, OP-27): the
        // empty 3D view is exactly what a silent success and a bad input look like alike, and the reason
        // — "tube radius requires L but got 1" — is what connects it to the parameter that was picked. An
        // invalid solid is a state, not a failure: the step is recorded and it heals when the number does.
        val why = (Evaluator().eval(solid.ref.node) as? EvalResult.Invalid)?.reason
        note =
            if (why == null) {
                "${nameOf(solid)} is $what — a solid, shown in the 3D view"
            } else {
                "${nameOf(solid)} is $what, but nothing is drawn for it yet: $why"
            }
    }

    /**
     * Extrude the area [el] by [depth] on the active **face** space's plane and subtract it from [part]
     * (OP-17): the drill, the pocket, the slot — one gesture.
     *
     * **This is the operation that goes inward**, and it is the only one: it sweeps **−normal**, which on a
     * face means into the material (the face's normal points out of it — [createFaceSpace]) and is what makes
     * a drill a drill. Its twin *Extrude* builds the same footprint the other way as a boss ([extrudeSolid]),
     * so the pair covers both intents by naming them rather than by a sign the user cannot see. The backward
     * sweep is [Construction.sketchBehind] — the drawing read on the flipped frame — rather than an offset
     * plane the sweep runs back from, because only that keeps the tool's cap **exactly** on the face; see
     * that function for the near-tangency an offset's rounding produces.
     *
     * Nothing here is new machinery: it is an extrude followed by [combineSolids], which is the click
     * path a user can also take by hand — with *Cut* rather than *Extrude* as the first half, since a
     * manual boss subtracted from the part removes nothing. It exists because the *space* already says which part is being cut,
     * so asking for that pick again is asking the user to repeat what they said by choosing the face. Two
     * solids come out of it — the tool and the cut part — and the tool is the cut's operand, so the 3D view
     * draws only the part (Scene3's material rule) and deleting the part takes its tool with it.
     *
     * [part] is **the part as it stands**, not the space's base: the editor resolves [facePartTip] at click
     * time and the step records it by name, so a second cut chains onto the first instead of forking the
     * model (the sequential-feature rule). Null in the plan space, where there is no face to cut into.
     *
     * **On a datum plane the same −normal sweep** is what "the other way" means there: a datum has no
     * material side, so *Extrude* takes its +normal and *Cut* takes the other one, with the sign of the
     * datum's angle choosing which is which (GitHub #6). The part it subtracts from is the one the datum's
     * hinge belongs to ([datumPartOf]), chained by the same tip rule.
     */
    @Suppress("UNCHECKED_CAST")
    fun cutOnFace(
        part: Element,
        el: Element,
        depth: ScalarRef,
    ): Element? {
        val on = activeSpace.plane ?: return null
        if (part.kind != ElementKind.SOLID) return null
        // a *Cut* is a subtract, so the operand rule is the boolean's (see [openShellRefusal])
        openShellRefusal(part)?.let {
            note = it
            return null
        }
        val region = regionOf(el) ?: return null
        val tool = add(cx.extrude(cx.sketchBehind(on, region), depth), ElementKind.SOLID, Styles.SOLID)
        return add(cx.subtract(part.ref as SolidRef, tool.ref as SolidRef), ElementKind.SOLID, Styles.SOLID)
            .also {
                madeSolid(it, "${nameOf(el)} cut ${lengthWord(depth)} into ${nameOf(part)} on ${activeSpace.name}")
            }
    }

    // ---- the loft: a run of sections, and the pyramid gesture that is its two-section case (OP-17) ----

    /** What a loft pick **is**, told apart from the element itself and never from a value (see [loftRoleOf]). */
    enum class LoftRole {
        /** An area — an outline, a wall footprint, a closed curve or a closed chain: one section of the run. */
        SECTION,

        /** A point: the apex that ends the run, at the point's own sketch plane. */
        APEX,

        /** An **open** curve: a guide the run follows between the sections it passes through. */
        GUIDE,
    }

    /**
     * Which part of a loft the element [el] is, or null when it can be none.
     *
     * Told apart **structurally** — by kind, by the step that built it, by whether its path is closed — and
     * deliberately not by a value: the classification decides how many sections the node has, so a replay must
     * reach the same answer as the click did (OP-21's structure-at-build-time rule). Anything that *bounds an
     * area* is a section, which is why a circle is a section and never a guide: a guide runs *between*
     * sections, so it is the open curves that are left.
     */
    fun loftRoleOf(el: Element): LoftRole? =
        when {
            el.isPoint -> LoftRole.APEX
            el.isArea || boundaryPiecesOf(el) != null -> LoftRole.SECTION
            el.isCurve -> LoftRole.GUIDE
            else -> null
        }

    /** The plane of the space [name] — what a section drawn there is embedded on (OP-17). */
    private fun planeOfSpace(name: String): PlaneRef = spaceNamed(name)?.plane ?: cx.planeXY()

    /**
     * The **loft** over [picks]: the sections in the order they were clicked, an apex where one of them is a
     * point, and a guide for every open curve among them (OP-17's third feature).
     *
     * One node, one element. The two things worth stating about the gesture:
     *
     * - **Each section is embedded on the plane of the space it was drawn in**, not on the active one. That is
     *   what makes a loft across sketch planes an ordinary construction rather than a new concept: the picks
     *   may come from the plan and from two datum planes, and the solid is a function of all three planes, so
     *   tilting a datum reshapes it. The element itself is stamped into the **first section's** space, because
     *   that is the space its footprint hint is drawn in and the coordinates a pick of it measures against.
     * - **Where each section was clicked scores its seam** — which boundary piece the correspondence starts at
     *   — once, here, and the step then restates it in `signs=` (OP-1/OP-18). A replay hands the signs back and
     *   nothing is scored again, because the vertex nearest a click moves when the section does.
     *
     * Refused by name (and nothing built) when a pick is none of the three, when fewer than two sections were
     * picked, or when a section bounds no area right now. Everything *geometric* — a fold, a guide that misses
     * its corresponding point, a section with no area — is the node's own business and is reported as the
     * reason it is invalid, so it heals when the drawing moves (OP-3).
     */
    @Suppress("UNCHECKED_CAST")
    fun loftSolid(
        picks: List<Element>,
        clicks: List<Vec2>,
        signs: List<Int> = emptyList(),
    ): Element? {
        val roles =
            picks.map { el ->
                loftRoleOf(el) ?: run {
                    note = "Loft: ${nameOf(el)} is neither an area, a point nor a curve, so it can be no part of a loft"
                    return null
                }
            }
        val sections = roles.count { it != LoftRole.GUIDE }
        if (sections < 2) {
            note =
                "Loft: pick at least two sections — areas on their own sketch planes, or an area and a point " +
                "for an apex end (Extrude to point does that in one gesture)"
            return null
        }
        val parts = ArrayList<LoftPart>()
        val seams = ArrayList<Int>()
        var homeSpace: String? = null
        for ((i, el) in picks.withIndex()) {
            when (roles[i]) {
                LoftRole.SECTION -> {
                    val region =
                        regionOf(el) ?: run {
                            note = "Loft: ${nameOf(el)} bounds no area, so it is no section"
                            return null
                        }
                    if (homeSpace == null) homeSpace = el.space
                    parts.add(LoftPart.Area(cx.sketchOn(planeOfSpace(el.space), region)))
                    seams.add(signs.getOrNull(seams.size) ?: seamOf(region, clicks.getOrNull(i)))
                }
                LoftRole.APEX -> {
                    // A height point picked as the apex is taken **as it is** (OP-25) — it already says where
                    // it stands in space, and sharing the node is what makes two solids follow one apex
                    // (OP-5). A plain 2D point is lifted by nothing: it lies in its own sketch plane, which is
                    // the same construction the loft has always made, now said with the general node.
                    // told apart by **kind**, never by casting the ref: `Ref<V>`'s parameter is erased, so
                    // `as? Point3Ref` succeeds for any ref and would defer the mistake to the value
                    val point =
                        if (el.kind == ElementKind.HEIGHT_POINT) {
                            el.ref as Point3Ref
                        } else {
                            cx.heightPoint(planeOfSpace(el.space), el.ref as PointRef, cx.const(0.0.mm))
                        }
                    parts.add(LoftPart.Apex(point))
                    // a point has no boundary to start a correspondence at, and the list is indexed by section
                    seams.add(0)
                }
                LoftRole.GUIDE -> parts.add(LoftPart.Guide(planeOfSpace(el.space), el.ref))
            }
        }
        val el = add(cx.loft(parts, seams), ElementKind.SOLID, Styles.SOLID)
        if (homeSpace != null) el.space = homeSpace
        registerSigns(el, seams)
        note = loftNote(el, roles)
        return el
    }

    // ---- the loft as a skin over drawn sections (OP-26's hull route, session 78 — queue entry 1) ----

    /** Every stated correspondence a skin's step carries, in pairs — `match=` and the writer's authority. */
    private val skinMatches = HashMap<Step, List<Element>>()
    private var pendingSkinMatch: List<Element>? = null

    private var restampScript: String? = null

    /**
     * The **re-stamped script** the last gesture produced instead of building anything, if it did — read
     * once, by the editor, which adopts it ([constructit.editor.Editor.maybeCompleteTool]).
     *
     * The one seam a tool that *edits an existing step* needs: a `ToolDef.build` cannot replace the document
     * it is handed, so it hands back the text and the editor does the adopting — exactly as the panel's
     * *Section law* field and the wall extension already do, now reachable from the tool table.
     */
    fun takeRestamp(): String? = restampScript.also { restampScript = null }

    /**
     * **Match sections** (session 78): state that the curve [a] and the curve [b] correspond, and re-stamp
     * every skin that runs between the sections they belong to ([skinMatched]).
     *
     * A click is a choice, and this is the whole of the choice: the pair is recorded on the loft's own step by
     * the two curves' script names, so a replay re-discovers nothing, a rename re-stamps it, and deleting
     * either curve takes the skin with it like any other reference.
     */
    fun matchSections(
        a: Element,
        b: Element,
    ) {
        val script = skinMatched(a, b) ?: return
        restampScript = script
        note = "${nameOf(a)} now runs to ${nameOf(b)} — the loft's correspondence is stated on its own step"
    }

    /** The pairs [step] states, or null — how the writer restates that step's own `match=` (see [skinMatched]). */
    internal fun skinMatchesOf(step: Step): List<Element>? = skinMatches[step]

    /**
     * The **skin over the drawn sections** [picks] — the loft of OP-26's hull route, ruled or [row]-wise
     * faired (session 78; the correspondence design is the user's own, see DESIGN.md).
     *
     * One node, one element, and three things worth stating about the gesture:
     *
     * - **Every section is an ordinary sketch on a station of one common run**, which is the whole of what
     *   makes this a construction rather than a new concept: the sections are embedded on their stations' own
     *   plane nodes, so retyping a distance slides that station and the skin follows it, and everything drawn
     *   on the station stays a live sketch.
     * - **The order is the stations' stated distances**, read here once and thereby structural (OP-21): the
     *   picks may be clicked in any order, and the node's inputs stand in station order. The distances are
     *   recorded parameters, so this is a fact of the file rather than a discovery — and a slide that puts two
     *   stations in one plane, or one past its neighbour, is reported by the body itself, by their distances
     *   (OP-3).
     * - **[matches] are stated pairs of curves**, in the order the *Match sections* tool recorded them, and
     *   they are resolved here to the piece indices the geometry speaks ([SkinMatch]) — the naming authority's
     *   own division of labour: the file names curves, the feature names pieces.
     *
     * Refused by name and building nothing where the *structure* is wrong — a pick that bounds no area, a
     * section that is not on a station, sections on two different runs, a closed run, fewer than two sections,
     * a matched curve that belongs to neither of two consecutive sections. Everything geometric is the node's
     * own business and is reported as the reason it is invalid, so it heals when the drawing moves (OP-3).
     */
    @Suppress("UNCHECKED_CAST")
    fun skinSolid(
        picks: List<Element>,
        row: SkinRow,
        matches: List<Element> = emptyList(),
    ): Element? {
        val what = if (row == SkinRow.RULED) "Loft (ruled)" else "Loft (faired)"
        val ev = Evaluator()
        // one region node per section, built once: [regionOf] *constructs* one, so asking twice would leave a
        // node nothing references behind (harmless, but this is the graph and it should stay tidy)
        val regions = HashMap<String, RegionRef>()
        val order = ArrayList<Triple<Element, ScalarEntry, Double>>()
        var spine: Element? = null
        for (el in picks) {
            val region = regionOf(el)
            if (region == null) {
                note = "$what: ${nameOf(el)} bounds no area, so it is no section"
                return null
            }
            regions[el.id] = region
            val space = spaceOf(el)
            val station = space.station
            val along = space.along
            if (station == null || along == null) {
                note =
                    "$what: ${nameOf(el)} is drawn in ${spaceLabel(space)}, which does not stand across a run — " +
                    "a skin's sections live on stations of one run, so put a station on the run with *Station* " +
                    "and draw the section there"
                return null
            }
            val first = spine
            if (first == null) {
                spine = station
            } else if (first !== station) {
                note =
                    "$what: ${nameOf(el)} is on a station of ${nameOf(station)} and the first section is on a " +
                    "station of ${nameOf(first)} — one skin runs over stations of **one** run, so loft each run's " +
                    "sections on their own"
                return null
            }
            order.add(Triple(el, along, evalMm(along.ref)))
        }
        val run = spine
        if (order.size < 2 || run == null) {
            note = "$what: pick at least two sections, each drawn on a station of one run"
            return null
        }
        // **A ring of sections has no first and no last section to cap** (the design's first-slice cut, named):
        // the run's closure is a fact of its construction, so this is a gesture refusal and not a value.
        val path = spaceCurveRef(run, what) ?: return null
        if ((ev.valueOf(path) as? Path3Value)?.path?.closed == true) {
            note =
                "$what: ${nameOf(run)} is a closed run, so its stations come round to where they started and " +
                "there is no first or last section to cap — a ring skin is a future extension; cut the run open, " +
                "or loft the sections of each part of it"
            return null
        }
        // the stations' own distances are the order (a stable sort, so nothing about equal ones is invented —
        // two sections at one distance are refused by the body itself, naming the distance)
        val sorted = order.sortedBy { it.third }
        val sections = sorted.map { it.first }
        val stated = ArrayList<SkinMatch>()
        if (matches.size % 2 != 0) {
            note = "$what: a stated match is two curves, and this step names ${matches.size}"
            return null
        }
        for (i in matches.indices step 2) {
            val m = skinMatchOf(sections, matches[i], matches[i + 1], what, ev, regions) ?: return null
            stated.add(m)
        }
        val parts = sorted.map { (el, along, _) -> SkinPart(cx.sketchOn(planeOfSpace(el.space), regions[el.id]!!), along.ref) }
        val solid = add(cx.skin(parts, row, stated), ElementKind.SOLID, Styles.SOLID)
        // the first section's own space, exactly as a loft belongs to its first section's: that is where its
        // footprint hint is drawn and the coordinates a pick of it measures against
        solid.space = sections.first().space
        if (matches.isNotEmpty()) pendingSkinMatch = matches
        madeSolid(
            solid,
            "${sections.size} sections skinned along ${nameOf(run)}" +
                (if (row == SkinRow.RULED) " with straight rulings" else ", faired through every station") +
                (if (stated.isEmpty()) "" else ", with ${stated.size} matched ${if (stated.size == 1) "pair" else "pairs"}"),
        )
        return solid
    }

    /**
     * The pair `(a, b)` as a [SkinMatch] over [sections], or null with [note] set.
     *
     * Two curves of **consecutive** sections, in either order — which one is the lower is the stations' own
     * business and not the click's, so the tool takes them as they were clicked and this puts them the right
     * way round.
     */
    private fun skinMatchOf(
        sections: List<Element>,
        a: Element,
        b: Element,
        what: String,
        ev: Evaluator,
        regions: Map<String, RegionRef> = emptyMap(),
    ): SkinMatch? {
        val pa = skinPieceOf(sections, a, ev, regions)
        val pb = skinPieceOf(sections, b, ev, regions)
        if (pa == null || pb == null) {
            val lost = if (pa == null) a else b
            note =
                "$what: ${nameOf(lost)} is no piece of any of these sections' outlines, so there is nothing to " +
                "match with it — click a curve of one section and a curve of the next"
            return null
        }
        val (ka, ia) = pa
        val (kb, ib) = pb
        if (ka == kb) {
            note =
                "$what: ${nameOf(a)} and ${nameOf(b)} are both pieces of section ${ka + 1}, and a match runs " +
                "from one section to the next — click a curve of each"
            return null
        }
        if (ka + 1 == kb) return SkinMatch(ka, ia, ib)
        if (kb + 1 == ka) return SkinMatch(kb, ib, ia)
        note =
            "$what: sections ${minOf(ka, kb) + 1} and ${maxOf(ka, kb) + 1} are not neighbours along the run, and " +
            "a match pairs the curves of **consecutive** sections — match each interval's own curves"
        return null
    }

    /**
     * Which section of [sections] the curve [curve] is a boundary piece of, and **which piece** — the one
     * translation from the file's own name for a curve to the index the geometry speaks ([SkinMatch]).
     *
     * The index is the piece's position in the section's own outline loop, which is what a stored face address
     * already means ([constructit.geom.Section3.FACE_ADDRESS_CONVENTION]) — and it is read off the *region the
     * skin is built from* rather than off the click order, because a chain's pieces are chained into a loop
     * and the loop's order is the one the feature indexes.
     */
    private fun skinPieceOf(
        sections: List<Element>,
        curve: Element,
        ev: Evaluator,
        regions: Map<String, RegionRef> = emptyMap(),
    ): Pair<Int, Int>? {
        for ((k, section) in sections.withIndex()) {
            val pieces = boundaryPiecesOf(section) ?: if (section === curve) listOf(section) else continue
            if (pieces.none { it === curve }) continue
            val ref = regions[section.id] ?: regionOf(section) ?: continue
            val region = (ev.valueOf(ref) as? RegionValue)?.region ?: continue
            val want = profilePieceOf(ev.valueOf(curve.ref) ?: continue) ?: continue
            val at = region.outer.elements.indexOfFirst { samePiece(it, want) }
            if (at >= 0) return k to at
        }
        return null
    }

    /** Whether two boundary pieces are the same curve — walked either way, since a loop may reverse one. */
    private fun samePiece(
        a: ProfileElement,
        b: ProfileElement,
    ): Boolean {
        if (a::class != b::class) return false
        val tol = Geom3.WELD_TOL
        val a0 = GeomMath.startOf(a)
        val a1 = GeomMath.endOf(a)
        val b0 = GeomMath.startOf(b)
        val b1 = GeomMath.endOf(b)
        return ((a0 - b0).length() <= tol && (a1 - b1).length() <= tol) ||
            ((a0 - b1).length() <= tol && (a1 - b0).length() <= tol)
    }

    /**
     * The whole journal with the pair `(a, b)` **stated on every skin that spans those two sections** — or
     * null with [note] set, by name.
     *
     * A Match is an **edit** of the bodies it concerns and never a feature of its own, which is the size
     * law's own mechanism read once more ([sweepLawRestated], and OP-23's re-stamp precedent before it): the
     * step that already declares the skin gains one argument, so the body keeps its identity, its name and
     * everything built on it, nothing downstream is rewired, and the whole thing is one undo.
     *
     * **Every** skin that runs over both sections, deliberately: a stated pair is a fact about two curves of
     * the drawing, so a second skin over the same sections reads the same statement rather than needing the
     * click again.
     *
     * This document is left exactly as it was — what comes back is text, and the caller adopts it
     * ([constructit.editor.Editor.matchSections]).
     */
    fun skinMatched(
        a: Element,
        b: Element,
    ): String? {
        val ev = Evaluator()
        val touched = ArrayList<Pair<Step, List<Element>>>()
        for (step in journal) {
            if (step.kind != "tool") continue
            val id = (step.args.firstOrNull() as? Arg.Text)?.s ?: continue
            if (id != Tools.LOFT_RULED && id != Tools.LOFT_FAIRED) continue
            val sections = referencedElements(step).filter { el -> el.isArea || boundaryPiecesOf(el) != null }
            if (skinPieceOf(sections, a, ev) == null || skinPieceOf(sections, b, ev) == null) continue
            val now = skinMatches[step] ?: emptyList()
            if (now.indices.step(2).any { (now[it] === a && now[it + 1] === b) || (now[it] === b && now[it + 1] === a) }) {
                note = "Match sections: ${nameOf(a)} and ${nameOf(b)} are matched already"
                return null
            }
            touched.add(step to (now + listOf(a, b)))
        }
        if (touched.isEmpty()) {
            note =
                "Match sections: no loft runs between the sections ${nameOf(a)} and ${nameOf(b)} belong to — " +
                "loft the sections first (*Loft (ruled)* or *Loft (faired)*) and then match the curves that " +
                "should meet; a loft whose sections have different piece counts says so and waits for exactly this"
            return null
        }
        val before = touched.map { (step, _) -> step to skinMatches[step] }
        return try {
            for ((step, pairs) in touched) skinMatches[step] = pairs
            DocumentFormat.save(this)
        } finally {
            for ((step, was) in before) {
                if (was == null) skinMatches.remove(step) else skinMatches[step] = was
            }
        }
    }

    /**
     * Which way "up" is for a point lifted off the **active** plane: the plane's own +normal, everywhere.
     *
     * *Extrude*'s own rule, stated once and shared by everything that lifts (OP-25). It used to make a face
     * the exception (−1), because a face plane's normal pointed *into* the material and a positive height
     * there must still stand outward; since the session-32 frame rule a face's normal points out of the
     * material like every other space's, so the exception is gone rather than compensated. Structural — read
     * from the space at build time and re-read on replay — so nothing about it is persisted either way.
     */
    private fun liftSign(): Int = 1

    /**
     * A **height point** over [base], standing [height] off the active sketch plane (OP-25) — the user's
     * generalization of the apex: *"an arbitrary free point in 3D from a point in 2D and a given height
     * parameter … 1 dof over its base point."*
     *
     * One element over one node, so everything else about it is what any element already gets: the height is
     * an ordinary named scalar (panel row, rename, wire), the base is a point like any other and stays
     * draggable where it lives, and the point itself is grabbed *in the 3D view*, where the drag reads its
     * height off the pointer's ray ([HeightPointHandle]).
     */
    fun heightPoint(
        base: PointRef,
        height: ScalarRef,
    ): Element {
        val sign = liftSign()
        val plane = activePlane()
        val el = add(cx.heightPoint(plane, base, height, sign), ElementKind.HEIGHT_POINT, Styles.DERIVED_POINT)
        el.handle = HeightPointHandle(plane, base, height.node, sign)
        // its value is a point in **space**, which is what every consumer reads off the element (OP-26) rather
        // than off the kind — see [Element.inSpace] and [pointInSpace]
        el.inSpace = true
        return el
    }

    /** The height-point gesture: a base point (clicked or created) and a height. */
    fun heightPointAt(
        base: PointRef,
        height: ScalarRef,
    ): Element {
        val el = heightPoint(base, height)
        note =
            "${nameOf(el)}: ${Format.num(((Evaluator().valueOf(height) as? ScalarValue)?.q?.mm) ?: 0.0)} mm above ${nameOf(elementFor(base) ?: el)} " +
            "— drag it in the 3D view to change the height, or retype it in the panel"
        return el
    }

    /** How a projected point (GitHub #14) is anchored — its target [plane] and the [source] it follows. */
    class ProjectedPoint(val plane: PlaneRef, val source: Element)

    /** Points that are the projection of another pane's point onto the active plane, by element id. */
    private val projected = HashMap<String, ProjectedPoint>()

    /** How [el] is projected, if it is a projected point (GitHub #14). */
    fun projectedOf(el: Element): ProjectedPoint? = projected[el.id]

    /**
     * A **projected point** (GitHub #14): [source] — a point defined on another pane — dropped along the
     * **active plane's normal** onto it, an ordinary derived point of the active space at that foot.
     *
     * > *"I'd like to select a point defined on an ancestor pane to get a derived point on my pane that is the
     * > projection of the ancestor pane's point on my pane."*
     *
     * The completion of what the context drawing already promises. A plane draws the outline of the solids
     * built before it (OP-17), which lets a construction reference *where the material is*; this lets it
     * reference *a point that was drawn elsewhere* — the two ways of anchoring across panes. The source is
     * **shared by node**, never copied (the no-solver stance): its world position flows through
     * [pointInSpace], the one seam every point kind already publishes it through (OP-26, session 53), so a
     * free point, an intersection, a coil rider, a height point or a section key point all project the same
     * way, and dragging the source — or tilting either plane — moves the projection with it.
     *
     * The result is a plain 2D point of the active plane, in that plane's own (u, v): usable as a circle's
     * centre, a coil's axis, a weld target, drawn where any point of this space is drawn. Its world position,
     * lifted back by a zero height ([pointInSpace]), is the perpendicular foot. Published through a
     * **re-pointable view** ([IndirectNode], OP-16) so *Make absolute* can free it in place ([detachProjected]),
     * the same affordance every bound point has (OP-4 case b).
     *
     * Refused **by name**, building nothing, only for the one structural thing — a pick that is not a point, or
     * a plane point that already lies **in** the target plane, whose projection onto it is itself. A source with
     * no current value is not refused: the projection is invalid with the source's own reason and heals when the
     * source has a value again (OP-3), because *where* the source is is a value, not structure.
     */
    fun projectToPlane(source: Element): Element? =
        recording("projectplane", Arg.El(source), skipIfEmpty = true) { projectToPlaneNow(source) }

    @Suppress("UNCHECKED_CAST")
    private fun projectToPlaneNow(source: Element): Element? {
        val target = activeSpace
        if (!source.isPoint) {
            note = "Project point: ${nameOf(source)} is ${kindWord(source)}, not a point — click the point to project"
            return null
        }
        // A **plane** point of the very plane it would be projected onto lands on itself — a structural refusal
        // (a 2D point of a space lies in that space's plane by construction), so it is caught here and not left to
        // heal. A point *in space* whose value happens to lie in the plane is a value condition and projects: its
        // foot is a real, different point (dropping a height point onto its own plane recovers its base).
        if (!source.inSpace && source.space == target.name) {
            note =
                "Project point: ${nameOf(source)} already lies in ${target.name} — its projection onto ${target.name} " +
                "is itself. Pick a point defined on another pane, or switch to the pane you want it projected onto."
            return null
        }
        val plane = activePlane()
        val local = cx.projectToPlane(plane, pointInSpace(source))
        val view = cx.indirect(local)
        val el = add(view, ElementKind.DERIVED_POINT, Styles.DERIVED_POINT)
        projected[el.id] = ProjectedPoint(plane, source)
        note =
            "${nameOf(el)}: ${nameOf(source)} projected onto ${target.name} — it follows ${nameOf(source)} " +
            "(drag it, or anything it is built on, and the projection moves)"
        return el
    }

    /**
     * Free projected point [pt] (GitHub #14) into a plain **free point of its plane** at the (u, v) it now has:
     * its view ([IndirectNode]) is re-pointed at a fresh free source, so nothing moves at the moment of the
     * change and everything built on it follows the point instead of the source from here on — [detachRider]'s
     * own sentence (OP-16's view re-pointed, OP-5's bind-in-place), for a point bound to a projection rather
     * than to a curve.
     *
     * [dofs] is the freed position as a replay hands it back (OP-18): an ordinary free point from now on, its
     * coordinates are state and ride the same `dofs=` seam every other re-parameterization uses ([relativeDofs],
     * via [detached]).
     */
    private fun detachProjected(
        pt: Element,
        dofs: List<Quantity>,
    ): Boolean {
        val view = pt.ref.node as? IndirectNode ?: return false
        val src = projected[pt.id]?.source
        val here = pointOf(view, Evaluator()) ?: return false
        val lengths = dofs.filter { it.dim == Dimension.LENGTH }
        val at = if (lengths.size == 2) Vec2(lengths[0].mm, lengths[1].mm) else here
        val free = SourceNode(nextId("fp"), PointValue(at))
        view.boundTo = free
        projected.remove(pt.id)
        detached[pt.id] = free
        pt.kind = ElementKind.POINT
        pt.style = Styles.FREE_POINT
        pt.handle = FreePointHandle(free)
        noteReparam(pt)
        noteEdit()
        note =
            "${nameOf(pt)} keeps its position and is a free point of ${pt.space} now — " +
            "it no longer follows ${src?.let { nameOf(it) } ?: "its source"}"
        return true
    }

    /**
     * A **curve in space through the points that were clicked** (OP-26's first source, step 1).
     *
     * The gesture is a repeating pick over points, and everything about *what is built* is read off the pick
     * list rather than from a flag beside it:
     *
     * - **Closed is said by returning to where you started.** Clicking the first point again both finishes the
     *   run and states the closure, so the collected picks end with the point they began with
     *   (`ToolDef.closesOnFirstPick`) — and the recorded step therefore states it too, by naming that point
     *   twice (`els=e3,e5,e7,e3`). No new file argument, and a replay closes for exactly the reason the
     *   gesture did. Enter finishes an **open** run.
     * - **Straight or smooth is said by which tool was used** — two ids, exactly as *Circle (centre, point)*
     *   and *Circle (centre, radius)* are two ids for one shape. A tool id is what the file records (OP-18),
     *   so the reading is persisted with no argument of its own, and neither build has to guess which one a
     *   gesture meant.
     *
     * **A pick is taken as the point in space it already is.** A height point (OP-25) is used as it stands —
     * so the curve *shares the node*, and dragging that point's base or retyping its height moves the curve,
     * along with everything else built on it. A plain 2D point is lifted by a **zero** height on its own
     * space's plane, which is the identical construction a loft's apex makes of one (see [loftSolid]) — so a
     * curve can be routed through ordinary drawn points without a second gesture, and the point stays
     * draggable where it lives. Told apart by the element's **kind**, never by casting the ref: `Ref<V>`'s
     * parameter is erased, so `as? Point3Ref` succeeds for anything and would defer the mistake to the value.
     *
     * Refused **by name**, building nothing, for the three things that are about *how many points there are*
     * — a pick that is not a point, fewer than two, a closed run with fewer than three, or the same point
     * clicked twice in a row. Everything about *where* the points are is the node's business and is reported
     * as the reason it is invalid, so it heals when the drawing moves (OP-3, [Construction.pathThrough]).
     */
    fun curveThroughPoints(
        picks: List<Element>,
        smooth: Boolean,
    ): Element? {
        val what = if (smooth) "Smooth curve" else "Curve"
        for (el in picks) {
            if (!el.isPoint) {
                note = "$what through points: ${nameOf(el)} is ${kindWord(el)}, not a point — click points in space"
                return null
            }
        }
        // the closure the gesture stated: the run came back to the point it started at
        val closed = picks.size >= 2 && picks.first() === picks.last()
        val through = if (closed) picks.dropLast(1) else picks
        if (through.size < 2) {
            note = "$what through points: click at least two points — one point is a place, not a curve"
            return null
        }
        if (closed && through.size < 3) {
            note = "$what through points: a closed curve needs at least three points — two would double back on themselves"
            return null
        }
        for (i in 0 until through.size - 1) {
            if (through[i] === through[i + 1]) {
                note = "$what through points: ${nameOf(through[i])} was clicked twice in a row, and a piece from a point to itself has no direction"
                return null
            }
        }
        val refs = through.map { pointInSpace(it) }
        val curve = add(cx.pathThrough(refs, closed = closed, smooth = smooth), ElementKind.SPACE_CURVE, Styles.SPACE_CURVE)
        val shape = if (smooth) "smooth" else "straight"
        note =
            "${nameOf(curve)}: a $shape ${if (closed) "closed " else ""}curve through ${through.size} points " +
            "(${through.joinToString(", ") { nameOf(it) }}) — move any of them and it follows"
        return curve
    }

    /**
     * A **helix** about the axis standing on the sketch plane at the picked point [el] (OP-26, step 3) — a
     * spring, a coil, the route a thread runs on.
     *
     * One click and three numbers, and the click is the only thing that is geometry: the axis is *this
     * space's normal through that point*, which is the same sentence a height point says (OP-25) and needs
     * no manipulator to state. So the coil rides its parents — drag the point and it moves, retype the
     * point's height and it rises, tilt the datum it was drawn on and the whole spring tilts with it.
     *
     * **A pick is taken as the point in space it already is**, exactly as a curve through points takes one: a
     * height point is used as it stands and its node is *shared*, and a plain 2D point is lifted by a **zero**
     * height onto its own space's plane — told apart by the element's kind, never by casting the ref.
     *
     * [hand] is which **tool** was used, not an argument: chirality is a discrete choice, so it is structural
     * (OP-1), and a tool id is what the file records (OP-18) — the same way *Curve through points* and
     * *Smooth curve through points* are two ids for one shape. There is therefore no new file argument and a
     * replay is right-handed for exactly the reason the gesture was.
     *
     * Refused **by name**, building nothing, for the one structural thing — a pick that is not a point.
     * Everything about the three numbers is the node's business and is reported as the reason it is invalid,
     * so it heals when a number moves back (OP-3, [Construction.helix]).
     */
    fun helixAbout(
        el: Element,
        radius: ScalarRef,
        pitch: ScalarRef,
        turns: ScalarRef?,
        hand: Handedness,
    ): Element? {
        if (!el.isPoint) {
            note = "Helix: ${nameOf(el)} is ${kindWord(el)}, not a point — click the point the axis stands on"
            return null
        }
        val plane = planeOfSpace(el.space)
        // a turn count nobody stated comes from the *tool* as a freedom the step owns ([toolScalarRefs]); the
        // constant is what a direct call means, and a direct call is code
        val n = turns ?: cx.const(Quantity.number(1.0))
        val curve = add(cx.helix(plane, pointInSpace(el), radius, pitch, n, hand), ElementKind.SPACE_CURVE, Styles.SPACE_CURVE)
        curve.space = el.space
        note =
            "${nameOf(curve)}: a ${hand.word} helix about ${nameOf(el)} — ${lengthWord(radius)} radius, " +
            "${lengthWord(pitch)} per turn, rising out of ${el.space}"
        return curve
    }

    /**
     * A **helix about [center] that begins at [start]** (OP-26, step 3) — the coil's other spelling, and the
     * one that states its **phase**.
     *
     * Two clicks and two numbers, and the second click is the whole of the difference: where a coil starts is
     * a real degree of freedom, and [helixAbout] can only ever start it along its space's own x. Stating it
     * with a point states the radius with the same click — a coil's base *is* a circle, so this stands to
     * [helixAbout] exactly as *Circle (centre, point)* stands to *Circle (centre, radius)*.
     *
     * **The start point is an ordinary pick and its node is shared** (the `POINT3` slot's rule): a coil can
     * begin at the edge of a drilled hole or on a boss, and it follows when that moves — which is the reason
     * this spelling exists at all, rather than a stated angle. An angle would be a number beside the drawing;
     * a point is *in* it (OP-26's explicit-anchor rule).
     *
     * Refused **by name**, building nothing, for the two structural things — a pick that is not a point, and
     * one point clicked for both, which no edit could ever heal because one node cannot stand in two places.
     * Two *different* points that happen to coincide is a condition on values, so it is the node's business
     * and comes back as the reason it is invalid, healing when either point moves (OP-3,
     * [Construction.helixThrough]).
     */
    fun helixThrough(
        center: Element,
        start: Element,
        pitch: ScalarRef,
        turns: ScalarRef?,
        hand: Handedness,
    ): Element? {
        for (el in listOf(center, start)) {
            if (!el.isPoint) {
                note =
                    "Helix: ${nameOf(el)} is ${kindWord(el)}, not a point — click the point the axis stands " +
                    "on, then the point the coil starts at"
                return null
            }
        }
        if (center === start) {
            note =
                "Helix: ${nameOf(center)} was clicked for both the centre and the start point — those two " +
                "are what state the radius, and one point cannot stand at both ends of it"
            return null
        }
        val plane = planeOfSpace(center.space)
        val n = turns ?: cx.const(Quantity.number(1.0))
        val curve =
            add(
                cx.helixThrough(plane, pointInSpace(center), pointInSpace(start), pitch, n, hand),
                ElementKind.SPACE_CURVE,
                Styles.SPACE_CURVE,
            )
        curve.space = center.space
        val r = helixOf(curve)?.radius
        note =
            "${nameOf(curve)}: a ${hand.word} helix about ${nameOf(center)}, starting at ${nameOf(start)} — " +
            (r?.let { "${Format.num(it)} mm radius, " } ?: "") +
            "${lengthWord(pitch)} per turn, rising out of ${center.space}"
        return curve
    }

    /** The helix [el] evaluates to, if it is one — what a status line says a picked start point bought. */
    private fun helixOf(el: Element): Curve3Element.Helix3? =
        ((Evaluator().eval(el.ref.node) as? EvalResult.Ok)?.value as? Path3Value)
            ?.path
            ?.elements
            ?.firstOrNull() as? Curve3Element.Helix3

    /**
     * [el] as the **point in space** it already is — a height point taken as it stands, its node *shared*,
     * and a plain 2D point lifted by a **zero** height onto its own space's plane (OP-26).
     *
     * One reading for every tool with a `POINT3` slot, told apart by the element's kind and never by casting
     * the ref: a curve through points, a coil's axis point and a coil's start point all mean the same thing
     * by a click on a point, so they say it once.
     */
    @Suppress("UNCHECKED_CAST")
    private fun pointInSpace(el: Element): Point3Ref =
        if (el.inSpace) {
            el.ref as Point3Ref
        } else {
            cx.heightPoint(planeOfSpace(el.space), el.ref as PointRef, cx.const(0.0.mm))
        }

    /**
     * **Two views combined into one run** (OP-26, step 5): [plan] drawn in one space, [elevation] drawn in
     * another that meets it, and the curve in space whose projection into each is the drawing made there.
     *
     * The routing workhorse, and it is how routing was done on drawing boards long before there were
     * kernels: draw the route twice, carry each point across with dividers, and the run in space is what the
     * two drawings jointly say. **It needs no new editing surface at all** — both picks are ordinary sketch
     * curves in ordinary spaces, so everything that already makes a drawing live (dragging a point, retyping
     * a radius, tilting the space it stands on) makes the run live with it. The two names are the everyday
     * reading rather than a requirement: any two curves in any two non-parallel spaces will do.
     *
     * The run belongs to the **first** view's space, exactly as a loft belongs to its first section's: that
     * is the space its plan projection is drawn in, the coordinates a pick of it measures against, and the
     * space whose normal starts a sweep's frame — one statement about which space this run belongs to. It is
     * also the view whose direction the run takes, so the far end of a *Station*'s distance is the end of the
     * curve the user drew first.
     *
     * Refused **by name**, building nothing, only for the structural things — a pick that is not a curve, a
     * pick that runs on for ever (a line or a ray states no run), and one curve clicked twice. Everything
     * about *where* the two drawings are is the node's business and comes back as the reason it is invalid,
     * so it heals when the drawing moves (OP-3): parallel spaces, a view that doubles back along the common
     * direction, and two views whose runs do not overlap are all values, and a gesture refused on a value
     * would make replay depend on one.
     */
    fun combineViews(
        plan: Element,
        elevation: Element,
    ): Element? {
        for (el in listOf(plan, elevation)) {
            if (!el.isCurve) {
                note =
                    "Combine two views: ${nameOf(el)} is ${kindWord(el)}, not a curve — draw each view as one " +
                    "curve and click them in turn"
                return null
            }
            if (el.kind == ElementKind.LINE || el.kind == ElementKind.RAY) {
                note =
                    "Combine two views: ${nameOf(el)} runs on for ever, so it states no length of run to " +
                    "match — a view is a bounded curve"
                return null
            }
            // a combined run rides both views' **tangents** (its frame is parallel-transported along them),
            // so a function curve with no statable derivative is refused **up front and by name** rather
            // than being differenced somewhere inside — the session-69 predicate rule
            funcTangentRefusal(el)?.let {
                note = "Combine two views: $it"
                return null
            }
        }
        if (plan === elevation) {
            note =
                "Combine two views: ${nameOf(plan)} was clicked twice, and one drawing is one view — the " +
                "second view is drawn in another space, so switch the sketch plane between the two clicks"
            return null
        }
        val curve =
            add(
                cx.combinedViews(planeOfSpace(plan.space), plan.ref, planeOfSpace(elevation.space), elevation.ref),
                ElementKind.SPACE_CURVE,
                Styles.SPACE_CURVE,
            )
        curve.space = plan.space
        note =
            "${nameOf(curve)}: the run whose projection into ${plan.space} is ${nameOf(plan)} and into " +
            "${elevation.space} is ${nameOf(elevation)} — move either drawing, or either space, and it follows"
        return curve
    }

    // ---- connect: the joining piece between two curve ends (OP-26, step 7) ----

    /**
     * The **joining piece between one end of [first] and one end of [second]** (OP-26, step 7) — the bend that
     * turns two runs that stop near each other into one route.
     *
     * **Two clicks, and each of them says two things**: which curve, and — by where it lands along it — which
     * of that curve's two ends is being joined. That is *a click is a choice* read exactly as OP-1 reads it
     * for an intersection's branch: scored **once**, here, from the click's own proximity to the two ends as
     * they are drawn in the space the click was made in, and then written into the step's `signs=` and taken
     * verbatim by every replay ([signs]). A reload that re-scored would swap the connection to a run's other
     * end as soon as an edit moved the geometry past the remembered click — the fillets-came-back-inverted
     * defect, and the reason session 41's kept side and session 45's chosen curve are stored rather than
     * re-derived.
     *
     * The piece **rides both curves**: they are its only geometric inputs, so moving either — or anything
     * either was built on — moves the connection and it stays smooth, with nothing rebuilt (OP-21). It belongs
     * to the **first** pick's space, exactly as a loft belongs to its first section's and a combined run to its
     * plan's: that is where its projection is drawn, the coordinates a pick of it measures against, and the
     * space whose normal starts a sweep's frame.
     *
     * Refused **by name**, building nothing, only for the two structural things — a pick that is not a curve
     * in space, and the **same end of the same curve** clicked twice, which states no gap and cannot heal
     * because both halves of it are structure. Everything about *where* the two runs are — ends that coincide,
     * a closed run with no end to join, a tension of nothing — is the node's business and comes back as the
     * reason it is invalid, so it heals when the drawing moves (OP-3, [Construction.connect]).
     */
    @Suppress("UNCHECKED_CAST")
    fun connectCurves(
        first: Element,
        second: Element,
        tensionA: ScalarRef?,
        tensionB: ScalarRef?,
        clicks: List<Vec2>,
        signs: List<Int>,
        mode: Continuity,
    ): Element? {
        val what = "Connect${if (mode == Continuity.G2) " (curvature)" else ""}"
        val runs = listOf(first, second).map { spaceCurveRef(it, what) ?: return null }
        val ends =
            if (signs.size >= 2) {
                signs.take(2).map { if (it == CurveEnd.START.ordinal) CurveEnd.START else CurveEnd.END }
            } else {
                val ev = Evaluator()
                listOf(
                    endNear(runs[0], first, clicks.getOrNull(0), CurveEnd.END, ev),
                    endNear(runs[1], second, clicks.getOrNull(1), CurveEnd.START, ev),
                )
            }
        if (first === second && ends[0] == ends[1]) {
            note =
                "$what: the ${ends[0].word} of ${nameOf(first)} was clicked twice, and a join needs two ends — " +
                "click near the other end of it to close the run, or pick a second curve"
            return null
        }
        val one = cx.const(Quantity.number(1.0))
        val run = cx.connect(runs[0], ends[0], runs[1], ends[1], tensionA ?: one, tensionB ?: one, mode)
        val from = "a ${mode.word} join from the ${ends[0].word} of ${nameOf(first)} to the ${ends[1].word} of ${nameOf(second)}"
        val structurally = inSpaceBecause(first, second)
        val inSpace = structurally ?: keptInSpaceByItsFile(mode, signs)
        if (inSpace == null) {
            val plane = planeOfSpace(first.space)
            val spans =
                (0 until mode.spans).map { i ->
                    add(cx.planarSpan(run, plane, i, mode.spans), ElementKind.BEZIER, Styles.CURVE)
                        .also { it.space = first.space }
                }
            registerSigns(spans.first(), ends.map { it.ordinal })
            val named = spans.joinToString(", ") { nameOf(it) }
            val piecesWord =
                if (spans.size == 1) {
                    "a drawing curve — it can close an outline, be filleted, broken or dimensioned like any other"
                } else {
                    "${spans.size} drawing curves — together they are the join, and an outline can take all of them"
                }
            note = "$named: $from — $piecesWord; exact, and it follows both of them"
            // **The deliberate change, said once on load** (GitHub #34, OP-18's versioning doctrine, and the
            // fillet's own precedent at [DocumentFormat.SUPERSEDING_FILLET_VERSION]): the file's literals are
            // untouched and the geometry is to the last bit what it was — what is different is that the join
            // is now a curve *of the drawing*, which is exactly what the report asked for.
            if ((replayingVersion ?: DocumentFormat.VERSION) < DocumentFormat.PLANAR_JOIN_VERSION) {
                noteLoad(
                    "$named ${if (spans.size == 1) "is" else "are"} the join of ${nameOf(first)} and " +
                        "${nameOf(second)} read in the drawing — the same curve in the same place, now a drawing " +
                        "curve, so an outline can be bounded by it",
                )
            }
            return spans.first()
        }
        val curve = add(run, ElementKind.SPACE_CURVE, Styles.SPACE_CURVE)
        curve.space = first.space
        // …and where the reading is the *file's* rather than the picks', that is a choice, so it is written
        // down beside the two ends and never worked out again (OP-1, OP-18) — see [keptInSpaceByItsFile]
        registerSigns(curve, ends.map { it.ordinal } + if (structurally == null) listOf(1) else emptyList())
        note = "${nameOf(curve)}: $from — a curve in space, because $inSpace; exact, and it follows both of them"
        // …and the load says so **once**, on the file that predates the reading, never again afterwards: a
        // file that carries the marker already means this, and a note about it would go on firing for ever
        if (structurally == null && (replayingVersion ?: DocumentFormat.VERSION) < DocumentFormat.PLANAR_JOIN_VERSION) {
            noteLoad(
                "${nameOf(curve)} stays a curve in space: a curvature join is ${mode.spans} pieces, and the one " +
                    "name this file gives it names all of them — connect the two curves again to have it as " +
                    "the drawing curves an outline can take",
            )
        }
        return curve
    }

    /**
     * Why the join of [first] and [second] cannot be a curve **of the drawing**, or null when it can — the
     * whole of GitHub #34's rule, and it is read off what was picked rather than off where anything stands
     * (OP-21).
     *
     * *"Connect curves result cannot be used to define an outline"*: the join of two drawn curves of one
     * space lies in that space's plane, so it **is** a drawing curve, and making it one is what lets it bound
     * an outline, be filleted, broken, dimensioned and swept like everything else drawn. Two picks that are
     * drawings of one space is a fact of the two elements' kinds and spaces — structure, decided when the
     * node is built and never re-derived — so a replay reaches the same reading without measuring anything.
     *
     * Anything else keeps the reading it always had: a curve in space has no plane to be drawn in, and two
     * spaces have no one plane between them.
     */
    private fun inSpaceBecause(
        first: Element,
        second: Element,
    ): String? =
        when {
            first.kind == ElementKind.SPACE_CURVE && second.kind == ElementKind.SPACE_CURVE ->
                "${nameOf(first)} and ${nameOf(second)} are curves in space"
            first.kind == ElementKind.SPACE_CURVE -> "${nameOf(first)} is a curve in space"
            second.kind == ElementKind.SPACE_CURVE -> "${nameOf(second)} is a curve in space"
            first.space != second.space ->
                "${nameOf(first)} and ${nameOf(second)} are drawn in ${spaceLabel(spaceOf(first))} and " +
                    "${spaceLabel(spaceOf(second))}, which have no one plane between them"
            else -> null
        }

    /**
     * Why a join the picks say is planar is nonetheless kept **as the curve in space its file made it** —
     * null for every join this build builds, and a sentence for the one case a file can carry (GitHub #34).
     *
     * **A name in a file names the same geometry for ever** (OP-18). A G1 join is one piece either way, so an
     * older file's `-> e7` still names the whole of it and the new reading costs that file nothing. A **G2**
     * join is three, and three drawing curves cannot wear one name: the step would create three elements
     * where the script declares one, and anything built on `e7` — a tube, a station, a sweep — would find a
     * third of the bend under it. So a file older than [DocumentFormat.PLANAR_JOIN_VERSION] keeps the curve
     * in space it was written with, the load says so once, and re-connecting the two curves is how the user
     * asks for the new reading.
     *
     * It is decided **once**, on that load, and then **written down** as a third entry in the step's own
     * `signs=` — the same treatment the two ends get, for the same reason (OP-1: scoring happens once; OP-18:
     * a choice is stored, never re-derived). That is what makes the re-saved file a fixed point: it says
     * `signs=0;0;1`, and every later load reads the join in space because the file says so rather than
     * because of a version it no longer declares.
     */
    private fun keptInSpaceByItsFile(
        mode: Continuity,
        signs: List<Int>,
    ): String? {
        if (mode.spans == 1) return null
        val marked = signs.size > 2 && signs[2] != 0
        val older = (replayingVersion ?: DocumentFormat.VERSION) < DocumentFormat.PLANAR_JOIN_VERSION
        return if (marked || older) "this drawing was written with it as one run in space" else null
    }

    /**
     * Which end of the run [run] a click at [at] names — **nearer wins**, measured in **[el]'s own space**.
     *
     * The run and the element are both passed because they answer different halves: a drawn pick is *lifted*
     * into the run being joined ([spaceCurveRef]), so the ends to score against are the **lifted** run's, while
     * the frame the click was made in is the element's own space. Reading the element's value here would have
     * scored a segment's ends against a `Path3Value` it does not have and fallen back to a default — the same
     * click meaning a different end depending on what kind of thing was picked.
     *
     * That is the right frame and not merely a convenient one: a curve in space is addressable only in the
     * space it belongs to ([addressableIn]), so the click that reached it was necessarily made in that space's
     * own coordinates — which is what makes the score independent of which space happens to be active when the
     * second pick lands, and therefore correct for a cross-space gesture.
     *
     * [fallback] is what a build with no click means (a macro's replay, a call from a test): the **end** of the
     * first run into the **start** of the second, which is the reading "carry on from where this one stops".
     * A tie goes to [CurveEnd.START], deterministically, so that the same drawing scores the same way twice.
     */
    private fun endNear(
        run: Path3Ref,
        el: Element,
        at: Vec2?,
        fallback: CurveEnd,
        ev: Evaluator,
    ): CurveEnd {
        val here = at ?: return fallback
        val path = (ev.valueOf(run) as? Path3Value)?.path ?: return fallback
        val start = path.start ?: return fallback
        val end = path.end ?: return fallback
        val plane = (ev.valueOf(planeOfSpace(el.space)) as? PlaneValue)?.plane ?: return fallback
        return if ((plane.toLocal(end) - here).length() < (plane.toLocal(start) - here).length()) CurveEnd.END else CurveEnd.START
    }

    // ---- intersection curves: where a working plane meets a solid (OP-26, step 6) ----

    /**
     * The ancestor solid whose section at [space]'s plane runs nearest [at], within [tol] — what a click on
     * the drawn section names.
     *
     * The **fourth reader of one enumeration** (GitHub #9: *"a plane should use all intersections with
     * ancestor solids as input geometry"*), beside what the canvas draws, what a section input can take and
     * what an origin can be anchored on. So the rule stays *what is visible is what is pickable*: the click
     * reaches whichever section it lands on, and nothing that is not drawn can be picked.
     */
    fun sectionSolidNear(
        space: SketchSpace,
        at: Vec2,
        tol: Double,
        ev: Evaluator,
    ): Element? {
        var best: Element? = null
        var bestDist = Double.MAX_VALUE
        for ((solid, section) in spaceSections(space, ev)) {
            for (piece in section.drawn) {
                val d = HitTest.distanceToPiece(at, piece)
                if (d <= tol && d < bestDist) {
                    bestDist = d
                    best = solid
                }
            }
        }
        return best
    }

    /**
     * The **curve in space where the active working plane meets [solid]** (OP-26, step 6) — one of the
     * ordered set, chosen by where the click landed and then persisted.
     *
     * A plane cuts a body in general in *several* curves — a plane through a bent bar cuts it twice, one
     * through a tube gives two loops — so this is OP-1's territory one dimension up: an **ordered solution
     * set** whose ordering is a stated function of the geometry, plus a separate `Select` holding the index.
     * [index] is what a replay hands back and is taken **verbatim**; only a live click scores, and it scores
     * once. Which curve is nearest a fixed position is exactly the sort of answer an edit elsewhere changes,
     * which is why a re-scoring reload would be a re-deciding one (OP-18, the fillets that came back
     * inverted).
     *
     * **It is the section machinery, promoted.** The node this hangs on is the *same* `section(solid, plane)`
     * node the plane's context is drawn from ([spaceSectionNodeOf]) — one node per (space, solid), shared with
     * every section input already taken there — so what the curve follows is exactly what the canvas shows,
     * and there is no second derivation that could disagree. The curve rides **both** operands: move the
     * solid, retype the plane's offset or tilt the datum, and it follows by recompute.
     *
     * Refused **by name**, building nothing, only for the structural things — a pick that is not a solid, a
     * plan space (which draws no section at all, GitHub #9's own rule), and a solid that is not an ancestor of
     * this plane, whose section node would point forward in creation order and is what keeps the graph acyclic.
     * Everything about *where* the plane is — that it misses the body, that the curve the user chose is no
     * longer there — is the node's business and comes back as the reason it is invalid, so it heals when the
     * drawing moves (OP-3).
     */
    @Suppress("UNCHECKED_CAST")
    fun intersectionCurve(
        solid: Element,
        near: Vec2,
        index: Int? = null,
    ): Element? {
        val space = activeSpace
        if (solid.kind != ElementKind.SOLID) {
            note = "Intersection curve: ${nameOf(solid)} is ${kindWord(solid)}, not a solid — click the section of the body you want the curve of"
            return null
        }
        if (space.plane == null) {
            note =
                "Intersection curve: the plan is not a working plane, so it draws no section — open one " +
                "(Plane at height, Sketch plane, Sketch on face) and click the body's section there"
            return null
        }
        val ev = Evaluator()
        if (spaceAncestors(space, ev).none { it === solid }) {
            note =
                "Intersection curve: ${nameOf(solid)} was built after ${space.name}, and a plane's inputs are " +
                "the solids that came before it — draw the plane after the body, or pick one that is already its context"
            return null
        }
        val sectionNode =
            spaceSectionNodeOf(space, solid) ?: run {
                note = "Intersection curve: ${space.name} has no section of ${nameOf(solid)} to take a curve from"
                return null
            }
        val planeRef = planeOfSpace(space.name)
        val set = cx.intersectionCurves(sectionNode, planeRef)
        val plane = (ev.valueOf(planeRef) as? PlaneValue)?.plane
        val curves = cx.solutionCurves(set, ev)
        val chosen =
            index ?: run {
                if (plane == null) {
                    note = "Intersection curve: ${space.name} has no value right now, so there is nothing to cut with"
                    return null
                }
                if (curves.isEmpty()) {
                    note =
                        "Intersection curve: ${space.name} does not cut ${nameOf(solid)} anywhere, so there is " +
                        "no curve where they meet"
                    return null
                }
                curves.indices.minByOrNull { i -> nearestOnCurve(curves[i], plane, near) } ?: 0
            }
        val curve = add(cx.selectCurve(set, chosen), ElementKind.SPACE_CURVE, Styles.SPACE_CURVE)
        curve.space = space.name
        registerSigns(curve, listOf(chosen))
        val what = curves.getOrNull(chosen)
        note =
            "${nameOf(curve)}: curve ${chosen + 1} of ${curves.size} where ${space.name} meets ${nameOf(solid)}" +
            (what?.let { " — ${if (it.path.closed) "closed, " else ""}${it.exactnessWord}" } ?: "") +
            " — move either and it follows"
        return curve
    }

    // ---- projection onto a face: a drawing thrown at a body along the way it is drawn (OP-26, step 8) ----

    /**
     * The **drawing [view] projected onto a face of [solid]** (OP-26, step 8) — an engraved line, a trimmed
     * edge, a route that has to follow a surface.
     *
     * **Two picks and nothing else, and the direction is the drawing's own.** The curve is thrown along the
     * normal of the space it is drawn in, which is exactly what *"drop it onto the face"* means: the
     * projection along a space's normal *is* what that space's own view shows, so the result's shadow in that
     * space is the drawing itself, to the last bit. A direction is therefore never typed and never picked —
     * a space already *is* one, and datum planes take any hinge and any angle (OP-17), so a route to be
     * thrown obliquely is drawn in the space that throws it.
     *
     * **Which face is a choice, scored once and then persisted.** Among the body's named faces, the one the
     * drawing lands on and, of those, the one nearest the eye — a space is always seen from its own `+normal`,
     * so it is *the face you can see from where you drew* ([Project3.landingFace]). The index is written into
     * the step's `signs=` and taken verbatim by every replay (OP-1/OP-18): a reload that scored again would
     * move an engraving to the other side of a plate as soon as an edit slid the drawing past it, which is the
     * fillets-came-back-inverted defect two features along.
     *
     * The curve belongs to the **drawing's** space, exactly as a combined run belongs to its plan's and a join
     * to its first pick's — and here that reading is doubly true, since the projection *coincides* with the
     * drawing there.
     *
     * Refused **by name**, building nothing, only for the structural things: a pick that is not a curve, a
     * pick that runs on for ever (a line or a ray states no length of run, step 5's own refusal), a pick that
     * is not a solid, and a **mesh body**, whose faces are emergent rather than named (OP-9's sink rule —
     * the sentence [Section3] already writes for each kind, plus the route that does work). Everything about
     * *where* the drawing and the body are is the node's business and comes back as the reason it is invalid,
     * so it heals when either moves (OP-3): a face standing edge-on to the drawing, a face that is not a
     * plane, and a face the body no longer has.
     */
    @Suppress("UNCHECKED_CAST")
    fun projectOntoFace(
        view: Element,
        solid: Element,
        signs: List<Int> = emptyList(),
    ): Element? {
        if (!view.isCurve) {
            note = "Project onto a face: ${nameOf(view)} is ${kindWord(view)}, not a curve — draw what you want thrown at the body, then click the body"
            return null
        }
        if (view.kind == ElementKind.LINE || view.kind == ElementKind.RAY) {
            note = "Project onto a face: ${nameOf(view)} runs on for ever, so it states no length of run to throw — project a bounded curve"
            return null
        }
        if (solid.kind != ElementKind.SOLID) {
            note = "Project onto a face: ${nameOf(solid)} is ${kindWord(solid)}, not a solid — click the body whose face the curve is to land on"
            return null
        }
        val ev = Evaluator()
        val feature =
            (ev.valueOf(solid.ref) as? SolidValue)?.solid?.feature ?: run {
                note = "Project onto a face: ${nameOf(solid)} has no value right now, so it shows no face to project onto"
                return null
            }
        val planeRef = planeOfSpace(view.space)
        val from =
            (ev.valueOf(planeRef) as? PlaneValue)?.plane ?: run {
                note = "Project onto a face: ${view.space} has no value right now, so there is no direction to project along"
                return null
            }
        val pieces =
            cx.drawnPieces(view.ref, ev) ?: run {
                note = "Project onto a face: ${nameOf(view)} has no value right now, so there is nothing to project"
                return null
            }
        val chosen =
            signs.getOrNull(0) ?: run {
                val (index, why) = Project3.landingFace(feature, pieces, from)
                index ?: run {
                    note =
                        "Project onto a face: ${nameOf(solid)} — $why. Put a working plane where the body is and " +
                        "take the curve there (Intersection curve), or build what you want beside it"
                    return null
                }
            }
        val curve =
            add(
                cx.projectedOntoFace(view.ref, planeRef, solid.ref as SolidRef, chosen),
                ElementKind.SPACE_CURVE,
                Styles.SPACE_CURVE,
            )
        curve.space = view.space
        registerSigns(curve, listOf(chosen))
        val patch = Section3.faces(feature).first?.getOrNull(chosen)
        val made = patch?.let { Project3.projectedOnto(pieces, from, it).first }
        val landing =
            patch?.let { if (Project3.whollyOnFace(pieces, from, it)) "wholly on the face" else "and part of it runs off the face, landing in the face's plane" }
        note =
            "${nameOf(curve)}: ${nameOf(view)} thrown onto ${patch?.name?.label ?: "a face"} of ${nameOf(solid)}" +
            (made?.let { " — ${it.exactnessWord}, $landing" } ?: "") +
            " — move either and it follows"
        return curve
    }

    /** How far [near] (in the plane's own coordinates) stands from [curve]'s projection there. */
    private fun nearestOnCurve(
        curve: IntersectionCurve,
        plane: Plane3,
        near: Vec2,
    ): Double =
        Curves3.projectedOnto(curve.path, plane).minOfOrNull { HitTest.distanceToPiece(near, it) } ?: Double.MAX_VALUE

    // ---- the sphere as a locus: distance carried in space (OP-28) ----

    /**
     * A **sphere locus** of [radius] about the point [centre] (OP-28) — *"every point 40 from that corner"*,
     * as a thing the drawing holds and constructions intersect.
     *
     * [centre] is read through [pointInSpace], the one seam every point's world position flows through, so a
     * plan point, a height point, a section corner, a curve's key point and a rider on a coil are all equally
     * a centre — and each of them is **shared by node**, so dragging the corner drags the locus and everything
     * built on it. That is the whole of the no-solver stance here: *"40 from that corner"* is an input, not an
     * assertion about a result.
     *
     * Nothing about the radius is refused here. A radius of zero or less is a **value**, so it is the node's
     * business and comes back as the reason it is invalid, healing the moment the number changes (OP-3).
     */
    fun sphereLocus(
        centre: Element,
        radius: ScalarRef,
    ): Element? {
        val c = pointInSpaceOr(centre, "Sphere locus") ?: return null
        val el = add(cx.sphere(c, radius), ElementKind.SPHERE_LOCUS, Styles.SPHERE_LOCUS)
        el.handle = SphereLocusHandle(planeOfSpace(activeSpace.name), c, radius.node)
        note = "${nameOf(el)}: every point at that distance from ${nameOf(centre)} — move either and it follows"
        return el
    }

    /**
     * A **sphere locus through [surface]**, centred on [centre] (OP-28) — the spelling that takes its distance
     * from the drawing instead of from the keyboard.
     *
     * The pair mirrors *Circle (centre, radius)* / *(centre, point)* and the ball's own two rows, one dimension
     * up, and the difference is one node: here the radius **is** `|surface − centre|`, so *"as far as that
     * corner"* is a shared input rather than a number that has to be retyped when the corner moves.
     */
    fun sphereLocusThrough(
        centre: Element,
        surface: Element,
    ): Element? {
        val c = pointInSpaceOr(centre, "Sphere locus") ?: return null
        val s = pointInSpaceOr(surface, "Sphere locus") ?: return null
        val el = add(cx.sphereThrough(c, s), ElementKind.SPHERE_LOCUS, Styles.SPHERE_LOCUS)
        note =
            "${nameOf(el)}: every point as far from ${nameOf(centre)} as ${nameOf(surface)} is — " +
            "move either and it follows"
        return el
    }

    /**
     * The **circle where two sphere loci meet** (OP-28) — an exact circle in space, and an ordinary
     * [ElementKind.SPACE_CURVE] with everything that implies: it draws in both views, it is picked, it sweeps,
     * it carries a station and a rider, and a third sphere meets it in the trilateration pair.
     *
     * No branch is scored, because there is none to score: two spheres meet in one circle or in none. Every
     * way of not meeting is the node's own reason and heals (OP-3).
     */
    fun sphereCircle(
        a: Element,
        b: Element,
    ): Element? {
        val s1 = sphereRefOf(a, "Circle of two sphere loci") ?: return null
        val s2 = sphereRefOf(b, "Circle of two sphere loci") ?: return null
        if (a === b) {
            note = "Circle of two sphere loci: ${nameOf(a)} cannot meet itself — click two different loci"
            return null
        }
        val el = add(cx.sphereCircle(s1, s2), ElementKind.SPACE_CURVE, Styles.SPACE_CURVE)
        note = "${nameOf(el)}: the circle where ${nameOf(a)} and ${nameOf(b)} meet — move either and it follows"
        return el
    }

    /**
     * The **point at three stated distances** (OP-28) — the trilateration pair, with the branch scored once
     * from the click and stored for ever after.
     *
     * **The choice is scored exactly once and never re-scored** (OP-1/OP-18). Where the click landed decides
     * which of the two solutions this point is, in whichever view was driving; from then on the branch is a
     * *sign* in the step, taken verbatim on replay. It means *which side of the plane through the three
     * centres* — see [constructit.geom.Spheres3.trilaterate] — so it goes on meaning the same thing however
     * far the drawing drifts, which is exactly what re-scoring by nearness could not promise.
     *
     * What comes out is an ordinary point in space: it can be a curve's point, a sweep's anchor, an apex, a
     * coil's axis point, or the centre of another locus. Nothing about it says how it was made.
     */
    fun trilateratePoint(
        a: Element,
        b: Element,
        c: Element,
        near: Vec2,
        view: PlaneProjection? = null,
        sign: Int? = null,
    ): Element? {
        val s1 = sphereRefOf(a, "Point from three sphere loci") ?: return null
        val s2 = sphereRefOf(b, "Point from three sphere loci") ?: return null
        val s3 = sphereRefOf(c, "Point from three sphere loci") ?: return null
        if (a === b || b === c || a === c) {
            note = "Point from three sphere loci: click three different loci — one of them was picked twice"
            return null
        }
        val set = cx.trilaterate(s1, s2, s3)
        val ev = Evaluator()
        val chosen = sign ?: scoredBranch(cx.solutionPoints3(set, ev), near, view, ev)
        val el = add(cx.selectPoint3(set, chosen, trilaterationEmptyReason(a, b, c)), ElementKind.DERIVED_POINT, Styles.DERIVED_POINT)
        el.inSpace = true
        registerSigns(el, listOf(chosen))
        note =
            "${nameOf(el)}: the point at all three distances, on the " +
            (if (chosen >= 0) "positive" else "negative") +
            " side of the plane through the three centres — drag any centre and it follows"
        return el
    }

    /**
     * The **point where a run crosses a sphere locus** (OP-28) — *"where does this route pass 40 mm from that
     * corner"*, with the crossing chosen once from the click and stored as an index.
     *
     * [run] is read through [spaceCurveRef], so the route may be a curve in space **or a drawing lifted into
     * the run it already is** — which is what makes *"where the footprint's own outline passes 40 from that
     * corner"* one gesture rather than two.
     *
     * The crossings are ordered by **arc length along the run** and the stored index is taken verbatim on
     * replay; a crossing the geometry no longer has is invalid with a reason and heals (OP-3), which is the
     * identical answer a vanished intersection curve gives.
     */
    fun sphereOnRun(
        sphere: Element,
        run: Element,
        near: Vec2,
        view: PlaneProjection? = null,
        index: Int? = null,
    ): Element? {
        val s = sphereRefOf(sphere, "Point where a run meets a sphere locus") ?: return null
        val path = spaceCurveRef(run, "Point where a run meets a sphere locus") ?: return null
        val set = cx.sphereMeetsRun(s, path)
        val ev = Evaluator()
        val points = cx.solutionPoints3(set, ev)
        val chosen =
            index ?: run {
                if (points.isEmpty()) {
                    note =
                        "Point where a run meets a sphere locus: ${nameOf(run)} does not reach ${nameOf(sphere)}, " +
                        "so it crosses it nowhere — change the radius, or pick a run that passes through it"
                    return null
                }
                val plane = activePlane3(ev)
                points.indices.minByOrNull { i -> HitTest.distanceToSpacePoint(points[i], near, view, plane) ?: Double.MAX_VALUE } ?: 0
            }
        val el = add(cx.selectPoint3At(set, chosen), ElementKind.DERIVED_POINT, Styles.DERIVED_POINT)
        el.inSpace = true
        registerSigns(el, listOf(chosen))
        note =
            "${nameOf(el)}: where ${nameOf(run)} crosses ${nameOf(sphere)}" +
            (if (points.size > 1) " (crossing ${chosen + 1} of ${points.size}, counted along the run)" else "") +
            liftNote(run)
        return el
    }

    /**
     * Which branch of an ordered pair in space the click at [near] meant — scored **once**, here, and stored
     * as a sign by the caller.
     *
     * Measured by the very distance the pick uses ([HitTest.distanceToSpacePoint]), so the branch the user
     * gets is the one they were pointing at in whichever view is driving. An empty set scores `+1`: there is
     * nothing to point at, the node will say so by name, and the branch is what the drawing rides when the
     * geometry comes back (OP-3's healing, which needs a recorded choice to heal *to*).
     */
    private fun scoredBranch(
        points: List<Vec3>,
        near: Vec2,
        view: PlaneProjection?,
        ev: Evaluator,
    ): Int {
        if (points.size < 2) return 1
        val plane = activePlane3(ev)
        val first = HitTest.distanceToSpacePoint(points.first(), near, view, plane) ?: return 1
        val last = HitTest.distanceToSpacePoint(points.last(), near, view, plane) ?: return 1
        return if (last < first) -1 else 1
    }

    /** What "no such point" is called for three loci that do not meet — [Construction.selectPoint3]'s sentence. */
    private fun trilaterationEmptyReason(
        a: Element,
        b: Element,
        c: Element,
    ): String =
        "no point in space is at all three of those distances at once — ${nameOf(a)}, ${nameOf(b)} and " +
            "${nameOf(c)} either do not overlap, or their centres now lie on one line (in which case they meet " +
            "in a whole circle rather than at a pair of points)"

    /** [el] as a sphere locus, or null with the reason it is not one — the `SPHERE` slot's own coercion. */
    @Suppress("UNCHECKED_CAST")
    private fun sphereRefOf(
        el: Element,
        what: String,
    ): Sphere3Ref? {
        if (el.kind == ElementKind.SPHERE_LOCUS) return el.ref as Sphere3Ref
        note =
            "$what: ${nameOf(el)} is ${kindWord(el)}, not a sphere locus — build one with " +
            "*Sphere locus (centre, radius)* and click that"
        return null
    }

    /** [el] as the point in space a locus is centred on, or null with the reason it is not a point. */
    private fun pointInSpaceOr(
        el: Element,
        what: String,
    ): Point3Ref? {
        if (!el.isPoint) {
            note = "$what: ${nameOf(el)} is ${kindWord(el)}, not a point — click the point the distance is measured from"
            return null
        }
        return pointInSpace(el)
    }

    // ---- the sweep: a profile carried along a curve in space (OP-26, step 2) ----

    /**
     * What a **variable section**'s `law=` stated on a tube or a sweep step (OP-26, session 77): the text as
     * written, its AST, and what its names resolved to — **by identity**, which is what makes a rename a
     * re-stamp rather than an orphaned reference.
     *
     * The exact twin of [FuncCurveBinding], and it exists for the same two reasons: the **text is the
     * record**, so the writer restates this step's own text; and a rename is a re-stamp, so a mention of a
     * scalar (or of a point's coordinate) living inside this string is rewritten in place
     * ([restampExpressions]).
     */
    class SweepLawBinding internal constructor(
        val element: Element,
        val source: String,
        internal val ast: Expr,
        internal val names: List<String>,
        internal val refs: List<ExprRef>,
        val param: String,
    ) {
        /** The law under the **current** names of what it reads — what a save writes. */
        var text: String = source

        /** Every scalar row this law reads — what the delete cascade follows. */
        internal fun scalarRefs(): List<ScalarEntry> = refs.mapNotNull { it.entry }

        /** Every point whose coordinate this law reads (the session-76 rule, one feature on). */
        internal fun pointRefs(): List<Element> = refs.mapNotNull { it.point }
    }

    private val sweepLaws = HashMap<Step, SweepLawBinding>()
    private var pendingSweepLaw: SweepLawBinding? = null

    /**
     * What a **function-family section**'s `laws=` stated on a sweep step (OP-26, session 79): one entry per
     * driven name, each with its law text and what that text's names resolved to — **by identity**, which is
     * what makes a rename a re-stamp rather than an orphaned reference.
     *
     * [SweepLawBinding] **keyed by the name it drives**, and it exists for that class's own two reasons plus
     * one that only a keyed list has: the **text is the record** and this is its sole authority (the writer
     * restates this step's whole `laws=` from here, so re-stating a law is an edit of the step rather than a
     * second feature); a rename is a re-stamp; and the driven name on the **left** of each `=` is a reference
     * like any other, so a rename rewrites *that* too — the two-sided re-stamp ([restampSweepLaws]).
     *
     * The pairs are written `name = expr`, semicolon-separated, with the **expression verbatim** as the user
     * typed it and the pair structure this class's own ([stated]). The alternative — keeping the whole
     * argument character for character, whitespace and all — was rejected because the *rows* are the entry
     * medium ([sectionFamilyRows]): the user types one formula per driven name and never the joining
     * punctuation, so there is no user text there to preserve, and a normalized join is what makes
     * `save → load → save` byte-equal after a re-stated row as well as after a load.
     */
    class SweepFamilyBinding internal constructor(
        val element: Element,
        val entries: List<Entry>,
    ) {
        /** One `name = expr` pair — the driven name, the law, and what the law reads. */
        class Entry internal constructor(
            /** The driven name as the step stated it. */
            val stated: String,
            val source: String,
            /**
             * The scalar row this law drives, or null for the run's own [FAMILY_TWIST_NAME] and for a name the
             * drawing carries nothing for (the hand-edited file — see [SectionFamily.unresolved]).
             */
            internal val target: ScalarEntry?,
            /** The law's parsed form, or null where one of the names it reads resolves to nothing. */
            internal val ast: Expr?,
            internal val names: List<String>,
            internal val refs: List<ExprRef>,
            val param: String,
        ) {
            /** The driven name **now** — re-stamped when the scalar it drives is renamed (the left side). */
            var driven: String = stated

            /** The law under the **current** names of what it reads — what a save writes (the right side). */
            var text: String = source

            /** Whether this entry is the run's own turn rather than a scalar of the drawing. */
            val isTwist: Boolean get() = target == null && driven == FAMILY_TWIST_NAME

            /** Whether the drawing carries nothing of this name at all — the load's soft failure. */
            val unresolved: Boolean get() = target == null && !isTwist
        }

        /** The whole `laws=` argument as it stands — what the writer puts back on the step. */
        val stated: String get() = entries.joinToString("; ") { "${it.driven} = ${it.text}" }

        /** The law stated for [name] under its current spelling, or null. */
        fun textOf(name: String): String? = entries.firstOrNull { it.driven == name }?.text

        /** Every scalar row this family reads **or drives** — what the delete cascade follows. */
        internal fun scalarRefs(): List<ScalarEntry> =
            entries.flatMap { e -> e.refs.mapNotNull { it.entry } + listOfNotNull(e.target) }

        /** Every point whose coordinate one of these laws reads (the session-76 rule, one feature on). */
        internal fun pointRefs(): List<Element> = entries.flatMap { e -> e.refs.mapNotNull { it.point } }
    }

    private val sweepFamilies = HashMap<Step, SweepFamilyBinding>()
    private var pendingSweepFamily: SweepFamilyBinding? = null

    /** The family [step] recorded, or null — how the writer restates that step's own `laws=`. */
    internal fun sweepFamilyBinding(step: Step): SweepFamilyBinding? = sweepFamilies[step]

    /** The family laws [el]'s section is read under, or null — what the panel rows show. */
    fun sweepFamilyOf(el: Element): SweepFamilyBinding? = sweepFamilies.values.firstOrNull { it.element === el }

    /**
     * The **free named scalars [profile]'s own drawing transitively reads** — what a family law may drive,
     * and what the panel offers a row for (the design pass's F3).
     *
     * *Free*, because a law is a substitution and a bound parameter has no value of its own to substitute:
     * `thickness = 0.12 * chord` follows `chord`'s law by ordinary recompute, which is sharing-is-equality
     * one level up. *Named*, because a law is written and a nameless freedom cannot be. *Transitively read*,
     * because that is what "the section is built from this" means, and it is asked of the region's own cone
     * so a scalar the drawing merely happens to carry is not offered.
     *
     * In the panel's own order, which is the drawing's order, so the rows do not shuffle.
     */
    fun sectionLawables(
        profile: Element,
        /**
         * Where the cone is read from — [profile]'s own node by default, and the **region** node where the
         * caller has one (`sweepAlongCurve`, which has already traced the boundary).
         *
         * The two differ only where the pick is one *piece* of a traced boundary: the piece's own cone is
         * then a subset of the whole outline's, so the panel offers a little less than the sweep would take
         * and never a name the sweep would refuse. Which is the safe direction, and the reason the panel does
         * not ask for the region: building one mints a node, and this is read on every repaint.
         */
        from: Node = profile.ref.node,
    ): List<ScalarEntry> {
        val cone = HashSet<String>()
        // the **element's own** node, not the region built from it: a region node adds no scalar the
        // boundary has not got, and asking for one here would mint a node on every repaint (the panel reads
        // this on every frame — see [constructit.editor.Editor.sectionFamilyRows])
        coneOf(from, cone)
        return scalars.filter { it.editable && !isBound(it) && it.ref.node.id in cone }
    }

    /** Every node id at or upstream of [n] — the cone a family reads, gathered once. */
    private fun coneOf(
        n: Node,
        into: MutableSet<String>,
    ) {
        if (!into.add(n.id)) return
        for (i in n.inputs) coneOf(i, into)
    }

    /**
     * The section's transitive **free** sources and parameters, as refs — [SectionFamily.watched].
     *
     * Every [SourceNode] and every unbound [ParameterNode] in the cone of the section (and of the point it
     * rides on, which is read under the same substitutions), which is the set whose motion can change what
     * a station's ring is. They become ordinary inputs of the sweep node, so the memo is sound by
     * construction rather than by an argument about what intermediate nodes do with their value objects.
     */
    private fun watchedFreedoms(vararg roots: Node?): List<Ref<*>> {
        val seen = HashSet<String>()
        val out = ArrayList<Node>()

        fun walk(n: Node) {
            if (!seen.add(n.id)) return
            val free = (n is SourceNode && n.boundTo == null) || (n is ParameterNode && n.boundTo == null)
            if (free) out.add(n)
            for (i in n.inputs) walk(i)
        }
        for (r in roots) r?.let { walk(it) }
        return out.map { Ref<Value>(it) }
    }

    /** The name the **run's own turn** goes by on the left of a family law (the design pass's F12). */
    val familyTwistName: String get() = FAMILY_TWIST_NAME

    /**
     * [text] read as a semicolon-separated list of **family laws** (OP-26, session 79), or null with [note]
     * set by name.
     *
     * Every structural refusal lives here and fires before anything is built — the design pass's F3 list,
     * one sentence each and each naming its cure. What is *not* refused here is anything about the values:
     * a law whose section turns inside out, whose piece count changes or whose area vanishes part-way along
     * the run is the node's own business and comes back as the named invalidity that heals (OP-3).
     */
    private fun familyLaws(
        text: String,
        what: String,
        profile: Element,
        from: Node = profile.ref.node,
    ): List<SweepFamilyBinding.Entry>? {
        val lawables = sectionLawables(profile, from)
        val stated = ArrayList<String>()
        val out = ArrayList<SweepFamilyBinding.Entry>()
        for (piece in text.split(';')) {
            if (piece.isBlank()) continue
            val eq = piece.indexOf('=')
            if (eq < 0) {
                note =
                    "$what: '${piece.trim()}' is no law — write the name of what varies, an '=', and a formula " +
                    "over $sweepLawParam ('chord = 200mm * (1 - 0.6*$sweepLawParam)'), and separate several with ';'"
                return null
            }
            val name = piece.substring(0, eq).trim()
            val body = piece.substring(eq + 1).trim()
            if (name.isEmpty()) {
                note = "$what: '${piece.trim()}' names nothing to vary — write 'name = formula'"
                return null
            }
            if (body.isEmpty()) {
                note = "$what: '$name' has no formula — write '$name = ' and one formula over $sweepLawParam, or leave the row empty"
                return null
            }
            if (name in stated) {
                note =
                    "$what: '$name' is given two laws in one step, and a station reads one value for it — " +
                    "keep the law you mean and delete the other"
                return null
            }
            stated.add(name)
            val entry = familyLaw(name, body, what, profile, lawables, stated) ?: return null
            out.add(entry)
        }
        return out
    }

    /** One `name = expr` pair resolved, or null with [note] set — see [familyLaws] for what is refused here. */
    private fun familyLaw(
        name: String,
        body: String,
        what: String,
        profile: Element,
        lawables: List<ScalarEntry>,
        stated: List<String>,
    ): SweepFamilyBinding.Entry? {
        // **`roll` and `twist` are the run's own names** and are reserved on the left of a law: the step
        // already carries both as parameters, so a drawing scalar of that name cannot be told from them here.
        if (name == FAMILY_ROLL_NAME) {
            note =
                "$what: '$FAMILY_ROLL_NAME' is where the section starts, not how it turns along the run — it is one " +
                "angle for the whole body, so state the variation as '$FAMILY_TWIST_NAME = …' and leave roll a parameter"
            return null
        }
        val collides = scalars.firstOrNull { it.name == name && it in lawables }
        if (name == FAMILY_TWIST_NAME && collides != null) {
            note =
                "$what: the section reads a parameter called '$FAMILY_TWIST_NAME', and '$FAMILY_TWIST_NAME' is also the run's " +
                "own turn — rename the parameter (the panel's rows rename in place) so the law says which one it drives"
            return null
        }
        // …a **coordinate** is read and never written (the session-76 rule, one feature on)
        if (name != FAMILY_TWIST_NAME && name.contains('.')) {
            note =
                "$what: '$name' is a point's coordinate, and a coordinate is read rather than driven — a family " +
                "law drives a **parameter** the section is built from, so state the law on the parameter that " +
                "coordinate is placed by"
            return null
        }
        val target =
            if (name == FAMILY_TWIST_NAME) {
                null
            } else {
                val row = scalars.firstOrNull { it.name == name }
                if (row != null && isBound(row)) {
                    val master = boundEntry(row)?.name ?: expressionOf(row) ?: "another value"
                    note =
                        "$what: '$name' follows $master, so it has no value of its own for a station to read — " +
                        "state the law on $master and '$name' reads it at every station"
                    return null
                }
                if (row != null && row !in lawables) {
                    note =
                        "$what: ${nameOf(profile)} is not built from '$name' — a family law drives a parameter " +
                        "the section reads, and this one reads ${lawableWord(lawables)}"
                    return null
                }
                row
            }
        // …a law may not read a name **this same step drives**: a station's values come from the drawing, and
        // reading one law's output in another would make the family a little solver of its own (F3)
        val ast =
            try {
                ExprParser.parse(body)
            } catch (err: ExprError) {
                note = "$what: can't read '$name' from '$body': ${err.message}"
                return null
            }
        val names = ArrayList<String>()
        val refs = ArrayList<ExprRef>()
        var unresolved = false
        for (n in ast.refNames()) {
            if (n == sweepLawParam) continue
            if (n in stated || n == FAMILY_TWIST_NAME) {
                note =
                    "$what: the law for '$name' reads '$n', which this same step drives — a station's values " +
                    "come from the drawing, so bind '$n' in the drawing (wire it, or give it a formula) and it " +
                    "follows '$name' at every station"
                return null
            }
            val resolved = resolveExprName(n)
            if (resolved == null) {
                if (n in EXPR_CONSTANTS) continue
                note = "$what: ${unknownName(n)}"
                return null
            }
            names.add(n)
            refs.add(resolved)
        }
        if (target == null && name != FAMILY_TWIST_NAME) unresolved = true
        return SweepFamilyBinding.Entry(
            name,
            body,
            target,
            if (unresolved) null else ast,
            names,
            refs,
            sweepLawParam,
        )
    }

    /** How a refusal lists what a section *is* built from — the cure half of the unread-name sentence. */
    private fun lawableWord(lawables: List<ScalarEntry>): String =
        if (lawables.isEmpty()) {
            "no named parameter at all (give the dimension that is to vary a parameter in the panel first)"
        } else {
            lawables.joinToString(", ") { "'${it.name}'" }
        }

    /**
     * The **family** [laws] state on the section [profile], as the DSL takes it — or null with [note] set.
     *
     * A name the drawing carries **nothing** for is kept rather than refused (the hand-edited file's case,
     * [SectionFamily.unresolved]): the law travels, the body is invalid with a reason naming the name, and
     * the load says which element it was ([noteLoad]).
     */
    private fun familyOf(
        entries: List<SweepFamilyBinding.Entry>,
        region: RegionRef,
        anchor: PointRef?,
    ): SectionFamily {
        val driven =
            entries.filter { !it.isTwist && !it.unresolved }.map { e ->
                FamilyLaw(
                    e.driven,
                    e.target!!.ref.node,
                    ExprLaw(e.ast!!, e.names, e.refs.map { ScalarRef(it.node) }, e.text, e.param),
                )
            }
        val twist =
            entries.firstOrNull { it.isTwist }?.let { e ->
                ExprLaw(e.ast!!, e.names, e.refs.map { ScalarRef(it.node) }, e.text, e.param)
            }
        return SectionFamily(
            region.node,
            anchor?.node,
            driven,
            twist,
            watchedFreedoms(region.node, anchor?.node),
            entries.filter { it.unresolved }.map { it.driven },
        )
    }

    /** The binding [entries] leave on the body they shape, for the writer and the re-stamp to find. */
    private fun rememberFamily(
        solid: Element,
        entries: List<SweepFamilyBinding.Entry>,
    ) {
        pendingSweepFamily = SweepFamilyBinding(solid, entries)
    }

    /**
     * The whole journal with [el]'s **family laws restated** as [text] — or null with [note] set, by name.
     *
     * [sweepLawRestated] one tier up, and every word of its reasoning applies unchanged: a law change is an
     * **edit** of the step that already declares this body, so the solid keeps its identity and its name,
     * nothing downstream is rewired, every scored choice stays where it is, and the whole thing is one undo
     * step. An empty [text] takes every law away, which is the section as it is drawn again.
     */
    fun sweepFamilyRestated(
        el: Element,
        text: String?,
    ): String? {
        val step = creatingStep(el)
        val toolId = if (step?.kind == "tool") (step.args.firstOrNull() as? Arg.Text)?.s else null
        val tool = toolId?.let { toolDef(it) }
        if (step == null || tool == null || !tool.carriesLaws) {
            note = familyRefusal(el, toolId)
            return null
        }
        val profile = sweptSectionOf(step)
        if (profile == null) {
            note =
                "Section laws: ${nameOf(el)} carries no drawn section to read per station — a family reads one " +
                "2D drawing once for every station of the run, so sweep an area with *Sweep (profile along a curve)*"
            return null
        }
        val wanted = text?.trim()?.ifEmpty { null }
        val entries = if (wanted == null) null else familyLaws(wanted, "Section laws", profile) ?: return null
        val before = sweepFamilies[step]
        if (entries == null || entries.isEmpty()) {
            sweepFamilies.remove(step)
        } else {
            sweepFamilies[step] = SweepFamilyBinding(el, entries)
        }
        return try {
            DocumentFormat.save(this)
        } finally {
            sweepFamilies.remove(step)
            before?.let { sweepFamilies[step] = it }
        }
    }

    /**
     * The **drawing a family reads** for [el]: the area itself where [el] *is* a section, or the section the
     * swept body [el] was built from — and null for anything that is neither.
     *
     * One question with two answers because the panel's rows have two readings, exactly as the single
     * *Section law* field has: with a swept body selected the rows **are** its laws, and with a section
     * selected they are what the next sweep of it will carry.
     */
    fun familySectionOf(el: Element): Element? {
        if (el.kind == ElementKind.SOLID) {
            val step = creatingStep(el) ?: return null
            val toolId = if (step.kind == "tool") (step.args.firstOrNull() as? Arg.Text)?.s else null
            val tool = toolId?.let { toolDef(it) } ?: return null
            if (!tool.carriesLaws) return null
            return sweptSectionOf(step)
        }
        return if (couldBeSection(el)) el else null
    }

    /**
     * Whether [el] could be **the section a sweep carries** — anything drawn that is not a point and not a
     * curve in space, which is exactly what the tool's own area slot coerces (see [regionOf]).
     *
     * One rule for both readings of the panel's rows, and deliberately generous: a *piece* of a traced
     * boundary offers the free scalars that piece is built from, which is a subset of the whole outline's and
     * therefore never a name a law may not drive.
     */
    private fun couldBeSection(el: Element): Boolean = !el.isPoint && el.kind != ElementKind.SPACE_CURVE

    /**
     * The names a family law may drive on [el] — the law-able scalars of the drawing it reads
     * ([sectionLawables]), with the run's own [FAMILY_TWIST_NAME] last.
     *
     * Last because it is the run's and not the drawing's: every other row is a dimension of the section, and
     * the twist is how that section turns while it travels.
     */
    fun familyLawNames(el: Element): List<String> {
        val section = familySectionOf(el) ?: return emptyList()
        // `distinct`, because a drawing may carry a scalar of the reserved name itself: one row then stands
        // for both readings and applying it refuses by name with the cure (rename the row), which is better
        // than two rows nobody can tell apart
        return (sectionLawables(section).map { it.name } + FAMILY_TWIST_NAME).distinct()
    }

    /** The area [step] swept, or null — the drawing a family reads, found from the step's own picks. */
    internal fun sweptSectionOf(step: Step): Element? =
        referencedElements(step).lastOrNull { couldBeSection(it) }

    /** Why [el] carries no family laws over its run, with the cure — see [sweepFamilyRestated]. */
    private fun familyRefusal(
        el: Element,
        toolId: String?,
    ): String =
        when (toolId) {
            // **The tube's section is the run's own statement**, not a drawing: there is no 2D DAG to read
            // per station, and the one dimension it has is exactly what `r(t)` already states (F6).
            Tools.TUBE ->
                "Section laws: ${nameOf(el)} is a tube, whose section is a circle the run itself states — " +
                    "there is no drawing to read per station, and the one size it has is *Section law* " +
                    "(`r($sweepLawParam) = 5mm * (1 - $sweepLawParam/2)`). To vary an outline, draw the section " +
                    "and sweep it with *Sweep (profile along a curve)*"
            // …the swept cut, in session 77's own sentence pluralized: its reach is derived from the solid it
            // cuts, and sections that differ per station would move that reach station by station.
            Tools.CUT_ALONG_CURVE, Tools.CUT_ALONG_CURVE_FLAT, Tools.SPLIT_ALONG_CURVE, Tools.SPLIT_ALONG_CURVE_FLAT ->
                "Section laws: ${nameOf(el)} is cut by a chain carried along a route, and a swept cut states no " +
                    "sizes of its own — how far its sections reach is derived from the solid it cuts, so sections " +
                    "that differed along the run would move that reach station by station. Sweep the varying " +
                    "section as a solid with *Sweep* (its own scalars may be formulas over the run) and subtract " +
                    "it with *Subtract*"
            else ->
                "Section laws: ${nameOf(el)} is ${kindWord(el)}, and laws over the run belong to a swept body " +
                    "whose section is a drawing — build one with *Sweep (profile along a curve)* and state the " +
                    "laws on that"
        }

    /** The law [step] recorded, or null — how the writer restates that step's own text. */
    internal fun sweepLawBinding(step: Step): SweepLawBinding? = sweepLaws[step]

    /** The law [el]'s section is carried at, or null — what the panel shows and a refusal quotes. */
    fun sweepLawOf(el: Element): SweepLawBinding? = sweepLaws.values.firstOrNull { it.element === el }

    /** The station parameter every size law is read in — `t`, 0 to 1 along the whole run. */
    val sweepLawParam: String get() = "t"

    /** A parsed law plus what its names resolved to — [sizeLaw]'s two halves, kept together. */
    private class LawParse(val input: ExprLaw, val refs: List<ExprRef>)

    /**
     * [text] read as a **size law over the station** (OP-26, session 77), or null with [note] set by name.
     *
     * Everything structural is refused here and before anything is built — a text that is not an expression
     * (with the character position), a name nothing in the drawing carries (with the cure, through
     * [unknownName]) — while everything about the *values* is the node's business and comes back as the named
     * invalidity that heals (OP-3): a radius law that is not a length, a scale that is not a plain number, a
     * size that goes non-positive part-way along the run.
     */
    private fun sizeLaw(
        text: String,
        what: String,
    ): LawParse? {
        val param = sweepLawParam
        val ast =
            try {
                ExprParser.parse(text)
            } catch (err: ExprError) {
                note = "$what: can't read the section's size from '${text.trim()}': ${err.message}"
                return null
            }
        val names = ArrayList<String>()
        val refs = ArrayList<ExprRef>()
        for (n in ast.refNames()) {
            // the station parameter is a **binder** and wins over everything, which is what makes `1 - t/2`
            // mean what it says even in a drawing that carries a scalar called `t` (the function curves' own
            // rule — see [funcCurveParam] and DESIGN.md's note on a rename that would capture one)
            if (n == param) continue
            val target = resolveExprName(n)
            if (target == null) {
                if (n in EXPR_CONSTANTS) continue
                note = "$what: ${unknownName(n)}"
                return null
            }
            names.add(n)
            refs.add(target)
        }
        return LawParse(ExprLaw(ast, names, refs.map { Ref(it.node) }, text, param), refs)
    }

    /**
     * The whole journal with [el]'s **size law restated** as [text] — or null with [note] set, by name.
     *
     * A law change is an **edit**, not a new feature, and it re-stamps the step that already declares this
     * body: the same reasoning OP-23 applied to a pattern's count and GitHub #7 to a wall's carrier set, and
     * it buys the same three things — the solid keeps its identity (its own step still declares it, so its
     * script name, its style and everything built on it follow the re-tapered body), nothing downstream is
     * rewired, and the whole thing is one undo step because the substrate of undo is the saved script
     * (OP-18). A blank [text] takes the law away, which is the section of one size again.
     *
     * **This document is left exactly as it was**: what comes back is text and the caller decides to adopt it
     * ([constructit.editor.Editor.setSectionLaw]), which is also what makes a failure to re-load harmless.
     */
    fun sweepLawRestated(
        el: Element,
        text: String?,
    ): String? {
        val step = creatingStep(el)
        val toolId = if (step?.kind == "tool") (step.args.firstOrNull() as? Arg.Text)?.s else null
        val tool = toolId?.let { toolDef(it) }
        if (step == null || tool == null || !tool.carriesLaw) {
            note = lawRefusal(el, toolId)
            return null
        }
        val wanted = text?.trim()?.ifEmpty { null }
        // refused **before** anything is rewritten, in the words the gesture would have used
        val parse = if (wanted == null) null else sizeLaw(wanted, "Section law") ?: return null
        // **The binding is the one authority for the law's text** — the writer reads it there and nowhere
        // else (see [DocumentFormat.restate]'s `tool` branch) — so restating the law is putting a different
        // binding on the very same step, and taking it away is removing that binding. Nothing about the
        // journal's shape, its order or what any step declares moves at all, which is what makes this the
        // cheapest possible edit and what keeps every scored choice this step carries exactly where it is.
        val before = sweepLaws[step]
        if (parse == null || wanted == null) {
            sweepLaws.remove(step)
        } else {
            sweepLaws[step] = SweepLawBinding(el, wanted, parse.input.ast, parse.input.names, parse.refs, parse.input.param)
        }
        return try {
            DocumentFormat.save(this)
        } finally {
            sweepLaws.remove(step)
            before?.let { sweepLaws[step] = it }
        }
    }

    /** Why [el] carries no size law over its run, with the cure — see [sweepLawRestated]. */
    private fun lawRefusal(
        el: Element,
        toolId: String?,
    ): String =
        when (toolId) {
            // **The swept cut is the recorded cut of this package** (OP-22's extension, step 2 — see
            // DESIGN.md): its section is a *chain*, unbounded in general, and the reach the whole operator is
            // judged and clipped by is **derived from the solid's own extent** rather than stated. A section
            // that scaled would make that derived reach a function of the station, and the reach, the
            // relevant span of the route and the clip box are solved for each other in one fixed-point loop —
            // so the honest answer today is to say so and name the way round it, not to carry half of it.
            Tools.CUT_ALONG_CURVE, Tools.CUT_ALONG_CURVE_FLAT, Tools.SPLIT_ALONG_CURVE, Tools.SPLIT_ALONG_CURVE_FLAT ->
                "Section law: ${nameOf(el)} is cut by a chain carried along a route, and a swept cut states no " +
                    "size of its own — how far its section reaches is derived from the solid it cuts, so a " +
                    "section that changed size along the run would move that reach station by station. Sweep the " +
                    "tapering section as a solid with *Sweep* (its scale may be a formula over the run) and " +
                    "subtract it with *Subtract*"
            else ->
                "Section law: ${nameOf(el)} is ${kindWord(el)}, and a size law over the run belongs to a swept " +
                    "body — build one with *Tube along a curve* or *Sweep (profile along a curve)* and state the " +
                    "law on that"
        }

    /** The binding [law] leaves on the solid it sizes, for the writer and the re-stamp to find. */
    private fun rememberLaw(
        solid: Element,
        text: String,
        parse: LawParse,
    ) {
        pendingSweepLaw = SweepLawBinding(solid, text, parse.input.ast, parse.input.names, parse.refs, parse.input.param)
    }

    /**
     * A **tube** of [radius] along the curve in space [el] (OP-26's step 2) — a cable, a conduit, a
     * handrail, a duct.
     *
     * The everyday half of the sweep, and one gesture: one number and one click. What it builds is an
     * ordinary solid, with everything that implies — it draws in the 3D view, shows a plan footprint that
     * can be clicked, unions and subtracts, exports, hides, is renamed and undone like any other.
     *
     * [roll] turns the section about the run at its start and [twist] is the total turn from one end to the
     * other; both default to nothing, and both are only *visible* on a section that is not round — which is
     * why they are offered here at all rather than only on [sweepAlongCurve]: a closed run whose frame does
     * not come back to itself is closed by stating the twist, and that is as true of a tube as of anything
     * else (see [constructit.geom.Geom3.sweep]).
     */
    @Suppress("UNCHECKED_CAST")
    fun tubeAlongCurve(
        el: Element,
        radius: ScalarRef,
        roll: ScalarRef? = null,
        twist: ScalarRef? = null,
        law: String? = null,
    ): Element? {
        val path = spaceCurveRef(el, "Tube") ?: return null
        // **[law] is `r(t)`, a length over the station** (OP-26, session 77) — a tapered handle, a horn. It
        // *supersedes* the typed radius rather than scaling it, because what it states is the radius itself;
        // absent, this is the tube it always was, down to the node's inputs and the step's own words.
        val parsed = if (law == null) null else sizeLaw(law, "Tube") ?: return null
        val solid =
            add(
                cx.tube(path, planeOfSpace(el.space), radius, noTurn(roll), noTurn(twist), parsed?.input),
                ElementKind.SOLID,
                Styles.SOLID,
            )
        solid.space = el.space
        if (parsed != null && law != null) rememberLaw(solid, law, parsed)
        val what = if (law == null) "a ${lengthWord(radius)} tube" else "a tube of r($sweepLawParam) = ${law.trim()}"
        madeSolid(solid, "$what along ${nameOf(el)}" + liftNote(el))
        return solid
    }

    /**
     * The area [profile] **swept along the curve in space** [el] (OP-26's step 2) — the general form, of
     * which [tubeAlongCurve] is the circular case.
     *
     * **With no pick and a run that pierces the section's own plane, the section is swept from where it is
     * drawn** (the in-place sweep — the user's design, GitHub issue #15 read one step further). The point of it
     * that travels is then *the point the run goes through the drawing at*, and the frame there is the
     * drawing's own plane, so the outline is literally the swept body's section at that place: a foundation
     * drawn in a section through the building, against the wall it sits by, sweeps round the wall's own
     * outline sitting on the ground rather than floating at the height its plane coordinates happen to be.
     * Which crossing is a **choice scored once, from the drawing, and recorded** — the one nearest the
     * section — and never re-scored on a later load (OP-1/OP-18), while *where* that crossing is stays a live
     * value that the body follows (OP-3).
     *
     * Failing all of that the profile is read in the moving frame's own coordinates **with its own origin on
     * the path**, so a section drawn 20 mm off its space's origin runs 20 mm off the route — a construction
     * rather than an argument. That is what a run which crosses nothing gets, and what every drawing written
     * before this reading keeps for ever.
     *
     * **[anchor] is the point of the section that rides the run** (GitHub issue #15), and it is what makes a
     * section drawn *in place* sweepable: a worm thread drawn at the shaft's surface stands 5 mm off the
     * drawing's origin because that is where the part is, and orbiting it 5 mm out from its own coil is not
     * what anybody meant — so the point it rides on is *stated*, as a pick, rather than compensated for by a
     * number this tool works out (DESIGN.md's *"explicit anchors beat compensation"*). Clicking an existing
     * point shares its node, so the body follows it when it is dragged; leaving it out is the older reading
     * exactly, down to the node's inputs (`Construction.sweep`).
     *
     * The anchor must be drawn in **the same space as the profile** — the two are subtracted, and coordinates
     * of two different planes have no difference — which is refused by name here, with the coordinates to
     * place instead. *Space origin* is the other way to say the same thing when a whole space is off-origin;
     * this is the way to say it about one section.
     *
     * The solid is stamped into the **curve's** space, not the active one, for the loft's own reason: that is
     * the space its footprint hint is drawn in and the coordinates a pick of it measures against — and here
     * it is also the space whose normal starts the frame, so the two readings are one statement about which
     * space this run belongs to.
     *
     * Refused **by name** and building nothing for the two structural things — a pick that is not a curve in
     * space, and a profile that bounds no area. Everything geometric (the profile outgrowing a bend, a closed
     * run whose frame will not close, a path with no length) is the node's own business and is reported as
     * the reason it is invalid, so it heals when a number moves back (OP-3).
     */
    fun sweepAlongCurve(
        el: Element,
        profile: Element,
        roll: ScalarRef? = null,
        twist: ScalarRef? = null,
        anchor: PointRef? = null,
        pierce: Int? = null,
        law: String? = null,
        /**
         * **The section read as a family of sections** (OP-26, session 79): semicolon-separated
         * `name = formula` pairs over the run parameter, each driving one named scalar the drawing is built
         * from — `chord = 200mm * (1 - 0.6*t); twist = 15deg * t`.
         *
         * The general tier above [law], and it **composes** with it: a family supplies the outline at each
         * station and the rigid law scales what it supplied. Absent, this is the sweep it always was, down to
         * the node's inputs and the step's own words (OP-18).
         */
        laws: String? = null,
    ): Element? {
        val path = spaceCurveRef(el, "Sweep") ?: return null
        val region =
            regionOf(profile) ?: run {
                note = "Sweep: ${nameOf(profile)} bounds no area, so it is no section to carry along ${nameOf(el)}"
                return null
            }
        val anchorEl = anchor?.let { elementFor(it) }
        // …and a point in **space** is refused before anything else about it is asked: what would be read off
        // it is the plane point at its projection, which is not this point (see [notInThePlane]). The gesture
        // cannot offer one — an optional slot declines a candidate it cannot use, so the click goes to the
        // section instead (`Editor.pickSharedPoint`) — and this is the backstop for every other route in,
        // which is where a `Point3Ref` would otherwise reach a `PointRef` input through an unchecked cast.
        if (anchorEl != null) {
            notInThePlane(
                anchorEl,
                "the point a section rides its run on",
                "place a point in ${spaceLabel(spaceOf(profile))} where ${nameOf(profile)} should ride and pick that — " +
                    "or leave it out, and the area's own origin rides the run",
            )?.let {
                note = "Sweep: $it"
                return null
            }
        }
        if (anchor != null && anchorEl != null && anchorEl.space != profile.space) {
            note =
                "Sweep: ${nameOf(anchorEl)} is drawn in ${spaceLabel(spaceOf(anchorEl))}, and " +
                "${nameOf(profile)} in ${spaceLabel(spaceOf(profile))} — the point the section rides on is a " +
                "point of the section's own plane, so place one there and pick that (or leave it out, and the " +
                "area's own origin rides the run)"
            return null
        }
        // Which crossing of the section's own plane the run is ridden at: **taken verbatim** when the step
        // recorded one, scored once here when this is the gesture (OP-1, OP-18). Only ever asked when no point
        // was picked — a stated anchor is the more explicit statement and supersedes everything.
        //
        // A **negative** recorded index is the statement that the section's own origin rides the run, and it
        // is why the reading is recorded even when there is nothing to choose: a step written before this
        // reading existed carries no index at all, and *that* is what keeps it meaning what it always meant
        // (OP-18 — a stored literal's semantics are frozen). Absence is the old file; a number is a choice.
        // **[law] is `scale(t)`, a plain factor over the station** (OP-26, session 77): the section carried
        // rigidly, read larger or smaller about the very point it rides the run on, and never a re-reading of
        // its own sketch. Refused by name here for everything structural, before anything is built.
        val parsed = if (law == null) null else sizeLaw(law, "Sweep") ?: return null
        // **[laws] is the family** (OP-26, session 79) — the section's own named scalars driven per station,
        // refused by name here for everything structural, before anything is built.
        val familyEntries =
            if (laws == null) null else familyLaws(laws, "Section laws", profile, region.node) ?: return null
        val sectionPlane = if (anchor == null) planeOfSpace(profile.space) else null
        val hits = if (sectionPlane == null) emptyList() else crossingsOf(path, sectionPlane)
        val chosen =
            when {
                sectionPlane == null -> null
                pierce != null -> if (pierce < 0) null else migratedPierce(pierce, path, sectionPlane, profile)
                // …and a step being **replayed** with no recorded reading was written before this reading
                // existed, so it keeps the one it was written with, for ever ([replayingVersion])
                replayingVersion != null -> null
                else -> nearestCrossing(hits, sectionPlane, region)
            }
        val solid =
            add(
                cx.sweep(
                    path,
                    planeOfSpace(el.space),
                    region,
                    noTurn(roll),
                    noTurn(twist),
                    anchor,
                    if (chosen == null) null else sectionPlane,
                    chosen ?: 0,
                    parsed?.input,
                    familyEntries?.takeIf { it.isNotEmpty() }?.let { familyOf(it, region, anchor) },
                ),
                ElementKind.SOLID,
                Styles.SOLID,
            )
        solid.space = el.space
        // The reading is recorded whenever there was one to make — a picked anchor is its own record and is
        // named by `pts=` instead, and a step replayed from a file that recorded none must write none back,
        // or a load followed by a save would put words in an older writer's mouth.
        if (anchor == null && (pierce != null || replayingVersion == null)) registerSigns(solid, listOf(chosen ?: -1))
        if (parsed != null && law != null) rememberLaw(solid, law, parsed)
        if (familyEntries != null && familyEntries.isNotEmpty()) {
            rememberFamily(solid, familyEntries)
            // …and a driven name the drawing carries nothing for is **named by the load** rather than dropped
            // (the hand-edited file's case): the body says why it is invalid, and the notes say which body
            val missing = familyEntries.filter { it.unresolved }
            if (missing.isNotEmpty() && replayingVersion != null) {
                noteLoad(
                    "${nameOf(solid)} states a law for ${missing.joinToString(", ") { "'${it.driven}'" }}, " +
                        "and this drawing carries no value of that name — the body is invalid until one exists",
                )
            }
        }
        madeSolid(
            solid,
            "${nameOf(profile)} swept along ${nameOf(el)}" +
                (familyEntries?.takeIf { it.isNotEmpty() }?.let { es -> ", with ${es.joinToString("; ") { "${it.driven}($sweepLawParam) = ${it.text}" }}" } ?: "") +
                (law?.let { ", scaled by $sweepLawParam -> ${it.trim()}" } ?: "") + liftNote(el) +
                (anchorEl?.let { ", riding on ${nameOf(it)}" } ?: ridingNote(el, profile, sectionPlane, chosen, hits.size)),
        )
        return solid
    }

    /**
     * The recorded crossing index [recorded], read the way the build that **wrote** it meant it (OP-18).
     *
     * Format 2 numbered a run's crossings by a walk that could not see a change of side across a closed run's
     * own seam, so this build's set has one more crossing than that build's — inserted at index **0**, because
     * the seam stands at no arc length at all. Every index a format-2 file recorded therefore names the
     * crossing one place further along, and reading it verbatim would silently ride the neighbour. That is a
     * stored literal changing meaning, so it is a version bump plus a migration
     * ([DocumentFormat.SEAM_ORDERED_VERSION]) rather than an edit to the reader.
     *
     * **The migration is exact, and that is the whole reason it is a migration at all.** The new set is the old
     * set with at most one crossing put in front of it, so what the old build would have shown for *this file
     * at this load* is `all[recorded]` of the seam-blind walk, and the very same crossing is
     * `all[recorded + 1]` of this one whenever the run crosses at its seam
     * ([constructit.geom.Pierce3.crossesAtSeam]) and `all[recorded]` whenever it does not. Nothing is guessed
     * and nothing is re-scored: the shift is read off the geometry the file itself rebuilds, which is the same
     * geometry the old reader would have measured. An index that had already outrun its set stays out of it,
     * and the node goes on refusing with the reason it always gave (OP-3).
     *
     * **Where it cannot arbitrate it says so** rather than assuming: with no value for the run or for the
     * section's plane there is no way to tell whether the numbering has moved for this drawing, so the number
     * is kept exactly as written — what the last writer meant — and the load **names the element**
     * ([loadNotes]), which is the rule the v1 rider migration already follows.
     */
    private fun migratedPierce(
        recorded: Int,
        path: Path3Ref,
        plane: PlaneRef,
        profile: Element,
    ): Int {
        val version = replayingVersion ?: return recorded
        if (version >= DocumentFormat.SEAM_ORDERED_VERSION) return recorded
        val ev = Evaluator()
        val run = (ev.valueOf(path) as? Path3Value)?.path
        val at = (ev.valueOf(plane) as? PlaneValue)?.plane
        if (run == null || at == null) {
            noteLoad(
                "${nameOf(profile)} rides a recorded crossing of its own plane, and the run has no value right " +
                    "now — so this load cannot tell whether that numbering has moved (a closed run's seam counts " +
                    "as a crossing from format ${DocumentFormat.SEAM_ORDERED_VERSION} on). The number is kept as " +
                    "it was written; sweep the section again if it rides the wrong crossing",
            )
            return recorded
        }
        return if (Pierce3.crossesAtSeam(run, at)) recorded + 1 else recorded
    }

    /** Where [path] crosses [plane], or nothing when either has no value right now. */
    private fun crossingsOf(
        path: Path3Ref,
        plane: PlaneRef,
    ): List<Pierce> {
        val ev = Evaluator()
        val run = (ev.valueOf(path) as? Path3Value)?.path ?: return emptyList()
        val at = (ev.valueOf(plane) as? PlaneValue)?.plane ?: return emptyList()
        return Pierce3.crossings(run, at)
    }

    /**
     * Which crossing of [plane] by [path] a section shaped like [region] rides — **the one nearest the
     * drawing**, or null where the run crosses that plane nowhere.
     *
     * Nearness is measured in the plane's own coordinates, from the crossing to the section's outline, because
     * that is the question the user is answering by having drawn the section where they drew it: a foundation
     * hugging one wall of a pillar means *this* wall, and the plan's outline crosses the plane through it once
     * at each wall. Scored **here**, once, at the click — the index is then written into the step and every
     * replay takes it verbatim, since a re-scored nearness moves the body to the other side of the drawing the
     * first time an edit slides the section past the middle (OP-18's own catalogue of that defect).
     */
    private fun nearestCrossing(
        hits: List<Pierce>,
        plane: PlaneRef,
        region: RegionRef,
    ): Int? {
        if (hits.isEmpty()) return null
        val ev = Evaluator()
        val at = (ev.valueOf(plane) as? PlaneValue)?.plane ?: return null
        val outline = ((ev.valueOf(region) as? RegionValue)?.region ?: return null).outer.elements
        return hits.indices.minByOrNull { i ->
            val p = at.toLocal(hits[i].at)
            outline.minOfOrNull { HitTest.distanceToPiece(p, it) } ?: Double.MAX_VALUE
        }
    }

    /**
     * What the status line says about how a picked-nothing sweep rides its run — the choice speaking for
     * itself, and naming the other way of stating it (*refusals speak*, and so do choices made for the user).
     */
    private fun ridingNote(
        el: Element,
        profile: Element,
        sectionPlane: PlaneRef?,
        chosen: Int?,
        count: Int,
    ): String {
        if (sectionPlane == null) return ""
        val where = spaceLabel(spaceOf(profile))
        if (chosen == null) {
            return if (count == 0) {
                ", with ${nameOf(profile)}'s own origin riding the run — ${nameOf(el)} does not cross $where, " +
                    "so there is no point of the section for the run to go through"
            } else {
                ", with ${nameOf(profile)}'s own origin riding the run — sweep it again to ride where " +
                    "${nameOf(el)} pierces $where, or pick the point of the section that is to ride it"
            }
        }
        return ", riding where ${nameOf(el)} pierces $where" +
            (if (count > 1) " (crossing ${chosen + 1} of $count, the one nearest the section)" else "") +
            " — pick a point of the section to ride it elsewhere"
    }

    // ---- the lift: a drawn curve is the run it already is (OP-26, step 1's missing source) ----

    /**
     * Whether [el] is a **drawing that can be lifted** into the run it already describes — a bounded curve of
     * its space, a traced outline, or an area (its boundary).
     *
     * The list is the kinds, not a measurement, so a pick either is one of these or is refused by name and
     * neither answer depends on where anything currently stands. Excluded, and each for its own reason: a
     * **line** or a **ray** runs on for ever and so states no length of run (step 5's refusal, verbatim); a
     * **chain** is unbounded in the same way and is a thing to cut *with*; a **point** is a place; a **solid**
     * is met by a plane instead ([intersectionCurve]).
     */
    fun isLiftable(el: Element): Boolean =
        when (el.kind) {
            ElementKind.SEGMENT, ElementKind.ARC, ElementKind.BEZIER, ElementKind.FUNC_CURVE -> true
            ElementKind.CIRCLE, ElementKind.ELLIPSE, ElementKind.ELLIPTIC_ARC -> true
            ElementKind.OUTLINE, ElementKind.AREA -> true
            else -> false
        }

    /**
     * Whether lifting [el] **closes the run**, read off its kind — a closed boundary by construction (an
     * outline, an area, a whole conic) closes, and a bounded piece does not.
     *
     * Structure, decided when the node is built and never derived from the values (OP-21, [Path3.closed]): a
     * chain whose last piece happens to end where the first begins is not the same object as one the drawing
     * says is closed, and a value that drifts must not silently change what the run *is*.
     */
    private fun liftCloses(el: Element): Boolean =
        el.kind == ElementKind.OUTLINE || el.kind == ElementKind.AREA ||
            el.kind == ElementKind.CIRCLE || el.kind == ElementKind.ELLIPSE

    /**
     * [el] as the **curve in space** every `PATH3` slot wants: the run itself where it is one, and the
     * **lift** of a drawing where it is a drawing — or null with the reason it is neither ([what] names the
     * tool).
     *
     * **The coercion, and it is the point of the whole package.** A curve's construction is always parented
     * (OP-26), so a curve drawn in a space already *is* geometry in the world: the user's own reading —
     * *"sweep the foundation round the pillar's outline"* — needs no second gesture, and the run it means is
     * exactly the drawing. This is the identical courtesy [pointInSpace] does one dimension down for a
     * `POINT3` slot, where a plain 2D point is taken as the point in space it is; the rule is the same one
     * read up a dimension, so every `PATH3` slot gains it at once — sweep, tube, station, connect, place,
     * and the swept cut's route.
     *
     * **Nothing is discovered and nothing is scored.** What the step records is the pick, and what the pick
     * means is a function of its *kind* — no proximity, no nearest-anything — so a replay rebuilds the same
     * node without deciding anything (the recorded-never-discovered rule). Where the user wants the lifted run
     * as an element in its own right — to name it, hide it, station it and sweep along it at once, or to chain
     * several drawn pieces into one route — that is the *Lift drawing into space* tool ([liftCurves]), which
     * builds this very node and wraps it in an element.
     */
    @Suppress("UNCHECKED_CAST")
    private fun spaceCurveRef(
        el: Element,
        what: String,
    ): Path3Ref? {
        if (el.kind == ElementKind.SPACE_CURVE) return el.ref as Path3Ref
        if (isLiftable(el)) return cx.liftedRun(listOf(el.ref), planeOfSpace(el.space), liftCloses(el))
        if (el.kind == ElementKind.LINE || el.kind == ElementKind.RAY) {
            note =
                "$what: ${nameOf(el)} runs on for ever, so it states no length of run — click a bounded curve, " +
                "an outline, or a curve in space"
            return null
        }
        note =
            "$what: ${nameOf(el)} is ${kindWord(el)}, not a curve — click a curve in space, or the drawing " +
            "that is to be the route (an outline, an area, a segment, an arc, a circle), which is read as the " +
            "run it already is"
        return null
    }

    /**
     * How a status line names a route that was **lifted from the drawing** — nothing at all for a curve in
     * space, which is the ordinary case and needs no sentence.
     *
     * A choice made for the user speaks, exactly as the in-place crossing does ([ridingNote]): the reader is
     * told that the drawing itself is being used as the run, in which space it was read, and — where a conic
     * had to be fitted — what that cost (OP-15).
     */
    private fun liftNote(el: Element): String {
        if (el.kind == ElementKind.SPACE_CURVE) return ""
        val ev = Evaluator()
        val plane = planeValueOfSpace(el.space, ev)
        val fitted = plane != null && cx.liftIsFitted(listOf(el.ref), plane, ev)
        return ", reading it as the run it already is where it is drawn in ${spaceLabel(spaceOf(el))}" +
            (if (fitted) " (its conic pieces fitted to 0.1 µm)" else "")
    }

    /**
     * A **curve in space lifted from the drawing** (OP-26, step 1's missing source) — the explicit tool, of
     * which every `PATH3` slot's own coercion ([spaceCurveRef]) is the one-click case.
     *
     * The gesture is a repeating pick over drawn curves, and everything about what is built is read off the
     * pick list rather than from a flag beside it, exactly as *Curve through points* reads its own:
     *
     * - **one pick that already closes** — an outline, an area, a circle, an ellipse — is a closed run, by its
     *   kind and not by measuring it;
     * - **several picks** are chained in the order they were clicked, each piece flipped if that is what makes
     *   it continue ([GeomMath.chainRun]), so clicking a segment then an arc gives the run the clicks describe;
     * - **clicking the first pick again** states that the run comes back there (`closesOnFirstPick`), so the
     *   recorded step says it by naming that element twice and a replay closes for the reason the gesture did.
     *
     * **Where the run starts and which way it goes is the drawing's own** and is worth stating, because a
     * closed run has no natural end: the run begins where the picked chain's first piece begins and travels
     * the way that chain is stored — for a traced outline its own normalized (counter-clockwise) traversal,
     * for a hand-picked chain the order of the clicks. So it is a property of the drawing rather than of the
     * click that reached it, two lifts of one outline are the same run, and a station's distance is measured
     * from a place that does not move when the drawing is clicked again.
     *
     * Refused **by name**, building nothing, for the structural things — a pick that is not a drawing that can
     * be lifted, and picks made in two different spaces, which have no one plane to be read in. Everything
     * about *where* the pieces are — a gap between two of them, a boundary that does not close — is the node's
     * business and comes back as the reason it is invalid, so it heals when the drawing moves (OP-3).
     */
    fun liftCurves(picks: List<Element>): Element? {
        val what = "Lift drawing into space"
        if (picks.isEmpty()) {
            note = "$what: click the drawing that is to become a run"
            return null
        }
        for (el in picks) {
            if (!isLiftable(el)) {
                note =
                    "$what: ${nameOf(el)} is ${kindWord(el)}, and a run is lifted out of a drawn curve — click " +
                    "an outline, an area, a segment, an arc, a circle or a Bézier"
                return null
            }
        }
        val closedByGesture = picks.size >= 2 && picks.first() === picks.last()
        val run = if (closedByGesture) picks.dropLast(1) else picks
        val space = run[0].space
        for (el in run) {
            if (el.space != space) {
                note =
                    "$what: ${nameOf(el)} is drawn in ${spaceLabel(spaceOf(el))} and ${nameOf(run[0])} in " +
                    "${spaceLabel(spaceOf(run[0]))} — a run is lifted out of one drawing, so pick the pieces of " +
                    "one space (or lift each and join them with Connect two curves)"
                return null
            }
        }
        val closed = closedByGesture || (run.size == 1 && liftCloses(run[0]))
        val plane = planeOfSpace(space)
        val curve = add(cx.liftedRun(run.map { it.ref }, plane, closed), ElementKind.SPACE_CURVE, Styles.SPACE_CURVE)
        curve.space = space
        val ev = Evaluator()
        val fitted = planeValueOfSpace(space, ev)?.let { cx.liftIsFitted(run.map { r -> r.ref }, it, ev) } ?: false
        note =
            "${nameOf(curve)}: ${run.joinToString(", ") { nameOf(it) }} read as ${if (closed) "a closed run" else "a run"} " +
            "in space, lying in ${spaceLabel(spaceOf(run[0]))} where ${if (run.size == 1) "it is" else "they are"} drawn — " +
            (if (fitted) "its conic pieces fitted to 0.1 µm, " else "exact, ") +
            "and it follows every edit of the drawing"
        return curve
    }

    /**
     * An angle input that was not given: a constant zero, so the node always has all five of its inputs.
     *
     * Reached only from a **direct** call now (a macro, the DSL, a test). Through the tools, a roll or a twist
     * nobody typed arrives as a *freedom the step owns* instead — a free node standing at the slot's default,
     * restated by the step and editable in the panel for ever ([toolScalarRefs]). The constant stays because
     * an API that demands five arguments to make a tube is a worse API, and a value stated in code is a
     * constant by definition.
     */
    private fun noTurn(angle: ScalarRef?): ScalarRef = angle ?: cx.const(Quantity.deg(0.0))

    /**
     * The pyramid/cone gesture: the area [el] run to a **point** [apex] standing [height] off the sketch plane
     * (OP-17). The two-section case of [loftSolid], with the apex placed by the same kind of scalar an extrude's
     * depth is.
     *
     * The apex is an ordinary point element — a fresh one where the click found nothing, the existing one where
     * it hit a point — so it is draggable in the plan and *shared* when it was clicked, and the pyramid follows
     * it either way (OP-5). Which way the height goes is the operation's business and follows *Extrude*'s own
     * rule: on a face space a positive height builds **outward**, because a face plane's normal points into the
     * material; in the plan and on a datum it follows the plane's own normal ([liftSign]).
     *
     * **The apex it builds *is* a height point** (OP-25), which is the whole of this tool's part in that
     * package: the same two clicks and the same typed number, and what comes out is the general node rather
     * than a lift welded into the loft — so the height stands in the panel under its own name, the apex is
     * grabbable in the 3D view, and the pyramid follows either edit. The user's own reading: *"the apex of an
     * extruded outline is exactly such a point."*
     */
    fun extrudeToPoint(
        el: Element,
        apex: PointRef,
        height: ScalarRef,
        at: Vec2? = null,
        signs: List<Int> = emptyList(),
    ): Element? {
        val region =
            regionOf(el) ?: run {
                note = "Extrude to point: ${nameOf(el)} bounds no area"
                return null
            }
        val plane = activePlane()
        val top = heightPoint(apex, height)
        val seam = signs.firstOrNull() ?: seamOf(region, at)
        val solid =
            add(
                cx.loft(
                    listOf(LoftPart.Area(cx.sketchOn(plane, region)), LoftPart.Apex(top.ref as Point3Ref)),
                    listOf(seam, 0),
                ),
                ElementKind.SOLID,
                Styles.SOLID,
            )
        registerSigns(solid, listOf(seam, 0))
        note = loftNote(solid, listOf(LoftRole.SECTION, LoftRole.APEX))
        return solid
    }

    /**
     * The seam a click at [at] scores on [region]: the index of the boundary piece whose **start** is nearest
     * it (OP-8's provenance order, the same durable name a side face has).
     *
     * A piece index rather than a tessellated-vertex index on purpose: the piece count is structural, so the
     * stored choice still means the same corner after the section is stretched, retyped or re-tessellated.
     */
    private fun seamOf(
        region: RegionRef,
        at: Vec2?,
    ): Int {
        val r = (Evaluator().valueOf(region) as? RegionValue)?.region ?: return 0
        val starts = r.outer.elements.map { GeomMath.startOf(it) }
        if (at == null || starts.isEmpty()) return 0
        return starts.indices.minByOrNull { (starts[it] - at).length() } ?: 0
    }

    /** What a finished loft says about itself: what it is made of, and its honesty class (OP-15). */
    private fun loftNote(
        el: Element,
        roles: List<LoftRole>,
    ): String {
        val ev = Evaluator()
        val sections = roles.count { it != LoftRole.GUIDE }
        val guides = roles.count { it == LoftRole.GUIDE }
        val apex = roles.any { it == LoftRole.APEX }
        val what =
            buildString {
                append("Loft ${nameOf(el)} over $sections section${if (sections == 1) "" else "s"}")
                if (apex) append(" (the last one a point — an apex)")
                if (guides > 0) append(", shaped by $guides guide${if (guides == 1) "" else "s"}")
            }
        val result = ev.resultOf(el.ref)
        if (result is EvalResult.Invalid) return "$what — invalid right now: ${result.reason}"
        val feature = (ev.valueOf(el.ref) as? SolidValue)?.solid?.feature as? Feature3.Loft
        return if (feature?.approximated == true) {
            "$what — approximated (a curved section or guide is sampled, OP-15), so its volume is too"
        } else {
            "$what — exact: every facet is planar, so its volume is analytic (OP-15)"
        }
    }

    /**
     * The **boundary piece starts** of the area [el], as values — what a preview marks as the seam a click
     * would score, and what it draws the correspondence between (see [Previews]).
     *
     * Values only, no node built: a preview runs on every hover, and a graph that grew a region node per frame
     * would be the wrong kind of cheap (the same rule [closesALoop] follows).
     */
    fun loftSeamPoints(
        el: Element,
        ev: Evaluator,
    ): List<ProfileElement>? {
        (ev.valueOf(el.ref) as? RegionValue)?.let { return it.region.outer.elements }
        (ev.valueOf(el.ref) as? LoopValue)?.let { return it.loop.elements }
        val pieces = boundaryPiecesOf(el) ?: return null
        val parts = pieces.map { profilePieceOf(ev.valueOf(it.ref) ?: return null) ?: return null }
        return GeomMath.chainLoop(parts).first?.elements
    }

    /**
     * Extrude the area [el] by [depth] **from the top face of the solid [base]** (OP-17 slice 3, through
     * the OP-8 provenance accessor `facePlane`): an upper storey, a boss, a rib.
     *
     * This is the sketch→feature→sketch loop as a gesture, and it needs no new concept in the canvas: the
     * 2D drawing *is* the plan, so the area is drawn in the same 2D space as the base's own footprint and
     * this tool only says which face it sits on. The plane is a derived node, not a captured height — raise
     * the base's depth, cut an opening into it, boolean it with something taller, and the storey above
     * follows, because `facePlane` recomputes from the feature's parameters (and for a boolean's prism from
     * its slabs' extent, which is the same construction over the result's own height).
     *
     * The new solid depends on the base *and* on the area. That is a second path to the base when the area
     * is itself derived from it — a [sectionSolid] of the base, the storey-from-a-section case — and a
     * second path is not a cycle: the DAG's rule is about ancestry, and the base is an ancestor of both.
     */
    @Suppress("UNCHECKED_CAST")
    fun extrudeOnFace(
        base: Element,
        el: Element,
        depth: ScalarRef,
    ): Element? {
        if (base.kind != ElementKind.SOLID) return null
        val region = regionOf(el) ?: return null
        val plane = cx.facePlane(base.ref as SolidRef, SolidFace.TOP)
        return add(cx.extrude(cx.sketchOn(plane, region), depth), ElementKind.SOLID, Styles.SOLID)
            .also {
                madeSolid(it, "${nameOf(el)} raised ${lengthWord(depth)} off ${nameOf(base)}'s top face")
            }
    }

    /**
     * The horizontal **section** of the solid [el] at [height], as an ordinary 2D area (OP-17, downward).
     *
     * An `AREA` element like a wall's footprint, so everything the result layer can do it can do: it draws
     * in plan, it is pickable, it can be dimensioned, and it can be extruded again — including onto the
     * very solid it was cut from ([extrudeOnFace]). Being derived, it also *follows*: drag the wall the
     * solid came from and the section reshapes, with no node created and none rebuilt.
     *
     * **And it says so** (GitHub #9). This tool's success used to be silent, which made it read as broken:
     * the area lands in the plan, and for a prism it is congruent with the footprint it lies on, so the
     * screen does not change by one pixel. A success that says nothing is a defect by this project's own rule
     * (OP-3: refusals speak — successes must too), so the note names what was made, of what, at what height,
     * and where it now lies.
     */
    @Suppress("UNCHECKED_CAST")
    fun sectionSolid(
        el: Element,
        height: ScalarRef,
    ): Element? {
        if (el.kind != ElementKind.SOLID) return null
        // RESULT, not FOOTPRINT: a section is a drawing in its own right, not the plan of a wall
        val area = add(cx.sectionAt(el.ref as SolidRef, height), ElementKind.AREA, Styles.RESULT)
        val where = if (activeSpace.isPlan) "the plan" else activeSpace.name
        note =
            "${nameOf(area)} is the cross-section of ${nameOf(el)} at ${lengthWord(height)} — a 2D area " +
            "drawn in $where, so a prism's lands exactly on its own footprint and nothing looks new; a " +
            "rounded one's is the outline the rounding leaves at that height, bands and corners and all. " +
            "Dimension it, or extrude it again."
        return area
    }

    /**
     * Revolve the area [el] about the axis carried by the line element [axis] (OP-17 slice 2): the whole way
     * round when no [angle] is stated, otherwise through [angle] starting [offset] from the sketch plane.
     *
     * The axis is the picked line's own origin and direction as *derived nodes*, so the axis moves with
     * the line: drag the centreline and the turned part follows. A profile touching the axis is legal, one
     * crossing it makes the node invalid with a reason and heals when it is dragged back (OP-3) — all of
     * that is [constructit.geom.Geom3.revolve]'s, unchanged.
     *
     * **Full is structural, and it is the default** (session 63, the user's design): with nothing typed this
     * builds a complete revolution — a body with no start, no end, no caps and no offset — and there is no
     * angle node in it for any later edit to open ([constructit.geom.Turn3]). A typed angle builds the
     * partial, whose interval `[offset, offset + angle]` both ends of is a live parameter.
     *
     * **Either sign sweeps**, which is what retires the stated limit this route used to carry (*"a partial
     * Revolve in a face space still sweeps inward … the honest fix is a `dir` argument on the feature"*). A
     * positive sweep turns toward the sketch plane's own normal — on a face space that is out of the material,
     * since session 32 stopped flipping the frame — and a negative one turns the other way. A `dir` argument
     * would be a second way to say what a sign says, so the direction is stated where every other freedom in
     * this program is stated: as a number in the panel.
     */
    fun revolveSolid(
        el: Element,
        axis: Element,
        angle: ScalarRef?,
        offset: ScalarRef? = null,
    ): Element? {
        val region = regionOf(el) ?: return null
        if (!axis.isLinear) return null
        val ref =
            if (angle == null) {
                fullTurnAbout(region, axis)
            } else {
                val line = carrierLine(axis)
                cx.revolve(cx.sketchOn(activePlane(), region), cx.lineOrigin(line), cx.lineDirection(line), angle, offset)
            }
        return add(ref, ElementKind.SOLID, Styles.SOLID)
            .also { madeSolid(it, "${nameOf(el)} turned about ${nameOf(axis)}${turnWord(angle, offset)}") }
    }

    /**
     * [region] taken **the whole way round** the line [axis] carries — the structural full turn, as a node.
     *
     * Factored out of [revolveSolid] because a second gesture builds exactly this body and must be the same
     * body: the ball ([ball]) is a half-disc given a complete revolution, and a sphere that went round through
     * some *other* route would be a second meaning for "closed" (`Turn3.Full` — session 63). One node kind,
     * one watertightness argument, two ways of asking for it.
     */
    private fun fullTurnAbout(
        region: RegionRef,
        axis: Element,
    ): SolidRef {
        val line = carrierLine(axis)
        return cx.revolveFull(cx.sketchOn(activePlane(), region), cx.lineOrigin(line), cx.lineDirection(line))
    }

    /**
     * Where a revolved body **starts and ends** about its axis, for the note the tool leaves.
     *
     * Said out loud because with a non-zero offset the drawn profile is deliberately not a section of the
     * body, and a solid standing 30° away from its own drawing is exactly the kind of thing somebody goes
     * hunting for. A complete revolution has no interval to state.
     */
    private fun turnWord(
        angle: ScalarRef?,
        offset: ScalarRef?,
    ): String {
        if (angle == null) return " — a complete revolution, so it has no ends"
        val a = evalQuantity(angle)?.takeIf { it.dim == Dimension.ANGLE } ?: return ""
        val o = offset?.let { evalQuantity(it) }?.takeIf { it.dim == Dimension.ANGLE } ?: Quantity.deg(0.0)
        val from = o.deg
        val to = from + a.deg
        return " from ${Format.num(minOf(from, to))}° to ${Format.num(maxOf(from, to))}° about it"
    }

    // ---- the ball: what a circle says one dimension up (session 52's queue, item 3) ----

    /**
     * The **sphere**, built out of primitives that already existed: the half-disc of [meridian] — a pole-to-pole
     * arc and the diameter that closes it — given a **complete revolution** about that diameter ([fullTurnAbout]).
     *
     * The kernel gains nothing. There is no `Sphere3` feature, no new node kind and no new refusal: what comes
     * out is an ordinary `Revolution` retaining its own exact profile sketch, and every property a ball needs is
     * a property the revolve already has. In particular it is watertight **structurally** rather than by value —
     * `Turn3.Full` is a kind, so this graph holds no angle node for a later edit or a drifting shared parameter
     * to crack the shell open with (session 63; [constructit.geom.Turn3]).
     *
     * **Every step is by construction, and each one removes the freedom that could break it** (OP-5, OP-14's
     * rule that a structural intent gets its own spelling):
     *
     * - the poles are a point *on* [meridian] and that point's **point reflection** through the centre, so the
     *   two are exactly antipodal — [Construction.pointReflect] carries no angle, so nothing can drift it to
     *   179° and leave the "diameter" a chord;
     * - the arc is `arcCenterStartEnd(centre, south, north)`, whose radius *is* the distance from the centre, so
     *   the profile is a true half-disc rather than two ends that happen to line up;
     * - the axis is the segment between the poles, so it is the diameter itself — the revolve spins the profile
     *   about its own closing edge and the body closes on the axis at both poles.
     *
     * **[meridian] is where the two spellings differ, and it is also where they refuse.** *(centre, radius)*
     * hands in `circleCR`, *(centre, surface point)* hands in `circleCP` — the very nodes the two circle tools
     * build — so a ball declines a non-positive radius in the same words its circle does, and a surface point
     * dragged onto the centre says "zero-radius circle" exactly as the compass does. That is the pairing being
     * honest all the way down: the sphere is what the circle says one dimension up, refusals included. The
     * circle itself is a **coercion node with no element**, like the region [regionOf] wraps a loop in.
     *
     * The pole phase is a structural constant (90°, this sketch plane's own +v), not a freedom: a ball has no
     * orientation to state, so an angle node here would be a degree of freedom that changes nothing — the same
     * argument [regularPolygon] makes about `360°/count`.
     *
     * **Exact where it counts, and the help says so.** Since item 4 of the sphere queue landed, a revolution
     * *does* have named faces ([Revolve3]): this body is one spherical band closed on its own axis, so a working
     * plane through the centre gives an **exact** circle of the drawn radius and an off-centre one the exact
     * small circle — each a construction input like any other. What stays approximate is the **picture**: the
     * mesh is inscribed twice over (a chorded meridian carried on chorded parallels), so the displayed volume
     * still comes out a fraction short of `4/3·π·r³`. That is the mesh's own business (OP-9's sink rule) and
     * not the section's, which is exactly the distinction the honesty clause used to conflate.
     */
    private fun ball(
        center: PointRef,
        meridian: CircleRef,
        radiusWord: String,
    ): Element? {
        val north = addDerived(cx.pointOnCircle(meridian, cx.const(90.0.deg)))
        val south = addDerived(cx.pointReflect(north, center))
        val arc = add(cx.arcCenterStartEnd(center, south, north), ElementKind.ARC, Styles.CONSTRUCT)
        val diameter = add(cx.segment(north, south), ElementKind.SEGMENT, Styles.CONSTRUCT)
        val half = cx.region(cx.loop(arc.ref, diameter.ref))
        val solid = add(fullTurnAbout(half, diameter), ElementKind.SOLID, Styles.SOLID)
        val about = elementFor(center)?.let { " round ${nameOf(it)}" } ?: ""
        madeSolid(
            solid,
            "a ball$radiusWord$about — the half-disc of ${nameOf(arc)} and ${nameOf(diameter)} turned a complete " +
                "revolution about that diameter, so it has no ends — one spherical face, sectioned exactly",
        )
        return solid
    }

    /**
     * *Sphere (centre, radius)*: type a radius, click the centre — the twin of `Circle (centre, radius)`, and
     * it hands [ball] the very circle that tool builds, so the radius is an ordinary parameter that resizes the
     * body when it is retyped and refuses a non-positive value in the circle's own words.
     */
    fun sphereCR(
        center: PointRef,
        radius: ScalarRef,
    ): Element? = ball(center, cx.circleCR(center, radius), " of radius ${lengthWord(radius)}")

    /**
     * *Sphere (centre, surface point)*: click the centre, then a point the surface passes through — the twin of
     * `Circle (centre, point)`.
     *
     * The radius is a **derived distance** and never a free parameter: [surface] is an ordinary point pick, so a
     * click on an existing point shares its node (OP-5) and dragging it resizes the ball, exactly as it resizes
     * the circle. The pole phase stays this plane's own +v rather than following [surface], so dragging that
     * point is pure resizing — a ball has no orientation for it to state.
     */
    fun sphereCP(
        center: PointRef,
        surface: PointRef,
    ): Element? = ball(center, cx.circleCP(center, surface), "")

    // ---- booleans between solids (OP-22) ----

    /**
     * Combine the solids [a] and [b] with [kind] into one new solid (OP-22).
     *
     * One op node, two solid inputs: the result is an ordinary dependent of both operands, so deleting
     * either takes it with them, and it is itself a legal operand of the next boolean. Whether the two
     * are actually prismatic along a common axis is *not* checked here — it is a question about values,
     * so it belongs inside `compute`, where a revolve operand makes the node invalid **with a reason**
     * that names Manifold (OP-9) and heals if the geometry changes (OP-3).
     */
    @Suppress("UNCHECKED_CAST")
    fun combineSolids(
        a: Element,
        b: Element,
        kind: BoolOp,
    ): Element? {
        if (a.kind != ElementKind.SOLID || b.kind != ElementKind.SOLID) return null
        if (a === b) {
            // a refusal that said nothing was the other half of GitHub #9's silent-success sweep
            note = "${nameOf(a)} cannot be combined with itself — click two different solids"
            return null
        }
        openShellRefusal(a)?.let {
            note = it
            return null
        }
        openShellRefusal(b)?.let {
            note = it
            return null
        }
        val ra = a.ref as SolidRef
        val rb = b.ref as SolidRef
        val ref =
            when (kind) {
                BoolOp.UNION -> cx.union(ra, rb)
                BoolOp.SUBTRACT -> cx.subtract(ra, rb)
                BoolOp.INTERSECT -> cx.intersect(ra, rb)
            }
        val word =
            when (kind) {
                BoolOp.UNION -> "fused with"
                BoolOp.SUBTRACT -> "less"
                BoolOp.INTERSECT -> "met with"
            }
        return add(ref, ElementKind.SOLID, Styles.SOLID)
            .also { madeSolid(it, "${nameOf(a)} $word ${nameOf(b)}") }
    }

    // ---- edge blends: the 2D fillet, one dimension up (session 71, slice 2) ----

    /**
     * Whether [el] can fill a blend's **profile** slot: one drawn curve of any kind, or a drawn chain
     * (GitHub #30).
     *
     * A profile has **two ends**, one to land on each face. What the *slot* takes is every drawn curve and
     * every closed result, and what has no two ends is refused **by name** at build time
     * ([blendProfileRefusal]) rather than being silently unpickable — a click on a circle deserves the
     * sentence that says why a circle cannot shape an edge, not a slot that ignores it (session 65).
     *
     * Everything accepted is read for its **numbers alone**: the profile's x is the setback along one face
     * and its y the setback along the other, so which plane it happens to be drawn on contributes nothing
     * but those two coordinates.
     */
    fun isBlendProfile(el: Element): Boolean = el.kind == ElementKind.CHAIN || el.isCurve || el.isArea

    /** [el] as a blend's drawn section, or null when it is not one — see [isBlendProfile]. */
    @Suppress("UNCHECKED_CAST")
    private fun blendProfileOf(el: Element): ProfileRef? =
        when (el.kind) {
            ElementKind.CHAIN -> cx.chainProfile(el.ref as ChainRef)
            ElementKind.SEGMENT, ElementKind.ARC, ElementKind.BEZIER, ElementKind.ELLIPTIC_ARC, ElementKind.FUNC_CURVE ->
                cx.profile(el.ref)
            else -> null
        }

    /** Why [el] is not a profile, in its own words. */
    private fun blendProfileRefusal(el: Element): String =
        when (el.kind) {
            ElementKind.CIRCLE, ElementKind.ELLIPSE, ElementKind.OUTLINE, ElementKind.AREA ->
                "is closed, and a rounding's profile has two ends — one to land on each face. Break it, or draw an open chain"
            ElementKind.LINE, ElementKind.RAY ->
                "runs on for ever, and a rounding's profile has two ends — one to land on each face. Draw a chain or a segment instead"
            else -> "is ${kindWord(el)}, not a curve a rounding can be shaped by — draw a chain, a segment, an arc or a Bézier"
        }

    /**
     * The section a **live** gesture scores its choices against — the typed size for the two built-ins, the
     * drawn profile's own value for the general tier — or null when there is nothing to score against yet.
     */
    private fun scoringSection(
        kind: BlendKind,
        size: ScalarRef?,
        profile: ProfileRef?,
        ev: Evaluator,
    ): BlendSection? {
        if (kind == BlendKind.PROFILE) {
            val drawn = ((ev.eval(profile!!.node) as? EvalResult.Ok)?.value as? ProfileValue)?.profile?.elements ?: return null
            return if (drawn.isEmpty()) null else BlendSection(kind, 0.0, drawn)
        }
        val r = ((ev.eval(size!!.node) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.mm
        return if (r == null || r <= 0.0) null else BlendSection(kind, r)
    }

    /**
     * **Break an edge of [solid]** — a fillet or a chamfer along a provenance-named edge, built as OP-9's own
     * sentence: the 2D fillet construction run in the edge's normal section, swept along the edge, applied by
     * a boolean ([Blend3]).
     *
     * **One pick, two granularities.** With [whole] false the click names **one edge** — the edge whose
     * drawing in the space the click was made in runs nearest it, which is the same 2D machinery a section
     * and a face space already draw the boundary with. With [whole] true it names a **face**, and what is
     * blended is that face's entire boundary chain ([Blend3.faceNear]) — *"all of the curve parts"* in one
     * click, which is the user's own words for the motivating case. Neither is new picking machinery.
     *
     * **Everything the click decides is scored once and then persisted** (OP-1/OP-18). [signs] is what a
     * replay hands back — the address first, then four integers per edge — and when it is present nothing is
     * scored at all: which edge, which face, which of the four sectors round the crease the blend fills, and
     * whether that sector is material are every one of them answers about geometry that *moves*, so a reload
     * that scored again would be a reload that re-decided. That is the fillet's own lesson, one dimension up.
     *
     * Refused **by name**, building nothing, only for the structural things: a pick that is not a solid, a
     * body with no named edges (a mesh boolean's result, an import, a sweep — each in [Section3]'s own words),
     * and a body or a size with no value to score against. Everything geometric — a size that reaches past a
     * face, a section this rounding has no name for, a bend the wedge outgrows — is the node's business and
     * comes back as the reason it is invalid, so it heals when the number comes down (OP-3).
     */

    @Suppress("UNCHECKED_CAST")
    fun blendEdges(
        solid: Element,
        size: ScalarRef?,
        kind: BlendKind,
        whole: Boolean,
        at: Vec2,
        view: PlaneProjection? = null,
        signs: List<Int> = emptyList(),
        profileEl: Element? = null,
    ): Element? {
        val what = "${kind.word.replaceFirstChar { it.uppercase() }} ${if (whole) "the edges of a face" else "an edge"}"
        if (solid.kind != ElementKind.SOLID) {
            note = "$what: ${nameOf(solid)} is ${kindWord(solid)}, not a solid — click the body whose edge you want broken"
            return null
        }
        // **the drawn section, resolved to an ordinary operand** (GitHub #30). A profile is a curve of the
        // drawing — one piece of any kind, or a drawn chain — and it becomes a `ProfileRef` the blend node
        // consumes, so editing it re-blends the body and deleting it cascades, exactly as any operand does.
        val profileRef =
            if (kind != BlendKind.PROFILE) {
                null
            } else {
                val el = profileEl
                if (el == null) {
                    note = "$what: click the profile to run along the edge — a drawn chain, or one segment, arc or curve"
                    return null
                }
                blendProfileOf(el) ?: run {
                    note = "$what: ${nameOf(el)} ${blendProfileRefusal(el)}"
                    return null
                }
            }
        val ev = Evaluator()
        // **Two walks, two questions, and they are not the same question** (session 71, slice 2).
        //
        // *Whose edges am I naming?* — a walk **backwards** along the picked body's own spine to the nearest
        // solid that names its edges ([analyticBaseOf]). Since slice 3 a **blended** body names its own
        // (`Feature3.Blend` extends its base's list), so the walk stops there and a blend of a blend is
        // addressed against the dressed list; what still names none is a body a general boolean made — a
        // fused part (OP-9's sink rule) — and there the addresses stay against the analytic body under it.
        //
        // *What body do I apply to?* — a walk **forwards** to the drawing's tip of that body's chain
        // ([tipOfChain], the sequential-feature rule OP-17 already states for a cut). Without it a blend made
        // after an ordinary Union or Subtract would land on the pre-boolean body and **fork** the model into a
        // blended-but-unfused solid beside a fused-but-unblended one — the recorded probe classic, one feature
        // over. With it, fillet → union → chamfer is one chain whichever body of it the click reached.
        //
        // Both are read off the graph and neither is recorded, and that is safe for the reason a `tool` step's
        // replay is exact at all: each is a pure function of the **picked element the step names** plus the
        // journal prefix, and replaying the prefix rebuilds exactly the elements and edges they walk. (The
        // face-space cut records its tip instead because its input — the *space's* anchor — is not named by
        // its step at all, so there would be nothing durable to re-resolve from.)
        val baseEl = analyticBaseOf(solid, ev)
        val body =
            (ev.valueOf(baseEl?.ref ?: solid.ref) as? SolidValue)?.solid ?: run {
                note = "$what: ${nameOf(solid)} has no value right now, so it shows no edges to break"
                return null
            }
        if (baseEl == null) {
            note = "$what: ${nameOf(solid)} — ${Section3.edges(body.feature).second}"
            return null
        }
        val tipEl = tipOfChain(solid, ev) ?: solid
        // how the click named its target, so the note can say it (see [BlendPick]); false on a replay, which
        // scores nothing at all
        var inView = false
        val address =
            signs.getOrNull(0) ?: run {
                val pick = blendTarget(body, whole, at, view, ev)
                inView = pick.inView
                pick.index ?: run {
                    note = "$what: ${nameOf(solid)} — ${pick.why}"
                    return null
                }
            }
        val (targets, whyTargets) = Blend3.targets(body.feature, whole, address, tangentRun(baseEl, ev))
        if (targets == null) {
            note = "$what: ${nameOf(solid)} — $whyTargets"
            return null
        }
        // **four integers per edge for the built-in rows, five for a drawn profile** — the fifth is which
        // end of it is the setback on which face. The chunk size is a property of the *tool id*, which the
        // step already carries, so no older file is re-read differently and no version bump is owed (OP-18).
        val perEdge = if (kind == BlendKind.PROFILE) 5 else 4
        val stored = signs.drop(1).chunked(perEdge).mapNotNull { BlendChoice.of(it) }
        val choices =
            if (stored.size >= targets.size) {
                stored.take(targets.size)
            } else {
                val sec = scoringSection(kind, size, profileRef, ev)
                if (sec == null) {
                    note =
                        if (kind == BlendKind.PROFILE) {
                            "$what: ${nameOf(profileEl!!)} has no value right now, so there is no profile to run along the edge"
                        } else {
                            "$what: type a positive ${kind.sizeWord} first (or pick a parameter in the panel)"
                        }
                    return null
                }
                // **which face the click named** is what says which end of a drawn profile is which setback
                // (GitHub #30): the face a face gesture landed on, or the face an edge pick was looking at.
                val onFace =
                    if (kind != BlendKind.PROFILE) {
                        null
                    } else if (whole) {
                        Section3.faces(body.feature).first?.getOrNull(address)?.name
                    } else {
                        Blend3.faceOfEdgeToward(body.feature, address, planeOfSpace(baseEl.space).let { (ev.valueOf(it) as PlaneValue).plane })
                            .first?.let { Section3.faces(body.feature).first?.getOrNull(it)?.name }
                    }
                val (scored, why) = Blend3.choicesFor(body, targets, sec, onFace)
                val fresh =
                    scored ?: run {
                        note = "$what: ${nameOf(solid)} — $why"
                        return null
                    }
                // **A recorded choice is never re-decided** (OP-18). A file written before one pick ran along
                // the tangent run (GitHub #29) carries exactly one choice, and it belongs to the edge the
                // click named — the run's *other* edges have none, so they are scored here, once, and the
                // next save records all of them. The load says so by name rather than quietly.
                if (stored.size == 1 && targets.size > 1) {
                    if ((replayingVersion ?: 0) in 1 until DocumentFormat.SUPERSEDING_FILLET_VERSION) {
                        noteLoad(
                            "${nameOf(solid)}'s ${kind.word} now runs along all ${targets.size} edges of the " +
                                "tangent-continuous run through the edge it named — they are one smooth band, " +
                                "which is what the drawing says they are; the added edges' choices are scored " +
                                "once here and written on the next save",
                        )
                    }
                    targets.indices.map { i -> if (targets[i] == address) stored[0] else fresh[i] }
                } else {
                    fresh
                }
            }
        val el =
            add(
                cx.blend(
                    tipEl.ref as SolidRef,
                    baseEl.ref as SolidRef,
                    planeOfSpace(baseEl.space),
                    size,
                    kind,
                    whole,
                    address,
                    choices,
                    // the run the pick stands for, resolved from the registry here and never stored (#29)
                    run = if (whole) emptyList() else targets,
                    profile = profileRef,
                ),
                ElementKind.SOLID,
                Styles.SOLID,
            )
        el.space = baseEl.space
        registerSigns(el, listOf(address) + choices.flatMap { if (kind == BlendKind.PROFILE) it.signsWithFlip() else it.signs() })
        val where =
            if (whole) {
                Section3.faces(body.feature).first?.getOrNull(address)?.name?.label ?: "a face"
            } else {
                Section3.edges(body.feature).first?.getOrNull(address)?.name?.label ?: "an edge"
            }
        madeSolid(
            el,
            "${nameOf(tipEl)} with " +
                (
                    if (kind == BlendKind.PROFILE) {
                        "the profile ${nameOf(profileEl!!)} run"
                    } else {
                        "a ${kind.word} of ${lengthWord(size!!)}"
                    }
                ) + " along ${if (whole) "every edge of " else ""}$where" +
                (if (baseEl !== tipEl) " of ${nameOf(baseEl)}" else "") +
                (
                    if (targets.size > 1 || (whole && Blend3.roundedAlready(body.feature, address) > 0)) {
                        // …and what it did **not** take, where an earlier rounding got there first: a face
                        // gesture breaks the edges of that face that are still sharp (session 80), and the
                        // note says so rather than leaving "(2 edges)" on a four-edged face to be a surprise
                        val already = if (whole) Blend3.roundedAlready(body.feature, address) else 0
                        " (${targets.size} edge${if (targets.size == 1) "" else "s"}" +
                            (if (already > 0) " — $already ${if (already == 1) "was" else "were"} already rounded" else "") + ")"
                    } else {
                        ""
                    }
                ) +
                // **which picture named it**, said out loud for the reason *Sketch on face* says it (the
                // `Face3DPickTest` precedent): the two views answer this question by different evidence, and a
                // user who got an edge they did not expect must be able to read which one answered.
                (if (inView) ", picked in the 3D view" else "") +
                (
                    if (kind == BlendKind.PROFILE) {
                        " — the profile is an ordinary drawing, so reshaping it re-cuts the body"
                    } else {
                        " — the ${kind.sizeWord} is an ordinary parameter, so retyping it re-rounds the body"
                    }
                ),
        )
        return el
    }

    /**
     * Whether two edges that meet are **one tangent-continuous run** — the 2D joint registry, read one level
     * up (GitHub #29).
     *
     * *"The extruded version of segment e11 is 3D-filleted. However, this results in an awkward result, since
     * segment e11 is linked to a fillet in the 2D base construction (arc e6) … I would expect that the
     * fillets are 'smoothly' joined together — as if I rounded all the edges with a rasp."* This is what
     * makes one pick take the rasped run: where the drawing **states** that two of its pieces hand over
     * tangentially, the two rims over them are one ribbon.
     *
     * Three conditions, and each is there for a reason:
     * - both edges lie **on one known plane, the same one** ([EdgeGeom.OnPlane]). That is where a drawing's
     *   own tangency lives — a cap's rim is its footprint's boundary — and it is what keeps the *upright* at
     *   a tangency out of the run: an upright is a straight edge across the two planes, not a piece of
     *   either. (Runs among edges that lie on no plane — a loft's rails — are a future extension, and
     *   nothing about them is claimed here.)
     * - they **share a face**, so the run walks along a boundary rather than jumping across the body;
     * - and the vertex they meet at is a **recorded tangent handover** of the drawing.
     *
     * The relation is on record; the *position* is only how the vertex is looked up, which is
     * [sharedEndBetween]'s own rule and for its own reason — a shared node is not available here at all,
     * since one side of the comparison is a corner of a mesh's own feature. Hidden pieces are skipped
     * exactly as the boundary follow skips them ([handoverPlaces]): a piece the drawing does not show is not
     * part of its boundary, so a superseded leg cannot lend its tangency to the rim over the segment that
     * replaced it — the replacement has to have **inherited** it (see [inheritTangency]).
     */
    private fun tangentRun(
        baseEl: Element,
        ev: Evaluator,
    ): ((SolidEdge, SolidEdge) -> Boolean)? {
        val plane = (ev.valueOf(planeOfSpace(baseEl.space)) as? PlaneValue)?.plane ?: return null
        val handovers =
            jointRegistry
                .filter { j ->
                    // ...of **this** drawing: a 2D position only means something in the space it was drawn
                    // in (OP-17), so a joint of another sketch space is not a handover of this body's rim
                    j.tangent && j.a.visible && j.b.visible && j.a.space == baseEl.space &&
                        elements.any { it === j.a } && elements.any { it === j.b }
                }
                .mapNotNull { (ev.valueOf(it.at) as? PointValue)?.p }
        if (handovers.isEmpty()) return null
        return { a, b ->
            val pa = (a.geom as? EdgeGeom.OnPlane)?.plane
            val pb = (b.geom as? EdgeGeom.OnPlane)?.plane
            pa != null && pa == pb && (b.between.has(a.between.a) || b.between.has(a.between.b)) &&
                Blend3.sharedEnd(a, b)?.let { w ->
                    handovers.any { (plane.toLocal(w) - it).length() <= GeomMath.JOIN_TOL }
                } == true
        }
    }

    /**
     * **Hollow [solid] to a wall of [thickness]** (session 75): the shell, with the face the click named left
     * open when [open], and closed when not ([Shell3]).
     *
     * **One walk, not two, and that is the difference from the blend.** A blend asks *whose edges am I naming*
     * of the analytic body under the part and *what do I apply to* of the drawing's tip; a shell asks one
     * question of one body, because its cavity is a function of the **whole** body being hollowed and a
     * fused part's cavity is not one operand's (see `Construction.shell`). So [tipOfChain] applies — the
     * sequential-feature rule OP-17 already states, so shelling after a Union hollows the fused part rather
     * than forking the model — and where that tip has no offset profile of its own it refuses **by name**,
     * naming the route that does work.
     *
     * The open face is **scored once and recorded** (OP-1/OP-18): in the 3D view by the pointer's own ray,
     * through the face-pick seam edit-in-3D slice 2 built ([Section3.faceAt] — a ray hit resolved against the
     * feature's own face list, never against a triangle's identity), and on a flat canvas by the same
     * *nearest the eye* reading a blend's face pick uses ([faceUnderClick]). Whichever named it, the index goes
     * into the step's `signs=` and every replay takes it verbatim.
     */
    fun shellSolid(
        solid: Element,
        thickness: ScalarRef,
        open: Boolean,
        at: Vec2,
        view: PlaneProjection? = null,
        signs: List<Int> = emptyList(),
    ): Element? {
        val what = if (open) "Shell" else "Hollow"
        if (solid.kind != ElementKind.SOLID) {
            note = "$what: ${nameOf(solid)} is ${kindWord(solid)}, not a solid — click the body you want hollowed"
            return null
        }
        val ev = Evaluator()
        val tipEl = tipOfChain(solid, ev) ?: solid
        val body =
            (ev.valueOf(tipEl.ref) as? SolidValue)?.solid ?: run {
                note = "$what: ${nameOf(tipEl)} has no value right now, so there is nothing to hollow"
                return null
            }
        Shell3.shellable(body.feature)?.let {
            note = "$what: ${nameOf(tipEl)} — $it"
            return null
        }
        // the face the note names, filled in by the open row and unread by the closed one
        var openLabel = "a face"
        val openFaces =
            if (!open) {
                emptyList()
            } else {
                val face =
                    signs.getOrNull(0) ?: run {
                        val (i, why) = faceForOpening(body, at, view, ev)
                        i ?: run {
                            note = "$what: ${nameOf(tipEl)} — $why"
                            return null
                        }
                    }
                Shell3.openFaceRefusal(body.feature, face)?.let {
                    note = "$what: ${nameOf(tipEl)} — $it"
                    return null
                }
                openLabel = Section3.faces(body.feature).first?.getOrNull(face)?.name?.label ?: "a face"
                listOf(face)
            }
        val el = add(cx.shell(tipEl.ref as SolidRef, thickness, openFaces), ElementKind.SOLID, Styles.SOLID)
        el.space = tipEl.space
        registerSigns(el, openFaces)
        madeSolid(
            el,
            "${nameOf(tipEl)} hollowed to a wall of ${lengthWord(thickness)}" +
                (if (open) ", with $openLabel left open" else ", closed all round") +
                " — the wall thickness is an ordinary parameter, so retyping it re-hollows the body",
        )
        return el
    }

    /**
     * Which face of [body] a shell is to open, from the click at [at] — **the ray's answer where a 3D view is
     * driving, the flat picture's where one is not**, as an index into [Section3.faces].
     *
     * The ray is asked first for the reason edit-in-3D slice 2 records: depth is evidence the flat pictures do
     * not have, and the face somebody is looking at is the face they clicked. The seam is the feature's own —
     * `Section3.faceAt` resolves a hit *point* against the analytic face list, so the answer cannot move with
     * the mesh quality, which is exactly what lets it be recorded as a durable choice (OP-1/OP-18). Without a
     * ray (the 2D canvas) the reading is the blend's: the flat face the click falls within as this space looks
     * at the body, or the face the rim it landed on is seen from.
     */
    private fun faceForOpening(
        body: Solid3,
        at: Vec2,
        view: PlaneProjection?,
        ev: Evaluator,
    ): Pair<Int?, String?> {
        val feature = body.feature
        val ray = view?.eyeRay(at)
        if (ray != null) {
            val t = Geom3.rayMesh(ray, body.mesh)
            if (t != null) {
                val (pick, why) = Section3.faceAt(feature, ray.at(t), ray.dir, Geom3.meshSag(body.mesh))
                if (pick == null) return null to why
                val faces = Section3.faces(feature).first ?: return null to why
                val i = faces.indexOfFirst { it.name == pick.patch.name }
                return if (i >= 0) i to null else null to "${pick.patch.name.label} is not a face this body can open"
            }
        }
        val from =
            (ev.valueOf(planeOfSpace(activeSpace.name)) as? PlaneValue)?.plane
                ?: return null to "${activeSpace.name} has no value right now, so there is nothing to click on"
        // …and where a 3D view *is* driving but its ray reached no body at all (a click just off the
        // silhouette), the rim reading below is still asked of the picture the camera shows (issue #24)
        return if (ray != null) faceUnderClick(feature, from, at, view, body) else faceUnderClick(feature, from, at)
    }

    /**
     * What a blend's click named: the index into the list it addresses, the refusal when it named nothing,
     * and **which picture answered** — see [blendTarget] and the note [blendEdges] writes.
     */
    private class BlendPick(val index: Int?, val why: String?, val inView: Boolean = false)

    /**
     * Which edge (or [whole] face) of [body] a blend's click at [at] named — **the ray's answer where a 3D
     * view is driving, the flat picture's where one is not** ([faceForOpening]'s own rule, applied to the
     * other gesture that picks on a body).
     *
     * **GitHub issue #24, and the fault it names is one line long.** A click in the 3D view is resolved onto
     * the working plane first (that is how every existing tool gets a 2D coordinate — edit-in-3D slice 1),
     * and the flat rule below then measures *plan-projected* edges against that plane point. For an edge that
     * does not lie in the working plane — the top rim of an extruded plate, 20 mm above the drawing — the
     * plane point is displaced from the edge by the whole parallax of the view, so which plan-projected edge
     * comes out nearest depends on where the camera happens to stand. The user's report is exactly that:
     * *"depends on the camera angle and position — sometimes it works, sometimes not."*
     *
     * The cure is not a better tolerance but the right picture: in the 3D view the edge is picked **as seen
     * from the camera** ([edgeInView]), which is the same sentence the flat rule already states — *the edge
     * whose drawing here runs nearest the click* — asked of the drawing the user is actually looking at.
     *
     * A face is resolved by the ray against the feature's own face list, which is [faceForOpening] verbatim
     * (`Section3.faceAt`, edit-in-3D slice 2), and falls through to the edge-seen-from reading below.
     *
     * **Nothing recorded changes.** The step stores the index in `signs=` and the click in `clicks=`
     * (OP-1/OP-18), and a replay passes no view at all — so this scores once, exactly as before, and every
     * file written before it replays to the same body.
     */
    private fun blendTarget(
        body: Solid3,
        whole: Boolean,
        at: Vec2,
        view: PlaneProjection?,
        ev: Evaluator,
    ): BlendPick {
        val feature = body.feature
        if (view?.eyeRay(at) != null) {
            if (whole) {
                val (i, why) = faceForOpening(body, at, view, ev)
                return BlendPick(i, why, inView = true)
            }
            edgeInView(feature, body, view, at)?.let { (i, why) -> return BlendPick(i, why, inView = true) }
        }
        val from =
            (ev.valueOf(planeOfSpace(activeSpace.name)) as? PlaneValue)?.plane
                ?: return BlendPick(null, "${activeSpace.name} has no value right now, so there is nothing to click on")
        val (i, why) = if (whole) faceUnderClick(feature, from, at) else edgeNear(feature, from, at)
        return BlendPick(i, why)
    }

    /**
     * Which edge of [feature] the click at [at] named **as the 3D view draws it**: the edge whose projected
     * path runs nearest the click *on screen*, ties to the edge nearest the eye and never to one hidden
     * behind the body (GitHub issue #24, and see [blendTarget] for why the flat rule cannot answer here).
     *
     * Four readings, in this order, and each is the flat rule's own sentence moved into the right picture:
     * - **under the cursor or not.** An edge whose drawing runs within the pick tolerance of the click — ten
     *   pixels, the number `Editor.tolPx` states for every other pick — is *under the cursor*, and one that
     *   is beats one that is not, however near that one comes.
     * - among those under the cursor, **the edge in front**, and this is the one place depth outranks screen
     *   distance rather than only breaking its ties. Inside the tolerance the click has landed *on the body*,
     *   and there the user cannot have meant an edge the body itself is standing in front of: looking down at
     *   a plate, its top and bottom rims draw within a pixel or two of each other and the underside is behind
     *   20 mm of material. A ray shot at the edge that meets the mesh before reaching it says so, forgiven by
     *   the tessellation sag, so an edge lying *on* the visible surface is never mistaken for a hidden one.
     * - then **screen distance**, which is the whole of the answer wherever nothing is hidden — a grazing view
     *   crowds five visible edges into as many pixels, and the one the cursor sits exactly on is the pick.
     * - and finally **nearest the eye** for two edges drawing at the very same place, which is the flat rule's
     *   tie-break verbatim.
     *
     * Screen distance throughout is measured to the edge's own drawing: the path is tessellated exactly as
     * the 3D view draws it ([Curves3.polyline], whose contract is *"what is on screen is what the pointer
     * reaches"*) and each vertex projected through the very projection that drew it, so what looks nearest
     * *is* nearest.
     *
     * Null — not a refusal — when the click has no screen image at all, so the caller falls back to the flat
     * reading rather than declining a gesture the old code would have answered.
     */
    private fun edgeInView(
        feature: Feature3,
        body: Solid3,
        view: PlaneProjection,
        at: Vec2,
    ): Pair<Int?, String?>? {
        val ray = view.eyeRay(at) ?: return null
        val click = view.toScreen(at) ?: return null
        val (edges, why) = Section3.edges(feature)
        if (edges == null) return null to why
        val eye = ray.origin
        // the bound a point read off a chord has to be forgiven by — the same number a ray hit is tested
        // against the analytic faces with (`Section3.faceAt`, edit-in-3D slice 2)
        val sag = Geom3.meshSag(body.mesh) + Geom3.WELD_TOL
        // the pick tolerance every other pick in this editor uses (`Editor.tolPx`), and here the width of the
        // tie: inside it the click cannot say which of two edges it meant, so depth answers instead
        val tolPx = 10.0
        var best: Int? = null
        var bestDist = Double.MAX_VALUE
        var bestDepth = Double.MAX_VALUE
        var bestHidden = false
        var bestUnder = false
        for (i in edges.indices) {
            val path = Blend3.edgePath(edges[i]).first ?: continue
            val pts = Curves3.polyline(path)
            var d = Double.MAX_VALUE
            var nearest: Vec3? = null
            var prevW: Vec3? = null
            var prevS: Vec2? = null
            for (w in pts) {
                val s = view.worldToScreen(w)
                if (s != null) {
                    val alone = (s - click).length()
                    if (alone < d) {
                        d = alone
                        nearest = w
                    }
                    val pw = prevW
                    val ps = prevS
                    if (ps != null && pw != null) {
                        val t = segmentParam(click, ps, s)
                        val on = (ps + (s - ps) * t - click).length()
                        if (on < d) {
                            d = on
                            // the world point at the screen parameter, which under perspective is the chord's
                            // own point rather than exactly the edge's — near enough for a depth comparison
                            // forgiven by [sag], and the chord is what was measured against anyway
                            nearest = pw + (w - pw) * t
                        }
                    }
                }
                prevW = w
                prevS = s
            }
            val p = nearest ?: continue
            val depth = (p - eye).length()
            if (depth <= Vec3.EPS) continue
            // hidden when the body itself stands between the eye and this edge — the ray-hit depth
            // [faceForOpening] already uses, shot at the edge instead of at the cursor
            val hidden = Geom3.rayMesh(ray.copy(dir = (p - eye) * (1.0 / depth)), body.mesh)?.let { it < depth - sag } ?: false
            val under = d <= tolPx
            val better =
                when {
                    best == null -> true
                    // the tolerance gate: an edge the cursor is *on* beats one it is merely nearest to
                    under != bestUnder -> under
                    // outside it there is no tie to break and nothing is hidden from a click that missed
                    !under -> d < bestDist - 1e-6
                    // inside it the click has landed on the body, and there you cannot have meant an edge
                    // the body itself is standing in front of
                    hidden != bestHidden -> !hidden
                    d < bestDist - 1e-6 -> true
                    d > bestDist + 1e-6 -> false
                    // two edges drawing at the very same place: the flat rule's own tie-break
                    else -> depth < bestDepth
                }
            if (better) {
                bestDist = d
                bestDepth = depth
                bestHidden = hidden
                bestUnder = under
                best = i
            }
        }
        return if (best == null) null to "it draws no edge here that a blend could run along" else best to null
    }

    /**
     * Where along the screen segment [a]→[b] the point [p] falls, clamped to the segment — the parameter
     * behind [HitTest.distanceToSegment], needed here because the *world* point at that place is what a depth
     * comparison is made at (see [edgeInView]).
     */
    private fun segmentParam(
        p: Vec2,
        a: Vec2,
        b: Vec2,
    ): Double {
        val ab = b - a
        val len2 = ab.dot(ab)
        if (len2 <= Vec2.EPS * Vec2.EPS) return 0.0
        return ((p - a).dot(ab) / len2).coerceIn(0.0, 1.0)
    }

    /**
     * Which edge of [feature] the click at [at] named, in the space [from] — **the edge whose drawing here
     * runs nearest it**, measured exactly as any other curve pick is ([HitTest.distanceToPiece]).
     *
     * The 2D machinery reaches 3D edges because a working plane's section and a face space's own picture
     * already draw them: the cap boundary a blend runs along is the very outline the canvas shows.
     *
     * **The flat reading, and only the flat one.** This used to answer for the 3D view too, on the grounds
     * that the click had already been resolved onto the working plane — which is exactly the fault GitHub
     * issue #24 reported: an edge that does not lie in this plane is drawn here by a projection nobody is
     * looking through. The 3D view has its own reading now ([edgeInView]) and this one is untouched, so every
     * flat gesture, message and golden is bit-identical to what it always was.
     */
    private fun edgeNear(
        feature: Feature3,
        from: Plane3,
        at: Vec2,
    ): Pair<Int?, String?> {
        val (edges, why) = Section3.edges(feature)
        if (edges == null) return null to why
        val n = from.normal.normalized()
        var best: Int? = null
        var bestDist = Double.MAX_VALUE
        var bestHeight = -Double.MAX_VALUE
        for (i in edges.indices) {
            val path = Blend3.edgePath(edges[i]).first ?: continue
            val d = Curves3.projectedOnto(path, from).minOfOrNull { HitTest.distanceToPiece(at, it) } ?: continue
            // **Ties go to the edge nearest the eye**, which is not a preference but the only reading a flat
            // view has: looking down at a plate, its top rim and its bottom rim draw the *same* line, and the
            // one the click meant is the one that is not hidden behind the other. The same sentence
            // [Blend3.faceNear] states for a face, and the same one a projected drawing lands by (OP-26).
            val height = (path.start ?: continue).let { (it - from.origin).dot(n) }
            if (d < bestDist - 1e-6 || (d < bestDist + 1e-6 && height > bestHeight)) {
                if (d < bestDist) bestDist = d
                bestHeight = height
                best = i
            }
        }
        return if (best == null) null to "it draws no edge here that a blend could run along" else best to null
    }

    /**
     * The solid whose **named edges** a blend on [el] is addressed by: [el] itself where it has them, and
     * otherwise the body [el] was made *from* — followed down its own **spine**, never sideways.
     *
     * The spine is the chain of **first** material inputs, and that is the whole of the rule: every operation
     * that consumes a solid states the part first and the tool second — *"the solid to keep, then the one to
     * remove from it"*, a union's first click, a blend's own body — so following input #0 walks back through
     * the part's history and never wanders into the pad that was fused onto it or the box that was cut out of
     * it. What it replaced was *"the last solid in creation order that this one depends on and that names its
     * edges"*, which is right only while a body has one analytic ancestor: a plate fused with a pad has two,
     * and creation order would have handed back the pad and addressed the wrong body's edge list.
     *
     * Structural and therefore not recorded: the graph is the same on a replay, so the walk is the same walk
     * and no stored address ever means something else. Null when nothing on the spine names its edges — an
     * imported body, a sweep, a boolean whose kept operand was never analytic — which is refused in
     * [Section3]'s own words.
     */
    private fun analyticBaseOf(
        el: Element,
        ev: Evaluator,
    ): Element? {
        fun namesEdges(e: Element): Boolean {
            val f = (ev.valueOf(e.ref) as? SolidValue)?.solid?.feature ?: return false
            return Section3.edges(f).first != null
        }
        var cur: Element? = el
        val seen = HashSet<String>()
        while (cur != null && seen.add(cur.id)) {
            if (namesEdges(cur)) return cur
            val next = cur.ref.node.inputs.firstOrNull { isMaterial(ev, it) } ?: return null
            cur = elements.lastOrNull { it.kind == ElementKind.SOLID && it.ref.node === next }
        }
        return null
    }

    /**
     * Which **face** a click on a body meant, in two readings: the flat face the click falls within as this
     * space looks at it, and — because a solid is picked by its footprint, which *is* a cap's own outline —
     * the face the edge it landed on is **seen from** ([Blend3.faceOfEdgeToward]).
     *
     * The second is what the everyday gesture uses: clicking a plate anywhere on its rim names its top face,
     * because that is the face of that rim you are looking at.
     *
     * **Which rim it landed on is asked of the view that shows it** where one is driving ([view] and [body]
     * given, GitHub issue #24): the containment test above is a *drop along this space's normal* and is
     * unchanged, but the rim reading is a nearest-drawing rule and had the parallax fault this issue names.
     * Both arguments absent — the 2D canvas, and every replay — is the old behaviour exactly.
     */
    private fun faceUnderClick(
        feature: Feature3,
        from: Plane3,
        at: Vec2,
        view: PlaneProjection? = null,
        body: Solid3? = null,
    ): Pair<Int?, String?> {
        val (inside, why) = Blend3.faceNear(feature, from, at)
        if (inside != null) return inside to null
        val (edge, whyEdge) =
            (if (view != null && body != null) edgeInView(feature, body, view, at) else null)
                ?: edgeNear(feature, from, at)
        if (edge == null) return null to (whyEdge ?: why)
        return Blend3.faceOfEdgeToward(feature, edge, from)
    }

    /**
     * The **state** [el] is in, in the words a status line uses — or null when there is nothing to say.
     *
     * One channel, so a state a user must know about is said wherever the element is named ([Editor]'s
     * inspector header and pick-cycle line both ask this) rather than needing a badge of its own. Two
     * answers today: an imported body that came in as an **open shell**, which decides what that body can be
     * used for (no boolean, no 3MF, no STL — everything else unchanged), and an element that is **hidden**.
     *
     * Hidden says which of the two hidings it is, because they answer differently: a user's hide is undone by
     * *Show*, while a welded alias is hidden **by the construction** and [setElementsVisible] refuses to show
     * it — so the sentence that named it must not promise a button that will decline. Reachable at all only
     * because a hidden element can be selected from the element tree, and — while *Show hidden* is on
     * (`Editor.showHidden`) — clicked on the canvas as a ghost.
     */
    fun stateOf(
        el: Element,
        ev: Evaluator = Evaluator(),
    ): String? {
        val states = ArrayList<String>(2)
        if (!el.visible) {
            states.add(
                if (hiddenByConstruction(el)) {
                    "hidden by the construction (Show leaves it hidden)"
                } else {
                    "hidden (Show brings it back)"
                },
            )
        }
        val f = (ev.valueOf(el.ref) as? SolidValue)?.solid?.feature as? Feature3.Imported
        if (f?.openShell != null) states.add("open shell (display and arrangement only)")
        return states.joinToString("; ").ifEmpty { null }
    }

    /**
     * Why [el] cannot be a boolean operand — it is an imported **open shell** — or null when it can.
     *
     * The *gesture's* half of a refusal the node also makes (`Construction.booleanOf` returns invalidity for
     * the same reason). Both exist, and each does a job the other cannot: the node's refusal is the doctrinal
     * one — the flag is a property of a **value**, so it belongs inside `compute` (OP-21), and it is what
     * protects any route to a boolean that does not come through here — while this one refuses the *click*,
     * with the body's **name** in it, before a dead element is built for the user to find and delete.
     *
     * Refusing a gesture on a value is normally forbidden, because a step whose replay depends on a value can
     * come back different. It is safe here for a stated reason: the flag is a pure function of a **frozen
     * literal** ([constructit.geom.Feature3.Imported.openShell]) — the triangles the step itself carries — so
     * no recorded step can ever have been written while this said yes and replay it while it says no.
     */
    private fun openShellRefusal(el: Element): String? {
        val f = (Evaluator().valueOf(el.ref) as? SolidValue)?.solid?.feature as? Feature3.Imported ?: return null
        f.openShell ?: return null
        return "${nameOf(el)} is an open shell — a boolean needs watertight operands. It came in that way " +
            "from ${f.source}; it still displays, places and exports (but not to 3MF or STL)."
    }

    /**
     * Cut every opening of a wall out of the solid [solidEl] (OP-21's 3D half, by way of OP-22): one box
     * per interval — width and position along the leg, the wall's full thickness across it, sill to head
     * in z — subtracted in one chain, giving **one** new solid.
     *
     * Each box is wired to the interval's own live parameters, so dragging or typing a position, a width,
     * a sill or a head moves the cut; the wall's carrier does too. What is *structural* is how many
     * openings there are: the count decides how many nodes exist, exactly as an array's count does, so an
     * opening added afterwards does not retro-cut and the tool is simply run again (see *Structural count*
     * in DESIGN.md). Deleting an opening does take the cut with it — a delete replays the surviving
     * script, so the chain is rebuilt with one box fewer.
     *
     * The **first** thick path the solid depends on is the one cut, which is the whole of it for a solid
     * extruded from one wall. A solid fused from two walls would only get the first wall's openings — run
     * the tool on each wall's own solid before fusing them, which is also the cheaper construction.
     */
    @Suppress("UNCHECKED_CAST")
    fun cutOpenings(solidEl: Element): Element? {
        if (solidEl.kind != ElementKind.SOLID) return null
        val tp =
            thickNetworks.firstOrNull { dependsOn(solidEl.ref.node, it.footprint.ref.node, HashSet()) }
                ?: run {
                    note =
                        "${nameOf(solidEl)} was not extruded from a wall footprint, so there are no openings to cut — " +
                        "extrude a wall's footprint first, or use Subtract"
                    return null
                }
        if (tp.intervals.isEmpty()) {
            note = "the wall ${nameOf(solidEl)} was extruded from has no openings on it yet — place one with Opening first"
            return null
        }
        var cut = solidEl.ref as SolidRef
        for (iv in tp.intervals) {
            // one op per carrier case, for the same reason the footprint has two (see [ThickCarrier]) —
            // and both read the interval off the *same* leg the jamb does, which is why a curved wall's
            // opening cuts with no case of its own
            val region =
                when (val c = tp.carrier) {
                    is ThickCarrier.Ortho ->
                        cx.intervalFootprint(c.vertices, tp.thickness, c.closed, c.justification, iv.legIndex, iv.position, iv.width)
                    is ThickCarrier.Network ->
                        cx.networkIntervalFootprint(c.curves.map { it.ref }, c.sides, tp.thickness, iv.legIndex, iv.position, iv.width)
                }
            val box = cx.extrude(cx.sketchOn(cx.planeOffset(cx.planeXY(), iv.sill), region), cx.sub(iv.head, iv.sill))
            cut = cx.subtract(cut, box)
        }
        val n = tp.intervals.size
        return add(cut, ElementKind.SOLID, Styles.SOLID)
            .also { madeSolid(it, "${nameOf(solidEl)} with $n opening${if (n == 1) "" else "s"} cut out of it") }
    }

    // ---- cutting with an unbounded chain (OP-22's extension, step 1) ----

    /**
     * A **cutting chain** through the points that were clicked (OP-22's extension): the finite run between
     * them, with a ray running out of each end.
     *
     * The gesture places ordinary 2D points, so a chain is live in the way everything else here is — drag a
     * point and every cut made with it recomputes — and clicking an *existing* point shares its node, since
     * that is what a point pick already does (`Editor.placePoint` snaps first). Two clicks give an infinite
     * **line**; each further click bends it.
     *
     * Refused **by name** for the one thing that is about *how many* points there are, and refused there
     * rather than in the node because a run of one point is not a run. Everything about *where* they are —
     * two of them in the same place, a chain that crosses itself — is the node's business and is reported as
     * the reason it is invalid, so it heals when the drawing moves (OP-3,
     * [Construction.chainThrough]).
     */
    fun chainThroughPoints(points: List<PointRef>): Element? {
        if (points.size < 2) {
            note = "Chain: click at least two points — one point states no direction, so there is no ray to continue it"
            return null
        }
        val chain = add(cx.chainThrough(points), ElementKind.CHAIN, Styles.CHAIN)
        note =
            "${nameOf(chain)} is a cutting chain through ${points.size} points, running to infinity at both ends — " +
            "cut a solid with it, or split one in two"
        return chain
    }

    /**
     * [el] as a chain to cut with, or null when it does not separate its plane at all.
     *
     * **Three ways in, one operator** — which is the whole of "one operator, not a ladder": what the slot
     * takes is anything that *is* a proper curve in the sense [Chain] states, however the drawing came to
     * hold it.
     *
     * - a drawn **chain**, the value itself;
     * - an infinite **line** ([Construction.lineChain]), which is the two-point chain said the other way
     *   round — the *Chain* tool's own help has always conceded it (*"two clicks give an infinite line"*), so
     *   refusing the line already in the drawing was refusing the very curve the tool would have drawn. And a
     *   line is what the construction tools *produce*: the **mirror image of a line is a line**, so a
     *   symmetric pair of cuts is one mirror rather than a second chain aimed by eye;
     * - anything **closed** ([Construction.closedChain]) — a circle, a traced outline, a rectangle, a wall
     *   footprint — coerced exactly as a curve that bounds an area is coerced into a region for the seam
     *   ([regionOf]), so the through-bore is the ordinary cut.
     *
     * A **ray** is deliberately not among them, and the reason is the operator's own well-formedness rather
     * than taste: a ray *stops*, and the plane flows round its end, so its complement is one region and
     * "which side" has no referent (see [Chain]). It is refused by name in [chainRefFor], with the line it is
     * one click away from being.
     */
    @Suppress("UNCHECKED_CAST")
    private fun chainOf(el: Element): ChainRef? =
        when (el.kind) {
            ElementKind.CHAIN -> el.ref as ChainRef
            ElementKind.LINE -> cx.lineChain(el.ref as LineRef)
            else -> regionOf(el)?.let { cx.closedChain(it) }
        }

    /** Whether [el] can fill a chain slot: a drawn chain, an infinite line, or anything closed enough to bound an area. */
    fun isChainCandidate(
        el: Element,
        ev: Evaluator,
    ): Boolean = el.kind == ElementKind.CHAIN || el.kind == ElementKind.LINE || areaPickFilter(ev)(el)

    /**
     * **Cut** the solid [solidEl] with the chain [chainEl], keeping the side the click at [at] is on — and,
     * where [alongEl] names one, carrying the chain along that **curve in space** in the [carry] stated
     * (OP-22's extension, step 2).
     *
     * A cut *is* a split with one half kept, which is why this builds the same node
     * ([Construction.splitSolid]) as [splitByChain] and differs only in how many halves become elements. The
     * kept side is a **discrete choice scored once from the gesture** and then persisted in the step's
     * `signs=` (OP-1/OP-18): [signs] is what a replay hands back, and when it is present nothing is scored
     * again — so an edit that moves the chain across the body keeps the half the user chose instead of
     * quietly swapping to the other one.
     *
     * The **mode is structural and it is the tool row that states it** — two rows, as a helix's two
     * handednesses are two rows (OP-18) — so the file records it by recording which tool ran, replay never
     * infers it from geometry, and there is no number anywhere that could drift into the other answer.
     *
     * **Which plane the cutting fence stands normal to is the *chain's own space*, never the active one** —
     * `planeOfSpace(chainEl.space)`, which is what this has always computed and is now what the gesture can
     * actually reach. A chain is a curve drawn *in a plane*; the fence is its prism along that plane's
     * normal, so a chain drawn in the plan cuts vertically whichever space happens to be showing when the
     * solid is picked. That is what lets the two picks span spaces at all (the row declares
     * [ToolDef.crossSpace] for OP-22's own reason — a solid is a **body**, not a drawing), and it is stable
     * under replay for the reason it needs no argument of its own: the step names the **chain element**, and
     * which space an element was drawn in is a fact of the drawing rather than of the step. No file changes
     * meaning, because before the picks could span spaces a chain was only pickable in the active space
     * ([addressableIn]) — so the two readings coincided on every file any build could have written, and this
     * one is the reading that goes on being true once they can differ. No new argument, **no version bump**
     * (OP-18).
     */
    @Suppress("UNCHECKED_CAST")
    fun cutByChain(
        solidEl: Element,
        chainEl: Element,
        at: Vec2? = null,
        signs: List<Int> = emptyList(),
        alongEl: Element? = null,
        carry: CarryMode = CarryMode.ROTATING,
    ): Element? {
        val chain = chainRefFor(solidEl, chainEl) ?: return null
        val along = if (alongEl == null) null else (spaceCurveRef(alongEl, "Cut by chain") ?: return null)
        // **The side is clicked in the chain's own space, and it has to be said now that the picks may span
        // spaces.** A click is a bare position; [Chains.sideAt] reads it in the chain's plane, and until the
        // boolean's `crossSpace` reached this row the two could not differ — a chain was only pickable where
        // it was drawn. They can now, and a position carried across a switch would be scored against a line it
        // does not share coordinates with: the wrong half, kept silently, and then frozen into `signs=` where
        // OP-1 guarantees it is never reconsidered. So it is a gesture refusal, by name, with the way forward.
        // A replay never comes here — it is handed the sign it recorded — which is what keeps every recorded
        // `clicks=` position in one stated frame: the chain's.
        if (signs.isEmpty() && at != null && activeSpace.name != chainEl.space) {
            note =
                "Cut by chain: the side to keep is clicked beside ${nameOf(chainEl)}, which is drawn in " +
                "${spaceLabel(spaceNamed(chainEl.space) ?: activeSpace)} — switch back there and click the side, " +
                "so that \"which half\" is read in the same drawing you pointed at"
            return null
        }
        val side = signs.firstOrNull() ?: sideScoredAt(chain, at)
        val cut =
            add(
                cx.splitSolid(solidEl.ref as SolidRef, chain, planeOfSpace(chainEl.space), side, along, carry),
                ElementKind.SOLID,
                Styles.SOLID,
            )
        registerSigns(cut, listOf(side))
        madeSolid(cut, "${nameOf(solidEl)} cut by ${nameOf(chainEl)}${alongWord(alongEl, carry)}, keeping the ${sideWord(side)} side")
        return cut
    }

    /**
     * **Split** the solid [solidEl] in two along the chain [chainEl]: both halves, as two solids — carried
     * along [alongEl] in the [carry] stated where one is named.
     *
     * The general operation, and the one a clamshell housing is. Nothing is scored here and nothing is
     * persisted beyond the step itself: the pair is *ordered* by the chain's own direction of travel — left
     * half first — which is a property of the value, so replay rebuilds the same two bodies without a choice
     * having been recorded (OP-1's ordered solution set, with both branches taken instead of one).
     *
     * The fence stands normal to the **chain's own space**, exactly as [cutByChain]'s does and for the same
     * reason — see the note there for why that reading needs no argument and no version bump.
     */
    @Suppress("UNCHECKED_CAST")
    fun splitByChain(
        solidEl: Element,
        chainEl: Element,
        alongEl: Element? = null,
        carry: CarryMode = CarryMode.ROTATING,
    ): Element? {
        val chain = chainRefFor(solidEl, chainEl) ?: return null
        val along = if (alongEl == null) null else (spaceCurveRef(alongEl, "Split by chain") ?: return null)
        val plane = planeOfSpace(chainEl.space)
        val ref = solidEl.ref as SolidRef
        val left = add(cx.splitSolid(ref, chain, plane, 1, along, carry), ElementKind.SOLID, Styles.SOLID)
        val right = add(cx.splitSolid(ref, chain, plane, -1, along, carry), ElementKind.SOLID, Styles.SOLID)
        note =
            "${nameOf(solidEl)} split by ${nameOf(chainEl)}${alongWord(alongEl, carry)} into ${nameOf(left)} " +
            "(the left of the chain's run) and ${nameOf(right)} (its right) — two solids, either of which can be " +
            "hidden or cut again"
        return left
    }

    /** How a status line names the directrix and the mode — nothing at all where the cut runs straight. */
    private fun alongWord(
        alongEl: Element?,
        carry: CarryMode,
    ): String = if (alongEl == null) "" else " swept along ${nameOf(alongEl)} (${carry.word})"

    /** The chain [chainEl] hands a cut, with both gesture refusals made by name — or null. */
    private fun chainRefFor(
        solidEl: Element,
        chainEl: Element,
    ): ChainRef? {
        if (solidEl.kind != ElementKind.SOLID) {
            note = "Cut by chain: ${nameOf(solidEl)} is ${kindWord(solidEl)}, not a solid — click the body to cut first"
            return null
        }
        openShellRefusal(solidEl)?.let {
            note = it
            return null
        }
        // A ray is the one near miss worth its own sentence: it looks like half a chain and is refused for a
        // reason of the operator rather than of the tool — it stops, so the plane closes round its end and
        // there are not two sides to choose between (see [chainOf] and [Chain]).
        if (chainEl.kind == ElementKind.RAY) {
            note =
                "Cut by chain: ${nameOf(chainEl)} is a ray — it stops, so the plane flows round its end and there is " +
                "no side to keep; cut with the line through it, with a chain, or with anything that closes"
            return null
        }
        return chainOf(chainEl) ?: run {
            note =
                "Cut by chain: ${nameOf(chainEl)} is ${kindWord(chainEl)} — cut with a chain or a line, both of which " +
                "run on for ever, or with anything that closes (a circle, an outline, a rectangle), which separates " +
                "the plane just as well"
            return null
        }
    }

    /**
     * Which side of [chain] a click at [at] means, as the sign the step will persist (OP-1): `+1` the left of
     * the chain's run, `-1` its right. A replay never comes here — it is handed the sign it recorded.
     */
    private fun sideScoredAt(
        chain: ChainRef,
        at: Vec2?,
    ): Int {
        val v = (Evaluator().valueOf(chain) as? ChainValue)?.chain ?: return 1
        return if (at == null) 1 else Chains.sideAt(v, at)
    }

    private fun sideWord(side: Int): String = if (side >= 0) "left" else "right"

    // ---- imported bodies, and the placement that moves any solid (the JT import, OP-9) ----

    /**
     * A **reference body**: the [mesh] a file gave us, at the [pose] that file put it at.
     *
     * One element, one step, and the step carries the mesh itself ([MeshText]) — the only step in the
     * format that holds geometry rather than a description of how to build it, for the only reason that
     * could justify it: an imported body *has* no construction. Its consequence is stated where the
     * encoding is: replay never re-runs a reader, so a library upgrade cannot silently change an old
     * drawing.
     *
     * [pose] is the file's own placement of this body, kept **beside** the vertices rather than multiplied
     * into them, so what the step records is the file's two statements — these triangles, at that pose —
     * and not their product. It is applied by the node, so the value is the body where the file put it.
     *
     * This is the *literal* only. What makes an imported body editable is the [placeSolid] that rides on
     * it, which the importer adds — see `Imports`.
     */
    fun importBody(
        source: String,
        mesh: Mesh3,
        pose: Xform3 = Xform3.IDENTITY,
    ): Element =
        recording(
            "import",
            *listOfNotNull(
                Arg.Keyed("src", Arg.Label(scalarWord(source))),
                Arg.Keyed("pose", Arg.Nums(pose.values().map { Quantity.number(it) })).takeIf { !pose.isIdentity },
                Arg.Keyed("mesh", Arg.Text(MeshText.encode(mesh))),
            ).toTypedArray(),
        ) {
            add(cx.importedSolid(scalarWord(source), mesh, pose), ElementKind.SOLID, Styles.SOLID)
        }

    /**
     * **Place** the solid [el]: read its own coordinates in the active sketch space's frame, at the point
     * [at], turned by [angle] about that space's normal (`cx.placeSolid`).
     *
     * Generic over solids on purpose — an extruded part places exactly as an imported one does, and the
     * import is merely the first caller. What comes out is a *new* solid element whose operand is the one
     * picked, so the original becomes that body's construction material (`isMaterial`) and the 3D view, the
     * preview and every export show one body rather than two, exactly as they do for a boolean.
     *
     * [angle] is optional because the tool's angle slot is defaulted: with nothing typed the placement is a
     * pure move, and the constant that says so is a node like any other.
     */
    @Suppress("UNCHECKED_CAST")
    fun placeSolid(
        el: Element,
        at: PointRef,
        angle: ScalarRef? = null,
    ): Element? {
        if (el.kind != ElementKind.SOLID) return null
        val ref = cx.placeSolid(el.ref as SolidRef, activePlane(), at, angle ?: cx.const(Quantity.deg(0.0)))
        return add(ref, ElementKind.SOLID, Styles.SOLID)
            .also { madeSolid(it, "${nameOf(el)} placed in ${activeSpace.name}") }
    }

    // ---- imported curves: a frozen run, its placement, and the sketch a flat one makes (OP-26, step 9) ----

    /**
     * Which curve elements came **from a file** — the literals and the placements riding them, by runtime id.
     *
     * A set rather than a field on [Element] for [elementMaterials]'s reason: provenance is a fact the *steps*
     * establish, so replay rebuilds it by running [importCurve] and [placeCurve] again, and nothing about it
     * is stored separately from the construction that makes it true.
     *
     * What turns on it is one thing only, and it is a doctrinal thing: [sketchFromWireframe] measures
     * planarity, and a *gesture* may only refuse on a measurement when the measurement cannot change its
     * answer. A frozen literal moved by a rigid placement cannot (OP-9's open-shell argument, session 34,
     * exactly), and a constructed run can — drag one of its points and it stops being flat. So the gesture is
     * offered on these and named as refused on anything else.
     */
    private val importedCurves = HashSet<String>()

    /** Whether [el] is a curve a file brought in — the literal, or a placement of one (see [importedCurves]). */
    fun isImportedRun(el: Element): Boolean = el.id in importedCurves

    /**
     * A **reference run**: the polyline a file gave us, at the [pose] that file put it at (OP-26, step 9).
     *
     * The literal half of an imported curve, and the twin of [importBody] in every respect — one element, one
     * step, and the step carries the points themselves ([PathText]) because an imported run *has* no
     * construction to describe. What rides it is a [placeCurve], which is what makes it movable; the importer
     * adds both, exactly as it does for a body (see `Imports`).
     */
    fun importCurve(
        source: String,
        path: Path3,
        pose: Xform3 = Xform3.IDENTITY,
    ): Element? {
        val text = PathText.encode(path) ?: return null
        return recording(
            "importcurve",
            *listOfNotNull(
                Arg.Keyed("src", Arg.Label(scalarWord(source))),
                Arg.Keyed("pose", Arg.Nums(pose.values().map { Quantity.number(it) })).takeIf { !pose.isIdentity },
                Arg.Keyed("path", Arg.Text(text)),
            ).toTypedArray(),
        ) {
            add(cx.importedPath(scalarWord(source), path, pose), ElementKind.SPACE_CURVE, Styles.SPACE_CURVE)
                .also { importedCurves.add(it.id) }
        }
    }

    /**
     * **Place** the curve in space [el]: read its own coordinates in the active sketch space's frame, at the
     * point [at], turned by [angle] about that space's normal (`cx.placeCurve`).
     *
     * [placeSolid] one dimension down, and generic over runs for the same reason: an import is merely the
     * first caller. What comes out is a *new* curve element whose operand is the one picked, so the literal
     * becomes this run's construction material and the two views draw one run rather than two.
     */
    @Suppress("UNCHECKED_CAST")
    fun placeCurve(
        el: Element,
        at: PointRef,
        angle: ScalarRef? = null,
    ): Element? {
        val runRef = spaceCurveRef(el, "Place curve") ?: return null
        val ref = cx.placeCurve(runRef, activePlane(), at, angle ?: cx.const(Quantity.deg(0.0)))
        val placed = add(ref, ElementKind.SPACE_CURVE, Styles.SPACE_CURVE)
        if (isImportedRun(el)) importedCurves.add(placed.id)
        note = "${nameOf(placed)}: ${nameOf(el)} placed in ${activeSpace.name} — drag the point to move it"
        return placed
    }

    /**
     * **A sketch made from an imported wireframe** (OP-26, step 9): a sketch space on the run's own plane,
     * with the run transcribed into it as ordinary points and segments.
     *
     * **Two recorded steps, and between them they say the whole thing** — the `sketchspace` that puts a plane
     * on the run ([createWireSpace]) and the `wiresketch` that states the geometry. The second carries the
     * transcribed **coordinates**, so a replay never re-measures anything and never re-reads the run: the
     * planarity question is asked once, here, by a person, and its answer is written down (*recorded, never
     * discovered*, OP-23). The points are ordinary free points from that moment on — drag them, weld them,
     * dimension them, trace them into an outline and extrude it — and the step restates where they have been
     * dragged to, exactly as a `point` step does.
     *
     * **Why the sketch lands in the run's own plane rather than in the space you are standing in.** A flat
     * wireframe *states* a plane; transcribing it into a different one would either refuse every run that is
     * not already lying on the active plane, or silently foreshorten it — and the second is the very thing
     * OP-26 forbids. The space is derived from the run, which is the station's own shape (a sketch space whose
     * plane is a node over a `Path3`), so this adds no concept: the sketch therefore **rides the placement**,
     * and dragging the imported body's anchor carries the plane and everything drawn on it along.
     *
     * **Refused by name, building nothing**, for three things — a pick that is not a curve in space; a run
     * that is not imported, because its planarity is either a fact of its construction or a value that can
     * change under a drag, and a gesture may not refuse on one of those; and a run that is **not flat**, with
     * the number it misses a plane by (see [Curves3.planeOfRun] for the tolerance and its argument).
     */
    fun sketchFromWireframe(el: Element): Element? {
        val what = "Sketch from wireframe"
        if (el.kind != ElementKind.SPACE_CURVE) {
            note =
                "$what: ${displayName(el)} is ${kindWord(el)}, not a curve in space — click an imported wireframe " +
                "run; a curve you drew is already in a sketch, and its own space is the one to draw in"
            return null
        }
        if (!isImportedRun(el)) {
            note =
                "$what: ${displayName(el)} was not imported — it is a curve this drawing constructs, so it is already " +
                "made of points you can edit, and whether it is flat is a fact of how it was built rather than " +
                "something to measure"
            return null
        }
        val path = (Evaluator().valueOf(el.ref) as? Path3Value)?.path
        if (path == null) {
            note = "$what: ${displayName(el)} has no run right now"
            return null
        }
        val (plane, why) = Curves3.planeOfRun(path)
        if (plane == null) {
            note = "$what: ${displayName(el)} cannot become a sketch — $why"
            return null
        }
        val points = Curves3.polyline(path)
        // a closed run's polyline ends where it began; the sketch states each point once and closes by saying so
        val open = if (path.closed && points.size > 1) points.dropLast(1) else points
        val local = open.map { plane.toLocal(it) }
        val base = activeSpace
        val space = createWireSpace(el) ?: return null
        val made = traceWireSketch(el, local, path.closed)
        note =
            "${space.name}: ${displayName(el)} traced into a sketch on its own plane — ${local.size} points and " +
            "${made.size} segments you can drag, dimension and build on (${base.name} is one click away in the space list)"
        return made.firstOrNull()
    }

    /**
     * The traced geometry itself, as its own recorded step — the half [sketchFromWireframe] writes down and
     * the **whole** of what a replay runs.
     *
     * Public because the loader is its second caller, and that is the point: the step states the transcribed
     * coordinates, so loading a drawing re-measures no planarity and re-reads no run. [run] is named for
     * provenance — the sketch belongs to that wireframe, and deleting the wireframe takes the space, and hence
     * the sketch, with it, exactly as a station's contents go with its run.
     */
    fun traceWireSketch(
        run: Element,
        local: List<Vec2>,
        closed: Boolean,
    ): List<Element> =
        recording(
            "wiresketch",
            *listOfNotNull(
                Arg.El(run),
                Arg.Keyed("pts", Arg.Positions(local)),
                Arg.Keyed("closed", Arg.Text("1")).takeIf { closed },
            ).toTypedArray(),
        ) {
            transcribe(local, closed)
        }

    /**
     * The sketch space **on an imported run's own plane** (OP-26, step 9) — the station's construction with
     * the one number left out, because a flat run states its plane completely.
     *
     * Recorded as a `sketchspace` step naming the run, so replay rebuilds the space from the same node and the
     * plane is a *derived* thing rather than twelve stored numbers: move the run's placement and the space
     * moves with it. A run that stops being flat makes the plane's node invalid with the reason and everything
     * drawn on it hides until it is flat again (OP-3) — which a rigid placement can never actually cause, and
     * which is why the gesture is allowed to refuse on the same measurement.
     */
    fun createWireSpace(
        curve: Element,
        named: String? = null,
    ): SketchSpace? {
        if (curve.kind != ElementKind.SPACE_CURVE) return null
        val name = named ?: nextDatumName()
        if (spaceNamed(name) != null) return null
        val base = activeSpace
        noteSpaceSwitch()
        return recording("sketchspace", Arg.Label(name), Arg.Keyed("wire", Arg.El(curve))) {
            @Suppress("UNCHECKED_CAST")
            addSpace(SketchSpace(name, null, from = base.name, wire = curve), cx.runPlane(curve.ref as Path3Ref))
        }
    }

    /**
     * The transcription itself: a free point per [local] coordinate and a segment between consecutive ones,
     * closing the ring when [closed] — ordinary sketch geometry in the active space, and nothing else.
     *
     * Free points rather than points derived from the literal, deliberately. What the gesture is *for* is
     * re-engineering: the file's sketch becomes the drawing's own, so every point is a degree of freedom the
     * user owns from here on. Derived points would be unmovable, which would make a traced sketch the one
     * sketch in this editor nobody can edit.
     */
    private fun transcribe(
        local: List<Vec2>,
        closed: Boolean,
    ): List<Element> {
        val pts = local.map { freePoint(Quantity.mm(it.x), Quantity.mm(it.y)) }
        val out = ArrayList<Element>(pts.size)
        for (i in 0 until pts.size - 1) out.add(segment(pts[i], pts[i + 1]))
        if (closed && pts.size >= 3) out.add(segment(pts.last(), pts.first()))
        return out
    }

    /** The named entry driving [ref] — every scalar a tool consumes came from the panel. */
    private fun scalarEntryFor(ref: ScalarRef): ScalarEntry =
        scalars.firstOrNull { it.ref.node === ref.node }
            ?: newParameter("v", (Evaluator().eval(ref.node) as? EvalResult.Ok)?.let { (it.value as? ScalarValue)?.q } ?: 0.0.mm)

    /** [ref]'s value right now, or null when its construction is invalid — the one read every helper below shares. */
    private fun evalQuantity(ref: ScalarRef): Quantity? =
        (Evaluator().eval(ref.node) as? EvalResult.Ok)?.let { (it.value as? ScalarValue)?.q }

    /**
     * [ref] read as millimetres, and **0 for anything that is not a length right now** — an invalid
     * construction as before, and now a value of the wrong dimension too.
     *
     * The second half is not a nicety (OP-27). `Quantity.mm` *throws* on a dimension mismatch (OP-7), and a
     * builder that reads a picked scalar this way throws with it — after the element it was describing has
     * already been added, and before the step that would have owned it was written. That is exactly how a
     * user's tube came to exist with no construction step behind it: the number they picked in the panel was
     * dimensionless, and the tool's own *status message* was what blew up. A tool's inputs are checked where
     * OP-7 says they are, in the node, which reports a reason and heals; nothing on the way to that may
     * throw.
     */
    private fun evalMm(ref: ScalarRef): Double = evalQuantity(ref)?.takeIf { it.dim == Dimension.LENGTH }?.mm ?: 0.0

    /**
     * [ref] **in the words the panel would use** — "12 mm" for the length these notes expect, and the value
     * as it actually stands ("5", "90°") when what was picked is something else.
     *
     * The honest half matters as much as the pretty one: a scalar of the wrong dimension makes the node
     * invalid (OP-7), so the 3D view stays empty, and a note that said "0 mm" would leave the user with no
     * way to connect that empty view to the parameter they picked. What is *wrong* with it is said by
     * [madeSolid], which reads the result rather than guessing from an input.
     */
    private fun lengthWord(ref: ScalarRef): String = Format.quantity(evalQuantity(ref) ?: 0.0.mm)

    /** The thick path [el] is the footprint of, if any. */
    fun thickNetworkOf(el: Element): ThickNetwork? = thickNetworks.firstOrNull { it.footprint === el }

    /**
     * [tp] resolved to values — its legs, the joins between them and the footprint region — or null when
     * the carrier is degenerate right now.
     *
     * The **one seam** between the two carrier cases (see [ThickCarrier]). An ortho carrier goes through the
     * very computation it always did, so its footprint is identical to what every stored `wall` step
     * produced before; a curve network goes through the fat-graph walk. Everything downstream of here —
     * the plan convention, the jambs, the interval clamps — has no case per carrier kind at all.
     */
    fun bodyOf(
        tp: ThickNetwork,
        ev: Evaluator,
    ): ThickBody? =
        when (val c = tp.carrier) {
            is ThickCarrier.Ortho -> {
                val pts = c.vertices.map { ((ev.eval(it.node) as? EvalResult.Ok)?.value as? PointValue)?.p ?: return null }
                // the corner cuts the carrier holds, read the same way the footprint node reads them (#25)
                val cuts =
                    c.vertices.indices.map { i ->
                        val r = c.rounds.getOrNull(i)?.let { scalarMm(it, ev) } ?: 0.0
                        val d = c.bevels.getOrNull(i)?.let { scalarMm(it, ev) } ?: 0.0
                        when {
                            r > Vec2.EPS -> CornerCut.Round(r)
                            d > Vec2.EPS -> CornerCut.Bevel(d)
                            else -> null
                        }
                    }
                GeomMath
                    .thickFaces(pts, c.closed, c.justification.offsets(scalarMm(tp.thickness, ev)), cuts)
                    .first
                    ?.let { thickBodyOf(it).first }
            }
            is ThickCarrier.Network -> {
                val pieces =
                    c.curves.mapIndexed { i, el ->
                        CarrierCurve(carrierPieceOf(ev.valueOf(el.ref) ?: return null) ?: return null, c.sides.getOrElse(i) { Justification.CENTER })
                    }
                thickNetwork(pieces, scalarMm(tp.thickness, ev)).first
            }
        }

    /** A picked curve's value as a carrier piece — the kinds a wall can follow (OP-21 extension). */
    private fun carrierPieceOf(v: Value): ProfileElement? =
        when (v) {
            is SegmentValue -> ProfileElement.Seg(v.seg)
            is ArcValue -> ProfileElement.ArcE(v.arc)
            is BezierValue -> ProfileElement.BezierE(v.bezier)
            is EllipticArcValue -> ProfileElement.EllipticArcE(v.arc)
            is EllipseValue -> ProfileElement.EllipseE(v.ellipse)
            is FuncCurveValue -> ProfileElement.FuncE(v.curve)
            else -> null
        }

    /**
     * The **plan drawing** of [tp] (OP-21): its footprint faces broken at every interval, plus a jamb
     * (reveal) line across the wall at each interval edge, plus the joins (an open end's cap, a step where
     * two offsets cannot mitre).
     *
     * A drawing convention, not a cut — the footprint region itself stays whole, which is what a plan
     * actually shows (below a sill and above a head there is material). Derived here, per render pass,
     * from evaluated values only: the intervals are sorted **by their current position**, so dragging one
     * past another re-sorts the drawing with no rebuild anywhere. That ordering is precisely the work
     * that must not happen while assembling the graph.
     *
     * Emitted as [ProfileElement]s rather than as segments since the OP-21 extension: a curved wall's plan
     * is drawn with the arcs it is made of, not with a barrel of chords.
     */
    fun planOf(
        tp: ThickNetwork,
        ev: Evaluator,
    ): List<ProfileElement>? {
        val body = bodyOf(tp, ev) ?: return null
        val perLeg = (0 until body.legCount).map { intervalsOnLeg(tp, it, ev) }
        val out = ArrayList<ProfileElement>()
        for (side in 0..1) {
            for (i in 0 until body.legCount) {
                val leg = body.legs[i]
                // one run per side, until a T-attachment splits the leg — and then the face on the branch's
                // side has a *gap* where the branch's material joins, which must not be bridged (that bridge
                // is exactly the seam line a wall drawn as two networks showed, GitHub #7). So each run is
                // drawn on its own, with the openings that fall in its own span of the leg (see [FaceRun]).
                val runs = leg.runs[side]
                for (run in runs) {
                    val here =
                        if (runs.size == 1) {
                            perLeg[i]
                        } else {
                            perLeg[i].filter { it.position < run.to - Vec2.EPS && it.position + it.width > run.from + Vec2.EPS }
                        }
                    val pieces = run.pieces
                    var cursor = GeomMath.startOf(pieces.first())
                    for (a in here) {
                        val from = if (runs.size == 1) a.position else maxOf(a.position, run.from)
                        val to = if (runs.size == 1) a.position + a.width else minOf(a.position + a.width, run.to)
                        out.addAll(subRun(pieces, cursor, leg.facePoint(from, side)))
                        cursor = leg.facePoint(to, side) // solid piece, then the gap
                    }
                    out.addAll(subRun(pieces, cursor, GeomMath.endOf(pieces.last())))
                }
            }
        }
        for (j in body.joins) if ((j.b - j.a).length() > Vec2.EPS) out.add(ProfileElement.Seg(j))
        for (j in jambsOn(tp, body, perLeg)) out.add(ProfileElement.Seg(j.seg))
        return out
    }

    /**
     * The stretch of an offset [run] between two points on it — the plan's "solid here, gap there".
     *
     * Kind-preserving, which is the whole reason it exists: on a straight run this is one segment (exactly
     * what the plan always emitted), on an arc run a shorter arc of the same circle, on a sampled run the
     * sub-polyline. Empty when the two points coincide, so a zero-width piece draws nothing.
     */
    private fun subRun(
        run: List<ProfileElement>,
        from: Vec2,
        to: Vec2,
    ): List<ProfileElement> {
        if ((to - from).length() <= Vec2.EPS) return emptyList()
        val single = run.singleOrNull()
        if (single is ProfileElement.ArcE) {
            val c = single.arc.center
            return listOf(
                ProfileElement.ArcE(
                    Arc(c, single.arc.radius, (from - c).angle(), (to - c).angle(), single.arc.ccw),
                ),
            )
        }
        if (single != null) return listOf(ProfileElement.Seg(Segment(from, to)))
        // a sampled run: keep the interior samples that lie between the two cuts
        val pts = listOf(run.first()).map { GeomMath.startOf(it) } + run.map { GeomMath.endOf(it) }
        val dir = (GeomMath.endOf(run.last()) - GeomMath.startOf(run.first())).normalized()
        val lo = (from - pts.first()).dot(dir)
        val hi = (to - pts.first()).dot(dir)
        val inner =
            pts.filter {
                val t = (it - pts.first()).dot(dir)
                t > lo + Vec2.EPS && t < hi - Vec2.EPS
            }
        val chain = listOf(from) + inner + listOf(to)
        return (0 until chain.size - 1).map { ProfileElement.Seg(Segment(chain[it], chain[it + 1])) }
    }

    /** One interval of a leg as the drawing sees it: the feature, plus its position and width right now. */
    private class IntervalAt(val interval: PathInterval, val position: Double, val width: Double)

    /** [tp]'s intervals on leg [i], **ordered by their current position** (see [planOf]). */
    private fun intervalsOnLeg(
        tp: ThickNetwork,
        i: Int,
        ev: Evaluator,
    ): List<IntervalAt> =
        tp.intervals
            .filter { it.legIndex == i }
            .map { IntervalAt(it, scalarMm(it.position, ev), scalarMm(it.width, ev)) }
            .sortedBy { it.position }

    /**
     * The jamb lines of [tp] as the plan draws them, each tagged with the interval it belongs to — the
     * pickable form of the same geometry [planOf] emits, so what looks like a jamb *is* the one that gets
     * grabbed (OP-21).
     */
    fun jambsOf(
        tp: ThickNetwork,
        ev: Evaluator,
    ): List<Jamb> {
        val body = bodyOf(tp, ev) ?: return emptyList()
        return jambsOn(tp, body, (0 until body.legCount).map { intervalsOnLeg(tp, it, ev) })
    }

    private fun jambsOn(
        tp: ThickNetwork,
        body: ThickBody,
        perLeg: List<List<IntervalAt>>,
    ): List<Jamb> {
        val out = ArrayList<Jamb>()
        for (i in 0 until body.legCount) {
            val leg = body.legs[i]
            for (a in perLeg[i]) {
                for (atEnd in listOf(false, true)) {
                    val d = if (atEnd) a.position + a.width else a.position
                    // on an arc leg this comes out **radial**, which is what a plan draws — and it needed no
                    // case of its own, because a jamb was always "the two face points at one arc length"
                    out.add(Jamb(tp, a.interval, atEnd, Segment(leg.facePoint(d, 0), leg.facePoint(d, 1))))
                }
            }
        }
        return out
    }

    /**
     * The drawing of **one** opening: its two jambs, plus the gap span on either face. What selecting a
     * jamb emphasizes, and derived from the same arithmetic as the plan it is drawn over.
     */
    fun intervalOutline(
        tp: ThickNetwork,
        iv: PathInterval,
        ev: Evaluator,
    ): List<Segment> {
        val body = bodyOf(tp, ev) ?: return emptyList()
        val i = iv.legIndex
        if (i !in 0 until body.legCount) return emptyList()
        val leg = body.legs[i]
        val a = scalarMm(iv.position, ev)
        val b = a + scalarMm(iv.width, ev)
        return listOf(
            Segment(leg.facePoint(a, 0), leg.facePoint(a, 1)),
            Segment(leg.facePoint(b, 0), leg.facePoint(b, 1)),
            Segment(leg.facePoint(a, 0), leg.facePoint(b, 0)),
            Segment(leg.facePoint(a, 1), leg.facePoint(b, 1)),
        )
    }

    /**
     * How far along leg [i] of [tp] the world position [at] falls, in mm from that leg's start — the one
     * projection a jamb drag needs (OP-21).
     *
     * Unclamped on purpose: where the cursor *is* and what the model may hold are two questions, and the
     * clamping belongs to the write ([setIntervalPosition], [setIntervalWidth]) so that a typed value is
     * bounded by exactly the same rule.
     */
    fun positionAlongLeg(
        tp: ThickNetwork,
        i: Int,
        at: Vec2,
        ev: Evaluator = Evaluator(),
    ): Double? {
        val body = bodyOf(tp, ev) ?: return null
        if (i !in 0 until body.legCount) return null
        // arc length along the leg, whatever kind of curve it is: the angle about the centre times the
        // radius for an arc, the cumulative polyline length for a Bézier (the OP-21 extension)
        return body.legs[i].distanceAt(at)
    }

    /** The current length of leg [i] of [tp] — the extent every interval on it is bounded by. */
    fun legLengthOf(
        tp: ThickNetwork,
        i: Int,
        ev: Evaluator = Evaluator(),
    ): Double? {
        val body = bodyOf(tp, ev) ?: return null
        return body.legs.getOrNull(i)?.length
    }

    /**
     * Slide [iv] to [mm] along its leg — what dragging its **leading** jamb writes, and what typing its
     * position writes (OP-13: one operation, hence one rule, here).
     *
     * The width is measured *from* the position (leg-relative, OP-21), so the whole opening moves and keeps
     * its size. **Clamped** to the leg's extent: an opening hanging past the corner is not a plan anyone
     * means, and the two ways it can be asked for — a cursor beyond the wall's end, a number typed too
     * large — deserve the same answer. The clamp is said out loud through [note], because geometry that
     * silently stops following the pointer reads as a bug.
     */
    fun setIntervalPosition(
        tp: ThickNetwork,
        iv: PathInterval,
        mm: Double,
    ): Boolean {
        val node = iv.position.node as? ParameterNode ?: return false
        if (node.boundTo != null) {
            note = "This opening's position is wired to another parameter — set that one instead"
            return false
        }
        val len = legLengthOf(tp, iv.legIndex) ?: return false
        val w = evalMm(iv.width)
        val max = maxOf(0.0, len - w)
        val want = mm.coerceIn(0.0, max)
        node.literal = ScalarValue(want.mm)
        note =
            if (kotlin.math.abs(want - mm) <= Vec2.EPS) {
                null
            } else {
                "Opening kept on its leg: position ${Format.num(want)} mm (0…${Format.num(max)} for a " +
                    "${Format.num(w)} mm opening on a ${Format.num(len)} mm leg)"
            }
        return true
    }

    /**
     * Put [iv]'s **trailing** edge at [mm] along its leg — what dragging its end jamb writes. The leading
     * edge stays where it is, so this *is* a write of the width, and it goes through the same clamp.
     */
    fun setIntervalEnd(
        tp: ThickNetwork,
        iv: PathInterval,
        mm: Double,
    ): Boolean = setIntervalWidth(tp, iv, mm - evalMm(iv.position))

    /**
     * Set [iv]'s width to [mm], clamped to `(0, legLength − pos]`.
     *
     * A width of zero or less would put the trailing jamb on or past the leading one — a crossed-over
     * opening, which is not a narrower opening but a broken drawing — so it is **refused**, clamped to
     * [MIN_INTERVAL_WIDTH] with the reason in [note]. Clamping rather than ignoring the write is what keeps
     * the gesture reversible: dragging the end jamb back out grows the opening again from where it stopped.
     *
     * The width may be a parameter **shared** with other openings (that is how two of them are made the
     * same size by construction rather than by a constraint), and then this resizes all of them. Invisible
     * if the others are off screen, so it is named.
     */
    fun setIntervalWidth(
        tp: ThickNetwork,
        iv: PathInterval,
        mm: Double,
    ): Boolean {
        val node = iv.width.node as? ParameterNode ?: return false
        if (node.boundTo != null) {
            note = "This opening's width is wired to another parameter — set that one instead"
            return false
        }
        val len = legLengthOf(tp, iv.legIndex) ?: return false
        val pos = evalMm(iv.position)
        val max = maxOf(MIN_INTERVAL_WIDTH, len - pos)
        val want = mm.coerceIn(MIN_INTERVAL_WIDTH, max)
        node.literal = ScalarValue(want.mm)
        val shared = thickNetworks.sumOf { p -> p.intervals.count { it !== iv && it.width.node === node } }
        note =
            when {
                kotlin.math.abs(want - mm) > Vec2.EPS && mm <= 0.0 ->
                    "An opening cannot be closed by crossing its jambs: width held at ${Format.num(want)} mm — " +
                        "delete the opening instead"
                kotlin.math.abs(want - mm) > Vec2.EPS ->
                    "Opening kept on its leg: width ${Format.num(want)} mm (at most ${Format.num(max)} mm from " +
                        "${Format.num(pos)} mm along a ${Format.num(len)} mm leg)"
                shared > 0 ->
                    "Width ${Format.num(want)} mm — this parameter is shared with $shared other opening" +
                        "${if (shared == 1) "" else "s"}, which resize with it"
                else -> null
            }
        return true
    }

    /**
     * Write one of an interval's carried heights — its sill or head (OP-21's 3D half). No clamp: unlike the
     * position and the width, a height is bounded by nothing the 2D drawing knows, and a head below its
     * sill is an invalid solid with a reason (OP-3), not a wrong one.
     */
    fun setIntervalHeight(
        ref: ScalarRef,
        mm: Double,
    ): Boolean {
        val node = ref.node as? ParameterNode ?: return false
        if (node.boundTo != null) return false
        node.literal = ScalarValue(mm.mm)
        return true
    }

    /**
     * [ref] in millimetres for a *geometric* read, 0 for anything that is not a length right now —
     * [evalMm]'s twin, dimension-safe for the same reason (OP-27): this one is asked at **draw** time, so
     * a wall whose thickness parameter is dimensionless would otherwise throw out of the renderer rather
     * than simply drawing nothing.
     */
    private fun scalarMm(
        ref: ScalarRef,
        ev: Evaluator,
    ): Double =
        ((ev.eval(ref.node) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.takeIf { it.dim == Dimension.LENGTH }?.mm ?: 0.0

    /**
     * Add an interval feature to leg [legIndex] of [tp] (the UI's door/window opening) at [position]
     * along it, spanning [width], carrying [sill] and [head] for the solid (OP-17).
     *
     * Position and the two heights become named parameters, so every value of an interval is a typed
     * field (OP-13); the width is shared with whatever the tool was given, which is how two openings
     * are made the same size *by construction* rather than by a constraint. Nothing is regenerated —
     * the footprint node is not even touched, and the plan drawing re-derives itself.
     */
    fun addInterval(
        tp: ThickNetwork,
        legIndex: Int,
        position: Quantity,
        width: ScalarRef,
        sill: Quantity,
        head: Quantity,
    ): PathInterval? {
        if (legIndex < 0 || legIndex >= tp.legCount) return null
        return recording(
            "opening",
            Arg.El(tp.footprint),
            Arg.Keyed("leg", Arg.Text(legIndex.toString())),
            Arg.Keyed("pos", Arg.Num(position)),
            Arg.Keyed("width", Arg.Sc(scalarEntryFor(width))),
            Arg.Keyed("sill", Arg.Num(sill)),
            Arg.Keyed("head", Arg.Num(head)),
        ) {
            PathInterval(
                legIndex,
                newParameter("pos", position).ref,
                width,
                newParameter("sill", sill).ref,
                newParameter("head", head).ref,
            ).also { tp.intervals.add(it) }
        }
    }

    /**
     * Add an interval of width [width] to whichever thick-path leg is nearest [at], centred on the
     * click. Resolving the click is the *tool's* job; what gets recorded is the resolved description
     * (which leg, how far along), so a replay never re-guesses. No-op if nothing is within tolerance.
     */
    fun addIntervalAt(
        at: Vec2,
        width: ScalarRef,
        tol: Double,
    ): Boolean {
        val ev = Evaluator()
        var best: ThickNetwork? = null
        var bestLeg = -1
        var bestPos = 0.0
        var bestLen = 0.0
        var bestD = Double.MAX_VALUE
        for (tp in thickNetworks) {
            val threshold = tol + evalMm(tp.thickness) / 2 // clicking anywhere on the body counts
            val body = bodyOf(tp, ev) ?: continue
            for (i in 0 until body.legCount) {
                val leg = body.legs[i]
                val along = leg.distanceAt(at).coerceIn(0.0, leg.length)
                val d = (at - leg.pointAt(along)).length()
                if (d <= threshold && d < bestD) {
                    bestD = d
                    best = tp
                    bestLeg = i
                    bestPos = along
                    bestLen = leg.length
                }
            }
        }
        val tp = best ?: return false
        val widthVal = evalMm(width)
        val pos = (bestPos - widthVal / 2).coerceIn(0.0, maxOf(0.0, bestLen - widthVal)) // centre on the click
        return addInterval(tp, bestLeg, pos.mm, width, 0.0.mm, DEFAULT_HEAD.mm) != null
    }

    fun ray(
        a: PointRef,
        b: PointRef,
    ) = add(cx.ray(a, b), ElementKind.RAY, Styles.CURVE)

    /**
     * The circle centred at [center] through [through] — whose second point **lies on it by construction**,
     * which is the fact the incidence registry records (GitHub #19): the radius point is exactly as good a
     * place to raise a tangent as a rider is, and refusing it was the tool asking for the wrong thing.
     */
    @Suppress("UNCHECKED_CAST")
    fun circle(
        center: PointRef,
        through: PointRef,
    ) = add(cx.circleCP(center, through), ElementKind.CIRCLE, Styles.CURVE)
        .also { stateOnCircle(through, it.ref as CircleRef, it) }

    fun circleCR(
        center: PointRef,
        radius: ScalarRef,
    ) = add(cx.circleCR(center, radius), ElementKind.CIRCLE, Styles.CURVE)

    /** The circle through three points — all three of which lie on it by construction ([circle]'s note). */
    @Suppress("UNCHECKED_CAST")
    fun circle3(
        a: PointRef,
        b: PointRef,
        c: PointRef,
    ) = add(cx.circle3(a, b, c), ElementKind.CIRCLE, Styles.CURVE)
        .also { stateAllOnCircle(listOf(a, b, c), it.ref as CircleRef, it) }

    /** The arc through three points — all three of which lie on its **carrier** circle by construction. */
    fun arc3(
        a: PointRef,
        b: PointRef,
        c: PointRef,
    ) = add(cx.arc3(a, b, c), ElementKind.ARC, Styles.CURVE)
        .also { stateAllOnCircle(listOf(a, b, c), carrierCircle(it), it) }

    fun arcCenterStartEnd(
        center: PointRef,
        start: PointRef,
        end: PointRef,
    ) = add(cx.arcCenterStartEnd(center, start, end), ElementKind.ARC, Styles.CURVE)

    // ---- conics (OP-24) ----

    /**
     * The ellipse centred at [center] whose own axis runs through [axisEnd] — which fixes the orientation
     * *and* the first semi-axis — with the second read off the third point [bPoint].
     *
     * Three clicks, three nodes, and nothing else: bind [axisEnd] onto a point of a line's direction and
     * the ellipse turns with that line; share [bPoint] with a second ellipse and the two are equally tall
     * by construction (OP-5).
     */
    fun ellipse(
        center: PointRef,
        axisEnd: PointRef,
        bPoint: PointRef,
    ) = add(cx.ellipseCAP(center, axisEnd, bPoint), ElementKind.ELLIPSE, Styles.CURVE)

    /** The same ellipse with the second semi-axis given as a scalar — [ellipse]'s typed twin. */
    fun ellipseCAB(
        center: PointRef,
        axisEnd: PointRef,
        b: ScalarRef,
    ) = add(cx.ellipseCAB(center, axisEnd, b), ElementKind.ELLIPSE, Styles.CURVE)

    /**
     * An **elliptic arc**: the ellipse's own inputs, plus the two points its ends are taken from — which
     * are projected onto the carrier exactly as a circular arc's cut points are ([Construction.arcBetween]).
     *
     * One creation, not two: the carrier ellipse is an ordinary node with no element of its own, the same
     * way *Arc (centre, ends)* builds an arc without leaving a circle behind.
     */
    fun ellipticArc(
        center: PointRef,
        axisEnd: PointRef,
        b: ScalarRef,
        start: PointRef,
        end: PointRef,
    ) = add(
        cx.ellipticArcBetween(cx.ellipseCAB(center, axisEnd, b), start, end, ccw = true),
        ElementKind.ELLIPTIC_ARC,
        Styles.CURVE,
    )

    // ---- function curves: the same expressions, plus a parameter (the session-71 entry, curve half) ----

    /**
     * What a `funccurve` step made: the two texts as written, their ASTs, the scalars they resolved to, and
     * the two source nodes carrying the domain.
     *
     * The exact twin of [ExprBinding], and it exists for the same two reasons: the **text is the record**, so
     * the writer restates this step's own text; and a **rename is a re-stamp**, so the mentions of a scalar
     * that live inside these strings are rewritten in place rather than orphaned ([restampExpressions]).
     */
    class FuncCurveBinding internal constructor(
        val element: Element,
        val xSource: String,
        val ySource: String,
        val xAst: Expr,
        val yAst: Expr,
        val names: List<String>,
        val refs: List<ExprRef>,
        val param: String,
        internal val from: DomainEnd,
        internal val to: DomainEnd,
    ) {
        /** The two texts under the **current** names of what they read — what a save writes. */
        var xText: String = xSource

        var yText: String = ySource

        /** Every scalar row this curve reads, its domain included — what the delete cascade follows. */
        internal fun scalarRefs(): List<ScalarEntry> =
            (refs + from.refs + to.refs).mapNotNull { it.entry }

        /** Every point whose coordinate this curve reads, its domain included (session-76 item a). */
        internal fun pointRefs(): List<Element> = (refs + from.refs + to.refs).mapNotNull { it.point }
    }

    /**
     * One **end of a function curve's domain** (the session-76 entry, item b): a plain number, or an
     * expression over named scalars.
     *
     * The plain number is the degenerate case and is left exactly as it was — [node] carries the literal, the
     * inspector's field writes it, and the step restates the number — so no stored file changes meaning. An
     * expression is the *same* mechanism the scalar half built, one argument on: [node] is **bound** to an
     * [ExprNode] ([SourceNode.boundTo], which the wire generalized to a function), so the domain follows a
     * teeth count by ordinary recompute, the field goes read-only by itself (nothing writable is under a
     * binding — [isFreeSource]), and the step restates the **text** instead of the number.
     *
     * [source] is what the user wrote and [text] the same expression under the current names of what it
     * reads; both null for the plain-number case.
     */
    class DomainEnd internal constructor(
        internal val node: SourceNode,
        val source: String?,
        internal val ast: Expr?,
        internal val names: List<String>,
        internal val refs: List<ExprRef>,
    ) {
        var text: String? = source

        /** The number this end currently runs to, whatever drives it. */
        internal fun value(): Double? = ((Evaluator().eval(node) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.base
    }

    private val funcCurves = HashMap<Step, FuncCurveBinding>()
    private var pendingFuncCurve: FuncCurveBinding? = null

    /** The binding [step] recorded, or null — how the writer restates that step's own two texts. */
    internal fun funcCurveBinding(step: Step): FuncCurveBinding? = funcCurves[step]

    /** The curve [el] is, or null — what the inspector and a refusal quote. */
    fun funcCurveOf(el: Element): FuncCurveBinding? = funcCurves.values.firstOrNull { it.element === el }

    /** The parameter name every function curve's expressions are read in — see [FUNC_PARAM_NOTE]. */
    val funcCurveParam: String get() = "t"

    /**
     * A **function curve** from the two texts [xText] and [yText] over the domain [t0]..[t1] — the whole of
     * the curve half's vocabulary, and deliberately no primitive per curve family (the user's own design:
     * *"allow to define such curve segments using arbitrary functions — with an involute as example"*).
     *
     * Everything it can refuse, it refuses **by name** and before anything is built: a text that is not an
     * expression (with the character position and what was expected there), and a name nothing in the
     * drawing carries (with the cure, through [unknownName] — the hyphenated scalar and the function written
     * without its arguments both speak). Everything about the *values* — a domain that does not run
     * forwards, an expression that leaves its own domain part-way, a coordinate that is not a length — is
     * the node's business and comes back as the named invalidity that heals (OP-3).
     *
     * The **domain** may be a number or an expression, [fromText]/[toText] carrying the latter (the
     * session-76 entry, item b): a gear flank's length then follows a teeth count like everything else. A
     * plain-number domain is the degenerate case and is stored and restated exactly as before.
     */
    fun functionCurve(
        xText: String,
        yText: String,
        t0: Double,
        t1: Double,
        fromText: String? = null,
        toText: String? = null,
    ): Element? =
        recording("funccurve", Arg.Label(xText), Arg.Label(yText), skipIfEmpty = true) {
            functionCurveNow(xText, yText, t0, t1, fromText, toText)
        }

    private fun functionCurveNow(
        xText: String,
        yText: String,
        t0: Double,
        t1: Double,
        fromText: String?,
        toText: String?,
    ): Element? {
        val param = funcCurveParam
        val xAst =
            try {
                ExprParser.parse(xText)
            } catch (err: ExprError) {
                note = "Can't read x($param) from '${xText.trim()}': ${err.message}"
                return null
            }
        val yAst =
            try {
                ExprParser.parse(yText)
            } catch (err: ExprError) {
                note = "Can't read y($param) from '${yText.trim()}': ${err.message}"
                return null
            }
        val names = ArrayList<String>()
        val refs = ArrayList<ExprRef>()
        for (n in (xAst.refNames() + yAst.refNames()).distinct()) {
            // the parameter is a **binder** and wins over everything, which is what makes `cos(t)` mean
            // what it says; a constant is what is left when nothing in the drawing carries the name
            if (n == param) continue
            val target = resolveExprName(n)
            if (target == null) {
                if (n in EXPR_CONSTANTS) continue
                note = "Can't build the curve: ${unknownName(n)}"
                return null
            }
            names.add(n)
            refs.add(target)
        }
        val a = domainEnd(t0, fromText, "from") ?: return null
        val b = domainEnd(t1, toText, "to") ?: return null
        val ref =
            cx.funcCurve(
                xAst,
                yAst,
                names,
                refs.map { Ref<ScalarValue>(it.node) },
                Ref<ScalarValue>(a.node),
                Ref<ScalarValue>(b.node),
                param,
                text = "x($param) = $xText, y($param) = $yText",
            )
        val el = add(ref, ElementKind.FUNC_CURVE, Styles.CURVE)
        el.handle = FuncCurveHandle(a.node, b.node)
        pendingFuncCurve = FuncCurveBinding(el, xText, yText, xAst, yAst, names, refs, param, a, b)
        return el
    }

    /**
     * One end of the domain: the literal [value], or [text] parsed as an expression over named scalars and
     * **bound over** that literal (the session-76 entry, item b). Null with a note when the text is not an
     * expression or names something the drawing does not carry — refused by name, before anything is built.
     *
     * A domain expression is deliberately *not* checked for dimension here: the domain must be dimensionless,
     * but that is a property of the **values** and therefore the node's own named invalidity that heals
     * (OP-3), exactly as a coordinate that is not a length is. `t from T` with `T` a length says so, quotes
     * the dimensions, and comes back the moment `T` is a plain number.
     *
     * Note the one asymmetry with the curve's coordinates, and it is forced rather than chosen: inside `x(t)`
     * the name `t` is the curve's own **binder**, while a domain *bounds* `t` and cannot depend on it, so
     * there `t` is an ordinary drawing scalar. Nothing can be captured, so nothing is refused.
     */
    private fun domainEnd(
        value: Double,
        text: String?,
        which: String,
    ): DomainEnd? {
        val node = SourceNode(nextId("ft"), ScalarValue(Quantity.number(value)))
        if (text == null) return DomainEnd(node, null, null, emptyList(), emptyList())
        val ast =
            try {
                ExprParser.parse(text)
            } catch (err: ExprError) {
                note = "Can't read the curve's $which from '${text.trim()}': ${err.message}"
                return null
            }
        val names = ArrayList<String>()
        val refs = ArrayList<ExprRef>()
        for (n in ast.refNames()) {
            val target = resolveExprName(n)
            if (target == null) {
                if (n in EXPR_CONSTANTS) continue
                note = "Can't read the curve's $which: ${unknownName(n)}"
                return null
            }
            names.add(n)
            refs.add(target)
        }
        node.boundTo = ExprNode(nextId("ex"), text, ast, names, refs.map { it.node })
        return DomainEnd(node, text, ast, names, refs)
    }

    /**
     * The domain [el] currently runs over — what its own step restates (OP-18: state restates as a value).
     *
     * Each end is a **number or a text**: a number is state and is restated as one, a text is a *formula* and
     * is restated verbatim under the current names of what it reads (the session-76 entry, item b). One end
     * may be each, since they are two independent arguments.
     */
    internal fun funcCurveDomain(el: Element): Pair<Arg, Arg>? =
        funcCurveOf(el)?.let { b ->
            fun end(d: DomainEnd): Arg? = d.text?.let { Arg.Label(it) } ?: d.value()?.let { Arg.Num(Quantity.number(it)) }
            val lo = end(b.from) ?: return null
            val hi = end(b.to) ?: return null
            lo to hi
        }

    /**
     * Why a tangent-dependent construction cannot use [el], or null when it can (the session-69 predicate
     * rule): a function curve whose derivative the vocabulary cannot state has no tangent to be anchored on,
     * and the honest answer is to say which function stopped it rather than to difference numerically.
     */
    fun funcTangentRefusal(el: Element): String? {
        val c = (Evaluator().valueOf(el.ref) as? FuncCurveValue)?.curve ?: return null
        return c.noTangent?.let { "${nameOf(el)}: $it" }
    }

    // ---- relational constructions ----

    /**
     * The perpendicular bisector of `a → b`, or — with a [factor] — the perpendicular through *that* point of
     * the span, which is the same construction with the 0.5 relaxed (see [midpoint]).
     *
     * Composed from ops that already exist rather than a new one: the ratio point, plus the perpendicular to
     * the line through the two points at it. The ratio point is an element of its own, so the factor is
     * draggable on the canvas as well as typeable (OP-13).
     */
    fun perpBisector(
        a: PointRef,
        b: PointRef,
        factor: ScalarRef? = null,
    ) = if (factor == null) {
        add(cx.perpBisector(a, b), ElementKind.LINE, Styles.CONSTRUCT)
    } else {
        add(cx.perpendicularThrough(cx.lineThrough(a, b), ratioPoint(a, b, factor)), ElementKind.LINE, Styles.CONSTRUCT)
    }

    fun angleBisector(
        a: PointRef,
        v: PointRef,
        b: PointRef,
    ) = add(cx.angleBisector(a, v, b), ElementKind.LINE, Styles.CONSTRUCT)

    fun perpendicularThrough(
        line: Element,
        p: PointRef,
    ) = add(cx.perpendicularThrough(carrierLine(line), p), ElementKind.LINE, Styles.CONSTRUCT)

    /**
     * The tangent to a circle at a point that lies on it **by construction** (GitHub #19).
     *
     * The criterion is the construction's, not the picture's ([circlesThrough]): a rider, the radius-defining
     * point of a circle drawn from two points, one of the three a circle was fitted through, an end of an
     * arc, a crossing with a circle among its operands, a tangency. It used to be *"the point's handle is an
     * [OnCircleHandle]"*, i.e. **a rider and nothing else**, which refused the very point that defines the
     * radius — a point that determines the tangent as unambiguously as any rider does.
     *
     * *Unambiguously* fails in exactly one place, and it is a fact about the geometry rather than about this
     * tool: a **circle ∩ circle** point lies on two circles, and there are two different tangents there. So
     * the tool asks for one more click ([Tools] declares that second slot conditional) and the pick is the
     * record — `els=` names the circle, replay takes it verbatim, and nothing is ever scored again (OP-1,
     * OP-18). Where a replay finds an ambiguity nobody resolved, it refuses by name rather than choosing.
     */
    fun tangentAtPointOnCircle(
        pointEl: Element,
        circleEl: Element? = null,
    ) {
        val on = circlesThrough(pointEl)
        val chosen =
            when {
                on.isEmpty() -> {
                    note =
                        "Tangent at point: ${nameOf(pointEl)} does not lie on a circle by construction — a " +
                        "tangent needs a point the drawing puts on one: a circle's own radius point, a point " +
                        "of a circle fitted through three, an end of an arc, a crossing with a circle, a " +
                        "tangency, or a point riding a circle"
                    null
                }
                circleEl != null ->
                    on.firstOrNull { it.host === circleEl } ?: run {
                        note =
                            "Tangent at point: ${nameOf(pointEl)} does not lie on ${nameOf(circleEl)} by " +
                            "construction — it lies on ${namesOf(on)}; click one of those"
                        null
                    }
                on.size == 1 -> on[0]
                else -> {
                    note =
                        "Tangent at point: ${nameOf(pointEl)} lies on ${namesOf(on)}, so the tangent there is " +
                        "two different lines — click the circle the tangent is to"
                    null
                }
            } ?: return
        add(cx.tangentAtCircle(chosen.carrier, pointEl.ref as PointRef), ElementKind.LINE, Styles.CONSTRUCT)
    }

    /** The circles of [on], named the way a refusal has to name them — "e4 and e7", "e4, e7 and e9". */
    private fun namesOf(on: List<OnCircle>): String {
        val names = on.map { it.host?.let { h -> nameOf(h) } ?: "the circle it rides" }
        return if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    fun parallelThrough(
        line: Element,
        p: PointRef,
    ) = add(cx.parallelThrough(carrierLine(line), p), ElementKind.LINE, Styles.CONSTRUCT)

    // ---- a rounding supersedes its corner: the legs, trimmed in place (GitHub #25, the user's design) ----

    /**
     * Supersede the corner of [leg1] and [leg2] with **its rounded self**: each leg trimmed back to the
     * handover the rounding just registered on it ([t1]/[t2]), the corner point left standing as
     * construction. Returns how many of the two legs were trimmed.
     *
     * This is the fix the reporter named: *"the fillet tool only creates the fillet arc, but does not
     * supersede [the corner] with its filleted version"* (GitHub #25). What they built by hand — key points
     * of the arc, two fresh segments onto them, three `hide` steps — is what one gesture now records, and it
     * records it **without a new element**: the leg is trimmed *in place*, behind the re-pointable view it
     * publishes ([publishedRef]), so `e3` goes on being `e3` and everything built on it — an outline, a
     * dimension, a wall, a rider — follows the trimmed leg with nothing rewired (OP-5, OP-16's precedent).
     *
     * **A leg is trimmed exactly when the handover lies on it.** That is not a shortcut, it is the rule: a
     * rounding is tangent to each leg's *carrier* (see [carrierLine]), and a carrier reaches beyond the drawn
     * piece — a line–circle fillet whose tangency falls outside an arc's sweep is a construction this drawing
     * has always allowed and still means (`ArcCarrierTest`). Where the handover *is* on the piece, the corner
     * is the one the user is rounding and the piece owes it the material; where it is not, there is no corner
     * of that piece there and nothing of it is taken. So every drawing written before this reading keeps
     * exactly the geometry it had, and the ones the report is about gain the trim.
     *
     * A later edit that pulls the handover off the leg is then ordinary invalidity with the reason and the
     * number that would fit ([Construction.trimmedLeg]), healing when it comes back (OP-3) — which is the
     * right answer and not a re-decision: re-scoring *whether* to trim on every recompute would make the
     * drawing's structure a function of its values (OP-21).
     */
    private fun supersedeWithTrim(
        leg1: Element,
        leg2: Element,
        t1: PointRef,
        t2: PointRef,
    ): Int {
        val ev = Evaluator()
        val a = (ev.valueOf(t1) as? PointValue)?.p ?: return 0
        val b = (ev.valueOf(t2) as? PointValue)?.p ?: return 0
        val corner = cornerBetween(leg1, leg2, (a + b) * 0.5, ev) ?: return 0
        // **a path's leg is the path's own to round.** A retained path publishes its boundary as an ordered
        // chain, and the corner piece can only be *in* that chain if the path records it — so a rounding
        // that is not two adjacent legs of one path takes nothing off a leg that belongs to one, or the
        // path's loop would be left with a gap no piece fills. Two legs of different paths meeting at a
        // junction, two legs of one path that are not neighbours, a leg against a plain segment: each stays
        // the rounding against the carriers it has always been.
        if (orthoCornerBetween(leg1, leg2) == null && (legOf(leg1) != null || legOf(leg2) != null)) return 0
        var n = 0
        if (trimLegTo(leg1, t1, a, corner, ev)) n++
        if (trimLegTo(leg2, t2, b, corner, ev)) n++
        if (n == 2) dimCornerPoint(corner, ev)
        return n
    }

    /**
     * Where [leg1] and [leg2] **meet** — the crossing of their two carriers nearest [near], which is where
     * the rounding sits.
     *
     * Nearest rather than first, for [sharedMeetings]' own reason: two curves can cross twice (a chord and
     * its arc), and the corner this rounding is in is the one it stands in.
     */
    private fun cornerBetween(
        leg1: Element,
        leg2: Element,
        near: Vec2,
        ev: Evaluator,
    ): Vec2? {
        val v1 = filletLegOf(leg1, ev) ?: return null
        val v2 = filletLegOf(leg2, ev) ?: return null
        return FilletMath.chamferCorners(v1, v2).minByOrNull { (it - near).length() }
    }

    /**
     * Trim [leg] back to [cut] — keeping the end away from [corner] — when [at] (where [cut] is now) lies on
     * the leg as it stands. False when it does not, or when the leg is a carrier with no ends to move.
     */
    private fun trimLegTo(
        leg: Element,
        cut: PointRef,
        at: Vec2,
        corner: Vec2,
        ev: Evaluator,
    ): Boolean {
        val view = leg.ref.node as? IndirectNode ?: return false
        val piece = pieceRef(leg)
        val v = ev.valueOf(piece) ?: return false
        if (!onPieceNow(v, at)) return false
        val trimmed: Ref<*> =
            when (v) {
                is SegmentValue -> {
                    val keepAtA = (v.seg.a - corner).length() >= (v.seg.b - corner).length()

                    @Suppress("UNCHECKED_CAST")
                    val ref = piece as SegmentRef
                    cx.trimmedLeg(ref, if (keepAtA) cx.segmentStart(ref) else cx.segmentEnd(ref), cut)
                }
                is ArcValue -> {
                    val keepAtStart = (GeomMath.arcStart(v.arc) - corner).length() >= (GeomMath.arcEnd(v.arc) - corner).length()

                    @Suppress("UNCHECKED_CAST")
                    val ref = piece as ArcRef
                    cx.trimmedArcLeg(ref, if (keepAtStart) cx.arcStart(ref) else cx.arcEnd(ref), cut)
                }
                else -> return false
            }
        view.boundTo = trimmed.node
        return true
    }

    /** Whether [at] lies on the bounded piece [v] as it stands — the question [trimLegTo] turns on. */
    private fun onPieceNow(
        v: Value,
        at: Vec2,
    ): Boolean =
        when (v) {
            is SegmentValue -> {
                val d = v.seg.b - v.seg.a
                val len = d.length()
                len > Vec2.EPS && (at - v.seg.a).dot(d * (1.0 / len)).let { it > GeomMath.JOIN_TOL && it < len - GeomMath.JOIN_TOL }
            }
            is ArcValue -> GeomMath.arcContains(v.arc, (at - v.arc.center).angle())
            else -> false
        }

    /**
     * Dim the corner point a rounding replaced: it stays in the drawing, draggable, and reads as the
     * construction it now is (OP-14's third column).
     *
     * Not hidden, which is what the reporter's hand-built version had to do: the point is the corner's own
     * degree of freedom, so dragging it moves both trimmed legs *and* the rounding between them. An ortho
     * path's vertex keeps its own look — there it is the handle the whole path is edited by, not scaffolding.
     */
    private fun dimCornerPoint(
        corner: Vec2,
        ev: Evaluator,
    ) {
        val el =
            elements.lastOrNull { e ->
                e.isPoint && e.style !== Styles.CONSTRUCT && pathOf(e) == null &&
                    ((ev.valueOf(e.ref) as? PointValue)?.p?.let { (it - corner).length() <= GeomMath.JOIN_TOL } == true)
            } ?: return
        el.style = Styles.CONSTRUCT
    }

    /**
     * A fillet of [radius] between two **carrier curves** — a line/segment/ray, a circle or an arc, in any
     * of the three combinations.
     *
     * One tool, because a fillet is one idea: the rounding is the circle of radius r tangent to both legs,
     * and *where its centre is* is the only thing that differs per leg kind — the intersection of two
     * corner-side rays for two lines, of an offset line with a concentric circle for a line and a circle,
     * of two concentric circles for two circles. Every variant is therefore composed of ops that already
     * existed ([Construction.parallelAtDistance], [Construction.concentricCircle], the intersections and
     * `Select`), plus the two accessors that give the tangencies — a projection on a straight leg, a
     * [Construction.radialPoint] on a round one.
     *
     * **Which** variant is a persisted discrete choice (OP-1), decided once from the two clicks by
     * [filletVariantFor] and stored as the signs of that composition: which side of the line, R+r or R−r,
     * and which intersection branch. Nothing is re-derived later, so editing the radius moves the fillet and
     * never re-picks a different one; a radius too large for any tangency makes the node invalid with a
     * reason and heals when it comes back down (OP-3).
     *
     * *Persisted* now means persisted **in the file** too (OP-18): given [signs] — the step's own
     * `signs=`, restated on save — the variant is taken verbatim and the scoring does not run. It used to
     * run again on every load, against whatever the geometry had become since, so a reload could hand back a
     * different one of the eight variants than the clicks chose (*"fillets inverted, producing sharp
     * corners"*). Scoring happens exactly once: when the user clicks.
     *
     * The arc is emitted **quietly** — one element, no visible points — as the line-line fillet always was;
     * the tangencies are registered as joints instead ([registerJoint]), which is what lets a filleted chain
     * be traced by the Outline tool's boundary-follow.
     */
    fun filletBetweenCurves(
        leg1: Element,
        leg2: Element,
        radius: ScalarRef,
        clickA: Vec2,
        clickB: Vec2,
        signs: List<Int> = emptyList(),
    ): Element? =
        when {
            leg1.isLinear && leg2.isLinear -> filletLineLine(leg1, leg2, radius, clickA, clickB, signs)
            isFilletLeg(leg1) && isFilletLeg(leg2) -> filletMixed(leg1, leg2, radius, clickA, clickB, signs)
            else -> {
                // named rather than dropped: a leg has to carry a line or a circle for the rounding to be
                // *tangent by construction*, and a spline, a conic and a function curve carry none — their
                // offsets are not curves of their own kind (OP-15), so a fillet against one could only be
                // fitted. That is the chamfer-on-arc convention's own spirit, said out loud.
                val bad = listOf(leg1, leg2).firstOrNull { !isFilletLeg(it) }
                if (bad != null) {
                    note = "Fillet: ${nameOf(bad)} is ${kindWord(bad)}, and a rounding is tangent by " +
                        "construction to a line or a circle — pick a line, a segment, a circle or an arc"
                }
                null
            }
        }

    /** Whether [el] can be a fillet leg at all: it must carry a line or a circle to be tangent to. */
    private fun isFilletLeg(el: Element): Boolean = el.isLinear || el.isCentric

    /**
     * The straight-leg case, unchanged: [Construction.filletBetweenLines] computes corner, bisector and
     * both tangencies in one op, and its tangent points are reachable as accessors on the arc it returns.
     * Kept rather than re-composed, because it is the case every existing drawing was built with — the
     * generalization has to add variants, not restate the one that already works.
     */
    private fun filletLineLine(
        leg1: Element,
        leg2: Element,
        radius: ScalarRef,
        clickA: Vec2,
        clickB: Vec2,
        signs: List<Int>,
    ): Element {
        val l1 = carrierLine(leg1)
        val l2 = carrierLine(leg2)
        val (sign1, sign2) = storedLegSigns(signs) ?: legSigns(l1, l2, clickA, clickB)
        val arc = cx.filletBetweenLines(l1, l2, radius, sign1, sign2)
        val el = add(arc, ElementKind.ARC, Styles.CURVE)
        registerSigns(el, listOf(sign1, sign2))
        // the op builds the arc from leg1's tangency to leg2's, so its own ends *are* the two joints — and a
        // rounding meets its legs *tangentially*, which is the fact a blend's run reads (GitHub #29)
        registerJoint(el, leg1, cx.arcStart(arc), tangent = true)
        registerJoint(el, leg2, cx.arcEnd(arc), tangent = true)
        supersedeCorner(leg1, leg2, el)
        val trimmed = supersedeWithTrim(leg1, leg2, cx.arcStart(arc), cx.arcEnd(arc))
        recordOrthoCorner(leg1, leg2, el, trimmed, radius, rounded = true)
        return el
    }

    /**
     * A fillet with at least one round leg: line–circle, or circle–circle.
     *
     * The graph is built in **exactly** the argument order [filletCentres] scores numerically, because a
     * `Select` sign means "first or last of *this* set" (OP-1) and both intersections order their solutions
     * from their arguments — swap them and the stored branch would mean the other point.
     */
    private fun filletMixed(
        leg1: Element,
        leg2: Element,
        radius: ScalarRef,
        clickA: Vec2,
        clickB: Vec2,
        signs: List<Int>,
    ): Element? {
        val v =
            if (signs.size >= 3) {
                FilletVariant(signs[0], signs[1], signs[2])
            } else {
                filletVariantFor(leg1, leg2, radius, clickA, clickB)
            } ?: return null
        val reason = "no circle of that radius is tangent to both legs there"
        val set =
            when {
                leg1.isLinear ->
                    cx.intersectLC(
                        cx.parallelAtDistance(carrierLine(leg1), radius, v.side1),
                        cx.concentricCircle(carrierCircle(leg2), radius, v.side2),
                    )
                leg2.isLinear ->
                    cx.intersectLC(
                        cx.parallelAtDistance(carrierLine(leg2), radius, v.side2),
                        cx.concentricCircle(carrierCircle(leg1), radius, v.side1),
                    )
                else ->
                    cx.intersectCC(
                        cx.concentricCircle(carrierCircle(leg1), radius, v.side1),
                        cx.concentricCircle(carrierCircle(leg2), radius, v.side2),
                    )
            }
        val centre = cx.select(set, v.branch, reason)
        val t1 = tangencyOn(leg1, centre)
        val t2 = tangencyOn(leg2, centre)
        val el = add(cx.filletArc(centre, t1, t2), ElementKind.ARC, Styles.CURVE)
        registerSigns(el, listOf(v.side1, v.side2, v.branch))
        registerJoint(el, leg1, t1, tangent = true)
        registerJoint(el, leg2, t2, tangent = true)
        supersedeCorner(leg1, leg2, el)
        val trimmed = supersedeWithTrim(leg1, leg2, t1, t2)
        recordOrthoCorner(leg1, leg2, el, trimmed, radius, rounded = true)
        return el
    }

    /** Where a fillet centred at [centre] touches [leg]: a projection on a straight leg, a radial on a round one. */
    private fun tangencyOn(
        leg: Element,
        centre: PointRef,
    ): PointRef = if (leg.isLinear) cx.projectToLine(centre, carrierLine(leg)) else cx.radialPoint(carrierCircle(leg), centre)

    /**
     * Which variant the two clicks meant — decided once, here, and then stored (OP-1).
     *
     * The scoring itself is [FilletMath.variantFor], on values: every variant is built *numerically* and
     * scored by how near its two tangencies fall to where the legs were clicked, which is the whole of the
     * information the clicks carry. Numerically rather than as throwaway nodes, because eight candidate
     * sub-graphs per fillet would be the wrong kind of cheap (as the area-pick filter records) — and, since
     * it is values, the **live preview runs the very same scoring** without touching the graph (see
     * [Previews.fillet]), so what the hover shows is what this click stores.
     *
     * When no variant has a solution at all — r larger than the geometry admits — the one *closest* to
     * having one is stored, so the invalid node (OP-3) heals into the fillet the user was reaching for as
     * soon as the radius comes down, instead of into an arbitrary other one.
     */
    private fun filletVariantFor(
        leg1: Element,
        leg2: Element,
        radius: ScalarRef,
        clickA: Vec2,
        clickB: Vec2,
    ): FilletVariant? {
        val ev = Evaluator()
        val r = ((ev.eval(radius.node) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.mm ?: return null
        val v1 = filletLegOf(leg1, ev) ?: return null
        val v2 = filletLegOf(leg2, ev) ?: return null
        return FilletMath.variantFor(v1, v2, r, clickA, clickB)
    }

    /** One fillet leg as values — its carrier line, or its carrier circle. */
    private fun filletLegOf(
        el: Element,
        ev: Evaluator,
    ): FilletLeg? =
        if (el.isLinear) {
            ((ev.eval(carrierLine(el).node) as? EvalResult.Ok)?.value as? LineValue)?.line?.let { FilletLeg.of(it) }
        } else {
            ((ev.eval(carrierCircle(el).node) as? EvalResult.Ok)?.value as? CircleValue)?.circle?.let { FilletLeg.of(it) }
        }

    /**
     * A straight bevel across the corner of two **carrier curves** — a line/segment/ray, a circle or an arc,
     * in any of the three combinations (the session-76 entry, item c: *the chamfer-on-arc convention, decided
     * once*).
     *
     * The convention, stated in one line and argued in [FilletMath.setback]: each setback point is
     * [distance] from the corner **along its own carrier** — arc distance on a round leg — and the bevel is
     * the straight segment between the two. What replaced the old line-only refusal is therefore *the same
     * sentence*, one leg kind on; the parked alternative (a chord of the same length) loses on three counts,
     * chief among them that it would take unequal amounts of material off the two legs of one corner.
     *
     * Which corner and which way along each leg stays a **stored discrete choice** (OP-1), scored once from
     * the two clicks: `signs = side1;side2` for two straight legs, exactly as before, and
     * `side1;side2;branch` where a round leg gives the carriers two crossings to choose between. The first two
     * positions never changed meaning, so every stored line–line chamfer replays byte for byte.
     */
    fun chamferBetweenCurves(
        leg1: Element,
        leg2: Element,
        distance: ScalarRef,
        clickA: Vec2,
        clickB: Vec2,
        signs: List<Int> = emptyList(),
    ): Element? =
        when {
            leg1.isLinear && leg2.isLinear -> chamferBetweenLines(leg1, leg2, distance, clickA, clickB, signs)
            isFilletLeg(leg1) && isFilletLeg(leg2) -> chamferMixed(leg1, leg2, distance, clickA, clickB, signs)
            else -> {
                // the fillet's own sentence, and the same reason: a bevel's end has to stay *on* its leg, so
                // the leg must carry a line or a circle to run along. A spline, a conic and a function curve
                // carry neither — their arc length is not a closed form this drawing states (OP-15) — so the
                // setback along one could only be sampled. Named rather than dropped.
                val bad = listOf(leg1, leg2).firstOrNull { !isFilletLeg(it) }
                if (bad != null) {
                    note = "Chamfer: ${nameOf(bad)} is ${kindWord(bad)}, and a bevel's end runs a stated " +
                        "distance along its leg — pick a line, a segment, a circle or an arc"
                }
                null
            }
        }

    /**
     * A chamfer with at least one **round** leg: the corner is a crossing of the two carriers (a persisted
     * branch, OP-1, since a round leg makes there be two of them), and each end is that distance along its own
     * carrier — `pointAlongLine` on a straight leg, [Construction.pointAlongCircle] on a round one.
     *
     * The graph is built in **exactly** the argument order [FilletMath.chamferCorners] scores numerically,
     * because a `Select` sign means "first or last of *this* set" (OP-1) and both intersections order their
     * solutions from their arguments — swap them and the stored branch would mean the other crossing.
     */
    private fun chamferMixed(
        leg1: Element,
        leg2: Element,
        distance: ScalarRef,
        clickA: Vec2,
        clickB: Vec2,
        signs: List<Int>,
    ): Element? {
        val v =
            if (signs.size >= 3) {
                ChamferVariant(signs[0], signs[1], signs[2])
            } else {
                chamferVariantFor(leg1, leg2, distance, clickA, clickB)
            } ?: return null
        val reason = "the two legs do not cross there, so there is no corner to bevel"
        val set =
            when {
                leg1.isLinear -> cx.intersectLC(carrierLine(leg1), carrierCircle(leg2))
                leg2.isLinear -> cx.intersectLC(carrierLine(leg2), carrierCircle(leg1))
                else -> cx.intersectCC(carrierCircle(leg1), carrierCircle(leg2))
            }
        val corner = cx.select(set, v.branch, reason)
        val a = addDerived(setbackOn(leg1, corner, distance, v.side1))
        val b = addDerived(setbackOn(leg2, corner, distance, v.side2))
        val bevel = segment(a, b)
        registerSigns(bevel, listOf(v.side1, v.side2, v.branch))
        // a bevel *turns* a corner where a rounding runs on smoothly, so these joints are not tangent —
        // which is what keeps a chamfered edge one band and a filleted run one ribbon (GitHub #29)
        registerJoint(bevel, leg1, a)
        registerJoint(bevel, leg2, b)
        supersedeCorner(leg1, leg2, bevel)
        val trimmed = supersedeWithTrim(leg1, leg2, a, b)
        recordOrthoCorner(leg1, leg2, bevel, trimmed, distance, rounded = false)
        return bevel
    }

    /** A bevel end: [distance] from [corner] along [leg]'s own carrier — the convention, as a construction. */
    private fun setbackOn(
        leg: Element,
        corner: PointRef,
        distance: ScalarRef,
        sign: Int,
    ): PointRef =
        if (leg.isLinear) {
            cx.pointAlongLine(carrierLine(leg), corner, distance, sign)
        } else {
            cx.pointAlongCircle(carrierCircle(leg), corner, distance, sign)
        }

    /**
     * Which variant the two clicks meant — decided once, here, and then stored (OP-1); the fillet's own
     * scoring ([filletVariantFor]) with the bevel's ends in place of the rounding's tangencies, so the live
     * preview runs the very same function ([Previews.chamfer]) without touching the graph.
     */
    private fun chamferVariantFor(
        leg1: Element,
        leg2: Element,
        distance: ScalarRef,
        clickA: Vec2,
        clickB: Vec2,
    ): ChamferVariant? {
        val ev = Evaluator()
        val d = ((ev.eval(distance.node) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.mm ?: return null
        val v1 = filletLegOf(leg1, ev) ?: return null
        val v2 = filletLegOf(leg2, ev) ?: return null
        val v = FilletMath.chamferVariantFor(v1, v2, d, clickA, clickB)
        if (v == null) {
            note = "Chamfer: ${nameOf(leg1)} and ${nameOf(leg2)} do not cross, so there is no corner to bevel"
        }
        return v
    }

    /**
     * The two-straight-legs case: the points at [distance] from the corner along each leg, joined by a
     * segment. The corner quadrant comes from where the legs were clicked, exactly as a fillet's does
     * ([legSigns]).
     *
     * Composed entirely of ops that already existed — `intersectLL` + `Select` for the corner (a persisted
     * branch, OP-1) and `pointAlongLine` for each bevel end — so a chamfer needs no geometry of its own:
     * both ends stay on their legs, and the bevel follows every later edit of either. Kept as its own case
     * rather than folded into [chamferMixed] for the reason the line–line *fillet* is kept: it is what every
     * existing drawing was built with, and two lines meet in one point, so it stores one sign fewer.
     */
    fun chamferBetweenLines(
        leg1: Element,
        leg2: Element,
        distance: ScalarRef,
        clickA: Vec2,
        clickB: Vec2,
        signs: List<Int> = emptyList(),
    ): Element {
        val l1 = carrierLine(leg1)
        val l2 = carrierLine(leg2)
        val (sign1, sign2) = storedLegSigns(signs) ?: legSigns(l1, l2, clickA, clickB)
        // two lines meet in a single point, so the branch is not a choice at all
        val corner = cx.select(cx.intersectLL(l1, l2), +1)
        val a = addDerived(cx.pointAlongLine(l1, corner, distance, sign1))
        val b = addDerived(cx.pointAlongLine(l2, corner, distance, sign2))
        val bevel = segment(a, b)
        // on the bevel, which is the step's *last* creation: the two ends are declared before it, and the
        // signs have to be restated in a place the step can find them again (see [Document.storedSigns])
        registerSigns(bevel, listOf(sign1, sign2))
        // the bevel's ends *are* where it hands over to each leg, and the corner it cut off is gone
        registerJoint(bevel, leg1, a)
        registerJoint(bevel, leg2, b)
        supersedeCorner(leg1, leg2, bevel)
        val trimmed = supersedeWithTrim(leg1, leg2, a, b)
        recordOrthoCorner(leg1, leg2, bevel, trimmed, distance, rounded = false)
        return bevel
    }

    /**
     * The quadrant a step **restated** (`signs=`), or null when it carries none and the clicks must decide.
     *
     * The whole reason the file carries them (OP-18): the clicks are positions, and the corner they were
     * scored against *moves* when either leg is edited, so re-scoring on load is re-deciding — see
     * [Document.scoredSigns].
     */
    private fun storedLegSigns(signs: List<Int>): Pair<Int, Int>? = if (signs.size >= 2) signs[0] to signs[1] else null

    /**
     * Which way along each of two legs the clicked corner opens: `+1` along the leg's own direction, `-1`
     * against it. A stored discrete choice (OP-1) — the quadrant is decided once, when the tool is used,
     * and never re-derived as the legs move. Shared by the fillet and the chamfer, which differ only in
     * what they put in that corner.
     *
     * Called **only** for a live click now: a replayed step hands its answer back through [storedLegSigns],
     * because the corner these clicks were scored against has moved with the legs since (OP-18).
     */
    private fun legSigns(
        l1: LineRef,
        l2: LineRef,
        clickA: Vec2,
        clickB: Vec2,
    ): Pair<Int, Int> {
        val ev = Evaluator()
        val la = ((ev.eval(l1.node) as? EvalResult.Ok)?.value as? LineValue)?.line ?: return 1 to 1
        val lb = ((ev.eval(l2.node) as? EvalResult.Ok)?.value as? LineValue)?.line ?: return 1 to 1
        return FilletMath.legSigns(la, lb, clickA, clickB)
    }

    // ---- shapes: several elements built round shared nodes, so the *shape* is invariant ----

    /**
     * A rectangle as a **closed ortho path**: `orthostart` at [a], three `orthovertex` steps round the
     * corners, `orthoclose` — the very steps the ortho-path tool records, emitted by two clicks instead of
     * five (GitHub issue #4).
     *
     * The point is that the result is *not a rectangle kind*. It is an ordinary path, so everything the path
     * machinery already offers arrives with it and none of it had to be written twice: every corner drags
     * (OP-20), every **leg** drags across itself, either side's length is a numeric field of its leg (OP-13),
     * a leg breaks and joins (OP-19), a run attaches to it, and it thickens into walls with jamb-ready
     * openings (OP-21). The old build — four segments over two clicked corners and two derived ones — was
     * rectangular by construction too, but its corners had one *coordinate* of freedom each in the drag's eyes,
     * which is what the report meant by "almost non-editable": pulling a corner moved it on one axis only.
     *
     * Null for a degenerate pair (a click that shares a coordinate with the first): there is no rectangle
     * there, and half of one is worse than none.
     */
    fun orthoRectangle(
        a: Vec2,
        c: Vec2,
        /** What the first click landed on, to join that corner to (see [linkPathEnd]); null for a plain click. */
        landingA: SnapResult? = null,
        /** The same for the second click — the diagonally opposite corner. */
        landingC: SnapResult? = null,
    ): OrthoPath? {
        // a click that landed on geometry *means* that geometry's position, exactly as a path click does
        val a = landingA?.takeIf { it.linked }?.pos ?: a
        val c = landingC?.takeIf { it.linked }?.pos ?: c
        if (abs(c.x - a.x) < Vec2.EPS || abs(c.y - a.y) < Vec2.EPS) return null
        val path = startOrthoPath(a)
        // A corner clicked *on* something starts *at* it, exactly as an ortho path's own click does, so the
        // rectangle follows that geometry instead of merely beginning at its coordinates. Each link is made
        // **while its corner is still the run's loose end** — that is the one thing a connection asks for
        // ([orthoEndpoint]), and it is also what makes this identical to drawing the path by hand: click,
        // join, carry on. Only the two *clicked* corners can join; the other two are the pair the clicks
        // imply, and no cursor was ever there to mean anything by.
        landingA?.takeIf { it.linked }?.let { linkPathEnd(path.vertices[0].ref, it) }
        // the corners in draw order, alternating axis, so no step continues the previous leg (which would
        // extend it instead of turning — see [addOrthoVertexNow])
        addOrthoVertex(path, Vec2(c.x, a.y))
        addOrthoVertex(path, c)
        landingC?.takeIf { it.linked }?.let { linkPathEnd(path.vertices[2].ref, it) }
        addOrthoVertex(path, Vec2(a.x, c.y))
        closeOrthoPath(path)
        return path
    }

    /**
     * A rectangle from two diagonally opposite corners — rectangular **by construction**.
     *
     * The other two corners are not points of their own: each takes one coordinate from each clicked
     * corner (`pointXY(x(a), y(c))` and `pointXY(x(c), y(a))`), so the four corners cannot stop forming a
     * rectangle. Dragging or typing either clicked corner reshapes the whole figure, and no gesture can
     * shear it — the same trick as an ortho leg, whose endpoints share a coordinate and are therefore
     * axis-aligned without anything being asserted (OP-5).
     *
     * Both clicked corners keep their handles, so a corner is editable by drag *and* by number (OP-13).
     *
     * **Superseded by [orthoRectangle] and kept for replay only** (`Tools.RECTANGLE_V1`): a stored step means
     * what it meant when it was written (OP-18).
     */
    fun rectangle(
        a: PointRef,
        c: PointRef,
    ): List<Element> {
        val ax = cx.measureX(a)
        val ay = cx.measureY(a)
        val cx0 = cx.measureX(c)
        val cy = cx.measureY(c)
        val b = addDerived(cx.pointXY(cx0, ay))
        val d = addDerived(cx.pointXY(ax, cy))
        return listOf(segment(a, b), segment(b, c), segment(c, d), segment(d, a))
    }

    /**
     * A `count`-sided regular polygon: [vertex] plus its rotations about [center] by multiples of
     * 360°/count, chained by segments.
     *
     * Regular by construction, with no new op: it is the existing general [Construction.rotate] applied
     * count-1 times, so dragging the centre or the vertex keeps every side equal and every angle the same.
     * [count] is **structural** — it decides how many nodes exist — so changing it means re-running the
     * tool, not editing a value (see *Structural count* in DESIGN.md).
     */
    fun regularPolygon(
        center: PointRef,
        vertex: PointRef,
        count: Int,
    ): List<Element> {
        if (count < 3) return emptyList()
        val vertices = ArrayList<PointRef>(count)
        vertices.add(vertex)
        for (k in 1 until count) {
            vertices.add(addDerived(cx.rotate(vertex, center, cx.const((360.0 * k / count).deg))))
        }
        return (0 until count).map { segment(vertices[it], vertices[(it + 1) % count]) }
    }

    /**
     * The [roundedRect] macro (OP-6) as a tool: a rounded rectangle spanning two diagonally opposite
     * corners, with corner radius [radius].
     *
     * Driven by construction like [rectangle] rather than by a copied-out centre and size: the macro's
     * centre is the clicked corners' midpoint and its width/height are their coordinate spans, so the two
     * clicked points keep driving the shape afterwards. The radius is an ordinary parameter, so editing it
     * re-rounds the corners live — nothing is regenerated.
     */
    fun roundedRectangle(
        a: PointRef,
        c: PointRef,
        radius: ScalarRef,
    ): List<Element> {
        val center = cx.midpoint(a, c)
        val width = cx.absS(cx.sub(cx.measureX(c), cx.measureX(a)))
        val height = cx.absS(cx.sub(cx.measureY(c), cx.measureY(a)))
        val rr = cx.instance(roundedRect, nextId("rr"), RoundedRectArgs(center, width, height, radius))
        // added in **boundary order**, so the step that built the shape also records the order its pieces
        // run in — which is what lets the whole rounded rectangle be picked as an area (see
        // [boundaryPiecesOf]) without anything having to guess how the pieces join
        return rr.boundary.map { ref ->
            if (rr.arcs.any { it === ref }) add(ref, ElementKind.ARC, Styles.CURVE) else add(ref, ElementKind.SEGMENT, Styles.CURVE)
        }
    }

    /**
     * A point at the two given scalars — the case that made the slot model take a *list* of scalar inputs
     * rather than one active parameter. It owns no DOF of its own: editing either parameter moves it, and
     * two points sharing a parameter stay aligned *because* they share it (OP-5).
     */
    fun pointFromCoordinates(
        x: ScalarRef,
        y: ScalarRef,
    ): PointRef = addDerived(cx.pointXY(x, y))

    // ---- arrays: the interactive generalization of the boltCircle / holePattern macros (OP-6) ----

    /**
     * [count]-1 copies of every element of [geoms], each translated by a whole multiple of the vector
     * [from] → [to].
     *
     * A **fan, not a chain**: copy *k* is `k·v` from the original rather than one step from copy *k-1*, so
     * no copy depends on a sibling — deleting one leaves the rest, and every copy recomputes directly from
     * the original and the two vector points. The copy keeps the source's kind and style, so an array of a
     * circle is circles and an array of a segment is segments, with no per-kind case anywhere.
     *
     * **A list, because the geometry slot may hold a whole group** (OP-16): one element is the list of one,
     * so the single-element array is unchanged and a group is *k*-1 further instances of all of it. The step
     * nodes are created once and shared by every member's copies — sharing a node is equality (OP-5), so
     * the whole array re-spaces from one drag of the vector. Instance-major order, so the copies read as
     * "the whole group again, and again".
     */
    @Suppress("UNCHECKED_CAST")
    fun linearArray(
        geoms: List<Element>,
        from: PointRef,
        to: PointRef,
        count: Int,
    ): List<Element> {
        if (count < 2 || geoms.isEmpty()) return emptyList()
        val dx = cx.sub(cx.measureX(to), cx.measureX(from))
        val dy = cx.sub(cx.measureY(to), cx.measureY(from))
        return (1 until count).flatMap { k ->
            val step = k.toDouble()
            val sx = cx.scale(dx, step)
            val sy = cx.scale(dy, step)
            geoms.map { geom -> addLike(cx.translateGeom(geom.ref as Ref<Value>, sx, sy), geom) }
        }
    }

    /**
     * [count]-1 copies of every element of [geoms] rotated about [center], evenly spaced round the full
     * turn — the interactive form of the bolt circle, whose macro does exactly this with points and holes.
     *
     * The angles are constants because [count] is structural: `360°/count` is not a value the user edits
     * afterwards, it is what "six of them, evenly spaced" *means*. A list of sources for the same reason
     * [linearArray] takes one — a whole group is one operand of the geometry slot (OP-16).
     */
    @Suppress("UNCHECKED_CAST")
    fun circularArray(
        geoms: List<Element>,
        center: PointRef,
        count: Int,
    ): List<Element> {
        if (count < 2 || geoms.isEmpty()) return emptyList()
        return (1 until count).flatMap { k ->
            val angle = cx.const((360.0 * k / count).deg)
            geoms.map { geom -> addLike(cx.rotate(geom.ref as Ref<Value>, center, angle), geom) }
        }
    }

    // ---- patterns as orbits (OP-23): a pattern is a rule, and every later gesture rides it ----

    private val patternList = ArrayList<Pattern>()

    /**
     * The patterns a replicated gesture's copies built (#18) — kept apart from [patternList] deliberately.
     *
     * A nested pattern is not a rule the *file* names: it has no `pattern` step, it is one of the things a
     * copy of an `orbit` step does, and the drawing has one per copy. So it stays out of the list every
     * `orbit` step resolves its name against ([patternNamed]) and out of the list the UI offers, while being
     * a first-class [Pattern] everywhere the nesting is addressed ([allPatterns], [memberPath]).
     */
    private val nestedPatterns = ArrayList<Pattern>()
    private var patternCounter = 0

    /** Every pattern still standing — one whose reference member is gone is gone with it. */
    val patterns: List<Pattern> get() = patternList.filter { p -> elements.any { it === p.reference } }

    /** The patterns a ride's copies built, still standing — one level of the nesting each (#18). */
    val nested: List<Pattern> get() = nestedPatterns.filter { p -> elements.any { it === p.reference } }

    /** …and both together, which is the list the composed addressing is resolved against (#18). */
    internal fun allPatterns(): List<Pattern> = (patternList + nestedPatterns).filter { p -> elements.any { it === p.reference } }

    private fun uniquePatternName(): String {
        var i = patternCounter + 1
        while (patternList.any { it.name == "P$i" } || nestedPatterns.any { it.name == "P$i" }) i++
        patternCounter = i
        return "P$i"
    }

    /** The pattern [name] declares, or null — the `orbit` step's one reference to its rule. */
    fun patternNamed(name: String): Pattern? = patterns.firstOrNull { it.name == name }

    /** Which orbit [el] is a member of, and at which index — null when it is outside every pattern. */
    fun memberSlot(el: Element): Pair<PatternOrbit, Int>? = memberSites(el).firstOrNull()?.let { it.orbit to it.index }

    /**
     * Where [el] sits in the **nesting** (OP-23, #18): one site per level, outermost first.
     *
     * A pattern nested inside a replicated gesture adds a level, so an element built inside copy *j* of a
     * gesture that carries a pattern is a member twice over — of that gesture's output orbit at *j*, and of
     * copy *j*'s own inner orbit at *k*. That pair is what makes a gesture on it fan over both, and the
     * addressing needs no new case anywhere: the pick is the same element reference it always was, with one
     * index per depth.
     *
     * At most one orbit per pattern, since a pattern's orbits partition what its gestures built. The levels of
     * a real element are contiguous from 0 by construction — everything a copy builds is a member of the ride
     * that built it — except while that ride is *mid-build*, where its outputs are not orbits yet; that is the
     * one place a site can exist at depth 1 with none at depth 0, and it is exactly what makes a gesture
     * inside a copy ride the copy's own pattern rather than the outer one.
     */
    fun memberSites(el: Element): List<MemberSite> {
        var found: ArrayList<MemberSite>? = null
        for (p in allPatterns()) {
            for ((pos, o) in p.orbits.withIndex()) {
                val i = o.members.indexOfFirst { it === el }
                if (i >= 0) {
                    (found ?: ArrayList<MemberSite>().also { found = it }).add(MemberSite(p, o, pos, i))
                    break
                }
            }
        }
        val hits = found ?: return emptyList()
        if (hits.size == 1) return hits
        return hits.sortedBy { it.level }
    }

    /** The pattern [el] belongs to as a member, outermost first, or null. */
    fun patternOf(el: Element): Pattern? = memberSites(el).firstOrNull()?.pattern

    /**
     * The **innermost** rule [el] belongs to (#18).
     *
     * A pattern is reached through its geometry (OP-23), and with nesting one element belongs to two rules at
     * once: a rounded polygon's vertex is a member of the ring the polygons sit on *and* of that polygon's
     * own corner ring.
     */
    fun innermostPatternOf(el: Element): Pattern? = memberSites(el).lastOrNull()?.pattern

    /**
     * The pattern at [level] of [g]'s nesting for the copy whose outer indices are [outer] (#18).
     *
     * Level 0's is the pattern the gesture rides outermost; a deeper one is *whichever copy's* pattern the
     * outer indices name, looked up in the ride that built them — which is the whole of how a nested address
     * resolves, and why nothing has to be transformed to find it.
     */
    internal fun patternAt(
        g: OrbitGesture,
        level: Int,
        outer: List<Int>,
    ): Pattern? {
        val anchor = g.levels.getOrNull(level)?.pattern ?: return null
        if (level == 0) return anchor
        if (outer.all { it == 0 }) return anchor
        return anchor.enclosing?.inner?.get(outer)?.getOrNull(anchor.enclosingSlot)
    }

    /**
     * Every replicated gesture that created [el] — how a selection reaches the rules that built it (#18).
     *
     * More than one, because a nested creation is registered at every level: a side of a polygon that
     * multiplied with a ring is an output of the ride *and* of that polygon's own side orbit. Ordered
     * outermost first, so the ride whose `count=` a re-stamp rewrites comes before the rules inside it.
     */
    fun ridesOf(el: Element): List<OrbitGesture> =
        allPatterns().flatMap { p ->
            p.gestures.filter { g -> g.outputs.any { o -> o.members.any { it === el } } }
        }

    /** The outermost replicated gesture that created [el], or null. */
    fun gestureOf(el: Element): OrbitGesture? = ridesOf(el).firstOrNull()

    /**
     * Where a position of cell 0 lands in cell [k] — the pattern's **own transform**, and the only thing a
     * replicated gesture transforms at all.
     *
     * Geometry is never transformed: a copy is built on the shared members, which is what makes adjacent
     * copies share nodes. A *click* is transformed because a click states a choice ("this quadrant"), and the
     * corresponding choice in another cell is that same click carried round. Negative [k] carries it back,
     * which is how a click is stored cell-locally in the first place.
     */
    fun patternCell(
        p: Pattern,
        k: Int,
        at: Vec2,
        ev: Evaluator = Evaluator(),
    ): Vec2 {
        val origin = pointOf(p.reference.ref.node, ev) ?: return at
        val about = pointOf(p.about.ref.node, ev) ?: return at
        return when (p.kind) {
            PatternKind.CIRCULAR -> {
                val a = 2.0 * PI * k / p.count
                val d = at - about
                about + Vec2(d.x * cos(a) - d.y * sin(a), d.x * sin(a) + d.y * cos(a))
            }
            PatternKind.LINEAR -> at + (about - origin) * k.toDouble()
        }
    }

    /**
     * Where a position of the all-zero cell lands in cell [index] of [g]'s nesting — the **composed**
     * transform (#18), and still the only thing a replicated gesture transforms at all.
     *
     * The order is forced by the geometry rather than chosen: copy *j*'s inner pattern *is* the outer
     * transform of copy 0's, so `T_out^j ∘ T_in0^k` and `T_inj^k ∘ T_out^j` are the same map — and taking the
     * first of the two means every level can be read off the **anchor** pattern alone. Hence innermost first
     * going out, and outermost first coming back ([composedCellBack]), which is how a click ends up stored
     * cell-locally at every depth.
     */
    private fun composedCell(
        g: OrbitGesture,
        index: List<Int>,
        at: Vec2,
        ev: Evaluator,
    ): Vec2 {
        var p = at
        for (l in index.indices.reversed()) {
            val level = g.levels.getOrNull(l) ?: continue
            p = patternCell(level.pattern, if (level.wraps) index[l] % level.count else index[l], p, ev)
        }
        return p
    }

    /** The same map inverted: a click carried back to the cell of index 0 at every level (#18). */
    private fun composedCellBack(
        g: OrbitGesture,
        index: List<Int>,
        at: Vec2,
        ev: Evaluator,
    ): Vec2 {
        var p = at
        for (l in index.indices) {
            val level = g.levels.getOrNull(l) ?: continue
            p = patternCell(level.pattern, -(if (level.wraps) index[l] % level.count else index[l]), p, ev)
        }
        return p
    }

    /**
     * A pattern: [reference] repeated [count] times about [about] — the ring (or row) as a live object.
     *
     * The members are the ordinary transform nodes the arrays already build (`rotate`, `translateGeom`), so
     * this adds no geometry op: what is new is that the document **remembers the rule**, which is what lets
     * every later gesture ride it and what a count change re-stamps. The step nodes of a row are created once
     * and shared by every member, so one drag of the vector re-spaces the whole row (OP-5).
     */
    fun createPattern(
        kind: PatternKind,
        reference: PointRef,
        about: PointRef,
        count: Int,
        /** The name the file gives it — replay must keep it, since the `orbit` steps refer to it. */
        named: String? = null,
    ): Pattern? {
        val refEl = elementFor(reference) ?: return null
        val aboutEl = elementFor(about) ?: return null
        if (count < 2) {
            note = "a pattern needs at least 2 instances"
            return null
        }
        if (refEl === aboutEl) {
            note = "a pattern needs two different points"
            return null
        }
        val name = named ?: uniquePatternName()
        return recording(
            "pattern",
            Arg.Label(name),
            Arg.Text(kind.name.lowercase()),
            Arg.Keyed("ref", Arg.El(refEl)),
            Arg.Keyed(if (kind == PatternKind.CIRCULAR) "centre" else "to", Arg.El(aboutEl)),
            Arg.Keyed("count", Arg.Text(count.toString())),
        ) {
            val p = Pattern(nextId("pat"), name, kind, refEl, aboutEl, count)
            val members = ArrayList<Element>(count)
            members.add(refEl)
            val dx = if (kind == PatternKind.LINEAR) cx.sub(cx.measureX(about), cx.measureX(reference)) else null
            val dy = if (kind == PatternKind.LINEAR) cx.sub(cx.measureY(about), cx.measureY(reference)) else null
            for (k in 1 until count) {
                val ref =
                    when (kind) {
                        PatternKind.CIRCULAR -> cx.rotate(reference, about, cx.const((360.0 * k / count).deg))
                        PatternKind.LINEAR -> cx.translateGeom(reference, cx.scale(dx!!, k.toDouble()), cx.scale(dy!!, k.toDouble()))
                    }
                addDerived(ref)
                members.add(elements.last())
            }
            p.orbits.add(PatternOrbit(p, members))
            // …and *whose* pattern it is: one a copy of a replicated gesture built is nested in it (#18), so
            // it is not a rule the file names and not one the UI offers — it is part of what that gesture does
            val host = building
            if (host == null) {
                patternList.add(p)
            } else {
                p.enclosing = host.gesture
                p.enclosingIndex = host.index
                p.enclosingSlot = host.gesture.inner.getOrPut(host.index) { ArrayList() }.size
                host.gesture.inner.getValue(host.index).add(p)
                nestedPatterns.add(p)
            }
            note = "Pattern ${p.name}: $count instances — anything built on its members now repeats round it"
            p
        }.also { p -> p.step = journal.lastOrNull()?.takeIf { it.kind == "pattern" } }
    }

    /** The copy of a replicated gesture being built right now — the nesting context a pattern is born into (#18). */
    private class Building(
        val gesture: OrbitGesture,
        val index: List<Int>,
    )

    private var building: Building? = null

    /**
     * Whether [tool] fanning over a pattern is even on the table, and if so how — **the replication trigger**
     * (OP-23).
     *
     * The rule: a gesture replicates when one of its picks is a pattern member and every other pick is
     * either a member of the same pattern or *invariant* under its transform. Scalars need no test at all —
     * a tool takes the very same parameter node into every copy, so equality is by reference (OP-5).
     *
     * Returns null when no pick touches a pattern (the overwhelming majority of gestures), a refusal when
     * one does but another input is outside, and a plan otherwise.
     */
    fun replicationOf(
        tool: ToolDef,
        picks: Picks,
    ): Replication? {
        if (!tool.replicates || tool.repeating || tool.slots.isEmpty()) return null
        // which pick fills each slot, in slot order — the tool's own declaration is the mapping
        val pointEls = picks.points.map { elementFor(it) }
        val slotEl = ArrayList<Element?>()
        var pi = 0
        // a face-part tool's *first* element is not a pick at all: it is the part the editor resolved for it
        // (OP-17). It is skipped here and re-resolved per copy, which is exactly the chain — see
        // [OrbitGesture.chainsPart].
        var ei = if (tool.facePartOperand) 1 else 0
        // …and which **click** filled each slot, which is not the slot's own index once a slot may be left
        // out: an optional pick that was skipped consumed no click ([SlotKind.OPTIONAL_POINT]), so the cells
        // below have to be read off the picks that happened rather than off the slot positions. The cell of a
        // slot that was skipped stays the origin and is read by nothing — the cells are a per-slot table for
        // the replay to line up against, and a skipped slot has no pick for anything to ask about.
        val clickIx = ArrayList<Int?>()
        var ci = 0
        for (slot in tool.slots) {
            when (slot) {
                SlotKind.PLACE_POINT, SlotKind.POINT -> slotEl.add(pointEls.getOrNull(pi++))
                // an optional slot takes a point only when the gesture gave it one
                SlotKind.OPTIONAL_POINT -> slotEl.add(pointEls.getOrNull(pi)?.also { pi++ })
                SlotKind.SIDE -> slotEl.add(null)
                else -> slotEl.add(picks.elements.getOrNull(ei++))
            }
            clickIx.add(if (Tools.isOptionalSlot(slot) && slotEl.last() == null) null else ci++)
        }
        if (pi != picks.points.size || ei != picks.elements.size) return null // a fan, or a part operand
        val sites = slotEl.map { el -> el?.let { memberSites(it) } ?: emptyList() }
        val outermost = sites.firstOrNull { it.isNotEmpty() }?.first()?.pattern ?: return null
        // **The level stack** (#18). A level is ridable when every pick is, there, either a member of that
        // level's pattern or invariant under it — OP-23's own rule, asked once per depth. The deepest ridable
        // level and every ridable level outside it make the run; the first level that stops it says which pick
        // and which pattern did, and the levels outside it still fan. So a gesture mixing levels loses depth
        // rather than losing the replication.
        val deepest = sites.maxOf { row -> row.maxOfOrNull { it.level } ?: -1 }
        // A gesture built **inside a copy of a ride** may not ride the levels that ride is already stamping
        // (#18): those cells are exactly what the enclosing ride re-runs this gesture in, so riding them again
        // would square the fan — and the ride's outputs are not orbits yet anyway. So the polygon's own inner
        // segment and fillet ride the copy's own pattern, which is the composition OP-23 already described.
        val floor = building?.gesture?.levels?.size ?: 0
        var top = deepest
        var run: LevelRun? = null
        var deeper: String? = null
        while (top >= floor) {
            val here = levelRun(tool, slotEl, sites, top, floor)
            if (here != null) {
                run = here
                break
            }
            // the level nobody could be carried through: report the outermost such reason, which is the one
            // about the level the gesture *nearly* reached
            deeper = levelRefusal(tool, slotEl, sites, top) ?: deeper
            top--
        }
        val plan = run ?: return Replication(outermost, null, emptyList(), deeper ?: "not replicated: ${nameOf(slotEl.first { it != null }!!)} is outside the pattern")
        if (plan.base > floor && building == null) {
            // a run that starts *inside* a nested pattern has no step form of its own — the `orbit` step names
            // the pattern, and a nested one is not a name the file resolves. Unreachable in practice (every
            // element a ride built is a member of that ride too), and refused rather than written unloadably.
            return Replication(outermost, null, emptyList(), "not replicated: ${plan.levels[0].pattern.name} is a pattern inside a pattern, which only the gesture that carries it can ride")
        }
        val p = plan.levels[0].pattern
        val levels = plan.levels
        val cells = ArrayList<Vec2>()
        val cellIndices = ArrayList<List<Int>>()
        val ev = Evaluator()
        val bare = OrbitGesture(levels, tool.id, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0)
        for (i in tool.slots.indices) {
            val click = clickIx[i]?.let { picks.clicks.getOrNull(it) } ?: Vec2(0.0, 0.0)
            val absolute = levels.indices.map { l -> plan.indexAt(i, l) ?: plan.anchors[l] }
            cells.add(composedCellBack(bare, absolute, click, ev))
            cellIndices.add(absolute.mapIndexed { l, v -> v - plan.anchors[l] })
        }
        val pointPicks = ArrayList<OrbitPick>()
        val elementPicks = ArrayList<OrbitPick>()
        for ((i, slot) in tool.slots.withIndex()) {
            val el = slotEl[i]
            val pick = if (el == null) null else plan.pickFor(i, el)
            when (slot) {
                SlotKind.PLACE_POINT, SlotKind.POINT -> pointPicks.add(pick ?: return null)
                // an optional slot contributes a pick only when the gesture filled it, so a fan of
                // unanchored sweeps carries no anchor and one of anchored sweeps carries exactly one
                SlotKind.OPTIONAL_POINT -> if (pick != null) pointPicks.add(pick)
                SlotKind.SIDE -> {}
                else -> elementPicks.add(pick ?: return null)
            }
        }
        val gesture =
            OrbitGesture(
                levels, tool.id, pointPicks, elementPicks, cells, cellIndices, emptyList(), picks.signs, picks.count,
                chainsPart = tool.facePartOperand,
            )
        val copies = copiesFor(gesture, p.count) ?: return Replication(p, null, emptyList(), "not replicated: the pattern has no room for it")
        if (copies.fold(1) { a, b -> a * b } < 2) {
            return Replication(p, null, emptyList(), "not replicated: the pattern has no room for a second copy of it")
        }
        return Replication(p, gesture, copies, null, deeper)
    }

    /**
     * The levels a gesture rides, as chosen by [replicationOf] — with, per slot, the index it sits at in each
     * of them, and the anchor each level is normalized by (#18).
     */
    private class LevelRun(
        val levels: List<OrbitLevel>,
        /** The depth the outermost level of the run sits at; 0 for every gesture but one inside a copy build. */
        val base: Int,
        val anchors: List<Int>,
        private val ownLevel: Map<Int, Int>,
        private val orbitPos: Map<Int, Int>,
        private val anchorEl: Map<Int, Element>,
        private val indices: Map<Int, List<Int>>,
    ) {
        /** Slot [slot]'s index at level [level], or null where nothing puts it in a cell of that level. */
        fun indexAt(
            slot: Int,
            level: Int,
        ): Int? = indices[slot]?.getOrNull(level)

        /** The recorded pick for slot [slot]: its orbit by position, and one offset per level up to its own. */
        fun pickFor(
            slot: Int,
            el: Element,
        ): OrbitPick {
            val own = ownLevel[slot] ?: return OrbitPick(el, -1, emptyList(), el)
            val row = indices.getValue(slot)
            return OrbitPick(
                anchorEl.getValue(slot),
                orbitPos.getValue(slot),
                (0..own).map { l -> row[l] - anchors[l] },
                null,
            )
        }
    }

    /**
     * The run of levels ending at [top] that every pick can be carried through, or null when [top] itself
     * cannot be ridden (#18).
     *
     * Extended outward from [top] while each level holds, because a level nobody can be carried through does
     * not stop the *inner* ones from fanning — a segment inside one copy of a ride rides that copy's own
     * pattern while the ride is still being built, and its outer level does not exist yet.
     */
    private fun levelRun(
        tool: ToolDef,
        slotEl: List<Element?>,
        sites: List<List<MemberSite>>,
        top: Int,
        floor: Int,
    ): LevelRun? {
        var from = top
        while (from > floor && holdsAt(tool, slotEl, sites, from - 1, top)) from--
        if (!holdsAt(tool, slotEl, sites, top, top)) return null
        // a run that does not reach the outermost pattern is kept to one level: the deeper levels of such a
        // run would need copy indices from levels the gesture is not riding, which is a rule nothing states
        if (from > floor) from = top
        val levels = ArrayList<OrbitLevel>()
        for (l in from..top) {
            // normalized to the all-zero copy only when the run rides the levels outside it: a run that starts
            // *inside* a nested pattern rides nothing outside, so there is no copy to normalize against and the
            // pattern the picks actually sit in is the one — which is what makes a gesture inside a ride's copy
            // build on that copy's own pattern (#18)
            val q = anchorPatternAt(sites, l, normalize = from == 0) ?: return null
            levels.add(OrbitLevel(q, q.enclosing?.let { h -> (h.inner.keys.maxOfOrNull { it.getOrElse(l - 1) { 0 } } ?: 0) + 1 } ?: 0))
        }
        // per slot: the deepest level of the run it is a member of, and its index in every level up to there
        val ownLevel = HashMap<Int, Int>()
        val orbitPos = HashMap<Int, Int>()
        val anchorEl = HashMap<Int, Element>()
        val indices = HashMap<Int, List<Int>>()
        for (i in tool.slots.indices) {
            if (slotEl[i] == null) continue
            val site = sites[i].lastOrNull { it.level in from..top } ?: continue
            val row = ArrayList<Int>()
            for (l in from until site.level) row.add(site.pattern.enclosingIndex.getOrElse(l) { 0 })
            row.add(site.index)
            ownLevel[i] = site.level - from
            orbitPos[i] = site.orbitPos
            val q = anchorPatternAt(sites, site.level, normalize = from == 0) ?: return null
            anchorEl[i] = q.orbits.getOrNull(site.orbitPos)?.members?.firstOrNull() ?: return null
            indices[i] = row
        }
        if (ownLevel.isEmpty()) return null
        // the base copy is the gesture shifted down to the lowest index it touches at every level, so the
        // recorded rule says nothing about *which* copy the user happened to click (OP-23)
        val anchors = levels.indices.map { l -> indices.values.mapNotNull { it.getOrNull(l) }.minOrNull() ?: 0 }
        val padded = indices.mapValues { (_, row) -> levels.indices.map { l -> row.getOrNull(l) ?: anchors[l] } }
        return LevelRun(levels, from, anchors, ownLevel, orbitPos, anchorEl, padded)
    }

    /** The pattern of level [level] — as it stands in the all-zero copy when the outer levels are ridden (#18). */
    private fun anchorPatternAt(
        sites: List<List<MemberSite>>,
        level: Int,
        normalize: Boolean,
    ): Pattern? {
        val q = sites.firstNotNullOfOrNull { row -> row.firstOrNull { it.level == level }?.pattern } ?: return null
        if (!normalize) return q
        val host = q.enclosing ?: return q
        return host.inner[List(level) { 0 }]?.getOrNull(q.enclosingSlot) ?: q
    }

    /**
     * Whether every pick can be carried round level [level] — OP-23's invariance rule, asked at one depth.
     *
     * A pick is carried when it is a member of that level's pattern, when the pattern's transform leaves it
     * where it is (a rotation's centre), or when it is a **solid** — the one non-member, non-invariant input a
     * replicated gesture may touch, because it is the body a feature is applied *to* rather than a geometric
     * input that has to travel with the copy. A pick that is a member at a level *deeper* than this one is
     * carried by that level's own copy shift and needs nothing here.
     *
     * The invariance is tested against the pattern of the copy the deeper picks name, because that is the copy
     * the recorded rule is written in: with a nested level, "the centre" is a different point per copy.
     */
    private fun holdsAt(
        tool: ToolDef,
        slotEl: List<Element?>,
        sites: List<List<MemberSite>>,
        level: Int,
        top: Int,
    ): Boolean = levelRefusalAt(tool, slotEl, sites, level, top) == null

    private fun levelRefusal(
        tool: ToolDef,
        slotEl: List<Element?>,
        sites: List<List<MemberSite>>,
        top: Int,
    ): String? = levelRefusalAt(tool, slotEl, sites, top, top)

    private fun levelRefusalAt(
        tool: ToolDef,
        slotEl: List<Element?>,
        sites: List<List<MemberSite>>,
        level: Int,
        top: Int,
    ): String? {
        val ref =
            sites.firstNotNullOfOrNull { row -> row.firstOrNull { it.level == level }?.pattern }
                ?: return "not replicated: nothing rides that pattern"
        for (i in tool.slots.indices) {
            val el = slotEl[i] ?: continue
            val here = sites[i].firstOrNull { it.level == level }
            // **One level is one transform** (#18), so a pick rides this level only if it sits in *this*
            // pattern: a member of a sibling copy's pattern is carried by a rotation about a different centre,
            // which is no rigid motion of the gesture at all, so it counts as an outside input here.
            if (here != null && here.pattern === ref) continue
            if (el.kind == ElementKind.SOLID) continue
            // …and an outside input has to be one this level's transform leaves alone
            if (ref.invariants.none { it === el }) {
                return when {
                    level > 0 -> "not replicated inside ${ref.name}: ${nameOf(el)} is outside it"
                    here != null -> "not replicated: ${nameOf(el)} belongs to pattern ${here.pattern.name}"
                    else -> "not replicated: ${nameOf(el)} is outside the pattern"
                }
            }
        }
        return null
    }

    /**
     * How many copies [g] gets **at each level**, with the ring [count] members long and [sizes] overriding
     * the orbit lengths a re-stamp will change (OP-23, #18).
     *
     * A ring wraps, so every member is the start of a copy and there are exactly as many copies as members.
     * A row does not: a gesture spanning m+1 neighbours makes n-m copies, which is why a row of holes drawn
     * between neighbours gives one fewer segment than there are holes. Stated per level, so the whole fan is
     * the product — the count of a nested pattern is as structural as the outer one's.
     */
    private fun copiesFor(
        g: OrbitGesture,
        count: Int,
        sizes: Map<PatternOrbit, Int> = emptyMap(),
    ): List<Int>? {
        val out = ArrayList<Int>()
        for (l in g.levels.indices) {
            val own = g.ridingAt(l)
            val through = g.shiftingAt(l)
            if (own.isEmpty() && through.isEmpty()) return null
            val level = g.levels[l]
            val n = if (l == 0) count else level.count
            if (level.wraps) {
                out.add(n)
            } else {
                // a row does not wrap, so what limits the fan is the longest span: an orbit's own length for a
                // pick that rides it, and how many copies the level has for one merely shifted through it
                val byOwn = own.minOfOrNull { orbitOf(g, it)?.let { o -> (sizes[o] ?: o.size) - it.offset } ?: 0 }
                val byShift = through.minOfOrNull { (if (l == 0) n else level.hostCopies) - it.offsets[l] }
                out.add(listOfNotNull(byOwn, byShift).minOrNull() ?: 0)
            }
        }
        return out.takeIf { it.all { c -> c >= 1 } }
    }

    /** The orbit a pick rides, as it stands in the all-zero copy — its size is what a span is measured against. */
    private fun orbitOf(
        g: OrbitGesture,
        pick: OrbitPick,
    ): PatternOrbit? = g.levels.getOrNull(pick.level)?.pattern?.orbits?.getOrNull(pick.orbitPos)

    /**
     * Run [tool] once **per copy** and record the whole fan as one `orbit` step (OP-23).
     *
     * Edit-time bookkeeping, in the outline-follow's sense (OP-18): the machine saves the clicks, the file
     * stores the rule the clicks stated. Every copy is an ordinary application of the same `ToolDef.build`
     * over shared members, so nothing here knows what the tool does — and because the copies are built *on*
     * the members rather than transformed off copy 0, adjacent copies share their nodes outright.
     *
     * One step, so one checkpoint and one undo removes the whole orbit; and one scoring, since the first copy
     * scores its choices from its own clicks and the rest are handed the result verbatim (OP-1).
     *
     * **One freedom, all copies** ([toolScalarRefs]): a defaulted scalar nobody stated is created *once*, for
     * the gesture, and every copy is built on it — which is what a pattern's own rule reading demands, since a
     * value per copy would be geometry the rule does not state. So the fan's turn count is one number, restated
     * once by the `orbit` step (`dofs=`) and editable through *any* of the copies ([ownFields]).
     */
    fun buildOrbit(
        plan: Replication,
        tool: ToolDef,
        scalars: List<ScalarEntry>,
        dofs: List<Quantity> = emptyList(),
    ): OrbitGesture? {
        val g0 = plan.gesture ?: return null
        val p = plan.pattern
        val g =
            OrbitGesture(
                g0.levels, g0.toolId, g0.points, g0.elements, g0.cells, g0.cellIndices, scalars, g0.signs, g0.count,
                chainsPart = g0.chainsPart,
            )
        var scored: List<Int> = g0.signs
        val perCopy = LinkedHashMap<List<Int>, List<Element>>()
        val journalBefore = journal.size
        val outerBuilding = building
        recording(
            "orbit",
            *orbitArgs(g),
            skipIfEmpty = true,
            argsAfter = { if (scored.isEmpty()) emptyList() else listOf(Arg.Keyed("signs", Arg.Text(scored.joinToString(";")))) },
        ) {
            // once, before the loop: the freedoms belong to the gesture, not to a copy of it
            val refs = toolScalarRefs(tool, dofs, scalars)
            var first = true
            for (index in cells(plan.copies)) {
                val before = elements.toHashSet()
                // a fresh pass per copy: a chained gesture resolves its base against what the copy before it
                // just built, so this loop is the one place a cached pass would be looking at the wrong document
                val copyPicks = picksFor(g, index, scored, Evaluator()) ?: break
                // …and *which* copy is being built, so a pattern this one creates is born into the nesting (#18)
                building = Building(g, index)
                try {
                    tool.build(this, copyPicks, refs)
                } finally {
                    building = outerBuilding
                }
                val made = elements.filter { it !in before }
                // the first copy scores its choices from its own clicks; every other copy is handed the
                // result, and the step writes it down — so a reload never scores again (OP-1)
                if (first && scored.isEmpty()) scored = storedSigns(made)
                first = false
                perCopy[index] = made
            }
        }
        // nothing built means the gesture had no effect at all: the caller falls back to applying it once.
        // Asked of what the copies *made* rather than of the journal, because a ride nested inside another one
        // is absorbed into the outer `orbit` step (#18) and so adds no step of its own — which is exactly the
        // rule [recording] has always followed for a tool that calls several document operations.
        if (perCopy.values.all { it.isEmpty() }) return null
        // …and the step that re-runs it: its own when it has one, and otherwise the enclosing ride's, stamped in
        // by that ride's [adoptSteps] once its step exists
        g.step = if (journal.size > journalBefore) journal.lastOrNull() else null
        // a nested pattern has no step of its own — the one `orbit` step that re-runs the whole ride is what
        // owns it, and every rule inside it (#18)
        adoptSteps(g, g.step)
        g.fan = plan.copies
        registerOrbits(g, perCopy, plan.copies)
        p.gestures.add(g)
        return g
    }

    /** Give every rule a copy of [g] built the step that re-runs it — [g]'s own `orbit` step (#18). */
    private fun adoptSteps(
        g: OrbitGesture,
        step: Step?,
    ) {
        for (patterns in g.inner.values) {
            for (q in patterns) {
                q.step = step
                for (inner in q.gestures) {
                    inner.step = step
                    adoptSteps(inner, step)
                }
            }
        }
    }

    /** A copy index folded into the level's own range: a ring wraps, a row does not (OP-23, per level). */
    private fun wrapAt(
        g: OrbitGesture,
        level: Int,
        i: Int,
    ): Int {
        val l = g.levels.getOrNull(level) ?: return i
        if (!l.wraps) return i
        val n = if (level == 0) l.count else maxOf(l.hostCopies, 1)
        return ((i % n) + n) % n
    }

    /** Every cell of a nesting [counts] deep, outer index major — so names line up from the start (OP-23). */
    private fun cells(counts: List<Int>): List<List<Int>> {
        var out = listOf(emptyList<Int>())
        for (n in counts) out = out.flatMap { prefix -> (0 until n).map { prefix + it } }
        return out
    }

    /** The `orbit` step's arguments — the gesture's rule, with the member picks written as `e2@1` (or `e2@0@3`). */
    private fun orbitArgs(g: OrbitGesture): Array<Arg> {
        fun ref(pick: OrbitPick): Arg = pick.fixed?.let { Arg.El(it) } ?: Arg.Member(pick.anchor, pick.offsets)
        return listOfNotNull(
            Arg.Label(g.pattern.name),
            Arg.Text(g.toolId),
            Arg.Keyed("pts", Arg.Refs(g.points.map { ref(it) })).takeIf { g.points.isNotEmpty() },
            Arg.Keyed("els", Arg.Refs(g.elements.map { ref(it) })).takeIf { g.elements.isNotEmpty() },
            // the chained base, said rather than named: copy k's base body is a different element for every k
            // *and* for every count, so a baked list of names could not survive a re-stamp — what is stable is
            // the rule that produced it (see [OrbitGesture.chainsPart])
            Arg.Keyed("part", Arg.Text("tip")).takeIf { g.chainsPart },
            Arg.Keyed("cells", Arg.Positions(g.cells)).takeIf { g.cells.isNotEmpty() },
            Arg.Keyed("scalar", Arg.Scs(g.scalars)).takeIf { g.scalars.isNotEmpty() },
            Arg.Keyed("count", Arg.Text(g.count.toString())).takeIf { g.count > 0 },
        ).toTypedArray()
    }

    /** The picks copy [index] of [g] applies to: members shifted per level, clicks carried into that cell. */
    private fun picksFor(
        g: OrbitGesture,
        index: List<Int>,
        signs: List<Int>,
        ev: Evaluator,
    ): Picks? {
        // The composed address, resolved outermost inward (#18): the outer indices name *which copy's* pattern
        // this pick's orbit belongs to, and the innermost index says where along that orbit it sits. Nothing is
        // transformed and nothing is searched — the ride that built the nested patterns kept them by copy.
        fun at(pick: OrbitPick): Element? {
            pick.fixed?.let { return it }
            val outer = (0 until pick.level).map { l -> wrapAt(g, l, pick.offsets[l] + index[l]) }
            val pat = patternAt(g, pick.level, outer) ?: return null
            val o = pat.orbits.getOrNull(pick.orbitPos) ?: return null
            val i = pick.offsets[pick.level] + index[pick.level]
            return o.members.getOrNull(if (g.levels[pick.level].wraps) ((i % o.size) + o.size) % o.size else i)
        }

        @Suppress("UNCHECKED_CAST")
        val points = g.points.map { (at(it) ?: return null).ref as? PointRef ?: return null }
        // the chain (OP-17's sequential-feature rule, per index): copy k's base is the tip *now*, which after
        // copy k-1 is copy k-1's own result — so one Cut on one member becomes a bolt circle of pockets in one
        // body, and not four features that each fork back onto the plate
        val part = if (g.chainsPart) listOfNotNull(facePartTip(ev)) else emptyList()
        val els = part + g.elements.map { at(it) ?: return null }
        val clicks =
            g.cells.mapIndexed { i, c ->
                val base = g.cellIndices.getOrElse(i) { List(index.size) { 0 } }
                composedCell(g, index.mapIndexed { l, j -> base.getOrElse(l) { 0 } + j }, c, ev)
            }
        return Picks(points, els, clicks.lastOrNull() ?: Vec2(0.0, 0.0), clicks, count = g.count, signs = signs)
    }

    /**
     * Turn what the copies built into orbits: element *s* of every copy is one orbit, indexed by copy — so
     * the outputs of a replicated gesture are members at their own index and the orbit **grows**.
     *
     * **One orbit per level** (#18), because a nested copy is indexed twice: element *s* of copy (*j*, *k*) is
     * a member of the outer pattern at *j* (holding *k* fixed) and of copy *j*'s inner pattern at *k* (holding
     * *j* fixed). Registering both is what makes `e@j@k` resolvable — and each orbit is owned by the pattern
     * of *its* level, so the inner ones hang off the very patterns this gesture's copies built.
     *
     * A copy that built a different number of elements than its siblings is refused as a member set rather
     * than half-registered: the structure of a step is fixed at build (OP-5), so an uneven fan means the
     * geometry gave out somewhere, and saying so is more use than a ragged pattern.
     */
    private fun registerOrbits(
        g: OrbitGesture,
        perCopy: Map<List<Int>, List<Element>>,
        counts: List<Int>,
    ) {
        val k = perCopy.values.firstOrNull()?.size ?: return
        if (k == 0) return
        if (perCopy.values.any { it.size != k } || perCopy.size != counts.fold(1) { a, b -> a * b } || perCopy.size < 2) {
            note = "Pattern ${g.pattern.name}: ${g.label} did not build the same geometry at every index, so its results are not pattern members"
            return
        }
        for (l in counts.indices) {
            val others = cells(counts.filterIndexed { i, _ -> i != l })
            for (rest in others) {
                val prefix = rest.take(l)
                val owner = patternAt(g, l, prefix) ?: continue
                for (s in 0 until k) {
                    val members =
                        (0 until counts[l]).map { i ->
                            perCopy[rest.take(l) + i + rest.drop(l)]?.get(s) ?: return
                        }
                    val o = PatternOrbit(owner, members)
                    owner.orbits.add(o)
                    g.outputs.add(o)
                }
            }
        }
    }

    /**
     * Replay a recorded `orbit` step: the same fan, from the rule the file states (OP-23).
     *
     * Nothing is discovered here — the offsets, the cell-local clicks, the scalars and the scored signs all
     * come from the step, and the only thing recomputed is where a cell-local click lands, which follows the
     * pattern's *current* shape and is exactly what makes a re-stamped count come out right.
     *
     * The **levels** are read off the picks' own depth (#18): a pick written `e@j@k` names an element two
     * levels deep, so the gesture rides two, and a pick with fewer offsets is invariant in the levels it does
     * not mention. Nothing about the nesting is stored twice.
     */
    internal fun replayOrbit(
        p: Pattern,
        tool: ToolDef,
        points: List<Pair<Element, List<Int>?>>,
        els: List<Pair<Element, List<Int>?>>,
        cells: List<Vec2>,
        scalars: List<ScalarEntry>,
        signs: List<Int>,
        count: Int,
        chainsPart: Boolean,
        dofs: List<Quantity> = emptyList(),
    ): OrbitGesture? {
        val depth = (points + els).maxOfOrNull { it.second?.size ?: 0 } ?: 0
        if (depth == 0) return null
        // the levels, read off the picks' own depth: level 0 is the pattern the step names, and each deeper one
        // is the anchor pattern of the orbit the pick at that depth is member 0 of
        val levels = ArrayList<OrbitLevel>()
        levels.add(OrbitLevel(p))
        for (l in 1 until depth) {
            val q =
                (points + els).firstNotNullOfOrNull { spec ->
                    if ((spec.second?.size ?: 0) <= l) null else memberSites(spec.first).firstOrNull { it.level == l }?.pattern
                } ?: return null
            val anchor = q.enclosing?.let { h -> h.inner[List(l) { 0 }]?.getOrNull(q.enclosingSlot) } ?: q
            levels.add(OrbitLevel(anchor, anchor.enclosing?.let { h -> (h.inner.keys.maxOfOrNull { it.getOrElse(l - 1) { 0 } } ?: 0) + 1 } ?: 0))
        }

        fun pick(spec: Pair<Element, List<Int>?>): OrbitPick? {
            val offs = spec.second ?: return OrbitPick(spec.first, -1, emptyList(), spec.first)
            val site = memberSites(spec.first).firstOrNull { it.level == offs.size - 1 } ?: return null
            return OrbitPick(spec.first, site.orbitPos, offs, null)
        }

        val pointPicks = points.map { pick(it) ?: return null }
        val elPicks = els.map { pick(it) ?: return null }
        // the click cells, derived from the tool's slots exactly as the recording derived them
        val cellIndices = ArrayList<List<Int>>()
        var pi = 0
        var ei = 0
        for (slot in tool.slots) {
            val pick =
                when (slot) {
                    SlotKind.PLACE_POINT, SlotKind.POINT -> pointPicks.getOrNull(pi++)
                    // an optional slot, replayed exactly as it was recorded: a point if the step names one
                    SlotKind.OPTIONAL_POINT -> pointPicks.getOrNull(pi)?.also { pi++ }
                    SlotKind.SIDE -> null
                    else -> elPicks.getOrNull(ei++)
                }
            cellIndices.add((0 until depth).map { l -> pick?.offsets?.getOrNull(l) ?: 0 })
        }
        val g = OrbitGesture(levels, tool.id, pointPicks, elPicks, cells, cellIndices, scalars, signs, count, chainsPart)
        val copies = copiesFor(g, p.count) ?: return null
        val plan = Replication(p, g, copies, null)
        return buildOrbit(plan, tool, scalars, dofs)
    }

    /**
     * Why [p] cannot be re-stamped at [n], or null when it can (OP-23).
     *
     * The one thing mod-n arithmetic cannot absorb: a gesture that spans **more neighbours than the new count
     * has members**. "Member 0 to member 4" is a pair at six; at three it is not a pair at all, and folding it
     * to (0, 1) would silently make a different drawing. So it is refused, by name.
     *
     * Checked at **level 0 only**, and deliberately (#18): a deeper level's count is not what this edit
     * changes, so its spans mean at the new count exactly what they meant at the old one. What a smaller outer
     * count genuinely loses inside a nested ride is caught by the replay's own drop rule, which names it.
     */
    fun restampRefusal(
        p: Pattern,
        n: Int,
    ): String? {
        if (n < 2) return "a pattern needs at least 2 instances"
        if (n == p.count) return "pattern ${p.name} already has $n instances"
        val sizes = HashMap<PatternOrbit, Int>()
        sizes[p.ring] = n
        for (g in p.gestures) {
            val over = g.ridingAt(0).firstOrNull { (sizes[orbitOf(g, it)] ?: 0) <= it.offset }
            if (over != null) {
                return "can't re-stamp pattern ${p.name} at $n: its ${g.label} spans ${over.offset + 1} members " +
                    "of a ${sizes[orbitOf(g, over)] ?: 0}-member orbit — use the tool again instead"
            }
            val copies = copiesFor(g, n, sizes) ?: return "can't re-stamp pattern ${p.name}: its ${g.label} rides nothing"
            if (copies[0] < 2) return "can't re-stamp pattern ${p.name} at $n: its ${g.label} would have no second copy"
            for (o in g.outputs) sizes[o] = if (o.pattern === p) copies[0] else o.size
        }
        return null
    }

    /**
     * Why the count [g] carries — a nested pattern's own, the sides of every polygon of a ride (#18) — cannot
     * become [n], or null when it can.
     *
     * The subject is the **gesture**, not a pattern, because that is where the literal lives: a ride's `count=`
     * is one number for the whole fan, so changing it re-runs the ride and every copy re-stamps together. What
     * the count may be is the tool's own business ([ToolDef.minCount]), exactly as it is when the tool is used.
     */
    fun gestureCountRefusal(
        g: OrbitGesture,
        n: Int,
    ): String? {
        val tool = toolDef(g.toolId)
        val least = maxOf(tool?.minCount ?: 2, 2)
        if (g.count <= 0) return "${g.label} has no count of its own to re-stamp"
        if (n < least) return "${g.label} needs at least $least"
        if (n == g.count) return "${g.label} already has $n"
        if (g.step == null) return "${g.label} has no step to re-run"
        return null
    }

    /**
     * **Re-trace a closed boundary** from two pieces — the Outline tool's follow, re-run as bookkeeping when
     * a re-stamp changes how many pieces the loop has (OP-14, OP-23).
     *
     * Deliberately the same two sources of truth the interactive follow consults ([continuationsFrom],
     * [handoverPosition]) and deliberately *not* used on load: the file keeps the full ordered boundary, so a
     * reload discovers nothing. What a re-stamp does is an **edit**, and an edit may follow.
     */
    fun followedLoop(
        a: Element,
        b: Element,
        ev: Evaluator = Evaluator(),
    ): Pair<List<Element>, List<Vec2>>? {
        var entered = handoverPosition(a, b, ev) ?: return null
        val chain = arrayListOf(a, b)
        var budget = elements.size + 1
        var closed = false
        while (budget-- > 0) {
            val next = continuationsFrom(chain.last(), entered, ev).singleOrNull() ?: return null
            if (next.piece === a) {
                closed = chain.size >= 3
                break
            }
            if (chain.any { it === next.piece }) return null
            chain.add(next.piece)
            entered = next.at
        }
        if (!closed) return null
        val n = chain.size
        val clicks =
            (0 until n).map { i ->
                val enter = handoverPosition(chain[(i - 1 + n) % n], chain[i], ev) ?: return null
                val exit = handoverPosition(chain[i], chain[(i + 1) % n], ev) ?: return null
                pointBetweenOn(chain[i], enter, exit, ev) ?: enter
            }
        return chain to clicks
    }

    /**
     * A regular polygon, optionally with its corners rounded in the same gesture (OP-23).
     *
     * With no radius this is the polygon it always was, recorded as the same `tool polygon` step — the count
     * is structural and the vertices are rotations of the clicked one. With a radius it is the pattern
     * composition written out: a circular pattern of the vertex, one segment between neighbouring members
     * (which fans to every side) and one fillet on a corner (which fans to every corner). So the everyday
     * shortcut and the general mechanism are the *same* construction, and the file says which one it is.
     */
    fun regularPolygonGesture(
        picks: Picks,
        scalarRefs: List<ScalarRef>,
    ) {
        val centre = picks.points.getOrNull(0) ?: return
        val vertex = picks.points.getOrNull(1) ?: return
        val radius = scalarRefs.firstOrNull()
        val scalars = entriesOf(scalarRefs)
        val count = picks.count
        val r = radius?.let { ((Evaluator().eval(it.node) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.mm ?: 0.0 } ?: 0.0
        if (radius == null || r <= 0.0) {
            recordingTool(Tools.POLYGON, picks, scalars) { regularPolygon(centre, vertex, count) }
            return
        }
        val p = createPattern(PatternKind.CIRCULAR, vertex, centre, count) ?: return
        val ev = Evaluator()
        val pos = { el: Element -> pointOf(el.ref.node, ev) ?: Vec2(0.0, 0.0) }
        val v0 = pos(p.ring.members[0])
        val v1 = pos(p.ring.members[1])
        val v2 = pos(p.ring.members[2 % p.ring.size])
        // one side, between neighbouring members: the orbit gives the other count-1
        val side =
            replicationOf(
                Tools.byId(Tools.SEGMENT)!!,
                Picks(listOf(p.ring.members[0].ref as PointRef, p.ring.members[1].ref as PointRef), emptyList(), v1, listOf(v0, v1)),
            )
        val sides = side?.gesture?.let { buildOrbit(side, Tools.byId(Tools.SEGMENT)!!, emptyList()) } ?: return
        val e0 = sides.outputs[0].members[0]
        val e1 = sides.outputs[0].members[1]
        // one rounding, on the corner those two sides make: the orbit rounds every corner
        val fillet = Tools.byId(Tools.FILLET)!!
        val plan =
            replicationOf(
                fillet,
                Picks(emptyList(), listOf(e0, e1), v1, listOf(v0 + (v1 - v0) * 0.9, v1 + (v2 - v1) * 0.1)),
            ) ?: return
        buildOrbit(plan, fillet, scalars)
        note = "Rounded polygon: ${p.name}, $count sides and $count roundings — retype the radius to re-round, or re-stamp the count"
    }

    /**
     * The circle **tangent to three lines** — the LLL case of Apollonius' problem, and the first of that
     * family to get a tool.
     *
     * Three lines that make a triangle admit four tangent circles: the incircle and the three excircles. All
     * four are the *same* construction with two discrete choices in it, which is the whole reason this needs
     * no solver and no new geometry — the composition is
     *
     * ```
     * centre = intersectLL( bisector(l1, l2, s12), bisector(l1, l3, s13) )   // two branches, four centres
     * touch  = projectToLine(centre, l1)
     * circle = circleCP(centre, touch)
     * ```
     *
     * and each *bisector* is itself a composition of ops that already existed: the legs' crossing
     * (`intersectLL` + `Select`), a unit step along each leg (`pointAlongLine`, whose sign is the branch) and
     * `angleBisector` of that corner. Tangency is therefore **by construction**: the centre is equidistant
     * from all three lines because it lies on a bisector of each pair, and the radius *is* the distance to
     * `l1`, since the circle is built through the foot of the perpendicular there. Nothing asserts it, so
     * dragging any line keeps all three tangencies exactly.
     *
     * Which of the four is a **stored discrete choice** (OP-1), scored once from the final click — the circle
     * whose circumference is nearest it — and written into the step's `signs=` as the two bisector branches
     * (the fillet's precedent, OP-18). Replay takes them verbatim, so a reload rebuilds the circle the user
     * clicked even when a line has since moved past that click; re-scoring would re-decide.
     *
     * Invalid rather than absent when the lines admit nothing (two parallel, or all three concurrent): every
     * `Select` in the chain reports its own empty set (OP-3), so the circle simply hides and comes back when
     * the lines make a triangle again.
     */
    fun circleFrom3Tangents(
        leg1: Element,
        leg2: Element,
        leg3: Element,
        at: Vec2,
        signs: List<Int> = emptyList(),
    ): Element? {
        val l1 = carrierLine(leg1)
        val l2 = carrierLine(leg2)
        val l3 = carrierLine(leg3)
        // the clicks are scored **once**, on the live gesture; a replayed step hands its answer back here
        val (s12, s13) =
            storedLegSigns(signs) ?: tangentCircleSigns(l1, l2, l3, at) ?: run {
                // a refusal is said out loud rather than leaving three picks that quietly built nothing
                note =
                    "No circle is tangent to all three of ${nameOf(leg1)}, ${nameOf(leg2)} and ${nameOf(leg3)}: " +
                    "two of them are parallel, or all three meet in one point"
                return null
            }
        val centre =
            cx.select(
                cx.intersectLL(bisectorOfLines(l1, l2, s12), bisectorOfLines(l1, l3, s13)),
                +1,
                "no circle is tangent to all three lines (two of them are parallel, or all three meet in a point)",
            )
        val touch = cx.projectToLine(centre, l1)
        val el = add(cx.circleCP(centre, touch), ElementKind.CIRCLE, Styles.CURVE)
        registerSigns(el, listOf(s12, s13))
        // Deliberately **no** joints (unlike a fillet's tangencies): a fillet *replaces* a corner, so the
        // boundary tracer must know where it hands over, while an inscribed circle replaces nothing and
        // touching a line is not a handover between two pieces of one boundary.
        return el
    }

    /**
     * One of the two bisectors of [l1] and [l2], as nodes: their crossing, a unit step along each leg
     * ([sign] flipping the second one's), and the bisector of that corner — the twin of
     * [FilletMath.bisector], which is what the scoring and the preview use on values.
     */
    private fun bisectorOfLines(
        l1: LineRef,
        l2: LineRef,
        sign: Int,
    ): LineRef {
        val corner = cx.select(cx.intersectLL(l1, l2), +1, "parallel lines have no crossing to bisect")
        val step = cx.const(1.0.mm)
        return cx.angleBisector(cx.pointAlongLine(l1, corner, step, +1), corner, cx.pointAlongLine(l2, corner, step, sign))
    }

    /** Which of the four tangent circles the click at [at] meant, as the two bisector branches (OP-1). */
    private fun tangentCircleSigns(
        l1: LineRef,
        l2: LineRef,
        l3: LineRef,
        at: Vec2,
    ): Pair<Int, Int>? {
        val ev = Evaluator()
        val lines = listOf(l1, l2, l3).map { ((ev.eval(it.node) as? EvalResult.Ok)?.value as? LineValue)?.line ?: return null }
        return FilletMath.nearestTangentCircle(lines[0], lines[1], lines[2], at)?.first
    }

    /** Both external (or internal) common tangents of two circles. */
    fun commonTangents(
        c1: Element,
        c2: Element,
        inner: Boolean,
    ): List<Element> {
        val a = carrierCircle(c1)
        val b = carrierCircle(c2)
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
        val ref = carrierCircle(circle)
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
    ) = addLike(cx.mirror(geom.ref as Ref<Value>, axis.ref as LineRef), geom)

    @Suppress("UNCHECKED_CAST")
    fun rotate(
        geom: Element,
        center: PointRef,
        angle: ScalarRef,
    ) = addLike(cx.rotate(geom.ref as Ref<Value>, center, angle), geom)

    /**
     * Geometry reflected through a **point** — [mirror]'s sibling, with a centre where Mirror has an axis
     * (OP-14: the structural intent gets its own spelling, so no angle can drift off the half turn).
     */
    @Suppress("UNCHECKED_CAST")
    fun pointReflect(
        geom: Element,
        center: PointRef,
    ) = addLike(cx.pointReflect(geom.ref as Ref<Value>, center), geom)

    @Suppress("UNCHECKED_CAST")
    fun scale(
        geom: Element,
        center: PointRef,
        factor: ScalarRef,
    ) = addLike(cx.scaleGeom(geom.ref as Ref<Value>, center, factor), geom)

    @Suppress("UNCHECKED_CAST")
    fun translateByVector(
        geom: Element,
        from: PointRef,
        to: PointRef,
    ) = addLike(cx.translateByVector(geom.ref as Ref<Value>, from, to), geom)

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

    /**
     * The measured length of a curve (OP-4) — a segment's, an arc's, an elliptic arc's or an ellipse's
     * whole circumference.
     *
     * Exact for the first two and **computed to a stated tolerance** for the conics (OP-15): an elliptic
     * integral has no closed form, so the status line says so where a conic is measured, and says nothing
     * where the number is exact. That asymmetry *is* the honesty line — construction exact, measurement
     * approximate — and it is stated once, here, rather than left for a reader to infer.
     */
    @Suppress("UNCHECKED_CAST")
    fun measureLength(seg: Element): ScalarEntry? =
        when (seg.kind) {
            ElementKind.SEGMENT -> measurement("len", cx.measureLength(seg.ref as SegmentRef))
            ElementKind.ARC -> measurement("len", cx.measureArcLength(seg.ref as ArcRef))
            ElementKind.ELLIPTIC_ARC ->
                measurement("len", cx.measureEllipticArcLength(seg.ref as EllipticArcRef)).also {
                    note = "${it.name} is computed numerically to ±${Conics.LENGTH_TOL_MM} mm — an elliptic arc's length has no closed form (OP-15)"
                }
            ElementKind.ELLIPSE ->
                measurement("len", cx.measureCircumference(seg.ref as EllipseRef)).also {
                    note = "${it.name} is computed numerically to ±${Conics.LENGTH_TOL_MM} mm — an ellipse's circumference has no closed form (OP-15)"
                }
            // the same statement one curve family on: a construction over a function curve is exact, and its
            // *measured* length is not — so the number is flagged where it is taken (OP-15, OP-24's line)
            ElementKind.FUNC_CURVE ->
                measurement("len", cx.measureFuncCurveLength(seg.ref as FuncCurveRef)).also {
                    note = "${it.name} is computed numerically to ±${FuncCurves.LENGTH_TOL_MM} mm — a function curve's length has no closed form (OP-15)"
                }
            else -> {
                note = "${nameOf(seg)} has no length to measure"
                null
            }
        }

    fun measureRadius(circle: Element) = measurement("radius", cx.measureRadius(carrierCircle(circle)))

    fun measureX(p: PointRef) = measurement("x", cx.measureX(p))

    fun measureY(p: PointRef) = measurement("y", cx.measureY(p))

    fun measureAngleLines(
        l1: Element,
        l2: Element,
    ) = measurement("angle", cx.measureAngleLines(carrierLine(l1), carrierLine(l2)))

    // ---- 3D measurements (OP-4, forward): a solid's numbers, as panel scalars ----
    //
    // The 3D→2D half of the seam that needs no geometry at all (OP-17): a measurement of a solid is an
    // ordinary read-only scalar entry, so it can drive a *new* 2D construction — which is how a papercraft
    // net gets its edge lengths from the part it wraps. Forward only: wiring one back into an ancestor of
    // the same solid is a cycle and is refused where every other wiring is ([wireParameter]).

    /** The volume of the solid [el] (dimension L³) — measured from its mesh, exact for the mesh. */
    @Suppress("UNCHECKED_CAST")
    fun measureSolidVolume(el: Element): ScalarEntry? {
        if (el.kind != ElementKind.SOLID) return null
        return measurement("vol", cx.measureVolume(el.ref as SolidRef))
    }

    /**
     * The extent of the solid [el] along the world [axis] (a length).
     *
     * **Which axis is a stored discrete choice, and the tool id is where it is stored** (OP-1's rule
     * applied to a tool): there are three tools, X, Y and Z, exactly as there are three boolean tools for
     * `BoolOp` and two tangent tools for inner/outer. The alternative — one tool that reads the axis off
     * the placing click, the way an angular dimension reads its sector — cannot work here: the choice
     * includes **Z**, which no click in a plan view can name. And a `tool` step records its id verbatim, so
     * three ids need no new argument in the file format for a choice that must replay identically (OP-18).
     */
    @Suppress("UNCHECKED_CAST")
    fun measureSolidExtent(
        el: Element,
        axis: Axis3,
    ): ScalarEntry? {
        if (el.kind != ElementKind.SOLID) return null
        return measurement("ext${axis.name.lowercase()}", cx.measureBBoxExtent(el.ref as SolidRef, axis))
    }

    // ---- dimensions: annotation over an ordinary measurement node (OP-4) ----
    //
    // Each of these creates *one* element (the annotation) plus the measurement entry it shows, so the
    // measured value is a first-class scalar like any other — readable in the panel, and wirable *from*.
    // Nothing is asserted: a dimension is the driven side of OP-4's driving-XOR-driven rule.
    //
    // Its own placement DOF are fresh source nodes, seeded from the click that placed it and thereafter
    // state of their own: a handle writes them (OP-13) and the save restates them (OP-18). [dofs] is that
    // restated state on replay — given, it is used verbatim, so a reload lands exactly where the drag left
    // it instead of re-deriving from the click.

    /** An aligned linear dimension between two point elements, its dimension line through [at]. */
    @Suppress("UNCHECKED_CAST")
    fun linearDimension(
        pa: Element,
        pb: Element,
        at: Vec2,
        dofs: List<Quantity> = emptyList(),
    ): Element? {
        if (!pa.isPoint || !pb.isPoint || pa === pb) return null
        // A **point in space** (OP-25, OP-26) is refused by name rather than measured: this annotation's value
        // is the distance between two points *of the working plane* and its graphic is drawn in that plane, so
        // handing it one would either measure a projection while drawing the number of a distance in space or
        // the reverse. Named as a scope, and DESIGN.md records the extension it wants — a dimension whose
        // graphic lives in the 3D view, which is where both of its ends are drawn.
        for (p in listOf(pa, pb)) {
            if (p.inSpace) {
                note =
                    "${nameOf(p)} is a point in space, and a linear dimension is measured and drawn in the " +
                    "sketch plane — dimension two points of ${activeSpace.name}, or read this one's own " +
                    "position in the panel"
                return null
            }
        }
        val a = pa.ref as PointRef
        val b = pb.ref as PointRef
        val ref = cx.measureDistance(a, b)
        measurement("dist", ref)
        val ev = Evaluator()
        val wa = ((ev.eval(a.node) as? EvalResult.Ok)?.value as? PointValue)?.p ?: Vec2(0.0, 0.0)
        val wb = ((ev.eval(b.node) as? EvalResult.Ok)?.value as? PointValue)?.p ?: Vec2(0.0, 0.0)
        val n = (wb - wa).normalized().perp()
        val offset = SourceNode(nextId("dl"), ScalarValue(dofs.getOrNull(0) ?: Quantity.mm((at - wa).dot(n))))
        return annotate(ref, LinearDimension(ref, a, b, offset))
    }

    /** A radial dimension on a circle or arc, its leader through [at]. */
    fun radialDimension(
        curve: Element,
        at: Vec2,
        dofs: List<Quantity> = emptyList(),
    ): Element? {
        if (curve.isElliptic) {
            note = "${nameOf(curve)} is an ellipse, which has no single radius — dimension the distance between its axis points (Key points), or measure its length"
            return null
        }
        if (!curve.isCentric) return null
        val circle = carrierCircle(curve)
        val ref = cx.measureRadius(circle)
        measurement("radius", ref)
        val c = (Evaluator().eval(circle.node) as? EvalResult.Ok)?.let { (it.value as? CircleValue)?.circle }
        val d = if (c == null) Vec2(1.0, 0.0) else at - c.center
        val angle = SourceNode(nextId("da"), ScalarValue(dofs.getOrNull(0) ?: Quantity.rad(d.angle())))
        val reach = SourceNode(nextId("dr"), ScalarValue(dofs.getOrNull(1) ?: Quantity.mm(d.length() - (c?.radius ?: 0.0))))
        return annotate(ref, RadialDimension(ref, circle, angle, reach))
    }

    /**
     * An angular dimension between two lines, naming the sector [at] lies in. That sector is resolved here,
     * once, into the stored signs the measurement itself is built from (OP-1) — so replaying the same click
     * makes the same choice, and moving the lines afterwards never changes which angle is meant.
     */
    fun angularDimension(
        l1: Element,
        l2: Element,
        at: Vec2,
        dofs: List<Quantity> = emptyList(),
    ): Element? {
        val a = carrierLine(l1)
        val b = carrierLine(l2)
        val ev = Evaluator()
        val la = ((ev.eval(a.node) as? EvalResult.Ok)?.value as? LineValue)?.line ?: return null
        val lb = ((ev.eval(b.node) as? EvalResult.Ok)?.value as? LineValue)?.line ?: return null
        val vertex = GeomMath.intersectLL(la, lb).points.firstOrNull() ?: return null
        val (s1, s2) = AngularDimension.signsToward(la.dir, lb.dir, at - vertex)
        val ref = cx.measureAngleSector(a, b, s1, s2)
        measurement("angle", ref)
        val radius = SourceNode(nextId("dR"), ScalarValue(dofs.getOrNull(0) ?: Quantity.mm((at - vertex).length())))
        return annotate(ref, AngularDimension(ref, a, b, s1, s2, radius))
    }

    /** The one displayable element of a dimension: the measurement it shows, drawn as [ann]. */
    private fun annotate(
        ref: ScalarRef,
        ann: DimensionAnnotation,
    ): Element =
        add(ref, ElementKind.DIMENSION, Styles.ANNOTATION).also {
            it.annotation = ann
            it.handle = ann
        }

    companion object {
        /**
         * The name of the default sketch space (OP-17): the **plan**, world XY. Every element belongs to a
         * space and this is the one they belonged to before spaces existed, which is why adding them
         * changed nothing about an existing drawing or an existing file.
         */
        const val PLAN_SPACE = "plan"

        /**
         * Whether [node] is **material** of the solid that takes it as an input, rather than merely an
         * ancestor of it: material means a *solid-valued* input, which is exactly what a boolean takes. A
         * frame accessor (`facePlane`, `sideFacePlane`) or a `section` passes through a plane or a region and
         * consumes no material at all.
         *
         * One rule, three readers: [Scene3] tells an operand from an output by it (a plate is still an
         * output while something is merely sketched on its face), [tipOfChain] follows it **forwards** to the
         * end of a part's boolean chain (OP-17's sequential-feature rule), and a blend follows a body's first
         * material input **backwards** down its spine to the analytic body whose edges it names.
         */
        fun isMaterial(
            ev: Evaluator,
            node: Node,
        ): Boolean = (ev.eval(node) as? EvalResult.Ok)?.value is SolidValue
    }
}

/**
 * How near a **format-1** rider's recorded position must be for it to arbitrate the anchor a stored distance
 * along a carrier was measured from (mm) — see [Document.migratedRiderDof].
 *
 * A real tolerance rather than exact equality, because the position travelled through a whole construction's
 * arithmetic before it was written; and far below anything a drawing can show, because the two candidate
 * readings differ by the anchor's own offset, which is a visible distance whenever it is not zero.
 */
private const val RIDER_MIGRATION_TOL = 1.0e-6

/** Head height a new interval carries by default (mm) — a door; the sill defaults to the floor. */
private const val DEFAULT_HEAD = 2100.0

/**
 * The narrowest an opening may be made by dragging or typing (mm) — see [Document.setIntervalWidth].
 *
 * A floor is needed rather than zero because zero is where the two jambs meet: the drawing would lose the
 * opening entirely while the model still carried it, and the next drag would have nothing to grab.
 */
private const val MIN_INTERVAL_WIDTH = 1.0

/** Default element styles. */
object Styles {
    val FREE_POINT = Style(stroke = "#1f77b4", width = 1.0)
    val DERIVED_POINT = Style(stroke = "#2ca02c", width = 1.0)
    val ON_CURVE = Style(stroke = "#ff7f0e", width = 1.0)
    val CURVE = Style(stroke = "#333333", width = 1.5)
    val CONSTRUCT = Style(stroke = "#9467bd", width = 1.2)
    val INVALID = Style(stroke = "#dddddd", width = 1.0)
    val PREVIEW = Style(stroke = "#ff7f0e", width = 1.0)

    /** A thick path's footprint (OP-21) — heavier than a construction curve, being a drawing. */
    val FOOTPRINT = Style(stroke = "#333333", width = 2.4)

    /** The result layer (OP-14): the drawing itself, weighted so it reads above its scaffolding. */
    val RESULT = Style(stroke = "#111111", width = 2.6)

    /**
     * A solid's **footprint hint** in the 2D canvas (OP-17): the boundary of the sketch it was made
     * from, drawn light. Thin on purpose — the solid's home is the 3D view — but present, because it is
     * what makes the solid pickable, and therefore selectable and deletable, in the view that has picking.
     */
    val SOLID = Style(stroke = "#8fa6c4", width = 1.2)

    /**
     * A **curve in space** (OP-26). Its own colour, because it is the one drawn thing in the canvas that is
     * not *in* the plane it is drawn on: what the 2D view shows is its projection, and a reader has to be
     * able to tell that from a curve that really lies there. Weighted like a result curve, since a routed
     * path is an output of the drawing and not scaffolding for one.
     *
     * One colour, asked by both back ends ([Scene3.colorOfCurve]), so a curve cannot come out one colour in
     * the plan and another on the GPU.
     */
    val SPACE_CURVE = Style(stroke = "#8c564b", width = 2.0)

    /**
     * A **sphere locus** (OP-28). Construction purple, and **dashed**, which is the whole of what its style
     * has to say: it is the one drawn thing in either view that is not geometry the drawing is *made of* —
     * it is what a point in space was constructed *with*, the same role a construction line plays in the
     * plane, and a reader must not mistake its outline for the silhouette of a ball.
     *
     * Thin and dashed rather than merely dimmed, because [DIMMED] is a *toggle's* answer and this is a fact
     * about the element: a locus reads as scaffolding whether or not anything downstream exists yet.
     */
    val SPHERE_LOCUS = Style(stroke = "#9467bd", width = 1.0, dash = 5.0)

    /**
     * A **cutting chain** (OP-22's extension). Its own colour, for the reason a space curve has one: it is
     * not geometry the drawing is made of but a *tool* — what it says is "material stops here" — and a
     * reader who cannot tell it from a drawn curve cannot tell a cut from an outline. Thin, because the cut
     * it makes is the thing to look at.
     */
    val CHAIN = Style(stroke = "#d62728", width = 1.4)

    /** Scaffolding, once a result exists to contrast it with — dimmed, not hidden. */
    val DIMMED = Style(stroke = "#c9c9c9", width = 1.0)

    /**
     * An element the user has **hidden**, while *Show hidden* is on ([Editor.showHidden]): a ghost.
     *
     * **Dashed**, and that is the load-bearing half. Hidden and scaffolding are two different states of an
     * element and both toggles can be on at once, so the two must be distinguishable *at a glance* — and two
     * light greys are not. A dash says "this is not really in the drawing" in a vocabulary no other style
     * uses, so it cannot be confused with [DIMMED]'s quiet grey, and the cool tint keeps it from reading as a
     * lighter version of the black a result is drawn in.
     */
    val GHOST = Style(stroke = "#9aa7b4", width = 1.0, dash = 4.0)

    /** Annotation (OP-14): thin, and a colour of its own, because it is not part of the drawing. */
    val ANNOTATION = Style(stroke = "#17607d", width = 1.0)

    /**
     * Geometry an armed tool has **picked** but not yet used — half of an operation in progress.
     *
     * Its own colour, deliberately not the selection's: what the next click and the Delete key act on is the
     * selection, so a pick that read as one would say the wrong thing (see [SceneRenderer]).
     */
    val PICKED = Style(stroke = "#e377c2", width = 4.0)
}
