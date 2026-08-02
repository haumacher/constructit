package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.Path3Value
import constructit.dsl.Point3Ref
import constructit.dsl.SolidRef
import constructit.dsl.point3
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.Camera3
import constructit.editor.DocumentFormat
import constructit.editor.DrawTarget
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.PlanePerspective
import constructit.editor.Scene3
import constructit.editor.Scene3Sync
import constructit.editor.Style
import constructit.editor.Styles
import constructit.editor.SvgDrawTarget
import constructit.editor.TextAnchor
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.Curve3Element
import constructit.geom.Curves3
import constructit.geom.Path3
import constructit.geom.Plane3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Curves in space** (OP-26, step 1): a `Path3` is a value of the graph, built through points that already
 * stand in space, drawn in the 3D view, projected into the plan, and pickable in both.
 *
 * What is asserted here and nowhere else, in the order the feature is built on itself: that a curve through
 * three points **is** the polyline through them, exactly; that it follows those points because it *shares
 * their nodes*, which is the parenting rule paying out and is asked hardest where a point is shared with a
 * solid; that the smooth mode interpolates its points and is C1 at every interior one, with its end
 * condition asserted rather than assumed; that closed-versus-open is structure and survives the file; that
 * the plan draws the curve seen down the active plane's normal; that a click reaches it in either view
 * without displacing the points it runs through; that a save round-trips byte for byte and one undo takes
 * the whole gesture back; that a drawing containing curves still costs an orbit and a hover nothing; and
 * that every way of asking for a curve that is not one is refused **by name**.
 */
class SpaceCurveTest {
    private val wPx = 800.0
    private val hPx = 600.0

    /** A target that only counts what it was given — what a claim about *work* is asserted against. */
    private class Counting : DrawTarget {
        val runs = ArrayList<List<Vec2>>()

        override fun begin(
            widthPx: Double,
            heightPx: Double,
        ) = Unit

        override fun polyline(
            points: List<Vec2>,
            style: Style,
        ) {
            runs.add(points)
        }

        override fun polygon(
            points: List<Vec2>,
            style: Style,
        ) {
            runs.add(points)
        }

        override fun circle(
            center: Vec2,
            radiusPx: Double,
            style: Style,
        ) = Unit

        override fun dot(
            center: Vec2,
            radiusPx: Double,
            color: String,
        ) = Unit

        override fun text(
            at: Vec2,
            text: String,
            style: Style,
            anchor: TextAnchor,
        ) = Unit

        override fun end() = Unit

        val points: Int get() = runs.sumOf { it.size }
    }

    // ---- driving the two views ----

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
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

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    /** A 3D view over [ed], posed so that the plan's points and their lifted twins are well apart on screen. */
    private fun view(ed: Editor): Viewport3 {
        val vp =
            Viewport3(
                camera = Camera3(target = Vec3(60.0, 50.0, 30.0), distance = 420.0, yaw = -0.9, pitch = 0.55),
                widthPx = wPx,
                heightPx = hPx,
            )
        vp.editor = ed
        vp.shown = true
        return vp
    }

    /** Where the point [lift] mm over plane point [base] is seen in this view — what a click there aims at. */
    private fun Viewport3.atLifted(
        base: Vec2,
        lift: Double,
    ): Vec2 = assertNotNull(assertNotNull(projection()).toScreenLifted(base, lift), "the lifted point has an image")

    /**
     * Hand the editor back to the 2D canvas — what the shell's view switch does ([Viewport3.shown]), and
     * what a test must do before driving a gesture in the plan: while the 3D view is shown it *is* the
     * editor's projection, so a click stated in canvas pixels would be resolved through the perspective.
     */
    private fun toPlan(vp: Viewport3) {
        vp.shown = false
    }

    private fun Viewport3.clickAt(screen: Vec2) {
        pointerMove(screen)
        pointerDown(screen)
        pointerUp(screen)
    }

    /** Click the height point standing [lift] over [base], in the 3D view — the only view it is pickable in. */
    private fun Viewport3.clickLifted(
        base: Vec2,
        lift: Double,
    ) = clickAt(atLifted(base, lift))

    // ---- reading the model ----

    private fun Editor.curves(): List<Element> = doc.elements.filter { it.kind == ElementKind.SPACE_CURVE }

    private fun pathOf(el: Element): Path3 =
        assertNotNull(Evaluator().valueOf(el.ref) as? Path3Value, "a curve in space evaluates to a path").path

    private fun assertVec(
        actual: Vec3,
        expected: Vec3,
        tol: Double = 1e-9,
        msg: String = "",
    ) {
        assertClose(actual.x, expected.x, tol, "x of $msg (was $actual, wanted $expected)")
        assertClose(actual.y, expected.y, tol, "y of $msg (was $actual, wanted $expected)")
        assertClose(actual.z, expected.z, tol, "z of $msg (was $actual, wanted $expected)")
    }

    /**
     * A height point over ([x], [y]) at height [h], through the ordinary gesture (OP-25) — two elements, the
     * base point and the lifted one, which is what makes both of them draggable afterwards.
     */
    private fun heightPoint(
        ed: Editor,
        x: Double,
        y: Double,
        h: String,
    ): Element {
        ed.setTool(Tools.HEIGHT_POINT)
        ed.type(h)
        ed.click(Vec2(x, y))
        return ed.doc.elements.last { it.kind == ElementKind.HEIGHT_POINT }
    }

    /** Three height points over an L in the plan, at three different heights — the fixture most tests use. */
    private fun threePoints(ed: Editor): List<Pair<Vec2, Double>> {
        heightPoint(ed, 0.0, 0.0, "40")
        heightPoint(ed, 120.0, 0.0, "20")
        heightPoint(ed, 120.0, 100.0, "70")
        return listOf(Vec2(0.0, 0.0) to 40.0, Vec2(120.0, 0.0) to 20.0, Vec2(120.0, 100.0) to 70.0)
    }

    /** Run [tool] over the given lifted points in the 3D view, finishing with Enter (an **open** curve). */
    private fun curveThrough(
        ed: Editor,
        vp: Viewport3,
        points: List<Pair<Vec2, Double>>,
        tool: String = Tools.CURVE3,
    ): Element {
        ed.setTool(tool)
        for ((base, lift) in points) vp.clickLifted(base, lift)
        ed.key("Enter")
        return assertNotNull(ed.curves().lastOrNull(), "the gesture built a curve: ${ed.statusHint}")
    }

    // ---- 1. a curve through three points IS the polyline through them ----

    /**
     * **The straight mode is the polyline, to the last bit.** Three height points, three clicks in the 3D
     * view, and what comes out is two [Curve3Element.Seg3]s whose world coordinates are the points' own —
     * no fitting, no smoothing, nothing approximated.
     */
    @Test
    fun aCurveThroughThreeHeightPointsIsThePolylineThroughThem() {
        val ed = Editor()
        val pts = threePoints(ed)
        val vp = view(ed)
        val curve = curveThrough(ed, vp, pts)

        val path = pathOf(curve)
        assertTrue(!path.closed, "Enter finishes an open curve")
        assertEquals(2, path.elements.size, "three points make two pieces: ${path.elements}")
        val world = pts.map { (b, h) -> Vec3(b.x, b.y, h) }
        for (i in 0 until 2) {
            val seg = assertNotNull(path.elements[i] as? Curve3Element.Seg3, "piece $i is a straight run")
            assertVec(seg.start, world[i], msg = "piece $i's start")
            assertVec(seg.end, world[i + 1], msg = "piece $i's end")
        }
        // and the chain really is a chain: consecutive pieces carry the identical hand-over point
        assertTrue(path.elements[0].end == path.elements[1].start, "the pieces meet exactly, not nearly")
    }

    // ---- 2. it follows its points, because the nodes are shared ----

    /**
     * **Both halves of a height point move the curve** (OP-25 + OP-26): drag the *base* in the plan and the
     * curve follows; retype the *height* and it follows too. Nothing here is a constraint — the curve is a
     * pure function of the points, so a recompute is the whole mechanism.
     */
    @Test
    fun draggingAPointsBaseOrRetypingItsHeightMovesTheCurve() {
        val ed = Editor()
        val pts = threePoints(ed)
        val vp = view(ed)
        val curve = curveThrough(ed, vp, pts)
        assertVec(assertNotNull(pathOf(curve).start), Vec3(0.0, 0.0, 40.0), msg = "the curve's first point")

        // the base, dragged where it lives: in the plan
        toPlan(vp)
        ed.drag(Vec2(0.0, 0.0), Vec2(-25.0, 15.0))
        assertVec(assertNotNull(pathOf(curve).start), Vec3(-25.0, 15.0, 40.0), msg = "the curve after the drag")

        // the height, an ordinary named scalar, retyped in the panel
        val h = assertNotNull(ed.doc.scalars.firstOrNull { it.name == "height" }, "the first height is a panel row")
        ed.doc.setParameter(h, 95.0.mm)
        assertVec(assertNotNull(pathOf(curve).start), Vec3(-25.0, 15.0, 95.0), msg = "the curve after the retype")
    }

    /**
     * **A point shared with something else couples them by construction** — the load-bearing one, and the
     * whole reason a curve takes *existing* points rather than copying coordinates.
     *
     * The apex of a pyramid is a height point (OP-25). Route a curve through that very apex, then drag the
     * base it stands on: the solid leans and the curve moves, in one recompute, because there is one node.
     */
    @Test
    fun aPointSharedWithASolidMovesBothWhenItIsDragged() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.type("90")
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(50.0, 50.0))
        val apex = ed.doc.elements.last { it.kind == ElementKind.HEIGHT_POINT }
        val solid = ed.doc.elements.last { it.kind == ElementKind.SOLID }
        heightPoint(ed, 200.0, 0.0, "30")
        heightPoint(ed, 200.0, 100.0, "30")

        val vp = view(ed)
        val curve =
            curveThrough(
                ed,
                vp,
                listOf(Vec2(50.0, 50.0) to 90.0, Vec2(200.0, 0.0) to 30.0, Vec2(200.0, 100.0) to 30.0),
            )
        assertTrue(
            ed.doc.elements.count { it.kind == ElementKind.HEIGHT_POINT } == 3,
            "the curve reused the apex rather than raising a fourth point",
        )
        assertVec(assertNotNull(pathOf(curve).start), Vec3(50.0, 50.0, 90.0), msg = "the curve starts at the apex")

        @Suppress("UNCHECKED_CAST")
        fun apexOfSolid(): Vec3 =
            Evaluator().solid(solid.ref as SolidRef).mesh.vertices.maxByOrNull { it.z }
                ?: error("the pyramid has an apex vertex")
        assertVec(apexOfSolid(), Vec3(50.0, 50.0, 90.0), msg = "the solid's apex before the drag")

        // one drag of the base both of them hang off
        toPlan(vp)
        ed.drag(Vec2(50.0, 50.0), Vec2(70.0, 35.0))
        assertVec(assertNotNull(pathOf(curve).start), Vec3(70.0, 35.0, 90.0), msg = "the curve followed")
        assertVec(apexOfSolid(), Vec3(70.0, 35.0, 90.0), msg = "and so did the solid, from the same node")
        assertVec(pointOf(apex), Vec3(70.0, 35.0, 90.0), msg = "which is the one point both of them read")
    }

    @Suppress("UNCHECKED_CAST")
    private fun pointOf(el: Element): Vec3 = Evaluator().point3(el.ref as Point3Ref)

    // ---- 3. the smooth mode interpolates, is C1, and its end condition is stated ----

    /**
     * **Smooth means interpolating**, and every claim about it is asserted rather than described: the curve
     * passes through every point it was built on, the two pieces meeting at an interior point share a
     * tangent there (C1), and at the ends it leaves along the chord to its neighbour — the end condition,
     * which is a real decision (see [Curves3.smoothThrough]).
     */
    @Test
    fun theSmoothModeInterpolatesItsPointsIsC1AndLeavesAlongTheEndChords() {
        val ed = Editor()
        val pts = threePoints(ed) + (heightPoint(ed, 0.0, 100.0, "10").let { Vec2(0.0, 100.0) to 10.0 })
        val vp = view(ed)
        val curve = curveThrough(ed, vp, pts, Tools.CURVE3_SMOOTH)

        val path = pathOf(curve)
        val world = pts.map { (b, h) -> Vec3(b.x, b.y, h) }
        assertEquals(3, path.elements.size, "four points make three spans: ${path.elements}")
        val pieces = path.elements.map { assertNotNull(it as? Curve3Element.Bezier3, "a smooth piece is a cubic") }

        // (a) it passes through every knot — the curve's own value at t = 0 and t = 1 of each span
        for (i in pieces.indices) {
            assertVec(Curves3.bezierPointAt(pieces[i], 0.0), world[i], 1e-9, "the curve at knot $i")
            assertVec(Curves3.bezierPointAt(pieces[i], 1.0), world[i + 1], 1e-9, "the curve at knot ${i + 1}")
        }
        // (b) C1 at every interior knot: the outgoing tangent is the incoming one, not merely parallel to it
        for (i in 0 until pieces.size - 1) {
            val incoming = Curves3.bezierTangentAt(pieces[i], 1.0)
            val outgoing = Curves3.bezierTangentAt(pieces[i + 1], 0.0)
            assertVec(outgoing, incoming, 1e-9, "the tangent at interior knot ${i + 1}")
            assertTrue(incoming.length() > 1e-6, "and it is a real tangent, not a cusp: $incoming")
        }
        // (c) the stated end condition: the chord. The first tangent is exactly P1 - P0, the last P(n-1) - P(n-2)
        assertVec(Curves3.bezierTangentAt(pieces.first(), 0.0), world[1] - world[0], 1e-9, "the start tangent")
        assertVec(
            Curves3.bezierTangentAt(pieces.last(), 1.0),
            world[world.size - 1] - world[world.size - 2],
            1e-9,
            "the end tangent",
        )
    }

    /**
     * **A smooth curve through two points is the straight one between them** — the degenerate case the chord
     * end condition is chosen to get right, and a property a natural spline would not have given.
     */
    @Test
    fun aSmoothCurveThroughTwoPointsIsTheStraightLine() {
        val a = Vec3(0.0, 0.0, 0.0)
        val b = Vec3(30.0, 40.0, 50.0)
        val el = assertNotNull(Curves3.smoothThrough(listOf(a, b)).single() as? Curve3Element.Bezier3)
        for (t in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
            assertVec(Curves3.bezierPointAt(el, t), a + (b - a) * t, 1e-9, "the curve at t = $t")
        }
    }

    // ---- 4. closed vs open is structure, and it survives the file ----

    /**
     * **Closing is said by returning to the point you started at**, and it is a different curve: one more
     * piece, ending where the first begins. The file states it the same way — by naming that point twice —
     * so a reload closes for exactly the reason the gesture did.
     */
    @Test
    fun clickingTheFirstPointAgainClosesTheCurveAndTheFileSaysSo() {
        val ed = Editor()
        val pts = threePoints(ed)
        val vp = view(ed)
        ed.setTool(Tools.CURVE3)
        for ((base, lift) in pts) vp.clickLifted(base, lift)
        // …and back to the first, which both finishes the run and states the closure
        vp.clickLifted(pts[0].first, pts[0].second)
        val curve = assertNotNull(ed.curves().lastOrNull(), "the closing click built the curve: ${ed.statusHint}")

        val path = pathOf(curve)
        assertTrue(path.closed, "the curve knows it is closed")
        assertEquals(3, path.elements.size, "three points closed make three pieces, not two")
        assertTrue(path.elements.last().end == path.elements.first().start, "and the last piece hands back to the first")

        val script = DocumentFormat.save(ed.doc)
        val step = script.lines().last { it.startsWith("tool curve3") }
        assertTrue(
            step.contains("els=") && step.substringAfter("els=").substringBefore(" ").let { it.split(",").let { p -> p.first() == p.last() } },
            "the step states the closure by naming the first point again: $step",
        )
        val reloaded = DocumentFormat.load(script)
        val back = reloaded.elements.last { it.kind == ElementKind.SPACE_CURVE }
        assertTrue(pathOf(back).closed, "and it comes back closed")
        assertEquals(path, pathOf(back), "with the same geometry, piece for piece")
    }

    /** The open twin of the above, so "three pieces" is a statement about closing and not about the fixture. */
    @Test
    fun theSameThreePointsFinishedWithEnterAreAnOpenCurve() {
        val ed = Editor()
        val pts = threePoints(ed)
        val curve = curveThrough(ed, view(ed), pts)
        val path = pathOf(curve)
        assertTrue(!path.closed)
        assertEquals(2, path.elements.size)
        assertTrue(path.elements.last().end != path.elements.first().start, "an open curve does not come back")
    }

    // ---- 5. the plan projection ----

    /**
     * **The plan draws the curve seen down the active plane's normal, exactly.** A path whose points all
     * stand over one space projects onto that space's plane as precisely the 2D chain their bases describe —
     * an affine map of an affine-invariant piece, with no tolerance anywhere in the statement.
     */
    @Test
    fun thePlanProjectionIsTheChainThroughTheBasePoints() {
        val ed = Editor()
        val pts = threePoints(ed)
        val curve = curveThrough(ed, view(ed), pts)

        val plan = Curves3.projectedOnto(pathOf(curve), Plane3(Vec3.ZERO, Vec3.X, Vec3.Y))
        assertEquals(2, plan.size, "one projected piece per piece")
        for (i in 0 until 2) {
            val seg = assertNotNull(plan[i] as? constructit.geom.ProfileElement.Seg, "a projected run is a segment")
            assertEquals(pts[i].first, seg.segment.a, "piece $i starts at the base of point $i")
            assertEquals(pts[i + 1].first, seg.segment.b, "and ends at the base of point ${i + 1}")
        }
    }

    /**
     * …and it is **drawn** there: the plan of a smooth curve over three height points, as an SVG golden.
     *
     * The picture is the assertion that the projection reaches the canvas at all — the coordinates are pinned
     * exactly by the test above, and this one pins that they are emitted, in the right style, as the chain
     * the coalescing makes of them (session 35) rather than as a polyline per piece.
     */
    @Test
    fun theProjectedCurveIsDrawnInThePlan() {
        val ed = Editor()
        // a smaller L than the other fixtures', so the whole picture fits the 800 x 600 canvas at the
        // default zoom — a golden nobody can look at whole is a golden nobody inspects
        heightPoint(ed, -60.0, -40.0, "40")
        heightPoint(ed, 40.0, -40.0, "20")
        heightPoint(ed, 40.0, 50.0, "70")
        val pts = listOf(Vec2(-60.0, -40.0) to 40.0, Vec2(40.0, -40.0) to 20.0, Vec2(40.0, 50.0) to 70.0)
        val vp = view(ed)
        curveThrough(ed, vp, pts, Tools.CURVE3_SMOOTH)
        toPlan(vp)
        ed.setTool(Tools.SELECT)
        val target = SvgDrawTarget()
        ed.render(target)
        Golden.check("space_curve_plan", target.svg())
    }

    /**
     * **…and the 3D view draws it where it is.** The curve is part of the *scene* rather than of the editor's
     * overlay ([CurveItem]), so this asks the scene's own renderer: every chord the painter's projector lays
     * down in the curve's colour is a chord of the curve's own polyline, and every one of them is drawn.
     */
    @Test
    fun theCurveIsDrawnInTheThreeDViewAsTheChordsItIsMadeOf() {
        val ed = Editor()
        val pts = threePoints(ed)
        val vp = view(ed)
        val curve = curveThrough(ed, vp, pts)

        val scene = Scene3.extract(ed.doc)
        val item = assertNotNull(scene.curves.singleOrNull(), "the scene carries the curve")
        assertEquals(curve.id, item.elementId, "and says which element it is")
        assertEquals(Styles.SPACE_CURVE.stroke, item.color, "in the one colour both views ask for")

        val rec = Counting()
        vp.render(scene, rec)
        val proj = assertNotNull(vp.projection())
        val expected =
            item.points.map { p -> assertNotNull(proj.toScreenLifted(Vec2(p.x, p.y), p.z)) }
        // the painter emits a curve chord by chord, so the count is the polyline's own
        val drawn = rec.runs.filter { it.size == 2 && expected.any { e -> e == it[0] } && expected.any { e -> e == it[1] } }
        assertEquals(
            expected.size - 1,
            drawn.size,
            "every chord of the curve is drawn exactly once: ${drawn.size} of ${expected.size - 1}",
        )
    }

    /**
     * **A curve through ordinary 2D points lies in the plane they were drawn on** — the zero-lift reading a
     * loft's apex already makes of a plain point (OP-25), so a route can be drawn in the plan with no height
     * gesture at all, and the points stay draggable where they live.
     */
    @Test
    fun aCurveThroughPlainPlanPointsLiesInThePlanAndFollowsThem() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        for (at in listOf(Vec2(0.0, 0.0), Vec2(80.0, 0.0), Vec2(80.0, 60.0))) ed.click(at)
        ed.setTool(Tools.CURVE3)
        for (at in listOf(Vec2(0.0, 0.0), Vec2(80.0, 0.0), Vec2(80.0, 60.0))) ed.click(at)
        ed.key("Enter")
        val curve = assertNotNull(ed.curves().lastOrNull(), "the plan gesture built a curve: ${ed.statusHint}")

        val path = pathOf(curve)
        assertEquals(2, path.elements.size)
        for (p in Curves3.polyline(path)) assertClose(p.z, 0.0, 1e-12, "a curve through plan points is flat")
        assertVec(assertNotNull(path.start), Vec3(0.0, 0.0, 0.0), msg = "its first point")

        ed.drag(Vec2(0.0, 0.0), Vec2(-30.0, 20.0))
        assertVec(assertNotNull(pathOf(curve).start), Vec3(-30.0, 20.0, 0.0), msg = "and it follows the drag")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the zero lift costs the file nothing")
    }

    // ---- 6. pickable in both views, and the points still outrank it ----

    /**
     * **A click reaches the curve in the plan, on its projection** — where it is drawn, which is the whole
     * rule. Clicked between two of its points, so nothing else is within reach.
     */
    @Test
    fun aClickOnTheProjectionSelectsTheCurveInThePlan() {
        val ed = Editor()
        val pts = threePoints(ed)
        val vp = view(ed)
        val curve = curveThrough(ed, vp, pts)

        toPlan(vp)
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(60.0, 0.0)) // half way along the first piece's plan image
        assertEquals(curve, ed.selection, "the projected curve took the click: ${ed.statusHint}")
    }

    /**
     * **…and in the 3D view, where the curve really is.** The click is aimed at the middle of the first
     * piece — 60 mm along, 30 mm up, which is nowhere near either of its endpoints or their bases.
     */
    @Test
    fun aClickOnTheCurveSelectsItInTheThreeDView() {
        val ed = Editor()
        val pts = threePoints(ed)
        val vp = view(ed)
        val curve = curveThrough(ed, vp, pts)

        ed.setTool(Tools.SELECT)
        vp.clickAt(vp.atLifted(Vec2(60.0, 0.0), 30.0))
        assertEquals(curve, ed.selection, "the curve took the click in space: ${ed.statusHint}")
    }

    /**
     * **The points it runs through still outrank it** where the two overlap — the existing pick-cycle rule
     * (*a point cannot dodge, a curve can be clicked elsewhere*), verified rather than changed. One more
     * click steps the cycle on, so the curve under the point is still reachable.
     */
    @Test
    fun aPointOnTheCurveStillTakesTheClickAndTheCurveIsNextInTheCycle() {
        val ed = Editor()
        val pts = threePoints(ed)
        val vp = view(ed)
        val curve = curveThrough(ed, vp, pts)
        val corner = ed.doc.elements.first { it.kind == ElementKind.HEIGHT_POINT }

        ed.setTool(Tools.SELECT)
        val at = vp.atLifted(pts[0].first, pts[0].second)
        vp.clickAt(at)
        assertEquals(corner, ed.selection, "the point wins the first click: ${ed.statusHint}")
        // …and one more click steps the cycle onto the curve under it: reachable, never displaced
        vp.clickAt(at)
        assertEquals(curve, ed.selection, "the curve under it is next in the cycle: ${ed.statusHint}")
    }

    // ---- 7. the file, and the undo ----

    /** **`save -> load -> save` is byte-equal, and the reloaded curve is the same geometry.** */
    @Test
    fun aCurveSurvivesSaveAndLoadByteForByte() {
        val ed = Editor()
        val pts = threePoints(ed)
        val curve = curveThrough(ed, view(ed), pts, Tools.CURVE3_SMOOTH)
        val once = DocumentFormat.save(ed.doc)
        val doc = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(doc), "the script round-trips byte for byte")
        val back = doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        assertEquals(pathOf(curve), pathOf(back), "and the curve reloads as the same geometry")
    }

    /** **One gesture, one undo**: the whole run of clicks goes back together, and comes back with redo. */
    @Test
    fun oneUndoTakesTheWholeGestureBack() {
        val ed = Editor()
        val pts = threePoints(ed)
        curveThrough(ed, view(ed), pts)
        assertEquals(1, ed.curves().size)

        assertTrue(ed.undo(), "the curve is taken back")
        assertEquals(0, ed.curves().size, "one checkpoint covered the whole gesture")
        assertEquals(3, ed.doc.elements.count { it.kind == ElementKind.HEIGHT_POINT }, "and the points it ran through stay")
        assertTrue(ed.redo(), "and it comes back")
        assertEquals(1, ed.curves().size)
    }

    // ---- 8. the perf contract (session 35), with curves in the drawing ----

    /**
     * **One view-projection matrix per frame, curves included.** The 3D view's overlay projects the plan of
     * every element; a curve that built a matrix per point would be exactly the defect session 35 removed.
     */
    @Test
    fun aDrawingWithCurvesStillBuildsOneMatrixPerFrame() {
        val ed = Editor()
        val pts = threePoints(ed)
        val vp = view(ed)
        val curve = curveThrough(ed, vp, pts, Tools.CURVE3_SMOOTH)
        // selected, so the curve's own world polyline goes through the projection as well — the emphasis is
        // the one thing drawn in the 3D view through `toScreenLifted`, a second door into the same camera
        toPlan(vp)
        ed.setTool(Tools.SELECT)
        // the midpoint of the first span's plan image, computed from the scheme rather than guessed
        val mid = Curves3.bezierPointAt(pathOf(curve).elements.first() as Curve3Element.Bezier3, 0.5)
        ed.click(Vec2(mid.x, mid.y))
        assertEquals(curve, ed.selection, "the curve is the subject: ${ed.statusHint}")

        val proj = PlanePerspective(Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), Viewport3().camera, wPx, hPx)
        assertEquals(0, proj.matrixBuilds, "nothing drawn yet")
        ed.pointing = proj
        val rec = Counting()
        ed.draw(rec, wPx, hPx)
        assertTrue(rec.points > 50, "the curve's own polyline went through it: ${rec.points} points")
        assertEquals(1, proj.matrixBuilds, "one matrix for all of them")
    }

    /**
     * **An orbit and a hover upload nothing; a curve that changed uploads once; a rename uploads nothing.**
     * The session-35 gate, asked of the new carrier — the two ways an identity-keyed cache goes wrong.
     */
    @Test
    fun aCurveEntersTheUploadGateWithoutCostingAnOrbitOrAHoverAnything() {
        val ed = Editor()
        val pts = threePoints(ed)
        val vp = view(ed)
        val curve = curveThrough(ed, vp, pts)

        val sync = Scene3Sync()
        ed.onChange = { sync.update(Scene3.extract(ed.doc)) { } }
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(1, sync.uploads, "the first look uploads")
        assertEquals(1, Scene3.extract(ed.doc).curves.size, "and the curve is in the scene")

        repeat(20) { sync.update(Scene3.extract(ed.doc)) { } }
        assertEquals(1, sync.uploads, "an unchanged document is the same path object every time")

        ed.setTool(Tools.SEGMENT)
        for (i in 0 until 30) vp.pointerMove(Vec2(200.0 + i, 300.0 + i))
        assertEquals(1, sync.uploads, "a hover moves no vertex")

        vp.cameraModifier = true
        vp.pointerDown(Vec2(400.0, 300.0))
        for (i in 0 until 20) vp.pointerMove(Vec2(400.0 + i * 3, 300.0))
        vp.pointerUp(Vec2(460.0, 300.0))
        vp.cameraModifier = false
        assertEquals(1, sync.uploads, "an orbit uploads nothing")

        ed.doc.nameElement(curve, "kabelweg")
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(1, sync.uploads, "a name is not vertex data")

        val h = assertNotNull(ed.doc.scalars.firstOrNull { it.name == "height" })
        ed.doc.setParameter(h, 85.0.mm)
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(2, sync.uploads, "a curve that moved is new vertex data")

        assertEquals(1, ed.doc.setElementsVisible(listOf(curve), false), "hide it")
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(3, sync.uploads, "a curve that left the scene is a change the view must see")
        assertEquals(0, Scene3.extract(ed.doc).curves.size, "and it really left")
    }

    // ---- 9. every refusal speaks ----

    /** **Fewer than two points is not a curve**, and the tool says so instead of building nothing quietly. */
    @Test
    fun oneSinglePointIsRefusedByName() {
        val ed = Editor()
        val pts = threePoints(ed)
        val vp = view(ed)
        ed.setTool(Tools.CURVE3)
        vp.clickLifted(pts[0].first, pts[0].second)
        ed.key("Enter")
        assertEquals(0, ed.curves().size, "nothing was built")
        assertTrue(
            ed.statusHint.contains("needs at least 2 picks (point in space)"),
            "and it said why, in its own words: ${ed.statusHint}",
        )
    }

    /** **A closed curve needs three points**: two would double back on themselves, which is refused by name. */
    @Test
    fun aClosedCurveThroughTwoPointsIsRefusedByName() {
        val ed = Editor()
        val pts = threePoints(ed)
        val vp = view(ed)
        ed.setTool(Tools.CURVE3)
        vp.clickLifted(pts[0].first, pts[0].second)
        vp.clickLifted(pts[1].first, pts[1].second)
        vp.clickLifted(pts[0].first, pts[0].second)
        assertEquals(0, ed.curves().size, "nothing was built")
        assertTrue(ed.statusHint.contains("at least three points"), "and it said why: ${ed.statusHint}")
    }

    /** **The same point twice in a row** makes a piece with no direction — refused, and the point is named. */
    @Test
    fun theSamePointClickedTwiceInARowIsRefusedByName() {
        val ed = Editor()
        val pts = threePoints(ed)
        val vp = view(ed)
        ed.setTool(Tools.CURVE3)
        vp.clickLifted(pts[0].first, pts[0].second)
        vp.clickLifted(pts[1].first, pts[1].second)
        vp.clickLifted(pts[1].first, pts[1].second)
        ed.key("Enter")
        assertEquals(0, ed.curves().size, "nothing was built")
        assertTrue(ed.statusHint.contains("twice in a row"), "and it said which: ${ed.statusHint}")
    }

    /**
     * **A degenerate curve is invalid with a reason, not refused for ever** (OP-3): two *different* points
     * that happen to stand in the same place make the node invalid — and dragging one apart heals it, which
     * is why this is a value condition and not a build-time refusal.
     */
    @Test
    fun twoCoincidentPointsMakeTheCurveInvalidWithAReasonThatHeals() {
        val ed = Editor()
        heightPoint(ed, 0.0, 0.0, "40")
        // two points over **one** base at two heights: distinct nodes, distinct places, both clickable —
        // until one height is retyped onto the other
        heightPoint(ed, 90.0, 0.0, "30")
        heightPoint(ed, 90.0, 0.0, "80")
        val top = assertNotNull(ed.doc.scalars.lastOrNull(), "the third height is a panel row")
        val vp = view(ed)
        val curve =
            curveThrough(
                ed,
                vp,
                listOf(Vec2(0.0, 0.0) to 40.0, Vec2(90.0, 0.0) to 30.0, Vec2(90.0, 0.0) to 80.0),
            )
        assertTrue(Evaluator().eval(curve.ref.node) is EvalResult.Ok, "three distinct places make a curve")

        ed.doc.setParameter(top, 30.0.mm)
        val why = Evaluator().eval(curve.ref.node)
        assertTrue(why is EvalResult.Invalid, "a piece with no direction is invalid, not silently dropped: $why")
        assertTrue(
            (why as EvalResult.Invalid).reason.contains("same place"),
            "and the reason says what is wrong: ${why.reason}",
        )

        // …and it heals the moment they are pulled apart, which a build-time refusal could never do
        ed.doc.setParameter(top, 80.0.mm)
        assertTrue(Evaluator().eval(curve.ref.node) is EvalResult.Ok, "moving one of them brings the curve back")
    }

    /** A curve with no plane to be seen against is not pickable — and says nothing rather than guessing. */
    @Test
    fun aCurveIsNotPickableWithoutAPlaneToLookAlong() {
        val ed = Editor()
        val pts = threePoints(ed)
        val curve = curveThrough(ed, view(ed), pts)
        assertNull(
            constructit.editor.HitTest.distanceTo(Evaluator(), curve, Vec2(60.0, 0.0)),
            "a caller that offers no plane is asking a question this is no answer to",
        )
    }
}
