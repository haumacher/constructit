package constructit.editor

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The palette's glyphs: inline SVG markup, one string per tool, drawn into a **24 × 24** box.
 *
 * Four rules, all of them structural rather than stylistic.
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
 * - **Families before pictures.** Where several tools are variants of one operation the glyph is *composed*:
 *   the base is drawn once and each variant adds one modifier mark — handedness by mirroring the base with the
 *   arrow at the winding end ([coil]), rotating against translational by [MOD_ROT] / [MOD_FLAT] in the free
 *   corner, cut against split by ghosting a side against keeping both with a gap, and a "…by point" variant by
 *   one extra [dot] on the surface. Two variants that differ in anything else are two glyphs to learn instead
 *   of one glyph and a mark.
 *
 * Every tool in the table carries one (GitHub issue #22, reversing the earlier partial-coverage cut); the
 * palette in `Main.kt` still renders a text row for anything that has none, which is now only a user-defined
 * macro, whose picture there is no way to know.
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
        extra: String = "",
    ) = """<circle cx="$x" cy="$y" r="$r"${if (extra.isEmpty()) "" else " $extra"}/>"""

    private fun oval(
        x: Double,
        y: Double,
        rx: Double,
        ry: Double,
        extra: String = "",
    ) = """<ellipse cx="$x" cy="$y" rx="$rx" ry="$ry"${if (extra.isEmpty()) "" else " $extra"}/>"""

    private fun path(
        d: String,
        extra: String = "",
    ) = """<path d="$d"${if (extra.isEmpty()) "" else " $extra"}/>"""

    /** A construction line the glyph shows only as context — the corner a fillet cuts, an array's circle. */
    private fun ghost(d: String) = path(d, """opacity=".4"""")

    private fun dashed(d: String) = path(d, """stroke-dasharray="2.6 2" opacity=".65"""")

    /**
     * A ghost that has to *carry* the reading rather than sit behind it — a whole run, a whole face. [ghost]'s
     * .4 disappears at 22 pixels when the stroke is long and thin; .55 stays secondary without vanishing.
     */
    private fun soft(d: String) = path(d, """opacity=".55"""")

    /** A sphere **locus**: dashed, because it is construction geometry, wherever one is consumed or made. */
    private fun locusRing(
        x: Double,
        y: Double,
        r: Double,
    ) = ring(x, y, r, """stroke-dasharray="2.6 2" opacity=".6"""")

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

    /** The `opacity` a [ghost] uses, for the helpers that take an `extra` rather than a `d`. */
    private const val GHOST = """opacity=".4""""

    private fun dashDot(d: String) = path(d, """stroke-dasharray="5 2 1.4 2"""")

    /** Two decimals at most: the arithmetic below is trigonometry, the markup should still read as geometry. */
    private fun n(v: Double): String {
        val r = (v * 100).roundToInt() / 100.0
        return if (r == r.toInt().toDouble()) r.toInt().toString() else r.toString()
    }

    /**
     * An arrowhead at (`x`, `y`) pointing along (`dx`, `dy`) — a chevron, not a triangle, so it inherits the
     * wrapper's stroke like every other mark. Computed rather than typed because an arrow that is 3° off its
     * curve's tangent is exactly the kind of smudge a 24-pixel glyph cannot afford.
     */
    private fun arrow(
        x: Double,
        y: Double,
        dx: Double,
        dy: Double,
        len: Double = 3.0,
        half: Double = 1.9,
        extra: String = "",
    ): String {
        val m = sqrt(dx * dx + dy * dy)
        val ux = dx / m
        val uy = dy / m
        val bx = x - ux * len
        val by = y - uy * len
        return path(
            "M${n(bx + uy * half)} ${n(by - ux * half)} L${n(x)} ${n(y)} L${n(bx - uy * half)} ${n(by + ux * half)}",
            extra,
        )
    }

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

    // the typed-semi-axis variant, told from [ELLIPSE] the way [CIRCLE_R] is told from [CIRCLE]:
    // the typed axis is a bold stroke with its letter, not a clicked dot
    val ELLIPSE_AB =
        oval(12.0, 12.0, 9.0, 5.5) + ghost("M12 12 L21 12") + path("M12 12 L12 6.5") +
            dot(12.0, 12.0, 2.0) + dot(21.0, 12.0, 2.0) + letter(14.2, 9.2, "b")

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

    // ---- shared bases, so a family is one drawing plus a mark ----

    /**
     * A box in space, as one `d` with six subpaths, so the same drawing can be the bold subject (*Hollow*) or
     * the ghosted context (*Sketch on face*) without being typed twice.
     */
    private const val BOX = "M3 9 L12 4 L21 9 L12 14 Z M3 9 L3 15 M21 9 L21 15 M12 14 L12 20.5 M3 15 L12 20.5 M21 15 L12 20.5"

    /** The same box in cabinet projection, where **X is horizontal, Y the depth diagonal and Z vertical** — the three axes the extent measures name. */
    private const val BOX_AXES = "M4 8 L4 18 L15 18 L15 8 Z M4 8 L8 4 L19 4 L15 8 M15 18 L19 14 L19 4"

    /** A small box, for the two *Place* rows where the box is the thing carried rather than the subject. */
    private const val BOX_SMALL = "M6 6.5 L12 3 L18 6.5 L12 10 Z M6 6.5 L6 10 M18 6.5 L18 10 M12 10 L12 13.5 M6 10 L12 13.5 M18 10 L12 13.5"

    /**
     * The coil, right-hand by construction: every half-turn is the same ellipse half, so the front strands rise
     * to the **right** — which is what handedness *is* in a drawing. `left` mirrors it about x = 12, which
     * flips every sweep flag and nothing else, so the two hands stay one drawing.
     */
    private fun coil(left: Boolean): String {
        val ys = listOf(20.6, 17.7, 14.8, 11.9, 9.0, 6.1)
        val near = if (left) 18.0 else 6.0
        val far = if (left) 6.0 else 18.0
        val sweep = if (left) 1 else 0
        val sb = StringBuilder("M${n(near)} ${n(ys[0])}")
        for (i in 1 until ys.size) sb.append(" A 6 2.2 0 0 $sweep ${n(if (i % 2 == 1) far else near)} ${n(ys[i])}")
        return sb.toString()
    }

    /** Where [coil] starts — the end the *centre and start point* variants mark with a dot. */
    private fun coilStart(left: Boolean) = if (left) 18.0 else 6.0

    /** …and it ends on the opposite side, which is where the arrow goes: the winding end names the hand. */
    private fun coilGlyph(left: Boolean) = path(coil(left)) + arrow(coilStart(!left), 3.4, 0.0, -1.0, 2.8, 1.8)

    /** The rotating modifier: a turn, in the corner the subject leaves free. */
    private val MOD_ROT = path("M20.2 22.2 A 2.8 2.8 0 1 0 17.4 19.4") + arrow(17.4, 19.6, 0.0, 1.0, 2.2, 1.4)

    /** The translational modifier: two parallel strokes — a slide, in the same corner. */
    private val MOD_FLAT = path("M17.3 21.8 L20.9 18.2") + path("M19.6 22.8 L23 19.4")

    /** The target two *Place* rows aim at: a hairline cross and the anchor on it. */
    private val PLACE_TARGET = ghost("M5 19.5 L19 19.5 M12 16 L12 23") + dot(12.0, 19.5, 1.9)

    // ---- points in space ----

    /**
     * A stride along a line: the carrier, the reference point it is measured from, the point the distance
     * reaches, and the distance itself as a **one-way** arrow — one way, because two arrowheads and two
     * witness lines is [DIM_LINEAR], and this tool makes a point, not a dimension.
     */
    val POINT_AT_DIST =
        path("M2.5 16 L21.5 16") + dot(6.5, 16.0, 2.2) + dot(17.5, 16.0, 2.9) +
            ghost("M6.5 15 L6.5 11") + ghost("M17.5 15 L17.5 11") +
            path("M6.5 11 L16.4 11") + arrow(17.5, 11.0, 1.0, 0.0, 2.8, 1.8)

    /**
     * The perpendicular dropped **onto** a plane: the arrow points down and the bold dot is the foot, which is
     * exactly what turns this round from [HEIGHT_POINT], where the arrow rises and the base is the given.
     */
    val PROJECT_TO_PLANE =
        soft("M2.5 16 L10 19.5 L21.5 15.5 L14 12 Z") + dot(12.0, 4.0, 2.2) + dashed("M12 4 L12 12.6") +
            arrow(12.0, 13.6, 0.0, 1.0, 2.4, 1.7) + dot(12.0, 15.8, 2.8)

    /** A point riding a coil: the coil is the context, the dot at the parametric angle is the product. */
    val POINT_ON_HELIX = soft(coil(false)) + dot(12.0, 15.55, 2.8)

    /** The two dots a tether runs between, drawn once for both *Make relative* and *Make absolute*. */
    private val TETHER = dot(7.0, 7.5, 2.9) + dot(18.5, 17.5, 2.4)

    /** Stated: the tether holds, and the arrow names the anchor it holds to. */
    val MAKE_RELATIVE = TETHER + dashed("M9.2 9.7 L16.4 16.1") + arrow(17.3, 16.9, 0.747, 0.665, 2.8, 1.8)

    /**
     * The same tether, cut — and the freed point wearing [POINT]'s own four ticks, because *free* is precisely
     * what it has become. The gap alone was the first draft, and at 22 pixels one gap in a dashed line is not
     * a difference anybody can see.
     */
    val MAKE_ABSOLUTE =
        TETHER + dashed("M9.2 9.7 L11.6 11.8") + dashed("M14.4 14.3 L16.4 16.1") +
            ghost("M7 2.4 L7 4.3 M7 10.7 L7 12.6 M1.9 7.5 L3.8 7.5 M10.2 7.5 L12.1 7.5")

    /** Trilateration: three loci, and the one point where all three close. */
    val SPHERE_TRILATERATE =
        locusRing(7.5, 8.5, 5.4) + locusRing(16.5, 8.5, 5.4) + locusRing(12.0, 16.5, 5.0) + dot(12.0, 11.5, 2.8)

    /** Where a run meets a sphere: the locus and the run are the givens, the crossing is the answer. */
    val SPHERE_ON_RUN =
        locusRing(13.0, 13.0, 6.5) + soft("M3 21 A 16 16 0 0 1 21 6") + dot(6.63, 11.66, 2.8)

    /** The origin of a space: one point and its three axes, which is all a frame is. */
    val SPACE_ORIGIN =
        dot(11.0, 13.0, 2.4) + path("M11 13 L11 5.2") + arrow(11.0, 4.2, 0.0, -1.0, 2.6, 1.7) +
            path("M11 13 L17.9 17") + arrow(18.8, 17.5, 0.866, 0.5, 2.6, 1.7) +
            path("M11 13 L4.1 17") + arrow(3.2, 17.5, -0.866, 0.5, 2.6, 1.7)

    // ---- curves in space ----

    /** The three knots both *through points* rows are fitted to — the only thing they share, and enough. */
    private val KNOTS3 = dot(4.0, 18.0, 2.1) + dot(12.0, 6.0, 2.1) + dot(20.0, 15.0, 2.1)

    val CURVE3 = path("M4 18 L12 6 L20 15") + KNOTS3

    val CURVE3_SMOOTH = path("M4 18 C 7.6 18.4, 9 6.6, 12 6 C 15 5.4, 16.8 12, 20 15") + KNOTS3

    val HELIX = coilGlyph(false)

    val HELIX_LEFT = coilGlyph(true)

    val HELIX_PT = HELIX + dot(coilStart(false), 20.6, 2.7)

    val HELIX_PT_LEFT = HELIX_LEFT + dot(coilStart(true), 20.6, 2.7)

    /** Two views at right angles, and the one point matched across them — the operation, not the panes. */
    val COMBINE_VIEWS =
        path("M3.5 5 L3.5 15.5 L11 19 L11 8.5 Z M11 8.5 L20.5 4.5 L20.5 15 L11 19 Z") +
            dashed("M7.2 11.5 L16.2 10.2") + dot(7.2, 11.5, 2.1) + dot(16.2, 10.2, 2.1)

    /**
     * Where a plane meets a cylinder: the body faint, the plane faint and **edge on** (one line, not a
     * parallelogram — a second quad here was a smudge), and the section they share bold.
     */
    val INTERSECTION_CURVE =
        soft("M6.5 5 L6.5 18 M17.5 5 L17.5 18") + oval(12.0, 5.0, 5.5, 2.1, GHOST) + oval(12.0, 18.0, 5.5, 2.1, GHOST) +
            soft("M2 16.25 L22 8.75") + oval(12.0, 12.5, 5.9, 2.2, """transform="rotate(-21 12 12.5)"""")

    /** The circle two spheres share: two loci, and the lens they cut, seen as the circle it is. */
    val SPHERE_CIRCLE = locusRing(8.5, 12.0, 6.5) + locusRing(15.5, 12.0, 6.5) + oval(12.0, 12.0, 2.2, 5.5)

    /** The flat outline, and the same outline standing in space over it. */
    val LIFT =
        ghost("M3 19.5 L9.5 22 L21 19 L14.5 16.5 Z") + path("M12 15 L12 10") + arrow(12.0, 9.0, 0.0, -1.0, 2.6, 1.8) +
            path("M3 6.5 L9.5 9 L21 6 L14.5 3.5 Z")

    /**
     * The two stubs a bridge joins and the ends it is welded to. Drawn at .55 rather than as a [ghost]: at 22
     * pixels a ghosted stub vanishes and the glyph collapses into [SEGMENT] — the bridge needs the two things
     * it bridges to be *visible* to mean anything, so the bridge carries the weight instead.
     */
    private val BRIDGE_ENDS =
        soft("M2 21.5 C 4 19, 6.5 16.5, 9.5 16") + soft("M22 2.5 C 20 5, 17.5 7.5, 14.5 8") +
            dot(9.5, 16.0, 1.9) + dot(14.5, 8.0, 1.9)

    /** Tangent-continuous: the bridge alone, thick, because the bridge is the product. */
    val CONNECT = BRIDGE_ENDS + path("M9.5 16 C 16.5 15, 7.5 9, 14.5 8", """stroke-width="2.4"""")

    /** Curvature-continuous: the same bridge, with the comb that says the curvature matches too. */
    val CONNECT_G2 = CONNECT + path("M10.64 13.68 L14.04 13.4") + path("M9.96 9.12 L13.36 9.4")

    /** A curve thrown onto a face: the face and the curve above it faint, the curve that lands bold. */
    val PROJECT_ON_FACE =
        soft("M2.5 17 L10 20.5 L21.5 16.5 L14 13 Z") + soft("M5 6.5 C 9 2.5, 15 2.5, 19 6.5") +
            dashed("M5 7 L5 17.4") + dashed("M19 7 L19 15.8") +
            path("M5 17.8 C 9 16.1, 15 15.1, 19 16.2")

    /** A curve placed in space: the run, over the target it is placed on. */
    val PLACE_CURVE = path("M3.5 11 C 7 3.5, 12.5 13.5, 20.5 5") + PLACE_TARGET

    /** The cut line of a drawing: dash-dot, the way a section line has always been drawn, with its joints. */
    val CHAIN = dashDot("M3 18 L10 7.5 L21 12.5") + dot(3.0, 18.0, 1.9) + dot(10.0, 7.5, 2.3) + dot(21.0, 12.5, 1.9)

    // ---- sphere loci (construct) ----

    /**
     * A sphere as a **locus** rather than a body: dashed, the way construction geometry is drawn, with its
     * meridian — so it can never be mistaken for the solid [SPHERE] two categories down.
     */
    private val LOCUS = ring(12.0, 12.0, 8.0, """stroke-dasharray="2.6 2"""") + oval(12.0, 12.0, 3.0, 8.0, GHOST) + dot(12.0, 12.0, 2.2)

    /** Stated by a radius: the locus alone. */
    val SPHERE_LOCUS = LOCUS

    /** Stated by a point on it: the same locus plus that point — the family's "…by point" mark. */
    val SPHERE_LOCUS_PT = LOCUS + dot(17.66, 6.34, 2.7)

    /**
     * The bisector is the product, so it is the **bold** ray and the two given rays are context — the reverse
     * of the proposal in issue #22, which dashed the middle one: dashing what the tool makes contradicts every
     * other glyph in the set. The two equal arcs say why that ray and not another.
     */
    val ANGLE_BISECTOR =
        soft("M4 20 L22 17") + soft("M4 20 L9 2.5") + ghost("M11.4 18.76 A 7.5 7.5 0 0 0 9.6 15.01") +
            ghost("M9.6 15.01 A 7.5 7.5 0 0 0 6.07 12.79") +
            path("M4 20 L13.7 11.35", """stroke-width="2.2"""") + dot(4.0, 20.0, 2.2)

    // ---- solids from curves ----

    /** A bent pipe: the two walls the run carries, closed by the section at its far end. */
    val TUBE =
        path("M3 21 C 3 11, 9 5.5, 20 5.5") + path("M8 21 C 8 13.5, 12 10.5, 20 10.5") + oval(20.0, 8.0, 1.7, 2.5)

    /** The profile rides the run: the section at the tail, the run bold, the arrow where it is heading. */
    val SWEEP =
        ring(5.0, 18.5, 3.0) + path("M5 18.5 C 11 18.5, 11 5.5, 18.6 5.5") + arrow(19.6, 5.5, 1.0, 0.0, 2.8, 1.8)

    /**
     * One edge of a body, rounded or bevelled — the same box either way, with the sharp corner it replaced
     * kept as a ghost, exactly as the 2D [FILLET] and [CHAMFER] keep theirs.
     */
    private fun edgeBox(round: Boolean): String {
        val top = if (round) "A 2 2 0 0 1 10.2 12" else "L10.2 12"
        val low = if (round) "A 2 2 0 0 1 10.2 19" else "L10.2 19"
        return path("M3 8 L12 3 L21 8 L13.8 12 $top Z") + path("M3 8 L3 15") + path("M21 8 L21 15") +
            path("M10.2 12 L10.2 19") + path("M13.8 12 L13.8 19") +
            path("M3 15 L10.2 19") + path("M21 15 L13.8 19") + path("M13.8 19 $low") +
            ghost("M10.2 12 L12 13.4 L13.8 12 M12 13.4 L12 20.6")
    }

    val BLEND_EDGE = edgeBox(true)

    val CHAMFER_EDGE = edgeBox(false)

    /** Every edge of one **face**, rounded or bevelled: the rim is the subject, the sharp rim the ghost. */
    private fun faceRim(round: Boolean): String {
        fun corner(to: String) = if (round) "A 1.8 1.8 0 0 1 $to" else "L $to"
        val d =
            "M5.62 7.88 L9.88 5.62 " + corner("14.12 5.62") + " L18.38 7.88 " + corner("18.38 10.12") +
                " L14.12 12.38 " + corner("9.88 12.38") + " L5.62 10.12 " + corner("5.62 7.88") + " Z"
        return ghost("M3.5 9 L12 4.5 L20.5 9 L12 13.5 Z") + path(d) +
            path("M5.62 10.12 L5.62 13.2") + path("M18.38 10.12 L18.38 13.2") +
            path("M9.88 12.38 L9.88 15.4") + path("M14.12 12.38 L14.12 15.4") +
            path("M5.62 13.2 L9.88 15.4") + path("M18.38 13.2 L14.12 15.4")
    }

    val BLEND_FACE = faceRim(true)

    val CHAMFER_FACE = faceRim(false)

    /**
     * Opened: a **tray** — outer rim, inner rim, and a shallow body under it. Deliberately not [BOX] plus a
     * mark: the closed shell is a box plus a dashed cavity, and at 22 pixels *solid cavity* against *dashed
     * cavity* inside the same cube was two glyphs nobody could tell apart. A container with an open top and a
     * cube with something hidden in it are two silhouettes, which is what the eye actually reads.
     */
    val SHELL =
        path("M2.5 11 L12 5.5 L21.5 11 L12 16.5 Z") + path("M5.9 11 L12 7.5 L18.1 11 L12 14.5 Z") +
            path("M2.5 11 L2.5 14.5") + path("M21.5 11 L21.5 14.5") + path("M12 16.5 L12 20") +
            path("M2.5 14.5 L12 20") + path("M21.5 14.5 L12 20")

    /** Closed: the box, and the cavity inside it — dashed, because it is exactly what you cannot see. */
    val SHELL_CLOSED =
        path(BOX) + dashed("M5.2 9 L12 5.15 L18.8 9 L12 12.85 Z M5.2 9 L5.2 12.4 M18.8 9 L18.8 12.4 M12 12.85 L12 16.4")

    /** A solid placed in space: the body, over the target it is placed on. */
    val PLACE_SOLID = path(BOX_SMALL) + PLACE_TARGET

    /** An opening cut through a wall: the slab, the hole, and the reveal that says the wall has thickness. */
    val CUT_OPENINGS =
        path("M2.5 9 L2.5 19 L16.5 19 L16.5 9 Z") + path("M2.5 9 L6.5 5.5 L20.5 5.5 L16.5 9") +
            path("M16.5 19 L20.5 15.5 L20.5 5.5") + path("M6 11.5 L6 16.5 L13 16.5 L13 11.5 Z") +
            path("M6 11.5 L7.6 10.1 L14.6 10.1 L13 11.5") + path("M13 16.5 L14.6 15.1 L14.6 10.1")

    /**
     * A body and the boundary through it, for all six *cut* / *split* rows. **Cut discards a side**, so the
     * discarded half goes faint and the boundary is drawn once; **split keeps both**, so both halves stay solid
     * and the boundary is drawn twice with a gap between. That is the whole difference, and it is the only one.
     */
    private fun cutBody(
        split: Boolean,
        upper: String,
        upperGap: String,
        lower: String,
        lowerGap: String,
        boundary: (String) -> String,
        seam: String,
        seamUp: String,
        seamDown: String,
    ): String =
        if (split) {
            path(upperGap) + boundary(seamUp) + path(lowerGap) + boundary(seamDown)
        } else {
            soft(upper) + path(lower) + boundary(seam)
        }

    private fun byChain(split: Boolean) =
        cutBody(
            split,
            "M3 14 L3 4 L21 4 L21 12",
            "M3 12.1 L3 4 L21 4 L21 10.1",
            "M3 14 L3 20 L21 20 L21 12",
            "M3 15.9 L3 20 L21 20 L21 13.9",
            { d -> dashDot(d) },
            "M3 14 L11 8 L21 12",
            "M3 12.1 L11 6.1 L21 10.1",
            "M3 15.9 L11 9.9 L21 13.9",
        )

    val CUT_BY_CHAIN = byChain(false)

    val SPLIT_BY_CHAIN = byChain(true)

    /**
     * The same body and the same distinction, with a **run** for a boundary rather than a chain — a smooth
     * curve against a kinked dash-dot polyline — plus the rotating / translational mark in the free corner.
     */
    private fun alongCurve(
        split: Boolean,
        mod: String,
    ) = cutBody(
        split,
        "M2.5 11.6 L2.5 2.5 L15.5 2.5 L15.5 8.6",
        "M2.5 9.7 L2.5 2.5 L15.5 2.5 L15.5 6.7",
        "M2.5 11.6 L2.5 18 L15.5 18 L15.5 8.6",
        "M2.5 13.5 L2.5 18 L15.5 18 L15.5 10.5",
        { d -> path(d) },
        "M2.5 11.6 C 7.5 11.6, 8 8.6, 15.5 8.6",
        "M2.5 9.7 C 7.5 9.7, 8 6.7, 15.5 6.7",
        "M2.5 13.5 C 7.5 13.5, 8 10.5, 15.5 10.5",
    ) + mod

    val CUT_ALONG_CURVE = alongCurve(false, MOD_ROT)

    val CUT_ALONG_CURVE_FLAT = alongCurve(false, MOD_FLAT)

    val SPLIT_ALONG_CURVE = alongCurve(true, MOD_ROT)

    val SPLIT_ALONG_CURVE_FLAT = alongCurve(true, MOD_FLAT)

    // ---- planes & sketch spaces ----

    /** A plane at a stated height: the plane, and the height itself as the dimension it is. */
    val PLANE_AT_HEIGHT =
        path("M2.5 8.5 L9 11.5 L21.5 8 L15 5 Z") + ghost("M2 19.5 L22 19.5") + path("M12 11 L12 19.5") +
            arrow(12.0, 10.4, 0.0, -1.0, 2.4, 1.6) + arrow(12.0, 20.1, 0.0, 1.0, 2.4, 1.6)

    /** A plane hinged on a line and turned: the line, the plane, and the angle between them. */
    val SKETCH_PLANE =
        ghost("M2.5 19 L21.5 19") + path("M5 19 L13 19 L19 7 L11 7 Z") +
            path("M18 19 A 5 5 0 0 0 15.24 14.53")

    /** A station on a run: the run is the given, the plane across it is what the tool makes. */
    val STATION =
        soft("M2.5 19 C 9 19, 10 6, 21.5 6") + path("M4 13.5 L10 16 L20 11.5 L14 9 Z")

    /** A sketch plane read off a wireframe: the wire in space faint, the flat space it defines bold. */
    val SKETCH_FROM_WIRE =
        soft("M3 13.5 L8 5 L13.5 9 L18 3.5 L20.5 11") + path("M2.5 16 L9.5 19.5 L21.5 14 L14.5 10.5 Z")

    /** A sketch on a face: the body faint, and the drawing that now lives on its top face bold. */
    val SKETCH_ON_FACE =
        soft(BOX) + path("M6.5 9.6 C 8.5 6.8, 11 11.2, 13.5 8.4 C 15.2 6.5, 16.6 8.4, 18 9.4")

    // ---- measurements ----
    // One language for all ten: the thing measured is **ghosted**, the mark that states the number is bold —
    // which is also what keeps them apart from the *dimensions* two rows down, whose graphic *is* the product.

    /** A length: the curve faint between its ends, and the span stated as a bracket. */
    val LENGTH =
        ghost("M4 8 L20 8") + ring(4.0, 8.0, 1.8, GHOST) + ring(20.0, 8.0, 1.8, GHOST) +
            ghost("M4 10 L4 13") + ghost("M20 10 L20 13") +
            path("M4 16 L20 16") + path("M4 13.4 L4 18.6") + path("M20 13.4 L20 18.6")

    /** A radius: the circle faint, the measure taken from the centre out to it. */
    val RADIUS =
        ring(12.0, 12.0, 7.5, GHOST) + dot(12.0, 12.0, 2.0) + path("M12 12 L17.4 8.2") +
            arrow(18.14, 7.7, 0.819, -0.574, 2.8, 1.8)

    /** An angle over **three points**: the legs faint, the three picks as dots, the opening stated by the arc. */
    val ANGLE =
        soft("M4 19 L21 19") + soft("M4 19 L17 4") + dot(4.0, 19.0, 2.4) + dot(21.0, 19.0, 1.9) + dot(17.0, 4.0, 1.9) +
            path("M13 19 A 9 9 0 0 0 9.89 12.21", """stroke-width="2.2"""")

    /** The same measure between two **lines**, which cross instead of meeting at a picked vertex. */
    val ANGLE_LINES =
        soft("M3 17 L21 7") + soft("M6 4 L16 20") +
            path("M16.07 9.73 A 5.5 5.5 0 0 1 14.18 17.06", """stroke-width="2.2"""")

    /** A coordinate: the axis faint, the point, the drop onto the axis dashed, and the foot stated bold. */
    val COORD_X =
        ghost("M2.5 20 L20 20") + arrow(21.5, 20.0, 1.0, 0.0, 2.6, 1.7, GHOST) +
            dot(13.0, 8.5, 2.6) + dashed("M13 8.5 L13 20") + path("M13 17 L13 22.6")

    val COORD_Y =
        ghost("M4 21.5 L4 4") + arrow(4.0, 2.5, 0.0, -1.0, 2.6, 1.7, GHOST) +
            dot(15.5, 11.0, 2.6) + dashed("M15.5 11 L4 11") + path("M1.4 11 L7 11")

    /** A volume: the body faint, and the space inside it as the thing counted. */
    val VOLUME = ghost(BOX_AXES) + dot(7.5, 12.0, 1.9) + dot(11.5, 10.0, 1.9) + dot(10.5, 15.2, 1.9)

    /** How far a body reaches along X: the width, below it. */
    val EXTENT_X =
        ghost(BOX_AXES) + path("M4 21 L15 21") + path("M4 19.2 L4 22.8") + path("M15 19.2 L15 22.8")

    /** Along Y: the depth, on the depth diagonal — the only direction in the drawing that *is* Y. */
    val EXTENT_Y =
        ghost(BOX_AXES) + path("M16.56 19.56 L20.56 15.56") + path("M15.71 18.71 L17.41 20.41") +
            path("M19.71 14.71 L21.41 16.41")

    /** Along Z: the height, beside it. */
    val EXTENT_Z =
        ghost(BOX_AXES) + path("M21.6 4 L21.6 14") + path("M20.2 4 L23 4") + path("M20.2 14 L23 14")

    /** Wrap a glyph in the one `<svg>` that gives every icon its paint, weight and size. */
    fun wrap(
        glyph: String,
        px: Int = 22,
    ): String =
        """<svg viewBox="0 0 24 24" width="$px" height="$px" fill="none" stroke="currentColor" """ +
            """stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">$glyph</svg>"""
}
