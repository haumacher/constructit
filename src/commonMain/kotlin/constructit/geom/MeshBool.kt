package constructit.geom

import kotlin.math.abs
import kotlin.math.floor

/**
 * **The general boolean engine seam** (OP-9) — one expect/actual declaration, so which engine computes a
 * boolean is *a deployment toggle, not a rewrite*.
 *
 * The division of labour with OP-22 is deliberate and stays: two prisms **along one axis** are combined
 * exactly, by the slab algebra, and never come here. Everything else — a cross-axis pair, a revolve
 * operand, a mesh-only result feeding another boolean — is a *mesh* boolean, and this is where it happens.
 * That partition is also the OP-9 type boundary: what leaves the exact path leaves the analytic layer with
 * it (see [Feature3.MeshBoolean]) and is a sink from then on.
 *
 * The contract every actual must keep:
 * - **Deterministic.** The same two meshes must give the same triangle list, bit for bit, on every run —
 *   the model is a pure function of its parameters (OP-4), and a mesh that reshuffled itself between two
 *   recomputes would break undo/reload equality. Manifold's own output ordering is not part of its API, so
 *   the result is put into a canonical form here rather than trusted: see [MeshCanon.canonical].
 * - **Result-or-reason, never a throw.** `null to reason` becomes an invalid node that hides its
 *   dependents and heals (OP-3), exactly like every other refusal in [Geom3].
 * - **Honest about availability.** [available] is false when the engine cannot run *right now* — no native
 *   library for this platform, or (in the browser) a WASM module still loading. The reason then says so,
 *   which is what makes the loading case an ordinary healing invalidity rather than a special state.
 */
expect object MeshBool {
    /** Whether a general boolean can be computed **right now**. */
    val available: Boolean

    /** What the engine is: its version when ready, why not when it is not. Goes into node reasons. */
    val status: String

    /**
     * [kind] applied to two closed meshes, or null with a reason. The result is canonical
     * ([MeshCanon.canonical]) and therefore a deterministic function of the two inputs.
     */
    fun boolean(
        kind: BoolOp,
        a: Mesh3,
        b: Mesh3,
    ): Pair<Mesh3?, String?>
}

/**
 * The reason a general boolean cannot be had, in one place because both actuals and the DSL say it.
 *
 * It names the engine *and* what is missing, because "not prismatic" is no longer the whole story: with
 * Manifold present the same operands succeed, so the refusal has to be about availability (OP-3's rule
 * that a reason is a statement about *this* state, not a permanent verdict).
 */
fun meshBoolUnavailable(status: String): String =
    "these solids have no common axis, so this boolean needs the general engine — Manifold (OP-9) — which is not available here: $status"

/**
 * The **canonical form** of a mesh: welded vertices, sorted lexicographically, triangles rotated to their
 * smallest corner and then sorted.
 *
 * Why this exists rather than trusting the engine: Manifold guarantees a *manifold* result, not a
 * particular vertex numbering, and it is free to (and does) sort internally, run in parallel, and emit
 * duplicated vertices where property runs meet. None of that may reach the model, which has to be a pure
 * function of its parameters. Canonicalising is cheap (two sorts) and turns "probably stable" into
 * "provably stable" — the same property the 2D kernel gets from [RegionBool.canonical].
 *
 * Three passes, each order-independent so the output does not depend on the input's order:
 * 1. **Weld** vertices that are the same point **at the engine's own resolution** ([weldTol]) — see below;
 *    `-0.0` is normalised to `0.0` first, since the two are equal as numbers but not as keys.
 * 2. **Sort** the surviving positions lexicographically and renumber.
 * 3. **Rotate** every triangle so its smallest index comes first — winding-preserving, which is the whole
 *    reason it is a rotation and not a sort — drop the degenerate ones, then sort the triangles.
 *
 * **Why the weld is a tolerance and not bit-identity** (found by the user's plate-and-handle drawing,
 * session 63). It used to be bit-identity, on the argument that *"the only duplicates a mesh engine produces
 * are bit-identical copies of one position, and welding on a tolerance would risk merging two genuinely
 * distinct points"*. The first half of that is false where a cut passes exactly through an **edge** of the
 * body — the user's chain runs through a point their handle stands on, which is a perfectly ordinary thing
 * to draw — and there the engine hands back one point as two, a fraction of a float32 ULP apart. The
 * triangles between them are collinear and have **zero area**: not a crack (every edge still has its two
 * faces, so [fault] passes it), but a sliver that no exporter should ever be handed, and the thing this
 * project asserts of every solid it builds.
 *
 * The second half is answered by *which* tolerance. This is not a modelling tolerance: it is the general
 * engine's own **representation** resolution, whose mesh positions are float32 (~1.2e-7 *relative*, OP-9,
 * the number [Chains.margin] already argues against). Two positions closer than a few ULPs of that are not
 * two points that this engine could tell apart — they are one point it failed to spell once. And the exact
 * path has always welded on a lattice ([Geom3.MeshBuilder], `Geom3.WELD_TOL`), so this makes *"one vertex
 * per position"* mean the same thing on both paths, which is what that sentence claimed all along.
 */
object MeshCanon {
    /**
     * One float32 ULP, the resolution of the general engine's vertex positions (OP-9) — the unit the weld
     * tolerance is counted in.
     */
    const val F32_ULP = 1.1920929e-7

    /**
     * How many ULPs apart two positions may be and still be one point — see the object note.
     *
     * **One**, deliberately, and the margin is measured rather than assumed: the splits this exists to close
     * are a *fraction* of a ULP at the mesh's own scale (the case that found it was 2.4e-7 mm on a 84 mm
     * part, i.e. 0.02 ULP), so one ULP is already two orders of headroom. Every ULP above that is a merge
     * radius spent on nothing, and it is not free: two vertices that are *genuinely* distinct — the two sides
     * of a knife edge, a near-tangent contact — merged here become a non-manifold vertex, which [fault]
     * catches and turns into a refusal. A refusal is honest but it is still a boolean the user cannot have,
     * so this number is kept at the smallest value that does the job.
     */
    const val WELD_ULPS = 1.0

    /**
     * How far apart two of [positions] may be and still be the same point: [WELD_ULPS] of float32 at the
     * mesh's own scale, floored at the exact path's absolute lattice (`Geom3.WELD_TOL`).
     *
     * **The scale is the mesh's, not each coordinate's**, and both terms are needed: a part far from the
     * origin has its resolution set by the size of its *coordinates*, while a part at the origin has it set
     * by its own *extent* (the engine works in a normalized box). Taking the larger of the two answers both
     * without a case. At drawing sizes this is tens of nanometres — six orders below any feature and five
     * above the noise it exists to absorb.
     */
    fun weldTol(positions: List<Vec3>): Double {
        // Only ever asked of the general engine's own output ([canonical] has no other caller): the number
        // below is that engine's representation resolution, not a modelling tolerance, and it would be the
        // wrong number to apply to a mesh this project built in double precision.
        var scale = 0.0
        for (v in positions) {
            scale = maxOf(scale, abs(v.x), abs(v.y), abs(v.z))
        }
        return maxOf(Geom3.WELD_TOL, WELD_ULPS * F32_ULP * scale)
    }

    fun canonical(mesh: Mesh3): Mesh3 {
        val normalized = mesh.vertices.map { Vec3(zero(it.x), zero(it.y), zero(it.z)) }
        val distinct = normalized.distinct().sortedWith(compareBy({ it.x }, { it.y }, { it.z }))
        // The lattice the exact path already welds on, walked in the canonical order above so that which
        // position survives a merge is a function of the *set* of positions and never of the input's order.
        val tol = weldTol(distinct)
        val buckets = HashMap<Long, MutableList<Vec3>>()
        val order = ArrayList<Vec3>(distinct.size)
        val repOf = HashMap<Vec3, Vec3>(distinct.size * 2)
        for (v in distinct) {
            val rep = nearby(buckets, v, tol)
            if (rep != null) {
                repOf[v] = rep
                continue
            }
            order.add(v)
            repOf[v] = v
            buckets.getOrPut(cellOf(v, tol)) { ArrayList() }.add(v)
        }
        val indexOf = HashMap<Vec3, Int>(order.size * 2)
        for ((i, v) in order.withIndex()) indexOf[v] = i
        val remap = IntArray(normalized.size) { indexOf.getValue(repOf.getValue(normalized[it])) }

        val tris = ArrayList<Tri>(mesh.triangles.size)
        for (t in mesh.triangles) {
            val a = remap[t.a]
            val b = remap[t.b]
            val c = remap[t.c]
            if (a == b || b == c || a == c) continue
            tris.add(
                if (a <= b && a <= c) {
                    Tri(a, b, c)
                } else if (b <= c) {
                    Tri(b, c, a)
                } else {
                    Tri(c, a, b)
                },
            )
        }
        tris.sortWith(compareBy({ it.a }, { it.b }, { it.c }))
        return Mesh3(order, tris)
    }

    /**
     * [canonical], then **checked**: the engine's result as a value, or null with a reason.
     *
     * The check is the one the tests apply — every directed edge used exactly once, with exactly one
     * opposite use — and it is here rather than only in the tests because a mesh boolean that hands back a
     * shell with a crack in it is the failure mode this whole design refuses to have (OP-22's rule, applied
     * to the general path). Manifold's own guarantee is about *its* representation, not about this one, and
     * the gap between them is real: a **tangent** contact (a bore whose wall just touches a face) is a
     * solid that touches itself along a line, which Manifold represents with coincident-but-distinct
     * vertices and [canonical] necessarily welds into one. Rather than emit that, this refuses it — an
     * ordinary invalid node with a reason, healing the moment a radius or a height moves (OP-3).
     *
     * Positional identity is deliberate and matches the rest of the engine: `Geom3`'s own mesh builder
     * welds too, so "one vertex per position" is what a [Mesh3] means here, and a zero-thickness contact
     * has no representation in it either way.
     */
    fun finish(mesh: Mesh3): Pair<Mesh3?, String?> {
        val out = canonical(mesh)
        if (out.triangles.isEmpty()) return null to "the general boolean produced no triangles"
        val fault = fault(out)
        return if (fault == null) out to null else null to fault
    }

    /**
     * How nearly two triangles that share an edge must face **opposite** ways to be a [flap], as a dot
     * product of their unit normals.
     *
     * `-1` is exactly back-to-back, and the slack is only what *reading* a normal off a float32 position
     * costs: the pair GitHub #33 was reported for came off the general engine at −0.999999999999938, six
     * decades inside this. What the number must **not** do is take in a **knife edge** — two faces meeting
     * at a real dihedral, however thin. That is a shape a body may legitimately have (a chain fused to
     * another along a near-tangent wall leaves one at 0.04° in this suite's own drawings), and it has
     * thickness; a flap has none. So the band is one part in a billion, a dihedral of 0.003°, which
     * separates the two by more than a decade at the closest either comes.
     */
    const val FLAP_COS = -1.0 + 1e-9

    /**
     * Why [mesh] is not a closed, consistently wound shell **and free of flaps**, or null when it is —
     * the two halves stated separately below so a caller that built its own mesh can ask them apart.
     */
    fun fault(mesh: Mesh3): String? = notClosed(mesh)

    /**
     * Why the shell **folds back on itself** — two triangles sharing an edge that are coplanar with
     * opposite normals, so the surface has zero thickness there — or null when it does not.
     *
     * *Why this is a fault of its own* (GitHub #33). Edge-use counts are blind to it: a flap uses every
     * directed edge exactly once with exactly one opposite use, so [notClosed] passes it, the volume
     * integral passes it (a back-to-back pair contributes nothing), and a slicer meets a surface that
     * encloses nothing. The reporter's picture — an upright rounded at the top and still coming to a
     * sharp point at the base — was exactly this: the old tip vertex left in the mesh with the wall's
     * triangulation running out to it and folding over.
     *
     * *Where it comes from.* A tool face lying **exactly in** a body face. The difference of two solids
     * that share a face is not a solid — the two coincident sheets have to cancel, and whether a float32
     * engine cancels them is a coin. That is the gap [Blend3]'s own step-off exists to open, and this is
     * the check that says it was opened everywhere: watertight or refused (OP-9), with *watertight* now
     * meaning what it always claimed to.
     */
    fun flap(mesh: Mesh3): String? {
        val owner = HashMap<Long, Int>(mesh.triangles.size * 4)
        for ((i, t) in mesh.triangles.withIndex()) {
            for (e in longArrayOf(edge(t.a, t.b), edge(t.b, t.c), edge(t.c, t.a))) owner[e] = i
        }
        // the triangles in their own order, so which flap a refusal names is a function of the mesh
        for ((i, t) in mesh.triangles.withIndex()) {
            val n = normalOf(mesh, t) ?: continue
            for ((from, to) in listOf(t.a to t.b, t.b to t.c, t.c to t.a)) {
                val j = owner[edge(to, from)] ?: continue
                if (j <= i) continue
                val m = normalOf(mesh, mesh.triangles[j]) ?: continue
                if (n.dot(m) > FLAP_COS) continue
                return "a zero-thickness flap at the edge between ${mesh.vertices[from]} and " +
                    "${mesh.vertices[to]}: the two triangles that share it are coplanar and wound against " +
                    "each other, so the surface folds back on itself and encloses nothing there"
            }
        }
        return null
    }

    /** The unit normal of one triangle, or null where it has no area to have one. */
    private fun normalOf(
        mesh: Mesh3,
        t: Tri,
    ): Vec3? {
        val a = mesh.vertices[t.a]
        val n = (mesh.vertices[t.b] - a).cross(mesh.vertices[t.c] - a)
        return if (n.length() <= Vec3.EPS) null else n.normalized()
    }

    /** Why [mesh] is not a closed, consistently wound shell, or null when it is. */
    fun notClosed(mesh: Mesh3): String? {
        val uses = HashMap<Long, Int>(mesh.triangles.size * 4)
        for (t in mesh.triangles) {
            for (e in longArrayOf(edge(t.a, t.b), edge(t.b, t.c), edge(t.c, t.a))) {
                uses[e] = (uses[e] ?: 0) + 1
            }
        }
        // the triangles are in canonical order, so the *message* is deterministic too
        for (t in mesh.triangles) {
            for ((from, to) in listOf(t.a to t.b, t.b to t.c, t.c to t.a)) {
                val forward = uses[edge(from, to)] ?: 0
                val back = uses[edge(to, from)] ?: 0
                if (forward != 1 || back != 1) {
                    return "the general boolean's result is not a closed shell: the edge between " +
                        "${mesh.vertices[from]} and ${mesh.vertices[to]} is used $forward times with $back " +
                        "opposite uses (a tangent or self-touching contact has no watertight mesh)"
                }
            }
        }
        return null
    }

    private fun edge(
        a: Int,
        b: Int,
    ): Long = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)

    /** `-0.0` mapped onto `0.0`: equal as numbers, different as hash keys, so a sign of zero could split a vertex. */
    private fun zero(v: Double): Double = if (v == 0.0) 0.0 else v

    /** Which [tol]-sized box of the weld lattice [v] falls in — the exact path's own scheme (`Geom3`). */
    private fun cellOf(
        v: Vec3,
        tol: Double,
    ): Long {
        val x = floor(v.x / tol).toLong()
        val y = floor(v.y / tol).toLong()
        val z = floor(v.z / tol).toLong()
        return (x * 73_856_093L) xor (y * 19_349_663L) xor (z * 83_492_791L)
    }

    /**
     * A position already kept that [v] is within [tol] of, or null — searched over the 27 boxes round its
     * own, so a coordinate landing just across a box boundary still finds its twin.
     */
    private fun nearby(
        buckets: Map<Long, MutableList<Vec3>>,
        v: Vec3,
        tol: Double,
    ): Vec3? {
        for (dx in -1..1) {
            for (dy in -1..1) {
                for (dz in -1..1) {
                    val probe = Vec3(v.x + dx * tol, v.y + dy * tol, v.z + dz * tol)
                    val here = buckets[cellOf(probe, tol)] ?: continue
                    for (w in here) {
                        if (abs(w.x - v.x) <= tol && abs(w.y - v.y) <= tol && abs(w.z - v.z) <= tol) return w
                    }
                }
            }
        }
        return null
    }
}
