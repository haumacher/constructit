package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.geom.Geom3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of OP-31 slice 5g** (the corner's slot count recorded): a version-7 file whose step
 * rounds a corner's rail, migrated, saved, and then given a fourth rounding on a far edge of the same dressing —
 * the rail step must build the same rail, so the body moves by exactly the new bevel's own wedge.
 */
class CornerSlotProbeTest {
    private val v7 = """constructit 7
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
tool chamferedge els=e13 clicks=-12.581664043342087,5.018353790754986 scalar="r" signs=2;-1;1;0;-1 -> e14,e15
tool chamferedge els=e14 clicks=-36.10499303384047,0.8875707537249014 scalar="r" signs=13;-1;1;0;1 -> e16
tool chamferedge els=e14 clicks=-6.480180051294639,24.048959979858537 scalar="r" signs=14;-1;1;0;1 -> e17
param "r2" = 2mm
tool filletedge els=e14 clicks=-33.91557367038956,-1.7134784580017737 scalar="r2" signs=20;-1;1;0;1 -> e18,e19
"""

    private fun load(text: String): Editor {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(text))
        return ed
    }

    @Suppress("UNCHECKED_CAST")
    private fun volumeOf(
        ed: Editor,
        name: String,
    ): Double {
        // the rail rounding's body is the last solid, whatever name the writer gives it beside its entry
        val el = ed.doc.elements.last { it.kind == ElementKind.SOLID }
        val r = Evaluator().eval(el.ref.node)
        assertTrue(r !is EvalResult.Invalid, "$name is valid: ${(r as? EvalResult.Invalid)?.reason}")
        val mesh = Evaluator().solid(el.ref as SolidRef).mesh
        assertManifold(mesh, name)
        return Geom3.volume(mesh)
    }

    @Test
    fun aRailStepSurvivesMigrationAndAFourthRoundingOnAFarEdge() {
        val ed = load(v7)
        assertTrue(ed.doc.loadNotes.isNotEmpty(), "a version-7 file is told once what moved")
        val before = volumeOf(ed, "e18")
        val saved = DocumentFormat.save(ed.doc)
        assertTrue(saved.startsWith("constructit ${DocumentFormat.VERSION}\n"), "written at the current version")
        assertTrue(saved.contains("slots="), "the dressing's body records its corner slots: $saved")
        val again = load(saved)
        assertTrue(again.doc.loadNotes.isEmpty(), "and nothing is said twice: ${again.doc.loadNotes}")
        assertEquals(saved, DocumentFormat.save(again.doc), "fixed point")
        assertClose(before, volumeOf(again, "e18"), 1e-9, "the migrated body is the one the old file built")
        // a fourth rounding on the dressing, far from every other: edge 16 (the top edge along y = 15.375, 88.75 mm,
        // touching no rounded edge), a 4 mm bevel — a plain triangular prism ending free in two square end faces,
        // so its figure is exact, and the rail step at the far corner must be untouched to float noise
        val lines = saved.lines().toMutableList()
        val body = lines.indexOfFirst { it.startsWith("tool chamferedge els=e14") && it.contains("signs=14;") }
        lines.add(body + 1, "tool chamferedge els=e14 clicks=17.5,15.4 scalar=\"r\" signs=16;-1;1;0;1 -> e20")
        val grown = load(lines.joinToString("\n"))
        val after = volumeOf(grown, "e18")
        val bevel = 16.0 / 2.0 * (61.875 + 26.875)
        assertClose(bevel, before - after, 1e-6 * before, "the fourth bevel takes exactly its own wedge and nothing else moves")
        val once = DocumentFormat.save(grown.doc)
        assertEquals(once, DocumentFormat.save(load(once).doc), "the grown file is a fixed point too")
    }
}
