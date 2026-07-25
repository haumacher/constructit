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
 * One layer of a **prismatic** solid (OP-22): the area [regions] occupies between heights [z0] and [z1],
 * measured along the prism's own axis from its plane's origin.
 *
 * The regions are **polygonal** — a slab only ever comes out of a boolean, and a boolean tessellates its
 * operands first (OP-22, the 2D analog of OP-15's approximated-curve rule).
 */
data class Slab(val regions: List<Region>, val z0: Double, val z1: Double) {
    val height: Double get() = z1 - z0
}

/**
 * The analytic description of a solid: which feature made it, from which sketch, with which
 * parameters (OP-9 — the analytic layer is the source of truth).
 *
 * Kept in the value next to the mesh so provenance accessors (OP-8) and later a B-rep/STEP export read
 * the *feature*, not the triangles.
 */
sealed interface Feature3 {
    /**
     * The 2D areas this feature's *plan* shows: what the canvas draws as a footprint hint and picks a
     * solid by (OP-17). An accessor rather than a field, because what "the plan" is differs per feature —
     * the sketch for a swept one, the stack of slabs for a prismatic one — and every caller wants the
     * same answer to the same question.
     */
    val footprint: List<Region>

    /** A prism: [sketch] swept [depth] mm along its plane's normal. */
    data class Extrusion(val sketch: Sketch3, val depth: Double) : Feature3 {
        override val footprint: List<Region> get() = sketch.regions
    }

    /** [sketch] swept [angle] rad about the in-plane axis through [axisOrigin] along [axisDir]. */
    data class Revolution(
        val sketch: Sketch3,
        val axisOrigin: Vec2,
        val axisDir: Vec2,
        val angle: Double,
    ) : Feature3 {
        override val footprint: List<Region> get() = sketch.regions
    }

    /**
     * A **prismatic solid** (OP-22): a stack of [slabs] along [plane]'s normal, each a polygonal area
     * over its own height range, the ranges disjoint and ascending.
     *
     * This is the form same-axis booleans are **closed** under, which is the whole reason it exists: an
     * extrusion is the one-slab case, and the result of subtracting/uniting/intersecting two prisms is
     * another prism, so results compose without ever leaving the exact algebra. A plain extrude keeps its
     * analytic [Extrusion] form instead (its arcs are still exact circles); the conversion happens only
     * when a boolean needs it — see [Geom3.prismatic].
     *
     * Which solids were combined is *not* recorded here: that is the op node's input list, and in this
     * model identity is the node (OP-8). The feature carries geometry, not history.
     */
    data class Prism(val plane: Plane3, val slabs: List<Slab>) : Feature3 {
        override val footprint: List<Region> get() = slabs.flatMap { it.regions }

        val minZ: Double get() = slabs.minOf { it.z0 }
        val maxZ: Double get() = slabs.maxOf { it.z1 }
    }
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
     * Does any other corner of the ring fall inside the candidate ear — **its boundary included**?
     * Corners *coincident* with the ear's own corners are skipped, which is what makes the doubled bridge
     * vertices harmless.
     *
     * The boundary counts, and that is not fastidiousness. A corner sitting exactly *on* the ear's
     * diagonal makes the diagonal a T-junction: the neighbouring triangle stops at that corner while this
     * one runs past it, so the cap has a crack in it — and the polygon left after the clip touches itself
     * there, which the clipper then triangulates into overlapping garbage. It went unnoticed until
     * booleans started producing such polygons routinely (a plus-shaped union has one), and it is a defect
     * of the triangulator rather than of them: `extrude` had it too.
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
            if (s1 >= -AREA_EPS && s2 >= -AREA_EPS && s3 >= -AREA_EPS) return true
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

    // ---- exact prismatic booleans (OP-22) ----
    // A boolean between two solids extruded along the SAME axis decomposes into z-breakpoints times 2D
    // region booleans, and is therefore *exact* — no BSP split, no coplanar-face heuristic, no repair
    // pass. Anything else is refused and waits for Manifold (OP-9).

    /** Heights closer together than this (mm) are one level of the stack. */
    const val Z_EPS = 1e-7

    private const val NOT_PRISMATIC =
        "this solid is not a prism, so it has no same-axis boolean; general booleans arrive with Manifold (OP-9)"

    /**
     * The **prismatic reading** of a feature (OP-22), or null with a reason.
     *
     * An extrusion becomes one slab — its curved boundary pieces tessellated, which is where the
     * exactness of the *analytic* circle is traded for the exactness of the *algebra* (OP-15's
     * approximated-curve rule, one dimension down). A prism is already one. A revolve is refused rather
     * than approximated: its faces are not vertical, so nothing here can describe it, and guessing would
     * be exactly the leaky general CSG this whole design avoids.
     */
    fun prismatic(
        feature: Feature3,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Pair<Feature3.Prism?, String?> =
        when (feature) {
            is Feature3.Prism -> feature to null
            is Feature3.Revolution -> null to NOT_PRISMATIC
            is Feature3.Extrusion -> oneSlab(feature, tolMm)
        }

    private fun oneSlab(
        feature: Feature3.Extrusion,
        tolMm: Double,
    ): Pair<Feature3.Prism?, String?> {
        if (feature.depth <= WELD_TOL) return null to "extrude depth must be positive"
        val (rings, why) = RegionBool.ringsOf(feature.sketch.regions, tolMm)
        if (rings == null) return null to why
        val (regions, why2) = RegionBool.regionsOf(RegionBool.canonical(rings))
        if (regions == null) return null to why2
        return Feature3.Prism(feature.sketch.plane, listOf(Slab(regions, 0.0, feature.depth))) to null
    }

    /**
     * [kind] applied to two solids (OP-22). Both must be prismatic **along a common axis**; the result is
     * a prism, so booleans compose.
     *
     * The algebra: take every slab boundary of either operand as a z-breakpoint, and on each resulting
     * z-interval apply the 2D kernel ([RegionBool]) to the two operands' areas there. Adjacent output
     * slabs whose areas come out identical are merged back, so a union of two storeys with the same
     * footprint is one shaft with no seam in it rather than two boxes touching.
     *
     * An **empty** result is refused with a reason rather than returned as a solid with no material: it
     * is an ordinary invalid node, hidden and healing when a parameter moves back (OP-3).
     */
    fun boolean(
        kind: BoolOp,
        a: Solid3,
        b: Solid3,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Pair<Solid3?, String?> {
        val (pa, whyA) = prismatic(a.feature, tolMm)
        if (pa == null) return null to (whyA ?: NOT_PRISMATIC)
        val (pb, whyB) = prismatic(b.feature, tolMm)
        if (pb == null) return null to (whyB ?: NOT_PRISMATIC)
        val (slabsB, whyM) = onAxisOf(pb, pa.plane)
        if (slabsB == null) return null to (whyM ?: NOT_PRISMATIC)
        val slabsA = pa.slabs

        val levels = levelsOf(slabsA + slabsB)
        val out = ArrayList<Slab>()
        for (i in 0 until levels.size - 1) {
            val z0 = levels[i]
            val z1 = levels[i + 1]
            if (z1 - z0 <= Z_EPS) continue
            val (rings, why) = RegionBool.combine(ringsBetween(slabsA, z0, z1), ringsBetween(slabsB, z0, z1), kind)
            if (rings == null) return null to (why ?: "the boolean failed on the slice $z0..$z1 mm")
            if (rings.isEmpty()) continue
            val (regions, why2) = RegionBool.regionsOf(rings)
            if (regions == null) return null to (why2 ?: "the boolean produced an area that does not nest")
            out.add(Slab(regions, z0, z1))
        }
        val merged = mergeSlabs(out)
        if (merged.isEmpty()) return null to "the boolean leaves nothing of the solid"
        val prism = Feature3.Prism(pa.plane, merged)
        val (mesh, whyMesh) = prismMesh(prism, tolMm)
        if (mesh == null) return null to (whyMesh ?: "cannot build the boolean's mesh")
        return Solid3(prism, mesh) to null
    }

    /**
     * [prism]'s slabs re-expressed in the frame [ref] — the step that makes "same axis" concrete.
     *
     * The two prisms must have **parallel** normals (either direction); everything else about the frames
     * may differ, because the map from one in-plane frame to the other is then a rigid motion of the 2D
     * coordinates and therefore preserves the polygons exactly. An anti-parallel normal reverses both the
     * height direction and the in-plane orientation, so the heights are swapped and every ring is
     * reversed — which is why this is worth doing properly rather than demanding identical frames: a
     * solid extruded from a *flipped* face plane is a perfectly ordinary operand.
     */
    private fun onAxisOf(
        prism: Feature3.Prism,
        ref: Plane3,
    ): Pair<List<Slab>?, String?> {
        val n = ref.normal.normalized()
        val nb = prism.plane.normal.normalized()
        val dot = n.dot(nb)
        if (abs(abs(dot) - 1.0) > 1e-9) {
            return null to "the two solids are not extruded along a common axis; general booleans arrive with Manifold (OP-9)"
        }
        val flip = dot < 0.0
        val base = (prism.plane.origin - ref.origin).dot(n)
        val slabs = ArrayList<Slab>(prism.slabs.size)
        for (s in prism.slabs) {
            val (rings, why) = RegionBool.ringsOf(s.regions)
            if (rings == null) return null to why
            val mapped =
                rings.map { ring ->
                    val moved =
                        ring.map { p ->
                            val w = prism.plane.toWorld(p)
                            Vec2((w - ref.origin).dot(ref.u), (w - ref.origin).dot(ref.v))
                        }
                    if (flip) moved.reversed() else moved
                }
            val (regions, why2) = RegionBool.regionsOf(RegionBool.canonical(mapped))
            if (regions == null) return null to why2
            val za = base + if (flip) -s.z0 else s.z0
            val zb = base + if (flip) -s.z1 else s.z1
            slabs.add(Slab(regions, min(za, zb), max(za, zb)))
        }
        return slabs.sortedBy { it.z0 } to null
    }

    /** Every slab boundary of [slabs], ascending, with heights within [Z_EPS] welded into one level. */
    private fun levelsOf(slabs: List<Slab>): List<Double> {
        val all = (slabs.map { it.z0 } + slabs.map { it.z1 }).sorted()
        val out = ArrayList<Double>(all.size)
        for (z in all) if (out.isEmpty() || z - out.last() > Z_EPS) out.add(z)
        return out
    }

    /**
     * The rings of the one slab of [slabs] spanning the whole interval [z0]..[z1], or none.
     *
     * Because the interval comes from the *combined* breakpoints, a slab either covers it entirely or
     * misses it — which is what makes the boolean a plain 2D operation per slice with no clipping.
     */
    private fun ringsBetween(
        slabs: List<Slab>,
        z0: Double,
        z1: Double,
    ): List<List<Vec2>> {
        val slab = slabs.firstOrNull { it.z0 <= z0 + Z_EPS && it.z1 >= z1 - Z_EPS } ?: return emptyList()
        return RegionBool.ringsOf(slab.regions).first ?: emptyList()
    }

    /** Adjacent slabs with identical areas merged into one — the interface between them is not a face. */
    private fun mergeSlabs(slabs: List<Slab>): List<Slab> {
        val out = ArrayList<Slab>(slabs.size)
        for (s in slabs) {
            val last = out.lastOrNull()
            if (last != null && abs(last.z1 - s.z0) <= Z_EPS && sameArea(last.regions, s.regions)) {
                out[out.size - 1] = Slab(last.regions, last.z0, s.z1)
            } else {
                out.add(s)
            }
        }
        return out
    }

    /**
     * Whether two areas are the same shape. A structural comparison of the canonical rings
     * ([RegionBool.canonical]) rather than a shape match: the kernel is deterministic, so two slices that
     * *are* the same area come out of it corner for corner.
     */
    private fun sameArea(
        a: List<Region>,
        b: List<Region>,
    ): Boolean {
        val ra = RegionBool.canonical(RegionBool.ringsOf(a).first ?: return false)
        val rb = RegionBool.canonical(RegionBool.ringsOf(b).first ?: return false)
        if (ra.size != rb.size) return false
        for (i in ra.indices) {
            if (ra[i].size != rb[i].size) return false
            for (j in ra[i].indices) if ((ra[i][j] - rb[i][j]).length() > RegionBool.EPS) return false
        }
        return true
    }

    /**
     * The mesh of a prismatic solid (OP-22): side walls per slab, horizontal caps at every level.
     *
     * The two halves and why they close:
     * - **Caps.** At each level the up-facing faces are `areaBelow − areaAbove` and the down-facing ones
     *   `areaAbove − areaBelow`, both by the 2D kernel. The counterbore's annular shoulder is not a case
     *   here — it is what that subtraction *is*. Where the areas agree the difference is empty and there
     *   is no face at all, which is how two storeys with one footprint become a single shaft.
     * - **Walls.** A quad strip along every slab boundary, in the loop's own direction.
     *
     * Watertightness needs one thing beyond that, and it is the only subtlety in this file: a horizontal
     * boundary may **cross** a vertical one (a boss overhanging the plate it sits on), which puts a cap
     * corner in the middle of a wall edge — a T-junction, i.e. a hole in the shell that no triangle
     * count would reveal. So every polygon is made to *conform* to one global corner set: wall edges are
     * split at it ([conform]), and cap triangles are subdivided at it ([splitToRequired]) — the latter
     * after triangulation, because the triangulator legitimately drops collinear corners and would
     * otherwise undo the very split that is needed.
     */
    fun prismMesh(
        prism: Feature3.Prism,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Pair<Mesh3?, String?> {
        val slabs = prism.slabs
        if (slabs.isEmpty()) return null to "a prism needs at least one slab"
        for (s in slabs) if (s.height <= WELD_TOL) return null to "a slab of the prism has no height"
        for (i in 0 until slabs.size - 1) {
            if (slabs[i].z1 > slabs[i + 1].z0 + Z_EPS) return null to "the prism's slabs overlap"
        }
        val slabRings = ArrayList<List<List<Vec2>>>(slabs.size)
        for (s in slabs) {
            val (rings, why) = RegionBool.ringsOf(s.regions, tolMm)
            if (rings == null) return null to why
            slabRings.add(rings)
        }

        val caps = ArrayList<Triple<Double, List<List<Vec2>>, Boolean>>()
        for (z in levelsOf(slabs)) {
            val below = slabs.indexOfFirst { abs(it.z1 - z) <= Z_EPS }.let { if (it < 0) emptyList() else slabRings[it] }
            val above = slabs.indexOfFirst { abs(it.z0 - z) <= Z_EPS }.let { if (it < 0) emptyList() else slabRings[it] }
            val (up, whyUp) = RegionBool.combine(below, above, BoolOp.SUBTRACT)
            if (up == null) return null to whyUp
            val (down, whyDown) = RegionBool.combine(above, below, BoolOp.SUBTRACT)
            if (down == null) return null to whyDown
            if (up.isNotEmpty()) caps.add(Triple(z, up, true))
            if (down.isNotEmpty()) caps.add(Triple(z, down, false))
        }

        // the one global corner set every polygon is made to agree with
        val required = (slabRings.flatten() + caps.flatMap { it.second }).flatten().distinct()

        val mb = MeshBuilder()
        val n = prism.plane.normal.normalized()

        fun world(
            p: Vec2,
            z: Double,
        ): Vec3 = prism.plane.toWorld(p) + n * z
        for ((si, rings) in slabRings.withIndex()) {
            val z0 = slabs[si].z0
            val z1 = slabs[si].z1
            for (ring in rings) {
                val poly = conform(ring, required)
                for (i in poly.indices) {
                    val p = poly[i]
                    val q = poly[(i + 1) % poly.size]
                    mb.triangle(world(p, z0), world(q, z0), world(q, z1))
                    mb.triangle(world(p, z0), world(q, z1), world(p, z1))
                }
            }
        }
        for ((z, rings, up) in caps) {
            val (regions, whyR) = RegionBool.regionsOf(rings)
            if (regions == null) return null to whyR
            for (r in regions) {
                val (tess, why) = tessellateRegion(r, tolMm)
                if (tess == null) return null to why
                val (tris, why2) = triangulate(tess)
                if (tris == null) return null to why2
                val (split, why3) = splitToRequired(tris, required)
                if (split == null) return null to why3
                for (t in split) {
                    if (up) {
                        mb.triangle(world(t.a, z), world(t.b, z), world(t.c, z))
                    } else {
                        mb.triangle(world(t.a, z), world(t.c, z), world(t.b, z))
                    }
                }
            }
        }
        return mb.build() to null
    }

    /** [ring] with every corner of [required] that lies in the interior of one of its edges inserted. */
    private fun conform(
        ring: List<Vec2>,
        required: List<Vec2>,
    ): List<Vec2> {
        val out = ArrayList<Vec2>(ring.size)
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            out.add(a)
            val d = b - a
            val len = d.length()
            if (len <= RegionBool.EPS) continue
            val inner = ArrayList<Pair<Double, Vec2>>()
            for (p in required) {
                val t = (p - a).dot(d) / (len * len)
                if (t * len <= RegionBool.EPS || (1.0 - t) * len <= RegionBool.EPS) continue
                if (abs(d.cross(p - a)) / len > RegionBool.EPS) continue
                inner.add(t to p)
            }
            inner.sortWith(compareBy({ it.first }, { it.second.x }, { it.second.y }))
            var last = a
            for ((_, p) in inner) {
                if ((p - last).length() > RegionBool.EPS) {
                    out.add(p)
                    last = p
                }
            }
        }
        return out
    }

    /**
     * [tris] subdivided until no corner of [required] lies in the interior of a triangle edge.
     *
     * A point on an edge splits its triangle in two through the opposite corner; both halves keep the
     * winding, the new interior edge is shared by exactly those two, and each split consumes one point —
     * so this terminates and stays manifold. A [required] point on an *interior* diagonal is split on
     * both sides, because the test is a function of the edge alone. The budget is a guard, not a policy:
     * exceeding it refuses the mesh (OP-3) rather than emitting one with a T-junction in it.
     */
    private fun splitToRequired(
        tris: List<Tri3>,
        required: List<Vec2>,
    ): Pair<List<Tri3>?, String?> {
        val out = ArrayList<Tri3>(tris.size)
        val pending = ArrayDeque<Tri3>()
        pending.addAll(tris)
        var budget = 4 * (tris.size + 1) * (required.size + 1)
        while (pending.isNotEmpty()) {
            val t = pending.removeFirst()
            val hit = firstInteriorPoint(t, required)
            if (hit == null) {
                out.add(t)
                continue
            }
            if (budget-- <= 0) return null to "a cap of the prism cannot be split to meet its neighbours"
            val (edge, p) = hit
            when (edge) {
                0 -> {
                    pending.addLast(Tri3(t.a, p, t.c))
                    pending.addLast(Tri3(p, t.b, t.c))
                }
                1 -> {
                    pending.addLast(Tri3(t.b, p, t.a))
                    pending.addLast(Tri3(p, t.c, t.a))
                }
                else -> {
                    pending.addLast(Tri3(t.c, p, t.b))
                    pending.addLast(Tri3(p, t.a, t.b))
                }
            }
        }
        return out to null
    }

    /** The first (edge index, point) of [t] with a [required] corner strictly inside that edge. */
    private fun firstInteriorPoint(
        t: Tri3,
        required: List<Vec2>,
    ): Pair<Int, Vec2>? {
        val ends = listOf(t.a to t.b, t.b to t.c, t.c to t.a)
        for ((k, e) in ends.withIndex()) {
            val d = e.second - e.first
            val len = d.length()
            if (len <= RegionBool.EPS) continue
            for (p in required) {
                val u = (p - e.first).dot(d) / (len * len)
                if (u * len <= RegionBool.EPS || (1.0 - u) * len <= RegionBool.EPS) continue
                if (abs(d.cross(p - e.first)) / len > RegionBool.EPS) continue
                return k to p
            }
        }
        return null
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
            // A prism's named faces are the same construction over its own extent (OP-22): the accessor
            // survives a boolean, so a boss can still be sketched on a counterbored plate's top face.
            is Feature3.Prism ->
                when (which) {
                    SolidFace.TOP -> feature.plane.translated(feature.maxZ) to null
                    SolidFace.BOTTOM -> feature.plane.translated(feature.minZ).flipped() to null
                }
            // Deliberately refused rather than guessed: a revolve's end caps are planes too, but they
            // are *rotated* frames, and naming them TOP/BOTTOM would invent a convention this slice has
            // no use for. The cut is recorded in DESIGN.md under OP-17.
            is Feature3.Revolution -> null to "a revolved solid has no top or bottom face"
        }

    // ---- sections: the downward half of the seam (OP-17), exact for prisms (OP-22) ----

    /**
     * The **horizontal cross-section** of [feature] at world height [height] mm, as 2D areas in **world
     * plan coordinates** — the downward direction of the seam (OP-17: `section(solid, plane) → Region`).
     *
     * For a prismatic solid this is not an approximation of anything: a prism *is* a stack of areas over
     * z-intervals (OP-22), so the section at a height **is** the slab there, corner for corner. A plain
     * [Feature3.Extrusion] is answered from its own analytic sketch rather than from its prismatic
     * reading, so a cut through a bored plate keeps its exact circles — the tessellation that a boolean
     * would force is not needed here.
     *
     * **The boundary rule** (a height landing exactly on a slab interface, within [Z_EPS]): the section
     * shows the material **above** the cut. Stated in the *world*, so it holds for a solid extruded from a
     * flipped face plane too, whose own axis runs downwards — hence the two cases below. A height at the
     * solid's very **top** face is therefore outside every slab and refused, as is one below its bottom:
     * a face is not a section, and saying so is more useful than an empty area (OP-3 — the node is
     * invalid, hidden, and heals when the height moves back).
     *
     * Refused rather than guessed: a revolve (its cross-section is a real analytic problem, cut from this
     * slice), and a prism whose axis is not vertical (a horizontal cut through it is not one of its
     * slabs at all).
     *
     * The areas come back mapped through the sketch plane's own in-plane frame, so they are in the
     * coordinates the 2D canvas draws — the *plan* — and not in the sketch's. For the world XY plane and
     * every plane derived from it by [Plane3.translated] that map is the identity; for a flipped one it is
     * a reflection, which is why it is applied rather than assumed away.
     */
    fun sectionAt(
        feature: Feature3,
        height: Double,
    ): Pair<List<Region>?, String?> {
        val plane: Plane3
        val layers: List<Slab>
        when (feature) {
            is Feature3.Revolution ->
                return null to "a revolved solid has no prismatic cross-section; sectioning one needs an analytic revolve section (OP-17)"
            is Feature3.Extrusion -> {
                plane = feature.sketch.plane
                // [Slab] is borrowed here as a plain (interval, areas) carrier and never escapes this
                // function, so the polygonal-regions convention of a *stored* slab is not at stake — which
                // is the whole point: these regions are the analytic ones, arcs and all.
                layers = listOf(Slab(feature.sketch.regions, 0.0, feature.depth))
            }
            is Feature3.Prism -> {
                plane = feature.plane
                layers = feature.slabs
            }
        }
        val n = plane.normal.normalized()
        if (abs(abs(n.z) - 1.0) > 1e-9) {
            return null to "this solid is not extruded vertically, so a horizontal cut is not one of its cross-sections"
        }
        // the solid's own axis coordinate of the cut: n is ±Z, so dividing by n.z is multiplying by it
        val s = (height - plane.origin.z) * n.z
        val up = n.z > 0.0
        val hit =
            layers.firstOrNull {
                if (up) s >= it.z0 - Z_EPS && s < it.z1 - Z_EPS else s > it.z0 + Z_EPS && s <= it.z1 + Z_EPS
            }
        if (hit == null) {
            val zs = layers.flatMap { listOf(plane.origin.z + n.z * it.z0, plane.origin.z + n.z * it.z1) }
            return null to "the solid has no material at z = $height mm (it spans ${zs.min()} to ${zs.max()} mm, and its top face is not a section)"
        }
        // sketch coordinates -> world plan coordinates: u and v as the columns of a 2D affine, which is
        // rigid (the frame is orthonormal and its normal is ±Z, so u and v lie in the plan), hence exact.
        val t = Affine(plane.u.x, plane.u.y, plane.v.x, plane.v.y, plane.origin.x, plane.origin.y)
        val out =
            hit.regions.map { r ->
                Region(
                    GeomMath.orient(GeomMath.transform(r.outer, t), ccw = true),
                    r.holes.map { GeomMath.orient(GeomMath.transform(it, t), ccw = false) },
                )
            }
        return out to null
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
