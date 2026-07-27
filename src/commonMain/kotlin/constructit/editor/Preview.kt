package constructit.editor

import constructit.core.ArcValue
import constructit.core.BezierValue
import constructit.core.CircleValue
import constructit.core.Evaluator
import constructit.core.LineValue
import constructit.core.LoopValue
import constructit.core.PointValue
import constructit.core.RayValue
import constructit.core.RegionValue
import constructit.core.SegmentValue
import constructit.dsl.valueOf
import constructit.geom.Affine
import constructit.geom.Arc
import constructit.geom.Bezier
import constructit.geom.CarrierCurve
import constructit.geom.Circle
import constructit.geom.FilletLeg
import constructit.geom.FilletMath
import constructit.geom.GeomMath
import constructit.geom.Justification
import constructit.geom.Line
import constructit.geom.Loop
import constructit.geom.ProfileElement
import constructit.geom.Ray
import constructit.geom.Region
import constructit.geom.Segment
import constructit.geom.Vec2
import constructit.geom.thickNetwork
import constructit.units.Quantity
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * One piece of a **live tool preview**: plain geometry, drawn in the preview style, owned by nobody.
 *
 * The value types the renderer already knows how to draw, and nothing else — a preview is not a lightweight
 * element, it is a *picture* of what the next click will build. Nothing here holds a `Node`, a `Ref` or an
 * `Element`, which is what makes the never-touches-the-graph rule structural rather than a discipline: a
 * preview function has no way to record anything even if it tried (see [PreviewContext]).
 */
sealed class PreviewShape {
    class Dot(val at: Vec2) : PreviewShape()

    class Seg(val seg: Segment) : PreviewShape()

    /** An infinite line — clipped to the viewport by the renderer, exactly as a real line is. */
    class Ln(val line: Line) : PreviewShape()

    class Ry(val ray: Ray) : PreviewShape()

    class Circ(val circle: Circle) : PreviewShape()

    class ArcS(val arc: Arc) : PreviewShape()

    class Bez(val bezier: Bezier) : PreviewShape()

    /** A polyline through world points — a closed path repeats its first point. */
    class Path(val points: List<Vec2>) : PreviewShape()

    /** A dimension's own graphic (OP-4), riding the cursor before the placing click. */
    class Dim(val graphic: DimensionGraphic) : PreviewShape()
}

/**
 * What a [ToolDef.preview] is handed: the picks collected so far, the values in effect, and where the cursor
 * is — everything the *next* click will be built from, and nothing that could build anything.
 *
 * It carries the [doc] and the pick tolerance because some previews have to resolve what the cursor is
 * **over** rather than merely where it is (a fillet's second leg, Mirror's axis) — the same question the
 * click itself will ask, asked one frame early. Reading the document is not touching the graph; every
 * preview here computes with `GeomMath` and returns values, which is the rule the ortho band already
 * followed (see DESIGN, *Snapping while placing*).
 */
class PreviewContext(
    val doc: Document,
    val ev: Evaluator,
    /** The picks so far — [Picks.at] is the cursor, so a preview can read it either way. */
    val picks: Picks,
    /**
     * One entry per declared scalar slot, in slot order: the value **in effect** — picked, typed, or the
     * slot's own default ([ScalarSlot.default]) — or null for a slot the tool is still waiting for, where an
     * honest preview shows nothing.
     */
    val scalars: List<Quantity?>,
    val cursor: Vec2,
    /** Pick tolerance in world units, for the previews that resolve what the cursor is over. */
    val tol: Double,
    /**
     * The **wall side in effect** for the next pick (the OP-21 extension) — a tool option, exactly as the
     * typed scalars above are, and read by the one preview whose shape depends on it.
     */
    val side: Justification = Justification.CENTER,
) {
    /** The structural count the tool would build with (OP-23's re-stamp number, an array's copies). */
    val count: Int get() = picks.count

    fun point(i: Int): Vec2? = picks.points.getOrNull(i)?.let { (ev.valueOf(it) as? PointValue)?.p }

    fun click(i: Int): Vec2? = picks.clicks.getOrNull(i)

    fun element(i: Int): Element? = picks.elements.getOrNull(i)

    fun length(i: Int): Double? = scalars.getOrNull(i)?.mm

    fun angle(i: Int): Double? = scalars.getOrNull(i)?.base

    fun number(i: Int): Double? = scalars.getOrNull(i)?.base

    /** The nearest element under the cursor passing [filter] — the pick this hover would make. */
    fun under(filter: (Element) -> Boolean): Element? = HitTest.nearest(doc, ev, cursor, tol, filter)
}

/**
 * The **live previews** of the data-driven tools: one function per shape of preview, referenced from the
 * `ToolDef` table (OP-13's "the panel and the keyboard are as much an input as the canvas", one step
 * earlier — the *cursor* is an input too, and the drawing should say so before the click).
 *
 * Two rules hold for every function here, and they are what makes the mechanism trustworthy:
 *
 * - **It never touches the graph.** Everything is computed from evaluated values with `GeomMath`, exactly as
 *   the snap resolver's hover-time intersections are, so hovering creates no node, no element and no step —
 *   asserted generically in `PreviewTest` (`nodesCreated` flat across a sweep of hovers over every tool).
 * - **It is honest.** What it draws is what the click will build, *including the values in effect* — a typed
 *   radius, a defaulted corner radius, the current count. Where the tool would build nothing (a missing
 *   scalar, a degenerate pick, a cursor over nothing) it returns an empty list rather than a guess. Where a
 *   discrete choice is at stake it runs the **same scoring the build runs** (see [fillet]), which is the
 *   point: the variant becomes visible before it is committed.
 */
object Previews {
    // ---- value-level accessors: an element as the geometry a preview can compute with ----

    /** [el]'s carrier line — a line, a segment or a ray, the coercion `SlotKind.LINE` makes. */
    fun lineOf(
        el: Element?,
        ev: Evaluator,
    ): Line? {
        if (el == null) return null
        return when (val v = ev.valueOf(el.ref)) {
            is LineValue -> v.line
            is RayValue -> Line(v.ray.origin, v.ray.dir)
            is SegmentValue -> (v.seg.b - v.seg.a).let { if (it.length() < Vec2.EPS) null else Line(v.seg.a, it.normalized()) }
            else -> null
        }
    }

    /** [el]'s carrier circle — a circle or an arc, the twin coercion `SlotKind.CIRCLE` makes. */
    fun circleOf(
        el: Element?,
        ev: Evaluator,
    ): Circle? {
        if (el == null) return null
        return when (val v = ev.valueOf(el.ref)) {
            is CircleValue -> v.circle
            is ArcValue -> Circle(v.arc.center, v.arc.radius)
            else -> null
        }
    }

    /** [el] as a fillet leg (either carrier), for the scoring the build performs. */
    fun legOf(
        el: Element?,
        ev: Evaluator,
    ): FilletLeg? = lineOf(el, ev)?.let { FilletLeg.of(it) } ?: circleOf(el, ev)?.let { FilletLeg.of(it) }

    /**
     * [el] as preview shapes — what a **ghost copy** of it is drawn as. Every value kind a transform can
     * copy is covered, because Mirror/Rotate/Scale/Translate take whatever is picked and a ghost that
     * skipped kinds would read as "that one is not coming along".
     */
    fun shapesOf(
        el: Element,
        ev: Evaluator,
    ): List<PreviewShape> =
        when (val v = ev.valueOf(el.ref)) {
            is PointValue -> listOf(PreviewShape.Dot(v.p))
            is SegmentValue -> listOf(PreviewShape.Seg(v.seg))
            is LineValue -> listOf(PreviewShape.Ln(v.line))
            is RayValue -> listOf(PreviewShape.Ry(v.ray))
            is CircleValue -> listOf(PreviewShape.Circ(v.circle))
            is ArcValue -> listOf(PreviewShape.ArcS(v.arc))
            is BezierValue -> listOf(PreviewShape.Bez(v.bezier))
            is LoopValue -> loopShapes(v.loop)
            is RegionValue -> regionShapes(v.region)
            else -> emptyList()
        }

    private fun regionShapes(r: Region): List<PreviewShape> = loopShapes(r.outer) + r.holes.flatMap { loopShapes(it) }

    /**
     * The wall the *Thicken* tool would build from the curves picked so far, plus the one under the cursor
     * (the OP-21 extension): the actual footprint, computed on values and drawn as itself.
     *
     * It is also the honest way to see a refusal before making it — a pick that would disconnect the network
     * simply draws nothing, and the status line says why after the click.
     */
    fun thicken(c: PreviewContext): List<PreviewShape> {
        val t = c.length(0) ?: return emptyList()
        val sides = c.picks.signs
        val picked = c.picks.elements
        val hover = c.under { it.isCurve }?.takeIf { h -> picked.none { it === h } }
        val curves =
            (picked + listOfNotNull(hover)).mapIndexed { i, el ->
                val piece = carrierPieceOf(c.ev.valueOf(el.ref) ?: return emptyList()) ?: return emptyList()
                CarrierCurve(piece, Tools.sideOf(sides.getOrElse(i) { c.side.ordinal }))
            }
        if (curves.isEmpty()) return emptyList()
        val (body, _) = thickNetwork(curves, t)
        return body?.let { regionShapes(it.region) } ?: emptyList()
    }

    private fun carrierPieceOf(v: constructit.core.Value): ProfileElement? =
        when (v) {
            is SegmentValue -> ProfileElement.Seg(v.seg)
            is ArcValue -> ProfileElement.ArcE(v.arc)
            is BezierValue -> ProfileElement.BezierE(v.bezier)
            else -> null
        }

    private fun loopShapes(l: Loop): List<PreviewShape> =
        l.elements.map {
            when (it) {
                is ProfileElement.Seg -> PreviewShape.Seg(it.segment)
                is ProfileElement.ArcE -> PreviewShape.ArcS(it.arc)
                is ProfileElement.CircleE -> PreviewShape.Circ(it.circle)
                is ProfileElement.BezierE -> PreviewShape.Bez(it.bezier)
            }
        }

    /** One preview shape under an affine map — how a ghost copy is placed. */
    fun transform(
        s: PreviewShape,
        t: Affine,
    ): PreviewShape =
        when (s) {
            is PreviewShape.Dot -> PreviewShape.Dot(t.apply(s.at))
            is PreviewShape.Seg -> PreviewShape.Seg(Segment(t.apply(s.seg.a), t.apply(s.seg.b)))
            is PreviewShape.Ln -> PreviewShape.Ln(Line(t.apply(s.line.origin), t.linear(s.line.dir).normalized()))
            is PreviewShape.Ry -> PreviewShape.Ry(Ray(t.apply(s.ray.origin), t.linear(s.ray.dir).normalized()))
            is PreviewShape.Circ -> PreviewShape.Circ(Circle(t.apply(s.circle.center), s.circle.radius * t.scale))
            is PreviewShape.ArcS -> PreviewShape.ArcS(GeomMath.transformArc(s.arc, t))
            is PreviewShape.Bez -> PreviewShape.Bez(GeomMath.transformBezier(s.bezier, t))
            is PreviewShape.Path -> PreviewShape.Path(s.points.map { t.apply(it) })
            is PreviewShape.Dim -> s
        }

    /** Every picked element ghosted under [maps] — one ghost per map, which is how an array previews. */
    private fun ghosts(
        c: PreviewContext,
        maps: List<Affine>,
    ): List<PreviewShape> {
        val own = c.picks.elements.flatMap { shapesOf(it, c.ev) }
        if (own.isEmpty()) return emptyList()
        return maps.flatMap { m -> own.map { transform(it, m) } }
    }

    // ---- curves: the band, in the shape the click will actually make ----

    /** The segment band: the piece the second click places. */
    fun segment(c: PreviewContext): List<PreviewShape> {
        val a = c.point(0) ?: return emptyList()
        return listOf(PreviewShape.Seg(Segment(a, c.cursor)))
    }

    /** The line band — drawn *infinite*, because that is the element the click makes. */
    fun line(c: PreviewContext): List<PreviewShape> {
        val a = c.point(0) ?: return emptyList()
        val d = c.cursor - a
        if (d.length() < Vec2.EPS) return emptyList()
        return listOf(PreviewShape.Ln(Line(a, d.normalized())))
    }

    /** The ray band — a half line from the clicked origin, as the renderer draws a ray. */
    fun ray(c: PreviewContext): List<PreviewShape> {
        val a = c.point(0) ?: return emptyList()
        val d = c.cursor - a
        if (d.length() < Vec2.EPS) return emptyList()
        return listOf(PreviewShape.Ry(Ray(a, d.normalized())))
    }

    /** The growing circle: centre clicked, radius under the cursor. */
    fun circleCentrePoint(c: PreviewContext): List<PreviewShape> {
        val centre = c.point(0) ?: return emptyList()
        val r = (c.cursor - centre).length()
        if (r < Vec2.EPS) return emptyList()
        return listOf(PreviewShape.Circ(Circle(centre, r)))
    }

    /** The live circumcircle through the two picked points and the cursor. */
    fun circle3(c: PreviewContext): List<PreviewShape> {
        val a = c.point(0) ?: return emptyList()
        val b = c.point(1) ?: return emptyList()
        val cc = GeomMath.circumcenter(a, b, c.cursor) ?: return emptyList()
        return listOf(PreviewShape.Circ(Circle(cc, (a - cc).length())))
    }

    /** The live arc through start, the picked mid point and the cursor — `Construction.arc3` on values. */
    fun arc3(c: PreviewContext): List<PreviewShape> {
        val a = c.point(0) ?: return emptyList()
        val b = c.point(1) ?: return emptyList()
        val cc = GeomMath.circumcenter(a, b, c.cursor) ?: return emptyList()
        val ccw = (b - a).cross(c.cursor - a) > 0
        return listOf(PreviewShape.ArcS(Arc(cc, (a - cc).length(), (a - cc).angle(), (c.cursor - cc).angle(), ccw)))
    }

    /** The arc a centre, a start and the cursor make — counter-clockwise, as the op sweeps. */
    fun arcCentreEnds(c: PreviewContext): List<PreviewShape> {
        val centre = c.point(0) ?: return emptyList()
        val start = c.point(1) ?: return emptyList()
        val r = (start - centre).length()
        if (r < Vec2.EPS) return emptyList()
        return listOf(PreviewShape.ArcS(Arc(centre, r, (start - centre).angle(), (c.cursor - centre).angle(), ccw = true)))
    }

    /** The closed outline two diagonally opposite corners make — the rectangle's own path. */
    fun rectangle(c: PreviewContext): List<PreviewShape> {
        val a = c.click(0) ?: return emptyList()
        val b = c.cursor
        if (abs(b.x - a.x) < Vec2.EPS || abs(b.y - a.y) < Vec2.EPS) return emptyList() // no rectangle there
        return listOf(PreviewShape.Path(listOf(a, Vec2(b.x, a.y), b, Vec2(a.x, b.y), a)))
    }

    /**
     * The rounded rectangle **with the radius in effect**: four sides and four quarter arcs, the pieces the
     * `roundedRect` macro emits. With a radius too large for the span it falls back to the sharp outline,
     * which is what the macro degenerates to.
     */
    fun roundedRect(c: PreviewContext): List<PreviewShape> {
        val a = c.point(0) ?: return emptyList()
        val b = c.cursor
        val hw = abs(b.x - a.x) / 2
        val hh = abs(b.y - a.y) / 2
        if (hw < Vec2.EPS || hh < Vec2.EPS) return emptyList()
        val centre = (a + b) * 0.5
        val r = (c.length(0) ?: 0.0).coerceAtLeast(0.0)
        if (r < Vec2.EPS || r > hw || r > hh) {
            val lo = centre - Vec2(hw, hh)
            val hi = centre + Vec2(hw, hh)
            return listOf(PreviewShape.Path(listOf(lo, Vec2(hi.x, lo.y), hi, Vec2(lo.x, hi.y), lo)))
        }
        val ix = hw - r
        val iy = hh - r
        val out = ArrayList<PreviewShape>(8)
        out.add(PreviewShape.Seg(Segment(centre + Vec2(-ix, hh), centre + Vec2(ix, hh))))
        out.add(PreviewShape.Seg(Segment(centre + Vec2(hw, iy), centre + Vec2(hw, -iy))))
        out.add(PreviewShape.Seg(Segment(centre + Vec2(ix, -hh), centre + Vec2(-ix, -hh))))
        out.add(PreviewShape.Seg(Segment(centre + Vec2(-hw, -iy), centre + Vec2(-hw, iy))))
        val corners = listOf(Vec2(ix, iy) to 0.0, Vec2(-ix, iy) to PI / 2, Vec2(-ix, -iy) to PI, Vec2(ix, -iy) to 3 * PI / 2)
        for ((offset, from) in corners) {
            out.add(PreviewShape.ArcS(Arc(centre + offset, r, from, from + PI / 2, ccw = true)))
        }
        return out
    }

    /**
     * The regular polygon of the **current count**, rounded by the corner radius in effect (OP-23's everyday
     * shortcut): the sides stay whole and each corner gains its arc, which is exactly what the gesture builds.
     */
    fun polygon(c: PreviewContext): List<PreviewShape> {
        val centre = c.point(0) ?: return emptyList()
        val n = c.count
        if (n < 3) return emptyList()
        val v0 = c.cursor - centre
        if (v0.length() < Vec2.EPS) return emptyList()
        val verts = (0 until n).map { k -> centre + rotate(v0, 2 * PI * k / n) }
        val out = ArrayList<PreviewShape>(2 * n)
        out.add(PreviewShape.Path(verts + verts.first()))
        val r = c.length(0) ?: 0.0
        if (r <= 0.0) return out
        for (k in 0 until n) {
            val prev = verts[(k + n - 1) % n]
            val here = verts[k]
            val next = verts[(k + 1) % n]
            val u1 = (prev - here).normalized()
            val u2 = (next - here).normalized()
            // the same op the rounding is built from ([FilletMath.lineLineArc] = `filletBetweenLines`), in the
            // corner the two sides open — which for a polygon's corner needs no scoring: it is the inside
            val arc = FilletMath.lineLineArc(Line(here, u1), Line(here, u2), r, +1, +1) ?: continue
            out.add(PreviewShape.ArcS(arc))
        }
        return out
    }

    // ---- construct: the rounding and the bevel, before the click that fixes the variant ----

    /**
     * The fillet arc the *current cursor side* would score: the second leg is whatever the cursor is over,
     * and the clicks scored are the first pick and the cursor.
     *
     * Run through [FilletMath.variantFor] — the very function the build scores with (OP-1) — so moving the
     * cursor from one side of a leg to the other flips the previewed arc exactly as the click will. This is
     * the whole reason a fillet wants a preview: which of the eight variants a pair of clicks means is the
     * one thing about the tool that was invisible until it was committed.
     */
    fun fillet(c: PreviewContext): List<PreviewShape> {
        val r = c.length(0) ?: return emptyList()
        if (r <= 0.0) return emptyList()
        val first = c.element(0) ?: return emptyList()
        val clickA = c.click(0) ?: return emptyList()
        val second = c.under { it !== first && (it.isLinear || it.isCentric) } ?: return emptyList()
        val l1 = lineOf(first, c.ev)
        val l2 = lineOf(second, c.ev)
        // the line-line case keeps its own op (and its own quadrant signs), so its preview does too
        if (l1 != null && l2 != null) {
            val (s1, s2) = FilletMath.legSigns(l1, l2, clickA, c.cursor)
            return listOfNotNull(FilletMath.lineLineArc(l1, l2, r, s1, s2)?.let { PreviewShape.ArcS(it) })
        }
        val leg1 = legOf(first, c.ev) ?: return emptyList()
        val leg2 = legOf(second, c.ev) ?: return emptyList()
        val v = FilletMath.variantFor(leg1, leg2, r, clickA, c.cursor) ?: return emptyList()
        return listOfNotNull(FilletMath.arcOf(leg1, leg2, r, v)?.let { PreviewShape.ArcS(it) })
    }

    /** The bevel the current cursor quadrant would cut, at the distance in effect. */
    fun chamfer(c: PreviewContext): List<PreviewShape> {
        val d = c.length(0) ?: return emptyList()
        if (d <= 0.0) return emptyList()
        val first = c.element(0) ?: return emptyList()
        val clickA = c.click(0) ?: return emptyList()
        val second = c.under { it !== first && it.isLinear } ?: return emptyList()
        val l1 = lineOf(first, c.ev) ?: return emptyList()
        val l2 = lineOf(second, c.ev) ?: return emptyList()
        val (s1, s2) = FilletMath.legSigns(l1, l2, clickA, c.cursor)
        return listOfNotNull(FilletMath.chamferEnds(l1, l2, d, s1, s2)?.let { PreviewShape.Seg(it) })
    }

    /**
     * The tangent circle nearest the cursor, once three lines are picked (the LLL case) — the candidate the
     * click would store, live, so which of incircle and the three excircles is being chosen is visible.
     */
    fun circle3Tangents(c: PreviewContext): List<PreviewShape> {
        val l1 = lineOf(c.element(0), c.ev) ?: return emptyList()
        val l2 = lineOf(c.element(1), c.ev) ?: return emptyList()
        val l3 = lineOf(c.element(2), c.ev) ?: return emptyList()
        val hit = FilletMath.nearestTangentCircle(l1, l2, l3, c.cursor) ?: return emptyList()
        return listOf(PreviewShape.Circ(hit.second))
    }

    // ---- transforms: ghost copies under the map the picks imply ----

    /** The picked geometry mirrored across whatever line the cursor is over. */
    fun mirror(c: PreviewContext): List<PreviewShape> {
        val axis = lineOf(c.under { it.isLinear }, c.ev) ?: return emptyList()
        return ghosts(c, listOf(Affine.reflection(axis)))
    }

    /** One ghost, rotated about the cursor by the angle in effect. */
    fun rotate(c: PreviewContext): List<PreviewShape> {
        val a = c.angle(0) ?: return emptyList()
        return ghosts(c, listOf(Affine.rotation(c.cursor, a)))
    }

    /** One ghost, scaled about the cursor by the factor in effect. */
    fun scale(c: PreviewContext): List<PreviewShape> {
        val k = c.number(0) ?: return emptyList()
        if (abs(k) < Vec2.EPS) return emptyList()
        return ghosts(c, listOf(Affine.scaling(c.cursor, k)))
    }

    /** One ghost, translated by the vector from the picked point to the cursor. */
    fun translate(c: PreviewContext): List<PreviewShape> {
        val from = c.point(0) ?: return emptyList()
        return ghosts(c, listOf(Affine.translation(c.cursor - from)))
    }

    /** Every copy of a linear array: k·v from the original, for k = 1 … count-1. */
    fun linearArray(c: PreviewContext): List<PreviewShape> {
        val from = c.point(0) ?: return emptyList()
        val v = c.cursor - from
        if (c.count < 2) return emptyList()
        return ghosts(c, (1 until c.count).map { Affine.translation(v * it.toDouble()) })
    }

    /** Every copy of a circular array: evenly spaced round the cursor. */
    fun circularArray(c: PreviewContext): List<PreviewShape> {
        if (c.count < 2) return emptyList()
        return ghosts(c, (1 until c.count).map { Affine.rotation(c.cursor, 2 * PI * it / c.count) })
    }

    /** The **ring** a circular pattern would stamp: its members, from the cursor round the picked centre. */
    fun circularPattern(c: PreviewContext): List<PreviewShape> {
        val centre = c.point(0) ?: return emptyList()
        if (c.count < 2) return emptyList()
        val v = c.cursor - centre
        if (v.length() < Vec2.EPS) return emptyList()
        return (0 until c.count).map { PreviewShape.Dot(centre + rotate(v, 2 * PI * it / c.count)) }
    }

    /** The row a linear pattern would stamp: the base and count-1 further steps to the cursor. */
    fun linearPattern(c: PreviewContext): List<PreviewShape> {
        val base = c.point(0) ?: return emptyList()
        if (c.count < 2) return emptyList()
        val v = c.cursor - base
        if (v.length() < Vec2.EPS) return emptyList()
        return (0 until c.count).map { PreviewShape.Dot(base + v * it.toDouble()) }
    }

    // ---- annotate: the dimension graphic riding the cursor ----

    /** The linear dimension the placing click would leave, offset to the cursor. */
    fun linearDimension(c: PreviewContext): List<PreviewShape> {
        val a = pointOfPick(c, 0) ?: return emptyList()
        val b = pointOfPick(c, 1) ?: return emptyList()
        if ((b - a).length() < Vec2.EPS) return emptyList()
        val offset = (c.cursor - a).dot((b - a).normalized().perp())
        return listOf(PreviewShape.Dim(LinearDimension.graphicOf(a, b, offset, Format.quantity(Quantity.mm((b - a).length())))))
    }

    /** The radial dimension the placing click would leave: leader angle and reach from the cursor. */
    fun radialDimension(c: PreviewContext): List<PreviewShape> {
        val circle = circleOf(c.element(0), c.ev) ?: return emptyList()
        val d = c.cursor - circle.center
        if (d.length() < Vec2.EPS) return emptyList()
        val text = "R " + Format.quantity(Quantity.mm(circle.radius))
        return listOf(PreviewShape.Dim(RadialDimension.graphicOf(circle, d.angle(), d.length() - circle.radius, text)))
    }

    /** The angular dimension the placing click would leave: the sector the cursor is in, at its distance. */
    fun angularDimension(c: PreviewContext): List<PreviewShape> {
        val l1 = lineOf(c.element(0), c.ev) ?: return emptyList()
        val l2 = lineOf(c.element(1), c.ev) ?: return emptyList()
        val v = FilletMath.cornerOf(l1, l2) ?: return emptyList()
        val (s1, s2) = AngularDimension.signsToward(l1.dir, l2.dir, c.cursor - v)
        val d1 = l1.dir * s1.toDouble()
        val d2 = l2.dir * s2.toDouble()
        val r = (c.cursor - v).length()
        if (r < Vec2.EPS) return emptyList()
        val opening = Quantity.rad(kotlin.math.acos(d1.normalized().dot(d2.normalized()).coerceIn(-1.0, 1.0)))
        return listOf(PreviewShape.Dim(AngularDimension.graphicOf(v, d1, d2, r, Format.quantity(opening))))
    }

    /** A dimension picks *points* as elements, so its span comes from the element list. */
    private fun pointOfPick(
        c: PreviewContext,
        i: Int,
    ): Vec2? = c.element(i)?.let { (c.ev.valueOf(it.ref) as? PointValue)?.p }

    private fun rotate(
        v: Vec2,
        a: Double,
    ): Vec2 = Vec2(v.x * cos(a) - v.y * sin(a), v.x * sin(a) + v.y * cos(a))
}
