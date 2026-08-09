package constructit

import constructit.core.Node
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Scene3
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The probe review of the picture's quality** — the coarse frame composed with the refusal family and the
 * validity channel.
 *
 * The law says quality belongs to the picture; the refusals say a fold is decided from the *feature*, before a
 * triangle exists. Put together they make a promise the delivery's suite never staged: an edit that folds a
 * body **mid-drag** must refuse on the very next coarse frame — spoken through the validity channel, the body
 * gone from the coarse scene — must heal on a later coarse frame just as live, and the whole episode must cost
 * **zero fine meshes**, because a refusal never needs a triangle and the picture only ever asked for coarse.
 */
class MeshQualityProbeTest {
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

    private fun nodes(ed: Editor): List<Node> {
        val seen = LinkedHashMap<Node, Unit>()

        fun walk(n: Node) {
            if (seen.put(n, Unit) != null) return
            n.inputs.forEach { walk(it) }
        }
        ed.doc.elements.forEach { walk(it.ref.node) }
        ed.doc.scalars.forEach { walk(it.ref.node) }
        return seen.keys.toList()
    }

    private fun fineMeshes(ed: Editor): Int = nodes(ed).sumOf { it.meshCount }

    private fun paint3d(ed: Editor) {
        Scene3.extract(ed.doc, ghosts = ed.ghostElements(), quality = ed.viewQuality)
    }

    @Test
    fun aFoldBornOnACoarseFrameSpeaksHealsAndCostsNoFineMesh() {
        val ed = Editor()
        ed.setTool(Tools_POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools_HELIX)
        ed.type("20")
        ed.type("30")
        ed.type("3")
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools_TUBE)
        ed.type("8")
        ed.click(Vec2(0.0, -20.0))
        val tube = ed.doc.elements.last { it.kind == ElementKind.SOLID }
        assertTrue(ed.invalidElements.isEmpty(), "a 16 mm tube fits a 30 mm pitch: ${ed.validityNote}")
        paint3d(ed)
        val fineAtRest = fineMeshes(ed)

        // mid-drag: squeeze the pitch under the tube's own clearance — the very next coarse frame must refuse
        val pitch = ed.doc.scalars.single { it.name == "pitch" }
        ed.interacting = true
        assertTrue(ed.setParameter(pitch, 12.0), "the edit itself is legal (OP-3)")
        paint3d(ed)
        val note = assertNotNull(ed.validityNote, "the fold is spoken on the coarse frame, not after it")
        assertTrue(note.contains(ed.doc.nameOf(tube)), "…naming the tube: $note")
        val scene = Scene3.extract(ed.doc, ghosts = ed.ghostElements(), quality = ed.viewQuality)
        assertTrue(scene.solids.none { it.elementId == tube.id }, "the folded body contributes nothing to the picture")

        // heal on a later coarse frame, just as live
        assertTrue(ed.setParameter(pitch, 30.0), "…and back")
        paint3d(ed)
        assertNull(ed.validityNote?.takeIf { it.contains("can't be built") }, "healed mid-drag: ${ed.validityNote}")
        assertTrue(ed.invalidElements.isEmpty(), "nothing is left unbuildable")

        // the whole episode was pictures and features only: not one fine mesh was built
        assertEquals(fineAtRest, fineMeshes(ed), "a refusal is decided from the feature — no number asked, no fine mesh paid")
        ed.interacting = false
    }

    private companion object {
        const val Tools_POINT = "point"
        const val Tools_HELIX = "helix"
        const val Tools_TUBE = "tube"
    }
}
