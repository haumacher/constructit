package constructit

import constructit.core.CircleValue
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.dsl.valueOf
import constructit.editor.CreateMode
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.PointerButton
import constructit.editor.SvgDrawTarget
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Probes composing user macros (OP-6) with mechanisms their UI never saw: an instance's input
 * attached to a line, a dimension measuring across two instances, and an array of an instance's
 * geometry. An instance is ordinary derived geometry over ordinary input points, so all three must
 * come for free.
 */
class MacroProbeTest {
    private fun Editor.click(
        world: Vec2,
        additive: Boolean = false,
    ) {
        val s = camera.worldToScreen(world)
        pointerDown(s, PointerButton.PRIMARY, additive)
        pointerUp(s)
    }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    /** Two points + circle of parameter r at the midpoint, recorded as a one-point-input tool. */
    private fun toolOf(ed: Editor): String {
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.MIDPOINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        ed.activeScalar = ed.doc.newParameter("r", 5.0.mm)
        ed.setTool(Tools.CIRCLE_R)
        ed.click(Vec2(15.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(-20.0, -20.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(50.0, 20.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(50.0, 20.0)))
        val d = assertNotNull(ed.beginCreate(CreateMode.TOOL))
        d.name = "pin"
        assertTrue(ed.confirmCreate())
        return ed.doc.macros.last().toolId
    }

    private fun instanceCircles(ed: Editor) =
        ed.doc.elements.filter { it.kind == ElementKind.CIRCLE }.drop(1).map { (Evaluator().valueOf(it.ref) as CircleValue).circle }

    /** An instance's input point is a free point — the attach magnet must take it onto a line. */
    @Test
    fun anInstanceInputAttachesToALineAndSlidesAlongIt() {
        val ed = Editor()
        val tool = toolOf(ed)
        ed.setTool(Tools.LINE)
        ed.click(Vec2(100.0, -50.0))
        ed.click(Vec2(100.0, 50.0)) // a vertical line at x=100
        ed.setTool(tool)
        ed.click(Vec2(200.0, 0.0)) // anchor input
        ed.click(Vec2(230.0, 0.0)) // second input
        val anchor = ed.doc.elements.last { it.kind == ElementKind.POINT && kotlin.math.abs((Evaluator().valueOf(it.ref) as PointValue).p.x - 200.0) < 1e-6 }

        // drag the instance's anchor onto the line: the magnet attaches it (2 -> 1 DOF)
        ed.drag(Vec2(200.0, 0.0), Vec2(100.0, 0.0))
        assertClose((Evaluator().valueOf(anchor.ref) as PointValue).p.x, 100.0, msg = "the input sits on the line")
        // and the instance's derived circle followed its anchor
        val c = instanceCircles(ed).single()
        assertClose(c.center.x, (100.0 + 230.0) / 2.0, msg = "the stamped circle re-derived from the attached input")
    }

    /** A dimension between two instances' inputs, live through a definition edit. */
    @Test
    fun aDimensionAcrossTwoInstancesFollowsTheDefinition() {
        val ed = Editor()
        val tool = toolOf(ed)
        ed.setTool(tool)
        ed.click(Vec2(100.0, 0.0))
        ed.click(Vec2(130.0, 0.0))
        ed.setTool(tool)
        ed.click(Vec2(100.0, 80.0))
        ed.click(Vec2(130.0, 80.0))

        ed.setTool(Tools.DIM_LINEAR)
        ed.click(Vec2(100.0, 0.0))
        ed.click(Vec2(100.0, 80.0))
        ed.click(Vec2(80.0, 40.0)) // where the dimension line sits
        val svg = SvgDrawTarget().also { ed.render(it) }.svg()
        assertTrue(svg.contains(">80 mm<"), "the two anchors are 80 apart")

        // retype the captured radius parameter: both stamped circles follow (OP-6 propagation)
        val r = ed.doc.scalars.first { it.name == "r" }
        ed.doc.setParameter(r, 9.0.mm)
        val radii = instanceCircles(ed).map { it.radius }
        assertEquals(2, radii.size)
        radii.forEach { assertClose(it, 9.0, msg = "the captured default propagates to every instance") }
    }

    /** An instance's derived circle is ordinary geometry: a circular array must copy it. */
    @Test
    fun anInstanceCircleCanBeArrayed() {
        val ed = Editor()
        val tool = toolOf(ed)
        ed.setTool(tool)
        ed.click(Vec2(100.0, 0.0))
        ed.click(Vec2(130.0, 0.0)) // stamped circle centred (115, 0), r 5
        ed.setTool(Tools.POINT)
        ed.click(Vec2(115.0, 40.0))
        ed.count = 3
        ed.setTool(Tools.ARRAY_CIRCULAR)
        ed.click(Vec2(115.0, 5.0)) // the stamped circle's outline
        ed.click(Vec2(115.0, 40.0)) // the centre
        assertEquals(1 + 1 + 2, ed.doc.elements.count { it.kind == ElementKind.CIRCLE }, "definition + instance + 2 copies")

        val once = constructit.editor.DocumentFormat.save(ed.doc)
        assertEquals(once, constructit.editor.DocumentFormat.save(constructit.editor.DocumentFormat.load(once)), "instance + array replays")
    }
}
