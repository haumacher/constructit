package constructit

import constructit.core.Evaluator
import constructit.core.PointValue
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
import kotlin.test.assertTrue

/**
 * Probes on the drag-subject and rename work: a drag into group B while group A holds the whole-
 * group selection, and a rename that collides with the typed-scalar auto-namer. Neither situation
 * was named in the implementation brief.
 */
class PolishProbeTest {
    private fun Editor.click(
        world: Vec2,
        additive: Boolean = false,
    ) {
        val s = camera.worldToScreen(world)
        pointerDown(s, PointerButton.PRIMARY, additive)
        pointerUp(s)
    }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    /** Selecting group A as a whole must not turn a drag in group B into B's frame drag. */
    @Test
    fun aDragInGroupBWhileGroupAIsSelectedMovesOnlyTheElement() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(-20.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(80.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(-20.0, 0.0), additive = true)
        val a = assertNotNull(ed.groupSelection("a"))
        assertTrue(ed.placeGroup(a))
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(80.0, 0.0), additive = true)
        val b = assertNotNull(ed.groupSelection("b"))
        assertTrue(ed.placeGroup(b))

        // select group A as a whole (click a member), then DRAG a member of B
        ed.click(Vec2(-60.0, 0.0))
        ed.drag(Vec2(40.0, 0.0), Vec2(40.0, 30.0))

        fun at(i: Int): Vec2 {
            val pts = ed.doc.elements.filter { it.kind == ElementKind.POINT }
            return (Evaluator().valueOf(pts[i].ref) as PointValue).p
        }
        assertClose(at(2).y, 30.0, msg = "the dragged member of B moved")
        assertClose(at(3).y, 0.0, msg = "B's other member did not — the element moved, not B's frame")
        assertClose(at(0).y, 0.0, msg = "A untouched")
        assertClose(at(1).y, 0.0)
    }

    /** A rename can take the very name the typed-scalar path would auto-create next; both survive. */
    @Test
    fun aRenameCollidingWithTheAutoNamerStaysUnique() {
        val ed = Editor()
        val r = ed.doc.newParameter("r", 4.0.mm)
        ed.checkpoint() // the panel's add button commits through this same seam
        assertEquals("radius", ed.renameParameter(r, "radius"), "rename to the auto-name the circle tool would pick")
        // now the typed-scalar path wants "radius" too: it must uniquify, not clash
        ed.setTool(Tools.CIRCLE_R)
        "7".forEach { ed.key(it.toString()) }
        ed.key("Enter")
        ed.click(Vec2(0.0, 0.0))
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.CIRCLE })
        val names = ed.doc.scalars.map { it.name }.sorted()
        assertEquals(listOf("radius", "radius2"), names, "the auto-namer respected the renamed entry")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "rename + auto-name round-trips")
        assertTrue(ed.undo(), "the circle+radius2 undo")
        assertTrue(ed.undo(), "the rename undoes too")
        assertEquals(listOf("r"), ed.doc.scalars.map { it.name }, "back to the original name")
    }
}
