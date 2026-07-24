package constructit.editor

import constructit.geom.Vec2
import kotlin.math.abs
import kotlin.math.round

/**
 * A [DrawTarget] that renders to a deterministic SVG string — lets the interactive scene be
 * snapshot-tested headlessly (same golden discipline as the engine).
 */
class SvgDrawTarget : DrawTarget {
    private val sb = StringBuilder()

    fun svg(): String = sb.toString()

    override fun begin(widthPx: Double, heightPx: Double) {
        sb.clear()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${fmt(widthPx)}\" height=\"${fmt(heightPx)}\" ")
        sb.append("viewBox=\"0 0 ${fmt(widthPx)} ${fmt(heightPx)}\">\n")
    }

    override fun polyline(points: List<Vec2>, style: Style) {
        if (points.isEmpty()) return
        val pts = points.joinToString(" ") { "${fmt(it.x)},${fmt(it.y)}" }
        sb.append("  <polyline points=\"$pts\" fill=\"none\" stroke=\"${style.stroke}\" stroke-width=\"${fmt(style.width)}\"/>\n")
    }

    override fun circle(center: Vec2, radiusPx: Double, style: Style) {
        sb.append("  <circle cx=\"${fmt(center.x)}\" cy=\"${fmt(center.y)}\" r=\"${fmt(radiusPx)}\" fill=\"${style.fill ?: "none"}\" stroke=\"${style.stroke}\" stroke-width=\"${fmt(style.width)}\"/>\n")
    }

    override fun dot(center: Vec2, radiusPx: Double, color: String) {
        sb.append("  <circle cx=\"${fmt(center.x)}\" cy=\"${fmt(center.y)}\" r=\"${fmt(radiusPx)}\" fill=\"$color\"/>\n")
    }

    override fun end() {
        sb.append("</svg>\n")
    }

    private companion object {
        const val PRECISION = 3
        const val SCALE = 1000L
        fun fmt(v: Double): String {
            val scaled = round(abs(v) * SCALE).toLong()
            val neg = v < 0 && scaled != 0L
            val intPart = scaled / SCALE
            val frac = (scaled % SCALE).toString().padStart(PRECISION, '0')
            return (if (neg) "-" else "") + "$intPart.$frac"
        }
    }
}
