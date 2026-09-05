package constructit

import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.l10n.contains
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **One naming authority, and it is the file's** (OP-18).
 *
 * The panel, the status line and every dialog used to show the runtime [Element.id], which counts everything
 * the document ever created — parameters, measurements, hidden coordinate sources, frames — while the file
 * numbers only the elements the journal declares, from 1 and gapless. Same `eN` shape, different numbers: a
 * drawing whose file said `e17` had the user looking at `e21`. Reported as a defect, and it garbled every
 * conversation *about* a drawing, which is what a name is for.
 *
 * These tests assert the equality directly — every visible name is the name the saved script declares — and
 * they start by asserting that the two *would* differ, so what is proved is the fix and not a tautology.
 */
class NameAuthorityTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    /** The names a saved script declares, in order: everything after each step's `->`. */
    private fun declaredNames(text: String): List<String> =
        text.lines().mapNotNull { line ->
            line.substringAfter("->", "").trim().takeIf { it.isNotEmpty() }
        }.flatMap { it.split(",").map { n -> n.trim() } }

    /** Every element's name as the *document* gives it — what the panel row, the labels and the notes show. */
    private fun shownNames(doc: Document): List<String> = doc.elements.map { doc.nameOf(it) }

    /**
     * A drawing whose runtime ids **must** diverge from the script's: two panel parameters (each burning an id
     * for its node and one for its entry) and a measurement, with geometry created around them.
     */
    private fun diverging(): Editor {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.CIRCLE_R) // a typed radius: one parameter
        for (c in "20") ed.key(c.toString())
        ed.key("Enter")
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.POINT_ON_LINE) // a rider: its parameter takes an id too
        ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.DISTANCE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.SELECT)
        return ed
    }

    @Test
    fun everyNameTheUserSeesIsTheNameTheFileWrites() {
        val ed = diverging()
        val text = DocumentFormat.save(ed.doc)

        // the premise: the internal ids are *not* the file's names, which is what the defect showed
        assertTrue(
            ed.doc.elements.any { it.id != ed.doc.nameOf(it) },
            "the two numberings must actually differ, or this proves nothing: ${ed.doc.elements.map { it.id }}",
        )
        // …and every name the user can read is the file's
        assertEquals(declaredNames(text), shownNames(ed.doc), "the panel's names are the script's, in order")
        assertEquals((1..ed.doc.elements.size).map { "e$it" }, shownNames(ed.doc), "gapless, from 1")
    }

    @Test
    fun theSelectionLabelAndTheStatusLineNameElementsTheSameWay() {
        val ed = diverging()
        val rider = ed.doc.elements.last { it.kind == ElementKind.ON_CURVE }
        val name = ed.doc.nameOf(rider)
        assertTrue(name != rider.id, "the fixture's ids diverge, so the label is a real test")

        ed.setTool(Tools.SELECT)
        ed.click(Vec2(30.0, 0.0))
        assertEquals("on_curve $name", ed.selectionLabel().render(), "the inspector header names it as the file does")

        // a refusal names the element the same way: this rider is not anchorable to a point off its carrier
        ed.setTool(Tools.MAKE_RELATIVE)
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(0.0, 0.0))
        assertTrue(ed.statusHint.contains(name), "the note names it as the file does: ${ed.statusHint}")
        assertTrue(!ed.statusHint.contains(rider.id), "and never by the internal id: ${ed.statusHint}")
    }

    /** Load is where the two numberings met head-on: a reloaded document must show exactly the file's names. */
    @Test
    fun aReloadedDrawingShowsTheNamesItsFileCarries() {
        val ed = diverging()
        val text = DocumentFormat.save(ed.doc)
        val fresh = DocumentFormat.load(text)
        assertEquals(declaredNames(text), shownNames(fresh))
        assertEquals(shownNames(ed.doc), shownNames(fresh), "and the same names before and after a round trip")
        assertEquals(text, DocumentFormat.save(fresh), "which is what byte-equality already promised")
    }

    /**
     * **A retired element keeps its number, and the UI says the same one.** An ortho path whose straight-on
     * step coalesced leaves a vertex that the file still creates (replay needs it, so the later `orthojoin`
     * has something to collapse) and the document no longer holds. That is the one case where a name is not
     * the name of anything visible — and the elements that *are* visible must still agree with the file.
     */
    @Test
    fun aCoalescedVertexDoesNotShiftTheNamesOfWhatSurvives() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(50.0, 2.0))
        ed.click(Vec2(48.0, -30.0))
        ed.click(Vec2(110.0, -28.0))
        ed.finishPath()
        // pull the jog flat: the middle leg collapses and the join retires two vertices and a leg (OP-19)
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(80.0, -30.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(80.0, 0.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(80.0, 0.0)))
        assertEquals(1, ed.doc.orthoPaths.single().legCount, "the jog is gone: ${ed.statusHint}")
        assertTrue(DocumentFormat.save(ed.doc).contains("orthojoin"), "and the join is a step")

        val text = DocumentFormat.save(ed.doc)
        val declared = declaredNames(text)
        for (el in ed.doc.elements) {
            assertTrue(
                ed.doc.nameOf(el) in declared,
                "${ed.doc.nameOf(el)} is shown but the file declares only $declared",
            )
        }
        assertEquals(shownNames(DocumentFormat.load(text)), shownNames(ed.doc), "and a reload agrees")
    }

    /** The same, over a break and a join — the other way an element is retired (OP-19). */
    @Test
    fun aBrokenAndRejoinedCurveKeepsUiAndFileInAgreement() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.BREAK_LEG)
        ed.click(Vec2(50.0, 0.0))
        val text = DocumentFormat.save(ed.doc)
        assertEquals(declaredNames(text).filter { it in shownNames(ed.doc) }, shownNames(ed.doc))
        assertTrue(ed.statusHint.split(" ").any { it.trimEnd('.', ',') in declaredNames(text) }, "got: ${ed.statusHint}")
    }

    /** The reported drawing: its panel names are its script's names, element for element. */
    @Test
    fun theWheelsPanelNamesAreItsScriptNames() {
        val doc = DocumentFormat.load(WHEEL_SCRIPT)
        val text = DocumentFormat.save(doc)
        assertEquals(declaredNames(text), shownNames(doc))
        assertTrue(doc.elements.any { it.id != doc.nameOf(it) }, "the wheel's ids diverge — parameters and riders take numbers")
        // and the name the load's own note used is one of them (OP-18's migration findings)
        val note = assertNotNull(doc.loadNotes.firstOrNull())
        assertTrue(declaredNames(text).any { note.render().startsWith(it) }, "the note names the element as the file does: $note")
    }

    private companion object {
        /** A cut of the reported wheel: a rider on a perpendicular bisector, and a format-1 header. */
        val WHEEL_SCRIPT =
            """
constructit 1
param "r" = 122mm
point -6,3.5 -> e1
tool circleR pts=e1 clicks=-5.25,2.5 scalar="r" -> e2
pointoncurve e2 -12.02973535129569,125.35090189076706 dofs=118.46225976403586deg -> e3
tool segment pts=e1,e3 clicks=-5.75,3.75;-12,124.75 -> e4
param "d" = 10mm
tool parallelat els=e4 clicks=-7,30.5;0,32.25 scalar="d" -> e5
tool intersect els=e5,e2 clicks=-1.5,116.25;8.5,124.25 -> e6,e7
tool perpbis pts=e7,e1 clicks=-2.75,125.25;89.75,77 -> e8
pointoncurve e8 14.118741663069027,42.702264910197286 dofs=52.86964276686915mm -> e9
tool perp pts=e9 els=e8 clicks=14.25,43.25;15,42.25 -> e10
""".trimStart()
    }
}
