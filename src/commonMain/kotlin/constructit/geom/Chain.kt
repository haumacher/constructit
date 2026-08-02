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
