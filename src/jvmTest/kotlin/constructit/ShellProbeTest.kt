package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Camera3
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Probe review of the shelling package — compositions the delivery never saw.
 *
 * The two questions: is the **pocket floor a real working plane** — a ray into the open cavity picks the
 * inner bottom face (the session-74 seam meeting the new inner addresses), a circle sketched there drills
 * into the bottom wall, and the volumes say so; and what happens when a **blend meets a shelled body** —
 * a case no gate names, so it must build honestly or refuse out loud, never decline in silence.
 */
class ShellProbeTest {
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

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun volumeOf(el: Element): Double {
        val mesh = Evaluator().solid(el.ref as SolidRef).mesh
        assertManifold(mesh, "a probed body")
        return Geom3.volume(mesh)
    }

    /** A 40×30×20 plate shelled open at the top to t = 3: outer 24000, cavity 34·24·17 = 13872. */
    private fun openBox(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 30.0))
        ed.activeScalar = ed.doc.newParameter("depth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(20.0, 0.0))
        ed.activeScalar = ed.doc.newParameter("t", 3.0.mm)
        val vp = view(ed, Camera3(target = Vec3(20.0, 15.0, 10.0), distance = 300.0, yaw = -1.1, pitch = 0.8))
        ed.setTool(Tools.SHELL)
        vp.clickWorld(Vec3(20.0, 15.0, 20.0))
        assertEquals(2, ed.solids().size, "the open box: ${ed.statusHint}")
        assertClose(volumeOf(ed.solids().last()), 24000.0 - 13872.0, tol = 1.0, msg = "shelled open at the top")
        return ed
    }

    /**
     * A ray straight down into the cavity picks the **pocket floor**; a circle sketched there and a Cut
     * drilled from it eat exactly one small cylinder out of the bottom wall.
     */
    @Test
    fun thePocketFloorTakesASketchAndACutThroughTheWall() {
        val ed = openBox()
        val shelled = ed.solids().last()
        val before = volumeOf(shelled)

        // the floor of the cavity lies at z = 3; a ray from above through the opening reaches it
        val vp = view(ed, Camera3(target = Vec3(20.0, 15.0, 10.0), distance = 300.0, yaw = -1.1, pitch = 1.2))
        val spacesBefore = ed.doc.spaces.size
        ed.setTool(Tools.SKETCH_ON_FACE)
        vp.clickWorld(Vec3(20.0, 15.0, 3.0))
        assertEquals(spacesBefore + 1, ed.doc.spaces.size, "the pocket floor is a working plane: ${ed.statusHint}")

        // a small bore, drilled from the floor into the bottom wall (Cut follows −normal)
        ed.activeScalar = ed.doc.newParameter("bore", 2.0.mm)
        ed.setTool(Tools.CIRCLE_R)
        vp.clickWorld(Vec3(20.0, 15.0, 3.0))
        ed.activeScalar = ed.doc.newParameter("hole", 2.0.mm)
        ed.setTool(Tools.CUT)
        vp.clickWorld(Vec3(22.0, 15.0, 3.0))
        val drilled = ed.solids().last()
        assertTrue(drilled !== shelled, "the cut built a new tip: ${ed.statusHint}")
        assertClose(
            volumeOf(drilled),
            before - PI * 2.0 * 2.0 * 2.0,
            tol = 0.5,
            msg = "one 2 mm bore, 2 mm deep, out of the bottom wall",
        )

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the whole story survives its file")
    }

    /** A blend asked of a shelled body — unnamed by any gate, so it builds honestly or refuses out loud. */
    @Test
    fun aBlendOnAShelledBodyBuildsOrSpeaks() {
        val ed = openBox()
        val shelled = ed.solids().last()
        val before = volumeOf(shelled)
        val solidsBefore = ed.solids().size

        ed.activeScalar = ed.doc.newParameter("r", 2.0.mm)
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(20.0, 0.0))

        if (ed.solids().size > solidsBefore) {
            val blended = ed.solids().last()
            val after = volumeOf(blended)
            assertTrue(after < before, "a convex rim fillet removes material: $after vs $before")
        } else {
            val said = assertNotNull(ed.statusHint, "the decline speaks")
            assertTrue(said.isNotBlank(), "with words: '$said'")
        }
        for (s in ed.solids()) {
            volumeOf(s)
        }
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "and the drawing survives its file")
    }
}
