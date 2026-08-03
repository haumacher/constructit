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
import constructit.exchange.ExportFormat
import constructit.exchange.Exports
import constructit.geom.Geom3
import constructit.geom.MeshBool
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The swept cut as a gesture** (OP-22's extension, step 2) — four table rows and no controller code.
 *
 * The geometry is [SweptCutTest]'s; what is asserted here is that the operator is an ordinary member of the
 * drawing and of the file. The one claim that is this step's own: the **carry mode is structural**, stated by
 * which row was used, written into the file as that tool id and never worked out again from the geometry —
 * which is asserted by reloading a drawing and getting the mode's own body back, where the other mode's would
 * differ by hundreds of cubic millimetres.
 */
class SweptCutToolTest {
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

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun Editor.volumeOf(el: Element): Double {
        val r = Evaluator().eval(el.ref.node)
        assertTrue(r is EvalResult.Ok, "${el.id} should have a value: ${(r as? EvalResult.Invalid)?.reason}")
        val mesh = Evaluator().solid(el.ref as SolidRef).mesh
        assertManifold(mesh, el.id)
        return Geom3.volume(mesh)
    }

    private fun view(ed: Editor): Viewport3 {
        val vp = Viewport3(camera = Camera3(target = Vec3(80.0, 20.0, 30.0), distance = 420.0, yaw = -0.9, pitch = 0.5), widthPx = wPx, heightPx = hPx)
        vp.editor = ed
        vp.shown = true
        return vp
    }

    private fun Viewport3.screenOf(p: Vec3): Vec2 = assertNotNull(camera.project(p, widthPx, heightPx), "$p projects")

    /**
     * The fixture, and it is the feature's own story: a **block** with a **route** climbing through it and
     * bending as it goes, and the **section** of the channel drawn about the plan's origin — because a chain
     * is read in the route's moving frame with its space's origin on the route, exactly as a swept solid's
     * profile is (OP-26).
     *
     * The route is drawn through height points (OP-25), picked in the 3D view where they are drawn, and it
     * **stops inside the block**: the ends run on by themselves, which is the operator's own claim.
     */
    private fun fixture(): Triple<Editor, Element, Element> {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(100.0, 40.0))
        ed.activeScalar = ed.doc.newParameter("depth", 40.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(70.0, 0.0))
        assertEquals(1, ed.solids().size, "the block: ${ed.statusHint}")

        // the route: (70, 20, 10) → (70, 20, 30) → (105, 20, 70), the first two sharing one base point
        for ((base, h) in listOf(Vec2(70.0, 20.0) to "10", Vec2(70.0, 20.0) to "30", Vec2(105.0, 20.0) to "70")) {
            ed.setTool(Tools.HEIGHT_POINT)
            ed.type(h)
            ed.click(base)
        }
        val vp = view(ed)
        ed.setTool(Tools.CURVE3)
        for (p in listOf(Vec3(70.0, 20.0, 10.0), Vec3(70.0, 20.0, 30.0), Vec3(105.0, 20.0, 70.0))) {
            vp.pointerDown(vp.screenOf(p))
            vp.pointerUp(vp.screenOf(p))
        }
        ed.key("Enter")
        val route = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, "the route: ${ed.statusHint}")
        // back to the canvas: hiding the 3D view is what hands the pointer's projection back to the 2D
        // camera, exactly as the shell does when the view is closed
        vp.shown = false

        // the section: a circle about the plan's origin, which is what puts the channel on the route
        ed.activeScalar = ed.doc.newParameter("r", 8.0.mm)
        ed.setTool(Tools.CIRCLE_R)
        ed.click(Vec2(0.0, 0.0))
        val chain = assertNotNull(ed.doc.elements.lastOrNull { it.isCurve }, "the section: ${ed.statusHint}")
        return Triple(ed, route, chain)
    }

    /** The cut gesture: the solid, the section, the route, then the side to keep. */
    private fun cutAlong(
        ed: Editor,
        tool: String,
    ): Element {
        ed.setTool(tool)
        ed.click(Vec2(70.0, 0.0))
        ed.click(Vec2(8.0, 0.0))
        ed.click(Vec2(70.0, 20.0))
        ed.click(Vec2(30.0, 30.0))
        return assertNotNull(ed.solids().lastOrNull(), "the swept cut was built: ${ed.statusHint}")
    }

    /** What the two modes remove, from the route the fixture states: `area × arc` and `area × rise`. */
    private val area = PI * 64.0
    private val turnedRemoval = area * (30.0 + 10.0 * 53.150729 / 40.0)
    private val flatRemoval = area * 40.0

    // ---- the gesture ----

    @Test
    fun clickingASolidASectionAndARouteCutsAChannelAlongIt() {
        if (!MeshBool.available) return
        val (ed, route, _) = fixture()
        val before = ed.solids().size
        val cut = cutAlong(ed, Tools.CUT_ALONG_CURVE)
        assertEquals(before + 1, ed.solids().size, "one new solid: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("swept along ${ed.doc.nameOf(route)} (rotating)"), "the tool says what it carried and how: ${ed.statusHint}")
        assertClose(
            ed.volumeOf(cut),
            60.0 * 40.0 * 40.0 - turnedRemoval,
            tol = 0.01 * turnedRemoval,
            msg = "a section square to the run removes area × arc length, and the route ran on out of the block by itself",
        )
    }

    /**
     * **The two rows cut different bodies from the identical picks**, each matching its own closed form: the
     * translational carry keeps the section in the plan, so it removes `area × the block's thickness`
     * whatever the route does sideways, while the rotating one removes `area × arc length`.
     */
    @Test
    fun theOtherRowCarriesTheSectionFlatAndRemovesLess() {
        if (!MeshBool.available) return
        val (ed, _, _) = fixture()
        val cut = cutAlong(ed, Tools.CUT_ALONG_CURVE_FLAT)
        assertTrue(ed.statusHint.contains("(translational)"), "the mode reaches the status line: ${ed.statusHint}")
        assertClose(
            ed.volumeOf(cut),
            60.0 * 40.0 * 40.0 - flatRemoval,
            tol = 0.01 * flatRemoval,
            msg = "a stack of parallel sections removes area × rise",
        )
        assertTrue(turnedRemoval - flatRemoval > 300.0, "and the two modes are apart by far more than any tolerance")
    }

    // ---- the mode is structural: the file records it, and replay never works it out again ----

    @Test
    fun theCarryModeSurvivesSaveLoadSaveAndIsNeverInferred() {
        if (!MeshBool.available) return
        for ((tool, expected) in listOf(Tools.CUT_ALONG_CURVE to turnedRemoval, Tools.CUT_ALONG_CURVE_FLAT to flatRemoval)) {
            val (ed, _, _) = fixture()
            val cut = cutAlong(ed, tool)
            val v = ed.volumeOf(cut)

            val text = DocumentFormat.save(ed.doc)
            assertTrue(text.contains("tool $tool"), "the mode is the tool id the file records (OP-18):\n$text")
            assertTrue(Regex("tool $tool[^\n]*signs=-1").containsMatchIn(text), "…and the kept side still rides signs= beside it:\n$text")
            val reloaded = DocumentFormat.load(text)
            assertEquals(text, DocumentFormat.save(reloaded), "save -> load -> save must be byte-equal")

            @Suppress("UNCHECKED_CAST")
            val back = reloaded.elements.last { it.kind == ElementKind.SOLID }
            val vBack = Geom3.volume(Evaluator().solid(back.ref as SolidRef).mesh)
            assertClose(vBack, v, tol = 1e-9, msg = "replay rebuilds the very same body")
            assertClose(vBack, 60.0 * 40.0 * 40.0 - expected, tol = 0.01 * expected, msg = "…and it is this mode's body, not the other's")
        }
    }

    // ---- split, and the ordinary obligations ----

    @Test
    fun splitAlongARouteKeepsBothHalves() {
        if (!MeshBool.available) return
        val (ed, _, _) = fixture()
        val before = ed.solids().size
        ed.setTool(Tools.SPLIT_ALONG_CURVE)
        ed.click(Vec2(70.0, 0.0))
        ed.click(Vec2(8.0, 0.0))
        ed.click(Vec2(70.0, 20.0))
        assertEquals(before + 2, ed.solids().size, "both halves become solids: ${ed.statusHint}")
        val halves = ed.solids().takeLast(2).map { ed.volumeOf(it) }
        assertClose(halves[0], turnedRemoval, tol = 0.01 * turnedRemoval, msg = "the channel itself is the left of the closed chain's run — its inside")
        assertClose(halves.sum(), 60.0 * 40.0 * 40.0, tol = 1.0, msg = "and a split is still a partition")
    }

    @Test
    fun theWholeGestureIsOneUndoAndTheCutHidesAndExports() {
        if (!MeshBool.available) return
        val (ed, _, _) = fixture()
        val before = ed.doc.elements.size
        val cut = cutAlong(ed, Tools.CUT_ALONG_CURVE)
        assertEquals(before + 1, ed.doc.elements.size)

        // it exports through all four writers, with the block itself hidden so only the cut is written
        for (el in ed.solids().dropLast(1)) el.visible = false
        for (f in ExportFormat.entries) {
            val r = Exports.export(ed.doc, "sweptcut", f)
            assertTrue(r.ok, "${f.label}: ${r.message}")
            assertTrue(r.bytes!!.isNotEmpty(), "${f.label} wrote no bytes")
        }

        ed.setTool(Tools.SELECT)
        ed.selectElement(cut)
        assertEquals(1, ed.setSelectionVisible(false), ed.statusHint)
        assertTrue(!cut.visible, "a swept cut hides like any other solid")
        assertTrue(ed.undo(), "one undo takes the hide back")
        assertTrue(ed.undo(), "and one more the whole cut gesture")
        assertEquals(before, ed.doc.elements.size, "the cut is gone in one step, route and section untouched")
    }

    /**
     * **A swept cut has no plan footprint, and that is inherited rather than new**: its result is a general
     * boolean, and OP-9's mesh-is-a-sink rule gives one no analytic plan (the mesh-only footprint for a
     * general boolean's result is a parked item of its own). It is a solid in every other sense — it draws in
     * the 3D view, it exports, it is an operand — which is what the assertions above stand on.
     */
    @Test
    fun aSweptCutIsDrawnInThreeDimensionsAndSaysWhyItHasNoPlan() {
        if (!MeshBool.available) return
        val (ed, _, _) = fixture()
        val cut = cutAlong(ed, Tools.CUT_ALONG_CURVE)

        @Suppress("UNCHECKED_CAST")
        val solid = Evaluator().solid(cut.ref as SolidRef)
        assertTrue(solid.feature.footprint.isEmpty(), "a general boolean has no plan to draw (OP-9)")
        assertTrue(solid.mesh.triangles.isNotEmpty(), "…but it has a body, which is what the 3D view shows")
        assertManifold(solid.mesh, "the swept cut")
    }
}
