package constructit

import constructit.editor.Dependencies
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.SvgDrawTarget
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **What a selection is built from, and what is built on it** (queue #18 item 1).
 *
 * The depth rule under test is the one `Dependencies` states: the *nearest element-bearing ancestors*, not
 * the literal input nodes (a fillet's arc consumes derived tangency nodes nothing displays) and not the
 * whole cone (which is the drawing). So the assertions are about the answers a user would give: a circle is
 * built from its centre and its radius point, a fillet from its two legs, a solid from the area it was
 * raised out of — and nothing reports a grandparent.
 */
class DependencyViewTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.selectAt(world: Vec2) {
        setTool(Tools.SELECT)
        click(world)
    }

    private fun Editor.svg(): String {
        val t = SvgDrawTarget()
        render(t)
        return t.svg()
    }

    /** The names of the inputs, with their roles — exactly what the inspector's *built from* row shows. */
    private fun builtFrom(ed: Editor): List<String> =
        ed.selectionInputs().map { (it.role?.let { r -> "$r " } ?: "") + ed.doc.nameOf(it.element) }

    private fun usedBy(ed: Editor): List<String> = ed.selectionDependents().map { ed.doc.nameOf(it) }

    @Test
    fun aCircleIsBuiltFromItsCentreAndItsRadiusPoint() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))

        val circle = ed.doc.elements.first { it.kind == ElementKind.CIRCLE }
        ed.selectElement(circle)
        // the two picks, in slot order, each named by the role the *tool* declares for that slot
        assertEquals(listOf("centre e1", "radius point e2"), builtFrom(ed), "the circle's own inputs, with roles")
        assertTrue(usedBy(ed).isEmpty(), "nothing is built on the circle yet; got ${usedBy(ed)}")

        // …and the relation is symmetric: the centre reports the circle as its dependent
        val centre = ed.doc.elements.first { it.id == "e1" || ed.doc.nameOf(it) == "e1" }
        ed.selectElement(centre)
        assertEquals(listOf(ed.doc.nameOf(circle)), usedBy(ed), "the centre is used by the circle")
        assertTrue(builtFrom(ed).isEmpty(), "a free point is built from nothing")
    }

    @Test
    fun aFilletIsBuiltFromItsTwoLegsAndNothingBehindThem() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.click(Vec2(0.0, 60.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(0.0, 60.0))
        val r = ed.doc.newParameter("r", constructit.units.Quantity.mm(12.0))
        ed.activeScalar = r
        ed.setTool(Tools.FILLET)
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(0.0, 30.0))

        val arc = ed.doc.elements.last { it.kind == ElementKind.ARC }
        ed.selectElement(arc)
        val inputs = builtFrom(ed)
        assertEquals(2, inputs.size, "a fillet reports its two legs and nothing else; got $inputs")
        assertTrue(inputs.all { it.startsWith("leg ") }, "both are named by the tool's slot word; got $inputs")
        // the *points* the legs are built on are one barrier further up, so they are deliberately absent —
        // that is the depth rule, and it is what keeps a highlight from becoming the whole drawing
        val legNames = inputs.map { it.removePrefix("leg ") }
        assertTrue(legNames.none { it == "e1" || it == "e2" || it == "e3" }, "the legs' own points are not inputs of the fillet; got $inputs")
    }

    @Test
    fun anExtrusionIsBuiltFromTheLegsOfTheAreaItWasRaisedFrom() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(-20.0, -10.0))
        ed.click(Vec2(20.0, 10.0))
        val depth = ed.doc.newParameter("depth", constructit.units.Quantity.mm(15.0))
        ed.activeScalar = depth
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(0.0, -10.0)) // the footprint's south side

        val solid = ed.doc.elements.first { it.kind == ElementKind.SOLID }
        ed.selectElement(solid)
        val inputs = ed.selectionInputs()
        // The rectangle is a **closed ortho path**, so its area is coerced from the four legs
        // (`Document.regionOf`) and the solid genuinely consumes all four — which is the honest answer and
        // the reason the rule reads the graph rather than the step. The step still supplies the *word*: only
        // the leg that was clicked is the declared "profile" pick.
        assertEquals(4, inputs.size, "the extrusion is built from every leg of the closed path; got ${builtFrom(ed)}")
        assertTrue(inputs.all { it.element.kind == ElementKind.SEGMENT }, "…all of them legs; got ${builtFrom(ed)}")
        assertEquals(listOf("profile"), inputs.mapNotNull { it.role }, "exactly the clicked leg carries Extrude's slot word")

        // and every leg knows about the solid
        ed.selectElement(inputs[0].element)
        assertTrue(usedBy(ed).contains(ed.doc.nameOf(solid)), "the profile is used by the solid; got ${usedBy(ed)}")
    }

    @Test
    fun theHighlightsAreDrawn() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))

        val plain = ed.svg()
        assertTrue(!plain.contains(INPUT_COLOUR), "with nothing selected there is nothing to highlight")

        // selecting the circle paints its two inputs; selecting a point paints its dependent
        ed.selectElement(ed.doc.elements.first { it.kind == ElementKind.CIRCLE })
        assertTrue(ed.svg().contains(INPUT_COLOUR), "the inputs of the selection are drawn in their own colour")
        ed.selectAt(Vec2(30.0, 0.0))
        assertTrue(ed.svg().contains(DEPENDENT_COLOUR), "…and the dependents of the selection in theirs")

        // the panel's hover is a *spotlight*: transient, decides nothing, and drawn over everything
        val circle = ed.doc.elements.first { it.kind == ElementKind.CIRCLE }
        assertTrue(ed.setSpotlight(circle), "pointing the spotlight is a change")
        assertTrue(ed.svg().contains(SPOTLIGHT_COLOUR), "the hovered element is highlighted")
        assertTrue(!ed.setSpotlight(circle), "pointing it at the same element again is not")
        ed.setSpotlight(null)
        assertTrue(!ed.svg().contains(SPOTLIGHT_COLOUR), "and it goes when the pointer leaves")
    }

    @Test
    fun aWeldedPointReportsItsMaster() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.JOIN)
        ed.click(Vec2(0.0, 0.0)) // keep this one
        ed.click(Vec2(40.0, 0.0)) // weld that one onto it

        val alias = ed.doc.elements.first { ed.doc.nameOf(it) == "e2" }
        // binding is an in-place re-point (OP-5), so the alias' node now *has* an input — and the walk finds
        // the master through it, with no special case for welding anywhere in the dependency code
        assertEquals(listOf("e1"), Dependencies.inputsOf(ed.doc, alias).map { ed.doc.nameOf(it.element) })
    }

    companion object {
        private const val INPUT_COLOUR = "#2ca02c"
        private const val DEPENDENT_COLOUR = "#bcbd22"
        private const val SPOTLIGHT_COLOUR = "#ff7f0e"
    }
}
