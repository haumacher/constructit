package constructit.editor

import constructit.core.ArcValue
import constructit.core.BezierValue
import constructit.core.CircleValue
import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.FrameValue
import constructit.core.IndirectNode
import constructit.core.LineValue
import constructit.core.LoopValue
import constructit.core.Node
import constructit.core.ParameterNode
import constructit.core.PlaneValue
import constructit.core.PointSetValue
import constructit.core.PointValue
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
import constructit.dsl.CircleRef
import constructit.dsl.Construction
import constructit.dsl.FrameRef
import constructit.dsl.LineRef
import constructit.dsl.LoftPart
import constructit.dsl.LoopRef
import constructit.dsl.PlaneRef
import constructit.dsl.PointRef
import constructit.dsl.PointSetRef
import constructit.dsl.RayRef
import constructit.dsl.Ref
import constructit.dsl.RegionRef
import constructit.dsl.RoundedRectArgs
import constructit.dsl.ScalarRef
import constructit.dsl.SectionRef
import constructit.dsl.SegmentRef
import constructit.dsl.SolidRef
import constructit.dsl.instance
import constructit.dsl.resultOf
import constructit.dsl.roundedRect
import constructit.dsl.valueOf
import constructit.geom.Arc
import constructit.geom.Axis3
import constructit.geom.BoolOp
import constructit.geom.CarrierCurve
import constructit.geom.Feature3
import constructit.geom.FilletLeg
import constructit.geom.FilletMath
import constructit.geom.FilletVariant
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Justification
import constructit.geom.Plane3
import constructit.geom.PlaneSection
import constructit.geom.ProfileElement
import constructit.geom.Section3
import constructit.geom.Segment
import constructit.geom.SolidFace
import constructit.geom.ThickBody
import constructit.geom.Vec2
import constructit.geom.Vec3
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
    LINE,
    RAY,
    CIRCLE,
    SEGMENT,
    ARC,

    /** A cubic Bézier (OP-15) — a curve like any other, pickable and trimmable-adjacent. */
    BEZIER,

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
 * A named **2D sketch space** (OP-17): a plane to embed on, and the coordinates the canvas draws in while
 * it is active.
 *
 * The point of OP-17 is that 2D geometry is *not* plane-resident, so this adds nothing to the engine: a
 * space is organizational + view state (OP-14's third column), and the only thing it contributes to a
 * construction is [plane] — the very argument `sketchOn` already takes. The default space is the **plan**
 * (world XY, [plane] null so every feature keeps building its own `planeXY` node exactly as before); a
 * *face* space names the solid and the boundary-piece index its plane is derived from (OP-8), and its
 * plane is the side face's, **flipped**, so its normal points into the material: that is the direction *Cut*
 * drills (`Document.cutOnFace`), while *Extrude* builds a boss outward from the same footprint.
 *
 * A **datum** space is the general form of the same thing (GitHub #6): its plane contains the carrier of a
 * drawn line ([hinge]) and is rotated out of the space it was defined in by [angle], a live parameter. A
 * datum has no material side, so which way its features build is fixed by its own normal — see
 * `Document.createDatumSpace` for the conventions and `Geom3.datumPlane` for the frame.
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
) {
    /** The plan is exactly the space with no plane node of its own: the world XY plane, by construction. */
    val isPlan: Boolean get() = plane == null

    /** A datum plane (a line and an angle), as opposed to a solid's face or the plan. */
    val isDatum: Boolean get() = hinge != null

    /** A space on a solid's planar side face (OP-8's `sideFacePlane`). */
    val isFace: Boolean get() = plane != null && hinge == null
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
     * Whether grabbing this element can actually move anything. An on-curve point qualifies only
     * while its handle still has a writable field: once every coordinate is driven — welded onto a
     * point, or shared by a loop closure — dragging it is inert, and a dead handle must not steal the
     * grab from the geometry that *can* move (which sits at the same place, being what drives it).
     */
    val draggable: Boolean get() =
        when (kind) {
            ElementKind.POINT, ElementKind.ON_CURVE, ElementKind.DIMENSION -> hasFreeDof
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
            kind == ElementKind.RAY || kind == ElementKind.ARC || kind == ElementKind.BEZIER

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
    val isPoint: Boolean get() = kind == ElementKind.POINT || kind == ElementKind.DERIVED_POINT || kind == ElementKind.ON_CURVE

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
class Joint(val a: Element, val b: Element, val at: PointRef)

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
     * Freedoms the group owns that are **already** rigid under a frame — a polar offset from an anchor
     * inside the group, an angle about a circle inside it. They need no rebinding at all, and counting them
     * is what makes such a group placeable instead of "owning no free point".
     */
    val rigid: List<Freedom> = emptyList(),
    /** Freedoms the placement cannot carry, each with the reason, in the user's words. */
    val uncapturable: List<String> = emptyList(),
) {
    /** Whether the frame would carry any freedom at all — else it would have nothing to move. */
    val carriesSomething: Boolean get() = candidates.isNotEmpty() || paths.isNotEmpty() || ridersToAnchor.isNotEmpty() || rigid.isNotEmpty()
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
class OrthoVertex(val ref: PointRef, var corner: OrthoCornerHandle, val ownAxis: Int, val local: PointRef) {
    /** Where a placement inserts the frame: the node [ref] names, bound in place (OP-16). */
    val indirect: IndirectNode? get() = ref.node as? IndirectNode
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
class Junction(val point: PointRef, val handle: Handle?, val curve: Element?) {
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
 * How a replicated gesture finds its pick again in every copy (OP-23): either the member of [orbit] at
 * index `offset + j`, or the one [fixed] element that is **invariant** under the pattern's transform and
 * therefore the same in every copy.
 */
class OrbitPick internal constructor(
    val orbit: PatternOrbit?,
    val offset: Int,
    val fixed: Element?,
)

/**
 * One **replicated gesture** (OP-23): the rule by which a tool application was stamped round a pattern.
 *
 * It is not a copy of geometry — it is the gesture itself, recorded as picks-by-index plus the clicks that
 * scored its choices, so re-running it at another count is re-running the same rule. [outputs] are the
 * orbits it produced, one per element each copy built.
 */
class OrbitGesture internal constructor(
    val pattern: Pattern,
    val toolId: String,
    val points: List<OrbitPick>,
    val elements: List<OrbitPick>,
    /** One per slot of the tool: that click, carried back to the cell of member index 0. */
    val cells: List<Vec2>,
    /** One per slot: which member index the click of that slot belongs to in the base copy. */
    val cellOffsets: List<Int>,
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
    val outputs = ArrayList<PatternOrbit>()

    /** The `orbit` step that records this gesture — what a re-stamp replays at another count. */
    var step: Step? = null
        internal set

    /** Every pick that rides an orbit — the ones an index shift moves. */
    val riding: List<OrbitPick> get() = (points + elements).filter { it.orbit != null }

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
    val copies: Int,
    val refusal: String?,
)

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
        // an identity snapshot, not a count: a step may *remove* elements too (a break replaces one
        // leg with three), and then a count would mistake shifted survivors for new ones
        val before = elements.toHashSet()
        pendingBefore = before
        val scalarsBefore = scalars.size
        val editsBefore = edits
        try {
            val result = body()
            val created = elements.filter { it !in before }
            // a tool whose build had no effect is not part of the construction — where "effect" counts an
            // in-place rewiring too, since a tool may bind rather than build (see [edits])
            if (skipIfEmpty && created.isEmpty() && scalars.size == scalarsBefore && edits == editsBefore) return result
            val step = Step(kind, args.toList() + (argsAfter?.invoke() ?: emptyList()))
            step.creates.addAll(created)
            step.createsScalars.addAll(scalars.subList(scalarsBefore, scalars.size))
            noteSpace(kind)
            journal.add(step)
            // which step *owns* each re-parameterization this operation performed (OP-4 case b), so the writer
            // restates an offset on the step that made it and nowhere else — a step that merely *uses* a
            // relative point (a circle through it) must not carry its distance and angle
            for (id in pendingReparams) reparamSteps[id] = step
            pendingReparams.clear()
            return result
        } finally {
            recordDepth--
            pendingBefore = null
        }
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
    fun facePartTip(ev: Evaluator = Evaluator()): Element? {
        val base = activeSpace.anchor ?: return null
        return elements.lastOrNull { el ->
            el.kind == ElementKind.SOLID && el.visible && (el === base || madeOf(el, base, ev))
        }
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
     * The **section node** of [space]: the part this plane belongs to, cut at this plane (OP-17's
     * section-inputs package). Null for the plan, and null for a plane that belongs to no solid.
     *
     * A real node, created **once per space** and shared by every input taken from it — which is what makes
     * the inputs a construction rather than a drawing: they are pure functions of the solid and the plane, so
     * retyping the plane's offset or dragging the part's corner slides the section and everything anchored on
     * it (OP-21: recompute, never rebuild).
     *
     * The solid is the space's own **anchor** — the solid the plane is derived from, or the part a datum was
     * resolved against at creation — and not [facePartTip]: the plane names a state of the model and its
     * context is the section of *that*, while which solid a *pick* of the part lands on is the tip's question
     * and keeps the tip's answer ([partOutlineOf]). Two questions, two answers, as the sequential-feature rule
     * already says for the plane itself.
     */
    @Suppress("UNCHECKED_CAST")
    fun spaceSectionNode(space: SketchSpace): SectionRef? {
        val plane = space.plane ?: return null
        val anchor = space.anchor ?: return null
        if (anchor.kind != ElementKind.SOLID) return null
        sectionNodes[space.name]?.let { return it }
        val node = cx.section(anchor.ref as SolidRef, plane)
        sectionNodes[space.name] = node
        return node
    }

    /** Section nodes by space name — one per space, so every input taken from it shares it (OP-5). */
    private val sectionNodes = HashMap<String, SectionRef>()

    /**
     * The **section of the part at [space]'s plane**, as it stands — the general mechanism behind a working
     * plane's context *and* its inputs (OP-17).
     *
     * Null when there is nothing to cut. Otherwise this is the part's section in the space's own (u, v): a
     * face space's plane lies **on** a face, so its section is that face's boundary (which is what the face
     * view has always drawn, now derived rather than assumed); a datum plane's section is the curves the cut
     * produces. See [constructit.geom.Section3.sectionOf] for the exactness classes.
     */
    fun spaceSection(
        space: SketchSpace,
        ev: Evaluator,
    ): PlaneSection? {
        val node = spaceSectionNode(space) ?: return null
        return (ev.valueOf(node) as? SectionValue)?.section
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
     * Everything [space] draws as **context**, in its own (u, v): the part's section at this plane, plus a
     * datum's hinge (its own u axis, over the extent the picked line reaches).
     *
     * One query for the renderer, and the reason both are in it is that they answer different questions —
     * the hinge says *where on the drawing am I standing*, the section says *where is the material*. A datum
     * that cuts nothing draws its hinge alone and says so in its note; a face space's section is the face.
     */
    fun spaceContext(
        space: SketchSpace,
        ev: Evaluator,
    ): List<ProfileElement> {
        val out = ArrayList<ProfileElement>()
        spaceSection(space, ev)?.let { out.addAll(it.drawn) }
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
     * The member of the active space's section nearest [at] within [tol] — corners first, then curves, which
     * is the same precedence a snap gives a point over the curve it lies on.
     *
     * Nothing is created here: this answers *what would be taken*, so a tool slot that cannot use it can
     * decline before a node exists (the pick pipeline's rule — an existing-only slot creates nothing on a
     * miss).
     */
    fun sectionCandidateNear(
        at: Vec2,
        tol: Double,
        ev: Evaluator,
        want: SectionInput? = null,
    ): SectionCandidate? {
        val space = activeSpace
        val section = spaceSection(space, ev) ?: return null
        var best: SectionCandidate? = null
        var bestDist = Double.MAX_VALUE
        if (want != SectionInput.EDGE) {
            for ((i, c) in section.corners.withIndex()) {
                val p = c.at ?: continue
                val d = (p - at).length()
                if (d <= tol && d < bestDist) {
                    bestDist = d
                    best =
                        SectionCandidate(
                            space, SectionInput.CORNER, i, p, c.provenance, section.inputsRefusal,
                            ElementKind.DERIVED_POINT,
                        )
                }
            }
            if (best != null) return best
        }
        if (want == SectionInput.CORNER) return null
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
                best = SectionCandidate(space, SectionInput.EDGE, i, at, e.provenance, why, edgeElementKind(e))
            }
        }
        // …and a piece no index names at all (a face the plane cuts twice) still has to answer for itself
        if (best == null) {
            val hit = section.drawn.minByOrNull { HitTest.distanceToPiece(at, it) }
            if (hit != null && HitTest.distanceToPiece(at, hit) <= tol) {
                val why =
                    section.inputsRefusal
                        ?: section.edges.firstNotNullOfOrNull { it.reason?.takeIf { r -> r.contains("separate pieces") } }
                        ?: "that piece of the section has no single name to take as an input"
                best = SectionCandidate(space, SectionInput.EDGE, -1, at, "a piece of the section", why, ElementKind.SEGMENT)
            }
        }
        return best
    }

    /** Which element kind a section curve becomes — the accessor's kind is part of the stored choice. */
    private fun edgeElementKind(e: constructit.geom.SectionEdge): ElementKind =
        when (e.curve) {
            is ProfileElement.ArcE -> ElementKind.ARC
            is ProfileElement.CircleE -> ElementKind.CIRCLE
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
            return "${e.provenance} is cut into a curve this drawing has no name for — an inclined plane through a " +
                "curved face is a conic, so it draws but cannot be anchored on; cut perpendicular to the axis for " +
                "the exact circle, or take a flat face's edge"
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
        return sectionInput(candidate.space, candidate.kind, candidate.index)
    }

    /**
     * The recorded form of [takeSectionInput] — also the loader's entry point, so a replay creates the same
     * node from the same index and never re-scores which curve was meant.
     */
    @Suppress("UNCHECKED_CAST")
    fun sectionInput(
        space: SketchSpace,
        kind: SectionInput,
        index: Int,
    ): Element? {
        val node = spaceSectionNode(space) ?: return null
        val section = (Evaluator().valueOf(node) as? SectionValue)?.section
        return recording(
            "sectioninput",
            Arg.Label(space.name),
            Arg.Keyed(if (kind == SectionInput.CORNER) "corner" else "edge", Arg.Text(index.toString())),
        ) {
            when (kind) {
                SectionInput.CORNER -> {
                    add(cx.sectionCorner(node, index), ElementKind.DERIVED_POINT, Styles.DERIVED_POINT)
                }
                SectionInput.EDGE -> {
                    // the *kind* of the curve is part of the accessor and therefore of the step (three ids for
                    // one choice, the bbox-measurement precedent): a section curve that has since become an arc
                    // makes the input invalid with a reason rather than changing type under a construction
                    when (section?.edges?.getOrNull(index)?.curve) {
                        is ProfileElement.ArcE -> add(cx.sectionArc(node, index), ElementKind.ARC, Styles.CONSTRUCT)
                        is ProfileElement.CircleE -> add(cx.sectionCircle(node, index), ElementKind.CIRCLE, Styles.CONSTRUCT)
                        else -> add(cx.sectionSegment(node, index), ElementKind.SEGMENT, Styles.CONSTRUCT)
                    }
                }
            }
        }
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
    fun activePlane3(ev: Evaluator): Plane3? {
        val ref = activeSpace.plane ?: return Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)
        return ((ev.eval(ref.node) as? EvalResult.Ok)?.value as? PlaneValue)?.plane
    }

    /**
     * The nearest solid boundary edge to [at]: the solid, and which of its boundary pieces (OP-8).
     *
     * The pick a plan-view editor can make: a **side face projects to exactly one footprint edge**, so
     * clicking that edge names the face, and the solid it belongs to, in one gesture. Measured against the
     * same footprint geometry the hint draws and [HitTest] picks by, so what looks like an edge is one.
     */
    fun solidEdgeNear(
        at: Vec2,
        tol: Double,
        ev: Evaluator,
    ): Pair<Element, Int>? {
        // drawn *in this space*, deliberately not the space's own anchor: that solid shows its face here,
        // not its plan, so a click on it is not in the coordinates its boundary pieces live in
        val solid =
            HitTest.nearest(this, ev, at, tol) { it.kind == ElementKind.SOLID && it.space == activeSpace.name }
                ?: return null
        val feature = (ev.valueOf(solid.ref) as? SolidValue)?.solid?.feature ?: return null
        val pieces = Geom3.boundaryPieces(feature)
        if (pieces.isEmpty()) return null
        val best = pieces.indices.minByOrNull { HitTest.distanceToPiece(at, pieces[it]) } ?: return null
        return solid to best
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
     * The plane is **derived**: `sideFacePlane` recomputes from the solid's own feature and is then flipped
     * so its normal points into the material — the direction a *Cut* sweeps ([cutOnFace]); which way an
     * operation builds is the operation's business, and a boss goes the other way ([extrudeSolid]). The flip
     * stays because it is what fixes the drawing's own coordinates (`v` down from the top face): reversing a
     * right-handed frame's normal mirrors `v`, so the frame is not a free choice once files exist. So the
     * frame is parametric — stretch the part and the face, its sketch and everything built from it follow.
     * That is face-**relative** positioning, and it is the honest intent here: a hole is dimensioned from
     * the part's own edge, where a rider on a wall wants a world coordinate (OP-20's absolute rule).
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
            val plane = cx.planeFlipped(cx.sideFacePlane(solid.ref as SolidRef, piece))
            val space = SketchSpace(name, plane, solid, piece)
            spaces.add(space)
            activeSpace = space
            space
        }
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
            val plane = if (shift == null) hinged else cx.planeOffset(hinged, shift.ref)
            val space = SketchSpace(name, plane, cuts, hinge = line, angle = entry, offset = shift, from = base.name)
            spaces.add(space)
            activeSpace = space
            space
        }
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
            space.isDatum ->
                "${space.name} (${Format.num(spaceAngleDeg(space))}° on ${space.hinge?.let { nameOf(it) }}" +
                    (space.offset?.let { ", ${Format.num(evalMm(it.ref))} mm off" } ?: "") +
                    (if (space.from == PLAN_SPACE) ")" else ", from ${space.from})")
            else -> "${space.name} (face of ${space.anchor?.let { nameOf(it) }})"
        }

    /** A datum space's offset along its own normal in mm, as it stands (0 when it has none). */
    fun spaceOffsetMm(space: SketchSpace): Double = space.offset?.let { evalMm(it.ref) } ?: 0.0

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
            elementNames.remove(el.id)
            existing?.let { s -> journal.removeAll { it === s } }
            return ""
        }
        val took = uniqueElementName(wanted, except = el)
        elementNames[el.id] = took
        if (existing == null) recording("name", Arg.El(el), Arg.Label(took)) { }
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
        val el = Element(nextId("e"), ref, kind, style)
        // the one place an element is born, hence the one place its sketch space is stamped (OP-17)
        el.space = activeSpace.name
        elements.add(el)
        return el
    }

    /** The element displaying [ref], if any — the inverse of the adders below. */
    fun elementFor(ref: Ref<*>): Element? = elements.lastOrNull { it.ref === ref }

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
            ElementKind.SEGMENT -> cx.lineOfSegment(el.ref as SegmentRef)
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
        if (el.kind == ElementKind.ARC) cx.circleOfArc(el.ref as ArcRef) else el.ref as CircleRef

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
        e.name = uniqueScalarName(wanted, except = e)
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
    fun analysePlacement(g: Group): Placement {
        val members = groupMembers(g)
        val memberSet = members.toHashSet()
        val candidates =
            ancestors(members.map { it.ref.node })
                .filterIsInstance<SourceNode>()
                .filter { it.boundTo == null && it.value is PointValue && ownedBy(it, memberSet) }
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
        val conflicts = ArrayList<SharedPoint>()
        // what the capture would take over: the free point sources, plus each captured path's vertices
        // (their published node) and the coordinate masters behind them
        val captured = HashSet<Node>(candidates)
        for (p in paths) {
            p.vertices.forEach { captured.add(it.ref.node) }
            captured.addAll(coordMasters(p, 0) + coordMasters(p, 1))
        }
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
            rigid,
            uncapturable,
        )
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
        val prospective = HashSet<SourceNode>(a.candidates)
        for (p in a.paths) prospective.addAll(coordMasters(p, 0) + coordMasters(p, 1))
        val (stuck, drivers) = deformingMembers(groupMembers(g), prospective)
        if (stuck.isNotEmpty()) {
            out.add(
                "this group cannot move independently — ${stuck.joinToString(", ") { nameOf(it) }} " +
                    "${if (stuck.size == 1) "is" else "are"} held by ${drivers.joinToString(", ")}, " +
                    "shared with the drawing outside the group (tick those in, or group them too)",
            )
        }
        if (a.conflicts.isNotEmpty()) {
            val points = a.conflicts.map { it.point }.distinct()
            val consumers = a.conflicts.map { it.consumer.id }.distinct()
            out.add(
                "this group cannot move independently — ${points.joinToString(", ")} " +
                    "${if (points.size == 1) "is" else "are"} shared with ${consumers.joinToString(", ")} outside it " +
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
                rec != null && rec.form == RiderForm.CIRCLE_ANGLE ->
                    out.add(Freedom(el, FreedomKind.ON_CIRCLE, "${nameOf(el)} — on circle ${nameOf(rec.host)}", owned, rider = rec))
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
            ) { placeGroupNow(g, at, angle, analysis.candidates, analysis.paths, analysis.ridersToAnchor) }
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
    ): PlaceResult {
        val f = FrameValue(at, angle)
        val node = SourceNode(nextId("fr"), f)
        val frame = Ref<FrameValue>(node)
        g.frame = frame
        g.frameHandle = FrameHandle(node)
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
        note = null // the capture's per-rider notes are not what the placement has to say
        return PlaceResult(g.captures.size, g.capturedPaths.size, deformingMembers(g), g.capturedRiders.size)
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
     * The pinned kinds are exactly two — a **free point source** owned by something outside the group (a
     * weld or an attach that left it), and an **ortho vertex coordinate the capture did not take**, which
     * stays an absolute world coordinate (a path whose freedom leaves the group at a junction). A curve
     * parameter (a point-on-line's distance, a point-on-circle's angle) is deliberately *not* one: it is
     * relative to a curve that itself follows the frame, so such a point is carried rigidly.
     */
    private fun deformingMembers(g: Group): List<Element> {
        // what the frame does drive: the captured points' locals, and the captured paths' own coordinates
        val carried = g.captures.mapTo(HashSet<SourceNode>()) { it.local }
        for (p in g.capturedPaths) carried.addAll(coordMasters(p, 0) + coordMasters(p, 1))
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
    ): Pair<List<Element>, List<String>> {
        val orthoCoords = HashSet<SourceNode>()
        for (p in orthoPaths) {
            for (v in p.vertices) {
                orthoCoords.add(v.corner.xNode)
                orthoCoords.add(v.corner.yNode)
            }
        }

        fun pinned(m: Element): List<SourceNode> =
            ancestors(listOf(m.ref.node)).filterIsInstance<SourceNode>().filter { s ->
                s.boundTo == null && s !in carried && (s.value is PointValue || s in orthoCoords)
            }
        val bad = members.filter { pinned(it).isNotEmpty() }
        val drivers = LinkedHashSet<String>()
        for (m in bad) pinned(m).forEach { drivers.add(labelOf(it)) }
        return bad to drivers.toList()
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
        g.frame = null
        g.frameHandle = null
        g.placeStep?.let { s -> journal.removeAll { it === s } }
        g.placeStep = null
        return true
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
    private fun elementOwning(node: SourceNode): Element? =
        elements.lastOrNull { it.ref.node === node || (it.ref.node as? IndirectNode)?.boundTo === node }

    /** Whether the element that *displays* [node] is one of [members] — see [analysePlacement]. */
    private fun ownedBy(
        node: SourceNode,
        members: Set<Element>,
    ): Boolean {
        val owner = elementOwning(node) ?: return true
        return owner in members
    }

    /** How to name a source node to the user: the element showing it (by its one name, OP-18), else the node's own id. */
    private fun labelOf(node: Node): String = (node as? SourceNode)?.let { elementOwning(it) }?.let { nameOf(it) } ?: node.id

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

    /** Free the parameter again, keeping its current (last driven) value. */
    fun unwireParameter(e: ScalarEntry) {
        val node = e.ref.node as? ParameterNode ?: return
        val cur = (Evaluator().eval(node) as? EvalResult.Ok)?.let { (it.value as ScalarValue).q }
        if (cur != null) node.literal = ScalarValue(cur)
        node.boundTo = null
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
        val node = literalNode(alias) ?: return false
        if (alias.kind != ElementKind.POINT || node.boundTo != null) return false
        if (!master.isPoint || master === alias) return false
        val masterNode = master.ref.node
        if (masterNode === node || joinWouldCycle(alias, masterNode)) return false // no cycles
        node.boundTo = masterNode
        alias.visible = false
        noteEdit()
        return true
    }

    /** Un-weld / detach: the point resumes as an independent free point at its current position. */
    fun unweld(alias: Element) = recording("unweld", Arg.El(alias), skipIfEmpty = true) { unweldNow(alias) }

    private fun unweldNow(alias: Element) {
        val node = literalNode(alias) ?: return
        val cur = (Evaluator().eval(node) as? EvalResult.Ok)?.let { (it.value as? PointValue)?.p }
        if (node.boundTo != null) noteEdit()
        node.boundTo = null
        relatives.remove(alias.id)
        if (cur != null) node.value = PointValue(cur)
        alias.kind = ElementKind.POINT
        alias.handle = FreePointHandle(node) // an independent free point again, handle included
        alias.style = Styles.FREE_POINT
        alias.visible = true
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
        val node = literalNode(pt)
        // A rider whose parameter was re-anchored to a base of its own carrier has an absolute form to go back
        // to — its position along the world-anchored carrier (OP-20) — so that case is answered first: it is
        // one step of the same progression, *measured from a base* → *riding the world* → *free of the curve*.
        if (node?.boundTo == null && riderOf(pt)?.carrierRelative == true) return releaseRider(pt)
        // A rider the *tool* created publishes its position through a re-pointable view (see [addRider]), so
        // there is a substrate to hand a literal back after all: re-point it at a free source where the point
        // now stands. Uniform with the drag-attached case below, which unbinds its own `SourceNode`.
        if (node?.boundTo == null && riderOf(pt) != null) return detachRider(pt, dofs)
        if (node?.boundTo == null) {
            note =
                if (node != null) {
                    "${nameOf(pt)} is already a free point"
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
     * Where [base] sits in [rec]'s **own** parameter — the quantity an offset from it is added to: its position
     * along the carrier, or its coordinate on the axis the host leaves free (see [riderOn]'s two forms).
     */
    private fun carrierBaseParam(
        rec: RiderRecord,
        base: PointRef,
        line: LineRef,
    ): ScalarRef =
        when (rec.form) {
            RiderForm.AXIS_COORD -> if (rec.axis == 0) cx.measureX(base) else cx.measureY(base)
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
    private fun paramOfCarrier(rec: RiderRecord): (Vec2, Evaluator) -> Double? {
        val line = rec.line
        val axis = rec.axis
        return { world, ev ->
            if (rec.form == RiderForm.AXIS_COORD) {
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
        if (rec.form == RiderForm.ALONG_LINE) noteCarrierRider(rec.point, rec.line!!, rec.param)
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
        riderOf(el)?.offset?.let { o -> return listOfNotNull(scalarOf(o, ev)) }
        // a **freed** rider (OP-16's view re-pointed, see [detachRider]) owns its coordinates from then on, so
        // what its step restates is the position it now has — through [restatedPosition], because a later
        // placement capture must not make the step describe post-capture geometry
        if (detached.containsKey(el.id)) {
            restatedPosition(el, stepIndex, ev)?.let { return listOf(Quantity.mm(it.x), Quantity.mm(it.y)) }
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
        val f = frameValueOf(g) ?: return q
        if (f.angle == 0.0) return q
        val here = pointOf(rec.point.node, ev) ?: return q
        val pre = f.origin + f.toLocal(here)
        if (rec.form == RiderForm.AXIS_COORD) return Quantity.mm(if (rec.axis == 0) pre.x else pre.y)
        val line = rec.line ?: return q
        val dir = ((ev.eval(line.node) as? EvalResult.Ok)?.value as? LineValue)?.line?.dir ?: return q
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

    /** Which of [riderOn]'s three parameter forms a rider's single degree of freedom is stated in. */
    enum class RiderForm {
        /** A world coordinate the host leaves free — a host axis-aligned *by construction* (OP-20). */
        AXIS_COORD,

        /** A signed distance along the carrier line, anchored to the line itself (OP-20). */
        ALONG_LINE,

        /** An angle about a circle's centre — already relative to the circle, so nothing re-anchors it. */
        CIRCLE_ANGLE,
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
        /** The constructed point riding the host — the element may be a free point *bound* onto it. */
        internal val point: PointRef,
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
        rider: Rider,
    ) {
        riders[el.id] = RiderRecord(el, host, rider.form, rider.dof, rider.line, rider.axis, rider.point)
    }

    /**
     * A point that **rides** a curve: the point itself, the [handle] over its single degree of freedom, and
     * how to [place] that freedom so a wanted world coordinate comes out exactly (see [Junction.place]).
     */
    private class Rider(
        val point: PointRef,
        val handle: Handle,
        /** The single source node carrying this rider's freedom — its parameter, whatever kind it is. */
        val dof: SourceNode,
        /** Which of the three forms the parameter is in — see [RiderForm]. */
        val form: RiderForm,
        /** The carrier, for the two linear forms; the coordinate axis for [RiderForm.AXIS_COORD]. */
        val line: LineRef? = null,
        val circle: CircleRef? = null,
        val axis: Int? = null,
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
    ): Rider? {
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
     */
    fun attachToCurve(
        pt: Element,
        curve: Element,
    ): Boolean = recording("attach", Arg.El(pt), Arg.El(curve), skipIfEmpty = true) { attachToCurveNow(pt, curve) }

    private fun attachToCurveNow(
        pt: Element,
        curve: Element,
    ): Boolean {
        val node = literalNode(pt) ?: return false
        if (attachTargetPos(pt, curve) == null) return false
        val p = (Evaluator().eval(node) as EvalResult.Ok).let { (it.value as PointValue).p }
        val rider = riderOn(curve, p, "") ?: return false
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
    ): Boolean = recording("attachortho", Arg.El(el), Arg.El(curve)) { attachOrthoEndpointToCurveNow(el, curve) }

    private fun attachOrthoEndpointToCurveNow(
        el: Element,
        curve: Element,
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
            return bindCornerToDeterminedMeeting(corner, curve, free, if (mx != null) 0 else 1)
        }
        val junction = junctionOnCurve(curve, el.ref.node) ?: return false
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
            else -> null
        }

    fun projectToLine(
        p: PointRef,
        line: Element,
    ) = addDerived(cx.projectToLine(p, carrierLine(line)))

    /**
     * Add a rider element over [ref]. [dof] is the rider's own parameter node and [restated] the value a
     * replay hands back for it (OP-18): a rider's position is **state**, since dragging it writes that
     * parameter, so the step restates the parameter itself rather than leaving the file describing the click
     * that first placed the rider. Given verbatim, so a saved drawing reloads bit for bit.
     */
    private fun addConstrained(
        ref: PointRef,
        handle: Handle,
        dof: SourceNode? = null,
        restated: Quantity? = null,
    ): PointRef {
        if (dof != null && restated != null && restated.dim == (dof.value as? ScalarValue)?.q?.dim) {
            dof.value = ScalarValue(restated)
        }
        // through [add], because that is where an element's sketch space is stamped (OP-17): building the
        // Element here instead left every constrained point in the **plan** whatever space it was drawn in,
        // and an ortho path drawn on a face came out as legs on the face with their corners in the plan
        add(ref, ElementKind.ON_CURVE, Styles.ON_CURVE).handle = handle
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
    private fun addRider(
        host: Element,
        rider: Rider,
        dof: Quantity?,
        at: Vec2? = null,
    ): PointRef {
        val (value, finding) = migratedRiderDof(rider, at, dof)
        // Published through a **re-pointable view** ([IndirectNode], OP-16's substrate), never as the derived
        // on-curve node itself. A rider has no literal of its own, so *Make absolute* had nothing to hand back
        // and refused — while the very same point created by *dragging* a free point onto the curve detached,
        // because there the element still published its own `SourceNode`. The view is what makes the two
        // uniform: [detachRider] re-points it at a free source and every consumer follows in place, exactly as
        // a welded point's consumers follow its master (OP-5).
        val ref = addConstrained(cx.indirect(rider.point), rider.handle, rider.dof, value)
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
        rider: Rider,
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
     * Intersect two curves. Segments/rays are treated as their carrier line. Branch count
     * follows the pair type (line-like ∩ line-like: 1 point, else: 2).
     */
    fun intersect(
        a: Element,
        b: Element,
    ): List<PointRef> {
        val (set, lineLine) = intersectionSet(a, b) ?: return emptyList()
        val refs = ArrayList<PointRef>()
        refs.add(cx.select(set, +1))
        if (!lineLine) refs.add(cx.select(set, -1))
        refs.forEach { addDerived(it) }
        return refs
    }

    /** The intersection solution set of [a] and [b], plus whether it holds a single branch. */
    @Suppress("UNCHECKED_CAST")
    private fun intersectionSet(
        a: Element,
        b: Element,
    ): Pair<PointSetRef, Boolean>? {
        val aLin = a.isLinear
        val bLin = b.isLinear
        // an arc intersects through its carrier circle, exactly as a segment does through its carrier line
        val aCirc = a.isCentric
        val bCirc = b.isCentric
        val lineLine = aLin && bLin
        val set: PointSetRef =
            when {
                lineLine -> cx.intersectLL(carrierLine(a), carrierLine(b))
                aCirc && bCirc -> cx.intersectCC(carrierCircle(a), carrierCircle(b))
                aLin && bCirc -> cx.intersectLC(carrierLine(a), carrierCircle(b))
                aCirc && bLin -> cx.intersectLC(carrierLine(b), carrierCircle(a))
                else -> return null
            }
        return set to lineLine
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
        val (set, lineLine) = intersectionSet(a, b) ?: return null

        // only where a *step* can restate them: the same helper serves the Outline tracer's handovers, which
        // are re-derived from that tool's own clicks and own no `signs=` of their own
        fun keep(
            sign: Int,
            ref: PointRef,
        ): PointRef {
            val out = addDerived(ref)
            if (remember) elements.lastOrNull()?.let { registerSigns(it, listOf(sign)) }
            return out
        }
        // a restated branch is taken as it stands — invalid included, which is ordinary invalidity (OP-3) and
        // not licence to pick the other one
        if (stored != null) return keep(stored, cx.select(set, stored))
        val ev = Evaluator()
        val candidates = if (lineLine) listOf(+1) else listOf(+1, -1)
        val best =
            candidates
                .map { it to cx.select(set, it) }
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
    fun extractPoints(el: Element): List<PointRef> {
        if (el.kind == ElementKind.AREA) {
            val region = el.ref as RegionRef
            val n = cx.regionCornerCount(region, Evaluator())
            if (n == 0) {
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
                else -> emptyList()
            }
        refs.forEach { addDerived(it) }
        return refs
    }

    fun tangentFromPoint(
        p: PointRef,
        circle: Element,
    ): List<PointRef> {
        val set = cx.tangentPointsFromPoint(p, carrierCircle(circle))
        val refs = listOf(cx.select(set, +1), cx.select(set, -1))
        refs.forEach { addDerived(it) }
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
    ) = add(cx.segment(a, b), ElementKind.SEGMENT, Styles.CURVE)

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
        return OrthoVertex(ref, corner, ownAxis, local)
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
        val node = el.ref.node
        val own = creatingStep(el)
        val out = ArrayList<String>()
        elements.filter { it !== el && dependsOn(it.ref.node, node, HashSet()) }.forEach { out.add(it.id) }
        scalars.filter { dependsOn(it.ref.node, node, HashSet()) }.forEach { out.add(it.name) }
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
            else -> {
                note = "${nameOf(el)} is not a segment, an arc or a Bézier"
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
        recordingTool(toolId, picks, scalars) { def.build(this, picks, scalars.map { it.ref }) }
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

        val pieces = ArrayList<Ref<*>>(n)
        for (i in 0 until n) {
            val from = joints[(i - 1 + n) % n]
            val to = joints[i]
            pieces.add(trimPiece(picks[i], from, to, clicks[i], ev) ?: return null)
        }
        return add(cx.loop(*pieces.toTypedArray()), ElementKind.OUTLINE, Styles.RESULT)
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
    ) {
        jointRegistry.add(Joint(a, b, at))
    }

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
            val m = supersessionCentre(s, ev) ?: continue
            left.minByOrNull { (it - m).length() }?.let { left.remove(it) }
        }
        return left
    }

    /** Where a superseding fillet/chamfer sits: the midpoint of the two joints it registered. */
    private fun supersessionCentre(
        s: Supersession,
        ev: Evaluator,
    ): Vec2? {
        val t1 = registeredJoint(s.by, s.a)?.let { (ev.valueOf(it) as? PointValue)?.p } ?: return null
        val t2 = registeredJoint(s.by, s.b)?.let { (ev.valueOf(it) as? PointValue)?.p } ?: return null
        return (t1 + t2) * 0.5
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
        val (set, lineLine) = intersectionSet(a, b) ?: return null
        if (lineLine) return null // two lines meet once: they cannot bound an area on their own
        val first = cx.select(set, +1)
        val second = cx.select(set, -1)
        val p1 = (ev.valueOf(first) as? PointValue)?.p ?: return null
        val p2 = (ev.valueOf(second) as? PointValue)?.p ?: return null
        if ((p1 - p2).length() < GeomMath.JOIN_TOL) return null // tangent: one meeting only
        return listOf(addDerived(first), addDerived(second))
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
        // a welded alias is hidden by construction, so it is never *shown* and never named in a step
        val subject = els.filter { el -> elements.any { it === el } && !(visible && isWelded(el)) }
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
        val ref = cx.thickFootprint(vertices, thickness, ring, justification)
        val el = add(ref, ElementKind.AREA, Styles.FOOTPRINT)
        val tp = ThickNetwork(ThickCarrier.Ortho(vertices.toList(), ring, justification, carrier), thickness, el)
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
                    "approximated (a Bézier's offset is not a Bézier, OP-15), so its area and its solid are too"
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
            else -> boundaryPiecesOf(el)?.let { pieces -> cx.region(cx.loop(*pieces.map { it.ref }.toTypedArray())) }
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
        if (el.kind == ElementKind.CIRCLE) return listOf(el)
        if (!el.isCurve) return null
        legOf(el)?.let { (path, _) -> if (path.closed) return path.legs.toList() }
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
     * **In a face space it builds a boss — outward, out of the material** (reported: an extrude on a face
     * produced a solid buried inside the part, visible only as its base z-fighting the face). *Which way an
     * operation builds is the operation's business, not the space's*: the space says where the drawing is and
     * which way its `v` runs, and *Cut* is the operation that goes inward ([cutOnFace]). Realized without
     * touching the space's frame — the sketch is put on the plane **[depth] behind** the face and swept the
     * space's own way, so the material lands between the face and `depth` outside it, and every drawn (u, v)
     * still means exactly what it meant (a right-handed frame cannot reverse its normal without mirroring `v`,
     * and mirroring `v` would move every face-space drawing ever saved — see [createFaceSpace]).
     *
     * **On a datum plane it sweeps the plane's own +normal** and offsets nothing (GitHub #6). The two rules
     * are the same rule stated against what each space actually has: a face plane points *into* material, so
     * the directions are named against the material; a datum has no material side at all, so they are named
     * against its own normal — which the **sign of its angle** turns round ([createDatumSpace]).
     */
    fun extrudeSolid(
        el: Element,
        depth: ScalarRef,
    ): Element? {
        val region = regionOf(el) ?: return null
        val space = activeSpace
        val plane =
            when {
                space.plane == null -> activePlane()
                // a face: start `depth` behind the face, so the material lands outside it — a boss
                space.isFace -> cx.planeOffset(space.plane, cx.neg(depth))
                // a datum: along its own +normal, which is the direction its angle's sign chose
                else -> space.plane
            }
        return add(cx.extrude(cx.sketchOn(plane, region), depth), ElementKind.SOLID, Styles.SOLID)
    }

    /**
     * Extrude the area [el] by [depth] on the active **face** space's plane and subtract it from [part]
     * (OP-17): the drill, the pocket, the slot — one gesture.
     *
     * **This is the operation that goes inward**, and it is the only one: it sweeps the face's own plane the
     * space's way (the normal points into the material — [createFaceSpace]), which is what makes a drill a
     * drill. Its twin *Extrude* builds the same footprint outward as a boss ([extrudeSolid]), so the pair
     * covers both intents by naming them rather than by a sign the user cannot see.
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
     * **On a datum plane it sweeps −normal instead**, by starting the sweep `depth` behind the plane — the
     * mirror image of what [extrudeSolid] does on a face, and for the mirror reason: a datum has no material
     * side, so *Extrude* takes its +normal and *Cut* takes the other one, with the sign of the datum's angle
     * choosing which is which (GitHub #6). The part it subtracts from is the one the datum's hinge belongs to
     * ([datumPartOf]), chained by the same tip rule.
     */
    @Suppress("UNCHECKED_CAST")
    fun cutOnFace(
        part: Element,
        el: Element,
        depth: ScalarRef,
    ): Element? {
        val space = activeSpace
        val on = space.plane ?: return null
        if (part.kind != ElementKind.SOLID) return null
        val region = regionOf(el) ?: return null
        val plane = if (space.isFace) on else cx.planeOffset(on, cx.neg(depth))
        val tool = add(cx.extrude(cx.sketchOn(plane, region), depth), ElementKind.SOLID, Styles.SOLID)
        return add(cx.subtract(part.ref as SolidRef, tool.ref as SolidRef), ElementKind.SOLID, Styles.SOLID)
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
                    parts.add(LoftPart.Apex(planeOfSpace(el.space), el.ref as PointRef, cx.const(0.0.mm)))
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

    /**
     * The pyramid/cone gesture: the area [el] run to a **point** [apex] standing [height] off the sketch plane
     * (OP-17). The two-section case of [loftSolid], with the apex placed by the same kind of scalar an extrude's
     * depth is.
     *
     * The apex is an ordinary point element — a fresh one where the click found nothing, the existing one where
     * it hit a point — so it is draggable in the plan and *shared* when it was clicked, and the pyramid follows
     * it either way (OP-5). Which way the height goes is the operation's business and follows *Extrude*'s own
     * rule: on a face space a positive height builds **outward**, because a face plane's normal points into the
     * material; in the plan and on a datum it follows the plane's own normal.
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
        val space = activeSpace
        val plane = activePlane()
        val lift = if (space.isFace) cx.neg(height) else height
        val seam = signs.firstOrNull() ?: seamOf(region, at)
        val solid =
            add(
                cx.loft(listOf(LoftPart.Area(cx.sketchOn(plane, region)), LoftPart.Apex(plane, apex, lift)), listOf(seam, 0)),
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
    }

    /**
     * The horizontal **section** of the solid [el] at [height], as an ordinary 2D area (OP-17, downward).
     *
     * An `AREA` element like a wall's footprint, so everything the result layer can do it can do: it draws
     * in plan, it is pickable, it can be dimensioned, and it can be extruded again — including onto the
     * very solid it was cut from ([extrudeOnFace]). Being derived, it also *follows*: drag the wall the
     * solid came from and the section reshapes, with no node created and none rebuilt.
     */
    @Suppress("UNCHECKED_CAST")
    fun sectionSolid(
        el: Element,
        height: ScalarRef,
    ): Element? {
        if (el.kind != ElementKind.SOLID) return null
        // RESULT, not FOOTPRINT: a section is a drawing in its own right, not the plan of a wall
        return add(cx.sectionAt(el.ref as SolidRef, height), ElementKind.AREA, Styles.RESULT)
    }

    /**
     * Revolve the area [el] through [angle] about the axis carried by the line element [axis] (OP-17
     * slice 2).
     *
     * The axis is the picked line's own origin and direction as *derived nodes*, so the axis moves with
     * the line: drag the centreline and the turned part follows. A profile touching the axis is legal, one
     * crossing it makes the node invalid with a reason and heals when it is dragged back (OP-3) — all of
     * that is [constructit.geom.Geom3.revolve]'s, unchanged.
     *
     * **Stated limit in a face space:** a partial turn sweeps toward the space's normal, i.e. *into* the
     * material, where [extrudeSolid]'s boss now goes outward. An extrude can be turned round by moving its
     * start plane, which changes no coordinate; a sweep cannot — reversing it means a negative angle, and the
     * kernel ties its cap winding to a positive sweep (the same rule that refuses a negative extrude depth).
     * So the honest answer here is a `dir` argument on the feature, and it is not built (DESIGN.md, OP-17).
     */
    fun revolveSolid(
        el: Element,
        axis: Element,
        angle: ScalarRef,
    ): Element? {
        val region = regionOf(el) ?: return null
        if (!axis.isLinear) return null
        val line = carrierLine(axis)
        val ref = cx.revolve(cx.sketchOn(activePlane(), region), cx.lineOrigin(line), cx.lineDirection(line), angle)
        return add(ref, ElementKind.SOLID, Styles.SOLID)
    }

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
        if (a.kind != ElementKind.SOLID || b.kind != ElementKind.SOLID || a === b) return null
        val ra = a.ref as SolidRef
        val rb = b.ref as SolidRef
        val ref =
            when (kind) {
                BoolOp.UNION -> cx.union(ra, rb)
                BoolOp.SUBTRACT -> cx.subtract(ra, rb)
                BoolOp.INTERSECT -> cx.intersect(ra, rb)
            }
        return add(ref, ElementKind.SOLID, Styles.SOLID)
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
                ?: return null
        if (tp.intervals.isEmpty()) return null
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
        return add(cut, ElementKind.SOLID, Styles.SOLID)
    }

    /** The named entry driving [ref] — every scalar a tool consumes came from the panel. */
    private fun scalarEntryFor(ref: ScalarRef): ScalarEntry =
        scalars.firstOrNull { it.ref.node === ref.node }
            ?: newParameter("v", (Evaluator().eval(ref.node) as? EvalResult.Ok)?.let { (it.value as? ScalarValue)?.q } ?: 0.0.mm)

    private fun evalMm(ref: ScalarRef): Double =
        (Evaluator().eval(ref.node) as? EvalResult.Ok)?.let { (it.value as? ScalarValue)?.q?.mm } ?: 0.0

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
                GeomMath.thickFaces(pts, c.closed, c.justification.offsets(scalarMm(tp.thickness, ev))).first?.let { thickBodyOf(it).first }
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

    private fun scalarMm(
        ref: ScalarRef,
        ev: Evaluator,
    ): Double = ((ev.eval(ref.node) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.mm ?: 0.0

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

    fun circle(
        center: PointRef,
        through: PointRef,
    ) = add(cx.circleCP(center, through), ElementKind.CIRCLE, Styles.CURVE)

    fun circleCR(
        center: PointRef,
        radius: ScalarRef,
    ) = add(cx.circleCR(center, radius), ElementKind.CIRCLE, Styles.CURVE)

    fun circle3(
        a: PointRef,
        b: PointRef,
        c: PointRef,
    ) = add(cx.circle3(a, b, c), ElementKind.CIRCLE, Styles.CURVE)

    fun arc3(
        a: PointRef,
        b: PointRef,
        c: PointRef,
    ) = add(cx.arc3(a, b, c), ElementKind.ARC, Styles.CURVE)

    fun arcCenterStartEnd(
        center: PointRef,
        start: PointRef,
        end: PointRef,
    ) = add(cx.arcCenterStartEnd(center, start, end), ElementKind.ARC, Styles.CURVE)

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

    /** Tangent at a point-on-circle — the circle is inferred from the point's handle. */
    fun tangentAtPointOnCircle(pointEl: Element) {
        val c = pointEl.handle
        if (c is OnCircleHandle) add(cx.tangentAtCircle(c.circle, pointEl.ref as PointRef), ElementKind.LINE, Styles.CONSTRUCT)
    }

    fun parallelThrough(
        line: Element,
        p: PointRef,
    ) = add(cx.parallelThrough(carrierLine(line), p), ElementKind.LINE, Styles.CONSTRUCT)

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
            else -> null
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
        // the op builds the arc from leg1's tangency to leg2's, so its own ends *are* the two joints
        registerJoint(el, leg1, cx.arcStart(arc))
        registerJoint(el, leg2, cx.arcEnd(arc))
        supersedeCorner(leg1, leg2, el)
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
        registerJoint(el, leg1, t1)
        registerJoint(el, leg2, t2)
        supersedeCorner(leg1, leg2, el)
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
     * A straight bevel across the corner of two legs: the points at [distance] from the corner along each
     * leg, joined by a segment. The corner quadrant comes from where the legs were clicked, exactly as a
     * fillet's does ([legSigns]).
     *
     * Composed entirely of ops that already existed — `intersectLL` + `Select` for the corner (a persisted
     * branch, OP-1) and `pointAlongLine` for each bevel end — so a chamfer needs no geometry of its own:
     * both ends stay on their legs, and the bevel follows every later edit of either.
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
    private var patternCounter = 0

    /** Every pattern still standing — one whose reference member is gone is gone with it. */
    val patterns: List<Pattern> get() = patternList.filter { p -> elements.any { it === p.reference } }

    private fun uniquePatternName(): String {
        var i = patternCounter + 1
        while (patternList.any { it.name == "P$i" }) i++
        patternCounter = i
        return "P$i"
    }

    /** The pattern [name] declares, or null — the `orbit` step's one reference to its rule. */
    fun patternNamed(name: String): Pattern? = patterns.firstOrNull { it.name == name }

    /** Which orbit [el] is a member of, and at which index — null when it is outside every pattern. */
    fun memberSlot(el: Element): Pair<PatternOrbit, Int>? {
        for (p in patterns) {
            for (o in p.orbits) {
                val i = o.members.indexOfFirst { it === el }
                if (i >= 0) return o to i
            }
        }
        return null
    }

    /** The pattern [el] belongs to as a member, or null. */
    fun patternOf(el: Element): Pattern? = memberSlot(el)?.first?.pattern

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
            patternList.add(p)
            note = "Pattern ${p.name}: $count instances — anything built on its members now repeats round it"
            p
        }.also { p -> p.step = journal.lastOrNull()?.takeIf { it.kind == "pattern" } }
    }

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
        for (slot in tool.slots) {
            when (slot) {
                SlotKind.PLACE_POINT, SlotKind.POINT -> slotEl.add(pointEls.getOrNull(pi++))
                SlotKind.SIDE -> slotEl.add(null)
                else -> slotEl.add(picks.elements.getOrNull(ei++))
            }
        }
        if (pi != picks.points.size || ei != picks.elements.size) return null // a fan, or a part operand
        val found = slotEl.mapIndexed { i, el -> i to el?.let { memberSlot(it) } }
        val p = found.firstNotNullOfOrNull { it.second }?.first?.pattern ?: return null
        // the picks, resolved against that one pattern
        val indexOf = HashMap<Int, Int>() // slot -> member index
        for ((i, slot) in found) {
            val el = slotEl[i] ?: continue
            if (slot != null && slot.first.pattern !== p) {
                return Replication(p, null, 0, "not replicated: ${nameOf(el)} belongs to pattern ${slot.first.pattern.name}")
            }
            if (slot != null) {
                indexOf[i] = slot.second
            } else if (p.invariants.none { it === el } && el.kind != ElementKind.SOLID) {
                return Replication(p, null, 0, "not replicated: ${nameOf(el)} is outside the pattern")
            }
            // ...a **solid** being the one admitted exception, because it is the body a feature is applied
            // *to* rather than a geometric input that must travel with the copy. Whether the same body in
            // every copy means a chain or a fan is the tool's own declaration: a face-part tool re-resolves
            // the tip per copy (a chain of pockets), while *Extrude on face* raises one boss per member off
            // the one base (a fan of independent solids). See the orbit-rule table in DESIGN.md.
        }
        // the base copy is the gesture shifted down to the lowest index it touches, so offsets are >= 0 and
        // the recorded rule says nothing about *which* copy the user happened to click
        val anchor = indexOf.values.min()
        val cells = ArrayList<Vec2>()
        val cellOffsets = ArrayList<Int>()
        val ev = Evaluator()
        for (i in tool.slots.indices) {
            val click = picks.clicks.getOrNull(i) ?: Vec2(0.0, 0.0)
            val at = indexOf[i] ?: anchor
            cells.add(patternCell(p, -at, click, ev))
            cellOffsets.add(at - anchor)
        }
        val pointPicks = ArrayList<OrbitPick>()
        val elementPicks = ArrayList<OrbitPick>()
        for ((i, slot) in tool.slots.withIndex()) {
            val el = slotEl[i]
            val orbit = found[i].second?.first
            val pick =
                when {
                    orbit != null -> OrbitPick(orbit, indexOf.getValue(i) - anchor, null)
                    el != null -> OrbitPick(null, 0, el)
                    else -> null
                }
            when (slot) {
                SlotKind.PLACE_POINT, SlotKind.POINT -> pointPicks.add(pick ?: return null)
                SlotKind.SIDE -> {}
                else -> elementPicks.add(pick ?: return null)
            }
        }
        val gesture =
            OrbitGesture(
                p, tool.id, pointPicks, elementPicks, cells, cellOffsets, emptyList(), picks.signs, picks.count,
                chainsPart = tool.facePartOperand,
            )
        val copies = copiesFor(gesture, p.count) ?: return Replication(p, null, 0, "not replicated: the pattern has no room for it")
        if (copies < 2) return Replication(p, null, 0, "not replicated: the pattern has no room for a second copy of it")
        return Replication(p, gesture, copies, null)
    }

    /**
     * How many copies [g] gets, with the ring [count] members long and [sizes] overriding the orbit lengths a
     * re-stamp will change.
     *
     * A ring wraps, so every member is the start of a copy and there are exactly as many copies as members.
     * A row does not: a gesture spanning m+1 neighbours makes n-m copies, which is why a row of holes drawn
     * between neighbours gives one fewer segment than there are holes.
     */
    private fun copiesFor(
        g: OrbitGesture,
        count: Int,
        sizes: Map<PatternOrbit, Int> = emptyMap(),
    ): Int? {
        val riding = g.riding
        if (riding.isEmpty()) return null
        if (g.pattern.wraps) return count
        return riding.minOf { (sizes[it.orbit] ?: it.orbit!!.size) - it.offset }
    }

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
     */
    fun buildOrbit(
        plan: Replication,
        tool: ToolDef,
        scalars: List<ScalarEntry>,
    ): OrbitGesture? {
        val g0 = plan.gesture ?: return null
        val p = plan.pattern
        val g =
            OrbitGesture(
                p, g0.toolId, g0.points, g0.elements, g0.cells, g0.cellOffsets, scalars, g0.signs, g0.count,
                chainsPart = g0.chainsPart,
            )
        var scored: List<Int> = g0.signs
        val perCopy = ArrayList<List<Element>>()
        val journalBefore = journal.size
        recording(
            "orbit",
            *orbitArgs(g),
            skipIfEmpty = true,
            argsAfter = { if (scored.isEmpty()) emptyList() else listOf(Arg.Keyed("signs", Arg.Text(scored.joinToString(";")))) },
        ) {
            for (j in 0 until plan.copies) {
                val before = elements.toHashSet()
                // a fresh pass per copy: a chained gesture resolves its base against what the copy before it
                // just built, so this loop is the one place a cached pass would be looking at the wrong document
                val copyPicks = picksFor(g, j, scored, Evaluator()) ?: break
                tool.build(this, copyPicks, scalars.map { it.ref })
                val made = elements.filter { it !in before }
                // the first copy scores its choices from its own clicks; every other copy is handed the
                // result, and the step writes it down — so a reload never scores again (OP-1)
                if (j == 0 && scored.isEmpty()) scored = storedSigns(made)
                perCopy.add(made)
            }
        }
        // nothing recorded means the gesture built nothing at all: the caller falls back to applying it once
        if (journal.size == journalBefore) return null
        g.step = journal.lastOrNull()
        registerOrbits(g, perCopy)
        p.gestures.add(g)
        return g
    }

    /** The `orbit` step's arguments — the gesture's rule, with the member picks written as `e2@1`. */
    private fun orbitArgs(g: OrbitGesture): Array<Arg> {
        fun ref(pick: OrbitPick): Arg = pick.fixed?.let { Arg.El(it) } ?: Arg.Member(pick.orbit!!.members[0], pick.offset)
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

    /** The picks copy [j] of [g] applies to: members shifted by j, clicks carried into cell `offset + j`. */
    private fun picksFor(
        g: OrbitGesture,
        j: Int,
        signs: List<Int>,
        ev: Evaluator,
    ): Picks? {
        val p = g.pattern

        fun at(pick: OrbitPick): Element? {
            val o = pick.orbit ?: return pick.fixed
            val i = if (p.wraps) (pick.offset + j) % o.size else pick.offset + j
            return o.members.getOrNull(i)
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
                val k = g.cellOffsets.getOrElse(i) { 0 } + j
                patternCell(p, if (p.wraps) k % p.count else k, c, ev)
            }
        return Picks(points, els, clicks.lastOrNull() ?: Vec2(0.0, 0.0), clicks, count = g.count, signs = signs)
    }

    /**
     * Turn what the copies built into orbits: element *s* of every copy is one orbit, indexed by copy — so
     * the outputs of a replicated gesture are members at their own index and the orbit **grows**.
     *
     * A copy that built a different number of elements than its siblings is refused as a member set rather
     * than half-registered: the structure of a step is fixed at build (OP-5), so an uneven fan means the
     * geometry gave out somewhere, and saying so is more use than a ragged pattern.
     */
    private fun registerOrbits(
        g: OrbitGesture,
        perCopy: List<List<Element>>,
    ) {
        val k = perCopy.firstOrNull()?.size ?: return
        if (k == 0) return
        if (perCopy.any { it.size != k } || perCopy.size < 2) {
            note = "Pattern ${g.pattern.name}: ${g.label} did not build the same geometry at every index, so its results are not pattern members"
            return
        }
        for (s in 0 until k) {
            val o = PatternOrbit(g.pattern, perCopy.map { it[s] })
            g.pattern.orbits.add(o)
            g.outputs.add(o)
        }
    }

    /**
     * Replay a recorded `orbit` step: the same fan, from the rule the file states (OP-23).
     *
     * Nothing is discovered here — the offsets, the cell-local clicks, the scalars and the scored signs all
     * come from the step, and the only thing recomputed is where a cell-local click lands, which follows the
     * pattern's *current* shape and is exactly what makes a re-stamped count come out right.
     */
    internal fun replayOrbit(
        p: Pattern,
        tool: ToolDef,
        points: List<Pair<Element, Int?>>,
        els: List<Pair<Element, Int?>>,
        cells: List<Vec2>,
        scalars: List<ScalarEntry>,
        signs: List<Int>,
        count: Int,
        chainsPart: Boolean,
    ): OrbitGesture? {
        fun pick(spec: Pair<Element, Int?>): OrbitPick? {
            val off = spec.second ?: return OrbitPick(null, 0, spec.first)
            val o = memberSlot(spec.first)?.first ?: return null
            return OrbitPick(o, off, null)
        }

        val pointPicks = points.map { pick(it) ?: return null }
        val elPicks = els.map { pick(it) ?: return null }
        // the click cells, derived from the tool's slots exactly as the recording derived them
        val offsets = ArrayList<Int>()
        var pi = 0
        var ei = 0
        for (slot in tool.slots) {
            val pick =
                when (slot) {
                    SlotKind.PLACE_POINT, SlotKind.POINT -> pointPicks.getOrNull(pi++)
                    SlotKind.SIDE -> null
                    else -> elPicks.getOrNull(ei++)
                }
            offsets.add(pick?.takeIf { it.orbit != null }?.offset ?: 0)
        }
        val g = OrbitGesture(p, tool.id, pointPicks, elPicks, cells, offsets, scalars, signs, count, chainsPart)
        val copies = copiesFor(g, p.count) ?: return null
        val plan = Replication(p, g, copies, null)
        return buildOrbit(plan, tool, scalars)
    }

    /**
     * Why [p] cannot be re-stamped at [n], or null when it can (OP-23).
     *
     * The one thing mod-n arithmetic cannot absorb: a gesture that spans **more neighbours than the new count
     * has members**. "Member 0 to member 4" is a pair at six; at three it is not a pair at all, and folding it
     * to (0, 1) would silently make a different drawing. So it is refused, by name.
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
            val over = g.riding.firstOrNull { (sizes[it.orbit] ?: 0) <= it.offset }
            if (over != null) {
                return "can't re-stamp pattern ${p.name} at $n: its ${g.label} spans ${over.offset + 1} members " +
                    "of a ${sizes[over.orbit] ?: 0}-member orbit — use the tool again instead"
            }
            val copies = copiesFor(g, n, sizes) ?: return "can't re-stamp pattern ${p.name}: its ${g.label} rides nothing"
            if (copies < 2) return "can't re-stamp pattern ${p.name} at $n: its ${g.label} would have no second copy"
            for (o in g.outputs) sizes[o] = copies
        }
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

    fun measureLength(seg: Element) = measurement("len", cx.measureLength(seg.ref as SegmentRef))

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
         * One rule, two readers: [Scene3] tells an operand from an output by it (a plate is still an output
         * while something is merely sketched on its face), and [facePartTip] follows it to the end of a
         * part's boolean chain (OP-17's sequential-feature rule).
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

    /** Scaffolding, once a result exists to contrast it with — dimmed, not hidden. */
    val DIMMED = Style(stroke = "#c9c9c9", width = 1.0)

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
