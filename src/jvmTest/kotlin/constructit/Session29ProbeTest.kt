package constructit

import constructit.core.CircleValue
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.Camera3
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.geom.Vec3
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Probes on the session-29 package, composing both items with what they must NOT have broken: an outline
 * whose joints are **genuine re-intersections** (the inverse of the duplicate-marker fix — those markers
 * must stay visible), and the **drill-on-a-face flow driven by SELECT drags in the 3D view across two
 * cameras** (the reversal exercised on a tilted working plane, not the plan the delivery tests used).
 */
class Session29ProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    /**
     * Four segments laid out like a `#`: the picks *cross* instead of sharing endpoints, so every joint is
     * a real re-intersection — new geometry, whose marker must stay green and visible. The eight stub
     * endpoints stay blue free points. The one-point-one-marker rule must distinguish the two, not blanket-hide.
     */
    @Test
    fun aCrossedOutlineKeepsItsGenuineJointMarkersVisible() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-10.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.click(Vec2(40.0, -10.0))
        ed.click(Vec2(40.0, 40.0))
        ed.click(Vec2(50.0, 30.0))
        ed.click(Vec2(-10.0, 30.0))
        ed.click(Vec2(0.0, 40.0))
        ed.click(Vec2(0.0, -10.0))
        ed.setTool(Tools.OUTLINE)
        ed.click(Vec2(20.0, 0.0))
        ed.click(Vec2(40.0, 15.0))
        ed.click(Vec2(20.0, 30.0))
        ed.click(Vec2(0.0, 15.0))
        ed.key("Enter")
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.OUTLINE }, ed.statusHint)

        // the four corner joints are new geometry: visible, at the four crossings
        val corners = setOf(Vec2(40.0, 0.0), Vec2(40.0, 30.0), Vec2(0.0, 30.0), Vec2(0.0, 0.0))
        val ev = Evaluator()
        val visibleDerived =
            ed.doc.elements.filter { it.kind == ElementKind.DERIVED_POINT && it.visible }.map {
                assertNotNull((ev.valueOf(it.ref) as? constructit.core.PointValue)?.p)
            }
        assertEquals(4, visibleDerived.size, "one visible marker per genuine re-intersection")
        for (c in corners) {
            assertTrue(visibleDerived.any { (it - c).length() < 1e-9 }, "a visible joint marker at $c")
        }
        // and the eight stub endpoints are still visible free points — none swallowed by the rule
        assertEquals(8, ed.doc.elements.count { it.kind == ElementKind.POINT && it.visible })

        // the outline extrudes like any drawn boundary, and the whole story replays byte-equal
        ed.setTool(Tools.EXTRUDE)
        ed.type("20")
        ed.click(Vec2(20.0, 0.0))
        val part = ed.doc.elements.filter { it.kind == ElementKind.SOLID }.last()

        @Suppress("UNCHECKED_CAST")
        val mesh = Evaluator().solid(part.ref as SolidRef).mesh
        assertManifold(mesh, "the crossed outline's prism")
        assertClose(Geom3.volume(mesh), 40.0 * 30.0 * 20.0, tol = 1e-6, msg = "the trimmed quad, raised")
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "crossing joints replay byte-equal")
    }

    /**
     * The user's own scenario, one step further than the delivery's plan-space tests: the drill circle on a
     * pyramid's slant face, **repositioned by SELECT drags in the 3D view**, before and after a
     * modifier-orbit — two cameras, one truth. The bore follows; the camera only moves when asked.
     */
    @Test
    fun theFaceDrillIsRepositionedBySelectDragsAcrossTwoCameras() {
        val ed = Editor()
        val vp = Viewport3(camera = Camera3(target = Vec3(50.0, 50.0, 30.0), distance = 280.0, yaw = -1.1, pitch = 0.9), widthPx = 800.0, heightPx = 600.0)
        vp.editor = ed
        vp.shown = true

        fun at(plane: Vec2) = assertNotNull(assertNotNull(vp.projection()).toScreen(plane), "$plane projects")

        fun clickAt(plane: Vec2) {
            val s = at(plane)
            vp.pointerDown(s)
            vp.pointerUp(s)
        }

        // the pyramid and its face drill, driven through the 3D view
        ed.setTool(Tools.RECTANGLE)
        clickAt(Vec2(0.0, 0.0))
        clickAt(Vec2(100.0, 100.0))
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.type("90")
        clickAt(Vec2(30.0, 0.0))
        clickAt(Vec2(50.0, 50.0))
        ed.setTool(Tools.SKETCH_ON_FACE)
        clickAt(Vec2(30.0, 0.0))
        assertTrue(ed.activeSpace.isFace, "on the slant face: ${ed.statusHint}")
        val slant = sqrt(50.0 * 50.0 + 90.0 * 90.0)
        val c0 = Vec2(0.0, slant / 2.0)
        ed.setTool(Tools.CIRCLE_R)
        ed.type("6")
        clickAt(c0)
        ed.setTool(Tools.CUT)
        ed.type("40")
        clickAt(Vec2(6.0, slant / 2.0))
        val circle = ed.doc.elements.last { it.kind == ElementKind.CIRCLE }
        val part = ed.doc.elements.filter { it.kind == ElementKind.SOLID }.last()

        fun centre(): Vec2 = assertNotNull((Evaluator().valueOf(circle.ref) as? CircleValue)?.circle?.center)
        assertClose(centre().x, c0.x, tol = 1e-9, msg = "the drill starts where it was clicked")

        // SELECT, plain drag on the tilted plane: the bore moves, the camera does not
        ed.setTool(Tools.SELECT)
        val cam0 = vp.camera
        val c1 = Vec2(12.0, slant / 2.0 - 10.0)
        vp.pointerDown(at(c0))
        vp.pointerMove(at(Vec2(6.0, slant / 2.0 - 5.0)))
        vp.pointerMove(at(c1))
        vp.pointerUp(at(c1))
        assertClose(centre().x, c1.x, tol = 1e-6, msg = "the centre followed the drag on the slant")
        assertClose(centre().y, c1.y, tol = 1e-6, msg = "the centre followed the drag on the slant")
        assertEquals(cam0, vp.camera, "a plain drag left the camera alone")

        // the modifier takes the camera; the drawing holds still
        vp.cameraModifier = true
        vp.pointerDown(Vec2(400.0, 300.0))
        vp.pointerMove(Vec2(330.0, 340.0))
        vp.pointerUp(Vec2(330.0, 340.0))
        vp.cameraModifier = false
        assertTrue(vp.camera != cam0, "the modifier drag orbited")
        assertClose(centre().x, c1.x, tol = 1e-9, msg = "the orbit moved no geometry")

        // and the same grab works through the second camera — the seam, not the pixels, owns the meaning
        vp.pointerDown(at(c1))
        vp.pointerMove(at(c0))
        vp.pointerUp(at(c0))
        assertClose(centre().x, c0.x, tol = 1e-6, msg = "dragged home through a different camera")
        assertClose(centre().y, c0.y, tol = 1e-6, msg = "dragged home through a different camera")

        @Suppress("UNCHECKED_CAST")
        val mesh = Evaluator().solid(part.ref as SolidRef).mesh
        assertManifold(mesh, "the drilled pyramid after three 3D drags")
        assertTrue(Geom3.volume(mesh) < 300000.0 - 100.0, "the bore is still cut: ${Geom3.volume(mesh)}")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "3D drags restate positions and replay byte-equal")
    }
}
