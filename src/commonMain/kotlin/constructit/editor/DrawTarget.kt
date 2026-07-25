package constructit.editor

import constructit.geom.Vec2

/** Visual style for a drawn primitive. */
data class Style(val stroke: String, val width: Double = 1.5, val fill: String? = null)

/** Horizontal placement of a text run relative to its anchor — SVG's `text-anchor`, canvas' `textAlign`. */
enum class TextAnchor { START, MIDDLE, END }

/**
 * The one font of the drawing surface. A single stack, stated once and used identically by every
 * backend, so an SVG golden describes what the browser draws (as far as SVG can promise).
 */
const val TEXT_SIZE_PX = 12.0

const val TEXT_FAMILY = "sans-serif"

/**
 * A backend-agnostic drawing surface. All coordinates are in **screen pixels** — the
 * SceneRenderer handles world->screen projection and arc tessellation, so implementations
 * (SVG, HTML Canvas, …) stay trivial. This is the one platform seam of the rendering path.
 */
interface DrawTarget {
    fun begin(
        widthPx: Double,
        heightPx: Double,
    )

    fun polyline(
        points: List<Vec2>,
        style: Style,
    )

    fun circle(
        center: Vec2,
        radiusPx: Double,
        style: Style,
    )

    fun dot(
        center: Vec2,
        radiusPx: Double,
        color: String,
    )

    /**
     * A single line of text with its baseline at [at], filled with `style.fill ?: style.stroke`.
     *
     * The one primitive a dimension needs that geometry does not (OP-4): the measured value has to be
     * *read*. Screen pixels like everything else, [TEXT_SIZE_PX] tall, never rotated — an annotation is
     * placed by the drawing, not by the geometry it names.
     */
    fun text(
        at: Vec2,
        text: String,
        style: Style,
        anchor: TextAnchor = TextAnchor.MIDDLE,
    )

    fun end()
}
