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
 * Probes over thick networks: the full architect chain on a CURVED wall (door on the arc leg, cut
 * in 3D), and a pattern's six edges thickened into a hex tube — OP-23 composing with the OP-21
 * extension.
 */
class ThickNetworkProbeTest {
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

    @Suppress("UNCHECKED_CAST")
    private fun Editor.lastMesh() = Evaluator().solid(doc.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef).mesh

    /** Segment-arc-segment wall, a door ON the arc, extruded and cut: the curved architect chain. */
    @Test
    fun aDoorOnTheArcLegCutsTheCurvedWall() {
        assumeTrue(MeshBool.available, "mesh boolean engine unavailable")
        val ed = Editor()
        // carrier: segment + arc + segment
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-80.0, 0.0))
        ed.click(Vec2(-30.0, 0.0))
        ed.setTool(Tools.ARC_3)
        ed.click(Vec2(-30.0, 0.0))
        ed.click(Vec2(0.0, 30.0)) // crown
        ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(80.0, 0.0))
        // thicken the three pieces
        ed.activeScalar = ed.doc.newParameter("t", 8.0.mm)
        ed.setTool(Tools.THICKEN)
        ed.click(Vec2(-55.0, 0.0))
        ed.click(Vec2(0.0, 30.0))
        ed.click(Vec2(55.0, 0.0))
        ed.key("Enter")
        assertEquals(1, ed.doc.thickNetworks.size, "one network: ${ed.statusHint}")
        // a door ON the arc leg
        ed.activeScalar = ed.doc.newParameter("w", 12.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(0.0, 30.0)) // the crown
        // extrude + cut
        ed.activeScalar = ed.doc.newParameter("h", 40.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(0.0, 34.0)) // the footprint's outer face at the crown
        val full = Geom3.volume(ed.lastMesh())
        ed.setTool(Tools.CUT_OPENINGS)
        ed.click(Vec2(0.0, 34.0))
        val cut = ed.lastMesh()
        assertManifold(cut, "curved wall with a door")
        assertTrue(Geom3.volume(cut) < full - 12.0 * 8.0 * 40.0 * 0.8, "the door removed real material: ${Geom3.volume(cut)} vs $full")
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)))
    }

    /** A hexagon pattern's six edges thickened: a closed ring footprint, extruded to a hex tube. */
    @Test
    fun aPatternsEdgesThickenIntoAHexTube() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.count = 6
        ed.setTool(Tools.PATTERN_CIRCULAR)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(50.0, 0.0))
        ed.click(Vec2(25.0, 43.3)) // ref0 -> ref1: the orbit draws all six edges
        assertEquals(6, ed.doc.elements.count { it.kind == ElementKind.SEGMENT }, "the orbit drew the hexagon")

        ed.activeScalar = ed.doc.newParameter("t", 6.0.mm)
        ed.setTool(Tools.THICKEN)
        // pick all six edges (midpoints of each side)
        for (k in 0 until 6) {
            val a = Math.toRadians(60.0 * k)
            val b = Math.toRadians(60.0 * (k + 1))
            val mx = (50.0 * Math.cos(a) + 50.0 * Math.cos(b)) / 2.0
            val my = (50.0 * Math.sin(a) + 50.0 * Math.sin(b)) / 2.0
            ed.click(Vec2(mx, my))
        }
        ed.key("Enter")
        val net = ed.doc.thickNetworks.lastOrNull()
        assertTrue(net != null, "the ring thickened: ${ed.statusHint}")

        ed.activeScalar = ed.doc.newParameter("h", 25.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(50.0 + 3.0, 0.0)) // near a mitred corner's outer face
        val mesh = ed.lastMesh()
        assertManifold(mesh, "hex tube")
        // ring area: regular hexagon side 50 -> apothem 43.30; centered band of 6 wide around the perimeter
        val outerA = 3.0 * Math.sqrt(3.0) / 2.0 * 53.464 * 53.464 // hexagon scaled outward by 3/apothem... assert bracket instead
        assertTrue(Geom3.volume(mesh) > 6.0 * 50.0 * 6.0 * 25.0 * 0.9, "roughly perimeter x t x h: ${Geom3.volume(mesh)}")
        assertTrue(Geom3.volume(mesh) < 6.0 * 50.0 * 6.0 * 25.0 * 1.2)
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)))
    }
}
