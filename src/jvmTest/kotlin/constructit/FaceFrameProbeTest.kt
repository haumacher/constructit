package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.plane
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Probe on the face-frame package, composing the origin anchor with what it exists FOR: anchored to a
 * corner that **moves under a parameter edit** (the face's top corner, when the plate's thickness is a
 * parameter), a bore recorded relative to that corner must ride along — the explicit anchor doing the
 * work no gesture compensation could ("explicit anchors beat compensation"), through a Cut chain and a
 * byte-equal replay.
 */
class FaceFrameProbeTest {
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

    @Test
    fun aBoreAnchoredToTheTopCornerRidesTheThicknessParameter() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(80.0, 50.0))
        val t = ed.doc.newParameter("t", 20.0.mm)
        ed.activeScalar = t
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(40.0, 0.0))
        assertTrue(ed.activeSpace.isFace, "on the plate's front face: ${ed.statusHint}")

        // the intrinsic frame stands on the picked edge's midpoint, v up into the face — the top-left
        // corner is at (-40, t); anchor the origin THERE: the one corner a thickness edit moves
        ed.setTool(Tools.SPACE_ORIGIN)
        ed.click(Vec2(-40.0, 20.0))
        val space = ed.activeSpace
        val p0 = Evaluator().plane(assertNotNull(space.plane))
        assertClose(p0.origin.x, 0.0, msg = "the origin is the top-left corner now")
        assertClose(p0.origin.z, 20.0, msg = "...at the plate's top, t = 20")

        // a bore recorded FROM that corner: 10 below the top, whatever the top turns out to be
        ed.setTool(Tools.CIRCLE_R)
        ed.type("6")
        ed.click(Vec2(15.0, -10.0))
        ed.setTool(Tools.CUT)
        ed.type("60")
        ed.click(Vec2(21.0, -10.0))
        val part = ed.doc.elements.filter { it.kind == ElementKind.SOLID }.last()

        @Suppress("UNCHECKED_CAST")
        fun vol() = Geom3.volume(Evaluator().solid(part.ref as SolidRef).mesh)
        val bore = PI * 36.0 * 50.0
        assertClose(vol(), 80.0 * 50.0 * 20.0 - bore, tol = 40.0, msg = "a through bore, 10 under the top")

        // the thickness grows: the anchored corner rises, the frame rides it, and the bore stays
        // "10 under the top" — in a midpoint-anchored frame it would have stayed 10 over the BOTTOM
        ed.doc.setParameter(t, 32.0.mm)
        assertClose(
            Evaluator().plane(assertNotNull(space.plane)).origin.z,
            32.0,
            msg = "the origin followed the corner up",
        )
        assertClose(vol(), 80.0 * 50.0 * 32.0 - bore, tol = 40.0, msg = "the bore rode the top: still fully inside")

        @Suppress("UNCHECKED_CAST")
        val mesh = Evaluator().solid(part.ref as SolidRef).mesh
        assertManifold(mesh, "the thickened plate with its riding bore")
        val hi = Geom3.bounds(mesh)!!.second
        assertClose(hi.z, 32.0, msg = "the plate really is 32 thick now")

        // and the whole story — space, anchor, bore, parameter edit — replays byte-equal
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the anchored frame replays byte-equal")
    }
}
