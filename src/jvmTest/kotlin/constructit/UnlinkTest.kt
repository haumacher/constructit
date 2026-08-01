package constructit

import constructit.core.Evaluator
import constructit.dsl.PointRef
import constructit.dsl.SegmentRef
import constructit.dsl.point
import constructit.dsl.segment
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **Unlink — the inverse of Join** (GitHub issue #10, the user's own design).
 *
 * A welded point is an *alias*: its own source is bound onto a master ([Document.unweld]'s substrate, OP-16 ×
 * OP-5), so it has no degree of freedom and is hidden, because the pair has to read as one dot. There was no
 * way back that a user could reach — *Make absolute* takes a **click**, and no click can land on a point that
 * is hidden, nor say which of three points merged into one dot is meant. So the gesture is **on the selection**
 * (the element tree lists a welded alias and names it), with the ordinary click as the fallback.
 *
 * Two things this pins beyond the gesture:
 *
 * - **Nothing jumps.** The bound value is read out and restated as the node's own, so the freed point is
 *   exactly where the merged dot was — and, because that position is now *state*, the step restates it
 *   (`dofs=`), or a replay would hand the point back to the master the earlier `weld` step re-binds it to.
 * - **Only the named point leaves.** Each alias holds its own binding, so a three-way weld loses exactly one
 *   member and stays welded otherwise.
 */
class UnlinkTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
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

    private fun Editor.at(el: Element): Vec2 = Evaluator().point(el.ref as PointRef)

    /** Two free points at [a] and [b], the second joined onto the first. */
    private fun joined(
        a: Vec2 = Vec2(0.0, 0.0),
        b: Vec2 = Vec2(30.0, 0.0),
    ): Editor {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(a)
        ed.click(b)
        ed.setTool(Tools.JOIN)
        ed.click(a)
        ed.click(b)
        return ed
    }

    private fun assertRoundTrips(ed: Editor): Document {
        val once = DocumentFormat.save(ed.doc)
        val back = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(back), "save -> load -> save must be byte-equal")
        return back
    }

    // ---- the gesture ----

    /**
     * The headline case: select the welded point, arm *Unlink*, and it is a free point again where the
     * merged dot stood — visible, draggable, and independent of the master from then on.
     */
    @Test
    fun unlinkingTheSelectedPointFreesItWhereItStands() {
        val ed = joined()
        val a = ed.doc.freePoints[0]
        val b = ed.doc.elements.first { it !== a && it.isPoint }
        assertTrue(ed.doc.isWelded(b))
        assertFalse(b.visible, "a welded alias is hidden by construction")

        // the master is dragged first, so the pair is somewhere neither of them was drawn
        ed.drag(Vec2(0.0, 0.0), Vec2(10.0, 40.0))
        assertClose(ed.at(b).x, 10.0)
        assertClose(ed.at(b).y, 40.0)

        ed.selectElement(b)
        ed.setTool(Tools.UNLINK)

        assertFalse(ed.doc.isWelded(b), "it left the weld")
        assertFalse(ed.doc.hiddenByConstruction(b), "…so it is no longer hidden by construction")
        assertTrue(b.visible, "and it is back to visible, pickable life")
        assertTrue(b.draggable, "with a degree of freedom of its own again")
        assertClose(ed.at(b).x, 10.0, msg = "nothing jumps: it stays where the merged dot was")
        assertClose(ed.at(b).y, 40.0)
        assertTrue(ed.statusHint.contains("free point again"), ed.statusHint)

        // independent from here on. It is dragged off the shared spot first — the two points really are
        // coincident, so a canvas gesture there could grab either, which is exactly what "they read as one
        // dot" meant while they were welded.
        ed.doc.moveFreePoint(b, Vec2(60.0, 70.0))
        assertClose(ed.at(b).x, 60.0, msg = "it has a degree of freedom of its own")
        ed.drag(Vec2(10.0, 40.0), Vec2(80.0, 40.0))
        assertClose(ed.at(a).x, 80.0, msg = "the master moved")
        assertClose(ed.at(b).x, 60.0, msg = "…without it")
        assertClose(ed.at(b).y, 70.0)
    }

    /** The fallback the user called good enough: nothing selected, so the tool takes one click. */
    @Test
    fun withNothingSelectedTheToolTakesOneClick() {
        val ed = joined()
        val a = ed.doc.freePoints[0]
        val b = ed.doc.elements.first { it !== a && it.isPoint }
        // drag-attach b's *visible* twin instead: a point welded onto a curve stays visible, so a click reaches it
        ed.clearSelection()
        ed.setTool(Tools.UNLINK)
        assertEquals(0, ed.pendingCount, "with nothing selected it waits for a pick")
        ed.click(Vec2(0.0, 0.0)) // the merged dot: the click reaches the master, which is free already
        assertTrue(ed.statusHint.contains("already free"), "the refusal names what it found: ${ed.statusHint}")
        assertTrue(ed.doc.isWelded(b), "and the alias underneath is untouched")
    }

    // ---- what a weld is, whichever gesture made it ----

    /** A point **drag-welded** onto another (the magnet) unlinks exactly as a Join'd one does. */
    @Test
    fun aDragWeldedPointUnlinksToo() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        val b = ed.doc.freePoints[1]
        ed.drag(Vec2(30.0, 0.0), Vec2(0.0, 0.0))
        assertTrue(ed.doc.isWelded(b), "the magnet welded it")

        ed.selectElement(b)
        ed.setTool(Tools.UNLINK)
        assertFalse(ed.doc.isWelded(b))
        assertClose(ed.at(b).x, 0.0)
        assertClose(ed.at(b).y, 0.0)
    }

    /**
     * A point **drag-attached onto a curve** is the same bond one dimension freer: its source is bound onto a
     * point-on-curve node. Unlink frees it, and the rider bookkeeping goes with the bond — a free point must
     * not still claim a host.
     */
    @Test
    fun aPointDraggedOntoACurveUnlinksAndStopsRidingIt() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.POINT)
        ed.click(Vec2(40.0, 25.0))
        val p = ed.doc.freePoints.last { it.kind == ElementKind.POINT }
        ed.drag(Vec2(40.0, 25.0), Vec2(40.0, 0.0))
        assertEquals(ElementKind.ON_CURVE, p.kind, "dropping it on the segment attached it")
        assertTrue(ed.doc.riderOf(p) != null)

        ed.selectElement(p)
        ed.setTool(Tools.UNLINK)
        assertEquals(ElementKind.POINT, p.kind, "an ordinary free point again")
        assertTrue(ed.doc.riderOf(p) == null, "and it rides nothing any more")
        assertClose(ed.at(p).x, 40.0)
        assertClose(ed.at(p).y, 0.0)
        // it really has two degrees of freedom now: it leaves the line it used to be confined to
        ed.drag(Vec2(40.0, 0.0), Vec2(40.0, 35.0))
        assertClose(ed.at(p).y, 35.0)
    }

    /**
     * A rider the **tool** created has no literal of its own to hand back — its position is published through
     * a re-pointable view (`Document.detachRider`) — and Unlink *delegates* there rather than refusing: on
     * screen it is the same point as the drag-attached one above, and a distinction the user cannot see is not
     * a distinction worth refusing over.
     */
    @Test
    fun aToolMadeRiderIsDelegatedToTheRiderFreeingPath() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.POINT_ON_LINE)
        ed.click(Vec2(60.0, 0.0))
        val r = ed.doc.elements.last { it.kind == ElementKind.ON_CURVE }

        ed.selectElement(r)
        ed.setTool(Tools.UNLINK)
        assertEquals(ElementKind.POINT, r.kind, "freed by the same conversion Make absolute uses")
        assertClose(ed.at(r).x, 60.0)
        assertClose(ed.at(r).y, 0.0)
        ed.drag(Vec2(60.0, 0.0), Vec2(60.0, 20.0))
        assertClose(ed.at(r).y, 20.0, msg = "and it is off the line for good")
        assertRoundTrips(ed)
    }

    // ---- several points welded into one dot ----

    /**
     * **A three-point weld loses exactly the selected member.** Two aliases on one master: unlinking one frees
     * it and says nothing about the other, which falls straight out of each alias holding its own binding.
     */
    @Test
    fun aThreeWayWeldKeepsTheOthersJoined() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(0.0, 30.0))
        val pts = ed.doc.elements.filter { it.isPoint }
        val (a, b, c) = Triple(pts[0], pts[1], pts[2])
        ed.setTool(Tools.JOIN)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.JOIN)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(0.0, 30.0))
        assertTrue(ed.doc.isWelded(b) && ed.doc.isWelded(c), "both are aliases of A")

        ed.selectElement(b)
        ed.setTool(Tools.UNLINK)
        assertFalse(ed.doc.isWelded(b), "the selected one left")
        assertTrue(ed.doc.isWelded(c), "the other member stays welded")
        assertFalse(c.visible, "…and stays hidden with it")

        // the freed one is at the shared spot; moved off it (a canvas drag there could grab either), the
        // master takes the still-welded member along and leaves the freed one where it is
        assertClose(ed.at(b).x, 0.0, msg = "nothing jumped")
        ed.doc.moveFreePoint(b, Vec2(-40.0, 20.0))
        ed.drag(Vec2(0.0, 0.0), Vec2(50.0, 0.0))
        assertClose(ed.at(a).x, 50.0)
        assertClose(ed.at(c).x, 50.0, msg = "C still follows A")
        assertClose(ed.at(b).x, -40.0, msg = "B does not")
    }

    // ---- refusals ----

    @Test
    fun refusalsSayWhatTheyFound() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        val free = ed.doc.freePoints[0]
        val circle = ed.doc.elements.first { it.kind == ElementKind.CIRCLE }

        ed.selectElement(free)
        ed.setTool(Tools.UNLINK)
        assertTrue(ed.statusHint.contains("already free"), ed.statusHint)
        assertTrue(ed.statusHint.contains(ed.doc.nameOf(free)), "it names the point: ${ed.statusHint}")

        ed.selectElement(circle)
        ed.setTool(Tools.UNLINK)
        assertTrue(ed.statusHint.contains("not a point"), ed.statusHint)
        assertTrue(ed.statusHint.contains("circle"), "it says what it is instead: ${ed.statusHint}")

        // a derived point: there is no bond to leave and no position of its own to give back, and the
        // refusal names what it comes from
        ed.setTool(Tools.MIDPOINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        val mid = ed.doc.elements.last { it.kind == ElementKind.DERIVED_POINT }
        ed.selectElement(mid)
        ed.setTool(Tools.UNLINK)
        assertTrue(ed.statusHint.contains("derived by the construction"), ed.statusHint)
        assertTrue(ed.statusHint.contains(ed.doc.nameOf(free)), "it names an input: ${ed.statusHint}")
    }

    /**
     * A **relative** point (OP-4 case b) is bound too, and is deliberately *not* unlinked: nothing drives it —
     * its two degrees of freedom are still its own, written as a distance and an angle — so it is refused with
     * a pointer to the conversion that does undo it.
     */
    @Test
    fun aRelativePointIsRefusedWithAPointerToMakeAbsolute() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        val anchor = ed.doc.freePoints[0]
        val pt = ed.doc.freePoints[1]
        ed.setTool(Tools.MAKE_RELATIVE)
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(0.0, 0.0))
        assertTrue(ed.doc.relativeOf(pt) != null, "it is measured from the anchor")

        ed.selectElement(pt)
        ed.setTool(Tools.UNLINK)
        assertTrue(ed.statusHint.contains("not joined to anything"), ed.statusHint)
        assertTrue(ed.statusHint.contains("Make absolute"), "it points at the tool that does undo it: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains(ed.doc.nameOf(anchor)), "and names the anchor: ${ed.statusHint}")
        assertTrue(ed.doc.relativeOf(pt) != null, "nothing was changed by the refusal")
    }

    // ---- persistence and undo ----

    /**
     * The unlink is an edit, so it is a **recorded step** — and the freed position is state on it. Without
     * that, replay would run the `weld` step first and hand the point straight back to the master.
     */
    @Test
    fun theFreedPositionSurvivesSaveLoadSaveAndReplayReproducesIt() {
        val ed = joined()
        val a = ed.doc.freePoints[0]
        val b = ed.doc.elements.first { it !== a && it.isPoint }
        ed.selectElement(b)
        ed.setTool(Tools.UNLINK)
        // ...and then moved somewhere neither the master nor its own drawn position is
        ed.drag(Vec2(0.0, 0.0), Vec2(80.0, 55.0))

        val once = DocumentFormat.save(ed.doc)
        assertTrue(
            once.lines().any { it.startsWith("tool unlink") && it.contains("dofs=") },
            "the step restates the freed position: $once",
        )
        val back = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(back), "save -> load -> save must be byte-equal")

        val master = back.freePoints[0]
        val freed = back.elements.first { it !== master && it.isPoint }
        assertFalse(back.isWelded(freed), "it comes back free")
        val p = Evaluator().point(freed.ref as PointRef)
        assertClose(p.x, 80.0, msg = "and where it was left, not where the weld had it")
        assertClose(p.y, 55.0)
    }

    /** The document-level operation records its own step and restates the same way. */
    @Test
    fun theStandaloneUnweldStepRestatesTheFreedPositionToo() {
        val ed = joined()
        val a = ed.doc.freePoints[0]
        val b = ed.doc.elements.first { it !== a && it.isPoint }
        ed.doc.unlink(b)
        ed.checkpoint()
        ed.doc.moveFreePoint(b, Vec2(12.0, 34.0))

        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.lines().any { it.startsWith("unweld ") && it.contains("dofs=") }, once)
        val back = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(back), "save -> load -> save must be byte-equal")
        val freed = back.elements.first { it !== back.freePoints[0] && it.isPoint }
        val p = Evaluator().point(freed.ref as PointRef)
        assertClose(p.x, 12.0)
        assertClose(p.y, 34.0)
    }

    /**
     * An **older script** — one written before the step restated anything — still means what it always did:
     * the point is freed where the geometry puts it. Kept permanently, because this is the assertion that
     * catches the day `dofs=` on an unweld becomes required or changes meaning (OP-18's versioning rule).
     */
    @Test
    fun anUnweldWrittenWithoutDofsStillFreesThePointWhereTheWeldHadIt() {
        val script =
            """
            constructit 2
            point 0,0 -> e1
            point 30,0 -> e2
            weld e2 e1
            unweld e2
            """.trimIndent() + "\n"
        val doc = DocumentFormat.load(script)
        val e2 = doc.elements.first { it.id != doc.freePoints[0].id && it.isPoint }
        assertFalse(doc.isWelded(e2))
        val p = Evaluator().point(e2.ref as PointRef)
        assertClose(p.x, 0.0, msg = "freed where the weld had put it, exactly as the gesture did")
        assertClose(p.y, 0.0)
        assertTrue(doc.loadNotes.isEmpty(), "a script that predates the restatement has nothing to be told: ${doc.loadNotes}")
    }

    /** One gesture, one undo: the weld comes back whole, hidden alias and all — and redo takes it away again. */
    @Test
    fun undoRestoresTheWeldAndRedoLetsItGoAgain() {
        val ed = joined()
        val b0 = ed.doc.elements.first { it !== ed.doc.freePoints[0] && it.isPoint }
        ed.selectElement(b0)
        ed.setTool(Tools.UNLINK)
        assertFalse(ed.doc.isWelded(ed.doc.elements.first { it !== ed.doc.freePoints[0] && it.isPoint }))

        ed.undo()
        // the document is rebuilt by a replay, so every handle has to be re-fetched
        val afterUndo = ed.doc.elements.first { it !== ed.doc.freePoints[0] && it.isPoint }
        assertTrue(ed.doc.isWelded(afterUndo), "undo puts the weld back")
        assertFalse(afterUndo.visible, "…hidden by construction again")

        ed.redo()
        val afterRedo = ed.doc.elements.first { it !== ed.doc.freePoints[0] && it.isPoint }
        assertFalse(ed.doc.isWelded(afterRedo), "and redo lets it go again")
        assertTrue(afterRedo.visible)
    }

    /**
     * The **visibility rule's inverse is complete**: a welded alias refuses to be shown while it is welded
     * ([Document.hiddenByConstruction]), and unlinking is what ends that — not a hide/show step, which would
     * be a second authority over the same pixel.
     */
    @Test
    fun aWeldedAliasRefusesToBeShownUntilItIsUnlinked() {
        val ed = joined()
        val b = ed.doc.elements.first { it !== ed.doc.freePoints[0] && it.isPoint }
        ed.selectElement(b)
        assertEquals(0, ed.setSelectionVisible(true), "showing it is refused while it is welded")
        assertFalse(b.visible)

        ed.selectElement(b)
        ed.setTool(Tools.UNLINK)
        assertTrue(b.visible, "unlinking is what brings it back")
        assertFalse(ed.doc.hiddenByConstruction(b))
        // and it is pickable again: a plain click selects it where the master no longer hides it
        ed.doc.moveFreePoint(b, Vec2(70.0, 5.0))
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(70.0, 5.0))
        assertEquals(b.id, ed.selection?.id, "the freed point takes a click of its own")
    }

    /** A wall traced over the weld keeps working: the consumers follow the point, which stops following. */
    @Test
    fun whatWasBuiltOnTheWeldedPointFollowsItAfterUnlink() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        val a = ed.doc.freePoints[0]
        val b = ed.doc.freePoints[1]
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(30.0, 40.0))
        val seg = ed.doc.elements.first { it.kind == ElementKind.SEGMENT }
        ed.setTool(Tools.JOIN)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        assertClose(Evaluator().segment(seg.ref as SegmentRef).a.x, 0.0, msg = "the segment end followed the weld")

        ed.selectElement(b)
        ed.setTool(Tools.UNLINK)
        ed.doc.moveFreePoint(b, Vec2(-20.0, -10.0))
        val s = Evaluator().segment(seg.ref as SegmentRef)
        assertClose(s.a.x, -20.0, msg = "the segment follows the freed point, not the old master")
        assertClose(s.a.y, -10.0)
        assertClose(Evaluator().point(a.ref as PointRef).x, 0.0, msg = "and the master stayed put")
    }

    /** The tool is in the palette's registry, takes one point and adds no scalar of its own. */
    @Test
    fun theToolIsDeclaredAsOnePointAndNothingElse() {
        val ed = Editor()
        val def = ed.doc.toolDef(Tools.UNLINK)!!
        assertEquals(1, def.slots.size)
        assertTrue(def.scalars.isEmpty())
        assertTrue(def.fromSelection, "it reads the selection, which is how it reaches a hidden alias")
        assertFalse(def.replicates, "freeing one point is not a gesture an orbit fans")
    }
}
