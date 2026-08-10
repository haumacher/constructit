package constructit

import constructit.core.Evaluator
import constructit.editor.Camera3
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Probe review of the click-a-face package — compositions the delivery never saw.
 *
 * The two questions: does the picked plane stay **the same plane while the dressing under it moves** — a
 * blended cap's trim follows its radius parameter, and the face space opened by a 3D click must ride the
 * face, not the trim; and does the new flat-end address reach a body **no other route could ever sketch
 * on** — the top cap of an extrusion bounded by a function curve, whose side face has no name at all.
 */
class Face3DPickProbeTest {
    private val wPx = 800.0
    private val hPx = 600.0

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun Viewport3.clickWorld(p: Vec3) {
        val s = assertNotNull(camera.project(p, widthPx, heightPx), "$p has an image on screen")
        pointerDown(s)
        pointerUp(s)
    }

    private fun view(
        ed: Editor,
        cam: Camera3,
    ): Viewport3 {
        val vp = Viewport3(camera = cam, widthPx = wPx, heightPx = hPx)
        vp.editor = ed
        vp.shown = true
        return vp
    }

    /**
     * A dressed quarter tube: the blend radius is a parameter, the start cap is picked in the 3D view, and
     * the resulting working plane survives the trim moving under it — same plane before and after the radius
     * edit, byte-equal through the file.
     */
    @Test
    fun aFaceSpaceOnADressedCapRidesTheFaceNotTheTrim() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 20.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-30.0, 0.0))
        ed.click(Vec2(130.0, 0.0))
        ed.setTool(Tools.REVOLVE)
        ed.type("90")
        ed.click(Vec2(50.0, 20.0))
        ed.click(Vec2(-20.0, 0.0))
        val r = ed.doc.newParameter("r", 2.0.mm)
        ed.activeScalar = r
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(50.0, 20.0))
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "the dressed tube: ${ed.statusHint}")

        // pick the start cap (it lies in the plan, z = 0) on the DRESSED body, in the 3D view
        val spacesBefore = ed.doc.spaces.size
        val vp = view(ed, Camera3(target = Vec3(50.0, 10.0, 5.0), distance = 300.0, yaw = -1.1, pitch = -0.7))
        ed.setTool(Tools.SKETCH_ON_FACE)
        vp.clickWorld(Vec3(50.0, 10.0, 0.0))
        assertEquals(spacesBefore + 1, ed.doc.spaces.size, "the cap became a working plane: ${ed.statusHint}")
        val plane = assertNotNull(ed.doc.activePlane3(Evaluator()), "and it is active")
        assertTrue(abs(abs(plane.normal.z) - 1.0) < 1e-9, "the cap's plane: normal along z, got ${plane.normal}")

        // the trim moves with the parameter; the plane does not
        ed.doc.setParameter(r, 5.0.mm)
        val planeAfter = assertNotNull(ed.doc.activePlane3(Evaluator()), "the space survives the re-round")
        assertTrue((planeAfter.origin - plane.origin).length() < 1e-9, "same origin under the moved trim")
        assertTrue((planeAfter.normal - plane.normal).length() < 1e-9, "same normal under the moved trim")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "byte-equal through the file")
    }

    /**
     * The bulge plate: an extrusion bounded by a function curve has an unnameable side face, so before this
     * package NO route could sketch on it anywhere above the plan — the 3D click on its flat top is the
     * first. And the curved function wall itself refuses, speaking.
     */
    @Test
    fun aFunctionCurvePlatesFlatTopIsReachableAndItsWallRefuses() {
        val ed = Editor()
        val arch =
            assertNotNull(
                ed.addFunctionCurve("40mm * t", "10mm * sin(PI * t)", 0.0, 1.0),
                "the sine arch builds: ${ed.statusHint}",
            )
        assertNotNull(arch)
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.OUTLINE)
        ed.click(Vec2(20.0, 10.0))
        ed.click(Vec2(20.0, 0.0))
        ed.key("Enter")
        ed.activeScalar = ed.doc.newParameter("depth", 8.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(20.0, 0.0))
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "the plate: ${ed.statusHint}")

        // the curved function wall refuses, speaking — aimed along a near-horizontal ray so the wall,
        // not the top cap, is the first thing the ray meets
        val wallView = view(ed, Camera3(target = Vec3(20.0, 5.0, 4.0), distance = 260.0, yaw = Math.PI / 2, pitch = 0.05))
        val spacesBefore = ed.doc.spaces.size
        ed.setTool(Tools.SKETCH_ON_FACE)
        wallView.clickWorld(Vec3(20.0, 10.0, 4.0))
        assertEquals(spacesBefore, ed.doc.spaces.size, "the function wall carries no sketch")
        val said = assertNotNull(ed.statusHint, "and the refusal speaks")
        assertTrue(said.isNotBlank(), "with words: '$said'")

        // the flat top takes the space — the first route that ever reached it
        val vp = view(ed, Camera3(target = Vec3(20.0, 5.0, 4.0), distance = 260.0, yaw = -1.2, pitch = 0.6))
        vp.clickWorld(Vec3(20.0, 4.0, 8.0))
        assertEquals(spacesBefore + 1, ed.doc.spaces.size, "the flat end became a working plane: ${ed.statusHint}")
        val plane = assertNotNull(ed.doc.activePlane3(Evaluator()))
        assertTrue(abs(plane.origin.z - 8.0) < 1e-9 || abs(plane.toWorld(Vec2(0.0, 0.0)).z - 8.0) < 1e-9, "at the top")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "byte-equal through the file")
    }
}
