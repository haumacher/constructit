package constructit

import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test

class OrthoBranchStartTest {
    private val script =
        """
constructit 1
orthostart -63.5,13.75 -> e1
orthovertex -63.5,73 -> e2,e3
orthovertex 53.5,73 -> e4,e5
orthovertex 53.5,13.75 -> e6,e7
orthoclose -> e8
orthostart 15.75,73 -> e9
attachortho e9 e5
orthovertex 15.75,13.75 -> e10,e11
attachortho e10 e8
""".trimStart()

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    @Test
    fun startingABranchOnTheJunctionPoint() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(script))
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(15.75, 73.0)) // the T-junction point (e9's attached start)
        kotlin.test.assertTrue(ed.statusHint.contains("starts on") && ed.statusHint.contains("existing point"), "a branch starts on the junction point, got: ${ed.statusHint}")
        ed.click(Vec2(35.0, 73.0))
        ed.click(Vec2(35.0, 40.0))
        ed.finishPath()
        kotlin.test.assertEquals(3, ed.doc.orthoPaths.size, "the branch is a third path")
    }

    @Test
    fun startingABranchOnARectangleCorner() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(script))
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(53.5, 73.0)) // the rectangle's NE corner point
        kotlin.test.assertTrue(ed.statusHint.contains("starts on"), "a branch starts on the corner, got: ${ed.statusHint}")
        ed.click(Vec2(53.5, 50.0))
        ed.click(Vec2(15.75, 50.0))
        kotlin.test.assertTrue(ed.statusHint.contains("ends on"), "reaching the middle leg ends the run attached, got: ${ed.statusHint}")
        kotlin.test.assertEquals(3, ed.doc.orthoPaths.size)
    }
}
