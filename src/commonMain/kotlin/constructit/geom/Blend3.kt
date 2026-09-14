package constructit.geom

import constructit.l10n.Msg
import constructit.l10n.Msgs
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
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
    private const val GROW_MM = ToolStep.MM

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
        // …and **not a free end's own notch curve** (OP-31, slice 5b), though it is as much a crease of this
        // face as any other. A whole-face gesture records one scored choice **per edge of the run**
        // (`signs=`), so the length of that list is part of what the address means — and how many notch
        // curves a face carries is a function of how many free ends the bands *below* it happen to have,
        // which every later gesture may move. Sweeping them up would make a stored face address mean a
        // different set of edges on a later build, which is the one thing OP-18 does not allow. The curve
        // has its own address since this slice: it is rounded by picking it.
        val live = hits.filter { edges[it].reason == null && !smoothRail(feature, it) && edges[it].name !is EdgeName.BlendNotch }
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
        // **read off the entry's own name, never off arithmetic over the block** (OP-31, slice 5b). The
        // appended list is grouped per entry now ([entryOwning]), so *"two per target, in the feature's
        // order"* is no longer where a rail stands; what a slot is, is what it says it is. A **corner
        // curve** or a **notch** is no rail at all, and whether *it* is a crease is said on the entry
        // itself ([SolidEdge.reason]) rather than here.
        val name = Section3.edges(feature).first?.getOrNull(index)?.name as? EdgeName.BlendRail ?: return false
        val k = feature.targets.indices.firstOrNull { feature.targets[it] == name.edge && !feature.isAbsent(it) } ?: return false
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
        /**
         * Every face the body states, as [creaseOf] read them — asked by [endSteps] of a **curved** crease
         * and by nothing else (OP-31, slice 5e): a band along an arc ends on the meridian plane, and whether
         * that plane is a face of the body is what decides whether the tool has to step through it.
         */
        val all: List<FacePatch>,
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
                    is ProfileElement.EllipseE, is ProfileElement.EllipticArcE, is ProfileElement.BezierE ->
                        // …and neither an ellipse nor a fitted curve carries a **rigid** section, which is
                        // what this reading is for. Since OP-31 slice 5f that is no longer a dead end: a
                        // crease whose section changes along the run is a **canal** band ([canalOf]), and
                        // every caller asks the catalogue first and the canal after it — so what this
                        // sentence has to say is which of the two the crease is, and it says exactly that
                        // (session 84: it used to advise *"blend a straight or circular edge"*, which the
                        // canal made false — this drawing does round the crease, and only rounds it).
                        null to Msgs.refusalBlendCarriesNoRigidSection(name = edge.name.label)
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
            // **a fitted crease is not a carrier to blend along** (OP-31, Tier B): its own normal section
            // turns along it, which is the very thing this vocabulary states for no edge — and it is said
            // in the same words a drawn spline's crease is refused in, because it is one.
            is EdgeGeom.InSpace -> null to Msgs.refusalBlendCarriesNoRigidSection(name = edge.name.label)
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
        return Crease(edge, path, e1, stations, stations[at], leg1!!, leg2!!, face1, face2, base1, base2, length, faces) to null
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
        val s1 = stepOf(crease.leg1, wedge.t1, wedge.t2) ?: return null to Msgs.refusalBlendTwoFacesThatCreaseRun()
        val s2 = stepOf(crease.leg2, wedge.t2, wedge.t1) ?: return null to Msgs.refusalBlendTwoFacesThatCreaseRun()
        val corner = s1.meet(s2) ?: return null to Msgs.refusalBlendTwoFacesThatCreaseRun()
        val g1 = s1.step(wedge.t1)
        val g2 = s2.step(wedge.t2)
        // the two legs as the section walks them — from the crease point out to each tangency, the straight
        // leg in one step and the round one in its own chords, minus the crease point itself, which the
        // section already carries as its first vertex (and as the stepped **corner** in the grown twin)
        val leg1 = GeomMath.tessellatePiece(sidePiece(crease.leg1, o, wedge.t1), GeomMath.TESS_TOL_MM).drop(1)
        val leg2 = GeomMath.tessellatePiece(sidePiece(crease.leg2, wedge.t2, o), GeomMath.TESS_TOL_MM).dropLast(1)
        val plain = listOf(o) + leg1 + arc + leg2
        val grown = listOf(corner) + leg1.map { s1.step(it) } + arc + leg2.map { s2.step(it) }
        // the very same boundary as an exact loop: the two legs stepped off, a jog back onto each tangency,
        // and the blend's own curve between them untouched
        val loop =
            Loop(
                listOf(s1.piece(corner, g1), ProfileElement.Seg(Segment(g1, wedge.t1))) +
                    wedge.pieces +
                    listOf(ProfileElement.Seg(Segment(wedge.t2, g2)), s2.piece(g2, corner)),
            )
        val region = Region(if (GeomMath.signedArea(loop) >= 0.0) loop else GeomMath.reverseLoop(loop), emptyList())
        // **every section is stepped off now**, which is what [stepOf] made true: a round leg has an offset
        // of its own, so there is no longer a section that is swept as it was drawn. The flag stays because
        // it is what says a straight run's tube must be built by [toolMesh] — the only builder that carries
        // [endSteps] — rather than by the plain sweep, whose cap then lies **in** the body's own end face.
        // A band along a straight crease against a cylinder went down that path and its cap was coplanar
        // with the annulus the crease ends on: on `XY` the engine cancelled the two sheets and the body came
        // out, and the same body sketched on a plane turned 30° about `y` came out folded (GitHub #36).
        val stepped = true
        if (grown.size < 3) return null to Msgs.refusalBlendRoundingOwnSectionHasFewer()
        // one winding for both, so index k of either ring is the same point of the same section
        return if (Geom3.polygonArea(grown) >= 0.0) {
            Grown(grown, plain, region, stepped) to null
        } else {
            Grown(reversedFromFirst(grown), reversedFromFirst(plain), region, stepped) to null
        }
    }

    /**
     * One leg's own **step off its face** — the map that carries a point of the leg [GROW_MM] out of the
     * wedge, and the profile element the stepped leg is.
     *
     * *Why a round leg has one too* (OP-31, slice 5h). [sectionOf]'s rule is that **a tool never shares a
     * face with the body**, and it used to hold only where both legs were straight: *"a round leg has no
     * straight offset in this vocabulary, so it is swept as it always was"*. It has one — a **circle's
     * offset is a concentric circle**, exactly, which is the one offset that needs no fitting at all — and
     * without it every band along a crease against a cylinder puts a flat sheet of tool *exactly in* the
     * body's own plane face and the difference of two solids that share a face is the coin toss
     * [MeshCanon.flap] exists to name. It came up tails at the end cap of a partial revolve and heads at
     * its start cap, off nothing but the arithmetic of a rotation, which is what said the coin was being
     * tossed at all (GitHub #36, slice 5h's probe).
     */
    private class LegStep(
        private val normal: Vec2?,
        private val circle: Circle?,
        private val side: Double,
        /** How far this leg steps — the micron off a plane, the wall's own skin off a curved one. */
        val off: Double,
    ) {
        /** The unit step out of the wedge at [q] — constant along a line, radial on a circle. */
        fun normalAt(q: Vec2): Vec2 {
            val c = circle ?: return normal!!
            val d = q - c.center
            return if (d.length() <= Vec2.EPS) normal ?: Vec2(1.0, 0.0) else d.normalized() * side
        }

        fun step(q: Vec2): Vec2 = q + normalAt(q) * off

        /** The stepped leg from [from] to [to]: a parallel line, or the concentric circle. */
        fun piece(
            from: Vec2,
            to: Vec2,
        ): ProfileElement = stepped?.let { sidePiece(FilletLeg(null, it), from, to) } ?: ProfileElement.Seg(Segment(from, to))

        /** This leg after the step: the concentric circle, or null where it is a line. */
        val stepped: Circle? get() = circle?.let { Circle(it.center, it.radius + side * off) }

        /** The line this leg becomes, as `p·n = d` through the crease point at the origin — null for a circle. */
        val lineNormal: Vec2? get() = if (circle == null) normal else null

        /**
         * Where this stepped leg and [other]'s meet, **solved and not linearised** — the grown section's own
         * corner.
         *
         * A line against a line is two linear equations. A **circle** is not: taking the corner as the point
         * `d` from each leg's *tangent* at the crease point leaves it off the offset circle by `d²/2R`,
         * which is nothing while `d` is a micron and is a whole weld tolerance once `d` is the wall's own
         * skin — and the profile's outline then does not close (OP-31, slice 5h's pose rework). So the two
         * offset legs are intersected as what they are, and of the two crossings the one **nearest the
         * crease point** is the corner: the other is the far side of the circle.
         */
        fun meet(other: LegStep): Vec2? {
            val n1 = lineNormal
            val n2 = other.lineNormal
            if (n1 != null && n2 != null) {
                val det = n1.x * n2.y - n1.y * n2.x
                if (abs(det) <= 1e-9) return null
                return Vec2((off * n2.y - other.off * n1.y) / det, (other.off * n1.x - off * n2.x) / det)
            }
            if (n1 != null) return other.stepped?.let { crossLineCircle(n1, off, it) }
            if (n2 != null) return stepped?.let { crossLineCircle(n2, other.off, it) }
            val c1 = stepped ?: return null
            val c2 = other.stepped ?: return null
            val d = c2.center - c1.center
            val len = d.length()
            if (len <= Vec2.EPS) return null
            // the radical line of the two circles, then the same crossing as a line against a circle
            val a = (c1.radius * c1.radius - c2.radius * c2.radius + len * len) / (2.0 * len)
            return crossLineCircle(d.normalized(), a + d.normalized().dot(c1.center), c1)
        }

        /** Where the line `p·n = d` crosses [c], nearest the crease point at the origin. */
        private fun crossLineCircle(
            n: Vec2,
            d: Double,
            c: Circle,
        ): Vec2? {
            val foot = n * d
            val dir = n.perp()
            val m = foot - c.center
            val b = 2.0 * m.dot(dir)
            val cc = m.dot(m) - c.radius * c.radius
            val disc = b * b - 4.0 * cc
            if (disc < 0.0) return null
            val root = sqrt(disc)
            val p1 = foot + dir * ((-b + root) / 2.0)
            val p2 = foot + dir * ((-b - root) / 2.0)
            return if (p1.length() <= p2.length()) p1 else p2
        }
    }

    /**
     * [LegStep] for one leg, or null where the leg states neither a line nor a circle to step off.
     *
     * Which way is **out of the wedge** is read off the other leg's own tangency [other], which always
     * stands on the wedge's side: away from it along a straight leg ([outwardAt]), and on a round one the
     * way that takes the circle *away* from it — inward where the wedge stands outside the circle, outward
     * where it stands inside. That is [outwardAt]'s rule said once for both kinds, so a leg's step is out
     * of the material at a convex crease and into it at a concave one exactly as it always was.
     */
    private fun stepOf(
        leg: FilletLeg,
        t: Vec2,
        other: Vec2,
    ): LegStep? {
        if (leg.line != null) return outwardAt(t, other)?.let { LegStep(it, null, 1.0, GROW_MM) }
        val c = leg.circle ?: return null
        if (c.radius <= Geom3.WELD_TOL) return null
        val side = if ((other - c.center).length() >= c.radius) -1.0 else 1.0
        // **and a round leg steps off by the wall's own skin, not by the micron a plane is stated to**
        // (OP-31, slice 5f's rule, said here for the ordinary band — GitHub #36's pose probe). The body's
        // triangles stand **inside** a curved face by as much as its own tessellation tolerance, twenty
        // times the micron, so a leg a micron proud of the *true* surface is still a fifth of a tolerance
        // short of where the body's skin actually is: the tool's leg and the body's facets then cross each
        // other along the whole rail in a band as wide as the chords are, and what the boolean answers
        // there is a coincident pair of triangles or a tangent contact rather than a crossing. Whether it
        // answered one or the other was decided by the **pose** — a revolve sketched on `XY` built and the
        // same revolve turned 30° about `y` did not — which is how it was found. It is [canalGrow]'s own
        // number, read the same way: twice the wall's own skin, never less than the micron.
        val off = max(GROW_MM, 2.0 * GeomMath.effectiveTol(c.radius, GeomMath.TESS_TOL_MM))
        // …and a circle the step would turn inside out is no leg to step off: a wall that bends within its
        // own skin of nothing states no offset, and the section says so rather than folding through its
        // own centre
        if (c.radius + side * off <= Geom3.WELD_TOL) return null
        return LegStep(outwardAt(t, other), c, side, off)
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
        val delta = min(sec.reach(), crease.length / 2.0) * PROBE_FRACTION
        if (delta <= Geom3.WELD_TOL) return 0.0 to 0.0
        val el = soleElement(crease)
        // **a curved crease's end is stepped where — and only where — it stands *in* a face of the body**
        // (OP-31, slice 5e). A band along an arc ends on the **meridian** plane, and where the body has a
        // face in that plane the tool's cap and that face are two sheets facing against each other: a
        // sector's rim ends exactly so, in its own radial face, and the boolean answered it with a
        // zero-thickness flap rather than a body. So the tube overshoots the face by the same micron a
        // straight band's does and the contact becomes an ordinary transversal crossing.
        //
        // Everywhere else it states **no step at all**, which is the three-way reading a straight run takes
        // and cannot: a curved band's own continuation is its *circle*, and where the crease hands over to a
        // neighbour **tangentially** the boundary runs on along the tangent instead — so a micron of the
        // circle carried past the hand-over leaves the neighbour's own band tangentially rather than
        // crossing it, which is the worst-conditioned contact there is (the rasped rim of GitHub #29, whose
        // three pieces are one ribbon). Nothing is owed there: the neighbour's tube covers that micron.
        if (el is Curve3Element.Arc3) {
            if (el.radius <= Geom3.WELD_TOL || el.arcLength <= Geom3.WELD_TOL) return 0.0 to 0.0
            if (abs(el.sweepAngle) >= 2.0 * PI - 1e-9) return 0.0 to 0.0
            return capInFace(crease, true) to capInFace(crease, false)
        }
        val seg = el as? Curve3Element.Seg3 ?: return 0.0 to 0.0
        val run = seg.end - seg.start
        if (run.length() <= Geom3.WELD_TOL) return 0.0 to 0.0
        val dir = run.normalized()
        return stepBeyond(crease, seg.start - dir * delta) to stepBeyond(crease, seg.end + dir * delta)
    }

    /** Out past the end by [GROW_MM] where the body has a face in the cap's own plane, and nothing where not. */
    private fun capInFace(
        crease: Crease,
        atStart: Boolean,
    ): Double {
        val els = crease.path.elements
        val el = if (atStart) els.first() else els.last()
        val t = Curves3.tangentAt(el, if (atStart) 0.0 else 1.0) ?: return 0.0
        val at = Frames3.pointAt(el, if (atStart) 0.0 else 1.0)
        val away = (if (atStart) t * -1.0 else t).normalized()
        val flush =
            crease.all.any { f ->
                f.plane?.let { p -> abs(p.normal.normalized().dot(away)) >= 1.0 - TANGENT_TOL && abs(p.distanceTo(at)) <= ON_BOUNDARY_TOL } == true
            }
        return if (flush) -GROW_MM else 0.0
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

        /**
         * Whether the ends this corner closes stand **on** the axis it turns about — a pivot about a *sharp*
         * upright, and nothing else.
         *
         * The section's leg in the other face then lies *along* that axis, so it takes the **plain** section
         * rather than the stepped-off one: a micron off the axis is a micron-wide disc swept round it, which
         * is the fold [toolMesh] and [sectionOf] both explain at length. About a **band** the axis stands
         * clear of the section and the step-off costs nothing.
         */
        val onAxis: Boolean get() = false

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

        /** The body's own vertex the walk turns about — where the corner is. */
        val walkAt: Vec3

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
        ): List<ProfileElement>? = sharedChainByLeg(pieces, tEnd)?.map { it.second }

        /**
         * The same curve, **leg by leg** — which is what a rounding of one of the walk's own rails needs: a
         * strip is taken off the tangency of the leg that carries it and off no other (OP-31, slice 5b).
         */
        fun sharedChainByLeg(
            pieces: List<Piece>,
            tEnd: Double,
        ): List<Pair<Int, ProfileElement>>? {
            val plane = walkFace.plane ?: return null
            val out = ArrayList<Pair<Int, ProfileElement>>()
            for (k in walkLegs.indices) {
                val lo = k.toDouble()
                val hi = min((k + 1).toDouble(), tEnd)
                if (hi - lo <= 1e-12) continue
                val p0 = plane.toLocal(tangencyAt(pieces, lo) ?: return null)
                val p1 = plane.toLocal(tangencyAt(pieces, hi) ?: return null)
                val leg = walkLegs[k]
                val pivot = leg.pivot
                if (pivot == null || abs(leg.turn) <= TANGENT_TOL) {
                    if ((p1 - p0).length() > Geom3.WELD_TOL) out.add(k to ProfileElement.Seg(Segment(p0, p1)))
                    continue
                }
                val c = plane.toLocal(pivot)
                val r = (p0 - c).length()
                if (r <= Geom3.WELD_TOL || (p1 - p0).length() <= Geom3.WELD_TOL) continue
                out.add(k to ProfileElement.ArcE(Arc(c, r, (p0 - c).angle(), (p1 - c).angle(), leg.turn >= 0.0)))
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

        override val walkAt: Vec3 get() = at

        override val rings: List<Placement> = walkRings(legs)

        override val ends: List<Pair<Int, Boolean>> get() = listOf(a to aAtStart, b to bAtStart)

        override val onAxis: Boolean get() = extra.isEmpty()

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
     * The **ledge**: the inside corner of two roundings that are *not* alike (OP-31, slice 5a).
     *
     * *What the corner is, in one sentence.* At an inside corner two bands do not overlap, so what belongs
     * between their ends is what the rolling ball does: it pivots about the upright ([Turn]). With **unlike**
     * sections the two balls are unlike too, and the pivot each of them would sweep is a different surface —
     * neither ends on the other's ring, which is what [ringsAgree] refuses and what left this pair unbuilt
     * (and, since the matrix, refused by name) until now.
     *
     * *Which of the two travels, derived and not chosen.* A rolling-ball blend removes the material no ball
     * of the family can be kept out of, so **the union of the two pivots is the body**, and where one
     * section contains the other that union *is* the containing one's pivot — the contained ball sweeps
     * nothing the other has not already swept. So the **deeper** section travels, the shallower one does
     * not, and building only the one that shows is what keeps the face list honest: a corner face that is
     * wholly inside another rounding's removal is a face the body does not have. Where **neither** section
     * contains the other — a bevel narrower than a round, whose chord and whose arc cross twice — the union
     * is two pivots and two surfaces meeting in a quartic, and that pair is refused by name instead
     * ([sectionContains], and the note under OP-31's fitted tier).
     *
     * *And the ledge itself.* The walk ends where its section stands in the **other band's own end plane**,
     * having turned through the corner's exterior angle — the same plane that band's own tube ends on. The
     * two sections there are nested and not equal, so between them stands a piece of that plane: a ledge as
     * wide as the two sections differ, exact (a plane through a horn torus' own axis cuts it in a circle,
     * and the other band's end ring is its own section), and a **face of the body** with its own outline. It
     * is the price of two unlike roundings meeting, not an approximation of anything: no ball of either
     * radius reaches the material it keeps.
     *
     * The tool is **one stitched shell** — the two tubes, the walk between them and the ledge closing the
     * gap — so the pair is one group and one boolean, and both gesture orders build the same tool from the
     * same rings.
     */
    private class Ledge(
        /** The **deeper** of the two: its section contains the other's, and it is the one that travels. */
        val a: Int,
        val aAtStart: Boolean,
        val placeA: Placement,
        /** The shallower one — the walk lands in its end plane and the ledge stands between the two. */
        val b: Int,
        val bAtStart: Boolean,
        val placeB: Placement,
        val shared: FacePatch,
        /** The walk from [a]'s own end round to [b]'s end plane — one leg, about the sharp upright. */
        val legs: List<Leg>,
        val at: Vec3,
        /** The way [b]'s edge runs out of the corner: the side of the landing plane the material is on. */
        val away: Vec3,
    ) : Walk {
        override val travelling: Int get() = a

        override val walkLegs: List<Leg> get() = legs

        override val walkNormal: Vec3? get() = shared.plane?.normal?.normalized()

        override val walkFace: FacePatch get() = shared

        override val walkAt: Vec3 get() = at

        override val rings: List<Placement> = walkRings(legs)

        override val ends: List<Pair<Int, Boolean>> get() = listOf(a to aAtStart, b to bAtStart)

        /** The pivot is about the sharp upright itself, so both ends stand on its axis ([Corner.onAxis]). */
        override val onAxis: Boolean get() = true

        override fun ringAt(end: Pair<Int, Boolean>): Placement = if (end.first == a && end.second == aAtStart) placeA else placeB

        /**
         * The plane the walk lands in — [b]'s own end plane, framed so that its normal points the way the
         * ledge **faces**: away from the material, which stands on [b]'s own side of it.
         */
        fun plane(): Plane3 {
            val last = rings.last()
            return if (last.cx.cross(last.cy).dot(away) <= 0.0) Plane3(at, last.cx, last.cy) else Plane3(at, last.cy, last.cx)
        }

        /**
         * The ledge's two rings in the landing plane's own frame, each **starting at the wedge's corner and
         * running counter-clockwise**: the walk's arrival section, and [b]'s own end section inside it.
         */
        fun ledgeRings(pieces: List<Piece>): Pair<List<Vec2>, List<Vec2>>? {
            val p = plane()
            val outer = ringFromCorner(pieces[a].plain.map { q -> p.toLocal(rings.last().at(q)) })
            val inner = ringFromCorner(pieces[b].plain.map { q -> p.toLocal(placeB.at(q)) })
            return if (outer.size < 3 || inner.size < 3) null else outer to inner
        }

        override fun emit(
            pieces: List<Piece>,
            out: Geom3.MeshBuilder,
        ) {
            // the plain section throughout: the pivot is about the sharp upright and the section's leg in
            // the other face lies *along* it, so a step-off there would be swept into a disc ([toolMesh])
            val section = pieces[a].plain
            for (l in 0 until rings.size - 1) {
                val lo = section.map { rings[l].at(it) }
                val hi = section.map { rings[l + 1].at(it) }
                for (m in section.indices) {
                    val n = (m + 1) % section.size
                    // the walk continues [a]'s own tube, so the two rings take the same roles its two did
                    if (aAtStart) {
                        out.triangle(hi[m], hi[n], lo[n])
                        out.triangle(hi[m], lo[n], lo[m])
                    } else {
                        out.triangle(lo[m], lo[n], hi[n])
                        out.triangle(lo[m], hi[n], hi[m])
                    }
                }
            }
            // …and the ledge, closing the shell between the two unlike sections.
            //
            // **Why it is a strip between two whole rings and not a filled crescent.** The two sections
            // stand at the *same* corner on the *same* two legs, so their rings do not merely touch there —
            // they **overlap along a segment of each leg**, and filling only the crescent between the two
            // tangencies would leave the outer ring's own leg edge running past the inner ring's vertex: a
            // T-junction, which is not a shell (the tool refuses it by name, which is how this was found).
            // So both rings are stitched whole, merged by the **angle each vertex stands at about the
            // corner** — monotone along both, a wedge being star-shaped from its own corner, so no quad of
            // the strip can cross another — and the two collinear triangles that puts on the legs have no
            // area and carry the connectivity that closes the shell. A micron of daylight between the two
            // rings was tried instead and is worse: it un-collapses the pivot's own **pole**, where every
            // ring of a turn about a sharp upright shares one point, and the tool folds on itself there.
            val p = plane()
            val (outer, inner) = ledgeRings(pieces) ?: return
            stitchRings(out, outer, inner, { q -> p.toWorld(q) }, { q -> p.toWorld(q) })
        }

        override fun label(pieces: List<Piece>): Msg =
            Msgs.nameBlendInsideCorner(
                first = pieces[a].crease.edge.name.label,
                second = pieces[b].crease.edge.name.label,
                face = shared.name.label,
            )

        /** The walk's own surfaces, and the ledge after them — the last face this corner puts on the body. */
        override fun faces(
            pieces: List<Piece>,
            nameAt: (Int) -> FaceName,
        ): List<FacePatch> {
            val walk =
                facePlan(pieces).mapIndexed { k, (leg, sr) ->
                    walkLegPatch(pieces[travelling], leg, sr, nameAt(k), walkNormal)
                }
            return walk + ledgeFace(pieces, this, nameAt(walk.size))
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

        override val walkAt: Vec3 get() = at

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
        ): Pair<List<ProfileElement>, Double>? {
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
     * [fittedChain]'s twin **in space** — the same Hermite fit, the same halve-and-measure, a chain of
     * [Curve3Element.Bezier3] instead of [ProfileElement.BezierE].
     *
     * It is stated separately rather than shared because the two live in different vocabularies and neither
     * is a special case of the other: a curve *in a plane* is a boundary piece and enters an outline, a
     * curve *in space* is an edge's own carrier ([EdgeGeom.InSpace]) and enters the edge list. What they
     * share — a Hermite cubic on central-difference tangents, halved until the midpoint of every span is
     * within [tol] — is the method, and it is written down once above.
     */
    internal fun fittedChain3(
        tol: Double,
        at: (Double) -> Vec3?,
    ): Pair<List<Curve3Element>, Double>? {
        val d = 1e-4

        fun slope(u: Double): Vec3? {
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
        var best: Pair<List<Curve3Element>, Double>? = null
        repeat(9) {
            val pts = (0..n).map { at(it.toDouble() / n) ?: return@repeat }
            val ms = (0..n).map { slope(it.toDouble() / n) ?: return@repeat }
            val chain =
                (0 until n).map {
                    Curve3Element.Bezier3(
                        pts[it],
                        pts[it] + ms[it] * (1.0 / (3.0 * n)),
                        pts[it + 1] - ms[it + 1] * (1.0 / (3.0 * n)),
                        pts[it + 1],
                    )
                }
            var worst = 0.0
            for (k in chain.indices) {
                val b = chain[k]
                val mid = (b.p0 + b.p1 * 3.0 + b.p2 * 3.0 + b.p3) * (1.0 / 8.0)
                val exact = at((k + 0.5) / n) ?: continue
                worst = max(worst, (mid - exact).length())
            }
            best = chain to worst
            if (worst <= tol) return chain to worst
            n *= 2
        }
        return best
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
     *
     * **It hands back the tolerance it *reached*, not the one it was asked for** (OP-31, slice 5a). A curve
     * with a kink in it — the station where a band's trim hands over from a neighbour's arc to that
     * neighbour's own leg — cannot be met to any tolerance by a chain of smooth cubics, and halving nine
     * times leaves the spans either side of the kink standing whatever they stand. Claiming the asked-for
     * number there is exactly the dishonesty [FacePatch.fitted] exists to prevent, so the measured worst is
     * what every reader of the value is given.
     */
    internal fun fittedChain(
        tol: Double,
        at: (Double) -> Vec2?,
    ): Pair<List<ProfileElement>, Double>? {
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
        var best: Pair<List<ProfileElement>, Double>? = null
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
            var worst = 0.0
            for (k in chain.indices) {
                val b = chain[k].bezier
                val mid = (b.p0 + b.p1 * 3.0 + b.p2 * 3.0 + b.p3) * (1.0 / 8.0)
                val exact = at((k + 0.5) / n) ?: continue
                worst = max(worst, (mid - exact).length())
            }
            best = chain to worst
            if (worst <= tol) return chain to worst
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

    /**
     * A section polygon read as a **ring starting at the wedge's own corner** and running counter-clockwise
     * — the one form the ledge's two nested profiles have to be in before one can be cut out of the other.
     *
     * The corner is the point of the section nearest its own origin (it *is* the origin, up to the step-off
     * this reading never sees), and the winding is stated rather than assumed because the two profiles are
     * read through two different placements of the same plane, either of which may be a reflection of it.
     */
    private fun ringFromCorner(poly: List<Vec2>): List<Vec2> {
        val distinct = ArrayList<Vec2>(poly.size)
        for (q in poly) if (distinct.isEmpty() || (q - distinct.last()).length() > Geom3.WELD_TOL) distinct.add(q)
        while (distinct.size > 1 && (distinct.first() - distinct.last()).length() <= Geom3.WELD_TOL) distinct.removeAt(distinct.size - 1)
        if (distinct.size < 3) return emptyList()
        val corner = distinct.indices.minByOrNull { distinct[it].length() } ?: 0
        val rotated = distinct.drop(corner) + distinct.take(corner)
        var twice = 0.0
        for (k in rotated.indices) {
            val q = rotated[k]
            val r = rotated[(k + 1) % rotated.size]
            twice += q.x * r.y - r.x * q.y
        }
        return if (twice >= 0.0) rotated else reversedFromFirst(rotated)
    }

    /**
     * The **annulus between two nested rings**, stitched by the angle each vertex stands at about their
     * common corner — one closed strip, every edge of both rings used exactly once.
     *
     * Both rings start at that corner and run counter-clockwise, and the angle is monotone along each
     * (a wedge is star-shaped from its own corner, and its two legs are the plateaus at either end), so
     * merging the two by angle gives a strip whose quads are radial and cannot cross. The winding is
     * stated: the rings are counter-clockwise about the plane's own normal, the tool stands on the *other*
     * side of that plane, so every triangle is emitted the other way round.
     */
    private fun stitchRings(
        out: Geom3.MeshBuilder,
        outer: List<Vec2>,
        inner: List<Vec2>,
        outerAt: (Vec2) -> Vec3,
        innerAt: (Vec2) -> Vec3,
    ) {
        val ref = atan2(outer[1].y, outer[1].x)

        fun keys(ring: List<Vec2>): List<Double> =
            ring.indices.map { k ->
                if (k == 0) {
                    -1.0
                } else {
                    val a = atan2(ring[k].y, ring[k].x) - ref
                    if (a < -1e-9) a + 2.0 * PI else a
                }
            } + listOf(3.0 * PI)
        val ko = keys(outer)
        val ki = keys(inner)
        val o = outer.map(outerAt) + listOf(outerAt(outer.first()))
        val n = inner.map(innerAt) + listOf(innerAt(inner.first()))
        var i = 0
        var j = 0
        while (i < o.size - 1 || j < n.size - 1) {
            if (j >= n.size - 1 || (i < o.size - 1 && ko[i + 1] <= ki[j + 1])) {
                out.triangle(o[i + 1], o[i], n[j])
                i++
            } else {
                out.triangle(n[j + 1], o[i], n[j])
                j++
            }
        }
    }

    /** A placement's own `(x, y)` read in [plane]'s — two orthonormal frames of one plane, so an [Affine]. */
    private fun placeMap(
        place: Placement,
        plane: Plane3,
    ): Affine {
        val o = place.origin - plane.origin
        return Affine(
            place.cx.dot(plane.u),
            place.cx.dot(plane.v),
            place.cy.dot(plane.u),
            place.cy.dot(plane.v),
            o.dot(plane.u),
            o.dot(plane.v),
        )
    }

    /** One side of a ledge: where the section meets the shared face, where it meets the other, and its curve between. */
    private class LedgeSide(val onShared: Vec2, val onOther: Vec2, val curve: List<ProfileElement>)

    /**
     * [piece]'s own section at the ledge, in [plane]'s frame and always running **from the shared face to
     * the other one** — so the two sides of a ledge are read the same way round whichever face each edge
     * happens to call its first.
     */
    private fun ledgeSide(
        piece: Piece,
        shared: FacePatch,
        place: Placement,
        plane: Plane3,
    ): LedgeSide {
        val map = placeMap(place, plane)
        val onFace1 = shared.name == piece.crease.face1.name
        val curve = piece.wedge.pieces.map { GeomMath.transform(it, map) }
        return LedgeSide(
            map.apply(if (onFace1) piece.wedge.t1 else piece.wedge.t2),
            map.apply(if (onFace1) piece.wedge.t2 else piece.wedge.t1),
            if (onFace1) curve else curve.reversed().map { GeomMath.reverse(it) },
        )
    }

    /**
     * **The ledge as a face**: the piece of the landing plane between the walk's arrival section and the
     * other band's own end section, stated exactly.
     *
     * Four pieces and every one of them is in this drawing's own vocabulary: the deeper section's blend
     * curve (a circle where a plane cuts a horn torus through its axis, a straight line where it cuts a
     * cone), a segment along the other face, the shallower section's curve run back, and a segment along the
     * shared face. Nothing here is fitted, and the reason is worth stating: the landing plane **contains**
     * the pivot's own axis, which is the one family of plane sections a torus answers with a circle.
     */
    private fun ledgeFace(
        pieces: List<Piece>,
        c: Ledge,
        name: FaceName,
    ): FacePatch {
        val plane = c.plane()
        // both sides read at the **landing plane itself**, not at the micron the tool's own walk stops
        // short of it: the drawing states the body, the step-off is the tool's business ([Ledge.landing])
        val outer = ledgeSide(pieces[c.a], c.shared, c.rings.last(), plane)
        val inner = ledgeSide(pieces[c.b], c.shared, c.placeB, plane)
        if (outer.curve.isEmpty() || inner.curve.isEmpty()) {
            return FacePatch(name, plane, emptyList(), Msgs.refusalBlendSweepsPieceProfileThisDrawing(name = name.label))
        }
        val ring =
            (
                outer.curve +
                    listOf(ProfileElement.Seg(Segment(outer.onOther, inner.onOther))) +
                    inner.curve.reversed().map { GeomMath.reverse(it) } +
                    listOf(ProfileElement.Seg(Segment(inner.onShared, outer.onShared)))
            )
                // …and a leg the two sections **share the whole of** contributes no piece at all: a bevel
                // beside a round of its own setback meets it at both tangencies, and the ledge between them
                // is a lune with two corners rather than four
                .filter { (GeomMath.endOf(it) - GeomMath.startOf(it)).length() > Geom3.WELD_TOL }
        if (ring.size < 2) {
            return FacePatch(name, plane, emptyList(), Msgs.refusalBlendHasNoLength(name = name.label))
        }
        val ccw = if (GeomMath.signedArea(Loop(ring)) >= 0.0) ring else ring.reversed().map { GeomMath.reverse(it) }
        return FacePatch(name, plane, ccw, null)
    }

    /**
     * Whether [outer]'s wedge **contains** [inner]'s, where the two stand at the same corner on the same two
     * legs — the one question that decides which of two unlike roundings travels round an inside corner
     * ([Ledge]), and a structural one rather than a measurement (OP-21).
     *
     * Two of the three answers are true at **any** dihedral, which is why they are the ones stated. Two
     * sections of the *same kind* nest by their size: a larger ball's arc stands further from the corner at
     * every depth, and a larger bevel's chord does too. A **bevel** contains a *round* whose setback is no
     * larger, because the bevel of that very setback **is** the round's own chord and the arc bulges toward
     * the corner from it. What is deliberately not stated is the fourth: a round wide enough contains a
     * bevel (at a right angle, from `r ≥ (2 + √2)c/2`), and the same condition at a general dihedral is a
     * different expression — so that pair is left to the refusal, and named there.
     */
    private fun sectionContains(
        outer: Piece,
        inner: Piece,
        shared: FacePatch,
    ): Boolean {
        if (outer.sec.kind == BlendKind.PROFILE || inner.sec.kind == BlendKind.PROFILE) return false
        val so = setbackOn(outer, shared)
        val si = setbackOn(inner, shared)
        if (so == null || si == null) return false
        if (outer.sec.kind == inner.sec.kind) return so >= si - Geom3.WELD_TOL
        return outer.sec.kind == BlendKind.CHAMFER && so >= si - Geom3.WELD_TOL
    }

    /** How far [piece]'s own tangency stands from the crease **in** [shared] — its setback there. */
    private fun setbackOn(
        piece: Piece,
        shared: FacePatch,
    ): Double? =
        when (shared.name) {
            piece.crease.face1.name -> piece.wedge.t1.length()
            piece.crease.face2.name -> piece.wedge.t2.length()
            else -> null
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

        /**
         * The point three **bevels** come to here, or null where the fill is a ball (OP-31, slice 5d) —
         * what each of the three bands runs on to, in place of ending square across ([bandToItsCorners]).
         */
        fun apex(pieces: List<Piece>): Vec3? = if (ball != null) null else apexOf(pieces, members)

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
        val seg = piece.seg
        if (seg != null) return (if (atStart) seg.end - seg.start else seg.start - seg.end).normalized()
        // **a curved crease leaves along its own tangent** (OP-31, slice 5e) — the same statement one
        // carrier along, and the only one a corner ever asks of a run's end.
        val els = piece.crease.path.elements
        val el = if (atStart) els.first() else els.last()
        val t = Curves3.tangentAt(el, if (atStart) 0.0 else 1.0) ?: return Vec3.X
        return (if (atStart) t else t * -1.0).normalized()
    }

    /**
     * Where [piece]'s crease ends, as the corner-finding pass asks it — the segment's own end, or the arc's.
     */
    private fun endPointOf(
        piece: Piece,
        atStart: Boolean,
    ): Vec3? {
        val seg = piece.seg
        if (seg != null) return if (atStart) seg.start else seg.end
        val els = piece.crease.path.elements
        if (els.isEmpty()) return null
        return Frames3.pointAt(if (atStart) els.first() else els.last(), if (atStart) 0.0 else 1.0)
    }

    /**
     * Where [piece]'s own section stands [s] along its crease from the start — the frame every ring of its
     * tube is placed in (OP-31, slice 5e).
     *
     * *The frame is the crease's own, station by station.* [creaseOf] states it as `(e1, t × e1)` with `e1`
     * constant along the run, and for a **straight** crease that second axis is constant too — which is
     * exactly `ref.e2`, so this is character for character the expression every sweep along a segment made
     * before this slice and no straight band's triangles move. For a **circular** one it turns with the
     * tangent, which is the whole of what makes the tube a revolution rather than a prism.
     */
    private fun placeAt(
        piece: Piece,
        s: Double,
    ): Placement? {
        val crease = piece.crease
        val seg = piece.seg
        if (seg != null) {
            val v = seg.end - seg.start
            if (v.length() <= Geom3.WELD_TOL) return null
            return Placement(seg.start + v.normalized() * s, crease.e1, crease.ref.e2)
        }
        val arc = soleElement(crease) as? Curve3Element.Arc3 ?: return null
        if (arc.arcLength <= Geom3.WELD_TOL) return null
        val t = s / arc.arcLength
        val tangent = arc.tangentAt(t)
        if (tangent.length() <= Vec3.EPS) return null
        val e2 = tangent.normalized().cross(crease.e1)
        if (e2.length() <= Vec3.EPS) return null
        return Placement(arc.at(t), crease.e1, e2.normalized())
    }

    /** The section frame at one end of [piece]'s own run — [placeAt] at the run's own two stations. */
    private fun endPlacement(
        piece: Piece,
        atStart: Boolean,
    ): Placement? = placeAt(piece, if (atStart) 0.0 else piece.length)

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
        val apex = apexOf(pieces, members) ?: return null
        return loop.indices.map { Triple(apex, loop[it], loop[(it + 1) % loop.size]) }
    }

    /**
     * **Where three bevels meeting at a vertex come to a point** — the meeting of their own three planes,
     * and the one number the whole apex is (OP-31, item 3; read for the face list in slice 5d).
     *
     * Each bevel's plane is stated from the section it carries at the vertex station and the direction its
     * run leaves in, so it *is* the band's own face and not a plane derived beside it. That is why the apex
     * needs no face of its own: the three triangles [apexPatch] fans from it lie one in each of those three
     * planes, edge to edge with the band whose plane it is, so the apex is the three **bands** running on to
     * a point ([bandToItsCorners]) rather than a fourth surface.
     */
    private fun apexOf(
        pieces: List<Piece>,
        members: List<Triple<Int, Boolean, Placement>>,
    ): Vec3? {
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
        return meetOfPlanes(planes)
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
    ): Vec3? = inFaceOf(piece, shared, Placement(Vec3.ZERO, piece.crease.e1, piece.crease.ref.e2))

    /** The same, read in the frame [place] — a curved crease's own frame turns along its run (slice 5e). */
    private fun inFaceOf(
        piece: Piece,
        shared: FacePatch,
        place: Placement,
    ): Vec3? {
        val t = if (shared.name == piece.crease.face1.name) piece.wedge.t1 else piece.wedge.t2
        val w = place.cx * t.x + place.cy * t.y
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
        val seg = piece.seg
        if (seg != null) {
            val v = seg.end - seg.start
            return (p - seg.start).dot(v) / v.length()
        }
        // **and along a circular crease it is the arc length**, read from the arc's own angle (slice 5e)
        val arc = soleElement(piece.crease) as? Curve3Element.Arc3 ?: return 0.0
        val rel = p - arc.center
        // the angle is read **about the run's own middle**, so that a whole turn's worth of arc still has
        // one answer per point and a point a hair before the start reads as a hair before it
        val half = arc.sweepAngle / 2.0
        var d = atan2(rel.dot(arc.v), rel.dot(arc.u)) - arc.startAngle - half
        while (d <= -PI) d += 2.0 * PI
        while (d > PI) d -= 2.0 * PI
        return (d + half) * arc.radius * (if (arc.sweepAngle >= 0.0) 1.0 else -1.0)
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
                    if (three.any { pieces[it].seg == null && soleElement(pieces[it].crease) == null }) continue
                    if (three.any { pieces[it].choice.convex != pieces[three[0]].choice.convex }) continue
                    val (trio, at) = endsMeeting(pieces, three, taken) ?: continue
                    val vertex = vertexOf(pieces, trio, at)
                    if (vertex == null) {
                        // **a curved edge among the three is a whole case, and it is refused by name**
                        // (OP-31, slice 5e). The ball still stands still there and its patch is still the
                        // spherical triangle between the three band ends — but the three stations are
                        // solved by crossing two tangency *lines* on each shared face ([vertexOf]), and a
                        // curved face puts those tangencies on a curve instead. Left alone the three tubes
                        // butt and the boolean answers with a tangent contact rather than a body, so it is
                        // named rather than silently broken (OP-3, and the watertight-or-refused rule).
                        if (three.any { pieces[it].seg == null } && refusal == null) {
                            refusal =
                                Msgs.refusalBlendCurvedVertexNotStated(
                                    name = pieces[three[0]].crease.edge.name.label,
                                    name2 = pieces[three[1]].crease.edge.name.label,
                                    name3 = pieces[three[2]].crease.edge.name.label,
                                )
                        }
                        continue
                    }
                    out.add(vertex)
                    taken.addAll(vertex.ends)
                }
            }
        }
        for (i in pieces.indices) {
            for (j in i + 1 until pieces.size) {
                val a = pieces[i]
                val b = pieces[j]
                // **a circular crease is a corner participant too** (OP-31, slice 5e). What it cannot be is
                // one side of a *mitre*: the surface equidistant from a straight edge and a curved one is a
                // curved medial one and not a plane, which is session 79's cut (1). At an **inside** corner
                // there is no equidistant surface to find at all — the two bands never overlap, and what
                // stands between them is the ball's own pivot about the upright, which is stated from the
                // two sections and the corner and asks nothing of the runs but their ends and directions.
                val curved = a.seg == null || b.seg == null
                if (curved && (soleElement(a.crease) == null || soleElement(b.crease) == null)) continue
                if (a.choice.convex != b.choice.convex) continue
                val shared =
                    listOf(a.crease.face1, a.crease.face2)
                        .firstOrNull { f -> f.plane != null && (f.name == b.crease.face1.name || f.name == b.crease.face2.name) }
                        ?: continue
                for (aAtStart in listOf(true, false)) {
                    for (bAtStart in listOf(true, false)) {
                        if ((i to aAtStart) in taken || (j to bAtStart) in taken) continue
                        val corner = endPointOf(a, aAtStart) ?: continue
                        if ((corner - (endPointOf(b, bAtStart) ?: continue)).length() > RING_TOL) continue
                        val frameA = endPlacement(a, aAtStart) ?: continue
                        val frameB = endPlacement(b, bAtStart) ?: continue
                        val ea = inFaceOf(a, shared, frameA) ?: continue
                        val eb = inFaceOf(b, shared, frameB) ?: continue
                        // a smooth hand-over is not a corner: the two sections already abut on one plane
                        if (ea.dot(eb) >= 1.0 - TANGENT_TOL) continue
                        val sum = ea + eb
                        if (sum.length() <= Geom3.WELD_TOL) continue
                        val bis = sum.normalized()
                        val c = ea.dot(bis)
                        if (c <= Geom3.WELD_TOL) continue
                        if (curved) {
                            // the convex one is left to overlap and be trimmed exactly as it always was —
                            // a correct body whose crease this slice names ([runInEdges]) rather than mitres
                            if (turnsInward(a, aAtStart, bis) && turnsInward(b, bAtStart, bis)) continue
                            val (turn, why) = turnOf(pieces, i, aAtStart, j, bAtStart, shared, corner, ea, eb)
                            if (why != null && refusal == null) refusal = why
                            out.add(turn ?: continue)
                            taken.add(i to aAtStart)
                            taken.add(j to bAtStart)
                            continue
                        }
                        // **the pivot about a slanted or a ring upright** (OP-31, slice 5h): a canal
                        // between two band ends, whose spine is set by the shared face and the upright and
                        // whose section is closed by the shared face and the **near** one of the pair's two
                        // other faces. It answers *neither* where the upright is one straight run square to
                        // the face — there session 80's exact circle is the pivot and nothing has changed.
                        if (!(turnsInward(a, aAtStart, bis) && turnsInward(b, bAtStart, bis))) {
                            val (pivoted, whyPivot) = canalTurnOf(pieces, i, aAtStart, j, bAtStart, shared, corner)
                            if (pivoted != null) {
                                out.add(pivoted)
                                taken.add(i to aAtStart)
                                taken.add(j to bAtStart)
                                continue
                            }
                            if (whyPivot != null) {
                                if (refusal == null) refusal = whyPivot
                                continue
                            }
                        }
                        val placeA = mitrePlacement(a, shared, corner, bis, c) ?: continue
                        val placeB = mitrePlacement(b, shared, corner, bis, c) ?: continue
                        if (!ringsAgree(a.grown.map { placeA.at(it) }, b.grown.map { placeB.at(it) })) {
                            // **the incongruent corner.** Two wedges that are not congruent in the face they
                            // share land on no common ring. At a **convex** corner that costs nothing — the
                            // two tools overlap and the boolean trims them exactly, which is session 79's
                            // cut (2) and stays. At an **inside** corner they never overlap at all, and
                            // leaving the pair alone leaves GitHub #31's spike standing between the two band
                            // ends: so the deeper of the two pivots about the upright and lands in the
                            // other's own end plane, with a ledge between them ([Ledge], OP-31 slice 5a).
                            // Where neither section contains the other, or the upright is itself a band,
                            // that walk cannot be stated and the pair is **refused by name** with its cure.
                            if (turnsInward(a, aAtStart, bis) && turnsInward(b, bAtStart, bis)) continue
                            val (ledge, whyLedge) = ledgeOf(pieces, i, aAtStart, j, bAtStart, shared, corner, ea, eb)
                            if (ledge == null) {
                                if (whyLedge != null && refusal == null) refusal = whyLedge
                                continue
                            }
                            out.add(ledge)
                            taken.add(i to aAtStart)
                            taken.add(j to bAtStart)
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
        ): Vec3? = endPointOf(pieces[i], atStart)
        for (e0 in listOf(true, false)) {
            for (e1 in listOf(true, false)) {
                for (e2 in listOf(true, false)) {
                    val ends = listOf(which[0] to e0, which[1] to e1, which[2] to e2)
                    if (ends.any { it in taken }) continue
                    val at = endOf(which[0], e0) ?: continue
                    if (ends.all { ((endOf(it.first, it.second) ?: return@all false) - at).length() <= RING_TOL }) return ends to at
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
            // — read in each run's own frame *at that end*, which for a straight crease is the constant one
            // it always was and for a circular one turns with the tangent (OP-31, slice 5e).
            //
            // …and the pivot is a **circle** about that upright exactly when the upright is one straight run
            // square to the shared face, which is the same thing as *both other faces containing the axis
            // through the corner along the shared face's own normal* ([axisLiesIn]). That is what session 81
            // parked as the slanted and the ring upright, and slice 5e's own finding is that it is also
            // session 79's cut (2): where the upright is square and sharp the two wedges are **congruent by
            // construction** — each one's plane is the meridian plane through the axis, in which the shared
            // face cuts a line square to it and the other face, containing the whole axis, cuts the axis
            // itself — so two roundings of one size and kind can differ there only if the upright does not
            // stand square, and the incongruent inside corner is precisely this refusal or slice 5a's ledge.
            uprightRefusal(a, b, shared, n)?.let { return null to it }
            val placeA = endPlacement(a, aAtStart) ?: return null to null
            val placeB = endPlacement(b, bAtStart) ?: return null to null
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
     * Why a pair cannot pivot about the **sharp** upright between them, or null where it can (OP-31, slice
     * 5e — session 81's two parked uprights, reached at last).
     *
     * The ball at an inside corner stays tangent to the shared face, so its centre stands on that face's own
     * offset plane; having reached the end of its edge it turns until it is tangent to the neighbour's face,
     * touching the upright throughout, so its centre also stands at `r` from the **upright**. Where the
     * upright is one straight run square to the shared face those two conditions are a plane and a cylinder
     * about an axis *normal* to it, which meet in a **circle** — session 80's pivot, exact. Where the
     * upright is slanted the same two meet in an **ellipse**, and the corner is a swept sphere along it: a
     * canal surface, which is the very construction slice (5f) owes the elliptical mitre and which this
     * drawing has no carrier for. Where the upright is a **ring** — a revolve's circular edge at an inside
     * corner of its cap — the centre's locus is a plane against a *torus*, a spiric quartic, and not even
     * (5f)'s ellipse.
     *
     * The question is asked of the two faces rather than hunted for among the edges, and that is what makes
     * it structural: the upright *is* where the pair's two other faces cross, so it is one straight run
     * square to the shared face exactly when each of those faces **contains the whole axis** through the
     * corner along the shared face's normal.
     */
    private fun uprightRefusal(
        a: Piece,
        b: Piece,
        shared: FacePatch,
        n: Vec3,
    ): Msg? {
        val fa = otherFace(a, shared) ?: return null
        val fb = otherFace(b, shared) ?: return null
        if (axisLiesIn(fa, n) && axisLiesIn(fb, n)) return null
        return Msgs.refusalBlendSharpUprightIsNotStraightSquare(
            what = Msgs.refusalBlendInsideCornerWhereMeets(name = a.crease.edge.name.label, name2 = b.crease.edge.name.label, name3 = shared.name.label),
            name = fa.name.label,
            name2 = fb.name.label,
            name3 = shared.name.label,
        )
    }

    /**
     * Whether the straight line along [n] through any point of [face] lies **in** [face]'s own surface — a
     * plane holds it when its normal is square to it, a cylinder when it is one of its own rulings, and no
     * other surface this drawing names holds a straight line at all (a cone's rulings all meet its apex, so
     * a family of them square to one face is not a family a corner can stand in).
     */
    private fun axisLiesIn(
        face: FacePatch,
        n: Vec3,
    ): Boolean {
        face.plane?.let { return abs(it.normal.normalized().dot(n)) <= TANGENT_TOL }
        val s = face.surface ?: return false
        return s.band is Revolve3.Band.Cylinder && abs(s.axis.normalized().dot(n)) >= 1.0 - TANGENT_TOL
    }

    /**
     * The **ledge** two unlike roundings leave at an inside corner ([Ledge], OP-31 slice 5a) — or the reason
     * this pair cannot have one, which is the same sentence the pair was refused with before the slice.
     *
     * Three conditions, each of them a *class* and not a case, and each named rather than silently skipped:
     * the upright must be **sharp** (where it is itself a band the walk follows that band's own curve, and
     * two unlike sections cannot both stand on it — session 81's pivot, unchanged); one of the two sections
     * must **contain** the other ([sectionContains]), since only then is the union of the two balls' pivots
     * a single surface this drawing can state; and the two must land on the **same two legs**, which is what
     * says the containment the sizes claim is really a containment in space.
     */

    private fun ledgeOf(
        pieces: List<Piece>,
        i: Int,
        aAtStart: Boolean,
        j: Int,
        bAtStart: Boolean,
        shared: FacePatch,
        at: Vec3,
        ea: Vec3,
        eb: Vec3,
    ): Pair<Ledge?, Msg?> {
        val why =
            Msgs.refusalBlendInsideCornerNotCongruent(
                name = pieces[i].crease.edge.name.label,
                name2 = pieces[j].crease.edge.name.label,
                name3 = shared.name.label,
            )
        val n = shared.plane?.normal?.normalized() ?: return null to null
        // **the upright is asked about first, because it is the deeper reason** (OP-31, slice 5e). Where the
        // upright is slanted or is a ring the two wedges are *bound* to differ — each one's plane is the
        // plane square to its own edge, and only an upright square to the shared face puts both of those
        // through one axis — so saying *"they are not congruent"* there names the symptom and not the cause,
        // and the cure it offers (give both edges the same rounding) would not work.
        uprightRefusal(pieces[i], pieces[j], shared, n)?.let { return null to it }
        if (uprightAt(pieces, i, j, shared, at) != null) return null to why
        val deep =
            when {
                sectionContains(pieces[i], pieces[j], shared) -> i
                sectionContains(pieces[j], pieces[i], shared) -> j
                else -> return null to why
            }
        val shallow = if (deep == i) j else i
        val deepAtStart = if (deep == i) aAtStart else bAtStart
        val shallowAtStart = if (deep == i) bAtStart else aAtStart
        val eDeep = if (deep == i) ea else eb
        val eShallow = if (deep == i) eb else ea
        val total = signedTurn(eDeep, eShallow, n)
        if (abs(total) <= TANGENT_TOL) return null to null
        if (pieces[deep].grown.maxOf { it.length() } <= Geom3.WELD_TOL) return null to null
        val placeDeep = Placement(at, pieces[deep].crease.e1, pieces[deep].crease.ref.e2)
        val seg = pieces[shallow].seg ?: return null to why
        val out = (if (shallowAtStart) seg.end else seg.start) - at
        if (out.length() <= Geom3.WELD_TOL) return null to why
        val away = out.normalized()
        val placeShallow = Placement(at, pieces[shallow].crease.e1, pieces[shallow].crease.ref.e2)
        val leg = turnLeg(pieces[deep], placeDeep, at, n, eDeep, total)
        val landed = otherLeg(pieces[deep], shared, leg.rings.last()) ?: return null to why
        val standing = otherLeg(pieces[shallow], shared, placeShallow) ?: return null to why
        if (landed.dot(standing) < 1.0 - TANGENT_TOL) return null to why
        return Ledge(
            deep,
            deepAtStart,
            placeDeep,
            shallow,
            shallowAtStart,
            placeShallow,
            shared,
            listOf(leg),
            at,
            away,
        ) to null
    }

    /** The way [piece]'s section reaches into the face that is **not** [shared], placed by [place]. */
    private fun otherLeg(
        piece: Piece,
        shared: FacePatch,
        place: Placement,
    ): Vec3? {
        val t = if (shared.name == piece.crease.face1.name) piece.wedge.t2 else piece.wedge.t1
        val v = place.at(t) - place.origin
        return if (v.length() <= Geom3.WELD_TOL) null else v.normalized()
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
        if (piece.seg == null && soleElement(piece.crease) == null) return false
        return bis.dot(outOf(piece, atStart)) > 1e-9
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
     * The stations a tube puts down **between** its two end rings — none along a straight run, and the arc's
     * own chorded steps along a circular one (OP-31, slice 5e).
     *
     * The sag rule is [GeomMath.chordSteps] over the reach the section actually has, which is the same rule
     * [turnLeg] steps a pivot by and the same one `Geom3.revolve` would have stepped this very band by had
     * it stood alone: a group of one is a revolution and states its surface exactly, and a group of several
     * is one stitched shell whose arcs are chords — OP-15's approximated class, and what every band here is.
     */
    private fun tubeStations(
        piece: Piece,
        p0: Placement,
        p1: Placement,
    ): List<Placement> {
        val arc = soleElement(piece.crease) as? Curve3Element.Arc3 ?: return emptyList()
        if (arc.arcLength <= Geom3.WELD_TOL) return emptyList()
        val from = stationOf(piece, p0.origin)
        val to = stationOf(piece, p1.origin)
        val span = abs(to - from) / arc.radius
        val reach = piece.grown.maxOf { it.length() } + arc.radius
        val steps = max(1, GeomMath.chordSteps(max(reach, Geom3.WELD_TOL), span, GeomMath.TESS_TOL_MM))
        if (steps <= 1) return emptyList()
        return (1 until steps).mapNotNull { l -> placeAt(piece, from + (to - from) * l / steps) }
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
        for (c in corners) if (c.onAxis) pivots.addAll(c.ends)
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
            // **a circular run has a tube here too** (OP-31, slice 5e): its section is carried along its own
            // arc rather than between two rings, which is the same statement one carrier along.
            if (soleElement(piece.crease) == null) return null to Msgs.refusalBlendIsNotOneStraightRun2(name = piece.crease.edge.name.label)
            val atStart = rings[at to true]
            val atEnd = rings[at to false]
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
            val p0 = atStart ?: (placeAt(piece, back0) ?: return null to Msgs.refusalBlendIsNotOneStraightRun2(name = piece.crease.edge.name.label))
            val p1 = atEnd ?: (placeAt(piece, piece.length - back1) ?: return null to Msgs.refusalBlendIsNotOneStraightRun2(name = piece.crease.edge.name.label))
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
            // **the stations between the two rings, and a straight run has none** — so its two rings are
            // its whole tube and its triangles are the very triangles it always had. A **circular** run's
            // tube is its section carried round its own arc, stepped by the sag rule every band here is
            // stepped by (OP-31, slice 5e).
            val mid = tubeStations(piece, p0, p1)
            var lo = s0.map { p0.at(it) }
            for ((k, place) in (mid + listOf(p1)).withIndex()) {
                val sec = if (k == mid.size) s1 else piece.grown
                val hi = sec.map { place.at(it) }
                for (m in piece.grown.indices) {
                    val n = (m + 1) % piece.grown.size
                    b.triangle(lo[m], lo[n], hi[n])
                    b.triangle(lo[m], hi[n], hi[m])
                }
                lo = hi
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
            if (crease == null) {
                // **a crease with no rigid section is scored as a canal's** (OP-31, slice 5f) — the material
                // side of each face and the one reading that says subtract or add, both stored as signs
                val (canal, whyCanal) = canalChoice(feature, base.mesh, edge, sec) ?: return null to why
                if (canal == null) return null to (whyCanal ?: why)
                out.add(canal)
                continue
            }
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
        // **the canal bands of this pass** (OP-31, slice 5f) — a ball along a crease whose section changes,
        // which is a tool of its own and takes part in no corner: it is applied after the catalogue's groups,
        // in the step's own order, and where another rounding meets it the boolean trims the two.
        val canals = ArrayList<Canal>()
        for ((k, i) in targets.withIndex()) {
            if (k in absent) continue
            val sec = sections[k]
            val edge = edges.getOrNull(i) ?: return null to Msgs.refusalBlendThisSolidHasNoEdge3(i = i + 1, count = edges.size)
            val choice = choices[k]
            val (crease, why) = creaseOf(feature, edge)
            if (crease == null) {
                val (canal, whyCanal) = canalOf(feature, edge, i, sec, choice) ?: return null to why
                if (canal == null) return null to (whyCanal ?: why)
                canals.add(canal)
                continue
            }
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

        // …and the **pivots** of this pass, each its own tool and its own boolean too (OP-31, slice 5h):
        // a canal corner's section changes from one band end to the other, so it carries neither band's
        // point count and is applied beside the group's shell rather than stitched into it.
        var turns: List<CanalTurn> = emptyList()

        // …and the canal bands of this pass, each its own tool and its own boolean (OP-31, slice 5f)
        fun applyCanals(body: Solid3): Pair<Solid3?, Msg?> {
            var result = body
            for (canal in canals) {
                val (tool, whyTool) = canalTool(canal)
                if (tool == null) {
                    return null to Msgs.refusalQualified(name = canal.edge.name.label, reason = whyTool ?: Msgs.refusalBlendCannotBeSweptAlongIt())
                }
                val (next, whyBool) = Geom3.combine(if (canal.convex) BoolOp.SUBTRACT else BoolOp.UNION, result, tool)
                result = next ?: return null to Msgs.refusalQualified(name = canal.edge.name.label, reason = whyBool ?: Msgs.refusalBlendCannotBeAppliedToBody())
            }
            return result to null
        }
        // **A dressing whose every rounding has been removed is its own base** — nothing fresh is cut, so
        // there is no tool, no corner and no boolean, and the bands under it (if any) are already off
        // (OP-30's tombstone). Stated here rather than reached through [cornersOf], which would answer the
        // same body the long way round.
        if (pieces.isEmpty()) return applyCanals(applyTo)
        // …and the bands already under this one, so a blend of a blend on an adjacent edge builds the same
        // corner a one-gesture chain would (GitHub #27, [chainPieces]).
        pieces.addAll(chainPieces(feature))
        val found = cornersOf(pieces)
        found.refusal?.let { return null to it }
        val corners = found.list
        turns = corners.filterIsInstance<CanalTurn>()
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

        // **the pivots are cut first**, before a single band is (OP-31, slice 5h). A pivot's section changes
        // from one band end to the other, so it carries neither band's point count and cannot be stitched
        // into the group's tube shell; and cut *after* the bands it would meet the body along the very band
        // surface they have just left, arc for arc, which is the coincident pair of faces [sectionOf]'s rule
        // exists to abolish. Cut into the undressed body it crosses ordinary faces transversally, and the
        // bands that follow overlap it as two tools of one sign always may.
        fun applyTurns(body: Solid3): Pair<Solid3?, Msg?> {
            var result = body
            for (turn in turns) {
                val (tool, whyTool) = cornerTool(turn)
                if (tool == null) {
                    return null to Msgs.refusalQualified(name = turn.shared.name.label, reason = whyTool ?: Msgs.refusalBlendCannotBeSweptAlongIt())
                }
                val (next, whyBool) = Geom3.combine(if (turn.convex) BoolOp.SUBTRACT else BoolOp.UNION, result, tool)
                // **and where the boolean cannot apply the pivot's tool, the drawing says so in its own
                // words** (OP-31, slice 5o). The tool is asked about itself first and comes back a closed
                // shell; what fails at a tight ring is the *meeting*, and until session 86 what came back
                // was the engine's own mesh diagnostic under the shared face's name — *"the edge between
                // (8.657, 8.08, 0) mm and (10, 8, 0) mm is used 2 times with 2 opposite uses"* — which is
                // the one answer session 84 wrote down that this drawing may never give. The sentence
                // names the two edges the ball pivots between, the ball itself and the cure that works,
                // and carries the engine's own words inside it as evidence rather than as the reason.
                result = next ?: return null to
                    Msgs.refusalBlendPivotToolMeetsTheBody(
                        sizePhrase = turn.sec.sizePhrase(),
                        name = pieces[turn.ai].crease.edge.name.label,
                        name2 = pieces[turn.bi].crease.edge.name.label,
                        reason = whyBool ?: Msgs.refusalBlendCannotBeAppliedToBody(),
                    )
            }
            return result to null
        }

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
        // …and a **canal corner** is stale for the very same reason one about a band is (OP-31, slice 5h):
        // the pivot ends each band at the station the ball first touches the upright, which stands **short**
        // of the crease's own end wherever the upright leans. A band already cut at full length has taken
        // that tail off the body, and a further subtraction can never give it back — so the level at which
        // such a corner is *fresh* rebuilds its chain from the undressed root, exactly as session 81's
        // mixed pivot does, and the two gesture routes then reach the same body.
        val stale =
            corners.any { c ->
                (c.extra.isNotEmpty() && (c.ends.any { !pieces[it.first].existing } || c.extra.any { !pieces[it].existing })) ||
                    (c is CanalTurn && c.ends.any { !pieces[it.first].existing } && c.ends.any { pieces[it.first].existing })
            }
        if (!stale) {
            var result = applyTo
            for (group in groups) {
                // a group of nothing but bands already off the body has nothing left to cut
                if (group.all { pieces[it].existing }) continue
                val (next, why) = apply(result, group)
                result = next ?: return null to why
            }
            return applyCanals(applyTurns(result).let { (r, why) -> r ?: return null to why })
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
        return applyCanals(applyTurns(result).let { (r, why) -> r ?: return null to why })
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
        // **which way the crease's own second axis points, and it is not always outward** (OP-31, slice 5b).
        // The section is stated in the crease's `(e1, e2)`, and `e2 = t × e1` at every station — which at
        // the arc's start is `sign(sweep)·(e1·axis)` times the radial. Where that sign is negative the
        // radial is the *opposite* of the frame the section was read in, and placing it on the radial
        // mirrors the wedge: the near end's notch of a rounded rim took 0.0135 mm³ where the far end's took
        // 1.3785, the tool having been turned inside out about the crease.
        val sigma = (if (arc.sweepAngle >= 0.0) 1.0 else -1.0) * (if (along >= 0.0) 1.0 else -1.0)
        // the section's own frame at the run's start: x along the crease's upright, y along the crease's own
        // second axis — the very placement [Frame3.place] makes there, with the *exact* radial and not a chord's
        val plane = Plane3(at0, up, outward * sigma)
        // …and the axis in that plane's coordinates: the line through the centre, along the upright
        val axisOrigin = Vec2(0.0, -sigma * arc.radius)
        val axisDir = Vec2(1.0, 0.0)
        // which way the turn runs: in that frame the plane's own normal is the crease's own tangent at the
        // start, so the turn is the arc's own sweep read positively and the two signs above carry the rest
        val sweep = abs(arc.sweepAngle)
        // …and the one thing a revolve cannot do, said in the words the sweep says it in: a section that
        // reaches **past** the axis is a ball wider than the rim it runs along, and revolving it would fold
        // the shell through itself. Reaching *to* the axis is the degenerate case above and is legal — a
        // profile touching the axis is what a turned part's pole is made of ([Geom3.revolve]).
        val into = if (sigma > 0.0) -piece.grown.minOf { it.y } else piece.grown.maxOf { it.y }
        if (into > arc.radius + Geom3.WELD_TOL) {
            return null to
                Msgs.refusalBlendProfileReachBendMmIs(mm = Frames3.mm(into), mm2 = Frames3.mm(arc.radius), mm3 = Frames3.mm(0.0))
        }
        // **and the tool's own two ends are stepped, exactly as a straight band's are** (OP-31, slice 5e).
        // [endSteps] states the step as a length along the crease; here it is an angle about the same axis,
        // positive back into the run and negative out past it. A closed ring states no step and this is then
        // the very expression it always was, which is what keeps every turned part's rim bit-identical.
        val g0 = piece.backAtStart / arc.radius
        val g1 = piece.backAtEnd / arc.radius
        if (g0 == 0.0 && g1 == 0.0) return Geom3.revolve(Sketch3(plane, listOf(piece.grownRegion)), axisOrigin, axisDir, sweep)
        val grown = sweep - g0 - g1
        if (grown <= Geom3.WELD_TOL) return null to Msgs.refusalBlendHasNoLengthSoIts(name = piece.crease.edge.name.label)
        return Geom3.revolve(Sketch3(plane, listOf(piece.grownRegion)), axisOrigin, axisDir, grown, g0)
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
        /**
         * The rigid crease this entry runs along — **null for a canal band** (OP-31, slice 5f), whose
         * section changes from one end of the run to the other and whose whole statement is [canal].
         */
        val crease: Crease?,
        val wedge: Wedge?,
        val choice: BlendChoice,
        val sec: BlendSection,
        /** The canal this entry is, where its crease carries no rigid section — null for every other. */
        val canal: Canal? = null,
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
            val choice = f.choices.getOrNull(k) ?: return null to Msgs.refusalBlendThisBlendRecordedNoChoice(i = i + 1)
            val (crease, why) = creaseOf(f.base, edge)
            if (crease == null) {
                // **a crease with no rigid section is a canal band** (OP-31, slice 5f): the catalogue is
                // asked first and this is reached only where it declines, so nothing it already builds moves
                val (canal, whyCanal) = canalOf(f.base, edge, i, sec, choice) ?: return null to why
                if (canal == null) return null to (whyCanal ?: why)
                out.add(Dressing(i, edge, null, null, choice, sec, canal))
                continue
            }
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
        val whole = trimmed.faces ?: return null to trimmed.why
        // **a cap a *later* gesture closed with a corner is no face of the body** (OP-31, slice 5p). A
        // band's flat end is stated by the level that made it, and a corner may be made one gesture later
        // — the chain then ends that band short of its own crease ("stale where fresh", slice 5h) and the
        // cap the lower level stated stands where the body has nothing. Every other reading of a band's
        // extent is already asked **at the tip** ([bandOf], [spanOf]); this is that rule for the cap, and it
        // moves no slot: the face keeps its index and says who owns its end instead.
        val faces = withoutCapsACornerTook(f, whole)
        if (trimmed.notches.isEmpty()) return faces to null
        val out = ArrayList<FacePatch>(faces.size)
        for ((i, patch) in faces.withIndex()) {
            val mine = trimmed.notches.filter { it.face == i }
            if (mine.isEmpty() || patch.reason != null) {
                out.add(patch)
                continue
            }
            val blocked = mine.firstNotNullOfOrNull { it.reason }
            if (blocked != null) {
                out.add(patch.copy(reason = blocked))
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
        val corners = cornersOf(pieces).list
        val span = spanOf(pieces, at, corners, canalsOf(f))

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
        // **and a band that ends at a bevelled vertex runs on to that vertex' own apex** (OP-31, slice 5d).
        // Three bevels meeting at a convex vertex close on three planar triangles, each of them lying *in*
        // one of the three bevel planes ([apexOf]) — so the apex is no new surface and needs no face, no
        // slot and no address: it is this very band, ending in a point instead of square across. Stating it
        // as three appended corner faces was the alternative and is worse twice over — it would say one
        // plane twice, and it would move every appended slot after it (OP-30, slice 5g).
        val apexNear = apexEnd(pieces, corners, at, true)?.let { plane.toLocal(it) }
        val apexFar = apexEnd(pieces, corners, at, false)?.let { plane.toLocal(it) }

        fun endRun(
            from: Vec2,
            to: Vec2,
            apex: Vec2?,
        ): List<ProfileElement>? =
            apex?.let { listOf(ProfileElement.Seg(Segment(from, it)), ProfileElement.Seg(Segment(it, to))) }
        if (straight) {
            val ring =
                (endRun(a0, a1, apexNear) ?: listOf(ProfileElement.Seg(Segment(a0, a1)))) +
                    listOf(ProfileElement.Seg(Segment(a1, b1))) +
                    (endRun(b1, b0, apexFar) ?: listOf(ProfileElement.Seg(Segment(b1, b0)))) +
                    listOf(ProfileElement.Seg(Segment(b0, a0)))
            return patch.copy(outline = ring)
        }
        val (near, nearTol) = fittedChain(Combine3.FIT_TOL_MM) { t -> corner(t, false) } ?: return patch
        val (far, farTol) = fittedChain(Combine3.FIT_TOL_MM) { t -> corner(1.0 - t, true) } ?: return patch
        val ring =
            (endRun(a0, a1, apexNear) ?: near) + listOf(ProfileElement.Seg(Segment(a1, b1))) +
                (endRun(b1, b0, apexFar) ?: far) + listOf(ProfileElement.Seg(Segment(b0, a0)))
        return patch.copy(outline = ring, fitted = max(nearTol, farTol))
    }

    /**
     * The **apex** the vertex corner at one end of band [at] comes to, or null where that end is free, is
     * closed by any other kind of corner, or is a *round* vertex (OP-31, slice 5d).
     *
     * A round vertex needs nothing here and gets nothing: its ball is tangent to all three bands all round,
     * so each band ends on the ball's own circle and states a corner patch of its own ([Vertex.ballFace]).
     * It is only a **bevelled** one that has no surface to name, because it has none: three planes meeting
     * are the three bands themselves.
     */
    private fun apexEnd(
        pieces: List<Piece>,
        corners: List<Corner>,
        at: Int,
        atStart: Boolean,
    ): Vec3? =
        corners.filterIsInstance<Vertex>()
            .firstOrNull { (at to atStart) in it.ends }
            ?.apex(pieces)

    /** See [deriveDressedFaces]: the list with every cap a corner at this tip has claimed tombstoned. */
    private fun withoutCapsACornerTook(
        f: Feature3.Blend,
        faces: List<FacePatch>,
    ): List<FacePatch> {
        if (faces.none { it.name is FaceName.BlendCap && it.reason == null }) return faces
        val pieces = piecesOf(f) ?: return faces
        val claimed = HashSet<Pair<Int, Boolean>>()
        for (c in cornersOf(pieces).list) claimed.addAll(c.ends)
        if (claimed.isEmpty()) return faces
        return faces.map { patch ->
            val name = patch.name as? FaceName.BlendCap ?: return@map patch
            if (patch.reason != null) return@map patch
            val at = pieces.indexOfFirst { it.index == name.edge }
            if (at < 0 || (at to name.atStart) !in claimed) {
                patch
            } else {
                FacePatch(name, null, emptyList(), Msgs.refusalBlendEndClosedByACorner(name = name.label))
            }
        }
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
            // **a canal takes no strip off a face's own outline**, and the reason is what its two walls
            // are: a canal band lies between two *curved* faces, whose statement is a surface and not an
            // outline at all, so what it takes off them is stated where their own extent is — the band's
            // run, ended by the canal's rail exactly as a crossing's mitre ends it ([canalSetbacks]).
            val crease = d.crease ?: continue
            val wedge = d.wedge ?: continue
            for ((patch, t) in listOf(crease.face1 to wedge.t1, crease.face2 to wedge.t2)) {
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
                // …and a superseded corner is a slot with no surface on it at all (OP-31, slice 5l)
                out.add(if (why == null) patch else patch.copy(plane = null, outline = emptyList(), reason = why, surface = null, pipe = null, absent = true))
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
        val baseEdges = Section3.edges(f.base).first ?: emptyList()
        // **the appended faces, grouped by the entry that owns them** — the very rule the edge list states
        // once at [entryOwning], read for faces: this entry's bands, its flat-end slots, and then the corner
        // patches whose latest participating entry it is. So adding a rounding only appends, and removing
        // one leaves every slot of its own block standing with a reason.
        val pool = SlotPool(liveSharedFaces(f)) { CornerSlot.of(it.name) }
        for ((k, d) in dressings.withIndex()) {
            // **a removed rounding keeps its band slots** — a reason and no surface, exactly what a consumed
            // edge already emits, so the bands of every entry after it keep their own numbers (OP-30)
            if (d == null) {
                val why = tombstoneWords(f, k, baseEdges.getOrNull(f.targets[k]))
                // a rounding that was removed left **no surface at all** behind, so each of its slots is a
                // slot with nothing on it rather than a surface with no name (OP-31, slice 5l)
                for (piece in 0 until f.bandsAt(k)) out.add(FacePatch(FaceName.BlendBand(f.targets[k], piece), null, emptyList(), why, absent = true))
                for (slot in 0 until capSlotsAt(f, baseEdges, k)) {
                    out.add(FacePatch(FaceName.BlendCap(f.targets[k], slot == 0), null, emptyList(), why, absent = true))
                }
                for (slot in f.corners.facesAt(k)) out.add(pool.take(slot) ?: goneFace(slot))
                continue
            }
            // …each of them **bounded by the corners at its ends** (OP-31, item 3b) before the level above
            // trims its own strips off it, so the two compose: this level says how far the band runs, the
            // next says what a rounding of its rail took off it.
            out.addAll(bandPatchesOf(d).map { bandToItsCorners(f, it) })
            out.addAll(capFaces(f, baseEdges, k, d))
            // …and the corner patches this entry's block records, by the very rule the edge list states one
            // function along ([SlotPool], OP-31 slice 5g)
            for (slot in f.corners.facesAt(k)) out.add(pool.take(slot) ?: goneFace(slot))
        }
        out.addAll(pool.rest())
        return out to null
    }

    /**
     * **The flat end of every band of this level that stops in mid-air** (OP-31, slice 5b).
     *
     * A free end whose cap stands **in a face of the body** is that face's own notch and no face of its own
     * (session 81) — the cap is coincident with it and states nothing new. A free end whose cap stands in a
     * plane the body has no face in *is* a new face, and the drawing had none: a rounding along a **curved**
     * crease ends on a meridian plane, which is a face of nothing, and session 81's own cut (2) is the same
     * hole one shape over. Without it [Section3.facesAreWholeBoundary]'s claim about a dressed part is
     * false and a level section through the cap cannot close (`BlendCurvedCreaseTest`).
     *
     * The patch is exact and needs no derivation at all: the cap **is** the wedge, standing in the plane
     * square to the crease at that end, so its outline is the section's own boundary carried through one
     * rigid map. Its normal points back along the band, which at a free end is out of the material.
     *
     * **No cap is ever superseded**, and that is a fact about the catalogue rather than an omission: every
     * corner it builds is between two **straight** creases ([cornersOf]'s own first precondition), so an end
     * a cap closes can never be claimed by one and the tombstone rule a corner *patch* lives under
     * ([cornerSuperseded]) has nothing to do here. The day a corner is built on a curved crease is the day
     * this needs the same treatment.
     */
    private fun capFaces(
        f: Feature3.Blend,
        baseEdges: List<SolidEdge>,
        k: Int,
        d: Dressing,
    ): List<FacePatch> {
        val slots = capSlotsAt(f, baseEdges, k)
        if (slots == 0) return emptyList()
        // **a canal band's own two flat ends** (OP-31, slice 5f): the section standing in the plane square
        // to its spine, or the sentence that says the run tapers to nothing there
        d.canal?.let { return canalCapFaces(it) }
        val pieces = piecesOf(f)
        val at = pieces?.indexOfFirst { it.index == d.index } ?: -1
        val piece = if (pieces != null && at >= 0) pieces[at] else null
        val faces = undressedFacesOf(f)
        val claimed = HashSet<Pair<Int, Boolean>>()
        if (pieces != null) for (c in cornersOf(pieces).list) claimed.addAll(c.ends)
        val out = ArrayList<FacePatch>(slots)
        for (slot in 0 until slots) {
            val atStart = slot == 0
            val name = FaceName.BlendCap(d.index, atStart)
            val closed = piece != null && (at to atStart) in claimed
            val free = piece != null && faces != null && !closed
            val made = if (free) capPatchAt(faces!!, piece!!, atStart, name) else null
            // **and where the end is not this slot's, the slot says whose it is** (OP-31, slices 5e and 5p).
            // Three sentences, because there are three ends: a **corner** closes it and the surface there is
            // the corner's own patch; a **notch** owns it, the cap standing flush in a face the body already
            // has (a curved band that ends on a meridian plane does this, and so does every straight one that
            // meets its neighbour at a right angle); or the drawing cannot read that end at all.
            // …and the first two of those three say the slot names **no surface of the body at all**, which
            // is a different fact from *"a surface this drawing cannot name"* and is what lets a boolean
            // pass over the slot rather than refuse the whole list ([FacePatch.absent], OP-31 slice 5l).
            val (why, gone) =
                when {
                    closed -> Msgs.refusalBlendEndClosedByACorner(name = name.label) to true
                    made == null && free && standsInAFace(faces!!, piece!!, atStart) -> Msgs.refusalBlendCapStandsInAFace(name = name.label) to true
                    else -> Msgs.refusalBlendNoNotchAtThisEnd(name = name.label) to false
                }
            out.add(made ?: FacePatch(name, null, emptyList(), why, absent = gone))
        }
        return out
    }

    /** Whether one free end of [piece] stands in a face of the body — where the notch owns it (slice 5e). */
    private fun standsInAFace(
        faces: List<FacePatch>,
        piece: Piece,
        atStart: Boolean,
    ): Boolean {
        val (at, away) = endFrameOf(piece, atStart) ?: return false
        return faces.any { f ->
            f.plane?.let { p -> abs(p.normal.normalized().dot(away)) >= 1.0 - TANGENT_TOL && abs(p.distanceTo(at)) <= ON_BOUNDARY_TOL } == true
        }
    }

    /** The cap of one free end, or null where the body already has a face in that plane (the notch owns it). */
    private fun capPatchAt(
        faces: List<FacePatch>,
        piece: Piece,
        atStart: Boolean,
        name: FaceName,
    ): FacePatch? {
        val (at, away) = endFrameOf(piece, atStart) ?: return null
        // where the body already has a face in that plane the **notch** owns the end, and a face of its own
        // there would be a second statement of one surface
        if (faces.any { it.plane?.let { p -> abs(p.normal.normalized().dot(away)) >= 1.0 - TANGENT_TOL && abs(p.distanceTo(at)) <= ON_BOUNDARY_TOL } == true }) return null
        // **and the cap says how far the body's own facet may stand from it** (OP-31, slice 5l). The tube's
        // end ring is laid at station [Piece.backAtStart] / `length − backAtEnd` and not at the crease's own
        // end — [endSteps]' own micron, taken out past a free end so that the ring does not stand on a
        // vertex of the body — so the flat face the body is left with stands that micron off the plane this
        // states. The plane is the drawing's (every neighbour's outline is stepped off *it*, and the body's
        // own step is what [capSteps] splices); what the micron changes is only how near a triangle has to
        // come to be recognised as **this** face's, and a reader that asked for float32 left a bored loft's
        // own cap facet on no carrier of either operand.
        return capPatch(piece, atStart, at, away, name, abs(if (atStart) piece.backAtStart else piece.backAtEnd))
    }

    /** Where a piece's crease ends and which way it leaves there — the cap's own point and outward normal. */
    private fun endFrameOf(
        piece: Piece,
        atStart: Boolean,
    ): Pair<Vec3, Vec3>? {
        val els = piece.crease.path.elements
        if (els.isEmpty()) return null
        val el = if (atStart) els.first() else els.last()
        val t = Curves3.tangentAt(el, if (atStart) 0.0 else 1.0) ?: return null
        val at = Frames3.pointAt(el, if (atStart) 0.0 else 1.0)
        return at to (if (atStart) t * -1.0 else t).normalized()
    }

    /** The cap itself: the wedge's own boundary, in the plane square to the crease at that end. */
    private fun capPatch(
        piece: Piece,
        atStart: Boolean,
        at: Vec3,
        away: Vec3,
        name: FaceName,
        /** How far the tool's own end ring stands from this plane — see [capPatchAt]. */
        slack: Double = 0.0,
    ): FacePatch {
        val e1 = piece.crease.e1
        val e2 = away.cross(e1)
        if (e2.length() <= Vec3.EPS) return FacePatch(name, null, emptyList(), Msgs.refusalBlendDoesNotStandSquareIts(name = piece.crease.edge.name.label))
        // **the frame is stated rather than corrected afterwards.** A cap's own normal points *out of the
        // material*, and at a free end the material lies beyond the cap while the groove lies behind it —
        // so the normal runs **back along the band**, which is `−away` either way, and `−(away × e1)` is the
        // second axis that says so.
        val v = e2.normalized() * -1.0
        val plane = Plane3(at, e1, v)
        // the wedge stands in the crease's `(e1, e2)` at *this* end — the same shape at every station, which
        // is what [creaseOf] proved before it swept anything — so the far end reads it mirrored, and a
        // mirrored ring is wound the other way round
        val ring = piece.wedge.region.outer.elements
        val mapped = ring.map { GeomMath.transform(it, Affine(1.0, 0.0, 0.0, if (atStart) 1.0 else -1.0, 0.0, 0.0)) }
        return FacePatch(name, plane, if (atStart) mapped else mapped.reversed().map { GeomMath.reverse(it) }, null, slack = slack)
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
            // **a canal entry contributes no piece**, and that is not a failure to read this body: its
            // crease carries no rigid section, so it is in no corner and no band-among-bands (OP-31, slice
            // 5f). Skipping it is what lets every *other* entry's band still state its own extent.
            val crease = creaseOf(f.base, edge).first ?: if (canalPath(edge) != null) continue else return null
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
        // **a canal corner is read the way it is built** (OP-31, slice 5h) — sampled on its own chart
        if (c is CanalTurn) return canalTurnCut(c, cut)
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
    private fun cornerFacesOf(f: Feature3.Blend): List<Pair<Int, FacePatch>> {
        val pieces = piecesOf(f) ?: return emptyList()
        // …counted over the roundings that **stand**, since [piecesOf] lists this level's own pieces first
        // and a tombstone contributes none (OP-30)
        val fresh = f.standing.size
        val out = ArrayList<Pair<Int, FacePatch>>()
        for (c in cornersOf(pieces).list) {
            if (c.ends.none { it.first < fresh } && c.extra.none { it < fresh }) continue
            val edges = cornerEdges(pieces, c)
            val who = entryOwning(f, c.ends.map { it.first } + c.extra)
            for (p in c.faces(pieces) { k -> FaceName.BlendCorner(edges, k) }) out.add(who to p)
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
        // …and the free ends this level turns into **corners**: a notch curve is the crease between a band
        // and the flat cap at its free end, and an end a corner claims has no cap and no such crease
        val cornered = HashSet<Pair<Int, Boolean>>()
        if (pieces != null) for (c in corners) for ((who, atStart) in c.ends) cornered.add(pieces[who].index to atStart)
        val out = ArrayList<SolidEdge>(baseEdges.size + 2 * dressings.size)
        for ((i, e) in baseEdges.withIndex()) {
            val d = consumed[i]
            val notch = e.name as? EdgeName.BlendNotch
            if (d == null && notch != null && e.reason == null && (notch.edge to notch.atStart) in cornered) {
                out.add(e.copy(reason = Msgs.refusalBlendFreeEndNowCorner(name = e.name.label)))
                continue
            }
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
        // **the curves two or more entries make together**, gathered by the entry that owns each — the
        // latest one that takes part in it ([entryOwning], where the whole ordering rule is stated) — and
        // then handed out **by the dressing's own record** ([SlotPool], OP-31 slice 5g)
        val pool = SlotPool(liveSharedEdges(f, pieces, corners)) { CornerSlot.of(it.name) }
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
                for (slot in 0 until notchSlotsAt(f, baseEdges, k)) {
                    out.add(SolidEdge(notchNameAt(f, k, slot), EdgeGeom.Straight(at, at), FacePair(band, band), why))
                }
                for (slot in f.corners.edgesAt(k)) out.add(pool.take(slot) ?: goneEdge(slot))
                continue
            }
            // **a canal band's two rails** are the curves its ball's contact traces on the two walls,
            // fitted chains through points exact on both the sphere and the wall (OP-31, slice 5f)
            val canal = d.canal
            if (canal != null) {
                for (side in 0..1) {
                    val (geom, tol) = canalRail(canal, side)
                    val wall = (if (side == 0) canal.w1 else canal.w2).patch.name
                    out.add(
                        SolidEdge(
                            EdgeName.BlendRail(d.index, side),
                            geom ?: EdgeGeom.Straight(canal.stations.first().p1, canal.stations.first().p1),
                            FacePair(wall, d.name),
                            if (geom == null) Msgs.refusalBlendCanalSpineNotFollowed(name = d.edge.name.label) else null,
                            tol,
                        ),
                    )
                }
                for (slot in f.corners.edgesAt(k)) out.add(pool.take(slot) ?: goneEdge(slot))
                continue
            }
            val crease = d.crease ?: continue
            val wedge = d.wedge ?: continue
            val at = pieces?.indexOfFirst { it.index == d.index } ?: -1
            val span = if (pieces != null && at >= 0) spanOf(pieces, at, corners, canalsOf(f)) else null
            for (side in 0..1) {
                val face = if (side == 0) crease.face1 else crease.face2
                val t = if (side == 0) wedge.t1 else wedge.t2
                val (geom, why) = railGeom(d, t, span)
                out.add(
                    SolidEdge(
                        EdgeName.BlendRail(d.index, side),
                        geom ?: EdgeGeom.Straight(crease.ref.at, crease.ref.at),
                        FacePair(face.name, d.name),
                        why,
                    ),
                )
            }
            out.addAll(notchEdges(f, baseEdges, pieces, corners, k, d))
            // …and, at the **end of this entry's block**, the shared curves the record puts there: the one
            // it has and the tombstone it does not ([SlotPool], OP-31 slice 5g)
            for (slot in f.corners.edgesAt(k)) out.add(pool.take(slot) ?: goneEdge(slot))
        }
        // …and last, whatever the record does not know yet — appended, which disturbs nothing, and joined to
        // the last block by the next gesture that records ([sharedSlots])
        out.addAll(pool.rest())
        return out to null
    }

    /**
     * **A slot's address as a file older than [DocumentFormat.GROUPED_SLOT_VERSION] meant it** — the map a
     * load runs over a stored `signs=` (OP-18, OP-31 slice 5b).
     *
     * The appended list used to be three runs — every entry's two rails, then every corner curve, then
     * every run-in crease — and is one **block per entry** now ([entryOwning]). That is what stops a stored
     * address naming a different curve when a rounding is added to the dressing or taken off it, and it
     * moves the indices such a file already holds. So the old order is reproduced from the very producers
     * the new one uses, the slot at [old] is found by **name**, and the name is looked up where it stands
     * now. An address this cannot place is handed back unchanged rather than guessed at (OP-3).
     */
    fun addressBefore(
        feature: Feature3,
        old: Int,
    ): Int {
        val f = feature as? Feature3.Blend ?: return old
        val edges = Section3.edges(feature).first ?: return old
        val base = Section3.edges(f.base).first ?: return old
        if (old < base.size) return old
        val n = f.targets.size
        if (old < base.size + 2 * n) {
            val k = (old - base.size) / 2
            val side = (old - base.size) % 2
            val target = f.targets.getOrNull(k) ?: return old
            return edges.indexOfFirst { it.name == EdgeName.BlendRail(target, side) }.takeIf { it >= 0 } ?: old
        }
        val pieces = piecesOf(f) ?: return old
        val corners = cornersOf(pieces).list
        val flat = cornerEdgesOf(f, pieces, corners).map { it.second } + runInEdges(f, pieces, corners).map { it.second }
        val was = flat.getOrNull(old - base.size - 2 * n) ?: return old
        return edges.indexOfFirst { it.name == was.name }.takeIf { it >= 0 } ?: old
    }

    /**
     * **The face list as a file older than [DocumentFormat.CAP_SLOT_VERSION] numbered it** (OP-31, slice 5p):
     * this one, less the two flat-end slots a **straight** crease's entry did not own.
     *
     * Nothing else moved, so the old order is this one with those entries struck out — which is the whole
     * reconstruction, stated once and recursively so that a chain's every level is numbered as that file
     * numbered it. Reading it off the current list rather than rebuilding it from the producers is what
     * keeps the two orders from drifting: a slot that is a tombstone here is a tombstone there.
     */
    private fun facesBeforeCapSlots(feature: Feature3): List<FacePatch>? {
        val f = feature as? Feature3.Blend ?: return Section3.faces(feature).first
        val faces = Section3.faces(feature).first ?: return null
        val base = Section3.faces(f.base).first ?: return null
        val baseBefore = facesBeforeCapSlots(f.base) ?: return null
        val baseEdges = Section3.edges(f.base).first ?: return null
        val gone = f.targets.indices.filter { straightCrease(f, baseEdges, it) }.mapNotNull { f.targets.getOrNull(it) }.toSet()
        val appended =
            faces.drop(base.size).filter {
                val n = it.name
                !(n is FaceName.BlendCap && n.edge in gone)
            }
        return baseBefore + appended
    }

    /**
     * **A face slot's address as a file older than [DocumentFormat.CAP_SLOT_VERSION] meant it** — the map a
     * load runs over a stored whole-face pick (OP-18, OP-31 slice 5p), the twin of [faceAddressBefore].
     *
     * Every entry owns two flat-end slots now and a straight crease's entry owned none, so every slot after
     * the first such entry stands two further on. The slot at [old] is found in that file's own order, by
     * **name**, and the name is looked up where it stands now; an address this cannot place is handed back
     * unchanged rather than guessed at (OP-3).
     */
    fun faceAddressBeforeCapSlots(
        feature: Feature3,
        old: Int,
    ): Int {
        val faces = Section3.faces(feature).first ?: return old
        val was = facesBeforeCapSlots(feature)?.getOrNull(old) ?: return old
        return faces.indexOfFirst { it.name == was.name }.takeIf { it >= 0 } ?: old
    }

    /** The same map for a **face** address: the bands were one run and the corner patches came after them. */
    fun faceAddressBefore(
        feature: Feature3,
        old: Int,
    ): Int {
        val f = feature as? Feature3.Blend ?: return old
        val faces = Section3.faces(feature).first ?: return old
        val base = Section3.faces(f.base).first ?: return old
        if (old < base.size) return old
        var j = old - base.size
        for (k in f.targets.indices) {
            val bands = bandSlotsAt(f, k)
            if (j < bands) {
                return faces.indexOfFirst { it.name == FaceName.BlendBand(f.targets[k], j) }.takeIf { it >= 0 } ?: old
            }
            j -= bands
        }
        val was = cornerFacesOf(f).map { it.second }.getOrNull(j) ?: return old
        return faces.indexOfFirst { it.name == was.name }.takeIf { it >= 0 } ?: old
    }

    /**
     * **Which entry of this dressing owns a curve two or more of them make** — the ordering rule OP-30's
     * rail decision states for rails, read for every curve a dressed body appends (OP-31, slice 5b).
     *
     * *The rule, once, for the whole list.* A dressed body's edge list is the base's, then **one block per
     * entry, in the entry's own order** — its two rails and its free-end notch slots — and then, **after
     * every block**, the curves two or more entries make *together*: the corner curves and the run-in
     * creases, each in the order of the **latest entry that takes part in it** and, within that, in the
     * corner's own order. The face list is the same sentence one word over: the base's faces, then per entry
     * its bands and its flat-end slots, then the corner patches by latest participant.
     *
     * *Why the fixed part comes first and the shared part last.* A slot only ever holds still if the number
     * of slots **before** it does. Two rails is a fact about an entry; so is `2 × bands` notch slots where
     * that entry's own base edge is a straight run (a curved one has no flat cap standing in a face, so it
     * states no notch), and so is the mirror of it for the flat-end faces. Every one of those counts survives
     * a **tombstone**, which records its band count ([Feature3.Blend.bandsAt]) and keeps its target — so a
     * rounding removed from the dressing keeps every slot of its own block by name, and one **added** only
     * ever appends after the last block. That is exactly what OP-30 demands of a rail (*"an entry addressing
     * a rail would then silently round a different edge because some other rounding was deleted"*), owed to
     * every curve the moment a *step* can hold one of their addresses — which slice 5b's notch curve made
     * ordinary. Interleaving the shared curves **into** the blocks was tried first and is worse: how many
     * curves a corner puts on the body is a fact about the corner's own *kind*, so a corner that goes with a
     * removed entry then shortens its block and every later block re-packs — which is the very defect,
     * moved one entry along (`DressedBodyTombstoneTest` is the fixture that says so).
     *
     * *And the shared curves themselves hold still too, since slice 5g.* A corner made or unmade by an edit
     * used to move the ones listed after it, which this rule left as its one residual class. It is closed the
     * way [Feature3.Blend.absent] closes a tombstone's bands: the slot count is decided at build time and
     * **recorded** on the feature ([Feature3.Blend.corners]), so what [entryOwning] now decides is only the
     * order in which a *new* corner is appended and recorded — see [SlotPool] for the three cases.
     */
    private fun entryOwning(
        f: Feature3.Blend,
        who: List<Int>,
    ): Int {
        val standing = f.standing
        var best = 0
        for (p in who) if (p < standing.size) best = max(best, standing[p])
        return best
    }

    /**
     * **The shared slots, laid out by the dressing's own record** (OP-31, slice 5g) — the last address a
     * dressed body did not hold still, and the rule that closes it.
     *
     * [entryOwning] states the ordering the appended lists have: the base's list, then **one block per
     * entry**, and the curves two or more entries make *together* after them, because a block's size may
     * never depend on a corner — **how many curves a corner puts on the body is a fact about the corner's own
     * kind**. That left exactly one class exposed, and it is two things rather than one: a corner *made or
     * unmade* by an edit (a third rounding that turns two free ends into a crossing, a size change that makes
     * a congruent pair incongruent, a rounding removed so a corner is gone) moved every shared curve after
     * it, and a rounding **added** to the dressing moved *all* of them, because the new entry's block goes in
     * ahead of the whole run. A `filletedge` step holding one of those addresses then silently rounded a
     * different curve.
     *
     * So the count is **decided at build time and recorded**, exactly as a tombstone's band count is
     * ([Feature3.Blend.absent], [Feature3.Blend.corners]) — and the record is read **inside the blocks**,
     * one list of slots at the end of each entry's own, which is what makes an added rounding append rather
     * than push. What the record holds is the slot's own **identity** ([CornerSlot]): which base edges the
     * corner stands between, which kind of curve, which piece — never a position, so it means the same thing
     * on the day it is read as on the day it was written (OP-18). Three cases, and they are the whole rule:
     *
     * - a **recorded** slot the body still has takes the curve back, whatever else changed;
     * - a recorded slot the body no longer has stands as a **tombstone** with a reason ([goneEdge],
     *   [goneFace]) — the same answer a removed rounding's rail slot gives, for the same reason;
     * - a curve the record does not know is **appended after every block**, and the next gesture records it
     *   into the last one, which is exactly where it already stands ([sharedSlots]) — so recording moves
     *   nothing either. Appending is the one move that disturbs nothing, which is why a *grown* corner takes
     *   it too: where a corner puts down more curves than were recorded for it — a drawn profile gains a
     *   piece, a crossing becomes a walk — the extra ones go to the end rather than pushing the record along.
     *
     * The record therefore only ever **grows**, and a body with no record at all lays out exactly as it did
     * before this slice, which is what makes the migration a no-op on the geometry
     * ([DocumentFormat.CORNER_SLOT_VERSION]).
     */
    private class SlotPool<T>(
        private val live: List<T>,
        keyOf: (T) -> CornerSlot?,
    ) {
        // by identity, and **in order** within one identity: two corners can in principle stand between the
        // same base edges, and the honest answer there is first come first served rather than a guess
        private val queued = HashMap<CornerSlot, ArrayDeque<Int>>()
        private val used = BooleanArray(live.size)

        init {
            for ((i, t) in live.withIndex()) keyOf(t)?.let { queued.getOrPut(it) { ArrayDeque() }.addLast(i) }
        }

        /** The curve recorded slot [slot] holds, or null where the body no longer has one. */
        fun take(slot: CornerSlot): T? {
            val i = queued[slot]?.removeFirstOrNull() ?: return null
            used[i] = true
            return live[i]
        }

        /** What no recorded slot claimed, in the order the corners put it down. */
        fun rest(): List<T> = live.indices.filter { !used[it] }.map { live[it] }
    }

    /** The curves two or more entries make together, in the order of the latest entry that takes part. */
    private fun liveSharedEdges(
        f: Feature3.Blend,
        pieces: List<Piece>?,
        corners: List<Corner>,
    ): List<SolidEdge> {
        if (pieces == null) return emptyList()
        val shared = HashMap<Int, MutableList<SolidEdge>>()
        for ((who, e) in cornerEdgesOf(f, pieces, corners)) shared.getOrPut(who) { ArrayList() }.add(e)
        for ((who, e) in runInEdges(f, pieces, corners)) shared.getOrPut(who) { ArrayList() }.add(e)
        return f.targets.indices.flatMap { shared[it] ?: emptyList() }
    }

    /** The same for the surfaces: the corner patches, by the entry that owns each. */
    private fun liveSharedFaces(f: Feature3.Blend): List<FacePatch> {
        val shared = HashMap<Int, MutableList<FacePatch>>()
        for ((who, p) in cornerFacesOf(f)) shared.getOrPut(who) { ArrayList() }.add(p)
        return f.targets.indices.flatMap { shared[it] ?: emptyList() }
    }

    /**
     * **The record as it stands after this body** — what a gesture writes back ([Feature3.Blend.corners]).
     *
     * Every recorded slot is kept, whether the body still has its curve or not (a tombstone is a slot too),
     * and everything the record did not know joins the **last** block, which is exactly where the layout has
     * just appended it. So recording is position-preserving, and running it twice changes nothing.
     */
    fun sharedSlots(f: Feature3.Blend): CornerSlots {
        val pieces = piecesOf(f)
        val corners = pieces?.let { cornersOf(it).list } ?: emptyList()
        return CornerSlots(
            recordedBlocks(f.corners.edges, liveSharedEdges(f, pieces, corners), f.targets.size) { CornerSlot.of(it.name) },
            recordedBlocks(f.corners.faces, liveSharedFaces(f), f.targets.size) { CornerSlot.of(it.name) },
        )
    }

    private fun <T> recordedBlocks(
        was: List<List<CornerSlot>>,
        live: List<T>,
        n: Int,
        keyOf: (T) -> CornerSlot?,
    ): List<List<CornerSlot>> {
        val pool = SlotPool(live, keyOf)
        val out = ArrayList<List<CornerSlot>>(n)
        for (k in 0 until n) {
            val block = was.getOrElse(k) { emptyList() }
            for (slot in block) pool.take(slot)
            out.add(block)
        }
        if (n == 0) return out
        // …and a curve outside the recordable vocabulary is left out of the record rather than written down
        // wrong: it appends every time, which is visible, where a lost slot would not be ([CornerSlot])
        val extra = pool.rest().mapNotNull(keyOf)
        if (extra.isNotEmpty()) out[n - 1] = out[n - 1] + extra
        return out
    }

    /** The tombstone a recorded **edge** slot keeps when the corner that put a curve there is gone. */
    private fun goneEdge(slot: CornerSlot): SolidEdge {
        val name = slot.edgeName
        val at = Vec3(0.0, 0.0, 0.0)
        // each of the two says which surfaces its curve ran between, which is what an edge is even when the
        // body no longer has it: a mitre stood between the two bands, a corner rail alongside the patch
        val between =
            if (slot.kind == CornerSlotKind.RAIL) {
                FacePair(slot.faceName, slot.faceName)
            } else {
                FacePair(FaceName.BlendBand(slot.edges.first(), slot.piece), FaceName.BlendBand(slot.edges.last(), slot.piece))
            }
        return SolidEdge(name, EdgeGeom.Straight(at, at), between, Msgs.refusalBlendCornerSlotGone(name = name.label))
    }

    /** The same tombstone for a recorded **face** slot — a corner patch the body no longer has. */
    private fun goneFace(slot: CornerSlot): FacePatch =
        FacePatch(slot.faceName, null, emptyList(), Msgs.refusalBlendCornerFaceGone(name = slot.faceName.label), absent = true)

    /** How many free-end notch slots entry [k] owns: two per band, and none where its crease is not one run. */
    private fun notchSlotsAt(
        f: Feature3.Blend,
        baseEdges: List<SolidEdge>,
        k: Int,
    ): Int = if (straightCrease(f, baseEdges, k)) 2 * bandSlotsAt(f, k) else 0

    /**
     * How many **flat end** face slots entry [k] owns: **two, always** (OP-31, slice 5p).
     *
     * *What this used to say, and why it was wrong.* Until slice 5p it read *"two where its crease is not one
     * straight run, and none where it is"*, on session 81's own sentence: *"at a free end of a straight edge
     * that frame lies in the end face's plane — the cap is square to the edge and so is the face"*. That is
     * true at a **right angle** and nowhere else. The face a straight crease's free end runs into is square
     * to that crease only where the two meet at 90°: on a regular **pentagonal** prism with one rounded top
     * edge the neighbouring wall stands at the polygon's own exterior angle, on a **loft** it leans by the
     * slant, and in both the band closes on a flat cap that is a face of the body like any other — with no
     * slot to state it, so a level section through the band region could not close (`refusal.section
     * .planeSectionThisSolidDoes`, the pentagon and the loft in exactly the same words).
     *
     * *Why the count cannot ask the geometry.* Whether a notch owns an end is a fact about the neighbouring
     * faces' **orientation** — a coordinate, and a loft's slant is an ordinary parameter — so a count read
     * off it would change the face list when a number is retyped, which is precisely what OP-21 forbids of
     * structure. So every entry owns two, and each is either the cap it has or a tombstone saying who owns
     * that end instead: the notch ([Msgs.refusalBlendCapStandsInAFace]) or the corner that closed it
     * ([Msgs.refusalBlendEndClosedByACorner]). The indices therefore move, which is what
     * [DocumentFormat.CAP_SLOT_VERSION] is for.
     */
    private fun capSlotsAt(
        @Suppress("UNUSED_PARAMETER") f: Feature3.Blend,
        @Suppress("UNUSED_PARAMETER") baseEdges: List<SolidEdge>,
        @Suppress("UNUSED_PARAMETER") k: Int,
    ): Int = 2

    /**
     * Whether entry [k]'s own crease is **one straight run** — read off the *base* body, so a tombstone
     * answers it as readily as a rounding that stands (its target is recorded either way).
     */
    private fun straightCrease(
        f: Feature3.Blend,
        baseEdges: List<SolidEdge>,
        k: Int,
    ): Boolean {
        val target = f.targets.getOrNull(k) ?: return true
        val el = baseEdges.getOrNull(target)?.let { edgePath(it).first?.elements?.singleOrNull() }
        return el is Curve3Element.Seg3
    }

    /** How many band faces entry [k] owns — its own sections where it stands, its tombstone's record where not. */
    private fun bandSlotsAt(
        f: Feature3.Blend,
        k: Int,
    ): Int {
        if (f.isAbsent(k)) return f.bandsAt(k)
        val sec = f.sections.getOrNull(k) ?: return 1
        return if (sec.kind == BlendKind.PROFILE) max(1, sec.profile.size) else 1
    }

    /** The name of notch slot [slot] of entry [k] — end by end, then band by band, so the order is the count's. */
    private fun notchNameAt(
        f: Feature3.Blend,
        k: Int,
        slot: Int,
    ): EdgeName.BlendNotch {
        val bands = max(1, bandSlotsAt(f, k))
        return EdgeName.BlendNotch(f.targets.getOrElse(k) { 0 }, slot < bands, slot % bands)
    }

    /**
     * **The curve a band's free end leaves in the face its cap stands in** (OP-31, slice 5b) — the last of
     * the four kinds of edge a dressed body has, and the one that made *"round off the end of a rounded
     * edge"* an ordinary ask with no address.
     *
     * Session 81 stated it as a **boundary piece** of that third face and nothing more ([Notch]): the face
     * outline showed the quarter arc, the level section closed on it, and no entry of the edge list said the
     * body had a crease there. It has one — the band is a cylinder about the edge's own offset axis and the
     * end face is the plane **square** to that axis, so the two meet in a circular arc about it — and a
     * crease this drawing can name is a crease a rounding may be addressed by. Its own rounding is then that
     * arc's **revolution** ([revolvedBand]), exact, with the [Revolve3] vocabulary naming the torus it makes.
     *
     * **Two slots per band, always** ([notchSlotsAt]) — one per end per piece of the entry's own section,
     * in the entry's own block ([entryOwning]) and never re-packed. A slot with no crease at it says so: an
     * end a corner claims is no free end, a **fill**'s cap closes a void and cuts nothing out of a face, and
     * a cap standing in no plane face of the body has no face to notch. That the *count* is a function of
     * the entry alone is what makes the address survive both edits — a rounding removed from the dressing
     * keeps its slots by name, and one added never moves another's.
     *
     * Only this level's **own** bands emit one, exactly as [cornerEdgesOf] lists only this level's corners:
     * a band from the chain below already stated its notch curve at the index the base's list gave it. And a
     * free end a later gesture turns into a **corner** is no free end any more, so the entry the base put
     * there keeps its index and says so — the same tombstone rule a consumed edge and a re-turned corner
     * curve both live under.
     */
    private fun notchEdges(
        f: Feature3.Blend,
        baseEdges: List<SolidEdge>,
        pieces: List<Piece>?,
        corners: List<Corner>,
        k: Int,
        d: Dressing,
    ): List<SolidEdge> {
        val slots = notchSlotsAt(f, baseEdges, k)
        if (slots == 0) return emptyList()
        val bands = max(1, bandSlotsAt(f, k))
        val at = pieces?.indexOfFirst { it.index == d.index } ?: -1
        val piece = if (pieces != null && at >= 0) pieces[at] else null
        val claimed = HashSet<Pair<Int, Boolean>>()
        for (c in corners) claimed.addAll(c.ends)
        val faces = undressedFacesOf(f)
        val out = ArrayList<SolidEdge>(slots)
        for (slot in 0 until slots) {
            val atStart = slot < bands
            val j = slot % bands
            val name = EdgeName.BlendNotch(d.index, atStart, j)
            val made =
                if (piece == null || faces == null || (at to atStart) in claimed || !piece.choice.convex) {
                    null
                } else {
                    notchEdgeAt(faces, piece, atStart, j, name)
                }
            out.add(
                made ?: SolidEdge(
                    name,
                    EdgeGeom.Straight(d.crease?.ref?.at ?: Vec3.ZERO, d.crease?.ref?.at ?: Vec3.ZERO),
                    FacePair(d.name, d.name),
                    if ((at to atStart) in claimed) {
                        Msgs.refusalBlendFreeEndNowCorner(name = name.label)
                    } else {
                        Msgs.refusalBlendNoNotchAtThisEnd(name = name.label)
                    },
                ),
            )
        }
        return out
    }

    /** The notch curve of one free end and one piece of the band's own section, or null where there is none. */
    private fun notchEdgeAt(
        faces: List<FacePatch>,
        piece: Piece,
        atStart: Boolean,
        j: Int,
        name: EdgeName.BlendNotch,
    ): SolidEdge? {
        val seg = piece.seg ?: return null
        val at = if (atStart) seg.start else seg.end
        val away = if (atStart) seg.start - seg.end else seg.end - seg.start
        if (away.length() <= Geom3.WELD_TOL) return null
        val (index, map) = notchFrame(faces, piece, at, away.normalized()) ?: return null
        val face = faces[index]
        val plane = face.plane ?: return null
        val sec = piece.wedge.pieces.getOrNull(j) ?: return null
        return SolidEdge(
            name,
            EdgeGeom.OnPlane(plane, GeomMath.transform(sec, map)),
            FacePair(FaceName.BlendBand(piece.index, j), face.name),
        )
    }

    /**
     * **The crease where two bands that no corner joins cross** — the last thing a dressed edge list owed
     * (OP-31, slice 5a).
     *
     * *Which pairs these are.* Two roundings of unlike size or kind at a **convex** corner overlap, and the
     * boolean trims them exactly: that is session 79's cut (2) and it stays, because the body it makes is
     * right. What was missing is the *drawing* saying what the body has there. Session 81 gave the two
     * bands their own extents ([endsRunInto], [runsInto]) and the planar one its own fitted outline
     * ([bandToItsCorners]); this states the **crease** between them, which is the curve those extents end on.
     *
     * *And what it is.* Two bevels meet in a straight line and it is stated as one, exactly. Everything else
     * — a bevel's plane against a round's cylinder, and above all two cylinders of unlike radius whose axes
     * are skew — is a conic or a quartic in no plane at all, so it is a **chain of cubics through points
     * that are every one of them exact on both surfaces** ([fittedChain3]), with the tolerance carried at
     * the value ([SolidEdge.fitted]) and said wherever the edge is read. Deliberately not distinguished: the
     * plane-against-cylinder case *is* an exact ellipse arc and is stated as a fitted chain all the same,
     * because the band outline beside it already is one and two answers for one curve is worse than one.
     */
    private fun runInEdges(
        f: Feature3.Blend,
        pieces: List<Piece>,
        corners: List<Corner>,
    ): List<Pair<Int, SolidEdge>> {
        val fresh = f.standing.size
        val out = ArrayList<Pair<Int, SolidEdge>>()
        val seen = HashSet<List<Int>>()
        for (at in pieces.indices) {
            for ((atStart, other) in endsRunInto(pieces, at, corners)) {
                if (at >= fresh && other >= fresh) continue
                if (!seen.add(listOf(at, other).sorted())) continue
                val who = entryOwning(f, listOf(at, other))
                for (e in runInCrease(pieces, at, other, atStart)) out.add(who to e)
            }
        }
        return out
    }

    /**
     * The interval of `[0, 1]` over which [at] states a point — found by scanning and then **halving at each
     * end**, exactly the way a one-ended pivot finds where its cap begins ([Pivot.capStart]).
     *
     * The scan is what makes it honest about the one thing halving cannot see: a curve that leaves and
     * comes back would be reported as one interval. It cannot happen for the crease this serves — a ruling
     * either reaches the neighbour's wedge or does not, and the reach is monotone in the section's own depth
     * — and where a later producer's is not, the scan's own resolution is the statement of what was assumed.
     */
    private fun creaseSpan(at: (Double) -> Vec3?): Pair<Double, Double>? {
        val n = 16
        val good = (0..n).map { it.toDouble() / n }.filter { at(it) != null }
        if (good.isEmpty()) return null
        var lo = good.first()
        var hi = good.last()
        if (lo > 0.0) {
            var bad = lo - 1.0 / n
            repeat(BISECT_STEPS) {
                val m = (bad + lo) / 2.0
                if (at(m) != null) lo = m else bad = m
            }
        }
        if (hi < 1.0) {
            var bad = hi + 1.0 / n
            repeat(BISECT_STEPS) {
                val m = (bad + hi) / 2.0
                if (at(m) != null) hi = m else bad = m
            }
        }
        return if (hi - lo > 1e-9) lo to hi else null
    }

    /** The crease between band [at] and band [other], one curve per piece of [at]'s own section. */
    private fun runInCrease(
        pieces: List<Piece>,
        at: Int,
        other: Int,
        atStart: Boolean,
    ): List<SolidEdge> {
        val a = pieces[at]
        val b = pieces[other]
        val seg = a.seg ?: return emptyList()
        val v = seg.end - seg.start
        if (v.length() <= Geom3.WELD_TOL) return emptyList()
        val u = v.normalized()
        val edges = listOf(a.index, b.index).sorted()
        val theirs = orientedSections(b)
        val out = ArrayList<SolidEdge>()
        for ((j, sec) in orientedSections(a).withIndex()) {
            if (sec == null) continue

            fun crease(t: Double): Vec3? {
                val q = sectionPointAt(sec, t) ?: return null
                val s = runsInto(a, b, q, atStart) ?: return null
                return worldOnStraight(a.crease, seg.start, u, q, s)
            }
            // **the crease runs over the part of [a]'s section whose ruling reaches [b]'s wedge at all**,
            // and that is often less than the whole of it: where [a] is the deeper of the two, its own
            // section runs on past the neighbour's and is ended by [a]'s own cap instead of by the crease.
            val (from, to) = creaseSpan { t -> crease(t) } ?: continue

            fun on(u: Double): Vec3? = crease(from + (to - from) * u)
            val p0 = on(0.0) ?: continue
            val p1 = on(1.0) ?: continue
            val mid = on(0.5) ?: continue
            if ((p1 - p0).length() <= Geom3.WELD_TOL) continue
            val straight = (mid - (p0 + p1) * 0.5).length() <= SAME_CURVE_TOL
            val fit = if (straight) null else fittedChain3(Combine3.FIT_TOL_MM) { u -> on(u) }
            if (!straight && fit == null) continue
            out.add(
                SolidEdge(
                    EdgeName.BlendMitre(edges, j),
                    if (fit == null) EdgeGeom.Straight(p0, p1) else EdgeGeom.InSpace(fit.first),
                    FacePair(FaceName.BlendBand(a.index, j), FaceName.BlendBand(b.index, min(j, theirs.size - 1))),
                    null,
                    fit?.second,
                ),
            )
        }
        return out
    }

    // ---- the corner curves: a dressed body's edge list states what the body has (OP-31, item 3) ----

    /**
     * **The curves a corner puts on the body**, each tagged with the entry that owns it (OP-31, item 3, and
     * its ordering settled by slice 5b).
     *
     * *Why they come after every entry's own block, and not woven into it.* Every index in this list is an
     * address a `signs=` in some file already holds (OP-17, OP-18), and a slot only holds still if the
     * number of slots **before** it does. A corner's own curve count is a fact about the corner's *kind*,
     * not about the entries, so a block that held one would shrink when the corner went — and every later
     * block with it. They are therefore listed after all the blocks, in the order of the **latest entry that
     * takes part in them** ([entryOwning]), which is what keeps every rail and every notch slot where it is
     * through both edits and leaves exactly one class exposed rather than all of them.
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
    ): List<Pair<Int, SolidEdge>> {
        // …only the corners this level makes: the ones under it are already in the base's own list, at the
        // indices they were appended at (the same freshness test [cornerFacesOf] uses, and deliberately so)
        val fresh = f.standing.size
        val made = ArrayList<Pair<Int, SolidEdge>>()
        for (c in corners) {
            if (c.ends.none { it.first < fresh } && c.extra.none { it < fresh }) continue
            val edges = cornerEdges(pieces, c)
            val faceAt = { k: Int -> FaceName.BlendCorner(edges, k) }
            val who = entryOwning(f, c.ends.map { it.first } + c.extra)
            val out = ArrayList<SolidEdge>()
            when (c) {
                is Joint -> out.addAll(jointEdges(pieces, c, edges))
                is Walk -> {
                    out.addAll(walkEdges(pieces, c, edges, faceAt))
                    if (c is Ledge) out.addAll(ledgeEdges(pieces, c, edges, faceAt))
                }
                // **the ball is this entry's one whole cut.** A *round* vertex states no crease at all —
                // the ball and each of the three bands envelop the same sphere and are tangent along the
                // band's own end circle — so there is nothing there for a list of creases to be missing. A
                // **bevelled** one does have three: its three bevel planes meet pairwise in lines running
                // to the apex, and those lines are the apex construction's rather than any ring's. They are
                // not listed, and a rounding of them is therefore not offered (see the note under OP-31).
                is Vertex -> Unit
                // **the pivot about a slanted or a ring upright** (OP-31, slice 5h): its two rails are the
                // ball's own contacts — the tangency curve on the shared face, fitted, and the range of
                // the upright the ball rolls along, which is a piece of that upright's own carrier and
                // therefore exact.
                is CanalTurn -> out.addAll(canalTurnEdges(c, edges))
            }
            for (e in out) made.add(who to e)
        }
        return made
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
        val shared = c.walkFace
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
     * The two rings a **ledge** puts on the body, after the walk's own rails: where the walk's arrival
     * section meets the ledge, and where the shallower band's own end section does (OP-31, slice 5a).
     *
     * Both are creases and not hand-overs, which is the difference from every other ring of a walk: the
     * ledge stands in the plane the walk's motion is **square to** at that instant, and the shallower band's
     * rulings are square to it too, so each surface meets it at a right angle. They are stated at the corner
     * itself — the micron the shallower tube is set back by is the tool's own business ([Ledge.emit]).
     */
    private fun ledgeEdges(
        pieces: List<Piece>,
        c: Ledge,
        edges: List<Int>,
        faceAt: (Int) -> FaceName,
    ): List<SolidEdge> {
        val deep = pieces[c.a]
        val shallow = pieces[c.b]
        val m = orientedSections(deep).size
        val ledge = faceAt(c.walkLegs.size * m)
        val out = ArrayList<SolidEdge>(m + orientedSections(shallow).size)
        for (j in 0 until m) {
            val (geom, why) = ringCurve(deep, c.rings.last(), j)
            out.add(
                SolidEdge(
                    EdgeName.BlendMitre(edges, j),
                    geom ?: EdgeGeom.Straight(c.at, c.at),
                    FacePair(faceAt((c.walkLegs.size - 1) * m + j), ledge),
                    why,
                ),
            )
        }
        for (j in orientedSections(shallow).indices) {
            val (geom, why) = ringCurve(shallow, c.placeB, j)
            out.add(
                SolidEdge(
                    EdgeName.BlendMitre(edges, m + j),
                    geom ?: EdgeGeom.Straight(c.at, c.at),
                    FacePair(ledge, FaceName.BlendBand(shallow.index, j)),
                    why,
                ),
            )
        }
        return out
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
        // **a canal band is the one face this list states no carrier for** (OP-31, slice 5f): it is
        // neither a plane, nor a revolution, nor a ruled strip, and it says so where it is read
        d.canal?.let { return listOf(if (it.bevel) bevelBandPatch(it) else canalBandPatch(it)) }
        val sections = orientedSections(d)
        val crease = d.crease ?: return emptyList()
        val el = soleElement(crease)
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
        val crease = d.crease ?: return FacePatch(name, null, emptyList(), Msgs.refusalBlendIsChainSeveralPiecesSo(name = d.edge.name.label))
        return when (el) {
            is Curve3Element.Seg3 -> {
                val v = el.end - el.start
                val len = v.length()
                if (len <= Geom3.WELD_TOL) {
                    FacePatch(name, null, emptyList(), Msgs.refusalBlendHasNoLengthSoIts(name = d.edge.name.label))
                } else {
                    val u = v * (1.0 / len)
                    Section3.sweptFace(Plane3(el.start, crease.e1, u.cross(crease.e1)), u, len, piece, name)
                }
            }
            is Curve3Element.Arc3 -> {
                val (frame, sr) =
                    revolvedBand(crease, el, piece) ?: return FacePatch(
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
        val crease = d.crease ?: return null to Msgs.refusalBlendIsChainSeveralPiecesSo2(name = d.edge.name.label)
        val el = soleElement(crease) ?: return null to Msgs.refusalBlendIsChainSeveralPiecesSo2(name = d.edge.name.label)
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
                        EdgeGeom.Straight(worldOnStraight(crease, el.start, u, t, s0), worldOnStraight(crease, el.start, u, t, s0)) to
                            Msgs.refusalBlendRailTakenByCorner(name = EdgeName.BlendRail(d.index, 0).label, name2 = d.name.label)
                    } else {
                        EdgeGeom.Straight(worldOnStraight(crease, el.start, u, t, s0), worldOnStraight(crease, el.start, u, t, s1)) to null
                    }
                }
            }
            is Curve3Element.Arc3 -> {
                val axis = el.normal.normalized()
                val w = crease.ref.at + crease.e1 * t.x + crease.ref.e2 * t.y
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
        /**
         * The **canal bands** standing on this chain (OP-31, slice 5f) — a canal along the mitre two bands
         * cross in ends both of them, nearer the run's middle than the mitre itself, and its rail is where.
         */
        canals: List<Canal> = emptyList(),
    ): (Vec2) -> Pair<Double, Double> {
        val piece = pieces[at]
        val len = piece.length
        // **a piece that is neither one straight run nor one arc stands over the whole of it**, and that is
        // provable rather than assumed: a corner is between two runs each of which is one or the other
        // ([cornersOf]), an upright a walk turns about must be straight ([Walk.uprightEnd]), and a band can
        // only run into another straight one ([endsRunInto]). So nothing can set such a band back, and
        // asking would be asking for a station along a run that has none. This is the case a rounding along
        // a **chain** makes ordinary: the legs of a pivot's own rail are arcs (OP-31, item 3).
        //
        // A **circular** run is not that case any more (OP-31, slice 5e): an arc-edged band ends at the
        // inside corner it pivots at exactly as a straight one does, and its station along its own run is
        // the arc length [stationOf] states.
        if (piece.seg == null && soleElement(piece.crease) == null) return { _ -> 0.0 to len }
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
        // …and where a rounding runs along this band's own **free-end notch** (OP-31, slice 5b), which ends
        // it as surely as a corner does and is not one: the notch curve is the band's end circle, so the
        // band that rounds it stands square to this band's run and its tangency *is* the setback along it.
        val notched = notchSetbacks(pieces, at)
        // …and where a **canal band** runs along the mitre this band makes with a neighbour: the canal's
        // own rail is this band's end there, and it stands before the mitre the crossing left (slice 5f)
        val bitten = canalSetbacks(pieces, at, canals)
        val sections = orientedSections(piece)
        return { p ->
            var from = lo?.let { stationOf(piece, it.at(p)) } ?: 0.0
            var to = hi?.let { stationOf(piece, it.at(p)) } ?: len
            for ((atStart, other) in met) {
                val s = runsInto(piece, pieces[other], p, atStart) ?: continue
                if (atStart) from = max(from, s) else to = min(to, s)
            }
            for ((where, back) in notched) {
                val (atStart, k) = where
                val on = sections.getOrNull(k)
                if (sections.size > 1 && (on == null || !onSpanOf(on, p))) continue
                if (atStart) from = max(from, back) else to = min(to, len - back)
            }
            for ((atStart, qs, ss) in bitten) {
                val s = setbackAt(p, qs, ss) ?: continue
                if (atStart) from = max(from, s) else to = min(to, s)
            }
            from to to
        }
    }

    /**
     * **What a rounding of this band's own free-end notch takes off its run** — one entry per end and per
     * piece of its section, and nothing where no such rounding stands (OP-31, slice 5b).
     *
     * Structural, never measured (OP-21): the band that rounds a notch names that notch as its crease, so
     * the pairing is by name, and the width is that band's own tangency on the face it takes the strip off.
     */
    private fun notchSetbacks(
        pieces: List<Piece>,
        at: Int,
    ): List<Pair<Pair<Boolean, Int>, Double>> {
        val edge = pieces[at].index
        val out = ArrayList<Pair<Pair<Boolean, Int>, Double>>()
        for (q in pieces) {
            val n = q.crease.edge.name as? EdgeName.BlendNotch ?: continue
            if (n.edge != edge) continue
            val face = FaceName.BlendBand(edge, n.piece)
            val t =
                when (face) {
                    q.crease.face1.name -> q.wedge.t1
                    q.crease.face2.name -> q.wedge.t2
                    else -> continue
                }
            out.add((n.atStart to n.piece) to t.length())
        }
        return out
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
        val span = spanOf(pieces, at, cornersOf(pieces).list, canalsOf(f))
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
        val span = spanOf(pieces, at, cornersOf(pieces).list, canalsOf(f))
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

    private fun orientedSections(d: Dressing): List<ProfileElement?> = d.wedge?.let { orientedSections(it, d.choice) } ?: emptyList()

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
            // **a strip off a curve a corner splices in is taken at the tip, with the splice** (OP-31,
            // slice 5b). A trim composes down the chain and a notch does not, so the free end's notch arc
            // and a corner's tangent rail are no pieces of this list at all — they replace a *corner* of it,
            // once, at the tip ([notchesOf]). Looking for one here found nothing and refused a face that is
            // perfectly statable; the strip is [insetChain]'s.
            if (edge.name is EdgeName.BlendNotch || edge.name is EdgeName.BlendCornerRail) continue
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
        /**
         * Why this correction **cannot be stated**, or null where it can (OP-31, slice 5b).
         *
         * The one producer: a rounding that runs **along** a curve this splice puts into the face — the
         * free end's own notch arc, a corner's rail — takes a strip off that curve, and the strip is taken
         * here rather than by [correctedOutline] because *a trim composes down the chain and a notch does
         * not*. Where the inset of a spliced chain has no answer in this vocabulary the face says so and
         * keeps no boundary that is not there (OP-3, OP-15's honesty line).
         */
        val reason: Msg? = null,
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
        // **the same sentence said for a canal** (OP-31, slice 5l): a canal's free-end cap runs out past the
        // end of the band it rolls on, onto the face that band is tangent to, and the step the tool took
        // there is the body's — see [canalCapSteps]. Asked first, because a canal band owns no [Piece] at
        // all and the rigid-section walk below has nothing to say about it.
        val canalSteps = canalCapSteps(trimmed, canalsOf(f))
        val pieces = piecesOf(f) ?: return canalSteps
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
            val at = c.walkAt
            // **and the strip a rounding of one of this walk's own rails took off the face it runs in**
            // (OP-31, slice 5b): the rail is spliced into that face at its corner rather than being one of
            // its boundary pieces, so the strip is taken here, leg by leg, with the splice.
            val mine = cornerEdges(pieces, c)
            c.sharedChainByLeg(pieces, hand)?.let { byLeg ->
                val widths =
                    byLeg.map { (leg, _) ->
                        insetWidths(pieces, c.walkFace, 1) { n ->
                            if (n is EdgeName.BlendCornerRail && n.edges == mine && n.piece == leg) 0 else null
                        }[0]
                    }
                spliceInto(faces, trimmed, c.walkFace, at, pieces[c.travelling].crease.edge, pieces[c.travelling].sec, byLeg.map { it.second }, widths = widths)?.let { out.add(it) }
            }
            if (c is Pivot) {
                c.capChain(pieces, hand)?.let { (chain, tol) ->
                    spliceInto(faces, trimmed, c.third, c.at, pieces[c.a].crease.edge, pieces[c.a].sec, chain, tol)?.let { out.add(it) }
                }
            }
        }
        for ((j, piece) in pieces.withIndex()) {
            // a **fill** adds material rather than taking it, so its cap closes a void and notches nothing.
            //
            // A **circular** run notches its end face exactly as a straight one does (OP-31, slice 5e).
            // Session 81's cut read *"only a straight run has a cap that stands in one plane at all"*, and
            // it does not hold: a band along an arc ends on the **meridian** plane, which is square to the
            // run and is a face of the body whenever the arc's own centre lies in it — a sector's rim ends
            // in its own radial face, which is the ordinary shape of a pie slice. The map from the wedge's
            // frame into that plane is rigid there for the same reason ([creaseOf]'s own proof), so the
            // notch is exact. What it does **not** get is an address: how many notch slots an entry owns is
            // decided by the base edge alone ([notchSlotsAt]) and a curved crease owns none, so stating one
            // here would move every appended address after it — OP-30's own rule, and slice 5g's subject.
            if (!piece.choice.convex) continue
            if (piece.seg == null && soleElement(piece.crease) == null) continue
            for (atStart in listOf(true, false)) {
                if ((j to atStart) in claimed) continue
                val at = endPointOf(piece, atStart) ?: continue
                val away = outOf(piece, atStart) * -1.0
                if (away.length() <= Geom3.WELD_TOL) continue
                val frame = endPlacement(piece, atStart) ?: continue
                // **the strip a band takes off its own two faces stops where the band does** (OP-31,
                // slice 5p): asked at every free end, and answered only by the faces that really do carry
                // material past the flat cap — see [capStep].
                out.addAll(capSteps(faces, trimmed, piece, at, away.normalized(), frame))
                out.add(notchAt(faces, piece, at, away.normalized(), atStart, pieces, frame) ?: continue)
            }
        }
        out.addAll(canalSteps)
        return out
    }

    /**
     * **The step a canal's own flat cap leaves in the face its leg has run out onto** (OP-31, slice 5l) —
     * [capSteps]' sentence said for a canal, in the same seam, so that one rule serves both.
     *
     * *What the body has, and what the drawing said.* A canal's tool ends a free run one step-off past the
     * last station ([canalGrow]: a tool never shares a face with the body, and a canal's walls are curved,
     * so the step is the walls' own skin and not a micron). Over that last stretch the ring is the section
     * **translated**, so each of its two legs sweeps a flat strip across whatever face the wall it lies on
     * runs tangent onto past its own end — the flat wall a round is tangent to, where the crease ends at
     * the body's own corner. The body is cut there by exactly that step-off; the drawing stated the face
     * whole, so a vertical plane through the tight bend met the cap's own cut a step-off away from the wall
     * it should have joined and the loop was open by 0.0401 mm.
     *
     * *The rule, and it is [capStep]'s one carrier over.* Asked at **every** free end a canal keeps a cap
     * at, and answered only by the faces that really carry material past it: the face the leg has run onto
     * is the one whose own plane carries the station's tangency, the leg's carried end **and** the carried
     * apex alike, which is the whole of what *"the band runs tangent onto it there"* means. Its trace is
     * the cap plane against that face — one straight segment on a plane wall, exact — and the chain spliced
     * in is the rail the tool's own step laid beside it, from the tangency the station has to the tangency
     * the cap has, then that segment to the corner. [spliceInto]'s own arithmetic does the rest, and the
     * sign is left to the geometry exactly as it is for a pivot's cap.
     *
     * *And where the step is owed and cannot be written down, the face says so.* The one case is a face the
     * leg runs onto whose own outline has **no corner** where the crease ends — a wall whose boundary there
     * is a tangency rather than a corner, which is a body this drawing does not build today. It refuses by
     * name (`refusal.blend.canalCapStepNotStated`) rather than keep a boundary the body has not got (OP-3).
     */
    private fun canalCapSteps(
        trimmed: List<FacePatch>,
        canals: List<Canal>,
    ): List<Notch> {
        val out = ArrayList<Notch>()
        for (canal in canals) {
            if (canal.closed || canal.stations.size < 2 || canal.grow <= Geom3.WELD_TOL) continue
            for (atStart in listOf(true, false)) {
                val st = if (atStart) canal.stations.first() else canal.stations.last()
                if (st.tip) continue
                val dir = st.t * (if (atStart) -canal.grow else canal.grow)
                val apex = st.world(st.apex)
                val tip = apex + dir
                // how near a point must stand to a face to be **on** it here: the spine's own solve is
                // stated to the canal's fitting tolerance and the tangency is read on it (OP-31, Tier B)
                val near = max(canal.fitted, CANAL_FIT_TOL_MM)
                for (side in 0..1) {
                    val foot = if (side == 0) st.p1 else st.p2
                    val toe = foot + dir
                    val wall = (if (side == 0) canal.w1 else canal.w2).patch.name
                    // **the face the leg has run onto**, and there is at most one: the face whose own plane
                    // carries the station's tangency, the leg's carried end and the carried apex alike.
                    // Where the leg runs out onto no face of this body at all — a free end standing clear
                    // in the air — nothing is owed and nothing is said.
                    val patch =
                        trimmed.firstOrNull { p ->
                            p.name != wall && p.name != canal.name && (p.name as? FaceName.BlendCap)?.edge != canal.index &&
                                p.reason == null && p.outline.isNotEmpty() &&
                                p.plane?.let { pl -> listOf(foot, toe, tip).all { q -> abs(pl.distanceTo(q)) <= near } } == true
                        } ?: continue
                    val plane = patch.plane ?: continue
                    // the corner the chain stands at is the face's **own**, and the crease's end is where it
                    // is: the station's apex stands on it to the spine's own tolerance and no nearer
                    val v = plane.toLocal(apex)
                    // …and it is the **nearest** corner of that face, accepted where it stands nearer to the
                    // crease's end than the leg's own tangency does: a station is solved and stands off the
                    // body's own corner by the spine's own tolerance, and no other corner of the face can
                    // be inside the setback the section itself states
                    val reach = (plane.toLocal(foot) - v).length()
                    val corner = patch.outline.map { GeomMath.startOf(it) }.minByOrNull { (it - v).length() }?.takeIf { (it - v).length() < reach }
                    // …and each end of the chain is handed over **past** the boundary it meets, which is what
                    // a bulge's two ends lying on the neighbours' own carriers means ([spliceInto]): a
                    // station is solved and its tangency stands off the face's own boundary by the spine's
                    // tolerance, so a chain that stopped *at* it would miss the crossing by that much
                    val f2 = plane.toLocal(foot)
                    val t2 = plane.toLocal(toe)
                    val a2 = plane.toLocal(tip)
                    val chain =
                        if ((t2 - f2).length() <= Vec2.EPS || (a2 - t2).length() <= Vec2.EPS) {
                            emptyList()
                        } else {
                            listOf(
                                ProfileElement.Seg(Segment(f2 + (f2 - t2), t2)),
                                ProfileElement.Seg(Segment(t2, a2 + (a2 - t2).normalized() * canal.grow)),
                            )
                        }
                    // …and the piece's own tolerance is the tool's **stated step**: that is how far the
                    // body's own facets may stand from this trace, and the section that chains onto it is
                    // entitled to know (OP-31, Tier B)
                    val made =
                        if (corner == null || chain.isEmpty()) {
                            null
                        } else {
                            spliceInto(trimmed, trimmed, patch, plane.toWorld(corner), canal.edge, canal.sec, chain, fitted = canal.grow)
                        }
                    out.add(
                        made ?: Notch(
                            canal.edge,
                            canal.sec,
                            trimmed.indexOfFirst { it.name == patch.name },
                            0,
                            0,
                            v,
                            chain,
                            reason = Msgs.refusalBlendCanalCapStepNotStated(name = canal.edge.name.label, name2 = patch.name.label),
                        ),
                    )
                }
            }
        }
        return out
    }

    /**
     * **The step a band's own flat cap leaves in each of the crease's two faces** (OP-31, slice 5p) — the
     * other half of what a free end owes, and the one the notch never covered.
     *
     * *The defect, in one shape.* A band takes a strip of constant width off each of the two faces its
     * crease runs between, and [correctedOutline] takes it off that face's whole boundary **piece**. Where
     * the band runs the whole of the crease that is exact — but the band ends on a flat cap square to the
     * crease, and beyond that cap the face may still have material: the face's *other* boundary piece at
     * that corner leaves it at some angle other than a right angle to the crease, so a **triangle** of the
     * face survives past the cap and the drawing had already trimmed it away. On the 35° loft that triangle
     * is what a level section at `z = 19.5` was missing, and the loop could not close.
     *
     * *The rule, and it is [bandToItsCorners]'s own one dimension over.* Where the face's other piece leaves
     * the corner **square to the crease** — an extrusion's upright, a plan corner that turns a right angle —
     * the face has nothing beyond the cap and the trimmed boundary is already where the body is: nothing is
     * owed and nothing is spliced. Where it leaves at any other angle the boundary **steps** along the cap's
     * own plane, whose trace on the face is the segment from the tangency at the crease's end to the corner
     * itself — one straight piece, exact, with both of its ends on the neighbours' own carriers. That is a
     * *bulge* in [spliceInto]'s own words, and the arithmetic beside it is already written.
     */
    private fun capSteps(
        faces: List<FacePatch>,
        trimmed: List<FacePatch>,
        piece: Piece,
        at: Vec3,
        away: Vec3,
        frame: Placement,
    ): List<Notch> {
        val out = ArrayList<Notch>()
        for (side in 0..1) {
            val name = if (side == 0) piece.crease.face1.name else piece.crease.face2.name
            val t = if (side == 0) piece.wedge.t1 else piece.wedge.t2
            capStep(faces, trimmed, piece, at, away, frame, name, t)?.let { out.add(it) }
        }
        return out
    }

    /** One face's own step — see [capSteps]. */
    private fun capStep(
        faces: List<FacePatch>,
        trimmed: List<FacePatch>,
        piece: Piece,
        at: Vec3,
        away: Vec3,
        frame: Placement,
        name: FaceName,
        t: Vec2,
    ): Notch? {
        val patch = faces.firstOrNull { it.name == name } ?: return null
        if (patch.reason != null) return null
        val plane = patch.plane ?: return null
        val v = plane.toLocal(at)
        val head = plane.toLocal(frame.at(t))
        if ((head - v).length() <= Geom3.WELD_TOL) return null
        // **the crease's own direction, in this face's plane** — what the other piece is measured against
        val along = plane.toLocal(at + away) - v
        if (along.length() <= Vec2.EPS) return null
        val d = along.normalized()
        // the two boundary pieces at this corner; the one that is *not* the crease is the one to ask
        val ends = patch.outline.indices.filter { (GeomMath.endOf(patch.outline[it]) - v).length() <= SAME_CURVE_TOL }
        val starts = patch.outline.indices.filter { (GeomMath.startOf(patch.outline[it]) - v).length() <= SAME_CURVE_TOL }
        if (ends.size != 1 || starts.size != 1 || ends[0] == starts[0]) return null
        val other =
            listOf(outgoing(patch.outline[ends[0]], back = true), outgoing(patch.outline[starts[0]], back = false))
                .filterNotNull()
                .minByOrNull { abs(it.dot(d)) } ?: return null
        // **square to the crease means nothing is owed**: the body has no material past the cap there, and
        // the strip the band took is already the boundary the body has
        if (abs(other.dot(d)) <= TANGENT_TOL) return null
        return spliceInto(faces, trimmed, patch, at, piece.crease.edge, piece.sec, listOf(ProfileElement.Seg(Segment(head, v))))
    }

    /**
     * Which way [e] leaves the end of it that a corner stands at — [back] for the piece that *ends* there.
     *
     * The **tangent** and never a chord: [capStep] asks whether this piece leaves square to the crease, and
     * a chord of an arc misses that by half its own sweep — which is how a flat annulus, whose boundary at
     * the rounded edge's end is a *circle* tangent to the cap's own plane, came to be told it had material
     * beyond a cap that in fact grazes it.
     */
    private fun outgoing(
        e: ProfileElement,
        back: Boolean,
    ): Vec2? {
        val sign = if (back) -1.0 else 1.0
        return when (e) {
            is ProfileElement.Seg ->
                (e.segment.b - e.segment.a).let { if (it.length() <= Vec2.EPS) null else it.normalized() * sign }
            is ProfileElement.ArcE -> {
                val a = if (back) e.arc.endAngle else e.arc.startAngle
                val r = Vec2(cos(a), sin(a))
                (if (e.arc.ccw) r.perp() else r.perp() * -1.0) * sign
            }
            else -> {
                // …and for the curves this drawing has no closed tangent for here, a chord short enough to
                // be one: the fallback is never the answer for a rim, which is the case that matters.
                val pts = GeomMath.tessellatePiece(e, 1e-9)
                if (pts.size < 2) {
                    null
                } else {
                    val d = if (back) pts[pts.size - 2] - pts.last() else pts[1] - pts.first()
                    if (d.length() <= Vec2.EPS) null else d.normalized()
                }
            }
        }
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
        edge: SolidEdge,
        sec: BlendSection,
        plainChain: List<ProfileElement>,
        fitted: Double? = null,
        widths: List<Double>? = null,
    ): Notch? {
        val index = faces.indexOfFirst { it.name == face.name }
        if (index < 0) return null
        val patch = faces[index]
        val plane = patch.plane ?: return null
        if (patch.reason != null) return null
        val v = plane.toLocal(at)
        val chain =
            if (widths == null || widths.all { it <= Geom3.WELD_TOL }) {
                plainChain
            } else {
                insetChain(plainChain, v, widths)
                    ?: return Notch(
                        edge,
                        sec,
                        index,
                        0,
                        0,
                        v,
                        plainChain,
                        reason = Msgs.refusalBlendSplicedStripNotStated(name = patch.name.label, name2 = edge.name.label),
                    )
            }
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
            edge,
            sec,
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
     * **A chain a corner or a free end splices into a face, with the strip a rounding of it took**
     * (OP-31, slice 5b) — the one place such a strip can be taken, and why it is here.
     *
     * A trim is a strip of constant width off a boundary **piece**, so two of them compose into one and
     * [correctedOutline] takes each level's own off the level below's answer. The curve a corner splices in
     * is not a piece of that answer at all: it replaces a *corner* of it, at the tip, over the whole chain
     * ([notchesOf]'s own rule, session 81). So a rounding that runs along such a curve — the free end's
     * notch arc, a corner's tangent rail — has its strip taken **here**, with the splice, and
     * [correctedOutline] leaves it alone rather than looking for a piece the trimmed list does not have.
     *
     * The arithmetic is the ordinary exact offset, twice over. Each piece steps onto its own offset carrier
     * ([GeomMath.offsetCarrier]) **away from the corner** — which is where the material is, the corner being
     * the very thing the splice cut off — and where two neighbours are stepped by the *same* width they meet
     * on their carriers exactly as a ring's corners do ([GeomMath.carrierJunction]). Where they are stepped
     * by different widths, or at the two open ends of the chain, the boundary genuinely **steps**: that is
     * the band's own flat end cap standing in the face, square to the crease, so it is stated as the
     * straight run it is and nothing is fitted anywhere.
     *
     * Null where a piece has no offset carrier in this vocabulary or two of them do not meet — and the
     * caller then says so on the face rather than drawing a boundary the body does not have.
     */
    private fun insetChain(
        chain: List<ProfileElement>,
        corner: Vec2,
        widths: List<Double>,
    ): List<ProfileElement>? {
        if (widths.all { it <= Geom3.WELD_TOL }) return chain
        val signed = ArrayList<Double>(chain.size)
        for ((k, e) in chain.withIndex()) {
            val (mid, dir) = midOf(e) ?: return null
            val n = dir.perp()
            if (n.length() <= Vec2.EPS) return null
            val step = n.normalized() * (1e-6 * max(1.0, (mid - corner).length()))
            signed.add(if ((mid + step - corner).length() >= (mid - step - corner).length()) widths[k] else -widths[k])
        }
        val carriers = chain.indices.map { GeomMath.offsetCarrier(chain[it], signed[it]) ?: return null }

        fun foot(
            carrier: Pair<Line?, Circle?>,
            p: Vec2,
        ): Vec2? {
            carrier.second?.let { c ->
                val v = p - c.center
                return if (v.length() <= Vec2.EPS) null else c.center + v.normalized() * c.radius
            }
            val l = carrier.first ?: return null
            return l.origin + l.dir * (p - l.origin).dot(l.dir)
        }
        // the chain's own stations: the two open ends stand square to the crease, and each junction is a
        // meeting of carriers where the two widths agree and a step of the cap's own where they do not
        val heads = ArrayList<Vec2>(chain.size)
        val tails = ArrayList<Vec2>(chain.size)
        for (k in chain.indices) {
            heads.add(
                if (k == 0 || abs(signed[k] - signed[k - 1]) > Geom3.WELD_TOL) {
                    foot(carriers[k], GeomMath.startOf(chain[k])) ?: return null
                } else {
                    GeomMath.carrierJunction(carriers[k - 1], carriers[k], GeomMath.startOf(chain[k])) ?: return null
                },
            )
            if (k > 0 && abs(signed[k] - signed[k - 1]) <= Geom3.WELD_TOL) tails[k - 1] = heads[k]
            tails.add(foot(carriers[k], GeomMath.endOf(chain[k])) ?: return null)
        }
        val out = ArrayList<ProfileElement>(2 * chain.size + 1)
        for (k in chain.indices) {
            if ((heads[k] - (if (k == 0) GeomMath.startOf(chain[0]) else tails[k - 1])).length() > Geom3.WELD_TOL) {
                out.add(ProfileElement.Seg(Segment(if (k == 0) GeomMath.startOf(chain[0]) else tails[k - 1], heads[k])))
            }
            out.add(GeomMath.onCarrier(chain[k], carriers[k], heads[k], tails[k]) ?: return null)
        }
        val last = GeomMath.endOf(chain.last())
        if ((tails.last() - last).length() > Geom3.WELD_TOL) out.add(ProfileElement.Seg(Segment(tails.last(), last)))
        return out
    }

    /**
     * **How wide a strip each piece of a spliced chain has lost** — the tangency, on [face], of every band
     * in the chain whose own crease is the curve [named] identifies, and zero where there is none.
     *
     * Structural and not measured (OP-21): the band's crease *is* the spliced curve, by name, and the
     * setback is the wedge's own tangency on that face. One width per piece of the chain, because a drawn
     * section's notch is one curve per piece and each of them may be rounded on its own.
     */
    private fun insetWidths(
        pieces: List<Piece>,
        face: FacePatch,
        count: Int,
        named: (EdgeName) -> Int?,
    ): List<Double> {
        val out = MutableList(count) { 0.0 }
        for (p in pieces) {
            val k = named(p.crease.edge.name) ?: continue
            if (k !in 0 until count) continue
            val t =
                when (face.name) {
                    p.crease.face1.name -> p.wedge.t1
                    p.crease.face2.name -> p.wedge.t2
                    else -> continue
                }
            out[k] = max(out[k], t.length())
        }
        return out
    }

    /**
     * The **face a free end's cap stands in** and the rigid map that carries the wedge's own section into
     * it — the whole of what a notch and its [EdgeName.BlendNotch] curve are both read from.
     *
     * The face has to be a **plane** of the body that is not one of the band's own two, standing square to
     * the edge with the end point on it, and there has to be exactly one such face — two coplanar
     * candidates is a body whose end the drawing cannot name, and it keeps what it kept (OP-3). The map is
     * rigid because the crease's `e1` and `e2` are both square to the edge and the face's normal **is** the
     * edge, so both of them lie in that plane.
     */
    private fun notchFrame(
        faces: List<FacePatch>,
        piece: Piece,
        at: Vec3,
        away: Vec3,
        frame: Placement = Placement(at, piece.crease.e1, piece.crease.ref.e2),
    ): Pair<Int, Affine>? {
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
        val plane = faces[index].plane ?: return null
        val o = plane.toLocal(at)
        val ax = plane.toLocal(at + frame.cx) - o
        val ay = plane.toLocal(at + frame.cy) - o
        if (abs(ax.x * ay.y - ax.y * ay.x) <= DIR_EPS) return null
        return index to Affine(ax.x, ax.y, ay.x, ay.y, o.x, o.y)
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
        atStart: Boolean,
        pieces: List<Piece>,
        frame: Placement = Placement(at, piece.crease.e1, piece.crease.ref.e2),
    ): Notch? {
        val (index, map) = notchFrame(faces, piece, at, away, frame) ?: return null
        val face = faces[index]
        val plane = face.plane ?: return null
        val v = plane.toLocal(at)
        val before = face.outline.indices.filter { (GeomMath.endOf(face.outline[it]) - v).length() <= SAME_CURVE_TOL }
        val after = face.outline.indices.filter { (GeomMath.startOf(face.outline[it]) - v).length() <= SAME_CURVE_TOL }
        if (before.size != 1 || after.size != 1 || before[0] == after[0]) return null
        val plain = piece.wedge.pieces.map { GeomMath.transform(it, map) }
        // **and the strip a rounding of this very notch curve took off the face** (OP-31, slice 5b): the
        // curve is not a piece of the trimmed list, so the strip is taken here, with the splice
        val widths =
            insetWidths(pieces, face, plain.size) { n ->
                if (n is EdgeName.BlendNotch && n.edge == piece.index && n.atStart == atStart) n.piece else null
            }
        val chain =
            insetChain(plain, v, widths)
                ?: return Notch(
                    piece.crease.edge,
                    piece.sec,
                    index,
                    before[0],
                    after[0],
                    v,
                    plain,
                    reason = Msgs.refusalBlendSplicedStripNotStated(name = face.name.label, name2 = piece.crease.edge.name.label),
                )
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
            bulge = !capStandsIn(face, v, map, piece),
        )
    }

    /**
     * Whether a free end's cap stands **in** the face its plane belongs to, or **past** its corner
     * (OP-31, slice 5d) — which is the one thing that decides whether the splice bites or extends.
     *
     * *The two shapes, and they are the same construction.* A band that ends free closes on a flat cap
     * standing in the plane square to its crease, and the body has a face in that plane at that point —
     * but not necessarily *under* the cap. Where the face's own material lies between the crease's two
     * legs the cap is a **bite**: the corner of the ring is replaced by the wedge, which is session 81's
     * notch and every case it was written for. Where the face lies on the *other* side of one of those
     * legs — the block's own **reflex** plan corner, where the band carves into the leg beside it and the
     * wall it leaves was interior material a moment ago — the cap **extends** the face instead: the same
     * chain, spliced at the same corner, standing past the two ring pieces rather than inside them. The
     * boundary's own arithmetic does not care which ([spliceInto] has said so since item 2); what cared was
     * [meetOnSpan], which demands the junction on the ring piece's **own span** because a fillet's arc is
     * tangent to that piece and its two crossings stand equally far from the corner. A cap that stands past
     * the corner has no such ambiguity — its ends lie on the neighbours' carriers by construction — so the
     * span is the wrong question there and [Notch.bulge] is the flag that says so.
     *
     * *Asked of the wedge and not of the corner.* The face's corner is a right angle in both shapes, so the
     * corner's own angle says nothing; what says it is where the removed material stands. The wedge is
     * **star-shaped from its own corner** (each row of it is one interval between the crease and the blend
     * curve), so a point half a setback out along the bisector of the two legs is inside it at any dihedral
     * and for a drawn profile alike — and whether *that* point is on the face is the whole question, asked
     * of the ring rather than of any bookkeeping.
     */
    private fun capStandsIn(
        face: FacePatch,
        v: Vec2,
        map: Affine,
        piece: Piece,
    ): Boolean {
        val a1 = map.linear(Vec2(1.0, 0.0))
        val a2 = map.linear(Vec2(0.0, 1.0))
        if (a1.length() <= Vec2.EPS || a2.length() <= Vec2.EPS) return true
        val bisector = a1.normalized() + a2.normalized()
        if (bisector.length() <= Vec2.EPS) return true
        val reach = 0.5 * min(piece.wedge.t1.length(), piece.wedge.t2.length())
        if (reach <= Geom3.WELD_TOL) return true
        val rings = Project3.ringsOf(face.outline)
        if (rings.isEmpty()) return true
        return onFace(rings, v + bisector.normalized() * reach)
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
        if (!trimEnd(cut, s1, atHead = true) || !trimEnd(cut, s2, atHead = false)) return null
        return Triple(s1, s2, cut)
    }

    /**
     * [chain]'s own end trimmed back to [at] — and where the trim **consumes** that piece entirely, the
     * piece dropped and the next one tried (OP-31, slice 5b).
     *
     * A chain that already ends where it meets its neighbour is left alone: there is nothing to trim, and a
     * fitted cubic has no offset carrier to be trimmed on (OP-31's cap curve). What is new is the middle
     * case, and it is the ordinary one the moment a spliced chain carries the **step** a band's flat end
     * leaves ([insetChain]): where the neighbouring ring piece has moved in by exactly the width that step
     * is, the step trims to nothing — and a piece the trim consumes is a piece the boundary does not have,
     * not a boundary this drawing cannot state. Dropping it is what lets a rounding of a *whole ribbon*
     * close on one continuous curve while a rounding of the turn **alone** keeps its two steps.
     */
    private fun trimEnd(
        chain: MutableList<ProfileElement>,
        at: Vec2,
        atHead: Boolean,
    ): Boolean {
        while (chain.isNotEmpty()) {
            val k = if (atHead) 0 else chain.size - 1
            val e = chain[k]
            val own = if (atHead) GeomMath.startOf(e) else GeomMath.endOf(e)
            if ((at - own).length() <= SAME_CURVE_TOL) return true
            val carrier = GeomMath.offsetCarrier(e, 0.0) ?: return false
            val cut =
                if (atHead) {
                    GeomMath.onCarrier(e, carrier, at, GeomMath.endOf(e))
                } else {
                    GeomMath.onCarrier(e, carrier, GeomMath.startOf(e), at)
                }
            if (cut != null) {
                chain[k] = cut
                return true
            }
            if (chain.size == 1) return false
            chain.removeAt(k)
        }
        return false
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
        // …and a splice that stands **past** the corner meets its neighbour on that neighbour's own
        // carrier beyond its end, which is the whole of what standing past it means (OP-31, slice 5d):
        // demanding the ring's own span there would refuse the very extension the cap is
        return GeomMath.carrierCrossings(a, b)
            .filter { onSpanOf(end, it) && (bulge || onSpanOf(ring, it)) }
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
            // a fitted crease in space shares a carrier with no boundary piece of a plane: it is in none
            is EdgeGeom.InSpace -> false
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
            is EdgeGeom.InSpace ->
                g.chain.firstOrNull()?.let { first ->
                    listOf(first.start, g.chain.last().end, g.chain[g.chain.size / 2].start)
                } ?: emptyList()
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

    // ---- the canal band: a ball along a crease whose section changes (OP-31, slice 5f) ----

    /**
     * **What a constant-radius ball leaves behind, in one sentence**: the envelope of the spheres of radius
     * `r` centred on a curve is a **pipe surface**, and in the plane normal to that curve at any station the
     * envelope's own characteristic is the **circle of radius `r` about the station** — exact, whatever the
     * spine does. The proof is one line and it is what makes this slice's tool exact rather than fitted: the
     * characteristic of the family `|x − c(s)| = r` is where the sphere meets its own derivative, which for a
     * constant `r` is the plane `(x − c)·c' = 0`, and a sphere cut through its centre is a great circle.
     *
     * So a rounding along a crease whose section **changes** — the elliptic mitre where two equal roundings
     * cross, and its concave twin where two fills do — needs only two things stated: the **spine**, which is
     * the locus of ball centres, and the two **tangency points** at each station, which are where the sphere
     * touches each of the two faces. Both are exact pointwise:
     *
     * - the spine is `{ c : dist(c, F₁) = r and dist(c, F₂) = r }`, two equations in the plane normal to the
     *   crease, solved to machine precision by Newton on the surfaces' own signed distances ([centreAt]).
     *   Where the two faces are equal cylinders whose axes cross it is again a **plane ellipse** — the
     *   mitre's own ellipse scaled by `(R + r)/R` about the point the axes cross, because a homothety about
     *   a point of an axis scales the distance to that axis — and [exactSpine] states it, which is what says
     *   the marched stations are points of an exact curve rather than a fit;
     * - a tangency is the **nearest point of the face to the centre** — a foot on an axis stepped out by the
     *   radius, a foot on a plane — and it lies **in the station's own normal plane** exactly, which is the
     *   second line of the proof: `|p(s) − c(s)| = r` differentiated is `(p − c)·(p' − c') = 0`, and `p − c`
     *   is the face's own normal at `p`, so `(p − c)·p' = 0` and `(p − c)·c' = 0` follows.
     *
     * What the *drawing* is asked to name is fitted and says so (OP-31's Tier B): the two rails are chains of
     * cubics through points every one of which is exact on both the sphere and the face, and the band's own
     * cut is sampled. The **tool** is not: it is the loft of the exact sections, stepped by the loft's own
     * warp rule, and the boolean applies it as it applies every other.
     */
    private class Wall(
        val patch: FacePatch,
        /** The plane this wall is — null for a cylinder. */
        val plane: Plane3?,
        val origin: Vec3,
        val axis: Vec3,
        val radius: Double,
        /**
         * Non-null where this wall is a **circle** rather than a surface — the ring a revolve's cap corner
         * pivots about (OP-31, slice 5h), of this radius about [origin] in the plane square to [axis]. A
         * straight upright is the same thing one dimension down: the axis itself, which is the cylinder
         * below of radius zero.
         */
        val circle: Double? = null,
    ) {
        /**
         * The signed distance from [c] to this wall, in the surface's **own** orientation — a plane's own
         * normal, a cylinder's own outward radial. Which of the two sides the material is on is no business
         * of the surface's: it is a **sign scored once from the body** and carried in the step
         * ([canalChoice]), exactly as every other side-of-the-crease reading in this drawing is.
         */
        fun out(c: Vec3): Double {
            plane?.let { return it.distanceTo(c) }
            val rel = c - origin
            circle?.let {
                val along = rel.dot(axis)
                val d = (rel - axis * along).length() - it
                return sqrt(d * d + along * along) - radius
            }
            return (rel - axis * rel.dot(axis)).length() - radius
        }

        /** The gradient of [out] at [c] — unit, and the direction a point leaves the surface along. */
        fun grad(c: Vec3): Vec3? {
            plane?.let { return it.normal.normalized() }
            val rel = c - origin
            val radial = rel - axis * rel.dot(axis)
            if (radial.length() <= Geom3.WELD_TOL) return null
            circle?.let {
                val v = c - (origin + radial.normalized() * it)
                return if (v.length() <= Geom3.WELD_TOL) null else v.normalized()
            }
            return radial.normalized()
        }

        /** The point of this wall's own surface nearest [c] — the ball's tangency, exact. */
        fun nearest(c: Vec3): Vec3? {
            plane?.let { return c - it.normal.normalized() * it.distanceTo(c) }
            val rel = c - origin
            val along = rel.dot(axis)
            val radial = rel - axis * along
            if (radial.length() <= Geom3.WELD_TOL) return null
            circle?.let { return origin + radial.normalized() * it }
            return origin + axis * along + radial.normalized() * radius
        }
    }

    /** The wall [patch] is — null where it names no surface a ball can be carried along. */
    private fun wallOf(patch: FacePatch): Wall? {
        patch.plane?.let { return Wall(patch, it, it.origin, it.normal.normalized(), 0.0) }
        val s = patch.surface ?: return null
        val band = s.band as? Revolve3.Band.Cylinder ?: return null
        if (band.r <= Geom3.WELD_TOL) return null
        return Wall(patch, null, s.origin, s.axis.normalized(), band.r)
    }

    /**
     * One **station** of a canal band: the ball's centre, the frame of its own normal plane, the two
     * tangencies, and the section the tool carries there.
     */
    private class CanalStation(
        val at: Vec3,
        val t: Vec3,
        val ax: Vec3,
        val ay: Vec3,
        val p1: Vec3,
        val p2: Vec3,
        /** The section's own polygon in `(ax, ay)`, the two tangencies first and the ball's arc last. */
        val poly: List<Vec2>,
        /**
         * The same section with **no step-off at all** (OP-31, slice 5l) — [poly] is the *tool's* ring, its
         * two legs carried [Canal.grow] past the walls so that no face of the tool shares a face with the
         * body ([canalSectionAt]); the face the body is actually left with ends **on** those walls, and a
         * cap stated on the grown ring stands a step-off outside the body it closes.
         */
        val face: List<Vec2>,
        /** How far the ball's arc turns here — zero where the two faces run tangent and the rounding ends. */
        val sweep: Double,
        /** Where the ball's own arc begins, as an angle in `(ax, ay)`. */
        val a1: Double,
        /** Where the crease's own point stands in this plane — the section's apex, on both walls exactly. */
        val apex: Vec2,
        /** How far along the spine this station stands, as a length from the run's start. */
        val s: Double,
        /**
         * Whether the run **tapers to nothing** here: the two walls run tangent, the ball touches both at
         * one point, and the section is shorter than the step-off itself. Measured on the arc's own length
         * rather than on its angle, so the reading is the same at every size.
         */
        val tip: Boolean,
    ) {
        fun world(q: Vec2): Vec3 = at + ax * q.x + ay * q.y

        /**
         * This station **carried along its own tangent** by [by] — the ring the tool really lays there
         * (OP-31, slice 5l). [canalMesh] *moves* a free end's ring by the canal's own step-off rather than
         * doubling it, so over that last stretch the band is this very section translated, its two
         * tangencies with it; and a reading that stops at the station stops [Canal.grow] short of the cap
         * the body has.
         */
        fun carried(by: Double): CanalStation =
            CanalStation(at + t * by, t, ax, ay, p1 + t * by, p2 + t * by, poly, face, sweep, a1, apex, s + abs(by), tip)
    }

    /** A canal band, ready to be swept, named and cut. */
    private class Canal(
        val index: Int,
        val edge: SolidEdge,
        val sec: BlendSection,
        val choice: BlendChoice,
        val r: Double,
        val stations: List<CanalStation>,
        val w1: Wall,
        val w2: Wall,
        /** The crease's own carrier and its pieces' lengths — what a rail and a cut resample the run on. */
        val path: Path3,
        val lens: List<Double>,
        /**
         * Whether the crease **closes on itself** — a pipe tee's weld line, a bored rim's own crossing.
         * A closed run has no free end at all: nothing is capped, the loft wraps, and the two flat-end slots
         * say so rather than claiming a face the body has not got.
         */
        val closed: Boolean,
        /** How many chords the ball's own arc is carried on — one count for every station, so rings stitch. */
        val arcSteps: Int,
        /** How far the fitted rails stand from the truth. */
        val fitted: Double,
        /**
         * How far this canal's tool steps off the two walls it rolls on — **a tessellation-safe micron**
         * (OP-31, slice 5f). See [canalGrow].
         */
        val grow: Double,
        /**
         * How nearly the two walls may run parallel before this drawing may not tell them apart (OP-31,
         * slice 5s) — the crease's own tolerance, read one dimension over. See [tangencyTolOf].
         */
        val tangentTol: Double,
    ) {
        /**
         * **The spine between the stations** — the Catmull–Rom through them, with the tolerance the curve
         * between two of them may stand from the truth, measured ([pipeOf]). It is the curve every reading
         * *between* two stations is seeded on ([canalSpineRawAt]); the band's own carrier is the same
         * interpolant carried one step past each free end, which is as far as the tool goes
         * ([canalBandPatch]).
         */
        val spine: Pipe3 by lazy { pipeOf(stations.map { PipeStation(it.at, it.t, it.ax, it.s) }, r, closed) }

        /**
         * **The run as the body really has it** (OP-31, slice 5l): the solved stations with each free end
         * carried [grow] further, which is exactly as far as the tool goes ([canalMesh] moves the end ring
         * there rather than doubling it) and exactly as far as the band's own carrier runs
         * ([canalBandPatch]). Every reader of the band — its cut, its rails, its cap — is asked on this
         * list, so the band, the neighbours it bites and the cap it closes on all end at one ring.
         */
        val run: List<CanalStation> by lazy {
            if (closed || stations.size < 2) {
                stations
            } else {
                val out = ArrayList<CanalStation>(stations.size + 2)
                // …and an end that **tapers to nothing** is carried nowhere: there is no ring there to
                // move and no cap to meet, the tool closing on the tip's own stepped point instead
                if (!stations.first().tip) out.add(stations.first().carried(-grow))
                for (st in stations) out.add(st.carried(0.0))
                if (!stations.last().tip) out.add(stations.last().carried(grow))
                // …and the arc length is restated from the run's own start, so the chart the cut is marched
                // on and the chart the carrier states are one coordinate
                var s = 0.0
                List(out.size) { k ->
                    if (k > 0) s += (out[k].at - out[k - 1].at).length()
                    CanalStation(out[k].at, out[k].t, out[k].ax, out[k].ay, out[k].p1, out[k].p2, out[k].poly, out[k].face, out[k].sweep, out[k].a1, out[k].apex, s, out[k].tip)
                }
            }
        }

        val name: FaceName get() = FaceName.BlendBand(index, 0)

        val convex: Boolean get() = choice.convex

        /**
         * Whether this entry is the **ruled strip a bevel leaves** rather than the pipe a ball does (OP-31,
         * slice 5n) — the one distinction the shared station machinery makes, and it is read off the section
         * the step recorded rather than stored beside it (OP-1: a kind is structure, and it is already there).
         */
        val bevel: Boolean get() = sec.kind == BlendKind.CHAMFER
    }

    /** How far a canal band's fitted rail or sampled cut may stand from the truth, in mm. */
    private const val CANAL_FIT_TOL_MM = 1e-4

    /**
     * **What fraction of the rectangle a chord and its sagitta span the circular segment between them
     * actually is** — two thirds, exactly, to the order a tessellation sagitta has (OP-31, slice 5s).
     *
     * A parabola through the chord's two ends and its own midpoint encloses `⅔ c h`, and a circular arc is
     * that parabola to `O(h³/c)`. It is what turns a wall's stated tessellation **tolerance** into the area
     * that wall's own skin can really hand back along a leg, which is the figure's own lower bound.
     */
    private const val SEGMENT_OF_ITS_RECTANGLE = 2.0 / 3.0

    /**
     * **How far a canal's tool steps off the walls it rolls on** (OP-31, slice 5f) — and why it is not the
     * micron every other tool in this drawing uses.
     *
     * [sectionOf]'s rule is *a tool never shares a face with the body*, and a micron settles it for an
     * ordinary rounding because an ordinary tool's legs lie in **planar** faces, which a mesh states
     * exactly. A canal's legs lie on *curved* ones, whose triangles stand **inside** the true surface by as
     * much as [GeomMath.effectiveTol] — twenty times the micron. A leg only a micron proud of the true
     * surface is therefore still a fifth of a tessellation tolerance short of where the body's own skin
     * actually is, and the tool's own leg and the body's own facets cross each other in a band as wide as
     * the chords are: the tool's face and the body's face come within microns of each other over a whole
     * strip, and what the boolean answers there is a coincident pair of triangles rather than a crossing.
     *
     * So the step-off is the **body's own skin**: twice the worst tessellation tolerance of the two walls,
     * never less than [GROW_MM]. Twice, because both surfaces are chorded and either may stand a whole
     * tolerance inside its own truth. It is the same number the figure's own bracket already states for the
     * skin ([canalRemoval]), read once and used by both.
     */
    private fun canalGrow(
        w1: Wall,
        w2: Wall,
    ): Double = max(ToolStep.off(w1.radiusOrNull), ToolStep.off(w2.radiusOrNull))

    /** How far [w]'s own triangles may stand inside its true surface — nothing at all where it is a plane. */
    private fun wallSkin(w: Wall): Double =
        if (w.plane != null) 0.0 else GeomMath.effectiveTol(max(w.radius, Geom3.WELD_TOL), GeomMath.TESS_TOL_MM)

    /** The radius the step-off is asked for — null where the wall is a plane, which is [ToolStep.off]'s own word. */
    private val Wall.radiusOrNull: Double? get() = if (plane != null) null else radius

    /**
     * The crease as a **curve in space** for a canal — the two carriers [pathOf] refuses, lifted.
     *
     * Null where the edge is a straight run or has no carrier at all: a straight crease between two named
     * faces has a rigid section and is the catalogue's own business, never this one's.
     */
    private fun canalPath(edge: SolidEdge): Path3? =
        when (val g = edge.geom) {
            is EdgeGeom.OnPlane ->
                when (g.piece) {
                    is ProfileElement.EllipseE, is ProfileElement.EllipticArcE, is ProfileElement.BezierE -> {
                        val closed = g.piece is ProfileElement.EllipseE
                        Intersect3.liftedRun(listOf(g.piece), g.plane, closed).first.takeIf { it.elements.isNotEmpty() }
                    }
                    else -> null
                }
            is EdgeGeom.InSpace -> Path3(g.chain).takeIf { g.chain.isNotEmpty() }
            is EdgeGeom.Straight -> null
        }

    /** A point of [path] at [u] over the whole chain, by the pieces' own lengths. */
    private fun alongPath(
        path: Path3,
        lens: List<Double>,
        u: Double,
    ): Vec3 {
        val total = lens.sum()
        if (total <= Geom3.WELD_TOL) return path.elements.first().start
        var want = u.coerceIn(0.0, 1.0) * total
        for ((k, el) in path.elements.withIndex()) {
            if (want <= lens[k] || k == path.elements.size - 1) {
                return Frames3.pointAt(el, if (lens[k] <= Geom3.WELD_TOL) 0.0 else (want / lens[k]).coerceIn(0.0, 1.0))
            }
            want -= lens[k]
        }
        return path.elements.last().end
    }

    /** One piece's length, sampled — only ever used to spread the stations along the crease. */
    private fun pieceLength(el: Curve3Element): Double {
        if (el is Curve3Element.Seg3) return (el.end - el.start).length()
        if (el is Curve3Element.Arc3) return el.arcLength
        var len = 0.0
        var prev = Frames3.pointAt(el, 0.0)
        for (i in 1..16) {
            val p = Frames3.pointAt(el, i / 16.0)
            len += (p - prev).length()
            prev = p
        }
        return len
    }

    /**
     * The **ball's centre** over crease point [m]: the point of the plane through [m] normal to [tau] that
     * stands `r` from both walls, on the air side of each. Newton on the two signed distances, to machine
     * precision — null where no such point can be reached, which is what says the ball does not fit.
     */
    private fun centreAt(
        w1: Wall,
        w2: Wall,
        m: Vec3,
        tau: Vec3,
        r1: Double,
        r2: Double,
    ): Vec3? {
        val g1 = (w1.grad(m) ?: return null) * (if (r1 >= 0.0) 1.0 else -1.0)
        val g2 = (w2.grad(m) ?: return null) * (if (r2 >= 0.0) 1.0 else -1.0)
        var seed = g1 + g2
        seed -= tau * seed.dot(tau)
        if (seed.length() <= Vec3.EPS) return null
        val ax = seed.normalized()
        val ay = tau.cross(ax).normalized()
        // **where the two faces run tangent the two equations are one**, and the answer is closed: the
        // crease point stepped `r` along their common normal stands `r` from both. That is the run's own
        // **tip** — the rounding tapers to nothing there — and it is stated rather than solved for, because
        // a Newton on two identical equations has no answer to give (OP-31, slice 5f).
        if (g1.cross(g2).length() <= TANGENT_TOL) return m + seed.normalized() * abs(r1)
        var x = 0.0
        var y = 0.0
        repeat(60) {
            val c = m + ax * x + ay * y
            val f1 = w1.out(c) - r1
            val f2 = w2.out(c) - r2
            if (abs(f1) <= ON_WALL_TOL && abs(f2) <= ON_WALL_TOL) return c
            val d1 = w1.grad(c) ?: return null
            val d2 = w2.grad(c) ?: return null
            val a11 = d1.dot(ax)
            val a12 = d1.dot(ay)
            val a21 = d2.dot(ax)
            val a22 = d2.dot(ay)
            val det = a11 * a22 - a12 * a21
            if (abs(det) <= 1e-12) return null
            var dx = (a22 * f1 - a12 * f2) / det
            var dy = (-a21 * f1 + a11 * f2) / det
            // …and the step is held to the ball's own size, so an ill-conditioned station near the tip
            // walks in rather than flying off
            val step = Vec2(dx, dy).length()
            val cap = 2.0 * max(abs(r1), abs(r2))
            if (step > cap) {
                dx *= cap / step
                dy *= cap / step
            }
            x -= dx
            y -= dy
        }
        val c = m + ax * x + ay * y
        return if (abs(w1.out(c) - r1) <= ON_WALL_TOL && abs(w2.out(c) - r2) <= ON_WALL_TOL) c else null
    }

    /**
     * **How near a signed distance must come to nothing for a point to *be* on a wall** — one number for
     * every solve in this machinery, used both to stop the walk and to accept what it reached.
     *
     * It used to be two: the walks stopped at `1e-12`/`1e-13` and their answers were accepted at `1e-9`, so
     * a point that had **already arrived** by the standard its own caller applies kept walking. That is
     * harmless while the arithmetic is exact — a drawing sketched on `XY` has planes at `z = 0` and the
     * residual really is zero — and it is a defect the moment the same drawing is sketched on a plane
     * turned 30° about `y`: the residual is then a nanometre, the walk carries on, and where the wall it is
     * walking on happens to be **parallel to the station's own plane** there is no direction within that
     * plane to walk in, so the solve gives up and the pivot is refused in a pose it builds in (GitHub #36).
     * And the nanometre is not rounding: it is exactly what [apexAt] hands on, since a point taken along
     * the segment from a contact to the apex inherits the apex's own tolerance. One number, so what one
     * solve accepts the next does not reject.
     */
    private const val ON_WALL_TOL = 1e-9

    /** [q] pulled back **onto** [w]'s own surface within the station's plane — Newton on the signed distance. */
    private fun onWall(
        w: Wall,
        place: Placement,
        q0: Vec2,
    ): Vec2? {
        var q = q0
        repeat(20) {
            val f = w.out(place.at(q))
            if (abs(f) <= ON_WALL_TOL) return q
            val g = w.grad(place.at(q)) ?: return null
            val gp = Vec2(g.dot(place.cx), g.dot(place.cy))
            val n2 = gp.dot(gp)
            if (n2 <= 1e-18) return null
            q = q - gp * (f / n2)
        }
        return if (abs(w.out(place.at(q))) <= ON_WALL_TOL) q else null
    }

    /** The point of the station's plane that lies on **both** walls — the crease's own point there, exact. */
    private fun apexAt(
        w1: Wall,
        w2: Wall,
        place: Placement,
        seed: Vec2,
    ): Vec2? {
        var q = seed
        repeat(40) {
            val p = place.at(q)
            val f1 = w1.out(p)
            val f2 = w2.out(p)
            if (abs(f1) <= ON_WALL_TOL && abs(f2) <= ON_WALL_TOL) return q
            val d1 = w1.grad(p) ?: return null
            val d2 = w2.grad(p) ?: return null
            val a11 = d1.dot(place.cx)
            val a12 = d1.dot(place.cy)
            val a21 = d2.dot(place.cx)
            val a22 = d2.dot(place.cy)
            val det = a11 * a22 - a12 * a21
            if (abs(det) <= 1e-12) return null
            q = Vec2(q.x - (a22 * f1 - a12 * f2) / det, q.y - (-a21 * f1 + a11 * f2) / det)
        }
        val p = place.at(q)
        return if (abs(w1.out(p)) <= ON_WALL_TOL && abs(w2.out(p)) <= ON_WALL_TOL) q else null
    }

    /**
     * One **leg** of a station's section: the wall's own trace from the tangency to the apex, sampled at
     * points that are exactly on the surface and each stepped [GROW_MM] off it into the air.
     *
     * *Why the leg is the trace and not a straight line to the apex.* The wall is curved, so a chord between
     * two of its points lies **inside** the material, and a tool whose leg lies inside the body leaves a
     * ridge of material as thick as the chord's own sagitta — a tenth of a millimetre on a 4 mm band, which
     * is not a micron-scale sliver but a visible remnant. So the leg is the trace itself, chorded finely
     * enough that its own sag is under half the step-off ([legStepsFor]) and then stepped out, which is the
     * curved-face reading of the very rule [sectionOf] states for a straight one: *a tool never shares a
     * face with the body*, and it stands **outside** it at a convex crease and inside at a concave one.
     */
    private fun legOf(
        w: Wall,
        place: Placement,
        from: Vec2,
        apex: Vec2,
        steps: Int,
        /** How far to step off the wall, **signed** in the wall's own orientation — see [canalSectionAt]. */
        grow: Double,
    ): List<Vec2>? {
        val out = ArrayList<Vec2>(steps)
        for (i in 1 until steps) {
            val q = onWall(w, place, from + (apex - from) * (i.toDouble() / steps)) ?: return null
            if (grow == 0.0) {
                out.add(q)
            } else {
                val g = w.grad(place.at(q)) ?: return null
                out.add(q + Vec2(g.dot(place.cx), g.dot(place.cy)).normalized() * grow)
            }
        }
        return out
    }

    /** One station's geometry before its section is stated — what the station count is refined on. */
    private class CanalRaw(
        val at: Vec3,
        val t: Vec3,
        val place: Placement,
        val p1: Vec3,
        val p2: Vec3,
        val a1: Double,
        val sweep: Double,
        val apex: Vec2,
    )

    /**
     * The ball's own station over crease parameter [u] — its centre, the frame of its normal plane, the two
     * tangencies and the turn of the arc between them.
     */
    private fun canalRawAt(
        w1: Wall,
        w2: Wall,
        path: Path3,
        lens: List<Double>,
        u: Double,
        r: Double,
        /** Which side of each wall the ball's centre stands on — the crease's own scored sector. */
        s1: Int,
        s2: Int,
        /** How nearly the two offsets may run parallel and still state a direction — see [tangencyTolOf]. */
        tol: Double,
    ): CanalRaw? {
        val h = 1e-5
        val m = alongPath(path, lens, u)
        val mA = alongPath(path, lens, (u - h).coerceIn(0.0, 1.0))
        val mB = alongPath(path, lens, (u + h).coerceIn(0.0, 1.0))
        var tau = mB - mA
        if (tau.length() <= Vec3.EPS) return null
        tau = tau.normalized()
        val c = centreAt(w1, w2, m, tau, s1 * r, s2 * r) ?: return null
        // **the spine's own tangent, and not the crease's**: the characteristic circle of a pipe surface
        // stands square to the *spine*, which is where the two tangencies lie exactly (see [Wall]), and it
        // is read from the two gradients **at this very point** rather than from two neighbouring solves
        // (OP-31, slice 5m — see [spineTangentAt]).
        val t = spineTangentAt(w1, w2, c, tau, tol) ?: tau
        val raw = canalRawFrom(w1, w2, c, t, m) ?: return null
        // **and at a tip the spine states no direction of its own** (OP-31, slice 5s). The two walls run
        // tangent there, so **four** branches of the two offsets' intersection cross at that one point —
        // one per side of each wall's own axis plane — and only one of them faces the material. The cross
        // product that reads the tangent is the difference of two parallel normals there: nothing at all
        // where the crease is **exact**, and the crease's own fitting tolerance where it is **fitted**,
        // whose *direction* is noise and chose the branch. What a tip's direction is, is the crease's: the
        // crease is the trim and it leaves the tip into the material. So a station that comes out a tip is
        // read again on the crease's own tangent, which is the same reading slice 5f states a tip with.
        if (abs(raw.sweep) * r <= GROW_MM) return canalRawFrom(w1, w2, c, tau, m) ?: raw
        return raw
    }

    /**
     * **How nearly two walls must run parallel before this drawing may not tell them apart** (OP-31, slice
     * 5s) — the number [spineTangentAt] declares a tangency at, and it is the **crease's own** tolerance
     * rather than an absolute.
     *
     * *Why an absolute will not do, and what the wrong answer looked like.* A canal's spine is the two
     * offsets' intersection curve and its tangent is `∇f₁ × ∇f₂`; where the two walls run **tangent** —
     * the run's own tip — that cross product vanishes and **four** branches of the intersection cross at
     * one point, one per side of each wall's own axis plane. Only one of them faces the material, and which
     * one is a fact the *crease* states: the crease is the trim, and it leaves the tip toward the material.
     * But the tip is only known as well as the crease's own curve is: where the crease is **exact** (the
     * ellipse two equal rounds cross in) the cross product really is nothing there and the march carries
     * the crease's direction through, which is what a tip is; where it is **fitted** (the quartic two
     * *unlike* rounds cross in, `SolidEdge.fitted`) the tip stands a fitting tolerance off the true
     * crossing, the cross product is that far from nothing rather than nothing at all, and its direction —
     * which is then noise — chose the branch. It chose the wrong one, and the canal along a fitted quartic
     * stood on the **inner** branch of the smaller cylinder.
     *
     * *The number.* Perturbing the point by `δ` turns a cylinder's own gradient by `δ / R`, so the
     * direction of `∇f₁ × ∇f₂` is uncertain by about `(δ / R) / sin θ` and is worth nothing once
     * `sin θ ≲ δ / R` — with `δ` the tolerance the crease's curve is stated to and `R` the **smaller** of
     * the two walls' radii, since a plane's gradient does not move at all. That is the same sentence slice
     * 5m wrote for a curve against a surface — *a crease is stated no tighter than a surface it cannot be
     * told off* — said here for two surfaces against each other. Where the crease is exact the old absolute
     * [TANGENT_TOL] is what is left, so nothing a drawing already builds moves.
     */
    private fun tangencyTolOf(
        edge: SolidEdge,
        w1: Wall,
        w2: Wall,
    ): Double {
        val delta = edge.fitted ?: return TANGENT_TOL
        val least =
            listOfNotNull(
                w1.radius.takeIf { w1.plane == null },
                w2.radius.takeIf { w2.plane == null },
            ).minOrNull() ?: return TANGENT_TOL
        if (least <= Geom3.WELD_TOL) return TANGENT_TOL
        return max(TANGENT_TOL, delta / least)
    }

    /**
     * **The spine's own tangent at [c]** (OP-31, slice 5m) — the cross product of the two offset surfaces'
     * own gradients, read at the point itself, oriented to carry on in [prev]'s direction.
     *
     * The spine is `{ c : dist(c, F₁) = r and dist(c, F₂) = r }`, which is the intersection of the two
     * faces' offset surfaces; the tangent of an intersection curve is `∇f₁ × ∇f₂`, and both gradients are
     * closed forms of the point ([Wall.grad]). That is the whole of why this slice's march is
     * well-conditioned where session 84's fixed point was not: **nothing is read from a neighbour**, so
     * there is no pair of nearly coincident planes for two neighbouring solves to be read in.
     *
     * Null where the two surfaces run **tangent** — the run's own tip, where the rounding tapers to
     * nothing and the intersection is a touching rather than a crossing; the march carries [prev] through
     * it, which is what a tip is: one point of an otherwise ordinary run.
     */
    private fun spineTangentAt(
        w1: Wall,
        w2: Wall,
        c: Vec3,
        prev: Vec3?,
        /** How nearly the two offsets may run parallel and still state a direction — see [tangencyTolOf]. */
        tol: Double,
    ): Vec3? {
        val g1 = w1.grad(c) ?: return null
        val g2 = w2.grad(c) ?: return null
        val x = g1.cross(g2)
        if (x.length() <= tol) return null
        val u = x.normalized()
        return if (prev != null && u.dot(prev) < 0.0) -u else u
    }

    /**
     * The station a **spine point** [c] carries: the frame of its own normal plane, the two tangencies, the
     * crease's own apex and the turn of the ball's arc between them (OP-31, slice 5m).
     *
     * Every one of them is a closed reading of [c] alone — a tangency is the wall's own nearest point, the
     * frame is square to [t], the apex is the one point of the plane that lies on both walls — so a station
     * is a pure function of where the ball's centre stands, whatever parameterisation brought it there.
     */
    private fun canalRawFrom(
        w1: Wall,
        w2: Wall,
        c: Vec3,
        t: Vec3,
        /** Where to look for the crease's own point — a point near it in space, or nothing at all. */
        apexSeed: Vec3?,
    ): CanalRaw? {
        val p1 = w1.nearest(c) ?: return null
        val p2 = w2.nearest(c) ?: return null
        var ax = p1 - c
        ax -= t * ax.dot(t)
        if (ax.length() <= Vec3.EPS) return null
        ax = ax.normalized()
        val place = Placement(c, ax, t.cross(ax))
        val q1 = Vec2((p1 - c).dot(place.cx), (p1 - c).dot(place.cy))
        val q2 = Vec2((p2 - c).dot(place.cx), (p2 - c).dot(place.cy))
        val seed =
            if (apexSeed == null) {
                Vec2(0.0, 0.0)
            } else {
                Vec2((apexSeed - c).dot(place.cx), (apexSeed - c).dot(place.cy))
            }
        val apex = apexAt(w1, w2, place, seed) ?: return null
        val a1 = atan2(q1.y, q1.x)
        val a2 = atan2(q2.y, q2.x)
        var d = a2 - a1
        while (d <= -PI) d += 2.0 * PI
        while (d > PI) d -= 2.0 * PI
        val other = if (d >= 0.0) d - 2.0 * PI else d + 2.0 * PI
        // **which of the two arcs is the rounding**: the one that faces the crease, which is the one whose
        // own midpoint stands on the apex's side of the centre
        val toward = if (apex.length() <= Geom3.WELD_TOL) q1 else apex.normalized()

        fun facing(s: Double): Double {
            val a = a1 + s / 2.0
            return Vec2(cos(a), sin(a)).dot(toward)
        }
        val sweep = if (facing(d) >= facing(other)) d else other
        // the sign is kept: which way the ball's arc runs from tangency to tangency is what the cap's own
        // arc and the section's winding are stated with
        return CanalRaw(c, t, place, p1, p2, a1, sweep, apex)
    }

    /**
     * **The spine, marched on itself** (OP-31, slice 5m) — and why the run is no longer read through the
     * crease's own parameterisation.
     *
     * Slice 5f spread the stations along the **crease** and solved the ball's centre in the crease's normal
     * plane at each. That map is a bijection only while the spine stands nearer the crease than the crease's
     * own centre of curvature; past that the crease's normal planes stop foliating the spine, `u` walks
     * forward, turns at a cusp and comes back over ground it has already covered, and session 84 refused
     * such a rounding by name rather than build the band twice over. The cure is to stop reading the spine
     * through anything but itself.
     *
     * The spine is the intersection of the two faces' **offset surfaces**, each offset by `r` toward the air
     * the ball rolls in — that is what *the centre stands `r` from both faces* says — so:
     *
     * - the **tangent** is `∇f₁ × ∇f₂` ([spineTangentAt]), read from the two gradients at the current point
     *   and from nothing else, which is exactly the ill-conditioning session 84 recorded and could not get
     *   round: *"a fixed point in the spine's tangent was tried and is ill-conditioned, because the two
     *   neighbours `c′` is read from are solved in two nearly coincident planes"* — there are no neighbours
     *   in this reading at all;
     * - a **step** is taken along that tangent and pulled back onto both offsets by Newton ([centreAt], which
     *   solves the two signed distances in the plane square to the tangent), so every station is on the spine
     *   to machine precision however long the run is;
     * - the run **ends** where the ball's contact leaves the faces' own trim, and that is the crease's own
     *   two ends: the crease [canalPath] carries *is* the trimmed edge, and its endpoint lifted to the spine
     *   is where the contact runs off. Those two liftings are well-conditioned — it is the run's *interior*
     *   the crease's foliation loses — so the two ends are stated exactly and the march lands on them rather
     *   than stepping past.
     *
     * A march that cannot reach the far end is the ball failing to fit somewhere along the crease — the two
     * offsets stop meeting — and refuses in the caller's own sentence; there is no partial run with two free
     * ends to be had here, because the crease is the trim and the walls carry it all the way.
     */
    private fun marchSpine(
        w1: Wall,
        w2: Wall,
        path: Path3,
        lens: List<Double>,
        r: Double,
        s1: Int,
        s2: Int,
        closed: Boolean,
        step: Double,
        /** How nearly the two offsets may run parallel and still state a direction — see [tangencyTolOf]. */
        tol: Double,
    ): List<CanalRaw>? {
        if (step <= Geom3.WELD_TOL) return null
        val first = canalRawAt(w1, w2, path, lens, 0.0, r, s1, s2, tol) ?: return null
        val last = if (closed) first else canalRawAt(w1, w2, path, lens, 1.0, r, s1, s2, tol) ?: return null
        val out = ArrayList<CanalRaw>()
        out.add(first)
        var cur = first
        // …and the walk is bounded by the crease's own length: a spine that has marched many times it has
        // lost its way, and that is a refusal rather than a loop that never ends
        val guard = max(16, ceil(8.0 * lens.sum() / step).toInt())
        var k = 0
        var done = false
        while (k < guard) {
            k++
            val c = centreAt(w1, w2, cur.at + cur.t * step, cur.t, s1 * r, s2 * r) ?: return null
            val t = spineTangentAt(w1, w2, c, cur.t, tol) ?: cur.t
            val raw = canalRawFrom(w1, w2, c, t, cur.place.at(cur.apex)) ?: return null
            if (closed) {
                if (k >= 3 && (raw.at - first.at).dot(first.t) >= 0.0 && (raw.at - first.at).length() <= step) {
                    done = true
                    break
                }
            } else if ((raw.at - last.at).dot(last.t) >= 0.0) {
                done = true
                break
            }
            out.add(raw)
            cur = raw
        }
        if (!done) return null
        if (!closed) {
            // the far end is the run's own end and is stated exactly; a station that has crowded up against
            // it is dropped rather than left as a step the loft would have to close on
            while (out.size > 1 && (out.last().at - last.at).length() <= step / 2.0) out.removeAt(out.size - 1)
            out.add(last)
        }
        return if (out.size >= 2) out else null
    }

    /**
     * **How far the true spine stands from the chord between two stations** (OP-31, slice 5m) — the loft's
     * own warp rule, asked of the band rather than tabulated, and the number the step is halved against.
     *
     * The chord's own midpoint is pulled back onto the spine and its station stated there; what is measured
     * is the miss of the centre and of the two tangencies, which is exactly what slice 5f measured when it
     * doubled the crease's station count.
     */
    private fun chordMiss(
        w1: Wall,
        w2: Wall,
        set: List<CanalRaw>,
        r: Double,
        s1: Int,
        s2: Int,
        closed: Boolean,
        /** How nearly the two offsets may run parallel and still state a direction — see [tangencyTolOf]. */
        tol: Double,
    ): Double {
        var worst = 0.0
        for (k in 0 until (if (closed) set.size else set.size - 1)) {
            val a = set[k]
            val b = set[(k + 1) % set.size]
            val mid = (a.at + b.at) * 0.5
            var t = b.at - a.at
            if (t.length() <= Vec3.EPS) continue
            t = t.normalized()
            val c = centreAt(w1, w2, mid, t, s1 * r, s2 * r) ?: continue
            val raw = canalRawFrom(w1, w2, c, spineTangentAt(w1, w2, c, t, tol) ?: t, a.place.at(a.apex)) ?: continue
            worst = max(worst, (raw.at - mid).length())
            worst = max(worst, (raw.p1 - (a.p1 + b.p1) * 0.5).length())
            worst = max(worst, (raw.p2 - (a.p2 + b.p2) * 0.5).length())
        }
        return worst
    }

    /**
     * How many chords a leg needs for its own **sag** to stay under half the step-off — measured on the
     * worst station rather than tabulated, so a flat wall takes one chord and a tight one takes what it
     * needs. A chord's sag falls as `1/n²`, which is what the square root reads.
     */
    private fun legStepsFor(
        w1: Wall,
        w2: Wall,
        raws: List<CanalRaw>,
    ): Int {
        var worst = 0.0
        for (raw in raws) {
            for ((w, from) in listOf(w1 to Vec2((raw.p1 - raw.at).dot(raw.place.cx), (raw.p1 - raw.at).dot(raw.place.cy)), w2 to Vec2((raw.p2 - raw.at).dot(raw.place.cx), (raw.p2 - raw.at).dot(raw.place.cy)))) {
                val mid = (from + raw.apex) * 0.5
                val on = onWall(w, raw.place, mid) ?: continue
                worst = max(worst, (on - mid).length())
            }
        }
        if (worst <= GROW_MM / 2.0) return 1
        return min(64, max(1, ceil(sqrt(worst / (GROW_MM / 2.0))).toInt()))
    }

    /** The section [raw] carries: the two legs off the two walls, the apex between them, the ball's arc. */
    private fun canalSectionAt(
        w1: Wall,
        w2: Wall,
        raw: CanalRaw,
        r: Double,
        legSteps: Int,
        arcSteps: Int,
        s1: Int,
        s2: Int,
        /**
         * How far the section steps off its two walls — **a tool never shares a face with the body**
         * ([sectionOf]). The region is on the `s` side of each wall, so the step is to the *other* side of
         * it: out of the material where the canal is subtracted, into it where it is added. Zero reads the
         * section exactly, which is what the figure's own quadrature integrates.
         */
        grow: Double,
    ): List<Vec2>? {
        val q1 = Vec2(r * cos(raw.a1), r * sin(raw.a1))
        val q2 = Vec2(r * cos(raw.a1 + raw.sweep), r * sin(raw.a1 + raw.sweep))
        val out = ArrayList<Vec2>(2 * legSteps + arcSteps + 2)
        out.add(q1)
        out.addAll(legOf(w1, raw.place, q1, raw.apex, legSteps, -s1 * grow) ?: return null)
        val g1 = w1.grad(raw.place.at(raw.apex)) ?: return null
        val g2 = w2.grad(raw.place.at(raw.apex)) ?: return null
        val outward =
            (
                Vec2(g1.dot(raw.place.cx), g1.dot(raw.place.cy)) * -s1.toDouble() +
                    Vec2(g2.dot(raw.place.cx), g2.dot(raw.place.cy)) * -s2.toDouble()
            ).normalized()
        out.add(raw.apex + outward * grow)
        out.addAll((legOf(w2, raw.place, q2, raw.apex, legSteps, -s2 * grow) ?: return null).reversed())
        out.add(q2)
        for (i in arcSteps - 1 downTo 1) {
            val a = raw.a1 + raw.sweep * i / arcSteps
            out.add(Vec2(r * cos(a), r * sin(a)))
        }
        return out
    }

    /**
     * The whole **canal band** along [edge] — the stations refined until the section's own change between
     * two of them is inside the tessellation tolerance, which is the loft's warp rule and never a fixed
     * count.
     *
     * Null where this edge is no canal case at all (a straight crease, or no carrier), so every rounding the
     * catalogue already builds reaches it unchanged; a pair with a reason is a canal case that cannot be
     * built, and the reason names what stopped it.
     */
    private fun canalOf(
        feature: Feature3,
        edge: SolidEdge,
        index: Int,
        sec: BlendSection,
        choice: BlendChoice,
    ): Pair<Canal?, Msg?>? {
        val path = canalPath(edge) ?: return null
        // **a bevel along such a crease is the ruled strip between the two setback traces** (OP-31, slice
        // 5n), which is the other half of what a crease with no rigid section can carry — see [bevelOf]
        if (sec.kind == BlendKind.CHAMFER) return bevelOf(feature, edge, index, sec, choice, path)
        if (sec.kind != BlendKind.FILLET) return null to Msgs.refusalBlendCarriesNoRigidSection(name = edge.name.label)
        val r = sec.size
        if (r <= Geom3.WELD_TOL) return null
        val faces = Section3.faces(feature).first ?: return null to Msgs.refusalBlendCanalSpineNotFollowed(name = edge.name.label)
        val f1 = faces.firstOrNull { it.name == edge.between.a } ?: return null to Msgs.refusalBlendCanalSpineNotFollowed(name = edge.name.label)
        val f2 = faces.firstOrNull { it.name == edge.between.b } ?: return null to Msgs.refusalBlendCanalSpineNotFollowed(name = edge.name.label)
        val w1 =
            wallOf(f1) ?: return null to
                Msgs.refusalBlendCanalWallNotStatable(name = edge.name.label, name2 = f1.name.label, name3 = f2.name.label)
        val w2 =
            wallOf(f2) ?: return null to
                Msgs.refusalBlendCanalWallNotStatable(name = edge.name.label, name2 = f2.name.label, name3 = f1.name.label)
        val s1 = choice.a
        val s2 = choice.b
        val lens = path.elements.map { pieceLength(it) }
        val notFitting = Msgs.refusalBlendCanalDoesNotFitAlong(sizePhrase = sec.sizePhrase(), name = edge.name.label)

        // **the stations march the spine itself** (OP-31, slice 5m), and the step is the geometry's own:
        // it starts at an eighth of the crease and is halved until the chord between two stations stands
        // inside the tessellation tolerance of the spine it spans ([chordMiss]) — a count derived at build
        // time from the body, stored nowhere and stated in no file (OP-21).
        val closed = (path.start != null && path.end != null && (path.start!! - path.end!!).length() <= Geom3.WELD_TOL)
        val creaseLen = lens.sum()
        if (creaseLen <= Geom3.WELD_TOL) return null to notFitting
        var step = creaseLen / 8.0
        // **and the branch is the crease's own** (OP-31, slice 5s): the two offsets cross four branches at
        // the run's tip, and which of them faces the material is a fact the crease states — read at the
        // tolerance the crease's own curve is stated to ([tangencyTolOf]), never at an absolute
        val tol = tangencyTolOf(edge, w1, w2)
        var set = marchSpine(w1, w2, path, lens, r, s1, s2, closed, step, tol) ?: return null to notFitting
        var halvings = 0
        // …and what is measured is the miss of the set being **replaced**, so the run is always marched one
        // level finer than the level that came inside the tolerance — slice 5f's own doubling, said for a
        // step rather than for a count
        while (halvings < 8 && set.size < 512) {
            val miss = chordMiss(w1, w2, set, r, s1, s2, closed, tol)
            halvings++
            step /= 2.0
            set = marchSpine(w1, w2, path, lens, r, s1, s2, closed, step, tol) ?: return null to notFitting
            if (miss <= GeomMath.TESS_TOL_MM) break
        }
        val arcSteps = max(1, GeomMath.chordSteps(r, set.maxOf { abs(it.sweep) }, GeomMath.TESS_TOL_MM))
        val legSteps = legStepsFor(w1, w2, set)
        val grow = canalGrow(w1, w2)
        // **a closed crease states its last station only once** (OP-31, slice 5f) — the run comes back to
        // where it began, and the march stops one step short of it rather than saying it twice, so the
        // loft wraps on a step of its own length instead of a zero-length one
        val used = set
        val stations = ArrayList<CanalStation>(used.size)
        var s = 0.0
        for ((k, raw) in used.withIndex()) {
            if (k > 0) s += (raw.at - used[k - 1].at).length()
            val tip = abs(raw.sweep) * r <= GROW_MM
            val poly =
                if (tip) {
                    List(2 * legSteps + arcSteps) { Vec2(r * cos(raw.a1), r * sin(raw.a1)) }
                } else {
                    canalSectionAt(w1, w2, raw, r, legSteps, arcSteps, s1, s2, grow) ?: return null to notFitting
                }
            // …and the same section with no step-off, which is the face the body keeps (OP-31, slice 5l)
            val face =
                if (tip) poly else canalSectionAt(w1, w2, raw, r, legSteps, arcSteps, s1, s2, 0.0) ?: return null to notFitting
            stations.add(CanalStation(raw.at, raw.t, raw.place.cx, raw.place.cy, raw.p1, raw.p2, poly, face, raw.sweep, raw.a1, raw.apex, s, tip))
        }
        if (stations.count { !it.tip } < 2) return null to notFitting
        // **and the run goes forward by construction** (OP-31, slice 5m): each step is taken along the
        // spine's own tangent and pulled straight back onto the spine, so there is no parameterisation left
        // to fold. Session 84's *"the crease's normal planes stop foliating the spine once the ball is
        // large against the crease's own bend"* was a property of the reading and never of the band, and
        // the refusal it earned (`refusal.blend.canalBallLargerThanBend`) is retired with its case.
        return Canal(index, edge, sec, choice, r, stations, w1, w2, path, lens, closed, arcSteps, CANAL_FIT_TOL_MM, grow, tol) to null
    }

    /**
     * The canal's **tool**: the loft of its stations, closed at each end.
     *
     * A **tip** station — where the two faces run tangent and the rounding tapers to nothing — is one point
     * and needs no cap: the quads that collapse with it are dropped and what comes out is a cone's apex
     * ([Geom3.MeshBuilder.triangle], which exists for exactly this). The point is drawn back [GROW_MM] along
     * the ball's own normal so that the tool does not *touch* the body there, which is the one contact a
     * general boolean has no answer for. An end that is **not** a tip is capped with its section
     * triangulated, and the cap is stepped a micron past the crease's own end so that it crosses the body
     * transversally instead of standing on the body's own vertex — [endSteps]' rule, said for a loft.
     */
    private fun canalMesh(canal: Canal): Mesh3? {
        val rings = ArrayList<List<Vec3>>()
        val first = canal.stations.first()
        val last = canal.stations.last()
        for ((k, st) in canal.stations.withIndex()) {
            // **each end is stepped a micron past the crease's own end**, and the end station is *moved*
            // there rather than doubled: a ring standing exactly at the end stands on the very point where
            // the ball's own contact runs off the wall it rolls on — a vertex of the body, and a tool
            // vertex on a body vertex is the one contact the general boolean has no answer for
            // ([endSteps]' rule, said for a loft's two ends).
            // …and the step is the canal's **own** step-off ([canalGrow]) and not the bare micron: the
            // vertex the ring would stand on is a vertex of two *curved* faces, whose triangles stand as
            // much as a tessellation tolerance off the truth, so a micron does not clear it (session 84 —
            // a second canal on the same body folded a flap there, and only where the first one had
            // re-triangulated the body's own tangency line)
            val step =
                if (canal.closed) {
                    0.0
                } else if (k == 0) {
                    -canal.grow
                } else if (k == canal.stations.size - 1) {
                    canal.grow
                } else {
                    0.0
                }
            rings.add(
                if (st.tip) {
                    // the tip steps to the tool's own safe side, the same way every leg does: away from
                    // the region, so the tool does not *touch* the body where it has nothing left to take.
                    // A **bevel**'s station already carries that one vertex ([bevelOf]) — the crease point
                    // stepped out along the two walls' own bisector — so it is taken rather than derived.
                    val tip = if (canal.bevel) st.world(st.poly[0]) else st.p1 - (st.at - st.p1) * (canal.grow / canal.r)
                    List(st.poly.size) { tip }
                } else {
                    st.poly.map { st.world(it) + st.t * step }
                },
            )
        }
        if (rings.size < 2) return null
        val tris = ArrayList<Triple<Vec3, Vec3, Vec3>>()
        for (l in 0 until (if (canal.closed) rings.size else rings.size - 1)) {
            val lo = rings[l]
            val hi = rings[(l + 1) % rings.size]
            if (lo.size != hi.size) return null
            for (m in lo.indices) {
                val nx = (m + 1) % lo.size
                tris.add(Triple(lo[m], lo[nx], hi[nx]))
                tris.add(Triple(lo[m], hi[nx], hi[m]))
            }
        }
        if (!canal.closed && !first.tip) {
            val place = Placement(first.at - first.t * canal.grow, first.ax, first.ay)
            for (t in sectionCaps(first.poly)) tris.add(Triple(place.at(t.c), place.at(t.b), place.at(t.a)))
        }
        if (!canal.closed && !last.tip) {
            val place = Placement(last.at + last.t * canal.grow, last.ax, last.ay)
            for (t in sectionCaps(last.poly)) tris.add(Triple(place.at(t.a), place.at(t.b), place.at(t.c)))
        }
        // **the winding is measured rather than argued**: the section's own frame may be a reflection of the
        // world's either way round the run, so the shell is built once and turned inside out if its own
        // signed volume says it is (the one reading a closed mesh always has)
        var six = 0.0
        for (t in tris) six += t.first.dot(t.second.cross(t.third))
        val b = Geom3.MeshBuilder()
        for (t in tris) {
            if (six >= 0.0) b.triangle(t.first, t.second, t.third) else b.triangle(t.first, t.third, t.second)
        }
        return b.build()
    }

    /**
     * A canal section triangulated as a cap, its **boundary running in the polygon's own index order** —
     * which is what makes the cap close on the side quads whichever way round the section's own frame is.
     * [capsOf] states a counter-clockwise ring's triangles, so a clockwise one is triangulated reversed and
     * its triangles turned back.
     */
    private fun sectionCaps(poly: List<Vec2>): List<Geom3.Tri3> {
        var twice = 0.0
        for (k in poly.indices) {
            val a = poly[k]
            val b = poly[(k + 1) % poly.size]
            twice += a.x * b.y - b.x * a.y
        }
        if (twice >= 0.0) return widestEars(poly) ?: capsOf(poly)
        val back = reversedFromFirst(poly)
        return (widestEars(back) ?: capsOf(back)).map { Geom3.Tri3(it.c, it.b, it.a) }
    }

    /**
     * A counter-clockwise ring triangulated by **clipping the widest ear there is** (OP-31, slice 5f) —
     * and the reason a canal's cap needs its own reading rather than the general triangulator's.
     *
     * A canal's section carries its two legs as chords of the walls' own traces, one count for the whole
     * run so that every ring stitches ([legStepsFor]); at the **end** of a run the ball's contact and the
     * crease's own point stand on one ruling of the wall it rolls on, so that station's leg is *straight*
     * and its chords are exactly collinear. An ear clipped at a collinear vertex has no area at all, and a
     * cap of slivers is a mesh no boolean accepts — while a triangulation with no sliver in it always
     * exists as long as the ring is not one straight line, because the collinear run can be spanned from a
     * vertex off it. So each ear is taken by **area**, largest first, rather than in index order; null
     * where no ear can be found at all, and then the general triangulator answers as before.
     */
    private fun widestEars(poly: List<Vec2>): List<Geom3.Tri3>? {
        val ring = ArrayList<Vec2>(poly.size)
        for (q in poly) if (ring.isEmpty() || (q - ring.last()).length() > Geom3.WELD_TOL) ring.add(q)
        while (ring.size > 1 && (ring.first() - ring.last()).length() <= Geom3.WELD_TOL) ring.removeAt(ring.size - 1)
        if (ring.size < 3) return emptyList()
        val live = ArrayList<Int>(ring.indices.toList())
        val out = ArrayList<Geom3.Tri3>(ring.size)
        while (live.size > 3) {
            var at = -1
            var widest = 0.0
            for (k in live.indices) {
                val a = ring[live[(k + live.size - 1) % live.size]]
                val b = ring[live[k]]
                val c = ring[live[(k + 1) % live.size]]
                val twice = (b - a).cross(c - a)
                if (twice <= 0.0 || twice <= widest) continue
                var clear = true
                for (j in live.indices) {
                    if (j == k || j == (k + live.size - 1) % live.size || j == (k + 1) % live.size) continue
                    if (inside(ring[live[j]], a, b, c)) {
                        clear = false
                        break
                    }
                }
                if (clear) {
                    widest = twice
                    at = k
                }
            }
            if (at < 0) return null
            out.add(
                Geom3.Tri3(
                    ring[live[(at + live.size - 1) % live.size]],
                    ring[live[at]],
                    ring[live[(at + 1) % live.size]],
                ),
            )
            live.removeAt(at)
        }
        out.add(Geom3.Tri3(ring[live[0]], ring[live[1]], ring[live[2]]))
        return out
    }

    /** Whether [q] lies within the counter-clockwise triangle `abc`, its own edges counting as within. */
    private fun inside(
        q: Vec2,
        a: Vec2,
        b: Vec2,
        c: Vec2,
    ): Boolean =
        (b - a).cross(q - a) >= 0.0 &&
            (c - b).cross(q - b) >= 0.0 &&
            (a - c).cross(q - c) >= 0.0

    /** The canal's tool as a solid, or the reason there is none. */
    private fun canalTool(canal: Canal): Pair<Solid3?, Msg?> {
        val mesh = canalMesh(canal) ?: return null to Msgs.refusalBlendCanalSpineNotFollowed(name = canal.edge.name.label)
        return Solid3.of(Feature3.MeshBoolean(BoolOp.UNION), mesh) to null
    }

    /**
     * **A station of this canal's spine at [v] of its own length** (OP-31, slice 5m) — the parameterisation
     * every reader of the band asks it in, now that the crease's own is gone.
     *
     * The marched stations state the spine at a sequence of arc lengths; a length between two of them is
     * read by stepping along their chord and then pulling the point back **onto** the spine, which is the
     * same Newton the march itself takes. So the answer is exact on both offset surfaces at every `v`,
     * continuous in `v`, and needs nothing of the crease at all — which is what lets a rail be fitted and
     * the figure be integrated over the spine's own length.
     */
    private fun canalSpineRawAt(
        canal: Canal,
        v: Double,
    ): CanalRaw? {
        val sts = canal.stations
        if (sts.size < 2) return null
        // **the seed rides the spine's own interpolant and the plane is the seed's own** (OP-31, slice 5m),
        // and both halves of that matter: a seed taken on the **chords** between stations and a plane taken
        // from the chord's own direction make the map only `C⁰`, so two samples a ten-thousandth apart either
        // side of a station report a turn the spine has not taken — which is a curvature of nothing at all,
        // and Pappus' own factor is read on exactly that. The interpolant is `C¹` and [spineTangentAt] is a
        // closed reading of the point, so what comes out is a `C¹` parameterisation of an exact curve.
        val u = v.coerceIn(0.0, 1.0) * (if (canal.closed) sts.size.toDouble() else (sts.size - 1).toDouble())
        val seed = canal.spine.centreAt(u)
        val near = sts[min(sts.size - 1, max(0, floor(u).toInt()))]
        val t0 = spineTangentAt(canal.w1, canal.w2, seed, near.t, canal.tangentTol) ?: near.t
        val c = centreAt(canal.w1, canal.w2, seed, t0, canal.choice.a * canal.r, canal.choice.b * canal.r) ?: return null
        val t = spineTangentAt(canal.w1, canal.w2, c, t0, canal.tangentTol) ?: t0
        return canalRawFrom(canal.w1, canal.w2, c, t, near.world(near.apex))
    }

    /**
     * **One station of this entry's run at [v] of its own length**, whichever kind of run it is (OP-31,
     * slice 5n) — the one reading the rails, the figure and the section's own area are all taken through.
     *
     * A canal's is read on the **spine** it was marched on ([canalSpineRawAt]); a bevel's on the **crease**
     * itself, which is its own parameterisation and needs no interpolant at all, because every ruling is a
     * closed reading of the crease point under it.
     */
    private fun stationRawAt(
        canal: Canal,
        v: Double,
    ): CanalRaw? =
        if (canal.bevel) {
            bevelRawAt(canal.w1, canal.w2, canal.path, canal.lens, v, canal.r, canal.choice.a, canal.choice.b)
        } else {
            canalSpineRawAt(canal, v)
        }

    /** Where station [k] of a bevel stands in the crease's own parameter — the count's own even spread. */
    private fun bevelParam(
        canal: Canal,
        k: Int,
    ): Double {
        val n = if (canal.closed) canal.stations.size else canal.stations.size - 1
        return if (n <= 0) 0.0 else k.toDouble() / n
    }

    /**
     * One **rail** of a canal band: the curve the ball's contact traces on wall [side], stated as a chain of
     * cubics through points that are every one of them **exact** on both the sphere and the wall.
     *
     * The knots are exact and the spans between are fitted, which is [fittedChain3]'s own contract and
     * OP-31's Tier B: the value is stated with the tolerance it reached rather than left out of the list.
     */
    private fun canalRail(
        canal: Canal,
        side: Int,
    ): Pair<EdgeGeom?, Double?> {
        val (chain, tol) =
            fittedChain3(canal.fitted) { u ->
                val raw = stationRawAt(canal, u)
                if (raw == null) {
                    null
                } else if (side == 0) {
                    raw.p1
                } else {
                    raw.p2
                }
            } ?: return null to null
        return EdgeGeom.InSpace(chain) to tol
    }

    /**
     * The **tool** a canal band is cut with, as a mesh — the seam a test asserts the tool itself on
     * (OP-31, slice 5f). Null where this edge carries no canal at all.
     */
    internal fun canalToolMesh(
        feature: Feature3,
        edgeIndex: Int,
        sec: BlendSection,
        choice: BlendChoice,
    ): Mesh3? {
        val edge = Section3.edges(feature).first?.getOrNull(edgeIndex) ?: return null
        val canal = canalOf(feature, edge, edgeIndex, sec, choice)?.first ?: return null
        return canalMesh(canal)
    }

    /**
     * **The spine a canal band was marched on** (OP-31, slice 5m) — each station's centre, its own tangent
     * and how far along the run it stands. The seam a test asserts the march itself on: that consecutive
     * tangents never reverse (there is no fold left to find) and that consecutive stations stand within the
     * step the geometry derived. Null where this edge carries no canal at all.
     */
    internal fun canalSpine(
        feature: Feature3,
        edgeIndex: Int,
        sec: BlendSection,
        choice: BlendChoice,
    ): List<Triple<Vec3, Vec3, Double>>? {
        val edge = Section3.edges(feature).first?.getOrNull(edgeIndex) ?: return null
        val canal = canalOf(feature, edge, edgeIndex, sec, choice)?.first ?: return null
        return canal.stations.map { Triple(it.at, it.t, it.s) }
    }

    /**
     * **What a canal band takes off the body**, bracketed by the loft's own chord terms (OP-31, slice 5f).
     *
     * The removal is `∫ A(s)·(1 − κ(s)·x̄(s)) ds` along the spine — Pappus' own factor, written for a section
     * that changes: `A(s)` is the section's **exact** area at each station (the region inside both walls and
     * outside the ball, whose boundary is the ball's own arc and the two walls' own traces, every point of it
     * exact), `κ` is the spine's own curvature and `x̄` the section's centroid measured toward the centre of
     * that curvature. The factor is not a refinement but the volume element itself: a tube swept along a
     * **plane** spine has `dV = (1 − κx) dA ds`, and on this fixture it is worth a fifth of the figure,
     * because the section stands a whole ball-radius out on the *convex* side of a spine whose radius of
     * curvature falls to 2 mm.
     *
     * The tool reaches that integral twice over and in opposite directions: the ball's arc arrives as
     * **chords**, which is *more* area than the section has (the disc inscribed in its own polygon is
     * smaller, so the corner region outside it is bigger), and the run arrives as chords too. So the figure
     * is bracketed between the exact quadrature and the chorded one, each read both as a trapezoid over the
     * tool's own stations and as Simpson's over them — a containment bound in the matrix's own style, never
     * widened to admit a body.
     */
    internal fun canalRemoval(
        feature: Feature3,
        edgeIndex: Int,
        sec: BlendSection,
        choice: BlendChoice,
    ): Pair<Double, Double>? {
        val edge = Section3.edges(feature).first?.getOrNull(edgeIndex) ?: return null
        val canal = canalOf(feature, edge, edgeIndex, sec, choice)?.first ?: return null
        val fineLegs = 256
        val fineArcs = 512
        val h = 1e-4

        fun centre(u: Double): Vec3? = stationRawAt(canal, u)?.at

        fun sliceAt(
            u: Double,
            legs: Int,
            arcs: Int,
        ): Double {
            val raw = stationRawAt(canal, u) ?: return 0.0
            // **a bevel's section is the region between its ruling and the two walls' own traces** (OP-31,
            // slice 5n) — at a plane pair the triangle with two sides `d` and the dihedral `α(s)` between
            // them, `½ d² sin α`, and on a curved wall that triangle plus the wall's own segment. Where it
            // is shallower than the tool's own step-off the tool comes to a point and takes nothing, and the
            // figure says the same.
            val poly =
                if (canal.bevel) {
                    if (bevelDepth(raw) <= canal.grow) return 0.0
                    bevelSectionAt(canal.w1, canal.w2, raw, legs, canal.choice.a, canal.choice.b, 0.0) ?: return 0.0
                } else {
                    if (abs(raw.sweep) * canal.r <= GROW_MM) return 0.0
                    canalSectionAt(canal.w1, canal.w2, raw, canal.r, legs, arcs, canal.choice.a, canal.choice.b, 0.0) ?: return 0.0
                }
            var twice = 0.0
            var mx = 0.0
            var my = 0.0
            for (k in poly.indices) {
                val a = poly[k]
                val b = poly[(k + 1) % poly.size]
                val cross = a.x * b.y - b.x * a.y
                twice += cross
                mx += (a.x + b.x) * cross
                my += (a.y + b.y) * cross
            }
            if (abs(twice) <= 1e-18) return 0.0
            val area = abs(twice) / 2.0
            val centroid = Vec2(mx / (3.0 * twice), my / (3.0 * twice))
            // the spine's own curvature and which way it bends — the circle through three of its points
            val p0 = centre((u - h).coerceIn(0.0, 1.0)) ?: return area
            val p2 = centre((u + h).coerceIn(0.0, 1.0)) ?: return area
            val v1 = p0 - raw.at
            val v2 = p2 - raw.at
            val n = v1.cross(v2)
            if (n.length() <= 1e-18) return area
            val towards = (v2 * v1.dot(v1) - v1 * v2.dot(v2)).cross(n) * (1.0 / (2.0 * n.dot(n)))
            val rho = towards.length()
            if (rho <= Geom3.WELD_TOL) return area
            val dir = towards * (1.0 / rho)
            val x = centroid.x * dir.dot(raw.place.cx) + centroid.y * dir.dot(raw.place.cy)
            return area * (1.0 - x / rho)
        }

        fun quadrature(
            legs: Int,
            arcs: Int,
            simpson: Boolean,
        ): Double {
            val n = canal.stations.size - 1
            if (n < 2) return 0.0
            // **`s` is the spine's own parameter now** (OP-31, slice 5m): a station stands at its own arc
            // length along the spine, so the quadrature's abscissae are those lengths and the weights are
            // the steps between them — one and the same parameterisation, where the crease's station count
            // and the spine's lengths used to be two.
            val total = canal.stations.last().s
            if (total <= Geom3.WELD_TOL) return 0.0
            var sum = 0.0
            for (k in 0 until n) {
                val len = canal.stations[k + 1].s - canal.stations[k].s
                if (len <= 0.0) continue
                // …and the abscissa is the run's **own** parameter: a canal's stations are marched at equal
                // steps along the spine, so its own arc length says where they stand; a bevel's are spread
                // at equal parameter along the crease, and that is what states them (OP-31, slice 5n)
                val u0 = if (canal.bevel) bevelParam(canal, k) else canal.stations[k].s / total
                val u1 = if (canal.bevel) bevelParam(canal, k + 1) else canal.stations[k + 1].s / total
                val a0 = sliceAt(u0, legs, arcs)
                val a1 = sliceAt(u1, legs, arcs)
                sum +=
                    if (simpson) {
                        len * (a0 + 4.0 * sliceAt((u0 + u1) / 2.0, legs, arcs) + a1) / 6.0
                    } else {
                        len * (a0 + a1) / 2.0
                    }
            }
            return sum
        }

        // **and the walls the ball rolls on reach the boolean as chords too**, which no other rounding in
        // this drawing has to allow for: an ordinary tool's legs lie in **planar** faces, which a mesh
        // states exactly, while a canal's lie on *curved* ones whose triangles stand inside the true
        // surface by at most the tessellation's own tolerance ([GeomMath.effectiveTol], the number the
        // chord count is chosen against). So the body's own skin gives back a strip as wide as that
        // tolerance along each leg, and the bracket's lower bound is the exact figure less that strip —
        // derived from the drawing's own rule, never a fudge factor.
        fun strip(
            t1: Double,
            t2: Double,
        ): Double {
            var sum = 0.0
            val n = canal.stations.size - 1
            for (k in 0 until n) {
                val len = canal.stations[k + 1].s - canal.stations[k].s
                if (len <= 0.0) continue
                sum += len * (legStrip(canal, k, t1, t2) + legStrip(canal, k + 1, t1, t2)) / 2.0
            }
            return sum
        }
        val exact = quadrature(fineLegs, fineArcs, true)
        val chorded = quadrature(canal.stations.size, canal.arcSteps, true)
        // **and the strip is a circular segment, not a rectangle** (OP-31, slice 5s). [strip] measures the
        // leg's own length times the tolerance the wall's triangles may stand inside its true surface; what
        // the body actually gives back along that leg is the region between each tessellation chord and its
        // own arc, which is a **circular segment** — two thirds of the rectangle the chord and the sagitta
        // span, exactly (`A = ⅔ c h` to the order a sagitta this small has), and nothing at all at the ends
        // of every chord. So the whole-leg rectangle was half again as much ground as the body can hand
        // back, and the figure's lower bound stood that much too low: on the unlike-size mitre at
        // `r = 0.5` it was **negative**, which is a bracket that admits a body that removes nothing.
        val skin = SEGMENT_OF_ITS_RECTANGLE * strip(wallSkin(canal.w1), wallSkin(canal.w2))
        val stepped = strip(canal.grow, canal.grow)
        if (canal.bevel) {
            // **a bevel's step-off gains the body nothing** (OP-31, slice 5n), and the bracket says so: its
            // two legs are stepped **out** of the region — into air where the strip is taken and into
            // material where it is added — so the tool covers more than the section and removes no more
            // than it, while the **ruling** is not stepped off at all but extended past each rail, which
            // is the same nothing one step further. What the tool really does gain is its two end rings,
            // moved a step past the crease's own ends so that they cross the body rather than stand on its
            // vertices ([canalMesh]) — one section's area times that step, at each free end.
            val ends =
                if (canal.closed) {
                    0.0
                } else {
                    canal.grow * (sliceAt(0.0, fineLegs, fineArcs) + sliceAt(1.0, fineLegs, fineArcs))
                }
            return (min(exact, chorded) - skin) to (max(exact, chorded) + ends)
        }
        return (min(exact, chorded) - skin) to (max(exact, chorded) + stepped)
    }

    /** The area one station's two legs stand to gain or lose to a strip [t1]/[t2] wide along each wall. */
    private fun legStrip(
        canal: Canal,
        k: Int,
        t1: Double,
        t2: Double,
    ): Double {
        val st = canal.stations[k]
        if (st.tip) return 0.0
        val legs = (st.poly.size - canal.arcSteps) / 2
        var l1 = 0.0
        var l2 = 0.0
        for (j in 0 until legs) l1 += (st.poly[j + 1] - st.poly[j]).length()
        for (j in legs until 2 * legs) l2 += (st.poly[j + 1] - st.poly[j]).length()
        return l1 * t1 + l2 * t2
    }

    // ---- the bevel along a crease with no rigid section (OP-31, slice 5n) ----

    /**
     * **A constant-setback chamfer along a crease of changing dihedral is the ruled strip between the two
     * setback traces on the two walls** (OP-31, slice 5n) — session 84's own sentence, built:
     *
     * > *"a constant-setback chamfer along a crease of changing dihedral is the ruled strip between the two
     * > setback traces on the two walls, which is a **loft between two fitted curves** and not the ball's
     * > canal — its own tool, its own cut reader and its own figure."*
     *
     * *What is carried, and from where.* Session 76 decided the convention a chamfer is stated under — **the
     * setback runs along the carrier** — and it holds here word for word: at each station the setback point
     * on wall *i* is the point of wall *i* standing a distance [BlendSection.size] from the crease point,
     * measured **in the plane square to the crease at that station**, along that wall's own trace in it. On
     * a plane wall that is a straight step and on a curved one a step along the wall's own section curve,
     * which is precisely what [FilletMath.setback] does one dimension down — so a chamfer along a *straight*
     * crease between two planes comes out of this construction vertex for vertex, and no dressed body moves.
     *
     * *Why this cannot fold where the canal could.* The canal's stations had to be marched on the **spine**
     * (slice 5m), because the crease's own normal planes stop foliating the spine once the ball is large
     * against the crease's bend. A bevel has no spine: every ruling is read from the crease point alone —
     * apex, frame, two traces — so station → ruling is a reparameterisation of the crease itself and there
     * is nothing in it to fold. What **can** degenerate is the strip: a setback trace is an in-face offset
     * of the crease, so two rulings cross once the setback outruns the wall's own bend or the wall's own
     * extent, and that is stated as what it is — the two rails stop advancing along the run — and refused
     * by name ([Msgs.refusalBlendBevelStripFoldsBack]) rather than built inside out.
     *
     * *The figure.* At each station the removed section is the region between the ruling and the two wall
     * traces, which at a plane pair is the triangle with sides `d`, `d` and the dihedral `α(s)` between them
     * — area `½ d² sin α(s)` — and on a curved wall is that triangle plus the wall's own segment. It is read
     * as the section's exact area and carried through Pappus' own volume element `∫A(s)(1 − κ x̄) ds` over
     * the crease, bracketed by the tessellation exactly as slice 5f brackets the canal ([canalRemoval],
     * which serves both because a station is a station).
     */
    private const val BEVEL_TRACE_STEPS = 32

    /**
     * The point of [w] standing a setback of [d] from the crease point [from], measured **in the station's
     * own plane** along [w]'s own trace in it (OP-31, slice 5n).
     *
     * The walk steps along the trace's own tangent — the perpendicular of the wall's projected gradient —
     * and pulls each step back **onto** the wall ([onWall]), so every point of it is on the surface to
     * machine precision; what is accumulated is the **arc** through each pair rather than their chord (the
     * turn between the two tangents states it), so the distance reached is the trace's own arc length and
     * not a polygon's. A **plane** wall takes one step and the answer is closed.
     *
     * *Which way along the trace.* Away from the crease and into the region the bevel takes: of the two
     * directions the trace offers, the one along which [other]'s own signed distance moves to the region's
     * side ([sOther], the crease's scored sector). Where the two walls run **tangent** in this plane neither
     * direction leaves the other wall and no setback is stated at all — the run's own tip, which the caller
     * reads as a taper rather than as a failure.
     */
    private fun setbackOnWall(
        w: Wall,
        other: Wall,
        place: Placement,
        from: Vec2,
        d: Double,
        sOther: Int,
        /** Which way the run was going on this wall at a neighbouring station — see [bevelRawAt]. */
        hint: Vec3?,
    ): Vec2? {
        if (d <= Geom3.WELD_TOL) return null

        fun traceDir(
            q: Vec2,
            prev: Vec2?,
        ): Vec2? {
            val g = w.grad(place.at(q)) ?: return null
            val gp = Vec2(g.dot(place.cx), g.dot(place.cy))
            if (gp.length() <= Vec2.EPS) return null
            val dir = gp.normalized().perp()
            return if (prev != null && dir.dot(prev) < 0.0) dir * -1.0 else dir
        }
        val start = traceDir(from, null) ?: return null
        val og = other.grad(place.at(from)) ?: return null
        val ogp = Vec2(og.dot(place.cx), og.dot(place.cy))
        if (ogp.length() <= Vec2.EPS) return null
        val lean = start.dot(ogp.normalized())
        // **where the two walls run tangent neither way along this trace leaves the other wall** — the
        // discriminant is even in the step there, so the direction is not the station's to state and is
        // carried in from a neighbour instead ([bevelRawAt]); with no neighbour to carry it, nothing is
        // stated at all
        var dir =
            if (abs(lean) > TANGENT_TOL) {
                if (lean * sOther >= 0.0) start else start * -1.0
            } else {
                val h = hint ?: return null
                val hp = Vec2(h.dot(place.cx), h.dot(place.cy))
                if (hp.length() <= Vec2.EPS) return null
                if (start.dot(hp) >= 0.0) start else start * -1.0
            }
        val n = if (w.plane != null) 1 else BEVEL_TRACE_STEPS
        val h = d / n
        var q = from
        var acc = 0.0
        repeat(n) {
            val nxt = onWall(w, place, q + dir * h) ?: return null
            val nd = traceDir(nxt, dir) ?: return null
            val chord = (place.at(nxt) - place.at(q)).length()
            if (chord <= 1e-15) return null
            val turn = acos(dir.dot(nd).coerceIn(-1.0, 1.0))
            val len = if (turn <= 1e-9) chord else chord * (turn / 2.0) / sin(turn / 2.0)
            if (acc + len >= d) return onWall(w, place, q + (nxt - q) * ((d - acc) / len))
            acc += len
            q = nxt
            dir = nd
        }
        return onWall(w, place, q + dir * (d - acc))
    }

    /**
     * The **bevel's own station** over crease parameter [u]: the crease point itself, the frame of its normal
     * plane, and the two setback points the ruling runs between (OP-31, slice 5n).
     *
     * The frame is re-centred on the crease's **exact** own point — the one point of the plane that lies on
     * both walls ([apexAt]) — rather than on the carrier's, because a fitted crease's carrier is only fitted
     * while its point on the two walls is not. So a bevel along the quartic two unlike rounds cross in is
     * stated to the same precision as one along an ellipse.
     */
    private fun bevelRawAt(
        w1: Wall,
        w2: Wall,
        path: Path3,
        lens: List<Double>,
        u: Double,
        d: Double,
        s1: Int,
        s2: Int,
    ): CanalRaw? {
        bevelRawFrom(w1, w2, path, lens, u, d, s1, s2, null, null)?.let { return it }
        // …and a station where the two walls run **tangent** states no direction of its own, so the run's is
        // carried in from a neighbour: one point of an otherwise ordinary run, read exactly the way slice
        // 5m's march carries its tangent through the canal's own tip
        for (delta in listOf(0.02, 0.06, 0.15)) {
            val v = if (u <= 0.5) min(1.0, u + delta) else max(0.0, u - delta)
            val near = bevelRawFrom(w1, w2, path, lens, v, d, s1, s2, null, null) ?: continue
            val h1 = near.p1 - near.at
            val h2 = near.p2 - near.at
            if (h1.length() <= Vec3.EPS || h2.length() <= Vec3.EPS) continue
            bevelRawFrom(w1, w2, path, lens, u, d, s1, s2, h1.normalized(), h2.normalized())?.let { return it }
        }
        return null
    }

    /** [bevelRawAt]'s own reading, with the two directions the run carries in where a station states none. */
    private fun bevelRawFrom(
        w1: Wall,
        w2: Wall,
        path: Path3,
        lens: List<Double>,
        u: Double,
        d: Double,
        s1: Int,
        s2: Int,
        hint1: Vec3?,
        hint2: Vec3?,
    ): CanalRaw? {
        val h = 1e-5
        val m = alongPath(path, lens, u)
        val mA = alongPath(path, lens, (u - h).coerceIn(0.0, 1.0))
        val mB = alongPath(path, lens, (u + h).coerceIn(0.0, 1.0))
        var tau = mB - mA
        if (tau.length() <= Vec3.EPS) return null
        tau = tau.normalized()
        val g1 = w1.grad(m) ?: return null
        val g2 = w2.grad(m) ?: return null
        var seed = g1 * -s1.toDouble() + g2 * -s2.toDouble()
        seed -= tau * seed.dot(tau)
        if (seed.length() <= Vec3.EPS) return null
        val ax = seed.normalized()
        val seek = Placement(m, ax, tau.cross(ax).normalized())
        // …and where the two walls run **tangent** the two equations of the apex are one and the Newton has
        // nothing to solve, while the crease's own carrier already stands on both walls there — so the
        // carrier states the point and the solve is not asked (OP-31, slice 5n)
        val apex =
            apexAt(w1, w2, seek, Vec2(0.0, 0.0))
                ?: Vec2(0.0, 0.0).takeIf { abs(w1.out(m)) <= ON_WALL_TOL && abs(w2.out(m)) <= ON_WALL_TOL }
                ?: return null
        val place = Placement(seek.at(apex), seek.cx, seek.cy)
        val q1 = setbackOnWall(w1, w2, place, Vec2(0.0, 0.0), d, s2, hint1) ?: return null
        val q2 = setbackOnWall(w2, w1, place, Vec2(0.0, 0.0), d, s1, hint2) ?: return null
        return CanalRaw(place.origin, tau, place, place.at(q1), place.at(q2), 0.0, 0.0, Vec2(0.0, 0.0))
    }

    /**
     * The section a bevel station carries: the **ruling** between the two setback points, and the two walls'
     * own traces back to the crease point between them.
     *
     * The two legs are stepped off the walls exactly as a canal's are ([canalSectionAt]'s own rule — *a tool
     * never shares a face with the body*), and the **ruling is not**: it is the bevel face itself and has to
     * pass through the two rails exactly. It is *extended* by the same step-off at each end instead, so the
     * tool's ruled face crosses each wall transversally a step past the rail rather than standing tangent
     * along it — which is the one contact a general boolean has no answer for.
     */
    private fun bevelSectionAt(
        w1: Wall,
        w2: Wall,
        raw: CanalRaw,
        legSteps: Int,
        s1: Int,
        s2: Int,
        grow: Double,
    ): List<Vec2>? {
        val q1 = localOf(raw, raw.p1)
        val q2 = localOf(raw, raw.p2)
        val ruling = q2 - q1
        if (ruling.length() <= Geom3.WELD_TOL) return null
        val u = ruling.normalized()
        val out = ArrayList<Vec2>(2 * legSteps + 1)
        out.add(q1 - u * grow)
        out.addAll(legOf(w1, raw.place, q1, raw.apex, legSteps, -s1 * grow) ?: return null)
        val g1 = w1.grad(raw.place.at(raw.apex)) ?: return null
        val g2 = w2.grad(raw.place.at(raw.apex)) ?: return null
        val outward =
            (
                Vec2(g1.dot(raw.place.cx), g1.dot(raw.place.cy)) * -s1.toDouble() +
                    Vec2(g2.dot(raw.place.cx), g2.dot(raw.place.cy)) * -s2.toDouble()
            ).normalized()
        out.add(raw.apex + outward * grow)
        out.addAll((legOf(w2, raw.place, q2, raw.apex, legSteps, -s2 * grow) ?: return null).reversed())
        out.add(q2 + u * grow)
        return out
    }

    /** A world point of [raw]'s own station plane, in that plane's own coordinates. */
    private fun localOf(
        raw: CanalRaw,
        p: Vec3,
    ): Vec2 = Vec2((p - raw.at).dot(raw.place.cx), (p - raw.at).dot(raw.place.cy))

    /**
     * **How deep the bevel bites at this station** — the crease point's own distance from the ruling, which
     * is what falls to nothing where the two walls run tangent and the strip lies in their common plane.
     */
    private fun bevelDepth(raw: CanalRaw): Double {
        val q1 = localOf(raw, raw.p1)
        val q2 = localOf(raw, raw.p2)
        val v = q2 - q1
        if (v.length() <= Geom3.WELD_TOL) return 0.0
        return abs(v.cross(raw.apex - q1)) / v.length()
    }

    /** How far the true strip stands from the chord between two stations — the count's own refinement rule. */
    private fun bevelChordMiss(
        w1: Wall,
        w2: Wall,
        path: Path3,
        lens: List<Double>,
        d: Double,
        s1: Int,
        s2: Int,
        us: List<Double>,
        set: List<CanalRaw>,
    ): Double {
        var worst = 0.0
        for (k in 0 until set.size - 1) {
            val a = set[k]
            val b = set[k + 1]
            val raw = bevelRawAt(w1, w2, path, lens, (us[k] + us[k + 1]) / 2.0, d, s1, s2) ?: continue
            worst = max(worst, (raw.at - (a.at + b.at) * 0.5).length())
            worst = max(worst, (raw.p1 - (a.p1 + b.p1) * 0.5).length())
            worst = max(worst, (raw.p2 - (a.p2 + b.p2) * 0.5).length())
        }
        return worst
    }

    /** Where the stations stand along the crease — its own parameter, and a closed run says its last once. */
    private fun stationParams(
        n: Int,
        closed: Boolean,
    ): List<Double> = if (closed) (0 until n).map { it.toDouble() / n } else (0..n).map { it.toDouble() / n }

    /**
     * The whole **bevel strip** along [edge], as an ordinary entry of an ordinary dressing (OP-31, slice 5n).
     *
     * It comes back as a [Canal] because a station is a station: once the ruling stands in the station's own
     * polygon where the ball's arc would, the loft, the two caps, the strip a neighbouring band is ended by
     * and Pappus' own quadrature are the very same code. What differs is the four readings a *surface* is
     * asked for — the band patch, the rails, the section's own polygon and the cut — and each of those asks
     * [Canal.bevel].
     */
    private fun bevelOf(
        feature: Feature3,
        edge: SolidEdge,
        index: Int,
        sec: BlendSection,
        choice: BlendChoice,
        path: Path3,
    ): Pair<Canal?, Msg?> {
        val d = sec.size
        if (d <= Geom3.WELD_TOL) return null to Msgs.refusalBlendThisHasNoSizeAll(word = sec.kind.word)
        val notFollowed = Msgs.refusalBlendBevelTraceNotFollowed(name = edge.name.label)
        val faces = Section3.faces(feature).first ?: return null to notFollowed
        val f1 = faces.firstOrNull { it.name == edge.between.a } ?: return null to notFollowed
        val f2 = faces.firstOrNull { it.name == edge.between.b } ?: return null to notFollowed
        val w1 =
            wallOf(f1) ?: return null to
                Msgs.refusalBlendBevelWallNotStatable(name = edge.name.label, name2 = f1.name.label, name3 = f2.name.label)
        val w2 =
            wallOf(f2) ?: return null to
                Msgs.refusalBlendBevelWallNotStatable(name = edge.name.label, name2 = f2.name.label, name3 = f1.name.label)
        val s1 = choice.a
        val s2 = choice.b
        val lens = path.elements.map { pieceLength(it) }
        val notFitting = Msgs.refusalBlendBevelDoesNotFitAlong(sizePhrase = sec.sizePhrase(), name = edge.name.label)
        if (lens.sum() <= Geom3.WELD_TOL) return null to notFitting
        val closed = (path.start != null && path.end != null && (path.start!! - path.end!!).length() <= Geom3.WELD_TOL)
        // **the count is the traces' own** (OP-21): it starts at eight and is doubled until the chord between
        // two stations stands inside the tessellation tolerance of the strip it spans — derived at build time
        // from the geometry, stored nowhere and stated in no file
        var n = 8
        var us = stationParams(n, closed)
        var set = us.map { bevelRawAt(w1, w2, path, lens, it, d, s1, s2) ?: return null to notFitting }
        var halvings = 0
        while (halvings < 6 && n < 512) {
            val miss = bevelChordMiss(w1, w2, path, lens, d, s1, s2, us, set)
            halvings++
            n *= 2
            us = stationParams(n, closed)
            set = us.map { bevelRawAt(w1, w2, path, lens, it, d, s1, s2) ?: return null to notFitting }
            if (miss <= GeomMath.TESS_TOL_MM) break
        }
        // **the strip degenerates where a setback outruns the wall's own bend** — a setback trace is an
        // in-face offset of the crease, and an offset develops a cusp once the offset reaches the trace's own
        // centre of curvature. Said as the strip's own fact: two neighbouring rulings cross, which is the two
        // rails ceasing to advance along the run.
        for (k in 0 until set.size - 1) {
            val a = set[k]
            val b = set[k + 1]
            val t = b.at - a.at
            if (t.length() <= Geom3.WELD_TOL) return null to notFitting
            if ((b.p1 - a.p1).dot(t) <= 0.0 || (b.p2 - a.p2).dot(t) <= 0.0) {
                return null to Msgs.refusalBlendBevelStripFoldsBack(sizePhrase = sec.sizePhrase(), name = edge.name.label)
            }
        }
        val legSteps = legStepsFor(w1, w2, set)
        val grow = canalGrow(w1, w2)
        val stations = ArrayList<CanalStation>(set.size)
        var s = 0.0
        for ((k, raw) in set.withIndex()) {
            if (k > 0) s += (raw.at - set[k - 1].at).length()
            val poly = bevelSectionAt(w1, w2, raw, legSteps, s1, s2, grow) ?: return null to notFitting
            // …and the same ruling with no step-off, which is the face the body keeps (OP-31, slice 5l)
            val face = bevelSectionAt(w1, w2, raw, legSteps, s1, s2, 0.0) ?: return null to notFitting
            // …and where the two walls run tangent the strip lies **in** their common plane and takes
            // nothing at all: the ring collapses on the one vertex that already stands clear of the body,
            // exactly as a canal's tip does, so the tool comes to a point instead of laying a flat face on a
            // flat face
            val tip = bevelDepth(raw) <= grow
            stations.add(
                CanalStation(
                    raw.at, raw.t, raw.place.cx, raw.place.cy, raw.p1, raw.p2,
                    if (tip) List(poly.size) { poly[legSteps] } else poly,
                    if (tip) List(poly.size) { poly[legSteps] } else face,
                    0.0, 0.0, raw.apex, s, tip,
                ),
            )
        }
        if (stations.count { !it.tip } < 2) return null to notFitting
        return Canal(index, edge, sec, choice, d, stations, w1, w2, path, lens, closed, 1, CANAL_FIT_TOL_MM, grow, TANGENT_TOL) to null
    }

    /**
     * The bevel strip as a face of the dressed body — **a ruled strip**, which is neither a plane, nor a
     * surface of revolution, nor the pipe a canal band is (OP-31, slice 5n).
     *
     * It carries no carrier: a general boolean has no fifth one yet, and the slot says so by name where one
     * is asked for ([FacePatch.ruled]). Its two rails are in the edge list as fitted chains with the
     * tolerance they reached, and its own cut is read through the rulings ([canalCut], marched on the
     * strip's own `(station, t)` chart).
     */
    private fun bevelBandPatch(canal: Canal): FacePatch {
        // **the strip runs as far as the tool did**, which is [Canal.grow] past each free end — the same
        // reading a canal band's pipe takes of its own surface ([canalBandPatch]): the loft's end ring is
        // *moved* there rather than doubled, so over that last step the strip is the last ruling carried
        // straight along the run's own direction. Two extra rulings state exactly that, and without them the
        // body's own cap facet stands on a surface the carrier has already ended (OP-31, slice 5r).
        val ends = if (canal.closed) 0.0 else canal.grow
        val rs = ArrayList<Ruling3>(canal.stations.size + 2)
        val first = canal.stations.first()
        val last = canal.stations.last()
        if (!canal.closed) rs.add(Ruling3(first.p1 - first.t * ends, first.p2 - first.t * ends, 0.0))
        for (st in canal.stations) rs.add(Ruling3(st.p1, st.p2, st.s + ends))
        if (!canal.closed) rs.add(Ruling3(last.p1 + last.t * ends, last.p2 + last.t * ends, last.s + 2.0 * ends))
        val strip = if (rs.size >= 2) ruledOf(rs, canal.closed) else null
        return FacePatch(
            canal.name,
            null,
            emptyList(),
            Msgs.refusalBlendBevelStripIsNotPlane(name = canal.name.label, sizePhrase = canal.sec.sizePhrase(), name2 = canal.edge.name.label),
            null,
            if (strip == null) canal.fitted else max(canal.fitted, strip.fitted),
            null,
            false,
            true,
            strip,
        )
    }

    /**
     * **A ruled strip from the rulings its builder solved** (OP-31, slice 5r), with the tolerance an
     * interpolated ruling may stand from the true one — measured rather than asserted, by exactly the
     * reading [pipeOf] takes of a spine: the same Catmull–Rom built through **half** the rulings is asked
     * for the ones it skipped, and the worst miss of either rail is carried.
     */
    private fun ruledOf(
        rulings: List<Ruling3>,
        closed: Boolean,
    ): Ruled3 {
        var worst = 0.0
        if (rulings.size >= 9) {
            val coarse = Ruled3(rulings.filterIndexed { i, _ -> i % 2 == 0 }, closed, 0.0)
            for (i in 3 until rulings.size - 3) {
                if (i % 2 == 1) {
                    worst = max(worst, (coarse.railA(i / 2.0) - rulings[i].a).length())
                    worst = max(worst, (coarse.railB(i / 2.0) - rulings[i].b).length())
                }
            }
        }
        return Ruled3(rulings, closed, max(worst, 1e-12))
    }

    // ---- the canal corner: the ball pivoting about a slanted or a ring upright (OP-31, slice 5h) ----

    /**
     * **What the ball does at an inside corner whose upright is not one straight run square to the shared
     * face**, in one sentence: it keeps its centre `r` from that face and `r` from the **upright**, and the
     * surface it leaves between the two band ends is the pipe of the ball along that locus.
     *
     * *Two roles that coincide along a crease and part company at a corner, which is the slice's own
     * finding.* Slice 5f's canal has **one** pair of walls doing both jobs: the spine is where the ball
     * stands `r` from both of them, and the section is what is inside both of them and outside the ball.
     * Here they are different pairs:
     *
     * - **the spine** is set by the shared face and the **upright** — a plane against a cylinder about a
     *   leaning axis is an **ellipse**, a plane against the torus about a **ring** is the spiric quartic a
     *   plane cuts a torus in, and the same 1-D solve follows both without knowing which it is on. The two
     *   contacts lie in the station's own normal plane exactly, the upright's for the same reason a face's
     *   does: differentiating `|p − c|² = r²` with `p` held on the upright gives `(p − c)·p′ = 0`, and
     *   `p − c` is square to the upright, so `(p − c)·c′ = 0` follows;
     * - **the section** is closed by the shared face and the **near face** — the one of the pair's two other
     *   faces whose own crease still runs on past the band's end. Both of them contain the upright, so both
     *   cut the station plane in a line through the contact; the *near* one's real half leaves the contact
     *   toward the crease and meets the shared face's trace at the crease point itself, which is the apex
     *   [apexAt] looks for. The far one's ray bounds nothing here: the region taken with it is the region
     *   taken with the near one plus the void wedge between them.
     *
     * *And the spine joins each band's own spine C¹, exactly*, which is what makes the two end rings the
     * bands' own end sections rather than a fit: the upright lies **in** the band's other face, and a sphere
     * tangent to a plane touches it at one point only — so at the station where the rolling ball first
     * reaches the upright its contact with the upright *is* its tangency with that face. Same contact, same
     * gradient, same tangent, same normal plane, same great circle.
     *
     * *Where the near face changes over.* At the one station whose plane contains the whole upright the two
     * faces' traces coincide along it and the apex is the corner's own vertex; before it the near face is
     * the first band's, after it the second's, and the section is continuous through the change because the
     * leg is the same leg on both sides of it. The apex sweeps exactly the two slivers of crease the bands'
     * tangencies leave behind — the corner's own business, and the reason this is a corner and not two runs.
     */
    private class CornerStation(
        val at: Vec3,
        val t: Vec3,
        val ax: Vec3,
        val ay: Vec3,
        /** The tangency on the shared face — exact. */
        val pF: Vec3,
        /** The contact on the upright — exact, and in this station's own plane. */
        val pU: Vec3,
        val poly: List<Vec2>,
        val a1: Double,
        val sweep: Double,
        val apex: Vec2,
        /** Which of the pair's two other faces closes the section here. */
        val nearA: Boolean,
        /** How far along the spine this station stands, as a length from the first band's end. */
        val s: Double,
    ) {
        fun world(q: Vec2): Vec3 = at + ax * q.x + ay * q.y
    }

    /** The pivot between two band ends about an upright the circle cannot follow, ready to be swept. */
    private class CanalTurn(
        val ai: Int,
        val aAtStart: Boolean,
        val bi: Int,
        val bAtStart: Boolean,
        val placeA: Placement,
        val placeB: Placement,
        val shared: FacePatch,
        val corner: Vec3,
        val stations: List<CornerStation>,
        val r: Double,
        val sec: BlendSection,
        val convex: Boolean,
        val grow: Double,
        val arcSteps: Int,
        val legSteps: Int,
        val wF: Wall,
        val wA: Wall,
        val wB: Wall,
        val wD: Wall,
        val fitted: Double,
        /** What a station is a pure function of — kept so the figure can re-read the pivot finely. */
        val frame: CornerFrame?,
        /**
         * What the two bands **give up** to this corner: each one's own wedge area times the length of
         * crease between the station the ball first touches the upright at and the corner's own vertex.
         *
         * Exact, and a prism's figure rather than a measurement: a band along a straight crease carries a
         * rigid section, so the piece of it the corner takes over is that section times that length. It is
         * carried here so the pivot can state what it is worth **against the two bands run whole**, which
         * is the one figure a test can measure without building a body that does not exist.
         */
        val tail: Double,
        /** How far past each band's own cap this pivot's tool reaches — see [canalTurnOf]. */
        val endStep: Pair<Double, Double>,
        /** The two planes the section's **fourth side** is cut off at, one per band — see [CapCut]. */
        val cutA: CapCut?,
        val cutB: CapCut?,
    ) : Corner {
        override val ends: List<Pair<Int, Boolean>> get() = listOf(ai to aAtStart, bi to bAtStart)

        override fun ringAt(end: Pair<Int, Boolean>): Placement = if (end.first == ai && end.second == aAtStart) placeA else placeB

        /**
         * **The corner's own surface is not in this mesh**, and that is stated rather than forgotten: a
         * canal corner's section changes from one end of the pivot to the other, so its rings carry neither
         * band's point count and it cannot be stitched into the group's tube shell. It is its own tool and
         * its own boolean, exactly as slice 5f's canal band is — and what this contributes here is the two
         * **caps** that close the tubes it ends, each wound the way that band's own free-end cap would be.
         */
        override fun emit(
            pieces: List<Piece>,
            out: Geom3.MeshBuilder,
        ) {
            for (end in ends) {
                val piece = pieces[end.first]
                val p = ringAt(end)
                for (t in piece.caps) {
                    if (end.second) out.triangle(p.at(t.c), p.at(t.b), p.at(t.a)) else out.triangle(p.at(t.a), p.at(t.b), p.at(t.c))
                }
            }
        }

        override fun label(pieces: List<Piece>): Msg =
            Msgs.refusalBlendInsideCornerWhereMeets(
                name = pieces[ai].crease.edge.name.label,
                name2 = pieces[bi].crease.edge.name.label,
                name3 = shared.name.label,
            )

        override fun faces(
            pieces: List<Piece>,
            nameAt: (Int) -> FaceName,
        ): List<FacePatch> {
            val name = nameAt(0)
            // **a pivot's corner face is a canal too** (OP-31, slice 5h), so it is the same carrier the
            // band along a run is (slice 5l): the pipe of the ball along the spine the shared face and the
            // upright set, charted in the very `(arc, station)` the corner's own reader already marches.
            val pipe = pipeOf(stations.map { PipeStation(it.at, it.t, it.ax, it.s) }, r, false)
            return listOf(
                FacePatch(
                    name,
                    null,
                    pipeTrim(pipe, stations.map { it.a1 to it.sweep }, false),
                    Msgs.refusalBlendCornerCanalIsNotPlane(
                        name = name.label,
                        sizePhrase = sec.sizePhrase(),
                        name2 = pieces[ai].crease.edge.name.label,
                        name3 = pieces[bi].crease.edge.name.label,
                    ),
                    null,
                    max(fitted, pipe.fitted),
                    pipe,
                ),
            )
        }
    }

    /**
     * The **upright** the pair pivots about, as a carrier a distance can be measured to — a straight run
     * where the two other faces are planes, a **ring** where one is a cylinder and the other cuts it square
     * to its own axis. Null where the crossing is a curve this drawing states no distance to.
     *
     * It is read off the two **faces** rather than hunted for among the edges, which is the same structural
     * reading [axisLiesIn] makes: the upright *is* where the pair's two other faces cross, and the corner
     * stands on it.
     */
    private fun uprightCarrier(
        fa: FacePatch,
        fb: FacePatch,
        at: Vec3,
    ): Wall? {
        val pa = fa.plane
        val pb = fb.plane
        if (pa != null && pb != null) {
            val dir = pa.normal.normalized().cross(pb.normal.normalized())
            if (dir.length() <= Vec3.EPS) return null
            return Wall(fa, null, at, dir.normalized(), 0.0)
        }
        val cyl = if (pa == null) fa else fb
        val plane = (if (pa == null) pb else pa) ?: return null
        val s = cyl.surface ?: return null
        val band = s.band as? Revolve3.Band.Cylinder ?: return null
        if (band.r <= Geom3.WELD_TOL) return null
        val axis = s.axis.normalized()
        if (abs(plane.normal.normalized().dot(axis)) < 1.0 - TANGENT_TOL) return null
        val origin = s.origin + axis * (plane.origin - s.origin).dot(axis)
        return Wall(cyl, null, origin, axis, 0.0, band.r)
    }

    /** The ball's own centre at station [s] of [piece]'s run — the wedge's own arc centre, placed. */
    private fun ballCentreAt(
        piece: Piece,
        s: Double,
    ): Vec3? {
        val arc = piece.wedge.pieces.singleOrNull() as? ProfileElement.ArcE ?: return null
        val place = placeAt(piece, s) ?: return null
        return place.at(arc.arc.center)
    }

    /**
     * Where along [piece]'s own run the rolling ball first **touches** the upright [wD] — the station the
     * band ends at and the pivot begins at, solved rather than assumed.
     *
     * Monotone and therefore bisected: the upright lies in the band's other face, so the distance from the
     * centre to it is `√(r² + d²)` with `d` the distance, *within that face*, from the ball's own tangency
     * to the upright — and `d` grows linearly along the run. The root is where `d` is zero, which is exactly
     * where the tangency runs off the face.
     */
    private fun touchStation(
        piece: Piece,
        wD: Wall,
        atStart: Boolean,
        r: Double,
    ): Double? {
        val len = piece.length
        if (len <= Geom3.WELD_TOL) return null

        // **the distance to the upright is never less than `r`**, so there is no sign to bisect on: the
        // ball is tangent to the band's other face and the upright lies *in* that face, so
        // `dist(c, d)² = r² + d²` with `d` the distance, within that face, from the tangency to the
        // upright. The station wanted is where that distance is **least** — where `d` is nothing at all
        // and the tangency runs off the face — so it is a minimum that is sought and not a root, and the
        // minimum of `√(r² + d²)` in a linear `d` is unimodal, which is what a ternary search asks for.
        fun at(u: Double): Double? = ballCentreAt(piece, if (atStart) u else len - u)?.let { wD.out(it) }
        var lo = 0.0
        var hi = len
        repeat(120) {
            val m1 = lo + (hi - lo) / 3.0
            val m2 = hi - (hi - lo) / 3.0
            val f1 = at(m1) ?: return null
            val f2 = at(m2) ?: return null
            if (f1 <= f2) hi = m2 else lo = m1
        }
        val u = (lo + hi) / 2.0
        // …and the least distance has to **be** `r`: where the ball never comes that near, the upright
        // leans away from this band and is not reached along it at all, and the pivot is refused rather
        // than the band ended at a station it does not touch. Zero is an ordinary answer — where the
        // upright stands in a plane square to the crease, as a revolve's own ring does, the ball touches
        // it at the corner itself and the band is not shortened at all.
        if (u >= len - Geom3.WELD_TOL) return null
        val least = at(u) ?: return null
        if (abs(least - r) > GeomMath.TESS_TOL_MM) return null
        return u
    }

    /**
     * One band's own **cap plane** as a cut for the pivot's fourth side (OP-31, slice 5o) — the plane square
     * to that band's crease at station [where], turned so that [CapCut.out] points **away** from the corner
     * [at]. It is the band's own end section carried along the crease: no constant is tuned into it and
     * nothing about it is this pivot's, which is why the same reading serves a straight crease and a curved
     * one and every ring radius alike.
     */
    private fun capCutOf(
        piece: Piece,
        atStart: Boolean,
        where: Double,
        at: Vec3,
    ): CapCut? {
        val place = placeAt(piece, if (atStart) where else piece.length - where) ?: return null
        val n = place.cx.cross(place.cy)
        if (n.length() <= Vec3.EPS) return null
        val out = n.normalized()
        val origin = place.at(Vec2(0.0, 0.0))
        return CapCut(origin, if ((at - origin).dot(out) <= 0.0) out else -out)
    }

    /** One station of the pivot before its section is stated — what the station count is refined on. */
    private class CornerRaw(
        val at: Vec3,
        val t: Vec3,
        val place: Placement,
        val pF: Vec3,
        val pU: Vec3,
        val a1: Double,
        val sweep: Double,
        val apex: Vec2,
        val nearA: Boolean,
    )

    /** Everything the pivot is read from, gathered once so a station is a pure function of an angle. */
    private class CornerFrame(
        val wF: Wall,
        val wA: Wall,
        val wB: Wall,
        val wD: Wall,
        val corner: Vec3,
        /** The material side of the shared face, unit — the way the ball's centre stands off it. */
        val into: Vec3,
        val ex: Vec3,
        val ey: Vec3,
        val dirA: Vec3,
        val dirB: Vec3,
        val sF: Int,
        val sA: Int,
        val sB: Int,
        val r: Double,
        val total: Double,
    )

    /**
     * The ball's centre at turn [theta] — its tangency on the shared face runs on that face's own polar
     * ray about the corner, and the one unknown is how far out it stands.
     *
     * One equation in one unknown and it is bracketed rather than iterated blind: at zero the centre stands
     * over the corner itself, which is *on* the upright, so the distance is at most `r`; far out it is more
     * than `r`; and it grows on the way. Bisection therefore always answers, at every slant and at every
     * ring radius, which is what makes *whether a ball rolls here* a property of the sizes alone.
     */
    private fun cornerCentreAt(
        cf: CornerFrame,
        theta: Double,
    ): Vec3? {
        val e = cf.ex * cos(theta) + cf.ey * sin(theta)

        fun at(rho: Double): Double = cf.wD.out(cf.corner + e * rho + cf.into * cf.r) - cf.r
        var lo = 0.0
        // **the tangency stands within a few ball radii of the corner, or this is not the pivot at all.**
        // Square, it stands at exactly `r`; leaning or bent, at `r` over the cosine of the lean. A root
        // further out than three radii is the locus' *other* branch — the ball on the far side of the
        // upright — and taking it would sweep a tool right through the part, so it is refused instead.
        var hi = 3.0 * cf.r
        if (at(0.0) > 0.0 || at(hi) < 0.0) return null
        repeat(90) {
            val mid = (lo + hi) / 2.0
            if (at(mid) < 0.0) lo = mid else hi = mid
        }
        return cf.corner + e * ((lo + hi) / 2.0) + cf.into * cf.r
    }

    /** Where a station stands before its section is read — the centre, the plane and the two contacts. */
    private class CornerPlace(
        val c: Vec3,
        val t: Vec3,
        val place: Placement,
        val pF: Vec3,
        val pU: Vec3,
        val q1: Vec2,
        val q2: Vec2,
    )

    /** The pivot's own frame at turn [theta] — solved, not stepped, so it is a pure function of the angle. */
    private fun cornerPlaceAt(
        cf: CornerFrame,
        theta: Double,
    ): CornerPlace? {
        val h = 1e-6 * max(1.0, abs(cf.total))
        val c = cornerCentreAt(cf, theta) ?: return null
        val cA = cornerCentreAt(cf, theta - h) ?: c
        val cB = cornerCentreAt(cf, theta + h) ?: c
        var t = cB - cA
        if (t.length() <= Vec3.EPS) return null
        t = t.normalized()
        val pF = cf.wF.nearest(c) ?: return null
        val pU = cf.wD.nearest(c) ?: return null
        var ax = pF - c
        ax -= t * ax.dot(t)
        if (ax.length() <= Vec3.EPS) return null
        ax = ax.normalized()
        val place = Placement(c, ax, t.cross(ax))
        return CornerPlace(
            c,
            t,
            place,
            pF,
            pU,
            Vec2((pF - c).dot(place.cx), (pF - c).dot(place.cy)),
            Vec2((pU - c).dot(place.cx), (pU - c).dot(place.cy)),
        )
    }

    /**
     * **Which of the two faces closes the section here, read off the material and not off a frame** — or
     * null where the reading is a **tie**, which is a station of the pivot and not a number to round.
     *
     * Both of the pair's other faces contain the upright, so both cut this plane in a line through the
     * contact and each of those lines has a **real** half and an **extension**: the real half is the one
     * that is a face of the body, and the extension runs on through the material beyond the upright. The
     * half that bounds the section is the real one, and which that is, is [outwardAt]'s own question asked
     * of the *far* wall: a point stepped from the contact along it lies on the material side of that wall
     * exactly when the face it is on is the body's own there.
     *
     * *Where the two walls' traces **coincide** — at the station the ball first reaches the upright, and at
     * the hand-over — the question has no answer at all: the stepped point stands on both walls, so both
     * readings are zero and both apexes are the same point. That is the tie, and it used to be broken by a
     * comparison of two reaches along the two creases which is **itself** a tie at a revolve's own ring,
     * where neither band gives up any crease: a revolve's start cap came out with one face and its end cap
     * — whose frame is the start's turned by the sweep — with the other, off 1e-32 of floating point, and
     * the section then closed on the far face's extension so that the tool shared the cap's own face with
     * the body. The material does not care which way round the frame is, so a tie is **reported** here and
     * [cornerRawAt] reads it a nudge further into the pivot, where the walls have parted.*
     */
    private fun nearSideFrom(
        cf: CornerFrame,
        pl: CornerPlace,
        apexA: Vec2?,
        apexB: Vec2?,
    ): Boolean? {
        fun bodysOwn(
            ap: Vec2?,
            far: Wall,
            sFar: Int,
        ): Double? {
            if (ap == null) return null
            val v = ap - pl.q2
            if (v.length() <= Geom3.WELD_TOL) return null
            val step = min(0.25 * cf.r, 0.5 * v.length())
            // …and the face the body has is the one that **bounds the void**, which is the far wall's own
            // non-material side: the void wedge's two faces are the part of each wall that lies beyond the
            // other, so a point of the body's own face A stands on the *far* side of B and a point of A's
            // extension stands with the material
            return far.out(pl.place.at(pl.q2 + v.normalized() * step)) * sFar
        }
        // …and where only one of the two walls meets the shared face in this plane at all, that one closes
        // the section and there is nothing to read
        if (apexA == null && apexB == null) return null
        if (apexA == null) return false
        if (apexB == null) return true
        val oA = bodysOwn(apexA, cf.wB, cf.sB)
        val oB = bodysOwn(apexB, cf.wA, cf.sA)
        // …and a reading of **nothing** is no reading: the stepped point stands on the far wall itself, so
        // the two traces coincide here and this station is a tie
        val readA = if (oA == null || abs(oA) <= Geom3.WELD_TOL) null else oA < 0.0
        val readB = if (oB == null || abs(oB) <= Geom3.WELD_TOL) null else oB < 0.0
        if (readA != null && readB != null) return if (readA != readB) readA else null
        if (readA != null) return readA
        if (readB != null) return !readB
        return null
    }

    /** [nearSideFrom] asked at an angle of its own — what a tie is resolved by. */
    private fun nearSideAt(
        cf: CornerFrame,
        theta: Double,
    ): Boolean? {
        val pl = cornerPlaceAt(cf, theta) ?: return null
        val seed = Vec2((cf.corner - pl.c).dot(pl.place.cx), (cf.corner - pl.c).dot(pl.place.cy))
        return nearSideFrom(cf, pl, apexAt(cf.wF, cf.wA, pl.place, seed), apexAt(cf.wF, cf.wB, pl.place, seed))
    }

    /** The pivot's own station at turn [theta] — centre, frame, the two contacts, the apex and the arc. */
    private fun cornerRawAt(
        cf: CornerFrame,
        theta: Double,
    ): CornerRaw? {
        val pl = cornerPlaceAt(cf, theta) ?: return null
        val c = pl.c
        val place = pl.place
        val q1 = pl.q1
        val seed = Vec2((cf.corner - c).dot(place.cx), (cf.corner - c).dot(place.cy))
        val apexA = apexAt(cf.wF, cf.wA, place, seed)
        val apexB = apexAt(cf.wF, cf.wB, place, seed)
        // **a tie is read a nudge into the pivot, never broken by arithmetic.** The two stations that tie
        // are the two the walls' traces coincide at; at both of them the section is the *same* curve either
        // way and only the step-off's direction differs, so the face the neighbouring stations close on is
        // the one this one closes on too — and asking the material a nudge further in is a pure function of
        // the angle, where a comparison of two zeroes is a coin (OP-31, slice 5h).
        val nudge = max(1e-4 * abs(cf.total), 1e-9)
        val inward = if (theta * 2.0 <= cf.total) nudge else -nudge
        val nearA =
            nearSideFrom(cf, pl, apexA, apexB)
                ?: nearSideAt(cf, theta + inward)
                ?: nearSideAt(cf, theta - inward)
                ?: (theta * 2.0 <= cf.total)
        val apex = (if (nearA) apexA else apexB) ?: return null
        val a1 = atan2(q1.y, q1.x)
        val a2 = atan2(pl.q2.y, pl.q2.x)
        var d = a2 - a1
        while (d <= -PI) d += 2.0 * PI
        while (d > PI) d -= 2.0 * PI
        val other = if (d >= 0.0) d - 2.0 * PI else d + 2.0 * PI
        val toward = if (apex.length() <= Geom3.WELD_TOL) q1 else apex.normalized()

        fun facing(s: Double): Double {
            val a = a1 + s / 2.0
            return Vec2(cos(a), sin(a)).dot(toward)
        }
        val sweep = if (facing(d) >= facing(other)) d else other
        return CornerRaw(c, pl.t, place, pl.pF, pl.pU, a1, sweep, apex, nearA)
    }

    /** [raw] as the canal's own station, so the section, the legs and the sag rule are read once only. */
    private fun asCanalRaw(raw: CornerRaw): CanalRaw = CanalRaw(raw.at, raw.t, raw.place, raw.pF, raw.pU, raw.a1, raw.sweep, raw.apex)

    /**
     * How far one point of the pivot's section is carried **out of the body**, at weight [w].
     *
     * Two terms, each of them the drawing's own number rather than a fudge. The section's chords stand a
     * sagitta inside the ball's true arc, so a point that reaches the upright over one of them grazes the
     * body's own edge unless it clears that sagitta. And where the near wall hands over to the far one the
     * section's apex comes up to the body's own **vertex**, where three faces meet — the one contact a
     * general boolean has no answer for — so there the whole leg is carried a twentieth of the ball's radius
     * clear, into the void the upright bounds, which is air and costs the body nothing. [near0] is how near
     * the hand-over this station stands and [w] how near the upright this point of it does.
     */
    private fun lift(
        r: Double,
        sweep: Double,
        arcSteps: Int,
        grow: Double,
        near0: Double,
        w: Double,
    ): Double {
        // …and a step-off of **nothing** is the section read exactly, which is what the figure's own
        // quadrature integrates: every clearance here is the tool's and none of it is the body's.
        if (grow <= 0.0) return 0.0
        val big = max(clearOf(r, sweep, arcSteps, grow), r / 20.0 * near0)
        return grow + max(0.0, big - grow) * w.coerceIn(0.0, 1.0)
    }

    /** How far the pivot's section must stand clear of the upright: its own chords' sagitta, never less than the step-off. */
    private fun clearOf(
        r: Double,
        sweep: Double,
        arcSteps: Int,
        grow: Double,
    ): Double = max(grow, 2.0 * r * (1.0 - cos(abs(sweep) / (2.0 * max(1, arcSteps)))))

    /**
     * The plane a pivot's section is **cut off** at — one band's own cap, carried the tool's own step-off
     * past it, with [out] pointing the way **away** from the corner (OP-31, slice 5o).
     *
     * *Why a pivot's section needs a fourth side at all.* A canal section closes on the **crease point** —
     * where this station's plane cuts the crease the near band runs along — and at a **tight** ring that
     * point runs away: the station plane turns until it stands nearly parallel to that crease, and the apex
     * walks thirty-eight millimetres down a ten-millimetre tube. The section that names it is right, but
     * what the loft makes of it is a razor-thin sliver of tool lying **along the body's own edge**, over
     * ground the band itself removes exactly — and a tool that meets the body along a surface rather than
     * across one has no watertight boolean. So the section is cut off where the band's own flat end section
     * stands (slice 5p's cap, the plane square to the crease at the band's end), and the tool is thereby
     * **local to the corner**: it reaches exactly as far down each crease as the band it hands over to,
     * and no further.
     */
    private class CapCut(
        val origin: Vec3,
        val out: Vec3,
    )

    /**
     * The pivot's own section — [canalSectionAt]'s, with the one vertex that would otherwise **stand on the
     * body's own upright** stepped clear of it.
     *
     * A canal band's two ends are tangencies on two *faces*, and the tool's own step-off carries its legs
     * off them. A pivot's second contact is the **edge** where the pair's two other faces cross, so the
     * section's arc arrives exactly on a line of the body and the tool's surface and the body's edge would
     * coincide along the whole contact — *"a tangent or self-touching contact has no watertight mesh"*, the
     * one answer a boolean cannot give a body. Past that contact is the **void** the upright bounds (the
     * nearest point of a convex set to an outside point is where the ray through it enters), so the vertex
     * is stepped that way by the tool's own step-off and the crossing happens in air, transversally.
     */
    private fun cornerSectionAt(
        wF: Wall,
        near: Wall,
        far: Wall,
        raw: CornerRaw,
        r: Double,
        legSteps: Int,
        arcSteps: Int,
        sF: Int,
        sNear: Int,
        sFar: Int,
        grow: Double,
        cut: CapCut?,
    ): List<Vec2>? {
        val place = raw.place
        val q1 = Vec2(r * cos(raw.a1), r * sin(raw.a1))
        val q2 = Vec2(r * cos(raw.a1 + raw.sweep), r * sin(raw.a1 + raw.sweep))

        fun flat(v: Vec3): Vec2 = Vec2(v.dot(place.cx), v.dot(place.cy))
        val gF = wF.grad(place.at(raw.apex)) ?: return null
        val gN = near.grad(place.at(raw.apex)) ?: return null
        // **three faces meet where the pivot hands one near wall over to the other**, and the apex is their
        // own vertex there: stepping it off two of them leaves it standing in the third, which is the fold
        // the boolean names at that vertex. So the far wall joins the step as the apex comes up to the
        // upright and leaves it again as the apex walks away — one weight, continuous, nothing switched.
        val gFar = far.grad(place.at(raw.apex)) ?: return null
        val near0 = max(0.0, 1.0 - (place.at(raw.apex) - (far.nearest(place.at(raw.apex)) ?: place.at(raw.apex))).length() / max(r, Geom3.WELD_TOL))
        val outApex =
            (flat(gF) * -sF.toDouble() + flat(gN) * -sNear.toDouble() + flat(gFar) * (-sFar.toDouble() * near0))
                .let { if (it.length() <= Geom3.WELD_TOL) return null else it.normalized() }
        // **the way out of the body at the contact is the void's own bisector**, and it has to be: the
        // contact stands on the *edge* where the pair's two other faces cross, and stepping it off one of
        // them alone walks **into** the material the other one bounds — which is how a leg that lies along
        // that edge came back as a coincident pair of triangles. Between the contact and the crease point
        // the step turns from the one to the other, so the leg leaves the body all the way along.
        val gnU = near.grad(raw.pU) ?: return null
        val gfU = far.grad(raw.pU) ?: return null
        val outVoid =
            (flat(gnU) * -sNear.toDouble() + flat(gfU) * -sFar.toDouble()).let { if (it.length() <= Geom3.WELD_TOL) return null else it.normalized() }

        // **and a leg leaves the body through *every* face it comes up to, weighted by how near it stands
        // to each** (OP-31, slice 5o). The near leg runs from the contact on the upright to the crease
        // point, and the crease point lies **in the shared face** — so the leg's last stretch stands within
        // a hair of that face, and a step taken along the near wall's own gradient alone has no component
        // out of it at all. At a ring that is not an abstraction: the leg's own end lies **in** the cap's
        // plane, its radial step keeps it there, and the tool then carries a whole rail of vertices inside
        // a face of the body — *"the edge between (8.657, 8.08, 0) mm and (10, 8, 0) mm is used 2 times
        // with 2 opposite uses"*, which is a tangent contact and not a crossing. The apex had this reading
        // already (`outApex`, and the far wall's weight in it); what it did not have was the rest of the
        // leg, and the weight is the same one measured at each point rather than at the apex only.
        fun awayAt(
            q: Vec2,
            tau: Double,
        ): Vec2 {
            val p = place.at(q)
            val gNq = near.grad(p)
            val gFq = wF.grad(p)
            val gRq = far.grad(p)
            if (gNq == null || gFq == null || gRq == null) return outVoid
            val wS = max(0.0, 1.0 - (p - (wF.nearest(p) ?: p)).length() / max(r, Geom3.WELD_TOL))
            val wR = max(0.0, 1.0 - (p - (far.nearest(p) ?: p)).length() / max(r, Geom3.WELD_TOL))
            val v = flat(gNq) * -sNear.toDouble() + flat(gFq) * (-sF.toDouble() * wS) + flat(gRq) * (-sFar.toDouble() * wR)
            val away = if (v.length() <= Geom3.WELD_TOL) outVoid else v.normalized()
            return (outVoid * (1.0 - tau) + away * tau).let { if (it.length() <= Geom3.WELD_TOL) outVoid else it.normalized() }
        }
        val out = ArrayList<Vec2>(2 * legSteps + arcSteps + 2)
        // **the fourth side, and it is a straight line stated exactly** (OP-31, slice 5o). The cut is a
        // *plane*, so its own signed distance is **affine** in the station's two coordinates and its trace
        // in this plane is a line with no fitting in it at all. Two terms set where that line stands, and
        // each of them is the body's own: the band's own cap, carried here by the very near/far rule
        // [nearSideFrom] reads the near wall itself by — and, where this station's own ball reaches **past**
        // that cap, the plane parallel to it the ball is **tangent** to, because a tool may never cut the
        // envelope it is the loft of. Whichever of the two stands further from the corner is the one that
        // cuts, so the fourth side never takes ground the ball itself reaches and never leaves the corner.
        val cutG = if (cut == null) Vec2(0.0, 0.0) else Vec2(cut.out.dot(place.cx), cut.out.dot(place.cy))
        val cuts = cut != null && cutG.length() > Geom3.WELD_TOL
        // …and the radius the tangent is taken at is the **tool's** and not the ball's: every point of the
        // arc is carried out of the body by [lift] and the two legs by the step-off, so a line tangent to
        // the bare ball would cross the very chords it has to stand clear of, and the section would not be
        // a simple polygon any more. It is taken at the furthest any of them is carried.
        val bulge = max(grow, lift(r, raw.sweep, arcSteps, grow, near0, 1.0))
        val cutC = if (!cuts) 0.0 else min((place.at(Vec2(0.0, 0.0)) - cut!!.origin).dot(cut.out), -(r + bulge) * cutG.length())

        fun beyond(q: Vec2): Double = if (!cuts) -1.0 else cutC + q.dot(cutG)

        // …and how far along a leg the cut stands is bisected on the **wall's own trace** rather than on
        // the chord to the apex: a curved wall's trace bends away from that chord, and the fourth side has
        // to meet each leg where the leg really is, which is the reading every other point of a leg gets.
        fun cutParam(
            w: Wall,
            from: Vec2,
        ): Double {
            if (!cuts || beyond(raw.apex) <= 0.0) return 1.0
            var lo = 0.0
            var hi = 1.0
            repeat(50) {
                val mid = (lo + hi) / 2.0
                val lerp = from + (raw.apex - from) * mid
                val q = onWall(w, place, lerp) ?: lerp
                if (beyond(q) < 0.0) lo = mid else hi = mid
            }
            return (lo + hi) / 2.0
        }
        val tF = cutParam(wF, q1)
        val tN = cutParam(near, q2)
        // **the tangency on the shared face is carried off it like every other point of that leg** (OP-31,
        // slice 5h, third probe). It is where the ball touches the face, so it stands **exactly in** that
        // face — and the pivot puts one of them down at every station, which is a whole rail of tool
        // vertices lying in a plane of the body. Whether two coincident sheets cancel is the coin
        // [MeshCanon.flap] exists to name, and it came up heads for a loft sketched on `XY`, where the
        // shared face is `z = HEIGHT` exactly, and tails for the same loft sketched on a plane turned 30°
        // about `x`. The leg's own step carries it off: the same face, the same gradient, the same
        // direction out of the material as the points that follow it (GitHub #36).
        val gF1 = wF.grad(place.at(q1)) ?: return null
        out.add(q1 + flat(gF1) * (-sF.toDouble() * grow))
        for (i in 1 until legSteps) {
            val q = onWall(wF, place, q1 + (raw.apex - q1) * (tF * i.toDouble() / legSteps)) ?: return null
            val g = wF.grad(place.at(q)) ?: return null
            out.add(q + flat(g) * (-sF.toDouble() * grow))
        }
        // …and where the apex comes up to the upright it comes up to the **body's own vertex**, where three
        // faces meet: a tool vertex standing on one is the one contact a general boolean has no answer for.
        // So the apex is carried a twentieth of the ball's radius out of the body there — into air, so it
        // takes nothing extra — and back to the ordinary micron as it walks away again.
        //
        // *That is the reading where the cut does not bite at all*, and there are three such places and
        // they are the three that matter: the two **ends** of the walk, where the station stands square to
        // the crease and parallel to that band's own cap, so the trace is at infinity and the section is
        // the band's own end section **exactly** — which is what lets the loft close on the ring that band
        // already has — and the **hand-over**, where the apex is the corner's own vertex and stands nearer
        // than any cap. Where the cut does bite, the fourth side's two ends are ordinary points of the two
        // legs and take their own legs' steps off the walls they stand on.
        val apexStep = raw.apex + awayAt(raw.apex, 1.0) * lift(r, raw.sweep, arcSteps, grow, near0, near0)
        if (tF >= 1.0) {
            out.add(apexStep)
        } else {
            val q = onWall(wF, place, q1 + (raw.apex - q1) * tF) ?: return null
            val g = wF.grad(place.at(q)) ?: return null
            out.add(q + flat(g) * (-sF.toDouble() * grow))
        }
        if (tN >= 1.0) {
            out.add(apexStep)
        } else {
            val q = onWall(near, place, q2 + (raw.apex - q2) * tN) ?: return null
            out.add(q + awayAt(q, tN) * lift(r, raw.sweep, arcSteps, grow, near0, max(1.0 - tN, near0)))
        }
        val nearLeg = ArrayList<Vec2>(legSteps)
        for (i in 1 until legSteps) {
            val tau = tN * i.toDouble() / legSteps
            val q = onWall(near, place, q2 + (raw.apex - q2) * tau) ?: return null
            // …and at the hand-over the **whole** leg lies along the upright, not only its far end, so the
            // clearance holds all the way along it rather than dipping back to the micron in the middle
            nearLeg.add(q + awayAt(q, tau) * lift(r, raw.sweep, arcSteps, grow, near0, max(1.0 - tau, near0)))
        }
        out.addAll(nearLeg.reversed())
        // **and the ball's own arc leaves the body where it touches the upright, not *on* it.** The last
        // chord before the contact runs within a sagitta of the upright's own line — a hundredth of a
        // millimetre where the step-off is a micron — so the tool's surface and the body's edge approach
        // each other over a whole strip and the boolean answers a zero-length edge. The contact and the
        // chords that reach it are therefore carried **into the void** the upright bounds, by a step that
        // covers that sagitta: the tool takes no more material for it (the void is air) and the crossing
        // becomes transversal. It decays over the arc, so only the chords that stand near the contact move.
        out.add(q2 + outVoid * lift(r, raw.sweep, arcSteps, grow, near0, 1.0))
        for (i in arcSteps - 1 downTo 1) {
            val a = raw.a1 + raw.sweep * i / arcSteps
            val fade = max(0.0, 1.0 - (arcSteps - i).toDouble() / 3.0)
            out.add(Vec2(r * cos(a), r * sin(a)) + outVoid * lift(r, raw.sweep, arcSteps, grow, near0, fade))
        }
        return out
    }

    /**
     * The **pivot between two band ends** about an upright that is not one straight run square to the face
     * the pair shares, or the reason it cannot be built, or neither where the upright *is* straight and
     * square and session 80's exact circle answers instead.
     */
    private fun canalTurnOf(
        pieces: List<Piece>,
        i: Int,
        aAtStart: Boolean,
        j: Int,
        bAtStart: Boolean,
        shared: FacePatch,
        at: Vec3,
    ): Pair<CanalTurn?, Msg?> {
        val a = pieces[i]
        val b = pieces[j]
        val n = shared.plane?.normal?.normalized() ?: return null to null
        val fa = otherFace(a, shared) ?: return null to null
        val fb = otherFace(b, shared) ?: return null to null
        // the square upright is not this construction's: there the two other faces cut the pivot's own
        // meridian plane in one and the same line and the corner is the exact horn torus (slice 5e)
        if (axisLiesIn(fa, n) && axisLiesIn(fb, n)) return null to null
        val what =
            Msgs.refusalBlendInsideCornerWhereMeets(name = a.crease.edge.name.label, name2 = b.crease.edge.name.label, name3 = shared.name.label)
        // **one size and one kind**, and the sentence says what does work: the ball that pivots here is one
        // ball, so two roundings of unlike size or kind have two pivots and no one surface between them.
        if (a.sec.kind != BlendKind.FILLET || b.sec.kind != BlendKind.FILLET || abs(a.sec.size - b.sec.size) > 1e-9 || a.sec.kind != b.sec.kind) {
            return null to
                Msgs.refusalBlendUprightNeedsOneSize(
                    what = what,
                    name = fa.name.label,
                    name2 = fb.name.label,
                    sizePhrase = a.sec.sizePhrase(),
                    sizePhrase2 = b.sec.sizePhrase(),
                )
        }
        if (a.choice.convex != b.choice.convex) return null to null
        val notStatable =
            Msgs.refusalBlendCanalWallNotStatable(name = a.crease.edge.name.label, name2 = fa.name.label, name3 = fb.name.label)
        val wF = wallOf(shared) ?: return null to notStatable
        val wA = wallOf(fa) ?: return null to notStatable
        val wB = wallOf(fb) ?: return null to notStatable
        val wD = uprightCarrier(fa, fb, at) ?: return null to notStatable
        val r = a.sec.size
        if (r <= Geom3.WELD_TOL) return null to null
        val dA = touchStation(a, wD, aAtStart, r) ?: return null to notStatable
        val dB = touchStation(b, wD, bAtStart, r) ?: return null to notStatable
        val cA = ballCentreAt(a, if (aAtStart) dA else a.length - dA) ?: return null to notStatable
        val cB = ballCentreAt(b, if (bAtStart) dB else b.length - dB) ?: return null to notStatable
        val tA = wF.nearest(cA) ?: return null to notStatable
        val tB = wF.nearest(cB) ?: return null to notStatable
        val into = (cA - tA).let { if (it.length() <= Vec3.EPS) return null to notStatable else it.normalized() }
        val exRaw = tA - at
        if (exRaw.length() <= Geom3.WELD_TOL) return null to notStatable
        val ex = exRaw.normalized()
        val ey0 = n.cross(ex)
        if (ey0.length() <= Vec3.EPS) return null to notStatable
        var ey = ey0.normalized()
        val rel = tB - at
        var total = atan2(rel.dot(ey), rel.dot(ex))
        // **the pivot is walked from the one band to the other, and the frame turned to suit** — never the
        // pair re-ordered to suit the frame (OP-31, slice 5h). Stating the direction from the *body* instead
        // — positive about the shared face's own outward normal — is order-free too and was tried, and it is
        // **wrong**: a partial revolve's two caps are each other's **mirror**, a reflection turns the other
        // way about its own normal, and the rule then builds the end cap's pivot as the *reversed* mirror of
        // the start cap's. Whether a pivot builds is then a property of which cap it sits on, which is the
        // one thing it may never be. Read from the pieces, the whole construction is mirror-covariant: the
        // two caps' pivots are each other's reflection, station for station. What the gesture's own order
        // must not reach is the **tool**, and that is answered where it arises, in [cornerMesh]'s quads.
        if (total < 0.0) {
            ey = -ey
            total = -total
        }
        if (total <= TANGENT_TOL) return null to null
        val sF = if (wF.out(cA) >= 0.0) 1 else -1
        val sA = if (wA.out(cA) >= 0.0) 1 else -1
        val sB = if (wB.out(cB) >= 0.0) 1 else -1
        val cf =
            CornerFrame(
                wF, wA, wB, wD, at, into, ex, ey,
                outOf(a, aAtStart), outOf(b, bAtStart),
                sF, sA, sB, r, total,
            )
        val tooLarge = Msgs.refusalBlendCanalBallLargerThanBend(sizePhrase = a.sec.sizePhrase(), name = a.crease.edge.name.label)

        fun raws(count: Int): List<CornerRaw>? = (0..count).map { cornerRawAt(cf, total * it / count) ?: return null }
        var steps = 16
        var set = raws(steps) ?: return null to tooLarge
        while (steps < 128) {
            val fine = raws(2 * steps) ?: return null to tooLarge
            var worst = 0.0
            for (k in 0 until steps) {
                val mid = fine[2 * k + 1]
                worst = max(worst, (mid.at - (set[k].at + set[k + 1].at) * 0.5).length())
                worst = max(worst, (mid.pF - (set[k].pF + set[k + 1].pF) * 0.5).length())
                worst = max(worst, (mid.pU - (set[k].pU + set[k + 1].pU) * 0.5).length())
            }
            set = fine
            steps *= 2
            if (worst <= GeomMath.TESS_TOL_MM) break
        }
        // **and the pivot has to go forward**, the same reading slice 5f makes of a run: where two steps of
        // the centre oppose each other the ball is larger than the bend it is asked to turn in
        for (k in 1 until set.size - 1) {
            val back = set[k].at - set[k - 1].at
            val on = set[k + 1].at - set[k].at
            if (back.length() <= Vec3.EPS || on.length() <= Vec3.EPS) continue
            if (back.normalized().dot(on.normalized()) <= 0.0) return null to tooLarge
        }
        // **four times the sag rule's own count** (OP-31, slice 5h): a pivot's arc *ends* on the body's own
        // upright, and the last chord before that end stands a sagitta inside the true surface — so the
        // tool's surface and the body's edge approach each other over a strip as wide as that sagitta, and
        // what the boolean answers there is a graze rather than a crossing. The sagitta falls as the square
        // of the count, so four times it is a sixteenth of the strip.
        val arcSteps = 4 * max(1, GeomMath.chordSteps(r, set.maxOf { abs(it.sweep) }, GeomMath.TESS_TOL_MM))
        // …and never fewer than three chords: at the hand-over the near leg lies **along** the upright
        // itself, and a single chord between its two stepped ends runs beside the body's own edge for its
        // whole length. Three gives the leg interior points that are pulled onto the wall and stepped off
        // it, so the tool crosses rather than grazes (OP-31, slice 5h).
        val legSteps = max(3, set.maxOf { legStepsFor(wF, if (it.nearA) wA else wB, listOf(asCanalRaw(it))) })
        val grow = 2.0 * max(canalGrow(wF, wA), canalGrow(wF, wB))
        // **the band's own cap does not stand on the upright.** At the station the ball first touches the
        // upright its tangency on that band's other face **is** the point of contact — the C¹ argument, read
        // one way round — so a tube capped exactly there puts a vertex of the tool on an edge of the body,
        // which is the one contact a general boolean has no answer for. The cap is carried the tool's own
        // step-off past it, into the ground the pivot covers anyway, and the two overlap there as two tools
        // of one sign always may.
        // …and **most of all** where the crease's own end is where the ball first touches, which is what a
        // revolve's ring is. It used to be read the other way round — *"only where the tangency really does
        // leave the band's other face **inside** the run: where the upright stands in a plane square to the
        // crease — a revolve's own ring — the band already ends at the crease's own end and there is nothing
        // to carry it off"* — and that is exactly backwards: the band is then capped in the plane square to
        // the crease at its own end, and **that plane is a face of the body there**. Two coincident sheets
        // again, and whether the engine cancels them is decided by the arithmetic: the same drawing built
        // sketched on `XY`, where the cancellation is exact, and folded sketched on a plane turned 30° about
        // `y`, where it is not (GitHub #36, the pose probe).
        //
        // *And the cap is carried off by the **wall's own skin**, not by a micron*, for [canalGrow]'s reason:
        // it has to clear the band's *curved* wall as well as the plane it stands in, and a micron is a
        // twentieth of that wall's own tessellation. A micron was tried and takes eighteen more cells of the
        // sweep away.
        val pullA = grow
        val pullB = grow
        val backA = min(a.length, dA + pullA)
        val backB = min(b.length, dB + pullB)
        val placeA = placeAt(a, if (aAtStart) backA else a.length - backA) ?: return null to notStatable
        val placeB = placeAt(b, if (bAtStart) backB else b.length - backB) ?: return null to notStatable
        // **the fourth side's own plane, one for each band** (OP-31, slice 5o) — that band's cap, carried
        // the tool's own step-off **past** the cap so that the side lies strictly inside the tube the band
        // itself removes rather than in the very plane the band's tool is capped in, which would be two
        // coincident sheets of the drawing's own making. Which of the two a station cuts on is the same
        // question [nearSideFrom] asks of the walls, answered once and read off `nearA`.
        val cutA = capCutOf(a, aAtStart, min(a.length, backA + grow), at) ?: return null to notStatable
        val cutB = capCutOf(b, bAtStart, min(b.length, backB + grow), at) ?: return null to notStatable
        val stations = ArrayList<CornerStation>(set.size)
        var s = 0.0
        for ((k, raw) in set.withIndex()) {
            if (k > 0) s += (raw.at - set[k - 1].at).length()
            val near = if (raw.nearA) wA else wB
            val sNear = if (raw.nearA) sA else sB
            val poly =
                cornerSectionAt(
                    wF, near, if (raw.nearA) wB else wA, raw, r, legSteps, arcSteps, sF, sNear, if (raw.nearA) sB else sA, grow,
                    if (raw.nearA) cutA else cutB,
                ) ?: return null to notStatable
            stations.add(CornerStation(raw.at, raw.t, raw.place.cx, raw.place.cy, raw.pF, raw.pU, poly, raw.a1, raw.sweep, raw.apex, raw.nearA, s))
        }
        return CanalTurn(
            i, aAtStart, j, bAtStart, placeA, placeB, shared, at, stations, r, a.sec, a.choice.convex,
            grow, arcSteps, legSteps, wF, wA, wB, wD, CANAL_FIT_TOL_MM, cf,
            abs(Geom3.polygonArea(a.plain)) * backA + abs(Geom3.polygonArea(b.plain)) * backB,
            (grow + pullA) to (grow + pullB),
            cutA,
            cutB,
        ) to null
    }

    /**
     * The pivot's **tool**: the loft of its stations, capped at each end a step **past** the band it ends.
     *
     * The overshoot is what keeps the two tools crossing rather than touching. At either end the section is
     * the band's own wedge and the spine runs along the band's own crease, so a ring stepped back there is a
     * slice of that band's tube — and the step-off is deliberately **twice** the band's, so the corner's own
     * legs stand outside the band's and the two solids meet transversally instead of sharing a face.
     */
    private fun cornerMesh(turn: CanalTurn): Mesh3? {
        if (turn.stations.size < 2) return null
        val rings = ArrayList<List<Vec3>>(turn.stations.size)
        for ((k, st) in turn.stations.withIndex()) {
            val step =
                if (k == 0) {
                    -turn.endStep.first
                } else if (k == turn.stations.size - 1) {
                    turn.endStep.second
                } else {
                    0.0
                }
            rings.add(st.poly.map { st.world(it) + st.t * step })
        }
        val tris = ArrayList<Triple<Vec3, Vec3, Vec3>>()
        for (l in 0 until rings.size - 1) {
            val lo = rings[l]
            val hi = rings[l + 1]
            if (lo.size != hi.size) return null
            for (m in lo.indices) {
                val nx = (m + 1) % lo.size
                val a = lo[m]
                val b = lo[nx]
                val c = hi[nx]
                val d = hi[m]
                // **a column between two coincident pairs encloses nothing, and is left out** (OP-31, slice
                // 5o). The section's fourth side is the band's own cap, and at the two ends of the walk and
                // at the hand-over that cap stands further out than the crease point itself, so the side has
                // no length there and its two vertices are one point said twice. Split about its centre,
                // such a column is two triangles wound against each other on the same three points — the
                // very zero-thickness flap [MeshCanon] names — so the column is skipped rather than emitted
                // and the two rings stitch straight across, which is what a side of no length means.
                if ((a - b).length() <= Geom3.WELD_TOL && (c - d).length() <= Geom3.WELD_TOL) continue
                // **a quad of a turning section takes its own centre, not one of its two diagonals**
                // (OP-31, slice 5h). A pivot's section turns from one band end to the other, so its quads
                // are **not plane** — and the two diagonals of a quad that is not plane enclose different
                // volumes. Choosing one of them makes the tool a function of *which way the run is walked*:
                // two gestures one at a time hand the pair over in the other order, which reverses the walk
                // and flips every diagonal, and the two routes came out 7.6e-5 mm³ apart on a ring pivot
                // for that reason and no other (GitHub #36). The **shorter** diagonal is order-free but
                // tosses a coin of its own wherever the two are within a hair, which a revolve's two caps —
                // each other's mirror to within the solver's last digit — are. The centre is neither and
                // both: four triangles to the quad's own centroid, whose volume is exactly the mean of the
                // two splits, a pure function of the four points, and free of any tie at all. So the routes
                // agree, and a pivot's tool is the mirror of its mirror's, station for station.
                val m = (a + b + c + d) * 0.25
                tris.add(Triple(a, b, m))
                tris.add(Triple(b, c, m))
                tris.add(Triple(c, d, m))
                tris.add(Triple(d, a, m))
            }
        }
        val first = turn.stations.first()
        val last = turn.stations.last()
        val placeFirst = Placement(first.at - first.t * turn.endStep.first, first.ax, first.ay)
        for (t in sectionCaps(first.poly)) tris.add(Triple(placeFirst.at(t.c), placeFirst.at(t.b), placeFirst.at(t.a)))
        val placeLast = Placement(last.at + last.t * turn.endStep.second, last.ax, last.ay)
        for (t in sectionCaps(last.poly)) tris.add(Triple(placeLast.at(t.a), placeLast.at(t.b), placeLast.at(t.c)))
        var six = 0.0
        for (t in tris) six += t.first.dot(t.second.cross(t.third))
        val b = Geom3.MeshBuilder()
        for (t in tris) {
            if (six >= 0.0) b.triangle(t.first, t.second, t.third) else b.triangle(t.first, t.third, t.second)
        }
        return b.build()
    }

    /**
     * The **tools** the pivots of this level are cut with, as meshes — the seam a test asserts the tool
     * itself on, which is the half no reading of the body can see (OP-31, slice 5h).
     */
    internal fun cornerToolMeshes(f: Feature3.Blend): List<Mesh3> {
        val pieces = piecesOf(f) ?: return emptyList()
        return cornersOf(pieces).list.filterIsInstance<CanalTurn>().mapNotNull { cornerMesh(it) }
    }

    /**
     * The pivot's tool as a solid, or the reason there is none — **asked of the tool itself** before the
     * body ever sees it, so that what comes back is the drawing's own sentence and never a mesh diagnostic
     * (the rule session 84 wrote down for the canal band, said again here).
     */
    private fun cornerTool(turn: CanalTurn): Pair<Solid3?, Msg?> {
        val mesh = cornerMesh(turn) ?: return null to Msgs.refusalBlendCanalSpineNotFollowed(name = turn.shared.name.label)
        if (mesh.triangles.isEmpty() || Geom3.volume(mesh) <= 0.0) return null to Msgs.refusalBlendRoundingOwnToolEnclosesNo()
        MeshCanon.notClosed(mesh)?.let { return null to Msgs.refusalBlendRoundingOwnToolIsNot(itWord = it) }
        return Solid3.of(Feature3.MeshBoolean(BoolOp.UNION), mesh) to null
    }

    /**
     * The two curves a **canal corner** puts on the body, and each says what it is.
     *
     * The **tangency** on the shared face is the corner's own rail, a fitted chain through points every one
     * of which is exact on both the ball and that face — the two bands' rails carried on round the corner,
     * so *rail → corner rail → rail* is one chain exactly as a walk's is. The **contact** on the upright is
     * not fitted at all: it is a piece of the upright's own carrier, which is a straight run or a circle,
     * and the pivot merely says how much of it the ball rolls along.
     */
    private fun canalTurnEdges(
        turn: CanalTurn,
        edges: List<Int>,
    ): List<SolidEdge> {
        val face = FaceName.BlendCorner(edges, 0)
        val out = ArrayList<SolidEdge>(2)
        val (chain, tol) = fittedChain3(turn.fitted) { u -> cornerPointAt(turn, u, true) } ?: (null to null)
        out.add(
            SolidEdge(
                EdgeName.BlendCornerRail(edges, 0),
                chain?.let { EdgeGeom.InSpace(it) } ?: EdgeGeom.Straight(turn.stations.first().pF, turn.stations.last().pF),
                FacePair(turn.shared.name, face),
                null,
                tol,
            ),
        )
        out.add(
            SolidEdge(
                EdgeName.BlendCornerRail(edges, 1),
                EdgeGeom.Straight(turn.stations.first().pU, turn.stations.last().pU),
                FacePair(turn.wD.patch.name, face),
                null,
            ),
        )
        return out
    }

    /** A point of one of the pivot's two rails at [u] over the whole turn — exact on the ball and the wall. */
    private fun cornerPointAt(
        turn: CanalTurn,
        u: Double,
        onShared: Boolean,
    ): Vec3? {
        val n = turn.stations.size - 1
        if (n < 1) return null
        val x = (u.coerceIn(0.0, 1.0) * n)
        val k = min(n - 1, x.toInt())
        val f = x - k
        val a = if (onShared) turn.stations[k].pF else turn.stations[k].pU
        val b = if (onShared) turn.stations[k + 1].pF else turn.stations[k + 1].pU
        return a + (b - a) * f
    }

    /**
     * Where [cut] crosses a **canal corner** — sampled on the pivot's own `(station, arc)` chart, for the
     * same reason slice 5f's band is: the characteristic curves are the ball's own circles in planes that
     * turn along the pivot, so a plane crosses the patch *across* the turn and a station-by-station reader
     * would find one point where the cut has a whole curve. Every point of the answer is exact on the
     * surface; only the chords between them are not (OP-15).
     */
    private fun canalTurnCut(
        turn: CanalTurn,
        cut: Plane3,
    ): Revolve3.BandCut? {
        val n = cut.normal.normalized()
        val d = cut.origin.dot(n)
        val arcs = max(8, turn.arcSteps)
        val at =
            turn.stations.map { st ->
                (0..arcs).map { j ->
                    val a = st.a1 + st.sweep * j / arcs
                    st.at + st.ax * (turn.r * cos(a)) + st.ay * (turn.r * sin(a))
                }
            }
        val segs = ArrayList<Pair<Vec2, Vec2>>()
        val last = at.size - 1
        for (k in 0 until last) {
            for (j in 0 until arcs) {
                val corners = listOf(at[k][j], at[k][j + 1], at[k + 1][j + 1], at[k + 1][j])
                val fs = corners.map { it.dot(n) - d }
                val hits = ArrayList<Vec2>(4)
                for (m in 0 until 4) {
                    val a = fs[m]
                    val b = fs[(m + 1) % 4]
                    if ((a > 0.0 && b > 0.0) || (a < 0.0 && b < 0.0) || a == b) continue
                    val t = (a / (a - b)).coerceIn(0.0, 1.0)
                    // **the two ends of the chart are exact**, and they have to be: the pivot's own cut
                    // hands over to each band's there, and a band's cut is stated on its surface rather
                    // than sampled — so a chord's crossing would miss it by a sagitta and the section
                    // would not close. The end station's own great circle is solved instead.
                    val station =
                        if (k == 0 && m == 0) {
                            turn.stations.first()
                        } else if (k == last - 1 && m == 2) {
                            turn.stations.last()
                        } else {
                            null
                        }
                    val exact =
                        station?.let {
                            val lo = it.a1 + it.sweep * (if (m == 0) j else j + 1) / arcs
                            val hi = it.a1 + it.sweep * (if (m == 0) j + 1 else j) / arcs
                            circleOnPlane(it, turn.r, n, d, lo, hi)
                        }
                    hits.add(cut.toLocal(exact ?: (corners[m] + (corners[(m + 1) % 4] - corners[m]) * t)))
                }
                if (hits.size >= 2) segs.add(hits[0] to hits[1])
                if (hits.size >= 4) segs.add(hits[2] to hits[3])
            }
        }
        val runs = chainSegments(segs)
        return if (runs.isEmpty()) null else Revolve3.BandCut(null, runs)
    }

    /**
     * Where the ball's own great circle at [st] meets the plane `x·n = d`, between the angles [lo] and [hi]
     * — solved rather than interpolated, so a chart's own boundary hands over to a band's exact cut.
     */
    private fun circleOnPlane(
        st: CornerStation,
        r: Double,
        n: Vec3,
        d: Double,
        lo: Double,
        hi: Double,
    ): Vec3? {
        val a = r * st.ax.dot(n)
        val b = r * st.ay.dot(n)
        val c = d - st.at.dot(n)
        val rad = hypot(a, b)
        if (rad <= Geom3.WELD_TOL || abs(c) > rad) return null
        val phi = atan2(b, a)
        val delta = acos((c / rad).coerceIn(-1.0, 1.0))
        for (root in listOf(phi + delta, phi - delta)) {
            for (turnBy in -2..2) {
                val ang = root + turnBy * 2.0 * PI
                if (ang >= min(lo, hi) - 1e-12 && ang <= max(lo, hi) + 1e-12) {
                    return st.at + st.ax * (r * cos(ang)) + st.ay * (r * sin(ang))
                }
            }
        }
        return null
    }

    /**
     * **What the pivots of this level take off the body**, bracketed the way slice 5f brackets a canal run:
     * Pappus' own volume element `∫ A(s)·(1 − κ(s)·x̄(s)) ds` along the spine, with `A` the section's exact
     * area at each station — the region inside the shared face and the near one and outside the ball, whose
     * boundary is the ball's own arc and the two walls' own traces.
     *
     * The bracket's two terms are the drawing's own rule and not a fudge: below, the exact quadrature **less
     * the walls' own tessellation strip**, because a wall the ball rolls on reaches the boolean as chords
     * standing inside the true surface; above, the chorded quadrature **plus the tool's own step-off strip**.
     */
    internal fun cornerRemoval(f: Feature3.Blend): Pair<Double, Double>? {
        val pieces = piecesOf(f) ?: return null
        val turns = cornersOf(pieces).list.filterIsInstance<CanalTurn>()
        if (turns.isEmpty()) return null
        // **the figure a body can be measured against** is what the pivot is worth *against the same two
        // roundings run whole*: its own quadrature, less the stretch of each band's rigid section it takes
        // over ([CanalTurn.tail], which is exactly that product). Both bands are prisms, so what they lose
        // needs no measurement at all.
        var lo = 0.0
        var hi = 0.0
        for (p in pieces) {
            // **each band is a prism and the figure says so**: a straight crease carries a rigid section, so
            // what it takes is that section's own area times its whole run — the exact wedge below and its
            // own chords above, since a tessellated section stands outside the arc it chords. Each free end
            // is stepped by [endSteps]' own micron, which is the slack the lower bound carries for it.
            val area = abs(Geom3.polygonArea(p.plain))
            // …and the section reaches the tool as **chords**, which stand outside the curves they chord by
            // at most the tessellation's own tolerance — so the area it overstates is that tolerance times
            // the section's own perimeter, which is the containment term and not a guess
            var perim = 0.0
            for (k in p.plain.indices) perim += (p.plain[(k + 1) % p.plain.size] - p.plain[k]).length()
            val chordSlack = GeomMath.TESS_TOL_MM * perim
            lo += (area - chordSlack) * p.length - 2.0 * area * GROW_MM
            hi += area * p.length + 2.0 * area * GROW_MM
        }
        for (turn in turns) {
            val (a, b) = cornerRemovalOf(turn) ?: return null
            lo += a
            hi += b
        }
        return lo to hi
    }

    /** One pivot's own figure and its bracket — see [cornerRemoval]. */
    private fun cornerRemovalOf(turn: CanalTurn): Pair<Double, Double>? {
        val cf = turn.frame ?: return null

        fun sliceAt(
            theta: Double,
            legs: Int,
            arcs: Int,
        ): Double {
            val raw = cornerRawAt(cf, theta) ?: return 0.0
            val near = if (raw.nearA) turn.wA else turn.wB
            val far = if (raw.nearA) turn.wB else turn.wA
            val sNear = if (raw.nearA) cf.sA else cf.sB
            val sFar = if (raw.nearA) cf.sB else cf.sA
            val poly =
                cornerSectionAt(turn.wF, near, far, raw, turn.r, legs, arcs, cf.sF, sNear, sFar, 0.0, if (raw.nearA) turn.cutA else turn.cutB)
                    ?: return 0.0
            var twice = 0.0
            var mx = 0.0
            var my = 0.0
            for (k in poly.indices) {
                val p = poly[k]
                val q = poly[(k + 1) % poly.size]
                val cross = p.x * q.y - q.x * p.y
                twice += cross
                mx += (p.x + q.x) * cross
                my += (p.y + q.y) * cross
            }
            if (abs(twice) <= 1e-18) return 0.0
            val area = abs(twice) / 2.0
            val centroid = Vec2(mx / (3.0 * twice), my / (3.0 * twice))
            val h = 1e-4 * max(1.0, abs(cf.total))
            val p0 = cornerCentreAt(cf, theta - h) ?: return area
            val p2 = cornerCentreAt(cf, theta + h) ?: return area
            val v1 = p0 - raw.at
            val v2 = p2 - raw.at
            val nrm = v1.cross(v2)
            if (nrm.length() <= 1e-18) return area
            val towards = (v2 * v1.dot(v1) - v1 * v2.dot(v2)).cross(nrm) * (1.0 / (2.0 * nrm.dot(nrm)))
            val rho = towards.length()
            if (rho <= Geom3.WELD_TOL) return area
            val dir = towards * (1.0 / rho)
            val x = centroid.x * dir.dot(raw.place.cx) + centroid.y * dir.dot(raw.place.cy)
            return area * (1.0 - x / rho)
        }

        fun quadrature(
            legs: Int,
            arcs: Int,
        ): Double {
            val count = turn.stations.size - 1
            if (count < 2) return 0.0
            var sum = 0.0
            for (k in 0 until count) {
                val len = turn.stations[k + 1].s - turn.stations[k].s
                if (len <= 0.0) continue
                val t0 = cf.total * k / count
                val t1 = cf.total * (k + 1) / count
                sum += len * (sliceAt(t0, legs, arcs) + 4.0 * sliceAt((t0 + t1) / 2.0, legs, arcs) + sliceAt(t1, legs, arcs)) / 6.0
            }
            return sum
        }

        fun strip(
            t1: Double,
            t2: Double,
        ): Double {
            var sum = 0.0
            val count = turn.stations.size - 1
            for (k in 0 until count) {
                val len = turn.stations[k + 1].s - turn.stations[k].s
                if (len <= 0.0) continue
                sum += len * (cornerLegStrip(turn, k, t1, t2) + cornerLegStrip(turn, k + 1, t1, t2)) / 2.0
            }
            return sum
        }
        val exact = quadrature(256, 512)
        val chorded = quadrature(turn.legSteps, turn.arcSteps)
        val skin = strip(wallSkin(turn.wF), max(wallSkin(turn.wA), wallSkin(turn.wB)))
        val stepped = strip(turn.grow, turn.grow)
        // …**less what the two bands give up to it**: the figure a body can actually be measured against is
        // the pivot's own removal against the same two roundings run whole, and what the corner takes back
        // off each is that band's own rigid section times the crease it hands over ([CanalTurn.tail]).
        return (min(exact, chorded) - skin - turn.tail) to (max(exact, chorded) + stepped - turn.tail)
    }

    /** The area one station's two legs stand to gain or lose to a strip [t1]/[t2] wide along each wall. */
    private fun cornerLegStrip(
        turn: CanalTurn,
        k: Int,
        t1: Double,
        t2: Double,
    ): Double {
        val st = turn.stations[k]
        // …and the section carries **one vertex more** than its two legs since slice 5o: the fourth side's
        // own two ends. That side stands in neither wall — it is the band's own cap plane — so its own
        // slack is carried by the wider of the two strips rather than attributed to a wall it is not in.
        val legs = (st.poly.size - turn.arcSteps - 1) / 2
        var l1 = 0.0
        var l2 = 0.0
        for (j in 0 until legs) l1 += (st.poly[j + 1] - st.poly[j]).length()
        for (j in legs + 1 until 2 * legs + 1) l2 += (st.poly[j + 1] - st.poly[j]).length()
        val fourth = (st.poly[legs + 1] - st.poly[legs]).length()
        return l1 * t1 + l2 * t2 + fourth * max(t1, t2)
    }

    /**
     * The band a canal leaves, as a face of the dressed body — no plane and no revolution, and it says so;
     * and since slice 5l it carries the **pipe surface** it is ([Pipe3]) with its trim stated in that
     * surface's own `(arc, station)` chart, so that a general boolean can look a triangle up against it.
     */
    private fun canalBandPatch(canal: Canal): FacePatch {
        // **the surface runs as far as the tool did**, which is [Canal.grow] past each free end: the loft's
        // end ring is *moved* there rather than doubled ([canalMesh]), so over that last step the band is
        // the last section carried straight along its own normal. [Canal.run] is that list — the very one
        // the band's own cut and its rails are read on, so the surface, the trim, the neighbours it bites
        // and the cap it closes on all end at one ring — and without it the body's own cap facet stands on
        // a surface the carrier has already ended (OP-31, slice 5l).
        val sts = canal.run.map { PipeStation(it.at, it.t, it.ax, it.s) }
        val arcs = canal.run.map { it.a1 to it.sweep }
        val pipe = pipeOf(sts, canal.r, canal.closed)
        return FacePatch(
            canal.name,
            null,
            pipeTrim(pipe, arcs, canal.closed),
            Msgs.refusalBlendCanalBandIsNotPlane(name = canal.name.label, sizePhrase = canal.sec.sizePhrase(), name2 = canal.edge.name.label),
            null,
            max(canal.fitted, pipe.fitted),
            pipe,
        )
    }

    /**
     * **A pipe surface from the stations its builder solved** (OP-31, slice 5l), with the tolerance the
     * curve *between* two stations may stand from the truth — measured rather than asserted.
     *
     * The measurement is the spine's own: the same Catmull–Rom interpolant built through **half** the
     * stations is asked for the ones it skipped, and the worst miss is carried. That is a conservative
     * estimate of the full-resolution interpolant's own error (the scheme is fourth order, so halving the
     * spacing divides the miss by about sixteen), it needs nothing but the stations themselves, and it
     * therefore says the same thing for a canal band's spine and for a canal corner's.
     */
    private fun pipeOf(
        stations: List<PipeStation>,
        r: Double,
        closed: Boolean,
    ): Pipe3 {
        var worst = 0.0
        if (stations.size >= 9) {
            val coarse = Pipe3(stations.filterIndexed { i, _ -> i % 2 == 0 }, r, closed, 0.0)
            // …asked away from the two ends, where the half-resolution chain has lost the very stations
            // that state how the run begins and would report its own reflection rather than the spine's bend
            for (i in 3 until stations.size - 3) {
                if (i % 2 == 1) worst = max(worst, (coarse.centreAt(i / 2.0) - stations[i].at).length())
            }
        }
        return Pipe3(stations, r, closed, max(worst, 1e-12))
    }

    /**
     * A canal's trim, stated in the pipe's own `(arc, station)` chart and directed **material to the left**
     * (OP-31, slice 5l) — the convention [BoolFace3] reads every curved face's boundary under.
     *
     * The band is the strip between its two rails: the arc runs from `a1` to `a1 + sweep` at every station,
     * so the boundary is the far rail walked forward along the run and the near rail walked back, closed at
     * each free end by that end's own section. Where the spine **closes on itself** there are no ends and
     * the two rails are two runs that span the chart's whole period, which is exactly what the chart's own
     * wrap reads them as.
     */
    private fun pipeTrim(
        pipe: Pipe3,
        arcs: List<Pair<Double, Double>>,
        closed: Boolean,
    ): List<ProfileElement> {
        if (pipe.stations.size < 2 || arcs.size != pipe.stations.size) return emptyList()
        val lo = ArrayList<Vec2>(arcs.size + 1)
        val hi = ArrayList<Vec2>(arcs.size + 1)
        var prev = arcs[0].first
        for ((k, st) in pipe.stations.withIndex()) {
            val a = unwrapTurn(arcs[k].first, prev)
            prev = a
            lo.add(Vec2(a, st.s))
            hi.add(Vec2(a + arcs[k].second, st.s))
        }
        if (closed) {
            lo.add(Vec2(lo[0].x, pipe.length))
            hi.add(Vec2(hi[0].x, pipe.length))
        }
        val ring = ArrayList<Vec2>(2 * hi.size + 2)
        ring.addAll(hi)
        if (!closed) ring.add(lo.last())
        val out = ArrayList<ProfileElement>(2 * hi.size + 2)
        if (closed) {
            // two runs rather than one ring: the rails span the chart's whole period and never meet
            for (k in 0 until hi.size - 1) out.add(ProfileElement.Seg(Segment(hi[k], hi[k + 1])))
            for (k in lo.size - 1 downTo 1) out.add(ProfileElement.Seg(Segment(lo[k], lo[k - 1])))
        } else {
            for (k in lo.size - 1 downTo 0) ring.add(lo[k])
            ring.add(hi[0])
            for (k in 0 until ring.size - 1) {
                if ((ring[k + 1] - ring[k]).length() <= 1e-12) continue
                out.add(ProfileElement.Seg(Segment(ring[k], ring[k + 1])))
            }
        }
        // **which way round is measured, not argued** — the same rule every oriented boundary in this
        // drawing is settled by (OP-14): a point the band genuinely has is put to the trim, and the walk
        // is turned where it says the material is on the other side. The ball's arc may run either way
        // about the spine (`sweep` carries a sign), so neither winding is the one to assume.
        var at = 0
        for (k in arcs.indices) if (abs(arcs[k].second) > abs(arcs[at].second)) at = k
        val probe = Vec2(unwrapTurn(arcs[at].first, lo[at].x) + arcs[at].second / 2.0, pipe.stations[at].s)
        if (BoolFace3.onPipe(pipe, out, probe)) return out
        val back = ArrayList<ProfileElement>(out.size)
        for (k in out.indices.reversed()) {
            val e = out[k] as ProfileElement.Seg
            back.add(ProfileElement.Seg(Segment(e.segment.b, e.segment.a)))
        }
        return back
    }

    /** [x] moved by whole turns to lie within half a turn of [near] — the arc coordinate, kept continuous. */
    private fun unwrapTurn(
        x: Double,
        near: Double,
    ): Double {
        var v = x
        while (v - near > PI) v -= 2.0 * PI
        while (near - v > PI) v += 2.0 * PI
        return v
    }

    /**
     * The two **flat ends** of a canal band: the section standing in the plane square to the spine, or —
     * where the run tapers to nothing because the two faces run tangent there — no face at all, said so.
     */
    private fun canalCapFaces(canal: Canal): List<FacePatch> =
        listOf(true, false).map { atStart ->
            val name = FaceName.BlendCap(canal.index, atStart)
            val st = if (atStart) canal.stations.first() else canal.stations.last()
            if (canal.closed) {
                val why =
                    if (canal.bevel) {
                        Msgs.refusalBlendBevelRunsRightRound(name = canal.edge.name.label)
                    } else {
                        Msgs.refusalBlendCanalRunsRightRound(name = canal.edge.name.label)
                    }
                FacePatch(name, null, emptyList(), why, absent = true)
            } else if (st.tip) {
                val why =
                    if (canal.bevel) {
                        Msgs.refusalBlendBevelTapersToNothing(name = canal.edge.name.label)
                    } else {
                        Msgs.refusalBlendCanalTapersToNothing(name = canal.edge.name.label)
                    }
                FacePatch(name, null, emptyList(), why, absent = true)
            } else {
                // …and the step is the canal's **own** step-off and not the bare micron: the tool's end ring
                // is moved by [Canal.grow] ([canalMesh]), so that is where the body's own cap actually
                // stands. A cap patch stated a micron past the station instead was forty times a
                // tessellation tolerance away from the facet it names — harmless to a section, and exactly
                // what made a bored canal body's own cap facet lie on no carrier (OP-31, slice 5l).
                val origin = st.at + st.t * (if (atStart) -canal.grow else canal.grow)
                // the normal runs **out of the material**, which at a free end is back along the run
                val plane = if (atStart) Plane3(origin, st.ax, -st.ay) else Plane3(origin, st.ax, st.ay)
                val flip = if (atStart) -1.0 else 1.0
                // **the cap is the section the body keeps and not the ring the tool laid** (OP-31, slice
                // 5l): the tool's own ring stands [Canal.grow] past each wall so that it shares no face
                // with the body, so a cap stated on it reaches a step-off outside the very faces it has to
                // meet, and a section across it could not close over that.
                val poly = st.face.map { Vec2(it.x, it.y * flip) }
                val legs = (poly.size - canal.arcSteps) / 2
                val out = ArrayList<ProfileElement>(2 * legs + 1)
                for (k in 0 until 2 * legs) out.add(ProfileElement.Seg(Segment(poly[k], poly[k + 1])))
                // …and the one piece that closes the ring is what the band's own section ends with: the
                // ball's arc for a canal, and for a **bevel** the ruling itself, which is one straight side
                // (OP-31, slice 5n)
                out.add(
                    if (canal.bevel) {
                        ProfileElement.Seg(Segment(poly[2 * legs], poly[0]))
                    } else {
                        ProfileElement.ArcE(
                            Arc(Vec2(0.0, 0.0), canal.r, (st.a1 + st.sweep * (if (canal.stations.first() === st) 1.0 else 1.0)) * flip, st.a1 * flip, flip * st.sweep < 0.0),
                        )
                    },
                )
                FacePatch(name, plane, out, null, null, null)
            }
        }

    /**
     * **Which way the body moves along a canal's crease, and which side of each face its material is on** —
     * scored once from the body itself ([Geom3.encloses]) and stored in the step as signs (OP-1/OP-18).
     *
     * Three readings, each a containment question about one point and never asked again: the material side
     * of each of the two faces (a cylinder alone needs it — a plane's own normal already states it), and
     * whether the region between the ball and the crease is material or void, which is the whole of
     * *subtract or add*. Null where this edge is no canal case at all.
     */
    private fun canalChoice(
        feature: Feature3,
        mesh: Mesh3,
        edge: SolidEdge,
        sec: BlendSection,
    ): Pair<BlendChoice?, Msg?>? {
        val path = canalPath(edge) ?: return null
        // **a ball and a bevel, and nothing else, run along a crease with no rigid section** (OP-31, slices
        // 5f and 5n). A **drawn profile** is stated in the crease's own normal section and there is no one
        // section to state it in here, so it is refused in the sentence that says which two are — and said
        // rather than left to the catalogue's, which would advise a straight edge instead.
        if (sec.kind != BlendKind.FILLET && sec.kind != BlendKind.CHAMFER) {
            return null to Msgs.refusalBlendCarriesNoRigidSection(name = edge.name.label)
        }
        val r = sec.size
        if (r <= Geom3.WELD_TOL) return null
        val faces = Section3.faces(feature).first ?: return null to Msgs.refusalBlendCanalSpineNotFollowed(name = edge.name.label)
        val f1 = faces.firstOrNull { it.name == edge.between.a } ?: return null to Msgs.refusalBlendCanalSpineNotFollowed(name = edge.name.label)
        val f2 = faces.firstOrNull { it.name == edge.between.b } ?: return null to Msgs.refusalBlendCanalSpineNotFollowed(name = edge.name.label)
        val w1 =
            wallOf(f1) ?: return null to
                Msgs.refusalBlendCanalWallNotStatable(name = edge.name.label, name2 = f1.name.label, name3 = f2.name.label)
        val w2 =
            wallOf(f2) ?: return null to
                Msgs.refusalBlendCanalWallNotStatable(name = edge.name.label, name2 = f2.name.label, name3 = f1.name.label)
        val lens = path.elements.map { pieceLength(it) }
        val h = 1e-5
        val m = alongPath(path, lens, 0.5)
        var tau = alongPath(path, lens, 0.5 + h) - alongPath(path, lens, 0.5 - h)
        if (tau.length() <= Vec3.EPS) return null to Msgs.refusalBlendCanalSpineNotFollowed(name = edge.name.label)
        tau = tau.normalized()
        val delta = r * PROBE_FRACTION
        if (delta <= Geom3.WELD_TOL) return null to Msgs.refusalBlendCanalDoesNotFitAlong(sizePhrase = sec.sizePhrase(), name = edge.name.label)
        // **the sector the ball rolls in, scored once from the body** — [sectorOf]'s own reading, said for
        // two curved walls: the two surfaces cut the normal plane into four sectors, and the one that is
        // different from the other three is the one the rounding goes in (the lone **material** sector at a
        // convex crease, where the ball is inside the material and the removal is what stands outside it;
        // the lone **void** one at a concave crease, where the ball rolls in the air and the fill is added).
        val found = ArrayList<Pair<Int, Int>>(4)
        for (t1 in listOf(1, -1)) {
            for (t2 in listOf(1, -1)) {
                val q =
                    centreAt(w1, w2, m, tau, t1 * delta, t2 * delta) ?: return null to
                        Msgs.refusalBlendCanalDoesNotFitAlong(sizePhrase = sec.sizePhrase(), name = edge.name.label)
                if (Geom3.encloses(mesh, q)) found.add(t1 to t2)
            }
        }
        val sector =
            when (found.size) {
                1 -> found[0]
                3 -> listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1).first { it !in found }
                else -> return null to Msgs.refusalBlendCanalNotSimpleCrease(name = edge.name.label)
            }
        if (sec.kind == BlendKind.CHAMFER) {
            // **and a setback has to stay on the wall it is measured along** (OP-31, slice 5n): a setback
            // trace is an in-face offset of the crease and it degenerates where it leaves the wall's own
            // extent, which the construction itself cannot see — the surface carries on past its own trim.
            // So the body is asked, at stations spread along the crease: a point just **inside** the wall at
            // each setback is material where the bevel is taken and void where it is added, and where it is
            // not the trace has run off the face and the bevel is refused by name.
            val clear = 2.0 * canalGrow(w1, w2)
            // …the region the bevel takes lies on the scored side of each wall, and the **material** is on
            // that same side at a convex crease and on the other at a concave one — so a point stepped off
            // the setback into the material is inside the body in both cases, and where it is not the trace
            // has run off the face it is measured along
            val mat = if (found.size == 1) 1.0 else -1.0
            for (k in 0..8) {
                val probe =
                    bevelRawAt(w1, w2, path, lens, k / 8.0, r, sector.first, sector.second) ?: return null to
                        Msgs.refusalBlendBevelDoesNotFitAlong(sizePhrase = sec.sizePhrase(), name = edge.name.label)
                for ((w, p) in listOf(w1 to probe.p1, w2 to probe.p2)) {
                    val g = w.grad(p) ?: continue
                    val side = (if (w === w1) sector.first else sector.second).toDouble()
                    if (!Geom3.encloses(mesh, p + g * (clear * side * mat))) {
                        return null to Msgs.refusalBlendBevelDoesNotFitAlong(sizePhrase = sec.sizePhrase(), name = edge.name.label)
                    }
                }
            }
        } else {
            // …and the ball has to fit in it: its centre stands `r` from both walls, and the crease's own
            // point has to lie **outside** the ball, or there is no material between the two to take away
            val raw =
                canalRawAt(w1, w2, path, lens, 0.5, r, sector.first, sector.second, tangencyTolOf(edge, w1, w2)) ?: return null to
                    Msgs.refusalBlendCanalDoesNotFitAlong(sizePhrase = sec.sizePhrase(), name = edge.name.label)
            if ((raw.place.at(raw.apex) - raw.at).length() <= r + Geom3.WELD_TOL) {
                return null to Msgs.refusalBlendCanalDoesNotFitAlong(sizePhrase = sec.sizePhrase(), name = edge.name.label)
            }
        }
        val choice = BlendChoice(sector.first, sector.second, 0, found.size == 1)
        // **a gesture is declined where the band cannot be had, not left to fail at build time** (OP-3): the
        // whole construction is cheap enough to ask here, and what it answers is the same sentence the
        // build would have answered with — the ball too large for the crease's own bend above all.
        val (_, whyCanal) = canalOf(feature, edge, 0, sec, choice) ?: return choice to null
        if (whyCanal != null) return null to whyCanal
        return choice to null
    }

    /** Every canal band of this level and of the chain under it — [chainPieces]' twin, one construction over. */
    private fun canalsOf(feature: Feature3): List<Canal> {
        val out = ArrayList<Canal>()
        var f = feature
        while (f is Feature3.Blend) {
            val below = f.base
            val (edges, _) = Section3.edges(below)
            if (edges != null) {
                for ((k, i) in f.targets.withIndex()) {
                    if (f.isAbsent(k)) continue
                    val sec = f.sections.getOrNull(k) ?: continue
                    val edge = edges.getOrNull(i) ?: continue
                    val choice = f.choices.getOrNull(k) ?: continue
                    out.add(canalOf(below, edge, i, sec, choice)?.first ?: continue)
                }
            }
            f = below
        }
        return out
    }

    /**
     * **Where a canal band ends the band it runs along** — one table per end it bites into, read the way
     * [runsInto] reads a band that runs into another: the canal's own rail *is* that band's new end, and it
     * stands nearer the run's middle than the mitre the crossing left there.
     *
     * The pairing is structural (OP-21): a canal's two walls are named faces, so the band it ends is the one
     * whose name a wall carries — never measured, never searched for among the triangles.
     */
    private fun canalSetbacks(
        pieces: List<Piece>,
        at: Int,
        canals: List<Canal>,
    ): List<Triple<Boolean, List<Vec2>, List<Double>>> {
        val piece = pieces[at]
        val out = ArrayList<Triple<Boolean, List<Vec2>, List<Double>>>()
        val len = piece.length
        for (canal in canals) {
            for (side in 0..1) {
                val name = (if (side == 0) canal.w1 else canal.w2).patch.name as? FaceName.BlendBand ?: continue
                if (name.edge != piece.index) continue
                val qs = ArrayList<Vec2>()
                val ss = ArrayList<Double>()
                // …and the rail runs as far as the tool does (OP-31, slice 5l): a free end's ring is
                // carried the canal's own step-off past the last station, so the neighbour it bites is
                // set back to **that** ring and not to a station the body no longer ends at
                for (st in canal.run) {
                    val p = if (side == 0) st.p1 else st.p2
                    val s = stationOf(piece, p)
                    val place = placeAt(piece, s) ?: continue
                    val rel = p - place.origin
                    qs.add(Vec2(rel.dot(place.cx), rel.dot(place.cy)))
                    ss.add(s)
                }
                if (qs.size < 2) continue
                out.add(Triple(ss.average() < len / 2.0, qs, ss))
            }
        }
        return out
    }

    /** Where the table [qs] → [ss] stands at section point [p] — the nearest span of the rail, interpolated. */
    private fun setbackAt(
        p: Vec2,
        qs: List<Vec2>,
        ss: List<Double>,
    ): Double? {
        var best = -1
        var bestD = Double.MAX_VALUE
        for (k in qs.indices) {
            val d = (qs[k] - p).length()
            if (d < bestD) {
                bestD = d
                best = k
            }
        }
        if (best < 0) return null
        var answer = ss[best]
        var span = Double.MAX_VALUE
        for (k in listOf(best - 1, best)) {
            if (k < 0 || k + 1 >= qs.size) continue
            val a = qs[k]
            val b = qs[k + 1]
            val v = b - a
            val l2 = v.dot(v)
            if (l2 <= 1e-18) continue
            val t = ((p - a).dot(v) / l2).coerceIn(0.0, 1.0)
            val d = (a + v * t - p).length()
            if (d < span) {
                span = d
                answer = ss[k] + (ss[k + 1] - ss[k]) * t
            }
        }
        return answer
    }

    /**
     * Where [cut] crosses a canal band — **sampled**, and the first reading in this drawing that has to be.
     *
     * A [Section3.RuledStrip] is a family of straight rulings and a canal band has none: its characteristic
     * curves are the ball's own circles, one per station, and they lie in planes that turn along the run. So
     * the cut is read the way the band is built — station by station, the ball's arc against the plane — and
     * the runs come back as chords, flagged (OP-15). Every one of those points is **exact** on the band's own
     * surface; only the chords between them are not, which is the same honesty class the rails are in.
     */
    internal fun canalCut(
        f: Feature3.Blend,
        edge: Int,
        cut: Plane3,
    ): Revolve3.BandCut? {
        val canal = canalsOf(f).firstOrNull { it.index == edge } ?: return null
        val n = cut.normal.normalized()
        val d = cut.origin.dot(n)
        // **the band is marched on its own chart**, station by station *and* along the ball's own arc, and
        // that second axis is not a nicety: a canal band is a ribbon that travels, so a level plane crosses
        // it **across** the run rather than along it, and a reader that only sampled the stations would find
        // one point where the cut has a whole curve. The chart is `(station, arc)`, the crossing is the zero
        // isoline of the plane's own signed distance on it, and the cells are walked as squares.
        // …and a **bevel**'s band is marched on the very same chart with `t` along its own **ruling** in
        // place of the ball's arc (OP-31, slice 5n): a point interpolated along a straight ruling is exactly
        // on the strip, so what comes back is exact where the plane crosses each ruling and chords between
        // two rulings — the same honesty class, and the same reader, because a level plane crosses a strip
        // that travels **across** the run exactly as it crosses a canal band that does.
        val arcs = max(8, canal.arcSteps)
        // …and the march covers the run the body has, each free end carried to the ring its own cap closes
        // on ([Canal.run]) — a cut that stopped at the last station stopped a step-off short of the cap and
        // the section could not close over the gap (OP-31, slice 5l)
        val at = ArrayList<List<Vec3>>(canal.run.size)
        for (st in canal.run) {
            at.add(
                (0..arcs).map { j ->
                    if (canal.bevel) {
                        st.p1 + (st.p2 - st.p1) * (j.toDouble() / arcs)
                    } else {
                        val a = st.a1 + st.sweep * j / arcs
                        st.at + st.ax * (canal.r * cos(a)) + st.ay * (canal.r * sin(a))
                    }
                },
            )
        }
        val segs = ArrayList<Pair<Vec2, Vec2>>()
        for (k in 0 until (if (canal.closed) at.size else at.size - 1)) {
            val next = (k + 1) % at.size
            for (j in 0 until arcs) {
                val corners = listOf(at[k][j], at[k][j + 1], at[next][j + 1], at[next][j])
                val fs = corners.map { it.dot(n) - d }
                val hits = ArrayList<Vec2>(4)
                for (m in 0 until 4) {
                    val a = fs[m]
                    val b = fs[(m + 1) % 4]
                    if ((a > 0.0 && b > 0.0) || (a < 0.0 && b < 0.0) || a == b) continue
                    val t = (a / (a - b)).coerceIn(0.0, 1.0)
                    hits.add(cut.toLocal(corners[m] + (corners[(m + 1) % 4] - corners[m]) * t))
                }
                if (hits.size >= 2) segs.add(hits[0] to hits[1])
                if (hits.size >= 4) segs.add(hits[2] to hits[3])
            }
        }
        val runs = chainSegments(segs)
        return if (runs.isEmpty()) null else Revolve3.BandCut(null, runs)
    }

    /** Marched segments joined end to end into the fewest polylines — the cells share their crossings exactly. */
    private fun chainSegments(segs: List<Pair<Vec2, Vec2>>): List<List<Vec2>> {
        val left = segs.filter { (it.first - it.second).length() > Geom3.WELD_TOL }.toMutableList()
        val out = ArrayList<List<Vec2>>()
        while (left.isNotEmpty()) {
            val run = ArrayList<Vec2>()
            val seed = left.removeAt(0)
            run.add(seed.first)
            run.add(seed.second)
            var grew = true
            while (grew) {
                grew = false
                for (i in left.indices) {
                    val (a, b) = left[i]
                    when {
                        (a - run.last()).length() <= Geom3.WELD_TOL -> run.add(b)
                        (b - run.last()).length() <= Geom3.WELD_TOL -> run.add(a)
                        (a - run.first()).length() <= Geom3.WELD_TOL -> run.add(0, b)
                        (b - run.first()).length() <= Geom3.WELD_TOL -> run.add(0, a)
                        else -> continue
                    }
                    left.removeAt(i)
                    grew = true
                    break
                }
            }
            out.add(run)
        }
        return out
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
