package constructit

import constructit.core.Evaluator
import constructit.dsl.ArcRef
import constructit.dsl.BezierRef
import constructit.dsl.PointRef
import constructit.dsl.SegmentRef
import constructit.dsl.arc
import constructit.dsl.bezier
import constructit.dsl.point
import constructit.dsl.resultOf
import constructit.dsl.scalar
import constructit.dsl.segment
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.PointerButton
import constructit.editor.Tools
import constructit.geom.Bezier
import constructit.geom.GeomMath
import constructit.geom.Vec2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Break on a plain segment, an arc and a Bézier** — the ortho break (OP-19) generalized off the path.
 *
 * One tool, dispatched by what the click landed on. What each case has to prove is the same three things:
 * the split lands *exactly* on the curve (so the drawing does not change shape at the moment of the break),
 * the joint is a real freedom afterwards (a free point, a rider, a live `t`), and the original curve is
 * treated honestly — **replaced** when nothing read it, **kept and hidden** when something did (OP-5: never
 * rewire a consumer, never silently change what it means).
 */
class BreakCurveTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s, PointerButton.PRIMARY)
        pointerUp(s)
    }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
        steps: Int = 3,
    ) {
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(from))
        for (i in 1..steps) pointerMove(camera.worldToScreen(from + (to - from) * (i.toDouble() / steps)))
        pointerUp(camera.worldToScreen(to))
    }

    private fun Editor.breakAt(world: Vec2) {
        setTool(Tools.BREAK_LEG)
        click(world)
    }

    private fun pos(el: Element): Vec2 = Evaluator().point(el.ref as PointRef)

    private fun seg(el: Element) = Evaluator().segment(el.ref as SegmentRef)

    private fun arcOf(el: Element) = Evaluator().arc(el.ref as ArcRef)

    private fun bez(el: Element) = Evaluator().bezier(el.ref as BezierRef)

    private fun curves(ed: Editor) = ed.doc.elements.filter { it.isCurve && it.visible }

    // ================= a plain segment =================

    /** Two free points and the segment between them — the span every segment case below splits. */
    private fun segmentDoc(): Editor {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 50.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 50.0))
        return ed
    }

    /**
     * The break is shape-preserving: the two halves start where the original started, end where it ended,
     * and meet at the projection of the click — so the drawing is byte-for-byte the same picture.
     */
    @Test
    fun breakingASegmentLeavesTheSameShapeInTwoPieces() {
        val ed = segmentDoc()
        val before = seg(ed.doc.elements.last())
        // clicked 1.5 mm *off* the segment, on the normal at 40% along: the split point is the projection
        // of the click, not the cursor — so the halves still add up to the line that was there
        val click = Vec2(40.0, 20.0) + Vec2(-0.4472135954999579, 0.8944271909999159) * 1.5
        ed.breakAt(click)

        val halves = curves(ed)
        assertEquals(2, halves.size, "one segment became two: ${halves.map { it.id }}")
        val h1 = seg(halves[0])
        val h2 = seg(halves[1])
        assertClose((h1.a - before.a).length(), 0.0, 1e-9, "the first half starts where the original did")
        assertClose((h2.b - before.b).length(), 0.0, 1e-9, "the second ends where the original ended")
        assertClose((h1.b - h2.a).length(), 0.0, 1e-9, "and they meet")
        // exactly on the original: the joint is the foot of the perpendicular from the click
        val ab = before.b - before.a
        val t = (click - before.a).dot(ab) / ab.dot(ab)
        assertClose(t, 0.4, 1e-9, "the click was on the normal at 40% along")
        assertClose((h1.b - (before.a + ab * t)).length(), 0.0, 1e-9, "the joint is the projected click")
        assertClose(h1.a.cross(h1.b - h1.a) - before.a.cross(before.b - before.a), 0.0, 1e-9, "still collinear")
    }

    /** Nothing read the segment, so the *step that drew it is gone*: the file reads as the two halves. */
    @Test
    fun anUnconsumedSegmentHasItsCreatingStepReplaced() {
        val ed = segmentDoc()
        assertTrue(DocumentFormat.save(ed.doc).contains("tool segment pts=e1,e2"), DocumentFormat.save(ed.doc))
        ed.breakAt(Vec2(40.0, 20.0))
        val script = DocumentFormat.save(ed.doc)
        assertEquals(
            """
constructit 3
point 0,0 -> e1
point 100,50 -> e2
point 40,20 -> e3
tool segment pts=e1,e3 -> e4
tool segment pts=e3,e2 -> e5
""".trimStart(),
            script,
            "the original's step is replaced, not annotated",
        )
        assertTrue(ed.statusHint.contains("split into"), "got: ${ed.statusHint}")
    }

    /** The joint is a free point: dragging it bends the pair, and each half follows its own end. */
    @Test
    fun theJointBendsAfterwardsAndBothHalvesFollow() {
        val ed = segmentDoc()
        ed.breakAt(Vec2(40.0, 20.0))
        val joint = ed.doc.elements.first { it.kind == ElementKind.POINT && abs(pos(it).x - 40.0) < 1e-9 }
        assertTrue(joint.draggable, "the split point is the freedom the break introduces")
        ed.drag(pos(joint), Vec2(40.0, 80.0))
        assertClose(pos(joint).y, 80.0, 1e-6, "it moved off the line, as asked")

        val halves = curves(ed)
        assertClose((seg(halves[0]).b - Vec2(40.0, 80.0)).length(), 0.0, 1e-6, "the first half follows the joint")
        assertClose((seg(halves[1]).a - Vec2(40.0, 80.0)).length(), 0.0, 1e-6, "and so does the second")
        assertClose((seg(halves[0]).a - Vec2(0.0, 0.0)).length(), 0.0, 1e-9, "the far ends stayed put")
        assertClose((seg(halves[1]).b - Vec2(100.0, 50.0)).length(), 0.0, 1e-9)
    }

    /**
     * A **consumed** segment stays, hidden — and the status says which element is why (OP-5).
     *
     * The consumer here is a rider on the segment: it is built on the segment's node, so replacing that node
     * would silently change what the rider slides along. Nothing is rewired, so the rider keeps working.
     */
    @Test
    fun aConsumedSegmentStaysHiddenAndItsConsumerKeepsWorking() {
        val ed = segmentDoc()
        val original = ed.doc.elements.last()
        ed.setTool(Tools.POINT_ON_LINE)
        ed.click(Vec2(20.0, 10.0))
        val rider = ed.doc.elements.last()
        assertEquals(ElementKind.ON_CURVE, rider.kind)

        ed.breakAt(Vec2(60.0, 30.0))
        assertTrue(ed.statusHint.contains("${original.id} stays (hidden)"), "got: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("${rider.id} is built on it"), "got: ${ed.statusHint}")
        assertFalse(original.visible, "it is still there, and out of the way")
        assertTrue(ed.doc.elements.any { it === original }, "and it was not removed — the rider needs it")
        assertEquals(2, curves(ed).size, "so the drawing shows the two halves")
        // the consumer is untouched: same position, still riding, still draggable along the same line
        assertClose((pos(rider) - Vec2(20.0, 10.0)).length(), 0.0, 1e-9)
        assertTrue(rider.draggable)
        ed.drag(pos(rider), Vec2(80.0, 40.0))
        assertClose((pos(rider) - Vec2(80.0, 40.0)).length(), 0.0, 1e-6, "it still slides along the segment it was given")
        // ...and the script still declares the hidden original, plus the recorded hide
        val script = DocumentFormat.save(ed.doc)
        assertTrue(script.contains("tool segment pts=e1,e2"), script)
        assertTrue(script.contains("hide els=e3"), script)
    }

    /** A *measurement* is a consumer too (OP-4): the number reads the segment, so the segment stays. */
    @Test
    fun aMeasuredSegmentStaysHiddenToo() {
        val ed = segmentDoc()
        val original = ed.doc.elements.last()
        ed.setTool(Tools.LENGTH)
        ed.click(Vec2(50.0, 25.0))
        val measured = ed.doc.scalars.single()
        ed.breakAt(Vec2(40.0, 20.0))
        assertTrue(ed.statusHint.contains("stays (hidden)"), "got: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains(measured.name), "the scalar reading it is named: ${ed.statusHint}")
        assertClose(Evaluator().scalar(measured.ref).mm, Vec2(100.0, 50.0).length(), 1e-9, "and it still measures the whole")
    }

    /**
     * A segment the drawing *derives* — a **mirrored** segment — has no two points of its own to hang halves
     * on, so the break materializes its **key points** and keeps the original as their source, hidden.
     */
    @Test
    fun aDerivedSegmentBreaksOverItsKeyPointsAndKeepsTheOriginal() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-80.0, 0.0))
        ed.click(Vec2(-20.0, 40.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-80.0, 0.0))
        ed.click(Vec2(-20.0, 40.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, -50.0))
        ed.click(Vec2(0.0, 50.0)) // the mirror axis: x = 0
        ed.setTool(Tools.MIRROR)
        ed.click(Vec2(-50.0, 20.0)) // the segment
        ed.click(Vec2(0.0, 20.0)) // the axis
        val side = ed.doc.elements.last { it.kind == ElementKind.SEGMENT }
        val was = seg(side)

        ed.breakAt((was.a + was.b) * 0.5)
        assertTrue(ed.statusHint.contains("stays (hidden)"), "got: ${ed.statusHint}")
        assertFalse(side.visible)
        assertTrue(DocumentFormat.save(ed.doc).contains("tool keypoints"), DocumentFormat.save(ed.doc))
        val halves = ed.doc.elements.filter { it.kind == ElementKind.SEGMENT && it.visible }.takeLast(2)
        assertEquals(2, halves.size)
        assertClose((seg(halves[0]).a - was.a).length(), 0.0, 1e-9)
        assertClose((seg(halves[1]).b - was.b).length(), 0.0, 1e-9)
        assertClose((seg(halves[0]).b - seg(halves[1]).a).length(), 0.0, 1e-9)
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save -> load -> save")
    }

    /** A break replaces geometry, so it is refused on a curve a user-defined tool is built from (OP-6). */
    @Test
    fun aSegmentThatDefinesAToolIsRefused() {
        val ed = segmentDoc()
        val original = ed.doc.elements.last()
        val ends = ed.doc.elements.filter { it.kind == ElementKind.POINT }
        assertNotNull(ed.doc.defineMacro("bar", ends + original, ends, emptyList()))
        ed.breakAt(Vec2(40.0, 20.0))
        assertTrue(ed.statusHint.contains("tool's definition"), "got: ${ed.statusHint}")
        assertEquals(1, curves(ed).size, "nothing was built")
    }

    /**
     * A member of a **placed** group is refused (OP-16): its position is frame-relative, and the free point a
     * break introduces would not be — so the joint would come apart the moment the group moved.
     */
    @Test
    fun aSegmentInAPlacedGroupIsRefused() {
        val ed = segmentDoc()
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(-40.0, -40.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(140.0, 90.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(140.0, 90.0)))
        assertNotNull(ed.groupSelection("kitchen"))
        assertTrue(ed.placeGroup(ed.doc.groups.single()), "got: ${ed.statusHint}")
        val before = DocumentFormat.save(ed.doc)

        ed.breakAt(Vec2(40.0, 20.0))
        assertTrue(ed.statusHint.contains("placed group kitchen"), "got: ${ed.statusHint}")
        assertEquals(before, DocumentFormat.save(ed.doc), "the break did not happen")
    }

    /** A click at an end would leave a zero-length piece: refused, and said. */
    @Test
    fun aBreakAtAnEndIsRefused() {
        val ed = segmentDoc()
        ed.breakAt(Vec2(0.0, 0.0))
        assertTrue(ed.statusHint.contains("away from"), "got: ${ed.statusHint}")
        assertEquals(1, curves(ed).size)
    }

    /** The break is one operation: one undo takes the whole split back, one redo brings it again. */
    @Test
    fun aSegmentBreakIsOneUndoStep() {
        val ed = segmentDoc()
        val before = DocumentFormat.save(ed.doc)
        ed.breakAt(Vec2(40.0, 20.0))
        val after = DocumentFormat.save(ed.doc)
        assertTrue(ed.undo())
        assertEquals(before, DocumentFormat.save(ed.doc), "one checkpoint per break")
        assertTrue(ed.redo())
        assertEquals(after, DocumentFormat.save(ed.doc))
    }

    /** The split rides the file: `save -> load -> save` byte-equal, and the joint still free. */
    @Test
    fun aSegmentBreakRoundTrips() {
        val ed = segmentDoc()
        ed.breakAt(Vec2(40.0, 20.0))
        ed.drag(Vec2(40.0, 20.0), Vec2(40.0, 70.0)) // the joint is state, so it must come back bent
        val once = DocumentFormat.save(ed.doc)
        val reloaded = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(reloaded), "save -> load -> save must be identical")
        val fresh = Editor()
        fresh.replaceDocument(reloaded)
        assertEquals(2, curves(fresh).size)
        assertClose((seg(curves(fresh)[0]).b - Vec2(40.0, 70.0)).length(), 0.0, 1e-6)
    }

    // ================= an arc =================

    /** A quarter-ish arc about the origin, drawn centre-start-end (counter-clockwise). */
    private fun arcDoc(): Editor {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.click(Vec2(0.0, 50.0))
        ed.setTool(Tools.ARC_CS)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.click(Vec2(0.0, 50.0))
        return ed
    }

    /**
     * Both sub-arcs are trims of the **same carrier**: same centre, same radius, meeting at the click's
     * angle and together sweeping exactly what the original swept.
     */
    @Test
    fun breakingAnArcMakesTwoArcsOnTheSharedCarrier() {
        val ed = arcDoc()
        val original = ed.doc.elements.last()
        val was = arcOf(original)
        // click on the arc at 45°
        val at = Vec2(50.0 * 0.70710678, 50.0 * 0.70710678)
        ed.breakAt(at)

        val halves = ed.doc.elements.filter { it.kind == ElementKind.ARC && it.visible }
        assertEquals(2, halves.size, "two arcs")
        val a1 = arcOf(halves[0])
        val a2 = arcOf(halves[1])
        for (a in listOf(a1, a2)) {
            assertClose((a.center - was.center).length(), 0.0, 1e-9, "on the carrier's centre")
            assertClose(a.radius, was.radius, 1e-9, "and its radius")
            assertEquals(was.ccw, a.ccw, "sweeping the way the carrier does (OP-1: a stored choice)")
        }
        assertClose(a1.startAngle, was.startAngle, 1e-9, "the first starts where the original did")
        assertClose(a2.endAngle, was.endAngle, 1e-9, "the second ends where it ended")
        assertClose(a1.endAngle, a2.startAngle, 1e-9, "and they hand over at one angle")
        assertClose(a1.endAngle, kotlin.math.PI / 4.0, 1e-6, "which is the click's")
        assertClose(
            abs(GeomMath.sweep(a1)) + abs(GeomMath.sweep(a2)),
            abs(GeomMath.sweep(was)),
            1e-9,
            "together they are the arc that was there",
        )
    }

    /**
     * The arc's halves are `arcBetween` on the arc itself, so the arc **is** a consumer of its own break:
     * it stays as the shared carrier, hidden, and the status says so rather than pretending otherwise.
     */
    @Test
    fun theBrokenArcStaysAsTheCarrierItsHalvesShare() {
        val ed = arcDoc()
        val original = ed.doc.elements.last()
        ed.breakAt(Vec2(35.0, 35.0))
        assertTrue(ed.statusHint.contains("${original.id} stays (hidden)"), "got: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("carrier"), "got: ${ed.statusHint}")
        assertFalse(original.visible)
        assertTrue(ed.doc.elements.any { it === original })
        // the carrier keeps driving both halves: move the arc's own start point and they follow
        ed.drag(Vec2(50.0, 0.0), Vec2(70.0, 0.0))
        val halves = ed.doc.elements.filter { it.kind == ElementKind.ARC && it.visible }
        assertClose(arcOf(halves[0]).radius, 70.0, 1e-6, "the first half grew with the carrier")
        assertClose(arcOf(halves[1]).radius, 70.0, 1e-6, "and so did the second")
    }

    /** The split point is a rider: sliding it round re-splits the arc, and the halves stay a partition. */
    @Test
    fun slidingTheArcsSplitPointReSplitsIt() {
        val ed = arcDoc()
        ed.breakAt(Vec2(35.0, 35.0))
        val rider = ed.doc.elements.last { it.kind == ElementKind.ON_CURVE }
        assertTrue(rider.draggable)
        val target = Vec2(50.0 * kotlin.math.cos(0.3), 50.0 * kotlin.math.sin(0.3))
        ed.drag(pos(rider), target)
        val halves = ed.doc.elements.filter { it.kind == ElementKind.ARC && it.visible }
        assertClose(arcOf(halves[0]).endAngle, 0.3, 1e-6, "the joint moved to where it was dragged")
        assertClose(arcOf(halves[1]).startAngle, 0.3, 1e-6, "and both halves re-split there")
    }

    /** The arc, the split angle and the sweep choice all survive a reload — byte-for-byte. */
    @Test
    fun anArcBreakRoundTripsWithItsChoices() {
        val ed = arcDoc()
        ed.breakAt(Vec2(35.0, 35.0))
        ed.drag(pos(ed.doc.elements.last { it.kind == ElementKind.ON_CURVE }), Vec2(20.0, 45.0))
        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.contains("breakarc e4"), once)
        assertTrue(once.contains("ccw"), "the sweep is a stored discrete choice (OP-1): $once")
        val reloaded = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(reloaded), "save -> load -> save must be identical")
        val fresh = Editor()
        fresh.replaceDocument(reloaded)
        val there = ed.doc.elements.filter { it.kind == ElementKind.ARC && it.visible }.map { arcOf(it) }
        val back = fresh.doc.elements.filter { it.kind == ElementKind.ARC && it.visible }.map { arcOf(it) }
        assertEquals(there.size, back.size)
        for ((a, b) in there.zip(back)) {
            assertClose(a.startAngle, b.startAngle, 1e-12)
            assertClose(a.endAngle, b.endAngle, 1e-12)
            assertEquals(a.ccw, b.ccw)
        }
    }

    /** A clockwise arc breaks the same way, and its halves keep *its* sweep, not a guessed one. */
    @Test
    fun aClockwiseArcKeepsItsSweepThroughTheBreak() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.click(Vec2(0.0, 50.0))
        // arc3: start, a point on the way, end — going clockwise from (0,50) down through (35,35)
        ed.setTool(Tools.ARC_3)
        ed.click(Vec2(0.0, 50.0))
        ed.click(Vec2(35.355339059327378, 35.355339059327378))
        ed.click(Vec2(50.0, 0.0))
        val original = ed.doc.elements.last()
        val was = arcOf(original)
        ed.breakAt(Vec2(46.19397662556434, 19.134171618254492)) // 22.5°
        val halves = ed.doc.elements.filter { it.kind == ElementKind.ARC && it.visible }
        assertEquals(2, halves.size)
        for (h in halves) assertEquals(was.ccw, arcOf(h).ccw, "the halves sweep the way the original did")
        assertClose(
            abs(GeomMath.sweep(arcOf(halves[0]))) + abs(GeomMath.sweep(arcOf(halves[1]))),
            abs(GeomMath.sweep(was)),
            1e-9,
            "and together they are it",
        )
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)))
    }

    /** One undo for the whole arc break, hide step included. */
    @Test
    fun anArcBreakIsOneUndoStep() {
        val ed = arcDoc()
        val before = DocumentFormat.save(ed.doc)
        ed.breakAt(Vec2(35.0, 35.0))
        assertTrue(ed.undo())
        assertEquals(before, DocumentFormat.save(ed.doc))
        assertTrue(ed.redo())
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.ARC && it.visible })
    }

    // ================= a cubic Bézier =================

    /** A cubic with four free control points — the curve every Bézier case below splits. */
    private fun bezierDoc(): Editor {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(20.0, 60.0))
        ed.click(Vec2(80.0, 60.0))
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.BEZIER)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(20.0, 60.0))
        ed.click(Vec2(80.0, 60.0))
        ed.click(Vec2(100.0, 0.0))
        return ed
    }

    /** de Casteljau's own formula, for the construction to be checked against. */
    private fun subdivide(
        b: Bezier,
        t: Double,
    ): Pair<Bezier, Bezier> {
        fun lerp(
            a: Vec2,
            c: Vec2,
        ) = a + (c - a) * t
        val l1 = lerp(b.p0, b.p1)
        val m = lerp(b.p1, b.p2)
        val r1 = lerp(b.p2, b.p3)
        val l2 = lerp(l1, m)
        val r2 = lerp(m, r1)
        val s = lerp(l2, r2)
        return Bezier(b.p0, l1, l2, s) to Bezier(s, r2, r1, b.p3)
    }

    private fun assertSameCurve(
        actual: Bezier,
        expected: Bezier,
        tol: Double = 1e-9,
        msg: String = "",
    ) {
        for (i in 0..8) {
            val s = i / 8.0
            val d = (GeomMath.bezierPointAt(actual, s) - GeomMath.bezierPointAt(expected, s)).length()
            assertClose(d, 0.0, tol, "at s=$s $msg")
        }
    }

    /** The two halves *are* the subdivision — checked against the closed form at several parameters. */
    @Test
    fun breakingABezierSplitsItExactlyByDeCasteljau() {
        val ed = bezierDoc()
        val was = bez(ed.doc.elements.last())
        // click a third along; the split lands at the curve's own nearest parameter to it
        ed.breakAt(GeomMath.bezierPointAt(was, 0.35))

        val halves = ed.doc.elements.filter { it.kind == ElementKind.BEZIER && it.visible }
        assertEquals(2, halves.size)
        val t = Evaluator().scalar(ed.doc.scalars.single { it.name == "t" }.ref).value
        assertClose(t, 0.35, 1e-6, "the click's nearest parameter")
        val (expectedLeft, expectedRight) = subdivide(was, t)
        assertSameCurve(bez(halves[0]), expectedLeft, msg = "left half")
        assertSameCurve(bez(halves[1]), expectedRight, msg = "right half")
        // and therefore the same picture: the halves re-parameterize the original
        for (i in 0..8) {
            val s = i / 8.0
            assertClose(
                (GeomMath.bezierPointAt(bez(halves[0]), s) - GeomMath.bezierPointAt(was, t * s)).length(),
                0.0,
                1e-9,
                "the left half is the original up to t",
            )
            assertClose(
                (GeomMath.bezierPointAt(bez(halves[1]), s) - GeomMath.bezierPointAt(was, t + (1 - t) * s)).length(),
                0.0,
                1e-9,
                "the right half is the rest of it",
            )
        }
    }

    /**
     * One `t`, shared by every intermediate point — which is what makes it *one* freedom (OP-5: sharing a
     * node is equality). Six ratio points, one parameter, and each of them draggable.
     */
    @Test
    fun theDeCasteljauPointsShareOneLiveTParameter() {
        val ed = bezierDoc()
        ed.breakAt(GeomMath.bezierPointAt(bez(ed.doc.elements.last()), 0.4))
        assertEquals(1, ed.doc.scalars.size, "one parameter for the whole split")
        val t = ed.doc.scalars.single()
        assertEquals("t", t.name)
        val ratios = ed.doc.elements.filter { it.kind == ElementKind.ON_CURVE }
        assertEquals(6, ratios.size, "three, two, one — de Casteljau's triangle, as a construction")
        for (r in ratios) {
            assertTrue(r.draggable, "${r.id} is a ratio point over t, so it is draggable")
            assertEquals(listOf("factor"), r.handle!!.fields().map { it.label }, "and the one field it has writes t (OP-13)")
        }
        val script = DocumentFormat.save(ed.doc)
        assertEquals(6, Regex("tool midpoint pts=\\S+ scalar=\"t\"").findAll(script).count(), script)
    }

    /**
     * **The split slides.** `t` is a live parameter, so typing it (or dragging any ratio point) re-splits
     * the curve — and the halves stay the exact subdivision at the new `t`.
     */
    @Test
    fun typingTSlidesTheSplitAndReSplitsTheCurveLive() {
        val ed = bezierDoc()
        val was = bez(ed.doc.elements.last())
        ed.breakAt(GeomMath.bezierPointAt(was, 0.4))
        val t = ed.doc.scalars.single()
        val halves = ed.doc.elements.filter { it.kind == ElementKind.BEZIER && it.visible }
        val split = ed.doc.elements.last { it.kind == ElementKind.ON_CURVE }

        for (v in listOf(0.15, 0.5, 0.82)) {
            ed.doc.setParameter(t, constructit.units.Quantity.number(v))
            assertClose((pos(split) - GeomMath.bezierPointAt(was, v)).length(), 0.0, 1e-9, "the joint slid to t=$v")
            val (l, r) = subdivide(was, v)
            assertSameCurve(bez(halves[0]), l, msg = "left half at t=$v")
            assertSameCurve(bez(halves[1]), r, msg = "right half at t=$v")
        }

        // the same write by dragging one of the ratio points: it is the same node (OP-13)
        val ratio = ed.doc.elements.first { it.kind == ElementKind.ON_CURVE }
        ed.drag(pos(ratio), Vec2(10.0, 30.0))
        val now = Evaluator().scalar(t.ref).value
        assertTrue(abs(now - 0.82) > 1e-6, "dragging a ratio point wrote t: $now")
        val (l, r) = subdivide(was, now)
        assertSameCurve(bez(halves[0]), l, msg = "left half after the drag")
        assertSameCurve(bez(halves[1]), r, msg = "right half after the drag")
    }

    /**
     * The halves are the *formula*, not a fit: drag a control point and they re-derive exactly, with no
     * refitting and no drift — the whole reason to construct the split instead of computing it once.
     */
    @Test
    fun theHalvesStayExactWhenAControlPointIsDragged() {
        val ed = bezierDoc()
        ed.breakAt(GeomMath.bezierPointAt(bez(ed.doc.elements.last()), 0.45))
        // the original's step was replaced, so what is left *is* the two halves — over the same four controls
        val halves = ed.doc.elements.filter { it.kind == ElementKind.BEZIER }
        assertEquals(2, halves.size)
        val controls = ed.doc.elements.filter { it.kind == ElementKind.POINT }
        assertEquals(4, controls.size)
        val t = Evaluator().scalar(ed.doc.scalars.single().ref).value

        for (target in listOf(Vec2(20.0, 120.0), Vec2(-40.0, -30.0), Vec2(55.0, 25.0))) {
            ed.drag(pos(controls[1]), target)
            assertClose((pos(controls[1]) - target).length(), 0.0, 1e-6, "the control moved to $target")
            val now = Bezier(pos(controls[0]), pos(controls[1]), pos(controls[2]), pos(controls[3]))
            val (l, r) = subdivide(now, t)
            assertSameCurve(bez(halves[0]), l, msg = "left half after moving a control to $target")
            assertSameCurve(bez(halves[1]), r, msg = "right half after moving a control to $target")
        }
    }

    /** Nothing consumed the spline, so its step went: the file reads as the construction of the two halves. */
    @Test
    fun anUnconsumedBezierHasItsCreatingStepReplaced() {
        val ed = bezierDoc()
        ed.breakAt(GeomMath.bezierPointAt(bez(ed.doc.elements.last()), 0.4))
        val script = DocumentFormat.save(ed.doc)
        assertFalse(
            script.contains("tool bezier pts=e1,e2,e3,e4"),
            "the original's step is gone:\n$script",
        )
        assertEquals(2, Regex("tool bezier ").findAll(script).count(), "replaced by the two halves:\n$script")
        assertTrue(script.contains("param \"t\" ="), script)
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.BEZIER && !it.visible }, "nothing hidden, nothing left over")
    }

    /** A consumed spline stays hidden, and what reads it keeps reading it. */
    @Test
    fun aConsumedBezierStaysHidden() {
        val ed = bezierDoc()
        val original = ed.doc.elements.last()
        // an outline over the spline and a segment closing it: the loop is built on the spline's node
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.OUTLINE)
        ed.click(GeomMath.bezierPointAt(bez(original), 0.5))
        ed.click(Vec2(50.0, 0.0))
        ed.key("Enter")
        val outline = ed.doc.elements.last { it.kind == ElementKind.OUTLINE }

        ed.breakAt(GeomMath.bezierPointAt(bez(original), 0.4))
        assertTrue(ed.statusHint.contains("${original.id} stays (hidden)"), "got: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("is built on it"), "got: ${ed.statusHint}")
        assertFalse(original.visible)
        assertTrue(Evaluator().resultOf(outline.ref) is constructit.core.EvalResult.Ok, "the outline still traces")
    }

    /** The parameter and the two halves ride the file, and the split comes back where it was left. */
    @Test
    fun aBezierBreakRoundTrips() {
        val ed = bezierDoc()
        ed.breakAt(GeomMath.bezierPointAt(bez(ed.doc.elements.last()), 0.4))
        ed.doc.setParameter(ed.doc.scalars.single(), constructit.units.Quantity.number(0.63))
        val once = DocumentFormat.save(ed.doc)
        val reloaded = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(reloaded), "save -> load -> save must be identical")
        val fresh = Editor()
        fresh.replaceDocument(reloaded)
        assertEquals(0.63, Evaluator().scalar(fresh.doc.scalars.single().ref).value)
        val there = ed.doc.elements.filter { it.kind == ElementKind.BEZIER && it.visible }.map { bez(it) }
        val back = fresh.doc.elements.filter { it.kind == ElementKind.BEZIER && it.visible }.map { bez(it) }
        assertEquals(there.size, back.size)
        for ((a, b) in there.zip(back)) assertSameCurve(a, b, 1e-12)
    }

    /** One undo for the whole Bézier break — the parameter, six ratio points and two halves together. */
    @Test
    fun aBezierBreakIsOneUndoStep() {
        val ed = bezierDoc()
        val before = DocumentFormat.save(ed.doc)
        ed.breakAt(GeomMath.bezierPointAt(bez(ed.doc.elements.last()), 0.4))
        assertTrue(ed.undo())
        assertEquals(before, DocumentFormat.save(ed.doc), "one checkpoint per break")
        assertTrue(ed.doc.scalars.isEmpty(), "the shared t went with the split that wanted it")
        assertTrue(ed.redo())
        assertEquals(1, ed.doc.scalars.size)
    }

    // ================= the ortho path is untouched (OP-19) =================

    /** An ortho leg still breaks the way it always did: a zero-length jog, not a construction split. */
    @Test
    fun anOrthoLegStillTakesTheJogPath() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 2.0))
        ed.finishPath()
        val path = ed.doc.orthoPaths.single()
        ed.breakAt(Vec2(60.0, 1.0))
        assertTrue(ed.statusHint.contains("open the corner"), "got: ${ed.statusHint}")
        assertEquals(3, path.legCount, "one leg became three, with a zero-length jog between")
        assertTrue(ed.doc.scalars.isEmpty(), "and no parameter appeared")
        assertTrue(DocumentFormat.save(ed.doc).contains("orthobreak"), DocumentFormat.save(ed.doc))
    }

    /** Clicking nothing says what the tool takes — all of it. */
    @Test
    fun clickingEmptySpaceExplainsWhatCanBeBroken() {
        val ed = segmentDoc()
        ed.breakAt(Vec2(500.0, 500.0))
        assertTrue(ed.statusHint.contains("segment"), "got: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("Bézier"), "got: ${ed.statusHint}")
        assertEquals(1, curves(ed).size)
    }
}
