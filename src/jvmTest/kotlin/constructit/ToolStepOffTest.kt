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
 * equality and not a tolerance. A **union**'s two coplanar caps pointing the *same* way are not the
 * degeneracy and are not counted: each face is used once each way, the union keeps one of them, and that is
 * what a fill's own cap standing in the wall it fills against has relied on since session 79's butt ends.
 *
 * *And the answer is not zero, so it is stated as the residue it is* (OP-31, slice 5q's one cut; the idiom
 * is `BlendMatrixTest.Residue`'s — a number here is a **claim that a defect is still there**, and it fails
 * the build the moment the number moves either way). Over the matrix's 288 two-edge cells, in both gesture
 * routes, sixteen difference tools and thirty-two union tools still hand the kernel a flush contact, and
 * every one of them is the same case: the plane is the body's own face *already carried a micron by an
 * earlier tool's step-off*, and the next tool steps off the nominal face by the same micron and lands
 * exactly on it. A step-off measured against the face the drawing names cannot see a face the previous
 * boolean left a micron away from it, and the cure — a step-off that reads the body it is standing on — is
 * a slice of its own. Every one of these cells builds today under both engines; what the number says is
 * that they build because the contact happened to be resolvable, not because it was never handed over.
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
            val shared = ToolStep.sharedPlanes(h.a, h.b, opposedOnly = h.kind == BoolOp.UNION)
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
        assertEquals(16, differences, "the difference tools that still lay a face in a face of the body")
        assertEquals(32, unions, "…and the union tools that back a face onto one")
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
        assertEquals(16, differences, "the same difference tools under ${MeshBool.status}")
        assertEquals(32, unions, "…and the same union tools")
    }
}
