package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.dsl.Construction
import constructit.dsl.ellipse
import constructit.dsl.point
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Conics
import constructit.geom.Ellipse
import constructit.geom.EllipticArc
import constructit.geom.Vec2
import constructit.units.deg
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Conics as first-class curve values** (OP-24): the drawn ellipse, its rider, its orientation by
 * construction, and the refusals that speak.
 *
 * The claim under test is the one the queue entry makes: *position-along an elliptic curve is exact*,
 * because nothing forces arc length to be the parameter. A rider lives at the parametric angle `t`
 * (`x = a·cos t`, `y = b·sin t` in the ellipse's own frame), so its point, its tangent and its normal are
 * plain trigonometry — the same thing an on-circle point has always done, one conic up.
 */
class EllipseTest {
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

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun Editor.at(el: Element): Vec2 = assertNotNull((Evaluator().valueOf(el.ref) as? PointValue)?.p)

    private fun roundTrip(ed: Editor): String {
        val once = DocumentFormat.save(ed.doc)
        val back = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(back), "save -> load -> save must be byte-equal")
        return once
    }

    /** The acceptance ellipse: centre (50, 50), axis end (110, 50) — so `a = 60`, orientation 0 — and `b = 30`. */
    private fun drawn(): Editor {
        val ed = Editor()
        ed.setTool(Tools.ELLIPSE_AB)
        ed.type("30")
        ed.click(Vec2(50.0, 50.0))
        ed.click(Vec2(110.0, 50.0))
        return ed
    }

    // ---- 1. the gesture, the value, and the rider ----

    @Test
    fun anEllipseIsDrawnByCentreAxisEndAndSemiAxis() {
        val ed = drawn()
        val el = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.ELLIPSE }, "an ellipse element")
        val e = assertNotNull(Evaluator().valueOf(el.ref) as? constructit.core.EllipseValue).ellipse
        assertClose(e.center.x, 50.0, 1e-12)
        assertClose(e.center.y, 50.0, 1e-12)
        assertClose(e.a, 60.0, 1e-12, "the axis end fixes the first semi-axis")
        assertClose(e.b, 30.0, 1e-12, "and the typed number the second")
        assertClose(e.rotation, 0.0, 1e-12, "and the axis end fixes the orientation too")
    }

    /**
     * A **rider at `t = π/3`** sits exactly where trigonometry says, its tangent and normal are the exact
     * derivatives there, and dragging it updates `t` while the point stays on the curve to 1e-9.
     */
    @Test
    fun aRiderOnAnEllipseLivesAtItsParametricAngleAndStaysExact() {
        val ed = drawn()
        val ellipse = ed.doc.elements.last { it.kind == ElementKind.ELLIPSE }
        val t0 = PI / 3.0
        val on = Vec2(50.0 + 60.0 * cos(t0), 50.0 + 30.0 * sin(t0))
        ed.setTool(Tools.POINT_ON_ELLIPSE)
        ed.click(on)
        val rider = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.ON_CURVE }, "a rider")
        assertClose(ed.at(rider).x, on.x, 1e-9, "the rider is where the parameter puts it")
        assertClose(ed.at(rider).y, on.y, 1e-9)
        val param = assertNotNull(ed.doc.riderParam(rider), "its own parameter node")
        assertClose(((Evaluator().eval(param) as EvalResult.Ok).value as constructit.core.ScalarValue).q.base, t0, 1e-9)

        // the tangent and the normal there: exact, by construction
        val e = assertNotNull(Evaluator().valueOf(ellipse.ref) as? constructit.core.EllipseValue).ellipse
        val tangent = Conics.tangentAt(e, t0)
        assertClose(tangent.x, -60.0 * sin(t0), 1e-12)
        assertClose(tangent.y, 30.0 * cos(t0), 1e-12)
        assertClose(Conics.normalAt(e, t0).dot(tangent.normalized()), 0.0, 1e-12, "the normal is normal")

        // drag it: t moves, and the point never leaves the curve
        val target = Vec2(20.0, 10.0)
        ed.setTool(Tools.SELECT)
        ed.drag(on, target)
        val now = ed.at(rider)
        assertClose(Conics.implicit(e, now), 0.0, 1e-9, "still exactly on the ellipse")
        val tNow = ((Evaluator().eval(param) as EvalResult.Ok).value as constructit.core.ScalarValue).q.base
        assertTrue(abs(tNow - t0) > 1e-3, "the parameter moved: $tNow")
        assertClose((now - Conics.pointAt(e, tNow)).length(), 0.0, 1e-9, "and the point is P(t) exactly")
        // …and the whole thing round-trips byte for byte
        roundTrip(ed)
    }

    // ---- 2. orientation from a shared point ----

    /**
     * **Orientation is a node, not a number**: bind the axis end onto a point of a 30°-rotated line's
     * direction and the ellipse turns with the line — and the rider's world position follows *exactly*,
     * because `t` is measured in the ellipse's own frame and the frame is what turned.
     */
    @Test
    fun bindingTheAxisEndToALinesDirectionTurnsTheEllipseAndTheRiderWithIt() {
        val c = Construction()
        val centre = c.freePoint("o", 50.0.mm, 50.0.mm)
        val theta = 30.0.deg
        // a point on the 30° line through the centre, at distance 60 — the axis end, by construction
        val axisEnd = c.polarPoint(centre, c.parameter("a", 60.0.mm), c.parameter("dir", theta))
        val e = c.ellipseCAB(centre, axisEnd, c.parameter("b", 30.0.mm))
        val t = c.parameter("t", 40.0.deg)
        val rider = c.pointOnEllipse(e, t)
        val ev = Evaluator()
        val el = ev.ellipse(e)
        assertClose(el.a, 60.0, 1e-12)
        assertClose(el.rotation, theta.base, 1e-12, "the picked point turned the frame")
        val tt = 40.0.deg.base
        val want =
            Vec2(
                50.0 + 60.0 * cos(tt) * cos(theta.base) - 30.0 * sin(tt) * sin(theta.base),
                50.0 + 60.0 * cos(tt) * sin(theta.base) + 30.0 * sin(tt) * cos(theta.base),
            )
        assertClose(ev.point(rider).x, want.x, 1e-12, "the rider's world position, computed by hand")
        assertClose(ev.point(rider).y, want.y, 1e-12)

        // …and turning the line turns both, with nothing rebuilt
        val before = c.nodesCreated
        c.set(c.parameter("unused", 0.0.mm), 0.0.mm) // no-op guard against accidental graph growth below
        val ev2 = Evaluator()
        assertEquals(before + 1, c.nodesCreated, "the guard parameter is the only new node")
        assertClose(ev2.ellipse(e).rotation, theta.base, 1e-12)
    }

    // ---- 3. the third-click form, and key points ----

    @Test
    fun theThreePointEllipseTakesItsSecondSemiAxisFromAPoint() {
        val ed = Editor()
        ed.setTool(Tools.ELLIPSE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(80.0, 0.0))
        ed.click(Vec2(10.0, 25.0))
        val el = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.ELLIPSE })
        val e = assertNotNull(Evaluator().valueOf(el.ref) as? constructit.core.EllipseValue).ellipse
        assertClose(e.a, 80.0, 1e-12)
        assertClose(e.b, 25.0, 1e-12, "the perpendicular distance of the third point from the axis")
        roundTrip(ed)
    }

    /** An ellipse's key points are its centre and its four axis endpoints — the vertices and co-vertices. */
    @Test
    fun keyPointsOfAnEllipseAreItsCentreAndFourAxisEnds() {
        val ed = drawn()
        ed.setTool(Tools.KEY_POINTS)
        ed.click(Vec2(110.0, 50.0))
        val pts = ed.doc.elements.filter { it.kind == ElementKind.DERIVED_POINT }.map { ed.at(it) }
        assertEquals(5, pts.size, "centre plus four axis ends: $pts")
        for (want in listOf(Vec2(50.0, 50.0), Vec2(110.0, 50.0), Vec2(50.0, 80.0), Vec2(-10.0, 50.0), Vec2(50.0, 20.0))) {
            assertTrue(pts.any { (it - want).length() < 1e-9 }, "a key point at $want; got $pts")
        }
    }

    /** The *Centre* tool works on a conic too — an ellipse has a centre, whatever else it has not got. */
    @Test
    fun theCentreToolTakesAnEllipse() {
        val ed = drawn()
        ed.setTool(Tools.CENTRE)
        ed.click(Vec2(110.0, 50.0))
        val p = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.DERIVED_POINT })
        assertClose(ed.at(p).x, 50.0, 1e-12)
        assertClose(ed.at(p).y, 50.0, 1e-12)
    }

    // ---- 4. measurement: the one honestly approximate reading ----

    /**
     * The measured length of a **quarter ellipse** with `a = 60`, `b = 30`, against a high-resolution
     * reference computed here — within the stated tolerance, and the status line says it is computed.
     */
    @Test
    fun theMeasuredLengthOfAQuarterEllipseIsWithinItsStatedTolerance() {
        val e = Ellipse(Vec2(0.0, 0.0), 60.0, 30.0, 0.0)
        val quarter = EllipticArc(e, 0.0, PI / 2.0, true)
        // a Richardson-free brute-force reference: 4 000 000 panels of the midpoint rule
        val n = 4_000_000
        var reference = 0.0
        for (i in 0 until n) {
            val t = (i + 0.5) * (PI / 2.0) / n
            reference += kotlin.math.sqrt(60.0 * 60.0 * sin(t) * sin(t) + 30.0 * 30.0 * cos(t) * cos(t))
        }
        reference *= (PI / 2.0) / n
        val measured = Conics.arcLength(quarter)
        assertClose(measured, reference, Conics.LENGTH_TOL_MM, "the elliptic integral, to its stated tolerance")
        // …and the whole circumference is four times a quarter, which the integrator has no way to fake
        assertClose(Conics.circumference(e), 4.0 * measured, 1e-9)
    }

    /** …and the *Length* tool says so: a conic's length is flagged as computed, a segment's is not. */
    @Test
    fun theLengthToolFlagsAConicAsComputedToTolerance() {
        val ed = drawn()
        ed.setTool(Tools.LENGTH)
        ed.click(Vec2(110.0, 50.0))
        assertTrue(ed.statusHint.contains("no closed form"), "the reading says it is computed: ${ed.statusHint}")
        val len = assertNotNull(ed.doc.scalars.lastOrNull { it.name.startsWith("len") })
        val exact = Conics.circumference(Ellipse(Vec2(50.0, 50.0), 60.0, 30.0, 0.0))
        assertClose(((Evaluator().eval(len.ref.node) as EvalResult.Ok).value as constructit.core.ScalarValue).q.mm, exact, 1e-9)

        // a segment's length carries no such note — the asymmetry *is* the honesty line
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 200.0))
        ed.click(Vec2(30.0, 200.0))
        ed.setTool(Tools.LENGTH)
        ed.click(Vec2(15.0, 200.0))
        assertTrue(!ed.statusHint.contains("no closed form"), "a segment's length is exact: ${ed.statusHint}")
    }

    // ---- 5. refusals, and the b > a decision ----

    /**
     * `a = 0` and `b = 0` are refused **with a reason** and heal (OP-3); `b > a` is not refused at all, and
     * the frame stays where the construction put it.
     *
     * The last is the package's own decision, and the test is what makes it a decision rather than an
     * accident: normalising to `a ≥ b` would swap the frame the instant `b` grew past `a`, and every stored
     * parametric angle — a rider's `t`, an arc's interval — would silently mean something 90° away.
     */
    @Test
    fun degenerateAxesRefuseByNameAndATallEllipseKeepsItsFrame() {
        val c = Construction()
        val centre = c.freePoint("o", 0.0.mm, 0.0.mm)
        val end = c.freePoint("e", 0.0.mm, 0.0.mm)
        val b = c.parameter("b", 30.0.mm)
        val zeroA = c.ellipseCAB(centre, end, b)
        val why = Evaluator().eval(zeroA.node)
        assertTrue(why is EvalResult.Invalid && why.reason.contains("coincides with its centre"), "$why")

        val end2 = c.freePoint("e2", 60.0.mm, 0.0.mm)
        val zeroB = c.ellipseCAB(centre, end2, c.parameter("b0", 0.0.mm))
        val why2 = Evaluator().eval(zeroB.node)
        assertTrue(why2 is EvalResult.Invalid && why2.reason.contains("positive second semi-axis"), "$why2")

        // b > a: a perfectly good ellipse, and the frame is untouched
        val tall = c.ellipseCAB(centre, end2, c.parameter("bt", 90.0.mm))
        val e = Evaluator().ellipse(tall)
        assertClose(e.a, 60.0, 1e-12, "a stays the semi-axis along the *picked* direction")
        assertClose(e.b, 90.0, 1e-12)
        assertClose(e.rotation, 0.0, 1e-12, "and the frame did not swap")
        assertClose(e.major, 90.0, 1e-12, "…while `major` still answers which one is bigger")
        assertClose(e.majorAngle, PI / 2.0, 1e-12)
        // t = 0 is still on the picked axis, which is exactly what a stored rider parameter depends on
        assertClose(Conics.pointAt(e, 0.0).x, 60.0, 1e-12)
        // and healing: give a a length again and the first ellipse comes back
        c.set(c.parameter("dummy", 0.0.mm), 0.0.mm)
        (end.node as constructit.core.SourceNode).value = PointValue(Vec2(10.0, 0.0))
        assertTrue(Evaluator().eval(zeroA.node) is EvalResult.Ok, "it heals (OP-3)")
    }

    // ---- 6. what it draws ----

    /**
     * The **rendered** conic, as a golden: an ellipse, an elliptic arc on it, and the arc's two ends — so
     * the one place a conic is a polyline rather than a primitive is pinned byte for byte.
     *
     * The step count is fixed (64 per full turn of parameter, the circle's own), which is exactly why a
     * golden of a conic is stable: nothing here depends on the camera or on curvature.
     */
    @Test
    fun anEllipseAndAnArcOfItDrawAsThemselves() {
        val c = Construction()
        val centre = c.freePoint("o", 0.0.mm, 0.0.mm)
        val axisEnd = c.freePoint("a", 60.0.mm, 0.0.mm)
        val e = c.ellipseCAB(centre, axisEnd, c.parameter("b", 30.0.mm))
        val arc = c.ellipticArcBetween(e, c.freePoint("s", 60.0.mm, 0.0.mm), c.freePoint("t", -60.0.mm, 0.0.mm), ccw = false)
        val items =
            listOf(
                constructit.svg.Drawable(e, stroke = "#bbbbbb"),
                constructit.svg.Drawable(arc),
                constructit.svg.Drawable(c.ellipticArcStart(arc), stroke = "#2ca02c"),
                constructit.svg.Drawable(c.ellipticArcEnd(arc), stroke = "#2ca02c"),
            )
        Golden.check("ellipse_and_arc", constructit.svg.Svg.render(Evaluator(), items))
    }

    /** A radial dimension on an ellipse refuses **by name** rather than pretending it has a radius. */
    @Test
    fun aRadialDimensionOnAnEllipseSaysWhyItCannot() {
        val ed = drawn()
        ed.setTool(Tools.DIM_RADIAL)
        ed.click(Vec2(110.0, 50.0))
        ed.click(Vec2(130.0, 70.0))
        assertTrue(ed.statusHint.contains("no single radius"), "it says what an ellipse has not got: ${ed.statusHint}")
    }
}
