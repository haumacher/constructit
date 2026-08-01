package constructit

import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.exchange.ExportFormat
import constructit.exchange.ExportScene
import constructit.exchange.Exports
import constructit.exchange.ThreeMf
import constructit.geom.Mesh3
import constructit.geom.Tri
import constructit.geom.Vec2
import constructit.geom.Vec3
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The 3MF package, unzipped and read back** — with the JVM's own `ZipInputStream`, which is the point: a
 * container this project writes by hand in `commonMain` has to be a container the platform's unzipper accepts,
 * or a slicer will not open it either.
 *
 * The two things that make 3MF worth writing rather than an STL are asserted directly: the **unit is stated**
 * (`unit="millimeter"`, the engine's own canonical base, so nothing is converted) and the **mesh is manifold
 * with consistent orientation**, which the spec requires and which this writer re-checks at the boundary and
 * refuses by name.
 */
class ThreeMfExportTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun pyramid(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.type("90")
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(50.0, 50.0))
        return ed
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                out[e.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    /** The container: exactly the three core parts, in the order a reader looks for them. */
    @Test
    fun thePackageHoldsTheThreeCoreParts() {
        val parts = unzip(ThreeMf.write(ExportScene.extract(pyramid().doc, "pyramid")))
        assertEquals(
            listOf("[Content_Types].xml", "_rels/.rels", "3D/3dmodel.model"),
            parts.keys.toList(),
            "core spec only: content types, the package relationship, the model",
        )
        assertTrue(parts["[Content_Types].xml"]!!.contains(ThreeMf.MODEL_CONTENT_TYPE))
        assertTrue(parts["[Content_Types].xml"]!!.contains(ThreeMf.RELS_CONTENT_TYPE))
        val rels = parts["_rels/.rels"]!!
        assertTrue(rels.contains("""Target="/3D/3dmodel.model""""), rels)
        assertTrue(rels.contains(ThreeMf.MODEL_RELATIONSHIP), rels)
    }

    /**
     * The model part: the unit, one object per body with the drawing's own name, the vertex and triangle
     * counts, and a build item that actually places it. An object nothing builds is an object no slicer prints.
     */
    @Test
    fun theModelStatesItsUnitAndPlacesEveryObject() {
        val ed = pyramid()
        val solid = ed.doc.elements.single { it.kind == ElementKind.SOLID }
        val scene = ExportScene.extract(ed.doc, "pyramid")
        val mesh = scene.nodes.single().mesh
        val model = unzip(ThreeMf.write(scene))["3D/3dmodel.model"]!!

        assertTrue(model.contains("""unit="millimeter""""), "the unit, stated — the whole reason for this format")
        assertTrue(model.contains("""xmlns="${ThreeMf.CORE_NAMESPACE}""""), model.take(400))
        assertTrue(model.contains("""<object id="1" type="model" name="${ed.doc.nameOf(solid)}">"""), "named by the authority")
        assertEquals(mesh.vertexCount, Regex("<vertex ").findAll(model).count(), "every vertex is written")
        assertEquals(mesh.triangleCount, Regex("<triangle ").findAll(model).count(), "...and every triangle")
        assertEquals(1, Regex("""<item objectid="1"/>""").findAll(model).count(), "the build places it exactly once")
        // no exponent anywhere: XML's double type allows one, and slicers in the wild have choked on it
        assertTrue(!model.contains("E-") && !model.contains("e-"), "no scientific notation in the coordinates")
    }

    /** The mesh survives the round trip through XML numerically: the vertices parsed back are the mesh's own. */
    @Test
    fun theMeshRoundTripsNumerically() {
        val scene = ExportScene.extract(pyramid().doc, "pyramid")
        val mesh = scene.nodes.single().mesh
        val model = unzip(ThreeMf.write(scene))["3D/3dmodel.model"]!!

        val vertices =
            Regex("""<vertex x="([^"]+)" y="([^"]+)" z="([^"]+)"/>""").findAll(model).map {
                Vec3(it.groupValues[1].toDouble(), it.groupValues[2].toDouble(), it.groupValues[3].toDouble())
            }.toList()
        assertEquals(mesh.vertexCount, vertices.size)
        for ((i, p) in mesh.vertices.withIndex()) {
            assertClose(vertices[i].x, p.x, 1e-6, "vertex $i")
            assertClose(vertices[i].y, p.y, 1e-6, "vertex $i")
            assertClose(vertices[i].z, p.z, 1e-6, "vertex $i")
        }
        val triangles =
            Regex("""<triangle v1="(\d+)" v2="(\d+)" v3="(\d+)"/>""").findAll(model).map {
                Tri(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
            }.toList()
        assertEquals(mesh.triangles, triangles, "the indices are the mesh's own, in emission order")
        // ...and the round-tripped mesh is still the watertight, outward-wound one the spec requires
        assertManifold(Mesh3(vertices, triangles), "the 3MF mesh, parsed back")
    }

    /** Two bodies: two objects, two build items — a 3MF keeps them apart, which is why it beats an STL. */
    @Test
    fun twoBodiesStayTwoObjects() {
        val ed = pyramid()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(150.0, 0.0))
        ed.click(Vec2(200.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("h", constructit.units.Quantity.mm(20.0))
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(175.0, 0.0))
        val model = unzip(ThreeMf.write(ExportScene.extract(ed.doc, "two")))["3D/3dmodel.model"]!!
        assertEquals(2, Regex("<object ").findAll(model).count())
        assertTrue(model.contains("""<item objectid="1"/>""") && model.contains("""<item objectid="2"/>"""), model)
    }

    /**
     * **Watertight or refused, by name** (OP-9) — checked at the boundary rather than assumed. The kernel
     * guarantees it upstream; a guarantee never checked where the file leaves the app is a guarantee that
     * quietly stops holding.
     */
    @Test
    fun aBrokenMeshIsRefusedByName() {
        val good = ExportScene.extract(pyramid().doc, "pyramid")
        assertNull(ThreeMf.check(good), "a real solid passes")

        // an open surface: one triangle, three edges with no partners
        val open =
            ExportScene(
                "broken",
                listOf(
                    constructit.exchange.ExportNode(
                        "e42",
                        Mesh3(listOf(Vec3.ZERO, Vec3.X, Vec3.Y), listOf(Tri(0, 1, 2))),
                        constructit.editor.Appearance.DEFAULT,
                    ),
                ),
            )
        val why = ThreeMf.check(open)
        assertTrue(why != null && why.contains("e42"), "the refusal names the body: $why")
        assertTrue(why!!.contains("not closed"), why)
    }

    /** The export entry point refuses in the same words rather than writing a file a slicer will reject. */
    @Test
    fun theExportRefusesRatherThanWriteAnUnprintableFile() {
        val result = Exports.export(pyramid().doc, "widget", ExportFormat.THREE_MF)
        assertTrue(result.ok, result.message)
        assertEquals("widget.3mf", result.fileName)
        assertTrue(result.message.contains("1 solid"), result.message)
        // a ZIP is what came out: the local file header's signature, "PK"
        assertEquals(listOf(0x50, 0x4b, 0x03, 0x04), result.bytes!!.take(4).map { it.toInt() and 0xff })
    }
}
