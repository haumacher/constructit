package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.LoopRef
import constructit.dsl.loop
import constructit.dsl.point
import constructit.dsl.resultOf
import constructit.editor.Camera
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Styles
import constructit.editor.SvgDrawTarget
import constructit.editor.Tools
import constructit.geom.GeomMath
import constructit.geom.ProfileElement
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * OP-14 end to end: draw construction geometry, trace an outline over it with the tool, and get a
 * closed oriented boundary that is a pure function of the construction — plus OP-15's splines taking
 * part in that boundary on equal terms.
 */
class OutlineTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.use(tool: String) {
        setTool(tool)
    }

    /** Four infinite lines, traced into a rectangle by clicking round them. */
    private fun rectangleSetup(): Editor {
        val ed = Editor()
        ed.use(Tools.LINE)
        // bottom, right, top, left — each drawn through two points
        ed.click(Vec2(-50.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.click(Vec2(40.0, -50.0))
        ed.click(Vec2(40.0, 50.0))
        ed.click(Vec2(-50.0, 30.0))
        ed.click(Vec2(50.0, 30.0))
        ed.click(Vec2(-20.0, -50.0))
        ed.click(Vec2(-20.0, 50.0))
        return ed
    }

    @Test
    fun tracingFourLinesGivesAClosedRectangle() {
        val ed = rectangleSetup()
        assertEquals(4, ed.doc.elements.count { it.kind == ElementKind.LINE })

        ed.use(Tools.OUTLINE)
        ed.click(Vec2(0.0, 0.0)) // on the bottom line
        ed.click(Vec2(40.0, 15.0)) // right
        ed.click(Vec2(0.0, 30.0)) // top
        ed.click(Vec2(-20.0, 15.0)) // left
        assertTrue(ed.key("Enter"), "Enter should close the outline")

        val outline = ed.doc.elements.singleOrNull { it.kind == ElementKind.OUTLINE }
        assertNotNull(outline, "an outline element should have been created")
        @Suppress("UNCHECKED_CAST")
        val ref = outline.ref as LoopRef
        val ev = Evaluator()
        assertTrue(ev.resultOf(ref) is EvalResult.Ok, "the traced loop must be valid: ${ev.resultOf(ref)}")
        val l = ev.loop(ref)
        assertEquals(4, l.elements.size)
        assertTrue(l.elements.all { it is ProfileElement.Seg })
        // 60 wide (-20..40) by 30 tall (0..30)
        assertClose(GeomMath.signedArea(l), 60.0 * 30.0, tol = 1e-9)
        assertTrue(GeomMath.signedArea(l) > 0.0, "normalised counter-clockwise")
    }

    /** Clicking the first curve again closes the outline, without needing the keyboard. */
    @Test
    fun clickingTheFirstCurveAgainCloses() {
        val ed = rectangleSetup()
        ed.use(Tools.OUTLINE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 15.0))
        ed.click(Vec2(0.0, 30.0))
        ed.click(Vec2(-20.0, 15.0))
        ed.click(Vec2(10.0, 0.0)) // the bottom line again

        val outline = ed.doc.elements.singleOrNull { it.kind == ElementKind.OUTLINE }
        assertNotNull(outline)
        @Suppress("UNCHECKED_CAST")
        assertClose(GeomMath.signedArea(Evaluator().loop(outline.ref as LoopRef)), 60.0 * 30.0, tol = 1e-9)
    }

    /** The whole point: the result follows the construction it was traced over. */
    @Test
    fun theOutlineIsAPureFunctionOfTheConstruction() {
        val ed = rectangleSetup()
        ed.use(Tools.OUTLINE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 15.0))
        ed.click(Vec2(0.0, 30.0))
        ed.click(Vec2(-20.0, 15.0))
        ed.key("Enter")

        @Suppress("UNCHECKED_CAST")
        val ref = ed.doc.elements.single { it.kind == ElementKind.OUTLINE }.ref as LoopRef
        assertClose(GeomMath.signedArea(Evaluator().loop(ref)), 60.0 * 30.0, tol = 1e-9)

        // drag the point that defines the right-hand line: the outline must widen with it
        ed.use(Tools.SELECT)
        val rightDefining = ed.doc.elements.first { it.kind == ElementKind.POINT && abs(Evaluator().point(pointRef(it)).x - 40.0) < 1e-9 }
        val from = Evaluator().point(pointRef(rightDefining))
        ed.pointerDown(ed.camera.worldToScreen(from))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(70.0, from.y)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(70.0, from.y)))

        // The right line is defined by (40,-50) and (40,50); moving only the first *tilts* it, so the
        // rectangle becomes a trapezoid — a stronger check than a translation would be. The tilted
        // line runs x = 70 - 0.3*(y+50), so it crosses y=0 at x=55 and y=30 at x=46, and the left edge
        // stays at x=-20: area = ((55+20) + (46+20))/2 * 30.
        assertClose(GeomMath.signedArea(Evaluator().loop(ref)), (75.0 + 66.0) / 2.0 * 30.0, tol = 1e-6, msg = "outline should follow the drag")
    }

    @Suppress("UNCHECKED_CAST")
    private fun pointRef(el: constructit.editor.Element) = el.ref as constructit.dsl.PointRef

    /** A boundary of two lines and an arc: the branch is chosen from the click and then stored. */
    @Test
    fun tracingAnArcKeepsTheSideThatWasClicked() {
        val ed = Editor()
        ed.use(Tools.LINE)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.use(Tools.CIRCLE_R)
        val r = ed.doc.newParameter("r", 20.mm)
        ed.activeScalar = r
        ed.click(Vec2(0.0, 0.0))

        ed.use(Tools.OUTLINE)
        ed.click(Vec2(0.0, 0.0)) // the line
        ed.click(Vec2(0.0, 20.0)) // the circle, clicked on top
        ed.key("Enter")

        val outline = ed.doc.elements.singleOrNull { it.kind == ElementKind.OUTLINE }
        assertNotNull(outline, "a line + circle should close into a half-disc")
        @Suppress("UNCHECKED_CAST")
        val l = Evaluator().loop(outline.ref as LoopRef)
        assertEquals(2, l.elements.size)
        // clicked above the line, so the *upper* half is meant
        assertClose(abs(GeomMath.signedArea(l)), 0.5 * PI * 400.0, tol = 1e-6)
    }

    /** Scaffolding is derived from the graph, not flagged: it is what the results depend on. */
    @Test
    fun scaffoldingIsDerivedFromWhatTheResultUses() {
        val ed = rectangleSetup()
        assertTrue(ed.doc.scaffoldingElements().isEmpty(), "with no result, nothing is scaffolding yet")

        ed.use(Tools.OUTLINE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 15.0))
        ed.click(Vec2(0.0, 30.0))
        ed.click(Vec2(-20.0, 15.0))
        ed.key("Enter")

        val scaffolding = ed.doc.scaffoldingElements()
        assertTrue(scaffolding.isNotEmpty(), "the traced lines and their points are scaffolding")
        assertTrue(scaffolding.none { it.isResult }, "a result is never its own scaffolding")
        assertTrue(scaffolding.count { it.kind == ElementKind.LINE } == 4, "all four lines feed the outline")

        // an unrelated circle is *not* scaffolding: nothing in the output depends on it
        ed.use(Tools.CIRCLE_R)
        ed.activeScalar = ed.doc.newParameter("rr", 5.mm)
        ed.click(Vec2(200.0, 200.0))
        assertTrue(
            ed.doc.scaffoldingElements().none { it.kind == ElementKind.CIRCLE },
            "geometry the result does not use is not scaffolding",
        )
    }

    /** A Bézier joins a boundary by being *built onto* the joints, rather than trimmed to them. */
    @Test
    fun aSplineCanCloseABoundary() {
        val ed = Editor()
        // a horizontal line, and a spline arching from one of its points to another
        ed.use(Tools.LINE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        val a = ed.doc.elements.first { it.kind == ElementKind.POINT }
        val b = ed.doc.elements.filter { it.kind == ElementKind.POINT }[1]

        ed.use(Tools.BEZIER)
        ed.click(Evaluator().point(pointRef(a)))
        ed.click(Vec2(0.0, 40.0))
        ed.click(Vec2(60.0, 40.0))
        ed.click(Evaluator().point(pointRef(b)))
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.BEZIER })

        ed.use(Tools.OUTLINE)
        ed.click(Vec2(30.0, 0.0)) // the line
        ed.click(Vec2(30.0, 30.0)) // the spline
        ed.key("Enter")

        val outline = ed.doc.elements.singleOrNull { it.kind == ElementKind.OUTLINE }
        assertNotNull(outline, "line + spline should close")
        @Suppress("UNCHECKED_CAST")
        val ref = outline.ref as LoopRef
        val ev = Evaluator()
        assertTrue(ev.resultOf(ref) is EvalResult.Ok, "the spline boundary must close: ${ev.resultOf(ref)}")
        val l = ev.loop(ref)
        assertEquals(2, l.elements.size)
        assertTrue(l.elements.any { it is ProfileElement.BezierE }, "the spline is part of the boundary")
        assertTrue(l.elements.any { it is ProfileElement.Seg }, "so is the trimmed line")
        // Exact area under that cubic arch: with y(t) = 120·t(1−t) and dx/dt = 360·t(1−t), the
        // integral ∫y·dx is 43200·∫t²(1−t)² dt = 43200/30. Splines carry no accuracy penalty here.
        assertClose(abs(GeomMath.signedArea(l)), 43200.0 / 30.0, tol = 1e-9)
    }

    /** The rendered scene, including a traced outline and a spline, through the SVG backend. */
    @Test
    fun svgGolden() {
        val ed = Editor()
        ed.canvasW = 320.0
        ed.canvasH = 240.0
        ed.camera = Camera.centered(ed.canvasW, ed.canvasH)
        ed.use(Tools.LINE)
        ed.click(Vec2(-40.0, -20.0))
        ed.click(Vec2(40.0, -20.0))
        val a = ed.doc.elements.first { it.kind == ElementKind.POINT }
        val b = ed.doc.elements.filter { it.kind == ElementKind.POINT }[1]
        ed.use(Tools.BEZIER)
        ed.click(Evaluator().point(pointRef(a)))
        ed.click(Vec2(-40.0, 40.0))
        ed.click(Vec2(40.0, 40.0))
        ed.click(Evaluator().point(pointRef(b)))
        ed.use(Tools.OUTLINE)
        ed.click(Vec2(0.0, -20.0))
        // on the curve, not merely near it: at t=0.5 this arch passes through (0, 25)
        ed.click(Vec2(0.0, 25.0))
        ed.key("Enter")
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.OUTLINE }, "the golden must actually contain a result")

        val target = SvgDrawTarget()
        ed.render(target)
        Golden.check("editor_outline_spline", target.svg())

        ed.dimScaffolding = true
        val dimmed = SvgDrawTarget()
        ed.render(dimmed)
        Golden.check("editor_outline_spline_dimmed", dimmed.svg())
        assertTrue(dimmed.svg().contains(Styles.DIMMED.stroke), "dimming should reach the output")
    }

    /**
     * **A boundary whose joints are tangencies.** Every side of a rounded rectangle meets its corner arc
     * tangentially, and a tangent line and circle have no intersection to find — so deriving each joint by
     * intersection refused to trace the commonest outline in mechanical CAD, and refused *silently*
     * (nothing is built when a joint cannot be found). Pieces that already meet now hand over at their
     * shared endpoint instead.
     *
     * The area asserted is the closed form `w·h − (4−π)r²`, and it is asserted **again after retyping the
     * radius**: the joints are endpoint accessors, so the boundary is still a function of the parameter and
     * not eight frozen coordinates.
     */
    @Test
    fun aRoundedRectangleTracesAlthoughEveryJointIsATangency() {
        val ed = Editor()
        ed.setTool(Tools.ROUNDED_RECT)
        ed.key("8")
        ed.key("Enter")
        ed.click(Vec2(-60.0, -40.0))
        ed.click(Vec2(60.0, 40.0))
        assertEquals(8, ed.doc.elements.count { it.isCurve }, "four sides and four corner arcs")

        ed.use(Tools.OUTLINE)
        // two picks — a side and the corner arc next to it — fix the direction; the boundary-follow walks
        // the other six pieces and closes it (a corner is clicked at its 45° point)
        val d = 8.0 * kotlin.math.cos(PI / 4)
        ed.click(Vec2(0.0, 40.0))
        ed.click(Vec2(52.0 + d, 32.0 + d))

        val outline = assertNotNull(ed.doc.elements.singleOrNull { it.kind == ElementKind.OUTLINE }, "the trace must produce a loop")

        @Suppress("UNCHECKED_CAST")
        val ref = outline.ref as LoopRef
        val loop = assertNotNull((Evaluator().eval(ref.node) as? EvalResult.Ok)?.value, "the loop must close").let { Evaluator().loop(ref) }
        assertEquals(8, loop.elements.size, "eight pieces, in the order they were clicked")

        fun area() = abs(GeomMath.signedArea(Evaluator().loop(ref)))
        assertClose(area(), 120.0 * 80.0 - (4 - PI) * 8.0 * 8.0, tol = 1e-6, msg = "w·h − (4−π)r²")

        // the radius is still what drives it — the joints follow, so the traced boundary is not frozen
        ed.doc.setParameter(ed.doc.scalars.single { it.editable }, 20.0.mm)
        assertClose(area(), 120.0 * 80.0 - (4 - PI) * 20.0 * 20.0, tol = 1e-6, msg = "the trace follows the parameter")
    }
}
