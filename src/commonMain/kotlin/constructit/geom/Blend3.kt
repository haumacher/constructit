package constructit.geom

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Which blend a corner gets — the 2D pair, one dimension up.
 *
 * **Structural, never a value** (OP-1's rule, the same one [Handedness] and [CarryMode] follow): which of the
 * two a blend is, is which tool row was used, so it is recorded in the step and can never change by itself.
 */
enum class BlendKind {
    FILLET,
    CHAMFER,

    /**
     * **The general tier** (GitHub #30, session 80): the section is a *drawn* curve rather than a solved one
     * — any open chain whose two ends land on the two faces, read in the corner's own frame, where its two
     * coordinates **are** the setbacks along the two faces.
     *
     * The two built-ins are this one's own fixtures at a right dihedral: a segment from `(c, 0)` to `(0, c)`
     * is [CHAMFER] vertex for vertex, and the quarter-arc centred at `(r, r)` is [FILLET]. Away from a right
     * angle the frame is **oblique**, which is the whole point — the numbers stay setbacks, so a skewed
     * corner is cut to the length a rasp reaches (see DESIGN.md, *Custom blend profiles*).
     */
    PROFILE,
    ;

    /** The word a refusal and a status line use. */
    val word: String get() =
        when (this) {
            FILLET -> "fillet"
            CHAMFER -> "chamfer"
            PROFILE -> "profile blend"
        }

    /** What the scalar this kind takes is called — the drawn profile itself, for the general tier. */
    val sizeWord: String get() =
        when (this) {
            FILLET -> "radius"
            CHAMFER -> "setback"
            PROFILE -> "profile"
        }
}

/**
 * The **recorded discrete choices of one blended edge** (OP-1/OP-18): which of the four sectors round the
 * crease the blend fills, which intersection branch its centre is, and whether that sector is *material*.
 *
 * Scored exactly once — when the tool is used — and thereafter taken verbatim from the step's `signs=`. The
 * fillet's own lesson, one dimension up: everything here is scored against geometry that **moves**, so a
 * reload that scored again would be a reload that re-decided (*"fillets inverted, producing sharp corners"*).
 *
 * [a] and [b] hold **what each construction consumes**, not the sector they were scored from, so that nothing
 * at all is derived from live geometry on replay — the same reason `Document.storedLegSigns` exists one
 * dimension down. For a **chamfer**, and for a fillet with **two straight legs**, they are the quadrant:
 * `+1` along each leg's own carrier direction, `-1` against it, exactly what [FilletMath.lineLineArc] and
 * [FilletMath.setback] take, and [branch] is unused. With **a round leg** a *fillet* stores
 * [FilletVariant]'s two offset sides instead, and [branch] is its intersection branch. [convex] is `true` when the
 * sector the blend fills is the **material** one — the blend is then subtracted — and `false` when it is the
 * void, where it is added.
 */
data class BlendChoice(val a: Int, val b: Int, val branch: Int, val convex: Boolean, val flip: Int = 1) {
    /** This choice as the four integers a built-in row's step restates. */
    fun signs(): List<Int> = listOf(a, b, branch, if (convex) 1 else -1)

    /**
     * …and as the **five** a [BlendKind.PROFILE] row's step restates, the fifth being [flip] — which end of
     * the drawn profile is the setback on which face.
     *
     * Five rather than four is unambiguous because the chunk size is a property of the **tool id**, which the
     * step already carries: no `filletedge` or `chamferedge` step is re-read differently and no new file can
     * be mistaken for an old one, so no version bump is owed (OP-18).
     */
    fun signsWithFlip(): List<Int> = signs() + flip

    companion object {
        /** The choice four (or five) restated integers name, or null when there are not four of them. */
        fun of(signs: List<Int>): BlendChoice? =
            if (signs.size < 4) null else BlendChoice(signs[0], signs[1], signs[2], signs[3] >= 0, signs.getOrElse(4) { 1 })
    }
}

/**
 * **What a blend's section is**: which kind, the size the two built-ins take, and — for
 * [BlendKind.PROFILE] — the drawn chain, in its own (setback, setback) coordinates.
 *
 * One object rather than a pair of arguments threaded through a dozen signatures, and it is what makes the
 * general tier an ordinary third case instead of a second construction: everything from [wedgeOf] down asks
 * this for *the curve between the two tangencies* and knows nothing else about which row was used.
 */
data class BlendSection(val kind: BlendKind, val size: Double, val profile: List<ProfileElement> = emptyList()) {
    /**
     * How far this section reaches from the crease, in mm — the size for the two built-ins, and the drawn
     * profile's own largest coordinate for the general tier.
     *
     * Used only where a *scale* is wanted (how far off the crease to probe for material, how big a step
     * along a leg to score a direction with), never as a size the user typed.
     */
    fun reach(): Double =
        if (kind != BlendKind.PROFILE) {
            size
        } else {
            profile.flatMap { GeomMath.tessellatePiece(it, GeomMath.TESS_TOL_MM) }.maxOfOrNull { it.length() } ?: 0.0
        }

    /** This section at [k] times its stated size — the built-ins scale their number, a profile scales itself. */
    fun scaledBy(k: Double): BlendSection =
        if (kind != BlendKind.PROFILE) {
            copy(size = size * k)
        } else {
            copy(profile = profile.map { GeomMath.transform(it, Affine.scaling(Vec2(0.0, 0.0), k)) })
        }

    /** How a refusal names the size that would fit — a number of millimetres, or a fraction of the drawing. */
    fun fitPhrase(k: Double): String =
        if (kind != BlendKind.PROFILE) {
            "about ${Frames3.mm(size * k)} mm"
        } else {
            "about ${Frames3.mm(k * 100.0)}% of the profile you drew"
        }

    /** How a refusal names the size this section *is*. */
    fun sizePhrase(): String =
        if (kind != BlendKind.PROFILE) "of ${kind.sizeWord} ${Frames3.mm(size)} mm" else "of that profile"
}

/**
 * **The edge blend: the 2D fillet, one dimension up** (session 71, slice 2 — OP-9's own sentence made
 * literal with machinery that already existed).
 *
 * The whole construction, in the order it runs:
 *
 * 1. The edge is a **provenance-named** [SolidEdge] (slice 1), so it names the **two faces** it separates.
 * 2. At a station along it, the plane **normal to the edge** cuts those two faces in two traces. A plane
 *    traces a line; a band traces a line (a ruling) or a circle — which is exactly [FilletMath.FilletLeg],
 *    so the 2D fillet construction runs **verbatim** in the moving section, and the already-easy cases are
 *    this same construction collapsed: an extrusion's upright and a revolution's ring are edges swept by a
 *    profile *corner*, and their normal section **is** the profile plane, so the arc that lands there is the
 *    outline's own fillet.
 * 3. The blend is the arc of that fillet (or, for a chamfer, the bevel between the two setback points
 *    [FilletMath.setback] finds along the legs) closed back to the corner —
 *    a **corner wedge** — swept along the edge lifted to a [Path3] and applied to the body by a boolean
 *    ([Geom3.combine]): **subtracted** where the sector it fills is material (a convex edge), **added**
 *    where it is void (a concave one).
 *
 * **The scope, stated rather than discovered: the section must be rigid.** What is swept is one section, so
 * an edge whose normal section *changes* along it is not this construction and is refused **by name** rather
 * than approximated. Every plane-against-plane edge is rigid (two planes meet at one dihedral everywhere),
 * as is every edge of a revolution's own frame — a ring, and a cap edge over a profile piece parallel or
 * perpendicular to the axis. What falls outside is named where it is met: a revolve **cap** edge over a
 * *slanted* or *curved* profile piece, whose adjacent band cuts the normal plane in a conic and whose true
 * blend has a spine that is not the edge offset at all. That is a **future extension**, not a silent decline
 * (see DESIGN.md, session 71 slice 2).
 *
 * **The triangles still take the mesh route** — the operands share no axis, so the wedge is applied by the
 * general engine — but since **slice 3** that is a statement about the mesh alone and no longer about the
 * body: the result is a [Feature3.Blend], whose face list *extends* the base's (see [dressedFaces]), so a
 * dressed part keeps its address space and answers sketch-on-face, sections and named refusals. The mesh is
 * a sink (OP-9), which is exactly why answering the faces analytically while the triangles come off the
 * boolean costs nothing. The one place the old cost stands is a blend applied to a body that was **fused**
 * or cut by a general boolean: there is no face list under it to extend, so that result stays a
 * `Feature3.MeshBoolean` with a silhouette plan, and the tool help says so.
 */
object Blend3 {
    /** How close to parallel/perpendicular counts as it, on a dot product of unit vectors. */
    private const val DIR_EPS = 1e-9

    /** How far a trace may differ between two stations, in mm (or as a direction cosine), and still be the same. */
    private const val RIGID_TOL = 1e-6

    /** Where along each piece of the edge the section is read — interior, so a face's own corner is not the sample. */
    private val SAMPLES = listOf(0.1, 0.5, 0.9)

    /** How far off the crease the material is probed, as a fraction of the smallest length in sight. */
    private const val PROBE_FRACTION = 0.05

    /** How many halvings the *largest radius that fits* is reported to (a refusal that heals, OP-3). */
    private const val FIT_STEPS = 24

    /** How far off a face's own boundary (mm) still counts as on the face — see [onFace]. */
    private const val ON_BOUNDARY_TOL = 1e-6

    /** How far apart two exactly-constructed curves may be (mm) and still be the same curve. */
    private const val SAME_CURVE_TOL = 1e-6

    /** How many stations a blend band's own section is sampled at (OP-15: deterministic, never adaptive). */
    private const val BAND_SECTION_STEPS = 64

    /**
     * How far **outside** its own two faces the stitched tool's section is stepped, in mm — see
     * [sectionPolygons]. A micron: four orders below any feature this drawing carries, and two orders above
     * the general engine's own float32 resolution at drawing sizes, which is the gap it exists to open.
     */
    private const val GROW_MM = 1e-3

    /** How far apart two mitre rings' points may be (mm) and still be the same ring — see [ringsAgree]. */
    private const val RING_TOL = 1e-6

    /** How nearly two in-face directions must agree for the hand-over to be smooth rather than a corner. */
    private const val TANGENT_TOL = 1e-9

    /** How far off an axis (mm) a drawn profile's end may stand and still be **on** that face — see [profileIn]. */
    private const val PROFILE_TOL = 1e-7

    /** How firmly two spans of a section must cross to be a crossing rather than a touch (mm²). */
    private const val CROSS_EPS = 1e-18

    // ---- what a blend is addressed by ----

    /**
     * The edges [address] names, as indices into [Section3.edges]'s own order.
     *
     * Two granularities and one rule: with [whole] false the address **is** an edge index — one pick, one
     * edge — and with it true the address is a **face** index and the answer is that face's whole boundary
     * chain ([Section3.edgesOfFace], *"all of the curve parts"* in one click). Both are structural: the
     * lists are the feature's own, so an index means the same thing after every edit (OP-17's index
     * stability), and nothing here is scored.
     */
    fun targets(
        feature: Feature3,
        whole: Boolean,
        address: Int,
        sameRun: ((SolidEdge, SolidEdge) -> Boolean)? = null,
    ): Pair<List<Int>?, String?> {
        val (edges, whyEdges) = Section3.edges(feature)
        if (edges == null) return null to whyEdges
        if (!whole) {
            if (address < 0 || address >= edges.size) {
                return null to "this solid has no edge #${address + 1} (it has ${edges.size})"
            }
            return runThrough(edges, address, sameRun) to null
        }
        val (faces, whyFaces) = Section3.faces(feature)
        if (faces == null) return null to whyFaces
        if (address < 0 || address >= faces.size) {
            return null to "this solid has no face #${address + 1} (it has ${faces.size})"
        }
        val face = faces[address].name
        val hits = edges.indices.filter { edges[it].between.has(face) }
        if (hits.isEmpty()) return null to "${face.label} has no edges to blend"
        // **A face gesture takes the edges of that face that are still creases** (session 80). Two kinds are
        // not: one an earlier rounding already took (it keeps its index and its carrier and says so —
        // [SolidEdge.reason]), and a **rail of a round**, where a band hands over to the face it is tangent
        // to and there is no crease at all. Without this a box could not be finished by faces: rounding its
        // top and bottom leaves every side face carrying two consumed edges and two rails, and the gesture
        // refused in the consumed edge's own words rather than rounding the two uprights that are still
        // sharp — which is exactly the detour GitHub #32's reporter had to take.
        val live = hits.filter { edges[it].reason == null && !smoothRail(feature, it) }
        if (live.isEmpty()) {
            return null to
                "every edge of ${face.label} has already been rounded, so there is nothing left there to " +
                "break — pick a face that still has a sharp edge on it, or change the size on the rounding " +
                "that took them"
        }
        return live to null
    }

    /**
     * Whether edge [index] is a **rail of a round** — where a band meets the face it is tangent to.
     *
     * Structural, never measured: a fillet's arc is tangent to both its legs *by construction*, so every rail
     * a [BlendKind.FILLET] appends is a smooth hand-over and no crease. A **chamfer**'s bevel meets its faces
     * at an angle, so its rails are ordinary sharp edges and a later gesture may break them. A
     * [BlendKind.PROFILE]'s rails are **sharp too, by the same rule read the other way**: whether a drawn
     * section happens to leave its face tangentially is a property of the *values* it was drawn with, and a
     * predicate that measured it would put geometry into a structural answer (OP-21). So a profile's rails
     * are ordinary edges a later gesture may break, which is also the more useful reading — a step's flats
     * genuinely do meet the faces at an angle. Which level a rail was appended at is read by walking the
     * chain — every dressed list keeps its base's indices and appends its own after them ([dressedEdges]) —
     * so the answer is a fact about the feature and not about the geometry (OP-21).
     */
    private fun smoothRail(
        feature: Feature3,
        index: Int,
    ): Boolean {
        if (feature !is Feature3.Blend) return false
        val below = Section3.edges(feature.base).first ?: return false
        if (index < below.size) return smoothRail(feature.base, index)
        return feature.kind == BlendKind.FILLET
    }

    /**
     * How many of the face's own edges an earlier rounding already took — what a face gesture's note says so
     * that *"(2 edges)"* on a four-edged face reads as the statement it is rather than as a surprise.
     */
    fun roundedAlready(
        feature: Feature3,
        address: Int,
    ): Int {
        val edges = Section3.edges(feature).first ?: return 0
        val faces = Section3.faces(feature).first ?: return 0
        val face = faces.getOrNull(address)?.name ?: return 0
        return edges.indices.count { edges[it].between.has(face) && edges[it].reason != null }
    }

    /**
     * The whole **tangent-continuous run** through edge [address] — one pick, one ribbon (GitHub #29).
     *
     * *"I would expect that the fillets are 'smoothly' joined together — as if I rounded all the edges with
     * a rasp."* A single pick therefore names not one edge but the run of edges that carry on smoothly
     * through it, and it is an addressing change and nothing else: the stored address stays the picked edge
     * index, so a file whose edge has no tangent neighbour replays to exactly the band it always built.
     *
     * [sameRun] is the caller's — the **2D joint registry, one level up** (`Document.tangentRun`): whether
     * two pieces meet tangentially is a fact the construction *stated*, never one measured off the geometry,
     * which is what keeps the number of swept edges structural (OP-21) and a replay exact. Absent it, one
     * pick is one edge, as before.
     *
     * The walk is a closure rather than a two-way march: an edge joins the run when it shares a vertex with
     * something already in it and [sameRun] says that vertex is a smooth handover. So a rim that is tangent
     * all the way round comes back whole (which is what the chain over a face already builds), and a run
     * that meets a **sharp** corner ends there exactly as a single-edge band ends today.
     */
    private fun runThrough(
        edges: List<SolidEdge>,
        address: Int,
        sameRun: ((SolidEdge, SolidEdge) -> Boolean)?,
    ): List<Int> {
        if (sameRun == null) return listOf(address)
        val run = linkedSetOf(address)
        var growing = true
        while (growing) {
            growing = false
            for (i in edges.indices) {
                if (i in run) continue
                // an entry that is **not a crease of the body as it stands** — an edge an earlier blend
                // consumed — keeps its index and its carrier and is no part of any run (see [SolidEdge.reason])
                if (edges[i].reason != null) continue
                if (run.any { j -> sharedEnd(edges[j], edges[i]) != null && sameRun(edges[j], edges[i]) }) {
                    run.add(i)
                    growing = true
                }
            }
        }
        return run.sorted()
    }

    /** Where two edges meet end to end, or null when they do not — the vertex a run may carry on through. */
    fun sharedEnd(
        a: SolidEdge,
        b: SolidEdge,
    ): Vec3? {
        val pa = pathOf(a).first ?: return null
        val pb = pathOf(b).first ?: return null
        val endsA = listOfNotNull(pa.start, pa.end)
        val endsB = listOfNotNull(pb.start, pb.end)
        for (x in endsA) for (y in endsB) if ((x - y).length() <= Geom3.WELD_TOL) return x
        return null
    }

    // ---- the crease: the edge as a path, and the two traces of its normal section ----

    /** One station of the edge: where it stands, which way it goes, and the section's second axis there. */
    private class Station(val at: Vec3, val tangent: Vec3, val e2: Vec3)

    /**
     * One edge ready to be blended: its path, the constant frame reference the sweep is stated with, the two
     * traces of the normal section, and the two faces they came from.
     */
    private class Crease(
        val edge: SolidEdge,
        val path: Path3,
        val e1: Vec3,
        val stations: List<Station>,
        val ref: Station,
        val leg1: FilletLeg,
        val leg2: FilletLeg,
        val face1: FacePatch,
        val face2: FacePatch,
        val length: Double,
    )

    /**
     * The edge as a **curve in space** — the lift (OP-26), so the sweep carries the blend along the exact
     * carrier the edge already is: a straight one stays a segment, a cap edge's arc stays an [Curve3Element.Arc3].
     */
    fun edgePath(edge: SolidEdge): Pair<Path3?, String?> = pathOf(edge)

    private fun pathOf(edge: SolidEdge): Pair<Path3?, String?> =
        when (val g = edge.geom) {
            is EdgeGeom.Straight -> {
                if ((g.b - g.a).length() <= Geom3.WELD_TOL) {
                    null to "${edge.name.label} is a single point, so there is no crease along it to blend"
                } else {
                    Path3(listOf(Curve3Element.Seg3(g.a, g.b))) to null
                }
            }
            is EdgeGeom.OnPlane -> {
                when (g.piece) {
                    is ProfileElement.EllipseE, is ProfileElement.EllipticArcE ->
                        null to
                            "${edge.name.label} is an ellipse, which this drawing carries no exact curve in space for — " +
                            "blend a straight or circular edge"
                    is ProfileElement.BezierE ->
                        null to
                            "${edge.name.label} is a spline, whose normal section turns along it — blend a straight " +
                            "or circular edge"
                    else -> {
                        val closed = g.piece is ProfileElement.CircleE
                        val path = Intersect3.liftedRun(listOf(g.piece), g.plane, closed).first
                        if (path.elements.isEmpty()) {
                            null to "${edge.name.label} has no length, so there is no crease along it to blend"
                        } else {
                            path to null
                        }
                    }
                }
            }
        }

    /** The frame reference the sweep is stated with — the edge's own plane normal where it has one. */
    private fun referenceOf(
        edge: SolidEdge,
        t0: Vec3,
    ): Vec3 {
        val seed = (edge.geom as? EdgeGeom.OnPlane)?.plane?.normal?.normalized() ?: Vec3(0.0, 0.0, 1.0)
        return Frames3.startReference(t0, seed)
    }

    /**
     * The **trace of one face** in the plane normal to the edge at [at], in that plane's own `(e1, e2)`.
     *
     * The dispatch is on the **surface**, never on the feature that made it (OP-8's whole point): a plane
     * cuts a plane in a line, an axis-normal cut of a band of revolution is a circle, a cut through the axis
     * is a ruling or a meridian, and a sphere is a circle whichever way it is cut. Everything else is a conic
     * this drawing's rounding vocabulary — lines and circles — has no name for, and is refused **wholly and
     * by name** up front rather than answered half-exactly (the session-69 predicate rule).
     */
    private fun traceOf(
        patch: FacePatch,
        at: Vec3,
        tangent: Vec3,
        e1: Vec3,
        e2: Vec3,
    ): Pair<FilletLeg?, String?> {
        fun local(p: Vec3) = Vec2((p - at).dot(e1), (p - at).dot(e2))

        fun direction(d: Vec3): Vec2? {
            val q = Vec2(d.dot(e1), d.dot(e2))
            return if (q.length() <= DIR_EPS) null else q.normalized()
        }

        fun ruling(d: Vec3): Pair<FilletLeg?, String?> {
            val q = direction(d) ?: return null to "${patch.name.label} lies along that edge rather than crossing it"
            return FilletLeg.of(Line(Vec2(0.0, 0.0), q)) to null
        }

        fun circleAbout(
            axisPoint: Vec3,
            axis: Vec3,
        ): Pair<FilletLeg?, String?> {
            val c = axisPoint + axis * ((at - axisPoint).dot(axis))
            val r = (at - c).length()
            if (r <= Geom3.WELD_TOL) return null to "${patch.name.label} closes on the axis at that edge, so it traces no circle there"
            return FilletLeg.of(Circle(local(c), r)) to null
        }

        val plane = patch.plane
        if (plane != null) return ruling(tangent.cross(plane.normal.normalized()))
        val surface =
            patch.surface
                ?: return null to (patch.reason ?: "${patch.name.label} has no surface this blend can be a function of")
        val axis = surface.axis.normalized()
        val alongAxis = abs(axis.dot(tangent))
        // the axis **line** lies in the normal plane exactly when the edge runs across the axis and the axis
        // point projects into the plane — then a band of revolution is cut in its own meridian
        val throughAxis = alongAxis <= 1e-7 && abs((surface.origin - at).dot(tangent)) <= RIGID_TOL
        return when (val band = surface.band) {
            is Revolve3.Band.Degenerate ->
                null to "${patch.name.label} lies on the axis of revolution, so there is no surface to blend against"
            is Revolve3.Band.Unnamed -> null to "${patch.name.label} is ${band.label}, which this blend has no section for"
            is Revolve3.Band.Planar -> ruling(tangent.cross(axis))
            is Revolve3.Band.Cylinder ->
                when {
                    alongAxis >= 1.0 - 1e-7 -> circleAbout(surface.origin, axis)
                    throughAxis -> ruling(axis)
                    else -> conic(patch, "a cylinder")
                }
            is Revolve3.Band.Cone ->
                when {
                    alongAxis >= 1.0 - 1e-7 -> circleAbout(surface.origin, axis)
                    abs((surface.origin + axis * band.sApex - at).dot(tangent)) <= RIGID_TOL ->
                        ruling(at - (surface.origin + axis * band.sApex))
                    else -> conic(patch, "a cone")
                }
            // a plane cuts a sphere in a circle whichever way it is turned, which is the one band with no case
            is Revolve3.Band.Sphere -> {
                val centre = surface.origin + axis * band.sc
                val h = (centre - at).dot(tangent)
                val inPlane = centre - tangent * h
                val r = (at - inPlane).length()
                if (r <= Geom3.WELD_TOL) {
                    null to "${patch.name.label} closes to a point at that edge, so it traces no circle there"
                } else {
                    FilletLeg.of(Circle(local(inPlane), r)) to null
                }
            }
            is Revolve3.Band.Torus ->
                when {
                    alongAxis >= 1.0 - 1e-7 -> circleAbout(surface.origin, axis)
                    throughAxis -> {
                        val onAxis = surface.origin + axis * band.sc
                        val radial = (at - onAxis).let { it - axis * it.dot(axis) }
                        if (radial.length() <= Geom3.WELD_TOL) {
                            null to "${patch.name.label} meets its own axis at that edge"
                        } else {
                            FilletLeg.of(Circle(local(onAxis + radial.normalized() * band.rc), band.minor)) to null
                        }
                    }
                    else -> conic(patch, "a torus")
                }
        }
    }

    private fun conic(
        patch: FacePatch,
        what: String,
    ): Pair<FilletLeg?, String?> =
        null to
            "${patch.name.label} is $what standing askew to that edge, so the plane square to the edge cuts it in a " +
            "conic rather than in a line or a circle — this blend rounds a crease whose section is one of those, " +
            "and a section that is neither is a future extension"

    /** Whether two traces read in two stations' frames are the **same** trace — the rigidity the sweep needs. */
    private fun same(
        a: FilletLeg,
        b: FilletLeg,
    ): Boolean =
        when {
            a.line != null && b.line != null -> abs(a.line.dir.dot(b.line.dir)) >= 1.0 - RIGID_TOL
            a.circle != null && b.circle != null ->
                (a.circle.center - b.circle.center).length() <= RIGID_TOL && abs(a.circle.radius - b.circle.radius) <= RIGID_TOL
            else -> false
        }

    /**
     * The edge, read as a crease: its path, its stations, and the two traces — refused by name where the
     * section is not one this rounding can say, or where it **changes along the edge**.
     */
    private fun creaseOf(
        feature: Feature3,
        edge: SolidEdge,
    ): Pair<Crease?, String?> {
        // an edge a blend already consumed keeps its index and its carrier, but it is no longer a crease of
        // the body — so building on it is refused in the words the dressed list put there (slice 3)
        edge.reason?.let { return null to it }
        val (faces, whyFaces) = Section3.faces(feature)
        if (faces == null) return null to whyFaces
        val face1 = faces.firstOrNull { it.name == edge.between.a } ?: return null to "this solid has no ${edge.between.a.label}"
        val face2 = faces.firstOrNull { it.name == edge.between.b } ?: return null to "this solid has no ${edge.between.b.label}"
        if (face1.name == face2.name) {
            return null to
                "${edge.name.label} is a seam where a face meets itself rather than a crease between two faces, " +
                "so there is nothing there to break"
        }
        val (path, whyPath) = pathOf(edge)
        if (path == null) return null to whyPath
        val t0 = Curves3.tangentAt(path.elements.first(), 0.0) ?: return null to "${edge.name.label} has no direction to sweep along"
        val e1 = referenceOf(edge, t0)
        val stations = ArrayList<Station>()
        var length = 0.0
        for (el in path.elements) {
            length += Curves3.lengthTo(el, 1.0)
            for (f in SAMPLES) {
                val t = Curves3.tangentAt(el, f) ?: continue
                stations.add(Station(Frames3.pointAt(el, f), t, t.cross(e1).normalized()))
            }
        }
        if (stations.isEmpty()) return null to "${edge.name.label} has no direction to sweep along"
        // the **reference station** is the middle one, and its traces are the section that gets swept: the
        // others are read only to check that it is the same section there, which is what makes sweeping one
        // rigid wedge a claim rather than an assumption
        val at = stations.size / 2
        var leg1: FilletLeg? = null
        var leg2: FilletLeg? = null
        for ((k, st) in stations.withIndex()) {
            val (a, whyA) = traceOf(face1, st.at, st.tangent, e1, st.e2)
            if (a == null) return null to "${edge.name.label}: $whyA"
            val (b, whyB) = traceOf(face2, st.at, st.tangent, e1, st.e2)
            if (b == null) return null to "${edge.name.label}: $whyB"
            val known1 = leg1
            val known2 = leg2
            if (known1 != null && known2 != null && (!same(known1, a) || !same(known2, b))) {
                return null to
                    "the section square to ${edge.name.label} changes along it — this blend sweeps one rigid " +
                    "section, so an edge whose section varies (a revolve's cap edge over a slanted or curved " +
                    "profile piece) is a future extension rather than something to approximate"
            }
            if (known1 == null || k == at) {
                leg1 = a
                leg2 = b
            }
        }
        return Crease(edge, path, e1, stations, stations[at], leg1!!, leg2!!, face1, face2, length) to null
    }

    // ---- which sector the blend fills, and whether it is material ----

    /** The outward unit normal of a trace at the corner — a line's perpendicular, a circle's radial. */
    private fun normalOf(leg: FilletLeg): Vec2? {
        val l = leg.line
        if (l != null) return l.dir.perp().normalized()
        val c = leg.circle ?: return null
        val d = Vec2(0.0, 0.0) - c.center
        return if (d.length() <= Vec2.EPS) null else d.normalized()
    }

    /** Which side of a trace [q] falls on: `+1` outward, `-1` inward, `0` on it. */
    private fun sideOf(
        leg: FilletLeg,
        q: Vec2,
    ): Int {
        val l = leg.line
        val d = if (l != null) (q - l.origin).dot(l.dir.perp().normalized()) else (q - leg.circle!!.center).length() - leg.circle.radius
        return if (d > 0.0) {
            1
        } else if (d < 0.0) {
            -1
        } else {
            0
        }
    }

    /**
     * The **sector the blend fills**, scored once from the body itself (OP-1): the two traces cut the section
     * into four sectors, and the one that is *different from the other three* is the one a blend goes in — the
     * lone **material** sector at a convex edge (where the blend is subtracted) and the lone **void** one at a
     * concave edge (where it is added).
     *
     * The reading is a containment question about one point, asked of the body ([Geom3.encloses]) at a stated
     * distance off the crease, and its answer is stored as a sign and never asked again. A count that is
     * neither one nor three means the two faces do not make a simple crease there, and is refused rather than
     * guessed at.
     */
    private fun sectorOf(
        crease: Crease,
        mesh: Mesh3,
        reach: Double,
    ): Pair<Triple<Int, Int, Boolean>?, String?> {
        val n1 = normalOf(crease.leg1) ?: return null to "${crease.edge.name.label}: ${crease.face1.name.label} has no side at that edge"
        val n2 = normalOf(crease.leg2) ?: return null to "${crease.edge.name.label}: ${crease.face2.name.label} has no side at that edge"
        var scale = min(reach, crease.length / 2.0)
        crease.leg1.circle?.let { scale = min(scale, it.radius) }
        crease.leg2.circle?.let { scale = min(scale, it.radius) }
        val delta = scale * PROBE_FRACTION
        if (delta <= Geom3.WELD_TOL) return null to "${crease.edge.name.label} is too small to blend at that size"
        val found = ArrayList<Pair<Int, Int>>(4)
        for (s1 in listOf(1, -1)) {
            for (s2 in listOf(1, -1)) {
                val q = n1 * (s1 * delta) + n2 * (s2 * delta)
                if (sideOf(crease.leg1, q) != s1 || sideOf(crease.leg2, q) != s2) {
                    return null to
                        "${crease.face1.name.label} and ${crease.face2.name.label} run too nearly tangent at " +
                        "${crease.edge.name.label} to tell the four sides of it apart"
                }
                val w = crease.ref.at + crease.e1 * q.x + crease.ref.e2 * q.y
                if (Geom3.encloses(mesh, w)) found.add(s1 to s2)
            }
        }
        return when (found.size) {
            // one material side of four: a **convex** edge, and the blend is subtracted out of it
            1 -> Triple(found[0].first, found[0].second, true) to null
            // three: a **concave** one, and the blend fills the lone void side
            3 ->
                listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1).first { it !in found }
                    .let { Triple(it.first, it.second, false) } to null
            else ->
                null to
                    "${crease.edge.name.label} is not a simple crease between ${crease.face1.name.label} and " +
                    "${crease.face2.name.label} — the material fills ${found.size} of its four sides, and a blend " +
                    "needs exactly one of them to itself"
        }
    }

    // ---- the corner wedge, in the section's own coordinates ----

    /**
     * The two tangencies of a blend, the wedge it fills the corner with, and the **blend's own section
     * curve** — the arc of a fillet, the bevel of a chamfer.
     *
     * [piece] is what slice 3 carries the band away on: the surface the blend *adds* to the body is that one
     * curve swept along the edge, so the face list is built from the same object the boolean was.
     */
    private class Wedge(
        val region: Region,
        val t1: Vec2,
        val t2: Vec2,
        /**
         * The blend's own section, **from [t1] to [t2]** — one piece for the two built-ins, the drawn chain
         * for [BlendKind.PROFILE]. The order is the one [sectionPolygons] walks, so it is stated rather than
         * re-derived.
         */
        val pieces: List<ProfileElement>,
        /**
         * Whether the wedge's own counter-clockwise loop traverses [pieces] **forwards**.
         *
         * This is what says which way the band faces, exactly and for any section: a counter-clockwise loop
         * has its interior on the **left** of travel, so the direction *into* the wedge at a section piece is
         * `perp(dir)` where the loop runs forwards and its negative where it runs back. The old rule — a
         * fillet's `q − centre`, a chamfer's normal toward the corner — is this one collapsed onto the two
         * shapes it was written for, and it is wrong for a **cove** (an arc bulging away from the crease),
         * whose centre is on the other side. See [bandOutward].
         */
        val forward: Boolean,
    )

    private fun sidePiece(
        leg: FilletLeg,
        from: Vec2,
        to: Vec2,
    ): ProfileElement {
        val c = leg.circle ?: return ProfileElement.Seg(Segment(from, to))
        val a = (from - c.center).angle()
        val b = (to - c.center).angle()
        var sweep = b - a
        while (sweep <= -PI) sweep += 2.0 * PI
        while (sweep > PI) sweep -= 2.0 * PI
        return ProfileElement.ArcE(Arc(c.center, c.radius, a, b, sweep >= 0.0))
    }

    /** The corner wedge of one scored choice, or the reason there is none at this size. */
    private fun wedgeOf(
        crease: Crease,
        sec: BlendSection,
        choice: BlendChoice,
    ): Pair<Wedge?, String?> {
        val loop: Loop
        val t1: Vec2
        val t2: Vec2
        val blendPieces: List<ProfileElement>
        if (sec.kind == BlendKind.PROFILE) {
            val (drawn, whyDrawn) = profileIn(crease, sec.profile, choice)
            if (drawn == null) return null to whyDrawn
            blendPieces = drawn
            t1 = GeomMath.startOf(drawn.first())
            t2 = GeomMath.endOf(drawn.last())
            loop = Loop(listOf(sidePiece(crease.leg1, Vec2(0.0, 0.0), t1)) + drawn + listOf(sidePiece(crease.leg2, t2, Vec2(0.0, 0.0))))
        } else if (sec.kind == BlendKind.FILLET) {
            val straight1 = crease.leg1.line
            val straight2 = crease.leg2.line
            val arc =
                if (straight1 != null && straight2 != null) {
                    // two straight legs: the corner is a real point, so the fillet is the quadrant
                    // construction ([FilletMath.lineLineArc]) — the same split the 2D tool makes, for the
                    // same reason (two offset *lines* meet in one point and there is no branch to pick)
                    FilletMath.lineLineArc(straight1, straight2, sec.size, choice.a, choice.b)
                        ?: return null to notFitting(crease, sec)
                } else {
                    FilletMath.arcOf(crease.leg1, crease.leg2, sec.size, FilletVariant(choice.a, choice.b, choice.branch))
                        ?: return null to notFitting(crease, sec)
                }
            t1 = arc.center + Vec2(cos(arc.startAngle), sin(arc.startAngle)) * arc.radius
            t2 = arc.center + Vec2(cos(arc.endAngle), sin(arc.endAngle)) * arc.radius
            blendPieces = listOf(ProfileElement.ArcE(arc))
            loop = Loop(listOf(sidePiece(crease.leg1, Vec2(0.0, 0.0), t1), ProfileElement.ArcE(arc), sidePiece(crease.leg2, t2, Vec2(0.0, 0.0))))
        } else {
            // **the chamfer-on-arc convention, inherited** (session 76, item c): the corner of the section is
            // the origin here, so each setback point is that distance from it **along its own leg** — a step
            // along a straight one, an arc distance along a round one ([FilletMath.setback], where the
            // convention is argued). The wedge is then closed with [sidePiece] exactly as the fillet's is, so
            // the two kinds differ in one piece — the bevel where the arc was — and in nothing else. For two
            // straight legs this is [FilletMath.chamferEnds] point for point, so no dressed body changes.
            val corner = Vec2(0.0, 0.0)
            val a = FilletMath.setback(crease.leg1, corner, sec.size, choice.a) ?: return null to notFitting(crease, sec)
            val b = FilletMath.setback(crease.leg2, corner, sec.size, choice.b) ?: return null to notFitting(crease, sec)
            if ((b - a).length() <= Geom3.WELD_TOL) return null to notFitting(crease, sec)
            val bevel = Segment(a, b)
            t1 = a
            t2 = b
            blendPieces = listOf(ProfileElement.Seg(bevel))
            loop = Loop(listOf(sidePiece(crease.leg1, corner, t1), ProfileElement.Seg(bevel), sidePiece(crease.leg2, t2, corner)))
        }
        val forward = GeomMath.signedArea(loop) >= 0.0
        val oriented = if (forward) loop else GeomMath.reverseLoop(loop)
        if (abs(GeomMath.signedArea(oriented)) <= Geom3.WELD_TOL * Geom3.WELD_TOL) {
            return null to "a ${sec.kind.word} ${sec.sizePhrase()} leaves no material at ${crease.edge.name.label}"
        }
        // **a section that crosses itself has no region to take away** (GitHub #30's own refusal). Asked of
        // the whole loop rather than of the drawing alone, because a profile that is simple on paper can
        // still cross a leg once it is read in a skewed corner's frame.
        if (sec.kind == BlendKind.PROFILE && crossesItself(oriented)) {
            return null to
                "that profile crosses itself once it is read in the corner at ${crease.edge.name.label}, so there " +
                "is no corner region for it to take away — draw a profile that runs from one face to the other " +
                "without doubling back"
        }
        return Wedge(Region(oriented, emptyList()), t1, t2, blendPieces, forward) to null
    }

    /**
     * The drawn profile read **in the corner's own frame** — the whole of the general tier's mechanism, and
     * the answer to the report's *"the cut must extend the length of the edge to produce the result of a
     * rasped edge"*.
     *
     * *The frame.* Let `u1` be the unit direction of the first leg pointing the way the corner opens and
     * `u2` the same for the second — the very directions [FilletMath.setback] steps along, which is why a
     * one-segment profile comes out as [BlendKind.CHAMFER] vertex for vertex. A drawn point `(x, y)` is read
     * as `x·u1 + y·u2`, so **x is the setback along the first face and y the setback along the second**, in
     * millimetres, at *every* dihedral. The frame is therefore oblique wherever the two faces do not stand
     * square, and that is the point rather than a distortion: the numbers stay setbacks, so a skewed corner
     * is cut to the length a rasp reaches, and a drawn arc becomes the sheared arc a hot wire would leave.
     *
     * The rejected alternative was an **orthonormal** frame on the corner's bisector, which keeps a drawn
     * circle circular at every angle and pays for it by having the profile's ends land on the two faces at a
     * right dihedral only — so one drawing would serve one corner and could not be shared, which is the
     * opposite of what sharing a node means here.
     *
     * *Which end goes to which face* is [BlendChoice.flip], scored once from the click and then taken
     * verbatim (OP-1/OP-18): `+1` reads the drawn x as the setback on face 1, `-1` the other way round. The
     * chain is returned running **from face 1 to face 2** whichever it was drawn, so everything downstream —
     * the tangencies, the rails, the trims — keeps naming the same face by the same number.
     */
    private fun profileIn(
        crease: Crease,
        drawn: List<ProfileElement>,
        choice: BlendChoice,
    ): Pair<List<ProfileElement>?, String?> {
        if (drawn.isEmpty()) return null to "that profile has no pieces to read"
        if (crease.leg1.line == null || crease.leg2.line == null) {
            return null to
                "the section square to ${crease.edge.name.label} meets ${
                    (if (crease.leg1.line == null) crease.face1 else crease.face2).name.label
                } in a circle rather than in a straight leg, and a drawn profile states its two ends as " +
                "setbacks along two straight legs — round that edge with Fillet edge or Chamfer edge, whose " +
                "section is tangent to the curve by construction. A drawn profile against a curved leg is a " +
                "future extension"
        }
        // the pieces must actually make one run, or the "two ends" the frame reads are not two ends
        for (i in 0 until drawn.size - 1) {
            if ((GeomMath.startOf(drawn[i + 1]) - GeomMath.endOf(drawn[i])).length() > PROFILE_TOL) {
                return null to "that profile's piece ${i + 1} does not meet piece ${i + 2}, so it is not one run from face to face"
            }
        }
        // …and it must run from one axis to the other: the drawn x-axis *is* one face and the y-axis the
        // other, so a profile stating its ends anywhere else is stating no setbacks at all
        val head = GeomMath.startOf(drawn.first())
        val tail = GeomMath.endOf(drawn.last())
        val run =
            when {
                abs(head.y) <= PROFILE_TOL && abs(tail.x) <= PROFILE_TOL -> drawn
                abs(head.x) <= PROFILE_TOL && abs(tail.y) <= PROFILE_TOL -> drawn.reversed().map { GeomMath.reverse(it) }
                else ->
                    return null to
                        "that profile's ends do not state a setback on each face: they stand at " +
                        "(${Frames3.mm(head.x)}, ${Frames3.mm(head.y)}) and (${Frames3.mm(tail.x)}, ${Frames3.mm(tail.y)}), " +
                        "and a profile's two ends must lie on the two axes — one at (x, 0), the other at (0, y). " +
                        "Move them onto the axes"
            }
        val a = GeomMath.startOf(run.first()).x
        val b = GeomMath.endOf(run.last()).y
        if (a <= PROFILE_TOL || b <= PROFILE_TOL) {
            return null to
                "that profile states a setback of ${Frames3.mm(a)} and ${Frames3.mm(b)} mm, and a rounding needs a " +
                "positive setback on each face — move the end that sits on the corner"
        }
        // **inside the corner's own quadrant, or it is a bead** — a profile reaching outside would *add*
        // material at a convex edge, and a section driving two booleans of opposite sign is structure decided
        // from a value (OP-21). Checked in the drawn coordinates, where the quadrant is exactly `x, y >= 0`.
        for (e in run) {
            for (q in GeomMath.tessellatePiece(e, GeomMath.TESS_TOL_MM)) {
                if (q.x < -PROFILE_TOL || q.y < -PROFILE_TOL) {
                    return null to
                        "that profile reaches outside the corner between ${crease.face1.name.label} and " +
                        "${crease.face2.name.label} — a profile that leaves the corner would add material rather " +
                        "than take it away. To add a bead, sweep a closed section along the edge and fuse it"
                }
            }
        }
        val u1 = FilletMath.setback(crease.leg1, Vec2(0.0, 0.0), 1.0, choice.a)
        val u2 = FilletMath.setback(crease.leg2, Vec2(0.0, 0.0), 1.0, choice.b)
        if (u1 == null || u2 == null) return null to notFitting(crease, BlendSection(BlendKind.PROFILE, 0.0, drawn))
        val forward = choice.flip >= 0
        val cx = if (forward) u1 else u2
        val cy = if (forward) u2 else u1
        val map = Affine(cx.x, cx.y, cy.x, cy.y, 0.0, 0.0)
        if (abs(map.det) <= DIR_EPS) {
            return null to "${crease.face1.name.label} and ${crease.face2.name.label} run too nearly parallel at ${crease.edge.name.label} to read a profile in"
        }
        val mapped = run.map { GeomMath.transform(it, map) }
        // with the flip the drawn x is the setback on **face 2**, so the run comes out face 2 → face 1 and is
        // turned round: `t1` names face 1's tangency whichever way the profile was read
        return (if (forward) mapped else mapped.reversed().map { GeomMath.reverse(it) }) to null
    }

    /** Whether a loop's own boundary crosses itself — asked of the tessellation, which is what is swept. */
    private fun crossesItself(loop: Loop): Boolean {
        val pts = ArrayList<Vec2>()
        for (e in loop.elements) {
            for (q in GeomMath.tessellatePiece(e, GeomMath.TESS_TOL_MM)) {
                if (pts.isEmpty() || (q - pts.last()).length() > Geom3.WELD_TOL) pts.add(q)
            }
        }
        while (pts.size > 1 && (pts.first() - pts.last()).length() <= Geom3.WELD_TOL) pts.removeAt(pts.size - 1)
        val n = pts.size
        if (n < 4) return false
        for (i in 0 until n) {
            for (j in i + 2 until n) {
                // neighbours share a point by construction and are never a crossing
                if (i == 0 && j == n - 1) continue
                if (crosses(pts[i], pts[(i + 1) % n], pts[j], pts[(j + 1) % n])) return true
            }
        }
        return false
    }

    /** Whether the two open segments `a→b` and `c→d` cross properly. */
    private fun crosses(
        a: Vec2,
        b: Vec2,
        c: Vec2,
        d: Vec2,
    ): Boolean {
        fun side(
            p: Vec2,
            q: Vec2,
            r: Vec2,
        ): Double = (q - p).cross(r - p)
        val d1 = side(a, b, c)
        val d2 = side(a, b, d)
        val d3 = side(c, d, a)
        val d4 = side(c, d, b)
        return d1 * d2 < -CROSS_EPS && d3 * d4 < -CROSS_EPS
    }

    private fun notFitting(
        crease: Crease,
        sec: BlendSection,
    ): String =
        "no ${sec.kind.word} ${sec.sizePhrase()} fits between ${crease.face1.name.label} and " +
            "${crease.face2.name.label} at ${crease.edge.name.label}"

    /**
     * Whether both tangencies stand **on** their own faces at every station, and the reason with the largest
     * size that would (OP-3's heal, stated as the number to type).
     *
     * This is the *"a radius that outgrows a leg"* refusal, and it is a question about the faces rather than
     * about the arc: a 20 mm round on the rim of a 10 mm plate has a perfectly good tangent circle, and it
     * reaches straight past the bottom of the plate. Asked only of the faces that **have** an outline — a
     * plane's — because a band's own extent is the sweep's business ([Embedding]) and it says so in the
     * curve's words.
     */
    private fun tangenciesFit(
        crease: Crease,
        wedge: Wedge,
    ): Boolean {
        for (st in crease.stations) {
            for ((face, t) in listOf(crease.face1 to wedge.t1, crease.face2 to wedge.t2)) {
                val plane = face.plane ?: continue
                val rings = Project3.ringsOf(face.outline)
                if (rings.isEmpty()) continue
                // **the station has to still be a crease of this face to be asked about** (session 80). A
                // dressed face's boundary steps inward wherever a *neighbouring* edge was rounded, so on a
                // second blend the far stations of this edge can stand in the strip that blend already took
                // — the edge keeps its full carrier ("the neighbours' ends", session 71's own cut) while the
                // face no longer reaches it. Asking whether the tangency lies on the face *there* is asking
                // about a crease that is gone, and its answer refused a rounding that fits perfectly well:
                // three edges of one corner, taken one gesture at a time, could not be had (GitHub #32).
                if (!onFace(rings, plane.toLocal(st.at))) continue
                val world = st.at + crease.e1 * t.x + st.e2 * t.y
                if (!onFace(rings, plane.toLocal(world))) return false
            }
        }
        return true
    }

    /**
     * Whether [q] lies **on** the face bounded by [rings] — inside it, or on its own boundary within
     * [ON_BOUNDARY_TOL].
     *
     * The boundary case is not a courtesy, it is the case slice 3 created: a dressed part's faces are the
     * base's with a **strip removed along each blended edge** ([Feature3.Blend]), so a second blend's
     * station can land exactly on the first blend's trim line — a fillet of 4 mm on one rim puts the cap's
     * new boundary at x = 4, and the next edge's first station is at a tenth of a 40 mm run. Asking a
     * strict inside/outside there is asking a coin to decide whether a chamfer fits, and it does fit: the
     * tangency is *on* the face. A hair either way is inside, and a radius that genuinely outgrows a face
     * misses it by millimetres, so nothing is masked.
     */
    private fun onFace(
        rings: List<List<Vec2>>,
        q: Vec2,
    ): Boolean {
        if (RegionBool.contains(rings, q)) return true
        for (ring in rings) {
            for (i in ring.indices) {
                val a = ring[i]
                val b = ring[(i + 1) % ring.size]
                val d = b - a
                val len2 = d.dot(d)
                val t = if (len2 <= 1e-18) 0.0 else ((q - a).dot(d) / len2).coerceIn(0.0, 1.0)
                if ((q - (a + d * t)).length() <= ON_BOUNDARY_TOL) return true
            }
        }
        return false
    }

    /** The largest size that fits at this crease, by halving — what a refusal names so it can be acted on. */
    private fun largestFitting(
        crease: Crease,
        sec: BlendSection,
        choice: BlendChoice,
    ): Double {
        var lo = 0.0
        var hi = 1.0
        repeat(FIT_STEPS) {
            val mid = (lo + hi) / 2.0
            val (w, _) = wedgeOf(crease, sec.scaledBy(mid), choice)
            if (w != null && tangenciesFit(crease, w)) lo = mid else hi = mid
        }
        return lo
    }

    // ---- the corner where two blends meet: the mitre, built rather than found (session 79) ----

    /**
     * How a **section's own 2D coordinates** are placed in space at one ring of the blend's cutting tool —
     * an affine map, and that is the whole reason the corner works.
     *
     * A ring is either a plain one (the section standing square to the edge at one station) or a **mitre**
     * one (the section stretched into the surface that splits the corner). Both are affine in the section's
     * `(x, y)`, so one representation carries both, and the cap triangles ([Geom3.triangulate] of the very
     * polygon the ring is) go through the same map as the ring itself.
     */
    private class Placement(val origin: Vec3, val cx: Vec3, val cy: Vec3) {
        fun at(q: Vec2): Vec3 = origin + cx * q.x + cy * q.y
    }

    /**
     * One target edge **prepared for the tool**: the crease, the wedge and the choice that made it, plus the
     * wedge's own boundary as a polygon and that polygon triangulated.
     *
     * [existing] marks a band that is **already off the body** — the chain this blend continues (see
     * [chainPieces]). Such a piece is never cut again; it is in the list so that the corner where a *new*
     * band meets it is built by construction instead of being looked for by the general boolean, which is
     * what GitHub #27 asked for.
     */
    private class Piece(
        val index: Int,
        val existing: Boolean,
        val crease: Crease,
        val wedge: Wedge,
        val choice: BlendChoice,
        val sec: BlendSection,
        /** The wedge's boundary, counter-clockwise in the section frame, starting at the section's corner. */
        val section: List<Vec2>,
        /** The same boundary grown out through both faces, point for point — see [sectionPolygons]. */
        val grown: List<Vec2>,
        /** The same polygon triangulated once — the tool's cap at every free end. */
        val caps: List<Geom3.Tri3>,
        /** The edge as one straight run, or null: only a straight edge can carry a corner (see [cornersOf]). */
        val seg: Curve3Element.Seg3?,
    ) {
        /** How long the run is — asked only where [seg] is there. */
        val length: Double get() = seg!!.let { (it.end - it.start).length() }
    }

    /**
     * A **mitre corner**: where two pieces meet at a shared vertex, the ring both of them end on.
     *
     * The ring is stored per side, as each side's own placement of its own section, and the two are *the
     * same points* — [ringsAgree] is what says so, and a pair that cannot say so is not a joint (the two
     * sweeps then overlap and the boolean trims them, exactly as before this session).
     */
    private class Joint(
        val a: Int,
        val aAtStart: Boolean,
        val placeA: Placement,
        val b: Int,
        val bAtStart: Boolean,
        val placeB: Placement,
        val shared: FacePatch,
    ) : Corner {
        override val ends: List<Pair<Int, Boolean>> get() = listOf(a to aAtStart, b to bAtStart)

        override fun ringAt(end: Pair<Int, Boolean>): Placement = if (end.first == a && end.second == aAtStart) placeA else placeB

        /** Nothing: the two tubes end on the same ring, so the surface is already closed there. */
        override fun emit(
            pieces: List<Piece>,
            out: Geom3.MeshBuilder,
        ) = Unit

        override fun label(pieces: List<Piece>): String =
            "the corner where ${pieces[a].crease.edge.name.label} meets ${pieces[b].crease.edge.name.label} " +
                "on ${shared.name.label}"
    }

    /**
     * The two section polygons the **stitched** tool is swept between: the wedge's own boundary, and the
     * same boundary **grown out through both faces** by [GROW_MM] — point for point, so a ring may be
     * either without the quads between two rings losing their correspondence.
     *
     * *Why grown.* The wedge's two legs lie exactly *in* the two faces, so the tool it sweeps has a flat
     * side coincident with a face of the body over a strip as wide as the tangency. One such contact a mesh
     * boolean can resolve; two of them overlapping at a corner — or one meeting a face another blend has
     * already trimmed — is a coplanar overlap whose answer is a fraction of a float32, and that is the
     * *"used 2 times with 2 opposite uses"* both reporters met. Stepping the legs a micron **outside** their
     * own faces turns those contacts into ordinary transversal crossings: the flat sides now cross the faces
     * at exactly the tangency lines instead of lying along them, and the arc between the two tangencies —
     * the only part of the section that decides any geometry — is untouched.
     *
     * *Why only at a corner.* A **free** end is capped on the plane square to the edge, where the body has
     * its own upright edge, and a tool a micron proud of two faces there leaves a micron-wide notch at that
     * upright which is a worse contact than the one it cured. So the growth is tapered: the ring at a corner
     * is the grown section, the ring at a free end is the plain one, and the flat side between them touches
     * its face along a *line* rather than over a strip.
     *
     * *And nothing extra is removed.* At a convex crease the material is inward of both faces, so a micron
     * beyond either is outside the body; at a concave one the wedge is added and the growth lies inside
     * material already there. Every volume in the suite is unchanged by it, which is what says so.
     *
     * A **round** leg (a fillet against a cylinder) has no straight offset in this vocabulary, so it is
     * swept as it always was — a plane against a curved band is not the degenerate case.
     */
    private fun sectionPolygons(
        crease: Crease,
        wedge: Wedge,
    ): Pair<Pair<List<Vec2>, List<Vec2>>?, String?> {
        val o = Vec2(0.0, 0.0)
        val arc = wedge.pieces.flatMap { GeomMath.tessellatePiece(it, GeomMath.TESS_TOL_MM) }
        val n1 = outwardAt(wedge.t1, wedge.t2)
        val n2 = outwardAt(wedge.t2, wedge.t1)
        val plain: List<Vec2>
        val grown: List<Vec2>
        if (crease.leg1.line != null && crease.leg2.line != null && n1 != null && n2 != null) {
            val corner = offsetCorner(n1, n2) ?: return null to "the two faces at that crease run too nearly parallel to step off"
            plain = listOf(o, wedge.t1) + arc + listOf(wedge.t2)
            grown = listOf(corner, wedge.t1 + n1 * GROW_MM) + arc + listOf(wedge.t2 + n2 * GROW_MM)
        } else {
            val pts = ArrayList<Vec2>()
            pts.add(o)
            pts.addAll(GeomMath.tessellatePiece(sidePiece(crease.leg1, o, wedge.t1), GeomMath.TESS_TOL_MM))
            pts.addAll(arc)
            pts.addAll(GeomMath.tessellatePiece(sidePiece(crease.leg2, wedge.t2, o), GeomMath.TESS_TOL_MM))
            val kept = ArrayList<Vec2>(pts.size)
            for (q in pts) if (kept.isEmpty() || (q - kept.last()).length() > Geom3.WELD_TOL) kept.add(q)
            while (kept.size > 1 && (kept.first() - kept.last()).length() <= Geom3.WELD_TOL) kept.removeAt(kept.size - 1)
            plain = kept
            grown = kept
        }
        if (plain.size < 3) return null to "the rounding's own section has fewer than three corners"
        // one winding for both, so index k of either ring is the same point of the same section
        return if (Geom3.polygonArea(grown) >= 0.0) {
            (plain to grown) to null
        } else {
            (reversedFromFirst(plain) to reversedFromFirst(grown)) to null
        }
    }

    /** [poly] traversed the other way round, keeping its first point first — the same points, one permutation. */
    private fun reversedFromFirst(poly: List<Vec2>): List<Vec2> = listOf(poly.first()) + poly.drop(1).reversed()

    /** The unit normal of the straight leg through [t], pointing **away** from the material ([other]'s side). */
    private fun outwardAt(
        t: Vec2,
        other: Vec2,
    ): Vec2? {
        if (t.length() <= Geom3.WELD_TOL) return null
        val p = t.normalized().perp()
        return if (other.dot(p) > 0.0) p * -1.0 else p
    }

    /** Where the two legs stepped [GROW_MM] outward meet — the grown section's own corner. */
    private fun offsetCorner(
        n1: Vec2,
        n2: Vec2,
    ): Vec2? {
        val det = n1.x * n2.y - n1.y * n2.x
        if (abs(det) <= 1e-9) return null
        return Vec2(GROW_MM * (n2.y - n1.y) / det, GROW_MM * (n1.x - n2.x) / det)
    }

    /** One target edge prepared: everything the tool needs about it, computed once. */
    private fun pieceOf(
        index: Int,
        existing: Boolean,
        crease: Crease,
        wedge: Wedge,
        choice: BlendChoice,
        sec: BlendSection,
    ): Pair<Piece?, String?> {
        val (polys, whyPoly) = sectionPolygons(crease, wedge)
        if (polys == null) return null to "${crease.edge.name.label}: $whyPoly"
        val (plain, grown) = polys
        val distinct = ArrayList<Vec2>(plain.size)
        for (q in plain) if (distinct.none { (it - q).length() <= Geom3.WELD_TOL }) distinct.add(q)
        val (caps, whyCaps) = Geom3.triangulate(Geom3.TessRegion(distinct, emptyList()))
        if (caps == null) {
            return null to "${crease.edge.name.label}: ${whyCaps ?: "the rounding's section cannot be triangulated"}"
        }
        return Piece(index, existing, crease, wedge, choice, sec, plain, grown, caps, soleElement(crease) as? Curve3Element.Seg3) to null
    }

    /**
     * The bands **already taken off** the body this blend dresses — its own chain, walked to the bottom.
     *
     * *Why a blend looks at what is under it* (GitHub #27). A blend of a blend on an **adjacent** edge meets
     * the first band at a shared vertex, and the corner there is the same corner a one-gesture chain would
     * build. The first band's crease, wedge and choice are all still on record in the [Feature3.Blend] under
     * this one, so that corner can be *constructed* rather than left to the boolean to find — and since the
     * tool then carries that band along with the new one, the two routes (two gestures, or one) take away
     * the very same region and the two bodies agree to the boolean's own arithmetic noise.
     *
     * Cutting the same band twice costs nothing and changes nothing: the tool is subtracted from a body that
     * band is already off, so the second cut is a coincident-face no-op. What it buys is the corner.
     *
     * A level whose crease can no longer be read is **skipped** rather than refused: it was built once, and
     * this is a better corner and never a new requirement (OP-3 — a reason belongs where the decision is).
     */
    private fun chainPieces(feature: Feature3): List<Piece> {
        val out = ArrayList<Piece>()
        var f = feature
        while (f is Feature3.Blend) {
            val below = f.base
            val (edges, _) = Section3.edges(below)
            if (edges != null) {
                for ((k, i) in f.targets.withIndex()) {
                    val edge = edges.getOrNull(i) ?: continue
                    val crease = creaseOf(below, edge).first ?: continue
                    val choice = f.choices.getOrNull(k) ?: continue
                    val wedge = wedgeOf(crease, f.section, choice).first ?: continue
                    out.add(pieceOf(i, true, crease, wedge, choice, f.section).first ?: continue)
                }
            }
            f = below
        }
        return out
    }

    // ---- where the ball stands still: the two corner patches (session 80, GitHub #31 and #32) ----

    /**
     * What closes one end of a band in the stitched tool, and the surface it puts between the ends.
     *
     * Three kinds, and they are three *geometries* rather than three special cases. Where two bands cross
     * they are split on the plane equidistant from their edges and there is nothing to fill ([Joint],
     * session 79). Where the ball **stands still** the corner is the ball's own surface, and there are
     * exactly two such places: it pivots about a concave upright, sweeping the band's section round it
     * ([Turn], GitHub #31), or it sits in a convex vertex touching all three faces at once, and the corner
     * is the patch its surface makes between the three band ends ([Vertex], GitHub #32).
     */
    private sealed interface Corner {
        /** Which band ends this corner closes — `(piece, atStart)`. */
        val ends: List<Pair<Int, Boolean>>

        /**
         * The pieces this corner is a **function of** without closing their ends — the **upright** the pair
         * pivots about, where that upright is itself a band (session 81, the mixed vertex).
         *
         * It is deliberately *not* in [ends]: the upright fills the opposite sector, so it is a boolean of
         * the other sign and can never share a tool with the pair ([groupsOf] joins only what [ends] names).
         * What it does name is the corner's **identity** — a corner about a rounded upright is a different
         * corner from the one about the sharp edge, so it carries the upright's index in its face name
         * ([cornerFacesOf]) and it is listed at the level where the *upright* is fresh, not only where an end
         * is — and its **order**: the upright's own tool has to be applied before the pair's, which is what
         * [blended] reads it for.
         */
        val extra: List<Int> get() = emptyList()

        /** The ring that end stands on. */
        fun ringAt(end: Pair<Int, Boolean>): Placement

        /** The surface between the ends — nothing for a crossing, the patch for the other two. */
        fun emit(
            pieces: List<Piece>,
            out: Geom3.MeshBuilder,
        )

        /** What a refusal calls this corner. */
        fun label(pieces: List<Piece>): String

        /**
         * The **faces** this corner adds to the dressed list, in the section's own piece order — empty
         * where it adds none.
         *
         * A crossing adds nothing: the two bands are trimmed against each other and every triangle still
         * belongs to one of them. A bevelled vertex adds nothing either, for the same reason one level up —
         * its three triangles lie exactly in the three bevel planes the bands already are. What *is* a new
         * surface is the ball: the spherical triangle at a convex vertex, and the horn torus (or cone) a
         * pivot sweeps at an inside corner — and a pivot of a **drawn** section sweeps one such surface per
         * piece of it, which is why this is a list (GitHub #30).
         */
        fun faces(
            pieces: List<Piece>,
            nameAt: (Int) -> FaceName,
        ): List<FacePatch> = emptyList()
    }

    /**
     * A **concave corner**: the ball pivots about the upright and the band's own section turns with it
     * (GitHub #31).
     *
     * *Why a turn and not a plane.* At a **convex** corner the two bands overlap and the removal splits on
     * the surface equidistant from the two edges. At a **concave** one they do not overlap at all: each
     * stops on the plane square to its own edge and the shared face's sharp corner stands between the two
     * ends — the reporter's *"spike"*. What belongs there is what the rolling ball does: having reached the
     * end of its own edge it **pivots about the upright**, its centre turning on a circle of radius `r`
     * about that line while it stays tangent to the shared face. So the corner is the band's own section
     * carried round that axis through the corner's exterior angle, and at the two ends of the turn it *is*
     * the two bands' own end sections — which is why nothing has to be matched: the first ring is one band's
     * end and the last is the other's.
     *
     * The surface it adds is the horn torus the pivoting ball sweeps (a torus whose tube and centre circle
     * are both `r`, so its hole closes to the point where the ball touches the upright), and for a chamfer
     * the cone the bevel sweeps. Both are exact statements of the section revolved, and the volume it takes
     * is Pappus' to the last digit: `φ · ∫ δ(h)²/2 dh`, which is `φ·r³(5/6 − π/4)` for a round and
     * `φ·c³/6` for a bevel.
     *
     * **The pivot is about *whatever stands at the upright*, and that is the generalization** (session 81,
     * the user's report on top of GitHub #31/#32). Session 80's sentence — *"its centre on a circle of
     * radius `r`"* — assumed the upright is a **sharp** edge. Where the upright is itself a band the ball
     * pivots about that band: for a fillet upright of radius `r_U` its centre runs on a circle of radius
     * `r + r_U` about the upright's own axis and the surface is a **ring** torus, the horn torus being
     * exactly the case `r_U = 0`. Said once for every kind of upright: **the pair's section follows the
     * upright band's own end-section curve, piece by piece, turning about the vertical through each joint
     * by the angle that curve's tangent turns there** — so a sharp upright is the degenerate curve of one
     * point and one turn, a chamfer upright is a turn, a slide and a turn, and a drawn one is its own chain.
     * Each of those is one [Leg], and the two bands' ends move from the corner itself to the **set-back**
     * where the upright's tangency on each of their other faces meets their own edge.
     */
    private class Turn(
        val a: Int,
        val aAtStart: Boolean,
        val placeA: Placement,
        val b: Int,
        val bAtStart: Boolean,
        val placeB: Placement,
        val shared: FacePatch,
        /** The walk from [a]'s own end round to [b]'s — one leg per piece of what stands at the upright. */
        val legs: List<Leg>,
        val at: Vec3,
        override val extra: List<Int> = emptyList(),
    ) : Corner {
        /** Every ring of the walk in order, a join between two legs counted once; the first is [placeA]. */
        val rings: List<Placement> =
            ArrayList<Placement>().also { out ->
                for (leg in legs) for ((k, p) in leg.rings.withIndex()) if (out.isEmpty() || k > 0) out.add(p)
            }

        override val ends: List<Pair<Int, Boolean>> get() = listOf(a to aAtStart, b to bAtStart)

        override fun ringAt(end: Pair<Int, Boolean>): Placement = if (end.first == a && end.second == aAtStart) placeA else placeB

        override fun emit(
            pieces: List<Piece>,
            out: Geom3.MeshBuilder,
        ) {
            val section = pieces[a].grown
            for (l in 0 until rings.size - 1) {
                val lo = section.map { rings[l].at(it) }
                val hi = section.map { rings[l + 1].at(it) }
                for (m in section.indices) {
                    val n = (m + 1) % section.size
                    // the turn continues [a]'s own tube, so the two rings take the same roles its two did
                    if (aAtStart) {
                        out.triangle(hi[m], hi[n], lo[n])
                        out.triangle(hi[m], lo[n], lo[m])
                    } else {
                        out.triangle(lo[m], lo[n], hi[n])
                        out.triangle(lo[m], hi[n], hi[m])
                    }
                }
            }
        }

        override fun label(pieces: List<Piece>): String =
            "the inside corner where ${pieces[a].crease.edge.name.label} meets ${pieces[b].crease.edge.name.label} " +
                "on ${shared.name.label}" +
                (if (extra.isEmpty()) "" else ", turned about ${pieces[extra.first()].crease.edge.name.label}")

        /**
         * Where this corner **ends the upright's own band**, as that band's own section placed on its edge —
         * and null where it turns about a sharp one.
         *
         * The pair's section touches the upright's surface at one point of itself, and that point travels
         * along the whole walk at one depth below the shared face: the depth of the pair's own tangency
         * there. So the upright's band stops on the plane at that depth, and above it the corner's own
         * surface stands. This is [spanOf]'s business and not [toolMesh]'s — the upright's *tool* still runs
         * the whole edge, and the corner's tool is what takes its top off (session 81).
         */
        fun uprightEnd(pieces: List<Piece>): Pair<Pair<Int, Boolean>, Placement>? {
            val u = extra.firstOrNull() ?: return null
            val up = pieces[u]
            val seg = up.seg ?: return null
            val n = shared.plane?.normal?.normalized() ?: return null
            val atStart = (seg.start - at).length() <= RING_TOL
            if (!atStart && (seg.end - at).length() > RING_TOL) return null
            val along = ((if (atStart) seg.end else seg.start) - at).normalized()
            val pair = pieces[a]
            val other = otherFace(pair, shared) ?: return null
            val t = if (other.name == pair.crease.face1.name) pair.wedge.t1 else pair.wedge.t2
            val depth = -(pair.crease.e1 * t.x + pair.crease.ref.e2 * t.y).dot(n)
            if (depth <= Geom3.WELD_TOL) return null
            return (u to atStart) to Placement(at + along * depth, up.crease.e1, up.crease.ref.e2)
        }

        /**
         * How this corner's faces are laid out: **leg by leg, one per piece of the pair's own section**.
         *
         * One [Leg] is one surface family — a revolution about its pivot, or the section carried straight
         * along a run — so the pivot about a sharp upright still states exactly one face per section piece
         * (its single leg) and a pivot about a bevelled one states three.
         */
        fun facePlan(pieces: List<Piece>): List<Pair<Leg, ProfileElement?>> {
            val sections = orientedSections(pieces[a])
            return legs.flatMap { leg -> sections.map { leg to it } }
        }

        /**
         * The pivot's own surface, as the **revolution or the sweep it is**: the band's section turned about
         * the axis square to the shared face — which [Revolve3] then names, a torus where the section is an
         * arc and a cone where it is a bevel — or carried straight along the upright bevel's own run, which
         * is the very sweep a band along a straight edge is ([Section3.sweptFace]).
         */
        override fun faces(
            pieces: List<Piece>,
            nameAt: (Int) -> FaceName,
        ): List<FacePatch> = facePlan(pieces).mapIndexed { k, (leg, sr) -> legPatch(pieces[a], leg, sr, nameAt(k)) }

        private fun legPatch(
            piece: Piece,
            leg: Leg,
            sr: ProfileElement?,
            name: FaceName,
        ): FacePatch {
            if (sr == null) {
                return FacePatch(name, null, emptyList(), "${name.label} turns a piece of the profile this drawing has no revolved surface for")
            }
            if (leg.pivot != null) {
                val (frame, map) =
                    axisFrame(piece, leg)
                        ?: return FacePatch(name, null, emptyList(), "${name.label} has no axis to turn about")
                val mapped =
                    mappedSection(sr, map)
                        ?: return FacePatch(name, null, emptyList(), "${name.label} turns a piece of the profile this drawing has no revolved surface for")
                return inCornersWords(Revolve3.bandPatch(frame, mapped, name), name)
            }
            val from = leg.rings.first()
            val to = leg.rings.last()
            val v = to.origin - from.origin
            val len = v.length()
            if (len <= Geom3.WELD_TOL) return FacePatch(name, null, emptyList(), "${name.label} has no length")
            // the sweep runs along the section frame's own normal, so the run is stated from whichever end
            // it leaves — the same right-handed convention [bandCarrier] states a straight band with
            val u = from.cx.cross(from.cy).normalized()
            val base = if (v.dot(u) >= 0.0) from else to
            return inCornersWords(Section3.sweptFace(Plane3(base.origin, base.cx, base.cy), u, len, sr, name), name)
        }

        /**
         * One leg as an axis frame and the pair's section in that frame's own `(s, r)` — all [Revolve3] ever
         * needs, and null for a leg that slides rather than turns.
         *
         * The **radial offset** is the whole of session 81 in one number: the section's own origin stands
         * `rho` out from the pivot, so the surface is a *ring* torus where the sharp upright's was a horn
         * one, and `rho = 0` reproduces session 80 verbatim.
         */
        fun axisFrame(
            piece: Piece,
            leg: Leg,
        ): Pair<Revolve3.Frame, Affine>? {
            val pivot = leg.pivot ?: return null
            val n = shared.plane?.normal?.normalized() ?: return null
            val axis = n * -1.0
            val off = leg.rings.first().origin - pivot
            val rho = off.length()
            val p = if (rho <= Geom3.WELD_TOL) leg.dir else off * (1.0 / rho)
            val frame =
                Revolve3.Frame(
                    Vec2(1.0, 0.0),
                    Vec2(0.0, 1.0),
                    Vec2(0.0, 0.0),
                    pivot,
                    axis,
                    p,
                    axis.cross(p),
                    min(0.0, -leg.turn),
                    max(0.0, -leg.turn),
                    false,
                )
            val e1 = piece.crease.e1
            val e2 = piece.crease.ref.e2
            // the section's own `(x, y)` read as the frame's `(s, r)`: down the axis, out along the radius,
            // the whole section standing `rho` out from the axis it turns about
            return frame to Affine(e1.dot(axis), e1.dot(p), e2.dot(axis), e2.dot(p), 0.0, rho)
        }
    }

    /**
     * One **leg of the pivot's walk**: the pair's section turned about one point, or slid along one straight
     * run (session 81).
     *
     * A sharp upright is one leg — the whole exterior angle turned about the edge itself. A **fillet**
     * upright is one leg too, turned about that fillet's own axis with the section standing `r_U` out from
     * it. A **chamfer** upright is three: a turn about the first rail, the slide along the bevel, a turn
     * about the second. A **drawn** one is its own chain read the same way.
     */
    private class Leg(
        /** The rings this leg puts down — the first at its start, the last at its end. */
        val rings: List<Placement>,
        /** The point it turns about, in the shared face's own plane; null where it slides instead. */
        val pivot: Vec3?,
        /** How far it turns about [pivot], signed about the shared face's normal; zero for a slide. */
        val turn: Double,
        /** The in-face direction the pair's section reaches along at this leg's start — the frame's `θ = 0`. */
        val dir: Vec3,
    )

    /**
     * One already-oriented section piece carried through an affine [map], with *material to its left*
     * re-established afterwards.
     *
     * The map may reverse orientation — the axis-aligned ones here are reflections as often as not — and
     * material-left is the convention [Revolve3.bandOf] reads a flat band's outward side from, so it is
     * restated after the map rather than assumed to survive it.
     */
    private fun mappedSection(
        oriented: ProfileElement,
        map: Affine,
    ): ProfileElement? {
        val (_, dir) = midOf(oriented) ?: return null
        // material is to the left of an oriented piece, so its outward normal is to the right of travel
        val outward = dir.perp() * -1.0
        return materialLeft(GeomMath.transform(oriented, map), map.linear(outward).normalized())
    }

    /**
     * A **convex vertex**: three bands meet, the ball touches all three faces at once, and the corner is the
     * patch its own surface makes between the three band ends (GitHub #32).
     *
     * *Why the ball reaches further than the three bands do.* Each band keeps the material inside its own
     * cylinder, so three of them keep the intersection of three cylinders — and that intersection has a
     * **point** sticking out toward the vertex which no ball of radius `r` can touch. On a box corner it
     * stands at `(1−1/√2)r` from the vertex along the diagonal, and it is exactly the *"sharp edges, not a
     * round surface"* the reporter saw. The ball's own surface cuts it off: the three bands end on the
     * plane square to each edge through the ball's centre — where each band's own section circle **is** a
     * great circle of that ball — and the spherical triangle between the three end arcs closes the tool.
     *
     * A **chamfer**'s three bevel planes already meet in a point of their own, so there is nothing extra to
     * take: its patch is the three bevel triangles running to that apex, which is where the three planes
     * cross. Both patches are stated the same way — three quads on the three faces, and a fill bounded by
     * the three band ends — and both are exact.
     */
    private class Vertex(
        val members: List<Triple<Int, Boolean, Placement>>,
        val patch: List<Triple<Vec3, Vec3, Vec3>>,
        val at: Vec3,
        val faces: List<FacePatch>,
        /** Where the ball sits and how big it is, for a round — null for a bevel, whose patch is three planes. */
        val ball: Pair<Vec3, Double>?,
    ) : Corner {
        override val ends: List<Pair<Int, Boolean>> get() = members.map { it.first to it.second }

        override fun ringAt(end: Pair<Int, Boolean>): Placement =
            members.first { it.first == end.first && it.second == end.second }.third

        override fun emit(
            pieces: List<Piece>,
            out: Geom3.MeshBuilder,
        ) {
            for ((x, y, z) in patch) out.triangle(x, y, z)
        }

        override fun label(pieces: List<Piece>): String =
            "the vertex where ${members.joinToString(", ") { pieces[it.first].crease.edge.name.label }} meet"

        /**
         * The ball's own surface, stated as the sphere it is. A **bevelled** vertex states none: its three
         * triangles lie exactly in the three bevel planes, which are the bands' own faces, so there is no
         * new surface there to name.
         */
        override fun faces(
            pieces: List<Piece>,
            nameAt: (Int) -> FaceName,
        ): List<FacePatch> = listOfNotNull(ballFace(nameAt(0)))

        private fun ballFace(name: FaceName): FacePatch? {
            val (centre, radius) = ball ?: return null
            // a sphere reads the same from every axis, so the frame takes the one the corner itself names —
            // from the vertex toward the ball — which makes the surface a function of the corner alone
            val axis = (centre - at).let { if (it.length() <= Geom3.WELD_TOL) return null else it.normalized() }
            val ref = Frames3.startReference(axis, Vec3(0.0, 0.0, 1.0))
            val frame =
                Revolve3.Frame(Vec2(1.0, 0.0), Vec2(0.0, 1.0), Vec2(0.0, 0.0), centre, axis, ref, axis.cross(ref), 0.0, 2.0 * PI, true)
            return inCornersWords(
                FacePatch(name, null, emptyList(), null, frame.surfaceOf(Revolve3.Band.Sphere(0.0, radius))),
                name,
            )
        }
    }

    /**
     * A corner patch's refusal **in the rounding's own words** — the same rule a band's is restated under
     * (session 65): the surface is the rounding's, nobody drew it, so it is not spoken of as a profile edge.
     */
    private fun inCornersWords(
        patch: FacePatch,
        name: FaceName,
    ): FacePatch {
        // a leg that **slides** a bevel along a bevel states a real plane, and a plane is a face you can
        // sketch on — so the restatement is for the surfaces that are not one, which is every corner patch
        // this drawing had before session 81 (a ball states its surface and leaves the sentence to here)
        if (patch.plane != null) return patch.copy(name = name)
        return patch.copy(
            name = name,
            reason =
                "${name.label} is ${patch.surface?.band?.label ?: "a surface this drawing has no name for"} and not " +
                    "a plane — it is where the rounding's own ball stands, so there is nothing to sketch on there; " +
                    "put a datum plane where you want to sketch",
        )
    }

    /** Where the three planes `n·x = d` cross, or null when they have no single crossing. */
    private fun meetOfPlanes(planes: List<Pair<Vec3, Double>>): Vec3? {
        val (n1, d1) = planes[0]
        val (n2, d2) = planes[1]
        val (n3, d3) = planes[2]
        val det = n1.dot(n2.cross(n3))
        if (abs(det) <= 1e-9) return null
        return (n2.cross(n3) * d1 + n3.cross(n1) * d2 + n1.cross(n2) * d3) * (1.0 / det)
    }

    /** How far along `p + u·s` and `q + v·t` the two lines cross, or null when they run parallel. */
    private fun crossingOf(
        p: Vec3,
        u: Vec3,
        q: Vec3,
        v: Vec3,
    ): Pair<Double, Double>? {
        val w = u.cross(v)
        val len2 = w.dot(w)
        if (len2 <= 1e-18) return null
        val r = q - p
        return (r.cross(v).dot(w) / len2) to (r.cross(u).dot(w) / len2)
    }

    /**
     * Where a section's tangency on [face] stands when the band's section is placed at the run's own
     * endpoint — the fixed point the vertex station is solved from ([vertexOf]).
     */
    private fun tangencyAt(
        piece: Piece,
        face: FacePatch,
        origin: Vec3,
    ): Vec3 {
        val t = if (face.name == piece.crease.face1.name) piece.wedge.t1 else piece.wedge.t2
        return origin + piece.crease.e1 * t.x + piece.crease.ref.e2 * t.y
    }

    /** The direction [piece]'s run takes **out of** the corner at that end. */
    private fun outOf(
        piece: Piece,
        atStart: Boolean,
    ): Vec3 {
        val seg = piece.seg!!
        return (if (atStart) seg.end - seg.start else seg.start - seg.end).normalized()
    }

    /**
     * The **three-band vertex** at [at], or null when these three bands do not make one.
     *
     * The stations are *solved* rather than assumed: each pair of bands shares a face, and on that face
     * their two tangency lines must meet at one point — the ball's own tangency there. Two lines crossing
     * gives each band's station, three pairs give each band two of them, and the three answers agreeing is
     * exactly the statement that a ball of this size sits in this corner. For a **fillet** they always do
     * (the ball is at distance `r` from all three faces and every tangency is its own foot); for a
     * **chamfer** they do when the three faces turn through the same angle at the vertex — a box corner,
     * and every prism whose plan turns a right angle — and where they do not, the pair is left as it was.
     */
    private fun vertexOf(
        pieces: List<Piece>,
        trio: List<Pair<Int, Boolean>>,
        at: Vec3,
    ): Vertex? {
        val faces = ArrayList<FacePatch>()
        for ((i, _) in trio) {
            for (f in listOf(pieces[i].crease.face1, pieces[i].crease.face2)) {
                if (f.plane == null) return null
                if (faces.none { it.name == f.name }) faces.add(f)
            }
        }
        if (faces.size != 3) return null
        // each face must be shared by exactly two of the three bands, or this is not one vertex
        val onFace = faces.map { f -> trio.indices.filter { k -> pieces[trio[k].first].crease.let { c -> c.face1.name == f.name || c.face2.name == f.name } } }
        if (onFace.any { it.size != 2 }) return null

        val dirs = trio.map { outOf(pieces[it.first], it.second) }
        val station = DoubleArray(trio.size) { Double.NaN }
        for ((k, f) in faces.withIndex()) {
            val (p, q) = onFace[k]
            val cross =
                crossingOf(
                    tangencyAt(pieces[trio[p].first], f, at),
                    dirs[p],
                    tangencyAt(pieces[trio[q].first], f, at),
                    dirs[q],
                ) ?: return null
            for ((who, s) in listOf(p to cross.first, q to cross.second)) {
                if (s <= Geom3.WELD_TOL || s >= pieces[trio[who].first].length) return null
                if (station[who].isNaN()) {
                    station[who] = s
                } else if (abs(station[who] - s) > RING_TOL) {
                    return null
                }
            }
        }

        val members =
            trio.indices.map { k ->
                val piece = pieces[trio[k].first]
                Triple(
                    trio[k].first,
                    trio[k].second,
                    Placement(at + dirs[k] * station[k], piece.crease.e1, piece.crease.ref.e2),
                )
            }
        val (patch, ball) = vertexPatch(pieces, trio, members, faces, onFace, at) ?: return null
        return Vertex(members, patch, at, faces, ball)
    }

    /** How far [p] stands off [plane], signed by its own normal. */
    private fun offPlane(
        plane: Plane3,
        p: Vec3,
    ): Double = (p - plane.origin).dot(plane.normal.normalized())

    /** [a] [b] [c] wound so that their normal points the way [want] does. */
    private fun facing(
        a: Vec3,
        b: Vec3,
        c: Vec3,
        want: Vec3,
    ): Triple<Vec3, Vec3, Vec3> = if ((b - a).cross(c - a).dot(want) >= 0.0) Triple(a, b, c) else Triple(a, c, b)

    /**
     * The vertex corner's own surface: one quad on each of the three faces, and the fill bounded by the
     * three band ends — the ball's spherical triangle for a round, the three bevels' own apex for a bevel.
     *
     * Each band's ring is read by position rather than by index arithmetic: its first point is the section's
     * corner, its next and last-but-one are the two tangencies, and everything between them is the blend
     * curve. Which tangency belongs to which face is asked of the faces themselves, so a ring that came out
     * the other way round reads the same.
     */
    private fun vertexPatch(
        pieces: List<Piece>,
        trio: List<Pair<Int, Boolean>>,
        members: List<Triple<Int, Boolean, Placement>>,
        faces: List<FacePatch>,
        onFace: List<List<Int>>,
        at: Vec3,
    ): Pair<List<Triple<Vec3, Vec3, Vec3>>, Pair<Vec3, Double>?>? {
        val rings = members.map { m -> pieces[m.first].grown.map { m.third.at(it) } }
        if (rings.any { it.size < 5 }) return null
        val tangency = arrayOfNulls<Vec3>(3)
        val grownTangency = arrayOfNulls<Vec3>(3)
        val blends = ArrayList<List<Vec3>>(3)
        for (ring in rings) {
            val n = ring.size
            for ((plain, grown) in listOf(ring[2] to ring[1], ring[n - 2] to ring[n - 1])) {
                val k = faces.indices.firstOrNull { abs(offPlane(faces[it].plane!!, plain)) <= RING_TOL } ?: return null
                if (abs(offPlane(faces[k].plane!!, grown) - GROW_MM) > RING_TOL) return null
                val known = tangency[k]
                if (known != null && (known - plain).length() > RING_TOL) return null
                tangency[k] = plain
                grownTangency[k] = grown
            }
            blends.add(ring.subList(2, n - 1))
        }
        if (tangency.any { it == null }) return null

        val out = ArrayList<Triple<Vec3, Vec3, Vec3>>()
        // **the three flat quads**, one per face: the vertex, the two bands' own section corners, and the
        // ball's tangency between them — all four a micron outside that face, where the tool's flat side is
        val grownVertex =
            meetOfPlanes(
                faces.map { f ->
                    val n = f.plane!!.normal.normalized()
                    n to (f.plane.origin.dot(n) + GROW_MM)
                },
            ) ?: return null
        for (k in faces.indices) {
            val want = faces[k].plane!!.normal.normalized()
            val cp = rings[onFace[k][0]][0]
            val cq = rings[onFace[k][1]][0]
            val tk = grownTangency[k]!!
            out.add(facing(grownVertex, cp, tk, want))
            out.add(facing(grownVertex, tk, cq, want))
        }

        // **the fill**, bounded by the three band ends chained into one loop
        val loop = chainOfBlends(blends) ?: return null
        val first = pieces[members[0].first]
        var ball: Pair<Vec3, Double>? = null
        // **the fill is the ball's own patch, or the three bevels' apex, and there is no third**. A drawn
        // section of *one* piece falls into whichever of the two it is — an arc gives the ball, a segment the
        // apex — so an asymmetric bevel and a single-arc profile both get their vertex. A section of
        // **several** pieces has neither: the three bands then meet along three mitre creases rather than at
        // one patch, and inventing a surface nobody constructed is the one thing this drawing does not do.
        // Such a trio is left as it was — two bands claim each other and the third butts, exactly as session
        // 80 leaves a chamfer vertex whose three faces turn through different angles (GitHub #30, a named cut).
        val fill =
            when (val piece = first.wedge.pieces.singleOrNull()) {
                is ProfileElement.ArcE -> {
                    val centre = members[0].third.at(piece.arc.center)
                    for (m in members.drop(1)) {
                        val other = (pieces[m.first].wedge.pieces.singleOrNull() as? ProfileElement.ArcE) ?: return null
                        if ((m.third.at(other.arc.center) - centre).length() > RING_TOL) return null
                    }
                    ball = centre to piece.arc.radius
                    spherePatch(centre, piece.arc.radius, loop)
                }
                is ProfileElement.Seg -> apexPatch(pieces, members, loop)
                else -> null
            } ?: return null
        for ((a, b, c) in fill) out.add(facing(a, b, c, (a + b + c) * (1.0 / 3.0) - at))
        return out to ball
    }

    /** The three band ends chained end to end into one closed loop of points. */
    private fun chainOfBlends(blends: List<List<Vec3>>): List<Vec3>? {
        val used = BooleanArray(blends.size)
        val loop = ArrayList<Vec3>(blends.sumOf { it.size })
        loop.addAll(blends[0])
        used[0] = true
        repeat(blends.size - 1) {
            val tail = loop.last()
            var found = false
            for (i in blends.indices) {
                if (used[i]) continue
                val run =
                    when {
                        (blends[i].first() - tail).length() <= RING_TOL -> blends[i]
                        (blends[i].last() - tail).length() <= RING_TOL -> blends[i].reversed()
                        else -> continue
                    }
                loop.addAll(run.drop(1))
                used[i] = true
                found = true
                break
            }
            if (!found) return null
        }
        if ((loop.first() - loop.last()).length() > RING_TOL) return null
        loop.removeAt(loop.size - 1)
        return loop
    }

    /**
     * The **spherical triangle** bounded by [loop], on the ball of [radius] about [centre] — the rolling
     * ball's own surface where it stands still.
     *
     * A polar mesh rather than a fan: rings walk out from the patch's own middle to [loop] along great
     * circles, so the last ring **is** the boundary, point for point, and every step's sag is the one
     * [GeomMath.chordSteps] gives a curve of this radius (OP-15 — deterministic, never adaptive).
     */
    private fun spherePatch(
        centre: Vec3,
        radius: Double,
        loop: List<Vec3>,
    ): List<Triple<Vec3, Vec3, Vec3>>? {
        if (radius <= Geom3.WELD_TOL) return null
        val dirs = loop.map { (it - centre) * (1.0 / radius) }
        var sum = Vec3(0.0, 0.0, 0.0)
        for (d in dirs) sum = sum + d
        if (sum.length() <= Geom3.WELD_TOL) return null
        val mid = sum.normalized()
        val reach = dirs.maxOf { acos(mid.dot(it).coerceIn(-1.0, 1.0)) }
        if (reach <= 1e-9) return null
        val steps = GeomMath.chordSteps(radius, reach, GeomMath.TESS_TOL_MM)

        fun slerp(
            to: Vec3,
            t: Double,
        ): Vec3 {
            val omega = acos(mid.dot(to).coerceIn(-1.0, 1.0))
            if (omega <= 1e-12) return to
            return (mid * sin((1.0 - t) * omega) + to * sin(t * omega)) * (1.0 / sin(omega))
        }
        val out = ArrayList<Triple<Vec3, Vec3, Vec3>>()
        val apex = centre + mid * radius
        var previous: List<Vec3>? = null
        for (l in 1..steps) {
            val ring = if (l == steps) loop else dirs.map { centre + slerp(it, l.toDouble() / steps) * radius }
            val below = previous
            if (below == null) {
                for (i in ring.indices) out.add(Triple(apex, ring[i], ring[(i + 1) % ring.size]))
            } else {
                for (i in ring.indices) {
                    val j = (i + 1) % ring.size
                    out.add(Triple(below[i], ring[i], ring[j]))
                    out.add(Triple(below[i], ring[j], below[j]))
                }
            }
            previous = ring
        }
        return out
    }

    /**
     * The **three bevels' own apex** — a chamfer's vertex, where the three cutting planes cross.
     *
     * There is nothing extra to take at a bevelled vertex and that is the whole of it: three planes already
     * meet in a point, so the patch is the three triangles running from [loop]'s three ends to that point,
     * each one lying exactly in its own band's plane. Exact, and it is the same sentence the two-band mitre
     * says one dimension down.
     */
    private fun apexPatch(
        pieces: List<Piece>,
        members: List<Triple<Int, Boolean, Placement>>,
        loop: List<Vec3>,
    ): List<Triple<Vec3, Vec3, Vec3>>? {
        val planes = ArrayList<Pair<Vec3, Double>>(members.size)
        for (m in members) {
            val piece = pieces[m.first]
            val bevel = (piece.wedge.pieces.singleOrNull() as? ProfileElement.Seg) ?: return null
            val from = m.third.at(bevel.segment.a)
            val to = m.third.at(bevel.segment.b)
            val along = outOf(piece, m.second)
            val n = (to - from).cross(along)
            if (n.length() <= Geom3.WELD_TOL) return null
            val unit = n.normalized()
            planes.add(unit to unit.dot(from))
        }
        val apex = meetOfPlanes(planes) ?: return null
        return loop.indices.map { Triple(apex, loop[it], loop[(it + 1) % loop.size]) }
    }

    /** Whether two rings are the **same** ring — the same points, however each side happens to order them. */
    private fun ringsAgree(
        a: List<Vec3>,
        b: List<Vec3>,
    ): Boolean {
        if (a.size != b.size) return false
        val used = BooleanArray(b.size)
        for (p in a) {
            var hit = -1
            for (j in b.indices) {
                if (!used[j] && (b[j] - p).length() <= RING_TOL) {
                    hit = j
                    break
                }
            }
            if (hit < 0) return false
            used[hit] = true
        }
        return true
    }

    /** The in-face direction the wedge reaches along on [shared] — the way its tangency there lies. */
    private fun inFaceOf(
        piece: Piece,
        shared: FacePatch,
    ): Vec3? {
        val t = if (shared.name == piece.crease.face1.name) piece.wedge.t1 else piece.wedge.t2
        val w = piece.crease.e1 * t.x + piece.crease.ref.e2 * t.y
        return if (w.length() <= Geom3.WELD_TOL) null else w.normalized()
    }

    /**
     * The mitre ring one piece puts at [corner], as the affine placement of its own section.
     *
     * **The whole corner, in one sentence.** The wedge's section is carried along the edge, and where two
     * such sweeps meet, the removal on each side of the surface *equidistant from the two edges* is that
     * side's own sweep — because the wedge, at any depth below the shared face, is everything from the
     * crease out to the rolling curve, so a point nearer its own edge than the neighbour's is inside the
     * neighbour's wedge as well. Splitting there therefore loses nothing and takes nothing extra, and for
     * two **straight** edges that surface is a plane: the one through the corner along the in-face bisector,
     * square to the shared face.
     *
     * So a section point standing `s` in from its own edge and `h` below the shared face lands, at the
     * corner, on the point standing `s` in from *both* edges at that depth — `corner + bisector·s/sin(θ/2)`
     * dropped `h` — which is affine in the section's own coordinates and therefore a [Placement]: the map
     * sends the in-face axis to `bisector/sin(θ/2)` and the face's normal to itself. Both sides compute it
     * from their own frame and land on the same points, which is what [ringsAgree] checks and what lets the
     * two tubes stitch into one watertight tool with no boolean between them.
     */
    private fun mitrePlacement(
        piece: Piece,
        shared: FacePatch,
        corner: Vec3,
        bis: Vec3,
        c: Double,
    ): Placement? {
        val n = shared.plane?.normal?.normalized() ?: return null
        val e = inFaceOf(piece, shared) ?: return null

        fun map(axis: Vec3): Vec3 = bis * (axis.dot(e) / c) + n * axis.dot(n)
        return Placement(corner, map(piece.crease.e1), map(piece.crease.ref.e2))
    }

    /** Where [p] stands along [piece]'s own run, as a length from its start. */
    private fun stationOf(
        piece: Piece,
        p: Vec3,
    ): Double {
        val seg = piece.seg ?: return 0.0
        val v = seg.end - seg.start
        return (p - seg.start).dot(v) / v.length()
    }

    /**
     * The **mitre corners** among [pieces] — one per shared vertex that can carry one.
     *
     * What a corner must be to be built rather than found, each condition with its reason:
     *
     * - **Two straight edges.** The splitting surface is equidistant from both edges, which is a *plane*
     *   only when both are straight; a corner where a circular edge turns into another is a curved medial
     *   surface, and that is a future extension rather than something to approximate. Such a pair is left
     *   to overlap and be trimmed by the boolean, as every pair was before this session.
     * - **One shared, flat face.** The corner is stated in that face's own frame — in from the edge, down
     *   from the face — so the face has to have a plane to be measured from.
     * - **The same sector.** Two bands filling opposite sectors are not one corner: one is subtracted and
     *   the other added, so they are two operations and stay two.
     * - **A sharp turn.** Where the boundary runs on smoothly there is no corner to build: the two sections
     *   abut on the very same plane already and their union has no crack in it (the rounded rim, exact
     *   before this session and untouched by it).
     * - **Rings that agree.** Both sides must land on the same points, which is the same thing as the two
     *   wedges being congruent in that face's frame — one size, one kind, one dihedral. Where the two edges'
     *   faces stand at different angles the corner is a surface this rounding cannot state exactly, and the
     *   pair is left to overlap as it did.
     * - **Only two at a vertex.** A ring is shared by two tubes; a vertex where three or more blended edges
     *   meet is the vertex blend that is already on record as a future extension.
     */
    private fun cornersOf(pieces: List<Piece>): Corners {
        val out = ArrayList<Corner>()
        var refusal: String? = null
        val taken = HashSet<Pair<Int, Boolean>>()
        // **vertices first, and that order is the rule.** A ring is shared by two tubes, so three bands at
        // one point cannot be three crossings; taking the vertex first is what stops two of them claiming
        // each other and leaving the third to butt — which is exactly the crease GitHub #32 reported.
        for (i in pieces.indices) {
            for (j in i + 1 until pieces.size) {
                for (k in j + 1 until pieces.size) {
                    val three = listOf(i, j, k)
                    if (three.any { pieces[it].seg == null || !pieces[it].choice.convex }) continue
                    val (trio, at) = endsMeeting(pieces, three, taken) ?: continue
                    val vertex = vertexOf(pieces, trio, at) ?: continue
                    out.add(vertex)
                    taken.addAll(vertex.ends)
                }
            }
        }
        for (i in pieces.indices) {
            for (j in i + 1 until pieces.size) {
                val a = pieces[i]
                val b = pieces[j]
                val sa = a.seg ?: continue
                val sb = b.seg ?: continue
                if (a.choice.convex != b.choice.convex) continue
                val shared =
                    listOf(a.crease.face1, a.crease.face2)
                        .firstOrNull { f -> f.plane != null && (f.name == b.crease.face1.name || f.name == b.crease.face2.name) }
                        ?: continue
                for (aAtStart in listOf(true, false)) {
                    for (bAtStart in listOf(true, false)) {
                        if ((i to aAtStart) in taken || (j to bAtStart) in taken) continue
                        val corner = if (aAtStart) sa.start else sa.end
                        if ((corner - (if (bAtStart) sb.start else sb.end)).length() > RING_TOL) continue
                        val ea = inFaceOf(a, shared) ?: continue
                        val eb = inFaceOf(b, shared) ?: continue
                        // a smooth hand-over is not a corner: the two sections already abut on one plane
                        if (ea.dot(eb) >= 1.0 - TANGENT_TOL) continue
                        val sum = ea + eb
                        if (sum.length() <= Geom3.WELD_TOL) continue
                        val bis = sum.normalized()
                        val c = ea.dot(bis)
                        if (c <= Geom3.WELD_TOL) continue
                        val placeA = mitrePlacement(a, shared, corner, bis, c) ?: continue
                        val placeB = mitrePlacement(b, shared, corner, bis, c) ?: continue
                        if (!ringsAgree(a.grown.map { placeA.at(it) }, b.grown.map { placeB.at(it) })) continue
                        val made =
                            if (turnsInward(a, aAtStart, bis) && turnsInward(b, bAtStart, bis)) {
                                Joint(i, aAtStart, placeA, j, bAtStart, placeB, shared)
                            } else {
                                // **the corner turns the other way**: an inside corner of the shared face,
                                // where the two bands do not overlap at all. The ball pivots about whatever
                                // stands at the upright and its section turns with it ([Turn], GitHub #31 and
                                // session 81's generalization to an upright that is itself a band).
                                val (turn, why) = turnOf(pieces, i, aAtStart, j, bAtStart, shared, corner, ea, eb)
                                if (why != null && refusal == null) refusal = why
                                turn ?: continue
                            }
                        out.add(made)
                        taken.add(i to aAtStart)
                        taken.add(j to bAtStart)
                    }
                }
            }
        }
        return Corners(out, refusal)
    }

    /**
     * The corners among a tool's pieces, and the **one refusal** a corner that cannot be stated carries.
     *
     * A pair the drawing simply does not make a corner of is no refusal at all — it is left to overlap and be
     * trimmed, as every pair was before session 79. What *is* a refusal is a corner the drawing **has** and
     * cannot state: an inside corner whose upright is itself rounded and whose pivot this vocabulary cannot
     * follow (session 81). Silently building the sharp-upright corner there would sweep a band over material
     * the upright's own rounding took away, which is the defect this holder exists to make impossible.
     */
    private class Corners(val list: List<Corner>, val refusal: String?)

    /** The ends of [which] that all stand at one point and are not spoken for, with that point. */
    private fun endsMeeting(
        pieces: List<Piece>,
        which: List<Int>,
        taken: Set<Pair<Int, Boolean>>,
    ): Pair<List<Pair<Int, Boolean>>, Vec3>? {
        fun endOf(
            i: Int,
            atStart: Boolean,
        ): Vec3 = pieces[i].seg!!.let { if (atStart) it.start else it.end }
        for (e0 in listOf(true, false)) {
            for (e1 in listOf(true, false)) {
                for (e2 in listOf(true, false)) {
                    val ends = listOf(which[0] to e0, which[1] to e1, which[2] to e2)
                    if (ends.any { it in taken }) continue
                    val at = endOf(which[0], e0)
                    if (ends.all { (endOf(it.first, it.second) - at).length() <= RING_TOL }) return ends to at
                }
            }
        }
        return null
    }

    /**
     * The **turn** between two bands at an inside corner: the corner, or a reason it cannot be built, or
     * neither where this pair simply does not make one.
     *
     * The rings are the pair's section carried along **whatever stands at the upright**, in the shared
     * face's own plane: at zero turn the map is the identity, so the first ring **is** the first band's own
     * end section, and at the end of the walk it must be the second band's — which is the same congruence
     * the crossing asks for, put to [ringsAgree]. Every turn is cut into steps by the same sag rule every
     * arc in this drawing gets.
     *
     * Where the upright is a **sharp** edge that is one leg turned about the corner itself, which is session
     * 80 verbatim. Where the upright is itself a band ([uprightAt]) the walk follows that band's own
     * end-section curve — the set-back moves the two bands' ends off the corner and the pivot moves onto the
     * upright's own axis — and where that walk cannot be stated the pair is **refused by name** rather than
     * left to build the sharp corner over material that is no longer there (session 81; the user's report).
     */
    private fun turnOf(
        pieces: List<Piece>,
        i: Int,
        aAtStart: Boolean,
        j: Int,
        bAtStart: Boolean,
        shared: FacePatch,
        at: Vec3,
        ea: Vec3,
        eb: Vec3,
    ): Pair<Turn?, String?> {
        val a = pieces[i]
        val b = pieces[j]
        val n = shared.plane?.normal?.normalized() ?: return null to null
        val perp = n.cross(ea)
        if (abs(perp.length() - 1.0) > 1e-6) return null to null
        val total = atan2(eb.dot(perp), eb.dot(ea).coerceIn(-1.0, 1.0))
        if (abs(total) <= TANGENT_TOL) return null to null
        if (a.grown.maxOf { it.length() } <= Geom3.WELD_TOL) return null to null
        val u = uprightAt(pieces, i, j, shared, at)
        if (u == null) {
            // **the sharp upright**: one leg, the pivot standing on the edge itself (session 80, unchanged)
            val placeA = Placement(at, a.crease.e1, a.crease.ref.e2)
            val placeB = Placement(at, b.crease.e1, b.crease.ref.e2)
            val leg = turnLeg(a, placeA, at, n, ea, total)
            val turn = Turn(i, aAtStart, placeA, j, bAtStart, placeB, shared, listOf(leg), at)
            if (!ringsAgree(a.grown.map { turn.rings.last().at(it) }, b.grown.map { placeB.at(it) })) return null to null
            return turn to null
        }
        val (legs, why) = uprightLegs(pieces, i, aAtStart, j, u, shared, at, ea, eb, total, n)
        if (legs == null) return null to why
        val placeA = legs.first().rings.first()
        val placeB = Placement(legs.last().rings.last().origin, b.crease.e1, b.crease.ref.e2)
        val turn = Turn(i, aAtStart, placeA, j, bAtStart, placeB, shared, legs, at, listOf(u))
        if (!ringsAgree(a.grown.map { turn.rings.last().at(it) }, b.grown.map { placeB.at(it) })) {
            return null to mismatchedTurn(pieces, i, j, u, shared)
        }
        return turn to null
    }

    /**
     * The **upright** two bands pivot about at [at], as an index into [pieces], or null where the upright is
     * a sharp edge (or a band this pair cannot pivot about).
     *
     * Three conditions and each is structural, never measured: the upright fills the **opposite** sector
     * (a concave fill between two convex bands, or a convex band between two fills), one of its ends stands
     * at the corner, and its two faces are exactly the pair's two *other* faces — which is the statement
     * that it is the edge those two faces cross at.
     */
    private fun uprightAt(
        pieces: List<Piece>,
        i: Int,
        j: Int,
        shared: FacePatch,
        at: Vec3,
    ): Int? {
        val fa = otherFace(pieces[i], shared) ?: return null
        val fb = otherFace(pieces[j], shared) ?: return null
        val want = setOf(fa.name, fb.name)
        for (k in pieces.indices) {
            if (k == i || k == j) continue
            val piece = pieces[k]
            if (piece.choice.convex == pieces[i].choice.convex) continue
            // whatever its carrier: a **ring** at a revolve's own inside corner is an upright too, and the
            // walk says so by name rather than this reading quietly leaving it out (which would build the
            // sharp-upright corner over material the ring's rounding took away)
            val ends = listOfNotNull(piece.crease.path.start, piece.crease.path.end)
            if (ends.none { (it - at).length() <= RING_TOL }) continue
            if (setOf(piece.crease.face1.name, piece.crease.face2.name) != want) continue
            return k
        }
        return null
    }

    /** The face of [piece]'s crease that is **not** [shared] — its other one. */
    private fun otherFace(
        piece: Piece,
        shared: FacePatch,
    ): FacePatch? =
        when (shared.name) {
            piece.crease.face1.name -> piece.crease.face2
            piece.crease.face2.name -> piece.crease.face1
            else -> null
        }

    /**
     * The walk from [i]'s end round to [j]'s along the upright band [u]'s own end-section curve, or the
     * reason it cannot be walked (session 81).
     *
     * *The sentence, once.* The upright's blend curve, read in the shared face's plane, is the path the
     * pair's section travels: at each point of it the section stands with its origin **on** the curve and
     * its in-face axis along the curve's own normal, so an **arc** of that curve revolves the section about
     * the arc's centre (a ring torus for a round pair, a cone-and-ring for a bevelled one), a **segment**
     * slides it, and a corner of the curve turns it in place. The set-back is not a separate rule: the
     * curve's first point *is* where the upright's tangency on the pair's other face meets its edge.
     */
    private fun uprightLegs(
        pieces: List<Piece>,
        i: Int,
        aAtStart: Boolean,
        j: Int,
        u: Int,
        shared: FacePatch,
        at: Vec3,
        ea: Vec3,
        eb: Vec3,
        total: Double,
        n: Vec3,
    ): Pair<List<Leg>?, String?> {
        val a = pieces[i]
        val up = pieces[u]
        val what =
            "the inside corner where ${a.crease.edge.name.label} meets ${pieces[j].crease.edge.name.label} " +
                "on ${shared.name.label}"
        val seg =
            up.seg ?: return null to
                "${up.crease.edge.name.label} is not one straight run, so $what cannot be turned about it — " +
                "the pair's section would have to follow a curve that moves along that edge, which is a " +
                "future extension. Leave that edge sharp, or round it on a body whose upright is straight"
        // **the upright has to stand square to the shared face**, or the curve the pair's section follows is
        // not a curve *in* that face at all and there is no pivot to state (OP-3: a refusal, not a guess)
        val along = (seg.end - seg.start).normalized()
        if (abs(abs(along.dot(n)) - 1.0) > 1e-6) {
            return null to
                "${up.crease.edge.name.label} is not square to ${shared.name.label}, so $what cannot be turned " +
                "about it — the roundings there are a future extension. Leave that upright sharp, or round " +
                "it on a body whose upright stands square to that face"
        }
        val aOther = otherFace(a, shared) ?: return null to null
        val forward = up.crease.face1.name == aOther.name
        val curve = if (forward) up.wedge.pieces else up.wedge.pieces.reversed().map { GeomMath.reverse(it) }
        if (curve.isEmpty()) return null to null
        val e1 = up.crease.e1
        val e2 = up.crease.ref.e2

        fun plan(q: Vec2): Vec3 = at + e1 * q.x + e2 * q.y

        fun world(d: Vec2): Vec3 = e1 * d.x + e2 * d.y
        val start = plan(GeomMath.startOf(curve.first()))
        // …and that first point must stand **on** the pair's own edge, running out of the corner: that is
        // what makes it the set-back rather than a point beside the edge
        val dirA = outOf(a, aAtStart)
        val off = start - at
        if ((off - dirA * off.dot(dirA)).length() > RING_TOL || off.dot(dirA) < -RING_TOL) {
            return null to
                "$what: ${up.crease.edge.name.label}'s rounding does not meet ${a.crease.edge.name.label} " +
                "along that edge, so there is no set-back for the corner to start from — a future extension"
        }
        val legs = ArrayList<Leg>()
        var place = Placement(start, a.crease.e1, a.crease.ref.e2)
        var dir = ea
        var acc = 0.0
        for (p in curve) {
            val here = plan(GeomMath.startOf(p))
            if ((place.origin - here).length() > RING_TOL) {
                return null to "$what: ${up.crease.edge.name.label}'s own profile is not one run, so the corner cannot follow it"
            }
            val t0 = tangentOf(p, true) ?: return null to profileTurnRefusal(what, up)
            val d0 = squareTo(world(t0), n, dir, total) ?: return null to profileTurnRefusal(what, up)
            val joint = signedTurn(dir, d0, n)
            if (abs(joint) > TANGENT_TOL) {
                val leg = turnLeg(a, place, here, n, dir, joint)
                legs.add(leg)
                place = leg.rings.last()
                dir = d0
                acc += joint
            }
            val to = plan(GeomMath.endOf(p))
            when (p) {
                is ProfileElement.Seg -> {
                    legs.add(Leg(listOf(place, Placement(to, place.cx, place.cy)), null, 0.0, dir))
                    place = legs.last().rings.last()
                }
                is ProfileElement.ArcE -> {
                    val centre = plan(p.arc.center)
                    val hand = if (n.dot(e1.cross(e2)) >= 0.0) 1.0 else -1.0
                    val sweep = GeomMath.sweep(p.arc) * hand
                    val leg = turnLeg(a, place, centre, n, dir, sweep, chordPath(a, p, p.arc.radius, ::plan))
                    legs.add(leg)
                    place = leg.rings.last()
                    // the in-face direction turns with the radial, by exactly the arc's own sweep
                    dir = (dir * cos(sweep) + n.cross(dir) * sin(sweep)).normalized()
                    acc += sweep
                }
                else -> return null to profileTurnRefusal(what, up)
            }
            if ((place.origin - to).length() > RING_TOL) return null to profileTurnRefusal(what, up)
        }
        val last = signedTurn(dir, eb, n)
        if (abs(last) > TANGENT_TOL) {
            val leg = turnLeg(a, place, place.origin, n, dir, last)
            legs.add(leg)
            acc += last
        }
        // the whole walk has to turn the corner and nothing more: a profile that doubles back would sweep
        // the section through itself, and inventing what that means is the one thing this drawing does not do
        if (abs(acc - total) > 1e-6) return null to profileTurnRefusal(what, up)
        return legs to null
    }

    /** Why a drawn upright's own profile cannot carry the corner, in the corner's own words. */
    private fun profileTurnRefusal(
        what: String,
        up: Piece,
    ): String =
        "$what turns about ${up.crease.edge.name.label}, whose own ${up.sec.kind.word} doubles back or leaves " +
            "its face square to it — the pair's section would sweep through itself there, so that corner is a " +
            "future extension. Give that upright a fillet, a chamfer, or a profile that runs one way across " +
            "the corner"

    /** Why the two roundings' end sections do not meet on one ring once the upright is rounded. */
    private fun mismatchedTurn(
        pieces: List<Piece>,
        i: Int,
        j: Int,
        u: Int,
        shared: FacePatch,
    ): String =
        "the inside corner where ${pieces[i].crease.edge.name.label} meets ${pieces[j].crease.edge.name.label} " +
            "on ${shared.name.label} is turned about ${pieces[u].crease.edge.name.label}, and the two roundings " +
            "do not end on one ring there — a corner is built only where the two are congruent in the face they " +
            "share. Give both edges the same rounding"

    /**
     * One leg that **turns**: [from] carried about [pivot] through [turn], stepped by the one sag rule.
     *
     * [path] is where the section's own origin stands at each step, and it is null exactly where that is the
     * ideal circle — a pivot about a *sharp* upright, or about a joint of a drawn one. Where the pivot is a
     * **band's own arc** it is that band's own chord polygon instead, and that is not a nicety: the tool has
     * to fit the mesh it cuts. The upright's band reaches the boolean as an inscribed polygon dipping a
     * chord's sag inside its own cylinder, so a tool face stepped a micron inside the *cylinder* still
     * stands a sag **outside** the polygon and leaves a sliver of the upright's own rounding behind. Running
     * the rings on the upright's own chords puts the tool's face a micron inside that polygon everywhere,
     * by construction rather than by luck, and it costs the corner patch nothing it did not already have —
     * its own surface is stated exactly ([Turn.axisFrame]) and only its triangles carry the chords, which is
     * OP-15's approximated class and what every band in this drawing already is.
     */
    private fun turnLeg(
        piece: Piece,
        from: Placement,
        pivot: Vec3,
        n: Vec3,
        dir: Vec3,
        turn: Double,
        path: List<Vec3>? = null,
    ): Leg {
        val rho = (from.origin - pivot).dot(dir)
        if (path != null) {
            val steps = path.size - 1
            val rings = path.indices.map { l -> turnedPlacement(from, pivot, n, dir, rho, turn * l / steps).let { Placement(path[l], it.cx, it.cy) } }
            return Leg(rings, pivot, turn, dir)
        }
        val reach = piece.grown.maxOf { it.length() } + abs(rho)
        val steps = max(1, GeomMath.chordSteps(max(reach, Geom3.WELD_TOL), abs(turn), GeomMath.TESS_TOL_MM))
        val rings = (0..steps).map { l -> turnedPlacement(from, pivot, n, dir, rho, turn * l / steps) }
        return Leg(rings, pivot, turn, dir)
    }

    /**
     * Where the section's origin stands at each step of a turn about an upright band's own **arc**: that
     * arc's own chord polygon, sub-divided until the pivot's own sag rule is met too.
     *
     * The polygon's own points are kept as steps — every one of them — because they are the very points the
     * upright's band puts on the body ([GeomMath.tessellatePiece], the same call [sectionPolygons] makes).
     */
    private fun chordPath(
        piece: Piece,
        arc: ProfileElement.ArcE,
        rho: Double,
        into: (Vec2) -> Vec3,
    ): List<Vec3> {
        val poly = GeomMath.tessellatePiece(arc, GeomMath.TESS_TOL_MM)
        if (poly.size < 2) return poly.map(into)
        val m = poly.size - 1
        val reach = piece.grown.maxOf { it.length() } + abs(rho)
        val mine = max(1, GeomMath.chordSteps(max(reach, Geom3.WELD_TOL), abs(GeomMath.sweep(arc.arc)), GeomMath.TESS_TOL_MM))
        val k = max(1, (mine + m - 1) / m)
        val out = ArrayList<Vec3>(m * k + 1)
        for (i in 0 until m) {
            for (l in 0 until k) out.add(into(poly[i] + (poly[i + 1] - poly[i]) * (l.toDouble() / k)))
        }
        out.add(into(poly[m]))
        return out
    }

    /**
     * [p] turned about the axis through [pivot] along [n] by [phi] — the placement's origin runs on the
     * circle of radius [rho] about that axis and its two axes turn with it.
     *
     * With `rho = 0` this is session 80's own arithmetic character for character, which is what keeps every
     * body that has a sharp-upright pivot in it bit-identical.
     */
    private fun turnedPlacement(
        p: Placement,
        pivot: Vec3,
        n: Vec3,
        e0: Vec3,
        rho: Double,
        phi: Double,
    ): Placement {
        val perp = n.cross(e0)
        val dir = e0 * cos(phi) + perp * sin(phi)
        return Placement(pivot + dir * rho, turnAxis(dir, n, e0, p.cx), turnAxis(dir, n, e0, p.cy))
    }

    /** The unit tangent of a section piece at one of its ends, in the section's own 2D frame. */
    private fun tangentOf(
        e: ProfileElement,
        atStart: Boolean,
    ): Vec2? =
        when (e) {
            is ProfileElement.Seg -> {
                val d = e.segment.b - e.segment.a
                if (d.length() <= Vec2.EPS) null else d.normalized()
            }
            is ProfileElement.ArcE -> {
                val q = if (atStart) GeomMath.startOf(e) else GeomMath.endOf(e)
                val radial = q - e.arc.center
                if (radial.length() <= Vec2.EPS) null else (radial.normalized().perp() * (if (e.arc.ccw) 1.0 else -1.0))
            }
            else -> null
        }

    /**
     * The in-face direction square to [tangent] the walk carries on with: of the two, the one nearer the
     * direction it came from, and where those two tie (a piece leaving its face at a right angle) the one
     * that turns the way the corner does.
     */
    private fun squareTo(
        tangent: Vec3,
        n: Vec3,
        from: Vec3,
        total: Double,
    ): Vec3? {
        val c = n.cross(tangent)
        if (c.length() <= Geom3.WELD_TOL) return null
        val plus = c.normalized()
        val minus = plus * -1.0
        val a1 = signedTurn(from, plus, n)
        val a2 = signedTurn(from, minus, n)
        val pick =
            when {
                abs(a1) < abs(a2) - 1e-12 -> plus
                abs(a2) < abs(a1) - 1e-12 -> minus
                a1 * total >= 0.0 -> plus
                else -> minus
            }
        val turn = signedTurn(from, pick, n)
        if (abs(turn) > PI / 2.0 + 1e-9) return null
        if (turn * total < -1e-12) return null
        return pick
    }

    /** How far [from] turns to reach [to] about [n], signed and in `(−π, π]`. */
    private fun signedTurn(
        from: Vec3,
        to: Vec3,
        n: Vec3,
    ): Double = atan2(n.dot(from.cross(to)), from.dot(to).coerceIn(-1.0, 1.0))

    /**
     * One axis of a section frame turned to [dir] — the in-face direction goes to [dir], the face's own
     * normal stays put, and `(ea, n)` being an orthonormal basis of the section plane is what makes that a
     * rotation rather than a shear.
     */
    private fun turnAxis(
        dir: Vec3,
        n: Vec3,
        ea: Vec3,
        axis: Vec3,
    ): Vec3 = dir * axis.dot(ea) + n * axis.dot(n)

    /**
     * Whether the corner's bisector turns **into** the shared face, which is what says the corner is a
     * convex one — `bis·d = cos(θ/2)` along the edge's own direction out of the corner, so this is the
     * statement `θ < 180°` and nothing more. See [cornersOf], where the other way round becomes a [Turn].
     */
    private fun turnsInward(
        piece: Piece,
        atStart: Boolean,
        bis: Vec3,
    ): Boolean {
        val seg = piece.seg ?: return false
        val d = (if (atStart) seg.end - seg.start else seg.start - seg.end).normalized()
        return bis.dot(d) > 1e-9
    }

    /**
     * How far into its own edge one mitre ring reaches — `cot(θ/2)` times the tangency's own setback, which
     * is the number that decides whether a corner has room for the size asked for.
     */
    private fun reachOf(
        piece: Piece,
        atStart: Boolean,
        place: Placement,
    ): Double {
        val from = if (atStart) 0.0 else piece.length
        return piece.grown.maxOf { abs(stationOf(piece, place.at(it)) - from) }
    }

    /**
     * Why the corners cannot host this size, or null when they can — *"the ball no longer fits in the
     * corner"*, named and healing (OP-3, session 65's rule that a refusal says what to do instead).
     *
     * The two corners of one edge each eat `cot(θ/2)` times the setback off it, so a corner sharp enough,
     * or an edge short enough, leaves them nothing to stand in. Where that happens the *whole* blend is
     * refused rather than the corner quietly dropped: a corner that does not fit is a rounding the user
     * cannot have, and saying so with the largest size that would is what makes it actionable.
     */
    private fun crowdedCorner(
        pieces: List<Piece>,
        corners: List<Corner>,
    ): Corner? {
        val reach = HashMap<Int, Double>()
        val blame = HashMap<Int, Corner>()
        for (c in corners) {
            for (end in c.ends) {
                val r = reachOf(pieces[end.first], end.second, c.ringAt(end))
                reach[end.first] = (reach[end.first] ?: 0.0) + r
                if (blame[end.first] == null) blame[end.first] = c
            }
        }
        // the pieces in **their own order**, never the map's: which corner a refusal names has to be a
        // function of the drawing and not of a hash (OP-15's determinism rule)
        for (who in pieces.indices) {
            val r = reach[who] ?: continue
            if (r >= pieces[who].length - Geom3.WELD_TOL) return blame[who]
        }
        return null
    }

    /**
     * The free ends of one group that **meet another member's free end** — a vertex the two sweeps butt at
     * without a corner between them.
     *
     * Where it happens, and why it must be said. A chain round a face whose boundary turns a **reflex**
     * corner (an L-shaped cap) has no corner built there: the two bands do not overlap at an inside corner,
     * they leave a wedge between them, and the ball that would round it pivots about the upright — the
     * inside-corner patch, a future extension. So the two sweeps are butt-ended at that vertex, and their
     * two caps, standing in the two planes square to the two edges, **share a segment of the upright**
     * where those planes meet. One mesh with that in it is not a shell, and the tool would refuse.
     *
     * The cure is a micron of daylight: the two butting ends are pulled back along their own edges by
     * [GROW_MM], which parts the caps completely (each tube then lies strictly on its own side of the
     * other's cap plane) and leaves a micron of material at a corner that already keeps a whole spike
     * there. Nothing else about the chain changes, so the five corners of a six-sided L-shaped cap are
     * still built and only its inside corner is still left alone.
     */
    private fun buttEnds(
        pieces: List<Piece>,
        group: List<Int>,
        rings: Map<Pair<Int, Boolean>, Placement>,
    ): Set<Pair<Int, Boolean>> {
        val free = ArrayList<Pair<Pair<Int, Boolean>, Vec3>>()
        for (at in group) {
            val seg = pieces[at].seg ?: continue
            for (atStart in listOf(true, false)) {
                if ((at to atStart) in rings) continue
                free.add((at to atStart) to (if (atStart) seg.start else seg.end))
            }
        }
        val out = HashSet<Pair<Int, Boolean>>()
        for (i in free.indices) {
            for (j in free.indices) {
                if (i == j || free[i].first.first == free[j].first.first) continue
                if ((free[i].second - free[j].second).length() <= RING_TOL) out.add(free[i].first)
            }
        }
        return out
    }

    /** The pieces grouped by the corners that join them — one tool, and one boolean, per group. */
    private fun groupsOf(
        count: Int,
        corners: List<Corner>,
    ): List<List<Int>> {
        val owner = IntArray(count) { it }

        fun root(x: Int): Int {
            var r = x
            while (owner[r] != r) r = owner[r]
            return r
        }
        for (c in corners) {
            for (end in c.ends.drop(1)) {
                val ra = root(c.ends.first().first)
                val rb = root(end.first)
                if (ra != rb) owner[max(ra, rb)] = min(ra, rb)
            }
        }
        val out = LinkedHashMap<Int, MutableList<Int>>()
        for (i in 0 until count) out.getOrPut(root(i)) { ArrayList() }.add(i)
        return out.values.toList()
    }

    /**
     * One group's whole cutting tool as **one closed mesh** — each edge's wedge carried between its two
     * rings, the mitre rings shared with the neighbour, a cap at every free end.
     *
     * Watertight by construction, and that is the point of the exercise: the two tubes either side of a
     * corner end on the *same* ring, so there is nothing left there for a boolean to intersect. What used to
     * happen instead is what GitHub #27 and #28 reported — two bands meeting almost tangentially where both
     * touch the shared face, whose crossing curve the general engine had to find in the worst conditioning
     * there is: it came out as slivers a millionth of a square millimetre across (the reporter's *"rendering
     * artefacts"*) or as no closed shell at all (*"a tangent or self-touching contact"*).
     *
     * The winding is stated rather than fixed up afterwards. The section is counter-clockwise in `(e1, e2)`
     * and `(e1, e2, u)` is right-handed, so the quads run from the ring nearer the run's start to the one
     * further along it, the far cap keeps the section's own winding and the near cap is reversed.
     */
    private fun toolMesh(
        pieces: List<Piece>,
        group: List<Int>,
        rings: Map<Pair<Int, Boolean>, Placement>,
        butts: Set<Pair<Int, Boolean>>,
        corners: List<Corner>,
    ): Pair<Mesh3?, String?> {
        val b = Geom3.MeshBuilder()
        // the corners' own surfaces first, so the tool is one shell before a single tube is drawn
        for (c in corners) if (c.ends.any { it.first in group }) c.emit(pieces, b)
        for (at in group) {
            val piece = pieces[at]
            val seg = piece.seg ?: return null to "${piece.crease.edge.name.label} is not one straight run, so it carries no corner"
            val atStart = rings[at to true]
            val atEnd = rings[at to false]
            val u = (seg.end - seg.start).normalized()
            val back0 = if ((at to true) in butts) GROW_MM else 0.0
            val back1 = if ((at to false) in butts) GROW_MM else 0.0
            val p0 = atStart ?: Placement(seg.start + u * back0, piece.crease.e1, piece.crease.ref.e2)
            val p1 = atEnd ?: Placement(seg.end - u * back1, piece.crease.e1, piece.crease.ref.e2)
            // grown at a corner, plain at a free end: the growth tapers along the run ([sectionPolygons])
            val r0 = (if (atStart != null) piece.grown else piece.section).map { p0.at(it) }
            val r1 = (if (atEnd != null) piece.grown else piece.section).map { p1.at(it) }
            for (m in piece.section.indices) {
                val n = (m + 1) % piece.section.size
                b.triangle(r0[m], r0[n], r1[n])
                b.triangle(r0[m], r1[n], r1[m])
            }
            if (atStart == null) for (t in piece.caps) b.triangle(p0.at(t.c), p0.at(t.b), p0.at(t.a))
            if (atEnd == null) for (t in piece.caps) b.triangle(p1.at(t.a), p1.at(t.b), p1.at(t.c))
        }
        val mesh = b.build()
        if (mesh.triangles.isEmpty()) return null to "the rounding's own tool has no triangles"
        if (Geom3.volume(mesh) <= 0.0) return null to "the rounding's own tool encloses no volume"
        MeshCanon.fault(mesh)?.let { return null to "the rounding's own tool is not a closed shell: $it" }
        return mesh to null
    }

    // ---- the whole construction ----

    /**
     * The **choices** a live gesture scores, one per edge of [targets] — what the tool writes into its step's
     * `signs=` and what every replay hands back instead (OP-1/OP-18).
     */
    fun choicesFor(
        base: Solid3,
        targets: List<Int>,
        sec: BlendSection,
        onFace: FaceName? = null,
    ): Pair<List<BlendChoice>?, String?> {
        val feature = base.feature
        val (edges, whyEdges) = Section3.edges(feature)
        if (edges == null) return null to whyEdges
        val reach = sec.reach()
        if (reach <= Geom3.WELD_TOL) return null to "this ${sec.kind.word} has no size at all to run along an edge"
        val out = ArrayList<BlendChoice>(targets.size)
        for (i in targets) {
            val edge = edges.getOrNull(i) ?: return null to "this solid has no edge #${i + 1}"
            val (crease, why) = creaseOf(feature, edge)
            if (crease == null) return null to why
            val (sector, whySector) = sectorOf(crease, base.mesh, reach)
            if (sector == null) return null to whySector
            val (s1, s2, convex) = sector
            // **which end of a drawn profile goes to which face** (GitHub #30): the first end is the setback
            // on the face the click named — the one it looked at for an edge pick, the one it landed on for a
            // face pick — so a chain round one face reads every edge the same way and its corners are
            // congruent by construction. Scored once here and taken verbatim ever after (OP-1/OP-18).
            val flip = if (onFace == null || crease.face1.name == onFace) 1 else -1
            val l1 = crease.leg1.line
            val l2 = crease.leg2.line
            out.add(
                if ((l1 != null && l2 != null) || sec.kind != BlendKind.FILLET) {
                    // **which way along each leg the corner opens** — the quadrant [FilletMath.legSigns]
                    // scores one dimension down, and what a chamfer stores whatever its legs are (session 76:
                    // the setback runs along the carrier, so the direction along it *is* the choice). A drawn
                    // profile stores the very same two, since its two coordinates are setbacks along those
                    // same legs. The probe walks the leg itself rather than its tangent, which for a straight
                    // leg is the very same point and for a round one is exactly on the carrier.
                    val step = min(reach, crease.length / 2.0) * PROBE_FRACTION
                    val q1 = FilletMath.setback(crease.leg1, Vec2(0.0, 0.0), step, 1)
                    val q2 = FilletMath.setback(crease.leg2, Vec2(0.0, 0.0), step, 1)
                    if (q1 == null || q2 == null) return null to notFitting(crease, sec)
                    val sign1 = if (sideOf(crease.leg2, q1) == s2) 1 else -1
                    val sign2 = if (sideOf(crease.leg1, q2) == s1) 1 else -1
                    BlendChoice(sign1, sign2, 0, convex, flip)
                } else {
                    // at least one round leg: the mixed fillet's stored variant — which side each leg is
                    // offset to, and which of the two intersections of those offsets the centre is
                    val centres =
                        FilletMath.centres(crease.leg1, crease.leg2, sec.size, s1, s2)?.takeIf { it.isNotEmpty() }
                            ?: return null to notFitting(crease, sec)
                    val branch = if ((centres.first()).length() <= (centres.last()).length()) 1 else -1
                    BlendChoice(s1, s2, branch, convex, flip)
                },
            )
        }
        return out to null
    }

    /**
     * [base] with a [kind] blend of [size] run along every edge of [targets] — the whole sentence: the corner
     * wedge of the 2D fillet swept along the lifted edge and applied by a boolean.
     *
     * **[base] states the geometry and [applyTo] takes the cut**, and they are the same body whenever the
     * addressed body *is* the body being dressed — which, since slice 3, is every blend and every blend of a
     * blend, because a [Feature3.Blend] names its own edges. They part company where an ordinary boolean
     * stands between the two: a **fused** body is a `Feature3.MeshBoolean` and names no edges (OP-9's sink
     * rule), so the addresses stay against the analytic base while the wedge is applied to the part as it
     * stands and blends *chain* instead of forking the model back onto the original. The pair is exactly the
     * part-and-tip split OP-17's sequential features already make, said one operation further, and it is
     * what let slice 3 supersede the mesh tier without changing a single stored address.
     *
     * **One sweep per edge, still** — and, since session 79, one boolean per *group of edges joined by a
     * corner*.
     *
     * The old rule read *"one sweep and one boolean per edge, deliberately … at a sharp corner they overlap
     * and the boolean trims them"*, on the argument that a construction whose number of sweeps moved with
     * the geometry would be structure decided at eval time (OP-21). The first half of it stands untouched:
     * there is exactly one sweep per target, in the step's own order, and the count is the address list's.
     * The second half is what GitHub #27 and #28 came from. Two wedges that overlap at a sharp corner are
     * two cylinders of one radius that are both **tangent to the shared face** where they meet it, so the
     * curve the boolean has to find there is the worst-conditioned intersection there is: the reporters got
     * *"a tangent or self-touching contact has no watertight mesh"* on a triangle, and sliver triangles a
     * millionth of a square millimetre across where it did come out.
     *
     * So the corner is **built** ([mitrePlacement]): the two sweeps are cut on the surface equidistant from
     * their two edges — a plane, for two straight edges — where both of them place their own section and
     * land on the same ring of points, and the two tubes are stitched into one closed tool that one boolean
     * applies. Nothing about the *result* changes: the region removed is the very same union it always was
     * (its volume is the machinist's figure to the last digit for a chamfer), and what changes is that no
     * engine has to discover it. The number of booleans is a fact about which targets **share a vertex**,
     * which is topology and not measurement, so OP-21's concern is answered rather than traded away.
     *
     * Where the boundary runs on **smoothly** nothing at all happens: the two sections already abut on the
     * same plane, no corner is built, and each piece is swept and applied exactly as before (the rounded
     * rim, bit for bit). The corners still left to the boolean, each for a stated reason, are listed at
     * [cornersOf]. Since session 80 the two places the ball **stands still** are built too: an inside
     * corner turns the section about the upright ([Turn]) and a convex vertex where three bands meet gets
     * the ball's own patch ([Vertex]).
     */
    fun blended(
        base: Solid3,
        applyTo: Solid3,
        targets: List<Int>,
        sec: BlendSection,
        choices: List<BlendChoice>,
        /**
         * The **undressed root** of the chain [base] stands on — the body every rounding in it was cut out
         * of — and null where there is none (a first blend, or one whose tip an ordinary boolean made).
         *
         * A fact about the *graph*, handed over by the node that built this blend rather than discovered
         * here (OP-21): it is needed only when this gesture rounds an upright an earlier corner pivots
         * about, and then it is needed absolutely — see the note at the rebuild below.
         */
        root: Solid3? = null,
    ): Pair<Solid3?, String?> {
        val kind = sec.kind
        if (kind != BlendKind.PROFILE && sec.size <= Geom3.WELD_TOL) {
            return null to "a ${kind.word} needs a positive ${kind.sizeWord} — this one is ${Frames3.mm(sec.size)} mm"
        }
        if (kind == BlendKind.PROFILE && sec.profile.isEmpty()) {
            return null to "a ${kind.word} needs a drawn profile to run along the edge"
        }
        val feature = base.feature
        val (edges, whyEdges) = Section3.edges(feature)
        if (edges == null) return null to whyEdges
        if (choices.size < targets.size) return null to "this blend recorded ${choices.size} choices for ${targets.size} edges"
        // **Every target prepared first, then the corners, then one tool per group.** The pieces are built
        // in the step's own order, so a blend with no corner in it reaches [Geom3.sweep] exactly as it did
        // before this session and its triangles are the same triangles.
        val pieces = ArrayList<Piece>()
        for ((k, i) in targets.withIndex()) {
            val edge = edges.getOrNull(i) ?: return null to "this solid has no edge #${i + 1} (it has ${edges.size})"
            val (crease, why) = creaseOf(feature, edge)
            if (crease == null) return null to why
            val choice = choices[k]
            val (wedge, whyWedge) = wedgeOf(crease, sec, choice)
            if (wedge == null) return null to whyWedge
            if (!tangenciesFit(crease, wedge)) {
                val fits = largestFitting(crease, sec, choice)
                return null to
                    "a ${kind.word} ${sec.sizePhrase()} reaches past ${crease.face1.name.label} " +
                    "or ${crease.face2.name.label} at ${crease.edge.name.label} — the largest that fits there is " +
                    sec.fitPhrase(fits)
            }
            val (piece, whyPiece) = pieceOf(i, false, crease, wedge, choice, sec)
            if (piece == null) return null to whyPiece
            pieces.add(piece)
        }
        // …and the bands already under this one, so a blend of a blend on an adjacent edge builds the same
        // corner a one-gesture chain would (GitHub #27, [chainPieces]).
        pieces.addAll(chainPieces(feature))
        val found = cornersOf(pieces)
        found.refusal?.let { return null to it }
        val corners = found.list
        crowdedCorner(pieces, corners)?.let { c ->
            val fits = largestCornerFitting(pieces, sec)
            return null to
                "${c.label(pieces)} is too sharp for a ${kind.word} ${sec.sizePhrase()} — " +
                "the corner the roundings share would reach further along an edge than the edge is long. " +
                "The largest that fits there is ${sec.fitPhrase(fits)}"
        }
        mixedCorner(pieces, corners)?.let { return null to it }
        val rings = HashMap<Pair<Int, Boolean>, Placement>()
        for (c in corners) for (end in c.ends) rings[end] = c.ringAt(end)
        val groups = groupsOf(pieces.size, corners)

        fun apply(
            body: Solid3,
            group: List<Int>,
        ): Pair<Solid3?, String?> {
            val lead = pieces[group.firstOrNull { !pieces[it].existing } ?: group.first()]
            val (tool, whyTool) =
                if (group.size == 1) {
                    Geom3.sweep(lead.crease.path, lead.crease.e1, SweepProfile.Section(lead.wedge.region), plan = null)
                } else {
                    val (mesh, whyMesh) = toolMesh(pieces, group, rings, buttEnds(pieces, group, rings), corners)
                    if (mesh == null) {
                        null to whyMesh
                    } else {
                        // the tool is a union of bands and states nothing else about itself: it is a mesh
                        // with no analytic reading, which is exactly what [Feature3.MeshBoolean] means (OP-9)
                        Solid3.of(Feature3.MeshBoolean(BoolOp.UNION), mesh) to null
                    }
                }
            if (tool == null) {
                return null to "${lead.crease.edge.name.label}: ${whyTool ?: "the blend cannot be swept along it"}"
            }
            val op = if (lead.choice.convex) BoolOp.SUBTRACT else BoolOp.UNION
            val (next, whyBool) = Geom3.combine(op, body, tool)
            if (next == null) {
                return null to "${lead.crease.edge.name.label}: ${whyBool ?: "the blend cannot be applied to this body"}"
            }
            return next to null
        }

        // **A corner that turns about an upright is rebuilt from the root, and the tip cannot be re-cut**
        // (session 81). Such a corner sets its two bands *back* along their own edges and turns the pivot on
        // a wider circle, so whichever of the three arrived last, some band on the body already ran past
        // where the corner now ends it — and a further boolean of the same sign can never take that back (a
        // second subtraction only removes more; a second union only adds more). So the whole chain is rebuilt
        // from its own undressed root, every group applied in dependency order, and for the two sectors that
        // is `(root ∪ upright) − chain'` and `(root − upright) ∪ fills'`. Where no corner turns about an
        // upright the old path stands untouched, so no existing drawing's mesh moves by one bit.
        val stale = corners.any { it.extra.isNotEmpty() }
        if (!stale) {
            var result = applyTo
            for (group in groups) {
                // a group of nothing but bands already off the body has nothing left to cut
                if (group.all { pieces[it].existing }) continue
                val (next, why) = apply(result, group)
                result = next ?: return null to why
            }
            return result to null
        }
        // …and where this is the chain's **first** rounding the body addressed *is* the undressed root, so
        // there is nothing to look up: the operand is only ever needed one rounding further along
        val start =
            (root ?: applyTo.takeIf { base === applyTo && it.feature !is Feature3.Blend }) ?: return null to
                "${(pieces.firstOrNull { !it.existing } ?: pieces.first()).crease.edge.name.label}: rounding it re-turns a corner an " +
                "earlier rounding made, and this body has an ordinary boolean under it rather than a chain " +
                "of roundings — so there is no undressed body to rebuild the chain from. Round that upright " +
                "before the fusion, or before the faces whose corner it turns"
        val ordered =
            orderedGroups(pieces, groups, corners) ?: return null to
                "${(pieces.firstOrNull { !it.existing } ?: pieces.first()).crease.edge.name.label}: two of this body's roundings each " +
                "turn the other's corner, so there is no order to apply them in — round one of the two edges " +
                "in a separate body, or leave one of them sharp"
        var result = start
        for (group in ordered) {
            val (next, why) = apply(result, group)
            result = next ?: return null to why
        }
        return result to null
    }

    /**
     * The groups in **dependency order**: an upright's own group before the group whose corner pivots about
     * it, ties in the order [groupsOf] found them. Null where two groups each turn the other's corner.
     *
     * The order is the whole of the rebuild's correctness: the pair's tool is stitched to a ring that stands
     * on the upright's own band, so the upright has to be *on the body* before the pair is cut out of it.
     */
    private fun orderedGroups(
        pieces: List<Piece>,
        groups: List<List<Int>>,
        corners: List<Corner>,
    ): List<List<Int>>? {
        val of = IntArray(pieces.size)
        for ((g, group) in groups.withIndex()) for (at in group) of[at] = g
        val before = Array(groups.size) { HashSet<Int>() }
        for (c in corners) {
            for (u in c.extra) {
                val gu = of[u]
                for (end in c.ends) if (of[end.first] != gu) before[of[end.first]].add(gu)
            }
        }
        val out = ArrayList<List<Int>>(groups.size)
        val done = BooleanArray(groups.size)
        repeat(groups.size) {
            val next = groups.indices.firstOrNull { !done[it] && before[it].all { p -> done[p] } } ?: return null
            done[next] = true
            out.add(groups[next])
        }
        return out
    }

    /**
     * Why two bands meeting at a shared vertex cannot make a corner, or null when every such meeting can —
     * the **general tier's own refusal** (GitHub #30, fork 4).
     *
     * Two wedges make a mitre (or a pivot) only where they are **congruent in the face they share**: one
     * section, one dihedral. Before the drawn profile that was rare enough to be left alone — two bands of
     * different radii overlapping are trimmed by the boolean, which is what every build has done — but it is
     * exactly the tangent contact GitHub #27 and #28 came from, and a drawn profile makes it ordinary: two
     * adjacent edges given two different profiles, or a custom band meeting a built-in fillet along a chain
     * of gestures. So where **at least one side is a drawn profile** the pair is refused by name with its
     * cure, and where neither is the old behaviour stands untouched — no existing drawing changes.
     *
     * Only a meeting of **two** ends is judged. Three bands at one vertex that the ball cannot close (a
     * multi-piece profile, session 80's unequal-turn chamfer) are left to the boolean exactly as they were:
     * refusing there would take away a rounding the user can perfectly well have.
     */
    private fun mixedCorner(
        pieces: List<Piece>,
        corners: List<Corner>,
    ): String? {
        val claimed = HashSet<Pair<Int, Boolean>>()
        for (c in corners) claimed.addAll(c.ends)
        for (i in pieces.indices) {
            for (j in i + 1 until pieces.size) {
                val a = pieces[i]
                val b = pieces[j]
                if (a.sec.kind != BlendKind.PROFILE && b.sec.kind != BlendKind.PROFILE) continue
                val sa = a.seg ?: continue
                val sb = b.seg ?: continue
                val shared =
                    listOf(a.crease.face1, a.crease.face2)
                        .firstOrNull { f -> f.plane != null && (f.name == b.crease.face1.name || f.name == b.crease.face2.name) }
                        ?: continue
                for (aAtStart in listOf(true, false)) {
                    for (bAtStart in listOf(true, false)) {
                        if ((i to aAtStart) in claimed || (j to bAtStart) in claimed) continue
                        val corner = if (aAtStart) sa.start else sa.end
                        if ((corner - (if (bAtStart) sb.start else sb.end)).length() > RING_TOL) continue
                        // three or more bands at this point are the vertex's business, not this one's
                        val here =
                            pieces.indices.count { k ->
                                pieces[k].seg?.let { (it.start - corner).length() <= RING_TOL || (it.end - corner).length() <= RING_TOL } == true
                            }
                        if (here != 2) continue
                        return "the corner where ${a.crease.edge.name.label} meets ${b.crease.edge.name.label} on " +
                            "${shared.name.label} carries two roundings that are not the same section on that face — " +
                            "a corner is built only where the two are congruent there. Give both edges the same " +
                            "profile, or round only one of the two edges that meet at it"
                    }
                }
            }
        }
        return null
    }

    /**
     * The largest size whose corners all have room, by halving — what the crowded-corner refusal names so it
     * can be acted on, the same shape of answer [largestFitting] gives for a size that outgrows a face.
     */
    private fun largestCornerFitting(
        pieces: List<Piece>,
        sec: BlendSection,
    ): Double {
        var lo = 0.0
        var hi = 1.0
        repeat(FIT_STEPS) {
            val mid = (lo + hi) / 2.0
            val trial = ArrayList<Piece>(pieces.size)
            var ok = true
            for (p in pieces) {
                val w = wedgeOf(p.crease, p.sec.scaledBy(mid), p.choice).first
                val q = if (w == null) null else pieceOf(p.index, p.existing, p.crease, w, p.choice, p.sec).first
                if (q == null) {
                    ok = false
                    break
                }
                trial.add(q)
            }
            val found = cornersOf(trial)
            if (ok && found.refusal == null && crowdedCorner(trial, found.list) == null) lo = mid else hi = mid
        }
        return lo
    }

    // ---- what a click names: the **face**, since an edge is picked by its own drawing (see `Document`) ----

    /**
     * Which **face** of [feature] the click at [at], made in [from], meant: among the flat faces the click
     * falls within when it is dropped along the space's own normal, the one **nearest the eye** — the rule
     * [Project3.landingFace] states for a projected drawing, asked of one point instead of a curve.
     *
     * Scored once from the click and then persisted (OP-1/OP-18): a reload that scored again would break a
     * different face's edges as soon as an edit slid the body past the click.
     */
    fun faceNear(
        feature: Feature3,
        from: Plane3,
        at: Vec2,
    ): Pair<Int?, String?> {
        val (faces, why) = Section3.faces(feature)
        if (faces == null) return null to why
        val d = from.normal.normalized()
        var best: Int? = null
        var bestReach = -Double.MAX_VALUE
        for (i in faces.indices) {
            val plane = faces[i].plane ?: continue
            val n = plane.normal.normalized()
            val denom = d.dot(n)
            if (abs(denom) <= 1e-9) continue
            val map = Project3.mapOnto(from, plane) ?: continue
            val rings = Project3.ringsOf(faces[i].outline)
            if (rings.isEmpty() || !RegionBool.contains(rings, map.apply(at))) continue
            val reach = (plane.origin - from.toWorld(at)).dot(n) / denom
            if (reach > bestReach) {
                bestReach = reach
                best = i
            }
        }
        return best to (if (best == null) NO_FACE_UNDER_CLICK else null)
    }

    // ---- slice 3: the dress-up feature's own face and edge lists ----

    /**
     * One blended edge, fully resolved — the crease, the wedge and the choice that made it.
     *
     * The dressed lists are pure functions of [Feature3.Blend], and this is the shared step both of them
     * take: [dressedFaces] needs the tangencies (to trim the two faces and to build the band) and
     * [dressedEdges] needs them again (for the two rails), and neither may re-derive them differently.
     */
    private class Dressing(
        val index: Int,
        val edge: SolidEdge,
        val crease: Crease,
        val wedge: Wedge,
        val choice: BlendChoice,
        val sec: BlendSection,
    ) {
        /** The band's own name — one per piece of the section, since a drawn profile has several. */
        fun nameAt(piece: Int): FaceName = FaceName.BlendBand(index, piece)

        val name: FaceName get() = nameAt(0)

        /** How this band is spoken of when it is the *thing* rather than the address — "the fillet". */
        val kindWord: String get() = "${sec.kind.word} itself"
    }

    private fun dressingsOf(f: Feature3.Blend): Pair<List<Dressing>?, String?> {
        val (edges, whyEdges) = Section3.edges(f.base)
        if (edges == null) return null to whyEdges
        val out = ArrayList<Dressing>(f.targets.size)
        for ((k, i) in f.targets.withIndex()) {
            val edge = edges.getOrNull(i) ?: return null to "this solid has no edge #${i + 1} (it has ${edges.size})"
            val (crease, why) = creaseOf(f.base, edge)
            if (crease == null) return null to why
            val choice = f.choices.getOrNull(k) ?: return null to "this blend recorded no choice for edge #${i + 1}"
            val (wedge, whyWedge) = wedgeOf(crease, f.section, choice)
            if (wedge == null) return null to whyWedge
            out.add(Dressing(i, edge, crease, wedge, choice, f.section))
        }
        return out to null
    }

    /**
     * **The dressed face list**: the base's faces at their **own indices**, with the outline of every face
     * the blend cut into corrected, followed by one band per blended edge (session 71, slice 3).
     *
     * Two rules, and both are OP-21's index stability read on a face list:
     *
     * - *Nothing renumbers and nothing drops out.* Face #3 of the base is face #3 of the dressed part,
     *   whatever the blend did to it, because a stored `sketchspace el= piece=` and every recorded section
     *   index is an address into this list (OP-18). A face the blend trimmed and whose correction is not
     *   reachable analytically keeps its index and gains a **reason** instead — it is still there, it is
     *   still cut, it just cannot be sketched on until the correction can be stated.
     * - *The bands append*, exactly the way an extrusion's two caps append after its sides. So a blend of a
     *   blend addresses the first blend's own bands with no new machinery: they are ordinary entries of an
     *   ordinary list.
     *
     * **The correction is analytic in this tier and that is what makes it honest.** The blend removes a
     * strip of constant width from each of the two faces — the tangency stands at a fixed distance from
     * the crease, which is exactly what "the section is rigid" means ([creaseOf]) — so the face's own
     * boundary piece over that edge steps **inward by that distance** and its neighbours are re-joined on
     * their own carriers: a line against a line, a line against a circle, a circle against a circle, all
     * of them the intersections this drawing already computes. Nothing is sampled and nothing is fitted.
     */
    fun dressedFaces(f: Feature3.Blend): Pair<List<FacePatch>?, String?> {
        val (baseFaces, whyFaces) = Section3.faces(f.base)
        if (baseFaces == null) return null to whyFaces
        val (dressings, whyDress) = dressingsOf(f)
        if (dressings == null) return null to whyDress
        val trims = HashMap<FaceName, MutableList<Pair<SolidEdge, Double>>>()
        for (d in dressings) {
            for ((patch, t) in listOf(d.crease.face1 to d.wedge.t1, d.crease.face2 to d.wedge.t2)) {
                trims.getOrPut(patch.name) { ArrayList() }.add(d.edge to t.length())
            }
        }
        val out = ArrayList<FacePatch>(baseFaces.size + dressings.size)
        // …and the corners this level **re-turned** about an upright it rounded: they keep their index and
        // gain a reason, exactly as a consumed edge does ([cornerSuperseded], session 81)
        val superseded = supersedings(f)
        for (patch in baseFaces) {
            val n = patch.name
            if (n is FaceName.BlendCorner) {
                val why = superseded[n.edges]
                out.add(if (why == null) patch else patch.copy(plane = null, outline = emptyList(), reason = why, surface = null))
                continue
            }
            val cut = trims[patch.name]
            if (cut == null) {
                out.add(patch)
                continue
            }
            val (outline, why) = correctedOutline(patch, cut)
            out.add(
                if (outline == null) {
                    // the index stays, the face stays cut, and the reason says what is not reachable — a
                    // curved face's own trim needs a surface offset this vocabulary does not have (OP-3)
                    patch.copy(reason = patch.reason ?: why)
                } else {
                    patch.copy(outline = outline)
                },
            )
        }
        for (d in dressings) out.addAll(bandPatchesOf(d))
        // …and the corners this blend's own bands make, **appended last** ([FaceName.BlendCorner]): the ball
        // at a convex vertex and the surface its pivot sweeps at an inside one are new surfaces, and a
        // crossing and a bevelled vertex are not
        for (patch in cornerFacesOf(f)) out.add(patch)
        return out to null
    }

    /**
     * The pieces a dressed feature's own corners are read from — its own targets, then the chain under it,
     * exactly the list [blended] builds, so the faces the drawing states are the faces the tool cut.
     */
    private fun piecesOf(f: Feature3.Blend): List<Piece>? {
        val (edges, _) = Section3.edges(f.base) ?: return null
        if (edges == null) return null
        val out = ArrayList<Piece>(f.targets.size)
        for ((k, i) in f.targets.withIndex()) {
            val edge = edges.getOrNull(i) ?: return null
            val crease = creaseOf(f.base, edge).first ?: return null
            val choice = f.choices.getOrNull(k) ?: return null
            val wedge = wedgeOf(crease, f.section, choice).first ?: return null
            out.add(pieceOf(i, false, crease, wedge, choice, f.section).first ?: return null)
        }
        out.addAll(chainPieces(f.base))
        return out
    }

    /**
     * Where [cut] crosses the corner patch [name] — **exactly**, in both of the shapes a corner can be.
     *
     * A **pivot** is a surface of revolution, so [Revolve3]'s whole table answers it verbatim, meridian
     * column included. A **ball** is cut by every plane in a circle (the one band with no case), and the
     * patch is that circle **clipped to the spherical triangle**: its three sides are great circles, so each
     * is a half-space through the ball's own centre and the answer is an angular interval on the cut circle
     * — three half-planes intersected, still exact, no sampling anywhere.
     */
    fun cornerCut(
        f: Feature3.Blend,
        name: FaceName.BlendCorner,
        cut: Plane3,
    ): Revolve3.BandCut? {
        val (pieces, c) = cornerNamed(f, name) ?: return null
        if (c is Turn) {
            val (leg, sr) = c.facePlan(pieces).getOrNull(name.piece) ?: return null
            if (sr == null || leg.pivot == null) return null
            val (frame, map) = c.axisFrame(pieces[c.a], leg) ?: return null
            return Revolve3.cutBandOf(frame, mappedSection(sr, map) ?: return null, cut)
        }
        if (c is Vertex) {
            val (centre, radius) = c.ball ?: return null
            return ballCut(centre, radius, c.members.map { outOf(pieces[it.first], it.second) }, c.at, cut)
        }
        return null
    }

    /** The corner [name] addresses, with the pieces it was read among, or null when this level has none. */
    private fun cornerNamed(
        f: Feature3.Blend,
        name: FaceName.BlendCorner,
    ): Pair<List<Piece>, Corner>? {
        val pieces = piecesOf(f) ?: return null
        for (c in cornersOf(pieces).list) if (cornerEdges(pieces, c) == name.edges) return pieces to c
        return null
    }

    /** The base edges a corner is named by — the ends it closes, and the upright it turns about. */
    private fun cornerEdges(
        pieces: List<Piece>,
        c: Corner,
    ): List<Int> = (c.ends.map { pieces[it.first].index } + c.extra.map { pieces[it].index }).distinct().sorted()

    /**
     * Why a corner the **base** states is no longer a surface of this body, or null where it still is.
     *
     * A pivot about a *sharp* upright is superseded the moment that upright is itself rounded: the ball then
     * turns about the upright's own band, on a wider circle and between set-back ends, and the horn torus it
     * used to sweep is nowhere on the part. The face keeps its index and gains this sentence — exactly what a
     * consumed **edge** keeps in [dressedEdges] — so nothing renumbers and nothing claims a surface that is
     * not there (OP-17/OP-21, session 81).
     */
    fun cornerSuperseded(
        f: Feature3.Blend,
        name: FaceName.BlendCorner,
    ): String? = supersedings(f)[name.edges]

    private fun supersedings(f: Feature3.Blend): Map<List<Int>, String> {
        val pieces = piecesOf(f) ?: return emptyMap()
        val out = HashMap<List<Int>, String>()
        for (c in cornersOf(pieces).list) {
            if (c.extra.isEmpty()) continue
            val ends = c.ends.map { pieces[it.first].index }.distinct().sorted()
            val whole = cornerEdges(pieces, c)
            if (whole == ends) continue
            val upright = pieces[c.extra.first()]
            out[ends] =
                "${FaceName.BlendCorner(ends, 0).label} was re-turned about ${upright.crease.edge.name.label} " +
                "when that edge was rounded — the ball no longer pivots about a sharp upright there, and " +
                "${FaceName.BlendCorner(whole, 0).label} stands in its place"
        }
        return out
    }

    /**
     * The **straight leg** of a corner as its family of rulings — the pair's section carried along the
     * upright bevel's own run, which is the very sweep a band along a straight edge is, so it is cut by the
     * very same machinery ([Section3.cutRuledStrip]) rather than by a second copy of it.
     */
    internal fun cornerStrip(
        f: Feature3.Blend,
        name: FaceName.BlendCorner,
    ): Section3.RuledStrip? {
        val (leg, sr) = straightLeg(f, name) ?: return null
        val from = leg.rings.first()
        val to = leg.rings.last()
        val steps =
            when (sr) {
                is ProfileElement.ArcE ->
                    max(BAND_SECTION_STEPS, GeomMath.chordSteps(sr.arc.radius, GeomMath.sweep(sr.arc), GeomMath.TESS_TOL_MM))
                else -> BAND_SECTION_STEPS
            }
        return Section3.RuledStrip(false, { t ->
            val q = sectionPointAt(sr, t) ?: Vec2(0.0, 0.0)
            from.at(q) to to.at(q)
        }, steps)
    }

    /**
     * The **straight leg** of a corner cut by a plane its run is **parallel** to — the one cut no ruling
     * crosses, stated exactly, exactly as [parallelBandCut] states it for a band.
     */
    internal fun cornerParallelCut(
        f: Feature3.Blend,
        name: FaceName.BlendCorner,
        cut: Plane3,
    ): List<ProfileElement>? {
        val (leg, sr) = straightLeg(f, name) ?: return null
        val from = leg.rings.first()
        val to = leg.rings.last()
        val v = to.origin - from.origin
        val len = v.length()
        if (len <= Geom3.WELD_TOL) return null
        val n = cut.normal.normalized()
        if (abs(v.dot(n) / len) > DIR_EPS) return null
        val depth = (from.origin - cut.origin).dot(n)
        val hits = sectionOnPlane(sr, Vec2(from.cx.dot(n), from.cy.dot(n)), -depth)
        val out = ArrayList<ProfileElement>(hits.size)
        for (q in hits) {
            val a = cut.toLocal(from.at(q))
            val b = cut.toLocal(to.at(q))
            if ((b - a).length() > Geom3.WELD_TOL) out.add(ProfileElement.Seg(Segment(a, b)))
        }
        return out.ifEmpty { null }
    }

    /** The leg and section piece [name] addresses, where that leg **slides** rather than turns. */
    private fun straightLeg(
        f: Feature3.Blend,
        name: FaceName.BlendCorner,
    ): Pair<Leg, ProfileElement>? {
        val (pieces, c) = cornerNamed(f, name) ?: return null
        if (c !is Turn) return null
        val (leg, sr) = c.facePlan(pieces).getOrNull(name.piece) ?: return null
        if (leg.pivot != null || sr == null) return null
        return leg to sr
    }

    /**
     * The circle a plane cuts a ball in, clipped to the spherical triangle whose three sides stand square to
     * [dirs] through the centre and whose inside is the side [at] is on.
     */
    private fun ballCut(
        centre: Vec3,
        radius: Double,
        dirs: List<Vec3>,
        at: Vec3,
        cut: Plane3,
    ): Revolve3.BandCut? {
        val n = cut.normal.normalized()
        val off = (centre - cut.origin).dot(n)
        if (abs(off) >= radius) return Revolve3.BandCut(emptyList(), null)
        val rho = sqrt(radius * radius - off * off)
        if (rho <= Geom3.WELD_TOL) return Revolve3.BandCut(emptyList(), null)
        var live = listOf(0.0 to 2.0 * PI)
        for (d in dirs) {
            val w = d * (if ((at - centre).dot(d) >= 0.0) 1.0 else -1.0)
            val a = rho * cut.u.dot(w)
            val b = rho * cut.v.dot(w)
            val k = off * n.dot(w)
            val reach = sqrt(a * a + b * b)
            live =
                when {
                    reach <= abs(k) -> if (k <= -reach) live else emptyList()
                    else -> {
                        val mid = atan2(b, a)
                        val half = acos((k / reach).coerceIn(-1.0, 1.0))
                        clipTurn(live, mid - half, mid + half)
                    }
                }
            if (live.isEmpty()) return Revolve3.BandCut(emptyList(), null)
        }
        val here = cut.toLocal(centre - n * off)
        return Revolve3.BandCut(live.map { (from, to) -> ProfileElement.ArcE(Arc(here, rho, from, to, true)) }, null)
    }

    /** [live] intersected with the turn interval `[lo, hi]`, both read round the whole circle. */
    private fun clipTurn(
        live: List<Pair<Double, Double>>,
        lo: Double,
        hi: Double,
    ): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        for ((a, b) in live) {
            // the allowed band repeats every turn, so it is met with each live span at three offsets — one
            // is enough for a span shorter than a full turn, and three covers the wrap either way
            for (shift in listOf(-2.0 * PI, 0.0, 2.0 * PI)) {
                val from = max(a, lo + shift)
                val to = min(b, hi + shift)
                if (to - from > 1e-12) out.add(from to to)
            }
        }
        return out
    }

    /**
     * One face per corner **this** blend's bands take part in that has a surface of its own.
     *
     * A corner is listed at the level where any of its ends **or its upright** is fresh (session 81): the
     * ring torus a pair pivots on appears when the *upright* is rounded, though both bands were cut two
     * gestures ago, and the horn torus it replaces keeps its index in the base's list with a reason
     * ([cornerSuperseded]). Nothing renumbers either way.
     */
    private fun cornerFacesOf(f: Feature3.Blend): List<FacePatch> {
        val pieces = piecesOf(f) ?: return emptyList()
        val out = ArrayList<FacePatch>()
        for (c in cornersOf(pieces).list) {
            if (c.ends.none { it.first < f.targets.size } && c.extra.none { it < f.targets.size }) continue
            val edges = cornerEdges(pieces, c)
            out.addAll(c.faces(pieces) { k -> FaceName.BlendCorner(edges, k) })
        }
        return out
    }

    /**
     * **The dressed edge list**: every base edge at its own index — a blended one flagged rather than
     * removed — then the two tangent rails of each band, appended.
     *
     * *Removing the consumed edge is not how it works*, and the reason is the same one that keeps the face
     * list stable: an index into this list is an address a step may already hold. So the edge stays where
     * it is with the carrier it had and a [SolidEdge.reason] saying it was rounded away; a second blend
     * asking for it is refused in those words, and a section still shows where it ran.
     *
     * What the rails buy is what makes a chain a chain: after a fillet, *"the edge between the band and the
     * top face"* is a first-class edge that a further blend can be addressed by.
     */
    fun dressedEdges(f: Feature3.Blend): Pair<List<SolidEdge>?, String?> {
        val (baseEdges, whyEdges) = Section3.edges(f.base)
        if (baseEdges == null) return null to whyEdges
        val (dressings, whyDress) = dressingsOf(f)
        if (dressings == null) return null to whyDress
        val consumed = dressings.associateBy { it.index }
        val out = ArrayList<SolidEdge>(baseEdges.size + 2 * dressings.size)
        for ((i, e) in baseEdges.withIndex()) {
            val d = consumed[i]
            out.add(
                if (d == null) {
                    e
                } else {
                    e.copy(
                        reason =
                            "${e.name.label} was rounded away by the ${f.kind.word} ${f.section.sizePhrase()} — " +
                                "${d.name.label} stands in its place, and its two tangent rails are edges of this body",
                    )
                },
            )
        }
        for (d in dressings) {
            for (side in 0..1) {
                val face = if (side == 0) d.crease.face1 else d.crease.face2
                val t = if (side == 0) d.wedge.t1 else d.wedge.t2
                val (geom, why) = railGeom(d, t)
                out.add(
                    SolidEdge(
                        EdgeName.BlendRail(d.index, side),
                        geom ?: EdgeGeom.Straight(d.crease.ref.at, d.crease.ref.at),
                        FacePair(face.name, d.name),
                        why,
                    ),
                )
            }
        }
        return out to null
    }

    // ---- the band: the blend's own section curve, carried along the edge ----

    /** The one curve piece an edge is, or null when it is a chain (which no tier here produces). */
    private fun soleElement(crease: Crease): Curve3Element? = crease.path.elements.singleOrNull()

    /** The world point of section coordinate [p] at parameter [s] along a **straight** edge from [a] along [u]. */
    private fun worldOnStraight(
        crease: Crease,
        a: Vec3,
        u: Vec3,
        p: Vec2,
        s: Double,
    ): Vec3 {
        val e2 = u.cross(crease.e1)
        return a + u * s + crease.e1 * p.x + e2 * p.y
    }

    /**
     * The **outward normal of the blend's own surface** — out of the body — on one piece of the section.
     *
     * One rule for every section, and it is the wedge's own winding rather than a shape: the wedge's loop is
     * counter-clockwise, so its interior lies to the **left** of travel, and the direction *into* the wedge
     * at a section piece is `perp(dir)` where the loop runs along that piece forwards ([Wedge.forward]) and
     * its negative where it runs back. The blend's surface then faces **into** the wedge where the wedge was
     * subtracted (a convex edge — the material is on the far side of it) and away from it where the wedge was
     * added (a concave one).
     *
     * *Why this replaced the two shape-specific readings.* The old rule was `q − centre` for an arc and the
     * bevel's perpendicular toward the corner for a segment. Both are this rule collapsed onto the shape they
     * were written for — and the arc one is **wrong for a cove**, an arc bulging away from the crease, whose
     * centre lies on the other side of the curve entirely. A drawn profile may be either, so the reading has
     * to come from the region and not from the curve.
     */
    private fun bandOutward(
        e: ProfileElement,
        forward: Boolean,
        convex: Boolean,
    ): Vec2? {
        val (_, dir) = midOf(e) ?: return null
        val n = dir.perp()
        if (n.length() <= Vec2.EPS) return null
        val sign = (if (forward) 1.0 else -1.0) * (if (convex) 1.0 else -1.0)
        return n.normalized() * sign
    }

    /** The mid-point and the tangent direction of a section piece — where its orientation is decided. */
    private fun midOf(e: ProfileElement): Pair<Vec2, Vec2>? =
        when (e) {
            is ProfileElement.Seg -> {
                val d = e.segment.b - e.segment.a
                if (d.length() <= Vec2.EPS) null else ((e.segment.a + e.segment.b) * 0.5) to d.normalized()
            }
            is ProfileElement.ArcE -> {
                val a = e.arc.startAngle + GeomMath.sweep(e.arc) * 0.5
                val p = GeomMath.arcPointAt(e.arc, a)
                val radial = (p - e.arc.center).normalized()
                p to (if (e.arc.ccw) radial.perp() else radial.perp() * -1.0)
            }
            else -> null
        }

    /**
     * [e] traversed so that the **material lies to its left** — the convention [Section3.sweptFace] and
     * [Revolve3.bandOf] both read, stated once here so a band's plane and a flat band's outward side come
     * out of the same sentence rather than out of two guesses.
     */
    private fun materialLeft(
        e: ProfileElement,
        outward: Vec2,
    ): ProfileElement {
        val (_, dir) = midOf(e) ?: return e
        return if (dir.perp().dot(outward) < 0.0) e else GeomMath.reverse(e)
    }

    /**
     * The band along one blended edge, as a face: the blend's section curve carried along the edge.
     *
     * **Two carriers and no third**, because the tier has no third: a **straight** edge carries the section
     * the way an extrusion does — a bevel sweeps a plane, a fillet arc sweeps a **cylinder** — and a
     * **circular** edge carries it the way a revolution does — the same two curves sweep a cone or an
     * annulus, a torus or a sphere. Both are read through the existing emitters ([Section3.sweptFace],
     * [Revolve3.bandPatch]) rather than restated, which is what keeps "a cylinder" one sentence in this
     * drawing instead of three.
     */
    private fun bandPatchesOf(d: Dressing): List<FacePatch> {
        val sections = orientedSections(d)
        val el = soleElement(d.crease)
        return sections.mapIndexed { k, piece ->
            val name = d.nameAt(k)
            when {
                el == null ->
                    FacePatch(name, null, emptyList(), "${d.edge.name.label} is a chain of several pieces, so its band has no single surface")
                // a Bézier, a conic or a function curve in the profile: the band it sweeps is a real
                // surface and its triangles are exact to the tessellation, but this drawing has no word
                // for it — so the face keeps its index and carries the reason (OP-15's approximated class)
                piece == null ->
                    FacePatch(
                        name,
                        null,
                        emptyList(),
                        "${name.label} sweeps a piece of the profile this drawing has no surface for — it draws, " +
                            "measures, prints and exports, and it offers no sketch plane and no exact section",
                    )
                else -> inBlendsWords(d, bandCarrier(d, el, piece, name))
            }
        }
    }

    /**
     * The band as the emitter that knows the surface states it — [Section3.sweptFace] for a straight edge,
     * [Revolve3.bandPatch] for a circular one.
     */
    private fun bandCarrier(
        d: Dressing,
        el: Curve3Element,
        piece: ProfileElement,
        name: FaceName,
    ): FacePatch {
        return when (el) {
            is Curve3Element.Seg3 -> {
                val v = el.end - el.start
                val len = v.length()
                if (len <= Geom3.WELD_TOL) {
                    FacePatch(name, null, emptyList(), "${d.edge.name.label} has no length, so its band has no surface")
                } else {
                    val u = v * (1.0 / len)
                    Section3.sweptFace(Plane3(el.start, d.crease.e1, u.cross(d.crease.e1)), u, len, piece, name)
                }
            }
            is Curve3Element.Arc3 -> {
                val (frame, sr) =
                    revolvedBand(d.crease, el, piece) ?: return FacePatch(
                        name,
                        null,
                        emptyList(),
                        "${d.edge.name.label} does not stand square to its own axis, so the band it carries is not a " +
                            "surface of revolution this drawing has a name for",
                    )
                Revolve3.bandPatch(frame, sr, name)
            }
            else ->
                FacePatch(
                    name,
                    null,
                    emptyList(),
                    "${d.edge.name.label} is neither straight nor circular, so its band is a surface this drawing " +
                        "has no name for",
                )
        }
    }

    /**
     * The band's refusal **in the blend's own words**.
     *
     * The two emitters above speak of *"that boundary edge"* and *"that profile edge"*, which is right for
     * an extrusion and a revolution and wrong here: nobody drew this piece — it is the rounding's own
     * section. So the surface is named the same way and the sentence is restated, which is the same rule a
     * sweep's refusals pass through under (session 65: a refusal speaks in the words of the thing the user
     * made, not of the machinery that noticed).
     */
    private fun inBlendsWords(
        d: Dressing,
        patch: FacePatch,
    ): FacePatch {
        if (patch.plane != null || patch.reason == null) return patch
        val what = patch.surface?.band?.label ?: "a surface this drawing has no name for"
        return patch.copy(
            reason =
                "${d.name.label} is $what and not a plane — it is the ${d.kindWord}, so there is nothing to " +
                    "sketch on there; put a datum plane where you want to sketch",
        )
    }

    /**
     * A circular edge read as an **axis frame**, and the blend's section curve read in that frame's own
     * `(s, r)` — which is all [Revolve3.bandOf] ever needed to name a surface.
     *
     * The map is a rigid axis-aligned one because the normal section of a circular edge *contains* the
     * axis: [referenceOf] seeds the section's first axis with the edge plane's normal, so `e1` is the axis
     * and `e2` is the radius, up to a sign each. Where that is not so — an edge whose own plane is not
     * square to the frame it turns in — there is no revolution to name and the caller says so.
     */
    private fun revolvedBand(
        crease: Crease,
        el: Curve3Element.Arc3,
        piece: ProfileElement,
    ): Pair<Revolve3.Frame, ProfileElement>? {
        val axis = el.normal.normalized()
        val sigmaS = crease.e1.dot(axis)
        if (abs(abs(sigmaS) - 1.0) > 1e-7) return null
        val at = crease.ref.at
        val rel = at - el.center
        val s0 = rel.dot(axis)
        val radial = rel - axis * s0
        if (radial.length() <= Geom3.WELD_TOL) return null
        val sigmaR = crease.ref.e2.dot(radial.normalized())
        if (abs(abs(sigmaR) - 1.0) > 1e-7) return null
        val a = Affine(if (sigmaS > 0) 1.0 else -1.0, 0.0, 0.0, if (sigmaR > 0) 1.0 else -1.0, s0, radial.length())
        val turnA = el.startAngle
        val turnB = el.startAngle + el.sweepAngle
        val frame =
            Revolve3.Frame(
                Vec2(1.0, 0.0),
                Vec2(0.0, 1.0),
                Vec2(0.0, 0.0),
                el.center,
                axis,
                el.u,
                el.v,
                min(turnA, turnB),
                max(turnA, turnB),
                abs(el.sweepAngle) >= 2.0 * PI - 1e-9,
            )
        // the map may reverse orientation (either sign is a reflection), and the material-left convention
        // is what [Revolve3.bandOf] reads a flat band's outward side from — so it is re-established *after*
        // the map rather than assumed to survive it
        return frame to (mappedSection(piece, a) ?: return null)
    }

    /** One tangent rail of a band, as a curve in the world — a straight one, or a ring about the edge's axis. */
    private fun railGeom(
        d: Dressing,
        t: Vec2,
    ): Pair<EdgeGeom?, String?> {
        val el = soleElement(d.crease) ?: return null to "${d.edge.name.label} is a chain of several pieces, so its rails have no single curve"
        return when (el) {
            is Curve3Element.Seg3 -> {
                val v = el.end - el.start
                val len = v.length()
                if (len <= Geom3.WELD_TOL) {
                    null to "${d.edge.name.label} has no length"
                } else {
                    val u = v * (1.0 / len)
                    EdgeGeom.Straight(worldOnStraight(d.crease, el.start, u, t, 0.0), worldOnStraight(d.crease, el.start, u, t, len)) to null
                }
            }
            is Curve3Element.Arc3 -> {
                val axis = el.normal.normalized()
                val w = d.crease.ref.at + d.crease.e1 * t.x + d.crease.ref.e2 * t.y
                val rel = w - el.center
                val s = rel.dot(axis)
                val r = (rel - axis * s).length()
                val o = el.center + axis * s
                if (r <= Geom3.WELD_TOL) {
                    EdgeGeom.Straight(o, o) to null
                } else {
                    val full = abs(el.sweepAngle) >= 2.0 * PI - 1e-9
                    val a = min(el.startAngle, el.startAngle + el.sweepAngle)
                    val b = max(el.startAngle, el.startAngle + el.sweepAngle)
                    val ring =
                        if (full) {
                            ProfileElement.CircleE(Circle(Vec2(0.0, 0.0), r), true)
                        } else {
                            ProfileElement.ArcE(Arc(Vec2(0.0, 0.0), r, a, b, true))
                        }
                    EdgeGeom.OnPlane(Plane3(o, el.u, el.v), ring) to null
                }
            }
            else -> null to "${d.edge.name.label} is neither straight nor circular, so its rails have no exact curve"
        }
    }

    // ---- the band, cut ----

    /**
     * Where [cut] crosses the band along base edge [edge] — **exactly**, when that band is a surface of
     * revolution about the edge's own axis, and null when it is not (a straight edge carries a *swept*
     * band instead; see [bandStrip]).
     *
     * The whole of [Revolve3]'s dispatch table applies unchanged, meridian column included, which is the
     * reason this is a delegation and not a second reading: a plane containing the axis cuts the band in
     * the blend's own section curve placed at the two meridian angles, a plane square to it cuts the
     * band's own circles, and the families answer the rest. A blend's torus band on the rim of a turned
     * part is the case that made this necessary — the section a machinist draws is the meridian one.
     */
    fun bandCut(
        f: Feature3.Blend,
        edge: Int,
        part: Int,
        cut: Plane3,
    ): Revolve3.BandCut? {
        val (pieces, at) = bandOf(f, edge) ?: return null
        val piece = pieces[at]
        val el = soleElement(piece.crease) as? Curve3Element.Arc3 ?: return null
        val section = orientedSections(piece).getOrNull(part) ?: return null
        val (frame, sr) = revolvedBand(piece.crease, el, section) ?: return null
        return Revolve3.cutBandOf(frame, sr, cut)
    }

    /**
     * The band along base edge [edge], with **the whole chain's pieces it stands among** — which is what a
     * cut needs, and it is not the same list its own level holds.
     *
     * A band's own **extent** is decided by the corners at its ends, and a corner can be made by a *later*
     * gesture than the one that made the band: round a plate's rim, then round an upright, and the rim's
     * band now stops at the ball. So a band is read at the **tip** — [piecesOf] gathers the level's own
     * targets and the chain under it, and every band in that chain is here — rather than at the level that
     * appended it, which is what session 80's cut *"the band's own face outline is still the full sweep"*
     * came down to for a section.
     */
    private fun bandOf(
        f: Feature3.Blend,
        edge: Int,
    ): Pair<List<Piece>, Int>? {
        val pieces = piecesOf(f) ?: return null
        val at = pieces.indexOfFirst { it.index == edge }
        return if (at < 0) null else pieces to at
    }

    /**
     * Where the band along a **straight** edge stands along its own run at each point of its section: from
     * the corner at one end to the corner at the other, or from end to end where it has none.
     *
     * This is the band's **extent**, and it is a function of the corners as they stand now (see [bandOf]).
     */
    private fun spanOf(
        pieces: List<Piece>,
        at: Int,
        corners: List<Corner>,
    ): (Vec2) -> Pair<Double, Double> {
        val piece = pieces[at]
        val len = piece.length
        val ends = HashMap<Boolean, Placement>()
        for (c in corners) {
            for (e in c.ends) if (e.first == at) ends[e.second] = c.ringAt(e)
            // …and the **upright** a corner turns about is ended by it too, though it is no end of the tool:
            // above that plane the corner's own surface stands where the upright's band used to (session 81)
            if (c is Turn) c.uprightEnd(pieces)?.let { (e, p) -> if (e.first == at) ends[e.second] = p }
        }
        val lo = ends[true]
        val hi = ends[false]
        return { p ->
            (lo?.let { stationOf(piece, it.at(p)) } ?: 0.0) to (hi?.let { stationOf(piece, it.at(p)) } ?: len)
        }
    }

    /**
     * The band along a **straight** edge as its family of rulings — the blend's section curve carried
     * along the edge, one straight ruling per point of that curve, each running only as far as the band
     * itself does ([spanOf]).
     *
     * Exact at every ruling and chords between, which is OP-15's approximated class and exactly what an
     * extrusion's own cylindrical side face gets: the two are the same surface reached by the same sweep,
     * so they are cut by the same machinery ([Section3.cutRuledStrip]) rather than by two. The one cut this
     * cannot answer is a plane **parallel to the rulings** — no ruling crosses it — and that is exactly the
     * one [parallelBandCut] states exactly instead.
     */
    internal fun bandStrip(
        f: Feature3.Blend,
        edge: Int,
        part: Int,
    ): Section3.RuledStrip? {
        val (pieces, at) = bandOf(f, edge) ?: return null
        val piece = pieces[at]
        val el = piece.seg ?: return null
        val section = orientedSections(piece).getOrNull(part) ?: return null
        val v = el.end - el.start
        val len = v.length()
        if (len <= Geom3.WELD_TOL) return null
        val u = v * (1.0 / len)
        val span = spanOf(pieces, at, cornersOf(pieces).list)
        val steps =
            when (section) {
                is ProfileElement.ArcE ->
                    max(BAND_SECTION_STEPS, GeomMath.chordSteps(section.arc.radius, GeomMath.sweep(section.arc), GeomMath.TESS_TOL_MM))
                else -> BAND_SECTION_STEPS
            }
        return Section3.RuledStrip(false, { t ->
            val p = sectionPointAt(section, t) ?: Vec2(0.0, 0.0)
            val (s0, s1) = span(p)
            worldOnStraight(piece.crease, el.start, u, p, s0) to worldOnStraight(piece.crease, el.start, u, p, s1)
        }, steps)
    }

    /**
     * The band along a **straight** edge cut by a plane the edge runs **parallel to** — exactly, or null
     * when that is not the cut this is.
     *
     * *The one case the rulings cannot answer, and the one a rounded plate is usually asked.* Sectioning a
     * rounded box half-way up its top band is a plane parallel to every ruling of that band, so no ruling
     * crosses it and the sampler finds nothing at all — the face came back as its own refusal in a section
     * that plainly does cut it. But the surface is a **cylinder about the band's spine** (or a plane, for a
     * bevel), and a plane parallel to that axis cuts a cylinder in a **pair of rulings** — which is exactly
     * what [Revolve3]'s own table says of it. So the answer is stated: the section curve's own crossings of
     * the cut plane, each carried along the edge over the band's own extent.
     */
    internal fun parallelBandCut(
        f: Feature3.Blend,
        edge: Int,
        part: Int,
        cut: Plane3,
    ): List<ProfileElement>? {
        val (pieces, at) = bandOf(f, edge) ?: return null
        val piece = pieces[at]
        val el = piece.seg ?: return null
        val v = el.end - el.start
        val len = v.length()
        if (len <= Geom3.WELD_TOL) return null
        val u = v * (1.0 / len)
        val n = cut.normal.normalized()
        if (abs(u.dot(n)) > DIR_EPS) return null
        val section = orientedSections(piece).getOrNull(part) ?: return null
        // the section's own points that lie in the cut plane: a straight leg meets it once, an arc twice
        val e1 = piece.crease.e1
        val e2 = piece.crease.ref.e2
        val here = worldOnStraight(piece.crease, el.start, u, Vec2(0.0, 0.0), 0.0)
        val depth = (here - cut.origin).dot(n)
        val hits = sectionOnPlane(section, Vec2(e1.dot(n), e2.dot(n)), -depth)
        if (hits.isEmpty()) return null
        val span = spanOf(pieces, at, cornersOf(pieces).list)
        val out = ArrayList<ProfileElement>(hits.size)
        for (p in hits) {
            val (s0, s1) = span(p)
            if (s1 - s0 <= Geom3.WELD_TOL) continue
            val a = cut.toLocal(worldOnStraight(piece.crease, el.start, u, p, s0))
            val b = cut.toLocal(worldOnStraight(piece.crease, el.start, u, p, s1))
            if ((b - a).length() > Geom3.WELD_TOL) out.add(ProfileElement.Seg(Segment(a, b)))
        }
        return out.ifEmpty { null }
    }

    /** The points of a section curve where `q·[dir] = [off]` — a straight leg's one, an arc's two. */
    private fun sectionOnPlane(
        e: ProfileElement,
        dir: Vec2,
        off: Double,
    ): List<Vec2> {
        if (dir.length() <= DIR_EPS) return emptyList()
        return when (e) {
            is ProfileElement.Seg -> {
                val a = e.segment.a
                val d = e.segment.b - a
                val den = d.dot(dir)
                if (abs(den) <= 1e-12) {
                    emptyList()
                } else {
                    val t = (off - a.dot(dir)) / den
                    if (t < -1e-9 || t > 1.0 + 1e-9) emptyList() else listOf(a + d * t.coerceIn(0.0, 1.0))
                }
            }
            is ProfileElement.ArcE -> {
                val unit = dir.normalized()
                val k = (off / dir.length())
                val c = e.arc.center.dot(unit)
                val h = k - c
                if (abs(h) > e.arc.radius) {
                    emptyList()
                } else {
                    val half = kotlin.math.acos((h / e.arc.radius).coerceIn(-1.0, 1.0))
                    val base = unit.angle()
                    listOf(base + half, base - half)
                        .map { GeomMath.arcPointAt(e.arc, it) to it }
                        .filter { (_, ang) -> onArc(e.arc, ang) }
                        .map { it.first }
                }
            }
            else -> emptyList()
        }
    }

    /** Whether the angle [th] lies within [arc]'s own sweep. */
    private fun onArc(
        arc: Arc,
        th: Double,
    ): Boolean {
        val sweep = GeomMath.sweep(arc)
        var t = th - arc.startAngle
        val two = 2.0 * PI
        if (sweep >= 0.0) {
            while (t < -1e-9) t += two
            while (t > two) t -= two
            return t <= sweep + 1e-9
        }
        while (t > 1e-9) t -= two
        while (t < -two) t += two
        return t >= sweep - 1e-9
    }

    /**
     * The blend's section pieces, each traversed with the material to its left — the band's own generators,
     * one per piece of the section, in the section's own order.
     *
     * A piece whose direction this drawing cannot state — a Bézier, a conic, a function curve — comes back
     * **null in its own slot** rather than sinking the whole list: its band keeps its index and carries a
     * reason, which is OP-15's approximated class exactly as a spline offset gets it, while its neighbours
     * are named and cut exactly.
     */
    private fun orientedSections(
        wedge: Wedge,
        choice: BlendChoice,
    ): List<ProfileElement?> =
        wedge.pieces.map { e ->
            val outward = bandOutward(e, wedge.forward, choice.convex)
            if (outward == null) null else materialLeft(e, outward)
        }

    private fun orientedSections(d: Dressing): List<ProfileElement?> = orientedSections(d.wedge, d.choice)

    private fun orientedSections(p: Piece): List<ProfileElement?> = orientedSections(p.wedge, p.choice)

    private fun sectionPointAt(
        e: ProfileElement,
        t: Double,
    ): Vec2? =
        when (e) {
            is ProfileElement.Seg -> e.segment.a + (e.segment.b - e.segment.a) * t
            is ProfileElement.ArcE -> GeomMath.arcPointAt(e.arc, e.arc.startAngle + GeomMath.sweep(e.arc) * t)
            else -> null
        }

    // ---- the outline correction: the strip a blend takes off each of its two faces ----

    /**
     * [patch]'s own boundary with the strip each of [trims] removed — the piece over the blended edge
     * stepped **inward** by the tangency's distance, and its neighbours re-joined on their own carriers.
     *
     * Analytic, and only for a **planar** face: on a plane the tangency curve is the boundary piece offset
     * by a constant, which is a line beside a line and a circle beside a circle, both of them exact. On a
     * curved face the same strip is an offset *on the surface* — a fact this drawing has no vocabulary for
     * yet — so the caller keeps the face at its index and states the reason instead of drawing a boundary
     * that is not there (OP-3, OP-15's honesty line).
     */
    private fun correctedOutline(
        patch: FacePatch,
        trims: List<Pair<SolidEdge, Double>>,
    ): Pair<List<ProfileElement>?, String?> {
        val plane =
            patch.plane
                ?: return null to
                    "${patch.name.label} is not a plane, so the strip the blend takes off it is an offset on a " +
                    "curved surface — this drawing states that face's own boundary only where it is flat, and " +
                    "the surface itself is unchanged (a future extension)"
        val offsets = HashMap<Int, Double>()
        for ((edge, d) in trims) {
            val hits = patch.outline.indices.filter { sameCurve(plane, patch.outline[it], edge) }
            if (hits.size != 1) {
                return null to
                    "${edge.name.label} matches ${hits.size} pieces of ${patch.name.label}'s own boundary, so the " +
                    "strip the blend takes off it cannot be stated exactly"
            }
            offsets[hits[0]] = (offsets[hits[0]] ?: 0.0) + d
        }
        val out = patch.outline.toMutableList()
        for (chain in chainsOf(patch.outline)) {
            if (chain.none { it in offsets }) continue
            val (fixed, why) = offsetChain(patch.outline, chain, offsets)
            if (fixed == null) return null to "${patch.name.label}: $why"
            for ((k, i) in chain.withIndex()) out[i] = fixed[k]
        }
        return out to null
    }

    /**
     * One ring of a face boundary, with the trimmed pieces stepped in and every corner re-solved.
     *
     * The arithmetic is [GeomMath.offsetCycle]'s — the exact constant offset, shared since session 75 with
     * the **shell**, which takes a wall of constant thickness off a whole profile the way this takes a strip
     * of constant width off one face. What stays here is the wording: the codes come back as the blend's own
     * sentences (session 65's rule).
     */
    private fun offsetChain(
        outline: List<ProfileElement>,
        chain: List<Int>,
        offsets: Map<Int, Double>,
    ): Pair<List<ProfileElement>?, String?> {
        val (fixed, code) = GeomMath.offsetCycle(chain.map { outline[it] }, chain.map { offsets[it] ?: 0.0 })
        if (fixed != null) return fixed to null
        return null to
            when (code) {
                GeomMath.OFFSET_NOT_A_CARRIER ->
                    "one of the pieces beside the blend is neither a straight run nor an arc, so the join cannot be solved"
                GeomMath.OFFSET_NO_JUNCTION -> "the blend's new boundary does not meet the piece beside it"
                else -> "a piece of that boundary is consumed at that size"
            }
    }

    /** The contiguous rings of a face boundary, as index lists — the wrap staying inside its own ring. */
    private fun chainsOf(outline: List<ProfileElement>): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        var cur = ArrayList<Int>()
        for (i in outline.indices) {
            if (cur.isNotEmpty() && (GeomMath.startOf(outline[i]) - GeomMath.endOf(outline[cur.last()])).length() > SAME_CURVE_TOL) {
                out.add(cur)
                cur = ArrayList()
            }
            cur.add(i)
            if ((GeomMath.endOf(outline[i]) - GeomMath.startOf(outline[cur.first()])).length() <= SAME_CURVE_TOL) {
                out.add(cur)
                cur = ArrayList()
            }
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    /**
     * Whether boundary piece [e] of the face in [plane] **is** the edge [edge] — the same curve in the
     * world, however each of the two happens to be traversed or indexed.
     *
     * *Matching rather than indexing, and stated as a decision.* The face outline's index space and the
     * edge list's are not the same space and cannot be made so by fiat: an extrusion's cap outline is its
     * footprint loop *mapped* (and the bottom cap's map is a reflection, which re-orients the ring — the
     * very fact `CAP_EDGE_CONVENTION` exists to keep out of the edge indices), a revolution's flat band
     * carries its own generated boundary, and a side face carries a rectangle. Rather than a case per
     * feature per cap, the two exact constructions are compared **as curves**: both come from the same
     * parameters, so they agree to the last bits, and comparing them discovers nothing that was not
     * constructed (OP-8's rule is about not reading names out of triangles, and there are no triangles here).
     */
    private fun sameCurve(
        plane: Plane3,
        e: ProfileElement,
        edge: SolidEdge,
    ): Boolean {
        fun near(
            a: Vec3,
            b: Vec3,
        ) = (a - b).length() <= SAME_CURVE_TOL
        val ends = listOf(plane.toWorld(GeomMath.startOf(e)), plane.toWorld(GeomMath.endOf(e)))
        return when (val g = edge.geom) {
            is EdgeGeom.Straight -> {
                if (e !is ProfileElement.Seg) {
                    false
                } else {
                    (near(ends[0], g.a) && near(ends[1], g.b)) || (near(ends[0], g.b) && near(ends[1], g.a))
                }
            }
            is EdgeGeom.OnPlane -> {
                val other = listOf(g.plane.toWorld(GeomMath.startOf(g.piece)), g.plane.toWorld(GeomMath.endOf(g.piece)))
                val sameEnds =
                    (near(ends[0], other[0]) && near(ends[1], other[1])) || (near(ends[0], other[1]) && near(ends[1], other[0]))
                if (!sameEnds) {
                    false
                } else {
                    val ca = centreAndRadius(plane, e)
                    val cb = centreAndRadius(g.plane, g.piece)
                    when {
                        ca == null && cb == null -> e is ProfileElement.Seg && g.piece is ProfileElement.Seg
                        ca == null || cb == null -> false
                        else -> near(ca.first, cb.first) && abs(ca.second - cb.second) <= SAME_CURVE_TOL
                    }
                }
            }
        }
    }

    /** A curved piece's world centre and radius — null for a straight one, which its endpoints already fix. */
    private fun centreAndRadius(
        plane: Plane3,
        e: ProfileElement,
    ): Pair<Vec3, Double>? =
        when (e) {
            is ProfileElement.ArcE -> plane.toWorld(e.arc.center) to e.arc.radius
            is ProfileElement.CircleE -> plane.toWorld(e.circle.center) to e.circle.radius
            else -> null
        }

    const val NO_FACE_UNDER_CLICK =
        "no flat face of this solid lies under that click as this space looks at it — click on the face whose " +
            "edges you want broken, or orbit until you can see it"

    /**
     * The face of edge [edgeIndex] that [from] **looks at**: of the two the edge separates, the one that is a
     * plane, is not edge-on to this view, and stands nearest the eye.
     *
     * The second reading of a face pick, and the one that matters in practice: a solid is clicked by its
     * footprint, which *is* a cap's own outline, so the click that names a body is a click on that cap's
     * boundary rather than inside it. Rather than give the pick a tolerance of its own — a pixel measure that
     * has no business in the kernel — the click is read as the edge it landed on and the face is the one that
     * edge is seen from. Deterministic, and the same *nearest the eye* rule [faceNear] uses.
     */
    fun faceOfEdgeToward(
        feature: Feature3,
        edgeIndex: Int,
        from: Plane3,
    ): Pair<Int?, String?> {
        val (edges, whyEdges) = Section3.edges(feature)
        if (edges == null) return null to whyEdges
        val edge = edges.getOrNull(edgeIndex) ?: return null to "this solid has no edge #${edgeIndex + 1}"
        val (faces, whyFaces) = Section3.faces(feature)
        if (faces == null) return null to whyFaces
        val d = from.normal.normalized()
        var best: Int? = null
        var bestReach = -Double.MAX_VALUE
        for (i in faces.indices) {
            if (!edge.between.has(faces[i].name)) continue
            val plane = faces[i].plane ?: continue
            val n = plane.normal.normalized()
            if (abs(d.dot(n)) <= 1e-9) continue
            val reach = (plane.origin - from.origin).dot(n) / d.dot(n)
            if (reach > bestReach) {
                bestReach = reach
                best = i
            }
        }
        return best to (if (best == null) NO_FACE_UNDER_CLICK else null)
    }
}
