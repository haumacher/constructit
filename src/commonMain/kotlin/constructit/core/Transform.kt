package constructit.core

import constructit.geom.Affine
import constructit.geom.Circle
import constructit.geom.Conics
import constructit.geom.Direction
import constructit.geom.GeomMath
import constructit.geom.Line
import constructit.geom.PointSet
import constructit.geom.Profile
import constructit.geom.Ray
import constructit.geom.Region
import constructit.geom.Segment

/** Apply an affine map to any geometry value, preserving its type (used by mirror/rotate/scale). */
fun transformValue(
    t: Affine,
    v: Value,
): Value =
    when (v) {
        is PointValue -> PointValue(t.apply(v.p))
        is LineValue -> LineValue(Line(t.apply(v.line.origin), t.linear(v.line.dir).normalized()))
        is RayValue -> RayValue(Ray(t.apply(v.ray.origin), t.linear(v.ray.dir).normalized()))
        is SegmentValue -> SegmentValue(Segment(t.apply(v.seg.a), t.apply(v.seg.b)))
        is CircleValue -> CircleValue(Circle(t.apply(v.circle.center), v.circle.radius * t.scale))
        is ArcValue -> ArcValue(GeomMath.transformArc(v.arc, t))
        // an affine image of an ellipse is an ellipse — exactly, for any affine map (see [Conics.transform])
        is EllipseValue -> EllipseValue(Conics.transform(v.ellipse, t))
        is EllipticArcValue -> EllipticArcValue(Conics.transform(v.arc, t))
        is BezierValue -> BezierValue(GeomMath.transformBezier(v.bezier, t))
        is DirectionValue -> DirectionValue(Direction(t.linear(v.dir.v).normalized()))
        is PointSetValue -> PointSetValue(PointSet(v.set.points.map { t.apply(it) }))
        is ProfileValue -> ProfileValue(Profile(v.profile.elements.map { GeomMath.transform(it, t) }))
        is LoopValue -> LoopValue(GeomMath.transform(v.loop, t))
        is RegionValue ->
            RegionValue(
                Region(
                    GeomMath.orient(GeomMath.transform(v.region.outer, t), ccw = true),
                    v.region.holes.map { GeomMath.orient(GeomMath.transform(it, t), ccw = false) },
                ),
            )
        is ScalarValue -> v // scalars are invariant under geometric transforms
        // A placement frame (OP-16) is not geometry: composing one with a construction transform is
        // *re-parenting* (step 3), which recomposes the frame and keeps the world output fixed — a
        // different operation from mirroring what it carries. Deliberately no rule here rather than a
        // plausible-looking wrong one; the Evaluator turns this into node invalidity (OP-3).
        is FrameValue -> throw IllegalArgumentException("a placement frame cannot be transformed (OP-16 step 3)")
        // 3D values (OP-17) live in a space this 2D map does not reach. Mirroring a plane or a solid is a
        // real operation, but it needs a 3D transform, and inventing one from a 2D affine (which axis
        // does its reflection line become?) would be a plausible-looking wrong answer. Refused, so the
        // Evaluator turns it into node invalidity (OP-3), until 3D transforms arrive with assemblies.
        is PlaneValue -> throw IllegalArgumentException("a sketch plane cannot be transformed by a 2D map (OP-17)")
        // ...and a height point (OP-25) is a point in space: a 2D map has nothing to say about the axis it
        // stands on. Mirror its base and its plane instead, and the point follows by construction.
        is Point3Value -> throw IllegalArgumentException("a height point cannot be transformed by a 2D map — mirror its base (OP-25)")
        is SketchValue -> throw IllegalArgumentException("a sketch cannot be transformed by a 2D map (OP-17)")
        is SolidValue -> throw IllegalArgumentException("a solid cannot be transformed by a 2D map (OP-17)")
        // A section is a *reading* of a solid at a plane (OP-17): what it would mean to mirror one is to
        // mirror the plane, which is the case above. Take the input first, then transform that.
        is SectionValue -> throw IllegalArgumentException("a section cannot be transformed by a 2D map — mirror its inputs instead (OP-17)")
    }
