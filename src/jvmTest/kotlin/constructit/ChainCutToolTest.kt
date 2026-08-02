package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.exchange.ExportFormat
import constructit.exchange.Exports
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Cutting with an unbounded chain, as gestures** (OP-22's extension, step 1) — three table rows and no
 * controller code: *Chain* draws the tool, *Cut by chain* keeps the side that was clicked, *Split by chain*
 * keeps both.
 *
 * What is worth asserting here is not that a mesh appeared (that is `ChainCutTest`'s job) but that the
 * result is an ordinary member of the drawing and of the file: the kept side is a **persisted sign** that
 * replay never re-scores, the cut is a legal boolean operand, it exports through all four writers, it draws
 * a plan footprint, it hides, and the whole gesture is one undo.
 */
class ChainCutToolTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
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

    private fun Editor.reasonOf(el: Element): String = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason ?: "«valid»"

    /** An 80 × 50 × 20 block drawn as a rectangle and extruded — 80 000 mm³ of material to cut. */
    private fun block(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(80.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("depth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(40.0, 0.0))
        assertEquals(1, ed.solids().size, "the block: ${ed.statusHint}")
        return ed
    }

    /** The chain the block is cut with: an infinite line at y = 20, drawn clear of the rectangle's corners. */
    private fun Editor.drawChain(
        y0: Double = 20.0,
        y1: Double = 20.0,
    ): Element {
        setTool(Tools.CHAIN)
        click(Vec2(-15.0, y0))
        click(Vec2(95.0, y1))
        key("Enter")
        return doc.elements.last { it.kind == ElementKind.CHAIN }
    }

    // ---- the gesture ----

    @Test
    fun clickingPointsDrawsAChainAndCuttingWithItKeepsTheSideThatWasClicked() {
        val ed = block()
        val chain = ed.drawChain()
        assertEquals(ElementKind.CHAIN, chain.kind, ed.statusHint)

        ed.setTool(Tools.CUT_BY_CHAIN)
        ed.click(Vec2(40.0, 50.0))
        ed.click(Vec2(60.0, 20.0))
        ed.click(Vec2(40.0, 40.0))
        assertEquals(2, ed.solids().size, "one new solid: ${ed.statusHint}")
        assertClose(ed.volumeOf(ed.solids().last()), 80.0 * 30.0 * 20.0, tol = 1e-6, msg = ed.statusHint)
        assertTrue(ed.statusHint.contains("keeping the left side"), ed.statusHint)
    }

    /** Clicking the other side keeps the complementary body — one gesture, two answers. */
    @Test
    fun clickingTheOtherSideKeepsTheComplementaryBody() {
        val ed = block()
        ed.drawChain()
        ed.setTool(Tools.CUT_BY_CHAIN)
        ed.click(Vec2(40.0, 50.0))
        ed.click(Vec2(60.0, 20.0))
        ed.click(Vec2(40.0, 5.0))
        assertClose(ed.volumeOf(ed.solids().last()), 80.0 * 20.0 * 20.0, tol = 1e-6, msg = ed.statusHint)
        assertTrue(ed.statusHint.contains("keeping the right side"), ed.statusHint)
    }

    /** *Split* keeps both halves, and they are the whole solid between them. */
    @Test
    fun splitKeepsBothHalvesAndTheySumToTheOriginal() {
        val ed = block()
        ed.drawChain()
        ed.setTool(Tools.SPLIT_BY_CHAIN)
        ed.click(Vec2(40.0, 50.0))
        ed.click(Vec2(60.0, 20.0))
        assertEquals(3, ed.solids().size, "both halves become solids: ${ed.statusHint}")
        val halves = ed.solids().takeLast(2).map { ed.volumeOf(it) }
        assertClose(halves[0], 80.0 * 30.0 * 20.0, tol = 1e-6)
        assertClose(halves[1], 80.0 * 20.0 * 20.0, tol = 1e-6)
        assertClose(halves.sum(), 80000.0, tol = 1e-6, msg = "a split is a partition")

        // one step that creates two elements, and the file says so: replay rebuilds both, in order, with no
        // choice recorded — the pair is ordered by the chain's own run
        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("tool splitbychain"), text)
        val reloaded = DocumentFormat.load(text)
        assertEquals(text, DocumentFormat.save(reloaded), "save -> load -> save must be byte-equal")
        val back = reloaded.elements.filter { it.kind == ElementKind.SOLID }.takeLast(2)

        @Suppress("UNCHECKED_CAST")
        val volumes = back.map { Geom3.volume(Evaluator().solid(it.ref as SolidRef).mesh) }
        assertClose(volumes[0], 80.0 * 30.0 * 20.0, tol = 1e-6, msg = "the left half came back as the left half")
        assertClose(volumes[1], 80.0 * 20.0 * 20.0, tol = 1e-6)
    }

    /** A closed curve already in the drawing fills the same slot: a circle cuts a through-bore. */
    @Test
    fun aCircleFillsTheChainSlotAndCutsAThroughBore() {
        val ed = block()
        ed.activeScalar = ed.doc.newParameter("r", 10.0.mm)
        ed.setTool(Tools.CIRCLE_R)
        ed.click(Vec2(40.0, 25.0))
        ed.setTool(Tools.CUT_BY_CHAIN)
        ed.click(Vec2(40.0, 50.0))
        ed.click(Vec2(50.0, 25.0))
        ed.click(Vec2(5.0, 5.0))
        assertEquals(2, ed.solids().size, "the circle was accepted as a chain: ${ed.statusHint}")
        val bored = ed.solids().last()
        val v = ed.volumeOf(bored)
        assertTrue(v < 80000.0 && v > 80000.0 - 320.0 * 20.0, "a ⌀20 bore removes about 6 283 mm³, but $v was left")
        assertEquals(1, Evaluator().solid(bored.ref as SolidRef).feature.footprint.single().holes.size, "the bore is a hole in the plan")
    }

    // ---- the kept side is a persisted sign, and replay never re-scores it ----

    /**
     * **The sign is stored, and moving the drawing afterwards does not re-decide it.**
     *
     * The chain is dragged clear across the point that was clicked, so a build that scored the side again
     * would keep the *other* half. It keeps the same one — which is the whole of OP-1 applied here: a
     * discrete choice belongs to the gesture that made it, not to the geometry as it stands.
     */
    @Test
    fun theKeptSideIsAPersistedSignThatReplayNeverScoresAgain() {
        val ed = block()
        ed.drawChain()
        ed.setTool(Tools.CUT_BY_CHAIN)
        ed.click(Vec2(40.0, 50.0))
        ed.click(Vec2(60.0, 20.0))
        ed.click(Vec2(40.0, 40.0))
        val cut = ed.solids().last()
        assertClose(ed.volumeOf(cut), 80.0 * 30.0 * 20.0, tol = 1e-6)

        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("tool cutbychain"), "the cut rides the generic tool step (OP-18):\n$text")
        assertTrue(Regex("tool cutbychain[^\n]*signs=1").containsMatchIn(text), "the kept side is written as a sign:\n$text")
        val reloaded = DocumentFormat.load(text)
        assertEquals(text, DocumentFormat.save(reloaded), "save -> load -> save must be byte-equal")

        // now move the chain in the *reloaded* drawing, past the point that was clicked
        val ed2 = Editor(reloaded)
        val chainPoints = ed2.doc.elements.filter { it.isPoint }.takeLast(2)
        assertEquals(2, chainPoints.size)
        for (p in chainPoints) {
            ed2.setTool(Tools.SELECT)
            val at = Evaluator().let { ev -> (ev.eval(p.ref.node) as EvalResult.Ok).value as constructit.core.PointValue }.p
            ed2.pointerDown(ed2.camera.worldToScreen(at))
            ed2.pointerMove(ed2.camera.worldToScreen(Vec2(at.x, 45.0)))
            ed2.pointerUp(ed2.camera.worldToScreen(Vec2(at.x, 45.0)))
        }
        val moved = ed2.solids().last()
        assertClose(
            ed2.volumeOf(moved),
            80.0 * 5.0 * 20.0,
            tol = 1e-6,
            msg = "the left of the run is still what is kept — re-scoring would have given 72 000 mm³",
        )
    }

    // ---- the refusals reach the user, and they heal ----

    @Test
    fun aChainThatMissesSaysSoAndHealsWhenItIsDraggedAcross() {
        val ed = block()
        ed.drawChain(y0 = 120.0, y1 = 120.0)
        ed.setTool(Tools.CUT_BY_CHAIN)
        ed.click(Vec2(40.0, 50.0))
        ed.click(Vec2(60.0, 120.0))
        ed.click(Vec2(40.0, 10.0))
        val cut = ed.solids().last()
        assertTrue(ed.reasonOf(cut).contains("leaves the solid untouched"), ed.reasonOf(cut))

        // drag one end of the chain down through the block: the cut appears, with no repair anywhere
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(-15.0, 120.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(-15.0, 20.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(-15.0, 20.0)))
        assertTrue(Evaluator().eval(cut.ref.node) is EvalResult.Ok, "healed: ${ed.reasonOf(cut)}")
    }

    /** A pick that is neither a chain nor closed is refused **by name**, and builds nothing. */
    @Test
    fun cuttingWithSomethingThatDoesNotCloseIsRefusedByName() {
        val ed = block()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-15.0, 120.0))
        ed.click(Vec2(95.0, 120.0))
        val before = ed.doc.elements.size
        ed.setTool(Tools.CUT_BY_CHAIN)
        ed.click(Vec2(40.0, 50.0))
        ed.click(Vec2(40.0, 120.0))
        assertEquals(before, ed.doc.elements.size, "a lone segment separates nothing, so nothing is built")
    }

    // ---- ordinary obligations: composition, the file, the plan, hiding, one undo ----

    @Test
    fun aCutIsAnOrdinaryOperandOfTheNextBoolean() {
        val ed = block()
        ed.drawChain()
        ed.setTool(Tools.CUT_BY_CHAIN)
        ed.click(Vec2(40.0, 50.0))
        ed.click(Vec2(60.0, 20.0))
        ed.click(Vec2(40.0, 40.0))
        assertClose(ed.volumeOf(ed.solids().last()), 80.0 * 30.0 * 20.0, tol = 1e-6)

        // a second block, wholly inside the kept half, subtracted from it
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(20.0, 30.0))
        ed.click(Vec2(30.0, 40.0))
        ed.activeScalar = ed.doc.scalars.single { it.name == "depth" }
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(25.0, 30.0))
        ed.setTool(Tools.SUBTRACT)
        // the cut half was created after the block, so on a boundary they share the newest takes the pick
        ed.click(Vec2(40.0, 50.0))
        ed.click(Vec2(25.0, 30.0))
        assertClose(
            ed.volumeOf(ed.solids().last()),
            80.0 * 30.0 * 20.0 - 10.0 * 10.0 * 20.0,
            tol = 1e-6,
            msg = ed.statusHint,
        )
    }

    @Test
    fun aCutExportsThroughAllFourWritersAndDrawsAPlanFootprint() {
        val ed = block()
        ed.drawChain()
        ed.setTool(Tools.CUT_BY_CHAIN)
        ed.click(Vec2(40.0, 50.0))
        ed.click(Vec2(60.0, 20.0))
        ed.click(Vec2(40.0, 40.0))
        val cut = ed.solids().last()

        // the plan: a cut solid shows a footprint like any other, which is what makes it pickable in 2D
        val plan = Evaluator().solid(cut.ref as SolidRef).feature.footprint
        assertEquals(1, plan.size, "one area")
        assertTrue(plan.single().outer.elements.isNotEmpty(), "with a boundary to draw and to click")

        // hide the block and its other half so only the cut is exported
        for (el in ed.solids().dropLast(1)) el.visible = false
        for (f in ExportFormat.entries) {
            val r = Exports.export(ed.doc, "cut", f)
            assertTrue(r.ok, "${f.label}: ${r.message}")
            assertTrue(r.bytes!!.isNotEmpty(), "${f.label} wrote no bytes")
        }
    }

    @Test
    fun theWholeGestureIsOneUndoAndTheCutHides() {
        val ed = block()
        ed.drawChain()
        val before = ed.doc.elements.size
        ed.setTool(Tools.CUT_BY_CHAIN)
        ed.click(Vec2(40.0, 50.0))
        ed.click(Vec2(60.0, 20.0))
        ed.click(Vec2(40.0, 40.0))
        assertEquals(before + 1, ed.doc.elements.size)
        val cut = ed.solids().last()

        ed.setTool(Tools.SELECT)
        ed.selectElement(cut)
        assertEquals(1, ed.setSelectionVisible(false), ed.statusHint)
        assertTrue(!cut.visible, "a cut hides like any other solid")

        assertTrue(ed.undo(), "one undo takes the hide back")
        assertTrue(ed.undo(), "and one more the whole cut gesture")
        assertEquals(before, ed.doc.elements.size, "the cut is gone in one step, chain and block untouched")
        assertEquals(1, ed.solids().size)
    }

    /** Drawing the chain is itself one gesture — its points and the chain go back together. */
    @Test
    fun drawingAChainIsOneUndo() {
        val ed = block()
        val before = ed.doc.elements.size
        ed.drawChain()
        assertTrue(ed.doc.elements.size > before)
        assertTrue(ed.undo(), ed.statusHint)
        assertEquals(before, ed.doc.elements.size, "the chain and the points it was drawn through go together")
    }

    /**
     * **…and it is drawn**: the chain's finite run *and* its two rays, clipped to the view exactly as a
     * drawn ray is, over the plan footprint of the half it cut. What the picture pins is that the unbounded
     * part reaches the canvas at all — a chain drawn only between its points would be a picture of a
     * different question, since the side that was kept is stated relative to the whole curve.
     */
    @Test
    fun theChainAndTheCutItMadeAreDrawn() {
        val ed = block()
        ed.drawChain()
        ed.setTool(Tools.CUT_BY_CHAIN)
        ed.click(Vec2(40.0, 50.0))
        ed.click(Vec2(60.0, 20.0))
        ed.click(Vec2(40.0, 40.0))
        ed.setTool(Tools.SELECT)
        val target = constructit.editor.SvgDrawTarget()
        ed.render(target)
        Golden.check("chain_cut_plan", target.svg())
    }

    /** The chain is drawn, picked and named like everything else — including where its rays are. */
    @Test
    fun theChainDrawsItsRaysAndIsPickable() {
        val ed = block()
        val chain = ed.drawChain()
        ed.setTool(Tools.SELECT)
        // 200 mm out along the first ray, far beyond the last point that was clicked
        ed.click(Vec2(-215.0, 20.0))
        assertEquals(listOf(chain), ed.selectedElements, "a chain is picked where it is drawn — rays included")
        assertTrue(ed.doc.nameOf(chain).startsWith("e"), "and it is named by the script like every element (OP-18)")
    }
}
