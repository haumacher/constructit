package constructit

import constructit.core.Evaluator
import constructit.geom.BlendKind
import constructit.geom.BoolOp
import constructit.geom.Mesh3
import constructit.geom.MeshBool
import constructit.geom.ToolStep
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A tool never shares a face with the body** (GitHub #33; OP-31, slice 5q) — asked of the matrix's own
 * bodies rather than argued in five separate places, and answered with the number it actually is.
 *
 * *The rule.* Every tool this drawing builds to work on a body — a cut's sketch, a blend's stitched band, a
 * canal's rolling section, a corner's pivot, a free end's cap — is stated **off** the body by its own
 * step-off ([ToolStep]): the micron where the face it stands against is a plane, the body's own skin where
 * that face is curved. Its purpose is a fact about the general engine and not about the drawing: a coplanar
 * pair of faces is a cancellation the kernel has to adjudicate, and it has no watertight answer to
 * adjudicate it with — under float32 the adjudication came out a coin, under float64 it comes out a
 * zero-thickness flap or an edge used twice, which is worse only because it is honest.
 *
 * *What is asked, and why the mesh is the right place to ask it.* Every boolean the matrix runs passes its
 * two operands through [MeshBool.observer], and for each pair this asks what the kernel is about to be
 * asked: do the two meshes have a face **in one plane**, overlapping there? Asking the *mesh* rather than
 * the face lists is deliberate — a tool's triangles are what the engine sees, several tools have no face
 * list at all (a sweep, a skin), and a plane a mesh states is stated exactly, so what decides it is an
 * equality and not a tolerance.
 *
 * *And which of the two senses is the degeneracy is decided by the **operation*** (OP-31, slice 5v). A
 * **union**'s two coplanar caps pointing the *same* way are not the degeneracy and are not counted: each
 * face is used once each way, the union keeps one of them, and that is what a fill's own cap standing in the
 * wall it fills against has relied on since session 79's butt ends. A **difference** is that same sentence
 * the other way round, and session 87 is where it was said: `A − B` is `A ∩ Bᶜ`, so every face of the tool
 * is used the other way about, and a tool face standing **against** a face of the body is the pair that
 * comes out pointing the same way — one face kept, each edge used once each way — while a tool face lying
 * in a face of the body and pointing **with** it is the pair that backs onto itself. So a tool that *lands
 * in* a face of the body is counted and a tool that **re-cuts** a face the body already carries is not; the
 * number over the matrix is **sixteen** either way, because every cell that had one had the other too.
 *
 * *And the answer is not zero, so it is stated as the residue it is* (OP-31, slice 5q's one cut; the idiom
 * is `BlendMatrixTest.Residue`'s — a number here is a **claim that a defect is still there**, and it fails
 * the build the moment the number moves either way). Over the matrix's 288 two-edge cells, in both gesture
 * routes, it was **sixteen difference tools and thirty-two union tools**.
 *
 * *The thirty-two are gone, and they were all one sentence* (OP-31, slice 5v; [ToolStep.clear]). Their
 * plane was the body's own face **already carried a micron by an earlier tool's step-off** — a micron-thick
 * ledge along the very face the next tool's leg was about to stand in — and the next tool stepped off the
 * *nominal* face by the same micron and came down exactly on it. **A step-off reads the body it is standing
 * on, not the plane the drawing names**: every tool of a pass is now stated against the body the kernel is
 * actually going to be handed, and the step is carried past whatever the body really has there. Not one of
 * the matrix's 288 volumes moves by so much as a bit.
 *
 * *The sixteen were two cases, and both of them are about where a corner **lands*** rather than about how
 * far a band stands off a face. **The first is gone** (OP-31, slice 5v, session 87): a ring standing on a
 * **pivot axis** used to take the wholly *plain* section, because the leg in the face the ball does not roll
 * on lies along the axis and a micron off the axis is swept into a zero-thickness disc — and its **other**
 * leg then lay flat in the shared face, swept into a sector of that very face. It now takes a section
 * stepped in the shared face and left flat on the axis (`Blend3`'s `Grown.axial1`/`axial2`), which is
 * GitHub #33's diagonal-jog argument said for a turn rather than for a run: the same points in the same
 * order as the grown twin, so every ring of the corner — the pivot's, the `Ledge`'s, the bands' own end
 * rings — is stepped identically and the C¹ meeting between them is exactly what it was. Not one volume in
 * the suite moves by a bit.
 *
 * *What is left is the second, and it is **eight***: **a ledge lands in the wall the corner stands
 * against**, because that is where it lands — a `Ledge`'s landing plane is square to the shallower band's
 * run, and at an inside corner of two bottom-rim edges that plane **is** the upright's own face, so the
 * ledge's cap lies flat in it. Two cures were built and discarded on their own evidence, and both are
 * recorded under OP-31: carrying every tool vertex in that plane by the skin shears the walk's last strip
 * (twenty triangles off their own carriers, the four gesture routes no longer one body), and lifting the cap
 * alone as a slab with a skirt back onto each ring puts four triangles on the pivot's own pole, where the
 * two rings coincide.
 *
 * Every one of these cells builds today under both engines; what the number says is that they build because
 * the contact happened to be resolvable, not because it was never handed over.
 *
 * It runs under **both** engines: the rule is the drawing's and owes nothing to which kernel is behind the
 * seam, and the from-source one is skipped where it is not built ([MeshBool.isNative]).
 */
class ToolStepOffTest {
    private val L = LBlock()

    private val kinds = listOf(BlendKind.FILLET, BlendKind.CHAMFER)

    /** One observed pair of operands: what the drawing was about to hand the kernel. */
    private class Handed(val kind: BoolOp, val a: Mesh3, val b: Mesh3)

    private fun handed(build: () -> Unit): List<Handed> {
        val seen = ArrayList<Handed>()
        MeshBool.observer = { k, a, b, _ -> seen.add(Handed(k, a, b)) }
        try {
            build()
        } finally {
            MeshBool.observer = null
        }
        return seen
    }

    /** Which of [pairs] hand the kernel a tool with a face in a face of the body, by operation. */
    private fun flush(pairs: List<Handed>): List<BoolOp> =
        pairs.mapNotNull { h ->
            val shared = ToolStep.sharedPlanes(h.a, h.b, opposed = h.kind == BoolOp.UNION)
            if (shared.isEmpty()) null else h.kind
        }

    /** The whole sweep: how many difference tools, and how many union tools, stand in a face of the body. */
    private fun sweep(pairs: List<Pair<Int, Int>>): Pair<Int, Int> {
        var differences = 0
        var unions = 0
        for ((a, b) in pairs) {
            for (ka in kinds) {
                for (kb in kinds) {
                    for (route in listOf(Route.ONE_PASS, Route.STACKED)) {
                        val seen =
                            handed {
                                val (refs, _) = L.run(listOf(Rounding(a, ka, 4.0), Rounding(b, kb, 4.0)), route, together = false)
                                refs?.forEach { Evaluator().eval(it.node) }
                            }
                        for (k in flush(seen)) if (k == BoolOp.UNION) unions++ else differences++
                    }
                }
            }
        }
        return differences to unions
    }

    /** **Every pair at a vertex, in both routes** — and the two counts are the residue above, exactly. */
    @Test
    fun everyToolOfTheMatrixStandsOffTheBodyOrIsCountedHere() {
        val pairs = L.block.pairs.map { (a, b, _) -> a to b }
        assertTrue(pairs.size == 36, "the matrix's thirty-six pairs: ${pairs.size}")
        val (differences, unions) = sweep(pairs)
        println("== tool step-off: 288 cells — $differences difference and $unions union tools hand over a flush contact")
        assertEquals(8, differences, "the difference tools that still lay a face in a face of the body")
        assertEquals(0, unions, "…and the union tools that back a face onto one")
    }

    /**
     * **And the from-source engine sees the same tools.** What a tool hands over is the drawing's own fact
     * and owes nothing to which kernel is behind the seam, so the two numbers are the same ones. Skipped
     * where the shim is not built.
     */
    @Test
    fun theSameNumbersHoldUnderTheFromSourceEngine() {
        assumeTrue(MeshBool.isNative, "not the from-source engine (-Dconstructit.manifold.native=<dir>): ${MeshBool.status}")
        val (differences, unions) = sweep(L.block.pairs.map { (a, b, _) -> a to b })
        assertEquals(8, differences, "the same difference tools under ${MeshBool.status}")
        assertEquals(0, unions, "…and the same union tools")
    }
}
