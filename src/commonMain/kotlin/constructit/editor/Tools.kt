package constructit.editor

import constructit.dsl.PointRef
import constructit.dsl.ScalarRef
import constructit.geom.Axis3
import constructit.geom.BoolOp
import constructit.geom.Vec2
import constructit.units.Dimension
import constructit.units.Quantity

/**
 * What the next click of a tool must supply. SIDE just captures a click position (creates nothing).
 *
 * AREA is the 2D→3D seam's slot (OP-17): it takes anything that *bounds an area* — a traced `Outline`
 * (one loop), a thick path's footprint (a region with holes), a **closed curve** (a circle), or a
 * **closed chain of curves one step built** (a rectangle, a rounded rectangle, a polygon). One slot for
 * all of them, because the difference is a coercion the document performs (`Document.regionOf`), not a
 * different pick — see `Document.boundaryPiecesOf`.
 *
 * SOLID is the boolean slot (OP-22). A solid is already pickable in the 2D canvas by its footprint hint,
 * so this needs no new picking machinery — only a filter, which is what a slot kind is.
 */
enum class SlotKind {
    PLACE_POINT,
    POINT,
    EXISTING_POINT,
    CURVE,

    /** Anything carrying an infinite line: a line, a segment or a ray (`Document.carrierLine`). */
    LINE,

    /**
     * Anything carrying a whole circle: a circle **or an arc** (`Document.carrierCircle`).
     *
     * The exact twin of [LINE], and it accepts an arc for the same reason [LINE] accepts a segment — every
     * circle op is about the carrier. The result may land off the arc's swept range, which is honest and is
     * said in the help of the tools that can do it, exactly as an intersection on a segment's carrier may
     * land beyond its ends.
     */
    CIRCLE,
    SEGMENT,
    GEOMETRY,
    ON_CIRCLE_POINT,
    SIDE,
    CENTRIC,

    /**
     * Either carrier: a line/segment/ray **or** a circle/arc — a *fillet leg*, whose only requirement is
     * that the leg determine a curve the rounding can be tangent to.
     */
    CARRIER,
    AREA,
    SOLID,
}

/**
 * One scalar input of a tool: the [name] the status line asks for, and the [dim] a typed number is read
 * in (OP-7 — mm for a length, degrees for an angle, a plain number otherwise).
 *
 * The dimension is what makes **typing** generic (OP-13): with it, digits typed in the status flow can
 * become a parameter for *any* scalar slot, so no tool needs to know that a value can be typed and the
 * mechanism has no per-tool half. It used to be a bare name, and a bare name cannot be turned into a
 * quantity without guessing.
 */
class ScalarSlot(
    val name: String,
    val dim: Dimension = Dimension.LENGTH,
    /**
     * The value this slot **means when nothing was typed or picked** — null for a slot the tool cannot do
     * without.
     *
     * A default is what lets a tool gain a scalar input *without gaining a step*: with every slot defaulted
     * the tool never waits (see [ToolDef.scalarsOptional]), so Midpoint stays one click plus one click and
     * only *also* accepts a factor. The declared number is what the status line names and what [ToolDef.build]
     * does when it is handed no value — for the ratio slot, 0.5, which is exactly `cx.midpoint`.
     */
    val default: Quantity? = null,
)

/**
 * CUSTOM is where a document's **user-defined macros** land (OP-6): a macro *is* a [ToolDef], so the
 * palette needs no second kind of button — only a category whose contents come from the document rather
 * than from [Tools.all]. See [Document.toolDef]: the registry is static plus the open document's macros.
 */
enum class ToolCategory { POINTS, CURVES, CONSTRUCT, TRANSFORM, MEASURE, ANNOTATE, RESULT, SOLIDS, CUSTOM }

/**
 * Geometry picked so far for the active tool (split by kind), [at] = the last click's world
 * position, and [clicks] = the world position of every click in slot order.
 */
class Picks(
    val points: List<PointRef>,
    val elements: List<Element>,
    val at: Vec2,
    val clicks: List<Vec2>,
    /**
     * State a replay hands back to a tool that owns degrees of freedom of its own — a dimension's offset
     * (OP-13), restated on save (OP-18). Empty for a live click, where the DOF is seeded from [clicks]
     * instead; the clicks keep encoding the *choices* (which side, which sector), which never change.
     */
    val dofs: List<Quantity> = emptyList(),
    /**
     * How many copies/vertices the tool is to build — a **structural** number, not a parameter: it
     * decides how many nodes exist, exactly as an ortho path's vertex count does, so it is recorded in
     * the tool step and re-run rather than edited afterwards (see *Structural count* in DESIGN.md).
     */
    val count: Int = 0,
    /**
     * Discrete choices a replay hands back **verbatim** — a fillet's variant, a chamfer's quadrant (OP-1,
     * OP-18). Empty for a live click, where they are scored from [clicks] once and then restated by the step
     * for every load after; a tool that is given them must not score again, since the corner the clicks were
     * scored against moves with the legs.
     */
    val signs: List<Int> = emptyList(),
    /**
     * What each click **landed on**, in slot order — the snap the editor resolved there, or null where the
     * click found nothing (and for every replayed step, which has no cursor to snap).
     *
     * A tool whose build connects to existing geometry reads it and calls [Document.linkPathEnd], the one
     * helper every joining route already goes through: an ortho-path click, the drag magnet's release, and the
     * rectangle's two corners. Nothing about it is recorded here, because a connection is recorded by *its own*
     * step (`weldortho` / `attachortho`, OP-18) — so replay rebuilds the links without ever re-snapping, which
     * is the same discipline [signs] follows for a discrete choice.
     */
    val landings: List<SnapResult?> = emptyList(),
)

/**
 * A data-driven tool: geometry [slots] to pick by clicking, [scalars] to take from the panel, a [help]
 * line for the status bar, then a [build] action.
 */
class ToolDef(
    val id: String,
    val label: String,
    val category: ToolCategory,
    val slots: List<SlotKind>,
    /**
     * The scalar inputs this tool consumes, **in order**, named and dimensioned for the status line and
     * for typed entry ("radius", a length). A list rather than a flag: a tool may need two (point from
     * coordinates wants x, then y), and the single-scalar majority is simply a list of one, so there is
     * one rule for collecting them and one rule for recording them (OP-18).
     */
    val scalars: List<ScalarSlot> = emptyList(),
    val help: String = "",
    /**
     * The single key that arms this tool, uppercase. A **tool option like any other**: the palette
     * renders it, `Editor.key` routes it, and nothing else knows it exists — so adding a shortcut stays
     * "edit the table", exactly as adding a tool is. Deliberately given to only a handful of tools: a
     * letter per tool would be a cipher, and the ones that carry a key are the ones a workflow uses over
     * and over (see the click budgets in DESIGN.md).
     */
    val shortcut: Char? = null,
    /**
     * When true the **last** slot repeats: the tool keeps collecting picks until the user finishes
     * (Enter, or clicking the first pick again to close a boundary). Keeps a variable-arity tool like
     * *Outline* data-driven instead of another special case in the controller.
     */
    val repeating: Boolean = false,
    /**
     * The smallest sensible [Picks.count] for this tool, or 0 when it needs no count at all — a polygon
     * needs three vertices, an array two instances. Non-zero is what makes the count field apply.
     */
    val minCount: Int = 0,
    /**
     * Whether a **whole group** may fill this tool's [SlotKind.GEOMETRY] slot (OP-16): the slot then holds
     * *every* member and [build] fans over the whole of [Picks.elements] instead of taking `elements[0]`.
     *
     * Declared per tool rather than inferred, for two reasons. A tool must *fan* to accept it, and only the
     * tool knows whether it can — and a tool with a second element slot (Mirror's axis) indexes
     * [Picks.elements] positionally, which a multi-element geometry slot would silently break. So opting in
     * is a promise about [build], made in the table like every other tool property.
     */
    val groupOperand: Boolean = false,
    /**
     * Whether this tool consumes **the part of the active face space** (OP-17's sequential-feature rule).
     * The editor resolves that part's current tip ([Document.facePartTip]) when the tool completes and puts
     * it in [Picks.elements] *before* the clicked ones, so [build] reads `elements[0]` as the part — and the
     * step records it by name, which is what makes replay exact and stops a second feature from forking the
     * model back onto the original base. Declared per tool rather than inferred, exactly as [groupOperand]
     * is: it is a promise about how [build] indexes its picks.
     */
    val facePartOperand: Boolean = false,
    /**
     * Whether [build] **records its own steps** instead of being wrapped in one `tool` step (OP-18).
     *
     * The rectangle needs it: what two clicks produce is a closed ortho path, and a path's degrees of freedom
     * are its corner positions — which the `orthostart`/`orthovertex`/`orthoclose` steps *restate* on every
     * save. A `tool` step restates only the clicks that started it, so every later drag of a corner or a leg
     * would be lost on reload. Recording the steps the ortho tool would have recorded also means the result
     * is not a special kind of path: the file cannot tell the two gestures apart, and needs no new step kind.
     *
     * One checkpoint still covers the whole gesture ([Editor.maybeCompleteTool] takes it), exactly as a break
     * that emits several steps does — one gesture, one undo.
     */
    val recordsSteps: Boolean = false,
    /**
     * Whether this tool's application is **replicated round a pattern** when its picks touch one (OP-23).
     *
     * Declared **true by default**, which is the opposite of every other opt-in here and deliberately so: the
     * rule of OP-23 is that *any* operation whose inputs touch pattern members fans out, and a table where
     * each tool had to remember to say so would be a rule with exceptions instead. So the exceptions are
     * where they belong — a handful of `false`s below, each with its reason. Two of them are structural and
     * enforced by [Document.replicationOf] rather than declared: a [repeating] tool already collects the whole
     * ring in one gesture, and a tool with no [slots] cannot touch a member.
     */
    val replicates: Boolean = true,
    val build: (Document, Picks, List<ScalarRef>) -> Unit,
) {
    /**
     * Whether this tool's scalars are **all defaulted**, i.e. it never waits for one (see
     * [ScalarSlot.default]). Such a tool completes on its last click and [build] then receives *no* scalar
     * refs unless the user typed or picked them — so gaining an optional input costs the existing gesture
     * nothing, and the tool's own step is unchanged when nobody used it.
     */
    val scalarsOptional: Boolean get() = scalars.isNotEmpty() && scalars.all { it.default != null }
}

object Tools {
    const val SELECT = "select"
    const val SELECT_HELP =
        "Drag a point to reshape the construction; drag empty space for a selection box; Shift+click to add or remove; " +
            "click a grouped element again to reach it alone; middle-drag or Space+drag to pan; wheel to zoom."

    /** Select is not a [ToolDef] (it is the absence of one), so its key lives beside the id. */
    const val SELECT_KEY = 'S'

    // The three dimensions a scalar slot can have, as named constructors — so the table below reads as
    // "a length called depth" and a slot can never be declared without saying how a typed number is read.
    private fun len(name: String) = ScalarSlot(name)

    private fun ang(name: String) = ScalarSlot(name, Dimension.ANGLE)

    /** An angle slot the tool can do without: [deg] is what it means with nothing typed. */
    private fun ang(
        name: String,
        deg: Double,
    ) = ScalarSlot(name, Dimension.ANGLE, Quantity.deg(deg))

    private fun num(name: String) = ScalarSlot(name, Dimension.NONE)

    /** A dimensionless slot the tool can do without — [ScalarSlot.default] names what it then means. */
    private fun num(
        name: String,
        default: Double,
    ) = ScalarSlot(name, Dimension.NONE, Quantity.number(default))

    /** A length slot the tool can do without: [mm] is what it means with nothing typed (0 = "don't"). */
    private fun len(
        name: String,
        mm: Double,
    ) = ScalarSlot(name, Dimension.LENGTH, Quantity.mm(mm))

    // Points
    const val POINT = "point"
    const val MIDPOINT = "midpoint"
    const val INTERSECT = "intersect"
    const val PROJECT = "project"
    const val POINT_ON_CIRCLE = "ptoncircle"
    const val POINT_ON_LINE = "ptonline"
    const val POINT_AT_DIST = "ptatdist"
    const val POINT_XY = "ptxy"
    const val CENTRE = "centre"
    const val KEY_POINTS = "keypoints"
    const val JOIN = "join"

    // OP-4 case (b): re-parameterize a free point onto an anchor, and the conversion back. Two tools rather
    // than one that guesses from what was clicked — a tool's slots say what it takes, and "make relative"
    // takes two points while "make absolute" takes one.
    const val MAKE_RELATIVE = "makerel"
    const val MAKE_ABSOLUTE = "makeabs"

    // Curves
    const val LINE = "line"
    const val SEGMENT = "segment"
    const val RAY = "ray"
    const val CIRCLE = "circle"
    const val CIRCLE_R = "circleR"
    const val ORTHO_PATH = "orthopath"
    const val BREAK_LEG = "breakleg"

    // The generic model is a thick path with interval features (OP-21); the *tool* names stay the
    // domain words the user expects.
    const val WALL = "wall"
    const val OPENING = "opening"
    const val CIRCLE_3 = "circle3"
    const val ARC_3 = "arc3"
    const val ARC_CS = "arccs"
    const val CONCENTRIC = "concentric"
    const val BEZIER = "bezier"

    /**
     * The rectangle tool: two diagonally opposite clicks, and what comes out is a **closed ortho path**
     * (GitHub issue #4). Its own id, because [RECTANGLE_V1] still has to mean what it always meant.
     */
    const val RECTANGLE = "rectpath"

    /**
     * The rectangle as it was built until now: four segments over the two clicked points and two derived
     * corners. **Replay only** — never offered in the palette, and kept because a stored step's meaning is
     * frozen (OP-18): every file written before this carries `tool rect`, and the loader checks that the step
     * creates exactly the six elements the script declares.
     */
    const val RECTANGLE_V1 = "rect"
    const val ROUNDED_RECT = "roundrect"
    const val POLYGON = "polygon"

    // Result layer (OP-14)
    const val OUTLINE = "outline"

    // Solids — the 2D->3D seam (OP-17)
    const val EXTRUDE = "extrude"
    const val REVOLVE = "revolve"

    // ...and back down again: a sketch on a solid's face, and a solid's section as 2D geometry
    const val EXTRUDE_ON_FACE = "extrudeface"
    const val SECTION = "section"

    // a named 2D sketch space on a solid's *side* face (OP-17), and the cut that space makes cheap
    const val SKETCH_ON_FACE = "sketchface"
    const val CUT = "cut"

    /**
     * A **datum** sketch space: any line, any angle (OP-17's datum extension, GitHub #6). *Sketch on face*
     * is its special case (a boundary segment at 90°) and *Section* is its parallel one.
     */
    const val SKETCH_PLANE = "sketchplane"

    // Booleans between same-axis prisms (OP-22), and the architectural application of them
    const val UNION = "union"
    const val SUBTRACT = "subtract"

    // not `INTERSECT`: that name is the point-intersection tool's, and a tool id is what the file
    // records (OP-18), so the solid one gets its own word rather than shadowing it
    const val INTERSECT_SOLIDS = "intersectsolids"
    const val CUT_OPENINGS = "cutopenings"

    // Construct
    const val PERP_BISECTOR = "perpbis"
    const val ANGLE_BISECTOR = "anglebis"
    const val PERPENDICULAR = "perp"
    const val PARALLEL = "parallel"
    const val PARALLEL_AT = "parallelat"
    const val TANGENT = "tangent"
    const val TANGENT_AT = "tangentat"
    const val FILLET = "fillet"
    const val CHAMFER = "chamfer"
    const val OUTER_TANGENTS = "outertan"
    const val INNER_TANGENTS = "innertan"

    // Transform
    const val MIRROR = "mirror"
    const val ROTATE = "rotate"
    const val SCALE = "scale"
    const val TRANSLATE_V = "translatev"
    const val ARRAY_LINEAR = "arraylinear"
    const val ARRAY_CIRCULAR = "arraycircular"

    // patterns as orbits (OP-23): not arrays — a pattern is a *rule* later gestures ride, and its members
    // are shared points that adjacent copies are built on
    const val PATTERN_CIRCULAR = "patterncircular"
    const val PATTERN_LINEAR = "patternlinear"

    // Measure
    const val DISTANCE = "mdist"
    const val ANGLE = "mangle"
    const val LENGTH = "mlen"
    const val RADIUS = "mradius"
    const val COORD_X = "mx"
    const val COORD_Y = "my"
    const val ANGLE_LINES = "manglelines"

    // 3D measurements (OP-4 forward): a solid's numbers, free to drive a new 2D construction. The axis of
    // an extent is a *discrete choice*, and the tool id is what stores it — hence three, not one (see
    // [Document.measureSolidExtent]).
    const val VOLUME = "mvolume"
    const val EXTENT_X = "mextentx"
    const val EXTENT_Y = "mextenty"
    const val EXTENT_Z = "mextentz"

    // Annotate (OP-4 + OP-14): a dimension *shows* a measurement, and drives nothing
    const val DIM_LINEAR = "dimlinear"
    const val DIM_RADIAL = "dimradial"
    const val DIM_ANGULAR = "dimangular"

    val all: List<ToolDef> =
        listOf(
            // ----- Points -----
            // `replicates = false`: a point placed *on* a member already is that member, so there is nothing
            // for an orbit to fan (OP-23)
            ToolDef(POINT, "Point", ToolCategory.POINTS, listOf(SlotKind.PLACE_POINT), shortcut = 'P', replicates = false, help = "Click empty space to place a free point.") { _, _, _ -> },
            // the factor is a *defaulted* scalar slot (0.5 = the midpoint), so the gesture is unchanged and
            // typing a number first turns the same two clicks into a ratio point (OP-13)
            ToolDef(MIDPOINT, "Midpoint / ratio point", ToolCategory.POINTS, listOf(SlotKind.POINT, SlotKind.POINT), scalars = listOf(num("factor", 0.5)), help = "Click two points to place their midpoint — or type a factor first (0.3 = three tenths of the way, 1.5 = beyond the second point) and drag it along afterwards.") { d, p, s -> d.midpoint(p.points[0], p.points[1], s.firstOrNull()) },
            ToolDef(INTERSECT, "Intersect", ToolCategory.POINTS, listOf(SlotKind.CURVE, SlotKind.CURVE), help = "Click two curves to add their intersection point(s). Curves count as their carriers, so a segment reaches beyond its ends and an arc round its whole circle — the point may land off the drawn piece.") { d, p, _ -> d.intersect(p.elements[0], p.elements[1]) },
            ToolDef(PROJECT, "Project to line", ToolCategory.POINTS, listOf(SlotKind.POINT, SlotKind.LINE), help = "Click a point, then a line, for the perpendicular foot.") { d, p, _ -> d.projectToLine(p.points[0], p.elements[0]) },
            // the rider's position along its host is **state** (dragged, typed, compensated, or re-anchored by a
            // placement), so it rides `dofs=` exactly as the `pointoncurve` step's does — the click stays the
            // *choice* it always was (which curve, which side). See [DocumentFormat.restate].
            ToolDef(POINT_ON_CIRCLE, "Point on circle", ToolCategory.POINTS, listOf(SlotKind.CIRCLE), replicates = false, help = "Click a circle or arc to add a point on it; drag it around the circle in Select mode (on an arc it rides the whole circle).") { d, p, _ -> d.pointOnCircle(p.elements[0], p.at, p.dofs.firstOrNull()) },
            ToolDef(POINT_ON_LINE, "Point on line", ToolCategory.POINTS, listOf(SlotKind.LINE), replicates = false, help = "Click a line to add a point on it; drag it along the line in Select mode.") { d, p, _ -> d.pointOnLine(p.elements[0], p.at, p.dofs.firstOrNull()) },
            ToolDef(POINT_AT_DIST, "Point at distance", ToolCategory.POINTS, listOf(SlotKind.POINT, SlotKind.LINE), scalars = listOf(len("distance")), help = "Type a distance (or pick a parameter in the panel), click the reference point, then click the line on the side you want.") { d, p, s -> d.pointAlongLine(p.elements[0], p.points[0], s[0], p.at) },
            // no slots at all: its inputs are both scalars, so it is complete as soon as the panel has
            // supplied x and y and a click merely says "now"
            ToolDef(POINT_XY, "Point (x, y)", ToolCategory.POINTS, emptyList(), scalars = listOf(len("x"), len("y")), help = "Type x, then y (or pick two parameters in the panel), then click anywhere: the point follows both, so editing either moves it.") { d, _, s -> d.pointFromCoordinates(s[0], s[1]) },
            ToolDef(CENTRE, "Centre", ToolCategory.POINTS, listOf(SlotKind.CENTRIC), help = "Click a circle or arc to add its centre point.") { d, p, _ -> d.centerOf(p.elements[0]) },
            ToolDef(KEY_POINTS, "Key points", ToolCategory.POINTS, listOf(SlotKind.CURVE), help = "Click a curve to add its defining points (endpoints, centre) — even on mirrored/derived geometry.") { d, p, _ -> d.extractPoints(p.elements[0]) },
            ToolDef(JOIN, "Join points", ToolCategory.POINTS, listOf(SlotKind.EXISTING_POINT, SlotKind.EXISTING_POINT), replicates = false, help = "Click the point to keep, then a free point to weld onto it (they become one).") { d, p, _ -> d.weld(p.elements[1], p.elements[0]) },
            // the offset is the tool's own DOF, restated on save through `dofs=` exactly as a dimension's
            // placement is (OP-13/OP-18), so a dragged or typed distance comes back
            ToolDef(MAKE_RELATIVE, "Make relative", ToolCategory.POINTS, listOf(SlotKind.EXISTING_POINT, SlotKind.EXISTING_POINT), replicates = false, help = "Click a free point, then the point it should follow: it keeps its distance and angle to that anchor, so moving the anchor takes it along. Drag it (or type distance / angle) to change the offset; Make absolute undoes it.") { d, p, _ -> d.makeRelative(p.elements[0], p.elements[1], p.dofs) },
            ToolDef(MAKE_ABSOLUTE, "Make absolute", ToolCategory.POINTS, listOf(SlotKind.EXISTING_POINT), replicates = false, help = "Click a point that follows something — relative to an anchor, welded, or riding a curve — to give it its own coordinates again, where it now stands.") { d, p, _ -> d.makeAbsolute(p.elements[0], p.dofs) },
            // ----- Curves -----
            ToolDef(LINE, "Line", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT), help = "Click two points to draw an infinite line.") { d, p, _ -> d.line(p.points[0], p.points[1]) },
            ToolDef(SEGMENT, "Segment", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT), shortcut = 'L', help = "Click two points to draw a segment.") { d, p, _ -> d.segment(p.points[0], p.points[1]) },
            ToolDef(RAY, "Ray", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT), help = "Click the origin, then a second point, to draw a ray.") { d, p, _ -> d.ray(p.points[0], p.points[1]) },
            ToolDef(ORTHO_PATH, "Ortho path", ToolCategory.CURVES, emptyList(), help = "Click to chain axis-aligned segments (each leg snaps horizontal/vertical, length is a parameter). Esc or double-click to finish.") { _, _, _ -> },
            ToolDef(BREAK_LEG, "Break curve", ToolCategory.CURVES, emptyList(), help = "Click a curve to split it where you clicked. On an ortho path's segment this inserts a zero-length corner you can pull into a jog; on a plain segment, an arc or a Bézier it makes two curves that together are the one you clicked, with the joint free to move (drag the split point, or type the Bézier's t).") { _, _, _ -> },
            ToolDef(WALL, "Wall", ToolCategory.CURVES, emptyList(), scalars = listOf(len("thickness")), shortcut = 'W', help = "Type a thickness (or pick a parameter in the panel), then click to chain an axis-aligned wall centerline; its footprint (mitred corners, end caps) is computed on finish. Esc or double-click to finish.") { _, _, _ -> },
            ToolDef(OPENING, "Opening (door/window)", ToolCategory.CURVES, emptyList(), scalars = listOf(len("width")), shortcut = 'D', help = "Type a width (or pick a parameter in the panel), then click on a wall to place a door/window there (position, width, sill and head stay editable; in plan the gap is a drawing convention, the wall itself stays whole).") { _, _, _ -> },
            ToolDef(CIRCLE, "Circle (centre, point)", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT), help = "Click the centre, then a point on the circle.") { d, p, _ -> d.circle(p.points[0], p.points[1]) },
            ToolDef(CIRCLE_R, "Circle (centre, radius)", ToolCategory.CURVES, listOf(SlotKind.POINT), scalars = listOf(len("radius")), shortcut = 'C', help = "Type a radius (or pick a parameter in the panel), then click the centre.") { d, p, s -> d.circleCR(p.points[0], s[0]) },
            ToolDef(CIRCLE_3, "Circle (3 points)", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT, SlotKind.POINT), help = "Click three points the circle passes through.") { d, p, _ -> d.circle3(p.points[0], p.points[1], p.points[2]) },
            ToolDef(ARC_3, "Arc (3 points)", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT, SlotKind.POINT), help = "Click start, a point on the arc, then the end.") { d, p, _ -> d.arc3(p.points[0], p.points[1], p.points[2]) },
            ToolDef(ARC_CS, "Arc (centre, ends)", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT, SlotKind.POINT), help = "Click the centre, the start point, then the end (sweeps counter-clockwise).") { d, p, _ -> d.arcCenterStartEnd(p.points[0], p.points[1], p.points[2]) },
            ToolDef(BEZIER, "Bezier curve", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT, SlotKind.POINT, SlotKind.POINT), help = "Click the start, two control points, then the end. Control points may be existing constructed points.") { d, p, _ -> d.bezierCurve(p.points[0], p.points[1], p.points[2], p.points[3]) },
            ToolDef(OUTLINE, "Outline", ToolCategory.RESULT, listOf(SlotKind.CURVE), repeating = true, shortcut = 'O', help = "Click the curves round the boundary in order, then click the first again (or press Enter) to close it.") { d, p, _ -> d.buildOutline(p.elements, p.clicks) },
            ToolDef(CONCENTRIC, "Concentric circle", ToolCategory.CURVES, listOf(SlotKind.CIRCLE, SlotKind.SIDE), scalars = listOf(len("distance")), help = "Type a distance (or pick a parameter in the panel), click a circle or arc, then click inside or outside for the concentric circle.") { d, p, s -> d.concentricCircle(p.elements[0], s[0], p.at) },
            // rectangular *by construction* — the two other corners share the clicked corners' coordinates,
            // so no gesture and no parameter edit can shear it (see [Document.rectangle])
            // two SIDE slots, because the corners this tool wants are the path's own vertices: a POINT slot
            // would place two free points beside them that nothing reads (see [Document.orthoRectangle])
            ToolDef(RECTANGLE, "Rectangle", ToolCategory.CURVES, listOf(SlotKind.SIDE, SlotKind.SIDE), shortcut = 'R', recordsSteps = true, help = "Click two diagonally opposite corners. The result is a closed ortho path: drag a corner or a whole side, type either side's length, and thicken it into walls. A corner clicked on existing geometry joins it, exactly as an ortho-path click does.") { d, p, _ -> d.orthoRectangle(p.clicks[0], p.clicks[1], p.landings.getOrNull(0), p.landings.getOrNull(1)) },
            ToolDef(ROUNDED_RECT, "Rounded rectangle", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT), scalars = listOf(len("radius")), help = "Type a corner radius (or pick a parameter in the panel), then click two diagonally opposite corners; centre and size follow those two points.") { d, p, s -> d.roundedRectangle(p.points[0], p.points[1], s[0]) },
            // the corner radius is a **defaulted** length slot (0 = don't round), and a non-zero one turns the
            // same two clicks into OP-23's composition — a circular pattern of the vertex, one replicated side
            // and one replicated fillet. So the everyday shortcut and the general mechanism are one
            // construction, and the tool records the steps that say which (see [Document.regularPolygonGesture]).
            // It does not itself replicate: what it builds *is* a pattern.
            ToolDef(POLYGON, "Regular polygon", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT), scalars = listOf(len("corner radius", 0.0)), minCount = 3, recordsSteps = true, replicates = false, help = "Set the number of sides, then click the centre and one vertex; the other vertices are that one rotated about the centre. Type a corner radius first to get a rounded polygon — a live pattern whose count you can re-stamp.") { d, p, s -> d.regularPolygonGesture(p, s) },
            // ----- Solids: the 2D->3D seam (OP-17). The sketch plane is the world XY plane in this
            // slice; the depth/angle is a panel parameter, which is where the feature's DOF is edited
            // (OP-13) since the 3D view has no picking yet.
            ToolDef(EXTRUDE, "Extrude", ToolCategory.SOLIDS, listOf(SlotKind.AREA), scalars = listOf(len("depth")), shortcut = 'E', help = "Type a depth (or pick a parameter in the panel), then click an outline or wall footprint: it becomes a solid, shown in the 3D view.") { d, p, s -> d.extrudeSolid(p.elements[0], s[0]) },
            ToolDef(REVOLVE, "Revolve", ToolCategory.SOLIDS, listOf(SlotKind.AREA, SlotKind.LINE), scalars = listOf(ang("angle")), help = "Type a angle (or pick a parameter in the panel), click an outline or footprint, then a line to spin it about (the profile must not cross the axis).") { d, p, s -> d.revolveSolid(p.elements[0], p.elements[1], s[0]) },
            // ----- and back down again (OP-17's downward direction). *Extrude on face* is the
            // sketch->feature->sketch loop as one gesture: the plan is drawn in the same 2D space, and the
            // tool only says which solid's top face it is stacked on (through `facePlane`, OP-8).
            // *Section* is the other direction: a solid's cross-section, as an ordinary 2D area.
            ToolDef(EXTRUDE_ON_FACE, "Extrude on face", ToolCategory.SOLIDS, listOf(SlotKind.SOLID, SlotKind.AREA), scalars = listOf(len("depth")), help = "Type a depth (or pick a parameter in the panel), click the solid to build on, then the area to raise: it is extruded from that solid's top face (an upper storey, a boss).") { d, p, s -> d.extrudeOnFace(p.elements[0], p.elements[1], s[0]) },
            ToolDef(SECTION, "Section", ToolCategory.SOLIDS, listOf(SlotKind.SOLID), scalars = listOf(len("height")), help = "Type a height (or pick a parameter in the panel), then click a solid: its cross-section at that height becomes an ordinary 2D area — dimension it, or extrude it again.") { d, p, s -> d.sectionSolid(p.elements[0], s[0]) },
            // ----- sketch on a *side* face (OP-17). One click, on a solid's footprint edge: a side face
            // projects to exactly that edge, so the edge names the face and the solid at once. Like the
            // path and opening tools this one records a step of its own (`sketchspace`, naming the
            // boundary-piece index — a discrete choice, OP-18), so the Editor runs its click.
            ToolDef(SKETCH_ON_FACE, "Sketch on face", ToolCategory.SOLIDS, emptyList(), help = "Click a straight footprint edge of a solid: the 2D view switches to that side face, where u runs along the edge from its start and v runs down from the top. Cut there drills into the material; Extrude builds a boss out of it.") { _, _, _ -> },
            // ----- ...and the general form of the same thing (GitHub #6): **any** line, **any** angle. One
            // LINE pick (a line, a segment, a ray or an ortho leg — the ordinary carrier coercion) plus a
            // *defaulted* angle slot, so the gesture is one click and typing a number first tilts the plane.
            // It records its own `sketchspace` step, like the face tool, and does not replicate: a sketch
            // space is organisation, not geometry an orbit could fan (OP-23).
            ToolDef(SKETCH_PLANE, "Sketch plane (line + angle)", ToolCategory.SOLIDS, listOf(SlotKind.LINE), scalars = listOf(ang("angle", 90.0)), recordsSteps = true, replicates = false, help = "Type an angle (90° if you type none), then click a line, segment or wall leg: the 2D view switches to a new sketch plane through that line, tilted by that angle out of the space you are in. u runs along the line, v rises out of the old plane; Extrude builds along the new plane's normal and Cut goes the other way, so a negative angle swaps them. The angle stays a parameter — retype it and the plane tilts, with everything drawn on it.") { d, p, s -> d.createDatumSpace(p.elements[0], s.firstOrNull()) },
            // `facePartOperand` makes elements[0] the part being cut — the *tip* of its boolean chain as it
            // stands, resolved by the editor and recorded in the step, so cuts chain instead of forking
            // it **does** replicate, and as a *chain*: the part operand is re-resolved per copy, so a Cut on one
            // member of a face-space pattern becomes a bolt circle of pockets in one body (OP-23)
            ToolDef(CUT, "Cut", ToolCategory.SOLIDS, listOf(SlotKind.AREA), scalars = listOf(len("depth")), facePartOperand = true, help = "In a face view: type a depth (or pick a parameter in the panel), then click an area — it is extruded into the material and subtracted from the part this face belongs to (a drilled hole, a pocket, a slot).") { d, p, s -> d.cutOnFace(p.elements[0], p.elements[1], s[0]) },
            // ----- Booleans (OP-22): exact for solids extruded along the same axis. Two solid picks and
            // nothing else — the slab algebra is the op node's job, so these are data like every other tool.
            ToolDef(UNION, "Union", ToolCategory.SOLIDS, listOf(SlotKind.SOLID, SlotKind.SOLID), help = "Click two solids to fuse them into one (they must be extruded along the same axis).") { d, p, _ -> d.combineSolids(p.elements[0], p.elements[1], BoolOp.UNION) },
            ToolDef(SUBTRACT, "Subtract", ToolCategory.SOLIDS, listOf(SlotKind.SOLID, SlotKind.SOLID), shortcut = 'X', help = "Click the solid to keep, then the one to remove from it (a counterbore, a pocket, an opening).") { d, p, _ -> d.combineSolids(p.elements[0], p.elements[1], BoolOp.SUBTRACT) },
            ToolDef(INTERSECT_SOLIDS, "Intersect solids", ToolCategory.SOLIDS, listOf(SlotKind.SOLID, SlotKind.SOLID), help = "Click two solids to keep only what they have in common.") { d, p, _ -> d.combineSolids(p.elements[0], p.elements[1], BoolOp.INTERSECT) },
            ToolDef(CUT_OPENINGS, "Cut openings", ToolCategory.SOLIDS, listOf(SlotKind.SOLID), help = "Click a solid extruded from a wall footprint: every opening on that wall becomes a subtracted box, sill to head. Openings added later need the tool again.") { d, p, _ -> d.cutOpenings(p.elements[0]) },
            // ----- Construct -----
            // the same defaulted factor as Midpoint: with none it is the bisector, with one it is the
            // perpendicular through that ratio point — composed from the ops that already exist
            ToolDef(PERP_BISECTOR, "Perp. bisector", ToolCategory.CONSTRUCT, listOf(SlotKind.POINT, SlotKind.POINT), scalars = listOf(num("factor", 0.5)), help = "Click two points for their perpendicular bisector — or type a factor first for the perpendicular through that point of the span instead.") { d, p, s -> d.perpBisector(p.points[0], p.points[1], s.firstOrNull()) },
            ToolDef(ANGLE_BISECTOR, "Angle bisector", ToolCategory.CONSTRUCT, listOf(SlotKind.POINT, SlotKind.POINT, SlotKind.POINT), help = "Click a point, the vertex, then another point.") { d, p, _ -> d.angleBisector(p.points[0], p.points[1], p.points[2]) },
            ToolDef(PERPENDICULAR, "Perpendicular", ToolCategory.CONSTRUCT, listOf(SlotKind.LINE, SlotKind.POINT), help = "Click a line, then a point, for the perpendicular through it.") { d, p, _ -> d.perpendicularThrough(p.elements[0], p.points[0]) },
            ToolDef(PARALLEL, "Parallel", ToolCategory.CONSTRUCT, listOf(SlotKind.LINE, SlotKind.POINT), help = "Click a line, then a point, for the parallel through it.") { d, p, _ -> d.parallelThrough(p.elements[0], p.points[0]) },
            ToolDef(PARALLEL_AT, "Parallel at distance", ToolCategory.CONSTRUCT, listOf(SlotKind.LINE, SlotKind.SIDE), scalars = listOf(len("distance")), help = "Type a distance (or pick a parameter in the panel), click the base line, then click the side you want the parallel on.") { d, p, s -> d.parallelAtDistance(p.elements[0], s[0], p.at) },
            ToolDef(TANGENT, "Tangent from point", ToolCategory.CONSTRUCT, listOf(SlotKind.POINT, SlotKind.CIRCLE), help = "Click an external point, then a circle or arc (an arc counts as its whole circle).") { d, p, _ -> d.tangentFromPoint(p.points[0], p.elements[0]) },
            ToolDef(TANGENT_AT, "Tangent at point", ToolCategory.CONSTRUCT, listOf(SlotKind.ON_CIRCLE_POINT), help = "Click a point that lies on a circle for the tangent there (use Point on circle).") { d, p, _ -> d.tangentAtPointOnCircle(p.elements[0]) },
            ToolDef(FILLET, "Fillet", ToolCategory.CONSTRUCT, listOf(SlotKind.CARRIER, SlotKind.CARRIER), scalars = listOf(len("radius")), help = "Type a radius (or pick a parameter in the panel), then click the two legs — lines, segments, circles or arcs — where you want the rounding to touch them.") { d, p, s -> d.filletBetweenCurves(p.elements[0], p.elements[1], s[0], p.clicks[0], p.clicks[1], p.signs) },
            // line-only, deliberately: a bevel across a round leg has two honest readings (a chord, or an
            // arc of the same length), and until the convention is stated a tool that picked one silently
            // would be guessing — recorded in DESIGN.md rather than half-built here
            ToolDef(CHAMFER, "Chamfer", ToolCategory.CONSTRUCT, listOf(SlotKind.LINE, SlotKind.LINE), scalars = listOf(len("distance")), help = "Type a chamfer distance (or pick a parameter in the panel), then click the two straight legs on the sides of the corner you want bevelled.") { d, p, s -> d.chamferBetweenLines(p.elements[0], p.elements[1], s[0], p.clicks[0], p.clicks[1], p.signs) },
            ToolDef(OUTER_TANGENTS, "Outer tangents", ToolCategory.CONSTRUCT, listOf(SlotKind.CIRCLE, SlotKind.CIRCLE), help = "Click two circles or arcs for their outer common tangents.") { d, p, _ -> d.commonTangents(p.elements[0], p.elements[1], inner = false) },
            ToolDef(INNER_TANGENTS, "Inner tangents", ToolCategory.CONSTRUCT, listOf(SlotKind.CIRCLE, SlotKind.CIRCLE), help = "Click two circles or arcs for their inner (crossing) common tangents.") { d, p, _ -> d.commonTangents(p.elements[0], p.elements[1], inner = true) },
            // ----- Transform -----
            ToolDef(MIRROR, "Mirror", ToolCategory.TRANSFORM, listOf(SlotKind.GEOMETRY, SlotKind.LINE), help = "Click geometry, then a line to mirror it across.") { d, p, _ -> d.mirror(p.elements[0], p.elements[1]) },
            ToolDef(ROTATE, "Rotate", ToolCategory.TRANSFORM, listOf(SlotKind.GEOMETRY, SlotKind.POINT), scalars = listOf(ang("angle")), help = "Type a angle (or pick a parameter in the panel), click geometry, then the centre.") { d, p, s -> d.rotate(p.elements[0], p.points[0], s[0]) },
            ToolDef(SCALE, "Scale", ToolCategory.TRANSFORM, listOf(SlotKind.GEOMETRY, SlotKind.POINT), scalars = listOf(num("factor")), help = "Type a factor (or pick a parameter in the panel), click geometry, then the centre.") { d, p, s -> d.scale(p.elements[0], p.points[0], s[0]) },
            ToolDef(TRANSLATE_V, "Translate by vector", ToolCategory.TRANSFORM, listOf(SlotKind.GEOMETRY, SlotKind.POINT, SlotKind.POINT), help = "Click geometry, then two points defining the translation vector.") { d, p, _ -> d.translateByVector(p.elements[0], p.points[0], p.points[1]) },
            // arrays: the interactive generalization of the boltCircle / holePattern macros (OP-6) — the
            // count is structural, so a different count is a different construction, not an edited value.
            // Their geometry slot takes a **whole group** as one operand (`groupOperand`, OP-16), which is
            // why both build from the whole of `p.elements`: one element is the list of one.
            ToolDef(ARRAY_LINEAR, "Linear array", ToolCategory.TRANSFORM, listOf(SlotKind.GEOMETRY, SlotKind.POINT, SlotKind.POINT), minCount = 2, groupOperand = true, replicates = false, help = "Set the number of instances, then click the geometry and two points giving the step vector; every copy follows both. With a whole group selected, clicking any member arrays the whole group.") { d, p, _ -> d.linearArray(p.elements, p.points[0], p.points[1], p.count) },
            ToolDef(ARRAY_CIRCULAR, "Circular array", ToolCategory.TRANSFORM, listOf(SlotKind.GEOMETRY, SlotKind.POINT), minCount = 2, groupOperand = true, replicates = false, help = "Set the number of instances, then click the geometry and the centre; the copies are spaced evenly round it. With a whole group selected, clicking any member arrays the whole group.") { d, p, _ -> d.circularArray(p.elements, p.points[0], p.count) },
            // patterns (OP-23). A pattern is **not** an array: an array copies geometry, a pattern states a
            // rule that later gestures ride, and its members are shared points the copies are built *on*. Both
            // record their own `pattern` step, because a pattern is a named object whose count can be
            // re-stamped — and neither replicates, since what it builds is the pattern itself.
            ToolDef(PATTERN_CIRCULAR, "Circular pattern", ToolCategory.TRANSFORM, listOf(SlotKind.POINT, SlotKind.POINT), minCount = 2, recordsSteps = true, replicates = false, help = "Set the number of instances, then click the centre and one reference point: the point is repeated evenly round the centre. Anything you build on its members afterwards is repeated round it too — one segment makes every side, one fillet rounds every corner.") { d, p, _ -> d.createPattern(PatternKind.CIRCULAR, p.points[1], p.points[0], p.count) },
            ToolDef(PATTERN_LINEAR, "Linear pattern", ToolCategory.TRANSFORM, listOf(SlotKind.POINT, SlotKind.POINT), minCount = 2, recordsSteps = true, replicates = false, help = "Set the number of instances, then click the base point and the step vector's end: the base is repeated along that vector. Anything you build on its members afterwards is repeated along it too (a row of holes, one circle).") { d, p, _ -> d.createPattern(PatternKind.LINEAR, p.points[0], p.points[1], p.count) },
            // ----- Measure -----
            // a measurement is a **reading**, not geometry: six of the same number is clutter where one is the
            // answer, so the measure and annotate tools decline the orbit (OP-23)
            ToolDef(DISTANCE, "Distance", ToolCategory.MEASURE, listOf(SlotKind.POINT, SlotKind.POINT), replicates = false, help = "Click two points to measure their distance.") { d, p, _ -> d.measureDistance(p.points[0], p.points[1]) },
            ToolDef(ANGLE, "Angle", ToolCategory.MEASURE, listOf(SlotKind.POINT, SlotKind.POINT, SlotKind.POINT), replicates = false, help = "Click a point, the vertex, then another point.") { d, p, _ -> d.measureAngle(p.points[0], p.points[1], p.points[2]) },
            ToolDef(LENGTH, "Length", ToolCategory.MEASURE, listOf(SlotKind.SEGMENT), replicates = false, help = "Click a segment to measure its length.") { d, p, _ -> d.measureLength(p.elements[0]) },
            ToolDef(RADIUS, "Radius", ToolCategory.MEASURE, listOf(SlotKind.CIRCLE), replicates = false, help = "Click a circle or arc to measure its radius.") { d, p, _ -> d.measureRadius(p.elements[0]) },
            ToolDef(COORD_X, "X coordinate", ToolCategory.MEASURE, listOf(SlotKind.POINT), replicates = false, help = "Click a point to read its x coordinate.") { d, p, _ -> d.measureX(p.points[0]) },
            ToolDef(COORD_Y, "Y coordinate", ToolCategory.MEASURE, listOf(SlotKind.POINT), replicates = false, help = "Click a point to read its y coordinate.") { d, p, _ -> d.measureY(p.points[0]) },
            ToolDef(ANGLE_LINES, "Angle (2 lines)", ToolCategory.MEASURE, listOf(SlotKind.LINE, SlotKind.LINE), replicates = false, help = "Click two lines to measure the angle between them.") { d, p, _ -> d.measureAngleLines(p.elements[0], p.elements[1]) },
            // 3D measurements (OP-4): the solid is picked in plan by its footprint hint, like any other
            // solid pick, and the number lands in the panel as a read-only scalar — usable downstream.
            ToolDef(VOLUME, "Volume", ToolCategory.MEASURE, listOf(SlotKind.SOLID), replicates = false, help = "Click a solid to measure its volume.") { d, p, _ -> d.measureSolidVolume(p.elements[0]) },
            ToolDef(EXTENT_X, "Extent (X)", ToolCategory.MEASURE, listOf(SlotKind.SOLID), replicates = false, help = "Click a solid to measure how far it reaches along X.") { d, p, _ -> d.measureSolidExtent(p.elements[0], Axis3.X) },
            ToolDef(EXTENT_Y, "Extent (Y)", ToolCategory.MEASURE, listOf(SlotKind.SOLID), replicates = false, help = "Click a solid to measure how far it reaches along Y.") { d, p, _ -> d.measureSolidExtent(p.elements[0], Axis3.Y) },
            ToolDef(EXTENT_Z, "Extent (Z)", ToolCategory.MEASURE, listOf(SlotKind.SOLID), replicates = false, help = "Click a solid to measure its height along Z.") { d, p, _ -> d.measureSolidExtent(p.elements[0], Axis3.Z) },
            // ----- Annotate: dimensions (OP-4) — the graphic shows a measurement, and drives nothing -----
            ToolDef(DIM_LINEAR, "Linear dimension", ToolCategory.ANNOTATE, listOf(SlotKind.EXISTING_POINT, SlotKind.EXISTING_POINT, SlotKind.SIDE), shortcut = 'M', replicates = false, help = "Click two points, then click where the dimension line should sit (drag it later, or type the offset).") { d, p, _ -> d.linearDimension(p.elements[0], p.elements[1], p.at, p.dofs) },
            ToolDef(DIM_RADIAL, "Radial dimension", ToolCategory.ANNOTATE, listOf(SlotKind.CENTRIC, SlotKind.SIDE), replicates = false, help = "Click a circle or arc, then click where the leader and its radius should sit.") { d, p, _ -> d.radialDimension(p.elements[0], p.at, p.dofs) },
            ToolDef(DIM_ANGULAR, "Angular dimension", ToolCategory.ANNOTATE, listOf(SlotKind.LINE, SlotKind.LINE, SlotKind.SIDE), replicates = false, help = "Click two lines, then click inside the angle you mean — that sector is what the dimension names.") { d, p, _ -> d.angularDimension(p.elements[0], p.elements[1], p.at, p.dofs) },
        )

    /**
     * Tools that exist **only to replay older files** (OP-18): a build kept reachable because a step already
     * written down means what it meant, while the gesture that used to produce it has moved on. Resolved by
     * [byId] and so by [Document.toolDef], but deliberately *not* part of [all] — the palette shows [all],
     * so nothing here can be armed, and no new file can name one.
     */
    val legacy: List<ToolDef> =
        listOf(
            ToolDef(RECTANGLE_V1, "Rectangle (as built before)", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT), help = "Four segments over two clicked corners and two derived ones — replaced by the ortho-path rectangle, and kept so older files replay.") { d, p, _ -> d.rectangle(p.points[0], p.points[1]) },
        )

    /**
     * The *built-in* tool of that id, including the replay-only [legacy] ones. Call [Document.toolDef]
     * instead wherever a document is at hand: a user-defined macro is a tool too (OP-6), and only the
     * document knows its own macros.
     */
    fun byId(id: String): ToolDef? = all.firstOrNull { it.id == id } ?: legacy.firstOrNull { it.id == id }

    /**
     * The tool [c] arms, case-insensitively, or null. Only built-in tools have keys: a macro's name is
     * the user's, so assigning it a letter would collide unpredictably with this table.
     */
    fun byShortcut(c: Char): String? {
        val k = c.uppercaseChar()
        if (k == SELECT_KEY) return SELECT
        return all.firstOrNull { it.shortcut == k }?.id
    }

    /** The key that arms [id], for the palette's label. */
    fun shortcutOf(id: String): Char? = if (id == SELECT) SELECT_KEY else byId(id)?.shortcut
}
