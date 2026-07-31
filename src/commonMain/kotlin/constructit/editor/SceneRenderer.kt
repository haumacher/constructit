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
 * Draws a [Document] to a [DrawTarget] through a [PlaneProjection]. Pure: projects the active space's
 * plane coordinates to screen, tessellates arcs to polylines, clips infinite lines/rays to the viewport.
 * Invalid nodes (OP-3) simply produce no value and are skipped (hidden).
 *
 * **One renderer, two projections** (edit-in-3D slice 1). The projection used to be the 2D [Camera], and
 * the whole of the generalization is that every world→screen step now goes through the interface: the
 * canvas passes its camera (a similarity — the exact case, and the one every golden covers), while the 3D
 * view passes a [PlanePerspective] and gets the very same drawing laid onto the working plane in the 3D
 * scene, arcs tessellated in plane space and projected vertex by vertex. Nothing was forked, because
 * *what* is drawn — every element of the active space, the selection, the tool's previews, the snap
 * markers, the section-inputs context — is not a question a projection has any part in.
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

    /**
     * **What the selection is built from, and what is built on it** (see [Dependencies]) — two more colours
     * in the emphasis vocabulary, because the answer to "which point is this circle's centre?" has to be
     * *pointed at*, not merely listed.
     *
     * Deliberately distinct from the selection's blue and from the pick colour, and deliberately lighter
     * than both: these elements are context for the one that is selected, so they must not compete with it.
     */
    private val inputStyle = Style("#2ca02c", 2.0)
    private val inputRing = Style("#2ca02c", 1.4)
    private val dependentStyle = Style("#bcbd22", 2.0)
    private val dependentRing = Style("#bcbd22", 1.4)

    /**
     * The element the pointer is over **in the inspector's own lists** — a transient spotlight, drawn last
     * so it wins over everything: hovering a name in "built from" has to say *that one*, immediately.
     */
    private val spotlightStyle = Style("#ff7f0e", 3.0)
    private val spotlightRing = Style("#ff7f0e", 2.2)

    /** The corner scale bar: a reading of the drawing, so it is drawn in the annotation's quiet grey. */
    private val scaleBarStyle = Style("#8a8a8a", 1.2)

    /** Screen length of a drawn frame axis — a marker, so it does not scale with the drawing. */
    private const val FRAME_AXIS_PX = 22.0

    /**
     * The whole drawing, on a target of its own: [begin], [draw], [end].
     *
     * Split from [draw] so the 3D view can lay the same drawing *over* an already-begun frame (the solids
     * the painter's projector has just shaded) without a second `begin` clearing them away.
     */
    fun render(
        doc: Document,
        ev: Evaluator,
        proj: PlaneProjection,
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
        inputs: Set<Element> = emptySet(),
        dependents: Set<Element> = emptySet(),
        spotlight: Set<Element> = emptySet(),
        scaleBar: Boolean = false,
    ) {
        target.begin(wPx, hPx)
        draw(
            doc, ev, proj, target, wPx, hPx, grid, highlight, preview, selected, snap, joins, closing,
            terminal, dimmed, marquee, frames, picked, emphasis, previews, inputs, dependents, spotlight,
            scaleBar,
        )
        target.end()
    }

    fun draw(
        doc: Document,
        ev: Evaluator,
        proj: PlaneProjection,
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
        inputs: Set<Element> = emptySet(),
        dependents: Set<Element> = emptySet(),
        spotlight: Set<Element> = emptySet(),
        scaleBar: Boolean = false,
    ) {
        val view = proj.viewRect(wPx, hPx).let { Rect(it.first, it.second) }
        // The grid is stated in *screen* pixels (about 40 of them per cell), so it exists only where a pixel
        // means one length everywhere: on the canvas. The 3D view has a ground grid of its own, drawn in the
        // world by [Scene3.furniture], which is the same statement made where perspective can carry it.
        if (grid && proj.similarity) drawGrid(proj, target, view)
        // Reference context of a sketch space (OP-17): **the part's section at this plane**, in the space's
        // own (u, v), plus a datum's hinge line (GitHub #6) — so the user can see where the plane is, and
        // where the material is, before drawing on it. A face space's plane lies *on* a face, so its section
        // is that face's own boundary: one mechanism, and a pyramid's lateral face draws its triangle where
        // the hardcoded rectangle this replaces could only ever draw a rectangle.
        // Drawn with the grid's weight because it is not this space's geometry; it is nevertheless where a
        // pick of the part this plane cuts lands ([Document.partOutlineOf]) and where a pick of a **section
        // input** lands ([Document.sectionCandidateNear]) — one rule: what is visible is pickable. Ghosting
        // the *other* spaces is deliberately not attempted (see DESIGN.md).
        val tip = doc.facePartTip(ev)
        drawChain(doc.spaceContext(doc.activeSpace, ev), proj, target, faceStyle)
        for (el in doc.elements) {
            if (!el.visible) continue
            // one canvas shows one space (OP-17): everything else belongs to a different coordinate system
            if (el.space != doc.activeSpace.name) continue
            val style = if (el in dimmed) Styles.DIMMED else el.style
            when (val v = ev.valueOf(el.ref)) {
                is PointValue -> dot(proj, target, v.p, style.stroke)
                is SegmentValue -> poly(proj, target, listOf(v.seg.a, v.seg.b), style)
                is LineValue -> clipLine(v.line, view)?.let { poly(proj, target, listOf(it.a, it.b), style) }
                is RayValue -> clipRay(v.ray, view)?.let { poly(proj, target, listOf(it.a, it.b), style) }
                is CircleValue -> proj.drawCircle(target, v.circle, style)
                is ArcValue -> poly(proj, target, proj.arcPoints(v.arc), style)
                is BezierValue -> poly(proj, target, GeomMath.tessellateBezier(v.bezier), style)
                is LoopValue -> drawChain(v.loop.elements, proj, target, style)
                is RegionValue -> {
                    // A thick path's footprint is drawn by the *plan convention* (OP-21): faces broken at
                    // every interval, jamb lines across them. The region itself stays whole — an opening
                    // does not interrupt the material — so the break lives here, in the drawing, and
                    // nowhere in the model.
                    val plan = doc.thickNetworkOf(el)?.let { doc.planOf(it, ev) }
                    if (plan != null) {
                        // the plan's pieces keep their kinds since the OP-21 extension, so a curved wall
                        // draws as the arcs it is made of rather than as a barrel of chords
                        drawChain(plan, proj, target, style)
                    } else {
                        drawChain(v.region.outer.elements, proj, target, style)
                        for (h in v.region.holes) drawChain(h.elements, proj, target, style)
                    }
                }
                // A solid's home is the 3D view; in plan it leaves a **footprint hint** — the boundary
                // of the areas its feature shows in plan, drawn light (OP-17). Not a projection of the mesh: a
                // shaded or hidden-line view is a *chosen* projection, which is the 3D view's job, and
                // the hint is what makes the solid pickable here — hence selectable and deletable in
                // the one view that has picking.
                is SolidValue -> drawFootprintHint(v, proj, target, style)
                is PointSetValue -> v.set.points.forEach { dot(proj, target, it, style.stroke) }
                // a dimension's value is a scalar (OP-4), so what is drawn is the graphic it prescribes
                is ScalarValue -> el.annotation?.let { drawDimension(it, ev, proj, target, style) }
                else -> {}
            }
        }
        // a selected dimension's own graphic on top, so the annotation being edited reads as picked
        for (el in selected) {
            if (!el.visible || el.space != doc.activeSpace.name) continue
            el.annotation?.let { drawDimension(it, ev, proj, target, selectionStyle, withText = false) }
        }
        // geometry an armed tool has picked, restated in the pick colour — so a boundary being traced shows
        // how much of it is already in, and a click that hit nothing is visibly a click that added nothing
        for (el in picked) emphasize(doc, el, ev, proj, target, view, pickStyle, pickRing, tip)
        // The selection's **inputs** and **dependents** (see [Dependencies]), under the selection itself so
        // the thing that was clicked still reads as the subject. Same emphasis vocabulary as everything else
        // — the piece restated on top of itself — because they are the same kind of statement about an
        // element, made about a different relation.
        for (el in inputs) emphasize(doc, el, ev, proj, target, view, inputStyle, inputRing, tip)
        for (el in dependents) emphasize(doc, el, ev, proj, target, view, dependentStyle, dependentRing, tip)
        // the selection, redrawn on top: what delete removes and — when it is a single element — what
        // the inspector's numeric fields refer to. Every kind is highlighted, since a marquee (OP-16)
        // takes whatever it covers and a selection that shows only its points would be unreadable.
        for (el in selected) emphasize(doc, el, ev, proj, target, view, selectionStyle, selectionRing, tip)
        // A selection that owns no element at all: an opening's jamb (OP-21) is part of the plan *drawing*,
        // so what the emphasis restates is that drawing — the two reveal lines and the gap between them.
        // Same vocabulary as every other selection (the piece drawn again on top of itself), which is what
        // makes "this opening is what the fields refer to" legible without a marker of its own.
        for (s in emphasis) poly(proj, target, listOf(s.a, s.b), selectionStyle)
        // a selected placed group's frame (OP-16 step 2): its origin and axes, drawn in the group's own
        // orientation — modest, because it is not geometry, but visible, because it is what a drag writes
        for (f in frames) {
            // the axis is a *pixel-long* mark, so its plane length is read off the local scale at the origin
            // it is drawn from — one number under a similarity, the perspective scale there in the 3D view
            val mm = FRAME_AXIS_PX / proj.scaleAt(f.origin)
            val o = proj.toScreen(f.origin) ?: continue
            poly(proj, target, listOf(f.origin, f.toWorld(Vec2(mm, 0.0))), frameStyle)
            poly(proj, target, listOf(f.origin, f.toWorld(Vec2(0.0, mm))), frameStyle)
            target.circle(o, 3.0, frameStyle)
        }
        // What the armed tool would build if the next click happened here (`ToolDef.preview`) — the ortho
        // band's own style, because it is the same promise about the same click, and drawn *before* the band
        // so the two can never disagree about who is on top.
        for (s in previews) drawPreview(s, proj, target, view)
        // rubber band of a marquee in progress — the rectangle whose contents the release will select
        marquee?.let { (a, b) ->
            val p = proj.toScreen(a)
            val q = proj.toScreen(b)
            if (p != null && q != null) target.polyline(listOf(p, Vec2(q.x, p.y), q, Vec2(p.x, q.y), p), marqueeStyle)
        }
        // rubber-band preview of the next ortho-path leg
        preview?.let { poly(proj, target, listOf(it.first, it.second), previewStyle) }
        // and of a *closed* loop: what the click will make, including the corner it moves into line
        for (seg in closing) {
            poly(proj, target, listOf(seg.first, seg.second), previewStyle)
        }
        // snap marker: a small square where a placing click would land (and what it would link to)
        snap?.let {
            val c = proj.toScreen(it) ?: return@let
            val r = 5.0
            target.polyline(
                listOf(Vec2(c.x - r, c.y - r), Vec2(c.x + r, c.y - r), Vec2(c.x + r, c.y + r), Vec2(c.x - r, c.y + r), Vec2(c.x - r, c.y - r)),
                snapStyle,
            )
        }
        // corners a release would join away: crossed out, because that is what happens to them
        for (j in joins) {
            val c = proj.toScreen(j) ?: continue
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
            val c = proj.toScreen(it) ?: return@let
            for (r in listOf(6.0, 10.0)) {
                target.polyline(
                    listOf(Vec2(c.x, c.y - r), Vec2(c.x + r, c.y), Vec2(c.x, c.y + r), Vec2(c.x - r, c.y), Vec2(c.x, c.y - r)),
                    terminalStyle,
                )
            }
        }
        // weld magnet: a double ring around the point a dragged point will snap/join onto
        highlight?.let {
            val s = proj.toScreen(it) ?: return@let
            target.circle(s, 11.0, haloOuter)
            target.circle(s, 6.0, haloInner)
        }
        // ...and last, the element a name in the panel is being hovered: nothing may hide it
        for (el in spotlight) emphasize(doc, el, ev, proj, target, view, spotlightStyle, spotlightRing, tip)
        // the ruler states a length in pixels, so it says something true only under a similarity — the same
        // rule as the grid, one line above
        if (scaleBar && proj.similarity) drawScaleBar(proj.scaleAt(Vec2(0.0, 0.0)), target, hPx)
    }

    /**
     * A plane-space polyline drawn on screen, or **nothing at all** when one of its vertices has no image.
     *
     * Dropping the whole primitive is the honest choice under perspective: a segment straddling the eye
     * plane has two halves that project to opposite edges of the screen, and joining them would draw a line
     * across the view that exists nowhere in the model. Near-plane clipping of every piece would be a
     * second projection pipeline, which is the one thing this engine does not have (OP-12). The 2D camera
     * never returns null, so the canvas path is exactly the map it always was.
     */
    private fun poly(
        proj: PlaneProjection,
        target: DrawTarget,
        points: List<Vec2>,
        style: Style,
    ) {
        val screen = ArrayList<Vec2>(points.size)
        for (p in points) screen.add(proj.toScreen(p) ?: return)
        target.polyline(screen, style)
    }

    private fun dot(
        proj: PlaneProjection,
        target: DrawTarget,
        at: Vec2,
        color: String,
    ) {
        proj.toScreen(at)?.let { target.dot(it, POINT_PX, color) }
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
        proj: PlaneProjection,
        target: DrawTarget,
        view: Rect,
    ) {
        when (s) {
            is PreviewShape.Dot -> dot(proj, target, s.at, previewStyle.stroke)
            is PreviewShape.Seg -> poly(proj, target, listOf(s.seg.a, s.seg.b), previewStyle)
            is PreviewShape.Ln ->
                clipLine(s.line, view)?.let { poly(proj, target, listOf(it.a, it.b), previewStyle) }
            is PreviewShape.Ry ->
                clipRay(s.ray, view)?.let { poly(proj, target, listOf(it.a, it.b), previewStyle) }
            is PreviewShape.Circ -> proj.drawCircle(target, s.circle, previewStyle)
            is PreviewShape.ArcS -> poly(proj, target, proj.arcPoints(s.arc), previewStyle)
            is PreviewShape.Bez -> poly(proj, target, GeomMath.tessellateBezier(s.bezier), previewStyle)
            is PreviewShape.Path -> poly(proj, target, s.points, previewStyle)
            is PreviewShape.Dim -> drawGraphic(s.graphic, proj, target, previewStyle, withText = true)
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
        proj: PlaneProjection,
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
            poly(proj, target, r + r.first(), style)
            return
        }
        if (el.space != doc.activeSpace.name) return
        when (val v = ev.valueOf(el.ref)) {
            is PointValue -> proj.toScreen(v.p)?.let { target.circle(it, 7.0, ringStyle) }
            is SegmentValue -> poly(proj, target, listOf(v.seg.a, v.seg.b), style)
            is LineValue ->
                clipLine(v.line, view)?.let { poly(proj, target, listOf(it.a, it.b), style) }
            is RayValue ->
                clipRay(v.ray, view)?.let { poly(proj, target, listOf(it.a, it.b), style) }
            is CircleValue -> proj.drawCircle(target, v.circle, style)
            is ArcValue -> poly(proj, target, proj.arcPoints(v.arc), style)
            is BezierValue -> poly(proj, target, GeomMath.tessellateBezier(v.bezier), style)
            is LoopValue -> drawChain(v.loop.elements, proj, target, style)
            is RegionValue -> {
                drawChain(v.region.outer.elements, proj, target, style)
                for (h in v.region.holes) drawChain(h.elements, proj, target, style)
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
        proj: PlaneProjection,
        target: DrawTarget,
        style: Style,
    ) {
        for (region in v.solid.feature.footprint) {
            drawChain(region.outer.elements, proj, target, style)
            for (h in region.holes) drawChain(h.elements, proj, target, style)
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
        proj: PlaneProjection,
        target: DrawTarget,
        style: Style,
        withText: Boolean = true,
    ) {
        drawGraphic(ann.graphic(ev) ?: return, proj, target, style, withText)
    }

    /**
     * A dimension's world-space skeleton on screen. Taken apart from [drawDimension] so a **previewed**
     * dimension (`PreviewShape.Dim`, which owns no annotation and no node) is drawn by exactly the same
     * code as a placed one.
     */
    private fun drawGraphic(
        g: DimensionGraphic,
        proj: PlaneProjection,
        target: DrawTarget,
        style: Style,
        withText: Boolean,
    ) {
        for (s in g.lines) poly(proj, target, listOf(s.a, s.b), style)
        g.arc?.let { arc -> poly(proj, target, proj.arcPoints(arc), style) }
        for (a in g.arrows) {
            proj.toScreen(a.tip)?.let { drawArrow(it, screenDir(proj, a.tip, a.along), target, style) }
        }
        if (!withText) return
        val at = proj.toScreen(g.textAt) ?: return
        target.text(at + screenDir(proj, g.textAt, g.textUp) * TEXT_GAP_PX, g.text, style, g.textAnchor)
    }

    /**
     * A plane direction at [from] as a unit *screen* direction — the y flip included, and under perspective
     * the local turn as well, which is exactly why it is measured rather than derived: an arrowhead has to
     * point along the line it terminates however that line is projected.
     */
    private fun screenDir(
        proj: PlaneProjection,
        from: Vec2,
        planeDir: Vec2,
    ): Vec2 {
        val a = proj.toScreen(from) ?: return Vec2(0.0, 0.0)
        val b = proj.toScreen(from + planeDir) ?: return Vec2(0.0, 0.0)
        return (b - a).normalized()
    }

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
     * An arc as a **plane-space** polyline at the 2D canvas' own step count (fixed per full turn, so
     * goldens do not depend on the camera); the sampling itself is `GeomMath`'s, shared with the
     * world-space tolerance-driven tessellation the 3D layer needs (OP-17) — one place for the maths,
     * two policies for how finely to apply it.
     *
     * Still public and still the canvas' policy, because [HitTest] measures a pick against these very
     * chords: what the canvas draws is what the canvas picks. The *drawing* asks the projection instead
     * ([PlaneProjection.arcPoints]), which is where the 3D view's finer, tolerance-driven count lives.
     */
    fun tessellate(arc: Arc): List<Vec2> = GeomMath.sampleArc(arc, GeomMath.renderArcSteps(arc))

    /**
     * Draw a boundary chain (a `Loop`, or a `Region`'s outer/hole loop) piece by piece. Each piece
     * becomes a polyline in screen space; the piece dispatch itself stays in `GeomMath`, except for
     * this one — emitting backend primitives is a rendering question, not a geometric one.
     */
    private fun drawChain(
        elements: List<ProfileElement>,
        proj: PlaneProjection,
        target: DrawTarget,
        style: Style,
    ) {
        for (el in elements) when (el) {
            is ProfileElement.Seg -> poly(proj, target, listOf(el.segment.a, el.segment.b), style)
            is ProfileElement.ArcE -> poly(proj, target, proj.arcPoints(el.arc), style)
            is ProfileElement.BezierE -> poly(proj, target, GeomMath.tessellateBezier(el.bezier), style)
            is ProfileElement.CircleE -> proj.drawCircle(target, el.circle, style)
        }
    }

    private val gridStyle = Style("#eeeeee", 1.0)
    private val axisStyle = Style("#c8c8c8", 1.0)

    /** The world grid spacing in use at [scale] — also what a grid snap rounds to. */
    fun gridStep(scale: Double): Double = niceStep(scale)

    /** A "nice" world grid spacing (1/2/5 x 10^k mm) so screen spacing is roughly 40 px. */
    private fun niceStep(scale: Double): Double = niceLength(40.0 / scale)

    /**
     * The **one rounding rule** of the drawing: the largest 1/2/5 × 10^k mm that does not exceed [wanted].
     *
     * It was the grid's, then the 3D ground's ([Scene3.gridStepFor]), and it is now the scale bar's too —
     * one rule, three consumers, so a bar and the grid it sits on can never round differently. What differs
     * is only what each asks for: 40 px of screen for a grid cell, [SCALE_BAR_PX] for the bar.
     */
    private fun niceLength(wanted: Double): Double {
        val mag = 10.0.pow(floor(log10(wanted)))
        val norm = wanted / mag
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

    /** How long the scale bar wants to be on screen; the round world length is fitted under it. */
    private const val SCALE_BAR_PX = 100.0

    /**
     * The world length the corner scale bar states at [scale] — a round number of millimetres by
     * [niceLength], spanning at most [SCALE_BAR_PX] pixels.
     *
     * Pure and public because it is the assertable half of the feature: the bar's *job* is that the number
     * beside it is a number a person recognises, and that is a statement about arithmetic, not about pixels.
     */
    fun scaleBarLength(scale: Double): Double = niceLength(SCALE_BAR_PX / scale)

    /** The bar's label: the same base unit the whole model is in (mm), never a converted one. */
    fun scaleBarLabel(scale: Double): String = "${Format.num(scaleBarLength(scale))} mm"

    /**
     * The corner ruler: a round length, drawn at the size it really is, with its number over it.
     *
     * Drawn here rather than in the shell for the reason every other overlay is — one renderer, so the SVG
     * goldens describe what the browser draws — and gated by a flag exactly as the grid is, so a golden of
     * geometry stays a golden of geometry (`Editor.showScaleBar`, set by the shell like `showGrid`).
     */
    private fun drawScaleBar(
        scale: Double,
        target: DrawTarget,
        hPx: Double,
    ) {
        val mm = scaleBarLength(scale)
        val px = mm * scale
        val x0 = SCALE_BAR_MARGIN_PX
        val y = hPx - SCALE_BAR_MARGIN_PX
        target.polyline(listOf(Vec2(x0, y), Vec2(x0 + px, y)), scaleBarStyle)
        target.polyline(listOf(Vec2(x0, y - SCALE_BAR_TICK_PX), Vec2(x0, y)), scaleBarStyle)
        target.polyline(listOf(Vec2(x0 + px, y - SCALE_BAR_TICK_PX), Vec2(x0 + px, y)), scaleBarStyle)
        target.text(Vec2(x0 + px / 2.0, y - SCALE_BAR_TICK_PX - 3.0), scaleBarLabel(scale), scaleBarStyle, TextAnchor.MIDDLE)
    }

    private const val SCALE_BAR_MARGIN_PX = 14.0
    private const val SCALE_BAR_TICK_PX = 5.0

    private fun drawGrid(
        proj: PlaneProjection,
        target: DrawTarget,
        view: Rect,
    ) {
        val step = niceStep(proj.scaleAt(view.lo))
        var x = floor(view.lo.x / step) * step
        while (x <= view.hi.x) {
            val style = if (abs(x) < step * 0.5) axisStyle else gridStyle
            poly(proj, target, listOf(Vec2(x, view.lo.y), Vec2(x, view.hi.y)), style)
            x += step
        }
        var y = floor(view.lo.y / step) * step
        while (y <= view.hi.y) {
            val style = if (abs(y) < step * 0.5) axisStyle else gridStyle
            poly(proj, target, listOf(Vec2(view.lo.x, y), Vec2(view.hi.x, y)), style)
            y += step
        }
    }

    private data class Rect(val lo: Vec2, val hi: Vec2)

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
