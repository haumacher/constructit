package constructit

import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.core.SegmentValue
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.PointerButton
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.l10n.contains
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **One pick cycle** (OP-16 / OP-13 / OP-21): a SELECT click collects *every* candidate within the pick
 * tolerance, ranks them by the precedence the press has always used, and selects the first — while clicking
 * the same spot again steps to the next, wrapping. It replaces two hand-built two-element cycles (the
 * group/member reach, and jamb-vs-leg), and what these tests pin is the pair of promises that makes that
 * safe:
 *
 * - **the first-click invariant** — a click that is not a repeat selects exactly what the old code selected,
 *   which is why every existing selection and gesture test still passes untouched;
 * - **nothing under the cursor is unreachable** — including the two things that used to lose outright: the
 *   curve a jamb outranked, and a *derived* point lying on the curve it came from.
 */
class PickCycleTest {
    private fun Editor.click(
        world: Vec2,
        additive: Boolean = false,
    ) {
        val s = camera.worldToScreen(world)
        pointerDown(s, PointerButton.PRIMARY, additive)
        pointerUp(s)
    }

    /** A click nudged by [dx] screen pixels — inside the repeat threshold, or outside it. */
    private fun Editor.clickOffBy(
        world: Vec2,
        dx: Double,
    ) {
        val s = camera.worldToScreen(world) + Vec2(dx, 0.0)
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

    /** What the selection addresses, as one comparable string. */
    private fun what(ed: Editor): String =
        ed.selectedJamb?.let { "jamb" }
            ?: ed.selectedGroup?.let { "group:${it.name}" }
            ?: ed.selection?.id
            ?: "nothing"

    /** Walk the cycle from a fresh first click, returning what each click addressed. */
    private fun walk(
        ed: Editor,
        at: Vec2,
        clicks: Int,
    ): List<String> {
        ed.click(Vec2(-900.0, -900.0)) // nothing there: the next click is a first click
        return (1..clicks).map {
            ed.click(at)
            what(ed)
        }
    }

    // ---- a pile: a point, two curves, a jamb and a grouped member, all at one spot ----

    /**
     * A wall (leg on y=0, footprint faces at y=±5) with an opening whose leading jamb crosses at x=40, a
     * plain segment laid through the same place, and a free point sitting exactly there — with the wall's
     * own leg **grouped**, so the pile carries the group/member pair too. Clicked at (40, 1) everything but
     * the footprint is inside the 2.5 mm pick tolerance, which makes the pile there five deep:
     *
     * `point, group bits, its member leg, the opening's jamb, the plain segment`
     *
     * — one entry of every kind the machine has to rank, including the two that used to be unreachable
     * there: the jamb the leg outranks, and a curve with no freedom of its own (the plain segment, which
     * both the jamb's distance cap and the drag filter push to the tail).
     *
     * Returns the free point, since it is what the first click must find.
     */
    private fun pile(): Pair<Editor, Element> {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 3.0))
        ed.finishPath()
        ed.activeScalar = ed.doc.newParameter("w", 20.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(50.0, 0.0)) // 40..60, so the jambs cross at x=40 and x=60
        // the extra geometry is placed *freely* (Alt in the shell), so it stays exactly where it is put
        // instead of being snapped onto the wall it is meant to lie beside
        ed.snapEnabled = false
        ed.setTool(Tools.POINT)
        ed.click(Vec2(40.0, 1.0))
        val point = ed.doc.elements.last { it.kind == ElementKind.POINT }
        ed.click(Vec2(-60.0, 40.0)) // a second point, far away, to group the segment with
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(20.0, 1.0))
        ed.click(Vec2(80.0, 1.0))
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(70.0, 1.0)) // the wall's leg, away from the pile (the only draggable curve there)
        ed.click(Vec2(-60.0, 40.0), additive = true)
        assertNotNull(ed.groupSelection("bits"))
        ed.snapEnabled = true
        return ed to point
    }

    /** The pile's five entries, in rank order: point, group, member leg, jamb, plain segment. */
    private fun pileOrder(ed: Editor): List<String> {
        val leg = ed.doc.groups.single().members.first { it.kind == ElementKind.SEGMENT }
        val plain = ed.doc.elements.last { it.kind == ElementKind.SEGMENT }
        return listOf(pointOfPile(ed).id, "group:bits", leg.id, "jamb", plain.id)
    }

    private fun pointOfPile(ed: Editor): Element =
        ed.doc.elements.first { it.kind == ElementKind.POINT && (Evaluator().valueOf(it.ref) as PointValue).p.x == 40.0 }

    /**
     * The pile's **first** click is today's winner — a draggable point beats everything meeting at it — and
     * the clicks after it reach every other candidate in turn, group before member, then wrap.
     */
    @Test
    fun everyCandidateInThePileIsReachedInTurnAndTheCycleWraps() {
        val (ed, point) = pile()
        val at = Vec2(40.0, 1.0)

        val seen = walk(ed, at, 5)
        assertEquals(5, ed.pickCycleSize, "point, group, member leg, jamb, plain segment: $seen")
        assertEquals(point.id, seen[0], "the first click is the draggable point — today's winner")
        assertEquals("group:bits", seen[1], "a grouped hit offers the whole group first (OP-16)")
        assertEquals(pileOrder(ed), seen, "every candidate, in rank order")
        assertEquals(5, seen.distinct().size, "and every click reached something new: $seen")

        ed.click(at)
        assertEquals(point.id, what(ed), "the cycle wraps")
        assertEquals(1, ed.pickCyclePosition)
    }

    /** The cycle is **transient**: it selects, and a selection is not part of the construction (OP-18). */
    @Test
    fun cyclingChangesNothingTheFileCarries() {
        val (ed, _) = pile()
        val before = DocumentFormat.save(ed.doc)
        walk(ed, Vec2(40.0, 1.0), 6)
        assertTrue(ed.key("Tab"))
        assertEquals(before, DocumentFormat.save(ed.doc), "clicking round the pile writes nothing")
        assertEquals(before, DocumentFormat.save(DocumentFormat.load(before)), "and the round trip is untouched")
    }

    /** The status line says where the click stands in the pile, and what one more click will do. */
    @Test
    fun theStatusLineNamesThePositionInThePile() {
        val (ed, _) = pile()
        val at = Vec2(40.0, 1.0)
        ed.click(Vec2(-900.0, -900.0))
        ed.click(at)
        assertTrue(ed.statusHint.contains("(1 of 5 here — click again for the next)"), "got: ${ed.statusHint}")
        ed.click(at)
        assertTrue(ed.statusHint.contains("(2 of 5 here — click again for the next)"), "got: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("bits"), "…and names what it took: ${ed.statusHint}")
        assertEquals(2, ed.pickCyclePosition)
    }

    /** A click **far** from the previous one is a first click again: the cycle is per-spot, not global. */
    @Test
    fun aFarClickStartsTheCycleOverAndANudgeDoesNot() {
        val (ed, point) = pile()
        val at = Vec2(40.0, 1.0)

        ed.click(Vec2(-900.0, -900.0))
        ed.click(at)
        assertEquals(point.id, what(ed))
        // a nudge inside the repeat threshold is still the same spot, so it steps on
        ed.clickOffBy(at, 2.0)
        assertEquals(2, ed.pickCyclePosition, "a 2 px nudge is the same spot: ${ed.statusHint}")
        // …and one beyond it is a new pick, so the ranking's winner comes back
        ed.clickOffBy(at, 8.0)
        assertEquals(1, ed.pickCyclePosition, "an 8 px move is a new pick: ${ed.statusHint}")
        assertEquals(point.id, what(ed))
    }

    /** A **drag** is not a click, so it leaves no cycle behind: the click after it starts from the top. */
    @Test
    fun aDragResetsTheCycle() {
        val (ed, point) = pile()
        val at = Vec2(40.0, 1.0)

        ed.click(Vec2(-900.0, -900.0))
        ed.click(at)
        assertEquals(1, ed.pickCyclePosition)
        ed.drag(at, Vec2(40.0, 25.0)) // the point is both what is selected and what the ranking grabs
        assertClose(pos(point).y, 25.0, msg = "the point moved")
        assertEquals(0, ed.pickCyclePosition, "a drag leaves no cycle standing")
        ed.click(Vec2(40.0, 25.0))
        assertEquals(point.id, what(ed), "the click after a drag is a first click")
        assertEquals(1, ed.pickCyclePosition)
    }

    private fun pos(el: Element): Vec2 = (Evaluator().valueOf(el.ref) as PointValue).p

    // ---- selection primes the drag: cycle to it, then drag exactly it ----

    /**
     * **The other half of cycling.** A press that continues the cycle drags what the cycle selected, even
     * where the ranking would grab something else: with the leg reached past the point sitting on it, the
     * drag moves the *leg*. Without priming the point would have taken the grab, and the cycle would be a
     * way of *looking* at things rather than of editing them.
     */
    @Test
    fun aPrimedSelectionTakesTheGrabFromTheRanking() {
        val (ed, point) = pile()
        val at = Vec2(40.0, 1.0)
        val leg = ed.doc.groups.single().members.first { it.kind == ElementKind.SEGMENT }

        walk(ed, at, 3) // point, group, then the leg alone
        assertEquals(leg.id, ed.selection?.id)
        ed.drag(at, Vec2(40.0, 15.0))

        assertTrue(ed.statusHint.contains("what is selected takes the grab"), "got: ${ed.statusHint}")
        assertClose(legY(leg), 14.0, msg = "the *leg* took the grab, offset from where it was pressed")
        assertClose(pos(point).y, 1.0, msg = "and the point that would have won the ranking did not move")
    }

    /** An **immovable** primed selection says why and moves nothing — no silent fall-through to a target. */
    @Test
    fun anImmovablePrimedSelectionExplainsAndMovesNothing() {
        val (ed, point) = pile()
        val at = Vec2(40.0, 1.0)
        val leg = ed.doc.groups.single().members.first { it.kind == ElementKind.SEGMENT }
        val plain = ed.doc.elements.last { it.kind == ElementKind.SEGMENT }
        val legWas = legY(leg)

        walk(ed, at, 5) // …down to the plain segment, which has no freedom of its own
        assertEquals(plain.id, ed.selection?.id)
        ed.drag(at, Vec2(40.0, 15.0))

        assertTrue(ed.statusHint.contains(ed.doc.nameOf(plain)), "the reason names it: ${ed.statusHint}")
        assertClose(pos(point).y, 1.0, msg = "the point did not move")
        assertClose(legY(leg), legWas, msg = "and neither did the leg")
        assertEquals(plain.id, ed.selection?.id, "the selection is left where the cycle put it")
    }

    /** Deselecting gives the default targeting back — the way out of a primed grab (Esc, or click away). */
    @Test
    fun deselectingRestoresTheDefaultTargeting() {
        val (ed, point) = pile()
        val at = Vec2(40.0, 1.0)

        walk(ed, at, 3) // the leg alone is primed
        assertTrue(ed.key("Escape"))
        assertEquals(0, ed.selectionCount)
        ed.drag(at, Vec2(40.0, 15.0))
        assertClose(pos(point).y, 15.0, msg = "with nothing primed the nearest point takes the grab again")
    }

    /**
     * The placed group's frame rule **is** this rule, and goes through the same press: a group selected as a
     * whole primes the frame, so pressing a member and dragging moves the frame (OP-16, unchanged).
     */
    @Test
    fun aWholeSelectedPlacedGroupPrimesItsFrame() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 0.0), additive = true)
        val g = assertNotNull(ed.groupSelection("part"))
        assertTrue(ed.placeGroup(g), "got: ${ed.statusHint}")

        ed.click(Vec2(-900.0, -900.0))
        ed.click(Vec2(40.0, 0.0)) // the whole group
        assertEquals(g, ed.selectedFrame())
        val origin = assertNotNull(ed.doc.frameValueOf(g)).origin
        ed.drag(Vec2(40.0, 0.0), Vec2(50.0, 10.0))

        assertClose(assertNotNull(ed.doc.frameValueOf(g)).origin.x, origin.x + 10.0, msg = "the frame moved")
        assertClose(assertNotNull(ed.doc.frameValueOf(g)).origin.y, origin.y + 10.0)
        assertEquals(g, ed.selectedFrame(), "and the group stays selected as a whole")
    }

    /** A leg's perpendicular coordinate, read off the drawing. */
    private fun legY(leg: Element): Double = (Evaluator().valueOf(leg.ref) as SegmentValue).seg.a.y

    /** Tab is the keyboard twin of clicking again — a DOF must not be reachable one way only (OP-13). */
    @Test
    fun tabStepsTheCycleLikeAnotherClick() {
        val (ed, point) = pile()
        val at = Vec2(40.0, 1.0)
        ed.click(Vec2(-900.0, -900.0))
        ed.click(at)
        assertEquals(point.id, what(ed))
        assertTrue(ed.key("Tab"))
        assertEquals(2, ed.pickCyclePosition)
        assertEquals("group:bits", what(ed), "Tab moved on: ${ed.statusHint}")
        // with no cycle live it is not consumed, so Tab keeps its usual meaning in the shell
        ed.clearSelection()
        assertTrue(!ed.key("Tab"))
    }

    // ---- the jamb rule stays the RANKING; cycling makes the loser reachable (OP-21) ----

    /**
     * Off the centreline the jamb is nearer and wins, exactly as before — and the leg it beat is the next
     * thing in the cycle rather than being unreachable there.
     */
    @Test
    fun offTheCentrelineTheJambStillWinsAndTheLegIsNext() {
        val ed = wallWithOpening()

        ed.click(Vec2(-900.0, -900.0))
        ed.click(Vec2(40.0, 1.0))
        assertNotNull(ed.selectedJamb, "across the wall the jamb is nearer: ${ed.statusHint}")
        assertEquals(1, ed.pickCyclePosition)
        ed.click(Vec2(40.0, 1.0))
        assertNull(ed.selectedJamb)
        assertTrue(ed.selectionLabel().render().startsWith("leg"), "the carrier leg the jamb outranked: ${ed.selectionLabel()}")
    }

    /**
     * On the centreline the leg is nearer and wins, exactly as before — and the jamb is now reachable from
     * there too, which it was not.
     */
    @Test
    fun alongTheCentrelineTheLegStillWinsAndTheJambIsReachable() {
        val ed = wallWithOpening()

        ed.click(Vec2(-900.0, -900.0))
        ed.click(Vec2(41.0, 0.3))
        assertNull(ed.selectedJamb, "along the wall the leg is nearer: ${ed.selectionLabel()}")
        assertTrue(ed.selectionLabel().render().startsWith("leg"), "got: ${ed.selectionLabel()}")
        ed.click(Vec2(41.0, 0.3))
        assertNotNull(ed.selectedJamb, "and the jamb is reachable by clicking again: ${ed.statusHint}")
        assertEquals(listOf("position", "width", "sill", "head"), ed.selectionFields().map { it.label })
    }

    private fun wallWithOpening(): Editor {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 3.0))
        ed.finishPath()
        ed.activeScalar = ed.doc.newParameter("w", 20.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.SELECT)
        return ed
    }

    // ---- a point cannot dodge, a curve can be clicked elsewhere — however the point was born ----

    /**
     * A **derived** point outranks the curve it sits on. It used to lose (only *draggable* points beat the
     * curves), which made a midpoint on its own segment hard to select at all — and the rationale never
     * depended on how the point came to be: a point cannot dodge, a curve can be clicked elsewhere. The
     * drag rank is untouched, so what the click reaches and what a grab moves may differ.
     */
    @Test
    fun aDerivedPointOnItsOwnSegmentTakesTheClickAndTheSegmentIsNext() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.MIDPOINT)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.SELECT)
        val mid = ed.doc.elements.last { it.kind == ElementKind.DERIVED_POINT }
        val seg = ed.doc.elements.first { it.kind == ElementKind.SEGMENT }

        ed.click(Vec2(-900.0, -900.0))
        ed.click(Vec2(0.0, 0.0))
        assertEquals(mid.id, ed.selection?.id, "the midpoint takes the click: ${ed.statusHint}")
        assertEquals(2, ed.pickCycleSize)
        ed.click(Vec2(0.0, 0.0))
        assertEquals(seg.id, ed.selection?.id, "the segment is the next candidate: ${ed.statusHint}")
    }

    /** The same for a **rider**: the point on the curve beats its own carrier. */
    @Test
    fun aRiderTakesTheClickBeforeItsCarrier() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-60.0, 20.0))
        ed.click(Vec2(60.0, 20.0))
        ed.setTool(Tools.POINT_ON_LINE)
        ed.click(Vec2(-10.0, 20.0))
        ed.setTool(Tools.SELECT)
        val rider = ed.doc.elements.last { it.kind == ElementKind.ON_CURVE }
        val seg = ed.doc.elements.first { it.kind == ElementKind.SEGMENT }

        ed.click(Vec2(-900.0, -900.0))
        ed.click(Vec2(-10.0, 20.0))
        assertEquals(rider.id, ed.selection?.id, "the rider: ${ed.statusHint}")
        ed.click(Vec2(-10.0, 20.0))
        assertEquals(seg.id, ed.selection?.id, "and its carrier next: ${ed.statusHint}")
    }

    // ---- the group/member pair is two consecutive entries of the same list (OP-16) ----

    /**
     * A grouped hit contributes **(whole group, that member alone)**, in that order — so the reach that used
     * to be its own two-state cycle is now two entries of the general one, and the order is unchanged.
     */
    @Test
    fun aGroupedHitOffersTheWholeGroupThenTheMemberAlone() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(60.0, 0.0), additive = true)
        val g = assertNotNull(ed.groupSelection("kitchen"))

        val seen = walk(ed, Vec2(-60.0, 0.0), 3)
        assertEquals(listOf("group:kitchen", "e1", "group:kitchen"), seen, "group, member, group again")
        assertEquals(2, ed.pickCycleSize, "the pile is exactly the pair")
        assertEquals(g, ed.selectedGroup)
    }
}
