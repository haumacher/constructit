package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.Section3
import constructit.geom.Vec2
import constructit.l10n.L10n
import constructit.units.mm
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of OP-29 slice 2** on what the delivery never saw: an engine refusal that nests a face's
 * and an edge's **name** and a number, read in German and English off the *same* value; a load note of an
 * older file in German; a status line that changes language without the gesture being repeated; and the file
 * staying English under a German session that produced a refusal.
 */
class MessageValueProbeTest {
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

    /** A 20 x 20 x 10 block with a rounding too large for its edge — the engine's own refusal, with names and a number in it. */
    private fun tooLarge(): EvalResult.Invalid {
        val cx = Construction()
        val pts = listOf(Vec2(0.0, 0.0), Vec2(20.0, 0.0), Vec2(20.0, 20.0), Vec2(0.0, 20.0)).mapIndexed { i, p -> cx.freePoint("p$i", p.x.mm, p.y.mm) }
        val segs = pts.indices.map { cx.segment(pts[it], pts[(it + 1) % 4]) }
        val body = cx.extrude(cx.sketchOn(cx.planeXY(), cx.region(cx.loop(*segs.toTypedArray()))), cx.const(10.0.mm))
        val solid = Evaluator().solid(body)
        val (targets, _) = Blend3.targets(solid.feature, false, 0)
        val (choices, _) = Blend3.choicesFor(solid, assertNotNull(targets), BlendSection(BlendKind.FILLET, 3.0))
        val blend = cx.blend(body, body, cx.planeXY(), cx.const(25.0.mm), BlendKind.FILLET, false, 0, assertNotNull(choices))
        val res = Evaluator().eval(blend.node)
        assertTrue(res is EvalResult.Invalid, "a 25 mm rounding on a 20 mm block is refused")
        return res
    }

    @Test
    fun aRefusalWithNamesAndANumberReadsInBothLanguagesOffOneValue() {
        val why = tooLarge().why
        val en = why.render("en")
        val de = why.render("de")
        assertTrue("the largest that fits there is" in en, "the English is the sentence it was: $en")
        assertTrue("reaches past" in en, en)
        assertNotEquals(en, de, "the German is another rendering of the same value")
        assertTrue('{' !in de && '}' !in de, "every placeholder rendered: $de")
        // the nested names and the number arrive in both
        val number = Regex("""\d+(\.\d+)?""").find(en.substringAfter("fits there is"))!!.value
        assertTrue(number in de, "the same number in the German: $de")
        assertTrue("mm" in de, "with its unit: $de")
        assertTrue("#" in de, "an edge's or face's number names it in German too: $de")
        // toString is a rendering in the active locale, so a printed reason is readable
        L10n.locale = "de"
        assertEquals(de, why.toString())
    }

    @Test
    fun anOlderFilesLoadNoteSpeaksTheSessionsLanguage() {
        val fixture = """constructit 4
point -60.625,-15.875 -> e1
point -27.5,45.5 -> e2
tool segment pts=e1,e2 clicks=-60.625,-15.875;-39.125,50.375 -> e3
point 0.125,-11.125 -> e4
tool segment pts=e2,e4 clicks=-40.125,49.125;33.125,-24.875 -> e5
param "r2" = 4mm
tool fillet els=e3,e5 clicks=-50.125,16.875;-20.625,30.125 scalar="r2" signs=-1;1 -> e6
tool connect els=e3,e5 clicks=-59.875,-12.875;-4.375,-26.875 signs=0;0 dofs=1;1 -> e7
"""
        L10n.locale = "de"
        val doc = DocumentFormat.load(fixture)
        val notes = doc.loadNotes
        assertEquals(1, notes.size, "one note: $notes")
        val de = notes.single().render("de")
        val en = notes.single().render("en")
        assertNotEquals(de, en, "the note is one value, two readings")
        assertTrue("e7" in de && "e7" in en, "the join is named in both: $de")
        assertTrue('{' !in de, de)
        // the file itself is English whatever the session speaks
        val saved = DocumentFormat.save(doc)
        assertTrue(saved.startsWith("constructit ") && "Verrundung" !in saved && "Kurve" !in saved, "the file is format, not prose")
    }

    @Test
    fun theStatusLineChangesLanguageWithoutTheGestureBeingRepeated() {
        L10n.locale = "de"
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 20.0))
        val body = extrude(ed)
        val de = ed.statusHint
        assertTrue(de.isNotBlank() && '{' !in de, "a German status after the gesture: $de")
        L10n.locale = "en"
        val en = ed.statusHint
        assertNotEquals(de, en, "the same message, read in English now: $en")
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "and nothing was built twice")
        // a name inside the status is a message too: it re-renders with the rest
        val faces = assertNotNull(Section3.faces(Evaluator().solid(body).feature).first)
        val top = faces.first { it.name.label.render("en").contains("top face") }
        assertNotEquals(top.name.label.render("en"), top.name.label.render("de"), "a face's name is a message")
    }

    private fun extrude(ed: Editor): SolidRef {
        ed.activeScalar = ed.doc.newParameter("d", 5.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(15.0, 0.0))
        return ed.doc.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef
    }
}
