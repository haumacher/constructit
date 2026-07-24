package constructit.editor

import constructit.core.CircleValue
import constructit.core.Evaluator
import constructit.core.LineValue
import constructit.core.PointValue
import constructit.core.SegmentValue
import constructit.dsl.valueOf
import constructit.geom.Vec2
import kotlin.math.abs

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
}
