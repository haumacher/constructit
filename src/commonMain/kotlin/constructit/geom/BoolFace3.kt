package constructit.geom

import constructit.l10n.Msg
import constructit.l10n.Msgs
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **The faces and creases a general boolean's result keeps** — OP-31's item 4, extended by slice 5c to the
 * curved carriers item 4 cut out.
 *
 * *The rule, and it is item 4's own rule with one word struck out.* A boolean never **moves** a surface,
 * curved or not: every triangle of the result lies in a face of one of the two operands, and the only
 * emergent thing is *where that face was trimmed*. So a result face on a **cylinder** is that cylinder — the
 * one an extruded arc sweeps, the one a revolution's segment sweeps, the one a rounding's band is — and a
 * result face on a cone, a sphere or a torus is that cone, sphere or torus, carried as the very
 * [Surface3] the operand already stated. Only the trim is computed.
 *
 * *What is exact and what is fitted.* A crease is stated in this drawing's own vocabulary wherever the
 * vocabulary reaches it: plane ∩ plane is a line; plane ∩ cylinder is a circle, an ellipse or a pair of
 * rulings; plane ∩ cone a circle or an ellipse; plane ∩ sphere a circle whichever way it is turned; plane ∩
 * torus the rings a coaxial or meridian plane cuts — every one of them [Revolve3.planeCut]'s own table,
 * which is the same table a section is read through, asked here without the band's own extent. Where it does
 * not reach — two cylinders whose axes cross or are skew, a cone met askew, a torus met off its axis — the
 * crease is a **chain of cubics through points that are every one of them exact on both surfaces**
 * ([EdgeGeom.InSpace]), with [SolidEdge.fitted] carrying how far the spans between may be from the truth.
 * Nothing is ever omitted and nothing exact is ever passed off as fitted or the other way round.
 *
 * *The frame a curved face's own outline is stated in.* A planar face states its boundary in its plane's
 * `(u, v)`; a curved one states it in the surface's **own** `(θ, t)` — the turn angle, and the parameter
 * along the band's own meridian ([Surface3.meridianCurve]). A ring is a horizontal line there, a ruling a
 * vertical one, and an oblique plane's cut a sinusoid, which is exactly the shape that has no name and is
 * stated as a fitted chain with [FacePatch.fitted] saying so. The alternative — a bounding box in `(θ, t)`,
 * which is what the family alone gives — was rejected outright: it would *overstate* the face wherever the
 * trim is oblique, and a section read against it would draw a curve running off the material, which is the
 * one state ([Blend3]'s matrix rule) that must never happen.
 *
 * *Containment without topology.* The `(θ, t)` boundary of a face that wraps — a bore's whole cylinder, a
 * ring's whole meridian — is not a set of closed loops, so an even-odd ray cannot read it. It is directed
 * instead, **material to the left**, and *"is this point on the face"* is the side of the nearest boundary
 * piece (searched over its own ±2π copies in whichever coordinate wraps). That answer is a value and needs
 * no mesh, which is what lets a section, a nested boolean and a pick all read the same face the same way.
 *
 * *What still refuses, wholly and by name.* An operand face with **no** analytic carrier at all — an
 * imported mesh, a skin's strip, a sweep, an elliptic cylinder, a spline's band — is the sink OP-9 named and
 * it stands ([Msgs.refusalSectionBoolFaceNotPlane] now says exactly that). So does an operand whose named
 * faces are not its whole boundary, and a trim the two surfaces meeting along it do not determine
 * ([Msgs.refusalSectionBoolTrimNotDetermined]).
 *
 * *The ordering rule is untouched* (OP-17, OP-18): operand-major, the operand's own face order, pieces by
 * smallest canonical triangle, creases by the pair of faces they separate and then by the smallest canonical
 * vertex. No stored address moves.
 */
internal object BoolFace3 {
    /**
     * How far a result vertex may sit off the operand carrier it belongs to and still be **on** it, in
     * float32 ULPs of the mesh's own scale — item 4's own recognition tolerance, unchanged, and applied to a
     * curved carrier by the same argument: what the outline is built from is the operand's exact surface,
     * never these vertices.
     */
    const val CARRIER_ULPS = 64.0

    /** Directions whose 2D cross product is under this count as parallel — a corner two lines do not fix. */
    private const val CORNER_EPS = 1e-9

    /** Below this a run's own coordinate counts as **constant**: a ring, or a ruling. */
    private const val ISO_TOL = 1e-9

    /** What a fitted crease or a fitted face boundary is asked for, in mm (OP-31, Tier B). */
    const val FIT_TOL = 1e-4

    private const val TWO_PI = 2.0 * PI

    /** How many samples a run is read at when deciding whether it is a ring, a ruling or neither. */
    private const val ISO_SAMPLES = 16

    /** One operand face as the lookup sees it: its exact carrier and its own boundary in that carrier's frame. */
    class Carrier(
        val operand: Int,
        val face: Int,
        val name: FaceName,
        /** The exact plane, for a flat face. Null for a curved one and for a slot with no surface at all. */
        val plane: Plane3?,
        /** The face's own boundary — in the plane's `(u, v)`, or in the surface's `(θ, t)`. */
        val outline: List<ProfileElement>,
        /** The exact curved carrier, for a face that is not flat. */
        val surface: Surface3?,
    ) {
        /** The planar outline as rings, tessellated once — a **predicate**'s resolution and nothing more. */
        val rings: List<List<Vec2>> by lazy { if (plane == null) emptyList() else Project3.ringsOf(outline) }

        /** The curved carrier's own `(θ, t)` reader, built once. */
        val patch: Patch? by lazy { surface?.let { Patch(it, outline) } }
    }

    // ---- a curved carrier's own (θ, t) frame ----

    /**
     * A curved face's **own parameter frame**: the turn angle `θ` and the parameter `t` along the band's own
     * meridian, together with the trim stated in it.
     *
     * `t` is millimetres along a straight meridian (a cylinder's, a cone's) and radians about a curved one (a
     * sphere's, a torus's) — the meridian's own parameter in each case, which is what makes `(θ, t)` a chart
     * of *every* band this drawing names rather than only of the two that happen to be graphs over the axis.
     */
    class Patch(
        val surface: Surface3,
        /** The trim, directed **material to the left**; empty means the carrier's whole natural extent. */
        val outline: List<ProfileElement>,
    ) {
        val meridian: ProfileElement? = surface.meridianCurve

        /** The meridian's period, where it closes on itself (a whole ring's), else null. */
        val tPeriod: Double? = if (meridian is ProfileElement.CircleE) TWO_PI else null

        /** The meridian's own parameter interval, for a carrier that states no trim of its own. */
        val tRange: Pair<Double, Double> =
            when (val m = meridian) {
                is ProfileElement.Seg -> 0.0 to (m.segment.b - m.segment.a).length()
                is ProfileElement.ArcE -> 0.0 to abs(GeomMath.sweep(m.arc))
                is ProfileElement.CircleE -> 0.0 to TWO_PI
                else -> 0.0 to 0.0
            }

        private val rings: List<List<Vec2>> by lazy { outline.map { GeomMath.tessellatePiece(it, 1e-3) } }

        /** Where the meridian's own parameter starts, as an angle, for a curved meridian. */
        private val tStart: Double =
            when (val m = meridian) {
                is ProfileElement.ArcE -> if (m.arc.ccw) m.arc.startAngle else m.arc.endAngle
                is ProfileElement.CircleE -> 0.0
                else -> 0.0
            }

        private val tCentre: Vec2 =
            when (val m = meridian) {
                is ProfileElement.ArcE -> m.arc.center
                is ProfileElement.CircleE -> m.circle.center
                else -> Vec2(0.0, 0.0)
            }

        /** The world point at `(θ, t)` — the chart's own inverse, which every reading below is built on. */
        fun at(
            th: Double,
            t: Double,
        ): Vec3? {
            val sr =
                when (val m = meridian) {
                    is ProfileElement.Seg -> {
                        val d = m.segment.b - m.segment.a
                        val len = d.length()
                        if (len <= Vec2.EPS) return null
                        m.segment.a + d * (t / len)
                    }
                    is ProfileElement.ArcE, is ProfileElement.CircleE -> {
                        val r = if (m is ProfileElement.ArcE) m.arc.radius else (m as ProfileElement.CircleE).circle.radius
                        tCentre + Vec2(r * cos(tStart + t), r * sin(tStart + t))
                    }
                    else -> return null
                }
            return surface.world(sr.x, sr.y, th)
        }

        /** Where the world point [p] stands in this chart: `θ` in `(−π, π]`, `t` the meridian's parameter. */
        fun of(p: Vec3): Vec2? {
            val rel = p - surface.origin
            val axial = rel.dot(surface.axis)
            val rad = rel - surface.axis * axial
            val th = atan2(rad.dot(surface.binormal), rad.dot(surface.ref))
            val sr = Vec2(axial, rad.length())
            val t =
                when (val m = meridian) {
                    is ProfileElement.Seg -> {
                        val d = m.segment.b - m.segment.a
                        val len = d.length()
                        if (len <= Vec2.EPS) return null
                        (sr - m.segment.a).dot(d) / len
                    }
                    is ProfileElement.ArcE, is ProfileElement.CircleE -> {
                        val a = atan2(sr.y - tCentre.y, sr.x - tCentre.x)
                        var w = a - tStart
                        while (w < 0.0) w += TWO_PI
                        while (w >= TWO_PI) w -= TWO_PI
                        w
                    }
                    else -> return null
                }
            return Vec2(th, t)
        }

        /**
         * Whether `(θ, t)` is **on this face** — the side of the nearest boundary piece, searched over the
         * ±2π copies of whichever coordinate wraps, so a face that goes all the way round reads exactly like
         * one that does not (see [BoolFace3]'s note on containment without topology).
         */
        fun contains(q: Vec2): Boolean {
            if (outline.isEmpty()) {
                if (!surface.full) {
                    var t = q.x
                    while (t < surface.turnStart - 1e-12) t += TWO_PI
                    while (t > surface.turnStart + TWO_PI + 1e-12) t -= TWO_PI
                    if (t > surface.turnEnd + 1e-9) return false
                }
                if (tPeriod != null) return true
                return q.y >= tRange.first - 1e-9 && q.y <= tRange.second + 1e-9
            }
            var best = Double.MAX_VALUE
            var side = false
            val dys = if (tPeriod == null) listOf(0.0) else listOf(-tPeriod, 0.0, tPeriod)
            for (ring in rings) {
                for (dx in listOf(-TWO_PI, 0.0, TWO_PI)) {
                    for (dy in dys) {
                        for (i in 0 until ring.size - 1) {
                            val a = Vec2(ring[i].x + dx, ring[i].y + dy)
                            val b = Vec2(ring[i + 1].x + dx, ring[i + 1].y + dy)
                            val ab = b - a
                            val len2 = ab.dot(ab)
                            if (len2 <= 0.0) continue
                            val u = ((q - a).dot(ab) / len2).coerceIn(0.0, 1.0)
                            val foot = a + ab * u
                            val d = (q - foot).length()
                            if (d < best - 1e-12) {
                                best = d
                                side = ab.x * (q.y - a.y) - ab.y * (q.x - a.x) > 0.0
                            }
                        }
                    }
                }
            }
            return side
        }
    }

    /**
     * Whether the two carriers are **the same surface** — two coincident planes, or two bands that are the
     * same cylinder, cone, sphere or ring however each was built.
     *
     * The plane half is decided from the plane's own numbers; the curved half is decided by *standing on one
     * and asking the other*, which is a predicate about two exact surfaces and never a fit. A pin driven into
     * a bore of its own radius is the curved twin of a bar's cap standing in a plate's own face, and the body
     * has one face there in both cases.
     */
    private fun oneSurface(
        a: Carrier,
        b: Carrier,
    ): Boolean {
        val pa = a.plane
        val pb = b.plane
        if (pa != null && pb != null) {
            if (abs(abs(pa.normal.normalized().dot(pb.normal.normalized())) - 1.0) > 1e-9) return false
            return abs(pb.distanceTo(pa.origin)) <= 1e-7
        }
        val sa = a.surface ?: return false
        val sb = b.surface ?: return false
        if (abs(abs(sa.axis.dot(sb.axis)) - 1.0) > 1e-9) return false
        val ma = sa.meridianCurve ?: return false
        val mb = sb.meridianCurve ?: return false
        for ((s, m, other) in listOf(Triple(sa, ma, b), Triple(sb, mb, a))) {
            for (u in listOf(0.0, 0.5, 1.0)) {
                val sr = pointOn(m, u) ?: return false
                if (sr.y <= Geom3.WELD_TOL) return false
                if (abs(offCarrier(other, s.world(sr.x, sr.y, s.turnStart))) > 1e-7) return false
            }
        }
        return true
    }

    /** A point of the meridian [m] at parameter [u], in the frame's own `(s, r)`. */
    private fun pointOn(
        m: ProfileElement,
        u: Double,
    ): Vec2? =
        when (m) {
            is ProfileElement.Seg -> m.segment.a + (m.segment.b - m.segment.a) * u
            is ProfileElement.ArcE -> {
                val a = m.arc.startAngle + GeomMath.sweep(m.arc) * u
                m.arc.center + Vec2(m.arc.radius * cos(a), m.arc.radius * sin(a))
            }
            is ProfileElement.CircleE -> m.circle.center + Vec2(m.circle.radius * cos(TWO_PI * u), m.circle.radius * sin(TWO_PI * u))
            else -> null
        }

    /** The band's [Revolve3.Frame], so [Revolve3]'s own table can be asked of a surface that has no feature. */
    fun frameOf(s: Surface3): Revolve3.Frame =
        Revolve3.Frame(
            Vec2(1.0, 0.0),
            Vec2(0.0, 1.0),
            Vec2(0.0, 0.0),
            s.origin,
            s.axis,
            s.ref,
            s.binormal,
            s.turnStart,
            s.turnEnd,
            s.full,
        )

    // ---- the implicit form of a carrier: what a corner solve and a trace are built on ----

    /** How far [p] stands off [c]'s exact surface, signed — zero exactly on it. */
    private fun offCarrier(
        c: Carrier,
        p: Vec3,
    ): Double {
        c.plane?.let { return it.distanceTo(p) }
        val s = c.surface ?: return Double.MAX_VALUE
        val rel = p - s.origin
        val axial = rel.dot(s.axis)
        val rad = rel - s.axis * axial
        val r = rad.length()
        return when (val band = s.band) {
            is Revolve3.Band.Cylinder -> r - band.r
            is Revolve3.Band.Cone -> {
                val ta = band.tanHalf
                val ca = 1.0 / sqrt(1.0 + ta * ta)
                val sa = ta * ca
                r * ca - coneSign(band) * (axial - band.sApex) * sa
            }
            is Revolve3.Band.Sphere -> (p - (s.origin + s.axis * band.sc)).length() - band.radius
            is Revolve3.Band.Torus -> {
                val ds = axial - band.sc
                val dr = r - band.rc
                sqrt(ds * ds + dr * dr) - band.minor
            }
            is Revolve3.Band.Planar -> axial - band.s
            else -> Double.MAX_VALUE
        }
    }

    /** Which side of its apex a cone's band stands on — the branch its own axial interval names. */
    private fun coneSign(band: Revolve3.Band.Cone): Double = if ((band.s0 + band.s1) / 2.0 - band.sApex >= 0.0) 1.0 else -1.0

    /** The gradient of [offCarrier] at [p] — a unit vector wherever the surface is regular there. */
    private fun gradCarrier(
        c: Carrier,
        p: Vec3,
    ): Vec3 {
        c.plane?.let { return it.normal.normalized() }
        val s = c.surface ?: return Vec3(0.0, 0.0, 0.0)
        val rel = p - s.origin
        val axial = rel.dot(s.axis)
        val rad = rel - s.axis * axial
        val r = rad.length()
        val ru = if (r > Vec3.EPS) rad * (1.0 / r) else s.ref
        return when (val band = s.band) {
            is Revolve3.Band.Cylinder -> ru
            is Revolve3.Band.Cone -> {
                val ta = band.tanHalf
                val ca = 1.0 / sqrt(1.0 + ta * ta)
                val sa = ta * ca
                ru * ca - s.axis * (coneSign(band) * sa)
            }
            is Revolve3.Band.Sphere -> {
                val d = p - (s.origin + s.axis * band.sc)
                if (d.length() > Vec3.EPS) d.normalized() else s.axis
            }
            is Revolve3.Band.Torus -> {
                val ds = axial - band.sc
                val dr = r - band.rc
                val h = sqrt(ds * ds + dr * dr)
                if (h > Vec3.EPS) s.axis * (ds / h) + ru * (dr / h) else s.axis
            }
            is Revolve3.Band.Planar -> s.axis
            else -> Vec3(0.0, 0.0, 0.0)
        }
    }

    /** Whether this carrier states an exact surface at all — the one thing the whole assembly needs of it. */
    private fun carried(c: Carrier): Boolean = c.plane != null || c.surface != null

    // ---- solving: a corner is a triple point, a trace is a projection ----

    /** The determinant of a 3×3, written once so [solve3] reads as Cramer's rule and nothing else. */
    private fun det3(a: Array<DoubleArray>): Double =
        a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
            a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) +
            a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])

    /** `M · x = b` for a 3×3 system, or null where it is singular — two carriers tangent, three in a pencil. */
    private fun solve3(
        m: Array<Vec3>,
        b: Vec3,
    ): Vec3? {
        val a = Array(3) { doubleArrayOf(m[it].x, m[it].y, m[it].z) }
        val d = det3(a)
        if (abs(d) <= CORNER_EPS) return null
        val rhs = doubleArrayOf(b.x, b.y, b.z)
        val out = DoubleArray(3)
        for (k in 0..2) {
            val c = Array(3) { a[it].copyOf() }
            for (i in 0..2) c[i][k] = rhs[i]
            out[k] = det3(c) / d
        }
        return Vec3(out[0], out[1], out[2])
    }

    /**
     * The point where three carriers meet, nearest [seed] — Newton on the three exact implicit surfaces.
     *
     * For three **planes** the system is linear and one step is the closed form, which is why the planar
     * assembly's own numbers are untouched by this slice (the caller keeps the 2D route there anyway). For a
     * curved carrier it converges quadratically from a float32 vertex and is checked, never trusted: a
     * corner that does not land, or that lands further from the engine's own vertex than its noise, is
     * refused rather than shipped.
     */
    private fun triplePoint(
        a: Carrier,
        b: Carrier,
        c: Carrier,
        seed: Vec3,
    ): Vec3? {
        var p = seed
        repeat(24) {
            val f = Vec3(offCarrier(a, p), offCarrier(b, p), offCarrier(c, p))
            if (abs(f.x) + abs(f.y) + abs(f.z) <= 1e-12) return p
            val j = arrayOf(gradCarrier(a, p), gradCarrier(b, p), gradCarrier(c, p))
            val d = solve3(j, Vec3(-f.x, -f.y, -f.z)) ?: return null
            p += d
            if (d.length() <= 1e-14) return p
        }
        val f = Vec3(offCarrier(a, p), offCarrier(b, p), offCarrier(c, p))
        return if (abs(f.x) + abs(f.y) + abs(f.z) <= 1e-9) p else null
    }

    /**
     * [q] pulled onto the exact curve where [a] and [b] meet — the two surface equations, closed by the
     * plane through [q] square to the curve's own direction.
     *
     * This is what makes a fitted chain *"through points that are every one of them exact on both
     * surfaces"* rather than a smoothing of triangles: the guide is the engine's float32 boundary and the
     * point that comes back is the drawing's own.
     */
    private fun ontoCrease(
        a: Carrier,
        b: Carrier,
        q: Vec3,
    ): Vec3? {
        var p = q
        repeat(24) {
            val ga = gradCarrier(a, p)
            val gb = gradCarrier(b, p)
            val w = ga.cross(gb)
            if (w.length() <= 1e-12) return null
            val f = Vec3(offCarrier(a, p), offCarrier(b, p), (p - q).dot(w))
            if (abs(f.x) + abs(f.y) <= 1e-13 && abs(f.z) <= 1e-13) return p
            val d = solve3(arrayOf(ga, gb, w), Vec3(-f.x, -f.y, -f.z)) ?: return null
            p += d
        }
        return if (abs(offCarrier(a, p)) <= 1e-9 && abs(offCarrier(b, p)) <= 1e-9) p else null
    }

    // ---- the exact crease vocabulary ----

    /**
     * Every exact curve the two carriers meet in, each with the plane it lies in — or null where this
     * drawing has no name for it and the caller has to fit.
     *
     * The table is [Revolve3.planeCut]'s wherever one carrier is a plane, which is the same table a section
     * reads and is therefore one statement and not two; two **coaxial** bands meet in the rings their
     * meridians cross at; and everything else — two cylinders whose axes cross or are skew, a cone met
     * askew, a torus met off its axis — has no name and comes back null.
     */
    private fun exactCurves(
        a: Carrier,
        b: Carrier,
    ): List<Pair<Plane3, ProfileElement>>? {
        val pa = a.plane
        val pb = b.plane
        if (pa != null && pb != null) return null
        val out = ArrayList<Pair<Plane3, ProfileElement>>()
        if (pa != null && b.surface != null) {
            b.surface.meridianCurve?.let { m -> Revolve3.planeCut(frameOf(b.surface), m, pa)?.let { cs -> out.addAll(cs.map { pa to it }) } }
            out.addAll(tangentRings(b.surface, pa))
        } else if (pb != null && a.surface != null) {
            a.surface.meridianCurve?.let { m -> Revolve3.planeCut(frameOf(a.surface), m, pb)?.let { cs -> out.addAll(cs.map { pb to it }) } }
            out.addAll(tangentRings(a.surface, pb))
        } else {
            val sa = a.surface ?: return null
            val sb = b.surface ?: return null
            out.addAll(coaxialRings(sa, sb) ?: emptyList())
        }
        return out.ifEmpty { null }
    }

    /**
     * The rings where a band is **tangent** to a plane square to its own axis — the one crease a fillet
     * always makes and the one [Revolve3]'s own table cannot state.
     *
     * A rounding's band runs *tangent* onto the faces it rounds: that is what a rounding is. So a dressed
     * body used as a boolean operand meets its own neighbours along circles where a meridian **touches** a
     * line rather than crossing it, and a crossing solved as a quadratic has a double root there that
     * arithmetic loses. The touch is found by distance instead ([touchPoints]) and the circle it names is
     * exact — which is what lets a rounded rim survive the next boolean as the torus it is.
     */
    private fun tangentRings(
        s: Surface3,
        plane: Plane3,
    ): List<Pair<Plane3, ProfileElement>> {
        val m = s.meridianCurve ?: return emptyList()
        if (m !is ProfileElement.ArcE && m !is ProfileElement.CircleE) return emptyList()
        val n = plane.normal.normalized()
        val k = s.axis.dot(n)
        if (abs(abs(k) - 1.0) > 1e-9) return emptyList()
        val s0 = -plane.distanceTo(s.origin) / k
        return touchPoints(m, Line(Vec2(s0, 0.0), Vec2(0.0, 1.0)), TOUCH_TOL)
            .filter { it.y > Geom3.WELD_TOL }
            .map { plane to ProfileElement.CircleE(Circle(plane.toLocal(s.origin + s.axis * s0), it.y), true) }
    }

    /** How near a meridian has to come to a line to **touch** it, in mm — a tangency, not a crossing. */
    private const val TOUCH_TOL = 1e-7

    /**
     * Two bands **about the same axis** meet in the circles their meridians cross at — a counterbore's step,
     * a rounding's band handing over to the bore it stands in. Null when the axes are not one line, or when
     * neither meridian is straight (two arcs crossing in the half-plane is a curve pair this does not name).
     */
    private fun coaxialRings(
        a: Surface3,
        b: Surface3,
    ): List<Pair<Plane3, ProfileElement>>? {
        if (abs(abs(a.axis.dot(b.axis)) - 1.0) > 1e-9) return null
        val d = b.origin - a.origin
        if ((d - a.axis * d.dot(a.axis)).length() > 1e-7) return null
        val ma = a.meridianCurve ?: return null
        val mb = b.meridianCurve ?: return null
        // b's meridian read in a's own (s, r): the axes agree up to a sign and an origin shift
        val flip = a.axis.dot(b.axis) < 0.0
        val shift = d.dot(a.axis)
        val map = Affine(if (flip) -1.0 else 1.0, 0.0, 0.0, 1.0, shift, 0.0)
        val mbA = GeomMath.transform(mb, map)
        val hits =
            when {
                ma is ProfileElement.Seg -> crossingsWith(mbA, ma.segment)
                mbA is ProfileElement.Seg -> crossingsWith(ma, mbA.segment)
                else -> return null
            }
        if (hits.isEmpty()) return null
        return hits.mapNotNull { sr ->
            if (sr.y <= Geom3.WELD_TOL) {
                null
            } else {
                val plane = Plane3(a.origin + a.axis * sr.x, a.ref, a.binormal)
                plane to ProfileElement.CircleE(Circle(Vec2(0.0, 0.0), sr.y), true)
            }
        }
    }

    /** Where [e] crosses the straight meridian [seg], in `(s, r)` — the crossings [Revolve3] reads too. */
    private fun crossingsWith(
        e: ProfileElement,
        seg: Segment,
    ): List<Vec2> {
        val d = seg.b - seg.a
        if (d.length() <= Vec2.EPS) return emptyList()
        return touchPoints(e, Line(seg.a, d), TOUCH_TOL).filter { p ->
            val t = (p - seg.a).dot(d) / d.dot(d)
            t >= -1e-9 && t <= 1.0 + 1e-9
        }
    }

    /**
     * Where the meridian [e] meets the line [line], **touches included**: an arc that comes within [tol] of
     * a line it does not cross meets it at the foot of the perpendicular, which is the double root a
     * quadratic loses (see [tangentRings] for why this drawing meets that case on every dressed body).
     */
    private fun touchPoints(
        e: ProfileElement,
        line: Line,
        tol: Double,
    ): List<Vec2> {
        val circle =
            when (e) {
                is ProfileElement.ArcE -> Circle(e.arc.center, e.arc.radius)
                is ProfileElement.CircleE -> e.circle
                else -> return Section3.crossingsOf(e, line)
            }
        val dir = line.dir.normalized()
        if (dir.length() <= Vec2.EPS) return emptyList()
        val rel = circle.center - line.origin
        val along = rel.dot(dir)
        val foot = line.origin + dir * along
        val d = (circle.center - foot).length()
        if (d > circle.radius + tol) return emptyList()
        if (d < circle.radius - tol) return Section3.crossingsOf(e, line)
        val at = foot
        // a **tangency at an arc's own end** is the ordinary case here, not a corner one: a rounding's band
        // touches the face it runs onto exactly where its section ends, so the containment test has to admit
        // the endpoint rather than exclude it by a hair
        if (e is ProfileElement.ArcE &&
            !GeomMath.arcContains(e.arc, (at - circle.center).angle()) &&
            (at - GeomMath.startOf(e)).length() > tol &&
            (at - GeomMath.endOf(e)).length() > tol
        ) {
            return emptyList()
        }
        return listOf(at)
    }

    // ---- reading and clipping one exact curve ----

    /** The world point at parameter [u] along the piece [e] as it lies in [plane]. */
    private fun curveAt(
        plane: Plane3,
        e: ProfileElement,
        u: Double,
    ): Vec3? {
        val p =
            when (e) {
                is ProfileElement.Seg -> e.segment.a + (e.segment.b - e.segment.a) * u
                is ProfileElement.ArcE -> {
                    val a = e.arc.startAngle + GeomMath.sweep(e.arc) * u
                    e.arc.center + Vec2(e.arc.radius * cos(a), e.arc.radius * sin(a))
                }
                is ProfileElement.CircleE -> {
                    val a = (if (e.ccw) 1.0 else -1.0) * TWO_PI * u
                    e.circle.center + Vec2(e.circle.radius * cos(a), e.circle.radius * sin(a))
                }
                is ProfileElement.EllipticArcE -> Conics.pointAt(e.arc.ellipse, e.arc.startT + Conics.sweep(e.arc) * u)
                is ProfileElement.EllipseE -> Conics.pointAt(e.ellipse, (if (e.ccw) 1.0 else -1.0) * TWO_PI * u)
                is ProfileElement.BezierE -> GeomMath.bezierPointAt(e.bezier, u)
                else -> return null
            }
        return plane.toWorld(p)
    }

    /**
     * [e] cut down to the run between [from] and [to], staying on its own carrier — the piece a boolean
     * genuinely left, stated in the same words the whole curve was.
     *
     * [mid] is a point the run really passes through, and it is what decides **which way round** an arc or
     * an elliptic arc goes: the two corners alone leave that open, and scoring it once from the body is the
     * same rule an intersection's branch is chosen by (OP-1).
     */
    private fun clipTo(
        plane: Plane3,
        e: ProfileElement,
        from: Vec3,
        to: Vec3,
        mid: Vec3,
    ): ProfileElement? {
        val a = plane.toLocal(from)
        val b = plane.toLocal(to)
        val m = plane.toLocal(mid)
        return when (e) {
            is ProfileElement.Seg -> ProfileElement.Seg(Segment(a, b))
            is ProfileElement.ArcE, is ProfileElement.CircleE -> {
                val circle = if (e is ProfileElement.ArcE) Circle(e.arc.center, e.arc.radius) else (e as ProfileElement.CircleE).circle
                val s = (a - circle.center).angle()
                val t = (b - circle.center).angle()
                val mm = (m - circle.center).angle()
                val ccw = GeomMath.arcContains(Arc(circle.center, circle.radius, s, t, true), mm)
                ProfileElement.ArcE(Arc(circle.center, circle.radius, s, t, ccw))
            }
            is ProfileElement.EllipticArcE, is ProfileElement.EllipseE -> {
                val ell = if (e is ProfileElement.EllipticArcE) e.arc.ellipse else (e as ProfileElement.EllipseE).ellipse
                val t0 = Conics.paramOf(ell, a)
                val t1 = Conics.paramOf(ell, b)
                val tm = Conics.paramOf(ell, m)
                val ccw = Conics.contains(EllipticArc(ell, t0, t1, true), tm)
                ProfileElement.EllipticArcE(EllipticArc(ell, t0, t1, ccw))
            }
            else -> null
        }
    }

    // ---- the assembly ----

    /** One connected piece of one carrier, as it stands in the result: its triangles and its own index. */
    private class Piece(
        var carrier: Int,
        val tris: MutableList<Int> = ArrayList(),
    )

    /**
     * One run of a face's boundary along a single crease: which face is on the other side, the exact ends,
     * the mesh vertices they stand at (so that two runs can be ordered and a crease taken once) and the
     * engine's own boundary walk, which is the **guide** a fitted answer is projected off.
     */
    private class Run(
        val other: Int,
        val fromVertex: Int,
        val toVertex: Int,
        val from: Vec3,
        val to: Vec3,
        val guide: List<Vec3>,
        val closed: Boolean,
    )

    fun assemble(
        carriers: List<Carrier>,
        r: BoolMesh,
    ): Pair<BoolProvenance?, Msg?> {
        val mesh = r.mesh
        val n = mesh.triangles.size
        var scale = 1.0
        for (v in mesh.vertices) scale = max(scale, max(abs(v.x), max(abs(v.y), abs(v.z))))
        val tol = max(1e-6, CARRIER_ULPS * MeshCanon.F32_ULP * scale)

        // ---- 1. every triangle onto the carrier it is a piece of ----
        val onCarrier = IntArray(n)
        val hints = HashMap<Long, Int>()
        var last = -1
        for (i in 0 until n) {
            val t = mesh.triangles[i]
            val va = mesh.vertices[t.a]
            val vb = mesh.vertices[t.b]
            val vc = mesh.vertices[t.c]
            val centre = (va + vb + vc) * (1.0 / 3.0)
            val key = planeKey(va, vb, vc)
            var pick = -1
            for (h in listOf(hints[key] ?: -1, last)) {
                if (h >= 0 && sits(carriers[h], va, vb, vc, centre, tol, inside = true)) {
                    pick = h
                    break
                }
            }
            if (pick < 0) pick = scanCarriers(carriers, r.owner[i], va, vb, vc, centre, tol)
            if (pick < 0 && r.owner[i] >= 0) pick = scanCarriers(carriers, -1, va, vb, vc, centre, tol)
            if (pick < 0) return null to Msgs.refusalSectionBoolSurfaceOffCarrier()
            onCarrier[i] = pick
            hints[key] = pick
            last = pick
        }

        // ---- 2. the directed-edge map, and the pieces each carrier survives in ----
        val owner = HashMap<Long, Int>(n * 4)
        for (i in 0 until n) {
            val t = mesh.triangles[i]
            owner[edgeKey(t.a, t.b)] = i
            owner[edgeKey(t.b, t.c)] = i
            owner[edgeKey(t.c, t.a)] = i
        }
        // **Two operands may share a plane, and then the body has one face there and not two** (slice 5c).
        // A turned bar fused to a plate stands its own cap in the plate's own side face; the surface at that
        // plane is one face of the result, and the boundary between the two carriers is no crease of the
        // body at all — it is determined by neither of them, which is exactly what the corner solve then
        // could not do. So the walk below crosses freely between coplanar carriers **of different
        // operands**, and the connected piece it finds is listed under the **first** carrier it stands on;
        // the others keep their slots and say where their surface went
        // ([Msgs.refusalSectionBoolFaceCoplanar]).
        //
        // It is a **piece** rule and not a carrier rule, and that is the half that matters: a pocket's floor
        // and the bar's own top face may lie in one plane and be nowhere near each other, and each is then
        // its own piece under its own name. Only surface the body genuinely has in one connected sheet is
        // one face of it.
        val coplanar = Array(carriers.size) { BooleanArray(carriers.size) }
        for (i in carriers.indices) {
            for (j in 0 until i) {
                if (carriers[i].operand == carriers[j].operand) continue
                if (!oneSurface(carriers[i], carriers[j])) continue
                coplanar[i][j] = true
                coplanar[j][i] = true
            }
        }
        val pieceOf = IntArray(n) { -1 }
        val pieces = ArrayList<Piece>()
        for (start in 0 until n) {
            if (pieceOf[start] >= 0) continue
            val piece = Piece(onCarrier[start])
            val id = pieces.size
            pieces.add(piece)
            // triangle order is canonical, so a piece's id is a function of the mesh and never of the walk
            val stack = ArrayList<Int>()
            stack.add(start)
            pieceOf[start] = id
            while (stack.isNotEmpty()) {
                val i = stack.removeAt(stack.size - 1)
                piece.tris.add(i)
                val t = mesh.triangles[i]
                for ((from, to) in listOf(t.a to t.b, t.b to t.c, t.c to t.a)) {
                    val j = owner[edgeKey(to, from)] ?: return null to Msgs.refusalSectionBoolSurfaceOffCarrier()
                    if (pieceOf[j] >= 0) continue
                    if (onCarrier[j] != onCarrier[i] && !coplanar[onCarrier[i]][onCarrier[j]]) continue
                    pieceOf[j] = id
                    stack.add(j)
                }
            }
            piece.tris.sort()
            piece.carrier = piece.tris.minOf { onCarrier[it] }
        }
        // which carrier a slot's surface went to, where a coplanar sibling took it
        val absorbed = IntArray(carriers.size) { -1 }
        for (p in pieces) {
            for (t in p.tris) if (onCarrier[t] != p.carrier) absorbed[onCarrier[t]] = p.carrier
        }

        // ---- 3. the face list: operand-major, one slot per operand face, its pieces in canonical order ----
        val order = ArrayList<Int>()
        val slotOfPiece = IntArray(pieces.size) { -1 }
        val names = ArrayList<FaceName>()
        val slotCarrier = ArrayList<Carrier?>()
        for (c in carriers.indices) {
            val mine = pieces.indices.filter { pieces[it].carrier == c }.sortedBy { pieces[it].tris.first() }
            if (mine.isEmpty()) {
                order.add(-1 - c)
                names.add(FaceName.BoolFace(carriers[c].operand, carriers[c].face, carriers[c].name))
                slotCarrier.add(null)
                continue
            }
            for ((k, pi) in mine.withIndex()) {
                slotOfPiece[pi] = order.size
                order.add(pi)
                names.add(FaceName.BoolFace(carriers[c].operand, carriers[c].face, carriers[c].name, k))
                val base = carriers[c]
                slotCarrier.add(
                    if (base.plane != null) {
                        Carrier(base.operand, base.face, base.name, orientedPlane(mesh, pieces[pi], base.plane), emptyList(), null)
                    } else {
                        base
                    },
                )
            }
        }

        // ---- 4. every face's outline, and with it every crease ----
        val patches = ArrayList<FacePatch>(order.size)
        val runsOf = ArrayList<List<Run>>(order.size)
        for ((slot, pi) in order.withIndex()) {
            if (pi < 0) {
                val k = -1 - pi
                val c = carriers[k]
                val why =
                    if (absorbed[k] >= 0) {
                        Msgs.refusalSectionBoolFaceCoplanar(name = carriers[absorbed[k]].name.label)
                    } else {
                        Msgs.refusalSectionBoolFaceConsumed(which = if (c.operand == 0) "a" else "b")
                    }
                patches.add(FacePatch(names[slot], null, emptyList(), why))
                runsOf.add(emptyList())
                continue
            }
            val self = slotCarrier[slot] ?: return null to Msgs.refusalSectionBoolSurfaceOffCarrier()
            val built =
                outlineOf(mesh, pieces[pi], pieceOf, slotOfPiece, slotCarrier, self, owner, tol)
                    ?: return null to (
                        if (self.plane != null) {
                            Msgs.refusalSectionBoolCornerNotDetermined()
                        } else {
                            Msgs.refusalSectionBoolTrimNotDetermined(name = names[slot].label)
                        }
                    )
            val (outline, runs, fitted) = built
            if (self.plane != null) {
                patches.add(FacePatch(names[slot], self.plane, outline, null, null, fitted))
            } else {
                val surface = self.surface ?: return null to Msgs.refusalSectionBoolSurfaceOffCarrier()
                val oriented =
                    orientedTrim(mesh, pieces[pi], surface, outline)
                        ?: return null to Msgs.refusalSectionBoolTrimNotDetermined(name = names[slot].label)
                patches.add(
                    FacePatch(
                        names[slot],
                        null,
                        oriented,
                        Msgs.refusalSectionBoolFaceIsCurved(name = names[slot].label, what = surface.band.label),
                        surface,
                        fitted,
                    ),
                )
            }
            runsOf.add(runs)
        }

        // ---- 5. the creases, each taken once, ordered by the two faces they separate ----
        val creases = ArrayList<Triple<Int, Int, Run>>()
        val seen = HashSet<Long>()
        for (slot in runsOf.indices) {
            for (run in runsOf[slot]) {
                if (run.other < slot) continue
                if (run.other == slot && !seen.add(edgeKey(min(run.fromVertex, run.toVertex), max(run.fromVertex, run.toVertex)))) continue
                creases.add(Triple(slot, run.other, run))
            }
        }
        creases.sortWith(compareBy({ it.first }, { it.second }, { min(it.third.fromVertex, it.third.toVertex) }, { max(it.third.fromVertex, it.third.toVertex) }))
        val edges = ArrayList<SolidEdge>(creases.size)
        var at = 0
        while (at < creases.size) {
            var to = at
            while (to < creases.size && creases[to].first == creases[at].first && creases[to].second == creases[at].second) to++
            for (k in at until to) {
                val (i, j, run) = creases[k]
                val ci = slotCarrier[i] ?: return null to Msgs.refusalSectionBoolCornerNotDetermined()
                val cj = slotCarrier[j] ?: return null to Msgs.refusalSectionBoolCornerNotDetermined()
                val (geom, fitted) =
                    creaseGeom(ci, cj, run)
                        ?: return null to Msgs.refusalSectionBoolTrimNotDetermined(name = names[i].label)
                edges.add(
                    SolidEdge(
                        EdgeName.BoolCrease(i, j, k - at),
                        geom,
                        FacePair(names[i], names[j]),
                        null,
                        fitted,
                    ),
                )
            }
            at = to
        }
        return BoolProvenance(patches, edges) to null
    }

    /**
     * The crease between two result faces, in the world: exact where the two carriers' own vocabulary
     * reaches it, and a chain of cubics through points exact on **both** surfaces where it does not.
     */
    private fun creaseGeom(
        a: Carrier,
        b: Carrier,
        run: Run,
    ): Pair<EdgeGeom, Double?>? {
        val pa = a.plane
        val pb = b.plane
        if (pa != null && pb != null) return EdgeGeom.Straight(run.from, run.to) to null
        val mid = midOf(run)
        exactCurves(a, b)?.let { cs ->
            val pick = cs.minByOrNull { (plane, e) -> nearest(plane, e, mid) }
            if (pick != null && nearest(pick.first, pick.second, mid) <= pickTol(a, b)) {
                if (run.closed) return EdgeGeom.OnPlane(pick.first, matchWalk(pick.first, pick.second, run.guide)) to null
                val piece = clipTo(pick.first, pick.second, run.from, run.to, mid)
                if (piece != null) return EdgeGeom.OnPlane(pick.first, piece) to null
            }
        }
        val at = tracer(a, b, run) ?: return null
        val (chain, worst) = Blend3.fittedChain3(FIT_TOL, at) ?: return null
        if (chain.isEmpty()) return null
        return EdgeGeom.InSpace(chain) to worst
    }

    /** A point the run really passes through — the engine's own middle, which is what scores a branch. */
    private fun midOf(run: Run): Vec3 = run.guide[run.guide.size / 2]

    /**
     * How far [p] stands off the curve [e] as it lies in [plane] — measured **against the curve** and not
     * against a tessellation of it, which is what makes it a *pick* among candidates rather than a fit.
     *
     * A chord chain would have answered up to half a chord away from a point that is exactly on the curve
     * (`√(2·r·tol)`, a tenth of a millimetre on a 5 mm bore), and the exact candidate was then discarded in
     * favour of a fitted one on whichever rim the two tessellations happened to disagree.
     */
    private fun nearest(
        plane: Plane3,
        e: ProfileElement,
        p: Vec3,
    ): Double {
        val off = plane.distanceTo(p)
        val q = plane.toLocal(p)
        val inPlane =
            when (e) {
                is ProfileElement.Seg -> {
                    val a = e.segment.a
                    val ab = e.segment.b - a
                    val len2 = ab.dot(ab)
                    val u = if (len2 <= 0.0) 0.0 else ((q - a).dot(ab) / len2).coerceIn(0.0, 1.0)
                    (q - (a + ab * u)).length()
                }
                is ProfileElement.ArcE -> abs((q - e.arc.center).length() - e.arc.radius)
                is ProfileElement.CircleE -> abs((q - e.circle.center).length() - e.circle.radius)
                is ProfileElement.EllipticArcE -> (Conics.nearestPoint(e.arc.ellipse, q) - q).length()
                is ProfileElement.EllipseE -> (Conics.nearestPoint(e.ellipse, q) - q).length()
                else -> {
                    var best = Double.MAX_VALUE
                    for (w in GeomMath.tessellatePiece(e, 1e-4)) best = min(best, (q - w).length())
                    best
                }
            }
        return sqrt(off * off + inPlane * inPlane)
    }

    /**
     * How near a candidate has to stand to the run's own middle to be **that** run's curve, in mm — the
     * chord tolerance of whichever carrier is curved, since the middle is a mesh vertex on a chord of it.
     */
    private fun pickTol(
        a: Carrier,
        b: Carrier,
    ): Double = carrierTol(a, carrierTol(b, 1e-4))

    /**
     * How far a result vertex may sit off a **curved** carrier and still be recognised as on it, in mm.
     *
     * *Why it is not the float32 tolerance the planar half uses.* A curved face reaches the engine as a
     * **chord polygon**, so every vertex the boolean itself creates — where a plate's face cuts a bore —
     * stands where the plane meets a *chord*, up to the tessellation's own sagitta inside the true surface.
     * Sixty-four float32 ULPs is a micron; the sagitta of a 25 mm cylinder at this drawing's chord rule is
     * twenty-two of them, and reading the vertex as *off the cylinder* is how the whole provenance of a
     * fused turned part first refused.
     *
     * So the recognition tolerance for a curved carrier is **the drawing's own chord tolerance at that
     * carrier's radius**, doubled, and it is a recognition tolerance and nothing else: what the outline and
     * the creases are built from is the exact surface, never these vertices. Its stated limit is that two
     * curved carriers standing closer together than their own chords cannot be told apart by position — and
     * that is why the search takes the **nearest** carrier rather than the first ([scanCarriers]).
     */
    private fun carrierTol(
        c: Carrier,
        base: Double,
    ): Double {
        val s = c.surface ?: return base
        val reach =
            when (val band = s.band) {
                is Revolve3.Band.Cylinder -> GeomMath.effectiveTol(band.r)
                is Revolve3.Band.Cone -> GeomMath.effectiveTol(max(abs(band.s0 - band.sApex), abs(band.s1 - band.sApex)) * band.tanHalf)
                is Revolve3.Band.Sphere -> GeomMath.effectiveTol(band.radius) * 2.0
                is Revolve3.Band.Torus -> GeomMath.effectiveTol(band.rc + band.minor) + GeomMath.effectiveTol(band.minor)
                else -> 0.0
            }
        return max(base, 2.0 * reach)
    }

    /**
     * The run read as a **curve**: the engine's boundary walk pulled onto the exact curve the two carriers
     * meet in, parameterised by the walk's own arc length.
     */
    private fun tracer(
        a: Carrier,
        b: Carrier,
        run: Run,
    ): ((Double) -> Vec3?)? {
        val guide = run.guide
        if (guide.size < 2) return null
        val cum = DoubleArray(guide.size)
        for (i in 1 until guide.size) cum[i] = cum[i - 1] + (guide[i] - guide[i - 1]).length()
        val total = cum[guide.size - 1]
        if (total <= Geom3.WELD_TOL) return null
        return { u ->
            val s = (u.coerceIn(0.0, 1.0)) * total
            var k = 0
            while (k < guide.size - 2 && cum[k + 1] < s) k++
            val span = cum[k + 1] - cum[k]
            val f = if (span <= 0.0) 0.0 else (s - cum[k]) / span
            ontoCrease(a, b, guide[k] + (guide[k + 1] - guide[k]) * f)
        }
    }

    // ---- one face's own boundary ----

    /**
     * One face's boundary **in its own frame**, the runs it is made of, and the tolerance anything fitted in
     * it reached — or null where the carriers meeting at a corner do not fix it.
     *
     * The whole point is unchanged from item 4: **the mesh's coordinates are not used**. The mesh says only
     * *which* faces are on the other side of each boundary edge and in what order; the geometry is then the
     * exact curve where this carrier meets that neighbour, clipped at corners that are exact triple points
     * of three carriers. The mesh vertex is looked at as a **check** and as the *guide* a fitted run is
     * projected off, both of which are stated where they happen.
     */
    private fun outlineOf(
        mesh: Mesh3,
        piece: Piece,
        pieceOf: IntArray,
        slotOfPiece: IntArray,
        slotCarrier: List<Carrier?>,
        self: Carrier,
        owner: Map<Long, Int>,
        tol: Double,
    ): Triple<List<ProfileElement>, List<Run>, Double?>? {
        val mine = piece.tris.toHashSet()
        val out = HashMap<Int, MutableList<IntArray>>()
        for (i in piece.tris) {
            val t = mesh.triangles[i]
            for ((from, to) in listOf(t.a to t.b, t.b to t.c, t.c to t.a)) {
                val twin = owner[edgeKey(to, from)] ?: return null
                if (twin in mine) continue
                out.getOrPut(from) { ArrayList() }.add(intArrayOf(to, slotOfPiece[pieceOf[twin]]))
            }
        }
        for (v in out.values) v.sortWith(compareBy({ it[0] }, { it[1] }))
        val pending = HashMap<Int, MutableList<IntArray>>()
        for ((k, v) in out) pending[k] = ArrayList(v)

        val outline = ArrayList<ProfileElement>()
        val runs = ArrayList<Run>()
        var fitted: Double? = null
        val starts = out.keys.sorted()
        for (first in starts) {
            while (!pending[first].isNullOrEmpty()) {
                val loop = ArrayList<IntArray>()
                var at = first
                while (true) {
                    val here = pending[at] ?: return null
                    if (here.isEmpty()) break
                    val step = here.removeAt(0)
                    loop.add(intArrayOf(at, step[0], step[1]))
                    at = step[0]
                    if (at == first) break
                }
                if (at != first || loop.size < 3) return null
                val made = runsOfLoop(mesh, loop, slotCarrier, self, tol) ?: return null
                outline.addAll(made.second)
                runs.addAll(made.first)
                made.third?.let { w -> fitted = max(fitted ?: 0.0, w) }
            }
        }
        if (runs.isEmpty()) return null
        return Triple(outline, runs, fitted)
    }

    /** One closed boundary loop as exact runs and the pieces they draw — see [outlineOf] for the argument. */
    private fun runsOfLoop(
        mesh: Mesh3,
        loop: List<IntArray>,
        slotCarrier: List<Carrier?>,
        self: Carrier,
        tol: Double,
    ): Triple<List<Run>, List<ProfileElement>, Double?>? {
        val m = loop.size
        // start the walk at a run boundary, and at the one with the smallest canonical vertex, so which
        // corner a run begins at is a function of the mesh and not of where the chain happened to start
        var head = -1
        for (i in 0 until m) {
            if (loop[i][2] == loop[(i + m - 1) % m][2]) continue
            if (head < 0 || loop[i][0] < loop[head][0]) head = i
        }
        // **a loop with one neighbour all the way round is a closed crease** and has no corner at all — a
        // bore's rim on the face it opens in, a ring where a band hands over. Item 4 could not have one
        // (three planes never make one) and refused the loop; a curved carrier makes it ordinary.
        if (head < 0) return closedLoop(mesh, loop, slotCarrier, self)
        val groups = ArrayList<IntArray>()
        val walks = ArrayList<List<Int>>()
        var i = 0
        while (i < m) {
            val a = loop[(head + i) % m]
            var j = i + 1
            while (j < m && loop[(head + j) % m][2] == a[2]) j++
            groups.add(intArrayOf(a[2], a[0], loop[(head + j - 1) % m][1]))
            val walk = ArrayList<Int>()
            walk.add(a[0])
            for (k in i until j) walk.add(loop[(head + k) % m][1])
            walks.add(walk)
            i = j
        }
        val neighbours = groups.map { slotCarrier.getOrNull(it[0]) ?: return null }
        if (neighbours.any { !carried(it) }) return null
        // every corner is where **three** carriers meet: this face and the neighbours on either side of it
        val corners = ArrayList<Vec3>(groups.size)
        val g = groups.size
        for (k in groups.indices) {
            val prev = neighbours[k]
            val next = neighbours[(k + 1) % g]
            val vertex = mesh.vertices[groups[(k + 1) % g][1]]
            val p =
                if (g < 2) {
                    return null
                } else if (self.plane != null && prev.plane != null && next.plane != null) {
                    // the planar route, kept verbatim so that item 4's own numbers do not move a bit
                    val la = creaseLine(self.plane, prev.plane) ?: return null
                    val lb = creaseLine(self.plane, next.plane) ?: return null
                    self.plane.toWorld(cross2(la, lb) ?: return null)
                } else {
                    triplePoint(self, prev, next, vertex) ?: return null
                }
            // a corner on a **curved** carrier stands where the engine cut a chord, so the check is the
            // chord's own tolerance there rather than float32's (see [carrierTol])
            val slack = 64.0 * tol + carrierTol(self, 0.0) + carrierTol(prev, 0.0) + carrierTol(next, 0.0)
            if ((p - vertex).length() > slack) return null
            corners.add(p)
        }
        val runs = ArrayList<Run>(groups.size)
        val drawn = ArrayList<ProfileElement>(groups.size)
        var fitted: Double? = null
        for (k in groups.indices) {
            val from = corners[(k + g - 1) % g]
            val to = corners[k]
            val run = Run(groups[k][0], groups[k][1], groups[k][2], from, to, walks[k].map { mesh.vertices[it] }, false)
            runs.add(run)
            val (piece, worst) = trimPiece(self, neighbours[k], run) ?: return null
            drawn.addAll(piece)
            worst?.let { w -> fitted = max(fitted ?: 0.0, w) }
        }
        return Triple(runs, drawn, fitted)
    }

    /** A boundary loop that faces **one** neighbour all the way round — a closed crease, and no corners. */
    private fun closedLoop(
        mesh: Mesh3,
        loop: List<IntArray>,
        slotCarrier: List<Carrier?>,
        self: Carrier,
    ): Triple<List<Run>, List<ProfileElement>, Double?>? {
        val other = slotCarrier.getOrNull(loop[0][2]) ?: return null
        if (!carried(other)) return null
        val walk = ArrayList<Int>()
        walk.add(loop[0][0])
        for (e in loop) walk.add(e[1])
        val start = mesh.vertices[loop[0][0]]
        val exact = ontoCrease(self, other, start) ?: start
        val run =
            Run(
                loop[0][2],
                loop.minOf { min(it[0], it[1]) },
                loop.maxOf { max(it[0], it[1]) },
                exact,
                exact,
                walk.map { mesh.vertices[it] },
                true,
            )
        val (piece, worst) = trimPiece(self, other, run) ?: return null
        return Triple(listOf(run), piece, worst)
    }

    /**
     * One run of a face's own boundary, **in that face's frame**: a piece of the exact crease for a planar
     * face, and the same curve read in `(θ, t)` for a curved one.
     *
     * A ring and a ruling come out as straight runs of the chart and are exact; everything else — the
     * sinusoid an oblique plane cuts a cylinder in, the curve two crossing cylinders leave on each other —
     * is a chain of cubics through points exact on both surfaces, and the tolerance it *reached* is what
     * comes back (OP-31, slice 5a's own correction).
     */
    private fun trimPiece(
        self: Carrier,
        other: Carrier,
        run: Run,
    ): Pair<List<ProfileElement>, Double?>? {
        val plane = self.plane
        if (plane != null) {
            val op = other.plane
            if (op != null) return listOf(ProfileElement.Seg(Segment(plane.toLocal(run.from), plane.toLocal(run.to)))) to null
            val mid = midOf(run)
            exactCurves(self, other)?.let { cs ->
                val pick =
                    cs.filter { abs(it.first.distanceTo(plane.origin)) <= 1e-7 || sameLevel(it.first, plane) }
                        .minByOrNull { (pl, e) -> nearest(pl, e, mid) }
                if (pick != null && nearest(pick.first, pick.second, mid) <= pickTol(self, other)) {
                    val inPlane = reframe(pick.first, pick.second, plane)
                    if (inPlane != null) {
                        if (run.closed) return listOf(matchWalk(plane, inPlane, run.guide)) to null
                        clipTo(plane, inPlane, run.from, run.to, mid)?.let { return listOf(it) to null }
                    }
                }
            }
            val at = tracer(self, other, run) ?: return null
            val (chain, worst) = Blend3.fittedChain(FIT_TOL) { u -> at(u)?.let { plane.toLocal(it) } } ?: return null
            if (chain.isEmpty()) return null
            return chain to worst
        }
        val patch = self.patch ?: return null
        return chartPiece(patch, self, other, run)
    }

    /** Whether the two planes are the same plane — the crease's own plane read back as this face's. */
    private fun sameLevel(
        a: Plane3,
        b: Plane3,
    ): Boolean = abs(abs(a.normal.normalized().dot(b.normal.normalized())) - 1.0) <= 1e-9 && abs(b.distanceTo(a.origin)) <= 1e-7

    /** [e], stated in [plane]'s own `(u, v)` instead of [from]'s — the same curve, one frame over. */
    private fun reframe(
        from: Plane3,
        e: ProfileElement,
        plane: Plane3,
    ): ProfileElement? {
        if (from == plane) return e
        val o = plane.toLocal(from.origin)
        val ux = plane.toLocal(from.origin + from.u) - o
        val uy = plane.toLocal(from.origin + from.v) - o
        if (abs(ux.length() - 1.0) > 1e-9 || abs(uy.length() - 1.0) > 1e-9) return null
        return GeomMath.transform(e, Affine(ux.x, ux.y, uy.x, uy.y, o.x, o.y))
    }

    /**
     * One run read in a curved face's own `(θ, t)` chart: a **ring** where `t` holds still, a **ruling**
     * where `θ` does, and a fitted chain otherwise.
     *
     * Both isolines are read off the exact curve at [ISO_SAMPLES] stations rather than asserted from the
     * family, because the same family gives both depending on how the neighbour stands — a plane square to a
     * cylinder's axis cuts a ring and one parallel to it cuts two rulings.
     */
    private fun chartPiece(
        patch: Patch,
        self: Carrier,
        other: Carrier,
        run: Run,
    ): Pair<List<ProfileElement>, Double?>? {
        val at = exactAt(self, other, run) ?: tracer(self, other, run) ?: return null
        val guideTh = DoubleArray(run.guide.size)
        var prev = 0.0
        for ((i, q) in run.guide.withIndex()) {
            val c = patch.of(q) ?: return null
            guideTh[i] = if (i == 0) c.x else unwrap(c.x, prev)
            prev = guideTh[i]
        }
        val guideT = DoubleArray(run.guide.size)
        if (patch.tPeriod != null) {
            var p = 0.0
            for ((i, q) in run.guide.withIndex()) {
                val c = patch.of(q) ?: return null
                guideT[i] = if (i == 0) c.y else unwrapBy(c.y, p, patch.tPeriod)
                p = guideT[i]
            }
        }

        fun chart(u: Double): Vec2? {
            val p = at(u) ?: return null
            val c = patch.of(p) ?: return null
            val k = ((u.coerceIn(0.0, 1.0)) * (run.guide.size - 1)).toInt().coerceIn(0, run.guide.size - 1)
            val th = unwrap(c.x, guideTh[k])
            val t = if (patch.tPeriod == null) c.y else unwrapBy(c.y, guideT[k], patch.tPeriod)
            return Vec2(th, t)
        }
        val samples = (0..ISO_SAMPLES).mapNotNull { chart(it.toDouble() / ISO_SAMPLES) }
        if (samples.size < ISO_SAMPLES + 1) return null
        val a = samples.first()
        val b = samples.last()
        val tSpread = samples.maxOf { abs(it.y - a.y) }
        val thSpread = samples.maxOf { abs(it.x - a.x) }
        if (tSpread <= ISO_TOL) {
            val end = if (run.closed) Vec2(a.x + wholeTurn(samples), a.y) else Vec2(b.x, a.y)
            return listOf(ProfileElement.Seg(Segment(Vec2(a.x, a.y), end))) to null
        }
        if (thSpread <= ISO_TOL) {
            val end = if (run.closed) Vec2(a.x, a.y + wholeMeridian(samples, patch)) else Vec2(a.x, b.y)
            return listOf(ProfileElement.Seg(Segment(Vec2(a.x, a.y), end))) to null
        }
        val (chain, worst) = Blend3.fittedChain(FIT_TOL) { u -> chart(u) } ?: return null
        if (chain.isEmpty()) return null
        return chain to worst
    }

    /** A closed run's own turn in `θ` — plus or minus a whole circle, which way the walk went. */
    private fun wholeTurn(samples: List<Vec2>): Double = if (samples.last().x >= samples.first().x) TWO_PI else -TWO_PI

    /** A closed run's own turn in `t`, for a meridian that closes on itself. */
    private fun wholeMeridian(
        samples: List<Vec2>,
        patch: Patch,
    ): Double {
        val p = patch.tPeriod ?: (patch.tRange.second - patch.tRange.first)
        return if (samples.last().y >= samples.first().y) p else -p
    }

    /** The exact curve of a run as a parameterisation, or null where the vocabulary has no name for it. */
    private fun exactAt(
        self: Carrier,
        other: Carrier,
        run: Run,
    ): ((Double) -> Vec3?)? {
        val mid = midOf(run)
        val cs = exactCurves(self, other) ?: return null
        val pick = cs.minByOrNull { (plane, e) -> nearest(plane, e, mid) } ?: return null
        if (nearest(pick.first, pick.second, mid) > pickTol(self, other)) return null
        val piece =
            if (run.closed) {
                matchWalk(pick.first, pick.second, run.guide)
            } else {
                clipTo(pick.first, pick.second, run.from, run.to, mid) ?: return null
            }
        return { u -> curveAt(pick.first, piece, u) }
    }

    /**
     * A **closed** run's own curve turned the way the engine's boundary walked it — which is the whole of
     * the material-left convention (OP-14) where there is no corner to fix a direction.
     *
     * Without it a bore's rim comes back as the *outer* winding of the face it opens in and the face's own
     * boundary then encloses the hole twice: a section through the plate draws material where the hole is,
     * which is the one state that must never happen and which is how this was found.
     */
    private fun matchWalk(
        plane: Plane3,
        e: ProfileElement,
        guide: List<Vec3>,
    ): ProfileElement {
        var area = 0.0
        val poly = guide.map { plane.toLocal(it) }
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            area += a.x * b.y - b.x * a.y
        }
        val walked = area > 0.0
        val own =
            when (e) {
                is ProfileElement.CircleE -> e.ccw
                is ProfileElement.EllipseE -> e.ccw
                is ProfileElement.ArcE -> GeomMath.sweep(e.arc) > 0.0
                is ProfileElement.EllipticArcE -> Conics.sweep(e.arc) > 0.0
                else -> return e
            }
        return if (own == walked) e else (reversedPiece(e) ?: e)
    }

    /** [x] moved by whole turns to lie within half a turn of [near]. */
    private fun unwrap(
        x: Double,
        near: Double,
    ): Double = unwrapBy(x, near, TWO_PI)

    /** [x] moved by whole periods to lie within half a period of [near]. */
    private fun unwrapBy(
        x: Double,
        near: Double,
        period: Double,
    ): Double = x - period * round((x - near) / period)

    /**
     * The trim turned so the face's material lies to the **left** of it — the one convention
     * [Patch.contains] reads, established once against a triangle the face genuinely has rather than
     * assumed to survive the chart.
     */
    private fun orientedTrim(
        mesh: Mesh3,
        piece: Piece,
        surface: Surface3,
        outline: List<ProfileElement>,
    ): List<ProfileElement>? {
        if (outline.isEmpty()) return outline
        val t = mesh.triangles[piece.tris.first()]
        val centre = (mesh.vertices[t.a] + mesh.vertices[t.b] + mesh.vertices[t.c]) * (1.0 / 3.0)
        val inside = Patch(surface, outline).of(centre) ?: return null
        if (Patch(surface, outline).contains(inside)) return outline
        val flipped = outline.reversed().map { reversedPiece(it) ?: return null }
        return if (Patch(surface, flipped).contains(inside)) flipped else null
    }

    /** One boundary piece walked the other way — the same curve, the other direction. */
    private fun reversedPiece(e: ProfileElement): ProfileElement? =
        when (e) {
            is ProfileElement.Seg -> ProfileElement.Seg(Segment(e.segment.b, e.segment.a))
            is ProfileElement.ArcE -> ProfileElement.ArcE(Arc(e.arc.center, e.arc.radius, e.arc.endAngle, e.arc.startAngle, !e.arc.ccw))
            is ProfileElement.CircleE -> ProfileElement.CircleE(e.circle, !e.ccw)
            is ProfileElement.EllipticArcE -> ProfileElement.EllipticArcE(EllipticArc(e.arc.ellipse, e.arc.endT, e.arc.startT, !e.arc.ccw))
            is ProfileElement.EllipseE -> ProfileElement.EllipseE(e.ellipse, !e.ccw)
            is ProfileElement.BezierE -> ProfileElement.BezierE(Bezier(e.bezier.p3, e.bezier.p2, e.bezier.p1, e.bezier.p0))
            else -> null
        }

    // ---- the lookup, unchanged in its argument and widened to a curved carrier ----

    /**
     * Whether [c] carries the triangle `(va, vb, vc)`: all three corners on its surface within [tol], and —
     * when [inside] is asked — the triangle's own centre within that face's extent.
     *
     * The extent test is what tells two faces **on one surface** apart: two straight pieces of one profile
     * lying on the same line, and now two pieces of one cylinder a plate has cut a pin into. It can do so
     * because a boolean only ever trims a face — a surviving piece lies inside the face it came from, never
     * outside it.
     */
    private fun sits(
        c: Carrier,
        va: Vec3,
        vb: Vec3,
        vc: Vec3,
        centre: Vec3,
        tol: Double,
        inside: Boolean,
    ): Boolean {
        val plane = c.plane
        if (plane != null) {
            if (abs(plane.distanceTo(va)) > tol) return false
            if (abs(plane.distanceTo(vb)) > tol) return false
            if (abs(plane.distanceTo(vc)) > tol) return false
            if (!inside || c.rings.isEmpty()) return true
            return RegionBool.contains(c.rings, plane.toLocal(centre))
        }
        c.surface ?: return false
        val ct = carrierTol(c, tol)
        if (abs(offCarrier(c, va)) > ct) return false
        if (abs(offCarrier(c, vb)) > ct) return false
        if (abs(offCarrier(c, vc)) > ct) return false
        if (!inside) return true
        val patch = c.patch ?: return false
        // the centre of a chord triangle stands **inside** the surface by its own sag, so it is put back on
        // the surface before the chart is asked — one Newton step, which is exact for every band here
        val g = gradCarrier(c, centre)
        val gg = g.dot(g)
        val on = if (gg <= 1e-18) centre else centre - g * (offCarrier(c, centre) / gg)
        val q = patch.of(on) ?: return false
        return patch.contains(q)
    }

    /**
     * The carrier a triangle belongs to, searched in the face list's own order so the answer is a function
     * of that order and never of the search: the first carrier whose extent **contains** the triangle wins,
     * and a carrier that only shares the surface is the fallback.
     */
    private fun scanCarriers(
        carriers: List<Carrier>,
        only: Int,
        va: Vec3,
        vb: Vec3,
        vc: Vec3,
        centre: Vec3,
        tol: Double,
    ): Int {
        var any = -1
        var best = -1
        var bestOff = Double.MAX_VALUE
        for (i in carriers.indices) {
            val c = carriers[i]
            if (only >= 0 && c.operand != only) continue
            if (!sits(c, va, vb, vc, centre, tol, inside = false)) continue
            if (any < 0) any = i
            if (!sits(c, va, vb, vc, centre, tol, inside = true)) continue
            val off = max(abs(offCarrier(c, va)), max(abs(offCarrier(c, vb)), abs(offCarrier(c, vc))))
            if (off < bestOff - 1e-12) {
                bestOff = off
                best = i
            }
        }
        return if (best >= 0) best else any
    }

    /**
     * [carrier] turned so its normal points **out of the result's material** — the operand plane itself
     * where the operand's material survived, and the flipped one where a subtraction turned the face into a
     * wall of the cavity it cut.
     */
    private fun orientedPlane(
        mesh: Mesh3,
        piece: Piece,
        carrier: Plane3,
    ): Plane3 {
        val n = carrier.normal.normalized()
        var s = 0.0
        for (i in piece.tris) {
            val t = mesh.triangles[i]
            val a = mesh.vertices[t.a]
            s += (mesh.vertices[t.b] - a).cross(mesh.vertices[t.c] - a).dot(n)
        }
        return if (s >= 0.0) carrier else carrier.flipped()
    }

    /** Where [other] crosses [plane], in [plane]'s own (u, v): `A·x + B·y = C`, or null when parallel. */
    private fun creaseLine(
        plane: Plane3,
        other: Plane3,
    ): DoubleArray? {
        val n = other.normal.normalized()
        val a = n.dot(plane.u)
        val b = n.dot(plane.v)
        if (a * a + b * b < CORNER_EPS) return null
        return doubleArrayOf(a, b, n.dot(other.origin - plane.origin))
    }

    /** Where two in-plane lines cross, or null where they do not fix a point. */
    private fun cross2(
        p: DoubleArray,
        q: DoubleArray,
    ): Vec2? {
        val det = p[0] * q[1] - q[0] * p[1]
        if (abs(det) < CORNER_EPS) return null
        return Vec2((p[2] * q[1] - q[2] * p[1]) / det, (p[0] * q[2] - q[0] * p[2]) / det)
    }

    /** A triangle's own plane, rounded to a lattice — a *hint* key only, never a decision ([sits] decides). */
    private fun planeKey(
        va: Vec3,
        vb: Vec3,
        vc: Vec3,
    ): Long {
        val n = (vb - va).cross(vc - va)
        val len = n.length()
        val u = if (len <= Vec3.EPS) n else n * (1.0 / len)
        val s = if (u.x < 0.0 || (u.x == 0.0 && (u.y < 0.0 || (u.y == 0.0 && u.z < 0.0)))) -1.0 else 1.0
        val q = 1e-4
        var h = 1469598103934665603L
        for (x in listOf(u.x * s, u.y * s, u.z * s, u.dot(va) * s)) {
            h = (h xor round(x / q).toLong()) * 1099511628211L
        }
        return h
    }

    private fun edgeKey(
        a: Int,
        b: Int,
    ): Long = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)

    // ---- reading a curved result face: the cut, and the sketch space that declines ----

    /**
     * A **curved** face of a general boolean's result, cut by a plane: the exact curves
     * [Revolve3.planeCut]'s table gives, clipped to the face's own trim, and the chart's own marching where
     * the table has no name for the curve (flagged, OP-15).
     */
    fun cutPatch(
        patch: FacePatch,
        cut: Plane3,
    ): Pair<List<ProfileElement>, List<List<Vec2>>>? {
        val surface = patch.surface ?: return null
        val chart = Patch(surface, patch.outline)
        val meridian = surface.meridianCurve ?: return null
        val exact = Revolve3.planeCut(frameOf(surface), meridian, cut)
        if (exact != null) {
            val kept = ArrayList<ProfileElement>()
            var whole = true
            for (e in exact) {
                for ((lo, hi) in onPatch(chart, cut, e)) {
                    if (lo <= 0.0 && hi >= 1.0) {
                        kept.add(e)
                        continue
                    }
                    val piece = subPiece(cut, e, lo, hi)
                    if (piece == null) whole = false else kept.add(piece)
                }
            }
            if (whole) return kept to emptyList()
        }
        return emptyList<ProfileElement>() to marched(chart, cut)
    }

    /** The parameter intervals of [e] that lie on the face — a predicate sampled, never a curve fitted. */
    private fun onPatch(
        chart: Patch,
        plane: Plane3,
        e: ProfileElement,
    ): List<Pair<Double, Double>> {
        val steps = 256
        val on = BooleanArray(steps + 1)
        for (i in 0..steps) {
            val p = curveAt(plane, e, i.toDouble() / steps)
            on[i] = p != null && chart.of(p)?.let { chart.contains(it) } == true
        }
        val out = ArrayList<Pair<Double, Double>>()
        var i = 0
        while (i <= steps) {
            if (!on[i]) {
                i++
                continue
            }
            var j = i
            while (j < steps && on[j + 1]) j++
            val lo = if (i == 0) 0.0 else refine(chart, plane, e, (i - 1).toDouble() / steps, i.toDouble() / steps)
            val hi = if (j == steps) 1.0 else refine(chart, plane, e, (j + 1).toDouble() / steps, j.toDouble() / steps)
            if (hi > lo) out.add(lo to hi)
            i = j + 1
        }
        return out
    }

    /** Where the face's own boundary crosses the curve, bisected between an outside and an inside sample. */
    private fun refine(
        chart: Patch,
        plane: Plane3,
        e: ProfileElement,
        outside: Double,
        inside: Double,
    ): Double {
        var lo = outside
        var hi = inside
        repeat(40) {
            val mid = (lo + hi) / 2.0
            val p = curveAt(plane, e, mid)
            val on = p != null && chart.of(p)?.let { chart.contains(it) } == true
            if (on) hi = mid else lo = mid
        }
        return hi
    }

    /** [e] between two of its own parameters — the same curve, cut down, in the same words. */
    private fun subPiece(
        plane: Plane3,
        e: ProfileElement,
        u0: Double,
        u1: Double,
    ): ProfileElement? {
        val a = curveAt(plane, e, u0) ?: return null
        val b = curveAt(plane, e, u1) ?: return null
        val m = curveAt(plane, e, (u0 + u1) / 2.0) ?: return null
        return clipTo(plane, e, a, b, m)
    }

    /**
     * The cut a face's chart is **marched** for, where the vocabulary has no name for it — a quartic where a
     * torus meets a plane askew, a hyperbola on a cone. Chords between points that are exact on the surface,
     * flagged as chords by the caller (OP-15's honesty line, not crossed).
     */
    private fun marched(
        chart: Patch,
        cut: Plane3,
    ): List<List<Vec2>> {
        val thSteps = 192
        val tSteps = 48
        val th0 = if (chart.surface.full) -PI else min(chart.surface.turnStart, chart.surface.turnEnd)
        val thSpan = if (chart.surface.full) TWO_PI else abs(chart.surface.turnEnd - chart.surface.turnStart)
        val t0 = chart.tRange.first
        val tSpan = chart.tRange.second - chart.tRange.first
        if (thSpan <= 0.0 || tSpan <= 0.0) return emptyList()
        val segs = ArrayList<Pair<Vec2, Vec2>>()
        for (i in 0 until thSteps) {
            for (j in 0 until tSteps) {
                val th = th0 + thSpan * i / thSteps
                val th2 = th0 + thSpan * (i + 1) / thSteps
                val t = t0 + tSpan * j / tSteps
                val t2 = t0 + tSpan * (j + 1) / tSteps
                val hits = ArrayList<Vec3>()
                for ((p, q) in listOf(Vec2(th, t) to Vec2(th2, t), Vec2(th2, t) to Vec2(th2, t2), Vec2(th2, t2) to Vec2(th, t2), Vec2(th, t2) to Vec2(th, t))) {
                    val a = chart.at(p.x, p.y) ?: continue
                    val b = chart.at(q.x, q.y) ?: continue
                    val da = cut.distanceTo(a)
                    val db = cut.distanceTo(b)
                    if (da == 0.0 || (da < 0.0) != (db < 0.0)) {
                        val f = if (da == db) 0.0 else da / (da - db)
                        val w = a + (b - a) * f
                        val mid = p + (q - p) * f
                        if (chart.contains(mid)) hits.add(w)
                    }
                }
                if (hits.size >= 2) segs.add(cut.toLocal(hits[0]) to cut.toLocal(hits[1]))
            }
        }
        return chainSegs(segs)
    }

    /** Loose chords chained into runs — the same walk [Revolve3] does for a band it can only sample. */
    private fun chainSegs(segs: List<Pair<Vec2, Vec2>>): List<List<Vec2>> {
        if (segs.isEmpty()) return emptyList()
        val used = BooleanArray(segs.size)
        val out = ArrayList<List<Vec2>>()
        for (i in segs.indices) {
            if (used[i]) continue
            used[i] = true
            val run = ArrayList<Vec2>()
            run.add(segs[i].first)
            run.add(segs[i].second)
            var grew = true
            while (grew) {
                grew = false
                for (j in segs.indices) {
                    if (used[j]) continue
                    val (a, b) = segs[j]
                    when {
                        (run.last() - a).length() <= 1e-6 -> {
                            run.add(b)
                            used[j] = true
                            grew = true
                        }
                        (run.last() - b).length() <= 1e-6 -> {
                            run.add(a)
                            used[j] = true
                            grew = true
                        }
                        (run.first() - b).length() <= 1e-6 -> {
                            run.add(0, a)
                            used[j] = true
                            grew = true
                        }
                        (run.first() - a).length() <= 1e-6 -> {
                            run.add(0, b)
                            used[j] = true
                            grew = true
                        }
                    }
                }
            }
            out.add(run)
        }
        return out
    }
}
