package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.core.SegmentValue
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.PointerButton
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A placed group carries the freedom its *connections* own** (OP-16 × OP-20, GitHub issue #11).
 *
 * The reported drawing is an outline with two interior runs branched off it — one welded to a corner and
 * attached to the far wall, one attached to that run and to the wall again. Grouping and placing all of it
 * moved the outline and left the interior standing:
 *
 * > "The whole assembly is not moved when moving the group. Some segments stay where they are."
 *
 * The retrofit understood free point sources and whole ortho paths, and an interior run's degrees of
 * freedom are neither: they are the **junction parameters** of the meetings it makes — a world coordinate
 * the wall it rides leaves free, or a distance along that wall's carrier line. Those are anchored to the
 * world, so the frame moved the walls while the meetings stayed put and the runs slid along them to keep
 * their world coordinate: e17 moved (0,0), e16 moved (50,0) and e19 moved (0,30) for a frame drag of
 * (50,30), which is exactly what "some segments stay where they are" looks like.
 *
 * The fix is the conversion OP-16 already applies to a rider, applied to a connection: a junction's
 * parameter is re-anchored to a point of the wall it meets, so the *anchor* moves rather than a coordinate
 * being compensated. DOF-preserving, world-invariant at the moment of capture, and inverted by unplacing.
 */
class PlacedJunctionTest {
    private fun Editor.click(
        world: Vec2,
        additive: Boolean = false,
    ) {
        val s = camera.worldToScreen(world)
        pointerDown(s, PointerButton.PRIMARY, additive)
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

    /**
     * The user's file, verbatim — an outline (e1..e12), a run welded to corner e6 and attached to the
     * closing wall e12 (e13..e15), and a second run attached to *that* run and to e12 again (e16..e20),
     * all grouped and placed.
     */
    private val reported =
        """
        constructit 2
        orthostart -44.654651610640634,-42.891501448519534 -> e1
        orthovertex -44.654651610640634,56.932564229106646 -> e2,e3
        orthovertex 10.006561466646858,56.932564229106646 -> e4,e5
        orthovertex 10.006561466646858,23.881406168687448 -> e6,e7
        orthovertex 88.55852142198333,23.881406168687448 -> e8,e9
        orthovertex 88.55852142198333,-42.891501448519534 -> e10,e11
        orthoclose -> e12
        orthostart 10.006561466646858,23.881406168687448 -> e13
        weldortho e13 e6
        orthovertex 10.006561466646858,-42.891501448519534 -> e14,e15
        attachortho e14 e12
        orthostart 10.006561466646858,-2.647242681581133 -> e16
        attachortho e16 e15
        orthovertex 54.4041523101855,-2.647242681581133 -> e17,e18
        orthovertex 54.4041523101855,-42.891501448519534 -> e19,e20
        attachortho e19 e12
        group "all" els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13,e14,e15,e16,e17,e18,e19,e20
        place "all" at=21.951934905671344,7.020531390293556 angle=0deg
        """.trimIndent() + "\n"

    /** The member the user grabbed: the outline's start corner. */
    private val grab = Vec2(-44.654651610640634, -42.891501448519534)

    /** Every point and every segment end, keyed by the element's name — the whole figure, nothing derived away. */
    private fun figure(ed: Editor): Map<String, List<Vec2>> {
        val ev = Evaluator()
        val out = LinkedHashMap<String, List<Vec2>>()
        for (el in ed.doc.elements) {
            when (val v = (ev.eval(el.ref.node) as? EvalResult.Ok)?.value) {
                is PointValue -> out[ed.doc.nameOf(el)] = listOf(v.p)
                is SegmentValue -> out[ed.doc.nameOf(el)] = listOf(v.seg.a, v.seg.b)
                else -> {}
            }
        }
        return out
    }

    private fun assertMovedBy(
        before: Map<String, List<Vec2>>,
        after: Map<String, List<Vec2>>,
        by: Vec2,
    ) {
        assertEquals(before.keys, after.keys, "the same elements are still there")
        for ((name, was) in before) {
            val now = assertNotNull(after[name])
            for (i in was.indices) {
                assertClose(now[i].x - was[i].x, by.x, msg = "$name[$i] x moved by ${now[i].x - was[i].x}, not ${by.x}")
                assertClose(now[i].y - was[i].y, by.y, msg = "$name[$i] y moved by ${now[i].y - was[i].y}, not ${by.y}")
            }
        }
    }

    // ---- the report ----

    /**
     * The reported gesture: click a member (which selects the placed group), then drag it — the frame drag.
     * **Every** point and every segment end must move by exactly the same vector, interior runs included.
     */
    @Test
    fun theWholeAssemblyFollowsTheFrame() {
        val ed = Editor(DocumentFormat.load(reported))
        val g = ed.doc.groups.single()
        assertTrue(g.placed, "the file's place step replayed")
        assertEquals(2, g.capturedJunctions.size, "both meetings on member walls were re-anchored")

        val before = figure(ed)
        ed.click(grab)
        assertTrue(ed.selectedGroup === g, "one click reaches the group as a whole")
        ed.drag(grab, grab + Vec2(50.0, 30.0))
        assertMovedBy(before, figure(ed), Vec2(50.0, 30.0))
    }

    /** The pinned interior corner from the report, named: e17 used to move (0,0) while the outline moved. */
    @Test
    fun theInteriorCornerNoLongerStandsStill() {
        val ed = Editor(DocumentFormat.load(reported))
        val e17 = assertNotNull(ed.doc.elements.firstOrNull { ed.doc.nameOf(it) == "e17" })
        val was = (Evaluator().valueOf(e17.ref) as PointValue).p
        ed.click(grab)
        ed.drag(grab, grab + Vec2(50.0, 30.0))
        val now = (Evaluator().valueOf(e17.ref) as PointValue).p
        assertClose(now.x - was.x, 50.0, msg = "e17 followed in x")
        assertClose(now.y - was.y, 30.0, msg = "e17 followed in y")
    }

    /** The file is still a pure function of its steps: a frame drag survives `save → load → save`. */
    @Test
    fun theMovedDrawingRoundTrips() {
        val ed = Editor(DocumentFormat.load(reported))
        ed.click(grab)
        ed.drag(grab, grab + Vec2(50.0, 30.0))
        val moved = figure(ed)

        val once = DocumentFormat.save(ed.doc)
        val reloaded = Editor(DocumentFormat.load(once))
        assertEquals(once, DocumentFormat.save(reloaded.doc), "save → load → save is byte-equal")
        assertMovedBy(moved, figure(reloaded), Vec2(0.0, 0.0))
    }

    /** Undo puts the whole assembly back — the frame drag is one step, as a literal edit on one node is. */
    @Test
    fun undoRestoresTheWholeAssembly() {
        val ed = Editor(DocumentFormat.load(reported))
        val before = figure(ed)
        ed.click(grab)
        ed.drag(grab, grab + Vec2(50.0, 30.0))
        assertTrue(ed.undo(), "the drag is one undo step")
        assertMovedBy(before, figure(ed), Vec2(0.0, 0.0))
    }

    /** Placing took one thing and unplacing gives exactly that back — the OP-16 property, junctions included. */
    @Test
    fun unplacingIsTheExactInverse() {
        val ed = Editor(DocumentFormat.load(reported))
        val before = figure(ed)
        val g = ed.doc.groups.single()
        assertTrue(ed.unplaceGroup(g))
        assertMovedBy(before, figure(ed), Vec2(0.0, 0.0))
        assertEquals(0, g.capturedJunctions.size)

        // and it can be placed again, carrying the same freedom
        assertTrue(ed.placeGroup(g), "got: ${ed.statusHint}")
        assertEquals(2, g.capturedJunctions.size)
        assertMovedBy(before, figure(ed), Vec2(0.0, 0.0))
        ed.click(grab)
        ed.drag(grab, grab + Vec2(-20.0, 15.0))
        assertMovedBy(before, figure(ed), Vec2(-20.0, 15.0))
    }

    /**
     * A junction is still a **degree of freedom** after the capture: the branch slides along the wall it
     * meets, and only it moves. Re-anchoring changes what the position is measured from, not how much
     * freedom there is.
     */
    @Test
    fun theBranchStillSlidesAlongItsWall() {
        val ed = Editor(DocumentFormat.load(reported))
        val before = figure(ed)
        // e16 rides the interior wall e15; reach the member alone (click the group, then the member again)
        val at = Vec2(10.006561466646858, -2.647242681581133)
        ed.click(at)
        ed.click(at)
        ed.drag(at, Vec2(10.006561466646858, 5.0))
        val after = figure(ed)
        assertTrue((after["e16"]!![0].y - before["e16"]!![0].y) > 5.0, "the branch slid along its wall")
        assertClose(after["e1"]!![0].x, before["e1"]!![0].x, msg = "and the outline did not move")
        assertClose(after["e10"]!![0].y, before["e10"]!![0].y)
    }

    // ---- the boundary: what a frame cannot turn says so ----

    /**
     * A run that follows the frame through its **connections** keeps its legs aligned to the *world's*
     * axes, not to the group's — so the frame moves it rigidly and cannot turn it. That is refused with
     * words rather than discovered as vanished geometry (OP-16 × OP-20).
     */
    @Test
    fun aFrameHeldByConnectionsRefusesToTurn() {
        val ed = Editor(DocumentFormat.load(reported))
        val g = ed.doc.groups.single()
        val refusal = assertNotNull(ed.doc.turnRefusal(g), "the group holds two connected runs")
        assertTrue(refusal.contains("aligned to the world's axes"), "got: $refusal")

        ed.click(grab)
        val fields = ed.selectionFields()
        val angle = assertNotNull(fields.firstOrNull { it.label == "angle" })
        assertFalse(angle.writable, "the angle field says so up front (OP-13: typing and dragging refuse together)")
        assertFalse(ed.writeSelectionField(fields.indexOf(angle), 30.0), "and the write is refused")
        assertTrue(ed.statusHint.contains("cannot be turned"), "with the reason: ${ed.statusHint}")
        assertClose(ed.doc.frameValueOf(g)!!.angle, 0.0, msg = "nothing turned")
    }

    /**
     * The other side of the same rule: a group whose paths are **captured** turns as it always did, and a
     * drag of a frame placed at 90° still moves every vertex rigidly — axis-sharing intact under the turn.
     */
    @Test
    fun aCapturedPathStaysRigidUnderATurnedFrame() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 2.0)) // +X
        ed.click(Vec2(98.0, 60.0)) // +Y
        ed.click(Vec2(0.0, 58.0)) // -X
        ed.finishPath()
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(-50.0, -50.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(200.0, 200.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(200.0, 200.0)))
        val g = assertNotNull(ed.groupSelection("run"))
        assertTrue(ed.placeGroup(g), "got: ${ed.statusHint}")
        assertEquals(null, ed.doc.turnRefusal(g), "every run is captured, so this frame may turn")

        // turn it a quarter, then move it: the figure must arrive rotated *and* rigid
        val fields = ed.selectionFields()
        val angle = assertNotNull(fields.firstOrNull { it.label == "angle" })
        assertTrue(angle.writable)
        assertTrue(ed.writeSelectionField(fields.indexOf(angle), 90.0))
        assertClose(ed.doc.frameValueOf(g)!!.angle, PI / 2, msg = "the frame is turned")
        val turned = figure(ed)
        // every leg is still straight and perpendicular — in the group, which is now the world turned 90°
        val path = ed.doc.orthoPaths.single()
        for (i in 0 until path.legCount) {
            val seg = (Evaluator().valueOf(path.legs[i].ref) as SegmentValue).seg
            val d = seg.b - seg.a
            assertTrue(
                kotlin.math.abs(d.x) < 1e-9 || kotlin.math.abs(d.y) < 1e-9,
                "leg $i is axis-aligned in the world too, because the turn is a quarter: $d",
            )
        }
        val handleAt = (Evaluator().valueOf(path.vertices[0].ref) as PointValue).p
        ed.click(handleAt)
        ed.drag(handleAt, handleAt + Vec2(25.0, -40.0))
        assertMovedBy(turned, figure(ed), Vec2(25.0, -40.0))
    }

    // ---- the boundary: a run grouped without the walls it meets ----

    /**
     * A **partial** group — the interior run alone, without the outline it hangs on. Its junctions ride
     * walls that are not members, so the frame does not drive them: OP-16's boundary-attachment rule, and
     * it has to be *reported* rather than silently mis-placed.
     */
    @Test
    fun aRunGroupedWithoutItsWallsSaysSo() {
        val ed = Editor(DocumentFormat.load(reported))
        assertTrue(ed.unplaceGroup(ed.doc.groups.single()))
        assertTrue(ed.ungroup(ed.doc.groups.single()))

        val interior = listOf("e16", "e17", "e18", "e19", "e20").map { n -> ed.doc.elements.first { ed.doc.nameOf(it) == n } }
        val g = assertNotNull(ed.doc.createGroup("branch", interior))
        val warnings = ed.doc.placementWarnings(g)
        assertTrue(warnings.isNotEmpty(), "the group cannot move independently, and says so at creation")
        assertTrue(
            warnings.any { it.contains("not in the group") },
            "naming the walls it meets: $warnings",
        )
    }
}
