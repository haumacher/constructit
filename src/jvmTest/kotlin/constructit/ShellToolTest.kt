package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Camera3
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.MeshBool
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
 * **The shell as a gesture** (session 75) — one `SOLID` pick, one length, and the face the click named written
 * into the step's `signs=` and never scored again.
 *
 * Two tool rows are two *statements*: *Shell* opens the face the click landed on, *Hollow* leaves the body
 * closed all round. What is asserted here is not that a cavity appeared but that the shell is an ordinary
 * member of the graph and of the file: watertight, exact where the boolean is exact, parametric through a
 * shared thickness (and through an **expression** over one), riding the generic `tool` step with a byte-equal
 * round trip, one undo per gesture, a stored open-face choice a reload takes verbatim — and a body it cannot
 * hollow refused by name instead of forked.
 */
class ShellToolTest {
    private val wPx = 800.0
    private val hPx = 600.0

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    /** A click in the 3D view **aimed at a point of the world** — which is what aiming at a face means. */
    private fun Viewport3.clickWorld(p: Vec3) {
        val s = assertNotNull(camera.project(p, widthPx, heightPx), "$p has an image on screen")
        pointerDown(s)
        pointerUp(s)
    }

    private fun Viewport3.clickPlane(at: Vec2) {
        val p = assertNotNull(projection(), "a working plane under the 3D view")
        val s = assertNotNull(p.toScreen(at), "$at has an image")
        pointerDown(s)
        pointerUp(s)
    }

    private fun view(
        ed: Editor,
        cam: Camera3,
    ): Viewport3 {
        val vp = Viewport3(camera = cam, widthPx = wPx, heightPx = hPx)
        vp.editor = ed
        vp.shown = true
        return vp
    }

    private fun requireEngine() = assumeTrue(MeshBool.available, "mesh boolean engine unavailable")

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(el: Element): Mesh3 {
        val r = Evaluator().eval(el.ref.node)
        assertTrue(r is EvalResult.Ok, "a solid with a value, not ${(r as? EvalResult.Invalid)?.reason}")
        return Evaluator().solid(el.ref as SolidRef).mesh
    }

    private fun volumeOf(el: Element): Double {
        val mesh = meshOf(el)
        assertManifold(mesh, "a hollow body")
        return Geom3.volume(mesh)
    }

    /** A 40 x 30 plate 20 deep, drawn and extruded by gestures — the plainest body with a cap to open. */
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

    private val openTop = 40.0 * 30.0 * 20.0 - 34.0 * 24.0 * 17.0
    private val closed = 40.0 * 30.0 * 20.0 - 34.0 * 24.0 * 14.0

    // ---- one click, one wall ----

    /**
     * **One click hollows the plate and names the face it opened.** The click lands inside the footprint, which
     * *is* the top cap's own outline, so the face this space is looking at is that cap — and the figure is the
     * exact one, because a plate and its cavity are prisms on one axis (OP-22).
     */
    @Test
    fun oneClickHollowsThePlateAndNamesTheFaceItOpened() {
        val ed = plate()
        assertClose(volumeOf(ed.solids().single()), 24000.0, 1e-6)
        ed.activeScalar = ed.doc.newParameter("wall", 3.0.mm)
        ed.setTool(Tools.SHELL)
        ed.click(Vec2(20.0, 0.0))
        assertEquals(2, ed.solids().size, "a shell is a solid of its own: ${ed.statusHint}")
        val said = assertNotNull(ed.statusHint)
        assertTrue(said.contains("hollowed to a wall of 3 mm"), "it says what it did: $said")
        assertTrue(said.contains("with the top face left open"), "and which face it opened: $said")
        assertClose(volumeOf(ed.solids().last()), openTop, 1e-6, "the wall, the floor and nothing else")
    }

    /** **The closed row leaves no opening**, which is a legal shell — the cavity is inset from both caps. */
    @Test
    fun theClosedRowWallsEveryFace() {
        val ed = plate()
        ed.activeScalar = ed.doc.newParameter("wall", 3.0.mm)
        ed.setTool(Tools.SHELL_CLOSED)
        ed.click(Vec2(20.0, 0.0))
        val said = assertNotNull(ed.statusHint)
        assertTrue(said.contains("closed all round"), "it says so: $said")
        assertClose(volumeOf(ed.solids().last()), closed, 1e-6, "both caps walled")
    }

    /** A wall the body cannot host refuses **at the gesture** and heals when the number comes down (OP-3). */
    @Test
    fun aWallThatDoesNotFitRefusesAndHealsWhenItIsRetyped() {
        val ed = plate()
        val t = ed.doc.newParameter("wall", 12.0.mm)
        ed.activeScalar = t
        ed.setTool(Tools.SHELL_CLOSED)
        ed.click(Vec2(20.0, 0.0))
        val el = ed.solids().last()
        val said = assertNotNull((Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason, "12 mm has nowhere to go in a 20 mm plate")
        assertTrue(said.contains("leaves no cavity between the two caps"), "the refusal says what met: $said")
        assertTrue(said.contains("the thickest wall that fits is about"), "and names the number to type: $said")
        ed.doc.setParameter(t, 3.0.mm)
        assertClose(volumeOf(el), closed, 1e-6, "and the same node is a body again")
    }

    // ---- the thickness is an ordinary parameter ----

    /**
     * **Equality by sharing, not by constraint**: one parameter feeding two shells is *"the same wall on both
     * parts"*, and retyping it moves both.
     */
    @Test
    fun oneThicknessParameterDrivesTwoShells() {
        val ed = plate()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(60.0, 0.0))
        ed.click(Vec2(100.0, 30.0))
        ed.activeScalar = ed.doc.newParameter("depth2", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(80.0, 0.0))
        val wall = ed.doc.newParameter("wall", 3.0.mm)
        for (at in listOf(Vec2(20.0, 0.0), Vec2(80.0, 0.0))) {
            ed.activeScalar = wall
            ed.setTool(Tools.SHELL)
            ed.click(at)
        }
        assertEquals(4, ed.solids().size, "two plates, two shells: ${ed.statusHint}")
        val both = ed.solids().takeLast(2).map { volumeOf(it) }
        assertClose(both[0], openTop, 1e-6)
        assertClose(both[1], openTop, 1e-6, "the same wall on the second plate")

        ed.doc.setParameter(wall, 5.0.mm)
        val thicker = 40.0 * 30.0 * 20.0 - 30.0 * 20.0 * 15.0
        for (el in ed.doc.elements.filter { it.kind == ElementKind.SOLID }.takeLast(2)) {
            assertClose(volumeOf(el), thicker, 1e-6, "one number, both walls")
        }
    }

    /**
     * **…and it composes with the expressions package**: a wall bound to `d/8` follows `d`, because an
     * expression is `boundTo` generalized and a shell's thickness is an ordinary scalar node like any other.
     */
    @Test
    fun aWallThicknessBoundToAnExpressionFollowsIt() {
        val ed = plate()
        val d = ed.doc.newParameter("d", 24.0.mm)
        val t = ed.doc.newParameter("wall", 1.0.mm)
        assertTrue(ed.doc.bindParameter(t, "d/8"), "wall = d/8: ${ed.doc.note}")
        ed.activeScalar = t
        ed.setTool(Tools.SHELL)
        ed.click(Vec2(20.0, 0.0))
        assertClose(volumeOf(ed.solids().last()), openTop, 1e-6, "d = 24 makes the wall 3")

        ed.doc.setParameter(d, 8.0.mm)
        val atOne = 40.0 * 30.0 * 20.0 - 38.0 * 28.0 * 19.0
        assertClose(
            volumeOf(ed.doc.elements.last { it.kind == ElementKind.SOLID }),
            atOne,
            1e-6,
            "and d = 8 makes it 1 — the wall follows d",
        )
    }

    // ---- the file, the undo, and the stored choice ----

    @Test
    fun theGestureRoundTripsByteForByteAndTakesOneUndo() {
        val ed = plate()
        ed.activeScalar = ed.doc.newParameter("wall", 3.0.mm)
        ed.setTool(Tools.SHELL)
        ed.click(Vec2(20.0, 0.0))
        assertEquals(2, ed.solids().size)

        val text = DocumentFormat.save(ed.doc)
        val step = text.lines().single { it.startsWith("tool ${Tools.SHELL}") }
        assertTrue(step.contains("signs="), "the shell restates the face it opened: $step")
        assertEquals(1, step.substringAfter("signs=").substringBefore(" ").trim().split(";").size, "one address and nothing else: $step")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save -> load -> save byte-equal")
        val back = DocumentFormat.load(text)
        assertClose(
            volumeOf(back.elements.last { it.kind == ElementKind.SOLID }),
            openTop,
            1e-6,
            "and the reloaded body is the same body",
        )

        ed.undo()
        assertEquals(1, ed.solids().size, "one press takes back the whole shell")
        ed.redo()
        assertEquals(2, ed.solids().size, "and one press puts it back")
    }

    /** The closed row records **no** sign, because it has no discrete choice to make. */
    @Test
    fun theClosedRowRecordsNoChoiceAtAll() {
        val ed = plate()
        ed.activeScalar = ed.doc.newParameter("wall", 3.0.mm)
        ed.setTool(Tools.SHELL_CLOSED)
        ed.click(Vec2(20.0, 0.0))
        val text = DocumentFormat.save(ed.doc)
        val step = text.lines().single { it.startsWith("tool ${Tools.SHELL_CLOSED}") }
        assertTrue(!step.contains("signs="), "nothing was scored, so nothing is restated: $step")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save -> load -> save byte-equal")
    }

    /**
     * **A stored open face is never re-scored** (OP-1/OP-18, the fillet's own lesson two features on).
     *
     * The face is an index into an *ordered* list, and which face a fixed click falls within moves when the body
     * does. So: shell the plate, then drag its footprint clear of the click, and assert the reload still opens
     * the face that was clicked — where a fresh score would find no face under that point at all.
     */
    @Test
    fun replayTakesTheStoredOpenFaceEvenWhereScoringWouldFindNone() {
        val ed = plate()
        ed.activeScalar = ed.doc.newParameter("wall", 3.0.mm)
        ed.setTool(Tools.SHELL)
        ed.click(Vec2(20.0, 0.0))
        val chosen = assertNotNull(ed.statusHint)
        assertTrue(chosen.contains("the top face left open"), "the click chose the cap: $chosen")
        val text = DocumentFormat.save(ed.doc)
        val address = text.lines().single { it.startsWith("tool ${Tools.SHELL}") }.substringAfter("signs=").substringBefore(" ").trim()

        // drag the rectangle's two right-hand corners left, until the clicked point is outside the footprint —
        // a fresh score there would land on no face at all. (The tool stays armed after a gesture, so it is
        // disarmed first: a pointer-down with a tool armed is a pick, not a drag.)
        ed.setTool(Tools.SELECT)
        for (corner in listOf(Vec2(0.0, 0.0), Vec2(0.0, 30.0))) {
            val from = ed.camera.worldToScreen(corner)
            val to = ed.camera.worldToScreen(Vec2(30.0, corner.y))
            ed.pointerMove(from)
            ed.pointerDown(from)
            ed.pointerMove(to)
            ed.pointerUp(to)
        }
        val moved = DocumentFormat.save(ed.doc)
        val back = DocumentFormat.load(moved)
        assertEquals(moved, DocumentFormat.save(back), "save -> load -> save byte-equal after the drag")
        assertTrue(
            DocumentFormat.save(back).lines().single { it.startsWith("tool ${Tools.SHELL}") }.contains("signs=$address"),
            "the reload takes the recorded face verbatim rather than scoring the click again",
        )
        val body = back.elements.last { it.kind == ElementKind.SOLID }
        assertManifold(meshOf(body), "the reloaded shell")
        assertClose(volumeOf(body), 10.0 * 30.0 * 20.0 - 4.0 * 24.0 * 17.0, 1e-6, "the narrower plate, still open at the top")
    }

    // ---- what the hollow body then is: a part you can go on building on ----

    /**
     * **A shelled body's own face carries a sketch, and a Cut drilled from it goes through the wall** — which is
     * the whole point of the shell being a feature rather than a mesh: its faces are still the body's own.
     *
     * The numbers say where the material went: the bore is 10 mm deep into a 3 mm wall, so what it takes is the
     * wall's own thickness and nothing more — the cavity behind it is already empty.
     */
    @Test
    fun aShelledPlatesFaceCarriesASketchAndACutThroughItsWall() {
        requireEngine()
        val ed = plate()
        ed.activeScalar = ed.doc.newParameter("wall", 3.0.mm)
        ed.setTool(Tools.SHELL)
        ed.click(Vec2(20.0, 0.0))
        val shell = ed.solids().last()
        val before = volumeOf(shell)

        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(20.0, 0.0))
        val space = ed.activeSpace
        assertTrue(space.isFace, "a wall of the hollow body opened as a working plane: ${ed.statusHint}")
        assertEquals(0, space.piece, "the footprint edge names the first face: ${ed.statusHint}")

        ed.setTool(Tools.CIRCLE_R)
        ed.type("4")
        ed.click(Vec2(0.0, 10.0))
        ed.setTool(Tools.CUT)
        ed.type("10")
        ed.click(Vec2(4.0, 10.0))
        val drilled = assertNotNull(ed.solids().lastOrNull { it !== shell }, "the bore was drilled: ${ed.statusHint}")
        val after = volumeOf(drilled)
        val exact = PI * 16.0 * 3.0
        assertClose(before - after, exact, exact * 0.03, "the bore takes the wall's own thickness and no more")
        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "and the whole chain round-trips byte-equal")
    }

    /**
     * **The face to open is picked in the 3D view** — the pointer's own ray, resolved against the feature's own
     * face list ([constructit.geom.Section3.faceAt], the seam edit-in-3D slice 2 built). The camera stands
     * above the plate, so the face under the pointer is the top cap, and the recorded address is the same one
     * the flat gesture writes.
     */
    @Test
    fun theFaceToOpenIsPickedInThe3DView() {
        val ed = plate()
        val vp = view(ed, Camera3(target = Vec3(20.0, 15.0, 10.0), distance = 200.0, yaw = 0.6, pitch = 0.9))
        ed.activeScalar = ed.doc.newParameter("wall", 3.0.mm)
        ed.setTool(Tools.SHELL)
        vp.clickWorld(Vec3(20.0, 15.0, 20.0))
        assertEquals(2, ed.solids().size, "the ray found the body: ${ed.statusHint}")
        val said = assertNotNull(ed.statusHint)
        assertTrue(said.contains("with the top face left open"), "and the face it is pointing at: $said")
        assertClose(volumeOf(ed.solids().last()), openTop, 1e-6)

        // …and the same body picked from **below** opens the bottom cap instead, which is what depth buys
        val under = plate()
        val vpUnder = view(under, Camera3(target = Vec3(20.0, 15.0, 10.0), distance = 200.0, yaw = 0.6, pitch = -0.9))
        under.activeScalar = under.doc.newParameter("wall", 3.0.mm)
        under.setTool(Tools.SHELL)
        vpUnder.clickWorld(Vec3(20.0, 15.0, 0.0))
        val below = assertNotNull(under.statusHint)
        assertTrue(below.contains("with the bottom face left open"), "the ray reaches the face it is aimed at: $below")
        assertClose(volumeOf(under.solids().last()), openTop, 1e-6, "the same wall, opened the other way up")
    }

    /**
     * **A fused part refuses by name rather than forking the model.** `tipOfChain` takes the gesture to the
     * union's own body — the sequential-feature rule — and that body has no profile to offset inward, so the
     * shell declines *there* instead of quietly hollowing the plate underneath it and leaving two parts
     * claiming to be the part.
     */
    @Test
    fun aFusedPartRefusesByNameRatherThanForking() {
        requireEngine()
        val ed = plate()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(35.0, 5.0))
        ed.click(Vec2(60.0, 25.0))
        ed.activeScalar = ed.doc.newParameter("padDepth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(47.5, 5.0))
        ed.setTool(Tools.UNION)
        ed.click(Vec2(20.0, 30.0))
        ed.click(Vec2(60.0, 15.0))
        val fused = ed.solids().last()
        val had = ed.solids().size

        ed.activeScalar = ed.doc.newParameter("wall", 2.0.mm)
        ed.setTool(Tools.SHELL)
        ed.click(Vec2(20.0, 0.0))
        assertEquals(had, ed.solids().size, "nothing was built: ${ed.statusHint}")
        val said = assertNotNull(ed.statusHint)
        assertTrue(said.contains(ed.doc.nameOf(fused)), "it names the body it declined: $said")
        assertTrue(said.contains("shell the body first"), "and the route that does work: $said")
    }

    /** A revolved cup, by gestures — the second body of the tier, and its wall is the annular one. */
    @Test
    fun aTurnedCupIsShelledByTheSameGesture() {
        requireEngine()
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(25.0, 40.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, -10.0))
        ed.click(Vec2(0.0, 50.0))
        // no angle typed is the whole way round — a closed body with no ends, which is the tier's second kind
        ed.setTool(Tools.REVOLVE)
        ed.click(Vec2(12.0, 0.0))
        ed.click(Vec2(0.0, 45.0))
        assertEquals(1, ed.solids().size, "the turned blank: ${ed.statusHint}")
        val blank = volumeOf(ed.solids().single())

        // a turned part is opened where a turned part is looked at: the disc at the top of it, in the 3D view.
        // (A body swept the whole way round has no cap standing in the plan at all, so the flat picture has no
        // drawing of that face to click — which is exactly the case the ray was built for.)
        val vp = view(ed, Camera3(target = Vec3(0.0, 20.0, 0.0), distance = 200.0, yaw = 0.5, pitch = 0.9))
        ed.activeScalar = ed.doc.newParameter("wall", 2.0.mm)
        ed.setTool(Tools.SHELL)
        vp.clickWorld(Vec3(10.0, 40.0, 0.0))
        assertEquals(2, ed.solids().size, "the cup: ${ed.statusHint}")
        val cup = volumeOf(ed.solids().last())
        val exact = PI * 25.0 * 25.0 * 40.0 - PI * 23.0 * 23.0 * 38.0
        assertTrue(cup < blank, "the cavity takes material out: $cup vs $blank")
        assertClose(cup, exact, exact * 0.02, "the annular wall plus the floor")
        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "and it round-trips byte-equal")
    }
}
