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
 *
 * **And one plan that is read off a feature rather than off triangles** ([ofSwept]). A *swept* body has a
 * feature but no sketch to show, so its plan came through here as well — which made the 2D view the one
 * consumer that could not avoid meshing, and a tube along a coil the one body a plan drag had to tessellate.
 * The run and the section answer the same question directly and exactly; the mesh route above stays what it
 * is, for the bodies that genuinely have nothing but triangles.
 */
object Silhouette {
    /**
     * **The plan of a swept body, read off its run instead of its triangles** — the loops it projects to
     * along [plane]'s normal, in that plane's own 2D coordinates.
     *
     * *Why there is a second route at all.* A swept solid's plan is a silhouette rather than a sketch, and
     * taking it from the mesh ([of]) made the 2D canvas the one consumer that could not avoid meshing — so a
     * tube along a three-turn helix rebuilt tens of thousands of triangles per mouse move for a plan drag
     * that never draws one (the deferral in [Solid3]). This route asks the run itself.
     *
     * *What it is.* At each station the outline of the body, seen in this plane, touches the section at its
     * two **extreme points across the run**: take the in-plane direction `m` perpendicular to the projected
     * tangent, and the section's support points along `+m` and `−m` are on the outline. Walking those gives
     * two rails, and the rails **are** the plan of the body's sides.
     *
     * *And it is exact for a tube's sides.* `m` is perpendicular to the tangent and lies in the plane, so it
     * lies in the section's own plane — which means for a circular section the support point is at exactly
     * the stated radius, whatever the run's inclination, with no cosine and no tessellation of the circle
     * involved ([radius] is passed analytically for that reason). For a general section the support is taken
     * over the tessellated boundary, which is OP-15's standing bargain and no worse than the mesh was. The
     * mitre push at a kink is included, because the support is taken of the *placed* ring, so the outer side
     * of a corner reaches its mitre point rather than the offset of the corner.
     *
     * **What it costs, said plainly, because this outline is also the pick target.** Two things differ from
     * the mesh silhouette:
     * - an **end face** of an open run is closed by the chord between its two rails, not by the outline of
     *   the projected end face. A body seen more end-on than side-on therefore has a hint that stops short of
     *   its own end by up to the section's reach, and a click that far past the last station may miss it. The
     *   rails run right up to the end, so what is *drawn* and what is *picked* stay the same geometry — the
     *   HitTest invariant — and the hint is a hint.
     * - where the run projects onto **one point** (a leg running straight down onto this plane) the rails
     *   are undefined, and what is drawn instead is the section's own projected outline there — exactly the
     *   right picture of a body seen end-on, and for a round section an exact circle.
     *
     * It stays a **pure function of the feature and the plane** — no quality argument, no mesh, no
     * tessellation of a round section — so the pick target cannot drift with a rendering choice and comes back
     * identical after a save and a reload.
     *
     * **And that constraint is what slice B was written around, kept structurally rather than by audit**
     * ([MeshQuality]). The rule it stated — *the station count may not become a render-time argument, because
     * this outline reads it* — needed no enforcement in the end: a picture's quality enters through the single
     * door `Solid3.meshAt`, and this function is reached from the *feature*, at evaluation time, before any
     * triangle exists. So nothing a picture asks for can reach these stations, and the coarse mesh of a swept
     * body is deliberately the **same run** with a cheaper ring — see the table at [Solid3.meshAt] for which
     * counts each feature does let a picture move.
     *
     * Loops, never an area — [Silhouette]'s own word: a run that comes back alongside itself in projection
     * (a coil) draws each pass's own rails rather than the boundary of their union, and every line drawn is a
     * line the body really has.
     */
    fun ofSwept(
        stations: List<Frame3>,
        section: List<Vec2>,
        radius: Double?,
        closed: Boolean,
        plane: Plane3,
        /**
         * The section's size at each station, or null for a section of one size (OP-26, session 77's
         * variable-section sweep). One factor per entry of [stations]; with null nothing is multiplied and
         * the outline is bit-identical to the one this always produced.
         */
        scales: List<Double>? = null,
    ): List<Region> {
        if (stations.size < 2 || section.isEmpty()) return emptyList()
        val n = stations.size
        val centres = stations.map { plane.toLocal(it.at) }
        // Per station, the two points its section is extreme at across the run — or nothing, where the run
        // points straight at this plane and there is no "across".
        val rails = arrayOfNulls<Pair<Vec2, Vec2>>(n)
        for (k in 0 until n) {
            // the chord this station's direction is read from: its neighbours, wrapping on a closed run and
            // one-sided at an open end
            val prev =
                when {
                    k > 0 -> k - 1
                    closed -> n - 1
                    else -> k
                }
            val next =
                when {
                    k < n - 1 -> k + 1
                    closed -> 0
                    else -> k
                }
            val flat = centres[next] - centres[prev]
            val span = (stations[next].at - stations[prev].at).length()
            if (span <= 0.0 || flat.length() <= END_ON * span) continue
            rails[k] = support(stations[k], section, radius, flat.perp() * (1.0 / flat.length()), plane, scales?.get(k) ?: 1.0)
        }

        val loops = ArrayList<Region>()
        if (closed && rails.all { it != null }) {
            // A closed run whose rails are defined throughout comes back to itself, and the two rails are two
            // rings: joining them into one loop would draw two radial lines the body has no edge at.
            loops.add(chain(rails.map { it!!.first }, true))
            loops.add(chain(rails.map { it!!.second }, true))
        } else {
            var k = 0
            while (k < n) {
                if (rails[k] == null) {
                    k++
                    continue
                }
                var j = k
                while (j + 1 < n && rails[j + 1] != null) j++
                val run = (k..j).map { rails[it]!! }
                loops.add(
                    if (run.size == 1) {
                        // one station on its own: the one chord across its section is all it can claim
                        chain(listOf(run[0].first, run[0].second), false)
                    } else {
                        chain(run.map { it.first } + run.map { it.second }.asReversed(), true)
                    },
                )
                k = j + 1
            }
        }
        // …and wherever a stretch of the run points straight at this plane, the section's own projected
        // outline is what the body shows there — drawn where that stretch begins and where it ends.
        for (k in 0 until n) {
            if (rails[k] != null) continue
            if (k == 0 || k == n - 1 || rails[k - 1] != null || rails[k + 1] != null) {
                loops.add(sectionLoop(stations[k], section, radius, plane, scales?.get(k) ?: 1.0))
            }
        }
        return loops
    }

    /**
     * The two points of [station]'s section that are extreme along [m] in [plane] — the outline's two rails.
     *
     * For a round section this is arithmetic rather than a search: [m] lies in the section's own plane (it is
     * perpendicular to the tangent and to the plane's normal), so `radius · m` expressed in the station's
     * `(ref, bi)` axes *is* the extreme point, and the exactness note in [ofSwept] is this one line. For any
     * other section the support is taken over its tessellated boundary.
     */
    private fun support(
        station: Frame3,
        section: List<Vec2>,
        radius: Double?,
        m: Vec2,
        plane: Plane3,
        /** The station's own size factor (OP-26, session 77) — exactly 1.0 for a section of one size. */
        scale: Double,
    ): Pair<Vec2, Vec2> {
        val world = plane.u * m.x + plane.v * m.y
        if (radius != null) {
            val inFrame = Vec2(world.dot(station.ref), world.dot(station.bi))
            val len = inFrame.length()
            if (len > 0.0) {
                // the analytic reading stays analytic under a law: a scaled circle is a circle, so the
                // support point is at exactly `scale * radius` and no chord of the section is consulted
                val at = inFrame * (radius * scale / len)
                return plane.toLocal(station.place(at)) to plane.toLocal(station.place(-at))
            }
        }
        var hi = section[0]
        var lo = section[0]
        var hiD = Double.NEGATIVE_INFINITY
        var loD = Double.POSITIVE_INFINITY
        for (p in section) {
            val d = (station.place(sized(p, scale))).dot(world)
            if (d > hiD) {
                hiD = d
                hi = p
            }
            if (d < loD) {
                loD = d
                lo = p
            }
        }
        return plane.toLocal(station.place(sized(hi, scale))) to plane.toLocal(station.place(sized(lo, scale)))
    }

    /** A section point at a station's own size — the identity where the section has one size (`scale == 1.0`). */
    private fun sized(
        p: Vec2,
        scale: Double,
    ): Vec2 = if (scale == 1.0) p else p * scale

    /**
     * The section's own outline at [station], projected — what a run shows where it points straight at this
     * plane. Exact and one element for a round section; the tessellated boundary otherwise.
     */
    private fun sectionLoop(
        station: Frame3,
        section: List<Vec2>,
        radius: Double?,
        plane: Plane3,
        /** The station's own size factor (OP-26, session 77) — exactly 1.0 for a section of one size. */
        scale: Double,
    ): Region {
        val centre = plane.toLocal(station.at)
        if (radius != null) {
            return Region(Loop(listOf(ProfileElement.CircleE(Circle(centre, radius * scale)))), emptyList())
        }
        return chain(section.map { plane.toLocal(station.place(sized(it, scale))) }, true)
    }

    /**
     * A rail (or a ring) as a [Region] of straight pieces — the same shape [regionOf] hands back for a traced
     * mesh outline, and for the same reason: the three consumers of a plan draw, measure and marquee-test
     * pieces, and none of them assumes an area.
     */
    private fun chain(
        points: List<Vec2>,
        closed: Boolean,
    ): Region {
        val merged = mergeCollinear(points, closed)
        val last = if (closed) merged.size else merged.size - 1
        return Region(Loop((0 until last).map { ProfileElement.Seg(Segment(merged[it], merged[(it + 1) % merged.size])) }), emptyList())
    }

    /**
     * How short a projected chord may get before a stretch of the run counts as pointing **straight at** the
     * plan — relative to the chord it projects from, so it is an angle (about 0.06°) and not a length.
     */
    private const val END_ON = 1e-3

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
