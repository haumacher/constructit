package constructit.geom

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
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
}

/** An ordered (ideally closed) chain of segments and arcs — the bridge to 3D extrude/revolve. */
data class Profile(val elements: List<ProfileElement>)

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
