package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
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
import kotlin.math.atan2
import kotlin.math.sqrt
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
 *
 * *Session 88 closed the queue entry this class was the evidence for* (*"the general boolean is deterministic
 * only to an ULP"*). The coin is the default binding's **mesh import** — pinned to one CPU it still lands both
 * ways, and one imported pair answers alike forty times — and nothing on that binding's surface pins it, while
 * the engine the app ships (WebAssembly, serial) and the from-source shim answer one way every time. So the
 * contract is stated per engine ([engineIsBitDeterministic]) and asserted through the suite's one helper
 * ([assertSameBody], [assertSameMesh], [assertSameFile]): bytes where the engine keeps them, the same body
 * where it does not. The keyhole corner and the file below are the queue entry's own two questions.
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
     * body — asserted through [assertSameBody], which is bytes-exact on an engine that keeps the bit contract.
     * Their answers are *not* the same mesh on the default binding — observed, printed, and asserted only where
     * the engine promises it.
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
                assertSameBody(Geom3.volume(ok), Geom3.volume(o0), "call $i, construction $k")
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

    // ---- session 89: the keyhole corner, and the file (the session-83 queue entry) ----

    /** `BlendCurvedCornerTest`'s keyhole: a 15 mm disc about `(0, 20)` on a 10 mm stem, 20 mm deep. */
    private fun keyhole(cx: Construction): SolidRef {
        val disc = 15.0
        val yj = 20.0 - sqrt(disc * disc - 25.0)
        val a = cx.freePoint("k${ids++}", (-5.0).mm, 0.0.mm)
        val b = cx.freePoint("k${ids++}", 5.0.mm, 0.0.mm)
        val c = cx.freePoint("k${ids++}", 5.0.mm, yj.mm)
        val d = cx.freePoint("k${ids++}", (-5.0).mm, yj.mm)
        val centre = cx.freePoint("k${ids++}", 0.0.mm, 20.0.mm)

        fun ang(deg: Double) = Quantity(deg * PI / 180.0, Dimension.ANGLE)
        val arc = cx.arc(centre, cx.const(disc.mm), cx.const(ang(atan2(yj - 20.0, 5.0) * 180.0 / PI)), cx.const(ang(atan2(yj - 20.0, -5.0) * 180.0 / PI)), true)
        val region = cx.region(cx.loop(cx.segment(a, b), cx.segment(b, c), arc, cx.segment(d, a)))
        return cx.extrude(cx.sketchOn(cx.planeXY(), region), cx.const(20.0.mm))
    }

    /** One fresh construction of the keyhole's inside corner rounded at 3 — the stem's top edge and the disc's — and every engine call it made. */
    private fun keyholeCorner(): Pair<List<Call>, Mesh3> {
        val calls = ArrayList<Call>()
        MeshBool.observer = { k, a, b, o -> calls.add(Call(k, a, b, o)) }
        try {
            ids = 0
            val cx = Construction()
            val on = keyhole(cx)
            val address = listOf(9, 10)
            val (choices, why) = Blend3.choicesFor(Evaluator().solid(on), address, BlendSection(BlendKind.FILLET, 3.0))
            val ref = cx.blendAll(on, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(3.mm), null, address, assertNotNull(choices, "the corner rounds: ${why?.render()}"))))
            val r = Evaluator().eval(ref.node)
            assertTrue(r !is EvalResult.Invalid, "the keyhole corner builds: ${(r as? EvalResult.Invalid)?.why?.render()}")
            return calls to Evaluator().solid(ref).mesh
        } finally {
            MeshBool.observer = null
        }
    }

    /**
     * **The keyhole corner, evaluated afresh two dozen times, is one body every time** — and one *mesh* every
     * time on an engine that keeps the bit contract ([engineIsBitDeterministic]).
     *
     * *This is the queue entry's own fixture, and its old contract fails here.* The default JVM binding answers
     * these bit-identical operands with two meshes, 1007 or 1010 vertices, at `15000.062802156805` or
     * `15000.06280217351` mm³ — measured 2 distinct answers in 40 calls, run after run, also pinned to one CPU
     * — so a bytes assertion on it fails about one evaluation in four. What is asserted is what is true of
     * every engine: the construction hands over the same operands to the bit, and each answer is the same
     * body ([assertSameMesh]); on the browser's engine and the from-source shim, the same bytes. Replaying the
     * first evaluation's own operands straight into the engine asks the same of the engine alone.
     */
    @Test
    fun theKeyholeCornerEvaluatedAfreshIsOneBodyEveryTime() {
        val n = 24
        val runs = (0 until n).map { keyholeCorner() }
        val first = runs[0]
        assertTrue(first.first.isNotEmpty(), "the corner asks the general boolean")
        for ((k, run) in runs.withIndex().drop(1)) {
            assertEquals(first.first.size, run.first.size, "evaluation $k asks the engine as often")
            for (i in first.first.indices) {
                assertTrue(identical(first.first[i].a, run.first[i].a), "evaluation $k, call $i: the first operand is bit-identical")
                assertTrue(identical(first.first[i].b, run.first[i].b), "evaluation $k, call $i: the second operand is bit-identical")
            }
            assertSameMesh(run.second, first.second, "evaluation $k of the keyhole corner")
        }
        val c = first.first.last()
        val replays = (0 until n).map { assertNotNull(MeshBool.boolean(c.kind, c.a, c.b).first, "the replay answers").mesh }
        for ((k, m) in replays.withIndex()) assertSameMesh(m, replays[0], "replay $k of the corner's own operands")
        val bodies = runs.map { it.second.vertices.size }.distinct().sorted()
        println("== keyhole corner (${MeshBool.status}): $n fresh evaluations give ${bodies.size} distinct mesh(es) $bodies; $n replays give ${replays.map { it.vertices.size }.distinct().sorted()}")
    }

    /** `BlendTightRingTest`'s drawing as a `.cit`, its body measured and that measurement wired to a parameter. */
    private fun measuredRingScript(): String {
        val inner = ring / 2.0
        val prof = listOf(Vec2(0.0, inner), Vec2(0.0, ring + 10.0), Vec2(10.0, ring + 10.0), Vec2(10.0, ring), Vec2(20.0, ring), Vec2(20.0, inner))
        val sb = StringBuilder("constructit ${DocumentFormat.JOURNAL_VERSION}\n")
        sb.append("orthostart ${prof[0].x},${prof[0].y} -> e1\n")
        var n = 2
        for (i in 1 until prof.size) {
            sb.append("orthovertex ${prof[i].x},${prof[i].y} -> e$n,e${n + 1}\n")
            n += 2
        }
        val region = n - 1
        sb.append("orthoclose -> e$n\n")
        n++
        sb.append("point 0,0 -> e$n\n")
        sb.append("point 1,0 -> e${n + 1}\n")
        sb.append("tool line pts=e$n,e${n + 1} clicks=0,0;1,0 -> e${n + 2}\n")
        val axis = n + 2
        n += 3
        sb.append("param \"a\" = 270deg\n")
        sb.append("tool revolve els=e$region,e$axis clicks=5,$ring;50,0 scalar=\"a\" -> e$n\n")
        var at = n
        sb.append("param \"r\" = 2mm\n")
        for (k in 0 until 2) {
            val body = Evaluator().solid(bodyOf(DocumentFormat.load(sb.toString()).elements).ref as SolidRef)
            val sharp = pair(body).filter { Blend3.choicesFor(body, listOf(it), BlendSection(BlendKind.FILLET, 2.0)).first?.get(0)?.convex == true }
            assertEquals(2 - k, sharp.size, "the reflex pair still has ${2 - k} sharp edge(s) to round")
            val choice = assertNotNull(Blend3.choicesFor(body, listOf(sharp[0]), BlendSection(BlendKind.FILLET, 2.0)).first, "edge ${sharp[0]} is scored")[0]
            sb.append("tool filletedge els=e$at clicks=0,0 scalar=\"r\" signs=${sharp[0]};${choice.signs().joinToString(";")} -> e${at + 1},e${at + 2}\n")
            at += 1
        }
        sb.append("tool mvolume els=e$at clicks=0,0\n")
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(sb.toString()))
        val v = ed.doc.newParameter("v", Quantity(1.0, Dimension.VOLUME))
        assertTrue(ed.doc.wireParameter(v, ed.doc.scalars.single { it.name == "vol" }), "a parameter takes the measured volume")
        return DocumentFormat.save(ed.doc)
    }

    private fun bodyOf(els: List<Element>): Element = els.last { it.kind == ElementKind.SOLID }

    /**
     * **A file may state a value the general boolean computed, and is still a fixed point of save and load**
     * (OP-4, OP-18) — the question the queue entry ends on, answered by reading the format rather than hoped.
     *
     * The format records gestures, and a gesture's literals are the user's; but a source node that is
     * **bound** is restated with what drives it ([DocumentFormat]'s `restate`), so a parameter wired to a
     * rounded body's measured volume writes that volume into its own `parameters` entry (OP-32), and a point welded to a
     * corner of a bored body's section writes the corner. Those two are the only roads a boolean-derived
     * float has into a file. On the shipped engine the boolean is bit-deterministic and the file is
     * byte-equal through any number of reloads; on the default JVM binding the stated number may come back
     * retriangulated, and [assertSameFile] says exactly how far.
     */
    @Test
    fun aFileThatStatesABooleanDerivedVolumeIsAFixedPointOfSaveAndLoad() {
        val once = measuredRingScript()
        val stated = once.lines().single { it.contains("\"name\":\"v\"") }
        println("== the wired parameter as the file states it: $stated")
        val volume = Regex("[0-9]+\\.[0-9]+").findAll(stated).map { it.value.toDouble() }.maxOrNull() ?: 0.0
        assertTrue(volume > 1000.0, "the file states the measured volume itself: $stated")
        for (k in 0 until 6) assertSameFile(DocumentFormat.save(DocumentFormat.load(once)), once, "reload $k of a file stating a measured volume")
    }
}
