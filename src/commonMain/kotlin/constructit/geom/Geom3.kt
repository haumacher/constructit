package constructit.geom

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3D vector / point in millimetres — the world the 2D→3D seam embeds into (OP-17).
 *
 * Deliberately lean: 3D geometry in this engine is *derived* (a plane frame, a mesh vertex), never
 * drawn or dragged, so it needs arithmetic and nothing else. The analytic layer stays 2D plus a frame,
 * which is what keeps the mesh a sink (OP-9).
 */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)

    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)

    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)

    operator fun unaryMinus() = Vec3(-x, -y, -z)

    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z

    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    fun length() = sqrt(x * x + y * y + z * z)

    fun normalized(): Vec3 {
        val l = length()
        return if (l < EPS) this else Vec3(x / l, y / l, z / l)
    }

    /** The [axis] component — so a measurement can be taken per axis without three near-copies. */
    fun component(axis: Axis3): Double =
        when (axis) {
            Axis3.X -> x
            Axis3.Y -> y
            Axis3.Z -> z
        }

    companion object {
        const val EPS = 1e-9
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val X = Vec3(1.0, 0.0, 0.0)
        val Y = Vec3(0.0, 1.0, 0.0)
        val Z = Vec3(0.0, 0.0, 1.0)
    }
}

/** A world axis — the regular way to ask a solid for one bounding-box number (OP-4). */
enum class Axis3 { X, Y, Z }

/**
 * Which provenance-named face of a feature is meant (OP-8). A *constructed* accessor, not a discovered
 * one: `TOP` is "the sketch plane moved along its normal by the depth", which is a function of the
 * feature's parameters and therefore survives every edit — the topological-naming problem does not
 * arise because nothing is ever re-identified.
 */
enum class SolidFace { TOP, BOTTOM }

/**
 * The **embedding frame** of a sketch (OP-17): a plane through [origin] spanned by the orthonormal
 * pair [u], [v], with normal `u × v`.
 *
 * This is the same concept as a placed group's frame (OP-16) one dimension up, and the reason 2D
 * geometry is *not* made plane-resident: one 2D construction can be embedded on several planes, which
 * is macro-instance semantics applied to the seam.
 */
data class Plane3(val origin: Vec3, val u: Vec3, val v: Vec3) {
    val normal: Vec3 get() = u.cross(v)

    /** Where sketch point [p] lands in the world. */
    fun toWorld(p: Vec2): Vec3 = origin + u * p.x + v * p.y

    /** The same frame, moved [d] mm along its own normal. */
    fun translated(d: Double): Plane3 = Plane3(origin + normal * d, u, v)

    /**
     * The same plane with its normal reversed. The flip has to mirror the in-plane frame as well (`v`
     * is negated), because a right-handed frame whose normal points the other way is a mirrored frame —
     * there is no way to flip the normal and keep the 2D coordinates unchanged.
     */
    fun flipped(): Plane3 = Plane3(origin, u, -v)
}

/**
 * **The seam** (OP-17): 2D result-layer [regions] (OP-14) embedded on a [plane].
 *
 * A separate value rather than a property of the regions, so the 2D engine stays abstract-planar and a
 * region can feed several sketches.
 */
data class Sketch3(val plane: Plane3, val regions: List<Region>)

/** One mesh triangle, as indices into [Mesh3.vertices], wound counter-clockwise seen from outside. */
data class Tri(val a: Int, val b: Int, val c: Int)

/**
 * An indexed triangle mesh — the **sink** (OP-9): render/print/export only, never lifted back to
 * analytic geometry. Vertices are in insertion order and triangles in emission order, so a mesh is a
 * deterministic function of its feature's parameters (nothing here iterates a hash).
 */
data class Mesh3(val vertices: List<Vec3>, val triangles: List<Tri>) {
    val vertexCount: Int get() = vertices.size
    val triangleCount: Int get() = triangles.size
}

/**
 * The analytic description of a solid: which feature made it, from which sketch, with which
 * parameters (OP-9 — the analytic layer is the source of truth).
 *
 * Kept in the value next to the mesh so provenance accessors (OP-8) and later a B-rep/STEP export read
 * the *feature*, not the triangles.
 */
sealed interface Feature3 {
    val sketch: Sketch3

    /** A prism: [sketch] swept [depth] mm along its plane's normal. */
    data class Extrusion(override val sketch: Sketch3, val depth: Double) : Feature3

    /** [sketch] swept [angle] rad about the in-plane axis through [axisOrigin] along [axisDir]. */
    data class Revolution(
        override val sketch: Sketch3,
        val axisOrigin: Vec2,
        val axisDir: Vec2,
        val angle: Double,
    ) : Feature3
}

/**
 * A solid: its analytic [feature] **plus** the [mesh] derived from it.
 *
 * The mesh rides inside the value because it is derived data, not a separate object with a life of its
 * own — and `SolidValue` stays a distinct type from a future mesh-only value, which is the OP-9
 * partition the type system enforces: analytic-preserving features on one side, mesh-only operations
 * (offset/shell/hull, imported meshes) on the other.
 */
data class Solid3(val feature: Feature3, val mesh: Mesh3)

/**
 * The 3D kernel: region tessellation, triangulation with holes, and the two features of this slice.
 *
 * Every function here is a pure function of values and returns `result to reason` rather than throwing,
 * so a feature that cannot be built becomes an **invalid node** with a reason and heals when its
 * parameters move back (OP-3).
 */
object Geom3 {
    /**
     * Distance below which two mesh vertices are the same vertex (mm). Caps and side walls are built
     * from the *same* tessellated points, so coincident vertices are normally bit-identical; this is a
     * safety net that also snaps a revolve profile onto its axis, and it is orders of magnitude below
     * the tessellation tolerance so it can never merge two genuinely distinct points.
     */
    const val WELD_TOL = 1e-7

    /** Areas below this (mm²) count as zero — a degenerate ear, a collinear vertex, a sliver. */
    private const val AREA_EPS = 1e-12

    // ---- vertex welding: an indexed mesh, in deterministic insertion order ----

    /**
     * Accumulates an indexed mesh. Vertices are welded on a lattice of [WELD_TOL] boxes and looked up
     * in the 27-box neighbourhood, so a coordinate landing just across a box boundary still finds its
     * twin; the scan order is fixed, so the result is deterministic. Indices are handed out in
     * insertion order — never in hash order — which is what makes a mesh byte-comparable.
     */
    private class MeshBuilder {
        private val vertices = ArrayList<Vec3>()
        private val buckets = HashMap<Long, MutableList<Int>>()
        private val tris = ArrayList<Tri>()

        private fun cell(v: Double): Long = round(v / WELD_TOL).toLong()

        private fun hash(
            i: Long,
            j: Long,
            k: Long,
        ): Long = i * 73856093L xor j * 19349663L xor k * 83492791L

        fun vertex(p: Vec3): Int {
            val ci = cell(p.x)
            val cj = cell(p.y)
            val ck = cell(p.z)
            for (di in -1L..1L) {
                for (dj in -1L..1L) {
                    for (dk in -1L..1L) {
                        val list = buckets[hash(ci + di, cj + dj, ck + dk)] ?: continue
                        for (idx in list) {
                            if ((vertices[idx] - p).length() <= WELD_TOL) return idx
                        }
                    }
                }
            }
            val idx = vertices.size
            vertices.add(p)
            buckets.getOrPut(hash(ci, cj, ck)) { ArrayList() }.add(idx)
            return idx
        }

        /**
         * Emit a triangle, **dropping** it when two of its corners weld together. That is not sloppiness
         * but the axis case: a revolve profile touching its axis makes every quad of that strip collapse
         * to a point, and the closed shell is exactly the one with those triangles left out.
         */
        fun triangle(
            a: Int,
            b: Int,
            c: Int,
        ) {
            if (a == b || b == c || a == c) return
            tris.add(Tri(a, b, c))
        }

        fun triangle(
            a: Vec3,
            b: Vec3,
            c: Vec3,
        ) = triangle(vertex(a), vertex(b), vertex(c))

        fun build(): Mesh3 = Mesh3(vertices.toList(), tris.toList())
    }

    // ---- tessellation: a region as polygons, in the sketch's own 2D coordinates ----

    /**
     * A [Region] as polygons: [outer] counter-clockwise, each hole clockwise (OP-14's convention,
     * preserved). Each list holds the distinct corners of a closed polygon — the closing point is *not*
     * repeated.
     */
    data class TessRegion(val outer: List<Vec2>, val holes: List<List<Vec2>>)

    /**
     * The area a tessellated region actually encloses — less than the region's exact area by each arc's
     * sagitta. The honest number to compare a mesh volume against, so the tests can state both: exact
     * agreement with the polygons the mesh is made of, and agreement with the exact area to within the
     * tessellation tolerance.
     */
    fun tessArea(t: TessRegion): Double = polygonArea(t.outer) + t.holes.sumOf { polygonArea(it) }

    /** A loop as its polyline of distinct corners, keeping the loop's own traversal direction. */
    fun tessellateLoop(
        loop: Loop,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): List<Vec2> {
        val pts = ArrayList<Vec2>()
        for (e in loop.elements) {
            val piece = GeomMath.tessellatePiece(e, tolMm)
            for (p in piece) {
                if (pts.isEmpty() || (p - pts.last()).length() > WELD_TOL) pts.add(p)
            }
        }
        while (pts.size > 1 && (pts.first() - pts.last()).length() <= WELD_TOL) pts.removeAt(pts.size - 1)
        return pts
    }

    fun tessellateRegion(
        region: Region,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Pair<TessRegion?, String?> {
        val outer = tessellateLoop(region.outer, tolMm)
        if (outer.size < 3) return null to "the outer boundary tessellates to fewer than three corners"
        val holes = ArrayList<List<Vec2>>(region.holes.size)
        for (h in region.holes) {
            val poly = tessellateLoop(h, tolMm)
            if (poly.size < 3) return null to "a hole tessellates to fewer than three corners"
            holes.add(poly)
        }
        // OP-14 normalises the loops, so this only re-states the convention in polygon terms.
        val o = if (polygonArea(outer) >= 0.0) outer else outer.reversed()
        val hs = holes.map { if (polygonArea(it) <= 0.0) it else it.reversed() }
        return TessRegion(o, hs) to null
    }

    fun polygonArea(poly: List<Vec2>): Double {
        var s = 0.0
        for (i in poly.indices) s += poly[i].cross(poly[(i + 1) % poly.size])
        return s / 2.0
    }

    // ---- triangulation with holes: the hard kernel ----

    /**
     * Triangulate a tessellated region into counter-clockwise triangles.
     *
     * Two deterministic steps, because a cap that re-triangulated differently after a parameter edit
     * would make the mesh stop being a pure function of the parameters (OP-15's rule: determinism is
     * the load-bearing property):
     *
     * 1. **Hole bridging.** Each hole is spliced into the outer polygon along a bridge traversed twice,
     *    turning "outer plus holes" into one weakly-simple polygon. Holes are taken in order of their
     *    rightmost corner (ties broken upward, then by input order), and the bridge partner is the
     *    *nearest* outer-polygon corner (ties broken by index) that the bridge can see: the bridge must
     *    cross no boundary edge and its midpoint must lie inside the material.
     * 2. **Ear clipping.** Scanned from index 0 every time, so the same polygon always yields the same
     *    triangles. Collinear corners are dropped rather than emitted, and a pass that finds no valid
     *    ear clips the most convex corner instead — progress is guaranteed, so a near-degenerate sliver
     *    cannot hang the mesh.
     */
    fun triangulate(t: TessRegion): Pair<List<Tri3>?, String?> {
        val (merged, why) = bridgeHoles(t.outer, t.holes)
        if (merged == null) return null to (why ?: "cannot triangulate")
        return earClip(merged)
    }

    /** A triangle of the cap, in sketch coordinates. */
    data class Tri3(val a: Vec2, val b: Vec2, val c: Vec2)

    private fun anchorIndex(poly: List<Vec2>): Int {
        var best = 0
        for (i in poly.indices) {
            val p = poly[i]
            val q = poly[best]
            if (p.x > q.x || (p.x == q.x && p.y > q.y)) best = i
        }
        return best
    }

    private fun bridgeHoles(
        outer: List<Vec2>,
        holes: List<List<Vec2>>,
    ): Pair<List<Vec2>?, String?> {
        if (holes.isEmpty()) return outer to null
        val anchors = holes.map { anchorIndex(it) }
        val order =
            holes.indices.sortedWith(
                compareByDescending<Int> { holes[it][anchors[it]].x }
                    .thenByDescending { holes[it][anchors[it]].y }
                    .thenBy { it },
            )
        var merged = outer
        for ((done, hi) in order.withIndex()) {
            val hole = holes[hi]
            val m = anchors[hi]
            val M = hole[m]
            val pending = order.drop(done).map { holes[it] }
            val candidates = merged.indices.sortedWith(compareBy({ (merged[it] - M).length() }, { it }))
            var spliced = false
            for (j in candidates) {
                if (!bridgeIsClear(M, merged[j], merged, pending)) continue
                merged = splice(merged, j, hole, m)
                spliced = true
                break
            }
            if (!spliced) return null to "no bridge from hole ${hi + 1} to the outer boundary is visible"
        }
        return merged to null
    }

    /** Insert [hole] (starting at its corner [m]) into [outer] along the bridge to `outer[j]`. */
    private fun splice(
        outer: List<Vec2>,
        j: Int,
        hole: List<Vec2>,
        m: Int,
    ): List<Vec2> {
        val out = ArrayList<Vec2>(outer.size + hole.size + 2)
        for (i in 0..j) out.add(outer[i])
        for (k in hole.indices) out.add(hole[(m + k) % hole.size])
        out.add(hole[m])
        for (i in j until outer.size) out.add(outer[i])
        return out
    }

    /**
     * Can the bridge [a]–[b] be drawn: it crosses no edge of [poly] or of any [pending] hole, and its
     * midpoint is material (inside [poly], outside every hole).
     */
    private fun bridgeIsClear(
        a: Vec2,
        b: Vec2,
        poly: List<Vec2>,
        pending: List<List<Vec2>>,
    ): Boolean {
        if ((b - a).length() <= WELD_TOL) return false
        if (crossesAnyEdge(a, b, poly)) return false
        for (h in pending) if (crossesAnyEdge(a, b, h)) return false
        val mid = (a + b) * 0.5
        if (!insidePolygon(mid, poly)) return false
        for (h in pending) if (insidePolygon(mid, h)) return false
        return true
    }

    private fun crossesAnyEdge(
        a: Vec2,
        b: Vec2,
        poly: List<Vec2>,
    ): Boolean {
        for (i in poly.indices) {
            if (properlyIntersect(a, b, poly[i], poly[(i + 1) % poly.size])) return true
        }
        return false
    }

    private fun same(
        p: Vec2,
        q: Vec2,
    ): Boolean = (p - q).length() <= WELD_TOL

    /**
     * Do the segments a–b and c–d meet anywhere other than at a shared endpoint? Sharing an endpoint is
     * allowed on purpose: a bridge starts and ends *on* the boundary it is tested against. Anything
     * else — a proper crossing, a touch in the middle, a collinear overlap — blocks the bridge, which
     * only ever costs the next candidate a try.
     */
    private fun properlyIntersect(
        a: Vec2,
        b: Vec2,
        c: Vec2,
        d: Vec2,
    ): Boolean {
        if (same(a, c) || same(a, d) || same(b, c) || same(b, d)) return false
        val d1 = sign((b - a).cross(c - a))
        val d2 = sign((b - a).cross(d - a))
        val d3 = sign((d - c).cross(a - c))
        val d4 = sign((d - c).cross(b - c))
        if (d1 * d2 < 0 && d3 * d4 < 0) return true
        if (d1 == 0 && onSegment(a, b, c)) return true
        if (d2 == 0 && onSegment(a, b, d)) return true
        if (d3 == 0 && onSegment(c, d, a)) return true
        if (d4 == 0 && onSegment(c, d, b)) return true
        return false
    }

    private fun sign(v: Double): Int =
        if (v > AREA_EPS) {
            1
        } else if (v < -AREA_EPS) {
            -1
        } else {
            0
        }

    private fun onSegment(
        a: Vec2,
        b: Vec2,
        p: Vec2,
    ): Boolean {
        val len = (b - a).length()
        if (len <= WELD_TOL) return false
        val t = (p - a).dot(b - a) / (len * len)
        return t > 0.0 && t < 1.0
    }

    /** Even-odd ray crossing test. Points on the boundary are not the question here (midpoints are). */
    private fun insidePolygon(
        p: Vec2,
        poly: List<Vec2>,
    ): Boolean {
        var inside = false
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            if ((a.y > p.y) != (b.y > p.y)) {
                val x = a.x + (p.y - a.y) / (b.y - a.y) * (b.x - a.x)
                if (x > p.x) inside = !inside
            }
        }
        return inside
    }

    private fun earClip(poly: List<Vec2>): Pair<List<Tri3>?, String?> {
        val ring = ArrayList<Int>(poly.size)
        for (i in poly.indices) ring.add(i)
        val tris = ArrayList<Tri3>(max(1, poly.size - 2))
        while (ring.size > 3) {
            var clipped = false
            var bestK = -1
            var bestCross = AREA_EPS
            for (k in ring.indices) {
                val ip = ring[(k - 1 + ring.size) % ring.size]
                val ic = ring[k]
                val inx = ring[(k + 1) % ring.size]
                val a = poly[ip]
                val b = poly[ic]
                val c = poly[inx]
                val cr = (b - a).cross(c - b)
                if (abs(cr) <= AREA_EPS) {
                    // A straight (or doubled-back) corner carries no area: drop it, emit nothing.
                    ring.removeAt(k)
                    clipped = true
                    break
                }
                if (cr < 0.0) continue // reflex
                if (cr > bestCross) {
                    bestCross = cr
                    bestK = k
                }
                if (containsAnotherCorner(poly, ring, a, b, c)) continue
                tris.add(Tri3(a, b, c))
                ring.removeAt(k)
                clipped = true
                break
            }
            if (!clipped) {
                if (bestK < 0) return null to "the boundary cannot be triangulated (is it self-intersecting?)"
                // No corner is a clean ear — clip the most convex one anyway, so a near-degenerate
                // sliver costs a little accuracy rather than an endless loop.
                val ip = ring[(bestK - 1 + ring.size) % ring.size]
                val ic = ring[bestK]
                val inx = ring[(bestK + 1) % ring.size]
                tris.add(Tri3(poly[ip], poly[ic], poly[inx]))
                ring.removeAt(bestK)
            }
        }
        if (ring.size == 3) {
            val a = poly[ring[0]]
            val b = poly[ring[1]]
            val c = poly[ring[2]]
            if (abs((b - a).cross(c - b)) > AREA_EPS) tris.add(Tri3(a, b, c))
        }
        if (tris.isEmpty()) return null to "the boundary encloses no area"
        return tris to null
    }

    /**
     * Does any other corner of the ring fall strictly inside the candidate ear? Corners *coincident*
     * with the ear's own corners are skipped, which is what makes the doubled bridge vertices harmless.
     */
    private fun containsAnotherCorner(
        poly: List<Vec2>,
        ring: List<Int>,
        a: Vec2,
        b: Vec2,
        c: Vec2,
    ): Boolean {
        for (i in ring) {
            val p = poly[i]
            if (same(p, a) || same(p, b) || same(p, c)) continue
            val s1 = (b - a).cross(p - a)
            val s2 = (c - b).cross(p - b)
            val s3 = (a - c).cross(p - c)
            if (s1 > AREA_EPS && s2 > AREA_EPS && s3 > AREA_EPS) return true
        }
        return false
    }

    // ---- features ----

    /**
     * A prism: [sketch] swept [depth] mm along its plane's normal (OP-17 slice 1).
     *
     * Caps are the triangulated region — bottom wound the other way so its normal points out of the
     * solid — and the side walls are a quad strip along every boundary piece, in the loop's own
     * direction. Because both read the *same* tessellated points, every wall edge meets exactly one cap
     * edge, which is where watertightness comes from rather than from a repair pass (OP-2).
     */
    fun extrude(
        sketch: Sketch3,
        depth: Double,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Pair<Solid3?, String?> {
        if (sketch.regions.isEmpty()) return null to "a sketch with no region cannot be extruded"
        if (depth <= WELD_TOL) return null to "extrude depth must be positive"
        val mb = MeshBuilder()
        val plane = sketch.plane
        val n = plane.normal
        for (region in sketch.regions) {
            val (tess, why) = tessellateRegion(region, tolMm)
            if (tess == null) return null to (why ?: "cannot tessellate the sketch")
            val (tris, reason) = triangulate(tess)
            if (tris == null) return null to (reason ?: "cannot triangulate the sketch")

            fun bottom(p: Vec2) = plane.toWorld(p)

            fun top(p: Vec2) = plane.toWorld(p) + n * depth
            for (t in tris) {
                mb.triangle(top(t.a), top(t.b), top(t.c))
                mb.triangle(bottom(t.a), bottom(t.c), bottom(t.b))
            }
            for (poly in listOf(tess.outer) + tess.holes) {
                for (i in poly.indices) {
                    val p = poly[i]
                    val q = poly[(i + 1) % poly.size]
                    mb.triangle(bottom(p), bottom(q), top(q))
                    mb.triangle(bottom(p), top(q), top(p))
                }
            }
        }
        return Solid3(Feature3.Extrusion(sketch, depth), mb.build()) to null
    }

    /**
     * A solid of revolution: [sketch] swept [angle] rad about the axis through [axisOrigin] along
     * [axisDir], **in the sketch plane** (OP-17 slice 2).
     *
     * The profile may *touch* the axis — that is the normal case for a turned part, and the collapsed
     * quads are simply dropped — but a profile **crossing** the axis is refused with a reason and heals
     * when it is dragged back (OP-3): revolving through the axis would fold the shell through itself,
     * and a solid that is quietly self-intersecting is worse than one that is visibly absent.
     *
     * A full turn closes the shell and gets no caps; a partial turn is capped at both ends by the
     * triangulated region, which is also what puts a profile with holes (a revolved slot) on the same
     * footing as a solid one.
     */
    fun revolve(
        sketch: Sketch3,
        axisOrigin: Vec2,
        axisDir: Vec2,
        angle: Double,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Pair<Solid3?, String?> {
        if (sketch.regions.isEmpty()) return null to "a sketch with no region cannot be revolved"
        if (axisDir.length() < Vec2.EPS) return null to "the axis of revolution has no direction"
        val twoPi = 2.0 * PI
        if (angle <= WELD_TOL) return null to "revolve angle must be positive"
        if (angle > twoPi + 1e-9) return null to "revolve angle must not exceed a full turn"
        val full = abs(angle - twoPi) <= 1e-9

        val tessellated = ArrayList<TessRegion>(sketch.regions.size)
        for (region in sketch.regions) {
            val (tess, why) = tessellateRegion(region, tolMm)
            if (tess == null) return null to (why ?: "cannot tessellate the sketch")
            tessellated.add(tess)
        }

        // Axis frame in sketch coordinates: s along the axis, r away from it. Both signs of the axis
        // direction describe the same axis, so the one with the profile on its positive side is chosen —
        // a rotation by pi, hence orientation-preserving, which is what keeps the winding rules below
        // independent of how the axis happened to be drawn.
        var axis = axisDir.normalized()
        val allPoints = tessellated.flatMap { listOf(it.outer) + it.holes }.flatten()
        val radii = allPoints.map { (it - axisOrigin).dot(axis.perp()) }
        val hasPos = radii.any { it > WELD_TOL }
        val hasNeg = radii.any { it < -WELD_TOL }
        if (hasPos && hasNeg) return null to "the profile crosses the axis of revolution"
        if (hasNeg) axis = -axis
        val perp = axis.perp()
        if (!hasPos && !hasNeg) return null to "the profile lies on the axis of revolution"

        // The same frame in the world: A along the axis, P the in-plane radial direction at angle 0,
        // N = A x P the plane's own normal, so increasing the angle turns P towards N.
        val plane = sketch.plane
        val axisWorld = (plane.u * axis.x + plane.v * axis.y).normalized()
        val radialWorld = (plane.u * perp.x + plane.v * perp.y).normalized()
        val normalWorld = axisWorld.cross(radialWorld)
        val originWorld = plane.toWorld(axisOrigin)

        fun sr(p: Vec2): Pair<Double, Double> {
            val d = p - axisOrigin
            val r = d.dot(perp)
            return d.dot(axis) to if (abs(r) <= WELD_TOL) 0.0 else r
        }

        val maxR = allPoints.maxOf { abs(sr(it).second) }
        val steps = max(3, GeomMath.chordSteps(maxR, angle, tolMm))

        fun at(
            p: Vec2,
            step: Int,
        ): Vec3 {
            val (s, r) = sr(p)
            val th = angle * step / steps
            return originWorld + axisWorld * s + radialWorld * (r * cos(th)) + normalWorld * (r * sin(th))
        }

        val mb = MeshBuilder()
        for (tess in tessellated) {
            for (poly in listOf(tess.outer) + tess.holes) {
                for (i in poly.indices) {
                    val p = poly[i]
                    val q = poly[(i + 1) % poly.size]
                    for (j in 0 until steps) {
                        mb.triangle(at(p, j), at(q, j), at(q, j + 1))
                        mb.triangle(at(p, j), at(q, j + 1), at(p, j + 1))
                    }
                }
            }
            if (!full) {
                val (tris, reason) = triangulate(tess)
                if (tris == null) return null to (reason ?: "cannot triangulate the revolve profile")
                for (t in tris) {
                    // The start cap faces backwards out of the sweep, the end cap forwards — the same
                    // reversed-bottom / upright-top rule the extrude uses.
                    mb.triangle(at(t.a, 0), at(t.c, 0), at(t.b, 0))
                    mb.triangle(at(t.a, steps), at(t.b, steps), at(t.c, steps))
                }
            }
        }
        return Solid3(Feature3.Revolution(sketch, axisOrigin, axis, angle), mb.build()) to null
    }

    // ---- provenance accessors (OP-8) ----

    /**
     * The plane of a feature's named [which] face — a *constructed* accessor, so it moves with the
     * parameters and is what makes "sketch on this face" possible without discovered topology.
     *
     * `TOP` keeps the sketch plane's own `u`/`v`, so a sketch placed on it uses the same coordinates as
     * the sketch below — the point of the accessor. `BOTTOM` is the sketch plane flipped, so its normal
     * points out of the solid.
     */
    fun facePlane(
        feature: Feature3,
        which: SolidFace,
    ): Pair<Plane3?, String?> =
        when (feature) {
            is Feature3.Extrusion ->
                when (which) {
                    SolidFace.TOP -> feature.sketch.plane.translated(feature.depth) to null
                    SolidFace.BOTTOM -> feature.sketch.plane.flipped() to null
                }
            // Deliberately refused rather than guessed: a revolve's end caps are planes too, but they
            // are *rotated* frames, and naming them TOP/BOTTOM would invent a convention this slice has
            // no use for. The cut is recorded in DESIGN.md under OP-17.
            is Feature3.Revolution -> null to "a revolved solid has no top or bottom face"
        }

    // ---- measurements (OP-4): scalars, so they may drive new 2D constructions ----

    /**
     * Volume enclosed by [mesh], from the divergence theorem: `V = Σ a·(b×c) / 6` over the triangles.
     * Exact for the mesh (a finite sum of rationals in the vertex coordinates), hence deterministic —
     * approximate only with respect to the *curved* solid, by the tessellation tolerance.
     */
    fun volume(mesh: Mesh3): Double {
        var sum = 0.0
        for (t in mesh.triangles) {
            val a = mesh.vertices[t.a]
            val b = mesh.vertices[t.b]
            val c = mesh.vertices[t.c]
            sum += a.dot(b.cross(c))
        }
        return sum / 6.0
    }

    /** Axis-aligned bounds of [mesh], or null when it has no vertices. */
    fun bounds(mesh: Mesh3): Pair<Vec3, Vec3>? {
        if (mesh.vertices.isEmpty()) return null
        var lo = mesh.vertices[0]
        var hi = mesh.vertices[0]
        for (p in mesh.vertices) {
            lo = Vec3(min(lo.x, p.x), min(lo.y, p.y), min(lo.z, p.z))
            hi = Vec3(max(hi.x, p.x), max(hi.y, p.y), max(hi.z, p.z))
        }
        return lo to hi
    }
}
