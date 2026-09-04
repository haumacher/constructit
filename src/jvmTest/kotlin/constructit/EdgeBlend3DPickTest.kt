package constructit

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
import constructit.geom.Blend3
import constructit.geom.Section3
import constructit.geom.Vec2
import constructit.geom.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The edge a blend breaks in the 3D view is the edge you clicked** — GitHub issue #24.
 *
 * *"When clicking the extruded leg of leg e12, the extruded leg of leg e3 is filleted. Depends on the camera
 * angle and position — sometimes it works, sometimes not."*
 *
 * The fault was one line of reasoning long. A click in the 3D view is resolved onto the working plane first
 * (edit-in-3D slice 1, which is how every existing tool gets a 2D coordinate), and the blend's edge pick then
 * measured **plan-projected** edges against that plane point. The rim the user clicked stands 20 mm above the
 * plan, so the plane point is displaced from it by the whole parallax of the view — and which plan-projected
 * edge came out nearest was then a function of where the camera happened to stand. Hence "random", and hence
 * camera-dependent.
 *
 * The cure is the right picture rather than a better tolerance: in the 3D view the edge is picked as the
 * camera shows it (`Document.edgeInView`), which is the same sentence the flat rule always stated — *the edge
 * whose drawing here runs nearest the click* — asked of the drawing the user is actually looking at. Ties
 * inside the pick tolerance go to the edge in front, and then to the edge nearest the eye, which is the flat
 * rule's own tie-break verbatim.
 *
 * Nothing recorded changed (OP-1/OP-18): the step still stores the edge index in `signs=` and the plane click
 * in `clicks=`, a replay re-scores nothing, and the 2D canvas — where `PlaneProjection.eyeRay` is null — keeps
 * exactly the picking it had ([theFlatCanvasPicksAsItAlwaysDid]).
 */
class EdgeBlend3DPickTest {
    /** The reporter's own script, verbatim, up to the point where the fillet gesture is made. */
    private val fixture =
        """
constructit 3
orthostart -26.875,-32.375 -> e1
orthovertex -26.875,15.375 -> e2,e3
orthovertex 61.875,15.375 -> e4,e5
orthovertex 61.875,0.375 -> e6,e7
orthovertex -5.521648428788623,0.375 -> e8,e9
orthovertex -5.521648428788623,-32.375 -> e10,e11
orthoclose -> e12
param "h" = 20mm
tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
param "h2" = 9mm
""".trimStart()

    /**
     * The rim over the reporter's leg e12: the top boundary of the extruded plate, at `y = -32.375`, `z = 20`,
     * running from the loop's start corner to its last one. What the click in every test below aims at.
     */
    private val rimOverE12 = Vec3(-16.2, -32.375, 20.0)

    /** The same rim on the plate's underside — what a click aimed *through* the body would land on. */
    private val rimUnderE12 = Vec3(-16.2, -32.375, 0.0)

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun assertSolidsManifold(ed: Editor) {
        for (el in ed.solids()) assertManifold(Evaluator().solid(el.ref as SolidRef).mesh, ed.doc.nameOf(el))
    }

    private fun armed(tool: String): Editor {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(fixture))
        ed.activeScalar = ed.doc.scalars.first { it.name == "h2" }
        ed.setTool(tool)
        return ed
    }

    private fun view(
        ed: Editor,
        cam: Camera3,
    ): Viewport3 {
        val vp = Viewport3(camera = cam, widthPx = 800.0, heightPx = 600.0)
        vp.editor = ed
        vp.shown = true
        return vp
    }

    /** A click in the 3D view **aimed at a point of the world** — which is what aiming at an edge means. */
    private fun Viewport3.clickWorld(p: Vec3) {
        val s = assertNotNull(camera.project(p, widthPx, heightPx), "$p has an image on screen")
        pointerDown(s)
        pointerUp(s)
    }

    /** The step the gesture wrote, or null when it refused. */
    private fun stepOf(
        ed: Editor,
        word: String,
    ): String? = DocumentFormat.save(ed.doc).lines().map { it.trim() }.firstOrNull { it.startsWith("tool $word") }

    /** The edge index a blend step recorded — the first of its `signs=`. */
    private fun addressOf(step: String): Int = step.substringAfter("signs=").substringBefore(";").toInt()

    /** Where the edge that step named actually runs, so a test can say *which* edge in the drawing's terms. */
    @Suppress("UNCHECKED_CAST")
    private fun edgeEndsOf(
        ed: Editor,
        address: Int,
    ): Pair<Vec3, Vec3> {
        val base = Evaluator().solid(ed.solids().first().ref as SolidRef).feature
        val edges = assertNotNull(Section3.edges(base).first, "the plate names its edges")
        val path = assertNotNull(Blend3.edgePath(edges[address]).first, "edge #$address has a path")
        return assertNotNull(path.start, "a start") to assertNotNull(path.end, "an end")
    }

    /** Run the fillet gesture from [cam], aimed at [target]; returns the edge index it named. */
    private fun fillet(
        cam: Camera3,
        target: Vec3 = rimOverE12,
        tool: String = Tools.BLEND_EDGE,
        word: String = "filletedge",
    ): Pair<Int, Editor> {
        val ed = armed(tool)
        view(ed, cam).clickWorld(target)
        val step = assertNotNull(stepOf(ed, word), "a blend was made from $cam: ${ed.statusHint}")
        assertSolidsManifold(ed)
        return addressOf(step) to ed
    }

    // ---- the report: one edge, whatever the camera ----

    /**
     * **The camera sweep the report asks for.** Six views of the same plate — the two the reporter's own
     * gesture came from, one steep, one from the far side, one grazing along the plan, and one from *below*
     * the plate — and the click aimed squarely at the rim over leg e12 every time.
     *
     * Before the fix the second of these named edge #17 (the rim over leg e3, on the other side of the part)
     * while the others named #12; that spread *is* the bug, so the assertion is the spread as much as the
     * value.
     */
    @Test
    fun theRimUnderTheClickIsTheEdgeFilletedFromEveryCamera() {
        val cameras =
            listOf(
                Camera3(target = Vec3(17.0, -8.0, 10.0), distance = 300.0, yaw = 0.4, pitch = 0.6),
                // the view that used to answer with edge #17
                Camera3(target = Vec3(17.0, -8.0, 10.0), distance = 300.0, yaw = -0.9, pitch = 0.9),
                Camera3(target = Vec3(17.0, -8.0, 10.0), distance = 200.0, yaw = 2.2, pitch = 0.3),
                Camera3(target = Vec3(17.0, -8.0, 10.0), distance = 400.0, yaw = 3.5, pitch = 1.2),
                // grazing: the plan almost edge-on, where a plan-projected measure is at its most misleading
                Camera3(target = Vec3(17.0, -8.0, 10.0), distance = 320.0, yaw = -0.3, pitch = 0.08),
                // and from underneath the plate, where the rim clicked is the far side of the body
                Camera3(target = Vec3(17.0, -8.0, 10.0), distance = 300.0, yaw = 0.6, pitch = -0.7),
            )
        val named = cameras.map { cam -> fillet(cam).first }
        assertEquals(1, named.toSet().size, "one edge from every camera, not six answers: $named")
        val (from, to) = edgeEndsOf(fillet(cameras[0]).second, named[0])
        assertClose(from.z, 20.0, tol = 1e-9, msg = "the rim on top of the plate, not the one underneath")
        assertClose(to.z, 20.0, tol = 1e-9, msg = "…at both ends")
        assertClose(from.y, -32.375, tol = 1e-9, msg = "the rim over leg e12, not over leg e3")
        assertClose(to.y, -32.375, tol = 1e-9, msg = "…at both ends")
    }

    /**
     * **The step the reporter expected is the step the gesture now writes**, from the very camera that used to
     * pick the wrong edge: `signs=12` and the four choices the blend scored round that crease.
     *
     * And the file is a fixed point over it (OP-18): the address is *stored*, so a reload re-scores nothing and
     * `save → load → save` is byte-equal — which is what makes the pick a durable choice rather than something
     * the next camera position could revise.
     */
    @Test
    fun theExpectedStepIsWhatTheGestureWritesAndItReloadsUnchanged() {
        val ed = armed(Tools.BLEND_EDGE)
        view(ed, Camera3(target = Vec3(17.0, -8.0, 10.0), distance = 300.0, yaw = -0.9, pitch = 0.9))
            .clickWorld(rimOverE12)
        val step = assertNotNull(stepOf(ed, "filletedge"), "the fillet was made: ${ed.statusHint}")
        assertTrue(
            step.startsWith("tool filletedge els=e13 clicks=") &&
                step.endsWith("scalar=\"h2\" signs=12;-1;1;0;1 -> e14"),
            "the reporter's own expected step: $step",
        )
        assertTrue("picked in the 3D view" in ed.statusHint, "and it says which picture answered: ${ed.statusHint}")

        val once = DocumentFormat.save(ed.doc)
        val twice = DocumentFormat.save(DocumentFormat.load(once))
        assertEquals(once, twice, "save -> load -> save is a fixed point")
        val back = Editor()
        back.replaceDocument(DocumentFormat.load(once))
        assertSolidsManifold(back)
        assertEquals(step, assertNotNull(stepOf(back, "filletedge")), "and the replay names the same edge")
    }

    /** A chamfer is the same gesture with a straight bevel, so it picks by the same rule. */
    @Test
    fun aChamferPicksTheSameRim() {
        val cam = Camera3(target = Vec3(17.0, -8.0, 10.0), distance = 300.0, yaw = -0.9, pitch = 0.9)
        val (chamfered, ed) = fillet(cam, tool = Tools.CHAMFER_EDGE, word = "chamferedge")
        assertEquals(12, chamfered, "the rim over leg e12: ${ed.statusHint}")
        val (from, _) = edgeEndsOf(ed, chamfered)
        assertClose(from.z, 20.0, tol = 1e-9, msg = "the top rim")
    }

    /**
     * **An edge hidden behind the body does not win a tie.** From almost straight overhead the plate's top and
     * bottom rims draw within a couple of pixels of each other, so the click cannot say which it meant — and
     * the one it cannot possibly have meant is the one the plate is standing on top of.
     *
     * Aimed at the **underside** rim deliberately: it is the nearer of the two to the cursor, and it still
     * loses, because inside the pick tolerance depth is the evidence and screen distance is not.
     */
    @Test
    fun anOccludedRimLosesToTheVisibleOneInFront() {
        val cam = Camera3(target = Vec3(17.0, -8.0, 10.0), distance = 300.0, yaw = 0.5, pitch = 1.5)
        val (named, ed) = fillet(cam, target = rimUnderE12)
        val (from, to) = edgeEndsOf(ed, named)
        assertClose(from.z, 20.0, tol = 1e-9, msg = "the rim you can see, not the one under the plate")
        assertClose(to.z, 20.0, tol = 1e-9, msg = "…at both ends")
        assertClose(from.y, -32.375, tol = 1e-9, msg = "and still the rim over leg e12")
    }

    /**
     * **The flat canvas is untouched.** With no eye to shoot a ray from, `PlaneProjection.eyeRay` is null and
     * the whole 3D reading does not exist: the plan measures its own orthographic drawing exactly as it always
     * did, ties to the edge nearest the eye — which over a plate lying in the plan is the top rim.
     *
     * So the two views agree here, and they agree *because* each measures the picture it actually shows. The
     * note says nothing about a 3D view, since none answered.
     */
    @Test
    fun theFlatCanvasPicksAsItAlwaysDid() {
        val ed = armed(Tools.BLEND_EDGE)
        val s = ed.camera.worldToScreen(Vec2(rimOverE12.x, rimOverE12.y))
        ed.pointerDown(s)
        ed.pointerUp(s)
        val step = assertNotNull(stepOf(ed, "filletedge"), "the fillet was made in the plan: ${ed.statusHint}")
        assertEquals(12, addressOf(step), "the top rim over leg e12, as the plan has always read it: $step")
        assertTrue("3D view" !in ed.statusHint.substringBefore(" — the radius"), "no 3D view answered: ${ed.statusHint}")
        assertSolidsManifold(ed)
    }
}
