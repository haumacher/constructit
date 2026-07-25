package constructit.editor

import constructit.core.CircleValue
import constructit.core.Evaluator
import constructit.core.LineValue
import constructit.core.PointValue
import constructit.core.RayValue
import constructit.core.SegmentValue
import constructit.dsl.valueOf
import constructit.geom.Circle
import constructit.geom.GeomMath
import constructit.geom.Line
import constructit.geom.Vec2
import kotlin.math.round

/** What a click landed on. Everything but [GRID] and [FREE] makes the new point *depend* on geometry. */
enum class SnapKind { POINT, INTERSECTION, ON_CURVE, GRID, FREE }

/**
 * Where a click lands and what it landed on: [target] is the point to reuse or the curve to attach
 * to, and [other] is the second curve of an [SnapKind.INTERSECTION].
 */
class SnapResult(
    val pos: Vec2,
    val kind: SnapKind,
    val target: Element? = null,
    val other: Element? = null,
) {
    /** Name of the snap, for the status bar. */
    val label: String
        get() =
            when (kind) {
                SnapKind.POINT -> "existing point"
                SnapKind.INTERSECTION -> "intersection"
                SnapKind.ON_CURVE -> "on curve"
                SnapKind.GRID -> "grid"
                SnapKind.FREE -> ""
            }

    /** True when placing here creates a dependency rather than a free point. */
    val linked: Boolean get() = kind == SnapKind.POINT || kind == SnapKind.INTERSECTION || kind == SnapKind.ON_CURVE
}

/**
 * Resolves where a click should land, so geometry can be placed *on* other geometry while it is being
 * drawn instead of only being attached afterwards by dragging.
 *
 * The point of snapping here is not cosmetic alignment: a snap that lands on geometry produces a real
 * dependency — reuse of an existing point (no new node), a derived intersection point (0 DOF), or an
 * on-curve slider (1 DOF). Coinciding by coordinate alone would silently come apart the moment the
 * other geometry moved, which is exactly what a construction is supposed to prevent.
 *
 * Resolution follows CAD osnap precedence: a point beats an intersection, which beats a curve, which
 * beats the grid. Geometric intersection is computed with [GeomMath] rather than by building nodes,
 * so hovering never touches the graph.
 */
object Snap {
    fun resolve(
        doc: Document,
        ev: Evaluator,
        world: Vec2,
        tol: Double,
        gridStep: Double? = null,
        /** Never snap to these, nor to anything built from them (a point can't attach to itself). */
        exclude: Element? = null,
    ): SnapResult {
        HitTest.nearestAnyPoint(doc, ev, world, tol)?.let { el ->
            if (el !== exclude) {
                val p = (ev.valueOf(el.ref) as? PointValue)?.p
                if (p != null) return SnapResult(p, SnapKind.POINT, el)
            }
        }

        // the attachable curves under the cursor, nearest first
        val near =
            doc.elements
                .filter { it !== exclude && it.visible && attachable(it) }
                .mapNotNull { el -> HitTest.distanceTo(ev, el, world)?.let { el to it } }
                .filter { it.second <= tol }
                .sortedBy { it.second }

        // two curves crossing under the cursor: prefer their intersection over either curve
        if (near.size >= 2) {
            val a = near[0].first
            val b = near[1].first
            val hit = crossing(ev, a, b)?.filter { (it - world).length() <= tol }?.minByOrNull { (it - world).length() }
            if (hit != null) return SnapResult(hit, SnapKind.INTERSECTION, a, b)
        }

        near.firstOrNull()?.let { (el, _) ->
            projection(ev, el, world)?.let { return SnapResult(it, SnapKind.ON_CURVE, el) }
        }

        if (gridStep != null && gridStep > 0.0) {
            val g = Vec2(round(world.x / gridStep) * gridStep, round(world.y / gridStep) * gridStep)
            if ((g - world).length() <= tol) return SnapResult(g, SnapKind.GRID)
        }
        return SnapResult(world, SnapKind.FREE)
    }

    /** Lines, segments, rays and circles can carry a point; arcs can't yet (no carrier circle). */
    private fun attachable(el: Element) = el.isLinear || el.kind == ElementKind.CIRCLE

    /** [el]'s geometry as an infinite line or a circle, for intersecting and projecting. */
    private fun formOf(
        ev: Evaluator,
        el: Element,
    ): Any? =
        when (val v = ev.valueOf(el.ref)) {
            is LineValue -> v.line
            is RayValue -> Line(v.ray.origin, v.ray.dir)
            is SegmentValue -> (v.seg.b - v.seg.a).let { d -> if (d.length() < Vec2.EPS) null else Line(v.seg.a, d.normalized()) }
            is CircleValue -> v.circle
            else -> null
        }

    /** Where [a] and [b] cross, treating segments/rays as their carrier lines. */
    private fun crossing(
        ev: Evaluator,
        a: Element,
        b: Element,
    ): List<Vec2>? {
        val fa = formOf(ev, a) ?: return null
        val fb = formOf(ev, b) ?: return null
        return when {
            fa is Line && fb is Line -> GeomMath.intersectLL(fa, fb).points
            fa is Line && fb is Circle -> GeomMath.intersectLC(fa, fb).points
            fa is Circle && fb is Line -> GeomMath.intersectLC(fb, fa).points
            fa is Circle && fb is Circle -> GeomMath.intersectCC(fa, fb).points
            else -> null
        }
    }

    private fun projection(
        ev: Evaluator,
        el: Element,
        world: Vec2,
    ): Vec2? =
        when (val f = formOf(ev, el)) {
            is Line -> f.origin + f.dir * (world - f.origin).dot(f.dir)
            is Circle -> {
                val d = world - f.center
                if (d.length() < Vec2.EPS) f.center + Vec2(f.radius, 0.0) else f.center + d * (f.radius / d.length())
            }
            else -> null
        }
}
