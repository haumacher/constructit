package constructit.geom

import constructit.l10n.Msg
import constructit.l10n.Msgs
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One **section of a skin** (OP-26, the loft over drawn sections): the area as it is drawn, on the plane of
 * the station it is drawn on, together with [at] — the station's own stated distance along the run.
 *
 * The distance is carried as a value rather than an order, because it is what the refusals speak about: a
 * skin that folds names *the two stations by their distances* and never the sampling (the session-65 law).
 * The **order** the sections stand in is structural — decided once, from these distances, by the gesture that
 * built the body (OP-21) — so this list arrives sorted and two sections at one distance refuse by name.
 */
data class SkinSection(val sketch: Sketch3, val at: Double)

/**
 * How a skin runs **between** its sections — the one structural choice of the feature, stated by which tool
 * row built it and therefore recorded by recording the tool (OP-1/OP-18, the `curve3`/`curve3smooth` and
 * `helix`/`helixleft` precedent).
 *
 * [RULED] is linear between corresponding points of consecutive sections: every ruling is a straight line, so
 * a skin between two parallel polygons is a **prismatoid** and its volume is exact.
 * [FAIRED] runs one interpolating cubic per correspondence family through **all** the stations
 * ([Curves3.smoothThrough]), so a three-station skin passes through the middle section instead of kinking at
 * it.
 */
enum class SkinRow { RULED, FAIRED }

/**
 * A **stated correspondence** between two curves of consecutive sections — the user's own mechanism, and the
 * load-bearing half of this feature (see DESIGN.md's as-built note).
 *
 * [a] is a piece of section [interval] and [b] a piece of section `interval + 1`, both in the section's own
 * boundary order ([Geom3.boundaryPieces]'s order, which is what a stored address already means). Nothing
 * about it is measured: a Match is a click, recorded by the curves' script names on the loft's own step, and
 * the skin reads it exactly as stated.
 */
data class SkinMatch(val interval: Int, val a: Int, val b: Int)

/**
 * **The loft: a skin over drawn sections** (OP-26's hull route — queue entry 1, ruled by the user in session
 * 77; see DESIGN.md for the design record and the correspondence design, which is the user's own).
 *
 * An ordered run of two or more closed sections, each an ordinary sketch on a **station of one common spine**,
 * skinned by one strip per (interval × piece) and capped at both ends with the end sections' own exact planar
 * regions. The whole of it is a pure function of values, returning `result to reason`, so everything it
 * declines becomes an invalid node with a reason and heals (OP-3).
 *
 * **Correspondence is stated, never discovered**, and that is the difference between this feature and the
 * session-23 [Feature3.Loft] beside it (whose correspondence is a global boundary parameter and a scored
 * seam). Four rules, all of them the user's:
 *
 * 1. **Loop orientation is normalized by area sign** — a fact, not a guess. Every station's plane has the
 *    spine's tangent for its normal, so a loop that turns counter-clockwise in its own station frame turns
 *    the same way about the run as every other one, and the mirrored correspondence that makes rails cross
 *    cannot arise.
 * 2. **Equal piece counts pair by the outline's cyclic traversal order**, with nothing stored.
 * 3. **A stated pair anchors the walk**, and the *first* stated pair is also the **seam** — it outranks the
 *    drawn-order inference, which is what lets a Match deliberately twist an equal-count skin's alignment
 *    (explicit anchors beat compensation). Pieces pair in traversal order from each anchor onward.
 * 4. **What nobody mapped collapses to the point between its mapped neighbours' images** — a triangle fan to
 *    one shared vertex, which is how a rectangle honestly becomes a triangle and stays watertight. Where real
 *    strips are wanted instead, the cure is a **Break**, which makes the equalized counts facts of the
 *    drawing rather than a rule inside this file.
 *
 * **Tier honesty.** A strip is a ruled or faired band, so the body is the **mesh tier** ([LOFT_ONLY], beside
 * the sweep's own refusal): there is no analytic surface to hand a consumer that needs one, and nothing
 * degrades silently. What it *does* have is a **constructed** face list — one entry per strip plus the two
 * caps, in a stated order, so a click resolves to a face with a stored address exactly as it does on a shell
 * ([Section3.FACE_ADDRESS_CONVENTION]) — and a strip whose corners happen to be coplanar is a plane you can
 * sketch on, while one that is not says so and says by how much.
 *
 * **One emission convention, stated because a volume depends on it.** A strip's quad `(j, i)` is split into
 * the triangles `(j,i) (j+1,i) (j+1,i+1)` and `(j,i) (j+1,i+1) (j,i+1)` — the diagonal from its own lower
 * rail. Where the four corners are coplanar the split is immaterial and the body is exactly the prismatoid
 * its rings state (which is why a frustum's volume is assertable to the last bit); where they are **not**,
 * the two possible diagonals are two different bodies, and this is the one the drawing means. A rectangle
 * running to a triangle always has one such strip — three of its four sides cannot be parallel to their
 * partners and close a triangle — so the convention is part of the feature rather than an implementation
 * detail, and it is asserted as such (`SkinTest`).
 */
object Skin3 {
    /**
     * Why a skin has nothing exact to offer a consumer that needs an analytic reading — the sweep's own
     * sentence, one feature along ([Section3] cites it, and so do the prismatic and the horizontal-section
     * routes).
     */
    val LOFT_ONLY =
        Msgs.refusalSkinThisSolidIsSkinOver()

    /**
     * How many rows a **faired** interval is drawn with — deterministic and never adaptive (OP-15's rule for
     * a curve the vocabulary cannot state), and irrelevant to every refusal (session 65: a refusal is a claim
     * about the drawing, so nothing that changes when the same drawing is meshed more finely may change it).
     *
     * A **ruled** interval is one row by nature: a chord of a straight ruling *is* the ruling, so the skin
     * between two sections is the prismatoid its two rings state and its volume is exact.
     */
    const val FAIR_ROWS = 8

    /** How far out of plane a strip's corners may stand and still be one plane a sketch can open on (mm). */
    private const val FLAT_TOL = 1e-6

    // ---- the value half: everything decided before a triangle exists ----

    /** One boundary piece of a section, in the loop's **normalized** traversal: its own index and its points. */
    internal class Piece(val at: Int, val pts: List<Vec2>)

    /** One section resolved to values: its plane, its region and its normalized pieces. */
    internal class Prep(
        val plane: Plane3,
        val region: Region,
        /**
         * The outline's pieces in the **normalized** walk — counter-clockwise in this section's own frame,
         * hence the same way round the run as every other section's, each carrying the index it was *drawn*
         * at so a stated match still names the curve the user clicked.
         */
        val pieces: List<Piece>,
    ) {
        /** Where drawn piece [i] stands in the normalized walk. */
        fun positionOf(i: Int): Int = pieces.indexOfFirst { it.at == i }
    }

    /**
     * One **strip** of one interval: the piece of the lower section and the piece of the upper one it runs
     * to, with exactly one of them absent where that side collapses to a point.
     *
     * [family] is the correspondence family both pieces belong to — the chain of mapped pieces through the
     * whole run, which is what a faired rail is interpolated along and what makes every strip's boundary rail
     * shared with its neighbour rather than merely equal to it.
     */
    internal class Strip(val lower: Int?, val upper: Int?, val family: Int)

    /**
     * One **correspondence family**: at most one piece per section, chained by the mappings of consecutive
     * intervals (so a family occupies a consecutive run of stations), sampled at one count.
     *
     * [count] is the number of sub-segments every member is resampled to — the *finest* tessellation among
     * them, so nothing about any member's shape is lost and every mapped pair has the same number of rails
     * (which is what keeps the strips free of T-junctions).
     */
    internal class Family(val at: IntArray, var count: Int) {
        /** The ring index this family collapses to at a station it has no piece at, or -1. */
        val apex = HashMap<Int, Int>()

        val first: Int get() = at.indexOfFirst { it >= 0 }
        val last: Int get() = at.indexOfLast { it >= 0 }
    }

    /**
     * The **plan** of a skin: the sections prepped, the correspondence resolved, the rings sampled and every
     * refusal already made — the mesh below and the face list beside it read the identical answers, which is
     * the discipline the loft's own `LoftPlan` exists for (one authority, two readers).
     */
    internal class SkinPlan(
        val sections: List<SkinSection>,
        val row: SkinRow,
        val preps: List<Prep>,
        val families: List<Family>,
        /** per interval, the strips in the cyclic order the walk produced */
        val strips: List<List<Strip>>,
        /** per section, the ring in that section's own plane coordinates */
        val ring2: List<List<Vec2>>,
        /** per section, the same ring in the world */
        val ringW: List<List<Vec3>>,
        /** per section, where the normalized piece at position p starts in the ring */
        val starts: List<IntArray>,
    ) {
        val intervals: Int get() = sections.size - 1

        /** The world point of rail [j] of [family] at station [k] — a ring point, or the apex it collapses to. */
        fun railPoint(
            family: Int,
            j: Int,
            k: Int,
        ): Vec3 {
            val f = families[family]
            val pos = f.at[k]
            val ring = ringW[k]
            if (pos < 0) return ring[f.apex[k] ?: 0]
            return ring[(starts[k][pos] + j) % ring.size]
        }

        /** The stations rail curves of [family] run through — its own span, plus the ends it fans into. */
        fun span(family: Int): IntRange {
            val f = families[family]
            var lo = f.first
            var hi = f.last
            if (f.apex.containsKey(lo - 1)) lo -= 1
            if (f.apex.containsKey(hi + 1)) hi += 1
            return lo..hi
        }
    }

    /**
     * The skin over [sections] — the whole feature, refusals and all.
     *
     * Every refusal is made here, before a triangle exists, which is what lets the body be handed to
     * [Solid3.derivedFine] refusal-free (OP-3, OP-9: watertight or refused).
     */
    fun skin(
        sections: List<SkinSection>,
        row: SkinRow,
        matches: List<SkinMatch> = emptyList(),
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Pair<Solid3?, Msg?> {
        val (plan, why) = plan(sections, row, matches, tolMm)
        if (plan == null) return null to why
        return shell(plan, matches)
    }

    /**
     * The value half — see [SkinPlan]. Comes back as `null to reason` so the node is invalid **with a named
     * reason** and heals when the drawing moves (OP-3).
     */
    internal fun plan(
        sections: List<SkinSection>,
        row: SkinRow,
        matches: List<SkinMatch>,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Pair<SkinPlan?, Msg?> {
        if (sections.size < 2) {
            return null to Msgs.refusalSkinLoftNeedsLeastTwoSections()
        }
        for (k in 0 until sections.size - 1) {
            if (abs(sections[k + 1].at - sections[k].at) <= Geom3.WELD_TOL) {
                return null to
                    Msgs.refusalSkinSectionsStandSameDistanceAlong(k = k + 1, k2 = k + 2, mm = Frames3.mm(sections[k].at))
            }
        }
        val preps = ArrayList<Prep>(sections.size)
        for ((k, s) in sections.withIndex()) {
            val (prep, whyPrep) = prepOf(s, k, tolMm)
            if (prep == null) return null to whyPrep
            preps.add(prep)
        }

        // ---- the correspondence, interval by interval: stated pairs first, traversal order after ----
        val strips = ArrayList<List<Strip>>(sections.size - 1)
        val raw = ArrayList<List<Pair<Int?, Int?>>>(sections.size - 1)
        for (k in 0 until sections.size - 1) {
            val (pairs, whyPairs) = pairing(preps[k], preps[k + 1], k, matches)
            if (pairs == null) return null to whyPairs
            raw.add(pairs)
        }

        // ---- families: the chains of mapped pieces, and the count every member is sampled at ----
        val families = familiesOf(preps, raw)
        for (f in families) {
            var count = 1
            for (k in preps.indices) {
                val pos = f.at[k]
                if (pos >= 0) count = max(count, preps[k].pieces[pos].pts.size - 1)
            }
            f.count = count
        }
        val famOf = preps.indices.map { k -> IntArray(preps[k].pieces.size) { -1 } }
        for ((fi, f) in families.withIndex()) {
            for (k in preps.indices) if (f.at[k] >= 0) famOf[k][f.at[k]] = fi
        }

        // ---- the rings: one shared point list per section, so a junction vertex is one vertex ----
        val ring2 = ArrayList<List<Vec2>>(preps.size)
        val starts = ArrayList<IntArray>(preps.size)
        for (k in preps.indices) {
            val pts = ArrayList<Vec2>()
            val st = IntArray(preps[k].pieces.size)
            for ((pos, piece) in preps[k].pieces.withIndex()) {
                st[pos] = pts.size
                val n = families[famOf[k][pos]].count
                val sampled = resample(piece.pts, n)
                for (j in 0 until n) pts.add(sampled[j])
            }
            if (pts.size < 3) return null to Msgs.refusalSkinSectionGivesFewerThanThree(k = k + 1)
            ring2.add(pts)
            starts.add(st)
        }
        val ringW = preps.indices.map { k -> ring2[k].map { preps[k].plane.toWorld(it) } }

        // ---- the strips, with each collapsing side's apex resolved to a ring vertex ----
        for (k in 0 until sections.size - 1) {
            val out = ArrayList<Strip>(raw[k].size)
            val pairs = raw[k]
            for ((i, p) in pairs.withIndex()) {
                val (a, b) = p
                if (a != null && b != null) {
                    out.add(Strip(a, b, famOf[k][a]))
                    continue
                }
                if (a != null) {
                    val fi = famOf[k][a]
                    families[fi].apex[k + 1] = apexAfter(pairs, i, starts[k + 1])
                    out.add(Strip(a, null, fi))
                } else {
                    val fi = famOf[k + 1][b!!]
                    families[fi].apex[k] = apexBefore(pairs, i, starts[k])
                    out.add(Strip(null, b, fi))
                }
            }
            strips.add(out)
        }
        val plan = SkinPlan(sections, row, preps, families, strips, ring2, ringW, starts)

        // ---- the skin's own criteria, per interval and spoken about the stations ----
        for (k in 0 until plan.intervals) {
            val why = advances(plan, k)
            if (why != null) return null to why
        }
        return plan to null
    }

    /** One section as values: one hole-free area, tessellated per piece, turned counter-clockwise. */
    private fun prepOf(
        s: SkinSection,
        k: Int,
        tolMm: Double,
    ): Pair<Prep?, Msg?> {
        if (s.sketch.regions.size != 1) {
            return null to Msgs.refusalSkinLoftSectionIsOneArea(k = k + 1, count = s.sketch.regions.size)
        }
        val region = s.sketch.regions[0]
        if (region.holes.isNotEmpty()) {
            return null to
                Msgs.refusalSkinSectionHasHoleSkinPairs(k = k + 1)
        }
        val loop = region.outer
        if (loop.elements.isEmpty()) return null to Msgs.refusalSkinSectionHasNoBoundaryRun(k = k + 1)
        val drawn = loop.elements.map { GeomMath.tessellatePiece(it, tolMm) }
        if (drawn.any { it.size < 2 }) return null to Msgs.refusalSkinPieceSectionOutlineHasNo(k = k + 1)
        val area = Geom3.polygonArea(Geom3.tessellateLoop(loop, tolMm))
        if (abs(area) <= 1e-12) return null to Msgs.refusalSkinSectionEnclosesNoArea(k = k + 1)
        // **Orientation is a fact, not a guess** (the user's rule): every station's normal is the run's own
        // tangent, so a loop turned counter-clockwise in its own frame turns the same way about the run as
        // every other section's, and the mirrored correspondence cannot arise at all.
        val flipped = area < 0.0
        val pieces =
            if (!flipped) {
                drawn.mapIndexed { i, pts -> Piece(i, pts) }
            } else {
                drawn.indices.reversed().map { i -> Piece(i, drawn[i].reversed()) }
            }
        return Prep(s.sketch.plane, region, pieces) to null
    }

    /**
     * The pairing of one interval: `(lower piece, upper piece)` in cyclic order, with a null where that side
     * collapses — positions in the **normalized** walk, never the drawn order.
     */
    private fun pairing(
        lower: Prep,
        upper: Prep,
        interval: Int,
        matches: List<SkinMatch>,
    ): Pair<List<Pair<Int?, Int?>>?, Msg?> {
        val nA = lower.pieces.size
        val nB = upper.pieces.size
        val stated = ArrayList<Pair<Int, Int>>()
        for (m in matches.filter { it.interval == interval }) {
            val a = lower.positionOf(m.a)
            val b = upper.positionOf(m.b)
            if (a < 0 || b < 0) {
                return null to
                    Msgs.refusalSkinStatedMatchNamesCurveThat(ifWord = if (a < 0) interval + 1 else interval + 2)
            }
            stated.add(a to b)
        }
        for (i in stated.indices) {
            for (j in i + 1 until stated.size) {
                if (stated[i].first == stated[j].first || stated[i].second == stated[j].second) {
                    return null to
                        Msgs.refusalSkinOneCurveSectionIsMatched(interval = interval + 1, interval2 = interval + 2)
                }
            }
        }
        if (stated.isEmpty() && nA != nB) {
            return null to
                Msgs.refusalSkinSectionHasPiecesSectionHas(interval = interval + 1, nA = nA, interval2 = interval + 2, nB = nB)
        }
        val anchors = if (stated.isEmpty()) listOf(0 to 0) else stated
        // **The first stated pair is the seam**: the walk starts there, so a Match twists an equal-count skin
        // (explicit anchors beat compensation — OP-26's own rule).
        val seam = anchors[0]
        val order = anchors.sortedBy { (it.first - seam.first).mod(nA) }
        for (i in 1 until order.size) {
            val prev = (order[i - 1].second - seam.second).mod(nB)
            val here = (order[i].second - seam.second).mod(nB)
            if (here <= prev) {
                return null to
                    Msgs.refusalSkinStatedMatchesCrossPieceSection(first = order[i - 1].first + 1, interval = interval + 1, second = order[i - 1].second + 1, interval2 = interval + 2, first2 = order[i].first + 1, second2 = order[i].second + 1)
            }
        }
        val out = ArrayList<Pair<Int?, Int?>>()
        for (i in order.indices) {
            val (p, q) = order[i]
            val (p2, q2) = order[(i + 1) % order.size]
            val dA = (p2 - p).mod(nA)
            val dB = (q2 - q).mod(nB)
            val gapA = if (dA == 0) nA - 1 else dA - 1
            val gapB = if (dB == 0) nB - 1 else dB - 1
            out.add(p to q)
            val paired = min(gapA, gapB)
            for (t in 1..paired) out.add((p + t).mod(nA) to (q + t).mod(nB))
            // …and the surplus at the far end of the gap is what fans, which is the whole of the degeneracy
            for (t in paired + 1..gapA) out.add((p + t).mod(nA) to null)
            for (t in paired + 1..gapB) out.add(null to (q + t).mod(nB))
        }
        return out to null
    }

    /** The families: a piece and everything it is mapped to, chained through consecutive intervals. */
    private fun familiesOf(
        preps: List<Prep>,
        raw: List<List<Pair<Int?, Int?>>>,
    ): List<Family> {
        val id = preps.indices.map { k -> IntArray(preps[k].pieces.size) { -1 } }
        val out = ArrayList<Family>()

        fun fresh(): Family {
            val f = Family(IntArray(preps.size) { -1 }, 1)
            out.add(f)
            return f
        }
        for (k in preps.indices) {
            for (pos in preps[k].pieces.indices) {
                if (id[k][pos] >= 0) continue
                val f = fresh()
                val fi = out.size - 1
                var kk = k
                var pp = pos
                while (true) {
                    f.at[kk] = pp
                    id[kk][pp] = fi
                    if (kk >= raw.size) break
                    val next = raw[kk].firstOrNull { it.first == pp && it.second != null }?.second ?: break
                    if (id[kk + 1][next] >= 0) break
                    kk += 1
                    pp = next
                }
            }
        }
        return out
    }

    /** The upper ring vertex a collapsing lower piece fans to: where its mapped neighbours' images meet. */
    private fun apexAfter(
        pairs: List<Pair<Int?, Int?>>,
        i: Int,
        starts: IntArray,
    ): Int {
        val n = pairs.size
        for (d in 1..n) {
            val p = pairs[(i + d) % n]
            if (p.second != null) return starts[p.second!!]
        }
        return 0
    }

    /** The lower ring vertex a collapsing upper piece fans to — [apexAfter]'s mirror. */
    private fun apexBefore(
        pairs: List<Pair<Int?, Int?>>,
        i: Int,
        starts: IntArray,
    ): Int {
        val n = pairs.size
        for (d in 1..n) {
            val p = pairs[(i + d) % n]
            if (p.first != null) return starts[p.first!!]
        }
        return 0
    }

    /**
     * Whether interval [k]'s skin runs *forward* everywhere, or the reason it folds — **spoken about the two
     * stations by their distances**, never about how finely anything was sampled (the session-65 law).
     *
     * Two ways a strip can be a fold rather than a skin, and they are the loft's own two: a ruling that runs
     * backwards along the interval (which is what two station planes crossing inside the body looks like where
     * it matters), and two rulings that **meet** in mid-run, which is what a crossed correspondence does.
     */
    private fun advances(
        plan: SkinPlan,
        k: Int,
    ): Msg? {
        val d = centre(plan.ringW[k + 1]) - centre(plan.ringW[k])
        if (d.length() <= Geom3.WELD_TOL) {
            return Msgs.refusalSkinStationsMmMmAlongRun(mm = Frames3.mm(plan.sections[k].at), mm2 = Frames3.mm(plan.sections[k + 1].at), k = k + 1, k2 = k + 2)
        }
        val dir = d.normalized()
        val lo = ArrayList<Vec3>()
        val hi = ArrayList<Vec3>()
        for (strip in plan.strips[k]) {
            val f = plan.families[strip.family]
            for (j in 0..f.count) {
                val a = plan.railPoint(strip.family, j, k)
                val b = plan.railPoint(strip.family, j, k + 1)
                lo.add(a)
                hi.add(b)
                // a collapsing strip legitimately has every ruling ending at one point, so the advance is
                // asked of the rulings that still have length
                if ((b - a).length() > Geom3.WELD_TOL && (b - a).dot(dir) <= 0.0) {
                    return fold(plan, k, Msgs.refusalSkinRunBackwards())
                }
            }
        }
        if (Geom3.crossingRails(lo, hi) != null) return fold(plan, k, Msgs.wordSkinCrossingRails())
        return null
    }

    private fun fold(
        plan: SkinPlan,
        k: Int,
        what: Msg,
    ): Msg =
        Msgs.refusalSkinSkinBetweenStationMmAlong(mm = Frames3.mm(plan.sections[k].at), mm2 = Frames3.mm(plan.sections[k + 1].at), k = k + 1, k2 = k + 2, what = what)

    private fun centre(ring: List<Vec3>): Vec3 {
        var s = Vec3.ZERO
        for (p in ring) s += p
        return s * (1.0 / ring.size)
    }

    /** [pts] resampled to [n] sub-segments, **arc-length proportionally**, both ends exact. */
    internal fun resample(
        pts: List<Vec2>,
        n: Int,
    ): List<Vec2> {
        if (n <= 1) return listOf(pts.first(), pts.last())
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + (pts[i] - pts[i - 1]).length()
        val total = cum[pts.size - 1]
        if (total <= Geom3.WELD_TOL) return List(n + 1) { pts.first() }
        val out = ArrayList<Vec2>(n + 1)
        out.add(pts.first())
        for (j in 1 until n) {
            val target = total * j / n
            var i = 1
            while (i < pts.size - 1 && cum[i] < target) i++
            val len = cum[i] - cum[i - 1]
            val f = if (len <= Geom3.WELD_TOL) 0.0 else (target - cum[i - 1]) / len
            out.add(pts[i - 1] + (pts[i] - pts[i - 1]) * f)
        }
        out.add(pts.last())
        return out
    }

    // ---- the mesh half: the rails, the strips and the two caps ----

    /**
     * The skin's triangles — and its two caps, which are the end sections' **own exact planar regions**,
     * triangulated and conformed to the ring the strips built (the T-junction rule a prism's caps follow).
     */
    private fun shell(
        plan: SkinPlan,
        matches: List<SkinMatch>,
    ): Pair<Solid3?, Msg?> {
        val caps = ArrayList<Triple<Prep, List<Geom3.Tri3>, Boolean>>(2)
        for (k in listOf(0, plan.sections.size - 1)) {
            val prep = plan.preps[k]
            // **The cap is triangulated over the strips' own ring**, not over a second tessellation of the
            // region — which is the T-junction rule a prism's caps follow, taken one step further. A prism
            // conforms its cap to the ring afterwards ([Geom3.splitToRequired]) because both come from the
            // same tessellation; a skin's ring is *resampled* at its correspondence's own counts, so a cap
            // built from the region's own points would keep vertices the strips have not got — a crack. The
            // ring's points all lie **on** the section's boundary, so nothing about the shape is lost, and
            // the cap's own **face** still carries the region's exact outline, arcs included ([faces]).
            val (tris, why) = Geom3.triangulate(Geom3.TessRegion(plan.ring2[k], emptyList()))
            if (tris == null) return null to Msgs.refusalSectionOfIndex(k = k + 1, reason = why ?: Msgs.refusalSolidSectionCannotBeTriangulated())
            val split = tris
            val runDir =
                if (k == 0) {
                    centre(plan.ringW[1]) - centre(plan.ringW[0])
                } else {
                    centre(plan.ringW[k]) - centre(plan.ringW[k - 1])
                }
            val n = prep.plane.normal.normalized()
            // out of the material: against the run at the first section, along it at the last
            val outward = if (k == 0) n.dot(runDir) < 0.0 else n.dot(runDir) > 0.0
            caps.add(Triple(prep, split, outward))
        }
        val feature = Feature3.Skin(plan.sections, plan.row, matches)
        // **One level** ([Solid3.meshAt]): a skin's row count comes from its correspondence — the families'
        // sampling counts and, for a faired row, the interpolant through every station — all of which live in
        // the plan above, so coarsening the picture would mean redoing the expensive half to save the cheap one.
        //
        // **And built here rather than on demand** (session 82): a stated pair can turn the correspondence far
        // enough that the band folds over itself, which is a fact about the *rings* and not about the mesh —
        // so the rows are laid out first, every quad the correspondence states is asked, and only then is a
        // triangle emitted. *Watertight or refused* (OP-9) outranks the laziness the deferral bought.
        val rails = HashMap<Long, List<Vec3>>()

        fun railOf(
            family: Int,
            j: Int,
        ): List<Vec3> =
            rails.getOrPut(family.toLong() * 4096L + j) {
                val span = plan.span(family)
                span.map { k -> plan.railPoint(family, j, k) }
            }

        // the rows of every band, in the order they are emitted in — laid out once and read twice
        val banded = ArrayList<List<List<Vec3>>>()
        val bandSteps = ArrayList<Int>()
        for (k in 0 until plan.intervals) {
            for (strip in plan.strips[k]) {
                val f = plan.families[strip.family]
                val span = plan.span(strip.family)
                val steps = if (plan.row == SkinRow.RULED) 1 else FAIR_ROWS
                banded.add((0..f.count).map { j -> rowsOf(railOf(strip.family, j), span, k, plan.row, steps) })
                bandSteps.add(steps)
            }
        }
        // ---- the correspondence's own refusal, and nothing emitted ----
        for ((bi, rows) in banded.withIndex()) {
            for (i in 0 until bandSteps[bi]) {
                for (j in 0 until rows.size - 1) {
                    if (!Geom3.foldedQuad(rows[j][i], rows[j + 1][i], rows[j + 1][i + 1], rows[j][i + 1])) continue
                    return null to
                        Msgs.refusalSkinCorrespondenceFoldsThisSkinShell(mm = Frames3.mm(rows[j][i].x), mm2 = Frames3.mm(rows[j][i].y), mm3 = Frames3.mm(rows[j][i].z))
                }
            }
        }
        val shell =
            run {
                val mb = Geom3.MeshBuilder()
                for ((bi, rows) in banded.withIndex()) {
                    for (i in 0 until bandSteps[bi]) {
                        for (j in 0 until rows.size - 1) {
                            mb.triangle(rows[j][i], rows[j + 1][i], rows[j + 1][i + 1])
                            mb.triangle(rows[j][i], rows[j + 1][i + 1], rows[j][i + 1])
                        }
                    }
                }
                for ((prep, split, outward) in caps) {
                    for (t in split) {
                        val a = prep.plane.toWorld(t.a)
                        val b = prep.plane.toWorld(t.b)
                        val c = prep.plane.toWorld(t.c)
                        // a cap triangle maps to the world with its normal along +plane.normal, so it is emitted as
                        // it stands exactly where that normal is the one out of the material
                        if (outward) mb.triangle(a, b, c) else mb.triangle(a, c, b)
                    }
                }
                mb.build()
            }
        // …and the shell itself, against the two degenerate closed shells the gate names — the backstop for a
        // correspondence that folds in a way no single quad does ([MeshCanon.fault], flap and hollow)
        MeshCanon.fault(shell)?.let {
            return null to
                Msgs.refusalSkinCorrespondenceFoldsThisSkinShell2(itWord = it)
        }
        return Solid3.derivedFine(feature) { shell } to null
    }

    /**
     * The [steps]`+ 1` points one rail contributes to interval [k]: the straight chord for a **ruled** row,
     * and the interpolating cubic's own span for a **faired** one ([Curves3.smoothThrough] over every station
     * the rail runs through, which is what makes a faired skin pass *exactly* through its middle sections).
     */
    private fun rowsOf(
        rail: List<Vec3>,
        span: IntRange,
        k: Int,
        row: SkinRow,
        steps: Int,
    ): List<Vec3> {
        val i = k - span.first
        val a = rail[i]
        val b = rail[i + 1]
        if (row == SkinRow.RULED || rail.size < 3) {
            return (0..steps).map { s -> a + (b - a) * (s.toDouble() / steps) }
        }
        val pieces = Curves3.smoothThrough(rail, closed = false)
        val piece = pieces.getOrNull(i) ?: return (0..steps).map { s -> a + (b - a) * (s.toDouble() / steps) }
        return (0..steps).map { s -> Frames3.pointAt(piece, s.toDouble() / steps) }
    }

    // ---- the face list: constructed, not emergent ----

    /**
     * The faces of a skin, in **provenance order**: one strip per (interval × piece) in the correspondence's
     * own cyclic order, then the two caps — the low end first, which is what
     * [Section3.FACE_ADDRESS_CONVENTION] already says of every flat end.
     *
     * Nothing here stands over a footprint boundary piece, so the whole list is addressed past the footprint
     * ([Section3.faceAddressCount]) — and every one of those addresses was a refusal before this feature
     * existed, so no stored byte changes meaning (OP-18).
     */
    fun faces(f: Feature3.Skin): Pair<List<FacePatch>?, Msg?> {
        val (plan, why) = plan(f.sections, f.row, f.matches)
        if (plan == null) return null to why
        val out = ArrayList<FacePatch>()
        for (k in 0 until plan.intervals) {
            for ((s, strip) in plan.strips[k].withIndex()) {
                out.add(stripPatch(plan, k, s, strip))
            }
        }
        for (k in listOf(0, plan.sections.size - 1)) {
            val prep = plan.preps[k]
            val runDir =
                if (k == 0) {
                    centre(plan.ringW[1]) - centre(plan.ringW[0])
                } else {
                    centre(plan.ringW[k]) - centre(plan.ringW[k - 1])
                }
            val n = prep.plane.normal.normalized()
            val outward = if (k == 0) n.dot(runDir) < 0.0 else n.dot(runDir) > 0.0
            val plane = if (outward) prep.plane else prep.plane.flipped()
            // the cap's boundary in the cap's own frame — [Section3.capFace]'s one convention, reached rather
            // than restated, so a skin's end section is a face space in the very coordinates every other cap is
            out.add(Section3.capFace(listOf(prep.region), plane, mirror = !outward, name = FaceName.SectionFace(k)))
        }
        return out to null
    }

    /**
     * One strip as a face: **planar** where its own corners are coplanar — which every strip of a ruled skin
     * between two parallel polygons is, and which is what makes *sketch on this face* work on a frustum's
     * flank — and a named non-plane otherwise, with the millimetres in the message (the loft's own sentence).
     */
    private fun stripPatch(
        plan: SkinPlan,
        interval: Int,
        index: Int,
        strip: Strip,
    ): FacePatch {
        val name = FaceName.SkinBand(interval, index)
        val f = plan.families[strip.family]
        val ring = ArrayList<Vec3>()
        for (j in 0..f.count) ring.add(plan.railPoint(strip.family, j, interval))
        for (j in f.count downTo 0) ring.add(plan.railPoint(strip.family, j, interval + 1))
        val pts = dedupe(ring)
        if (pts.size < 3) return FacePatch(name, null, emptyList(), Msgs.refusalSkinThatFaceCollapsesLine())
        val plane = planeThrough(pts) ?: return FacePatch(name, null, emptyList(), Msgs.refusalSkinThatFaceIsDegenerate())
        val off = pts.maxOf { abs(plane.distanceTo(it)) }
        if (off > FLAT_TOL) {
            return FacePatch(
                name,
                null,
                emptyList(),
                Msgs.refusalSkinThatFaceIsRuledBand(round = (kotlin.math.round(off * 1000.0) / 1000.0).toString()),
            )
        }
        val local = dedupe2(pts.map { plane.toLocal(it) })
        if (local.size < 3) return FacePatch(name, null, emptyList(), Msgs.refusalSkinThatFaceEnclosesNoArea())
        return FacePatch(name, plane, ringPieces(oriented(local)), null)
    }

    /** The best-fit plane through a ring of at least three distinct points, with its normal out of the body. */
    private fun planeThrough(pts: List<Vec3>): Plane3? {
        val o = pts[0]
        val u0 = pts[1] - o
        if (u0.length() <= Geom3.WELD_TOL) return null
        var n = Vec3.ZERO
        for (i in 1 until pts.size - 1) {
            val c = (pts[i] - o).cross(pts[i + 1] - o)
            if (c.length() > n.length()) n = c
        }
        if (n.length() <= 1e-12) return null
        val normal = n.normalized()
        val u = (u0 - normal * u0.dot(normal)).let { if (it.length() <= Geom3.WELD_TOL) return null else it.normalized() }
        return Plane3(o, u, normal.cross(u))
    }

    private fun dedupe(pts: List<Vec3>): List<Vec3> {
        val out = ArrayList<Vec3>()
        for (p in pts) if (out.none { (it - p).length() <= Geom3.WELD_TOL }) out.add(p)
        return out
    }

    private fun dedupe2(pts: List<Vec2>): List<Vec2> {
        val out = ArrayList<Vec2>()
        for (p in pts) if (out.none { (it - p).length() <= Geom3.WELD_TOL }) out.add(p)
        return out
    }

    private fun oriented(pts: List<Vec2>): List<Vec2> {
        var area = 0.0
        for (i in pts.indices) area += pts[i].cross(pts[(i + 1) % pts.size])
        return if (area >= 0.0) pts else pts.reversed()
    }

    private fun ringPieces(pts: List<Vec2>): List<ProfileElement> =
        pts.indices.map { ProfileElement.Seg(Segment(pts[it], pts[(it + 1) % pts.size])) }
}
