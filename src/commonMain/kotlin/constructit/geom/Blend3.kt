package constructit.geom

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Which blend a corner gets — the 2D pair, one dimension up.
 *
 * **Structural, never a value** (OP-1's rule, the same one [Handedness] and [CarryMode] follow): which of the
 * two a blend is, is which tool row was used, so it is recorded in the step and can never change by itself.
 */
enum class BlendKind {
    FILLET,
    CHAMFER,
    ;

    /** The word a refusal and a status line use. */
    val word: String get() = if (this == FILLET) "fillet" else "chamfer"

    /** What the scalar this kind takes is called. */
    val sizeWord: String get() = if (this == FILLET) "radius" else "setback"
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
data class BlendChoice(val a: Int, val b: Int, val branch: Int, val convex: Boolean) {
    /** This choice as the four integers a step restates. */
    fun signs(): List<Int> = listOf(a, b, branch, if (convex) 1 else -1)

    companion object {
        /** The choice four restated integers name, or null when there are not four of them. */
        fun of(signs: List<Int>): BlendChoice? =
            if (signs.size < 4) null else BlendChoice(signs[0], signs[1], signs[2], signs[3] >= 0)
    }
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
        return hits to null
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
        size: Double,
    ): Pair<Triple<Int, Int, Boolean>?, String?> {
        val n1 = normalOf(crease.leg1) ?: return null to "${crease.edge.name.label}: ${crease.face1.name.label} has no side at that edge"
        val n2 = normalOf(crease.leg2) ?: return null to "${crease.edge.name.label}: ${crease.face2.name.label} has no side at that edge"
        var scale = min(size, crease.length / 2.0)
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
    private class Wedge(val region: Region, val t1: Vec2, val t2: Vec2, val piece: ProfileElement)

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
        size: Double,
        kind: BlendKind,
        choice: BlendChoice,
    ): Pair<Wedge?, String?> {
        val loop: Loop
        val t1: Vec2
        val t2: Vec2
        val blendPiece: ProfileElement
        if (kind == BlendKind.FILLET) {
            val straight1 = crease.leg1.line
            val straight2 = crease.leg2.line
            val arc =
                if (straight1 != null && straight2 != null) {
                    // two straight legs: the corner is a real point, so the fillet is the quadrant
                    // construction ([FilletMath.lineLineArc]) — the same split the 2D tool makes, for the
                    // same reason (two offset *lines* meet in one point and there is no branch to pick)
                    FilletMath.lineLineArc(straight1, straight2, size, choice.a, choice.b)
                        ?: return null to notFitting(crease, size, kind)
                } else {
                    FilletMath.arcOf(crease.leg1, crease.leg2, size, FilletVariant(choice.a, choice.b, choice.branch))
                        ?: return null to notFitting(crease, size, kind)
                }
            t1 = arc.center + Vec2(cos(arc.startAngle), sin(arc.startAngle)) * arc.radius
            t2 = arc.center + Vec2(cos(arc.endAngle), sin(arc.endAngle)) * arc.radius
            blendPiece = ProfileElement.ArcE(arc)
            loop = Loop(listOf(sidePiece(crease.leg1, Vec2(0.0, 0.0), t1), ProfileElement.ArcE(arc), sidePiece(crease.leg2, t2, Vec2(0.0, 0.0))))
        } else {
            // **the chamfer-on-arc convention, inherited** (session 76, item c): the corner of the section is
            // the origin here, so each setback point is that distance from it **along its own leg** — a step
            // along a straight one, an arc distance along a round one ([FilletMath.setback], where the
            // convention is argued). The wedge is then closed with [sidePiece] exactly as the fillet's is, so
            // the two kinds differ in one piece — the bevel where the arc was — and in nothing else. For two
            // straight legs this is [FilletMath.chamferEnds] point for point, so no dressed body changes.
            val corner = Vec2(0.0, 0.0)
            val a = FilletMath.setback(crease.leg1, corner, size, choice.a) ?: return null to notFitting(crease, size, kind)
            val b = FilletMath.setback(crease.leg2, corner, size, choice.b) ?: return null to notFitting(crease, size, kind)
            if ((b - a).length() <= Geom3.WELD_TOL) return null to notFitting(crease, size, kind)
            val bevel = Segment(a, b)
            t1 = a
            t2 = b
            blendPiece = ProfileElement.Seg(bevel)
            loop = Loop(listOf(sidePiece(crease.leg1, corner, t1), ProfileElement.Seg(bevel), sidePiece(crease.leg2, t2, corner)))
        }
        val oriented = if (GeomMath.signedArea(loop) >= 0.0) loop else GeomMath.reverseLoop(loop)
        if (abs(GeomMath.signedArea(oriented)) <= Geom3.WELD_TOL * Geom3.WELD_TOL) {
            return null to "a ${kind.word} of ${Frames3.mm(size)} mm leaves no material at ${crease.edge.name.label}"
        }
        return Wedge(Region(oriented, emptyList()), t1, t2, blendPiece) to null
    }

    private fun notFitting(
        crease: Crease,
        size: Double,
        kind: BlendKind,
    ): String =
        "no ${kind.word} of ${kind.sizeWord} ${Frames3.mm(size)} mm fits between ${crease.face1.name.label} and " +
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
        size: Double,
        kind: BlendKind,
        choice: BlendChoice,
    ): Double {
        var lo = 0.0
        var hi = size
        repeat(FIT_STEPS) {
            val mid = (lo + hi) / 2.0
            val (w, _) = wedgeOf(crease, mid, kind, choice)
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
        /** The wedge's boundary, counter-clockwise in the section frame, starting at the section's corner. */
        val section: List<Vec2>,
        /** The same boundary grown out through both faces, point for point — see [sectionPolygons]. */
        val grown: List<Vec2>,
        /** The same polygon triangulated once — the tool's cap at every free end. */
        val caps: List<Geom3.Tri3>,
        /** The edge as one straight run, or null: only a straight edge can carry a mitre (see [jointsOf]). */
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
    )

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
        val arc = GeomMath.tessellatePiece(wedge.piece, GeomMath.TESS_TOL_MM)
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
        return Piece(index, existing, crease, wedge, choice, plain, grown, caps, soleElement(crease) as? Curve3Element.Seg3) to null
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
                    val wedge = wedgeOf(crease, f.size, f.kind, choice).first ?: continue
                    out.add(pieceOf(i, true, crease, wedge, choice).first ?: continue)
                }
            }
            f = below
        }
        return out
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
    private fun jointsOf(pieces: List<Piece>): List<Joint> {
        val out = ArrayList<Joint>()
        val taken = HashSet<Pair<Int, Boolean>>()
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
                        // **a corner that turns the other way is not this corner** (a reflex corner of the
                        // shared face): the mitre then stands *outside* both edges and the two bands do not
                        // overlap at all but leave a wedge between them, which is the inside-corner patch a
                        // ball rolls round — a future extension. Left to overlap and be trimmed, as before.
                        if (!turnsInward(a, aAtStart, bis) || !turnsInward(b, bAtStart, bis)) continue
                        out.add(Joint(i, aAtStart, placeA, j, bAtStart, placeB, shared))
                        taken.add(i to aAtStart)
                        taken.add(j to bAtStart)
                    }
                }
            }
        }
        return out
    }

    /**
     * Whether the corner's bisector turns **into** the shared face, which is what says the corner is a
     * convex one — `bis·d = cos(θ/2)` along the edge's own direction out of the corner, so this is the
     * statement `θ < 180°` and nothing more. See the reflex-corner note in [jointsOf].
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
        joints: List<Joint>,
    ): Joint? {
        val reach = HashMap<Int, Double>()
        val blame = HashMap<Int, Joint>()
        for (j in joints) {
            for ((who, pair) in listOf(j.a to (j.aAtStart to j.placeA), j.b to (j.bAtStart to j.placeB))) {
                val r = reachOf(pieces[who], pair.first, pair.second)
                reach[who] = (reach[who] ?: 0.0) + r
                if (blame[who] == null) blame[who] = j
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
        joints: List<Joint>,
    ): List<List<Int>> {
        val owner = IntArray(count) { it }

        fun root(x: Int): Int {
            var r = x
            while (owner[r] != r) r = owner[r]
            return r
        }
        for (j in joints) {
            val ra = root(j.a)
            val rb = root(j.b)
            if (ra != rb) owner[max(ra, rb)] = min(ra, rb)
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
    ): Pair<Mesh3?, String?> {
        val b = Geom3.MeshBuilder()
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
        size: Double,
        kind: BlendKind,
    ): Pair<List<BlendChoice>?, String?> {
        val feature = base.feature
        val (edges, whyEdges) = Section3.edges(feature)
        if (edges == null) return null to whyEdges
        val out = ArrayList<BlendChoice>(targets.size)
        for (i in targets) {
            val edge = edges.getOrNull(i) ?: return null to "this solid has no edge #${i + 1}"
            val (crease, why) = creaseOf(feature, edge)
            if (crease == null) return null to why
            val (sector, whySector) = sectorOf(crease, base.mesh, size)
            if (sector == null) return null to whySector
            val (s1, s2, convex) = sector
            val l1 = crease.leg1.line
            val l2 = crease.leg2.line
            out.add(
                if ((l1 != null && l2 != null) || kind == BlendKind.CHAMFER) {
                    // **which way along each leg the corner opens** — the quadrant [FilletMath.legSigns]
                    // scores one dimension down, and what a chamfer stores whatever its legs are (session 76:
                    // the setback runs along the carrier, so the direction along it *is* the choice). The
                    // probe walks the leg itself rather than its tangent, which for a straight leg is the
                    // very same point and for a round one is exactly on the carrier.
                    val step = min(size, crease.length / 2.0) * PROBE_FRACTION
                    val q1 = FilletMath.setback(crease.leg1, Vec2(0.0, 0.0), step, 1)
                    val q2 = FilletMath.setback(crease.leg2, Vec2(0.0, 0.0), step, 1)
                    if (q1 == null || q2 == null) return null to notFitting(crease, size, kind)
                    val sign1 = if (sideOf(crease.leg2, q1) == s2) 1 else -1
                    val sign2 = if (sideOf(crease.leg1, q2) == s1) 1 else -1
                    BlendChoice(sign1, sign2, 0, convex)
                } else {
                    // at least one round leg: the mixed fillet's stored variant — which side each leg is
                    // offset to, and which of the two intersections of those offsets the centre is
                    val centres =
                        FilletMath.centres(crease.leg1, crease.leg2, size, s1, s2)?.takeIf { it.isNotEmpty() }
                            ?: return null to notFitting(crease, size, kind)
                    val branch = if ((centres.first()).length() <= (centres.last()).length()) 1 else -1
                    BlendChoice(s1, s2, branch, convex)
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
     * [jointsOf]; an **inside** corner and a vertex where three or more blended edges meet remain the named
     * future extensions.
     */
    fun blended(
        base: Solid3,
        applyTo: Solid3,
        targets: List<Int>,
        size: Double,
        kind: BlendKind,
        choices: List<BlendChoice>,
    ): Pair<Solid3?, String?> {
        if (size <= Geom3.WELD_TOL) return null to "a ${kind.word} needs a positive ${kind.sizeWord} — this one is ${Frames3.mm(size)} mm"
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
            val (wedge, whyWedge) = wedgeOf(crease, size, kind, choice)
            if (wedge == null) return null to whyWedge
            if (!tangenciesFit(crease, wedge)) {
                val fits = largestFitting(crease, size, kind, choice)
                return null to
                    "a ${kind.word} of ${kind.sizeWord} ${Frames3.mm(size)} mm reaches past ${crease.face1.name.label} " +
                    "or ${crease.face2.name.label} at ${crease.edge.name.label} — the largest that fits there is " +
                    "about ${Frames3.mm(fits)} mm"
            }
            val (piece, whyPiece) = pieceOf(i, false, crease, wedge, choice)
            if (piece == null) return null to whyPiece
            pieces.add(piece)
        }
        // …and the bands already under this one, so a blend of a blend on an adjacent edge builds the same
        // corner a one-gesture chain would (GitHub #27, [chainPieces]).
        pieces.addAll(chainPieces(feature))
        val joints = jointsOf(pieces)
        crowdedCorner(pieces, joints)?.let { j ->
            val a = pieces[j.a]
            val b = pieces[j.b]
            val fits = largestCornerFitting(pieces, size, kind)
            return null to
                "the corner where ${a.crease.edge.name.label} meets ${b.crease.edge.name.label} on " +
                "${j.shared.name.label} is too sharp for a ${kind.word} of ${kind.sizeWord} " +
                "${Frames3.mm(size)} mm — the corner the two roundings share would reach further along an edge " +
                "than the edge is long. The largest that fits there is about ${Frames3.mm(fits)} mm"
        }
        val rings = HashMap<Pair<Int, Boolean>, Placement>()
        for (j in joints) {
            rings[j.a to j.aAtStart] = j.placeA
            rings[j.b to j.bAtStart] = j.placeB
        }
        var result = applyTo
        for (group in groupsOf(pieces.size, joints)) {
            // a group of nothing but bands already off the body has nothing left to cut
            if (group.all { pieces[it].existing }) continue
            val fresh = group.filter { !pieces[it].existing }
            val lead = pieces[fresh.first()]
            val (tool, whyTool) =
                if (fresh.size == 1 && group.size == 1) {
                    Geom3.sweep(lead.crease.path, lead.crease.e1, SweepProfile.Section(lead.wedge.region), plan = null)
                } else {
                    val (mesh, whyMesh) = toolMesh(pieces, group, rings, buttEnds(pieces, group, rings))
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
            val (next, whyBool) = Geom3.combine(op, result, tool)
            if (next == null) {
                return null to "${lead.crease.edge.name.label}: ${whyBool ?: "the blend cannot be applied to this body"}"
            }
            result = next
        }
        return result to null
    }

    /**
     * The largest size whose corners all have room, by halving — what the crowded-corner refusal names so it
     * can be acted on, the same shape of answer [largestFitting] gives for a size that outgrows a face.
     */
    private fun largestCornerFitting(
        pieces: List<Piece>,
        size: Double,
        kind: BlendKind,
    ): Double {
        var lo = 0.0
        var hi = size
        repeat(FIT_STEPS) {
            val mid = (lo + hi) / 2.0
            val trial = ArrayList<Piece>(pieces.size)
            var ok = true
            for (p in pieces) {
                val w = wedgeOf(p.crease, mid, kind, p.choice).first
                val q = if (w == null) null else pieceOf(p.index, p.existing, p.crease, w, p.choice).first
                if (q == null) {
                    ok = false
                    break
                }
                trial.add(q)
            }
            if (ok && crowdedCorner(trial, jointsOf(trial)) == null) lo = mid else hi = mid
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
        val kind: BlendKind,
    ) {
        val name: FaceName get() = FaceName.BlendBand(index)

        /** How this band is spoken of when it is the *thing* rather than the address — "the fillet". */
        val kindWord: String get() = "${kind.word} itself"
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
            val (wedge, whyWedge) = wedgeOf(crease, f.size, f.kind, choice)
            if (wedge == null) return null to whyWedge
            out.add(Dressing(i, edge, crease, wedge, choice, f.kind))
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
        for (patch in baseFaces) {
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
        for (d in dressings) out.add(bandPatchOf(d))
        return out to null
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
                            "${e.name.label} was rounded away by the ${f.kind.word} of ${Frames3.mm(f.size)} mm — " +
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
     * The **outward normal of the blend's own surface**, in the section's coordinates, at [q] on it.
     *
     * One rule for both kinds and both signs: the blend's surface faces the **corner** when the wedge was
     * subtracted (a convex edge — the material is on the far side of it) and away from the corner when the
     * wedge was added (a concave one). For a fillet that is `q − centre`, flipped on a concave edge; for a
     * chamfer it is the bevel's perpendicular chosen the same way.
     */
    private fun bandOutward(
        wedge: Wedge,
        choice: BlendChoice,
        q: Vec2,
    ): Vec2? {
        val sign = if (choice.convex) 1.0 else -1.0
        return when (val e = wedge.piece) {
            is ProfileElement.ArcE -> {
                val d = q - e.arc.center
                if (d.length() <= Vec2.EPS) null else d.normalized() * sign
            }
            is ProfileElement.Seg -> {
                val dir = (e.segment.b - e.segment.a)
                if (dir.length() <= Vec2.EPS) {
                    null
                } else {
                    val n = dir.normalized().perp()
                    // toward the corner (the section's origin) when convex, away from it when concave
                    val toward = if (n.dot(Vec2(0.0, 0.0) - q) >= 0.0) n else n * -1.0
                    toward * sign
                }
            }
            else -> null
        }
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
    private fun bandPatchOf(d: Dressing): FacePatch {
        val name = d.name
        val el =
            soleElement(d.crease)
                ?: return FacePatch(name, null, emptyList(), "${d.edge.name.label} is a chain of several pieces, so its band has no single surface")
        val piece =
            orientedSection(d)
                ?: return FacePatch(name, null, emptyList(), "the ${d.crease.edge.name.label} blend has no section curve with a side")
        return inBlendsWords(d, bandCarrier(d, el, piece))
    }

    /**
     * The band as the emitter that knows the surface states it — [Section3.sweptFace] for a straight edge,
     * [Revolve3.bandPatch] for a circular one.
     */
    private fun bandCarrier(
        d: Dressing,
        el: Curve3Element,
        piece: ProfileElement,
    ): FacePatch {
        val name = d.name
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
                    revolvedBand(d, el, piece) ?: return FacePatch(
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
        d: Dressing,
        el: Curve3Element.Arc3,
        piece: ProfileElement,
    ): Pair<Revolve3.Frame, ProfileElement>? {
        val axis = el.normal.normalized()
        val sigmaS = d.crease.e1.dot(axis)
        if (abs(abs(sigmaS) - 1.0) > 1e-7) return null
        val at = d.crease.ref.at
        val rel = at - el.center
        val s0 = rel.dot(axis)
        val radial = rel - axis * s0
        if (radial.length() <= Geom3.WELD_TOL) return null
        val sigmaR = d.crease.ref.e2.dot(radial.normalized())
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
        val moved = GeomMath.transform(piece, a)
        val (mid, _) = midOf(d.wedge.piece) ?: return null
        val outward = bandOutward(d.wedge, d.choice, mid) ?: return null
        return frame to materialLeft(moved, a.linear(outward).normalized())
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
        cut: Plane3,
    ): Revolve3.BandCut? {
        val d = dressingOf(f, edge) ?: return null
        val el = soleElement(d.crease) as? Curve3Element.Arc3 ?: return null
        val (frame, sr) = revolvedBand(d, el, orientedSection(d) ?: return null) ?: return null
        return Revolve3.cutBandOf(frame, sr, cut)
    }

    /**
     * The band along a **straight** edge as its family of rulings — the blend's section curve carried
     * along the edge, one straight ruling per point of that curve.
     *
     * Exact at every ruling and chords between, which is OP-15's approximated class and exactly what an
     * extrusion's own cylindrical side face gets: the two are the same surface reached by the same sweep,
     * so they are cut by the same machinery ([Section3.cutRuledStrip]) rather than by two.
     */
    internal fun bandStrip(
        f: Feature3.Blend,
        edge: Int,
    ): Section3.RuledStrip? {
        val d = dressingOf(f, edge) ?: return null
        val el = soleElement(d.crease) as? Curve3Element.Seg3 ?: return null
        val piece = orientedSection(d) ?: return null
        val v = el.end - el.start
        val len = v.length()
        if (len <= Geom3.WELD_TOL) return null
        val u = v * (1.0 / len)
        val steps =
            when (piece) {
                is ProfileElement.ArcE -> max(BAND_SECTION_STEPS, GeomMath.chordSteps(piece.arc.radius, GeomMath.sweep(piece.arc), GeomMath.TESS_TOL_MM))
                else -> BAND_SECTION_STEPS
            }
        return Section3.RuledStrip(false, { t ->
            val p = sectionPointAt(piece, t) ?: Vec2(0.0, 0.0)
            worldOnStraight(d.crease, el.start, u, p, 0.0) to worldOnStraight(d.crease, el.start, u, p, len)
        }, steps)
    }

    private fun dressingOf(
        f: Feature3.Blend,
        edge: Int,
    ): Dressing? = dressingsOf(f).first?.firstOrNull { it.index == edge }

    /** The blend's section curve, traversed with the material to its left — the band's own generator. */
    private fun orientedSection(d: Dressing): ProfileElement? {
        val (mid, _) = midOf(d.wedge.piece) ?: return null
        val outward = bandOutward(d.wedge, d.choice, mid) ?: return null
        return materialLeft(d.wedge.piece, outward)
    }

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
