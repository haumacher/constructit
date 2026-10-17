package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.editor.DocumentFormat
import constructit.editor.ElementKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The orchestrator's probe of OP-32 slice 2: a file edited by hand far beyond what any click could have
 * survived — the L's notch moved to the other side and its height changed — keeps its picks by meaning, and
 * every feature either builds a closed body or is invalid with a reason; nothing is re-picked and nothing throws.
 */
class PicksAsMeaningsProbeTest {
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
    fun aHandEditedShapeKeepsEveryPickByMeaning() {
        val json = DocumentFormat.save(DocumentFormat.load(issue36))
        assertFalse(json.contains("\"clicks\""), "a v12 file states no click:\n$json")
        val edges = Regex("\"edge\":(\\d+)").findAll(json).map { it.groupValues[1] }.toList()
        // the notch's inner corner moves from x = -5.52 to x = 40 and the height doubles: same topology, other values
        val edited = json.replace("-5.521648428788623", "40").replace("\"20 mm\"", "\"40 mm\"")
        val doc = DocumentFormat.load(edited)
        val again = DocumentFormat.save(doc)
        assertEquals(edges, Regex("\"edge\":(\\d+)").findAll(again).map { it.groupValues[1] }.toList(), "the picks are the same edges")
        assertSameFile(DocumentFormat.save(DocumentFormat.load(again)), again, "the edited file is a fixed point")
        for (el in doc.elements.filter { it.kind == ElementKind.SOLID }) {
            when (val r = Evaluator().eval(el.ref.node)) {
                is EvalResult.Ok -> assertManifold((r.value as constructit.core.SolidValue).solid.mesh, "solid ${doc.nameOf(el)}")
                is EvalResult.Invalid -> assertTrue(r.why.render().isNotBlank(), "an invalid solid says why")
            }
        }
    }
}
