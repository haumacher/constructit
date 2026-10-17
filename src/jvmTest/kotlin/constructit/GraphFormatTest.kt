package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.SolidValue
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.mm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The document is a graph of features, not a journal** (OP-32): the file is JSON, every element and feature
 * has an identity of its own that is never given out twice, every reference names one, and the order a load
 * builds in is the references' — the file's own order means nothing.
 */
class GraphFormatTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    /** GitHub #36's first script, verbatim: a journal written by format 6, two fillets on an extruded L. */
    private val issue36 =
        """
constructit 6
orthostart -26.875,-32.375 -> e1
orthovertex -26.875,15.375 -> e2,e3
orthovertex 61.875,15.375 -> e4,e5
orthovertex 61.875,0.375 -> e6,e7
orthovertex -5.521648428788623,0.375 -> e8,e9
orthovertex -5.521648428788623,-32.375 -> e10,e11
orthoclose -> e12
param "h" = 20mm
tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
hide els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
show els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
param "r" = 4mm
tool filletedge els=e13 clicks=-31.252365457721638,11.31587462139538 scalar="r" signs=13;-1;1;0;1 -> e14,e15
tool filletedge els=e14 clicks=-15.659687663642714,14.554138077719443 scalar="r" signs=2;-1;1;0;-1 -> e16
""".trimStart()

    private fun solidOf(doc: constructit.editor.Document): SolidValue =
        doc.elements.last { it.kind == ElementKind.SOLID }.let { (Evaluator().eval(it.ref.node) as EvalResult.Ok).value as SolidValue }

    // ---- the user's file ----

    @Test
    fun theIssue36ScriptImportsSavesAsJsonAndReloadsIdentically() {
        val journal = DocumentFormat.load(issue36)
        val json = DocumentFormat.save(journal)
        assertTrue(DocumentFormat.isGraph(json), "the save is a graph:\n$json")
        val back = DocumentFormat.load(json)
        assertEquals(json, DocumentFormat.save(back), "save -> load -> save is byte-equal")
        assertSameDrawing(journal, back, what = "#36")
        val solid = solidOf(back)
        assertManifold(solid.solid.mesh, "the twice-filleted L")
        assertEquals(Geom3.volume(solidOf(journal).solid.mesh), Geom3.volume(solid.solid.mesh), "the same body, to the bit")
        // every element keeps the name the journal gave it — the import names nothing anew
        for (n in 1..16) assertNotNull(back.elements.firstOrNull { back.nameOf(it) == "e$n" }, "e$n")
        // the file says what exists: one path with its five vertices and its close, both parameters by name
        val s = saved(json)
        val path = s.kind("path").single()
        assertEquals(listOf("e1"), path.ids("creates"))
        assertEquals(5, path.arr("vertices").size)
        assertEquals(listOf("e12"), (path["close"] as JsonObject).ids("creates"))
        assertEquals(listOf("20 mm", "4 mm"), s.parameters.map { it.str("value") })
        assertEquals(listOf("h", "r"), s.parameters.map { it.str("name") })
        assertEquals(2, s.tools(Tools.BLEND_EDGE).size)
        assertEquals(13, s.tools(Tools.BLEND_EDGE)[0].int("edge"), "the edge the pick named, under its own name (OP-32 slice 2)")
        assertEquals(listOf(-1, 1, 0, 1), s.tools(Tools.BLEND_EDGE)[0].ints("signs"), "and the sector it chose")
        assertEquals(listOf(s.param("r")!!.str("id")), s.tools(Tools.BLEND_EDGE)[0].ids("params"), "a parameter is referenced by identity")
        // hide-all then show-all leaves nothing: the view states what is, and nothing is hidden (OP-32)
        assertTrue(s.hidden.isEmpty(), "a hide undone by a show leaves no trace")
        assertTrue(!s.root.containsKey("view"))
    }

    /** The layout: fixed keys in a fixed order, one line per feature, numbers in the one canonical spelling. */
    @Test
    fun theFileIsJsonWithAFixedLayoutAndOneLinePerFeature() {
        val json = DocumentFormat.save(DocumentFormat.load(issue36))
        val root = Json.parseToJsonElement(json).jsonObject
        assertEquals(listOf("format", "version", "nextId", "parameters", "features"), root.keys.toList())
        assertEquals("constructit", (root["format"] as JsonPrimitive).content)
        assertEquals(DocumentFormat.VERSION, (root["version"] as JsonPrimitive).content.toInt())
        assertEquals("""{"e":17,"f":13}""", root["nextId"].toString(), "both high-water marks are stored")
        val lines = json.lines()
        assertEquals("{", lines.first())
        assertEquals(
            """    {"id":"f1","kind":"path","at":[-26.875,-32.375],"creates":["e1"],"vertices":[{"id":"f2","at":[-26.875,15.375],"creates":["e2","e3"]},""" +
                """{"id":"f3","at":[61.875,15.375],"creates":["e4","e5"]},{"id":"f4","at":[61.875,0.375],"creates":["e6","e7"]},""" +
                """{"id":"f5","at":[-5.521648428788623,0.375],"creates":["e8","e9"]},{"id":"f6","at":[-5.521648428788623,-32.375],"creates":["e10","e11"]}],""" +
                """"close":{"id":"f7","creates":["e12"]}},""",
            lines.first { it.contains("\"kind\":\"path\"") },
            "the path is one feature with its vertex list:\n$json",
        )
        assertTrue(lines.any { it == """    {"id":"f8","name":"h","value":"20 mm"},""" }, json)
        assertTrue(json.lines().none { it.contains("E-") || it.contains("e-") }, "never scientific")
    }

    // ---- order carries no meaning ----

    private fun shuffled(
        json: String,
        seed: Int,
    ): String {
        val root = Json.parseToJsonElement(json).jsonObject
        val rnd = Random(seed)
        val out = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        // the keys in another order too: a reader that relied on any order at all would show it here
        for (k in root.keys.shuffled(rnd)) {
            val v = root.getValue(k)
            out[k] = if (v is JsonArray) JsonArray(v.shuffled(rnd)) else v
        }
        return JsonObject(out).toString()
    }

    @Test
    fun shufflingTheFeaturesOfAFileIsTheSameDrawingAndTheSameBytes() {
        val journal = DocumentFormat.load(issue36)
        val json = DocumentFormat.save(journal)
        for (seed in 1..6) {
            val mixed = shuffled(json, seed)
            assertNotEquals(json, mixed)
            val back = DocumentFormat.load(mixed)
            assertEquals(json, DocumentFormat.save(back), "seed $seed: the shuffled file saves to the very same bytes")
            assertSameDrawing(journal, back, what = "seed $seed")
        }
    }

    /** A richer drawing — a pattern, a placed group, a datum, an expression on a named point — shuffled as well. */
    @Test
    fun aShuffledFileWithEveryKindOfReferenceIsStillTheSameDrawing() {
        val ed = Editor()
        val doc = ed.doc
        val w = doc.newParameter("w", 30.0.mm)
        val a = doc.freePoint(0.0.mm, 0.0.mm)
        val b = doc.freePoint(40.0.mm, 0.0.mm)
        val c = doc.freePoint(40.0.mm, 30.0.mm)
        doc.nameElement(doc.elementFor(b)!!, "P")
        val h = doc.newParameter("h", 10.0.mm)
        assertTrue(doc.bindParameter(h, "P.x / 4 + w / 10"), doc.note ?: "")
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(40.0, 30.0))
        doc.createGroup("G", listOf(doc.elementFor(a)!!, doc.elementFor(c)!!))
        doc.setElementsVisible(listOf(doc.elementFor(c)!!), false)
        val json = DocumentFormat.save(doc)
        val reference = DocumentFormat.load(json)
        assertEquals(json, DocumentFormat.save(reference))
        for (seed in 1..6) {
            val back = DocumentFormat.load(shuffled(json, seed))
            assertEquals(json, DocumentFormat.save(back), "seed $seed")
            assertSameDrawing(reference, back, what = "seed $seed")
        }
        val s = saved(json)
        assertEquals(mapOf("e2" to "P"), s.names, "a user name is an attribute of the element, and the one an expression reads")
        assertEquals("P.x / 4 + w / 10", (s.param("h")!!["formula"] as JsonObject).str("text"))
        assertEquals(listOf("e1", "e3"), s.kind("group").single().ids("members"))
        assertEquals(listOf("e3"), s.hidden)
        assertTrue(w.serial > 0)
    }

    // ---- what a load refuses, by name ----

    private fun edit(
        json: String,
        f: (JsonObject) -> JsonObject,
    ): String = f(Json.parseToJsonElement(json).jsonObject).toString()

    private fun withFeatures(
        root: JsonObject,
        map: (JsonObject) -> JsonObject,
    ): JsonObject = JsonObject(root + ("features" to JsonArray((root["features"] as JsonArray).map { map(it.jsonObject) })))

    private fun loadError(text: String): String {
        val e = runCatching { DocumentFormat.load(text) }.exceptionOrNull()
        assertTrue(e is DocumentFormat.LoadError, "a load error, not $e")
        return e.message!!
    }

    @Test
    fun aReferenceToAnIdentityNothingCreatesIsALoadErrorThatNamesTheFeature() {
        val json = DocumentFormat.save(DocumentFormat.load(issue36))
        val broken =
            edit(json) { root ->
                withFeatures(root) { f ->
                    if (f.str("tool") == Tools.EXTRUDE) JsonObject(f + ("els" to JsonArray(listOf(JsonPrimitive("e99"))))) else f
                }
            }
        val why = loadError(broken)
        assertTrue(why.contains("f9 (tool extrude)") && why.contains("e99"), "names the feature and what it refers to: $why")
    }

    @Test
    fun aCycleOfReferencesIsALoadErrorThatNamesAFeatureOfIt() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        val json = DocumentFormat.save(ed.doc)
        // the first point now claims to be built on the segment that is built on it
        val cyclic =
            edit(json) { root ->
                withFeatures(root) { f ->
                    if (f.str("kind") == "point" && f.ids("creates") == listOf("e1")) {
                        JsonObject(mapOf("id" to f["id"]!!, "kind" to JsonPrimitive("tool"), "tool" to JsonPrimitive(Tools.MIDPOINT), "pts" to JsonArray(listOf(JsonPrimitive("e3"))), "creates" to f["creates"]!!))
                    } else {
                        f
                    }
                }
            }
        val why = loadError(cyclic)
        assertTrue(why.contains("built from its own result") && why.contains("f1") && why.contains("f3"), "names the circle: $why")
    }

    @Test
    fun aFileFromANewerVersionSaysSoRatherThanBeingMisread() {
        val json = DocumentFormat.save(DocumentFormat.load(issue36))
        val newer = edit(json) { JsonObject(it + ("version" to JsonPrimitive(DocumentFormat.VERSION + 1))) }
        val why = loadError(newer)
        assertTrue(why.contains("newer version") && why.contains("${DocumentFormat.VERSION + 1}"), why)
        assertTrue(loadError("""{"format":"sketchup","version":1}""").contains("not a ConstructIt drawing"))
        assertTrue(loadError("{ not json").contains("not well-formed JSON"))
        // …and a journal claiming a graph version is no file any build wrote
        assertTrue(loadError("constructit ${DocumentFormat.VERSION}\npoint 0,0 -> e1\n").contains("JSON"))
    }

    /** A field of the wrong shape is a load error naming the feature, never a crash or a silently different drawing. */
    @Test
    fun aMalformedFieldIsALoadErrorThatNamesTheFeature() {
        val why = loadError("""{"format":"constructit","version":11,"features":[{"id":"f1","kind":"point","at":"here","creates":["e1"]}]}""")
        assertTrue(why.contains("f1 (point)"), "names the feature: $why")
        val unknown = loadError("""{"format":"constructit","version":11,"features":[{"id":"f1","kind":"teleport","creates":["e1"]}]}""")
        assertTrue(unknown.contains("f1 (teleport)") && unknown.contains("teleport"), "and an unknown kind by its kind: $unknown")
        val twice = loadError("""{"format":"constructit","version":11,"features":[{"id":"f1","kind":"point","at":[0,0],"creates":["e1"]},{"id":"f2","kind":"point","at":[1,0],"creates":["e1"]}]}""")
        assertTrue(twice.contains("e1") && twice.contains("f1 (point)") && twice.contains("f2 (point)"), "an identity created twice names both: $twice")
    }

    // ---- identities ----

    @Test
    fun anIdentityIsNeverGivenOutTwiceNotEvenAfterADelete() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        assertEquals(listOf("e1", "e2", "e3"), ed.doc.elements.map { ed.doc.nameOf(it) })
        // delete the middle one: the others keep their names — the journal's positional numbering renamed e3
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(30.0, 0.0))
        assertTrue(ed.deleteSelection(), ed.statusHint)
        assertEquals(listOf("e1", "e3"), ed.doc.elements.map { ed.doc.nameOf(it) }, "a delete renames nothing")
        // and the next point is a new number, not the freed one — also after a save and a load
        val back = Editor(DocumentFormat.load(DocumentFormat.save(ed.doc)))
        back.setTool(Tools.POINT)
        back.click(Vec2(90.0, 0.0))
        assertEquals(listOf("e1", "e3", "e4"), back.doc.elements.map { back.doc.nameOf(it) }, "e2 stays retired")
        assertEquals("""{"e":5,"f":5}""", saved(back.doc).root["nextId"].toString())
    }

    @Test
    fun undoSnapshotsAreGraphsAndKeepTheIdentities() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        assertTrue(ed.undo())
        assertEquals(listOf("e1"), ed.doc.elements.map { ed.doc.nameOf(it) })
        assertTrue(ed.redo())
        assertEquals(listOf("e1", "e2"), ed.doc.elements.map { ed.doc.nameOf(it) })
        assertTrue(DocumentFormat.isGraph(DocumentFormat.save(ed.doc)))
    }
}
