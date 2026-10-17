package constructit

import constructit.editor.DocumentFormat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Every version-12 file stays readable for ever, as the drawing it was** (OP-18's *Versioning & migration*,
 * OP-32 slice 3) — the permanent cross-build load test of the format that stated picks by index.
 *
 * The fixtures in `src/jvmTest/resources/v12/` were **written by the build that wrote version 12** (session 89,
 * before slice 3 changed anything) — the version-11 corpus and the four journal fixtures re-saved, and #36's second
 * script — each beside its **drawing** as that build loaded it (`<name>.drawing`). Every edge, face, run and slot
 * record in them is an index into its body's list. Loaded now, each index is resolved once, against the geometry
 * in dependency order, by the very builds that resolved it then — the old load itself — and the drawing is the
 * same. Saved, every one of them is stated by what made it (a key), and the file is a fixed point.
 */
class V12CorpusTest {
    private val dir = File("src/jvmTest/resources/v12")

    private fun fixtures(): List<File> = dir.listFiles { f -> f.name.endsWith(".cit") }.orEmpty().sortedBy { it.name }

    /** Every pick address a saved feature states: a tool's `edge`/`face`, a face space's `piece`, every run's and slot's edges. */
    private fun addresses(f: JsonObject): List<JsonPrimitive> {
        val out = ArrayList<JsonPrimitive>()
        // a section input's `edge`/`corner` is a curve of the section, by position — the stated limit, not a pick
        if (f.str("kind") == "tool" || f.str("kind") == "orbit") for (k in listOf("edge", "face")) (f[k] as? JsonPrimitive)?.let { out.add(it) }
        if (f.str("kind") == "sketchspace") (f["piece"] as? JsonPrimitive)?.let { out.add(it) }
        (f["runs"] as? JsonArray)?.forEach { r -> (r as? JsonArray)?.forEach { out.add(it as JsonPrimitive) } }
        (f["slots"] as? JsonArray)?.forEach { s -> ((s as JsonObject)["edges"] as JsonArray).forEach { out.add(it as JsonPrimitive) } }
        return out
    }

    @Test
    fun everyVersion12FixtureLoadsAsTheDrawingItsOwnBuildLoaded() {
        val files = fixtures()
        assertTrue(files.size >= 35, "the v12 corpus is in place (${files.size} files)")
        val failures = ArrayList<String>()
        var keyed = 0
        for (f in files) {
            try {
                val text = f.readText()
                assertEquals(12, saved(text).version, "${f.name} is a version-12 file")
                val doc = DocumentFormat.load(text)
                val golden = File(dir, f.name.removeSuffix(".cit") + ".drawing").readText()
                V11CorpusTest.assertSameFingerprint(golden, V11CorpusTest.fingerprint(doc), f.name)
                // …saved, it is the current version and names every pick by what made it
                val json = DocumentFormat.save(doc)
                val s = saved(json)
                assertEquals(DocumentFormat.VERSION, s.version, "${f.name}: re-saved at the current version")
                for (feature in s.features) {
                    for (a in addresses(feature)) {
                        assertTrue(a.isString, "${f.name}: ${feature.str("id")} states its pick by key, not by index ($a):\n$json")
                        keyed++
                    }
                }
                // …which loads as the same drawing again, and is a fixed point from then on
                val back = DocumentFormat.load(json)
                V11CorpusTest.assertSameFingerprint(golden, V11CorpusTest.fingerprint(back), "${f.name} (re-saved)")
                assertSameFile(DocumentFormat.save(back), json, "${f.name}: save -> load -> save")
            } catch (e: Throwable) {
                failures.add("${f.name}: ${e.message?.take(600)}")
            }
        }
        assertTrue(failures.isEmpty(), "${failures.size} of ${files.size} v12 files do not load as they did:\n" + failures.joinToString("\n\n"))
        assertTrue(keyed >= 40, "the corpus states $keyed picks, every one by key")
    }
}
