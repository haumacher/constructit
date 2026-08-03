package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PlaneValue
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Camera3
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.SketchSpace
import constructit.editor.SvgDrawTarget
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.Geom3
import constructit.geom.MeshBool
import constructit.geom.Plane3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The station as a gesture, and as an ordinary sketch space** (OP-26, step 4).
 *
 * The geometry is [StationTest]'s; this is the other half of the claim, and it is the half that decides
 * whether step 4 is a feature or a mechanism: *a station is the same kind of thing a datum plane is, so
 * nothing downstream learns a new concept.* So everything here is asserted through the ordinary gestures —
 * draw in it, extrude off it, cut with it, place a second one from the same parameter, save it, undo it —
 * and none of it through anything a station brought with it.
 *
 * The one thing that is genuinely new is the parenting: a station **rides its path**, so moving a point the
 * curve runs through carries the plane and everything drawn on it along. That is the point of the feature and
 * it is asserted by moving the point, not the plane.
 */
class StationToolTest {
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
    private fun Editor.meshOf(el: Element) = Evaluator().solid(el.ref as SolidRef).mesh

    private fun Editor.invalid(el: Element): Boolean = Evaluator().eval(el.ref.node) !is EvalResult.Ok

    private fun planeOf(
        ed: Editor,
        space: SketchSpace,
    ): Plane3? =
        ((Evaluator().eval(assertNotNull(space.plane).node) as? EvalResult.Ok)?.value as? PlaneValue)?.plane

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

    private fun requireEngine() =
        assumeTrue(
            MeshBool.available,
            "a cut across a run is cross-axis and needs the general boolean engine (Manifold, OP-9): ${MeshBool.status}",
        )

    // ---- the fixture: a run in the plan, and a station across it ----

    /**
     * A straight run of 300 mm along +X through two plan points, as a curve in space.
     *
     * Plain 2D points, lifted by a **zero** height onto the plan (step 1's own rule for a `POINT3` slot), so
     * the whole fixture is drawn with two clicks and the geometry is arithmetic: a station `d` mm along it
     * stands at `(d, 0, 0)` facing +X, its own u axis is world +Z (the plan's normal, square to the run) and
     * its v is −Y.
     */
    private fun run300(ed: Editor): Element {
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(300.0, 0.0))
        ed.setTool(Tools.CURVE3)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(300.0, 0.0))
        ed.key("Enter")
        return assertNotNull(
            ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE },
            "the run was drawn: ${ed.statusHint}",
        )
    }

    /** Arm *Station*, type [mm] and click the run — the whole gesture, one number and one click. */
    private fun Editor.stationOn(
        curve: Element,
        mm: String,
        at: Vec2 = Vec2(150.0, 0.0),
    ): SketchSpace {
        setTool(Tools.STATION)
        type(mm)
        click(at)
        assertTrue(doc.activeSpace.isStation, "the station opened: $statusHint")
        assertNotNull(curve, "on the run that was clicked")
        return doc.activeSpace
    }

    // ---- 1. the gesture, and what it makes ----

    /**
     * **One number and one click**, and what comes out is a sketch space: origin on the curve, normal along
     * it, axes the moving frame's.
     */
    @Test
    fun aDistanceAndOneClickStandAPlaneAcrossTheRun() {
        val ed = Editor()
        val curve = run300(ed)
        val space = ed.stationOn(curve, "120")

        assertEquals("station1", space.name, "a station's own series of names")
        assertTrue(space.isStation)
        assertTrue(space.isDatum, "a plane that is a face of nothing")
        assertFalse(space.isFace, "and not a face")
        assertEquals(curve, space.station, "it knows the run it stands across")

        val p = assertNotNull(planeOf(ed, space))
        assertVec3(p.origin, Vec3(120.0, 0.0, 0.0), msg = "the origin is 120 mm along the run")
        assertVec3(p.normal, Vec3.X, 1e-9, "the normal is the direction the run goes")
        assertVec3(p.u, Vec3.Z, 1e-9, "u is the transported reference — the plan's normal, square to the run")
        assertVec3(p.v, Vec3(0.0, -1.0, 0.0), 1e-9, "and v completes the frame")
        assertTrue(ed.statusHint.contains("station across"), "the view says where it is: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("120"), ed.statusHint)
        assertTrue(ed.doc.spaceLabel(space).contains("120 mm along"), ed.doc.spaceLabel(space))
    }

    /** **The distance is what the tool waits for** — a station is *stated* by it, so it has no default. */
    @Test
    fun theToolWaitsForTheDistance() {
        val ed = Editor()
        val curve = run300(ed)
        ed.setTool(Tools.STATION)
        ed.click(Vec2(150.0, 0.0))
        assertFalse(ed.doc.activeSpace.isStation, "no number, no station: ${ed.statusHint}")
        assertNotNull(curve)
        ed.type("40")
        assertTrue(ed.doc.activeSpace.isStation, "and the number finishes it: ${ed.statusHint}")
        assertVec3(assertNotNull(planeOf(ed, ed.doc.activeSpace)).origin, Vec3(40.0, 0.0, 0.0))
    }

    /** **A pick that is not a curve in space is refused by name**, and nothing is built. */
    @Test
    fun aPickThatIsNotACurveInSpaceIsRefusedByName() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.STATION)
        ed.type("40")
        ed.click(Vec2(50.0, 0.0))
        assertFalse(ed.doc.activeSpace.isStation, "no station on a plain segment")
        assertEquals(1, ed.doc.spaces.size, "and no space was created either")
    }

    // ---- 2. it is a space: what you draw there is where the station is ----

    /**
     * **Draw in it and what you draw is where the station is.** A circle at the station's own origin becomes
     * a cylinder standing on the run and pointing along it — through the ordinary *Circle* and *Extrude*
     * gestures, with nothing about a station in either.
     */
    @Test
    fun aCircleDrawnOnTheStationExtrudesIntoATubeAlongTheRun() {
        val ed = Editor()
        val curve = run300(ed)
        ed.stationOn(curve, "120")

        ed.setTool(Tools.CIRCLE_R)
        ed.type("8")
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("20")
        ed.click(Vec2(8.0, 0.0))
        val solid = assertNotNull(ed.solids().lastOrNull(), "the boss was built: ${ed.statusHint}")

        val mesh = ed.meshOf(solid)
        assertManifold(mesh, "the boss on a station")
        // a circle of radius 8 extruded 20 mm — the tessellated volume is a hair under πr²h
        assertClose(Geom3.volume(mesh), PI * 64.0 * 20.0, 20.0, "a cylinder of radius 8 and depth 20")
        val lo = mesh.vertices.minOf { it.x }
        val hi = mesh.vertices.maxOf { it.x }
        assertClose(lo, 120.0, 1e-6, "it stands on the station, 120 mm along the run")
        assertClose(hi, 140.0, 1e-6, "and grows 20 mm along the run's own direction")
        // the section is tessellated, so its extent is the polygon's rather than the circle's
        assertClose(mesh.vertices.maxOf { it.z }, 8.0, 0.01, "with the circle's radius standing up out of the run")
        assertClose(mesh.vertices.maxOf { it.y }, 8.0, 0.01, "and across it")
    }

    /**
     * **A cut from a station takes material out of the part it passes through** — the mitre, the notch, the
     * gland: the reason to want a plane square to a run at all.
     *
     * The part is resolved and **recorded** at creation, exactly as a plane at a height resolves its own
     * (GitHub #9): the newest visible solid this plane passes through.
     */
    @Test
    fun aCutFromAStationTakesMaterialOutOfThePartItPassesThrough() {
        requireEngine()
        val ed = Editor()
        // a block from (100, -40) to (200, 40), 60 mm tall
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(100.0, -40.0))
        ed.click(Vec2(200.0, 40.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("60")
        ed.click(Vec2(150.0, -40.0))
        val block = assertNotNull(ed.solids().lastOrNull(), "the block was built: ${ed.statusHint}")
        val before = Geom3.volume(ed.meshOf(block))
        assertClose(before, 100.0 * 80.0 * 60.0, 1.0, "a 100 x 80 x 60 block")

        val curve = run300(ed)
        val space = ed.stationOn(curve, "180", at = Vec2(280.0, 0.0))
        assertEquals(block, space.anchor, "the station knows the part it passes through, resolved once")

        ed.setTool(Tools.CIRCLE_R)
        ed.type("10")
        ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.CUT)
        ed.type("70")
        ed.click(Vec2(40.0, 0.0))
        val cut = assertNotNull(ed.solids().lastOrNull(), "the cut was made: ${ed.statusHint}")
        assertTrue(cut !== block, "and it is a new tip of the part's feature chain")
        val mesh = ed.meshOf(cut)
        assertManifold(mesh, "the part after a cut normal to the run")
        assertTrue(
            Geom3.volume(mesh) < before - 1000.0,
            "the bore took material out: ${Geom3.volume(mesh)} vs $before",
        )
    }

    // ---- 3. it rides its path ----

    /**
     * **The station and everything on it ride the run** — the parenting rule, and the whole point of the
     * feature. Nothing here touches the plane: the *point the curve runs through* is dragged, two
     * constructions away, and the plane, the sketch on it and the solid built from it all follow.
     */
    @Test
    fun movingAPointTheRunPassesThroughCarriesTheStationAndEverythingOnIt() {
        val ed = Editor()
        val curve = run300(ed)
        val space = ed.stationOn(curve, "120")
        ed.setTool(Tools.CIRCLE_R)
        ed.type("8")
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("20")
        ed.click(Vec2(8.0, 0.0))
        val solid = assertNotNull(ed.solids().lastOrNull(), "the boss: ${ed.statusHint}")
        assertClose(ed.meshOf(solid).vertices.minOf { it.x }, 120.0, 1e-6)

        // …and now turn the run: drag its far point from (300, 0) round to (0, 300)
        ed.setActiveSpace(Document.PLAN_SPACE)
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(300.0, 0.0), Vec2(0.0, 300.0))
        assertNotNull(curve, "the run is still the run")

        val p = assertNotNull(planeOf(ed, space), "the station still stands")
        assertVec3(p.origin, Vec3(0.0, 120.0, 0.0), msg = "120 mm along the run, which now goes north")
        assertVec3(p.normal, Vec3.Y, 1e-9, "facing the way the run now goes")
        val mesh = ed.meshOf(solid)
        assertManifold(mesh, "the boss after the run turned")
        assertClose(mesh.vertices.minOf { it.y }, 120.0, 1e-6, "and the solid went with it, in one recompute")
        assertClose(mesh.vertices.maxOf { it.y }, 140.0, 1e-6)
    }

    /** **Retyping the distance slides the station along the run**, taking everything drawn on it. */
    @Test
    fun retypingTheDistanceSlidesTheStationAndItsSketch() {
        val ed = Editor()
        val curve = run300(ed)
        val space = ed.stationOn(curve, "120")
        ed.setTool(Tools.CIRCLE_R)
        ed.type("8")
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("20")
        ed.click(Vec2(8.0, 0.0))
        val solid = assertNotNull(ed.solids().lastOrNull())

        val distance = assertNotNull(space.along, "the distance is a panel parameter")
        ed.doc.setParameter(distance, 250.0.mm)
        assertVec3(assertNotNull(planeOf(ed, space)).origin, Vec3(250.0, 0.0, 0.0), msg = "the plane slid")
        assertClose(ed.meshOf(solid).vertices.minOf { it.x }, 250.0, 1e-6, "and so did what was drawn on it")
    }

    // ---- 4. two stations from one parameter ----

    /**
     * **Two stations driven by one parameter move together** — sharing *is* equality, so there was nothing to
     * build for this.
     *
     * The editor's way of saying it is wiring one parameter to another; the *sum* — `base + d`, a station a
     * stated distance along from another — is the same thing one node further and is asserted in
     * [StationTest.twoStationsFromOneParameterMoveTogether], since OP-7's textual expressions are not built
     * yet and the graph is where the expression lives.
     */
    @Test
    fun twoStationsWiredToOneParameterMoveTogether() {
        val ed = Editor()
        val curve = run300(ed)
        val first = ed.stationOn(curve, "60")
        ed.setActiveSpace(Document.PLAN_SPACE)
        val second = ed.stationOn(curve, "200")
        assertEquals("station2", second.name, "the second station in the series")

        val base = assertNotNull(first.along)
        val other = assertNotNull(second.along)
        assertTrue(ed.doc.wireParameter(other, base), "the second station's distance follows the first's")
        assertVec3(assertNotNull(planeOf(ed, second)).origin, Vec3(60.0, 0.0, 0.0), msg = "so it went to 60")

        ed.doc.setParameter(base, 175.0.mm)
        assertVec3(assertNotNull(planeOf(ed, first)).origin, Vec3(175.0, 0.0, 0.0), msg = "retyping the base…")
        assertVec3(assertNotNull(planeOf(ed, second)).origin, Vec3(175.0, 0.0, 0.0), msg = "…moved both")
    }

    // ---- 5. out of range is invalidity, and it heals ----

    /**
     * **A distance past the end of the run makes the station invalid, and everything on it hides** (OP-3) —
     * *not* a refused gesture, because the distance is a live value. Retyping it brings all of it back.
     */
    @Test
    fun aStationPastTheEndOfTheRunHidesWhatIsOnItAndHeals() {
        val ed = Editor()
        val curve = run300(ed)
        val space = ed.stationOn(curve, "120")
        ed.setTool(Tools.CIRCLE_R)
        ed.type("8")
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("20")
        ed.click(Vec2(8.0, 0.0))
        val solid = assertNotNull(ed.solids().lastOrNull())
        assertFalse(ed.invalid(solid), "it is a solid to begin with")

        val distance = assertNotNull(space.along)
        ed.doc.setParameter(distance, 5000.0.mm)
        assertNull(planeOf(ed, space), "5000 mm along a 300 mm run is nowhere")
        assertTrue(ed.invalid(solid), "so what was sketched on it hides — invalidity propagates (OP-3)")
        val why = (Evaluator().eval(assertNotNull(space.plane).node) as? EvalResult.Invalid)?.reason
        assertTrue(assertNotNull(why).contains("past the end"), "and the plane says why: $why")
        assertTrue(assertNotNull(why).contains("300"), "naming the run's length: $why")

        // …and a negative one is the other mistake, with its own sentence
        ed.doc.setParameter(distance, (-10.0).mm)
        val negative = (Evaluator().eval(assertNotNull(space.plane).node) as? EvalResult.Invalid)?.reason
        assertTrue(assertNotNull(negative).contains("measured from the start"), "$negative")

        ed.doc.setParameter(distance, 200.0.mm)
        assertVec3(assertNotNull(planeOf(ed, space)).origin, Vec3(200.0, 0.0, 0.0), msg = "healed")
        assertFalse(ed.invalid(solid), "and the solid came back with it")
        assertClose(ed.meshOf(solid).vertices.minOf { it.x }, 200.0, 1e-6)
    }

    // ---- 6. the file, and undo ----

    /** **`save → load → save` is byte-equal**, and the station comes back as the space it was. */
    @Test
    fun saveLoadSaveIsByteEqualAndTheStationComesBack() {
        val ed = Editor()
        val curve = run300(ed)
        ed.stationOn(curve, "120")
        ed.setTool(Tools.CIRCLE_R)
        ed.type("8")
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("20")
        ed.click(Vec2(8.0, 0.0))

        val text = DocumentFormat.save(ed.doc)
        assertTrue(Regex("sketchspace \"station1\" path=e\\d+ at=\"distance\"").containsMatchIn(text), text)
        val doc = DocumentFormat.load(text)
        assertEquals(text, DocumentFormat.save(doc), "save → load → save is byte-equal")

        val space = assertNotNull(doc.spaceNamed("station1"), "the station reloaded")
        assertTrue(space.isStation, "as a station")
        val plane =
            ((Evaluator().eval(assertNotNull(space.plane).node) as? EvalResult.Ok)?.value as? PlaneValue)?.plane
        assertVec3(assertNotNull(plane).origin, Vec3(120.0, 0.0, 0.0), msg = "the plane is where it was")

        @Suppress("UNCHECKED_CAST")
        val mesh = Evaluator().solid(doc.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef).mesh
        assertManifold(mesh, "the reloaded boss")
        assertClose(mesh.vertices.minOf { it.x }, 120.0, 1e-6, "and so is what was drawn on it")
    }

    /** **One undo takes the whole gesture back** — the space, its parameter and the switch. */
    @Test
    fun oneUndoTakesTheStationGestureBack() {
        val ed = Editor()
        val curve = run300(ed)
        val spaces = ed.doc.spaces.size
        ed.stationOn(curve, "120")
        assertEquals(spaces + 1, ed.doc.spaces.size, "one space more")

        ed.undo()
        assertEquals(spaces, ed.doc.spaces.size, "and one undo takes it back whole")
        assertTrue(ed.doc.activeSpace.isPlan, "leaving the user in the plan they came from")
        assertNotNull(
            ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE },
            "with the run it was standing on untouched",
        )
    }

    // ---- 7. both views, like any other space ----

    /**
     * **The station is the working plane in both views.** The 3D view edits on it — its projection *is* the
     * station's plane, so a click there lands on the run's own cross-section — and the 2D canvas draws it
     * like every other space.
     */
    @Test
    fun theStationIsTheWorkingPlaneInBothViews() {
        val ed = Editor()
        val curve = run300(ed)
        val space = ed.stationOn(curve, "120")

        val vp = Viewport3(camera = Camera3(target = Vec3(120.0, 0.0, 0.0), distance = 400.0, yaw = -0.9, pitch = 0.5), widthPx = wPx, heightPx = hPx)
        vp.editor = ed
        vp.shown = true
        val proj = assertNotNull(vp.projection(), "the 3D view has a working plane to edit on")
        assertVec3(proj.plane.origin, Vec3(120.0, 0.0, 0.0), msg = "and it is the station's")
        assertVec3(proj.plane.normal, Vec3.X, 1e-9)

        // a click in the 3D view lands on the station plane, in its own coordinates
        ed.setTool(Tools.POINT)
        val screen = assertNotNull(proj.toScreen(Vec2(25.0, 10.0)), "the plane point has an image")
        vp.pointerMove(screen)
        vp.pointerDown(screen)
        vp.pointerUp(screen)
        val pt = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.POINT }, ed.statusHint)
        assertEquals(space.name, pt.space, "the point was drawn on the station")
        val world = proj.plane.toWorld(Vec2(25.0, 10.0))
        assertVec3(world, Vec3(120.0, -10.0, 25.0), 1e-6, "25 mm up out of the run and 10 mm across it")
    }

    /**
     * **…and the 2D canvas draws it like every other space**: the section of the solid the station passes
     * through as context, in the station's own (u, v), with what is drawn on it over the top.
     *
     * The block is 100 × 80 × 60 and the station stands square across a run that goes through it, so its
     * section is the block's 80 × 60 cross-section — which is drawn without a line of code that knows about
     * stations, because a station's context is [Document.spaceSections] like a datum's.
     */
    @Test
    fun theStationsOwnViewDrawsTheSectionItCutsAndWhatIsDrawnOnIt() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(100.0, -40.0))
        ed.click(Vec2(200.0, 40.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("60")
        ed.click(Vec2(150.0, -40.0))
        val block = assertNotNull(ed.solids().lastOrNull(), ed.statusHint)

        val curve = run300(ed)
        val space = ed.stationOn(curve, "150", at = Vec2(280.0, 0.0))
        assertEquals(block, space.anchor, "the run goes through the block, so the station cuts it")
        assertEquals(1, ed.doc.spaceSections(space, Evaluator()).size, "and its section is drawn as context")

        ed.setTool(Tools.CIRCLE_R)
        ed.type("12")
        ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.SELECT)
        val target = SvgDrawTarget()
        ed.render(target)
        Golden.check("station_plane", target.svg())
    }

    /**
     * **A station stands on a helix as readily as on a run of segments** — a curve in space is a curve in
     * space, and the station reads its arc length, which for a coil is exact.
     */
    @Test
    fun aStationOnAHelixStandsOnTheCoil() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.HELIX)
        ed.type("20")
        ed.type("30")
        ed.type("2")
        ed.click(Vec2(0.0, 0.0))
        val coil = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)

        ed.setTool(Tools.STATION)
        ed.type("100")
        ed.click(Vec2(20.0, 0.0))
        val space = ed.doc.activeSpace
        assertTrue(space.isStation, ed.statusHint)
        assertEquals(coil, space.station)
        val p = assertNotNull(planeOf(ed, space))
        // a coil of radius 20 and pitch 30 travels sqrt((2πr)² + p²) = 128.4 mm per turn at constant speed,
        // so 100 mm along is 0.779 of a turn — the point is on the cylinder and has risen proportionally
        assertClose(kotlin.math.hypot(p.origin.x, p.origin.y), 20.0, 1e-6, "the station is on the coil's own cylinder")
        assertClose(p.origin.z, 100.0 / (2.0 * PI * 20.0).let { kotlin.math.hypot(it, 30.0) } * 30.0, 1e-6, "risen with it")
        assertClose(p.normal.dot(Vec3.Z), 30.0 / kotlin.math.hypot(2.0 * PI * 20.0, 30.0), 1e-9, "facing along the coil")
    }

    /**
     * **Deleting the run takes the station and everything on it** — the ordinary dependency rule, which
     * reaches a sketch space because the space's own `sketchspace` step names the curve (OP-18).
     */
    @Test
    fun deletingTheRunTakesTheStationAndEverythingOnItWithIt() {
        val ed = Editor()
        val curve = run300(ed)
        ed.stationOn(curve, "120")
        ed.setTool(Tools.CIRCLE_R)
        ed.type("8")
        ed.click(Vec2(0.0, 0.0))
        assertEquals(2, ed.doc.spaces.size, "the plan and the station")

        ed.setActiveSpace(Document.PLAN_SPACE)
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(150.0, 0.0))
        assertEquals(curve, ed.selection, "the run is selected: ${ed.statusHint}")
        assertTrue(ed.deleteSelection(), "and deleted")
        assertEquals(1, ed.doc.spaces.size, "the station went with it: ${ed.statusHint}")
        assertTrue(ed.doc.activeSpace.isPlan)
        assertNull(
            ed.doc.elements.firstOrNull { it.kind == ElementKind.CIRCLE },
            "and so did the circle drawn on it",
        )
    }

    /** A station is listed and labelled as what it is — the space list's own answer, not the shell's. */
    @Test
    fun theSpaceListNamesTheStationByItsRunAndItsDistance() {
        val ed = Editor()
        val curve = run300(ed)
        val space = ed.stationOn(curve, "120")
        val label = ed.doc.spaceLabel(space)
        assertTrue(label.startsWith("station1 ("), label)
        assertTrue(label.contains("120 mm along"), label)
        assertTrue(label.contains(ed.doc.nameOf(curve)), "and it names the run: $label")
    }
}
