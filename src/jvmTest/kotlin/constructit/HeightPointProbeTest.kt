package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Camera3
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.exchange.ExportScene
import constructit.exchange.Stl
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Probes on the height point, composing it with what the package never met: the apex dragged in the 3D
 * view and the result pushed **through the export seam** (the file's own triangles carry the new height),
 * with undo taking the drag back; and **one height scalar feeding two pyramids** — shared node *is*
 * equality (the no-solver stance), exercised through the newest node kind.
 */
class HeightPointProbeTest {
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

    private fun Editor.solids() = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun Editor.meshOf(el: constructit.editor.Element) = Evaluator().solid(el.ref as SolidRef).mesh

    private fun viewOver(ed: Editor): Viewport3 {
        val vp = Viewport3(camera = Camera3(target = Vec3(50.0, 50.0, 40.0), distance = 320.0, yaw = -0.9, pitch = 0.5), widthPx = 800.0, heightPx = 600.0)
        vp.editor = ed
        vp.shown = true
        return vp
    }

    private fun Viewport3.screenOf(p: Vec3): Vec2 = assertNotNull(camera.project(p, widthPx, heightPx), "$p projects")

    private fun stlVolume(bytes: ByteArray): Double {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.position(80)
        val n = b.getInt()
        var vol = 0.0
        repeat(n) {
            repeat(3) { b.getFloat() }
            val v = Array(3) { doubleArrayOf(b.getFloat().toDouble(), b.getFloat().toDouble(), b.getFloat().toDouble()) }
            b.getShort()
            vol += (
                v[0][0] * (v[1][1] * v[2][2] - v[2][1] * v[1][2]) -
                    v[1][0] * (v[0][1] * v[2][2] - v[2][1] * v[0][2]) +
                    v[2][0] * (v[0][1] * v[1][2] - v[1][1] * v[0][2])
            ) / 6.0
        }
        return vol
    }

    /** The apex dragged in 3D, the new pyramid leaving through STL, and undo taking the drag back. */
    @Test
    fun aDraggedApexLeavesThroughTheExportSeamAndUndoTakesItBack() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.type("90")
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(50.0, 50.0))
        val part = ed.solids().single()
        assertClose(Geom3.volume(ed.meshOf(part)), 300000.0, tol = 1e-6, msg = "the exact pyramid (OP-24's loft)")

        // grab the apex where the camera shows it, drop it where a 130-high apex would show: ray-to-line = 130
        val vp = viewOver(ed)
        ed.setTool(Tools.SELECT)
        val cam0 = vp.camera
        vp.pointerDown(vp.screenOf(Vec3(50.0, 50.0, 90.0)))
        vp.pointerMove(vp.screenOf(Vec3(50.0, 50.0, 110.0)))
        vp.pointerMove(vp.screenOf(Vec3(50.0, 50.0, 130.0)))
        vp.pointerUp(vp.screenOf(Vec3(50.0, 50.0, 130.0)))
        assertEquals(cam0, vp.camera, "a plain drag left the camera alone")
        assertClose(Geom3.volume(ed.meshOf(part)), 100.0 * 100.0 * 130.0 / 3.0, tol = 1.0, msg = "the solid followed the dragged height")

        // out through the seam: the STL's own triangles integrate to the dragged pyramid
        val stl = Stl.write(ExportScene.extract(ed.doc, "apex"))
        assertClose(stlVolume(stl), 100.0 * 100.0 * 130.0 / 3.0, tol = 2.0, msg = "the file carries the dragged apex (float32)")

        // the drag is one recorded edit: one undo, and the pyramid is back
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the dragged height replays byte-equal")
        ed.undo()
        assertClose(Geom3.volume(ed.meshOf(ed.solids().single())), 300000.0, tol = 1e-6, msg = "undo takes the drag back")
    }

    /** One parameter, two pyramids: sharing the node IS the equality — and one edit moves both. */
    @Test
    fun twoPyramidsShareOneHeightAndMoveTogether() {
        val ed = Editor()
        val h = ed.doc.newParameter("h", 60.0.mm)

        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 60.0))
        ed.activeScalar = h
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(30.0, 30.0))

        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(100.0, 0.0))
        ed.click(Vec2(160.0, 60.0))
        ed.activeScalar = h
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.click(Vec2(130.0, 0.0))
        ed.click(Vec2(130.0, 30.0))

        val parts = ed.solids()
        assertEquals(2, parts.size, ed.statusHint)
        for (p in parts) {
            assertClose(Geom3.volume(ed.meshOf(p)), 60.0 * 60.0 * 60.0 / 3.0, tol = 1e-6, msg = "both stand 60 high")
        }

        // the shared node is the equality: one edit, both apexes follow — no constraint asserted anywhere
        ed.doc.setParameter(h, 90.0.mm)
        for (p in parts) {
            assertClose(Geom3.volume(ed.meshOf(p)), 60.0 * 60.0 * 90.0 / 3.0, tol = 1e-6, msg = "one parameter moved both")
        }

        // and the whole twin construction replays byte-equal
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the shared height replays byte-equal")
    }
}
