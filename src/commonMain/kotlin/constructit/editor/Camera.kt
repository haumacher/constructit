package constructit.editor

import constructit.geom.Vec2

/**
 * World<->screen mapping. World is y-up (mm); screen is y-down (px).
 * screen = (world.x*scale + panX, -world.y*scale + panY).
 */
data class Camera(val panX: Double, val panY: Double, val scale: Double) {
    fun worldToScreen(w: Vec2) = Vec2(w.x * scale + panX, -w.y * scale + panY)

    fun screenToWorld(s: Vec2) = Vec2((s.x - panX) / scale, -(s.y - panY) / scale)

    fun pan(
        dxScreen: Double,
        dyScreen: Double,
    ) = copy(panX = panX + dxScreen, panY = panY + dyScreen)

    /** Zoom by [factor] while keeping the world point under screen position [s] fixed. */
    fun zoomAt(
        s: Vec2,
        factor: Double,
    ): Camera {
        val w = screenToWorld(s)
        val newScale = scale * factor
        return Camera(s.x - w.x * newScale, s.y + w.y * newScale, newScale)
    }

    companion object {
        /** Centre the origin in a [wPx] x [hPx] viewport at [scale] px/mm. */
        fun centered(
            wPx: Double,
            hPx: Double,
            scale: Double = 4.0,
        ) = Camera(wPx / 2, hPx / 2, scale)
    }
}
