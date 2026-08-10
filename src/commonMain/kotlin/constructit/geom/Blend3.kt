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
    ): Pair<List<Int>?, String?> {
        val (edges, whyEdges) = Section3.edges(feature)
        if (edges == null) return null to whyEdges
        if (!whole) {
            if (address < 0 || address >= edges.size) {
                return null to "this solid has no edge #${address + 1} (it has ${edges.size})"
            }
            return listOf(address) to null
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
     * One sweep and one boolean **per edge**, deliberately, rather than one sweep per tangent-continuous run:
     * whether two pieces meet tangentially is a property of *values*, and a construction whose number of
     * sweeps moved with the geometry would be structure decided at eval time (OP-21's rule). Two wedges either
     * side of a tangency corner abut on exactly the same section in exactly the same plane, so their union is
     * the one smooth ribbon with no crack in it; at a **sharp** corner they overlap and the boolean trims
     * them, leaving the corner itself sharp — a vertex blend where three or more edges meet is the named
     * future extension.
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
        var result = applyTo
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
            val (tool, whyTool) =
                Geom3.sweep(crease.path, crease.e1, SweepProfile.Section(wedge.region), plan = null)
            if (tool == null) return null to "${edge.name.label}: ${whyTool ?: "the blend cannot be swept along it"}"
            val (next, whyBool) = Geom3.combine(if (choice.convex) BoolOp.SUBTRACT else BoolOp.UNION, result, tool)
            if (next == null) {
                return null to "${edge.name.label}: ${whyBool ?: "the blend cannot be applied to this body"}"
            }
            result = next
        }
        return result to null
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
