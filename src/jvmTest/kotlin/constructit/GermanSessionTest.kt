package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Format
import constructit.editor.Tools
import constructit.expr.ExprError
import constructit.expr.ExprEval
import constructit.expr.ExprParser
import constructit.geom.Vec2
import constructit.l10n.L10n
import constructit.l10n.Messages
import constructit.l10n.Msgs
import constructit.l10n.contains
import constructit.units.Dimension
import constructit.units.DimensionError
import constructit.units.Quantity
import constructit.units.mm
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    /**
     * **The file is still locale-neutral now that the numbers are not** (OP-18 × OP-29 slice 3).
     *
     * The slice moved every measured figure the *reader* sees into the reader's own notation, and the one
     * thing that must not have moved with it is the *writer*: `DocumentFormat` keeps the decimal point, in
     * every language, or a drawing saved in Berlin would not reopen in London. Three reporter-supplied
     * files rather than three built drawings, because a file full of coordinates like
     * `41.999800864975384` is where a stray `NumberFormat` would show first — and one of them (#31) carries
     * relative parameterizations whose own literals go through the same writer.
     */
    @Test
    fun theWriterKeepsTheDecimalPointInEveryLanguage() {
        val saved = ArrayList<String>()
        for ((what, script) in listOf("#35" to issue35, "#31" to issue31, "connect" to connectScript)) {
            L10n.locale = "en"
            val inEnglish = DocumentFormat.save(DocumentFormat.load(script))
            L10n.locale = "de"
            val once = DocumentFormat.save(DocumentFormat.load(script))
            assertEquals(inEnglish, once, "$what: the saved script must not depend on the reader's language")
            assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "$what: save -> load -> save under de")
            // the writer's own spelling, stated positively: every `<number>mm` literal keeps its point, and
            // a comma is only ever the separator between the two coordinates of a click
            for (literal in Regex("[-0-9.]+(?:mm|deg)\\b").findAll(once)) {
                assertFalse(',' in literal.value, "$what: a decimal comma reached the file: ${literal.value}")
            }
            saved.add(once)
        }
        // …and the check above is worth something only if a decimal literal was there to be spoiled
        assertTrue(Regex("[0-9]\\.[0-9]").containsMatchIn(saved.joinToString("\n")), "expected decimal literals to check")
    }

    /**
     * **A refusal states its size in the reader's numbers** (OP-29 slice 3) — off *one* value, read twice.
     *
     * Slice 2 handed this message a `String` the engine had already spelled, which is why a German session
     * read `5.5 mm` inside an otherwise German sentence. The figure is a `Num` now: the engine still states
     * it once, in the file's own spelling and at the file's own precision, and the *reader* decides how it
     * is punctuated. Note what does **not** change with the language — the digits, and the `mm`.
     */
    @Test
    fun aRefusalStatesItsSizeInTheReadersNumbers() {
        val why = Msgs.refusalShellShellNeedsPositiveWallThickness(Format.num(5.5))
        assertEquals("a shell needs a positive wall thickness — this one is 5.5 mm", why.render("en"))
        assertTrue("5,5 mm" in why.render("de"), why.render("de"))
        assertFalse("5.5" in why.render("de"), "the decimal point is the file's, not the German reader's")
        // …and the precision is the engine's in both: the locale moves the separator and nothing else
        val fine = Msgs.refusalShellShellNeedsPositiveWallThickness(Format.num(0.1252))
        assertTrue("0.125 mm" in fine.render("en"), fine.render("en"))
        assertTrue("0,125 mm" in fine.render("de"), fine.render("de"))
    }

    /** An angle, an area and a plain factor — the units that are symbols, and the separator that is not. */
    @Test
    fun anAngleAndAnAreaAreSpelledTheReadersWay() {
        val angle = Format.quantityMsg(Quantity.deg(12.5))
        assertEquals("12.5°", angle.render("en"))
        assertEquals("12,5°", angle.render("de"))
        val area = Format.quantityMsg(Quantity(1234.5, Dimension.AREA))
        assertEquals("1234.5 mm²", area.render("en"), "no grouping: the drawing's figures read against the file")
        assertEquals("1234,5 mm²", area.render("de"))
        assertEquals("0.25", Format.quantityMsg(Quantity.number(0.25)).render("en"))
        assertEquals("0,25", Format.quantityMsg(Quantity.number(0.25)).render("de"))
    }

    /** A count inside a plural is a *number* to ICU, so both engines group it the way the language does. */
    @Test
    fun aCountInsideAPluralIsWrittenTheReadersWay() {
        assertEquals("Loaded 1,234 elements", Messages.msgLoaded(1234, "en"))
        assertEquals("Geladen 1.234 Elemente", Messages.msgLoaded(1234, "de"))
        assertEquals("Loaded 1 element", Messages.msgLoaded(1, "en"))
        assertEquals("Geladen 1 Element", Messages.msgLoaded(1, "de"))
    }

    /**
     * **The parameter panel round trip** (OP-29 slice 3), through the editor's own API rather than the DOM:
     * a German session types `5,5`, the file stores `5.5mm`, and the field shows `5,5` again.
     *
     * `Format.read` is the one place this project writes a rule instead of calling a library — there is no
     * reference *parser* in either target — and `Format.display` is its inverse through `NumberFormat`. The
     * three assertions are the three things that have to hold at once: what the reader typed became the
     * value they meant, the writer did not follow them into a comma (OP-18), and reading it back gives the
     * reader their own notation and not the file's.
     */
    @Test
    fun theParameterPanelTakesADecimalCommaAndGivesOneBack() {
        L10n.locale = "de"
        val ed = Editor()
        val r = ed.doc.newParameter("r", 1.0.mm)
        val typed = assertNotNull(Format.read("5,5"), "a German session types a comma")
        ed.setParameter(r, typed)

        assertEquals(5.5, typed, "what was typed is what it means")
        val script = DocumentFormat.save(ed.doc)
        assertTrue("5.5mm" in script, "the file keeps the decimal point (OP-18): $script")
        assertFalse("5,5" in script, "…and never the reader's: $script")
        assertEquals("5,5", Format.display(5.5), "and the field shows it back the way it was typed")
        assertEquals("5.5", Format.display(5.5, "en"), "…while an English session reads the same value its way")

        // the rule is symmetric, and strict where it has to be: the *other* language's separator is not a
        // number, because `1.234` would otherwise mean two different things in the two sessions
        assertEquals(null, Format.read("5.5", "de"))
        assertEquals(null, Format.read("5,5", "en"))
        assertEquals(null, Format.read("", "de"))
        assertEquals(-0.5, Format.read("-0,5", "de"))
    }

    /**
     * **The armed tool's hint is a value too** (OP-29 slice 4) — the last sentence the editor assembled in
     * Kotlin.
     *
     * It was `tool.help + " Using " + "name = " + Format.quantity(default) + " (default)"`, five English
     * fragments joined at the moment the status line was *built*. It is one message with the name and the
     * quantity as arguments now, so the reader's language decides at the moment the line is *painted* — the
     * same property every other note has had since slice 2, asserted the same way: one value, read twice.
     */
    @Test
    fun theArmedToolsHintIsGermanAndSwitchesBackWithoutTheGestureRepeating() {
        val ed = Editor()
        ed.setTool(Tools.MIDPOINT)
        val hint = ed.currentHelp()

        val english = hint.render("en")
        assertTrue("Using factor = 0.5 (default)" in english, english)
        assertTrue("type it and click" in english, english)

        val german = hint.render("de")
        assertTrue("factor = 0,5 (Standard)" in german, "the default is a quantity, spelled the reader's way: $german")
        assertTrue("Verwendet" in german, "and the frame around it is German: $german")
        assertFalse("(default)" in german, "no English fragment survives: $german")
        // the slot's own name stays as it is: it becomes a parameter name in the file (OP-18)
        assertTrue("factor" in german, german)
        assertNotEquals(english, german)
        assertEquals(english, hint.render("en"), "and the very same value reads English again")
    }

    /**
     * **The formula parser's diagnostics speak German** (OP-29 slice 4) — parked by slice 2 and again by
     * slice 3, and the last English the engine produced.
     *
     * They are a sublanguage with character positions in them, which is exactly why they are messages
     * rather than strings: the position is an *argument*, the function and unit names are the formula
     * language's own vocabulary and stay as they are (OP-18), and only the sentence around them moves.
     */
    @Test
    fun theFormulaParsersDiagnosticsSpeakGerman() {
        val blank = assertFailsWith<ExprError> { ExprParser.parse("  ") }
        assertEquals("an expression is expected, and this is blank", blank.why.render("en"))
        assertTrue("Ausdruck" in blank.why.render("de"), blank.why.render("de"))

        val position = assertFailsWith<ExprError> { ExprParser.parse("1 +") }
        assertTrue("a value is expected at position 4" in position.why.render("en"), position.why.render("en"))
        val german = position.why.render("de")
        assertTrue("Position 4" in german, "the position is an argument, not a word: $german")
        assertFalse("expected" in german, german)

        // the unit and the function names are the formula language's own and are never translated
        val unit = assertFailsWith<ExprError> { ExprParser.parse("3furlong") }
        assertTrue("''furlong''".replace("''", "'") in unit.why.render("de"), unit.why.render("de"))
        assertTrue("mm, cm, m, deg" in unit.why.render("de"), unit.why.render("de"))

        // …and a dimension violation, which reaches the reader through the same channel
        val dim = assertFailsWith<DimensionError> { ExprEval.eval(ExprParser.parse("1mm + 1deg")) { null } }
        assertEquals("cannot add L and A", dim.why.render("en"))
        assertTrue("addiert" in dim.why.render("de"), dim.why.render("de"))
        assertTrue("L" in dim.why.render("de") && "A" in dim.why.render("de"), "the dimension tokens stand: ${dim.why.render("de")}")
    }

    /**
     * …and the whole of it through the editor: a formula that parses and then leaves its domain arrives as
     * the ordinary named invalidity that heals (OP-3), with the parser's own sentence inside it — so the
     * *reason* switches language with everything else rather than being the one English island left.
     */
    @Test
    fun aBadFormulaRefusesInTheReadersLanguage() {
        val ed = Editor()
        val r = ed.doc.newParameter("r", 10.0.mm)
        assertTrue(ed.doc.bindParameter(r, "sqrt(0 - 1)*1mm"), "the text parses; it is the value that has no root")
        val invalid = assertNotNull(Evaluator().eval(r.ref.node) as? EvalResult.Invalid, "a root of a negative")
        assertTrue("sqrt of a negative value" in invalid.why.render("en"), invalid.why.render("en"))
        val german = invalid.why.render("de")
        assertTrue("Quadratwurzel" in german, german)
        assertFalse("negative value" in german, german)
        // …and the formula itself is quoted verbatim in both, because it is the file's text (OP-18)
        assertTrue("sqrt(0 - 1)*1mm" in german, german)
    }

    /** **GitHub #35's attached file, verbatim** — seven roundings on one body, all by the one parameter `r`. */
    private val issue35 =
        """
constructit 5
orthostart -26.875,-32.375 -> e1
orthovertex -26.875,15.375 -> e2,e3
orthovertex 41.999800864975384,15.375 -> e4,e5
orthovertex 41.999800864975384,-11.775083491926196 -> e6,e7
orthovertex -5.521648428788623,-11.775083491926196 -> e8,e9
orthovertex -5.521648428788623,-32.375 -> e10,e11
orthoclose -> e12
param "h" = 20mm
tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
hide els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
show els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
param "r" = 5mm
tool filletedge els=e13 clicks=-42.670739764447546,-4.867038301721209 scalar="r" signs=12;-1;1;0;1 -> e14
tool filletedge els=e14 clicks=-31.533048614089623,14.504582242265968 scalar="r" signs=13;-1;1;0;1 -> e15
tool filletedge els=e15 clicks=-15.209120508301623,-22.09480593584297 scalar="r" signs=1;-1;1;0;1 -> e16
tool filletedge els=e16 clicks=-1.6336097588108203,35.97358839564461 scalar="r" signs=14;-1;1;0;1 -> e17
tool filletedge els=e17 clicks=-11.301657028615722,8.858099327956722 scalar="r" signs=2;-1;1;0;-1 -> e18
tool filletedge els=e18 clicks=56.88568755568988,21.122250050431774 scalar="r" signs=3;-1;1;0;1 -> e19
tool filletedge els=e19 clicks=52.78762484641989,32.49678119098172 scalar="r" signs=15;-1;1;0;1 -> e20
""".trimStart()

    /** **GitHub #31's script, verbatim** — two roundings meeting in a concave corner, over two relative legs. */
    private val issue31 =
        """
constructit 3
orthostart -13.18902721970728,-16.006179064359657 -> e1
orthovertex -13.18902721970728,31.488369994030137 -> e2,e3
orthovertex 50.93454370701417,31.488369994030137 -> e4,e5
orthovertex 50.93454370701417,21.488369994030137 -> e6,e7
orthovertex -3.1890272197072793,21.488369994030137 -> e8,e9
orthovertex -3.1890272197072793,-16.006179064359657 -> e10,e11
orthoclose -> e12
param "h" = 20mm
tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
hide els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
show els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
tool makerel els=e10,e1 clicks=-5.003059576596437,-32.398104273234466;-27.16684620319296,-32.96159037391065 dofs=10mm
tool makerel els=e6,e4 clicks=61.86395770364392,0.09626086575873762;62.80310120477089,14.934728183564884 dofs=-10mm
param "r" = 3mm
tool filletedge els=e13 clicks=-4.752493982761422,46.49095575601497 scalar="r" signs=13;-1;1;0;1 -> e14
tool filletedge els=e14 clicks=30.09910577599922,89.3378106087082 scalar="r" signs=14;-1;1;0;1 -> e15
""".trimStart()

    /** The connect fixture, as a script — two welded points and the segment between them. */
    private val connectScript = DocumentFormat.save(aConnectedPair().doc)

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
