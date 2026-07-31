package constructit.editor

import constructit.geom.Arc
import constructit.geom.Circle
import constructit.geom.GeomMath
import constructit.geom.Vec2
import kotlin.math.max
import kotlin.math.min

/**
 * World<->screen mapping. World is y-up (mm); screen is y-down (px).
 * screen = (world.x*scale + panX, -world.y*scale + panY).
 *
 * The 2D canvas' [PlaneProjection], and the **exact** one: a similarity, so one number is the scale
 * everywhere, a point always has an image, and a circle's image is a circle. The whole rendering path
 * goes through the interface, which is what lets the same [SceneRenderer] draw the active space onto its
 * working plane inside the 3D view (see [PlanePerspective]) — and why every 2D golden is untouched: this
 * implementation emits exactly the calls the renderer used to make inline.
 */
data class Camera(val panX: Double, val panY: Double, val scale: Double) : PlaneProjection {
    fun worldToScreen(w: Vec2) = Vec2(w.x * scale + panX, -w.y * scale + panY)

    fun screenToWorld(s: Vec2) = Vec2((s.x - panX) / scale, -(s.y - panY) / scale)

    override fun toScreen(p: Vec2): Vec2 = worldToScreen(p)

    override fun toPlane(s: Vec2): Vec2 = screenToWorld(s)

    override fun scaleAt(p: Vec2): Double = scale

    override val similarity: Boolean get() = true

    override fun viewRect(
        wPx: Double,
        hPx: Double,
    ): Pair<Vec2, Vec2> {
        val a = screenToWorld(Vec2(0.0, 0.0))
        val b = screenToWorld(Vec2(wPx, hPx))
        return Vec2(min(a.x, b.x), min(a.y, b.y)) to Vec2(max(a.x, b.x), max(a.y, b.y))
    }

    /**
     * A fixed number of steps per full turn, so a golden does not depend on the zoom — the renderer's own
     * policy since arcs were first drawn, kept here where the policy belongs to a projection.
     */
    override fun arcPoints(arc: Arc): List<Vec2> = GeomMath.sampleArc(arc, GeomMath.renderArcSteps(arc))

    override fun drawCircle(
        target: DrawTarget,
        c: Circle,
        style: Style,
    ) = target.circle(worldToScreen(c.center), c.radius * scale, style)

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
