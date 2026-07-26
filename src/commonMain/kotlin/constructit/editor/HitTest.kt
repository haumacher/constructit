package constructit.editor

import constructit.core.ArcValue
import constructit.core.BezierValue
import constructit.core.CircleValue
import constructit.core.Evaluator
import constructit.core.LineValue
import constructit.core.LoopValue
import constructit.core.PointSetValue
import constructit.core.PointValue
import constructit.core.RayValue
import constructit.core.RegionValue
import constructit.core.SegmentValue
import constructit.core.SolidValue
import constructit.dsl.valueOf
import constructit.geom.Arc
import constructit.geom.GeomMath
import constructit.geom.ProfileElement
import constructit.geom.Vec2
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Picking: **one** distance rule ([distanceTo]) and **one** search over it ([nearestAll]); everything
 * else here is a filter. Placement, dragging, the weld/attach magnet and the snap resolver all go
 * through it, so a world position is near a segment in exactly one sense — clamped to the segment
 * itself, never to its infinite carrier.
 */
object HitTest {
    /** Draggable points only — the pick for a drag in SELECT mode. */
    fun nearestFreePoint(
        doc: Document,
        ev: Evaluator,
        world: Vec2,
        tol: Double,
    ): Element? = nearest(doc, ev, world, tol) { it.isPoint && it.draggable }

    /**
     * Nearest curve that is itself draggable — an ortho leg. Only consulted after
     * [nearestFreePoint] misses, so a vertex always wins over the legs meeting at it.
     */
    fun nearestDraggableCurve(
        doc: Document,
        ev: Evaluator,
        world: Vec2,
        tol: Double,
    ): Element? = nearest(doc, ev, world, tol) { it.isCurve && it.hasFreeDof }

    /**
     * Nearest annotation whose own DOF a drag can write — a dimension's offset (OP-13). Consulted after
     * points and curves, so a dimension crossing the geometry it names never steals a grab from it.
     */
    fun nearestDraggableAnnotation(
        doc: Document,
        ev: Evaluator,
        world: Vec2,
        tol: Double,
    ): Element? = nearest(doc, ev, world, tol) { it.annotation != null && it.hasFreeDof }

    /** Nearest element a pointer can address at all, movable or not — for selecting and explaining. */
    fun nearestSelectable(
        doc: Document,
        ev: Evaluator,
        world: Vec2,
        tol: Double,
    ): Element? = nearest(doc, ev, world, tol) { it.selectable }

    /** Nearest point-like element (free, derived, or on-curve), for snapping/reuse. */
    fun nearestAnyPoint(
        doc: Document,
        ev: Evaluator,
        world: Vec2,
        tol: Double,
    ): Element? = nearest(doc, ev, world, tol) { it.isPoint }

    /**
     * Screen-independent distance from [world] to [el]'s geometry, or null if it has no distance
     * (an invalid node, or a value kind that isn't pickable).
     */
    fun distanceTo(
        ev: Evaluator,
        el: Element,
        world: Vec2,
    ): Double? {
        // An annotation's value is a scalar, so it has no geometry of its own to measure against: what is
        // pickable is the graphic it draws (OP-4). Measured to that, a dimension is picked exactly where it
        // is visible — its lines, its arc, and the number itself.
        el.annotation?.let { a -> return a.graphic(ev)?.let { distanceToGraphic(world, it) } }
        return distanceToValue(ev, el, world)
    }

    private fun distanceToGraphic(
        world: Vec2,
        g: DimensionGraphic,
    ): Double =
        (
            g.lines.map { distToSegment(world, it.a, it.b) } +
                listOfNotNull(g.arc?.let { distToArc(world, it) }) +
                listOf((g.textAt - world).length())
        ).min()

    private fun distanceToValue(
        ev: Evaluator,
        el: Element,
        world: Vec2,
    ): Double? =
        when (val v = ev.valueOf(el.ref)) {
            is PointValue -> (v.p - world).length()
            is LineValue -> abs((world - v.line.origin).cross(v.line.dir))
            is CircleValue -> abs((world - v.circle.center).length() - v.circle.radius)
            is SegmentValue -> distToSegment(world, v.seg.a, v.seg.b)
            is ArcValue -> distToArc(world, v.arc)
            // A Bézier is measured against its own tessellation — the same polyline the renderer
            // draws, so what looks near the curve is near it.
            is BezierValue ->
                GeomMath.tessellateBezier(v.bezier).zipWithNext().minOfOrNull { (a, b) -> distToSegment(world, a, b) }
            is LoopValue -> v.loop.elements.minOfOrNull { distToPiece(world, it) }
            is RegionValue ->
                (v.region.outer.elements + v.region.holes.flatMap { it.elements })
                    .minOfOrNull { distToPiece(world, it) }
            // A solid is picked by the footprint hint the renderer draws for it (OP-17) — measured to the
            // same geometry, so what looks pickable is pickable. Its mesh is not consulted: there is no
            // 3D picking in this slice, and a 2D distance to a projected mesh would depend on the 3D camera.
            is SolidValue ->
                v.solid.feature.footprint
                    .flatMap { r -> r.outer.elements + r.holes.flatMap { it.elements } }
                    .minOfOrNull { distToPiece(world, it) }
            else -> null
        }

    /**
     * Distance to [el] **as the document's active sketch space shows it** (OP-17) — the one place the
     * space enters picking. For everything drawn in the space that is its own geometry; for the solid a
     * *face* space was cut from it is the face rectangle, which is what that solid looks like there.
     */
    private fun distanceIn(
        doc: Document,
        ev: Evaluator,
        el: Element,
        world: Vec2,
        tip: Element?,
    ): Double? = doc.faceOutlineOf(el, ev, tip)?.let { ringDistance(world, it) } ?: distanceTo(ev, el, world)

    private fun ringDistance(
        world: Vec2,
        ring: List<Vec2>,
    ): Double = ring.indices.minOf { distToSegment(world, ring[it], ring[(it + 1) % ring.size]) }

    /**
     * Every visible element satisfying [filter] within [tol] of [world], nearest first; ties go to the
     * most recently created, which is the one drawn on top.
     *
     * Only elements the **active sketch space** addresses take part (OP-17, [Document.addressableIn]): a
     * plan element is not pickable while a face is being sketched on, and vice versa — the coordinates
     * would not even mean the same thing.
     */
    fun nearestAll(
        doc: Document,
        ev: Evaluator,
        world: Vec2,
        tol: Double,
        filter: (Element) -> Boolean,
    ): List<Pair<Element, Double>> {
        // the part the active face space belongs to, as it stands (OP-17's tip rule) — resolved once per
        // search, because resolving it walks the graph and this asks it of every element
        val tip = doc.facePartTip(ev)
        return doc.elements
            .asSequence()
            .withIndex()
            .filter { (_, el) -> el.visible && doc.addressableIn(el, tip) && filter(el) }
            .mapNotNull { (i, el) -> distanceIn(doc, ev, el, world, tip)?.let { Triple(el, it, i) } }
            .filter { it.second <= tol }
            .sortedWith(compareBy({ it.second }, { -it.third }))
            .map { it.first to it.second }
            .toList()
    }

    /** Nearest element (point or curve) satisfying [filter], within [tol]. */
    fun nearest(
        doc: Document,
        ev: Evaluator,
        world: Vec2,
        tol: Double,
        filter: (Element) -> Boolean,
    ): Element? = nearestAll(doc, ev, world, tol, filter).firstOrNull()?.first

    fun nearestCurve(
        doc: Document,
        ev: Evaluator,
        world: Vec2,
        tol: Double,
    ): Element? = nearest(doc, ev, world, tol) { it.isCurve }

    // ---- marquee: pick everything a rectangle meets (OP-16) ----

    /**
     * Every visible element whose geometry meets the world rectangle spanned by [a] and [b] — what a
     * rubber-band drag in SELECT mode selects. *Meets*, not *contains*: an architect rubber-bands over
     * a room to grab the walls crossing it, and requiring full containment would drop every one of them.
     */
    fun within(
        doc: Document,
        ev: Evaluator,
        a: Vec2,
        b: Vec2,
    ): List<Element> {
        val lo = Vec2(kotlin.math.min(a.x, b.x), kotlin.math.min(a.y, b.y))
        val hi = Vec2(kotlin.math.max(a.x, b.x), kotlin.math.max(a.y, b.y))
        val tip = doc.facePartTip(ev)
        return doc.elements.filter { el ->
            el.visible && doc.addressableIn(el, tip) &&
                (doc.faceOutlineOf(el, ev, tip)?.let { r -> ringMeets(r, lo, hi) } ?: meetsRect(ev, el, lo, hi))
        }
    }

    private fun ringMeets(
        ring: List<Vec2>,
        lo: Vec2,
        hi: Vec2,
    ): Boolean = ring.indices.any { spanMeets(ring[it], ring[(it + 1) % ring.size] - ring[it], lo, hi, 0.0, 1.0) }

    /**
     * Whether [el]'s geometry meets the axis-aligned rectangle [lo]..[hi]. The dispatch mirrors
     * [distanceTo] — the same kinds, the same approximations: a Bézier or an arc is measured against the
     * polyline the renderer draws, so what the marquee visibly covers is what it takes.
     */
    private fun meetsRect(
        ev: Evaluator,
        el: Element,
        lo: Vec2,
        hi: Vec2,
    ): Boolean {
        el.annotation?.let { a ->
            val g = a.graphic(ev) ?: return false
            return g.lines.any { spanMeets(it.a, it.b - it.a, lo, hi, 0.0, 1.0) } ||
                (g.arc?.let { polyMeets(SceneRenderer.tessellate(it), lo, hi) } ?: false) ||
                inRect(g.textAt, lo, hi)
        }
        return meetsRectValue(ev, el, lo, hi)
    }

    private fun meetsRectValue(
        ev: Evaluator,
        el: Element,
        lo: Vec2,
        hi: Vec2,
    ): Boolean =
        when (val v = ev.valueOf(el.ref)) {
            is PointValue -> inRect(v.p, lo, hi)
            is SegmentValue -> spanMeets(v.seg.a, v.seg.b - v.seg.a, lo, hi, 0.0, 1.0)
            is LineValue -> spanMeets(v.line.origin, v.line.dir, lo, hi, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
            is RayValue -> spanMeets(v.ray.origin, v.ray.dir, lo, hi, 0.0, Double.POSITIVE_INFINITY)
            is CircleValue -> circleMeets(v.circle.center, v.circle.radius, lo, hi)
            is ArcValue -> polyMeets(SceneRenderer.tessellate(v.arc), lo, hi)
            is BezierValue -> polyMeets(GeomMath.tessellateBezier(v.bezier), lo, hi)
            is LoopValue -> v.loop.elements.any { pieceMeets(it, lo, hi) }
            is RegionValue ->
                v.region.outer.elements.any { pieceMeets(it, lo, hi) } ||
                    v.region.holes.any { h -> h.elements.any { pieceMeets(it, lo, hi) } }
            // the same footprint hint the pick uses, so a marquee takes what it visibly covers
            is SolidValue ->
                v.solid.feature.footprint.any { r ->
                    r.outer.elements.any { pieceMeets(it, lo, hi) } ||
                        r.holes.any { h -> h.elements.any { pieceMeets(it, lo, hi) } }
                }
            is PointSetValue -> v.set.points.any { inRect(it, lo, hi) }
            else -> false
        }

    private fun inRect(
        p: Vec2,
        lo: Vec2,
        hi: Vec2,
    ): Boolean = p.x >= lo.x && p.x <= hi.x && p.y >= lo.y && p.y <= hi.y

    /**
     * Slab clip of the span `origin + t*dir`, `t` in [tLo]..[tHi], against the rectangle: a segment
     * (0..1), a ray (0..inf) and a line (-inf..inf) differ only in that range.
     */
    private fun spanMeets(
        origin: Vec2,
        dir: Vec2,
        lo: Vec2,
        hi: Vec2,
        tLo: Double,
        tHi: Double,
    ): Boolean {
        var tMin = tLo
        var tMax = tHi
        for (axis in 0..1) {
            val d = if (axis == 0) dir.x else dir.y
            val o = if (axis == 0) origin.x else origin.y
            val l = if (axis == 0) lo.x else lo.y
            val h = if (axis == 0) hi.x else hi.y
            if (abs(d) < Vec2.EPS) {
                if (o < l || o > h) return false
            } else {
                val t1 = (l - o) / d
                val t2 = (h - o) / d
                tMin = kotlin.math.max(tMin, kotlin.math.min(t1, t2))
                tMax = kotlin.math.min(tMax, kotlin.math.max(t1, t2))
            }
        }
        return tMin <= tMax
    }

    /**
     * A circle *outline* meets the rectangle when the rectangle reaches the circle and is not swallowed
     * by it: the nearest point of the rectangle is no further than the radius, the farthest no nearer.
     */
    private fun circleMeets(
        center: Vec2,
        radius: Double,
        lo: Vec2,
        hi: Vec2,
    ): Boolean {
        val near = Vec2(center.x.coerceIn(lo.x, hi.x), center.y.coerceIn(lo.y, hi.y))
        val far =
            listOf(lo, Vec2(hi.x, lo.y), hi, Vec2(lo.x, hi.y)).maxOf { (it - center).length() }
        return (near - center).length() <= radius && far >= radius
    }

    private fun polyMeets(
        pts: List<Vec2>,
        lo: Vec2,
        hi: Vec2,
    ): Boolean = pts.zipWithNext().any { (a, b) -> spanMeets(a, b - a, lo, hi, 0.0, 1.0) }

    private fun pieceMeets(
        e: ProfileElement,
        lo: Vec2,
        hi: Vec2,
    ): Boolean =
        when (e) {
            is ProfileElement.Seg -> spanMeets(e.segment.a, e.segment.b - e.segment.a, lo, hi, 0.0, 1.0)
            is ProfileElement.ArcE -> polyMeets(SceneRenderer.tessellate(e.arc), lo, hi)
            is ProfileElement.CircleE -> circleMeets(e.circle.center, e.circle.radius, lo, hi)
            is ProfileElement.BezierE -> polyMeets(GeomMath.tessellateBezier(e.bezier), lo, hi)
        }

    /**
     * Distance to one boundary piece — the same rule an outline is picked by, exposed because naming a
     * *side face* is naming a footprint edge (OP-17), and that pick must measure exactly as this one does.
     */
    fun distanceToPiece(
        world: Vec2,
        e: ProfileElement,
    ): Double = distToPiece(world, e)

    /** Distance to one boundary piece, so an outline is pickable as a whole. */
    private fun distToPiece(
        world: Vec2,
        e: ProfileElement,
    ): Double =
        when (e) {
            is ProfileElement.Seg -> distToSegment(world, e.segment.a, e.segment.b)
            is ProfileElement.ArcE -> distToArc(world, e.arc)
            is ProfileElement.CircleE -> abs((world - e.circle.center).length() - e.circle.radius)
            is ProfileElement.BezierE ->
                GeomMath.tessellateBezier(e.bezier).zipWithNext().minOf { (a, b) -> distToSegment(world, a, b) }
        }

    private fun distToSegment(
        p: Vec2,
        a: Vec2,
        b: Vec2,
    ): Double {
        val ab = b - a
        val t = if (ab.length() < Vec2.EPS) 0.0 else ((p - a).dot(ab) / ab.dot(ab)).coerceIn(0.0, 1.0)
        return (p - (a + ab * t)).length()
    }

    /** Distance to an arc: to the circle if the point's angle is within the sweep, else to the nearer end. */
    private fun distToArc(
        p: Vec2,
        arc: Arc,
    ): Double {
        val to = p - arc.center
        return if (angleInSweep(atan2(to.y, to.x), arc)) {
            abs(to.length() - arc.radius)
        } else {
            minOf((p - arcPoint(arc, arc.startAngle)).length(), (p - arcPoint(arc, arc.endAngle)).length())
        }
    }

    private fun arcPoint(
        arc: Arc,
        ang: Double,
    ) = arc.center + Vec2(arc.radius * cos(ang), arc.radius * sin(ang))

    private fun angleInSweep(
        ang: Double,
        arc: Arc,
    ): Boolean {
        val twoPi = 2 * kotlin.math.PI

        fun norm(x: Double): Double {
            var r = x % twoPi
            if (r < 0) r += twoPi
            return r
        }
        val sweep = if (arc.ccw) norm(arc.endAngle - arc.startAngle) else norm(arc.startAngle - arc.endAngle)
        val rel = if (arc.ccw) norm(ang - arc.startAngle) else norm(arc.startAngle - ang)
        return rel <= sweep
    }
}
