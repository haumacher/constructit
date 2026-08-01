package constructit.geom

/**
 * **The plan of a mesh-only solid** — the outline its triangles project to, seen along a plane's normal.
 *
 * The long-parked question OP-9 and OP-17 both left open ("the mesh-only footprint"): every *constructed*
 * solid shows a plan because its feature holds one — an extrusion's sketch, a prism's slabs — and that plan
 * is what the 2D view draws as a footprint hint and what a click picks the solid by. A solid with **no**
 * analytic feature has none of that, and until now the consequence was silent and severe: it drew nothing,
 * so it was invisible in the plan, and it answered no distance, so it could not fill a `SOLID` slot at all —
 * no boolean, no placement, no measurement by clicking.
 *
 * The answer here is the one the geometry actually supports, and no more than that: for a **closed, oriented**
 * mesh — which every solid in this engine is, by OP-9's watertight-or-refused doctrine — the boundary of the
 * projected shape is exactly the set of **silhouette edges**, and they are found without a single 2D boolean.
 * Project every vertex into the plane; call a triangle *front* when its projected signed area is positive;
 * then an edge shared by two front triangles is used in both directions and is interior, while an edge
 * between a front and a back triangle is used in one direction only — and is therefore on the outline. The
 * survivors are directed consistently by construction (counter-clockwise around the projected material), so
 * chaining them end to end yields closed loops with no orientation to guess.
 *
 * **Loops, not an area, and that is the honest word.** Each loop becomes a [Region] of its own with no holes.
 * A ring's inner loop *is* a hole of its outer one and a nesting analysis could say so — but a silhouette may
 * also **self-overlap** (a bracket seen from an angle whose two arms cross in projection), and there the
 * outer/hole reading is not merely unknown, it does not exist. So this returns what it can prove: *these are
 * the curves the body's outline projects to*. That is exactly what the three consumers of a feature's plan
 * ask for — the renderer draws every loop, the hit test measures distance to every piece, the marquee tests
 * every piece — and it claims nothing an area-consuming caller could later be misled by.
 *
 * **Exactness.** The loops are exact for the mesh: every vertex is the mesh's own, orthogonally projected, and
 * the only arithmetic is a projection and a sign. What is approximate is the mesh, which is the standing
 * bargain for anything that has left the analytic layer (OP-15) — a bore's outline is its chords, exactly as
 * the 3D view draws it.
 */
object Silhouette {
    /**
     * The outline loops of [mesh] seen along [plane]'s normal, in that plane's own 2D coordinates.
     *
     * Deterministic: triangles are read in mesh order and each loop is walked from the lowest-numbered
     * unused edge, so the same mesh always yields the same loops in the same order — the rule [Mesh3]
     * itself obeys, and the one a byte-equal save depends on.
     *
     * Linear in the triangle count: one projection per vertex, one sign per triangle, one hash entry per
     * directed edge of a front triangle. No 2D boolean, no tolerance, no repair.
     */
    fun of(
        mesh: Mesh3,
        plane: Plane3,
    ): List<Region> {
        if (mesh.triangles.isEmpty()) return emptyList()
        val flat = mesh.vertices.map { plane.toLocal(it) }
        // Every directed edge of every front-facing triangle. An interior edge appears here twice, once in
        // each direction; an outline edge appears once — which is the whole algorithm.
        val edges = HashSet<Long>(mesh.triangles.size * 2)
        val order = ArrayList<Long>(mesh.triangles.size * 3)
        for (t in mesh.triangles) {
            val a = flat[t.a]
            val b = flat[t.b]
            val c = flat[t.c]
            // the projected signed area, doubled — a triangle seen edge-on has none and is *back*, which is
            // a choice made once so that every triangle has a side and no edge is left unclassified
            if ((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x) <= 0.0) continue
            for (e in listOf(key(t.a, t.b), key(t.b, t.c), key(t.c, t.a))) {
                edges.add(e)
                order.add(e)
            }
        }
        val outline = LinkedHashMap<Int, ArrayList<Int>>()
        var count = 0
        for (e in order) {
            if (reverse(e) in edges) continue
            outline.getOrPut(from(e)) { ArrayList(2) }.add(to(e))
            count++
        }
        if (count == 0) return emptyList()

        val loops = ArrayList<Region>()
        val used = HashMap<Int, Int>(outline.size * 2)
        for ((start, _) in outline) {
            while (true) {
                val ring = walk(start, outline, used, count) ?: break
                if (ring.size >= 3) loops.add(regionOf(ring.map { flat[it] }))
            }
        }
        return loops
    }

    /**
     * One closed ring from [start], consuming outline edges as it goes — or null when [start] has none left.
     *
     * A vertex may carry several outline edges (two silhouette loops can touch at a point, and a projection
     * can bring two unrelated parts of a body onto one vertex), so the walk takes them in the order they were
     * met. The step cap is the total number of outline edges: a closed mesh cannot produce an open chain, and
     * a cap is what turns "cannot" into "does not hang" if one ever does.
     */
    private fun walk(
        start: Int,
        outline: Map<Int, ArrayList<Int>>,
        used: HashMap<Int, Int>,
        cap: Int,
    ): List<Int>? {
        val first = outline[start] ?: return null
        if ((used[start] ?: 0) >= first.size) return null
        val ring = ArrayList<Int>()
        var at = start
        var steps = 0
        while (steps++ <= cap) {
            val outs = outline[at] ?: return null
            val i = used[at] ?: 0
            if (i >= outs.size) return null
            used[at] = i + 1
            ring.add(at)
            at = outs[i]
            if (at == start) return ring
        }
        return null
    }

    /** A closed ring of plane points as a [Region] — see this object's note on why it carries no holes. */
    private fun regionOf(points: List<Vec2>): Region {
        val merged = mergeCollinear(points)
        val segments = merged.indices.map { ProfileElement.Seg(Segment(merged[it], merged[(it + 1) % merged.size])) }
        return Region(Loop(segments), emptyList())
    }

    /**
     * Consecutive points that lie on one straight run, collapsed to its ends.
     *
     * A tessellated body's silhouette runs along facet edges, so a flat face contributes one edge per facet
     * where it means one line — a box would arrive with eight outline segments instead of four. The test is
     * **exact collinearity** (a zero cross product), never a tolerance: merging *nearly* collinear chords
     * would move the outline off the mesh by the sagitta, and a hint that is not where the body is would be
     * worse than a long one.
     */
    private fun mergeCollinear(points: List<Vec2>): List<Vec2> {
        if (points.size < 3) return points
        val out = ArrayList<Vec2>(points.size)
        for (i in points.indices) {
            val prev = points[(i - 1 + points.size) % points.size]
            val here = points[i]
            val next = points[(i + 1) % points.size]
            val a = here - prev
            val b = next - here
            if (a.cross(b) == 0.0 && a.dot(b) > 0.0) continue
            out.add(here)
        }
        return if (out.size >= 3) out else points
    }

    private fun key(
        a: Int,
        b: Int,
    ): Long = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)

    private fun reverse(e: Long): Long = key(to(e), from(e))

    private fun from(e: Long): Int = (e ushr 32).toInt()

    private fun to(e: Long): Int = e.toInt()
}
