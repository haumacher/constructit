package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.geom.BlendKind
import constructit.geom.Geom3
import constructit.geom.Section3
import constructit.geom.Vec3
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The rounding matrix** (OP-31 item 1, GitHub #36).
 *
 * > *"3D filleting still not works in all combinations … You can combine operations and stack them on each
 * > other … this works fine in some cases but creates nonsense in others … This cannot be fixed one-by-one …
 * > Please add a comprehensive test case with all possible combinations!"*
 *
 * So the matrix **is** the specification. It enumerates roundings over the reporter's own L-block — every
 * single edge, every pair that shares a vertex, every triple at a vertex, two sizes, both gesture orders,
 * one dressing and a stack, and a rounding on the rail an earlier one made — and demands of **every cell**
 * exactly one of two states:
 *
 * - **built**: the body is valid, [assertManifold] passes on the fine mesh, and its volume lies inside the
 *   closed-form bracket [predict] derives for that cell out of the figures in [Figures]; or
 * - **refused by name**: the node is `EvalResult.Invalid` with a reason that names an edge or a face.
 *
 * The third state — **built and silently wrong** — fails the test. That is the whole point.
 *
 * ### The residue
 *
 * At HEAD the matrix finds cells in that third state; they are the diagnosis of #36 and they are named in
 * [Residue] below, each with the OP-31 queue item that will close it. For a residue cell the test asserts the
 * **inverse**: that the cell is *still* wrong in exactly the stated way. So when a package fixes it the entry
 * goes stale and this test fails, telling whoever fixed it to delete the entry — the seeded-defect discipline
 * `TranslationReviewTest` runs over the translations, run here over the geometry.
 *
 * Nothing here loosens a bracket to admit a wrong body: every figure is written as an expression of the size,
 * the run and the angle, with the chord term as the upper margin, and where the algebra states no figure at
 * all the cell must be in the residue or the test fails for want of one.
 */
class BlendMatrixTest {
    /**
     * **What the matrix cannot yet build, named** — one entry per class, with the OP-31 queue item that
     * closes it and the measurement that says what wrong looks like today.
     *
     * A residue entry is a *claim that a defect is still there*. Fix the defect and the cells it covers stop
     * matching their inverse assertion, this test fails, and the entry is deleted with the fix.
     */
    enum class Residue(
        val item: String,
        val what: String,
    ) {
        /**
         * **A convex band running into the concave fill** — `Blend3.cornersOf` skips a pair whose two members
         * differ in sign, so the band runs the whole of its edge, the fill runs the whole of the upright, and
         * nothing is built where they meet: a ledge, refused by nobody. Eighty-nine cells of the matrix, and
         * the reporter's script 1 among them.
         *
         * Measured, one 4 mm fillet on edge 13 of the L-block and one on the concave upright, edge 2:
         * **40566.627 mm³** with the fill's gesture first and **40566.658 mm³** with the band's — script 1's
         * own figure — against the naive bracket **[40564.516, 40569.748] mm³**, which is
         * `40611.445 + 3.4336·20 − 3.4336·32.75` with the chords. The corner that is owed is the pivot about a
         * band with one end instead of two, and `Figures.pivotTakes(4, FILLET, π/2, 4)` is **26.393 mm³** of
         * it — five times the whole width of that bracket, so this is not a matter of tolerance.
         */
        MIXED_SIGN_PAIR(
            "(2) the mixed-sign pair — a fill meeting a band's end is the pivot about a band with one end",
            "no corner is built between a convex band and the concave fill, and none is refused",
        ),

        /**
         * **An inside corner whose two roundings are not congruent** — two kinds, or two sizes. `ringsAgree`
         * fails, so `cornersOf` leaves the pair alone; at a *convex* corner that is right (the two tools
         * overlap and the boolean trims what is doubled), but at an **inside** corner the two bands do not
         * overlap at all and the face's sharp corner stands between their two ends: GitHub #31's own spike,
         * back again whenever the two roundings differ. *Blend edge with a profile*'s own help says two edges
         * given two different profiles "cannot share a corner **and say so**"; nothing says so.
         * Twenty-four cells of the matrix.
         *
         * Measured, a 4 mm fillet on edge 13 and a 3 mm fillet on edge 14: **40362.201 mm³** against the naive
         * bracket **[40361.438, 40369.229] mm³** — no corner, and no refusal either. A 4 mm fillet on edge 13
         * and a 4 mm *bevel* on edge 14 is the same class: **39957.193 mm³** against **[39956.670, 39960.226]**.
         */
        INCONGRUENT_INSIDE_CORNER(
            "(2) the mixed-sign pair, and with it the congruence rule at an inside corner",
            "an inside corner between two roundings that are not congruent is neither built nor refused",
        ),

        /**
         * **A rail is stated as one straight piece where the body's crease is a chain** — the reporter's
         * script 2. Bevel two cap edges that meet at the plan's inside corner and bevel the upright between
         * them: the body itself is right (its volume is the pivot-about-a-bevel figure to a part in 10⁵, which
         * [everyTripleAtAVertexInEveryOrder] brackets), and the **edge list** is where it goes wrong. Each
         * bevel's two rails are stated at the whole length of the edge they round, although the band they
         * bound is set back by the bevel's own setback where it turns the pivot: with `c = 4 mm` the rails of
         * edge 13's bevel are stated **32.75 mm** long where the crease runs **28.75 mm**, and the two that
         * run along a side face are buried inside the fill over exactly those 4 mm. No mitre crease between
         * the two bevels is listed at all — the dressed list is the base's eighteen and two rails per
         * rounding, and nothing else.
         *
         * A fillet along such a rail therefore runs 4 mm past the crease: **39959.770 mm³**. The volume alone
         * cannot tell the two apart — a band over the crease's own 28.75 mm brackets to
         * **[39959.664, 39962.873] mm³**, which the chord margin makes wide enough to contain it — so the
         * residue is asserted on the rail's stated **run**, and on the buried 4 mm, and not on a figure.
         */
        RAIL_IS_NOT_A_CHAIN(
            "(3) edges are chains — rail → corner curve → rail as one edge, and the mitre crease named",
            "a rail is stated at the whole length of its edge though its band is set back at a pivot",
        ),

        /**
         * **The gesture order decides whether the body builds at all** — for two of the L-block's thirty-six
         * pairs, in the **stacked** route only, and in one of the two orders only.
         *
         * A rounding of edge 6 and a rounding of edge 11 (the two bottom-cap edges at plan corner 0) build in
         * one dressing, and build stacked as 11-then-6; stacked as **6-then-11** the second pass folds the
         * surface back on itself — `MeshCanon.flap` catches it and the body is refused by name, *"boundary
         * edge #6 of the bottom face: a zero-thickness flap …"*, at a point beside plan corner **1**, which is
         * the far end of edge 6 and not the corner the two roundings share at all. Edges 0 and 12 at the same
         * corner are the same case the other way up, and so are the two triples at those two vertices.
         *
         * That is a legitimate state by the matrix's own rule — refused by name, not built and wrong — so it
         * is not what fails a cell. It is named here because *"which gesture arrived last"* may not decide
         * what a body is (OP-30's own sentence, and `BlendMixedVertexTest.theTwoOrdersBuildTheSameBody`
         * asserts it for the mixed vertex), and because it is the shape of the reporter's *"works fine in
         * some cases but creates nonsense in others"*.
         */
        ORDER_DECIDES_THE_BODY(
            "(3) edges are chains — the stacked pass rebuilds the chain, and the rebuild is where the flap appears",
            "one stacking order refuses a body that the other order and the one dressing both build",
        ),
    }

    // ---- the runner ----

    private val L = LBlock()

    private var built = 0
    private var refused = 0
    private var residual = 0

    /**
     * Which residue class [entries] falls in on [b], or null where the algebra owes a figure.
     *
     * Classified **structurally** — by what meets what and with which sign — never by the cell's name, so a
     * class covers every gesture order and every route by construction.
     */
    private fun residueOf(
        b: Body,
        entries: List<Rounding>,
    ): Residue? {
        val byEdge = entries.associateBy { it.edge }
        if (byEdge.size != entries.size) return null
        for ((at, es) in b.vertices) {
            val here = es.filter { it in byEdge }.map { byEdge.getValue(it) }
            if (here.size != 2) continue
            val convex = here.count { b.convex(it.edge) == true }
            if (convex == 1) return Residue.MIXED_SIGN_PAIR
            if (convex != 2) continue
            val theta = b.sharedAngle(here[0].edge, here[1].edge, at) ?: continue
            val congruent = here[0].kind == here[1].kind && here[0].size == here[1].size
            if (theta > PI && !congruent) return Residue.INCONGRUENT_INSIDE_CORNER
        }
        return null
    }

    /**
     * **One cell of the matrix**: round [entries] on the block by [route] and demand one of the two states,
     * or — where the cell is in the [Residue] — demand that it is still in the third one.
     *
     * Prints the row `cell | state | volume | bracket`, so the run's own log is the coverage table.
     */
    private fun cell(
        name: String,
        entries: List<Rounding>,
        route: Route,
        together: Boolean = false,
    ) {
        val on = L.block
        val res = residueOf(on, entries)
        val (stages, why) = L.run(entries, route, together)
        if (stages == null) {
            val reason = why ?: ""
            assertTrue(namesSomething(reason), "$name was refused without naming an edge or a face: '$reason'")
            assertTrue(
                res == null,
                "$name is in the residue as ${res?.name} but is now refused by name — that state is legitimate, " +
                    "so delete the residue entry: $reason",
            )
            refused++
            println("$name | refused | — | ${reason.take(70)}")
            return
        }
        val ref = stages.last()
        val v = measure(ref, name)
        if (res != null) {
            // the **inverse** assertion: the cell must still be wrong in exactly the way the entry says
            val naive = assertNotNull(naive(on, entries), "$name: the naive figure is stateable")
            assertTrue(
                v in naive,
                "$name is in the residue as ${res.name} (${res.what}) and no longer builds the naive figure " +
                    "$naive — it built $v. If that is a fix, delete the residue entry; if not, it is a new defect.",
            )
            assertTrue(
                !statesACorner(Evaluator().solid(ref)),
                "$name is in the residue as ${res.name} and now states a rounded corner of its own — " +
                    "delete the residue entry, its queue item ${res.item} is done",
            )
            residual++
            println("$name | RESIDUE ${res.name} | $v | naive $naive")
            return
        }
        val bracket =
            assertNotNull(
                predict(on, entries),
                "$name is neither bracketed by the algebra nor named in the residue — one of the two it must be",
            )
        assertTrue(v in bracket, "$name built $v, outside its own bracket $bracket")
        built++
        println("$name | built | $v | $bracket")
    }

    private fun tally(what: String) {
        println("== $what: ${built + refused + residual} cells — $built built, $refused refused by name, $residual residue")
    }

    private val kinds = listOf(BlendKind.FILLET, BlendKind.CHAMFER)

    private fun tag(k: BlendKind) = if (k == BlendKind.FILLET) "f" else "c"

    /**
     * **The fixture is the reporter's own extrusion**, and that is asserted rather than assumed: the block the
     * matrix rounds is built through the DSL for the sake of enumerating addresses, so it must be the very body
     * script 1 loads — the same volume, the same eighteen edges in the same order, each with the same two ends
     * and the same sign. Nothing below means anything if this drifts.
     */
    @Test
    fun theFixtureIsTheReportersOwnExtrusion() {
        val loaded = Body(Evaluator().solid(refOf(bodyOf(script1.lines().takeWhile { !it.startsWith("param \"r\"") }.joinToString("\n") + "\n"))))
        assertClose(L.baseVolume, loaded.volume, 1e-9, "the L-block is the reporter's own")
        assertEquals(loaded.count, L.block.count, "eighteen edges")
        for (i in 0 until loaded.count) {
            assertEquals(loaded.edges[i].name, L.block.edges[i].name, "edge $i is the same edge")
            for ((k, p) in loaded.ends(i).withIndex()) assertClose((p - L.block.ends(i)[k]).length(), 0.0, 1e-9, "edge $i end $k")
            assertEquals(loaded.convex(i), L.block.convex(i), "edge $i has the same sign")
        }
        // the two the whole matrix turns on: the concave upright and the two cap edges that end at it
        assertEquals(false, L.block.convex(2), "edge 2 is the concave upright at the plan's inside corner")
        assertEquals(listOf(2, 13, 14), L.block.vertices.first { at -> at.second.contains(2) && at.first.z > 0.0 }.second.sorted(), "the mixed vertex")
    }

    // ---- 1. every single edge, both kinds ----

    /**
     * **Thirty-six cells**: each of the L-block's eighteen edges, filleted and chamfered at 4 mm. The figure
     * is the band alone — `w·L` taken at a convex crease, *added* at the concave one — bracketed below by the
     * exact wedge and above by the chords the arc reaches the engine as.
     */
    @Test
    fun everySingleEdgeBothKinds() {
        for (k in kinds) {
            for (i in 0 until L.block.count) cell("single(e$i,${tag(k)}4)", listOf(Rounding(i, k, 4.0)), Route.ONE_PASS)
        }
        assertEquals(36, built + refused + residual, "eighteen edges, two kinds")
        tally("single edges")
    }

    // ---- 2. every pair that shares a vertex ----

    /**
     * **Every pair of edges that share a vertex, in one dressing** — both kinds each way round (fillet/fillet,
     * chamfer/chamfer and the two mixed), and in **both gesture orders**, which is what OP-30 makes of two
     * consecutive gestures whose sizes are the drawing's own parameter.
     *
     * Thirty-six pairs cover every class the block has: convex crossings on a cap and on a side face, the two
     * **inside-corner pivots** where the plan turns reflex, and the four **mixed-sign** pairs where a convex
     * band runs into the concave fill — the class script 1 exposes.
     */
    @Test
    fun everyPairAtAVertexInOneDressing() {
        for ((a, b, _) in L.block.pairs) {
            for (ka in kinds) {
                for (kb in kinds) {
                    cell("pair(e$a${tag(ka)},e$b${tag(kb)})", listOf(Rounding(a, ka, 4.0), Rounding(b, kb, 4.0)), Route.ONE_PASS)
                    cell("pair(e$b${tag(kb)},e$a${tag(ka)})", listOf(Rounding(b, kb, 4.0), Rounding(a, ka, 4.0)), Route.ONE_PASS)
                }
            }
        }
        assertEquals(36 * 4 * 2, built + refused + residual, "thirty-six pairs, four kind pairings, both orders")
        tally("pairs, one dressing")
    }

    /** The same pairs **stacked** — each rounding on the body the one before it made, which is OP-30's chain. */
    @Test
    fun everyPairAtAVertexStacked() {
        for ((a, b, _) in L.block.pairs) {
            for (ka in kinds) {
                for (kb in kinds) {
                    cell("stack(e$a${tag(ka)},e$b${tag(kb)})", listOf(Rounding(a, ka, 4.0), Rounding(b, kb, 4.0)), Route.STACKED)
                    cell("stack(e$b${tag(kb)},e$a${tag(ka)})", listOf(Rounding(b, kb, 4.0), Rounding(a, ka, 4.0)), Route.STACKED)
                }
            }
        }
        assertEquals(36 * 4 * 2, built + refused + residual, "the same, stacked")
        tally("pairs, stacked")
    }

    /** And the same pairs as **one gesture** — one `BlendRun` over both edges, which is what a face pick is. */
    @Test
    fun everyPairAtAVertexAsOneGesture() {
        for ((a, b, _) in L.block.pairs) {
            for (k in kinds) {
                cell("one(e$a,e$b,${tag(k)})", listOf(Rounding(a, k, 4.0), Rounding(b, k, 4.0)), Route.ONE_PASS, together = true)
            }
        }
        assertEquals(36 * 2, built + refused + residual, "thirty-six pairs, two kinds, one gesture each")
        tally("pairs, one gesture")
    }

    // ---- 3. every triple at a vertex, every order ----

    /**
     * **All three edges at each of the twelve vertices, in all six orders and in both routes** — the convex
     * three-vertex ball at ten of them, and at the two where the plan turns reflex the pivot about a band:
     * two convex cap bands set back by the fill's own reach, turning about whatever stands at the upright.
     */
    @Test
    fun everyTripleAtAVertexInEveryOrder() {
        for ((_, es) in L.block.vertices) {
            for (k in kinds) {
                for (order in permutations(es)) {
                    val entries = order.map { Rounding(it, k, 4.0) }
                    val name = "triple(${order.joinToString(",") { "e$it" }},${tag(k)})"
                    cell(name, entries, Route.ONE_PASS)
                    cell("$name.stacked", entries, Route.STACKED)
                }
            }
        }
        assertEquals(12 * 2 * 6 * 2, built + refused + residual, "twelve vertices, two kinds, six orders, two routes")
        // **the positive control for [statesACorner]**, which every residue cell's inverse assertion leans on:
        // a body that *does* build a corner must say so, or "no corner is stated" would pass by being unable
        // to fail. The mixed vertex all three rounded is such a body — the pivot about a band.
        val (three, why) = L.run(listOf(2, 13, 14).map { Rounding(it, BlendKind.FILLET, 4.0) }, Route.ONE_PASS)
        assertTrue(
            statesACorner(Evaluator().solid(assertNotNull(three, why).last())),
            "a body that builds a corner names it, so a residue cell that stops being wrong cannot pass unnoticed",
        )
        tally("triples")
    }

    // ---- 4. two sizes ----

    /**
     * **Two sizes on the pairs.** By the congruence rule no corner is built between roundings that do not end
     * on one ring, so a convex pair is simply two tools overlapping and trimmed by the boolean; what the
     * overlap is, is not closed form, and what brackets it is *containment* — a round of the smaller size sits
     * inside both tools, a bevel of the larger contains both, and the two congruent crossings of those bound
     * it (see [predict]). That is a wide bracket and it is stated as wide on purpose.
     *
     * At an **inside** corner the same rule leaves the two ends not touching at all, which is the residue
     * entry [Residue.INCONGRUENT_INSIDE_CORNER].
     */
    @Test
    fun twoSizesOnEveryPair() {
        for ((a, b, _) in L.block.pairs) {
            for (k in kinds) {
                cell("sizes(e$a${tag(k)}4,e$b${tag(k)}3)", listOf(Rounding(a, k, 4.0), Rounding(b, k, 3.0)), Route.ONE_PASS)
                cell("sizes(e$b${tag(k)}4,e$a${tag(k)}3)", listOf(Rounding(b, k, 4.0), Rounding(a, k, 3.0)), Route.ONE_PASS)
            }
        }
        assertEquals(36 * 2 * 2, built + refused + residual, "thirty-six pairs, two kinds, both ways round")
        tally("two sizes")
    }

    /**
     * **The gesture order may not decide what the body is.** For every pair that shares a vertex, at one size
     * and one kind, the one dressing and the two stacked orders must all reach the same body — the corner is a
     * fact about which bands meet where, not about which gesture arrived last (OP-30).
     *
     * Two pairs still disagree, and they are [Residue.ORDER_DECIDES_THE_BODY]: named here rather than
     * discovered, so that fixing them fails this test and retires the entry.
     */
    @Test
    fun theGestureOrderDoesNotDecideTheBody() {
        var agreed = 0
        for ((a, b, _) in L.block.pairs) {
            for (k in kinds) {
                val one = Rounding(a, k, 4.0)
                val two = Rounding(b, k, 4.0)
                val routes =
                    listOf(
                        "one dressing" to L.run(listOf(one, two), Route.ONE_PASS),
                        "stacked $a then $b" to L.run(listOf(one, two), Route.STACKED),
                        "stacked $b then $a" to L.run(listOf(two, one), Route.STACKED),
                    )
                val volumes = routes.map { (what, r) -> what to r.first?.let { measure(it.last(), "$what of e$a/e$b") } }
                val listed = setOf(a, b) in orderDecides && k == BlendKind.FILLET
                val disagree = volumes.any { it.second == null } || volumes.any { kotlin.math.abs(it.second!! - volumes[0].second!!) > 1e-5 * L.baseVolume }
                if (listed) {
                    assertTrue(
                        disagree,
                        "RESIDUE ${Residue.ORDER_DECIDES_THE_BODY.name}: e$a and e$b now agree in every order — " +
                            "delete the residue entry (${Residue.ORDER_DECIDES_THE_BODY.item})",
                    )
                    println("order(e$a,e$b,${tag(k)}) | RESIDUE ${Residue.ORDER_DECIDES_THE_BODY.name} | ${volumes.map { it.second }} |")
                } else {
                    assertTrue(
                        !disagree,
                        "the gesture order decided the body of e$a and e$b (${tag(k)}): $volumes — " +
                            "if that is a class rather than a case, name it in the residue",
                    )
                    agreed++
                    println("order(e$a,e$b,${tag(k)}) | built | ${volumes[0].second} | the same in all three routes")
                }
            }
        }
        assertEquals(36 * 2 - orderDecides.size, agreed, "seventy-two pair-and-kind cells, two of them in the residue")
        println("== gesture order: ${36 * 2} cells — $agreed agree in all three routes, ${orderDecides.size} in the residue")
    }

    /**
     * The pairs whose **stacked** order still decides whether the body builds — [Residue.ORDER_DECIDES_THE_BODY].
     * An explicit list, because there is nothing structural about them: both are ordinary right-angled convex
     * corners of the block, and their thirty-four siblings agree in every order.
     */
    private val orderDecides = setOf(setOf(6, 11), setOf(0, 12))

    // ---- 5. stacked roundings: a rounding on a rail ----

    /**
     * **A fillet on the rail of a bevel whose free ends stand on nothing else** — the simplest stacked case,
     * and the one that must work. Each of the eighteen edges bevelled at 4 mm, then each of the two rails
     * that bevel appends to the edge list filleted at 1 mm and at 4 mm: seventy-two cells, each bracketed by
     * the band figure at the rail's own dihedral (`3π/4`, since a bevel halves a right angle) over the rail's
     * own run, on the **measured** volume of the bevelled body.
     */
    @Test
    fun aFilletOnTheRailOfABevel() {
        var cells = 0
        for (i in 0 until L.block.count) {
            val bevel = Rounding(i, BlendKind.CHAMFER, 4.0)
            val (first, whyFirst) = L.run(listOf(bevel), Route.STACKED)
            val dressed = Body(Evaluator().solid(assertNotNull(first, whyFirst).last()))
            for (rail in L.block.count until dressed.count) {
                for (size in listOf(1.0, 4.0)) {
                    val on = Rounding(rail, BlendKind.FILLET, size)
                    val name = "rail(e$i c4 -> e$rail f$size)"
                    val (stages, why) = L.run(listOf(bevel, on), Route.STACKED)
                    cells++
                    if (stages == null) {
                        assertTrue(namesSomething(why ?: ""), "$name refused without naming anything: '$why'")
                        println("$name | refused | — | ${why?.take(70)}")
                        continue
                    }
                    val v = measure(stages.last(), name)
                    val bracket = assertNotNull(predict(dressed, listOf(on)), "$name has no bracket")
                    assertTrue(v in bracket, "$name built $v, outside its own bracket $bracket")
                    println("$name | built | $v | $bracket")
                }
            }
        }
        assertEquals(72, cells, "eighteen bevels, two rails each, two sizes")
        println("== a fillet on a bevel's rail: $cells cells")
    }

    /**
     * **A fillet on the rail of a fillet is refused, by name** — and it must be: a round's rail is where the
     * band runs *tangent* onto the face, so there is no crease there to break. Thirty-six cells, all refused.
     */
    @Test
    fun aFilletOnTheRailOfAFilletIsRefusedByName() {
        var cells = 0
        for (i in 0 until L.block.count) {
            val round = Rounding(i, BlendKind.FILLET, 4.0)
            val (first, whyFirst) = L.run(listOf(round), Route.STACKED)
            val dressed = Body(Evaluator().solid(assertNotNull(first, whyFirst).last()))
            for (rail in L.block.count until dressed.count) {
                val name = "rail(e$i f4 -> e$rail f1)"
                val (stages, why) = L.run(listOf(round, Rounding(rail, BlendKind.FILLET, 1.0)), Route.STACKED)
                cells++
                assertTrue(stages == null, "$name built a rounding of a tangent rail, where there is no crease")
                assertTrue(namesSomething(why ?: ""), "$name refused without naming anything: '$why'")
                assertTrue("tangent" in (why ?: ""), "$name: the refusal says why — '$why'")
                println("$name | refused | — | ${why?.take(70)}")
            }
        }
        assertEquals(36, cells, "eighteen rounds, two rails each")
        println("== a fillet on a round's rail: $cells cells, all refused by name")
    }

    /**
     * **The reporter's second script, as a class**: two cap edges that meet at the plan's inside corner and
     * the upright between them, all three bevelled, then a fillet on one bevel's rail.
     *
     * The bevelled body is right — [everyTripleAtAVertexInEveryOrder] brackets it — and the rail is where it
     * goes wrong, which is [Residue.RAIL_IS_NOT_A_CHAIN]: the rail is stated at the whole length of the edge
     * it rounds, though the band it bounds is set back by the bevel's own setback at the pivot. Asserted as
     * the inverse, so building the chain retires the entry.
     */
    @Test
    fun theRailOfABevelThatTurnsAPivotIsStatedTooLong() {
        val c = 4.0
        val entries = listOf(2, 13, 14).map { Rounding(it, BlendKind.CHAMFER, c) }
        val (stages, why) = L.run(entries, Route.ONE_PASS)
        val dressed = Body(Evaluator().solid(assertNotNull(stages, why).last()))
        assertEquals(L.block.count + 6, dressed.count, "eighteen base edges and two rails per rounding")

        // the rails of the bevel on edge 13, which turns the pivot at the inside corner
        val rails = (L.block.count until dressed.count).filter { dressed.straight(it) && dressed.length(it) > 30.0 && dressed.length(it) < 40.0 }
        assertEquals(2, rails.size, "the two rails of edge 13's bevel: $rails")
        for (rail in rails) {
            assertClose(
                dressed.length(rail),
                L.block.length(13),
                1e-9,
                "RESIDUE ${Residue.RAIL_IS_NOT_A_CHAIN.name}: rail $rail is stated at the whole ${L.block.length(13)} mm " +
                    "of edge 13 — if it now runs the crease's own ${L.block.length(13) - c} mm, the chain is built and " +
                    "the residue entry must go (${Residue.RAIL_IS_NOT_A_CHAIN.item})",
            )
        }
        // …and the proof that the extra 4 mm is not there: a rail that runs along a **side** face runs, over
        // exactly the bevel's own setback at its pivot end, through material the fill put there — a stated
        // crease buried inside the solid. Asked of the body in every direction at once, so a point on any
        // face of it is never "buried" and only a run through the inside can say yes.
        val buried = (L.block.count until dressed.count).filter { dressed.straight(it) && buriedEndOf(dressed, it) != null }
        assertEquals(
            2,
            buried.size,
            "RESIDUE ${Residue.RAIL_IS_NOT_A_CHAIN.name}: two of the six rails run into the fill at the pivot — $buried do. " +
                "If none does, the rails are the crease's own run and the residue entry must go",
        )
        for (rail in buried) {
            val (at, along) = buriedEndOf(dressed, rail)!!
            assertTrue(deepInside(dressed, at + along * (c - 0.1)), "rail $rail is buried right up to the setback")
            assertTrue(!deepInside(dressed, at + along * (c + 0.1)), "…and no further: the buried run is the bevel's own $c mm")
        }

        // and the fillet on such a rail runs past the crease: it builds, and it builds the *stated* run
        val on = Rounding(rails.first(), BlendKind.FILLET, c)
        val (out, whyOut) = L.run(entries + on, Route.STACKED)
        val v = measure(assertNotNull(out, whyOut).last(), "a fillet on the rail of a bevel that turns a pivot")
        val stated = assertNotNull(predict(dressed, listOf(on)), "the band over the rail's stated run")
        assertTrue(
            v in stated,
            "RESIDUE ${Residue.RAIL_IS_NOT_A_CHAIN.name}: the fillet no longer runs the rail's stated length — $v vs $stated",
        )
        println("script2 class | RESIDUE ${Residue.RAIL_IS_NOT_A_CHAIN.name} | $v | over the stated run $stated")
    }

    // ---- 6. the reporter's three scripts, verbatim ----

    /** The reporter's script 1 (GitHub #36), verbatim. */
    private val script1 =
        """
constructit 6
orthostart -26.875,-32.375 -> e1
orthovertex -26.875,15.375 -> e2,e3
orthovertex 61.875,15.375 -> e4,e5
orthovertex 61.875,0.375 -> e6,e7
orthovertex -5.521648428788623,0.375 -> e8,e9
orthovertex -5.521648428788623,-32.375 -> e10,e11
orthoclose -> e12
param "h" = 20mm
tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
hide els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
show els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
param "r" = 4mm
tool filletedge els=e13 clicks=-31.252365457721638,11.31587462139538 scalar="r" signs=13;-1;1;0;1 -> e14,e15
tool filletedge els=e14 clicks=-15.659687663642714,14.554138077719443 scalar="r" signs=2;-1;1;0;-1 -> e16
""".trimStart()

    /** The reporter's script 2 (GitHub #36), verbatim. */
    private val script2 =
        """
constructit 6
orthostart -26.875,-32.375 -> e1
orthovertex -26.875,15.375 -> e2,e3
orthovertex 61.875,15.375 -> e4,e5
orthovertex 61.875,0.375 -> e6,e7
orthovertex -5.521648428788623,0.375 -> e8,e9
orthovertex -5.521648428788623,-32.375 -> e10,e11
orthoclose -> e12
param "h" = 20mm
tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
hide els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
show els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
param "r" = 4mm
tool chamferedge els=e13 clicks=-12.581664043342087,5.018353790754986 scalar="r" signs=2;-1;1;0;-1 -> e14,e15
tool chamferedge els=e14 clicks=-36.10499303384047,0.8875707537249014 scalar="r" signs=13;-1;1;0;1 -> e16
tool chamferedge els=e14 clicks=-6.480180051294639,24.048959979858537 scalar="r" signs=14;-1;1;0;1 -> e17
param "r2" = 4mm
tool filletedge els=e14 clicks=-33.91557367038956,-1.7134784580017737 scalar="r2" signs=20;-1;1;0;1 -> e18,e19
""".trimStart()

    /** The reporter's script 3 (GitHub #36), verbatim. */
    private val script3 =
        """
constructit 6
point -62.875,31.375 -> e1
point -30.875,85.625 -> e2
tool segment pts=e1,e2 clicks=-62.875,31.375;-30.875,85.625 -> e3
point 0,40 -> e4
tool segment pts=e2,e4 clicks=-30.625,85.625;-0.625,41.625 -> e5
point -13.125,-16.625 -> e6
point -2.875,10.375 -> e7
tool segment pts=e6,e7 clicks=-13.125,-16.625;-2.875,10.375 -> e8
point 23.875,-28.625 -> e9
tool segment pts=e7,e9 clicks=-1.875,11.625;23.875,-28.625 -> e10
orthostart 0,40 -> e11
weldortho e11 e4
orthovertex 55.375,40 -> e12,e13
orthovertex 55.375,7.625 -> e14,e15
orthovertex 42.625,7.625 -> e16,e17
orthovertex 42.625,-28.625 -> e18,e19
orthovertex 23.875,-28.625 -> e20,e21
weldortho e20 e9
orthostart -13.125,-16.625 -> e22
weldortho e22 e6
orthovertex -62.875,-16.625 -> e23,e24
orthovertex -62.875,31.375 -> e25,e26
weldortho e25 e1
tool outline els=e26,e3,e5,e13,e15,e17,e19,e21,e10,e8,e24 clicks=-64.375,8.125;-53.375,45.875;-15.4375,62.8125;27.6875,40;55.375,23.8125;49,7.625;42.625,-10.5;33.25,-28.625;10.5,-9.125;-8,-3.125;-38,-16.625 -> e27,e28,e29,e30,e31,e32,e33,e34,e35,e36,e37,e38
param "h" = 20mm
tool extrude els=e38 clicks=-62.875,1.125 scalar="h" -> e39
sketchspace "face1" el=e39 piece=12
sectioninput "face1" corner=5 -> e40
sectioninput "face1" corner=10 -> e41
tool segment pts=e40,e41 clicks=0.3197102721685636,39.87857769973661;-2.670280948200181,8.982001755926243 -> e42
sectioninput "face1" edge=9 -> e43
sectioninput "face1" edge=8 -> e44
sectioninput "face1" edge=7 -> e45
sectioninput "face1" edge=6 -> e46
sectioninput "face1" edge=5 -> e47
tool outline els=e42,e43,e44,e45,e46,e47 clicks=-1.341395961369628,22.270851624231778;-7.819710272168575,-3.310184372256374;-31.739640035118533,-16.931255487269546;-63.13454784899036,2.337576821773477;-52.83568920105357,48.18410886742757;-17.28801580333627,64.1307287093942 -> e48,e49,e50,e51,e52,e53,e54
point -37.221290605794564,16.290869183494287 -> e55
point 24.22200452172562,7.247008565104377 -> e56
param "h2" = 20.059973213497795mm
param "h3" = -10mm
tool extrudepoint pts=e55 els=e54 clicks=-62.968437225636535,7.320895522388052;-37.221290605794564,16.290869183494287 scalar="h2" signs=3;0 -> e57,e58
sectioninput "face1" edge=4 -> e59
sectioninput "face1" edge=3 -> e60
sectioninput "face1" edge=2 -> e61
sectioninput "face1" edge=1 -> e62
sectioninput "face1" edge=0 -> e63
sectioninput "face1" edge=10 -> e64
tool outline els=e59,e60,e61,e62,e63,e64,e42 clicks=22.57853380158033,39.87857769973661;55.966769095697984,22.935294117647054;49.156233538191394,7.819227392449509;42.34569798068481,-6.466286215978938;32.7112818261633,-28.558999122036887;11.449122036874448,-12.11404741000879;-1.8397278314310854,23.76584723441615 -> e65,e66,e67,e68,e69,e70,e71,e72
tool extrudepoint pts=e56 els=e72 clicks=13.608560140474097,-13.110711150131705;28.060184372256362,23.101404741000874 scalar="h3" signs=2;0 -> e73,e74
tool union els=e39,e58 clicks=-32.07562232552941,-20.815122882110373;-30.58466836505312,26.41198263967152 -> e75
tool subtract els=e75,e74 clicks=32.74583581902277,-32.13938581579633;28.083777267655293,-1.3892442991406568 -> e76
param "r" = 5mm
""".trimStart()

    private fun bodyOf(script: String): Element = DocumentFormat.load(script).elements.last { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun refOf(el: Element): SolidRef = el.ref as SolidRef

    /**
     * **Script 1, the reporter's own file.** A top edge and the concave upright it ends at, both filleted by
     * the one parameter `r`, which OP-30 joins into one dressing. It builds, it is watertight, and its volume
     * is the **naive** figure — band over the whole edge, fill over the whole upright, no corner — which is
     * [Residue.MIXED_SIGN_PAIR] and nothing else.
     */
    @Test
    fun theReportersFirstScriptBuildsTheNaiveFigure() {
        val v = measure(refOf(bodyOf(script1)), "script 1")
        val naive = assertNotNull(naive(L.block, listOf(Rounding(13, BlendKind.FILLET, 4.0), Rounding(2, BlendKind.FILLET, 4.0))))
        assertTrue(
            v in naive,
            "RESIDUE ${Residue.MIXED_SIGN_PAIR.name}: script 1 no longer builds the naive figure $naive — it built $v. " +
                "If the corner is built, delete the entry (${Residue.MIXED_SIGN_PAIR.item})",
        )
        assertTrue(
            !statesACorner(Evaluator().solid(refOf(bodyOf(script1)))),
            "RESIDUE ${Residue.MIXED_SIGN_PAIR.name}: script 1 now states a corner of its own — delete the entry",
        )
        // …and the corner it is owed is far bigger than the bracket, so this is not a matter of tolerance
        val owed = Figures.pivotTakes(4.0, BlendKind.FILLET, PI / 2.0, 4.0)
        assertTrue(owed > naive.hi - naive.lo, "the pivot owed ($owed mm³) is wider than the bracket itself")
        println("script 1 | RESIDUE ${Residue.MIXED_SIGN_PAIR.name} | $v | naive $naive, owed a pivot of $owed mm³")
    }

    /**
     * **Script 2, the reporter's own file.** Three bevels making a pivot corner, then a fillet on one bevel's
     * rail. It builds and it is watertight; what is wrong is the rail's own run, which
     * [theRailOfABevelThatTurnsAPivotIsStatedTooLong] states as [Residue.RAIL_IS_NOT_A_CHAIN].
     */
    @Test
    fun theReportersSecondScriptBuildsOverTheRailsStatedRun() {
        val v = measure(refOf(bodyOf(script2)), "script 2")
        val entries = listOf(2, 13, 14).map { Rounding(it, BlendKind.CHAMFER, 4.0) }
        val (stages, why) = L.run(entries, Route.ONE_PASS)
        val dressed = Body(Evaluator().solid(assertNotNull(stages, why).last()))
        val rail = (L.block.count until dressed.count).first { dressed.straight(it) && dressed.convex(it) == true && dressed.length(it) > 30.0 && dressed.length(it) < 40.0 }
        val bracket = assertNotNull(predict(dressed, listOf(Rounding(rail, BlendKind.FILLET, 4.0))), "the band over the rail's stated run")
        assertTrue(
            v in bracket,
            "RESIDUE ${Residue.RAIL_IS_NOT_A_CHAIN.name}: script 2 no longer builds the band over the rail's stated run — $v vs $bracket",
        )
        println("script 2 | RESIDUE ${Residue.RAIL_IS_NOT_A_CHAIN.name} | $v | over the stated run $bracket")
    }

    /**
     * **Script 3, the reporter's own file** — a union and a subtract, and then a rounding of the result.
     *
     * The script itself ends before the rounding, so the fixture is what the *next* gesture would meet: a
     * general boolean's result is a `Feature3.MeshBoolean` and carries no faces, so `Section3.edges` refuses
     * it by name, in these exact words. OP-9's own promise — *"Boolean results are analytic-preserving"* — is
     * what OP-31's item (d) makes good on; until it does, the refusal is the honest state and it is pinned
     * verbatim here so that making good on it fails this test.
     */
    @Test
    fun theReportersThirdScriptRefusesTheRoundingByName() {
        val body = Evaluator().solid(refOf(bodyOf(script3)))
        val (edges, why) = Section3.edges(body.feature)
        assertTrue(edges == null, "a general boolean's result carries no edge list — it handed back ${edges?.size}")
        assertEquals(
            "this solid is mesh-only (a general boolean's result, OP-9), so its section has no faces to name — " +
                "its curves draw as chords and cannot be used as construction inputs; build the geometry you want " +
                "to anchor on from the operands' own sketches instead",
            assertNotNull(why, "and it says why").render(),
            "the refusal script 3's next gesture meets, verbatim",
        )
        println("script 3 | refused | — | ${why.render().take(70)}")
    }

    // ---- plumbing ----

    /**
     * The end of rail [i] whose run starts **inside** the body, with the direction it runs in — or null where
     * neither end does. What it proves: a stated crease that begins buried in material is not a crease of this
     * body at all, which is [Residue.RAIL_IS_NOT_A_CHAIN] said about the geometry rather than about a number.
     */
    private fun buriedEndOf(
        b: Body,
        i: Int,
    ): Pair<Vec3, Vec3>? {
        val ends = b.ends(i)
        for (k in 0..1) {
            val at = ends[k]
            val along = (ends[1 - k] - at).normalized()
            if (deepInside(b, at + along * 0.1)) return at to along
        }
        return null
    }

    /** Whether [p] has material on **every** side of it — so it is inside the solid and on no face of it. */
    private fun deepInside(
        b: Body,
        p: Vec3,
    ): Boolean {
        val e = 0.05
        return listOf(Vec3(e, 0.0, 0.0), Vec3(-e, 0.0, 0.0), Vec3(0.0, e, 0.0), Vec3(0.0, -e, 0.0), Vec3(0.0, 0.0, e), Vec3(0.0, 0.0, -e))
            .all { Geom3.encloses(b.mesh, p + it) }
    }

    private fun <T> permutations(xs: List<T>): List<List<T>> =
        if (xs.size <= 1) listOf(xs) else xs.indices.flatMap { i -> permutations(xs.filterIndexed { j, _ -> j != i }).map { listOf(xs[i]) + it } }
}
