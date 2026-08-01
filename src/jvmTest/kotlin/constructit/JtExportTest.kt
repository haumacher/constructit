package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Appearance
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Mat4
import constructit.editor.Tools
import constructit.exchange.ExportFormat
import constructit.exchange.ExportNode
import constructit.exchange.ExportScene
import constructit.exchange.Exports
import constructit.exchange.Jt
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.geom.Vec3
import de.haumacher.kotlinjt.scene.LengthUnit
import de.haumacher.kotlinjt.scene.Mesh
import de.haumacher.kotlinjt.scene.SceneNode
import de.haumacher.kotlinjt.scene.readScene
import de.haumacher.kotlinjt.write.JtWriteException
import de.haumacher.kotlinjt.write.writeJt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import de.haumacher.kotlinjt.scene.Mat4 as JtMat4

/**
 * **The JT export, read back through the sibling library's own reader** — the loop the queue entry asked to
 * close: gestures in, `writeJt` bytes out, `readScene` in again, and every statement the file makes checked
 * against the drawing that made it.
 *
 * The assertions are deliberately *semantic* rather than structural: the name the naming authority gave, the
 * **volume** recomputed from the triangles the file hands back (which says the corners came out in the right
 * order at the right coordinates, not merely that a mesh is present), the declared unit, and the material. The
 * bytes themselves are the library's business and are tested there — this file tests the adapter, which is the
 * only part of the route ConstructIt owns.
 */
class JtExportTest {
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

    /** A 100 × 100 plan square with its apex 90 mm over the centre, bored 6 mm through a slanted face. */
    private fun drilledPyramid(): Editor {
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
        return ed
    }

    /** The enclosed volume of a re-read mesh, by the divergence theorem — the file is the authority. */
    private fun volumeOf(mesh: Mesh): Double {
        var sum = 0.0
        for (t in mesh.triangles) {
            val a = mesh.positions[t.v0]
            val b = mesh.positions[t.v1]
            val c = mesh.positions[t.v2]
            sum +=
                a.x.toDouble() * (b.y.toDouble() * c.z.toDouble() - c.y.toDouble() * b.z.toDouble()) -
                b.x.toDouble() * (a.y.toDouble() * c.z.toDouble() - c.y.toDouble() * a.z.toDouble()) +
                c.x.toDouble() * (a.y.toDouble() * b.z.toDouble() - b.y.toDouble() * a.z.toDouble())
        }
        return sum / 6.0
    }

    /**
     * **The acceptance.** A drilled, renamed, dressed part exported to JT and read back: one named part,
     * millimetres declared, the drilled volume recomputed from the file's own triangles, the Tier-1 material
     * where a viewer looks for it.
     */
    @Test
    fun aDrilledRenamedCopperPartRoundTripsThroughKotlinJt() {
        val ed = drilledPyramid()
        val part = ed.doc.elements.filter { it.kind == ElementKind.SOLID }.last()
        val drilled = Geom3.volume(meshOf(ed))
        assertEquals("korpus", ed.doc.nameElement(part, "korpus"))
        assertNotNull(ed.setMaterial(part, Appearance("#b87333", roughness = 0.35, metallic = 0.9)))

        val result = Exports.export(ed.doc, "probe", ExportFormat.JT)
        assertTrue(result.ok, result.message)
        assertEquals("probe.jt", result.fileName)
        assertTrue(result.message.contains("1 solid"), result.message)
        assertFalse(result.message.contains("("), "silence means success: ${result.message}")

        val scene = readScene(result.bytes!!)
        assertTrue(scene.notes.isEmpty(), "the library read it back with nothing to complain about: ${scene.notes}")
        assertEquals(LengthUnit.MILLIMETERS, scene.units, "the unit is declared in the file, never assumed")
        assertEquals("probe", scene.root.name, "the drawing names the root")

        val body = scene.root.children.single()
        assertEquals("korpus", body.name, "OP-18's naming authority rode all the way into the file")
        assertEquals(JtMat4.IDENTITY, body.transform, "the kernel emits world-space meshes, so the placement is identity")

        // the volume, from the file's own triangles — the strong statement about winding and coordinates
        val mesh = body.meshes.single()
        assertTrue(mesh.triangles.size > 4, "a drilled solid has more than a tetrahedron's facets")
        assertClose(volumeOf(mesh), drilled, tol = 1.0, msg = "the JT holds the same body (float32 vertices)")
        assertTrue(mesh.normals.isNotEmpty(), "normals are bound, so a viewer shades the bore the way the preview does")
        assertTrue(mesh.triangles.all { it.n0 >= 0 && it.n1 >= 0 && it.n2 >= 0 }, "every corner names its normal")

        // the material: base colour and roughness survive; metalness is not JT's concept and comes back 0
        val material = assertNotNull(body.material, "the Tier-1 material rode along")
        val linear = Appearance("#b87333").linearRgb()
        assertClose(material.baseColor.r.toDouble(), linear[0], 1e-6, "linear RGB, from the one parse")
        assertClose(material.baseColor.g.toDouble(), linear[1], 1e-6)
        assertClose(material.baseColor.b.toDouble(), linear[2], 1e-6)
        assertClose(material.baseColor.a.toDouble(), 1.0, 1e-6, "opaque")
        assertClose(material.roughness.toDouble(), 0.35, 1e-5, "roughness → Phong shininess → roughness")
        assertEquals(0f, material.metallic, "JT's material is Phong: metalness has no counterpart and none is invented")
    }

    /**
     * **The transform mapping, on a matrix that is not the identity** — because "identity survives" would
     * prove nothing about two libraries with opposite conventions.
     *
     * `constructit.editor.Mat4` is column-major/column-vector; the library's is row-major/row-vector. The two
     * transposes cancel, so the sixteen numbers copy across — which is exactly the claim under test here,
     * asserted three ways: the elements land where the library says translation lives (12–14), the library
     * transforms a point to the same place ConstructIt does, and the placement survives the file.
     */
    @Test
    fun aNonIdentityPlacementSurvivesTheMappingAndTheFile() {
        // a quarter turn about Z, then a translation — column-major, the layout `Mat4.transform` reads
        val placed =
            Mat4(
                doubleArrayOf(
                    0.0, 1.0, 0.0, 0.0,
                    -1.0, 0.0, 0.0, 0.0,
                    0.0, 0.0, 1.0, 0.0,
                    7.0, -3.0, 11.0, 1.0,
                ),
            )
        val cube = ExportScene.extract(box().doc, "placed").nodes.single().mesh
        val scene = ExportScene("placed", listOf(ExportNode("block", cube, Appearance.DEFAULT, placed)))

        val mapped = Jt.scene(scene).root.children.single().transform
        assertEquals(listOf(7.0, -3.0, 11.0), mapped.values.subList(12, 15), "translation lives in elements 12–14")
        for (p in listOf(Vec3(1.0, 2.0, 3.0), Vec3(-4.0, 0.5, 2.0))) {
            val here = placed.transform(Vec3(p.x, p.y, p.z))
            val there = mapped.transformPoint(de.haumacher.kotlinjt.scene.Vec3(p.x.toFloat(), p.y.toFloat(), p.z.toFloat()))
            assertClose(there.x.toDouble(), here.x, 1e-6, "the same point, both conventions")
            assertClose(there.y.toDouble(), here.y, 1e-6)
            assertClose(there.z.toDouble(), here.z, 1e-6)
        }

        val back = readScene(Jt.write(scene)).root.children.single()
        assertEquals("block", back.name)
        assertEquals(mapped.values, back.transform.values, "the placement is in the file, element for element")
    }

    /**
     * **The refusals — none of which this adapter can reach, which is the assertion.**
     *
     * The library refuses a scene its own reader would hand back differently: an undeclared unit, a node with
     * geometry *and* children, more than ten LOD tiers, and the two children its structural collapse would
     * splice out or absorb (an unnamed identity-transform child). Every one of those is unreachable through
     * [Jt] **by construction**, and the reasons are structural rather than lucky: the unit is always declared,
     * the root carries no geometry, a body node carries no children, one mesh is one tier, and an export name
     * is never empty (the naming authority falls back to the script name `e7`, and clearing a user name
     * restores exactly that). So this test asserts the shape rather than inventing a refusal — and then proves
     * the refusal path is real by handing the writer a scene the *seam's own constructors* allow but the
     * extractor never produces: a body with no name.
     */
    @Test
    fun theWritersRefusalsAreUnreachableThroughThisAdapterAndStillSpeak() {
        val ed = box()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(150.0, 0.0))
        ed.click(Vec2(200.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("h", constructit.units.Quantity.mm(20.0))
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(175.0, 0.0))
        val scene = Jt.scene(ExportScene.extract(ed.doc, "two"))

        assertEquals(LengthUnit.MILLIMETERS, scene.units, "never UNSPECIFIED — the unit refusal cannot fire")

        fun check(node: SceneNode) {
            val geometry = node.meshes.isNotEmpty() || node.polylines.isNotEmpty()
            assertFalse(geometry && node.children.isNotEmpty(), "no node carries geometry and children")
            assertTrue(node.meshes.size <= 1, "one mesh per body is one LOD tier, far under the ten Table 6 allows")
            for (child in node.children) {
                assertTrue(child.name.isNotEmpty(), "an export name is never empty, so no child is spliced or absorbed")
                check(child)
            }
        }
        check(scene.root)
        assertEquals(2, scene.root.children.size)
        // and the whole scene writes, which is the same statement made by the writer itself
        assertTrue(writeJt(scene).isNotEmpty())

        // the refusal path, reached the only way it can be: a hand-built scene with an unnamed body
        val nameless =
            ExportScene("nameless", listOf(ExportNode("", ExportScene.extract(box().doc, "b").nodes.single().mesh, Appearance.DEFAULT)))
        val why = assertFailsWith<JtWriteException> { Jt.write(nameless) }
        assertTrue(why.message!!.contains("unnamed"), "the refusal names what is wrong: ${why.message}")
    }

    /** The empty drawing is refused before any writer runs — one refusal, shared by all four formats. */
    @Test
    fun theEmptyDrawingRefusalSpeaksOnTheJtPathToo() {
        val result = Exports.export(Editor().doc, "empty", ExportFormat.JT)
        assertFalse(result.ok)
        assertEquals("empty.jt", result.fileName)
        assertTrue(result.message.contains("nothing to export"), result.message)
        assertTrue(result.message.contains("Extrude"), "the refusal says what to do about it: ${result.message}")
    }

    /** Two visible solids, two named children — the structure tree is what JT is exported *for*. */
    @Test
    fun twoBodiesBecomeTwoDistinctlyNamedParts() {
        val ed = box()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(150.0, 0.0))
        ed.click(Vec2(200.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("h", constructit.units.Quantity.mm(20.0))
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(175.0, 0.0))
        val solids = ed.doc.elements.filter { it.kind == ElementKind.SOLID }
        assertEquals("plate", ed.doc.nameElement(solids.first(), "plate"))

        val result = Exports.export(ed.doc, "assembly", ExportFormat.JT)
        assertTrue(result.ok, result.message)
        assertTrue(result.message.contains("2 solids"), result.message)
        val scene = readScene(result.bytes!!)
        val names = scene.root.children.map { it.name }
        assertEquals(2, names.size, "two bodies, two parts")
        assertEquals(names.size, names.toSet().size, "and their names are distinct: $names")
        assertTrue(names.contains("plate"), "the renamed one is in the file: $names")
        assertTrue(scene.root.children.all { it.meshes.single().triangles.isNotEmpty() }, "each part carries its mesh")
    }

    /** A 100 × 100 × 40 block, the simplest body these tests need. */
    private fun box(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.activeScalar = ed.doc.newParameter("t", constructit.units.Quantity.mm(40.0))
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(50.0, 0.0))
        return ed
    }
}
