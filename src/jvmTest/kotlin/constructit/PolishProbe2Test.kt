package constructit

import constructit.editor.CreateMode
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.SceneRenderer
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Probes over the polish package: the dependency view on a MACRO INSTANCE (its chips must name the
 * instance's argument points, the one freedom it has), and the scale bar's rounding at extreme
 * zooms.
 */
class PolishProbe2Test {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    @Test
    fun anInstancesChipsNameItsArgumentPoints() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        ed.activeScalar = ed.doc.newParameter("r", 5.0.mm)
        ed.setTool(Tools.CIRCLE_R)
        ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(-20.0, -20.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(60.0, 20.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(60.0, 20.0)))
        val d = assertNotNull(ed.beginCreate(CreateMode.TOOL))
        d.name = "stud"
        assertTrue(ed.confirmCreate())
        val tool = ed.doc.macros.last().toolId
        ed.setTool(tool)
        ed.click(Vec2(100.0, 50.0))
        ed.click(Vec2(130.0, 50.0))

        // select the stamped circle: its inputs must include the two argument points, by script name
        val stamped = ed.doc.elements.last { it.kind == ElementKind.CIRCLE }
        ed.selectElement(stamped)
        val inputs = ed.selectionInputs()
        assertTrue(inputs.isNotEmpty(), "an instance's freedom is its arguments: $inputs")
        val names = inputs.map { ed.doc.nameOf(it.element) }
        val argNames =
            ed.doc.elements.filter { it.kind == ElementKind.POINT }
                .filter { (constructit.core.Evaluator().eval(it.ref.node) as? constructit.core.EvalResult.Ok)?.let { r -> (r.value as constructit.core.PointValue).p.x >= 100.0 } == true }
                .map { ed.doc.nameOf(it) }
        assertTrue(argNames.any { it in names }, "chips $names should include an argument point of $argNames")
    }

    @Test
    fun theScaleBarPicksRoundNumbersAtEveryZoom() {
        // ~100px target: at 4 px/mm -> 25mm -> nice 20 or 50; at 0.01 px/mm -> 10 m (issue #12: converted
        // upward, deliberately — this row asserted "10000 mm" until that decision was reversed); at
        // 100 px/mm -> 1mm. The centimetre rung (GitHub #16) is why the first row now reads in cm: the
        // length it picks is unchanged, the unit it is said in is the largest the number is still ≥ 1 in.
        for ((scale, acceptable) in listOf(
            4.0 to setOf("2 cm", "2.5 cm", "5 cm"),
            0.01 to setOf("10 m", "5 m", "20 m"),
            100.0 to setOf("1 mm", "2 mm", "0.5 mm"),
        )) {
            val label = SceneRenderer.scaleBarLabel(scale)
            assertTrue(label in acceptable, "scale $scale gave '$label', expected one of $acceptable")
        }
    }
}
