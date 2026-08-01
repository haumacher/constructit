package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.Point3Value
import constructit.dsl.SolidRef
import constructit.dsl.point3
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.Camera3
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.HeightPointHandle
import constructit.editor.PlanePerspective
import constructit.editor.ScalarEntry
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.Geom3
import constructit.geom.Plane3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The height point** (OP-25) — the apex generalized, and the user's design end to end:
 *
 * > "One could construct an arbitrary free point in 3D from a point in 2D and a given height parameter.
 * > This 3D point has 1 dof over its base point - the height. Such point (and the apex of an extruded
 * > outline is exactly such point) can then be manipulated even in the 3D scene - adjusting the height
 * > parameter (in reverse) - by estimating the distance of the base point to the projection of the mouse
 * > coordinate on the height line."
 *
 * Four things are asserted here and nowhere else: that the everyday pyramid gesture now *yields* one (its
 * height a named scalar that moves the solid), that a standalone one embeds correctly on a **tilted** face
 * plane, that the reverse drag in the 3D view writes the height to the ray-to-line answer — with the camera
 * untouched and the solid following — and that the two edges of that rule hold: a **wired** height refuses
 * the drag exactly as a welded point does, and a **near-parallel** view holds the height instead of
 * exploding it.
 */
class HeightPointTest {
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
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    private fun Editor.heightPoints(): List<Element> = doc.elements.filter { it.kind == ElementKind.HEIGHT_POINT }

    @Suppress("UNCHECKED_CAST")
    private fun volumeOf(el: Element): Double = Geom3.volume(Evaluator().solid(el.ref as SolidRef).mesh)

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(el: Element) = Evaluator().solid(el.ref as SolidRef).mesh

    private fun at3(el: Element): Vec3 {
        val v = assertNotNull(Evaluator().valueOf(el.ref) as? Point3Value, "a height point evaluates to a point in space")
        return v.p
    }

    private fun scalarNamed(
        ed: Editor,
        name: String,
    ): ScalarEntry = assertNotNull(ed.doc.scalars.firstOrNull { it.name == name }, "the panel has a scalar named '$name'")

    private fun roundTrips(
        ed: Editor,
        msg: String,
    ) {
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), msg)
    }

    /** A 100 x 100 square in the plan, run to an apex 90 mm over its centre — the acceptance pyramid. */
    private fun pyramid(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.type("90")
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(50.0, 50.0))
        return ed
    }

    // ---- 1. the everyday gesture yields one, and its height is an ordinary parameter ----

    /**
     * The pyramid built by the two clicks it has always taken: its apex **is** a height point, standing where
     * the formula says, with the typed height a named scalar — retype it and the apex rises, the solid with it.
     */
    @Test
    fun theExtrudeToPointApexIsAHeightPointWhoseHeightIsANamedScalar() {
        val ed = pyramid()
        val solid = ed.solids().single()
        val apex = ed.heightPoints().single()
        assertEquals(Vec3(50.0, 50.0, 90.0), at3(apex), "embed(base) + h * n, in the plan's own world: ${ed.statusHint}")
        assertClose(volumeOf(solid), 300000.0, tol = 1e-6, msg = "100 x 100 x 90 / 3")

        // the height is a first-class scalar, editable by name from the panel
        val height = scalarNamed(ed, "height")
        assertClose(Evaluator().scalar(height.ref).mm, 90.0, tol = 1e-9, msg = "the typed number is what it holds")
        ed.doc.setParameter(height, 45.0.mm)
        assertEquals(Vec3(50.0, 50.0, 45.0), at3(apex), "the apex followed its parameter")
        assertClose(volumeOf(solid), 150000.0, tol = 1e-6, msg = "and the solid followed the apex")
        assertManifold(meshOf(solid), "the pyramid after a height edit")

        // ...and the base is still an ordinary 2D point, draggable where it lives: Cavalieri keeps the volume
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(50.0, 50.0), Vec2(260.0, 40.0))
        assertClose(at3(apex).x, 260.0, tol = 1e-6, msg = "the base moved in the plan, and the apex with it")
        assertClose(at3(apex).z, 45.0, tol = 1e-9, msg = "...at the height it had: the base drag writes no height")
        assertClose(volumeOf(solid), 150000.0, tol = 1e-6, msg = "an oblique pyramid of the same volume")
        roundTrips(ed, "a pyramid whose apex is a height point replays byte-equal")
    }

    /** The plan draws no second dot for it: what the apex looks like in 2D is exactly what it always was. */
    @Test
    fun thePlanShowsNoChromeForIt() {
        val ed = pyramid()
        val apex = ed.heightPoints().single()
        // not pickable in the 2D canvas at all — the plan edits the *base*, which sits at the same place
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(50.0, 50.0))
        val hit = assertNotNull(ed.selection, "something was selected at the apex position")
        assertTrue(hit !== apex, "the plan click reached the base point, not the height point: ${ed.doc.nameOf(hit)}")
        assertEquals(ElementKind.POINT, hit.kind, "and it is the ordinary free point the gesture placed")
    }

    // ---- 2. a standalone one, on a tilted plane ----

    /**
     * The tool on its own, on the **slant face** of a pyramid: the point stands off that face along its own
     * normal — which since the session-32 frame rule *is* outward, because a face plane's normal points out
     * of the material — and the numbers are the exact ones the face's geometry prescribes. The lift sign that
     * used to make a face the exception is gone rather than compensated (`Document.liftSign`).
     */
    @Test
    fun aHeightPointOnAFaceStandsOffThatFacesOwnPlane() {
        val ed = pyramid()
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(30.0, 0.0))
        assertTrue(ed.activeSpace.isFace, "on the slant face: ${ed.statusHint}")
        val plane = assertNotNull(ed.doc.activePlane3(Evaluator()), "the face plane evaluates")

        val base = Vec2(0.0, 20.0)
        ed.setTool(Tools.HEIGHT_POINT)
        ed.type("25")
        ed.click(base)
        val point = ed.heightPoints().single { it.space == ed.activeSpace.name }

        val n = plane.normal.normalized()
        val expected = plane.toWorld(base) + n * 25.0
        val got = at3(point)
        assertClose((got - expected).length(), 0.0, tol = 1e-9, msg = "embed(base) + h * n, and n points out of the face: $got")
        // ...which is to say: exactly 25 mm off the plane, on the side the material is not
        assertClose(plane.distanceTo(got), 25.0, tol = 1e-9, msg = "25 mm clear of the face")
        assertClose(plane.toLocal(got).x - base.x, 0.0, tol = 1e-9, msg = "and straight above its base in the face's own u")
        assertClose(plane.toLocal(got).y - base.y, 0.0, tol = 1e-9, msg = "...and in its v")
        // the slant is genuinely tilted, so this says something a plan test could not
        assertTrue(kotlin.math.abs(n.z) > 1e-3 && kotlin.math.abs(n.z) < 0.999, "the face is tilted: n = $n")
        roundTrips(ed, "a height point on a face replays byte-equal")
    }

    /** A height point picked as a loft's apex is taken as it is — the loft is a consumer, not a builder. */
    @Test
    fun theLoftTakesAHeightPointAsItsApex() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.HEIGHT_POINT)
        ed.type("90")
        ed.click(Vec2(50.0, 50.0))
        val apex = ed.heightPoints().single()

        // picked **in the 3D view**, which is where a height point is drawn and therefore where it is
        // pickable — one rule, and the loft's slot needed nothing new to accept it
        val vp = viewOver(ed)
        ed.setTool(Tools.LOFT)
        for (p in listOf(Vec3(30.0, 0.0, 0.0), Vec3(50.0, 50.0, 90.0))) {
            vp.pointerDown(vp.screenOf(p))
            vp.pointerUp(vp.screenOf(p))
        }
        ed.key("Enter")
        val solid = assertNotNull(ed.solids().singleOrNull(), "the loft was built: ${ed.statusHint}")
        assertClose(volumeOf(solid), 300000.0, tol = 1e-6, msg = "the same exact pyramid, built the general way")
        assertManifold(meshOf(solid), "loft to a height point")

        // one node, two readings: retyping the height moves the loft, which is what "consumer" means
        ed.doc.setParameter(scalarNamed(ed, "height"), 30.0.mm)
        assertClose(volumeOf(solid), 100000.0, tol = 1e-6, msg = "the loft followed the height point's parameter")
        assertEquals(Vec3(50.0, 50.0, 30.0), at3(apex), "and the point itself is where the formula says")
        roundTrips(ed, "loft over a height point replays byte-equal")
    }

    // ---- 3. the reverse drag ----

    /** A 3D view over the plan of [ed], framed on the pyramid. */
    private fun viewOver(ed: Editor): Viewport3 {
        val vp =
            Viewport3(
                camera = Camera3(target = Vec3(50.0, 50.0, 40.0), distance = 320.0, yaw = -0.9, pitch = 0.5),
                widthPx = wPx,
                heightPx = hPx,
            )
        vp.editor = ed
        vp.shown = true
        return vp
    }

    /** Where the world point [p] is on this view's screen — the test's own projection, not the seam's. */
    private fun Viewport3.screenOf(p: Vec3): Vec2 = assertNotNull(camera.project(p, widthPx, heightPx), "$p projects")

    /**
     * **The reverse drag, exactly**: grabbing the apex in the 3D view and dragging to the screen position of a
     * point 140 mm up the height line writes **140** into the height parameter — because the pointer's ray
     * through that pixel passes through that very point, so the closest approach on the line is it.
     *
     * Asserted against a position computed from the camera alone, so the number is the geometry's and not the
     * implementation's. The camera does not move (a plain drag belongs to the editor, session 29's reversal),
     * and the solid follows, which is the whole point of editing the apex rather than a number.
     */
    @Test
    fun draggingTheApexInThe3DViewWritesTheRayToLineHeight() {
        val ed = pyramid()
        val solid = ed.solids().single()
        val apex = ed.heightPoints().single()
        ed.setTool(Tools.SELECT)
        val vp = viewOver(ed)
        val camBefore = vp.camera

        val from = vp.screenOf(Vec3(50.0, 50.0, 90.0))
        val to = vp.screenOf(Vec3(50.0, 50.0, 140.0))
        vp.pointerDown(from)
        assertEquals(apex, ed.selection, "the press grabbed the apex: ${ed.statusHint}")
        vp.pointerMove(vp.screenOf(Vec3(50.0, 50.0, 115.0)))
        vp.pointerMove(to)
        vp.pointerUp(to)

        assertClose(Evaluator().scalar(scalarNamed(ed, "height").ref).mm, 140.0, tol = 1e-6, msg = "the drag wrote the height: ${ed.statusHint}")
        assertClose((at3(apex) - Vec3(50.0, 50.0, 140.0)).length(), 0.0, tol = 1e-9, msg = "so the apex stands where the pointer aimed")
        assertEquals(camBefore, vp.camera, "and the camera did not move at all")
        assertClose(volumeOf(solid), 100.0 * 100.0 * 140.0 / 3.0, tol = 1e-6, msg = "the solid followed")
        assertManifold(meshOf(solid), "the pyramid after a 3D apex drag")

        // the base did not move: the height point owns the height and nothing else (the 1 DOF)
        val base = ed.doc.elements.first { it.kind == ElementKind.POINT }
        assertClose(assertNotNull(Evaluator().point(base)).x, 50.0, tol = 1e-9, msg = "the base stayed in its plane")
        roundTrips(ed, "a 3D height drag restates its scalar and replays byte-equal")
    }

    private fun Evaluator.point(el: Element): Vec2? = (valueOf(el.ref) as? constructit.core.PointValue)?.p

    /** The grab holds where it landed: a press beside the apex does not teleport the height to the cursor. */
    @Test
    fun theGrabDoesNotJump() {
        val ed = pyramid()
        val apex = ed.heightPoints().single()
        ed.setTool(Tools.SELECT)
        val vp = viewOver(ed)
        // press a few pixels below the apex — inside the tolerance, so it grabs, but not on the point
        val from = vp.screenOf(Vec3(50.0, 50.0, 90.0)) + Vec2(0.0, 5.0)
        vp.pointerDown(from)
        assertEquals(apex, ed.selection, "the press grabbed the apex: ${ed.statusHint}")
        val heldAt = at3(apex).z
        assertClose(heldAt, 90.0, tol = 1e-9, msg = "the press alone moved nothing")
        // one pixel of movement moves the height by about a pixel's worth, not by the 5 px the grab was off by
        vp.pointerMove(from + Vec2(0.0, 1.0))
        val after = at3(apex).z
        assertTrue(kotlin.math.abs(after - 90.0) < 3.0, "the height eased off its value rather than jumping: $after")
        vp.pointerUp(from + Vec2(0.0, 1.0))
    }

    // ---- 4. the two edges: a wired height, and a view that cannot say ----

    /**
     * A height **wired** onto another parameter is driven by the construction, so the drag is refused and the
     * refusal names what drives it — the very rule a welded 2D point follows, because it is asked in the very
     * same way ([constructit.editor.isFreeSource]).
     */
    @Test
    fun aWiredHeightRefusesTheDragTheWayAWeldedPointDoes() {
        val ed = pyramid()
        val apex = ed.heightPoints().single()
        val master = ed.doc.newParameter("ridge", 120.0.mm)
        assertTrue(ed.doc.wireParameter(scalarNamed(ed, "height"), master), "the height is wired onto 'ridge'")
        assertEquals(Vec3(50.0, 50.0, 120.0), at3(apex), "so it follows the master")
        assertTrue(!apex.hasFreeDof, "and has no freedom of its own left")

        ed.setTool(Tools.SELECT)
        val vp = viewOver(ed)
        val from = vp.screenOf(Vec3(50.0, 50.0, 120.0))
        val to = vp.screenOf(Vec3(50.0, 50.0, 60.0))
        vp.pointerDown(from)
        vp.pointerMove(to)
        vp.pointerUp(to)
        assertEquals(Vec3(50.0, 50.0, 120.0), at3(apex), "the drag wrote nothing: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("height"), "and the refusal names the driven value: ${ed.statusHint}")

        // ...and the master still moves it, which is what the wire was for
        ed.doc.setParameter(master, 200.0.mm)
        assertEquals(Vec3(50.0, 50.0, 200.0), at3(apex), "move what drives it instead")
    }

    /**
     * **The near-parallel clamp.** When the pointer's ray comes within [HeightPointHandle.MIN_RAY_LINE_SIN]
     * of the height line, no height can honestly be read off it — one pixel would mean many millimetres — so
     * the height is *held* rather than sent to infinity, and the view says why.
     *
     * The 2D canvas is the extreme case of the same rule: it looks straight along the normal, so it can never
     * read a height. That is asserted first, because it is the reason the default answers as it does.
     */
    @Test
    fun aRayNearlyInLineWithTheHeightLineHoldsTheHeight() {
        val ed = pyramid()
        val apex = ed.heightPoints().single()
        val handle = assertNotNull(apex.handle as? HeightPointHandle, "the apex carries a height-point handle")
        val ev = Evaluator()

        // (a) the 2D canvas: its viewing ray *is* the height line, so there is nothing to read
        assertNull(handle.liftFrom(Vec2(50.0, 50.0), ed.camera, ev), "a similarity can read no height")
        handle.drag(Vec2(50.0, 50.0), ed.camera, 0.0, ev)
        assertEquals(Vec3(50.0, 50.0, 90.0), at3(apex), "and writes nothing rather than something wrong")

        // (b) a 3D view looking almost straight down the height line: the same answer, for the same reason
        val plan = Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)
        val overhead = Camera3(target = Vec3(50.0, 50.0, 0.0), distance = 400.0, pitch = PI / 2.0 - 1e-3)
        val steep = PlanePerspective(plan, overhead, wPx, hPx)
        assertNull(handle.liftFrom(Vec2(50.0, 50.0), steep, ev), "within ~3 degrees of the line, no reading")

        // ...and one honest degree further out it reads again, exactly: the ray through a point of the line
        val oblique = PlanePerspective(plan, Camera3(target = Vec3(50.0, 50.0, 40.0), distance = 320.0, yaw = -0.9, pitch = 0.5), wPx, hPx)
        val aimed = assertNotNull(oblique.toPlane(assertNotNull(oblique.camera.project(Vec3(50.0, 50.0, 140.0), wPx, hPx))))
        assertClose(assertNotNull(handle.liftFrom(aimed, oblique, ev)), 140.0, tol = 1e-6, msg = "the ray-to-line answer")

        // the whole gesture through the steep view: the editor holds the height and says so
        ed.setTool(Tools.SELECT)
        ed.pointing = steep
        val from = assertNotNull(overhead.project(Vec3(50.0, 50.0, 90.0), wPx, hPx))
        ed.pointerDown(from)
        ed.pointerMove(from + Vec2(30.0, 20.0))
        val note = ed.statusHint
        ed.pointerUp(from + Vec2(30.0, 20.0))
        ed.pointing = null
        assertClose(at3(apex).z, 90.0, tol = 1e-9, msg = "the height is held, not exploded: $note")
    }

    // ---- 5. the pick, where the two points nearly coincide ----

    /**
     * At a **low** height the apex and its base project to nearly the same pixel, and the existing ranking
     * decides: what is drawn on top takes the press (the height point, created later), and one more click
     * steps the pick cycle to the base. No new rule — the tie-break is the one every pile has used.
     */
    @Test
    fun atALowHeightTheApexTakesThePressAndTheCycleReachesTheBase() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.type("2")
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(50.0, 50.0))
        val apex = ed.heightPoints().single()
        val base = ed.doc.elements.first { it.kind == ElementKind.POINT }

        ed.setTool(Tools.SELECT)
        val vp = viewOver(ed)
        val s = vp.screenOf(Vec3(50.0, 50.0, 2.0))
        vp.pointerDown(s)
        vp.pointerUp(s)
        assertEquals(apex, ed.selection, "the apex is drawn on top, so it takes the press: ${ed.statusHint}")
        vp.pointerDown(s)
        vp.pointerUp(s)
        assertEquals(base, ed.selection, "and a repeat click steps the cycle to the base under it: ${ed.statusHint}")
    }

    // ---- 6. the file ----

    /** The tool's own step round-trips, and the dragged height comes back as the number it now is (OP-18). */
    @Test
    fun theHeightIsStateAndTheFileRestatesIt() {
        val ed = Editor()
        ed.setTool(Tools.HEIGHT_POINT)
        ed.type("40")
        ed.click(Vec2(20.0, 30.0))
        val apex = ed.heightPoints().single()
        assertEquals(Vec3(20.0, 30.0, 40.0), at3(apex), "the standalone point stands where it was asked to")
        roundTrips(ed, "a bare height point replays byte-equal")

        ed.setTool(Tools.SELECT)
        val vp = viewOver(ed)
        val to = vp.screenOf(Vec3(20.0, 30.0, 75.0))
        vp.pointerDown(vp.screenOf(Vec3(20.0, 30.0, 40.0)))
        vp.pointerMove(to)
        vp.pointerUp(to)
        assertClose(at3(apex).z, 75.0, tol = 1e-6, msg = "dragged to 75: ${ed.statusHint}")

        // the height is **state**, so the `param` step the tool introduced re-reads it at save (OP-18) —
        // exactly as a 2D drag's coordinates are re-read, and with no new file machinery for the drag
        val once = DocumentFormat.save(ed.doc)
        val restated = assertNotNull(Regex("""param "height" = ([-0-9.eE]+)mm""").find(once), "the file states the height:\n$once")
        assertClose(restated.groupValues[1].toDouble(), 75.0, tol = 1e-6, msg = "restated as the value it now is, not the one typed:\n$once")
        val back = DocumentFormat.load(once)
        val reloaded = back.elements.single { it.kind == ElementKind.HEIGHT_POINT }
        val p = assertNotNull(Evaluator().valueOf(reloaded.ref) as? Point3Value).p
        assertClose((p - Vec3(20.0, 30.0, 75.0)).length(), 0.0, tol = 1e-6, msg = "and comes back there: $p")
        assertEquals(once, DocumentFormat.save(back), "save -> load -> save is byte-equal")
    }

    /** The document's own DSL still says it in one line, and the value is the formula. */
    @Test
    fun theNodeIsAPureFunctionOfItsThreeInputs() {
        val c = constructit.dsl.Construction()
        val base = c.freePoint("base", 10.mm, 20.mm)
        val h = c.parameter("h", 30.mm)
        val p = c.heightPoint(c.planeXY(), base, h)
        val ev = Evaluator()
        assertEquals(Vec3(10.0, 20.0, 30.0), ev.point3(p))
        c.set(base, 11.mm, 21.mm)
        assertEquals(Vec3(11.0, 21.0, 30.0), Evaluator().point3(p), "it follows its base")
        c.set(h, 5.mm)
        assertEquals(Vec3(11.0, 21.0, 5.0), Evaluator().point3(p), "and its height")
        // an invalid input hides it, like everything else (OP-3)
        val bad = c.heightPoint(c.planeXY(), base, c.parameter("a", constructit.units.Quantity.deg(30.0)))
        assertTrue(Evaluator().eval(bad.node) is EvalResult.Invalid, "an angle is no height")
    }

    /** A plain 2D point is still a perfectly good loft apex — the zero-height case, unchanged in behaviour. */
    @Test
    fun aPlain2DPointStillEndsALoft() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("0")
        ed.type("60")
        ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.POINT)
        ed.click(Vec2(50.0, 50.0))
        ed.setTool(Tools.LOFT)
        ed.click(Vec2(50.0, 50.0))
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.click(Vec2(30.0, 0.0))
        ed.key("Enter")
        val solid = assertNotNull(ed.solids().singleOrNull(), "the loft was built: ${ed.statusHint}")
        assertClose(volumeOf(solid), 200000.0, tol = 1e-6, msg = "a pyramid 60 mm tall over a 100 x 100 base")
        assertEquals(0, ed.heightPoints().size, "and no height point was invented for a point that needs none")
    }
}
