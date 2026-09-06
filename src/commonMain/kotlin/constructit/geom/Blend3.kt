package constructit.geom

import constructit.l10n.Msg
import constructit.l10n.Msgs
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
    val word: Msg get() =
        when (this) {
            FILLET -> Msgs.wordBlendFillet()
            CHAMFER -> Msgs.wordBlendChamfer()
            PROFILE -> Msgs.wordBlendProfile()
        }

    /** The branch this kind selects in an ICU `select` — the enumeration inside a sentence (OP-29). */
    val selector: String get() = name.lowercase()

    /** What the scalar this kind takes is called — the drawn profile itself, for the general tier. */
    val sizeWord: Msg get() =
        when (this) {
            FILLET -> Msgs.wordBlendSizeRadius()
            CHAMFER -> Msgs.wordBlendSizeSetback()
            PROFILE -> Msgs.wordBlendSizeProfile()
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
    fun fitPhrase(k: Double): Msg =
        if (kind != BlendKind.PROFILE) {
            Msgs.phraseBlendAboutSize(mm = Frames3.mm(size * k))
        } else {
            Msgs.phraseBlendAboutFraction(percent = Frames3.mm(k * 100.0))
        }

    /** How a refusal names the size this section *is*. */
    fun sizePhrase(): Msg =
        if (kind != BlendKind.PROFILE) {
            Msgs.phraseBlendOfSize(sizeWord = kind.sizeWord, mm = Frames3.mm(size))
        } else {
            Msgs.phraseBlendOfThatProfile()
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
    /**
     * **The instrument**: how many dressed lists have been *derived* rather than read off a memo since
     * [resetDerivations] (GitHub #35).
     *
     * An observer of a derivation and never a second definition of one, exactly as [Solid3.meterTo] is: the
     * lists are the same lists whether anybody is counting. It exists because the defect this counts is a
     * **cost**, and a cost is asserted by counting the work, not by reading a clock (OP-15 — a test that
     * timed this would be a test that fails on a loaded machine).
     */
    var derivations: Int = 0
        private set

    /** Set [derivations] back to zero — the test's own bookend. */
    fun resetDerivations() {
        derivations = 0
    }

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

    /** How many halvings a walk parameter is found by — tighter than [FIT_STEPS], since a chain has to meet. */
    private const val BISECT_STEPS = 48

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
    ): Pair<List<Int>?, Msg?> {
        val (edges, whyEdges) = Section3.edges(feature)
        if (edges == null) return null to whyEdges
        if (!whole) {
            if (address < 0 || address >= edges.size) {
                return null to Msgs.refusalBlendThisSolidHasNoEdge(address = address + 1, count = edges.size)
            }
            return runThrough(edges, address, sameRun) to null
        }
        val (faces, whyFaces) = Section3.faces(feature)
        if (faces == null) return null to whyFaces
        if (address < 0 || address >= faces.size) {
            return null to Msgs.refusalBlendThisSolidHasNoFace(address = address + 1, count = faces.size)
        }
        val face = faces[address].name
        val hits = edges.indices.filter { edges[it].between.has(face) }
        if (hits.isEmpty()) return null to Msgs.refusalBlendHasNoEdgesBlend(name = face.label)
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
                Msgs.refusalBlendEveryEdgeHasAlreadyBeen(name = face.label)
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
        // …and a **corner curve** is no rail at all: it stands after every rail (OP-31 item 3), and whether
        // it is a crease is said on the entry itself ([SolidEdge.reason]) rather than here
        if (index >= below.size + 2 * feature.targets.size) return false
        // …of **that rail's own rounding**, since one pass may run several sections (OP-30's next step): the
        // rails append two per target in the feature's order, so the target is the pair's index
        val k = (index - below.size) / 2
        if (feature.isAbsent(k)) return false
        return feature.sections.getOrNull(k)?.kind == BlendKind.FILLET
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
        /**
         * The same two faces on the **undressed** body — what [tangenciesFit] asks, and only it.
         *
         * *Whether a section fits between two faces is a question about the body, not about what other
         * roundings have already taken off them* (session 81; session 80's own lesson carried to its end).
         * A dressed face's boundary steps inward wherever a neighbour was rounded, so asking the dressed
         * one made the answer depend on **how many gestures** the same dressing had been delivered in — a
         * plate whose two opposite rims are rounded until they meet (a bullnose, an ordinary thing to want)
         * built in one pass and was refused as a chain. Asking the undressed face makes the two agree,
         * which is OP-30's own invariant, and it still catches the case the refusal exists for: a 20 mm
         * round on the rim of a 10 mm plate reaches past the plate itself.
         */
        val base1: FacePatch,
        val base2: FacePatch,
        val length: Double,
    )

    /**
     * The edge as a **curve in space** — the lift (OP-26), so the sweep carries the blend along the exact
     * carrier the edge already is: a straight one stays a segment, a cap edge's arc stays an [Curve3Element.Arc3].
     */
    fun edgePath(edge: SolidEdge): Pair<Path3?, Msg?> = pathOf(edge)

    private fun pathOf(edge: SolidEdge): Pair<Path3?, Msg?> =
        when (val g = edge.geom) {
            is EdgeGeom.Straight -> {
                if ((g.b - g.a).length() <= Geom3.WELD_TOL) {
                    null to Msgs.refusalBlendIsSinglePointSoThere(name = edge.name.label)
                } else {
                    Path3(listOf(Curve3Element.Seg3(g.a, g.b))) to null
                }
            }
            is EdgeGeom.OnPlane -> {
                when (g.piece) {
                    is ProfileElement.EllipseE, is ProfileElement.EllipticArcE ->
                        // …and where that ellipse is a **mitre**, the refusal says which ellipse it is and
                        // what does work (OP-31, item 3, Tier B item 5): two equal rounds crossing meet in a
                        // plane ellipse, exactly and by construction, and a rounding carried along it would
                        // have a section that changes from one end of the arc to the other — which this
                        // drawing states for no edge. Two equal *bevels* meet in a straight crease, and that
                        // one rounds with the machinery already here.
                        null to
                            if (edge.name is EdgeName.BlendMitre) {
                                Msgs.refusalBlendMitreSectionChanges(name = edge.name.label)
                            } else {
                                Msgs.refusalBlendIsEllipseWhichThisDrawing(name = edge.name.label)
                            }
                    is ProfileElement.BezierE ->
                        null to
                            Msgs.refusalBlendIsSplineWhoseNormalSection(name = edge.name.label)
                    else -> {
                        val closed = g.piece is ProfileElement.CircleE
                        val path = Intersect3.liftedRun(listOf(g.piece), g.plane, closed).first
                        if (path.elements.isEmpty()) {
                            null to Msgs.refusalBlendHasNoLengthSoThere(name = edge.name.label)
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
    ): Pair<FilletLeg?, Msg?> {
        fun local(p: Vec3) = Vec2((p - at).dot(e1), (p - at).dot(e2))

        fun direction(d: Vec3): Vec2? {
            val q = Vec2(d.dot(e1), d.dot(e2))
            return if (q.length() <= DIR_EPS) null else q.normalized()
        }

        fun ruling(d: Vec3): Pair<FilletLeg?, Msg?> {
            val q = direction(d) ?: return null to Msgs.refusalBlendLiesAlongThatEdgeRather(name = patch.name.label)
            return FilletLeg.of(Line(Vec2(0.0, 0.0), q)) to null
        }

        fun circleAbout(
            axisPoint: Vec3,
            axis: Vec3,
        ): Pair<FilletLeg?, Msg?> {
            val c = axisPoint + axis * ((at - axisPoint).dot(axis))
            val r = (at - c).length()
            if (r <= Geom3.WELD_TOL) return null to Msgs.refusalBlendClosesAxisThatEdgeSo(name = patch.name.label)
            return FilletLeg.of(Circle(local(c), r)) to null
        }

        val plane = patch.plane
        if (plane != null) return ruling(tangent.cross(plane.normal.normalized()))
        val surface =
            patch.surface
                ?: return null to (patch.reason ?: Msgs.refusalBlendHasNoSurfaceThisBlend(name = patch.name.label))
        val axis = surface.axis.normalized()
        val alongAxis = abs(axis.dot(tangent))
        // the axis **line** lies in the normal plane exactly when the edge runs across the axis and the axis
        // point projects into the plane — then a band of revolution is cut in its own meridian
        val throughAxis = alongAxis <= 1e-7 && abs((surface.origin - at).dot(tangent)) <= RIGID_TOL
        return when (val band = surface.band) {
            is Revolve3.Band.Degenerate ->
                null to Msgs.refusalBlendLiesAxisRevolutionSoThere(name = patch.name.label)
            is Revolve3.Band.Unnamed -> null to Msgs.refusalBlendIsWhichThisBlendHas(name = patch.name.label, name2 = band.label)
            is Revolve3.Band.Planar -> ruling(tangent.cross(axis))
            is Revolve3.Band.Cylinder ->
                when {
                    alongAxis >= 1.0 - 1e-7 -> circleAbout(surface.origin, axis)
                    throughAxis -> ruling(axis)
                    else -> conic(patch, Msgs.nameBandCylinder())
                }
            is Revolve3.Band.Cone ->
                when {
                    alongAxis >= 1.0 - 1e-7 -> circleAbout(surface.origin, axis)
                    abs((surface.origin + axis * band.sApex - at).dot(tangent)) <= RIGID_TOL ->
                        ruling(at - (surface.origin + axis * band.sApex))
                    else -> conic(patch, Msgs.nameBandCone())
                }
            // a plane cuts a sphere in a circle whichever way it is turned, which is the one band with no case
            is Revolve3.Band.Sphere -> {
                val centre = surface.origin + axis * band.sc
                val h = (centre - at).dot(tangent)
                val inPlane = centre - tangent * h
                val r = (at - inPlane).length()
                if (r <= Geom3.WELD_TOL) {
                    null to Msgs.refusalBlendClosesPointThatEdgeSo(name = patch.name.label)
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
                            null to Msgs.refusalBlendMeetsItsOwnAxisThat(name = patch.name.label)
                        } else {
                            FilletLeg.of(Circle(local(onAxis + radial.normalized() * band.rc), band.minor)) to null
                        }
                    }
                    else -> conic(patch, Msgs.nameBandTorus())
                }
        }
    }

    private fun conic(
        patch: FacePatch,
        what: Msg,
    ): Pair<FilletLeg?, Msg?> =
        null to
            Msgs.refusalBlendIsStandingAskewThatEdge(name = patch.name.label, what = what)

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
    ): Pair<Crease?, Msg?> {
        // an edge a blend already consumed keeps its index and its carrier, but it is no longer a crease of
        // the body — so building on it is refused in the words the dressed list put there (slice 3)
        edge.reason?.let { return null to it }
        // **the construction reads the trimmed faces, never the notched ones** (session 81). A free end's
        // notch is a statement about a *corner* of a face's boundary; whether a section fits between two
        // faces is not a question about that corner, and asking it there would refuse a rounding that fits
        // — session 80's `tangenciesFit` lesson, one boundary detail further on. Keeping the whole
        // construction on the trimmed list is also what says the notch changes what the drawing **states**
        // and never what the solid **is**: every volume in the suite is unmoved by it.
        val (faces, whyFaces) = trimmedFacesOf(feature)
        if (faces == null) return null to whyFaces
        val face1 = faces.firstOrNull { it.name == edge.between.a } ?: return null to Msgs.refusalBlendThisSolidHasNo(name = edge.between.a.label)
        val face2 = faces.firstOrNull { it.name == edge.between.b } ?: return null to Msgs.refusalBlendThisSolidHasNo(name = edge.between.b.label)
        if (face1.name == face2.name) {
            return null to
                Msgs.refusalBlendIsSeamWhereFaceMeets(name = edge.name.label)
        }
        val (path, whyPath) = pathOf(edge)
        if (path == null) return null to whyPath
        val t0 = Curves3.tangentAt(path.elements.first(), 0.0) ?: return null to Msgs.refusalBlendHasNoDirectionSweepAlong(name = edge.name.label)
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
        if (stations.isEmpty()) return null to Msgs.refusalBlendHasNoDirectionSweepAlong(name = edge.name.label)
        // the **reference station** is the middle one, and its traces are the section that gets swept: the
        // others are read only to check that it is the same section there, which is what makes sweeping one
        // rigid wedge a claim rather than an assumption
        val at = stations.size / 2
        var leg1: FilletLeg? = null
        var leg2: FilletLeg? = null
        for ((k, st) in stations.withIndex()) {
            val (a, whyA) = traceOf(face1, st.at, st.tangent, e1, st.e2)
            if (a == null) return null to Msgs.refusalQualified(name = edge.name.label, reason = whyA ?: Msg.EMPTY)
            val (b, whyB) = traceOf(face2, st.at, st.tangent, e1, st.e2)
            if (b == null) return null to Msgs.refusalQualified(name = edge.name.label, reason = whyB ?: Msg.EMPTY)
            val known1 = leg1
            val known2 = leg2
            if (known1 != null && known2 != null && (!same(known1, a) || !same(known2, b))) {
                return null to
                    Msgs.refusalBlendSectionSquareChangesAlongIt(name = edge.name.label)
            }
            if (known1 == null || k == at) {
                leg1 = a
                leg2 = b
            }
        }
        val undressed = undressedFacesOf(feature)
        val base1 = undressed?.firstOrNull { it.name == face1.name } ?: face1
        val base2 = undressed?.firstOrNull { it.name == face2.name } ?: face2
        return Crease(edge, path, e1, stations, stations[at], leg1!!, leg2!!, face1, face2, base1, base2, length) to null
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
    ): Pair<Triple<Int, Int, Boolean>?, Msg?> {
        val n1 = normalOf(crease.leg1) ?: return null to Msgs.refusalBlendHasNoSideThatEdge(name = crease.edge.name.label, name2 = crease.face1.name.label)
        val n2 = normalOf(crease.leg2) ?: return null to Msgs.refusalBlendHasNoSideThatEdge(name = crease.edge.name.label, name2 = crease.face2.name.label)
        var scale = min(reach, crease.length / 2.0)
        crease.leg1.circle?.let { scale = min(scale, it.radius) }
        crease.leg2.circle?.let { scale = min(scale, it.radius) }
        val delta = scale * PROBE_FRACTION
        if (delta <= Geom3.WELD_TOL) return null to Msgs.refusalBlendIsTooSmallBlendThat(name = crease.edge.name.label)
        val found = ArrayList<Pair<Int, Int>>(4)
        for (s1 in listOf(1, -1)) {
            for (s2 in listOf(1, -1)) {
                val q = n1 * (s1 * delta) + n2 * (s2 * delta)
                if (sideOf(crease.leg1, q) != s1 || sideOf(crease.leg2, q) != s2) {
                    return null to
                        Msgs.refusalBlendRunTooNearlyTangentTell(name = crease.face1.name.label, name2 = crease.face2.name.label, name3 = crease.edge.name.label)
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
                    Msgs.refusalBlendIsNotSimpleCreaseBetween(name = crease.edge.name.label, name2 = crease.face1.name.label, name3 = crease.face2.name.label, count = found.size)
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
    ): Pair<Wedge?, Msg?> {
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
            return null to Msgs.refusalBlendLeavesNoMaterial(word = sec.kind.word, sizePhrase = sec.sizePhrase(), name = crease.edge.name.label)
        }
        // **a section that crosses itself has no region to take away** (GitHub #30's own refusal). Asked of
        // the whole loop rather than of the drawing alone, because a profile that is simple on paper can
        // still cross a leg once it is read in a skewed corner's frame.
        if (sec.kind == BlendKind.PROFILE && crossesItself(oriented)) {
            return null to
                Msgs.refusalBlendThatProfileCrossesItselfOnce(name = crease.edge.name.label)
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
    ): Pair<List<ProfileElement>?, Msg?> {
        if (drawn.isEmpty()) return null to Msgs.refusalBlendThatProfileHasNoPieces()
        if (crease.leg1.line == null || crease.leg2.line == null) {
            return null to
                Msgs.refusalBlendSectionSquareMeetsCircleRather(
                    name = crease.edge.name.label,
                    ifWord =
                        (if (crease.leg1.line == null) crease.face1 else crease.face2).name.label,
                )
        }
        // the pieces must actually make one run, or the "two ends" the frame reads are not two ends
        for (i in 0 until drawn.size - 1) {
            if ((GeomMath.startOf(drawn[i + 1]) - GeomMath.endOf(drawn[i])).length() > PROFILE_TOL) {
                return null to Msgs.refusalBlendThatProfilePieceDoesNot(i = i + 1, i2 = i + 2)
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
                        Msgs.refusalBlendThatProfileEndsDoNot(mm = Frames3.mm(head.x), mm2 = Frames3.mm(head.y), mm3 = Frames3.mm(tail.x), mm4 = Frames3.mm(tail.y))
            }
        val a = GeomMath.startOf(run.first()).x
        val b = GeomMath.endOf(run.last()).y
        if (a <= PROFILE_TOL || b <= PROFILE_TOL) {
            return null to
                Msgs.refusalBlendThatProfileStatesSetbackMm(mm = Frames3.mm(a), mm2 = Frames3.mm(b))
        }
        // **inside the corner's own quadrant, or it is a bead** — a profile reaching outside would *add*
        // material at a convex edge, and a section driving two booleans of opposite sign is structure decided
        // from a value (OP-21). Checked in the drawn coordinates, where the quadrant is exactly `x, y >= 0`.
        for (e in run) {
            for (q in GeomMath.tessellatePiece(e, GeomMath.TESS_TOL_MM)) {
                if (q.x < -PROFILE_TOL || q.y < -PROFILE_TOL) {
                    return null to
                        Msgs.refusalBlendThatProfileReachesOutsideCorner(name = crease.face1.name.label, name2 = crease.face2.name.label)
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
            return null to Msgs.refusalBlendRunTooNearlyParallelRead(name = crease.face1.name.label, name2 = crease.face2.name.label, name3 = crease.edge.name.label)
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
    ): Msg =
        Msgs.refusalBlendNoFitsBetween(word = sec.kind.word, sizePhrase = sec.sizePhrase(), name = crease.face1.name.label, name2 = crease.face2.name.label, name3 = crease.edge.name.label)

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
            for ((face, t) in listOf(crease.base1 to wedge.t1, crease.base2 to wedge.t2)) {
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
        /**
         * The wedge's boundary **grown out through both faces**, counter-clockwise in the section frame and
         * starting at the grown corner — the one section every ring of the tool is placed from
         * ([sectionOf]).
         */
        val grown: List<Vec2>,
        /**
         * The **un**-stepped boundary, point for point with [grown] — what a ring standing on a **pivot
         * axis** is placed from, and nothing else. See [toolMesh].
         */
        val plain: List<Vec2>,
        /** The same shape as an exact region, for the sweep along a crease that is not a straight run. */
        val grownRegion: Region,
        /** Whether that section really was stepped off — false for a wedge with a round leg ([Grown]). */
        val stepped: Boolean,
        /** The same polygon triangulated once — the tool's cap at every free end. */
        val caps: List<Geom3.Tri3>,
        /** The edge as one straight run, or null: only a straight edge can carry a corner (see [cornersOf]). */
        val seg: Curve3Element.Seg3?,
        /** How far the tube is stepped along the run at each free end — see [endSteps]. */
        val backAtStart: Double,
        val backAtEnd: Double,
    ) {
        /**
         * How long this piece's own run is — the segment's length where the crease is one straight run, and
         * the **crease's own** length where it is not.
         *
         * It used to be asked only where [seg] was there and read `seg!!`. Since a rounding can address a
         * *chain* (OP-31, item 3) a pass routinely carries pieces whose crease is an arc — the leg of a
         * pivot's own rail — and every reader of a band's extent meets one, so the answer has to be total.
         */
        val length: Double get() = seg?.let { (it.end - it.start).length() } ?: crease.length
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

        override fun label(pieces: List<Piece>): Msg =
            Msgs.refusalBlendCornerWhereMeets(name = pieces[a].crease.edge.name.label, name2 = pieces[b].crease.edge.name.label, name3 = shared.name.label)
    }

    /**
     * The blend's section as the tool actually carries it: one polygon, and the same shape as a region.
     *
     * [stepped] is false for the one section that has no step-off to make — a wedge with a **round** leg,
     * whose offset is not a line and is not in this vocabulary (see [sectionOf]). Such a tool is swept
     * exactly as it always was, so nothing about it moves.
     */
    private class Grown(val polygon: List<Vec2>, val plain: List<Vec2>, val region: Region, val stepped: Boolean)

    /**
     * The section the tool is swept with: the wedge's own boundary with each straight leg **pivoted off its
     * face about its own tangency** by [GROW_MM] at the crease — as a polygon (what a stitched tube's rings
     * are placed from) and as an exact [Region] (what [Geom3.sweep] carries along a curved crease), which
     * are the same shape said twice.
     *
     * *Why stepped off at all.* The wedge's two legs lie exactly *in* the two faces, so the tool it sweeps
     * has a flat side coincident with a face of the body over a strip as wide as the tangency. One such
     * contact a mesh boolean can resolve; two of them overlapping at a corner — or one meeting a face
     * another blend has already trimmed — is a coplanar overlap whose answer is a fraction of a float32,
     * and that is the *"used 2 times with 2 opposite uses"* both reporters met. Moving the leg off its face
     * turns that into an ordinary contact along the **tangency line only** — which the arc already makes,
     * being tangent to the face there — and the arc between the two tangencies, the only part of the
     * section that decides any geometry, is untouched.
     *
     * *And why the jog back onto the tangency is **diagonal*** (the probe of GitHub #33). The step-off has
     * to come back to the tangency: that point is the arc's own end, it is on the face by definition, and
     * moving it would move the geometry. So each leg is a strip a micron off its face that ends a micron
     * short of the tangency's foot, joined to the tangency by a short jog. **That jog must not stand square
     * to its own leg**, and the reason is the pivot. At an inside corner the ball turns about the upright
     * ([Turn]), and at the corner station the leg in the *other* face **is** that axis — both its ends lie
     * on the upright, the tangency at radius zero. A square jog there is **radial**, so the turn sweeps it
     * into a flat micron-wide disc about the axis — and the tube's own jog, extruded along the run, lies in
     * the very same plane and the turn takes the disc back over it. That is a zero-thickness fold in the
     * tool, and a boolean hands it straight on to the body: a 100° pivot at `r = 0.3` on a dart's reflex
     * vertex does exactly that ([MeshCanon.flap] names it at `z = 20 − r`). A **diagonal** jog — a micron
     * out and a micron along the leg — sweeps a *cone* whose apex is the tangency, on the axis, and a cone
     * shares no plane with the tube's own jog. The pure alternative, pivoting the leg about its tangency so
     * that nothing jogs at all, was tried and is worse: it leaves the leg's whole plane **grazing** the
     * face along the tangency line instead of standing clear of it, which is the tangent contact the
     * general engine has no watertight answer for (a 19° chamfered tip folds along its own setback line).
     *
     * *And the whole run of it, not only a corner* (GitHub #33). Session 79's rule read *"grown at a corner,
     * plain at a free end: the growth tapers along the run"*, on the argument that a tool a micron proud of
     * its two faces at a **free** end leaves a micron-wide notch at the body's own upright there. It does,
     * and a micron-wide notch is an ordinary transversal sliver the engine resolves; a coplanar face is a
     * coin toss, and the reporter's chevron is where it came up tails — the tip vertex left in the mesh at
     * `z = 0` with the wall folded back over it, a zero-thickness flap ([MeshCanon.flap]). **A tool never
     * shares a face with the body**: the step-off is uniform along the whole run, on every tool, and the
     * free end is answered by stepping the *cap* instead ([endSteps]).
     *
     * *And nothing extra is removed.* [outwardAt] steps each leg to its own **safe** side: out of the
     * material at a convex crease (where the wedge is subtracted, so a micron beyond either face is air),
     * into it at a concave one (where the wedge is added, so the micron lies in material already there).
     * Every volume in the suite is unchanged by it, which is what says so.
     *
     * A **round** leg (a fillet against a cylinder) has no straight offset in this vocabulary, so it is
     * swept as it always was — a plane against a curved band is not the degenerate case.
     */
    private fun sectionOf(
        crease: Crease,
        wedge: Wedge,
    ): Pair<Grown?, Msg?> {
        val o = Vec2(0.0, 0.0)
        val arc = wedge.pieces.flatMap { GeomMath.tessellatePiece(it, GeomMath.TESS_TOL_MM) }
        val n1 = outwardAt(wedge.t1, wedge.t2)
        val n2 = outwardAt(wedge.t2, wedge.t1)
        val grown: List<Vec2>
        val plain: List<Vec2>
        val region: Region
        val stepped: Boolean
        if (crease.leg1.line != null && crease.leg2.line != null && n1 != null && n2 != null) {
            val corner = offsetCorner(n1, n2) ?: return null to Msgs.refusalBlendTwoFacesThatCreaseRun()
            val g1 = wedge.t1 + n1 * GROW_MM
            val g2 = wedge.t2 + n2 * GROW_MM
            plain = listOf(o, wedge.t1) + arc + listOf(wedge.t2)
            grown = listOf(corner, g1) + arc + listOf(g2)
            // the very same boundary as an exact loop: the two legs stepped off, a square jog back onto
            // each tangency, and the blend's own curve between them untouched
            val loop =
                Loop(
                    listOf(ProfileElement.Seg(Segment(corner, g1)), ProfileElement.Seg(Segment(g1, wedge.t1))) +
                        wedge.pieces +
                        listOf(ProfileElement.Seg(Segment(wedge.t2, g2)), ProfileElement.Seg(Segment(g2, corner))),
                )
            region = Region(if (GeomMath.signedArea(loop) >= 0.0) loop else GeomMath.reverseLoop(loop), emptyList())
            stepped = true
        } else {
            val pts = ArrayList<Vec2>()
            pts.add(o)
            pts.addAll(GeomMath.tessellatePiece(sidePiece(crease.leg1, o, wedge.t1), GeomMath.TESS_TOL_MM))
            pts.addAll(arc)
            pts.addAll(GeomMath.tessellatePiece(sidePiece(crease.leg2, wedge.t2, o), GeomMath.TESS_TOL_MM))
            val kept = ArrayList<Vec2>(pts.size)
            for (q in pts) if (kept.isEmpty() || (q - kept.last()).length() > Geom3.WELD_TOL) kept.add(q)
            while (kept.size > 1 && (kept.first() - kept.last()).length() <= Geom3.WELD_TOL) kept.removeAt(kept.size - 1)
            grown = kept
            plain = kept
            region = wedge.region
            stepped = false
        }
        if (grown.size < 3) return null to Msgs.refusalBlendRoundingOwnSectionHasFewer()
        // one winding for both, so index k of either ring is the same point of the same section
        return if (Geom3.polygonArea(grown) >= 0.0) {
            Grown(grown, plain, region, stepped) to null
        } else {
            Grown(reversedFromFirst(grown), reversedFromFirst(plain), region, stepped) to null
        }
    }

    /**
     * How far each end of a straight run's tube is stepped **along the run**, in mm — positive *back* into
     * the run, negative *out* past its end — so that a free end's cap never lies in a face of the body
     * either (GitHub #33, the other half of [sectionOf]'s step-off).
     *
     * A free end is capped on the plane square to the edge there, and wherever the body has a face in that
     * plane — the ordinary upright, whose two ends stand on the part's own caps; an edge ending at an
     * inside corner, where the wall across the corner is square to it — the cap is coplanar with it and the
     * difference is the same coin toss the legs were. So the cap is stepped a micron to whichever side is
     * safe, and which side that is, is decided by **what lies beyond the end**:
     *
     * - **Air** — nothing of the body continues past — and the tube **overshoots** by [GROW_MM]. It removes
     *   nothing extra (it is air) and the cap now stands clear of the body's own face.
     * - **Material** — the crease's *shared* face runs on past the end while the other stops, which is what
     *   an inside corner is — and the tube is **pulled back** by [GROW_MM]: session 79's `buttEnds` micron
     *   of daylight, said once for every free end rather than only for a butting pair. It leaves a micron
     *   of material at a corner that already keeps a whole spike there, and that is recorded.
     * - **The crease runs on** — *both* faces continue, so the edge simply carries further than this
     *   rounding does (a smooth run handed over to the next band, or a chain the user stopped short) — and
     *   the tube **overshoots** again: a micron more is taken off an edge the very same section is cutting
     *   anyway, and where the run really does stop it is a micron of a rounding the user asked for.
     *
     * *How the three are told apart, exactly and deterministically:* by the **dressed face list**, not by a
     * probe of the triangles. The point a micron-scale step beyond the end lies in both faces' planes (the
     * edge is their intersection), so the question is only whether it is still *on* each face's own
     * outline — [onFace], the same reading [tangenciesFit] already makes of the same lists. Two faces
     * reaching it is a crease that runs on, one is an inside corner, none is air.
     *
     * *And only for a **subtract** tool.* A concave wedge is **added**, so its cap is a face of the finished
     * part: overshooting would leave a micron burr standing proud of the body's cap and pulling back a
     * micron notch, and both are geometry the user did not draw. Flush is also *safe* there, which is why
     * it is not a compromise: a union of two solids whose caps are coplanar **and point the same way** has
     * no surface passing through another — the two sheets are the outside of one merged face, each edge of
     * it still used once each way. The degenerate case is coplanar sheets facing *against* each other,
     * which is what a difference makes and what the step-off exists to prevent.
     */
    private fun endSteps(
        crease: Crease,
        choice: BlendChoice,
        sec: BlendSection,
    ): Pair<Double, Double> {
        if (!choice.convex) return 0.0 to 0.0
        val seg = soleElement(crease) as? Curve3Element.Seg3 ?: return 0.0 to 0.0
        val run = seg.end - seg.start
        if (run.length() <= Geom3.WELD_TOL) return 0.0 to 0.0
        val dir = run.normalized()
        val delta = min(sec.reach(), crease.length / 2.0) * PROBE_FRACTION
        if (delta <= Geom3.WELD_TOL) return 0.0 to 0.0
        return stepBeyond(crease, seg.start - dir * delta) to stepBeyond(crease, seg.end + dir * delta)
    }

    /** Back into the run where [beyond] is material an inside corner keeps, out past its end otherwise. */
    private fun stepBeyond(
        crease: Crease,
        beyond: Vec3,
    ): Double = if (facesReaching(crease, beyond) == 1) GROW_MM else -GROW_MM

    /** How many of the crease's two faces still reach [p] — see [endSteps]. */
    private fun facesReaching(
        crease: Crease,
        p: Vec3,
    ): Int {
        var count = 0
        for (face in listOf(crease.face1, crease.face2)) {
            val plane = face.plane ?: continue
            val rings = Project3.ringsOf(face.outline)
            if (rings.isEmpty()) continue
            if (abs(plane.distanceTo(p)) > ON_BOUNDARY_TOL) continue
            if (onFace(rings, plane.toLocal(p))) count++
        }
        return count
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
    ): Pair<Piece?, Msg?> {
        val (section, whySection) = sectionOf(crease, wedge)
        if (section == null) return null to Msgs.refusalQualified(name = crease.edge.name.label, reason = whySection ?: Msg.EMPTY)
        val grown = section.polygon
        val distinct = ArrayList<Vec2>(grown.size)
        for (q in grown) if (distinct.none { (it - q).length() <= Geom3.WELD_TOL }) distinct.add(q)
        val (caps, whyCaps) = Geom3.triangulate(Geom3.TessRegion(distinct, emptyList()))
        if (caps == null) {
            return null to Msgs.refusalQualified(name = crease.edge.name.label, reason = whyCaps ?: Msgs.refusalBlendSectionCannotBeTriangulated())
        }
        val (back0, back1) = endSteps(crease, choice, sec)
        return Piece(
            index,
            existing,
            crease,
            wedge,
            choice,
            sec,
            grown,
            section.plain,
            section.region,
            section.stepped,
            caps,
            soleElement(crease) as? Curve3Element.Seg3,
            back0,
            back1,
        ) to null
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
                    // a **tombstone** took nothing off this body, so there is no band of it under anything
                    if (f.isAbsent(k)) continue
                    val sec = f.sections.getOrNull(k) ?: continue
                    val edge = edges.getOrNull(i) ?: continue
                    val crease = creaseOf(below, edge).first ?: continue
                    val choice = f.choices.getOrNull(k) ?: continue
                    val wedge = wedgeOf(crease, sec, choice).first ?: continue
                    out.add(pieceOf(i, true, crease, wedge, choice, sec).first ?: continue)
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
        fun label(pieces: List<Piece>): Msg

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
     * A corner that is **one section carried along a walk** — the two the rolling ball makes when it leaves
     * its own edge and follows what stands at the next one.
     *
     * There are exactly two, and they differ only in how the walk *ends*. A [Turn] walks from one band's end
     * round to another band's end, so the walk is closed at both ends by a tube. A [Pivot] walks from one
     * band's end until the third face at the vertex caps it, so it is closed at one end by a tube and at the
     * other by a plane — *"the pivot about a band with one end instead of two"* (OP-31, item (b)).
     *
     * Everything else about them is the same object, which is why it is said once here: the rings are the
     * legs' own placements in order, one face per (leg × section piece), and a leg that turns is a surface of
     * revolution whose axis frame [Revolve3] then names and cuts.
     */
    private sealed interface Walk : Corner {
        /** The piece whose section travels — the one whose end the walk continues. */
        val travelling: Int

        /** The walk itself, one leg per piece of what stands at the edge it turns about. */
        val walkLegs: List<Leg>

        /** The axis every turning leg is about, unit — the plane the walk runs in, read as its normal. */
        val walkNormal: Vec3?

        /** The face the walk runs **in** — the one the travelling section keeps its tangency on. */
        val walkFace: FacePatch

        /** Where the walk's rings stand at walk parameter [t], legs numbered from zero. */
        fun placeAt(t: Double): Placement? {
            val n = walkNormal ?: return null
            val k = min(walkLegs.size - 1, max(0, t.toInt()))
            val u = (t - k).coerceIn(0.0, 1.0)
            val leg = walkLegs[k]
            val from = leg.rings.first()
            val pivot = leg.pivot ?: return Placement(from.origin + (leg.rings.last().origin - from.origin) * u, from.cx, from.cy)
            return turnedPlacement(from, pivot, n, leg.dir, (from.origin - pivot).dot(leg.dir), leg.turn * u)
        }

        /** Where the travelling section's tangency on [walkFace] stands at walk parameter [t]. */
        fun tangencyAt(
            pieces: List<Piece>,
            t: Double,
        ): Vec3? {
            val piece = pieces[travelling]
            val q = if (walkFace.name == piece.crease.face1.name) piece.wedge.t1 else piece.wedge.t2
            return placeAt(t)?.at(q)
        }

        /**
         * The corner's own correction of the face the walk runs in: the section's **tangency curve** on it,
         * from the walk's start to [tEnd].
         *
         * Exact, piece for piece, and for the reason the walk's surface is: the tangency lies in that face's
         * plane, the pivot lies in it too and the axis stands square to it — so a turning leg carries the
         * tangency round the pivot on a **circle** of that plane and a sliding one along a straight run.
         * Below the corner the face's boundary is the band's straight setback; from the corner on it is this
         * curve, which is why a level section through the corner closes instead of leaving a gap as wide as
         * the corner is deep.
         *
         * Stated on the **walk** rather than on the one-ended pivot (OP-31, item 3): a two-ended [Turn]
         * leaves exactly the same curve on the face it runs in, and until the corner's own rails were edges
         * of the body nobody had to ask. Now they are, and the strip a rounding of one takes off that face
         * has to come off the curve the body actually has there.
         */
        fun sharedChain(
            pieces: List<Piece>,
            tEnd: Double,
        ): List<ProfileElement>? {
            val plane = walkFace.plane ?: return null
            val out = ArrayList<ProfileElement>()
            for (k in walkLegs.indices) {
                val lo = k.toDouble()
                val hi = min((k + 1).toDouble(), tEnd)
                if (hi - lo <= 1e-12) continue
                val p0 = plane.toLocal(tangencyAt(pieces, lo) ?: return null)
                val p1 = plane.toLocal(tangencyAt(pieces, hi) ?: return null)
                val leg = walkLegs[k]
                val pivot = leg.pivot
                if (pivot == null || abs(leg.turn) <= TANGENT_TOL) {
                    if ((p1 - p0).length() > Geom3.WELD_TOL) out.add(ProfileElement.Seg(Segment(p0, p1)))
                    continue
                }
                val c = plane.toLocal(pivot)
                val r = (p0 - c).length()
                if (r <= Geom3.WELD_TOL || (p1 - p0).length() <= Geom3.WELD_TOL) continue
                out.add(ProfileElement.ArcE(Arc(c, r, (p0 - c).angle(), (p1 - c).angle(), leg.turn >= 0.0)))
            }
            return out.ifEmpty { null }
        }

        /** Every ring of the walk in order, a join between two legs counted once. */
        val rings: List<Placement> get() = walkRings(walkLegs)

        /** How the faces are laid out: **leg by leg, one per piece of the travelling section**. */
        fun facePlan(pieces: List<Piece>): List<Pair<Leg, ProfileElement?>> = walkFacePlan(pieces[travelling], walkLegs)

        /** One leg as an axis frame and the section in that frame's own `(s, r)`; null for a leg that slides. */
        fun axisFrame(
            piece: Piece,
            leg: Leg,
        ): Pair<Revolve3.Frame, Affine>? = walkNormal?.let { walkAxisFrame(piece, leg, it) }

        /** Where this corner **ends the band it turns about**, or null where it turns about a sharp edge. */
        fun uprightEnd(pieces: List<Piece>): Pair<Pair<Int, Boolean>, Placement>? = null

        override fun faces(
            pieces: List<Piece>,
            nameAt: (Int) -> FaceName,
        ): List<FacePatch> =
            facePlan(pieces).mapIndexed { k, (leg, sr) ->
                walkLegPatch(pieces[travelling], leg, sr, nameAt(k), walkNormal)
            }
    }

    /** [legs]' placements in order, the join between two legs counted once; the first is the walk's start. */
    private fun walkRings(legs: List<Leg>): List<Placement> =
        ArrayList<Placement>().also { out ->
            for (leg in legs) for ((k, p) in leg.rings.withIndex()) if (out.isEmpty() || k > 0) out.add(p)
        }

    /** One face per (leg × piece of [piece]'s own section), in that order. */
    private fun walkFacePlan(
        piece: Piece,
        legs: List<Leg>,
    ): List<Pair<Leg, ProfileElement?>> {
        val sections = orientedSections(piece)
        return legs.flatMap { leg -> sections.map { leg to it } }
    }

    /**
     * The walk's own surface, as the **revolution or the sweep it is**: the section turned about the axis
     * square to the walk's plane — which [Revolve3] then names, a torus where the section is an arc and a
     * cone where it is a bevel — or carried straight along a bevel's own run, which is the very sweep a band
     * along a straight edge is ([Section3.sweptFace]).
     */
    private fun walkLegPatch(
        piece: Piece,
        leg: Leg,
        sr: ProfileElement?,
        name: FaceName,
        n: Vec3?,
    ): FacePatch {
        if (sr == null) {
            return FacePatch(name, null, emptyList(), Msgs.refusalBlendTurnsPieceProfileThisDrawing(name = name.label))
        }
        if (leg.pivot != null) {
            val (frame, map) =
                (n?.let { walkAxisFrame(piece, leg, it) })
                    ?: return FacePatch(name, null, emptyList(), Msgs.refusalBlendHasNoAxisTurnAbout(name = name.label))
            val mapped =
                mappedSection(sr, map)
                    ?: return FacePatch(name, null, emptyList(), Msgs.refusalBlendTurnsPieceProfileThisDrawing(name = name.label))
            return inCornersWords(Revolve3.bandPatch(frame, mapped, name), name)
        }
        val from = leg.rings.first()
        val to = leg.rings.last()
        val v = to.origin - from.origin
        val len = v.length()
        if (len <= Geom3.WELD_TOL) return FacePatch(name, null, emptyList(), Msgs.refusalBlendHasNoLength(name = name.label))
        // the sweep runs along the section frame's own normal, so the run is stated from whichever end
        // it leaves — the same right-handed convention [bandCarrier] states a straight band with
        val u = from.cx.cross(from.cy).normalized()
        val base = if (v.dot(u) >= 0.0) from else to
        return inCornersWords(Section3.sweptFace(Plane3(base.origin, base.cx, base.cy), u, len, sr, name), name)
    }

    /**
     * One leg as an axis frame and the travelling section in that frame's own `(s, r)` — all [Revolve3] ever
     * needs, and null for a leg that slides rather than turns.
     *
     * The **radial offset** is the whole of session 81 in one number: the section's own origin stands `rho`
     * out from the pivot, so the surface is a *ring* torus where a sharp upright's was a horn one, and
     * `rho = 0` reproduces session 80 verbatim.
     */
    private fun walkAxisFrame(
        piece: Piece,
        leg: Leg,
        n: Vec3,
    ): Pair<Revolve3.Frame, Affine>? {
        val pivot = leg.pivot ?: return null
        val axis = n * -1.0
        val start = leg.rings.first()
        // **the radial offset is radial**: the axial part of the offset is the frame's own `s`, not part of
        // the radius. On a walk that runs *in* a plane the axial part is zero and this is session 81's own
        // number unchanged; stating it makes the frame right for a walk that does not (OP-31, item 3b).
        val full = start.origin - pivot
        val axial = full.dot(axis)
        val off = full - axis * axial
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
        // **the section's axes as they stand at *this* leg's start, not as they were drawn** (OP-31, item
        // 3b). A walk turns the section with it: after a bevelled upright's first quarter-turn the section
        // stands 45° round from the frame its crease was stated in, and reading the *original* `e1, e2` here
        // placed the second turn's own cone 45° out of true. Its rings are placements ([Placement]), so the
        // frame it actually stands in is the leg's own first ring — which for a single-turn walk *is* the
        // crease's frame, so session 80's and 81's corners are unmoved.
        val e1 = start.cx
        val e2 = start.cy
        // the section's own `(x, y)` read as the frame's `(s, r)`: down the axis, out along the radius,
        // the whole section standing `rho` out from the axis it turns about and `axial` along it
        return frame to Affine(e1.dot(axis), e1.dot(p), e2.dot(axis), e2.dot(p), axial, rho)
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
    ) : Walk {
        override val travelling: Int get() = a

        override val walkLegs: List<Leg> get() = legs

        override val walkNormal: Vec3? get() = shared.plane?.normal?.normalized()

        override val walkFace: FacePatch get() = shared

        override val rings: List<Placement> = walkRings(legs)

        override val ends: List<Pair<Int, Boolean>> get() = listOf(a to aAtStart, b to bAtStart)

        override fun ringAt(end: Pair<Int, Boolean>): Placement = if (end.first == a && end.second == aAtStart) placeA else placeB

        override fun emit(
            pieces: List<Piece>,
            out: Geom3.MeshBuilder,
        ) {
            // **the plain section where the pivot axis runs through it** — about a *sharp* upright the leg
            // in the other face lies along that upright, so a step-off would lift it a micron off the axis
            // and the turn would sweep that micron into a disc ([toolMesh]). About a **band** the axis
            // stands `r_U` away from the section and there is no such point, so the step-off is kept and
            // the leg does not lie in the face it is tangent to.
            val section = if (extra.isEmpty()) pieces[a].plain else pieces[a].grown
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

        override fun label(pieces: List<Piece>): Msg =
            if (extra.isEmpty()) {
                Msgs.nameBlendInsideCorner(
                    first = pieces[a].crease.edge.name.label,
                    second = pieces[b].crease.edge.name.label,
                    face = shared.name.label,
                )
            } else {
                Msgs.nameBlendInsideCornerTurned(
                    first = pieces[a].crease.edge.name.label,
                    second = pieces[b].crease.edge.name.label,
                    face = shared.name.label,
                    upright = pieces[extra.first()].crease.edge.name.label,
                )
            }

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
        override fun uprightEnd(pieces: List<Piece>): Pair<Pair<Int, Boolean>, Placement>? {
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
    }

    /**
     * The **mixed-sign pair**: a fill that meets a band's end, and turns about it (OP-31, item (b)).
     *
     * *What was wrong before.* A pair of unlike sign was left to overlap and be trimmed by the boolean —
     * session 79's default, and right for a pair that does overlap. This pair does not. The fill is tangent
     * to the two faces of its own crease, and the band has **rounded one of them away** over the last `r_U`
     * of the fill's run; above that the fill stands on nothing, a vertical ledge beside the band. Nothing
     * refused, and the body came out at exactly the naive figure — band over the whole edge, fill over the
     * whole upright, no corner at all (GitHub #36, script 1).
     *
     * *Which of the two travels, derived rather than chosen.* A **fill's** ball lives in the air, so where
     * the face it was tangent to has become the band's own convex surface it rolls round the **outside** of
     * it: external tangency, its centre on the circle of radius `r + r_U` about that band's axis, which
     * exists for every pair of sizes. A **band's** ball lives in the material, and where its face has become
     * a fill's concave surface it would have to be tangent to it from *inside* — and the inside of a fill's
     * cylinder is the very air the fill was put there to keep. So the band's spine simply ends, and the
     * fill's carries on: **the concave piece travels and the convex one is pivoted about**, always.
     *
     * *And the walk is [Turn]'s own, one end short.* The fill's section follows the band's own end-section
     * curve piece by piece, turning about the axis square to the face the fill shares with nothing else —
     * its **other** face, the one the band stands square to — which is the same sentence session 81 wrote
     * for a pair pivoting about an upright, with the band in the upright's place. What differs is only the
     * end: there is no second band to land on, so the walk runs to the end of the band's own curve and is
     * **capped by the third face** at the vertex — the band's far face, whose plane every ring is clipped to
     * ([emit]). At the last ring the section stands *on* that plane and the clip closes the tube itself.
     */
    private class Pivot(
        val a: Int,
        val aAtStart: Boolean,
        /** The band the fill turns about — a boolean of the other sign, so never in [ends]. */
        val b: Int,
        /** The face the fill shares with nothing else here; the band stands square to it and it is the walk's plane. */
        val shared: FacePatch,
        /** The band's **far** face: the plane that caps the walk, and the third face of the vertex. */
        val third: FacePatch,
        val legs: List<Leg>,
        val at: Vec3,
    ) : Walk {
        override val travelling: Int get() = a

        override val walkLegs: List<Leg> get() = legs

        override val walkNormal: Vec3? get() = shared.plane?.normal?.normalized()

        override val walkFace: FacePatch get() = shared

        override val rings: List<Placement> = walkRings(legs)

        override val ends: List<Pair<Int, Boolean>> get() = listOf(a to aAtStart)

        override val extra: List<Int> get() = listOf(b)

        override fun ringAt(end: Pair<Int, Boolean>): Placement = rings.first()

        /**
         * The walk's tube, every ring **clamped to the third face's plane**.
         *
         * *Why clamping and not clipping.* The plane's condition reads in the section's own coordinates as an
         * affine one, and its gradient there points along the section's reach into the shared face — which is
         * the one direction the wedge is monotone in (each row of it is one interval, between the crease and
         * the blend curve). So pulling every point that stands beyond the plane back **along that gradient**
         * gives the clipped section exactly, as a polygon of the very same points in the very same order: the
         * tube keeps one stitching, no ring has to be re-triangulated against a cut, and every clamped point
         * lands *on* the plane, so the flat top the clip leaves is planar by construction rather than by
         * tolerance. A row that lies wholly beyond the plane collapses to zero width and contributes nothing,
         * which is the empty row said in the same arithmetic.
         */
        override fun emit(
            pieces: List<Piece>,
            out: Geom3.MeshBuilder,
        ) {
            val piece = pieces[a]
            val plane = third.plane ?: return
            val n3 = plane.normal.normalized()
            val d0 = plane.origin.dot(n3)
            val stations = stationsFor(piece, n3, d0)
            val clamped = stations.map { p -> clampedSection(piece.grown, p, n3, d0) }
            val strips = clamped.mapIndexed { l, qs -> qs.map { stations[l].at(it) } }
            for (l in 0 until strips.size - 1) {
                val lo = strips[l]
                val hi = strips[l + 1]
                for (m in lo.indices) {
                    val n = (m + 1) % lo.size
                    // the walk continues [a]'s own tube, so the two rings take the same roles its two did
                    if (aAtStart) {
                        triangleUnlessFlat(out, hi[m], hi[n], lo[n])
                        triangleUnlessFlat(out, hi[m], lo[n], lo[m])
                    } else {
                        triangleUnlessFlat(out, lo[m], lo[n], hi[n])
                        triangleUnlessFlat(out, lo[m], hi[n], hi[m])
                    }
                }
            }
            // …and the far end, which no band closes: the clamped section standing on the third face itself
            val last = stations.last()
            for (t in capsOf(clamped.last())) {
                if (aAtStart) {
                    triangleUnlessFlat(out, last.at(t.c), last.at(t.b), last.at(t.a))
                } else {
                    triangleUnlessFlat(out, last.at(t.a), last.at(t.b), last.at(t.c))
                }
            }
        }

        /**
         * The rings [emit] steps on: every leg's own, and for a **sliding** one the stations at which the
         * cap's line passes a vertex of the section as well.
         *
         * *Why those stations and no others.* Between two of them the same vertices stand beyond the plane,
         * so each one moves **affinely** with the station and the strip between two rings is the solid
         * exactly rather than nearly — where a plain two-ring slide loses a fifth of a bevelled corner to
         * the straight line it draws between a full section and a collapsed one. A **turning** leg already
         * steps on the upright band's own chords, finely enough that its own sag rule covers this too.
         */
        fun stationsFor(
            piece: Piece,
            n3: Vec3,
            d0: Double,
        ): List<Placement> {
            val out = ArrayList<Placement>()
            for ((k, leg) in legs.withIndex()) {
                val rs =
                    if (leg.pivot != null) {
                        leg.rings
                    } else {
                        val from = leg.rings.first()
                        val to = leg.rings.last()
                        val den = (to.origin - from.origin).dot(n3)
                        val cuts =
                            if (abs(den) <= 1e-12) {
                                emptyList()
                            } else {
                                piece.grown
                                    .map { q -> (d0 - from.origin.dot(n3) - (from.cx.dot(n3) * q.x + from.cy.dot(n3) * q.y)) / den }
                                    .filter { it > 1e-9 && it < 1.0 - 1e-9 }
                                    .sorted()
                            }
                        (listOf(0.0) + cuts + listOf(1.0)).mapNotNull { placeAt(k + it) }
                    }
                for ((m, p) in rs.withIndex()) if (out.isEmpty() || m > 0) out.add(p)
            }
            return out
        }

        override fun label(pieces: List<Piece>): Msg =
            Msgs.nameBlendRunOutCorner(
                first = pieces[a].crease.edge.name.label,
                second = pieces[b].crease.edge.name.label,
                face = third.name.label,
            )

        /**
         * Where the **cap's own boundary** stands at walk parameter [t] — the point at which the third
         * face's plane crosses the travelling section's blend curve — or null while the walk is still wholly
         * on the material side of it.
         *
         * This is the very line [emit] clamps every ring to, read on the *plain* section rather than the
         * grown one: the tool is a micron proud of itself, the drawing is not.
         */
        fun capAt(
            pieces: List<Piece>,
            t: Double,
        ): Vec3? {
            val piece = pieces[a]
            val plane = third.plane ?: return null
            val n3 = plane.normal.normalized()
            val p = placeAt(t) ?: return null
            val g = Vec2(p.cx.dot(n3), p.cy.dot(n3))
            if (g.length() <= DIR_EPS) return null
            val depth = p.origin.dot(n3) - plane.origin.dot(n3)
            val hits = piece.wedge.pieces.flatMap { sectionOnPlane(it, g, -depth) }
            return hits.minByOrNull { it.length() }?.let { p.at(it) }
        }

        /**
         * The walk parameter at which the section **first reaches** the third face, by halving — before it
         * the tangency on [shared] is still on that face and after it the cap stands there instead, so this
         * one number is where the two outline corrections hand over to each other.
         */
        fun capStart(pieces: List<Piece>): Double? {
            val hi = legs.size.toDouble()
            if (capAt(pieces, hi) == null) return null
            if (capAt(pieces, 0.0) != null) return 0.0
            var lo = 0.0
            var top = hi
            repeat(BISECT_STEPS) {
                val mid = (lo + top) / 2.0
                if (capAt(pieces, mid) == null) lo = mid else top = mid
            }
            return top
        }

        /**
         * The corner's own correction of the **third** face: the boundary of the flat top the plane leaves
         * on the walk, from [tStart] to the walk's end — a **fitted** cubic chain, and said so here.
         *
         * *Why fitted.* The curve is the walk's surface met by a plane **parallel to its own axis**, which
         * for a rounded pair is a torus met that way: a quartic (a spiric of Perseus) and no member of this
         * drawing's vocabulary. OP-31's Tier B is the decision that admits it — *"an approximation is better
         * than nothing at all"* (the user) — so it is a chain of cubics through points that are every one of
         * them **exact** on the surface and on the plane, halved until the midpoint of every span stands
         * within [Combine3.FIT_TOL_MM] of the true curve. The alternative was to leave the top face stating
         * a corner that is not on the body, which is the free end's notch bug one session on.
         */
        fun capChain(
            pieces: List<Piece>,
            tStart: Double,
        ): List<ProfileElement>? {
            val plane = third.plane ?: return null
            val hi = legs.size.toDouble()
            if (hi - tStart <= 1e-12) return null
            return fittedChain(Combine3.FIT_TOL_MM) { u ->
                capAt(pieces, tStart + (hi - tStart) * u)?.let { plane.toLocal(it) }
            }
        }

        /**
         * Where this corner **ends the band it turns about**: the fill covers the band's own surface over
         * the last stretch of its run, so the band stops where the fill's tangency on the face the two share
         * meets the band's own edge — its own setback, read from the fill's side.
         */
        override fun uprightEnd(pieces: List<Piece>): Pair<Pair<Int, Boolean>, Placement>? {
            val up = pieces[b]
            val seg = up.seg ?: return null
            val atStart = (seg.start - at).length() <= RING_TOL
            if (!atStart && (seg.end - at).length() > RING_TOL) return null
            val along = ((if (atStart) seg.end else seg.start) - at).normalized()
            val pair = pieces[a]
            val other = otherFace(pair, shared) ?: return null
            val t = if (other.name == pair.crease.face1.name) pair.wedge.t1 else pair.wedge.t2
            val depth = (pair.crease.e1 * t.x + pair.crease.ref.e2 * t.y).dot(along)
            if (depth <= Geom3.WELD_TOL) return null
            return (b to atStart) to Placement(at + along * depth, up.crease.e1, up.crease.ref.e2)
        }
    }

    /**
     * A curve this drawing has no name for, stated as a **chain of cubics through exact points** and within
     * [tol] of the true curve — OP-15's third class, the one OP-31's Tier B admits (*"an approximation is
     * better than nothing at all"*).
     *
     * Two things make it converge fast enough to be worth having, each of them measured rather than hoped:
     *
     * - each span is a **Hermite** cubic on the curve's own tangents, taken as a central difference at a
     *   width far below the span's, so the chain meets the curve to **fourth** order. Catmull–Rom's
     *   central-difference-of-knots tangents are second order and cost 256 spans where this takes 32; an
     *   arc-length re-reading of the knots was tried in between and is worse than useless here, because a
     *   piecewise-linear re-parameterization is only C0 and takes the fourth order away again.
     * - the span count is found by **halving and measuring**, the midpoint of every span against the true
     *   curve at the matching parameter — an upper bound on the geometric distance, exactly as
     *   [Intersect3]'s own ellipse fit states it.
     *
     * Every knot is a point of [at], so it is *on* the surface and on the plane; only the spans between are
     * fitted, and the tolerance is the one number that says how far.
     */
    private fun fittedChain(
        tol: Double,
        at: (Double) -> Vec2?,
    ): List<ProfileElement>? {
        // `dC/du`, second order everywhere: a central difference inside, and the three-point one-sided
        // formula at each end, so the ends are no worse than the middle. The step is wide enough that the
        // curve's own arithmetic noise (an `acos` near ±1 costs half the digits) divides away and far
        // narrower than any span this fit will take.
        val d = 1e-4

        fun slope(u: Double): Vec2? {
            if (u <= d) {
                val a = at(0.0) ?: return null
                val b = at(d) ?: return null
                val c = at(2.0 * d) ?: return null
                return (a * -3.0 + b * 4.0 - c) * (1.0 / (2.0 * d))
            }
            if (u >= 1.0 - d) {
                val a = at(1.0) ?: return null
                val b = at(1.0 - d) ?: return null
                val c = at(1.0 - 2.0 * d) ?: return null
                return (a * 3.0 - b * 4.0 + c) * (1.0 / (2.0 * d))
            }
            val lo = at(u - d) ?: return null
            val hi = at(u + d) ?: return null
            return (hi - lo) * (1.0 / (2.0 * d))
        }
        var n = 2
        var best: List<ProfileElement>? = null
        repeat(9) {
            val pts = (0..n).map { at(it.toDouble() / n) ?: return@repeat }
            val ms = (0..n).map { slope(it.toDouble() / n) ?: return@repeat }
            val chain =
                (0 until n).map {
                    ProfileElement.BezierE(
                        Bezier(
                            pts[it],
                            pts[it] + ms[it] * (1.0 / (3.0 * n)),
                            pts[it + 1] - ms[it + 1] * (1.0 / (3.0 * n)),
                            pts[it + 1],
                        ),
                    )
                }
            best = chain
            var worst = 0.0
            for (k in chain.indices) {
                val b = chain[k].bezier
                val mid = (b.p0 + b.p1 * 3.0 + b.p2 * 3.0 + b.p3) * (1.0 / 8.0)
                val exact = at((k + 0.5) / n) ?: continue
                worst = max(worst, (mid - exact).length())
            }
            if (worst <= tol) return chain
            n *= 2
        }
        return best
    }

    /**
     * [section], clipped to the plane `(n, d)` at one ring and returned as a polygon of a **fixed** count —
     * every point that stands beyond the plane pulled back onto it along the plane's own gradient in the
     * section's frame, and every edge carrying one extra point that is the **crossing** where there is one.
     *
     * *Why the pull-back is the clip and not merely near it.* The plane reads in the section's own
     * coordinates as an affine condition whose gradient points along the section's reach into the shared
     * face — the one direction the wedge is monotone in, each row of it one interval between the crease and
     * the blend curve. So moving every point that stands beyond back **along that gradient** lands it on the
     * plane and nowhere else, and a row that lies wholly beyond collapses to zero width, which is the empty
     * row said in the same arithmetic.
     *
     * *Why the extra point per edge.* Pulling the **vertices** back is not enough: a boundary edge that
     * crosses the line has its far end pulled onto the line, and the straight run to it cuts the corner the
     * crossing makes — a whole fifth of a bevelled corner, whose section is a triangle with one long edge
     * and no other point on it. One extra vertex per edge, at the crossing where there is one and on top of
     * the edge's own start where there is not, makes the clipped polygon exact and keeps the count fixed, so
     * the tube still stitches ring to ring with no T-junction anywhere. The duplicates that leaves are
     * zero-area triangles, which [triangleUnlessFlat] drops.
     */
    private fun clampedSection(
        section: List<Vec2>,
        p: Placement,
        n: Vec3,
        d: Double,
    ): List<Vec2> {
        val gx = p.cx.dot(n)
        val gy = p.cy.dot(n)
        val g2 = gx * gx + gy * gy
        val c = p.origin.dot(n) - d
        // …and the count is the same **even where the plane has no gradient here at all** (a ring standing
        // square to it, where nothing is clipped): a ring of the plain count beside one of the doubled count
        // is a T-junction, and the tube would not close along it
        val flat = g2 <= 1e-18

        fun off(q: Vec2) = if (flat) -1.0 else c + gx * q.x + gy * q.y

        fun pull(q: Vec2): Vec2 {
            val f = off(q)
            return if (f <= 0.0) q else Vec2(q.x - gx * f / g2, q.y - gy * f / g2)
        }
        val out = ArrayList<Vec2>(2 * section.size)
        for (i in section.indices) {
            val a = section[i]
            val b = section[(i + 1) % section.size]
            out.add(pull(a))
            val fa = off(a)
            val fb = off(b)
            // where the plane meets this edge, read on the **carrier** and then held to the edge's own span:
            // a strict sign change would put the extra point back on a vertex exactly at the station where
            // the crossing arrives there, and the strip either side of that station would then draw the
            // straight line the crossing is meant to replace
            val t = if (fa == fb) 0.0 else (fa / (fa - fb)).coerceIn(0.0, 1.0)
            out.add(pull(a + (b - a) * t))
        }
        return out
    }

    /** [section] triangulated as a cap, its own points de-duplicated first; empty where it has no area. */
    private fun capsOf(section: List<Vec2>): List<Geom3.Tri3> {
        val distinct = ArrayList<Vec2>(section.size)
        for (q in section) if (distinct.isEmpty() || (q - distinct.last()).length() > Geom3.WELD_TOL) distinct.add(q)
        while (distinct.size > 1 && (distinct.first() - distinct.last()).length() <= Geom3.WELD_TOL) distinct.removeAt(distinct.size - 1)
        if (distinct.size < 3) return emptyList()
        return Geom3.triangulate(Geom3.TessRegion(distinct, emptyList())).first ?: emptyList()
    }

    /** [out] gains this triangle unless its three corners do not span one — a clamped row has no area. */
    private fun triangleUnlessFlat(
        out: Geom3.MeshBuilder,
        a: Vec3,
        b: Vec3,
        c: Vec3,
    ) {
        if ((b - a).cross(c - a).length() <= 1e-18) return
        out.triangle(a, b, c)
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
     * A **trihedral vertex**: three bands meet, the ball touches all three faces at once, and the corner is
     * the patch its own surface makes between the three band ends (GitHub #32; session 81's concave reading).
     *
     * *One ball, read from either side.* At a **convex** vertex the ball sits in the material and the corner
     * cell keeps its octant of material. At a **concave** one — three fills at a room's own corner — the
     * ball sits against the three faces from the *air* side, its centre at `(r, r, r)` from the vertex
     * **along** the three outward normals rather than against them, and the corner cell keeps its octant of
     * **air**: the patch is added rather than taken. Everything below is the same object; what turns with
     * the sign is where the three flat quads are grown to and which way they face, both of them read off the
     * fill's own wedge in [vertexPatch].
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

        override fun label(pieces: List<Piece>): Msg =
            Msgs.nameBlendVertexCorner(list = Msg.joined(members.map { pieces[it.first].crease.edge.name.label }, ", "))

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
                Msgs.refusalBlendIsNotPlaneItIs(name = name.label, name2 = patch.surface?.band?.label ?: Msgs.nameBandUnnamed()),
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
     *
     * The solve is **the same arithmetic for a concave vertex** (session 81): the tangency lines still cross
     * where the ball's own foot is, and the only thing that reads differently is which side of each face the
     * ball stands on, which is already carried by the wedge the tangencies come out of.
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
        // **the one sign the whole patch turns on** (session 81). Every step below is stated from the
        // fill's own wedge rather than flipped by trial: [outwardAt] steps each leg *out of the wedge*,
        // which at a convex crease is out of the material and at a concave one **into** it, so the grown
        // leg stands a micron on the far side of its face from the tool; and the tool's own outside there
        // is the side its interior is not on, which is the face's normal for a corner that is subtracted
        // and its negative for one that is united. The ball is the same object either way — the corner cell
        // keeps the ball's own octant, of material at a convex vertex and of air at a concave one — so the
        // fill's winding, which faces the ball's centre, does not turn at all.
        val convex = pieces[trio[0].first].choice.convex
        val step = if (convex) GROW_MM else -GROW_MM
        val rings = members.map { m -> pieces[m.first].grown.map { m.third.at(it) } }
        if (rings.any { it.size < 5 }) return null
        val tangency = arrayOfNulls<Vec3>(3)
        val steppedTangency = arrayOfNulls<Vec3>(3)
        val blends = ArrayList<List<Vec3>>(3)
        for (ring in rings) {
            val n = ring.size
            for ((plain, stepped) in listOf(ring[2] to ring[1], ring[n - 2] to ring[n - 1])) {
                val k = faces.indices.firstOrNull { abs(offPlane(faces[it].plane!!, plain)) <= RING_TOL } ?: return null
                if (abs(offPlane(faces[k].plane!!, stepped) - step) > RING_TOL) return null
                val known = tangency[k]
                if (known != null && (known - plain).length() > RING_TOL) return null
                tangency[k] = plain
                steppedTangency[k] = stepped
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
                    n to (f.plane.origin.dot(n) + step)
                },
            ) ?: return null
        for (k in faces.indices) {
            val want = faces[k].plane!!.normal.normalized() * (if (convex) 1.0 else -1.0)
            val cp = rings[onFace[k][0]][0]
            val cq = rings[onFace[k][1]][0]
            val tk = steppedTangency[k]!!
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
        // **the fill is wound against its own surface, not against the vertex** (the probe of GitHub #33).
        // Asking each triangle to face away from [at] reads the patch one triangle at a time, and at a sharp
        // enough vertex — a dart's 19° tip — the answer disagrees with the patch's own winding for a few of
        // them: those come out flipped, the same directed edge is emitted twice, and the tool is refused as
        // no closed shell. The ball has an exact answer that is the same for every triangle of it: the tool
        // keeps the ball, so its surface there faces the ball's **centre**. A bevel's apex has no centre and
        // keeps the reading it always had — three planar triangles, where the vertex is the right reference.
        for ((a, b, c) in fill) {
            val g = (a + b + c) * (1.0 / 3.0)
            out.add(facing(a, b, c, ball?.let { it.first - g } ?: (g - at)))
        }
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
     *
     * The rings are wound by the loop's own order and **nothing re-reads them one triangle at a time** —
     * see the note on the fill in [vertexPatch], which is where a per-triangle reading went wrong.
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
        var refusal: Msg? = null
        val taken = HashSet<Pair<Int, Boolean>>()
        // **vertices first, and that order is the rule.** A ring is shared by two tubes, so three bands at
        // one point cannot be three crossings; taking the vertex first is what stops two of them claiming
        // each other and leaving the third to butt — which is exactly the crease GitHub #32 reported.
        for (i in pieces.indices) {
            for (j in i + 1 until pieces.size) {
                for (k in j + 1 until pieces.size) {
                    val three = listOf(i, j, k)
                    // **three of one sign**, and that is the whole of what a vertex asks (session 81). Three
                    // convex bands is the ball sitting in the corner; three **fills** is the same ball
                    // standing in a room's own corner from the air side, and the patch is its spherical
                    // triangle added rather than taken. A *mixed* trio is neither: there the pair pivots
                    // about the band between them ([Turn]), which the pass below builds.
                    if (three.any { pieces[it].seg == null }) continue
                    if (three.any { pieces[it].choice.convex != pieces[three[0]].choice.convex }) continue
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
                        if (!ringsAgree(a.grown.map { placeA.at(it) }, b.grown.map { placeB.at(it) })) {
                            // **the incongruent inside corner**, and it is a refusal rather than a silence
                            // (OP-31's matrix, session 83). Two wedges that are not congruent in the face
                            // they share land on no common ring, and at a **convex** corner that costs
                            // nothing — the two tools overlap and the boolean trims them exactly, which is
                            // session 79's cut (2) and stays. At an **inside** corner they never overlap at
                            // all: leaving the pair alone leaves GitHub #31's spike standing between the two
                            // band ends, silently, whenever the two roundings differ in size or in kind. So
                            // the pair is named here, with the cure, rather than built wrong.
                            if (!(turnsInward(a, aAtStart, bis) && turnsInward(b, bAtStart, bis)) && refusal == null) {
                                refusal =
                                    Msgs.refusalBlendInsideCornerNotCongruent(
                                        name = a.crease.edge.name.label,
                                        name2 = b.crease.edge.name.label,
                                        name3 = shared.name.label,
                                    )
                            }
                            continue
                        }
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
        // **the mixed-sign pair**, last (OP-31, item (b)). A fill meets a band's end, and the band has
        // rounded away the very face the fill was tangent to over the last `r_U` of its run: the two never
        // overlap, so there is nothing for a boolean to trim and the fill stands there as a ledge. Either it
        // turns about the band or the body is wrong, which is why this one is **refused by name** where the
        // turn cannot be stated rather than quietly left alone.
        for (i in pieces.indices) {
            for (j in pieces.indices) {
                if (i == j) continue
                // the fill travels and the band is pivoted about, always: a fill's ball rolls round the
                // **outside** of the band's convex surface (`r + r_U`, and that circle always exists), while
                // a band's ball would have to be tangent to the fill's concave one from inside, which is the
                // air the fill was put there to keep. See [Pivot].
                if (pieces[i].choice.convex || !pieces[j].choice.convex) continue
                val sa = pieces[i].seg ?: continue
                val sb = pieces[j].seg ?: continue
                for (aAtStart in listOf(true, false)) {
                    if ((i to aAtStart) in taken) continue
                    val corner = if (aAtStart) sa.start else sa.end
                    val bAtStart =
                        listOf(true, false).firstOrNull { ((if (it) sb.start else sb.end) - corner).length() <= RING_TOL } ?: continue
                    if ((j to bAtStart) in taken) continue
                    // a ring is shared by two tubes: three or more roundings ending here are the vertex's
                    // business or the two-ended pivot's, and both of those passes have already run
                    val here =
                        pieces.indices.count { k ->
                            pieces[k].seg?.let { (it.start - corner).length() <= RING_TOL || (it.end - corner).length() <= RING_TOL } == true
                        }
                    if (here != 2) continue
                    val (made, why) = mixedPivotOf(pieces, i, aAtStart, j, corner)
                    if (why != null && refusal == null) refusal = why
                    made ?: continue
                    out.add(made)
                    taken.add(i to aAtStart)
                }
            }
        }
        return Corners(out, refusal)
    }

    /**
     * The **one-ended pivot** where a fill meets a band's end, or the reason it cannot be stated, or neither
     * where this pair does not make one (OP-31, item (b)).
     *
     * Three faces stand at the vertex and each has one job. The **common** one is the face the band rounded
     * away and the fill was tangent to — the reason there is a corner here at all. The fill's **other** one
     * is the face the walk runs in: the band stands square to it, so the band's own end-section curve lies
     * *in* it and the pair's section follows that curve exactly as session 81's pivot follows an upright's.
     * The band's far face is the **third**, and it caps the walk: there is no second band to land on, so the
     * walk runs to the end of the band's curve and every ring is clipped to that plane ([Pivot.emit]).
     *
     * The one thing that has to hold at the end is that the walk arrives reaching **straight out through**
     * the third face. That is what says the section stands on the cap rather than crossing it at an angle,
     * and it is the same kind of statement [ringsAgree] makes of a two-ended one: where it fails the pair is
     * named rather than built.
     */
    private fun mixedPivotOf(
        pieces: List<Piece>,
        i: Int,
        aAtStart: Boolean,
        j: Int,
        at: Vec3,
    ): Pair<Pivot?, Msg?> {
        val a = pieces[i]
        val b = pieces[j]
        val common =
            listOf(a.crease.face1, a.crease.face2)
                .firstOrNull { f -> f.name == b.crease.face1.name || f.name == b.crease.face2.name } ?: return null to null
        val shared = otherFace(a, common) ?: return null to null
        val third = otherFace(b, common) ?: return null to null
        if (third.name == shared.name || third.name == common.name) return null to null
        val what =
            Msgs.refusalBlendRunOutCornerWhere(name = a.crease.edge.name.label, name2 = b.crease.edge.name.label, name3 = common.name.label)
        val n = shared.plane?.normal?.normalized() ?: return null to Msgs.refusalBlendRunOutHasNoPlane(what = what, name = shared.name.label)
        val n3 = third.plane?.normal?.normalized() ?: return null to Msgs.refusalBlendRunOutHasNoPlane(what = what, name = third.name.label)
        val ea = inFaceOf(a, shared) ?: return null to null
        if (a.grown.maxOf { it.length() } <= Geom3.WELD_TOL) return null to null
        val total = signedTurn(ea, n3, n)
        if (abs(total) <= TANGENT_TOL || abs(abs(total) - PI) <= TANGENT_TOL) {
            return null to Msgs.refusalBlendRunOutDoesNotTurn(what = what, name = third.name.label)
        }
        // …and the walk ends reaching **straight out through** the third face rather than on a second band's
        // own end section. That is the one-ended pivot's whole difference from [Turn], and it is one
        // argument: a bevelled band leaves the section sliding along its own plane, so the ball still has to
        // pivot about the bevel's far rail before the cap can take it — the very last leg a two-ended walk
        // takes onto its partner, taken here onto the plane instead.
        val (walked, why) = uprightLegs(pieces, i, aAtStart, j, shared, at, ea, n3, total, n, what)
        if (walked == null) return null to why
        if (walked.dir.dot(n3) < 1.0 - 1e-6) return null to Msgs.refusalBlendRunOutDoesNotTurn(what = what, name = third.name.label)
        return Pivot(i, aAtStart, j, shared, third, walked.legs, at) to null
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
    private class Corners(val list: List<Corner>, val refusal: Msg?)

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
    ): Pair<Turn?, Msg?> {
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
        val what =
            Msgs.refusalBlendInsideCornerWhereMeets(name = a.crease.edge.name.label, name2 = b.crease.edge.name.label, name3 = shared.name.label)
        val (walked, why) = uprightLegs(pieces, i, aAtStart, u, shared, at, ea, eb, total, n, what)
        if (walked == null) return null to why
        val legs = walked.legs
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
        u: Int,
        shared: FacePatch,
        at: Vec3,
        ea: Vec3,
        eb: Vec3?,
        total: Double,
        n: Vec3,
        what: Msg,
    ): Pair<Walked?, Msg?> {
        val a = pieces[i]
        val up = pieces[u]
        val seg =
            up.seg ?: return null to
                Msgs.refusalBlendIsNotOneStraightRun(name = up.crease.edge.name.label, what = what)
        // **the upright has to stand square to the shared face**, or the curve the pair's section follows is
        // not a curve *in* that face at all and there is no pivot to state (OP-3: a refusal, not a guess)
        val along = (seg.end - seg.start).normalized()
        if (abs(abs(along.dot(n)) - 1.0) > 1e-6) {
            return null to
                Msgs.refusalBlendIsNotSquareSoCannot(name = up.crease.edge.name.label, name2 = shared.name.label, what = what)
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
                Msgs.refusalBlendRoundingDoesNotMeetAlong(what = what, name = up.crease.edge.name.label, name2 = a.crease.edge.name.label)
        }
        val legs = ArrayList<Leg>()
        var place = Placement(start, a.crease.e1, a.crease.ref.e2)
        var dir = ea
        var acc = 0.0
        for (p in curve) {
            val here = plan(GeomMath.startOf(p))
            if ((place.origin - here).length() > RING_TOL) {
                return null to Msgs.refusalBlendOwnProfileIsNotOne(what = what, name = up.crease.edge.name.label)
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
        // …and where a **second** band closes the walk, the last turn onto its own end section. A
        // [Pivot] has none — the third face caps it instead — so it walks to the end of the curve and
        // stops (OP-31, item (b)).
        if (eb != null) {
            val last = signedTurn(dir, eb, n)
            if (abs(last) > TANGENT_TOL) {
                val leg = turnLeg(a, place, place.origin, n, dir, last)
                legs.add(leg)
                dir = eb
                acc += last
            }
            // the whole walk has to turn the corner and nothing more: a profile that doubles back would
            // sweep the section through itself, and inventing what that means is the one thing this
            // drawing does not do
            if (abs(acc - total) > 1e-6) return null to profileTurnRefusal(what, up)
        }
        return Walked(legs, dir, acc) to null
    }

    /** A finished walk: its legs, the in-face direction it ends reaching along, and how far it turned. */
    private class Walked(val legs: List<Leg>, val dir: Vec3, val turned: Double)

    /** Why a drawn upright's own profile cannot carry the corner, in the corner's own words. */
    private fun profileTurnRefusal(
        what: Msg,
        up: Piece,
    ): Msg =
        Msgs.refusalBlendTurnsAboutWhoseOwnDoubles(what = what, name = up.crease.edge.name.label, word = up.sec.kind.word)

    /** Why the two roundings' end sections do not meet on one ring once the upright is rounded. */
    private fun mismatchedTurn(
        pieces: List<Piece>,
        i: Int,
        j: Int,
        u: Int,
        shared: FacePatch,
    ): Msg =
        Msgs.refusalBlendInsideCornerWhereMeetsIs(name = pieces[i].crease.edge.name.label, name2 = pieces[j].crease.edge.name.label, name3 = shared.name.label, name4 = pieces[u].crease.edge.name.label)

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
     *
     * **The one ring that is not stepped off is the one standing on a pivot axis** (the probe of GitHub
     * #33). At an inside corner the ball turns about the upright ([Turn]), and at that corner station the
     * band's leg in the *other* face **lies along** that upright — the whole leg, both of its ends, not
     * merely a point of it. Step it a micron off its face and it is a micron off the **axis**, and the turn
     * sweeps it round as a micron-wide disc whose plane is the very plane the tube's own jog lies in: the
     * tool folds back over itself there, and the boolean hands the fold on to the body (`MeshCanon.flap`
     * names it at `z = 20 − r` on a dart's 100° reflex vertex). So such a `Turn` end takes the **plain**
     * section, its leg back on the axis where the turn leaves it exactly fixed, and the tube tapers to it
     * over its own run — which is what a *free* end did for three sessions before #33 moved it, so it is a
     * construction this build already knows works, applied where it is now the one that has to hold.
     *
     * Every other ring keeps the step-off, because none of them stands on an axis anything turns about: a
     * mitre, a ball vertex, a free end — and a turn about a **band** ([Turn.extra], session 81's mixed
     * pivot), where the axis is that band's own and stands `r_U` clear of the section, so no point of the
     * section is at radius zero and stepping the legs off costs nothing.
     */
    private fun toolMesh(
        pieces: List<Piece>,
        group: List<Int>,
        rings: Map<Pair<Int, Boolean>, Placement>,
        butts: Set<Pair<Int, Boolean>>,
        corners: List<Corner>,
    ): Pair<Mesh3?, Msg?> {
        val b = Geom3.MeshBuilder()
        // the corners' own surfaces first, so the tool is one shell before a single tube is drawn
        for (c in corners) if (c.ends.any { it.first in group }) c.emit(pieces, b)
        // the ends that stand **on** a pivot axis: a turn about a sharp upright, and only that one
        val pivots = HashSet<Pair<Int, Boolean>>()
        for (c in corners) if (c is Turn && c.extra.isEmpty()) pivots.addAll(c.ends)
        // …and the band ends a **one-ended pivot** covers with its own fill (OP-31, item (b)). [endSteps]
        // pulls such an end back a micron because the shared face runs on past it while the other stops —
        // which is what an inside corner is, and there the micron of unrounded ridge stands beside a corner
        // that keeps a whole spike anyway. Here the fill covers that very stretch, so the ridge is left
        // *inside* the finished part with the fill's own surface a micron off it, and the boolean answers
        // that pair with a sliver. The tube overshoots instead: it removes only material the fill puts back.
        val covered = HashSet<Pair<Int, Boolean>>()
        for (c in corners) if (c is Pivot) c.uprightEnd(pieces)?.let { covered.add(it.first) }
        for (at in group) {
            val piece = pieces[at]
            val seg = piece.seg ?: return null to Msgs.refusalBlendIsNotOneStraightRun2(name = piece.crease.edge.name.label)
            val atStart = rings[at to true]
            val atEnd = rings[at to false]
            val u = (seg.end - seg.start).normalized()
            // a butting pair keeps its own micron of daylight, and every other free end is stepped by what
            // lies beyond it ([endSteps]) — the two agree wherever both speak, since a butt *is* an inside
            // corner, and the pair is kept named because that is where the rule was first written down
            val back0 =
                if ((at to true) in covered) {
                    -GROW_MM
                } else if ((at to true) in butts) {
                    GROW_MM
                } else {
                    piece.backAtStart
                }
            val back1 =
                if ((at to false) in covered) {
                    -GROW_MM
                } else if ((at to false) in butts) {
                    GROW_MM
                } else {
                    piece.backAtEnd
                }
            val p0 = atStart ?: Placement(seg.start + u * back0, piece.crease.e1, piece.crease.ref.e2)
            val p1 = atEnd ?: Placement(seg.end - u * back1, piece.crease.e1, piece.crease.ref.e2)
            // **a band that is already off the body contributes its corner ring as a cap and no tube at
            // all** (OP-31, item 3; the matrix's own `ORDER_DECIDES_THE_BODY`).
            //
            // Session 79 put such a band in the tool so that the corner where a *fresh* one meets it is
            // built by construction rather than looked for, and said of the tube itself that *"cutting a
            // band that is already off costs nothing — a coincident-face no-op"*. It is not a no-op. The
            // tube's whole surface **is** the body's own band surface there, which is the very contact
            // session 81 wrote the step-off to abolish (*"a tool never shares a face with the body"*) — and
            // the step-off only moves the two straight **legs** off their faces; the band's own arc is left
            // standing exactly on the body's. Whether a boolean resolves a coincident cylindrical strip is a
            // fraction of a float32: on the L-block two of the thirty-six pairs came out with the surface
            // folded back on itself and `MeshCanon.flap` refused them by name, at the *far* end of the band
            // that was rounded first and not at the corner the two share — and only in one of the two
            // stacking orders, which is exactly the reporter's *"works fine in some cases but creates
            // nonsense in others"*.
            //
            // The cure is to stop cutting it. What the tube would remove is already removed, so a tool that
            // stops at the corner ring and closes there removes precisely the same material — the corner is
            // still built, the volume cannot move, and the tool shares no surface with the body any more.
            // The cap faces the way that band's own free-end cap would, so the shell closes round the fresh
            // tube and the corner's own patch. It costs no boolean; the alternative considered and rejected
            // was to rebuild the chain from its undressed root at every such level, which cures it too and
            // puts back the `O(n)` booleans per level that OP-30's part 1 measured and removed.
            // …and only where every corner that claims one of its ends is a **crossing with a fresh band**.
            // A crossing's ring is a plane through both tubes and the tube beyond it is re-cutting and
            // nothing else, so capping there is the whole of what it did. Two conditions narrow it, and
            // each is a shape the cap cannot answer: a **walk**'s corner surface is generated from the
            // travelling section and its ends do work besides cutting — a pivot ends the band it turns
            // about, a butt keeps its micron of daylight — so a walk keeps the tube it always had; and a
            // crossing between two bands that are **both** already off would leave a hole in the middle of
            // a stitched run of tubes, with the two caps facing each other across it. The two cells the
            // matrix's residue named are both a fresh band crossing a single existing one.
            val crossingOnly =
                corners.filter { c -> c.ends.any { it.first == at } }.let { mine ->
                    mine.isNotEmpty() && mine.all { c -> c is Joint && c.ends.all { it.first == at || !pieces[it.first].existing } }
                }
            if (piece.existing && crossingOnly) {
                // …wound **against** this band's own free-end cap: the cap is not closing this tube, which
                // is not there any more, but the fresh one that ends on the same ring, so it faces the way
                // the missing tube ran rather than away from it.
                if (atStart != null) for (t in piece.caps) b.triangle(p0.at(t.a), p0.at(t.b), p0.at(t.c))
                if (atEnd != null) for (t in piece.caps) b.triangle(p1.at(t.c), p1.at(t.b), p1.at(t.a))
                continue
            }
            // stepped off everywhere but on a pivot axis: a tool never shares a face with the body, and
            // never folds over itself at a turn either ([sectionOf], GitHub #33 and its probe)
            val s0 = if ((at to true) in pivots) piece.plain else piece.grown
            val s1 = if ((at to false) in pivots) piece.plain else piece.grown
            val r0 = s0.map { p0.at(it) }
            val r1 = s1.map { p1.at(it) }
            for (m in piece.grown.indices) {
                val n = (m + 1) % piece.grown.size
                b.triangle(r0[m], r0[n], r1[n])
                b.triangle(r0[m], r1[n], r1[m])
            }
            if (atStart == null) for (t in piece.caps) b.triangle(p0.at(t.c), p0.at(t.b), p0.at(t.a))
            if (atEnd == null) for (t in piece.caps) b.triangle(p1.at(t.a), p1.at(t.b), p1.at(t.c))
        }
        val mesh = b.build()
        if (mesh.triangles.isEmpty()) return null to Msgs.refusalBlendRoundingOwnToolHasNo()
        if (Geom3.volume(mesh) <= 0.0) return null to Msgs.refusalBlendRoundingOwnToolEnclosesNo()
        // Closedness, and deliberately **not** [MeshCanon.flap]. The tool is a *union of bands*, and where
        // one of them pivots about an upright ([Turn]) the section's own tangency stands on the pivot axis
        // while its stepped-off twin a micron away sweeps a micron-wide disc round it — so the tool overlaps
        // itself by exactly that micron there, which is a fold in the tool and no fold at all in the body it
        // cuts. A flap is a statement about a *result* (OP-9), and that is where it is asked ([MeshCanon.fault]).
        MeshCanon.notClosed(mesh)?.let { return null to Msgs.refusalBlendRoundingOwnToolIsNot(itWord = it) }
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
    ): Pair<List<BlendChoice>?, Msg?> {
        val feature = base.feature
        val (edges, whyEdges) = Section3.edges(feature)
        if (edges == null) return null to whyEdges
        val reach = sec.reach()
        if (reach <= Geom3.WELD_TOL) return null to Msgs.refusalBlendThisHasNoSizeAll(word = sec.kind.word)
        val out = ArrayList<BlendChoice>(targets.size)
        for (i in targets) {
            val edge = edges.getOrNull(i) ?: return null to Msgs.refusalBlendThisSolidHasNoEdge2(i = i + 1)
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
        /**
         * The section run along each of [targets] — **one per target** (OP-30's next step), so a dressing of
         * three sizes is one pass rather than three chained blends. [blended] with one section for the whole
         * list is the overload below, which is what a single gesture and the mesh tier still call.
         */
        sections: List<BlendSection>,
        choices: List<BlendChoice>,
        /**
         * The **tombstones** — positions in [targets] whose rounding was removed, mapped to the band slots
         * it keeps ([Feature3.Blend.absent]). They contribute no piece, no tool and no boolean here; they
         * exist only so the dressed face and edge lists keep their slots.
         */
        absent: Map<Int, Int> = emptyMap(),
        /**
         * The **undressed root** of the chain [base] stands on — the body every rounding in it was cut out
         * of — and null where there is none (a first blend, or one whose tip an ordinary boolean made).
         *
         * A fact about the *graph*, handed over by the node that built this blend rather than discovered
         * here (OP-21): it is needed only when this gesture rounds an upright an earlier corner pivots
         * about, and then it is needed absolutely — see the note at the rebuild below.
         */
        root: Solid3? = null,
    ): Pair<Solid3?, Msg?> {
        if (sections.size < targets.size) return null to Msgs.refusalBlendThisBlendRecordedSectionsEdges(count = sections.size, count2 = targets.size)
        for ((k, sec) in sections.withIndex()) {
            if (k in absent || k >= targets.size) continue
            if (sec.kind != BlendKind.PROFILE && sec.size <= Geom3.WELD_TOL) {
                return null to Msgs.refusalBlendNeedsPositiveThisOneIs(word = sec.kind.word, sizeWord = sec.kind.sizeWord, mm = Frames3.mm(sec.size))
            }
            if (sec.kind == BlendKind.PROFILE && sec.profile.isEmpty()) {
                return null to Msgs.refusalBlendNeedsDrawnProfileRunAlong(word = sec.kind.word)
            }
        }
        val feature = base.feature
        val (edges, whyEdges) = Section3.edges(feature)
        if (edges == null) return null to whyEdges
        if (choices.size < targets.size) return null to Msgs.refusalBlendThisBlendRecordedChoicesEdges(count = choices.size, count2 = targets.size)
        // **Every target prepared first, then the corners, then one tool per group.** The pieces are built
        // in the step's own order, so a blend with no corner in it reaches [Geom3.sweep] exactly as it did
        // before this session and its triangles are the same triangles. A **tombstoned** target is skipped
        // outright: it keeps a slot in the dressed lists and nothing else (OP-30).
        val pieces = ArrayList<Piece>()
        for ((k, i) in targets.withIndex()) {
            if (k in absent) continue
            val sec = sections[k]
            val edge = edges.getOrNull(i) ?: return null to Msgs.refusalBlendThisSolidHasNoEdge3(i = i + 1, count = edges.size)
            val (crease, why) = creaseOf(feature, edge)
            if (crease == null) return null to why
            val choice = choices[k]
            val (wedge, whyWedge) = wedgeOf(crease, sec, choice)
            if (wedge == null) return null to whyWedge
            if (!tangenciesFit(crease, wedge)) {
                val fits = largestFitting(crease, sec, choice)
                return null to
                    Msgs.refusalBlendReachesPastLargestThatFits(
                        word = sec.kind.word,
                        sizePhrase = sec.sizePhrase(),
                        name = crease.face1.name.label,
                        name2 = crease.face2.name.label,
                        name3 = crease.edge.name.label,
                        fitPhrase = sec.fitPhrase(fits),
                    )
            }
            val (piece, whyPiece) = pieceOf(i, false, crease, wedge, choice, sec)
            if (piece == null) return null to whyPiece
            pieces.add(piece)
        }
        // **A dressing whose every rounding has been removed is its own base** — nothing fresh is cut, so
        // there is no tool, no corner and no boolean, and the bands under it (if any) are already off
        // (OP-30's tombstone). Stated here rather than reached through [cornersOf], which would answer the
        // same body the long way round.
        if (pieces.isEmpty()) return applyTo to null
        // …and the bands already under this one, so a blend of a blend on an adjacent edge builds the same
        // corner a one-gesture chain would (GitHub #27, [chainPieces]).
        pieces.addAll(chainPieces(feature))
        val found = cornersOf(pieces)
        found.refusal?.let { return null to it }
        val corners = found.list
        crowdedCorner(pieces, corners)?.let { c ->
            // …in the words of the **fresh** rounding the corner crowds, since a pass may run several
            // sections and only one of them is the gesture the user is making (OP-3: the reason belongs
            // where the decision is)
            val blamed = c.ends.map { pieces[it.first] }.firstOrNull { !it.existing } ?: pieces[c.ends.first().first]
            val fits = largestCornerFitting(pieces)
            return null to
                Msgs.refusalBlendIsTooSharpCornerRoundings(name = c.label(pieces), word = blamed.sec.kind.word, sizePhrase = blamed.sec.sizePhrase(), fitPhrase = blamed.sec.fitPhrase(fits))
        }
        mixedCorner(pieces, corners)?.let { return null to it }
        val rings = HashMap<Pair<Int, Boolean>, Placement>()
        for (c in corners) for (end in c.ends) rings[end] = c.ringAt(end)
        val groups = groupsOf(pieces.size, corners)

        fun apply(
            body: Solid3,
            group: List<Int>,
        ): Pair<Solid3?, Msg?> {
            val lead = pieces[group.firstOrNull { !pieces[it].existing } ?: group.first()]
            val (tool, whyTool) =
                if (group.size == 1 && !(lead.seg != null && lead.stepped)) {
                    // **the two tools that are still one sweep.** A crease that is not a straight run — a
                    // cap edge's circle, a ring — has no tube to stitch and no free end to step; and a wedge
                    // with a **round** leg has no step-off in this vocabulary at all. Both are swept exactly
                    // as they always were, the first with the **grown** section (which is the whole of what
                    // GitHub #33 asks of a tool that has one) and the second with its own, unmoved.
                    revolvedBand(lead)
                        ?: Geom3.sweep(lead.crease.path, lead.crease.e1, SweepProfile.Section(lead.grownRegion), plan = null)
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
                return null to Msgs.refusalQualified(name = lead.crease.edge.name.label, reason = whyTool ?: Msgs.refusalBlendCannotBeSweptAlongIt())
            }
            val op = if (lead.choice.convex) BoolOp.SUBTRACT else BoolOp.UNION
            val (next, whyBool) = Geom3.combine(op, body, tool)
            if (next == null) {
                return null to Msgs.refusalQualified(name = lead.crease.edge.name.label, reason = whyBool ?: Msgs.refusalBlendCannotBeAppliedToBody())
            }
            return next to null
        }

        // **A corner that turns about a band rebuilds the chain from the root at the level where it is
        // *fresh*, and the tip cannot be re-cut** (session 81, tightened by GitHub #35 part 1).
        //
        // *Why a rebuild is needed at all.* A corner about a rounded upright sets its two bands **back**
        // along their own edges and turns the pivot on a **wider** circle. So the moment the upright becomes
        // a band, the two bands already on the body have run **past** where the corner now ends them — and a
        // further boolean of the same sign can never take that back (a second subtraction only removes more;
        // a second union only adds more). The whole chain is therefore rebuilt from its own undressed root,
        // every group applied in dependency order, and for the two sectors that is `(root ∪ upright) − chain'`
        // and `(root − upright) ∪ fills'` — both the rolling ball's own answer.
        //
        // *And why only where the corner is **fresh**.* The argument above is about a corner **arriving**:
        // it is the level that first states the pair's set-back ends and its wider pivot that meets bands
        // already run past them. Once that level has rebuilt, the chain on the body *is* the corner's own
        // answer, and every later rounding is an ordinary fresh group again — nothing about it moves a
        // set-back or widens a circle, and the corner it does not touch is already right. Session 81 said
        // *"any corner that turns about an upright"*, so **every** level after the first mixed corner
        // rebuilt the whole chain: `O(n)` booleans per level and `O(n²)` for a recompute, which is the
        // second half of what GitHub #35 measured. The rule the argument actually carries is: **a corner
        // that turns about a band rebuilds at the level where it is fresh** — where one of its two ends, or
        // its upright, is a rounding *this* gesture makes. That is [cornerFacesOf]'s own freshness test, and
        // deliberately the same one: the level that *lists* the corner as a new face of the body is the
        // level that has to build the body it is a face of.
        //
        // *Why the pair counts and not only the upright.* GitHub #35 asked whether a fresh **pair** about an
        // already-rounded upright could skip the rebuild too, on the grounds that nothing has run past
        // anything yet. It cannot, and `BlendMixedVertexTest.theOtherSectorPivotsTheSameWay` is the fixture
        // that proves it: the cavity's two fills are rounded one gesture at a time, so the **first** fill
        // was united at its full length while there was no second band to make a corner with — and when the
        // second arrives, the corner sets that first band *back*, which a further union can never do. The
        // body came out 2.107 mm³ heavier than the same three gestures in the other order (40007.239 against
        // 40005.132 mm³), which is precisely the tail of the first fill standing where the ring torus should.
        // The same reading holds one sector over: the upright's own band is ended by the corner too, and a
        // second subtraction cannot give back what the full-length cut took.
        //
        // Where no corner about a band is fresh the old path stands untouched — fresh groups applied to the
        // tip — so no existing drawing's mesh moves by more than the general engine's own float32 noise.
        val stale = corners.any { c -> c.extra.isNotEmpty() && (c.ends.any { !pieces[it.first].existing } || c.extra.any { !pieces[it].existing }) }
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
                Msgs.refusalBlendRoundingItReTurnsCorner(first = (pieces.firstOrNull { !it.existing } ?: pieces.first()).crease.edge.name.label)
        val ordered =
            orderedGroups(pieces, groups, corners) ?: return null to
                Msgs.refusalBlendTwoThisBodyRoundingsEach(first = (pieces.firstOrNull { !it.existing } ?: pieces.first()).crease.edge.name.label)
        var result = start
        for (group in ordered) {
            val (next, why) = apply(result, group)
            result = next ?: return null to why
        }
        return result to null
    }

    /**
     * [blended] with **one section for the whole target list** — one gesture's own reading of itself.
     *
     * The case a single rounding is, kept as its own call rather than made every caller's business: the mesh
     * tier and the DSL's one-gesture `blend` state a kind and a size, not a list of them, and a dressing
     * whose entries all share a size node hands over the same section repeated. Per-target sections are what
     * a *dressing* of several sizes needs (OP-30's next step), and it is the overload above.
     */
    fun blended(
        base: Solid3,
        applyTo: Solid3,
        targets: List<Int>,
        sec: BlendSection,
        choices: List<BlendChoice>,
        root: Solid3? = null,
    ): Pair<Solid3?, Msg?> = blended(base, applyTo, targets, List(targets.size) { sec }, choices, emptyMap(), root)

    /**
     * **The band along a circular rim, built as what it is: a surface of revolution** (session 82).
     *
     * A crease that is one circular arc turns about a fixed axis, and the section stands in the plane through
     * that axis at every station — so the tool the blend sweeps *is* a solid of revolution, and [Geom3.revolve]
     * builds exactly that. Why it matters, and why the general sweep cannot: [Frames3] carries a run as
     * **chords**, and a section point that lands on the axis is then placed within a chord's own error of it
     * rather than on it. Where the ball is as wide as the rim it runs along — `r = R`, the ball **stands
     * still** and the band is the ball's own sphere patch — that point *is* the axis for the whole run, and
     * the ring the sweep traces round it instead of a pole is a few tenths of a millimetre across at the run's
     * ends and a few microns across in the middle. The fan over that ring folds back on itself, which is the
     * ten micron-scale flaps GitHub #29's own drawing carried. A revolve has no such error: a profile point on
     * the axis is on the axis at every station, the quads that collapse with it are dropped
     * ([Geom3.MeshBuilder.triangle], which exists for this very case), and what comes out is one pole.
     *
     * Null where the crease is not one circular arc, or where its axis is not the section's own upright — then
     * the band is not a body of revolution and the sweep is the only construction there is.
     */
    private fun revolvedBand(piece: Piece): Pair<Solid3?, Msg?>? {
        val arc = piece.crease.path.elements.singleOrNull() as? Curve3Element.Arc3 ?: return null
        if (arc.radius <= Geom3.WELD_TOL) return null
        val up = piece.crease.e1.normalized()
        val axis = arc.normal.normalized()
        val along = up.dot(axis)
        if (abs(abs(along) - 1.0) > AXIS_TOL) return null
        val at0 = arc.at(0.0)
        val outward = (at0 - arc.center).normalized()
        if (outward.length() <= Vec3.EPS) return null
        // the section's own frame at the run's start: x along the crease's upright, y along the radius out of
        // the axis — the very placement [Frame3.place] makes there, with the *exact* radial instead of a chord's
        val plane = Plane3(at0, up, outward)
        // …and the axis in that plane's coordinates: the line through the centre, along the upright
        val axisOrigin = Vec2(0.0, -arc.radius)
        val axisDir = Vec2(1.0, 0.0)
        // which way the turn runs: the plane's own normal is `up × outward`, the tangent at the start for a
        // right-handed arc, so a positive sweep about that normal is the arc's own direction and the sign of
        // the arc's sweep against the upright is the whole of the correspondence
        val sweep = if (along >= 0.0) arc.sweepAngle else -arc.sweepAngle
        // …and the one thing a revolve cannot do, said in the words the sweep says it in: a section that
        // reaches **past** the axis is a ball wider than the rim it runs along, and revolving it would fold
        // the shell through itself. Reaching *to* the axis is the degenerate case above and is legal — a
        // profile touching the axis is what a turned part's pole is made of ([Geom3.revolve]).
        val into = -piece.grown.minOf { it.y }
        if (into > arc.radius + Geom3.WELD_TOL) {
            return null to
                Msgs.refusalBlendProfileReachBendMmIs(mm = Frames3.mm(into), mm2 = Frames3.mm(arc.radius), mm3 = Frames3.mm(0.0))
        }
        return Geom3.revolve(Sketch3(plane, listOf(piece.grownRegion)), axisOrigin, axisDir, sweep)
    }

    /** How nearly a crease's own upright must lie along the axis it turns about for the band to be a revolve. */
    private const val AXIS_TOL = 1e-9

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
    ): Msg? {
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
                        return Msgs.refusalBlendCornerWhereMeetsCarriesTwo(name = a.crease.edge.name.label, name2 = b.crease.edge.name.label, name3 = shared.name.label)
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
    private fun largestCornerFitting(pieces: List<Piece>): Double {
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
    ): Pair<Int?, Msg?> {
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

    /**
     * One entry per target, in the feature's own order — **null where the rounding was removed** (OP-30's
     * tombstone), so the two derived lists can emit that position's slots and nothing else.
     */
    private fun dressingsOf(f: Feature3.Blend): Pair<List<Dressing?>?, Msg?> {
        val (edges, whyEdges) = Section3.edges(f.base)
        if (edges == null) return null to whyEdges
        val out = ArrayList<Dressing?>(f.targets.size)
        for ((k, i) in f.targets.withIndex()) {
            if (f.isAbsent(k)) {
                out.add(null)
                continue
            }
            val sec = f.sections.getOrNull(k) ?: return null to Msgs.refusalBlendThisBlendRecordedNoSection(i = i + 1)
            val edge = edges.getOrNull(i) ?: return null to Msgs.refusalBlendThisSolidHasNoEdge3(i = i + 1, count = edges.size)
            val (crease, why) = creaseOf(f.base, edge)
            if (crease == null) return null to why
            val choice = f.choices.getOrNull(k) ?: return null to Msgs.refusalBlendThisBlendRecordedNoChoice(i = i + 1)
            val (wedge, whyWedge) = wedgeOf(crease, sec, choice)
            if (wedge == null) return null to whyWedge
            out.add(Dressing(i, edge, crease, wedge, choice, sec))
        }
        return out to null
    }

    /**
     * What a **tombstoned** target says about itself — the sentence its band faces and its two rails carry
     * (OP-30): the rounding is gone, the slot is not, and the gesture that does work is named.
     */
    private fun tombstoneWords(
        f: Feature3.Blend,
        k: Int,
        edge: SolidEdge?,
    ): Msg {
        val what = edge?.name?.label ?: Msgs.nameSolidEdgeIndex(edge = f.targets[k] + 1)
        return Msgs.refusalBlendWasRemovedNothingStandsHere(
            getOrNull = f.sections.getOrNull(k)?.kind?.word ?: Msgs.wordBlendRounding(),
            what = what,
        )
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
    fun dressedFaces(f: Feature3.Blend): Pair<List<FacePatch>?, Msg?> = f.dressedFaces

    /**
     * A solid's faces **with every trim of the chain composed and no notch cut yet** — the base a dressing
     * builds its own answer on ([deriveDressedFaces] says why the two halves are separate lists).
     *
     * For anything that is not a dressing this *is* the face list; there is nothing to hold back.
     */
    private fun trimmedFacesOf(feature: Feature3): Pair<List<FacePatch>?, Msg?> =
        if (feature is Feature3.Blend) feature.trimmedFaces.let { it.faces to it.why } else Section3.faces(feature)

    /**
     * The dressed list itself: the **trimmed** one with each free end's notch cut out of the face its cap
     * stands in (session 81, the queued section limit (a)).
     *
     * The two halves of the correction are separate because they compose differently. A **trim** is a strip
     * of constant width off one boundary piece, so a strip of `d₁` and then one of `d₂` off the same piece
     * is one strip of `d₁ + d₂` and each level may take its own off the level below's answer. A **notch**
     * replaces a *corner* of the ring with the wedge's own section, and a later level offsetting the piece
     * beside it would have to re-solve a junction against an arc that is **tangent** to that piece — two
     * solutions exactly as far from the corner they replace, which nearness alone cannot choose between. So
     * the trims are composed down the chain on their own and the notches are applied **once, at the tip**,
     * over the whole chain's free ends ([Notch]).
     *
     * That is also the only reading that is *right*: an end that is free at one level and closed by a
     * corner at the next must lose its notch, and at the tip it simply never gets one.
     */
    internal fun deriveDressedFaces(f: Feature3.Blend): Pair<List<FacePatch>?, Msg?> {
        val trimmed = f.trimmedFaces
        val faces = trimmed.faces ?: return null to trimmed.why
        if (trimmed.notches.isEmpty()) return faces to null
        val out = ArrayList<FacePatch>(faces.size)
        for ((i, patch) in faces.withIndex()) {
            val mine = trimmed.notches.filter { it.face == i }
            if (mine.isEmpty() || patch.reason != null) {
                out.add(patch)
                continue
            }
            val (outline, why) = notchedOutline(patch, mine)
            // **and the face says the boundary is fitted, where one of its notches was** (OP-31, Tier B):
            // the widest tolerance any piece spliced into it was fitted to, and nothing at all where every
            // piece is exact. *"Since roundings are essential for all kinds of objects, an approximation is
            // better than nothing at all"* — which is a decision about what may be **built**, not a licence
            // to keep quiet about it, so the number is carried at the value and said wherever it is read
            // ([Section3.words]).
            val fitted = mine.mapNotNull { it.fitted }.maxOrNull()
            out.add(if (outline == null) patch.copy(reason = why) else patch.copy(outline = outline, fitted = fitted ?: patch.fitted))
        }
        return out to null
    }

    /**
     * A **planar band's own outline, bounded by the corners at its ends** (OP-31, item 3b) — the face list's
     * half of what item 3 did for the edge list.
     *
     * Session 79's cut (5) read *"the band's own face outline is still the full sweep"*, and session 81
     * retired it for a section's **rulings** ([bandStrip], [parallelBandCut], both of which ask [spanOf])
     * and not for the **patch**. So a bevel's band was a rectangle over the whole of its crease however much
     * of it a corner had taken away: on the reporter's own three-bevel corner the upright's band was drawn
     * over its whole 20 mm where the walk ends it at 16, and a level section above that height met a piece
     * the body does not have and could not close its loop.
     *
     * The correction is the rail's own, read one dimension over: a planar band is a straight section carried
     * along a straight crease, so its outline is the quadrilateral between the two **stations** [spanOf]
     * gives at each end of that section. Every corner in the catalogue ends a band on an *affine* placement
     * — a crossing's mitre ring, a walk's end ring, a ball's — so the two end edges are straight and the
     * answer is **exact**; where a band instead simply *runs into* one no corner joins it to (session 79's
     * cut (2), [endsRunInto]) the station moves along the neighbour's own section and the edge is a curve
     * this drawing has no word for, so it is stated as a **fitted** chain and the patch says so
     * ([FacePatch.fitted], OP-31's Tier B).
     *
     * Asked at the **tip**, like every other reading of a band's extent ([bandOf]): a corner can be made by a
     * later gesture than the one that made the band.
     */
    private fun bandToItsCorners(
        f: Feature3.Blend,
        patch: FacePatch,
    ): FacePatch {
        val name = patch.name as? FaceName.BlendBand ?: return patch
        // a **curved** band states no outline at all — it is a cylinder or a torus and its cut is analytic
        // ([bandCut], [parallelBandCut]) — so there is nothing here to bound
        val plane = patch.plane ?: return patch
        if (patch.reason != null) return patch
        val (pieces, at) = bandOf(f, name.edge) ?: return patch
        val piece = pieces[at]
        val el = piece.seg ?: return patch
        val section = orientedSections(piece).getOrNull(name.piece) as? ProfileElement.Seg ?: return patch
        val v = el.end - el.start
        val len = v.length()
        if (len <= Geom3.WELD_TOL) return patch
        val u = v * (1.0 / len)
        val span = spanOf(pieces, at, cornersOf(pieces).list)

        fun corner(
            t: Double,
            far: Boolean,
        ): Vec2? {
            val q = sectionPointAt(section, t) ?: return null
            val (s0, s1) = span(q)
            if (s1 - s0 <= Geom3.WELD_TOL) return null
            return plane.toLocal(worldOnStraight(piece.crease, el.start, u, q, if (far) s1 else s0))
        }
        // **a band no corner touches keeps the rectangle it had**, bit for bit: the outline is already right
        // and re-deriving it would move it by a float's worth for nothing (OP-15's own discipline).
        val whole =
            listOf(0.0, 0.5, 1.0).all { t ->
                val q = sectionPointAt(section, t)
                q != null && span(q).let { abs(it.first) <= Geom3.WELD_TOL && abs(it.second - len) <= Geom3.WELD_TOL }
            }
        if (whole) return patch
        val a0 = corner(0.0, false) ?: return patch.copy(reason = Msgs.refusalBlendRailTakenByCorner(name = patch.name.label, name2 = patch.name.label))
        val a1 = corner(1.0, false) ?: return patch.copy(reason = Msgs.refusalBlendRailTakenByCorner(name = patch.name.label, name2 = patch.name.label))
        val b1 = corner(1.0, true) ?: return patch
        val b0 = corner(0.0, true) ?: return patch
        // **exact where the end is a placement, fitted where it is a run into a neighbour.** The midpoint
        // against the chord of the two ends is the whole test: an affine end is on it to the last bits.
        val m0 = corner(0.5, false)
        val m1 = corner(0.5, true)
        val straight =
            m0 != null && m1 != null &&
                (m0 - (a0 + a1) * 0.5).length() <= SAME_CURVE_TOL && (m1 - (b0 + b1) * 0.5).length() <= SAME_CURVE_TOL
        if (straight) {
            val ring =
                listOf(
                    ProfileElement.Seg(Segment(a0, a1)),
                    ProfileElement.Seg(Segment(a1, b1)),
                    ProfileElement.Seg(Segment(b1, b0)),
                    ProfileElement.Seg(Segment(b0, a0)),
                )
            return patch.copy(outline = ring)
        }
        val near = fittedChain(Combine3.FIT_TOL_MM) { t -> corner(t, false) } ?: return patch
        val far = fittedChain(Combine3.FIT_TOL_MM) { t -> corner(1.0 - t, true) } ?: return patch
        val ring = near + listOf(ProfileElement.Seg(Segment(a1, b1))) + far + listOf(ProfileElement.Seg(Segment(b0, a0)))
        return patch.copy(outline = ring, fitted = Combine3.FIT_TOL_MM)
    }

    /**
     * The trimmed list and the notches the tip owes, derived at most **once per feature instance** behind
     * the memo on [Feature3.Blend.trimmedFaces] — GitHub #35's first cause (OP-5). The seam stays where it
     * was: [Section3.faces] still asks [dressedFaces], and what changed is only how often the answer has to
     * be worked out.
     */
    internal class Trimmed(val faces: List<FacePatch>?, val why: Msg?, val notches: List<Notch>)

    /** See [Trimmed]. */
    internal fun deriveTrimmedFaces(f: Feature3.Blend): Trimmed {
        val (faces, why) = deriveTrimmedList(f)
        val pristine = if (faces == null) null else undressedFacesOf(f)
        return Trimmed(faces, why, if (pristine == null || faces == null) emptyList() else notchesOf(f, pristine, faces))
    }

    /**
     * The faces of the **undressed** body under a chain of roundings — where a free end still stands on a
     * corner of the face its cap fills, and where the piece indices of every level's list come from.
     *
     * A dressed list is the base's *restated* — [GeomMath.offsetCycle] gives one piece back per piece it
     * takes and [deriveTrimmedList] one face back per face — so face `i` and its boundary piece `k` mean the
     * same thing at every depth of the chain, and a notch located down here is spliced in up there.
     */
    private fun undressedFacesOf(f: Feature3): List<FacePatch>? {
        var under = f
        while (under is Feature3.Blend) under = under.base
        return Section3.faces(under).first
    }

    private fun deriveTrimmedList(f: Feature3.Blend): Pair<List<FacePatch>?, Msg?> {
        derivations++
        val (baseFaces, whyFaces) = trimmedFacesOf(f.base)
        if (baseFaces == null) return null to whyFaces
        val (dressings, whyDress) = dressingsOf(f)
        if (dressings == null) return null to whyDress
        val trims = HashMap<FaceName, MutableList<Pair<SolidEdge, Double>>>()
        for (d in dressings.filterNotNull()) {
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
        val baseEdges = Section3.edges(f.base).first
        for ((k, d) in dressings.withIndex()) {
            // **a removed rounding keeps its band slots** — a reason and no surface, exactly what a consumed
            // edge already emits, so the bands of every entry after it keep their own numbers (OP-30)
            if (d == null) {
                val why = tombstoneWords(f, k, baseEdges?.getOrNull(f.targets[k]))
                for (piece in 0 until f.bandsAt(k)) out.add(FacePatch(FaceName.BlendBand(f.targets[k], piece), null, emptyList(), why))
                continue
            }
            // …each of them **bounded by the corners at its ends** (OP-31, item 3b) before the level above
            // trims its own strips off it, so the two compose: this level says how far the band runs, the
            // next says what a rounding of its rail took off it.
            out.addAll(bandPatchesOf(d).map { bandToItsCorners(f, it) })
        }
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
            // a **tombstone** builds no piece, so it is in no corner: what it keeps is a slot, not a band
            if (f.isAbsent(k)) continue
            val sec = f.sections.getOrNull(k) ?: return null
            val edge = edges.getOrNull(i) ?: return null
            val crease = creaseOf(f.base, edge).first ?: return null
            val choice = f.choices.getOrNull(k) ?: return null
            val wedge = wedgeOf(crease, sec, choice).first ?: return null
            out.add(pieceOf(i, false, crease, wedge, choice, sec).first ?: return null)
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
        if (c is Walk) {
            val (leg, sr) = c.facePlan(pieces).getOrNull(name.piece) ?: return null
            if (sr == null || leg.pivot == null) return null
            val (frame, map) = c.axisFrame(pieces[c.travelling], leg) ?: return null
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
    ): Msg? = supersedings(f)[name.edges]

    private fun supersedings(f: Feature3.Blend): Map<List<Int>, Msg> {
        val pieces = piecesOf(f) ?: return emptyMap()
        val out = HashMap<List<Int>, Msg>()
        for (c in cornersOf(pieces).list) {
            if (c.extra.isEmpty()) continue
            val ends = c.ends.map { pieces[it.first].index }.distinct().sorted()
            val whole = cornerEdges(pieces, c)
            if (whole == ends) continue
            val upright = pieces[c.extra.first()]
            out[ends] =
                Msgs.refusalBlendWasReTurnedAboutWhen(name = FaceName.BlendCorner(ends, 0).label, name2 = upright.crease.edge.name.label, name3 = FaceName.BlendCorner(whole, 0).label)
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
        if (c !is Walk) return null
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
        // …counted over the roundings that **stand**, since [piecesOf] lists this level's own pieces first
        // and a tombstone contributes none (OP-30)
        val fresh = f.standing.size
        val out = ArrayList<FacePatch>()
        for (c in cornersOf(pieces).list) {
            if (c.ends.none { it.first < fresh } && c.extra.none { it < fresh }) continue
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
    fun dressedEdges(f: Feature3.Blend): Pair<List<SolidEdge>?, Msg?> = f.dressedEdges

    /** The derivation itself, run once per feature instance — see [deriveDressedFaces]. */
    internal fun deriveDressedEdges(f: Feature3.Blend): Pair<List<SolidEdge>?, Msg?> {
        derivations++
        val (baseEdges, whyEdges) = Section3.edges(f.base)
        if (baseEdges == null) return null to whyEdges
        val (dressings, whyDress) = dressingsOf(f)
        if (dressings == null) return null to whyDress
        // …the roundings that **stand**: a tombstone rounded nothing away, so its base edge is a crease of
        // this body again (OP-30)
        val consumed = dressings.filterNotNull().associateBy { it.index }
        // **the chain as it stands at this level** — the pieces every band is among and the corners they
        // make, which is what says how far a band's crease actually runs ([spanOf]) and which corner curves
        // this body has (OP-31 item 3). Read once for the whole list rather than per rail.
        val pieces = piecesOf(f)
        val corners = pieces?.let { cornersOf(it).list } ?: emptyList()
        val superseded = supersedings(f)
        val out = ArrayList<SolidEdge>(baseEdges.size + 2 * dressings.size)
        for ((i, e) in baseEdges.withIndex()) {
            val d = consumed[i]
            // a corner curve the **base** stated whose corner this level re-turned about a fresh upright is
            // no curve of this body any more — it keeps its index and says so, exactly as the corner *face*
            // it bounds does ([cornerSuperseded], OP-17)
            val gone = (e.name as? EdgeName.BlendMitre)?.edges ?: (e.name as? EdgeName.BlendCornerRail)?.edges
            out.add(
                if (d == null) {
                    if (gone != null && e.reason == null) superseded[gone]?.let { e.copy(reason = it) } ?: e else e
                } else {
                    e.copy(
                        reason =
                            Msgs.refusalBlendWasRoundedAwayStandsIts(name = e.name.label, word = d.sec.kind.word, sizePhrase = d.sec.sizePhrase(), name2 = d.name.label),
                    )
                },
            )
        }
        for ((k, d) in dressings.withIndex()) {
            // **a removed rounding keeps its two rail slots too**, with the same reason and a degenerate
            // carrier: what a chained rounding addressed is still numbered where it was, and asking for it
            // is a refusal that names the rounding that is gone rather than a different edge (OP-17, OP-3)
            if (d == null) {
                val target = f.targets[k]
                val e = baseEdges.getOrNull(target)
                val why = tombstoneWords(f, k, e)
                val at = (e?.geom as? EdgeGeom.Straight)?.a ?: Vec3(0.0, 0.0, 0.0)
                val band = FaceName.BlendBand(target, 0)
                for (side in 0..1) {
                    val face = if (side == 0) e?.between?.a else e?.between?.b
                    out.add(SolidEdge(EdgeName.BlendRail(target, side), EdgeGeom.Straight(at, at), FacePair(face ?: band, band), why))
                }
                continue
            }
            val at = pieces?.indexOfFirst { it.index == d.index } ?: -1
            val span = if (pieces != null && at >= 0) spanOf(pieces, at, corners) else null
            for (side in 0..1) {
                val face = if (side == 0) d.crease.face1 else d.crease.face2
                val t = if (side == 0) d.wedge.t1 else d.wedge.t2
                val (geom, why) = railGeom(d, t, span)
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
        if (pieces != null) out.addAll(cornerEdgesOf(f, pieces, corners))
        return out to null
    }

    // ---- the corner curves: a dressed body's edge list states what the body has (OP-31, item 3) ----

    /**
     * **The curves a corner puts on the body**, appended after every band's rails and in the corners' own
     * order — the third and last thing a dressed edge list has to say (OP-31, item 3).
     *
     * *Why appended, and not woven in beside the band they belong to.* Every index in this list is an
     * address a `signs=` in some file already holds (OP-17, OP-18). The base's own edges keep their indices,
     * the rails keep theirs, and the corner curves go after all of them — so a file written before this
     * session loads exactly the body it loaded before, and the only thing that ever moves is a corner
     * *patch*'s address, which is the same exposure OP-30's own note already records.
     *
     * Two curves, and they are the two directions of one tube:
     *
     * - a **mitre** ([EdgeName.BlendMitre]) runs *across* the corner — the ring the two tubes are split on
     *   at a crossing (a straight segment between two bevels, an ellipse arc between two rounds, exactly as
     *   session 79 predicted and never built), the ring where a band hands over to a walk's surface, and
     *   the ring between two legs of a walk;
     * - a **corner rail** ([EdgeName.BlendCornerRail]) runs *along* it, in the face the walk runs in — the
     *   band's own tangent rail carried on round the corner. It is what makes *rail → corner curve → rail*
     *   one chain, which is the reporter's own ask ([chainRun]).
     *
     * **A round's corner curves are stated and marked as no crease**, in the same breath and for the same
     * reason [SolidEdge.reason] exists at all: a ball rolling off a straight run onto a pivot is tangent to
     * its own band along the whole hand-over ring — both surfaces envelop the *same* ball there — so there
     * is nothing to round, and saying so by name beats leaving the curve out of a list that claims to state
     * what the body has. A **bevel**'s are ordinary creases: its band is a plane and the walk's leg is a
     * cone, and they meet at an angle. That is [smoothRail]'s own rule, read for a corner.
     */
    private fun cornerEdgesOf(
        f: Feature3.Blend,
        pieces: List<Piece>,
        corners: List<Corner>,
    ): List<SolidEdge> {
        // …only the corners this level makes: the ones under it are already in the base's own list, at the
        // indices they were appended at (the same freshness test [cornerFacesOf] uses, and deliberately so)
        val fresh = f.standing.size
        val out = ArrayList<SolidEdge>()
        for (c in corners) {
            if (c.ends.none { it.first < fresh } && c.extra.none { it < fresh }) continue
            val edges = cornerEdges(pieces, c)
            val faceAt = { k: Int -> FaceName.BlendCorner(edges, k) }
            when (c) {
                is Joint -> out.addAll(jointEdges(pieces, c, edges))
                is Walk -> out.addAll(walkEdges(pieces, c, edges, faceAt))
                // **the ball is this entry's one whole cut.** A *round* vertex states no crease at all —
                // the ball and each of the three bands envelop the same sphere and are tangent along the
                // band's own end circle — so there is nothing there for a list of creases to be missing. A
                // **bevelled** one does have three: its three bevel planes meet pairwise in lines running
                // to the apex, and those lines are the apex construction's rather than any ring's. They are
                // not listed, and a rounding of them is therefore not offered (see the note under OP-31).
                is Vertex -> Unit
            }
        }
        return out
    }

    /** The one curve a **crossing** puts on the body: the mitre, one piece per piece of the section. */
    private fun jointEdges(
        pieces: List<Piece>,
        c: Joint,
        edges: List<Int>,
    ): List<SolidEdge> {
        val a = pieces[c.a]
        val b = pieces[c.b]
        return orientedSections(a).indices.map { j ->
            val (geom, why) = ringCurve(a, c.placeA, j)
            SolidEdge(
                EdgeName.BlendMitre(edges, j),
                geom ?: EdgeGeom.Straight(c.placeA.origin, c.placeA.origin),
                FacePair(FaceName.BlendBand(a.index, j), FaceName.BlendBand(b.index, j)),
                why,
            )
        }
    }

    /**
     * The curves a **walk** puts on the body: its rails, one per leg — and *only* its rails.
     *
     * *What a walk's rings are, measured rather than assumed.* A walk is **one continuous motion of one
     * section**: it starts on the band's own end ring and carries it round, so the ring where a band hands
     * over to the walk, and the ring between two legs of it, are hand-overs and not creases. The mesh says
     * so with no room for argument — on the reporter's own three bevels the dihedral across every one of
     * those rings is **0.00°**, while the dihedral across a rail is 45° — and the count settles it a second
     * time: a leg is chorded, so its rings are a *tessellation* fact and an edge list may not be one.
     *
     * So the walk contributes the **longitudinal** curve and nothing else: the tangency the travelling
     * section keeps on the face the walk runs in, leg by leg — a circle about that leg's own pivot where it
     * turns, a straight run where it slides. That is the band's own rail carried on round the corner, and
     * it is what makes *rail → corner curve → rail* one chain ([chainRun]).
     */
    private fun walkEdges(
        pieces: List<Piece>,
        c: Walk,
        edges: List<Int>,
        faceAt: (Int) -> FaceName,
    ): List<SolidEdge> {
        val travelling = pieces[c.travelling]
        val m = orientedSections(travelling).size
        val shared = (c as? Turn)?.shared ?: (c as? Pivot)?.shared ?: return emptyList()
        val onFace1 = shared.name == travelling.crease.face1.name
        val q = if (onFace1) travelling.wedge.t1 else travelling.wedge.t2
        val onPiece = if (onFace1) 0 else m - 1
        return c.walkLegs.mapIndexed { k, leg ->
            val name = EdgeName.BlendCornerRail(edges, k)
            val (geom, why) = legRailCurve(leg, q, c.walkNormal)
            SolidEdge(
                name,
                geom ?: EdgeGeom.Straight(leg.rings.first().at(q), leg.rings.first().at(q)),
                FacePair(faceAt(k * m + onPiece), shared.name),
                why ?: handOverReason(travelling, name),
            )
        }
    }

    /**
     * Why a corner curve of [piece] is **no crease of the body** — or null where it is one.
     *
     * The structural rule, never a measurement (OP-21): a [BlendKind.FILLET]'s surface and the corner it
     * hands over to are both tangent to the very same ball along their shared ring, so the hand-over is
     * smooth by construction; a bevel's plane and a walk's cone are not, and a drawn profile's is read the
     * same way its rails are — sharp, because whether a drawn section happens to leave tangentially is a
     * property of the values it was drawn with.
     */
    private fun handOverReason(
        piece: Piece,
        name: EdgeName,
    ): Msg? =
        if (piece.sec.kind != BlendKind.FILLET) {
            null
        } else {
            Msgs.refusalBlendCornerHandsOverSmoothly(name = name.label, name2 = Msgs.nameSolidRoundedBandAlongEdge(edge = piece.index + 1))
        }

    /**
     * One ring of a corner as a **curve in the world**: piece [j] of [piece]'s own section carried through
     * the ring's placement.
     *
     * A placement is affine in the section's `(x, y)` ([Placement]), so a straight leg stays straight and an
     * arc stays a conic — and where the placement **stretches**, which is exactly what a mitre one does, a
     * circle's arc becomes an **ellipse** arc and this states it as one (OP-24's conic vocabulary). That is
     * session 79's own prediction, *"the ellipse arc in the mitre plane, the wedge's own blend curve
     * stretched by 1/sin(θ/2)"*, made a value.
     */
    private fun ringCurve(
        piece: Piece,
        place: Placement,
        j: Int,
    ): Pair<EdgeGeom?, Msg?> {
        val sec =
            orientedSections(piece).getOrNull(j)
                ?: return null to Msgs.refusalBlendSweepsPieceProfileThisDrawing(name = piece.crease.edge.name.label)
        val n = place.cx.cross(place.cy)
        if (n.length() <= Vec2.EPS || place.cx.length() <= Vec2.EPS) {
            return EdgeGeom.Straight(place.origin, place.origin) to null
        }
        val u = place.cx.normalized()
        val w = n.normalized().cross(u)
        val map = Affine(place.cx.dot(u), place.cx.dot(w), place.cy.dot(u), place.cy.dot(w), 0.0, 0.0)
        val mapped =
            mappedExactly(sec, map)
                ?: return null to Msgs.refusalBlendCornerCurveNotStated(name = piece.crease.edge.name.label)
        return EdgeGeom.OnPlane(Plane3(place.origin, u, w), mapped) to null
    }

    /**
     * The **tangency of one leg**, as the curve it is: a circle about the leg's own pivot where the leg
     * turns, a straight run where it slides.
     *
     * Exact in both shapes and for the reason [Pivot.sharedChain] already states about the shared face — the
     * whole leg is one rigid motion of the section, so any single point of that section travels a circle
     * about the walk's axis or a straight line along it, and nothing between.
     */
    private fun legRailCurve(
        leg: Leg,
        q: Vec2,
        axis: Vec3?,
    ): Pair<EdgeGeom?, Msg?> {
        val p0 = leg.rings.first().at(q)
        val p1 = leg.rings.last().at(q)
        val pivot = leg.pivot
        if (pivot == null || abs(leg.turn) <= TANGENT_TOL || axis == null) {
            return EdgeGeom.Straight(p0, p1) to null
        }
        val n = axis.normalized()
        val centre = pivot + n * ((p0 - pivot).dot(n))
        val rel = p0 - centre
        val r = rel.length()
        if (r <= Geom3.WELD_TOL) return EdgeGeom.Straight(p0, p1) to null
        val u = rel.normalized()
        val v = n.cross(u)
        val ang = atan2((p1 - centre).dot(v), (p1 - centre).dot(u))
        val ccw = leg.turn >= 0.0
        var end = ang
        if (ccw) {
            while (end <= 0.0) end += 2.0 * PI
        } else {
            while (end >= 0.0) end -= 2.0 * PI
        }
        return EdgeGeom.OnPlane(Plane3(centre, u, v), ProfileElement.ArcE(Arc(Vec2(0.0, 0.0), r, 0.0, end, ccw))) to null
    }

    /**
     * [e] carried through [map] **exactly**, whatever the map does to it — or null where this drawing has
     * no exact word for the answer.
     *
     * [GeomMath.transform] is written for a *similarity* (it carries a radius through by `t.scale`), which
     * is every map the blend had before corners had curves. A mitre placement is not one: it stretches the
     * section along the bisector by `1/sin(θ/2)`, and an arc under it is an **ellipse** arc. So the
     * similarity is tested rather than assumed, and the general case goes through [Conics], where the
     * answer is exact rather than fitted.
     */
    private fun mappedExactly(
        e: ProfileElement,
        map: Affine,
    ): ProfileElement? {
        val ux = map.linear(Vec2(1.0, 0.0))
        val uy = map.linear(Vec2(0.0, 1.0))
        val similar = abs(ux.length() - uy.length()) <= 1e-9 * max(1.0, ux.length()) && abs(ux.dot(uy)) <= 1e-9 * max(1.0, ux.length() * uy.length())
        if (similar) return GeomMath.transform(e, map)
        return when (e) {
            is ProfileElement.Seg, is ProfileElement.BezierE, is ProfileElement.EllipticArcE, is ProfileElement.EllipseE ->
                GeomMath.transform(e, map)
            is ProfileElement.ArcE -> {
                val a = e.arc
                val arc = EllipticArc(Ellipse(a.center, a.radius, a.radius, 0.0), a.startAngle, a.endAngle, a.ccw)
                ProfileElement.EllipticArcE(Conics.transform(arc, map))
            }
            is ProfileElement.CircleE -> {
                val c = e.circle
                ProfileElement.EllipseE(Conics.transform(Ellipse(c.center, c.radius, c.radius, 0.0), map), if (map.det < 0) !e.ccw else e.ccw)
            }
            is ProfileElement.FuncE -> null
        }
    }

    /**
     * **When two edges of a dressed body are one chain** (OP-31, item 3) — the answer
     * [Section3.edges]-side, and the third source [targets] takes a run from.
     *
     * *"I would expect that the fillets are 'smoothly' joined together"* was answered in 2D by the drawing's
     * own joint registry (`Document.tangentRun`), which knows nothing about the curves a **corner** adds.
     * This is the same question asked of the construction instead of of the sketch: a band's tangent rail
     * and the corner curve that carries it on lie in the same face and meet end to end, and they are one
     * chain **because the corner was built to continue the band** — the walk starts on the band's own end
     * ring and carries its section on from there. Nothing is measured (OP-21): the rule is that one of the
     * two is a corner rail, that they share a face by name, and that they meet.
     *
     * Two rails of two *bands* meeting at a crossing are deliberately **not** one chain: they meet at the
     * plan's own corner angle, which is a crease and not a hand-over — the mitre between them is listed as
     * its own edge and is the thing to round there.
     */
    fun chainRun(): (SolidEdge, SolidEdge) -> Boolean =
        { a, b ->
            val corner = a.name is EdgeName.BlendCornerRail || b.name is EdgeName.BlendCornerRail
            val shares = listOf(a.between.a, a.between.b).any { b.between.has(it) }
            corner && shares && sharedEnd(a, b) != null
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
                    FacePatch(name, null, emptyList(), Msgs.refusalBlendIsChainSeveralPiecesSo(name = d.edge.name.label))
                // a Bézier, a conic or a function curve in the profile: the band it sweeps is a real
                // surface and its triangles are exact to the tessellation, but this drawing has no word
                // for it — so the face keeps its index and carries the reason (OP-15's approximated class)
                piece == null ->
                    FacePatch(
                        name,
                        null,
                        emptyList(),
                        Msgs.refusalBlendSweepsPieceProfileThisDrawing(name = name.label),
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
                    FacePatch(name, null, emptyList(), Msgs.refusalBlendHasNoLengthSoIts(name = d.edge.name.label))
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
                        Msgs.refusalBlendDoesNotStandSquareIts(name = d.edge.name.label),
                    )
                Revolve3.bandPatch(frame, sr, name)
            }
            else ->
                FacePatch(
                    name,
                    null,
                    emptyList(),
                    Msgs.refusalBlendIsNeitherStraightNorCircular(name = d.edge.name.label),
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
        val what = patch.surface?.band?.label ?: Msgs.refusalBlendSurfaceThisDrawingHasNo()
        return patch.copy(
            reason =
                Msgs.refusalBlendIsNotPlaneItIs2(name = d.name.label, what = what, kind = d.kindWord),
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
        span: ((Vec2) -> Pair<Double, Double>)? = null,
    ): Pair<EdgeGeom?, Msg?> {
        val el = soleElement(d.crease) ?: return null to Msgs.refusalBlendIsChainSeveralPiecesSo2(name = d.edge.name.label)
        return when (el) {
            is Curve3Element.Seg3 -> {
                val v = el.end - el.start
                val len = v.length()
                if (len <= Geom3.WELD_TOL) {
                    null to Msgs.refusalBlendHasNoLength(name = d.edge.name.label)
                } else {
                    val u = v * (1.0 / len)
                    // **the crease's own run, not the edge's** (OP-31, item 3): where a corner takes the
                    // band over — a crossing, a pivot, a ball, a pivot with one end — the crease stops
                    // there and the corner's own curve carries on. Stating the rail at the whole length of
                    // the edge put a rounding of it 4 mm past the crease and, on a rail that lies in a side
                    // face, buried it inside the fill; [spanOf] is the very reading the band's own *face*
                    // outline has taken since session 81, asked here for the rail as well.
                    val (s0, s1) = span?.invoke(t) ?: (0.0 to len)
                    if (s1 - s0 <= Geom3.WELD_TOL) {
                        EdgeGeom.Straight(worldOnStraight(d.crease, el.start, u, t, s0), worldOnStraight(d.crease, el.start, u, t, s0)) to
                            Msgs.refusalBlendRailTakenByCorner(name = EdgeName.BlendRail(d.index, 0).label, name2 = d.name.label)
                    } else {
                        EdgeGeom.Straight(worldOnStraight(d.crease, el.start, u, t, s0), worldOnStraight(d.crease, el.start, u, t, s1)) to null
                    }
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
            else -> null to Msgs.refusalBlendIsNeitherStraightNorCircular2(name = d.edge.name.label)
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
        // **a piece that is not one straight run stands over the whole of it**, and that is provable rather
        // than assumed: every corner in this catalogue is between **two straight edges** ([cornersOf]'s own
        // first precondition — the surface equidistant from the two is a plane only then), an upright a walk
        // turns about must be one too ([Walk.uprightEnd]), and a band can only run into another straight one
        // ([endsRunInto]). So nothing can set such a band back, and asking would be asking for a station
        // along a run that has none. This is the case a rounding along a **chain** makes ordinary: the legs
        // of a pivot's own rail are arcs (OP-31, item 3).
        if (piece.seg == null) return { _ -> 0.0 to len }
        val ends = HashMap<Boolean, Placement>()
        for (c in corners) {
            for (e in c.ends) if (e.first == at) ends[e.second] = c.ringAt(e)
            // …and the **upright** a corner turns about is ended by it too, though it is no end of the tool:
            // above that plane the corner's own surface stands where the upright's band used to (session 81)
            if (c is Walk) c.uprightEnd(pieces)?.let { (e, p) -> if (e.first == at) ends[e.second] = p }
        }
        val lo = ends[true]
        val hi = ends[false]
        // …and where a band simply **runs into** one no corner joins it to — two edges of a face rounded to
        // sizes that are not congruent, so the pair lands on no common ring (session 79's cut (2)) — the
        // boolean trims the two against each other, and the drawing has to say where (session 81)
        val met = endsRunInto(pieces, at, corners)
        return { p ->
            var from = lo?.let { stationOf(piece, it.at(p)) } ?: 0.0
            var to = hi?.let { stationOf(piece, it.at(p)) } ?: len
            for ((atStart, other) in met) {
                val s = runsInto(piece, pieces[other], p, atStart) ?: continue
                if (atStart) from = max(from, s) else to = min(to, s)
            }
            from to to
        }
    }

    /**
     * The **free** ends of band [at] that another free end stands at — where two bands butt with no corner
     * between them, which since session 80 means only one thing: the pair is **not congruent** there, so no
     * ring is shared and session 79's cut (2) leaves the two to the boolean.
     *
     * The boolean does trim them, exactly and every time; what was missing is the *drawing* saying so, which
     * is what makes a level section through such a pair close ([runsInto]).
     */
    private fun endsRunInto(
        pieces: List<Piece>,
        at: Int,
        corners: List<Corner>,
    ): List<Pair<Boolean, Int>> {
        val seg = pieces[at].seg ?: return emptyList()
        val claimed = HashSet<Pair<Int, Boolean>>()
        for (c in corners) claimed.addAll(c.ends)
        val out = ArrayList<Pair<Boolean, Int>>()
        for (atStart in listOf(true, false)) {
            if ((at to atStart) in claimed) continue
            val v = if (atStart) seg.start else seg.end
            for (j in pieces.indices) {
                if (j == at || pieces[j].choice.convex != pieces[at].choice.convex) continue
                val other = pieces[j].seg ?: continue
                val meets =
                    listOf(true, false).any { bStart ->
                        (j to bStart) !in claimed && ((if (bStart) other.start else other.end) - v).length() <= RING_TOL
                    }
                if (meets) out.add(atStart to j)
            }
        }
        return out
    }

    /**
     * How far band [a]'s ruling through section point [p] runs before it enters band [b]'s own tool — or
     * null where it never does, which is every pair that only touches.
     *
     * *Exact, and the same arithmetic as everything else here.* [b]'s tool is its wedge carried along a
     * straight run, so the ruling — itself a straight line — reads in [b]'s section frame as a **line**
     * moving affinely with the station along [a]. Where that line crosses the wedge's own boundary is line
     * against line and line against circle; whether the free end starts *inside* the wedge is the parity of
     * the crossings behind it, so nothing is sampled and no containment is guessed at. A wedge piece this
     * vocabulary cannot cross exactly — a drawn profile's Bézier — states no crossing, so such a pair is
     * left as it was rather than clipped by a fitted curve (OP-15's honesty line).
     */
    private fun runsInto(
        a: Piece,
        b: Piece,
        p: Vec2,
        atStart: Boolean,
    ): Double? {
        val ea = a.seg ?: return null
        val eb = b.seg ?: return null
        val va = ea.end - ea.start
        val la = va.length()
        val lb = (eb.end - eb.start).length()
        if (la <= Geom3.WELD_TOL || lb <= Geom3.WELD_TOL) return null
        val ua = va * (1.0 / la)
        val ub = (eb.end - eb.start) * (1.0 / lb)
        val origin = worldOnStraight(a.crease, ea.start, ua, p, 0.0)
        val q0 = Vec2((origin - b.crease.ref.at).dot(b.crease.e1), (origin - b.crease.ref.at).dot(b.crease.ref.e2))
        val d = Vec2(ua.dot(b.crease.e1), ua.dot(b.crease.ref.e2))
        if (d.length() <= DIR_EPS) return null
        val from = if (atStart) 0.0 else la
        // the tool is only there over [b]'s own run, and the ruling has to be standing in it to be cut by it
        val t0 = (origin - eb.start).dot(ub)
        val dt = ua.dot(ub)
        if ((t0 + from * dt) < -Geom3.WELD_TOL || (t0 + from * dt) > lb + Geom3.WELD_TOL) return null
        val crossings = wedgeCrossings(b.wedge, q0, d)
        if (crossings.isEmpty()) return null
        // **inside is a parity, not a containment test**: a line comes in from outside, so the run just past
        // the free end stands in the wedge exactly when an odd number of crossings lie at or behind that end
        // — *at* included, because the two bands share a face and [b]'s own leg lies in it, which is a
        // crossing standing exactly where [a]'s cap does
        val inward = if (atStart) 1.0 else -1.0
        if (crossings.count { inward * (it - from) <= Geom3.WELD_TOL } % 2 == 0) return null
        return crossings.filter { inward * (it - from) > Geom3.WELD_TOL }.minByOrNull { inward * (it - from) }
    }

    /** Where the line `q0 + s·d` crosses the wedge's own boundary, as stations along [a]'s run. */
    private fun wedgeCrossings(
        wedge: Wedge,
        q0: Vec2,
        d: Vec2,
    ): List<Double> {
        val line = Line(q0, d.normalized())
        val dd = d.dot(d)
        val out = ArrayList<Double>()
        for (e in wedge.region.outer.elements) {
            val pts =
                when (e) {
                    is ProfileElement.Seg -> {
                        val v = e.segment.b - e.segment.a
                        if (v.length() <= Vec2.EPS) emptyList() else GeomMath.intersectLL(line, Line(e.segment.a, v)).points
                    }
                    is ProfileElement.ArcE -> GeomMath.intersectLC(line, Circle(e.arc.center, e.arc.radius)).points
                    is ProfileElement.CircleE -> GeomMath.intersectLC(line, e.circle).points
                    else -> emptyList()
                }
            for (q in pts) if (onSpanOf(e, q)) out.add((q - q0).dot(d) / dd)
        }
        return out.sorted()
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
    ): Pair<List<ProfileElement>?, Msg?> {
        val plane =
            patch.plane
                ?: return null to
                    Msgs.refusalBlendIsNotPlaneSoStrip(name = patch.name.label)
        val offsets = HashMap<Int, Double>()
        for ((edge, d) in trims) {
            val hits = patch.outline.indices.filter { sameCurve(plane, patch.outline[it], edge) }
            if (hits.size != 1) {
                return null to
                    Msgs.refusalBlendMatchesPiecesOwnBoundarySo(name = edge.name.label, count = hits.size, name2 = patch.name.label)
            }
            offsets[hits[0]] = (offsets[hits[0]] ?: 0.0) + d
        }
        val out = patch.outline.toMutableList()
        for (chain in chainsOf(patch.outline)) {
            if (chain.none { it in offsets }) continue
            val (fixed, why) = offsetChain(patch.outline, chain, offsets)
            if (fixed == null) return null to Msgs.refusalQualified(name = patch.name.label, reason = why ?: Msg.EMPTY)
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
    ): Pair<List<ProfileElement>?, Msg?> {
        val (fixed, code) = GeomMath.offsetCycle(chain.map { outline[it] }, chain.map { offsets[it] ?: 0.0 })
        if (fixed != null) return fixed to null
        return null to
            when (code) {
                GeomMath.OFFSET_NOT_A_CARRIER ->
                    Msgs.refusalBlendOnePiecesBesideBlendIs()
                GeomMath.OFFSET_NO_JUNCTION -> Msgs.refusalBlendBlendNewBoundaryDoesNot()
                else -> Msgs.refusalBlendPieceThatBoundaryIsConsumed()
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

    // ---- the outline correction: the notch a band's own **free end** takes out of the face it ends in ----

    /**
     * A band's **free end**, read as the notch its cap takes out of the face that cap stands in (session
     * 81; the queued section limit (a)).
     *
     * *What the fault was.* A band that ends without a corner — a fillet along one rim edge of a plate —
     * closes on a cap standing in the plane square to its edge, and that plane is a **third** face: the side
     * face at the edge's end, which is neither of the two the band runs between. The cap takes the wedge's
     * own section out of that face at the corner the edge ends at, and [deriveTrimmedList] corrects the
     * outlines of the band's *own* two faces only — so the end face kept a stale rectangle, a level section
     * through the body crossed a boundary that is not where the drawing says it is, and the loop did not
     * close ([Section3.regionsOf]'s *"does not close into an area"*).
     *
     * *What the cure is, and why it is the same arithmetic.* The wedge's section is already stated exactly,
     * in the crease's own `(e1, e2)` frame, and at a free end of a **straight** edge that frame lies in the
     * end face's plane — the cap is square to the edge and so is the face. So the notch is that very section
     * carried through one rigid map into the face's own plane coordinates, and the correction is the corner
     * of the ring replaced by *setback along one face → the section → setback along the other*: line against
     * line and line against circle, [GeomMath.offsetCarrier]'s own vocabulary, nothing sampled and nothing
     * new (OP-15's exact tier).
     *
     * [before] and [after] are the ring pieces the corner stands between, as indices into the face's own
     * **undressed** boundary — which every level's list is piece for piece parallel to, because
     * [GeomMath.offsetCycle] restates a ring rather than re-cutting it. [pieces] runs from [before]'s
     * carrier to [after]'s, so the assembly is one splice.
     */
    internal class Notch(
        val edge: SolidEdge,
        val sec: BlendSection,
        val face: Int,
        val before: Int,
        val after: Int,
        /** The free end itself, in the face's own plane — what a size that would fit is scaled about. */
        val at: Vec2,
        val pieces: List<ProfileElement>,
        /**
         * Whether this splice reaches **past** the boundary as it now stands rather than biting into it —
         * true for the one-ended pivot's two corrections, false for a free end's own notch.
         *
         * It changes one thing and says why: a notch's junction is looked for **on the ring piece's own
         * span**, because a fillet's arc is tangent to that piece's old place and its two crossings stand
         * equally far from the corner they replace, so nearness cannot choose between them and the span can.
         * A bulge has no such ambiguity — its two ends lie *on* the neighbours' carriers by construction, so
         * the meeting is the end itself — and demanding the span would refuse the very extension it is.
         */
        val bulge: Boolean = false,
        /**
         * **The tolerance this chain was fitted to**, in millimetres — or null where every piece of it is
         * exact (OP-31, Tier B).
         *
         * The one producer this drawing has of a fitted curve: the flat top a one-ended pivot's cap leaves
         * on the third face, whose boundary is a torus met by a plane parallel to its own axis — a spiric of
         * Perseus, a quartic, and no member of this vocabulary ([Pivot.capChain]). It travels with the notch
         * so that the **face** it is spliced into can say so ([FacePatch.fitted]), which is the field item 2
         * of the rounding algebra owed and could not add.
         */
        val fitted: Double? = null,
    )

    /**
     * Every notch this dressing owes: one per **free end** of every band in the chain, over the whole chain
     * at once.
     *
     * An end is free when no corner claims it — no crossing, no pivot, no ball ([cornersOf]) — which is
     * exactly when the tool closes it with a flat cap instead. Asked of [piecesOf], so an end that a *later*
     * gesture turns into a corner never gets a notch at all rather than getting one and having it taken back.
     */
    private fun notchesOf(
        f: Feature3.Blend,
        faces: List<FacePatch>,
        trimmed: List<FacePatch>,
    ): List<Notch> {
        val pieces = piecesOf(f) ?: return emptyList()
        val claimed = HashSet<Pair<Int, Boolean>>()
        for (c in cornersOf(pieces).list) claimed.addAll(c.ends)
        val out = ArrayList<Notch>()
        // **the one-ended pivot owes two corrections of its own** (OP-31, item (b)): the face the walk runs
        // in loses the corner of its boundary to the section's tangency curve, and the third face **gains**
        // the flat top the walk's cap leaves on it. The two hand over at one parameter — where the section
        // first reaches the third face — so the two curves meet at the very vertex the body has there.
        for (c in cornersOf(pieces).list) {
            if (c !is Walk) continue
            // …and a **two-ended** walk owes the first of the two, over its whole length (OP-31, item 3). A
            // [Turn] leaves the very same tangency curve on the face it runs in as a [Pivot] does, and until
            // that corner's own rails were edges of the body nobody had to ask: now a rounding can address
            // one, so the strip it takes off that face has to come off the curve the body actually has
            // there rather than off the sharp meeting of two setback lines that is not on it.
            val hand = if (c is Pivot) c.capStart(pieces) ?: continue else c.walkLegs.size.toDouble()
            val at = if (c is Pivot) c.at else (c as Turn).at
            c.sharedChain(pieces, hand)?.let { chain ->
                spliceInto(faces, trimmed, c.walkFace, at, pieces[c.travelling], chain)?.let { out.add(it) }
            }
            if (c is Pivot) {
                c.capChain(pieces, hand)?.let { chain ->
                    spliceInto(faces, trimmed, c.third, c.at, pieces[c.a], chain, Combine3.FIT_TOL_MM)?.let { out.add(it) }
                }
            }
        }
        for ((j, piece) in pieces.withIndex()) {
            // a **fill** adds material rather than taking it, so its cap closes a void and notches nothing;
            // and only a straight run has a cap that stands in one plane at all
            if (!piece.choice.convex) continue
            val seg = piece.seg ?: continue
            for (atStart in listOf(true, false)) {
                if ((j to atStart) in claimed) continue
                val at = if (atStart) seg.start else seg.end
                val away = (if (atStart) seg.start - seg.end else seg.end - seg.start)
                if (away.length() <= Geom3.WELD_TOL) continue
                out.add(notchAt(faces, piece, at, away.normalized()) ?: continue)
            }
        }
        return out
    }

    /**
     * One corner correction as a [Notch]: [chain] spliced into [face]'s own boundary at [at], the two ring
     * pieces beside that corner re-trimmed to meet it.
     *
     * It is the free end's own splice with the sign of the change left to the geometry: a **notch** takes a
     * bite out of the corner and the one-ended pivot's cap **bulges past** it, and neither the junction nor
     * the assembly cares which — both are the chain's two ends met on the neighbours' own carriers.
     */
    private fun spliceInto(
        faces: List<FacePatch>,
        trimmed: List<FacePatch>,
        face: FacePatch,
        at: Vec3,
        piece: Piece,
        chain: List<ProfileElement>,
        fitted: Double? = null,
    ): Notch? {
        val index = faces.indexOfFirst { it.name == face.name }
        if (index < 0) return null
        val patch = faces[index]
        val plane = patch.plane ?: return null
        if (patch.reason != null) return null
        val v = plane.toLocal(at)
        val before = patch.outline.indices.filter { (GeomMath.endOf(patch.outline[it]) - v).length() <= SAME_CURVE_TOL }
        val after = patch.outline.indices.filter { (GeomMath.startOf(patch.outline[it]) - v).length() <= SAME_CURVE_TOL }
        if (before.size != 1 || after.size != 1 || before[0] == after[0]) return null
        val head = GeomMath.startOf(chain.first())
        // which way round the chain runs is read off the boundary **as it now stands**, not off the undressed
        // one: at an undressed corner both pieces run through the very corner the chain replaces and stand
        // the same distance from its ends, so the two tie and the tie is not a fact about the shape
        val ring = trimmed.getOrNull(index)?.outline?.takeIf { it.size == patch.outline.size } ?: patch.outline
        val forwards = offCarrier(ring[before[0]], head) <= offCarrier(ring[after[0]], head)
        return Notch(
            piece.crease.edge,
            piece.sec,
            index,
            before[0],
            after[0],
            v,
            if (forwards) chain else chain.reversed().map { GeomMath.reverse(it) },
            bulge = true,
            fitted = fitted,
        )
    }

    /**
     * The notch one free end takes, or null where this drawing does not state one: the face the cap stands
     * in has to be a **plane** of the body that is not one of the band's own two, standing square to the
     * edge with the end point on it, and there has to be exactly one such face — two coplanar candidates is
     * a body whose end the drawing cannot name, and it keeps what it kept (OP-3).
     */
    private fun notchAt(
        faces: List<FacePatch>,
        piece: Piece,
        at: Vec3,
        away: Vec3,
    ): Notch? {
        var index = -1
        for ((i, face) in faces.withIndex()) {
            if (face.name == piece.crease.face1.name || face.name == piece.crease.face2.name) continue
            val plane = face.plane ?: continue
            if (face.reason != null) continue
            if (abs(plane.normal.normalized().dot(away)) < 1.0 - TANGENT_TOL) continue
            if (abs(plane.distanceTo(at)) > ON_BOUNDARY_TOL) continue
            val rings = Project3.ringsOf(face.outline)
            if (rings.isEmpty() || !onFace(rings, plane.toLocal(at))) continue
            if (index >= 0) return null
            index = i
        }
        if (index < 0) return null
        val face = faces[index]
        val plane = face.plane ?: return null
        // the crease's own frame, planted at the free end: `e1` and `e2` are both square to the edge and the
        // face's normal **is** the edge, so both lie in this plane and the map is a rigid one
        val o = plane.toLocal(at)
        val ax = plane.toLocal(at + piece.crease.e1) - o
        val ay = plane.toLocal(at + piece.crease.ref.e2) - o
        if (abs(ax.x * ay.y - ax.y * ay.x) <= DIR_EPS) return null
        val map = Affine(ax.x, ax.y, ay.x, ay.y, o.x, o.y)
        val v = plane.toLocal(at)
        val before = face.outline.indices.filter { (GeomMath.endOf(face.outline[it]) - v).length() <= SAME_CURVE_TOL }
        val after = face.outline.indices.filter { (GeomMath.startOf(face.outline[it]) - v).length() <= SAME_CURVE_TOL }
        if (before.size != 1 || after.size != 1 || before[0] == after[0]) return null
        val chain = piece.wedge.pieces.map { GeomMath.transform(it, map) }
        // the section runs from the tangency on `face1` to the one on `face2`; which of the two ring pieces
        // each of those lies on is read off the pieces themselves, so no face-name bookkeeping decides it
        val head = GeomMath.startOf(chain.first())
        val forwards = offCarrier(face.outline[before[0]], head) <= offCarrier(face.outline[after[0]], head)
        return Notch(
            piece.crease.edge,
            piece.sec,
            index,
            before[0],
            after[0],
            v,
            if (forwards) chain else chain.reversed().map { GeomMath.reverse(it) },
        )
    }

    /**
     * [patch]'s boundary with every one of [notches] spliced in — the corner replaced by the section, and
     * the two pieces beside it re-trimmed on their own carriers.
     *
     * The junction is the ordinary intersection of two carriers with **two** things said about it that
     * [GeomMath.carrierJunction] cannot know on its own. Where the ring piece has not moved the tangency
     * *is* the answer and is taken verbatim; where it has, a fillet's arc is **tangent** to the piece's old
     * place, so the two solutions stand equally far from the corner they replace and nearness cannot choose
     * — the one on the section's **own span** is the corner ([meetOnSpan]).
     *
     * And where there is no meeting at all, the notch takes **nothing**: the strips the bands took off this
     * face already reach further into the corner than the cap's own section does, so the boundary is
     * already where it should be and the notch is dropped rather than drawn. (It is provable rather than
     * hopeful: the section lies inside the box of its two setbacks, so a trim as deep as either setback
     * leaves it nothing to cut.)
     */
    private fun notchedOutline(
        patch: FacePatch,
        notches: List<Notch>,
    ): Pair<List<ProfileElement>?, Msg?> {
        spliceAll(patch, notches, null, 1.0)?.let { return it to null }
        // …the one whose absence makes the rest fit is the one to name, and the size that would fit there is
        // found by halving exactly as [largestFitting] finds a radius (OP-3 — a refusal that heals)
        val blame = notches.firstOrNull { spliceAll(patch, notches, it, 0.0) != null } ?: notches.first()
        var lo = 0.0
        var hi = 1.0
        repeat(FIT_STEPS) {
            val mid = (lo + hi) / 2.0
            if (spliceAll(patch, notches, blame, mid) != null) lo = mid else hi = mid
        }
        return null to
            Msgs.refusalBlendFreeEndNotchReachesPast(
                word = blame.sec.kind.word,
                sizePhrase = blame.sec.sizePhrase(),
                name = blame.edge.name.label,
                fitPhrase = blame.sec.fitPhrase(lo),
            )
    }

    /**
     * The boundary with the notches spliced in, or null where one of them cannot stand there — [adjust]
     * taken at [k] times its own size, and dropped altogether at zero.
     */
    private fun spliceAll(
        patch: FacePatch,
        notches: List<Notch>,
        adjust: Notch?,
        k: Double,
    ): List<ProfileElement>? {
        val cutStart = HashMap<Int, Vec2>()
        val cutEnd = HashMap<Int, Vec2>()
        val splice = HashMap<Int, List<ProfileElement>>()
        for (n in notches) {
            if (n === adjust && k <= 0.0) continue
            if (n.before !in patch.outline.indices || n.after !in patch.outline.indices) return null
            val pieces =
                if (n === adjust) n.pieces.map { GeomMath.transform(it, Affine.scaling(n.at, k)) } else n.pieces
            val chain = spliceOf(patch.outline, n, pieces) ?: continue
            // two free ends notching **one** corner of one face is a body this drawing cannot state the
            // boundary of, and it is refused rather than half-drawn
            if (n.before in cutEnd || n.after in cutStart || n.before in splice) return null
            cutEnd[n.before] = chain.first
            cutStart[n.after] = chain.second
            splice[n.before] = chain.third
        }
        val out = ArrayList<ProfileElement>(patch.outline.size + notches.sumOf { it.pieces.size })
        for (i in patch.outline.indices) {
            val e = patch.outline[i]
            if (i in cutStart || i in cutEnd) {
                val carrier = GeomMath.offsetCarrier(e, 0.0) ?: return null
                out.add(GeomMath.onCarrier(e, carrier, cutStart[i] ?: GeomMath.startOf(e), cutEnd[i] ?: GeomMath.endOf(e)) ?: return null)
            } else {
                out.add(e)
            }
            splice[i]?.let { out.addAll(it) }
        }
        return out
    }

    /**
     * Where one notch meets its two neighbours, and the section trimmed between — null where the boundary
     * as it now stands does not meet it at all (see [notchedOutline]).
     */
    private fun spliceOf(
        outline: List<ProfileElement>,
        n: Notch,
        pieces: List<ProfileElement>,
    ): Triple<Vec2, Vec2, List<ProfileElement>>? {
        val head = pieces.first()
        val tail = pieces.last()
        val s1 = meetOnSpan(outline[n.before], head, GeomMath.startOf(head), n.bulge) ?: return null
        val s2 = meetOnSpan(outline[n.after], tail, GeomMath.endOf(tail), n.bulge) ?: return null
        val cut = pieces.toMutableList()
        // …and a chain that already **ends** where it meets its neighbour is left alone: there is nothing to
        // trim, and a fitted cubic has no offset carrier to be trimmed on (OP-31's cap curve)
        if ((s1 - GeomMath.startOf(cut[0])).length() > SAME_CURVE_TOL) {
            cut[0] = GeomMath.onCarrier(cut[0], GeomMath.offsetCarrier(cut[0], 0.0) ?: return null, s1, GeomMath.endOf(cut[0])) ?: return null
        }
        val last = cut.size - 1
        if ((s2 - GeomMath.endOf(cut[last])).length() > SAME_CURVE_TOL) {
            cut[last] = GeomMath.onCarrier(cut[last], GeomMath.offsetCarrier(cut[last], 0.0) ?: return null, GeomMath.startOf(cut[last]), s2) ?: return null
        }
        return Triple(s1, s2, cut)
    }

    /** Where the ring piece [ring] and the section's end piece [end] meet — see [notchedOutline]. */
    private fun meetOnSpan(
        ring: ProfileElement,
        end: ProfileElement,
        known: Vec2,
        bulge: Boolean = false,
    ): Vec2? {
        val a = GeomMath.offsetCarrier(ring, 0.0) ?: return null
        if (offCarrier(ring, known) <= SAME_CURVE_TOL) return if (bulge || onSpanOf(ring, known)) known else null
        val b = GeomMath.offsetCarrier(end, 0.0) ?: return null
        return GeomMath.carrierCrossings(a, b)
            .filter { onSpanOf(end, it) && onSpanOf(ring, it) }
            .minByOrNull { (it - known).length() }
    }

    /** How far [q] stands off the carrier of [e] — the distance a line, a circle or nothing states. */
    private fun offCarrier(
        e: ProfileElement,
        q: Vec2,
    ): Double {
        val (line, circle) = GeomMath.offsetCarrier(e, 0.0) ?: return Double.MAX_VALUE
        if (line != null) return abs((q - line.origin).dot(line.dir.perp().normalized()))
        if (circle != null) return abs((q - circle.center).length() - circle.radius)
        return Double.MAX_VALUE
    }

    /** Whether [q] stands on [e]'s **own** run rather than merely on the carrier it lies along. */
    private fun onSpanOf(
        e: ProfileElement,
        q: Vec2,
    ): Boolean =
        when (e) {
            is ProfileElement.Seg -> {
                val d = e.segment.b - e.segment.a
                val len2 = d.dot(d)
                if (len2 <= 1e-18) false else ((q - e.segment.a).dot(d) / len2) in -SAME_CURVE_TOL..(1.0 + SAME_CURVE_TOL)
            }
            is ProfileElement.ArcE -> onArc(e.arc, (q - e.arc.center).angle())
            is ProfileElement.CircleE -> true
            else -> false
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
     *
     * *Along, not equal to* (session 81). A dressed face's boundary piece is the base edge's own curve
     * **already shortened** wherever a neighbouring edge was rounded: the piece keeps its carrier and gives
     * up an end. Asking for equal endpoints therefore answered *"matches 0 pieces"* the moment a chain
     * rounded two edges of one face — a corner of a box taken one gesture at a time — and the face lost its
     * outline for no reason at all. So the question is whether the piece **lies along** the edge: the same
     * carrier, and its own run inside the edge's. The `hits.size != 1` guard in [correctedOutline] is what
     * keeps that honest, and a run that stands off the carrier or past its ends is still no match.
     */
    private fun sameCurve(
        plane: Plane3,
        e: ProfileElement,
        edge: SolidEdge,
    ): Boolean {
        val ours = marksOf(plane, e)
        // …and **the same question asked the other way round** (OP-31, item 3). Session 81 relaxed *equal
        // to* into *lies along*, because a dressed face's boundary piece gives up an end wherever a
        // neighbour was rounded while the edge still ran the whole way. Since a rail is stated over its
        // **crease's own run** the containment is as often the other way: the corner takes the last of the
        // rail while the face's boundary piece runs on into the corner's own tangency chain. Neither side
        // is "the longer one" as a rule, so the predicate is *the same carrier, and one run inside the
        // other* — and the `hits.size != 1` guard in [correctedOutline] is still what keeps it honest, since
        // two collinear pieces both overlapping one edge refuse rather than pick.
        val theirs = edgeMarks(edge)
        return when (val g = edge.geom) {
            is EdgeGeom.Straight ->
                e is ProfileElement.Seg &&
                    (ours.all { alongSeg(g.a, g.b, it) } || theirs.all { onPieceIn(plane, e, it) })
            is EdgeGeom.OnPlane -> {
                val ca = centreAndRadius(plane, e)
                val cb = centreAndRadius(g.plane, g.piece)
                val sameCarrier =
                    when {
                        ca == null && cb == null -> e is ProfileElement.Seg && g.piece is ProfileElement.Seg
                        ca == null || cb == null -> false
                        else -> (ca.first - cb.first).length() <= SAME_CURVE_TOL && abs(ca.second - cb.second) <= SAME_CURVE_TOL
                    }
                sameCarrier &&
                    (ours.all { onPieceIn(g.plane, g.piece, it) } || theirs.all { onPieceIn(plane, e, it) })
            }
        }
    }

    /** The start, the middle and the end of boundary piece [e] of the face in [plane], in the world. */
    private fun marksOf(
        plane: Plane3,
        e: ProfileElement,
    ): List<Vec3> {
        val out = ArrayList<Vec3>(3)
        out.add(plane.toWorld(GeomMath.startOf(e)))
        out.add(plane.toWorld(GeomMath.endOf(e)))
        sectionPointAt(e, 0.5)?.let { out.add(plane.toWorld(it)) }
        return out
    }

    /** The same three marks of an **edge**'s own curve. */
    private fun edgeMarks(edge: SolidEdge): List<Vec3> =
        when (val g = edge.geom) {
            is EdgeGeom.Straight -> listOf(g.a, g.b, (g.a + g.b) * 0.5)
            is EdgeGeom.OnPlane -> marksOf(g.plane, g.piece)
        }

    /** Whether [p] stands on the run [piece] draws in [plane] — in the plane, on the carrier, within the span. */
    private fun onPieceIn(
        plane: Plane3,
        piece: ProfileElement,
        p: Vec3,
    ): Boolean {
        if (abs(plane.distanceTo(p)) > SAME_CURVE_TOL) return false
        val q = plane.toLocal(p)
        return offCarrier(piece, q) <= SAME_CURVE_TOL && onSpanOf(piece, q)
    }

    /** Whether [p] stands on the straight run from [a] to [b] — on its line, and between its two ends. */
    private fun alongSeg(
        a: Vec3,
        b: Vec3,
        p: Vec3,
    ): Boolean {
        val d = b - a
        val len = d.length()
        if (len <= Geom3.WELD_TOL) return (p - a).length() <= SAME_CURVE_TOL
        val u = d * (1.0 / len)
        val t = (p - a).dot(u)
        return (p - (a + u * t)).length() <= SAME_CURVE_TOL && t >= -SAME_CURVE_TOL && t <= len + SAME_CURVE_TOL
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

    val NO_FACE_UNDER_CLICK =
        Msgs.refusalBlendNoFlatFaceThisSolid()

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
    ): Pair<Int?, Msg?> {
        val (edges, whyEdges) = Section3.edges(feature)
        if (edges == null) return null to whyEdges
        val edge = edges.getOrNull(edgeIndex) ?: return null to Msgs.refusalBlendThisSolidHasNoEdge4(edgeIndex = edgeIndex + 1)
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
