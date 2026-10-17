package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The orchestrator's probe of OP-32 slice 3 on plain 2D construction the delivery never exercised: a chain of
 * midpoints whose inputs are replaced, rerouted and closed into a circle.
 */
class RefactorProbeTest {
    private val script =
        """
constructit ${DocumentFormat.JOURNAL_VERSION}
point 0,0 -> e1
point 10,0 -> e2
point 0,10 -> e3
tool midpoint pts=e1,e2 clicks=0,0;10,0 -> e4
tool midpoint pts=e4,e2 clicks=5,0;10,0 -> e5
tool circle pts=e4,e3 clicks=5,0;0,10 -> e6
""".trimStart()

    private fun el(
        doc: Document,
        name: String,
    ): Element = assertNotNull(doc.elements.firstOrNull { doc.nameOf(it) == name }, name)

    private fun at(
        doc: Document,
        name: String,
    ): Pair<Double, Double> = ((Evaluator().eval(el(doc, name).ref.node) as EvalResult.Ok).value as PointValue).p.let { it.x to it.y }

    @Test
    fun replacingAnInputMovesEverythingDownstreamAndUndoesInOneStep() {
        val ed = Editor(DocumentFormat.load(script))
        ed.checkpoint()
        val before = DocumentFormat.save(ed.doc)
        assertTrue(ed.replaceInput(el(ed.doc, "e4"), 1, el(ed.doc, "e3")), "the midpoint's second input becomes e3: ${ed.statusHint}")
        assertEquals(0.0 to 5.0, at(ed.doc, "e4"), "the midpoint of e1 and e3")
        assertEquals(5.0 to 2.5, at(ed.doc, "e5"), "and the midpoint built on it follows")
        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "the edited file is a fixed point")
        assertTrue(ed.undo())
        assertEquals(before, DocumentFormat.save(ed.doc), "one undo restores the file")
    }

    @Test
    fun aReplacementFromDownstreamIsRefusedAndChangesNothing() {
        val ed = Editor(DocumentFormat.load(script))
        ed.checkpoint()
        val before = DocumentFormat.save(ed.doc)
        assertFalse(ed.replaceInput(el(ed.doc, "e4"), 0, el(ed.doc, "e5")), "e5 is built from e4: a circle")
        assertTrue(ed.statusHint.isNotBlank(), "the refusal speaks")
        assertEquals(before, DocumentFormat.save(ed.doc), "nothing changed")
    }

    @Test
    fun deletingAMidpointReroutesItsReadersOntoItsFirstPointInput() {
        val ed = Editor(DocumentFormat.load(script))
        ed.checkpoint()
        ed.selectElement(el(ed.doc, "e4"))
        assertTrue(ed.deleteSelectionRerouting(), "delete keeping dependents: ${ed.statusHint}")
        assertFalse(ed.doc.elements.any { ed.doc.nameOf(it) == "e4" }, "e4 is gone")
        assertEquals(5.0 to 0.0, at(ed.doc, "e5"), "the second midpoint now reads e1 and e2")
        assertNotNull(ed.doc.elements.firstOrNull { ed.doc.nameOf(it) == "e6" }, "the circle survives, centred on e1")
        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "the edited file is a fixed point")
    }
}
