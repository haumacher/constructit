package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.FaceName
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Mesh3
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// **The rounding matrix's own arithmetic and plumbing** (OP-31, item 1 — the matrix is the specification).
//
// Nothing here asserts anything: it states the closed-form figures the blend algebra is made of, reads the
// fixture's own topology off the body rather than off a table, and runs one cell. What every figure is, and
// where it comes from, is in [Figures]. The rule the whole file serves is the one OP-31 states: every cell
// is either built inside its own derived bracket, or refused by name — a third state is a defect.

/**
 * The closed forms the 3D blend's volume is made of — each one a function of the size, the kind and the
 * angle, never of a measurement, so a bracket built out of them is *derived* and not fitted.
 *
 * They are the same figures [BlendVertexTest], [BlendMixedVertexTest] and [BlendCornerTest] state; they live
 * here because the matrix needs all of them at once, over angles it reads off the body.
 */
object Figures {
    /**
     * **What one unit length of band takes out of a crease whose material fills [theta] radians** — the
     * section area between the two faces and the rounding's own section.
     *
     * A **round**: the ball of radius `r` tangent to both faces has its centre `r/sin(θ/2)` from the crease,
     * the two tangency points stand `r·cot(θ/2)` along the legs, and the corner region between the legs and
     * the arc is `r²·cot(θ/2) − r²(π − θ)/2` — which at `θ = π/2` is the familiar `r²(1 − π/4)`.
     *
     * A **bevel** of setback `c` along each leg is the triangle between them: `c²·sin(θ)/2`, and `c²/2` at a
     * right angle.
     */
    fun wedgeArea(
        size: Double,
        kind: BlendKind,
        theta: Double = PI / 2.0,
    ): Double =
        if (kind == BlendKind.CHAMFER) {
            size * size * sin(theta) / 2.0
        } else {
            size * size * (1.0 / tan(theta / 2.0) - (PI - theta) / 2.0)
        }

    /**
     * **The same wedge as the engine sees it**: a round's arc reaches the boolean as inscribed chords over its
     * sweep `π − θ`, stepped by `GeomMath.chordSteps` at the drawing's tolerance, and the chord polygon leaves
     * the circular segments between chord and arc to the wedge — `r²/2·(α − n·sin(α/n))` more than the exact
     * figure. A bevel has no arc and is exact. This is the upper end of a band's bracket at **any** dihedral,
     * where [chordSurplus] is its quarter-arc approximation.
     */
    fun wedgeAreaByChords(
        size: Double,
        kind: BlendKind,
        theta: Double = PI / 2.0,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Double {
        if (kind == BlendKind.CHAMFER) return wedgeArea(size, kind, theta)
        val alpha = PI - theta
        val n = GeomMath.chordSteps(size, alpha, tolMm)
        return wedgeArea(size, kind, theta) + size * size / 2.0 * (alpha - n * sin(alpha / n))
    }

    /**
     * `∫₀^size δ(h)² dh` over the section's own inset from the shared face — a round's rolling one
     * `r³(5/3 − π/2)`, a bevel's straight one `c³/3`. Stated at a right angle only, which is every crease of
     * the matrix's fixture that carries a corner.
     */
    fun moment(
        size: Double,
        kind: BlendKind,
    ): Double = if (kind == BlendKind.CHAMFER) size * size * size / 3.0 else size * size * size * (5.0 / 3.0 - PI / 2.0)

    /** How far the section's own centroid stands from the crease, along the shared face. */
    fun centroidReach(
        size: Double,
        kind: BlendKind,
    ): Double = (moment(size, kind) / 2.0) / wedgeArea(size, kind)

    /**
     * **A crossing**: what two congruent bands meeting at interior angle [rad] take off their own sum, since
     * near the corner they overlap and the removal splits on the plane equidistant from the two edges.
     */
    fun crossingTakes(
        size: Double,
        kind: BlendKind,
        rad: Double,
    ): Double = moment(size, kind) / tan(rad / 2.0)

    /**
     * **A pivot**: what a turn of [rad] about an axis standing [rho] from the section's origin *adds* to the
     * two bands' sum — Pappus over the section's own centroid. `rho = 0` is the sharp upright of session 80
     * and gives `rad·∫δ²/2`; `rho = r_U` is the ring torus about a rounded one.
     */
    fun pivotTakes(
        size: Double,
        kind: BlendKind,
        rad: Double,
        rho: Double,
    ): Double = wedgeArea(size, kind) * rad * (rho + centroidReach(size, kind))

    /**
     * **A convex three-edge vertex**: what the ball standing in the corner takes off the three bands' sum.
     * The cell `[0, r]³` keeps the ball's own octant, so the sum loses `(2 − 7π/12)r³` there; three bevels
     * meet in their own apex and lose `(3/4)c³`.
     */
    fun vertexTakes(
        size: Double,
        kind: BlendKind,
    ): Double = if (kind == BlendKind.CHAMFER) 0.75 * size * size * size else (2.0 - 7.0 * PI / 12.0) * size * size * size

    /**
     * **How high a section stands at reach [x] from its crease**, on a right-angled wedge: a bevel's straight
     * `c − x`, a round's `r − √(r² − (x − r)²)`. It is the section read as a function rather than as an area,
     * which is what a corner that eats part of it needs.
     */
    fun sectionHeight(
        size: Double,
        kind: BlendKind,
        x: Double,
    ): Double =
        if (kind == BlendKind.CHAMFER) {
            size - x
        } else {
            size - sqrt((size * size - (x - size) * (x - size)).coerceAtLeast(0.0))
        }

    /** Simpson over `[a, b]` — deterministic and fixed-step, so a figure stated through it is the same bit twice. */
    private fun integral(
        a: Double,
        b: Double,
        n: Int = 2000,
        f: (Double) -> Double,
    ): Double {
        val h = (b - a) / n
        var s = f(a) + f(b)
        for (i in 1 until n) s += f(a + h * i) * (if (i % 2 == 0) 2.0 else 4.0)
        return s * h / 3.0
    }

    /**
     * **The one-ended pivot** — what the corner where a fill runs out into a band's end *adds* (OP-31 item 2).
     *
     * The fill's section turns about the band on the circle of radius `r + r_U` and the **third** face at the
     * vertex caps the walk, so a ring at radius `ρ` from that axis is counted only over the turn it stands
     * below the cap for, which is `arcsin(r_U/ρ)` and nothing else:
     *
     * ```
     * V = ∫_{r_U}^{r_U+r} ρ · arcsin(r_U/ρ) · h(ρ − r_U) dρ
     * ```
     *
     * Pappus' `w·φ·ρ̄` — [pivotTakes] — is the same integral with no cap over it; here the cap eats the far
     * rings and the turn is a function of the radius, which is why this is its own figure and not that one.
     *
     * About a **bevelled** band the walk is a quarter-turn about the first rail, a slide the bevel's own
     * `c_U√2`, and a quarter-turn the cap takes away entirely, so it adds
     * `(π/4)·∫₀^r x·h(x) dx + ∫₀^{c_U√2} A(K) dK` with `A(K)` the section's area out to reach `K`.
     *
     * Stated at a right-angled vertex, which is every vertex of the matrix's fixture.
     */
    fun runOutAdds(
        bandSize: Double,
        bandKind: BlendKind,
        size: Double,
        kind: BlendKind,
    ): Double =
        if (bandKind == BlendKind.CHAMFER) {
            val moment = integral(0.0, size) { x -> x * sectionHeight(size, kind, x) }
            val area = { k: Double -> if (k <= 0.0) 0.0 else integral(0.0, kotlin.math.min(k, size)) { x -> sectionHeight(size, kind, x) } }
            (PI / 4.0) * moment + integral(0.0, bandSize * sqrt(2.0)) { k -> area(k) }
        } else {
            integral(bandSize, bandSize + size) { rho ->
                rho * kotlin.math.asin(kotlin.math.min(1.0, bandSize / rho)) * sectionHeight(size, kind, rho - bandSize)
            }
        }

    /**
     * **The chord allowance a one-ended pivot needs**, derived on both sides and from the sag rule alone.
     *
     * *Above*: the section reaches the engine as its own chord polygon, which is
     * `wedgeAreaByChords − wedgeArea` larger and scales the whole corner with it. *Below*: the walk is stepped
     * by the same rule, and a linear loft over a turn of `Δ` under-sweeps by `Δ²/6` of it, while the sag rule
     * gives `Δ² ≤ 8·tol/R` at radius `R` — so at most `4·tol/(3·R)` of the corner, taken at the section's own
     * size, which is the smallest radius any part of it turns at.
     */
    fun runOutSlack(
        bandSize: Double,
        bandKind: BlendKind,
        size: Double,
        kind: BlendKind,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Double {
        val take = runOutAdds(bandSize, bandKind, size, kind)
        val w = wedgeArea(size, kind)
        val up = take * (wedgeAreaByChords(size, kind, PI / 2.0, tolMm) - w) / w
        val down = take * 4.0 * tolMm / (3.0 * size)
        return up + down
    }

    /**
     * **The chord surplus** an inscribed arc leaves over [length] mm of band: the arc reaches the engine as
     * chords, a chord stands further from the crease than the arc it replaces, so a meshed tool always takes
     * a little *more* than the exact one. The band's own surface is `π·r/2` per unit length at a right angle
     * and the mean gap between chord and arc is two thirds of the sag rule's own tolerance, so the surplus is
     * at most `π·r·tol·length/3` ([EdgeBlendTest]'s model, and [BlendVertexTest]'s).
     */
    fun chordSurplus(
        r: Double,
        length: Double,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Double = PI * r * tolMm * length / 3.0

    /**
     * The same allowance for **one corner patch**, whose surface is at most a whole ball's `4πr²`: the
     * patch's own chords can stand no further off it than the sag rule allows, so `4πr²·2/3·tol` bounds what
     * a corner can add over its exact figure whichever corner it is.
     */
    fun cornerSurplus(
        r: Double,
        tolMm: Double = GeomMath.TESS_TOL_MM,
    ): Double = 4.0 * PI * r * r * tolMm * 2.0 / 3.0
}

// ---- the fixture ----

/** One rounding of a cell: which edge of the body it addresses, with what section. */
data class Rounding(
    val edge: Int,
    val kind: BlendKind,
    val size: Double,
) {
    override fun toString(): String = "e$edge${if (kind == BlendKind.FILLET) "F" else "C"}$size"
}

/** How the roundings of a cell reach the body — OP-30's two shapes. */
enum class Route {
    /** All of them in **one** `Blend3.blended` pass, which is what a dressing of one size is. */
    ONE_PASS,

    /** Each on the body the one before it made — a rounding that addresses a rail, and OP-30's chain. */
    STACKED,
}

/** What one cell of the matrix turned out to be. */
sealed interface CellState {
    /** The body is valid, watertight, and its volume lies inside the cell's own bracket. */
    data class Built(val volume: Double) : CellState

    /** The node is invalid, and the reason names an edge or a face. */
    data class Refused(val reason: String) : CellState

    /** Built, and silently wrong — the state OP-31 exists to make impossible. */
    data class Wrong(val volume: Double, val why: String) : CellState
}

/** A two-sided closed-form bracket on a cell's volume, or `null` where the algebra does not state one. */
data class Bracket(val lo: Double, val hi: Double) {
    operator fun contains(v: Double): Boolean = v >= lo - 1e-6 && v <= hi + 1e-6

    override fun toString(): String = "[${fmt(lo)}, ${fmt(hi)}]"

    private fun fmt(x: Double): String = ((x * 1000.0).toLong() / 1000.0).toString()
}

/**
 * **What one body says about itself** — the edge list, each edge's run and its wedge angle, the vertices, and
 * whether the material fills less than a half turn there.
 *
 * Everything the matrix needs about a fixture, read off `Section3` rather than tabulated, so it works the
 * same for the L-block and for the dressed body a rounding of it makes (which is what a rounding on a *rail*
 * addresses).
 */
class Body(val solid: Solid3) {
    val mesh: Mesh3 = solid.mesh

    val volume: Double = Geom3.volume(mesh)

    val edges = Section3.edges(solid.feature).first ?: error("this body names no edges")

    val faces = Section3.faces(solid.feature).first ?: error("this body names no faces")

    val count get() = edges.size

    private val paths =
        edges.indices.map { i ->
            val p = Blend3.edgePath(edges[i]).first ?: return@map null
            val el = p.elements.singleOrNull() ?: return@map null
            el.start to el.end
        }

    /** Whether edge [i] is a straight run the matrix can state a figure for. */
    fun straight(i: Int): Boolean = paths[i] != null && edges[i].reason == null

    fun length(i: Int): Double = paths[i]!!.let { (it.second - it.first).length() }

    fun ends(i: Int): List<Vec3> = paths[i]!!.let { listOf(it.first, it.second) }

    /** The direction edge [i] runs in, starting from its end at [at]. */
    fun away(
        i: Int,
        at: Vec3,
    ): Vec3 {
        val (s, e) = paths[i]!!
        return if ((s - at).length() <= 1e-6) (e - s).normalized() else (s - e).normalized()
    }

    private val convexity = HashMap<Int, Boolean?>()

    /** Whether the material fills less than a half turn at edge [i] — scored the way a live gesture scores it. */
    fun convex(i: Int): Boolean? =
        convexity.getOrPut(i) {
            Blend3.choicesFor(solid, listOf(i), BlendSection(BlendKind.FILLET, 0.5)).first?.get(0)?.convex
        }

    /**
     * **The angle the rounding's own wedge stands in at edge [i]** — the material's dihedral where the edge is
     * convex, and the *air*'s where it is concave, which are the same expression: the two faces' outward
     * normals turn by `π − θ` between them either way, so `θ = π − acos(n₁·n₂)` is the wedge's angle whichever
     * side the material is on. `π/2` at a box edge, `3π/4` at the rail of a bevel.
     */
    fun wedgeAngle(i: Int): Double? {
        val ns =
            listOf(edges[i].between.a, edges[i].between.b).map { n ->
                faces.firstOrNull { it.name == n }?.plane?.normal?.normalized() ?: return null
            }
        val d = ns[0].dot(ns[1]).coerceIn(-1.0, 1.0)
        return PI - acos(d)
    }

    /** The body's vertices: every point more than one edge stands at, with those edge indices. */
    val vertices: List<Pair<Vec3, List<Int>>> =
        run {
            val out = ArrayList<Pair<Vec3, MutableList<Int>>>()
            for (i in edges.indices) {
                if (!straight(i)) continue
                for (p in ends(i)) {
                    val at = out.firstOrNull { (q, _) -> (q - p).length() <= 1e-6 }
                    if (at == null) out.add(p to arrayListOf(i)) else at.second.add(i)
                }
            }
            out.filter { it.second.size >= 2 }.map { it.first to it.second.toList() }
        }

    /** Every unordered pair of edges that share a vertex, with that vertex. */
    val pairs: List<Triple<Int, Int, Vec3>> =
        vertices.flatMap { (at, es) ->
            es.indices.flatMap { a -> (a + 1 until es.size).map { b -> Triple(es[a], es[b], at) } }
        }

    /** The outward normal of the one face [a] and [b] share, or null where they share none with a plane. */
    fun sharedFaceNormal(
        a: Int,
        b: Int,
    ): Vec3? {
        val ea = edges[a].between
        val eb = edges[b].between
        for (n in listOf(ea.a, ea.b).filter { it == eb.a || it == eb.b }) {
            val p = faces.firstOrNull { it.name == n }?.plane ?: continue
            return p.normal.normalized()
        }
        return null
    }

    /**
     * The **interior angle** the face [a] and [b] share turns at their common vertex [at] — less than π where
     * the face's corner is convex there, more where it turns an inside one.
     *
     * Read off the body rather than off the plan: the two edges' own directions give the angle up to the
     * reflex ambiguity, and the material settles it — step a hair along their bisector and a hair *into* the
     * body away from the shared face, and ask the solid whether that point is in it.
     */
    fun sharedAngle(
        a: Int,
        b: Int,
        at: Vec3,
    ): Double? {
        val n = sharedFaceNormal(a, b) ?: return null
        val da = away(a, at)
        val db = away(b, at)
        val alpha = angleBetween3(da, db)
        val bis = da + db
        if (bis.length() <= 1e-9) return null
        val eps = 1e-3
        return if (Geom3.encloses(mesh, at + bis.normalized() * eps - n * eps)) alpha else 2.0 * PI - alpha
    }
}

/**
 * **The reporter's own L-block** (GitHub #36, script 1 up to `param "r"`): an L-shaped ortho path of six
 * corners, extruded 20 mm.
 *
 * Eighteen edges — six uprights, six on the bottom cap, six on the top — one upright **concave** (the plan's
 * inside corner) and its two adjacent cap edges meeting it at a mixed vertex. Everything the matrix needs
 * about it is read off the body itself: which edges meet where, how long each is, whether it is convex, and
 * what interior angle the face two edges share turns at their common vertex. Nothing is tabulated, so the
 * fixture cannot drift away from what `Section3` says it is.
 */
class LBlock {
    /** The reporter's six corners, in his own order. */
    val plan =
        listOf(
            Vec2(-26.875, -32.375),
            Vec2(-26.875, 15.375),
            Vec2(61.875, 15.375),
            Vec2(61.875, 0.375),
            Vec2(-5.521648428788623, 0.375),
            Vec2(-5.521648428788623, -32.375),
        )

    val height = 20.0

    val cx = Construction()

    val base: SolidRef = prism(cx, plan, height)

    /** The undressed block, as it says what it is. */
    val block = Body(Evaluator().solid(base))

    val baseVolume get() = block.volume

    private fun prism(
        cx: Construction,
        xy: List<Vec2>,
        h: Double,
    ): SolidRef {
        val pts = xy.mapIndexed { i, p -> cx.freePoint("p$i", p.x.mm, p.y.mm) }
        val segs = xy.indices.map { cx.segment(pts[it], pts[(it + 1) % xy.size]) }
        return cx.extrude(cx.sketchOn(cx.planeXY(), cx.region(cx.loop(*segs.toTypedArray()))), cx.const(h.mm))
    }

    // ---- running one cell ----

    /**
     * Round [entries] on the block by [route] and hand back the body it made, or the reason it was refused.
     *
     * The choices are **scored the way a live gesture scores them** ([Blend3.choicesFor], which is the very
     * call the tool makes) against whichever body the rounding addresses, so no sign is ever guessed here.
     *
     * [ONE_PASS][Route.ONE_PASS] is one dressing — the shape OP-30 gives a run of gestures whose sizes are
     * the drawing's own parameters — and [STACKED][Route.STACKED] is a rounding standing on the body the one
     * before it made, which is what addressing a **rail** is. [together] puts every entry into a single
     * `BlendRun`, which is what one whole-face gesture is; it needs one kind and one size.
     */
    fun run(
        entries: List<Rounding>,
        route: Route,
        together: Boolean = false,
    ): Pair<List<SolidRef>?, String?> {
        val sizes = HashMap<Double, constructit.dsl.ScalarRef>()

        fun sizeOf(mm: Double) = sizes.getOrPut(mm) { cx.const(mm.mm) }
        val stages = ArrayList<SolidRef>()
        var cur = base
        val runs = ArrayList<Construction.BlendRun>()
        if (together) {
            val (choices, why) =
                Blend3.choicesFor(Evaluator().solid(base), entries.map { it.edge }, BlendSection(entries[0].kind, entries[0].size))
            if (choices == null) return null to (why?.render() ?: "no choice")
            runs.add(Construction.BlendRun(entries[0].kind, sizeOf(entries[0].size), null, entries.map { it.edge }, choices))
        } else {
            for (e in entries) {
                val on = Evaluator().solid(cur)
                val (choices, why) = Blend3.choicesFor(on, listOf(e.edge), BlendSection(e.kind, e.size))
                if (choices == null) return null to (why?.render() ?: "no choice")
                val run = Construction.BlendRun(e.kind, sizeOf(e.size), null, listOf(e.edge), choices)
                if (route == Route.STACKED) {
                    cur = cx.blendAll(cur, cx.planeXY(), listOf(run))
                    val r = Evaluator().eval(cur.node)
                    if (r is EvalResult.Invalid) return null to r.reason
                    stages.add(cur)
                } else {
                    runs.add(run)
                }
            }
        }
        if (route == Route.ONE_PASS || together) {
            cur = cx.blendAll(base, cx.planeXY(), runs)
            val r = Evaluator().eval(cur.node)
            if (r is EvalResult.Invalid) return null to r.reason
            stages.add(cur)
        }
        return stages to null
    }
}

/** The mesh-side of a built cell: watertight, and its volume. */
fun measure(
    ref: SolidRef,
    what: String,
): Double {
    val mesh = Evaluator().solid(ref).mesh
    assertManifold(mesh, what)
    return Geom3.volume(mesh)
}

/** A refusal that names something: non-empty, and mentioning an edge, a face or a body. */
fun namesSomething(reason: String): Boolean {
    if (reason.isBlank()) return false
    val nouns =
        listOf(
            "edge", "face", "cap", "upright", "rail", "corner", "band", "solid", "body",
            "rounding", "fillet", "chamfer", "section", "crease", "profile", "vertex",
        )
    return nouns.any { it in reason.lowercase() }
}

/**
 * **The bracket the algebra states for one cell**, or null where it states none — which is a cell OP-31's
 * residue has to name.
 *
 * The figure is built the way the design entry writes it: every rounding contributes its band over its own
 * run, and every vertex where two or three of them meet contributes its corner — a **crossing** where the
 * face they share turns a convex corner, a **pivot** where it turns an inside one, the **ball** where three
 * convex bands meet. The upper margin is the chord surplus, since an arc reaches the engine as inscribed
 * chords and a chord always takes a hair more; a cell with no arc in it anywhere gets the float32 noise of
 * the boolean and nothing else.
 *
 * A **mixed-sign** pair — a fill running out into a band's end — is the one-ended pivot [Figures.runOutAdds]
 * states, added rather than taken and with the fill's own run set back by the band's size (OP-31 item (2),
 * session 83).
 *
 * What it declines to state, and each is a class rather than a case:
 * - a corner at an edge whose wedge does not stand at a right angle, since the crossing, pivot, ball and
 *   run-out figures above are written at one (a **band** at any angle is stated, so a rounding of a rail on
 *   its own is bracketed; two of them meeting is not);
 * - an **incongruent** inside corner, which since session 83 is refused by name and so never reaches a
 *   bracket at all.
 */
fun predict(
    b: Body,
    entries: List<Rounding>,
    corners: Boolean = true,
): Bracket? {
    val byEdge = entries.associateBy { it.edge }
    if (byEdge.size != entries.size) return null
    if (entries.any { it.edge < 0 || it.edge >= b.count }) return null
    if (entries.any { !b.straight(it.edge) || b.convex(it.edge) == null || b.wedgeAngle(it.edge) == null }) return null
    var cornerLo = 0.0
    var cornerHi = 0.0
    val setback = HashMap<Int, Double>()
    var extra = 0.0

    fun cut(
        e: Int,
        by: Double,
    ) {
        setback[e] = (setback[e] ?: 0.0) + by
    }
    for ((at, es) in b.vertices) {
        if (!corners) break
        val here = es.filter { it in byEdge }.map { byEdge.getValue(it) }
        if (here.size < 2) continue
        // every corner figure below is written at a right-angled wedge, which is every crease of the
        // undressed block; a corner between two roundings of a *rail* is not one this states
        if (here.any { kotlin.math.abs(b.wedgeAngle(it.edge)!! - PI / 2.0) > 1e-9 }) return null
        val concave = here.filter { b.convex(it.edge) == false }
        val convex = here.filter { b.convex(it.edge) == true }
        val congruent = here.all { it.kind == here[0].kind && it.size == here[0].size }
        val kind = here[0].kind
        val size = here[0].size
        val slack = if (here.any { it.kind == BlendKind.FILLET }) Figures.cornerSurplus(here.maxOf { it.size }) else 0.0
        when {
            // **two convex bands.** The face they share settles which corner it is.
            here.size == 2 && concave.isEmpty() -> {
                val theta = b.sharedAngle(here[0].edge, here[1].edge, at) ?: return null
                if (theta < PI) {
                    if (congruent) {
                        val take = Figures.crossingTakes(size, kind, theta)
                        cornerLo -= take + slack
                        cornerHi -= take - slack
                    } else {
                        // **incongruent, so no corner is built** — the two tools simply overlap and the
                        // boolean trims. What the overlap is, is not closed form; what bounds it is
                        // *containment*: a tool contained in both takes no more than the true overlap, and a
                        // tool containing both takes no less. A round of the smaller size sits inside every
                        // tool here, and a bevel of the larger size contains every one of them (the bevel's
                        // plane is the chord of the round's own arc), so the two congruent crossings of those
                        // two bracket it honestly, if loosely.
                        cornerLo -= Figures.crossingTakes(here.maxOf { it.size }, BlendKind.CHAMFER, theta)
                        cornerHi -= Figures.crossingTakes(here.minOf { it.size }, BlendKind.FILLET, theta)
                    }
                } else {
                    // an inside corner of the shared face, the third edge left sharp: the ball pivots about
                    // it through the corner's exterior angle. An **incongruent** pair makes no such corner
                    // and is refused by name (session 83), so it never reaches here.
                    if (!congruent) return null
                    val take = Figures.pivotTakes(size, kind, theta - PI, 0.0)
                    cornerLo += take - slack
                    cornerHi += take + slack
                }
            }
            // **three convex bands** at a box corner: the ball itself, which replaces all three crossings.
            here.size == 3 && concave.isEmpty() -> {
                if (!congruent) return null
                val take = Figures.vertexTakes(size, kind)
                cornerLo -= take + slack
                cornerHi -= take - slack
            }
            // **the mixed vertex, all three rounded**: the two convex bands set back by the fill's own reach,
            // and the pair's section carried round whatever stands at the upright.
            here.size == 3 && concave.size == 1 && convex.size == 2 -> {
                if (!congruent) return null
                val theta = b.sharedAngle(convex[0].edge, convex[1].edge, at) ?: return null
                if (theta <= PI) return null
                val phi = theta - PI
                cut(convex[0].edge, size)
                cut(convex[1].edge, size)
                val take =
                    if (kind == BlendKind.CHAMFER) {
                        // a bevelled upright is a turn, a slide the width of the bevel, and a turn
                        extra += Figures.wedgeArea(size, kind) * 2.0 * size * sin(phi / 2.0)
                        2.0 * Figures.pivotTakes(size, kind, phi / 2.0, 0.0)
                    } else {
                        // a rounded upright: one turn, on the circle of radius r + r_U about its own axis
                        Figures.pivotTakes(size, kind, phi, size)
                    }
                cornerLo += take - slack
                cornerHi += take + slack
            }
            // **a mixed-sign pair** — a fill running out into a band's end (OP-31 item (2)). The fill's
            // section turns about the band on the circle of radius `r + r_U` and the third face at the
            // vertex caps the walk, so the corner **adds** [Figures.runOutAdds] and the fill's own run is
            // set back by the band's size. Nothing here asks the two to be congruent: there is no ring for
            // them to share, and the circle `r + r_U` exists for every pair of sizes and kinds.
            here.size == 2 && concave.size == 1 && convex.size == 1 -> {
                // the closed form is written where the shared face turns a **convex** corner at the vertex,
                // which is where the band's own curve runs out of it — an inside one there is a different
                // walk and this states no figure for it
                val theta = b.sharedAngle(here[0].edge, here[1].edge, at) ?: return null
                if (theta >= PI) return null
                val fill = concave[0]
                val band = convex[0]
                val take = Figures.runOutAdds(band.size, band.kind, fill.size, fill.kind)
                val give = Figures.runOutSlack(band.size, band.kind, fill.size, fill.kind)
                cut(fill.edge, band.size)
                cornerLo -= take + give
                cornerHi -= take - give
            }
            else -> return null
        }
    }
    var lo = extra
    var hi = extra
    for (e in entries) {
        val run = b.length(e.edge) - (setback[e.edge] ?: 0.0)
        if (run <= 0.0) return null
        val w = Figures.wedgeArea(e.size, e.kind, b.wedgeAngle(e.edge)!!)
        if (w <= 0.0) return null
        val surplus = if (e.kind == BlendKind.CHAMFER) 0.0 else Figures.chordSurplus(e.size, b.length(e.edge))
        if (b.convex(e.edge) == true) {
            lo += w * run
            hi += w * run + surplus
        } else {
            lo += -(w * run + surplus)
            hi += -w * run
        }
    }
    lo += cornerLo
    hi += cornerHi
    // the general boolean's own float32 noise, which even an all-planar figure gets
    val noise = b.volume * 1e-5
    return Bracket(b.volume - (hi + noise), b.volume - (lo - noise))
}

/**
 * **The naive figure**: every band over the whole of its own edge, and no corner anywhere — what a cell in
 * OP-31's residue actually builds, and what the matrix pins it to so that building the corner *fails* the
 * test and retires the residue entry (the seeded-defect discipline of `TranslationReviewTest`).
 */
fun naive(
    b: Body,
    entries: List<Rounding>,
): Bracket? = predict(b, entries, corners = false)

/** Whether the dressed body states a rounded corner of its own — a face the roundings' meeting made. */
fun statesACorner(solid: Solid3): Boolean =
    (Section3.faces(solid.feature).first ?: emptyList()).any { it.name is FaceName.BlendCorner }
