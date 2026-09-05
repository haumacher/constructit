package constructit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The engine says nothing English of its own** (OP-29, slice 2).
 *
 * `ChromeBundleTest` is this test one layer up, and the argument is identical: the value of the refactor is
 * its *coverage*, and coverage is what a feature like this loses silently — the next refusal written in
 * `geom/`, the next status note added to `Document`, carries its words in Kotlin unless something says
 * otherwise. So this reads the five places slice 2 owns — `geom/`, `dsl/`, `core/`, `editor/Document.kt`
 * and `editor/Editor.kt` — lexes their string literals and fails on any that reads as an English sentence.
 *
 * What is deliberately **not** a violation, each for a stated reason:
 *
 * - a literal listed in [developerText]: the message of a `require`/`throw` that states a *programming*
 *   invariant, not a refusal. These never reach a user — a broken one is a bug in this repository, and the
 *   sentence is for whoever reads the stack trace. Kept as exact strings, so a real refusal cannot hide
 *   behind a rule;
 * - text with no two words in it: an id, a key, a file token, a unit symbol, a formula name.
 *
 * Everything else must be a `Msg`.
 */
class EngineBundleTest {
    private val engine =
        listOf(
            "src/commonMain/kotlin/constructit/geom",
            "src/commonMain/kotlin/constructit/dsl",
            "src/commonMain/kotlin/constructit/core",
            "src/commonMain/kotlin/constructit/editor/Document.kt",
            "src/commonMain/kotlin/constructit/editor/Editor.kt",
        )

    /**
     * Sentences that state a **programming** invariant. Each one is the message of a `require`, a `check` or
     * a `throw` that fires only when this repository is wrong about itself: a `when` that has grown a branch
     * nobody handled, an array whose length the caller promised. A user cannot provoke one, and the
     * evaluator's own catch (OP-3) turns anything that does escape into `Msg.text` — visible, and never
     * pretending to be a translated sentence.
     */
    private val developerText =
        setOf(
            // Construction: a ProfileElement kind the DSL's `when` does not know — a new kind, not a user input
            "profile element must be a segment, arc, Bézier, elliptic arc or function curve",
            // Construction: the section-family builder is called with both anchors, which no tool does
            "a section rides a stated point or the run's own crossing, never both",
            // Xform3: a 3x4 matrix given the wrong number of numbers
            "an Xform3 is twelve numbers (3x3 plus a translation), got \${m.size}",
            // Document: two `require`s over arguments the editor has already checked
            "not an editable parameter",
            "not a free point",
        )

    private fun sources(): List<File> =
        engine.flatMap { path ->
            val f = File(path)
            assertTrue(f.exists(), "expected to find $path")
            if (f.isDirectory) f.walkTopDown().filter { it.extension == "kt" }.toList() else listOf(f)
        }

    /**
     * Every string literal of a Kotlin source, comments and character literals skipped — the same
     * hand-written scanner `ChromeBundleTest` uses, plus the one thing slice 2 needed: a literal *inside*
     * a `${…}` interpolation is part of its enclosing literal, not a separate one.
     */
    private fun stringLiterals(source: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c == '/' && source.startsWith("//", i) -> {
                    val end = source.indexOf('\n', i)
                    i = if (end < 0) source.length else end
                }
                c == '/' && source.startsWith("/*", i) -> {
                    // Kotlin block comments nest, and this repository's KDoc contains `/*` in prose
                    var depth = 1
                    i += 2
                    while (i < source.length && depth > 0) {
                        when {
                            source.startsWith("/*", i) -> {
                                depth++
                                i += 2
                            }
                            source.startsWith("*/", i) -> {
                                depth--
                                i += 2
                            }
                            else -> i++
                        }
                    }
                }
                source.startsWith("\"\"\"", i) -> {
                    val end = source.indexOf("\"\"\"", i + 3)
                    out.add(source.substring(i + 3, if (end < 0) source.length else end))
                    i = if (end < 0) source.length else end + 3
                }
                c == '"' -> {
                    val end = endOfString(source, i)
                    out.add(source.substring(i + 1, end - 1))
                    i = end
                }
                c == '\'' -> {
                    var j = i + 1
                    while (j < source.length && source[j] != '\'') j += if (source[j] == '\\') 2 else 1
                    i = j + 1
                }
                else -> i++
            }
        }
        return out
    }

    /** The index just past the closing quote of the string opening at [at] — `${…}` skipped whole. */
    private fun endOfString(
        source: String,
        at: Int,
    ): Int {
        var j = at + 1
        while (j < source.length && source[j] != '"') {
            j =
                when {
                    source[j] == '\\' -> j + 2
                    source.startsWith("\${", j) -> endOfTemplate(source, j + 1)
                    else -> j + 1
                }
        }
        return j + 1
    }

    /** The index just past the `}` closing the `${` at [at] — nested strings and braces included. */
    private fun endOfTemplate(
        source: String,
        at: Int,
    ): Int {
        var depth = 0
        var j = at
        while (j < source.length) {
            when {
                source[j] == '{' -> {
                    depth++
                    j++
                }
                source[j] == '}' -> {
                    depth--
                    j++
                    if (depth == 0) return j
                }
                source[j] == '"' -> j = endOfString(source, j)
                else -> j++
            }
        }
        return j
    }

    /** `${…}` and `$name` are values, not words — what the sentence around them says is what matters. */
    private fun withoutInterpolations(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text[i] == '$' && i + 1 < text.length && text[i + 1] == '{') {
                i = endOfTemplate(text, i + 1)
            } else if (text[i] == '$') {
                var j = i + 1
                while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                i = j
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    /** Two words of two letters or more, side by side: the cheapest honest definition of a sentence. */
    private val prose = Regex("[A-Za-z][A-Za-z']+[  ]+[A-Za-z][A-Za-z']+")

    @Test
    fun noEnglishSentenceIsWrittenIntoTheEngine() {
        val offenders = ArrayList<String>()
        for (file in sources()) {
            for (literal in stringLiterals(file.readText())) {
                if (literal in developerText) continue
                if (prose.containsMatchIn(withoutInterpolations(literal))) {
                    offenders.add("${file.path}: \"$literal\"")
                }
            }
        }
        assertEquals(
            emptyList(),
            offenders,
            "every refusal and every status note belongs in l10n/app_en.arb as a Msg (OP-29); found",
        )
    }
}
