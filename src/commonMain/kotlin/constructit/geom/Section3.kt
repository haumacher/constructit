package constructit.geom

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The **structural name of a face** of a feature (OP-8): what it is a face *of*, never what a mesh lookup
 * found.
 *
 * The names are constructed from the feature's own parameters, so they survive every edit — the
 * topological-naming problem does not arise because nothing is ever re-identified. An extrude's side face
 * *is* a boundary piece of its profile (the order [Geom3.boundaryPieces] already fixes); a loft's ruled face
 * *is* a band of the run at a rail of its own correspondence.
 */
sealed interface FaceName {
    /** How this face is spoken of — in a refusal, in a status line, beside a section input. */
    val label: String

    /** The face an extrusion's boundary piece [piece] sweeps (OP-8's order). */
    data class Side(val piece: Int) : FaceName {
        override val label: String get() = "the face over boundary edge #${piece + 1}"
    }

    /** An extrusion's or prism's named cap. */
    data class Cap(val which: SolidFace) : FaceName {
        override val label: String get() = if (which == SolidFace.TOP) "the top face" else "the bottom face"
    }

    /** A loft's ruled face: the band between sections [band] and `band + 1`, over rail interval [rail]. */
    data class Band(val band: Int, val rail: Int) : FaceName {
        override val label: String
            get() = "the face between sections ${band + 1} and ${band + 2} at edge #${rail + 1}"
    }

    /** A loft's terminal section, as a face of the solid. */
    data class SectionFace(val section: Int) : FaceName {
        override val label: String get() = "section ${section + 1}'s own face"
    }
}

/**
 * The **structural name of an edge** of a feature (OP-8) — an edge being where two faces meet, which is
 * exactly what the queue entry says a section *corner* is provenance-wise: the two faces it was cut from.
 */
sealed interface EdgeName {
    val label: String

    /** An extrusion: the edge swept by the start corner of boundary piece [piece]. */
    data class Upright(val piece: Int) : EdgeName {
        override val label: String get() = "the upright edge at corner #${piece + 1}"
    }

    /** An extrusion: boundary piece [piece] as it lies on cap [which]. */
    data class CapPiece(val which: SolidFace, val piece: Int) : EdgeName {
        override val label: String
            get() = "boundary edge #${piece + 1} of ${if (which == SolidFace.TOP) "the top" else "the bottom"} face"
    }

    /** A loft: the rail from section [band] to `band + 1` at rail index [rail]. */
    data class Rail(val band: Int, val rail: Int) : EdgeName {
        override val label: String get() = "the rail from section ${band + 1} to ${band + 2} at corner #${rail + 1}"
    }

    /** A loft: interval [rail] of section [section]'s own boundary ring. */
    data class SectionRing(val section: Int, val rail: Int) : EdgeName {
        override val label: String get() = "edge #${rail + 1} of section ${section + 1}"
    }
}

/**
 * One face of a solid, named structurally: the plane it lies in (normal **out of the material**, the
 * convention [Geom3.facePlane] and [Geom3.sideFace] already use) and its own boundary in that plane's (u, v).
 *
 * [reason] is non-null exactly when the face is not a plane one can sketch on — a cylinder swept by a curved
 * boundary piece, a ruled quad whose four corners are not coplanar. Such a face still *has* a section (it is
 * cut like any other surface), which is why the refusal lives on the patch instead of removing it from the
 * list: the ordering is structural, so nothing may drop out of it (OP-3 — invalid with a reason, and healing).
 */
data class FacePatch(
    val name: FaceName,
    val plane: Plane3?,
    val outline: List<ProfileElement>,
    val reason: String?,
)

/** One edge of a solid, in the world: a straight one, or a curve lying on a known plane. */
sealed interface EdgeGeom {
    data class Straight(val a: Vec3, val b: Vec3) : EdgeGeom

    data class OnPlane(val plane: Plane3, val piece: ProfileElement) : EdgeGeom
}

/** One structurally named edge of a solid — the thing a section *corner* is the cut of. */
data class SolidEdge(val name: EdgeName, val geom: EdgeGeom)

/**
 * One **curve of a section**, in the cutting plane's own (u, v) — and an *input* of the construction on that
 * plane (OP-6's compound value plus accessors).
 *
 * Exactly one of [curve] and [sampled] is non-null, or neither:
 * - [curve] — the cut is statable **exactly**: a plane through a planar facet is a segment (or an arc, where
 *   the facet's own boundary is curved), a plane perpendicular to a cylinder's axis is that cylinder's
 *   circle, a plane containing the axis is a ruling. Exact means exact: the numbers come from the feature's
 *   parameters, not from its triangles.
 * - [sampled] — the cut is a curve the vocabulary cannot say (OP-15): a twisted band's, or a cylinder's cut
 *   that runs off the ends of the material. Exact at every sample, chords between, flagged, and refused as
 *   an *input* by name — see [PlaneSection.inputsRefusal] for the other honest limit. The **inclined cut of
 *   a cylinder** used to be here and is not any more: since the conics package (OP-24) it comes back as an
 *   exact [ProfileElement.EllipseE] or [ProfileElement.EllipticArcE], which is the honesty line moving
 *   outward by a change in *compute* and not in what any file stores.
 * - neither — this face is not cut at all, or is cut in more than one piece; [reason] says which (OP-3).
 */
data class SectionEdge(
    /** Which face this was cut from, in the user's words — the structural address is the index. */
    val provenance: String,
    val curve: ProfileElement?,
    val sampled: List<Vec2>?,
    val reason: String?,
) {
    /** OP-15's class of this one curve: sampled means chords between exact points. */
    val approximated: Boolean get() = sampled != null
}

/** One **corner of a section**: where the plane crosses a structurally named edge of the solid. */
data class SectionCorner(
    val provenance: String,
    val at: Vec2?,
    val reason: String?,
)

/**
 * The **section of a solid at a plane**, in the plane's own (u, v) — the context of a working plane, and the
 * inputs it offers (OP-17, the section-inputs package).
 *
 * One rule, two readings. What a non-plan working plane draws as context is the part's section at itself; and
 * the *face* case is the degenerate section where the plane lies **on** a face, whose section is that face's
 * own boundary ([onFace]). Both come out of here, which is why a face space and a datum plane need no
 * separate mechanisms.
 *
 * [edges] and [corners] are the **ordered, structural** sets an accessor addresses by index (OP-6, like
 * `PointSet` + `Select`): one entry per named face and per named edge of the feature, present whether or not
 * the plane happens to cut it, so an index means the same thing after every edit and an entry the plane
 * misses is invalid *with a reason* and heals (OP-3). A click records the index (OP-1/OP-18) and it is taken
 * verbatim on replay, never re-scored.
 */
data class PlaneSection(
    val edges: List<SectionEdge>,
    val corners: List<SectionCorner>,
    /** The face this plane lies on, if any — then this section *is* that face's boundary. */
    val onFace: FaceName?,
    /**
     * Why this section exposes no inputs, or null. A solid with no analytic pedigree — the general
     * boolean's mesh-only result (OP-9's sink rule), a revolve, a prism assembled by the slab algebra —
     * still *draws* its section, from the mesh, because seeing where the plane cuts is worth having; but
     * there is no face to name, so there is nothing an index could address.
     */
    val inputsRefusal: String?,
    /** Everything the section draws, in the cutting plane's (u, v) — including pieces no index names. */
    val drawn: List<ProfileElement>,
    /** OP-15: true when anything drawn here is chords rather than the curve itself. */
    val approximated: Boolean,
) {
    /** Nothing at all: the plane misses the part (or lies outside it). */
    val isEmpty: Boolean get() = drawn.isEmpty()

    /** The section's corner positions as they stand, in order, skipping the ones the plane misses. */
    val cornerPoints: List<Vec2> get() = corners.mapNotNull { it.at }

    companion object {
        val EMPTY = PlaneSection(emptyList(), emptyList(), null, null, emptyList(), false)
    }
}

/**
 * **Sections and structural faces** — the general mechanism behind a working plane's context and its inputs
 * (OP-17's section-inputs package, OP-8's provenance, OP-15's honesty line).
 *
 * The whole file is pure functions of values, returning `result to reason`, so everything here becomes an
 * invalid node with a reason and heals (OP-3).
 */
object Section3 {
    /** Distances below this (mm) count as "on the plane" — a face coincident with the cut. */
    const val ON_PLANE_TOL = 1e-7

    /** Directions closer than this to parallel count as parallel. */
    private const val DIR_EPS = 1e-9

    /** How finely a curve the vocabulary cannot state is sampled (OP-15: deterministic, never adaptive). */
    private const val SAMPLE_STEPS = 64

    private const val MESH_ONLY =
        "this solid is mesh-only (a general boolean's result, OP-9), so its section has no faces to name — " +
            "its curves draw as chords and cannot be used as construction inputs; build the geometry you want " +
            "to anchor on from the operands' own sketches instead"

    private const val REVOLVE_ONLY =
        "a revolved solid's faces are surfaces of revolution, which this slice does not name — its section " +
            "draws from the mesh and offers no construction inputs"

    private const val PRISM_ONLY =
        "this solid is a stack of slabs from the exact boolean algebra (OP-22), whose internal interfaces are " +
            "not faces — its section draws from the mesh and offers no construction inputs; a horizontal cut " +
            "through it is exact via the Section tool"

    private const val IMPORT_ONLY =
        "this body was imported from a file, so it is triangles and nothing else (OP-9) — its section draws " +
            "from the mesh and offers no construction inputs; build what you want to anchor on beside it, " +
            "and place the body against that"

    // ---- the structural face list, per feature kind ----

    /**
     * The faces of [feature] in **provenance order** (OP-8), or null with the reason it has none that are
     * constructed rather than emergent.
     *
     * For an extrusion the order is [Geom3.boundaryPieces]'s — regions in order, outer loop then holes,
     * pieces in loop order — followed by the two caps, so `Side(piece)` is at index `piece`. For a loft it is
     * band-major over the correspondence's rails, followed by the terminal sections.
     */
    fun faces(feature: Feature3): Pair<List<FacePatch>?, String?> =
        when (feature) {
            is Feature3.Extrusion -> extrusionFaces(feature) to null
            is Feature3.Prism -> prismFaces(feature) to null
            is Feature3.Loft -> loftFaces(feature)
            is Feature3.Revolution -> null to REVOLVE_ONLY
            is Feature3.MeshBoolean -> null to MESH_ONLY
            is Feature3.Imported -> null to IMPORT_ONLY
        }

    /**
     * Whether [faces] is the solid's **whole** boundary — which is what a general section may be assembled
     * from.
     *
     * False for a prism: [prismFaces] names the faces `sideFace` names (one whole side per boundary piece,
     * over the solid's full extent — the recorded "a face is one whole side" convention), which is right for
     * *sketching on* one and wrong for cutting, since the material between two slabs may not be there. Such a
     * solid's section therefore draws from the mesh and names nothing.
     */
    fun facesAreWholeBoundary(feature: Feature3): Boolean =
        when (feature) {
            is Feature3.Extrusion -> true
            is Feature3.Loft -> true
            is Feature3.Prism -> false
            is Feature3.Revolution -> false
            is Feature3.MeshBoolean -> false
            is Feature3.Imported -> false
        }

    /** Why a general section of [feature] cannot name its faces, or null when it can. */
    fun structuralRefusal(feature: Feature3): String? =
        when (feature) {
            is Feature3.Extrusion, is Feature3.Loft -> faces(feature).second
            is Feature3.Prism -> PRISM_ONLY
            is Feature3.Revolution -> REVOLVE_ONLY
            is Feature3.MeshBoolean -> MESH_ONLY
            is Feature3.Imported -> IMPORT_ONLY
        }

    private fun extrusionFaces(f: Feature3.Extrusion): List<FacePatch> {
        val out = ArrayList<FacePatch>()
        val p = f.sketch.plane
        val axis = p.normal.normalized()
        var i = 0
        for (r in f.sketch.regions) {
            for (loop in listOf(r.outer) + r.holes) {
                for (e in loop.elements) out.add(sweptFace(p, axis, f.depth, e, FaceName.Side(i++)))
            }
        }
        out.add(capFace(f.sketch.regions, p.flipped(), mirror = true, name = FaceName.Cap(SolidFace.BOTTOM)))
        out.add(capFace(f.sketch.regions, p.translated(f.depth), mirror = false, name = FaceName.Cap(SolidFace.TOP)))
        return out
    }

    /** The face boundary piece [e] sweeps, when it is a plane — else the patch that says why it is not. */
    private fun sweptFace(
        base: Plane3,
        axis: Vec3,
        depth: Double,
        e: ProfileElement,
        name: FaceName,
    ): FacePatch =
        when (e) {
            is ProfileElement.Seg -> {
                val a = base.toWorld(e.segment.a)
                val b = base.toWorld(e.segment.b)
                val d = b - a
                val len = d.length()
                if (len <= Geom3.WELD_TOL) {
                    FacePatch(name, null, emptyList(), "that boundary edge has no length")
                } else {
                    val u = d * (1.0 / len)
                    // the material is on the +axis side of the base plane and, in the plane, to the *left* of
                    // a normalised loop (OP-14) — so the outward direction is the piece's right, and the frame
                    // that makes `u × v` point out of the material has v along the sweep
                    val d2 = (e.segment.b - e.segment.a).normalized()
                    val outward = (base.u * d2.y - base.v * d2.x).normalized()
                    val v = outward.cross(u).normalized()
                    val plane = Plane3(a, u, v)
                    FacePatch(name, plane, rectangle(len, depth), null)
                }
            }
            is ProfileElement.ArcE, is ProfileElement.CircleE, is ProfileElement.EllipticArcE, is ProfileElement.EllipseE ->
                FacePatch(
                    name,
                    null,
                    emptyList(),
                    "that boundary edge is curved, so the face it sweeps is a cylinder and not a plane — pick a straight edge",
                )
            is ProfileElement.BezierE ->
                FacePatch(
                    name,
                    null,
                    emptyList(),
                    "that boundary edge is a spline, so the face it sweeps is ruled and not a plane — pick a straight edge",
                )
        }

    private fun rectangle(
        w: Double,
        h: Double,
    ): List<ProfileElement> =
        listOf(
            ProfileElement.Seg(Segment(Vec2(0.0, 0.0), Vec2(w, 0.0))),
            ProfileElement.Seg(Segment(Vec2(w, 0.0), Vec2(w, h))),
            ProfileElement.Seg(Segment(Vec2(w, h), Vec2(0.0, h))),
            ProfileElement.Seg(Segment(Vec2(0.0, h), Vec2(0.0, 0.0))),
        )

    /**
     * The rectangle a **side face** covers in its own intrinsic frame (OP-17's session-32 rule): the picked
     * segment on the x axis, centred on its midpoint, the material at `v` in `0..h`.
     */
    private fun sideRectangle(
        w: Double,
        h: Double,
    ): List<ProfileElement> {
        val x = w / 2
        return listOf(
            ProfileElement.Seg(Segment(Vec2(-x, 0.0), Vec2(x, 0.0))),
            ProfileElement.Seg(Segment(Vec2(x, 0.0), Vec2(x, h))),
            ProfileElement.Seg(Segment(Vec2(x, h), Vec2(-x, h))),
            ProfileElement.Seg(Segment(Vec2(-x, h), Vec2(-x, 0.0))),
        )
    }

    private fun capFace(
        regions: List<Region>,
        plane: Plane3,
        mirror: Boolean,
        name: FaceName,
    ): FacePatch {
        val t = if (mirror) Affine(1.0, 0.0, 0.0, -1.0, 0.0, 0.0) else Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        val pieces =
            regions.flatMap { r ->
                (listOf(r.outer) + r.holes).flatMap { l -> GeomMath.transform(l, t).elements }
            }
        return FacePatch(name, plane, pieces, null)
    }

    /**
     * A prism's faces, for the one question they answer: **which plane a face is**, so a sketch space can be
     * opened on one. One whole side per boundary piece over the solid's full extent, exactly as
     * [Geom3.sideFace] names it (see [facesAreWholeBoundary] for why that is not enough to cut with).
     */
    private fun prismFaces(f: Feature3.Prism): List<FacePatch> {
        val out = ArrayList<FacePatch>()
        val pieces = Geom3.boundaryPieces(f)
        for (i in pieces.indices) {
            val (face, why) = Geom3.sideFace(f, i)
            out.add(
                if (face == null) {
                    FacePatch(FaceName.Side(i), null, emptyList(), why)
                } else {
                    // sideFace anchors on the picked segment's midpoint with v running into the face
                    FacePatch(FaceName.Side(i), face.plane, sideRectangle(face.length, face.height), null)
                },
            )
        }
        out.add(capFace(f.slabs.filter { abs(it.z0 - f.minZ) < Geom3.Z_EPS }.flatMap { it.regions }, f.plane.translated(f.minZ).flipped(), mirror = true, name = FaceName.Cap(SolidFace.BOTTOM)))
        out.add(capFace(f.slabs.filter { abs(it.z1 - f.maxZ) < Geom3.Z_EPS }.flatMap { it.regions }, f.plane.translated(f.maxZ), mirror = false, name = FaceName.Cap(SolidFace.TOP)))
        return out
    }

    /**
     * A loft's faces: one per band and rail interval, plus its terminal sections (OP-17's loft note).
     *
     * Named only while the loft is **exact** — every section boundary and every guide straight. As soon as one
     * is curved, the correspondence's rails are tessellation artifacts rather than corners of the drawing, so
     * an index into them would not survive a change of tolerance: refused by name, with the plane that does
     * work in the message.
     */
    private fun loftFaces(f: Feature3.Loft): Pair<List<FacePatch>?, String?> {
        if (f.approximated) {
            return null to
                "this loft has a curved section or guide, so its ruled faces are sampled rather than named — " +
                "put a datum plane where you want to sketch"
        }
        val (plan, why) = Geom3.loftPlan(f.sections, f.seams, f.guides)
        if (plan == null) return null to why
        val out = ArrayList<FacePatch>()
        val m = plan.railCount
        for (k in 0 until plan.sections.size - 1) {
            for (j in 0 until m) out.add(bandPatch(plan, k, j, refUpper = false))
        }
        for (k in listOf(0, plan.sections.size - 1)) {
            val prep = plan.preps.getOrNull(k) ?: continue
            val region = (plan.sections[k] as? LoftSection.Area)?.sketch?.regions?.firstOrNull() ?: continue
            val run = if (k == 0) plan.runs.first() else plan.runs.last()
            val n = prep.plane.normal.normalized()
            // out of the material: against the run at the first section, along it at the last
            val outward = if (k == 0) n.dot(run) < 0.0 else n.dot(run) > 0.0
            val plane = if (outward) prep.plane else prep.plane.flipped()
            out.add(capFace(listOf(region), plane, mirror = !outward, name = FaceName.SectionFace(k)))
        }
        return out to null
    }

    /**
     * The face over band [band] at rail interval [rail] of a loft's plan — one place both callers get it
     * from, so `faces()`'s free frame and the sketching frame cannot drift apart in anything but their
     * declared reference edge (see [bandFace]).
     */
    private fun bandPatch(
        plan: Geom3.LoftPlan,
        band: Int,
        rail: Int,
        refUpper: Boolean,
    ): FacePatch {
        val j2 = (rail + 1) % plan.railCount
        return bandFace(
            plan.ringW[band][rail],
            plan.ringW[band][j2],
            plan.ringW[band + 1][j2],
            plan.ringW[band + 1][rail],
            FaceName.Band(band, rail),
            refUpper,
        )
    }

    /**
     * The ruled quad `a → b → d → c` as a face: planar when its four corners are coplanar (which every face
     * of a polygon→apex pyramid and of an untwisted frustum is), and a named non-plane otherwise.
     *
     * The frame is the **intrinsic** one a side face uses, one dimension of freedom up (OP-17, session 32):
     * the **reference edge** — the one the picked footprint segment is, `a → b` unless [refUpper] names the
     * later section's `c → d` — lies on the x axis about its own midpoint, `v` runs across the face towards
     * the opposite edge (into the face's interior, which is what makes a pyramid's apex sit at `+v`), and
     * the normal points out of the material, so the face is seen from outside. A degenerate reference edge
     * (the apex end of a pyramid) falls back to the other one, which is the only edge there is.
     */
    private fun bandFace(
        a: Vec3,
        b: Vec3,
        d: Vec3,
        c: Vec3,
        name: FaceName,
        refUpper: Boolean = false,
    ): FacePatch {
        val lower = b - a
        val upper = d - c
        val useUpper = if (refUpper) upper.length() > Geom3.WELD_TOL else lower.length() <= Geom3.WELD_TOL
        val p = if (useUpper) c else a
        val q = if (useUpper) d else b
        val far = if (useUpper) (a + b) * 0.5 else (c + d) * 0.5
        val e0 = q - p
        if (e0.length() <= Geom3.WELD_TOL) return FacePatch(name, null, emptyList(), "that face's edge has no length")
        val along = e0.normalized()
        val out0 = lower.cross(d - a)
        val out1 = lower.cross(c - a)
        val out2 = upper.cross(a - c)
        val out =
            listOf(out0, out1, out2).maxByOrNull { it.length() }?.takeIf { it.length() >= DIR_EPS }?.normalized()
                ?: return FacePatch(name, null, emptyList(), "that face is degenerate")
        val across = (far - p).let { it - along * it.dot(along) }
        if (across.length() < DIR_EPS) return FacePatch(name, null, emptyList(), "that face is degenerate")
        // v points into the face from its reference edge; u is what right-handedness leaves (u × v = out)
        val v = across.normalized()
        val u = v.cross(out).normalized()
        val plane = Plane3((p + q) * 0.5, u, v)
        val ring = listOf(a, b, d, c)
        val off = ring.maxOf { abs(plane.distanceTo(it)) }
        if (off > 1e-6) {
            return FacePatch(
                name,
                null,
                emptyList(),
                "that face is ruled rather than flat (its corners are ${kotlin.math.round(off * 1000.0) / 1000.0} mm " +
                    "out of plane) — " +
                    "put a datum plane where you want to sketch",
            )
        }
        val local = dedupe(ring.map { plane.toLocal(it) })
        if (local.size < 3) return FacePatch(name, null, emptyList(), "that face encloses no area")
        return FacePatch(name, plane, ringPieces(oriented(local)), null)
    }

    private fun dedupe(pts: List<Vec2>): List<Vec2> {
        val out = ArrayList<Vec2>()
        for (p in pts) if (out.none { (it - p).length() <= Geom3.WELD_TOL }) out.add(p)
        return out
    }

    /** A point ring as counter-clockwise segments — OP-14's normalisation, applied to a face's own boundary. */
    private fun oriented(pts: List<Vec2>): List<Vec2> {
        var area = 0.0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            area += a.cross(b)
        }
        return if (area >= 0.0) pts else pts.reversed()
    }

    private fun ringPieces(pts: List<Vec2>): List<ProfileElement> =
        pts.indices.map { ProfileElement.Seg(Segment(pts[it], pts[(it + 1) % pts.size])) }

    // ---- the structural edge list ----

    /** The edges of [feature] in provenance order, or null with the reason it has none that are constructed. */
    fun edges(feature: Feature3): Pair<List<SolidEdge>?, String?> =
        when (feature) {
            is Feature3.Extrusion -> extrusionEdges(feature) to null
            is Feature3.Loft -> loftEdges(feature)
            is Feature3.Prism -> null to PRISM_ONLY
            is Feature3.Revolution -> null to REVOLVE_ONLY
            is Feature3.MeshBoolean -> null to MESH_ONLY
            is Feature3.Imported -> null to IMPORT_ONLY
        }

    private fun extrusionEdges(f: Feature3.Extrusion): List<SolidEdge> {
        val out = ArrayList<SolidEdge>()
        val p = f.sketch.plane
        val axis = p.normal.normalized()
        val pieces = Geom3.boundaryPieces(f)
        for ((i, e) in pieces.withIndex()) {
            val s = p.toWorld(GeomMath.startOf(e))
            out.add(SolidEdge(EdgeName.Upright(i), EdgeGeom.Straight(s, s + axis * f.depth)))
        }
        for ((i, e) in pieces.withIndex()) out.add(SolidEdge(EdgeName.CapPiece(SolidFace.BOTTOM, i), EdgeGeom.OnPlane(p, e)))
        for ((i, e) in pieces.withIndex()) {
            out.add(SolidEdge(EdgeName.CapPiece(SolidFace.TOP, i), EdgeGeom.OnPlane(p.translated(f.depth), e)))
        }
        return out
    }

    private fun loftEdges(f: Feature3.Loft): Pair<List<SolidEdge>?, String?> {
        if (f.approximated) {
            return null to
                "this loft has a curved section or guide, so its rails are sampled rather than named — " +
                "put a datum plane where you want to sketch"
        }
        val (plan, why) = Geom3.loftPlan(f.sections, f.seams, f.guides)
        if (plan == null) return null to why
        val out = ArrayList<SolidEdge>()
        val m = plan.railCount
        for (k in 0 until plan.sections.size - 1) {
            for (j in 0 until m) {
                out.add(SolidEdge(EdgeName.Rail(k, j), EdgeGeom.Straight(plan.ringW[k][j], plan.ringW[k + 1][j])))
            }
        }
        for (k in plan.sections.indices) {
            if (plan.preps.getOrNull(k) == null) continue
            for (j in 0 until m) {
                out.add(
                    SolidEdge(
                        EdgeName.SectionRing(k, j),
                        EdgeGeom.Straight(plan.ringW[k][j], plan.ringW[k][(j + 1) % m]),
                    ),
                )
            }
        }
        return out to null
    }

    // ---- the face a footprint edge names: sketch-on-face, generalized past the prism ----

    /**
     * The face of [feature] over **footprint boundary piece** [piece] — the pick a plan view can make (a side
     * face projects to exactly one footprint edge), generalized from the prism to every feature whose faces
     * are named.
     *
     * The stored address is unchanged (OP-8's boundary-piece index, what the `sketchspace` step already
     * records), so this closes the loft's *"no named end faces"* cut for the faces that **are** planes without
     * touching a single recorded file: a pyramid's lateral face is a face space, and a ruled one refuses by
     * name.
     */
    fun facePatchOfFootprintPiece(
        feature: Feature3,
        piece: Int,
    ): Pair<FacePatch?, String?> {
        if (feature !is Feature3.Loft) {
            // The prism route is [Geom3.sideFace] verbatim — frame, anchor and refusals — because that frame
            // is the **sketching** convention (OP-17): the picked segment on the x axis, v into the face,
            // the normal out of the material. Not a duplicate of [faces]'s own frame for the same face: this
            // one is what a user's coordinates are measured in and the other one is free.
            val (face, why) = Geom3.sideFace(feature, piece)
            if (face == null) return null to why
            return FacePatch(FaceName.Side(piece), face.plane, sideRectangle(face.length, face.height), null) to null
        }
        val (plan, why2) = Geom3.loftPlan(feature.sections, feature.seams, feature.guides)
        if (plan == null) return null to why2
        val (fs, whyFaces) = faces(feature)
        if (fs == null) return null to whyFaces
        val k = feature.sections.indexOfFirst { it is LoftSection.Area }
        if (k < 0) return null to "this loft has no area section to take a face from"
        val band = if (k < feature.sections.size - 1) k else k - 1
        val rail = plan.railOfPiece(k, piece) ?: return null to "this solid has no boundary piece #${piece + 1}"
        if (band < 0 || band + 1 >= plan.ringW.size || rail >= plan.railCount) {
            return null to "this solid has no boundary piece #${piece + 1} (it has ${Geom3.boundaryPieces(feature).size})"
        }
        // the picked footprint segment is section k's own ring edge — the band's lower edge unless the area
        // section is the *last* one, and the frame is built on it (OP-17's intrinsic rule)
        val patch = bandPatch(plan, band, rail, refUpper = band != k)
        if (patch.plane == null) return null to (patch.reason ?: "that face is ruled rather than flat — put a datum plane where you want to sketch")
        return patch to null
    }

    // ---- the section itself ----

    /**
     * The **section of [solid] at [plane]**, in the plane's own (u, v) — one function, three answers, in this
     * order:
     *
     * 1. the plane lies **on a face** → the degenerate section: that face's own boundary, exact, its pieces
     *    and corners the inputs (this is what a face space draws, and why it needs no mechanism of its own);
     * 2. the solid's faces are **named and complete** → the structural section: one entry per face and per
     *    edge, exact where the cut is statable and sampled-and-flagged where it is a conic (OP-15);
     * 3. otherwise → the **mesh** section: it draws, in chords, and offers no inputs, with the reason named.
     */
    fun sectionOf(
        solid: Solid3,
        plane: Plane3,
    ): PlaneSection {
        val feature = solid.feature
        val (fs, whyFaces) = faces(feature)
        if (fs != null) {
            val on = fs.firstOrNull { it.plane != null && coincident(it.plane, plane) }
            if (on != null) return faceSection(on, plane)
            if (facesAreWholeBoundary(feature)) return structuralSection(feature, fs, plane)
        }
        return meshSection(solid.mesh, plane, whyFaces ?: structuralRefusal(feature) ?: MESH_ONLY)
    }

    /** Whether [face] and [cut] are the same plane — parallel normals, and the origin lying in the other. */
    private fun coincident(
        face: Plane3,
        cut: Plane3,
    ): Boolean {
        val a = face.normal.normalized()
        val b = cut.normal.normalized()
        if (abs(abs(a.dot(b)) - 1.0) > 1e-9) return false
        return abs(cut.distanceTo(face.origin)) <= ON_PLANE_TOL
    }

    /**
     * The face case: the section **is** the face's boundary, re-read in the cutting plane's own coordinates.
     *
     * The boundary is normalised counter-clockwise in *those* coordinates (OP-14's rule, and the same honest
     * caveat: a ring turned inside out renames its own edges), so the index order is the face's own piece
     * order — an extrude's side face therefore comes back as the very rectangle the face view has always
     * drawn, corner for corner.
     */
    private fun faceSection(
        patch: FacePatch,
        cut: Plane3,
    ): PlaneSection {
        val fp = patch.plane!!
        val t = Affine(fp.u.dot(cut.u), fp.u.dot(cut.v), fp.v.dot(cut.u), fp.v.dot(cut.v), cut.toLocal(fp.origin).x, cut.toLocal(fp.origin).y)
        val loop = GeomMath.chainLoop(patch.outline).first
        val pieces =
            if (loop == null) {
                patch.outline.map { GeomMath.transform(it, t) }
            } else {
                // A canonical form, for the reason [RegionBool.canonical] has one: the same face read through
                // two different frames of the same plane must come back as the same list, or an index would
                // mean one edge in one view and another in the next. Counter-clockwise in *these* coordinates
                // (OP-14's rule, with its own caveat: a ring turned inside out renames its own edges), then
                // rotated to start at the corner nearest the plane's own origin — which for a face frame *is*
                // a corner of that face, so the numbering starts where the coordinates do.
                rotatedToFirstCorner(GeomMath.orient(GeomMath.transform(loop, t), ccw = true).elements)
            }
        val edges =
            pieces.mapIndexed { i, e ->
                SectionEdge(
                    "edge #${i + 1} of ${patch.name.label}",
                    e.takeIf { it !is ProfileElement.BezierE },
                    if (e is ProfileElement.BezierE) GeomMath.tessellatePiece(e) else null,
                    null,
                )
            }
        val corners =
            pieces.mapIndexed { i, e ->
                SectionCorner("corner #${i + 1} of ${patch.name.label}", GeomMath.startOf(e), null)
            }
        return PlaneSection(
            edges,
            corners,
            patch.name,
            null,
            pieces,
            pieces.any { it is ProfileElement.BezierE },
        )
    }

    /**
     * A closed chain of pieces, rotated so the corner **lowest in (v, then u)** comes first.
     *
     * A canonical start is needed for the reason the counter-clockwise normalisation above is (an index must
     * mean the same edge whichever frame of the plane reads it), and this particular rule is chosen because
     * it is **translation-invariant**: a space whose origin has been moved — anchored on a corner, offset by
     * (dx, dy) — must not renumber its own section, and "nearest the origin" (what this used to do) would.
     * For a face frame it starts at the picked segment's own first corner, so edge #1 is the picked edge.
     */
    private fun rotatedToFirstCorner(pieces: List<ProfileElement>): List<ProfileElement> {
        if (pieces.size < 2) return pieces
        val starts = pieces.map { GeomMath.startOf(it) }
        val loY = starts.minOf { it.y }
        // the tolerance is what keeps two corners of one horizontal edge from swapping under an offset's
        // last bit: they are then compared by u, which is a real difference
        val best = pieces.indices.filter { starts[it].y <= loY + Geom3.WELD_TOL }.minByOrNull { starts[it].x } ?: 0
        return List(pieces.size) { pieces[(best + it) % pieces.size] }
    }

    /** The general case: cut every named face, cross every named edge. */
    private fun structuralSection(
        feature: Feature3,
        fs: List<FacePatch>,
        cut: Plane3,
    ): PlaneSection {
        val drawn = ArrayList<ProfileElement>()
        val edges = ArrayList<SectionEdge>()
        for (patch in fs) {
            val (edge, extra) = cutFace(feature, patch, cut)
            edges.add(edge)
            edge.curve?.let { drawn.add(it) }
            edge.sampled?.let { drawn.addAll(polylinePieces(it)) }
            drawn.addAll(extra)
        }
        val (es, whyEdges) = edges(feature)
        val corners =
            es?.map { e ->
                val (at, why) = cutEdge(e.geom, cut)
                SectionCorner(e.name.label, at, why)
            } ?: emptyList()
        return PlaneSection(
            edges,
            corners,
            null,
            if (es == null) whyEdges else null,
            drawn,
            edges.any { it.approximated },
        )
    }

    private fun polylinePieces(pts: List<Vec2>): List<ProfileElement> =
        (0 until pts.size - 1).mapNotNull {
            if ((pts[it + 1] - pts[it]).length() <= Geom3.WELD_TOL) null else ProfileElement.Seg(Segment(pts[it], pts[it + 1]))
        }

    /**
     * Cut one named face: the named curve, plus anything else that face contributes to the drawing.
     *
     * A face cut into **several** pieces is drawn whole and named not at all — the type refusing rather than
     * the geometry failing, exactly as a multi-piece [Geom3.sectionAt] is refused: one index must mean one
     * curve, and *which* of two pieces an index meant would change as the geometry moved.
     */
    private fun cutFace(
        feature: Feature3,
        patch: FacePatch,
        cut: Plane3,
    ): Pair<SectionEdge, List<ProfileElement>> {
        val label = patch.name.label
        if (patch.plane != null) {
            val pieces = cutPlanarFace(patch.plane, patch.outline, cut)
            return when (pieces.size) {
                0 -> SectionEdge(label, null, null, "the plane does not cut $label") to emptyList()
                1 -> SectionEdge(label, pieces[0], null, null) to emptyList()
                else ->
                    SectionEdge(
                        label,
                        null,
                        null,
                        "the plane cuts $label into ${pieces.size} separate pieces, and one input is one curve — " +
                            "move the plane to where that face is crossed once",
                    ) to pieces
            }
        }
        // the exact case first, and it is the one a mechanical drawing lives on: a cylinder cut **perpendicular
        // to its axis** is that cylinder's own arc or circle, derived from the profile rather than fitted to
        // samples (OP-15 — exact means the numbers come from the parameters)
        perpendicularCylinderCut(feature, patch.name, cut)?.let { return SectionEdge(label, it, null, null) to emptyList() }
        // ...and, since the conics package (OP-24), the *inclined* cut of a cylinder is exact too: it is a
        // true ellipse, and the drawing now has a name for one
        inclinedCylinderCut(feature, patch.name, cut)?.let { return SectionEdge(label, it, null, null) to emptyList() }
        val strip = ruledStrip(feature, patch.name) ?: return SectionEdge(label, null, null, patch.reason ?: "the plane does not cut $label") to emptyList()
        return cutRuledStrip(label, strip, cut)
    }

    /**
     * A planar face ∩ the cutting plane: **exact**, and one segment (or arc) per interval of the face the cut
     * runs through.
     *
     * The crossings are analytic — a line against a segment, a line against an arc — so a cut through a bored
     * plate's cap keeps its exact circles; only the *inside/outside* decision between two crossings is taken
     * on the tessellated ring, which is a decision about a point far from the boundary and never about a
     * coordinate.
     */
    private fun cutPlanarFace(
        face: Plane3,
        outline: List<ProfileElement>,
        cut: Plane3,
    ): List<ProfileElement> {
        val line = cutLineIn(face, cut) ?: return emptyList()
        val ts = ArrayList<Double>()
        for (e in outline) {
            for (p in crossingsOf(e, line)) ts.add((p - line.origin).dot(line.dir))
        }
        if (ts.size < 2) return emptyList()
        ts.sort()
        val rings = ringsOf(outline)
        val out = ArrayList<ProfileElement>()
        var i = 0
        while (i < ts.size - 1) {
            val a = ts[i]
            val b = ts[i + 1]
            if (b - a <= Geom3.WELD_TOL) {
                i++
                continue
            }
            val mid = line.origin + line.dir * ((a + b) * 0.5)
            if (RegionBool.contains(rings, mid)) {
                val pa = face.toWorld(line.origin + line.dir * a)
                val pb = face.toWorld(line.origin + line.dir * b)
                out.add(ProfileElement.Seg(Segment(cut.toLocal(pa), cut.toLocal(pb))))
            }
            i++
        }
        return out
    }

    private fun ringsOf(outline: List<ProfileElement>): List<List<Vec2>> {
        val rings = ArrayList<List<Vec2>>()
        var cur = ArrayList<Vec2>()
        for (e in outline) {
            val pts = GeomMath.tessellatePiece(e)
            if (cur.isEmpty()) {
                cur.addAll(pts)
            } else if ((GeomMath.startOf(e) - cur.last()).length() <= 1e-6) {
                cur.addAll(pts.drop(1))
            } else {
                rings.add(cur)
                cur = ArrayList(pts)
            }
            if (cur.size > 2 && (cur.first() - cur.last()).length() <= 1e-6) {
                cur.removeAt(cur.size - 1)
                rings.add(cur)
                cur = ArrayList()
            }
        }
        if (cur.size > 2) rings.add(cur)
        return rings
    }

    /** The cutting plane, read as a line in [face]'s own 2D coordinates — null when the two are parallel. */
    private fun cutLineIn(
        face: Plane3,
        cut: Plane3,
    ): Line? {
        val n = cut.normal.normalized()
        val a = face.u.dot(n)
        val b = face.v.dot(n)
        val c = cut.distanceTo(face.origin)
        val len2 = a * a + b * b
        if (len2 <= DIR_EPS) return null
        val p0 = Vec2(-c * a / len2, -c * b / len2)
        return Line(p0, Vec2(-b, a).normalized())
    }

    /** Where a boundary piece crosses a line, analytically per kind (a spline is sampled). */
    private fun crossingsOf(
        e: ProfileElement,
        line: Line,
    ): List<Vec2> =
        when (e) {
            is ProfileElement.Seg -> {
                val d = e.segment.b - e.segment.a
                val hits = GeomMath.intersectLL(line, Line(e.segment.a, d)).points
                hits.filter { p ->
                    val t = (p - e.segment.a).dot(d) / max(d.dot(d), 1e-18)
                    t >= -1e-9 && t <= 1.0 + 1e-9
                }
            }
            is ProfileElement.ArcE ->
                GeomMath.intersectLC(line, Circle(e.arc.center, e.arc.radius)).points.filter {
                    GeomMath.arcContains(e.arc, (it - e.arc.center).angle())
                }
            is ProfileElement.CircleE -> GeomMath.intersectLC(line, e.circle).points
            // exact for a conic too, since session 27: line ∩ ellipse is a quadratic (OP-24)
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

    // ---- ruled faces: the cylinder, the spline sweep, the twisted band ----

    /**
     * A face that is not a plane, as the family of straight **rulings** it is made of: `t → (from, to)` over
     * `t ∈ 0..1`, plus whether the family closes on itself.
     *
     * One shape for three surfaces — an extrude's cylinder (the ruling is the sweep at one boundary
     * parameter), an extrude's spline sweep, and a loft's twisted band — because the cut is the same
     * operation on all of them: solve the ruling for where the plane crosses it. Exactly what OP-15 calls the
     * approximated class: exact at every ruling, chords between.
     */
    private class RuledStrip(val closed: Boolean, val at: (Double) -> Pair<Vec3, Vec3>, val steps: Int)

    /**
     * A cylindrical face cut **perpendicular to its own axis**: the profile's arc or circle itself, restated in
     * the cutting plane's coordinates. Null when the cut is not perpendicular, or lands off the sweep.
     *
     * This is the exact half of OP-15's line for curved faces, and it is exact *by construction*: the two
     * planes are parallel, so the map between them is a rigid 2D affine and the piece keeps its kind. The
     * turned part's measured section — a lathe's own question — therefore comes back as a circle with the
     * profile's radius and not as a barrel of chords.
     */
    private fun perpendicularCylinderCut(
        feature: Feature3,
        name: FaceName,
        cut: Plane3,
    ): ProfileElement? {
        if (feature !is Feature3.Extrusion || name !is FaceName.Side) return null
        val e = Geom3.boundaryPieces(feature).getOrNull(name.piece) ?: return null
        if (e !is ProfileElement.ArcE && e !is ProfileElement.CircleE) return null
        val p = feature.sketch.plane
        val axis = p.normal.normalized()
        val n = cut.normal.normalized()
        val along = axis.dot(n)
        if (abs(abs(along) - 1.0) > 1e-9) return null
        val t = -cut.distanceTo(p.origin) / along
        val lo = min(0.0, feature.depth)
        val hi = max(0.0, feature.depth)
        if (t < lo - ON_PLANE_TOL || t > hi + ON_PLANE_TOL) return null
        val at = p.translated(t)
        val o = cut.toLocal(at.origin)
        return GeomMath.transform(e, Affine(at.u.dot(cut.u), at.u.dot(cut.v), at.v.dot(cut.u), at.v.dot(cut.v), o.x, o.y))
    }

    /**
     * A cylindrical face cut by an **inclined** plane: the true ellipse it is, **exact** (OP-24).
     *
     * This is where the conics package moves OP-15's honesty line outward, and it moves it by a change in
     * *compute* alone: a point of the cylinder is `Q(φ) = C + r·cos φ·u + r·sin φ·v`, the ruling through it
     * meets the plane at `Q(φ) − axis·dist(Q(φ))/(axis·n)`, and because `dist` is affine in `cos φ` and
     * `sin φ` the whole expression is `C₀ + cos φ·A + sin φ·B` — an ellipse given by two conjugate
     * semi-diameters (see [Conics.cylinderSection]). Nothing is fitted to samples; the numbers come from the
     * feature's own parameters, which is what *exact* means here.
     *
     * Two conditions, both refusals rather than approximations. The plane must not be parallel to the axis
     * (that section is a pair of rulings, not an ellipse), and it must cross **every** ruling of the swept
     * range *within the extrusion's own depth* — otherwise the cut runs off the ends of the cylinder and the
     * section is a mixture of elliptic and straight pieces, which one named curve cannot be. Either way the
     * sampled path below still answers, exactly as it did before.
     */
    private fun inclinedCylinderCut(
        feature: Feature3,
        name: FaceName,
        cut: Plane3,
    ): ProfileElement? {
        if (feature !is Feature3.Extrusion || name !is FaceName.Side) return null
        val e = Geom3.boundaryPieces(feature).getOrNull(name.piece) ?: return null
        val circle =
            when (e) {
                is ProfileElement.ArcE -> Circle(e.arc.center, e.arc.radius)
                is ProfileElement.CircleE -> e.circle
                else -> return null
            }
        val p = feature.sketch.plane
        val axis = p.normal.normalized()
        val k = axis.dot(cut.normal.normalized())
        if (abs(k) < 1e-9 || abs(abs(k) - 1.0) <= 1e-9) return null
        val centre3 = p.toWorld(circle.center)
        val ell = Conics.cylinderSection(centre3, axis, p.u, p.v, circle.radius, cut) ?: return null
        // every ruling of the swept range must be crossed inside the material
        val lo = min(0.0, feature.depth) - ON_PLANE_TOL
        val hi = max(0.0, feature.depth) + ON_PLANE_TOL

        fun crossesInside(phi: Double): Boolean {
            val q = p.toWorld(circle.center + Vec2(circle.radius * cos(phi), circle.radius * sin(phi)))
            val s = -cut.distanceTo(q) / k
            return s >= lo && s <= hi
        }
        return when (e) {
            is ProfileElement.CircleE -> {
                // a sinusoid over a full turn: its extremes are the centre's crossing ± the in-plane reach
                val n = cut.normal.normalized()
                val reach = circle.radius * kotlin.math.hypot(n.dot(p.u), n.dot(p.v)) / abs(k)
                val mid = -cut.distanceTo(centre3) / k
                if (mid - reach < lo || mid + reach > hi) return null
                ProfileElement.EllipseE(ell, e.ccw)
            }
            is ProfileElement.ArcE -> {
                val sweep = GeomMath.sweep(e.arc)
                if ((0..SAMPLE_STEPS).any { !crossesInside(e.arc.startAngle + sweep * it / SAMPLE_STEPS) }) return null
                val at = { f: Double ->
                    val q = p.toWorld(circle.center + Vec2(circle.radius * cos(f), circle.radius * sin(f)))
                    cut.toLocal(q - axis * (cut.distanceTo(q) / k))
                }
                val t0 = Conics.paramOf(ell, at(e.arc.startAngle))
                val t1 = Conics.paramOf(ell, at(e.arc.endAngle + 0.0 * sweep))
                val tm = Conics.paramOf(ell, at(e.arc.startAngle + sweep * 0.5))
                // φ → t is a monotone reparametrization, but its *direction* depends on the frame the
                // conjugate-diameter reduction happened to choose, so it is decided by where the middle
                // of the swept range lands rather than assumed
                val ccw = Conics.contains(EllipticArc(ell, t0, t1, true), tm)
                ProfileElement.EllipticArcE(EllipticArc(ell, t0, t1, ccw))
            }
            else -> null
        }
    }

    private fun ruledStrip(
        feature: Feature3,
        name: FaceName,
    ): RuledStrip? =
        when {
            feature is Feature3.Extrusion && name is FaceName.Side -> {
                val e = Geom3.boundaryPieces(feature).getOrNull(name.piece)
                val p = feature.sketch.plane
                val axis = p.normal.normalized() * feature.depth
                when (e) {
                    is ProfileElement.ArcE -> {
                        val sweep = GeomMath.sweep(e.arc)
                        RuledStrip(false, { t ->
                            val w = p.toWorld(GeomMath.arcPointAt(e.arc, e.arc.startAngle + sweep * t))
                            w to w + axis
                        }, max(SAMPLE_STEPS, GeomMath.chordSteps(e.arc.radius, sweep, GeomMath.TESS_TOL_MM)))
                    }
                    is ProfileElement.CircleE -> {
                        val sweep = if (e.ccw) 2.0 * kotlin.math.PI else -2.0 * kotlin.math.PI
                        RuledStrip(true, { t ->
                            val ang = sweep * t
                            val w = p.toWorld(e.circle.center + Vec2(e.circle.radius * kotlin.math.cos(ang), e.circle.radius * kotlin.math.sin(ang)))
                            w to w + axis
                        }, max(SAMPLE_STEPS, GeomMath.chordSteps(e.circle.radius, 2.0 * kotlin.math.PI, GeomMath.TESS_TOL_MM)))
                    }
                    is ProfileElement.BezierE ->
                        RuledStrip(false, { t ->
                            val w = p.toWorld(GeomMath.bezierPointAt(e.bezier, t))
                            w to w + axis
                        }, max(SAMPLE_STEPS, GeomMath.bezierSteps(e.bezier, GeomMath.TESS_TOL_MM)))
                    else -> null
                }
            }
            feature is Feature3.Loft && name is FaceName.Band -> {
                val (plan, _) = Geom3.loftPlan(feature.sections, feature.seams, feature.guides)
                if (plan == null) {
                    null
                } else {
                    val m = plan.railCount
                    val j2 = (name.rail + 1) % m
                    val a = plan.ringW[name.band][name.rail]
                    val b = plan.ringW[name.band][j2]
                    val c = plan.ringW[name.band + 1][name.rail]
                    val d = plan.ringW[name.band + 1][j2]
                    RuledStrip(false, { t -> (a + (b - a) * t) to (c + (d - c) * t) }, SAMPLE_STEPS)
                }
            }
            else -> null
        }

    /**
     * A ruled face ∩ the cutting plane, and the honesty line drawn where OP-15 puts it.
     *
     * Two shortcuts are **exact** and taken first, because they are the cuts a mechanical drawing actually
     * makes: a cylinder cut **perpendicular** to its axis is that cylinder's own circle (derived from the
     * profile, not from the mesh), and one cut **along** its axis is a ruling. Everything else — the inclined
     * cut through a cylinder, the cut through a twisted band — is a curve this vocabulary contains no name
     * for — the cut through a twisted band, and a cylinder's cut that leaves the material through its ends —
     * so it comes back **sampled and flagged**: exact at every ruling, chords between. The inclined cut of a
     * cylinder no longer arrives here at all: it is answered exactly, one level up (see
     * [inclinedCylinderCut]), which is the change first-class conics bought.
     */
    private fun cutRuledStrip(
        label: String,
        strip: RuledStrip,
        cut: Plane3,
    ): Pair<SectionEdge, List<ProfileElement>> {
        val runs = ArrayList<ArrayList<Vec2>>()
        var cur: ArrayList<Vec2>? = null
        val n = strip.steps
        for (i in 0..n) {
            val t = i.toDouble() / n
            val hit = crossRuling(strip.at(t), cut)
            if (hit == null) {
                cur = null
            } else {
                if (cur == null) {
                    cur = ArrayList()
                    runs.add(cur)
                }
                if (cur.isEmpty() || (cur.last() - hit).length() > Geom3.WELD_TOL) cur.add(hit)
            }
        }
        if (runs.isEmpty()) return SectionEdge(label, null, null, "the plane does not cut $label") to emptyList()
        if (strip.closed && runs.size == 1 && runs[0].size > 2) {
            val r = runs[0]
            if ((r.first() - r.last()).length() > Geom3.WELD_TOL) r.add(r.first())
        }
        if (runs.size > 1) {
            return SectionEdge(
                label,
                null,
                null,
                "the plane cuts $label into ${runs.size} separate pieces, and one input is one curve — " +
                    "move the plane to where that face is crossed once",
            ) to runs.flatMap { polylinePieces(it) }
        }
        val pts = runs[0]
        if (pts.size < 2) return SectionEdge(label, null, null, "the plane only touches $label") to emptyList()
        // a cut that comes out **straight** is a segment and is stated as one: a ruling of the cylinder (a plane
        // through its axis), and every cut of a strip whose two edges happen to be parallel
        if (pts.size == 2 || straightWithin(pts)) {
            return SectionEdge(label, ProfileElement.Seg(Segment(pts.first(), pts.last())), null, null) to emptyList()
        }
        return SectionEdge(label, null, pts, null) to emptyList()
    }

    /** Where the plane crosses one ruling, or null when it misses it — an exact line/plane solve. */
    private fun crossRuling(
        ruling: Pair<Vec3, Vec3>,
        cut: Plane3,
    ): Vec2? {
        val d0 = cut.distanceTo(ruling.first)
        val d1 = cut.distanceTo(ruling.second)
        val den = d0 - d1
        if (abs(den) <= DIR_EPS) return if (abs(d0) <= ON_PLANE_TOL) cut.toLocal(ruling.first) else null
        val s = d0 / den
        if (s < -1e-12 || s > 1.0 + 1e-12) return null
        return cut.toLocal(ruling.first + (ruling.second - ruling.first) * s.coerceIn(0.0, 1.0))
    }

    private fun straightWithin(pts: List<Vec2>): Boolean {
        val a = pts.first()
        val b = pts.last()
        val d = b - a
        val len = d.length()
        if (len <= Geom3.WELD_TOL) return false
        val dir = d * (1.0 / len)
        return pts.all { abs((it - a).cross(dir)) <= ON_PLANE_TOL }
    }

    /** Where the plane crosses one named edge — exact, and refusing rather than guessing (OP-3). */
    private fun cutEdge(
        geom: EdgeGeom,
        cut: Plane3,
    ): Pair<Vec2?, String?> =
        when (geom) {
            is EdgeGeom.Straight -> {
                val d0 = cut.distanceTo(geom.a)
                val d1 = cut.distanceTo(geom.b)
                when {
                    abs(d0) <= ON_PLANE_TOL && abs(d1) <= ON_PLANE_TOL ->
                        null to "that edge lies in the plane, so it is a whole line there and not a corner"
                    abs(d0) <= ON_PLANE_TOL -> cut.toLocal(geom.a) to null
                    abs(d1) <= ON_PLANE_TOL -> cut.toLocal(geom.b) to null
                    d0 * d1 > 0.0 -> null to "the plane does not cross that edge"
                    else -> cut.toLocal(geom.a + (geom.b - geom.a) * (d0 / (d0 - d1))) to null
                }
            }
            is EdgeGeom.OnPlane -> {
                val line = cutLineIn(geom.plane, cut)
                if (line == null) {
                    null to "the plane is parallel to the face that edge lies on"
                } else {
                    val hits = crossingsOf(geom.piece, line)
                    when (hits.size) {
                        0 -> null to "the plane does not cross that edge"
                        1 -> cut.toLocal(geom.plane.toWorld(hits[0])) to null
                        else -> null to "the plane crosses that edge ${hits.size} times, and one input is one point"
                    }
                }
            }
        }

    // ---- the mesh route: it draws, and it names nothing (OP-9's sink rule) ----

    /**
     * The section of a **mesh**: every triangle crossed contributes its chord, in the plane's own (u, v).
     *
     * Deterministic (triangle order, vertex order) and honest about what it is: chords of a tessellation, so
     * the whole section is flagged approximated and exposes no inputs — [reason] says why and what does work.
     * Drawing it anyway is the point: *where the plane cuts* is worth seeing even where nothing can be
     * anchored on it.
     */
    private fun meshSection(
        mesh: Mesh3,
        plane: Plane3,
        reason: String,
    ): PlaneSection {
        val out = ArrayList<ProfileElement>()
        for (t in mesh.triangles) {
            val vs = listOf(mesh.vertices[t.a], mesh.vertices[t.b], mesh.vertices[t.c])
            val ds = vs.map { plane.distanceTo(it) }
            val hits = ArrayList<Vec2>()
            for (i in 0..2) {
                val j = (i + 1) % 3
                if (abs(ds[i]) <= ON_PLANE_TOL) {
                    hits.add(plane.toLocal(vs[i]))
                } else if (ds[i] * ds[j] < 0.0) {
                    val s = ds[i] / (ds[i] - ds[j])
                    hits.add(plane.toLocal(vs[i] + (vs[j] - vs[i]) * s))
                }
            }
            val uniq = dedupe(hits)
            if (uniq.size == 2) out.add(ProfileElement.Seg(Segment(uniq[0], uniq[1])))
        }
        return PlaneSection(emptyList(), emptyList(), null, reason, out, true)
    }
}
