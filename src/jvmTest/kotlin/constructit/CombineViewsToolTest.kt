package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.SegmentValue
import constructit.dsl.Path3Ref
import constructit.dsl.SolidRef
import constructit.dsl.path3
import constructit.dsl.plane
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.Camera3
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Scene3
import constructit.editor.SvgDrawTarget
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.Curve3Element
import constructit.geom.Path3
import constructit.geom.Plane3
import constructit.geom.Segment
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Quantity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Combine two views as a gesture** (OP-26, step 5) — the half of the step that decides whether it is a
 * feature or a mechanism.
 *
 * The geometry is [CombineViewsTest]'s. What is asserted here is the claim the record makes about the step:
 * *it needs no new editing surface at all.* Both picks are ordinary sketch curves in ordinary spaces, so
 * everything that already makes a drawing live makes the run live — and the run **rides both parents**, which
 * is asserted by moving each of the four things it was built from (the plan drawing, the elevation drawing,
 * and the space either is drawn in) rather than by moving the run.
 *
 * The fixture is the classical one and the arithmetic is deliberately trivial, so that every number below can
 * be read rather than trusted: a 200 mm run in the plan at y = 40, a 2-in-5 grade in the elevation folded up
 * about the x axis, and therefore the straight run from (0, 40, 0) to (200, 40, 80).
 */
class CombineViewsToolTest {
    private val wPx = 800.0
    private val hPx = 600.0

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
        pointerMove(camera.worldToScreen(from))
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun runOf(el: Element): Path3 = Evaluator().path3(el.ref as Path3Ref)

    private fun assertVec3(
        actual: Vec3,
        expected: Vec3,
        tol: Double = 1e-9,
        msg: String = "",
    ) {
        assertClose(actual.x, expected.x, tol, "$msg (x)")
        assertClose(actual.y, expected.y, tol, "$msg (y)")
        assertClose(actual.z, expected.z, tol, "$msg (z)")
    }

    private fun view(ed: Editor): Viewport3 {
        val vp =
            Viewport3(
                camera = Camera3(target = Vec3(100.0, 40.0, 40.0), distance = 520.0, yaw = -0.15, pitch = 0.45),
                widthPx = wPx,
                heightPx = hPx,
            )
        vp.editor = ed
        vp.shown = true
        return vp
    }

    // ---- the fixture: a plan, an elevation, and the run they jointly state ----

    /** The name the elevation space was given when it was created. */
    private var elevationSpace = ""

    /**
     * Two drawings and one combine, entirely by clicking.
     *
     * A hinge along the x axis, an elevation space turned 90° out of the plan about it (so its own
     * coordinates are `(x, z)` — the drawing board's second view, folded up), the route drawn in each, and the
     * two combined. The first pick is the **plan**, which is what makes the run belong to the plan's space and
     * run the way the plan is drawn.
     */
    private class Fixture(
        val ed: Editor,
        val run: Element,
        val hinge: Element,
        val planCurve: Element,
        val elevationCurve: Element,
    ) {
        operator fun component1() = ed

        operator fun component2() = run
    }

    private fun routed(): Fixture {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(200.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 40.0))
        ed.click(Vec2(200.0, 40.0))

        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("90")
        ed.click(Vec2(100.0, 0.0))
        assertTrue(!ed.activeSpace.isPlan, "the elevation space opened: ${ed.statusHint}")
        elevationSpace = ed.activeSpace.name
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(200.0, 80.0))

        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE), "back to the plan for the first pick")
        ed.setTool(Tools.COMBINE_VIEWS)
        ed.click(Vec2(100.0, 40.0))
        assertTrue(ed.setActiveSpace(elevationSpace), "switch the sketch plane between the two clicks")
        assertTrue(ed.statusHint.contains("kept"), "the tool says its pick survived the switch: ${ed.statusHint}")
        ed.click(Vec2(100.0, 40.0))
        val run =
            assertNotNull(
                ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE },
                "the run was built: ${ed.statusHint}",
            )
        val segs = ed.doc.elements.filter { it.kind == ElementKind.SEGMENT }
        return Fixture(ed, run, segs[0], segs[1], segs[2])
    }

    /**
     * **The defining property, asserted through the gestures**: every sample of the run projects into each
     * space onto the curve drawn there.
     *
     * The same claim [CombineViewsTest] makes of the geometry, asked here of whatever the drawing has become
     * after an edit — which is what "it rides its parents" has to mean if it is to mean anything.
     */
    private fun assertProjectsOntoBothDrawings(f: Fixture) {
        val ev = Evaluator()
        val a = (assertNotNull(ev.valueOf(f.planCurve.ref)) as SegmentValue).seg
        val b = (assertNotNull(ev.valueOf(f.elevationCurve.ref)) as SegmentValue).seg
        val plan = Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)
        val elev = elevationPlane(f.ed)
        val path = runOf(f.run)
        for (i in 0..40) {
            val t = i / 40.0
            val piece = path.elements.single() as Curve3Element.Seg3
            val p = piece.start + (piece.end - piece.start) * t
            assertTrue(distanceTo(plan.toLocal(p), a) <= 1e-9, "the run's plan projection is off the plan drawing at t=$t")
            assertTrue(distanceTo(elev.toLocal(p), b) <= 1e-9, "the run's elevation projection is off the elevation at t=$t")
        }
    }

    private fun distanceTo(
        p: Vec2,
        s: Segment,
    ): Double {
        val d = s.b - s.a
        val t = ((p - s.a).dot(d) / d.dot(d)).coerceIn(0.0, 1.0)
        return (p - (s.a + d * t)).length()
    }

    private fun elevationPlane(ed: Editor): Plane3 =
        Evaluator().plane(assertNotNull(assertNotNull(ed.doc.spaceNamed(elevationSpace)).plane))

    // ---- 1. the gesture, and what it makes ----

    /**
     * **Two clicks in two spaces, and nothing else** — no scalar, no discrete choice, no new kind of pick.
     * What comes out is a curve in space like any other, and here it is the exact segment the two straight
     * drawings state.
     */
    @Test
    fun theGestureIsTwoOrdinaryCurvePicksInTwoSpaces() {
        val (ed, run) = routed()
        val piece = assertNotNull(runOf(run).elements.single() as? Curve3Element.Seg3, "one straight piece")
        assertVec3(piece.start, Vec3(0.0, 40.0, 0.0), msg = "the run starts where both drawings start")
        assertVec3(piece.end, Vec3(200.0, 40.0, 80.0), msg = "and ends where both of them end")
        assertEquals(Document.PLAN_SPACE, run.space, "the run belongs to the first view's space, as a loft does")
        assertTrue(ed.statusHint.contains("move either drawing"), "and the status says what it rides: ${ed.statusHint}")
    }

    // ---- 2. it rides both parents, and both their spaces ----

    /** **Drag a point of the plan and the run follows** — the first of the four things it is built from. */
    @Test
    fun theRunRidesThePlanDrawing() {
        val (ed, run) = routed()
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(200.0, 40.0), Vec2(200.0, 90.0))
        assertVec3(assertNotNull(runOf(run).end), Vec3(200.0, 90.0, 80.0), msg = "the run's far end came with the plan point")
        assertVec3(assertNotNull(runOf(run).start), Vec3(0.0, 40.0, 0.0), msg = "and the near end stayed put")
    }

    /** **Drag a point of the elevation and the run follows too** — the second, and it is a different drawing. */
    @Test
    fun theRunRidesTheElevationDrawing() {
        val (ed, run) = routed()
        assertTrue(ed.setActiveSpace(elevationSpace))
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(200.0, 80.0), Vec2(200.0, 130.0))
        assertVec3(assertNotNull(runOf(run).end), Vec3(200.0, 40.0, 130.0), msg = "the run rose with the elevation")
    }

    /**
     * **Tilt the space the elevation is drawn in, and the run follows that too** — which is the parenting rule
     * doing what it exists for (OP-26): the second view is a drawing *in a space*, and a space is a node.
     *
     * Asserted by the defining property rather than by a coordinate: after the datum is turned from 90° to
     * 60°, the run's projection into the **new** elevation plane is still the curve drawn there.
     */
    @Test
    fun theRunRidesTheSpaceItsElevationIsDrawnIn() {
        val (ed, run) = routed()
        val before = assertNotNull(runOf(run).end)
        val angle = assertNotNull(assertNotNull(ed.doc.spaceNamed(elevationSpace)).angle, "the datum's angle is a parameter")
        ed.doc.setParameter(angle, Quantity.deg(60.0))
        val after = assertNotNull(runOf(run).end)
        assertTrue((after - before).length() > 1.0, "the run moved with the space: $before -> $after")

        // the elevation drawing is unchanged in its own coordinates, so the run must still project onto it
        val plane = elevationPlane(ed)
        val local = plane.toLocal(after)
        assertClose(local.x, 200.0, 1e-9, "…at the far end of the drawn elevation")
        assertClose(local.y, 80.0, 1e-9, "…which is still 2 in 5 in that space's own coordinates")
        assertClose(assertNotNull(runOf(run).end).y * 0.0, 0.0, 1e-12)
        // …and the plan is untouched, so the run still stands over the plan drawing
        assertClose(after.y, 40.0, 1e-9, "the plan says y = 40 wherever the elevation has gone")
    }

    /**
     * **Move the line the elevation space stands on, and the run follows that too.** A datum is hinged on a
     * drawn line, so dragging that line's end turns the whole space — the drawing on it and the run with it —
     * and the run is still, exactly, the curve whose projection into each space is the drawing made there.
     */
    @Test
    fun theRunRidesTheHingeTheElevationSpaceStandsOn() {
        val f = routed()
        assertProjectsOntoBothDrawings(f)
        val before = assertNotNull(runOf(f.run).end)

        assertTrue(f.ed.setActiveSpace(Document.PLAN_SPACE))
        f.ed.setTool(Tools.SELECT)
        f.ed.drag(Vec2(200.0, 0.0), Vec2(200.0, 60.0))
        val after = assertNotNull(runOf(f.run).end)
        assertTrue((after - before).length() > 1.0, "the run moved with the space's hinge: $before -> $after")
        assertProjectsOntoBothDrawings(f)
    }

    // ---- 3. it composes: a run is a run ----

    /** **A tube swept along a combined run is an ordinary watertight solid.** */
    @Test
    fun aTubeAlongACombinedRunIsWatertight() {
        val (ed, _) = routed()
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.setTool(Tools.TUBE)
        ed.type("6")
        ed.click(Vec2(100.0, 40.0))
        val tube = assertNotNull(ed.solids().lastOrNull(), "the tube was built: ${ed.statusHint}")

        @Suppress("UNCHECKED_CAST")
        val solid = Evaluator().solid(tube.ref as SolidRef)
        assertManifold(solid.mesh, "a tube along a combined run")
        assertTrue(solid.feature.footprint.isNotEmpty(), "and it shows a plan footprint like any other solid")
    }

    /**
     * **A station stands on a combined run** like on any other — which is the measure of the step: nothing
     * downstream learns that this curve was made from two drawings.
     *
     * The run is 200 mm along x and 80 mm up, so it is `sqrt(200² + 80²)` = 215.407 mm long; a station at
     * 107.703 mm is its midpoint.
     */
    @Test
    fun aStationStandsOnACombinedRun() {
        val (ed, _) = routed()
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.setTool(Tools.STATION)
        ed.type("107.7033")
        ed.click(Vec2(100.0, 40.0))
        assertTrue(ed.activeSpace.isStation, "the station opened: ${ed.statusHint}")
        val p = Evaluator().plane(assertNotNull(ed.activeSpace.plane))
        assertVec3(p.origin, Vec3(100.0, 40.0, 40.0), 1e-3, "halfway along the run")
        assertVec3(p.normal.normalized(), Vec3(200.0, 0.0, 80.0).normalized(), 1e-6, "facing the way it goes")
    }

    // ---- 4. both views draw it, and the pointer reaches it ----

    /**
     * **Drawn in the 3D view and in the 2D canvas, and clickable in both.**
     *
     * The plan is the one place where a combined run is bound to *coincide* with something else — its
     * projection into a parent space **is** the curve drawn there, by definition — so the click that reaches
     * it in the plan is the second one of the ordinary pick cycle, which is exactly the machinery two
     * overlapping curves already use.
     */
    @Test
    fun theRunIsDrawnAndPickableInBothViews() {
        val f = routed()
        val ed = f.ed
        val run = f.run
        assertEquals(1, Scene3.extract(ed.doc).curves.size, "the 3D view has the curve, in space")

        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE), "the 3D view points at the working plane")
        val vp = view(ed)
        ed.setTool(Tools.SELECT)
        val piece = runOf(run).elements.single() as Curve3Element.Seg3
        val on = piece.start + (piece.end - piece.start) * 0.35
        vp.clickAt(vp.atLifted(Vec2(on.x, on.y), on.z))
        assertEquals(run, ed.selection, "the 3D view took the click where the run honestly stands: ${ed.statusHint}")

        vp.shown = false
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.click(Vec2(600.0, 600.0))
        assertEquals(null, ed.selection, "empty space clears the selection first")
        val reached = ArrayList<Element?>()
        ed.click(Vec2(80.0, 40.0))
        reached.add(ed.selection)
        ed.click(Vec2(80.0, 40.0))
        reached.add(ed.selection)
        assertTrue(run in reached, "and the plan reached it too, on its turn of the cycle: ${ed.statusHint}")
        assertTrue(f.planCurve in reached, "with the drawing it coincides with on the other turn")
    }

    private fun Viewport3.clickAt(screen: Vec2) {
        pointerMove(screen)
        pointerDown(screen)
        pointerUp(screen)
    }

    private fun Viewport3.atLifted(
        base: Vec2,
        lift: Double,
    ): Vec2 = assertNotNull(assertNotNull(projection()).toScreenLifted(base, lift), "the lifted point has an image")

    // ---- 5. the file, and the undo ----

    /** **`save → load → save` is byte-equal**, and the run comes back where it was — from the two views alone. */
    @Test
    fun theRunSurvivesSaveAndLoadByteForByte() {
        val (ed, run) = routed()
        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.lines().any { it.startsWith("tool ${Tools.COMBINE_VIEWS}") }, "the step records the tool id: $once")
        val doc = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(doc), "the script round-trips byte for byte")
        val back = doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        assertEquals(runOf(run), runOf(back), "and the run reloads piece for piece")
    }

    /** **One gesture, one undo** — and the two drawings it was built from stay, because they are not part of it. */
    @Test
    fun oneUndoTakesTheGestureBack() {
        val (ed, _) = routed()
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE })
        assertTrue(ed.undo(), "the run is taken back")
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE }, "one checkpoint covered the gesture")
        assertEquals(3, ed.doc.elements.count { it.kind == ElementKind.SEGMENT }, "and both drawings, and the hinge, stay")
        assertTrue(ed.redo(), "and it comes back")
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE })
    }

    // ---- 6. the refusals ----

    /**
     * **Two views in one space are parallel spaces**, and that is node invalidity rather than a refused
     * gesture: which space a drawing is in is a *value* the drawing carries, so refusing the click would make
     * a replay depend on one.
     */
    @Test
    fun twoViewsInOneSpaceAreRefusedByTheNodeAsParallel() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(200.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 40.0))
        ed.click(Vec2(200.0, 90.0))
        ed.setTool(Tools.COMBINE_VIEWS)
        ed.click(Vec2(100.0, 0.0))
        ed.click(Vec2(100.0, 65.0))
        val run = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)
        val why = (Evaluator().eval(run.ref.node) as? EvalResult.Invalid)?.reason
        assertTrue(why?.contains("parallel") == true, "the node names it: $why")
        assertEquals(0, Scene3.extract(ed.doc).curves.size, "and an invalid run draws nothing")
    }

    /**
     * **Dragging the elevation off the plan's run hides everything built on it, and dragging it back heals**
     * (OP-3) — the ranges no longer overlap, the reason says both of them, and the tube comes back with it.
     */
    @Test
    fun anElevationDraggedOutOfRangeHidesTheRunAndComesBack() {
        val (ed, run) = routed()
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.setTool(Tools.TUBE)
        ed.type("6")
        ed.click(Vec2(100.0, 40.0))
        val tube = assertNotNull(ed.solids().lastOrNull(), "the tube: ${ed.statusHint}")
        assertEquals(1, Scene3.extract(ed.doc).solids.size)

        assertTrue(ed.setActiveSpace(elevationSpace))
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(0.0, 0.0), Vec2(400.0, 0.0))
        ed.drag(Vec2(200.0, 80.0), Vec2(600.0, 80.0))
        val why = (Evaluator().eval(run.ref.node) as? EvalResult.Invalid)?.reason
        assertTrue(why?.contains("do not describe the same run") == true, "the node names it: $why")
        assertTrue(why?.contains("400 to 600") == true, "and states the second view's range: $why")
        assertTrue(Evaluator().eval(tube.ref.node) is EvalResult.Invalid, "and the tube hides with it")

        ed.drag(Vec2(400.0, 0.0), Vec2(0.0, 0.0))
        ed.drag(Vec2(600.0, 80.0), Vec2(200.0, 80.0))
        assertTrue(Evaluator().eval(run.ref.node) is EvalResult.Ok, "and it heals")
        assertEquals(1, Scene3.extract(ed.doc).solids.size, "with the tube back in the view")
    }

    /** A view that **runs on for ever** — a line, a ray — states no length of run, and is refused by name. */
    @Test
    fun aViewThatRunsOnForEverIsRefusedByName() {
        val ed = Editor()
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(200.0, 40.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 60.0))
        ed.click(Vec2(200.0, 60.0))
        val line = ed.doc.elements.last { it.kind == ElementKind.LINE }
        val seg = ed.doc.elements.last { it.kind == ElementKind.SEGMENT }
        assertEquals(null, ed.doc.combineViews(line, seg), "nothing is built")
        assertTrue(ed.doc.note?.contains("runs on for ever") == true, "and the refusal names it: ${ed.doc.note}")
        assertEquals(null, ed.doc.combineViews(seg, seg), "and one drawing is one view")
        assertTrue(ed.doc.note?.contains("clicked twice") == true, "named too: ${ed.doc.note}")
    }

    // ---- 7. the picture ----

    /**
     * **…and it is drawn**: the plan of a run combined from a curved plan and a straight elevation, as an SVG
     * golden. What it pins is that the run's plan projection is the plan drawing itself — which is the
     * defining property, seen.
     */
    @Test
    fun theCombinedRunsPlanProjectionIsDrawn() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(200.0, 0.0))
        ed.setTool(Tools.BEZIER)
        ed.click(Vec2(0.0, 30.0))
        ed.click(Vec2(60.0, 110.0))
        ed.click(Vec2(140.0, -30.0))
        ed.click(Vec2(200.0, 50.0))
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("90")
        ed.click(Vec2(100.0, 0.0))
        val elevation = ed.activeSpace.name
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(200.0, 60.0))
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.setTool(Tools.COMBINE_VIEWS)
        ed.click(Vec2(48.1, 55.6))
        assertTrue(ed.setActiveSpace(elevation))
        ed.click(Vec2(100.0, 30.0))
        val run = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)
        assertNotNull(runOf(run).elements.single() as? Curve3Element.Bezier3, "a cubic plan against a straight elevation is exact")

        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.setTool(Tools.SELECT)
        val target = SvgDrawTarget()
        ed.render(target)
        Golden.check("combine_views_plan", target.svg())
    }
}
