package constructit

import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Scene3
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Probes over incremental recompute: a jamb drag must leave the BASE extrusion's compute count
 * untouched (OP-21's purity claim, now measurable), and orbiting the 3D view recomputes nothing.
 */
class RecomputeProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
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

    @Test
    fun aJambDragRecomputesTheCutButNeverTheBase() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(20.0, 0.0))
        ed.click(Vec2(21.0, 100.0))
        ed.finishPath()
        ed.activeScalar = ed.doc.newParameter("h", 50.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(15.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("w", 15.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(20.0, 50.0))
        ed.setTool(Tools.CUT_OPENINGS)
        ed.click(Vec2(15.0, 50.0))

        val solids = ed.doc.elements.filter { it.kind == ElementKind.SOLID }
        val base = solids.first().ref.node
        val cut = solids.last().ref.node
        // warm everything
        repeat(3) { Scene3.extract(ed.doc) }
        val baseCount = base.computeCount
        val cutCount = cut.computeCount
        assertTrue(baseCount > 0)

        // slide the door by its leading jamb (the wall is VERTICAL: jambs run across x at their y)
        ed.drag(Vec2(17.0, 42.5), Vec2(17.0, 57.5))
        repeat(3) { Scene3.extract(ed.doc) }
        assertTrue(cut.computeCount > cutCount, "the cut chain recomputed")
        assertEquals(baseCount, base.computeCount, "the base extrusion never recomputed — openings are descriptions (OP-21)")
    }

    @Test
    fun orbitingTheViewRecomputesNothing() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 40.0))
        ed.activeScalar = ed.doc.newParameter("h", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(30.0, 0.0))
        val solid = ed.doc.elements.last { it.kind == ElementKind.SOLID }.ref.node
        Scene3.extract(ed.doc)
        val count = solid.computeCount

        // orbiting is camera-only: extract per frame, nothing may recompute
        repeat(50) { Scene3.extract(ed.doc) }
        assertEquals(count, solid.computeCount, "orbit frames are free")
    }
}
