package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.OrthoCornerHandle
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Where a thing sits along its host is an absolute quantity, not a share of the host (OP-20).**
 *
 * Reported on this drawing: a closed rectangle with a T-branch that starts on the top wall and ends on the
 * right wall. Dragging the rectangle's *bottom* wall down by 20 took the branch's horizontal leg down with
 * it, from y=17.25 to y=-2.75 — a leg the gesture never touched. The junction on the right wall held its
 * position as a distance along that wall's carrier line, whose origin is the wall's bottom corner, and that
 * corner belongs to the bottom wall. So resizing a wall dragged every T attached to it.
 *
 * As the user put it: *"it should be transparent to which corner a segment-attached point is anchored."*
 * That is now the rule for every route that puts a position on a curve — see `Document.riderOn`.
 */
class JunctionAnchorTest {
    /** The reported drawing, verbatim. */
    private val tBranch =
        """
constructit 1
orthostart -70.75,41.75 -> e1
orthovertex -70.75,-12.25 -> e2,e3
orthovertex 21.25,-12.25 -> e4,e5
orthovertex 21.25,41.75 -> e6,e7
orthoclose -> e8
orthostart -29,41.75 -> e9
attachortho e9 e8
orthovertex -29,17.25 -> e10,e11
orthovertex 21.25,17.25 -> e12,e13
attachortho e12 e7
""".trimStart()

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    /** Vertex [v] of path [path], in world coordinates. */
    private fun at(
        ed: Editor,
        path: Int,
        v: Int,
    ): Vec2 =
        ed.doc.orthoPaths[path].vertices[v].let {
            ((Evaluator().eval(it.ref.node) as EvalResult.Ok).value as PointValue).p
        }

    /** The drawing survives being written, read and written again — byte for byte (OP-5). */
    private fun assertRoundTrips(
        ed: Editor,
        what: String,
    ) {
        val saved = DocumentFormat.save(ed.doc)
        val again = Editor()
        again.replaceDocument(DocumentFormat.load(saved))
        assertEquals(saved, DocumentFormat.save(again.doc), "the file must replay to itself after $what")
    }

    private fun field(
        el: Element,
        label: String,
    ) = el.handle!!.fields().first { it.label == label }

    /**
     * The reported gesture: drag the bottom wall down by 20. Both walls that own it move; the branch does
     * not, because its position along the right wall is that wall's *world y*, not a distance from a corner.
     */
    @Test
    fun resizingAWallLeavesEveryBranchOnItWhereItStands() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(tBranch))
        ed.drag(Vec2(-25.0, -12.25), Vec2(-25.0, -32.25))

        assertClose(at(ed, 0, 1).y, -32.25, 1e-9, "the bottom wall moved")
        assertClose(at(ed, 0, 2).y, -32.25, 1e-9, "along its whole length")
        assertClose(at(ed, 0, 0).y, 41.75, 1e-9, "and the top wall stayed")
        assertClose(at(ed, 1, 1).y, 17.25, 1e-9, "the branch's horizontal leg must not follow a wall it never met")
        assertClose(at(ed, 1, 2).y, 17.25, 1e-9, "at either end")
        assertClose(at(ed, 1, 0).x, -29.0, 1e-9, "and nothing about it moved sideways either")
        assertClose(at(ed, 1, 2).x, 21.25, 1e-9, "its end still rides the right wall")
        assertRoundTrips(ed, "dragging the bottom wall")
    }

    /**
     * The same rule for the *first* attach, whose host is the top wall: the wall's carrier line takes its
     * origin from a corner the left wall owns, so moving the left wall would have slid the branch along the
     * top wall. Then the right wall, which owns the top wall's other corner.
     */
    @Test
    fun movingTheWallThatOwnsTheHostsCornerLeavesTheBranchWhereItStands() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(tBranch))
        ed.drag(Vec2(-70.75, 0.0), Vec2(-90.75, 0.0))
        assertClose(at(ed, 0, 0).x, -90.75, 1e-9, "the left wall moved")
        assertClose(at(ed, 1, 0).x, -29.0, 1e-9, "the branch stays where it stands on the top wall")
        assertClose(at(ed, 1, 1).x, -29.0, 1e-9, "with its vertical leg still vertical")
        assertRoundTrips(ed, "dragging the left wall")

        ed.drag(Vec2(21.25, 0.0), Vec2(41.25, 0.0))
        assertClose(at(ed, 0, 3).x, 41.25, 1e-9, "the right wall moved")
        assertClose(at(ed, 1, 0).x, -29.0, 1e-9, "the branch still stands where it did on the top wall")
        assertClose(at(ed, 1, 2).x, 41.25, 1e-9, "while its far end rides the wall it is attached to")
        assertClose(at(ed, 1, 2).y, 17.25, 1e-9, "at the height it has always had")
        assertRoundTrips(ed, "dragging the right wall")
    }

    /**
     * Absolute anchoring removes no freedom (OP-20): the degree of freedom is still the junction's, it is
     * simply expressed as a coordinate. So the T slides along both walls, by drag and by typed number
     * (OP-13), exactly as before.
     */
    @Test
    fun theBranchStillSlidesAlongBothWallsItMeets() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(tBranch))

        ed.drag(Vec2(0.0, 17.25), Vec2(0.0, 5.0)) // the horizontal leg, along the right wall
        assertClose(at(ed, 1, 1).y, 5.0, 1e-9, "the horizontal leg slides down")
        assertClose(at(ed, 1, 2).y, 5.0, 1e-9, "both of its ends together")
        assertClose(at(ed, 1, 2).x, 21.25, 1e-9, "still on the right wall")

        ed.drag(Vec2(-29.0, 30.0), Vec2(-44.0, 30.0)) // the vertical leg, along the top wall
        assertClose(at(ed, 1, 0).x, -44.0, 1e-9, "the vertical leg slides along the top wall")
        assertClose(at(ed, 1, 1).x, -44.0, 1e-9, "carrying the corner below it")
        assertClose(at(ed, 1, 0).y, 41.75, 1e-9, "and stays on the wall")
        assertRoundTrips(ed, "sliding the branch")

        // typing reaches exactly as far as dragging: both of these coordinates are *driven*, and both are
        // still settable through the junction that owns them
        val leg = ed.doc.orthoPaths[1].legs[1]
        val y = field(leg, "y")
        assertTrue(y.writable, "a driven coordinate is not a read-only one")
        y.write((-3.0).mm)
        assertClose(at(ed, 1, 2).y, -3.0, 1e-9, "typed y places the junction on the right wall")

        val start = ed.doc.elementFor(ed.doc.orthoPaths[1].vertices[0].ref)!!
        val x = field(start, "x")
        assertTrue(x.writable)
        x.write(12.0.mm)
        assertClose(at(ed, 1, 0).x, 12.0, 1e-9, "typed x places the junction on the top wall")
        assertClose(at(ed, 1, 0).y, 41.75, 1e-9, "which does not leave the wall")
        assertRoundTrips(ed, "typing into a driven coordinate")
    }

    /**
     * The same drawing **drawn live**, click for click, rather than replayed from the file: the two routes
     * build the same construction, so the branch is anchored the same way and moves the same way.
     */
    @Test
    fun theDrawingBehavesTheSameWayWhenItIsDrawnLive() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(-70.75, 41.75))
        ed.click(Vec2(-70.75, -12.25))
        ed.click(Vec2(21.25, -12.25))
        ed.click(Vec2(21.25, 41.75))
        ed.click(Vec2(-70.75, 41.75)) // close
        ed.finishPath()
        ed.click(Vec2(-29.0, 41.75)) // the branch starts on the top wall
        ed.click(Vec2(-29.0, 17.25))
        ed.click(Vec2(21.25, 17.25)) // and ends on the right wall, which finishes the run
        assertEquals(2, ed.doc.orthoPaths.size)

        ed.drag(Vec2(-25.0, -12.25), Vec2(-25.0, -32.25)) // the reported gesture
        assertClose(at(ed, 0, 1).y, -32.25, 1e-9, "the bottom wall moved")
        assertClose(at(ed, 1, 1).y, 17.25, 1e-9, "and the branch stayed, as on the loaded file")

        ed.drag(Vec2(-29.0, 30.0), Vec2(-44.0, 30.0)) // its vertical leg slides along the top wall
        assertClose(at(ed, 1, 0).x, -44.0, 1e-9)
        assertClose(at(ed, 1, 1).x, -44.0, 1e-9)

        // a *vertical* gesture on that vertical leg asks for a freedom it does not have: the junction it
        // hangs from rides a horizontal wall, so there is nothing to write, and nothing moves
        val before = (0..2).map { at(ed, 1, it) }
        ed.drag(Vec2(-44.0, 30.0), Vec2(-44.0, 45.0))
        assertEquals(before, (0..2).map { at(ed, 1, it) }, "an inert gesture must move nothing at all")
        assertRoundTrips(ed, "the live-drawn T")
    }

    /** Undo puts every one of those drags back, and redo puts them forward again. */
    @Test
    fun undoAndRedoRestoreTheAnchoredPositions() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(tBranch))
        ed.drag(Vec2(-25.0, -12.25), Vec2(-25.0, -32.25))
        ed.drag(Vec2(0.0, 17.25), Vec2(0.0, 5.0))
        assertClose(at(ed, 1, 1).y, 5.0, 1e-9)

        assertTrue(ed.undo(), "the slide undoes")
        assertClose(at(ed, 1, 1).y, 17.25, 1e-9, "back to where it was")
        assertClose(at(ed, 0, 1).y, -32.25, 1e-9, "the wall drag still stands")
        assertTrue(ed.undo(), "and so does the wall drag")
        assertClose(at(ed, 0, 1).y, -12.25, 1e-9)
        assertClose(at(ed, 1, 1).y, 17.25, 1e-9, "the branch never moved in the first place")
        assertTrue(ed.redo() && ed.redo(), "both redo")
        assertClose(at(ed, 0, 1).y, -32.25, 1e-9)
        assertClose(at(ed, 1, 1).y, 5.0, 1e-9)
    }

    /**
     * A *determined* meeting point (`bindCornerToDeterminedMeeting`) stores no parameter at all: it is where
     * the axis line through the coordinate the corner no longer owns crosses the host's carrier line. That is
     * already absolute — this pins it, since a relative form here would be the same defect one route over.
     */
    @Test
    fun aDeterminedMeetingPointIsAbsoluteToo() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(-63.5, 13.75))
        ed.click(Vec2(-63.5, 73.0))
        ed.click(Vec2(53.5, 73.0))
        ed.click(Vec2(53.5, 13.75))
        ed.click(Vec2(-63.5, 13.75)) // close the room
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(15.75, 73.0)) // start on the top wall: a junction
        ed.click(Vec2(15.75, 13.75)) // reach the bottom wall: one coordinate left, so a determined meeting
        assertEquals(2, ed.doc.orthoPaths.size)
        assertClose(at(ed, 1, 1).y, 13.75, 1e-9)

        // stretch the bottom wall from the corner its carrier line takes its origin from
        ed.drag(Vec2(-63.5, 40.0), Vec2(-100.0, 40.0))
        assertClose(at(ed, 0, 0).x, -100.0, 1e-9, "the left wall moved")
        assertClose(at(ed, 1, 0).x, 15.75, 1e-9, "the run keeps its place on the top wall")
        assertClose(at(ed, 1, 1).x, 15.75, 1e-9, "and stays vertical")
        assertClose(at(ed, 1, 1).y, 13.75, 1e-9, "its determined end stays on the bottom wall, where it was")
        assertRoundTrips(ed, "stretching the host of a determined meeting")
    }

    /**
     * The same rule on the *free point* routes — drag-to-attach and the point-on-line tool. Both used to
     * store a distance from the host line's origin, so stretching the wall from the far end slid them.
     */
    @Test
    fun anAttachedPointDoesNotSlideWhenItsHostIsStretched() {
        for (route in listOf("attach", "tool")) {
            val ed = Editor()
            ed.setTool(Tools.ORTHO_PATH)
            ed.click(Vec2(0.0, 0.0))
            ed.click(Vec2(100.0, 1.0)) // one horizontal wall, y=0, from x=0 to x=100
            ed.finishPath()
            if (route == "attach") {
                ed.setTool(Tools.POINT)
                ed.click(Vec2(40.0, 12.0))
                ed.drag(Vec2(40.0, 12.0), Vec2(40.0, 0.0)) // the magnet attaches it to the leg
            } else {
                ed.setTool(Tools.POINT_ON_LINE)
                ed.click(Vec2(40.0, 0.0))
            }
            val rider = ed.doc.elements.last { it.kind == ElementKind.ON_CURVE && it.handle !is OrthoCornerHandle }

            fun pos() = ((Evaluator().eval(rider.ref.node) as EvalResult.Ok).value as PointValue).p
            assertClose(pos().x, 40.0, 1e-6, "$route: it starts where it was put")

            // drag the wall's *start* corner along the wall — the anchor the old parameter was measured from
            ed.drag(Vec2(0.0, 0.0), Vec2(-50.0, 0.0))
            assertClose(pos().x, 40.0, 1e-6, "$route: stretching the host must not carry the point along")
            assertClose(pos().y, 0.0, 1e-6, "$route: and it is still on the host")

            // it is still the point's own freedom: dragging it slides it along the wall as ever
            ed.drag(Vec2(40.0, 0.0), Vec2(70.0, 0.0))
            assertClose(pos().x, 70.0, 1e-6, "$route: the rider still slides")
            assertRoundTrips(ed, "$route onto a wall leg")
        }
    }

    /**
     * A **slanted** host has no world coordinate to store, so a rider on it keeps a distance *along* the
     * line — but measured from an anchor of the line itself, so stretching the host from either end (which
     * does not move the line) leaves the rider alone. Turning the host is the honest limit: no parameter
     * along a curve survives the curve being turned, and DESIGN.md records that.
     */
    @Test
    fun aRiderOnASlantedHostSurvivesTheHostBeingStretched() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))

        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(-60.0, 50.0))
        ed.click(Vec2(50.0, 50.4)) // the leg runs on until it meets the segment, at (50,50)
        ed.finishPath()
        assertEquals(1, ed.doc.junctions.size, "meeting the segment made a junction")
        assertClose(at(ed, 0, 1).x, 50.0, 1e-6)
        assertClose(at(ed, 0, 1).y, 50.0, 1e-6)

        // pull the segment's start back along its own direction: the line is unchanged, so the junction is
        ed.drag(Vec2(0.0, 0.0), Vec2(-40.0, -40.0))
        assertClose(at(ed, 0, 1).x, 50.0, 1e-6, "the junction rides the line, not the endpoint")
        assertClose(at(ed, 0, 1).y, 50.0, 1e-6)

        // and the junction still slides along the slanted host, which is the freedom it owns
        ed.drag(Vec2(0.0, 50.0), Vec2(0.0, 20.0))
        assertClose(at(ed, 0, 1).y, 20.0, 1e-6, "the leg lands under the cursor")
        assertClose(at(ed, 0, 1).x, 20.0, 1e-6, "and the junction is still on the segment")

        // the file replays to the same *drawing* — and, on a slanted host, only to within floating point:
        // solving for a parameter along a diagonal and restating the position it produces is a round trip
        // through an irrational direction, so the last digit of the literal moves. An axis-aligned host has
        // no such round trip, which is why the drawings above are byte-stable.
        val saved = DocumentFormat.save(ed.doc)
        val again = Editor()
        again.replaceDocument(DocumentFormat.load(saved))
        assertClose(at(again, 0, 1).x, 20.0, 1e-6, "reloaded onto the same point of the segment")
        assertClose(at(again, 0, 1).y, 20.0, 1e-6)
    }
}
