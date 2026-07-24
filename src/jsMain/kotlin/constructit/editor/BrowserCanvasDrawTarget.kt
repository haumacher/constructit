package constructit.editor

import constructit.geom.Vec2
import org.w3c.dom.CanvasRenderingContext2D
import kotlin.math.PI

/** [DrawTarget] backed by an HTML5 canvas 2D context. The browser seam of the render path. */
class BrowserCanvasDrawTarget(private val ctx: CanvasRenderingContext2D) : DrawTarget {

    override fun begin(widthPx: Double, heightPx: Double) {
        ctx.clearRect(0.0, 0.0, widthPx, heightPx)
        ctx.asDynamic().lineJoin = "round"
        ctx.asDynamic().lineCap = "round"
    }

    override fun polyline(points: List<Vec2>, style: Style) {
        if (points.isEmpty()) return
        ctx.beginPath()
        ctx.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) ctx.lineTo(points[i].x, points[i].y)
        ctx.strokeStyle = style.stroke
        ctx.lineWidth = style.width
        ctx.stroke()
    }

    override fun circle(center: Vec2, radiusPx: Double, style: Style) {
        ctx.beginPath()
        ctx.arc(center.x, center.y, radiusPx, 0.0, 2 * PI)
        style.fill?.let { ctx.fillStyle = it; ctx.fill() }
        ctx.strokeStyle = style.stroke
        ctx.lineWidth = style.width
        ctx.stroke()
    }

    override fun dot(center: Vec2, radiusPx: Double, color: String) {
        ctx.beginPath()
        ctx.arc(center.x, center.y, radiusPx, 0.0, 2 * PI)
        ctx.fillStyle = color
        ctx.fill()
    }

    override fun end() {}
}
