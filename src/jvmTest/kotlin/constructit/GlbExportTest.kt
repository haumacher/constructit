package constructit

import constructit.editor.Appearance
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.exchange.ExportFormat
import constructit.exchange.ExportScene
import constructit.exchange.Exports
import constructit.exchange.Glb
import constructit.exchange.RenderMesh
import constructit.geom.Vec2
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The GLB bytes, parsed back structurally** — the container, the two spec conversions, the accessors and the
 * material — plus a byte golden for the acceptance pyramid.
 *
 * A writer of a binary format is only as good as something that reads it: every assertion here reads the file
 * the way a viewer does (magic, version, declared length, chunk alignment, accessor offsets into the binary
 * chunk) rather than trusting the writer's own arithmetic. The golden then pins the whole thing, which is what
 * turns a later "harmless" edit to the writer into a failing test instead of a file that opens crooked in
 * somebody else's viewer.
 */
class GlbExportTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    /** The acceptance pyramid: a 100 × 100 plan square with its apex 90 mm over the centre. */
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

    /** A GLB read back: the JSON chunk as text, the binary chunk as bytes. */
    private class Parsed(val json: String, val bin: ByteArray, val total: Int)

    private fun parse(bytes: ByteArray): Parsed {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(Glb.MAGIC, b.getInt(), "the first four bytes spell glTF")
        assertEquals("glTF", String(bytes, 0, 4, Charsets.US_ASCII), "...and they are readable as that word")
        assertEquals(2, b.getInt(), "glTF 2.0")
        val total = b.getInt()
        assertEquals(bytes.size, total, "the declared length is the file's length")
        var json: String? = null
        var bin: ByteArray? = null
        while (b.hasRemaining()) {
            val len = b.getInt()
            val type = b.getInt()
            assertEquals(0, len % 4, "every chunk length is a multiple of four (padding included)")
            val data = ByteArray(len)
            b.get(data)
            when (type) {
                0x4E4F534A -> json = String(data, Charsets.UTF_8)
                0x004E4942 -> bin = data
                else -> throw AssertionError("unknown chunk type $type")
            }
        }
        assertTrue(json != null && bin != null, "a GLB has both chunks")
        assertTrue(json!!.trimEnd(' ').endsWith("}"), "the JSON chunk is padded with spaces, so it stays text: '$json'")
        return Parsed(json, bin!!, total)
    }

    /** The value of a `"key":` in the JSON, as raw text up to the matching delimiter — enough to assert on. */
    private fun field(
        json: String,
        key: String,
    ): String {
        val at = json.indexOf("\"$key\":")
        assertTrue(at >= 0, "the JSON has a '$key': $json")
        var i = at + key.length + 3
        var depth = 0
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            if (c == '[' || c == '{') depth++
            if (c == ']' || c == '}') {
                if (depth == 0) break
                depth--
            }
            if (c == ',' && depth == 0) break
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun numbers(s: String): List<Double> =
        Regex("-?\\d+(?:\\.\\d+)?").findAll(s).map { it.value.toDouble() }.toList()

    /**
     * The container and the **two spec conversions**, which are the whole reason a mesh format can be written
     * honestly without a compliance project: glTF is metres and +Y-up by specification, so the root node scales
     * by 0.001 and turns the Z-up world by −90° about X — once, at the root, so every vertex in the file is
     * still the model's own millimetre number.
     */
    @Test
    fun theRootCarriesTheUnitAndTheUpAxisConversion() {
        val ed = pyramid()
        val glb = Glb.write(ExportScene.extract(ed.doc, "pyramid"))
        val p = parse(glb)

        val rotation = numbers(field(p.json, "rotation"))
        val root = -kotlin.math.sqrt(2.0) / 2.0
        assertEquals(4, rotation.size, "a quaternion has four components: $rotation")
        assertClose(rotation[0], root, 1e-12, "x = sin(-45°): the −90° turn about X that takes Z-up to Y-up")
        assertClose(rotation[1], 0.0, 1e-12)
        assertClose(rotation[2], 0.0, 1e-12)
        assertClose(rotation[3], -root, 1e-12, "w = cos(-45°)")

        val scale = numbers(field(p.json, "scale"))
        assertEquals(listOf(0.001, 0.001, 0.001), scale, "mm -> m, the unit glTF is defined in")

        assertTrue(p.json.contains(""""asset":{"generator":"ConstructIt","version":"2.0"}"""), p.json)
        assertTrue(p.json.contains(""""scene":0"""), "the default scene is stated")
        // the body hangs under the root as its own named child, so a viewer's tree reads like the drawing's
        val solid = ed.doc.elements.single { it.kind == ElementKind.SOLID }
        assertTrue(p.json.contains(""""children":[1]"""), p.json)
        assertTrue(p.json.contains("""{"mesh":0,"name":"${ed.doc.nameOf(solid)}"}"""), "the node is named by the authority: ${p.json}")
    }

    /**
     * **The accessors describe the mesh that is actually in the buffer**: three per body (positions, normals,
     * indices), each 4-byte aligned, counts matching, and the position accessor carrying the `min`/`max` the
     * spec requires for that attribute. Read out of the binary chunk rather than believed.
     */
    @Test
    fun theAccessorsMatchTheMeshAndTheBufferHoldsIt() {
        val ed = pyramid()
        val scene = ExportScene.extract(ed.doc, "pyramid")
        val render = RenderMesh.of(scene.nodes.single().mesh)
        val p = parse(Glb.write(scene))

        // three accessors, in the writer's fixed order
        assertEquals(3, Regex("\"componentType\"").findAll(p.json).count(), "one accessor per buffer view")
        val accessors = field(p.json, "accessors")
        assertTrue(accessors.contains(""""componentType":5126,"count":${render.vertexCount},"type":"VEC3""""), accessors)
        // a pyramid is small, so its indices fit in an unsigned short — the componentType a reader handles
        // most cheaply, and the one this writer picks while the vertex count allows it
        assertTrue(render.vertexCount <= 0xFFFF)
        assertTrue(accessors.contains(""""componentType":5123,"count":${render.indices.size},"type":"SCALAR""""), accessors)

        // every buffer view starts 4-byte aligned and lies inside the declared buffer
        val views = Regex("""\{"buffer":0,"byteLength":(\d+),"byteOffset":(\d+)\}""").findAll(field(p.json, "bufferViews")).toList()
        assertEquals(3, views.size)
        for (v in views) {
            val length = v.groupValues[1].toInt()
            val offset = v.groupValues[2].toInt()
            assertEquals(0, offset % 4, "an accessor's offset into the buffer must be a multiple of its component size")
            assertTrue(offset + length <= p.bin.size, "view $offset+$length is inside the ${p.bin.size}-byte buffer")
        }
        assertEquals(p.bin.size, numbers(field(p.json, "buffers")).single().toInt(), "the buffer's declared length")

        // ...and the positions really are in there, in millimetres: the apex is 90 mm up
        val posOffset = views[0].groupValues[2].toInt()
        val fb = ByteBuffer.wrap(p.bin, posOffset, render.vertexCount * 12).order(ByteOrder.LITTLE_ENDIAN)
        var maxZ = -1e9
        var maxX = -1e9
        repeat(render.vertexCount) {
            maxX = maxOf(maxX, fb.getFloat().toDouble())
            fb.getFloat()
            maxZ = maxOf(maxZ, fb.getFloat().toDouble())
        }
        assertClose(maxZ, 90.0, 1e-3, "the apex, in the file's own numbers")
        assertClose(maxX, 100.0, 1e-3, "and the plan square's width")
        val max = numbers(field(p.json, "accessors").substringAfter("\"max\":"))
        assertClose(max[0], 100.0, 1e-3, "the POSITION accessor's max says the same")
        assertClose(max[2], 90.0, 1e-3)
    }

    /** The Tier-1 numbers land in the material as glTF defines them: **linear** base colour, and the two factors. */
    @Test
    fun theMaterialRoundTripsTheTierOneNumbers() {
        val ed = pyramid()
        val solid = ed.doc.elements.single { it.kind == ElementKind.SOLID }
        ed.setMaterial(solid, Appearance("#b87333", roughness = 0.35, metallic = 0.9))
        val p = parse(Glb.write(ExportScene.extract(ed.doc, "copper")))

        val material = field(p.json, "materials")
        val factor = numbers(field(material, "baseColorFactor"))
        val srgb = Appearance.parseHex("#b87333")!!
        assertEquals(4, factor.size, "rgb + alpha")
        for (i in 0..2) {
            assertClose(factor[i], Appearance.toLinear(srgb[i]), 1e-9, "baseColorFactor is *linear* RGB, per spec")
        }
        assertEquals(1.0, factor[3], "opaque")
        assertTrue(material.contains(""""metallicFactor":0.9"""), material)
        assertTrue(material.contains(""""roughnessFactor":0.35"""), material)
        assertTrue(material.contains(""""name":"${ed.doc.nameOf(solid)}""""), "the material is named after its body: $material")
    }

    /**
     * **The golden**: the acceptance pyramid's GLB, byte for byte.
     *
     * Possible only because the writer is deterministic by design — fixed JSON key order, one canonical number
     * format, no clock, no hash iteration — and worth having for exactly that reason: this is the assertion
     * that notices a change nobody meant.
     */
    @Test
    fun theAcceptancePyramidsGlbIsAGolden() {
        val once = Glb.write(ExportScene.extract(pyramid().doc, "pyramid"))
        val twice = Glb.write(ExportScene.extract(pyramid().doc, "pyramid"))
        assertTrue(once.contentEquals(twice), "two runs of the same model must produce the same bytes")
        Golden.checkBytes("export-pyramid.glb", once)
    }

    /** The export entry point: a name, a message that says what went out, and no notes to add. */
    @Test
    fun theExportSaysWhatItWrote() {
        val result = Exports.export(pyramid().doc, "widget", ExportFormat.GLB)
        assertTrue(result.ok)
        assertEquals("widget.glb", result.fileName)
        assertTrue(result.message.contains("1 solid"), result.message)
        assertTrue(result.message.contains("triangles"), result.message)
        assertTrue(!result.message.contains("("), "nothing was skipped, so there is nothing in brackets: ${result.message}")
        assertEquals("glTF", String(result.bytes!!, 0, 4, Charsets.US_ASCII))
    }

    /** Two bodies, two nodes, two meshes, two materials — and the accessors keep pace. */
    @Test
    fun twoBodiesBecomeTwoNamedNodes() {
        val ed = pyramid()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(150.0, 0.0))
        ed.click(Vec2(200.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("h", constructit.units.Quantity.mm(20.0))
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(175.0, 0.0))
        val scene = ExportScene.extract(ed.doc, "two")
        assertEquals(2, scene.nodes.size)
        val p = parse(Glb.write(scene))
        assertTrue(p.json.contains(""""children":[1,2]"""), p.json)
        assertEquals(6, Regex("\"componentType\"").findAll(p.json).count(), "three accessors per body")
        assertEquals(2, Regex("\"primitives\"").findAll(p.json).count())
        assertEquals(2, Regex("\"pbrMetallicRoughness\"").findAll(p.json).count())
    }
}
