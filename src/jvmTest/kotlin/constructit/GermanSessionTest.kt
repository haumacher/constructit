package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.l10n.L10n
import constructit.l10n.contains
import constructit.units.mm
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A German session, headless** (OP-29, slice 2) — the whole point of the slice, asserted from the outside.
 *
 * Slice 1 left a German chrome around English prose, and this is the test that says that is over: the same
 * gestures, the same document, the same objects, read once in German and once in English. Nothing is rebuilt
 * between the two readings — the refusal, the note and the face's name are *values*, and the language is a
 * property of the reader, so switching [L10n.locale] is the whole of the change.
 *
 * Three things are checked of every German sentence, because each of them was a way the refactor could have
 * been half-done: that it **is German** (not the English fall-back), that the **name inside it** is German
 * too (the nested-message argument, which a `String` name would have frozen in English), and that the
 * **numbers** it quotes are still there (a message that loses its arguments is worse than an English one).
 */
class GermanSessionTest {
    @AfterTest
    fun resetLocale() {
        L10n.locale = "en"
    }

    /**
     * **GitHub #29's script, verbatim** — a rounding whose file names one edge of a tangent-continuous run,
     * which the load re-reads as the whole run and *says so*. The load note this slice has to speak in the
     * reader's language.
     */
    private val issue29 =
        """
constructit 3
point -61.6061297403065,-10.474592995769466 -> e1
point 16.658941297601118,27.98155575420435 -> e2
tool segment pts=e1,e2 clicks=-71.375,24.375;-9.625,84.375 -> e3
point 43.04371547563214,-34.63299257776767 -> e4
tool segment pts=e2,e4 clicks=-9.625,84.375;27.125,0.875 -> e5
param "r" = 5mm
tool fillet els=e3,e5 clicks=-27.125,66.875;-2.875,70.875 scalar="r" signs=-1;1 -> e6
tool keypoints els=e6 clicks=-11.375,77.875 -> e7,e8,e9
tool segment pts=e8,e1 clicks=-17.625,76.125;-71.625,24.125 -> e10
tool segment pts=e9,e4 clicks=-4.375,73.375;27.625,1.125 -> e11
hide els=e3
hide els=e5
hide els=e2
hide els=e7
tool segment pts=e1,e4 clicks=-70.625,23.875;27.625,-0.375 -> e12
param "h" = 20mm
tool outline els=e12,e10,e6,e11 clicks=-45.625,18.375;-54.375,41.125;-11.065544035971813,76.9875382494712;11.649132871223834,36.03785456470231 -> e13,e14,e15,e16,e17
tool extrude els=e17 clicks=-40.125,17.375 scalar="h" -> e18
tool filletedge els=e18 clicks=6.353621791250703,46.69554776203246 scalar="r" signs=8;-1;1;0;1 -> e19
show els=e2
""".trimStart()

    /** A 6 mm plate whose 10 mm rounding has nowhere to go — the refusal that names the edge and the size. */
    private fun aRoundingTooLargeToFit(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 30.0))
        ed.activeScalar = ed.doc.newParameter("depth", 6.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(20.0, 0.0))
        ed.activeScalar = ed.doc.newParameter("r", 10.0.mm)
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(20.0, 0.0))
        return ed
    }

    /**
     * **The refusal speaks German** (OP-3 × OP-29): the same value, read twice.
     *
     * The sentence names the edge, and the *edge's own name* is a message argument — so it is German too,
     * which is exactly what a face name kept as a `String` could never have been. The millimetres stay where
     * they were: number formatting is slice 3, and this slice must not have moved it.
     */
    @Test
    fun aRefusalIsGermanInAGermanSessionAndEnglishAgainAfterwards() {
        val ed = aRoundingTooLargeToFit()
        val el = ed.doc.elements.last { it.kind == ElementKind.SOLID }
        val invalid = assertNotNull(Evaluator().eval(el.ref.node) as? EvalResult.Invalid, "10 mm on a 6 mm plate")

        L10n.locale = "de"
        val german = invalid.reason
        assertTrue("Verrundung" in german, "the kind of rounding, in German: $german")
        assertTrue("Randkante #1 der oberen Fläche" in german, "and the edge's own name, in German: $german")
        assertTrue("das Größte, was dort passt" in german, "and the cure: $german")
        assertTrue(Regex("\\d").containsMatchIn(german), "the number it must type is still in it: $german")
        assertFalse("boundary edge" in german, "no English left in it: $german")

        // …and the same object, read again, is the English sentence it always was
        L10n.locale = "en"
        val english = invalid.reason
        assertNotEquals(german, english)
        assertTrue("boundary edge #1 of the top face" in english, english)
        assertTrue("largest that fits" in english, english)
    }

    /** The status line is the same value one layer up, so it switches with the reader too. */
    @Test
    fun theStatusLineOfARefusedGestureSwitchesLanguage() {
        val ed = aRoundingTooLargeToFit()
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(-100.0, -100.0))
        L10n.locale = "de"
        val germanLine = ed.statusLine
        assertTrue("Verrundung" in germanLine || "Randkante" in germanLine, "the standing validity note is German: $germanLine")
        L10n.locale = "en"
        assertTrue("boundary edge" in ed.statusLine || "fillet" in ed.statusLine, ed.statusLine)
        assertNotEquals(germanLine, ed.statusLine)
    }

    /**
     * **A load note is a value too** — so a drawing opened in a German session says in German what the load
     * had to decide, and the very same document says it in English a moment later.
     *
     * This is the one that could not work at all before the slice: a load note was composed while the file
     * was being read, so its language was whatever was active *then*.
     */
    @Test
    fun aLoadNoteIsGermanInAGermanSession() {
        L10n.locale = "de"
        val doc = DocumentFormat.load(issue29)
        val note = assertNotNull(doc.noteMsg, "the migration says what it decided")
        val german = note.render()
        assertTrue("Kanten" in german, "the load note is German: $german")
        assertFalse("edges of the tangent" in german, "no English left in it: $german")
        L10n.locale = "en"
        assertTrue("edges of the tangent-continuous run" in note, "and the same note in English: ${note.render()}")
        assertNotEquals(german, note.render())
    }

    /**
     * **The file is locale-neutral** (OP-18), on three drawings rather than the two slice 1 checked: a script
     * saved in a German session is byte-identical to the one saved in an English session, and round-trips.
     */
    @Test
    fun theFileSaysTheSameThingInEveryLanguage() {
        val builders: List<() -> Editor> = listOf(::aRoundingTooLargeToFit, ::aConnectedPair, ::aWallOverAPath)
        for (build in builders) {
            L10n.locale = "en"
            val inEnglish = DocumentFormat.save(build().doc)
            L10n.locale = "de"
            val inGerman = DocumentFormat.save(build().doc)
            assertEquals(inEnglish, inGerman, "the saved script must not depend on the reader's language")
            assertEquals(inGerman, DocumentFormat.save(DocumentFormat.load(inGerman)), "and it is a fixed point under de")
        }
        // …and the fixture the load note came from is a fixed point in German as well
        L10n.locale = "de"
        val once = DocumentFormat.save(DocumentFormat.load(issue29))
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "issue #29's file, saved under de")
        L10n.locale = "en"
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "…and the same bytes under en")
    }

    private fun aConnectedPair(): Editor {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 10.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 10.0))
        return ed
    }

    private fun aWallOverAPath(): Editor {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.click(Vec2(60.0, 40.0))
        return ed
    }

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }
}
