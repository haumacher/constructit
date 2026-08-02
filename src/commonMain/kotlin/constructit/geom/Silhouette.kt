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
 * The answer here is the one the geometry actually supports, and no more than that: the boundary of the
 * projected shape is exactly the set of **silhouette edges**, and they are found without a single 2D boolean.
 * Project every vertex into the plane; call a triangle *front* when its projected signed area is positive;
 * then an edge shared by two front triangles is used in both directions and is interior, while an edge with
 * no front-facing partner is used in one direction only — and is therefore on the outline. The survivors are
 * directed consistently by construction (counter-clockwise around the projected material), so chaining them
 * end to end yields loops with no orientation to guess.
 *
 * **An open shell needs no special case, and the reason is worth stating** (the JT import's flagged bodies,
 * OP-9). The kept edges are the boundary of the front-facing *chain*, and the boundary of a chain is a
 * cycle — so a surface with a hole in it still yields **closed** loops: what changes is only *which* loops,
 * because the shell's own rim is now part of the outline wherever it borders front-facing material. That is
 * the honest picture and it is drawn as such. What genuinely cannot close is a mesh whose triangles are
 * **inconsistently wound** or non-manifold, where one directed edge is claimed twice and the count no longer
 * balances; those come out as **open chains**, drawn and picked as the chains they are. Nothing is ever
 * mended, dropped or silently emptied — an outline that cannot close is said by being open.
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
     * Deterministic: triangles are read in mesh order and each chain is walked from the first vertex that
     * still has an unused edge, so the same mesh always yields the same outline in the same order — the rule
     * [Mesh3] itself obeys, and the one a byte-equal save depends on.
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
                val chain = walk(start, outline, used, count) ?: break
                // a closed ring needs three corners to bound anything; an open chain is already a drawing at
                // two, so both floors are the honest ones for what the chain *is*
                if (chain.points.size >= (if (chain.closed) 3 else 2)) {
                    loops.add(regionOf(chain.points.map { flat[it] }, chain.closed))
                }
            }
        }
        return loops
    }

    /** One traced run of outline edges: its vertices in order, and whether it came back to its start. */
    private class Chain(val points: List<Int>, val closed: Boolean)

    /**
     * One run of outline edges from [start], consuming them as it goes — or null when [start] has none left.
     *
     * A vertex may carry several outline edges (two silhouette loops can touch at a point, and a projection
     * can bring two unrelated parts of a body onto one vertex), so the walk takes them in the order they were
     * met.
     *
     * **A run that dead-ends is returned open, not discarded.** For any mesh whose directed edges balance —
     * every closed or open *shell* with consistent winding — the walk always returns to its start, because
     * the boundary of a chain is a cycle. A mesh that is inconsistently wound or non-manifold breaks that
     * balance, and then a run genuinely ends somewhere else: the honest answer is the polyline it traced,
     * which draws and picks as what it is. Discarding it would be the one outcome forbidden here — an
     * outline silently missing part of a body.
     *
     * The step cap is the total number of outline edges, which is what turns "cannot loop forever" from an
     * argument into a fact.
     */
    private fun walk(
        start: Int,
        outline: Map<Int, ArrayList<Int>>,
        used: HashMap<Int, Int>,
        cap: Int,
    ): Chain? {
        val first = outline[start] ?: return null
        if ((used[start] ?: 0) >= first.size) return null
        val run = ArrayList<Int>()
        var at = start
        var steps = 0
        while (steps++ <= cap) {
            val outs = outline[at]
            val i = used[at] ?: 0
            if (outs == null || i >= outs.size) {
                // the run ends here: keep the vertex it ended on, so the last segment is drawn
                run.add(at)
                return Chain(run, false)
            }
            used[at] = i + 1
            run.add(at)
            at = outs[i]
            if (at == start) return Chain(run, true)
        }
        run.add(at)
        return Chain(run, false)
    }

    /**
     * A traced run of plane points as a [Region] — closed into a ring when it came back to its start, left
     * **open** when it did not. See this object's note on why neither carries holes.
     *
     * An open chain is a `Loop` whose pieces do not meet, which is exactly what the three consumers of a
     * plan need it to be: the renderer draws each piece, the hit test measures distance to each piece, the
     * marquee tests each piece — none of them assumes closure, and inventing a closing segment would draw a
     * line where the body has no edge.
     */
    private fun regionOf(
        points: List<Vec2>,
        closed: Boolean,
    ): Region {
        val merged = mergeCollinear(points, closed)
        val last = if (closed) merged.size else merged.size - 1
        val segments = (0 until last).map { ProfileElement.Seg(Segment(merged[it], merged[(it + 1) % merged.size])) }
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
    private fun mergeCollinear(
        points: List<Vec2>,
        closed: Boolean,
    ): List<Vec2> {
        if (points.size < 3) return points
        val out = ArrayList<Vec2>(points.size)
        for (i in points.indices) {
            // an **open** chain has two ends, and an end is never an interior point of a straight run — it is
            // where the outline stops, so it is kept whatever its neighbours do
            if (!closed && (i == 0 || i == points.size - 1)) {
                out.add(points[i])
                continue
            }
            val prev = points[(i - 1 + points.size) % points.size]
            val here = points[i]
            val next = points[(i + 1) % points.size]
            val a = here - prev
            val b = next - here
            if (a.cross(b) == 0.0 && a.dot(b) > 0.0) continue
            out.add(here)
        }
        return if (out.size >= (if (closed) 3 else 2)) out else points
    }

    private fun key(
        a: Int,
        b: Int,
    ): Long = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)

    private fun reverse(e: Long): Long = key(to(e), from(e))

    private fun from(e: Long): Int = (e ushr 32).toInt()

    private fun to(e: Long): Int = e.toInt()
}
