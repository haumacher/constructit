package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.HitTest
import constructit.editor.Tools
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The seam **downward**, as tools (OP-17): *Extrude on face* (the sketch→feature→sketch loop as one
 * gesture, through the OP-8 provenance accessor `facePlane`) and *Section* (a solid's cross-section as an
 * ordinary 2D area, exact for prisms — OP-22).
 *
 * The headline case is [aStoreyBuiltFromTheStoreyBelowIsOneLiveChain]: 2D → 3D → 2D → 3D with no special
 * code anywhere, and every consequence of that chain being an ordinary DAG — one live drag reshapes both
 * storeys, the file round-trips byte-for-byte, and a delete takes exactly the cone below it.
 */
class SeamDownToolTest {
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

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    private fun Editor.areas(): List<Element> = doc.elements.filter { it.kind == ElementKind.AREA }

    @Suppress("UNCHECKED_CAST")
    private fun Editor.meshOf(el: Element) = Evaluator().solid(el.ref as SolidRef).mesh

    @Suppress("UNCHECKED_CAST")
    private fun Editor.areaOf(el: Element) = Evaluator().scalar(doc.cx.regionArea(el.ref as RegionRef)).base

    // ---- the room: a closed 6 x 4 m wall ring, 300 thick, extruded 3 m ----

    private val bandArea = 2.0 * 300.0 * (6000.0 + 4000.0)

    /** A closed wall ring plus its first storey. The band's own boundary is at x = -150 (outer face). */
    private fun groundFloor(): Editor {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 300.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(6000.0, 20.0))
        ed.click(Vec2(5980.0, 4000.0))
        ed.click(Vec2(20.0, 4000.0))
        ed.click(Vec2(0.0, 0.0)) // clicking the start closes the ring and finishes the wall
        ed.activeScalar = ed.doc.newParameter("h1", 3000.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(-150.0, 2000.0)) // the band's outer face
        assertClose(ed.areaOf(ed.areas().single()), bandArea, tol = 1e-6, msg = "the band area is 2t(w+h)")
        return ed
    }

    /** A point on the wall band's outer boundary — where the ring, its section and its solids all draw. */
    private val onBand = Vec2(-150.0, 2000.0)

    // ---- Extrude on face ----

    /**
     * A boss (here an interior wall's footprint) raised from the base solid's **top face**: it starts
     * exactly where the base ends, and it *stays* there when the base's own depth is typed — the plane is
     * a derived node, not a captured height (OP-8).
     */
    @Test
    fun anAreaExtrudedOnAFaceSitsOnTheBaseAndFollowsIt() {
        val ed = groundFloor()
        // an interior wall, well clear of the ring's band so every pick is unambiguous
        ed.activeScalar = ed.doc.newParameter("t2", 400.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(3000.0, 1000.0))
        ed.click(Vec2(3010.0, 3000.0))
        ed.finishPath()
        val boss = ed.areas().last()

        ed.activeScalar = ed.doc.newParameter("h2", 500.0.mm)
        ed.setTool(Tools.EXTRUDE_ON_FACE)
        ed.click(onBand) // the base solid, by its footprint hint
        ed.click(Vec2(2800.0, 2000.0)) // the area to raise
        assertEquals(2, ed.solids().size, "one new solid")

        val mesh = ed.meshOf(ed.solids().last())
        assertManifold(mesh, "boss on the top face")
        val b = assertNotNull(Geom3.bounds(mesh))
        assertClose(b.first.z, 3000.0, msg = "it starts on the base's top face")
        assertClose(b.second.z, 3500.0)
        assertClose(Geom3.volume(mesh), ed.areaOf(boss) * 500.0, tol = 1e-3)

        // raise the base: the face moves, so the boss moves. Nothing is rebuilt (OP-21's rule).
        ed.doc.setParameter(ed.doc.scalars.single { it.name == "h1" }, 4200.0.mm)
        val moved = assertNotNull(Geom3.bounds(ed.meshOf(ed.solids().last())))
        assertClose(moved.first.z, 4200.0)
        assertClose(moved.second.z, 4700.0)
    }

    /**
     * `facePlane` through **two** booleans: a wall with openings cut out of it is a `Prism`, whose named
     * faces are the same construction over its own extent (OP-22) — so a storey still stacks on a cut
     * wall, and on a union of cut walls.
     */
    @Test
    fun aFaceExtrusionStacksOnABooleanResult() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 200.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(10.0, 4000.0))
        ed.finishPath()
        ed.activeScalar = ed.doc.newParameter("h", 2600.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(-100.0, 2000.0))
        ed.activeScalar = ed.doc.newParameter("doorW", 900.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(0.0, 1000.0))
        ed.setTool(Tools.CUT_OPENINGS)
        ed.click(Vec2(-100.0, 2000.0))
        val cutWall = ed.solids().last()
        assertTrue(Evaluator().solid(cutWall.ref as SolidRef).feature is Feature3.Prism, "a cut wall is a prism")

        // a second wall to stand on the cut one, drawn clear of it
        ed.activeScalar = ed.doc.newParameter("t2", 200.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(2000.0, 1000.0))
        ed.click(Vec2(2010.0, 3000.0))
        ed.finishPath()
        ed.activeScalar = ed.doc.newParameter("h2", 1000.0.mm)
        ed.setTool(Tools.EXTRUDE_ON_FACE)
        ed.click(Vec2(-100.0, 2000.0)) // the newest solid on that face is the cut wall
        ed.click(Vec2(1900.0, 2000.0))

        val upper = ed.solids().last()
        assertManifold(ed.meshOf(upper), "storey on a cut wall")
        val b = assertNotNull(Geom3.bounds(ed.meshOf(upper)))
        assertClose(b.first.z, 2600.0, msg = "the prism's top is derived from its slabs, cuts and all")
        assertClose(b.second.z, 3600.0)

        // ...and **two** booleans deep: fuse the cut wall with the storey on it and stack again. The
        // union's top is its own slabs' extent, which is the upper storey's roof.
        ed.setTool(Tools.UNION)
        ed.click(Vec2(-100.0, 2000.0)) // the cut wall (the newest solid on that face)
        ed.click(Vec2(1900.0, 2000.0)) // the storey standing on it
        val fused = ed.solids().last()
        ed.activeScalar = ed.doc.newParameter("h3", 400.0.mm)
        ed.setTool(Tools.EXTRUDE_ON_FACE)
        ed.click(Vec2(-100.0, 2000.0)) // the union, whose plan covers both walls
        ed.click(Vec2(1900.0, 2000.0)) // the same area again — an area may feed several features
        val roof = ed.solids().last()
        assertManifold(ed.meshOf(roof), "a third storey on a union of a cut wall and a storey")
        assertClose(
            assertNotNull(Geom3.bounds(ed.meshOf(roof))).first.z,
            3600.0,
            msg = "the top face survives a subtract and a union",
        )
        assertManifold(ed.meshOf(fused), "the fused stack")

        // and the top face still follows the *wall's* parameter through the boolean
        ed.doc.setParameter(ed.doc.scalars.single { it.name == "h" }, 3000.0.mm)
        assertClose(assertNotNull(Geom3.bounds(ed.meshOf(upper))).first.z, 3000.0)
    }

    /** A face-based solid draws its footprint hint at its true plan outline, so it is pickable there. */
    @Test
    fun aFaceExtrusionIsPickableAtItsPlanOutline() {
        val ed = groundFloor()
        ed.activeScalar = ed.doc.newParameter("t2", 400.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(3000.0, 1000.0))
        ed.click(Vec2(3010.0, 3000.0))
        ed.finishPath()
        ed.activeScalar = ed.doc.newParameter("h2", 500.0.mm)
        ed.setTool(Tools.EXTRUDE_ON_FACE)
        ed.click(onBand)
        ed.click(Vec2(2800.0, 2000.0))
        val boss = ed.solids().last()

        val ev = Evaluator()
        val d = assertNotNull(HitTest.distanceTo(ev, boss, Vec2(2800.0, 2000.0)), "a solid must have a 2D distance")
        assertClose(d, 0.0, tol = 1e-9, msg = "the hint is at the plan outline, not at the sketch's origin")
        assertClose(assertNotNull(HitTest.distanceTo(ev, boss, Vec2(2700.0, 2000.0))), 100.0, tol = 1e-9)
        assertTrue(HitTest.within(ed.doc, ev, Vec2(2500.0, 500.0), Vec2(3500.0, 3500.0)).contains(boss))
    }

    // ---- Section ----

    @Test
    fun theSectionToolMakesAnOrdinaryAreaThatFollowsTheWall() {
        val ed = groundFloor()
        ed.activeScalar = ed.doc.newParameter("cut", 1200.0.mm)
        ed.setTool(Tools.SECTION)
        ed.click(onBand)

        val section = assertNotNull(ed.areas().lastOrNull(), "the tool should create one area")
        assertEquals(2, ed.areas().size, "the wall footprint and the section")
        assertTrue(section.isResult, "a section is output geometry, not scaffolding (OP-14)")
        assertClose(ed.areaOf(section), bandArea, tol = 1e-6, msg = "the section of a one-slab prism is its own footprint")

        // it is DERIVED: drag the ground floor's carrier and the section reshapes, with nothing rebuilt
        val before = ed.doc.elements.size
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(6000.0, 0.0), Vec2(7000.0, 0.0))
        assertEquals(before, ed.doc.elements.size, "a drag recomputes; it creates nothing")
        val widened = 2.0 * 300.0 * (7000.0 + 4000.0)
        assertClose(ed.areaOf(ed.areas()[0]), widened, tol = 1e-6, msg = "the drag widened the room")
        assertClose(ed.areaOf(section), widened, tol = 1e-6, msg = "and the section followed it")

        // a height above the solid is refused with a reason, and heals (OP-3)
        ed.doc.setParameter(ed.doc.scalars.single { it.name == "cut" }, 9000.0.mm)
        val bad = Evaluator().eval(section.ref.node)
        assertTrue(bad is EvalResult.Invalid && bad.reason.contains("no material"), "reason was: $bad")
        ed.doc.setParameter(ed.doc.scalars.single { it.name == "cut" }, 500.0.mm)
        assertTrue(Evaluator().eval(section.ref.node) is EvalResult.Ok, "lowering the cut heals it")
    }

    /** The tools name the panel value they still want, before any click is thrown away (OP-13). */
    @Test
    fun theNewToolsAskForTheirScalarFirst() {
        val ed = Editor()
        ed.setTool(Tools.SECTION)
        assertTrue(ed.currentHelp().contains("height"), ed.currentHelp())
        ed.setTool(Tools.EXTRUDE_ON_FACE)
        assertTrue(ed.currentHelp().contains("depth"), ed.currentHelp())
    }

    /** A section is an area like any other, so the plain *Extrude* tool takes it. */
    @Test
    fun aSectionCanBeExtrudedByTheOrdinaryTool() {
        val ed = groundFloor()
        ed.activeScalar = ed.doc.newParameter("cut", 1200.0.mm)
        ed.setTool(Tools.SECTION)
        ed.click(onBand)
        ed.activeScalar = ed.doc.newParameter("d", 250.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(onBand) // the section is the newest area on that boundary, so it takes the pick
        assertEquals(2, ed.solids().size)
        val slab = ed.solids().last()
        assertManifold(ed.meshOf(slab), "a solid extruded from a section")
        assertClose(Geom3.volume(ed.meshOf(slab)), bandArea * 250.0, tol = 1e-3)
    }

    // ---- the headline chain: 2D -> 3D -> 2D -> 3D ----

    /**
     * The whole seam, in one construction and with no code that knows about it: a drawn wall ring becomes
     * storey 1, storey 1's **section** becomes 2D geometry again, and that section — extruded on storey 1's
     * own top face — becomes storey 2.
     *
     * Storey 2 depends on storey 1 *twice* (through the face plane and through the section), which is not a
     * cycle: acyclicity is about ancestry, and storey 1 is an ancestor of both paths. What that double
     * dependency buys is the assertion at the end: **one** drag of a ground-floor carrier vertex reshapes
     * both storeys, because there is only one description of the plan in the document.
     */
    @Test
    fun aStoreyBuiltFromTheStoreyBelowIsOneLiveChain() {
        val ed = groundFloor()
        val ground = ed.solids().single()

        // 3D -> 2D: the plan of storey 1, cut at 1.2 m
        ed.activeScalar = ed.doc.newParameter("cut", 1200.0.mm)
        ed.setTool(Tools.SECTION)
        ed.click(onBand)
        val section = ed.areas().last()

        // 2D -> 3D again: that section, extruded on storey 1's top face — storey 2 from the plan of
        // storey 1. Both areas draw on the same boundary, so the *newest* takes the pick, which is the
        // section (the wall footprint is what the section was cut from).
        ed.activeScalar = ed.doc.newParameter("h2", 2800.0.mm)
        ed.setTool(Tools.EXTRUDE_ON_FACE)
        ed.click(onBand)
        ed.click(onBand)
        assertEquals(2, ed.solids().size, "storey 2 exists")
        val upper = ed.solids().last()
        assertManifold(ed.meshOf(upper), "storey 2")
        val b = assertNotNull(Geom3.bounds(ed.meshOf(upper)))
        assertClose(b.first.z, 3000.0, msg = "storey 2 stands on storey 1")
        assertClose(b.second.z, 5800.0)
        assertClose(Geom3.volume(ed.meshOf(upper)), bandArea * 2800.0, tol = 1e-3)

        // the stack is union-able, and being one footprint it fuses into a single shaft with no floor
        // slab in it (OP-22's slab merge). Storey 2 is hidden for the first pick, which is how a user
        // addresses the storey below when the two plans coincide — a pick, nothing more.
        upper.visible = false
        ed.setTool(Tools.UNION)
        ed.click(onBand)
        upper.visible = true
        ed.click(onBand)
        assertEquals(3, ed.solids().size, "the union was built: ${ed.statusHint}")
        val shaft = ed.solids().last()
        assertManifold(ed.meshOf(shaft), "the two storeys fused")
        assertClose(Geom3.volume(ed.meshOf(shaft)), bandArea * 5800.0, tol = 1e-3)
        val prism = Evaluator().solid(shaft.ref as SolidRef).feature as Feature3.Prism
        assertEquals(1, prism.slabs.size, "one footprint, so one slab — no seam between the storeys")

        // ONE drag, both storeys: the plan exists once, and everything above it is derived
        val v1 = Geom3.volume(ed.meshOf(ground))
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(6000.0, 0.0), Vec2(7000.0, 0.0))
        val widened = 2.0 * 300.0 * (7000.0 + 4000.0)
        assertTrue(Geom3.volume(ed.meshOf(ground)) > v1, "the ground floor grew")
        assertClose(Geom3.volume(ed.meshOf(ground)), widened * 3000.0, tol = 1e-3)
        assertClose(ed.areaOf(section), widened, tol = 1e-6, msg = "the section is the new plan")
        assertClose(Geom3.volume(ed.meshOf(upper)), widened * 2800.0, tol = 1e-3, msg = "and so is storey 2")
        assertManifold(ed.meshOf(shaft), "the fused shaft after the drag")
        assertClose(Geom3.volume(ed.meshOf(shaft)), widened * 5800.0, tol = 1e-3)

        // the file: the chain is four ordinary tool steps, and it comes back identically (OP-18)
        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("tool section"), "the section rides the generic tool step:\n$text")
        assertTrue(text.contains("tool extrudeface"), text)
        val reloaded = DocumentFormat.load(text)
        assertEquals(text, DocumentFormat.save(reloaded), "save -> load -> save must be byte-equal")
        assertEquals(ed.doc.elements.size, reloaded.elements.size)
        val ev = Evaluator()
        for (el in reloaded.elements.filter { it.kind == ElementKind.SOLID }) {
            @Suppress("UNCHECKED_CAST")
            assertManifold(ev.solid(el.ref as SolidRef).mesh, "reloaded ${el.id}")
        }

        // delete: the cone below the section is exactly storey 2 and the union; storey 1 stays, and undo
        // brings the whole cone back (OP-18 — undo is the saved script)
        ed.setTool(Tools.SELECT)
        ed.selectElement(section)
        assertTrue(ed.deleteSelection(), ed.statusHint)
        assertEquals(1, ed.solids().size, "storey 2 and the union went with the section")
        assertEquals(1, ed.areas().size, "the wall footprint is upstream, so it stays")
        assertTrue(ed.undo())
        assertEquals(3, ed.solids().size, "undo restores the whole cone")
        assertManifold(ed.meshOf(ed.solids().last()), "restored shaft")
    }
}
