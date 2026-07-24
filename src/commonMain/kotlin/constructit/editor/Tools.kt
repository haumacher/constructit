package constructit.editor

import constructit.dsl.PointRef
import constructit.dsl.ScalarRef
import constructit.geom.Vec2

/** What the next click of a tool must supply. SIDE just captures a click position (creates nothing). */
enum class SlotKind { PLACE_POINT, POINT, CURVE, LINE, CIRCLE, SEGMENT, GEOMETRY, ON_CIRCLE_POINT, SIDE }

enum class ToolCategory { POINTS, CURVES, CONSTRUCT, TRANSFORM, MEASURE }

/** Geometry picked so far for the active tool (split by kind), plus [at] = the last click's world position. */
class Picks(val points: List<PointRef>, val elements: List<Element>, val at: Vec2)

/**
 * A data-driven tool: geometry [slots] to pick by clicking (plus an optional [scalar] from the
 * active parameter), a [help] line for the status bar, then a [build] action.
 */
class ToolDef(
    val id: String,
    val label: String,
    val category: ToolCategory,
    val slots: List<SlotKind>,
    val scalar: Boolean = false,
    val help: String = "",
    val build: (Document, Picks, ScalarRef?) -> Unit,
)

object Tools {
    const val SELECT = "select"
    const val SELECT_HELP = "Drag a point to reshape the construction; drag empty space to pan; wheel to zoom."

    // Points
    const val POINT = "point"
    const val MIDPOINT = "midpoint"
    const val INTERSECT = "intersect"
    const val PROJECT = "project"
    const val POINT_ON_CIRCLE = "ptoncircle"
    const val POINT_ON_LINE = "ptonline"
    const val POINT_AT_DIST = "ptatdist"
    const val KEY_POINTS = "keypoints"

    // Curves
    const val LINE = "line"
    const val SEGMENT = "segment"
    const val RAY = "ray"
    const val CIRCLE = "circle"
    const val CIRCLE_R = "circleR"
    const val CIRCLE_3 = "circle3"
    const val ARC_3 = "arc3"
    const val ARC_CS = "arccs"

    // Construct
    const val PERP_BISECTOR = "perpbis"
    const val ANGLE_BISECTOR = "anglebis"
    const val PERPENDICULAR = "perp"
    const val PARALLEL = "parallel"
    const val PARALLEL_AT = "parallelat"
    const val TANGENT = "tangent"
    const val TANGENT_AT = "tangentat"

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
        ToolDef(POINT, "Point", ToolCategory.POINTS, listOf(SlotKind.PLACE_POINT), help = "Click empty space to place a free point.") { _, _, _ -> },
        ToolDef(MIDPOINT, "Midpoint", ToolCategory.POINTS, listOf(SlotKind.POINT, SlotKind.POINT), help = "Click two points to place their midpoint.") { d, p, _ -> d.midpoint(p.points[0], p.points[1]) },
        ToolDef(INTERSECT, "Intersect", ToolCategory.POINTS, listOf(SlotKind.CURVE, SlotKind.CURVE), help = "Click two curves to add their intersection point(s).") { d, p, _ -> d.intersect(p.elements[0], p.elements[1]) },
        ToolDef(PROJECT, "Project to line", ToolCategory.POINTS, listOf(SlotKind.POINT, SlotKind.LINE), help = "Click a point, then a line, for the perpendicular foot.") { d, p, _ -> d.projectToLine(p.points[0], p.elements[0]) },
        ToolDef(POINT_ON_CIRCLE, "Point on circle", ToolCategory.POINTS, listOf(SlotKind.CIRCLE), help = "Click a circle to add a point on it; drag it around the circle in Select mode.") { d, p, _ -> d.pointOnCircle(p.elements[0], p.at) },
        ToolDef(POINT_ON_LINE, "Point on line", ToolCategory.POINTS, listOf(SlotKind.LINE), help = "Click a line to add a point on it; drag it along the line in Select mode.") { d, p, _ -> d.pointOnLine(p.elements[0], p.at) },
        ToolDef(POINT_AT_DIST, "Point at distance", ToolCategory.POINTS, listOf(SlotKind.POINT, SlotKind.LINE), scalar = true, help = "Select a distance, click the reference point, then click the line on the side you want.") { d, p, s -> d.pointAlongLine(p.elements[0], p.points[0], s!!, p.at) },
        ToolDef(KEY_POINTS, "Key points", ToolCategory.POINTS, listOf(SlotKind.CURVE), help = "Click a curve to add its defining points (endpoints, centre) — even on mirrored/derived geometry.") { d, p, _ -> d.extractPoints(p.elements[0]) },

        // ----- Curves -----
        ToolDef(LINE, "Line", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT), help = "Click two points to draw an infinite line.") { d, p, _ -> d.line(p.points[0], p.points[1]) },
        ToolDef(SEGMENT, "Segment", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT), help = "Click two points to draw a segment.") { d, p, _ -> d.segment(p.points[0], p.points[1]) },
        ToolDef(RAY, "Ray", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT), help = "Click the origin, then a second point, to draw a ray.") { d, p, _ -> d.ray(p.points[0], p.points[1]) },
        ToolDef(CIRCLE, "Circle (centre, point)", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT), help = "Click the centre, then a point on the circle.") { d, p, _ -> d.circle(p.points[0], p.points[1]) },
        ToolDef(CIRCLE_R, "Circle (centre, radius)", ToolCategory.CURVES, listOf(SlotKind.POINT), scalar = true, help = "Select a radius parameter, then click the centre.") { d, p, s -> d.circleCR(p.points[0], s!!) },
        ToolDef(CIRCLE_3, "Circle (3 points)", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT, SlotKind.POINT), help = "Click three points the circle passes through.") { d, p, _ -> d.circle3(p.points[0], p.points[1], p.points[2]) },
        ToolDef(ARC_3, "Arc (3 points)", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT, SlotKind.POINT), help = "Click start, a point on the arc, then the end.") { d, p, _ -> d.arc3(p.points[0], p.points[1], p.points[2]) },
        ToolDef(ARC_CS, "Arc (centre, ends)", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT, SlotKind.POINT), help = "Click the centre, the start point, then the end (sweeps counter-clockwise).") { d, p, _ -> d.arcCenterStartEnd(p.points[0], p.points[1], p.points[2]) },

        // ----- Construct -----
        ToolDef(PERP_BISECTOR, "Perp. bisector", ToolCategory.CONSTRUCT, listOf(SlotKind.POINT, SlotKind.POINT), help = "Click two points for their perpendicular bisector.") { d, p, _ -> d.perpBisector(p.points[0], p.points[1]) },
        ToolDef(ANGLE_BISECTOR, "Angle bisector", ToolCategory.CONSTRUCT, listOf(SlotKind.POINT, SlotKind.POINT, SlotKind.POINT), help = "Click a point, the vertex, then another point.") { d, p, _ -> d.angleBisector(p.points[0], p.points[1], p.points[2]) },
        ToolDef(PERPENDICULAR, "Perpendicular", ToolCategory.CONSTRUCT, listOf(SlotKind.LINE, SlotKind.POINT), help = "Click a line, then a point, for the perpendicular through it.") { d, p, _ -> d.perpendicularThrough(p.elements[0], p.points[0]) },
        ToolDef(PARALLEL, "Parallel", ToolCategory.CONSTRUCT, listOf(SlotKind.LINE, SlotKind.POINT), help = "Click a line, then a point, for the parallel through it.") { d, p, _ -> d.parallelThrough(p.elements[0], p.points[0]) },
        ToolDef(PARALLEL_AT, "Parallel at distance", ToolCategory.CONSTRUCT, listOf(SlotKind.LINE, SlotKind.SIDE), scalar = true, help = "Select a distance, click the base line, then click the side you want the parallel on.") { d, p, s -> d.parallelAtDistance(p.elements[0], s!!, p.at) },
        ToolDef(TANGENT, "Tangent from point", ToolCategory.CONSTRUCT, listOf(SlotKind.POINT, SlotKind.CIRCLE), help = "Click an external point, then a circle.") { d, p, _ -> d.tangentFromPoint(p.points[0], p.elements[0]) },
        ToolDef(TANGENT_AT, "Tangent at point", ToolCategory.CONSTRUCT, listOf(SlotKind.ON_CIRCLE_POINT), help = "Click a point that lies on a circle for the tangent there (use Point on circle).") { d, p, _ -> d.tangentAtPointOnCircle(p.elements[0]) },

        // ----- Transform -----
        ToolDef(MIRROR, "Mirror", ToolCategory.TRANSFORM, listOf(SlotKind.GEOMETRY, SlotKind.LINE), help = "Click geometry, then a line to mirror it across.") { d, p, _ -> d.mirror(p.elements[0], p.elements[1]) },
        ToolDef(ROTATE, "Rotate", ToolCategory.TRANSFORM, listOf(SlotKind.GEOMETRY, SlotKind.POINT), scalar = true, help = "Select an angle parameter, click geometry, then the centre.") { d, p, s -> d.rotate(p.elements[0], p.points[0], s!!) },
        ToolDef(SCALE, "Scale", ToolCategory.TRANSFORM, listOf(SlotKind.GEOMETRY, SlotKind.POINT), scalar = true, help = "Select a factor parameter, click geometry, then the centre.") { d, p, s -> d.scale(p.elements[0], p.points[0], s!!) },

        // ----- Measure -----
        ToolDef(DISTANCE, "Distance", ToolCategory.MEASURE, listOf(SlotKind.POINT, SlotKind.POINT), help = "Click two points to measure their distance.") { d, p, _ -> d.measureDistance(p.points[0], p.points[1]) },
        ToolDef(ANGLE, "Angle", ToolCategory.MEASURE, listOf(SlotKind.POINT, SlotKind.POINT, SlotKind.POINT), help = "Click a point, the vertex, then another point.") { d, p, _ -> d.measureAngle(p.points[0], p.points[1], p.points[2]) },
        ToolDef(LENGTH, "Length", ToolCategory.MEASURE, listOf(SlotKind.SEGMENT), help = "Click a segment to measure its length.") { d, p, _ -> d.measureLength(p.elements[0]) },
        ToolDef(RADIUS, "Radius", ToolCategory.MEASURE, listOf(SlotKind.CIRCLE), help = "Click a circle to measure its radius.") { d, p, _ -> d.measureRadius(p.elements[0]) },
    )

    fun byId(id: String): ToolDef? = all.firstOrNull { it.id == id }
}
