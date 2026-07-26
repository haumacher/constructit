package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.MeshBool
import constructit.geom.Vec2
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Probes on face sketching: two face spaces on two different edges, each with its own bore, in one
 * chained part — spaces must coexist and the whole chain replay; then the honest answer for a face
 * on a placed (rotated) solid.
 */
class FaceSketchProbeTest {
    private fun requireEngine() = assumeTrue(MeshBool.available, "mesh boolean engine unavailable")

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(v: String) {
        v.forEach { key(it.toString()) }
        key("Enter")
    }

    private fun Editor.solids() = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun Editor.meshOf(el: constructit.editor.Element) = Evaluator().solid(el.ref as SolidRef).mesh

    @Test
    fun twoFaceSpacesOnTwoEdgesEachCarryABore() {
        requireEngine()
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(80.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("t", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(40.0, 0.0))

        // face 1: the front edge (y=0), Cut a bore
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.CIRCLE_R)
        ed.type("2.5")
        ed.click(Vec2(25.0, 8.0))
        ed.setTool(Tools.CUT)
        ed.type("10")
        ed.click(Vec2(27.5, 8.0))
        val afterOne = ed.solids().last()
        assertManifold(ed.meshOf(afterOne), "one bore")

        // face 2: the right edge (x=80) — a second space, a second bore into the chained result
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(80.0, 25.0))
        ed.setTool(Tools.CIRCLE_R)
        ed.type("3")
        ed.click(Vec2(20.0, 10.0))
        ed.setTool(Tools.CUT)
        ed.type("12")
        ed.click(Vec2(23.0, 10.0))

        val part = ed.solids().last()
        val mesh = ed.meshOf(part)
        assertManifold(mesh, "two bores from two faces")
        val expected = 80.0 * 50.0 * 20.0 - Math.PI * 2.5 * 2.5 * 10.0 - Math.PI * 3.0 * 3.0 * 12.0
        assertTrue(Math.abs(Geom3.volume(mesh) - expected) / expected < 2e-3, "volume ${Geom3.volume(mesh)} vs $expected")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "two spaces + two cuts replay")
    }
}
