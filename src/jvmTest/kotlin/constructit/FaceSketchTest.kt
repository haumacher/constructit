package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.plane
import constructit.dsl.solid
import constructit.editor.Arg
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Scene3
import constructit.editor.SvgDrawTarget
import constructit.editor.Tools
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.MeshBool
import constructit.geom.Vec2
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
 * **Sketching on a planar face** (OP-17): named 2D sketch spaces, a space derived from a solid's *side*
 * face (OP-8 — by boundary-piece index), and the feature that space makes reachable by clicking — the
 * user's back-side drill, end to end.
 *
 * The headline is [aDrillOnASideFaceLandsWhereTheFaceCoordinatesPutIt]: a plate drawn and extruded in
 * plan, a circle drawn *on one of its side faces*, extruded to a typed depth and subtracted. That cut is
 * **cross-axis**, so it goes to the general engine (OP-9, `MeshBool`) — which already worked from the DSL
 * and now works from the toolbar, because there is finally a way to *name* a vertical plane.
 *
 * What the rest asserts is that a space is nothing but organisation and view state: the frame is derived
 * (stretch the plate and the bore rides the face), spaces isolate picking, the file records which space
 * each step was built in, and deleting the face's solid takes the space's geometry with it.
 */
class FaceSketchTest {
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

    @Suppress("UNCHECKED_CAST")
    private fun Editor.meshOf(el: Element) = Evaluator().solid(el.ref as SolidRef).mesh

    private fun requireEngine() =
        assumeTrue(
            MeshBool.available,
            "a cross-axis cut needs the general boolean engine (Manifold, OP-9): ${MeshBool.status}",
        )

    // ---- the fixture: an 80 x 50 plate, 20 thick, on the world XY plane ----

    /**
     * The plate: a rectangle from (0,0) to (80,50) — a closed chain one step built, hence an area — raised
     * 20 mm. Its footprint's first boundary piece is therefore the edge (0,0)→(80,0), which is the face
     * every test below drills into.
     */
    private fun plate(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(80.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("thickness", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(40.0, 0.0)) // the bottom edge of the footprint
        return ed
    }

    /** A point on the plate's bottom footprint edge — where the side face projects to (OP-17). */
    private val onFrontEdge = Vec2(40.0, 0.0)

    /** Arm *Sketch on face* and click the front edge; the 2D view is that face afterwards. */
    private fun Editor.sketchOnFront() {
        setTool(Tools.SKETCH_ON_FACE)
        click(onFrontEdge)
    }

    // ---- the frame, and the convention it states ----

    /**
     * The face frame, spelled out in world coordinates: `u` along the picked edge from its start, `v`
     * **down** from the face's top, and the sketch plane's normal pointing **into** the material, which is
     * what makes a positive extrude depth a cut.
     */
    @Test
    fun theFaceFrameRunsAlongTheEdgeAndDownFromTheTop() {
        val ed = plate()
        ed.sketchOnFront()
        val space = ed.activeSpace
        assertFalse(space.isPlan, "the view switched to the face: ${ed.statusHint}")
        assertEquals(0, space.piece, "the bottom edge is the footprint's first boundary piece (OP-8)")

        val p = Evaluator().plane(assertNotNull(space.plane))
        assertClose(p.origin.x, 0.0, msg = "the origin is the edge's start corner")
        assertClose(p.origin.y, 0.0)
        assertClose(p.origin.z, 20.0, msg = "...at the face's top edge, so the flip leaves the face at v >= 0")
        assertClose(p.u.x, 1.0, msg = "u runs along the edge")
        assertClose(p.v.z, -1.0, msg = "v runs down the face")
        assertClose(p.normal.y, 1.0, msg = "and the normal points into the material, so an extrude drills")

        // the reference outline the face view draws: the rectangle the face actually covers
        val r = assertNotNull(ed.doc.faceOutline(space, Evaluator()))
        assertClose(r[2].x, 80.0, msg = "as wide as the edge is long")
        assertClose(r[2].y, 20.0, msg = "as tall as the plate is thick")
        val target = SvgDrawTarget()
        ed.render(target)
        assertTrue(target.svg().contains("#cfd8e3"), "and it is drawn, as reference context: ${target.svg()}")
    }

    // ---- THE HEADLINE: the drill, by clicking ----

    /**
     * Plate in plan → extrude → *Sketch on face* on a side edge → a ⌀5 circle at (25, 8) in the face's own
     * coordinates → *Extrude* 10 deep → *Subtract*. The result is a watertight plate with a horizontal bore
     * exactly where the face coordinates put it.
     *
     * The numbers: the face frame maps (25, 8) to the world point (25, 0, 12) — 25 mm along the front edge,
     * 8 mm down from the 20 mm-thick plate's top face — and the bore runs 10 mm inward along +Y. So the
     * cylinder is x ∈ 22.5..27.5, y ∈ 0..10, z ∈ 9.5..14.5, and the material lost is π·2.5²·10 = 196.35 mm³
     * (a little less, because an inscribed tessellated circle removes slightly too little — the direction
     * such an error *must* take, so it is asserted).
     */
    @Test
    fun aDrillOnASideFaceLandsWhereTheFaceCoordinatesPutIt() {
        requireEngine()
        val ed = plate()
        val base = ed.solids().single()
        ed.sketchOnFront()

        // draw the bore's circle in the face view: a typed radius, then a click at (25, 8)
        ed.setTool(Tools.CIRCLE_R)
        ed.key("2")
        ed.key(".")
        ed.key("5")
        ed.key("Enter")
        ed.click(Vec2(25.0, 8.0))
        val circle = ed.doc.elements.last { it.kind == ElementKind.CIRCLE }
        assertEquals(ed.activeSpace.name, circle.space, "the circle belongs to the face space")

        // ...extrude it to a typed depth: the space's plane is the face's, flipped, so this drills inward
        ed.setTool(Tools.EXTRUDE)
        ed.key("1")
        ed.key("0")
        ed.key("Enter")
        ed.click(Vec2(27.5, 8.0)) // the circle, by its boundary
        assertEquals(2, ed.solids().size, "the drill is a solid of its own: ${ed.statusHint}")
        val drill = ed.solids().last()

        // the face frame's mapping, asserted twice: exactly, on the frame itself...
        val world = Evaluator().plane(ed.activeSpace.plane!!).toWorld(Vec2(25.0, 8.0))
        assertClose(world.x, 25.0, msg = "25 mm along the edge")
        assertClose(world.y, 0.0, msg = "on the face")
        assertClose(world.z, 12.0, msg = "8 mm down from the top face at z = 20")
        // ...and on the mesh that came out, where an inscribed polygon costs a couple of hundredths
        val db = assertNotNull(Geom3.bounds(ed.meshOf(drill)))
        assertClose((db.first.x + db.second.x) / 2, 25.0, tol = 0.02, msg = "the bore is where the frame put it")
        assertClose((db.first.z + db.second.z) / 2, 12.0, tol = 0.02)
        assertClose(db.first.y, 0.0, tol = 1e-9, msg = "it starts on the face itself")
        assertClose(db.second.y, 10.0, tol = 1e-9, msg = "and runs 10 mm into the material")
        assertClose(db.second.x - db.first.x, 5.0, tol = 0.05, msg = "a ⌀5 drill")

        // ...and subtract it: cross-axis, so the general engine (OP-9)
        ed.setTool(Tools.SUBTRACT)
        ed.click(onFrontEdge) // the plate, addressable in this space *as this face*
        ed.click(Vec2(27.5, 8.0)) // the drill
        assertEquals(3, ed.solids().size, "the cut part exists: ${ed.statusHint}")
        val part = ed.solids().last()
        val mesh = ed.meshOf(part)
        assertManifold(mesh, "drilled plate")
        assertTrue(
            Evaluator().solid(part.ref as SolidRef).feature is Feature3.MeshBoolean,
            "a drill across the plate's axis has no prismatic form (OP-9/OP-22)",
        )

        val exact = PI * 2.5 * 2.5 * 10.0
        val removed = 80.0 * 50.0 * 20.0 - Geom3.volume(mesh)
        assertTrue(removed < exact, "an inscribed bore removes slightly too little ($removed of $exact mm³)")
        assertTrue(removed > exact * 0.98, "...but only by what the 0.02 mm chord tolerance explains ($removed)")
        // the bore is interior in x and z, so the part is still the plate it was
        val pb = assertNotNull(Geom3.bounds(mesh))
        assertClose(pb.first.x, 0.0, tol = 1e-3)
        assertClose(pb.second.x, 80.0, tol = 1e-3)
        assertClose(pb.second.z, 20.0, tol = 1e-3)
        assertClose(Geom3.volume(ed.meshOf(base)), 80.0 * 50.0 * 20.0, tol = 1e-6, msg = "the plate itself is untouched")
    }

    /** The one-gesture form of the same thing: *Cut* in a face view extrudes and subtracts in one step. */
    @Test
    fun theCutToolIsTheSameDrillInOneGesture() {
        requireEngine()
        val ed = plate()
        ed.sketchOnFront()
        ed.setTool(Tools.CIRCLE_R)
        ed.key("2")
        ed.key(".")
        ed.key("5")
        ed.key("Enter")
        ed.click(Vec2(25.0, 8.0))

        ed.setTool(Tools.CUT)
        ed.key("1")
        ed.key("0")
        ed.key("Enter")
        ed.click(Vec2(27.5, 8.0))
        assertEquals(3, ed.solids().size, "one step, two solids: the tool and the part: ${ed.statusHint}")
        val part = ed.solids().last()
        assertManifold(ed.meshOf(part), "cut plate")
        val exact = PI * 2.5 * 2.5 * 10.0
        val removed = 80.0 * 50.0 * 20.0 - Geom3.volume(ed.meshOf(part))
        assertTrue(removed < exact && removed > exact * 0.98, "the same bore as the two-step path ($removed)")

        // and it round-trips: the space is a step, the cut is a tool step (OP-18)
        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("sketchspace \"face1\" el=e9 piece=0"), text)
        assertTrue(text.contains("tool cut"), text)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save -> load -> save must be byte-equal")
    }

    /** In the plan space there is no face to cut into, so *Cut* declines with a reason, not silently. */
    @Test
    fun cutInThePlanSpaceIsRefusedWithAReason() {
        val ed = plate()
        ed.setTool(Tools.CUT)
        ed.key("5")
        ed.key("Enter")
        ed.click(onFrontEdge)
        assertEquals(1, ed.solids().size, "no face, no cut")
        assertTrue(ed.statusHint.contains("Sketch on face"), ed.statusHint)
    }

    // ---- the sequential-feature rule: features CHAIN onto the part, they do not fork it ----

    /**
     * **Two faces, two bores, one part.** A second cut must subtract from *the part* — the tip of its
     * boolean chain — and not from the plate the part started as. Anchoring the boolean to the space's
     * original base forked the model instead: two coincident one-hole solids, each claiming to be the part,
     * with the final volume short by exactly the first bore. Found by a review probe; this is the
     * regression, and it asserts the rule from all four sides — the volume, the step that records *which*
     * solid was cut, the 3D scene (one part, not two shells fighting), and the round trip.
     */
    @Test
    fun aSecondCutChainsOntoTheFirstInsteadOfForkingThePart() {
        requireEngine()
        val ed = plate()
        ed.sketchOnFront()
        ed.setTool(Tools.CIRCLE_R)
        ed.key("2")
        ed.key(".")
        ed.key("5")
        ed.key("Enter")
        ed.click(Vec2(25.0, 8.0))
        ed.setTool(Tools.CUT)
        ed.key("1")
        ed.key("0")
        ed.key("Enter")
        ed.click(Vec2(27.5, 8.0))
        val firstPart = ed.solids().last()

        // the second face: arming the tool goes back to the plan, where footprint edges are drawn
        ed.setTool(Tools.SKETCH_ON_FACE)
        assertTrue(ed.activeSpace.isPlan, "arming the tool returns to the plan: ${ed.statusHint}")
        ed.click(Vec2(80.0, 25.0)) // the plate's right-hand edge
        assertEquals("face2", ed.activeSpace.name)
        ed.setTool(Tools.CIRCLE_R)
        ed.key("3")
        ed.key("Enter")
        ed.click(Vec2(20.0, 10.0))
        ed.setTool(Tools.CUT)
        ed.key("1")
        ed.key("2")
        ed.key("Enter")
        ed.click(Vec2(23.0, 10.0))
        val part = ed.solids().last()

        // (1) the volume: the plate less BOTH bores
        val mesh = ed.meshOf(part)
        assertManifold(mesh, "a part with two bores from two faces")
        val bores = PI * 2.5 * 2.5 * 10.0 + PI * 3.0 * 3.0 * 12.0
        val removed = 80.0 * 50.0 * 20.0 - Geom3.volume(mesh)
        assertTrue(removed < bores, "inscribed bores remove slightly too little ($removed of $bores mm³)")
        assertTrue(removed > bores * 0.98, "...but both of them are gone, not just the second ($removed)")

        // (2) the step records WHICH solid it cut, so replay cannot re-resolve it differently
        val step = assertNotNull(ed.doc.creatingStep(part))
        val els = step.args.filterIsInstance<Arg.Keyed>().first { it.key == "els" }.value as Arg.Els
        assertEquals(firstPart, els.els[0], "the second cut's operand is the first cut's result")

        // (3) one part in the 3D view: the fork drew two coincident shells
        assertEquals(listOf(part.id), Scene3.extract(ed.doc).solids.map { it.elementId })
        assertEquals(part, ed.doc.facePartTip(), "and the tip is the newest link of the chain")

        // (4) the file, and undo
        val text = DocumentFormat.save(ed.doc)
        assertEquals(2, Regex("(?m)^tool cut ").findAll(text).count(), text)
        val reloaded = DocumentFormat.load(text)
        assertEquals(text, DocumentFormat.save(reloaded), "save -> load -> save must be byte-equal")
        @Suppress("UNCHECKED_CAST")
        val back = reloaded.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef
        assertClose(Geom3.volume(Evaluator().solid(back).mesh), Geom3.volume(mesh), tol = 1e-6, msg = "the chain came back, not the fork")

        // undo replays the script into a *fresh* document, so the tip is the same element by id, not by
        // object identity — ids are handed out in replay order, which is deterministic (OP-18)
        assertTrue(ed.undo(), "undo the second cut")
        assertEquals(firstPart.id, ed.doc.facePartTip()?.id, "the tip falls back to the first cut")
        assertTrue(ed.redo())
        assertClose(Geom3.volume(ed.meshOf(ed.solids().last())), Geom3.volume(mesh), tol = 1e-6)
    }

    /**
     * The same rule reached by hand: after a cut, the face rectangle picks **the part**, not the plate it
     * came from — which is what makes the manual *Extrude → Subtract* path chain too (a cut part is
     * mesh-only, so it has no footprint of its own to click anywhere else).
     */
    @Test
    fun theFaceRectanglePicksThePartAtItsTip() {
        requireEngine()
        val ed = plate()
        val base = ed.solids().single()
        ed.sketchOnFront()
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(40.0, 0.0))
        assertEquals(base, ed.selection, "with nothing cut yet, the part *is* the plate")

        ed.setTool(Tools.CIRCLE_R)
        ed.key("2")
        ed.key(".")
        ed.key("5")
        ed.key("Enter")
        ed.click(Vec2(25.0, 8.0))
        ed.setTool(Tools.CUT)
        ed.key("1")
        ed.key("0")
        ed.key("Enter")
        ed.click(Vec2(27.5, 8.0))
        val part = ed.solids().last()

        ed.setTool(Tools.SELECT)
        ed.click(Vec2(40.0, 0.0))
        assertEquals(part, ed.selection, "the rectangle now stands for the part, so Subtract would chain")
    }

    // ---- the frame is derived: the sketch rides the face (face-RELATIVE, and deliberately) ----

    /**
     * **Parametricity, which is the whole point of deriving the frame** (OP-8): move the plate's corner and
     * the face moves, so the bore stays 25 mm from the edge's start and 8 mm below the top face — it rides
     * the part. Contrast OP-20's rule for a rider on a wall, which wants a *world* coordinate: a hole is
     * dimensioned from the part's own edge, so face-relative is the honest intent here.
     */
    @Test
    fun theBoreRidesTheFaceWhenThePlateIsEdited() {
        val ed = plate()
        ed.sketchOnFront()
        ed.setTool(Tools.CIRCLE_R)
        ed.key("2")
        ed.key(".")
        ed.key("5")
        ed.key("Enter")
        ed.click(Vec2(25.0, 8.0))
        ed.setTool(Tools.EXTRUDE)
        ed.key("1")
        ed.key("0")
        ed.key("Enter")
        ed.click(Vec2(27.5, 8.0))
        val drill = ed.solids().last()

        // stretch the plate in the plan view: the corner (0,0) goes to (-20,-10)
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.setTool(Tools.SELECT)
        val elementsBefore = ed.doc.elements.size
        ed.drag(Vec2(0.0, 0.0), Vec2(-20.0, -10.0))
        assertEquals(elementsBefore, ed.doc.elements.size, "a drag recomputes the frame; it creates nothing")

        val moved = assertNotNull(Geom3.bounds(ed.meshOf(drill)))
        assertClose((moved.first.x + moved.second.x) / 2, 5.0, tol = 0.02, msg = "still 25 mm along the edge, whose start moved to x = -20")
        assertClose(moved.first.y, -10.0, tol = 1e-9, msg = "and still on the face, which moved to y = -10")
        assertClose((moved.first.z + moved.second.z) / 2, 12.0, tol = 0.02)

        // thicken the plate: v is measured down from the top face, so the bore follows it up
        ed.doc.setParameter(ed.doc.scalars.single { it.name == "thickness" }, 30.0.mm)
        val raised = assertNotNull(Geom3.bounds(ed.meshOf(drill)))
        assertClose((raised.first.z + raised.second.z) / 2, 22.0, tol = 0.02, msg = "8 mm below the new top face at z = 30")

        // the face outline follows too — the view says where the material now is
        val r = assertNotNull(ed.doc.faceOutline(ed.doc.spaceNamed("face1")!!, Evaluator()))
        assertClose(r[2].x, 100.0, msg = "the stretched edge")
        assertClose(r[2].y, 30.0, msg = "and the thicker plate")
    }

    // ---- refusals (OP-3), with reasons ----

    /** An **arc** edge sweeps a cylinder, not a plane: refused with a reason, and nothing is created. */
    @Test
    fun aCurvedFootprintEdgeIsRefused() {
        val ed = Editor()
        ed.setTool(Tools.ROUNDED_RECT)
        ed.key("8")
        ed.key("Enter")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(80.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("t", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(40.0, 0.0))

        val steps = ed.doc.journal.size
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(2.34, 2.34)) // the bottom-left corner rounding
        assertTrue(ed.activeSpace.isPlan, "the view stayed in the plan")
        assertTrue(ed.statusHint.contains("curved"), ed.statusHint)
        assertEquals(steps, ed.doc.journal.size, "and no step was recorded")

        // ...while the straight edge between the roundings is fine
        ed.click(Vec2(40.0, 0.0))
        assertFalse(ed.activeSpace.isPlan, "a straight edge carries a sketch: ${ed.statusHint}")
    }

    /** A cylinder has one curved side face and no straight edge at all, so every pick on it refuses. */
    @Test
    fun aCylindersSideIsNotAPlanarFace() {
        val ed = Editor()
        ed.setTool(Tools.CIRCLE_R)
        ed.key("2")
        ed.key("0")
        ed.key("Enter")
        ed.click(Vec2(0.0, 0.0))
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(20.0, 0.0))
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(20.0, 0.0))
        assertTrue(ed.activeSpace.isPlan)
        assertTrue(ed.statusHint.contains("curved"), ed.statusHint)
    }

    /** Clicking nothing says so, as every miss must (there is no silent tool in this editor). */
    @Test
    fun aClickOnNoEdgeSaysSo() {
        val ed = plate()
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(400.0, 400.0))
        assertTrue(ed.activeSpace.isPlan)
        assertTrue(ed.statusHint.contains("footprint edge"), ed.statusHint)
    }

    // ---- isolation: one canvas, one space ----

    /**
     * A space is a coordinate system, so its geometry is **only** addressable while it is shown: a plan
     * element is not pickable in the face view and a face element is not pickable in the plan. The one
     * deliberate exception is the solid the face belongs to, which is addressable there *as that face* —
     * which is what makes the *Subtract* pick above possible at all.
     */
    @Test
    fun spacesIsolatePickingAndDrawing() {
        val ed = plate()
        val plan = ed.doc.elements.filter { it.space == Document.PLAN_SPACE }
        val base = ed.solids().single()
        ed.sketchOnFront()

        // The plan's own geometry: not addressable here. (80,50) is a rectangle corner in the plan and is
        // clear of the face rectangle ([0,80] x [0,20]), which *is* addressable — see below.
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(80.0, 50.0))
        assertNull(ed.selection, "a plan point is not pickable in the face view")
        // ...but the face's solid is, on the rectangle the face covers
        ed.click(Vec2(40.0, 0.0))
        assertEquals(base, ed.selection, "the base solid is addressable as its own face")

        // draw a circle here, then go back: it is gone from the plan's picking and drawing
        ed.setTool(Tools.CIRCLE_R)
        ed.key("3")
        ed.key("Enter")
        ed.click(Vec2(25.0, 8.0))
        val faceCircle = ed.doc.elements.last { it.kind == ElementKind.CIRCLE }
        val faceSvg = SvgDrawTarget().also { ed.render(it) }.svg()

        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(28.0, 8.0))
        assertTrue(ed.selection !== faceCircle, "a face element is not pickable in the plan")
        val planSvg = SvgDrawTarget().also { ed.render(it) }.svg()
        assertTrue(planSvg != faceSvg, "the two views draw different things")
        // the plan's own geometry is back
        ed.click(Vec2(80.0, 50.0))
        assertTrue(plan.any { it === ed.selection }, "and the plan's own is pickable again")

        // a marquee over everything takes only this space's elements (OP-16 + OP-17)
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(-500.0, -500.0), Vec2(500.0, 500.0))
        assertTrue(ed.selectedElements.isNotEmpty())
        assertTrue(ed.selectedElements.none { it === faceCircle }, "the marquee is a pick like any other")
    }

    // ---- the file (OP-18) ----

    /**
     * The file records **which space each step was built in**, by ordering — a `space` switch step, exactly
     * like the ortho path's "current path". A switch alone records nothing: it is view state, so what makes
     * it into the script is only the switch a *step* needs.
     */
    @Test
    fun twoSpacesWithGeometryInBothRoundTripByteEqual() {
        val ed = plate()
        ed.sketchOnFront()
        ed.setTool(Tools.CIRCLE_R)
        ed.key("3")
        ed.key("Enter")
        ed.click(Vec2(25.0, 8.0))

        // back to the plan and draw there too, so the script has to switch back
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-40.0, -40.0))
        // ...and once more into the face
        assertTrue(ed.setActiveSpace("face1"))
        ed.setTool(Tools.CIRCLE_R)
        ed.key("2")
        ed.key("Enter")
        ed.click(Vec2(60.0, 10.0))

        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("\nsketchspace \"face1\""), text)
        assertEquals(2, Regex("(?m)^space ").findAll(text).count(), "one switch step per switch a step needed:\n$text")
        val reloaded = DocumentFormat.load(text)
        assertEquals(text, DocumentFormat.save(reloaded), "save -> load -> save must be byte-equal")
        assertEquals(ed.doc.elements.size, reloaded.elements.size)
        assertEquals("face1", reloaded.activeSpace.name, "a reload leaves the space the script ends in")
        assertEquals(
            ed.doc.elements.map { it.space },
            reloaded.elements.map { it.space },
            "and every element comes back in the space it was drawn in",
        )
        // the reloaded frame is re-derived, not stored
        val ev = Evaluator()
        assertClose(ev.plane(reloaded.spaceNamed("face1")!!.plane!!).origin.z, 20.0)

        // switching views on its own is not a step and not an undo step
        val undos = text
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        assertEquals(undos, DocumentFormat.save(ed.doc), "a view switch changes no script")
    }

    /** Undo across the space's own creation: the space goes, and comes back with redo. */
    @Test
    fun undoAcrossSpaceCreationTakesTheSpaceWithIt() {
        val ed = plate()
        ed.sketchOnFront()
        ed.setTool(Tools.CIRCLE_R)
        ed.key("3")
        ed.key("Enter")
        ed.click(Vec2(25.0, 8.0))
        assertEquals(2, ed.doc.spaces.size)

        assertTrue(ed.undo(), "undo the circle")
        assertEquals(2, ed.doc.spaces.size, "the space is still there")
        assertEquals("face1", ed.activeSpace.name, "and the view stayed on the face")
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.CIRCLE })

        assertTrue(ed.undo(), "undo the space")
        assertEquals(1, ed.doc.spaces.size, "the space is gone")
        assertTrue(ed.activeSpace.isPlan, "so the view is back in the plan")
        assertTrue(ed.redo())
        assertEquals(2, ed.doc.spaces.size, "and redo brings it back")
        assertTrue(ed.redo())
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.CIRCLE })
    }

    /**
     * Deleting the solid the face belongs to takes the space **and everything drawn in it** with it: the
     * space's plane cannot be derived any more, and its geometry's coordinates mean nothing without it.
     */
    @Test
    fun deletingTheBaseSolidCascadesToTheSpacesGeometry() {
        val ed = plate()
        val base = ed.solids().single()
        ed.sketchOnFront()
        ed.setTool(Tools.CIRCLE_R)
        ed.key("3")
        ed.key("Enter")
        ed.click(Vec2(25.0, 8.0))
        ed.setTool(Tools.EXTRUDE)
        ed.key("4")
        ed.key("Enter")
        ed.click(Vec2(28.0, 8.0))
        assertEquals(2, ed.solids().size)

        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.setTool(Tools.SELECT)
        ed.selectElement(base)
        assertTrue(ed.deleteSelection(), ed.statusHint)
        assertEquals(0, ed.solids().size, "the drill went with the face it was drawn on")
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.CIRCLE })
        assertEquals(1, ed.doc.spaces.size, "and so did the space")
        assertTrue(ed.activeSpace.isPlan)
        // the surviving script still replays (and still round-trips)
        val text = DocumentFormat.save(ed.doc)
        assertFalse(text.contains("sketchspace"), text)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)))

        assertTrue(ed.undo(), "and undo brings the whole cone back")
        assertEquals(2, ed.solids().size)
        assertEquals(2, ed.doc.spaces.size)
    }

    /** A second face on the same solid is a second space, with its own frame and its own name. */
    @Test
    fun twoFacesOfOneSolidAreTwoSpaces() {
        val ed = plate()
        ed.sketchOnFront()
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(80.0, 25.0)) // the right-hand edge, (80,0)→(80,50)
        assertEquals(3, ed.doc.spaces.size)
        assertEquals("face2", ed.activeSpace.name)
        assertEquals(1, ed.activeSpace.piece, "the second boundary piece (OP-8)")
        val p = Evaluator().plane(ed.activeSpace.plane!!)
        assertClose(p.origin.x, 80.0)
        assertClose(p.u.y, 1.0, msg = "u runs along that edge, which the boundary traverses upward")
        assertClose(p.normal.x, -1.0, msg = "and the sketch normal points back into the material")
        val r = assertNotNull(ed.doc.faceOutline(ed.activeSpace, Evaluator()))
        assertClose(r[2].x, 50.0, msg = "that face is 50 long")
    }

    /**
     * The base solid **stays in the 3D scene** while something is sketched on its face — a defect the face
     * space made glaring and which was never about faces: `Scene3` hid any solid another visible solid was
     * built from, and a face plane makes the base an *ancestor* of the drill without making it its material.
     * So the plate disappeared the moment a drill was drawn on it (and a wall disappeared under the storey
     * stacked on it). Material now means a **solid-valued** input, which is exactly a boolean's operands.
     */
    @Test
    fun theBaseSolidIsStillDrawnWhileSomethingSitsOnItsFace() {
        requireEngine()
        val ed = plate()
        val base = ed.solids().single()
        ed.sketchOnFront()
        ed.setTool(Tools.CIRCLE_R)
        ed.key("2")
        ed.key(".")
        ed.key("5")
        ed.key("Enter")
        ed.click(Vec2(25.0, 8.0))
        ed.setTool(Tools.EXTRUDE)
        ed.key("1")
        ed.key("0")
        ed.key("Enter")
        ed.click(Vec2(27.5, 8.0))
        val drill = ed.solids().last()

        val both = Scene3.extract(ed.doc).solids.map { it.elementId }
        assertTrue(both.contains(base.id), "the plate is not the drill's material, so it is still an output; got $both")
        assertTrue(both.contains(drill.id), "and the drill has nothing built on it yet")

        // ...and once the boolean *does* consume them, only the part is drawn
        ed.setTool(Tools.SUBTRACT)
        ed.click(onFrontEdge)
        ed.click(Vec2(27.5, 8.0))
        val part = ed.solids().last()
        assertEquals(listOf(part.id), Scene3.extract(ed.doc).solids.map { it.elementId }, "a boolean's operands are its material")
    }

    /** The plan space is what every existing drawing is in, so nothing about one changed. */
    @Test
    fun everythingStartsInThePlanSpace() {
        val ed = plate()
        assertEquals(1, ed.doc.spaces.size)
        assertTrue(ed.activeSpace.isPlan)
        assertTrue(ed.doc.elements.all { it.space == Document.PLAN_SPACE })
        assertNull(ed.doc.faceOutline(ed.doc.planSpace, Evaluator()), "the plan has no face outline")
        assertFalse(DocumentFormat.save(ed.doc).contains("space"), "and the file says nothing about spaces")
    }
}
