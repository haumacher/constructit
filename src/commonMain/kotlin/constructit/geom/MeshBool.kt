package constructit.geom

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
 * 1. **Weld** vertices with *identical* coordinates — the only duplicates a mesh engine produces are
 *    bit-identical copies of one position (a seam between two property runs), and welding on a tolerance
 *    would instead risk merging two genuinely distinct points. `-0.0` is normalised to `0.0` first, since
 *    the two are equal as numbers but not as keys.
 * 2. **Sort** the surviving positions lexicographically and renumber.
 * 3. **Rotate** every triangle so its smallest index comes first — winding-preserving, which is the whole
 *    reason it is a rotation and not a sort — drop the degenerate ones, then sort the triangles.
 */
object MeshCanon {
    fun canonical(mesh: Mesh3): Mesh3 {
        val normalized = mesh.vertices.map { Vec3(zero(it.x), zero(it.y), zero(it.z)) }
        val order =
            normalized
                .distinct()
                .sortedWith(compareBy({ it.x }, { it.y }, { it.z }))
        val indexOf = HashMap<Vec3, Int>(order.size * 2)
        for ((i, v) in order.withIndex()) indexOf[v] = i
        val remap = IntArray(normalized.size) { indexOf.getValue(normalized[it]) }

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

    /** Why [mesh] is not a closed, consistently wound shell, or null when it is. */
    fun fault(mesh: Mesh3): String? {
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
}
