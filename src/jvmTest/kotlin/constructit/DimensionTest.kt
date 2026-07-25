package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.core.ScalarValue
import constructit.editor.AngularDimension
import constructit.editor.Camera
import constructit.editor.DimensionAnnotation
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.LinearDimension
import constructit.editor.RadialDimension
import constructit.editor.SvgDrawTarget
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dimensions (roadmap 7): a dimension is a **displayable element whose value is an ordinary measurement
 * node** (OP-4). Two properties carry the whole feature and most of these tests pin one of them:
 *
 * - it **shows** and never asserts — the graphic recomputes from the live value, and dragging a measured
 *   point changes the number rather than the geometry;
 * - its own placement is a [constructit.editor.Handle] like any other (OP-13) — the same source nodes
 *   are written by a drag and by a typed field, and restated on save (OP-18).
 */
class DimensionTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    /** The base-unit (mm / rad) value of the named scalar — a measurement is a scalar like any other. */
    private fun scalar(
        doc: Document,
        name: String,
    ): Double {
        val e = doc.scalars.first { it.name == name }
        return ((Evaluator().eval(e.ref.node) as EvalResult.Ok).value as ScalarValue).q.base
    }

    private fun dimensions(doc: Document): List<Element> = doc.elements.filter { it.annotation != null }

    private fun annotation(doc: Document): DimensionAnnotation = dimensions(doc).last().annotation!!

    private fun pointAt(
        doc: Document,
        id: String,
    ): Vec2 {
        val el = doc.elements.first { it.id == id }
        return ((Evaluator().eval(el.ref.node) as EvalResult.Ok).value as PointValue).p
    }

    /** Two free points 100 apart, then a linear dimension 20 above them. */
    private fun linear(): Editor {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.DIM_LINEAR)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.click(Vec2(50.0, 20.0)) // where the dimension line sits
        ed.setTool(Tools.SELECT)
        return ed
    }

    /** A circle of radius 30 about the origin, with a radial dimension leading straight up. */
    private fun radial(): Editor {
        val ed = Editor()
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.DIM_RADIAL)
        ed.click(Vec2(0.0, 30.0)) // the circle itself
        ed.click(Vec2(0.0, 50.0)) // leader up, 20 past it
        ed.setTool(Tools.SELECT)
        return ed
    }

    /**
     * Two lines crossing at (50,0) at 45°, plus an angular dimension on the sector containing [at].
     */
    private fun angular(at: Vec2): Editor {
        val ed = Editor()
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, -50.0))
        ed.click(Vec2(100.0, 50.0))
        ed.setTool(Tools.DIM_ANGULAR)
        ed.click(Vec2(20.0, 0.0))
        ed.click(Vec2(75.0, 25.0))
        ed.click(at)
        ed.setTool(Tools.SELECT)
        return ed
    }

    // ---- placing ----

    @Test
    fun aLinearDimensionShowsTheMeasuredDistanceAndSitsWhereItWasPlaced() {
        val ed = linear()
        assertEquals(1, dimensions(ed.doc).size)
        assertEquals(ElementKind.DIMENSION, dimensions(ed.doc)[0].kind)
        assertClose(scalar(ed.doc, "dist"), 100.0, msg = "the value is an ordinary measurement node (OP-4)")
        val ann = annotation(ed.doc) as LinearDimension
        assertClose((ann.offset.value as ScalarValue).q.mm, 20.0, msg = "the click set the offset")
        assertEquals("100 mm", ann.label(Evaluator()))
    }

    @Test
    fun aRadialDimensionShowsTheRadiusWithItsOwnWord() {
        val ed = radial()
        assertClose(scalar(ed.doc, "radius"), 30.0)
        val ann = annotation(ed.doc) as RadialDimension
        assertClose((ann.leaderAngle.value as ScalarValue).q.deg, 90.0, msg = "the leader points the way it was placed")
        assertClose((ann.leaderReach.value as ScalarValue).q.mm, 20.0, msg = "and reaches 20 past the circle")
        assertEquals("R 30 mm", ann.label(Evaluator()))
    }

    /** An arc reaches a radial dimension through its carrier circle — the coercion LINE slots already have. */
    @Test
    fun anArcCanBeDimensionedThroughItsCarrierCircle() {
        val ed = Editor()
        ed.setTool(Tools.ARC_3)
        ed.click(Vec2(-20.0, 0.0))
        ed.click(Vec2(0.0, 20.0))
        ed.click(Vec2(20.0, 0.0))
        ed.setTool(Tools.DIM_RADIAL)
        ed.click(Vec2(0.0, 20.0))
        ed.click(Vec2(0.0, 40.0))
        assertEquals(1, dimensions(ed.doc).size)
        assertClose(scalar(ed.doc, "radius"), 20.0)
    }

    /**
     * Which sector an angular dimension names is decided by the click and then **stored** (OP-1): the
     * obtuse sector reads 135°, the acute one 45°, and the arc drawn is the one the number belongs to.
     */
    @Test
    fun anAngularDimensionNamesTheSectorItsClickWasIn() {
        assertClose(scalar(angular(Vec2(70.0, 5.0)).doc, "angle"), Math.toRadians(45.0), tol = 1e-9)
        assertClose(scalar(angular(Vec2(30.0, 5.0)).doc, "angle"), Math.toRadians(135.0), tol = 1e-9)
    }

    @Test
    fun anAngularDimensionsArcSpansTheSectorItNames() {
        val ed = angular(Vec2(30.0, 5.0))
        val g = assertNotNull((annotation(ed.doc) as AngularDimension).graphic(Evaluator()))
        val arc = assertNotNull(g.arc)
        assertClose(arc.center.x, 50.0)
        assertClose(arc.center.y, 0.0)
        assertEquals("135°", g.text)
        // from the -x leg counter-clockwise... the sector is the one the click was in, so the sweep is 135°
        val sweep = kotlin.math.abs(arc.endAngle - arc.startAngle)
        assertClose(if (sweep > Math.PI) 2 * Math.PI - sweep else sweep, Math.toRadians(135.0), tol = 1e-9)
    }

    // ---- the offset as a handle (OP-13) ----

    @Test
    fun draggingADimensionMovesItsOffsetAndNothingElse() {
        val ed = linear()
        val ann = annotation(ed.doc) as LinearDimension
        ed.drag(Vec2(50.0, 20.0), Vec2(50.0, 35.0))
        assertClose((ann.offset.value as ScalarValue).q.mm, 35.0, msg = "the grab held its offset")
        assertClose(scalar(ed.doc, "dist"), 100.0, msg = "a dimension drives nothing")
        assertClose(pointAt(ed.doc, "e1").y, 0.0)
        assertClose(pointAt(ed.doc, "e2").x, 100.0)
    }

    @Test
    fun draggingARadialDimensionSwingsItsLeader() {
        val ed = radial()
        val ann = annotation(ed.doc) as RadialDimension
        ed.drag(Vec2(0.0, 50.0), Vec2(45.0, 0.0))
        assertClose((ann.leaderAngle.value as ScalarValue).q.deg, 0.0)
        assertClose((ann.leaderReach.value as ScalarValue).q.mm, 15.0)
        assertClose(scalar(ed.doc, "radius"), 30.0, msg = "the circle is untouched")
    }

    @Test
    fun draggingAnAngularDimensionResizesItsArcButKeepsItsSector() {
        val ed = angular(Vec2(30.0, 5.0))
        val ann = annotation(ed.doc) as AngularDimension
        val before = ann.sign1 to ann.sign2
        ed.drag(assertNotNull(ann.anchor(Evaluator())), Vec2(50.0, 60.0))
        assertClose((ann.radius.value as ScalarValue).q.mm, 60.0)
        assertEquals(before, ann.sign1 to ann.sign2, "the sector is a stored choice, not a live guess")
        assertClose(scalar(ed.doc, "angle"), Math.toRadians(135.0), tol = 1e-9)
    }

    /** The typed form of the very same write (OP-13) — and the measured value reports itself read-only. */
    @Test
    fun theOffsetIsTypeableAndTheMeasuredValueIsNot() {
        val ed = linear()
        ed.click(Vec2(50.0, 20.0)) // select the dimension
        val fields = ed.selectionFields()
        assertEquals(listOf("offset", "distance"), fields.map { it.label })
        assertTrue(fields[0].writable)
        assertFalse(fields[1].writable, "a measurement is derived — a dimension is the driven side (OP-4)")
        assertNull(fields[1].node, "and so it has no node to write")
        assertTrue(ed.writeSelectionField(0, -12.5))
        assertClose((annotation(ed.doc) as LinearDimension).offset.value.let { (it as ScalarValue).q.mm }, -12.5)
        assertFalse(ed.writeSelectionField(1, 999.0), "the measured value refuses to be written")
        assertClose(scalar(ed.doc, "dist"), 100.0)
    }

    /** A dimension that lies over the geometry it names must not steal the grab from it. */
    @Test
    fun aPointUnderTheDimensionLineStillWins() {
        val ed = linear()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(50.0, 20.0)) // right on the dimension line
        ed.setTool(Tools.SELECT)
        val onTheLine = ed.doc.freePoints.last()
        ed.drag(Vec2(50.0, 20.0), Vec2(50.0, 60.0))
        assertClose(pointAt(ed.doc, onTheLine.id).y, 60.0, msg = "the point moved")
        assertClose((annotation(ed.doc) as LinearDimension).offset.value.let { (it as ScalarValue).q.mm }, 20.0)
    }

    // ---- live value ----

    /**
     * The load-bearing property: the graphic is a *view* of the node. Move a measured point and the value
     * — and the text drawn for it — follow, with nothing rebuilt.
     */
    @Test
    fun movingAMeasuredPointChangesTheNumberTheDimensionDraws() {
        val ed = linear()
        val nodesBefore = ed.doc.cx.nodesCreated
        ed.drag(Vec2(100.0, 0.0), Vec2(150.0, 0.0))
        assertClose(scalar(ed.doc, "dist"), 150.0)
        assertEquals(nodesBefore, ed.doc.cx.nodesCreated, "a moved point recomputes the dimension, it does not rebuild it")
        val target = SvgDrawTarget()
        ed.render(target)
        assertTrue(target.svg().contains(">150 mm<"), "the drawn text shows the new value")
        assertFalse(target.svg().contains(">100 mm<"))
    }

    /** An invalid or degenerate measurement simply draws nothing (OP-3), rather than a broken graphic. */
    @Test
    fun aCollapsedSpanDrawsNoDimension() {
        val ed = linear()
        ed.drag(Vec2(100.0, 0.0), Vec2(0.0, 0.0))
        assertNull(annotation(ed.doc).graphic(Evaluator()), "a zero-length span has no direction to align to")
        val target = SvgDrawTarget()
        ed.render(target)
        assertFalse(target.svg().contains("<text"))
    }

    // ---- OP-14: annotation is neither result nor scaffolding ----

    /**
     * A dimension is **annotation** (OP-14's third column), so the dim-scaffolding view never touches it —
     * even when its measured value has been wired into the drawing, which does put the measurement node in
     * the result's ancestor closure.
     */
    @Test
    fun aDimensionIsNeverDimmedAsScaffolding() {
        val ed = linear()
        val t = ed.doc.newParameter("t", 10.0.mm)
        ed.activeScalar = t
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, -60.0))
        ed.click(Vec2(80.0, -57.0))
        ed.finishPath()
        assertTrue(ed.doc.wireParameter(t, ed.doc.scalars.first { it.name == "dist" }), "the wall is as thick as the measured span")
        val scaffolding = ed.doc.scaffoldingElements()
        assertTrue(scaffolding.isNotEmpty(), "the carrier legs are scaffolding")
        assertTrue(scaffolding.none { it.isAnnotation }, "the dimension is not")
        ed.dimScaffolding = true
        val target = SvgDrawTarget()
        ed.render(target)
        assertTrue(target.svg().contains("#17607d"), "it is still drawn in its own annotation colour")
    }

    // ---- persistence (OP-18) ----

    /**
     * All three kinds through the save format, after dragging one — with no per-kind support in
     * `DocumentFormat`: the generic `tool` step carries the picks, and `dofs=` carries the placement the
     * drag wrote, while the clicks stay verbatim because what they encode is a *choice*.
     */
    @Test
    fun everyDimensionKindRoundTripsIncludingADraggedOffset() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.DIM_LINEAR)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.click(Vec2(50.0, 20.0))
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, -60.0))
        ed.click(Vec2(30.0, -60.0))
        ed.setTool(Tools.DIM_RADIAL)
        ed.click(Vec2(0.0, -30.0))
        ed.click(Vec2(0.0, -10.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, -50.0))
        ed.click(Vec2(100.0, 50.0))
        ed.setTool(Tools.DIM_ANGULAR)
        ed.click(Vec2(20.0, 0.0))
        ed.click(Vec2(75.0, 25.0))
        ed.click(Vec2(30.0, 5.0))
        ed.setTool(Tools.SELECT)
        // a drag, so what is saved is state and not the click that first set it
        ed.drag(Vec2(50.0, 20.0), Vec2(53.0, 37.5))

        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.contains("dofs="), "the placement is restated as a value")
        val reloaded = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(reloaded), "save -> load -> save must be identical")
        assertEquals(3, dimensions(reloaded).size)
        assertClose(scalar(reloaded, "dist"), 100.0)
        assertClose(scalar(reloaded, "radius"), 30.0)
        assertClose(scalar(reloaded, "angle"), Math.toRadians(135.0), tol = 1e-9)
        assertClose(
            (dimensions(reloaded)[0].annotation as LinearDimension).offset.value.let { (it as ScalarValue).q.mm },
            37.5,
            msg = "the dragged offset came back, not the placing click's 20",
        )
    }

    /** Delete is step-shaped (OP-18): a dimension follows the geometry it measures, and undo brings both back. */
    @Test
    fun deletingAMeasuredPointTakesItsDimensionAlong() {
        val ed = linear()
        ed.click(Vec2(100.0, 0.0))
        assertTrue(ed.deleteSelection())
        assertEquals(0, dimensions(ed.doc).size, "the dimension had nothing left to measure")
        assertTrue(ed.doc.scalars.none { it.name == "dist" })
        assertTrue(ed.undo())
        assertEquals(1, dimensions(ed.doc).size)
        assertClose(scalar(ed.doc, "dist"), 100.0)
    }

    /** A dimension drag commits like any other (one operation, one undo step), because it is one. */
    @Test
    fun undoRevertsADimensionDrag() {
        val ed = linear()
        ed.drag(Vec2(50.0, 20.0), Vec2(50.0, 35.0))
        assertTrue(ed.undo())
        assertClose((annotation(ed.doc) as LinearDimension).offset.value.let { (it as ScalarValue).q.mm }, 20.0)
    }

    /** Deleting the dimension itself leaves the drawing alone — nothing depends on an annotation. */
    @Test
    fun deletingADimensionLeavesTheGeometry() {
        val ed = linear()
        ed.click(Vec2(50.0, 20.0))
        assertTrue(ed.deleteSelection())
        assertEquals(0, dimensions(ed.doc).size)
        assertEquals(2, ed.doc.freePoints.size)
    }

    // ---- rendering ----

    private fun rendered(ed: Editor): String {
        ed.canvasW = 320.0
        ed.canvasH = 240.0
        ed.camera = Camera.centered(320.0, 240.0, scale = 1.2)
        val target = SvgDrawTarget()
        ed.render(target)
        return target.svg()
    }

    @Test
    fun aLinearDimensionRenders() {
        Golden.check("editor_dim_linear", rendered(linear()))
    }

    @Test
    fun aRadialDimensionRenders() {
        Golden.check("editor_dim_radial", rendered(radial()))
    }

    @Test
    fun anAngularDimensionRenders() {
        Golden.check("editor_dim_angular", rendered(angular(Vec2(30.0, 5.0))))
    }
}
