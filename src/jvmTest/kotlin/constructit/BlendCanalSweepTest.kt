package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.EdgeGeom
import constructit.geom.EdgeName
import constructit.geom.FaceName
import constructit.geom.Geom3
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Every canal a ball can physically roll builds** (OP-31, slice 5f; the rework of session 84) — the matrix's
 * own reading, said for the band that has no rigid section.
 *
 * A 40 × 30 × 20 block with its four top edges rounded leaves four elliptical mitres, and a ball of radius
 * `rc` rolls along each of them. The sweep reads three round sizes against six ball sizes, the rounds made
 * **both** as one entry of one gesture and as four gestures one after another, and the canals made as one
 * mitre, as two mitres that share a band, as all four in one gesture, and as all four one gesture at a time.
 * Every cell is one of exactly two states:
 *
 * - **built** — the body is manifold, what it took off is inside the figure `canalRemoval` states for it, and
 *   the two routes that make the same body (one gesture with four addresses, and four gestures) agree to the
 *   engine's own resolution;
 * - **refused by name** — the node is `EvalResult.Invalid` (or the scoring declines) with a reason that names
 *   an edge, a face or a crease.
 *
 * There is no third state and no residue: a Manifold status code, a mesh diagnostic or a bare "cannot" is a
 * failure of this test, because a ball that fits is a body this drawing owes. The two defects the rework
 * found — a cap triangulated into slivers where a run's end leg is straight ([Blend3] `widestEars`), and a
 * tool stepped off a *curved* wall by a micron where that wall's own triangles stand a whole tessellation
 * tolerance inside it (`canalGrow`) — were both parameter-sporadic, which is why the reading is a sweep and
 * not a case.
 */
class BlendCanalSweepTest {
    private val width = 40.0
    private val depth = 30.0
    private val height = 20.0

    private val rounds = listOf(4.0, 5.0, 6.0)
    private val balls = listOf(0.5, 1.0, 1.2, 1.5, 2.0, 2.5)

    @Test
    fun everyBallThatRollsAlongAMitreBuildsInEveryRouteAndEveryOrder() {
        var built = 0
        var refused = 0
        val rolls = HashMap<Pair<Double, Double>, MutableSet<Boolean>>()
        for (bigR in rounds) {
            val routes = listOf("one entry" to blockRounded(bigR, sequential = false), "stacked" to blockRounded(bigR, sequential = true))
            val volumes = routes.map { (what, b) -> volumeOf(b.rounded, "rounds $bigR, $what") }
            assertTrue(
                abs(volumes[0] - volumes[1]) <= 1e-9 * volumes[0],
                "the four rounds at $bigR give one body in both routes: ${volumes[0]} against ${volumes[1]}",
            )
            for ((route, block) in routes) {
                // **the addresses are the body's own**: a mitre is an edge of *this* body, so each route
                // reads its own, which is what makes the two routes comparable at all
                val mitres = mitresOf(Evaluator().solid(block.rounded))
                assertEquals(4, mitres.size, "four rounds meet in four mitres ($route)")
                val sets =
                    listOf(
                        Triple("one mitre", listOf(mitres[0]), false),
                        Triple("two mitres sharing a band", adjacentPair(Evaluator().solid(block.rounded), mitres), false),
                        Triple("four mitres in one gesture", mitres, false),
                        Triple("four mitres one gesture at a time", mitres, true),
                    )
                for ((what, addresses, sequential) in sets) {
                    for (rc in balls) {
                        val cell = "R=$bigR rounds=$route canal($what) rc=$rc"
                        val (body, why) = canalled(block, addresses, rc, sequential)
                        if (body == null) {
                            val reason = assertNotNull(why, "a cell that does not build has a reason: $cell")
                            assertTrue(namesSomething(reason), "$cell is refused without naming anything: $reason")
                            refused++
                            rolls.getOrPut(bigR to rc) { HashSet() }.add(false)
                            println("$cell | refused | — | ${reason.take(80)}")
                            continue
                        }
                        assertManifold(body.mesh, cell)
                        // **the tool itself is a solid**, which is the half of this that no reading of the
                        // body can see: a loft whose cap is triangulated into slivers is a mesh the engine
                        // declines by status code, and a status code is not a sentence (session 84)
                        for (m in addresses) {
                            val choice = assertNotNull(Blend3.choicesFor(Evaluator().solid(block.rounded), listOf(m), BlendSection(BlendKind.FILLET, rc)).first, "$cell scores mitre $m")[0]
                            val tool = assertNotNull(Blend3.canalToolMesh(Evaluator().solid(block.rounded).feature, m, BlendSection(BlendKind.FILLET, rc), choice), "$cell: mitre $m has a tool")
                            assertManifold(tool, "$cell: the tool along mitre $m")
                        }
                        val took = volumeOf(block.rounded, cell) - Geom3.volume(body.mesh)
                        val (lo, hi) = bracketOf(block, addresses, rc, sequential)
                        assertTrue(took in lo..hi, "$cell takes $took, outside the figure [$lo, $hi] the algebra states")
                        val faces = assertNotNull(Section3.faces(body.feature).first, "$cell names its faces")
                        for (m in addresses) {
                            val band = assertNotNull(faces.firstOrNull { it.name == FaceName.BlendBand(m, 0) }, "$cell: the canal along mitre $m is a face")
                            assertNotNull(band.reason, "$cell: …which is no plane and says so")
                        }
                        built++
                        rolls.getOrPut(bigR to rc) { HashSet() }.add(true)
                        println("$cell | built | $took | [$lo, $hi]")
                    }
                }
                // …and all four mitres in one gesture, or one gesture at a time, is one and the same body
                for (rc in balls) {
                    val (one, _) = canalled(block, mitres, rc, false)
                    val (seq, _) = canalled(block, mitres, rc, true)
                    assertTrue((one == null) == (seq == null), "R=$bigR $route rc=$rc: the two canal routes disagree about whether the band can be had")
                    if (one == null || seq == null) continue
                    val v1 = Geom3.volume(one.mesh)
                    val v2 = Geom3.volume(seq.mesh)
                    assertTrue(abs(v1 - v2) <= 1e-9 * v1, "R=$bigR $route rc=$rc: four canals in one gesture and in four give $v1 against $v2")
                }
            }
        }
        // **whether a ball rolls is a property of the two sizes and of nothing else** — not of how many
        // mitres one gesture names, not of the order the gestures came in, and not of how the rounds under
        // it were made. Eight cells refuse and they are the eight readings of one and the same pair.
        for ((sizes, answers) in rolls) {
            assertEquals(1, answers.size, "R=${sizes.first} rc=${sizes.second} builds in some routes and refuses in others")
        }
        assertEquals(rounds.size * balls.size, rolls.size, "every pair of sizes is read")
        assertEquals(rounds.size * balls.size * 4 * 2, built + refused, "every cell of the sweep is read")
        println("== the canal sweep: ${built + refused} cells — $built built, $refused refused by name, 0 residue")
    }

    // ---- fixtures ----

    private class Block(val cx: Construction, val rounded: SolidRef)

    /** The block with its four top edges rounded at [bigR] — in one entry of one gesture, or one at a time. */
    private fun blockRounded(
        bigR: Double,
        sequential: Boolean,
    ): Block {
        val cx = Construction()
        val box = prism(cx, listOf(Vec2(0.0, 0.0), Vec2(width, 0.0), Vec2(width, depth), Vec2(0.0, depth)), height)
        val tops = topEdges(Evaluator().solid(box))
        assertEquals(4, tops.size, "a block has four top edges")
        val sec = BlendSection(BlendKind.FILLET, bigR)
        if (!sequential) {
            val choices = assertNotNull(Blend3.choicesFor(Evaluator().solid(box), tops, sec).first, "the four top edges are scored")
            return Block(cx, cx.blendAll(box, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(bigR.mm), null, tops, choices))))
        }
        var on = box
        for (e in tops) {
            val choices = assertNotNull(Blend3.choicesFor(Evaluator().solid(on), listOf(e), sec).first, "top edge $e is scored")
            on = cx.blendAll(on, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(bigR.mm), null, listOf(e), choices)))
        }
        return Block(cx, on)
    }

    /** [addresses] rounded at [rc] — one gesture with every address, or one gesture each — or the reason there is none. */
    private fun canalled(
        block: Block,
        addresses: List<Int>,
        rc: Double,
        sequential: Boolean,
    ): Pair<Solid3?, String?> {
        val cx = block.cx
        val sec = BlendSection(BlendKind.FILLET, rc)
        var on = block.rounded
        for (step in if (sequential) addresses.map { listOf(it) } else listOf(addresses)) {
            val (choices, why) = Blend3.choicesFor(Evaluator().solid(on), step, sec)
            if (choices == null) return null to (why?.render() ?: "")
            on = cx.blendAll(on, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(rc.mm), null, step, choices)))
            val r = Evaluator().eval(on.node)
            if (r is EvalResult.Invalid) return null to r.reason
        }
        return Evaluator().solid(on) to null
    }

    /** The figure the algebra states for the whole set, summed the way each gesture cuts it. */
    private fun bracketOf(
        block: Block,
        addresses: List<Int>,
        rc: Double,
        sequential: Boolean,
    ): Pair<Double, Double> {
        val cx = block.cx
        val sec = BlendSection(BlendKind.FILLET, rc)
        var lo = 0.0
        var hi = 0.0
        var on = block.rounded
        for (step in if (sequential) addresses.map { listOf(it) } else listOf(addresses)) {
            val base = Evaluator().solid(on)
            val choices = assertNotNull(Blend3.choicesFor(base, step, sec).first, "the figure's own scoring")
            for ((k, m) in step.withIndex()) {
                val (l, h) = assertNotNull(Blend3.canalRemoval(base.feature, m, sec, choices[k]), "the algebra states the figure of mitre $m")
                lo += l
                hi += h
            }
            on = cx.blendAll(on, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(rc.mm), null, step, choices)))
        }
        return lo to hi
    }

    private fun mitresOf(s: Solid3): List<Int> {
        val es = assertNotNull(Section3.edges(s.feature).first, "it names its edges")
        return es.indices.filter { es[it].name is EdgeName.BlendMitre && es[it].reason == null && es[it].geom is EdgeGeom.OnPlane }
    }

    /** Two mitres that **share a band** — the pair whose runs end on one and the same rounded face. */
    private fun adjacentPair(
        s: Solid3,
        mitres: List<Int>,
    ): List<Int> {
        val es = assertNotNull(Section3.edges(s.feature).first, "it names its edges")

        fun walls(i: Int) = setOf(es[i].between.a, es[i].between.b)
        for (i in mitres.indices) {
            for (j in i + 1 until mitres.size) {
                if (walls(mitres[i]).intersect(walls(mitres[j])).isNotEmpty()) return listOf(mitres[i], mitres[j])
            }
        }
        return listOf(mitres[0], mitres[1])
    }

    private fun topEdges(s: Solid3): List<Int> {
        val es = assertNotNull(Section3.edges(s.feature).first, "it names its edges")
        return es.indices.filter { i ->
            val path = Blend3.edgePath(es[i]).first ?: return@filter false
            val a = path.start ?: return@filter false
            val b = path.end ?: return@filter false
            abs(a.z - height) < 1e-9 && abs(b.z - height) < 1e-9
        }
    }

    private fun namesSomething(reason: String): Boolean =
        reason.contains("#") || reason.contains("face") || reason.contains("edge") || reason.contains("crease") || reason.contains("canal")

    private fun volumeOf(
        ref: SolidRef,
        what: String,
    ): Double {
        val ev = Evaluator()
        val r = ev.eval(ref.node)
        assertTrue(r is EvalResult.Ok, "$what: ${(r as? EvalResult.Invalid)?.reason}")
        val mesh = ev.solid(ref).mesh
        assertManifold(mesh, what)
        return Geom3.volume(mesh)
    }

    private fun prism(
        cx: Construction,
        xy: List<Vec2>,
        h: Double,
    ): SolidRef {
        val pts = xy.mapIndexed { i, p -> cx.freePoint("p$i", p.x.mm, p.y.mm) }
        val segs = xy.indices.map { cx.segment(pts[it], pts[(it + 1) % xy.size]) }
        return cx.extrude(cx.sketchOn(cx.planeXY(), cx.region(cx.loop(*segs.toTypedArray()))), cx.const(h.mm))
    }
}
