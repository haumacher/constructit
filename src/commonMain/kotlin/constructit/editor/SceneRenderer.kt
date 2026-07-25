package constructit.editor

import constructit.core.ArcValue
import constructit.core.BezierValue
import constructit.core.CircleValue
import constructit.core.Evaluator
import constructit.core.FrameValue
import constructit.core.LineValue
import constructit.core.LoopValue
import constructit.core.PointSetValue
import constructit.core.PointValue
import constructit.core.RayValue
import constructit.core.RegionValue
import constructit.core.SegmentValue
import constructit.dsl.valueOf
import constructit.geom.Arc
import constructit.geom.GeomMath
import constructit.geom.Line
import constructit.geom.ProfileElement
import constructit.geom.Ray
import constructit.geom.Segment
import constructit.geom.Vec2
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Draws a [Document] to a [DrawTarget] through a [Camera]. Pure: projects world->screen,
 * tessellates arcs to polylines, clips infinite lines/rays to the viewport. Invalid nodes
 * (OP-3) simply produce no value and are skipped (hidden).
 */
object SceneRenderer {
    private const val POINT_PX = 4.0
    private const val TWO_PI = 2.0 * kotlin.math.PI

    private val haloOuter = Style("#ff7f0e", 2.0)
    private val haloInner = Style("#ff7f0e", 1.0)

    private val previewStyle = Style("#ff7f0e", 1.5)

    private val snapStyle = Style("#d62728", 1.5)

    private val joinStyle = Style("#9467bd", 2.0)

    private val selectionStyle = Style("#1f77b4", 3.0)
    private val selectionRing = Style("#1f77b4", 1.5)

    private val marqueeStyle = Style("#1f77b4", 1.0)

    /** A placed group's frame marker (OP-16): its own axes, in the group's orientation. */
    private val frameStyle = Style("#8c564b", 1.4)

    /** Screen length of a drawn frame axis — a marker, so it does not scale with the drawing. */
    private const val FRAME_AXIS_PX = 22.0

    fun render(
        doc: Document,
        ev: Evaluator,
        cam: Camera,
        target: DrawTarget,
        wPx: Double,
        hPx: Double,
        grid: Boolean = false,
        highlight: Vec2? = null,
        preview: Pair<Vec2, Vec2>? = null,
        selected: Set<Element> = emptySet(),
        snap: Vec2? = null,
        joins: List<Vec2> = emptyList(),
        closing: List<Pair<Vec2, Vec2>> = emptyList(),
        dimmed: Set<Element> = emptySet(),
        marquee: Pair<Vec2, Vec2>? = null,
        frames: List<FrameValue> = emptyList(),
    ) {
        target.begin(wPx, hPx)
        val view = worldViewRect(cam, wPx, hPx)
        if (grid) drawGrid(cam, target, view)
        for (el in doc.elements) {
            if (!el.visible) continue
            val style = if (el in dimmed) Styles.DIMMED else el.style
            when (val v = ev.valueOf(el.ref)) {
                is PointValue -> target.dot(cam.worldToScreen(v.p), POINT_PX, style.stroke)
                is SegmentValue -> target.polyline(listOf(cam.worldToScreen(v.seg.a), cam.worldToScreen(v.seg.b)), style)
                is LineValue -> clipLine(v.line, view)?.let { target.polyline(listOf(cam.worldToScreen(it.a), cam.worldToScreen(it.b)), style) }
                is RayValue -> clipRay(v.ray, view)?.let { target.polyline(listOf(cam.worldToScreen(it.a), cam.worldToScreen(it.b)), style) }
                is CircleValue -> target.circle(cam.worldToScreen(v.circle.center), v.circle.radius * cam.scale, style)
                is ArcValue -> target.polyline(tessellate(v.arc).map { cam.worldToScreen(it) }, style)
                is BezierValue -> target.polyline(GeomMath.tessellateBezier(v.bezier).map { cam.worldToScreen(it) }, style)
                is LoopValue -> drawChain(v.loop.elements, cam, target, style)
                is RegionValue -> {
                    // A thick path's footprint is drawn by the *plan convention* (OP-21): faces broken at
                    // every interval, jamb lines across them. The region itself stays whole — an opening
                    // does not interrupt the material — so the break lives here, in the drawing, and
                    // nowhere in the model.
                    val plan = doc.thickPathOf(el)?.let { doc.planOf(it, ev) }
                    if (plan != null) {
                        for (s in plan) target.polyline(listOf(cam.worldToScreen(s.a), cam.worldToScreen(s.b)), style)
                    } else {
                        drawChain(v.region.outer.elements, cam, target, style)
                        for (h in v.region.holes) drawChain(h.elements, cam, target, style)
                    }
                }
                is PointSetValue -> v.set.points.forEach { target.dot(cam.worldToScreen(it), POINT_PX, style.stroke) }
                else -> {}
            }
        }
        // the selection, redrawn on top: what delete removes and — when it is a single element — what
        // the inspector's numeric fields refer to. Every kind is highlighted, since a marquee (OP-16)
        // takes whatever it covers and a selection that shows only its points would be unreadable.
        for (el in selected) {
            if (!el.visible) continue
            when (val v = ev.valueOf(el.ref)) {
                is PointValue -> target.circle(cam.worldToScreen(v.p), 7.0, selectionRing)
                is SegmentValue -> target.polyline(listOf(cam.worldToScreen(v.seg.a), cam.worldToScreen(v.seg.b)), selectionStyle)
                is LineValue ->
                    clipLine(v.line, view)?.let { target.polyline(listOf(cam.worldToScreen(it.a), cam.worldToScreen(it.b)), selectionStyle) }
                is RayValue ->
                    clipRay(v.ray, view)?.let { target.polyline(listOf(cam.worldToScreen(it.a), cam.worldToScreen(it.b)), selectionStyle) }
                is CircleValue -> target.circle(cam.worldToScreen(v.circle.center), v.circle.radius * cam.scale, selectionStyle)
                is ArcValue -> target.polyline(tessellate(v.arc).map { cam.worldToScreen(it) }, selectionStyle)
                is BezierValue -> target.polyline(GeomMath.tessellateBezier(v.bezier).map { cam.worldToScreen(it) }, selectionStyle)
                is LoopValue -> drawChain(v.loop.elements, cam, target, selectionStyle)
                is RegionValue -> {
                    drawChain(v.region.outer.elements, cam, target, selectionStyle)
                    for (h in v.region.holes) drawChain(h.elements, cam, target, selectionStyle)
                }
                else -> {}
            }
        }
        // a selected placed group's frame (OP-16 step 2): its origin and axes, drawn in the group's own
        // orientation — modest, because it is not geometry, but visible, because it is what a drag writes
        for (f in frames) {
            val o = cam.worldToScreen(f.origin)
            val ax = cam.worldToScreen(f.toWorld(Vec2(FRAME_AXIS_PX / cam.scale, 0.0)))
            val ay = cam.worldToScreen(f.toWorld(Vec2(0.0, FRAME_AXIS_PX / cam.scale)))
            target.polyline(listOf(o, ax), frameStyle)
            target.polyline(listOf(o, ay), frameStyle)
            target.circle(o, 3.0, frameStyle)
        }
        // rubber band of a marquee in progress — the rectangle whose contents the release will select
        marquee?.let { (a, b) ->
            val p = cam.worldToScreen(a)
            val q = cam.worldToScreen(b)
            target.polyline(listOf(p, Vec2(q.x, p.y), q, Vec2(p.x, q.y), p), marqueeStyle)
        }
        // rubber-band preview of the next ortho-path leg
        preview?.let { target.polyline(listOf(cam.worldToScreen(it.first), cam.worldToScreen(it.second)), previewStyle) }
        // and of a *closed* loop: what the click will make, including the corner it moves into line
        for (seg in closing) {
            target.polyline(listOf(cam.worldToScreen(seg.first), cam.worldToScreen(seg.second)), previewStyle)
        }
        // snap marker: a small square where a placing click would land (and what it would link to)
        snap?.let {
            val c = cam.worldToScreen(it)
            val r = 5.0
            target.polyline(
                listOf(Vec2(c.x - r, c.y - r), Vec2(c.x + r, c.y - r), Vec2(c.x + r, c.y + r), Vec2(c.x - r, c.y + r), Vec2(c.x - r, c.y - r)),
                snapStyle,
            )
        }
        // corners a release would join away: crossed out, because that is what happens to them
        for (j in joins) {
            val c = cam.worldToScreen(j)
            val r = 6.0
            target.polyline(listOf(Vec2(c.x - r, c.y - r), Vec2(c.x + r, c.y + r)), joinStyle)
            target.polyline(listOf(Vec2(c.x - r, c.y + r), Vec2(c.x + r, c.y - r)), joinStyle)
            target.circle(c, r + 3.0, joinStyle)
        }
        // weld magnet: a double ring around the point a dragged point will snap/join onto
        highlight?.let {
            val s = cam.worldToScreen(it)
            target.circle(s, 11.0, haloOuter)
            target.circle(s, 6.0, haloInner)
        }
        target.end()
    }

    private fun norm2pi(a: Double): Double {
        var r = a % TWO_PI
        if (r < 0) r += TWO_PI
        return r
    }

    fun tessellate(arc: Arc): List<Vec2> {
        val sweep = if (arc.ccw) norm2pi(arc.endAngle - arc.startAngle) else -norm2pi(arc.startAngle - arc.endAngle)
        val n = max(6, ceil(abs(sweep) / TWO_PI * 64).toInt())
        return (0..n).map {
            val ang = arc.startAngle + sweep * it / n
            arc.center + Vec2(arc.radius * cos(ang), arc.radius * sin(ang))
        }
    }

    /**
     * Draw a boundary chain (a `Loop`, or a `Region`'s outer/hole loop) piece by piece. Each piece
     * becomes a polyline in screen space; the piece dispatch itself stays in `GeomMath`, except for
     * this one — emitting backend primitives is a rendering question, not a geometric one.
     */
    private fun drawChain(
        elements: List<ProfileElement>,
        cam: Camera,
        target: DrawTarget,
        style: Style,
    ) {
        for (el in elements) when (el) {
            is ProfileElement.Seg ->
                target.polyline(listOf(cam.worldToScreen(el.segment.a), cam.worldToScreen(el.segment.b)), style)
            is ProfileElement.ArcE -> target.polyline(tessellate(el.arc).map { cam.worldToScreen(it) }, style)
            is ProfileElement.BezierE ->
                target.polyline(GeomMath.tessellateBezier(el.bezier).map { cam.worldToScreen(it) }, style)
            is ProfileElement.CircleE ->
                target.circle(cam.worldToScreen(el.circle.center), el.circle.radius * cam.scale, style)
        }
    }

    private val gridStyle = Style("#eeeeee", 1.0)
    private val axisStyle = Style("#c8c8c8", 1.0)

    /** The world grid spacing in use at [scale] — also what a grid snap rounds to. */
    fun gridStep(scale: Double): Double = niceStep(scale)

    /** A "nice" world grid spacing (1/2/5 x 10^k mm) so screen spacing is roughly 40 px. */
    private fun niceStep(scale: Double): Double {
        val worldPerTarget = 40.0 / scale
        val mag = 10.0.pow(floor(log10(worldPerTarget)))
        val norm = worldPerTarget / mag
        val factor =
            if (norm < 2) {
                1.0
            } else if (norm < 5) {
                2.0
            } else {
                5.0
            }
        return factor * mag
    }

    private fun drawGrid(
        cam: Camera,
        target: DrawTarget,
        view: Rect,
    ) {
        val step = niceStep(cam.scale)
        var x = floor(view.lo.x / step) * step
        while (x <= view.hi.x) {
            val style = if (abs(x) < step * 0.5) axisStyle else gridStyle
            target.polyline(listOf(cam.worldToScreen(Vec2(x, view.lo.y)), cam.worldToScreen(Vec2(x, view.hi.y))), style)
            x += step
        }
        var y = floor(view.lo.y / step) * step
        while (y <= view.hi.y) {
            val style = if (abs(y) < step * 0.5) axisStyle else gridStyle
            target.polyline(listOf(cam.worldToScreen(Vec2(view.lo.x, y)), cam.worldToScreen(Vec2(view.hi.x, y))), style)
            y += step
        }
    }

    private data class Rect(val lo: Vec2, val hi: Vec2)

    private fun worldViewRect(
        cam: Camera,
        wPx: Double,
        hPx: Double,
    ): Rect {
        val a = cam.screenToWorld(Vec2(0.0, 0.0))
        val b = cam.screenToWorld(Vec2(wPx, hPx))
        return Rect(Vec2(min(a.x, b.x), min(a.y, b.y)), Vec2(max(a.x, b.x), max(a.y, b.y)))
    }

    private fun clipLine(
        line: Line,
        r: Rect,
    ): Segment? = clipParam(line.origin, line.dir, r, Double.NEGATIVE_INFINITY)

    private fun clipRay(
        ray: Ray,
        r: Rect,
    ): Segment? = clipParam(ray.origin, ray.dir, r, 0.0)

    private fun clipParam(
        o: Vec2,
        dir: Vec2,
        r: Rect,
        tStart: Double,
    ): Segment? {
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
                val t1 = (lo - oo) / od
                val t2 = (hi - oo) / od
                tMin = max(tMin, min(t1, t2))
                tMax = min(tMax, max(t1, t2))
            }
        }
        if (tMin > tMax) return null
        return Segment(o + dir * tMin, o + dir * tMax)
    }
}
