package constructit

import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **An element can carry a name of its own** (queue #18 item 4b, OP-7's "nodes get names" one level up).
 *
 * The rules under test are the ones the parameter rename established (OP-7) and the naming authority fixed
 * (OP-18): the name is a **recorded step**, so it survives save/load and undoes like anything else; the
 * *script* name stays the identity everywhere; a second rename restates the one step rather than adding
 * another; clearing drops the step; and deleting the element takes the name with it, through the ordinary
 * reference rule and no special case.
 */
class ElementNameTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    /** Two points and a segment over them: `e1`, `e2`, `e3`. */
    private fun drawing(): Editor {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.checkpoint()
        return ed
    }

    private fun segment(ed: Editor) = ed.doc.elements.first { it.kind == ElementKind.SEGMENT }

    @Test
    fun aNamedElementShowsItsNameAndKeepsItsScriptName() {
        val ed = drawing()
        val seg = segment(ed)
        assertEquals("bore-axis", ed.nameElement(seg, "bore-axis"))
        assertEquals("bore-axis", ed.doc.userNameOf(seg))
        // the script name stays the identity — the display puts the label in front of it, never instead
        assertEquals("e3", ed.doc.nameOf(seg))
        assertEquals("bore-axis (e3)", ed.doc.displayName(seg))
        assertEquals("segment bore-axis (e3)", ed.selectionLabelOf(seg))
    }

    private fun Editor.selectionLabelOf(el: constructit.editor.Element): String {
        selectElement(el)
        return selectionLabel()
    }

    @Test
    fun theNameIsAStepAndSurvivesSaveAndLoad() {
        val ed = drawing()
        ed.nameElement(segment(ed), "bore-axis")
        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.lines().any { it == "name e3 \"bore-axis\"" }, "the step says it plainly; got:\n$text")

        val back = DocumentFormat.load(text)
        val seg = back.elements.first { it.kind == ElementKind.SEGMENT }
        assertEquals("bore-axis", back.userNameOf(seg), "and a reload brings it back")
        assertEquals(text, DocumentFormat.save(back), "save -> load -> save is byte-equal")
    }

    @Test
    fun renamingRestatesTheOneStepRatherThanAddingASecond() {
        val ed = drawing()
        val seg = segment(ed)
        ed.nameElement(seg, "bore-axis")
        val steps = ed.doc.journal.count { it.kind == "name" }
        assertEquals("spindle", ed.nameElement(seg, "spindle"))
        assertEquals(steps, ed.doc.journal.count { it.kind == "name" }, "a rename is state, not a second step")
        assertTrue(DocumentFormat.save(ed.doc).contains("name e3 \"spindle\""), "and the file says the new one")
        assertTrue(!DocumentFormat.save(ed.doc).contains("bore-axis"), "…only the new one")
    }

    @Test
    fun clearingTheNameDropsTheStep() {
        val ed = drawing()
        val seg = segment(ed)
        ed.nameElement(seg, "bore-axis")
        assertEquals("", ed.nameElement(seg, "  "))
        assertNull(ed.doc.userNameOf(seg))
        assertEquals(0, ed.doc.journal.count { it.kind == "name" }, "nothing is left for the step to say")
        assertEquals("e3", ed.doc.displayName(seg))
    }

    @Test
    fun namesAreOneWordAndUnique() {
        val ed = drawing()
        val a = ed.doc.elements.first { ed.doc.nameOf(it) == "e1" }
        val b = ed.doc.elements.first { ed.doc.nameOf(it) == "e2" }
        assertEquals("bore-axis", ed.nameElement(a, "bore axis"), "spaces become one word, as a scalar's do")
        assertEquals("bore-axis2", ed.nameElement(b, "bore axis"), "a clash gets a suffix, as a scalar's does")
        assertEquals("bore-axis", ed.nameElement(a, "bore-axis"), "renaming to the name it already holds is a no-op")
    }

    @Test
    fun deletingTheElementTakesItsNameStep() {
        val ed = drawing()
        val seg = segment(ed)
        ed.nameElement(seg, "bore-axis")
        ed.selectElement(seg)
        assertTrue(ed.deleteSelection(), "the segment goes")
        val text = DocumentFormat.save(ed.doc)
        assertTrue(!text.contains("name "), "and its name step with it, through the ordinary reference rule; got:\n$text")
        DocumentFormat.load(text) // the remaining script still replays
    }

    @Test
    fun namingIsOneUndoStep() {
        val ed = drawing()
        val seg = segment(ed)
        ed.nameElement(seg, "bore-axis")
        assertTrue(ed.canUndo)
        ed.undo()
        assertNull(ed.doc.elements.first { it.kind == ElementKind.SEGMENT }.let { ed.doc.userNameOf(it) })
        ed.redo()
        assertEquals("bore-axis", ed.doc.elements.first { it.kind == ElementKind.SEGMENT }.let { ed.doc.userNameOf(it) })
    }
}
