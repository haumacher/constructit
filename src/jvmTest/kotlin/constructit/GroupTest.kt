package constructit

import constructit.core.Evaluator
import constructit.dsl.PointRef
import constructit.dsl.point
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.PointerButton
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Flat named groups (OP-16 build order step 1): a named set at document level, organizational only —
 * no frame, no transform, and provably no effect on geometry or handles. Membership is recorded as a
 * `group` step, so it survives save/load, and a group is never left referring to something deleted.
 */
class GroupTest {
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

    /** Three free points; the outer two get grouped. */
    private fun grouped(name: String = ""): Editor {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(60.0, 0.0), additive = true)
        ed.groupSelection(name)
        return ed
    }

    private fun pos(
        ed: Editor,
        id: String,
    ) = Evaluator().point(ed.doc.elements.first { it.id == id }.ref as PointRef)

    @Test
    fun namesAreAutoNumberedUniqueAndOneWord() {
        val ed = grouped()
        assertEquals(listOf("group1"), ed.doc.groups.map { it.name })
        ed.click(Vec2(0.0, 0.0))
        assertEquals("group2", ed.groupSelection("")?.name, "a blank name auto-numbers")
        assertNull(ed.doc.createGroup("hall", emptyList()), "an empty group is refused")

        // a step's arguments are split on spaces, so a name is one word; a clash gets a number
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 60.0))
        ed.click(Vec2(30.0, 60.0))
        val fresh = ed.doc.freePoints.takeLast(2)
        assertEquals("west-wall", ed.doc.createGroup("west wall", listOf(fresh[0]))?.name)
        assertEquals("west-wall2", ed.doc.createGroup("west-wall", listOf(fresh[1]))?.name)
    }

    @Test
    fun clickingAMemberSelectsTheGroupAndClickingAgainReachesTheMember() {
        val ed = grouped("kitchen")
        ed.click(Vec2(0.0, 90.0)) // start from nothing selected

        ed.click(Vec2(-60.0, 0.0))
        assertEquals(2, ed.selectionCount, "clicking a member selects the whole group")
        assertEquals("e1", ed.selection?.id, "…with the clicked element as primary")
        assertTrue(ed.statusHint.contains("kitchen"), "got: ${ed.statusHint}")
        assertTrue(ed.selectionFields().isEmpty(), "two elements — no single handle to address")

        // a second click on the same element reaches it alone, so its fields stay reachable (OP-13)
        ed.click(Vec2(-60.0, 0.0))
        assertEquals(1, ed.selectionCount)
        assertEquals("e1", ed.selection?.id)
        assertEquals(listOf("x", "y"), ed.selectionFields().map { it.label })
        assertTrue(ed.statusHint.contains("alone"), "got: ${ed.statusHint}")

        // and a third goes back to the group
        ed.click(Vec2(-60.0, 0.0))
        assertEquals(2, ed.selectionCount)

        // an ungrouped element is unaffected by any of this
        ed.click(Vec2(0.0, 0.0))
        assertEquals(1, ed.selectionCount)
        assertEquals("", ed.statusHint)
    }

    /** Grouping must not touch geometry or handles: a reached member drags and types exactly as before. */
    @Test
    fun aMemberStillDragsAndTypesAfterGrouping() {
        val ed = grouped("kitchen")
        ed.drag(Vec2(60.0, 0.0), Vec2(75.0, 20.0))
        assertClose(pos(ed, "e3").x, 75.0)
        assertClose(pos(ed, "e3").y, 20.0)

        // reach the member, then write its field — the same node the drag wrote
        ed.click(Vec2(75.0, 20.0))
        ed.click(Vec2(75.0, 20.0))
        assertEquals(1, ed.selectionCount)
        assertTrue(ed.writeSelectionField(0, 80.0))
        assertClose(pos(ed, "e3").x, 80.0)
    }

    @Test
    fun ungroupDissolvesTheGroupAndKeepsItsElements() {
        val ed = grouped("kitchen")
        val g = ed.doc.groups.single()
        assertTrue(ed.ungroup(g))
        assertTrue(ed.doc.groups.isEmpty())
        assertEquals(3, ed.doc.freePoints.size, "the elements stay")
        assertTrue(ed.doc.journal.none { it.kind == "group" }, "and the step goes with it")

        // clicking a former member now selects just that element
        ed.click(Vec2(-60.0, 0.0))
        assertEquals(1, ed.selectionCount)
    }

    @Test
    fun anElementIsInAtMostOneGroup() {
        val ed = grouped("kitchen")
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(0.0, 0.0), additive = true)
        assertNull(ed.groupSelection("hall"), "e1 is already grouped")
        assertTrue(ed.statusHint.contains("already in group kitchen"), "got: ${ed.statusHint}")
        assertEquals(1, ed.doc.groups.size)
    }

    @Test
    fun groupingAndUngroupingAreEachOneUndoStep() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(60.0, 0.0), additive = true)
        val beforeGroup = DocumentFormat.save(ed.doc)

        ed.groupSelection("kitchen")
        val afterGroup = DocumentFormat.save(ed.doc)
        assertTrue(afterGroup != beforeGroup, "the group is in the script")

        assertTrue(ed.undo())
        assertEquals(beforeGroup, DocumentFormat.save(ed.doc))
        assertTrue(ed.doc.groups.isEmpty())
        assertTrue(ed.redo())
        assertEquals(afterGroup, DocumentFormat.save(ed.doc))
        assertEquals("kitchen", ed.doc.groups.single().name)

        ed.ungroup(ed.doc.groups.single())
        assertEquals(beforeGroup, DocumentFormat.save(ed.doc))
        assertTrue(ed.undo())
        assertEquals("kitchen", ed.doc.groups.single().name, "one undo restores the group")
    }

    @Test
    fun aGroupSurvivesSaveLoadSaveByteIdentically() {
        val ed = grouped("kitchen")
        // a group over mixed kinds, to prove membership is by element name and nothing else
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(-30.0, 0.0)) // the segment
        ed.click(Vec2(0.0, 0.0), additive = true)
        ed.groupSelection("hall")

        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.contains("group \"kitchen\" els=e1,e3"), "got:\n$once")
        val reloaded = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(reloaded), "save -> load -> save must be identical")
        assertEquals(listOf("kitchen", "hall"), reloaded.groups.map { it.name })
        assertEquals(listOf(2, 2), reloaded.groups.map { reloaded.groupMembers(it).size })
    }

    /**
     * Deleting a member leaves a **consistent** group: the member is silently gone, and a group whose
     * members are all gone disappears. The rule lives in one place — a `group` step is exempt from the
     * usual "references a dropped element" cascade and is written with the surviving members only — so
     * live delete and replay cannot disagree.
     */
    @Test
    fun deletingAMemberLeavesTheGroupConsistent() {
        val ed = grouped("kitchen")
        ed.click(Vec2(0.0, 90.0))
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(-60.0, 0.0)) // reach the member alone, so only it is deleted
        assertEquals(1, ed.selectionCount)
        assertTrue(ed.deleteSelection())

        val g = ed.doc.groups.single()
        assertEquals("kitchen", g.name)
        assertEquals(1, ed.doc.groupMembers(g).size, "the deleted member is simply not in it any more")
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "and the script still round-trips")

        // taking the last member with it takes the group
        ed.click(Vec2(60.0, 0.0))
        assertTrue(ed.deleteSelection())
        assertTrue(ed.doc.groups.isEmpty(), "a group with no members left does not exist")
        assertFalse(DocumentFormat.save(ed.doc).contains("group"), "and no group step is written")
    }

    /** The whole group can be deleted in one operation, because it is one multi-selection. */
    @Test
    fun deletingAGroupSelectionRemovesEveryMember() {
        val ed = grouped("kitchen")
        ed.click(Vec2(0.0, 90.0))
        ed.click(Vec2(-60.0, 0.0)) // selects both members
        assertEquals(2, ed.selectionCount)
        assertTrue(ed.deleteSelection())
        assertEquals(1, ed.doc.freePoints.size)
        assertTrue(ed.doc.groups.isEmpty())
    }

    @Test
    fun theGroupsPanelViewHidesAndShowsEveryMember() {
        val ed = grouped("kitchen")
        val g = ed.doc.groups.single()
        assertTrue(ed.isGroupVisible(g))
        ed.setGroupVisible(g, false)
        assertEquals(listOf("e2"), ed.doc.elements.filter { it.visible }.map { it.id })
        assertFalse(ed.isGroupVisible(g))
        ed.setGroupVisible(g, true)
        assertTrue(ed.doc.elements.all { it.visible })

        // and selecting from the panel is the same selection a canvas click makes
        ed.selectGroup(g)
        assertEquals(2, ed.selectionCount)
        assertEquals(ElementKind.POINT, ed.selection?.kind)
    }
}
