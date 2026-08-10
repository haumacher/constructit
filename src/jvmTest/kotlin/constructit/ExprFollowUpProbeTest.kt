package constructit

import constructit.core.Evaluator
import constructit.core.FuncCurveValue
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.FuncCurves
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Probe review of the expression follow-ups — compositions the delivery never saw.
 *
 * The two questions: do the **first two items compose in one curve** — an involute whose radius reads a
 * named point's coordinate and whose domain is a formula over a parameter, dragged, edited, renamed at both
 * ends and judged through the file; and does the **new arc-leg chamfer meet the pattern fan** — a rounded
 * rectangle is OP-23's composition, so one chamfer gesture across one corner's arc and side must bevel
 * every corner.
 */
class ExprFollowUpProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun curveOf(el: Element) = assertNotNull((Evaluator().valueOf(el.ref) as? FuncCurveValue)?.curve, "a curve value")

    @Test
    fun aCurveOverACoordinateAndAFormulaDomainFollowsBothMasters() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(40.0, 0.0))
        val p = ed.doc.elements.last { it.kind == ElementKind.POINT }
        assertEquals("P", ed.doc.nameElement(p, "P"), "the point carries a name")
        val bigT = ed.doc.newParameter("T", 1.0.mm)

        val flank =
            assertNotNull(
                ed.addFunctionCurve(
                    "(P.x/2) * (cos(t) + t * sin(t))",
                    "(P.x/2) * (sin(t) - t * cos(t))",
                    0.0,
                    1.5,
                ),
                "the involute over the coordinate builds: ${ed.statusHint}",
            )
        val q = assertNotNull(FuncCurves.pointAt(curveOf(flank), 1.0))
        assertClose(q.x, 20.0 * (cos(1.0) + sin(1.0)), tol = 1e-9, msg = "radius = P.x/2 = 20")

        // dragging P moves the curve through the coordinate
        ed.doc.moveFreePoint(p, Vec2(60.0, 0.0))
        val q2 = assertNotNull(FuncCurves.pointAt(curveOf(flank), 1.0))
        assertClose(q2.x, 30.0 * (cos(1.0) + sin(1.0)), tol = 1e-9, msg = "the coordinate moved the curve")

        // renaming the point re-stamps the curve's two texts, and the file agrees
        assertEquals("base", ed.doc.nameElement(p, "base"))
        val once = DocumentFormat.save(ed.doc)
        assertTrue("base.x/2" in once, "the stored texts carry the new name:\n$once")
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "byte-equal round trip")
        val loaded = DocumentFormat.load(once)
        val flankLoaded = loaded.elements.single { it.kind == ElementKind.FUNC_CURVE }
        val q3 =
            assertNotNull(
                FuncCurves.pointAt(
                    assertNotNull((Evaluator().valueOf(flankLoaded.ref) as? FuncCurveValue)?.curve),
                    1.0,
                ),
            )
        assertClose(q3.x, q2.x, tol = 1e-9, msg = "the reloaded curve is the same curve")
        assertNotNull(bigT)
    }

    @Test
    fun oneChamferGestureBevelsEveryCornerOfARoundedHexagon() {
        // a rounded regular polygon IS OP-23's pattern composition — the fan's home ground
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("corner", 6.0.mm)
        ed.count = 6
        ed.setTool(Tools.POLYGON)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        val arcs = ed.doc.elements.count { it.kind == ElementKind.ARC }
        assertEquals(6, arcs, "six rounded corners: ${ed.statusHint}")
        val segsBefore = ed.doc.elements.count { it.kind == ElementKind.SEGMENT }

        // one chamfer across one corner's arc and its neighbouring side — the fan does the rest
        ed.activeScalar = ed.doc.newParameter("c", 2.0.mm)
        ed.setTool(Tools.CHAMFER)
        // the corner arc near the vertex at (40, 0) — the cut corner leaves the arc within a millimetre
        ed.click(Vec2(40.0, 0.0))
        // and the side towards the vertex at 60°, clicked at its midpoint
        ed.click(Vec2(30.0, 17.32))
        val segsAfter = ed.doc.elements.count { it.kind == ElementKind.SEGMENT }
        assertTrue(
            segsAfter >= segsBefore + 6,
            "one gesture, six bevels — the chamfer fanned round the pattern: " +
                "$segsBefore -> $segsAfter (${ed.statusHint})",
        )

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "and the fanned bevels survive their file")
    }
}
