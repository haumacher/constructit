package constructit.geom

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
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
 * dimension down. With **two straight legs** (a fillet or a chamfer alike) they are the quadrant:
 * `+1` along each leg's own direction, `-1` against it, exactly what [FilletMath.lineLineArc] and
 * [FilletMath.chamferEnds] take, and [branch] is unused. With **a round leg** (a mixed fillet) they are
 * [FilletVariant]'s two offset sides and [branch] is its intersection branch. [convex] is `true` when the
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
 * 3. The blend is the arc of that fillet (or [FilletMath.chamferEnds]'s bevel) closed back to the corner —
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
 * **The cost is stated too**: the boolean takes the mesh route (the operands share no axis), so a blended
 * body is `Feature3.MeshBoolean` — it draws, measures, prints and picks, and it offers no section inputs.
 * Slice 3's `Feature3.Blend` is the cure and is queued.
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

    /** The two tangencies of a blend, and the wedge it fills the corner with. */
    private class Wedge(val region: Region, val t1: Vec2, val t2: Vec2)

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
            loop = Loop(listOf(sidePiece(crease.leg1, Vec2(0.0, 0.0), t1), ProfileElement.ArcE(arc), sidePiece(crease.leg2, t2, Vec2(0.0, 0.0))))
        } else {
            val l1 = crease.leg1.line
            val l2 = crease.leg2.line
            if (l1 == null || l2 == null) {
                return null to
                    "a chamfer bevels a corner between two straight legs, and at ${crease.edge.name.label} " +
                    "${(if (l1 == null) crease.face1 else crease.face2).name.label} is curved in section — " +
                    "fillet it instead (a chamfer across a curved leg is a future extension)"
            }
            val bevel = FilletMath.chamferEnds(l1, l2, size, choice.a, choice.b) ?: return null to notFitting(crease, size, kind)
            t1 = bevel.a
            t2 = bevel.b
            loop = Loop(listOf(ProfileElement.Seg(Segment(Vec2(0.0, 0.0), t1)), ProfileElement.Seg(bevel), ProfileElement.Seg(Segment(t2, Vec2(0.0, 0.0)))))
        }
        val oriented = if (GeomMath.signedArea(loop) >= 0.0) loop else GeomMath.reverseLoop(loop)
        if (abs(GeomMath.signedArea(oriented)) <= Geom3.WELD_TOL * Geom3.WELD_TOL) {
            return null to "a ${kind.word} of ${Frames3.mm(size)} mm leaves no material at ${crease.edge.name.label}"
        }
        return Wedge(Region(oriented, emptyList()), t1, t2) to null
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
                if (!RegionBool.contains(rings, plane.toLocal(world))) return false
            }
        }
        return true
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
                if (l1 != null && l2 != null) {
                    // two straight legs, fillet and chamfer alike: what is stored is **which way along each
                    // leg the corner opens**, the quadrant [FilletMath.legSigns] scores one dimension down
                    val step = min(size, crease.length / 2.0) * PROBE_FRACTION
                    val sign1 = if (sideOf(crease.leg2, l1.dir * step) == s2) 1 else -1
                    val sign2 = if (sideOf(crease.leg1, l2.dir * step) == s1) 1 else -1
                    BlendChoice(sign1, sign2, 0, convex)
                } else if (kind == BlendKind.CHAMFER) {
                    // scored anyway, so the build's own refusal is the one that speaks (it names the leg)
                    BlendChoice(1, 1, 0, convex)
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
     * **[base] states the geometry and [applyTo] takes the cut**, and they are the same body for a first
     * blend. They part company for the second one: a blended body is a `Feature3.MeshBoolean`, which names no
     * edges (OP-9's sink rule), so its own edges could never be addressed — the addresses stay against the
     * **analytic base**, which still has them, while the wedge is applied to the body as it stands so that
     * blends *chain* instead of forking the model back onto the original. The pair is exactly the
     * part-and-tip split OP-17's sequential features already make, said one operation further, and it is what
     * lets slice 3's `Feature3.Blend` supersede this without changing a single stored address.
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
