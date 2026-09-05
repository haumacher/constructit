package constructit

import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.l10n.L10n
import constructit.l10n.Messages
import constructit.l10n.formatMessage
import constructit.units.mm
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of OP-29 slice 1** on what the delivery never saw: the same gestures under `de` and
 * `en` write **the same file**, the German chrome has no untranslated or unrendered entry, a regional tag and
 * an unknown one resolve the way the design says, and a plural renders through the reference engine in both.
 */
class I18nProbeTest {
    @AfterTest
    fun english() {
        L10n.locale = "en"
    }

    private val fixture = """constructit 4
orthostart -26.875,-32.375 -> e1
orthovertex -26.875,15.375 -> e2,e3
orthovertex 61.875,15.375 -> e4,e5
orthovertex 61.875,0.375 -> e6,e7
orthovertex -5.521648428788623,0.375 -> e8,e9
orthovertex -5.521648428788623,-32.375 -> e10,e11
orthoclose -> e12
param "h" = 20mm
tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
param "r" = 5mm
"""

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    /** Load the fixture, round the top face's edges (a real gesture) and save — under [locale]. */
    private fun sessionUnder(locale: String): String {
        L10n.locale = locale
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(fixture))
        ed.activeScalar = ed.doc.newParameter("d", 3.0.mm)
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(70.0, 20.0))
        ed.click(Vec2(90.0, 40.0))
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(80.0, 20.0))
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "two bodies under $locale: ${ed.statusHint}")
        return DocumentFormat.save(ed.doc)
    }

    @Test
    fun theFileIsTheSameWhateverLanguageTheChromeSpeaks() {
        val en = sessionUnder("en")
        val de = sessionUnder("de")
        assertEquals(en, de, "the file is format, not UI: byte-equal under two locales")
        assertEquals(en, DocumentFormat.save(DocumentFormat.load(de)), "and a fixed point")
        for (word in listOf("Verrundung", "Fase", "Skizze", "Körper", "Umriss")) {
            assertTrue(word !in de, "no German reaches the file: $word")
        }
    }

    @Test
    fun everyToolSpeaksGermanAndNothingIsLeftUnrendered() {
        val en = Tools.all.associate { it.id to Messages.text("tool.${it.id}.title", "en") }
        val de = Tools.all.associate { it.id to Messages.text("tool.${it.id}.title", "de") }
        var same = 0
        for ((id, label) in de) {
            assertTrue(label.isNotBlank(), "tool $id has a German title")
            assertTrue('{' !in label && '}' !in label, "tool $id renders every placeholder: $label")
            assertTrue(!label.startsWith("tool."), "tool $id is not its own key: $label")
            if (label == en[id]) same++
        }
        // a handful of titles are legitimately the same word in both languages (proper names), not most
        assertTrue(same < de.size / 10, "$same of ${de.size} tool titles are unchanged in German: ${de.filter { it.value == en[it.key] }.keys}")
        for (t in Tools.all) {
            val help = Messages.patternOrNull("tool.${t.id}.help", "de").orEmpty()
            assertTrue(help.isBlank() || ('{' !in help), "help of ${t.id} renders in German: $help")
        }
    }

    @Test
    fun aRegionalTagFindsItsLanguageAndAnUnknownOneFallsBackToEnglish() {
        assertEquals(Messages.indexOf("de"), Messages.indexOf("de-AT"), "Austrian German is German")
        assertEquals(Messages.indexOf("en"), Messages.indexOf("xx-YY"), "an unknown tag is English")
        assertEquals(Messages.indexOf("en"), Messages.indexOf(""), "and so is nothing")
        val first = Tools.all.first().id
        assertEquals(Messages.text("tool.$first.title", "en"), Messages.text("tool.$first.title", "xx"), "an unknown locale renders English")
        L10n.locale = "de"
        assertEquals(Messages.text("tool.$first.title", "de"), Tools.all.first().label, "the label property follows the active locale")
    }

    @Test
    fun aPluralRendersThroughTheReferenceEngineInBothLanguages() {
        val one = Messages.msgLoaded(1, locale = "de")
        val many = Messages.msgLoaded(7, locale = "de")
        assertTrue(one != many && "1" in one && "7" in many, "German plural forms differ: $one / $many")
        assertEquals("Loaded 1 element", Messages.msgLoaded(1, locale = "en"))
        assertEquals("Loaded 7 elements", Messages.msgLoaded(7, locale = "en"))
        // the engine is ICU: the `#` form and a select both render
        assertEquals("3 Kanten", formatMessage("de", "{n, plural, one{# Kante} other{# Kanten}}", mapOf("n" to 3)))
        assertEquals("a chamfer", formatMessage("en", "a {kind, select, fillet{fillet} chamfer{chamfer} other{blend}}", mapOf("kind" to "chamfer")))
    }
}
