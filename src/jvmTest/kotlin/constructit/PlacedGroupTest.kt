package constructit

import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.core.SegmentValue
import constructit.core.SourceNode
import constructit.dsl.valueOf
import constructit.editor.Camera
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Group
import constructit.editor.PointerButton
import constructit.editor.SvgDrawTarget
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Placed groups (OP-16 build order step 2): a group carries **its own coordinate frame**, and moving it
 * is a literal edit on that one source node — not a transform applied to N points.
 *
 * The load-bearing property is that placing is a *refactoring*: retrofitting the group's free points to
 * frame-relative form preserves every world position, preserves the degree-of-freedom count, and is
 * invertible. Everything else here follows from it — a frame drag moves derived geometry too (it is
 * upstream of everything), a member still drags alone (its local point is still free), and the file
 * still restates world positions (the retrofit re-derives the locals on replay).
 */
class PlacedGroupTest {
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

    private fun Editor.marquee(
        from: Vec2,
        to: Vec2,
    ) {
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    /**
     * Four free points, two crossing segments, a midpoint and the segments' intersection — so the scene
     * has *derived* geometry that must travel with the frame although the frame does not name it.
     *
     * Bounding box (-60,-20)..(60,40), hence a frame origin at (0,10).
     */
    private fun scene(): Editor {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-60.0, 0.0)) // e1
        ed.click(Vec2(60.0, 0.0)) // e2
        ed.click(Vec2(0.0, 40.0)) // e3
        ed.click(Vec2(20.0, -20.0)) // e4
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(60.0, 0.0)) // e5: horizontal
        ed.click(Vec2(0.0, 40.0))
        ed.click(Vec2(20.0, -20.0)) // e6: crossing it
        ed.setTool(Tools.MIDPOINT)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(0.0, 40.0)) // e7: derived
        ed.setTool(Tools.INTERSECT)
        ed.click(Vec2(-30.0, 0.0)) // e5
        ed.click(Vec2(10.0, 10.0)) // e6 -> e8: derived
        ed.setTool(Tools.SELECT)
        return ed
    }

    /** [scene] with every element in one group — closure included, so nothing outside consumes it. */
    private fun grouped(name: String = "kitchen"): Editor {
        val ed = scene()
        ed.marquee(Vec2(-100.0, -60.0), Vec2(100.0, 80.0))
        assertEquals(8, ed.selectionCount, "the marquee must take the whole construction")
        assertNotNull(ed.groupSelection(name))
        return ed
    }

    private fun placed(name: String = "kitchen"): Pair<Editor, Group> {
        val ed = grouped(name)
        val g = ed.doc.groups.single()
        assertTrue(ed.placeGroup(g), "got: ${ed.statusHint}")
        return ed to g
    }

    /** Every element's evaluated geometry, as text — the invariant a refactoring must not change. */
    private fun geometry(ed: Editor): List<String> {
        val ev = Evaluator()
        return ed.doc.elements.map { el ->
            when (val v = ev.valueOf(el.ref)) {
                is PointValue -> "${el.id} ${fmt(v.p)}"
                is SegmentValue -> "${el.id} ${fmt(v.seg.a)}-${fmt(v.seg.b)}"
                else -> "${el.id} $v"
            }
        }
    }

    private fun fmt(p: Vec2) = "${(p.x * 1e6).toLong()},${(p.y * 1e6).toLong()}"

    private fun pos(
        ed: Editor,
        id: String,
    ): Vec2 = (Evaluator().valueOf(ed.doc.elements.first { it.id == id }.ref) as PointValue).p

    // ---- the headline: the retrofit is world-invariant, DOF-preserving and invertible ----

    @Test
    fun placingAndUnplacingLeaveEveryPositionExactlyWhereItWas() {
        val ed = grouped()
        val before = geometry(ed)
        val g = ed.doc.groups.single()

        assertTrue(ed.placeGroup(g), "got: ${ed.statusHint}")
        assertEquals(before, geometry(ed), "placing must not move anything — it only changes how it is held")
        assertClose(frameOrigin(g).x, 0.0, msg = "the frame starts at the members' bounding-box centre")
        assertClose(frameOrigin(g).y, 10.0)
        // the four free points are now driven (by their frameApply nodes), and the locals took their DOF
        assertEquals(4, g.captures.size)
        assertTrue(g.captures.all { it.original.boundTo != null }, "a captured point is bound, never rewired")
        assertTrue(g.captures.all { it.local.boundTo == null }, "…and its local coordinate is the free DOF")
        assertClose(g.captures.first { it.element?.id == "e1" }.let { localOf(it.local).x }, -60.0)
        assertClose(g.captures.first { it.element?.id == "e1" }.let { localOf(it.local).y }, -10.0)

        assertTrue(ed.unplaceGroup(g))
        assertEquals(before, geometry(ed), "and unplacing is the exact inverse")
        assertTrue(ed.doc.freePoints.all { (it.ref.node as SourceNode).boundTo == null }, "the sources are free again")
        assertFalse(g.placed)
        assertTrue(ed.doc.groups.single().name == "kitchen", "the group itself survives as a flat one")
    }

    private fun frameOrigin(g: Group): Vec2 = (Evaluator().eval(g.frameNode!!) as constructit.core.EvalResult.Ok).let { (it.value as constructit.core.FrameValue).origin }

    private fun localOf(n: SourceNode): Vec2 = (n.value as PointValue).p

    /** A framed point is bound, but it is not a welded alias — it stays visible, draggable and typed. */
    @Test
    fun aFramedPointIsNotAWeldedAlias() {
        val (ed, _) = placed()
        val e1 = ed.doc.elements.first { it.id == "e1" }
        assertFalse(ed.doc.isWelded(e1), "bound onto its frame is not welded onto another point")
        assertTrue(ed.doc.isFramed(e1))
        assertTrue(e1.visible)
        assertTrue(e1.draggable, "its local point is still a free degree of freedom")

        // so the panel can still hide and show it; a welded alias is the one that stays hidden
        val g = ed.doc.groups.single()
        ed.setGroupVisible(g, false)
        assertTrue(ed.doc.elements.none { it.visible })
        ed.setGroupVisible(g, true)
        assertTrue(ed.doc.elements.all { it.visible })
    }

    // ---- moving: the frame is a handle, by drag and by number ----

    @Test
    fun draggingAPlacedGroupMovesItsFrameAndEverythingFollowsRigidly() {
        val (ed, g) = placed()
        val before = ed.doc.elements.associate { it.id to (Evaluator().valueOf(it.ref) as? PointValue)?.p }

        ed.click(Vec2(0.0, 90.0)) // start from nothing selected
        ed.click(Vec2(60.0, 0.0)) // a member -> the whole group
        assertEquals(8, ed.selectionCount)
        assertEquals(g, ed.selectedFrame())
        ed.drag(Vec2(60.0, 0.0), Vec2(90.0, 25.0))

        assertClose(frameOrigin(g).x, 30.0)
        assertClose(frameOrigin(g).y, 35.0)
        for ((id, p) in before) {
            if (p == null) continue
            assertClose(pos(ed, id).x, p.x + 30.0, msg = "$id x")
            assertClose(pos(ed, id).y, p.y + 25.0, msg = "$id y")
        }
        // derived geometry included: it is downstream of the captured points, so it needs no rule of its own
        assertClose(pos(ed, "e7").x, -30.0 + 30.0)
        assertClose(pos(ed, "e8").y, 0.0 + 25.0)
    }

    @Test
    fun theFramesXYAndAngleAreTypedFieldsOfTheSelectedGroup() {
        val (ed, g) = placed()
        ed.click(Vec2(0.0, 90.0))
        ed.click(Vec2(-60.0, 0.0))
        assertEquals(listOf("x", "y", "angle"), ed.selectionFields().map { it.label })
        assertEquals("frame of kitchen", ed.selectionLabel())
        assertClose(ed.selectionFields()[0].read(Evaluator())!!.mm, 0.0)
        assertClose(ed.selectionFields()[1].read(Evaluator())!!.mm, 10.0)

        assertTrue(ed.writeSelectionField(0, 100.0), "x is writable — the frame is free")
        assertClose(frameOrigin(g).x, 100.0)
        assertClose(pos(ed, "e1").x, 40.0, msg = "the group moved with it")
        assertClose(ed.selectionFields()[0].read(Evaluator())!!.mm, 100.0, msg = "and the field reads back")
    }

    @Test
    fun typingAnAngleRotatesTheGroupAboutTheFrameOrigin() {
        val (ed, g) = placed()
        ed.click(Vec2(0.0, 90.0))
        ed.click(Vec2(-60.0, 0.0))
        assertTrue(ed.writeSelectionField(2, 90.0))

        // e1 is local (-60,-10); rotated a quarter turn about (0,10) it lands at (10,-50)
        assertClose(pos(ed, "e1").x, 10.0)
        assertClose(pos(ed, "e1").y, -50.0)
        // e2 is local (60,-10) -> (10,70)
        assertClose(pos(ed, "e2").x, 10.0)
        assertClose(pos(ed, "e2").y, 70.0)
        // distances are preserved: a frame move is rigid
        assertClose((pos(ed, "e1") - pos(ed, "e2")).length(), 120.0)
        // the derived intersection rotated too
        assertClose((pos(ed, "e8") - frameOrigin(g)).length(), (Vec2(40.0 / 3.0, -10.0)).length())
    }

    // ---- which subject a gesture on a member addresses (OP-16: press decides, release selects) ----

    /**
     * The user's report: pressing a member of a group **nobody selected** and dragging moved the whole
     * frame, although grouping is invisible until something of it is selected. A drag there must move that
     * element, exactly as if it were ungrouped — and leave *it* selected, as an ungrouped drag does.
     */
    @Test
    fun draggingAMemberOfAnUnselectedGroupMovesTheMemberAndLeavesTheFrameWhereItWas() {
        val (ed, g) = placed()
        ed.click(Vec2(0.0, 90.0)) // nothing selected: the group is invisible from here
        assertEquals(0, ed.selectionCount)

        ed.drag(Vec2(60.0, 0.0), Vec2(75.0, 20.0)) // straight onto e2, no click first

        assertClose(pos(ed, "e2").x, 75.0, msg = "the member moved under the cursor")
        assertClose(pos(ed, "e2").y, 20.0)
        assertClose(pos(ed, "e1").x, -60.0, msg = "and nothing else did")
        assertClose(pos(ed, "e3").y, 40.0)
        assertClose(frameOrigin(g).x, 0.0, msg = "the frame did not move")
        assertClose(frameOrigin(g).y, 10.0)

        // a pure drag leaves the element it moved selected — the least surprising answer, and the same one
        // dragging ungrouped geometry gives
        assertEquals(1, ed.selectionCount)
        assertEquals("e2", ed.selection?.id)
        assertNull(ed.selectedFrame(), "…so the panel addresses the member, not the frame")
    }

    /** The other half: once a click has selected the group as a whole, a drag moves the frame. */
    @Test
    fun aClickThenADragOfTheSameMemberMovesTheFrame() {
        val (ed, g) = placed()
        ed.click(Vec2(0.0, 90.0))
        ed.click(Vec2(60.0, 0.0)) // the click selects the whole group
        assertEquals(g, ed.selectedFrame())

        ed.drag(Vec2(60.0, 0.0), Vec2(75.0, 20.0))

        assertClose(frameOrigin(g).x, 15.0, msg = "the frame took the whole gesture")
        assertClose(frameOrigin(g).y, 30.0)
        assertClose(pos(ed, "e1").x, -45.0, msg = "and the group came with it")
        assertClose(pos(ed, "e1").y, 20.0)
        assertEquals(g, ed.selectedFrame(), "a moved group stays selected as a whole")
    }

    /** Axis lock applies to the member drag exactly as it does to the frame's. */
    @Test
    fun anUnselectedGroupsMemberDragTakesAxisLock() {
        val (ed, g) = placed()
        ed.click(Vec2(0.0, 90.0))
        ed.axisLock = true
        ed.drag(Vec2(60.0, 0.0), Vec2(85.0, 5.0)) // dominated by x

        assertClose(pos(ed, "e2").x, 85.0)
        assertClose(pos(ed, "e2").y, 0.0, msg = "the lock held the other axis")
        assertClose(frameOrigin(g).y, 10.0, msg = "and the frame is still untouched")
    }

    /** The member-reach cycle is untouched: click, click again, click once more. */
    @Test
    fun theGroupMemberClickCycleStillGoesGroupMemberGroup() {
        val (ed, g) = placed()
        ed.click(Vec2(0.0, 90.0))
        ed.click(Vec2(60.0, 0.0))
        assertEquals(8, ed.selectionCount)
        assertEquals(g, ed.selectedFrame())
        ed.click(Vec2(60.0, 0.0))
        assertEquals(1, ed.selectionCount)
        assertEquals("e2", ed.selection?.id)
        ed.click(Vec2(60.0, 0.0))
        assertEquals(8, ed.selectionCount)
        assertEquals(g, ed.selectedFrame())
    }

    // ---- reaching a member: its drag inverts the frame ----

    @Test
    fun aMemberDragsInsideTheFrameAndMovesNothingElse() {
        val (ed, _) = placed()
        ed.click(Vec2(0.0, 90.0))
        ed.click(Vec2(60.0, 0.0)) // the group
        ed.click(Vec2(60.0, 0.0)) // e2 alone
        assertEquals(1, ed.selectionCount)
        assertNull(ed.selectedFrame(), "reaching a member alone addresses the member, not the frame")
        assertEquals(listOf("x", "y"), ed.selectionFields().map { it.label })

        ed.drag(Vec2(60.0, 0.0), Vec2(75.0, 20.0))
        assertClose(pos(ed, "e2").x, 75.0, msg = "it lands under the cursor")
        assertClose(pos(ed, "e2").y, 20.0)
        assertClose(pos(ed, "e1").x, -60.0, msg = "and nothing else moved")
        assertClose(pos(ed, "e3").y, 40.0)
        // the write went to the *local* node, so the frame itself is untouched
        assertClose(frameOrigin(ed.doc.groups.single()).x, 0.0)
        assertClose(frameOrigin(ed.doc.groups.single()).y, 10.0)

        // and the typed field writes the same node, still in world numbers
        assertTrue(ed.writeSelectionField(0, 80.0))
        assertClose(pos(ed, "e2").x, 80.0)
        assertClose(pos(ed, "e2").y, 20.0)
    }

    /** A member drag inside a *rotated* frame still lands under the cursor — the inverse map is exact. */
    @Test
    fun aMemberDragInvertsARotatedFrame() {
        val (ed, g) = placed()
        ed.click(Vec2(0.0, 90.0))
        ed.click(Vec2(-60.0, 0.0))
        assertTrue(ed.writeSelectionField(2, 90.0))
        val start = pos(ed, "e1") // (10,-50)
        ed.click(Vec2(0.0, 90.0))
        ed.click(start)
        ed.click(start) // e1 alone
        assertEquals(1, ed.selectionCount)
        ed.drag(start, Vec2(40.0, -30.0))
        assertClose(pos(ed, "e1").x, 40.0)
        assertClose(pos(ed, "e1").y, -30.0)
        assertClose(frameOrigin(g).x, 0.0, msg = "the frame did not move")
    }

    // ---- the honest failure modes of OP-16 ----

    @Test
    fun placingIsRefusedWhenAFreePointIsSharedWithANonMember() {
        val ed = scene()
        // group only part of the construction: the intersection e8 and the midpoint e7 stay outside and
        // still depend on the points inside it
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(60.0, 0.0), additive = true)
        ed.click(Vec2(-30.0, 0.0), additive = true) // the horizontal segment
        val g = ed.groupSelection("half")!!

        assertFalse(ed.placeGroup(g))
        assertTrue(ed.statusHint.startsWith("Can't place half: e1"), "got: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("also used by"), "got: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("e8"), "the outside consumer is named: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("cannot move independently"), "got: ${ed.statusHint}")
        assertFalse(g.placed, "nothing was changed by the refusal")
        assertTrue(ed.doc.journal.none { it.kind == "place" })
    }

    @Test
    fun aGroupThatOwnsNoFreePointCannotBePlaced() {
        val ed = scene()
        ed.click(Vec2(-30.0, 20.0)) // e7, the midpoint: derived, owns nothing
        val g = ed.groupSelection("derived")!!
        assertFalse(ed.placeGroup(g))
        // "no free point" was the old wording; the refusal now covers every kind of freedom (OP-16)
        assertTrue(ed.statusHint.contains("owns no degree of freedom"), "got: ${ed.statusHint}")
    }

    /**
     * A member whose end is welded to something *outside* the group keeps following that thing: the weld's
     * `boundTo` leaves the group, so that end is not one of the group's degrees of freedom and the frame
     * does not move it. The group **deforms** there — correctly, and reported at placement time, because on
     * canvas it is invisible until the group is moved (OP-16).
     */
    @Test
    fun aMemberBoundOutsideTheGroupDoesNotFollowTheFrameAndSaysSo() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-60.0, 0.0)) // e1
        ed.click(Vec2(0.0, 0.0)) // e2, to be welded outside
        ed.click(Vec2(40.0, 20.0)) // e3, the outsider
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(0.0, 0.0)) // e4, from e1 to e2
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(0.0, 0.0), Vec2(39.0, 19.0)) // weld e2 onto e3 by the magnet
        assertTrue(ed.statusHint.contains("Joined"), "got: ${ed.statusHint}")

        // group the segment and its free end; its other end now follows e3, which stays outside
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(-10.0, 10.0), additive = true) // the segment, at its midpoint
        assertEquals(2, ed.selectionCount)
        val g = ed.groupSelection("wing")!!
        assertTrue(ed.placeGroup(g), "got: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("will not follow it"), "the deformation is reported: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("e4"), "…naming the member that is held from outside: ${ed.statusHint}")

        ed.click(Vec2(0.0, 90.0))
        ed.click(Vec2(-60.0, 0.0))
        ed.drag(Vec2(-60.0, 0.0), Vec2(-60.0, 30.0))
        assertClose(pos(ed, "e1").y, 30.0, msg = "the captured end followed the frame")
        val seg = (Evaluator().valueOf(ed.doc.elements.first { it.id == "e4" }.ref) as SegmentValue).seg
        assertClose(seg.b.x, 40.0, msg = "the welded end did not — the group deformed, as it must")
        assertClose(seg.b.y, 20.0)
    }

    // ---- persistence (OP-18) ----

    @Test
    fun aPlacedGroupSurvivesSaveLoadSaveByteIdentically() {
        val (ed, _) = placed()
        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.contains("place \"kitchen\" at=0,10 angle=0deg"), "got:\n$once")
        // the members' own steps keep restating WORLD positions: they are replayed before the retrofit
        assertTrue(once.contains("point -60,0"), "got:\n$once")
        val reloaded = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(reloaded), "save -> load -> save must be identical")
        assertEquals(geometry(ed), geometry(Editor(reloaded)), "and the reloaded drawing is the same drawing")
        val g = reloaded.groups.single()
        assertTrue(g.placed)
        assertEquals(4, g.captures.size, "replay re-runs the same retrofit")
    }

    @Test
    fun theScriptStaysStableAfterAFrameDragAndAfterAMemberDrag() {
        val (ed, _) = placed()
        ed.click(Vec2(0.0, 90.0))
        ed.click(Vec2(60.0, 0.0))
        ed.drag(Vec2(60.0, 0.0), Vec2(85.0, 20.0)) // the frame
        val afterFrame = DocumentFormat.save(ed.doc)
        assertTrue(afterFrame.contains("at=25,30"), "the frame's origin is restated: got\n$afterFrame")
        assertTrue(afterFrame.contains("point -35,20"), "and the members' world positions with it: got\n$afterFrame")
        assertEquals(afterFrame, DocumentFormat.save(DocumentFormat.load(afterFrame)))

        ed.click(Vec2(85.0, 20.0))
        ed.click(Vec2(85.0, 20.0)) // e2 alone
        ed.drag(Vec2(85.0, 20.0), Vec2(90.0, 40.0))
        val afterMember = DocumentFormat.save(ed.doc)
        assertTrue(afterMember.contains("point 90,40"), "a member's own world position moved: got\n$afterMember")
        assertTrue(afterMember.contains("at=25,30"), "the frame did not: got\n$afterMember")
        assertEquals(afterMember, DocumentFormat.save(DocumentFormat.load(afterMember)))
    }

    @Test
    fun placingAndFrameDragsAreEachOneUndoStep() {
        val ed = grouped()
        val g = ed.doc.groups.single()
        val beforePlace = DocumentFormat.save(ed.doc)

        assertTrue(ed.placeGroup(g))
        val afterPlace = DocumentFormat.save(ed.doc)
        assertTrue(ed.undo())
        assertEquals(beforePlace, DocumentFormat.save(ed.doc))
        assertFalse(ed.doc.groups.single().placed, "one undo unplaces it")
        assertTrue(ed.redo())
        assertEquals(afterPlace, DocumentFormat.save(ed.doc))

        ed.click(Vec2(0.0, 90.0))
        ed.click(Vec2(60.0, 0.0))
        ed.drag(Vec2(60.0, 0.0), Vec2(90.0, 25.0))
        val afterMove = DocumentFormat.save(ed.doc)
        assertTrue(afterMove != afterPlace)
        assertTrue(ed.undo())
        assertEquals(afterPlace, DocumentFormat.save(ed.doc), "one undo puts the group back")
        assertTrue(ed.redo())
        assertEquals(afterMove, DocumentFormat.save(ed.doc))

        // unplacing drops the step outright, like ungroup drops the group step
        val g2 = ed.doc.groups.single()
        assertTrue(ed.unplaceGroup(g2))
        assertTrue(ed.doc.journal.none { it.kind == "place" })
        assertEquals(afterMove.lines().filter { !it.startsWith("place") }, DocumentFormat.save(ed.doc).lines())
    }

    /** A `place` step whose group is gone goes too — the rule the `group` step already follows (OP-18). */
    @Test
    fun deletingEveryMemberRemovesThePlaceStep() {
        val (ed, _) = placed()
        ed.click(Vec2(0.0, 90.0))
        ed.click(Vec2(-60.0, 0.0)) // the whole group
        assertEquals(8, ed.selectionCount)
        assertTrue(ed.deleteSelection(), "got: ${ed.statusHint}")
        assertTrue(ed.doc.groups.isEmpty())
        val text = DocumentFormat.save(ed.doc)
        assertFalse(text.contains("place"), "got:\n$text")
        assertFalse(text.contains("group"), "got:\n$text")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)))
    }

    /** Deleting *one* member leaves the placement consistent: the rest stays frame-relative. */
    @Test
    fun deletingOneMemberLeavesThePlacementConsistent() {
        val (ed, _) = placed()
        ed.click(Vec2(0.0, 90.0))
        ed.click(Vec2(-30.0, 20.0)) // the group
        ed.click(Vec2(-30.0, 20.0)) // e7, the midpoint, alone
        assertEquals(1, ed.selectionCount)
        assertTrue(ed.deleteSelection(), "got: ${ed.statusHint}")

        val g = ed.doc.groups.single()
        assertTrue(g.placed, "the placement survives a member going")
        assertEquals(4, g.captures.size)
        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("place \"kitchen\""), "got:\n$text")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)))

        // and deleting a *captured* point (with the geometry built on it) re-runs the retrofit over what
        // is left, rather than leaving a placement that refers to a point the script no longer declares
        ed.click(Vec2(0.0, 90.0))
        ed.click(Vec2(20.0, -20.0))
        ed.click(Vec2(20.0, -20.0)) // e4 alone
        assertEquals(1, ed.selectionCount)
        assertTrue(ed.deleteSelection(), "got: ${ed.statusHint}")
        val after = ed.doc.groups.single()
        assertTrue(after.placed)
        assertEquals(3, after.captures.size)
        val text2 = DocumentFormat.save(ed.doc)
        assertEquals(text2, DocumentFormat.save(DocumentFormat.load(text2)))
        assertClose(pos(ed, "e1").x, -60.0, msg = "and nothing moved")
    }

    @Test
    fun ungroupingAPlacedGroupUnplacesItFirst() {
        val (ed, g) = placed()
        val before = geometry(ed)
        assertTrue(ed.ungroup(g))
        assertEquals(before, geometry(ed), "its points keep their positions")
        assertTrue(ed.doc.groups.isEmpty())
        assertTrue(ed.doc.freePoints.all { (it.ref.node as SourceNode).boundTo == null }, "and are free again")
        val text = DocumentFormat.save(ed.doc)
        assertFalse(text.contains("place"), "got:\n$text")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)))
    }

    @Test
    fun aPlacedGroupCannotBePlacedTwice() {
        val (ed, g) = placed()
        assertFalse(ed.placeGroup(g))
        assertTrue(ed.statusHint.contains("already placed"), "got: ${ed.statusHint}")
        assertEquals(1, ed.doc.journal.count { it.kind == "place" })
    }

    /** A framed point is already derived, so the weld magnet must not offer to join it onto anything. */
    @Test
    fun theMagnetDoesNotOfferToWeldAFramedPoint() {
        val (ed, _) = placed()
        ed.click(Vec2(0.0, 90.0))
        ed.click(Vec2(60.0, 0.0))
        ed.click(Vec2(60.0, 0.0)) // e2 alone
        ed.drag(Vec2(60.0, 0.0), Vec2(0.0, 39.0)) // drop it right next to e3
        assertFalse(ed.statusHint.contains("Joined"), "got: ${ed.statusHint}")
        assertClose(pos(ed, "e2").y, 39.0, msg = "it simply moved there")
    }

    // ---- rendering ----

    /** How many strokes of the frame marker's own colour the scene draws — two axes and the origin dot. */
    private fun frameMarks(ed: Editor): Int {
        val target = SvgDrawTarget()
        ed.render(target)
        return Regex("#8c564b").findAll(target.svg()).count()
    }

    /**
     * The marker follows the group's **visibility**, not only its selection (OP-16): hiding a placed group
     * left its origin marker floating on an otherwise empty canvas, which reads as geometry that cannot be
     * picked. A frame is drawn only while something of its group is both selected and drawn.
     */
    @Test
    fun aHiddenGroupDrawsNoFrameMarkerAndShowingItBringsTheMarkerBack() {
        val (ed, g) = placed()
        ed.click(Vec2(0.0, 90.0))
        ed.click(Vec2(-60.0, 0.0)) // the whole group: the frame is addressable, hence drawn
        assertEquals(3, frameMarks(ed), "two axes and the origin dot")

        ed.setGroupVisible(g, false)
        assertEquals(0, frameMarks(ed), "a hidden group draws no frame either")
        ed.setGroupVisible(g, true)
        assertEquals(3, frameMarks(ed), "and it comes back with the group")

        // the same through the selection's own Hide, which is the other way to hide every member
        assertTrue(ed.setSelectionVisible(false) > 0)
        assertEquals(0, frameMarks(ed))
        ed.setSelectionVisible(true)
        assertEquals(3, frameMarks(ed))
    }

    /** A placed, rotated group through the renderer, frame marker included. */
    @Test
    fun aPlacedRotatedGroupRendersWithItsFrame() {
        val (ed, _) = placed()
        ed.click(Vec2(0.0, 90.0))
        ed.click(Vec2(-60.0, 0.0))
        assertTrue(ed.writeSelectionField(2, 30.0))
        ed.canvasW = 320.0
        ed.canvasH = 260.0
        ed.camera = Camera.centered(320.0, 260.0, scale = 1.6)

        val target = SvgDrawTarget()
        ed.render(target)
        Golden.check("editor_placed_group", target.svg())
    }
}
