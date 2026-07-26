package constructit

import constructit.core.CircleValue
import constructit.core.Evaluator
import constructit.dsl.valueOf
import constructit.editor.CreateMode
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
import kotlin.test.assertTrue

/**
 * Probes for the typed-scalar mechanism against consumers it was not written for: a USER-recorded
 * macro tool's scalar slot, and undo across a typed-parameter tool application. One mechanism for
 * all scalar slots means all — including the slots a user invents after the mechanism shipped.
 */
class TypedScalarProbeTest {
    private fun Editor.click(
        world: Vec2,
        additive: Boolean = false,
    ) {
        val s = camera.worldToScreen(world)
        pointerDown(s, PointerButton.PRIMARY, additive)
        pointerUp(s)
    }

    private fun Editor.type(value: String) {
        value.forEach { assertTrue(key(it.toString()), "digit '$it'") }
        assertTrue(key("Enter"))
    }

    /** A recorded tool's scalar slot takes a typed value like any built-in slot. */
    @Test
    fun aUserMacroToolsScalarSlotTakesATypedValue() {
        val ed = Editor()
        // record: two points + a circle of parameter r at the second point
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        ed.activeScalar = ed.doc.newParameter("r", 5.0.mm)
        ed.setTool(Tools.CIRCLE_R)
        ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(-20.0, -20.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(60.0, 20.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(60.0, 20.0)))
        val d = assertNotNull(ed.beginCreate(CreateMode.TOOL))
        d.name = "stud"
        assertTrue(ed.confirmCreate())
        val tool = ed.doc.macros.last().toolId

        // stamp it, typing the radius instead of picking a panel parameter
        ed.setTool(tool)
        ed.type("9")
        ed.click(Vec2(100.0, 50.0))
        ed.click(Vec2(140.0, 50.0))

        val stamped = ed.doc.elements.filter { it.kind == ElementKind.CIRCLE }.drop(1)
        assertEquals(1, stamped.size, "one stamped instance circle")
        assertClose((Evaluator().valueOf(stamped[0].ref) as CircleValue).circle.radius, 9.0, msg = "the typed 9 fed the macro's scalar slot")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "typed scalar + macro instance replays")
    }

    /** Undo across a typed-scalar tool application restores the pre-tool state in one step. */
    @Test
    fun undoAfterATypedScalarToolIsOneStep() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        val before = DocumentFormat.save(ed.doc)
        ed.setTool(Tools.CIRCLE_R)
        ed.type("7")
        ed.click(Vec2(0.0, 0.0))
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.CIRCLE })

        assertTrue(ed.undo())
        assertEquals(before, DocumentFormat.save(ed.doc), "the typed parameter and the circle undo together, or in cleanly separate steps ending at the pre-tool state")
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.CIRCLE })
    }

    /** A typed value whose tool never completes is retracted — it must not leak into a later undo step. */
    @Test
    fun aTypedScalarWhoseToolIsCancelledIsRetracted() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        val before = DocumentFormat.save(ed.doc)
        ed.setTool(Tools.CIRCLE_R)
        ed.type("7") // the parameter exists now, pending its tool...
        ed.setTool(Tools.SELECT) // ...which is cancelled instead

        assertEquals(before, DocumentFormat.save(ed.doc), "the orphaned parameter is retracted with its picks")
        assertTrue(ed.doc.scalars.none { it.name.startsWith("radius") })

        // and a panel-created parameter is untouched by cancellation — only the typed path retracts
        val named = ed.doc.newParameter("keepme", 4.0.mm)
        ed.activeScalar = named
        ed.checkpoint()
        ed.setTool(Tools.CIRCLE_R)
        ed.setTool(Tools.SELECT)
        assertTrue(ed.doc.scalars.any { it === named }, "a deliberate panel parameter survives a cancelled tool")
    }
}
