package constructit.geom

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **The named faces of a surface of revolution, and the exact sections they make** (OP-17's item 4 of the
 * sphere queue; OP-8's provenance one curve-family further out).
 *
 * A revolution has no faces of its own to discover: it has the faces its **profile's own pieces** sweep.
 * That is the whole idea of this file and the reason it needs no topology — a profile segment parallel to
 * the axis sweeps a cylinder, one meeting the axis at an angle sweeps a cone, one perpendicular to it
 * sweeps a flat annulus, a profile arc centred on the axis sweeps a sphere and one centred off it a torus,
 * and a partial turn adds the two flat caps the profile itself is. Every one of those is a *constructed*
 * name, so it survives every edit exactly as an extrude's side face does (see [Section3] and
 * [Geom3.boundaryPieces] for the address that identifies one).
 *
 * **The identity rule, stated once.** A revolution's face is named by the **index of the profile boundary
 * piece it is swept by**, in [Geom3.boundaryPieces] order — regions in order, each region's outer loop then
 * its holes, pieces in loop order — followed by the two caps of a partial turn, low-angle first. That is
 * the *same address space* an extrusion's `FaceName.Side` uses and the same one `sketchspace el= piece=`
 * has always recorded, so nothing about any stored file changes meaning. The index is a fact of the
 * **construction**, not of the geometry: dragging the profile, retyping a radius, changing the sweep or
 * moving the axis all leave the piece list the same length in the same order, so face #3 goes on being
 * face #3 and everything anchored on it follows (OP-17's liveness). The one honest caveat is [Loop]'s own
 * (OP-14, recorded on [Geom3.boundaryPieces]): a ring the user turns inside out is renormalised and
 * renames its own edges.
 *
 * **What is exact and what is not.** The doctrine's second half — *exact paths never degrade silently to
 * mesh paths; dispatch by predicate up front* — is this file's law, and [cutBand] is where it is obeyed:
 * the plane's relation to the axis is decided **first**, and only a family that has an exact answer for
 * that relation gives one. See [cutBand] for the table.
 */
object Revolve3 {
    /** How close to parallel/perpendicular counts as it, on a dot product of unit vectors. */
    private const val DIR_EPS = 1e-9

    /** How far a point may sit off a band, in mm, and still count as on it. */
    private const val BAND_TOL = 1e-6

    /** How many points of a candidate exact curve are tested against the band. */
    private const val BAND_SAMPLES = 128

    private const val TWO_PI = 2.0 * PI

    /**
     * The **axis frame** of a revolution, in its sketch and in the world — [Geom3.revolve]'s own frame,
     * read back off the feature rather than recomputed, because [Feature3.Revolution.axisDir] already
     * stores the canonicalised direction (the one with the profile on its positive side).
     *
     * `s` runs along the axis from [origin2], `r` away from it, and a point of the body at `(s, r, θ)` is
     * `O + A·s + P·(r cos θ) + N·(r sin θ)` — verbatim the emitter's `at()`.
     */
    class Frame(
        val axis2: Vec2,
        val perp2: Vec2,
        val origin2: Vec2,
        val O: Vec3,
        val A: Vec3,
        val P: Vec3,
        val N: Vec3,
        val turnStart: Double,
        val turnEnd: Double,
        val full: Boolean,
    ) {
        /** The sketch point [p] as `(s, r)` in the axis frame. */
        fun sr(p: Vec2): Vec2 {
            val d = p - origin2
            return Vec2(d.dot(axis2), d.dot(perp2))
        }

        /** The sketch point an `(s, r)` pair names — the inverse of [sr]. */
        fun sketch(
            s: Double,
            r: Double,
        ): Vec2 = origin2 + axis2 * s + perp2 * r

        /** The radial unit direction at turn angle [th]. */
        fun radial(th: Double): Vec3 = P * cos(th) + N * sin(th)

        /** The world point at `(s, r, θ)`. */
        fun world(
            s: Double,
            r: Double,
            th: Double,
        ): Vec3 = O + A * s + radial(th) * r

        /** Where the world point [p] sits: `(s, r, θ)`, the angle in `(−π, π]`. */
        fun of(p: Vec3): Triple<Double, Double, Double> {
            val d = p - O
            val s = d.dot(A)
            val rad = d - A * s
            return Triple(s, rad.length(), atan2(rad.dot(N), rad.dot(P)))
        }

        /** Whether turn angle [th] is inside the swept interval — always true for a complete turn. */
        fun turnContains(th: Double): Boolean {
            if (full) return true
            var t = th
            while (t < turnStart - 1e-12) t += TWO_PI
            while (t > turnStart + TWO_PI + 1e-12) t -= TWO_PI
            return t <= turnEnd + 1e-9
        }

        /** The turn interval's own sweep, a complete turn included. */
        val sweep: Double get() = if (full) TWO_PI else turnEnd - turnStart
    }

    /**
     * The **surface family** one profile piece sweeps — what makes an exact section exact, and what a
     * refusal names when there is none.
     */
    sealed interface Band {
        /** How this surface is spoken of in a refusal. */
        val label: String

        /** A piece lying **on** the axis: it sweeps nothing at all, which is the normal case for a turned part. */
        data object Degenerate : Band {
            override val label: String get() = "nothing (it lies on the axis of revolution)"
        }

        /** A segment perpendicular to the axis: a flat annulus, disc or sector at axial coordinate [s]. */
        data class Planar(
            val s: Double,
            val rLo: Double,
            val rHi: Double,
            val outward: Double,
        ) : Band {
            override val label: String get() = if (rLo <= Geom3.WELD_TOL) "a flat disc" else "a flat annulus"
        }

        /** A segment parallel to the axis, at radius [r] over the axial interval `[s0, s1]`. */
        data class Cylinder(val r: Double, val s0: Double, val s1: Double) : Band {
            override val label: String get() = "a cylinder"
        }

        /** A segment at an angle to the axis: the cone with its apex at axial coordinate [sApex]. */
        data class Cone(
            val sApex: Double,
            val tanHalf: Double,
            val s0: Double,
            val s1: Double,
        ) : Band {
            override val label: String get() = "a cone"
        }

        /** An arc whose centre lies **on** the axis, at axial coordinate [sc]: a band of a sphere. */
        data class Sphere(val sc: Double, val radius: Double) : Band {
            override val label: String get() = "a sphere"
        }

        /** An arc whose centre lies off the axis: a band of the torus with those two radii. */
        data class Torus(
            val sc: Double,
            val rc: Double,
            val minor: Double,
        ) : Band {
            override val label: String get() = "a torus"
        }

        /** A profile piece whose surface of revolution this vocabulary has no name for. */
        data class Unnamed(override val label: String) : Band
    }

    // ---- the frame, and the family of one piece ----

    /**
     * [feature]'s axis frame, or null when it has none — the two refusals [Geom3.revolve] itself makes
     * (a directionless axis, a profile lying on it), restated here so a face list can decline the same way.
     */
    fun frameOf(feature: Feature3.Revolution): Frame? {
        val axis2 = feature.axisDir
        if (axis2.length() < Vec2.EPS) return null
        val a2 = axis2.normalized()
        val p2 = a2.perp()
        val plane = feature.sketch.plane
        val A = (plane.u * a2.x + plane.v * a2.y).normalized()
        val P = (plane.u * p2.x + plane.v * p2.y).normalized()
        val turn = feature.turn
        val arc = turn as? Turn3.Arc
        val full = arc == null || abs(arc.sweep - TWO_PI) <= 1e-9
        return Frame(
            a2,
            p2,
            feature.axisOrigin,
            plane.toWorld(feature.axisOrigin),
            A,
            P,
            A.cross(P),
            arc?.start ?: 0.0,
            arc?.end ?: TWO_PI,
            full,
        )
    }

    /** The surface [e] sweeps about [f]'s axis — read off the piece's own kind and position (OP-8). */
    fun bandOf(
        f: Frame,
        e: ProfileElement,
    ): Band =
        when (e) {
            is ProfileElement.Seg -> {
                val a = f.sr(e.segment.a)
                val b = f.sr(e.segment.b)
                val ds = b.x - a.x
                val dr = b.y - a.y
                when {
                    abs(a.y) <= Geom3.WELD_TOL && abs(b.y) <= Geom3.WELD_TOL -> Band.Degenerate
                    abs(ds) <= Geom3.WELD_TOL -> {
                        val d2 = (e.segment.b - e.segment.a).normalized()
                        Band.Planar(a.x, min(a.y, b.y), max(a.y, b.y), Vec2(d2.y, -d2.x).dot(f.axis2))
                    }
                    abs(dr) <= Geom3.WELD_TOL -> Band.Cylinder(a.y, min(a.x, b.x), max(a.x, b.x))
                    else ->
                        Band.Cone(
                            a.x - a.y * ds / dr,
                            abs(dr / ds),
                            min(a.x, b.x),
                            max(a.x, b.x),
                        )
                }
            }
            is ProfileElement.ArcE -> {
                val c = f.sr(e.arc.center)
                if (abs(c.y) <= Geom3.WELD_TOL) Band.Sphere(c.x, e.arc.radius) else Band.Torus(c.x, c.y, e.arc.radius)
            }
            // a circle centred on the axis would cross it, which [Geom3.revolve] refuses outright, so a
            // whole-circle profile piece is always a torus
            is ProfileElement.CircleE -> {
                val c = f.sr(e.circle.center)
                Band.Torus(c.x, c.y, e.circle.radius)
            }
            is ProfileElement.EllipseE, is ProfileElement.EllipticArcE ->
                Band.Unnamed("a surface of revolution swept by an ellipse, which this drawing has no name for")
            is ProfileElement.BezierE ->
                Band.Unnamed("a surface of revolution swept by a spline, which this drawing has no name for")
        }

    // ---- the face family ----

    /** How many faces a revolution has: one per profile piece, plus the two caps of a partial turn. */
    fun faceCount(feature: Feature3.Revolution): Int {
        val n = Geom3.boundaryPieces(feature).size
        val f = frameOf(feature) ?: return n
        return if (f.full) n else n + 2
    }

    /**
     * The faces of [feature] in provenance order: one per profile boundary piece, then — for a partial
     * turn — the cap at the interval's **low** angle and the one at its **high** angle, which is
     * [Geom3.revolve]'s own reversed-bottom / upright-top rule read on the angle.
     */
    fun faces(feature: Feature3.Revolution): Pair<List<FacePatch>?, String?> {
        val f = frameOf(feature) ?: return null to "the axis of revolution has no direction"
        val pieces = Geom3.boundaryPieces(feature)
        val out = ArrayList<FacePatch>(pieces.size + 2)
        for ((i, e) in pieces.withIndex()) out.add(bandPatch(f, e, FaceName.Side(i)))
        if (!f.full) {
            out.add(capPatch(feature, f, SolidFace.BOTTOM))
            out.add(capPatch(feature, f, SolidFace.TOP))
        }
        return out to null
    }

    /** One band as a face: a plane where the family is a flat one, and a named non-plane otherwise. */
    private fun bandPatch(
        f: Frame,
        e: ProfileElement,
        name: FaceName,
    ): FacePatch {
        val band = bandOf(f, e)
        if (band !is Band.Planar) {
            return FacePatch(
                name,
                null,
                emptyList(),
                if (band is Band.Degenerate) {
                    "that profile edge lies on the axis of revolution, so it sweeps no surface at all"
                } else {
                    "that profile edge sweeps ${band.label} and not a plane — put a datum plane where you want to sketch"
                },
                band,
            )
        }
        return FacePatch(name, planarPlane(f, band), planarOutline(f, band), null, band)
    }

    /**
     * The plane of a flat band, in **the frame a sketch on it is measured in**: the origin is where the
     * **axis** pierces it, `u` is the radial direction at turn angle 0 — the one the profile itself is
     * drawn at — and `v` is what right-handedness leaves once the normal points out of the material.
     *
     * That the origin is the axis and not the picked edge's midpoint is the one place this frame departs
     * from [Geom3.sideFace]'s intrinsic rule, and deliberately: a prism's side face has no distinguished
     * point, so its own edge is the only choice-free anchor there is, whereas a face of revolution has a
     * **centre** — which is what somebody sketching a boss on the end of a turned part is measuring from.
     * A space may still move its origin from there by an anchor and an offset, exactly as any other can.
     */
    private fun planarPlane(
        f: Frame,
        band: Band.Planar,
    ): Plane3 {
        val sigma = if (band.outward >= 0.0) 1.0 else -1.0
        return Plane3(f.O + f.A * band.s, f.P, f.N * sigma)
    }

    /**
     * A flat band's own boundary in that frame: a full annulus or disc for a complete turn, and the sector
     * of one for a partial turn. The face's `(x, y)` are `(r cos φ, r sin φ)` with `φ = σ·θ`, σ being the
     * flip [planarPlane] applied — so the outline is generated here in the face's own terms rather than
     * transformed into them.
     */
    private fun planarOutline(
        f: Frame,
        band: Band.Planar,
    ): List<ProfileElement> {
        val sigma = if (band.outward >= 0.0) 1.0 else -1.0
        val rHi = band.rHi
        val rLo = band.rLo
        if (rHi <= Geom3.WELD_TOL) return emptyList()
        if (f.full) {
            val outer = ProfileElement.CircleE(Circle(Vec2(0.0, 0.0), rHi), true)
            return if (rLo <= Geom3.WELD_TOL) listOf(outer) else listOf(outer, ProfileElement.CircleE(Circle(Vec2(0.0, 0.0), rLo), false))
        }
        val a = min(sigma * f.turnStart, sigma * f.turnEnd)
        val b = max(sigma * f.turnStart, sigma * f.turnEnd)

        fun at(
            r: Double,
            phi: Double,
        ) = Vec2(r * cos(phi), r * sin(phi))
        if (rLo <= Geom3.WELD_TOL) {
            return listOf(
                ProfileElement.Seg(Segment(Vec2(0.0, 0.0), at(rHi, a))),
                ProfileElement.ArcE(Arc(Vec2(0.0, 0.0), rHi, a, b, true)),
                ProfileElement.Seg(Segment(at(rHi, b), Vec2(0.0, 0.0))),
            )
        }
        return listOf(
            ProfileElement.Seg(Segment(at(rLo, a), at(rHi, a))),
            ProfileElement.ArcE(Arc(Vec2(0.0, 0.0), rHi, a, b, true)),
            ProfileElement.Seg(Segment(at(rHi, b), at(rLo, b))),
            ProfileElement.ArcE(Arc(Vec2(0.0, 0.0), rLo, b, a, false)),
        )
    }

    /**
     * A partial revolution's cap: the profile itself, standing in the half-plane at the interval's [which]
     * end, with its normal out of the sweep.
     *
     * The frames are the two the emitter's winding already implies. At the **high** end the outward normal
     * is the direction of increasing angle, and `A × R(θ) = T(θ)` is exactly that, so the frame is
     * `(A, R)` and a profile point reads `(s, r)`. At the **low** end the outward normal is `−T`, so the
     * frame is `(R, A)` and the same point reads `(r, s)` — the coordinate swap that mirrors the boundary,
     * which is the revolve's reversed-bottom rule read on the angle.
     */
    private fun capPatch(
        feature: Feature3.Revolution,
        f: Frame,
        which: SolidFace,
    ): FacePatch {
        val name = FaceName.RevolveCap(which)
        val plane = capPlane(f, which)
        val o = f.origin2
        val t =
            if (which == SolidFace.TOP) {
                Affine(f.axis2.x, f.perp2.x, f.axis2.y, f.perp2.y, -f.axis2.dot(o), -f.perp2.dot(o))
            } else {
                Affine(f.perp2.x, f.axis2.x, f.perp2.y, f.axis2.y, -f.perp2.dot(o), -f.axis2.dot(o))
            }
        val pieces =
            feature.sketch.regions.flatMap { r ->
                (listOf(r.outer) + r.holes).flatMap { l -> GeomMath.transform(l, t).elements }
            }
        return FacePatch(name, plane, pieces, null, null)
    }

    /** The plane of the cap at the interval's [which] end — see [capPatch] for why these two frames. */
    fun capPlane(
        f: Frame,
        which: SolidFace,
    ): Plane3 =
        if (which == SolidFace.TOP) {
            Plane3(f.O, f.A, f.radial(f.turnEnd))
        } else {
            Plane3(f.O, f.radial(f.turnStart), f.A)
        }

    /**
     * The face a `sketchspace el= piece=` address names, in the frame a sketch on it is measured in.
     *
     * Indices `0 until n` are the profile's own boundary pieces, and `n` / `n + 1` the low- and high-angle
     * **caps** of a partial turn — the same order [faces] emits, so one address space serves both.
     */
    fun facePatchOf(
        feature: Feature3.Revolution,
        piece: Int,
    ): Pair<FacePatch?, String?> {
        val f = frameOf(feature) ?: return null to "the axis of revolution has no direction"
        val pieces = Geom3.boundaryPieces(feature)
        if (piece < 0) return null to "this solid has no face #${piece + 1}"
        if (piece >= pieces.size) {
            if (f.full) {
                return null to
                    "this solid is a complete revolution, so it has no start and no end and therefore no cap faces — " +
                    "put a datum plane where you want to sketch"
            }
            val which =
                when (piece) {
                    pieces.size -> SolidFace.BOTTOM
                    pieces.size + 1 -> SolidFace.TOP
                    else -> return null to "this solid has no face #${piece + 1} (it has ${pieces.size + 2})"
                }
            return capPatch(feature, f, which) to null
        }
        val patch = bandPatch(f, pieces[piece], FaceName.Side(piece))
        if (patch.plane == null) return null to (patch.reason ?: "that face is not a plane")
        return patch to null
    }

    // ---- the structural edges ----

    /**
     * The edges of [feature]: the **ring** each profile corner traces, then — for a partial turn — the
     * profile's own pieces as they lie on each cap.
     */
    fun edges(feature: Feature3.Revolution): Pair<List<SolidEdge>?, String?> {
        val f = frameOf(feature) ?: return null to "the axis of revolution has no direction"
        val pieces = Geom3.boundaryPieces(feature)
        val out = ArrayList<SolidEdge>()
        for ((i, e) in pieces.withIndex()) {
            val sr = f.sr(GeomMath.startOf(e))
            if (sr.y <= Geom3.WELD_TOL) {
                // a corner **on** the axis traces no ring: it is one point, and [Section3.cutEdge] answers
                // a degenerate edge as the point it is
                val p = f.world(sr.x, 0.0, 0.0)
                out.add(SolidEdge(EdgeName.RevolveRing(i), EdgeGeom.Straight(p, p)))
            } else {
                val plane = Plane3(f.O + f.A * sr.x, f.P, f.N)
                val ring =
                    if (f.full) {
                        ProfileElement.CircleE(Circle(Vec2(0.0, 0.0), sr.y), true)
                    } else {
                        ProfileElement.ArcE(Arc(Vec2(0.0, 0.0), sr.y, f.turnStart, f.turnEnd, true))
                    }
                out.add(SolidEdge(EdgeName.RevolveRing(i), EdgeGeom.OnPlane(plane, ring)))
            }
        }
        if (!f.full) {
            for (which in listOf(SolidFace.BOTTOM, SolidFace.TOP)) {
                val patch = capPatch(feature, f, which)
                val plane = patch.plane ?: continue
                for ((i, e) in patch.outline.withIndex()) {
                    out.add(SolidEdge(EdgeName.RevolveCapPiece(which, i), EdgeGeom.OnPlane(plane, e)))
                }
            }
        }
        return out to null
    }

    // ---- the section: the dispatch, and the honest answer where there is no name ----

    /**
     * The result of cutting one band: the exact curves in the cutting plane's own `(u, v)`, or the honest
     * sampled runs where the vocabulary has no name for the cut.
     */
    class BandCut(val exact: List<ProfileElement>?, val runs: List<List<Vec2>>?)

    /**
     * **Cut one band with a plane — the dispatch table, decided by predicate before any geometry is made.**
     *
     * | band | plane ⟂ axis | plane through the axis | plane ∥ axis, off it | oblique |
     * |------|--------------|------------------------|----------------------|---------|
     * | flat annulus / disc / sector | exact (handled as a planar face) | exact | exact | exact |
     * | cap of a partial turn | exact (planar face) | exact | exact | exact |
     * | cylinder | exact circle / arc | exact pair of rulings | exact pair of rulings | **exact ellipse** |
     * | cone | exact circle / arc | exact pair of rulings | mesh (a hyperbola: no name) | exact ellipse when `\|n·axis\| > sin α`, else mesh (parabola / hyperbola) |
     * | sphere | exact circle / arc | **exact circle** | **exact circle** | **exact circle** |
     * | torus | exact circles / arcs | exact profile arcs | mesh (a quartic: no name) | mesh (a quartic) |
     * | swept by an ellipse or a spline | exact circles / arcs | exact profile pieces | mesh | mesh |
     *
     * Two of those columns are **family-independent** and are what makes the table's left half so wide: a
     * plane perpendicular to the axis meets the body wherever the profile does, so its section is the
     * circles (arcs, over a partial turn) at the profile's own crossing radii — exact for a spline profile
     * as much as for a segment; and a plane **containing** the axis meets it in the profile piece itself,
     * placed at the two meridian angles the plane's half-planes stand at. Both are constructed **on** the
     * band, so neither can leave it.
     *
     * The family answers are tried first, because they say more when they apply (a ball cut through its
     * own axis is *one* circle, not two half-circles), and each is kept only if it lies **wholly** on the
     * band — the band being one piece of a surface, not the whole of it. Where it does not, the two
     * columns above answer, and where they do not apply either the cut comes back as the sampled runs of
     * the band's own tessellation, flagged (OP-15). Nothing is ever fitted to samples, and no exact path
     * turns into a mesh path without the caller being told.
     *
     * Whether a candidate lies on the band is decided by **sampling the candidate** — [BAND_SAMPLES]
     * points, each tested exactly (its `(s, r)` preimage against the profile piece, its angle against the
     * turn interval). That is a *predicate* decided by sampling and not a curve approximated by it, which
     * is the same line `Section3.inclinedCylinderCut` already draws for a cylinder running off its ends.
     */
    fun cutBand(
        feature: Feature3.Revolution,
        piece: Int,
        cut: Plane3,
    ): BandCut? {
        val f = frameOf(feature) ?: return null
        val e = Geom3.boundaryPieces(feature).getOrNull(piece) ?: return null
        val band = bandOf(f, e)
        if (band is Band.Degenerate) return BandCut(emptyList(), null)
        val n = cut.normal.normalized()
        val k = f.A.dot(n)
        val perpToAxis = abs(abs(k) - 1.0) <= DIR_EPS
        val parallelToAxis = abs(k) <= DIR_EPS
        val throughAxis = parallelToAxis && abs(cut.distanceTo(f.O)) <= Section3.ON_PLANE_TOL

        if (!perpToAxis) {
            val family = familyCut(f, band, cut, k)
            if (family != null) {
                val fits = family.map { it to onBand(f, e, cut, it) }
                // a candidate wholly off the band is one the plane meets on the rest of the surface and not
                // on this face at all, so it is dropped rather than disqualifying the answer; one only
                // *partly* on it is a proper sub-piece, which is what no exact curve here can state
                if (fits.none { it.second == Fit.PART }) {
                    return BandCut(fits.filter { it.second == Fit.ON }.map { it.first }, null)
                }
            }
        }
        if (perpToAxis) return BandCut(axisNormalCut(f, e, cut, k), null)
        if (throughAxis) return BandCut(throughAxisCut(f, e, cut, n), null)
        return BandCut(null, sampledRuns(f, e, cut))
    }

    // ---- the two family-independent columns ----

    /**
     * A plane **perpendicular to the axis**: the circles (arcs, over a partial turn) at the radii where
     * the profile piece crosses that axial coordinate — exact whatever kind the piece is, because the
     * crossings themselves are analytic ([Section3]'s own `crossingsOf`, one frame over).
     */
    private fun axisNormalCut(
        f: Frame,
        e: ProfileElement,
        cut: Plane3,
        k: Double,
    ): List<ProfileElement> {
        val s = -cut.distanceTo(f.O) / k
        // the profile's own crossings of the line s = const, in sketch coordinates
        val line = Line(f.sketch(s, 0.0), f.perp2)
        val out = ArrayList<ProfileElement>()
        for (q in crossings(e, line)) {
            val r = f.sr(q).y
            if (r <= Geom3.WELD_TOL) continue
            val c = cut.toLocal(f.O + f.A * s)
            val a0 = cut.toLocal(f.world(s, r, f.turnStart)) - c
            if (f.full) {
                out.add(ProfileElement.CircleE(Circle(c, r), true))
            } else {
                val mid = cut.toLocal(f.world(s, r, (f.turnStart + f.turnEnd) / 2.0)) - c
                val a1 = cut.toLocal(f.world(s, r, f.turnEnd)) - c
                val start = a0.angle()
                val end = a1.angle()
                val ccw = GeomMath.arcContains(Arc(c, r, start, end, true), mid.angle())
                out.add(ProfileElement.ArcE(Arc(c, r, start, end, ccw)))
            }
        }
        return out
    }

    /**
     * A plane **containing the axis**: the profile piece itself, placed at each of the two meridian angles
     * the plane's half-planes stand at — exact for every kind of piece, and on the band by construction.
     */
    private fun throughAxisCut(
        f: Frame,
        e: ProfileElement,
        cut: Plane3,
        n: Vec3,
    ): List<ProfileElement> {
        // the plane contains the axis, so its in-plane direction perpendicular to the axis is A × n
        val q = f.A.cross(n).normalized()
        val th0 = atan2(q.dot(f.N), q.dot(f.P))
        val out = ArrayList<ProfileElement>()
        for (th in listOf(th0, th0 + PI)) {
            if (!f.turnContains(th)) continue
            val rad = f.radial(th)
            // (s, r) at this meridian angle read in the cutting plane: an isometry, so the piece keeps its kind
            val o = cut.toLocal(f.O)
            val ea = Vec2(f.A.dot(cut.u), f.A.dot(cut.v))
            val er = Vec2(rad.dot(cut.u), rad.dot(cut.v))
            val toWorld = Affine(ea.x, ea.y, er.x, er.y, o.x, o.y)
            val toSR =
                Affine(
                    f.axis2.x,
                    f.perp2.x,
                    f.axis2.y,
                    f.perp2.y,
                    -f.axis2.dot(f.origin2),
                    -f.perp2.dot(f.origin2),
                )
            out.add(GeomMath.transform(GeomMath.transform(e, toSR), toWorld))
        }
        return out
    }

    // ---- the family answers ----

    /** The exact curves this family's own geometry gives for a non-perpendicular plane, or null: no name. */
    private fun familyCut(
        f: Frame,
        band: Band,
        cut: Plane3,
        k: Double,
    ): List<ProfileElement>? =
        when (band) {
            is Band.Sphere -> sphereCut(f, band, cut)
            is Band.Cylinder -> cylinderCut(f, band, cut, k)
            is Band.Cone -> coneCut(f, band, cut, k)
            else -> null
        }

    /** Plane ∩ sphere is a circle, for **every** plane — the one family with no case analysis at all. */
    private fun sphereCut(
        f: Frame,
        band: Band.Sphere,
        cut: Plane3,
    ): List<ProfileElement> {
        val c = f.O + f.A * band.sc
        val d = cut.distanceTo(c)
        val rr = band.radius * band.radius - d * d
        if (rr <= Geom3.WELD_TOL * Geom3.WELD_TOL) return emptyList()
        val foot = c - cut.normal.normalized() * d
        return listOf(ProfileElement.CircleE(Circle(cut.toLocal(foot), sqrt(rr)), true))
    }

    /** Plane ∩ cylinder: a pair of rulings where the plane is parallel to the axis, an ellipse otherwise. */
    private fun cylinderCut(
        f: Frame,
        band: Band.Cylinder,
        cut: Plane3,
        k: Double,
    ): List<ProfileElement>? {
        if (abs(k) <= DIR_EPS) {
            val n = cut.normal.normalized()
            val h = cut.distanceTo(f.O)
            val ww = band.r * band.r - h * h
            if (ww <= Geom3.WELD_TOL * Geom3.WELD_TOL) return emptyList()
            val w = sqrt(ww)
            val q = f.A.cross(n).normalized()
            val foot = f.O - n * h
            return listOf(1.0, -1.0).map { sgn ->
                val base = foot + q * (w * sgn)
                ProfileElement.Seg(
                    Segment(cut.toLocal(base + f.A * band.s0), cut.toLocal(base + f.A * band.s1)),
                )
            }
        }
        val ell = Conics.cylinderSection(f.O, f.A, f.P, f.N, band.r, cut) ?: return null
        return listOf(ProfileElement.EllipseE(ell, true))
    }

    /**
     * Plane ∩ cone: the rulings through the apex when the plane passes through it, and the **exact ellipse**
     * when the plane is steeper than the cone's own half-angle. A parabola or a hyperbola comes back as
     * null — a real curve this drawing has no name for, so the caller says so rather than fitting one.
     */
    private fun coneCut(
        f: Frame,
        band: Band.Cone,
        cut: Plane3,
        k: Double,
    ): List<ProfileElement>? {
        val apex = f.O + f.A * band.sApex
        val alphaSin = band.tanHalf / hypot(1.0, band.tanHalf)
        val alphaCos = 1.0 / hypot(1.0, band.tanHalf)
        val n = cut.normal.normalized()
        val dApex = cut.distanceTo(apex)
        if (abs(dApex) <= Section3.ON_PLANE_TOL) {
            // the plane through the apex meets the cone in the rulings whose direction lies in it
            val mp = n.dot(f.P)
            val mn = n.dot(f.N)
            val amp = hypot(mp, mn)
            val rhs = -k * alphaCos / alphaSin
            if (amp <= DIR_EPS || abs(rhs) > amp) return emptyList()
            val psi = atan2(mn, mp)
            val da = acos((rhs / amp).coerceIn(-1.0, 1.0))
            return listOf(psi + da, psi - da).distinctBy { round(it * 1e9) }.mapNotNull { phi ->
                val d = f.A * alphaCos + f.radial(phi) * alphaSin
                val along = d.dot(f.A)
                if (abs(along) <= DIR_EPS) {
                    null
                } else {
                    val t0 = (band.s0 - band.sApex) / along
                    val t1 = (band.s1 - band.sApex) / along
                    ProfileElement.Seg(Segment(cut.toLocal(apex + d * t0), cut.toLocal(apex + d * t1)))
                }
            }
        }
        // an ellipse exactly when the plane crosses every ruling — i.e. is steeper than the half-angle
        if (abs(k) <= alphaSin + 1e-12) return null
        // |P − apex|² − (1 + tan²α)((P − apex)·A)² = 0, with P = cut.origin + u·x + v·y
        val w = cut.origin - apex
        val kappa = 1.0 + band.tanHalf * band.tanHalf
        val a1 = cut.u.dot(f.A)
        val a2 = cut.v.dot(f.A)
        val wa = w.dot(f.A)
        val ell =
            Conics.ellipseFromImplicit(
                1.0 - kappa * a1 * a1,
                -2.0 * kappa * a1 * a2,
                1.0 - kappa * a2 * a2,
                2.0 * (w.dot(cut.u) - kappa * wa * a1),
                2.0 * (w.dot(cut.v) - kappa * wa * a2),
                w.dot(w) - kappa * wa * wa,
            ) ?: return null
        return listOf(ProfileElement.EllipseE(ell, true))
    }

    // ---- the band test ----

    /** How much of a candidate curve lies on the band. */
    private enum class Fit { ON, PART, OFF }

    /**
     * Whether the exact candidate [c] — read in the cutting plane's own `(u, v)` — lies on the band the
     * profile piece [e] sweeps: sampled at [BAND_SAMPLES] points, each tested **exactly**.
     */
    private fun onBand(
        f: Frame,
        e: ProfileElement,
        cut: Plane3,
        c: ProfileElement,
    ): Fit {
        var on = 0
        var off = 0
        for (p in samplePiece(c, BAND_SAMPLES)) {
            val (s, r, th) = f.of(cut.toWorld(p))
            if (f.turnContains(th) && pieceContains(e, f.sketch(s, r))) on++ else off++
            if (on > 0 && off > 0) return Fit.PART
        }
        return if (off == 0) Fit.ON else Fit.OFF
    }

    /** Whether the sketch point [p] lies on the profile piece [e] — exact per kind, to [BAND_TOL]. */
    private fun pieceContains(
        e: ProfileElement,
        p: Vec2,
    ): Boolean =
        when (e) {
            is ProfileElement.Seg -> {
                val a = e.segment.a
                val d = e.segment.b - a
                val len2 = d.dot(d)
                if (len2 <= 1e-18) {
                    (p - a).length() <= BAND_TOL
                } else {
                    val t = (p - a).dot(d) / len2
                    t >= -1e-9 && t <= 1.0 + 1e-9 && abs((p - a).cross(d) / kotlin.math.sqrt(len2)) <= BAND_TOL
                }
            }
            is ProfileElement.ArcE ->
                abs((p - e.arc.center).length() - e.arc.radius) <= BAND_TOL &&
                    GeomMath.arcContains(e.arc, (p - e.arc.center).angle())
            is ProfileElement.CircleE -> abs((p - e.circle.center).length() - e.circle.radius) <= BAND_TOL
            // an ellipse's and a spline's bands never reach here: their families answer null, and the two
            // family-independent columns are constructed on the band already
            else -> false
        }

    /** [n] points **on** the curve — parametric, never chords, because this is what the band test reads. */
    private fun samplePiece(
        e: ProfileElement,
        n: Int,
    ): List<Vec2> =
        when (e) {
            is ProfileElement.Seg -> (0..n).map { e.segment.a + (e.segment.b - e.segment.a) * (it.toDouble() / n) }
            is ProfileElement.ArcE -> {
                val sw = GeomMath.sweep(e.arc)
                (0..n).map { GeomMath.arcPointAt(e.arc, e.arc.startAngle + sw * it / n) }
            }
            is ProfileElement.CircleE ->
                (0 until n).map {
                    val a = TWO_PI * it / n
                    e.circle.center + Vec2(e.circle.radius * cos(a), e.circle.radius * sin(a))
                }
            is ProfileElement.EllipseE -> (0 until n).map { Conics.pointAt(e.ellipse, TWO_PI * it / n) }
            is ProfileElement.EllipticArcE -> Conics.sample(e.arc, n)
            is ProfileElement.BezierE -> (0..n).map { GeomMath.bezierPointAt(e.bezier, it.toDouble() / n) }
        }

    /** Where a profile piece crosses a line, analytically per kind (a spline is sampled). */
    private fun crossings(
        e: ProfileElement,
        line: Line,
    ): List<Vec2> =
        when (e) {
            is ProfileElement.Seg -> {
                val d = e.segment.b - e.segment.a
                GeomMath.intersectLL(line, Line(e.segment.a, d)).points.filter { p ->
                    val t = (p - e.segment.a).dot(d) / max(d.dot(d), 1e-18)
                    t >= -1e-9 && t <= 1.0 + 1e-9
                }
            }
            is ProfileElement.ArcE ->
                GeomMath.intersectLC(line, Circle(e.arc.center, e.arc.radius)).points.filter {
                    GeomMath.arcContains(e.arc, (it - e.arc.center).angle())
                }
            is ProfileElement.CircleE -> GeomMath.intersectLC(line, e.circle).points
            is ProfileElement.EllipticArcE ->
                Conics.intersectLE(line, e.arc.ellipse).points.filter { Conics.contains(e.arc, Conics.paramOf(e.arc.ellipse, it)) }
            is ProfileElement.EllipseE -> Conics.intersectLE(line, e.ellipse).points
            is ProfileElement.BezierE -> {
                val pts = GeomMath.tessellatePiece(e)
                (0 until pts.size - 1).flatMap { i ->
                    val d = pts[i + 1] - pts[i]
                    GeomMath.intersectLL(line, Line(pts[i], d)).points.filter { p ->
                        val t = (p - pts[i]).dot(d) / max(d.dot(d), 1e-18)
                        t >= 0.0 && t <= 1.0
                    }
                }
            }
        }

    // ---- the honest answer where there is no name (OP-15's approximated class) ----

    /**
     * The band's own cut, sampled: the surface is tessellated exactly as the emitter tessellates it —
     * the profile's chords carried on the turn's stations — every quad is crossed exactly, and the
     * resulting chords are chained into runs. Exact at every vertex, chords between, and flagged.
     */
    private fun sampledRuns(
        f: Frame,
        e: ProfileElement,
        cut: Plane3,
    ): List<List<Vec2>> {
        val poly = GeomMath.tessellatePiece(e).map { f.sr(it) }
        if (poly.size < 2) return emptyList()
        val maxR = poly.maxOf { abs(it.y) }
        val rings = max(3, GeomMath.chordSteps(maxR, f.sweep, GeomMath.TESS_TOL_MM))
        val segs = ArrayList<Pair<Vec2, Vec2>>()
        for (i in 0 until poly.size - 1) {
            for (j in 0 until rings) {
                val t0 = f.turnStart + f.sweep * j / rings
                val t1 = f.turnStart + f.sweep * (j + 1) / rings
                val a = f.world(poly[i].x, poly[i].y, t0)
                val b = f.world(poly[i + 1].x, poly[i + 1].y, t0)
                val c = f.world(poly[i + 1].x, poly[i + 1].y, t1)
                val d = f.world(poly[i].x, poly[i].y, t1)
                cutTriangle(a, b, c, cut)?.let { segs.add(it) }
                cutTriangle(a, c, d, cut)?.let { segs.add(it) }
            }
        }
        return chainSegments(segs)
    }

    /** Where the plane crosses one triangle, as the chord it is — null when it misses or only grazes it. */
    private fun cutTriangle(
        a: Vec3,
        b: Vec3,
        c: Vec3,
        cut: Plane3,
    ): Pair<Vec2, Vec2>? {
        val vs = listOf(a, b, c)
        val ds = vs.map { cut.distanceTo(it) }
        val hits = ArrayList<Vec2>()
        for (i in 0..2) {
            val j = (i + 1) % 3
            if (abs(ds[i]) <= Section3.ON_PLANE_TOL) {
                hits.add(cut.toLocal(vs[i]))
            } else if (ds[i] * ds[j] < 0.0) {
                hits.add(cut.toLocal(vs[i] + (vs[j] - vs[i]) * (ds[i] / (ds[i] - ds[j]))))
            }
        }
        val uniq = ArrayList<Vec2>()
        for (p in hits) if (uniq.none { (it - p).length() <= Geom3.WELD_TOL }) uniq.add(p)
        return if (uniq.size == 2) uniq[0] to uniq[1] else null
    }

    /**
     * Chords welded into runs: endpoints on a [Geom3.WELD_TOL] lattice, then walked greedily, closed runs
     * closing on themselves. Deterministic — insertion order throughout, nothing iterates a hash.
     */
    private fun chainSegments(segs: List<Pair<Vec2, Vec2>>): List<List<Vec2>> {
        if (segs.isEmpty()) return emptyList()
        val pts = ArrayList<Vec2>()
        val index = HashMap<Long, ArrayList<Int>>()

        fun key(p: Vec2): Long = (round(p.x / Geom3.WELD_TOL).toLong() * 73856093L) xor (round(p.y / Geom3.WELD_TOL).toLong() * 19349663L)

        fun idOf(p: Vec2): Int {
            for (dx in -1..1) {
                for (dy in -1..1) {
                    val k = key(Vec2(p.x + dx * Geom3.WELD_TOL, p.y + dy * Geom3.WELD_TOL))
                    index[k]?.forEach { if ((pts[it] - p).length() <= Geom3.WELD_TOL) return it }
                }
            }
            pts.add(p)
            index.getOrPut(key(p)) { ArrayList() }.add(pts.size - 1)
            return pts.size - 1
        }

        val links = ArrayList<ArrayList<Int>>()
        val ends = ArrayList<Pair<Int, Int>>()
        for ((a, b) in segs) {
            val ia = idOf(a)
            val ib = idOf(b)
            if (ia == ib) continue
            while (links.size < pts.size) links.add(ArrayList())
            links[ia].add(ends.size)
            links[ib].add(ends.size)
            ends.add(ia to ib)
        }
        while (links.size < pts.size) links.add(ArrayList())
        val used = BooleanArray(ends.size)
        val out = ArrayList<List<Vec2>>()
        // open runs first, from their free ends, so a run is never entered in its middle
        val starts = pts.indices.filter { links[it].size == 1 } + pts.indices
        for (seed in starts) {
            while (links[seed].any { !used[it] }) {
                val run = ArrayList<Int>()
                var at = seed
                run.add(at)
                while (true) {
                    val ei = links[at].firstOrNull { !used[it] } ?: break
                    used[ei] = true
                    val (a, b) = ends[ei]
                    at = if (a == at) b else a
                    run.add(at)
                    if (at == seed) break
                }
                if (run.size >= 2) out.add(run.map { pts[it] })
            }
        }
        return out
    }
}
