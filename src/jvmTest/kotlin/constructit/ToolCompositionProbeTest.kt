package constructit

import constructit.core.CircleValue
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.core.SegmentValue
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Probes composing the new tools with mechanisms they were not written against: an array of an
 * array's copy, a point whose coordinate is a *measurement* (OP-4 driving a scalar slot), and a
 * rectangle corner welded onto outside geometry. A tool builds ordinary nodes, so every existing
 * mechanism must keep working on what it built.
 */
class ToolCompositionProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
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

    private fun circles(ed: Editor): List<constructit.geom.Circle> {
        val ev = Evaluator()
        return ed.doc.elements.filter { it.kind == ElementKind.CIRCLE }.mapNotNull { (ev.valueOf(it.ref) as? CircleValue)?.circle }
    }

    /** An array's copy is an ordinary element, so it must itself be arrayable. */
    @Test
    fun anArrayOfAnArrayCopyComposes() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(20.0, 0.0))
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(5.0, 0.0)) // r = 5 at origin
        ed.count = 3
        ed.setTool(Tools.ARRAY_LINEAR)
        ed.click(Vec2(0.0, 5.0)) // the circle, picked on its outline (its centre is a point and would win)
        // the step vector from two points of its own — reusing the centre would couple v to the drag below
        ed.click(Vec2(0.0, -30.0))
        ed.click(Vec2(20.0, -30.0))
        assertEquals(3, circles(ed).size)

        // now circular-array the LAST copy (at x=40) about a centre above it
        ed.setTool(Tools.POINT)
        ed.click(Vec2(40.0, 30.0))
        ed.count = 4
        ed.setTool(Tools.ARRAY_CIRCULAR)
        ed.click(Vec2(40.0, 5.0)) // the second linear copy, on its outline
        ed.click(Vec2(40.0, 30.0)) // the centre
        assertEquals(6, circles(ed).size, "3 linear + 3 more spaced round the centre")

        // the chain stays live end to end: dragging the ORIGINAL's centre moves the nested copies
        ed.drag(Vec2(0.0, 0.0), Vec2(0.0, 10.0))
        val centers = circles(ed).map { it.center }.sortedBy { it.x }
        assertClose(centers.first().y, 10.0, msg = "the original moved")
        // the arrayed copy sits at (40,10); its quarter-turn images about (40,30) are (60,30)/(40,50)/(20,30)
        assertClose(centers.last().x, 60.0, msg = "the nested copy derives through both arrays")
        assertClose(centers.last().y, 30.0)

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "nested arrays replay")
    }

    /** OP-4: a measurement is a scalar like any other, so it can drive a scalar slot. */
    @Test
    fun aMeasuredDistanceCanDriveAPointCoordinate() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.DISTANCE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0)) // dist = 40
        val dist = ed.doc.scalars.first { !it.editable }
        val y = ed.doc.newParameter("h", constructit.units.Quantity.mm(25.0))

        ed.activeScalar = dist // x <- the measurement
        ed.activeScalar = y // y <- the parameter... but picks are consumed in pick order: x then y
        ed.setTool(Tools.POINT_XY)
        ed.click(Vec2(999.0, 999.0)) // the click only says "now"

        fun derived(): Vec2 {
            val pts = ed.doc.elements.filter { it.kind == ElementKind.POINT || it.kind == ElementKind.DERIVED_POINT }
            return (Evaluator().valueOf(pts.last().ref) as PointValue).p
        }
        assertClose(derived().x, 40.0, msg = "x is the measured distance")
        assertClose(derived().y, 25.0)

        // stretch the measured span: the derived point follows the measurement (driving XOR driven)
        ed.drag(Vec2(40.0, 0.0), Vec2(70.0, 0.0))
        assertClose(derived().x, 70.0, msg = "the measurement drives the coordinate live")
    }

    /** A rectangle's free diagonal corner welds like any free point; the shape stays rectangular. */
    @Test
    fun aRectangleCornerWeldedToAnOutsidePointStaysARectangle() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(80.0, 60.0)) // the outside anchor
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(50.0, 30.0))
        // weld the rectangle's far corner onto the anchor: drag it there, the magnet takes over
        ed.drag(Vec2(50.0, 30.0), Vec2(80.0, 60.0))

        val segs = ed.doc.elements.filter { it.kind == ElementKind.SEGMENT }.map { (Evaluator().valueOf(it.ref) as SegmentValue).seg }
        assertEquals(4, segs.size)
        val xs = segs.flatMap { listOf(it.a.x, it.b.x) }.distinct().sorted()
        val ys = segs.flatMap { listOf(it.a.y, it.b.y) }.distinct().sorted()
        assertEquals(2, xs.size, "still axis-aligned: two distinct x, got $xs")
        assertEquals(2, ys.size, "still axis-aligned: two distinct y, got $ys")
        assertClose(xs[1], 80.0, msg = "the welded corner sits on the anchor")
        assertClose(ys[1], 60.0)

        // drag the ANCHOR: the rectangle must follow it and stay rectangular
        ed.drag(Vec2(80.0, 60.0), Vec2(90.0, 40.0))
        val segs2 = ed.doc.elements.filter { it.kind == ElementKind.SEGMENT }.map { (Evaluator().valueOf(it.ref) as SegmentValue).seg }
        val xs2 = segs2.flatMap { listOf(it.a.x, it.b.x) }.distinct().sorted()
        val ys2 = segs2.flatMap { listOf(it.a.y, it.b.y) }.distinct().sorted()
        assertEquals(2, xs2.size, "welded corner drag keeps it a rectangle, got $xs2")
        assertClose(xs2[1], 90.0)
        assertClose(ys2[1], 40.0, msg = "the welded corner followed the anchor down to y=40")
    }
}
