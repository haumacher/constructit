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
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.Curve3Element
import constructit.geom.Curves3
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.Path3
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Project3
import constructit.geom.Section3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Quantity
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Projection onto a face as a gesture** (OP-26, step 8) — the half that decides whether the step is a
 * feature or a mechanism.
 *
 * The geometry is [ProjectOnFaceTest]'s. What is asserted here is the doctrine and the composition: **two
 * ordinary picks and no third input**, because the direction is the drawing's own space; the face a drawing
 * lands on scored **once** and thereafter persisted as an index, never re-scored; every value failure
 * invalid *with a reason* and healing; and the run behaving like any other curve in space — swept,
 * stationed, joined, drawn and picked in both views, saved byte-equal, undone in one.
 */
class ProjectOnFaceToolTest {
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

    @Suppress("UNCHECKED_CAST")
    private fun runOf(el: Element): Path3 = Evaluator().path3(el.ref as Path3Ref)

    private fun reasonOf(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

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
                camera = Camera3(target = Vec3(50.0, 50.0, 15.0), distance = 520.0, yaw = -0.15, pitch = 0.45),
                widthPx = 800.0,
                heightPx = 600.0,
            )
        vp.editor = ed
        vp.shown = true
        return vp
    }

    /** The face index the step recorded — read out of the file, which is where a choice is stored (OP-18). */
    private fun storedFace(ed: Editor): Int {
        val line = assertNotNull(DocumentFormat.save(ed.doc).lines().lastOrNull { it.startsWith("tool ${Tools.PROJECT_ON_FACE}") })
        return assertNotNull(line.split(" ").firstOrNull { it.startsWith("signs=") }).removePrefix("signs=").toInt()
    }

    private fun Viewport3.atLifted(
        base: Vec2,
        lift: Double,
    ): Vec2 = assertNotNull(assertNotNull(projection()).toScreenLifted(base, lift), "the lifted point has an image")

    private fun noteOf(ed: Editor): String = ed.doc.note ?: ""

    private fun spaceCurves(ed: Editor): List<Element> = ed.doc.elements.filter { it.kind == ElementKind.SPACE_CURVE }

    @Suppress("UNCHECKED_CAST")
    private fun featureOf(el: Element): Feature3 = Evaluator().solid(el.ref as SolidRef).feature

    // ---- fixture 1: a plate, and a line drawn over it in the plan ----

    private class Plate(val ed: Editor, val solid: Element, val drawn: Element)

    /** A 100 × 100 plate 20 mm thick, and a segment drawn across the middle of it in the plan. */
    private fun plate(depth: String = "20"): Plate {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type(depth)
        ed.click(Vec2(50.0, 0.0))
        val solid = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID }, ed.statusHint)
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(20.0, 30.0))
        ed.click(Vec2(80.0, 70.0))
        val drawn = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SEGMENT }, ed.statusHint)
        return Plate(ed, solid, drawn)
    }

    /** The two clicks of the gesture: the drawing, then the body. */
    private fun project(
        f: Plate,
        onCurveAt: Vec2 = Vec2(50.0, 50.0),
        onSolidAt: Vec2 = Vec2(50.0, 0.0),
    ): Element {
        f.ed.setTool(Tools.PROJECT_ON_FACE)
        f.ed.click(onCurveAt)
        f.ed.click(onSolidAt)
        return assertNotNull(spaceCurves(f.ed).lastOrNull(), f.ed.statusHint)
    }

    // ---- fixture 2: the acceptance pyramid, whose flanks are inclined ----

    private class Pyramid(val ed: Editor, val solid: Element, val drawn: Element)

    /** The 100 × 100 pyramid, 90 mm to the apex, and a segment drawn over its **south** flank. */
    private fun pyramid(over: Pair<Vec2, Vec2> = Vec2(35.0, 10.0) to Vec2(65.0, 15.0)): Pyramid {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.type("90")
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(50.0, 50.0))
        val solid = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID }, ed.statusHint)
        ed.setTool(Tools.SEGMENT)
        ed.click(over.first)
        ed.click(over.second)
        val drawn = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SEGMENT }, ed.statusHint)
        return Pyramid(ed, solid, drawn)
    }

    // ---- 1. the gesture ----

    /**
     * **Two clicks and nothing else**: what is thrown, and what it is thrown at. The direction is never
     * stated because a space already is one — the plan looks straight down, so the line lands on the plate's
     * top face at exactly the plate's own thickness, and its plan projection *is* the line that was drawn.
     */
    @Test
    fun twoClicksThrowTheDrawingOntoTheFaceItLandsOn() {
        val f = plate()
        val curve = project(f)
        val path = runOf(curve)
        assertEquals(1, path.elements.size, "one drawn piece, one piece of run")
        val e = assertNotNull(path.elements[0] as? Curve3Element.Seg3, "a segment stays a segment")
        assertVec3(e.start, Vec3(20.0, 30.0, 20.0), 0.0, "exact, on the top face")
        assertVec3(e.end, Vec3(80.0, 70.0, 20.0), 0.0, "and at the far end")
        assertEquals(Document.PLAN_SPACE, curve.space, "the run belongs to the space it was drawn in")
        assertTrue(f.ed.statusHint.contains("top face"), "the note names the face it landed on: ${f.ed.statusHint}")
        assertTrue(f.ed.statusHint.contains("exact"), "…its exactness class: ${f.ed.statusHint}")
        assertTrue(f.ed.statusHint.contains("wholly on the face"), "…and that it landed on it: ${f.ed.statusHint}")
    }

    /** A curve drawn over a **pyramid's flank** lands on that flank, sloping with it. */
    @Test
    fun aDrawingOverAnInclinedFaceLandsOnTheSlope() {
        val f = pyramid()
        f.ed.setTool(Tools.PROJECT_ON_FACE)
        f.ed.click(Vec2(50.0, 12.5))
        f.ed.click(Vec2(50.0, 0.0))
        val curve = assertNotNull(spaceCurves(f.ed).lastOrNull(), f.ed.statusHint)
        val path = runOf(curve)
        val patch = assertNotNull(Section3.faces(featureOf(f.solid)).first)[storedFace(f.ed)]
        val plane = assertNotNull(patch.plane)
        for (el in path.elements) {
            assertClose(plane.distanceTo(el.start), 0.0, 1e-9, "in the flank's plane")
            assertClose(plane.distanceTo(el.end), 0.0, 1e-9, "…at both ends")
        }
        // the flank rises 90 mm over 50 mm of ground, so a point 10 mm in from the south edge stands at 18 mm
        assertClose(path.elements[0].start.z, 18.0, 1e-9, "on the slope, where the slope is: ${path.elements[0].start}")
    }

    // ---- 2. the face is a choice: scored once, then persisted ----

    /** The chosen face is written into the step as an ordinary `signs=`, and the file round-trips. */
    @Test
    fun theFaceIsPersistedAsASignAndTheFileRoundTrips() {
        val f = plate()
        val curve = project(f)
        val once = DocumentFormat.save(f.ed.doc)
        assertTrue(
            once.lines().any { it.startsWith("tool ${Tools.PROJECT_ON_FACE}") && it.contains("signs=5") },
            "the step records the face it chose, as an index into the body's own face order (OP-8): $once",
        )
        val doc = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(doc), "save -> load -> save is byte-equal")
        val back = doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        assertEquals(runOf(curve), runOf(back), "and the run reloads piece for piece")
    }

    /**
     * **The stored face holds after a move that would score differently** — the fillet's own regression
     * (OP-18: *"a choice is not state, so it is not re-read from the geometry"*), two features along.
     *
     * A line is drawn over the pyramid's **south** flank and thrown at it. Then the line is dragged over the
     * **north** flank, where a fresh scoring would land it on the other face — checked, so the probe means
     * something — and the reload still hands back the flank the user chose. What the run does then is the
     * step's recorded answer to running off a face: it lands in the chosen flank's *plane*, whole.
     */
    @Test
    fun theStoredFaceHoldsAfterTheDrawingHasMovedOverAnother() {
        val f = pyramid()
        f.ed.setTool(Tools.PROJECT_ON_FACE)
        f.ed.click(Vec2(50.0, 12.5))
        f.ed.click(Vec2(50.0, 0.0))
        val curve = assertNotNull(spaceCurves(f.ed).lastOrNull(), f.ed.statusHint)
        val chosen = storedFace(f.ed)

        f.ed.setTool(Tools.SELECT)
        f.ed.drag(Vec2(35.0, 10.0), Vec2(35.0, 90.0))
        f.ed.drag(Vec2(65.0, 15.0), Vec2(65.0, 85.0))

        val ev = Evaluator()
        val feature = featureOf(f.solid)
        val drawn = listOf(ProfileElement.Seg((ev.valueOf(f.drawn.ref) as SegmentValue).seg))
        val rescored = assertNotNull(Project3.landingFace(feature, drawn, Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)).first)
        assertTrue(rescored != chosen, "a fresh scoring would now prefer face $rescored over $chosen")

        val doc = DocumentFormat.load(DocumentFormat.save(f.ed.doc))
        val back = doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        assertEquals(runOf(curve), runOf(back), "the reload keeps the chosen face, and does not re-decide it")
        val plane = assertNotNull(assertNotNull(Section3.faces(feature).first)[chosen].plane)
        assertClose(plane.distanceTo(runOf(back).elements[0].start), 0.0, 1e-9, "and the run lies in that face's plane")
    }

    // ---- 3. running off the face is said, not refused ----

    /** A drawing that hangs over the edge is thrown whole, into the face's plane, and the drawing says so. */
    @Test
    fun aRunThatLeavesTheFaceIsBuiltAndTheNoteSaysSo() {
        val f = plate()
        f.ed.setTool(Tools.SELECT)
        f.ed.drag(Vec2(80.0, 70.0), Vec2(160.0, 70.0))
        val curve = project(f, onCurveAt = Vec2(90.0, 50.0))
        assertTrue(f.ed.statusHint.contains("runs off the face"), "the note says where it went: ${f.ed.statusHint}")
        assertTrue(f.ed.statusHint.contains("face's plane"), "…and what it did instead: ${f.ed.statusHint}")
        val path = runOf(curve)
        assertEquals(1, path.elements.size, "not clipped — one drawn piece is one piece of run")
        assertVec3(path.elements[0].end, Vec3(160.0, 70.0, 20.0), 0.0, "the far end is where the drawing put it")
    }

    // ---- 4. the refusals ----

    /**
     * **A face standing edge-on to the drawing is invalidity with a reason, and it heals** (OP-3) — the
     * degenerate direction, by name.
     *
     * A line drawn on an **upright** datum is thrown at the plate's top face (the index a replay hands back,
     * which is exactly how a stored choice arrives): the drawing's own normal lies *in* that face, so there is
     * no curve on it and the node says so. Retype the datum's angle and it comes straight back.
     */
    @Test
    fun aFaceEdgeOnToTheDrawingIsInvalidByNameAndHeals() {
        val f = plate()
        f.ed.setTool(Tools.SEGMENT)
        f.ed.click(Vec2(-20.0, 50.0))
        f.ed.click(Vec2(120.0, 50.0))
        f.ed.setTool(Tools.SKETCH_PLANE)
        f.ed.type("90")
        f.ed.click(Vec2(50.0, 50.0))
        assertTrue(f.ed.activeSpace.isDatum, "the upright datum opened: ${f.ed.statusHint}")
        f.ed.setTool(Tools.SEGMENT)
        f.ed.click(Vec2(-30.0, 10.0))
        f.ed.click(Vec2(30.0, 40.0))
        val onDatum = assertNotNull(f.ed.doc.elements.lastOrNull { it.kind == ElementKind.SEGMENT })

        // the face a replay would hand back: the plate's top, which an upright drawing looks along
        val curve = assertNotNull(f.ed.doc.projectOntoFace(onDatum, f.solid, listOf(5)), noteOf(f.ed))
        assertTrue(assertNotNull(reasonOf(curve)).contains("edge-on"), reasonOf(curve)!!)

        val angle = assertNotNull(f.ed.activeSpace.angle)
        f.ed.doc.setParameter(angle, Quantity.deg(50.0))
        assertEquals(null, reasonOf(curve), "tilt the space the drawing stands on and the run comes back")
        f.ed.doc.setParameter(angle, Quantity.deg(90.0))
        assertTrue(assertNotNull(reasonOf(curve)).contains("edge-on"), "…and it goes again")
    }

    /** A **mesh body** has no face to land on, and the gesture refuses by name, naming what does work. */
    @Test
    fun aMeshBodyIsRefusedByNameAndPointsAtWhatWorks() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(20.0, 0.0))
        ed.click(Vec2(60.0, 40.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(0.0, 40.0))
        ed.setTool(Tools.REVOLVE)
        ed.type("360")
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(0.0, 20.0))
        val solid = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID }, ed.statusHint)
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(30.0, 10.0))
        ed.click(Vec2(50.0, 30.0))
        val drawn = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SEGMENT })

        assertEquals(null, ed.doc.projectOntoFace(drawn, solid), "a revolve names no face")
        assertTrue(noteOf(ed).contains("revolved"), noteOf(ed))
        assertTrue(noteOf(ed).contains("Intersection curve"), "…and says what does work: ${noteOf(ed)}")
    }

    /** The two structural refusals: a pick that is not a curve, and a pick that is not a solid. */
    @Test
    fun theStructuralRefusalsSpeak() {
        val f = plate()
        assertEquals(null, f.ed.doc.projectOntoFace(f.solid, f.solid))
        assertTrue(noteOf(f.ed).contains("not a curve"), noteOf(f.ed))
        assertEquals(null, f.ed.doc.projectOntoFace(f.drawn, f.drawn))
        assertTrue(noteOf(f.ed).contains("not a solid"), noteOf(f.ed))

        f.ed.setTool(Tools.LINE)
        f.ed.click(Vec2(10.0, 90.0))
        f.ed.click(Vec2(90.0, 95.0))
        val line = assertNotNull(f.ed.doc.elements.lastOrNull { it.kind == ElementKind.LINE })
        assertEquals(null, f.ed.doc.projectOntoFace(line, f.solid))
        assertTrue(noteOf(f.ed).contains("runs on for ever"), noteOf(f.ed))
    }

    // ---- 5. it rides both parents ----

    /** Drag a point of the drawing and the engraving follows it — the run's first parent. */
    @Test
    fun theRunRidesTheDrawing() {
        val f = plate()
        val curve = project(f)
        f.ed.setTool(Tools.SELECT)
        f.ed.drag(Vec2(20.0, 30.0), Vec2(30.0, 40.0))
        assertVec3(runOf(curve).elements[0].start, Vec3(30.0, 40.0, 20.0), 1e-9, "the run followed the drawing")
    }

    /** Retype the body's thickness and the engraving rises with the face — the run's second parent. */
    @Test
    fun theRunRidesTheSolid() {
        val f = plate()
        val curve = project(f)
        f.ed.doc.setParameter(f.ed.doc.scalars.single { it.name == "depth" }, 55.0.mm)
        assertVec3(runOf(curve).elements[0].start, Vec3(20.0, 30.0, 55.0), 1e-9, "the top face rose, and took the run with it")
        f.ed.doc.setParameter(f.ed.doc.scalars.single { it.name == "depth" }, 20.0.mm)
        assertVec3(runOf(curve).elements[0].start, Vec3(20.0, 30.0, 20.0), 1e-9, "and back")
    }

    // ---- 6. it composes ----

    /** A tube swept along the engraving is an ordinary watertight solid. */
    @Test
    fun aTubeAlongTheProjectionIsWatertight() {
        val f = plate()
        val curve = project(f)
        f.ed.setTool(Tools.TUBE)
        f.ed.type("3")
        f.ed.click(Vec2(50.0, 50.0))
        val tube = assertNotNull(f.ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID }, f.ed.statusHint)
        assertTrue(tube !== f.solid, "a new solid: ${f.ed.statusHint}")
        @Suppress("UNCHECKED_CAST")
        assertManifold(Evaluator().solid(tube.ref as SolidRef).mesh, "tube along a projection")
        assertEquals(curve.space, tube.space, "and it belongs where the run does")
    }

    /** A station stands on the engraving, at a distance measured along it. */
    @Test
    fun aStationStandsOnTheProjection() {
        val f = plate()
        val curve = project(f)
        val length = Curves3.length(runOf(curve))
        f.ed.setTool(Tools.STATION)
        f.ed.type("${(length / 2.0).toInt()}")
        f.ed.click(Vec2(50.0, 50.0))
        assertTrue(f.ed.activeSpace.isStation, "the station opened: ${f.ed.statusHint}")
        val plane = Evaluator().plane(assertNotNull(f.ed.activeSpace.plane))
        assertClose(plane.origin.z, 20.0, 1e-9, "its origin sits on the run, which is on the face")
        assertClose(plane.normal.normalized().z, 0.0, 1e-9, "and it stands square across a run that does not climb")
    }

    /** A **connect** joins the engraving to another run, exactly as it joins any two curves in space. */
    @Test
    fun aProjectionJoinsToAnotherRunLikeAnyOther() {
        val f = plate()
        val projected = project(f)
        f.ed.setTool(Tools.SEGMENT)
        f.ed.click(Vec2(120.0, 90.0))
        f.ed.click(Vec2(160.0, 120.0))
        val other = assertNotNull(f.ed.doc.elements.lastOrNull { it.kind == ElementKind.SEGMENT })
        val second = assertNotNull(f.ed.doc.projectOntoFace(other, f.solid, listOf(5)), noteOf(f.ed))

        f.ed.setTool(Tools.CONNECT)
        f.ed.click(Vec2(80.0, 70.0))
        f.ed.click(Vec2(120.0, 90.0))
        val join = assertNotNull(spaceCurves(f.ed).lastOrNull(), f.ed.statusHint)
        assertTrue(join !== projected && join !== second, "a third run: ${f.ed.statusHint}")
        val b = assertNotNull(runOf(join).elements.single() as? Curve3Element.Bezier3)
        assertVec3(b.p0, runOf(projected).elements[0].end, 1e-9, "it starts where the engraving ends")
        assertVec3(b.p3, runOf(second).elements[0].start, 1e-9, "and ends where the other run begins")
    }

    // ---- 7. it is a curve like any other ----

    /** Drawn in the 3D view, and pickable in both — the standing rule that what is drawn can be reached. */
    @Test
    fun theRunIsDrawnAndPickedInBothViews() {
        val f = plate()
        val curve = project(f)
        assertEquals(1, Scene3.extract(f.ed.doc).curves.size, "the 3D view has it, in space")

        val vp = view(f.ed)
        f.ed.setTool(Tools.SELECT)
        val at = vp.atLifted(Vec2(35.0, 40.0), 20.0)
        // the plate's own outline draws through the same place, so the 3D canvas reaches the run by the
        // ordinary pick cycle, exactly as the 2D one does where two curves coincide
        val in3d = ArrayList<Element?>()
        repeat(3) {
            vp.pointerMove(at)
            vp.pointerDown(at)
            vp.pointerUp(at)
            in3d.add(f.ed.selection)
        }
        assertTrue(curve in in3d, "the 3D view took the click where the run honestly stands: ${f.ed.statusHint}")

        vp.shown = false
        f.ed.click(Vec2(600.0, 600.0))
        assertEquals(null, f.ed.selection, "empty space clears the selection first")
        // in the plan the run's projection *is* the curve it was thrown from, so it is reached by the
        // ordinary pick cycle — the same machinery two coincident curves already use
        val reached = ArrayList<Element?>()
        repeat(3) {
            f.ed.click(Vec2(50.0, 50.0))
            reached.add(f.ed.selection)
        }
        assertTrue(curve in reached, "and the plan's own canvas reached it: ${f.ed.statusHint}")
    }

    /** One undo takes the gesture back, and leaves the drawing and the body standing. */
    @Test
    fun oneUndoTakesTheGestureBack() {
        val f = plate()
        val before = f.ed.doc.elements.size
        project(f)
        assertTrue(f.ed.undo(), "the run is taken back")
        assertEquals(before, f.ed.doc.elements.size, "the run is gone")
        assertTrue(f.ed.doc.elements.any { it.id == f.drawn.id }, "the drawing is not")
        assertTrue(f.ed.doc.elements.any { it.id == f.solid.id }, "and neither is the body")
    }

    /** The whole gesture is a pure function of the drawing: the same two clicks give the same run. */
    @Test
    fun theSameDrawingGivesTheSameRun() {
        assertEquals(runOf(project(plate())), runOf(project(plate())), "deterministic")
    }

    /** The fixtures are solids in the sense OP-2 means — watertight, both of them. */
    @Test
    fun theFixturesAreWatertight() {
        @Suppress("UNCHECKED_CAST")
        assertManifold(Evaluator().solid(plate().solid.ref as SolidRef).mesh, "plate")
        @Suppress("UNCHECKED_CAST")
        assertManifold(Evaluator().solid(pyramid().solid.ref as SolidRef).mesh, "pyramid")
        assertTrue(Geom3.volume(Evaluator().solid(pyramid().solid.ref as SolidRef).mesh) > 0.0)
    }
}
