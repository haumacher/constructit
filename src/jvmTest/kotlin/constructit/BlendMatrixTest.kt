package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.Curve3Element
import constructit.geom.EdgeName
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Plane3
import constructit.geom.Revolve3
import constructit.geom.Section3
import constructit.geom.Vec3
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan
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
     * matching their inverse assertion, this test fails, and the entry is deleted with the fix. **The list
     * is empty**, and has been since OP-31's item 3 (session 83): item 2 closed the mixed-sign pair and the
     * incongruent inside corner, and item 3 closed the last two — a rail is now stated over its crease's own
     * run with the corner curves beside it, and a stacking order no longer decides whether a body builds.
     * The mechanism stays here because it is the honest way to name the next class that turns up.
     */
    enum class Residue(
        val item: String,
        val what: String,
    )

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
    @Suppress("UNUSED_PARAMETER")
    private fun residueOf(
        b: Body,
        entries: List<Rounding>,
    ): Residue? {
        // **No class of *cell* is in the residue any more** (session 83): the mixed-sign pair is built and
        // the incongruent inside corner is refused by name, so both of the classes this used to sort into
        // now reach one of the two legitimate states on their own. The two entries that stand are neither a
        // volume nor a refusal — one is what the **edge list** says about a rail and the other is which
        // gesture order builds at all — and each is asserted in its own test. The machinery stays, unused,
        // because it is how the next class is named before anyone codes against it.
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
        fixture: Fixture = L,
    ) {
        val on = fixture.block
        val res = residueOf(on, entries)
        val (stages, why) = fixture.run(entries, route, together)
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
     * **inside-corner pivots** where the plan turns reflex, and the four **mixed-sign** pairs where the
     * concave fill runs out into a band's end — the class script 1 exposes, built since session 83 as the
     * one-ended pivot about that band.
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
     * At an **inside** corner the same rule leaves the two ends not touching at all — no ring shared and no
     * overlap either — and that pair is **refused by name** (session 83). A **mixed-sign** pair asks nothing
     * of the two sizes: its ball turns on the circle `r + r_U`, which every pair of sizes has.
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
     * **Every one of the seventy-two agrees**, since OP-31's item 3 (session 83). Two of them did not: a
     * fillet on edges 6 and 11, and one on 0 and 12, built in one dressing and stacked one way round and
     * were refused by name the other way, because the second pass re-cut the band the first had already
     * taken off and the two coincident cylinders folded. A band already off the body now contributes its
     * corner ring as a **cap** and no tube at all ([Blend3.toolMesh]), so there is nothing left to coincide
     * and *which gesture arrived last* decides nothing (OP-30's own sentence).
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
                val disagree = volumes.any { it.second == null } || volumes.any { kotlin.math.abs(it.second!! - volumes[0].second!!) > 1e-5 * L.baseVolume }
                assertTrue(
                    !disagree,
                    "the gesture order decided the body of e$a and e$b (${tag(k)}): $volumes — " +
                        "if that is a class rather than a case, name it in the residue",
                )
                agreed++
                println("order(e$a,e$b,${tag(k)}) | built | ${volumes[0].second} | the same in all three routes")
            }
        }
        assertEquals(36 * 2, agreed, "seventy-two pair-and-kind cells, every one of them the same body in all three routes")
        println("== gesture order: ${36 * 2} cells — $agreed agree in all three routes, none in the residue")
    }

    /**
     * The **tangent rails** a dressing appended, by name — never *"everything after the base's own edges"*.
     *
     * Since OP-31's slice 5b that tail also holds the corner curves and every band's own free-end **notch**
     * curve, which are creases of the body and addresses of their own; the two classes below are about the
     * rails and say so.
     */
    private fun railsOf(dressed: Body): List<Int> =
        (L.block.count until dressed.count).filter { dressed.edges[it].name is EdgeName.BlendRail }

    // ---- 4b. the sector: a circular crease among straight ones (OP-31, slice 5e) ----

    private val S = Sector()

    /**
     * **The sector class** — a 90° pie slice of a 30 mm disc, 20 mm deep, whose nine edges hold the drawing's
     * first **circular** crease beside straight ones (OP-31, slice 5e).
     *
     * Enumerated exactly as the L-block's are: every single edge in both kinds, then every pair that shares a
     * vertex in both kinds, at one size. What it covers that no other fixture does: a band along an arc
     * (Pappus, not `w·L`), the **convex** corner where that arc hands over to a radius edge — no mitre there,
     * since the surface equidistant from a straight edge and a curved one is a curved medial one, so the two
     * tools overlap and the boolean trims them — the **apex** where two straight edges meet at the sector's
     * own angle, which is the crossing it always was, and the three-band vertices where an upright joins an
     * arc, which refuse by name because the ball standing still there is stated only between planes.
     *
     * The rule is the matrix's own and is not relaxed for it: built inside a bracket the algebra derives, or
     * refused by name.
     */
    @Test
    fun theSectorsOwnEdgesAndPairs() {
        val mine = (0 until S.block.count).filter { straightLegged(it) }
        assertEquals(7, mine.size, "seven of the nine: the two uprights standing on the cylinder are not in the class")
        for (k in kinds) {
            for (i in mine) cell("sector-single(e$i,${tag(k)}2)", listOf(Rounding(i, k, 2.0)), Route.ONE_PASS, fixture = S)
        }
        val pairs = S.block.pairs.filter { (a, b, _) -> a in mine && b in mine }
        for ((a, b, _) in pairs) {
            for (k in kinds) {
                cell("sector-pair(e$a,e$b,${tag(k)}2)", listOf(Rounding(a, k, 2.0), Rounding(b, k, 2.0)), Route.ONE_PASS, fixture = S)
            }
        }
        tally("the sector")
        assertEquals((mine.size + pairs.size) * 2, built + refused + residual, "every edge of the class and every pair of them, both kinds")
        assertEquals(0, residual, "no cell of the sector is in the residue")
    }

    /**
     * Whether edge [i] of the sector has a wedge with **two straight legs**, which is what the algebra above
     * states a figure for.
     *
     * A *circular* crease coaxial with the cylinder beside it has one: its normal section is the meridian
     * plane, in which a cylinder cuts its own **ruling** ([Blend3]'s `creaseOf` proves exactly this). A
     * *straight* one standing on the cylinder — the sector's two rim uprights — does not: its normal section
     * cuts the cylinder in a **circle**, so the wedge is bounded by an arc of radius 30 rather than by a line
     * and is a fifth of a square millimetre wider per millimetre of run than `wedgeArea` says. The figure for
     * that wedge is a closed form and is not written here; the two cells are out of the class rather than
     * bracketed loosely, and the omission is recorded under OP-31's fitted tier.
     */
    private fun straightLegged(i: Int): Boolean =
        S.block.arc(i) != null ||
            listOf(S.block.edges[i].between.a, S.block.edges[i].between.b).all { n ->
                S.block.faces.firstOrNull { it.name == n }?.plane != null
            }

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
            for (rail in railsOf(dressed)) {
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
            for (rail in railsOf(dressed)) {
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
     * This was `RAIL_IS_NOT_A_CHAIN`, and since OP-31's item 3 (session 83) it is the class that closes it.
     * Every rail is stated over the **crease's own run** — 28.75 mm where the edge is 32.75 and the pivot
     * takes the last 4 — none of them begins inside material, and the corner's own curves are in the list
     * beside them: the rail that carries a band's tangency round the pivot, leg by leg. A fillet along such
     * a rail is then bracketed by the band figure at the rail's own 135° wedge over that run.
     */
    @Test
    fun theRailOfABevelThatTurnsAPivotRunsTheCreaseAndCarriesOnRoundIt() {
        val c = 4.0
        val entries = listOf(2, 13, 14).map { Rounding(it, BlendKind.CHAMFER, c) }
        val (stages, why) = L.run(entries, Route.ONE_PASS)
        val dressed = Body(Evaluator().solid(assertNotNull(stages, why).last()))
        // eighteen base edges, two rails per rounding, and the pivot's own three legs of corner rail
        // eighteen base edges; then one **block per entry** (OP-31, slice 5b): two rails and two free-end
        // notch slots each, whose counts are a function of the entry alone so that no address re-packs when
        // a rounding is added to the dressing or taken off it; then the pivot's three legs of corner rail,
        // which two entries make together and which are therefore listed after every block
        assertEquals(L.block.count + 3 * (2 + 2) + 3, dressed.count, "the base's edges, the three blocks, and the corner's own curves")
        val cornerRails = dressed.edges.indices.filter { dressed.edges[it].name is EdgeName.BlendCornerRail }
        assertEquals(3, cornerRails.size, "a bevelled pivot walks turn, slide, turn: $cornerRails")

        // **the rails of edge 13's bevel run the crease, not the edge**
        val rails =
            (L.block.count until dressed.count).filter {
                dressed.edges[it].name.let { n -> n is EdgeName.BlendRail && n.edge == 13 }
            }
        assertEquals(2, rails.size, "the two rails of edge 13's bevel: $rails")
        for (rail in rails) {
            assertClose(dressed.length(rail), L.block.length(13) - c, 1e-9, "rail $rail runs the crease's own ${L.block.length(13) - c} mm")
        }

        // **and nothing is buried**: no stated crease of this body begins inside material
        val buried = (L.block.count until dressed.count).filter { dressed.straight(it) && buriedEndOf(dressed, it) != null }
        assertEquals(emptyList(), buried, "a stated crease that begins inside the solid is no crease of it")

        // and a fillet along such a rail is the band over that run, at the rail's own wedge
        val on = Rounding(rails.first(), BlendKind.FILLET, c)
        val (out, whyOut) = L.run(entries + on, Route.STACKED)
        val v = measure(assertNotNull(out, whyOut).last(), "a fillet on the rail of a bevel that turns a pivot")
        val bracket = assertNotNull(predict(dressed, listOf(on)), "the band over the rail's own run")
        assertTrue(v in bracket, "the fillet on rail ${rails.first()} built $v, outside $bracket")
        println("script2 class | built | $v | $bracket over the crease's own ${L.block.length(13) - c} mm")
    }

    // ---- 5c. the corner curves: every curve a corner puts on the body, rounded ----

    /**
     * **Every corner curve the matrix's own bodies list, rounded at 1 mm** (OP-31, slice 5b).
     *
     * Item 3 put the curves a corner adds into the edge list — the **mitre** across a crossing, the walk's
     * **corner rail** along it — and slice 5b added the third, the **notch** a band's free end leaves in the
     * face its cap stands in. All three are addresses a user can pick, so all three enter the matrix under
     * its own rule: built inside a bracket the algebra derives, or refused by name. The third state, built
     * and silently wrong, fails the build here exactly as it does everywhere else.
     *
     * *The figures, and each is structural rather than measured* (OP-21):
     *
     * - a **notch** curve stands at a right angle by construction — the cap plane is square to the crease and
     *   the band's own surface runs along it — so `θ = π/2` whatever the band is;
     * - a **corner rail** of a bevel stands in `3π/4`: the walk carries the bevel's own 45° slope round the
     *   turn, and a bevel halves the right angle the block's creases are (a *round*'s corner rail is a
     *   tangent hand-over and refuses, which is the answer rather than a gap);
     * - a **mitre** lies between two faces the drawing states, so its dihedral is read off them.
     *
     * And the shape decides the figure: a straight run is the band over its own length, an arc is the section
     * **revolved** — Pappus over the turn, bracketed by containment between the exact section at the nearest
     * radius it reaches and the chorded one at the farthest.
     *
     * *Sampled, and it says so*: the pairs are taken at one size and one kind pairing each (both rounds and
     * both bevels), the triples likewise, which is every **shape** of corner the L-block has without
     * enumerating the sizes again — the sizes are the pair classes' own business above.
     */
    @Test
    fun everyCornerCurveOfEveryBuiltPairAndTripleRoundsOrRefusesByName() {
        var built = 0
        var refused = 0
        var cells = 0
        val size = 1.0
        val groups = ArrayList<List<Int>>()
        for ((a, b, _) in L.block.pairs) groups.add(listOf(a, b))
        for ((_, es) in L.block.vertices) groups.add(es.sorted())
        for (group in groups) {
            for (kind in listOf(BlendKind.FILLET, BlendKind.CHAMFER)) {
                val entries = group.map { Rounding(it, kind, 4.0) }
                val (stages, _) = L.run(entries, Route.ONE_PASS)
                val on = stages?.lastOrNull() ?: continue
                val dressed = Body(Evaluator().solid(on))
                for (i in dressed.edges.indices) {
                    val e = dressed.edges[i]
                    if (e.reason != null) continue
                    if (e.name !is EdgeName.BlendMitre && e.name !is EdgeName.BlendCornerRail && e.name !is EdgeName.BlendNotch) continue
                    cells++
                    val what = "curve(${group.joinToString("+") { "e$it" }} ${tag(kind)} -> e$i ${e.name::class.simpleName})"
                    val (out, why) = stackedOn(on, i, size)
                    if (out == null) {
                        assertTrue(namesSomething(why ?: ""), "$what refused without naming anything: '$why'")
                        refused++
                        continue
                    }
                    val v = measure(out, what)
                    val bracket = assertNotNull(cornerCurveBracket(dressed, i, size), "$what has no bracket")
                    assertTrue(v in bracket, "$what built $v, outside its own bracket $bracket")
                    built++
                }
            }
        }
        println("== corner curves: $cells cells — $built built inside their own bracket, $refused refused by name")
        assertEquals(cells, built + refused, "every corner-curve cell is built inside a bracket or refused by name")
        assertTrue(built > 0 && refused > 0, "both states are exercised: $built built, $refused refused")
    }

    /** One rounding of [address] standing on the body [on] already is — the chain, not another entry. */
    private fun stackedOn(
        on: SolidRef,
        address: Int,
        size: Double,
    ): Pair<SolidRef?, String?> {
        val body = Evaluator().solid(on)
        val (choices, why) = Blend3.choicesFor(body, listOf(address), BlendSection(BlendKind.FILLET, size))
        if (choices == null) return null to (why?.render() ?: "no choice")
        val ref =
            L.cx.blendAll(
                on,
                L.cx.planeXY(),
                listOf(Construction.BlendRun(BlendKind.FILLET, L.cx.const(size.mm), null, listOf(address), choices)),
            )
        val r = Evaluator().eval(ref.node)
        if (r is EvalResult.Invalid) return null to r.reason
        return ref to null
    }

    /**
     * The bracket a rounding along corner curve [at] of [dressed] is held to — or null where the algebra
     * states none, which is a cell the class may not silently pass.
     */
    private fun cornerCurveBracket(
        dressed: Body,
        at: Int,
        size: Double,
    ): Bracket? {
        val e = dressed.edges[at]
        // **a crease with no rigid section is a canal band** (OP-31, slice 5f), and its own figure is the
        // quadrature the algebra states for one: `∫ A(s)(1 − κx̄) ds` along the spine, bracketed by the
        // loft's own chord terms and by the walls' own tessellation. It is asked of the construction because
        // the section changes along the run — there is no one wedge to carry.
        if (Blend3.edgePath(e).first == null) {
            val sec = BlendSection(BlendKind.FILLET, size)
            val choice = Blend3.choicesFor(dressed.solid, listOf(at), sec).first?.firstOrNull() ?: return null
            val (lo, hi) = Blend3.canalRemoval(dressed.solid.feature, at, sec, choice) ?: return null
            val sign = if (choice.convex) 1.0 else -1.0
            val a = dressed.volume - sign * hi
            val b = dressed.volume - sign * lo
            val slack = 1e-5 * dressed.volume
            return Bracket(kotlin.math.min(a, b) - slack, kotlin.math.max(a, b) + slack)
        }
        val theta =
            when (e.name) {
                is EdgeName.BlendNotch -> PI / 2.0
                is EdgeName.BlendCornerRail -> 3.0 * PI / 4.0
                is EdgeName.BlendMitre -> dressed.wedgeAngle(at) ?: return null
                else -> return null
            }
        val kind = BlendKind.FILLET
        val exact = Figures.wedgeArea(size, kind, theta)
        val chorded = Figures.wedgeAreaByChords(size, kind, theta)
        val noise = 1e-5 * dressed.volume
        val el = Blend3.edgePath(e).first?.elements?.singleOrNull() ?: return null
        val (lo, hi) =
            when (el) {
                is Curve3Element.Seg3 -> {
                    val len = (el.end - el.start).length()
                    if (len <= 1e-9) return null
                    exact * len to chorded * len + Figures.chordSurplus(size, len)
                }
                is Curve3Element.Arc3 -> {
                    val phi = abs(el.sweepAngle)
                    if (phi <= 1e-9 || el.radius <= 1e-9) return null
                    // the section reaches `setback` off the crease either way, so containment brackets the
                    // centroid's own radius between the two — never fitted, never widened to admit a body
                    val setback = size / tan(theta / 2.0)
                    val near = max(1e-9, el.radius - setback)
                    exact * phi * near to chorded * phi * (el.radius + setback)
                }
                else -> return null
            }
        // …and which **way** it moves the body is the crease's own sign, scored the way a gesture scores it:
        // a corner curve at a reflex corner of the plan is concave, and a rounding of it is a **fill** that
        // adds its wedge rather than taking it (the free end of a band that stands proud of the face it ends
        // in, which is every notch at an inside corner of the block's own plan)
        val sign = if (dressed.convex(at) == false) -1.0 else 1.0
        val a = dressed.volume - sign * hi
        val b = dressed.volume - sign * lo
        return Bracket(kotlin.math.min(a, b) - noise, kotlin.math.max(a, b) + noise)
    }

    // ---- 5b. the face list: a level section through every corner the matrix builds ----

    /**
     * **Every corner the matrix builds must state its faces, and a level section through its own height must
     * close** (OP-31, item 3b).
     *
     * The consumer's question rather than the volume's: a band whose patch is still drawn over the whole of
     * its crease where a corner has taken part of it away puts a piece in the section that the body does not
     * have, and the loop cannot close — which is what a face space, a section input and the panel's own
     * readout all meet. Two heights per cell, taken at the corner's **own** setbacks rather than at a round
     * number: half the rounding's size below the shared face, and half-way down the upright. The exact
     * boundary — the plane through the very station a corner ends a band at — is left out on purpose: a
     * plane through a vertex of the body is a degenerate cut and a different question (`z = 16` on the
     * reporter's own corner refuses by name and `z = 16.1` closes).
     *
     * **Sampled**: the thirty-six pairs at one size in **one dressing**, both kinds — seventy-two cells
     * rather than the pair class's full 288, because the stacked route and the gesture orders reach the same
     * body (`theGestureOrderDoesNotDecideTheBody`) and this asks about the *drawing* of that body, not about
     * how it was reached.
     */
    @Test
    fun everyBuiltCornerStatesItsFacesAndSectionsThroughItsOwnHeight() {
        var stated = 0
        var closed = 0
        var cuts = 0
        var refused = 0
        val open = ArrayList<String>()
        for ((a, b, at) in L.block.pairs) {
            for (k in kinds) {
                val name = "faces(e$a,e$b,${tag(k)})"
                val (stages, why) = L.run(listOf(Rounding(a, k, 4.0), Rounding(b, k, 4.0)), Route.ONE_PASS)
                if (stages == null) {
                    assertTrue(namesSomething(why ?: ""), "$name was refused without naming anything: '$why'")
                    refused++
                    continue
                }
                val solid = Evaluator().solid(stages.last())
                val faces = assertNotNull(Section3.faces(solid.feature).first, "$name names its faces")
                for (f in faces) {
                    val reason = f.reason?.render() ?: ""
                    assertTrue(
                        "Exception" !in reason && "kotlin." !in reason && "java." !in reason,
                        "$name: ${f.name.label.render()} carries a fault where a reason should be: '$reason'",
                    )
                }
                stated++
                // the corner's own two heights, each a *setback* off the shared vertex rather than a round
                // number, and each nudged off the exact station where a band ends (a plane through a vertex
                // of the body is a degenerate cut, not a defect of the drawing)
                for (z in listOf(at.z + (if (at.z < L.height / 2.0) 2.1 else -2.1), at.z + (if (at.z < L.height / 2.0) 4.1 else -4.1))) {
                    if (z <= 0.01 || z >= L.height - 0.01) continue
                    val cut = Plane3(Vec3(0.0, 0.0, z), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))
                    val (regions, whyCut) = Section3.regionsOf(solid.feature, cut)
                    cuts++
                    if (regions == null) {
                        assertTrue(namesSomething(whyCut?.render() ?: ""), "$name: the section at z = $z refuses without naming anything")
                        open.add("$name z=$z")
                    } else {
                        assertTrue(regions.isNotEmpty(), "$name: …into at least one area at z = $z")
                        closed++
                    }
                }
            }
        }
        assertEquals(36 * 2, stated + refused, "thirty-six pairs, two kinds")
        assertEquals(72, stated, "every pair states its faces, with no fault standing where a reason should")
        // **And there is no residue left here** (OP-31, slice 5d). Item 3b left 36 of the 144 cuts open and
        // pinned the number so that closing one would be looked at; this is that look. The three causes were
        // one composition each and none of them was the one item 3b named: a sampled run of a band's cut
        // ended at the last **sample** the plane crossed rather than at the station where the band's own
        // ruling ends, a turning leg's run dropped the very station where it hands over to the leg beside it,
        // and a free end's cap standing **past** the corner of the face it belongs to was refused by the
        // demand that a splice meet its neighbours on their own spans. All 144 close, and the number is
        // pinned the other way round now: one that stops closing fails this test.
        assertEquals(144, cuts, "two heights per built cell")
        assertEquals(144, closed, "every cut through a built corner closes: $open")
        println("== faces at a corner: ${stated + refused} cells — $stated state their faces, $closed of $cuts cuts close, ${open.size} refuse by name")
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
     * **Script 1, the reporter's own file — the one-ended pivot, built** (OP-31 item (2), session 83).
     *
     * A top edge and the concave upright it ends at, both filleted by the one parameter `r`, which OP-30
     * joins into one dressing. The band rounds away the very face the fill is tangent to over the last `r_U`
     * of the fill's run, so the two never overlap and there is nothing for a boolean to trim: the fill turns
     * about the band instead, on the circle of radius `r + r_U`, and the third face at the vertex caps the
     * walk.
     *
     * **The volume alone cannot tell this body from the ledge**, and that is a fact about the arithmetic
     * rather than a weakness of the test: the corner *adds* `runOutAdds = 16.747 mm³` and *shortens* the
     * fill's own run by the band's size, which gives `w·r_U = 13.734 mm³` back — a net of `3.013 mm³` on a
     * body whose two arcs' own chord slop over 32.75 + 20 mm of run is wider than that. So the naive bracket
     * and this one **overlap**, and the two statements that carry the weight are made where no tolerance can
     * reach them:
     *
     * - the **net against superposition** — the same two roundings measured *alone* and added — where every
     *   chord term cancels because it is the same tessellation on both sides, and what is left is the
     *   corner's own closed form;
     * - the **corner itself**, a ring torus of centre radius `r + r_U` and tube `r`, which the naive body
     *   has no face of at all, and which is [statesACorner]'s own positive control.
     */
    @Test
    fun theReportersFirstScriptBuildsTheOneEndedPivot() {
        val r = 4.0
        val band = Rounding(13, BlendKind.FILLET, r)
        val fill = Rounding(2, BlendKind.FILLET, r)
        val v = measure(refOf(bodyOf(script1)), "script 1")
        val bracket = assertNotNull(predict(L.block, listOf(band, fill)), "script 1's own bracket")
        assertTrue(v in bracket, "script 1 built $v, outside the one-ended pivot's bracket $bracket")

        // **superposition**: the two roundings alone, added, is the naive body to the last chord — so the
        // difference is the corner and nothing else, with no chord term left in it
        val alone =
            listOf(band, fill).sumOf { e ->
                val (only, why) = L.run(listOf(e), Route.ONE_PASS)
                measure(assertNotNull(only, why).last(), "e${e.edge} alone") - L.baseVolume
            }
        val net = v - (L.baseVolume + alone)
        val owed = Figures.runOutAdds(r, BlendKind.FILLET, r, BlendKind.FILLET) - Figures.wedgeArea(r, BlendKind.FILLET) * r
        val give = Figures.runOutSlack(r, BlendKind.FILLET, r, BlendKind.FILLET) + 1e-5 * L.baseVolume
        assertTrue(abs(net - owed) <= give, "the corner's own net is $net where the algebra says $owed (± $give)")
        assertTrue(net > give, "…and it is not nothing: the ledge's own net is zero")

        // **the corner itself**, which the naive body has no face of
        val corner =
            assertNotNull(
                Section3
                    .faces(Evaluator().solid(refOf(bodyOf(script1))).feature)
                    .first
                    ?.mapNotNull { it.surface?.band as? Revolve3.Band.Torus }
                    ?.firstOrNull(),
                "script 1 names the ring torus it turns on",
            )
        assertClose(corner.rc, 2.0 * r, 1e-9, "its centre circle is r + r_U")
        assertClose(corner.minor, r, 1e-9, "…and its tube is the fill's own size")
        assertTrue(statesACorner(Evaluator().solid(refOf(bodyOf(script1)))), "…and it is stated as a corner of the body")

        val naive = assertNotNull(naive(L.block, listOf(band, fill)), "the naive figure the ledge used to build")
        println("script 1 | built | $v | $bracket, a net of $net against the algebra's $owed (naive $naive overlaps)")
    }

    /**
     * **Script 2, the reporter's own file.** Three bevels making a pivot corner, then a fillet on one bevel's
     * rail. It builds, it is watertight, and since OP-31's item 3 the rail it rounds runs the crease's own
     * 28.75 mm rather than the edge's 32.75 — which is what
     * [theRailOfABevelThatTurnsAPivotRunsTheCreaseAndCarriesOnRoundIt] states as a class.
     */
    @Test
    fun theReportersSecondScriptBuildsOverTheCreasesOwnRun() {
        val v = measure(refOf(bodyOf(script2)), "script 2")
        val entries = listOf(2, 13, 14).map { Rounding(it, BlendKind.CHAMFER, 4.0) }
        val (stages, why) = L.run(entries, Route.ONE_PASS)
        val dressed = Body(Evaluator().solid(assertNotNull(stages, why).last()))
        val rail =
            (L.block.count until dressed.count).first {
                dressed.edges[it].name.let { n -> n is EdgeName.BlendRail && n.edge == 13 } && dressed.convex(it) == true
            }
        // **the pick takes the whole ribbon** (OP-31, item 3): rail → the pivot's own three legs of corner
        // rail → the next band's rail, one tangent-continuous chain, exactly as one pick has taken a
        // tangent-continuous rim since GitHub #29. So the figure is the band's own wedge at the rail's
        // 135° dihedral carried along the **chain's** total run, and the ribbon being tangent throughout is
        // what says there is no corner term to add: a constant section swept along a smooth path.
        val run = assertNotNull(Blend3.targets(dressed.solid.feature, false, rail, Blend3.chainRun()).first, "the chain through rail $rail")
        assertTrue(run.size > 1, "a bevel's rail carries on round the pivot: $run")
        val total = run.sumOf { runLength(dressed, it) }
        val band = Figures.wedgeArea(4.0, BlendKind.FILLET, 3.0 * PI / 4.0) * total
        val surplus = Figures.chordSurplus(4.0, total)
        val noise = 1e-5 * dressed.volume
        val bracket = Bracket(dressed.volume - band - surplus - noise, dressed.volume - band + noise)
        assertTrue(v in bracket, "script 2 built $v, outside the band over the ribbon's own $total mm $bracket")
        println("script 2 | built | $v | $bracket over the ribbon's ${run.size} pieces, $total mm")
    }

    /**
     * **Script 3, the reporter's own file** — a union and a subtract, and then a rounding of the result.
     *
     * *"This is especially hard, since the target object is the result of add and subtract — but this is the
     * only way to create the base solid of such structure."* Until OP-31's item (4) a general boolean's result
     * carried no faces and this cell was pinned as the mesh-only refusal; now the fused body names every face as
     * a piece of an operand's face and every crease as an exact line between two planes, so the matrix runs
     * its single-edge class over **all** of them: each crease of `e76`, filleted and chamfered at the reporter's
     * own 5 mm, is built inside the band's own figure at the crease's measured dihedral or refused by name.
     */
    @Test
    fun theReportersThirdScriptTakesARoundingOnEveryOneOfItsCreases() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(script3))
        val el = ed.doc.elements.last { e -> e.kind == ElementKind.SOLID }
        val on = Body(Evaluator().solid(refOf(el)))
        assertTrue(on.count >= 40, "the fused body names its creases: ${on.count}")
        val cx = ed.doc.cx
        val size = cx.const(5.0.mm)
        var cells = 0
        for (k in kinds) {
            for (i in 0 until on.count) {
                val name = "script3(e$i,${tag(k)}5)"
                cells++
                if (!on.straight(i)) {
                    assertTrue(on.edges[i].reason != null, "$name: a crease that is not a straight run says why")
                    refused++
                    continue
                }
                val (choices, whyChoice) = Blend3.choicesFor(on.solid, listOf(i), BlendSection(k, 5.0))
                if (choices == null) {
                    val reason = whyChoice?.render() ?: ""
                    assertTrue(namesSomething(reason), "$name was refused a choice without naming anything: '$reason'")
                    refused++
                    println("$name | refused | — | ${reason.take(70)}")
                    continue
                }
                val ref = cx.blendAll(refOf(el), cx.planeXY(), listOf(Construction.BlendRun(k, size, null, listOf(i), choices)))
                val r = Evaluator().eval(ref.node)
                if (r is EvalResult.Invalid) {
                    val reason = r.reason ?: ""
                    assertTrue(namesSomething(reason), "$name was refused without naming an edge or a face: '$reason'")
                    refused++
                    println("$name | refused | — | ${reason.take(70)}")
                    continue
                }
                val v = measure(ref, name)
                val theta = assertNotNull(on.wedgeAngle(i), "$name: two planes have a dihedral")
                val band = Figures.wedgeArea(5.0, k, theta) * on.length(i)
                val sign = if (assertNotNull(on.convex(i), "$name has a sign")) -1.0 else 1.0
                val surplus = (Figures.wedgeAreaByChords(5.0, k, theta) - Figures.wedgeArea(5.0, k, theta)) * on.length(i)
                val noise = 1e-6 * on.volume
                // **A free end in an oblique face.** The band closes on a cap square to its own crease (the free
                // end's notch, session 81); where the face the crease ends in is not square to it, the body
                // beyond that cap is left standing (a convex band) or the tool reaches past the face (a fill),
                // by at most the wedge's own section carried `reach·tan β` along the crease — bounded, not
                // fitted, and zero at a square end.
                val reach = if (k == BlendKind.CHAMFER) 5.0 else 5.0 * max(1.0, 1.0 / tan(theta / 2.0))
                val stub = on.ends(i).sumOf { end -> Figures.wedgeArea(5.0, k, theta) * reach * endObliquity(on, i, end) }
                val bracket =
                    if (sign < 0) {
                        Bracket(on.volume - band - surplus - noise, on.volume - band + stub + noise)
                    } else {
                        Bracket(on.volume + band - stub - noise, on.volume + band + surplus + stub + noise)
                    }
                assertTrue(v in bracket, "$name built $v, outside its own bracket $bracket (θ = $theta, L = ${on.length(i)}, stub ≤ $stub)")
                built++
                println("$name | built | $v | $bracket")
            }
        }
        assertEquals(2 * on.count, cells, "every crease, both kinds")
        tally("script 3, every crease")
    }

    // ---- plumbing ----

    /**
     * `tan β` for the face crease [i] ends in at [end], β being the angle between that face and the plane square
     * to the crease — `0` at a square end, larger the more the end face leans along the crease; capped where the
     * end face nearly contains the crease. The end face is the plane through [end] that is neither of the
     * crease's own two.
     */
    private fun endObliquity(
        b: Body,
        i: Int,
        end: Vec3,
    ): Double {
        val dir = (b.ends(i)[1] - b.ends(i)[0]).normalized()
        val own = setOf(b.edges[i].between.a, b.edges[i].between.b)
        return b.faces
            .filter { f -> f.name !in own && f.plane != null && abs(f.plane!!.distanceTo(end)) < 1e-6 }
            .maxOfOrNull { f ->
                val c = abs(f.plane!!.normal.normalized().dot(dir)).coerceIn(1e-3, 1.0)
                sqrt(1.0 - c * c) / c
            } ?: 0.0
    }

    // ---- 11. the bored block: every crease of a body a general boolean made ----

    /**
     * **The bored-block class** (OP-31, slice 5c): every crease of a body the *general boolean* made, rounded
     * and bevelled at 2 mm, under the matrix's own rule — built inside a bracket the algebra derives, or
     * refused by name.
     *
     * The fixture is the commonest mechanical body there is and the one GitHub #36's reporter asked for: a
     * 40 × 30 × 20 block with a 10 mm bore driven **across** its own extrusion axis, so the exact slab algebra
     * (OP-22) has no answer and the general engine runs. Its creases are of two kinds and the algebra states a
     * figure for each: the twelve straight ones are bands over their own length (`w·L`, the same figure every
     * cell of the L-block is bracketed by), and the two **circular** rims are revolutions — Pappus over the
     * wedge's own centroid radius, bracketed by containment between the exact section at the nearest radius
     * the chorded ring reaches and the chorded section at the farthest (slice 5b's own rule, one producer
     * further on).
     */
    @Test
    fun everyCreaseOfABoredBlockRoundsOrRefusesByName() {
        assumeTrue(constructit.geom.MeshBool.available, "no general boolean engine")
        built = 0
        refused = 0
        residual = 0
        val cx = Construction()
        val block = cx.extrude(cx.sketchOn(cx.planeXY(), boredPlan(cx)), cx.const(20.mm))
        val hole = cx.freePoint("bore.c", 15.mm, 10.mm)
        val drill =
            cx.extrude(
                cx.sketchOn(cx.plane(Vec3(-5.0, 0.0, 0.0), Vec3.Y, Vec3.Z), cx.region(cx.loop(cx.circleCR(hole, cx.const(5.mm))))),
                cx.const(50.mm),
            )
        val base = cx.subtract(block, drill)
        val body = Body(Evaluator().solid(base))
        assertManifold(body.mesh, "the bored block")
        assertEquals(14, body.count, "twelve straight creases and two rims")

        val size = 2.0
        for (i in 0 until body.count) {
            for (kind in kinds) {
                val name = "bored/e$i${tag(kind)}$size"
                val (choices, whyC) = Blend3.choicesFor(body.solid, listOf(i), BlendSection(kind, size))
                if (choices == null) {
                    val reason = whyC?.render() ?: ""
                    assertTrue(namesSomething(reason), "$name was refused without naming an edge or a face: '$reason'")
                    refused++
                    println("$name | refused | — | ${reason.take(70)}")
                    continue
                }
                val ref = cx.blend(base, base, cx.planeXY(), cx.const(size.mm), kind, false, i, choices)
                val r = Evaluator().eval(ref.node)
                if (r is EvalResult.Invalid) {
                    val reason = r.reason
                    assertTrue(namesSomething(reason), "$name was refused without naming an edge or a face: '$reason'")
                    refused++
                    println("$name | refused | — | ${reason.take(70)}")
                    continue
                }
                val took = body.volume - measure(ref, name)
                val bracket = assertNotNull(boredBracket(body, i, kind, size), "$name is bracketed by the algebra")
                assertTrue(took in bracket, "$name took $took, outside its own bracket $bracket")
                built++
                println("$name | built | $took | $bracket")
            }
        }
        tally("the bored block's own creases")
        assertEquals(28, built + refused, "fourteen creases, both kinds")
    }

    /** The bored block's plan: the 40 × 30 rectangle the drill goes through. */
    private fun boredPlan(cx: Construction): constructit.dsl.RegionRef {
        val xy = listOf(Vec3(0.0, 0.0, 0.0), Vec3(40.0, 0.0, 0.0), Vec3(40.0, 30.0, 0.0), Vec3(0.0, 30.0, 0.0))
        val pts = xy.mapIndexed { i, p -> cx.freePoint("bp$i", p.x.mm, p.y.mm) }
        return cx.region(cx.loop(*xy.indices.map { cx.segment(pts[it], pts[(it + 1) % xy.size]) }.toTypedArray()))
    }

    /**
     * What a rounding of crease [i] of the bored block takes, bracketed — a **band** over a straight run and
     * a **revolution** over a circular one.
     *
     * Both figures are read off the body rather than tabulated: the run's own length, the ring's own radius
     * and the wedge's own centroid, with the side the material stands on decided by asking the body
     * ([Geom3.encloses]) exactly as the engine's own sector is.
     */
    private fun boredBracket(
        b: Body,
        i: Int,
        kind: BlendKind,
        size: Double,
    ): Bracket? {
        // the general engine's own float32 noise, at the matrix's established rate: a part in a million of
        // the body it worked on, which is three orders below the chord term and is what a body assembled from
        // float32 vertices costs whatever is rounded on it
        val noise = 1e-6 * b.volume
        val geom = b.edges[i].geom
        if (geom is constructit.geom.EdgeGeom.Straight) {
            val l = (geom.b - geom.a).length()
            val w = Figures.wedgeArea(size, kind)
            val hi = Figures.wedgeAreaByChords(size, kind)
            return Bracket(w * l - noise, hi * l + noise)
        }
        val piece = (geom as? constructit.geom.EdgeGeom.OnPlane)?.piece as? constructit.geom.ProfileElement.CircleE ?: return null
        val plane = (geom as constructit.geom.EdgeGeom.OnPlane).plane
        val radius = piece.circle.radius
        val centre = plane.toWorld(piece.circle.center)
        // which way the material lies from the ring, radially — asked of the body, never assumed
        val at = plane.toWorld(piece.circle.center + constructit.geom.Vec2(radius, 0.0))
        val out = (at - centre).normalized()
        val n = plane.normal.normalized()
        val outward = if (Geom3.encloses(b.mesh, at + out * 0.2 - n * 0.2) || Geom3.encloses(b.mesh, at + out * 0.2 + n * 0.2)) 1.0 else -1.0
        val rho = radius + outward * Figures.centroidReach(size, kind)
        // the ring reaches the engine as a chord polygon, so it is bracketed by **containment**: the exact
        // section carried round the polygon's own nearest radius below, the chorded section round the ring's
        // own radius above. The inscription happens **twice** and the bound says so — the body's own ring is
        // a polygon inscribed in the bore's circle, and the tool's revolution is a polygon inscribed in that
        // — so each takes a factor `cos(π/n)` off the radius the material genuinely reaches.
        val steps = GeomMath.chordSteps(rho, 2.0 * PI, GeomMath.TESS_TOL_MM)
        val inscribed = kotlin.math.cos(PI / steps)
        val near = rho * inscribed * inscribed
        return Bracket(
            Figures.wedgeArea(size, kind) * 2.0 * PI * near - noise,
            Figures.wedgeAreaByChords(size, kind) * 2.0 * PI * rho + noise,
        )
    }

    /** How long edge [i] of [b] runs, whichever curve it is — the chain's pieces are arcs as often as not. */
    private fun runLength(
        b: Body,
        i: Int,
    ): Double {
        val path = Blend3.edgePath(b.edges[i]).first ?: return 0.0
        return path.elements.sumOf { e ->
            when (e) {
                is Curve3Element.Seg3 -> (e.end - e.start).length()
                is Curve3Element.Arc3 -> e.radius * abs(e.sweepAngle)
                else -> 0.0
            }
        }
    }

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
