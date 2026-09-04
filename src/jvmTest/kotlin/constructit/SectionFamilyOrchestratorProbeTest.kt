package constructit

import constructit.SectionFamilyFixture.Rect
import constructit.SectionFamilyFixture.click
import constructit.SectionFamilyFixture.invalidity
import constructit.SectionFamilyFixture.meshOf
import constructit.SectionFamilyFixture.midOf
import constructit.SectionFamilyFixture.solids
import constructit.SectionFamilyFixture.straightRun
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.ScalarEntry
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of the function family** — composed with what the delivery was not written against:
 * a law that reads a *free* parameter of the drawing (edited, undone, reloaded), the delete cascade through the
 * *right* side of a law, and OP-3's healing when the section itself goes empty at every station.
 */
class SectionFamilyOrchestratorProbeTest {
    private fun assertRoundTrips(ed: Editor): Document {
        val once = DocumentFormat.save(ed.doc)
        val reloaded = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(reloaded), "save -> load -> save must be identical")
        return reloaded
    }

    private fun Editor.selectAt(where: Vec2) {
        setTool(Tools.SELECT)
        click(where)
    }

    private fun volumeOf(el: Element): Double = Geom3.volume(meshOf(el))

    private fun body(ed: Editor): Element = ed.doc.elements.last { it.kind == ElementKind.SOLID }

    /** `w = span * (1 - 0.5 t)`, `h = 10`, a 100 mm run: volume is `10 * span * 0.75 * 100 = 750 * span`. */
    private fun spanRect(): Pair<Editor, ScalarEntry> {
        val ed = Editor()
        val rect = Rect(ed, 20.0, 10.0)
        val span = ed.doc.newParameter("span", 20.0.mm)
        straightRun(ed, 100.0)
        ed.selectAt(rect.pick())
        assertTrue(ed.setFamilyLaw("w", "span * (1 - 0.5*t)"), ed.statusHint)
        ed.setTool(Tools.SWEEP)
        ed.click(midOf(100.0))
        ed.click(rect.pick())
        assertNotNull(ed.solids().lastOrNull(), "the sweep was built: ${ed.statusHint}")
        return ed to span
    }

    @Test
    fun aLawReadingAFreeParameterFollowsItsValueThroughEditUndoAndReload() {
        val (ed, span) = spanRect()
        assertManifold(meshOf(body(ed)), "span = 20")
        assertClose(volumeOf(body(ed)), 15000.0, tol = 1e-6, msg = "750 * 20")

        assertTrue(ed.setParameter(span, 40.0), ed.statusHint)
        assertManifold(meshOf(body(ed)), "span = 40")
        assertClose(volumeOf(body(ed)), 30000.0, tol = 1e-6, msg = "750 * 40 — the law re-reads the parameter")

        val reloaded = assertRoundTrips(ed)
        val back = reloaded.elements.last { it.kind == ElementKind.SOLID }
        assertNull(invalidity(back), "the reloaded body is live")
        assertClose(Geom3.volume(meshOf(back)), 30000.0, tol = 1e-6, msg = "and reads the stored value")

        assertTrue(ed.undo(), "undo the edit")
        assertClose(volumeOf(body(ed)), 15000.0, tol = 1e-6, msg = "back to 750 * 20")
        assertTrue(ed.redo(), "redo it")
        assertClose(volumeOf(body(ed)), 30000.0, tol = 1e-6, msg = "and forward again")
    }

    @Test
    fun deletingAParameterALawReadsTakesTheBodyAndLeavesTheFileLoadable() {
        val (ed, span) = spanRect()
        val sweepStep = assertNotNull(ed.doc.creatingStep(body(ed)), "the sweep has a step")
        val paramStep = assertNotNull(ed.doc.journal.firstOrNull { s -> s.createsScalars.any { it === span } }, "span has a step")
        val cascade = ed.doc.dependentSteps(setOf(paramStep))
        assertTrue(sweepStep in cascade, "a law's right side is a reference too: deleting span takes the body")
        ed.doc.journal.removeAll(cascade)
        val text = DocumentFormat.save(ed.doc)
        assertTrue(!text.contains("laws="), "no law left naming a value that has gone: $text")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "what is left round-trips")
    }

    @Test
    fun aFamilyWhoseSectionGoesEmptyAtEveryStationIsInvalidAndHeals() {
        val (ed, _) = spanRect()
        val h = ed.doc.scalars.first { it.name == "h" }
        assertTrue(ed.setParameter(h, 0.0), ed.statusHint)
        val why = assertNotNull(invalidity(body(ed)), "a zero-height section has no area anywhere along the run")
        println("invalid: $why")
        assertTrue(ed.setParameter(h, 10.0), ed.statusHint)
        assertNull(invalidity(body(ed)), "and it heals (OP-3)")
        assertClose(volumeOf(body(ed)), 15000.0, tol = 1e-6, msg = "to the body it was")
        assertManifold(meshOf(body(ed)), "healed")
    }
}
