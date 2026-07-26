package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.RegionRef
import constructit.dsl.ScalarRef
import constructit.dsl.SolidRef
import constructit.dsl.plane
import constructit.dsl.resultOf
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.ScalarEntry
import constructit.editor.Tools
import constructit.geom.Axis3
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Showcase 2 — the architect's house: one multi-step 2D→3D→2D→3D→2D→3D chain, and a gable roof.**
 *
 * The story, told end to end with the tools where they exist and the DSL where they do not yet:
 *
 * 1. **2D.** A closed wall ring is drawn with the *Wall* tool (a thick path, OP-21) and given a door and a
 *    window (*Opening*).
 * 2. **3D.** *Extrude* raises the ground floor; *Cut openings* subtracts one box per opening (OP-22).
 * 3. **2D again.** *Section* cuts the storey above the door head and hands back an ordinary area.
 * 4. **3D again.** *Extrude on face* raises storey 2 from that area, on storey 1's own top face (OP-8).
 * 5. **Numbers out of 3D.** The *Extent* tools measure the built solids; one measurement is **wired** to a
 *    named panel parameter, so the panel value *is* the building's size.
 * 6. **3D once more, on a vertical plane.** The gable roof: a triangular profile sketched on a plane whose
 *    in-plane axes are world Y and Z, extruded *horizontally* along the ridge.
 *
 * **Step 6 is the point of this file.** There is no roof feature, no roof code, and nothing that knows what
 * a ridge is: `Geom3.extrude` sweeps a sketch along *its plane's* normal, so a vertical sketch plane makes a
 * horizontal prism with no special case — and if the seam were quietly XY-only, this is where it would break.
 * The roof's profile is driven entirely by **measurements of the solids below it** (OP-4 forward, OP-9's
 * scalar rule), so it is not merely placed on top of the house: it *is* a function of the house, and the
 * final assertion is that **one** drag of a single carrier vertex reshapes the ground floor, storey 2 and
 * the roof together.
 *
 * Two things are deliberately *not* done, and both are recorded rather than worked around: the roof is not
 * unioned with the walls (a horizontal prism and a vertical one have no common axis, and OP-22 refuses that
 * boolean with a reason — asserted in [theRoofAndTheWallsHaveNoCommonAxisSoTheBooleanRefuses]), and the roof
 * is built from the DSL because the tool surface has no way to *name* a vertical plane yet — the gap is in
 * the datum-plane UI, not in the engine.
 */
class HouseChainTest {
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

    private fun Editor.param(name: String): ScalarEntry = doc.scalars.single { it.name == name }

    // The room: a 6 x 4 m closed ring, 300 thick, so the band runs from -150 to 6150 by -150 to 4150.
    private val bandArea = 2.0 * 300.0 * (6000.0 + 4000.0)
    private val onBand = Vec2(-150.0, 2000.0)

    private val doorWidth = 900.0
    private val doorHead = 2100.0
    private val windowWidth = 1200.0
    private val windowSill = 900.0

    /** Steps 1–4: the ring, the openings, the cut ground floor, its section, and storey 2 from it. */
    private class House(val ed: Editor, val ground: Element, val section: Element, val upper: Element)

    private fun house(): House {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 300.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(6000.0, 20.0))
        ed.click(Vec2(5980.0, 4000.0))
        ed.click(Vec2(20.0, 4000.0))
        ed.click(Vec2(0.0, 0.0))
        assertClose(ed.areaOf(ed.areas().single()), bandArea, tol = 1e-6, msg = "the band area is 2t(w + h)")

        ed.activeScalar = ed.doc.newParameter("h1", 3000.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(onBand)

        // a door and a window in the south wall (leg 0), the window floating between 900 and 2100
        ed.activeScalar = ed.doc.newParameter("doorW", doorWidth.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(2000.0, 0.0))
        ed.activeScalar = ed.doc.newParameter("winW", windowWidth.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(4000.0, 0.0))
        val sills = ed.doc.scalars.filter { it.name.startsWith("sill") }
        assertEquals(2, sills.size, "each opening carries its own sill and head")
        ed.doc.setParameter(sills[1], windowSill.mm)

        ed.setTool(Tools.CUT_OPENINGS)
        ed.click(onBand)
        val ground = ed.solids().last()

        // 3D -> 2D: the plan of the ground floor, cut *above* the door head so the ring is unbroken there
        ed.activeScalar = ed.doc.newParameter("cut", 2500.0.mm)
        ed.setTool(Tools.SECTION)
        ed.click(onBand)
        val section = ed.areas().last()

        // 2D -> 3D: storey 2, from the ground floor's own section, on the ground floor's own top face
        ed.activeScalar = ed.doc.newParameter("h2", 2800.0.mm)
        ed.setTool(Tools.EXTRUDE_ON_FACE)
        ed.click(onBand)
        ed.click(onBand)
        return House(ed, ground, section, ed.solids().last())
    }

    /** Step 5+6: the gable roof, as a triangular sketch on a vertical plane, driven by measured extents. */
    private class Roof(val solid: SolidRef, val gable: ScalarEntry, val span: ScalarEntry, val ridgeZ: ScalarRef)

    /**
     * The roof over [house], every dimension of it measured off the storeys.
     *
     * Where each number comes from is the interesting part: the *Extent* tools take the plan size and the
     * storey height as panel scalars (the tool path), the ring's own origin and the eaves height come from
     * `measureBBoxMin`/`Max` in the DSL (no tool offers a bounding-box *bound* yet), and the span is a
     * **named parameter wired** to the measurement, which is how a user reads and reuses a measured number.
     * Only the gable rise is free.
     */
    private fun roofOver(house: House): Roof {
        val ed = house.ed
        val cx = ed.doc.cx
        val upper = house.upper.ref as SolidRef

        ed.setTool(Tools.EXTENT_X)
        ed.click(onBand)
        val extX = ed.param("extx")
        ed.setTool(Tools.EXTENT_Y)
        ed.click(onBand)
        val extY = ed.param("exty")

        // a named parameter that *is* the measured span: the panel's own way of reusing a 3D number (OP-4)
        val span = ed.doc.newParameter("span", 0.0.mm)
        assertTrue(ed.doc.wireParameter(span, extY), "the span parameter must accept the measured extent")
        assertTrue(ed.doc.isBound(span))

        val x0 = cx.measureBBoxMin(upper, Axis3.X)
        val y0 = cx.measureBBoxMin(upper, Axis3.Y)
        val eaves = cx.measureBBoxMax(upper, Axis3.Z)
        val gable = ed.doc.newParameter("gable", 1500.0.mm)
        val ridgeZ = cx.add(eaves, gable.ref)

        // The vertical sketch plane: the YZ plane slid along its own normal (+X) to the ring's west face, so
        // the sketch's u is world Y and its v is world Z. Nothing about this is roof-specific.
        val plane = cx.planeOffset(cx.planeYZ(), x0)
        val west = cx.pointXY(y0, eaves)
        val east = cx.pointXY(cx.add(y0, span.ref), eaves)
        val ridge = cx.pointXY(cx.add(y0, cx.scale(span.ref, 0.5)), ridgeZ)
        val gableProfile =
            cx.region(cx.loop(cx.segment(west, east), cx.segment(east, ridge), cx.segment(ridge, west)))
        return Roof(cx.extrude(cx.sketchOn(plane, gableProfile), extX.ref), gable, span, ridgeZ)
    }

    // ---- the chain, end to end ----

    @Test
    fun theHouseIsOneLiveChainFromThePlanToTheRidge() {
        val house = house()
        val ed = house.ed
        val roof = roofOver(house)
        val ev = Evaluator()

        // every solid is watertight — the mesh is a sink (OP-9), so nothing downstream would tell us
        assertEquals(3, ed.solids().size, "the raw wall, the cut ground floor, and storey 2")
        for (el in ed.solids()) assertManifold(ed.meshOf(el), el.id)
        assertManifold(ev.solid(roof.solid).mesh, "gable roof")

        // the volumes, exactly: a plan gap is not a cut (OP-21), so only the two opening boxes are missing
        val doorBox = 300.0 * doorWidth * doorHead
        val windowBox = 300.0 * windowWidth * (doorHead - windowSill)
        assertClose(Geom3.volume(ed.meshOf(house.ground)), bandArea * 3000.0 - doorBox - windowBox, tol = 1e-3)
        assertClose(Geom3.volume(ed.meshOf(house.upper)), bandArea * 2800.0, tol = 1e-3)
        assertClose(ed.areaOf(house.section), bandArea, tol = 1e-6, msg = "above the door head the ring is whole")

        // the roof: a horizontal prism out of a vertical sketch, spanning the ring and standing on the eaves
        val rm = ev.solid(roof.solid).mesh
        val b = assertNotNull(Geom3.bounds(rm))
        assertClose(b.first.x, -150.0, msg = "the roof starts at the ring's west face")
        assertClose(b.second.x, 6150.0, msg = "...and ends at its east face")
        assertClose(b.first.y, -150.0)
        assertClose(b.second.y, 4150.0)
        assertClose(b.first.z, 5800.0, msg = "the eaves are the top of storey 2")
        assertClose(b.second.z, 7300.0, msg = "the ridge is the wall height plus the gable rise")
        assertClose(ev.scalar(roof.ridgeZ).mm, 7300.0, tol = 1e-9, msg = "3000 + 2800 + 1500")
        assertClose(Geom3.volume(rm), 0.5 * 4300.0 * 1500.0 * 6300.0, tol = 1e-6, msg = "half the span times the rise times the length")

        // and the roof really is one prism swept along +X, not something reinterpreted
        val prism = Geom3.prismatic((ev.solid(roof.solid).feature)).first
        assertNotNull(prism)
        assertClose(prism.plane.normal.x, 1.0, tol = 1e-12, msg = "the sweep axis is world X")
        assertEquals(Vec3(0.0, 1.0, 0.0), prism.plane.u, "the sketch's u is world Y")
        assertEquals(Vec3(0.0, 0.0, 1.0), prism.plane.v, "the sketch's v is world Z")

        // ---- ONE drag: the plan exists once, so everything above it follows ----
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(6000.0, 0.0), Vec2(7000.0, 0.0))
        val wider = 2.0 * 300.0 * (7000.0 + 4000.0)
        val ev2 = Evaluator()
        assertClose(Geom3.volume(ed.meshOf(house.ground)), wider * 3000.0 - doorBox - windowBox, tol = 1e-3, msg = "ground floor")
        assertClose(Geom3.volume(ed.meshOf(house.upper)), wider * 2800.0, tol = 1e-3, msg = "storey 2 followed the plan")
        assertManifold(ev2.solid(roof.solid).mesh, "roof after the drag")
        assertClose(
            Geom3.volume(ev2.solid(roof.solid).mesh),
            0.5 * 4300.0 * 1500.0 * 7300.0,
            tol = 1e-6,
            msg = "the roof grew with the house: the ridge is 1 m longer",
        )
        assertClose(ev2.scalar(roof.ridgeZ).mm, 7300.0, tol = 1e-9, msg = "and no taller")

        // the other direction too, so the *span* (a wired parameter) is seen to drive the profile
        ed.drag(Vec2(20.0, 4000.0), Vec2(20.0, 4600.0))
        val ev3 = Evaluator()
        assertClose(ev3.scalar(roof.span.ref).mm, 4900.0, tol = 1e-9, msg = "the wired span is the new plan depth")
        assertManifold(ev3.solid(roof.solid).mesh, "roof after the second drag")
        assertClose(Geom3.volume(ev3.solid(roof.solid).mesh), 0.5 * 4900.0 * 1500.0 * 7300.0, tol = 1e-6)

        // typing the one free number left moves only the ridge
        ed.doc.setParameter(roof.gable, 2200.0.mm)
        val ev4 = Evaluator()
        assertClose(ev4.scalar(roof.ridgeZ).mm, 8000.0, tol = 1e-9)
        assertClose(Geom3.volume(ev4.solid(roof.solid).mesh), 0.5 * 4900.0 * 2200.0 * 7300.0, tol = 1e-6)
        assertManifold(ev4.solid(roof.solid).mesh, "steeper roof")
    }

    /**
     * The roof is a **separate solid**, and that is a stated cut rather than an omission: a prism swept along
     * X and a prism swept along Z have no common axis, so OP-22's exact algebra has no answer and the boolean
     * refuses *with the reason that names what would answer it* (Manifold, OP-9). The scene is the sum of its
     * solids, which is all a printer or a viewer needs.
     */
    @Test
    fun theRoofAndTheWallsHaveNoCommonAxisSoTheBooleanRefuses() {
        val house = house()
        val roof = roofOver(house)
        val cx = house.ed.doc.cx

        @Suppress("UNCHECKED_CAST")
        val r = Evaluator().resultOf(cx.union(house.upper.ref as SolidRef, roof.solid))
        assertTrue(r is EvalResult.Invalid, "a horizontal prism and a vertical one cannot be fused exactly")
        assertTrue(r.reason.contains("common axis"), "reason was: ${r.reason}")
        assertTrue(r.reason.contains("Manifold (OP-9)"), "the refusal should name what would answer it")

        // ...and the scene's total volume is simply the sum, which is what an export writes out
        val ev = Evaluator()
        val total =
            Geom3.volume(house.ed.meshOf(house.ground)) +
                Geom3.volume(house.ed.meshOf(house.upper)) +
                Geom3.volume(ev.solid(roof.solid).mesh)
        val doorBox = 300.0 * doorWidth * doorHead
        val windowBox = 300.0 * windowWidth * (doorHead - windowSill)
        assertClose(
            total,
            bandArea * 5800.0 - doorBox - windowBox + 0.5 * 4300.0 * 1500.0 * 6300.0,
            tol = 1e-3,
        )
    }

    /**
     * The tool-built part of the chain is **six ordinary steps** in the file and comes back byte-identical
     * (OP-18) — including the wire step, which is what makes a measured number reusable across a reload.
     *
     * The roof is *not* in the file: it was built from the DSL against the document's own construction, and
     * only tool steps are recorded. That is the honest state of this slice — the missing piece is a way to
     * *name a plane* in the UI, not anything about the geometry.
     */
    @Test
    fun theToolBuiltChainRoundTripsByteForByte() {
        val house = house()
        roofOver(house)
        val ed = house.ed

        val text = DocumentFormat.save(ed.doc)
        for (step in listOf("wall", "opening", "tool extrude", "tool cutopenings", "tool section", "tool extrudeface", "tool mextenty", "wire")) {
            assertTrue(text.contains(step), "the file should record '$step':\n$text")
        }
        // the *parameter* the roof is shaped by is a panel value and is recorded; the roof itself is not,
        // because only tool steps are — which is exactly the DSL-only part of this showcase, stated
        assertTrue(text.contains("param \"gable\""), "the gable rise is an ordinary panel parameter:\n$text")
        assertEquals(3, text.split("\n").count { it.startsWith("tool extrude") || it.startsWith("tool cutopenings") })

        val reloaded = DocumentFormat.load(text)
        assertEquals(text, DocumentFormat.save(reloaded), "save -> load -> save must be byte-equal")
        assertEquals(ed.doc.elements.size, reloaded.elements.size)
        assertEquals(3, reloaded.elements.count { it.kind == ElementKind.SOLID }, "the three tool-built solids, not the roof")
        val ev = Evaluator()
        for (el in reloaded.elements.filter { it.kind == ElementKind.SOLID }) {
            @Suppress("UNCHECKED_CAST")
            assertManifold(ev.solid(el.ref as SolidRef).mesh, "reloaded ${el.id}")
        }
        // the reloaded document still carries the wiring, so its span still tracks the building
        val span = reloaded.scalars.single { it.name == "span" }
        assertTrue(reloaded.isBound(span), "the wiring survives a reload")
        assertClose(Evaluator().scalar(span.ref).mm, 4300.0, tol = 1e-9)
    }

    /**
     * A section of the ground floor taken **at window level** falls into two disjoint areas — the door and
     * the window cut the same leg, so between them the ring is severed twice — and that is the *type*
     * refusing, not the geometry failing: a `Region` is one outer boundary with holes. It heals when the cut
     * moves above the heads, which is where the chain takes it.
     */
    @Test
    fun aSectionThroughTheDoorIsRefusedBecauseAnAreaIsOneRegion() {
        val house = house()
        val ed = house.ed
        ed.doc.setParameter(ed.param("cut"), 1000.0.mm)
        val bad = Evaluator().eval(house.section.ref.node)
        assertTrue(bad is EvalResult.Invalid, "cut through both openings the ring is not one area")
        assertTrue(bad.reason.contains("separate areas"), "reason was: ${bad.reason}")

        ed.doc.setParameter(ed.param("cut"), 2500.0.mm)
        assertTrue(Evaluator().eval(house.section.ref.node) is EvalResult.Ok, "raising the cut heals it")
    }

    /** The ground floor really is a prism whose slabs are the openings' story, level by level (OP-22). */
    @Test
    fun theCutGroundFloorsSlabsAreTheOpeningsStory() {
        val house = house()
        val prism = Evaluator().solid(house.ground.ref as SolidRef).feature as Feature3.Prism
        assertEquals(listOf(0.0, windowSill, doorHead), prism.slabs.map { it.z0 }, "the two sills and the shared head")
        assertEquals(1, prism.slabs[0].regions.size, "below the window a single door leaves a U, not two pieces")
        assertEquals(2, prism.slabs[1].regions.size, "with the window too, the ring is severed twice")
        assertEquals(1, prism.slabs[2].regions.size, "above the heads the ring is whole again")
    }
}
