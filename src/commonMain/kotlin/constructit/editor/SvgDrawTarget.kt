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

    override fun begin(
        widthPx: Double,
        heightPx: Double,
    ) {
        sb.clear()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${fmt(widthPx)}\" height=\"${fmt(heightPx)}\" ")
        sb.append("viewBox=\"0 0 ${fmt(widthPx)} ${fmt(heightPx)}\">\n")
    }

    override fun polyline(
        points: List<Vec2>,
        style: Style,
    ) {
        if (points.isEmpty()) return
        val pts = points.joinToString(" ") { "${fmt(it.x)},${fmt(it.y)}" }
        sb.append("  <polyline points=\"$pts\" fill=\"none\" stroke=\"${style.stroke}\" stroke-width=\"${fmt(style.width)}\"${dash(style)}/>\n")
    }

    override fun polygon(
        points: List<Vec2>,
        style: Style,
    ) {
        if (points.isEmpty()) return
        val pts = points.joinToString(" ") { "${fmt(it.x)},${fmt(it.y)}" }
        sb.append("  <polygon points=\"$pts\" fill=\"${style.fill ?: "none"}\" stroke=\"${style.stroke}\" stroke-width=\"${fmt(style.width)}\"${dash(style)}/>\n")
    }

    override fun circle(
        center: Vec2,
        radiusPx: Double,
        style: Style,
    ) {
        sb.append("  <circle cx=\"${fmt(center.x)}\" cy=\"${fmt(center.y)}\" r=\"${fmt(radiusPx)}\" fill=\"${style.fill ?: "none"}\" stroke=\"${style.stroke}\" stroke-width=\"${fmt(style.width)}\"${dash(style)}/>\n")
    }

    override fun dot(
        center: Vec2,
        radiusPx: Double,
        color: String,
    ) {
        sb.append("  <circle cx=\"${fmt(center.x)}\" cy=\"${fmt(center.y)}\" r=\"${fmt(radiusPx)}\" fill=\"$color\"/>\n")
    }

    /** Fixed attribute order and fixed precision, like every other primitive here, so goldens stay byte-stable. */
    override fun text(
        at: Vec2,
        text: String,
        style: Style,
        anchor: TextAnchor,
    ) {
        sb.append("  <text x=\"${fmt(at.x)}\" y=\"${fmt(at.y)}\" font-family=\"$TEXT_FAMILY\" font-size=\"${fmt(TEXT_SIZE_PX)}\" ")
        sb.append("text-anchor=\"${anchorName(anchor)}\" fill=\"${style.fill ?: style.stroke}\">${escape(text)}</text>\n")
    }

    override fun end() {
        sb.append("</svg>\n")
    }

    private companion object {
        const val PRECISION = 3
        const val SCALE = 1000L

        fun anchorName(a: TextAnchor): String =
            when (a) {
                TextAnchor.START -> "start"
                TextAnchor.MIDDLE -> "middle"
                TextAnchor.END -> "end"
            }

        /**
         * A dashed stroke's attribute, or nothing at all for a solid one — written **only** when there is a
         * dash, so every golden taken before [Style.dash] existed still matches byte for byte.
         */
        fun dash(style: Style): String = style.dash?.let { " stroke-dasharray=\"${fmt(it)}\"" } ?: ""

        fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        fun fmt(v: Double): String {
            val scaled = round(abs(v) * SCALE).toLong()
            val neg = v < 0 && scaled != 0L
            val intPart = scaled / SCALE
            val frac = (scaled % SCALE).toString().padStart(PRECISION, '0')
            return (if (neg) "-" else "") + "$intPart.$frac"
        }
    }
}
