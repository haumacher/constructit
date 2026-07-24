package constructit.svg

import constructit.core.ArcValue
import constructit.core.CircleValue
import constructit.core.Evaluator
import constructit.core.LineValue
import constructit.core.PointSetValue
import constructit.core.PointValue
import constructit.core.SegmentValue
import constructit.dsl.Ref
import constructit.dsl.valueOf
import constructit.geom.Line
import constructit.geom.Segment
import constructit.geom.Vec2
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** One drawable graph output plus its style. */
data class Drawable(val ref: Ref<*>, val stroke: String = "#1f77b4", val fill: String = "none")

/**
 * Canonical, deterministic SVG serializer (per the testing strategy in DESIGN.md):
 * fixed decimal precision, Locale.ROOT formatting, stable element order, auto viewBox.
 * Math space is y-up; screen space is y-down, so y is negated on output.
 */
object Svg {

    private const val PRECISION = 3
    private const val STROKE_WIDTH = 0.5
    private const val POINT_RADIUS = 1.2

    private val scale10 = Math.pow(10.0, PRECISION.toDouble())

    private fun fmt(v: Double): String {
        // Round to PRECISION decimals, then normalize -0.0 -> 0.0 so float dust never prints "-0.000".
        val rounded = Math.round(v * scale10) / scale10
        val n = if (rounded == 0.0) 0.0 else rounded
        return String.format(Locale.ROOT, "%.${PRECISION}f", n)
    }

    private fun screen(v: Vec2) = Vec2(v.x, -v.y)

    private fun norm2pi(a: Double): Double {
        val twoPi = 2 * Math.PI
        var r = a % twoPi
        if (r < 0) r += twoPi
        return r
    }

    fun render(ev: Evaluator, items: List<Drawable>, margin: Double = 6.0): String {
        // 1. gather geometry + bbox sample points (math space).
        val samples = ArrayList<Vec2>()
        data class Prepared(val kind: String, val d: Drawable, val geom: Any)
        val prepared = ArrayList<Prepared>()

        for (d in items) {
            when (val v = ev.valueOf(d.ref)) {
                is PointValue -> { samples.add(v.p); prepared.add(Prepared("point", d, v.p)) }
                is SegmentValue -> { samples.add(v.seg.a); samples.add(v.seg.b); prepared.add(Prepared("segment", d, v.seg)) }
                is CircleValue -> {
                    val c = v.circle
                    samples.add(c.center + Vec2(c.radius, c.radius)); samples.add(c.center - Vec2(c.radius, c.radius))
                    prepared.add(Prepared("circle", d, c))
                }
                is ArcValue -> {
                    val a = v.arc
                    samples.add(a.center + Vec2(a.radius, a.radius)); samples.add(a.center - Vec2(a.radius, a.radius))
                    prepared.add(Prepared("arc", d, a))
                }
                is LineValue -> prepared.add(Prepared("line", d, v.line)) // clipped later; not in bbox
                is PointSetValue -> for (p in v.set.points) { samples.add(p); prepared.add(Prepared("point", d, p)) }
                else -> {} // scalars etc. not drawable
            }
        }

        if (samples.isEmpty()) samples.add(Vec2(0.0, 0.0))
        var minX = samples[0].x; var minY = samples[0].y; var maxX = samples[0].x; var maxY = samples[0].y
        for (p in samples) {
            minX = min(minX, p.x); minY = min(minY, p.y); maxX = max(maxX, p.x); maxY = max(maxY, p.y)
        }
        // math bbox for line clipping (with margin)
        val clipMin = Vec2(minX - margin, minY - margin)
        val clipMax = Vec2(maxX + margin, maxY + margin)

        // 2. viewBox in screen space (y negated: top = -maxY).
        val vbX = minX - margin
        val vbY = -(maxY) - margin
        val vbW = (maxX - minX) + 2 * margin
        val vbH = (maxY - minY) + 2 * margin

        // 3. emit.
        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
        sb.append("viewBox=\"${fmt(vbX)} ${fmt(vbY)} ${fmt(vbW)} ${fmt(vbH)}\" ")
        sb.append("width=\"${fmt(vbW)}\" height=\"${fmt(vbH)}\">\n")

        for (p in prepared) {
            when (p.kind) {
                "point" -> {
                    val s = screen(p.geom as Vec2)
                    sb.append("  <circle cx=\"${fmt(s.x)}\" cy=\"${fmt(s.y)}\" r=\"${fmt(POINT_RADIUS)}\" fill=\"${p.d.stroke}\"/>\n")
                }
                "segment" -> {
                    val seg = p.geom as Segment
                    val a = screen(seg.a); val b = screen(seg.b)
                    sb.append(lineTag(a, b, p.d.stroke))
                }
                "line" -> {
                    val clipped = clipLineToRect(p.geom as Line, clipMin, clipMax)
                    if (clipped != null) {
                        val a = screen(clipped.a); val b = screen(clipped.b)
                        sb.append(lineTag(a, b, p.d.stroke))
                    }
                }
                "circle" -> {
                    val c = p.geom as constructit.geom.Circle
                    val s = screen(c.center)
                    sb.append("  <circle cx=\"${fmt(s.x)}\" cy=\"${fmt(s.y)}\" r=\"${fmt(c.radius)}\" fill=\"${p.d.fill}\" stroke=\"${p.d.stroke}\" stroke-width=\"${fmt(STROKE_WIDTH)}\"/>\n")
                }
                "arc" -> sb.append(arcTag(p.geom as constructit.geom.Arc, p.d.stroke))
            }
        }
        sb.append("</svg>\n")
        return sb.toString()
    }

    private fun lineTag(a: Vec2, b: Vec2, stroke: String): String =
        "  <line x1=\"${fmt(a.x)}\" y1=\"${fmt(a.y)}\" x2=\"${fmt(b.x)}\" y2=\"${fmt(b.y)}\" stroke=\"$stroke\" stroke-width=\"${fmt(STROKE_WIDTH)}\"/>\n"

    private fun arcTag(arc: constructit.geom.Arc, stroke: String): String {
        val p0 = arc.center + Vec2(arc.radius * cos(arc.startAngle), arc.radius * sin(arc.startAngle))
        val p1 = arc.center + Vec2(arc.radius * cos(arc.endAngle), arc.radius * sin(arc.endAngle))
        val s0 = screen(p0); val s1 = screen(p1)
        val sweepAngle = if (arc.ccw) norm2pi(arc.endAngle - arc.startAngle) else norm2pi(arc.startAngle - arc.endAngle)
        val largeArc = if (sweepAngle > Math.PI) 1 else 0
        // We emit in screen space (y negated). SVG sweep-flag=1 is increasing screen-angle;
        // negating y flips the sense, so a math-CCW arc is sweep-flag 0 (and math-CW is 1).
        val sweepFlag = if (arc.ccw) 0 else 1
        return "  <path d=\"M ${fmt(s0.x)} ${fmt(s0.y)} A ${fmt(arc.radius)} ${fmt(arc.radius)} 0 $largeArc $sweepFlag ${fmt(s1.x)} ${fmt(s1.y)}\" fill=\"none\" stroke=\"$stroke\" stroke-width=\"${fmt(STROKE_WIDTH)}\"/>\n"
    }

    /** Liang-Barsky style clip of an infinite line to an axis-aligned rectangle. */
    private fun clipLineToRect(line: Line, lo: Vec2, hi: Vec2): Segment? {
        var tMin = Double.NEGATIVE_INFINITY
        var tMax = Double.POSITIVE_INFINITY
        val o = line.origin; val dir = line.dir
        for (axis in 0..1) {
            val od = if (axis == 0) dir.x else dir.y
            val oo = if (axis == 0) o.x else o.y
            val lom = if (axis == 0) lo.x else lo.y
            val him = if (axis == 0) hi.x else hi.y
            if (abs(od) < Vec2.EPS) {
                if (oo < lom || oo > him) return null
            } else {
                val t1 = (lom - oo) / od
                val t2 = (him - oo) / od
                tMin = max(tMin, min(t1, t2))
                tMax = min(tMax, max(t1, t2))
            }
        }
        if (tMin > tMax) return null
        return Segment(o + dir * tMin, o + dir * tMax)
    }
}
