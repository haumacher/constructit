package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Appearance
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.exchange.ExportScene
import constructit.exchange.Glb
import constructit.exchange.Stl
import constructit.exchange.ThreeMf
import constructit.geom.Geom3
import constructit.geom.Justification
import constructit.geom.Vec2
import constructit.units.mm
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Probes on the export package, composing it with the week's other features: a **drilled** pyramid (a Cut
 * chain — the tip alone must export), **renamed** through the naming authority and **dressed** in Tier-1
 * copper, written by all three writers and read back from the bytes; and a **wall's** extrusion through STL,
 * its volume recomputed from the file's own triangles.
 */
class ExportProbeTest {
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

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(ed: Editor) =
        Evaluator().solid(ed.doc.elements.filter { it.kind == ElementKind.SOLID }.last().ref as SolidRef).mesh

    /** Volume from an STL's own bytes, by the divergence theorem — the file is the authority here. */
    private fun stlVolume(bytes: ByteArray): Pair<Int, Double> {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.position(80)
        val n = b.getInt()
        assertEquals(84 + 50 * n, bytes.size, "the STL size formula")
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
        return n to vol
    }

    @Test
    fun aDrilledRenamedCopperPyramidExportsAsOneNodeInAllThreeFormats() {
        // the pyramid and its face drill (the section-inputs acceptance flow)
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.type("90")
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(50.0, 50.0))
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(30.0, 0.0))
        val slant = sqrt(50.0 * 50.0 + 90.0 * 90.0)
        ed.setTool(Tools.CIRCLE_R)
        ed.type("6")
        ed.click(Vec2(0.0, slant / 2.0))
        ed.setTool(Tools.CUT)
        ed.type("40")
        ed.click(Vec2(6.0, slant / 2.0))
        val part = ed.doc.elements.filter { it.kind == ElementKind.SOLID }.last()
        val drilled = Geom3.volume(meshOf(ed))
        assertTrue(drilled < 300000.0 - 100.0, "the bore took material: $drilled")

        // the naming authority and Tier-1, composed
        assertEquals("korpus", ed.doc.nameElement(part, "korpus"))
        assertNotNull(ed.setMaterial(part, Appearance("#b87333", roughness = 0.35, metallic = 0.9)))

        // the seam: ONE node — the tip of the chain — named as renamed, silent notes
        val scene = ExportScene.extract(ed.doc, "probe")
        assertEquals(1, scene.nodes.size, "the Cut chain exports its tip alone: ${scene.notes}")
        assertEquals("korpus", scene.nodes.single().name)
        assertTrue(scene.notes.isEmpty(), "silence means success: ${scene.notes}")

        // STL: the file's own triangles integrate to the drilled volume
        val (facets, vol) = stlVolume(Stl.write(scene))
        assertTrue(facets > 4, "a drilled solid has more than a tetrahedron's facets")
        assertClose(vol, drilled, tol = 1.0, msg = "the STL is the same body (float32 triangles)")

        // GLB: spells glTF and carries the renamed node and a material
        val glb = Glb.write(scene)
        assertEquals("glTF", glb.copyOfRange(0, 4).decodeToString())
        val json = glb.decodeToString(20, 20 + ByteBuffer.wrap(glb, 12, 4).order(ByteOrder.LITTLE_ENDIAN).getInt())
        assertTrue(json.contains("\"korpus\""), "the node keeps its given name")
        assertTrue(json.contains("baseColorFactor"), "the Tier-1 material rode along")

        // 3MF: unzips, says millimetres, holds one object
        var model = ""
        ZipInputStream(ByteArrayInputStream(ThreeMf.write(scene))).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name.endsWith("3dmodel.model")) model = z.readBytes().decodeToString()
                e = z.nextEntry
            }
        }
        assertTrue(model.contains("unit=\"millimeter\""), "explicit units")
        assertEquals(1, Regex("<object ").findAll(model).count(), "one body")
        assertTrue(model.contains("korpus"), "named in the build")

        // and the whole dressed document still replays byte-equal
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "name + material steps replay")
    }

    @Test
    fun aWallsExtrusionExportsWatertightThroughStl() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.activeScalar = ed.doc.newParameter("d", 20.0.mm)
        ed.setTool(Tools.THICKEN)
        ed.justification = Justification.CENTER
        ed.click(Vec2(50.0, 0.0))
        ed.key("Enter")
        ed.setTool(Tools.EXTRUDE)
        ed.type("80")
        ed.click(Vec2(50.0, 10.0))
        val wall = Geom3.volume(meshOf(ed))
        assertClose(wall, 100.0 * 20.0 * 80.0, tol = 1e-6, msg = "the 3D wall: ${ed.statusHint}")

        val (_, vol) = stlVolume(Stl.write(ExportScene.extract(ed.doc, "wall")))
        assertClose(vol, wall, tol = 1.0, msg = "the STL carries the watertight wall (float32 triangles)")
    }
}
