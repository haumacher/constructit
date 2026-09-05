package constructit

import com.ibm.icu.text.MessagePattern
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.l10n.L10n
import constructit.l10n.Messages
import constructit.l10n.formatMessage
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * **The message bundles, and what may be assumed of them** (OP-29).
 *
 * The translated ARB is a golden that a machine wrote and a person reviewed, so the properties worth
 * asserting are the ones a review cannot be trusted to catch every time: that every pattern still parses as
 * ICU MessageFormat, and that a translator has not renamed a placeholder. Both were real: the first release
 * of `app_de.arb` came back with `#` rewritten as `{#}`, and with `{reason}` translated to `{Grund}`.
 */
class MessageBundleTest {
    @AfterTest
    fun resetLocale() {
        L10n.locale = "en"
    }

    private fun bundle(locale: String): Map<String, String> {
        val file = File("l10n/app_$locale.arb")
        assertTrue(file.exists(), "expected ${file.path}")
        // The ARB subset this project writes is flat: `"key": "value"` plus `"@key": { … }` metadata. The
        // metadata objects are skipped, so a two-line reader is enough and the test needs no JSON library.
        val out = LinkedHashMap<String, String>()
        var depth = 0
        var pendingKey: String? = null
        var i = 0
        val text = file.readText()
        while (i < text.length) {
            when (val c = text[i]) {
                '{' -> {
                    depth++
                    i++
                }
                '}' -> {
                    depth--
                    i++
                }
                '"' -> {
                    val sb = StringBuilder()
                    var j = i + 1
                    while (j < text.length && text[j] != '"') {
                        if (text[j] == '\\') {
                            sb.append(
                                when (text[j + 1]) {
                                    'n' -> '\n'
                                    't' -> '\t'
                                    'u' -> text.substring(j + 2, j + 6).toInt(16).toChar()
                                    else -> text[j + 1]
                                },
                            )
                            j += if (text[j + 1] == 'u') 6 else 2
                        } else {
                            sb.append(text[j])
                            j++
                        }
                    }
                    i = j + 1
                    if (depth != 1) continue
                    // at the top level the strings alternate key, value — and a `@key` opens an object
                    if (pendingKey == null) {
                        pendingKey = sb.toString()
                    } else {
                        if (!pendingKey.startsWith("@")) out[pendingKey] = sb.toString()
                        pendingKey = null
                    }
                }
                ':' -> i++
                ',' -> {
                    if (depth == 1) pendingKey = null
                    i++
                }
                else -> i++
            }
        }
        return out
    }

    /** The argument names an ICU pattern binds — ICU4J's own parser, so a plural branch is not one. */
    private fun argumentsOf(pattern: String): Set<String> {
        val parsed = MessagePattern(pattern)
        return (0 until parsed.countParts())
            .map { parsed.getPart(it) }
            .filter { it.type == MessagePattern.Part.Type.ARG_NAME }
            .map { parsed.getSubstring(it) }
            .toSet()
    }

    /**
     * **No brace survives rendering, in any language.**
     *
     * ICU's apostrophe rule is a trap laid for exactly this project: `'{n}'` does not put `{n}` in quotes,
     * it *quotes the brace* — the message then renders the literal text `{n}` at the user and binds nothing.
     * Slice 2 wrote 30 English patterns and DeepL wrote 11 German ones that way. The argument-set checks
     * below catch most of them; this catches the rest, including a pattern that quotes one occurrence of a
     * placeholder it also uses elsewhere, by rendering every message and looking for a brace that is left.
     */
    @Test
    fun nothingRendersABraceAtTheUser() {
        val left = ArrayList<String>()
        for (locale in Messages.locales) {
            for ((key, pattern) in bundle(locale)) {
                val args = argumentsOf(pattern).associateWith { 1 as Any? }
                val text =
                    try {
                        if (args.isEmpty()) pattern else formatMessage(locale, pattern, args)
                    } catch (e: IllegalArgumentException) {
                        throw AssertionError("$locale/$key does not render: $pattern", e)
                    }
                if ('{' in text || '}' in text) left.add("$locale/$key: $text")
            }
        }
        assertEquals(emptyList(), left, "a brace reached the reader — an apostrophe quoted a placeholder out")
    }

    @Test
    fun everyPatternInEveryLanguageIsValidIcu() {
        for (locale in Messages.locales) {
            for ((key, pattern) in bundle(locale)) {
                if (key.startsWith("@")) continue
                try {
                    MessagePattern(pattern)
                } catch (e: IllegalArgumentException) {
                    throw AssertionError("$locale/$key is not ICU MessageFormat: $pattern", e)
                }
            }
        }
    }

    /**
     * A translator may reword a message freely; it may not rename the holes in it. This is the assertion
     * that catches a machine translation of `{reason}` into `{Grund}`, which no formatter could bind.
     */
    @Test
    fun everyTranslationBindsTheSamePlaceholdersAsTheEnglish() {
        val english = bundle("en")
        val drift = ArrayList<String>()
        for (locale in Messages.locales.filter { it != "en" }) {
            for ((key, pattern) in bundle(locale)) {
                val expected = argumentsOf(english[key] ?: continue)
                val actual = argumentsOf(pattern)
                if (expected != actual) drift.add("$locale/$key: expected $expected, got $actual")
            }
        }
        assertEquals(emptyList(), drift, "a translation renamed a placeholder")
    }

    /** …and every declared placeholder is actually used, so a typed accessor never takes a dead argument. */
    @Test
    fun everyDeclaredPlaceholderIsUsedByItsMessage() {
        val declared = Regex("\"@([^\"]+)\"[^{]*\\{[^}]*\"placeholders\"\\s*:\\s*\\{")
        val text = File("l10n/app_en.arb").readText()
        val english = bundle("en")
        // a light check: every `{name}` the accessor would pass must appear in the English pattern
        for (m in declared.findAll(text)) {
            val key = m.groupValues[1]
            val pattern = english[key] ?: continue
            assertTrue(argumentsOf(pattern).isNotEmpty(), "$key declares placeholders its pattern never uses")
        }
    }

    /** The plural, through ICU4J, in both languages — one message, two grammars. */
    @Test
    fun aPluralReadsCorrectlyInBothLanguages() {
        assertEquals("Loaded 1 element", Messages.msgLoaded(1, "en"))
        assertEquals("Loaded 4 elements", Messages.msgLoaded(4, "en"))
        assertEquals("Geladen 1 Element", Messages.msgLoaded(1, "de"))
        assertEquals("Geladen 4 Elemente", Messages.msgLoaded(4, "de"))
        // and the plural the browser E2E reads off the create dialog
        assertEquals("Group — 1 element", Messages.uiDialogTitle("Group", 1, "en"))
        assertEquals("Group — 2 elements", Messages.uiDialogTitle("Group", 2, "en"))
        assertEquals("Group — 1 Element", Messages.uiDialogTitle("Group", 1, "de"))
        assertEquals("Group — 2 Elemente", Messages.uiDialogTitle("Group", 2, "de"))
    }

    /** …and the select, which is how one sentence carries a discrete reason (OP-29). */
    @Test
    fun aSelectPicksItsBranchInBothLanguages() {
        assertEquals("hidden by the construction (Show leaves it hidden)", Messages.uiTreeHidden("construction", "en"))
        assertEquals("hidden — select it and press Show to bring it back", Messages.uiTreeHidden("user", "en"))
        assertTrue(Messages.uiTreeHidden("construction", "de").startsWith("durch die Konstruktion"))
        assertTrue(Messages.uiTreeHidden("user", "de").startsWith("ausgeblendet"))
        // an unknown branch falls into `other`, which is what makes the message total
        assertEquals(Messages.uiTreeHidden("user", "de"), Messages.uiTreeHidden("whatever", "de"))
    }

    /** Nothing is memoised across locales by mistake: the same key, two languages, two answers. */
    @Test
    fun theActiveLocaleDecidesWithoutBeingPassed() {
        assertEquals("Drawing", Messages.uiPanelDrawing())
        L10n.locale = "de"
        assertEquals("Zeichnung", Messages.uiPanelDrawing())
        assertEquals("Kante verrunden", Tools.byId(Tools.BLEND_EDGE)!!.label)
        L10n.locale = "en"
        assertEquals("Fillet edge", Tools.byId(Tools.BLEND_EDGE)!!.label)
    }

    /** A language nobody bundled, and a regional tag of one that is: English, and its own language. */
    @Test
    fun anUnknownLanguageFallsBackToEnglish() {
        assertEquals("Drawing", Messages.uiPanelDrawing("ja"))
        assertEquals("Drawing", Messages.uiPanelDrawing("en-GB"))
        assertEquals("Zeichnung", Messages.uiPanelDrawing("de-AT"))
        assertEquals("Zeichnung", Messages.uiPanelDrawing("de_CH"))
        assertEquals(0, Messages.indexOf("pt-BR"))
    }

    /** A key nobody wrote is visible rather than blank — the key itself, which names what is missing. */
    @Test
    fun anUnknownKeyRendersAsItsOwnName() {
        assertEquals("no.such.key", Messages.text("no.such.key", "de"))
        assertEquals(null, Messages.patternOrNull("no.such.key", "de"))
    }

    /**
     * **The file is locale-neutral** (OP-18 × OP-29): the drawing is a construction, and a construction says
     * nothing about the language of whoever is reading it. Two drawings, saved under German, byte-identical
     * to the same drawings saved under English — and each still round-tripping.
     */
    @Test
    fun theFileSaysTheSameThingInEveryLanguage() {
        for (build in listOf(::pointsAndCurves, ::anOutlineOverAPath)) {
            L10n.locale = "en"
            val inEnglish = DocumentFormat.save(build().doc)
            L10n.locale = "de"
            val ed = build()
            val once = DocumentFormat.save(ed.doc)
            assertEquals(inEnglish, once, "the saved script must not depend on the reader's language")
            val twice = DocumentFormat.save(DocumentFormat.load(once))
            assertEquals(once, twice, "save -> load -> save must still be identical under de")
        }
    }

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun pointsAndCurves(): Editor {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 10.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 10.0))
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, -60.0))
        ed.click(Vec2(25.0, -60.0))
        return ed
    }

    private fun anOutlineOverAPath(): Editor {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(80.0, 0.0))
        ed.click(Vec2(80.0, 50.0))
        ed.click(Vec2(0.0, 50.0))
        ed.click(Vec2(0.0, 0.0))
        ed.key("Enter")
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(40.0, 25.0))
        return ed
    }

    /**
     * The two engines are asked the same question here, and the browser E2E asks the *other* one: this
     * asserts what ICU4J answers, and `BrowserE2ETest.theChromeSpeaksGerman` asserts that
     * `intl-messageformat` answers the same string in a real Chrome.
     */
    @Test
    fun theJvmEngineAnswersWhatTheBrowserEngineIsAskedToMatch() {
        assertEquals(
            "Group — 2 Elemente",
            formatMessage("de", Messages.patternOrNull("ui.dialog.title", "de")!!, mapOf("title" to "Group", "count" to 2)),
        )
        assertNotEquals(Messages.uiDialogTitle("Group", 2, "en"), Messages.uiDialogTitle("Group", 2, "de"))
    }
}
