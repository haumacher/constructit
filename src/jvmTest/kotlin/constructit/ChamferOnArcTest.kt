package constructit

import constructit.core.CircleValue
import constructit.core.Evaluator
import constructit.core.SegmentValue
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.PreviewShape
import constructit.editor.Tools
import constructit.geom.Segment
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The chamfer-on-arc convention, decided** (the session-76 entry, item c — retiring the parked note *"a
 * bevel across a round leg has two honest readings, a chord and an arc of the same length, and until that
 * convention is stated a tool that silently picked one would be guessing"*).
 *
 * The convention, and what these tests pin: **the setback is measured along the carrier**, so on a round leg
 * it is an *arc* distance, and the bevel is the straight segment between the two setback points. The decisive
 * assertion is [aBevelAcrossALineAndAnArcSetsBackAlongEachCarrier]'s last one: the chord from the corner to
 * the round leg's setback point is measurably **shorter** than the setback, which is precisely what tells the
 * chosen reading from the rejected one.
 *
 * The geometry is picked so that every number is exact: the circle centred at (0, 40) with radius 50 crosses
 * the x-axis at (±30, 0), so the corner is a whole number and the two setback points are closed form.
 */
class ChamferOnArcTest {
    private val R = 50.0
    private val C = Vec2(0.0, 40.0)
    private val corner = Vec2(30.0, 0.0)
    private val setback = 10.0

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    /** The point at arc distance [d] from [corner] round the test circle, turned counter-clockwise for `+1`. */
    private fun alongCircle(
        d: Double,
        sign: Int,
    ): Vec2 {
        val a = atan2(corner.y - C.y, corner.x - C.x) + sign * d / R
        return C + Vec2(cos(a), sin(a)) * R
    }

    /** A segment along the x-axis and a circle crossing it at (±30, 0), with a chamfer distance ready to use. */
    private fun corner(distance: Double = 10.0): Editor {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.activeScalar = ed.doc.newParameter("R", R.mm)
        ed.setTool(Tools.CIRCLE_R)
        ed.click(C)
        ed.activeScalar = ed.doc.newParameter("c", distance.mm)
        ed.setTool(Tools.CHAMFER)
        return ed
    }

    private fun bevelOf(ed: Editor): Segment {
        val el = ed.doc.elements.last { it.kind == ElementKind.SEGMENT }
        return assertNotNull(Evaluator().valueOf(el.ref) as? SegmentValue, "the bevel is built").seg
    }

    /** The bevel end nearer [near] — the two ends come in whatever order the construction made them. */
    private fun endNear(
        s: Segment,
        near: Vec2,
    ): Vec2 = if ((s.a - near).length() <= (s.b - near).length()) s.a else s.b

    private fun roundTrips(ed: Editor): String {
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save -> load -> save must be byte-equal")
        return once
    }

    // ---- the convention itself ----

    @Test
    fun aBevelAcrossALineAndAnArcSetsBackAlongEachCarrier() {
        val ed = corner()
        ed.click(Vec2(45.0, 0.0)) // the straight leg, on the +x side of the corner at (30, 0)
        ed.click(Vec2(38.0, 7.0)) // the round leg, counter-clockwise of that corner
        val bevel = bevelOf(ed)

        val onLine = endNear(bevel, Vec2(40.0, 0.0))
        val onArc = endNear(bevel, alongCircle(setback, +1))
        // the straight leg: exactly the setback along it from the corner, as it always was
        assertClose(onLine.x, 40.0, 1e-9, "the straight leg's end is 10 mm along it from (30, 0)")
        assertClose(onLine.y, 0.0, 1e-9, "and still on the leg")
        // the round leg: exactly the setback **along the arc**, closed form
        val want = alongCircle(setback, +1)
        assertClose(onArc.x, want.x, 1e-9, "the round leg's end is 10 mm of arc from the corner")
        assertClose(onArc.y, want.y, 1e-9, "likewise y")
        assertClose((onArc - C).length(), R, 1e-9, "and it is on the circle, by construction")

        // **the decisive assertion**: the *chord* from the corner to that end is 2R·sin(d/2R), not d — so this
        // is the arc reading and not the chord one, and the difference is far above any tolerance
        val chord = (onArc - corner).length()
        assertClose(chord, 2.0 * R * sin(setback / (2.0 * R)), 1e-9, "the chord is what the arc reading implies")
        assertTrue(abs(chord - setback) > 1e-4, "and it is measurably shorter than the setback: $chord")
    }

    /** It is a construction: widening the chamfer slides both ends along their own legs. */
    @Test
    fun wideningTheChamferSlidesBothEndsAlongTheirLegs() {
        val ed = corner()
        ed.click(Vec2(45.0, 0.0))
        ed.click(Vec2(38.0, 7.0))
        ed.doc.setParameter(ed.doc.scalars.first { it.name == "c" }, 25.0.mm)
        val wider = bevelOf(ed)
        assertClose(endNear(wider, Vec2(55.0, 0.0)).x, 55.0, 1e-9, "25 mm along the straight leg")
        val want = alongCircle(25.0, +1)
        val onArc = endNear(wider, want)
        assertClose(onArc.x, want.x, 1e-9, "25 mm of arc along the round one")
        assertClose(onArc.y, want.y, 1e-9, "likewise y")
        assertClose((onArc - C).length(), R, 1e-9, "still on the circle")
    }

    /** The other quadrant, and the other corner: both are read off the clicks, exactly as a fillet's are. */
    @Test
    fun theQuadrantAndTheCornerAreBothReadOffTheClicks() {
        val ed = corner()
        ed.click(Vec2(20.0, 0.0)) // this time on the -x side of the corner at (30, 0)
        ed.click(Vec2(21.0, -5.0)) // and clockwise of it
        val bevel = bevelOf(ed)
        assertClose(endNear(bevel, Vec2(20.0, 0.0)).x, 20.0, 1e-9, "10 mm back along the leg")
        val want = alongCircle(setback, -1)
        assertClose(endNear(bevel, want).x, want.x, 1e-9, "and 10 mm of arc the other way round")

        // ...and the crossing at (-30, 0) is the corner when the clicks point there
        val ed2 = corner()
        ed2.click(Vec2(-45.0, 0.0))
        ed2.click(Vec2(-38.0, 7.0))
        val other = bevelOf(ed2)
        assertTrue(other.a.x < 0.0 && other.b.x < 0.0, "the bevel sits at the crossing the clicks named: $other")
    }

    /** Two round legs are the third combination, and no new case: both ends run along their own circles. */
    @Test
    fun twoArcsBevelAsWell() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("R1", 50.0.mm)
        ed.setTool(Tools.CIRCLE_R)
        ed.click(Vec2(0.0, 0.0))
        ed.activeScalar = ed.doc.newParameter("R2", 40.0.mm)
        ed.setTool(Tools.CIRCLE_R)
        ed.click(Vec2(60.0, 0.0))
        // the two circles cross where x = (3600 + 2500 - 1600) / 120 = 37.5, y = ±sqrt(2500 - 1406.25)
        val cross = Vec2(37.5, kotlin.math.sqrt(2500.0 - 37.5 * 37.5))
        ed.activeScalar = ed.doc.newParameter("c", 6.0.mm)
        ed.setTool(Tools.CHAMFER)
        ed.click(cross + Vec2(-4.0, -3.0)) // on the first circle, clockwise of the crossing
        ed.click(cross + Vec2(4.0, -2.0)) // on the second, the other way
        val bevel = bevelOf(ed)
        for (end in listOf(bevel.a, bevel.b)) {
            val onFirst = abs((end - Vec2(0.0, 0.0)).length() - 50.0) < 1e-9
            val onSecond = abs((end - Vec2(60.0, 0.0)).length() - 40.0) < 1e-9
            assertTrue(onFirst || onSecond, "each end stays on its own carrier: $end")
        }
        // each end is its setback of **arc** from the crossing, measured on its own circle
        for ((centre, radius) in listOf(Vec2(0.0, 0.0) to 50.0, Vec2(60.0, 0.0) to 40.0)) {
            val end = listOf(bevel.a, bevel.b).first { abs((it - centre).length() - radius) < 1e-9 }
            val a0 = atan2(cross.y - centre.y, cross.x - centre.x)
            val a1 = atan2(end.y - centre.y, end.x - centre.x)
            var turn = a1 - a0
            while (turn > kotlin.math.PI) turn -= 2.0 * kotlin.math.PI
            while (turn < -kotlin.math.PI) turn += 2.0 * kotlin.math.PI
            assertClose(abs(turn) * radius, 6.0, 1e-9, "6 mm of arc along the circle of radius $radius")
        }
        roundTrips(ed)
    }

    // ---- the stored choice, and the file ----

    /**
     * **The sign is scored once and taken verbatim.** After the circle grows, the corner the clicks were
     * scored against has moved — so a replay that scored again would be a replay that re-decided (the
     * fillet's own lesson, OP-1/OP-18). The reload must reproduce the live bevel exactly.
     */
    @Test
    fun theStoredSignSurvivesAReplayAgainstMovedGeometry() {
        val ed = corner()
        ed.click(Vec2(45.0, 0.0))
        ed.click(Vec2(38.0, 7.0))
        ed.checkpoint()
        val stored = ed.doc.journal.last { it.kind == "tool" }

        // the circle grows: the crossings move from ±30 to ±sqrt(70^2 - 40^2), and both clicks are now
        // nowhere near the bevel they scored
        ed.doc.setParameter(ed.doc.scalars.first { it.name == "R" }, 70.0.mm)
        val live = bevelOf(ed)

        val text = roundTrips(ed)
        assertTrue(text.contains("signs="), "the choice is in the file:\n$text")
        val back = DocumentFormat.load(text)
        val reloaded =
            assertNotNull(
                Evaluator().valueOf(back.elements.last { it.kind == ElementKind.SEGMENT }.ref) as? SegmentValue,
                "the reloaded bevel",
            ).seg
        assertClose(reloaded.a.x, live.a.x, 1e-12, "the reloaded bevel is the live one, to the last bit")
        assertClose(reloaded.a.y, live.a.y, 1e-12, "likewise")
        assertClose(reloaded.b.x, live.b.x, 1e-12, "likewise")
        assertClose(reloaded.b.y, live.b.y, 1e-12, "likewise")
        assertEquals(3, ed.doc.storedSigns(stored).size, "three signs: a way along each leg, and which crossing")
    }

    /**
     * A **line–line** chamfer is untouched by the generalization — the same two signs in the same two
     * positions, so every stored one replays byte for byte (OP-18: no stored literal changes meaning).
     */
    @Test
    fun theLineLineChamferStoresExactlyWhatItAlwaysDid() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(0.0, 60.0))
        ed.activeScalar = ed.doc.newParameter("c", 10.0.mm)
        ed.setTool(Tools.CHAMFER)
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(0.0, 30.0))
        ed.checkpoint()
        val bevel = bevelOf(ed)
        assertEquals(setOf(10.0 to 0.0, 0.0 to 10.0), setOf(bevel.a.x to bevel.a.y, bevel.b.x to bevel.b.y))
        assertEquals(2, ed.doc.storedSigns(ed.doc.journal.last { it.kind == "tool" }).size, "two signs, as ever")
        roundTrips(ed)
    }

    // ---- what still refuses, and why ----

    /**
     * A leg that carries **neither a line nor a circle** is refused by name: a bevel's end has to run a stated
     * distance *along* its leg, and a function curve's arc length is not a closed form this drawing states
     * (OP-15) — so the setback along one could only be sampled. The fillet's own sentence, one tool over.
     */
    @Test
    fun aFunctionCurveLegIsRefusedByName() {
        val ed = Editor()
        ed.doc.newParameter("r", 20.0.mm)
        assertNotNull(ed.addFunctionCurve("r * (cos(t) + t * sin(t))", "r * (sin(t) - t * cos(t))", 0.0, 1.6), ed.statusHint)
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        val curve = ed.doc.elements.first { it.kind == ElementKind.FUNC_CURVE }
        val line = ed.doc.elements.last { it.kind == ElementKind.SEGMENT }
        val before = ed.doc.elements.size
        assertNull(
            ed.doc.chamferBetweenCurves(line, curve, ed.doc.scalars.first { it.name == "r" }.ref, Vec2(0.0, 0.0), Vec2(20.0, 0.0)),
            "there is no bevel across it",
        )
        val why = assertNotNull(ed.doc.note, "and it says so by name")
        assertTrue(why.contains(ed.doc.nameOf(curve)), "naming the leg: $why")
        assertTrue(why.contains("circle") || why.contains("arc"), "and what does work: $why")
        assertEquals(before, ed.doc.elements.size, "nothing was built")
    }

    /** Two legs that do not cross have no corner to bevel, and that is said rather than dropped. */
    @Test
    fun legsThatDoNotCrossAreRefusedByName() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.activeScalar = ed.doc.newParameter("R", 10.0.mm)
        ed.setTool(Tools.CIRCLE_R)
        ed.click(Vec2(0.0, 40.0)) // a circle of radius 10 nowhere near the axis
        ed.activeScalar = ed.doc.newParameter("c", 4.0.mm)
        val line = ed.doc.elements.first { it.kind == ElementKind.SEGMENT }
        val circle = ed.doc.elements.first { it.kind == ElementKind.CIRCLE }
        assertNull(
            ed.doc.chamferBetweenCurves(line, circle, ed.doc.scalars.first { it.name == "c" }.ref, Vec2(20.0, 0.0), Vec2(0.0, 30.0)),
            "nothing to bevel",
        )
        assertTrue(assertNotNull(ed.doc.note).contains("cross"), "and it says why: ${ed.doc.note}")
    }

    // ---- the preview shows what the click builds ----

    @Test
    fun thePreviewIsTheBevelTheClickCuts() {
        val ed = corner()
        ed.click(Vec2(45.0, 0.0))
        val s = ed.camera.worldToScreen(Vec2(38.0, 7.0))
        ed.pointerMove(s)
        val previewed = assertNotNull(ed.previewShapes.filterIsInstance<PreviewShape.Seg>().firstOrNull()).seg
        ed.click(Vec2(38.0, 7.0))
        val built = bevelOf(ed)
        assertClose(previewed.a.x, built.a.x, 1e-9, "the bevel built is the bevel previewed")
        assertClose(previewed.a.y, built.a.y, 1e-9, "likewise")
        assertClose(previewed.b.x, built.b.x, 1e-9, "likewise")
        assertClose(previewed.b.y, built.b.y, 1e-9, "likewise")
    }

    /** The bevel's ends are **joints**, so a chamfered corner still traces into a closed outline. */
    @Test
    fun theBevelsEndsAreJointsOnBothLegs() {
        val ed = corner()
        ed.click(Vec2(45.0, 0.0))
        ed.click(Vec2(38.0, 7.0))
        val bevel = ed.doc.elements.last { it.kind == ElementKind.SEGMENT }
        val line = ed.doc.elements.first { it.kind == ElementKind.SEGMENT }
        val circle = ed.doc.elements.first { it.kind == ElementKind.CIRCLE }
        for (leg in listOf<Element>(line, circle)) {
            assertNotNull(ed.doc.registeredJoint(bevel, leg), "the bevel hands over to ${ed.doc.nameOf(leg)}")
        }
        assertTrue(
            (Evaluator().valueOf(circle.ref) as CircleValue).circle.radius > 0.0,
            "and the legs themselves are untouched",
        )
    }
}
