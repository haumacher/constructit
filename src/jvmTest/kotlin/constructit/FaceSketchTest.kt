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
 * plan, a circle drawn *on one of its side faces*, and *Cut* to a typed depth. That cut is
 * **cross-axis**, so it goes to the general engine (OP-9, `MeshBool`) — which already worked from the DSL
 * and now works from the toolbar, because there is finally a way to *name* a vertical plane. Its twin is
 * [anExtrudeOnASideFaceBuildsABossOutOfTheMaterial]: the same footprint, built *out* of the material,
 * which is what a plain *Extrude* on a face means (GitHub #1).
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
     * **The frame is intrinsic** (session 32, superseding "u from the edge's start, v down from the top"):
     * the picked segment lies **on the x axis** about its own **midpoint**, `v` runs **into the face's
     * interior** as seen from that segment — for this upright plate, world up — and the normal points **out
     * of the material**, i.e. at whoever is looking at the face. Right-handedness then fixes `u`.
     *
     * Nothing here is a stored sign, and nothing degenerates when a face turns parallel to a world axis: a
     * face is locally on exactly one side of its own boundary edge, which is the whole derivation. Which way
     * an operation builds is the operation's business — *Cut* sweeps −normal and drills, *Extrude* sweeps
     * +normal and bosses (see `anExtrudeOnASideFaceBuildsABossOutOfTheMaterial`).
     */
    @Test
    fun theFaceFrameStandsOnThePickedEdgeAndRunsIntoTheFace() {
        val ed = plate()
        ed.sketchOnFront()
        val space = ed.activeSpace
        assertFalse(space.isPlan, "the view switched to the face: ${ed.statusHint}")
        assertEquals(0, space.piece, "the bottom edge is the footprint's first boundary piece (OP-8)")

        val p = Evaluator().plane(assertNotNull(space.plane))
        assertClose(p.origin.x, 40.0, msg = "the origin is the picked edge's midpoint")
        assertClose(p.origin.y, 0.0)
        assertClose(p.origin.z, 0.0, msg = "...at the face's own bottom edge, which is the segment that was picked")
        assertClose(p.u.x, 1.0, msg = "u runs along the edge")
        assertClose(p.v.z, 1.0, msg = "v runs up into the face, which is where its material is")
        assertClose(p.normal.y, -1.0, msg = "and the normal points out of the material, at the viewer")

        // the reference outline the face view draws: the rectangle the face actually covers
        val r = assertNotNull(ed.doc.faceOutline(space, Evaluator()))
        assertEquals(4, r.size)
        assertClose(r[0].x, -40.0, msg = "the numbering starts at the picked edge's first corner")
        assertClose(r[0].y, 0.0)
        assertClose(r[1].x, 40.0, msg = "as wide as the edge is long, centred on it")
        assertClose(r[1].y, 0.0)
        assertClose(r[2].x, 40.0)
        assertClose(r[2].y, 20.0, msg = "as tall as the plate is thick, all of it at v >= 0")
        assertClose(r[3].x, -40.0)
        assertClose(r[3].y, 20.0)
        val target = SvgDrawTarget()
        ed.render(target)
        assertTrue(target.svg().contains("#cfd8e3"), "and it is drawn, as reference context: ${target.svg()}")
    }

    /**
     * **+v is up on screen**, which is what makes "into the face" a statement about the picture and not only
     * about the numbers: the 2D canvas maps a space's own (u, v) with y increasing upwards, in the face view
     * exactly as in the plan.
     */
    @Test
    fun theFaceViewDrawsVUpwards() {
        val ed = plate()
        ed.sketchOnFront()
        val low = ed.camera.worldToScreen(Vec2(0.0, 0.0))
        val high = ed.camera.worldToScreen(Vec2(0.0, 15.0))
        assertTrue(high.y < low.y, "a bigger v is drawn higher up the canvas ($high vs $low)")
        val right = ed.camera.worldToScreen(Vec2(20.0, 0.0))
        assertTrue(right.x > low.x, "and a bigger u to the right")
    }

    /**
     * **The rule is about the face, not about the world.** The plate's *far* side is the mirror image of its
     * front: the same picked-edge-on-the-x-axis, the same `v` into the material's side of the edge, and a
     * normal that points the other way in the world — so `u` runs the other way too, which is exactly what
     * "the outward normal points at the viewer" means once you walk round the part.
     */
    @Test
    fun theFarSideFaceIsTheSameRuleSeenFromTheOtherSide() {
        val ed = plate()
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(40.0, 50.0)) // the back edge, (80,50)->(0,50)
        assertEquals(2, ed.activeSpace.piece, "the third boundary piece")
        val p = Evaluator().plane(assertNotNull(ed.activeSpace.plane))
        assertClose(p.origin.x, 40.0, msg = "the midpoint of that edge")
        assertClose(p.origin.y, 50.0)
        assertClose(p.origin.z, 0.0)
        assertClose(p.normal.y, 1.0, msg = "the normal points out of the material — the other way from the front face")
        assertClose(p.v.z, 1.0, msg = "v still runs into the face, which is still upward here")
        assertClose(p.u.x, -1.0, msg = "so u runs the other way: right-handedness is what turns it round")
        // and the material really is on the −normal side, which is what "interior" was read from
        assertClose(p.toWorld(Vec2(0.0, 10.0)).y, 50.0, msg = "the frame lies on the face")
    }

    /**
     * **...and it does not secretly mean "up".** A pyramid whose apex hangs *below* its base plane has slant
     * faces whose interior runs **downwards** from the picked base edge — so `v` points down in the world,
     * with the normal still out of the material and the apex still at `+v`. A world-anchored frame (the draft
     * this rule replaced) has no answer here that is not a special case.
     */
    @Test
    fun anInvertedPyramidsFaceRunsDownwardsFromItsBaseEdge() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        for (c in "90") ed.key(c.toString())
        ed.key("Enter")
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(50.0, 50.0))
        // turn it upside down: the apex's height is an ordinary parameter (OP-25)
        ed.doc.setParameter(ed.doc.scalars.single { it.name == "height" }, (-90.0).mm)

        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(30.0, 0.0))
        assertTrue(ed.activeSpace.isFace, "a flat face of the inverted loft: ${ed.statusHint}")
        val p = Evaluator().plane(assertNotNull(ed.activeSpace.plane))
        // *which* lateral face a footprint edge names is the loft plan's rail mapping and is not this test's
        // subject (a flipped run renumbers it — see the note under OP-17); the frame on the face it opened is
        assertClose(p.origin.z, 0.0, msg = "the frame stands on a base edge, which lies in the base plane")
        assertClose(Vec2(p.origin.x - 50.0, p.origin.y - 50.0).length(), 50.0, tol = 1e-9, msg = "at that edge's midpoint")
        assertTrue(p.v.z < -0.5, "v runs down the face, because that is where this face's material is: ${p.v}")
        assertClose(p.u.z, 0.0, msg = "u lies along the base edge, in the base plane")
        assertTrue(p.normal.z < 0.0, "and the normal points out of the material — downwards-and-out here: ${p.normal}")

        val section = assertNotNull(ed.doc.spaceSection(ed.activeSpace, Evaluator()))
        val corners = section.cornerPoints
        assertEquals(3, corners.size, "a triangle: $corners")
        assertTrue(corners.count { kotlin.math.abs(it.y) < 1e-9 } == 2, "the picked edge lies on the x axis: $corners")
        val apex = assertNotNull(corners.maxByOrNull { it.y })
        assertClose(apex.x, 0.0, msg = "the apex is over the middle of that edge")
        assertClose(apex.y, kotlin.math.sqrt(50.0 * 50.0 + 90.0 * 90.0), tol = 1e-6, msg = "...at +v, a slant height away")
    }

    // ---- THE HEADLINE: the drill, by clicking ----

    /**
     * Plate in plan → extrude → *Sketch on face* on a side edge → a ⌀5 circle at (−15, 12) in the face's own
     * coordinates → *Cut* 10 deep. The result is a watertight plate with a horizontal bore exactly where the
     * face coordinates put it.
     *
     * The numbers: the face frame maps (−15, 12) to the world point (25, 0, 12) — 15 mm back along the front
     * edge from its 40 mm midpoint, 12 mm up from the edge itself — and the bore runs 10 mm inward along +Y (a
     * *Cut* sweeps −normal, and the face's normal points out of the material). So the
     * cylinder is x ∈ 22.5..27.5, y ∈ 0..10, z ∈ 9.5..14.5, and the material lost is π·2.5²·10 = 196.35 mm³
     * (a little less, because an inscribed tessellated circle removes slightly too little — the direction
     * such an error *must* take, so it is asserted).
     *
     * **Cut is the operation that goes inward** — the drill drills because that is what *Cut* means, not
     * because of which way the space's plane happens to face. Its twin builds the same footprint outward as a
     * boss (`anExtrudeOnASideFaceBuildsABossOutOfTheMaterial`), which is what a plain *Extrude* here means.
     */
    @Test
    fun aDrillOnASideFaceLandsWhereTheFaceCoordinatesPutIt() {
        requireEngine()
        val ed = plate()
        val base = ed.solids().single()
        ed.sketchOnFront()

        // draw the bore's circle in the face view: a typed radius, then a click at (−15, 12)
        ed.setTool(Tools.CIRCLE_R)
        ed.key("2")
        ed.key(".")
        ed.key("5")
        ed.key("Enter")
        ed.click(Vec2(-15.0, 12.0))
        val circle = ed.doc.elements.last { it.kind == ElementKind.CIRCLE }
        assertEquals(ed.activeSpace.name, circle.space, "the circle belongs to the face space")

        // ...cut it to a typed depth: *Cut* sweeps the face's own plane inward, which is what drills
        ed.setTool(Tools.CUT)
        ed.key("1")
        ed.key("0")
        ed.key("Enter")
        ed.click(Vec2(-12.5, 12.0)) // the circle, by its boundary
        assertEquals(3, ed.solids().size, "one gesture, two solids — the drill and the cut part: ${ed.statusHint}")
        val drill = ed.solids()[1]

        // the face frame's mapping, asserted twice: exactly, on the frame itself...
        val world = Evaluator().plane(ed.activeSpace.plane!!).toWorld(Vec2(-15.0, 12.0))
        assertClose(world.x, 25.0, msg = "25 mm along the edge")
        assertClose(world.y, 0.0, msg = "on the face")
        assertClose(world.z, 12.0, msg = "12 mm up from the picked edge, which lies at z = 0")
        // ...and on the mesh that came out, where an inscribed polygon costs a couple of hundredths
        val db = assertNotNull(Geom3.bounds(ed.meshOf(drill)))
        assertClose((db.first.x + db.second.x) / 2, 25.0, tol = 0.02, msg = "the bore is where the frame put it")
        assertClose((db.first.z + db.second.z) / 2, 12.0, tol = 0.02)
        assertClose(
            db.first.y,
            -Geom3.TOOL_STEP_MM,
            tol = 1e-9,
            msg = "it starts one micron off the face, in the air — a tool never shares a face with the body (GitHub #33)",
        )
        assertClose(db.second.y, 10.0, tol = 1e-9, msg = "and runs 10 mm into the material")
        assertClose(db.second.x - db.first.x, 5.0, tol = 0.05, msg = "a ⌀5 drill")

        // ...and the part it left: a cross-axis subtraction, so the general engine (OP-9)
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

    /**
     * **The other half of the pair, and the defect that named it** (GitHub #1): a plain *Extrude* in a face
     * space builds a **boss — outward, out of the material**.
     *
     * Reported as a glitch on a face: the extrude produced a solid of the right size in the right place that
     * was *buried inside the part*, visible only as its base z-fighting the face it was drawn on. The cause was
     * that the space's plane was the face's plane **flipped**, so its normal pointed into the material and a
     * plain extrude inherited that direction. Which way an operation builds belongs to the **operation**, and
     * since the frame stopped being flipped (session 32) that reads as one sentence for every kind of space:
     * *Extrude* sweeps the plane's +normal, which on a face is out of the material, and *Cut* sweeps the other
     * way. **This test is what proves the sign swap kept the user-visible behaviour identical**: the boss
     * still stands out, and the drill above still drills in.
     *
     * The numbers, against the same plate and the same circle as the drill: material is at y > 0, so the boss
     * occupies y ∈ −10..0 — flush against the face, entirely outside the plate — and unioning it adds exactly
     * its own volume.
     */
    @Test
    fun anExtrudeOnASideFaceBuildsABossOutOfTheMaterial() {
        requireEngine()
        val ed = plate()
        val base = ed.solids().single()
        ed.sketchOnFront()
        ed.setTool(Tools.CIRCLE_R)
        ed.key("2")
        ed.key(".")
        ed.key("5")
        ed.key("Enter")
        ed.click(Vec2(-15.0, 12.0))

        ed.setTool(Tools.EXTRUDE)
        ed.key("1")
        ed.key("0")
        ed.key("Enter")
        ed.click(Vec2(-12.5, 12.0))
        assertEquals(2, ed.solids().size, "the boss is a solid of its own: ${ed.statusHint}")
        val boss = ed.solids().last()

        val bb = assertNotNull(Geom3.bounds(ed.meshOf(boss)))
        assertClose((bb.first.x + bb.second.x) / 2, 25.0, tol = 0.02, msg = "where the face coordinates put it")
        assertClose((bb.first.z + bb.second.z) / 2, 12.0, tol = 0.02)
        assertClose(bb.second.y, 0.0, tol = 1e-9, msg = "it ends on the face itself")
        assertClose(bb.first.y, -10.0, tol = 1e-9, msg = "and stands 10 mm out of the material, not into it")
        assertManifold(ed.meshOf(boss), "boss")

        // and it is material added: the union is the plate plus the boss, not the plate with a wart inside it
        ed.setTool(Tools.UNION)
        ed.click(onFrontEdge) // the plate, addressable here as this face
        ed.click(Vec2(-12.5, 12.0)) // the boss
        assertEquals(3, ed.solids().size, "the joined part exists: ${ed.statusHint}")
        val part = ed.solids().last()
        val mesh = ed.meshOf(part)
        assertManifold(mesh, "plate with a boss")
        val exact = PI * 2.5 * 2.5 * 10.0
        val added = Geom3.volume(mesh) - 80.0 * 50.0 * 20.0
        assertTrue(added < exact, "an inscribed boss adds slightly too little ($added of $exact mm³)")
        assertTrue(added > exact * 0.98, "...but only by what the chord tolerance explains ($added)")
        val pb = assertNotNull(Geom3.bounds(mesh))
        assertClose(pb.first.y, -10.0, tol = 1e-3, msg = "the part now reaches 10 mm past the face")
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
        ed.click(Vec2(-15.0, 12.0))

        ed.setTool(Tools.CUT)
        ed.key("1")
        ed.key("0")
        ed.key("Enter")
        ed.click(Vec2(-12.5, 12.0))
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
        ed.click(Vec2(-15.0, 12.0))
        ed.setTool(Tools.CUT)
        ed.key("1")
        ed.key("0")
        ed.key("Enter")
        ed.click(Vec2(-12.5, 12.0))
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
        ed.click(Vec2(-15.0, 12.0))
        ed.setTool(Tools.CUT)
        ed.key("1")
        ed.key("0")
        ed.key("Enter")
        ed.click(Vec2(-12.5, 12.0))
        val part = ed.solids().last()

        ed.setTool(Tools.SELECT)
        ed.click(Vec2(40.0, 0.0))
        assertEquals(part, ed.selection, "the rectangle now stands for the part, so Subtract would chain")
    }

    // ---- the frame is derived: the sketch rides the face (face-RELATIVE, and deliberately) ----

    /**
     * **Parametricity, which is the whole point of deriving the frame** (OP-8): move the plate's corner and
     * the face moves, so the bore stays 15 mm back from the edge's **midpoint** and 12 mm up from the edge
     * itself — it rides the part. Contrast OP-20's rule for a rider on a wall, which wants a *world*
     * coordinate: a hole is dimensioned from the part's own edge, so face-relative is the honest intent here.
     *
     * What the intrinsic frame changes here is *which* edit moves the bore, and it is worth stating because it
     * is the user-visible consequence of the rule: coordinates are measured from the picked edge, so
     * **thickening the plate leaves the bore where it is** (it used to ride the top face down). Anchoring the
     * space's origin on a corner is how the other reading is asked for.
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
        ed.click(Vec2(-15.0, 12.0))
        ed.setTool(Tools.CUT)
        ed.key("1")
        ed.key("0")
        ed.key("Enter")
        ed.click(Vec2(-12.5, 12.0))
        // the cut's *tool* solid is the bore; the part is what it left (OP-17's one gesture, two solids)
        val drill = ed.solids()[1]

        // stretch the plate in the plan view: the corner (0,0) goes to (-20,-10)
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.setTool(Tools.SELECT)
        val elementsBefore = ed.doc.elements.size
        ed.drag(Vec2(0.0, 0.0), Vec2(-20.0, -10.0))
        assertEquals(elementsBefore, ed.doc.elements.size, "a drag recomputes the frame; it creates nothing")

        val moved = assertNotNull(Geom3.bounds(ed.meshOf(drill)))
        assertClose(
            (moved.first.x + moved.second.x) / 2,
            15.0,
            tol = 0.02,
            msg = "still 15 mm back from the edge's midpoint, which moved to x = 30",
        )
        assertClose(
            moved.first.y,
            -10.0 - Geom3.TOOL_STEP_MM,
            tol = 1e-9,
            msg = "and still one micron off the face, which moved to y = -10",
        )
        assertClose((moved.first.z + moved.second.z) / 2, 12.0, tol = 0.02)

        // thicken the plate: v is measured up from the picked edge, so the bore stays where it is
        ed.doc.setParameter(ed.doc.scalars.single { it.name == "thickness" }, 30.0.mm)
        val raised = assertNotNull(Geom3.bounds(ed.meshOf(drill)))
        assertClose((raised.first.z + raised.second.z) / 2, 12.0, tol = 0.02, msg = "12 mm above the edge it is measured from")

        // the face outline follows too — the view says where the material now is
        val r = assertNotNull(ed.doc.faceOutline(ed.doc.spaceNamed("face1")!!, Evaluator()))
        assertClose(r[1].x, 50.0, msg = "half the stretched edge, which the frame is centred on")
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
        // clear of the face rectangle ([-40,40] x [0,20]), which *is* addressable — see below.
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(80.0, 50.0))
        assertNull(ed.selection, "a plan point is not pickable in the face view")
        // ...but the face's solid is, on the rectangle the face covers (its own picked edge, at the origin)
        ed.click(Vec2(0.0, 0.0))
        assertEquals(base, ed.selection, "the base solid is addressable as its own face")

        // draw a circle here, then go back: it is gone from the plan's picking and drawing
        ed.setTool(Tools.CIRCLE_R)
        ed.key("3")
        ed.key("Enter")
        ed.click(Vec2(-15.0, 12.0))
        val faceCircle = ed.doc.elements.last { it.kind == ElementKind.CIRCLE }
        val faceSvg = SvgDrawTarget().also { ed.render(it) }.svg()

        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(-12.0, 12.0))
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
        ed.click(Vec2(-15.0, 12.0))

        // back to the plan and draw there too, so the script has to switch back
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-40.0, -40.0))
        // ...and once more into the face
        assertTrue(ed.setActiveSpace("face1"))
        ed.setTool(Tools.CIRCLE_R)
        ed.key("2")
        ed.key("Enter")
        ed.click(Vec2(20.0, 10.0))

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
        assertClose(ev.plane(reloaded.spaceNamed("face1")!!.plane!!).origin.z, 0.0)
        assertClose(ev.plane(reloaded.spaceNamed("face1")!!.plane!!).origin.x, 40.0)

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
        ed.click(Vec2(-15.0, 12.0))
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
        ed.click(Vec2(-15.0, 12.0))
        ed.setTool(Tools.EXTRUDE)
        ed.key("4")
        ed.key("Enter")
        ed.click(Vec2(-12.0, 12.0))
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
        assertClose(p.origin.y, 25.0, msg = "the origin is that edge's own midpoint")
        assertClose(p.origin.z, 0.0)
        assertClose(p.u.y, 1.0, msg = "u runs along that edge, which the boundary traverses upward")
        assertClose(p.v.z, 1.0, msg = "v runs up into the face")
        assertClose(p.normal.x, 1.0, msg = "and the sketch normal points out of the material, at the viewer")
        val r = assertNotNull(ed.doc.faceOutline(ed.activeSpace, Evaluator()))
        assertClose(r[1].x, 25.0, msg = "that face is 50 long, centred on the picked edge")
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
        ed.click(Vec2(-15.0, 12.0))
        ed.setTool(Tools.EXTRUDE)
        ed.key("1")
        ed.key("0")
        ed.key("Enter")
        ed.click(Vec2(-12.5, 12.0))
        val drill = ed.solids().last()

        val both = Scene3.extract(ed.doc).solids.map { it.elementId }
        assertTrue(both.contains(base.id), "the plate is not the drill's material, so it is still an output; got $both")
        assertTrue(both.contains(drill.id), "and the drill has nothing built on it yet")

        // ...and once the boolean *does* consume them, only the part is drawn
        ed.setTool(Tools.SUBTRACT)
        ed.click(onFrontEdge)
        ed.click(Vec2(-12.5, 12.0))
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
