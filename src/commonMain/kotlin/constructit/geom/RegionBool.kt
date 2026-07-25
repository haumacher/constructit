package constructit.geom

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor

/** Which set operation a boolean performs (OP-22). */
enum class BoolOp { UNION, INTERSECT, SUBTRACT }

/**
 * The **2D region boolean kernel** (OP-22): union / intersection / difference of polygonal areas with
 * holes, exact enough to carry the watertightness guarantee up into the prismatic solid booleans that
 * consume it.
 *
 * ### Why polygons only
 * Curved boundary pieces are tessellated *first*, at the ordinary [GeomMath.TESS_TOL_MM], so this
 * kernel is purely polygonal. That is the 2D analog of the mesh-is-a-sink rule (OP-15): an exact
 * analytic circle stays exact until it meets a boolean, and a boolean's boundary is an **approximated**
 * curve from then on. Trading exactness for a closed, robust algebra is the same trade OP-15 already
 * records for spline offsets — and it is confined to the operands of a boolean, not to extrusion.
 *
 * ### The algorithm — an arrangement, then a winding classification
 * Not Greiner-Hormann (fails on shared edges) and not a Martinez-Rueda sweep (whose status flags are
 * the part that is hard to get right in the degenerate cases). Instead, the honest brute-force form of
 * the same idea, in four deterministic passes:
 *
 * 1. **Weld** every input vertex into one table on an [EPS] lattice, so "the same point" is one integer.
 * 2. **Arrange**: split every input edge at every point where another edge crosses or touches it,
 *    collinear overlaps included, producing *fragments* whose interiors meet no other fragment. Each
 *    undirected fragment is kept **once**, however many operands contributed it — which is exactly what
 *    makes a shared edge behave.
 * 3. **Classify** each fragment by asking which side of it is material, for each operand, at a probe
 *    point provably inside a face of the arrangement (offset from the fragment's midpoint by less than
 *    half the distance to the nearest non-collinear edge). Inside-ness is the **nonzero winding rule**,
 *    which is precisely OP-14's convention (outer counter-clockwise, holes clockwise). A fragment
 *    survives iff the operation's result differs on its two sides, oriented so the material is on its
 *    left.
 * 4. **Chain** the surviving fragments into loops: at each vertex the next edge is the first one found
 *    rotating **clockwise** from the reverse of the arrival direction, which separates a pinch point
 *    into two loops instead of one figure-eight. Loops then nest into `Region(outer, holes)`.
 *
 * No probe is ever taken *on* a boundary, no epsilon is applied to a raw cross product, and nothing
 * iterates a hash map — every ordering here is insertion or an explicit sort, so a result is a
 * deterministic function of the input coordinates (which is load-bearing: see OP-15).
 *
 * ### Complexity, and what it costs
 * The arrangement is **O(E²)** in the number of boundary edges and the classification O(F·E), because both
 * passes are plain double loops. That is a deliberate trade for the degenerate-case honesty above: at the
 * sizes this engine produces — a bored plate is a few hundred edges, a wall a handful — it is immeasurable,
 * and a sweep-line version (O(E log E)) can replace either pass behind this same signature if a
 * thousand-edge operand (a tessellated involute gear cut into a plate) ever makes it matter.
 *
 * ### What it refuses
 * A boundary configuration it cannot resolve is **refused with a reason** rather than approximated
 * (OP-3): two edges crossing at an angle below [PARALLEL_SIN] are read as parallel, and if that (or any
 * other loss) leaves the arrangement inconsistent — an unbalanced vertex, an unclosable chain, a hole
 * belonging to no boundary — the kernel returns null and the solid above it goes invalid. It never
 * emits an area whose loops do not close, because the mesh built from one would leak silently.
 */
object RegionBool {
    /**
     * Distance below which two points **are** the same point (mm).
     *
     * Deliberately [Geom3.WELD_TOL]'s value: five orders of magnitude below the 0.02 mm tessellation
     * tolerance, so it can never merge two genuinely distinct tessellation points, and far above the
     * ~1e-13 mm noise of a line-line intersection on drawing-sized coordinates, so a crossing computed
     * twice from different edges lands on one vertex.
     */
    const val EPS = 1e-7

    /**
     * Below this sine of the angle between two edges they are treated as **parallel**.
     *
     * The positional error of an intersection point grows like `noise / sin`, so at 1e-9 a crossing is
     * still located to ~1e-7 mm — one [EPS], i.e. it welds to the right vertex. Two boundaries genuinely
     * crossing at a shallower angle than this are *not* split, which is the one honest hole in the
     * arrangement; the chain check in [combine] then fails and the boolean refuses.
     */
    const val PARALLEL_SIN = 1e-9

    /** Loops enclosing less than this (mm²) carry no area and are dropped — a spur, not a boundary. */
    private const val AREA_EPS = 1e-9

    // ---- rings: the polygonal reading of OP-14 regions ----

    /**
     * [regions] as **rings**: closed polygons of distinct corners, outer counter-clockwise and holes
     * clockwise (OP-14's convention, so the nonzero winding rule reads them correctly). Curves are
     * tessellated at [tolMm].
     */
    fun ringsOf(
        regions: List<Region>,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Pair<List<List<Vec2>>?, String?> {
        val out = ArrayList<List<Vec2>>()
        for (r in regions) {
            val (tess, why) = Geom3.tessellateRegion(r, tolMm)
            if (tess == null) return null to (why ?: "cannot tessellate a region")
            out.add(tess.outer)
            out.addAll(tess.holes)
        }
        return out to null
    }

    /**
     * Rings nested back into areas: every counter-clockwise ring is a region, every clockwise ring is a
     * hole of the **innermost** region containing it — so an island inside a hole comes out as its own
     * region, at any depth.
     *
     * Containment is tested at the *midpoint of a ring's first edge*, never at a corner: two loops of a
     * valid area may share isolated points but never a stretch of edge, so an edge midpoint is provably
     * off every other loop and the test needs no tolerance at all.
     */
    fun regionsOf(rings: List<List<Vec2>>): Pair<List<Region>?, String?> {
        val areas = rings.map { ringArea(it) }
        val outers = rings.indices.filter { areas[it] > 0.0 }
        val holes = rings.indices.filter { areas[it] < 0.0 }
        val holesOf = HashMap<Int, MutableList<Int>>()
        for (h in holes) {
            val probe = edgeMidpoint(rings[h])
            val parent =
                outers
                    .filter { windingAt(listOf(rings[it]), probe) != 0 }
                    .minByOrNull { areas[it] }
                    ?: return null to "a hole of the result lies outside every boundary"
            holesOf.getOrPut(parent) { ArrayList() }.add(h)
        }
        return outers.map { o ->
            Region(loopOf(rings[o]), (holesOf[o] ?: emptyList<Int>()).map { loopOf(rings[it]) })
        } to null
    }

    /** A ring as a closed [Loop] of segments — the OP-14 value the seam consumes. */
    fun loopOf(ring: List<Vec2>): Loop =
        Loop(ring.indices.map { ProfileElement.Seg(Segment(ring[it], ring[(it + 1) % ring.size])) })

    /** Signed area of a ring: positive counter-clockwise. */
    fun ringArea(ring: List<Vec2>): Double {
        var s = 0.0
        for (i in ring.indices) s += ring[i].cross(ring[(i + 1) % ring.size])
        return s / 2.0
    }

    /** Total signed area of an area's rings — holes are negative, so this is the material area. */
    fun area(rings: List<List<Vec2>>): Double = rings.sumOf { ringArea(it) }

    /**
     * Winding number of [rings] about [p] (Sunday's algorithm, half-open in y so a ray through a corner
     * is counted once). Exact for any [p] not *on* a ring; the caller guarantees that.
     */
    fun windingAt(
        rings: List<List<Vec2>>,
        p: Vec2,
    ): Int {
        var wn = 0
        for (r in rings) {
            for (i in r.indices) {
                val a = r[i]
                val b = r[(i + 1) % r.size]
                if (a.y <= p.y) {
                    if (b.y > p.y && (b - a).cross(p - a) > 0.0) wn++
                } else if (b.y <= p.y && (b - a).cross(p - a) < 0.0) {
                    wn--
                }
            }
        }
        return wn
    }

    /** Whether [p] is material under the nonzero rule. */
    fun contains(
        rings: List<List<Vec2>>,
        p: Vec2,
    ): Boolean = windingAt(rings, p) != 0

    /**
     * A canonical form of an area: each ring rotated to start at its lexicographically smallest corner,
     * the rings then sorted.
     *
     * Semantically a no-op — it exists so that two areas computed from the same inputs are *structurally*
     * identical, which is what lets adjacent slabs of a prism be compared and merged (OP-22) without a
     * shape-matching search.
     */
    fun canonical(rings: List<List<Vec2>>): List<List<Vec2>> =
        rings
            .map { rotated(it) }
            .sortedWith(compareBy({ it[0].x }, { it[0].y }, { -ringArea(it) }, { it.size }))

    private fun rotated(ring: List<Vec2>): List<Vec2> {
        var best = 0
        for (i in ring.indices) {
            val p = ring[i]
            val q = ring[best]
            if (p.x < q.x || (p.x == q.x && p.y < q.y)) best = i
        }
        return List(ring.size) { ring[(best + it) % ring.size] }
    }

    private fun edgeMidpoint(ring: List<Vec2>): Vec2 = (ring[0] + ring[1 % ring.size]) * 0.5

    // ---- the kernel ----

    /**
     * [kind] applied to the areas [a] and [b], as rings (see [ringsOf]). An **empty result is an empty
     * list**, not a failure — emptiness is meaningful here and becomes invalidity one level up, where
     * there is a solid to hide (OP-3).
     */
    fun combine(
        a: List<List<Vec2>>,
        b: List<List<Vec2>>,
        kind: BoolOp,
    ): Pair<List<List<Vec2>>?, String?> {
        // The trivial cases short-circuit for a reason beyond speed: they hand the operand's own rings
        // back unchanged, so a slab that meets nothing in the other solid stays *identical* to it and
        // the slab merge (OP-22) can recognise it.
        if (a.isEmpty() || b.isEmpty()) {
            return when (kind) {
                BoolOp.UNION -> canonical(a + b) to null
                BoolOp.INTERSECT -> emptyList<List<Vec2>>() to null
                BoolOp.SUBTRACT -> (if (b.isEmpty()) canonical(a) else emptyList()) to null
            }
        }
        val vt = VertexTable()
        val ringsA = a.map { r -> r.map { vt.id(it) } }
        val ringsB = b.map { r -> r.map { vt.id(it) } }
        val edges = ArrayList<IntArray>()
        for (rings in listOf(ringsA, ringsB)) {
            val before = edges.size
            for (r in rings) {
                for (i in r.indices) {
                    val u = r[i]
                    val v = r[(i + 1) % r.size]
                    if (u != v) edges.add(intArrayOf(u, v))
                }
            }
            if (edges.size - before < 3) return null to "an operand of the boolean encloses no area"
        }
        val weldedA = ringsA.map { r -> r.map { vt.points[it] } }
        val weldedB = ringsB.map { r -> r.map { vt.points[it] } }

        // 2. the arrangement: split every edge wherever another meets it
        val splits = Array(edges.size) { ArrayList<Pair<Double, Int>>() }
        for (i in edges.indices) {
            for (j in i + 1 until edges.size) {
                crossings(vt, edges, i, j, splits)
            }
        }
        val frags = ArrayList<IntArray>()
        for (i in edges.indices) {
            val e = edges[i]
            val ordered =
                splits[i].distinctBy { it.second }.sortedWith(compareBy({ it.first }, { it.second }))
            var cursor = e[0]
            for ((_, id) in ordered) {
                if (id != cursor) {
                    frags.add(intArrayOf(cursor, id))
                    cursor = id
                }
            }
            if (cursor != e[1]) frags.add(intArrayOf(cursor, e[1]))
        }

        // 3. one classification per *undirected* fragment — a shared edge is one fragment, not two
        val groups = ArrayList<IntArray>()
        val seen = HashMap<Long, Int>()
        for (f in frags) {
            val lo = minOf(f[0], f[1])
            val hi = maxOf(f[0], f[1])
            val key = lo.toLong() * 1_000_000_007L + hi
            if (seen.put(key, groups.size) == null) groups.add(intArrayOf(f[0], f[1]))
        }
        val kept = ArrayList<IntArray>()
        for (g in groups) {
            val delta = probeOffset(vt, edges, g) ?: return null to "two boundaries are closer together than the kernel can resolve"
            val mid = (vt.points[g[0]] + vt.points[g[1]]) * 0.5
            val n = (vt.points[g[1]] - vt.points[g[0]]).normalized().perp()
            val left = mid + n * delta
            val right = mid - n * delta
            val insideL = apply(kind, contains(weldedA, left), contains(weldedB, left))
            val insideR = apply(kind, contains(weldedA, right), contains(weldedB, right))
            if (insideL == insideR) continue
            kept.add(if (insideL) intArrayOf(g[0], g[1]) else intArrayOf(g[1], g[0]))
        }
        if (kept.isEmpty()) return emptyList<List<Vec2>>() to null

        // 4. chain the surviving fragments into loops
        val (loops, why) = chain(vt, kept)
        if (loops == null) return null to (why ?: "the boolean result does not close")
        val rings = loops.filter { abs(ringArea(it)) > AREA_EPS }
        if (rings.isEmpty()) return emptyList<List<Vec2>>() to null
        return canonical(rings) to null
    }

    /** The whole boolean, from OP-14 regions to OP-14 regions. */
    fun combineRegions(
        a: List<Region>,
        b: List<Region>,
        kind: BoolOp,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Pair<List<Region>?, String?> {
        val (ra, whyA) = ringsOf(a, tolMm)
        if (ra == null) return null to whyA
        val (rb, whyB) = ringsOf(b, tolMm)
        if (rb == null) return null to whyB
        val (rings, why) = combine(ra, rb, kind)
        if (rings == null) return null to why
        if (rings.isEmpty()) return emptyList<Region>() to null
        return regionsOf(rings)
    }

    private fun apply(
        kind: BoolOp,
        inA: Boolean,
        inB: Boolean,
    ): Boolean =
        when (kind) {
            BoolOp.UNION -> inA || inB
            BoolOp.INTERSECT -> inA && inB
            BoolOp.SUBTRACT -> inA && !inB
        }

    /**
     * How far off the fragment's midpoint a side probe may sit: half the distance to the nearest edge
     * that is *not* collinear with the fragment, capped at a quarter of the fragment's own length.
     *
     * Any edge crossing the fragment's interior transversally would already have split it, so an edge at
     * distance zero from the midpoint is necessarily collinear with it and is skipped — which is exactly
     * how a shared edge gets classified without ever probing *on* a boundary. Null when even that leaves
     * no room, i.e. when the input is finer than [EPS] and no honest answer exists.
     */
    private fun probeOffset(
        vt: VertexTable,
        edges: List<IntArray>,
        frag: IntArray,
    ): Double? {
        val p = vt.points[frag[0]]
        val q = vt.points[frag[1]]
        val mid = (p + q) * 0.5
        val len = (q - p).length()
        var nearest = len * 0.5
        for (e in edges) {
            val a = vt.points[e[0]]
            val b = vt.points[e[1]]
            val d = distToSegment(mid, a, b)
            if (d <= EPS) continue // collinear with this fragment (nothing else can touch its midpoint)
            if (d < nearest) nearest = d
        }
        val delta = minOf(nearest * 0.5, len * 0.25)
        return if (delta > 1e-12) delta else null
    }

    /** Record where edges [i] and [j] meet, as split parameters on both. */
    private fun crossings(
        vt: VertexTable,
        edges: List<IntArray>,
        i: Int,
        j: Int,
        splits: Array<ArrayList<Pair<Double, Int>>>,
    ) {
        val p1 = vt.points[edges[i][0]]
        val q1 = vt.points[edges[i][1]]
        val p2 = vt.points[edges[j][0]]
        val q2 = vt.points[edges[j][1]]
        val r = q1 - p1
        val u = q2 - p2
        val lr = r.length()
        val lu = u.length()
        if (lr < EPS || lu < EPS) return
        val den = r.cross(u)
        if (abs(den) > PARALLEL_SIN * lr * lu) {
            val t = (p2 - p1).cross(u) / den
            val s = (p2 - p1).cross(r) / den
            if (t < -EPS / lr || t > 1.0 + EPS / lr) return
            if (s < -EPS / lu || s > 1.0 + EPS / lu) return
            val id = vt.id(p1 + r * t.coerceIn(0.0, 1.0))
            split(vt, edges, i, id, splits)
            split(vt, edges, j, id, splits)
            return
        }
        // parallel: only a collinear *overlap* splits anything, and its split points are the other
        // edge's own endpoints — already welded vertices, so the overlap costs no new coordinates
        if (distToLine(p2, p1, r) > EPS || distToLine(q2, p1, r) > EPS) return
        split(vt, edges, i, edges[j][0], splits)
        split(vt, edges, i, edges[j][1], splits)
        split(vt, edges, j, edges[i][0], splits)
        split(vt, edges, j, edges[i][1], splits)
    }

    /** Note vertex [id] as an interior split of edge [i], if it really lies strictly inside it. */
    private fun split(
        vt: VertexTable,
        edges: List<IntArray>,
        i: Int,
        id: Int,
        splits: Array<ArrayList<Pair<Double, Int>>>,
    ) {
        val e = edges[i]
        if (id == e[0] || id == e[1]) return
        val a = vt.points[e[0]]
        val d = vt.points[e[1]] - a
        val len2 = d.dot(d)
        if (len2 <= 0.0) return
        val p = vt.points[id]
        val t = (p - a).dot(d) / len2
        val len = kotlin.math.sqrt(len2)
        if (t * len <= EPS || (1.0 - t) * len <= EPS) return
        if (distToLine(p, a, d) > 2.0 * EPS) return
        splits[i].add(t to id)
    }

    private fun distToLine(
        p: Vec2,
        origin: Vec2,
        dir: Vec2,
    ): Double {
        val len = dir.length()
        if (len < EPS) return (p - origin).length()
        return abs(dir.cross(p - origin)) / len
    }

    private fun distToSegment(
        p: Vec2,
        a: Vec2,
        b: Vec2,
    ): Double {
        val ab = b - a
        val len2 = ab.dot(ab)
        val t = if (len2 <= 0.0) 0.0 else ((p - a).dot(ab) / len2).coerceIn(0.0, 1.0)
        return (p - (a + ab * t)).length()
    }

    /**
     * Chain the kept directed fragments into closed loops.
     *
     * At a vertex where several boundaries meet, the next fragment is the first one met rotating
     * **clockwise** from the reverse of the arrival direction. That is the rule that keeps the material
     * hugged on the left and, at a pinch point (two areas touching at a corner), separates the two
     * boundaries into two loops rather than one self-touching figure-eight — which is what a
     * triangulator downstream needs. The reverse fragment itself can never be a candidate: a fragment
     * and its reverse would have to be material on both sides at once.
     */
    private fun chain(
        vt: VertexTable,
        kept: List<IntArray>,
    ): Pair<List<List<Vec2>>?, String?> {
        val outOf = HashMap<Int, MutableList<Int>>()
        val inDegree = HashMap<Int, Int>()
        // the vertices in first-seen order, so the balance check below reports the same vertex every
        // time (the check itself is order-independent, but its *message* should be too)
        val touched = ArrayList<Int>()
        val known = HashSet<Int>()
        for ((idx, f) in kept.withIndex()) {
            outOf.getOrPut(f[0]) { ArrayList() }.add(idx)
            inDegree[f[1]] = (inDegree[f[1]] ?: 0) + 1
            for (v in f) if (known.add(v)) touched.add(v)
        }
        for (v in touched) {
            if ((outOf[v]?.size ?: 0) != (inDegree[v] ?: 0)) {
                return null to "the boolean result has an unbalanced boundary vertex"
            }
        }
        val used = BooleanArray(kept.size)
        val loops = ArrayList<List<Vec2>>()
        for (start in kept.indices) {
            if (used[start]) continue
            val ring = ArrayList<Vec2>()
            var cur = start
            val startVertex = kept[start][0]
            var guard = 0
            while (true) {
                if (guard++ > kept.size + 1) return null to "the boolean result does not close"
                used[cur] = true
                ring.add(vt.points[kept[cur][0]])
                val at = kept[cur][1]
                if (at == startVertex && nextFrom(vt, kept, outOf, cur, used, allow = start) == start) break
                val nxt =
                    nextFrom(vt, kept, outOf, cur, used, allow = -1)
                        ?: return null to "the boolean result does not close"
                cur = nxt
            }
            // fewer than three corners is a doubled edge, which encloses nothing — the area filter in
            // [combine] would drop it anyway
            if (ring.size >= 3) loops.add(ring)
        }
        return loops to null
    }

    /**
     * The fragment continuing [cur]: among the unused out-fragments at its far end (plus [allow], so a
     * loop can close onto the fragment it started from), the first one clockwise from the reversed
     * arrival direction.
     */
    private fun nextFrom(
        vt: VertexTable,
        kept: List<IntArray>,
        outOf: Map<Int, MutableList<Int>>,
        cur: Int,
        used: BooleanArray,
        allow: Int,
    ): Int? {
        val at = kept[cur][1]
        val back = vt.points[kept[cur][0]] - vt.points[at]
        val ar = atan2(back.y, back.x)
        var best = -1
        var bestTurn = Double.MAX_VALUE
        for (idx in outOf[at] ?: return null) {
            if (used[idx] && idx != allow) continue
            val d = vt.points[kept[idx][1]] - vt.points[kept[idx][0]]
            var turn = (ar - atan2(d.y, d.x)) % TWO_PI
            if (turn <= 1e-12) turn += TWO_PI
            if (turn < bestTurn) {
                bestTurn = turn
                best = idx
            }
        }
        return if (best < 0) null else best
    }

    private const val TWO_PI = 2.0 * kotlin.math.PI

    /**
     * Welds points onto an [EPS] lattice and hands out ids in **insertion order**, looking each point up
     * in the 9-cell neighbourhood so a coordinate landing just across a cell boundary still finds its
     * twin. The same idea as [Geom3]'s mesh weld, one dimension down.
     */
    private class VertexTable {
        val points = ArrayList<Vec2>()
        private val buckets = HashMap<Long, MutableList<Int>>()

        private fun cell(v: Double): Long = floor(v / EPS).toLong()

        private fun key(
            i: Long,
            j: Long,
        ): Long = i * 73856093L xor j * 19349663L

        fun id(p: Vec2): Int {
            val ci = cell(p.x)
            val cj = cell(p.y)
            for (di in -1L..1L) {
                for (dj in -1L..1L) {
                    val list = buckets[key(ci + di, cj + dj)] ?: continue
                    for (idx in list) if ((points[idx] - p).length() <= EPS) return idx
                }
            }
            val idx = points.size
            points.add(p)
            buckets.getOrPut(key(ci, cj)) { ArrayList() }.add(idx)
            return idx
        }
    }
}
