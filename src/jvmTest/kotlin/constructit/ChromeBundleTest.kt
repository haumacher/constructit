package constructit

import constructit.editor.SlotKind
import constructit.editor.ToolCategory
import constructit.editor.Tools
import constructit.l10n.Messages
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The chrome says nothing English of its own** (OP-29).
 *
 * The mechanism of slice 1 is worth exactly as much as its coverage, and coverage is the one property a
 * feature like this loses silently: the next tool added to the table, or the next button added to the panel,
 * carries its words in Kotlin unless something says otherwise. So this reads the three files the slice owns
 * — the tool table, the browser shell and its HTML — and fails on any string literal that reads as an
 * English sentence.
 *
 * What is deliberately **not** a violation, each for a stated reason:
 *
 * - a **scalar slot's name** (`radius`, `corner radius`): it becomes the *name of a parameter* in the
 *   drawing and is written to the file, so it is format rather than UI and stays locale-neutral (OP-18).
 *   Whitelisted by asking the tool table itself, so a new one needs no edit here;
 * - CSS class names, selectors and fragments of HTML attribute syntax, which are markup;
 * - the two `js("…")` snippets, which are JavaScript source;
 * - language **endonyms** in the picker, which are never translated (see `LOCALE_NAMES`).
 */
class ChromeBundleTest {
    private val chrome =
        listOf(
            "src/commonMain/kotlin/constructit/editor/Tools.kt",
            "src/jsMain/kotlin/constructit/Main.kt",
        )

    private val markup = "src/jsMain/resources/index.html"

    /**
     * Markup, JavaScript and CSS that reads as prose but is not — one entry per reason, and the reason is
     * in the comment above. Kept as *exact* strings so that a real sentence can never hide behind a rule.
     */
    private val notProse =
        setOf(
            // CSS: a selector and two class lists
            "#fl-rows input[data-fl=\"\$name\"]",
            "prow active",
            "tool active",
            // an HTML attribute fragment appended to a checkbox
            " checked disabled",
            // JavaScript source handed to the browser through `js(…)`
            "typeof window.showSaveFilePicker === 'function' && window.location.protocol !== 'file:'",
            "typeof window.showOpenFilePicker !== 'function'",
        )

    /** Words that stand in `index.html` as themselves: the product's name and two unit symbols. */
    private val notTranslated = setOf("ConstructIt", "mm", "2D", "3D", "GLB", "3MF", "STL", "JT", "+", "#", "°")

    private fun read(path: String): String {
        val file = File(path)
        assertTrue(file.exists(), "expected to find $path")
        return file.readText()
    }

    /**
     * Every string literal of a Kotlin source, comments and character literals skipped and escapes undone.
     * A hand-written scanner because that is all it takes: the alternative is a Kotlin parser on the test
     * classpath to find quotation marks.
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
                    val text = StringBuilder()
                    var j = i + 1
                    while (j < source.length && source[j] != '"') {
                        if (source[j] == '\\') {
                            text.append(source[j + 1])
                            j += 2
                        } else {
                            text.append(source[j])
                            j++
                        }
                    }
                    out.add(text.toString())
                    i = j + 1
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

    /** `${…}` and `$name` are values, not words — what the sentence around them says is what matters. */
    private fun withoutInterpolations(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text[i] == '$' && i + 1 < text.length && text[i + 1] == '{') {
                var depth = 0
                var j = i + 1
                while (j < text.length) {
                    if (text[j] == '{') depth++
                    if (text[j] == '}') {
                        depth--
                        if (depth == 0) break
                    }
                    j++
                }
                i = j + 1
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

    /** Whatever a browser would *not* show: tags, half-tags and entities. */
    private fun withoutMarkup(text: String): String =
        text
            .replace(Regex("<[^<>]*>"), "")
            .replace(Regex("<[A-Za-z/][^<>]*$"), "")
            .replace(Regex("^[^<>]*>"), "")
            .replace(Regex("&#?\\w+;"), "")

    /** Two words of two letters or more, side by side: the cheapest honest definition of a sentence. */
    private val prose = Regex("[A-Za-z]{2,}[ \u00a0]+[A-Za-z]{2,}")

    @Test
    fun noEnglishSentenceIsWrittenIntoTheChrome() {
        // the tool table's own answer to "which words are file names, not UI"
        val parameterNames = Tools.all.flatMap { tool -> tool.scalars.map { it.name } }.toSet()
        val offenders = ArrayList<String>()
        for (path in chrome) {
            for (literal in stringLiterals(read(path))) {
                if (literal in parameterNames || literal in notProse) continue
                if (prose.containsMatchIn(withoutMarkup(withoutInterpolations(literal)))) {
                    offenders.add("$path: \"$literal\"")
                }
            }
        }
        assertEquals(
            emptyList(),
            offenders,
            "every user-visible sentence of the chrome belongs in l10n/app_en.arb (OP-29); found",
        )
    }

    @Test
    fun noEnglishSentenceIsWrittenIntoTheHtml() {
        var html = read(markup)
        // the stylesheet, the comments and the one script tag are not the page's words
        html = html.replace(Regex("(?s)<style>.*?</style>"), "")
        html = html.replace(Regex("(?s)<!--.*?-->"), "")
        html = html.replace(Regex("(?s)<script[^>]*>.*?</script>"), "")
        val offenders = ArrayList<String>()

        // the two attributes a browser renders as prose
        for (m in Regex("(?<![-\\w])(title|placeholder)=\"([^\"]*)\"").findAll(html)) {
            offenders.add("index.html @${m.groupValues[1]}: \"${m.groupValues[2]}\"")
        }
        // ...and every text node between tags
        for (m in Regex(">([^<>]+)<").findAll(html)) {
            val text = m.groupValues[1].replace(Regex("&#?\\w+;"), "").trim()
            if (text.isEmpty() || text in notTranslated) continue
            offenders.add("index.html text: \"$text\"")
        }
        assertEquals(
            emptyList(),
            offenders,
            "index.html states keys (data-i18n / data-i18n-title / data-i18n-placeholder), never words (OP-29); found",
        )
    }

    /**
     * …and the other half of coverage: every key the chrome addresses by hand must exist. A `data-i18n`
     * naming a key nobody wrote would render the key itself, which is visible but silly.
     */
    @Test
    fun everyKeyTheHtmlNamesIsInTheBundle() {
        val html = read(markup)
        val missing =
            Regex("data-i18n(?:-title|-placeholder)?=\"([^\"]+)\"")
                .findAll(html)
                .map { it.groupValues[1] }
                .filter { Messages.patternOrNull(it, "en") == null }
                .toList()
        assertEquals(emptyList(), missing, "index.html names keys the bundle does not carry")
    }

    /** Every tool, every category and every slot kind can say what it is. */
    @Test
    fun everyToolAndCategoryHasItsWords() {
        val nameless = Tools.all.filter { Messages.patternOrNull("tool.${it.id}.title", "en") == null }
        assertEquals(emptyList(), nameless.map { it.id }, "a tool with no title in the bundle")
        val helpless = Tools.all.filter { it.help.isBlank() }
        assertEquals(emptyList(), helpless.map { it.id }, "a tool with no help line")
        for (category in ToolCategory.values()) {
            val key = "category.${category.name.lowercase()}"
            assertTrue(Messages.patternOrNull(key, "en") != null, "no word for $key")
        }
        for (kind in SlotKind.values()) {
            assertTrue(Tools.roleOfKind(kind).isNotBlank(), "no word for slot kind $kind")
        }
        assertTrue(Tools.roleOfKind(null).isNotBlank(), "no word for an unknown slot kind")
    }
}
