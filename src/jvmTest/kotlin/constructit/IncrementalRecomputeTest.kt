package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.FrameValue
import constructit.core.Node
import constructit.core.PointValue
import constructit.core.ScalarValue
import constructit.core.SegmentValue
import constructit.core.SolidValue
import constructit.dsl.PointRef
import constructit.dsl.valueOf
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Scene3
import constructit.editor.SvgDrawTarget
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.deg
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Persistent incremental recompute — the OP-5 dirty-marking, kept.**
 *
 * The design promised from day one that a drag "mutates a literal, marks the node dirty, and recomputes
 * only the affected downstream cone", with outputs cached. What the implementation had was a *per-pass*
 * memo: a fresh [Evaluator] per repaint, per hit-test, per handle read, each one recomputing the whole
 * cone of whatever it touched. On 2D geometry that was invisible; on a revolve it re-tessellated the mesh
 * on every mouse move, which is what made even the 2D view lag (the 2D pass evaluates a solid element
 * too, to find out that it is a solid).
 *
 * **The scheme**: each node keeps its last result *and the argument values it computed it from*, and
 * reuses it when the arguments are the same objects (`===`). Nothing else — no clock, no version stamps,
 * no reverse-dependency index. The mark travels downstream by itself: a mutated source hands out a new
 * value object, so every consumer's identity check misses, and theirs in turn, exactly through the
 * affected cone and no further. What this file has to prove is that *every* mutation point really does
 * hand out something new — the literal writes, and the four re-pointings that change the DAG's shape
 * (weld, attach, capture under a frame, parameter wiring) where a stale cache would be wrong geometry
 * rather than a slow repaint.
 *
 * The counters ([Node.computeCount]) are the acceptance criterion the queue item states, and are the
 * second half of this file: repaints that change nothing upstream must leave every one of them alone.
 */
class IncrementalRecomputeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun el(
        ed: Editor,
        id: String,
    ): Element = ed.doc.elements.first { it.id == id }

    private fun pos(node: Node): Vec2 = ((Evaluator().eval(node) as EvalResult.Ok).value as PointValue).p

    private fun pos(el: Element): Vec2 = pos(el.ref.node)

    /** Every node reachable from [root] through its *current* inputs, [root] included. */
    private fun cone(root: Node): List<Node> {
        val seen = LinkedHashMap<Node, Unit>()

        fun walk(n: Node) {
            if (seen.put(n, Unit) != null) return
            n.inputs.forEach { walk(it) }
        }
        walk(root)
        return seen.keys.toList()
    }

    /** Total [Node.computeCount] over [root]'s cone — how much work the graph under it has ever done. */
    private fun computes(root: Node): Int = cone(root).sumOf { it.computeCount }

    private fun computes(ed: Editor): Int {
        val seen = LinkedHashMap<Node, Unit>()
        ed.doc.elements.forEach { e -> cone(e.ref.node).forEach { seen[it] = Unit } }
        ed.doc.scalars.forEach { s -> cone(s.ref.node).forEach { seen[it] = Unit } }
        return seen.keys.sumOf { it.computeCount }
    }

    /** Read the whole document once, so every memo is warm — what a repaint before the edit would do. */
    private fun warm(ed: Editor) {
        val ev = Evaluator()
        ed.doc.elements.forEach { ev.eval(it.ref.node) }
        ed.doc.scalars.forEach { ev.eval(it.ref.node) }
    }

    // =================================================================================================
    // The dangerous half: mutations that change the shape of the DAG, each read through a warm cache.
    // =================================================================================================

    /** Two free points and the segment between them — the smallest scene with a derived consumer. */
    private fun pair(): Editor {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0)) // e1
        ed.click(Vec2(30.0, 0.0)) // e2
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0)) // e3
        ed.setTool(Tools.SELECT)
        return ed
    }

    private fun seg(ed: Editor) = (Evaluator().valueOf(el(ed, "e3").ref) as SegmentValue).seg

    @Test
    fun aWeldThroughAWarmCacheIsSeenAtOnce() {
        val ed = pair()
        warm(ed)

        assertTrue(ed.doc.weld(el(ed, "e2"), el(ed, "e1")), "e2 welds onto e1")
        assertClose(pos(el(ed, "e2")).x, 0.0, msg = "the alias reads its master, not the literal it kept")
        assertClose(seg(ed).b.x, 0.0, msg = "and so does everything already built on it (OP-5)")

        warm(ed)
        ed.doc.moveFreePoint(el(ed, "e1"), Vec2(-20.0, 5.0))
        assertClose(pos(el(ed, "e2")).x, -20.0, msg = "the alias follows a master that moves")
        assertClose(seg(ed).b.y, 5.0)

        warm(ed)
        ed.doc.unweld(el(ed, "e2"))
        ed.doc.moveFreePoint(el(ed, "e1"), Vec2(40.0, 40.0))
        assertClose(pos(el(ed, "e2")).x, -20.0, msg = "unwelded, it keeps the position it had and stops following")
        assertClose(seg(ed).b.y, 5.0)
    }

    @Test
    fun makeRelativeAndMakeAbsoluteAreSeenAtOnce() {
        val ed = pair()
        warm(ed)

        assertTrue(ed.doc.makeRelative(el(ed, "e2"), el(ed, "e1")), "got: ${ed.doc.note}")
        assertClose(pos(el(ed, "e2")).x, 30.0, msg = "re-parameterizing moves nothing")

        warm(ed)
        ed.doc.moveFreePoint(el(ed, "e1"), Vec2(10.0, 0.0))
        assertClose(pos(el(ed, "e2")).x, 40.0, msg = "the offset now rides the anchor")
        assertClose(seg(ed).b.x, 40.0)

        warm(ed)
        assertTrue(ed.doc.makeAbsolute(el(ed, "e2")), "got: ${ed.doc.note}")
        ed.doc.moveFreePoint(el(ed, "e1"), Vec2(-50.0, 0.0))
        assertClose(pos(el(ed, "e2")).x, 40.0, msg = "absolute again: it owns its coordinates and stays")
    }

    @Test
    fun anAttachToACurveIsSeenAtOnce() {
        val ed = pair()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(15.0, 12.0)) // e4, clear of the segment
        ed.setTool(Tools.SELECT)
        warm(ed)

        assertTrue(ed.doc.attachToCurve(el(ed, "e4"), el(ed, "e3")), "got: ${ed.doc.note}")
        assertClose(pos(el(ed, "e4")).y, 0.0, msg = "attaching puts the point on its host")

        warm(ed)
        ed.doc.moveFreePoint(el(ed, "e1"), Vec2(0.0, 20.0))
        ed.doc.moveFreePoint(el(ed, "e2"), Vec2(30.0, 20.0))
        assertClose(pos(el(ed, "e4")).y, 20.0, msg = "and it rides the host when the host moves")
    }

    /**
     * A tool-made rider freed again: this is the [constructit.core.IndirectNode] re-pointing — the view a
     * rider publishes is aimed at a fresh free source, with no consumer rewired. A memo that survived that
     * would leave the point stuck to a curve it no longer rides.
     */
    @Test
    fun freeingARiderRepointsItsViewThroughAWarmCache() {
        val ed = pair()
        ed.setTool(Tools.POINT_ON_LINE)
        ed.click(Vec2(15.0, 0.0)) // a rider on e3
        ed.setTool(Tools.SELECT)
        val rider = ed.doc.elements.last { it.isPoint }
        warm(ed)
        ed.doc.moveFreePoint(el(ed, "e2"), Vec2(30.0, 40.0))
        val riding = pos(rider)
        assertTrue(riding.y > 1.0, "the rider follows its host while it rides it (at $riding)")

        warm(ed)
        assertTrue(ed.doc.makeAbsolute(rider), "got: ${ed.doc.note}")
        assertClose(pos(rider).x, riding.x, msg = "freeing moves nothing")
        assertClose(pos(rider).y, riding.y)

        warm(ed)
        ed.doc.moveFreePoint(el(ed, "e2"), Vec2(30.0, 0.0))
        assertClose(pos(rider).y, riding.y, msg = "and from then on the point ignores the curve")
    }

    @Test
    fun placingAndUnplacingAGroupAreSeenAtOnce() {
        val ed = pair()
        ed.pointerDown(ed.camera.worldToScreen(Vec2(-40.0, -40.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(60.0, 40.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(60.0, 40.0)))
        assertEquals(3, ed.selectionCount, "the marquee takes the whole scene")
        ed.groupSelection("part")
        val g = ed.doc.groups.single()
        warm(ed)

        assertTrue(ed.placeGroup(g), "got: ${ed.statusHint}")
        assertClose(pos(el(ed, "e2")).x, 30.0, msg = "placing is world-invariant")

        warm(ed)
        val frame = g.frameNode!!
        val f = (Evaluator().eval(frame) as EvalResult.Ok).value as FrameValue
        frame.value = FrameValue(f.origin + Vec2(100.0, 0.0), f.angle)
        assertClose(pos(el(ed, "e2")).x, 130.0, msg = "moving the frame moves every captured point")
        assertClose(seg(ed).a.x, 100.0, msg = "…and the geometry built on them")

        warm(ed)
        assertTrue(ed.unplaceGroup(g))
        val was = pos(el(ed, "e2"))
        frame.value = FrameValue(f.origin + Vec2(500.0, 0.0), f.angle)
        assertClose(pos(el(ed, "e2")).x, was.x, msg = "unplaced, the points own their coordinates again")
    }

    /** The same substrate one level up: a wall's vertices are captured through re-pointable views. */
    @Test
    fun placingAWallCapturesItsPathThroughAWarmCache() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(1.0, 60.0))
        ed.finishPath()
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(-40.0, -40.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(60.0, 100.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(60.0, 100.0)))
        ed.groupSelection("wall")
        val g = ed.doc.groups.single()
        warm(ed)
        assertTrue(ed.placeGroup(g), "got: ${ed.statusHint}")

        warm(ed)
        val frame = g.frameNode!!
        val f = (Evaluator().eval(frame) as EvalResult.Ok).value as FrameValue
        frame.value = FrameValue(f.origin + Vec2(250.0, 0.0), f.angle)
        val ev = Evaluator()
        val moved =
            ed.doc.elements.mapNotNull { (ev.valueOf(it.ref) as? SegmentValue)?.seg }
                .flatMap { listOf(it.a.x, it.b.x) }
        assertTrue(moved.isNotEmpty(), "the wall draws something")
        assertTrue(moved.all { it > 200.0 }, "the whole captured path travelled with the frame: $moved")
    }

    @Test
    fun wiringAndUnwiringAParameterAreSeenAtOnce() {
        val ed = Editor()
        val a = ed.doc.newParameter("a", 10.0.mm)
        val b = ed.doc.newParameter("b", 25.0.mm)
        warm(ed)

        assertTrue(ed.doc.wireParameter(a, b), "a := b")
        assertClose(((Evaluator().valueOf(a.ref) as ScalarValue).q).mm, 25.0, msg = "the wired parameter reads its master")

        warm(ed)
        ed.doc.setParameter(b, 40.0.mm)
        assertClose(((Evaluator().valueOf(a.ref) as ScalarValue).q).mm, 40.0)

        warm(ed)
        ed.doc.unwireParameter(a)
        ed.doc.setParameter(b, 5.0.mm)
        assertClose(((Evaluator().valueOf(a.ref) as ScalarValue).q).mm, 40.0, msg = "unwired, it keeps its last driven value")
    }

    // =================================================================================================
    // The acceptance: recompute counters flat across repaints that change nothing upstream.
    // =================================================================================================

    /**
     * The turned part: a wall footprint (10 wide, 60 long) spun a full turn about a drawn line on the Y
     * axis — the fixture the user's report is about, since its mesh is the expensive node in the graph.
     */
    private fun turnedPart(sweeps: List<Double> = listOf(360.0)): Editor {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(20.0, 0.0))
        ed.click(Vec2(21.0, 60.0))
        ed.finishPath()
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(0.0, 20.0))
        for ((i, s) in sweeps.withIndex()) {
            ed.activeScalar = ed.doc.newParameter(if (i == 0) "sweep" else "sweep$i", s.deg)
            ed.setTool(Tools.REVOLVE)
            ed.click(Vec2(15.0, 30.0))
            ed.click(Vec2(0.0, 10.0))
        }
        ed.setTool(Tools.SELECT)
        return ed
    }

    private fun Editor.solid(): Element = doc.elements.first { it.kind == ElementKind.SOLID }

    /** One repaint: the 2D scene *and* the 3D scene, which is what the browser shell does per frame. */
    private fun repaint(ed: Editor) {
        ed.render(SvgDrawTarget())
        Scene3.extract(ed.doc)
    }

    @Test
    fun aHundredRepaintsThatChangeNothingRecomputeNothing() {
        val ed = turnedPart()
        repaint(ed)
        val solid = ed.solid().ref.node
        val afterFirst = computes(ed)
        val solidAfterFirst = solid.computeCount

        repeat(100) { repaint(ed) }

        assertEquals(afterFirst, computes(ed), "100 repaints of an untouched drawing must recompute nothing")
        assertEquals(solidAfterFirst, solid.computeCount, "the revolve tessellates once, not once per frame")
        assertEquals(1, solid.computeCount, "…and that once is the first pass")
    }

    @Test
    fun draggingAnUnrelatedPointLeavesTheRevolveAlone() {
        val ed = turnedPart()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-80.0, -80.0))
        ed.setTool(Tools.SELECT)
        repaint(ed)
        val solid = ed.solid().ref.node
        val far = ed.doc.elements.last { it.kind == ElementKind.POINT }
        val before = solid.computeCount
        val farBefore = computes(far.ref.node)

        // a drag is many moves, i.e. many repaints — the frame rate the report is about
        val from = ed.camera.worldToScreen(Vec2(-80.0, -80.0))
        ed.pointerDown(from)
        for (i in 1..20) {
            ed.pointerMove(ed.camera.worldToScreen(Vec2(-80.0 + i, -80.0)))
            repaint(ed)
        }
        ed.pointerUp(ed.camera.worldToScreen(Vec2(-60.0, -80.0)))
        repaint(ed)

        assertClose(pos(far).x, -60.0, msg = "the point really did move")
        assertEquals(before, solid.computeCount, "a drag outside the revolve's cone must not re-tessellate it")
        assertTrue(computes(far.ref.node) > farBefore, "…while the cone that did change recomputed")
    }

    @Test
    fun editingTheRevolveAngleRecomputesItOncePerPass() {
        val ed = turnedPart()
        repaint(ed)
        val solid = ed.solid().ref.node
        val sweep = ed.doc.scalars.first { it.name == "sweep" }
        val before = solid.computeCount
        val fullTurn = volumeOf(ed)

        ed.doc.setParameter(sweep, 180.0.deg)
        repaint(ed)
        assertEquals(before + 1, solid.computeCount, "the edited parameter's cone recomputes exactly once")
        repaint(ed)
        assertEquals(before + 1, solid.computeCount, "and the repaint after it is free again")

        // and it is the *new* geometry that is cached, not the old one
        assertClose(volumeOf(ed), fullTurn / 2.0, tol = fullTurn * 1e-2, msg = "half the sweep, half the material")
    }

    private fun volumeOf(ed: Editor): Double =
        Geom3.volume((Evaluator().valueOf(ed.solid().ref) as SolidValue).solid.mesh)

    /**
     * The perf demonstration, on the same fixture. The "before" is measured by forcing the very work the
     * old evaluator did — every node's memo dropped, so each pass recomputes the whole cone. Nothing is
     * asserted on wall-clock (it is a shared CI machine); the assertion is on the counters, and the
     * numbers are printed.
     */
    @Test
    fun aWarmCacheMakesARepaintCheap() {
        val ed = turnedPart(sweeps = listOf(360.0, 300.0, 240.0, 180.0))
        repaint(ed)
        val roots = ed.doc.elements.map { it.ref.node }
        val all = LinkedHashMap<Node, Unit>().also { m -> roots.forEach { r -> cone(r).forEach { m[it] = Unit } } }.keys
        val tris = ed.doc.elements.filter { it.kind == ElementKind.SOLID }.sumOf { meshOf(it).triangles.size }
        val passes = 50

        // the evaluation half of a render pass, which is the part this change is about
        val coldEval =
            timed(passes) {
                all.forEach { it.invalidate() }
                warm(ed)
            }
        val warmEval = timed(passes) { warm(ed) }
        // and the whole repaint, 2D scene plus 3D scene, as the browser shell drives it per frame
        val cold =
            timed(passes) {
                all.forEach { it.invalidate() }
                repaint(ed)
            }
        val coldComputes = computes(ed)
        val warmRepaint = timed(passes) { repaint(ed) }

        assertEquals(coldComputes, computes(ed), "the warm passes recompute nothing at all")
        println(
            "[incremental] ${all.size} nodes, $tris triangles, $passes passes: " +
                "eval ${fmtMs(coldEval)} -> ${fmtMs(warmEval)} ms (${fmtX(coldEval / warmEval)}x), " +
                "repaint ${fmtMs(cold)} -> ${fmtMs(warmRepaint)} ms (${fmtX(cold / warmRepaint)}x)",
        )
    }

    private fun meshOf(el: Element) = (Evaluator().valueOf(el.ref) as SolidValue).solid.mesh

    private fun timed(
        passes: Int,
        body: () -> Unit,
    ): Double {
        val t0 = System.nanoTime()
        repeat(passes) { body() }
        return (System.nanoTime() - t0) / 1e6 / passes
    }

    private fun fmtMs(v: Double) = ((v * 1000).toLong() / 1000.0).toString()

    private fun fmtX(v: Double) = ((v * 10).toLong() / 10.0).toString()

    /**
     * The memo is **per node**, so it is document-scoped without a static anywhere: two documents built
     * from the same script share no cache, and an edit in one is invisible to the other.
     */
    @Test
    fun twoDocumentsShareNoCache() {
        val a = pair()
        val b = pair()
        warm(a)
        warm(b)
        a.doc.moveFreePoint(el(a, "e1"), Vec2(-100.0, 0.0))
        assertClose(pos(el(a, "e1")).x, -100.0)
        assertClose(pos(el(b, "e1")).x, 0.0, msg = "the other document is untouched")
    }

    /**
     * **Invalidity is never memoized (OP-3).** A node can be invalid for a reason its arguments do not
     * carry — the general boolean engine still loading is the standing case — so it must be retried on
     * every pass and heal the moment the reason goes away. Here the reason is an ordinary one: a zero
     * sweep. What matters is that the invalid node is *recomputed* while it is invalid rather than
     * answering from a cache that would outlive the reason.
     */
    @Test
    fun anInvalidNodeIsRetriedEveryPassSoItCanHeal() {
        val ed = turnedPart()
        repaint(ed)
        val solid = ed.solid().ref.node
        val sweep = ed.doc.scalars.first { it.name == "sweep" }

        ed.doc.setParameter(sweep, 0.0.deg)
        repaint(ed)
        assertTrue(Evaluator().eval(solid) is EvalResult.Invalid, "a zero sweep has no solid")
        val tries = solid.computeCount
        repaint(ed)
        assertTrue(solid.computeCount > tries, "an invalid node is retried, never cached (OP-3)")

        ed.doc.setParameter(sweep, 360.0.deg)
        repaint(ed)
        assertTrue(Evaluator().eval(solid) is EvalResult.Ok, "and it heals")
        val healed = solid.computeCount
        repaint(ed)
        assertEquals(healed, solid.computeCount, "…back to being free")
    }

    /**
     * A macro instance over a *definition source* opts out of the memo, because the wrapper runs that
     * node's `compute` and cannot see writes to its literal. Retyping a captured default must reach every
     * instance on the next pass — the property that makes an instance a function of its definition.
     */
    @Test
    fun aCapturedDefaultStillReachesEveryInstance() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0)) // e1: the input the instance is stamped at
        ed.click(Vec2(40.0, 0.0)) // e2: an internal free point of the definition
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0)) // e3
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(-20.0, -20.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(60.0, 20.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(60.0, 20.0)))
        val def =
            ed.doc.defineMacro("stick", ed.doc.elements.filter { it.visible }, listOf(el(ed, "e1")), emptyList())
                ?: throw AssertionError("the definition should be accepted")
        val made = ed.doc.instantiateMacro(def, listOf(el(ed, "e1").ref as PointRef), emptyList())
        assertTrue(made.isNotEmpty(), "the instance exists")
        warm(ed)

        // retype the definition's internal point: the instance is a view of it, so it must follow
        ed.doc.moveFreePoint(el(ed, "e2"), Vec2(40.0, 25.0))
        val ev = Evaluator()
        val segs = made.mapNotNull { (ev.valueOf(it.ref) as? SegmentValue)?.seg }
        assertTrue(segs.isNotEmpty() && segs.all { it.b.y > 20.0 }, "the instance follows the definition: $segs")
    }
}
