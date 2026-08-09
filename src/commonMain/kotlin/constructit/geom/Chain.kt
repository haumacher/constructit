package constructit.geom

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A curve **properly embedded** in its sketch plane: it separates that plane into exactly two components,
 * so *"the side to keep"* has a referent (OP-22's extension, step 1).
 *
 * The two forms are the two ways a curve can be proper, and they are one concept rather than two features:
 *
 * - [Open] — a finite run of pieces with a **ray at each end**, i.e. a curve that goes to infinity at both
 *   ends. That is the unbounded Jordan curve theorem's hypothesis, and it is why the rays are the
 *   operator's *well-formedness condition* rather than decoration: a chain that merely **stops** leaves a
 *   crack that space flows around, and the two "sides" are then one region.
 * - [Closed] — an area's boundary, which separates its plane too (a bounded inside, an unbounded outside).
 *   So the closed case *falls out of the same rule* instead of being a second operator, and the through-slot
 *   and the through-bore are the ordinary cut with a closed chain.
 *
 * A chain that **self-intersects** is refused ([Chains.defect]) — not for being ugly, but because it does
 * not separate cleanly and the question the user asked then has no answer.
 *
 * **Which side is which is a property of the value, not of the viewport**: the pieces are *ordered*, so the
 * chain has a direction of travel, and side `+1` is the one on its **left**. For a [Closed] chain, whose
 * outer boundary runs counter-clockwise (OP-14's convention), the left of travel is the inside — one rule,
 * both forms, and it turns with the chain under a rigid motion exactly as OP-1's intersection ordering does.
 */
sealed interface Chain {
    /** The finite pieces, in order. The unbounded part of an [Open] chain is its two rays. */
    val pieces: List<ProfileElement>

    /**
     * A chain that runs to infinity at both ends: [start] leaves the first piece's start, [end] leaves the
     * last piece's end, and both point **away** from the finite run.
     */
    data class Open(
        val start: Ray,
        override val pieces: List<ProfileElement>,
        val end: Ray,
    ) : Chain

    /**
     * A chain that comes back to where it began — an [area]'s boundary, holes included. Its inside is the
     * nonzero-winding inside, which is the same convention every other area in this engine is read by.
     */
    data class Closed(val area: Region) : Chain {
        override val pieces: List<ProfileElement> get() = area.outer.elements + area.holes.flatMap { it.elements }
    }
}

/**
 * The tool that cuts with a [Chain]: **unbounded in the statement, bounded in the implementation**
 * (OP-22's extension, step 1).
 *
 * A cut by an unbounded chain is not a boolean this kernel has to learn — it is an ordinary boolean whose
 * removed operand is **derived at evaluation time from the target's own extent**. Nothing about the graph's
 * shape depends on how big the target happens to be (OP-21 is untouched: this is all value-dependent work
 * inside a node's `compute`), and if the target grows the bound grows with it, because nothing was stored.
 */
object Chains {
    /**
     * Where "the same point" is one point (mm) — the same lattice the 2D kernel welds on
     * ([RegionBool.EPS]), so a chain that touches itself by less than the boolean would resolve is
     * refused rather than silently arranged into something else.
     */
    const val EPS = RegionBool.EPS

    /**
     * The **margin** by which the derived bound exceeds the target, as a fraction of the target's largest
     * extent — and the floor below, in mm, for a target too small for a fraction to mean anything.
     *
     * **Why a margin exists at all, and it is not a fudge.** The bound's job is to close the tool *outside*
     * the target: every face of the closure then lies strictly beyond every face of the target, which makes
     * the coplanar-face degeneracy — the failure mode of the big-box-sized-by-eye workaround this replaces
     * — **unreachable by construction** rather than merely unlikely. A box sized by eye eventually lands a
     * face exactly on a face of the target; a bound derived from the target's own extent plus anything
     * strictly positive never can, because every point of the target satisfies `u ≤ uMax < uMax + margin`.
     *
     * **So why these two numbers, if any positive number would do in exact arithmetic.** Because the
     * arithmetic is not exact, and "strictly greater" has to survive every epsilon that decides coplanarity
     * downstream: `RegionBool.EPS` and `Geom3.Z_EPS` at 1e-7 mm, `Geom3.WELD_TOL` at 1e-7 mm, and the
     * general engine's float32 mesh positions at ~1e-7 *relative* (OP-9). The relative term is the one that
     * answers the last of those — 5 % of the extent is five to six orders above float32's resolution at any
     * drawing size, so no rounding can bring the closure onto the target whether the part is a bracket or a
     * building. The absolute floor answers the first three, which are absolute: 1 mm is seven orders above
     * the welding lattice, so a target that is itself a hair wide still gets a bound that is comfortably a
     * separate surface. Neither number is a tolerance anything is compared against — being too generous
     * costs nothing but a slightly larger prism, which is why the floor is set well clear rather than
     * finely.
     */
    const val MARGIN_FRACTION = 0.05

    /** The smallest derived margin (mm) — see [MARGIN_FRACTION]. */
    const val MARGIN_FLOOR_MM = 1.0

    /** The margin a target of largest extent [extent] mm is bounded with — see [MARGIN_FRACTION]. */
    fun margin(extent: Double): Double = max(MARGIN_FLOOR_MM, MARGIN_FRACTION * extent)

    // ---- building a chain ----

    /**
     * The chain through [points], in order: straight pieces between them, and a ray at each end.
     *
     * **How the rays are stated, and what that costs.** The first ray runs *backwards* along the first
     * span and the last runs *forwards* along the last — so the chain continues out of each end in the
     * direction it was already going, which needs no extra input and is predictable in one sentence. The
     * cost is stated rather than hidden: the two asymptotic directions are **not independently addressable**
     * — to send an end off at a different angle you add a point, which is the same click you would have
     * spent stating a direction. Two points therefore give an infinite **line**, three a corner with two
     * rays, and so on.
     */
    fun through(points: List<Vec2>): Pair<Chain.Open?, String?> {
        if (points.size < 2) return null to "a chain needs at least two points — the first and the last become its rays"
        for (i in 0 until points.size - 1) {
            if ((points[i + 1] - points[i]).length() <= EPS) {
                return null to
                    "points ${i + 1} and ${i + 2} of this chain are in the same place, so the span between them has " +
                    "no direction — move one of them"
            }
        }
        val pieces = (0 until points.size - 1).map { ProfileElement.Seg(Segment(points[it], points[it + 1])) }
        val start = Ray(points[0], (points[0] - points[1]).normalized())
        val end = Ray(points.last(), (points.last() - points[points.size - 2]).normalized())
        return Chain.Open(start, pieces, end) to null
    }

    /** An affine image of a chain — a chain, since an affine map takes rays to rays and areas to areas. */
    fun transform(
        chain: Chain,
        t: Affine,
    ): Chain =
        when (chain) {
            is Chain.Open ->
                Chain.Open(
                    Ray(t.apply(chain.start.origin), t.linear(chain.start.dir).normalized()),
                    chain.pieces.map { GeomMath.transform(it, t) },
                    Ray(t.apply(chain.end.origin), t.linear(chain.end.dir).normalized()),
                )
            is Chain.Closed ->
                Chain.Closed(
                    Region(
                        GeomMath.orient(GeomMath.transform(chain.area.outer, t), ccw = true),
                        chain.area.holes.map { GeomMath.orient(GeomMath.transform(it, t), ccw = false) },
                    ),
                )
        }

    // ---- the refusal that is about the chain alone ----

    /**
     * Why [chain] does not separate its plane — or null when it does.
     *
     * The one condition is that it does not meet itself: two pieces that are not neighbours in the run may
     * not share a point at all, and two that *are* neighbours may share only the corner between them. A
     * chain that crosses or touches itself divides the plane into more than two pieces (or into one, where
     * it merely doubles back), so "which side" has no answer, and answering anyway would be a guess.
     *
     * Asked of the **tessellation**, deliberately: the boolean this feeds tessellates its operands first
     * (OP-22), so what is checked here is exactly the curve that will be built — the 2D analog of OP-15's
     * approximated-curve rule, one dimension down.
     */
    fun defect(
        chain: Chain,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): String? =
        when (chain) {
            is Chain.Open -> openDefect(chain, tolMm)
            is Chain.Closed -> closedDefect(chain, tolMm)
        }

    private fun openDefect(
        chain: Chain.Open,
        tolMm: Double,
    ): String? {
        val pts = polyline(chain.pieces, tolMm) ?: return "the pieces of this chain do not join up end to end"
        polylineDefect(pts, closed = false)?.let { return it }
        // …and the two rays, which is the half a finite check would miss: an end sent back across the run
        // re-crosses it far from any piece, and the chain is then not embedded however tidy its corners are.
        val edges = pts.zipWithNext()
        for ((i, e) in edges.withIndex()) {
            if (i > 0 && rayMeetsSegment(chain.start, e.first, e.second)) {
                return "this chain's first ray runs back across it, so it does not separate the plane — refused, " +
                    "because \"which side\" then has no answer"
            }
            if (i < edges.size - 1 && rayMeetsSegment(chain.end, e.first, e.second)) {
                return "this chain's last ray runs back across it, so it does not separate the plane — refused, " +
                    "because \"which side\" then has no answer"
            }
        }
        if (raysMeet(chain.start, chain.end)) {
            return "this chain's two ends cross each other, so it does not separate the plane — refused, " +
                "because \"which side\" then has no answer"
        }
        return null
    }

    private fun closedDefect(
        chain: Chain.Closed,
        tolMm: Double,
    ): String? {
        val (rings, why) = RegionBool.ringsOf(listOf(chain.area), tolMm)
        if (rings == null) return why ?: "this closed chain cannot be read as a boundary"
        for (r in rings) polylineDefect(r + r.first(), closed = true)?.let { return it }
        for (i in rings.indices) {
            for (j in i + 1 until rings.size) {
                for (ea in (rings[i] + rings[i].first()).zipWithNext()) {
                    for (eb in (rings[j] + rings[j].first()).zipWithNext()) {
                        if (segmentsMeet(ea.first, ea.second, eb.first, eb.second)) {
                            return "two boundaries of this closed chain meet, so it does not separate the plane — " +
                                "refused, because \"which side\" then has no answer"
                        }
                    }
                }
            }
        }
        return null
    }

    /** Whether the polyline [pts] meets itself; [closed] adds the edge from the last point back to the first. */
    private fun polylineDefect(
        pts: List<Vec2>,
        closed: Boolean,
    ): String? {
        val edges = pts.zipWithNext()
        val n = edges.size
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val neighbours = j == i + 1 || (closed && i == 0 && j == n - 1)
                val meets =
                    if (neighbours) {
                        overlapping(edges[i], edges[j])
                    } else {
                        segmentsMeet(edges[i].first, edges[i].second, edges[j].first, edges[j].second)
                    }
                if (meets) {
                    return "this chain meets itself, so it does not separate the plane into two sides — refused, " +
                        "because \"which side\" then has no answer"
                }
            }
        }
        return null
    }

    /**
     * Two consecutive edges sharing more than the corner between them — the chain doubling straight back
     * over itself, which is a self-intersection however short the overlap is.
     */
    private fun overlapping(
        a: Pair<Vec2, Vec2>,
        b: Pair<Vec2, Vec2>,
    ): Boolean =
        when {
            (a.second - b.first).length() <= EPS -> onSegment(b.second, a.first, a.second) || onSegment(a.first, b.first, b.second)
            (b.second - a.first).length() <= EPS -> onSegment(a.second, b.first, b.second) || onSegment(b.first, a.first, a.second)
            else -> segmentsMeet(a.first, a.second, b.first, b.second)
        }

    // ---- the derived bound, and the two halves it closes ----

    /**
     * The two **tool solids** that split [target] by [chain] drawn on [plane]: the left half first, the
     * right half second (the chain's own direction of travel, see [Chain]).
     *
     * This is the whole of *unbounded in the statement, bounded in the implementation*. The chain's rays are
     * clipped where they leave a box derived from the target's own extent **plus a margin** ([margin]), the
     * clipped chain is closed along that box — strictly outside the target, which is what the margin buys —
     * and what comes back is an ordinary prism that the ordinary boolean engine can take as an ordinary
     * operand. Nothing about the bound is stored, so a target that grows is bounded larger next pass.
     *
     * Both halves are built, always, because the operator **is** split: a cut is split keeping one side, and
     * the discarded half is what answers "did this cut do anything at all" exactly rather than by comparing
     * volumes against a tolerance.
     */
    fun tools(
        chain: Chain,
        plane: Plane3,
        target: Mesh3,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Pair<Pair<Solid3, Solid3>?, String?> {
        if (target.vertices.isEmpty()) return null to "the solid to cut has no geometry to bound the cutting tool with"
        var lo = plane.toLocal(target.vertices[0])
        var hi = lo
        var nLo = plane.distanceTo(target.vertices[0])
        var nHi = nLo
        for (p in target.vertices) {
            val q = plane.toLocal(p)
            lo = Vec2(min(lo.x, q.x), min(lo.y, q.y))
            hi = Vec2(max(hi.x, q.x), max(hi.y, q.y))
            val d = plane.distanceTo(p)
            nLo = min(nLo, d)
            nHi = max(nHi, d)
        }
        // the chain's own finite part is inside the box too, so its corners are never clipped and each ray
        // leaves the box exactly once — which is what makes the closure below a single boundary walk
        for (e in chain.pieces) {
            val (a, b) = GeomMath.bounds(e)
            lo = Vec2(min(lo.x, a.x), min(lo.y, a.y))
            hi = Vec2(max(hi.x, b.x), max(hi.y, b.y))
        }
        val m = margin(max(hi.x - lo.x, max(hi.y - lo.y, nHi - nLo)))
        val boxLo = lo - Vec2(m, m)
        val boxHi = hi + Vec2(m, m)

        val (sides, why) = halves(chain, boxLo, boxHi, tolMm)
        if (sides == null) return null to why
        val base = Plane3(plane.origin + plane.normal.normalized() * (nLo - m), plane.u, plane.v)
        val depth = (nHi - nLo) + 2.0 * m
        val (left, whyL) = Geom3.extrude(Sketch3(base, sides.first), depth, tolMm)
        if (left == null) return null to (whyL ?: "cannot build the cutting tool")
        val (right, whyR) = Geom3.extrude(Sketch3(base, sides.second), depth, tolMm)
        if (right == null) return null to (whyR ?: "cannot build the cutting tool")
        return (left to right) to null
    }

    // ---- the swept cut: the directrix as a general Path3, and the mode (OP-22's extension, step 2) ----
    // Step 1's cut extrudes the chain straight through the target along its space's normal. That straight
    // line **is** a directrix — the degenerate one — so what follows is not a second operator but the same
    // one with its second operand allowed to curve: the chain rides a moving frame along a `Path3`
    // ([Frames3], OP-26's step 2, reused wholesale), and *how* it rides is the one discrete mode
    // ([CarryMode]). Two things the straight case hid have to be answered here, and both are answered the
    // way step 1 answered the chain's rays — **unbounded in the statement, bounded in the implementation**:
    // the directrix's own ends run on, and the profile's reach becomes the target's own extent.

    /**
     * How many times the effective reach and the stations that matter are re-derived from one another.
     *
     * They define each other — which stations can reach the target depends on how far the section reaches,
     * and how far it reaches depends on which stations had to be covered — so the pair is taken to a fixed
     * point from below, starting at the target's distance from the run. It is reached in one step for any
     * run that passes the target once (the commonest case by far) and in two for one that comes back
     * alongside it; a run that keeps needing more is a run that has folded, which the criterion below then
     * refuses in its own words rather than this loop deciding anything.
     */
    private const val REACH_PASSES = 4

    /**
     * The two **tool solids** that split [target] by [chain] drawn on [plane] and carried along [directrix]
     * in the [mode] stated: the left half first, the right half second (the chain's own direction of travel,
     * see [Chain]).
     *
     * **The degenerate directrix is the straight case, and it takes the straight case's code.** A directrix
     * running along [plane]'s own normal is the line step 1 already extrudes along, so it is dispatched
     * *by predicate, up front* to [tools] — which keeps OP-22's exact slab algebra reachable for it (a swept
     * mesh could only ever be a general boolean) and makes "the straight case is unchanged" a fact about the
     * code rather than a tolerance in a test. It is also why the two modes coincide there: they are the same
     * call.
     *
     * **The directrix is unbounded too, and that is one concept rather than two.** A *finite* directrix would
     * reintroduce "extrude far enough along it" — the very guess this operator exists to remove — so the run
     * **continues out of each end along its end tangent**, exactly as a chain continues out of each end along
     * its first and last span. Nothing states how far: the ends are pushed out until the section they carry
     * can no longer touch the target, which is derived from the target every pass and stored nowhere. That a
     * straight extension needs exactly **one** station is not a shortcut but the sweep's own rule — a chord
     * of a line *is* the line — and it is why lengthening the drawn route changes nothing at all: the
     * section's coordinates of a fixed point do not vary along a straight run.
     *
     * **The other end of the same statement**: the run is *clipped* to the stations whose sections can reach
     * the target, so what is built is the piece of an unbounded surface that matters, and a run that folds
     * through itself well clear of the solid is no more a defect than a chain's ray that leaves the box.
     *
     * Everything here is a function of values inside one call (OP-21): nothing about the graph's shape
     * depends on how big the target is, and a target that grows is bounded larger next pass.
     */
    fun sweptTools(
        chain: Chain,
        plane: Plane3,
        directrix: Path3,
        mode: CarryMode,
        target: Mesh3,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Pair<Pair<Solid3, Solid3>?, String?> {
        if (target.vertices.isEmpty()) return null to "the solid to cut has no geometry to bound the cutting tool with"
        if (directrix.isEmpty) return null to "this route has no pieces, so there is nothing to carry the cut along"
        if (straightAlong(directrix, plane.normal)) return tools(chain, plane, target, tolMm)

        val (lo3, hi3) = Geom3.bounds(target) ?: return null to "the solid to cut has no geometry to bound the cutting tool with"
        val corners = boxCorners(lo3, hi3)
        val centre = (lo3 + hi3) * 0.5
        val radius = (hi3 - lo3).length() * 0.5
        val chainBox = bounds(chain)
        val m =
            margin(
                maxOf(
                    hi3.x - lo3.x,
                    hi3.y - lo3.y,
                    hi3.z - lo3.z,
                    chainBox?.let { max(it.second.x - it.first.x, it.second.y - it.first.y) } ?: 0.0,
                ),
            )

        // **The section's own axes at the start are the chain's own space**, which is the frames decision
        // this operator was given: the chain's 2D coordinates and the frame's start reference are one
        // statement. Handing the space's *u* to the transport makes the section stand exactly as drawn when
        // the run leaves along the space's normal — the everyday case, and the one where a naive `up = the
        // normal` would be degenerate and fall back on a world axis (see [Frames3.startReference]). Where the
        // run leaves along *u* instead, *v* is perpendicular to it by construction, so the drawing still
        // answers and no world axis is ever consulted.
        val t0 = Curves3.tangentAt(directrix.elements.first(), 0.0) ?: return null to "this route has no direction at its start"
        val up = if ((plane.u - t0 * plane.u.dot(t0)).length() > 1e-6) plane.u else plane.v
        val carry = Carry(mode, plane, if (t0.dot(plane.normal.normalized()) < 0.0) -1.0 else 1.0)

        val (frame, whyFrame) = Frames3.along(directrix, up, tolMm = tolMm)
        if (frame == null) return null to (whyFrame ?: "cannot build a moving frame along this route")
        // A closed route is cut open where it stands **furthest from the solid** — the one place a cut can
        // have its ends without them mattering — after which it is the open case exactly, caps and all. That
        // is what makes a revolved cut fall out of this operator instead of needing one of its own.
        val stations = if (directrix.closed) openedAwayFrom(frame.stations, lo3, hi3) else frame.stations
        if (stations.size < 2) return null to "this route has no length, so there is nothing to carry the cut along"
        val startRay = Ray3(stations.first().at, -stations.first().tangent)
        val endRay = Ray3(stations.last().at, stations.last().tangent)
        val rays = if (directrix.closed) null else (startRay to endRay)

        // the seed: how far the far edge of the solid stands from the run, rays included
        var relevance = corners.maxOf { runDistance(it, stations, rays) } + m
        var span = 0..0
        var boxLo = Vec2(0.0, 0.0)
        var boxHi = Vec2(0.0, 0.0)
        var reach = 0.0
        for (pass in 0 until REACH_PASSES) {
            span = relevantSpan(stations, rays, lo3, hi3, relevance)
            val box = sectionBox(stations, span, corners, carry, chainBox, m)
            boxLo = box.first
            boxHi = box.second
            reach = boxReach(boxLo, boxHi)
            if (reach <= relevance) break
            relevance = reach
        }

        // …and now the ends: walk out to the first station the section can no longer reach the solid from,
        // and where the run itself ends, run it on until that is true. The caps then stand strictly outside
        // the target, which is the same guarantee the chain's own closure gets from the margin.
        var k0 = span.first
        var k1 = span.last
        while (k0 > 0 && boxDistance(stations[k0].at, lo3, hi3) <= reach + m) k0--
        while (k1 < stations.size - 1 && boxDistance(stations[k1].at, lo3, hi3) <= reach + m) k1++
        if (k1 == k0) {
            if (k1 < stations.size - 1) {
                k1++
            } else {
                k0--
            }
        }
        if (k0 < 0) return null to "this route is a single station, so there is nothing to carry the cut along"
        val run = ArrayList(stations.subList(k0, k1 + 1))
        val nearStart = boxDistance(run.first().at, lo3, hi3) <= reach + m
        val nearEnd = boxDistance(run.last().at, lo3, hi3) <= reach + m
        if (directrix.closed && (nearStart || nearEnd)) {
            return null to
                "this closed route never leaves the solid's reach, so the cut has no clear end — state the " +
                "route as an open run through the body, or move it clear of the solid where it should stop"
        }
        if (nearStart) run.add(0, runOn(run.first(), backwards = true, d = reach + 2.0 * m + (run.first().at - centre).length() + radius))
        if (nearEnd) run.add(runOn(run.last(), backwards = false, d = reach + 2.0 * m + (run.last().at - centre).length() + radius))

        // **The embedding criterion, in its bounded-reach form** ([Embedding]): the same double-normal
        // machinery the sweep uses, with the profile's own (infinite) reach replaced by the derived one and
        // the run replaced by the piece of it that matters. And what it decides is not only watertightness:
        // a surface that does not meet itself over the solid's extent is exactly a surface for which *"which
        // side"* has an answer there, so the refusal and this operator's semantics are one statement.
        val length = run.last().s - run.first().s
        val report =
            Embedding.check(
                MovingFrame(run, length, closed = false, seam = 0.0, startRef = run.first().ref),
                reach,
                "the cut's reach across this solid (${Frames3.mm(reach)} mm)",
                subject = "the cutting surface",
                cure = "open the run out, or bring the cut nearer to it",
            )
        report.defect?.let { return null to it }
        foldDefect(run, carry, boxLo, boxHi)?.let { return null to it }

        val (sides, whySides) = halves(chain, boxLo, boxHi, tolMm)
        if (sides == null) return null to whySides
        // Which way the shell is wound, asked once for the whole tool: the rotating carry always advances
        // along its own tangent, so only reading the section mirrored can turn it round, while a
        // translational carry keeps the section as drawn and may instead travel against its normal.
        val reversed =
            when (mode) {
                CarryMode.ROTATING -> carry.handed < 0.0
                CarryMode.TRANSLATIONAL -> (run.last().at - run.first().at).dot(plane.normal.normalized()) < 0.0
            }
        val (left, whyL) = sweptShell(sides.first, run, carry, reversed, up, tolMm)
        if (left == null) return null to (whyL ?: "cannot build the cutting tool")
        val (right, whyR) = sweptShell(sides.second, run, carry, reversed, up, tolMm)
        if (right == null) return null to (whyR ?: "cannot build the cutting tool")
        return (left to right) to null
    }

    /**
     * Whether [path] is the **degenerate directrix** — the straight line along [n], which is what a cut with
     * no directrix at all already is.
     *
     * A predicate over the pieces rather than a measurement of the mesh they make: every piece is straight
     * and every one runs along the given direction, so the answer is exact and a hair of curvature makes it
     * false rather than nearly true.
     */
    private fun straightAlong(
        path: Path3,
        n: Vec3,
    ): Boolean {
        if (path.closed) return false
        val axis = n.normalized()
        for (el in path.elements) {
            if (el !is Curve3Element.Seg3) return false
            val d = el.end - el.start
            if (d.length() <= Vec3.EPS) return false
            if (abs(abs(d.normalized().dot(axis)) - 1.0) > 1e-9) return false
        }
        return path.elements.isNotEmpty()
    }

    /** The eight corners of the axis-aligned box `[lo, hi]` — what the target is measured by. */
    private fun boxCorners(
        lo: Vec3,
        hi: Vec3,
    ): List<Vec3> =
        listOf(
            Vec3(lo.x, lo.y, lo.z),
            Vec3(hi.x, lo.y, lo.z),
            Vec3(lo.x, hi.y, lo.z),
            Vec3(hi.x, hi.y, lo.z),
            Vec3(lo.x, lo.y, hi.z),
            Vec3(hi.x, lo.y, hi.z),
            Vec3(lo.x, hi.y, hi.z),
            Vec3(hi.x, hi.y, hi.z),
        )

    /** How far [p] stands from the box `[lo, hi]` — zero inside it. */
    private fun boxDistance(
        p: Vec3,
        lo: Vec3,
        hi: Vec3,
    ): Double {
        val dx = max(0.0, max(lo.x - p.x, p.x - hi.x))
        val dy = max(0.0, max(lo.y - p.y, p.y - hi.y))
        val dz = max(0.0, max(lo.z - p.z, p.z - hi.z))
        return Vec3(dx, dy, dz).length()
    }

    /**
     * How far the box `[lo, hi]` stands from the ray [r] — a ternary search, and it is exact rather than a
     * sampling: the distance from a point to a convex set is a convex function, so its composition with the
     * ray's own affine parameter has exactly one minimum and no local one to fall into.
     */
    private fun rayBoxDistance(
        r: Ray3,
        lo: Vec3,
        hi: Vec3,
    ): Double {
        val centre = (lo + hi) * 0.5
        var a = 0.0
        var b = max(0.0, (centre - r.origin).dot(r.dir)) + (hi - lo).length()
        repeat(80) {
            val x = a + (b - a) / 3.0
            val y = b - (b - a) / 3.0
            if (boxDistance(r.at(x), lo, hi) <= boxDistance(r.at(y), lo, hi)) b = y else a = x
        }
        return boxDistance(r.at((a + b) / 2.0), lo, hi)
    }

    /** How far [p] stands from the run — its stations as a polyline, plus the two rays that continue it. */
    private fun runDistance(
        p: Vec3,
        stations: List<Frame3>,
        rays: Pair<Ray3, Ray3>?,
    ): Double {
        var best = Double.MAX_VALUE
        for (k in 0 until stations.size - 1) {
            val a = stations[k].at
            val d = stations[k + 1].at - a
            val len2 = d.dot(d)
            val t = if (len2 <= Vec3.EPS) 0.0 else min(1.0, max(0.0, (p - a).dot(d) / len2))
            best = min(best, (p - (a + d * t)).length())
        }
        if (rays != null) {
            for (r in listOf(rays.first, rays.second)) {
                val t = max(0.0, (p - r.origin).dot(r.dir))
                best = min(best, (p - r.at(t)).length())
            }
        }
        return best
    }

    /**
     * The stations whose sections can reach within [near] of the box `[lo, hi]` — first and last, as a range.
     *
     * A **range** rather than a set, because what is built is one connected piece of the run: everything
     * between the first station that matters and the last one does, whether or not it comes within reach in
     * between. The run's own two ends are measured as **rays**, since they continue, which is what lets a
     * route drawn short of the solid still cut it.
     */
    private fun relevantSpan(
        stations: List<Frame3>,
        rays: Pair<Ray3, Ray3>?,
        lo: Vec3,
        hi: Vec3,
        near: Double,
    ): IntRange {
        var first = -1
        var last = -1
        for (k in stations.indices) {
            val d =
                when {
                    rays != null && k == 0 -> rayBoxDistance(rays.first, lo, hi)
                    rays != null && k == stations.size - 1 -> rayBoxDistance(rays.second, lo, hi)
                    else -> boxDistance(stations[k].at, lo, hi)
                }
            if (d <= near) {
                if (first < 0) first = k
                last = k
            }
        }
        // Nothing within reach at all: the cut misses this solid, and that is a property of the *result* —
        // the boolean's own "this cut leaves the solid untouched" says it, in the words the user can act on.
        if (first < 0) {
            val k = stations.indices.minByOrNull { boxDistance(stations[it].at, lo, hi) } ?: 0
            return k..k
        }
        return first..last
    }

    /**
     * The box the chain is clipped to, in the chain's **own 2D coordinates**: the target as it stands in
     * every station of [span]'s frame, the chain's own finite extent, and a margin round both.
     *
     * The projection into a station's frame is **linear**, so taking the target's eight box [corners] bounds
     * every point inside it exactly — no sampling, and no vertex count to depend on. The margin does here
     * what it does in step 1: it puts every face of the closure strictly outside the target, so the
     * coplanar-face degeneracy is unreachable by construction (see [MARGIN_FRACTION]).
     */
    private fun sectionBox(
        stations: List<Frame3>,
        span: IntRange,
        corners: List<Vec3>,
        carry: Carry,
        chainBox: Pair<Vec2, Vec2>?,
        m: Double,
    ): Pair<Vec2, Vec2> {
        var lo: Vec2? = null
        var hi: Vec2? = null
        for (k in span) {
            for (c in corners) {
                val q = carry.sectionOf(stations[k], c)
                lo = if (lo == null) q else Vec2(min(lo.x, q.x), min(lo.y, q.y))
                hi = if (hi == null) q else Vec2(max(hi.x, q.x), max(hi.y, q.y))
            }
        }
        if (chainBox != null) {
            val a = chainBox.first
            val b = chainBox.second
            lo = if (lo == null) a else Vec2(min(lo.x, a.x), min(lo.y, a.y))
            hi = if (hi == null) b else Vec2(max(hi.x, b.x), max(hi.y, b.y))
        }
        val l = lo ?: Vec2(0.0, 0.0)
        val h = hi ?: Vec2(0.0, 0.0)
        return (l - Vec2(m, m)) to (h + Vec2(m, m))
    }

    /**
     * The **effective reach**: how far the clipped section stands from the frame's origin, which sits on the
     * run — *the distance to the far edge of the target's extent*, and the number the embedding criterion is
     * asked about instead of a profile's own (infinite) one.
     */
    private fun boxReach(
        lo: Vec2,
        hi: Vec2,
    ): Double =
        listOf(lo, Vec2(hi.x, lo.y), hi, Vec2(lo.x, hi.y)).maxOf { it.length() }

    /**
     * **How the chain's own 2D coordinates are read at a station** — the mode, and the one sign that keeps
     * the section standing as it was drawn.
     *
     * [place] is the whole of [CarryMode] in two lines: rotating is the moving frame's own placement, mitre
     * and all ([Frame3.place]); translational keeps the chain's space's axes and moves only the origin, so
     * every section is parallel to the space it was drawn in and there is no mitre to make — parallel
     * sections do not trim each other.
     *
     * **[handed] is why a route travelled the other way cuts the same body.** The frame's second axis is
     * `tangent × ref`, which *is* the space's own **v** while the run leaves along the space's normal and is
     * its negative while the run leaves against it — so a route drawn pointing the other way through the same
     * plane would sweep the mirror image of the chain, and the half the user clicked would come out on the
     * other side. Reading the section's y through this sign undoes exactly that, and nothing else: with it, a
     * straight directrix along the normal and one along its negative are the same cut, which is what "the
     * straight case is the degenerate directrix" has to mean. The tie (a run leaving *within* the chain's
     * plane) goes to `+1`, deterministically, because a run that grazes the section's own plane has no
     * side to prefer and every reload must pick the same one.
     */
    private class Carry(
        val mode: CarryMode,
        val plane: Plane3,
        val handed: Double,
    ) {
        /** Where the world point [p] stands in a station's section coordinates — [place]'s inverse in (x, y). */
        fun sectionOf(
            st: Frame3,
            p: Vec3,
        ): Vec2 {
            val q = p - st.at
            return when (mode) {
                CarryMode.ROTATING -> Vec2(q.dot(st.ref), handed * q.dot(st.bi))
                CarryMode.TRANSLATIONAL -> Vec2(q.dot(plane.u), q.dot(plane.v))
            }
        }

        /** Where the section point [p] stands in the world at [st]. */
        fun place(
            st: Frame3,
            p: Vec2,
        ): Vec3 =
            when (mode) {
                CarryMode.ROTATING -> st.place(Vec2(p.x, handed * p.y))
                CarryMode.TRANSLATIONAL -> st.at + plane.u * p.x + plane.v * p.y
            }
    }

    /**
     * A station [d] mm out along the run beyond [st] — how the directrix's ends **run on**.
     *
     * One station is the whole extension, and that is the sweep's own rule rather than an economy: the
     * continuation is straight, a chord of a line *is* the line, and the frame carries through a straight run
     * unchanged. Its cap plane is normal to the run (no mitre out there, since nothing turns) and it carries
     * no curvature, because a straight run has none.
     */
    private fun runOn(
        st: Frame3,
        backwards: Boolean,
        d: Double,
    ): Frame3 {
        val dir = if (backwards) -st.tangent else st.tangent
        return Frame3(st.s + (if (backwards) -d else d), st.at + dir * d, st.tangent, st.ref, st.tangent, 0.0)
    }

    /**
     * A closed run cut open at the station **furthest from the box** `[lo, hi]`, its arc lengths restated
     * from there.
     *
     * A cut needs its tool's ends somewhere; the place they cost nothing is the far side of the loop, where
     * the section cannot reach the solid anyway — and once it is open it is the open case exactly, so a
     * closed directrix needs no branch anywhere below this line.
     */
    private fun openedAwayFrom(
        stations: List<Frame3>,
        lo: Vec3,
        hi: Vec3,
    ): List<Frame3> {
        val n = stations.size
        if (n < 2) return stations
        val far = stations.indices.maxByOrNull { boxDistance(stations[it].at, lo, hi) } ?: 0
        val out = ArrayList<Frame3>(n)
        var s = 0.0
        for (i in 0 until n) {
            val st = stations[(far + i) % n]
            if (i > 0) s += (st.at - out.last().at).length()
            out.add(Frame3(s, st.at, st.tangent, st.ref, st.mitre, st.curvature))
        }
        return out
    }

    /**
     * Why the tool folds back on itself between two stations — or null when every band advances.
     *
     * The exact condition, and one statement for both modes: **the section at the next station must stand
     * strictly beyond the plane the section at this one lies in**. In [CarryMode.ROTATING] that plane is the
     * mitre plane, so this is the corner condition — a mitre that eats more of a span than the span has to
     * give trims the band past itself, which is a fold no proximity test can see. In
     * [CarryMode.TRANSLATIONAL] the section planes are all parallel to the chain's own space, so the same
     * sentence reads as *the run must keep advancing through that space* — and while it does, no two sections
     * can meet at all, since they lie in distinct parallel planes.
     *
     * Checked at the **four corners of the clipped box** and nowhere else, which is exact rather than a
     * sample: both the mitre push and the advance are affine in the section's (x, y), so their extremes are
     * at the corners of the box every profile point lies in.
     */
    private fun foldDefect(
        run: List<Frame3>,
        carry: Carry,
        lo: Vec2,
        hi: Vec2,
    ): String? {
        val probes = listOf(lo, Vec2(hi.x, lo.y), hi, Vec2(lo.x, hi.y))
        val n = carry.plane.normal.normalized()
        val advance = (run.last().at - run.first().at).dot(n)
        for (k in 0 until run.size - 1) {
            val a = run[k]
            val b = run[k + 1]
            val ahead =
                when (carry.mode) {
                    CarryMode.ROTATING -> a.mitre
                    CarryMode.TRANSLATIONAL -> if (advance < 0.0) -n else n
                }
            for (p in probes) {
                if ((carry.place(b, p) - carry.place(a, p)).dot(ahead) > Geom3.WELD_TOL) continue
                return when (carry.mode) {
                    CarryMode.ROTATING ->
                        "the cut folds back on itself ${Frames3.mm(a.s)} mm along the route — the corner there " +
                            "turns more sharply than the cut reaches across this solid " +
                            "(${Frames3.mm(boxReach(lo, hi))} mm), so the two sides of the join trim past each " +
                            "other; open the corner out, or bring the cut nearer to the run"
                    CarryMode.TRANSLATIONAL ->
                        "the route stops advancing through the chain's own plane ${Frames3.mm(a.s)} mm along it, " +
                            "so a section carried without turning would fold back through the one before it — " +
                            "carry it rotating instead, or keep the route going the way it started"
                }
            }
        }
        return null
    }

    /**
     * One half of the split, as a solid: [regions] carried along [run] in the [mode] stated.
     *
     * The mesh is [Geom3.sweptShells]' — the sweep's own bands and caps, so the tool is watertight for the
     * reason a swept solid is and not for a reason of its own. Its feature says **`Sweep`**, which is the
     * honest answer to the only question anything ever asks of a tool solid's feature: a swept body shares an
     * axis with nothing (OP-26), so the boolean below it takes the general engine (OP-9), exactly as a cut
     * with a curved tool must. Where a half comes back as more than one region the feature names the first;
     * the tool is discarded the moment the boolean has run.
     */
    private fun sweptShell(
        regions: List<Region>,
        run: List<Frame3>,
        carry: Carry,
        reversed: Boolean,
        up: Vec3,
        tolMm: Double,
    ): Pair<Solid3?, String?> {
        if (regions.isEmpty()) return null to "this chain cuts nothing off the solid's extent"
        val tess = ArrayList<Geom3.TessRegion>(regions.size)
        for (r in regions) {
            val (t, why) = Geom3.tessellateRegion(r, tolMm)
            if (t == null) return null to (why ?: "cannot tessellate the cutting chain's own side")
            tess.add(t)
        }
        val (shell, whyMesh) =
            Geom3.sweptShells(tess, run, closed = false, reversed = reversed) { st, p -> carry.place(st, p) }
        if (shell == null) return null to whyMesh
        val spine = Path3(run.zipWithNext().map { (a, b) -> Curve3Element.Seg3(a.at, b.at) })
        return Solid3.derived(
            Feature3.Sweep(spine, SweepProfile.Section(regions.first()), up, 0.0, 0.0, emptyList(), carry.mode),
            shell,
        ) to null
    }

    /**
     * The two areas the chain cuts the box `[lo, hi]` into: **left of travel first**, right second.
     *
     * For an [Chain.Open] chain the boundary of the left half is the chain itself, from where its first ray
     * leaves the box to where its last one does, followed by the box's own boundary walked
     * **counter-clockwise** back to the start — which puts the interior on the left of the chain by the same
     * rule that makes a counter-clockwise ring an outer boundary (OP-14). For a [Chain.Closed] one the left
     * half is simply the area, and the right is the box minus it, taken by the 2D kernel so the two are
     * exact complements.
     */
    fun halves(
        chain: Chain,
        lo: Vec2,
        hi: Vec2,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Pair<Pair<List<Region>, List<Region>>?, String?> {
        val box = listOf(lo, Vec2(hi.x, lo.y), hi, Vec2(lo.x, hi.y))
        return when (chain) {
            is Chain.Closed -> {
                val (inside, why) = RegionBool.ringsOf(listOf(chain.area), tolMm)
                if (inside == null) return null to why
                val (outside, why2) = RegionBool.combine(listOf(box), inside, BoolOp.SUBTRACT)
                if (outside == null) return null to why2
                val (a, whyA) = RegionBool.regionsOf(inside)
                if (a == null) return null to whyA
                val (b, whyB) = RegionBool.regionsOf(outside)
                if (b == null) return null to whyB
                (a to b) to null
            }
            is Chain.Open -> {
                val run = polyline(chain.pieces, tolMm) ?: return null to "the pieces of this chain do not join up end to end"
                val entry = exitOf(chain.start, lo, hi) ?: return null to "this chain's first ray does not leave the solid's extent"
                val leave = exitOf(chain.end, lo, hi) ?: return null to "this chain's last ray does not leave the solid's extent"
                val sIn = perimeterAt(entry, lo, hi)
                val sOut = perimeterAt(leave, lo, hi)
                if (abs(((sOut - sIn) + 4.0) % 4.0) <= 1e-9) {
                    return null to "this chain's two ends leave the solid's extent at the same place, so it cuts nothing off"
                }
                val cut = listOf(entry) + run + listOf(leave)
                val left = cut + walk(sOut, sIn, lo, hi)
                val right = cut.reversed() + walk(sIn, sOut, lo, hi)
                (listOf(regionOf(left)) to listOf(regionOf(right))) to null
            }
        }
    }

    /**
     * Which side of [chain] the point [at] lies on: `+1` left of its direction of travel, `-1` right.
     *
     * What a click means when it says *"keep this side"* — scored **once**, from the gesture, and then
     * persisted as a sign (OP-1), never re-scored on replay.
     */
    fun sideAt(
        chain: Chain,
        at: Vec2,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Int =
        when (chain) {
            is Chain.Closed -> {
                val rings = RegionBool.ringsOf(listOf(chain.area), tolMm).first
                if (rings != null && RegionBool.contains(rings, at)) 1 else -1
            }
            is Chain.Open -> {
                val run = polyline(chain.pieces, tolMm) ?: listOf(chain.start.origin, chain.end.origin)
                var best = Double.MAX_VALUE
                var side = 1
                for ((a, b) in run.zipWithNext()) {
                    val d = distanceToSegment(at, a, b)
                    if (d < best) {
                        best = d
                        side = if (leftOf(at, a, b) >= 0.0) 1 else -1
                    }
                }
                // A ray points *away* from the run, so travelling along the chain means arriving **down**
                // the first ray and leaving **up** the last one — which is why the two are given different
                // directions of travel here rather than one rule for both.
                for ((r, travel) in listOf(chain.start to -chain.start.dir, chain.end to chain.end.dir)) {
                    val t = (at - r.origin).dot(r.dir)
                    if (t <= 0.0) continue
                    val d = (at - (r.origin + r.dir * t)).length()
                    if (d < best) {
                        best = d
                        side = if (leftOf(at, r.origin, r.origin + travel) >= 0.0) 1 else -1
                    }
                }
                side
            }
        }

    /** The finite extent of a chain's pieces, or null when it has none. */
    fun bounds(chain: Chain): Pair<Vec2, Vec2>? {
        if (chain.pieces.isEmpty()) return null
        var lo = GeomMath.bounds(chain.pieces[0]).first
        var hi = GeomMath.bounds(chain.pieces[0]).second
        for (e in chain.pieces) {
            val (a, b) = GeomMath.bounds(e)
            lo = Vec2(min(lo.x, a.x), min(lo.y, a.y))
            hi = Vec2(max(hi.x, b.x), max(hi.y, b.y))
        }
        return lo to hi
    }

    /** A chain's pieces as one polyline, or null when they do not join up. */
    fun polyline(
        pieces: List<ProfileElement>,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): List<Vec2>? {
        if (pieces.isEmpty()) return null
        val out = ArrayList<Vec2>()
        for ((i, e) in pieces.withIndex()) {
            val part = GeomMath.tessellatePiece(e, tolMm)
            if (i == 0) {
                out.addAll(part)
            } else {
                if ((part.first() - out.last()).length() > EPS) return null
                out.addAll(part.drop(1))
            }
        }
        return out
    }

    // ---- the small exact predicates the above is written in (all in millimetres) ----

    private fun regionOf(ring: List<Vec2>): Region = Region(GeomMath.orient(RegionBool.loopOf(ring), ccw = true), emptyList())

    /** Signed distance of [p] from the directed line `a → b`: positive to its **left**. */
    private fun leftOf(
        p: Vec2,
        a: Vec2,
        b: Vec2,
    ): Double {
        val d = b - a
        val len = d.length()
        if (len <= EPS) return 0.0
        return d.cross(p - a) / len
    }

    private fun distanceToSegment(
        p: Vec2,
        a: Vec2,
        b: Vec2,
    ): Double {
        val d = b - a
        val len2 = d.dot(d)
        if (len2 <= EPS * EPS) return (p - a).length()
        val t = min(1.0, max(0.0, (p - a).dot(d) / len2))
        return (p - (a + d * t)).length()
    }

    private fun onSegment(
        p: Vec2,
        a: Vec2,
        b: Vec2,
    ): Boolean = distanceToSegment(p, a, b) <= EPS

    /** Whether the closed segments `a1a2` and `b1b2` share any point at all. */
    private fun segmentsMeet(
        a1: Vec2,
        a2: Vec2,
        b1: Vec2,
        b2: Vec2,
    ): Boolean {
        val d1 = leftOf(a1, b1, b2)
        val d2 = leftOf(a2, b1, b2)
        val d3 = leftOf(b1, a1, a2)
        val d4 = leftOf(b2, a1, a2)
        val straddleA = (d1 > EPS && d2 < -EPS) || (d1 < -EPS && d2 > EPS)
        val straddleB = (d3 > EPS && d4 < -EPS) || (d3 < -EPS && d4 > EPS)
        if (straddleA && straddleB) return true
        return onSegment(a1, b1, b2) || onSegment(a2, b1, b2) || onSegment(b1, a1, a2) || onSegment(b2, a1, a2)
    }

    /** Whether the ray [r] meets the closed segment `a b` — the infinite half, not a long segment. */
    private fun rayMeetsSegment(
        r: Ray,
        a: Vec2,
        b: Vec2,
    ): Boolean {
        val e = b - a
        val den = r.dir.cross(e)
        if (abs(den) > EPS) {
            val t = (a - r.origin).cross(e) / den
            val u = (a - r.origin).cross(r.dir) / den
            return t >= -EPS && u >= -EPS && u <= 1.0 + EPS
        }
        // parallel: only a collinear overlap counts, and then one of the segment's ends is on the ray
        return onRay(r, a) || onRay(r, b)
    }

    private fun onRay(
        r: Ray,
        p: Vec2,
    ): Boolean {
        val t = (p - r.origin).dot(r.dir)
        if (t < -EPS) return false
        return (p - (r.origin + r.dir * max(0.0, t))).length() <= EPS
    }

    private fun raysMeet(
        a: Ray,
        b: Ray,
    ): Boolean {
        val den = a.dir.cross(b.dir)
        if (abs(den) > EPS) {
            val t = (b.origin - a.origin).cross(b.dir) / den
            val u = (b.origin - a.origin).cross(a.dir) / den
            return t >= -EPS && u >= -EPS
        }
        return onRay(a, b.origin) || onRay(b, a.origin)
    }

    /** Where the ray [r], whose origin is inside the box, leaves it. */
    private fun exitOf(
        r: Ray,
        lo: Vec2,
        hi: Vec2,
    ): Vec2? {
        var t = Double.MAX_VALUE
        if (r.dir.x > EPS) t = min(t, (hi.x - r.origin.x) / r.dir.x)
        if (r.dir.x < -EPS) t = min(t, (lo.x - r.origin.x) / r.dir.x)
        if (r.dir.y > EPS) t = min(t, (hi.y - r.origin.y) / r.dir.y)
        if (r.dir.y < -EPS) t = min(t, (lo.y - r.origin.y) / r.dir.y)
        if (t == Double.MAX_VALUE || t <= 0.0) return null
        return r.origin + r.dir * t
    }

    /**
     * Where on the box's perimeter [p] is, as a number in `[0, 4)`: 0 at the low corner, rising
     * counter-clockwise with one unit per side.
     */
    private fun perimeterAt(
        p: Vec2,
        lo: Vec2,
        hi: Vec2,
    ): Double {
        val w = hi.x - lo.x
        val h = hi.y - lo.y
        return when {
            abs(p.y - lo.y) <= EPS -> (p.x - lo.x) / w
            abs(p.x - hi.x) <= EPS -> 1.0 + (p.y - lo.y) / h
            abs(p.y - hi.y) <= EPS -> 2.0 + (hi.x - p.x) / w
            else -> 3.0 + (hi.y - p.y) / h
        }
    }

    /** The box corners strictly between the perimeter positions [from] and [to], counter-clockwise. */
    private fun walk(
        from: Double,
        to: Double,
        lo: Vec2,
        hi: Vec2,
    ): List<Vec2> {
        val corners = listOf(lo, Vec2(hi.x, lo.y), hi, Vec2(lo.x, hi.y))
        val span = ((to - from) + 4.0) % 4.0
        val out = ArrayList<Vec2>()
        var s = floor(from) + 1.0
        while (s - from < span - 1e-12) {
            out.add(corners[(s.toInt() % 4 + 4) % 4])
            s += 1.0
        }
        return out
    }
}
