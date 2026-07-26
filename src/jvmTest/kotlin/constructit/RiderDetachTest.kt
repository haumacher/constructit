package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.IndirectNode
import constructit.core.LineValue
import constructit.core.PointValue
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Riders detach uniformly** (OP-16's re-pointable view applied to OP-4 case b / OP-20's riders).
 *
 * *Make absolute* worked on a point that had been **dragged** onto a curve — that point still published its
 * own `SourceNode`, so unbinding it handed its coordinates back — and **refused** the very same point created
 * by the snap or by the point-on-line / point-on-circle tools, whose element published the *derived* on-curve
 * node itself: there was no literal to give back and no way to rewire the consumers (OP-5 forbids rewriting an
 * input list). Two riders that look and behave identically, one of them a dead end.
 *
 * The fix is the substrate OP-16 already has: a rider is published through an [IndirectNode], so freeing it is
 * a **re-point** — the view stops naming the on-curve node and names a free source at the position the point
 * has right now. Nothing moves at the moment of the change, and everything built on the point (a perpendicular
 * through it, a fillet, an arrayed copy) follows the point rather than the curve from then on, with no input
 * list rewired.
 */
class RiderDetachTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
        steps: Int = 4,
    ) {
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(from))
        for (i in 1..steps) pointerMove(camera.worldToScreen(from + (to - from) * (i.toDouble() / steps)))
        pointerUp(camera.worldToScreen(to))
    }

    private fun pos(el: Element): Vec2 = ((Evaluator().eval(el.ref.node) as EvalResult.Ok).value as PointValue).p

    private fun named(
        doc: Document,
        name: String,
    ): Element = doc.elements.first { doc.nameOf(it) == name }

    /** A horizontal segment `e1 → e2` (`e3`) with a rider on it at 30 mm, made by the point-on-line tool. */
    private fun ridden(): Editor {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 20.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 20.0))
        ed.setTool(Tools.POINT_ON_LINE)
        ed.click(Vec2(30.0, 6.0))
        ed.setTool(Tools.SELECT)
        return ed
    }

    // ---- the report: a tool-created rider is freed where it stands, and then drags free ----

    @Test
    fun aToolCreatedRiderIsFreedWhereItStandsAndThenDragsAnywhere() {
        val ed = ridden()
        val rider = named(ed.doc, "e4")
        assertEquals(ElementKind.ON_CURVE, rider.kind)
        assertTrue(rider.ref.node is IndirectNode, "a rider is published through a re-pointable view (OP-16)")
        val was = pos(rider)

        assertTrue(ed.doc.makeAbsolute(rider), "got: ${ed.doc.note}")
        assertEquals(was, pos(rider), "nothing moves at the moment of the change")
        assertEquals(ElementKind.POINT, rider.kind, "it is an ordinary free point now")
        assertEquals(null, ed.doc.riderOf(rider), "and it rides nothing")
        assertEquals(listOf("x", "y"), rider.handle!!.fields().map { it.label }, "two coordinates, which is its freedom")
        assertTrue(ed.doc.note!!.contains("keeps its position"), "got: ${ed.doc.note}")

        // it leaves the line — the thing it could not do before
        ed.drag(was, Vec2(40.0, 60.0))
        assertClose(pos(rider).x, 40.0, 1e-9)
        assertClose(pos(rider).y, 60.0, 1e-9, "off the carrier entirely")

        // …and dragging the host no longer carries it
        val here = pos(rider)
        ed.drag(Vec2(100.0, 20.0), Vec2(100.0, 80.0))
        assertEquals(here, pos(rider), "the carrier is nothing to it any more")
    }

    /** The other rider form: an angle about a circle's centre (OP-20's third form). */
    @Test
    fun aRiderOnACircleDetachesTheSameWay() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.CIRCLE_R)
        for (c in "50") ed.key(c.toString())
        ed.key("Enter")
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.POINT_ON_CIRCLE)
        ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.SELECT)
        val rider = ed.doc.elements.last { it.kind == ElementKind.ON_CURVE }
        val was = pos(rider)
        assertTrue(ed.doc.makeAbsolute(rider), "got: ${ed.doc.note}")
        assertEquals(was, pos(rider))
        ed.drag(was, Vec2(10.0, 10.0))
        assertClose((pos(rider) - Vec2(10.0, 10.0)).length(), 0.0, 1e-9, "free of the circle")
    }

    /** The point that always could — asserted beside the one that could not, since the promise is uniformity. */
    @Test
    fun theDragAttachedRiderStillDetachesAndBothReadAlike() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.POINT)
        ed.click(Vec2(30.0, 20.0))
        val free = ed.doc.elements.last { it.kind == ElementKind.POINT }
        ed.drag(Vec2(30.0, 20.0), Vec2(30.0, 0.0)) // onto the segment: the magnet attaches it
        assertEquals(ElementKind.ON_CURVE, free.kind, "attached by dragging: ${ed.statusHint}")

        val was = pos(free)
        assertTrue(ed.doc.makeAbsolute(free))
        assertEquals(was, pos(free))
        assertEquals(ElementKind.POINT, free.kind)
        assertEquals(listOf("x", "y"), free.handle!!.fields().map { it.label })
    }

    /** A second *Make absolute* on a freed rider says it is already free rather than refusing obscurely. */
    @Test
    fun aFreedRiderIsSimplyAFreePointAfterwards() {
        val ed = ridden()
        val rider = named(ed.doc, "e4")
        assertTrue(ed.doc.makeAbsolute(rider))
        assertFalse(ed.doc.makeAbsolute(rider))
        assertTrue(ed.doc.note!!.contains("already a free point"), "got: ${ed.doc.note}")

        // and it is a free point in every sense: it can be welded onto another point, and freed again
        val other = named(ed.doc, "e1")
        assertTrue(ed.doc.weld(rider, other), "a freed rider is weldable")
        assertEquals(pos(other), pos(rider))
        assertTrue(ed.doc.makeAbsolute(rider))
        assertEquals(ElementKind.POINT, rider.kind)
    }

    /** A path corner still has nothing to hand back, and says so — the junction machinery is untouched. */
    @Test
    fun anOrthoCornerIsStillDerivedByTheConstruction() {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        ed.click(Vec2(50.0, 40.0))
        ed.key("Enter")
        val corner = ed.doc.orthoPaths.single().vertices[1].let { v -> ed.doc.elementFor(v.ref)!! }
        assertFalse(ed.doc.makeAbsolute(corner), "a corner is a shared coordinate, not a literal of its own")
        assertTrue(ed.doc.note!!.contains("derived by the construction"), "got: ${ed.doc.note}")
    }

    // ---- the consumer audit: every reader of rider identity, through the indirection ----

    @Test
    fun everythingBuiltOnTheRiderFollowsThePointAfterwards() {
        val ed = ridden()
        val rider = named(ed.doc, "e4")
        // a perpendicular to the host through the rider — the wheel's own construction, in miniature
        // (the tool takes the line first, then the point: Tools.PERPENDICULAR's slots)
        ed.setTool(Tools.PERPENDICULAR)
        ed.click(Vec2(80.0, 16.0))
        ed.click(pos(rider))
        val perp = ed.doc.elements.last { it.kind == ElementKind.LINE }

        assertTrue(ed.doc.makeAbsolute(rider))
        // written through the point's own handle rather than by a canvas drag, because the perpendicular runs
        // through the point and a pick there is a cycle question, not a rider question (PickCycleTest owns that)
        val to = Vec2(30.0, 60.0)
        ed.doc.moveFreePoint(rider, to)
        assertEquals(to, pos(rider))
        val l = ((Evaluator().eval(perp.ref.node) as EvalResult.Ok).value as LineValue).line
        assertClose((l.origin - to).dot(l.dir.perp()), 0.0, 1e-9, "the perpendicular goes with the point")
    }

    /** The gesture-time compensation registry (OP-20): a freed rider is no longer in it. */
    @Test
    fun aFreedRiderIsNoLongerCompensatedWhenTheHostTurns() {
        val ed = ridden()
        assertEquals(1, ed.doc.riderAnchors().size, "a distance along a slanted carrier is compensated")
        val rider = named(ed.doc, "e4")
        assertTrue(ed.doc.makeAbsolute(rider))
        assertTrue(ed.doc.riderAnchors().isEmpty(), "freed, it has no parameter along the carrier to correct")
    }

    /** A placement (OP-16) sees a freed rider as the free point it now is, and carries it rigidly. */
    @Test
    fun aFreedRiderIsAnOrdinaryFreePointToAGroupPlacement() {
        val ed = ridden()
        val rider = named(ed.doc, "e4")
        assertTrue(ed.doc.makeAbsolute(rider))
        val members = listOf(named(ed.doc, "e1"), named(ed.doc, "e2"), named(ed.doc, "e3"), rider)
        val g = assertNotNull(ed.doc.createGroup("fig", members))
        val a = ed.doc.analysePlacement(g)
        assertEquals(3, a.candidates.size, "three free points now, the freed rider included")
        assertTrue(a.conflicts.isEmpty(), "and nothing outside it holds them")
        val was = pos(rider)
        assertTrue(ed.placeGroup(g), "got: ${ed.statusHint}")
        assertTrue(ed.doc.isFramed(rider), "its position is frame-relative like any other member point")
        assertEquals(was, pos(rider), "a placement moves nothing")
        // and the whole figure moves as one, the freed rider with it: the frame is the only thing written
        val origin = ((Evaluator().eval(g.frameNode!!) as EvalResult.Ok).value as constructit.core.FrameValue).origin
        g.frameHandle!!.drag(origin + Vec2(0.0, 25.0), Evaluator())
        assertClose((pos(rider) - (was + Vec2(0.0, 25.0))).length(), 0.0, 1e-9, "it rides the frame like a member point")
    }

    /**
     * **The whole progression, one affordance at a time** (OP-4 case b): a rider *measured from a base of its
     * carrier* → *riding the world* → *free of the curve*. Each step moves nothing and each is invertible.
     */
    @Test
    fun makeAbsoluteWalksBackOneReParameterizationAtATime() {
        val ed = ridden()
        val rider = named(ed.doc, "e4")
        // measure it from an end of its carrier first (the shared-carrier reading of Make relative)
        ed.setTool(Tools.MAKE_RELATIVE)
        ed.click(pos(rider))
        ed.click(Vec2(0.0, 0.0))
        assertTrue(ed.doc.riderOf(rider)!!.carrierRelative, "measured from e1: ${ed.statusHint}")
        val was = pos(rider)

        assertTrue(ed.doc.makeAbsolute(rider), "step one: it is measured from the world again")
        assertEquals(was, pos(rider))
        assertEquals(ElementKind.ON_CURVE, rider.kind, "still riding its host")
        assertFalse(ed.doc.riderOf(rider)!!.carrierRelative)

        assertTrue(ed.doc.makeAbsolute(rider), "step two: it is off the host")
        assertEquals(was, pos(rider))
        assertEquals(ElementKind.POINT, rider.kind)
        assertEquals(null, ed.doc.riderOf(rider))
    }

    /** Deleting a freed rider takes the step that freed it, so the remaining script still loads. */
    @Test
    fun deletingAFreedRiderTakesTheStepThatFreedIt() {
        val ed = ridden()
        val rider = named(ed.doc, "e4")
        assertTrue(ed.doc.makeAbsolute(rider))
        ed.setTool(Tools.SELECT)
        ed.click(pos(rider))
        assertTrue(ed.deleteSelection(), "got: ${ed.statusHint}")
        val text = DocumentFormat.save(ed.doc)
        assertTrue(!text.contains("absolute"), "the step went with the element it named: $text")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "and what is left still replays")
    }

    // ---- persistence (OP-18): the freed position is state, so the step restates it ----

    @Test
    fun aFreedRiderRoundTripsWhereItWasDraggedTo() {
        val ed = ridden()
        val rider = named(ed.doc, "e4")
        assertTrue(ed.doc.makeAbsolute(rider))
        ed.drag(pos(rider), Vec2(70.0, 55.0))

        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("absolute e4 dofs="), "the step restates the freed coordinates: $text")
        val fresh = DocumentFormat.load(text)
        val reloaded = named(fresh, "e4")
        assertEquals(ElementKind.POINT, reloaded.kind, "it comes back free")
        assertClose((pos(reloaded) - Vec2(70.0, 55.0)).length(), 0.0, 1e-9, "and where it was left")
        assertEquals(text, DocumentFormat.save(fresh), "save -> load -> save is byte-equal")
    }

    /** Undo puts the rider back on its curve, which is the same replay path. */
    @Test
    fun undoPutsTheRiderBackOnItsHost() {
        val ed = ridden()
        val rider = named(ed.doc, "e4")
        val was = pos(rider)
        ed.setTool(Tools.MAKE_ABSOLUTE)
        ed.click(was)
        assertEquals(ElementKind.POINT, named(ed.doc, "e4").kind, "freed by the tool: ${ed.statusHint}")
        assertTrue(ed.undo())
        val back = named(ed.doc, "e4")
        assertEquals(ElementKind.ON_CURVE, back.kind, "and it rides its host again")
        assertClose((pos(back) - was).length(), 0.0, 1e-9)
    }

    // ---- the reported drawing (GitHub #22): the wheel's rider on its perpendicular bisector ----

    /**
     * **The user's file, verbatim** (the six-spoke wheel of the format-1 report — `wheel_old.cit`). Its `e18`
     * rides the perpendicular bisector `e17`, and a whole figure hangs off it: the perpendicular `e19` through
     * it, the fillets built on that, and a six-fold circular array of the lot. Freeing `e18` used to be
     * refused; it now moves nothing at that instant, drags free afterwards, and every consumer — the
     * perpendicular, the fillets, the arrayed copies — keeps working.
     */
    @Test
    fun theReportedWheelsRiderIsFreedWithoutMovingAnythingAndKeepsItsConsumers() {
        val doc = DocumentFormat.load(wheel)
        val rider = named(doc, "e18")
        assertEquals(ElementKind.ON_CURVE, rider.kind, "the rider on the perpendicular bisector")
        val perp = named(doc, "e19")
        // the array is instance-major over 11 members, so the first instance's copy of `e18` is `e56`
        val copy = named(doc, "e56")
        val before = doc.elements.associateWith { valid(it) }
        val positions = doc.elements.filter { it.isPoint }.associateWith { pos(it) }
        val was = pos(rider)

        assertTrue(doc.makeAbsolute(rider), "got: ${doc.note}")

        assertEquals(was, pos(rider), "nothing moves at the moment of the change")
        for ((el, p) in positions) assertEquals(p, pos(el), "nor does anything else: ${doc.nameOf(el)}")
        for ((el, ok) in before) {
            if (ok) assertTrue(valid(el), "${doc.nameOf(el)} was valid before and must still be")
        }

        // now it drags where the curve never let it go, and the figure follows
        val copyWas = pos(copy)
        val to = was + Vec2(12.0, -7.0)
        doc.moveFreePoint(rider, to)
        assertEquals(to, pos(rider))
        val l = ((Evaluator().eval(perp.ref.node) as EvalResult.Ok).value as LineValue).line
        assertClose((l.origin - to).dot(l.dir.perp()), 0.0, 1e-9, "the perpendicular through it went along")
        // the array is `rotate(el, centre, 60°·k)`, so a copy of the rider moves by the *turned* delta
        val d = to - was
        val a60 = 2.0 * kotlin.math.PI / 6.0
        val expected = Vec2(d.x * cos(a60) - d.y * sin(a60), d.x * sin(a60) + d.y * cos(a60))
        assertClose((pos(copy) - copyWas - expected).length(), 0.0, 1e-6, "the arrayed copy moved with it, turned into its own spoke")
        for ((el, ok) in before) {
            if (ok) assertTrue(valid(el), "${doc.nameOf(el)} still computes after the drag")
        }

        // and the file carries the change: one step, and a byte-stable round trip at format 2
        val text = DocumentFormat.save(doc)
        assertTrue(text.contains("absolute e18 dofs="), "the freed position is state: the step restates it")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save -> load -> save is byte-equal")
    }

    /** Whether [el] computes — the audit's question, asked of every element before and after. */
    private fun valid(el: Element): Boolean = Evaluator().eval(el.ref.node) is EvalResult.Ok

    private val wheel =
        """
constructit 1
param "r" = 122mm
point -6,3.5 -> e1
tool circleR pts=e1 clicks=-5.25,2.5 scalar="r" -> e2
pointoncurve e2 -12.02973535129569,125.35090189076706 dofs=118.46225976403586deg -> e3
tool segment pts=e1,e3 clicks=-5.75,3.75;-12,124.75 -> e4
param "d" = 10mm
tool parallelat els=e4 clicks=-7,30.5;0,32.25 scalar="d" -> e5
tool parallelat els=e4 clicks=-8,32.75;-15.75,32.75 scalar="d" -> e6
group "strebe" els=e6,e5
tool arraycircular pts=e1 els=e5 clicks=2.5,32.25;-5.75,4.75 count=6 -> e7,e8,e9,e10,e11
tool intersect els=e5,e2 clicks=-1.5,116.25;8.5,124.25 -> e12,e13
tool intersect els=e8,e2 clicks=81.5,71.75;84.5,85.5 -> e14,e15
tool arccs pts=e1,e14,e13 clicks=-5,3;91.75,77.25;-0.5,126.5 -> e16
tool perpbis pts=e13,e14 clicks=-2.75,125.25;89.75,77 -> e17
pointoncurve e17 14.118741663069027,42.702264910197286 dofs=52.86964276686915mm -> e18
tool perp pts=e18 els=e17 clicks=14.25,43.25;15,42.25 -> e19
tool intersect els=e5,e19 clicks=1.25,62.25;6.5,49.75 -> e20
tool intersect els=e19,e8 clicks=23,41.5;37.75,43.25 -> e21
tool segment pts=e14,e21 clicks=91,77.75;29.25,39 -> e22
tool segment pts=e21,e20 clicks=29.5,37.75;3.25,53.25 -> e23
tool segment pts=e20,e13 clicks=3.25,53.25;-1.25,125 -> e24
param "f" = 14mm
tool fillet els=e5,e19 clicks=1,70.5;7.75,49.25 scalar="f" -> e25
tool fillet els=e23,e22 clicks=22.5,41.75;41,45.25 scalar="f" -> e26
tool keypoints els=e25 clicks=3.75,61.5 -> e27,e28,e29
tool keypoints els=e26 clicks=34,44.75 -> e30,e31,e32
tool segment pts=e29,e31 clicks=9.5,47.5;21,40.75 -> e33
tool fillet els=e24,e16 clicks=1.582395332810995,52.404821316619085;22.863387068348143,120.58663949843712 scalar="f" -> e34
tool fillet els=e16,e8 clicks=80.92123830801745,87.94201139926363;76.9956184733067,69.14035850670169 scalar="f" -> e35
tool keypoints els=e34 clicks=3.64851103529033,120.3800279281892 -> e36,e37,e38
tool keypoints els=e35 clicks=83.19396558074472,78.4378791678587 -> e39,e40,e41
hide els=e16
hide els=e24
hide els=e22
hide els=e23
hide els=e30
hide els=e27
hide els=e39
hide els=e36
hide els=e2
tool segment pts=e38,e40 clicks=16.251816820414273,123.89242462240406;79.26834574603399,90.00812710174297 -> e42
tool segment pts=e41,e32 clicks=76.9956184733067,69.14035850670169;33.40057715099275,40.21473867199101 -> e43
tool segment pts=e28,e37 clicks=0.9625606220671945,57.57011057281742;-2.343224501899741,109.63622627529665 -> e44
show els=e16
hide els=e42
tool arccs pts=e1,e40,e38 clicks=-5.85562119611461,3.231267597610915;80.09479202702572,90.62796181248677;15.01214739892667,124.7188709033958 -> e45
hide els=e16
group "hole" els=e43,e26,e33,e25,e44,e34,e45,e35,e1,e3,e18
tool arraycircular pts=e1 els=e43,e26,e33,e25,e44,e34,e45,e35,e1,e3,e18 clicks=40.116279630331576,116.70854032488342;-4.983720369668341,4.508540324883623 count=6 -> e46,e47,e48,e49,e50,e51,e52,e53,e54,e55,e56,e57,e58,e59,e60,e61,e62,e63,e64,e65,e66,e67,e68,e69,e70,e71,e72,e73,e74,e75,e76,e77,e78,e79,e80,e81,e82,e83,e84,e85,e86,e87,e88,e89,e90,e91,e92,e93,e94,e95,e96,e97,e98,e99,e100
""".trimStart()
}
