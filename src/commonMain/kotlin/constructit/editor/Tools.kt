package constructit.editor

import constructit.dsl.PointRef
import constructit.dsl.ScalarRef

/** What the next click of a tool must supply. */
enum class SlotKind { PLACE_POINT, POINT, CURVE, LINE, CIRCLE, SEGMENT, GEOMETRY }

enum class ToolCategory { POINTS, CURVES, CONSTRUCT, TRANSFORM, MEASURE }

/** Geometry picked so far for the active tool, in slot order and split by kind. */
class Picks(val points: List<PointRef>, val elements: List<Element>)

/**
 * A data-driven tool: a list of geometry [slots] to pick by clicking (plus an optional
 * [scalar] taken from the active parameter), then a [build] action. This is what lets the
 * whole 2D op algebra be surfaced without a growing when-statement.
 */
class ToolDef(
    val id: String,
    val label: String,
    val category: ToolCategory,
    val slots: List<SlotKind>,
    val scalar: Boolean = false,
    val build: (Document, Picks, ScalarRef?) -> Unit,
)

object Tools {
    const val SELECT = "select"

    // Points
    const val POINT = "point"
    const val MIDPOINT = "midpoint"
    const val INTERSECT = "intersect"
    const val PROJECT = "project"
    const val POINT_ON_CIRCLE = "ptoncircle"
    const val POINT_ON_LINE = "ptonline"

    // Curves
    const val LINE = "line"
    const val SEGMENT = "segment"
    const val RAY = "ray"
    const val CIRCLE = "circle"
    const val CIRCLE_R = "circleR"
    const val CIRCLE_3 = "circle3"
    const val ARC_3 = "arc3"

    // Construct
    const val PERP_BISECTOR = "perpbis"
    const val ANGLE_BISECTOR = "anglebis"
    const val PERPENDICULAR = "perp"
    const val PARALLEL = "parallel"
    const val TANGENT = "tangent"

    // Transform
    const val MIRROR = "mirror"
    const val ROTATE = "rotate"
    const val SCALE = "scale"

    // Measure
    const val DISTANCE = "mdist"
    const val ANGLE = "mangle"
    const val LENGTH = "mlen"
    const val RADIUS = "mradius"

    val all: List<ToolDef> = listOf(
        // ----- Points -----
        ToolDef(POINT, "Point", ToolCategory.POINTS, listOf(SlotKind.PLACE_POINT)) { _, _, _ -> },
        ToolDef(MIDPOINT, "Midpoint", ToolCategory.POINTS, listOf(SlotKind.POINT, SlotKind.POINT)) { d, p, _ -> d.midpoint(p.points[0], p.points[1]) },
        ToolDef(INTERSECT, "Intersect", ToolCategory.POINTS, listOf(SlotKind.CURVE, SlotKind.CURVE)) { d, p, _ -> d.intersect(p.elements[0], p.elements[1]) },
        ToolDef(PROJECT, "Project to line", ToolCategory.POINTS, listOf(SlotKind.POINT, SlotKind.LINE)) { d, p, _ -> d.projectToLine(p.points[0], p.elements[0]) },
        ToolDef(POINT_ON_CIRCLE, "Point on circle", ToolCategory.POINTS, listOf(SlotKind.CIRCLE), scalar = true) { d, p, s -> d.pointOnCircle(p.elements[0], s!!) },
        ToolDef(POINT_ON_LINE, "Point on line", ToolCategory.POINTS, listOf(SlotKind.LINE), scalar = true) { d, p, s -> d.pointOnLine(p.elements[0], s!!) },

        // ----- Curves -----
        ToolDef(LINE, "Line", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT)) { d, p, _ -> d.line(p.points[0], p.points[1]) },
        ToolDef(SEGMENT, "Segment", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT)) { d, p, _ -> d.segment(p.points[0], p.points[1]) },
        ToolDef(RAY, "Ray", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT)) { d, p, _ -> d.ray(p.points[0], p.points[1]) },
        ToolDef(CIRCLE, "Circle (centre, point)", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT)) { d, p, _ -> d.circle(p.points[0], p.points[1]) },
        ToolDef(CIRCLE_R, "Circle (centre, radius)", ToolCategory.CURVES, listOf(SlotKind.POINT), scalar = true) { d, p, s -> d.circleCR(p.points[0], s!!) },
        ToolDef(CIRCLE_3, "Circle (3 points)", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT, SlotKind.POINT)) { d, p, _ -> d.circle3(p.points[0], p.points[1], p.points[2]) },
        ToolDef(ARC_3, "Arc (3 points)", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT, SlotKind.POINT)) { d, p, _ -> d.arc3(p.points[0], p.points[1], p.points[2]) },

        // ----- Construct -----
        ToolDef(PERP_BISECTOR, "Perp. bisector", ToolCategory.CONSTRUCT, listOf(SlotKind.POINT, SlotKind.POINT)) { d, p, _ -> d.perpBisector(p.points[0], p.points[1]) },
        ToolDef(ANGLE_BISECTOR, "Angle bisector", ToolCategory.CONSTRUCT, listOf(SlotKind.POINT, SlotKind.POINT, SlotKind.POINT)) { d, p, _ -> d.angleBisector(p.points[0], p.points[1], p.points[2]) },
        ToolDef(PERPENDICULAR, "Perpendicular", ToolCategory.CONSTRUCT, listOf(SlotKind.LINE, SlotKind.POINT)) { d, p, _ -> d.perpendicularThrough(p.elements[0], p.points[0]) },
        ToolDef(PARALLEL, "Parallel", ToolCategory.CONSTRUCT, listOf(SlotKind.LINE, SlotKind.POINT)) { d, p, _ -> d.parallelThrough(p.elements[0], p.points[0]) },
        ToolDef(TANGENT, "Tangent from point", ToolCategory.CONSTRUCT, listOf(SlotKind.POINT, SlotKind.CIRCLE)) { d, p, _ -> d.tangentFromPoint(p.points[0], p.elements[0]) },

        // ----- Transform -----
        ToolDef(MIRROR, "Mirror", ToolCategory.TRANSFORM, listOf(SlotKind.GEOMETRY, SlotKind.LINE)) { d, p, _ -> d.mirror(p.elements[0], p.elements[1]) },
        ToolDef(ROTATE, "Rotate", ToolCategory.TRANSFORM, listOf(SlotKind.GEOMETRY, SlotKind.POINT), scalar = true) { d, p, s -> d.rotate(p.elements[0], p.points[0], s!!) },
        ToolDef(SCALE, "Scale", ToolCategory.TRANSFORM, listOf(SlotKind.GEOMETRY, SlotKind.POINT), scalar = true) { d, p, s -> d.scale(p.elements[0], p.points[0], s!!) },

        // ----- Measure -----
        ToolDef(DISTANCE, "Distance", ToolCategory.MEASURE, listOf(SlotKind.POINT, SlotKind.POINT)) { d, p, _ -> d.measureDistance(p.points[0], p.points[1]) },
        ToolDef(ANGLE, "Angle", ToolCategory.MEASURE, listOf(SlotKind.POINT, SlotKind.POINT, SlotKind.POINT)) { d, p, _ -> d.measureAngle(p.points[0], p.points[1], p.points[2]) },
        ToolDef(LENGTH, "Length", ToolCategory.MEASURE, listOf(SlotKind.SEGMENT)) { d, p, _ -> d.measureLength(p.elements[0]) },
        ToolDef(RADIUS, "Radius", ToolCategory.MEASURE, listOf(SlotKind.CIRCLE)) { d, p, _ -> d.measureRadius(p.elements[0]) },
    )

    fun byId(id: String): ToolDef? = all.firstOrNull { it.id == id }
}
