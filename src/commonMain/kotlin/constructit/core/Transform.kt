package constructit.core

import constructit.geom.Affine
import constructit.geom.Chains
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
        // …and a cutting chain (OP-22's extension) transforms like the curve it is: an affine map takes rays
        // to rays, so a mirrored chain is a chain, and the side it keeps follows the mirror with it.
        is ChainValue -> ChainValue(Chains.transform(v.chain, t))
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
        // A **point reflection of a solid** is a future extension recorded here rather than smuggled in with
        // the 2D one, and it is not the same operation: in the plane a half turn is *proper* (det = +1, it is
        // a rotation), while `−I` in space has det = −1, so reflecting a body through a point yields a
        // **mirror-image part** — a different thing to manufacture, and one that has to be said out loud
        // before it can be built by clicking.
        is PlaneValue -> throw IllegalArgumentException("a sketch plane cannot be transformed by a 2D map (OP-17)")
        // ...and a height point (OP-25) is a point in space: a 2D map has nothing to say about the axis it
        // stands on. Mirror its base and its plane instead, and the point follows by construction.
        is Point3Value -> throw IllegalArgumentException("a height point cannot be transformed by a 2D map — mirror its base (OP-25)")
        // ...and a curve in space (OP-26) is the same answer one dimension further: a 2D affine map says
        // nothing about the axis its points stand on. Mirror the points it is built through — the curve is a
        // pure function of them and follows by construction, which is the whole of the parenting rule.
        is Path3Value -> throw IllegalArgumentException("a curve in space cannot be transformed by a 2D map — mirror the points it runs through (OP-26)")
        is SketchValue -> throw IllegalArgumentException("a sketch cannot be transformed by a 2D map (OP-17)")
        is SolidValue -> throw IllegalArgumentException("a solid cannot be transformed by a 2D map (OP-17)")
        // A section is a *reading* of a solid at a plane (OP-17): what it would mean to mirror one is to
        // mirror the plane, which is the case above. Take the input first, then transform that.
        is SectionValue -> throw IllegalArgumentException("a section cannot be transformed by a 2D map — mirror its inputs instead (OP-17)")
        // …and an intersection's ordered set of curves in space (OP-26, step 6) is a reading of a solid at a
        // plane exactly as a section is, with the curve's own answer above on top of it.
        is Path3SetValue -> throw IllegalArgumentException("intersection curves cannot be transformed by a 2D map — mirror the solid and the plane instead (OP-26)")
        // …and a sphere locus (OP-28) is the same answer once more: a 2D affine map says nothing about the
        // axis its centre stands on, and there is no honest image of a sphere in a plane's own coordinates.
        // Mirror the centre — the locus is a pure function of it and of its radius, and follows by
        // construction, which is the parenting rule again.
        is Sphere3Value -> throw IllegalArgumentException("a sphere locus cannot be transformed by a 2D map — mirror its centre (OP-28)")
        // …and its ordered solution sets in space, for the reason the curves above give.
        is Point3SetValue -> throw IllegalArgumentException("points in space cannot be transformed by a 2D map — mirror the loci they came from (OP-28)")
    }
