package constructit.editor

import constructit.core.CircleValue
import constructit.core.EllipseValue
import constructit.core.Evaluator
import constructit.core.LineValue
import constructit.core.PointValue
import constructit.core.RayValue
import constructit.core.SegmentValue
import constructit.dsl.valueOf
import constructit.geom.Circle
import constructit.geom.Conics
import constructit.geom.Ellipse
import constructit.geom.GeomMath
import constructit.geom.Line
import constructit.geom.Vec2
import kotlin.math.round

/** What a click landed on. Everything but [GRID] and [FREE] makes the new point *depend* on geometry. */
enum class SnapKind {
    POINT,
    INTERSECTION,
    ON_CURVE,

    /**
     * A **corner of the working plane's section** (OP-17's section inputs): the part's own geometry, as a
     * place to draw from. It ranks with an existing point, because that is what it becomes — placing here
     * materializes the section's corner accessor and the new geometry hangs off the solid and the plane.
     */
    SECTION_CORNER,
    GRID,
    FREE,
}

/**
 * Where a click lands and what it landed on: [target] is the point to reuse or the curve to attach
 * to, and [other] is the second curve of an [SnapKind.INTERSECTION].
 */
class SnapResult(
    val pos: Vec2,
    val kind: SnapKind,
    val target: Element? = null,
    val other: Element? = null,
    /** [SnapKind.SECTION_CORNER] only: which corner of the section this is — the index that gets recorded. */
    val sectionCorner: Int = -1,
    /**
     * [SnapKind.SECTION_CORNER] only: **which solid's** section that index addresses (GitHub #9). A working
     * plane cuts every solid built before it, so an index alone no longer names a corner — and dropping the
     * solid here would materialize the anchor's corner of the same number instead of the one under the
     * cursor.
     */
    val sectionSolid: Element? = null,
) {
    /** Name of the snap, for the status bar. */
    val label: String
        get() =
            when (kind) {
                SnapKind.POINT -> "existing point"
                SnapKind.INTERSECTION -> "intersection"
                SnapKind.ON_CURVE -> "on curve"
                SnapKind.SECTION_CORNER -> "section corner"
                SnapKind.GRID -> "grid"
                SnapKind.FREE -> ""
            }

    /** True when placing here creates a dependency rather than a free point. */
    val linked: Boolean get() =
        kind == SnapKind.POINT || kind == SnapKind.INTERSECTION || kind == SnapKind.ON_CURVE ||
            kind == SnapKind.SECTION_CORNER
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
        /**
         * Geometry to ignore — the thing being placed and anything it is part of. Snapping onto your
         * own construction could only ever be refused as a cycle, so it must not be offered.
         */
        exclude: (Element) -> Boolean = { false },
    ): SnapResult {
        HitTest.nearestAnyPoint(doc, ev, world, tol)?.let { el ->
            if (!exclude(el)) {
                val p = (ev.valueOf(el.ref) as? PointValue)?.p
                if (p != null) return SnapResult(p, SnapKind.POINT, el)
            }
        }

        // A corner of the working plane's **section** ranks next: it is not an element yet, but placing here
        // makes one (the corner accessor), so it produces a real dependency exactly as an existing point does
        // — which is the rule this whole resolution is about (OP-17's section inputs).
        doc.sectionCandidateNear(world, tol, ev, Document.SectionInput.CORNER)?.let { c ->
            if (c.refusal == null) {
                return SnapResult(c.at, SnapKind.SECTION_CORNER, sectionCorner = c.index, sectionSolid = c.solid)
            }
        }

        // the attachable curves under the cursor, nearest first — the shared search, so "near a segment"
        // means the same here as everywhere else
        val near = HitTest.nearestAll(doc, ev, world, tol) { !exclude(it) && attachable(it) }

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

    /**
     * Where an axis-aligned leg leaving [from] along [axis] (0 = horizontal, 1 = vertical) meets
     * [curve], taking the crossing nearest [near].
     *
     * This is where an ortho leg genuinely *ends* when snapped to a curve: the leg cannot bend to
     * reach the cursor's projection, it runs on until it hits — and it is the same endpoint
     * [Document.attachOrthoEndpointToCurve] then derives, so the preview matches what gets built.
     */
    fun axisCrossing(
        ev: Evaluator,
        curve: Element,
        from: Vec2,
        axis: Int,
        near: Vec2,
    ): Vec2? {
        val axisLine = Line(from, if (axis == 0) Vec2(1.0, 0.0) else Vec2(0.0, 1.0))
        val points =
            when (val f = formOf(ev, curve)) {
                is Line -> GeomMath.intersectLL(axisLine, f).points
                is Circle -> GeomMath.intersectLC(axisLine, f).points
                is Ellipse -> Conics.intersectLE(axisLine, f).points
                else -> null
            } ?: return null
        return points.minByOrNull { (it - near).length() }
    }

    /** Where [world] lands on [curve] itself — the point a click on a segment refers to. */
    fun legPoint(
        ev: Evaluator,
        curve: Element,
        world: Vec2,
    ): Vec2? = projection(ev, curve, world)

    /** Lines, segments, rays, circles and ellipses can carry a point; arcs can't yet (no carrier circle). */
    private fun attachable(el: Element) = el.isLinear || el.kind == ElementKind.CIRCLE || el.kind == ElementKind.ELLIPSE

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
            is EllipseValue -> v.ellipse
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
            // the conic cases (OP-24) — the very same solvers the intersection *op* uses, run one frame
            // early on values, so what the snap offers is what the click would build
            fa is Line && fb is Ellipse -> Conics.intersectLE(fa, fb).points
            fa is Ellipse && fb is Line -> Conics.intersectLE(fb, fa).points
            fa is Circle && fb is Ellipse -> Conics.intersect(Conics.ofCircle(fa), fb).points
            fa is Ellipse && fb is Circle -> Conics.intersect(fa, Conics.ofCircle(fb)).points
            fa is Ellipse && fb is Ellipse -> Conics.intersect(fa, fb).points
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
            is Ellipse -> Conics.nearestPoint(f, world)
            else -> null
        }
}
