package constructit.editor

import constructit.core.ArcValue
import constructit.core.CircleValue
import constructit.core.Evaluator
import constructit.core.LineValue
import constructit.core.PointValue
import constructit.core.SegmentValue
import constructit.dsl.valueOf
import constructit.geom.Arc
import constructit.geom.Vec2
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Picking helpers: find the nearest free point or curve to a world position within a tolerance. */
object HitTest {

    fun nearestFreePoint(doc: Document, ev: Evaluator, world: Vec2, tol: Double): Element? {
        var best: Element? = null
        var bestD = tol
        for (el in doc.elements) {
            if (!el.draggable) continue
            val p = (ev.valueOf(el.ref) as? PointValue)?.p ?: continue
            val d = (p - world).length()
            if (d <= bestD) { bestD = d; best = el }
        }
        return best
    }

    /** Nearest point-like element (free, derived, or on-curve), for snapping/reuse. */
    fun nearestAnyPoint(doc: Document, ev: Evaluator, world: Vec2, tol: Double): Element? {
        var best: Element? = null
        var bestD = tol
        for (el in doc.elements) {
            if (!el.isPoint) continue
            val p = (ev.valueOf(el.ref) as? PointValue)?.p ?: continue
            val d = (p - world).length()
            if (d <= bestD) { bestD = d; best = el }
        }
        return best
    }

    /** Nearest element (point or curve) satisfying [filter], within [tol]. */
    fun nearest(doc: Document, ev: Evaluator, world: Vec2, tol: Double, filter: (Element) -> Boolean): Element? {
        var best: Element? = null
        var bestD = tol
        for (el in doc.elements) {
            if (!filter(el)) continue
            val d = when (val v = ev.valueOf(el.ref)) {
                is PointValue -> (v.p - world).length()
                is LineValue -> abs((world - v.line.origin).cross(v.line.dir))
                is CircleValue -> abs((world - v.circle.center).length() - v.circle.radius)
                is SegmentValue -> distToSegment(world, v.seg.a, v.seg.b)
                is ArcValue -> distToArc(world, v.arc)
                else -> continue
            }
            if (d <= bestD) { bestD = d; best = el }
        }
        return best
    }

    fun nearestCurve(doc: Document, ev: Evaluator, world: Vec2, tol: Double): Element? {
        var best: Element? = null
        var bestD = tol
        for (el in doc.elements) {
            val d = when (val v = ev.valueOf(el.ref)) {
                is LineValue -> abs((world - v.line.origin).cross(v.line.dir))
                is CircleValue -> abs((world - v.circle.center).length() - v.circle.radius)
                is SegmentValue -> distToSegment(world, v.seg.a, v.seg.b)
                is ArcValue -> distToArc(world, v.arc)
                else -> continue
            }
            if (d <= bestD) { bestD = d; best = el }
        }
        return best
    }

    private fun distToSegment(p: Vec2, a: Vec2, b: Vec2): Double {
        val ab = b - a
        val t = if (ab.length() < Vec2.EPS) 0.0 else ((p - a).dot(ab) / ab.dot(ab)).coerceIn(0.0, 1.0)
        return (p - (a + ab * t)).length()
    }

    /** Distance to an arc: to the circle if the point's angle is within the sweep, else to the nearer end. */
    private fun distToArc(p: Vec2, arc: Arc): Double {
        val to = p - arc.center
        return if (angleInSweep(atan2(to.y, to.x), arc)) {
            abs(to.length() - arc.radius)
        } else {
            minOf((p - arcPoint(arc, arc.startAngle)).length(), (p - arcPoint(arc, arc.endAngle)).length())
        }
    }

    private fun arcPoint(arc: Arc, ang: Double) = arc.center + Vec2(arc.radius * cos(ang), arc.radius * sin(ang))

    private fun angleInSweep(ang: Double, arc: Arc): Boolean {
        val twoPi = 2 * kotlin.math.PI
        fun norm(x: Double): Double { var r = x % twoPi; if (r < 0) r += twoPi; return r }
        val sweep = if (arc.ccw) norm(arc.endAngle - arc.startAngle) else norm(arc.startAngle - arc.endAngle)
        val rel = if (arc.ccw) norm(ang - arc.startAngle) else norm(arc.startAngle - ang)
        return rel <= sweep
    }
}
