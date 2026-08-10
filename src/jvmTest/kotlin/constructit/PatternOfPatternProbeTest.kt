package constructit

import constructit.core.ArcValue
import constructit.core.Evaluator
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Probe review of the #18 package — the composition no package saw: a **nested ride whose corner radius is
 * an expression**. A ring of rounded squares, every corner of every copy driven by `d/10`, must re-round on
 * one edit of `d` — n×m corners through two features that never met — and the drawing must survive its file.
 */
class PatternOfPatternProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun arcRadii(ed: Editor): List<Double> =
        ed.doc.elements.filter { it.kind == ElementKind.ARC }.map {
            (Evaluator().valueOf(it.ref) as ArcValue).arc.radius
        }

    @Test
    fun aFormulaDrivenCornerReRoundsEveryCopyOfEveryCell() {
        val ed = Editor()
        val d = ed.doc.newParameter("d", 80.0.mm)
        val corner = ed.doc.newParameter("corner", 1.0.mm)
        assertTrue(ed.doc.bindParameter(corner, "d/10"), "the corner derives from d: ${ed.doc.note}")

        // the ring: six members about the origin
        ed.count = 6
        ed.setTool(Tools.PATTERN_CIRCULAR)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))

        // the ride: a rounded square per member, centre the member, vertex the next member round
        ed.activeScalar = corner
        ed.count = 4
        ed.setTool(Tools.POLYGON)
        ed.click(Vec2(100.0, 0.0))
        ed.click(Vec2(100.0 * cos(Math.PI / 3), 100.0 * sin(Math.PI / 3)))

        val radii = arcRadii(ed)
        assertEquals(24, radii.size, "six copies of four rounded corners: ${ed.statusHint}")
        assertTrue(radii.all { kotlin.math.abs(it - 8.0) < 1e-9 }, "every corner at d/10 = 8: $radii")

        // one edit of the master re-rounds all twenty-four corners through the binding
        ed.doc.setParameter(d, 40.0.mm)
        val radii2 = arcRadii(ed)
        assertEquals(24, radii2.size)
        assertTrue(radii2.all { kotlin.math.abs(it - 4.0) < 1e-9 }, "every corner followed d: $radii2")

        // and the whole story survives its file
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "byte-equal round trip")
        val loaded = DocumentFormat.load(once)
        val loadedRadii =
            loaded.elements.filter { it.kind == ElementKind.ARC }.map {
                (Evaluator().valueOf(it.ref) as ArcValue).arc.radius
            }
        assertEquals(24, loadedRadii.size, "the reloaded ring keeps every corner")
        assertTrue(loadedRadii.all { kotlin.math.abs(it - 4.0) < 1e-9 }, "at the derived radius")
    }
}
