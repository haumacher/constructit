package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.FrameValue
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.PatternKind
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A group's name is editable** (queue #18 item 4a) — the parameter-rename pattern (OP-7) applied one level
 * up, and the two things it had to get right.
 *
 * (a) The name is written in **two** steps, `group` and `place` (OP-16), and both restate the current one.
 * (b) The writer resolves a placement's group by **step identity**, not by the recorded label — which was a
 * latent defect the rename exposed: with the two out of step the frame stopped being restated and a placed
 * group's position was lost on the next save.
 *
 * And the thing that is *not* affected, asserted so nobody has to wonder: a **pattern's** name (OP-23) lives
 * in its own namespace, and no pattern or orbit step ever names a group.
 */
class GroupRenameTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
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

    /** Three free points, grouped as "kitchen". */
    private fun grouped(framed: Boolean): Editor {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(20.0, 0.0))
        ed.click(Vec2(20.0, 15.0))
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(-10.0, -10.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(30.0, 25.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(30.0, 25.0)))
        val g = ed.groupSelection("kitchen")!!
        if (framed) ed.placeGroup(g)
        ed.checkpoint()
        return ed
    }

    @Test
    fun renamingAGroupRestatesItsStep() {
        val ed = grouped(framed = false)
        val g = ed.doc.groups.first()
        assertEquals("larder", ed.renameGroup(g, "larder"))
        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("group \"larder\""), "the declaring step writes the name it has now; got:\n$text")
        assertTrue(!text.contains("kitchen"), "…and only that one")
        val back = DocumentFormat.load(text)
        assertEquals("larder", back.groups.first().name, "a reload brings the new name back")
        assertEquals(text, DocumentFormat.save(back), "save -> load -> save stays byte-equal")
    }

    @Test
    fun aPlacedGroupKeepsBothItsNameAndItsFrame() {
        val ed = grouped(framed = true)
        val g = ed.doc.groups.first()
        // move it, so the frame carries a value only a restate can preserve
        ed.drag(Vec2(20.0, 0.0), Vec2(45.0, 8.0))
        assertEquals("larder", ed.renameGroup(g, "larder"))

        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("group \"larder\""), "the group step follows the rename; got:\n$text")
        assertTrue(text.contains("place \"larder\""), "and so does the placement, which names it too")

        val back = DocumentFormat.load(text)
        val placed = back.groups.first()
        assertEquals("larder", placed.name)
        assertTrue(placed.placed, "it is still a placed group")
        // the frame is the thing the `place` step exists to restate: a lookup by the *old* name would have
        // silently reverted it to where the group was first placed
        val was = (Evaluator().eval(g.frameNode!!) as EvalResult.Ok).value as FrameValue
        val now = (Evaluator().eval(placed.frameNode!!) as EvalResult.Ok).value as FrameValue
        assertClose(now.origin.x, was.origin.x, 1e-9, "the frame came back where it was")
        assertClose(now.origin.y, was.origin.y, 1e-9)
        assertEquals(text, DocumentFormat.save(back), "save -> load -> save stays byte-equal")
    }

    @Test
    fun aRenameIsUniquifiedAndOneWord() {
        val ed = grouped(framed = false)
        val g = ed.doc.groups.first()
        assertEquals("west-wing", ed.renameGroup(g, "west wing"), "one word, since a step's arguments split on spaces")
        assertEquals("west-wing", ed.renameGroup(g, "   "), "a blank field keeps the old name")

        // a second group, then a clash
        ed.setTool(Tools.POINT)
        ed.click(Vec2(80.0, 80.0))
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(80.0, 80.0))
        val other = ed.groupSelection("annex")!!
        assertEquals("west-wing2", ed.renameGroup(other, "west-wing"), "a clash gets a suffix, as a parameter's does")
    }

    @Test
    fun aPatternsNameIsUntouchedByAGroupRename() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(25.0, 0.0))
        ed.count = 4
        ed.setTool(Tools.PATTERN_CIRCULAR)
        ed.click(Vec2(0.0, 0.0)) // centre
        ed.click(Vec2(25.0, 0.0)) // reference
        assertEquals(PatternKind.CIRCULAR, ed.doc.patterns.first().kind)
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(25.0, 0.0))
        val g = ed.groupSelection("ring")!!
        ed.checkpoint()

        assertEquals("hub", ed.renameGroup(g, "hub"))
        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("pattern \"P1\""), "a pattern is named in its own namespace; got:\n$text")
        assertTrue(text.contains("group \"hub\""), "and the group's rename reaches only the group's steps")
        DocumentFormat.load(text) // both still resolve
    }
}
