package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.Camera3
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
 * Probe: the user's inscribed-circle drill, driven **entirely through the 3D viewport on a face space** —
 * a tilted working plane the slice-1 tests never clicked interactively — with an orbit detour in the middle
 * of the LLL pick sequence. Face space × section inputs × LLL × Cut × the ray seam, in one flow.
 */
class Edit3DProbeTest {
    private val wPx = 800.0
    private val hPx = 600.0

    private fun Viewport3.clickAt(plane: Vec2) {
        val p = assertNotNull(projection(), "a working plane under the 3D view")
        val s = assertNotNull(p.toScreen(plane), "$plane has an image")
        pointerDown(s)
        pointerUp(s)
    }

    @Test
    fun theFaceDrillWorksThroughThe3DViewWithAnOrbitDetour() {
        val ed = Editor()
        val vp = Viewport3(camera = Camera3(target = Vec3(50.0, 50.0, 30.0), distance = 280.0, yaw = -1.1, pitch = 0.9), widthPx = wPx, heightPx = hPx)
        vp.editor = ed
        vp.shown = true

        // the pyramid, clicked in 3D on the plan
        ed.setTool(Tools.RECTANGLE)
        vp.clickAt(Vec2(0.0, 0.0))
        vp.clickAt(Vec2(100.0, 100.0))
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        for (c in "90") ed.key(c.toString())
        ed.key("Enter")
        vp.clickAt(Vec2(30.0, 0.0))
        vp.clickAt(Vec2(50.0, 50.0))
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SOLID }, ed.statusHint)

        // onto the face: the working plane tilts, and the 3D view must follow it
        ed.setTool(Tools.SKETCH_ON_FACE)
        vp.clickAt(Vec2(30.0, 0.0))
        assertTrue(ed.activeSpace.isFace, "on the face: ${ed.statusHint}")

        // the face triangle and its incircle, computed independently — in the face's intrinsic frame
        // (session 32): the picked base edge on the x axis about its midpoint, the apex at +v
        val slant = sqrt(50.0 * 50.0 + 90.0 * 90.0)
        val a = Vec2(0.0, slant)
        val b = Vec2(-50.0, 0.0)
        val c = Vec2(50.0, 0.0)
        val la = (b - c).length()
        val lb = (c - a).length()
        val lc = (a - b).length()
        val incentre = (a * la + b * lb + c * lc) * (1.0 / (la + lb + lc))
        val r = (la * slant) / (la + lb + lc)
        val onCircle = incentre + Vec2(0.6 * r, 0.8 * r)
        assertClose(incentre.y, r, tol = 1e-9, msg = "the incircle touches the base edge, which is the x axis here")

        // LLL through the 3D view — with an orbit detour after the second leg
        ed.setTool(Tools.CIRCLE_LLL)
        vp.clickAt(Vec2(0.0, 0.0))
        vp.clickAt(Vec2(-25.0, slant / 2.0))
        vp.cameraModifier = true
        vp.pointerDown(Vec2(400.0, 300.0))
        vp.pointerMove(Vec2(310.0, 330.0))
        vp.pointerUp(Vec2(310.0, 330.0))
        vp.cameraModifier = false
        vp.clickAt(Vec2(25.0, slant / 2.0))
        vp.clickAt(onCircle)
        val circle = ed.doc.elements.last { it.kind == ElementKind.CIRCLE }
        val cv = assertNotNull(Evaluator().valueOf(circle.ref) as? constructit.core.CircleValue).circle
        assertClose(cv.center.x, incentre.x, tol = 1e-6, msg = "the incircle, picked through two different cameras")
        assertClose(cv.center.y, incentre.y, tol = 1e-6, msg = "the incircle, picked through two different cameras")

        // the drill, still in the 3D view
        ed.setTool(Tools.CIRCLE_R)
        for (ch in "6") ed.key(ch.toString())
        ed.key("Enter")
        vp.clickAt(incentre)
        ed.setTool(Tools.CUT)
        for (ch in "40") ed.key(ch.toString())
        ed.key("Enter")
        vp.clickAt(Vec2(incentre.x + 6.0, incentre.y))
        val part = ed.doc.elements.filter { it.kind == ElementKind.SOLID }.last()

        @Suppress("UNCHECKED_CAST")
        val mesh = Evaluator().solid(part.ref as SolidRef).mesh
        assertManifold(mesh, "pyramid drilled through the 3D view")
        assertTrue(Geom3.volume(mesh) < 300000.0 - 100.0, "the bore took material: ${Geom3.volume(mesh)}")

        val once = constructit.editor.DocumentFormat.save(ed.doc)
        assertEquals(
            once,
            constructit.editor.DocumentFormat.save(constructit.editor.DocumentFormat.load(once)),
            "the 3D-driven drill replays byte-equal",
        )
    }
}
