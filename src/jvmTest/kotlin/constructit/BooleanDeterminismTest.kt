package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.BoolOp
import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.MeshBool
import constructit.geom.Section3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Dimension
import constructit.units.Quantity
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Whose coin is it?** (OP-9; OP-31, slices 5h/5o and the queue's (5q)).
 *
 * Two rounds of slice 5o found the pivot at a tight ring deciding by a coin: the same cell builds, refuses
 * and refuses again in one JVM, and the 160-cell sweep's tally moves by ±4 between runs of one tree. A coin
 * has to be someone's, and the stored model is a pure function of its parameters *by doctrine* (recompute,
 * undo and reload must agree), so this class asks the question at the one seam where it can be asked —
 * [MeshBool.observer], through which every general boolean passes its operands and its answer.
 *
 * *What it found, session 86.* Two fresh constructions of the same pivot hand the engine **byte-identical**
 * operands, and the engine answers differently: 2334 vertices on one call, 2336 on the next, with the volume
 * equal to nine decimals — in one JVM, and also with the process pinned to one CPU. Our construction is
 * deterministic; the engine's mesh is not a function of its operands. That is recorded here as what is
 * asserted (the operands, and the volume the answers agree on) and what is deliberately *not* (the mesh
 * itself), until (5q) puts a binding underneath that can be told to be deterministic.
 *
 * *And when it does.* Pointed at the from-source engine (`-Dconstructit.manifold.native=<dir>`, see
 * `native/` and [BooleanEngineTest]), the print below becomes an **assertion**: one distinct vertex count,
 * because a serial Manifold 3 has nothing left to toss a coin with. The old engine's path keeps printing, so
 * this class says the same true thing about whichever engine is underneath it.
 */
class BooleanDeterminismTest {
    private var ids = 0
    private val ring = 8.0

    private fun polygon(
        cx: Construction,
        pts: List<Vec2>,
    ): RegionRef {
        val ps = pts.map { cx.freePoint("T${ids++}", it.x.mm, it.y.mm) }
        return cx.region(cx.loop(*ps.indices.map { cx.segment(ps[it], ps[(it + 1) % ps.size]) }.toTypedArray()))
    }

    /** `BlendTightRingTest`'s fixture: an L-shaped meridian turned 270° about `x`, its cap's reflex corner on the ring. */
    private fun turned(cx: Construction): SolidRef {
        val inner = ring / 2.0
        val prof = listOf(Vec2(0.0, inner), Vec2(0.0, ring + 10.0), Vec2(10.0, ring + 10.0), Vec2(10.0, ring), Vec2(20.0, ring), Vec2(20.0, inner))
        val o = cx.freePoint("To${ids++}", 0.mm, 0.mm)
        val axis = cx.direction(o, cx.freePoint("Tx${ids++}", 1.mm, 0.mm))
        return cx.revolve(cx.sketchOn(cx.planeXY(), polygon(cx, prof)), o, axis, cx.const(Quantity(270.0 * PI / 180.0, Dimension.ANGLE)))
    }

    private fun pair(solid: constructit.geom.Solid3): List<Int> {
        val at = Vec3(10.0, ring, 0.0)
        val es = assertNotNull(Section3.edges(solid.feature).first, "it names its edges")
        return es.indices.filter { i ->
            val e = es[i]
            if (!e.between.a.label.render().contains("cap at the start") && !e.between.b.label.render().contains("cap at the start")) return@filter false
            val path = Blend3.edgePath(e).first ?: return@filter false
            val s = path.start ?: return@filter false
            val t = path.end ?: return@filter false
            (s - at).length() < 1e-6 || (t - at).length() < 1e-6
        }
    }

    private class Call(val kind: BoolOp, val a: Mesh3, val b: Mesh3, val out: Mesh3?)

    /** One fresh construction of the pivot at `r = 2`, and every boolean it asked the engine for. */
    private fun pivotCalls(): List<Call> {
        val calls = ArrayList<Call>()
        MeshBool.observer = { k, a, b, o -> calls.add(Call(k, a, b, o)) }
        try {
            ids = 0
            val cx = Construction()
            val base = turned(cx)
            val body = Evaluator().solid(base)
            val address = pair(body)
            val (choices, why) = Blend3.choicesFor(body, address, BlendSection(BlendKind.FILLET, 2.0))
            val ref = cx.blendAll(base, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(2.mm), null, address, assertNotNull(choices, "the pair rounds: ${why?.render()}"))))
            val r = Evaluator().eval(ref.node)
            assertTrue(r !is EvalResult.Invalid, "the pivot at R = 8, r = 2 builds: ${(r as? EvalResult.Invalid)?.why?.render()}")
        } finally {
            MeshBool.observer = null
        }
        return calls
    }

    private fun identical(
        a: Mesh3,
        b: Mesh3,
    ): Boolean = a.vertices.size == b.vertices.size && a.triangles == b.triangles && a.vertices.indices.all { a.vertices[it] == b.vertices[it] }

    /**
     * **The construction is deterministic; the engine's mesh is not.** Six fresh constructions hand the
     * engine the same operands, call for call, bit for bit — that is asserted. Their answers enclose the same
     * volume — asserted to the float32 snap. Their answers are *not* the same mesh — observed, printed, and
     * left unasserted on purpose: the day (5q) lands, the print below is the assertion to promote.
     */
    @Test
    fun theConstructionHandsIdenticalOperandsAndTheEngineAnswersToTheSameVolume() {
        val runs = (0 until 6).map { pivotCalls() }
        val n = runs[0].size
        assertTrue(n >= 2, "the pivot asks the engine at least twice (the bands, then the corner): $n")
        for (run in runs) assertEquals(n, run.size, "every construction asks the same number of times")
        for (i in 0 until n) {
            val c0 = runs[0][i]
            for ((k, run) in runs.withIndex().drop(1)) {
                val ck = run[i]
                assertEquals(c0.kind, ck.kind, "call $i: the same operation")
                assertTrue(identical(c0.a, ck.a), "call $i, construction $k: the first operand is bit-identical (${c0.a.vertices.size} vertices)")
                assertTrue(identical(c0.b, ck.b), "call $i, construction $k: the second operand is bit-identical (${c0.b.vertices.size} vertices)")
                val o0 = assertNotNull(c0.out, "call $i answers")
                val ok = assertNotNull(ck.out, "call $i, construction $k answers")
                val v0 = Geom3.volume(o0)
                assertClose(Geom3.volume(ok), v0, 1e-7 * abs(v0), "call $i, construction $k: the same volume")
            }
        }
        val sizes = runs.map { it.last().out!!.vertices.size }.distinct().sorted()
        println("== boolean determinism (${MeshBool.status}): identical operands on $n calls × ${runs.size} constructions; the engine's final mesh has ${sizes.size} distinct vertex count(s): $sizes")
        if (MeshBool.isNative) assertEquals(1, sizes.size, "the from-source engine is serial, so identical operands give one mesh: $sizes")
    }

    /**
     * **The base body and a plain band are fixed points**, so the coin is not everywhere: a revolve's mesh, a
     * single rounding at the ring, and a prism's rounded edge are the same mesh on every evaluation. This is
     * the boundary of the finding — where the answer *is* a function of the operands — kept so the coin's
     * territory is known rather than feared.
     */
    @Test
    fun aRevolveAndASingleBandAreFixedPointsOfEvaluation() {
        val bases =
            (0 until 3).map {
                ids = 0
                Evaluator().solid(turned(Construction())).mesh
            }
        assertTrue(bases.all { identical(it, bases[0]) }, "the turned body is one mesh on every evaluation")
        val bands =
            (0 until 3).map {
                ids = 0
                val cx = Construction()
                val base = turned(cx)
                val body = Evaluator().solid(base)
                val one = listOf(pair(body)[0])
                val (choices, why) = Blend3.choicesFor(body, one, BlendSection(BlendKind.FILLET, 2.0))
                val ref = cx.blendAll(base, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(2.mm), null, one, assertNotNull(choices, "one band rounds: ${why?.render()}"))))
                Evaluator().solid(ref).mesh
            }
        assertTrue(bands.all { identical(it, bands[0]) }, "a single band at the ring is one mesh on every evaluation (${bands.map { it.vertices.size }})")
    }
}
