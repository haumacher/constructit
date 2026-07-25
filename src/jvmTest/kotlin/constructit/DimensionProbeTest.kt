package constructit

import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.PointerButton
import constructit.editor.SvgDrawTarget
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.Quantity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Probes composing dimensions with features their implementation never saw: a dimension whose
 * measured points ride a placed group's frame (OP-16), and a radial dimension on a circle whose
 * radius is a live parameter (OP-7). An annotation is ordinary geometry over ordinary measurement
 * nodes, so every earlier mechanism must keep working underneath it unchanged.
 */
class DimensionProbeTest {
    private fun Editor.click(
        world: Vec2,
        additive: Boolean = false,
    ) {
        val s = camera.worldToScreen(world)
        pointerDown(s, PointerButton.PRIMARY, additive)
        pointerUp(s)
    }

    private fun svg(ed: Editor): String {
        val t = SvgDrawTarget()
        ed.render(t)
        return t.svg()
    }

    @Test
    fun aDimensionInsideAPlacedGroupSurvivesRotationWithItsValueIntact() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-50.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.DIM_LINEAR)
        ed.click(Vec2(-50.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.click(Vec2(0.0, 20.0)) // where the dimension line sits
        assertTrue(svg(ed).contains(">100 mm<"), "the dimension reads before placing")

        // group everything (dimension included) and place it
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(-120.0, -80.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(120.0, 80.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(120.0, 80.0)))
        val g = ed.groupSelection("dim")!!
        assertTrue(ed.placeGroup(g), "a group containing an annotation must still place")

        // rotate the frame a quarter turn via its typed field (OP-13)
        ed.click(Vec2(-50.0, 0.0))
        assertEquals(listOf("x", "y", "angle"), ed.selectionFields().map { it.label })
        assertTrue(ed.writeSelectionField(2, 90.0))

        val pts = ed.doc.elements.filter { it.kind == ElementKind.POINT }
        val a = (Evaluator().valueOf(pts[0].ref) as PointValue).p
        val b = (Evaluator().valueOf(pts[1].ref) as PointValue).p
        assertClose(a.x, 0.0, msg = "the span is vertical after the quarter turn")
        assertClose(b.x, 0.0)
        assertClose(a.y, -50.0)
        assertClose(b.y, 50.0)
        assertTrue(svg(ed).contains(">100 mm<"), "rotation changes the drawing, never the measured value")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "dimension + place round-trips")
    }

    @Test
    fun aRadialDimensionFollowsItsWiredRadiusParameter() {
        val ed = Editor()
        val r = ed.doc.newParameter("r", Quantity.mm(30.0))
        ed.activeScalar = r
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.CIRCLE_R)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.DIM_RADIAL)
        ed.click(Vec2(30.0, 0.0)) // the circle itself
        ed.click(Vec2(55.0, 0.0)) // leader out to the right
        assertTrue(svg(ed).contains("R 30 mm"), "got: ${svg(ed).substringAfter("<text").take(120)}")

        ed.doc.setParameter(r, Quantity.mm(45.0))
        assertTrue(svg(ed).contains("R 45 mm"), "editing the parameter re-labels the dimension")
    }
}
