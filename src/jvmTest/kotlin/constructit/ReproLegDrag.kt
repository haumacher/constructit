package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test

class ReproLegDrag {
    private val file =
        """
constructit 1
orthostart -70.75,41.75 -> e1
orthovertex -70.75,-12.25 -> e2,e3
orthovertex 21.25,-12.25 -> e4,e5
orthovertex 21.25,41.75 -> e6,e7
orthoclose -> e8
orthostart -29,41.75 -> e9
attachortho e9 e8
orthovertex -29,17.25 -> e10,e11
orthovertex 21.25,17.25 -> e12,e13
attachortho e12 e7
""".trimStart()

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
                println("  path$pi v$vi (${v.ref.node.id}): $pos")
            }
        }
    }

    @Test
    fun draggingTheVerticalBranchLeg() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(file))
        dump(ed, "before")
        // the user's actual gesture: drag the rectangle's BOTTOM wall down by 20
        ed.drag(Vec2(-25.0, -12.25), Vec2(-25.0, -32.25))
        println("status: ${ed.statusHint}")
        dump(ed, "after dragging the bottom wall")
    }
}
