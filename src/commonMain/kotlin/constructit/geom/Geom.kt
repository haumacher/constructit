package constructit.geom

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 2D vector / point in millimetres. */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)

    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)

    operator fun times(s: Double) = Vec2(x * s, y * s)

    operator fun unaryMinus() = Vec2(-x, -y)

    fun dot(o: Vec2) = x * o.x + y * o.y

    fun cross(o: Vec2) = x * o.y - y * o.x

    fun length() = hypot(x, y)

    fun normalized(): Vec2 {
        val l = length()
        return if (l < EPS) this else Vec2(x / l, y / l)
    }

    /** Rotated +90 degrees (points to the left of this direction). */
    fun perp() = Vec2(-y, x)

    fun angle() = atan2(y, x)

    companion object {
        const val EPS = 1e-9
    }
}

/** An infinite line through [origin] with unit direction [dir]. */
data class Line(val origin: Vec2, val dir: Vec2)

/** A ray from [origin] in unit direction [dir] (t >= 0). */
data class Ray(val origin: Vec2, val dir: Vec2)

data class Segment(val a: Vec2, val b: Vec2)

data class Circle(val center: Vec2, val radius: Double)

/** Arc from [startAngle] to [endAngle] (radians), swept counter-clockwise if [ccw]. */
data class Arc(
    val center: Vec2,
    val radius: Double,
    val startAngle: Double,
    val endAngle: Double,
    val ccw: Boolean,
)

/** Ordered solution set of points (OP-1 canonical ordering). Cardinality 0, 1 or 2. */
data class PointSet(val points: List<Vec2>)

/** A 2D direction / free vector. */
data class Direction(val v: Vec2)

/**
 * A cubic Bézier curve: from [p0] to [p3], shaped by control points [p1] and [p2] (OP-15).
 *
 * A spline is a **pure function of its control points**, which is why it needs no new evaluation
 * machinery here — and because each control point may itself be *constructed*, technical geometry can
 * drive smooth geometry. Tangency at an end is likewise achieved by construction: put the first
 * control leg on the tangent line and G1 cannot be violated.
 */
data class Bezier(val p0: Vec2, val p1: Vec2, val p2: Vec2, val p3: Vec2)

/** One element of a profile chain. */
sealed interface ProfileElement {
    data class Seg(val segment: Segment) : ProfileElement

    data class ArcE(val arc: Arc) : ProfileElement

    /** A cubic Bézier as a boundary piece (OP-15) — a loop may mix these with segments and arcs. */
    data class BezierE(val bezier: Bezier) : ProfileElement

    /**
     * A whole circle as a single element — a closed boundary in its own right (a circular hole),
     * so it never has to be faked as a full-turn [ArcE] whose 0-vs-2π sweep is ambiguous.
     * [ccw] carries the orientation an arc gets from its own sweep direction (OP-14).
     */
    data class CircleE(val circle: Circle, val ccw: Boolean = true) : ProfileElement
}

/** An ordered (ideally closed) chain of segments and arcs — the bridge to 3D extrude/revolve. */
data class Profile(val elements: List<ProfileElement>)

/**
 * A **closed, oriented** chain of trimmed curve pieces: the boundary of an area (OP-14).
 *
 * Unlike [Profile] — which is any ordered chain — a `Loop` is only ever built by an op that has
 * verified consecutive pieces meet and that the last meets the first, and that has normalised the
 * traversal direction. Failing either makes the *node* invalid (OP-3), so the type itself carries
 * no validity flag.
 */
data class Loop(val elements: List<ProfileElement>)

/**
 * An area: an [outer] boundary with zero or more [holes] (OP-14). By convention [outer] runs
 * counter-clockwise and every hole clockwise, so the signed areas simply add up — which is also
 * what an extrude consumes at the 2D→3D seam (OP-17).
 */
data class Region(val outer: Loop, val holes: List<Loop>)

/**
 * Where a thick path's material sits relative to its carrier (OP-21). "Left" is the +90° side of the
 * traversal direction, so justification is defined by the carrier's own direction and needs no
 * reference to inside/outside — which a carrier that is not a closed ring does not have.
 */
enum class Justification {
    CENTER,
    LEFT,
    RIGHT,
    ;

    /** The two signed face offsets for thickness [t], in ascending order. */
    fun offsets(t: Double): List<Double> =
        when (this) {
            CENTER -> listOf(-t / 2.0, t / 2.0)
            LEFT -> listOf(0.0, t)
            RIGHT -> listOf(-t, 0.0)
        }
}

/**
 * The offset faces of a thick path (OP-21): [faces]`[side]` is one side's chain of corners — one per
 * carrier vertex, mitred at interior ones and dropped perpendicular at an open carrier's ends.
 *
 * Carrying the [legs] (origin + unit direction) and [offsets] rather than only the corner points is
 * what makes a *position along the carrier* addressable on either face without re-deriving anything:
 * see [GeomMath.facePoint]. That is the whole of what an interval feature needs.
 */
data class ThickFaces(
    val faces: List<List<Vec2>>,
    val legs: List<Line>,
    val legLengths: List<Double>,
    val offsets: List<Double>,
    val closed: Boolean,
) {
    val legCount: Int get() = legs.size
}

/** Geometry math: intersections emit ordered [PointSet]s (OP-1). */
object GeomMath {
    private const val EPS = Vec2.EPS

    /**
     * Circle-circle intersection. Ordering: index 0 is the point on the LEFT of the directed
     * line center(c1) -> center(c2); index 1 is on the right. Stable under rigid motion (OP-1).
     */
    fun intersectCC(
        c1: Circle,
        c2: Circle,
    ): PointSet {
        val d = c2.center - c1.center
        val dist = d.length()
        if (dist < EPS) return PointSet(emptyList()) // concentric / equal
        if (dist > c1.radius + c2.radius + EPS) return PointSet(emptyList()) // too far
        if (dist < abs(c1.radius - c2.radius) - EPS) return PointSet(emptyList()) // contained
        val a = (c1.radius * c1.radius - c2.radius * c2.radius + dist * dist) / (2 * dist)
        val h2 = c1.radius * c1.radius - a * a
        val dir = d * (1.0 / dist)
        val mid = c1.center + dir * a
        val h = if (h2 <= 0.0) 0.0 else sqrt(h2)
        if (h < EPS) return PointSet(listOf(mid)) // tangent
        val left = mid + dir.perp() * h
        val right = mid - dir.perp() * h
        return PointSet(listOf(left, right))
    }

    /** Line-line intersection (empty if parallel). */
    fun intersectLL(
        l1: Line,
        l2: Line,
    ): PointSet {
        val denom = l1.dir.cross(l2.dir)
        if (abs(denom) < EPS) return PointSet(emptyList())
        val t = (l2.origin - l1.origin).cross(l2.dir) / denom
        return PointSet(listOf(l1.origin + l1.dir * t))
    }

    /** Line-circle intersection, ordered along the line's own direction (OP-1). */
    fun intersectLC(
        line: Line,
        c: Circle,
    ): PointSet {
        val proj = (c.center - line.origin).dot(line.dir)
        val closest = line.origin + line.dir * proj
        val distToCenter = (c.center - closest).length()
        if (distToCenter > c.radius + EPS) return PointSet(emptyList())
        val half = sqrt(max(0.0, c.radius * c.radius - distToCenter * distToCenter))
        if (half < EPS) return PointSet(listOf(closest))
        return PointSet(listOf(line.origin + line.dir * (proj - half), line.origin + line.dir * (proj + half)))
    }

    /** Circumcentre of three points (null if collinear). */
    fun circumcenter(
        a: Vec2,
        b: Vec2,
        c: Vec2,
    ): Vec2? {
        val dd = 2 * (a.x * (b.y - c.y) + b.x * (c.y - a.y) + c.x * (a.y - b.y))
        if (abs(dd) < EPS) return null
        val a2 = a.x * a.x + a.y * a.y
        val b2 = b.x * b.x + b.y * b.y
        val c2 = c.x * c.x + c.y * c.y
        val ux = (a2 * (b.y - c.y) + b2 * (c.y - a.y) + c2 * (a.y - b.y)) / dd
        val uy = (a2 * (c.x - b.x) + b2 * (a.x - c.x) + c2 * (b.x - a.x)) / dd
        return Vec2(ux, uy)
    }

    /** Rotate a vector by [theta] radians. */
    private fun rot(
        v: Vec2,
        theta: Double,
    ): Vec2 {
        val co = kotlin.math.cos(theta)
        val si = kotlin.math.sin(theta)
        return Vec2(v.x * co - v.y * si, v.x * si + v.y * co)
    }

    /**
     * Common tangent lines of two circles. [inner] selects the crossing (internal) tangents,
     * otherwise the external ones. Returns 0 or 2 lines (order: rotation +phi then -phi).
     */
    fun commonTangents(
        c1: Circle,
        c2: Circle,
        inner: Boolean,
    ): List<Line> {
        val d = c2.center - c1.center
        val dist = d.length()
        if (dist < EPS) return emptyList()
        val u = d * (1.0 / dist)
        val k = if (inner) -(c1.radius + c2.radius) else (c2.radius - c1.radius)
        val cosPhi = k / dist
        if (cosPhi < -1.0 - 1e-12 || cosPhi > 1.0 + 1e-12) return emptyList()
        val phi = kotlin.math.acos(cosPhi.coerceIn(-1.0, 1.0))
        val result = ArrayList<Line>(2)
        for (s in intArrayOf(1, -1)) {
            val n = rot(u, s * phi) // unit normal of the tangent line
            val p = n.dot(c1.center) - c1.radius // signed offset: n . x = p
            result.add(Line(n * p, n.perp()))
        }
        return result
    }

    // ---- loops & areas (OP-14) ----

    /**
     * Tolerance for "these two trimmed pieces meet" (mm). Cut points are normally *constructed*
     * (an intersection, a projection), so they agree to within floating-point noise; this is orders
     * of magnitude looser than that and still far tighter than any real geometry.
     */
    const val JOIN_TOL = 1e-6

    /** Signed sweep of an [arc] in radians: positive counter-clockwise, in (-2π, 2π). */
    fun sweep(arc: Arc): Double {
        val twoPi = 2 * PI
        val raw = (arc.endAngle - arc.startAngle) % twoPi
        return if (arc.ccw) {
            if (raw < 0) raw + twoPi else raw
        } else {
            if (raw > 0) raw - twoPi else raw
        }
    }

    fun arcPointAt(
        arc: Arc,
        angle: Double,
    ): Vec2 = arc.center + Vec2(arc.radius * cos(angle), arc.radius * sin(angle))

    fun arcStart(arc: Arc): Vec2 = arcPointAt(arc, arc.startAngle)

    fun arcEnd(arc: Arc): Vec2 = arcPointAt(arc, arc.endAngle)

    /** Where a piece starts, following its own orientation. */
    fun startOf(e: ProfileElement): Vec2 =
        when (e) {
            is ProfileElement.Seg -> e.segment.a
            is ProfileElement.ArcE -> arcStart(e.arc)
            is ProfileElement.CircleE -> e.circle.center + Vec2(e.circle.radius, 0.0)
            is ProfileElement.BezierE -> e.bezier.p0
        }

    /** Where a piece ends, following its own orientation. */
    fun endOf(e: ProfileElement): Vec2 =
        when (e) {
            is ProfileElement.Seg -> e.segment.b
            is ProfileElement.ArcE -> arcEnd(e.arc)
            is ProfileElement.CircleE -> e.circle.center + Vec2(e.circle.radius, 0.0)
            is ProfileElement.BezierE -> e.bezier.p3
        }

    /** The same piece traversed the other way. */
    fun reverse(e: ProfileElement): ProfileElement =
        when (e) {
            is ProfileElement.Seg -> ProfileElement.Seg(Segment(e.segment.b, e.segment.a))
            is ProfileElement.ArcE -> ProfileElement.ArcE(Arc(e.arc.center, e.arc.radius, e.arc.endAngle, e.arc.startAngle, !e.arc.ccw))
            is ProfileElement.CircleE -> ProfileElement.CircleE(e.circle, !e.ccw)
            is ProfileElement.BezierE ->
                ProfileElement.BezierE(Bezier(e.bezier.p3, e.bezier.p2, e.bezier.p1, e.bezier.p0))
        }

    /**
     * Twice the signed area swept by one piece, as the line integral ∮ (x·dy − y·dx). Summing this
     * over a closed loop and halving gives the enclosed signed area — exactly, arcs included.
     */
    private fun doubleSignedArea(e: ProfileElement): Double =
        when (e) {
            is ProfileElement.Seg -> e.segment.a.cross(e.segment.b)
            is ProfileElement.ArcE -> {
                val a = e.arc
                val r = a.radius
                r * r * sweep(a) +
                    a.center.x * r * (sin(a.endAngle) - sin(a.startAngle)) -
                    a.center.y * r * (cos(a.endAngle) - cos(a.startAngle))
            }
            is ProfileElement.CircleE -> {
                val r = e.circle.radius
                (if (e.ccw) 1.0 else -1.0) * 2.0 * PI * r * r
            }
            is ProfileElement.BezierE -> {
                // Closed form of the same line integral over a cubic Bézier — exact, not sampled,
                // so a spline in a boundary costs no accuracy relative to segments and arcs.
                val b = e.bezier
                (
                    6 * b.p0.x * b.p1.y + 3 * b.p0.x * b.p2.y + b.p0.x * b.p3.y -
                        6 * b.p1.x * b.p0.y + 3 * b.p1.x * b.p2.y + 3 * b.p1.x * b.p3.y -
                        3 * b.p2.x * b.p0.y - 3 * b.p2.x * b.p1.y + 6 * b.p2.x * b.p3.y -
                        b.p3.x * b.p0.y - 3 * b.p3.x * b.p1.y - 6 * b.p3.x * b.p2.y
                ) / 10.0
            }
        }

    /** Signed area of a closed [loop]: positive when it runs counter-clockwise. */
    fun signedArea(loop: Loop): Double = loop.elements.sumOf { doubleSignedArea(it) } / 2.0

    /** The same loop traversed the other way (so its signed area flips). */
    fun reverseLoop(loop: Loop): Loop = Loop(loop.elements.reversed().map { reverse(it) })

    /** [loop] oriented counter-clockwise if [ccw], else clockwise. */
    fun orient(
        loop: Loop,
        ccw: Boolean,
    ): Loop = if ((signedArea(loop) >= 0.0) == ccw) loop else reverseLoop(loop)

    /**
     * Chain [parts] into a closed loop, in the order given.
     *
     * A piece's *stored* direction is arbitrary — a segment built from two intersections points
     * whichever way its inputs happened to be picked — so each piece after the first is flipped if
     * that is what makes it continue from the previous one. This is deterministic (the first piece
     * keeps its own direction, and the traversal is then forced) and is what lets the caller name a
     * boundary by simply clicking round it. Returns null with a reason when the chain does not close.
     */
    fun chainLoop(parts: List<ProfileElement>): Pair<Loop?, String?> {
        if (parts.isEmpty()) return null to "a loop needs at least one piece"
        if (parts.any { it is ProfileElement.CircleE }) {
            return if (parts.size == 1) {
                Loop(parts) to null
            } else {
                null to "a whole circle already closes, so it cannot be chained with other pieces"
            }
        }
        val chained = ArrayList<ProfileElement>(parts.size)
        chained.add(parts[0])
        var cursor = endOf(parts[0])
        for (i in 1 until parts.size) {
            val e = parts[i]
            val forward = (startOf(e) - cursor).length()
            val backward = (endOf(e) - cursor).length()
            val pick = if (forward <= backward) e else reverse(e)
            val gap = kotlin.math.min(forward, backward)
            if (gap > JOIN_TOL) {
                return null to "piece ${i + 1} does not meet the previous one (gap $gap mm)"
            }
            chained.add(pick)
            cursor = endOf(pick)
        }
        val closingGap = (startOf(chained[0]) - cursor).length()
        if (closingGap > JOIN_TOL) return null to "loop does not close (gap $closingGap mm)"
        return Loop(chained) to null
    }

    // ---- thick paths: an offset region around a carrier (OP-21) ----

    /**
     * The offset faces of the polyline [points] at the signed [offsets] (one per side), mitring every
     * interior corner as the intersection of the adjacent face lines and dropping a perpendicular at an
     * open carrier's two ends.
     *
     * Deliberately a *function of values*, called from inside a node's `compute`: leg directions, the
     * mitres and (for a ring) which side ends up outermost all depend on where the carrier currently is,
     * so deriving them while assembling the graph would freeze the shape the carrier had when it was
     * built. Returns null with a reason instead of throwing (OP-3): two collinear legs have parallel
     * offsets and hence no mitre at all, which is a *state* the drawing recovers from.
     */
    fun thickFaces(
        points: List<Vec2>,
        closed: Boolean,
        offsets: List<Double>,
    ): Pair<ThickFaces?, String?> {
        if (points.size < 2) return null to "a thick path needs at least two carrier points"
        if (closed && points.size < 3) return null to "a closed carrier needs at least three points"
        if (offsets.size != 2) return null to "a thick path has exactly two faces"
        if (abs(offsets[1] - offsets[0]) < EPS) return null to "a thick path needs a non-zero thickness"
        val legCount = if (closed) points.size else points.size - 1
        val legs = ArrayList<Line>(legCount)
        val lengths = ArrayList<Double>(legCount)
        for (i in 0 until legCount) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val d = b - a
            val len = d.length()
            if (len < EPS) return null to "carrier leg ${i + 1} has zero length"
            legs.add(Line(a, d * (1.0 / len)))
            lengths.add(len)
        }
        val faces = ArrayList<List<Vec2>>(2)
        for (off in offsets) {
            fun faceLine(i: Int) = Line(legs[i].origin + legs[i].dir.perp() * off, legs[i].dir)

            fun mitre(
                i: Int,
                j: Int,
            ): Vec2? = intersectLL(faceLine(i), faceLine(j)).points.firstOrNull()

            val corners = ArrayList<Vec2>(points.size)
            if (closed) {
                for (j in 0 until legCount) {
                    corners.add(mitre((j - 1 + legCount) % legCount, j) ?: return null to "corner ${j + 1} has collinear legs, so no mitre")
                }
            } else {
                corners.add(legs.first().origin + legs.first().dir.perp() * off)
                for (j in 1 until legCount) {
                    corners.add(mitre(j - 1, j) ?: return null to "corner ${j + 1} has collinear legs, so no mitre")
                }
                corners.add(legs.last().origin + legs.last().dir * lengths.last() + legs.last().dir.perp() * off)
            }
            faces.add(corners)
        }
        return ThickFaces(faces, legs, lengths, offsets, closed) to null
    }

    /**
     * Where the carrier position [dist] along leg [leg] lands on face [side] — the foot of the
     * perpendicular, which is what an interval's edge is. Pure arithmetic on the leg frame, so an
     * interval needs no construction of its own.
     */
    fun facePoint(
        f: ThickFaces,
        leg: Int,
        dist: Double,
        side: Int,
    ): Vec2 = f.legs[leg].origin + f.legs[leg].dir * dist + f.legs[leg].dir.perp() * f.offsets[side]

    /**
     * The footprint of [f] as a [Region] (OP-21): an **open** carrier gives one loop (both faces plus
     * the two end caps), a **closed** one gives the ring `Region(outer, [inner])` — which is exactly
     * OP-14's hole machinery, reached with no new value type.
     *
     * Which offset side is the outer boundary of a ring depends on the carrier's own orientation, so it
     * is decided here by comparing the enclosed areas rather than by a sign convention the caller would
     * have to keep true as the carrier is edited.
     */
    fun thickRegion(f: ThickFaces): Pair<Region?, String?> {
        if (f.closed) {
            val a = polygonLoop(f.faces[0]) ?: return null to "a face of the ring is degenerate"
            val b = polygonLoop(f.faces[1]) ?: return null to "a face of the ring is degenerate"
            val outer = if (abs(signedArea(a)) >= abs(signedArea(b))) a else b
            val inner = if (outer === a) b else a
            return Region(orient(outer, ccw = true), listOf(orient(inner, ccw = false))) to null
        }
        // one loop: out along one face, across the end cap, back along the other, across the start cap
        val ring = f.faces[1] + f.faces[0].reversed()
        val loop = polygonLoop(ring) ?: return null to "the footprint is degenerate"
        return Region(orient(loop, ccw = true), emptyList()) to null
    }

    /** A closed loop of segments through [pts], skipping repeated points; null if fewer than 3 remain. */
    private fun polygonLoop(pts: List<Vec2>): Loop? {
        val clean = ArrayList<Vec2>(pts.size)
        for (p in pts) if (clean.isEmpty() || (p - clean.last()).length() > EPS) clean.add(p)
        while (clean.size > 1 && (clean.first() - clean.last()).length() <= EPS) clean.removeAt(clean.size - 1)
        if (clean.size < 3) return null
        return Loop(clean.indices.map { ProfileElement.Seg(Segment(clean[it], clean[(it + 1) % clean.size])) })
    }

    // ---- piece-level dispatch lives here, and only here ----
    // ProfileElement is a sealed hierarchy, so the compiler lists every site that must handle a new
    // piece kind. That check is worth keeping — but it is only worth *one* set of sites, so nothing
    // outside GeomMath dispatches on ProfileElement. The single deliberate exception is a renderer
    // emitting backend-specific markup, which is not a geometric question.

    /**
     * Conservative axis-aligned bounds of one piece: for anything circular this is the box of the
     * *whole* circle, not of the swept part.
     *
     * Deliberately not tightened. Tightening would shrink the auto-viewBox of every arc-bearing SVG
     * golden, so it is a behavioural change that belongs in its own commit — not a side effect of
     * moving this code.
     */
    fun bounds(e: ProfileElement): Pair<Vec2, Vec2> =
        when (e) {
            is ProfileElement.Seg ->
                Vec2(min(e.segment.a.x, e.segment.b.x), min(e.segment.a.y, e.segment.b.y)) to
                    Vec2(max(e.segment.a.x, e.segment.b.x), max(e.segment.a.y, e.segment.b.y))
            is ProfileElement.ArcE ->
                e.arc.center - Vec2(e.arc.radius, e.arc.radius) to e.arc.center + Vec2(e.arc.radius, e.arc.radius)
            is ProfileElement.CircleE ->
                e.circle.center - Vec2(e.circle.radius, e.circle.radius) to e.circle.center + Vec2(e.circle.radius, e.circle.radius)
            is ProfileElement.BezierE -> {
                // The control polygon's box: a Bézier lies inside its own convex hull, so this is
                // conservative for the same reason the circle box is.
                val b = e.bezier
                val xs = listOf(b.p0.x, b.p1.x, b.p2.x, b.p3.x)
                val ys = listOf(b.p0.y, b.p1.y, b.p2.y, b.p3.y)
                Vec2(xs.min(), ys.min()) to Vec2(xs.max(), ys.max())
            }
        }

    /** Apply an affine map to an arc, flipping its sweep when the map reflects. */
    fun transformArc(
        arc: Arc,
        t: Affine,
    ): Arc {
        val center = t.apply(arc.center)
        val s0 = t.linear(Vec2(cos(arc.startAngle), sin(arc.startAngle)))
        val s1 = t.linear(Vec2(cos(arc.endAngle), sin(arc.endAngle)))
        val flip = t.det < 0
        return Arc(center, arc.radius * t.scale, atan2(s0.y, s0.x), atan2(s1.y, s1.x), if (flip) !arc.ccw else arc.ccw)
    }

    /** Apply an affine map to one piece, preserving its kind. */
    fun transform(
        e: ProfileElement,
        t: Affine,
    ): ProfileElement =
        when (e) {
            is ProfileElement.Seg -> ProfileElement.Seg(Segment(t.apply(e.segment.a), t.apply(e.segment.b)))
            is ProfileElement.ArcE -> ProfileElement.ArcE(transformArc(e.arc, t))
            is ProfileElement.CircleE ->
                ProfileElement.CircleE(
                    Circle(t.apply(e.circle.center), e.circle.radius * t.scale),
                    if (t.det < 0) !e.ccw else e.ccw,
                )
            is ProfileElement.BezierE -> ProfileElement.BezierE(transformBezier(e.bezier, t))
        }

    /**
     * Apply an affine map to a Bézier. Béziers are **affine invariant** — mapping the control points
     * maps the curve — so this is exact for rotate/mirror/scale alike, with no re-fitting.
     */
    fun transformBezier(
        b: Bezier,
        t: Affine,
    ): Bezier = Bezier(t.apply(b.p0), t.apply(b.p1), t.apply(b.p2), t.apply(b.p3))

    /** Point on a cubic Bézier at parameter [t] in [0,1] (de Casteljau / Bernstein basis). */
    fun bezierPointAt(
        b: Bezier,
        t: Double,
    ): Vec2 {
        val u = 1.0 - t
        val w0 = u * u * u
        val w1 = 3.0 * t * u * u
        val w2 = 3.0 * t * t * u
        val w3 = t * t * t
        return Vec2(
            w0 * b.p0.x + w1 * b.p1.x + w2 * b.p2.x + w3 * b.p3.x,
            w0 * b.p0.y + w1 * b.p1.y + w2 * b.p2.y + w3 * b.p3.y,
        )
    }

    /** Tangent direction of a cubic Bézier at [t] (the derivative; not normalised). */
    fun bezierTangentAt(
        b: Bezier,
        t: Double,
    ): Vec2 {
        val u = 1.0 - t
        return (b.p1 - b.p0) * (3.0 * u * u) + (b.p2 - b.p1) * (6.0 * u * t) + (b.p3 - b.p2) * (3.0 * t * t)
    }

    /**
     * Polyline approximation of a Bézier for rendering. A **fixed** subdivision count on purpose:
     * an adaptive one would make SVG goldens depend on curvature, and determinism is worth more here
     * than a few saved points.
     */
    fun tessellateBezier(
        b: Bezier,
        steps: Int = BEZIER_STEPS,
    ): List<Vec2> = (0..steps).map { bezierPointAt(b, it.toDouble() / steps) }

    const val BEZIER_STEPS = 24

    /**
     * Apply an affine map to a loop, keeping the orientation it had. A reflection reverses the
     * traversal direction, which would otherwise silently invert the loop's sign (OP-14).
     */
    fun transform(
        loop: Loop,
        t: Affine,
    ): Loop {
        val wasCcw = signedArea(loop) >= 0.0
        return orient(Loop(loop.elements.map { transform(it, t) }), wasCcw)
    }

    /** Axis-aligned bounding box of a set of points, or null if empty. */
    fun bbox(points: List<Vec2>): Pair<Vec2, Vec2>? {
        if (points.isEmpty()) return null
        var minX = points[0].x
        var minY = points[0].y
        var maxX = points[0].x
        var maxY = points[0].y
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        return Vec2(minX, minY) to Vec2(maxX, maxY)
    }
}
