package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **Make relative on a shared carrier — OP-4 case (b) where both picks lie on one curve.**
 *
 * The polar form (`anchor + PolarVector(d, θ)`) is the right answer for a *free* point: two degrees of freedom
 * before, two after. A **rider** has one, and it belongs to its carrier — so the same two picks, when both of
 * them are positions on one carrier, mean something more specific: state the rider's position as a signed
 * distance from the other one, *along the carrier*. One DOF before, one after; nothing moves at the moment of
 * the change; and the rider now follows any edit of its host coherently with the point it is measured from.
 *
 * That is the OP-20 principle closing: an **explicit** anchor supersedes gesture-time compensation. A rider
 * whose position is stated relative to its carrier needs no compensation at all when the host turns, because
 * its motion is stated rather than corrected — and it is therefore not even registered for it.
 */
class CarrierRelativeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
        steps: Int = 4,
    ) {
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(from))
        for (i in 1..steps) pointerMove(camera.worldToScreen(from + (to - from) * (i.toDouble() / steps)))
        pointerUp(camera.worldToScreen(to))
    }

    private fun pos(el: Element): Vec2 = ((Evaluator().eval(el.ref.node) as EvalResult.Ok).value as PointValue).p

    /**
     * The element the comments call `eN` — the *N*th created, since the document's own ids skip (a rider's
     * hidden parameter takes one from the same counter).
     */
    private fun el(
        ed: Editor,
        id: String,
    ): Element = ed.doc.elements[id.removePrefix("e").toInt() - 1]

    /**
     * A slanted segment `e1 → e2` with two riders on it (`e4` at 30 mm along, `e5` at 60 mm) — the drawing the
     * OP-20 compensation note was written for, so it is also the one that shows what a stated anchor buys.
     */
    private fun carrier(): Editor {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0)) // e1
        ed.click(Vec2(100.0, 0.0)) // e2
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0)) // e3
        ed.setTool(Tools.POINT_ON_LINE)
        ed.click(Vec2(30.0, 0.0)) // e4
        ed.click(Vec2(60.0, 0.0)) // e5
        ed.setTool(Tools.SELECT)
        return ed
    }

    private fun makeRelative(
        ed: Editor,
        point: String,
        base: String,
    ) {
        ed.setTool(Tools.MAKE_RELATIVE)
        ed.click(pos(el(ed, point)))
        ed.click(pos(el(ed, base)))
    }

    /** The document's own id of the element the comments call `eN` — what the status line and notes name. */
    private fun idOf(
        ed: Editor,
        id: String,
    ): String = el(ed, id).id

    // ---- the specialization, both bases ----

    /** Base = another rider on the same carrier: the offset is a distance along it, and the status says so. */
    @Test
    fun aRiderMeasuredFromAnotherRiderOfTheSameCarrier() {
        val ed = carrier()
        val was = pos(el(ed, "e5"))
        makeRelative(ed, "e5", "e4")
        assertEquals(was, pos(el(ed, "e5")), "nothing moves at the moment of the change")
        assertTrue(
            ed.statusHint.contains("30 mm from ${idOf(ed, "e4")} along ${idOf(ed, "e3")}"),
            "got: ${ed.statusHint}",
        )
        assertEquals(listOf("distance"), el(ed, "e5").handle!!.fields().map { it.label }, "one DOF, and it is that distance")
        assertEquals(idOf(ed, "e4"), ed.doc.riderOf(el(ed, "e5"))!!.base!!.id)

        // it still slides along its host — one degree of freedom before, one after
        ed.drag(pos(el(ed, "e5")), Vec2(80.0, 0.0))
        assertClose(pos(el(ed, "e5")).x, 80.0, 1e-9)
        assertClose(pos(el(ed, "e5")).y, 0.0, 1e-9, "and it never leaves the carrier")
        assertClose(distanceField(ed, "e5"), 50.0, 1e-9, "the distance from e4 is what the drag wrote")
    }

    /** Base = one of the carrier's own ends — a dimension from the corner, which is what a drawing states. */
    @Test
    fun aRiderMeasuredFromAnEndOfItsCarrier() {
        val ed = carrier()
        makeRelative(ed, "e4", "e1")
        assertTrue(
            ed.statusHint.contains("from ${idOf(ed, "e1")} along ${idOf(ed, "e3")}"),
            "got: ${ed.statusHint}",
        )
        assertEquals(idOf(ed, "e1"), ed.doc.riderOf(el(ed, "e4"))!!.base!!.id)
        assertClose(distanceField(ed, "e4"), 30.0, 1e-9)

        // typing the distance reaches exactly as far as dragging (OP-13)
        ed.setTool(Tools.SELECT)
        ed.click(pos(el(ed, "e4")))
        assertTrue(ed.writeSelectionField(0, 45.0))
        assertClose(pos(el(ed, "e4")).x, 45.0, 1e-9)
    }

    /** The polar form is still what a *free* point gets — the specialization is about the picks, not the tool. */
    @Test
    fun afreePointStillGetsThePolarForm() {
        val ed = carrier()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(20.0, 40.0)) // e6, free
        makeRelative(ed, "e6", "e4")
        assertEquals(listOf("distance", "angle"), el(ed, "e6").handle!!.fields().map { it.label })
        assertTrue(ed.statusHint.contains("distance and angle"), "got: ${ed.statusHint}")
    }

    /** A base that is not on the rider's carrier is refused — with the reason, and recording nothing. */
    @Test
    fun aBaseOffTheCarrierIsRefusedWithAReason() {
        val ed = carrier()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(20.0, 40.0)) // e6, off the line
        val steps = ed.doc.journal.size
        makeRelative(ed, "e4", "e6")
        assertTrue(ed.statusHint.contains("pick a point on ${idOf(ed, "e3")}"), "got: ${ed.statusHint}")
        assertFalse(ed.doc.riderOf(el(ed, "e4"))!!.carrierRelative)
        assertEquals(steps, ed.doc.journal.size, "a refusal records nothing")
    }

    // ---- what the stated anchor buys: coherence under an edit of the host, with no compensation ----

    /**
     * **The OP-20 principle, asserted.** Turning the host used to slide a world-anchored rider along the
     * sweeping carrier, which is why a gesture compensates them. A rider measured *from another position on
     * the same carrier* needs none of that: it keeps its distance from its base by construction, so the pair
     * turns coherently — and the document does not even register it for compensation, so the drag performs
     * **zero** compensation writes.
     */
    @Test
    fun aStatedAnchorSupersedesCompensationWhenTheHostTurns() {
        val ed = carrier()
        makeRelative(ed, "e4", "e1")
        makeRelative(ed, "e5", "e4")
        assertTrue(ed.doc.riderAnchors().isEmpty(), "both riders are stated, so nothing is registered to compensate")

        // turn the host 90°: e2 goes from (100,0) to (0,100)
        ed.drag(Vec2(100.0, 0.0), Vec2(0.0, 100.0), steps = 8)
        assertClose(pos(el(ed, "e2")).x, 0.0, 1e-9)
        // both riders are exactly where their stated distances put them, on the turned carrier
        assertClose(pos(el(ed, "e4")).x, 0.0, 1e-9, "still on the carrier")
        assertClose(pos(el(ed, "e4")).y, 30.0, 1e-9, "30 mm from e1, as stated")
        assertClose(pos(el(ed, "e5")).y, 60.0, 1e-9, "and 30 mm beyond e4")
        assertTrue(ed.doc.riderAnchors().isEmpty(), "…and still nothing to compensate")

        // the world-anchored twin is the contrast: it is registered, and its distance is *along the carrier*
        val fresh = carrier()
        assertEquals(2, fresh.doc.riderAnchors().size, "world-anchored riders are the ones a gesture compensates")
    }

    /** A **chain**: the base measured from an end, the next from the base — an ordinary dimension chain. */
    @Test
    fun aChainOfStatedDistancesIsADimensionChain() {
        val ed = carrier()
        makeRelative(ed, "e4", "e1")
        makeRelative(ed, "e5", "e4")

        // move the far end along the line: nothing riding it moves, because nothing is measured from it
        ed.drag(Vec2(100.0, 0.0), Vec2(140.0, 0.0))
        assertClose(pos(el(ed, "e4")).x, 30.0, 1e-9)
        assertClose(pos(el(ed, "e5")).x, 60.0, 1e-9)

        // move the *base* of the chain: everything downstream follows, once
        ed.drag(pos(el(ed, "e4")), Vec2(50.0, 0.0))
        assertClose(pos(el(ed, "e4")).x, 50.0, 1e-9)
        assertClose(pos(el(ed, "e5")).x, 80.0, 1e-9, "the second rider kept its 30 mm from the first")

        // …and a rider already measured from something is not silently re-anchored: one anchor at a time,
        // exactly as the polar form insists (Make absolute first)
        assertFalse(ed.doc.makeRelative(el(ed, "e4"), el(ed, "e5")))
        assertTrue(ed.doc.note!!.contains("already measured from"), ed.doc.note!!)
    }

    /** And the circular chain is refused by the acyclicity check every connection uses (OP-4). */
    @Test
    fun measuringTheBaseFromItsOwnDependentIsRefused() {
        val ed = carrier()
        makeRelative(ed, "e5", "e4")
        val steps = ed.doc.journal.size
        assertFalse(ed.doc.makeRelative(el(ed, "e4"), el(ed, "e5")), "that would be a cycle")
        assertTrue(ed.doc.note!!.contains("is already measured from"), ed.doc.note!!)
        assertEquals(steps, ed.doc.journal.size, "and a refusal records nothing")
        assertFalse(ed.doc.riderOf(el(ed, "e4"))!!.carrierRelative)
    }

    /** *Make absolute* is the inverse here too: the rider keeps its place and is world-anchored again. */
    @Test
    fun makeAbsoluteGivesTheRiderItsAbsoluteParameterBack() {
        val ed = carrier()
        makeRelative(ed, "e5", "e4")
        ed.drag(pos(el(ed, "e5")), Vec2(70.0, 0.0))
        val where = pos(el(ed, "e5"))

        ed.setTool(Tools.MAKE_ABSOLUTE)
        ed.click(where)
        assertEquals(where, pos(el(ed, "e5")), "it did not move an inch")
        assertFalse(ed.doc.riderOf(el(ed, "e5"))!!.carrierRelative)
        assertEquals(listOf("along line"), el(ed, "e5").handle!!.fields().map { it.label }, "its own parameter again")
        assertTrue(ed.statusHint.contains("measured from the world again"), "got: ${ed.statusHint}")
        // both riders are world-anchored again: e4 never was re-anchored, and e5 has just been released
        assertEquals(2, ed.doc.riderAnchors().size, "…so it is registered for compensation once more")

        // and the old behaviour is back: moving the base leaves it where it is
        ed.drag(pos(el(ed, "e4")), Vec2(10.0, 0.0))
        assertClose(pos(el(ed, "e5")).x, 70.0, 1e-9)
    }

    // ---- persistence ----

    /** The distance is state, so it rides the `dofs=` seam: `save -> load -> save` byte-equal (OP-18). */
    @Test
    fun aCarrierRelativeRiderRoundTrips() {
        val ed = carrier()
        makeRelative(ed, "e4", "e1")
        makeRelative(ed, "e5", "e4")
        ed.drag(pos(el(ed, "e5")), Vec2(75.0, 0.0))
        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.contains("tool makerel"), once)
        assertEquals(2, once.lines().count { it.startsWith("tool makerel") && it.contains("dofs=") }, once)
        val reloaded = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(reloaded), "save -> load -> save must be identical")

        val fresh = Editor()
        fresh.replaceDocument(reloaded)
        assertEquals(pos(el(ed, "e4")), pos(el(fresh, "e4")))
        assertEquals(pos(el(ed, "e5")), pos(el(fresh, "e5")))
        assertEquals(idOf(fresh, "e4"), fresh.doc.riderOf(el(fresh, "e5"))!!.base!!.id, "as a chain, not as two loose riders")
        // and the reloaded drawing behaves the same way under the same edit
        ed.drag(Vec2(100.0, 0.0), Vec2(0.0, 100.0), steps = 4)
        fresh.drag(Vec2(100.0, 0.0), Vec2(0.0, 100.0), steps = 4)
        assertEquals(pos(el(ed, "e5")), pos(el(fresh, "e5")))
    }

    /** Re-anchoring a rider is one undoable operation, and undo gives the absolute parameter back. */
    @Test
    fun reAnchoringARiderIsOneUndoStep() {
        val ed = carrier()
        val before = DocumentFormat.save(ed.doc)
        makeRelative(ed, "e5", "e4")
        assertTrue(ed.undo())
        assertEquals(before, DocumentFormat.save(ed.doc))
        assertFalse(ed.doc.riderOf(el(ed, "e5"))!!.carrierRelative)
        assertTrue(ed.redo())
        assertTrue(ed.doc.riderOf(el(ed, "e5"))!!.carrierRelative)
    }

    private fun distanceField(
        ed: Editor,
        id: String,
    ): Double = el(ed, id).handle!!.fields().first { it.label == "distance" }.read(Evaluator())!!.mm
}
