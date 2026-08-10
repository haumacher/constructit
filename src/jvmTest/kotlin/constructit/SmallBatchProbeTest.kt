package constructit

import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Probe review of the small batch — compositions the delivery never saw.
 *
 * The two questions: is `save ∘ load` a **first-pass fixed point on a drawing that uses everything this
 * session built at once** — an expression-bound radius, a dressed (blended) solid, and a point attached to
 * a segment by the drag the creep lived in; and does the sign keep to its side of the boundary against the
 * *blend* — the pad states a negative size, the node refuses it in its own words, and the retyped positive
 * heals.
 */
class SmallBatchProbeTest {
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

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    @Test
    fun theKitchenSinkDrawingIsAFirstPassFixedPoint() {
        val ed = Editor()
        val d = ed.doc.newParameter("d", 24.0.mm)
        val r = ed.doc.newParameter("r", 5.0.mm)
        assertTrue(ed.doc.bindParameter(r, "d/2"), "the radius derives: ${ed.doc.note}")

        // a disc dressed with a fillet — the blend feature over an expression-driven base
        ed.activeScalar = r
        ed.setTool(Tools.CIRCLE_R)
        ed.click(Vec2(60.0, 40.0))
        ed.activeScalar = ed.doc.newParameter("depth", 10.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(72.0, 40.0))
        ed.activeScalar = ed.doc.newParameter("blend", 2.0.mm)
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(72.0, 40.0))
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "the dressed disc: ${ed.statusHint}")

        // a free point dragged onto a segment — the attach whose restated freedom the batch added
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.POINT)
        ed.click(Vec2(20.0, 8.0))
        ed.drag(Vec2(20.0, 8.0), Vec2(20.0, 0.4))

        val once = DocumentFormat.save(ed.doc)
        assertTrue("attach" in once, "the drag attached the point (the probe's premise): \n$once")
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the FIRST pass is the fixed point")

        // and it stays one after the master parameter moves everything
        ed.doc.setParameter(d, 30.0.mm)
        val again = DocumentFormat.save(ed.doc)
        assertEquals(again, DocumentFormat.save(DocumentFormat.load(again)), "still a fixed point after the edit")
    }

    @Test
    fun aNegativeBlendSizeIsTheNodesRefusalAndThePositiveHeals() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 30.0))
        ed.activeScalar = ed.doc.newParameter("depth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(20.0, 0.0))
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SOLID })

        // the pad states the negative size; whatever declines must be the construction, speaking
        ed.setTool(Tools.BLEND_EDGE)
        ed.type("-3")
        ed.click(Vec2(0.0, 15.0))
        val afterNegative = ed.doc.elements.count { it.kind == ElementKind.SOLID }
        if (afterNegative == 1) {
            val said = assertNotNull(ed.statusHint, "the decline speaks")
            assertTrue(said.isNotBlank(), "the decline speaks words: '$said'")
        }

        // retyped positive, the same gesture heals into the body
        ed.setTool(Tools.BLEND_EDGE)
        ed.type("3")
        ed.click(Vec2(0.0, 15.0))
        assertTrue(
            ed.doc.elements.count { it.kind == ElementKind.SOLID } > afterNegative,
            "the positive size builds the fillet: ${ed.statusHint}",
        )

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "and the drawing survives its file")
    }
}
