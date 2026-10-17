package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.SolidValue
import constructit.editor.DocumentFormat
import constructit.editor.ElementKind
import org.junit.jupiter.api.ClassOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every **journal** — a file an older build wrote, `constructit 1` … `constructit 10` — that any test of this
 * suite imports, collected as it is imported (OP-32). Registered for every test class by JUnit's extension
 * autodetection (`junit-platform.properties`), so no test has to know it exists: the corpus is whatever the
 * suite actually loads, user fixtures, GitHub-issue scripts and generated scripts alike.
 */
class JournalCorpus : BeforeAllCallback {
    override fun beforeAll(context: ExtensionContext) {
        if (DocumentFormat.importTap == null) DocumentFormat.importTap = { text, _ -> synchronized(texts) { texts.add(text) } }
    }

    companion object {
        /** Distinct texts, in the order they were first imported. */
        val texts = LinkedHashSet<String>()
    }
}

/**
 * **The whole corpus survives the move to the graph** (OP-32) — the acceptance test of the format change.
 *
 * For every journal the suite imported ([JournalCorpus]) and every `.cit` fixture in the test resources: the
 * import saves as a graph, the graph loads, and saves again **byte for byte**; and the graph load is **the same
 * drawing** as the journal load — the same elements under the same names, the same values, every solid of the
 * same volume and manifold. Ordered last ([ClassOrderer.OrderAnnotation] in `junit-platform.properties`), so the
 * corpus is complete when it runs; run on its own it still covers the resource fixtures.
 */
@Order(Int.MAX_VALUE)
class JournalCorpusTest {
    @Test
    fun everyImportedJournalRoundTripsThroughTheGraphAsTheSameDrawing() {
        val resources = File("src/jvmTest/resources").listFiles { f -> f.name.endsWith(".cit") }.orEmpty().sortedBy { it.name }.map { it.readText() }
        val corpus = LinkedHashSet<String>(resources)
        synchronized(JournalCorpus.texts) { corpus.addAll(JournalCorpus.texts) }
        var solids = 0
        var decided = 0
        val failures = ArrayList<String>()
        for ((i, text) in corpus.withIndex()) {
            val head = text.trimStart().lineSequence().first()
            try {
                val journal = DocumentFormat.load(text)
                val graph = DocumentFormat.save(journal)
                assertTrue(DocumentFormat.isGraph(graph), "an import saves as a graph")
                val back = DocumentFormat.load(graph)
                assertEquals(graph, DocumentFormat.save(back), "script $i ($head): save -> load -> save is byte-equal")
                assertSameDrawing(journal, back, what = "script $i ($head)")
                if (saved(graph).features.any { it.containsKey("tips") || it.containsKey("runs") }) decided++
                for (el in back.elements.filter { it.kind == ElementKind.SOLID }) {
                    val v = (Evaluator().eval(el.ref.node) as? EvalResult.Ok)?.value as? SolidValue ?: continue
                    if (v.solid.mesh.triangles.isEmpty()) continue
                    assertManifold(v.solid.mesh, "script $i: ${back.nameOf(el)}")
                    solids++
                }
            } catch (e: Throwable) {
                failures.add("script $i ($head, ${text.lines().size} lines): ${e.message?.take(400)}\n${text.take(600)}")
            }
        }
        println(
            "journal corpus: ${corpus.size} scripts (${resources.size} resource fixtures), $solids solids checked manifold, " +
                "$decided recording a decision taken from what was visible",
        )
        assertTrue(failures.isEmpty(), "${failures.size} of ${corpus.size} scripts do not survive the graph:\n" + failures.joinToString("\n\n"))
        assertTrue(corpus.size >= resources.size, "the corpus holds at least the resource fixtures")
    }
}
