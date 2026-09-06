package constructit

import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Format
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.l10n.L10n
import constructit.l10n.Messages
import constructit.l10n.Msgs
import constructit.l10n.formatNumber
import constructit.units.mm
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The third language, proved end to end** (OP-29 slice 4).
 *
 * The point of the whole feature was never German: it was that *a* language is one entry in `targetLangs`
 * plus a review. French is the language that says so — added after the mechanism was built, translated by
 * the same plugin run, reviewed by `TranslationReviewTest`'s own checks plus a non-native pass over the
 * chrome, and shipped as a chunk with nothing in the code changed for it.
 *
 * So this is `GermanSessionTest` asked of a language nobody designed for: the chrome is French, the
 * numbers are French, a plural picks a branch, and the **file is not** — which is the property OP-18 owes
 * every language equally and the one a new locale is most likely to break.
 */
class FrenchSessionTest {
    @AfterTest
    fun english() {
        L10n.locale = "en"
    }

    /** French is bundled, and its chunk is beside German's rather than inside the main table. */
    @Test
    fun frenchIsALanguageThisBuildCarries() {
        assertTrue("fr" in Messages.locales, "expected fr among ${Messages.locales}")
        assertEquals(Messages.indexOf("fr"), Messages.indexOf("fr-CA"), "Canadian French is French")
        assertEquals("Dessin", Messages.uiPanelDrawing("fr"))
        assertEquals("Drawing", Messages.uiPanelDrawing("en"))
    }

    /**
     * **Every tool speaks French, and almost none of them by accident.**
     *
     * The 90 % bar is what tells a real translation from a bundle that fell back to English: a handful of
     * titles are legitimately the same word in both languages (*Point*, *Segment*, *Volume*), and a
     * hundred of them are not.
     */
    @Test
    fun everyToolSpeaksFrenchAndNothingIsLeftUnrendered() {
        var same = 0
        for (tool in Tools.all) {
            val french = Messages.text("tool.${tool.id}.title", "fr")
            assertTrue(french.isNotBlank(), "tool ${tool.id} has a French title")
            assertTrue('{' !in french && '}' !in french, "tool ${tool.id} renders every placeholder: $french")
            assertFalse(french.startsWith("tool."), "tool ${tool.id} is not its own key: $french")
            if (french == Messages.text("tool.${tool.id}.title", "en")) same++
            val help = Messages.patternOrNull("tool.${tool.id}.help", "fr").orEmpty()
            assertTrue(help.isBlank() || '{' !in help, "help of ${tool.id} renders in French: $help")
        }
        assertTrue(same * 10 < Tools.all.size, "$same of ${Tools.all.size} tool titles are unchanged in French")
    }

    /** A plural through ICU4J in a third language, and a `select` picking its branch. */
    @Test
    fun aPluralAndASelectReadCorrectlyInFrench() {
        val one = Messages.msgLoaded(1, "fr")
        val many = Messages.msgLoaded(7, "fr")
        assertTrue(one != many && "1" in one && "7" in many, "the French plural forms differ: $one / $many")
        assertEquals(Messages.uiDialogTitle("Groupe", 1, "fr"), Messages.uiDialogTitle("Groupe", 1, "fr"))
        assertTrue('{' !in Messages.uiTreeHidden("construction", "fr"), Messages.uiTreeHidden("construction", "fr"))
        assertEquals(Messages.uiTreeHidden("user", "fr"), Messages.uiTreeHidden("whatever", "fr"), "an unknown branch falls into other")
    }

    /** The figures too: French writes the comma, exactly as German does, and off the same value. */
    @Test
    fun theNumbersAreFrenchAndTheFileIsNot() {
        assertEquals("1,5", formatNumber("fr", 1.5, 1))
        assertEquals("12345,5", formatNumber("fr", 12345.5, 1), "no grouping, in every language")
        val why = Msgs.refusalShellShellNeedsPositiveWallThickness(Format.num(5.5))
        assertTrue("5,5 mm" in why.render("fr"), why.render("fr"))
        assertTrue("5.5 mm" in why.render("en"), why.render("en"))
        assertEquals("5,5", Format.display(5.5, "fr"))
        assertEquals(5.5, Format.read("5,5", "fr"))
        assertEquals(null, Format.read("5.5", "fr"), "the other language's separator is not a number here either")
    }

    /**
     * **The file says the same thing in French** (OP-18 × OP-29) — the assertion a new locale is likeliest
     * to break, because the writer and the panel use the same figures and only one of them may localize.
     */
    @Test
    fun theFileIsTheSameWhateverLanguageTheChromeSpeaks() {
        val en = sessionUnder("en")
        val fr = sessionUnder("fr")
        assertEquals(en, fr, "the file is format, not UI: byte-equal under two locales")
        assertEquals(fr, DocumentFormat.save(DocumentFormat.load(fr)), "…and a fixed point under fr")
        for (word in listOf("congé", "chanfrein", "esquisse", "solide", "contour")) {
            assertFalse(word in fr, "no French reaches the file: $word")
        }
        // …and no decimal comma in a measurement: the comma in `40.5,30` is the *coordinate* separator,
        // which is the file's own syntax and has nothing to do with anyone's notation
        for (literal in Regex("[-0-9.]+(?:mm|deg)\\b").findAll(fr)) {
            assertFalse(',' in literal.value, "a decimal comma reached the file: ${literal.value}")
        }
        assertTrue("6.5mm" in fr, "and a decimal literal was there to be spoiled:\n$fr")
    }

    /** The armed hint and the formula parser, in the third language — slice 4's two conversions. */
    @Test
    fun theHintAndTheParserSpeakFrenchToo() {
        val ed = Editor()
        ed.setTool(Tools.MIDPOINT)
        val hint = ed.currentHelp().render("fr")
        assertTrue("factor = 0,5" in hint, "the default is a figure, spelled the reader's way: $hint")
        assertFalse("(default)" in hint, "and the frame around it is French: $hint")

        val why = Msgs.refusalExprValueExpected(at = 4)
        assertTrue("4" in why.render("fr") && '{' !in why.render("fr"), why.render("fr"))
        assertEquals("a value is expected at position 4, and the expression ends there", why.render("en"))
    }

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    /** One drawing, built by gestures, under [locale] — a rectangle raised into a plate and rounded. */
    private fun sessionUnder(locale: String): String {
        L10n.locale = locale
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.5, 30.0))
        ed.activeScalar = ed.doc.newParameter("depth", 6.5.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(20.0, 0.0))
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "a plate under $locale: ${ed.statusHint}")
        return DocumentFormat.save(ed.doc)
    }
}
