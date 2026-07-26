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
class ScalarSlot(val name: String, val dim: Dimension = Dimension.LENGTH)

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
    val build: (Document, Picks, List<ScalarRef>) -> Unit,
)

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

    private fun num(name: String) = ScalarSlot(name, Dimension.NONE)

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
    const val RECTANGLE = "rect"
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
            ToolDef(POINT, "Point", ToolCategory.POINTS, listOf(SlotKind.PLACE_POINT), shortcut = 'P', help = "Click empty space to place a free point.") { _, _, _ -> },
            ToolDef(MIDPOINT, "Midpoint", ToolCategory.POINTS, listOf(SlotKind.POINT, SlotKind.POINT), help = "Click two points to place their midpoint.") { d, p, _ -> d.midpoint(p.points[0], p.points[1]) },
            ToolDef(INTERSECT, "Intersect", ToolCategory.POINTS, listOf(SlotKind.CURVE, SlotKind.CURVE), help = "Click two curves to add their intersection point(s). Curves count as their carriers, so a segment reaches beyond its ends and an arc round its whole circle — the point may land off the drawn piece.") { d, p, _ -> d.intersect(p.elements[0], p.elements[1]) },
            ToolDef(PROJECT, "Project to line", ToolCategory.POINTS, listOf(SlotKind.POINT, SlotKind.LINE), help = "Click a point, then a line, for the perpendicular foot.") { d, p, _ -> d.projectToLine(p.points[0], p.elements[0]) },
            ToolDef(POINT_ON_CIRCLE, "Point on circle", ToolCategory.POINTS, listOf(SlotKind.CIRCLE), help = "Click a circle or arc to add a point on it; drag it around the circle in Select mode (on an arc it rides the whole circle).") { d, p, _ -> d.pointOnCircle(p.elements[0], p.at) },
            ToolDef(POINT_ON_LINE, "Point on line", ToolCategory.POINTS, listOf(SlotKind.LINE), help = "Click a line to add a point on it; drag it along the line in Select mode.") { d, p, _ -> d.pointOnLine(p.elements[0], p.at) },
            ToolDef(POINT_AT_DIST, "Point at distance", ToolCategory.POINTS, listOf(SlotKind.POINT, SlotKind.LINE), scalars = listOf(len("distance")), help = "Type a distance (or pick a parameter in the panel), click the reference point, then click the line on the side you want.") { d, p, s -> d.pointAlongLine(p.elements[0], p.points[0], s[0], p.at) },
            // no slots at all: its inputs are both scalars, so it is complete as soon as the panel has
            // supplied x and y and a click merely says "now"
            ToolDef(POINT_XY, "Point (x, y)", ToolCategory.POINTS, emptyList(), scalars = listOf(len("x"), len("y")), help = "Type x, then y (or pick two parameters in the panel), then click anywhere: the point follows both, so editing either moves it.") { d, _, s -> d.pointFromCoordinates(s[0], s[1]) },
            ToolDef(CENTRE, "Centre", ToolCategory.POINTS, listOf(SlotKind.CENTRIC), help = "Click a circle or arc to add its centre point.") { d, p, _ -> d.centerOf(p.elements[0]) },
            ToolDef(KEY_POINTS, "Key points", ToolCategory.POINTS, listOf(SlotKind.CURVE), help = "Click a curve to add its defining points (endpoints, centre) — even on mirrored/derived geometry.") { d, p, _ -> d.extractPoints(p.elements[0]) },
            ToolDef(JOIN, "Join points", ToolCategory.POINTS, listOf(SlotKind.EXISTING_POINT, SlotKind.EXISTING_POINT), help = "Click the point to keep, then a free point to weld onto it (they become one).") { d, p, _ -> d.weld(p.elements[1], p.elements[0]) },
            // ----- Curves -----
            ToolDef(LINE, "Line", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT), help = "Click two points to draw an infinite line.") { d, p, _ -> d.line(p.points[0], p.points[1]) },
            ToolDef(SEGMENT, "Segment", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT), shortcut = 'L', help = "Click two points to draw a segment.") { d, p, _ -> d.segment(p.points[0], p.points[1]) },
            ToolDef(RAY, "Ray", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT), help = "Click the origin, then a second point, to draw a ray.") { d, p, _ -> d.ray(p.points[0], p.points[1]) },
            ToolDef(ORTHO_PATH, "Ortho path", ToolCategory.CURVES, emptyList(), help = "Click to chain axis-aligned segments (each leg snaps horizontal/vertical, length is a parameter). Esc or double-click to finish.") { _, _, _ -> },
            ToolDef(BREAK_LEG, "Break segment", ToolCategory.CURVES, emptyList(), help = "Click a segment of an ortho path to split it there, inserting a zero-length corner you can then pull into a jog.") { _, _, _ -> },
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
            ToolDef(RECTANGLE, "Rectangle", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT), shortcut = 'R', help = "Click two diagonally opposite corners; the other two follow them, so it stays a rectangle however you drag it.") { d, p, _ -> d.rectangle(p.points[0], p.points[1]) },
            ToolDef(ROUNDED_RECT, "Rounded rectangle", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT), scalars = listOf(len("radius")), help = "Type a corner radius (or pick a parameter in the panel), then click two diagonally opposite corners; centre and size follow those two points.") { d, p, s -> d.roundedRectangle(p.points[0], p.points[1], s[0]) },
            ToolDef(POLYGON, "Regular polygon", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT), minCount = 3, help = "Set the number of sides, then click the centre and one vertex; the other vertices are that one rotated about the centre.") { d, p, _ -> d.regularPolygon(p.points[0], p.points[1], p.count) },
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
            // ----- Booleans (OP-22): exact for solids extruded along the same axis. Two solid picks and
            // nothing else — the slab algebra is the op node's job, so these are data like every other tool.
            ToolDef(UNION, "Union", ToolCategory.SOLIDS, listOf(SlotKind.SOLID, SlotKind.SOLID), help = "Click two solids to fuse them into one (they must be extruded along the same axis).") { d, p, _ -> d.combineSolids(p.elements[0], p.elements[1], BoolOp.UNION) },
            ToolDef(SUBTRACT, "Subtract", ToolCategory.SOLIDS, listOf(SlotKind.SOLID, SlotKind.SOLID), shortcut = 'X', help = "Click the solid to keep, then the one to remove from it (a counterbore, a pocket, an opening).") { d, p, _ -> d.combineSolids(p.elements[0], p.elements[1], BoolOp.SUBTRACT) },
            ToolDef(INTERSECT_SOLIDS, "Intersect solids", ToolCategory.SOLIDS, listOf(SlotKind.SOLID, SlotKind.SOLID), help = "Click two solids to keep only what they have in common.") { d, p, _ -> d.combineSolids(p.elements[0], p.elements[1], BoolOp.INTERSECT) },
            ToolDef(CUT_OPENINGS, "Cut openings", ToolCategory.SOLIDS, listOf(SlotKind.SOLID), help = "Click a solid extruded from a wall footprint: every opening on that wall becomes a subtracted box, sill to head. Openings added later need the tool again.") { d, p, _ -> d.cutOpenings(p.elements[0]) },
            // ----- Construct -----
            ToolDef(PERP_BISECTOR, "Perp. bisector", ToolCategory.CONSTRUCT, listOf(SlotKind.POINT, SlotKind.POINT), help = "Click two points for their perpendicular bisector.") { d, p, _ -> d.perpBisector(p.points[0], p.points[1]) },
            ToolDef(ANGLE_BISECTOR, "Angle bisector", ToolCategory.CONSTRUCT, listOf(SlotKind.POINT, SlotKind.POINT, SlotKind.POINT), help = "Click a point, the vertex, then another point.") { d, p, _ -> d.angleBisector(p.points[0], p.points[1], p.points[2]) },
            ToolDef(PERPENDICULAR, "Perpendicular", ToolCategory.CONSTRUCT, listOf(SlotKind.LINE, SlotKind.POINT), help = "Click a line, then a point, for the perpendicular through it.") { d, p, _ -> d.perpendicularThrough(p.elements[0], p.points[0]) },
            ToolDef(PARALLEL, "Parallel", ToolCategory.CONSTRUCT, listOf(SlotKind.LINE, SlotKind.POINT), help = "Click a line, then a point, for the parallel through it.") { d, p, _ -> d.parallelThrough(p.elements[0], p.points[0]) },
            ToolDef(PARALLEL_AT, "Parallel at distance", ToolCategory.CONSTRUCT, listOf(SlotKind.LINE, SlotKind.SIDE), scalars = listOf(len("distance")), help = "Type a distance (or pick a parameter in the panel), click the base line, then click the side you want the parallel on.") { d, p, s -> d.parallelAtDistance(p.elements[0], s[0], p.at) },
            ToolDef(TANGENT, "Tangent from point", ToolCategory.CONSTRUCT, listOf(SlotKind.POINT, SlotKind.CIRCLE), help = "Click an external point, then a circle or arc (an arc counts as its whole circle).") { d, p, _ -> d.tangentFromPoint(p.points[0], p.elements[0]) },
            ToolDef(TANGENT_AT, "Tangent at point", ToolCategory.CONSTRUCT, listOf(SlotKind.ON_CIRCLE_POINT), help = "Click a point that lies on a circle for the tangent there (use Point on circle).") { d, p, _ -> d.tangentAtPointOnCircle(p.elements[0]) },
            ToolDef(FILLET, "Fillet", ToolCategory.CONSTRUCT, listOf(SlotKind.CARRIER, SlotKind.CARRIER), scalars = listOf(len("radius")), help = "Type a radius (or pick a parameter in the panel), then click the two legs — lines, segments, circles or arcs — where you want the rounding to touch them.") { d, p, s -> d.filletBetweenCurves(p.elements[0], p.elements[1], s[0], p.clicks[0], p.clicks[1]) },
            // line-only, deliberately: a bevel across a round leg has two honest readings (a chord, or an
            // arc of the same length), and until the convention is stated a tool that picked one silently
            // would be guessing — recorded in DESIGN.md rather than half-built here
            ToolDef(CHAMFER, "Chamfer", ToolCategory.CONSTRUCT, listOf(SlotKind.LINE, SlotKind.LINE), scalars = listOf(len("distance")), help = "Type a chamfer distance (or pick a parameter in the panel), then click the two straight legs on the sides of the corner you want bevelled.") { d, p, s -> d.chamferBetweenLines(p.elements[0], p.elements[1], s[0], p.clicks[0], p.clicks[1]) },
            ToolDef(OUTER_TANGENTS, "Outer tangents", ToolCategory.CONSTRUCT, listOf(SlotKind.CIRCLE, SlotKind.CIRCLE), help = "Click two circles or arcs for their outer common tangents.") { d, p, _ -> d.commonTangents(p.elements[0], p.elements[1], inner = false) },
            ToolDef(INNER_TANGENTS, "Inner tangents", ToolCategory.CONSTRUCT, listOf(SlotKind.CIRCLE, SlotKind.CIRCLE), help = "Click two circles or arcs for their inner (crossing) common tangents.") { d, p, _ -> d.commonTangents(p.elements[0], p.elements[1], inner = true) },
            // ----- Transform -----
            ToolDef(MIRROR, "Mirror", ToolCategory.TRANSFORM, listOf(SlotKind.GEOMETRY, SlotKind.LINE), help = "Click geometry, then a line to mirror it across.") { d, p, _ -> d.mirror(p.elements[0], p.elements[1]) },
            ToolDef(ROTATE, "Rotate", ToolCategory.TRANSFORM, listOf(SlotKind.GEOMETRY, SlotKind.POINT), scalars = listOf(ang("angle")), help = "Type a angle (or pick a parameter in the panel), click geometry, then the centre.") { d, p, s -> d.rotate(p.elements[0], p.points[0], s[0]) },
            ToolDef(SCALE, "Scale", ToolCategory.TRANSFORM, listOf(SlotKind.GEOMETRY, SlotKind.POINT), scalars = listOf(num("factor")), help = "Type a factor (or pick a parameter in the panel), click geometry, then the centre.") { d, p, s -> d.scale(p.elements[0], p.points[0], s[0]) },
            ToolDef(TRANSLATE_V, "Translate by vector", ToolCategory.TRANSFORM, listOf(SlotKind.GEOMETRY, SlotKind.POINT, SlotKind.POINT), help = "Click geometry, then two points defining the translation vector.") { d, p, _ -> d.translateByVector(p.elements[0], p.points[0], p.points[1]) },
            // arrays: the interactive generalization of the boltCircle / holePattern macros (OP-6) — the
            // count is structural, so a different count is a different construction, not an edited value
            ToolDef(ARRAY_LINEAR, "Linear array", ToolCategory.TRANSFORM, listOf(SlotKind.GEOMETRY, SlotKind.POINT, SlotKind.POINT), minCount = 2, help = "Set the number of instances, then click the geometry and two points giving the step vector; every copy follows both.") { d, p, _ -> d.linearArray(p.elements[0], p.points[0], p.points[1], p.count) },
            ToolDef(ARRAY_CIRCULAR, "Circular array", ToolCategory.TRANSFORM, listOf(SlotKind.GEOMETRY, SlotKind.POINT), minCount = 2, help = "Set the number of instances, then click the geometry and the centre; the copies are spaced evenly round it.") { d, p, _ -> d.circularArray(p.elements[0], p.points[0], p.count) },
            // ----- Measure -----
            ToolDef(DISTANCE, "Distance", ToolCategory.MEASURE, listOf(SlotKind.POINT, SlotKind.POINT), help = "Click two points to measure their distance.") { d, p, _ -> d.measureDistance(p.points[0], p.points[1]) },
            ToolDef(ANGLE, "Angle", ToolCategory.MEASURE, listOf(SlotKind.POINT, SlotKind.POINT, SlotKind.POINT), help = "Click a point, the vertex, then another point.") { d, p, _ -> d.measureAngle(p.points[0], p.points[1], p.points[2]) },
            ToolDef(LENGTH, "Length", ToolCategory.MEASURE, listOf(SlotKind.SEGMENT), help = "Click a segment to measure its length.") { d, p, _ -> d.measureLength(p.elements[0]) },
            ToolDef(RADIUS, "Radius", ToolCategory.MEASURE, listOf(SlotKind.CIRCLE), help = "Click a circle or arc to measure its radius.") { d, p, _ -> d.measureRadius(p.elements[0]) },
            ToolDef(COORD_X, "X coordinate", ToolCategory.MEASURE, listOf(SlotKind.POINT), help = "Click a point to read its x coordinate.") { d, p, _ -> d.measureX(p.points[0]) },
            ToolDef(COORD_Y, "Y coordinate", ToolCategory.MEASURE, listOf(SlotKind.POINT), help = "Click a point to read its y coordinate.") { d, p, _ -> d.measureY(p.points[0]) },
            ToolDef(ANGLE_LINES, "Angle (2 lines)", ToolCategory.MEASURE, listOf(SlotKind.LINE, SlotKind.LINE), help = "Click two lines to measure the angle between them.") { d, p, _ -> d.measureAngleLines(p.elements[0], p.elements[1]) },
            // 3D measurements (OP-4): the solid is picked in plan by its footprint hint, like any other
            // solid pick, and the number lands in the panel as a read-only scalar — usable downstream.
            ToolDef(VOLUME, "Volume", ToolCategory.MEASURE, listOf(SlotKind.SOLID), help = "Click a solid to measure its volume.") { d, p, _ -> d.measureSolidVolume(p.elements[0]) },
            ToolDef(EXTENT_X, "Extent (X)", ToolCategory.MEASURE, listOf(SlotKind.SOLID), help = "Click a solid to measure how far it reaches along X.") { d, p, _ -> d.measureSolidExtent(p.elements[0], Axis3.X) },
            ToolDef(EXTENT_Y, "Extent (Y)", ToolCategory.MEASURE, listOf(SlotKind.SOLID), help = "Click a solid to measure how far it reaches along Y.") { d, p, _ -> d.measureSolidExtent(p.elements[0], Axis3.Y) },
            ToolDef(EXTENT_Z, "Extent (Z)", ToolCategory.MEASURE, listOf(SlotKind.SOLID), help = "Click a solid to measure its height along Z.") { d, p, _ -> d.measureSolidExtent(p.elements[0], Axis3.Z) },
            // ----- Annotate: dimensions (OP-4) — the graphic shows a measurement, and drives nothing -----
            ToolDef(DIM_LINEAR, "Linear dimension", ToolCategory.ANNOTATE, listOf(SlotKind.EXISTING_POINT, SlotKind.EXISTING_POINT, SlotKind.SIDE), shortcut = 'M', help = "Click two points, then click where the dimension line should sit (drag it later, or type the offset).") { d, p, _ -> d.linearDimension(p.elements[0], p.elements[1], p.at, p.dofs) },
            ToolDef(DIM_RADIAL, "Radial dimension", ToolCategory.ANNOTATE, listOf(SlotKind.CENTRIC, SlotKind.SIDE), help = "Click a circle or arc, then click where the leader and its radius should sit.") { d, p, _ -> d.radialDimension(p.elements[0], p.at, p.dofs) },
            ToolDef(DIM_ANGULAR, "Angular dimension", ToolCategory.ANNOTATE, listOf(SlotKind.LINE, SlotKind.LINE, SlotKind.SIDE), help = "Click two lines, then click inside the angle you mean — that sector is what the dimension names.") { d, p, _ -> d.angularDimension(p.elements[0], p.elements[1], p.at, p.dofs) },
        )

    /**
     * The *built-in* tool of that id. Call [Document.toolDef] instead wherever a document is at hand: a
     * user-defined macro is a tool too (OP-6), and only the document knows its own macros.
     */
    fun byId(id: String): ToolDef? = all.firstOrNull { it.id == id }

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
