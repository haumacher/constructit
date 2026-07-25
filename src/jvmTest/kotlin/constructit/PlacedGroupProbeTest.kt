package constructit

import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.core.SegmentValue
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.PointerButton
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Probes for placed groups (OP-16 step 2) that compose the frame with *pre-existing* features —
 * construction on top of a placed group, several frames side by side, welding onto a captured
 * member. A placed group is ordinary geometry, so everything that worked before placing must keep
 * working after it, on the same code paths.
 */
class PlacedGroupProbeTest {
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

    private fun Editor.pointAt(idx: Int): Vec2 {
        val pts = doc.elements.filter { it.kind == ElementKind.POINT }
        return (Evaluator().valueOf(pts[idx].ref) as PointValue).p
    }

    /** Two free points and their segment, grouped and placed; bbox centre (0,0). */
    private fun placedPair(ed: Editor): Vec2 {
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.marqueeAll()
        val g = ed.groupSelection("g")!!
        assertTrue(ed.placeGroup(g))
        return Vec2(0.0, 0.0)
    }

    private fun Editor.marqueeAll() {
        pointerDown(camera.worldToScreen(Vec2(-100.0, -100.0)))
        pointerMove(camera.worldToScreen(Vec2(100.0, 100.0)))
        pointerUp(camera.worldToScreen(Vec2(100.0, 100.0)))
    }

    @Test
    fun constructionBuiltOnAPlacedMemberFollowsItsFrame() {
        val ed = Editor()
        placedPair(ed)
        // a NEW point outside any group, and a NEW segment from the placed member to it —
        // built AFTER placement, so its endpoint snaps to (reuses) the frame-driven point
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 80.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(40.0, 0.0)) // snaps to the placed member point
        ed.click(Vec2(0.0, 80.0))
        val seg = ed.doc.elements.last { it.kind == ElementKind.SEGMENT }

        // drag the frame: click a member (selects the group), then drag it 30 to the right
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(-40.0, 0.0))
        ed.drag(Vec2(-40.0, 0.0), Vec2(-10.0, 0.0))

        val v = Evaluator().valueOf(seg.ref) as SegmentValue
        assertClose(v.seg.a.x, 70.0) // the member end followed the frame
        assertClose(v.seg.a.y, 0.0)
        assertClose(v.seg.b.x, 0.0) // the outside end did not
        assertClose(v.seg.b.y, 80.0)

        // the post-placement construction replays: place is mid-journal now
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save -> load -> save with steps after 'place'")
    }

    @Test
    fun twoPlacedGroupsMoveIndependently() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-60.0, 40.0))
        ed.click(Vec2(-20.0, 40.0))
        ed.click(Vec2(20.0, -40.0))
        ed.click(Vec2(60.0, -40.0))
        ed.setTool(Tools.SELECT)
        // first pair -> group a, second pair -> group b, both placed
        ed.click(Vec2(-60.0, 40.0))
        ed.click(Vec2(-20.0, 40.0), additive = true)
        val a = ed.groupSelection("a")!!
        assertTrue(ed.placeGroup(a))
        ed.click(Vec2(20.0, -40.0))
        ed.click(Vec2(60.0, -40.0), additive = true)
        val b = ed.groupSelection("b")!!
        assertTrue(ed.placeGroup(b))

        // drag group a's frame via one of its members
        ed.click(Vec2(-60.0, 40.0))
        ed.drag(Vec2(-60.0, 40.0), Vec2(-60.0, 90.0))

        assertClose(ed.pointAt(0).y, 90.0)
        assertClose(ed.pointAt(1).y, 90.0)
        assertClose(ed.pointAt(2).y, -40.0, msg = "group b must not follow group a's frame")
        assertClose(ed.pointAt(3).y, -40.0)
    }

    @Test
    fun weldingAFreePointOntoAPlacedMemberFollowsTheFrame() {
        val ed = Editor()
        placedPair(ed)
        ed.setTool(Tools.POINT)
        ed.click(Vec2(90.0, 60.0)) // a free point outside the group
        ed.setTool(Tools.SELECT)
        // drag it onto the placed member at (40,0): the magnet welds on release
        ed.drag(Vec2(90.0, 60.0), Vec2(40.0, 0.0))
        val pts = ed.doc.elements.filter { it.kind == ElementKind.POINT }
        assertEquals(1, pts.count { it.visible && (Evaluator().valueOf(it.ref) as PointValue).p.x > 0.0 }, "welded pair reads as one dot")

        // move the frame; the welded alias must ride along
        ed.click(Vec2(-40.0, 0.0))
        ed.drag(Vec2(-40.0, 0.0), Vec2(-40.0, -50.0))
        val alias = ed.pointAt(2)
        assertClose(alias.x, 40.0)
        assertClose(alias.y, -50.0, msg = "a point welded onto a placed member follows the frame")
    }
}
