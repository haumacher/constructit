package constructit

import constructit.core.CircleValue
import constructit.core.Evaluator
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.PointerButton
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Probes on group-fed arrays: a group whose member is an ANNOTATION (not geometry — the fan must
 * answer honestly, not emit invisible garbage), and originals driving their copies live after a
 * group array.
 */
class GroupArrayProbeTest {
    private fun Editor.click(
        world: Vec2,
        additive: Boolean = false,
    ) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s, PointerButton.PRIMARY, additive)
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

    /** A dimension inside the arrayed group: the tool must say what it does with it, not garble. */
    @Test
    fun aGroupContainingADimensionArraysHonestly() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(20.0, 0.0))
        ed.setTool(Tools.DIM_LINEAR)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(20.0, 0.0))
        ed.click(Vec2(10.0, 12.0))
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(-10.0, -10.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(30.0, 20.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(30.0, 20.0)))
        val g = assertNotNull(ed.groupSelection("dimmed"))
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(0.0, 0.0)) // whole-group selection

        ed.setTool(Tools.POINT)
        ed.click(Vec2(10.0, 60.0)) // the pattern centre (leaves group selection... reselect)
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(0.0, 0.0)) // whole-group selection again
        ed.count = 2
        ed.setTool(Tools.ARRAY_CIRCULAR)
        ed.click(Vec2(0.0, 0.0)) // member click feeds the group
        ed.click(Vec2(10.0, 60.0)) // centre
        println("status: ${ed.statusHint}")

        // honest outcomes: either the dimension was skipped with a message, or copied as a valid
        // annotation. NOT acceptable: invalid invisible elements in the document.
        val ev = Evaluator()
        val broken = ed.doc.elements.filter { ev.valueOf(it.ref) == null && it.visible }
        assertEquals(emptyList(), broken.map { it.id }, "no arrayed element may be silently invalid: status was '${ed.statusHint}'")
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "whatever the answer, it replays")
    }

    /** After a group array, dragging one ORIGINAL member drives its copies live. */
    @Test
    fun draggingAnOriginalDrivesItsGroupArrayCopies() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.CIRCLE_R)
        ed.activeScalar = ed.doc.newParameter("r", 4.0.mm)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(0.0, 10.0))
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(-8.0, -8.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(8.0, 14.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(8.0, 14.0)))
        val g = assertNotNull(ed.groupSelection("unit"))
        ed.setTool(Tools.POINT)
        ed.click(Vec2(40.0, 0.0)) // centre
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(0.0, 10.0)) // reselect group via the segment end... (member click)
        ed.count = 2
        ed.setTool(Tools.ARRAY_CIRCULAR)
        ed.click(Vec2(0.0, 4.0)) // circle outline: member click feeds the whole group
        ed.click(Vec2(40.0, 0.0))
        val circles = { ed.doc.elements.filter { it.kind == ElementKind.CIRCLE } }
        assertEquals(2, circles().size, "original + 1 copy (count=2 incl. original): ${ed.statusHint}")

        // drag the ORIGINAL circle's centre point; the copy must follow (180° about (40,0))
        ed.drag(Vec2(0.0, 0.0), Vec2(-6.0, 4.0)) // clear of the weld magnet round the segment end
        val copy = (Evaluator().valueOf(circles()[1].ref) as CircleValue).circle
        assertClose(copy.center.x, 86.0, msg = "the copy re-derives from the dragged original")
        assertClose(copy.center.y, -4.0)
    }
}
