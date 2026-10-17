package constructit

import constructit.editor.DocumentFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Order without accident** (OP-32, slice 3d): where creation order means something and no reference says so, the
 * file says so by an explicit edge (`after`), so the order a load builds in is decided by the references alone and
 * the identities' own order — the loader's last tie-break — decides nothing.
 *
 * The proof is the brief's: every identity of a file renamed by a random bijection (`f<n>` among the features,
 * `e<n>` among the elements, wherever the file states one — a reference, a key naming an edge by the curves that
 * made it, a name, the view) loads to **the same drawing**, element for element under the renaming. Before this
 * slice a dressing's roundings joined in identity order and a weld made while a vertex was its path's end was
 * built wherever its identity fell, so a renaming could reorder both.
 */
class OrderWithoutAccidentTest {
    private val ID = Regex("\\b([ef])(\\d+)\\b")

    /** [text] with every identity renamed by a random bijection (one for `e`, one for `f`); the map is returned too. */
    private fun permuted(
        text: String,
        seed: Int,
    ): Pair<String, Map<String, String>> {
        val root = Json.parseToJsonElement(text) as JsonObject
        val used = HashSet<String>()

        fun collect(v: JsonElement) {
            when (v) {
                is JsonObject ->
                    v.forEach { (k, x) ->
                        ID.findAll(k).forEach { used.add(it.value) }
                        collect(x)
                    }
                is JsonArray -> v.forEach { collect(it) }
                is JsonPrimitive -> if (v.isString) ID.findAll(v.content).forEach { used.add(it.value) }
            }
        }
        collect(root["features"]!!)
        root["parameters"]?.let { collect(it) }
        val rnd = Random(seed)
        val map = HashMap<String, String>()
        for (prefix in listOf("e", "f")) {
            val ids = used.filter { it.startsWith(prefix) }.sortedBy { it.drop(1).toInt() }
            ids.zip(ids.shuffled(rnd)).forEach { (a, b) -> map[a] = b }
        }

        fun rename(s: String): String = ID.replace(s) { m -> map[m.value] ?: m.value }

        fun walk(v: JsonElement): JsonElement =
            when (v) {
                is JsonObject -> JsonObject(LinkedHashMap(v.entries.associate { (k, x) -> rename(k) to walk(x) }))
                is JsonArray -> JsonArray(v.map { walk(it) })
                is JsonPrimitive -> if (v.isString) JsonPrimitive(rename(v.content)) else v
                else -> v
            }
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, v) in root) out[k] = if (k == "format" || k == "version" || k == "nextId") v else walk(v)
        return Json.encodeToString(JsonElement.serializer(), JsonObject(out)) to map
    }

    /** A drawing's fingerprint with every element name carried back through [inverse]. */
    private fun renamedBack(
        print: String,
        inverse: Map<String, String>,
    ): String =
        print.lines().filter { it.isNotEmpty() }.map { line ->
            val name = line.substringBefore(' ')
            (inverse[name] ?: name) + line.drop(name.length)
        }.sortedBy { it.substringBefore(' ') }.joinToString("\n", postfix = "\n")

    private fun corpus(): List<Pair<String, String>> =
        File("src/jvmTest/resources/v12").listFiles { f -> f.name.endsWith(".cit") }.orEmpty().sortedBy { it.name }
            .map { it.name to DocumentFormat.save(DocumentFormat.load(it.readText())) }

    @Test
    fun aFileWhoseIdentitiesArePermutedLoadsToTheSameDrawing() {
        val failures = ArrayList<String>()
        var checked = 0
        for ((name, text) in corpus()) {
            val reference = V11CorpusTest.fingerprint(DocumentFormat.load(text))
            val sorted = reference.lines().filter { it.isNotEmpty() }.sortedBy { it.substringBefore(' ') }.joinToString("\n", postfix = "\n")
            for (seed in 1..3) {
                try {
                    val (p, map) = permuted(text, seed * 7919 + name.hashCode())
                    val inverse = map.entries.associate { (a, b) -> b to a }
                    val back = renamedBack(V11CorpusTest.fingerprint(DocumentFormat.load(p)), inverse)
                    V11CorpusTest.assertSameFingerprint(sorted, back, "$name, renaming $seed")
                    checked++
                } catch (e: Throwable) {
                    failures.add("$name, renaming $seed: ${e.message?.take(500)}")
                }
            }
        }
        assertTrue(failures.isEmpty(), "${failures.size} renamed files do not load to the same drawing:\n" + failures.joinToString("\n\n"))
        assertTrue(checked >= 100, "the corpus is renamed $checked times")
    }
}
