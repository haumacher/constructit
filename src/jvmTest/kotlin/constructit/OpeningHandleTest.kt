package constructit

import constructit.core.Evaluator
import constructit.core.SolidValue
import constructit.dsl.RegionRef
import constructit.dsl.region
import constructit.dsl.scalar
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.PathInterval
import constructit.editor.ThickPath
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * OP-21's openings become **editable where they are drawn** (OP-13): the two jambs the plan convention
 * draws are grabbable, the leading one sliding the whole opening (`pos`) and the trailing one setting its
 * `width`. Nothing new is stored — no element, no node — so what these tests pin is that a *drawing*
 * carries a handle: the pick resolves a jamb into a handle over the interval's existing parameters, and the
 * same clamps bound a drag and a typed number.
 */
class OpeningHandleTest {
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

    private fun Editor.marqueeAll() {
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(Vec2(-400.0, -400.0)))
        pointerMove(camera.worldToScreen(Vec2(400.0, 400.0)))
        pointerUp(camera.worldToScreen(Vec2(400.0, 400.0)))
    }

    /**
     * A straight 100-long wall, 10 thick, with one 20-wide opening at 40..60 on its single leg — so the two
     * jambs cross the wall at x=40 and x=60 and the faces run at y=±5.
     */
    private fun wallWithOpening(): Editor {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 3.0))
        ed.finishPath()
        ed.activeScalar = ed.doc.newParameter("w", 20.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(50.0, 0.0)) // centred on the click -> 40..60
        ed.setTool(Tools.SELECT)
        return ed
    }

    private fun tp(ed: Editor): ThickPath = ed.doc.thickPaths.single()

    private fun iv(ed: Editor): PathInterval = tp(ed).intervals.single()

    private fun pos(i: PathInterval) = Evaluator().scalar(i.position).mm

    private fun width(i: PathInterval) = Evaluator().scalar(i.width).mm

    /** The x-extents of the plan's face pieces along [y] — the drawn form of "solid here, gap there". */
    private fun facePieces(
        ed: Editor,
        y: Double,
    ): List<Pair<Double, Double>> =
        ed.doc
            .planOf(tp(ed), Evaluator())!!
            .filter { kotlin.math.abs(it.a.y - y) < 1e-9 && kotlin.math.abs(it.b.y - y) < 1e-9 }
            .map { minOf(it.a.x, it.b.x) to maxOf(it.a.x, it.b.x) }
            .sortedBy { it.first }

    // ---- the two handles ----

    @Test
    fun draggingTheLeadingJambSlidesTheWholeOpening() {
        val ed = wallWithOpening()
        val i = iv(ed)
        val nodesBefore = ed.doc.cx.nodesCreated
        val elementsBefore = ed.doc.elements.toList()

        // grab the jamb at x=40, off the carrier so the leg cannot claim the press, and slide it to x=15
        ed.drag(Vec2(40.0, 4.0), Vec2(15.0, 4.0))

        assertClose(pos(i), 15.0, msg = "the leading jamb writes the position")
        assertClose(width(i), 20.0, msg = "the width is start-relative, so it is preserved")
        assertEquals(listOf(0.0 to 15.0, 35.0 to 100.0), facePieces(ed, 5.0), "the opening is drawn where it now is")
        assertEquals(listOf(0.0 to 15.0, 35.0 to 100.0), facePieces(ed, -5.0), "on both faces")
        assertEquals(nodesBefore, ed.doc.cx.nodesCreated, "a jamb drag is a value edit — nothing is built")
        assertEquals(elementsBefore, ed.doc.elements, "and no element is added or replaced")
    }

    @Test
    fun draggingTheTrailingJambSetsTheWidthAndLeavesTheStart() {
        val ed = wallWithOpening()
        val i = iv(ed)

        ed.drag(Vec2(60.0, 4.0), Vec2(85.0, 4.0))

        assertClose(pos(i), 40.0, msg = "the leading edge stays put")
        assertClose(width(i), 45.0, msg = "the trailing jamb writes width = cursor - pos")
        assertEquals(listOf(0.0 to 40.0, 85.0 to 100.0), facePieces(ed, 5.0))
    }

    /** A grab beside the jamb line holds its offset, so the opening does not jump to the cursor. */
    @Test
    fun aJambGrabHoldsItsOffset() {
        val ed = wallWithOpening()
        val i = iv(ed)
        ed.setTool(Tools.SELECT)
        // press 2 mm short of the jamb (still within the pick tolerance), then move by exactly 10 mm
        ed.pointerDown(ed.camera.worldToScreen(Vec2(38.0, 4.0)))
        assertNotNull(ed.selectedJamb, "the press landed on the jamb; got: ${ed.statusHint}")
        ed.pointerMove(ed.camera.worldToScreen(Vec2(48.0, 4.0)))
        assertClose(pos(i), 50.0, msg = "the opening moved by the gesture's 10 mm, not to the cursor's 48")
        ed.pointerUp(ed.camera.worldToScreen(Vec2(48.0, 4.0)))
    }

    // ---- the clamps: the leg's extent, and jambs that would cross ----

    @Test
    fun aSlideIsClampedToTheLegAndSaysSo() {
        val ed = wallWithOpening()
        val i = iv(ed)

        ed.drag(Vec2(40.0, 4.0), Vec2(-30.0, 4.0))
        assertClose(pos(i), 0.0, msg = "clamped at the leg's start")
        assertTrue(ed.statusHint.contains("kept on its leg"), "got: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("0…80"), "the bound is named: ${ed.statusHint}")

        ed.drag(Vec2(0.0, 4.0), Vec2(200.0, 4.0))
        assertClose(pos(i), 80.0, msg = "clamped at the far end, leaving room for the width")
        assertClose(width(i), 20.0, msg = "and the clamp never eats the width")
        assertEquals(listOf(0.0 to 80.0), facePieces(ed, 5.0), "the gap runs to the wall's end")
    }

    @Test
    fun aWidthIsRefusedRatherThanCrossingTheJambs() {
        val ed = wallWithOpening()
        val i = iv(ed)

        // drag the trailing jamb back *past* the leading one
        ed.drag(Vec2(60.0, 4.0), Vec2(20.0, 4.0))
        assertTrue(width(i) > 0.0, "a crossed-over opening is refused, not drawn inside out")
        assertClose(width(i), 1.0, msg = "held at the narrowest an opening may be")
        assertClose(pos(i), 40.0, msg = "and the leading edge still did not move")
        assertTrue(ed.statusHint.contains("cannot be closed by crossing its jambs"), "got: ${ed.statusHint}")

        // …and it is reversible: dragging back out grows the opening again from where it stopped
        ed.drag(Vec2(41.0, 4.0), Vec2(70.0, 4.0))
        assertClose(width(i), 30.0)
    }

    @Test
    fun aWidthIsClampedToWhatIsLeftOfTheLeg() {
        val ed = wallWithOpening()
        val i = iv(ed)
        ed.drag(Vec2(60.0, 4.0), Vec2(140.0, 4.0))
        assertClose(width(i), 60.0, msg = "at most legLength - pos")
        assertTrue(ed.statusHint.contains("at most 60"), "got: ${ed.statusHint}")
    }

    // ---- the same values, typed (OP-13) ----

    @Test
    fun aSelectedJambShowsTheOpeningsValuesAsTypedFields() {
        val ed = wallWithOpening()
        val i = iv(ed)
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(40.0, 4.0))

        val jamb = assertNotNull(ed.selectedJamb, "clicking a jamb selects the opening; got: ${ed.statusHint}")
        assertTrue(jamb.interval === i)
        assertTrue(!jamb.atEnd, "the jamb at 40 is the leading one")
        assertEquals(0, ed.selectionCount, "an opening owns no element, so nothing else is selected")
        assertEquals(listOf("position", "width", "sill", "head"), ed.selectionFields().map { it.label })
        assertTrue(ed.selectionFields().all { it.writable }, "every value of an opening is writable")
        assertClose(ed.selectionFields()[0].read(Evaluator())!!.mm, 40.0)
        assertClose(ed.selectionFields()[3].read(Evaluator())!!.mm, 2100.0, msg = "the head reads beside them")
        assertTrue(ed.selectionLabel().contains("opening on leg 1"), "got: ${ed.selectionLabel()}")

        // the same order for the trailing jamb: what differs is which node the *drag* writes
        ed.click(Vec2(60.0, 4.0))
        assertTrue(assertNotNull(ed.selectedJamb).atEnd)
        assertEquals(listOf("position", "width", "sill", "head"), ed.selectionFields().map { it.label })
    }

    @Test
    fun typedPositionAndWidthWriteThroughTheJambsFields() {
        val ed = wallWithOpening()
        val i = iv(ed)
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(40.0, 4.0))

        assertTrue(ed.writeSelectionField(0, 10.0))
        assertClose(pos(i), 10.0)
        assertTrue(ed.writeSelectionField(1, 30.0))
        assertClose(width(i), 30.0)
        assertEquals(listOf(0.0 to 10.0, 40.0 to 100.0), facePieces(ed, 5.0), "typing moves the drawing exactly as dragging does")

        // typing is bounded by the same rule as the drag, and reports it the same way (OP-13)
        assertTrue(ed.writeSelectionField(0, 900.0))
        assertClose(pos(i), 70.0)
        assertTrue(ed.statusHint.contains("kept on its leg"), "got: ${ed.statusHint}")

        // and a height is a field of the same handle
        assertTrue(ed.writeSelectionField(2, 900.0))
        assertClose(Evaluator().scalar(i.sill).mm, 900.0)
    }

    /** Selecting a jamb emphasizes the opening's own drawing — it has no element to highlight. */
    @Test
    fun aSelectedOpeningIsEmphasizedAsItsOwnDrawing() {
        val ed = wallWithOpening()
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(40.0, 4.0))
        val outline = ed.doc.intervalOutline(tp(ed), iv(ed), Evaluator())
        assertEquals(4, outline.size, "the two jambs and the gap span on either face")

        fun has(
            ax: Double,
            ay: Double,
            bx: Double,
            by: Double,
        ) = outline.any { (it.a - Vec2(ax, ay)).length() < 1e-9 && (it.b - Vec2(bx, by)).length() < 1e-9 }
        assertTrue(has(40.0, -5.0, 40.0, 5.0) || has(40.0, 5.0, 40.0, -5.0), "leading jamb: $outline")
        assertTrue(has(60.0, -5.0, 60.0, 5.0) || has(60.0, 5.0, 60.0, -5.0), "trailing jamb: $outline")
        assertTrue(has(40.0, 5.0, 60.0, 5.0) || has(40.0, -5.0, 60.0, -5.0), "the gap span: $outline")
    }

    // ---- one gesture, one undo step; one file either way ----

    @Test
    fun aJambDragIsOneUndoStepAndSurvivesTheRoundTrip() {
        val ed = wallWithOpening()
        val i = iv(ed)
        val before = DocumentFormat.save(ed.doc)

        ed.drag(Vec2(40.0, 4.0), Vec2(70.0, 4.0))
        assertClose(pos(i), 70.0)
        val after = DocumentFormat.save(ed.doc)
        assertTrue(after != before, "the drag changed the script")
        assertEquals(after, DocumentFormat.save(DocumentFormat.load(after)), "save -> load -> save is byte-identical")
        assertClose(Evaluator().scalar(DocumentFormat.load(after).thickPaths.single().intervals.single().position).mm, 70.0)

        assertTrue(ed.undo())
        assertEquals(before, DocumentFormat.save(ed.doc), "one drag, one undo step")
        assertClose(pos(assertNotNull(ed.doc.thickPaths.single().intervals.singleOrNull())), 40.0, msg = "back where it was")
        assertTrue(ed.redo())
        assertEquals(after, DocumentFormat.save(ed.doc), "and the same one step forward again")
    }

    @Test
    fun twoOpeningsOnOneLegStayOrderedWhenOneIsDraggedPastTheOther() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 3.0))
        ed.finishPath()
        ed.activeScalar = ed.doc.newParameter("w", 10.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(25.0, 0.0)) // 20..30
        ed.click(Vec2(65.0, 0.0)) // 60..70
        ed.setTool(Tools.SELECT)
        assertEquals(listOf(0.0 to 20.0, 30.0 to 60.0, 70.0 to 100.0), facePieces(ed, 5.0))

        val first = tp(ed).intervals.first()
        val nodesBefore = ed.doc.cx.nodesCreated
        val elementsBefore = ed.doc.elements.toList()
        val footprintBefore = tp(ed).footprint
        val regionBefore = Evaluator().region(tp(ed).footprint.ref as RegionRef)

        // drag the *first* opening's leading jamb past the second one
        ed.drag(Vec2(20.0, 4.0), Vec2(80.0, 4.0))

        assertClose(pos(first), 80.0)
        assertEquals(listOf(0.0 to 60.0, 70.0 to 80.0, 90.0 to 100.0), facePieces(ed, 5.0), "the plan re-sorted itself")
        assertEquals(nodesBefore, ed.doc.cx.nodesCreated, "dragging one opening past another builds nothing")
        assertEquals(elementsBefore, ed.doc.elements)
        assertTrue(footprintBefore === tp(ed).footprint)
        assertEquals(regionBefore, Evaluator().region(tp(ed).footprint.ref as RegionRef), "the footprint never noticed")
        // the selection still addresses the interval it grabbed, not whichever is now first in the drawing
        assertTrue(assertNotNull(ed.selectedJamb).interval === first)
    }

    // ---- a ring's legs, and a carrier that has since been edited ----

    @Test
    fun aClosedRingsLegTakesAJambDragAfterTheCarrierMoved() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 3.0))
        ed.click(Vec2(58.0, 40.0))
        ed.click(Vec2(2.0, 40.0))
        ed.click(Vec2(0.0, 0.0)) // closes the loop and finishes
        ed.activeScalar = ed.doc.newParameter("w", 20.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(30.0, 0.0)) // a door in the bottom wall: 20..40 on leg 0
        ed.setTool(Tools.SELECT)
        val i = iv(ed)
        assertClose(pos(i), 20.0)

        // stretch the carrier first: the top-right corner out to x=90, which lengthens leg 0
        ed.drag(Vec2(60.0, 40.0), Vec2(90.0, 40.0))
        assertClose(pos(i), 20.0, msg = "an opening is leg-relative, so editing the carrier leaves it where it was")

        // now slide the door along the (now 90-long) leg — its jamb is still exactly where it is drawn
        ed.drag(Vec2(20.0, 4.0), Vec2(65.0, 4.0))
        assertClose(pos(i), 65.0)
        assertClose(width(i), 20.0)
        assertTrue(ed.doc.planOf(tp(ed), Evaluator())!!.isNotEmpty())

        // …and the clamp knows the leg is longer now
        ed.drag(Vec2(65.0, 4.0), Vec2(200.0, 4.0))
        assertClose(pos(i), 70.0, msg = "90 long minus the 20 wide opening")
    }

    /** A jamb on a *second* leg is addressed by exactly the same pick, in that leg's own direction. */
    @Test
    fun anOpeningOnASecondLegSlidesAlongThatLeg() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(50.0, 4.0)) // +X to (50,0)
        ed.click(Vec2(47.0, 60.0)) // +Y to (50,60)
        ed.finishPath()
        ed.activeScalar = ed.doc.newParameter("w", 10.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(50.0, 30.0)) // on the vertical leg: 25..35 along it
        ed.setTool(Tools.SELECT)
        val i = iv(ed)
        assertEquals(1, i.legIndex)
        assertClose(pos(i), 25.0)

        // the leg runs +Y from (50,0), so its jamb crosses at y=25 between x=45 and x=55
        ed.drag(Vec2(54.0, 25.0), Vec2(54.0, 45.0))
        assertClose(pos(i), 45.0, msg = "the drag is projected onto *this* leg's direction")
        assertClose(width(i), 10.0)
    }

    // ---- inside a placed group (OP-16): a distance along a leg is what a rigid frame leaves alone ----

    @Test
    fun aJambOfAWallInAPlacedTurnedGroupDragsInWorldSpace() {
        val ed = wallWithOpening()
        val i = iv(ed)
        ed.marqueeAll()
        val g = assertNotNull(ed.groupSelection("wall"), "got: ${ed.statusHint}")
        assertTrue(ed.placeGroup(g), "got: ${ed.statusHint}")
        assertClose(pos(i), 40.0, msg = "placing is world-invariant, values included")

        // turn the frame a quarter turn about the members' bbox centre (50,0): the carrier now runs +Y
        // from (50,-50) to (50,50), so the opening's leading jamb crosses at y=-10
        ed.click(Vec2(20.0, 0.0))
        assertTrue(ed.writeSelectionField(2, 90.0), "the frame's angle field")

        ed.drag(Vec2(54.0, -10.0), Vec2(54.0, 10.0))
        assertClose(pos(i), 60.0, msg = "a distance along a leg needs no inverse frame map — it is what a rigid map preserves")
        assertClose(width(i), 20.0)
        assertTrue(ed.statusHint.contains("not group wall"), "the frame it outranked is named: ${ed.statusHint}")

        // and the drawing followed, in world coordinates
        val jambs = ed.doc.jambsOf(tp(ed), Evaluator())
        assertTrue(jambs.any { (it.seg.a - Vec2(45.0, 10.0)).length() < 1e-6 || (it.seg.b - Vec2(45.0, 10.0)).length() < 1e-6 }, "got: ${jambs.map { it.seg }}")
    }

    // ---- and the solid follows (OP-21's 3D half, by way of OP-22) ----

    @Test
    fun theCutSolidFollowsAJambDrag() {
        val ed = wallWithOpening()
        val i = iv(ed)
        ed.activeScalar = ed.doc.newParameter("h", 2000.0.mm)
        val solid = assertNotNull(ed.doc.extrudeSolid(tp(ed).footprint, ed.doc.scalars.first { it.name == "h" }.ref))
        val cut = assertNotNull(ed.doc.cutOpenings(solid))
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.SOLID })

        fun mesh() = (Evaluator().valueOf(cut.ref) as SolidValue).solid.mesh

        // 100 x 10 x 2000 of wall, less a 20-wide opening through it (the box runs sill 0 to head 2100,
        // so the solid's own 2000 height is what bounds the cut)
        assertClose(Geom3.volume(mesh()), 100.0 * 10.0 * 2000.0 - 20.0 * 10.0 * 2000.0, tol = 1e-3)
        assertManifold(mesh(), "the wall with its opening")

        // sliding the opening moves the cut and cannot change its volume
        ed.drag(Vec2(40.0, 4.0), Vec2(10.0, 4.0))
        assertClose(pos(i), 10.0)
        assertClose(Geom3.volume(mesh()), 1_600_000.0, tol = 1e-3, msg = "a slide moves material, it does not remove any")
        assertTrue(
            mesh().vertices.any { kotlin.math.abs(it.x - 10.0) < 1e-6 } && mesh().vertices.any { kotlin.math.abs(it.x - 30.0) < 1e-6 },
            "the cut's reveals are at the opening's new position",
        )
        assertManifold(mesh(), "the wall after the slide")

        // widening it removes more: the trailing jamb from 30 to 50 makes the opening 40 wide
        ed.drag(Vec2(30.0, 4.0), Vec2(50.0, 4.0))
        assertClose(width(i), 40.0)
        assertClose(Geom3.volume(mesh()), 100.0 * 10.0 * 2000.0 - 40.0 * 10.0 * 2000.0, tol = 1e-3)
        assertClose(Geom3.volume(mesh()), 1_200_000.0, tol = 1e-3, msg = "400,000 mm3 more taken out")
        assertManifold(mesh(), "the widened opening")
    }

    /**
     * A width parameter **shared** between two openings is how they are made the same size by construction
     * (OP-21), so a jamb drag resizes both — invisibly if the other one is off screen, hence out loud.
     */
    @Test
    fun draggingAJambOfASharedWidthResizesBothAndSaysSo() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 3.0))
        ed.finishPath()
        ed.activeScalar = ed.doc.newParameter("w", 10.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(25.0, 0.0))
        ed.click(Vec2(65.0, 0.0))
        ed.setTool(Tools.SELECT)
        val (a, b) = tp(ed).intervals
        assertTrue(a.width.node === b.width.node, "one parameter, two openings")

        ed.drag(Vec2(30.0, 4.0), Vec2(45.0, 4.0))
        assertClose(width(a), 25.0)
        assertClose(width(b), 25.0, msg = "sharing a node *is* equality — no constraint asserted it")
        assertTrue(ed.statusHint.contains("shared with 1 other opening"), "got: ${ed.statusHint}")
        assertEquals(listOf(0.0 to 20.0, 45.0 to 60.0, 85.0 to 100.0), facePieces(ed, 5.0))
    }

    /**
     * A **wired** parameter (OP-7) is driven by construction, so the drag reports itself inert instead of
     * quietly absorbing the gesture — the same answer the panel's disabled row gives.
     */
    @Test
    fun aWiredWidthReportsItselfDrivenRatherThanMovingNothing() {
        val ed = wallWithOpening()
        val i = iv(ed)
        val master = ed.doc.newParameter("std", 20.0.mm)
        assertTrue(ed.doc.wireParameter(ed.doc.scalars.first { it.name == "w" }, master))

        ed.drag(Vec2(60.0, 4.0), Vec2(80.0, 4.0))
        assertClose(width(i), 20.0, msg = "the width follows its master, so the drag wrote nothing")
        assertTrue(ed.statusHint.contains("wired to another parameter"), "got: ${ed.statusHint}")
        assertTrue(!ed.selectionFields()[1].writable, "and the panel row says the same")
        assertTrue(ed.selectionFields()[0].writable, "while the position is still free")
    }

    /** Delete has no route to an opening (a recorded cut), so it says so instead of doing nothing. */
    @Test
    fun deleteSaysWhyItCannotReachASelectedOpening() {
        val ed = wallWithOpening()
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(40.0, 4.0))
        assertNotNull(ed.selectedJamb)
        assertTrue(!ed.deleteSelection())
        assertTrue(ed.statusHint.contains("no element of its own"), "got: ${ed.statusHint}")
        assertEquals(1, tp(ed).intervals.size, "and it removed nothing")
    }

    /** A carrier vertex still wins the grab where it meets a jamb: it is the more specific thing there. */
    @Test
    fun theCarrierIsStillReachableAroundItsOpenings() {
        val ed = wallWithOpening()
        // the carrier leg runs along y=0 — pressing *along* the wall between the jambs grabs the leg, since
        // it is nearer there than either jamb
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(50.0, 0.5)))
        assertTrue(ed.selectedJamb == null, "along the wall the leg is nearer: ${ed.selectionLabel()}")
        assertEquals("leg", ed.selectionLabel().substringBefore(" "), "got: ${ed.selectionLabel()}")
        ed.pointerUp(ed.camera.worldToScreen(Vec2(50.0, 0.5)))

        // and a vertex outranks everything meeting at it
        ed.pointerDown(ed.camera.worldToScreen(Vec2(0.0, 0.0)))
        assertTrue(ed.selectedJamb == null)
        assertEquals("corner", ed.selectionLabel().substringBefore(" "), "got: ${ed.selectionLabel()}")
        ed.pointerUp(ed.camera.worldToScreen(Vec2(0.0, 0.0)))
    }
}
