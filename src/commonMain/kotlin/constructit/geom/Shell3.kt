package constructit.geom

import kotlin.math.abs

/**
 * **The shell: a body hollowed to a stated wall thickness** (session 75 — the constant-offset tier the blend
 * built, read on a whole profile instead of on one face).
 *
 * The whole construction, in the order it runs:
 *
 * 1. The **cavity is the base offset inward by `t`**, and in this tier that is exact (OP-15): every boundary
 *    piece steps onto its own offset carrier — a line onto a line, an arc onto an arc — and every corner is
 *    re-solved as an ordinary intersection ([GeomMath.offsetCycle], the very arithmetic the blend's outline
 *    correction runs on). An **extrusion**'s cavity is that offset profile extruded from `t` above the bottom
 *    cap to `t` below the top; a **revolution**'s is the offset meridian revolved through the same turn.
 * 2. A face the shell leaves **open** is *not* offset: its own piece keeps its carrier (or, for a cap, the
 *    cavity's span reaches that cap), so the cavity comes out through it and takes that face away. This is why
 *    open-vs-closed needs no second construction — a closed shell is the same sentence with no open face.
 * 3. The body is **base minus cavity**, through the one boolean dispatch ([Geom3.combine]): the *exact* slab
 *    algebra for an extrusion (OP-22 — both operands are prisms on one axis, so the plate's figures are exact),
 *    the general engine for a revolution. The result is restated under [Feature3.Shell] ([Solid3.restated]), so
 *    **the feature answers faces analytically while the triangles come off the boolean** — slice 3's bargain,
 *    and it costs nothing because the mesh is a sink (OP-9).
 *
 * **What is in the tier and what refuses by name.** An extrusion and a **complete** revolution are in. A
 * partial revolve is not, and the reason is a wall thickness rather than a missing case: inset from a radial
 * cap is an *angular* inset, whose wall grows thicker with the radius, so it would not be the thickness
 * anybody typed. A prism (the exact boolean's own result), a general boolean's mesh, an import, a sweep, a
 * loft, a blended body and a shell of a shell all refuse in their own words, each naming the route that does
 * work — and several of them are constructive: shell the operands *before* fusing them, round the part *after*
 * hollowing it, sweep a hollow section.
 *
 * **The thickness is an ordinary parameter** — shareable, expression-bindable — and a thickness the body
 * cannot host refuses with **the thickest that fits**, found by halving exactly as a blend's radius is
 * ([largestFitting]).
 */
object Shell3 {
    /** How many halvings the *thickest wall that fits* is reported to (a refusal that heals, OP-3). */
    private const val FIT_STEPS = 24

    /** How far off the base's own boundary (mm) still counts as on it — see [insideBase]. */
    private const val ON_BOUNDARY_TOL = 1e-6

    /** How far two planes may differ and still be the same plane, in mm and as a direction cosine. */
    private const val SAME_PLANE_TOL = 1e-7

    // ---- the gates: which bodies have a shell at all, and which faces can be opened ----

    /**
     * Why [feature] cannot be hollowed **at all**, or null when it can — the kind gate, asked before any
     * geometry is made so that a refusal is about the body rather than about a number (OP-3, OP-21).
     */
    fun shellable(feature: Feature3): String? =
        when (feature) {
            is Feature3.Extrusion ->
                if (feature.depth <= Geom3.WELD_TOL) "this solid has no depth, so there is no wall to leave behind" else null
            is Feature3.Revolution -> {
                // **Closed the whole way round**, asked of the emitter's own reading rather than of the kind:
                // [Turn3.Full] and a *stated* interval that measures a full turn are the same closed body with
                // the same capless face list (see [Geom3.revolve]), and a shell may not tell them apart when
                // the geometry does not.
                val frame = Revolve3.frameOf(feature)
                when {
                    frame == null -> "the axis of revolution has no direction"
                    frame.full -> null
                    else ->
                        "this solid is revolved less than the whole way round, so its cavity would have to be held " +
                            "off the two radial caps by an *angle* rather than by a distance — and that wall would " +
                            "grow thicker with the radius, which is not the thickness you typed. Revolve the whole " +
                            "way round to shell it; a shell of a partial revolve is a future extension"
                }
            }
            is Feature3.Prism ->
                "this solid is a stack of slabs from the exact boolean algebra (OP-22), so it has no one profile to " +
                    "offset inward — shell the body first and combine it afterwards, or hollow it with a Cut; the " +
                    "slab reading of a shell is a future extension"
            is Feature3.MeshBoolean ->
                "this solid is mesh-only (a general boolean's result, OP-9), so it has no profile to offset inward — " +
                    "hollowing it would be a mesh offset, which this drawing does not do. Shell the operands before " +
                    "fusing them, and the fused part is hollow by construction"
            is Feature3.Imported ->
                "this body was imported from a file, so it is triangles and nothing else (OP-9) — hollowing one " +
                    "would be a mesh offset, which this drawing does not do; build the wall you want beside it"
            is Feature3.Sweep ->
                "this solid is a profile swept along a curve (OP-26), so its wall would be its section offset along " +
                    "the whole run — sweep a **hollow section** instead, which is the same body by construction; a " +
                    "shell of a sweep is a future extension"
            is Feature3.Loft ->
                "this solid's cross-section changes along the run (a loft), so one wall thickness is not a constant " +
                    "offset of its sections — a shell of a loft is a future extension"
            is Feature3.Skin ->
                "this solid is a skin over drawn sections, so its outline changes along the run and one wall " +
                    "thickness is not a constant offset of its sections — loft the outer sections, loft the inner " +
                    "ones and subtract; a shell of a skin is a future extension"
            is Feature3.Blend ->
                "this solid is a blended body, so the surface to offset inward is the rounding's own — shell the body " +
                    "first and round it afterwards, which is the same part by construction"
            is Feature3.Shell ->
                "this solid is already a shell, so there is no second cavity to make — retype its wall thickness, " +
                    "which is an ordinary parameter, or open one of its faces"
        }

    /**
     * Why face [face] of [feature] cannot be the **opening**, or null when it can — the address gate.
     *
     * A face that sweeps no surface at all (a profile piece lying on the axis of revolution) has nothing to
     * open, and an index past the face list is no face of this body. Both are structural, so both are decided
     * here rather than discovered in the middle of an offset.
     */
    fun openFaceRefusal(
        feature: Feature3,
        face: Int,
    ): String? {
        val (faces, why) = Section3.faces(feature)
        if (faces == null) return why
        if (face < 0 || face >= faces.size) return "this solid has no face #${face + 1} (it has ${faces.size})"
        if (sweepsNothing(feature, face)) {
            return "${faces[face].name.label} sweeps no surface at all (its profile edge lies on the axis of " +
                "revolution), so there is nothing to open there"
        }
        return null
    }

    /** Whether face [face] is a piece that sweeps no surface — a revolve profile edge on the axis. */
    private fun sweepsNothing(
        feature: Feature3,
        face: Int,
    ): Boolean {
        if (feature !is Feature3.Revolution) return false
        val frame = Revolve3.frameOf(feature) ?: return false
        val piece = Geom3.boundaryPieces(feature).getOrNull(face) ?: return false
        return Revolve3.bandOf(frame, piece) is Revolve3.Band.Degenerate
    }

    // ---- the cavity ----

    /**
     * The **cavity** of [feature] at wall thickness [thickness], with the faces [openFaces] names left open —
     * a feature of the same kind as the base, since an offset extrusion is an extrusion and an offset
     * revolution is a revolution.
     *
     * Assumes the two gates above have passed; what it can still refuse is geometric and heals (OP-3): a
     * profile piece consumed by the offset, two carriers that no longer meet, caps that would meet in the
     * middle, an offset that leaves the body. The caller names *the thickest wall that fits* on top of it.
     */
    fun cavityOf(
        feature: Feature3,
        thickness: Double,
        openFaces: List<Int>,
    ): Pair<Feature3?, String?> {
        if (thickness <= Geom3.WELD_TOL) {
            return null to "a shell needs a positive wall thickness — this one is ${Frames3.mm(thickness)} mm"
        }
        return when (feature) {
            is Feature3.Extrusion -> extrusionCavity(feature, thickness, openFaces)
            is Feature3.Revolution -> revolutionCavity(feature, thickness, openFaces)
            else -> null to (shellable(feature) ?: "this solid has no cavity to make")
        }
    }

    private fun extrusionCavity(
        f: Feature3.Extrusion,
        t: Double,
        open: List<Int>,
    ): Pair<Feature3?, String?> {
        val n = Geom3.boundaryPieces(f).size
        val z0 = if (open.contains(n)) 0.0 else t
        val z1 = f.depth - (if (open.contains(n + 1)) 0.0 else t)
        if (z1 - z0 <= Geom3.WELD_TOL) {
            return null to
                "a wall of ${Frames3.mm(t)} mm leaves no cavity between the two caps of a body " +
                "${Frames3.mm(f.depth)} mm deep"
        }
        val (regions, why) = erodedRegions(f, f.sketch.regions, t, open)
        if (regions == null) return null to why
        return Feature3.Extrusion(Sketch3(f.sketch.plane.translated(z0), regions), z1 - z0) to null
    }

    private fun revolutionCavity(
        f: Feature3.Revolution,
        t: Double,
        open: List<Int>,
    ): Pair<Feature3?, String?> {
        val (regions, why) = erodedRegions(f, f.sketch.regions, t, open)
        if (regions == null) return null to why
        return Feature3.Revolution(Sketch3(f.sketch.plane, regions), f.axisOrigin, f.axisDir, f.turn) to null
    }

    /**
     * [regions] with every boundary piece stepped inward by [t] — except the pieces whose face is **open**,
     * and the pieces that sweep no surface at all, which keep their own carriers.
     *
     * The piece indices are [Geom3.boundaryPieces]'s own order, which is exactly the face order for both
     * kinds in this tier, so *"face #3 is open"* is *"piece 3 keeps its carrier"* with no mapping table in
     * between. Inward is the piece's **left** on a normalised loop (OP-14), so one sign erodes an outer
     * boundary and grows a hole — which is what a wall thickness means either way.
     */
    private fun erodedRegions(
        feature: Feature3,
        regions: List<Region>,
        t: Double,
        open: List<Int>,
    ): Pair<List<Region>?, String?> {
        val out = ArrayList<Region>(regions.size)
        var index = 0
        for (region in regions) {
            val loops = ArrayList<Loop>(1 + region.holes.size)
            for (loop in listOf(region.outer) + region.holes) {
                val first = index
                index += loop.elements.size
                val offsets =
                    loop.elements.mapIndexed { k, e ->
                        if (open.contains(first + k) || sweepsNothing(feature, first + k)) 0.0 else t
                    }
                val (pieces, code) = GeomMath.offsetCycle(loop.elements, offsets)
                if (pieces == null) return null to offsetRefusal(code, t)
                val moved = Loop(pieces)
                if (!keptItsHandedness(loop, moved)) {
                    return null to
                        "a wall of ${Frames3.mm(t)} mm turns one of this profile's rings inside out, so there is no " +
                        "cavity of that thickness in it"
                }
                loops.add(moved)
            }
            val eroded = Region(loops[0], loops.drop(1))
            if (!insideBase(region, eroded)) {
                return null to
                    "a wall of ${Frames3.mm(t)} mm pushes the cavity's own boundary outside this profile, so the " +
                    "wall would not close"
            }
            out.add(eroded)
        }
        return out to null
    }

    /** The offset's own codes, in the shell's words (session 65: a refusal speaks of what the user made). */
    private fun offsetRefusal(
        code: String?,
        t: Double,
    ): String =
        when (code) {
            GeomMath.OFFSET_NOT_A_CARRIER ->
                "one piece of this profile is neither a straight run nor an arc, so its offset is not a curve this " +
                    "drawing can state exactly — a shell of a spline, an ellipse or a function curve is a future extension"
            GeomMath.OFFSET_NO_JUNCTION ->
                "the cavity's own boundary does not close at a wall of ${Frames3.mm(t)} mm — two of its offset " +
                    "pieces no longer meet"
            else -> "a wall of ${Frames3.mm(t)} mm consumes one piece of this profile, so there is no cavity of that thickness in it"
        }

    /** Whether the offset ring still runs the way it did — a ring turned inside out has no cavity in it. */
    private fun keptItsHandedness(
        was: Loop,
        now: Loop,
    ): Boolean {
        val a = GeomMath.signedArea(was)
        val b = GeomMath.signedArea(now)
        if ((a >= 0.0) != (b >= 0.0)) return false
        // an outer ring may only shrink and a hole may only grow — the same statement twice, since the sign is
        // what says which of the two a ring is (OP-14). Not strict: a ring every one of whose faces is open
        // keeps its area, and that is not an inversion.
        return if (a >= 0.0) abs(b) <= abs(a) else abs(b) >= abs(a)
    }

    /**
     * Whether the [eroded] region really lies **within** [base] — the one guard against an offset that folds
     * over itself somewhere no single piece was consumed.
     *
     * A piece kept for an *open* face lies exactly **on** the base's boundary, which is why being on it counts
     * ([ON_BOUNDARY_TOL]). Stated rather than silent: this is a sampled test on the offset boundary's own
     * polyline, so an offset that leaves the profile and comes back within one chord is not caught here — what
     * catches those is the piece-consumption test above and, past both, the boolean's own refusal.
     */
    private fun insideBase(
        base: Region,
        eroded: Region,
    ): Boolean {
        val rings = Project3.ringsOf(base.outer.elements + base.holes.flatMap { it.elements })
        if (rings.isEmpty()) return true
        for (loop in listOf(eroded.outer) + eroded.holes) {
            for (e in loop.elements) {
                for (p in GeomMath.tessellatePiece(e)) {
                    if (RegionBool.contains(rings, p)) continue
                    if (rings.minOf { ringDistance(it, p) } <= ON_BOUNDARY_TOL) continue
                    return false
                }
            }
        }
        return true
    }

    /** The distance from [p] to the closed polyline [ring]. */
    private fun ringDistance(
        ring: List<Vec2>,
        p: Vec2,
    ): Double {
        var best = Double.MAX_VALUE
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            val ab = b - a
            val s = if (ab.length() <= Vec2.EPS) 0.0 else ((p - a).dot(ab) / ab.dot(ab)).coerceIn(0.0, 1.0)
            val d = (p - (a + ab * s)).length()
            if (d < best) best = d
        }
        return best
    }

    /** The **thickest wall that fits**, by halving — what a refusal names so it can be acted on (OP-3). */
    fun largestFitting(
        feature: Feature3,
        thickness: Double,
        openFaces: List<Int>,
    ): Double {
        var lo = 0.0
        var hi = thickness
        repeat(FIT_STEPS) {
            val mid = (lo + hi) / 2.0
            if (cavityOf(feature, mid, openFaces).first != null) lo = mid else hi = mid
        }
        return lo
    }

    // ---- the whole construction ----

    /**
     * [base] hollowed to a wall of [thickness] with the faces [openFaces] names left open — the mesh half:
     * the cavity built as an ordinary body of its own kind and taken out by the one boolean dispatch.
     *
     * The caller restates the result under [Feature3.Shell] ([Solid3.restated]), which is what makes the
     * triangles the boolean's and the *names* the feature's own — see the note on this object.
     */
    fun shelled(
        base: Solid3,
        thickness: Double,
        openFaces: List<Int>,
    ): Pair<Solid3?, String?> {
        shellable(base.feature)?.let { return null to it }
        for (i in openFaces) openFaceRefusal(base.feature, i)?.let { return null to it }
        val (cavity, why) = cavityOf(base.feature, thickness, openFaces)
        if (cavity == null) {
            val fits = largestFitting(base.feature, thickness, openFaces)
            val reason = why ?: "this body has no cavity of that thickness"
            return null to
                if (fits > Geom3.WELD_TOL) "$reason — the thickest wall that fits is about ${Frames3.mm(fits)} mm" else reason
        }
        val (tool, whyTool) = solidOf(cavity)
        if (tool == null) return null to (whyTool ?: "the cavity of this body cannot be built")
        val (out, whyBool) = Geom3.combine(BoolOp.SUBTRACT, base, tool)
        if (out == null) return null to (whyBool ?: "the cavity cannot be taken out of this body")
        return out to null
    }

    /** The cavity as a body — its own kind's emitter, since an offset extrusion is an extrusion. */
    private fun solidOf(cavity: Feature3): Pair<Solid3?, String?> =
        when (cavity) {
            is Feature3.Extrusion -> Geom3.extrude(cavity.sketch, cavity.depth)
            is Feature3.Revolution -> Geom3.revolve(cavity.sketch, cavity.axisOrigin, cavity.axisDir, cavity.turn)
            else -> null to "the cavity of this body is not a shape this drawing can build"
        }

    // ---- the shelled face and edge lists: the base's, extended ----

    /**
     * **The shelled face list**: the base's faces at their **own indices** — an open one carrying the cavity's
     * boundary as a hole, which is the *rim* it actually is — followed by the **inner twin** of every one of
     * them, in the same order (session 75).
     *
     * Three rules, and all three are OP-21's index stability read on a face list:
     *
     * - *Nothing renumbers and nothing drops out.* Face #3 of the base is face #3 of the shelled part,
     *   because every index in this list is an address a step may already hold (OP-18). A shell moves no
     *   face at all, so the outer faces are the base's own patches, unchanged, verbatim.
     * - *The open face keeps its index and becomes a ring.* The alternative — dropping it, or keeping it with
     *   a reason — was rejected on what the body actually has there: after opening the top of a plate the
     *   material at the top is the **wall's own rim**, a perfectly good planar face with a hole in it, and it
     *   is exactly the base's outline plus the cavity's coincident boundary reversed. So it is stated, and a
     *   sketch on the rim of a cup works. Where the rim cannot be stated exactly — an open face that is not a
     *   plane, or whose cavity twin is not — the index stays and the reason says so.
     * - *The inner faces append at a stated offset.* The inner twin of face `i` is entry `faces(base).size + i`
     *   ([FaceName.ShellInner]), which makes outer→inner arithmetic rather than a search, and puts every one of
     *   them in the address space's *ends* range, where a sketch reaches it with no new machinery
     *   ([Section3.FACE_ADDRESS_CONVENTION]) — a pocket floor is a face you can draw on.
     *
     * The inner patch is the cavity's own face **turned inside out**: the same surface, the plane flipped so
     * its normal points out of the *wall* (into the cavity) as OP-17's convention requires, and the outline
     * mapped into that flipped frame so it still says the same curve.
     */
    fun faces(f: Feature3.Shell): Pair<List<FacePatch>?, String?> {
        val (baseFaces, whyBase) = Section3.faces(f.base)
        if (baseFaces == null) return null to whyBase
        val (cavity, whyCavity) = cavityOf(f.base, f.thickness, f.openFaces)
        if (cavity == null) return null to whyCavity
        val (cavFaces, whyCav) = Section3.faces(cavity)
        if (cavFaces == null) return null to whyCav
        if (cavFaces.size != baseFaces.size) {
            return null to
                "this body's cavity has ${cavFaces.size} faces where the body itself has ${baseFaces.size}, so the " +
                "inner faces cannot be paired with the outer ones"
        }
        val out = ArrayList<FacePatch>(2 * baseFaces.size)
        for ((i, patch) in baseFaces.withIndex()) {
            if (!f.openFaces.contains(i)) {
                out.add(patch)
                continue
            }
            val (rim, whyRim) = rimOutline(patch, cavFaces[i])
            out.add(if (rim == null) patch.copy(reason = patch.reason ?: whyRim) else patch.copy(outline = rim))
        }
        for ((i, cav) in cavFaces.withIndex()) out.add(innerPatch(i, cav, f.openFaces.contains(i)))
        return out to null
    }

    /**
     * The **rim**: the open face's own boundary with the cavity's coincident boundary added as a hole.
     *
     * Both sides are exact and both come from the same parameters — the face's outline is the base's, the hole
     * is the offset profile — so this is a restatement and not a discovery (OP-8): the two are compared as
     * *planes*, because a face patch's frame and its cavity twin's are built by two different translations of
     * one plane and therefore agree geometrically rather than bit for bit.
     */
    private fun rimOutline(
        patch: FacePatch,
        cav: FacePatch,
    ): Pair<List<ProfileElement>?, String?> {
        val plane =
            patch.plane
                ?: return null to
                    "${patch.name.label} is not a plane, so the rim left when the wall opens through it is not a " +
                    "boundary this drawing states — the face is open all the same (a future extension)"
        val cavPlane =
            cav.plane
                ?: return null to
                    "the cavity's own face behind ${patch.name.label} is not a plane, so the rim left when the wall " +
                    "opens through it is not a boundary this drawing states (a future extension)"
        if (!samePlane(plane, cavPlane)) {
            return null to
                "the cavity does not reach ${patch.name.label} in its own plane, so the rim there cannot be stated exactly"
        }
        val map = mapBetween(cavPlane, plane)
        val holes =
            loopsOfOutline(cav.outline).flatMap { ring ->
                GeomMath.reverseLoop(Loop(ring.map { GeomMath.transform(it, map) })).elements
            }
        return (patch.outline + holes) to null
    }

    /** One inner face: the cavity's own, turned inside out — or the reason this body has none there. */
    private fun innerPatch(
        face: Int,
        cav: FacePatch,
        open: Boolean,
    ): FacePatch {
        val name = FaceName.ShellInner(face)
        if (open) {
            return FacePatch(
                name,
                null,
                emptyList(),
                "face #${face + 1} is open, so there is no wall behind it — what this body has there is that face's " +
                    "own rim, which keeps its index",
            )
        }
        val plane = cav.plane
        if (plane == null) {
            return FacePatch(
                name,
                null,
                emptyList(),
                "${name.label} is ${cav.surface?.band?.label ?: "a surface this drawing has no name for"} and not a " +
                    "plane, so there is nothing to sketch on there; put a datum plane where you want to sketch",
                cav.surface,
            )
        }
        val flipped = plane.flipped()
        val map = mapBetween(plane, flipped)
        return FacePatch(name, flipped, cav.outline.map { GeomMath.transform(it, map) }, null, cav.surface)
    }

    /**
     * **The shelled edge list**: every base edge at its own index, then the cavity's own edges appended in
     * the same order ([EdgeName.ShellInner]).
     *
     * A shell **consumes no edge** — that is the difference from the blend, and it is a fact about the
     * geometry rather than a choice: hollowing takes material from behind the faces and leaves every outer
     * crease exactly where it was, an open face's own boundary included. So nothing here carries a *rounded
     * away* reason, and what the appended edges buy is the same thing the blend's rails buy: the inner creases
     * are first-class edges a later feature can be addressed by.
     *
     * The adjacency is restated one face along: an inner edge runs between two inner faces — except where one
     * of them is the twin of an **open** face, where the face this body actually has is that face's **rim**, so
     * the pair names the base face. That is what makes the rim's own inner boundary an edge of the body.
     */
    fun edges(f: Feature3.Shell): Pair<List<SolidEdge>?, String?> {
        val (baseEdges, whyBase) = Section3.edges(f.base)
        if (baseEdges == null) return null to whyBase
        val (cavity, whyCavity) = cavityOf(f.base, f.thickness, f.openFaces)
        if (cavity == null) return null to whyCavity
        val (cavEdges, whyCav) = Section3.edges(cavity)
        if (cavEdges == null) return null to whyCav
        val (cavFaces, whyFaces) = Section3.faces(cavity)
        if (cavFaces == null) return null to whyFaces
        val (baseFaces, whyBaseFaces) = Section3.faces(f.base)
        if (baseFaces == null) return null to whyBaseFaces

        fun inner(name: FaceName): FaceName {
            val j = cavFaces.indexOfFirst { it.name == name }
            if (j < 0) return name
            return if (f.openFaces.contains(j)) baseFaces[j].name else FaceName.ShellInner(j)
        }
        val out = ArrayList<SolidEdge>(baseEdges.size + cavEdges.size)
        out.addAll(baseEdges)
        for ((i, e) in cavEdges.withIndex()) {
            out.add(SolidEdge(EdgeName.ShellInner(i), e.geom, FacePair(inner(e.between.a), inner(e.between.b)), e.reason))
        }
        return out to null
    }

    /**
     * The cavity and its own patch behind face [face] — what a **section** of an inner face is cut from
     * ([Section3]'s own dispatch, one feature along), or null when this body has no wall there.
     */
    fun cavityFace(
        f: Feature3.Shell,
        face: Int,
    ): Pair<Feature3, FacePatch>? {
        if (f.openFaces.contains(face)) return null
        val cavity = cavityOf(f.base, f.thickness, f.openFaces).first ?: return null
        val patch = Section3.faces(cavity).first?.getOrNull(face) ?: return null
        return cavity to patch
    }

    // ---- the two small geometric helpers ----

    /** Whether two frames describe the **same plane** — parallel normals, each origin lying in the other. */
    private fun samePlane(
        a: Plane3,
        b: Plane3,
    ): Boolean {
        val na = a.normal.normalized()
        val nb = b.normal.normalized()
        if (abs(abs(na.dot(nb)) - 1.0) > SAME_PLANE_TOL) return false
        return abs(b.distanceTo(a.origin)) <= SAME_PLANE_TOL
    }

    /**
     * The affine map from [from]'s own (u, v) into [to]'s, for two frames of **one plane** — derived from the
     * images of the origin and the two unit vectors, so a flip, a turn and a shift are all one sentence and
     * none of them is a case.
     */
    private fun mapBetween(
        from: Plane3,
        to: Plane3,
    ): Affine {
        val o = to.toLocal(from.toWorld(Vec2(0.0, 0.0)))
        val x = to.toLocal(from.toWorld(Vec2(1.0, 0.0))) - o
        val y = to.toLocal(from.toWorld(Vec2(0.0, 1.0))) - o
        return Affine(x.x, x.y, y.x, y.y, o.x, o.y)
    }

    /** The contiguous rings of a face outline, as piece lists — the wrap staying inside its own ring. */
    private fun loopsOfOutline(outline: List<ProfileElement>): List<List<ProfileElement>> {
        val out = ArrayList<List<ProfileElement>>()
        var cur = ArrayList<ProfileElement>()
        for (e in outline) {
            if (cur.isNotEmpty() && (GeomMath.startOf(e) - GeomMath.endOf(cur.last())).length() > GeomMath.JOIN_TOL) {
                out.add(cur)
                cur = ArrayList()
            }
            cur.add(e)
            if ((GeomMath.endOf(e) - GeomMath.startOf(cur.first())).length() <= GeomMath.JOIN_TOL) {
                out.add(cur)
                cur = ArrayList()
            }
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }
}
