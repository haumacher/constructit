package constructit

import constructit.core.ArcValue
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.core.SegmentValue
import constructit.dsl.circle
import constructit.dsl.scalar
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The everyday completions: rectangle, regular polygon, chamfer, rounded rectangle and point-from-
 * coordinates — plus the two input-model extensions they needed (an *ordered list* of scalar inputs and
 * a structural count).
 *
 * Each shape is asserted to hold *by construction*: what must stay true (a rectangle stays rectangular,
 * a polygon stays regular) is asserted **after moving an input**, since that is the only way to tell a
 * construction from four segments that merely happen to line up.
 */
class ToolCompletionsTest {
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

    private fun Editor.selectAt(world: Vec2) {
        setTool(Tools.SELECT)
        click(world)
    }

    private fun segments(doc: Document): List<constructit.geom.Segment> {
        val ev = Evaluator()
        return doc.elements.filter { it.kind == ElementKind.SEGMENT }.mapNotNull { (ev.valueOf(it.ref) as? SegmentValue)?.seg }
    }

    private fun arcs(doc: Document): List<constructit.geom.Arc> {
        val ev = Evaluator()
        return doc.elements.filter { it.kind == ElementKind.ARC }.mapNotNull { (ev.valueOf(it.ref) as? ArcValue)?.arc }
    }

    /** Every corner of the closed chain of segments, deduplicated and sorted — the shape's fingerprint. */
    private fun corners(doc: Document): List<Vec2> {
        val out = ArrayList<Vec2>()
        for (s in segments(doc)) {
            for (p in listOf(s.a, s.b)) if (out.none { (it - p).length() < 1e-6 }) out.add(p)
        }
        return out.sortedWith(compareBy({ it.x }, { it.y }))
    }

    private fun assertCorners(
        doc: Document,
        expected: List<Vec2>,
    ) {
        val got = corners(doc)
        assertEquals(expected.size, got.size, "corner count; got $got")
        expected.sortedWith(compareBy({ it.x }, { it.y })).forEachIndexed { i, e ->
            assertClose(got[i].x, e.x, msg = "corner $i x of $got")
            assertClose(got[i].y, e.y, msg = "corner $i y of $got")
        }
    }

    private fun assertRoundTrips(ed: Editor): Document {
        val once = DocumentFormat.save(ed.doc)
        val reloaded = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(reloaded), "save -> load -> save must be identical")
        assertEquals(ed.doc.elements.map { it.kind }, reloaded.elements.map { it.kind }, "same element kinds")
        return reloaded
    }

    // ---- Rectangle ----

    @Test
    fun aRectangleStaysARectangleWhenEitherDiagonalCornerMoves() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 20.0))

        assertEquals(4, ed.doc.elements.count { it.kind == ElementKind.SEGMENT }, "four sides")
        // two clicks, and what they produce is a **closed ortho path** (GitHub issue #4): four corners of its
        // own, no free points beside them, and the axis-aligned legs are what keep it a rectangle
        val path = ed.doc.orthoPaths.single()
        assertTrue(path.closed, "the path is closed")
        assertEquals(4, path.vertices.size, "four corners")
        assertEquals(4, path.legs.size, "four legs")
        assertEquals(0, ed.doc.freePoints.size, "a path's corners are its own, so no free points are left over")
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.DERIVED_POINT })
        assertCorners(ed.doc, listOf(Vec2(0.0, 0.0), Vec2(40.0, 0.0), Vec2(40.0, 20.0), Vec2(0.0, 20.0)))

        // drag one diagonal corner: the two derived corners must follow it, on one coordinate each
        ed.drag(Vec2(0.0, 0.0), Vec2(-10.0, -6.0))
        assertCorners(ed.doc, listOf(Vec2(-10.0, -6.0), Vec2(40.0, -6.0), Vec2(40.0, 20.0), Vec2(-10.0, 20.0)))

        // and the other one, to show neither corner is privileged
        ed.drag(Vec2(40.0, 20.0), Vec2(60.0, 50.0))
        assertCorners(ed.doc, listOf(Vec2(-10.0, -6.0), Vec2(60.0, -6.0), Vec2(60.0, 50.0), Vec2(-10.0, 50.0)))

        // every side is axis-parallel — the invariant that cannot be violated here (no shear exists)
        for (s in segments(ed.doc)) {
            val horizontal = abs(s.a.y - s.b.y) < 1e-9
            val vertical = abs(s.a.x - s.b.x) < 1e-9
            assertTrue(horizontal || vertical, "side $s is neither horizontal nor vertical")
        }
    }

    /**
     * The whole reason the rectangle is a path now (GitHub issue #4): a **side** drags across itself, and
     * either side's length is a number one can type. Both come from the path machinery unchanged — this test
     * only asserts that the rectangle reaches it.
     */
    @Test
    fun aRectanglesSidesDragAndItsWidthAndHeightAreTyped() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 20.0))

        // drag the top side down: only that side moves, and the rectangle is still a rectangle
        ed.drag(Vec2(20.0, 20.0), Vec2(20.0, 14.0))
        assertCorners(ed.doc, listOf(Vec2(0.0, 0.0), Vec2(40.0, 0.0), Vec2(40.0, 14.0), Vec2(0.0, 14.0)))

        // and type the two lengths, each on its own leg — the width on a horizontal side, the height on a
        // vertical one, which is exactly what "set the width and height precisely" asked for
        ed.selectAt(Vec2(20.0, 0.0))
        val width = ed.selectionFields().indexOfFirst { it.label == "length (move end)" }
        assertTrue(width >= 0, "a leg reports its length; got ${ed.selectionFields().map { it.label }}")
        assertTrue(ed.writeSelectionField(width, 55.0))
        // the *right* side, drawn upward, so its "move end" end is the top one
        ed.selectAt(Vec2(55.0, 7.0))
        val height = ed.selectionFields().indexOfFirst { it.label == "length (move end)" }
        assertTrue(height >= 0)
        assertTrue(ed.writeSelectionField(height, 25.0))
        assertCorners(ed.doc, listOf(Vec2(0.0, 0.0), Vec2(55.0, 0.0), Vec2(55.0, 25.0), Vec2(0.0, 25.0)))
    }

    /** A rectangle bounds an area, so it extrudes with one pick exactly as it always did (OP-14/OP-17). */
    @Test
    fun aRectangleStillBoundsAnAreaForTheSeam() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 20.0))
        val side = ed.doc.orthoPaths.single().legs.first()
        assertEquals(4, ed.doc.boundaryPiecesOf(side)?.size, "the closed path's legs are its boundary")
        assertTrue(ed.doc.areaPickFilter(Evaluator())(side), "and an area slot takes it")
    }

    @Test
    fun typingACornersCoordinateReshapesTheRectangle() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 20.0))

        ed.selectAt(Vec2(40.0, 20.0))
        val fields = ed.selectionFields()
        val x = fields.indexOfFirst { it.label == "x" }
        val y = fields.indexOfFirst { it.label == "y" }
        assertTrue(x >= 0 && y >= 0, "a clicked corner is a free point with x/y fields; got ${fields.map { it.label }}")
        assertTrue(ed.writeSelectionField(x, 80.0))
        assertTrue(ed.writeSelectionField(y, 30.0))
        assertCorners(ed.doc, listOf(Vec2(0.0, 0.0), Vec2(80.0, 0.0), Vec2(80.0, 30.0), Vec2(0.0, 30.0)))
    }

    @Test
    fun aRectangleRoundTrips() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(-15.0, -5.0))
        ed.click(Vec2(25.0, 35.0))
        ed.drag(Vec2(-15.0, -5.0), Vec2(-20.0, -10.0)) // a dragged corner is state, restated on save
        val reloaded = assertRoundTrips(ed)
        assertCorners(reloaded, listOf(Vec2(-20.0, -10.0), Vec2(25.0, -10.0), Vec2(25.0, 35.0), Vec2(-20.0, 35.0)))
    }

    // ---- Regular polygon (structural count) ----

    @Test
    fun aHexagonsVerticesAreTheClickedVertexRotatedAboutTheCentre() {
        val ed = Editor()
        ed.count = 6
        ed.setTool(Tools.POLYGON)
        ed.click(Vec2(0.0, 0.0)) // centre
        ed.click(Vec2(30.0, 0.0)) // first vertex

        assertEquals(6, ed.doc.elements.count { it.kind == ElementKind.SEGMENT }, "six sides")
        val got = corners(ed.doc)
        assertEquals(6, got.size)
        val expected =
            (0 until 6).map {
                val a = 2 * kotlin.math.PI * it / 6
                Vec2(30.0 * kotlin.math.cos(a), 30.0 * kotlin.math.sin(a))
            }
        assertCorners(ed.doc, expected)
        // regular: every side of a hexagon equals its circumradius
        for (s in segments(ed.doc)) assertClose((s.b - s.a).length(), 30.0, tol = 1e-9)

        // still regular after moving the vertex — it is a rotation, not six placed points
        ed.drag(Vec2(30.0, 0.0), Vec2(0.0, 50.0))
        for (s in segments(ed.doc)) assertClose((s.b - s.a).length(), 50.0, tol = 1e-6)
    }

    @Test
    fun theStructuralCountIsRecordedAndReplayedExactly() {
        val ed = Editor()
        ed.count = 5
        ed.setTool(Tools.POLYGON)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(20.0, 0.0))
        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("count=5"), "the count travels with the tool step; got:\n$text")

        // replay must build the same five sides even though the *editor's* count now says something else
        val fresh = Editor()
        fresh.count = 12
        fresh.replaceDocument(DocumentFormat.load(text))
        assertEquals(5, fresh.doc.elements.count { it.kind == ElementKind.SEGMENT })
        assertEquals(text, DocumentFormat.save(fresh.doc))
    }

    @Test
    fun aPolygonBelowThreeSidesBuildsNothing() {
        val ed = Editor()
        ed.count = 2 // clamped up to the tool's minimum of 3 rather than building a degenerate figure
        ed.setTool(Tools.POLYGON)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(20.0, 0.0))
        assertEquals(3, ed.doc.elements.count { it.kind == ElementKind.SEGMENT })
    }

    // ---- Chamfer ----

    @Test
    fun aChamferMeetsBothLegsAtTheChamferDistance() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0)) // leg along +x
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(0.0, 60.0)) // leg along +y
        ed.activeScalar = ed.doc.newParameter("c", 10.0.mm)
        ed.setTool(Tools.CHAMFER)
        ed.click(Vec2(30.0, 0.0)) // on the horizontal leg, on the +x side of the corner
        ed.click(Vec2(0.0, 30.0)) // on the vertical leg, on the +y side

        val bevel = segments(ed.doc).last()
        val ends = listOf(bevel.a, bevel.b).sortedBy { it.x }
        // one end 10 mm up the vertical leg, the other 10 mm along the horizontal one
        assertClose(ends[0].x, 0.0)
        assertClose(ends[0].y, 10.0)
        assertClose(ends[1].x, 10.0)
        assertClose(ends[1].y, 0.0)
        assertClose((bevel.b - bevel.a).length(), hypot(10.0, 10.0))

        // it is a construction: widening the chamfer moves both ends along their legs
        ed.doc.setParameter(ed.doc.scalars.first { it.name == "c" }, 25.0.mm)
        val wider = segments(ed.doc).last()
        val widerEnds = listOf(wider.a, wider.b).sortedBy { it.x }
        assertClose(widerEnds[0].y, 25.0)
        assertClose(widerEnds[1].x, 25.0)
        assertClose((wider.b - wider.a).length(), hypot(25.0, 25.0))
        assertRoundTrips(ed)
    }

    @Test
    fun aChamferSitsInTheQuadrantThatWasClicked() {
        val ed = Editor()
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.click(Vec2(0.0, -60.0))
        ed.click(Vec2(0.0, 60.0))
        ed.activeScalar = ed.doc.newParameter("c", 8.0.mm)
        ed.setTool(Tools.CHAMFER)
        ed.click(Vec2(-30.0, 0.0)) // this time on the -x side
        ed.click(Vec2(0.0, -30.0)) // and the -y side
        val bevel = segments(ed.doc).last()
        for (p in listOf(bevel.a, bevel.b)) {
            assertTrue(p.x <= 1e-9 && p.y <= 1e-9, "the bevel must sit in the clicked quadrant; got $p")
        }
    }

    // ---- Rounded rectangle (the roundedRect macro as a tool) ----

    @Test
    fun aRoundedRectangleSpansTheTwoClicksAndRoundsLive() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("r", 5.0.mm)
        ed.setTool(Tools.ROUNDED_RECT)
        ed.click(Vec2(-30.0, -20.0))
        ed.click(Vec2(30.0, 20.0))

        assertEquals(4, ed.doc.elements.count { it.kind == ElementKind.SEGMENT }, "four straight flanks")
        assertEquals(4, ed.doc.elements.count { it.kind == ElementKind.ARC }, "four corner arcs")
        for (a in arcs(ed.doc)) assertClose(a.radius, 5.0)
        // outline extent = the span of the two clicks; a flank is that span less two radii
        val xs = segments(ed.doc).flatMap { listOf(it.a.x, it.b.x) }
        val ys = segments(ed.doc).flatMap { listOf(it.a.y, it.b.y) }
        assertClose(xs.max() - xs.min(), 60.0)
        assertClose(ys.max() - ys.min(), 40.0)
        val horizontal = segments(ed.doc).first { abs(it.a.y - it.b.y) < 1e-9 }
        assertClose((horizontal.b - horizontal.a).length(), 60.0 - 2 * 5.0)

        // editing the radius re-rounds it *by recompute*: no element and no node is replaced
        val elements = ed.doc.elements.size
        val nodes = ed.doc.cx.nodesCreated
        ed.doc.setParameter(ed.doc.scalars.first { it.name == "r" }, 12.0.mm)
        for (a in arcs(ed.doc)) assertClose(a.radius, 12.0)
        assertEquals(elements, ed.doc.elements.size, "a radius edit must not regenerate the shape")
        assertEquals(nodes, ed.doc.cx.nodesCreated, "a radius edit must recompute, not rebuild (OP-21 rule)")

        // and the clicked corners keep driving the size
        ed.drag(Vec2(30.0, 20.0), Vec2(50.0, 20.0))
        val widened = segments(ed.doc).flatMap { listOf(it.a.x, it.b.x) }
        assertClose(widened.max() - widened.min(), 80.0)
        assertRoundTrips(ed)
    }

    // ---- Point from coordinates: two scalar slots ----

    @Test
    fun aPointFromTwoParametersFollowsBothOfThem() {
        val ed = Editor()
        val px = ed.doc.newParameter("px", 10.0.mm)
        val py = ed.doc.newParameter("py", 20.0.mm)
        ed.setTool(Tools.POINT_XY)
        ed.activeScalar = px // the panel picks are the tool's inputs, in order
        ed.activeScalar = py
        ed.click(Vec2(0.0, 0.0)) // a tool with no geometry slots: the click only says "now"

        val ev = Evaluator()
        val point = ed.doc.elements.single { it.kind == ElementKind.DERIVED_POINT }
        val p = (ev.valueOf(point.ref) as PointValue).p
        assertClose(p.x, 10.0)
        assertClose(p.y, 20.0)

        // editing either parameter moves it — it owns no DOF of its own
        ed.doc.setParameter(px, -40.0.mm)
        ed.doc.setParameter(py, 5.0.mm)
        val moved = (Evaluator().valueOf(point.ref) as PointValue).p
        assertClose(moved.x, -40.0)
        assertClose(moved.y, 5.0)

        val reloaded = assertRoundTrips(ed)
        val back = Evaluator().valueOf(reloaded.elements.single { it.kind == ElementKind.DERIVED_POINT }.ref) as PointValue
        assertClose(back.p.x, -40.0)
        assertClose(back.p.y, 5.0)
    }

    @Test
    fun twoPointsSharingAParameterStayAlignedBecauseTheyShareIt() {
        val ed = Editor()
        val shared = ed.doc.newParameter("x0", 15.0.mm)
        val y1 = ed.doc.newParameter("y1", 0.0.mm)
        val y2 = ed.doc.newParameter("y2", 40.0.mm)
        ed.setTool(Tools.POINT_XY)
        ed.activeScalar = shared
        ed.activeScalar = y1
        ed.click(Vec2(0.0, 0.0))
        ed.activeScalar = shared
        ed.activeScalar = y2
        ed.click(Vec2(0.0, 0.0))

        ed.doc.setParameter(shared, 60.0.mm)
        val ev = Evaluator()
        val xs = ed.doc.elements.filter { it.kind == ElementKind.DERIVED_POINT }.map { (ev.valueOf(it.ref) as PointValue).p.x }
        assertEquals(2, xs.size)
        for (x in xs) assertClose(x, 60.0)
    }

    /**
     * The panel selects a scalar on every click *and* on every focus of its value field, so a re-visit
     * must not count as a second pick — otherwise glancing at x's value while collecting x and y would
     * silently build the point at (x, x).
     */
    @Test
    fun revisitingTheSameParameterIsOnePick() {
        val ed = Editor()
        val px = ed.doc.newParameter("px", 11.0.mm)
        val py = ed.doc.newParameter("py", 22.0.mm)
        ed.setTool(Tools.POINT_XY)
        ed.activeScalar = px
        ed.activeScalar = py
        ed.activeScalar = py // the panel re-selecting what is already active
        ed.activeScalar = py
        ed.click(Vec2(0.0, 0.0))
        val p = Evaluator().valueOf(ed.doc.elements.single { it.kind == ElementKind.DERIVED_POINT }.ref) as PointValue
        assertClose(p.p.x, 11.0)
        assertClose(p.p.y, 22.0)
    }

    @Test
    fun aToolWaitingForItsSecondScalarSaysWhichOne() {
        val ed = Editor()
        ed.setTool(Tools.POINT_XY)
        assertTrue(ed.currentHelp().contains("x, then y"), "got: ${ed.currentHelp()}")
        ed.activeScalar = ed.doc.newParameter("a", 1.0.mm)
        assertTrue(ed.currentHelp().contains("type y"), "the first is picked, so only y is wanted; got: ${ed.currentHelp()}")

        // clicking before both are in place must build nothing, and say so rather than failing silently
        ed.click(Vec2(0.0, 0.0))
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.DERIVED_POINT })
        ed.activeScalar = ed.doc.newParameter("b", 2.0.mm)
        ed.click(Vec2(0.0, 0.0))
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.DERIVED_POINT })
    }

    // ---- typing a scalar: direct distance entry generalized to every scalar slot (OP-13) ----

    /** Type the digits, press Enter: the number *is* a parameter, indistinguishable from a panel one. */
    @Test
    fun aTypedNumberBecomesTheParameterTheToolWanted() {
        val ed = Editor()
        ed.setTool(Tools.CIRCLE_R)
        assertTrue(ed.currentHelp().contains("type radius"), "the tool says a number can be typed; got: ${ed.currentHelp()}")
        "7".forEach { assertTrue(ed.key(it.toString())) }
        assertTrue(ed.statusHint.contains("radius = 7"), "the entry echoes as it is typed; got: ${ed.statusHint}")
        assertTrue(ed.key("Enter"))
        ed.click(Vec2(0.0, 0.0))

        val circle = ed.doc.elements.single { it.kind == ElementKind.CIRCLE }
        assertClose(Evaluator().circle(circle.ref as constructit.dsl.CircleRef).radius, 7.0)
        // named after the slot, editable, and part of the file — nothing marks it as typed
        val entry = ed.doc.scalars.single { it.editable }
        assertEquals("radius", entry.name)
        assertTrue(DocumentFormat.save(ed.doc).contains("param \"radius\" = 7mm"), DocumentFormat.save(ed.doc))
        ed.doc.setParameter(entry, 11.0.mm)
        assertClose(Evaluator().circle(circle.ref as constructit.dsl.CircleRef).radius, 11.0, msg = "a typed value stays live")
        // the next one uniquifies exactly as a panel parameter does
        ed.key("3")
        ed.key("Enter")
        assertEquals(listOf("radius", "radius2"), ed.doc.scalars.filter { it.editable }.map { it.name })
    }

    /**
     * Order-independence: the clicks may come **first**. A tool missing a scalar used to throw its picks
     * away, which made the geometry pay for a value that had not been supplied yet.
     */
    @Test
    fun aToolThatWaitedForItsNumberFinishesWhenItArrives() {
        val ed = Editor()
        ed.setTool(Tools.CIRCLE_R)
        ed.click(Vec2(20.0, 0.0)) // the centre, before any radius exists
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.CIRCLE })
        assertTrue(ed.statusHint.contains("type radius"), "it says what it is waiting for; got: ${ed.statusHint}")
        ed.key("5")
        ed.key("Enter")

        val circle = ed.doc.elements.single { it.kind == ElementKind.CIRCLE }
        assertClose(Evaluator().circle(circle.ref as constructit.dsl.CircleRef).radius, 5.0)
        val c = Evaluator().circle(circle.ref as constructit.dsl.CircleRef).center
        assertClose(c.x, 20.0, msg = "and it is built where the click was, not where the keyboard is")
    }

    /** The same for a *picked* parameter arriving late — one completion path, two ways in. */
    @Test
    fun aToolThatWaitedFinishesWhenAParameterIsPicked() {
        val ed = Editor()
        val r = ed.doc.newParameter("r", 4.0.mm)
        ed.setTool(Tools.CIRCLE_R)
        ed.activeScalar = null // nothing picked yet, as after a fresh load
        ed.click(Vec2(0.0, 0.0))
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.CIRCLE })
        ed.activeScalar = r
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.CIRCLE }, "picking the row finished it")
    }

    /**
     * A slot's **dimension** is what a typed number is read in (OP-7): the same digits mean millimetres
     * for a depth, degrees for an angle and a bare number for a factor — declared by the tool, so nothing
     * has to guess.
     */
    @Test
    fun aTypedNumberIsReadInTheSlotsOwnDimension() {
        val ed = Editor()
        ed.setTool(Tools.ROTATE)
        ed.key("9")
        ed.key("0")
        ed.key("Enter")
        val angle = ed.doc.scalars.single { it.editable }
        assertEquals("angle", angle.name)
        assertClose(Evaluator().scalar(angle.ref).deg, 90.0, msg = "an angle slot reads degrees")

        ed.setTool(Tools.SCALE)
        ed.key("2")
        ed.key("Enter")
        val factor = ed.doc.scalars.last { it.editable }
        assertEquals("factor", factor.name)
        assertEquals(constructit.units.Dimension.NONE, Evaluator().scalar(factor.ref).dim)
        assertClose(Evaluator().scalar(factor.ref).value, 2.0, msg = "a factor is a bare number")
    }

    /**
     * A typed value is half of the operation it was typed for: "7, Enter, click" is ONE undo step that
     * removes the circle *and* the parameter made for it (a panel parameter, created deliberately, keeps
     * its own step). See the pendingTypedParams seal in `Editor.checkpoint`.
     */
    @Test
    fun aTypedScalarUndoesWithTheToolItFed() {
        val ed = Editor()
        ed.setTool(Tools.CIRCLE_R)
        ed.key("7")
        ed.key("Enter")
        ed.click(Vec2(0.0, 0.0))
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.CIRCLE })
        assertEquals(listOf("radius"), ed.doc.scalars.map { it.name })

        assertTrue(ed.undo(), "the typed parameter and its tool are one step")
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.CIRCLE })
        assertTrue(ed.doc.scalars.isEmpty(), "the parameter existed only to feed that tool, so it goes with it")
        assertTrue(ed.redo(), "and both come back together")
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.CIRCLE })
        assertEquals(listOf("radius"), ed.doc.scalars.map { it.name })
    }

    /** A tool with no scalar slot leaves digits alone, so nothing swallows a keystroke it cannot use. */
    @Test
    fun digitsAreIgnoredByAToolThatTakesNoScalar() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        assertTrue(!ed.key("5"), "the segment tool has no scalar to type into")
        assertEquals(0, ed.doc.scalars.size)
    }

    // ---- tool shortcuts: the palette without the round trip ----

    @Test
    fun everyShortcutIsUniqueAndArmsItsOwnTool() {
        val keys = Tools.all.mapNotNull { it.shortcut } + Tools.SELECT_KEY
        assertEquals(keys.size, keys.distinct().size, "two tools would answer the same key: $keys")
        assertTrue(keys.all { it.isUpperCase() }, "keys are stored uppercase so matching is one rule: $keys")

        val ed = Editor()
        for (tool in Tools.all) {
            val k = tool.shortcut ?: continue
            assertTrue(ed.key(k.lowercaseChar().toString()), "$k should be consumed")
            assertEquals(tool.id, ed.toolId, "$k should arm ${tool.id}")
            assertTrue(ed.key(k.toString()), "and its uppercase should too")
            assertEquals(tool.id, ed.toolId)
        }
        assertTrue(ed.key(Tools.SELECT_KEY.toString()))
        assertEquals(Tools.SELECT, ed.toolId, "S is select — the one key that is not a ToolDef's")
        assertTrue(!ed.key("q"), "an unassigned letter is left for the shell")
    }

    /** A single-scalar tool still means exactly "the active parameter", so nothing about them changed. */
    @Test
    fun aSingleScalarToolStillConsumesTheActiveParameter() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("r", 7.0.mm)
        ed.setTool(Tools.CIRCLE_R)
        ed.click(Vec2(0.0, 0.0))
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.CIRCLE })
        assertTrue(DocumentFormat.save(ed.doc).contains("scalar=\"r\""), DocumentFormat.save(ed.doc))
        // a later pick replaces it, as before: the tool takes the *last* one
        ed.activeScalar = ed.doc.newParameter("r2", 3.0.mm)
        ed.click(Vec2(50.0, 0.0))
        assertClose(Evaluator().circle(ed.doc.elements.last { it.kind == ElementKind.CIRCLE }.ref as constructit.dsl.CircleRef).radius, 3.0)
    }
}
