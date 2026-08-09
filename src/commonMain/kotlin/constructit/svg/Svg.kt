package constructit.svg

import constructit.core.ArcValue
import constructit.core.BezierValue
import constructit.core.ChainValue
import constructit.core.CircleValue
import constructit.core.DirectionValue
import constructit.core.EllipseValue
import constructit.core.EllipticArcValue
import constructit.core.Evaluator
import constructit.core.FrameValue
import constructit.core.LineValue
import constructit.core.LoopValue
import constructit.core.Path3SetValue
import constructit.core.Path3Value
import constructit.core.PlaneValue
import constructit.core.Point3SetValue
import constructit.core.Point3Value
import constructit.core.PointSetValue
import constructit.core.PointValue
import constructit.core.ProfileValue
import constructit.core.RayValue
import constructit.core.RegionValue
import constructit.core.ScalarValue
import constructit.core.SectionValue
import constructit.core.SegmentValue
import constructit.core.SketchValue
import constructit.core.SolidValue
import constructit.core.Sphere3Value
import constructit.dsl.Ref
import constructit.dsl.valueOf
import constructit.geom.Bezier
import constructit.geom.Chain
import constructit.geom.Conics
import constructit.geom.GeomMath
import constructit.geom.Line
import constructit.geom.ProfileElement
import constructit.geom.Ray
import constructit.geom.Segment
import constructit.geom.Vec2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/** One drawable graph output plus its style. */
data class Drawable(val ref: Ref<*>, val stroke: String = "#1f77b4", val fill: String = "none")

/**
 * Canonical, deterministic SVG serializer (per the testing strategy in DESIGN.md):
 * fixed decimal precision, Locale.ROOT formatting, stable element order, auto viewBox.
 * Math space is y-up; screen space is y-down, so y is negated on output.
 */
object Svg {
    private const val PRECISION = 3
    private const val SCALE = 1000L // 10^PRECISION
    private const val STROKE_WIDTH = 0.5
    private const val POINT_RADIUS = 1.2

    /** Canonical fixed-precision formatter (multiplatform: no Locale / String.format). */
    private fun fmt(v: Double): String {
        val scaled = round(abs(v) * SCALE).toLong()
        val neg = v < 0 && scaled != 0L // normalize -0.000 -> 0.000
        val intPart = scaled / SCALE
        val frac = (scaled % SCALE).toString().padStart(PRECISION, '0')
        return (if (neg) "-" else "") + "$intPart.$frac"
    }

    private fun screen(v: Vec2) = Vec2(v.x, -v.y)

    /** Add bbox samples for a chain of profile / loop pieces (piece dispatch lives in GeomMath). */
    private fun sampleChain(
        elements: List<ProfileElement>,
        samples: MutableList<Vec2>,
    ) {
        for (el in elements) {
            val (lo, hi) = GeomMath.bounds(el)
            samples.add(lo)
            samples.add(hi)
        }
    }

    private fun norm2pi(a: Double): Double {
        val twoPi = 2 * kotlin.math.PI
        var r = a % twoPi
        if (r < 0) r += twoPi
        return r
    }

    fun render(
        ev: Evaluator,
        items: List<Drawable>,
        margin: Double = 6.0,
    ): String {
        // 1. gather geometry + bbox sample points (math space).
        val samples = ArrayList<Vec2>()

        data class Prepared(val kind: String, val d: Drawable, val geom: Any)
        val prepared = ArrayList<Prepared>()

        for (d in items) {
            when (val v = ev.valueOf(d.ref)) {
                is PointValue -> {
                    samples.add(v.p)
                    prepared.add(Prepared("point", d, v.p))
                }
                is SegmentValue -> {
                    samples.add(v.seg.a)
                    samples.add(v.seg.b)
                    prepared.add(Prepared("segment", d, v.seg))
                }
                is CircleValue -> {
                    val c = v.circle
                    samples.add(c.center + Vec2(c.radius, c.radius))
                    samples.add(c.center - Vec2(c.radius, c.radius))
                    prepared.add(Prepared("circle", d, c))
                }
                is ArcValue -> {
                    val a = v.arc
                    samples.add(a.center + Vec2(a.radius, a.radius))
                    samples.add(a.center - Vec2(a.radius, a.radius))
                    prepared.add(Prepared("arc", d, a))
                }
                is BezierValue -> {
                    sampleChain(listOf(ProfileElement.BezierE(v.bezier)), samples)
                    prepared.add(Prepared("chain", d, listOf(ProfileElement.BezierE(v.bezier))))
                }
                // a conic has no SVG primitive of its own here, so it rides the chain path as a polyline
                // — the same treatment a Bézier gets, and drawn from the same tessellation (OP-24)
                is EllipseValue -> {
                    sampleChain(listOf(ProfileElement.EllipseE(v.ellipse)), samples)
                    prepared.add(Prepared("chain", d, listOf<ProfileElement>(ProfileElement.EllipseE(v.ellipse))))
                }
                is EllipticArcValue -> {
                    sampleChain(listOf(ProfileElement.EllipticArcE(v.arc)), samples)
                    prepared.add(Prepared("chain", d, listOf<ProfileElement>(ProfileElement.EllipticArcE(v.arc))))
                }
                is LineValue -> prepared.add(Prepared("line", d, v.line)) // clipped later; not in bbox
                is RayValue -> prepared.add(Prepared("ray", d, v.ray))
                is ProfileValue -> {
                    sampleChain(v.profile.elements, samples)
                    prepared.add(Prepared("chain", d, v.profile.elements))
                }
                is LoopValue -> {
                    sampleChain(v.loop.elements, samples)
                    prepared.add(Prepared("chain", d, v.loop.elements))
                }
                // A cutting chain (OP-22's extension) draws as what it is: its finite pieces, plus a ray at
                // each end that clips to the view exactly as a drawn ray does — three prepared items, so the
                // unbounded half is not quietly dropped from a document that shows the bounded one.
                is ChainValue -> {
                    sampleChain(v.chain.pieces, samples)
                    prepared.add(Prepared("chain", d, v.chain.pieces))
                    (v.chain as? Chain.Open)?.let {
                        prepared.add(Prepared("ray", d, it.start))
                        prepared.add(Prepared("ray", d, it.end))
                    }
                }
                is RegionValue -> {
                    val elements = v.region.outer.elements + v.region.holes.flatMap { it.elements }
                    sampleChain(elements, samples)
                    prepared.add(Prepared("chain", d, elements))
                }
                is PointSetValue -> for (p in v.set.points) {
                    samples.add(p)
                    prepared.add(Prepared("point", d, p))
                }
                // Not drawable — spelled out rather than absorbed by an `else`, so that a new
                // Value type breaks this build until someone decides whether an export shows it.
                // An exporter silently omitting what it was handed is the failure worth preventing.
                is ScalarValue -> {}
                is DirectionValue -> {}
                // a placement frame (OP-16) is a coordinate system, not a drawn thing: it is where the
                // geometry it carries already is, so exporting it would draw nothing twice
                is FrameValue -> {}
                // 3D values (OP-17). A plane and a sketch are frames, like the placement frame above.
                // A solid *has* a 2D image, but only through a chosen projection — a view decision, not
                // an export one — so drawing one here would be this serializer inventing a camera. It
                // arrives with the 3D viewport, which owns that choice; nothing puts a solid in a 2D
                // document in the meantime.
                is PlaneValue -> {}
                is SketchValue -> {}
                is SolidValue -> {}
                // …and a height point (OP-25) is a point *off* the plane: its 2D image is its base's, which
                // this document already draws, so exporting it would draw one dot twice.
                is Point3Value -> {}
                // …and a curve in space (OP-26) has a 2D image only through a *chosen* plane to look along —
                // the active sketch space's, which the canvas knows and this serializer does not. Exactly the
                // solid's answer above, and stated rather than guessed at: projecting onto world XY would be
                // this exporter inventing a viewing direction for a curve drawn on a tilted datum.
                is Path3Value -> {}
                // …and a section is a reading of a solid: it is drawn as the working plane's context by the
                // canvas, which knows which plane it is standing on. This serializer does not.
                is SectionValue -> {}
                // …and an intersection's curves are that same reading, one dimension up (OP-26, step 6).
                is Path3SetValue -> {}
                // …and a sphere locus (OP-28) is scaffolding in space: its image needs a plane to look
                // along, which is the height point's and the space curve's answer above, and it is not
                // geometry an exported drawing is about in the first place — it is what a point in space
                // was constructed *with*.
                is Sphere3Value -> {}
                is Point3SetValue -> {}
                null -> {}
            }
        }

        if (samples.isEmpty()) samples.add(Vec2(0.0, 0.0))
        var minX = samples[0].x
        var minY = samples[0].y
        var maxX = samples[0].x
        var maxY = samples[0].y
        for (p in samples) {
            minX = min(minX, p.x)
            minY = min(minY, p.y)
            maxX = max(maxX, p.x)
            maxY = max(maxY, p.y)
        }
        // math bbox for line clipping (with margin)
        val clipMin = Vec2(minX - margin, minY - margin)
        val clipMax = Vec2(maxX + margin, maxY + margin)

        // 2. viewBox in screen space (y negated: top = -maxY).
        val vbX = minX - margin
        val vbY = -(maxY) - margin
        val vbW = (maxX - minX) + 2 * margin
        val vbH = (maxY - minY) + 2 * margin

        // 3. emit.
        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
        sb.append("viewBox=\"${fmt(vbX)} ${fmt(vbY)} ${fmt(vbW)} ${fmt(vbH)}\" ")
        sb.append("width=\"${fmt(vbW)}\" height=\"${fmt(vbH)}\">\n")

        for (p in prepared) {
            when (p.kind) {
                "point" -> {
                    val s = screen(p.geom as Vec2)
                    sb.append("  <circle cx=\"${fmt(s.x)}\" cy=\"${fmt(s.y)}\" r=\"${fmt(POINT_RADIUS)}\" fill=\"${p.d.stroke}\"/>\n")
                }
                "segment" -> {
                    val seg = p.geom as Segment
                    val a = screen(seg.a)
                    val b = screen(seg.b)
                    sb.append(lineTag(a, b, p.d.stroke))
                }
                "line" -> {
                    val clipped = clipLineToRect(p.geom as Line, clipMin, clipMax)
                    if (clipped != null) {
                        val a = screen(clipped.a)
                        val b = screen(clipped.b)
                        sb.append(lineTag(a, b, p.d.stroke))
                    }
                }
                "circle" -> {
                    val c = p.geom as constructit.geom.Circle
                    val s = screen(c.center)
                    sb.append("  <circle cx=\"${fmt(s.x)}\" cy=\"${fmt(s.y)}\" r=\"${fmt(c.radius)}\" fill=\"${p.d.fill}\" stroke=\"${p.d.stroke}\" stroke-width=\"${fmt(STROKE_WIDTH)}\"/>\n")
                }
                "arc" -> sb.append(arcTag(p.geom as constructit.geom.Arc, p.d.stroke))
                "ray" -> {
                    val clipped = clipRayToRect(p.geom as Ray, clipMin, clipMax)
                    if (clipped != null) sb.append(lineTag(screen(clipped.a), screen(clipped.b), p.d.stroke))
                }
                "chain" -> {
                    @Suppress("UNCHECKED_CAST")
                    for (el in p.geom as List<ProfileElement>) when (el) {
                        is ProfileElement.Seg -> sb.append(lineTag(screen(el.segment.a), screen(el.segment.b), p.d.stroke))
                        is ProfileElement.ArcE -> sb.append(arcTag(el.arc, p.d.stroke))
                        is ProfileElement.BezierE -> sb.append(bezierTag(el.bezier, p.d.stroke))
                        is ProfileElement.CircleE -> {
                            val s = screen(el.circle.center)
                            sb.append("  <circle cx=\"${fmt(s.x)}\" cy=\"${fmt(s.y)}\" r=\"${fmt(el.circle.radius)}\" fill=\"none\" stroke=\"${p.d.stroke}\" stroke-width=\"${fmt(STROKE_WIDTH)}\"/>\n")
                        }
                        is ProfileElement.EllipticArcE -> sb.append(polyTag(Conics.renderSample(el.arc), p.d.stroke))
                        is ProfileElement.EllipseE -> sb.append(polyTag(Conics.renderSampleWhole(el.ellipse, el.ccw), p.d.stroke))
                    }
                }
            }
        }
        sb.append("</svg>\n")
        return sb.toString()
    }

    private fun lineTag(
        a: Vec2,
        b: Vec2,
        stroke: String,
    ): String =
        "  <line x1=\"${fmt(a.x)}\" y1=\"${fmt(a.y)}\" x2=\"${fmt(b.x)}\" y2=\"${fmt(b.y)}\" stroke=\"$stroke\" stroke-width=\"${fmt(STROKE_WIDTH)}\"/>\n"

    private fun arcTag(
        arc: constructit.geom.Arc,
        stroke: String,
    ): String {
        val p0 = arc.center + Vec2(arc.radius * cos(arc.startAngle), arc.radius * sin(arc.startAngle))
        val p1 = arc.center + Vec2(arc.radius * cos(arc.endAngle), arc.radius * sin(arc.endAngle))
        val s0 = screen(p0)
        val s1 = screen(p1)
        val sweepAngle = if (arc.ccw) norm2pi(arc.endAngle - arc.startAngle) else norm2pi(arc.startAngle - arc.endAngle)
        val largeArc = if (sweepAngle > kotlin.math.PI) 1 else 0
        // We emit in screen space (y negated). SVG sweep-flag=1 is increasing screen-angle;
        // negating y flips the sense, so a math-CCW arc is sweep-flag 0 (and math-CW is 1).
        val sweepFlag = if (arc.ccw) 0 else 1
        return "  <path d=\"M ${fmt(s0.x)} ${fmt(s0.y)} A ${fmt(arc.radius)} ${fmt(arc.radius)} 0 $largeArc $sweepFlag ${fmt(s1.x)} ${fmt(s1.y)}\" fill=\"none\" stroke=\"$stroke\" stroke-width=\"${fmt(STROKE_WIDTH)}\"/>\n"
    }

    /** A Bézier is emitted natively (SVG's own cubic), so no tessellation enters the golden. */
    private fun bezierTag(
        b: Bezier,
        stroke: String,
    ): String {
        val s0 = screen(b.p0)
        val s1 = screen(b.p1)
        val s2 = screen(b.p2)
        val s3 = screen(b.p3)
        return "  <path d=\"M ${fmt(s0.x)} ${fmt(s0.y)} C ${fmt(s1.x)} ${fmt(s1.y)} ${fmt(s2.x)} ${fmt(s2.y)} ${fmt(s3.x)} ${fmt(s3.y)}\" fill=\"none\" stroke=\"$stroke\" stroke-width=\"${fmt(STROKE_WIDTH)}\"/>\n"
    }

    /**
     * A world polyline as an SVG `polyline` — what a conic draws as (OP-24). SVG has an elliptical-arc
     * path command, but it speaks the *endpoint* parameterization and would need the sweep and
     * large-arc flags derived per piece; a polyline at the renderer's own step count is the same picture
     * the canvas draws, which is the property the goldens are for.
     */
    private fun polyTag(
        pts: List<Vec2>,
        stroke: String,
    ): String {
        if (pts.size < 2) return ""
        val d = pts.joinToString(" ") { screen(it).let { s -> "${fmt(s.x)},${fmt(s.y)}" } }
        return "  <polyline points=\"$d\" fill=\"none\" stroke=\"$stroke\" stroke-width=\"${fmt(STROKE_WIDTH)}\"/>\n"
    }

    /** Liang-Barsky style clip of an infinite line to an axis-aligned rectangle. */
    private fun clipLineToRect(
        line: Line,
        lo: Vec2,
        hi: Vec2,
    ): Segment? {
        var tMin = Double.NEGATIVE_INFINITY
        var tMax = Double.POSITIVE_INFINITY
        val o = line.origin
        val dir = line.dir
        for (axis in 0..1) {
            val od = if (axis == 0) dir.x else dir.y
            val oo = if (axis == 0) o.x else o.y
            val lom = if (axis == 0) lo.x else lo.y
            val him = if (axis == 0) hi.x else hi.y
            if (abs(od) < Vec2.EPS) {
                if (oo < lom || oo > him) return null
            } else {
                val t1 = (lom - oo) / od
                val t2 = (him - oo) / od
                tMin = max(tMin, min(t1, t2))
                tMax = min(tMax, max(t1, t2))
            }
        }
        if (tMin > tMax) return null
        return Segment(o + dir * tMin, o + dir * tMax)
    }

    /** Clip a forward ray (t >= 0) to an axis-aligned rectangle. */
    private fun clipRayToRect(
        ray: Ray,
        lo: Vec2,
        hi: Vec2,
    ): Segment? {
        var tMin = 0.0
        var tMax = Double.POSITIVE_INFINITY
        val o = ray.origin
        val dir = ray.dir
        for (axis in 0..1) {
            val od = if (axis == 0) dir.x else dir.y
            val oo = if (axis == 0) o.x else o.y
            val lom = if (axis == 0) lo.x else lo.y
            val him = if (axis == 0) hi.x else hi.y
            if (abs(od) < Vec2.EPS) {
                if (oo < lom || oo > him) return null
            } else {
                val t1 = (lom - oo) / od
                val t2 = (him - oo) / od
                tMin = max(tMin, min(t1, t2))
                tMax = min(tMax, max(t1, t2))
            }
        }
        if (tMin > tMax) return null
        return Segment(o + dir * tMin, o + dir * tMax)
    }
}
