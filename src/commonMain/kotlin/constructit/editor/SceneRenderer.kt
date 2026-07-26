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
import constructit.core.ScalarValue
import constructit.core.SegmentValue
import constructit.core.SolidValue
import constructit.dsl.valueOf
import constructit.geom.Arc
import constructit.geom.GeomMath
import constructit.geom.Line
import constructit.geom.ProfileElement
import constructit.geom.Ray
import constructit.geom.Segment
import constructit.geom.Vec2
import kotlin.math.abs
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

    /** The mark on the vertex a finished run ended on — its own colour, so it reads as nothing else. */
    private val terminalStyle = Style("#17becf", 2.0)

    private val selectionStyle = Style("#1f77b4", 3.0)
    private val selectionRing = Style("#1f77b4", 1.5)

    /**
     * Geometry a tool has **picked** but not yet used (OP-14's Outline tool above all) — [Styles.PICKED],
     * in the document's palette because it is a role of an element and not a mark of the gesture.
     *
     * Same drawing vocabulary as the selection (the piece restated on top of itself), a different colour and
     * weight, so "these five curves are in" is legible at a glance on curves of every kind.
     */
    private val pickStyle = Styles.PICKED
    private val pickRing = Styles.PICKED.copy(width = 2.0)

    private val marqueeStyle = Style("#1f77b4", 1.0)

    /** A placed group's frame marker (OP-16): its own axes, in the group's orientation. */
    private val frameStyle = Style("#8c564b", 1.4)

    /** The outline of the face a sketch space sits on (OP-17): reference context, so grid-weight. */
    private val faceStyle = Style("#cfd8e3", 1.4)

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
        terminal: Vec2? = null,
        dimmed: Set<Element> = emptySet(),
        marquee: Pair<Vec2, Vec2>? = null,
        frames: List<FrameValue> = emptyList(),
        picked: Set<Element> = emptySet(),
        emphasis: List<Segment> = emptyList(),
        previews: List<PreviewShape> = emptyList(),
    ) {
        target.begin(wPx, hPx)
        val view = worldViewRect(cam, wPx, hPx)
        if (grid) drawGrid(cam, target, view)
        // Reference context of a sketch space (OP-17): a **face**'s rectangle, or a **datum**'s hinge line
        // (GitHub #6), in this space's own (u, v) — so the user can see where the plane *is* before drawing
        // on it. Drawn with the grid's weight because it is not this space's geometry; it is nevertheless
        // where a pick of the part this plane cuts lands ([Document.partOutlineOf]). Ghosting the *other*
        // spaces is deliberately not attempted (see DESIGN.md).
        val tip = doc.facePartTip(ev)
        doc.spaceOutline(doc.activeSpace, ev)?.let { r ->
            target.polyline((r + r.first()).map { cam.worldToScreen(it) }, faceStyle)
        }
        for (el in doc.elements) {
            if (!el.visible) continue
            // one canvas shows one space (OP-17): everything else belongs to a different coordinate system
            if (el.space != doc.activeSpace.name) continue
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
                // A solid's home is the 3D view; in plan it leaves a **footprint hint** — the boundary
                // of the areas its feature shows in plan, drawn light (OP-17). Not a projection of the mesh: a
                // shaded or hidden-line view is a *chosen* projection, which is the 3D view's job, and
                // the hint is what makes the solid pickable here — hence selectable and deletable in
                // the one view that has picking.
                is SolidValue -> drawFootprintHint(v, cam, target, style)
                is PointSetValue -> v.set.points.forEach { target.dot(cam.worldToScreen(it), POINT_PX, style.stroke) }
                // a dimension's value is a scalar (OP-4), so what is drawn is the graphic it prescribes
                is ScalarValue -> el.annotation?.let { drawDimension(it, ev, cam, target, style) }
                else -> {}
            }
        }
        // a selected dimension's own graphic on top, so the annotation being edited reads as picked
        for (el in selected) {
            if (!el.visible || el.space != doc.activeSpace.name) continue
            el.annotation?.let { drawDimension(it, ev, cam, target, selectionStyle, withText = false) }
        }
        // geometry an armed tool has picked, restated in the pick colour — so a boundary being traced shows
        // how much of it is already in, and a click that hit nothing is visibly a click that added nothing
        for (el in picked) emphasize(doc, el, ev, cam, target, view, pickStyle, pickRing, tip)
        // the selection, redrawn on top: what delete removes and — when it is a single element — what
        // the inspector's numeric fields refer to. Every kind is highlighted, since a marquee (OP-16)
        // takes whatever it covers and a selection that shows only its points would be unreadable.
        for (el in selected) emphasize(doc, el, ev, cam, target, view, selectionStyle, selectionRing, tip)
        // A selection that owns no element at all: an opening's jamb (OP-21) is part of the plan *drawing*,
        // so what the emphasis restates is that drawing — the two reveal lines and the gap between them.
        // Same vocabulary as every other selection (the piece drawn again on top of itself), which is what
        // makes "this opening is what the fields refer to" legible without a marker of its own.
        for (s in emphasis) target.polyline(listOf(cam.worldToScreen(s.a), cam.worldToScreen(s.b)), selectionStyle)
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
        // What the armed tool would build if the next click happened here (`ToolDef.preview`) — the ortho
        // band's own style, because it is the same promise about the same click, and drawn *before* the band
        // so the two can never disagree about who is on top.
        for (s in previews) drawPreview(s, cam, target, view)
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
        // The **terminus** of a run that just finished by reaching geometry: nested diamonds around the
        // vertex it ended on. One mark for every way a run can end on something — welded, attached or
        // closed — because what the user has to notice is that *drawing stopped here*, not which
        // construction stopped it. Diamonds, so it cannot be read as the snap marker (an axis-aligned
        // square) or as the magnet halo (rings), and pixel-sized like every other mark, since it is a
        // statement about the gesture rather than geometry.
        terminal?.let {
            val c = cam.worldToScreen(it)
            for (r in listOf(6.0, 10.0)) {
                target.polyline(
                    listOf(Vec2(c.x, c.y - r), Vec2(c.x + r, c.y), Vec2(c.x, c.y + r), Vec2(c.x - r, c.y), Vec2(c.x, c.y - r)),
                    terminalStyle,
                )
            }
        }
        // weld magnet: a double ring around the point a dragged point will snap/join onto
        highlight?.let {
            val s = cam.worldToScreen(it)
            target.circle(s, 11.0, haloOuter)
            target.circle(s, 6.0, haloInner)
        }
        target.end()
    }

    /**
     * One [PreviewShape] in the preview style — the whole of the rendering half of live tool previews.
     *
     * Every case is a call the renderer already makes for the corresponding *value* kind (an infinite line is
     * clipped, an arc is tessellated, a dimension goes through the same skeleton drawing), which is what
     * keeps the preview a picture of the same geometry rather than a second drawing vocabulary.
     */
    private fun drawPreview(
        s: PreviewShape,
        cam: Camera,
        target: DrawTarget,
        view: Rect,
    ) {
        when (s) {
            is PreviewShape.Dot -> target.dot(cam.worldToScreen(s.at), POINT_PX, previewStyle.stroke)
            is PreviewShape.Seg -> target.polyline(listOf(cam.worldToScreen(s.seg.a), cam.worldToScreen(s.seg.b)), previewStyle)
            is PreviewShape.Ln ->
                clipLine(s.line, view)?.let { target.polyline(listOf(cam.worldToScreen(it.a), cam.worldToScreen(it.b)), previewStyle) }
            is PreviewShape.Ry ->
                clipRay(s.ray, view)?.let { target.polyline(listOf(cam.worldToScreen(it.a), cam.worldToScreen(it.b)), previewStyle) }
            is PreviewShape.Circ -> target.circle(cam.worldToScreen(s.circle.center), s.circle.radius * cam.scale, previewStyle)
            is PreviewShape.ArcS -> target.polyline(tessellate(s.arc).map { cam.worldToScreen(it) }, previewStyle)
            is PreviewShape.Bez -> target.polyline(GeomMath.tessellateBezier(s.bezier).map { cam.worldToScreen(it) }, previewStyle)
            is PreviewShape.Path -> target.polyline(s.points.map { cam.worldToScreen(it) }, previewStyle)
            is PreviewShape.Dim -> drawGraphic(s.graphic, cam, target, previewStyle, withText = true)
        }
    }

    /**
     * Redraw [el] on top of itself in [style] — the one **emphasis** vocabulary, used for a selection and
     * for a tool's picks alike (a point gets a ring in [ringStyle] instead, since a fatter dot reads as a
     * different point). Every value kind is covered, because a marquee or a trace takes whatever it covers
     * and an emphasis that skipped kinds would be read as "that one is not in".
     */
    private fun emphasize(
        doc: Document,
        el: Element,
        ev: Evaluator,
        cam: Camera,
        target: DrawTarget,
        view: Rect,
        style: Style,
        ringStyle: Style,
        tip: Element?,
    ) {
        if (!el.visible) return
        // the same substitution picking makes (OP-17): in a face space the part that face belongs to *is*
        // the face rectangle, so that is what a pick of it highlights
        doc.partOutlineOf(el, ev, tip)?.let { r ->
            target.polyline((r + r.first()).map { cam.worldToScreen(it) }, style)
            return
        }
        if (el.space != doc.activeSpace.name) return
        when (val v = ev.valueOf(el.ref)) {
            is PointValue -> target.circle(cam.worldToScreen(v.p), 7.0, ringStyle)
            is SegmentValue -> target.polyline(listOf(cam.worldToScreen(v.seg.a), cam.worldToScreen(v.seg.b)), style)
            is LineValue ->
                clipLine(v.line, view)?.let { target.polyline(listOf(cam.worldToScreen(it.a), cam.worldToScreen(it.b)), style) }
            is RayValue ->
                clipRay(v.ray, view)?.let { target.polyline(listOf(cam.worldToScreen(it.a), cam.worldToScreen(it.b)), style) }
            is CircleValue -> target.circle(cam.worldToScreen(v.circle.center), v.circle.radius * cam.scale, style)
            is ArcValue -> target.polyline(tessellate(v.arc).map { cam.worldToScreen(it) }, style)
            is BezierValue -> target.polyline(GeomMath.tessellateBezier(v.bezier).map { cam.worldToScreen(it) }, style)
            is LoopValue -> drawChain(v.loop.elements, cam, target, style)
            is RegionValue -> {
                drawChain(v.region.outer.elements, cam, target, style)
                for (h in v.region.holes) drawChain(h.elements, cam, target, style)
            }
            else -> {}
        }
    }

    /**
     * A solid's footprint hint: the loops of whatever its feature shows in plan (`Feature3.footprint`) —
     * the sketch it was swept from, or, for a boolean, the outline of every slab of the prism, so a
     * counterbore and a cut opening are visible in plan without inventing a projection (OP-22).
     *
     * The sketch's own 2D coordinates are used directly, and with sketch spaces (OP-17) that is exact
     * rather than a caveat: a solid is drawn in **the space its sketch was drawn in**, whose coordinates
     * *are* those of its plane. A drill sketched on a face therefore shows its circle in the face view,
     * where it belongs, instead of being projected into a plan it has no honest projection into.
     */
    private fun drawFootprintHint(
        v: SolidValue,
        cam: Camera,
        target: DrawTarget,
        style: Style,
    ) {
        for (region in v.solid.feature.footprint) {
            drawChain(region.outer.elements, cam, target, style)
            for (h in region.holes) drawChain(h.elements, cam, target, style)
        }
    }

    /** Screen length of an arrowhead's barbs, and their half-angle: a drawing mark, so it never scales. */
    private const val ARROW_PX = 9.0
    private const val ARROW_SPREAD = 0.3

    /** How far the value text is lifted off its own dimension line, in pixels. */
    private const val TEXT_GAP_PX = 4.0

    /**
     * Draw a dimension (OP-4): its world-space skeleton through the camera, plus the two things that must
     * *not* scale with the drawing — arrowheads and the value text, both sized in pixels here, which is
     * the only layer that knows about pixels.
     */
    private fun drawDimension(
        ann: DimensionAnnotation,
        ev: Evaluator,
        cam: Camera,
        target: DrawTarget,
        style: Style,
        withText: Boolean = true,
    ) {
        drawGraphic(ann.graphic(ev) ?: return, cam, target, style, withText)
    }

    /**
     * A dimension's world-space skeleton on screen. Taken apart from [drawDimension] so a **previewed**
     * dimension (`PreviewShape.Dim`, which owns no annotation and no node) is drawn by exactly the same
     * code as a placed one.
     */
    private fun drawGraphic(
        g: DimensionGraphic,
        cam: Camera,
        target: DrawTarget,
        style: Style,
        withText: Boolean,
    ) {
        for (s in g.lines) target.polyline(listOf(cam.worldToScreen(s.a), cam.worldToScreen(s.b)), style)
        g.arc?.let { arc -> target.polyline(tessellate(arc).map { cam.worldToScreen(it) }, style) }
        for (a in g.arrows) drawArrow(cam.worldToScreen(a.tip), screenDir(cam, a.tip, a.along), target, style)
        if (!withText) return
        target.text(cam.worldToScreen(g.textAt) + screenDir(cam, g.textAt, g.textUp) * TEXT_GAP_PX, g.text, style, g.textAnchor)
    }

    /** A world direction at [from] as a unit *screen* direction — the camera's y flip included. */
    private fun screenDir(
        cam: Camera,
        from: Vec2,
        worldDir: Vec2,
    ): Vec2 = (cam.worldToScreen(from + worldDir) - cam.worldToScreen(from)).normalized()

    /** An open arrowhead at [tip] pointing [along] (a unit screen direction): two barbs, no fill. */
    private fun drawArrow(
        tip: Vec2,
        along: Vec2,
        target: DrawTarget,
        style: Style,
    ) {
        if (along.length() < Vec2.EPS) return
        val back = -along
        target.polyline(
            listOf(tip + rotate(back, ARROW_SPREAD) * ARROW_PX, tip, tip + rotate(back, -ARROW_SPREAD) * ARROW_PX),
            style,
        )
    }

    private fun rotate(
        v: Vec2,
        a: Double,
    ) = Vec2(v.x * cos(a) - v.y * sin(a), v.x * sin(a) + v.y * cos(a))

    /**
     * An arc as a screen-ready polyline. The step count is the renderer's own (fixed per full turn, so
     * goldens do not depend on the camera); the sampling itself is `GeomMath`'s, shared with the
     * world-space tolerance-driven tessellation the 3D layer needs (OP-17) — one place for the maths,
     * two policies for how finely to apply it.
     */
    fun tessellate(arc: Arc): List<Vec2> = GeomMath.sampleArc(arc, GeomMath.renderArcSteps(arc))

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
