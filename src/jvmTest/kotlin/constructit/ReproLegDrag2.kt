package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.editor.Editor
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test

class ReproLegDrag2 {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
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

    private fun dump(
        ed: Editor,
        label: String,
    ) {
        println(label)
        for ((pi, p) in ed.doc.orthoPaths.withIndex()) {
            for ((vi, v) in p.vertices.withIndex()) {
                val pos = ((Evaluator().eval(v.ref.node) as EvalResult.Ok).value as PointValue).p
                println("  path$pi v$vi: $pos")
            }
        }
    }

    @Test
    fun drawnLiveThenDragTheVerticalLeg() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(-70.75, 41.75))
        ed.click(Vec2(-70.75, -12.25))
        ed.click(Vec2(21.25, -12.25))
        ed.click(Vec2(21.25, 41.75))
        ed.click(Vec2(-70.75, 41.75)) // close
        ed.finishPath()
        // the branch: start ON the top leg, down, right, ending ON the right leg
        ed.click(Vec2(-29.0, 41.75))
        println("branch start: ${ed.statusHint}")
        ed.click(Vec2(-29.0, 17.25))
        ed.click(Vec2(21.25, 17.25))
        println("branch end: ${ed.statusHint}")
        dump(ed, "before")
        ed.drag(Vec2(-29.0, 30.0), Vec2(-44.0, 30.0)) // drag the vertical branch leg left
        println("drag status: ${ed.statusHint}")
        dump(ed, "after")
        // now a VERTICAL gesture on the (vertical) leg: it has no DOF that way — what moves?
        ed.drag(Vec2(-44.0, 30.0), Vec2(-44.0, 45.0))
        println("vertical drag status: ${ed.statusHint}")
        dump(ed, "after vertical gesture")
    }
}
