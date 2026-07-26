package constructit

import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.core.SegmentValue
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Group
import constructit.editor.PointerButton
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A whole group as one operand of a tool** (OP-16), on the report "I cannot create a circular array
 * from a group as input".
 *
 * The interaction rule is the one the drag subject already follows: *a group acts as a whole only when
 * selected as a whole.* So a geometry-slot click on a member arrays the **group** while the group is what
 * is selected, and arrays that element alone otherwise — the old behaviour, which is pinned here too,
 * because grouping is invisible until something of it is selected and a click must never copy more than
 * the user can see.
 *
 * What the *file* records is unchanged in kind: the geometry slot's picks, which are now several
 * elements (`els=e1,e3`). The group is a fact about the gesture, not about the construction, so nothing
 * in the step names it — which is why ungrouping afterwards cannot orphan an array and why the delete
 * cascade needs no new rule.
 */
class GroupArrayTest {
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

    private fun points(doc: Document): List<Vec2> {
        val ev = Evaluator()
        return doc.elements.filter { it.kind == ElementKind.POINT }.mapNotNull { (ev.valueOf(it.ref) as? PointValue)?.p }
    }

    private fun segments(doc: Document): List<constructit.geom.Segment> {
        val ev = Evaluator()
        return doc.elements.filter { it.kind == ElementKind.SEGMENT }.mapNotNull { (ev.valueOf(it.ref) as? SegmentValue)?.seg }
    }

    private fun rot(
        p: Vec2,
        deg: Double,
    ): Vec2 {
        val a = deg * PI / 180.0
        return Vec2(p.x * cos(a) - p.y * sin(a), p.x * sin(a) + p.y * cos(a))
    }

    private fun assertHasPoint(
        doc: Document,
        want: Vec2,
    ) {
        val got = points(doc)
        assertTrue(got.any { (it - want).length() < 1e-6 }, "no point at $want; got $got")
    }

    private fun assertRoundTrips(ed: Editor) {
        val once = DocumentFormat.save(ed.doc)
        val reloaded = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(reloaded), "save -> load -> save must be identical")
        assertEquals(ed.doc.elements.map { it.kind }, reloaded.elements.map { it.kind }, "same element kinds")
    }

    /** Two free points at (40,0) and (0,40), grouped — so the group is selected **as a whole**. */
    private fun twoPointGroup(count: Int): Pair<Editor, Group> {
        val ed = Editor()
        ed.count = count
        ed.setTool(Tools.POINT)
        ed.click(Vec2(40.0, 0.0)) // e1
        ed.click(Vec2(0.0, 40.0)) // e2
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(0.0, 40.0), additive = true)
        val g = ed.groupSelection("kitchen")!!
        assertEquals(g, ed.selectedGroup, "grouping leaves the group selected as a whole")
        return ed to g
    }

    // ---- the canvas rule ----

    /**
     * The report, fixed: **N = 3 of a two-element group is 4 new copies** — the count is "instances
     * *including* the original" (as for a single element), and each instance is the whole group.
     */
    @Test
    fun aMemberClickArraysTheWholeSelectedGroup() {
        val (ed, g) = twoPointGroup(count = 3)
        ed.setTool(Tools.ARRAY_CIRCULAR)
        ed.click(Vec2(40.0, 0.0)) // a member — the group is selected, so the group is the geometry
        assertTrue(ed.statusHint.contains("Group kitchen (2 elements)"), "the status line narrates it; got: ${ed.statusHint}")
        ed.click(Vec2(0.0, 0.0)) // the centre of rotation (a fresh free point)

        // 2 originals + the centre point + 4 copies (2 further instances of 2 members)
        assertEquals(7, points(ed.doc).size, "count 3 of a 2-element group is 4 new copies; got ${points(ed.doc)}")
        for (k in 1..2) {
            assertHasPoint(ed.doc, rot(Vec2(40.0, 0.0), 120.0 * k))
            assertHasPoint(ed.doc, rot(Vec2(0.0, 40.0), 120.0 * k))
        }
        assertTrue(ed.statusHint.contains("group kitchen's 2 elements"), "and says what it built; got: ${ed.statusHint}")

        // one step, and its geometry slot names both members
        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("tool arraycircular pts=e3 els=e1,e2 clicks=40,0;0,0 count=3 -> e4,e5,e6,e7"), "got:\n$text")
        assertEquals(1, ed.doc.journal.count { it.kind == "tool" })
        assertRoundTrips(ed)
        assertEquals(2, ed.doc.groupMembers(g).size, "arraying a group does not change its membership")
    }

    @Test
    fun aLinearArrayOfAGroupStepsEveryMember() {
        val (ed, _) = twoPointGroup(count = 3)
        ed.setTool(Tools.ARRAY_LINEAR)
        ed.click(Vec2(0.0, 40.0)) // any member will do — the group is the operand
        ed.click(Vec2(-60.0, -60.0)) // step vector: from
        ed.click(Vec2(-35.0, -60.0)) // to — 25 mm along +x

        // 2 originals + 2 vector points + 4 copies
        assertEquals(8, points(ed.doc).size, "got ${points(ed.doc)}")
        for (k in 1..2) {
            assertHasPoint(ed.doc, Vec2(40.0 + 25.0 * k, 0.0))
            assertHasPoint(ed.doc, Vec2(25.0 * k, 40.0))
        }
        assertRoundTrips(ed)
    }

    /**
     * The old behaviour, pinned: without a whole-group selection a member click means **that element**.
     * Both ways of not having one are checked — a member deliberately reached alone, and a group nobody
     * has selected at all.
     */
    @Test
    fun aMemberClickWithoutAWholeGroupSelectionArraysThatElementAlone() {
        val (ed, _) = twoPointGroup(count = 3)
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(40.0, 0.0)) // the group again…
        ed.click(Vec2(40.0, 0.0)) // …and once more: the member alone
        assertNull(ed.selectedGroup, "the group is no longer addressed as a whole")

        ed.setTool(Tools.ARRAY_CIRCULAR)
        ed.click(Vec2(40.0, 0.0))
        assertFalse(ed.statusHint.contains("Group kitchen"), "no group note; got: ${ed.statusHint}")
        ed.click(Vec2(0.0, 0.0))
        // 2 originals + centre + 2 copies of e1 only
        assertEquals(5, points(ed.doc).size, "only the clicked element is arrayed; got ${points(ed.doc)}")
        assertHasPoint(ed.doc, rot(Vec2(40.0, 0.0), 120.0))
        assertTrue(points(ed.doc).none { (it - rot(Vec2(0.0, 40.0), 120.0)).length() < 1e-6 }, "e2 was not copied")

        // …and with nothing selected at all, the same
        val (ed2, _) = twoPointGroup(count = 3)
        ed2.setTool(Tools.SELECT)
        ed2.click(Vec2(-90.0, -90.0)) // empty space clears the selection
        assertEquals(0, ed2.selectionCount)
        ed2.setTool(Tools.ARRAY_CIRCULAR)
        ed2.click(Vec2(40.0, 0.0))
        ed2.click(Vec2(0.0, 0.0))
        assertEquals(5, points(ed2.doc).size, "a group nobody selected is invisible; got ${points(ed2.doc)}")
    }

    // ---- the sidebar route ----

    /**
     * The groups panel's row is the other way into the same slot ([Editor.clickGroup]): naming the group
     * *is* the pick, so it needs no prior selection, and the remaining slots proceed by canvas clicks.
     */
    @Test
    fun theGroupsPanelRowFeedsAnArmedGeometrySlot() {
        val (ed, g) = twoPointGroup(count = 4)
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(-90.0, -90.0)) // nothing selected: the row does not need it
        ed.setTool(Tools.ARRAY_CIRCULAR)

        ed.clickGroup(g)
        assertEquals(0, ed.selectionCount, "a pick is not a selection")
        assertEquals(2, ed.toolPicks.size, "both members are picked")
        assertTrue(ed.statusHint.contains("Group kitchen (2 elements)"), "got: ${ed.statusHint}")

        ed.click(Vec2(0.0, 0.0)) // the remaining slot, by canvas click as usual
        assertEquals(9, points(ed.doc).size, "count 4 of 2 elements is 6 new copies; got ${points(ed.doc)}")
        for (k in 1..3) {
            assertHasPoint(ed.doc, rot(Vec2(40.0, 0.0), 90.0 * k))
            assertHasPoint(ed.doc, rot(Vec2(0.0, 40.0), 90.0 * k))
        }
        assertRoundTrips(ed)

        // with no tool waiting for geometry the very same row simply selects, as it always did
        ed.setTool(Tools.SELECT)
        ed.clickGroup(g)
        assertEquals(2, ed.selectionCount)
        assertEquals(g, ed.selectedGroup)
    }

    /** A row click while the slot is *not* the pending one leaves the tool alone and selects. */
    @Test
    fun theGroupsPanelRowSelectsOnceTheGeometrySlotIsFilled() {
        val (ed, g) = twoPointGroup(count = 3)
        ed.setTool(Tools.ARRAY_CIRCULAR)
        ed.click(Vec2(40.0, 0.0)) // the geometry slot is now filled; the centre is pending
        ed.clickGroup(g)
        assertEquals(2, ed.selectionCount, "the row selected instead of feeding a point slot")
        assertEquals(2, ed.toolPicks.size, "and the pending array is untouched")
    }

    // ---- structure: live recompute, delete, undo, and the copies' own status ----

    @Test
    fun everyCopyFollowsAMemberDragLive() {
        val (ed, _) = twoPointGroup(count = 3)
        ed.setTool(Tools.ARRAY_CIRCULAR)
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(0.0, 0.0))

        // reach the member alone, then drag it — the copies are transform nodes over it, so they follow
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.drag(Vec2(40.0, 0.0), Vec2(60.0, 0.0))
        for (k in 1..2) assertHasPoint(ed.doc, rot(Vec2(60.0, 0.0), 120.0 * k))
        for (k in 1..2) assertHasPoint(ed.doc, rot(Vec2(0.0, 40.0), 120.0 * k))

        // and the other member's copies with it
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(0.0, 40.0))
        ed.click(Vec2(0.0, 40.0))
        ed.drag(Vec2(0.0, 40.0), Vec2(0.0, 50.0))
        for (k in 1..2) assertHasPoint(ed.doc, rot(Vec2(0.0, 50.0), 120.0 * k))
        assertRoundTrips(ed)
    }

    /** The copies land **ungrouped** — the deliberate first cut (OP-16's as-built note). */
    @Test
    fun theCopiesAreNotThemselvesGrouped() {
        val (ed, g) = twoPointGroup(count = 3)
        ed.setTool(Tools.ARRAY_CIRCULAR)
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(0.0, 0.0))

        assertEquals(1, ed.doc.groups.size, "no group per copy")
        assertEquals(2, ed.doc.groupMembers(g).size)
        val copies = ed.doc.elements.drop(3) // e1, e2, the centre — then the copies
        assertEquals(4, copies.size)
        for (c in copies) assertNull(ed.doc.groupOf(c), "${c.id} must be ungrouped")
    }

    /** A group array is **one** step: deleting any of its copies drops the array and keeps the group. */
    @Test
    fun aGroupArrayIsOneStepToDeleteAndOneToUndo() {
        val (ed, g) = twoPointGroup(count = 3)
        val beforeArray = DocumentFormat.save(ed.doc)
        ed.setTool(Tools.ARRAY_CIRCULAR)
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(0.0, 0.0))
        val afterArray = DocumentFormat.save(ed.doc)

        // one undo takes the whole array (and the centre point it placed goes with the step that made it)
        assertTrue(ed.undo())
        assertEquals(beforeArray, DocumentFormat.save(ed.doc), "one undo removes the whole array")
        assertTrue(ed.redo())
        assertEquals(afterArray, DocumentFormat.save(ed.doc))

        // deleting one copy drops the step, hence every copy — the originals and the group stay
        ed.setTool(Tools.SELECT)
        ed.click(rot(Vec2(40.0, 0.0), 120.0))
        assertEquals(1, ed.selectionCount)
        assertTrue(ed.deleteSelection())
        assertEquals(3, points(ed.doc).size, "the two originals and the centre survive; got ${points(ed.doc)}")
        // a delete replays into a *fresh* document, so the group is looked up again rather than held
        assertEquals(2, ed.doc.groupMembers(ed.doc.groups.single()).size)
        assertRoundTrips(ed)
    }

    /**
     * Deleting a **member** takes the array with it — the cascade needs no new rule, because the step
     * names the members it consumed like any other tool step (OP-18).
     */
    @Test
    fun deletingAMemberTakesTheArrayAndLeavesTheGroupConsistent() {
        val (ed, g) = twoPointGroup(count = 3)
        ed.setTool(Tools.ARRAY_CIRCULAR)
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(0.0, 0.0))

        ed.setTool(Tools.SELECT)
        ed.click(Vec2(40.0, 0.0)) // the group…
        ed.click(Vec2(40.0, 0.0)) // …then the member alone
        assertTrue(ed.deleteSelection())
        assertEquals(1, ed.doc.groupMembers(ed.doc.groups.single()).size, "the group keeps its other member")
        assertTrue(ed.doc.journal.none { it.kind == "tool" }, "and the array went with the source it copied")
        assertRoundTrips(ed)
    }

    /** Ungrouping afterwards leaves the array standing: the file names members, never the group. */
    @Test
    fun ungroupingAfterwardsLeavesTheArrayStanding() {
        val (ed, g) = twoPointGroup(count = 3)
        ed.setTool(Tools.ARRAY_CIRCULAR)
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(0.0, 0.0))
        val arrayed = points(ed.doc).size

        assertTrue(ed.ungroup(g))
        assertEquals(arrayed, points(ed.doc).size, "the copies are unaffected by the group going")
        assertFalse(DocumentFormat.save(ed.doc).contains("group \""), "no group step is left")
        assertRoundTrips(ed)
    }

    /**
     * A group multiplies the structural count, so the bound that protects a single element from a mistyped
     * count applies to the **copies** — and is *refused*, not clamped: a different number of copies would be
     * a different construction (OP-18). Both routes into the slot say the same thing.
     */
    @Test
    fun tooManyCopiesIsRefusedWithTheNumbers() {
        val (ed, g) = twoPointGroup(count = 400)
        ed.setTool(Tools.ARRAY_CIRCULAR)
        ed.click(Vec2(40.0, 0.0))
        assertTrue(ed.statusHint.contains("798 copies"), "got: ${ed.statusHint}")
        assertEquals(0, ed.toolPicks.size, "and nothing is picked")

        // the row says the same, and consumes the click rather than falling through to selecting — which
        // would replace the reason with a selection note
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(-90.0, -90.0))
        ed.setTool(Tools.ARRAY_CIRCULAR)
        ed.clickGroup(g)
        assertTrue(ed.statusHint.contains("798 copies"), "the row says the same; got: ${ed.statusHint}")
        assertEquals(0, ed.selectionCount, "a refusal consumes the row click rather than selecting")

        // the single-element bound is untouched: 400 instances of one element is 399 copies
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(40.0, 0.0)) // the member alone
        ed.setTool(Tools.ARRAY_CIRCULAR)
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(0.0, 0.0))
        assertEquals(2 + 1 + 399, points(ed.doc).size, "got ${points(ed.doc).size}")
    }

    // ---- placed groups (OP-16 step 2): the probe ----

    private fun rotAbout(
        p: Vec2,
        c: Vec2,
        deg: Double,
    ): Vec2 = c + rot(p - c, deg)

    /** Two points and their segment, grouped and placed; bbox centre (0,0), so the frame sits there. */
    private fun placedTrio(count: Int): Editor {
        val ed = Editor()
        ed.count = count
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(-90.0, -30.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(90.0, 30.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(90.0, 30.0)))
        val g = ed.groupSelection("kitchen")!!
        assertTrue(ed.placeGroup(g), "got: ${ed.statusHint}")
        assertEquals(g, ed.selectedGroup, "still addressed as a whole, so a member click means the group")
        return ed
    }

    /**
     * **Arraying a placed group's members just works**, and it works for the reason placing was built the
     * way it was: a copy is a transform node over the member's *published* point, which is already bound
     * onto `frameApply(frame, local)` (OP-16 step 2, OP-5). So the copies are downstream of the frame and a
     * frame drag moves them with everything else — nothing about the array knows a frame exists.
     */
    @Test
    fun aPlacedGroupCanBeArrayedAndItsCopiesFollowTheFrame() {
        val ed = placedTrio(count = 4)
        val centre = Vec2(0.0, -80.0)
        ed.setTool(Tools.ARRAY_CIRCULAR)
        ed.click(Vec2(-40.0, 0.0)) // a member: the whole placed group is the geometry
        ed.click(centre)

        // 3 members × 3 further instances: 6 point copies and 3 segment copies
        assertEquals(4, segments(ed.doc).size)
        assertEquals(2 + 1 + 6, points(ed.doc).size, "got ${points(ed.doc)}")
        for (k in 1..3) {
            assertHasPoint(ed.doc, rotAbout(Vec2(-40.0, 0.0), centre, 90.0 * k))
            assertHasPoint(ed.doc, rotAbout(Vec2(40.0, 0.0), centre, 90.0 * k))
        }

        // now move the frame: one literal write, and every copy follows because it is downstream of it
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(-40.0, 0.0), Vec2(-40.0, 20.0)) // the frame, by a member (the group is selected)
        assertTrue(ed.statusHint.contains("Moved kitchen"), "got: ${ed.statusHint}")
        for (k in 1..3) {
            assertHasPoint(ed.doc, rotAbout(Vec2(-40.0, 20.0), centre, 90.0 * k))
            assertHasPoint(ed.doc, rotAbout(Vec2(40.0, 20.0), centre, 90.0 * k))
        }
        assertRoundTrips(ed)
    }

    /**
     * The other order is **refused, with the reason** — and by the rule that was already there rather than
     * a new one: a copy is a non-member that depends on a free point the group owns, so placing that group
     * would move the source out from under geometry outside it (OP-16's "owned, shared, or outside").
     *
     * So the honest sequence is *place, then array*, and the message says which elements are in the way.
     */
    @Test
    fun placingAGroupThatIsAlreadyArrayedIsRefusedWithTheReason() {
        val (ed, g) = twoPointGroup(count = 3)
        ed.setTool(Tools.ARRAY_CIRCULAR)
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(0.0, 0.0))

        assertFalse(ed.placeGroup(g), "the copies depend on the points the frame would take")
        assertTrue(ed.statusHint.contains("also used by"), "got: ${ed.statusHint}")
        assertFalse(g.placed)
    }

    // ---- a group of mixed kinds, and a group whose members are already derived ----

    @Test
    fun aGroupOfMixedKindsArraysEveryKindWithNoPerKindCase() {
        val ed = Editor()
        ed.count = 3
        ed.setTool(Tools.POINT)
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.SELECT)
        // marquee the three, then group them: the marquee's selection is not a group selection, so the
        // grouping is what makes it one
        ed.pointerDown(ed.camera.worldToScreen(Vec2(20.0, -10.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(60.0, 10.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(60.0, 10.0)))
        assertEquals(3, ed.selectionCount)
        ed.groupSelection("bracket")

        ed.setTool(Tools.ARRAY_CIRCULAR)
        ed.click(Vec2(40.0, 0.0)) // the segment, between its endpoints
        ed.click(Vec2(0.0, 0.0))

        // 3 members × 2 further instances: 4 points and 2 segments added
        assertEquals(3, segments(ed.doc).size, "the segment is arrayed as segments")
        // the segment reused the two points, so the group is 2 points + 1 segment: 4 point copies
        assertEquals(2 + 1 + 4, points(ed.doc).size, "and both endpoints as points; got ${points(ed.doc)}")
        assertTrue(
            segments(ed.doc).any { (it.a - rot(Vec2(30.0, 0.0), 120.0)).length() < 1e-6 },
            "the 120° copy of the segment; got ${segments(ed.doc)}",
        )
        assertRoundTrips(ed)
    }
}
