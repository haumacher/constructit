package constructit.geom

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
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
 * One leg of a thick carrier, resolved to values: the oriented carrier [piece], its arc [length], the two
 * signed face [offsets] (ascending; + is left of the walk direction) and the two **trimmed** offset runs
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
    /** The two offset runs, trimmed corner to corner and oriented along the leg; index by side. */
    val runs: List<List<ProfileElement>>,
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
        val b =
            (piece as? ProfileElement.BezierE)?.bezier
                ?: return pts[i] + (pts[i + 1] - pts[i]) * local to (pts[i + 1] - pts[i]).normalized()
        val t = ((i + local) / (pts.size - 1)).coerceIn(0.0, 1.0)
        return GeomMath.bezierPointAt(b, t) to GeomMath.bezierTangentAt(b, t).normalized()
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

    val verts = ArrayList<Vec2>()

    fun vertexId(p: Vec2): Int {
        for (i in verts.indices) if ((verts[i] - p).length() <= WELD) return i
        verts.add(p)
        return verts.size - 1
    }

    val tail = IntArray(curves.size)
    val head = IntArray(curves.size)
    val lengths = DoubleArray(curves.size)
    val offsets = ArrayList<List<Double>>(curves.size)
    for ((i, c) in curves.withIndex()) {
        val len = carrierLength(c.piece)
        if (len < Vec2.EPS) return null to "carrier ${i + 1} has zero length"
        lengths[i] = len
        offsets.add(c.side.offsets(thickness))
        tail[i] = vertexId(GeomMath.startOf(c.piece))
        head[i] = vertexId(GeomMath.endOf(c.piece))
        if (tail[i] == head[i]) return null to "carrier ${i + 1} starts and ends at the same point, so it closes on itself"
    }
    disconnection(curves.size, tail, head, verts.size)?.let { return null to it }

    // two directed half-edges per curve: 2i forward, 2i+1 reversed
    val n = curves.size * 2
    val to = IntArray(n) { if (it % 2 == 0) head[it / 2] else tail[it / 2] }
    val from = IntArray(n) { if (it % 2 == 0) tail[it / 2] else head[it / 2] }
    val walls = ArrayList<OffsetWall>(n)
    for (h in 0 until n) {
        val i = h / 2
        val forward = h % 2 == 0
        val piece = if (forward) curves[i].piece else GeomMath.reverse(curves[i].piece)
        // a half-edge's *left* wall: reversing a curve mirrors the direction and the sign together
        val off = if (forward) offsets[i][1] else -offsets[i][0]
        walls.add(
            offsetWall(piece, off)
                ?: return null to "carrier ${i + 1} is thicker than the arc it follows, so it has no inner face",
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
        var h = start
        var guard = 0
        while (!seen[h] && guard++ <= n) {
            seen[h] = true
            // a run that came out empty (its two corners coincide) is not a boundary piece; keeping it
            // would break every tangent the checks below read, exactly as a repeated point would
            chain.addAll(runs[h].filter { (GeomMath.endOf(it) - GeomMath.startOf(it)).length() > WELD })
            val g = next[h]
            val a = GeomMath.endOf(runs[h].last())
            val b = GeomMath.startOf(runs[g].first())
            if ((b - a).length() > WELD) {
                chain.add(ProfileElement.Seg(Segment(a, b)))
                joins.add(Segment(a, b))
            }
            h = g
        }
        if (h != start) return null to "the wall boundary does not close at this network's junctions"
        if (chain.size < 2) return null to "a wall face of this network is degenerate"
        // The walk keeps material on its **right** — a half-edge's left wall is the boundary the material
        // lies under — so every ring comes back with the opposite handedness to OP-14's convention. They
        // all flip together, which is what keeps the outer/hole classification below a question of sign.
        loops.add(GeomMath.reverseLoop(Loop(chain)))
    }

    val legs =
        curves.indices.map { i ->
            leg(
                curves[i].piece,
                offsets[i],
                lengths[i],
                listOf(runs[i * 2 + 1].reversed().map { GeomMath.reverse(it) }, runs[i * 2]),
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
                    listOf<ProfileElement>(
                        ProfileElement.Seg(Segment(f.faces[side][i], f.faces[side][(i + 1) % f.faces[side].size])),
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
        is ProfileElement.CircleE -> null
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
        is ProfileElement.CircleE -> Vec2(1.0, 0.0)
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
    runs: List<List<ProfileElement>>,
): ThickLeg {
    if (piece !is ProfileElement.BezierE) return ThickLeg(piece, offsets, length, runs, null, null)
    val pts = GeomMath.tessellateBezier(piece.bezier)
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

/** Null when the curves span one connected graph, otherwise the refusal — by name. */
private fun disconnection(
    count: Int,
    tail: IntArray,
    head: IntArray,
    vertices: Int,
): String? {
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
    val runs = roots.map { r -> (0 until count).filter { find(tail[it]) == r }.joinToString("+") { "curve ${it + 1}" } }
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
        is ProfileElement.CircleE -> Vec2(0.0, 1.0)
    }

private fun endTangent(e: ProfileElement): Vec2 =
    when (e) {
        is ProfileElement.Seg -> (e.segment.b - e.segment.a).normalized()
        is ProfileElement.ArcE ->
            Vec2(cos(e.arc.endAngle), sin(e.arc.endAngle)).let { if (e.arc.ccw) it.perp() else -it.perp() }
        is ProfileElement.BezierE -> GeomMath.bezierTangentAt(e.bezier, 1.0).normalized()
        is ProfileElement.CircleE -> Vec2(0.0, 1.0)
    }

/** [raw] shifted by whole turns to the representative nearest [target] — see [trim]. */
private fun nearAngle(
    raw: Double,
    target: Double,
): Double {
    val twoPi = 2 * PI
    return raw + twoPi * kotlin.math.round((target - raw) / twoPi)
}
