package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Vec2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Probe review of the ortho-vertex anchoring (GitHub #23)** — the delivery composed with features it was not
 * written against: the undo/redo stack, a replay from file followed by a drag, and an extrusion over the
 * anchored loop whose volume must be exactly what the moved footprint says.
 */
class OrthoVertexAnchorProbeTest {
    private val loop =
        """
constructit 3
orthostart -68.625,23.375 -> e1
orthovertex -68.625,68.875 -> e2,e3
orthovertex 13.625,68.875 -> e4,e5
orthovertex 13.625,55.875 -> e6,e7
orthovertex -48.375,55.875 -> e8,e9
orthovertex -48.375,23.375 -> e10,e11
orthoclose -> e12
""".trimStart()

    private fun loaded(script: String = loop): Editor = Editor().also { it.replaceDocument(DocumentFormat.load(script)) }

    private fun el(
        ed: Editor,
        n: Int,
    ): Element = ed.doc.elements[n - 1]

    private fun pos(el: Element): Vec2 = ((Evaluator().eval(el.ref.node) as EvalResult.Ok).value as PointValue).p

    private fun closingLeg(ed: Editor): Double = (pos(el(ed, 10)) - pos(el(ed, 1))).length()

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(from + (to - from) * 0.5))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    /** Drag the vertical leg e3 (x = -68.625 at the start) sideways by [dx]. */
    private fun dragE3(
        ed: Editor,
        dx: Double,
    ) {
        val x = pos(el(ed, 1)).x
        ed.drag(Vec2(x, 46.0), Vec2(x + dx, 46.0))
    }

    /** Undo peels the drag first, then the anchoring; redo puts both back, and the leg holds again. */
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    @Test
    fun undoAndRedoLayerTheAnchoringUnderTheDrag() {
        val ed = loaded()
        // through the tool, so the anchoring is a checkpoint of its own (a direct doc call is not)
        ed.setTool(Tools.MAKE_RELATIVE)
        ed.click(pos(el(ed, 10)))
        ed.click(pos(el(ed, 1)))
        assertTrue("now follows" in (ed.statusHint), "anchored by gesture: ${ed.statusHint}")
        dragE3(ed, 10.0)
        assertClose(closingLeg(ed), 20.25, tol = 1e-6, msg = "anchored: the leg holds under the drag")
        assertClose(pos(el(ed, 1)).x, -58.625, tol = 1e-6, msg = "and the drag happened")

        assertTrue(ed.undo(), "undo the drag")
        assertClose(pos(el(ed, 1)).x, -68.625, tol = 1e-6, msg = "the drag is undone")
        assertClose(closingLeg(ed), 20.25, tol = 1e-6, msg = "still anchored, still 20.25")

        assertTrue(ed.undo(), "undo the anchoring")

        assertTrue(ed.redo(), "redo the anchoring")
        assertTrue(ed.redo(), "redo the drag")
        assertClose(pos(el(ed, 1)).x, -58.625, tol = 1e-6, msg = "the drag is back")
        assertClose(closingLeg(ed), 20.25, tol = 1e-6, msg = "and the anchoring is back with it")

        // back to the free vertex (a new edit truncates the redo stack, so this comes last)
        assertTrue(ed.undo(), "undo the drag again")
        assertTrue(ed.undo(), "undo the anchoring again")
        dragE3(ed, 10.0)
        assertTrue(abs(closingLeg(ed) - 20.25) > 5.0, "free again: the leg changes length as it did before (${closingLeg(ed)})")
    }

    /** A drawing loaded from file is anchored by the *replayed* step, not by a literal — so the drag holds there too. */
    @Test
    fun theReplayedAnchoringHoldsUnderADragAfterReload() {
        val ed = loaded()
        assertTrue(ed.doc.makeRelative(el(ed, 10), el(ed, 1)), ed.doc.note ?: "")
        val saved = DocumentFormat.save(ed.doc)
        val again = loaded(saved)
        assertEquals(saved, DocumentFormat.save(again.doc), "byte-equal round trip")
        dragE3(again, -7.0)
        assertClose(closingLeg(again), 20.25, tol = 1e-6, msg = "the reloaded drawing keeps the leg's length")
        assertClose(pos(el(again, 1)).x, -75.625, tol = 1e-6, msg = "and moved")
        val twice = DocumentFormat.save(again.doc)
        assertEquals(twice, DocumentFormat.save(loaded(twice).doc), "and the dragged drawing round-trips too")
    }

    /** An extrusion over the anchored loop: after the drag its volume is the moved footprint's area times the height, exactly. */
    @Test
    fun theExtrusionOverTheAnchoredLoopFollowsTheDragExactly() {
        val ed =
            loaded(
                loop +
                    """
param "h" = 20mm
tool extrude els=e11 clicks=-30,40 scalar="h" -> e13
""".trimStart(),
            )
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "the extrusion: ${ed.doc.note}")
        assertTrue(ed.doc.makeRelative(el(ed, 10), el(ed, 1)), ed.doc.note ?: "")
        dragE3(ed, 12.0)
        val corners = listOf(1, 2, 4, 6, 8, 10).map { pos(el(ed, it)) }
        var twice = 0.0
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            twice += a.x * b.y - b.x * a.y
        }
        val area = abs(twice) / 2

        @Suppress("UNCHECKED_CAST")
        val solid = Evaluator().solid(ed.doc.elements.first { it.kind == ElementKind.SOLID }.ref as SolidRef)
        assertManifold(solid.mesh, "the extrusion over the dragged, anchored loop")
        assertClose(Geom3.volume(solid.mesh), area * 20.0, tol = 1e-6, msg = "volume = footprint area x 20")
        assertClose(closingLeg(ed), 20.25, tol = 1e-6, msg = "the closing leg held")
    }
}
