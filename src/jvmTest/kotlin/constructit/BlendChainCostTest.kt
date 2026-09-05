package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.ElementKind
import constructit.geom.Blend3
import constructit.geom.Geom3
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **What a chain of roundings costs** (GitHub #35, part 1) — asserted by *counting the work*, never by
 * reading a clock.
 *
 * The report: *"After adding a small number of 3D fillets, the UI becomes totally unresponsive — changing
 * the radius or moving a point takes tenth of seconds."* [SCRIPT] is the reporter's own file: an L-shaped
 * extrusion with seven roundings on it — four top edges, two convex uprights and one concave upright.
 * Measured on the JVM, one recompute after a radius change took about a second and the **per-level** cost
 * grew about fivefold along the chain, which is exponential in its depth. Two causes, and this test is the
 * regression on both:
 *
 * 1. **The dressed lists had no memo.** `Section3.faces(Blend)` derives from the base's list, and every
 *    piece of the chain under a level is prepared by reading the list of the level below it — so one ask at
 *    the tip re-derived every lower level's list once per target and once per corner, and `T(n) ≈ Σ T(k)`.
 *    The memo now lives on the feature instance itself ([constructit.geom.Feature3.Blend.dressedFaces]),
 *    which is exactly where a chain's identity already is: a level's `base` **is** the level below.
 * 2. **The rebuild rule was wider than its argument.** Session 81 rebuilt the whole chain from its
 *    undressed root for *"any corner that turns about an upright"*, so every level after the first mixed
 *    corner paid `O(n)` booleans. The argument only reaches the level where such a corner is **fresh** —
 *    where one of its ends or its upright is a rounding that gesture makes ([Blend3.blended]). A corner
 *    that was already built stays built, and a later rounding elsewhere applies its own group to the tip.
 *
 * A clock is deliberately not asserted (OP-15): the wall time is printed as information, and what the test
 * holds fixed is the number of derivations, the number of booleans, and every volume along the chain.
 */
class BlendChainCostTest {
    /*
     * The reporter's file, verbatim but for one closing `show` line naming every intermediate: since OP-30 a pure
     * chain of roundings loads as one dressed body with entries, and this test is about the **chain** — a step whose
     * result something else reads keeps its chain, so that one line is what keeps the seven levels seven.
     */

    /**
     * GitHub #35's attached file, verbatim. Seven `filletedge` steps on one extrusion: top edges 12, 13, 14
     * and 15, convex uprights 1 and 3, and the concave upright 2 — the one whose rounding re-turns a corner.
     */
    private val script =
        """
constructit 5
orthostart -26.875,-32.375 -> e1
orthovertex -26.875,15.375 -> e2,e3
orthovertex 41.999800864975384,15.375 -> e4,e5
orthovertex 41.999800864975384,-11.775083491926196 -> e6,e7
orthovertex -5.521648428788623,-11.775083491926196 -> e8,e9
orthovertex -5.521648428788623,-32.375 -> e10,e11
orthoclose -> e12
param "h" = 20mm
tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
hide els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
show els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
param "r" = 5mm
tool filletedge els=e13 clicks=-42.670739764447546,-4.867038301721209 scalar="r" signs=12;-1;1;0;1 -> e14
tool filletedge els=e14 clicks=-31.533048614089623,14.504582242265968 scalar="r" signs=13;-1;1;0;1 -> e15
tool filletedge els=e15 clicks=-15.209120508301623,-22.09480593584297 scalar="r" signs=1;-1;1;0;1 -> e16
tool filletedge els=e16 clicks=-1.6336097588108203,35.97358839564461 scalar="r" signs=14;-1;1;0;1 -> e17
tool filletedge els=e17 clicks=-11.301657028615722,8.858099327956722 scalar="r" signs=2;-1;1;0;-1 -> e18
tool filletedge els=e18 clicks=56.88568755568988,21.122250050431774 scalar="r" signs=3;-1;1;0;1 -> e19
tool filletedge els=e19 clicks=52.78762484641989,32.49678119098172 scalar="r" signs=15;-1;1;0;1 -> e20
show els=e14,e15,e16,e17,e18,e19
""".trimStart()

    /**
     * The volumes the chain had **before** this package, at `r = 6 mm`, in the order the solids stand in the
     * file: the undressed extrusion, then one per rounding.
     *
     * They are the proof that nothing moved. The memo changes no arithmetic at all; the tightened rebuild
     * rule changes the *order* two booleans run in at the last two levels, and the general engine's own
     * float32 noise is the whole of the difference there (a part in 1e11 on the last two, and bit-identical
     * on the first five).
     */
    private val before =
        listOf(
            46196.677070167460,
            46029.228926693710,
            45888.911544942566,
            45748.306652547160,
            45359.051418778080,
            45536.220158610490,
            45400.599278614010,
            45203.924848179915,
        )

    /**
     * One recompute of the tip after a radius change: **one dressed list derived per level that has one
     * asked of it, and one boolean per group per level plus the one rebuild.**
     *
     * *Why 13 derivations.* There are seven blend levels. Each one's node validates its **own** dressed face
     * list before handing the body on (`Construction.blend` — a body may not claim faces it cannot state),
     * which is 7; each one that another rounding stands on has its **edge** list read to resolve that
     * rounding's target, which is the six below the tip. The tip's own edge list is never asked, because
     * nothing is built on it yet. 7 + 6 = 13 — one derivation per list that is actually wanted, and the memo
     * is what makes "wanted once" and "derived once" the same number.
     *
     * *Why 8 booleans.* The chain's seven levels carry two groups: everything that meets round the top —
     * the four top-edge bands and the two convex uprights, all joined at shared corners — is one subtracted
     * group, and the concave upright's fill is a united group of its own. Levels 1-4 and 6-7 each apply
     * their one **fresh** group to the tip: 6 booleans. Level 5 is the one that rounds the concave upright,
     * which is where the corner that turns about it is **fresh**, so the chain is rebuilt from its undressed
     * root with both groups in dependency order: 2 booleans. 6 + 2 = 8. Under the old rule levels 6 and 7
     * rebuilt too, for 10 — and that count grows with the chain, which is the `O(n²)` this fixes.
     */
    @Test
    fun theReportersSevenRoundingsRecomputeInOnePassPerLevel() {
        val doc = DocumentFormat.load(script)
        val solids = doc.elements.filter { it.kind == ElementKind.SOLID }
        assertEquals(8, solids.size, "the extrusion and its seven roundings")
        val tip = solids.last().ref as SolidRef

        doc.setParameter(doc.scalars.single { it.name == "r" }, 6.0.mm)
        Blend3.resetDerivations()
        Geom3.resetCombines()
        val ev = Evaluator()
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        val res = ev.eval(tip.node)
        assertTrue(res is EvalResult.Ok, "the tip: ${(res as? EvalResult.Invalid)?.reason}")
        val v = Geom3.volume(ev.solid(tip).mesh)
        val ms = started.elapsedNow().inWholeMicroseconds / 1000.0

        assertEquals(13, Blend3.derivations, "one dressed list per level that has one asked of it (7 face, 6 edge)")
        assertEquals(8, Geom3.combines, "one boolean per fresh group per level, plus the one rebuild at level 5")
        assertClose(v / before.last(), 1.0, tol = 1e-9, msg = "the tip's volume is the volume it was: $v vs ${before.last()}")
        assertManifold(ev.solid(tip).mesh, "the reporter's seven roundings")
        // information, never an assertion: before this package the same recompute took about a second
        println("BlendChainCostTest: the reporter's chain recomputes in ${(ms * 10).toInt() / 10.0} ms")
    }

    /**
     * Every level's volume is the volume it was, the file is still a fixed point, and **the rebuild happens
     * exactly once, at the level that makes the mixed corner**.
     *
     * Walked one level at a time, each with its own fresh [Evaluator] and everything below it already in the
     * node memo, which is what a recompute after a radius change really costs the user level by level. The
     * boolean count per level reads `1 1 1 1 2 1 1`: one fresh group applied to the tip everywhere except
     * level 5, where rounding the concave upright first states the corner that turns about it and the chain
     * is rebuilt from its undressed root in two groups. Before this package the tail read `1 1 1 1 2 2 2`
     * and would have kept growing with the chain.
     */
    @Test
    fun everyLevelKeepsItsVolumeAndOnlyTheMixedCornerRebuilds() {
        val doc = DocumentFormat.load(script)
        // a version-5 file saves back under the current header, so the file is asserted as a fixed point
        val once = DocumentFormat.save(doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the reporter's file is a fixed point")
        val solids = doc.elements.filter { it.kind == ElementKind.SOLID }
        doc.setParameter(doc.scalars.single { it.name == "r" }, 6.0.mm)
        val combines = ArrayList<Int>()
        val derivations = ArrayList<Int>()
        val took = ArrayList<Double>()
        for ((k, el) in solids.withIndex()) {
            val ref = el.ref as SolidRef
            val ev = Evaluator()
            Blend3.resetDerivations()
            Geom3.resetCombines()
            val started = kotlin.time.TimeSource.Monotonic.markNow()
            val res = ev.eval(ref.node)
            assertTrue(res is EvalResult.Ok, "${el.id}: ${(res as? EvalResult.Invalid)?.reason}")
            val v = Geom3.volume(ev.solid(ref).mesh)
            took.add(started.elapsedNow().inWholeMicroseconds / 1000.0)
            combines.add(Geom3.combines)
            derivations.add(Blend3.derivations)
            assertClose(v / before[k], 1.0, tol = 1e-9, msg = "level $k (${el.id}) is unmoved: $v vs ${before[k]}")
        }
        // information, never an assertion (OP-15): the same walk before this package read
        // 3.1 1.4 1.9 5.6 14.1 38.0 147.2 760.9 ms — a fivefold step a level
        println("BlendChainCostTest: level by level ${took.map { (it * 10).toInt() / 10.0 }} ms")
        assertEquals(listOf(0, 1, 1, 1, 1, 2, 1, 1), combines, "one boolean a level, and the one rebuild at the concave upright")
        assertEquals(listOf(0, 1, 2, 2, 2, 2, 2, 2), derivations, "a level derives its own faces and the edges of the one below it")
    }

    /**
     * The memo is a **memo**, not a cache with a life of its own: the same document at two radii answers
     * with two different bodies, and the second ask derives its lists afresh because the second recompute
     * builds fresh features (OP-5 — the memo lives exactly as long as the value it belongs to).
     */
    @Test
    fun aSecondRadiusDerivesAfreshAndAnswersAfresh() {
        val doc = DocumentFormat.load(script)
        val tip = doc.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef
        val r = doc.scalars.single { it.name == "r" }

        doc.setParameter(r, 6.0.mm)
        val six = Geom3.volume(Evaluator().solid(tip).mesh)
        doc.setParameter(r, 4.0.mm)
        Blend3.resetDerivations()
        Geom3.resetCombines()
        val four = Geom3.volume(Evaluator().solid(tip).mesh)
        assertEquals(13, Blend3.derivations, "the new radius derives its own thirteen and no more")
        assertEquals(8, Geom3.combines, "and runs the same eight booleans")
        assertTrue(four > six, "a smaller fillet takes less material away: $four vs $six")
        // …and asking again for the very same radius derives nothing at all: the node memo holds the value
        Blend3.resetDerivations()
        Geom3.resetCombines()
        assertClose(Geom3.volume(Evaluator().solid(tip).mesh), four, tol = 1e-12, msg = "the same pass again")
        assertEquals(0, Blend3.derivations, "an unchanged drawing derives nothing")
        assertEquals(0, Geom3.combines, "and runs no boolean")
    }
}
