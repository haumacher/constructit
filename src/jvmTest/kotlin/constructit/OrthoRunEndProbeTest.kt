package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Probes on the doubly-connected run fix: the user's ORIGINAL T-web file must live through a reload
 * with both connections real, and the determined-meeting mechanism must hold on a circle and under
 * undo. This is the file that used to lose its bottom attachment silently.
 */
class OrthoRunEndProbeTest {
    private val userFile =
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

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    private fun vertexAt(
        ed: Editor,
        path: Int,
        i: Int,
    ): Vec2 = ((Evaluator().eval(ed.doc.orthoPaths[path].vertices[i].ref.node) as EvalResult.Ok).value as PointValue).p

    /** The user's exact file: both ends of the middle run stay connected through save and reload. */
    @Test
    fun theUsersTWebKeepsBothConnectionsThroughAReload() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(userFile))
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the file is stable")

        // drag the rectangle's TOP leg up: the middle run's top end must follow it
        ed.drag(Vec2(-30.0, 73.0), Vec2(-30.0, 93.0))
        assertClose(vertexAt(ed, 1, 0).y, 93.0, msg = "the middle run's top end rides the top wall")
        // drag the rectangle's BOTTOM (closing) leg down: the middle run's bottom end must follow too —
        // this is the connection that used to be dropped silently
        ed.drag(Vec2(-30.0, 13.75), Vec2(-30.0, -6.25))
        assertClose(vertexAt(ed, 1, 1).y, -6.25, msg = "the bottom end is genuinely attached, not just drawn there")

        val after = DocumentFormat.save(ed.doc)
        assertEquals(after, DocumentFormat.save(DocumentFormat.load(after)), "still stable after both drags")
    }

    /** The determined meeting point on a CIRCLE: one free coordinate, branch stored, follows the circle. */
    @Test
    fun aDeterminedMeetingOnACircleHoldsItsBranch() {
        val ed = Editor()
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0)) // r=40 at origin
        // a run whose first end attaches to a segment (takes x), then reaches the circle with only y free
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-80.0, 60.0))
        ed.click(Vec2(80.0, 60.0)) // horizontal segment above
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(24.0, 60.0)) // start ON the segment: x becomes the junction's
        ed.click(Vec2(24.0, 32.05)) // straight down onto the circle (24² + 32² ≈ 40²)
        assertTrue(ed.statusHint.contains("finished") || ed.statusHint.contains("ends on"), "got: ${ed.statusHint}")

        // the meeting point must ride the circle: grow it and check the run's end
        ed.drag(Vec2(40.0, 0.0), Vec2(50.0, 0.0)) // drag the radius point
        val end = vertexAt(ed, 0, 1)
        assertClose(end.x * end.x + end.y * end.y, 50.0 * 50.0, tol = 1e-3, msg = "the end sits on the grown circle")
        assertTrue(end.y > 0.0, "the stored branch keeps the upper crossing")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "circle meeting replays")
    }

    /** Undo across the determined-meeting attach: one step back to the unfinished run's absence. */
    @Test
    fun undoTakesTheWholeConnectedRunBack() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(userFile))
        val before = DocumentFormat.save(ed.doc)
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(-20.0, 73.0)) // start on the top leg
        ed.click(Vec2(-20.0, 13.75)) // straight down onto the bottom leg: both-ends-connected run
        assertTrue(ed.doc.orthoPaths.size == 3)

        assertTrue(ed.undo())
        assertEquals(before, DocumentFormat.save(ed.doc), "the run, its attaches and its meeting point undo as one step")
        assertTrue(ed.redo())
        assertEquals(3, ed.doc.orthoPaths.size)
    }
}
