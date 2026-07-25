package constructit

import constructit.core.Evaluator
import constructit.dsl.PointRef
import constructit.dsl.point
import constructit.dsl.scalar
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Undo/redo over document-format snapshots (OP-18): one snapshot per committed user-level
 * operation, restored by replaying the script. The load-bearing assertion is that undo walks back
 * through the *exact* saved texts the edits produced — undo can never invent a state that saving
 * would not have written.
 */
class UndoRedoTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
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

    private fun save(ed: Editor) = DocumentFormat.save(ed.doc)

    @Test
    fun undoWalksBackThroughTheExactSavedStatesAndRedoForwardAgain() {
        val ed = Editor()
        val states = ArrayList<String>()
        states += save(ed)
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        states += save(ed)
        ed.click(Vec2(40.0, 0.0))
        states += save(ed)
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        states += save(ed)

        for (i in states.size - 2 downTo 0) {
            assertTrue(ed.undo())
            assertEquals(states[i], save(ed), "undo must land on the exact earlier script")
        }
        assertFalse(ed.canUndo)
        assertFalse(ed.undo())
        for (i in 1 until states.size) {
            assertTrue(ed.redo())
            assertEquals(states[i], save(ed), "redo must land on the exact later script")
        }
        assertFalse(ed.canRedo)
    }

    @Test
    fun undoRestoresADraggedPositionAndRedoTheDrag() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(0.0, 0.0), Vec2(30.0, -10.0))

        fun p(): Vec2 = Evaluator().point(ed.doc.elements.first { it.kind == ElementKind.POINT }.ref as PointRef)
        assertClose(p().x, 30.0)
        assertClose(p().y, -10.0)
        assertTrue(ed.undo())
        assertClose(p().x, 0.0)
        assertClose(p().y, 0.0)
        assertTrue(ed.redo())
        assertClose(p().x, 30.0)
        assertClose(p().y, -10.0)
    }

    @Test
    fun aDragThatChangesNothingPushesNoSnapshot() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(0.0, 0.0)) // grab and release in place: selects, moves nothing
        assertTrue(ed.undo(), "only the point creation is on the stack")
        assertFalse(ed.canUndo)
    }

    @Test
    fun aCancelledToolPushesNoSnapshot() {
        val ed = Editor()
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, 0.0)) // first slot only
        ed.key("Escape")
        assertFalse(ed.canUndo, "an operation that never committed is not an undo step")
    }

    @Test
    fun anOrthoPathUndoesAsOneStep() {
        val ed = Editor()
        val empty = save(ed)
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(50.0, 2.0))
        ed.click(Vec2(48.0, 30.0))
        ed.finishPath()
        val done = save(ed)

        assertTrue(ed.undo())
        assertEquals(empty, save(ed))
        assertTrue(ed.doc.orthoPaths.isEmpty())
        assertFalse(ed.canUndo, "start and every leg are one operation")
        assertTrue(ed.redo())
        assertEquals(done, save(ed))
        assertEquals(2, ed.doc.orthoPaths.single().legCount)
    }

    @Test
    fun aWeldUndoesAsOneStepWithTheDragThatMadeIt() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(0.0, 0.0), Vec2(30.0, 0.0)) // magnet welds on release
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.POINT && it.visible })

        assertTrue(ed.undo())
        val points = ed.doc.elements.filter { it.kind == ElementKind.POINT }
        assertEquals(2, points.count { it.visible }, "the weld and its drag revert together")
        assertTrue(points.none { ed.doc.isWelded(it) })
        assertClose(Evaluator().point(points[0].ref as PointRef).x, 0.0)

        assertTrue(ed.redo())
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.POINT && it.visible })
    }

    @Test
    fun breakAndJoinEachUndoAsOneStep() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 2.0))
        ed.finishPath()
        val drawn = save(ed)
        ed.setTool(Tools.BREAK_LEG)
        ed.click(Vec2(60.0, 1.0))
        val broken = save(ed)
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(80.0, 0.0), Vec2(80.0, -25.0)) // pull the jog open
        val open = save(ed)
        ed.drag(Vec2(80.0, -25.0), Vec2(80.0, 0.0)) // flatten it -> joins on release
        assertTrue(save(ed).contains("orthojoin"))
        assertEquals(1, ed.doc.orthoPaths.single().legCount)

        assertTrue(ed.undo())
        assertEquals(open, save(ed), "the join (with its drag) is one step")
        assertTrue(ed.undo())
        assertEquals(broken, save(ed), "the opening drag is one step")
        assertTrue(ed.undo())
        assertEquals(drawn, save(ed), "the break is one step")
        assertEquals(1, ed.doc.orthoPaths.single().legCount)
    }

    /**
     * An interval feature's values are ordinary parameters, so editing one commits and undoes like every
     * other panel edit. It only works because the interval step *restates* what it introduced (OP-18):
     * while an opening's position lived in a parameter no step described, the edit was invisible to the
     * saved script and therefore to undo.
     */
    @Test
    fun anOpeningPositionEditIsOneUndoStep() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 3.0))
        ed.finishPath()
        ed.activeScalar = ed.doc.newParameter("w", 20.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(50.0, 0.0))
        val placed = save(ed)

        val pos = ed.doc.scalars.first { it.name == "pos" }
        ed.doc.setParameter(pos, 10.0.mm)
        ed.checkpoint()

        fun position() = Evaluator().scalar(pos.ref).mm
        assertClose(position(), 10.0)
        assertTrue(ed.undo())
        assertEquals(placed, save(ed), "the position edit is one step")
        assertClose(Evaluator().scalar(ed.doc.scalars.first { it.name == "pos" }.ref).mm, 40.0)
        assertTrue(ed.redo())
        assertClose(Evaluator().scalar(ed.doc.scalars.first { it.name == "pos" }.ref).mm, 10.0)
    }

    @Test
    fun aTypedFieldWriteIsOneUndoStep() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(10.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(10.0, 0.0)) // select it
        assertTrue(ed.writeSelectionField(0, 55.0))

        fun x(): Double = Evaluator().point(ed.doc.elements.first { it.kind == ElementKind.POINT }.ref as PointRef).x
        assertClose(x(), 55.0)
        assertTrue(ed.undo())
        assertClose(x(), 10.0)
        assertTrue(ed.redo())
        assertClose(x(), 55.0)
    }

    @Test
    fun undoMidPathDiscardsOnlyTheUncommittedPath() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-60.0, 0.0))
        val committed = save(ed)
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(50.0, 2.0)) // still drawing — nothing committed yet

        assertTrue(ed.undo())
        assertEquals(committed, save(ed), "the first undo discards the in-progress work only")
        assertTrue(ed.doc.orthoPaths.isEmpty())
        assertFalse(ed.canRedo, "work that never committed has no snapshot to redo")
        assertTrue(ed.canUndo, "the committed point is still on the stack")
    }

    @Test
    fun aNewEditAfterUndoClearsTheRedoStack() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        assertTrue(ed.undo())
        assertTrue(ed.canRedo)
        ed.click(Vec2(0.0, 40.0)) // a new edit forks the history
        assertFalse(ed.canRedo)
        assertFalse(ed.redo())
    }
}
