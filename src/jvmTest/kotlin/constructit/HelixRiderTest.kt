package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.Point3Value
import constructit.dsl.Path3Ref
import constructit.dsl.SolidRef
import constructit.dsl.path3
import constructit.dsl.solid
import constructit.editor.Camera3
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.PlanePerspective
import constructit.editor.Tools
import constructit.geom.Curve3Element
import constructit.geom.Handedness
import constructit.geom.Plane3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.deg
import constructit.units.mm
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A curve in space says what it is defined by, and a point can ride a coil** (OP-26, the queue's own
 * design — session 53).
 *
 * Two halves of one package, and each is the 3D reading of something the 2D drawing has had for a long time:
 *
 * - **Key points.** `Document.extractPoints` used to fall through to nothing for a curve in space, so a coil
 *   had no end point to build on. Now every run hands back its **start** and **end**, and a helix its
 *   **centre** as well — the arc's own triple. They are *accessors on the curve node*, not copies, so a
 *   retyped pitch or a dragged waypoint moves them; and one route serves the coil, the curve through points,
 *   the connect, the combined view and an imported wireframe.
 * - **A rider whose angle is not modular.** The point-on-a-circle, one dimension up: 450° *is* the second
 *   winding, one whole pitch above 90°, and that unboundedness is the whole content of the parameter. The
 *   2D/3D split is in the **pick** — the plan can only state the first winding, the 3D view's ray knows which
 *   one it hit — and a drag follows the same asymmetry.
 */
class HelixRiderTest {
    private val wPx = 800.0
    private val hPx = 600.0
    private val plan = Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    /** A coil about a point placed at [at], through the ordinary gestures. */
    private fun coilAt(
        ed: Editor,
        at: Vec2,
        radius: String = "20",
        pitch: String = "12",
        turns: String = "3",
        left: Boolean = false,
    ): Element {
        ed.setTool(Tools.POINT)
        ed.click(at)
        ed.setTool(if (left) Tools.HELIX_LEFT else Tools.HELIX)
        ed.type(radius)
        ed.type(pitch)
        ed.type(turns)
        ed.click(at)
        return assertNotNull(
            ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE },
            "the coil was built: ${ed.statusHint}",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun helixOf(el: Element): Curve3Element.Helix3 =
        Evaluator().path3(el.ref as Path3Ref).elements.single() as Curve3Element.Helix3

    @Suppress("UNCHECKED_CAST")
    private fun at(el: Element): Vec3? = (Evaluator().eval(el.ref.node) as? EvalResult.Ok)?.let { (it.value as? Point3Value)?.p }

    private fun assertVec(
        actual: Vec3?,
        expected: Vec3,
        msg: String,
    ) {
        val a = assertNotNull(actual, "$msg — the point has no value")
        assertTrue((a - expected).length() <= 1e-12, "$msg (was $a, wanted $expected)")
    }

    /** The key points of [el], through the *Key points* tool, clicked at [where] on its drawn projection. */
    private fun keyPointsOf(
        ed: Editor,
        el: Element,
        where: Vec2,
    ): List<Element> {
        val before = ed.doc.elements.toSet()
        ed.setTool(Tools.KEY_POINTS)
        ed.click(where)
        return ed.doc.elements.filter { it !in before }
    }

    /** The rider the *Point on helix* tool makes from a click at [where], in whichever view [ed] is pointing. */
    private fun riderAt(
        ed: Editor,
        where: Vec2,
    ): Element? = riderAtScreen(ed, ed.pointing?.toScreen(where) ?: ed.camera.worldToScreen(where))

    /** The same gesture, aimed at a **screen** position — what a 3D pick needs, since a lifted point has one. */
    private fun riderAtScreen(
        ed: Editor,
        s: Vec2,
    ): Element? {
        ed.setTool(Tools.POINT_ON_HELIX)
        ed.pointerMove(s)
        ed.pointerDown(s)
        ed.pointerUp(s)
        return ed.doc.elements.lastOrNull { it.kind == ElementKind.ON_CURVE }
    }

    /** Type an angle into the rider's own field — the panel's route, which is the drag's write (OP-13). */
    private fun setAngle(
        rider: Element,
        deg: Double,
    ) {
        val field = assertNotNull(rider.handle?.fields()?.firstOrNull { it.label == "angle" }, "the rider has an angle field")
        assertTrue(field.writable, "and it is the point's own freedom")
        field.write(deg.deg)
    }

    /** The rider's own angle, in degrees — the one parameter it owns. */
    private fun angleOf(
        ed: Editor,
        rider: Element,
    ): Double = assertNotNull(ed.doc.restatedRiderParam(rider, Evaluator()), "the rider has a parameter").deg

    // ---- 1: the key points of a coil ----

    /**
     * **A coil's key points are its centre, its start and its end** — the arc's own triple, at the exact
     * analytic positions, for both handednesses; and every one of them **follows the coil**: retype the pitch,
     * retype the turn count, drag the axis point, and the points move because they are accessors on the curve.
     */
    @Test
    fun theKeyPointsOfACoilAreItsCentreStartAndEndAndTheyFollowIt() {
        for (left in listOf(false, true)) {
            val ed = Editor()
            val hand = if (left) Handedness.LEFT else Handedness.RIGHT
            val coil = coilAt(ed, Vec2(10.0, 20.0), radius = "20", pitch = "12", turns = "2.25", left = left)
            assertEquals(hand, helixOf(coil).hand)

            val pts = keyPointsOf(ed, coil, Vec2(30.0, 20.0))
            assertEquals(3, pts.size, "centre, start, end for a ${hand.word} coil: ${ed.statusHint}")
            assertTrue(pts.all { it.inSpace && it.isPoint }, "each one is a point in space")

            val h = helixOf(coil)
            // the analytic triple: the axis point, the point beside it along the space's own x, and the end —
            // 2.25 turns is a quarter past the top, so the end stands 90° round (the way the coil turns) and
            // 2.25 pitches up
            assertVec(at(pts[0]), Vec3(10.0, 20.0, 0.0), "the centre of the ${hand.word} coil")
            assertVec(at(pts[1]), Vec3(30.0, 20.0, 0.0), "its start")
            assertVec(at(pts[2]), Vec3(10.0, 20.0 + hand.turnSign * 20.0, 27.0), "its end")
            // ...and the same numbers read off the value itself, so the accessors are the curve's own ends
            assertVec(at(pts[1]), h.start, "the start accessor is the run's start")
            assertVec(at(pts[2]), h.end, "the end accessor is the run's end")

            // retype the pitch: the end rises, the start and the centre do not
            val pitch = assertNotNull(ed.doc.scalars.first { it.name.startsWith("pitch") })
            ed.doc.setParameter(pitch, 20.0.mm)
            assertVec(at(pts[2]), Vec3(10.0, 20.0 + hand.turnSign * 20.0, 45.0), "the end followed the pitch")
            assertVec(at(pts[1]), Vec3(30.0, 20.0, 0.0), "while the start stayed where the coil starts")

            // retype the turn count: the end walks round and up
            val turns = assertNotNull(ed.doc.scalars.first { it.name.startsWith("turns") })
            ed.doc.setParameter(turns, constructit.units.Quantity.number(3.0))
            assertVec(at(pts[2]), Vec3(30.0, 20.0, 60.0), "three whole turns end above the start")

            // drag the axis point: all three follow, because the coil does
            val axis = ed.doc.elements.first { it.kind == ElementKind.POINT }
            ed.setTool(Tools.SELECT)
            val from = ed.camera.worldToScreen(Vec2(10.0, 20.0))
            ed.pointerMove(from)
            ed.pointerDown(from)
            val to = ed.camera.worldToScreen(Vec2(40.0, -10.0))
            ed.pointerMove(to)
            ed.pointerUp(to)
            assertEquals(axis, ed.selection ?: axis)
            assertVec(at(pts[0]), Vec3(40.0, -10.0, 0.0), "the centre went with the point")
            assertVec(at(pts[1]), Vec3(60.0, -10.0, 0.0), "and so did the start")
            assertVec(at(pts[2]), Vec3(60.0, -10.0, 60.0), "and the end")
        }
    }

    /**
     * **The key points of a coil are ordinary points of the drawing**: one undo takes the whole gesture, they
     * are pickable in the plan where they project, and `save → load → save` is byte-equal.
     */
    @Test
    fun aCoilsKeyPointsAreOrdinaryPointsOfTheDrawing() {
        val ed = Editor()
        val coil = coilAt(ed, Vec2(0.0, 0.0))
        val before = ed.doc.elements.size
        val pts = keyPointsOf(ed, coil, Vec2(20.0, 0.0))
        assertEquals(3, pts.size, ed.statusHint)
        assertTrue(ed.doc.steplessElements().isEmpty(), "every one of them has a step (OP-27)")

        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.lines().any { it.startsWith("tool keypoints") }, "the gesture is in the script: $once")
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save → load → save is byte-equal")
        val back = DocumentFormat.load(once)
        assertEquals(3, back.elements.count { it.inSpace && it.kind == ElementKind.DERIVED_POINT }, "and they come back as points in space")

        // the plan draws them where they project, so a click reaches the end point standing 36 mm up
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(20.0, 0.0))
        assertTrue(ed.selection?.inSpace == true, "a click in the plan reaches a point in space: ${ed.statusHint}")

        assertTrue(ed.undo(), "one undo")
        assertEquals(before, ed.doc.elements.size, "takes the whole extraction back")
    }

    // ---- 2: the same route, for every other kind of run ----

    /**
     * **Start and end, for a run of any shape** — the claim that makes this general from the first slice: a
     * curve through points, a smooth one, a connect between two runs and an imported wireframe all hand back
     * the two points that bound them, and each one follows its own curve.
     */
    @Test
    fun everyKindOfRunHandsBackItsStartAndEnd() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        for (p in listOf(Vec2(0.0, 0.0), Vec2(40.0, 0.0), Vec2(40.0, 30.0))) ed.click(p)
        ed.setTool(Tools.CURVE3)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(40.0, 30.0))
        ed.key("Enter")
        val run = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)

        val pts = keyPointsOf(ed, run, Vec2(20.0, 0.0))
        assertEquals(2, pts.size, "a run with no centre hands back the pair: ${ed.statusHint}")
        assertVec(at(pts[0]), Vec3(0.0, 0.0, 0.0), "the run's start")
        assertVec(at(pts[1]), Vec3(40.0, 30.0, 0.0), "and its end")

        // it follows: drag the last waypoint and the end point goes with it
        ed.setTool(Tools.SELECT)
        val from = ed.camera.worldToScreen(Vec2(40.0, 30.0))
        ed.pointerMove(from)
        ed.pointerDown(from)
        val to = ed.camera.worldToScreen(Vec2(60.0, 50.0))
        ed.pointerMove(to)
        ed.pointerUp(to)
        assertVec(at(pts[1]), Vec3(60.0, 50.0, 0.0), "the end accessor followed the point the run ends at")

        // a **closed** run: its last piece hands over to its first, so start and end are one place — and it
        // hands back one point rather than two that are equal (the count is read off the closure, which is
        // structure, not off two positions happening to agree)
        val ed2 = Editor()
        ed2.setTool(Tools.POINT)
        for (p in listOf(Vec2(0.0, 0.0), Vec2(40.0, 0.0), Vec2(20.0, 30.0))) ed2.click(p)
        ed2.setTool(Tools.CURVE3_SMOOTH)
        ed2.click(Vec2(0.0, 0.0))
        ed2.click(Vec2(40.0, 0.0))
        ed2.click(Vec2(20.0, 30.0))
        ed2.click(Vec2(0.0, 0.0))
        val loop = assertNotNull(ed2.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed2.statusHint)
        assertTrue(Evaluator().path3(loop.ref as Path3Ref).closed, "the gesture closed it")
        val one = keyPointsOf(ed2, loop, midOf(ed2, loop))
        assertEquals(1, one.size, "a closed run has one distinguished end point: ${ed2.statusHint}")
        assertTrue(ed2.statusHint.contains("closed"), "and the status says why: ${ed2.statusHint}")
        assertVec(at(one[0]), Vec3(0.0, 0.0, 0.0), "where the loop begins and ends")
    }

    /**
     * **A connect's key points are its own two ends** — a *derived* run, whose accessors therefore follow both
     * curves it was built from. The point of asking it here is that nothing in the extraction knows what kind
     * of run it was handed.
     */
    @Test
    fun aDerivedRunHandsBackItsEndsToo() {
        val ed = Editor()
        val a = coilAt(ed, Vec2(0.0, 0.0), radius = "20", pitch = "12", turns = "1")
        val b = coilAt(ed, Vec2(120.0, 0.0), radius = "20", pitch = "12", turns = "1")
        ed.setTool(Tools.CONNECT)
        ed.click(Vec2(20.0, 0.0))
        ed.click(Vec2(140.0, 0.0))
        val join = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)
        assertTrue(join !== a && join !== b)

        val ends = Evaluator().path3(join.ref as Path3Ref).let { it.start!! to it.end!! }
        val pts = keyPointsOf(ed, join, midOf(ed, join))
        assertEquals(2, pts.size, "the join's two ends: ${ed.statusHint}")
        assertVec(at(pts[0]), ends.first, "the start of the joining piece")
        assertVec(at(pts[1]), ends.second, "and its end")

        // and they ride both coils: retype the first coil's radius and the join's own start moves
        val radius = ed.doc.scalars.first { it.name.startsWith("radius") }
        ed.doc.setParameter(radius, 26.0.mm)
        val moved = Evaluator().path3(join.ref as Path3Ref).start!!
        assertVec(at(pts[0]), moved, "the accessor is the run's start, whatever the run has become")
    }

    /** A point on the plan projection of [el] a click can reach it by — its own drawn polyline's midpoint. */
    private fun midOf(
        ed: Editor,
        el: Element,
    ): Vec2 {
        val path = Evaluator().path3(el.ref as Path3Ref)
        val pts = constructit.geom.Curves3.polyline(path)
        return plan.toLocal(pts[pts.size / 2])
    }

    // ---- 3: the rider, and the angle that is not modular ----

    /**
     * **90°, 450° and 810° on a three-turn coil are three different places**, and the middle one is exactly
     * **one whole pitch above** the first: the assertion that proves the parameter is not reduced into a
     * circle's worth of angle anywhere.
     */
    @Test
    fun aRiderAt90At450AndAt810IsExactAndTheAngleIsNotModular() {
        for (left in listOf(false, true)) {
            val ed = Editor()
            val hand = if (left) Handedness.LEFT else Handedness.RIGHT
            val coil = coilAt(ed, Vec2(0.0, 0.0), radius = "20", pitch = "12", turns = "3", left = left)
            val riders =
                listOf(90.0, 450.0, 810.0).map { deg ->
                    val r = assertNotNull(ed.doc.pointOnHelix(coil, Vec2(20.0, 0.0), null, deg.deg), "a rider at $deg")
                    ed.doc.elements.last { it.kind == ElementKind.ON_CURVE } to r
                }
            for ((i, entry) in riders.withIndex()) {
                val expected = Vec3(0.0, hand.turnSign * 20.0, 3.0 + i * 12.0)
                assertVec(at(entry.first), expected, "the ${hand.word} rider at ${90 + i * 360}°")
            }
            // the one that says it plainly: a whole turn along the coil is a whole pitch along the axis
            val z = riders.map { assertNotNull(at(it.first)).z }
            assertClose(z[1] - z[0], 12.0, 1e-12, "450° is one pitch above 90°")
            assertClose(z[2] - z[1], 12.0, 1e-12, "and 810° one more")
            // ...while all three stand at the same bearing, which is what makes them one *winding* apart
            for (r in riders) assertClose(assertNotNull(at(r.first)).x, 0.0, 1e-12, "the same bearing")
        }
    }

    /**
     * **An angle past the end of the coil is a value condition, so it names itself and heals** (OP-3) — never
     * a refused gesture, never a clamp. Below zero likewise: the angle counts *along* the coil, so a negative
     * one is off its near end.
     */
    @Test
    fun anAnglePastTheEndNamesItselfAndHealsWhenTheCoilGrows() {
        val ed = Editor()
        val coil = coilAt(ed, Vec2(0.0, 0.0), radius = "20", pitch = "12", turns = "2")
        val rider = riderAt(ed, Vec2(20.0, 0.0))
        assertNotNull(rider, ed.statusHint)

        // 900° is past a two-turn coil (720°): the gesture is not refused, the *node* says so
        setAngle(rider, 900.0)
        val bad = Evaluator().eval(rider.ref.node)
        assertTrue(bad is EvalResult.Invalid, "past the end is invalid, not clamped: $bad")
        val why = (bad as EvalResult.Invalid).reason
        assertTrue(why.contains("900"), "and it names the angle: $why")
        assertTrue(why.contains("720"), "and where the coil ends: $why")
        assertTrue(why.contains("turn count"), "and what to do about it: $why")
        assertNull(at(rider), "so the point has no position while it is off the coil")

        // ...and it heals when the coil is made longer, with nothing else touched
        val turns = ed.doc.scalars.first { it.name.startsWith("turns") }
        ed.doc.setParameter(turns, constructit.units.Quantity.number(3.0))
        assertVec(at(rider), Vec3(-20.0, 0.0, 30.0), "the point came back where it said it was: 900° = 2.5 turns")

        // a negative angle is off the near end, and says which end
        setAngle(rider, -30.0)
        val low = Evaluator().eval(rider.ref.node)
        assertTrue(low is EvalResult.Invalid, "below zero is invalid too: $low")
        assertTrue((low as EvalResult.Invalid).reason.contains("near end"), "and says which end: ${low.reason}")
        setAngle(rider, 30.0)
        assertNotNull(at(rider), "and it heals from that too")
    }

    // ---- 5: the pick, in each view ----

    /**
     * **A plan click can only state the first winding** — however far round the coil the click lands, because
     * every winding is drawn on the same circle there — **and typing reaches any winding afterwards** (OP-13).
     */
    @Test
    fun aPlanClickStatesTheFirstWindingAndTypingReachesAnyOther() {
        val ed = Editor()
        val coil = coilAt(ed, Vec2(0.0, 0.0), radius = "20", pitch = "12", turns = "3")
        for (bearing in listOf(0.0, 45.0, 90.0, 200.0, 359.0)) {
            val at = Vec2(20.0 * kotlin.math.cos(bearing * PI / 180.0), 20.0 * kotlin.math.sin(bearing * PI / 180.0))
            val rider = assertNotNull(riderAt(ed, at), "a click at $bearing°: ${ed.statusHint}")
            val angle = angleOf(ed, rider)
            assertTrue(angle >= 0.0 && angle < 360.0, "a plan click states the first winding, not $angle°")
            assertClose(angle, bearing, 1.0, "and it is the bearing the click named")
            assertTrue(ed.statusHint.contains("winding 1"), "and it says so: ${ed.statusHint}")
        }
        // typing walks it up the spring: 720° is the third winding, and the point rises two pitches
        val rider = ed.doc.elements.last { it.kind == ElementKind.ON_CURVE }
        setAngle(rider, 720.0)
        assertVec(at(rider), Vec3(20.0, 0.0, 24.0), "720° is two whole turns up")
        assertEquals(coil, ed.doc.riderOf(rider)?.host, "and it is still riding the coil")
    }

    /**
     * **In the 3D view the pointer's ray knows the winding**, so a click on the second or third one yields an
     * angle above 360° directly — driven through the same door every 3D gesture uses ([Editor.pointing]).
     */
    @Test
    fun aClickInTheThreeDViewStatesTheWindingItHit() {
        val ed = Editor()
        val coil = coilAt(ed, Vec2(0.0, 0.0), radius = "20", pitch = "12", turns = "3")
        val h = helixOf(coil)
        // A low pose looking at the side of the coil: the windings are 12 mm apart there, and the sight line at
        // the bearing clicked below crosses the coil rather than grazing it — a view along the *tangent* is
        // genuinely ambiguous about which angle it means, and that is geometry, not picking.
        val cam = Camera3(target = Vec3(0.0, 0.0, 18.0), distance = 150.0, yaw = PI / 2.0, pitch = 0.15)
        ed.pointing = PlanePerspective(plan, cam, wPx, hPx)
        for (deg in listOf(90.0, 450.0, 810.0)) {
            val world = h.atAngle(deg * PI / 180.0)
            val screen = assertNotNull(screenOf(ed.pointing as PlanePerspective, world), "the coil is drawn at $deg°")
            val rider = assertNotNull(riderAtScreen(ed, screen), "a 3D click at $deg°: ${ed.statusHint}")
            val angle = angleOf(ed, rider)
            assertClose(angle, deg, 8.0, "the ray states the winding it hit")
            assertVec(at(rider), h.atAngle(angle * PI / 180.0), "and the point is on the coil there")
        }
        // the same click, in the plan, could only have said the first winding — the split is in the pick
        ed.pointing = null
        val flat = assertNotNull(riderAt(ed, plan.toLocal(h.atAngle(450.0 * PI / 180.0))), ed.statusHint)
        assertTrue(angleOf(ed, flat) < 360.0, "the plan reduced it to the first winding")
    }

    // ---- 6: the drag, which follows the same asymmetry ----

    /** **A drag in the 3D view crosses windings; a drag in the plan stays on the winding it is on.** */
    @Test
    fun aDragCrossesWindingsInThreeDAndKeepsItsWindingInThePlan() {
        val ed = Editor()
        val coil = coilAt(ed, Vec2(0.0, 0.0), radius = "20", pitch = "12", turns = "3")
        val h = helixOf(coil)
        val rider = assertNotNull(riderAt(ed, Vec2(20.0, 0.0)), ed.statusHint)

        // --- in the plan: from 450° (bearing 0 on the second winding) round to bearing 180°
        setAngle(rider, 450.0)
        ed.setTool(Tools.SELECT)
        val grab = ed.camera.worldToScreen(plan.toLocal(h.atAngle(450.0 * PI / 180.0)))
        ed.pointerMove(grab)
        ed.pointerDown(grab)
        assertEquals(rider, ed.selection ?: rider, "the rider took the grab: ${ed.statusHint}")
        val to = ed.camera.worldToScreen(Vec2(-20.0, 0.0))
        ed.pointerMove(to)
        ed.pointerUp(to)
        val planned = angleOf(ed, rider)
        assertTrue(planned in 360.0..720.0, "the plan drag stayed on the winding it was on, not $planned°")
        assertClose(planned, 540.0, 1.0, "and moved round it to the bearing the cursor named")

        // --- in the 3D view: the same rider, dragged from the second winding onto the third
        val cam = Camera3(target = Vec3(0.0, 0.0, 18.0), distance = 150.0, yaw = 0.0, pitch = 0.15)
        val proj = PlanePerspective(plan, cam, wPx, hPx)
        ed.pointing = proj
        setAngle(rider, 450.0)
        // grabbed where the coil is *drawn* in this view, which is where the pointer reaches it
        val grab3 = assertNotNull(screenOf(proj, h.atAngle(450.0 * PI / 180.0)))
        ed.pointerMove(grab3)
        ed.pointerDown(grab3)
        val target3 = assertNotNull(screenOf(proj, h.atAngle(810.0 * PI / 180.0)))
        ed.pointerMove(target3)
        ed.pointerUp(target3)
        val dragged = angleOf(ed, rider)
        assertTrue(dragged > 720.0, "the 3D drag crossed a winding: $dragged°")
        assertClose(dragged, 810.0, 10.0, "and landed where the pointer was aiming")
    }

    private fun screenOf(
        proj: PlanePerspective,
        world: Vec3,
    ): Vec2? = proj.toScreenLifted(plan.toLocal(world), plan.distanceTo(world))

    // ---- 7: the file ----

    /**
     * **`save → load → save` is byte-equal, and the reloaded rider is on the same winding** — the fixture is
     * an angle above 360°, which is the one a normalization anywhere in the round trip would destroy.
     */
    @Test
    fun theFileKeepsBothFeaturesAndTheWindingTheRiderIsOn() {
        val ed = Editor()
        val coil = coilAt(ed, Vec2(0.0, 0.0), radius = "20", pitch = "12", turns = "3")
        keyPointsOf(ed, coil, Vec2(20.0, 0.0))
        val rider = assertNotNull(riderAt(ed, Vec2(20.0, 0.0)), ed.statusHint)
        setAngle(rider, 810.0)
        ed.checkpoint()
        val here = assertNotNull(at(rider))

        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.lines().any { it.startsWith("tool ptonhelix") }, "the rider is in the script: $once")
        val dof =
            assertNotNull(
                Regex("dofs=([0-9.]+)deg").find(once)?.groupValues?.get(1)?.toDouble(),
                "the rider's own angle is restated: $once",
            )
        assertClose(dof, 810.0, 1e-9, "windings and all — a normalization anywhere would write 90 here")
        val back = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(back), "save → load → save is byte-equal")
        assertEquals(emptyList(), back.loadNotes, "and it needs no migration")

        val reloaded = back.elements.last { it.kind == ElementKind.ON_CURVE }
        assertClose(assertNotNull(back.restatedRiderParam(reloaded, Evaluator())).deg, 810.0, 1e-9, "the same angle")
        val there = assertNotNull((Evaluator().eval(reloaded.ref.node) as? EvalResult.Ok)?.let { (it.value as? Point3Value)?.p })
        assertTrue((here - there).length() <= 1e-12, "on the same winding of the same coil ($here vs $there)")
        assertEquals(3, back.elements.count { it.kind == ElementKind.DERIVED_POINT && it.inSpace }, "and the key points came back")
    }

    // ---- 8: composition ----

    /**
     * **Both halves compose with what the drawing already has**: a run in space between two key points of a
     * coil, a tube along the coil, a second coil that *starts at the rider* — and *Make absolute*, which frees
     * the rider where it stands. Each gesture is one undo.
     */
    @Test
    fun theyComposeWithEverythingAPointInSpaceIsLegalFor() {
        val ed = Editor()
        val coil = coilAt(ed, Vec2(0.0, 0.0), radius = "20", pitch = "12", turns = "3")
        val pts = keyPointsOf(ed, coil, Vec2(20.0, 0.0))
        assertEquals(3, pts.size, ed.statusHint)

        // a straight run in space between the coil's start and its end — clicked **in the 3D view**, which is
        // the view that can tell the two apart (in the plan they project onto the same place), and the key
        // points' nodes are *shared* so the chord follows the coil through every edit
        val cam = Camera3(target = Vec3(0.0, 0.0, 18.0), distance = 150.0, yaw = 0.0, pitch = 0.15)
        val proj = PlanePerspective(plan, cam, wPx, hPx)
        ed.pointing = proj
        ed.setTool(Tools.CURVE3)
        for (p in listOf(pts[1], pts[2])) {
            val where = assertNotNull(at(p), "the key point has a position")
            val s = assertNotNull(screenOf(proj, where), "and it is drawn in the 3D view")
            ed.pointerMove(s)
            ed.pointerDown(s)
            ed.pointerUp(s)
        }
        ed.key("Enter")
        ed.pointing = null
        val chord = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)
        assertTrue(chord !== coil, "a run of its own: ${ed.statusHint}")
        val chordPath = Evaluator().path3(chord.ref as Path3Ref)
        assertVec(chordPath.start, Vec3(20.0, 0.0, 0.0), "it starts at the coil's start")
        assertVec(chordPath.end, Vec3(20.0, 0.0, 36.0), "and ends at its end")
        assertTrue(chord.ref.node.inputs.any { it === pts[1].ref.node }, "the key point's node is shared, not copied")

        // a tube along the coil is unaffected by any of it, and still watertight. Clicked at bearing 90°,
        // because the chord above projects onto the plan as a single point at bearing 0 and would take the pick
        ed.setTool(Tools.TUBE)
        ed.type("3")
        ed.click(Vec2(0.0, 20.0))
        val tube = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID }, ed.statusHint)
        assertManifold(Evaluator().solid(tube.ref as SolidRef).mesh, "a tube on the coil")

        // a rider, then a **second coil starting at it**: a point in space is a legal input wherever one is
        val rider = assertNotNull(riderAt(ed, Vec2(0.0, 20.0)), ed.statusHint)
        setAngle(rider, 450.0)
        val second =
            assertNotNull(
                ed.doc.helixThrough(pts[0], rider, ed.doc.cx.const(6.0.mm), null, Handedness.RIGHT),
                "a coil that starts at the rider: ${ed.statusHint}",
            )
        val h2 = Evaluator().path3(second.ref as Path3Ref).elements.single() as Curve3Element.Helix3
        assertClose(h2.radius, 20.0, 1e-9, "its radius is how far the rider stands from the axis")
        // ...and it follows the rider: slide the rider round and the second coil's start goes with it
        setAngle(rider, 540.0)
        val moved = Evaluator().path3(second.ref as Path3Ref).elements.single() as Curve3Element.Helix3
        assertTrue((moved.start - h2.start).length() > 1.0, "the second coil started somewhere else")

        // *Make absolute*: the rider keeps its position and comes off the coil (OP-4)
        val was = assertNotNull(at(rider))
        assertTrue(ed.doc.makeAbsolute(rider), "Make absolute frees it: ${ed.doc.note}")
        assertVec(at(rider), was, "nothing moved at the moment of the change")
        assertNull(ed.doc.riderOf(rider), "it rides nothing now")
        assertEquals(ElementKind.HEIGHT_POINT, rider.kind, "what it was freed into is the pair of freedoms a point in space has")
        assertTrue(rider.hasFreeDof, "and it is draggable again")
        val after = DocumentFormat.save(ed.doc)
        assertEquals(after, DocumentFormat.save(DocumentFormat.load(after)), "and the freed rider round-trips")
        val reloaded = DocumentFormat.load(after).elements.last { it.kind == ElementKind.HEIGHT_POINT }
        val there = assertNotNull((Evaluator().eval(reloaded.ref.node) as? EvalResult.Ok)?.let { (it.value as? Point3Value)?.p })
        assertTrue((was - there).length() <= 1e-9, "in the very place it was freed ($was vs $there)")
    }

    /** One gesture, one undo — asked of each of the two new rows in turn. */
    @Test
    fun eachGestureIsOneUndo() {
        val ed = Editor()
        val coil = coilAt(ed, Vec2(0.0, 0.0))
        val base = ed.doc.elements.size
        keyPointsOf(ed, coil, Vec2(20.0, 0.0))
        assertEquals(base + 3, ed.doc.elements.size)
        val rider = assertNotNull(riderAt(ed, Vec2(20.0, 0.0)), ed.statusHint)
        assertEquals(base + 4, ed.doc.elements.size)
        assertTrue(ed.undo(), "undo the rider")
        assertEquals(base + 3, ed.doc.elements.size, "one step")
        assertTrue(ed.undo(), "undo the extraction")
        assertEquals(base, ed.doc.elements.size, "one step")
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE }, "and the coil is untouched")
        assertNotNull(rider)
        assertNotNull(coil)
    }

    // ---- 9 and 10: what is refused, by name ----

    /**
     * **A rider on a non-helix curve in space refuses by name and builds nothing** — the deliberate scope: a
     * parameter along a spline through points would re-anchor whenever those points moved, which is
     * `ALONG_LINE`'s own problem one dimension up. The refusal names the element and the alternative.
     */
    @Test
    fun aRiderOnANonHelixRunRefusesByNameAndBuildsNothing() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        for (p in listOf(Vec2(0.0, 0.0), Vec2(40.0, 0.0), Vec2(40.0, 30.0))) ed.click(p)
        ed.setTool(Tools.CURVE3)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(40.0, 30.0))
        ed.key("Enter")
        val run = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE })
        val before = ed.doc.elements.size
        val steps = ed.doc.journal.size

        ed.setTool(Tools.POINT_ON_HELIX)
        ed.click(Vec2(20.0, 0.0))
        assertEquals(before, ed.doc.elements.size, "nothing was built: ${ed.statusHint}")
        assertEquals(steps, ed.doc.journal.size, "and nothing was recorded")
        assertTrue(ed.statusHint.contains(ed.doc.nameOf(run)), "the refusal names the element: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("not a coil"), "and says what it is not: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("Station"), "and names the alternative: ${ed.statusHint}")
    }

    /**
     * **A linear dimension between points in space is refused by name**, because its number is measured in the
     * working plane and its graphic is drawn there — the scope DESIGN.md records as the extension it wants.
     */
    @Test
    fun aLinearDimensionOnAPointInSpaceRefusesByName() {
        val ed = Editor()
        val coil = coilAt(ed, Vec2(0.0, 0.0))
        val pts = keyPointsOf(ed, coil, Vec2(20.0, 0.0))
        val before = ed.doc.elements.size
        assertNull(ed.doc.linearDimension(pts[1], pts[2], Vec2(40.0, 0.0)), "no dimension is made")
        assertEquals(before, ed.doc.elements.size, "and nothing is left behind")
        val why = assertNotNull(ed.doc.takeNote())
        assertTrue(why.contains("point in space"), "the refusal says what it is: $why")
        assertTrue(why.contains(ed.doc.nameOf(pts[1])), "and names it: $why")
    }

    // ---- what a reviewer tries next: the placement, the delete, and the drawing ----

    /**
     * **A placed group carries a coil and its rider rigidly** (OP-16 × the rider forms): an angle in the
     * *host's own frame* is rigid under a frame that moves the host, exactly as a circle's angle is — so the
     * placement re-anchors nothing, the stored angle is untouched, and the point is still on the coil after the
     * figure is turned.
     */
    @Test
    fun aPlacedGroupCarriesACoilAndItsRiderRigidly() {
        val ed = Editor()
        val coil = coilAt(ed, Vec2(0.0, 0.0), radius = "20", pitch = "12", turns = "3")
        val rider = assertNotNull(riderAt(ed, Vec2(0.0, 20.0)), ed.statusHint)
        setAngle(rider, 450.0)
        val before = angleOf(ed, rider)

        // marquee the whole figure, then create + place (the dialog's frame tick is on by default)
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(-40.0, -40.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(40.0, 40.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(40.0, 40.0)))
        val dialog = assertNotNull(ed.beginCreate(constructit.editor.CreateMode.GROUP), "the figure can be grouped")
        dialog.name = "spring"
        assertTrue(ed.confirmCreate(), "and it creates: ${ed.statusHint}")
        val g = ed.doc.groups.last()
        assertTrue(g.placed, "and places: ${ed.statusHint}")
        assertTrue(
            ed.doc.analysePlacement(g).rigid.any { it.element === rider },
            "the coil's angle needs no re-anchoring: ${ed.doc.analysePlacement(g).uncapturable}",
        )

        // move the frame, and the rider is still where its angle says on the coil it rides
        val frame = assertNotNull(g.frameHandle, "the group has a frame")
        frame.drag(Vec2(60.0, 30.0), Evaluator())
        assertClose(angleOf(ed, rider), before, 1e-12, "nothing re-anchored the angle")
        val h = helixOf(ed.doc.elements.first { it.kind == ElementKind.SPACE_CURVE })
        assertVec(at(rider), h.atAngle(before * PI / 180.0), "and the point is on the coil where it was")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "a placed figure with a coil rider replays")
    }

    /** **Deleting the coil takes what rides it**: a key point and a rider cannot outlive the curve they read. */
    @Test
    fun deletingTheCoilTakesItsKeyPointsAndItsRiderWithIt() {
        val ed = Editor()
        val coil = coilAt(ed, Vec2(0.0, 0.0))
        keyPointsOf(ed, coil, Vec2(20.0, 0.0))
        assertNotNull(riderAt(ed, Vec2(0.0, 20.0)), ed.statusHint)
        ed.setTool(Tools.SELECT)
        ed.selectElement(coil)
        assertTrue(ed.deleteSelection(), "the coil is deletable: ${ed.statusHint}")
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE }, "the coil is gone")
        assertEquals(0, ed.doc.elements.count { it.inSpace }, "and so is everything that read it: ${ed.statusHint}")
        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "and what is left still round-trips")
    }

    /**
     * **What is drawn is what is picked**: the plan draws a dot at the *projection* of every point in space
     * this package makes, which is exactly where [aCoilsKeyPointsAreOrdinaryPointsOfTheDrawing]'s click reaches
     * one. (A height point is the deliberate exception — its plan image is its base's own dot.)
     */
    @Test
    fun thePlanDrawsEachPointInSpaceWhereItProjects() {
        val ed = Editor()
        val coil = coilAt(ed, Vec2(0.0, 0.0))
        val pts = keyPointsOf(ed, coil, Vec2(20.0, 0.0))
        val rider = assertNotNull(riderAt(ed, Vec2(0.0, 20.0)), ed.statusHint)
        setAngle(rider, 450.0)
        ed.setTool(Tools.SELECT)

        val dots = ArrayList<Vec2>()
        ed.draw(DotCapture(dots), wPx, hPx)
        for (el in pts + rider) {
            val want = ed.camera.worldToScreen(plan.toLocal(assertNotNull(at(el))))
            assertTrue(dots.any { (it - want).length() <= 0.5 }, "${ed.doc.nameOf(el)} is drawn at its projection $want")
        }
    }

    /** A target that keeps only the dots — what a claim about *where a point is drawn* is asserted against. */
    private class DotCapture(val dots: MutableList<Vec2>) : constructit.editor.DrawTarget {
        override fun begin(
            widthPx: Double,
            heightPx: Double,
        ) = Unit

        override fun polyline(
            points: List<Vec2>,
            style: constructit.editor.Style,
        ) = Unit

        override fun polygon(
            points: List<Vec2>,
            style: constructit.editor.Style,
        ) = Unit

        override fun circle(
            center: Vec2,
            radiusPx: Double,
            style: constructit.editor.Style,
        ) = Unit

        override fun dot(
            center: Vec2,
            radiusPx: Double,
            color: String,
        ) {
            dots.add(center)
        }

        override fun text(
            at: Vec2,
            text: String,
            style: constructit.editor.Style,
            anchor: constructit.editor.TextAnchor,
        ) = Unit

        override fun end() = Unit
    }

    /** The document API is the one the tool row goes through — asserted so the row cannot drift from it. */
    @Test
    fun theToolRowAndTheDocumentAgree() {
        val def = assertNotNull(Tools.all.firstOrNull { it.id == Tools.POINT_ON_HELIX }, "the row exists")
        assertEquals(listOf(constructit.editor.SlotKind.PATH3), def.slots, "one pick: the coil")
        assertTrue(def.help.contains("450"), "and the help says which view does which: ${def.help}")
        assertTrue(def.help.contains("3D view"))
        val keyPoints = assertNotNull(Tools.all.firstOrNull { it.id == Tools.KEY_POINTS })
        assertEquals(listOf(constructit.editor.SlotKind.EXTRACTABLE), keyPoints.slots, "key points stay one row")
        assertTrue(keyPoints.help.contains("curve in space"), "whose help now mentions a coil: ${keyPoints.help}")
        assertEquals(Document.RiderForm.HELIX_ANGLE, Document.RiderForm.valueOf("HELIX_ANGLE"), "the form is structural")
    }
}
