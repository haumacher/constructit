package constructit.geom

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * One carrier curve of a thick network, with the [side] its own direction gives its material
 * (the OP-21 extension: *a wall is a thickness applied to an arbitrary path*).
 *
 * The side is per curve and not per network, because that is what makes a carrier drawable in whatever
 * order its pieces happen to exist: "left" is the +90° side of *this* curve's own direction, exactly as
 * [Justification] already defines it, and it needs no inside/outside.
 */
data class CarrierCurve(val piece: ProfileElement, val side: Justification)

/**
 * One continuous stretch of one face of a [ThickLeg]: the boundary [pieces], trimmed corner to corner and
 * oriented along the leg, plus the span of the leg's own arc length ([from]..[to]) they cover.
 *
 * A leg has exactly **one** run per face until a *T-attachment* splits it (see `thickNetwork`): an endpoint
 * of another carrier landing in this leg's interior is a vertex of the fat graph, so this leg's faces are
 * mitred against the branch there — and on the branch's side the face has a genuine **gap**, which is the
 * branch's own material. Keeping the runs separate rather than concatenating them is what lets the plan draw
 * that gap instead of bridging it with a seam line across the junction.
 */
class FaceRun internal constructor(
    val pieces: List<ProfileElement>,
    val from: Double,
    val to: Double,
)

/**
 * One leg of a thick carrier, resolved to values: the oriented carrier [piece], its arc [length], the two
 * signed face [offsets] (ascending; + is left of the walk direction) and the **trimmed** offset runs
 * the footprint boundary actually uses, in the leg's own direction.
 *
 * This is the one thing the plan convention, the jambs and the interval features all measure along, and it
 * is why none of them needed a case per curve kind: a *position along a leg* is an arc length whether the
 * leg is a straight ortho run, a concentric arc or a sampled Bézier.
 *
 * [samples] is non-null only for a Bézier, whose offset is a polyline rather than a curve of its own kind —
 * OP-15's **approximated** class, carried in the type rather than hidden.
 */
class ThickLeg internal constructor(
    val piece: ProfileElement,
    val offsets: List<Double>,
    val length: Double,
    /**
     * Each face's boundary runs, trimmed corner to corner and oriented along the leg — index by side, then
     * in leg order. One per side for an ordinary leg, one per **T-split span** for a leg that another
     * carrier ends on (see [FaceRun]).
     */
    val runs: List<List<FaceRun>>,
    private val samples: List<Vec2>?,
    private val cum: List<Double>?,
) {
    /** Whether this leg's offsets are sampled rather than exact (a Bézier carrier — OP-15). */
    val approximated: Boolean get() = samples != null

    /** The carrier point at arc length [d] from this leg's start. */
    fun pointAt(d: Double): Vec2 =
        when (val e = piece) {
            is ProfileElement.Seg -> e.segment.a + (e.segment.b - e.segment.a).normalized() * d
            is ProfileElement.ArcE -> GeomMath.arcPointAt(e.arc, angleAt(e.arc, d))
            else -> sampleAt(d).first
        }

    /** The unit carrier direction at arc length [d]. */
    fun dirAt(d: Double): Vec2 =
        when (val e = piece) {
            is ProfileElement.Seg -> (e.segment.b - e.segment.a).normalized()
            is ProfileElement.ArcE ->
                angleAt(e.arc, d).let { t -> Vec2(cos(t), sin(t)) }.let { if (e.arc.ccw) it.perp() else -it.perp() }
            else -> sampleAt(d).second
        }

    /** Where arc length [d] lands on face [side] — the foot of the perpendicular, i.e. an interval's edge. */
    fun facePoint(
        d: Double,
        side: Int,
    ): Vec2 = offsetPoint(d, offsets[side])

    /** Where arc length [d] lands [off] to the **left** of the carrier — [facePoint]'s general form. */
    fun offsetPoint(
        d: Double,
        off: Double,
    ): Vec2 = pointAt(d) + dirAt(d).perp() * off

    /**
     * The two offsets a 3D **cutter** across this leg spans (the OP-21 extension's 3D half) — the faces,
     * widened by [margin] wherever the leg is *curved*.
     *
     * The rule behind the asymmetry, and it is not a case per curve kind: **a cutter may share a face with
     * the wall only when that face is exact.** On a straight leg the two faces are the same line and the 2D
     * kernel's shared-edge rule (OP-22) resolves them exactly — which is what OP-21 deliberately relied on.
     * On a curved leg they are not: the wall's face and the cutter's face are two *independent
     * tessellations of one arc*, so they are near-coincident rather than coincident, they cross each other
     * once per chord, and the kernel dutifully returns a crescent sliver for each crossing. Slivers are
     * areas, so they become sub-slabs, and a sub-slab too thin to triangulate is a hole in the shell.
     *
     * So on a curved leg the cutter stops pretending and simply **overhangs**: its long faces sit clear of
     * the material on both sides, leaving only the two jamb faces to cut — and those are genuinely
     * transverse. The removed volume is unchanged, because the overhang is outside the wall.
     *
     * The widening is clamped to half the smaller face radius, so an arc barely thicker than its wall
     * cannot have a cutter face turned inside out.
     */
    fun cutterOffsets(margin: Double): List<Double> {
        val arc = (piece as? ProfileElement.ArcE)?.arc
        if (piece is ProfileElement.Seg) return offsets
        val m =
            if (arc == null) {
                margin
            } else {
                val radii = offsets.map { arc.radius + (if (arc.ccw) -it else it) }
                minOf(margin, 0.5 * (radii.minOrNull() ?: margin))
            }
        return listOf(offsets[0] - m, offsets[1] + m)
    }

    /**
     * How far along this leg [p] falls, in mm of arc length from the leg's start.
     *
     * Unclamped on purpose, exactly as `Document.positionAlongLeg` always was: where the cursor *is* and
     * what the model may hold are two questions, and the clamp belongs to the write.
     */
    fun distanceAt(p: Vec2): Double =
        when (val e = piece) {
            is ProfileElement.Seg -> (p - e.segment.a).dot((e.segment.b - e.segment.a).normalized())
            is ProfileElement.ArcE ->
                sweepFrom(atan2(p.y - e.arc.center.y, p.x - e.arc.center.x) - e.arc.startAngle, e.arc.ccw) * e.arc.radius
            else -> nearestOnSamples(p)
        }

    private fun angleAt(
        arc: Arc,
        d: Double,
    ): Double = arc.startAngle + (if (arc.ccw) d / arc.radius else -d / arc.radius)

    /**
     * The carrier point and unit tangent at arc length [d] on a sampled leg.
     *
     * The **arc length → parameter** map is the sampled part (a cumulative polyline lookup); the point and
     * the tangent it hands back are then the curve's own, exactly. So a face point on a Bézier wall is the
     * *exact* offset at a parameter located approximately — which is where the approximation belongs, and
     * why the drawn boundary agrees with it at every sample and only chords between them (OP-15).
     */
    private fun sampleAt(d: Double): Pair<Vec2, Vec2> {
        val pts = samples ?: return Vec2(0.0, 0.0) to Vec2(1.0, 0.0)
        val cs = cum ?: return pts.first() to Vec2(1.0, 0.0)
        var i = 0
        while (i < cs.size - 2 && cs[i + 1] < d) i++
        val span = cs[i + 1] - cs[i]
        val local = if (span < Vec2.EPS) 0.0 else (d - cs[i]) / span
        val u = ((i + local) / (pts.size - 1)).coerceIn(0.0, 1.0)
        return when (val e = piece) {
            is ProfileElement.BezierE ->
                GeomMath.bezierPointAt(e.bezier, u) to GeomMath.bezierTangentAt(e.bezier, u).normalized()
            // the samples are at equal *parametric* steps, so `u` is the parameter fraction directly: the
            // arc-length→parameter map is the sampled part and the point it lands on is the curve's own
            is ProfileElement.EllipticArcE ->
                (e.arc.startT + Conics.sweep(e.arc) * u).let { t ->
                    Conics.pointAt(e.arc.ellipse, t) to Conics.walkTangent(e.arc, t)
                }
            else -> pts[i] + (pts[i + 1] - pts[i]) * local to (pts[i + 1] - pts[i]).normalized()
        }
    }

    private fun nearestOnSamples(p: Vec2): Double {
        val pts = samples ?: return 0.0
        val cs = cum ?: return 0.0
        var best = 0.0
        var bestD = Double.MAX_VALUE
        for (i in 0 until pts.size - 1) {
            val ab = pts[i + 1] - pts[i]
            val len2 = ab.dot(ab)
            val t = if (len2 < Vec2.EPS * Vec2.EPS) 0.0 else ((p - pts[i]).dot(ab) / len2).coerceIn(0.0, 1.0)
            val d = (pts[i] + ab * t - p).length()
            if (d < bestD) {
                bestD = d
                best = cs[i] + (cs[i + 1] - cs[i]) * t
            }
        }
        return best
    }

    companion object {
        /** How far [raw] radians is from an arc's start **in the arc's own direction**, always ≥ 0. */
        internal fun sweepFrom(
            raw: Double,
            ccw: Boolean,
        ): Double {
            val twoPi = 2 * PI
            var v = raw % twoPi
            if (ccw) {
                if (v < 0) v += twoPi
            } else {
                if (v > 0) v -= twoPi
                v = -v
            }
            return v
        }
    }
}

/**
 * A thick carrier resolved to values: its [legs], the [joins] that belong to no leg (an open end's cap and
 * the straight *step* where two offsets cannot mitre) and the [region] they bound.
 *
 * Produced by **both** carrier cases — an ortho polyline through [thickBodyOf], a curve network through
 * [thickNetwork] — so everything above it (the plan convention, jamb picking, the interval clamps, the 3D
 * cut) is written once and knows nothing about which kind of carrier it has.
 *
 * [approximated] is OP-15's class said out loud: true when a Bézier carrier contributed a sampled offset,
 * or when the boundary had to be resolved through the polygonal kernel (OP-22) rather than by construction.
 */
class ThickBody(
    val legs: List<ThickLeg>,
    val joins: List<Segment>,
    val region: Region,
    val approximated: Boolean,
) {
    val legCount: Int get() = legs.size
}

/** Tolerance at which two carrier endpoints **are** the same vertex of the network (mm). */
private const val WELD = 1e-6

/**
 * The footprint of a **connected curve network** thickened by [thickness] (the OP-21 extension).
 *
 * Every decision here is a function of *values* — which endpoints coincide, hence what the graph is, hence
 * the cyclic order at every vertex — so it lives inside a node's `compute` and nowhere else. That is the
 * rule the first wall implementation broke, applied to a topology instead of to a sort order: dragging two
 * carrier ends apart makes this invalid **with a reason** rather than silently wrong.
 *
 * The boundary is a walk of the fat graph: travel a half-edge's left offset wall, and on arriving at a
 * vertex take the neighbour of the reverse half-edge **clockwise** in the cyclic order of outgoing
 * tangents. That one rule covers a dangling end (*k*=1, whose neighbour is itself, so the corner is the
 * cap), an ordinary corner (*k*=2, the mitre) and a **branch** (*k*≥3, one ordinary corner per angularly
 * adjacent pair) — which is the T/L junction cleanup OP-21 deferred, with no 2D boolean in it.
 *
 * A vertex is **not** only where two carriers share an endpoint. An endpoint that lands in the *interior* of
 * another carrier is a vertex too, and it **splits that carrier** there ([tSplits]) — which is what makes a
 * partition wall drawn onto a hull wall one network instead of two abutting ones (GitHub #7). The split is a
 * *value*, exactly like the welding and the cyclic order: only the count of carrier curves and their sides
 * are structural, so dragging the T apart makes this invalid with a reason and pushing it back heals it
 * (OP-3). Once split, a T needs no rule of its own — it is the *k*=3 branch above, three ordinary mitres.
 */
fun thickNetwork(
    curves: List<CarrierCurve>,
    thickness: Double,
): Pair<ThickBody?, String?> {
    if (curves.isEmpty()) return null to "a wall needs at least one carrier curve"
    if (thickness <= Vec2.EPS) return null to "a thick path needs a non-zero thickness"
    for ((i, c) in curves.withIndex()) {
        if (c.piece is ProfileElement.CircleE) {
            return null to "carrier ${i + 1} is a whole circle, which has no ends to join — break it into arcs first"
        }
    }

    val lengths = DoubleArray(curves.size)
    val offsets = ArrayList<List<Double>>(curves.size)
    for ((i, c) in curves.withIndex()) {
        val len = carrierLength(c.piece)
        if (len < Vec2.EPS) return null to "carrier ${i + 1} has zero length"
        lengths[i] = len
        offsets.add(c.side.offsets(thickness))
    }

    // the edges of the fat graph: every carrier, cut at every T-attachment on it. A carrier nothing ends on
    // is one edge, which is what it always was.
    val edges = ArrayList<Edge>()
    for (i in curves.indices) {
        for (part in splitCarrier(curves[i].piece, tSplits(curves, lengths, i), lengths[i])) {
            edges.add(Edge(i, part.piece, part.from, part.to))
        }
    }

    val verts = ArrayList<Vec2>()

    fun vertexId(p: Vec2): Int {
        for (i in verts.indices) if ((verts[i] - p).length() <= WELD) return i
        verts.add(p)
        return verts.size - 1
    }

    val tail = IntArray(edges.size)
    val head = IntArray(edges.size)
    for ((e, edge) in edges.withIndex()) {
        tail[e] = vertexId(GeomMath.startOf(edge.piece))
        head[e] = vertexId(GeomMath.endOf(edge.piece))
        if (tail[e] == head[e]) {
            return null to "carrier ${edge.carrier + 1} starts and ends at the same point, so it closes on itself"
        }
    }
    disconnection(edges, tail, head, verts.size)?.let { return null to it }

    // two directed half-edges per edge: 2e forward, 2e+1 reversed
    val n = edges.size * 2
    val to = IntArray(n) { if (it % 2 == 0) head[it / 2] else tail[it / 2] }
    val from = IntArray(n) { if (it % 2 == 0) tail[it / 2] else head[it / 2] }
    val walls = ArrayList<OffsetWall>(n)
    for (h in 0 until n) {
        val e = edges[h / 2]
        val forward = h % 2 == 0
        val piece = if (forward) e.piece else GeomMath.reverse(e.piece)
        // a half-edge's *left* wall: reversing a curve mirrors the direction and the sign together
        val off = if (forward) offsets[e.carrier][1] else -offsets[e.carrier][0]
        walls.add(
            offsetWall(piece, off)
                ?: return null to "carrier ${e.carrier + 1} is thicker than the arc it follows, so it has no inner face",
        )
    }

    // the cyclic order of outgoing tangents at every vertex — the whole of the junction rule
    val outgoing = Array(verts.size) { ArrayList<Int>() }
    for (h in 0 until n) outgoing[from[h]].add(h)
    val angle = DoubleArray(n) { outgoingDir(walls[it].carrier).angle() }
    for (ring in outgoing) ring.sortBy { angle[it] }

    val next = IntArray(n)
    for (h in 0 until n) {
        val ring = outgoing[to[h]]
        val at = ring.indexOf(h xor 1)
        next[h] = ring[(at - 1 + ring.size) % ring.size]
    }

    val entry = arrayOfNulls<Vec2>(n)
    val exit = arrayOfNulls<Vec2>(n)
    for (h in 0 until n) {
        val g = next[h]
        val corner = if (g == (h xor 1)) null else meet(walls[h], walls[g], verts[to[h]])
        exit[h] = corner ?: GeomMath.endOf(walls[h].pieces.last())
        entry[g] = corner ?: GeomMath.startOf(walls[g].pieces.first())
    }

    // trim every wall to its two corners. A run that comes back **reversed** is the exact signature (a
    // sign, not a tolerance) of faces overlapping past what adjacent-pair resolution can express.
    var tangled = false
    val runs = ArrayList<List<ProfileElement>>(n)
    for (h in 0 until n) {
        val (chain, forward) = trim(walls[h], entry[h]!!, exit[h]!!)
        runs.add(chain)
        if (!forward) tangled = true
    }

    // the rings: follow next() until it returns to where it started
    val loops = ArrayList<Loop>()
    val joins = ArrayList<Segment>()
    val seen = BooleanArray(n)
    for (start in 0 until n) {
        if (seen[start]) continue
        val chain = ArrayList<ProfileElement>()
        // whether each piece merely **continues** the one before it along the same carrier — the far face of
        // a T-split, where the walk passes through the junction rather than turning at it (see [merged])
        val continues = ArrayList<Boolean>()
        var h = start
        var guard = 0
        var through = false
        while (!seen[h] && guard++ <= n) {
            seen[h] = true
            // a run that came out empty (its two corners coincide) is not a boundary piece; keeping it
            // would break every tangent the checks below read, exactly as a repeated point would
            val pieces = runs[h].filter { (GeomMath.endOf(it) - GeomMath.startOf(it)).length() > WELD }
            for ((k, p) in pieces.withIndex()) {
                chain.add(p)
                continues.add(k == 0 && through)
            }
            val g = next[h]
            val a = GeomMath.endOf(runs[h].last())
            val b = GeomMath.startOf(runs[g].first())
            through = passesThrough(edges, h, g)
            if ((b - a).length() > WELD) {
                chain.add(ProfileElement.Seg(Segment(a, b)))
                continues.add(false)
                joins.add(Segment(a, b))
                through = false
            }
            h = g
        }
        if (h != start) return null to "the wall boundary does not close at this network's junctions"
        if (chain.size < 2) return null to "a wall face of this network is degenerate"
        // the walk's last step re-enters the ring's first piece, so its pass-through belongs to that piece
        if (chain.isNotEmpty()) continues[0] = through
        // The walk keeps material on its **right** — a half-edge's left wall is the boundary the material
        // lies under — so every ring comes back with the opposite handedness to OP-14's convention. They
        // all flip together, which is what keeps the outer/hole classification below a question of sign.
        loops.add(GeomMath.reverseLoop(Loop(merged(chain, continues))))
    }

    // One leg per **carrier curve**, whatever a T-attachment did to it: a leg is the thing an opening's
    // position is measured along, so its arithmetic stays that of the whole carrier and a split cannot move
    // an interval. What the split changes is only how many face runs the leg has (see [FaceRun]).
    val legs =
        curves.indices.map { i ->
            val own = edges.indices.filter { edges[it].carrier == i }
            leg(
                curves[i].piece,
                offsets[i],
                lengths[i],
                (0..1).map { side ->
                    own.map { e ->
                        val chain = if (side == 1) runs[e * 2] else runs[e * 2 + 1].reversed().map { GeomMath.reverse(it) }
                        FaceRun(chain, edges[e].from, edges[e].to)
                    }
                },
            )
        }
    val sampled = legs.any { it.approximated }

    // A ring that is **not simple** is where pairwise construction stops being able to express the union:
    // two of the walls overlap past what their adjacent-pair corner resolved. Both tests are signs rather
    // than tolerances — a run that came back reversed, and a ring whose total turning is not one full turn.
    if (!tangled) tangled = loops.any { abs(abs(turning(it)) - 2 * PI) > 1e-6 }
    if (!tangled) nest(loops)?.let { return ThickBody(legs, joins, it, sampled) to null }

    // The kernel route (OP-22): the nonzero-winding interior of the tangle, which is what the union *is*.
    // Taking it demotes the footprint to OP-15's approximated class, because the kernel is polygonal.
    val rings = loops.map { Geom3.tessellateLoop(it) }.filter { it.size >= 3 }
    if (rings.isEmpty()) return null to "the wall footprint encloses no area"
    val (merged, why) = RegionBool.combine(rings, rings, BoolOp.UNION)
    if (merged == null) return null to (why ?: "the wall footprint cannot be resolved")
    val (regions, whyNest) = RegionBool.regionsOf(merged)
    if (regions == null) return null to (whyNest ?: "the wall footprint cannot be resolved")
    if (regions.size != 1) {
        return null to "this wall's footprint falls into ${regions.size} separate areas — thicken them separately"
    }
    return ThickBody(legs, joins, regions.single(), true) to null
}

/**
 * The [ThickBody] of the **ortho** carrier case: the same [GeomMath.thickFaces] / [GeomMath.thickRegion]
 * the *Wall* tool has always used, wrapped in the shape everything above a carrier consumes.
 *
 * Deliberately the old computation rather than the new tracer on rectilinear input. A generalized tracer
 * that merely *happened* to agree here would be a claim with nothing to check it; reusing the code is a
 * guarantee that every stored `wall` step replays to the identical region.
 */
fun thickBodyOf(f: ThickFaces): Pair<ThickBody?, String?> {
    val (region, why) = GeomMath.thickRegion(f)
    if (region == null) return null to why
    val legs =
        (0 until f.legCount).map { i ->
            val runs =
                (0..1).map { side ->
                    listOf(
                        FaceRun(
                            listOf(ProfileElement.Seg(Segment(f.faces[side][i], f.faces[side][(i + 1) % f.faces[side].size]))),
                            0.0,
                            f.legLengths[i],
                        ),
                    )
                }
            val start = f.legs[i].origin
            ThickLeg(
                ProfileElement.Seg(Segment(start, start + f.legs[i].dir * f.legLengths[i])),
                f.offsets,
                f.legLengths[i],
                runs,
                null,
                null,
            )
        }
    val joins =
        if (f.closed) {
            emptyList()
        } else {
            listOf(Segment(f.faces[0].first(), f.faces[1].first()), Segment(f.faces[0].last(), f.faces[1].last()))
        }
    return ThickBody(legs, joins, region, false) to null
}

/** Arc length of a carrier piece — sampled for a Bézier, which is OP-15's approximated class. */
fun carrierLength(e: ProfileElement): Double =
    when (e) {
        is ProfileElement.Seg -> (e.segment.b - e.segment.a).length()
        is ProfileElement.ArcE -> abs(GeomMath.sweep(e.arc)) * e.arc.radius
        is ProfileElement.CircleE -> 2 * PI * e.circle.radius
        is ProfileElement.BezierE ->
            GeomMath.tessellateBezier(e.bezier).let { pts -> (0 until pts.size - 1).sumOf { (pts[it + 1] - pts[it]).length() } }
        // an elliptic integral: numeric to a stated tolerance (OP-15), which is what puts an elliptic
        // carrier in the same approximated class a Bézier carrier is in
        is ProfileElement.EllipticArcE -> Conics.arcLength(e.arc)
        is ProfileElement.EllipseE -> Conics.circumference(e.ellipse)
        // numeric to a stated tolerance, which puts a function-curve carrier in the same approximated
        // class a Bézier and an elliptic one are already in (OP-15)
        is ProfileElement.FuncE -> FuncCurves.arcLength(e.curve)
    }

/** How many samples an elliptic carrier's offset and its arc-length map are built on (OP-15, OP-24). */
internal fun ellipticSteps(arc: EllipticArc): Int =
    max(GeomMath.BEZIER_STEPS, Conics.chordSteps(arc.ellipse, Conics.sweep(arc), GeomMath.TESS_TOL_MM))

// ---- T-attachments: the vertices that are not endpoints ----

/**
 * Whether the walk's step from [h] to [g] merely **passes through** a T-split: the two half-edges are two
 * spans of *one* carrier, travelled the same way, so what looks like a junction to the graph is no corner at
 * all on this face — the branch joins on the other side.
 */
private fun passesThrough(
    edges: List<Edge>,
    h: Int,
    g: Int,
): Boolean = g != (h xor 1) && h % 2 == g % 2 && edges[h / 2].carrier == edges[g / 2].carrier

/**
 * [chain] with every pass-through corner ([continues]) closed up again: two collinear segments become one,
 * two arcs of one circle become one arc.
 *
 * Why it matters rather than being cosmetic: a boundary vertex with **zero turning** is a corner the drawing
 * would show, an extra `regionCorner` accessor would address, and — worst — a cap triangulation could produce
 * a zero-area triangle from. So a split leaves the far face exactly the single face it was before, which is
 * also what keeps an unsplit network's boundary bit-identical (there are no pass-throughs then, and this is a
 * no-op).
 */
private fun merged(
    chain: List<ProfileElement>,
    continues: List<Boolean>,
): List<ProfileElement> {
    if (continues.none { it }) return chain
    // a ring has no first piece: rotate until the walk *turns* into index 0, so the merge can run linearly
    var shift = 0
    while (shift < chain.size && continues[shift]) shift++
    if (shift >= chain.size) return chain
    val order = (chain.indices).map { (it + shift) % chain.size }
    val out = ArrayList<ProfileElement>(chain.size)
    for (i in order) {
        val join = if (continues[i]) mergeTwo(out.lastOrNull(), chain[i]) else null
        if (join != null) out[out.size - 1] = join else out.add(chain[i])
    }
    return out
}

/** [a] and [b] as one piece when they are the same straight line or the same circle, else null. */
private fun mergeTwo(
    a: ProfileElement?,
    b: ProfileElement,
): ProfileElement? {
    if (a == null) return null
    if (a is ProfileElement.Seg && b is ProfileElement.Seg) {
        val da = (a.segment.b - a.segment.a).normalized()
        val db = (b.segment.b - b.segment.a).normalized()
        if (abs(da.cross(db)) > 1e-9 || da.dot(db) <= 0.0) return null
        return ProfileElement.Seg(Segment(a.segment.a, b.segment.b))
    }
    if (a is ProfileElement.ArcE && b is ProfileElement.ArcE) {
        if (a.arc.ccw != b.arc.ccw) return null
        if ((a.arc.center - b.arc.center).length() > WELD || abs(a.arc.radius - b.arc.radius) > WELD) return null
        return ProfileElement.ArcE(Arc(a.arc.center, a.arc.radius, a.arc.startAngle, b.arc.endAngle, a.arc.ccw))
    }
    return null
}

/** One edge of the fat graph: a whole carrier, or one span of one that a T-attachment split. */
private class Edge(
    val carrier: Int,
    val piece: ProfileElement,
    val from: Double,
    val to: Double,
)

/** One span of a carrier: the [piece] itself, and the carrier arc lengths it covers. */
private class CarrierPart(
    val piece: ProfileElement,
    val from: Double,
    val to: Double,
)

/**
 * Where carrier [i] is **split by a T-attachment**: the arc lengths at which another carrier's endpoint lands
 * in its interior, ascending and deduplicated.
 *
 * The three degenerate configurations collapse to the plain-weld case rather than double-splitting, and all
 * three by the same arithmetic: an endpoint at (or within [WELD] of) the host's own end is not interior, two
 * endpoints at one spot are one split, and an endpoint off the host is no split at all.
 */
private fun tSplits(
    curves: List<CarrierCurve>,
    lengths: DoubleArray,
    i: Int,
): List<Double> {
    val host = curves[i].piece
    val out = ArrayList<Double>()
    for (j in curves.indices) {
        if (j == i) continue
        for (p in listOf(GeomMath.startOf(curves[j].piece), GeomMath.endOf(curves[j].piece))) {
            val d = interiorParam(host, p, lengths[i]) ?: continue
            if (out.none { abs(it - d) <= WELD }) out.add(d)
        }
    }
    out.sort()
    return out
}

/**
 * How far along [host] the point [p] lies **strictly inside** it (to within [WELD]), or null.
 *
 * Exact for a segment (the perpendicular foot) and for an arc (the angle about its centre, which also rules
 * out a point on the circle but past the arc's sweep). For a Bézier the parameter is the nearest-point search
 * the *Break* tool already uses and the arc length is the sampled map — OP-15's approximated class, which a
 * Bézier leg is in already, so the flag stays honest.
 */
private fun interiorParam(
    host: ProfileElement,
    p: Vec2,
    length: Double,
): Double? {
    val d =
        when (host) {
            is ProfileElement.Seg -> {
                val dir = (host.segment.b - host.segment.a).normalized()
                val t = (p - host.segment.a).dot(dir)
                if ((host.segment.a + dir * t - p).length() > WELD) return null
                t
            }
            is ProfileElement.ArcE -> {
                if (abs((p - host.arc.center).length() - host.arc.radius) > WELD) return null
                ThickLeg.sweepFrom(
                    atan2(p.y - host.arc.center.y, p.x - host.arc.center.x) - host.arc.startAngle,
                    host.arc.ccw,
                ) * host.arc.radius
            }
            is ProfileElement.BezierE -> {
                val t = GeomMath.bezierNearestParam(host.bezier, p)
                if ((GeomMath.bezierPointAt(host.bezier, t) - p).length() > WELD) return null
                bezierLength(host.bezier, t)
            }
            is ProfileElement.EllipticArcE -> {
                val t = Conics.paramOf(host.arc.ellipse, p)
                if ((Conics.pointAt(host.arc.ellipse, t) - p).length() > WELD) return null
                if (!Conics.contains(host.arc, t)) return null
                Conics.arcLength(EllipticArc(host.arc.ellipse, host.arc.startT, t, host.arc.ccw))
            }
            is ProfileElement.FuncE -> {
                val t = FuncCurves.nearestParam(host.curve, p)
                val at = FuncCurves.pointAt(host.curve, t) ?: return null
                if ((at - p).length() > WELD) return null
                FuncCurves.arcLength(host.curve.copy(t1 = t))
            }
            is ProfileElement.CircleE, is ProfileElement.EllipseE -> return null
        }
    return d.takeIf { it > WELD && it < length - WELD }
}

/** [piece] cut at every arc length in [at] (which is strictly interior and ascending) — one part when empty. */
private fun splitCarrier(
    piece: ProfileElement,
    at: List<Double>,
    length: Double,
): List<CarrierPart> {
    if (at.isEmpty()) return listOf(CarrierPart(piece, 0.0, length))
    val bounds = listOf(0.0) + at + listOf(length)
    return (0 until bounds.size - 1).map { k ->
        CarrierPart(
            subCarrier(piece, bounds[k], bounds[k + 1], atStart = k == 0, atEnd = k == bounds.size - 2),
            bounds[k],
            bounds[k + 1],
        )
    }
}

/**
 * The stretch of [piece] between arc lengths [a] and [b].
 *
 * The carrier's *own* ends are taken verbatim ([atStart], [atEnd]) rather than recomputed from the arc
 * length, so a split leaves the outer endpoints bit-identical to the unsplit carrier and the vertex a
 * neighbour welds onto cannot drift by a rounding.
 */
private fun subCarrier(
    piece: ProfileElement,
    a: Double,
    b: Double,
    atStart: Boolean,
    atEnd: Boolean,
): ProfileElement =
    when (piece) {
        is ProfileElement.Seg -> {
            val dir = (piece.segment.b - piece.segment.a).normalized()
            ProfileElement.Seg(
                Segment(
                    if (atStart) piece.segment.a else piece.segment.a + dir * a,
                    if (atEnd) piece.segment.b else piece.segment.a + dir * b,
                ),
            )
        }
        is ProfileElement.ArcE -> {
            val turn = if (piece.arc.ccw) 1.0 else -1.0
            ProfileElement.ArcE(
                Arc(
                    piece.arc.center,
                    piece.arc.radius,
                    if (atStart) piece.arc.startAngle else piece.arc.startAngle + turn * a / piece.arc.radius,
                    if (atEnd) piece.arc.endAngle else piece.arc.startAngle + turn * b / piece.arc.radius,
                    piece.arc.ccw,
                ),
            )
        }
        is ProfileElement.BezierE ->
            ProfileElement.BezierE(
                subBezier(
                    piece.bezier,
                    if (atStart) 0.0 else bezierParam(piece.bezier, a),
                    if (atEnd) 1.0 else bezierParam(piece.bezier, b),
                ),
            )
        is ProfileElement.EllipticArcE -> {
            val e = piece.arc.ellipse
            ProfileElement.EllipticArcE(
                EllipticArc(
                    e,
                    if (atStart) piece.arc.startT else Conics.paramAtDistance(e, piece.arc.startT, if (piece.arc.ccw) a else -a),
                    if (atEnd) piece.arc.endT else Conics.paramAtDistance(e, piece.arc.startT, if (piece.arc.ccw) b else -b),
                    piece.arc.ccw,
                ),
            )
        }
        // the domain is cut where the arc-length map says, which is OP-15's sampled map exactly as the
        // elliptic carrier uses it: the *map* is numeric, the parameters it lands on are the curve's own
        is ProfileElement.FuncE ->
            ProfileElement.FuncE(
                piece.curve.copy(
                    t0 = if (atStart) piece.curve.t0 else FuncCurves.paramAtDistance(piece.curve, piece.curve.t0, a),
                    t1 = if (atEnd) piece.curve.t1 else FuncCurves.paramAtDistance(piece.curve, piece.curve.t0, b),
                ),
            )
        is ProfileElement.CircleE, is ProfileElement.EllipseE -> piece
    }

/** The tessellation of [b] with the cumulative chord lengths beside it — the sampled arc-length map. */
private fun bezierCum(b: Bezier): Pair<List<Vec2>, List<Double>> {
    val pts = GeomMath.tessellateBezier(b)
    val cum = ArrayList<Double>(pts.size)
    var acc = 0.0
    cum.add(0.0)
    for (i in 0 until pts.size - 1) {
        acc += (pts[i + 1] - pts[i]).length()
        cum.add(acc)
    }
    return pts to cum
}

/** Arc length at parameter [t] of [b], on the sampled map — the inverse of [bezierParam]. */
private fun bezierLength(
    b: Bezier,
    t: Double,
): Double {
    val (pts, cum) = bezierCum(b)
    val n = pts.size - 1
    val x = (t * n).coerceIn(0.0, n.toDouble())
    val i = minOf(x.toInt(), n - 1)
    return cum[i] + (cum[i + 1] - cum[i]) * (x - i)
}

/** Parameter of [b] at arc length [d], on the same sampled map. */
private fun bezierParam(
    b: Bezier,
    d: Double,
): Double {
    val (pts, cum) = bezierCum(b)
    val n = pts.size - 1
    var i = 0
    while (i < n - 1 && cum[i + 1] < d) i++
    val span = cum[i + 1] - cum[i]
    val local = if (span < Vec2.EPS) 0.0 else (d - cum[i]) / span
    return ((i + local) / n).coerceIn(0.0, 1.0)
}

/** The stretch of [b] between parameters [t0] and [t1] — **de Casteljau**, so the part *is* the curve. */
private fun subBezier(
    b: Bezier,
    t0: Double,
    t1: Double,
): Bezier {
    val rest = if (t0 <= 0.0) b else splitBezier(b, t0).second
    if (t1 >= 1.0) return rest
    val u = if (t0 <= 0.0) t1 else (t1 - t0) / (1.0 - t0)
    return splitBezier(rest, u.coerceIn(0.0, 1.0)).first
}

private fun splitBezier(
    b: Bezier,
    t: Double,
): Pair<Bezier, Bezier> {
    fun at(
        p: Vec2,
        q: Vec2,
    ) = p + (q - p) * t
    val p01 = at(b.p0, b.p1)
    val p12 = at(b.p1, b.p2)
    val p23 = at(b.p2, b.p3)
    val p012 = at(p01, p12)
    val p123 = at(p12, p23)
    val mid = at(p012, p123)
    return Bezier(b.p0, p01, p012, mid) to Bezier(mid, p123, p23, b.p3)
}

// ---- the pieces of the walk ----

/**
 * One offset wall of one directed half-edge: the boundary [pieces] themselves, plus whichever *carrier*
 * an intersection has to be taken on — the infinite [line] of a straight wall, the whole [circle] of a
 * concentric one — and the [carrier] curve the tangent order is read from.
 */
private class OffsetWall(
    val pieces: List<ProfileElement>,
    val carrier: ProfileElement,
    val line: Line?,
    val circle: Circle?,
    val polyline: List<Vec2>?,
)

/**
 * The offset wall of [piece] at signed distance [off] — **exact** for a segment (a parallel) and for an arc
 * (a *concentric* arc), **sampled** for a Bézier (OP-15). Null when an arc's inner offset would have a
 * non-positive radius, i.e. a wall thicker than the curve it follows.
 */
private fun offsetWall(
    piece: ProfileElement,
    off: Double,
): OffsetWall? =
    when (piece) {
        is ProfileElement.Seg -> {
            val dir = (piece.segment.b - piece.segment.a).normalized()
            val d = dir.perp() * off
            val seg = Segment(piece.segment.a + d, piece.segment.b + d)
            OffsetWall(listOf(ProfileElement.Seg(seg)), piece, Line(seg.a, dir), null, null)
        }
        is ProfileElement.ArcE -> {
            val r = piece.arc.radius + (if (piece.arc.ccw) -off else off)
            if (r <= Vec2.EPS) {
                null
            } else {
                val arc = Arc(piece.arc.center, r, piece.arc.startAngle, piece.arc.endAngle, piece.arc.ccw)
                OffsetWall(listOf(ProfileElement.ArcE(arc)), piece, null, Circle(arc.center, r), null)
            }
        }
        is ProfileElement.BezierE -> {
            val poly = offsetBezier(piece.bezier, off)
            val pieces = (0 until poly.size - 1).map { ProfileElement.Seg(Segment(poly[it], poly[it + 1])) }
            OffsetWall(pieces, piece, null, null, poly)
        }
        // an ellipse's offset is **not an ellipse** — OP-15's spline rule verbatim, so it is sampled and
        // the leg (and with it the whole footprint) is flagged approximated
        is ProfileElement.EllipticArcE -> {
            val poly = offsetEllipticArc(piece.arc, off)
            val pieces = (0 until poly.size - 1).map { ProfileElement.Seg(Segment(poly[it], poly[it + 1])) }
            OffsetWall(pieces, piece, null, null, poly)
        }
        // a function curve's offset is not a function curve of the same shape — OP-15's spline rule
        // verbatim — so it is sampled, exact at every sample along the curve's own normal, and the leg
        // (with it the whole footprint) is flagged approximated
        is ProfileElement.FuncE -> {
            val poly = offsetFuncCurve(piece.curve, off)
            if (poly.size < 2) {
                null
            } else {
                val pieces = (0 until poly.size - 1).map { ProfileElement.Seg(Segment(poly[it], poly[it + 1])) }
                OffsetWall(pieces, piece, null, null, poly)
            }
        }
        is ProfileElement.CircleE, is ProfileElement.EllipseE -> null
    }

/**
 * A **function curve's** offset at signed distance [off], as a polyline (OP-15's approximated class).
 *
 * The same bargain [offsetBezier] and [offsetEllipticArc] make, and made here for the same reason: the
 * offset of an arbitrary function is not that function displaced, so there is nothing exact to hand back.
 * Every sample sits at a parameter of the true curve and is displaced along its **exact** normal there — so
 * the honest claim is "exact at the sample points, chords between". Where the derivative is not statable
 * there is no normal at all and the offset is empty, which the caller turns into the refusal it is.
 */
fun offsetFuncCurve(
    c: FuncCurve,
    off: Double,
): List<Vec2> {
    val n = max(GeomMath.BEZIER_STEPS, FuncCurves.chordSteps(c))
    val out = ArrayList<Vec2>(n + 1)
    for (k in 0..n) {
        val t = c.t0 + c.span * k / n
        val p = FuncCurves.pointAt(c, t) ?: return emptyList()
        val nrm = FuncCurves.normalAt(c, t) ?: return emptyList()
        out.add(p + nrm * off)
    }
    return out
}

/**
 * A cubic Bézier's offset at signed distance [off], as a polyline (OP-15's **approximated** class).
 *
 * The samples are the ordinary tessellation's parameters and each is displaced along the curve's **exact**
 * normal there, so the approximation is confined to exactly one place: the chords *between* samples. That
 * is the same bargain every tessellated curve in this engine makes, and it is why the honest claim is "the
 * offset is exact at the sample points" rather than "the offset is within a tolerance".
 */
fun offsetBezier(
    b: Bezier,
    off: Double,
): List<Vec2> =
    (0..GeomMath.BEZIER_STEPS).map { k ->
        val t = k.toDouble() / GeomMath.BEZIER_STEPS
        GeomMath.bezierPointAt(b, t) + GeomMath.bezierTangentAt(b, t).normalized().perp() * off
    }

/**
 * An **elliptic arc's** offset at signed distance [off], as a polyline (OP-15's approximated class, OP-24).
 *
 * The same bargain [offsetBezier] makes, and it is made here for a reason worth naming: the offset of an
 * ellipse is not an ellipse — it is a sextic — so there is no exact conic to hand back, and inventing one
 * would be the "plausible-looking wrong answer" this design record keeps refusing. Every sample sits at a
 * parametric angle of the true curve and is displaced along the **exact** outward normal there, so the
 * honest claim is precisely "exact at the sample points, chords between".
 */
fun offsetEllipticArc(
    arc: EllipticArc,
    off: Double,
): List<Vec2> {
    val n = ellipticSteps(arc)
    val sw = Conics.sweep(arc)
    return (0..n).map { k ->
        val t = arc.startT + sw * k / n
        Conics.pointAt(arc.ellipse, t) + Conics.walkTangent(arc, t).perp() * off
    }
}

/** The unit direction a half-edge leaves its tail vertex in (its carrier's tangent at the start). */
private fun outgoingDir(piece: ProfileElement): Vec2 =
    when (piece) {
        is ProfileElement.Seg -> (piece.segment.b - piece.segment.a).normalized()
        is ProfileElement.ArcE ->
            Vec2(cos(piece.arc.startAngle), sin(piece.arc.startAngle)).let { if (piece.arc.ccw) it.perp() else -it.perp() }
        is ProfileElement.BezierE ->
            GeomMath.tessellateBezier(piece.bezier).let { pts ->
                ((pts.firstOrNull { (it - pts[0]).length() > Vec2.EPS } ?: pts.last()) - pts[0]).normalized()
            }
        is ProfileElement.EllipticArcE -> Conics.walkTangent(piece.arc, piece.arc.startT)
        is ProfileElement.FuncE -> funcWalkTangent(piece.curve, piece.curve.t0)
        is ProfileElement.CircleE, is ProfileElement.EllipseE -> Vec2(1.0, 0.0)
    }

/**
 * The unit walk direction of a function curve at [t] — its derivative where the AST states one, and the
 * chord out of that point where it does not.
 *
 * The chord fallback is confined to *this* question deliberately: which way a carrier leaves a vertex is a
 * fact about the drawn polyline the network is walked on, not a construction anchored on a tangent — the
 * constructions that *are* refuse by name instead (`Document.funcTangentRefusal`).
 */
private fun funcWalkTangent(
    c: FuncCurve,
    t: Double,
): Vec2 {
    FuncCurves.tangentAt(c, t)?.takeIf { it.length() > Vec2.EPS }?.let { return it.normalized() }
    val here = FuncCurves.pointAt(c, t) ?: return Vec2(1.0, 0.0)
    val step = if (t >= c.t1 - Vec2.EPS) -c.span / FuncCurves.RENDER_STEPS else c.span / FuncCurves.RENDER_STEPS
    val there = FuncCurves.pointAt(c, t + step) ?: return Vec2(1.0, 0.0)
    val d = if (step > 0) there - here else here - there
    return if (d.length() < Vec2.EPS) Vec2(1.0, 0.0) else d.normalized()
}

/**
 * Where two offset walls meet, nearest the shared carrier vertex [v]: `intersectLL` for the classic mitre,
 * `intersectLC` and `intersectCC` for the mixed and circular cases (the fillet work's machinery, reused).
 * Null when the two carriers do not meet at all — and then the walk joins the two wall ends with a straight
 * *step*, which is the same construction an end cap is.
 */
private fun meet(
    a: OffsetWall,
    b: OffsetWall,
    v: Vec2,
): Vec2? {
    val la = a.line ?: a.polyline?.let { terminalLine(it, atEnd = true) }
    val lb = b.line ?: b.polyline?.let { terminalLine(it, atEnd = false) }
    val set =
        when {
            la != null && lb != null -> GeomMath.intersectLL(la, lb)
            la != null && b.circle != null -> GeomMath.intersectLC(la, b.circle)
            a.circle != null && lb != null -> GeomMath.intersectLC(lb, a.circle)
            a.circle != null && b.circle != null -> GeomMath.intersectCC(a.circle, b.circle)
            else -> PointSet(emptyList())
        }
    return set.points.minByOrNull { (it - v).length() }
}

/** The infinite line of a polyline's terminal segment — how a sampled offset takes part in a mitre. */
private fun terminalLine(
    poly: List<Vec2>,
    atEnd: Boolean,
): Line {
    val p = if (atEnd) poly[poly.size - 2] else poly[1]
    val q = if (atEnd) poly.last() else poly.first()
    return Line(q, (q - p).normalized())
}

/**
 * [wall] trimmed between its two corners, plus whether the run still runs **forwards**. A reversed run is
 * what sends the whole footprint to the kernel: it is the exact signature of two faces overlapping past
 * what an adjacent pair of walls can resolve between them.
 */
private fun trim(
    wall: OffsetWall,
    a: Vec2,
    b: Vec2,
): Pair<List<ProfileElement>, Boolean> {
    val poly = wall.polyline
    if (poly != null) {
        val inner = poly.filter { (it - poly.first()).length() > WELD && (it - poly.last()).length() > WELD }
        val chain = listOf(a) + inner + listOf(b)
        val dir = (poly.last() - poly.first()).normalized()
        return (0 until chain.size - 1).map { ProfileElement.Seg(Segment(chain[it], chain[it + 1])) } to
            ((b - a).dot(dir) >= -WELD)
    }
    return when (val p = wall.pieces.single()) {
        is ProfileElement.ArcE -> {
            val arc = p.arc
            val cut =
                Arc(
                    arc.center,
                    arc.radius,
                    atan2(a.y - arc.center.y, a.x - arc.center.x),
                    atan2(b.y - arc.center.y, b.x - arc.center.x),
                    arc.ccw,
                )
            // A mitre legitimately pushes a corner *past* the carrier's own end — a straight run's does too,
            // and only a run that came back **reversed** means two faces have overlapped. So the test is on
            // the run's own direction, not on how much of the arc it covers: each corner is placed at the
            // representative of its angle nearest where it nominally belongs (the run's start, the run's
            // end), which is exact for any mitre short of half a turn and cannot be fooled by the wrap that
            // makes a 197° trim of a 180° arc look like an overlap.
            val total = GeomMath.sweep(arc)
            val ua = nearAngle(atan2(a.y - arc.center.y, a.x - arc.center.x) - arc.startAngle, 0.0)
            val ub = nearAngle(atan2(b.y - arc.center.y, b.x - arc.center.x) - arc.startAngle, total)
            listOf<ProfileElement>(ProfileElement.ArcE(cut)) to ((ub - ua) * (if (arc.ccw) 1.0 else -1.0) >= -1e-9)
        }
        else -> {
            val dir = (GeomMath.endOf(p) - GeomMath.startOf(p)).normalized()
            listOf<ProfileElement>(ProfileElement.Seg(Segment(a, b))) to ((b - a).dot(dir) >= -WELD)
        }
    }
}

private fun leg(
    piece: ProfileElement,
    offsets: List<Double>,
    length: Double,
    runs: List<List<FaceRun>>,
): ThickLeg {
    val pts =
        when (piece) {
            is ProfileElement.BezierE -> GeomMath.tessellateBezier(piece.bezier)
            is ProfileElement.EllipticArcE -> Conics.sample(piece.arc, ellipticSteps(piece.arc))
            else -> return ThickLeg(piece, offsets, length, runs, null, null)
        }
    val cum = ArrayList<Double>(pts.size)
    var acc = 0.0
    cum.add(0.0)
    for (i in 0 until pts.size - 1) {
        acc += (pts[i + 1] - pts[i]).length()
        cum.add(acc)
    }
    return ThickLeg(piece, offsets, length, runs, pts, cum)
}

/**
 * The traced rings nested into one area: exactly one positive ring is the outer boundary and every negative
 * one a hole. More than one positive ring is not something a connected fat graph produces, so it means the
 * same thing a reversed run does — null here, and the caller takes the kernel route.
 */
private fun nest(loops: List<Loop>): Region? {
    val areas = loops.map { GeomMath.signedArea(it) }
    val outers = loops.indices.filter { areas[it] > 0.0 }
    if (outers.size != 1) return null
    val outer = outers.single()
    return Region(loops[outer], loops.indices.filter { it != outer }.map { GeomMath.orient(loops[it], ccw = false) })
}

/**
 * Null when the curves span one connected graph, otherwise the refusal — by name.
 *
 * Asked of the **edges**, i.e. after every T-attachment has split the carrier it lands on: a partition whose
 * end sits mid-way along a hull wall is connected to it, and the connectivity question has to be asked over
 * the same vertex set the walk uses or the two answers could differ. The refusal still names *curves*,
 * because a carrier is what the user picked.
 */
private fun disconnection(
    edges: List<Edge>,
    tail: IntArray,
    head: IntArray,
    vertices: Int,
): String? {
    val count = edges.size
    val parent = IntArray(vertices) { it }

    fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) r = parent[r]
        var c = x
        while (parent[c] != c) {
            val nxt = parent[c]
            parent[c] = r
            c = nxt
        }
        return r
    }
    for (i in 0 until count) {
        val a = find(tail[i])
        val b = find(head[i])
        if (a != b) parent[a] = b
    }
    val roots = (0 until count).map { find(tail[it]) }.distinct()
    if (roots.size <= 1) return null
    val runs =
        roots.map { r ->
            (0 until count).filter { find(tail[it]) == r }.map { edges[it].carrier }.distinct().sorted()
                .joinToString("+") { "curve ${it + 1}" }
        }
    return "these curves are not connected — they form ${roots.size} separate runs (${runs.joinToString(" / ")}); " +
        "a wall's carrier is one connected network"
}

/**
 * The total turning of a closed [loop] — every arc's own sweep plus the exterior angle at every corner.
 *
 * A **simple** closed curve turns through exactly one full circle (±2π); anything else has run over itself,
 * which is precisely the case the pairwise construction cannot express and the kernel can. Cheap (one pass
 * over the pieces), exact, and it needs no tessellation and no pairwise intersection test.
 */
private fun turning(loop: Loop): Double {
    val n = loop.elements.size
    if (n == 0) return 0.0
    var total = 0.0
    for (i in 0 until n) {
        val e = loop.elements[i]
        if (e is ProfileElement.ArcE) total += GeomMath.sweep(e.arc)
        val out = endTangent(e)
        val into = startTangent(loop.elements[(i + 1) % n])
        total += atan2(out.cross(into), out.dot(into))
    }
    return total
}

private fun startTangent(e: ProfileElement): Vec2 =
    when (e) {
        is ProfileElement.Seg -> (e.segment.b - e.segment.a).normalized()
        is ProfileElement.ArcE ->
            Vec2(cos(e.arc.startAngle), sin(e.arc.startAngle)).let { if (e.arc.ccw) it.perp() else -it.perp() }
        is ProfileElement.BezierE -> GeomMath.bezierTangentAt(e.bezier, 0.0).normalized()
        is ProfileElement.EllipticArcE -> Conics.walkTangent(e.arc, e.arc.startT)
        is ProfileElement.FuncE -> funcWalkTangent(e.curve, e.curve.t0)
        is ProfileElement.CircleE, is ProfileElement.EllipseE -> Vec2(0.0, 1.0)
    }

private fun endTangent(e: ProfileElement): Vec2 =
    when (e) {
        is ProfileElement.Seg -> (e.segment.b - e.segment.a).normalized()
        is ProfileElement.ArcE ->
            Vec2(cos(e.arc.endAngle), sin(e.arc.endAngle)).let { if (e.arc.ccw) it.perp() else -it.perp() }
        is ProfileElement.BezierE -> GeomMath.bezierTangentAt(e.bezier, 1.0).normalized()
        is ProfileElement.EllipticArcE -> Conics.walkTangent(e.arc, e.arc.endT)
        is ProfileElement.FuncE -> funcWalkTangent(e.curve, e.curve.t1)
        is ProfileElement.CircleE, is ProfileElement.EllipseE -> Vec2(0.0, 1.0)
    }

/** [raw] shifted by whole turns to the representative nearest [target] — see [trim]. */
private fun nearAngle(
    raw: Double,
    target: Double,
): Double {
    val twoPi = 2 * PI
    return raw + twoPi * kotlin.math.round((target - raw) / twoPi)
}
