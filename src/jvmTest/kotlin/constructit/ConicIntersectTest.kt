package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.core.SourceNode
import constructit.dsl.Construction
import constructit.dsl.ellipse
import constructit.dsl.pointSet
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Circle
import constructit.geom.Conics
import constructit.geom.Ellipse
import constructit.geom.Line
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Conic intersections** (OP-24) — the genuinely new maths of the package, and the branch discipline that
 * makes four solutions storable.
 *
 * Line ∩ ellipse is a quadratic and keeps the ordinary two-branch `Select(sign)`, ordered along the line's
 * own direction exactly as line ∩ circle is. Circle ∩ ellipse and ellipse ∩ ellipse are **quartics** — up to
 * four solutions — ordered by ascending parametric angle on the **first operand** and addressed by *index*
 * (`selectAt`), which is what a step's `signs=` records.
 */
class ConicIntersectTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun Editor.at(el: Element): Vec2 = assertNotNull((Evaluator().valueOf(el.ref) as? PointValue)?.p)

    private fun roundTrip(ed: Editor): String {
        val once = DocumentFormat.save(ed.doc)
        val back = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(back), "save -> load -> save must be byte-equal")
        return once
    }

    /** An ellipse element drawn by the two-click tool: centre, axis end, typed semi-axis. */
    private fun Editor.ellipse(
        centre: Vec2,
        axisEnd: Vec2,
        b: String,
    ): Element {
        setTool(Tools.ELLIPSE_AB)
        type(b)
        click(centre)
        click(axisEnd)
        return doc.elements.last { it.kind == ElementKind.ELLIPSE }
    }

    // ---- 1. line ∩ ellipse: the ordinary two-branch set ----

    /** A quadratic: two branches, ordered along the line, both exactly on the curve. */
    @Test
    fun aLineMeetsAnEllipseInTwoOrderedBranches() {
        val e = Ellipse(Vec2(0.0, 0.0), 60.0, 30.0, 0.0)
        val hits = Conics.intersectLE(Line(Vec2(0.0, 10.0), Vec2(1.0, 0.0)), e).points
        assertEquals(2, hits.size)
        assertTrue(hits[0].x < hits[1].x, "ordered along the line's own direction: $hits")
        for (p in hits) assertClose(Conics.implicit(e, p), 0.0, 1e-12, "on the ellipse: $p")
        // a tangent line gives the single point it is, and a miss gives nothing (OP-3 does the rest)
        assertEquals(1, Conics.intersectLE(Line(Vec2(0.0, 30.0), Vec2(1.0, 0.0)), e).points.size)
        assertEquals(0, Conics.intersectLE(Line(Vec2(0.0, 40.0), Vec2(1.0, 0.0)), e).points.size)
    }

    /**
     * Both branches by clicking, persisted — and then the line is dragged so the two branches **swap
     * sides**: the recorded sign keeps *its* branch, because a branch is a choice and is never re-scored
     * (OP-1/OP-18).
     */
    @Test
    fun aRecordedLineEllipseBranchKeepsItsSideWhenTheLineTurns() {
        val ed = Editor()
        ed.ellipse(Vec2(0.0, 0.0), Vec2(60.0, 0.0), "30")
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-100.0, 10.0))
        ed.click(Vec2(100.0, 10.0))
        ed.setTool(Tools.INTERSECT)
        ed.click(Vec2(60.0, 0.0))
        ed.click(Vec2(0.0, 10.0))
        val pts = ed.doc.elements.filter { it.kind == ElementKind.DERIVED_POINT }
        assertEquals(2, pts.size, "a quadratic has two branches")
        val e = Ellipse(Vec2(0.0, 0.0), 60.0, 30.0, 0.0)
        for (p in pts) assertClose(Conics.implicit(e, ed.at(p)), 0.0, 1e-9)
        val leftFirst = ed.at(pts[0]).x < ed.at(pts[1]).x
        assertTrue(leftFirst, "branch 0 is the one the line's direction reaches first")
        val script = roundTrip(ed)

        // reverse the segment by dragging its two endpoints past each other: the line's direction flips, so
        // the *geometric* left/right of the two branches swaps — and each recorded branch stays its own
        val ends = ed.doc.elements.filter { it.kind == ElementKind.POINT && abs(ed.at(it).y - 10.0) < 1e-9 }
        assertEquals(2, ends.size, "the segment's own two ends")
        val a = ends.first { ed.at(it).x < 0 }
        val b = ends.first { ed.at(it).x > 0 }
        (a.ref.node as SourceNode).value = PointValue(Vec2(100.0, 10.0))
        (b.ref.node as SourceNode).value = PointValue(Vec2(-100.0, 10.0))
        val now = pts.map { ed.at(it) }
        assertTrue(now[0].x > now[1].x, "the ordering followed the line, as an ordered set must: $now")
        for (p in now) assertClose(Conics.implicit(e, p), 0.0, 1e-9, "…and both are still on the ellipse")
        // the *script* is unchanged: nothing about the branch was re-decided, only re-evaluated
        assertEquals(script.lines().first(), DocumentFormat.save(ed.doc).lines().first())
    }

    // ---- 2. the quartic: four solutions ----

    /** Two crossed ellipses with four real intersections: all four found, verified by substitution. */
    @Test
    fun twoCrossedEllipsesMeetInFourPointsAllOnBothCurves() {
        val e1 = Ellipse(Vec2(0.0, 0.0), 60.0, 25.0, 0.0)
        val e2 = Ellipse(Vec2(0.0, 0.0), 60.0, 25.0, PI / 2.0)
        val pts = Conics.intersect(e1, e2).points
        assertEquals(4, pts.size, "a cross of two ellipses: $pts")
        for (p in pts) {
            assertClose(Conics.implicit(e1, p), 0.0, 1e-12, "on the first: $p")
            assertClose(Conics.implicit(e2, p), 0.0, 1e-12, "on the second: $p")
        }
        // the ordering convention, asserted: ascending parametric angle on the **first** operand
        val ts = pts.map { Conics.paramOf(e1, it) }
        assertEquals(ts.sorted(), ts, "ordered by t on operand 1: $ts")
        assertTrue(ts.all { it >= 0.0 && it < 2 * PI }, "folded into [0, 2π): $ts")
    }

    /** …and it is not a symmetry accident: an offset, turned pair still gives four, all verified. */
    @Test
    fun anOffsetTurnedPairAlsoGivesFourVerifiedSolutions() {
        val e1 = Ellipse(Vec2(0.0, 0.0), 50.0, 20.0, 0.2)
        val e2 = Ellipse(Vec2(4.0, 3.0), 45.0, 22.0, 1.4)
        val pts = Conics.intersect(e1, e2).points
        assertEquals(4, pts.size, "$pts")
        for (p in pts) {
            assertClose(Conics.implicit(e1, p), 0.0, 1e-11, "on the first: $p")
            assertClose(Conics.implicit(e2, p), 0.0, 1e-11, "on the second: $p")
        }
        val ts = pts.map { Conics.paramOf(e1, it) }
        assertEquals(ts.sorted(), ts, "the stated ordering holds for a general pair too: $ts")
    }

    /** A circle against an ellipse is the same quartic, parametrized on the **circle** (operand 1). */
    @Test
    fun aCircleMeetsAnEllipseInFourPointsOrderedOnTheCircle() {
        val e = Ellipse(Vec2(0.0, 0.0), 60.0, 20.0, 0.0)
        val c = Circle(Vec2(0.0, 0.0), 40.0)
        val pts = Conics.intersect(Conics.ofCircle(c), e).points
        assertEquals(4, pts.size, "$pts")
        for (p in pts) {
            assertClose(Conics.implicit(e, p), 0.0, 1e-12)
            assertClose((p - c.center).length(), 40.0, 1e-12)
        }
        val ts = pts.map { Conics.norm((it - c.center).angle()) }
        assertEquals(ts.sorted(), ts, "ordered by polar angle on the circle: $ts")
    }

    /** t = π is a root the half-angle substitution cannot name, and the solver tests it directly. */
    @Test
    fun aSolutionAtTheParametersOwnSingularityIsStillFound() {
        val e1 = Ellipse(Vec2(0.0, 0.0), 50.0, 30.0, 0.0)
        // a circle through P(π) = (−50, 0), crossing there rather than touching — a transversal solution
        // sitting exactly where the half-angle substitution `z = tan(t/2)` blows up
        val e2 = Conics.ofCircle(Circle(Vec2(-30.0, 10.0), kotlin.math.sqrt(500.0)))
        val pts = Conics.intersect(e1, e2).points
        assertTrue(pts.any { (it - Vec2(-50.0, 0.0)).length() < 1e-9 }, "the singular parameter is a solution: $pts")
        for (p in pts) {
            assertClose(Conics.implicit(e1, p), 0.0, 1e-11)
            assertClose(Conics.implicit(e2, p), 0.0, 1e-11)
        }
    }

    /**
     * A **tangency** at the singular parameter is still found, and reported once — the double root the
     * isolator meets at a critical point rather than at a sign change.
     */
    @Test
    fun aTangencyAtTheSingularParameterIsReportedOnce() {
        val e1 = Ellipse(Vec2(0.0, 0.0), 50.0, 30.0, 0.0)
        val e2 = Ellipse(Vec2(-20.0, 0.0), 30.0, 40.0, 0.0)
        val pts = Conics.intersect(e1, e2).points
        val touching = pts.filter { (it - Vec2(-50.0, 0.0)).length() < 1e-5 }
        assertEquals(1, touching.size, "the two ellipses touch at (−50, 0), once: $pts")
        for (p in pts) {
            assertClose(Conics.implicit(e1, p), 0.0, 1e-9, "$p")
            assertClose(Conics.implicit(e2, p), 0.0, 1e-9, "$p")
        }
    }

    /** Two identical conics share a whole curve, not a solution set — so the set is empty and says so. */
    @Test
    fun twoCoincidentEllipsesHaveNoSolutionSet() {
        val e = Ellipse(Vec2(3.0, 4.0), 50.0, 30.0, 0.3)
        assertEquals(0, Conics.intersect(e, e).points.size)
        val c = Construction()
        val set = c.intersectEE(c.ellipseCAB(c.freePoint("o", 0.0.mm, 0.0.mm), c.freePoint("e", 60.0.mm, 0.0.mm), c.parameter("b", 30.0.mm)), c.ellipseCAB(c.freePoint("o2", 0.0.mm, 0.0.mm), c.freePoint("e2", 60.0.mm, 0.0.mm), c.parameter("b2", 30.0.mm)))
        val why = Evaluator().eval(c.selectAt(set, 0, "these two conics coincide, so they have no crossing").node)
        assertTrue(why is EvalResult.Invalid && why.reason.contains("coincide"), "$why")
    }

    // ---- 3. the four branches, by clicking, persisted ----

    /**
     * Four crossed ellipses by clicking: the *Intersect* tool creates one point per solution there is
     * (structural per extraction, exactly as *Key points* is), each one an indexed branch, and the whole
     * document replays byte for byte.
     */
    @Test
    fun theIntersectToolBuildsAllFourBranchesAndTheyPersist() {
        val ed = Editor()
        ed.ellipse(Vec2(0.0, 0.0), Vec2(60.0, 0.0), "25")
        ed.ellipse(Vec2(0.0, 0.0), Vec2(0.0, 60.0), "25")
        ed.setTool(Tools.INTERSECT)
        ed.click(Vec2(60.0, 0.0))
        ed.click(Vec2(0.0, 60.0))
        val pts = ed.doc.elements.filter { it.kind == ElementKind.DERIVED_POINT }
        assertEquals(4, pts.size, "four solutions, four points")
        val e1 = Ellipse(Vec2(0.0, 0.0), 60.0, 25.0, 0.0)
        val e2 = Ellipse(Vec2(0.0, 0.0), 60.0, 25.0, PI / 2.0)
        for (p in pts) {
            assertClose(Conics.implicit(e1, ed.at(p)), 0.0, 1e-9)
            assertClose(Conics.implicit(e2, ed.at(p)), 0.0, 1e-9)
        }
        val script = roundTrip(ed)
        val back = DocumentFormat.load(script)
        val again = back.elements.filter { it.kind == ElementKind.DERIVED_POINT }
        assertEquals(4, again.size)
        for (i in pts.indices) {
            val a = ed.at(pts[i])
            val b = assertNotNull((Evaluator().valueOf(again[i].ref) as? PointValue)?.p)
            assertClose((a - b).length(), 0.0, 1e-12, "branch $i replays to the same point")
        }
    }

    /**
     * **Each of the four choices persists and replays byte-equal.** The single-branch *snap* route records
     * the branch it scored as `signs=`, so the four are told apart in the file by an index — the encoding
     * `selectAt` uses, and the one a `signs=` integer already carried.
     */
    @Test
    fun eachOfTheFourBranchesIsRecordedByIndexAndReplaysVerbatim() {
        val e1 = Ellipse(Vec2(0.0, 0.0), 60.0, 25.0, 0.0)
        val solutions = Conics.intersect(e1, Ellipse(Vec2(0.0, 0.0), 60.0, 25.0, PI / 2.0)).points
        assertEquals(4, solutions.size)
        for ((i, want) in solutions.withIndex()) {
            val ed = Editor()
            val a = ed.ellipse(Vec2(0.0, 0.0), Vec2(60.0, 0.0), "25")
            val b = ed.ellipse(Vec2(0.0, 0.0), Vec2(0.0, 60.0), "25")
            val p = assertNotNull(ed.doc.intersectNear(a, b, want), "branch $i by proximity")
            assertClose(((Evaluator().valueOf(p) as PointValue).p - want).length(), 0.0, 1e-9, "branch $i")
            val script = DocumentFormat.save(ed.doc)
            assertTrue(script.contains("signs=$i"), "branch $i is recorded as its index:\n$script")
            val back = DocumentFormat.load(script)
            assertEquals(script, DocumentFormat.save(back), "branch $i replays byte-equal")
            val replayed = back.elements.last { it.kind == ElementKind.DERIVED_POINT }
            val at = assertNotNull((Evaluator().valueOf(replayed.ref) as? PointValue)?.p)
            assertClose((at - want).length(), 0.0, 1e-9, "…to the same point")
        }
    }

    /**
     * **Shrink one ellipse until only two solutions remain**: the recorded branch either survives as *its
     * own index* or goes **invalid with a reason** — it never silently becomes another point. And it heals
     * the moment the fourth solution comes back (OP-3).
     */
    @Test
    fun losingTwoSolutionsInvalidatesTheHighBranchesRatherThanSwappingThem() {
        val c = Construction()
        val centre = c.freePoint("cc", 0.0.mm, 0.0.mm)
        val circle = c.circleCR(centre, c.parameter("r", 40.0.mm))
        val ellipse = c.ellipseCAB(c.freePoint("o", 0.0.mm, 0.0.mm), c.freePoint("ax", 60.0.mm, 0.0.mm), c.parameter("b", 25.0.mm))
        val set = c.intersectCE(circle, ellipse)
        assertEquals(4, Evaluator().pointSet(set).points.size)
        val branches = (0..3).map { c.selectAt(set, it) }
        val before = branches.map { (Evaluator().valueOf(it) as PointValue).p }
        val shape = Evaluator().ellipse(ellipse)
        for (p in before) assertClose(Conics.implicit(shape, p), 0.0, 1e-9)

        // slide the circle sideways until the two curves only cross twice
        c.set(centre, 30.0.mm, 0.0.mm)
        val now = Evaluator().pointSet(set).points
        assertEquals(2, now.size, "only two crossings left: $now")
        val ev = Evaluator()
        for (i in 0..1) {
            val v = ev.eval(branches[i].node)
            assertTrue(v is EvalResult.Ok, "branch $i still names the i-th solution of the set")
            assertClose(((v as EvalResult.Ok).value as PointValue).p.let { (it - now[i]).length() }, 0.0, 1e-12)
        }
        for (i in 2..3) {
            val v = ev.eval(branches[i].node)
            assertTrue(v is EvalResult.Invalid, "branch $i has no solution to name")
            assertTrue((v as EvalResult.Invalid).reason.contains("2 solution"), v.reason)
            assertTrue(v.reason.contains("branch ${i + 1}"), "it names the branch: ${v.reason}")
        }
        // …and it heals
        c.set(centre, 0.0.mm, 0.0.mm)
        val healed = Evaluator()
        for (i in 0..3) assertTrue(healed.eval(branches[i].node) is EvalResult.Ok, "branch $i heals")
        for (i in 0..3) {
            val p = (healed.valueOf(branches[i]) as PointValue).p
            assertClose((p - before[i]).length(), 0.0, 1e-12, "and comes back to exactly where it was")
        }
    }

    /** A near-tangency collapses to fewer real solutions, honestly — never to a spurious extra pair. */
    @Test
    fun aNearTangentPairReportsFewerSolutionsRatherThanGuessing() {
        val e1 = Ellipse(Vec2(0.0, 0.0), 50.0, 30.0, 0.0)
        // touching from outside at (50, 0): a double root there, plus nothing else
        val e2 = Ellipse(Vec2(70.0, 0.0), 20.0, 15.0, 0.0)
        val pts = Conics.intersect(e1, e2).points
        assertTrue(pts.size <= 2, "a tangency is one solution or none, never four: $pts")
        for (p in pts) {
            assertClose(Conics.implicit(e1, p), 0.0, 1e-6, "whatever comes back is genuinely on both: $p")
            assertClose(Conics.implicit(e2, p), 0.0, 1e-6)
        }
        // pull them apart: two honest crossings
        val apart = Conics.intersect(e1, Ellipse(Vec2(60.0, 0.0), 20.0, 15.0, 0.0)).points
        assertEquals(2, apart.size, "$apart")
        for (p in apart) {
            assertClose(Conics.implicit(e1, p), 0.0, 1e-11)
        }
        // and past each other: none at all
        assertEquals(0, Conics.intersect(e1, Ellipse(Vec2(200.0, 0.0), 20.0, 15.0, 0.0)).points.size)
    }

    // ---- 4. the polynomial solver itself ----

    /** The root isolator: a quartic with four real roots, one with two, and a double root reported once. */
    @Test
    fun theRootIsolatorFindsEveryRealRootAndRepeatsNone() {
        // (x+3)(x+1)(x-2)(x-5) = x^4 - 3x^3 - 15x^2 + 19x + 30
        val four = constructit.geom.Roots.real(doubleArrayOf(30.0, 19.0, -15.0, -3.0, 1.0))
        assertEquals(4, four.size, "$four")
        for ((i, r) in listOf(-3.0, -1.0, 2.0, 5.0).withIndex()) assertClose(four[i], r, 1e-9, "root $i")
        // (x^2+1)(x-1)(x-4): only two real
        val two = constructit.geom.Roots.real(doubleArrayOf(4.0, -5.0, 5.0, -5.0, 1.0))
        assertEquals(2, two.size, "$two")
        // (x-2)^2 (x^2+1): a double root, once
        val dbl = constructit.geom.Roots.real(doubleArrayOf(4.0, -4.0, 5.0, -4.0, 1.0))
        assertEquals(1, dbl.size, "a repeated root is one root: $dbl")
        assertClose(dbl[0], 2.0, 1e-6)
        // a degree drop: the leading coefficient is (numerically) absent
        val cubic = constructit.geom.Roots.real(doubleArrayOf(-6.0, 11.0, -6.0, 1.0, 1e-20))
        assertEquals(3, cubic.size, "$cubic")
        assertTrue(abs(cubic[0] - 1.0) < 1e-9 && abs(cubic[2] - 3.0) < 1e-9, "$cubic")
    }
}
