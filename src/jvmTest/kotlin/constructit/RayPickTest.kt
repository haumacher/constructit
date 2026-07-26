package constructit

import constructit.core.Evaluator
import constructit.core.LineValue
import constructit.dsl.valueOf
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.HitTest
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A ray is pickable** — a reported defect: `HitTest.distanceToValue` had no `RayValue` case, so every ray
 * fell into the unpickable `else`. It drew, and a marquee took it (the rectangle test *did* have the kind),
 * but no click could reach it: it could not be selected, could not be cycled to, and could not fill a slot —
 * arming *Perpendicular* and clicking a ray simply refused it.
 *
 * The fix is one case in the one distance rule, which is why all of that comes back at once (OP-13: nothing
 * is reachable by one route and not another).
 */
class RayPickTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    /** A ray from (0,0) through (100,0) — so it runs +X and stops dead at its origin. */
    private fun withRay(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RAY)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.SELECT)
        return ed
    }

    private fun ray(ed: Editor) = ed.doc.elements.first { it.kind == ElementKind.RAY }

    @Test
    fun aRayIsSelectableByClickingIt() {
        val ed = withRay()
        ed.click(Vec2(60.0, 0.0))
        assertEquals(ray(ed).id, ed.selection?.id, "got: ${ed.statusHint}")
        assertTrue(ed.selectionLabel().startsWith("ray"), "got: ${ed.selectionLabel()}")
    }

    /** The user's exact repro: a ray fills the LINE slot, so Perpendicular builds through it. */
    @Test
    fun aRayFillsALineSlot() {
        val ed = withRay()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(40.0, 30.0))
        ed.setTool(Tools.PERPENDICULAR)
        ed.click(Vec2(60.0, 0.0)) // the ray
        ed.click(Vec2(40.0, 30.0)) // the point it runs through

        val perp = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.LINE }, "got: ${ed.statusHint}")
        val line = assertNotNull(Evaluator().valueOf(perp.ref) as? LineValue).line
        assertClose(line.dir.dot(Vec2(1.0, 0.0)), 0.0, msg = "perpendicular to the ray's direction")
        assertClose((line.origin - Vec2(40.0, 30.0)).cross(line.dir), 0.0, msg = "through the clicked point")
    }

    /**
     * The clamp is on the **origin side only**: behind the origin a click measures to the origin, so it falls
     * outside the tolerance where the infinite carrier line would still have been hit.
     */
    @Test
    fun behindTheOriginTheRayIsOutOfReach() {
        val ed = withRay()
        val r = ray(ed)
        val ev = Evaluator()
        assertClose(assertNotNull(HitTest.distanceTo(ev, r, Vec2(60.0, 2.0))), 2.0, msg = "beside the ray")
        assertClose(assertNotNull(HitTest.distanceTo(ev, r, Vec2(-30.0, 0.0))), 30.0, msg = "behind its origin")

        // …and a click back there reaches nothing, rather than the ray it is not on
        ed.click(Vec2(-30.0, 0.0))
        assertEquals(0, ed.selectionCount, "got: ${ed.selectionLabel()}")
    }

    /** A nearer thing still wins the click; the ray is then the next candidate in the cycle. */
    @Test
    fun aNearerElementWinsAndTheRayIsNextInTheCycle() {
        val ed = withRay()
        ed.snapEnabled = false
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(20.0, 0.5))
        ed.click(Vec2(80.0, 0.5))
        ed.setTool(Tools.SELECT)
        val seg = ed.doc.elements.last { it.kind == ElementKind.SEGMENT }

        ed.click(Vec2(-900.0, -900.0))
        ed.click(Vec2(60.0, 0.6))
        assertEquals(seg.id, ed.selection?.id, "the nearer segment: ${ed.statusHint}")
        assertEquals(2, ed.pickCycleSize, "…with the ray in the same pile")
        ed.click(Vec2(60.0, 0.6))
        assertEquals(ray(ed).id, ed.selection?.id, "clicking again reaches the ray: ${ed.statusHint}")
        assertNull(ed.selectedJamb)
    }
}
