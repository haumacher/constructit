package constructit

import constructit.core.Evaluator
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Probes on curve breaking: the sliding split under a traced outline (the halves always recompose
 * the same curve, so the region's area is invariant in t — one number checks the whole stack), and
 * a break whose original carries a rider (the consumer route must keep the rider alive).
 */
class BreakCurveProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    @Test
    fun slidingTheSplitLeavesATracedAreaInvariant() {
        val ed = Editor()
        // a closed shape: two segments + a bezier top, then break the bezier and trace through the split
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.BEZIER)
        ed.click(Vec2(100.0, 0.0))
        ed.click(Vec2(90.0, 60.0))
        ed.click(Vec2(10.0, 60.0))
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.BREAK_LEG)
        ed.click(Vec2(50.0, 45.2)) // near the bezier's crown
        println("break: ${ed.statusHint}")
        val beziers = ed.doc.elements.filter { it.kind == ElementKind.BEZIER && it.visible }
        assertEquals(2, beziers.size, "two visible halves")

        ed.setTool(Tools.OUTLINE)
        ed.click(Vec2(50.0, 0.0)) // the base segment
        ed.click(Vec2(25.0, 40.0)) // one bezier half — follow should take the rest
        val outline = ed.doc.elements.last { it.kind == ElementKind.OUTLINE }
        val areaRef = ed.doc.cx.loopArea(outline.ref as constructit.dsl.LoopRef)
        val a0 = (Evaluator().eval(areaRef.node) as constructit.core.EvalResult.Ok).let { (it.value as constructit.core.ScalarValue).q.base }
        assertTrue(a0 > 100.0, "a real area: $a0")

        // slide the split parameter: the halves recompose the same curve — area must not move
        val t = ed.doc.scalars.first { it.name.startsWith("t") }
        for (v in listOf(0.2, 0.5, 0.8)) {
            ed.doc.setParameter(t, constructit.units.Quantity.number(v))
            val a = (Evaluator().eval(areaRef.node) as constructit.core.EvalResult.Ok).let { (it.value as constructit.core.ScalarValue).q.base }
            assertClose(a, a0, tol = 1e-6, msg = "area invariant at t=$v")
        }
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "broken bezier + outline replays")
    }

    @Test
    fun breakingASegmentWithARiderKeepsTheRiderAlive() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.POINT_ON_LINE)
        ed.click(Vec2(70.0, 0.0)) // a rider — the segment now has a consumer
        ed.setTool(Tools.BREAK_LEG)
        ed.click(Vec2(30.0, 0.0))
        println("break: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("hidden"), "the consumer route explains itself: ${ed.statusHint}")

        // the rider still lives on the (hidden) original and still slides
        val rider = ed.doc.elements.first { it.kind == ElementKind.ON_CURVE }
        val p0 = (Evaluator().valueOf(rider.ref) as constructit.core.PointValue).p
        assertClose(p0.x, 70.0)
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(70.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(85.0, 0.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(85.0, 0.0)))
        val p1 = (Evaluator().valueOf(rider.ref) as constructit.core.PointValue).p
        assertClose(p1.x, 85.0, msg = "the rider still slides on its hidden carrier")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "hidden-original break replays")
    }
}
