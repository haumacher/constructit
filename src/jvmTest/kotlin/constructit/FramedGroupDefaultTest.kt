package constructit

import constructit.editor.CreateMode
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.PointerButton
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.l10n.contains
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A group is framed by default** (OP-16 step 2), and *flat* is a purpose rather than a fallback.
 *
 * The create dialog's `movable (with frame)` tick is on, so confirming makes the group **and** places it as
 * one operation — one checkpoint, so one undo removes both, because giving a part its frame is not a second
 * thing the user did. Unticking is first-class and worded as its own intent: a flat group is the natural
 * **array original**, since the copies an array makes of it derive frame-free (a user-found use).
 *
 * These assert the dialog *model* and the editor's operations — nothing here knows the shell exists.
 */
class FramedGroupDefaultTest {
    private fun Editor.click(
        world: Vec2,
        additive: Boolean = false,
    ) {
        val s = camera.worldToScreen(world)
        pointerDown(s, PointerButton.PRIMARY, additive)
        pointerUp(s)
    }

    /** Two free points and the segment over them, all selected. */
    private fun selected(): Editor {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(-60.0, -20.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(60.0, 20.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(60.0, 20.0)))
        return ed
    }

    @Test
    fun theFrameTickIsOnByDefaultAndBothReadingsAreIntents() {
        val ed = selected()
        val d = assertNotNull(ed.beginCreate(CreateMode.GROUP))
        assertTrue(d.framed, "a group is nearly always a movable part, so the frame is the default")
        assertTrue(d.framedMeaning.contains("moves as one"), "got: ${d.framedMeaning}")
        assertTrue(d.flatMeaning.contains("array original"), "the flat reading is a purpose: ${d.flatMeaning}")
        assertFalse(d.flatMeaning.contains("cannot"), "…and is not phrased as a failure: ${d.flatMeaning}")
        assertTrue(d.help.render().contains(d.framedLabel.render()) && d.help.render().contains("array original"), "got: ${d.help}")

        // a tool takes no frame: an instance is *placed* by its anchor input (OP-6), which is a different idea
        ed.cancelCreate()
        assertFalse(assertNotNull(ed.beginCreate(CreateMode.TOOL)).framed)
    }

    /** Confirming with the tick on is **one** checkpoint: one undo removes the frame *and* the group. */
    @Test
    fun creatingAFramedGroupIsOneUndoStep() {
        val ed = selected()
        val before = DocumentFormat.save(ed.doc)
        val d = assertNotNull(ed.beginCreate(CreateMode.GROUP))
        d.name = "part"
        assertTrue(ed.confirmCreate())

        val g = ed.doc.groups.single()
        assertTrue(g.placed, "confirming created it *and* placed it: ${ed.statusHint}")
        val after = DocumentFormat.save(ed.doc)
        assertTrue(after.lines().any { it.startsWith("group \"part\"") }, after)
        assertTrue(after.lines().any { it.startsWith("place \"part\"") }, after)
        assertTrue(ed.statusHint.contains("Grouped 3 elements as part"), "got: ${ed.statusHint}")

        assertEquals(after, DocumentFormat.save(DocumentFormat.load(after)), "save -> load -> save is byte-identical")

        assertTrue(ed.undo())
        assertEquals(before, DocumentFormat.save(ed.doc), "one undo removes both halves")
        assertTrue(ed.doc.groups.isEmpty())
        assertTrue(ed.redo())
        assertEquals(after, DocumentFormat.save(ed.doc), "and one redo brings both back")
    }

    /** With the tick off the group is flat — and the status says that as an intent, not as a shortfall. */
    @Test
    fun untickingMakesAFlatGroupAndSaysSo() {
        val ed = selected()
        val d = assertNotNull(ed.beginCreate(CreateMode.GROUP))
        d.framed = false
        d.name = "original"
        assertTrue(ed.confirmCreate())

        val g = ed.doc.groups.single()
        assertFalse(g.placed, "no frame was asked for")
        assertTrue(ed.statusHint.contains("a named set, with no frame"), "got: ${ed.statusHint}")
        assertTrue(DocumentFormat.save(ed.doc).lines().none { it.startsWith("place ") }, "and none is recorded")
    }

    /**
     * **The flat group's purpose, cashed in:** it is the array original, and the copies derive frame-free —
     * which is also why the other order is refused (OP-16's as-built note), so this is the honest sequence.
     */
    @Test
    fun aFlatGroupIsTheArrayOriginalAndItsCopiesCarryNoFrame() {
        val ed = selected()
        val d = assertNotNull(ed.beginCreate(CreateMode.GROUP))
        d.framed = false
        d.name = "original"
        assertTrue(ed.confirmCreate())
        val g = ed.doc.groups.single()
        val elementsBefore = ed.doc.elements.size

        // the group as one operand (OP-16): select it as a whole, then array it
        ed.selectGroup(g)
        ed.count = 3
        ed.setTool(Tools.ARRAY_LINEAR)
        ed.click(Vec2(-40.0, 0.0)) // a member — the group is selected, so the group is the geometry
        ed.click(Vec2(-40.0, 60.0)) // the step vector: from here…
        ed.click(Vec2(-40.0, 120.0)) // …to here
        assertTrue(ed.doc.elements.size > elementsBefore, "the array built copies: ${ed.statusHint}")
        assertEquals(1, ed.doc.groups.size, "the copies are not grouped (a recorded cut) …")
        assertTrue(ed.doc.groups.single().placed.not(), "… and the original still carries no frame")
    }

    /**
     * **A refused placement is not a refused gesture.** A selection that owns no freedom at all cannot carry
     * a frame (three degrees of freedom moving nothing), so the group is created **flat** and the reason is
     * shown — the same sentence Place would give, arriving at the gesture that caused it.
     */
    @Test
    fun aRefusedFrameStillLeavesTheGroupWithTheReasonShown() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.MIDPOINT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(0.0, 0.0)) // the midpoint alone: derived, and its parents are left unticked
        val d = assertNotNull(ed.beginCreate(CreateMode.GROUP))
        d.candidates.forEach { it.checked = false }
        d.name = "nothing-to-move"
        assertTrue(ed.confirmCreate(), "the group is still made")

        val g = ed.doc.groups.single()
        assertFalse(g.placed, "but it has no frame")
        assertTrue(ed.statusHint.contains("Grouped 1 element as nothing-to-move"), "got: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("owns no degree of freedom"), "and says why: ${ed.statusHint}")
        assertTrue(DocumentFormat.save(ed.doc).lines().none { it.startsWith("place ") })
    }
}
