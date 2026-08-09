package constructit.editor

import constructit.geom.Arc
import constructit.geom.Circle
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Plane3
import constructit.geom.Ray3
import constructit.geom.Vec2
import constructit.geom.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Screen pixels ↔ **one plane's own (u, v)** — the single authority two consumers share.
 *
 * The consumers are the *event* path (a pointer position becomes plane coordinates, and a pick tolerance
 * in pixels becomes one in millimetres) and the *rendering* path ([SceneRenderer] projects the same
 * plane's geometry back onto the screen). Sharing one type is what makes "what you clicked is what you
 * see" a property of the code rather than a coincidence between two conversions.
 *
 * Two implementations, and the difference between them is the whole of edit-in-3D slice 1:
 * - [Camera] — the 2D canvas. A **similarity** (uniform scale, one flip), so the scale is one number for
 *   the whole plane, a circle's image is a circle, and screen-space furniture (the grid, the ruler) is
 *   exact. Every existing golden goes through this path unchanged.
 * - [PlanePerspective] — the working plane seen in the 3D view. Not a similarity: the scale varies across
 *   the plane, a circle's image is an ellipse, and a point can have no image at all. Everything that
 *   assumption bought has to be *asked for* here instead, which is what the members below exist for.
 *
 * Pure, in `commonMain`, so both paths are asserted headlessly (OP-12) — the browser shell contributes
 * only which viewport an event came from.
 */
interface PlaneProjection {
    /**
     * Where plane point [p] lands on screen (pixels, y down), or **null** when it has no image — at or
     * behind the eye plane under perspective. A caller that is drawing drops the primitive; a caller
     * measuring a direction has nothing to measure.
     */
    fun toScreen(p: Vec2): Vec2?

    /**
     * The plane point under screen position [s], or **null** when there is none: the ray runs parallel to
     * the plane, or meets it behind the eye.
     *
     * Null is the honest answer and the reason this is nullable at all — the alternative is a NaN or an
     * enormous coordinate reaching the editor, which would place geometry where nobody pointed.
     */
    fun toPlane(s: Vec2): Vec2?

    /**
     * The **local** scale at plane point [p], in pixels per millimetre: one number, so a pick tolerance
     * stated in pixels ([Editor.pickToleranceAt]) has a plane-space radius, and a mark whose size is
     * stated in pixels (a frame axis, an arrowhead) has a plane-space length.
     *
     * Isotropic by construction — the geometric mean of the two principal scales — because a tolerance is
     * a disc, not an ellipse. For a similarity it is simply the scale.
     */
    fun scaleAt(p: Vec2): Double

    /**
     * True when [scaleAt] returned a **clamped** value at [p]: the plane is so far away or so nearly
     * edge-on there that an honest tolerance would be metres wide. The caller says so rather than letting
     * a pick silently reach across the drawing (see [Editor.pointerMove]).
     */
    fun scaleClampedAt(p: Vec2): Boolean = false

    // ---- one axis further: the plane's own frame in 3D (OP-25, the height point) ----
    //
    // **The ray seam's answer.** A height point is not *on* the working plane, so neither drawing it nor
    // dragging it can be said in plane coordinates alone — and the question was whether `Viewport3` should
    // hand the editor a ray or the projection should grow one. It grows one, for the reason this interface
    // exists at all: it is already the single authority both the event path and the rendering path share,
    // and a height point is exactly "the plane's own frame, one axis further" — so the two paths keep
    // agreeing by construction. `Editor` stays headless (it asks its `pointing` projection, never a
    // camera), the 2D canvas needs no code (the defaults below *are* its answer), and a test asserts the
    // whole of it by building a `PlanePerspective` of its own.
    //
    // Everything here is stated in the plane's own **(u, v, lift)** coordinates — an orthonormal frame, so a
    // distance in it is millimetres — rather than in the world: the 2D [Camera] has no plane and could not
    // answer a world question at all, and the consumer (a height point's base and height) speaks that frame
    // already.

    /**
     * Where the point standing [lift] mm off the plane above [p] lands on screen, or null when it has no
     * image ([toScreen]'s rule, one axis up).
     *
     * The default is the **orthographic** answer, and it is the honest one for a similarity: a 2D canvas
     * looks along its plane's normal, so a lifted point's image is its base's image and a height is
     * invisible there. That is why a height point draws and picks in the 3D view only — see
     * [SceneRenderer] and [HitTest].
     */
    fun toScreenLifted(
        p: Vec2,
        lift: Double,
    ): Vec2? = toScreen(p)

    /**
     * The **viewing ray** through plane point [p], in the plane's own (u, v, lift) coordinates: what a drag
     * of an out-of-plane handle is measured against (OP-25's ray-to-line).
     *
     * The default is again the orthographic one — straight along the normal — which is what makes the
     * near-parallel clamp fire in the 2D canvas: a height line *is* that ray there, so the plan can say
     * nothing about a height, and it declines instead of exploding.
     */
    fun viewRay(p: Vec2): Ray3 = Ray3(Vec3(p.x, p.y, 0.0), Vec3(0.0, 0.0, 1.0))

    /**
     * The pointer's own ray through plane point [p], in **world** coordinates — or null where this
     * projection has no eye to shoot it from.
     *
     * The seam that makes a **body clickable in the 3D view** (OP-13's 2D/3D split, the helix rider's rule
     * generalized: the ray answers what the plan cannot). It grows here rather than on [Editor] for the
     * reason [viewRay] does — this interface is already the one authority the event path and the rendering
     * path share — and it is stated in the world rather than in the plane's frame because what it is
     * measured against is a **mesh**, which has no plane and belongs to no space.
     *
     * **Null is the 2D canvas's honest answer, not a stub.** A plan looks along its plane's normal, so
     * *every* body over the drawing lies on the same ray and depth decides nothing; the plan already has its
     * own answer — the footprint hint — and the one thing it must not do is invent a third. So the 2D canvas
     * keeps exactly the picking it had, and 3D picking is precisely what the 3D view adds.
     *
     * The direction is a unit vector, as [Camera3.unproject]'s is, so the parameter it comes back with is
     * millimetres and two hits compare directly.
     */
    fun eyeRay(p: Vec2): Ray3? = null

    /**
     * Whether this projection is a **similarity**: uniform scale and no perspective, so a length in
     * pixels means the same thing everywhere on the plane.
     *
     * What turns on it is exactly the drawing that is stated in screen space and would otherwise be a
     * lie: the grid and the corner ruler. Both are read off *one* scale, and under perspective there is
     * no such number — the 3D view has a ground grid of its own, drawn in the world where it belongs.
     */
    val similarity: Boolean

    /** The plane-space rectangle the viewport covers — what an infinite line or ray is clipped to. */
    fun viewRect(
        wPx: Double,
        hPx: Double,
    ): Pair<Vec2, Vec2>

    /**
     * [arc] as a **plane-space** polyline, which the caller then projects vertex by vertex.
     *
     * Tessellating in plane space is what a non-similarity requires: chords chosen from the arc's own
     * geometry stay chords of the arc after projection, whereas a screen-space step count assumes the
     * image of an equal angular step is an equal screen step — true under a similarity, false under
     * perspective, where the far half of an arc spanning depth would be sampled as finely as the near
     * half is coarsely.
     */
    fun arcPoints(arc: Arc): List<Vec2>

    /**
     * Draw [c] — the one primitive whose *shape* the projection decides. Under a similarity a circle's
     * image is a circle, so it goes to [DrawTarget.circle] and the goldens keep their one-element path;
     * under perspective it is an ellipse, which the target has no primitive for, so it is emitted as a
     * projected ring.
     */
    fun drawCircle(
        target: DrawTarget,
        c: Circle,
        style: Style,
    )
}

/**
 * The working plane as the 3D view's perspective camera sees it: the **ray seam** in both directions.
 *
 * Built per event and per repaint from the camera and the plane, never stored — the same discipline
 * [Scene3] follows, so nothing here can disagree with the model or the view after either changes.
 */
class PlanePerspective(
    val plane: Plane3,
    val camera: Camera3,
    val widthPx: Double,
    val heightPx: Double,
) : PlaneProjection {
    override val similarity: Boolean get() = false

    /**
     * The world→clip matrix, built **once per projection instead of once per vertex** — the whole of the
     * orbit's cost, and the one place it could be paid.
     *
     * [Camera3.project] rebuilds `perspective()` and `lookAt()` (three normalized cross products, a tangent,
     * four sines and cosines) and multiplies two 4x4s for *every* point handed to it. A view of an imported
     * assembly projects a few hundred thousand points per frame, so an orbit was spending ~53 ms of a frame
     * building the same sixteen numbers over and over; with the matrix cached the same points cost ~2 ms.
     * The arithmetic is unchanged — [Camera3.projectWith] is the very function `project` delegates to, and
     * the one [Painter3] and the GPU already share (OP-12: one projection pipeline, three consumers).
     *
     * **What makes the cache safe is that this object is a value, not state.** The class note above states
     * the discipline it is built on: a `PlanePerspective` is constructed per event and per repaint from the
     * camera and the plane and never stored, and all four of its inputs are `val`s over immutable data
     * ([Camera3] is a data class whose gestures return copies). So there is no write that could invalidate
     * this, and no invalidation to get wrong. That is also why the cache lives here and **not** in
     * [Camera3]: the camera *is* mutable state in [Viewport3] (a field reassigned by every orbit step), and a
     * matrix cached beside it would need exactly the invalidation this arrangement does not have.
     */
    private var vpCache: Mat4? = null

    /**
     * How many times [vp] actually built the matrix over this projection's life — 0 before anything is
     * drawn, 1 afterwards however many points went through it.
     *
     * `internal`, and it exists for the performance regression alone (`ProjectionCostTest`): the property
     * being asserted is a *count of work*, which nothing observable from outside the module can report.
     * Cheaper than the alternative of a spy over [Camera3], which is a data class with no seam to subclass.
     */
    internal var matrixBuilds: Int = 0
        private set

    private fun vp(): Mat4 {
        vpCache?.let { return it }
        val m = camera.viewProjection(widthPx, heightPx)
        vpCache = m
        matrixBuilds++
        return m
    }

    override fun toScreen(p: Vec2): Vec2? = camera.projectWith(vp(), plane.toWorld(p), widthPx, heightPx)

    override fun toPlane(s: Vec2): Vec2? {
        val ray = camera.unproject(s, widthPx, heightPx)
        val t = Geom3.rayPlane(ray, plane) ?: return null
        return plane.toLocal(ray.at(t))
    }

    override fun toScreenLifted(
        p: Vec2,
        lift: Double,
    ): Vec2? = camera.projectWith(vp(), plane.toWorld(p) + plane.normal.normalized() * lift, widthPx, heightPx)

    /**
     * The eye, and the direction from it through the point on the plane — the same line the pointer's ray
     * is, expressed in this plane's own frame (see the interface note).
     */
    override fun viewRay(p: Vec2): Ray3 {
        val n = plane.normal.normalized()
        val eye = plane.toLocal(camera.eye)
        val d = plane.toWorld(p) - camera.eye
        return Ray3(Vec3(eye.x, eye.y, plane.distanceTo(camera.eye)), Vec3(d.dot(plane.u), d.dot(plane.v), d.dot(n)))
    }

    /**
     * The very ray the pointer cast to reach [p] — from the eye, through the point on the working plane the
     * cursor landed on. Reconstructed rather than remembered, and it is exact: the editor's plane
     * coordinates come from [toPlane], which is [Camera3.unproject] met with this plane, so shooting back
     * out through the meeting point retraces the same line.
     */
    override fun eyeRay(p: Vec2): Ray3? = Ray3(camera.eye, (plane.toWorld(p) - camera.eye).normalized())

    /**
     * The perspective scale at [p], in closed form (checked against finite differences of [toScreen] in
     * `Edit3DTest`): `focalPx/depth` is the scale of a surface facing the eye squarely at that depth, and
     * the two corrections are the plane's own tilt.
     *
     * The area scale of the plane→screen map at [p] is `(focalPx/depth)² · |n̂·r̂| · (|r|/depth)`, where r
     * is the vector from the eye to the point: `|n̂·r̂|` is the foreshortening of a tilted plane and
     * `|r|/depth` the widening away from the view axis. This returns its square root — the isotropic
     * scale — clamped from below at [nominalScale] / [MAX_TOLERANCE_FACTOR].
     */
    override fun scaleAt(p: Vec2): Double = max(rawScaleAt(p), nominalScale / MAX_TOLERANCE_FACTOR)

    override fun scaleClampedAt(p: Vec2): Boolean = rawScaleAt(p) < nominalScale / MAX_TOLERANCE_FACTOR

    /** The scale a plane facing the eye squarely would have at the camera's own target — the view's own. */
    val nominalScale: Double get() = camera.focalPx(heightPx) / camera.distance

    private fun rawScaleAt(p: Vec2): Double {
        val w = plane.toWorld(p)
        val r = w - camera.eye
        val depth = r.dot(camera.forward())
        if (depth <= Camera3.MIN_NEAR) return 0.0
        val obliquity = abs(plane.normal.normalized().dot(r.normalized()))
        val widening = r.length() / depth
        return camera.focalPx(heightPx) / depth * sqrt(obliquity * widening)
    }

    /**
     * The plane patch the viewport covers, sampled at nine screen positions (the corners, the edge
     * midpoints and the centre) — sampled rather than solved because the exact region is a triangle or a
     * half-plane once the plane's horizon crosses the viewport, and the only consumer is the clip box of
     * an infinite line.
     *
     * Capped at [MAX_SPAN_FACTOR] camera distances about the centre of what was hit: a plane seen nearly
     * edge-on reaches the horizon, and a construction line drawn to the horizon is a polyline of
     * astronomical coordinates, not a line the user can see. Nothing is hit at all only when the whole
     * viewport looks away from the plane, and then the box is a degenerate point at the plane's origin —
     * which clips every infinite line away, i.e. draws nothing, which is correct.
     */
    override fun viewRect(
        wPx: Double,
        hPx: Double,
    ): Pair<Vec2, Vec2> {
        val hits = ArrayList<Vec2>(9)
        // the centre first, so it is the one the cap is measured about even when a corner looks past the
        // plane's horizon
        toPlane(Vec2(wPx / 2.0, hPx / 2.0))?.let { hits.add(it) }
        for (fx in listOf(0.0, 0.5, 1.0)) {
            for (fy in listOf(0.0, 0.5, 1.0)) {
                toPlane(Vec2(fx * wPx, fy * hPx))?.let { hits.add(it) }
            }
        }
        val centre = hits.firstOrNull() ?: return Vec2(0.0, 0.0) to Vec2(0.0, 0.0)
        val span = MAX_SPAN_FACTOR * camera.distance
        var loX = Double.POSITIVE_INFINITY
        var loY = Double.POSITIVE_INFINITY
        var hiX = Double.NEGATIVE_INFINITY
        var hiY = Double.NEGATIVE_INFINITY
        for (h in hits) {
            val x = h.x.coerceIn(centre.x - span, centre.x + span)
            val y = h.y.coerceIn(centre.y - span, centre.y + span)
            loX = min(loX, x)
            loY = min(loY, y)
            hiX = max(hiX, x)
            hiY = max(hiY, y)
        }
        return Vec2(loX, loY) to Vec2(hiX, hiY)
    }

    /**
     * Chords chosen from the arc's own geometry at the tessellation tolerance, but never fewer than the
     * 2D renderer's own count — so an arc drawn on a plane a metre from the eye is not coarser in 3D than
     * it is in the 2D canvas, and one drawn under the nose is as smooth as its radius asks for.
     */
    override fun arcPoints(arc: Arc): List<Vec2> {
        val steps =
            max(
                GeomMath.renderArcSteps(arc),
                GeomMath.chordSteps(arc.radius, GeomMath.sweep(arc), GeomMath.TESS_TOL_MM),
            )
        return GeomMath.sampleArc(arc, min(steps, MAX_CURVE_STEPS))
    }

    override fun drawCircle(
        target: DrawTarget,
        c: Circle,
        style: Style,
    ) {
        val steps = min(max(GeomMath.chordSteps(c.radius, 2.0 * kotlin.math.PI, GeomMath.TESS_TOL_MM), 32), MAX_CURVE_STEPS)
        val ring = GeomMath.sampleCircle(c, steps, ccw = true).mapNotNull { toScreen(it) }
        if (ring.size >= 2) target.polyline(ring, style)
    }

    companion object {
        /**
         * How much bigger than the view's nominal one a pick tolerance may grow before [scaleAt] stops
         * following the perspective and says so.
         *
         * 20 is chosen from what an ordinary orbit reaches: a point at the far edge of a framed part sits
         * within a small multiple of the target depth and is foreshortened by a small factor, and the two
         * enter the scale as a product of a ratio and a square root — comfortably inside 20. Past it the
         * cursor is on a part of the plane the view cannot honestly show, and a 10-pixel tolerance would
         * mean metres of plane; better a tolerance that stops growing, with the status line saying why.
         */
        const val MAX_TOLERANCE_FACTOR = 20.0

        /** How many camera distances of plane an infinite line is drawn across — see [viewRect]. */
        const val MAX_SPAN_FACTOR = 20.0

        /** A ceiling on tessellation: a 10 m radius at 0.02 mm would otherwise ask for a million chords. */
        const val MAX_CURVE_STEPS = 720
    }
}
