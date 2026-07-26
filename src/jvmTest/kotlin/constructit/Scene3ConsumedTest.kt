package constructit

import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Scene3
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The 3D view shows outputs, not operands (OP-14 one level up): a solid another visible solid is
 * built from — a boolean's raw wall, a counterbore's cylinder — is that solid's construction
 * material, and drawing both painted two coincident shells z-fighting per pixel. Delete the
 * consumer and the operand is an output again.
 */
class Scene3ConsumedTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    @Test
    fun aBooleanOperandIsNotDrawnBesideItsResult() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(20.0, 0.0))
        ed.click(Vec2(21.0, 100.0))
        ed.finishPath()
        ed.activeScalar = ed.doc.newParameter("h", 50.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(15.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("w", 15.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(20.0, 50.0))
        ed.setTool(Tools.CUT_OPENINGS)
        ed.click(Vec2(15.0, 50.0))

        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "raw extrusion + cut result exist as elements")
        val scene = Scene3.extract(ed.doc)
        assertEquals(1, scene.solids.size, "the raw operand is not drawn beside the result it built")
        val cut = ed.doc.elements.last { it.kind == ElementKind.SOLID }
        assertEquals(cut.id, scene.solids.single().elementId)

        // delete the result: the raw extrusion is nobody's material any more, so it shows again
        ed.selectElement(cut)
        assertTrue(ed.deleteSelection())
        val after = Scene3.extract(ed.doc)
        assertEquals(1, after.solids.size)
        assertEquals(
            ed.doc.elements.single { it.kind == ElementKind.SOLID }.id,
            after.solids.single().elementId,
            "the operand is an output again once nothing consumes it",
        )
    }
}
