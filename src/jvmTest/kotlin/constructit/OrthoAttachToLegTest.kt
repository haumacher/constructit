package constructit

import constructit.editor.Editor
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertTrue

class OrthoAttachToLegTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    @Test
    fun dragEndOntoOtherPathLeg() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 2.0)) // path A: horizontal leg y=0..ish
        ed.finishPath()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(40.0, 80.0))
        ed.click(Vec2(41.0, 30.0)) // path B: vertical, dangling end at (40,30)
        ed.finishPath()
        println("paths=" + ed.doc.orthoPaths.size)
        // drag B's dangling end down onto A's leg at (40,0)
        ed.drag(Vec2(40.0, 30.0), Vec2(40.0, 0.0))
        println("status: " + ed.statusHint)
        val endY =
            ed.doc.orthoPaths[1].vertices.last().let { v ->
                (constructit.core.Evaluator().eval(v.ref.node) as constructit.core.EvalResult.Ok).value as constructit.core.PointValue
            }.p
        println("end after drag: $endY")
        assertTrue(kotlin.math.abs(endY.y - 0.0) < 1e-6, "end should sit on A's leg (attached), is at $endY")
        // and it should STAY attached: drag A's leg down, B's end must follow
        ed.drag(Vec2(80.0, 0.0), Vec2(80.0, -20.0))
        val endY2 =
            ed.doc.orthoPaths[1].vertices.last().let { v ->
                (constructit.core.Evaluator().eval(v.ref.node) as constructit.core.EvalResult.Ok).value as constructit.core.PointValue
            }.p
        println("end after leg drag: $endY2")
        assertTrue(kotlin.math.abs(endY2.y + 20.0) < 1e-6, "attached end must follow the leg, is at $endY2")
    }

    @Test
    fun drawingAPathOntoAnotherPathsLegFinishesAttached() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 2.0)) // path A
        ed.finishPath()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(40.0, 80.0))
        ed.click(Vec2(40.0, 1.0)) // reaching A's leg should finish the run attached
        println("status: " + ed.statusHint + " paths=" + ed.doc.orthoPaths.size + " active=" + (ed.doc.orthoPaths.getOrNull(1)?.vertices?.size))
        // drag A's leg; B's end should follow if attached
        ed.drag(Vec2(80.0, 0.0), Vec2(80.0, -20.0))
        val end =
            ed.doc.orthoPaths[1].vertices.last().let { v ->
                (constructit.core.Evaluator().eval(v.ref.node) as constructit.core.EvalResult.Ok).value as constructit.core.PointValue
            }.p
        println("B end after dragging A's leg: $end")
        assertTrue(kotlin.math.abs(end.y + 20.0) < 1e-6, "B's end must ride A's leg, is at $end")
    }
}
