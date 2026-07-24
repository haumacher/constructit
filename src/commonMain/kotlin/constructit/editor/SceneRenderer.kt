package constructit.editor

import constructit.core.ArcValue
import constructit.core.CircleValue
import constructit.core.Evaluator
import constructit.core.LineValue
import constructit.core.PointSetValue
import constructit.core.PointValue
import constructit.core.RayValue
import constructit.core.SegmentValue
import constructit.dsl.valueOf
import constructit.geom.Arc
import constructit.geom.Line
import constructit.geom.Ray
import constructit.geom.Segment
import constructit.geom.Vec2
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws a [Document] to a [DrawTarget] through a [Camera]. Pure: projects world->screen,
 * tessellates arcs to polylines, clips infinite lines/rays to the viewport. Invalid nodes
 * (OP-3) simply produce no value and are skipped (hidden).
 */
object SceneRenderer {

    private const val POINT_PX = 4.0
    private const val TWO_PI = 2.0 * kotlin.math.PI

    fun render(doc: Document, ev: Evaluator, cam: Camera, target: DrawTarget, wPx: Double, hPx: Double) {
        target.begin(wPx, hPx)
        val view = worldViewRect(cam, wPx, hPx)
        for (el in doc.elements) {
            if (!el.visible) continue
            when (val v = ev.valueOf(el.ref)) {
                is PointValue -> target.dot(cam.worldToScreen(v.p), POINT_PX, el.style.stroke)
                is SegmentValue -> target.polyline(listOf(cam.worldToScreen(v.seg.a), cam.worldToScreen(v.seg.b)), el.style)
                is LineValue -> clipLine(v.line, view)?.let { target.polyline(listOf(cam.worldToScreen(it.a), cam.worldToScreen(it.b)), el.style) }
                is RayValue -> clipRay(v.ray, view)?.let { target.polyline(listOf(cam.worldToScreen(it.a), cam.worldToScreen(it.b)), el.style) }
                is CircleValue -> target.circle(cam.worldToScreen(v.circle.center), v.circle.radius * cam.scale, el.style)
                is ArcValue -> target.polyline(tessellate(v.arc).map { cam.worldToScreen(it) }, el.style)
                is PointSetValue -> v.set.points.forEach { target.dot(cam.worldToScreen(it), POINT_PX, el.style.stroke) }
                else -> {}
            }
        }
        target.end()
    }

    private fun norm2pi(a: Double): Double { var r = a % TWO_PI; if (r < 0) r += TWO_PI; return r }

    fun tessellate(arc: Arc): List<Vec2> {
        val sweep = if (arc.ccw) norm2pi(arc.endAngle - arc.startAngle) else -norm2pi(arc.startAngle - arc.endAngle)
        val n = max(6, ceil(abs(sweep) / TWO_PI * 64).toInt())
        return (0..n).map {
            val ang = arc.startAngle + sweep * it / n
            arc.center + Vec2(arc.radius * cos(ang), arc.radius * sin(ang))
        }
    }

    private data class Rect(val lo: Vec2, val hi: Vec2)

    private fun worldViewRect(cam: Camera, wPx: Double, hPx: Double): Rect {
        val a = cam.screenToWorld(Vec2(0.0, 0.0))
        val b = cam.screenToWorld(Vec2(wPx, hPx))
        return Rect(Vec2(min(a.x, b.x), min(a.y, b.y)), Vec2(max(a.x, b.x), max(a.y, b.y)))
    }

    private fun clipLine(line: Line, r: Rect): Segment? = clipParam(line.origin, line.dir, r, Double.NEGATIVE_INFINITY)
    private fun clipRay(ray: Ray, r: Rect): Segment? = clipParam(ray.origin, ray.dir, r, 0.0)

    private fun clipParam(o: Vec2, dir: Vec2, r: Rect, tStart: Double): Segment? {
        var tMin = tStart
        var tMax = Double.POSITIVE_INFINITY
        for (axis in 0..1) {
            val od = if (axis == 0) dir.x else dir.y
            val oo = if (axis == 0) o.x else o.y
            val lo = if (axis == 0) r.lo.x else r.lo.y
            val hi = if (axis == 0) r.hi.x else r.hi.y
            if (abs(od) < Vec2.EPS) {
                if (oo < lo || oo > hi) return null
            } else {
                val t1 = (lo - oo) / od; val t2 = (hi - oo) / od
                tMin = max(tMin, min(t1, t2)); tMax = min(tMax, max(t1, t2))
            }
        }
        if (tMin > tMax) return null
        return Segment(o + dir * tMin, o + dir * tMax)
    }
}
