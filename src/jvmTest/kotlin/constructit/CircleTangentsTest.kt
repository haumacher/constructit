package constructit

import constructit.core.CircleValue
import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.CircleRef
import constructit.dsl.resultOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.PreviewShape
import constructit.editor.Tools
import constructit.geom.Circle
import constructit.geom.FilletMath
import constructit.geom.Line
import constructit.geom.Vec2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Circle from three tangents** (the LLL case of Apollonius' problem) — the tool, and the two properties
 * that make it a *construction* rather than a solve.
 *
 * *Tangency by construction*: the centre is the crossing of one bisector per line pair, so it is equidistant
 * from all three lines by composition, and the radius is the distance to the first one because the circle is
 * built through the foot of the perpendicular there. The assertion is therefore the tangency itself, in mm,
 * before **and after** dragging a line — nothing is re-solved, so nothing can drift.
 *
 * *The choice is stored, not re-scored* (OP-1/OP-18): three lines admit four tangent circles, the final click
 * picks one, and the two bisector branches it resolves to ride the step's `signs=`. The regression this
 * inherits from the fillet is the one at the end: move a line so that the stored click now sits nearer a
 * *different* candidate, save, reload — and the circle must still be the one that was chosen.
 */
class CircleTangentsTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.hover(world: Vec2) = pointerMove(camera.worldToScreen(world))

    private fun Editor.segmentAt(
        a: Vec2,
        b: Vec2,
    ) {
        setTool(Tools.SEGMENT)
        click(a)
        click(b)
    }

    /**
     * A 30-40-50 triangle with its legs on the axes: the base along y = 0, the upright along x = 0 and the
     * hypotenuse across them. Its incircle is r = (a + b − c)/2 = 10 at (10, 10) — a known answer, which is
     * what makes this a probe of the construction rather than of itself.
     */
    private fun triangle(): Editor {
        val ed = Editor()
        ed.segmentAt(Vec2(-10.0, 0.0), Vec2(50.0, 0.0)) // the base, y = 0
        ed.segmentAt(Vec2(0.0, -10.0), Vec2(0.0, 60.0)) // the upright, x = 0
        ed.segmentAt(Vec2(30.0, 0.0), Vec2(0.0, 40.0)) // the hypotenuse
        return ed
    }

    /** Pick the three legs (well clear of one another) and click near the circle wanted. */
    private fun Editor.threeTangents(near: Vec2) {
        setTool(Tools.CIRCLE_LLL)
        click(Vec2(40.0, 0.0)) // the base, past the triangle
        click(Vec2(0.0, 50.0)) // the upright, above it
        click(Vec2(15.0, 20.0)) // the hypotenuse, at its middle
        click(near)
    }

    private fun circleOf(
        doc: Document,
        ev: Evaluator = Evaluator(),
    ): Circle? {
        val el = doc.elements.lastOrNull { it.kind == ElementKind.CIRCLE } ?: return null
        return ((ev.resultOf(el.ref as CircleRef) as? EvalResult.Ok)?.value as? CircleValue)?.circle
    }

    /** The three carrier lines of the scene, as values — what tangency is measured against. */
    private fun lines(): List<Line> =
        listOf(
            Line(Vec2(0.0, 0.0), Vec2(1.0, 0.0)),
            Line(Vec2(0.0, 0.0), Vec2(0.0, 1.0)),
            Line(Vec2(30.0, 0.0), (Vec2(0.0, 40.0) - Vec2(30.0, 0.0)).normalized()),
        )

    private fun assertTangent(
        c: Circle,
        ls: List<Line> = lines(),
        tol: Double = 1e-9,
    ) {
        for ((i, l) in ls.withIndex()) {
            val d = FilletMath.distanceTo(l, c.center)
            assertTrue(abs(d - c.radius) < tol, "line $i: distance $d should equal the radius ${c.radius}")
        }
    }

    // ---- the construction ----

    /** The inscribed circle: tangent to all three lines, and the triangle's known incircle. */
    @Test
    fun theInscribedCircleIsTangentToAllThreeLines() {
        val ed = triangle()
        ed.threeTangents(Vec2(10.0, 10.0)) // inside the triangle: the incircle
        val c = circleOf(ed.doc)
        assertNotNull(c, "the tool should have built a circle")
        assertTangent(c)
        assertClose(c.center.x, 10.0)
        assertClose(c.center.y, 10.0)
        assertClose(c.radius, 10.0)
    }

    /**
     * A click near an **excircle** builds that one instead — the four candidates are the incircle and the
     * three excircles, and which one is the click's to say.
     */
    @Test
    fun aClickNearAnExcircleBuildsTheExcircle() {
        val ed = triangle()
        // The excircle **opposite the right angle** lies beyond the hypotenuse, on the bisector from the
        // origin. For the 30-40-50 triangle (s = 60, area = 600) its radius is area/(s − c) = 600/10 = 60, so
        // it touches both axes at 60 and its centre is (60, 60) — a second known answer.
        ed.threeTangents(Vec2(30.0, 60.0))
        val c = circleOf(ed.doc)
        assertNotNull(c)
        assertTangent(c)
        assertClose(c.center.x, 60.0)
        assertClose(c.center.y, 60.0)
        assertClose(c.radius, 60.0)
    }

    /** All four candidates exist, are distinct, and every one of them is tangent to all three lines. */
    @Test
    fun thereAreFourCandidatesAndEveryOneIsTangent() {
        val ls = lines()
        val all = FilletMath.tangentCircles(ls[0], ls[1], ls[2])
        assertEquals(4, all.size, "a triangle admits the incircle and three excircles")
        assertEquals(4, all.map { it.second.center }.distinct().size, "and they are four different circles")
        assertEquals(4, all.map { it.first }.distinct().size, "each keyed by its own pair of bisector branches")
        for ((_, c) in all) assertTangent(c)
    }

    /** Parallel legs admit nothing, and the tool **says so** instead of leaving three picks that did nothing. */
    @Test
    fun parallelLegsAreRefusedOutLoud() {
        val ed = Editor()
        ed.segmentAt(Vec2(-40.0, 0.0), Vec2(40.0, 0.0))
        ed.segmentAt(Vec2(-40.0, 20.0), Vec2(40.0, 20.0)) // parallel to the first
        ed.segmentAt(Vec2(0.0, -30.0), Vec2(0.0, 40.0))
        val steps = ed.doc.journal.size
        ed.setTool(Tools.CIRCLE_LLL)
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(30.0, 20.0))
        ed.click(Vec2(0.0, -20.0))
        ed.click(Vec2(10.0, 10.0))
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.CIRCLE }, "nothing is tangent to all three")
        assertEquals(steps, ed.doc.journal.size, "a build with no effect records no step")
        assertTrue(ed.statusHint.contains("parallel"), "the refusal should name its reason: ${ed.statusHint}")
    }

    // ---- parametric: the tangencies survive an edit of the lines ----

    /**
     * **Tangency is by construction, so it survives a drag.** One leg's endpoint is moved — which turns the
     * whole triangle — and all three tangencies still hold exactly, with nothing re-solved and no step added.
     */
    @Test
    fun theCircleStaysTangentWhenALegIsDragged() {
        val ed = triangle()
        ed.threeTangents(Vec2(10.0, 10.0))
        val nodes = ed.doc.cx.nodesCreated

        // drag the hypotenuse's upper end from (0, 40) to (0, 70): a taller triangle (past the upright's own
        // end, so the drag joins nothing — this probe is about recompute, not about welding)
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(0.0, 40.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(0.0, 70.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(0.0, 70.0)))

        val moved = lines().dropLast(1) + Line(Vec2(30.0, 0.0), (Vec2(0.0, 70.0) - Vec2(30.0, 0.0)).normalized())
        val c = circleOf(ed.doc)
        assertNotNull(c)
        assertTangent(c, moved)
        assertEquals(nodes, ed.doc.cx.nodesCreated, "a drag recomputes the circle; it never rebuilds it")
    }

    // ---- persistence: the choice is restated, never re-scored ----

    @Test
    fun itRoundTripsByteForByte() {
        val ed = triangle()
        ed.threeTangents(Vec2(30.0, 60.0))
        val saved = DocumentFormat.save(ed.doc)
        assertTrue(saved.contains("tool circle3tan"), saved)
        assertTrue(saved.contains("signs="), "the two bisector branches are the stored choice (OP-1): $saved")
        val reloaded = DocumentFormat.load(saved)
        assertEquals(saved, DocumentFormat.save(reloaded), "save -> load -> save is byte-equal")
        val before = circleOf(ed.doc)!!
        val after = circleOf(reloaded)!!
        assertClose(after.center.x, before.center.x)
        assertClose(after.center.y, before.center.y)
        assertClose(after.radius, before.radius)
        assertTangent(after)
    }

    /**
     * **The stored signs hold when a line has moved past the click** — the fillet's regression, one tool
     * along (OP-18: "a choice is not state, so it is not re-read from the geometry").
     *
     * An excircle is chosen by a click well outside the triangle. Then the hypotenuse is dragged so far that
     * the *incircle* is now the candidate nearest that click: a load that re-scored would hand back the
     * incircle. The reloaded circle must still be the excircle — i.e. the same one the drawing shows before
     * the round trip.
     */
    @Test
    fun aReloadKeepsTheChosenCircleAfterALineMoved() {
        val click = Vec2(45.0, 10.0)
        val ed = triangle()
        ed.threeTangents(click) // nearest that click: the excircle beyond the hypotenuse, r = 60
        val chosen = circleOf(ed.doc)!!
        assertClose(chosen.radius, 60.0)

        // drag the hypotenuse's upper end far out: the triangle grows, and the click is now nearest the
        // *inscribed* circle instead
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(0.0, 40.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(0.0, 130.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(0.0, 130.0)))
        val nowShown = circleOf(ed.doc)!!
        val moved = lines().dropLast(1) + Line(Vec2(30.0, 0.0), (Vec2(0.0, 130.0) - Vec2(30.0, 0.0)).normalized())
        assertTangent(nowShown, moved)

        // the probe only means something if re-scoring *would* now give a different circle — so check that
        val rescored = FilletMath.nearestTangentCircle(moved[0], moved[1], moved[2], click)!!.second
        assertTrue(
            (rescored.center - nowShown.center).length() > 1.0,
            "the probe needs the moved geometry to prefer another candidate; re-scoring still gives the same one",
        )

        val reloaded = DocumentFormat.load(DocumentFormat.save(ed.doc))
        val after = circleOf(reloaded)!!
        assertClose(after.center.x, nowShown.center.x, msg = "the reload must keep the chosen circle, not re-decide it")
        assertClose(after.center.y, nowShown.center.y)
        assertClose(after.radius, nowShown.radius)
        assertTangent(after, moved)
    }

    // ---- the preview (task A's mechanism, on this tool) ----

    /**
     * After the three lines, the candidate **nearest the cursor** is drawn live — and the click builds exactly
     * that one, which is what makes the choice visible before it is committed.
     */
    @Test
    fun thePreviewShowsTheCandidateUnderTheCursorAndTheClickBuildsIt() {
        val ed = triangle()
        ed.setTool(Tools.CIRCLE_LLL)
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(0.0, 50.0))
        ed.click(Vec2(15.0, 20.0))

        ed.hover(Vec2(10.0, 10.0))
        val inside = ed.previewShapes.filterIsInstance<PreviewShape.Circ>().single().circle
        assertClose(inside.radius, 10.0, msg = "inside the triangle the incircle is nearest")
        assertTangent(inside)

        ed.hover(Vec2(30.0, 60.0))
        val outside = ed.previewShapes.filterIsInstance<PreviewShape.Circ>().single().circle
        assertClose(outside.radius, 60.0, msg = "out beyond the hypotenuse it is that excircle")

        ed.click(Vec2(30.0, 60.0))
        val built = circleOf(ed.doc)!!
        assertClose(built.center.x, outside.center.x)
        assertClose(built.center.y, outside.center.y)
        assertClose(built.radius, outside.radius, msg = "the circle built is the circle previewed")
    }
}
