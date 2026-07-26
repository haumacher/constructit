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
import constructit.core.PointValue
import constructit.core.RayValue
import constructit.core.RegionValue
import constructit.core.ScalarValue
import constructit.core.SegmentValue
import constructit.core.SourceNode
import constructit.core.Value
import constructit.dsl.ArcRef
import constructit.dsl.BezierRef
import constructit.dsl.CircleRef
import constructit.dsl.Construction
import constructit.dsl.FrameRef
import constructit.dsl.LineRef
import constructit.dsl.LoopRef
import constructit.dsl.PointRef
import constructit.dsl.PointSetRef
import constructit.dsl.RayRef
import constructit.dsl.Ref
import constructit.dsl.RegionRef
import constructit.dsl.RoundedRectArgs
import constructit.dsl.ScalarRef
import constructit.dsl.SegmentRef
import constructit.dsl.SolidRef
import constructit.dsl.instance
import constructit.dsl.roundedRect
import constructit.dsl.valueOf
import constructit.geom.Axis3
import constructit.geom.BoolOp
import constructit.geom.GeomMath
import constructit.geom.Justification
import constructit.geom.ProfileElement
import constructit.geom.Segment
import constructit.geom.SolidFace
import constructit.geom.ThickFaces
import constructit.geom.Vec2
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
}

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
) {
    /** Whether the frame would carry any freedom at all — else it would have nothing to move. */
    val carriesSomething: Boolean get() = candidates.isNotEmpty() || paths.isNotEmpty()
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
 * A retained **thick path** (OP-21): the offset region of [thickness] around a carrier polyline,
 * justified by [justification], with parametric [intervals] along it. A wall is one use of this and
 * gives the tool its name; the model deliberately says nothing about walls.
 *
 * The geometry is a single [footprint] element over one `Region` node, so editing an interval or the
 * thickness **recomputes** rather than regenerates: no element is replaced, no node is orphaned, and
 * the carrier's own vertices and legs are untouched (they stay draggable exactly as any ortho path).
 */
class ThickPath(
    val vertices: List<PointRef>,
    val thickness: ScalarRef,
    val justification: Justification,
    val closed: Boolean,
    /** The carrier path this was built from, when it came from the ortho-path tool. */
    val carrier: OrthoPath?,
    /** The one displayable output: the footprint region (OP-14). */
    val footprint: Element,
) {
    val intervals = ArrayList<PathInterval>()

    val legCount: Int get() = if (closed) vertices.size else vertices.size - 1
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
     * Run [body] as one journal step. Nested calls are absorbed into the outermost one, so a tool that
     * calls several document operations is recorded as the single tool application the user performed —
     * which is also the only granularity that replays correctly.
     */
    private fun <T> recording(
        kind: String,
        vararg args: Arg,
        skipIfEmpty: Boolean = false,
        body: () -> T,
    ): T {
        if (recordDepth > 0) return body()
        recordDepth++
        // an identity snapshot, not a count: a step may *remove* elements too (a break replaces one
        // leg with three), and then a count would mistake shifted survivors for new ones
        val before = elements.toHashSet()
        val scalarsBefore = scalars.size
        try {
            val result = body()
            val created = elements.filter { it !in before }
            // a tool whose build had no effect is not part of the construction
            if (skipIfEmpty && created.isEmpty() && scalars.size == scalarsBefore) return result
            val step = Step(kind, args.toList())
            step.creates.addAll(created)
            step.createsScalars.addAll(scalars.subList(scalarsBefore, scalars.size))
            journal.add(step)
            return result
        } finally {
            recordDepth--
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

    // ---- delete: the unit of removal is the journal step (OP-18) ----

    /** The journal step that created [el], if any — what a delete of [el] removes. */
    fun creatingStep(el: Element): Step? = journal.firstOrNull { s -> s.creates.any { it === el } }

    /** Elements a step's arguments reference, keyed wrappers included. */
    private fun referencedElements(step: Step): List<Element> {
        val out = ArrayList<Element>()

        fun walk(a: Arg) {
            when (a) {
                is Arg.El -> out.add(a.el)
                is Arg.Els -> out.addAll(a.els)
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

        fun labelOfStep(step: Step): String? = step.args.filterIsInstance<Arg.Label>().firstOrNull()?.s

        fun drop(
            step: Step,
            chain: PathChain?,
        ) {
            dropped.add(step)
            droppedEls.addAll(step.creates)
            droppedScalars.addAll(step.createsScalars)
            // a thick path can go without taking its carrier path down with it — the dependency runs the
            // other way. Every other step belonging to a path's chain takes that chain's future with it.
            if (step.kind != "wall") chain?.dropped = true
        }

        var current: PathChain? = null
        val chainOfEl = HashMap<Element, PathChain>()
        var seenRoot = false
        for (step in journal) {
            val els = referencedElements(step)
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
                    referencedScalars(step).any { it in droppedScalars }
            if (depends) drop(step, chain)
            // a definition is all-or-nothing: losing one of its elements changes how many elements an
            // instance creates, which replay checks (OP-18), so the whole declaration goes
            if (depends && step.kind == "macrodef") labelOfStep(step)?.let { droppedMacros.add(it) }
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
        elements.add(el)
        return el
    }

    /** The element displaying [ref], if any — the inverse of the adders below. */
    fun elementFor(ref: Ref<*>): Element? = elements.lastOrNull { it.ref === ref }

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

    /** Ensure scalar names are unique so the wiring dropdown is never ambiguous. */
    private fun uniqueScalarName(base: String): String {
        val b = base.ifBlank { "p" }
        if (scalars.none { it.name == b }) return b
        var i = 2
        while (scalars.any { it.name == "$b$i" }) i++
        return "$b$i"
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

    /** Names are unique so the panel is unambiguous; blank auto-numbers ("group1", "group2", …). */
    private fun uniqueGroupName(base: String): String {
        // one word, since a step's arguments are split on spaces (as for scalar names)
        val b = base.trim().replace(Regex("\\s+"), "-")
        if (b.isNotEmpty() && allGroups.none { it.name == b }) return b
        val stem = b.ifEmpty { "group" }
        // an unnamed group is "group1"; a name that clashes becomes "kitchen2", as for scalars
        var i = if (b.isEmpty()) 1 else 2
        while (allGroups.any { it.name == "$stem$i" }) i++
        return "$stem$i"
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
        val node = el.ref.node as? SourceNode ?: return false
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
        return Placement(boundsCentre(members) ?: Vec2(0.0, 0.0), candidates, paths, conflicts)
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
            ) { placeGroupNow(g, at, angle, analysis.candidates, analysis.paths) }
        g.placeStep = journal.lastOrNull()?.takeIf { it.kind == "place" }
        return result
    }

    private fun placeGroupNow(
        g: Group,
        at: Vec2,
        angle: Double,
        candidates: List<SourceNode>,
        paths: List<OrthoPath>,
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
        for ((i, src) in candidates.withIndex()) {
            val w = world[i] ?: continue
            // a change of origin, not of orientation — the one rule both capture kinds follow (see the
            // method comment and [capturePath]): the local coordinate is the world one measured from the
            // frame's origin, and the frame's angle then *turns* what it carries
            val local = SourceNode(nextId("lp"), PointValue(w - f.origin))
            src.boundTo = cx.frameApply(frame, Ref<PointValue>(local)).node
            val el = elements.lastOrNull { it.ref.node === src }
            val prior = el?.handle
            // its DOF is now the local point, so its handle must write *that* — by inverse-mapping the
            // cursor, which keeps the drag landing under the pointer and the fields reading world values
            if (el != null) el.handle = FramedPointHandle(node, local)
            g.captures.add(FrameCapture(src, local, el, prior))
        }
        for (path in paths) capturePath(g, path, frame, f)
        return PlaceResult(g.captures.size, g.capturedPaths.size, deformingMembers(g))
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
        val carried = g.captures.mapTo(HashSet()) { it.local }
        for (p in g.capturedPaths) carried.addAll(coordMasters(p, 0) + coordMasters(p, 1))
        val orthoCoords = HashSet<SourceNode>()
        for (p in orthoPaths) {
            for (v in p.vertices) {
                orthoCoords.add(v.corner.xNode)
                orthoCoords.add(v.corner.yNode)
            }
        }
        return groupMembers(g).filter { m ->
            ancestors(listOf(m.ref.node)).filterIsInstance<SourceNode>().any { s ->
                s.boundTo == null && s !in carried && (s.value is PointValue || s in orthoCoords)
            }
        }
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
            val node = el.ref.node as? SourceNode ?: return world
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

    /** Whether the element that *displays* [node] is one of [members] — see [analysePlacement]. */
    private fun ownedBy(
        node: SourceNode,
        members: Set<Element>,
    ): Boolean {
        val owner = elements.lastOrNull { it.ref.node === node } ?: return true
        return owner in members
    }

    /** How to name a source node to the user: the element showing it, else the node's own id. */
    private fun labelOf(node: Node): String = elements.lastOrNull { it.ref.node === node }?.id ?: node.id

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
                el.kind == ElementKind.POINT && (el.ref.node as? SourceNode)?.boundTo == null && el.ref.node.id in closure
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
        return MacroAnalysis(points, parameters, problems, owned.mapTo(HashSet()) { it.id })
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
        return listOfNotNull((el.ref.node as? SourceNode)?.takeIf { it.boundTo == null })
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
        el.kind == ElementKind.POINT && (el.ref.node as? SourceNode)?.boundTo != null && !isFramed(el)

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
    ): Boolean = recording("weld", Arg.El(alias), Arg.El(master)) { weldNow(alias, master) }

    private fun weldNow(
        alias: Element,
        master: Element,
    ): Boolean {
        val node = alias.ref.node as? SourceNode ?: return false
        if (alias.kind != ElementKind.POINT || node.boundTo != null) return false
        if (!master.isPoint || master === alias) return false
        val masterNode = master.ref.node
        if (masterNode === node || joinWouldCycle(alias, masterNode)) return false // no cycles
        node.boundTo = masterNode
        alias.visible = false
        return true
    }

    /** Un-weld / detach: the point resumes as an independent free point at its current position. */
    fun unweld(alias: Element) = recording("unweld", Arg.El(alias)) { unweldNow(alias) }

    private fun unweldNow(alias: Element) {
        val node = alias.ref.node as? SourceNode ?: return
        val cur = (Evaluator().eval(node) as? EvalResult.Ok)?.let { (it.value as? PointValue)?.p }
        node.boundTo = null
        if (cur != null) node.value = PointValue(cur)
        alias.kind = ElementKind.POINT
        alias.handle = FreePointHandle(node) // an independent free point again, handle included
        alias.style = Styles.FREE_POINT
        alias.visible = true
    }

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
        val node = pt.ref.node as? SourceNode ?: return null
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

    /**
     * Attach free point [pt] onto [curve]: it becomes a 1-DOF on-curve point (draggable along the
     * curve). The point's node is welded ([SourceNode.boundTo]) onto a fresh point-on-curve node
     * driven by a hidden parameter, so everything already referencing the point now slides with it.
     * Reversible via [unweld]. Same validity rules as [attachTargetPos].
     */
    fun attachToCurve(
        pt: Element,
        curve: Element,
    ): Boolean = recording("attach", Arg.El(pt), Arg.El(curve)) { attachToCurveNow(pt, curve) }

    private fun attachToCurveNow(
        pt: Element,
        curve: Element,
    ): Boolean {
        val node = pt.ref.node as? SourceNode ?: return false
        if (attachTargetPos(pt, curve) == null) return false
        val ev = Evaluator()
        val p = (ev.eval(node) as EvalResult.Ok).let { (it.value as PointValue).p }
        when {
            curve.isLinear -> {
                val lr = carrierLine(curve)
                val l = (ev.eval(lr.node) as EvalResult.Ok).value as LineValue
                val t0 = (p - l.line.origin).dot(l.line.dir)
                val tNode = SourceNode(nextId("t"), ScalarValue(Quantity.mm(t0)))
                node.boundTo = cx.pointOnLineAt(lr, Ref<ScalarValue>(tNode)).node
                pt.handle = OnLineHandle(lr, tNode)
            }
            else -> { // circle
                val cr = curve.ref as CircleRef
                val c = (ev.eval(cr.node) as EvalResult.Ok).value as CircleValue
                val aNode = SourceNode(nextId("a"), ScalarValue(Quantity.rad((p - c.circle.center).angle())))
                node.boundTo = cx.pointOnCircle(cr, Ref<ScalarValue>(aNode)).node
                pt.handle = OnCircleHandle(cr, aNode)
            }
        }
        pt.kind = ElementKind.ON_CURVE
        pt.style = Styles.ON_CURVE
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
        val junction = junctionOnCurve(curve, el.ref.node) ?: return false
        return bindCornerToJunction(corner, junction)
    }

    /** The node a junction on [curve] would ride — its carrier line, or the circle itself. */
    private fun carrierNodeOf(curve: Element): Node? =
        when {
            curve.isLinear -> carrierLine(curve).node
            curve.kind == ElementKind.CIRCLE -> curve.ref.node
            else -> null
        }

    /** A junction sliding along [curve], placed where [near] currently is. Null if it would cycle. */
    private fun junctionOnCurve(
        curve: Element,
        near: Node,
    ): Junction? {
        val ev = Evaluator()
        val p = (ev.eval(near) as? EvalResult.Ok)?.let { (it.value as? PointValue)?.p } ?: return null
        if (curve.isLinear) {
            val lr = carrierLine(curve)
            if (dependsOn(lr.node, near, HashSet())) return null
            val l = (ev.eval(lr.node) as? EvalResult.Ok)?.value as? LineValue ?: return null
            val tNode = SourceNode(nextId("jt"), ScalarValue(Quantity.mm((p - l.line.origin).dot(l.line.dir))))
            val point = cx.pointOnLineAt(lr, Ref<ScalarValue>(tNode))
            val junction = Junction(point, OnLineHandle(lr, tNode), curve)
            junction.place = { axis, value ->
                // a line is affine in its parameter: t = (value - origin) / dir, exactly
                val line = ((Evaluator().eval(lr.node) as? EvalResult.Ok)?.value as? LineValue)?.line
                val d = if (axis == 0) line?.dir?.x else line?.dir?.y
                val o = if (axis == 0) line?.origin?.x else line?.origin?.y
                if (line == null || d == null || o == null || abs(d) < Vec2.EPS) {
                    false
                } else {
                    tNode.value = ScalarValue(Quantity.mm((value - o) / d))
                    true
                }
            }
            junctions.add(junction)
            return junction
        }
        if (curve.kind == ElementKind.CIRCLE) {
            val cr = curve.ref as CircleRef
            if (dependsOn(cr.node, near, HashSet())) return null
            val c = (ev.eval(cr.node) as? EvalResult.Ok)?.value as? CircleValue ?: return null
            val aNode = SourceNode(nextId("ja"), ScalarValue(Quantity.rad((p - c.circle.center).angle())))
            val point = cx.pointOnCircle(cr, Ref<ScalarValue>(aNode))
            val junction = Junction(point, OnCircleHandle(cr, aNode), curve)
            junction.place = { axis, value ->
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
            junctions.add(junction)
            return junction
        }
        return null
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
    ): Boolean {
        if (pathFrameOf(corner) != null) return false
        val mx = writableMaster(corner.xNode) ?: return false
        val my = writableMaster(corner.yNode) ?: return false
        // the definitive cycle test, against the freedom that will actually drive the corner: a weld onto
        // an *existing* junction binds to that junction's point, which need not be the point clicked
        if (dependsOn(junction.point.node, mx, HashSet()) || dependsOn(junction.point.node, my, HashSet())) return false
        driveByJunction(mx, junction, junction.point, 0)
        if (my !== mx) driveByJunction(my, junction, junction.point, 1)
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
        val existing = (target.handle as? OrthoCornerHandle)?.let { junctionOf(it.xNode) ?: junctionOf(it.yNode) }
        // a target with no handle of its own — a derived point such as an intersection — makes a junction
        // that owns nothing: the meeting point is then fixed by construction, and honestly immovable
        val junction =
            existing ?: Junction(tref, target.handle, null).also {
                it.place = { axis, value ->
                    // a plain point owns its coordinates outright, so placing one is just a write
                    val field = it.handle?.fields()?.getOrNull(axis)
                    field?.write(Quantity.mm(value))
                    field?.writable == true
                }
                junctions.add(it)
            }
        return bindCornerToJunction(corner, junction)
    }

    fun remove(el: Element) {
        elements.remove(el)
    }

    // ---- points ----

    fun midpoint(
        a: PointRef,
        b: PointRef,
    ) = addDerived(cx.midpoint(a, b))

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

    private fun addConstrained(
        ref: PointRef,
        handle: Handle,
    ): PointRef {
        elements.add(Element(nextId("e"), ref, ElementKind.ON_CURVE, Styles.ON_CURVE, handle = handle))
        return ref
    }

    /** Point that slides along a line; created at the projection of [at], draggable along the line. */
    fun pointOnLine(
        line: Element,
        at: Vec2,
    ): PointRef {
        val lineRef = carrierLine(line)
        val l = (Evaluator().eval(lineRef.node) as? EvalResult.Ok)?.value as? LineValue
        val t0 = if (l != null) (at - l.line.origin).dot(l.line.dir) else 0.0
        val tNode = SourceNode(nextId("t"), ScalarValue(Quantity.mm(t0)))
        return addConstrained(cx.pointOnLineAt(lineRef, Ref<ScalarValue>(tNode)), OnLineHandle(lineRef, tNode))
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

    /** Point that slides along a circle; created at the click angle, draggable around the circle. */
    fun pointOnCircle(
        circle: Element,
        at: Vec2,
    ): PointRef {
        val circleRef = circle.ref as CircleRef
        val c = (Evaluator().eval(circleRef.node) as? EvalResult.Ok)?.value as? CircleValue
        val a0 = if (c != null) (at - c.circle.center).angle() else 0.0
        val aNode = SourceNode(nextId("a"), ScalarValue(Quantity.rad(a0)))
        return addConstrained(cx.pointOnCircle(circleRef, Ref<ScalarValue>(aNode)), OnCircleHandle(circleRef, aNode))
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
        val aCirc = a.kind == ElementKind.CIRCLE
        val bCirc = b.kind == ElementKind.CIRCLE
        val lineLine = aLin && bLin
        val set: PointSetRef =
            when {
                lineLine -> cx.intersectLL(carrierLine(a), carrierLine(b))
                aCirc && bCirc -> cx.intersectCC(a.ref as CircleRef, b.ref as CircleRef)
                aLin && bCirc -> cx.intersectLC(carrierLine(a), b.ref as CircleRef)
                aCirc && bLin -> cx.intersectLC(carrierLine(b), a.ref as CircleRef)
                else -> return null
            }
        return set to lineLine
    }

    /**
     * The single intersection of [a] and [b] nearest [near], as a derived point — the branch the
     * click indicated, persisted as its `Select(sign)` (OP-1), never re-guessed later.
     */
    fun intersectNear(
        a: Element,
        b: Element,
        near: Vec2,
    ): PointRef? = recording("intersectnear", Arg.El(a), Arg.El(b), Arg.Pos(near)) { intersectNearNow(a, b, near) }

    private fun intersectNearNow(
        a: Element,
        b: Element,
        near: Vec2,
    ): PointRef? {
        val (set, lineLine) = intersectionSet(a, b) ?: return null
        val ev = Evaluator()
        val candidates = if (lineLine) listOf(+1) else listOf(+1, -1)
        val best =
            candidates
                .map { it to cx.select(set, it) }
                .mapNotNull { (sign, ref) ->
                    ((ev.eval(ref.node) as? EvalResult.Ok)?.value as? PointValue)?.let { Triple(sign, ref, (it.p - near).length()) }
                }.minByOrNull { it.third } ?: return null
        return addDerived(best.second)
    }

    /** A point that slides along [el] at [at] — the on-curve form of a click landing on a curve. */
    fun pointOnCurve(
        el: Element,
        at: Vec2,
    ): PointRef? = recording("pointoncurve", Arg.El(el), Arg.Pos(at)) { pointOnCurveNow(el, at) }

    private fun pointOnCurveNow(
        el: Element,
        at: Vec2,
    ): PointRef? =
        when {
            el.isLinear -> pointOnLine(el, at)
            el.kind == ElementKind.CIRCLE -> pointOnCircle(el, at)
            else -> null
        }

    /** Materialize a curve's defining points as derived points (works on transformed geometry too). */
    fun extractPoints(el: Element): List<PointRef> {
        val refs: List<PointRef> =
            when (el.kind) {
                ElementKind.SEGMENT -> listOf(cx.segmentStart(el.ref as SegmentRef), cx.segmentEnd(el.ref as SegmentRef))
                ElementKind.CIRCLE -> listOf(cx.circleCenter(el.ref as CircleRef))
                ElementKind.ARC -> listOf(cx.arcCenter(el.ref as ArcRef), cx.arcStart(el.ref as ArcRef), cx.arcEnd(el.ref as ArcRef))
                ElementKind.RAY -> listOf(cx.rayOrigin(el.ref as RayRef))
                else -> emptyList()
            }
        refs.forEach { addDerived(it) }
        return refs
    }

    fun tangentFromPoint(
        p: PointRef,
        circle: Element,
    ): List<PointRef> {
        val set = cx.tangentPointsFromPoint(p, circle.ref as CircleRef)
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

    private fun driveByJunction(
        node: SourceNode,
        junction: Junction,
        driver: Ref<PointValue>,
        axis: Int,
    ) {
        node.boundTo = (if (axis == 0) cx.measureX(driver) else cx.measureY(driver)).node
        junctionByNode[node.id] = junction
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
    val thickPaths = ArrayList<ThickPath>()

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
    ): PointRef? =
        when {
            a.kind == ElementKind.BEZIER -> bezierEndNear(a, nearB, ev)
            b.kind == ElementKind.BEZIER -> bezierEndNear(b, nearA, ev)
            // pieces that *already* meet hand over there, instead of being re-intersected
            else -> sharedEndBetween(a, b, ev) ?: intersectNearNow(a, b, (nearA + nearB) * 0.5)
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
     * Build a retained thick path of [thickness] around the carrier [path] (OP-21). One node computes
     * the whole footprint region — offset faces, mitred corners, end caps — so this creates exactly one
     * element and never has to be rebuilt: editing the carrier, the thickness or any interval simply
     * recomputes it. The carrier stays a plain ortho path, draggable and typeable as before.
     */
    fun buildThickPath(
        path: OrthoPath,
        thickness: ScalarRef,
        justification: Justification = Justification.CENTER,
    ): ThickPath? =
        recording("wall", Arg.Sc(scalarEntryFor(thickness)), Arg.Text(justification.name.lowercase())) {
            buildThickPathNow(path.vertices.map { it.ref }, thickness, justification, path.closed, path)
        }

    private fun buildThickPathNow(
        vertices: List<PointRef>,
        thickness: ScalarRef,
        justification: Justification,
        closed: Boolean,
        carrier: OrthoPath?,
    ): ThickPath? {
        if (vertices.size < 2) return null
        val ring = closed && vertices.size >= 3
        val ref = cx.thickFootprint(vertices, thickness, ring, justification)
        val el = add(ref, ElementKind.AREA, Styles.FOOTPRINT)
        val tp = ThickPath(vertices.toList(), thickness, justification, ring, carrier, el)
        thickPaths.add(tp)
        return tp
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
     * - a **closed chain one step built** (a rectangle, a rounded rectangle, a polygon) is a boundary in
     *   the order that step created it. The order is the *construction's*, not something detected from the
     *   picture: OP-14 rejects seed-point region finding precisely because the loop's identity would be
     *   discovered, and here it is read off the step that built the pieces, so the same step always yields
     *   the same loop. What is checked (below) is only whether that chain currently closes.
     *
     * Deliberately **not** extended to "curves that happen to touch": that is region detection, and a
     * drawing where two constructions cross would then acquire areas the user never built.
     */
    fun boundaryPiecesOf(el: Element): List<Element>? {
        if (el.kind == ElementKind.CIRCLE) return listOf(el)
        if (!el.isCurve) return null
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
     * **The sketch plane is the world XY plane** in this slice: a 2D drawing *is* the plan, so that is
     * where its regions live, and the alternative — asking the user to pick a plane before there is any
     * way to make one — would be a datum-management UI before there is a datum to manage. Sketching on a
     * face (and therefore choosing a plane) arrives with the provenance accessors, which is the point of
     * `facePlane` already existing in the engine.
     *
     * The depth stays a **panel parameter**: it is the feature's degree of freedom, and OP-13 is
     * satisfied through the parameter rather than through a 3D drag handle, which there is no picking in
     * this view to grab (see [Viewport3]).
     */
    fun extrudeSolid(
        el: Element,
        depth: ScalarRef,
    ): Element? {
        val region = regionOf(el) ?: return null
        return add(cx.extrude(cx.sketchOn(cx.planeXY(), region), depth), ElementKind.SOLID, Styles.SOLID)
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
     */
    fun revolveSolid(
        el: Element,
        axis: Element,
        angle: ScalarRef,
    ): Element? {
        val region = regionOf(el) ?: return null
        if (!axis.isLinear) return null
        val line = carrierLine(axis)
        val ref = cx.revolve(cx.sketchOn(cx.planeXY(), region), cx.lineOrigin(line), cx.lineDirection(line), angle)
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
            thickPaths.firstOrNull { dependsOn(solidEl.ref.node, it.footprint.ref.node, HashSet()) }
                ?: return null
        if (tp.intervals.isEmpty()) return null
        var cut = solidEl.ref as SolidRef
        for (iv in tp.intervals) {
            val region =
                cx.intervalFootprint(
                    tp.vertices,
                    tp.thickness,
                    tp.closed,
                    tp.justification,
                    iv.legIndex,
                    iv.position,
                    iv.width,
                )
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
    fun thickPathOf(el: Element): ThickPath? = thickPaths.firstOrNull { it.footprint === el }

    /** The carrier vertex positions and offset faces of [tp] as they are now, or null if degenerate. */
    private fun facesOf(
        tp: ThickPath,
        ev: Evaluator,
    ): ThickFaces? {
        val pts = tp.vertices.map { ((ev.eval(it.node) as? EvalResult.Ok)?.value as? PointValue)?.p ?: return null }
        return GeomMath.thickFaces(pts, tp.closed, tp.justification.offsets(scalarMm(tp.thickness, ev))).first
    }

    /**
     * The **plan drawing** of [tp] (OP-21): its footprint faces broken at every interval, plus a jamb
     * (reveal) line across the path at each interval edge, plus end caps for an open carrier.
     *
     * A drawing convention, not a cut — the footprint region itself stays whole, which is what a plan
     * actually shows (below a sill and above a head there is material). Derived here, per render pass,
     * from evaluated values only: the intervals are sorted **by their current position**, so dragging one
     * past another re-sorts the drawing with no rebuild anywhere. That ordering is precisely the work
     * that must not happen while assembling the graph.
     */
    fun planOf(
        tp: ThickPath,
        ev: Evaluator,
    ): List<Segment>? {
        val f = facesOf(tp, ev) ?: return null
        val perLeg = (0 until f.legCount).map { intervalsOnLeg(tp, it, ev) }
        val out = ArrayList<Segment>()

        fun emit(
            a: Vec2,
            b: Vec2,
        ) {
            if ((b - a).length() > Vec2.EPS) out.add(Segment(a, b))
        }
        for (side in 0..1) {
            val corners = f.faces[side]
            for (i in 0 until f.legCount) {
                var cursor = corners[i]
                for ((pos, width) in perLeg[i]) {
                    emit(cursor, GeomMath.facePoint(f, i, pos, side))
                    cursor = GeomMath.facePoint(f, i, pos + width, side) // solid piece, then the gap
                }
                emit(cursor, corners[(i + 1) % corners.size])
            }
        }
        if (!tp.closed) {
            emit(f.faces[0].first(), f.faces[1].first())
            emit(f.faces[0].last(), f.faces[1].last())
        }
        for (i in 0 until f.legCount) {
            for ((pos, width) in perLeg[i]) {
                for (d in listOf(pos, pos + width)) {
                    emit(GeomMath.facePoint(f, i, d, 0), GeomMath.facePoint(f, i, d, 1))
                }
            }
        }
        return out
    }

    /** [tp]'s intervals on leg [i] as (position, width), **ordered by their current position**. */
    private fun intervalsOnLeg(
        tp: ThickPath,
        i: Int,
        ev: Evaluator,
    ): List<Pair<Double, Double>> =
        tp.intervals
            .filter { it.legIndex == i }
            .map { scalarMm(it.position, ev) to scalarMm(it.width, ev) }
            .sortedBy { it.first }

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
        tp: ThickPath,
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
        var best: ThickPath? = null
        var bestLeg = -1
        var bestPos = 0.0
        var bestLen = 0.0
        var bestD = Double.MAX_VALUE
        for (tp in thickPaths) {
            val threshold = tol + evalMm(tp.thickness) / 2 // clicking anywhere on the body counts
            val f = facesOf(tp, ev) ?: continue
            for (i in 0 until f.legCount) {
                val leg = f.legs[i]
                val along = (at - leg.origin).dot(leg.dir).coerceIn(0.0, f.legLengths[i])
                val d = (at - (leg.origin + leg.dir * along)).length()
                if (d <= threshold && d < bestD) {
                    bestD = d
                    best = tp
                    bestLeg = i
                    bestPos = along
                    bestLen = f.legLengths[i]
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

    fun perpBisector(
        a: PointRef,
        b: PointRef,
    ) = add(cx.perpBisector(a, b), ElementKind.LINE, Styles.CONSTRUCT)

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
     * Fillet between two legs (lines/segments/rays). The corner is their intersection; the
     * quadrant is chosen by which side of the corner each leg was clicked ([clickA]/[clickB]).
     */
    fun filletBetweenLines(
        leg1: Element,
        leg2: Element,
        radius: ScalarRef,
        clickA: Vec2,
        clickB: Vec2,
    ): Element {
        val l1 = carrierLine(leg1)
        val l2 = carrierLine(leg2)
        val (sign1, sign2) = legSigns(l1, l2, clickA, clickB)
        return add(cx.filletBetweenLines(l1, l2, radius, sign1, sign2), ElementKind.ARC, Styles.CURVE)
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
    ): Element {
        val l1 = carrierLine(leg1)
        val l2 = carrierLine(leg2)
        val (sign1, sign2) = legSigns(l1, l2, clickA, clickB)
        // two lines meet in a single point, so the branch is not a choice at all
        val corner = cx.select(cx.intersectLL(l1, l2), +1)
        val a = addDerived(cx.pointAlongLine(l1, corner, distance, sign1))
        val b = addDerived(cx.pointAlongLine(l2, corner, distance, sign2))
        return segment(a, b)
    }

    /**
     * Which way along each of two legs the clicked corner opens: `+1` along the leg's own direction, `-1`
     * against it. A stored discrete choice (OP-1) — the quadrant is decided once, when the tool is used,
     * and never re-derived as the legs move. Shared by the fillet and the chamfer, which differ only in
     * what they put in that corner.
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
        val denom = la.dir.cross(lb.dir)
        if (abs(denom) <= Vec2.EPS) return 1 to 1 // parallel legs: no corner to sit in
        val corner = la.origin + la.dir * ((lb.origin - la.origin).cross(lb.dir) / denom)
        return (if ((clickA - corner).dot(la.dir) < 0) -1 else 1) to (if ((clickB - corner).dot(lb.dir) < 0) -1 else 1)
    }

    // ---- shapes: several elements built round shared nodes, so the *shape* is invariant ----

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
     * [count]-1 copies of [geom], each translated by a whole multiple of the vector [from] → [to].
     *
     * A **fan, not a chain**: copy *k* is `k·v` from the original rather than one step from copy *k-1*, so
     * no copy depends on a sibling — deleting one leaves the rest, and every copy recomputes directly from
     * the original and the two vector points. The copy keeps the source's kind and style, so an array of a
     * circle is circles and an array of a segment is segments, with no per-kind case anywhere.
     */
    @Suppress("UNCHECKED_CAST")
    fun linearArray(
        geom: Element,
        from: PointRef,
        to: PointRef,
        count: Int,
    ): List<Element> {
        if (count < 2) return emptyList()
        val dx = cx.sub(cx.measureX(to), cx.measureX(from))
        val dy = cx.sub(cx.measureY(to), cx.measureY(from))
        return (1 until count).map { k ->
            val step = k.toDouble()
            add(cx.translateGeom(geom.ref as Ref<Value>, cx.scale(dx, step), cx.scale(dy, step)), geom.kind, geom.style)
        }
    }

    /**
     * [count]-1 copies of [geom] rotated about [center], evenly spaced round the full turn — the
     * interactive form of the bolt circle, whose macro does exactly this with points and holes.
     *
     * The angles are constants because [count] is structural: `360°/count` is not a value the user edits
     * afterwards, it is what "six of them, evenly spaced" *means*.
     */
    @Suppress("UNCHECKED_CAST")
    fun circularArray(
        geom: Element,
        center: PointRef,
        count: Int,
    ): List<Element> {
        if (count < 2) return emptyList()
        return (1 until count).map { k ->
            add(cx.rotate(geom.ref as Ref<Value>, center, cx.const((360.0 * k / count).deg)), geom.kind, geom.style)
        }
    }

    /** Both external (or internal) common tangents of two circles. */
    fun commonTangents(
        c1: Element,
        c2: Element,
        inner: Boolean,
    ): List<Element> {
        val a = c1.ref as CircleRef
        val b = c2.ref as CircleRef
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
        val ref = circle.ref as CircleRef
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
    ) = add(cx.mirror(geom.ref as Ref<Value>, axis.ref as LineRef), geom.kind, geom.style)

    @Suppress("UNCHECKED_CAST")
    fun rotate(
        geom: Element,
        center: PointRef,
        angle: ScalarRef,
    ) = add(cx.rotate(geom.ref as Ref<Value>, center, angle), geom.kind, geom.style)

    @Suppress("UNCHECKED_CAST")
    fun scale(
        geom: Element,
        center: PointRef,
        factor: ScalarRef,
    ) = add(cx.scaleGeom(geom.ref as Ref<Value>, center, factor), geom.kind, geom.style)

    @Suppress("UNCHECKED_CAST")
    fun translateByVector(
        geom: Element,
        from: PointRef,
        to: PointRef,
    ) = add(cx.translateByVector(geom.ref as Ref<Value>, from, to), geom.kind, geom.style)

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

    fun measureRadius(circle: Element) = measurement("radius", cx.measureRadius(circle.ref as CircleRef))

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
        val circle = carrierCircle(curve) ?: return null
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

    /** The full circle of a circle or arc element — the coercion a radial dimension needs. */
    @Suppress("UNCHECKED_CAST")
    private fun carrierCircle(el: Element): CircleRef? =
        when (el.kind) {
            ElementKind.CIRCLE -> el.ref as CircleRef
            ElementKind.ARC -> cx.circleOfArc(el.ref as ArcRef)
            else -> null
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
}

/** Head height a new interval carries by default (mm) — a door; the sill defaults to the floor. */
private const val DEFAULT_HEAD = 2100.0

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
}
