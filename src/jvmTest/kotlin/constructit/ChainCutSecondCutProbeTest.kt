package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.LineValue
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.Camera3
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.PlanePerspective
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.geom.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The probe review of the reachable chain cut, one gesture further** — the user's *whole* intent, which is
 * both chains: trim the handle by `chain2`, then trim **the result** by `chain1`.
 *
 * The second cut is where two promises meet that the delivery's suite proves only separately. The ray must
 * find the **cut body** — the original handle is a consumed operand, excluded by construction — so a second
 * three-quarter-view click on "the handle" must mean the trimmed one; and the sequential cut must extend the
 * feature chain at its tip rather than forking it (the probe classic: a second cut of the *original* would
 * silently discard the first). Then the fused finale: the twice-trimmed handle unioned with its plate by two
 * ray picks, because a boolean's SOLID slots ride the same new route.
 */
class ChainCutSecondCutProbeTest {
    private fun whyInvalid(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(el: Element) = Evaluator().solid(el.ref as SolidRef).mesh

    private fun solids(doc: Document): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    private fun Editor.click(world: Vec2) {
        val s = assertNotNull(pointing?.toScreen(world) ?: camera.worldToScreen(world), "the point has an image")
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.look(at: Vec3) {
        val plane = assertNotNull(doc.activePlane3(Evaluator()), "the active space has a plane")
        pointing = PlanePerspective(plane, Camera3(target = at, distance = 300.0, yaw = 0.6, pitch = 0.5), 800.0, 600.0)
    }

    private fun centreOf(el: Element): Vec3 {
        val b = assertNotNull(Geom3.bounds(meshOf(el)), "the solid has bounds")
        return (b.first + b.second) * 0.5
    }

    private fun Editor.aimAt(el: Element): Vec2 {
        val plane = assertNotNull(doc.activePlane3(Evaluator()), "the active space has a plane")
        return plane.toLocal(centreOf(el))
    }

    /** A point [t] mm along [chain] from its origin, and one [d] mm to its left at that station. */
    private fun along(
        doc: Document,
        chain: Element,
        t: Double,
        d: Double = 0.0,
    ): Vec2 {
        val line = (Evaluator().valueOf(chain.ref) as LineValue).line
        val dir = line.dir.normalized()
        val p = line.origin + dir * t
        return Vec2(p.x - dir.y * d, p.y + dir.x * d)
    }

    private fun named(
        doc: Document,
        name: String,
    ): Element =
        assertNotNull(
            doc.elements.firstOrNull { doc.userNameOf(it) == name } ?: doc.elements.firstOrNull { doc.nameOf(it) == name },
            "the drawing has $name",
        )

    @Test
    fun theSecondChainTrimsTheFirstCutAndTheFuseRidesTheSameRay() {
        val ed = Editor(DocumentFormat.load(ChainCutFixture.CIT))
        val doc = ed.doc
        val handle = named(doc, "e30")
        val plate = solids(doc).first()

        // ---- cut 1: the handle by chain2, ray + plan, exactly the user's gesture ----
        ed.look(centreOf(handle))
        ed.setTool(Tools.CUT_BY_CHAIN)
        ed.click(ed.aimAt(handle))
        ed.pointing = null
        val chain2 = named(doc, "chain2")
        ed.click(along(doc, chain2, 30.0))
        ed.click(along(doc, chain2, 30.0, 20.0))
        val cut1 = solids(doc).last()
        assertNull(whyInvalid(cut1), "the first trim builds: ${ed.statusHint}")
        val vCut1 = Geom3.volume(meshOf(cut1))
        assertTrue(vCut1 < Geom3.volume(meshOf(handle)), "…and took material off")

        // ---- cut 2: aim the ray at the SAME place — it must find the trimmed body, not the consumed one ----
        ed.look(centreOf(cut1))
        ed.setTool(Tools.CUT_BY_CHAIN)
        ed.click(ed.aimAt(cut1))
        ed.pointing = null
        val chain1 = named(doc, "chain1")
        // chain1 mirrors chain2 across the rotated base line; keep the handle's side of it too
        ed.click(along(doc, chain1, 30.0))
        ed.click(along(doc, chain1, 30.0, -20.0))
        val cut2 = solids(doc).last()
        assertTrue(cut2 !== cut1, "a fourth solid: the second trim")
        assertNull(whyInvalid(cut2), "…which builds: ${ed.statusHint}")
        assertManifold(meshOf(cut2), "the twice-trimmed handle")
        val vCut2 = Geom3.volume(meshOf(cut2))
        assertTrue(vCut2 in 1e-9..vCut1, "the second chain took its own material: $vCut2 of $vCut1")
        // the chain extends at the tip: the second step names the FIRST CUT as its operand, not the handle
        val saved = DocumentFormat.save(doc)
        val steps = saved.lineSequence().filter { it.startsWith("tool cutbychain") }.toList()
        assertEquals(2, steps.size, "two recorded cuts")
        assertTrue(
            steps[1].contains("els=${doc.nameOf(cut1)},"),
            "the second cut targets the first cut's body — the tip, not a fork: ${steps[1]}",
        )
        // this drawing carries the queued attach-restatement creep (~1e-13 mm on e40 per save, settling in
        // four — pinned by the delivery), so the file's claim is a fixed point rather than first-save
        // byte-equality, and the geometry's claim is that replay rebuilds the same bodies
        var text = saved
        var settled = false
        for (i in 0 until 4) {
            val again = DocumentFormat.save(DocumentFormat.load(text))
            settled = again == text
            if (settled) break
            text = again
        }
        assertTrue(settled, "the save settles to a fixed point within four round trips")
        val reloaded = DocumentFormat.load(text)
        assertClose(
            Geom3.volume(meshOf(solids(reloaded).last())),
            vCut2,
            1e-6,
            msg = "and replay rebuilds the twice-trimmed handle",
        )

        // ---- the finale: fuse the twice-trimmed handle with its plate, both operands by ray ----
        ed.look(centreOf(cut2))
        ed.setTool(Tools.UNION)
        ed.click(ed.aimAt(cut2))
        ed.click(ed.aimAt(plate))
        val fused = solids(doc).last()
        assertTrue(fused !== cut2, "the union is a new body: ${ed.statusHint}")
        assertNull(whyInvalid(fused), "…which builds: ${ed.statusHint}")
        assertManifold(meshOf(fused), "plate and twice-trimmed handle, fused by two ray picks")
        val vFused = Geom3.volume(meshOf(fused))
        val vPlate = Geom3.volume(meshOf(plate))
        assertTrue(vFused > vPlate && vFused <= (vPlate + vCut2) * 1.01, "the fuse accounts for its parts: $vFused")

        // and two undos peel the fuse and the second trim, in order — re-fetching from ed.doc, because an
        // undo rebuilds the document and a handle from before it reads the old graph
        val atFuse = ed.doc.journal.size
        assertTrue(ed.undo(), "undo takes the fuse")
        assertEquals(4, solids(ed.doc).size, "…leaving the four bodies that made it")
        val atCut2 = ed.doc.journal.size
        assertTrue(atCut2 < atFuse, "and the journal is one gesture shorter ($atFuse -> $atCut2)")
        assertTrue(ed.undo(), "the next undo is taken too")
        assertEquals(3, solids(ed.doc).size, "…and takes the second trim")
        assertTrue(ed.doc.journal.size < atCut2, "peeling a second layer, not restoring the same one twice")
    }

    /**
     * **Three chain-cut gestures peel in three undos**, journal sizes checked at every layer — this flow's
     * own statement of the rule `UndoRedoTest.everyGesturePeelsInItsOwnUndoOnADrawingThatRestatesAPosition`
     * makes generally.
     *
     * The probe review found the second undo doing nothing here, and the cause turned out to have nothing to
     * do with cuts, with the ray pick, or with re-arming the tool between gestures: this drawing **restates a
     * derived position** (`e40` is attached to `e32`), so `save ∘ load` moved a ULP, and `Editor`'s committed
     * baseline was the text a restore had been loaded *from* rather than what the restored document saved
     * *to*. Every undo after the first then took the discard-uncommitted-work branch and popped nothing. The
     * fix re-derives the baseline from the document (`Editor.restore`); this test is here so the flow that
     * exposed it keeps asserting it, with the general one standing beside it.
     */
    @Test
    fun threeCutGesturesPeelInThreeUndos() {
        val ed = Editor(DocumentFormat.load(ChainCutFixture.CIT))
        val doc = ed.doc
        val chain2 = named(doc, "chain2")
        val chain1 = named(doc, "chain1")
        val journal = { ed.doc.journal.size }
        val layers = ArrayList<Int>()

        layers.add(journal())
        cutByRay(ed, named(doc, "e30"), chain2, 20.0)
        layers.add(journal())
        cutByRay(ed, solids(doc).last(), chain1, -20.0)
        layers.add(journal())
        // a third gesture of a different kind, so the sequence is not three of one thing
        val cut2 = solids(doc).last()
        ed.look(centreOf(cut2))
        ed.setTool(Tools.UNION)
        ed.click(ed.aimAt(cut2))
        ed.click(ed.aimAt(solids(doc).first()))
        assertEquals(5, solids(ed.doc).size, "plate, handle, two trims and the fuse")

        for ((i, expected) in layers.reversed().withIndex()) {
            assertTrue(ed.canUndo, "undo ${i + 1} of 3 is offered")
            assertTrue(ed.undo(), "…and taken")
            assertEquals(expected, journal(), "undo ${i + 1} peels exactly one gesture")
            assertEquals(4 - i, solids(ed.doc).size, "…and exactly one body with it")
        }
        assertFalse(ed.canUndo, "the loaded drawing is the floor")
    }

    /** One *Cut by chain* gesture: the solid by a 3D ray, then the chain and the side to keep in the plan. */
    private fun cutByRay(
        ed: Editor,
        solid: Element,
        chain: Element,
        side: Double,
    ) {
        ed.look(centreOf(solid))
        ed.setTool(Tools.CUT_BY_CHAIN)
        ed.click(ed.aimAt(solid))
        ed.pointing = null
        ed.click(along(ed.doc, chain, 30.0))
        ed.click(along(ed.doc, chain, 30.0, side))
    }
}
