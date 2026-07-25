package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Scene3
import constructit.editor.Tools
import constructit.geom.Axis3
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.deg
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The 2D→3D seam **as tools** (OP-17): the engine's `extrude`/`revolve` reached by clicking, so the
 * seam is finally something a user can cross.
 *
 * The interesting assertions are not that a mesh appeared but that the solid is an ordinary member of
 * the graph and of the file: it is watertight (OP-2), its depth follows a panel parameter (OP-13), it
 * rides the generic `tool` step and round-trips byte-for-byte (OP-18), and deleting the area it was
 * built from takes it with it (OP-5's dependency cone).
 */
class SolidToolTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    /** A straight wall from (20,0) to (20,60), 10 thick — a rectangular footprint at x in 15..25. */
    private fun wallEditor(): Editor {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(20.0, 0.0))
        ed.click(Vec2(21.0, 60.0))
        ed.finishPath()
        return ed
    }

    private fun Editor.area() = doc.elements.single { it.kind == ElementKind.AREA }

    private fun Editor.solidElement() = doc.elements.single { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun Editor.mesh() = Evaluator().solid(solidElement().ref as SolidRef).mesh

    // ---- extrude ----

    @Test
    fun extrudingAWallFootprintGivesAWatertightSolid() {
        val ed = wallEditor()
        ed.activeScalar = ed.doc.newParameter("depth", 12.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(15.0, 30.0)) // on the footprint's left face

        val solid = assertNotNull(ed.doc.elements.singleOrNull { it.kind == ElementKind.SOLID }, "the tool should create one solid")
        assertTrue(solid.isResult, "a solid is an output, not scaffolding (OP-14)")
        val mesh = ed.mesh()
        assertManifold(mesh, "extruded wall")
        // 10 x 60 footprint, 12 deep
        assertClose(Geom3.volume(mesh), 10.0 * 60.0 * 12.0, tol = 1e-6)
        val b = assertNotNull(Geom3.bounds(mesh))
        assertClose(b.first.z, 0.0, msg = "the sketch plane is the world XY plane in this slice")
        assertClose(b.second.z, 12.0)
    }

    /** OP-13 through the parameter: the feature's depth is a panel value, so typing it moves the solid. */
    @Test
    fun theDepthFollowsItsParameter() {
        val ed = wallEditor()
        val depth = ed.doc.newParameter("depth", 12.0.mm)
        ed.activeScalar = depth
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(15.0, 30.0))
        assertClose(Geom3.volume(ed.mesh()), 10.0 * 60.0 * 12.0, tol = 1e-6)

        ed.doc.setParameter(depth, 30.0.mm)
        assertClose(Geom3.volume(ed.mesh()), 10.0 * 60.0 * 30.0, tol = 1e-6)
        assertClose(assertNotNull(Geom3.bounds(ed.mesh())).second.z, 30.0)
        assertManifold(ed.mesh(), "re-parameterised solid")

        // and a depth of zero is refused rather than approximated — the node goes invalid and heals (OP-3)
        ed.doc.setParameter(depth, 0.0.mm)
        assertTrue(
            Evaluator().eval(ed.solidElement().ref.node) is EvalResult.Invalid,
            "a zero depth has no solid",
        )
        ed.doc.setParameter(depth, 8.0.mm)
        assertClose(Geom3.volume(ed.mesh()), 10.0 * 60.0 * 8.0, tol = 1e-6)
    }

    /**
     * The other half of the AREA slot: a traced `Outline` is a *loop*, and the document coerces it to a
     * region for the sketch. One slot, one tool, two kinds of pick — and still exactly one new element.
     */
    @Test
    fun anOutlineCanBeExtrudedToo() {
        val ed = Editor()
        // four infinite construction lines bounding a 60 x 30 rectangle, traced into an outline (OP-14)
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-50.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.click(Vec2(40.0, -50.0))
        ed.click(Vec2(40.0, 50.0))
        ed.click(Vec2(-50.0, 30.0))
        ed.click(Vec2(50.0, 30.0))
        ed.click(Vec2(-20.0, -50.0))
        ed.click(Vec2(-20.0, 50.0))
        ed.setTool(Tools.OUTLINE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 15.0))
        ed.click(Vec2(0.0, 30.0))
        ed.click(Vec2(-20.0, 15.0))
        ed.key("Enter")
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.OUTLINE })

        val before = ed.doc.elements.size
        ed.activeScalar = ed.doc.newParameter("depth", 5.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(0.0, 0.0)) // on the traced bottom edge
        assertEquals(before + 1, ed.doc.elements.size, "the loop->region coercion is a node, not an element")
        assertManifold(ed.mesh(), "extruded outline")
        assertClose(Geom3.volume(ed.mesh()), 60.0 * 30.0 * 5.0, tol = 1e-6)
    }

    /** The panel is an input like the canvas (OP-13): with no depth picked, the tool says so. */
    @Test
    fun theToolAsksForItsDepthBeforeAnyClick() {
        val ed = Editor()
        ed.setTool(Tools.EXTRUDE)
        assertTrue(ed.currentHelp().contains("depth"), "it should name the parameter it wants: '${ed.currentHelp()}'")
        ed.setTool(Tools.REVOLVE)
        assertTrue(ed.currentHelp().contains("angle"), ed.currentHelp())
    }

    // ---- revolve ----

    /**
     * The turned part, by gesture: the wall footprint (10 wide, 60 long, offset 15 from the Y axis)
     * spun a full turn about a drawn line on that axis gives a tube — outer radius 25, bore 15.
     */
    @Test
    fun revolvingAFootprintAboutADrawnLineGivesATube() {
        val ed = wallEditor()
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(0.0, 20.0)) // the axis: the world Y axis, drawn
        ed.activeScalar = ed.doc.newParameter("sweep", 360.0.deg)
        ed.setTool(Tools.REVOLVE)
        ed.click(Vec2(15.0, 30.0)) // the profile
        ed.click(Vec2(0.0, 10.0)) // the axis line

        val mesh = ed.mesh()
        assertManifold(mesh, "revolved tube")
        val exact = PI * (25.0 * 25.0 - 15.0 * 15.0) * 60.0
        val err = (exact - Geom3.volume(mesh)) / exact
        assertTrue(abs(err) < 5e-3, "tube volume is $err off the exact one, more than the tessellation explains")
        val b = assertNotNull(Geom3.bounds(mesh))
        assertClose(b.second.x, 25.0, tol = 0.05, msg = "the outer radius is the far face's distance from the axis")
        assertClose(b.second.y, 60.0, msg = "the axis direction is the profile's own length direction")
    }

    @Test
    fun aProfileCrossingItsAxisIsRefusedWithAReason() {
        val ed = wallEditor()
        ed.setTool(Tools.LINE)
        // a line straight through the middle of the footprint, drawn clear of the wall so the two points
        // stay free rather than snapping onto the carrier
        ed.click(Vec2(20.0, -30.0))
        ed.click(Vec2(20.0, -10.0))
        ed.activeScalar = ed.doc.newParameter("sweep", 180.0.deg)
        ed.setTool(Tools.REVOLVE)
        ed.click(Vec2(15.0, 30.0))
        ed.click(Vec2(20.0, -20.0))

        // the element exists (the construction is recorded) but has no value, and says why (OP-3)
        val el = ed.solidElement()
        val result = Evaluator().eval(el.ref.node)
        assertTrue(result is constructit.core.EvalResult.Invalid, "a profile across the axis must not fold through itself")
        assertTrue((result as constructit.core.EvalResult.Invalid).reason.contains("crosses the axis"), result.reason)
    }

    @Test
    fun aPartialRevolveIsCappedAndStillWatertight() {
        val ed = wallEditor()
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(0.0, 20.0))
        ed.activeScalar = ed.doc.newParameter("sweep", 90.0.deg)
        ed.setTool(Tools.REVOLVE)
        ed.click(Vec2(15.0, 30.0))
        ed.click(Vec2(0.0, 10.0))
        val mesh = ed.mesh()
        assertManifold(mesh, "quarter tube")
        val exact = PI * (25.0 * 25.0 - 15.0 * 15.0) * 60.0 / 4.0
        assertTrue(abs((exact - Geom3.volume(mesh)) / exact) < 5e-3)
    }

    // ---- the file, and the dependency cone ----

    @Test
    fun aDocumentWithBothFeaturesRoundTrips() {
        val ed = wallEditor()
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(0.0, 20.0))
        ed.activeScalar = ed.doc.newParameter("depth", 12.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(15.0, 30.0))
        ed.activeScalar = ed.doc.newParameter("sweep", 270.0.deg)
        ed.setTool(Tools.REVOLVE)
        ed.click(Vec2(15.0, 30.0))
        ed.click(Vec2(0.0, 10.0))
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.SOLID })

        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("tool extrude"), "the seam rides the generic tool step (OP-18):\n$text")
        assertTrue(text.contains("tool revolve"), text)
        val reloaded = DocumentFormat.load(text)
        assertEquals(text, DocumentFormat.save(reloaded), "save -> load -> save must be byte-equal")
        assertEquals(ed.doc.elements.size, reloaded.elements.size)
        // the geometry came back, not just the text
        val ev = Evaluator()
        for (el in reloaded.elements.filter { it.kind == ElementKind.SOLID }) {
            @Suppress("UNCHECKED_CAST")
            assertManifold(ev.solid(el.ref as SolidRef).mesh, "reloaded ${el.id}")
        }
    }

    @Test
    fun deletingTheAreaTakesTheSolidWithItAndUndoBringsBothBack() {
        val ed = wallEditor()
        ed.activeScalar = ed.doc.newParameter("depth", 12.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(15.0, 30.0))
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SOLID })

        // the hint sits exactly on the footprint, so the *tree* is what addresses one of the two
        ed.setTool(Tools.SELECT)
        ed.selectElement(ed.area())
        assertTrue(ed.deleteSelection(), "the footprint should be deletable: ${ed.statusHint}")
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "the solid is downstream, so it goes too")
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.AREA })

        assertTrue(ed.undo())
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "undo restores the whole cone")
        assertManifold(ed.mesh(), "restored solid")
    }

    /** Deleting the *solid* alone leaves the drawing it was made from: the dependency runs one way. */
    @Test
    fun deletingTheSolidLeavesTheDrawing() {
        val ed = wallEditor()
        ed.activeScalar = ed.doc.newParameter("depth", 12.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(15.0, 30.0))
        ed.setTool(Tools.SELECT)
        ed.selectElement(ed.solidElement())
        assertTrue(ed.deleteSelection(), ed.statusHint)
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.SOLID })
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.AREA })
    }

    // ---- the 3D scene ----

    @Test
    fun theSceneCarriesEveryVisibleSolidWithAStableColour() {
        val ed = wallEditor()
        ed.activeScalar = ed.doc.newParameter("depth", 12.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(15.0, 30.0))
        val el = ed.solidElement()

        val scene = Scene3.extract(ed.doc)
        assertEquals(1, scene.solids.size)
        assertEquals(Scene3.colorFor(el.id), scene.solids[0].color, "the colour is a function of the element id")
        assertTrue(scene.lines.isNotEmpty(), "the view has a ground grid and axes")
        val b = assertNotNull(scene.bounds())
        assertClose(b.second.z, 12.0)

        // hidden is hidden in 3D too — visibility is one view state, not two
        el.visible = false
        assertTrue(Scene3.extract(ed.doc).isEmpty)
        el.visible = true

        // an invalid solid contributes nothing and comes back when it heals (OP-3)
        val depth = ed.doc.scalars.single { it.name == "depth" }
        ed.doc.setParameter(depth, 0.0.mm)
        assertTrue(Scene3.extract(ed.doc).isEmpty, "a solid with no value has nothing to draw")
        ed.doc.setParameter(depth, 4.0.mm)
        assertEquals(1, Scene3.extract(ed.doc).solids.size)
    }

    @Test
    fun aSolidIsPickableInTwoDByItsFootprintHint() {
        val ed = wallEditor()
        ed.activeScalar = ed.doc.newParameter("depth", 12.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(15.0, 30.0))
        val solid = ed.solidElement()
        // measured to the sketch boundary the renderer draws for it, so what looks pickable is pickable
        val d = constructit.editor.HitTest.distanceTo(Evaluator(), solid, Vec2(15.0, 30.0))
        assertNotNull(d, "a solid must have a 2D distance, or it can never be selected or deleted")
        assertClose(d, 0.0, tol = 1e-9)
        assertTrue(constructit.editor.HitTest.within(ed.doc, Evaluator(), Vec2(0.0, -5.0), Vec2(40.0, 70.0)).contains(solid))
    }

    /** Sanity on the bounding-box measurements reaching a tool-built solid (OP-4, the 3D→2D scalars). */
    @Test
    fun aToolBuiltSolidCanBeMeasured() {
        val ed = wallEditor()
        ed.activeScalar = ed.doc.newParameter("depth", 7.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(15.0, 30.0))
        @Suppress("UNCHECKED_CAST")
        val ref = ed.solidElement().ref as SolidRef
        val ev = Evaluator()
        assertClose(ev.scalar(ed.doc.cx.measureBBoxExtent(ref, Axis3.Z)).mm, 7.0)
        assertClose(ev.scalar(ed.doc.cx.measureBBoxExtent(ref, Axis3.X)).mm, 10.0)
        assertClose(ev.scalar(ed.doc.cx.measureVolume(ref)).base, 10.0 * 60.0 * 7.0, tol = 1e-6)
    }
}
