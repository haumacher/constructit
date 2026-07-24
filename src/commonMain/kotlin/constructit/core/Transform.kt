package constructit.core

import constructit.geom.Affine
import constructit.geom.Arc
import constructit.geom.Circle
import constructit.geom.Direction
import constructit.geom.Line
import constructit.geom.PointSet
import constructit.geom.Profile
import constructit.geom.ProfileElement
import constructit.geom.Ray
import constructit.geom.Segment
import constructit.geom.Vec2
import kotlin.math.atan2

/** Apply an affine map to any geometry value, preserving its type (used by mirror/rotate/scale). */
fun transformValue(t: Affine, v: Value): Value = when (v) {
    is PointValue -> PointValue(t.apply(v.p))
    is LineValue -> LineValue(Line(t.apply(v.line.origin), t.linear(v.line.dir).normalized()))
    is RayValue -> RayValue(Ray(t.apply(v.ray.origin), t.linear(v.ray.dir).normalized()))
    is SegmentValue -> SegmentValue(Segment(t.apply(v.seg.a), t.apply(v.seg.b)))
    is CircleValue -> CircleValue(Circle(t.apply(v.circle.center), v.circle.radius * t.scale))
    is ArcValue -> ArcValue(transformArc(t, v.arc))
    is DirectionValue -> DirectionValue(Direction(t.linear(v.dir.v).normalized()))
    is PointSetValue -> PointSetValue(PointSet(v.set.points.map { t.apply(it) }))
    is ProfileValue -> ProfileValue(Profile(v.profile.elements.map { transformElement(t, it) }))
    is ScalarValue -> v // scalars are invariant under geometric transforms
}

private fun transformArc(t: Affine, arc: Arc): Arc {
    val center = t.apply(arc.center)
    val s0 = t.linear(Vec2(kotlin.math.cos(arc.startAngle), kotlin.math.sin(arc.startAngle)))
    val s1 = t.linear(Vec2(kotlin.math.cos(arc.endAngle), kotlin.math.sin(arc.endAngle)))
    val flip = t.det < 0
    return Arc(center, arc.radius * t.scale, atan2(s0.y, s0.x), atan2(s1.y, s1.x), if (flip) !arc.ccw else arc.ccw)
}

private fun transformElement(t: Affine, e: ProfileElement): ProfileElement = when (e) {
    is ProfileElement.Seg -> ProfileElement.Seg(Segment(t.apply(e.segment.a), t.apply(e.segment.b)))
    is ProfileElement.ArcE -> ProfileElement.ArcE(transformArc(t, e.arc))
}
