package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.ScalarEntry
import constructit.editor.Tools
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The booleans **as tools** (OP-22): three two-pick tools over the existing `SOLID` element, plus the
 * architectural application — *Cut openings*, which turns a wall's interval features into subtracted
 * boxes and is the 3D half OP-21 designed but left unbuilt.
 *
 * As with the seam's own tools (OP-17), the interesting assertions are not that a mesh appeared but that
 * the result is an ordinary member of the graph and of the file: watertight, parametric through the
 * operands' own values, riding the generic `tool` step with a byte-equal round trip, and taking its place
 * in the dependency cone when an operand is deleted.
 */
class BooleanToolTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun Editor.meshOf(el: Element) = Evaluator().solid(el.ref as SolidRef).mesh

    private fun Editor.newest(): Element = solids().last()

    /**
     * Two crossing walls, each extruded 100 deep — the two operands every boolean tool test starts from.
     *
     * Real building sizes, so that clicking a face of one solid is unambiguously *that* face: picking has a
     * tolerance, and every solid draws a footprint hint (OP-17), so operands 200 mm apart would be one
     * pick rather than two.
     */
    private fun crossedWalls(): Editor {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 200.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(10.0, 600.0)) // vertical carrier: footprint x in -100..100, y in 0..600
        ed.finishPath()
        ed.setTool(Tools.WALL)
        ed.click(Vec2(-300.0, 200.0))
        ed.click(Vec2(300.0, 210.0)) // horizontal carrier: footprint y in 100..300, x in -300..300
        ed.finishPath()
        ed.activeScalar = ed.doc.newParameter("depth", 100.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(-100.0, 500.0)) // the vertical wall's left face
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(-300.0, 200.0)) // the horizontal wall's left end cap
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.SOLID })
        return ed
    }

    /**
     * Pick the two operand solids, in order.
     *
     * The points are the stretches of each wall's boundary that run *inside* the other one — the only
     * places that stay unambiguous once a boolean result is drawing a footprint hint of its own, since a
     * result's boundary is made of pieces of its operands' (ties go to the newest element).
     */
    private fun Editor.pickBoth() {
        click(Vec2(-100.0, 200.0)) // the vertical wall's left face, inside the horizontal one
        click(Vec2(0.0, 100.0)) // the horizontal wall's lower face, inside the vertical one
    }

    private val across = 200.0 * 600.0 * 100.0
    private val along = 600.0 * 200.0 * 100.0
    private val shared = 200.0 * 200.0 * 100.0

    // ---- the three tools ----

    @Test
    fun theThreeBooleanToolsProduceTheThreeAnswers() {
        val expected =
            mapOf(
                Tools.UNION to across + along - shared,
                Tools.SUBTRACT to across - shared,
                Tools.INTERSECT_SOLIDS to shared,
            )
        for ((tool, volume) in expected) {
            val ed = crossedWalls()
            ed.setTool(tool)
            ed.pickBoth()
            assertEquals(3, ed.solids().size, "$tool should add one solid")
            val mesh = ed.meshOf(ed.newest())
            assertManifold(mesh, tool)
            // 1e-3 mm^3 on a part of 2 x 10^7: the arithmetic is exact, the tolerance is what
            // double-precision accumulation over a few hundred triangles costs
            assertClose(Geom3.volume(mesh), volume, tol = 1e-3, msg = tool)
        }
    }

    /** A boolean is parametric through its operands: their own parameters still drive it. */
    @Test
    fun theResultFollowsTheOperandsParameters() {
        val ed = crossedWalls()
        ed.setTool(Tools.UNION)
        ed.pickBoth()
        val result = ed.newest()
        assertClose(Geom3.volume(ed.meshOf(result)), across + along - shared, tol = 1e-3)

        ed.doc.setParameter(ed.doc.scalars.single { it.name == "depth" }, 250.0.mm)
        assertManifold(ed.meshOf(result), "deeper union")
        assertClose(Geom3.volume(ed.meshOf(result)), (200.0 * 600.0 + 600.0 * 200.0 - 200.0 * 200.0) * 250.0, tol = 1e-3)

        // ...and through the 2D drawing underneath: thicken the walls
        ed.doc.setParameter(ed.doc.scalars.single { it.name == "t" }, 400.0.mm)
        assertManifold(ed.meshOf(result), "thicker union")
        assertClose(Geom3.volume(ed.meshOf(result)), (400.0 * 600.0 + 600.0 * 400.0 - 400.0 * 400.0) * 250.0, tol = 1e-3)
    }

    /** Prisms are closed under booleans, so the *tool* composes too: pick a result as an operand. */
    @Test
    fun aBooleanResultIsAnOperandOfTheNextBoolean() {
        val ed = crossedWalls()
        ed.setTool(Tools.UNION)
        ed.pickBoth()
        // a pocket wholly inside the vertical bar, so its own faces are 50 mm clear of every other
        // boundary and one click addresses it
        ed.activeScalar = ed.doc.newParameter("t2", 100.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(-50.0, 450.0))
        ed.click(Vec2(50.0, 455.0))
        ed.finishPath()
        ed.activeScalar = ed.doc.scalars.single { it.name == "depth" }
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(-50.0, 450.0)) // the pocket's left end cap
        ed.setTool(Tools.SUBTRACT)
        // the union was created after its operands, so on a face they share the *union* takes the pick
        ed.click(Vec2(-300.0, 200.0))
        ed.click(Vec2(-50.0, 450.0))
        val mesh = ed.meshOf(ed.newest())
        assertManifold(mesh, "union minus a pocket")
        assertClose(Geom3.volume(mesh), across + along - shared - 100.0 * 100.0 * 100.0, tol = 1e-3)
    }

    // ---- Cut openings: OP-21's 3D half ----

    /** A wall with a door and a window, and the parameters that describe them. */
    private class Wall(val ed: Editor, val doorPos: ScalarEntry, val windowPos: ScalarEntry, val solid: Element)

    private fun walledRoomWall(): Wall {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 200.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(10.0, 4000.0)) // one wall, 4 m long, 200 thick: x in -100..100
        ed.finishPath()
        ed.activeScalar = ed.doc.newParameter("h", 2600.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(-100.0, 2000.0))
        val solid = ed.solids().single()

        // a door: 900 wide, sill 0, head 2100 (the tool's default)
        ed.activeScalar = ed.doc.newParameter("doorW", 900.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(0.0, 1000.0))
        // a window: 1200 wide, floating between 900 and 2100
        ed.activeScalar = ed.doc.newParameter("winW", 1200.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(0.0, 3000.0))
        val sills = ed.doc.scalars.filter { it.name.startsWith("sill") }
        assertEquals(2, sills.size, "each opening carries its own sill")
        ed.doc.setParameter(sills[1], 900.0.mm)

        val positions = ed.doc.scalars.filter { it.name.startsWith("pos") }
        assertEquals(2, positions.size)
        return Wall(ed, positions[0], positions[1], solid)
    }

    private val fullWall = 200.0 * 4000.0 * 2600.0
    private val doorHole = 200.0 * 900.0 * 2100.0
    private val windowHole = 200.0 * 1200.0 * (2100.0 - 900.0)

    @Test
    fun cuttingOpeningsRemovesExactlyTheDoorAndTheWindow() {
        val w = walledRoomWall()
        val ed = w.ed
        assertClose(Geom3.volume(ed.meshOf(w.solid)), fullWall, tol = 1e-3, msg = "the plan gap is not a cut (OP-21)")

        ed.setTool(Tools.CUT_OPENINGS)
        ed.click(Vec2(-100.0, 2000.0))
        assertEquals(2, ed.solids().size, "one new solid, however many openings there are")
        val cut = ed.newest()
        val mesh = ed.meshOf(cut)
        assertManifold(mesh, "wall with a door and a window")
        assertClose(Geom3.volume(mesh), fullWall - doorHole - windowHole, tol = 1e-3)

        // the door reaches the floor, the window floats: the two are different shapes, not one convention
        val b = assertNotNull(Geom3.bounds(mesh))
        assertClose(b.first.z, 0.0, tol = 1e-9)
        assertClose(b.second.z, 2600.0, tol = 1e-9)
        val prism = Evaluator().solid(cut.ref as SolidRef).feature as Feature3.Prism
        // levels: 0 (door sill = the base), 900 (window sill), 2100 (both heads), 2600
        assertEquals(listOf(0.0, 900.0, 2100.0), prism.slabs.map { it.z0 })
        assertEquals(2, prism.slabs[0].regions.size, "at floor level the door splits the wall in two")
        assertEquals(3, prism.slabs[1].regions.size, "between the sills, door and window both cut")
        assertEquals(1, prism.slabs[2].regions.size, "above the heads the wall is whole again")
    }

    /**
     * The cut is **wired to the interval's own parameters** (OP-13): typing a new position moves the hole
     * through the solid — the same amount of material, in a different place, with the jamb where the
     * number says.
     */
    @Test
    fun draggingAnOpeningsPositionMovesTheCutLive() {
        val w = walledRoomWall()
        val ed = w.ed
        ed.setTool(Tools.CUT_OPENINGS)
        ed.click(Vec2(-100.0, 2000.0))
        val cut = ed.newest()
        val before = ed.meshOf(cut)
        assertClose(Geom3.volume(before), fullWall - doorHole - windowHole, tol = 1e-3)
        // the door was centred on the click at y = 1000, so its jambs are at 550 and 1450
        assertTrue(before.vertices.any { abs(it.y - 550.0) < 1e-9 }, "the door's jamb is where it was placed")

        ed.doc.setParameter(w.doorPos, 1200.0.mm)
        val after = ed.meshOf(cut)
        assertManifold(after, "wall after moving the door")
        assertClose(Geom3.volume(after), fullWall - doorHole - windowHole, tol = 1e-3, msg = "the same hole, moved")
        assertEquals(before.triangleCount, after.triangleCount, "the shape is the same shape, so the mesh is the same size")
        assertTrue(after.vertices.any { abs(it.y - 1200.0) < 1e-9 }, "the jamb followed the parameter")
        assertTrue(after.vertices.any { abs(it.y - 2100.0) < 1e-9 }, "and so did the other one")
        assertTrue(after.vertices.none { abs(it.y - 550.0) < 1e-9 }, "nothing is left behind at the old jamb")

        // the head is a parameter too: raise the door to the ceiling and it becomes a pass-through
        val head = ed.doc.scalars.first { it.name.startsWith("head") }
        ed.doc.setParameter(head, 2600.0.mm)
        val through = ed.meshOf(cut)
        assertManifold(through, "wall with a full-height opening")
        assertClose(Geom3.volume(through), fullWall - 200.0 * 900.0 * 2600.0 - windowHole, tol = 1e-3)
    }

    /**
     * The opening **count is structural** (the array rule): a cut describes the openings that existed when
     * the tool ran, so a later one does not retro-cut. Re-running the tool is what includes it.
     */
    @Test
    fun anOpeningAddedAfterTheCutDoesNotRetroCut() {
        val w = walledRoomWall()
        val ed = w.ed
        ed.setTool(Tools.CUT_OPENINGS)
        ed.click(Vec2(-100.0, 2000.0))
        val cut = ed.newest()
        val twoHoles = Geom3.volume(ed.meshOf(cut))

        ed.activeScalar = ed.doc.newParameter("extraW", 600.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(0.0, 2000.0))
        assertClose(Geom3.volume(ed.meshOf(cut)), twoHoles, tol = 1e-9, msg = "structural: the cut is the cut it was")

        // run the tool again over the *original* solid and the third opening is in
        ed.setTool(Tools.CUT_OPENINGS)
        ed.click(Vec2(-100.0, 2000.0))
        val again = ed.newest()
        assertManifold(ed.meshOf(again), "re-cut wall")
        assertClose(
            Geom3.volume(ed.meshOf(again)),
            fullWall - doorHole - windowHole - 200.0 * 600.0 * 2100.0,
            tol = 1e-3,
        )
    }

    /** Cutting a solid that is not a wall's extrusion is a no-op the tool explains rather than a crash. */
    @Test
    fun cuttingOpeningsNeedsAWallWithOpenings() {
        val ed = crossedWalls()
        val before = ed.doc.elements.size
        ed.setTool(Tools.CUT_OPENINGS)
        ed.click(Vec2(-100.0, 500.0)) // a wall, but with no openings on it
        assertEquals(before, ed.doc.elements.size, "nothing to cut, so nothing is built")
    }

    // ---- the file, and the dependency cone ----

    @Test
    fun aDocumentWithBooleansRoundTrips() {
        val ed = crossedWalls()
        ed.setTool(Tools.UNION)
        ed.pickBoth()
        ed.setTool(Tools.INTERSECT_SOLIDS)
        ed.pickBoth()
        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("tool union"), "a boolean rides the generic tool step (OP-18):\n$text")
        assertTrue(text.contains("tool intersectsolids"), text)
        val reloaded = DocumentFormat.load(text)
        assertEquals(text, DocumentFormat.save(reloaded), "save -> load -> save must be byte-equal")
        assertEquals(ed.doc.elements.size, reloaded.elements.size)
        val ev = Evaluator()
        for (el in reloaded.elements.filter { it.kind == ElementKind.SOLID }) {
            @Suppress("UNCHECKED_CAST")
            assertManifold(ev.solid(el.ref as SolidRef).mesh, "reloaded ${el.id}")
        }
    }

    @Test
    fun aCutWallRoundTripsAndItsBoxesComeBackWired() {
        val w = walledRoomWall()
        val ed = w.ed
        ed.setTool(Tools.CUT_OPENINGS)
        ed.click(Vec2(-100.0, 2000.0))
        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("tool cutopenings"), text)
        val reloaded = DocumentFormat.load(text)
        assertEquals(text, DocumentFormat.save(reloaded), "save -> load -> save must be byte-equal")

        @Suppress("UNCHECKED_CAST")
        val meshes = reloaded.elements.filter { it.kind == ElementKind.SOLID }.map { Evaluator().solid(it.ref as SolidRef).mesh }
        assertEquals(2, meshes.size)
        assertClose(Geom3.volume(meshes[1]), fullWall - doorHole - windowHole, tol = 1e-3, msg = "the cuts came back")
        // and they came back *wired*: moving the door in the reloaded document still moves the hole
        val pos = reloaded.scalars.first { it.name.startsWith("pos") }
        reloaded.setParameter(pos, 2000.0.mm)

        @Suppress("UNCHECKED_CAST")
        val moved = Evaluator().solid(reloaded.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef).mesh
        assertManifold(moved, "reloaded wall after moving the door")
        assertTrue(moved.vertices.any { abs(it.y - 2000.0) < 1e-9 }, "the reloaded box tracks its parameter")
    }

    @Test
    fun deletingAnOperandTakesTheBooleanWithItAndUndoBringsBothBack() {
        val ed = crossedWalls()
        ed.setTool(Tools.UNION)
        ed.pickBoth()
        assertEquals(3, ed.solids().size)

        val operand = ed.solids()[0]
        ed.setTool(Tools.SELECT)
        ed.selectElement(operand)
        assertTrue(ed.deleteSelection(), ed.statusHint)
        assertEquals(1, ed.solids().size, "the union is downstream of the operand, so it goes too")

        assertTrue(ed.undo())
        assertEquals(3, ed.solids().size, "undo restores the whole cone")
        assertManifold(ed.meshOf(ed.solids().last()), "restored union")
    }

    /** Deleting one opening rebuilds the cut with one box fewer — the delete replays the script (OP-18). */
    @Test
    fun deletingAnOpeningRebuildsTheCutWithoutIt() {
        val w = walledRoomWall()
        val ed = w.ed
        ed.setTool(Tools.CUT_OPENINGS)
        ed.click(Vec2(-100.0, 2000.0))
        assertClose(Geom3.volume(ed.meshOf(ed.newest())), fullWall - doorHole - windowHole, tol = 1e-3)

        // an interval has no element of its own, so its *step* is what a delete addresses: drop it and
        // replay, exactly as deleting through the UI does
        val openingStep = ed.doc.journal.last { it.kind == "opening" }
        ed.doc.journal.removeAll(ed.doc.dependentSteps(openingStep))
        val reloaded = DocumentFormat.load(DocumentFormat.save(ed.doc))

        @Suppress("UNCHECKED_CAST")
        val cut = Evaluator().solid(reloaded.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef)
        assertManifold(cut.mesh, "wall with only the door")
        assertClose(Geom3.volume(cut.mesh), fullWall - doorHole, tol = 1e-3, msg = "the window's box is gone with it")
    }

    /** A boolean whose operand is refused says so where the user is: the element exists, the value does not. */
    @Test
    fun anEmptyBooleanIsAnInvalidElementWithAReason() {
        val ed = crossedWalls()
        ed.setTool(Tools.INTERSECT_SOLIDS)
        ed.pickBoth()
        val meet = ed.newest()
        assertTrue(Evaluator().eval(meet.ref.node) is EvalResult.Ok)

        // drag the horizontal wall clear of the vertical one: nothing is common any more
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(200.0, 200.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(200.0, 900.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(200.0, 900.0)))
        val r = Evaluator().eval(meet.ref.node)
        assertTrue(r is EvalResult.Invalid, "two solids that no longer meet have no intersection")
        assertTrue(r.reason.contains("leaves nothing"), "reason was: ${r.reason}")
    }
}
