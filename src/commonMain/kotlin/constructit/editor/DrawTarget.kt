package constructit.editor

import constructit.geom.Vec2

/** Visual style for a drawn primitive. */
data class Style(val stroke: String, val width: Double = 1.5, val fill: String? = null)

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

    fun end()
}
