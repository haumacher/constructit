package constructit

import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Format
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.l10n.L10n
import constructit.l10n.Messages
import constructit.units.mm
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** **Orchestrator's probe of OP-29 slice 4**: the third language, end to end, on what the delivery never saw. */
class FrenchProbeTest {
    @AfterTest
    fun english() {
        L10n.locale = "en"
    }

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    @Test
    fun frenchIsAThirdReadingOfEveryToolAndOfAStatusLine() {
        assertTrue("fr" in Messages.locales, "French is a bundle: ${Messages.locales}")
        var sameAsEn = 0
        var sameAsDe = 0
        for (t in Tools.all) {
            val fr = Messages.text("tool.${t.id}.title", "fr")
            assertTrue(fr.isNotBlank() && '{' !in fr && !fr.startsWith("tool."), "${t.id}: $fr")
            if (fr == Messages.text("tool.${t.id}.title", "en")) sameAsEn++
            if (fr == Messages.text("tool.${t.id}.title", "de")) sameAsDe++
        }
        assertTrue(sameAsEn < Tools.all.size / 10 && sameAsDe < Tools.all.size / 10, "French is its own: $sameAsEn like English, $sameAsDe like German")
        L10n.locale = "fr"
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 20.0))
        ed.activeScalar = ed.doc.newParameter("d", 7.5.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(15.0, 0.0))
        val fr = ed.statusHint
        assertTrue(fr.isNotBlank() && '{' !in fr, "a French status: $fr")
        assertTrue("7,5" in fr || "7,5" in Format.display(7.5, "fr"), "French spells the figure with a comma: $fr / ${Format.display(7.5, "fr")}")
        L10n.locale = "en"
        assertNotEquals(fr, ed.statusHint, "the same message, English now")
        val saved = DocumentFormat.save(ed.doc)
        assertTrue("7.5mm" in saved, "the file keeps the point: $saved")
        assertEquals(saved, DocumentFormat.save(DocumentFormat.load(saved)))
    }

    @Test
    fun theArmedToolHintReReadsOnASwitch() {
        // no parameter armed: the hint names the tool's default, which is where the last English fragments lived
        val ed = Editor()
        // Connect carries two defaulted tensions, so its armed hint names a default in every language
        ed.setTool(Tools.CONNECT)
        val hint = ed.currentHelp()
        val de = hint.render("de")
        val fr = hint.render("fr")
        val en = hint.render("en")
        assertTrue(de.isNotBlank() && '{' !in de, "the armed hint in German: $de")
        assertNotEquals(de, fr, "re-read in French: $fr")
        assertTrue('{' !in fr, fr)
        assertNotEquals(fr, en, "and in English: $en")
        assertTrue("default" in en && "tension" in en && "1" in en, "the English hint names the default: $en")
        assertTrue("tension" in de && "tension" in fr, "the slot's own name is a file name and stays: $de / $fr")
    }
}
