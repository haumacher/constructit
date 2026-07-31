package constructit.editor

import constructit.geom.Arc
import constructit.geom.Circle
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Plane3
import constructit.geom.Vec2
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

    override fun toScreen(p: Vec2): Vec2? = camera.project(plane.toWorld(p), widthPx, heightPx)

    override fun toPlane(s: Vec2): Vec2? {
        val ray = camera.unproject(s, widthPx, heightPx)
        val t = Geom3.rayPlane(ray, plane) ?: return null
        return plane.toLocal(ray.at(t))
    }

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
