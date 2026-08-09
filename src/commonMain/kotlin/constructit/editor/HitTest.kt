package constructit.editor

import constructit.core.ArcValue
import constructit.core.BezierValue
import constructit.core.ChainValue
import constructit.core.CircleValue
import constructit.core.EllipseValue
import constructit.core.EllipticArcValue
import constructit.core.Evaluator
import constructit.core.LineValue
import constructit.core.LoopValue
import constructit.core.Path3Value
import constructit.core.Point3Value
import constructit.core.PointSetValue
import constructit.core.PointValue
import constructit.core.RayValue
import constructit.core.RegionValue
import constructit.core.SegmentValue
import constructit.core.SolidValue
import constructit.core.Sphere3Value
import constructit.dsl.valueOf
import constructit.geom.Arc
import constructit.geom.Chain
import constructit.geom.Circle
import constructit.geom.Curve3Element
import constructit.geom.Curves3
import constructit.geom.GeomMath
import constructit.geom.Path3
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Ray3
import constructit.geom.Segment
import constructit.geom.Sphere3
import constructit.geom.Spheres3
import constructit.geom.Vec2
import constructit.geom.Vec3
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
    // The per-kind "nearest draggable point / curve / annotation" searches that used to live here are gone:
    // a SELECT click now collects *all* of them and ranks them in one place (`Editor.pickAt`), so having three
    // one-line filters here as well would be two statements of one precedence — and it was exactly such a
    // duplicate that let a derived point be unreachable under its own curve.

    /**
     * The **jamb** of a thick path's opening nearest [world], within [tol] (OP-21) — the pick that makes an
     * opening editable where it is visible.
     *
     * A jamb is a line the plan convention *draws*, not an element, so it cannot take part in [nearestAll]'s
     * element search; it is measured by the very same rule ([distanceToSegment]) so that what looks near a
     * jamb is near it, and the caller resolves the winner into a handle ([Jamb.handle]) owned by the thick
     * path. This mirrors how a leg is addressed through its ortho path: nothing is stored, so nothing can go
     * stale when the carrier moves or a value re-sorts the drawing.
     *
     * Deliberately **not** ranked against the carrier leg here. A jamb crosses its own leg, so which of the
     * two the pointer means is a question of distance and the caller decides it (see `Editor.pointerDown`).
     */
    fun nearestJamb(
        doc: Document,
        ev: Evaluator,
        world: Vec2,
        tol: Double,
    ): Jamb? {
        var best: Jamb? = null
        var bestD = tol
        for (tp in doc.thickNetworks) {
            val el = tp.footprint
            if (!el.visible || el.space != doc.activeSpace.name) continue
            for (j in doc.jambsOf(tp, ev)) {
                val d = distToSegment(world, j.seg.a, j.seg.b)
                // ties go to the later interval, which is the one drawn on top — [nearestAll]'s rule
                if (d <= bestD) {
                    bestD = d
                    best = j
                }
            }
        }
        return best
    }

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
     *
     * Screen-independent for everything *in* the working plane, which is everything but one: a **height
     * point** (OP-25) stands off the plane, so how near the pointer is to it is a question only the [view]
     * can answer — see [distanceToHeightPoint].
     */
    fun distanceTo(
        ev: Evaluator,
        el: Element,
        world: Vec2,
        view: PlaneProjection? = null,
        plane: Plane3? = null,
    ): Double? {
        // An annotation's value is a scalar, so it has no geometry of its own to measure against: what is
        // pickable is the graphic it draws (OP-4). Measured to that, a dimension is picked exactly where it
        // is visible — its lines, its arc, and the number itself.
        el.annotation?.let { a -> return a.graphic(ev)?.let { distanceToGraphic(world, it) } }
        (el.handle as? HeightPointHandle)?.let { h -> return distanceToHeightPoint(h, world, view, ev) }
        (ev.valueOf(el.ref) as? Path3Value)?.let { return distanceToPath(it.path, world, view, plane) }
        (ev.valueOf(el.ref) as? Point3Value)?.let { return distanceToSpacePoint(it.p, world, view, plane) }
        (ev.valueOf(el.ref) as? Sphere3Value)?.let { return distanceToSphere(it.sphere, world, view, plane) }
        return distanceToValue(ev, el, world)
    }

    /**
     * How near [world] is to the point in space [p] — a **key point** of a curve in space, or a point riding
     * a coil (OP-26). Each view measures it where it draws it, which is [distanceToPath]'s rule for a point
     * rather than a chain:
     * - **in the plan**, its projection onto [plane], which is exactly where the canvas draws its dot;
     * - **in the 3D view**, the distance from the point to the pointer's viewing ray, in the plane's own
     *   orthonormal frame and therefore in millimetres — the height point's own measure ([distanceToHeightPoint]).
     *
     * **Drawn and picked in the plan, unlike a height point**, and the difference is not an inconsistency: a
     * height point's plan image is *its base's own dot*, so drawing it would say nothing and picking it would
     * take the grab from the point the plan actually edits (OP-25). A curve's key point has no such twin — it
     * lies on the projection of the curve, which the plan already draws — and a rider's angle is something the
     * plan can both show and edit (within its winding). What is drawn is picked, in both views.
     *
     * **Null with no view at all**: the snap resolver and the weld magnet ask a 2D question, and the answer
     * would be a *plane* point at the projection, which is not this point. A placing click therefore never
     * snaps onto one — the same refusal a height point makes, for the same reason.
     *
     * Public since OP-28, because scoring a branch is the same question as picking one: a click that has to
     * say *which* of two trilateration solutions it meant measures against exactly what the pick measures
     * against, in whichever view is driving, so the branch the user gets is the one they pointed at.
     */
    fun distanceToSpacePoint(
        p: Vec3,
        world: Vec2,
        view: PlaneProjection?,
        plane: Plane3?,
    ): Double? {
        val pl = plane ?: return null
        if (view == null) return null
        if (view.similarity) return (pl.toLocal(p) - world).length()
        val ray = view.viewRay(world)
        val d = ray.dir
        val len = d.length()
        if (len < Vec2.EPS) return null
        val local = lifted(pl, p)
        return (local - ray.origin).cross(d).length() / len
    }

    /**
     * How near [world] is to the **sphere locus** [s] (OP-28) — each view measuring it where it draws it,
     * which is the rule every 3D kind in this file follows.
     *
     * - **In the plan**, the distance to its **outline circle**: how far the pointer stands from the centre's
     *   projection, less the radius, unsigned — so the locus is grabbed on the ring the canvas draws and not
     *   across the whole disc, exactly as a drawn circle is picked on its rim and not at its centre.
     * - **In the 3D view**, the distance to the nearest of its **three great circles**, against the pointer's
     *   viewing ray in the plane's own (u, v, lift) frame and therefore in millimetres — the same measure
     *   [distanceToPath] takes, over the very polylines the renderer draws.
     *
     * **Null with no view at all**, which is the *point* in space's rule rather than the curve's, and
     * deliberately so: the snap resolver and the weld magnet ask a 2D question, and a placing click must
     * never land on a construction locus — what would be shared is a plane point at a projection, which is
     * not on the locus at all.
     */
    private fun distanceToSphere(
        s: Sphere3,
        world: Vec2,
        view: PlaneProjection?,
        plane: Plane3?,
    ): Double? {
        val pl = plane ?: return null
        if (view == null) return null
        if (view.similarity) return abs((pl.toLocal(s.center) - world).length() - s.radius)
        val ray = view.viewRay(world)
        if (ray.dir.length() < Vec3.EPS) return null
        return Spheres3.greatCircles(s).minOfOrNull { circle ->
            val local = Curves3.polyline(circle).map { p -> pl.toLocal(p).let { Vec3(it.x, it.y, pl.distanceTo(p)) } }
            local.zipWithNext().minOfOrNull { (a, b) -> distRayToSegment(ray, a, b) } ?: Double.MAX_VALUE
        }
    }

    /**
     * How near [world] is to the curve in space [path] — **each view measuring the curve where it draws it**
     * (OP-26), which is the height point's rule (OP-25) applied to a whole chain.
     *
     * - **In the plan** (a similarity, or no view at all) the curve's image is its projection onto [plane],
     *   and that projection is what the canvas draws — so the distance is the ordinary plane-space distance
     *   to those very pieces ([distToPiece]), in millimetres, and the ordinary tolerance applies unchanged.
     * - **In the 3D view** the curve is drawn where it really is, so the pointer's *viewing ray* is what it
     *   is measured against — in the plane's own orthonormal (u, v, lift) frame, hence again in millimetres.
     *   Measured against the drawn polyline, so what looks near the curve is near it, exactly as a Bézier and
     *   an ellipse already are.
     *
     * Null without a [plane]: a curve in space has no image until something says which way it is being
     * looked at, and a caller that offers none is asking a question this is no answer to.
     */
    private fun distanceToPath(
        path: Path3,
        world: Vec2,
        view: PlaneProjection?,
        plane: Plane3?,
    ): Double? {
        val pl = plane ?: return null
        if (view == null || view.similarity) {
            return Curves3.projectedOnto(path, pl).minOfOrNull { distToPiece(world, it) }
        }
        val ray = view.viewRay(world)
        if (ray.dir.length() < Vec3.EPS) return null
        val local = Curves3.polyline(path).map { p -> pl.toLocal(p).let { Vec3(it.x, it.y, pl.distanceTo(p)) } }
        return local.zipWithNext().minOfOrNull { (a, b) -> distRayToSegment(ray, a, b) }
    }

    /**
     * How far along the coil [h] the pointer at [world] is aiming — the **angle** a click on a helix states,
     * in the rider's own parameterization (`Curve3Element.Helix3.atAngle`), or null where the view cannot say.
     *
     * **The 2D/3D split is here, in the pick, and not in the point** (OP-26, the queue's own design), and it
     * is the very split [distanceToPath] already makes for the same reason:
     * - **In the plan** every winding of a coil projects onto the same image, so a click there can only
     *   honestly name a bearing — the angle comes back reduced into `[0, 360°)`, which is the **first**
     *   winding. Typing reaches any other one afterwards (OP-13), and a drag keeps the winding it is on
     *   ([OnHelixHandle]), because the plan has nothing to say about which winding the pointer meant.
     * - **In the 3D view** the pointer's ray meets the drawn curve on a *known* winding, so the angle comes
     *   back as it is — past 360° where that is where the curve was hit.
     *
     * Measured against the very polyline both views draw ([Curves3.sample], at [Curves3.drawSteps]), refined
     * within the chord the pointer is nearest: what is on screen is what the pointer reaches, which is the
     * same rule a Bézier, an ellipse and a curve in space are all picked by.
     */
    fun helixAngleAt(
        h: Curve3Element.Helix3,
        plane: Plane3,
        world: Vec2,
        view: PlaneProjection?,
    ): Double? {
        val n = Curves3.drawSteps(h)
        val plan = view == null || view.similarity
        val ray = if (plan) null else view!!.viewRay(world)
        if (ray != null && ray.dir.length() < Vec3.EPS) return null
        var best = -1.0
        var bestD = Double.MAX_VALUE
        // the drawn polyline's own vertices, in the frame the view measures in: plane (u, v) for the plan,
        // the plane's (u, v, lift) for the ray — [distanceToPath]'s two frames, unchanged
        var prev = h.at(0.0)
        for (i in 1..n) {
            val here = h.at(i.toDouble() / n)
            val (d, s) =
                if (ray == null) {
                    nearestOnSegment(world, plane.toLocal(prev), plane.toLocal(here))
                } else {
                    rayToSegment(ray, lifted(plane, prev), lifted(plane, here))
                }
            if (d < bestD) {
                bestD = d
                best = (i - 1 + s) / n
            }
            prev = here
        }
        if (best < 0.0) return null
        val theta = h.totalAngle * best
        if (!plan) return theta
        // the first winding, which is all the plan can state: one whole turn taken out as many times as it goes
        val turn = 2.0 * kotlin.math.PI
        return theta - turn * kotlin.math.floor(theta / turn)
    }

    /** A point of space in the plane's own orthonormal (u, v, lift) frame — where a ray pick is measured. */
    private fun lifted(
        plane: Plane3,
        p: Vec3,
    ): Vec3 = plane.toLocal(p).let { Vec3(it.x, it.y, plane.distanceTo(p)) }

    /** The distance from [p] to the segment `a`..`b`, and the clamped parameter where that happened. */
    private fun nearestOnSegment(
        p: Vec2,
        a: Vec2,
        b: Vec2,
    ): Pair<Double, Double> {
        val ab = b - a
        val t = if (ab.length() < Vec2.EPS) 0.0 else ((p - a).dot(ab) / ab.dot(ab)).coerceIn(0.0, 1.0)
        return (p - (a + ab * t)).length() to t
    }

    /**
     * Closest approach between the ray [r] (`t >= 0`) and the segment `a`..`b` — the standard two-parameter
     * clamp, with the parallel case falling back to the distance from the segment's ends.
     *
     * Written out here rather than reached for from `Geom3` because this is picking's own measure: the ray is
     * a *pointer*, so it is clamped behind the eye and the segment is clamped to its own extent — a pick must
     * never be answered by a point on the backward extension of either.
     */
    private fun distRayToSegment(
        r: Ray3,
        a: Vec3,
        b: Vec3,
    ): Double = rayToSegment(r, a, b).first

    /**
     * [distRayToSegment]'s answer **with the parameter it happened at** — the distance for a pick, the
     * parameter for a pick that has to say *where along* the thing it hit the pointer was aiming
     * ([helixAngleAt]). One arithmetic, so the winding a click resolves to is the winding it looks nearest.
     */
    private fun rayToSegment(
        r: Ray3,
        a: Vec3,
        b: Vec3,
    ): Pair<Double, Double> {
        val d1 = r.dir
        val d2 = b - a
        val w = r.origin - a
        val a11 = d1.dot(d1)
        val a12 = d1.dot(d2)
        val a22 = d2.dot(d2)
        val b1 = d1.dot(w)
        val b2 = d2.dot(w)
        val det = a11 * a22 - a12 * a12
        // the unclamped optimum, or the segment's own start where the two are parallel (or it is degenerate)
        var t = if (det <= Vec3.EPS || a22 <= Vec3.EPS || a11 <= Vec3.EPS) 0.0 else (a11 * b2 - a12 * b1) / det
        // …then one pass of clamping each parameter against the other, which is exact once one of the two
        // bounds is active — and a pick only ever activates the ray's, at the eye
        t = t.coerceIn(0.0, 1.0)
        val s = (if (a11 <= Vec3.EPS) 0.0 else d1.dot(a + d2 * t - r.origin) / a11).coerceAtLeast(0.0)
        if (a22 > Vec3.EPS) t = (d2.dot(r.origin + d1 * s - a) / a22).coerceIn(0.0, 1.0)
        return (r.origin + d1 * s - (a + d2 * t)).length() to t
    }

    /**
     * How near [world] is to the height point [h]: the distance from the point to the **pointer's viewing
     * ray**, in the plane's own orthonormal frame and therefore in millimetres — so the caller's ordinary
     * plane-space tolerance (`Editor.pickToleranceAt`, ten pixels through the local scale) applies to it
     * unchanged, which is what "the same local tolerance logic tool picks use" means.
     *
     * **Null — not pickable — under a similarity or with no view at all**, and that is the whole of where a
     * height point lives. A 2D canvas looks along its plane's normal, so a height has no image there and the
     * point's would sit exactly on its base's; drawing a second dot on top of the apex dot the plan already
     * has would be chrome that says nothing, and picking one there would take the grab from the base — which
     * *is* what the plan edits (OP-25). What is drawn is what is picked, and neither happens in the plan. A
     * caller that offers no view at all — the snap resolver, the weld magnet — is asking a 2D question, and
     * an off-plane point is no answer to it.
     */
    private fun distanceToHeightPoint(
        h: HeightPointHandle,
        world: Vec2,
        view: PlaneProjection?,
        ev: Evaluator,
    ): Double? {
        if (view == null || view.similarity) return null
        val (base, lift) = h.localAt(ev) ?: return null
        val ray = view.viewRay(world)
        val d = ray.dir
        val len = d.length()
        if (len < Vec2.EPS) return null
        val w = Vec3(base.x - ray.origin.x, base.y - ray.origin.y, lift - ray.origin.z)
        return w.cross(d).length() / len
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
            // A ray is a segment clamped on **one** side: perpendicular distance where the projection lands
            // on the ray, distance to the origin behind it. Missing this case made a ray unpickable
            // *altogether* — it drew, and a marquee took it ([meetsRect] has the kind), but no click could
            // reach it, so it could not select, could not cycle, and could not fill a LINE slot either
            // (a reported defect: Perpendicular refused a ray). One distance rule, one place to add a kind.
            is RayValue -> distToRay(world, v.ray.origin, v.ray.dir)
            is CircleValue -> abs((world - v.circle.center).length() - v.circle.radius)
            is SegmentValue -> distToSegment(world, v.seg.a, v.seg.b)
            is ArcValue -> distToArc(world, v.arc)
            // measured against the drawn polyline, for the reason [distToPiece] gives
            is EllipseValue ->
                SceneRenderer.tessellate(v.ellipse, true).zipWithNext().minOf { (a, b) -> distToSegment(world, a, b) }
            is EllipticArcValue ->
                SceneRenderer.tessellate(v.arc).zipWithNext().minOf { (a, b) -> distToSegment(world, a, b) }
            // A Bézier is measured against its own tessellation — the same polyline the renderer
            // draws, so what looks near the curve is near it.
            is BezierValue ->
                GeomMath.tessellateBezier(v.bezier).zipWithNext().minOfOrNull { (a, b) -> distToSegment(world, a, b) }
            is LoopValue -> v.loop.elements.minOfOrNull { distToPiece(world, it) }
            // A cutting chain (OP-22's extension) is measured against everything it draws: its finite run,
            // and — where it is unbounded — the two rays, by the same rule a drawn ray is picked by. One
            // distance rule, one place to add a kind.
            is ChainValue ->
                (
                    v.chain.pieces.map { distToPiece(world, it) } +
                        ((v.chain as? Chain.Open)?.let { listOf(distToRay(world, it.start.origin, it.start.dir), distToRay(world, it.end.origin, it.end.dir)) } ?: emptyList())
                ).minOrNull()
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
            // Everything else has no geometry *in this space* and is therefore not pickable, deliberately —
            // the kinds here and the kinds `SceneRenderer` draws must otherwise agree, since a drawn thing
            // that cannot be picked is the defect a missing ray case was. The one drawn kind left out is a
            // **PointSetValue**: an ordered solution set is scaffolding for the `Select` beside it (OP-1), and
            // it is that selected point — an element of its own — that a click is meant to reach.
            else -> null
        }

    /**
     * Distance to [el] **as the document's active sketch space shows it** (OP-17) — the one place the
     * space enters picking. For everything drawn in the space that is its own geometry; for the part a
     * *face* or *datum* space cuts into it is that space's reference context — the face rectangle, or the
     * datum's hinge — which is what that solid looks like there.
     */
    private fun distanceIn(
        doc: Document,
        ev: Evaluator,
        el: Element,
        world: Vec2,
        tip: Element?,
        view: PlaneProjection?,
        plane: Plane3?,
    ): Double? {
        doc.partOutlineOf(el, ev, tip)?.let { return ringDistance(world, it) }
        // ...and *only* as that context: a part with none to measure against (an unbounded datum hinge, a solid
        // with no value) is simply not pickable here. Falling back to its own geometry would measure a
        // coordinate from another space, which is the one thing one-canvas-one-space exists to prevent.
        if (el.space != doc.activeSpace.name) return null
        return distanceTo(ev, el, world, view, plane)
    }

    private fun ringDistance(
        world: Vec2,
        ring: List<Vec2>,
    ): Double = ring.indices.minOf { distToSegment(world, ring[it], ring[(it + 1) % ring.size]) }

    /**
     * Every visible element satisfying [filter] within [tol] of [world], nearest first; ties go to the
     * most recently created, which is the one drawn on top.
     *
     * [ghosts] are hidden elements the caller is currently **drawing** as ghosts (OP-18's *Show hidden*,
     * [Editor.ghostElements]) and therefore wants reachable. Empty by default, which is what keeps every
     * construction pick exactly what it was: only the routes that *select* pass a ghost set.
     *
     * **What is visible wins.** A ghost is offered only when nothing live is within [tol] at all — a search
     * that found a visible element returns no ghosts, whatever their distances. One rule, and it is the
     * conservative one: a ghost is a picture of something the user took out of the drawing, so it may never
     * take a click away from geometry that is really there.
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
        view: PlaneProjection? = null,
        ghosts: Set<Element> = emptySet(),
        filter: (Element) -> Boolean,
    ): List<Pair<Element, Double>> {
        // the part the active face space belongs to, as it stands (OP-17's tip rule) — resolved once per
        // search, because resolving it walks the graph and this asks it of every element
        val tip = doc.facePartTip(ev)
        // …and the active plane, for the same reason: a curve in space (OP-26) is measured against the frame
        // this canvas is looking along, and resolving that is a node evaluation
        val plane = doc.activePlane3(ev)
        val hits =
            doc.elements
                .asSequence()
                .withIndex()
                .filter { (_, el) -> (el.visible || el in ghosts) && doc.addressableIn(el, tip) && filter(el) }
                .mapNotNull { (i, el) -> distanceIn(doc, ev, el, world, tip, view, plane)?.let { Triple(el, it, i) } }
                .filter { it.second <= tol }
                .sortedWith(compareBy({ it.second }, { -it.third }))
                .toList()
        val live = if (ghosts.isEmpty()) hits else hits.filter { it.first.visible }
        return (if (live.isEmpty()) hits else live).map { it.first to it.second }
    }

    /** Nearest element (point or curve) satisfying [filter], within [tol]. */
    fun nearest(
        doc: Document,
        ev: Evaluator,
        world: Vec2,
        tol: Double,
        view: PlaneProjection? = null,
        ghosts: Set<Element> = emptySet(),
        filter: (Element) -> Boolean,
    ): Element? = nearestAll(doc, ev, world, tol, view, ghosts, filter).firstOrNull()?.first

    // ---- marquee: pick everything a rectangle meets (OP-16) ----

    /**
     * Every visible element whose geometry meets the world rectangle spanned by [a] and [b] — what a
     * rubber-band drag in SELECT mode selects. *Meets*, not *contains*: an architect rubber-bands over
     * a room to grab the walls crossing it, and requiring full containment would drop every one of them.
     *
     * [ghosts] joins them in, for the reason a rubber band exists: it takes **what is drawn**. While *Show
     * hidden* is on a ghost is drawn, so a band across it that left it out would be the tool disagreeing with
     * the picture — and the visible-wins rule of [nearestAll] has nothing to decide here, since a band is not
     * a competition between candidates.
     */
    fun within(
        doc: Document,
        ev: Evaluator,
        a: Vec2,
        b: Vec2,
        view: PlaneProjection? = null,
        ghosts: Set<Element> = emptySet(),
    ): List<Element> {
        val lo = Vec2(kotlin.math.min(a.x, b.x), kotlin.math.min(a.y, b.y))
        val hi = Vec2(kotlin.math.max(a.x, b.x), kotlin.math.max(a.y, b.y))
        val tip = doc.facePartTip(ev)
        val plane = doc.activePlane3(ev)
        return doc.elements.filter { el ->
            (el.visible || el in ghosts) && doc.addressableIn(el, tip) &&
                (
                    doc.partOutlineOf(el, ev, tip)?.let { r -> ringMeets(r, lo, hi) }
                        // the same rule as [distanceIn]: the part is met as this space's reference context or not
                        // at all, never by geometry belonging to another space's coordinates
                        ?: (el.space == doc.activeSpace.name && meetsRect(ev, el, lo, hi, view, plane))
                )
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
        view: PlaneProjection?,
        plane: Plane3?,
    ): Boolean {
        // A curve in space (OP-26) is met where the band *sees* it — its projection onto the plane in the
        // plan, and in the 3D view its image mapped back onto the plane the band was dragged on, which is
        // the height point's own rule for the same question.
        (ev.valueOf(el.ref) as? Path3Value)?.let { v ->
            val pl = plane ?: return false
            if (view == null || view.similarity) {
                return Curves3.projectedOnto(v.path, pl).any { pieceMeets(it, lo, hi) }
            }
            return Curves3.polyline(v.path).any { p ->
                val at = view.toScreenLifted(pl.toLocal(p), pl.distanceTo(p))?.let { view.toPlane(it) }
                at != null && inRect(at, lo, hi)
            }
        }
        // ...and a **point in space** (OP-26) the same way, in whichever view the band was dragged in: its
        // projection in the plan, where the canvas draws its dot, and its image in the 3D view
        (ev.valueOf(el.ref) as? Point3Value)?.let { v ->
            if (el.handle is HeightPointHandle) return@let
            val pl = plane ?: return false
            if (view == null || view.similarity) return inRect(pl.toLocal(v.p), lo, hi)
            val at = view.toScreenLifted(pl.toLocal(v.p), pl.distanceTo(v.p))?.let { view.toPlane(it) } ?: return false
            return inRect(at, lo, hi)
        }
        // a height point is met where it is *seen* — its image, mapped back onto the plane the band was
        // dragged on — which is the same "only through a view that can place it" rule the pick follows
        // …and a **sphere locus** (OP-28) is met where the band sees its image: its outline in the plan, its
        // great circles in the 3D view — the same two pictures the renderer draws and the pick measures.
        (ev.valueOf(el.ref) as? Sphere3Value)?.let { v ->
            val pl = plane ?: return false
            if (view == null || view.similarity) {
                return pieceMeets(ProfileElement.CircleE(Circle(pl.toLocal(v.sphere.center), v.sphere.radius)), lo, hi)
            }
            return Spheres3.greatCircles(v.sphere).any { circle ->
                Curves3.polyline(circle).any { p ->
                    val at = view.toScreenLifted(pl.toLocal(p), pl.distanceTo(p))?.let { view.toPlane(it) }
                    at != null && inRect(at, lo, hi)
                }
            }
        }
        (el.handle as? HeightPointHandle)?.let { h ->
            if (view == null || view.similarity) return false
            val (base, lift) = h.localAt(ev) ?: return false
            val at = view.toScreenLifted(base, lift)?.let { view.toPlane(it) } ?: return false
            return inRect(at, lo, hi)
        }
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
            is EllipseValue -> polyMeets(SceneRenderer.tessellate(v.ellipse, true), lo, hi)
            is EllipticArcValue -> polyMeets(SceneRenderer.tessellate(v.arc), lo, hi)
            is BezierValue -> polyMeets(GeomMath.tessellateBezier(v.bezier), lo, hi)
            is LoopValue -> v.loop.elements.any { pieceMeets(it, lo, hi) }
            is ChainValue ->
                v.chain.pieces.any { pieceMeets(it, lo, hi) } ||
                    (v.chain as? Chain.Open)?.let { c ->
                        listOf(c.start, c.end).any { spanMeets(it.origin, it.dir, lo, hi, 0.0, Double.POSITIVE_INFINITY) }
                    } == true
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
            is ProfileElement.EllipticArcE -> polyMeets(SceneRenderer.tessellate(e.arc), lo, hi)
            is ProfileElement.EllipseE -> polyMeets(SceneRenderer.tessellate(e.ellipse, e.ccw), lo, hi)
        }

    /**
     * Distance to a bare segment — the same clamped rule everything else here uses, exposed because a
     * *drawing* can be pickable without being an element: a thick path's jamb (OP-21) is measured with it,
     * and so is compared against the elements around it on equal terms.
     */
    fun distanceToSegment(
        world: Vec2,
        seg: Segment,
    ): Double = distToSegment(world, seg.a, seg.b)

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
            // An ellipse has no "distance to the curve" in closed form, so it is measured — like a Bézier
            // — against the very polyline the renderer draws: what looks near the curve is near it.
            is ProfileElement.EllipticArcE ->
                SceneRenderer.tessellate(e.arc).zipWithNext().minOf { (a, b) -> distToSegment(world, a, b) }
            is ProfileElement.EllipseE ->
                SceneRenderer.tessellate(e.ellipse, e.ccw).zipWithNext().minOf { (a, b) -> distToSegment(world, a, b) }
        }

    /** Distance to a ray: [distToSegment]'s clamp, on the origin side only — the far side runs on. */
    private fun distToRay(
        p: Vec2,
        origin: Vec2,
        dir: Vec2,
    ): Double {
        if (dir.length() < Vec2.EPS) return (p - origin).length()
        val d = dir.normalized()
        val t = (p - origin).dot(d)
        return if (t <= 0.0) (p - origin).length() else (p - (origin + d * t)).length()
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
