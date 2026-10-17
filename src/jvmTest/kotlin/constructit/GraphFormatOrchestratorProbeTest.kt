package constructit

import constructit.editor.DocumentFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/** The orchestrator's probe of OP-32 slice 1: the file must say what exists, and survive hand edits. */
class GraphFormatOrchestratorProbeTest {
    private val issue36 =
        """
constructit 6
orthostart -26.875,-32.375 -> e1
orthovertex -26.875,15.375 -> e2,e3
orthovertex 61.875,15.375 -> e4,e5
orthovertex 61.875,0.375 -> e6,e7
orthovertex -5.521648428788623,0.375 -> e8,e9
orthovertex -5.521648428788623,-32.375 -> e10,e11
orthoclose -> e12
param "h" = 20mm
tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
param "r" = 4mm
tool filletedge els=e13 clicks=-31.252365457721638,11.31587462139538 scalar="r" signs=13;-1;1;0;1 -> e14,e15
tool filletedge els=e14 clicks=-15.659687663642714,14.554138077719443 scalar="r" signs=2;-1;1;0;-1 -> e16
""".trimStart()

    @Test
    fun toggledVisibilityLeavesStateNotHistory() {
        val doc = DocumentFormat.load(issue36)
        val path = doc.elements.take(3)
        repeat(10) {
            doc.setElementsVisible(path, false)
            doc.setElementsVisible(path, true)
        }
        doc.setElementsVisible(path, false)
        val json = DocumentFormat.save(doc)
        val viewLines = json.lines().dropWhile { !it.contains("\"view\"") }
        println(json)
        assertTrue(viewLines.size <= 4, "the view states which elements are hidden, not 21 toggles:\n$json")
    }

    @Test
    fun aHandEditedParameterIsTheDrawing() {
        val json = DocumentFormat.save(DocumentFormat.load(issue36))
        val edited = json.replace("\"value\":\"4 mm\"", "\"value\":\"3 mm\"")
        val back = DocumentFormat.load(edited)
        assertEquals(edited, DocumentFormat.save(back), "the hand edit is a fixed point")
    }

    @Test
    fun aHandDeletedMiddleFeatureIsRefusedByName() {
        val json = DocumentFormat.save(DocumentFormat.load(issue36))
        val withoutFirstFillet = json.lines().filterNot { it.contains("\"creates\":[\"e14\",\"e15\"]") }.joinToString("\n")
        val e = assertFails { DocumentFormat.load(withoutFirstFillet) }
        assertTrue((e.message ?: "").contains("e14"), "the refusal names the missing element: ${e.message}")
    }
}
