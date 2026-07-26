package constructit

import constructit.editor.DocumentFormat
import constructit.editor.Editor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression (user-reported): deleting the trailing segment e11 must remove exactly that step —
 * at e39e97a it also silently dropped the THIRD fillet during the delete's replay and lost the
 * on-curve point's dofs. A delete is a journal filter plus a replay; nothing else may change.
 */
class DeleteFilletProbeTest {
    private val file =
        """
constructit 1
point -67,38.75 -> e1
point -73.75,-63.5 -> e2
tool segment pts=e1,e2 clicks=-33.5,68;-38,-34.5 -> e3
point 52.75,12.5 -> e4
tool segment pts=e2,e4 clicks=-36.75,-34.75;69,56.75 -> e5
tool segment pts=e1,e4 clicks=-33,68.75;69,55.5 -> e6
param "r" = 10mm
tool fillet els=e3,e5 clicks=-37,0.75;-17,-17 scalar="r" -> e7
tool fillet els=e6,e5 clicks=42,59.75;53.25,41.5 scalar="r" -> e8
tool fillet els=e6,e3 clicks=-12.25,65.25;-34.5,39.5 scalar="r" -> e9
pointoncurve e6 -41.38481635657432,33.13498062096097 dofs=-47.519917395325116mm -> e10
tool segment pts=e10,e4 clicks=-41.25,33.75;53,12.75 -> e11
""".trimStart()

    @Test
    fun deletingTheTrailingSegmentRemovesExactlyThatStep() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(file))
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the file round-trips before anything else")

        // the user's gesture: the ADDITIONAL SEGMENT (last segment element), not a runtime id
        val seg = ed.doc.elements.last { it.kind == constructit.editor.ElementKind.SEGMENT }
        ed.selectElement(seg)
        assertTrue(ed.deleteSelection(), "delete the additional segment: ${ed.statusHint}")

        val after = DocumentFormat.save(ed.doc)
        assertEquals(3, Regex("tool fillet").findAll(after).count(), "all three fillets survive:\n$after")
        assertTrue(after.contains("dofs="), "the on-curve point keeps its parameter:\n$after")
        assertEquals(after, DocumentFormat.save(DocumentFormat.load(after)), "and the result round-trips")
        // the surviving script is the original minus exactly the last step
        assertEquals(once.trim().lines().dropLast(1), after.trim().lines(), "one step gone, nothing else changed")
    }
}
