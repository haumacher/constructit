package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.editor.Editor
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertTrue

class OrthoAttachVariantsTest {
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

    private fun endOf(
        ed: Editor,
        path: Int,
    ): Vec2 =
        ed.doc.orthoPaths[path].vertices.last().let { v ->
            ((Evaluator().eval(v.ref.node) as EvalResult.Ok).value as PointValue).p
        }

    @Test
    fun ontoMidLegOfALongerPath() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 2.0))
        ed.click(Vec2(98.0, 60.0))
        ed.click(Vec2(180.0, 62.0)) // 3 legs
        ed.finishPath()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(140.0, 120.0))
        ed.click(Vec2(141.0, 90.0))
        ed.finishPath()
        ed.drag(Vec2(140.0, 90.0), Vec2(140.0, 60.0)) // onto the third leg (y=60)
        println("status: ${ed.statusHint}; end=${endOf(ed, 1)}")
        ed.drag(Vec2(170.0, 60.0), Vec2(170.0, 40.0)) // drag that leg down
        println("after leg drag end=${endOf(ed, 1)}")
        assertTrue(kotlin.math.abs(endOf(ed, 1).y - 40.0) < 1e-6, "end should ride the leg, is at ${endOf(ed, 1)}")
    }

    @Test
    fun ontoALegCarryingAWall() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 2.0))
        ed.finishPath()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(40.0, 80.0))
        ed.click(Vec2(41.0, 30.0))
        ed.finishPath()
        ed.drag(Vec2(40.0, 30.0), Vec2(40.0, 0.0)) // onto the wall's centerline leg
        println("status: ${ed.statusHint}; end=${endOf(ed, 1)}")
        assertTrue(kotlin.math.abs(endOf(ed, 1).y) < 1e-6, "end should land on the centerline, is at ${endOf(ed, 1)}")
        ed.drag(Vec2(80.0, 0.0), Vec2(80.0, -20.0))
        println("after leg drag end=${endOf(ed, 1)}")
        assertTrue(kotlin.math.abs(endOf(ed, 1).y + 20.0) < 1e-6, "end should ride the wall leg, is at ${endOf(ed, 1)}")
    }

    @Test
    fun ontoAClosedLoopLeg() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 2.0))
        ed.click(Vec2(98.0, 60.0))
        ed.click(Vec2(1.0, 58.0))
        ed.click(Vec2(0.0, 0.0)) // close the loop
        ed.finishPath()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(40.0, 120.0))
        ed.click(Vec2(41.0, 80.0))
        ed.finishPath()
        ed.drag(Vec2(40.0, 80.0), Vec2(40.0, 60.0)) // onto the loop's top leg
        println("status: ${ed.statusHint}; end=${endOf(ed, 1)}")
        assertTrue(kotlin.math.abs(endOf(ed, 1).y - 60.0) < 1e-6, "end should land on the loop leg, is at ${endOf(ed, 1)}")
    }
}
