package constructit

import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **Visibility is part of the file** — the reversal of a decision OP-18 recorded (see
 * `Document.setElementsVisible`).
 *
 * The old rationale was that the file is a construction and has no viewing section. It is a construction the
 * user has *arranged*, though, and reopening a drawing with every hidden helper line back on top of the
 * result is data loss from the user's chair. So hide/show is a recorded step — one per gesture, batched over
 * the selection — which makes it survive save/load, undo like everything else, and follow the same
 * member-deletion rules a `group` step follows.
 *
 * What is deliberately *not* recorded: a welded alias, which is hidden **by construction** and not by a
 * decision anyone made.
 */
class VisibilityTest {
    private fun Editor.click(
        world: Vec2,
        additive: Boolean = false,
    ) {
        val s = camera.worldToScreen(world)
        pointerDown(s, constructit.editor.PointerButton.PRIMARY, additive)
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

    /** Three circles, so there is something to hide that is not a point. */
    private fun scene(): Editor {
        val ed = Editor()
        ed.setTool(Tools.CIRCLE_R)
        ed.activeScalar = ed.doc.newParameter("r", 10.0.let { constructit.units.Quantity.mm(it) })
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        return ed
    }

    @Test
    fun hidingTwoElementsSurvivesSaveAndLoad() {
        val ed = scene()
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(-40.0, 10.0)) // on the first circle
        ed.click(Vec2(0.0, 10.0), additive = true)
        assertEquals(2, ed.setSelectionVisible(false))

        val text = DocumentFormat.save(ed.doc)
        assertEquals(1, text.lines().count { it.startsWith("hide ") }, "one step for the whole selection: $text")
        val reloaded = DocumentFormat.load(text)
        assertEquals(2, reloaded.elements.count { !it.visible }, "the hidden circles come back hidden")
        assertEquals(
            ed.doc.elements.map { it.visible },
            reloaded.elements.map { it.visible },
            "and exactly the same ones",
        )
        assertEquals(text, DocumentFormat.save(reloaded), "save -> load -> save must be identical")
    }

    @Test
    fun showingIsARecordedStepToo() {
        val ed = scene()
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(-40.0, 10.0))
        ed.setSelectionVisible(false)
        // the hidden circle is not pickable, so the panel route selects it — which is what a user does
        ed.selectElement(ed.doc.elements.first { it.kind == ElementKind.CIRCLE })
        assertEquals(1, ed.setSelectionVisible(true))

        val text = DocumentFormat.save(ed.doc)
        assertEquals(1, text.lines().count { it.startsWith("hide ") })
        assertEquals(1, text.lines().count { it.startsWith("show ") })
        assertTrue(DocumentFormat.load(text).elements.all { it.visible }, "the show wins, being later")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "byte-equal round trip")
    }

    @Test
    fun aNoOpHideRecordsNothing() {
        val ed = scene()
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(-40.0, 10.0))
        assertEquals(1, ed.setSelectionVisible(false))
        assertEquals(0, ed.setSelectionVisible(false), "hiding what is already hidden changes nothing")
        assertEquals(
            1,
            DocumentFormat.save(ed.doc).lines().count { it.startsWith("hide ") },
            "and records no second step",
        )
    }

    /**
     * Deleting a hidden element leaves the hide step consistent — the same rule a `group` step follows: the
     * step names the survivors, and goes only when none are left.
     */
    @Test
    fun deletingAHiddenElementKeepsTheStepConsistent() {
        val ed = scene()
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(-40.0, 10.0))
        ed.click(Vec2(0.0, 10.0), additive = true)
        ed.setSelectionVisible(false)

        ed.selectElement(ed.doc.elements.first { it.kind == ElementKind.CIRCLE })
        assertTrue(ed.deleteSelection())
        val text = DocumentFormat.save(ed.doc)
        assertEquals(1, text.lines().count { it.startsWith("hide ") }, "the step survives with its other member: $text")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "and still replays byte for byte")

        // now the last hidden element goes too: nothing is left to hide, so no step is written
        ed.selectElement(ed.doc.elements.first { !it.visible })
        assertTrue(ed.deleteSelection())
        val bare = DocumentFormat.save(ed.doc)
        assertEquals(0, bare.lines().count { it.startsWith("hide ") }, "a step with no members left is gone: $bare")
        assertEquals(bare, DocumentFormat.save(DocumentFormat.load(bare)))
    }

    /** A group's toggle is the same per-element step over its members — one rule, one encoding. */
    @Test
    fun aGroupToggleRecordsPerElementSteps() {
        val ed = scene()
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(-60.0, -20.0), Vec2(20.0, 20.0)) // marquee the first two circles
        val g = ed.groupSelection("helpers")
        assertTrue(g != null)
        ed.setGroupVisible(g!!, false)
        assertTrue(ed.doc.groupMembers(g).none { it.visible })
        assertFalse(ed.isGroupVisible(g))

        val text = DocumentFormat.save(ed.doc)
        assertEquals(1, text.lines().count { it.startsWith("hide ") }, "the members, not the group: $text")
        val reloaded = DocumentFormat.load(text)
        assertEquals(
            ed.doc.elements.count { !it.visible },
            reloaded.elements.count { !it.visible },
            "every member the toggle hid is hidden after a reload",
        )
        assertEquals(text, DocumentFormat.save(reloaded))

        assertTrue(ed.undo(), "and the toggle is one undo step")
        assertTrue(ed.doc.elements.all { it.visible })
    }

    /** A welded alias is hidden by construction, so nothing about it is recorded and it is never shown. */
    @Test
    fun aWeldedAliasIsNotARecordedHide() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(30.0, 0.0), Vec2(0.0, 0.0)) // weld the second point onto the first
        val alias = ed.doc.elements.last { it.isPoint }
        assertFalse(alias.visible, "a welded alias hides by construction")

        val text = DocumentFormat.save(ed.doc)
        assertEquals(0, text.lines().count { it.startsWith("hide ") }, "which is not a decision, so not a step: $text")
        ed.selectElement(alias)
        assertEquals(0, ed.setSelectionVisible(true), "and showing it is refused")
        assertEquals(text, DocumentFormat.save(ed.doc))
    }
}
