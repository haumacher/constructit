package constructit

import constructit.core.Evaluator
import constructit.dsl.LineRef
import constructit.dsl.line
import constructit.dsl.point
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Line
import constructit.geom.Vec2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * GitHub #19 — *no tangent can be created at the radius-defining point of a circle*.
 *
 * The report, verbatim: draw a circle from two points, then try *Tangent at point* on the point that gives
 * it its radius — the click is rejected. Same for a circle ∩ line crossing. And the reporter's own third
 * case, which is the design boundary: at a crossing of **two** circles the point does not say which circle
 * the tangent is to.
 *
 * **What was wrong.** The tool's slot asked for a *rider* — a point whose handle is an `OnCircleHandle` —
 * and a rider is only one of the ways a drawing puts a point on a circle. The honest criterion is the
 * construction's: a point lies on a circle because something **built** it there (`Document.circlesThrough`),
 * and the radius point of `circle(centre, through)` does so as unambiguously as any rider. Which is recorded
 * where it is made, the way OP-14's joint registry records a tangency, and never measured off the picture —
 * `|p − c| = r` today is not a promise about tomorrow's parameters.
 *
 * **The ambiguity is a fact about the geometry, so it is answered by a click.** Where the point lies on two
 * circles the tool asks for the circle as well ([constructit.editor.ToolDef.slotsNeeded]) and the pick is the
 * record: `els=` names it, replay takes it verbatim, nothing is scored again (OP-1, OP-18).
 */
class TangentAtPointTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun linesOf(doc: Document): List<Element> = doc.elements.filter { it.kind == ElementKind.LINE }

    @Suppress("UNCHECKED_CAST")
    private fun lineValue(el: Element): Line = Evaluator().line(el.ref as LineRef)

    /** A tangent to the circle at [centre] of radius [r] stands exactly [r] from the centre. */
    private fun assertTangentTo(
        el: Element,
        centre: Vec2,
        r: Double,
        what: String,
    ) {
        val l = lineValue(el)
        assertClose(abs((centre - l.origin).cross(l.dir)), r, 1e-9, "$what stands the radius off the centre")
    }

    /** The drawing of the report: a circle from two points, and nothing else. */
    private fun circleFromTwoPoints(): Editor {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(25.0, 0.0))
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(25.0, 0.0))
        return ed
    }

    // ---- 1. the report, exactly ----

    /** **The radius-defining point carries the tangent**, on one click, as a rider always did. */
    @Test
    fun theRadiusDefiningPointTakesTheTangent() {
        val ed = circleFromTwoPoints()
        val before = linesOf(ed.doc).size
        ed.setTool(Tools.TANGENT_AT)
        ed.click(Vec2(25.0, 0.0))
        val tangent = assertNotNull(linesOf(ed.doc).lastOrNull(), "a tangent was built: ${ed.statusHint}")
        assertEquals(before + 1, linesOf(ed.doc).size, "exactly one: ${ed.statusHint}")
        assertClose(lineValue(tangent).dir.x, 0.0, 1e-9, "the tangent at (25, 0) stands upright")
        assertTangentTo(tangent, Vec2(0.0, 0.0), 25.0, "the tangent")

        // …and it is a construction, not a snapshot: move the radius point and the tangent goes with it
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(25.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(0.0, 40.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(0.0, 40.0)))
        assertClose(lineValue(tangent).dir.y, 0.0, 1e-9, "…and at (0, 40) it lies flat")
        assertTangentTo(tangent, Vec2(0.0, 0.0), 40.0, "the moved tangent")
    }

    /** **A circle ∩ line crossing carries it too** — the report's second case. */
    @Test
    fun aCrossingOfACircleAndALineTakesTheTangent() {
        val ed = circleFromTwoPoints()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-50.0, 10.0))
        ed.click(Vec2(50.0, 10.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-50.0, 10.0))
        ed.click(Vec2(50.0, 10.0))
        ed.setTool(Tools.INTERSECT)
        ed.click(Vec2(0.0, 10.0)) // the line
        ed.click(Vec2(0.0, 25.0)) // the circle
        val crossings = ed.doc.elements.filter { it.kind == ElementKind.DERIVED_POINT }
        assertEquals(2, crossings.size, "a line crosses a circle twice: ${ed.statusHint}")

        val before = linesOf(ed.doc).size
        val at = Evaluator().point(crossings[0].ref as constructit.dsl.PointRef)
        ed.setTool(Tools.TANGENT_AT)
        ed.click(at)
        assertEquals(before + 1, linesOf(ed.doc).size, "the crossing took the tangent: ${ed.statusHint}")
        assertTangentTo(assertNotNull(linesOf(ed.doc).lastOrNull()), Vec2(0.0, 0.0), 25.0, "the tangent at the crossing")
    }

    /** …and so does an **arc's own end**, which is on its carrier circle for the same reason. */
    @Test
    fun anArcsEndTakesTheTangent() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-30.0, 0.0))
        ed.click(Vec2(0.0, 30.0))
        ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.ARC_3)
        ed.click(Vec2(-30.0, 0.0))
        ed.click(Vec2(0.0, 30.0))
        ed.click(Vec2(30.0, 0.0))
        val arc = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.ARC }, "the arc: ${ed.statusHint}")
        ed.setTool(Tools.KEY_POINTS)
        ed.click(Vec2(0.0, 30.0)) // anywhere on the arc — the pick is the arc
        assertTrue(ed.doc.elements.any { it.kind == ElementKind.DERIVED_POINT }, "its key points: ${ed.statusHint}")
        assertTrue(ed.doc.circlesThrough(ed.doc.elements.last { it.kind == ElementKind.DERIVED_POINT }).isNotEmpty())

        val before = linesOf(ed.doc).size
        ed.setTool(Tools.TANGENT_AT)
        ed.click(Vec2(30.0, 0.0)) // the arc's end
        assertEquals(before + 1, linesOf(ed.doc).size, "the arc's end took the tangent: ${ed.statusHint}")
        assertTangentTo(assertNotNull(linesOf(ed.doc).lastOrNull()), Vec2(0.0, 0.0), 30.0, "the tangent at the arc's end")
        assertNotNull(arc)
    }

    // ---- 2. the ambiguity, answered by a click and recorded as one ----

    /** Two circles of radius 25 whose crossings stand at (±…, ±…) — and the intersection points of them. */
    private fun twoCrossingCircles(): Editor {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(25.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(55.0, 0.0))
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(25.0, 0.0))
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(55.0, 0.0))
        ed.setTool(Tools.INTERSECT)
        ed.click(Vec2(0.0, 25.0)) // the first circle, well away from the second
        ed.click(Vec2(55.0, 0.0)) // the second
        return ed
    }

    /**
     * **A crossing of two circles asks which circle** — one more click, and the tangent is to the circle
     * clicked. Clicking the other circle from the same point gives the *other* tangent, which is what makes
     * this a choice rather than a formality.
     */
    @Test
    fun aCrossingOfTwoCirclesTakesTheCircleAsASecondClick() {
        for (which in listOf(Vec2(0.0, 25.0) to Vec2(0.0, 0.0), Vec2(30.0, 25.0) to Vec2(30.0, 0.0))) {
            val (clickCircle, centre) = which
            val ed = twoCrossingCircles()
            val crossing = ed.doc.elements.filter { it.kind == ElementKind.DERIVED_POINT }
            val at = Evaluator().point(crossing[0].ref as constructit.dsl.PointRef)
            assertEquals(2, ed.doc.circlesThrough(crossing[0]).size, "the crossing lies on both circles")

            val before = linesOf(ed.doc).size
            ed.setTool(Tools.TANGENT_AT)
            ed.click(at)
            assertEquals(before, linesOf(ed.doc).size, "nothing is built until the circle is named: ${ed.statusHint}")
            assertTrue(ed.statusHint.contains("1 more"), "…and the tool says it wants one more click: ${ed.statusHint}")
            ed.click(clickCircle)
            assertEquals(before + 1, linesOf(ed.doc).size, "…and then the tangent: ${ed.statusHint}")
            assertTangentTo(assertNotNull(linesOf(ed.doc).lastOrNull()), centre, 25.0, "the tangent to the circle clicked")
        }
    }

    /**
     * **The pick is the record** (OP-18): the step names the circle, the file round-trips byte for byte, and
     * the reloaded drawing has the same line — so nothing is re-scored on replay, however the two circles
     * have moved since.
     */
    @Test
    fun theCircleClickedIsRestatedAndReplayNeverScoresAgain() {
        val ed = twoCrossingCircles()
        val crossing = ed.doc.elements.first { it.kind == ElementKind.DERIVED_POINT }
        val at = Evaluator().point(crossing.ref as constructit.dsl.PointRef)
        ed.setTool(Tools.TANGENT_AT)
        ed.click(at)
        ed.click(Vec2(30.0, 25.0)) // the second circle
        val tangent = assertNotNull(linesOf(ed.doc).lastOrNull(), "the tangent: ${ed.statusHint}")
        val before = lineValue(tangent)

        val once = DocumentFormat.save(ed.doc)
        val step = assertNotNull(once.lines().firstOrNull { it.startsWith("tool tangentat") }, "the step: $once")
        assertEquals(2, step.substringAfter("els=").substringBefore(' ').split(",").size, "it names the point and the circle: $step")
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save ∘ load is a fixed point")

        val reloaded = DocumentFormat.load(once)
        val there = lineValue(linesOf(reloaded).last())
        assertClose(there.dir.x, before.dir.x, 1e-12, "the reloaded tangent is the same line")
        assertClose(there.dir.y, before.dir.y, 1e-12, "the reloaded tangent is the same line")
        assertTangentTo(linesOf(reloaded).last(), Vec2(30.0, 0.0), 25.0, "…and still to the circle that was clicked")
    }

    // ---- 3. the refusal, and what it stopped prescribing ----

    /**
     * **A point on no circle is refused without prescribing a tool nobody needs.** The old help sent every
     * refusal to *Point on circle*, which is one route of five and was not the one the reporter wanted.
     */
    @Test
    fun aPointOnNoCircleIsRefusedAndTheHelpNamesEveryRoute() {
        val ed = circleFromTwoPoints()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(80.0, 80.0))
        val before = ed.doc.elements.size
        ed.setTool(Tools.TANGENT_AT)
        ed.click(Vec2(80.0, 80.0))
        assertEquals(before, ed.doc.elements.size, "nothing was built and nothing placed: ${ed.statusHint}")
        assertTrue(ed.statusHint.startsWith("Tangent at point needs an existing point on circle"), "${ed.statusHint}")
        assertTrue(!ed.statusHint.contains("use Point on circle"), "it no longer prescribes one tool: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("by construction"), "it states the criterion: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("radius point"), "…and names the route the report asked for: ${ed.statusHint}")
    }

    /**
     * …and where the build is reached with such a point anyway — by a replay of a step whose drawing has
     * changed — it refuses **by name** and builds nothing, rather than raising a tangent to a circle the
     * point is not on.
     */
    @Test
    fun theBuildItselfRefusesAPointNoCircleWasBuiltThrough() {
        val ed = circleFromTwoPoints()
        val centre = ed.doc.elements.first { it.kind == ElementKind.POINT }
        val before = linesOf(ed.doc).size
        ed.doc.tangentAtPointOnCircle(centre)
        assertEquals(before, linesOf(ed.doc).size, "the centre is not on its own circle")
        val note = assertNotNull(ed.doc.note, "and it says so")
        assertTrue(note.contains("does not lie on a circle by construction"), note)
        assertTrue(note.contains("radius point"), note)
    }

    /** **A rider is still one click**, which is the case that always worked and must keep working. */
    @Test
    fun aRiderIsStillOneClick() {
        val ed = circleFromTwoPoints()
        ed.setTool(Tools.POINT_ON_CIRCLE)
        ed.click(Vec2(0.0, 25.0))
        val before = linesOf(ed.doc).size
        ed.setTool(Tools.TANGENT_AT)
        ed.click(Vec2(0.0, 25.0))
        assertEquals(before + 1, linesOf(ed.doc).size, "the rider took the tangent: ${ed.statusHint}")
        assertTangentTo(assertNotNull(linesOf(ed.doc).lastOrNull()), Vec2(0.0, 0.0), 25.0, "the rider's tangent")
    }

    // ---- 4. the derived circle: the probe review's composition, one step further out ----

    /**
     * **A mirrored circle's tangent is reachable, and the help names the route it takes.**
     *
     * A mirror copies the *circle*, so the copy's inputs are the original and the axis — it has no radius point
     * of its own to click, and nothing about the incidence registry can invent one. What puts a point on the
     * copy is *Point on circle*, which takes any circle whatever built it, and the tangent then stands there in
     * one click. This is asserted because the alternative reading — "a derived circle's tangent is
     * unreachable" — is what the old refusal's *"use Point on circle"* was really about, and the route has to
     * keep working now that the message no longer prescribes it as the only one.
     */
    @Test
    fun aMirroredCirclesTangentIsReachableThroughARiderOnTheCopy() {
        val ed = Editor()
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(-20.0, 0.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, -50.0))
        ed.click(Vec2(0.0, 50.0))
        ed.setTool(Tools.MIRROR)
        ed.click(Vec2(-40.0, 20.0)) // the circle, on its own outline
        ed.click(Vec2(0.0, 10.0)) // the axis
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.CIRCLE }, "the copy: ${ed.statusHint}")

        // the copy stands at (40,0) with radius 20 and publishes no point of its own — *Point on circle* does
        ed.setTool(Tools.POINT_ON_CIRCLE)
        ed.click(Vec2(20.0, 0.0))
        val rider = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.ON_CURVE }, "a point on the copy: ${ed.statusHint}")
        assertEquals(1, ed.doc.circlesThrough(rider).size, "the rider lies on one circle by construction")

        val before = linesOf(ed.doc).size
        ed.setTool(Tools.TANGENT_AT)
        ed.click(Vec2(20.0, 0.0))
        assertEquals(before + 1, linesOf(ed.doc).size, "one click on the rider: ${ed.statusHint}")
        assertTangentTo(assertNotNull(linesOf(ed.doc).lastOrNull()), Vec2(40.0, 0.0), 20.0, "the copy's tangent")
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "and the drawing survives its file")
    }

    /**
     * **A patterned circle's own radius point takes the tangent, on every copy** — the registry is general over
     * OP-23's orbits for free, and that is not a coincidence: an orbit *re-runs the gesture* per cell, so the
     * same `Document.circle` call states the same incidence for each copy. Nothing here knows about patterns.
     */
    @Test
    fun aPatternedCirclesRadiusPointTakesTheTangentOnEveryCopy() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.count = 3
        ed.setTool(Tools.PATTERN_LINEAR)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        // a circle riding two members: centre on one, radius point on the next — so the gesture orbits
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.CIRCLE }, "the circle and its copy: ${ed.statusHint}")

        // the copy's radius point is the pattern member at (80,0) — and it lies on the copy by construction
        val member = ed.doc.elements.last { it.isPoint }
        assertEquals(1, ed.doc.circlesThrough(member).size, "the copy's radius point knows its circle")
        val before = linesOf(ed.doc).size
        ed.setTool(Tools.TANGENT_AT)
        ed.click(Vec2(80.0, 0.0))
        assertEquals(before + 1, linesOf(ed.doc).size, "one click on the copy's radius point: ${ed.statusHint}")
        assertTangentTo(assertNotNull(linesOf(ed.doc).lastOrNull()), Vec2(40.0, 0.0), 40.0, "the copy's tangent")
    }

    /** **A mirrored arc's ends take it too** — its extracted ends are on its carrier circle by construction. */
    @Test
    fun aMirroredArcsEndsTakeTheTangent() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(-40.0, 20.0))
        ed.click(Vec2(-20.0, 0.0))
        ed.setTool(Tools.ARC_3)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(-40.0, 20.0))
        ed.click(Vec2(-20.0, 0.0))
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, -50.0))
        ed.click(Vec2(0.0, 50.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, -50.0))
        ed.click(Vec2(0.0, 50.0))
        ed.setTool(Tools.MIRROR)
        ed.click(Vec2(-40.0, 20.0))
        ed.click(Vec2(0.0, 10.0))
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.ARC }, "the mirrored arc: ${ed.statusHint}")

        ed.setTool(Tools.KEY_POINTS)
        ed.click(Vec2(40.0, 20.0)) // the copy's own top
        val ends =
            ed.doc.elements.filter { it.kind == ElementKind.DERIVED_POINT && ed.doc.circlesThrough(it).isNotEmpty() }
        assertEquals(2, ends.size, "the copy's two ends know their carrier circle: ${ed.statusHint}")

        val before = linesOf(ed.doc).size
        ed.setTool(Tools.TANGENT_AT)
        ed.click(Vec2(20.0, 0.0)) // one of the copy's ends
        assertEquals(before + 1, linesOf(ed.doc).size, "one click on the copy's end: ${ed.statusHint}")
        assertTangentTo(assertNotNull(linesOf(ed.doc).lastOrNull()), Vec2(40.0, 0.0), 20.0, "the mirrored arc's tangent")
    }
}
