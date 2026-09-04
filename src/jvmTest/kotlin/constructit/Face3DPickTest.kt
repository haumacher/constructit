package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Camera3
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.MeshBool
import constructit.geom.Section3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Click a face of a solid in the 3D view and that face is the working plane** (edit-in-3D slice 2).
 *
 * The mechanism under every test here is one sentence: the ray answers with a body and a *point*
 * ([Geom3.rayMesh]), and *which face* that point is on is asked of the **feature's own face list**
 * ([Section3.faceAt]) rather than of the triangles it was hit on — so the answer is an index in the stored
 * address space ([Section3.FACE_ADDRESS_CONVENTION]) and the gesture records the very
 * `sketchspace el= piece=` step the 2D gesture writes. Two gestures, one behaviour, one recorded choice
 * (OP-1/OP-18): a replay re-resolves nothing, which the last test proves by moving the drawing until a fresh
 * ray would answer differently.
 */
class Face3DPickTest {
    private val wPx = 800.0
    private val hPx = 600.0

    // ---- driving the two views ----

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    /** A click in the 3D view **aimed at a point of the world** — which is what aiming at a face means. */
    private fun Viewport3.clickWorld(p: Vec3): Vec2 {
        val s = assertNotNull(camera.project(p, widthPx, heightPx), "$p has an image on screen")
        pointerDown(s)
        pointerUp(s)
        return s
    }

    /** A click in the 3D view at a plane point of the **active** working plane (drawing, once a face is open). */
    private fun Viewport3.clickPlane(at: Vec2) {
        val p = assertNotNull(projection(), "a working plane under the 3D view")
        val s = assertNotNull(p.toScreen(at), "$at has an image")
        pointerDown(s)
        pointerUp(s)
    }

    private fun requireEngine() = assumeTrue(MeshBool.available, "mesh boolean engine unavailable")

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(el: Element): Mesh3 = Evaluator().solid(el.ref as SolidRef).mesh

    @Suppress("UNCHECKED_CAST")
    private fun featureOf(el: Element): Feature3 = Evaluator().solid(el.ref as SolidRef).feature

    private fun view(
        ed: Editor,
        cam: Camera3,
    ): Viewport3 {
        val vp = Viewport3(camera = cam, widthPx = wPx, heightPx = hPx)
        vp.editor = ed
        vp.shown = true
        return vp
    }

    // ---- the fixtures ----

    /** A 40 x 30 plate 20 deep: x 0..40, y 0..30, z 0..20. Four side faces, two caps. */
    private fun plate(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 30.0))
        ed.activeScalar = ed.doc.newParameter("depth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(20.0, 0.0))
        assertEquals(1, ed.solids().size, "the plate: ${ed.statusHint}")
        return ed
    }

    /**
     * A quarter of a tube: the rectangle `0..100 x 0..20` revolved 90° about the world X axis, so the body is
     * `{x in 0..100, y >= 0, z >= 0, y² + z² <= 400}` — a barrel, two flat annuli, and the two caps the
     * partial turn adds (the cap at the start of the sweep lying in the plan itself).
     */
    private fun quarter(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 20.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-30.0, 0.0))
        ed.click(Vec2(130.0, 0.0))
        ed.setTool(Tools.REVOLVE)
        ed.type("90")
        ed.click(Vec2(50.0, 20.0))
        ed.click(Vec2(-20.0, 0.0))
        assertEquals(1, ed.solids().size, "the quarter tube: ${ed.statusHint}")
        return ed
    }

    // ---- 1. the acceptance: a partial revolve's cap, picked in 3D and drilled ----

    /**
     * **A partial revolve's cap, clicked in the 3D view, drilled from, and written to file as the same step the
     * 2D gesture writes.**
     *
     * The cap at the start of the sweep lies in the plan and faces **away** from a camera above, which is why
     * the camera here is below: a face is picked where it can be seen (the facing rule of [Section3.faceAt]).
     * Everything after the pick is ordinary — a circle on the working plane, a *Cut* through it — and the
     * volume it takes is the exact cylinder, which is what "the face space is the same face space" means.
     */
    @Test
    fun aPartialRevolvesCapIsPickedInThe3DViewAndDrilled() {
        requireEngine()
        val ed = quarter()
        val body = ed.solids().single()
        val before = Geom3.volume(meshOf(body))
        // the chords are inscribed in the barrel, so the mesh is a shade under the exact quarter cylinder
        assertClose(before, PI * 400.0 * 100.0 / 4.0, tol = before * 0.005, msg = "a quarter of a tube")

        // below the plan, looking up at the cap that stands in it
        val vp = view(ed, Camera3(target = Vec3(50.0, 10.0, 5.0), distance = 300.0, yaw = -1.1, pitch = -0.7))
        ed.setTool(Tools.SKETCH_ON_FACE)
        vp.clickWorld(Vec3(50.0, 10.0, 0.0))
        val space = ed.activeSpace
        assertTrue(space.isFace, "the cap opened as a working plane: ${ed.statusHint}")
        assertEquals(4, space.piece, "the caps come after the profile's own four pieces, low angle first")
        assertTrue("cap at the start of the sweep" in ed.statusHint, "and it says which face: ${ed.statusHint}")

        // the drill, in the cap's own coordinates
        val plane = assertNotNull(ed.doc.planeOf(space, Evaluator()))
        val centre = plane.toLocal(Vec3(50.0, 10.0, 0.0))
        ed.setTool(Tools.CIRCLE_R)
        ed.type("5")
        vp.clickPlane(centre)
        ed.setTool(Tools.CUT)
        ed.type("10")
        vp.clickPlane(centre + Vec2(5.0, 0.0))
        val drilled = assertNotNull(ed.solids().lastOrNull { it !== body }, "the bore was drilled: ${ed.statusHint}")
        val mesh = meshOf(drilled)
        assertManifold(mesh, "a quarter tube drilled through its own cap, picked in 3D")
        val after = Geom3.volume(mesh)
        assertClose(before - after, PI * 25.0 * 10.0, tol = PI * 25.0 * 10.0 * 0.02, msg = "the 5 mm bore, 10 mm deep")

        // the file: the very step the 2D gesture writes, and a byte-equal replay of the whole story
        val text = DocumentFormat.save(ed.doc)
        val step = assertNotNull(text.lines().firstOrNull { it.trim().startsWith("sketchspace") }, text)
        assertTrue("piece=4" in step, "the address is the piece index, recorded: $step")
        assertTrue("el=" in step, "…of a named element: $step")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "the 3D face pick replays byte-equal")
    }

    // ---- 2. the same face, the two gestures ----

    /**
     * **An extrusion's side face picked in 3D is the same face, in the same frame, as the footprint edge
     * names** — session 32's intrinsic rule, arrived at from the other view: the picked edge on the x axis
     * about its own midpoint, `v` up into the face. A point drawn at the same plane coordinates therefore
     * lands at the same world position, which is the whole claim.
     */
    @Test
    fun anExtrusionsSideFacePickedIn3DLandsWhereThe2DRouteWouldPutIt() {
        val flat = plate()
        flat.setTool(Tools.SKETCH_ON_FACE)
        flat.click(Vec2(20.0, 0.0))
        val flatSpace = flat.activeSpace
        assertEquals(0, flatSpace.piece, "the footprint edge names the first face: ${flat.statusHint}")
        flat.setTool(Tools.POINT)
        flat.click(Vec2(5.0, 8.0))
        val flatAt = assertNotNull(flat.doc.planeOf(flatSpace, Evaluator())).toWorld(Vec2(5.0, 8.0))

        val ed = plate()
        val vp = view(ed, Camera3(target = Vec3(20.0, 5.0, 10.0), distance = 260.0, yaw = -1.3, pitch = 0.5))
        ed.setTool(Tools.SKETCH_ON_FACE)
        assertTrue("3D view" in ed.statusHint, "the tool says how it picks here: ${ed.statusHint}")
        vp.clickWorld(Vec3(20.0, 0.0, 10.0))
        val space = ed.activeSpace
        assertEquals(0, space.piece, "the very face the ray met: ${ed.statusHint}")
        assertTrue("boundary edge #1" in ed.statusHint, "named in the drawing's own words: ${ed.statusHint}")
        val rayAt = assertNotNull(ed.doc.planeOf(space, Evaluator())).toWorld(Vec2(5.0, 8.0))
        assertClose(rayAt.x, flatAt.x, tol = 1e-9, msg = "the same frame, x")
        assertClose(rayAt.y, flatAt.y, tol = 1e-9, msg = "the same frame, y")
        assertClose(rayAt.z, flatAt.z, tol = 1e-9, msg = "the same frame, z")

        // and the two files record the same address, which is the point of there being one address space
        val one = DocumentFormat.save(flat.doc).lines().first { it.trim().startsWith("sketchspace") }
        val two = DocumentFormat.save(ed.doc).lines().first { it.trim().startsWith("sketchspace") }
        assertEquals(one, two, "one step, two gestures")
    }

    /**
     * **The top of a plate — a face no footprint edge projects to.** That is the coverage the 3D click adds:
     * the flat ends sit past the footprint's own pieces in the address space, so a cap gets an index and a
     * `sketchspace` step of its own, and its frame is the coordinates the footprint was drawn in.
     */
    @Test
    fun aCapNoFootprintEdgeProjectsToIsPickedInThe3DView() {
        val ed = plate()
        val vp = view(ed, Camera3(target = Vec3(20.0, 15.0, 20.0), distance = 260.0, yaw = -1.1, pitch = 0.9))
        ed.setTool(Tools.SKETCH_ON_FACE)
        vp.clickWorld(Vec3(10.0, 10.0, 20.0))
        val space = ed.activeSpace
        assertTrue(space.isFace, "the top face opened as a working plane: ${ed.statusHint}")
        assertEquals(5, space.piece, "four boundary pieces, then the bottom cap, then the top one")
        assertTrue("top face" in ed.statusHint, "and it says which face: ${ed.statusHint}")
        val plane = assertNotNull(ed.doc.planeOf(space, Evaluator()))
        val at = plane.toWorld(Vec2(10.0, 10.0))
        assertClose(at.x, 10.0, tol = 1e-9, msg = "the footprint's own coordinates, x")
        assertClose(at.y, 10.0, tol = 1e-9, msg = "…and y")
        assertClose(at.z, 20.0, tol = 1e-9, msg = "…standing on the top face")
        assertClose(plane.normal.z, 1.0, tol = 1e-9, msg = "the normal points out of the material")

        val text = DocumentFormat.save(ed.doc)
        assertTrue("piece=5" in text, "the cap's address is recorded like any other: $text")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "…and it reloads byte-equal")
    }

    /**
     * **A loft's flats, both kinds**: a pyramid's lateral face is the band its footprint edge names, and its
     * **base** — the terminal section, which no footprint edge projects to either — sits past them in the
     * address space. The apex end contributes no face at all, which is why there is exactly one flat end here.
     */
    @Test
    fun bothKindsOfALoftsFlatFacesArePickedInThe3DView() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.type("90")
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(50.0, 50.0))
        assertEquals(1, ed.solids().size, "the pyramid: ${ed.statusHint}")

        // the flank over the first footprint edge, from outside it
        val vp = view(ed, Camera3(target = Vec3(50.0, 20.0, 30.0), distance = 400.0, yaw = -1.4, pitch = 0.4))
        ed.setTool(Tools.SKETCH_ON_FACE)
        vp.clickWorld(Vec3(50.0, 100.0 / 6.0, 30.0))
        assertEquals(0, ed.activeSpace.piece, "the band the first footprint edge names: ${ed.statusHint}")

        // …and the base, which only a ray from underneath can reach
        vp.camera = Camera3(target = Vec3(50.0, 50.0, 10.0), distance = 400.0, yaw = 0.6, pitch = -0.8)
        ed.setTool(Tools.SKETCH_ON_FACE)
        vp.clickWorld(Vec3(50.0, 50.0, 0.0))
        assertEquals(4, ed.activeSpace.piece, "four bands, then the loft's own terminal section: ${ed.statusHint}")
        assertTrue("section 1" in ed.statusHint, "named as the section it is: ${ed.statusHint}")
        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "both addresses reload byte-equal")
    }

    // ---- 3. a dressed body ----

    /**
     * **A blend does not renumber anything.** A face space opened on a plate's side face before it is filleted
     * still means that face afterwards — the dressed body's face #1 is the same plane — and a 3D click on the
     * dressed body's surviving flank answers with the **base's** index.
     */
    @Test
    fun aDressedBodyIsPickedAtItsBaseAddressAndAnOlderSpaceStillMeansItsFace() {
        val ed = plate()
        val base = ed.solids().single()
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(20.0, 0.0))
        val space = ed.activeSpace
        assertEquals(0, space.piece)
        val planeBefore = assertNotNull(ed.doc.planeOf(space, Evaluator()))

        // dress it: the top rim over that very face, rounded
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.activeScalar = ed.doc.newParameter("r", 4.0.mm)
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(20.0, 0.0))
        val dressed = assertNotNull(ed.solids().lastOrNull { it !== base }, "the fillet: ${ed.statusHint}")
        assertTrue(featureOf(dressed) is Feature3.Blend, "a dress-up feature")

        // the older space is untouched, and the *dressed* body answers the same address with the same plane
        val planeAfter = assertNotNull(ed.doc.planeOf(space, Evaluator()))
        assertClose(planeAfter.origin.y, planeBefore.origin.y, tol = 1e-12, msg = "the space did not move")
        val onDressed = assertNotNull(Section3.facePatchOfFootprintPiece(featureOf(dressed), 0).first, "face #1 of the dressed body")
        val dp = assertNotNull(onDressed.plane)
        assertClose(dp.origin.x, planeBefore.origin.x, tol = 1e-12, msg = "the same face, x")
        assertClose(dp.origin.z, planeBefore.origin.z, tol = 1e-12, msg = "the same face, z")

        // …and a 3D click on the dressed flank, well clear of the rounded band, records that base index
        val vp = view(ed, Camera3(target = Vec3(20.0, 5.0, 8.0), distance = 260.0, yaw = -1.3, pitch = 0.4))
        ed.setTool(Tools.SKETCH_ON_FACE)
        vp.clickWorld(Vec3(20.0, 0.0, 5.0))
        val second = ed.activeSpace
        assertTrue(second !== space, "a face of a different body is a space of its own: ${ed.statusHint}")
        assertEquals(0, second.piece, "the base's own index, on the dressed body: ${ed.statusHint}")
        assertEquals(dressed, second.anchor, "and it is the dressed body it is a face of")
        assertEquals(DocumentFormat.save(ed.doc), DocumentFormat.save(DocumentFormat.load(DocumentFormat.save(ed.doc))))
    }

    /** **A blend's own band is not a plane**, and clicking it says so instead of opening something else. */
    @Test
    fun aBlendsOwnBandRefusesByName() {
        val ed = plate()
        val base = ed.solids().single()
        ed.activeScalar = ed.doc.newParameter("r", 4.0.mm)
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(20.0, 0.0))
        assertNotNull(ed.solids().lastOrNull { it !== base }, "the fillet: ${ed.statusHint}")

        // the quarter-round runs along y = 0 at the top: its surface is the cylinder centred (y, z) = (4, 16)
        val onBand = Vec3(20.0, 4.0 - 4.0 * kotlin.math.cos(0.6), 16.0 + 4.0 * kotlin.math.sin(0.6))
        val vp = view(ed, Camera3(target = Vec3(20.0, 5.0, 14.0), distance = 200.0, yaw = -1.4, pitch = 0.7))
        ed.setTool(Tools.SKETCH_ON_FACE)
        vp.clickWorld(onBand)
        assertTrue(ed.activeSpace.isPlan, "nothing opened: ${ed.statusHint}")
        assertTrue("rounded band" in ed.statusHint, "the band is named: ${ed.statusHint}")
        assertTrue("flat faces" in ed.statusHint, "…and what does work is named too: ${ed.statusHint}")
    }

    // ---- 4. the two refusals ----

    /** **A barrel is no plane**: the refusal names the surface it is and the flat faces that do exist. */
    @Test
    fun aCurvedBandRefusesByNameInThe3DView() {
        val ed = quarter()
        val spaces = ed.doc.spaces.size
        val vp = view(ed, Camera3(target = Vec3(50.0, 8.0, 12.0), distance = 300.0, yaw = -1.2, pitch = 0.6))
        ed.setTool(Tools.SKETCH_ON_FACE)
        // squarely on the barrel, at 60° round the sweep
        vp.clickWorld(Vec3(50.0, 20.0 * kotlin.math.cos(1.05), 20.0 * kotlin.math.sin(1.05)))
        assertTrue(ed.activeSpace.isPlan, "nothing opened: ${ed.statusHint}")
        assertEquals(spaces, ed.doc.spaces.size, "and nothing was created")
        assertTrue("cylinder" in ed.statusHint, "the band is named by its surface: ${ed.statusHint}")
        assertTrue("datum plane" in ed.statusHint, "…with what does work instead: ${ed.statusHint}")
        assertTrue("flat faces" in ed.statusHint, "…and which faces those are: ${ed.statusHint}")
    }

    /**
     * **A mesh-route body has no face to name**, and says which route it took. This is the *mesh* half of the
     * parked face-ID provenance item, refusing by name rather than guessing at a triangle's identity.
     */
    @Test
    fun aMeshBooleanRefusesByItsRoute() {
        requireEngine()
        val ed = plate()
        // a bore drilled through a side face is a cross-axis boolean, hence the general mesh route
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(20.0, 0.0))
        ed.setTool(Tools.CIRCLE_R)
        ed.type("4")
        ed.click(Vec2(0.0, 10.0))
        ed.setTool(Tools.CUT)
        ed.type("12")
        ed.click(Vec2(4.0, 10.0))
        val bored = ed.solids().last()
        assertTrue(featureOf(bored) is Feature3.MeshBoolean, "the general boolean's result: ${ed.statusHint}")

        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        val spaces = ed.doc.spaces.size
        val vp = view(ed, Camera3(target = Vec3(20.0, 15.0, 10.0), distance = 260.0, yaw = -1.1, pitch = 0.8))
        ed.setTool(Tools.SKETCH_ON_FACE)
        vp.clickWorld(Vec3(20.0, 20.0, 20.0))
        assertEquals(spaces, ed.doc.spaces.size, "nothing opened: ${ed.statusHint}")
        assertTrue("mesh-only" in ed.statusHint, "the route is named: ${ed.statusHint}")
        assertTrue(ed.doc.nameOf(bored) in ed.statusHint, "…and so is the body: ${ed.statusHint}")
    }

    // ---- 5. one face, one space ----

    /** **Clicking a face that already has a space shows it** — from either view, since it is one behaviour. */
    @Test
    fun clickingAFaceThatAlreadyHasASpaceActivatesItInsteadOfDoublingIt() {
        val ed = plate()
        val vp = view(ed, Camera3(target = Vec3(20.0, 5.0, 10.0), distance = 260.0, yaw = -1.3, pitch = 0.5))
        ed.setTool(Tools.SKETCH_ON_FACE)
        vp.clickWorld(Vec3(20.0, 0.0, 10.0))
        val space = ed.activeSpace
        assertEquals(2, ed.doc.spaces.size, "the plan and one face: ${ed.statusHint}")
        val elements = ed.doc.elements.size
        val journal = ed.doc.journal.size

        // …the same face, in the 3D view again
        ed.setTool(Tools.SKETCH_ON_FACE)
        vp.clickWorld(Vec3(25.0, 0.0, 6.0))
        assertEquals(2, ed.doc.spaces.size, "still one face space: ${ed.statusHint}")
        assertTrue(ed.activeSpace === space, "and it is the one that was already there")
        assertTrue("already has" in ed.statusHint, "said out loud: ${ed.statusHint}")
        assertEquals(elements, ed.doc.elements.size, "nothing was built")
        assertEquals(journal, ed.doc.journal.size, "and nothing was recorded")

        // …and the same face, from the plan, by its footprint edge — the 2D canvas back on screen, which is
        // what takes the perspective projection off the editor again
        vp.shown = false
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(20.0, 0.0))
        assertEquals(2, ed.doc.spaces.size, "one behaviour, two gestures: ${ed.statusHint}")
        assertTrue(ed.activeSpace === space, "the 2D route activates it too")
    }

    // ---- 6. the address, not the coincidence ----

    /**
     * **The stored address survives geometry that would make a fresh ray choose differently** (OP-18's lesson).
     *
     * A plate's flank is picked in the 3D view; then a second body is built between the camera and that flank.
     * The very same screen position now resolves to the *other* body's face — asserted, so the test is about
     * the address and not about a coincidence — while the recorded step still names the first body and the
     * same piece, and the reload agrees to the byte.
     */
    @Test
    fun theRecordedAddressIsNotReResolvedOnReplay() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(60.0, 0.0))
        ed.click(Vec2(100.0, 30.0))
        ed.activeScalar = ed.doc.newParameter("depth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(80.0, 0.0))
        val far = ed.solids().single()

        val vp = view(ed, Camera3(target = Vec3(80.0, 0.0, 10.0), distance = 200.0, yaw = -PI / 2.0, pitch = 0.3))
        ed.setTool(Tools.SKETCH_ON_FACE)
        val screen = vp.clickWorld(Vec3(80.0, 0.0, 10.0))
        val space = ed.activeSpace
        assertEquals(0, space.piece, "the flank facing the camera: ${ed.statusHint}")
        assertEquals(far, space.anchor)

        // an occluder, straight in front of that flank
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(60.0, -40.0))
        ed.click(Vec2(100.0, -10.0))
        ed.activeScalar = ed.doc.newParameter("tall", 40.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(80.0, -40.0))
        val near = assertNotNull(ed.solids().lastOrNull { it !== far }, "the occluder: ${ed.statusHint}")

        val text = DocumentFormat.save(ed.doc)
        val step = assertNotNull(text.lines().firstOrNull { it.trim().startsWith("sketchspace") }, text)
        assertTrue("el=${ed.doc.nameOf(far)} " in "$step " && "piece=0" in step, "the far body's own face, recorded: $step")

        // the reload takes it verbatim
        val again = DocumentFormat.load(text)
        val reloaded = assertNotNull(again.spaces.firstOrNull { it.isFace }, "the face space came back")
        assertEquals(0, reloaded.piece, "the same address")
        assertEquals(ed.doc.nameOf(far), again.nameOf(assertNotNull(reloaded.anchor)), "on the same body")
        assertEquals(text, DocumentFormat.save(again), "byte-equal")

        // …and re-resolving *would* have chosen otherwise: the same pixel now answers with the occluder.
        // Through the viewport, because that is what a click in the 3D view is: since session 80 a
        // projection the view lends is released the moment another surface delivers a gesture
        // ([Editor.viewPointing]), and the flat clicks that built the occluder are such gestures.
        ed.setTool(Tools.SKETCH_ON_FACE)
        vp.pointerDown(screen)
        vp.pointerUp(screen)
        assertEquals(near, ed.activeSpace.anchor, "a fresh ray meets the near body now: ${ed.statusHint}")
        assertTrue(ed.activeSpace !== space, "which is a different face space altogether")
    }
}
