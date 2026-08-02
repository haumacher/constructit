package constructit.geom

/**
 * **Is this mesh a closed, oriented solid?** — the one production implementation of the question OP-9's
 * *watertight-or-refused* doctrine is stated in, and the twin of the test suite's `assertManifold`.
 *
 * It lived inside the 3MF writer while printing was the only consumer that had to ask. It has three now —
 * 3MF and STL refuse to write a body that is not one, and the **JT import** *flags* one rather than refusing
 * it — so it lives where the mesh does, and there is exactly one answer to the question in the build.
 *
 * The check is structural, not approximate: every directed edge used **exactly once** with its reverse used
 * exactly once — which says *closed* and *consistently oriented* in one statement — plus a positive enclosed
 * volume, so the consistent orientation is the outward one. No tolerance, and nothing is repaired: a mesh
 * this would have to mend is a mesh whose geometry the build does not understand.
 *
 * **What it is not.** It is not a policy. A solid the kernel *constructs* is watertight by construction and
 * this only ever confirms it; a mesh that arrives from outside may be an **open shell**, and what happens
 * then is the *consumer's* answer, not this function's — printing refuses it, a boolean refuses it, and
 * display, arrangement and re-engineering are perfectly happy with it (the JT import note under OP-9).
 */
object Watertight {
    /**
     * Why [mesh] is **not** a closed oriented solid, naming the first defect found — or null when it is one.
     *
     * The message is a sentence fragment ("the surface is not closed and consistently wound at the edge 3-4"),
     * so every caller can put it after its own framing rather than re-deriving the reason.
     */
    fun defect(mesh: Mesh3): String? {
        if (mesh.triangles.isEmpty()) return "it has no triangles"
        val used = HashMap<Long, Int>(mesh.triangles.size * 3)
        for (t in mesh.triangles) {
            if (t.a == t.b || t.b == t.c || t.a == t.c) return "a triangle repeats a corner"
            for (e in listOf(t.a to t.b, t.b to t.c, t.c to t.a)) {
                val key = (e.first.toLong() shl 32) or (e.second.toLong() and 0xffffffffL)
                used[key] = (used[key] ?: 0) + 1
            }
        }
        // Iterate the triangles, not the map: the checks are order-free but the message must not be.
        for (t in mesh.triangles) {
            for (e in listOf(t.a to t.b, t.b to t.c, t.c to t.a)) {
                val fwd = (e.first.toLong() shl 32) or (e.second.toLong() and 0xffffffffL)
                val back = (e.second.toLong() shl 32) or (e.first.toLong() and 0xffffffffL)
                if (used[fwd] != 1 || used[back] != 1) {
                    return "the surface is not closed and consistently wound at the edge ${e.first}-${e.second}"
                }
            }
        }
        if (Geom3.volume(mesh) <= 0.0) return "it encloses no positive volume — the surface is inside out"
        return null
    }
}
