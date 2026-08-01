package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.exchange.ExportFormat
import constructit.exchange.ExportNode
import constructit.exchange.ExportScene
import constructit.exchange.Exports
import constructit.exchange.SceneSync
import constructit.exchange.Stl
import constructit.geom.Geom3
import constructit.geom.Vec2
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Probes on the JT import, composing it with what the delivery never exercised together: a **boolean carved
 * out of an imported body** and pushed back out through the export seam (the full circle: construct → JT →
 * import → subtract → STL), and the imported literal riding the **preview's incremental contract** — same
 * pointer when nothing moved, one rebuild when its anchor is dragged.
 */
class JtImportProbeTest {
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

    /** A 40 x 40 x 20 box named by the user, exported to JT bytes — the "vendor file" of both probes. */
    private fun vendorBytes(): ByteArray {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 40.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("20")
        ed.click(Vec2(20.0, 0.0))
        val part = ed.doc.elements.filter { it.kind == ElementKind.SOLID }.last()
        assertEquals("rohteil", ed.doc.nameElement(part, "rohteil"))
        val result = Exports.export(ed.doc, "rohteil", ExportFormat.JT)
        assertTrue(result.ok, result.message)
        return result.bytes!!
    }

    @Test
    fun aBooleanCarvesTheImportedBodyAndTheResultLeavesThroughStl() {
        val ed = Editor()
        val imported = ed.importFile(vendorBytes(), "rohteil.jt")
        assertTrue(imported.ok, imported.message)
        assertTrue(imported.refusals.isEmpty(), "the exported box is watertight: ${imported.refusals}")
        assertTrue(imported.bodies.any { it.contains("rohteil") }, "the user's name crossed both formats: ${imported.bodies}")

        // a constructed pocket, overlapping the reference body by 10 x 20 x 20
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(30.0, 10.0))
        ed.click(Vec2(70.0, 30.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("20")
        ed.click(Vec2(50.0, 10.0))

        // subtract the constructed solid FROM the imported one: the literal is a true boolean operand
        ed.setTool(Tools.SUBTRACT)
        ed.click(Vec2(20.0, 40.0))
        ed.click(Vec2(50.0, 10.0))
        val cut = ed.doc.elements.filter { it.kind == ElementKind.SOLID }.last()

        @Suppress("UNCHECKED_CAST")
        val mesh = Evaluator().solid(cut.ref as SolidRef).mesh
        assertManifold(mesh, "the imported body with a constructed bite taken out")
        assertClose(Geom3.volume(mesh), 40.0 * 40.0 * 20.0 - 4000.0, tol = 2.0, msg = "32000 minus the 4000 overlap (float32 literal)")

        // and out again: the whole chain ends in STL bytes whose own triangles integrate to the same body
        val scene = ExportScene.extract(ed.doc, "carved")
        val stl = Stl.write(scene)
        val b = ByteBuffer.wrap(stl).order(ByteOrder.LITTLE_ENDIAN)
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
        val exported = scene.nodes.sumOf { Geom3.volume(it.mesh) }
        assertClose(vol, exported, tol = 1.0, msg = "construct -> JT -> import -> subtract -> STL, one body all the way")

        // the journal replays the whole story byte-equal — including the embedded literal
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the imported literal replays byte-equal")
    }

    /** The preview's diff over an imported body: free when nothing moved, one rebuild when its anchor drags. */
    private class FakeBackend : SceneSync.Backend<Int> {
        var next = 0
        val attached = LinkedHashMap<Int, String>()

        override fun add(node: ExportNode): Int {
            val h = next++
            attached[h] = node.name
            return h
        }

        override fun remove(handle: Int) {
            assertTrue(attached.remove(handle) != null, "handle $handle removed twice")
        }

        override fun material(
            handle: Int,
            material: constructit.editor.Appearance,
        ) {
            assertTrue(handle in attached, "restyling a detached handle")
        }
    }

    @Test
    fun theImportedLiteralRidesThePreviewsIncrementalContract() {
        val ed = Editor()
        assertTrue(ed.importFile(vendorBytes(), "rohteil.jt").ok, ed.statusHint)
        val backend = FakeBackend()
        val sync = SceneSync(backend)
        sync.update(ExportScene.extract(ed.doc, "p"))
        assertEquals(1, backend.attached.size, "one reference body on screen")

        // nothing moved: the literal's value is the same object (OP-5 on an empty-input node), zero uploads
        sync.update(ExportScene.extract(ed.doc, "p"))
        assertEquals(0, sync.lastUploads, "an untouched import costs the preview nothing")

        // drag the placement anchor: the same body rebuilds once — never accumulates
        ed.setTool(Tools.SELECT)
        val from = ed.camera.worldToScreen(Vec2(0.0, 0.0))
        val to = ed.camera.worldToScreen(Vec2(15.0, 5.0))
        ed.pointerDown(from)
        ed.pointerMove(to)
        ed.pointerUp(to)
        sync.update(ExportScene.extract(ed.doc, "p"))
        assertEquals(1, backend.attached.size, "still exactly one body after the drag")
        assertEquals(1, sync.lastUploads, "one rebuild for one moved body")

        val placed = ed.doc.elements.filter { it.kind == ElementKind.SOLID }.last()

        @Suppress("UNCHECKED_CAST")
        val mesh = Evaluator().solid(placed.ref as SolidRef).mesh
        assertClose(Geom3.volume(mesh), 40.0 * 40.0 * 20.0, tol = 1.0, msg = "a placement drag changes no volume")
        val lo = Geom3.bounds(mesh)!!.first
        assertClose(lo.x, 15.0, tol = 1e-6, msg = "the body followed its anchor")
        assertClose(lo.y, 5.0, tol = 1e-6, msg = "the body followed its anchor")
    }
}
