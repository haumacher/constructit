package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Probes composing the solid tools with what already exists: two features over ONE area, an opening
 * that must NOT cut the extrusion (the OP-21 plan-gap rule crossing the seam), and a carrier drag
 * reshaping a solid. A solid is an ordinary dependent of its area, nothing more.
 */
class SolidToolProbeTest {
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

    private fun Editor.wall(): Editor {
        activeScalar = doc.newParameter("t", 10.0.mm)
        setTool(Tools.WALL)
        click(Vec2(20.0, 0.0))
        click(Vec2(21.0, 60.0))
        finishPath()
        return this
    }

    @Suppress("UNCHECKED_CAST")
    private fun Editor.meshes() =
        doc.elements.filter { it.kind == ElementKind.SOLID }.map { Evaluator().solid(it.ref as SolidRef).mesh }

    /** One area, two features — the region node is shared, and each solid is its own dependent. */
    @Test
    fun twoFeaturesOverOneAreaAreIndependentDependents() {
        val ed = Editor().wall()
        ed.activeScalar = ed.doc.newParameter("d1", 12.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(15.0, 30.0))
        ed.activeScalar = ed.doc.newParameter("d2", 30.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(15.0, 30.0))
        assertEquals(2, ed.meshes().size)
        val volumes = ed.meshes().map { Geom3.volume(it) }.sorted()
        assertClose(volumes[0], 10.0 * 60.0 * 12.0, tol = 1e-6)
        assertClose(volumes[1], 10.0 * 60.0 * 30.0, tol = 1e-6)

        // delete one solid: the other and the footprint survive
        val doomed = ed.doc.elements.first { it.kind == ElementKind.SOLID }
        ed.selectElement(doomed)
        assertTrue(ed.deleteSelection())
        assertEquals(1, ed.meshes().size)
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.AREA }, "the shared area survives its consumer")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)))
    }

    /** OP-21 across the seam: an opening is a description; the footprint — and hence the solid — stays whole. */
    @Test
    fun anOpeningDoesNotCutTheExtrudedWall() {
        val ed = Editor().wall()
        ed.activeScalar = ed.doc.newParameter("depth", 25.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(15.0, 30.0))
        val before = Geom3.volume(ed.meshes().single())

        ed.activeScalar = ed.doc.newParameter("w", 15.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(20.0, 30.0)) // a door mid-wall

        val after = Geom3.volume(ed.meshes().single())
        assertClose(after, before, tol = 1e-9, msg = "the plan gap is a drawing convention; subtraction is the boolean task")
    }

    /** Dragging the carrier reshapes the footprint, and the solid recomputes through the seam. */
    @Test
    fun draggingTheCarrierReshapesTheSolid() {
        val ed = Editor().wall()
        ed.activeScalar = ed.doc.newParameter("depth", 12.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(15.0, 30.0))

        // stretch the wall by dragging its far end from y=60 to y=100
        ed.drag(Vec2(20.0, 60.0), Vec2(20.0, 100.0))
        assertClose(Geom3.volume(ed.meshes().single()), 10.0 * 100.0 * 12.0, tol = 1e-6, msg = "the solid follows the dragged carrier")
        assertManifold(ed.meshes().single(), "solid after carrier drag")
    }
}
