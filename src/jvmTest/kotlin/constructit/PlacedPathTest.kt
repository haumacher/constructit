package constructit

import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.core.RegionValue
import constructit.core.SegmentValue
import constructit.core.SolidValue
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Group
import constructit.editor.OrthoPath
import constructit.editor.PointerButton
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Ortho paths under a group frame** — OP-16's *ortho-path bonus*, and the cut its as-built note used to
 * record ("ortho paths and walls are not captured").
 *
 * An ortho vertex's freedom lives in two scalar coordinate nodes, and a leg is axis-aligned because one
 * endpoint's coordinate is bound to the other's. Capturing such a path leaves **all** of that structure
 * alone and only re-reads the coordinates as the group's *local* ones (`IndirectNode` publishes each
 * vertex through `frameApply`), so:
 *
 * - placing is world-invariant and moves the whole path as one literal write on the frame;
 * - axis-alignment becomes alignment to the **frame's** axes — turn the frame 30° and every leg is still
 *   straight and perpendicular in the group, tilted 30° in the world. That is the rotated project frame;
 * - everything downstream (the wall riding the path, its openings, the solid cut from them) follows,
 *   because it is downstream and nothing was rewired (OP-5).
 */
class PlacedPathTest {
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

    private fun Editor.marqueeAll() {
        pointerDown(camera.worldToScreen(Vec2(-400.0, -400.0)))
        pointerMove(camera.worldToScreen(Vec2(400.0, 400.0)))
        pointerUp(camera.worldToScreen(Vec2(400.0, 400.0)))
    }

    /**
     * An L-shaped open ortho path: (0,0) → (100,0) → (100,60). Bounding box (0,0)..(100,60), so a frame
     * lands at (50,30) and the three vertices are local (-50,-30), (50,-30), (50,30).
     */
    private fun lPath(tool: String = Tools.ORTHO_PATH): Editor {
        val ed = Editor()
        if (tool == Tools.WALL) ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(tool)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 3.0)) // +X: lands at (100,0)
        ed.click(Vec2(97.0, 60.0)) // +Y: lands at (100,60)
        ed.finishPath()
        ed.setTool(Tools.SELECT)
        return ed
    }

    /** [lPath] with every element grouped and placed — the user's blocked workflow, end to end. */
    private fun placedL(tool: String = Tools.ORTHO_PATH): Pair<Editor, Group> {
        val ed = lPath(tool)
        ed.marqueeAll()
        val g = assertNotNull(ed.groupSelection("flat"), "got: ${ed.statusHint}")
        assertTrue(ed.placeGroup(g), "got: ${ed.statusHint}")
        return ed to g
    }

    private fun path(ed: Editor): OrthoPath = ed.doc.orthoPaths.single()

    private fun vertex(
        ed: Editor,
        i: Int,
    ): Vec2 = (Evaluator().valueOf(path(ed).vertices[i].ref) as PointValue).p

    private fun leg(
        ed: Editor,
        i: Int,
    ) = (Evaluator().valueOf(path(ed).legs[i].ref) as SegmentValue).seg

    private fun origin(
        ed: Editor,
        g: Group,
    ): Vec2 = ed.doc.frameValueOf(g)!!.origin

    private fun angleOf(
        ed: Editor,
        g: Group,
    ): Double = ed.doc.frameValueOf(g)!!.angle

    /** Where local point [l] lands under a frame at [o] turned by [deg]. */
    private fun world(
        o: Vec2,
        deg: Double,
        l: Vec2,
    ): Vec2 {
        val a = deg * PI / 180.0
        return Vec2(o.x + l.x * cos(a) - l.y * sin(a), o.y + l.x * sin(a) + l.y * cos(a))
    }

    /** Every element's evaluated geometry, as text — the invariant a refactoring must not change. */
    private fun geometry(ed: Editor): List<String> {
        val ev = Evaluator()
        return ed.doc.elements.map { el ->
            when (val v = ev.valueOf(el.ref)) {
                is PointValue -> "${el.id} ${fmt(v.p)}"
                is SegmentValue -> "${el.id} ${fmt(v.seg.a)}-${fmt(v.seg.b)}"
                is RegionValue -> "${el.id} " + v.region.outer.elements.joinToString("|") { fmt(constructit.geom.GeomMath.startOf(it)) }
                else -> "${el.id} $v"
            }
        }
    }

    private fun fmt(p: Vec2) = "${(p.x * 1e6).toLong()},${(p.y * 1e6).toLong()}"

    /** Select a single element by clicking it twice: once reaches the group, once the member (OP-16). */
    private fun Editor.reach(world: Vec2) {
        click(Vec2(-300.0, -300.0)) // start from nothing selected
        click(world)
        click(world)
    }

    // ---- the report: grouping an ortho path and placing it must simply work ----

    /**
     * The user's exact complaint: "I can group an ortho-path, but I cannot assign a frame to that group:
     * 'it owns no free point, so a frame would have nothing to move'. However one of these points is
     * completely free."
     */
    @Test
    fun anOrthoPathCanBeGroupedAndPlaced() {
        val ed = lPath()
        ed.marqueeAll()
        assertEquals(5, ed.selectionCount, "three vertices and two legs")
        val g = assertNotNull(ed.groupSelection("run"))

        assertTrue(ed.placeGroup(g), "got: ${ed.statusHint}")
        assertFalse(ed.statusHint.contains("owns no degree of freedom"), "got: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("1 path"), "the frame says what it carries: ${ed.statusHint}")
        assertFalse(ed.statusHint.contains("will not follow"), "and nothing is left behind: ${ed.statusHint}")
        assertEquals(1, g.capturedPaths.size)
        assertTrue(g.placed)
    }

    /** The old refusal survives for a group that owns no freedom at all. */
    @Test
    fun aGroupThatCarriesNothingIsStillRefused() {
        val ed = lPath()
        // the midpoint of a leg: derived, owns nothing of its own
        ed.setTool(Tools.MIDPOINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(50.0, 0.0))
        val g = assertNotNull(ed.groupSelection("derived"))
        assertFalse(ed.placeGroup(g))
        assertTrue(ed.statusHint.contains("owns no degree of freedom"), "got: ${ed.statusHint}")
    }

    // ---- the headline: world-invariant, rigid, invertible ----

    @Test
    fun placingLeavesThePathExactlyWhereItWasAndUnplacingInvertsIt() {
        val ed = lPath()
        val before = geometry(ed)
        ed.marqueeAll()
        val g = assertNotNull(ed.groupSelection("flat"))

        assertTrue(ed.placeGroup(g), "got: ${ed.statusHint}")
        assertEquals(before, geometry(ed), "placing must not move anything — it only changes how it is held")
        assertClose(origin(ed, g).x, 50.0, msg = "the frame starts at the bounding-box centre")
        assertClose(origin(ed, g).y, 30.0)
        // the coordinates are now local — the *same* nodes, read in the group's axes
        assertEquals(path(ed).frame, g.frameNode)
        assertClose(localX(ed, 0), -50.0)
        assertClose(localY(ed, 0), -30.0)
        assertClose(localX(ed, 2), 50.0)
        assertClose(localY(ed, 2), 30.0)

        assertTrue(ed.unplaceGroup(g))
        assertEquals(before, geometry(ed), "and unplacing is the exact inverse")
        assertFalse(g.placed)
        assertEquals(null, path(ed).frame)
        assertEquals(0, g.capturedPaths.size)
        assertClose(localX(ed, 0), 0.0, msg = "the coordinates are world coordinates again")
    }

    private fun localX(
        ed: Editor,
        i: Int,
    ): Double = coord(ed, i, 0)

    private fun localY(
        ed: Editor,
        i: Int,
    ): Double = coord(ed, i, 1)

    private fun coord(
        ed: Editor,
        i: Int,
        axis: Int,
    ): Double {
        val c = path(ed).vertices[i].corner
        val node = if (axis == 0) c.xNode else c.yNode
        return ((Evaluator().eval(node) as constructit.core.EvalResult.Ok).value as constructit.core.ScalarValue).q.mm
    }

    @Test
    fun draggingTheFrameMovesTheWholePathRigidly() {
        val (ed, g) = placedL()
        ed.click(Vec2(50.0, 0.0)) // a member: the whole group
        assertEquals(g, ed.selectedFrame())
        ed.drag(Vec2(50.0, 0.0), Vec2(70.0, 25.0)) // the leg's midpoint -> +20,+25

        assertClose(origin(ed, g).x, 70.0)
        assertClose(origin(ed, g).y, 55.0)
        assertClose(vertex(ed, 0).x, 20.0)
        assertClose(vertex(ed, 0).y, 25.0)
        assertClose(vertex(ed, 1).x, 120.0)
        assertClose(vertex(ed, 1).y, 25.0)
        assertClose(vertex(ed, 2).x, 120.0)
        assertClose(vertex(ed, 2).y, 85.0)
        assertClose(leg(ed, 0).a.y, leg(ed, 0).b.y, msg = "still axis-aligned: one write moved the frame, not the legs")
    }

    /**
     * The same rule on a *path* member (OP-16's as-built drag-subject note): a leg of a group **nobody
     * selected** drags perpendicular in the group, exactly as it would ungrouped, and the frame stays.
     * This is the shape the report actually had — a wall under a project frame.
     */
    @Test
    fun draggingALegOfAnUnselectedGroupMovesTheLegAndLeavesTheFrame() {
        val (ed, g) = placedL()
        ed.click(Vec2(-300.0, -300.0)) // nothing selected: the group is invisible from here
        assertEquals(0, ed.selectionCount)
        val mid = (leg(ed, 0).a + leg(ed, 0).b) * 0.5

        ed.drag(mid, mid + Vec2(0.0, 20.0))

        assertClose(leg(ed, 0).a.y, 20.0, msg = "the leg moved perpendicular, as an ungrouped one would")
        assertClose(leg(ed, 0).b.y, 20.0, msg = "…and stayed axis-aligned")
        assertClose(vertex(ed, 2).y, 60.0, msg = "the far corner did not follow")
        assertClose(origin(ed, g).x, 50.0, msg = "the frame did not move")
        assertClose(origin(ed, g).y, 30.0)
        assertEquals(1, ed.selectionCount, "the drag leaves the leg it moved selected")
    }

    // ---- the rotated project frame ----

    @Test
    fun turningTheFrameTurnsThePathAndEveryLegStaysStraightInTheGroup() {
        val (ed, g) = placedL()
        ed.click(Vec2(50.0, 0.0)) // the group, so the panel addresses its frame
        assertEquals(listOf("x", "y", "angle"), ed.selectionFields().map { it.label })
        assertTrue(ed.writeSelectionField(2, 30.0))
        assertClose(angleOf(ed, g), 30.0 * PI / 180.0)

        val o = Vec2(50.0, 30.0)
        for ((i, l) in listOf(Vec2(-50.0, -30.0), Vec2(50.0, -30.0), Vec2(50.0, 30.0)).withIndex()) {
            val w = world(o, 30.0, l)
            assertClose(vertex(ed, i).x, w.x, msg = "vertex $i x")
            assertClose(vertex(ed, i).y, w.y, msg = "vertex $i y")
        }
        // in the world the legs are turned; in the group they are still one horizontal and one vertical
        val a = leg(ed, 0).b - leg(ed, 0).a
        val b = leg(ed, 1).b - leg(ed, 1).a
        assertClose(a.dot(b), 0.0, tol = 1e-9, msg = "perpendicular in the group, hence in the world")
        assertClose(kotlin.math.atan2(a.y, a.x), 30.0 * PI / 180.0, msg = "leg 0 runs along the frame's x axis")
        assertClose(a.length(), 100.0, msg = "and rigidly: lengths are untouched")
        assertClose(b.length(), 60.0)
        assertClose(localY(ed, 0), localY(ed, 1), msg = "leg 0 is horizontal *in local coordinates*")
        assertClose(localX(ed, 1), localX(ed, 2), msg = "leg 1 is vertical in local coordinates")
    }

    // ---- editing inside a placed, rotated group ----

    @Test
    fun aCornerDragInsideARotatedGroupLandsUnderTheCursorAndKeepsTheLegsFrameAligned() {
        val (ed, g) = placedL()
        ed.click(Vec2(50.0, 0.0))
        assertTrue(ed.writeSelectionField(2, 30.0))
        val corner = vertex(ed, 2) // the free end
        ed.reach(corner)
        assertEquals(1, ed.selectionCount)
        assertEquals(listOf("x", "y", "leg length"), ed.selectionFields().map { it.label })

        val to = corner + Vec2(15.0, -8.0)
        ed.drag(corner, to)
        assertClose(vertex(ed, 2).x, to.x, tol = 1e-9, msg = "it lands under the cursor through the rotation")
        assertClose(vertex(ed, 2).y, to.y, tol = 1e-9)
        // the leg into it is still along the frame's y axis, and the corner before it moved with it
        val b = leg(ed, 1).b - leg(ed, 1).a
        assertClose(kotlin.math.atan2(b.y, b.x), 120.0 * PI / 180.0, tol = 1e-9, msg = "still the frame's y axis")
        val a = leg(ed, 0).b - leg(ed, 0).a
        assertClose(a.dot(b), 0.0, tol = 1e-9)
        assertClose(origin(ed, g).x, 50.0, msg = "the frame itself did not move")
    }

    /** Typed fields are the same write as the drag (OP-13) — in **world** numbers, through the rotation. */
    @Test
    fun theCornersTypedFieldsReadAndWriteWorldCoordinatesThroughTheRotation() {
        val (ed, _) = placedL()
        ed.click(Vec2(50.0, 0.0))
        assertTrue(ed.writeSelectionField(2, 30.0))
        val corner = vertex(ed, 2)
        ed.reach(corner)

        assertClose(ed.selectionFields()[0].read(Evaluator())!!.mm, corner.x, msg = "x reads the world position")
        assertClose(ed.selectionFields()[1].read(Evaluator())!!.mm, corner.y)
        assertTrue(ed.writeSelectionField(0, 40.0), "and it is writable — the path owns both coordinates")
        assertClose(vertex(ed, 2).x, 40.0, tol = 1e-9, msg = "the world x is exactly what was typed")
        assertClose(vertex(ed, 2).y, corner.y, tol = 1e-9, msg = "and the world y is untouched")
        // still frame-aligned: the write went through the inverse into both local masters
        val a = leg(ed, 0).b - leg(ed, 0).a
        val b = leg(ed, 1).b - leg(ed, 1).a
        assertClose(a.dot(b), 0.0, tol = 1e-9)
    }

    @Test
    fun aLegDragInsideARotatedGroupMovesItPerpendicularInTheGroup() {
        val (ed, _) = placedL()
        ed.click(Vec2(50.0, 0.0))
        assertTrue(ed.writeSelectionField(2, 30.0))
        val mid = (leg(ed, 0).a + leg(ed, 0).b) * 0.5
        ed.reach(mid)
        assertEquals(1, ed.selectionCount)
        assertEquals(listOf("y in group", "length (move end)", "length (move start)"), ed.selectionFields().map { it.label })

        val before = leg(ed, 0)
        // push it across itself: in the group that is a change of one local coordinate only
        val across = Vec2(-sin(30.0 * PI / 180.0), cos(30.0 * PI / 180.0)) * 20.0
        ed.drag(mid, mid + across)
        val after = leg(ed, 0)
        assertClose((after.a - before.a).length(), 20.0, tol = 1e-9, msg = "both ends moved by the drag, across the leg")
        assertClose((after.b - before.b).length(), 20.0, tol = 1e-9)
        assertClose((after.b - after.a).angle(), (before.b - before.a).angle(), tol = 1e-9, msg = "and it stayed frame-aligned")
        assertClose(localY(ed, 0), localY(ed, 1), tol = 1e-9)
        assertClose(vertex(ed, 2).x, world(Vec2(50.0, 30.0), 30.0, Vec2(50.0, 30.0)).x, tol = 1e-9, msg = "the far corner stayed put")
    }

    // ---- break and join keep working, in local space (OP-19) ----

    @Test
    fun aLegOfAPlacedPathBreaksAndJoinsAgainInTheGroupsAxes() {
        val (ed, _) = placedL()
        ed.click(Vec2(50.0, 0.0))
        assertTrue(ed.writeSelectionField(2, 30.0))
        val onLeg = (leg(ed, 0).a + leg(ed, 0).b) * 0.5

        ed.setTool(Tools.BREAK_LEG)
        ed.click(onLeg)
        assertTrue(ed.statusHint.contains("broken"), "got: ${ed.statusHint}")
        assertEquals(5, path(ed).vertices.size)
        assertEquals(4, path(ed).legCount)
        // the break did not change the shape: the jog is zero-length and the whole run is still straight
        assertClose((leg(ed, 1).b - leg(ed, 1).a).length(), 0.0, tol = 1e-9)
        assertClose(localY(ed, 0), localY(ed, 3), tol = 1e-9, msg = "both halves still at the same local offset")

        // open the jog by dragging the second half across the run, in the group's axes. Dragged straight
        // from where it is drawn: the legs a break created are new elements, hence not group members, so
        // the press reaches the leg rather than the frame (and a *click* on a leg beside a flat jog would
        // join it away again — the release commits, as it does for any path).
        ed.setTool(Tools.SELECT)
        val half = (leg(ed, 2).a + leg(ed, 2).b) * 0.5
        val across = Vec2(-sin(30.0 * PI / 180.0), cos(30.0 * PI / 180.0)) * 25.0
        ed.drag(half, half + across)
        assertClose((leg(ed, 1).b - leg(ed, 1).a).length(), 25.0, tol = 1e-9, msg = "the jog opened by 25 in the group")
        val jog = leg(ed, 1).b - leg(ed, 1).a
        val run = leg(ed, 0).b - leg(ed, 0).a
        assertClose(jog.dot(run), 0.0, tol = 1e-9, msg = "and it is perpendicular to the run, in the frame's axes")
        assertClose(localY(ed, 2), -5.0, tol = 1e-9, msg = "the jog is a *local* offset: -30 + 25")

        // drag it back: the join fires on release and the run is one leg again
        val back = (leg(ed, 2).a + leg(ed, 2).b) * 0.5
        ed.drag(back, back - across)
        assertEquals(3, path(ed).vertices.size, "the flattened corner is gone: ${ed.statusHint}")
        assertEquals(2, path(ed).legCount)
        assertClose(localY(ed, 0), localY(ed, 1), tol = 1e-9, msg = "one straight run again, in local coordinates")
        assertClose((leg(ed, 0).b - leg(ed, 0).a).length(), 100.0, tol = 1e-9)
        assertClose(
            kotlin.math.atan2((leg(ed, 0).b - leg(ed, 0).a).y, (leg(ed, 0).b - leg(ed, 0).a).x),
            30.0 * PI / 180.0,
            tol = 1e-9,
            msg = "and still turned with the frame",
        )
    }

    /**
     * A break recorded *after* the placement replays after it too — so its positions are restated in world
     * coordinates, where the ones of the path's own drawing steps (which replay *before* the capture) are
     * the pre-capture ones. Both conventions in one script, and the script still reloads byte-identically.
     */
    @Test
    fun aBreakInsideAPlacedGroupReplaysAndTheJogSurvivesReload() {
        val (ed, _) = placedL()
        ed.click(Vec2(50.0, 0.0))
        assertTrue(ed.writeSelectionField(2, 30.0))
        ed.setTool(Tools.BREAK_LEG)
        ed.click((leg(ed, 0).a + leg(ed, 0).b) * 0.5)
        ed.setTool(Tools.SELECT)
        val half = (leg(ed, 2).a + leg(ed, 2).b) * 0.5
        ed.drag(half, half + Vec2(-sin(30.0 * PI / 180.0), cos(30.0 * PI / 180.0)) * 25.0)
        assertEquals(5, path(ed).vertices.size)

        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.indexOf("place \"flat\"") < text.indexOf("orthobreak"), "the break was recorded after the placement")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "got:\n$text")
        assertEquals(geometry(ed), geometry(Editor(DocumentFormat.load(text))), "the turned, broken path reloads exactly")
    }

    // ---- a closed loop, a wall on it, an opening in it, and a solid cut from that ----

    @Test
    fun aClosedLoopIsCapturedAndTurnsWithItsFrame() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 2.0))
        ed.click(Vec2(58.0, 40.0))
        ed.click(Vec2(2.0, 40.0))
        ed.click(Vec2(0.0, 0.0)) // closes the loop and finishes
        ed.setTool(Tools.SELECT)
        ed.marqueeAll()
        val g = assertNotNull(ed.groupSelection("room"))
        val before = geometry(ed)
        assertTrue(ed.placeGroup(g), "got: ${ed.statusHint}")
        assertEquals(before, geometry(ed), "a closed loop is captured world-invariantly too")
        assertTrue(path(ed).closed)

        ed.click(Vec2(30.0, 0.0))
        assertTrue(ed.writeSelectionField(2, 90.0))
        // a quarter turn about (30,20): the loop is still closed, still rectangular, still 60x40
        val o = Vec2(30.0, 20.0)
        assertClose(vertex(ed, 0).x, world(o, 90.0, Vec2(-30.0, -20.0)).x)
        assertClose(vertex(ed, 0).y, world(o, 90.0, Vec2(-30.0, -20.0)).y)
        val closing = leg(ed, 3)
        assertClose((closing.b - vertex(ed, 0)).length(), 0.0, tol = 1e-9, msg = "the closing leg still meets the start")
        assertClose((leg(ed, 0).b - leg(ed, 0).a).length(), 60.0)
        assertClose((leg(ed, 1).b - leg(ed, 1).a).length(), 40.0)
        assertClose((leg(ed, 0).b - leg(ed, 0).a).dot(leg(ed, 1).b - leg(ed, 1).a), 0.0, tol = 1e-9)
    }

    /**
     * A wall on a captured carrier: the footprint reads the carrier's vertices, so it follows for free —
     * and an opening's parameters are leg-relative, so it stays where it was on its (now turned) leg.
     */
    @Test
    fun aWallWithAnOpeningFollowsItsFrameAndTurnsWithIt() {
        val (ed, g) = placedL(Tools.WALL)
        val tp = ed.doc.thickPaths.single()
        assertEquals(1, ed.doc.groups.single().capturedPaths.size)

        // an opening 30 along the first leg, 20 wide
        ed.activeScalar = ed.doc.newParameter("w", 20.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(30.0, 0.0))
        assertEquals(1, tp.intervals.size, "got: ${ed.statusHint}")
        ed.setTool(Tools.SELECT)

        // the footprint's corners and the plan's face pieces *before* the turn — mitres, gap and all. The
        // frame sits at the members' bounding-box centre, which the wall's own faces widen (52.5,27.5).
        val o = origin(ed, g)
        val outerBefore = footprintCorners(ed, tp)
        val planBefore = ed.doc.planOf(tp, Evaluator())!!.flatMap { listOf(it.a, it.b) }
        assertTrue(planBefore.size > 4, "the opening splits the inner face, so the plan has more than four pieces")

        ed.click(Vec2(70.0, 0.0)) // the group
        assertTrue(ed.writeSelectionField(2, 30.0))

        // every corner is exactly where the frame maps it: the footprint reads the carrier's vertices, so
        // capturing the carrier carried the wall — no rule of its own (OP-5)
        val outerAfter = footprintCorners(ed, tp)
        assertEquals(outerBefore.size, outerAfter.size, "the same footprint, turned")
        for (p in outerBefore) {
            val want = world(o, 30.0, p - o)
            assertTrue(outerAfter.any { (it - want).length() < 1e-6 }, "footprint corner $p -> $want missing in $outerAfter")
        }
        // and the plan convention with it: the opening's parameters are leg-relative, so its gap stayed put
        // on the (now turned) leg rather than sliding along it
        val planAfter = ed.doc.planOf(tp, Evaluator())!!.flatMap { listOf(it.a, it.b) }
        assertEquals(planBefore.size, planAfter.size, "the same drawing, turned")
        for (p in planBefore) {
            val want = world(o, 30.0, p - o)
            assertTrue(planAfter.any { (it - want).length() < 1e-6 }, "plan point $p -> $want missing")
        }
    }

    private fun footprintCorners(
        ed: Editor,
        tp: constructit.editor.ThickPath,
    ): List<Vec2> {
        val reg = (Evaluator().valueOf(tp.footprint.ref) as RegionValue).region
        return (reg.outer.elements + reg.holes.flatMap { it.elements }).map { constructit.geom.GeomMath.startOf(it) }
    }

    /** A solid built from the wall *before* the placement is downstream of it, so it follows the frame. */
    @Test
    fun aSolidCutFromAPlacedWallFollowsTheFrame() {
        val ed = lPath(Tools.WALL)
        val tp = ed.doc.thickPaths.single()
        ed.activeScalar = ed.doc.newParameter("w", 20.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(30.0, 0.0))
        assertEquals(1, tp.intervals.size)
        // extrude the footprint, then cut the openings out of it — all built before any group exists
        ed.setTool(Tools.SELECT)
        ed.activeScalar = ed.doc.newParameter("h", 2000.0.mm)
        val solid = assertNotNull(ed.doc.extrudeSolid(tp.footprint, ed.doc.scalars.first { it.name == "h" }.ref))
        val cut = assertNotNull(ed.doc.cutOpenings(solid))

        ed.marqueeAll()
        val g = assertNotNull(ed.groupSelection("wall"), "got: ${ed.statusHint}")
        val volumeBefore = (Evaluator().valueOf(cut.ref) as SolidValue).solid.mesh.let { constructit.geom.Geom3.volume(it) }
        assertTrue(ed.placeGroup(g), "got: ${ed.statusHint}")
        assertClose(
            (Evaluator().valueOf(cut.ref) as SolidValue).solid.mesh.let { constructit.geom.Geom3.volume(it) },
            volumeBefore,
            tol = 1e-3,
            msg = "placing is world-invariant all the way down the chain",
        )

        ed.click(Vec2(70.0, 0.0))
        ed.drag(Vec2(70.0, 0.0), Vec2(90.0, 40.0)) // move the frame by (20,40)
        val moved = (Evaluator().valueOf(cut.ref) as SolidValue).solid
        assertClose(constructit.geom.Geom3.volume(moved.mesh), volumeBefore, tol = 1e-3, msg = "a rigid move, so the volume is the same")
        assertTrue(
            moved.mesh.vertices.any { kotlin.math.abs(it.x - 20.0) < 1e-6 && kotlin.math.abs(it.y - 35.0) < 1e-6 },
            "the solid moved with the frame (its outer corner is at (20,35) now)",
        )
        assertManifold(moved.mesh, "the cut wall")
    }

    // ---- boundary honesty: what the frame cannot carry says so (OP-16) ----

    @Test
    fun aPathWeldedToANonMemberIsNotCapturedAndSaysSo() {
        val ed = lPath() // path A: capturable
        // path B, whose far end is welded onto a free point that will stay outside the group
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, -60.0))
        ed.click(Vec2(60.0, -58.0))
        ed.finishPath()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(140.0, -60.0))
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(60.0, -60.0), Vec2(139.0, -59.0)) // the magnet welds B's end onto it
        assertTrue(ed.statusHint.contains("Joined"), "got: ${ed.statusHint}")

        // membership stated outright, since B's welded end now sits *on* the outside point and a click there
        // could only ever reach one of the two
        val a = ed.doc.orthoPaths[0]
        val b = ed.doc.orthoPaths[1]
        val members = (a.vertices + b.vertices).mapNotNull { ed.doc.elementFor(it.ref) } + a.legs + b.legs
        val g = assertNotNull(ed.doc.createGroup("mixed", members))
        assertTrue(ed.placeGroup(g), "got: ${ed.statusHint}")

        assertEquals(1, g.capturedPaths.size, "path A is captured; B, whose freedom leaves the group, is not")
        assertTrue(g.capturedPaths.single() === a)
        assertTrue(ed.statusHint.contains("will not follow it"), "and that is reported: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains(ed.doc.nameOf(ed.doc.elementFor(b.vertices.first().ref)!!)), "naming B: ${ed.statusHint}")

        // moving the frame proves it: A rides along, B stays hung on the outside point
        val bStart = (Evaluator().valueOf(b.vertices.first().ref) as PointValue).p
        ed.click(Vec2(50.0, 0.0))
        ed.drag(Vec2(50.0, 0.0), Vec2(50.0, 40.0))
        assertClose((Evaluator().valueOf(a.vertices[0].ref) as PointValue).p.y, 40.0, msg = "the captured path followed the frame")
        assertClose(
            (Evaluator().valueOf(b.vertices.first().ref) as PointValue).p.y,
            bStart.y,
            msg = "the uncaptured one did not — the group deforms there, correctly",
        )
    }

    /** A placed path is not extended in place, and its ends do not weld — both refusals are visible. */
    @Test
    fun aPlacedPathIsNotExtendedAndItsEndsDoNotWeld() {
        val (ed, _) = placedL()
        val startVertex = ed.doc.elementFor(path(ed).vertices.first().ref)!!
        assertEquals(null, ed.doc.resumableEnd(startVertex), "no resuming a placed path")

        val placed = path(ed)

        fun at(i: Int) = (Evaluator().valueOf(placed.vertices[i].ref) as PointValue).p

        // drawing from its end starts a NEW run joined there instead of extending it
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        assertTrue(ed.statusHint.contains("not extended in place"), "got: ${ed.statusHint}")
        ed.click(Vec2(0.0, -50.0))
        ed.finishPath()
        assertEquals(2, ed.doc.orthoPaths.size, "a second path, joined to the placed one")
        assertEquals(3, placed.vertices.size, "and the placed path is untouched")

        // the new run *does* follow the placed one: it is driven by that vertex's *world* position, which is
        // the direction that works — a junction reading a world position, not a local one reading a junction
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(50.0, 0.0))
        ed.drag(Vec2(50.0, 0.0), Vec2(50.0, 20.0))
        val branch = ed.doc.orthoPaths[1]
        val start = (Evaluator().valueOf(branch.vertices.first().ref) as PointValue).p
        assertClose(start.y, 20.0, msg = "the branch follows the placed path's vertex")

        // and a placed end cannot be welded outward: its coordinates are local, a junction is world
        ed.setTool(Tools.POINT)
        ed.click(Vec2(160.0, 100.0))
        ed.setTool(Tools.SELECT)
        val end = at(2)
        ed.reach(end)
        ed.drag(end, Vec2(159.0, 99.0))
        assertFalse(ed.statusHint.contains("Joined"), "no weld was offered or made: ${ed.statusHint}")
        assertClose(at(2).x, 159.0, msg = "the corner simply moved there")
    }

    // ---- persistence, undo (OP-18) ----

    @Test
    fun aPlacedPathSurvivesSaveLoadSaveByteIdentically() {
        val (ed, g) = placedL()
        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.contains("place \"flat\" at=50,30 angle=0deg"), "got:\n$once")
        assertTrue(once.contains("orthostart 0,0"), "an unturned placed path restates its world positions: got\n$once")
        val reloaded = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(reloaded), "save -> load -> save must be identical")
        assertEquals(geometry(ed), geometry(Editor(reloaded)), "and the reloaded drawing is the same drawing")
        val g2 = reloaded.groups.single()
        assertTrue(g2.placed)
        assertEquals(1, g2.capturedPaths.size, "replay re-runs the same capture")
        assertEquals(g.captures.size, g2.captures.size)
    }

    @Test
    fun theScriptStaysStableAfterAFrameDragACornerDragAndARotation() {
        val (ed, _) = placedL()
        ed.click(Vec2(50.0, 0.0))
        ed.drag(Vec2(50.0, 0.0), Vec2(75.0, 20.0)) // the frame
        val afterFrame = DocumentFormat.save(ed.doc)
        assertTrue(afterFrame.contains("at=75,50"), "the frame's origin is restated: got\n$afterFrame")
        assertEquals(afterFrame, DocumentFormat.save(DocumentFormat.load(afterFrame)))

        val corner = vertex(ed, 2)
        ed.reach(corner)
        ed.drag(corner, corner + Vec2(10.0, 10.0))
        val afterCorner = DocumentFormat.save(ed.doc)
        assertEquals(afterCorner, DocumentFormat.save(DocumentFormat.load(afterCorner)))
        assertEquals(
            geometry(ed),
            geometry(Editor(DocumentFormat.load(afterCorner))),
            "a corner dragged inside the frame reloads where it is",
        )

        ed.click(Vec2(-300.0, -300.0))
        ed.click(vertex(ed, 0))
        assertTrue(ed.writeSelectionField(2, 30.0), "got: ${ed.statusHint}")
        val turned = DocumentFormat.save(ed.doc)
        assertTrue(Regex("angle=(30|29\\.9+\\d*)deg").containsMatchIn(turned), "got:\n$turned")
        assertTrue(
            turned.contains("orthostart 25,20"),
            "a turned placed path restates the shape the frame then turns — its local coordinates plus the origin: got\n$turned",
        )
        assertEquals(turned, DocumentFormat.save(DocumentFormat.load(turned)), "a turned placed path round-trips too")
        assertEquals(
            geometry(ed),
            geometry(Editor(DocumentFormat.load(turned))),
            "the reloaded path is turned exactly as it was — its steps restate the pre-rotation shape",
        )
    }

    @Test
    fun placingAPathIsOneUndoStep() {
        val ed = lPath()
        ed.marqueeAll()
        val g = assertNotNull(ed.groupSelection("flat"))
        val beforePlace = DocumentFormat.save(ed.doc)
        assertTrue(ed.placeGroup(g))
        val afterPlace = DocumentFormat.save(ed.doc)

        assertTrue(ed.undo())
        assertEquals(beforePlace, DocumentFormat.save(ed.doc), "one undo unplaces it")
        assertFalse(ed.doc.groups.single().placed)
        assertEquals(null, ed.doc.orthoPaths.single().frame, "and the path's coordinates are world ones again")
        assertTrue(ed.redo())
        assertEquals(afterPlace, DocumentFormat.save(ed.doc))
        assertEquals(1, ed.doc.groups.single().capturedPaths.size)
    }

    /** Un-placing a *turned* group un-turns its path — the one thing it cannot keep, so it is reported. */
    @Test
    fun unplacingATurnedGroupUnturnsThePathAndSaysSo() {
        val (ed, g) = placedL()
        ed.click(Vec2(50.0, 0.0))
        assertTrue(ed.writeSelectionField(2, 30.0))
        assertTrue(ed.unplaceGroup(g))
        assertTrue(ed.statusHint.contains("unturned again"), "got: ${ed.statusHint}")
        assertClose(vertex(ed, 0).x, 0.0, msg = "back at the frame's axes, i.e. where it was placed")
        assertClose(vertex(ed, 0).y, 0.0)
        assertClose(leg(ed, 0).a.y, leg(ed, 0).b.y, msg = "and axis-aligned in the world again")
        val text = DocumentFormat.save(ed.doc)
        assertFalse(text.contains("place"), "got:\n$text")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)))
    }

    /** Legs stay pickable where they *appear*, rotation included — hit testing is on evaluated geometry. */
    @Test
    fun aTurnedLegIsPickedWhereItIsDrawn() {
        val (ed, _) = placedL()
        ed.click(Vec2(50.0, 0.0))
        assertTrue(ed.writeSelectionField(2, 30.0))
        val mid = (leg(ed, 0).a + leg(ed, 0).b) * 0.5
        ed.click(Vec2(-300.0, -300.0))
        ed.click(mid)
        ed.click(mid)
        assertEquals(1, ed.selectionCount)
        assertEquals("leg ${ed.doc.nameOf(path(ed).legs[0])}", ed.selectionLabel())
        // and *not* where it used to be before the turn
        ed.click(Vec2(-300.0, -300.0))
        ed.click(Vec2(50.0, 0.0))
        assertTrue(ed.selectionCount == 0 || ed.selectionLabel() != "leg ${ed.doc.nameOf(path(ed).legs[0])}")
    }

    /**
     * Deleting inside a placed group replays the surviving script (OP-18), which is the one place a
     * *pruned* journal and a live capture meet — so the positions each step restates have to stay
     * consistent with a `place` step that may itself be going.
     */
    @Test
    fun deletingAMemberOfAPlacedPathReplaysCleanly() {
        val (ed, _) = placedL()
        ed.reach((leg(ed, 0).a + leg(ed, 0).b) * 0.5)
        assertEquals(1, ed.selectionCount)
        assertTrue(ed.deleteSelection(), "got: ${ed.statusHint}")

        // a path's topology steps chain (OP-18), so dropping the step that drew a leg drops the rest of the
        // run with it; what is left is the start vertex, still captured, under a placement that survives
        val g = ed.doc.groups.single()
        assertTrue(g.placed)
        assertEquals(1, g.capturedPaths.size)
        assertEquals(1, ed.doc.orthoPaths.single().vertices.size)
        assertClose(vertex(ed, 0).x, 0.0, msg = "and it did not move")
        assertClose(vertex(ed, 0).y, 0.0)
        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("place \"flat\" at=50,30"), "got:\n$text")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "the pruned script replays as it stands")
    }

    /**
     * A free point and an ortho path under **one** frame: both are captured, both stay rigid, and the file
     * carries the two conventions its two capture kinds need — a point restates its world position, a path
     * vertex the pre-rotation one — without either disturbing the other.
     */
    @Test
    fun aGroupOfAPointAndAPathCarriesBothRigidly() {
        val ed = lPath()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 60.0))
        ed.setTool(Tools.SELECT)
        ed.marqueeAll()
        val g = assertNotNull(ed.groupSelection("both"))
        val before = geometry(ed)
        assertTrue(ed.placeGroup(g), "got: ${ed.statusHint}")
        assertEquals(before, geometry(ed))
        assertEquals(1, g.captures.size, "the free point")
        assertEquals(1, g.capturedPaths.size, "and the path")
        assertTrue(ed.statusHint.contains("1 point and 1 path"), "got: ${ed.statusHint}")

        val o = origin(ed, g)
        val pointBefore = Vec2(0.0, 60.0)
        ed.click(Vec2(50.0, 0.0))
        assertTrue(ed.writeSelectionField(2, 30.0))
        val point = (Evaluator().valueOf(ed.doc.freePoints.single().ref) as PointValue).p
        val wantPoint = world(o, 30.0, pointBefore - o)
        assertClose(point.x, wantPoint.x, msg = "the point turned about the same origin")
        assertClose(point.y, wantPoint.y)
        assertClose(vertex(ed, 0).x, world(o, 30.0, Vec2(0.0, 0.0) - o).x, msg = "and so did the path")
        assertClose((point - vertex(ed, 0)).length(), 60.0, msg = "rigid: the distance between them is unchanged")

        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "got:\n$text")
        assertEquals(geometry(ed), geometry(Editor(DocumentFormat.load(text))))
    }

    @Test
    fun everyElementOfAPlacedPathStillReportsItsKind() {
        val (ed, _) = placedL()
        assertEquals(3, ed.doc.elements.count { it.kind == ElementKind.ON_CURVE })
        assertTrue(ed.doc.elements.filter { it.kind == ElementKind.ON_CURVE }.all { it.draggable }, "corners still drag")
        assertTrue(ed.doc.elements.filter { it.kind == ElementKind.SEGMENT }.all { it.hasFreeDof }, "legs still drag")
        assertTrue(ed.doc.elements.none { ed.doc.isWelded(it) }, "a framed vertex is not a welded alias")
    }
}
