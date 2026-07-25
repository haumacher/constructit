package constructit.geom

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
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

/** One element of a profile chain. */
sealed interface ProfileElement {
    data class Seg(val segment: Segment) : ProfileElement

    data class ArcE(val arc: Arc) : ProfileElement

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
        }

    /** Where a piece ends, following its own orientation. */
    fun endOf(e: ProfileElement): Vec2 =
        when (e) {
            is ProfileElement.Seg -> e.segment.b
            is ProfileElement.ArcE -> arcEnd(e.arc)
            is ProfileElement.CircleE -> e.circle.center + Vec2(e.circle.radius, 0.0)
        }

    /** The same piece traversed the other way. */
    fun reverse(e: ProfileElement): ProfileElement =
        when (e) {
            is ProfileElement.Seg -> ProfileElement.Seg(Segment(e.segment.b, e.segment.a))
            is ProfileElement.ArcE -> ProfileElement.ArcE(Arc(e.arc.center, e.arc.radius, e.arc.endAngle, e.arc.startAngle, !e.arc.ccw))
            is ProfileElement.CircleE -> ProfileElement.CircleE(e.circle, !e.ccw)
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
