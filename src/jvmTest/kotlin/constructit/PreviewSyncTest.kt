package constructit

import constructit.editor.Appearance
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.exchange.ExportNode
import constructit.exchange.ExportScene
import constructit.exchange.SceneSync
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The regression for the reported preview defect: **changing a parameter added the new body without taking
 * the old one down**, so every edit piled one more stale mesh into the three.js scene. The fake backend
 * below counts exactly what the browser's scene graph held — handles *attached* — driven by a real document
 * and a real parameter edit, which is the reported reproduction verbatim. (The old code disposed the
 * replaced body's GPU resources but never detached its scene object; three.js re-uploads a disposed
 * geometry that is still attached, so the ghosts stayed visible.)
 */
class PreviewSyncTest {
    /** A backend that records what the scene graph would hold. Handles are just serial numbers. */
    private class FakeBackend : SceneSync.Backend<Int> {
        var next = 0
        val attached = LinkedHashMap<Int, String>()
        var restyles = 0

        override fun add(node: ExportNode): Int {
            val h = next++
            attached[h] = node.name
            return h
        }

        override fun remove(handle: Int) {
            // a second remove of the same handle is the detach/dispose split coming apart again
            assertTrue(attached.remove(handle) != null, "handle $handle removed twice")
        }

        override fun material(
            handle: Int,
            material: Appearance,
        ) {
            assertTrue(handle in attached, "restyling a detached handle")
            restyles++
        }
    }

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    @Test
    fun aParameterEditReplacesTheBodyInsteadOfPilingUpGhosts() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 40.0))
        val h = ed.doc.newParameter("h", 20.0.mm)
        ed.activeScalar = h
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(20.0, 0.0))
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SOLID }, ed.statusHint)

        val backend = FakeBackend()
        val sync = SceneSync(backend)
        sync.update(ExportScene.extract(ed.doc, "preview"))
        assertEquals(1, backend.attached.size, "one body on screen")
        assertEquals(1, sync.lastUploads)
        val before = backend.attached.keys.single()

        // the reported gesture: change the parameter — the preview follows the document change
        ed.doc.setParameter(h, 35.0.mm)
        sync.update(ExportScene.extract(ed.doc, "preview"))
        assertEquals(1, backend.attached.size, "the old body LEFT the scene: ${backend.attached}")
        assertEquals(1, sync.lastUploads, "one rebuild, not an accumulation")
        assertTrue(before !in backend.attached, "the replaced handle is the one that left")

        // ...and again, because the report says "more and more": every edit must replace, never add
        ed.doc.setParameter(h, 50.0.mm)
        sync.update(ExportScene.extract(ed.doc, "preview"))
        assertEquals(1, backend.attached.size, "still one body after the second edit")
    }

    @Test
    fun anUnchangedBodyCostsNothingAndARestyleCostsNoUpload() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 40.0))
        ed.setTool(Tools.EXTRUDE)
        for (c in "20") ed.key(c.toString())
        ed.key("Enter")
        ed.click(Vec2(20.0, 0.0))
        val part = ed.doc.elements.filter { it.kind == ElementKind.SOLID }.last()

        val backend = FakeBackend()
        val sync = SceneSync(backend)
        sync.update(ExportScene.extract(ed.doc, "preview"))
        val handle = backend.attached.keys.single()

        // an orbit re-extracts and re-updates with nothing changed: zero uploads is the whole point (OP-5)
        sync.update(ExportScene.extract(ed.doc, "preview"))
        assertEquals(0, sync.lastUploads, "an unchanged document uploads nothing")
        assertEquals(handle, backend.attached.keys.single(), "the same live object stayed")

        // a colour picked in the panel restyles the same handle — never a geometry rebuild
        ed.setMaterial(part, Appearance("#2266aa", roughness = 0.5, metallic = 0.0))
        sync.update(ExportScene.extract(ed.doc, "preview"))
        assertEquals(0, sync.lastUploads, "a restyle is not an upload")
        assertEquals(1, backend.restyles)
        assertEquals(handle, backend.attached.keys.single())
    }

    @Test
    fun aBodyThatDisappearsLeavesTheScene() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 40.0))
        ed.setTool(Tools.EXTRUDE)
        for (c in "20") ed.key(c.toString())
        ed.key("Enter")
        ed.click(Vec2(20.0, 0.0))
        val part = ed.doc.elements.filter { it.kind == ElementKind.SOLID }.last()

        val backend = FakeBackend()
        val sync = SceneSync(backend)
        sync.update(ExportScene.extract(ed.doc, "preview"))
        assertEquals(1, backend.attached.size)

        ed.doc.setElementsVisible(listOf(part), false)
        sync.update(ExportScene.extract(ed.doc, "preview"))
        assertEquals(0, backend.attached.size, "a hidden body is off the screen")
        assertEquals(0, sync.lastUploads)
    }
}
