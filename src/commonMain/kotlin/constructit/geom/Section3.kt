package constructit.geom

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
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

    /**
     * A **partial** revolution's cap — the profile itself, standing at one end of the swept interval.
     *
     * [SolidFace.BOTTOM] is the cap at the interval's **low** angle and [SolidFace.TOP] the one at its
     * high angle, which is not a new convention but [Geom3.revolve]'s own winding rule read on the angle
     * (the reversed-bottom / upright-top rule the extrude uses). A complete revolution has neither.
     */
    data class RevolveCap(val which: SolidFace) : FaceName {
        override val label: String
            get() = if (which == SolidFace.TOP) "the cap at the end of the sweep" else "the cap at the start of the sweep"
    }

    /**
     * The **band a blend sweeps along one edge** of its base (session 71, slice 3) — a fillet's cylinder or
     * torus, a chamfer's plane or cone.
     *
     * [edge] is the index of the blended edge in the base's own [Section3.edges] order, which is *also* this
     * feature's own order for that edge (the dressed list keeps every base index, [Feature3.Blend]), so the
     * one number names both the band and the crease it rounds.
     */
    data class BlendBand(val edge: Int) : FaceName {
        override val label: String get() = "the rounded band along edge #${edge + 1}"
    }

    /**
     * The **corner patch** where a blend's own bands meet — the one place the rolling ball stands still
     * (session 80): the ball's spherical triangle at a convex vertex, and the surface its pivot sweeps at an
     * inside corner (a horn torus for a round, a cone for a bevel).
     *
     * [edges] are the blended edges that meet there, as indices into the base's own [Section3.edges] order —
     * which every dressed list preserves ([Feature3.Blend]), so one set of numbers names the corner whatever
     * depth of chain it was found at. Sorted, so the name is a function of the corner and not of the order
     * the bands were built in.
     *
     * These faces are appended **after** the bands, and that is deliberate: how many corners a blend has is
     * a fact about which of its edges share a vertex, so it can change when the drawing's shape does, and
     * putting them last keeps every other face's index exactly where it was (OP-17). What moves with them is
     * an address that points *at* a corner, which is the same exposure a band whose surface cannot be stated
     * already carries.
     */
    data class BlendCorner(val edges: List<Int>) : FaceName {
        override val label: String
            get() =
                "the rounded corner where " +
                    edges.joinToString(", ") { "edge #${it + 1}" }.let {
                        val at = it.lastIndexOf(", ")
                        if (at < 0) it else it.substring(0, at) + " and " + it.substring(at + 2)
                    } + " meet"
    }

    /**
     * The **inner twin** of one face of a shelled body (session 75): the cavity's own face standing behind
     * face [face] of the base, at the wall's thickness.
     *
     * [face] is the index of the outer face in the base's own [Section3.faces] order — which is *also* this
     * feature's order for it ([Feature3.Shell] keeps every base index) — so the one number names both walls
     * of one wall. That is what makes outer→inner a **structural** mapping rather than a search: the inner
     * twin of face `i` is the entry at `faces(base).size + i`.
     */
    data class ShellInner(val face: Int) : FaceName {
        override val label: String get() = "the inner face behind face #${face + 1}"
    }

    /**
     * One **strip of a skin** (session 78): the band between sections [interval] and `interval + 1` at
     * [strip] of the correspondence's own cyclic walk.
     *
     * A name of its own rather than the loft's [Band], and for the reason a name is a name: a loft's band is
     * addressed *through its footprint* by the rail a footprint edge is, and a skin's strip is not — it is one
     * entry of a constructed list, reached at the address that list gives it. Two features, two orders, no
     * arithmetic in common.
     */
    data class SkinBand(val interval: Int, val strip: Int) : FaceName {
        override val label: String
            get() = "the skin between sections ${interval + 1} and ${interval + 2} at strip #${strip + 1}"
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

    /**
     * A revolution: the **ring** traced by the start corner of profile piece [piece] — a circle over a
     * complete turn, an arc over a partial one, and a single point where that corner is on the axis.
     */
    data class RevolveRing(val piece: Int) : EdgeName {
        override val label: String get() = "the ring traced by profile corner #${piece + 1}"
    }

    /** A partial revolution: profile edge [piece] as it lies on the cap at [which] end of the sweep. */
    data class RevolveCapPiece(val which: SolidFace, val piece: Int) : EdgeName {
        override val label: String
            get() =
                "profile edge #${piece + 1} of the cap at the " +
                    (if (which == SolidFace.TOP) "end" else "start") + " of the sweep"
    }

    /**
     * A **rail of a blend's band** (session 71, slice 3): where the band along base edge [edge] runs tangent
     * onto one of the two faces that edge separated — [side] 0 for the first of the pair, 1 for the second.
     *
     * The two edges a blend *creates*, which is why they append rather than replace: the edge it consumed
     * keeps its index and its reason ([SolidEdge.reason]), and nothing renumbers (OP-21).
     */
    data class BlendRail(val edge: Int, val side: Int) : EdgeName {
        override val label: String get() = "tangent rail #${side + 1} of the rounded band along edge #${edge + 1}"
    }

    /**
     * The **inner twin** of one edge of a shelled body (session 75): where the cavity's two faces behind
     * [edge] meet.
     *
     * [edge] is the index of the outer edge in the base's own [Section3.edges] order, for
     * [FaceName.ShellInner]'s reason and by the same structural mapping — the inner twin of edge `i` is the
     * entry at `edges(base).size + i`. An edge the cavity does not have (a corner of an **open** face, whose
     * inner twin is the rim's own inner boundary) keeps its index and states that instead.
     */
    data class ShellInner(val edge: Int) : EdgeName {
        override val label: String get() = "the inner edge behind edge #${edge + 1}"
    }
}

/**
 * A face's **analytic surface, stated where it stands**: a [Revolve3.Band] together with the axis frame its
 * numbers are measured in.
 *
 * *Why the frame travels with the band* (session 71, slice 1). [Revolve3.Band] says `a cylinder of radius r
 * over the axial interval s0..s1` — a sentence that means nothing without an axis, and which the revolution
 * could leave implicit only because its one reader could always ask [Revolve3.frameOf] for the frame back.
 * An **extrusion's** arc-swept face is the very same cylinder and has no revolve frame to ask, so the frame
 * had to become part of the statement. The alternative considered and rejected was putting the frame
 * *inside* each `Band` case (`Cylinder(origin, axis, r, …)`), which would repeat it six times and force
 * [Revolve3.cutBand]'s family dispatch — which works in the frame's own `(s, r, θ)` throughout — to carry
 * data it already has; the other alternative, a **second** surface vocabulary for extrusions, would say "a
 * cylinder" twice and make a blend dispatch on the *feature* rather than on the surface, which is exactly
 * what OP-8's provenance rule is for.
 *
 * The frame is the revolution's own, generalized: a point of the surface at `(s, r, θ)` is
 * `origin + axis·s + (ref·cos θ + binormal·sin θ)·r`, and `turnStart..turnEnd` (or [full]) is the angular
 * extent of **this patch** — a revolve's turn, an extruded arc's own sweep.
 */
data class Surface3(
    /** A point the axis passes through: the band's `s` is measured from here. */
    val origin: Vec3,
    /** The axis direction, a unit vector. */
    val axis: Vec3,
    /** The radial direction at `θ = 0`, a unit vector perpendicular to [axis]. */
    val ref: Vec3,
    val turnStart: Double,
    val turnEnd: Double,
    /** Whether the patch goes all the way round, in which case the turn interval is the whole circle. */
    val full: Boolean,
    val band: Revolve3.Band,
) {
    /** `axis × ref` — the frame's third leg, derived rather than stored so the three cannot drift apart. */
    val binormal: Vec3 get() = axis.cross(ref)

    /** The world point of this surface at `(s, r, θ)`. */
    fun world(
        s: Double,
        r: Double,
        th: Double,
    ): Vec3 = origin + axis * s + (ref * cos(th) + binormal * sin(th)) * r
}

/**
 * The **two named faces an edge bounds** (OP-8, session 71 slice 1) — stated by the feature that built the
 * edge, never discovered from triangles.
 *
 * Unordered: an edge separates two faces and neither of them is "first", so [sameAs] compares as a set. The
 * two may be the **same** face, and that is not a degenerate case to guard against but a real one — the seam
 * of a cylinder extruded from a whole circle is where face #1 meets face #1.
 */
data class FacePair(val a: FaceName, val b: FaceName) {
    /** Whether [f] is one of the two. */
    fun has(f: FaceName): Boolean = a == f || b == f

    /** The face on the other side of the edge from [f], or null when [f] is not one of the two. */
    fun other(f: FaceName): FaceName? =
        when {
            a == f -> b
            b == f -> a
            else -> null
        }

    /** Whether this is the pair `{x, y}`, in either order. */
    fun sameAs(
        x: FaceName,
        y: FaceName,
    ): Boolean = (a == x && b == y) || (a == y && b == x)
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
    /**
     * The **surface** this face is a patch of, where it has an analytic one: a cylindrical, conical,
     * spherical, toroidal or flat band with the axis frame it stands in ([Surface3]) — a revolution's, and
     * since session 71 an extrusion's arc-swept side face too.
     *
     * Null for the faces whose family is the patch itself: every **planar** face (whose exact statement is
     * [plane] plus [outline], and which has no axis to name), every **ruled** one whose only description is
     * its own rulings, and every face the vocabulary refuses by name — an elliptic cylinder, a spline's
     * sweep — where [reason] says which and no half-exact answer is offered.
     *
     * It is carried on the patch rather than re-derived by whoever needs it so that the exact parameters
     * a face was **built** from — an axis, a radius, a half-angle, a band's own interval — are the ones a
     * section, a refusal and a blend all read. That is the 3D-blending work's own prerequisite: a fillet
     * between two faces is a function of their surfaces, not of their triangles.
     */
    val surface: Surface3? = null,
)

/** One edge of a solid, in the world: a straight one, or a curve lying on a known plane. */
sealed interface EdgeGeom {
    data class Straight(val a: Vec3, val b: Vec3) : EdgeGeom

    data class OnPlane(val plane: Plane3, val piece: ProfileElement) : EdgeGeom
}

/**
 * One structurally named edge of a solid — the thing a section *corner* is the cut of, and (since session
 * 71) the thing a blend is a construction over.
 *
 * [between] is the edge's **adjacency**: the two [FaceName]s it bounds, read off the feature's own structure
 * — which profile piece, which cap, which band or rail — and never off the mesh (OP-8: provenance, not
 * discovery). It is stated for every entry, degenerate ones included: a revolve corner *on* the axis traces
 * a ring that collapses to a point, and it still names the two bands it separates, so nothing drops out of
 * the ordered list and no index ever renumbers (OP-3, OP-17's index-stability rule).
 */
data class SolidEdge(
    val name: EdgeName,
    val geom: EdgeGeom,
    val between: FacePair,
    /**
     * Why this entry is **not a crease of the body as it stands**, or null when it is (session 71, slice 3).
     *
     * The one thing a dress-up feature needs that a plain list did not have: a blend *consumes* the edge it
     * rounds, and the entry may not drop out — every index in this list is an address a step may already
     * hold, so an edge that is gone stays in place and says so (OP-3's invalid-with-a-reason, OP-21's
     * index-stability rule). [geom] is still the base's carrier, so a reader that only wants the curve gets
     * the curve; a reader that wants to *build on* the edge — a second blend — is refused in these words.
     */
    val reason: String? = null,
)

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
 * One piece of what a section **draws**, together with OP-15's class of it: [approximated] is true exactly
 * when the piece is a **chord** of a curve rather than the curve itself — a ruled face's cut, a mesh
 * triangle's crossing — and false when it is the cut stated in the drawing's own vocabulary.
 *
 * A flag beside the piece rather than a parallel list, because the two must not be able to drift apart: the
 * only consumer that needs it (OP-26's step 6, which promotes a section into a curve in space) has to say
 * *per curve* whether it is exact, and a section can mix the two — a bored plate cut across its bore is four
 * exact arcs beside a twisted band's chords. [PlaneSection.drawn] is still the plain list of pieces, so
 * nothing that only wants the geometry had to learn about this.
 */
data class DrawnPiece(val piece: ProfileElement, val approximated: Boolean)

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
    /**
     * Everything the section draws, in the cutting plane's (u, v) — including pieces no index names — each
     * with OP-15's class of it ([DrawnPiece]).
     */
    val pieces: List<DrawnPiece>,
    /** OP-15: true when anything drawn here is chords rather than the curve itself. */
    val approximated: Boolean,
) {
    /** Everything the section draws, in the cutting plane's (u, v) — the geometry alone. */
    val drawn: List<ProfileElement> get() = pieces.map { it.piece }

    /** Nothing at all: the plane misses the part (or lies outside it). */
    val isEmpty: Boolean get() = pieces.isEmpty()

    /** The section's corner positions as they stand, in order, skipping the ones the plane misses. */
    val cornerPoints: List<Vec2> get() = corners.mapNotNull { it.at }

    companion object {
        val EMPTY = PlaneSection(emptyList(), emptyList(), null, null, emptyList<DrawnPiece>(), false)
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

    private const val PRISM_ONLY =
        "this solid is a stack of slabs from the exact boolean algebra (OP-22), whose internal interfaces are " +
            "not faces — its section draws from the mesh and offers no construction inputs; a horizontal cut " +
            "through it is exact via the Section tool"

    private const val SWEEP_ONLY =
        "this solid is a profile swept along a curve (OP-26), whose faces are the moving frame's and not a " +
            "constructed list — its section draws from the mesh and offers no construction inputs; put a datum " +
            "plane where you want to sketch"

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
            is Feature3.Revolution -> Revolve3.faces(feature)
            is Feature3.MeshBoolean -> null to MESH_ONLY
            is Feature3.Imported -> null to IMPORT_ONLY
            is Feature3.Sweep -> null to SWEEP_ONLY
            // **The skin's list is constructed** (session 78): one strip per (interval × piece) in the
            // correspondence's own order, then the two caps. The skin owns that order, so it is stated once
            // in [Skin3] and read from here — the very discipline the loft's `LoftPlan` exists for.
            is Feature3.Skin -> Skin3.faces(feature)
            // **The dressed list** (session 71, slice 3): the base's faces at their own indices, outlines
            // corrected where the blend consumed an edge, one band appended per blended edge. The blend owns
            // that arithmetic, so it is stated once in [Blend3] and read from here.
            is Feature3.Blend -> Blend3.dressedFaces(feature)
            // **The shelled list** (session 75): the base's faces at their own indices — an open one carrying
            // the cavity's boundary as a hole, which is the rim it actually is — followed by the inner twin of
            // every one of them. The shell owns that arithmetic, so it is stated once in [Shell3].
            is Feature3.Shell -> Shell3.faces(feature)
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
            // A revolution's faces **are** its whole boundary (OP-17's item 4): every profile boundary
            // piece sweeps exactly one band, a partial turn adds exactly two caps, and a piece lying on
            // the axis sweeps nothing — which is not a hole in the shell but the pole it closes on.
            is Feature3.Revolution -> true
            is Feature3.MeshBoolean -> false
            is Feature3.Imported -> false
            is Feature3.Sweep -> false
            // **False for a skin, and that is the honesty line rather than a gap.** Its faces are named and
            // they *are* the whole boundary — but a strip is a ruled or faired band, so the curve a plane
            // cuts it in is not one this drawing can state (OP-15). So a skin's section draws from the mesh
            // and names nothing, with [Skin3.LOFT_ONLY] as the reason; a plane lying **on** one of its faces
            // is the exact case that does work, and [sectionOf] takes it before ever asking this.
            is Feature3.Skin -> false
            // A dressed part's faces are whole exactly when its base's are: the blend replaces a strip of two
            // faces with a band it appends, so nothing leaves the shell and nothing is added outside it.
            is Feature3.Blend -> facesAreWholeBoundary(feature.base)
            // A shelled part's faces are whole exactly when its base's are: the outer boundary is the base's,
            // the inner one is the cavity's, and there is nothing else — which is what lets a working plane's
            // section of a hollow body be assembled from its named faces and show both walls.
            is Feature3.Shell -> facesAreWholeBoundary(feature.base)
        }

    /**
     * [feature] with its **dressings taken off** — the shape underneath, however many blends stand on it.
     *
     * For the readers that ask *which kind of body is this* rather than *what are its faces*: a dressed
     * revolve is still a revolve, so its caps are still picked the way a revolve's caps are picked
     * (`Document.capUnder`). Everything about the faces themselves goes through [faces], which corrects
     * them; this is only for the questions the dressing does not change.
     */
    fun undressed(feature: Feature3): Feature3 =
        when (feature) {
            is Feature3.Blend -> undressed(feature.base)
            // A shell is a dressing by the same test: it takes material away behind the faces and moves none
            // of them, so a shelled revolve is still a revolve for every question that is about *which kind of
            // body this is* rather than about its faces (session 75).
            is Feature3.Shell -> undressed(feature.base)
            else -> feature
        }

    /** Why a general section of [feature] cannot name its faces, or null when it can. */
    fun structuralRefusal(feature: Feature3): String? =
        when (feature) {
            is Feature3.Extrusion, is Feature3.Loft, is Feature3.Revolution, is Feature3.Blend, is Feature3.Shell ->
                faces(feature).second
            is Feature3.Prism -> PRISM_ONLY
            is Feature3.MeshBoolean -> MESH_ONLY
            is Feature3.Imported -> IMPORT_ONLY
            is Feature3.Sweep -> SWEEP_ONLY
            is Feature3.Skin -> Skin3.LOFT_ONLY
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

    /**
     * The face boundary piece [e] sweeps, when it is a plane — else the patch that says why it is not.
     *
     * `internal` since session 71 slice 3, because a **blend's band along a straight edge is exactly this**:
     * the blend's own section curve carried along the edge. A segment sweeps a plane and an arc sweeps a
     * cylinder whichever construction is doing the carrying, so the blend reads this rather than saying the
     * same two sentences a second time. The caller states the convention this relies on: [base]'s normal is
     * the sweep direction, and the material lies to the **left** of [e].
     */
    internal fun sweptFace(
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
            // An arc or a circle sweeps a **cylinder**, and since session 71 it says so in the vocabulary and
            // not only in prose: the refusal is unchanged (it is not a plane, so a sketch space still
            // declines) and the typed surface stands beside it for whoever wants the surface rather than a
            // plane — a blend, above all.
            is ProfileElement.ArcE ->
                FacePatch(name, null, emptyList(), CURVED_SIDE, extrudedCylinder(base, depth, Circle(e.arc.center, e.arc.radius), e.arc))
            is ProfileElement.CircleE ->
                FacePatch(name, null, emptyList(), CURVED_SIDE, extrudedCylinder(base, depth, e.circle, null))
            // An **ellipse** sweeps an elliptic cylinder, which this drawing has no word for: it refuses
            // wholly and by name, dispatched here by predicate rather than answered half-exactly further
            // down (the session-69 rule, one feature over).
            is ProfileElement.EllipticArcE, is ProfileElement.EllipseE ->
                FacePatch(
                    name,
                    null,
                    emptyList(),
                    "that boundary edge is an ellipse, so the face it sweeps is an elliptic cylinder, which this " +
                        "drawing has no name for — pick a straight edge",
                )
            is ProfileElement.BezierE ->
                FacePatch(
                    name,
                    null,
                    emptyList(),
                    "that boundary edge is a spline, so the face it sweeps is ruled and not a plane — pick a straight edge",
                )
            // an extrude **carries** a function curve — the piece tessellates into the mesh like every other
            // — and the face it sweeps is a ruled surface this drawing has no name for, so the patch is
            // `plane = null` with the reason and `surface = null`, refused by predicate up front
            is ProfileElement.FuncE ->
                FacePatch(
                    name,
                    null,
                    emptyList(),
                    "that boundary edge is a function curve, so the face it sweeps is ruled and not a plane — " +
                        "pick a straight edge",
                )
        }

    private const val CURVED_SIDE =
        "that boundary edge is curved, so the face it sweeps is a cylinder and not a plane — pick a straight edge"

    /**
     * The **cylinder** an extrusion's arc or circle sweeps, in [Surface3]'s frame: the axis is the sweep
     * direction through the piece's own centre, `θ` is measured in the sketch plane's own `(u, v)` — so the
     * piece's angles *are* the surface's — and the band's axial interval is the extrusion's depth range,
     * whichever sign the depth has.
     *
     * [arc] null means the whole circle, i.e. a patch that closes on itself.
     */
    private fun extrudedCylinder(
        base: Plane3,
        depth: Double,
        circle: Circle,
        arc: Arc?,
    ): Surface3 {
        val band = Revolve3.Band.Cylinder(circle.radius, min(0.0, depth), max(0.0, depth))
        val o = base.toWorld(circle.center)
        val axis = base.normal.normalized()
        if (arc == null) return Surface3(o, axis, base.u, 0.0, 2.0 * PI, true, band)
        val end = arc.startAngle + GeomMath.sweep(arc)
        return Surface3(o, axis, base.u, min(arc.startAngle, end), max(arc.startAngle, end), false, band)
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

    /**
     * The map from a footprint piece's own 2D coordinates into a cap face's — the identity at the **top**,
     * where the face frame is the sketch frame, and the `y` mirror at the **bottom**, where the plane is
     * flipped ([Plane3.flipped] negates `v`) so the normal points out of the material.
     *
     * One function because two things must use exactly this map and cannot be allowed to drift: the cap
     * face's own outline ([capFace]) and the cap **edges** ([extrusionEdges]) — see [CAP_EDGE_CONVENTION].
     */
    private fun capMap(mirror: Boolean): Affine =
        if (mirror) Affine(1.0, 0.0, 0.0, -1.0, 0.0, 0.0) else Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

    internal fun capFace(
        regions: List<Region>,
        plane: Plane3,
        mirror: Boolean,
        name: FaceName,
    ): FacePatch {
        val t = capMap(mirror)
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

    /**
     * **The one cap-edge convention** (session 71, slice 1), stated here and cited by both implementations —
     * [extrusionEdges] and [Revolve3.edges].
     *
     * A **cap edge** — an extrusion's [EdgeName.CapPiece], a partial revolution's [EdgeName.RevolveCapPiece]
     * — is one footprint boundary piece as it lies on one cap face. Three questions had two answers before
     * this, and now have one each:
     *
     * 1. **Which index space.** [Geom3.boundaryPieces]'s, always: `CapPiece(which, i)` and
     *    `RevolveCapPiece(which, i)` are *profile piece `i`*, the very index [FaceName.Side] uses and the one
     *    a stored `sketchspace el= piece=` has always recorded. One address space for the whole file. What
     *    this replaced is the revolve's own former space — the index of a piece in the **transformed** cap
     *    outline, which is not a construction fact at all but an artefact of a determinant: the bottom cap's
     *    map is a reflection, so [GeomMath.transform] re-orients the loop (OP-14) and piece *i* of that
     *    outline was profile piece `n − 1 − i` **within its own loop**. Nobody could have done that index
     *    arithmetic correctly from outside, which is the argument for the convention being this one.
     * 2. **Which geometry.** The piece **as it lies on the cap**, not the piece as it was drawn: the
     *    footprint piece mapped through the cap's own [capMap]/[Revolve3.capMap]. So the returned curve is in
     *    the coordinates of a face that is *in the face list*, and a reader can hold the edge and the face's
     *    outline in one frame. What this replaced is the extrusion's former answer, which handed back the
     *    untransformed sketch piece against the un-flipped sketch plane — the same world curve, in a frame
     *    belonging to no face.
     * 3. **Which plane.** [FacePatch.plane] of the cap the edge lies on. It follows from (2) and is stated so
     *    that `EdgeGeom.OnPlane.plane` of a cap edge is always a named face's plane.
     *
     * Nothing a file stores changes meaning: the world curve of every cap edge is what it was, and a cut of
     * one ([cutEdge]) is invariant under the direction a piece is traversed in, so `sectionOf` answers
     * exactly as before — only the revolve's **bottom** cap block now comes out in profile order instead of
     * reversed, which is the bug this convention exists to remove.
     */
    const val CAP_EDGE_CONVENTION =
        "a cap edge is footprint boundary piece i, in Geom3.boundaryPieces order, as it lies on the cap face — " +
            "in that face's own plane and coordinates"

    /**
     * The half-open index ranges of [Geom3.boundaryPieces] that each **loop** of the footprint occupies:
     * regions in order, each region's outer loop then its holes.
     *
     * The flat piece list has lost the one fact adjacency needs — where a ring closes — and this puts it
     * back without adding a second order to keep in step: it is [Geom3.boundaryPieces]'s own traversal,
     * counted. Its consumer is [previousInLoop], and the reason it exists is that the corner before piece #1
     * of a **hole** is the last piece of that hole and never a piece of the region around it.
     */
    fun loopSpans(feature: Feature3): List<IntRange> {
        val out = ArrayList<IntRange>()
        var at = 0
        for (r in feature.footprint) {
            for (loop in listOf(r.outer) + r.holes) {
                out.add(at until at + loop.elements.size)
                at += loop.elements.size
            }
        }
        return out
    }

    /**
     * The boundary piece **before** [piece] in its own loop — the wrap being within the loop, never across a
     * region boundary or into a hole.
     *
     * This is what makes an upright's or a ring's adjacency structural: the edge at the *start* corner of
     * piece `i` is where the face over piece `i − 1` meets the face over piece `i`, and "`i − 1`" means the
     * loop's own predecessor. A one-piece loop (a circle, an ellipse) is its own predecessor, and the edge is
     * then the seam where a face meets itself — which is a fact about the construction and not a degeneracy.
     */
    fun previousInLoop(
        feature: Feature3,
        piece: Int,
    ): Int {
        val span = loopSpans(feature).firstOrNull { piece in it } ?: return piece
        val n = span.last - span.first + 1
        return span.first + (piece - span.first + n - 1) % n
    }

    /** The edges of [feature] in provenance order, or null with the reason it has none that are constructed. */
    fun edges(feature: Feature3): Pair<List<SolidEdge>?, String?> =
        when (feature) {
            is Feature3.Extrusion -> extrusionEdges(feature) to null
            is Feature3.Loft -> loftEdges(feature)
            is Feature3.Revolution -> Revolve3.edges(feature)
            is Feature3.Prism -> null to PRISM_ONLY
            is Feature3.MeshBoolean -> null to MESH_ONLY
            is Feature3.Imported -> null to IMPORT_ONLY
            is Feature3.Sweep -> null to SWEEP_ONLY
            // **A skin's edges are the one half of its provenance this cut does not build** (session 78, and
            // it is recorded in DESIGN.md as such): its faces are named, and the creases between them are the
            // rails and the ring intervals — but an edge list is what a *blend* is a construction over, and a
            // blend of a mesh-tier body has no analytic face to run tangent onto. So it refuses in the
            // skin's own words rather than handing out edges nothing may build on.
            is Feature3.Skin -> null to Skin3.LOFT_ONLY
            // the dressed list: every base edge at its own index (a consumed one flagged with its reason and
            // never removed), then two tangent rails appended per blended edge — see [Blend3.dressedEdges]
            is Feature3.Blend -> Blend3.dressedEdges(feature)
            // the base's edges at their own indices, then the cavity's own, appended — [Shell3.edges]
            is Feature3.Shell -> Shell3.edges(feature)
        }

    /**
     * An extrusion's edges: the upright at every boundary corner, then the boundary itself as it lies on the
     * bottom cap and on the top one — see [CAP_EDGE_CONVENTION] for what a cap edge hands back.
     *
     * The adjacency is read straight off the sweep (OP-8). An **upright** stands at the start corner of piece
     * `i`, which is where the face over the loop's previous piece meets the face over piece `i`
     * ([previousInLoop] — the wrap stays inside the loop, so a hole's first upright never names the outer
     * boundary). A **cap piece** is where that cap meets the face over the same piece `i`.
     */
    private fun extrusionEdges(f: Feature3.Extrusion): List<SolidEdge> {
        val out = ArrayList<SolidEdge>()
        val p = f.sketch.plane
        val axis = p.normal.normalized()
        val pieces = Geom3.boundaryPieces(f)
        for ((i, e) in pieces.withIndex()) {
            val s = p.toWorld(GeomMath.startOf(e))
            out.add(
                SolidEdge(
                    EdgeName.Upright(i),
                    EdgeGeom.Straight(s, s + axis * f.depth),
                    FacePair(FaceName.Side(previousInLoop(f, i)), FaceName.Side(i)),
                ),
            )
        }
        for (which in listOf(SolidFace.BOTTOM, SolidFace.TOP)) {
            val bottom = which == SolidFace.BOTTOM
            val plane = if (bottom) p.flipped() else p.translated(f.depth)
            val t = capMap(bottom)
            for ((i, e) in pieces.withIndex()) {
                out.add(
                    SolidEdge(
                        EdgeName.CapPiece(which, i),
                        EdgeGeom.OnPlane(plane, GeomMath.transform(e, t)),
                        FacePair(FaceName.Cap(which), FaceName.Side(i)),
                    ),
                )
            }
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
        val last = plan.sections.size - 1
        for (k in 0 until last) {
            for (j in 0 until m) {
                // a rail runs *along* the run, so it is the crease between the two bands of the same band
                // row that meet at rail j — the previous rail interval and this one, wrapping round the ring
                out.add(
                    SolidEdge(
                        EdgeName.Rail(k, j),
                        EdgeGeom.Straight(plan.ringW[k][j], plan.ringW[k + 1][j]),
                        FacePair(FaceName.Band(k, (j + m - 1) % m), FaceName.Band(k, j)),
                    ),
                )
            }
        }
        for (k in plan.sections.indices) {
            if (plan.preps.getOrNull(k) == null) continue
            for (j in 0 until m) {
                // a section's own ring edge is the crease *across* the run: the band below it meets the band
                // above it, and at a terminal section one of the two is that section's own face — which
                // exists exactly when the ring does, since [loftFaces] caps every area section that is
                // terminal and [loftEdges] rings every section that has a prep (an apex has neither)
                val below = if (k == 0) FaceName.SectionFace(0) else FaceName.Band(k - 1, j)
                val above = if (k == last) FaceName.SectionFace(last) else FaceName.Band(k, j)
                out.add(
                    SolidEdge(
                        EdgeName.SectionRing(k, j),
                        EdgeGeom.Straight(plan.ringW[k][j], plan.ringW[k][(j + 1) % m]),
                        FacePair(below, above),
                    ),
                )
            }
        }
        return out to null
    }

    // ---- the generic accessors the blend consumes ----

    /**
     * The edges of [feature] that bound face [face], in the edge list's own order — *"the edges of face f"*,
     * the first of the two questions an edge blend asks (session 71).
     *
     * Generic by construction: it reads the stated [SolidEdge.between] of every edge and knows nothing about
     * which feature made them, so a feature that names its faces gets this for free and one that refuses
     * ([edges] returning a reason) refuses here too, by the same words.
     */
    fun edgesOfFace(
        feature: Feature3,
        face: FaceName,
    ): Pair<List<SolidEdge>?, String?> {
        val (fs, whyFaces) = faces(feature)
        if (fs == null) return null to whyFaces
        if (fs.none { it.name == face }) return null to "this solid has no ${face.label}"
        val (es, whyEdges) = edges(feature)
        if (es == null) return null to whyEdges
        return es.filter { it.between.has(face) } to null
    }

    /**
     * The edge of [feature] between faces [a] and [b] — *"the edge between f and g"*, the second question,
     * and the one an edge blend is actually addressed by.
     *
     * Two faces may meet along **several** edges — the two arcs of a slot's outline meet at both ends — and
     * that is refused rather than resolved, for the reason every other one-input-is-one-curve refusal in this
     * file is: which of two an index meant would change as the geometry moved. Name the edge itself then.
     */
    fun edgeBetween(
        feature: Feature3,
        a: FaceName,
        b: FaceName,
    ): Pair<SolidEdge?, String?> {
        val (fs, whyFaces) = faces(feature)
        if (fs == null) return null to whyFaces
        for (f in listOf(a, b)) if (fs.none { it.name == f }) return null to "this solid has no ${f.label}"
        val (es, whyEdges) = edges(feature)
        if (es == null) return null to whyEdges
        val hits = es.filter { it.between.sameAs(a, b) }
        return when (hits.size) {
            0 -> null to "${a.label} and ${b.label} do not meet along an edge"
            1 -> hits[0] to null
            else ->
                null to
                    "${a.label} and ${b.label} meet along ${hits.size} separate edges, and one input is one edge — " +
                    "name the edge itself"
        }
    }

    // ---- the face a footprint edge names: sketch-on-face, generalized past the prism ----

    /**
     * **The whole stored address space, stated once** (OP-17's `sketchspace el= piece=`, extended in session
     * 74 for edit-in-3D slice 2) — cited by [facePatchOfFootprintPiece] and [addressOfFace], which are the
     * only two functions that may read or write it.
     *
     * `0 until n` (with `n = Geom3.boundaryPieces(feature).size`) is the face **over footprint boundary piece
     * i** — an extrusion's or a revolution's `Side(i)`, a loft's `Band` at the rail that piece is. That is
     * OP-8's original address and no stored literal changes meaning.
     *
     * `n` onward are the faces of [faces] that stand over **no** footprint piece — the flat ends: an
     * extrusion's or a prism's two [FaceName.Cap]s, a partial revolution's two [FaceName.RevolveCap]s, a
     * loft's terminal [FaceName.SectionFace]s — in the **face list's own order**, which for all three is
     * low end first. The revolution has addressed its caps that way since session 63
     * ([Revolve3.facePatchOf]); this generalizes the same convention to the other two rather than inventing a
     * second one, and it is what a 3D face click needs, because a ray reaches a cap that no footprint edge
     * projects to. Nothing a file stores changes meaning: every one of those indices was a **refusal** before
     * (`this solid has no boundary piece #k`), so no build could have written one.
     */
    const val FACE_ADDRESS_CONVENTION =
        "face address i < n is the face over footprint boundary piece i (Geom3.boundaryPieces order); " +
            "i >= n is the (i − n)-th face standing over no footprint piece — the flat ends, in Section3.faces order"

    /**
     * The face of [feature] over **footprint boundary piece** [piece] — the pick a plan view can make (a side
     * face projects to exactly one footprint edge), generalized from the prism to every feature whose faces
     * are named, and past the footprint to the flat ends (see [FACE_ADDRESS_CONVENTION]).
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
        // **A dressed part is sketched on exactly where its base was** (session 71, slice 3): the frame is
        // the base's — same plane, same origin, same u — because a stored `sketchspace el= piece=` must go on
        // meaning what it meant (OP-18), and a blend does not move a face, it trims it. What the face space
        // *draws* is the trimmed outline, taken from the dressed list where the two frames are the same
        // plane, so the picture shows the rounded corner the body actually has.
        if (feature is Feature3.Blend) {
            val (base, why) = facePatchOfFootprintPiece(feature.base, piece)
            if (base == null) return null to why
            val trimmed =
                faces(feature).first?.firstOrNull { it.name == base.name && it.plane != null && it.plane == base.plane }
            return (if (trimmed == null) base else base.copy(outline = trimmed.outline)) to null
        }
        // **A shelled part is sketched on exactly where its base was**, and its *inner* faces are reached past
        // the base's own ends by the very address space that already reaches a cap (session 75, and
        // [FACE_ADDRESS_CONVENTION] unchanged): a shell's face list is base-then-inner, so the base's ends keep
        // their addresses and the inner faces take the ones after them — every one of which was a refusal
        // before, so no stored byte changes meaning (OP-18). A side face's frame stays the base's, with the
        // shell's own outline where the two frames are the same plane (the rim's hole).
        if (feature is Feature3.Shell) {
            if (piece >= Geom3.boundaryPieces(feature).size) return endFacePatch(feature, piece)
            val (base, why) = facePatchOfFootprintPiece(feature.base, piece)
            if (base == null) return null to why
            val shelled =
                faces(feature).first?.firstOrNull { it.name == base.name && it.plane != null && it.plane == base.plane }
            return (if (shelled == null) base else base.copy(outline = shelled.outline)) to null
        }
        // A revolution's face is the band its profile piece sweeps, and indices past the profile's own
        // pieces are the two caps of a partial turn ([Revolve3.facePatchOf]) — one address space, so a
        // recorded `sketchspace el= piece=` needs no format change to reach either.
        if (feature is Feature3.Revolution) return Revolve3.facePatchOf(feature, piece)
        // …and past the footprint's own pieces, the flat ends, in the face list's order
        // ([FACE_ADDRESS_CONVENTION]) — which is where the revolution's caps have always been.
        if (piece >= Geom3.boundaryPieces(feature).size) return endFacePatch(feature, piece)
        // **A skin's faces stand over no footprint piece** (session 78), and that is what keeps their stored
        // addresses still: its plan hint is its first section's outline, drawn in that station's own space, so
        // a footprint edge of one names nothing. Every face it *has* is reached past the footprint, at the
        // address a click on it records ([FACE_ADDRESS_CONVENTION], and [Skin3.faces] for the order).
        if (feature is Feature3.Skin) {
            return null to
                "a skin over drawn sections is not addressed by a footprint edge — click the face itself in the " +
                "3D view, or click one of its end sections to sketch on it"
        }
        if (feature !is Feature3.Loft) {
            // The prism route is [Geom3.sideFace] verbatim — frame, anchor and refusals — because that frame
            // is the **sketching** convention (OP-17): the picked segment on the x axis, v into the face,
            // the normal out of the material. Not a duplicate of [faces]'s own frame for the same face: this
            // one is what a user's coordinates are measured in and the other one is free.
            val (face, why) = Geom3.sideFace(feature, piece)
            if (face == null) return null to why
            return FacePatch(FaceName.Side(piece), face.plane, sideRectangle(face.length, face.height), null) to null
        }
        val (addr, why2) = loftAddress(feature)
        if (addr == null) return null to why2
        val rail = addr.plan.railOfPiece(addr.section, piece) ?: return null to "this solid has no boundary piece #${piece + 1}"
        if (rail >= addr.plan.railCount) {
            return null to "this solid has no boundary piece #${piece + 1} (it has ${Geom3.boundaryPieces(feature).size})"
        }
        // the picked footprint segment is section k's own ring edge — the band's lower edge unless the area
        // section is the *last* one, and the frame is built on it (OP-17's intrinsic rule)
        val patch = bandPatch(addr.plan, addr.band, rail, refUpper = addr.band != addr.section)
        if (patch.plane == null) return null to (patch.reason ?: "that face is ruled rather than flat — put a datum plane where you want to sketch")
        return patch to null
    }

    /**
     * The **flat end** at address `piece` — entry `piece − n` of the faces standing over no footprint piece
     * ([FACE_ADDRESS_CONVENTION]).
     *
     * Read off [faces] rather than re-derived, so a cap's sketching frame *is* the frame the face list states
     * for it: an extrusion's top cap is the sketch's own (u, v) — draw on it in the coordinates the footprint
     * was drawn in — and the bottom cap is that frame with `v` mirrored, which is what makes its normal point
     * out of the material ([capMap]). There is no picked edge to anchor on here and no choice to make.
     */
    private fun endFacePatch(
        feature: Feature3,
        piece: Int,
    ): Pair<FacePatch?, String?> {
        val (fs, why) = faces(feature)
        if (fs == null) return null to why
        val n = Geom3.boundaryPieces(feature).size
        val ends = endFaces(fs)
        val patch =
            ends.getOrNull(piece - n)
                ?: return null to "this solid has no face #${piece + 1} (it has ${n + ends.size})"
        if (patch.plane == null) return null to (patch.reason ?: "that face is not a plane — put a datum plane where you want to sketch")
        return patch to null
    }

    /**
     * **How many addresses this body has**, side faces and flat ends together — what a reader that wants to
     * walk the whole address space counts up to ([FACE_ADDRESS_CONVENTION]).
     */
    fun faceAddressCount(feature: Feature3): Int =
        Geom3.boundaryPieces(feature).size + (faces(feature).first?.let { endFaces(it).size } ?: 0)

    /**
     * The faces of [fs] the address space puts **past** the footprint's own pieces — the flat ends.
     *
     * A dressing's own appended band is not one of them, and that is the one exclusion worth stating: a
     * blend's band has no address at all (a chamfer's included, flat though it is), because the address space
     * says nothing about faces a dress-up feature adds. It is refused by name instead of given an index that
     * would mean something else after the next blend.
     */
    private fun endFaces(fs: List<FacePatch>): List<FacePatch> =
        fs.filter { !overFootprintPiece(it.name) && it.name !is FaceName.BlendBand }

    /** Whether [name] is the face over a footprint boundary piece — the `0 until n` half of the address space. */
    private fun overFootprintPiece(name: FaceName): Boolean = name is FaceName.Side || name is FaceName.Band

    /** A loft's footprint-piece address: the plan, the area section the footprint is, and the band it names. */
    private class LoftAddress(val plan: Geom3.LoftPlan, val section: Int, val band: Int)

    /**
     * Where a loft's `piece` addresses read from — one place, so [facePatchOfFootprintPiece] and
     * [addressOfFace] cannot drift on which band a footprint edge means.
     */
    private fun loftAddress(f: Feature3.Loft): Pair<LoftAddress?, String?> {
        val (plan, why) = Geom3.loftPlan(f.sections, f.seams, f.guides)
        if (plan == null) return null to why
        val whyFaces = faces(f).second
        if (whyFaces != null) return null to whyFaces
        val k = f.sections.indexOfFirst { it is LoftSection.Area }
        if (k < 0) return null to "this loft has no area section to take a face from"
        val band = if (k < f.sections.size - 1) k else k - 1
        if (band < 0 || band + 1 >= plan.ringW.size) {
            return null to "this solid has no boundary piece to name a face by (it has ${Geom3.boundaryPieces(f).size})"
        }
        return LoftAddress(plan, k, band) to null
    }

    /**
     * The **stored address of a named face** — the inverse of [facePatchOfFootprintPiece], and null for a face
     * the address space cannot say (see [FACE_ADDRESS_CONVENTION]).
     *
     * What a pick needs: the geometric question ("which face is under this ray") is answered in the face
     * list's own terms ([faceAt]), and this is the one translation from a [FaceName] to the integer a
     * `sketchspace … piece=` step records. Structural throughout — nothing here measures anything.
     *
     * Null for exactly three kinds of face, each of them honest rather than missing: a **blend's own band**
     * (which is a rounded strip, not a plane anybody sketches on), a loft's band over a section the footprint
     * is not (a three-section loft's middle band — a footprint edge names one band, and only one), and any
     * face of a body whose face list refuses altogether.
     */
    fun addressOfFace(
        feature: Feature3,
        name: FaceName,
    ): Int? {
        // A dressed part is addressed exactly as its base is (session 71, slice 3): the blend keeps every base
        // index, so the address of a surviving base face is the base's own.
        if (feature is Feature3.Blend) return addressOfFace(feature.base, name)
        if (name is FaceName.BlendBand) return null
        val n = Geom3.boundaryPieces(feature).size
        if (name is FaceName.Side) return name.piece.takeIf { it in 0 until n }
        if (name is FaceName.Band) {
            val f = feature as? Feature3.Loft ?: return null
            val addr = loftAddress(f).first ?: return null
            if (name.band != addr.band) return null
            return (0 until n).firstOrNull { addr.plan.railOfPiece(addr.section, it) == name.rail }
        }
        val ends = faces(feature).first?.let { endFaces(it) } ?: return null
        val k = ends.indexOfFirst { it.name == name }
        return if (k < 0) null else n + k
    }

    // ---- which face a point of the surface is on: the ray's answer, taken from the feature (slice 2) ----

    /**
     * One face of [feature] the point [at] lies on, with the address a click on it would record.
     *
     * [off] is how far the point stood off that face in mm — kept because it is the evidence the answer was
     * chosen on, and because a consumer that wants to know how sure the pick was has no other way to ask.
     */
    data class FacePick(
        val patch: FacePatch,
        /** The `sketchspace … piece=` address of [patch], or null when the address space cannot say it. */
        val piece: Int?,
        val off: Double,
    )

    /**
     * **Which face of [feature] the point [at] is on, aimed along [along]** — the authority every 3D face pick
     * goes through (edit-in-3D slice 2), and the seam slice 3 and appearance Tier 3 consume.
     *
     * The whole rule, because it is the one thing this feature is:
     *
     * 1. **The feature answers, not the triangles.** [at] is a point of a *mesh* — a ray's hit, so it lies on
     *    a **chord** and sits inside the true surface by up to the tessellation sag. It is tested against the
     *    body's own [faces]: a **planar** patch by the distance to its plane plus containment in its own
     *    outline, a **[Surface3] band** by the distance to that surface plus its stated intervals (the axial
     *    one, and the turn). Nothing reads a triangle, so the answer cannot change with the mesh quality —
     *    which is exactly why a pick may be recorded as a durable choice (OP-1/OP-18).
     * 2. **The tolerance is the mesh's own sag, never an ad-hoc epsilon.** [tol] is what the caller
     *    computed from the very mesh the ray met ([Geom3.meshSag]); a candidate farther off than that is not a
     *    candidate at all, and the refusal says so rather than snapping to the nearest thing.
     * 3. **A face you can see wins.** Where two faces both contain the point — which is exactly what a hit on
     *    an *edge* is, and the commonest case of all, since a silhouette grazes the body along one — the one
     *    whose plane faces the ray is taken. A hit at the entry point of the body cannot honestly be a
     *    back-facing face, and the alternative (lowest index) would answer "the bottom cap" for a click
     *    squarely on a pyramid's flank. Ties past that go to the nearer face, then to the lower index, so the
     *    answer is deterministic.
     *
     * Refused by name — never guessed — when the body has no named faces at all (a general boolean's result,
     * an import, a sweep: [faces]'s own reason, which is the **mesh half** of the parked face-ID item and
     * stays parked), and when no named face contains the point, which is what a hit on a **prism's** internal
     * step face is (its named faces are its sides and its two caps, not the whole boundary —
     * [facesAreWholeBoundary]).
     *
     * *One stated limit.* A [Revolve3.Band.Sphere] or [Revolve3.Band.Torus] carries no axial interval of its
     * own, so two bands lying on the **same** sphere or torus (a profile drawn with two arcs of one circle)
     * are told apart by their turn interval alone and otherwise resolve to the first of them. Both are the
     * same surface, so a refusal names the right kind of band either way; a consumer that assigns something
     * *per face* there would put it on a same-surface neighbour, which is recorded here rather than hidden.
     */
    fun faceAt(
        feature: Feature3,
        at: Vec3,
        along: Vec3,
        tol: Double,
    ): Pair<FacePick?, String?> {
        val (fs, why) = faces(feature)
        if (fs == null) return null to why
        val dir = if (along.length() <= Vec3.EPS) null else along.normalized()
        var best: FacePick? = null
        var bestFacing = 2
        // in face-list order, and a candidate has to be *strictly* better to displace one — which is what
        // makes a tie go to the lower index, and the whole answer deterministic
        for (patch in fs) {
            val off = offOfPatch(patch, at, tol) ?: continue
            // 0 = its plane faces the ray (or it is a curved band, which has no stated material side here),
            // 1 = the ray would have to pass through the body to reach it
            val facing =
                if (patch.plane == null || dir == null) {
                    0
                } else if (patch.plane.normal.normalized().dot(dir) < 0.0) {
                    0
                } else {
                    1
                }
            val b = best
            if (b == null || facing < bestFacing || (facing == bestFacing && off < b.off)) {
                best = FacePick(patch, addressOfFace(feature, patch.name), off)
                bestFacing = facing
            }
        }
        val pick = best ?: return null to "the ray met this body where no named face of it is"
        return pick to null
    }

    /**
     * How far [at] stands off [patch], or null when it is not on that face at all: outside its outline, off
     * the band's own interval, or farther than [tol] away.
     */
    private fun offOfPatch(
        patch: FacePatch,
        at: Vec3,
        tol: Double,
    ): Double? {
        val plane = patch.plane
        if (plane != null) {
            val d = abs(plane.distanceTo(at))
            if (d > tol) return null
            val rings = ringsOf(patch.outline)
            if (rings.isEmpty()) return null
            val uv = plane.toLocal(at)
            if (RegionBool.contains(rings, uv)) return d
            // a point just outside the boundary is on the face as far as a mesh hit can tell: a chord of a
            // curved edge falls short of it by the same sag the plane distance is allowed
            val edge = rings.minOf { ring -> ringDistance(ring, uv) }
            if (edge > tol) return null
            return kotlin.math.sqrt(d * d + edge * edge)
        }
        val surface = patch.surface ?: return null
        val off = surfaceOff(surface, at, tol) ?: return null
        return if (off > tol) null else off
    }

    /** The distance from [p] to the closed polyline [ring], in the ring's own plane. */
    private fun ringDistance(
        ring: List<Vec2>,
        p: Vec2,
    ): Double {
        var best = Double.MAX_VALUE
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            val ab = b - a
            val t = if (ab.length() <= Vec2.EPS) 0.0 else ((p - a).dot(ab) / ab.dot(ab)).coerceIn(0.0, 1.0)
            val d = (p - (a + ab * t)).length()
            if (d < best) best = d
        }
        return best
    }

    /**
     * How far [at] stands off the band [s] states, or null when it lies outside the band's own extent — the
     * curved half of [faceAt], done in the surface's own `(s, r, θ)` frame and therefore in millimetres.
     *
     * Each family's distance is the one its own statement gives: a cylinder's is the radial error, a cone's
     * the perpendicular distance to its generating line in the meridian half-plane, a sphere's and a torus's
     * the error of the radius they are stated by.
     */
    private fun surfaceOff(
        s: Surface3,
        at: Vec3,
        tol: Double,
    ): Double? {
        val rel = at - s.origin
        val axial = rel.dot(s.axis)
        val rad = rel - s.axis * axial
        val r = rad.length()
        if (!turnHolds(s, atan2(rad.dot(s.binormal), rad.dot(s.ref)), r, tol)) return null

        fun within(
            a: Double,
            b: Double,
        ): Boolean = axial >= min(a, b) - tol && axial <= max(a, b) + tol
        return when (val band = s.band) {
            is Revolve3.Band.Cylinder -> if (!within(band.s0, band.s1)) null else abs(r - band.r)
            is Revolve3.Band.Cone ->
                if (!within(band.s0, band.s1)) {
                    null
                } else {
                    abs(r - abs(axial - band.sApex) * band.tanHalf) / kotlin.math.sqrt(1.0 + band.tanHalf * band.tanHalf)
                }
            // no axial interval of its own — the stated limit on [faceAt]
            is Revolve3.Band.Sphere -> abs(kotlin.math.sqrt((axial - band.sc) * (axial - band.sc) + r * r) - band.radius)
            is Revolve3.Band.Torus -> {
                val ds = axial - band.sc
                val dr = r - band.rc
                abs(kotlin.math.sqrt(ds * ds + dr * dr) - band.minor)
            }
            // a flat band has a plane, so it never reaches here; the other two have no surface to be off of
            is Revolve3.Band.Planar, Revolve3.Band.Degenerate, is Revolve3.Band.Unnamed -> null
        }
    }

    /** Whether turn angle [th] is inside [s]'s swept interval, with [tol] mm of slack read at radius [r]. */
    private fun turnHolds(
        s: Surface3,
        th: Double,
        r: Double,
        tol: Double,
    ): Boolean {
        if (s.full) return true
        val slack = if (r <= tol) PI else tol / r
        val two = 2.0 * PI
        var t = th
        while (t < s.turnStart - 1e-12) t += two
        while (t > s.turnStart + two + 1e-12) t -= two
        return t <= s.turnEnd + slack || t >= s.turnStart + two - slack
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
            // a face's own boundary is the face, whatever kind its pieces are — a Bézier edge is exact
            // geometry and only its *input* reading is sampled, which is what `approximated` says here
            pieces.map { DrawnPiece(it, false) },
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

    /**
     * The **regions** [plane] cuts [feature] into, in that plane's own `(u, v)` — the section read as area
     * rather than as curves, for the bodies whose faces are all named.
     *
     * *Why it exists* (session 80). A **dressed** part has no prismatic cross-section: the rounding changes
     * the area through the blend, so no slab of the base answers for it, and `Geom3.sectionAt` refused by
     * name and pointed at a working plane instead. But the structural section already *is* the answer —
     * every face of a dressed part is named and every cut of it is stated — so what was missing was only the
     * step from curves to a closed area. The pieces come back in one plane and meet end to end by
     * construction, so this chains them and hands back what they enclose; where they do not close, that is
     * said rather than papered over (OP-3), and the caller keeps its own refusal.
     *
     * The class is the section's own (OP-15): where every piece of it is exact the area is exact, and where
     * a face is cut in chords the area is those chords'.
     */
    fun regionsOf(
        feature: Feature3,
        plane: Plane3,
    ): Pair<List<Region>?, String?> {
        val fs = faces(feature).first ?: return null to structuralRefusal(feature)
        if (!facesAreWholeBoundary(feature)) return null to (structuralRefusal(feature) ?: MESH_ONLY)
        val section = structuralSection(feature, fs, plane)
        if (section.isEmpty) return null to "the plane does not cut this solid"
        val loops =
            chainLoops(section.drawn) ?: return null to
                "the plane's section of this solid does not close into an area — one of the faces it crosses is " +
                "cut in a way this drawing states only as curves; read the section on a working plane instead"
        return nest(loops) to null
    }

    /** [pieces] chained end to end into closed loops, or null when one of them does not close. */
    private fun chainLoops(pieces: List<ProfileElement>): List<Loop>? {
        val left = pieces.filter { (GeomMath.endOf(it) - GeomMath.startOf(it)).length() > Geom3.WELD_TOL }.toMutableList()
        val out = ArrayList<Loop>()
        while (left.isNotEmpty()) {
            val run = arrayListOf(left.removeAt(0))
            while ((GeomMath.startOf(run.first()) - GeomMath.endOf(run.last())).length() > CHAIN_TOL) {
                val end = GeomMath.endOf(run.last())
                val at =
                    left.indexOfFirst {
                        (GeomMath.startOf(it) - end).length() <= CHAIN_TOL || (GeomMath.endOf(it) - end).length() <= CHAIN_TOL
                    }
                if (at < 0) return null
                val piece = left.removeAt(at)
                run.add(if ((GeomMath.startOf(piece) - end).length() <= CHAIN_TOL) piece else GeomMath.reverse(piece))
            }
            if (run.size < 2) return null
            out.add(Loop(run))
        }
        return out.ifEmpty { null }
    }

    /** The loops sorted into areas: the ones no other contains are outers, the rest are their holes. */
    private fun nest(loops: List<Loop>): List<Region> {
        val rings = loops.map { Geom3.tessellateLoop(it) }
        val inside = loops.indices.map { i -> loops.indices.filter { j -> j != i && RegionBool.contains(listOf(rings[j]), rings[i].first()) } }
        val out = ArrayList<Region>()
        for (i in loops.indices) {
            if (inside[i].isNotEmpty()) continue
            out.add(Region(loops[i], loops.indices.filter { j -> inside[j] == listOf(i) }.map { loops[it] }))
        }
        return out
    }

    /** How far apart two of a section's own pieces may be (mm) and still be one boundary. */
    private const val CHAIN_TOL = 1e-6

    /** Why a face the plane crosses more than once names no single input — the section still draws both. */
    private const val CUT_TWICE =
        "the plane cuts that face into separate pieces, and one input is one curve — move the plane to where " +
            "that face is crossed once"

    /** The general case: cut every named face, cross every named edge. */
    private fun structuralSection(
        feature: Feature3,
        fs: List<FacePatch>,
        cut: Plane3,
    ): PlaneSection {
        val drawn = ArrayList<DrawnPiece>()
        val edges = ArrayList<SectionEdge>()
        for (patch in fs) {
            val (edge, extra) = cutFace(feature, patch, cut)
            edges.add(edge)
            edge.curve?.let { drawn.add(DrawnPiece(it, false)) }
            edge.sampled?.let { pts -> drawn.addAll(polylinePieces(pts).map { DrawnPiece(it, true) }) }
            drawn.addAll(extra)
        }
        val (es, whyEdges) = edges(feature)
        val corners =
            es?.map { e ->
                // an edge a dress-up feature **consumed** is not a corner of this body, whatever its old
                // carrier crosses: it keeps its index (a stored address may name it) and states its reason
                // instead of a point that is not there (session 71, slice 3)
                if (e.reason != null) {
                    SectionCorner(e.name.label, null, e.reason)
                } else {
                    val (at, why) = cutEdge(e.geom, cut)
                    SectionCorner(e.name.label, at, why)
                }
            } ?: emptyList()
        return PlaneSection(
            edges,
            corners,
            null,
            if (es == null) whyEdges else null,
            drawn,
            // What is drawn decides this, not only what is named: a face cut into several pieces refuses as
            // an *input* and keeps drawing, and when those pieces are chords the section is chords whether
            // or not one index could name them (OP-15 — the flag is about the picture's honesty).
            edges.any { it.approximated } || drawn.any { it.approximated },
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
    ): Pair<SectionEdge, List<DrawnPiece>> {
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
                        // a planar face's cut is exact whether or not one index can name it
                    ) to pieces.map { DrawnPiece(it, false) }
            }
        }
        // **A dressed part cuts as its base plus its bands** (session 71, slice 3). A face the blend only
        // trimmed is still the base's own surface, so the base's exact readings answer it verbatim — the
        // cylinder of a bored plate is the same cylinder after its rim is broken. A **band** is the blend's
        // own, and it is cut the way the blend was built: one section at a time along the edge.
        if (feature is Feature3.Blend) {
            val n = patch.name
            // **A band or a corner belongs to the level that made it**, and a chain of blends is a chain of
            // face lists: each keeps its base's and appends its own. So a face this level did not append is
            // asked of the level that did, exactly as a face the blend only *trimmed* is asked of the base —
            // one recursion, no special case, and a section through a plate whose rim was rounded two
            // gestures ago draws that rim's band rather than its refusal.
            if (n is FaceName.BlendCorner) {
                if (n.edges.none { it in feature.targets }) return cutFace(feature.base, patch, cut)
                // a **corner** patch is the one place the rolling ball stands still (session 80): a pivot is
                // a surface of revolution and gets [Revolve3]'s table, and a ball is cut in a circle by every
                // plane — that circle clipped to its own spherical triangle
                Blend3.cornerCut(feature, n, cut)?.let { return bandCutToEdge(label, it) }
                return SectionEdge(label, null, null, patch.reason ?: "the plane does not cut $label") to emptyList()
            }
            if (n !is FaceName.BlendBand) return cutFace(feature.base, patch, cut)
            // a band about a circular edge is a surface of revolution and gets [Revolve3]'s whole table;
            // a band along a straight one is a swept strip and gets the rulings, exact at every one of them —
            // except where the plane runs **parallel** to those rulings, which no ruling crosses and which
            // the band states exactly instead (`Blend3.parallelBandCut`, the cut a sectioned rounded plate
            // is actually asked for)
            Blend3.bandCut(feature, n.edge, cut)?.let { return bandCutToEdge(label, it) }
            Blend3.parallelBandCut(feature, n.edge, cut)?.let { pieces ->
                // the extras are what *no index names*, so a single piece is the edge's own curve and
                // nothing besides — [bandCutToEdge]'s convention, kept
                return if (pieces.size == 1) {
                    SectionEdge(label, pieces[0], null, null) to emptyList()
                } else {
                    SectionEdge(label, null, null, CUT_TWICE) to pieces.map { DrawnPiece(it, false) }
                }
            }
            val strip = Blend3.bandStrip(feature, n.edge)
            if (strip != null) return cutRuledStrip(label, strip, cut)
            return SectionEdge(label, null, null, patch.reason ?: "the plane does not cut $label") to emptyList()
        }
        // **A shelled part cuts as its base plus its cavity** (session 75), which is slice 3's sentence with
        // one word changed. An outer face is the base's own surface, so the base's exact readings answer it
        // verbatim; an **inner** face is the *cavity's* own surface, so it is cut by asking the cavity — the
        // same table, the same frame, one feature along — and the answer is restated in the shell's words.
        if (feature is Feature3.Shell) {
            val n = patch.name
            if (n !is FaceName.ShellInner) return cutFace(feature.base, patch, cut)
            val inner = Shell3.cavityFace(feature, n.face)
            if (inner != null) {
                val (cavity, cavPatch) = inner
                val (edge, extra) = cutFace(cavity, cavPatch, cut)
                return SectionEdge(label, edge.curve, edge.sampled, edge.reason?.replace(cavPatch.name.label, label)) to extra
            }
            return SectionEdge(label, null, null, patch.reason ?: "the plane does not cut $label") to emptyList()
        }
        // a revolution's bands have their own dispatch, decided by the plane's relation to the axis before
        // any geometry is made (OP-17's item 4 — see [Revolve3.cutBand] for the table)
        revolutionCut(feature, patch, cut)?.let { return it }
        // the exact case first, and it is the one a mechanical drawing lives on: a cylinder cut **perpendicular
        // to its axis** is that cylinder's own arc or circle, derived from the profile rather than fitted to
        // samples (OP-15 — exact means the numbers come from the parameters)
        perpendicularCylinderCut(feature, patch.name, cut)?.let { return SectionEdge(label, it, null, null) to emptyList() }
        // ...and, since the conics package (OP-24), the *inclined* cut of a cylinder is exact too: it is a
        // true ellipse, and the drawing now has a name for one
        inclinedCylinderCut(feature, patch.name, cut)?.let { return SectionEdge(label, it, null, null) to emptyList() }
        // ...and the cut **parallel to the axis** is exact too, and is the one an architectural drawing lives
        // on — a column beside a vertical working plane. The two readings above both decline it (the plane
        // crosses no ruling transversely: every ruling is parallel to it), and the sampled strip below cannot
        // see it for the same reason, so it has its own reading: the rulings standing where the plane's trace
        // in the sketch plane crosses the boundary piece — one upright per crossing, each derived from the
        // profile's own parameters, a line against an arc or a conic (OP-15)
        axisParallelSideCut(feature, patch.name, cut)?.let { return it }
        val strip = ruledStrip(feature, patch.name) ?: return SectionEdge(label, null, null, patch.reason ?: "the plane does not cut $label") to emptyList()
        return cutRuledStrip(label, strip, cut)
    }

    /**
     * A band of a **surface of revolution**, cut (OP-17's item 4): the exact curve where the family and the
     * plane's relation to the axis have one, and the band's own sampled runs where they do not.
     *
     * Null when this face is not a revolution's band at all, so every other reading below stands untouched.
     * The refusals are the planar face's verbatim — one input is one curve, and a face cut into several
     * pieces is drawn whole and named not at all — because that rule is about *indices*, not about which
     * surface the pieces came off.
     */
    private fun revolutionCut(
        feature: Feature3,
        patch: FacePatch,
        cut: Plane3,
    ): Pair<SectionEdge, List<DrawnPiece>>? {
        if (feature !is Feature3.Revolution) return null
        val name = patch.name
        if (name !is FaceName.Side) return null
        val label = name.label
        val band = Revolve3.cutBand(feature, name.piece, cut) ?: return null
        return bandCutToEdge(label, band)
    }

    /**
     * One [Revolve3.BandCut] read as a section edge — the exact curves where the family has them, the
     * sampled runs where it does not, and the one-input-is-one-curve refusal in both cases.
     *
     * Shared since session 71 slice 3 by a revolution's bands and by an **edge blend's** band about a
     * circular edge, which is the same table asked of the same frame ([Blend3.bandCut]).
     */
    private fun bandCutToEdge(
        label: String,
        band: Revolve3.BandCut,
    ): Pair<SectionEdge, List<DrawnPiece>> {
        val exact = band.exact
        if (exact != null) {
            return when (exact.size) {
                0 -> SectionEdge(label, null, null, "the plane does not cut $label") to emptyList()
                1 -> SectionEdge(label, exact[0], null, null) to emptyList()
                else ->
                    SectionEdge(
                        label,
                        null,
                        null,
                        "the plane cuts $label into ${exact.size} separate pieces, and one input is one curve — " +
                            "move the plane to where that face is crossed once",
                    ) to exact.map { DrawnPiece(it, false) }
            }
        }
        val runs = band.runs.orEmpty().filter { it.size >= 2 }
        return when (runs.size) {
            0 -> SectionEdge(label, null, null, "the plane does not cut $label") to emptyList()
            1 -> SectionEdge(label, null, runs[0], null) to emptyList()
            else ->
                SectionEdge(
                    label,
                    null,
                    null,
                    "the plane cuts $label into ${runs.size} separate pieces, and one input is one curve — " +
                        "move the plane to where that face is crossed once",
                ) to runs.flatMap { r -> polylinePieces(r).map { DrawnPiece(it, true) } }
        }
    }

    /**
     * A curved side face of an extrusion, cut by a plane **parallel to the extrusion's axis**: one upright
     * segment per crossing of the cutting plane's trace with the boundary piece, exact (OP-15 — the crossings
     * are the same analytic ones a planar face's cut uses). Null where the plane is not axis-parallel or the
     * piece is not a curved side, so the caller's other readings stand untouched.
     *
     * More than one crossing is the planar-face rule verbatim: one input is one curve, so the edge refuses by
     * name while every upright is still **drawn** — a vertical plane through a column's middle shows both of
     * its sides (the report that drove this), and an input wants the plane moved to where the face is crossed
     * once.
     */
    private fun axisParallelSideCut(
        feature: Feature3,
        name: FaceName,
        cut: Plane3,
    ): Pair<SectionEdge, List<DrawnPiece>>? {
        if (feature !is Feature3.Extrusion || name !is FaceName.Side) return null
        val e = Geom3.boundaryPieces(feature).getOrNull(name.piece) ?: return null
        // a straight piece sweeps a *planar* side face, which [cutPlanarFace] already answers exactly
        if (e is ProfileElement.Seg) return null
        val p = feature.sketch.plane
        if (abs(p.normal.normalized().dot(cut.normal.normalized())) >= 1e-9) return null
        val line = cutLineIn(p, cut) ?: return null
        val label = name.label
        val axis = p.normal.normalized() * feature.depth
        val uprights =
            crossingsOf(e, line).map { q ->
                val w = p.toWorld(q)
                ProfileElement.Seg(Segment(cut.toLocal(w), cut.toLocal(w + axis)))
            }
        return when (uprights.size) {
            0 -> SectionEdge(label, null, null, "the plane does not cut $label") to emptyList()
            1 -> SectionEdge(label, uprights[0], null, null) to emptyList()
            else ->
                SectionEdge(
                    label,
                    null,
                    null,
                    "the plane cuts $label into ${uprights.size} separate pieces, and one input is one curve — " +
                        "move the plane to where that face is crossed once",
                ) to uprights.map { DrawnPiece(it, false) }
        }
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
            // numeric but deterministic, from the curve's own fixed grid — the seeding is the tessellation
            // and nothing about it depends on the camera or on a click (OP-15's spline rule)
            is ProfileElement.FuncE -> FuncCurves.intersectImplicit(e.curve, FuncCurves.lineImplicit(line)).points
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
    internal class RuledStrip(val closed: Boolean, val at: (Double) -> Pair<Vec3, Vec3>, val steps: Int)

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
    ): Pair<SectionEdge, List<DrawnPiece>> {
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
        return runsToEdge(label, runs)
    }

    /**
     * The sampled runs of one face's cut, read as a section edge — the one place *"exact at every station,
     * chords between"* (OP-15) becomes an input or a refusal.
     *
     * Shared by a ruled strip's cut and, since session 71 slice 3, by a **blend band's**: both produce runs
     * of exact points and neither may answer the *"one input is one curve"* question differently.
     */
    private fun runsToEdge(
        label: String,
        runs: List<List<Vec2>>,
    ): Pair<SectionEdge, List<DrawnPiece>> {
        if (runs.isEmpty()) return SectionEdge(label, null, null, "the plane does not cut $label") to emptyList()
        if (runs.size > 1) {
            return SectionEdge(
                label,
                null,
                null,
                "the plane cuts $label into ${runs.size} separate pieces, and one input is one curve — " +
                    "move the plane to where that face is crossed once",
                // a ruled face's cut is chords between exact rulings, named or not (OP-15)
            ) to runs.flatMap { r -> polylinePieces(r).map { DrawnPiece(it, true) } }
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
                    // A **degenerate** edge is one point, and the point is the honest answer when the plane
                    // reaches it: that is what a revolution's pole is — the ring a corner on the axis traces,
                    // which has no length but is a corner of the section all the same (OP-17's item 4).
                    (geom.b - geom.a).length() <= Geom3.WELD_TOL ->
                        if (abs(d0) <= ON_PLANE_TOL) cut.toLocal(geom.a) to null else null to "the plane does not cross that edge"
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
        val out = ArrayList<DrawnPiece>()
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
            // a triangle's crossing is a chord of the tessellation and nothing more (OP-9's sink rule)
            if (uniq.size == 2) out.add(DrawnPiece(ProfileElement.Seg(Segment(uniq[0], uniq[1])), true))
        }
        return PlaneSection(emptyList(), emptyList(), null, reason, out, true)
    }
}
