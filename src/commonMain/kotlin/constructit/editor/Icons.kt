package constructit.editor

/**
 * The palette's glyphs: inline SVG markup, one string per tool, drawn into a **24 × 24** box.
 *
 * Three rules, all of them structural rather than stylistic.
 *
 * - **Self-contained.** The markup is part of the build — no icon font, no sprite sheet, nothing fetched —
 *   so the palette works from a `file:` URL and an offline page, exactly as the rest of the shell does.
 * - **Inherited paint.** Every glyph strokes `currentColor` with the width, caps and joins the wrapping
 *   `<svg>` sets ([SvgIcon.wrap]), so an active button's white-on-blue state costs the icon nothing. A shape
 *   that is genuinely solid (a cursor, a point) says `fill="currentColor" stroke="none"` for itself.
 * - **It draws the operation, not the result.** At 24 pixels the difference between an arc tool and a fillet
 *   is not the arc — it is the two legs and the corner the arc replaced, which is why the fillet and chamfer
 *   glyphs keep that corner as a ghost. The same reason puts a dashed axis in *Mirror* and a ghost copy in
 *   *Translate*: what the button promises is the transformation, and the transformation is the pair.
 *
 * Coverage is deliberately partial — a tool with no legible picture keeps its text row (see the palette in
 * `Main.kt`), because an unreadable glyph is worse than the label it replaced.
 */
object Icons {
    // ---- little builders, so the markup below reads as geometry rather than as punctuation ----

    private fun dot(
        x: Double,
        y: Double,
        r: Double = 1.9,
    ) = """<circle cx="$x" cy="$y" r="$r" fill="currentColor" stroke="none"/>"""

    private fun ring(
        x: Double,
        y: Double,
        r: Double,
    ) = """<circle cx="$x" cy="$y" r="$r"/>"""

    private fun oval(
        x: Double,
        y: Double,
        rx: Double,
        ry: Double,
    ) = """<ellipse cx="$x" cy="$y" rx="$rx" ry="$ry"/>"""

    private fun path(
        d: String,
        extra: String = "",
    ) = """<path d="$d"${if (extra.isEmpty()) "" else " $extra"}/>"""

    /** A construction line the glyph shows only as context — the corner a fillet cuts, an array's circle. */
    private fun ghost(d: String) = path(d, """opacity=".4"""")

    private fun dashed(d: String) = path(d, """stroke-dasharray="2.6 2" opacity=".65"""")

    private fun rect(
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        extra: String = "",
    ) = """<rect x="$x" y="$y" width="$w" height="$h"${if (extra.isEmpty()) "" else " $extra"}/>"""

    private fun letter(
        x: Double,
        y: Double,
        s: String,
    ) = """<text x="$x" y="$y" font-size="8" font-family="sans-serif" fill="currentColor" stroke="none">$s</text>"""

    // ---- select & points ----

    val SELECT = path("M6 3 L6 19 L10.2 15.2 L12.8 20.8 L15.2 19.6 L12.6 14.2 L17.5 13.8 Z", """fill="currentColor" stroke-width="1.2"""")

    val POINT = dot(12.0, 12.0, 3.2) + ghost("M12 3 L12 6.5") + ghost("M12 17.5 L12 21") + ghost("M3 12 L6.5 12") + ghost("M17.5 12 L21 12")

    val MIDPOINT = path("M4 18 L20 6") + dot(4.0, 18.0, 1.7) + dot(20.0, 6.0, 1.7) + dot(12.0, 12.0, 3.0)

    val INTERSECT = path("M3 6 L21 18") + path("M3 18 L21 6") + dot(12.0, 12.0, 2.8)

    val PROJECT = path("M3 18 L21 18") + dashed("M9 6.5 L9 18") + path("M9 15 L12 15 L12 18") + dot(9.0, 6.0, 2.2) + dot(9.0, 18.0, 2.2)

    val CENTRE = ring(12.0, 12.0, 7.5) + dot(12.0, 12.0, 2.4) + ghost("M12 7 L12 9") + ghost("M12 15 L12 17") + ghost("M7 12 L9 12") + ghost("M15 12 L17 12")

    val KEY_POINTS = path("M5 19 L12 5 L20 17 Z") + dot(5.0, 19.0, 2.2) + dot(12.0, 5.0, 2.2) + dot(20.0, 17.0, 2.2)

    val POINT_ON_LINE = path("M3 17.5 L21 6.5") + dot(12.0, 12.0, 2.6)

    val POINT_ON_CIRCLE = ring(12.0, 12.0, 7.5) + dot(17.3, 6.7, 2.6)

    /** A point riding an ellipse: the curve, and the mark at its parametric angle (OP-24). */
    val POINT_ON_ELLIPSE = oval(12.0, 12.0, 9.0, 5.5) + dot(18.4, 8.1, 2.6)

    val POINT_XY = path("M4 20 L20 20") + path("M4 20 L4 4") + dashed("M15 9 L15 20") + dashed("M15 9 L4 9") + dot(15.0, 9.0, 2.4)

    val JOIN = ring(16.5, 12.0, 4.2) + dot(16.5, 12.0, 2.2) + dot(4.5, 12.0, 2.2) + path("M6.8 12 L11 12") + path("M9.4 10.2 L11.4 12 L9.4 13.8")

    /** The same figure as [JOIN] with the arrow turned round: the point *leaving* the weld it was in. */
    val UNLINK = ring(16.5, 12.0, 4.2) + dot(16.5, 12.0, 2.2) + dot(4.5, 12.0, 2.2) + path("M7.2 12 L11.4 12") + path("M9.2 10.2 L7.2 12 L9.2 13.8")

    /** A height point (OP-25): a base point on the ground, and the point standing over it. */
    val HEIGHT_POINT =
        path("M2 19 L10 22 L22 18 L14 15 Z", """opacity=".4"""") + dot(12.0, 18.5, 2.2) + dashed("M12 18.5 L12 5") +
            dot(12.0, 5.0, 2.6) + path("M9.6 7.4 L12 5 L14.4 7.4")

    // ---- curves ----

    val SEGMENT = path("M5 18 L19 6") + dot(5.0, 18.0, 2.0) + dot(19.0, 6.0, 2.0)

    val LINE = path("M2.5 19.5 L21.5 4.5") + dot(8.0, 15.1, 2.0) + dot(16.0, 8.8, 2.0)

    val RAY = path("M5 19 L20.5 5.5") + path("M16.2 6.9 L20.5 5.5 L18.5 9.5") + dot(5.0, 19.0, 2.2)

    val CIRCLE = ring(12.0, 12.0, 7.5) + ghost("M12 12 L19.5 12") + dot(12.0, 12.0, 2.0) + dot(19.5, 12.0, 2.0)

    val CIRCLE_R = ring(12.0, 12.0, 7.5) + path("M12 12 L19.5 12") + dot(12.0, 12.0, 2.0) + letter(13.5, 9.8, "r")

    val CIRCLE_3 = ring(12.0, 12.0, 7.5) + dot(12.0, 4.5, 2.0) + dot(5.5, 15.75, 2.0) + dot(18.5, 15.75, 2.0)

    val CIRCLE_LLL = ring(12.0, 12.5, 5.5) + path("M2.5 18 L21.5 18") + path("M12 1.5 L2.5 18") + path("M12 1.5 L21.5 18")

    val ARC_3 = path("M4 17 A 8.5 8.5 0 0 1 20 17") + dot(4.0, 17.0, 2.0) + dot(12.0, 11.4, 2.0) + dot(20.0, 17.0, 2.0)

    val ARC_CS = path("M19 18 A 12 12 0 0 0 7 6") + ghost("M7 18 L19 18") + ghost("M7 18 L7 6") + dot(7.0, 18.0, 2.0) + dot(19.0, 18.0, 2.0) + dot(7.0, 6.0, 2.0)

    /** The ellipse's operation: the two axes that define it, and the curve they make (OP-24). */
    val ELLIPSE =
        oval(12.0, 12.0, 9.0, 5.5) + ghost("M12 12 L21 12") + ghost("M12 12 L12 6.5") +
            dot(12.0, 12.0, 2.0) + dot(21.0, 12.0, 2.0) + dot(12.0, 6.5, 2.0)

    val ELLIPTIC_ARC =
        path("M21 12 A 9 5.5 0 0 0 12 6.5") + ghost("M12 12 L21 12") + ghost("M12 12 L12 6.5") +
            dot(12.0, 12.0, 2.0) + dot(21.0, 12.0, 2.0) + dot(12.0, 6.5, 2.0)

    val BEZIER =
        ghost("M4 19 L4 7") + ghost("M20 5 L20 17") + path("M4 19 C 4 7, 20 17, 20 5") +
            rect(2.7, 5.7, 2.6, 2.6) + rect(18.7, 15.7, 2.6, 2.6) + dot(4.0, 19.0, 2.0) + dot(20.0, 5.0, 2.0)

    val RECTANGLE = rect(4.0, 6.0, 16.0, 12.0) + dot(4.0, 6.0, 2.0) + dot(20.0, 18.0, 2.0)

    val ROUNDED_RECT = rect(4.0, 6.0, 16.0, 12.0, """rx="4"""") + dot(4.0, 6.0, 1.8) + dot(20.0, 18.0, 1.8)

    val POLYGON = path("M12 3.5 L19.4 7.8 L19.4 16.2 L12 20.5 L4.6 16.2 L4.6 7.8 Z")

    val ORTHO_PATH = path("M3.5 19 L3.5 12 L11 12 L11 6 L20.5 6") + dot(3.5, 19.0, 1.8) + dot(11.0, 12.0, 1.8) + dot(20.5, 6.0, 1.8)

    val WALL = path("M3 21 L3 8 L21 8") + path("M8 21 L8 13 L21 13") + path("M3 21 L8 21") + path("M21 8 L21 13")

    val OPENING =
        path("M2.5 8 L9.5 8") + path("M14.5 8 L21.5 8") + path("M2.5 15 L9.5 15") + path("M14.5 15 L21.5 15") +
            path("M9.5 8 L9.5 15") + path("M14.5 8 L14.5 15")

    val BREAK_LEG = path("M3 12 L9.5 12") + path("M14.5 12 L21 12") + dashed("M12 5.5 L12 18.5") + dot(9.5, 12.0, 2.2) + dot(14.5, 12.0, 2.2)

    val CONCENTRIC = ring(12.0, 12.0, 8.5) + ring(12.0, 12.0, 4.5) + dot(12.0, 12.0, 1.8)

    // ---- construct ----

    val PERP_BISECTOR =
        path("M4 17 L20 7") + path("M7.2 4.4 L16.8 19.6") + dot(4.0, 17.0, 2.0) + dot(20.0, 7.0, 2.0) + dot(12.0, 12.0, 2.4)

    val PERPENDICULAR = path("M3 17 L21 17") + path("M12 4 L12 20") + path("M12 14 L15 14 L15 17") + dot(12.0, 7.5, 2.2)

    val PARALLEL = path("M3 19 L21 13") + path("M3 11 L21 5") + dot(10.0, 8.7, 2.4)

    val PARALLEL_AT =
        path("M3 18 L21 12") + path("M3 11 L21 5") + path("M12 8 L12 15") +
            path("M10.4 9.8 L12 8 L13.6 9.8") + path("M10.4 13.2 L12 15 L13.6 13.2")

    val TANGENT = ring(15.0, 13.0, 6.0) + path("M4 19 L20 19") + path("M4 19 L11 8.3") + dot(4.0, 19.0, 2.2)

    val TANGENT_AT = ring(11.0, 14.0, 6.5) + path("M6.5 3.5 L21 12") + dot(15.6, 9.4, 2.4)

    val FILLET = ghost("M11 19 L19 19 L19 11") + path("M3 19 L11 19 A 8 8 0 0 0 19 11 L19 3")

    val CHAMFER = ghost("M10 19 L19 19 L19 10") + path("M3 19 L10 19 L19 10 L19 3")

    val OUTER_TANGENTS =
        ring(7.0, 13.0, 5.0) + ring(18.0, 10.0, 3.5) + path("M4.5 8.8 L21.5 6.4") + path("M6.9 18.4 L21.5 12.4")

    val INNER_TANGENTS =
        ring(7.0, 13.0, 5.0) + ring(18.0, 10.0, 3.5) + path("M7.6 7.4 L21 16.2") + path("M9.9 18.4 L17 4.1")

    // ---- transform ----

    val MIRROR =
        dashed("M12 2.5 L12 21.5") + path("M9 6 L3 12 L9 18 Z") + path("M15 6 L21 12 L15 18 Z", """opacity=".55"""")

    // the half turn drawn as what it is: one shape and its image *through the dot*, so the pair reads as a
    // reflection in a point rather than in the line MIRROR's icon draws
    val POINT_REFLECT =
        path("M4 4 L10 4 L4 10 Z") + path("M20 20 L14 20 L20 14 Z", """opacity=".55"""") + dot(12.0, 12.0, 2.4)

    val ROTATE =
        dot(12.0, 20.0, 2.2) + ghost("M12 20 L4.2 13.4") + ghost("M12 20 L19.8 13.4") +
            path("M4.2 13.4 A 10.2 10.2 0 0 1 19.8 13.4") + path("M18.8 9.5 L19.8 13.4 L16.2 11.8")

    val SCALE =
        rect(4.0, 14.0, 6.0, 6.0) + rect(4.0, 4.0, 16.0, 16.0, """opacity=".55"""") +
            path("M10.5 13.5 L18 6") + path("M14.6 7.2 L18 6 L16.8 9.4")

    val TRANSLATE_V =
        rect(3.5, 13.0, 7.0, 7.0) + rect(13.5, 4.0, 7.0, 7.0, """opacity=".5"""") +
            path("M10 13 L15 8") + path("M11.6 9.2 L15 8 L13.8 11.4")

    val ARRAY_LINEAR = rect(3.0, 9.0, 5.0, 6.0) + rect(9.5, 9.0, 5.0, 6.0) + rect(16.0, 9.0, 5.0, 6.0)

    val ARRAY_CIRCULAR =
        ghost("M12 4 A 8 8 0 1 1 11.9 4") + rect(10.0, 2.0, 4.0, 4.0) + rect(2.0, 10.0, 4.0, 4.0) +
            rect(18.0, 10.0, 4.0, 4.0) + rect(10.0, 18.0, 4.0, 4.0)

    val PATTERN_CIRCULAR =
        ghost("M12 4 A 8 8 0 1 1 11.9 4") + dot(12.0, 4.0, 2.3) + dot(18.9, 8.0, 2.3) + dot(18.9, 16.0, 2.3) +
            dot(12.0, 20.0, 2.3) + dot(5.1, 16.0, 2.3) + dot(5.1, 8.0, 2.3) + dot(12.0, 12.0, 1.5)

    val PATTERN_LINEAR =
        ghost("M4 12 L20 12") + dot(4.0, 12.0, 2.6) + dot(9.3, 12.0, 2.3) + dot(14.7, 12.0, 2.3) + dot(20.0, 12.0, 2.3)

    // ---- result layer & solids ----

    val OUTLINE = path("M4 15 L4 7 L11 4 L20 8 L18 18 L9 19 Z", """stroke-width="2.4"""")

    val THICKEN =
        ghost("M3 16 C 8 6, 16 6, 21 16") + path("M3 13 C 8 3, 16 3, 21 13") + path("M3 19 C 8 9, 16 9, 21 19") +
            path("M3 13 L3 19") + path("M21 13 L21 19")

    val EXTRUDE =
        path("M4 16 L12 20 L20 16 L12 12 Z", """opacity=".5"""") + path("M4 9 L12 13 L20 9 L12 5 Z") +
            path("M4 16 L4 9") + path("M12 20 L12 13") + path("M20 16 L20 9")

    val REVOLVE =
        dashed("M6 2.5 L6 21.5") + """<ellipse cx="6" cy="17" rx="12" ry="3.4" opacity=".45"/>""" + rect(10.0, 8.0, 8.0, 9.0)

    /** The ball itself: an outline and the equator that turns a flat disc into a sphere at 24 pixels. */
    private val BALL = ring(12.0, 12.0, 8.0) + """<ellipse cx="12" cy="12" rx="8" ry="3" opacity=".4"/>"""

    /**
     * The two ball rows are [CIRCLE_R] and [CIRCLE] with that equator drawn through them, because what
     * distinguishes them from each other is exactly what distinguishes the two circle rows: a typed radius
     * against a second click.
     */
    val SPHERE_R = BALL + path("M12 12 L20 12") + dot(12.0, 12.0, 2.0) + letter(13.5, 9.8, "r")

    val SPHERE = BALL + ghost("M12 12 L20 12") + dot(12.0, 12.0, 2.0) + dot(20.0, 12.0, 2.0)

    /** Extrude to a point: a base, and every corner of it rising to one dot — the pyramid, as a gesture. */
    val EXTRUDE_TO_POINT =
        path("M4 18 L12 21.5 L20 18 L12 14.5 Z", """opacity=".5"""") + path("M4 18 L12 3") + path("M20 18 L12 3") +
            path("M12 21.5 L12 3", """opacity=".3"""") + dot(12.0, 3.0)

    /** Loft: two sections of different size, and the rails that run between them. */
    val LOFT =
        path("M3 19 L12 22 L21 19 L12 16 Z", """opacity=".5"""") + path("M7.5 5 L12 6.6 L16.5 5 L12 3.4 Z") +
            path("M3 19 L7.5 5") + path("M21 19 L16.5 5") + path("M12 22 L12 6.6", """opacity=".3"""")

    val SECTION = rect(5.0, 6.0, 14.0, 13.0) + dashed("M2 12 L22 12") + path("M5 12 L19 12")

    val CUT =
        rect(4.0, 8.0, 16.0, 12.0) + path("M9 8 L9 14 L15 14 L15 8") + path("M12 2 L12 7") + path("M10.2 5.2 L12 7 L13.8 5.2")

    val EXTRUDE_ON_FACE =
        path("M3 17 L12 21 L21 17 L12 13 Z") + rect(8.5, 4.5, 7.0, 7.0) + ghost("M8.5 11.5 L8.5 15.5") + ghost("M15.5 11.5 L15.5 15.5")

    val UNION =
        ring(9.0, 12.0, 6.0) + ring(15.0, 12.0, 6.0) +
            path("M9 6 a6 6 0 1 0 0 12 a6 6 0 1 0 0 -12", """fill="currentColor" fill-opacity=".18" stroke="none"""") +
            path("M15 6 a6 6 0 1 0 0 12 a6 6 0 1 0 0 -12", """fill="currentColor" fill-opacity=".18" stroke="none"""")

    val SUBTRACT =
        path("M9 6 a6 6 0 1 0 0 12 a6 6 0 1 0 0 -12", """fill="currentColor" fill-opacity=".18"""") +
            path("M15 6 a6 6 0 1 0 0 12 a6 6 0 1 0 0 -12", """stroke-dasharray="2.6 2"""")

    val INTERSECT_SOLIDS =
        ghost("M9 6 a6 6 0 1 0 0 12 a6 6 0 1 0 0 -12") + ghost("M15 6 a6 6 0 1 0 0 12 a6 6 0 1 0 0 -12") +
            path("M12 6.8 A 6 6 0 0 1 12 17.2 A 6 6 0 0 1 12 6.8 Z", """fill="currentColor" fill-opacity=".28"""")

    // ---- measure & annotate ----

    val DISTANCE =
        path("M5 17 L19 7") + path("M5 17 L9.4 16.4") + path("M5 17 L5.6 12.6") +
            path("M19 7 L14.6 7.6") + path("M19 7 L18.4 11.4") + dot(5.0, 17.0, 2.0) + dot(19.0, 7.0, 2.0)

    val DIM_LINEAR =
        ghost("M5 5 L5 19") + ghost("M19 5 L19 19") + path("M5 15 L19 15") +
            path("M8 12.6 L5 15 L8 17.4") + path("M16 12.6 L19 15 L16 17.4")

    val DIM_RADIAL =
        ring(11.0, 14.0, 7.0) + path("M11 14 L21 4") + path("M15.9 9.1 L18.6 8.9 L18.4 11.6") + letter(2.5, 8.5, "R")

    val DIM_ANGULAR =
        path("M4 20 L21 20") + path("M4 20 L16.2 4.7") + path("M14 20 A 10 10 0 0 0 10.2 12.2") + dot(4.0, 20.0, 2.0)

    /** Wrap a glyph in the one `<svg>` that gives every icon its paint, weight and size. */
    fun wrap(
        glyph: String,
        px: Int = 22,
    ): String =
        """<svg viewBox="0 0 24 24" width="$px" height="$px" fill="none" stroke="currentColor" """ +
            """stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">$glyph</svg>"""
}
