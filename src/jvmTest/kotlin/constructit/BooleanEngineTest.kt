package constructit

import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.MeshBool
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.units.Dimension
import constructit.units.Quantity
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The general boolean in double precision, and deterministic** (OP-9; OP-31's (5q), prototype).
 *
 * Two things were measured about the JVM's boolean engine in session 86 and neither was good. Its answer is
 * **not a function of its operands** — byte-identical operands come back as 2334 vertices on one call and
 * 2336 on the next, in one JVM, pinned to one CPU ([BooleanDeterminismTest] is where that is pinned). And it
 * carries positions as **float32**, so a second boolean on a curved body re-snaps the first one's
 * tessellation and two disjoint roundings of one partial revolve disagree by 1.69e-4 mm³ between the two
 * gesture routes. Both are the *binding's*, not the construction's: the engine inside the clojars jar is
 * already a Manifold 3 with `GetMeshGL64` in it, under a Java surface that cannot reach it.
 *
 * `native/` is the answer built in session 86: an upstream Manifold 3.5.1 — **the version the browser already
 * runs** — compiled from source with `MANIFOLD_PAR=OFF` (serial, so nothing is left to race) behind a JNI
 * shim of one C++ file that speaks `MeshGL64` both ways. This class is what measures it. Every test here
 * **skips** unless that shim is what `MeshBool` loaded, because it is a prototype a developer opts into with
 * `-Dconstructit.manifold.native=<dir>` and a bare checkout has no toolchain to build it with; the suite's
 * default engine is unchanged and so is every tolerance written against it.
 */
class BooleanEngineTest {
    private var ids = 0
    private val ring = 8.0

    private fun requireNative() =
        assumeTrue(
            MeshBool.available && MeshBool.isNative,
            "not the from-source engine (-Dconstructit.manifold.native=<dir>): ${MeshBool.status}",
        )

    private fun polygon(
        cx: Construction,
        pts: List<Vec2>,
    ): RegionRef {
        val ps = pts.map { cx.freePoint("E${ids++}", it.x.mm, it.y.mm) }
        return cx.region(cx.loop(*ps.indices.map { cx.segment(ps[it], ps[(it + 1) % ps.size]) }.toTypedArray()))
    }

    /**
     * The same fixture [BooleanDeterminismTest] measures on: an L-shaped meridian turned 270° about `x`, so
     * the body's walls are **curved** — which is the whole point, since a box's coordinates survive float32
     * exactly and show nothing.
     */
    private fun turned(cx: Construction): SolidRef {
        val inner = ring / 2.0
        val prof = listOf(Vec2(0.0, inner), Vec2(0.0, ring + 10.0), Vec2(10.0, ring + 10.0), Vec2(10.0, ring), Vec2(20.0, ring), Vec2(20.0, inner))
        val o = cx.freePoint("Eo${ids++}", 0.mm, 0.mm)
        val axis = cx.direction(o, cx.freePoint("Ex${ids++}", 1.mm, 0.mm))
        return cx.revolve(cx.sketchOn(cx.planeXY(), polygon(cx, prof)), o, axis, cx.const(Quantity(270.0 * PI / 180.0, Dimension.ANGLE)))
    }

    private class Call(val a: Mesh3, val b: Mesh3, val out: Mesh3?)

    private fun watching(build: () -> Unit): List<Call> {
        val calls = ArrayList<Call>()
        MeshBool.observer = { _, a, b, o -> calls.add(Call(a, b, o)) }
        try {
            build()
        } finally {
            MeshBool.observer = null
        }
        return calls
    }

    private fun identical(
        a: Mesh3,
        b: Mesh3,
    ): Boolean = a.vertices.size == b.vertices.size && a.triangles == b.triangles && a.vertices.indices.all { a.vertices[it] == b.vertices[it] }

    /** [BooleanDeterminismTest]'s pivot at the ring, built once. */
    private fun pivot(): List<Call> =
        watching {
            ids = 0
            val cx = Construction()
            val base = turned(cx)
            val body = Evaluator().solid(base)
            val at = constructit.geom.Vec3(10.0, ring, 0.0)
            val es = assertNotNull(Section3.edges(body.feature).first, "it names its edges")
            val address =
                es.indices.filter { i ->
                    val e = es[i]
                    if (!e.between.a.label.render().contains("cap at the start") && !e.between.b.label.render().contains("cap at the start")) return@filter false
                    val path = Blend3.edgePath(e).first ?: return@filter false
                    val s = path.start ?: return@filter false
                    val t = path.end ?: return@filter false
                    (s - at).length() < 1e-6 || (t - at).length() < 1e-6
                }
            val (choices, why) = Blend3.choicesFor(body, address, BlendSection(BlendKind.FILLET, 2.0))
            val ref = cx.blendAll(base, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(2.mm), null, address, assertNotNull(choices, "the pair rounds: ${why?.render()}"))))
            Evaluator().eval(ref.node)
        }

    /**
     * **Determinism: one mesh, twelve times.** The measurement session 86 made is repeated here against the
     * from-source engine — twelve fresh constructions of the pivot at the tight ring, which hand the engine
     * byte-identical operands call for call — and this time the *answers* are byte-identical too, not merely
     * equal in volume. That is `MANIFOLD_PAR=OFF` doing the only thing it was chosen for: Manifold 3 dropped
     * `ExecutionParams::deterministic`, so a serial build is what stands in its place, and being serial there
     * is no thread-count-dependent reduction left for a marginal contact to be decided by.
     */
    @Test
    fun identicalOperandsGiveOneMeshOnTwelveCalls() {
        requireNative()
        val runs = (0 until 12).map { pivot() }
        val n = runs[0].size
        assertTrue(n >= 2, "the pivot asks the engine at least twice: $n")
        for (run in runs) assertEquals(n, run.size, "every construction asks the same number of times")
        for (i in 0 until n) {
            val c0 = runs[0][i]
            val o0 = assertNotNull(c0.out, "call $i answers")
            for ((k, run) in runs.withIndex().drop(1)) {
                assertTrue(identical(c0.a, run[i].a), "call $i, construction $k: the first operand is bit-identical")
                assertTrue(identical(c0.b, run[i].b), "call $i, construction $k: the second operand is bit-identical")
                val ok = assertNotNull(run[i].out, "call $i, construction $k answers")
                assertTrue(identical(o0, ok), "call $i, construction $k: the **answer** is bit-identical too (${o0.vertices.size} vs ${ok.vertices.size} vertices)")
            }
        }
        println("== ${MeshBool.status}: ${runs.size} constructions × $n calls, one mesh each (${runs[0].map { it.out!!.vertices.size }})")
    }

    /** Every edge of [body] a fillet of radius [r] scores on its own. */
    private fun roundable(
        body: Solid3,
        r: Double,
    ): List<Int> {
        val es = assertNotNull(Section3.edges(body.feature).first, "it names its edges")
        return es.indices.filter { Blend3.choicesFor(body, listOf(it), BlendSection(BlendKind.FILLET, r)).first != null }
    }

    private fun rounded(
        edges: List<Int>,
        r: Double,
    ): Double {
        ids = 0
        val cx = Construction()
        val base = turned(cx)
        val body = Evaluator().solid(base)
        val runs = edges.map { e -> Construction.BlendRun(BlendKind.FILLET, cx.const(r.mm), null, listOf(e), assertNotNull(Blend3.choicesFor(body, listOf(e), BlendSection(BlendKind.FILLET, r)).first, "edge $e rounds")) }
        return Geom3.volume(Evaluator().solid(cx.blendAll(base, cx.planeXY(), runs)).mesh)
    }

    /**
     * **Precision: the two gesture routes agree.** Session 86's experiment, run against the new engine. Two
     * ordinary fillets on two **disjoint** edges of one partial revolve — no pivot, no corner, nothing shared
     * — remove a fixed amount of material, and *what* they remove cannot depend on whether they were taken
     * one at a time or in one gesture. Under float32 it did: 20.637743331 mm³ one at a time against
     * 20.637574231 mm³ together, a deficit of 1.69e-4 mm³ (8.2e-6 relative), because the first boolean
     * re-snapped the revolve's cylinder tessellation and the second then cut a slightly different polyhedron.
     *
     * Under `MeshGL64` the same experiment reaches **5.2e-12 relative** on this fixture, where the clojars binding reaches 1.37e-7 on the very same one — the claim (5q) was
     * queued on is 1e-9, met with three orders to spare. This one runs on **both** engines so the contrast is
     * the same two numbers rather than two experiments: the old engine's own figure is printed beside it and
     * only the assertion is held to the new one, exactly as [BooleanDeterminismTest]'s vertex counts are.
     */
    @Test
    fun theTwoGestureRoutesRemoveTheSameMaterial() {
        assumeTrue(MeshBool.available, "no general boolean engine: ${MeshBool.status}")
        val r = 1.0
        ids = 0
        val cx = Construction()
        val body = Evaluator().solid(turned(cx))
        val whole = Geom3.volume(body.mesh)
        val es = assertNotNull(Section3.edges(body.feature).first, "it names its edges")
        val candidates = roundable(body, r)
        assertTrue(candidates.size >= 2, "the revolve has at least two edges a 1 mm fillet scores on: $candidates")

        // two edges that share no endpoint: disjoint, so neither rounding can touch the other's material
        val pair =
            candidates.flatMap { i -> candidates.filter { it > i }.map { i to it } }
                .firstOrNull { (i, j) ->
                    val p = Blend3.edgePath(es[i]).first ?: return@firstOrNull false
                    val q = Blend3.edgePath(es[j]).first ?: return@firstOrNull false
                    listOfNotNull(p.start, p.end).all { a -> listOfNotNull(q.start, q.end).all { b -> (a - b).length() > 4 * r } }
                }
        assertNotNull(pair, "two disjoint roundable edges: $candidates")

        val apart = (whole - rounded(listOf(pair.first), r)) + (whole - rounded(listOf(pair.second), r))
        val together = whole - rounded(listOf(pair.first, pair.second), r)
        val rel = abs(apart - together) / apart
        println("== ${MeshBool.status}: edges ${pair.first}+${pair.second} take $apart mm³ one at a time and $together mm³ in one gesture ($rel relative)")
        if (MeshBool.isNative) {
            assertTrue(rel < 1e-9, "the two routes remove the same material to 1e-9 relative: $apart vs $together ($rel)")
        }
    }
}
