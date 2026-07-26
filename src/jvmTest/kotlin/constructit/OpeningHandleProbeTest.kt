package constructit

import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Probes on jamb handles: a width parameter SHARED by two openings resized through one jamb
 * (equality by reference is the paradigm — the handle must honor it), a jamb on a closed ring,
 * and the leg-relative contract under a carrier stretch.
 */
class OpeningHandleProbeTest {
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

    private fun openings(ed: Editor) = ed.doc.thickPaths.single().intervals

    private fun widthOf(
        ed: Editor,
        i: Int,
    ): Double = constructit.core.Evaluator().let { ev -> (ev.eval(openings(ed)[i].width.node) as constructit.core.EvalResult.Ok).let { (it.value as constructit.core.ScalarValue).q.mm } }

    /** One width parameter, two doors: dragging one far jamb resizes both — shared IS equal. */
    @Test
    fun aSharedWidthResizesBothOpeningsThroughOneJamb() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("w", 15.0.mm)
        ed.setTool(Tools.WALL)
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 20.0))
        ed.click(Vec2(200.0, 21.0))
        ed.finishPath()
        val w = ed.doc.newParameter("w2", 15.0.mm)
        ed.activeScalar = w
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(40.0, 20.0))
        ed.activeScalar = w // the SAME width parameter for the second door
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(140.0, 20.0))
        assertEquals(2, openings(ed).size)
        assertTrue(openings(ed)[0].width.node === openings(ed)[1].width.node, "one node, two doors")

        // drag door 1's far jamb from ~47.5 to 62.5 → width 30; door 2 must follow
        val startEdge = widthOf(ed, 0)
        val posDoor1 = 40.0 - startEdge / 2 // centred on the click
        ed.drag(Vec2(posDoor1 + startEdge, 17.0), Vec2(posDoor1 + 30.0, 17.0)) // off the centerline: the jamb, not the leg
        assertClose(widthOf(ed, 0), 30.0, tol = 0.5, msg = "the dragged door resized")
        assertClose(widthOf(ed, 1), widthOf(ed, 0), msg = "the shared parameter carries door 2 with it")
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)))
    }

    /** A jamb on a closed ring's leg drags like any other; stretching the carrier keeps pos leg-relative. */
    @Test
    fun ringJambsDragAndStayLegRelative() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(150.0, 1.0))
        ed.click(Vec2(149.0, 100.0))
        ed.click(Vec2(1.0, 99.0))
        ed.click(Vec2(0.0, 0.0)) // close the ring
        ed.finishPath()
        ed.activeScalar = ed.doc.newParameter("w", 20.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(50.0, 0.0)) // a door on the bottom leg
        val pos0 = constructit.core.Evaluator().let { ev -> (ev.eval(openings(ed)[0].position.node) as constructit.core.EvalResult.Ok).let { (it.value as constructit.core.ScalarValue).q.mm } }

        // slide the whole door 20 to the right by its leading jamb
        ed.drag(Vec2(pos0, 3.0), Vec2(pos0 + 20.0, 3.0)) // off the centerline: the jamb, not the leg
        val pos1 = constructit.core.Evaluator().let { ev -> (ev.eval(openings(ed)[0].position.node) as constructit.core.EvalResult.Ok).let { (it.value as constructit.core.ScalarValue).q.mm } }
        assertClose(pos1, pos0 + 20.0, tol = 0.5, msg = "the ring door slid by its jamb")

        // stretch the ring: drag the bottom-right corner right; pos stays measured from the leg START
        ed.drag(Vec2(150.0, 0.0), Vec2(190.0, 0.0))
        val pos2 = constructit.core.Evaluator().let { ev -> (ev.eval(openings(ed)[0].position.node) as constructit.core.EvalResult.Ok).let { (it.value as constructit.core.ScalarValue).q.mm } }
        assertClose(pos2, pos1, msg = "leg-relative by design: a stretch of the far end leaves pos alone")
    }
}
