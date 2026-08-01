package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Appearance
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.exchange.ExportFormat
import constructit.exchange.Exports
import constructit.geom.Geom3
import constructit.geom.Vec2
import de.haumacher.kotlinjt.scene.LengthUnit
import de.haumacher.kotlinjt.scene.Mesh
import de.haumacher.kotlinjt.scene.readScene
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Probes on the JT export, composing it with features the package never saw together: a **conic** body (an
 * elliptic prism) sharing one file with a **face-drilled, non-ASCII-renamed, copper-dressed** plate; a
 * **hidden body** staying out of the file with its name spoken in the result; and the writer's
 * byte-determinism claim held **across a save → load replay** of the whole document.
 */
class JtProbeTest {
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

    /** Volume from the JT scene's own triangles, by the divergence theorem — the file is the authority. */
    private fun volumeOf(mesh: Mesh): Double {
        var vol = 0.0
        for (t in mesh.triangles) {
            val a = mesh.positions[t.v0]
            val b = mesh.positions[t.v1]
            val c = mesh.positions[t.v2]
            vol += (
                a.x.toDouble() * (b.y.toDouble() * c.z.toDouble() - c.y.toDouble() * b.z.toDouble()) -
                    b.x.toDouble() * (a.y.toDouble() * c.z.toDouble() - c.y.toDouble() * a.z.toDouble()) +
                    c.x.toDouble() * (a.y.toDouble() * b.z.toDouble() - b.y.toDouble() * a.z.toDouble())
            ) / 6.0
        }
        return vol
    }

    @Test
    fun aDrilledUmlautPlateAndAnEllipticPrismShareOneJtFile() {
        // the plate, drilled through its front face (the OP-17 face-space flow): 80 x 50, 20 thick,
        // an 8 mm bore through the middle of the face, swept past the far side
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(200.0, 0.0))
        ed.click(Vec2(280.0, 50.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("20")
        ed.click(Vec2(240.0, 0.0))
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(240.0, 0.0))
        assertTrue(ed.activeSpace.isFace, "on the plate's front face: ${ed.statusHint}")
        ed.setTool(Tools.CIRCLE_R)
        ed.type("8")
        ed.click(Vec2(40.0, 10.0))
        ed.setTool(Tools.CUT)
        ed.type("60")
        ed.click(Vec2(48.0, 10.0))
        val plate = ed.doc.elements.filter { it.kind == ElementKind.SOLID }.last()

        @Suppress("UNCHECKED_CAST")
        val plateMesh = Evaluator().solid(plate.ref as SolidRef).mesh
        assertManifold(plateMesh, "the drilled plate")
        val drilled = Geom3.volume(plateMesh)
        assertTrue(drilled < 80.0 * 50.0 * 20.0 - 9000.0, "the bore took material: $drilled")

        // the naming authority, stressed with a non-ASCII name, and a Tier-1 dress
        assertEquals("träger", ed.doc.nameElement(plate, "träger"))
        ed.setMaterial(plate, Appearance("#b87333", roughness = 0.35, metallic = 0.9))

        // the second body: an elliptic prism (the conics package), drawn back in the plan
        assertTrue(ed.setActiveSpace("plan"))
        ed.setTool(Tools.ELLIPSE)
        ed.click(Vec2(50.0, 100.0))
        ed.click(Vec2(110.0, 100.0))
        ed.click(Vec2(50.0, 130.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("50")
        ed.click(Vec2(110.0, 100.0))
        // four solid elements: the plate, the bore's cylinder (construction material, never exported),
        // the drilled tip, the prism — of which the JT must carry exactly the two outputs
        assertEquals(4, ed.doc.elements.count { it.kind == ElementKind.SOLID }, ed.statusHint)

        val result = Exports.export(ed.doc, "probe", ExportFormat.JT)
        assertTrue(result.ok, result.message)
        assertEquals("probe.jt", result.fileName)

        // read back through the library's own reader: TWO bodies (the Cut chain exports its tip alone),
        // the umlaut name intact, millimetres declared, and the file's triangles carrying the volumes
        val scene = readScene(result.bytes!!)
        assertEquals(LengthUnit.MILLIMETERS, scene.units, "millimetres are declared, not assumed")
        assertTrue(scene.notes.isEmpty(), "the read has nothing to refuse: ${scene.notes}")
        assertEquals("probe", scene.root.name)
        assertEquals(2, scene.root.children.size, "the Cut chain exports its tip alone, plus the prism")
        val traeger = assertNotNull(scene.root.children.find { it.name == "träger" }, "the non-ASCII rename survives")
        assertClose(volumeOf(traeger.meshes.single()), drilled, tol = 1.0, msg = "the JT carries the drilled plate (float32)")
        val prism = scene.root.children.single { it !== traeger }
        val prismVol = volumeOf(prism.meshes.single())
        val analytic = PI * 60.0 * 30.0 * 50.0
        assertTrue(
            prismVol < analytic && prismVol > 0.995 * analytic,
            "the elliptic body crossed the format whole: $prismVol vs $analytic",
        )
    }

    @Test
    fun aHiddenBodyStaysOutByNameAndTheBytesAreDeterministicAcrossReplay() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 40.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("20")
        ed.click(Vec2(20.0, 0.0))
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(100.0, 0.0))
        ed.click(Vec2(150.0, 40.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("30")
        ed.click(Vec2(125.0, 0.0))
        val solids = ed.doc.elements.filter { it.kind == ElementKind.SOLID }
        assertEquals(2, solids.size, ed.statusHint)

        // hide the first body — through the journaled step (OP-18), so the replay below keeps it hidden:
        // the JT holds one part, and the export's message NAMES the hidden one
        assertEquals(1, ed.doc.setElementsVisible(listOf(solids[0]), false))
        val result = Exports.export(ed.doc, "pair", ExportFormat.JT)
        assertTrue(result.ok, result.message)
        assertTrue(result.message.contains("hidden"), "the note rides the result: ${result.message}")
        val scene = readScene(result.bytes!!)
        assertEquals(1, scene.root.children.size, "the hidden body is not in the file")
        assertClose(
            volumeOf(scene.root.children.single().meshes.single()),
            50.0 * 40.0 * 30.0,
            tol = 1e-3,
            msg = "the visible body is the one that shipped",
        )

        // determinism, twice: the same document gives byte-identical JT, and so does its save → load replay
        assertContentEquals(result.bytes, Exports.export(ed.doc, "pair", ExportFormat.JT).bytes, "same scene, same bytes")
        val replayed = DocumentFormat.load(DocumentFormat.save(ed.doc))
        assertContentEquals(
            result.bytes,
            Exports.export(replayed, "pair", ExportFormat.JT).bytes,
            "a replayed document exports the identical JT — evaluation is deterministic end to end",
        )
    }
}
