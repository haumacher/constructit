package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The reviewer's probe for the cross-space boolean gesture (session 55): what the package's own tests never
 * composed.
 *
 * - **Subtract's operand order is semantic, and it must survive the space switch**: "keep the column, remove
 *   the box" and its reverse are different bodies, so the pick kept across `setActiveSpace` has to keep its
 *   *position* in the gesture, not merely its identity — asserted by driving both orders and by the file
 *   naming the operands in pick order.
 * - **The three operations agree with each other**: |A| + |B| = |A∪B| + |A∩B| and |A∖B| = |A| − |A∩B|, the
 *   inclusion–exclusion identities, each side measured off a manifold mesh the general engine produced. No
 *   single operation's test can catch an engine that is consistently wrong; the identities can.
 * - **Redo rebuilds the union**: undo across the document rebuild, then redo, and the fused body is back.
 */
class BooleanCrossSpaceProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun volumeOf(el: Element): Double = Geom3.volume(Evaluator().solid(el.ref as SolidRef).mesh)

    /**
     * A plan box and a cross-axis box on a vertical datum, overlapping: the plan slab spans z 0..30 over
     * (−10..30, −10..10); the datum's box, drawn on the plane through y = 0, is extruded 40 towards +y from
     * z 5..25 over x 5..25 — so the overlap is exactly (5..25, 0..10, 5..25), volume 20 · 10 · 20.
     */
    private fun twoBoxes(): Editor {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        val line = ed.doc.elements.last { it.kind == ElementKind.LINE }
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(-10.0, -10.0))
        ed.click(Vec2(30.0, 10.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("30")
        ed.click(Vec2(10.0, 10.0))
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SOLID }, ed.statusHint)

        assertNotNull(ed.doc.createDatumSpace(line, null), "the datum stands on the line")
        // in the datum's (u, v): u along the line (world x), v up out of the plan (world z)
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(5.0, 5.0))
        ed.click(Vec2(25.0, 25.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("40")
        ed.click(Vec2(15.0, 5.0))
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.SOLID }, ed.statusHint)
        return ed
    }

    private fun solids(ed: Editor) = ed.doc.elements.filter { it.kind == ElementKind.SOLID }

    /** The cross-space two-click gesture for [tool], first operand picked in the datum, second in the plan. */
    private fun crossSpaceBoolean(
        ed: Editor,
        tool: String,
        firstDatum: Boolean,
    ): Element {
        val before = solids(ed).size
        ed.setTool(tool)
        if (firstDatum) {
            ed.click(Vec2(15.0, 5.0))
            ed.setActiveSpace(Document_PLAN)
            ed.click(Vec2(10.0, 10.0))
        } else {
            ed.click(Vec2(10.0, 10.0))
            val datum = ed.doc.spaces.last { it.name != Document_PLAN }.name
            ed.setActiveSpace(datum)
            ed.click(Vec2(15.0, 5.0))
        }
        val out = solids(ed)
        assertEquals(before + 1, out.size, "the $tool built: ${ed.statusHint}")
        return out.last()
    }

    @Test
    fun subtractKeepsItsOperandOrderAcrossTheSpaceSwitch() {
        // keep the plan slab, remove the datum box — first click in the plan, second across the switch
        val a = twoBoxes()
        a.setActiveSpace(Document_PLAN)
        val keepSlab = crossSpaceBoolean(a, Tools.SUBTRACT, firstDatum = false)
        val slabMinusBox = volumeOf(keepSlab)
        assertManifold(Evaluator().solid(keepSlab.ref as SolidRef).mesh, "slab minus box")

        // ...and the reverse order, starting in the datum: keep the box, remove the slab
        val b = twoBoxes()
        val datum = b.doc.spaces.last { it.name != Document_PLAN }.name
        b.setActiveSpace(datum)
        val keepBox = crossSpaceBoolean(b, Tools.SUBTRACT, firstDatum = true)
        val boxMinusSlab = volumeOf(keepBox)
        assertManifold(Evaluator().solid(keepBox.ref as SolidRef).mesh, "box minus slab")

        // the two orders are two different bodies, each exactly its operand less the shared block
        val slab = 40.0 * 20.0 * 30.0
        val box = 20.0 * 20.0 * 40.0
        val shared = 20.0 * 10.0 * 20.0
        assertClose(slabMinusBox, slab - shared, slab * 1e-6, "slab minus box is the slab less the overlap")
        assertClose(boxMinusSlab, box - shared, box * 1e-6, "box minus slab is the box less the overlap")

        // the file names the operands in pick order, which is what makes the order replayable
        val text = DocumentFormat.save(a.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save → load → save is byte-equal")
        val step = text.lines().last { it.startsWith("tool subtract") }
        val slabName = a.doc.nameOf(solids(a).first())
        assertTrue(step.contains("els=$slabName,"), "the kept solid is named first: $step")
    }

    /** |A| + |B| = |A∪B| + |A∩B| — all four bodies from the general engine, all manifold. */
    @Test
    fun unionAndIntersectionAgreeWithTheirOperands() {
        val u = twoBoxes()
        u.setActiveSpace(Document_PLAN)
        val union = crossSpaceBoolean(u, Tools.UNION, firstDatum = false)
        assertManifold(Evaluator().solid(union.ref as SolidRef).mesh, "the union")

        val i = twoBoxes()
        i.setActiveSpace(Document_PLAN)
        val inter = crossSpaceBoolean(i, Tools.INTERSECT_SOLIDS, firstDatum = false)
        assertManifold(Evaluator().solid(inter.ref as SolidRef).mesh, "the intersection")

        val slab = 40.0 * 20.0 * 30.0
        val box = 20.0 * 20.0 * 40.0
        val shared = 20.0 * 10.0 * 20.0
        assertClose(volumeOf(inter), shared, shared * 1e-6, "the intersection is the shared block")
        assertClose(volumeOf(union), slab + box - shared, (slab + box) * 1e-6, "and the union is inclusion–exclusion")
    }

    /** Undo across the rebuild, then redo: the fused body is back, riding the same step. */
    @Test
    fun redoRebuildsTheCrossSpaceUnion() {
        val ed = twoBoxes()
        ed.setActiveSpace(Document_PLAN)
        crossSpaceBoolean(ed, Tools.UNION, firstDatum = false)
        assertEquals(3, solids(ed).size)
        assertTrue(ed.undo(), "undo the union")
        assertEquals(2, solids(ed).size, "the operands stand alone again")
        assertTrue(ed.redo(), "redo it")
        val back = solids(ed)
        assertEquals(3, back.size, "the fused body is back")
        val r = Evaluator().eval(back.last().ref.node)
        assertTrue(r is EvalResult.Ok, "and it evaluates: ${(r as? EvalResult.Invalid)?.reason}")
        assertManifold(Evaluator().solid(back.last().ref as SolidRef).mesh, "the redone union")
    }

    companion object {
        private const val Document_PLAN = "plan"
    }
}
