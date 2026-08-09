package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
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
import constructit.editor.Scene3
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.BoolOp
import constructit.geom.Continuity
import constructit.geom.Curve3Element
import constructit.geom.Curves3
import constructit.geom.MeshBool
import constructit.geom.Path3
import constructit.geom.Plane3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Quantity
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Connect as a gesture** (OP-26, step 7) — the half that decides whether the step is a feature or a
 * formula.
 *
 * The geometry is [ConnectTest]'s. What is asserted here is **the end choice**: two clicks, each of which
 * says which curve *and* which of its two ends, scored once and then persisted — including the probe sessions
 * 41, 43 and 45 all use, where the drawing is moved until a fresh scoring would choose the other end and the
 * reload is required to hand back the one the user clicked. Beyond that, the composition: the join rides both
 * runs and stays smooth, a tube along it is watertight, the three tubes of a route fuse into one body, it is
 * drawn and picked in both views, `save → load → save` is byte-equal, and one undo takes the gesture back.
 *
 * The fixture is deliberately trivial so that every number can be read: a 100 mm run along +X from the
 * origin, and a 100 mm run along +Y starting at (200, 80).
 */
class ConnectToolTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        pointerMove(camera.worldToScreen(from))
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun runOf(el: Element): Path3 = Evaluator().path3(el.ref as Path3Ref)

    private fun reasonOf(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    private fun assertVec3(
        actual: Vec3,
        expected: Vec3,
        tol: Double = 1e-9,
        msg: String = "",
    ) {
        assertClose(actual.x, expected.x, tol, "$msg (x)")
        assertClose(actual.y, expected.y, tol, "$msg (y)")
        assertClose(actual.z, expected.z, tol, "$msg (z)")
    }

    private fun view(ed: Editor): Viewport3 {
        val vp =
            Viewport3(
                camera = Camera3(target = Vec3(120.0, 60.0, 0.0), distance = 520.0, yaw = -0.15, pitch = 0.45),
                widthPx = 800.0,
                heightPx = 600.0,
            )
        vp.editor = ed
        vp.shown = true
        return vp
    }

    // ---- the fixture ----

    private class Route(val ed: Editor, val first: Element, val second: Element, val join: Element)

    /** A run of points in the plan, taken as a curve in space — OP-26 step 1's own gesture. */
    private fun runThrough(
        ed: Editor,
        vararg at: Vec2,
        smooth: Boolean = false,
    ): Element {
        ed.setTool(Tools.POINT)
        for (p in at) ed.click(p)
        ed.setTool(if (smooth) Tools.CURVE3_SMOOTH else Tools.CURVE3)
        for (p in at) ed.click(p)
        ed.key("Enter")
        return assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, "the run was drawn: ${ed.statusHint}")
    }

    /**
     * Two runs and a join between them, entirely by clicking. [nearA] and [nearB] are where the two clicks
     * land, and each of them chooses the end of its own run that it is nearest.
     */
    private fun routed(
        nearA: Vec2 = Vec2(95.0, 0.0),
        nearB: Vec2 = Vec2(200.0, 85.0),
        tool: String = Tools.CONNECT,
    ): Route {
        val ed = Editor()
        val a = runThrough(ed, Vec2(0.0, 0.0), Vec2(100.0, 0.0))
        val b = runThrough(ed, Vec2(200.0, 80.0), Vec2(200.0, 180.0))
        ed.setTool(tool)
        ed.click(nearA)
        ed.click(nearB)
        val join = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, "the join was built: ${ed.statusHint}")
        assertTrue(join !== a && join !== b, "and it is a new curve rather than one of the two")
        return Route(ed, a, b, join)
    }

    /**
     * **The defining property, asserted through the gestures**: the join stands on both runs' chosen ends and
     * leaves each of them along that run's own direction there.
     *
     * Everything on the right-hand side is read off the two **joined** curves as they now stand, so this is
     * the claim re-asked of whatever the drawing has become — which is what "it rides its parents" has to mean.
     */
    private fun assertJoinsSmoothly(
        r: Route,
        endOfFirst: Vec3,
        outOfFirst: Vec3,
        endOfSecond: Vec3,
        intoSecond: Vec3,
    ) = assertJoinsSmoothly(r.join, endOfFirst, outOfFirst, endOfSecond, intoSecond)

    private fun assertJoinsSmoothly(
        el: Element,
        endOfFirst: Vec3,
        outOfFirst: Vec3,
        endOfSecond: Vec3,
        intoSecond: Vec3,
    ) {
        val join = runOf(el)
        assertVec3(assertNotNull(join.start), endOfFirst, 0.0, "it starts on the first run's chosen end")
        assertVec3(assertNotNull(join.end), endOfSecond, 0.0, "and finishes on the second run's")
        assertVec3(
            assertNotNull(Curves3.tangentAt(join.elements.first(), 0.0)),
            outOfFirst,
            1e-12,
            "it leaves along the first run's own direction",
        )
        assertVec3(
            assertNotNull(Curves3.tangentAt(join.elements.last(), 1.0)),
            intoSecond,
            1e-12,
            "and arrives along the second run's",
        )
    }

    /** Where the join runs halfway along, in the plan's own coordinates — what a click aiming at it uses. */
    private fun midOfJoin(r: Route): Vec2 {
        val mid = Curves3.bezierPointAt(runOf(r.join).elements.single() as Curve3Element.Bezier3, 0.5)
        return Vec2(mid.x, mid.y)
    }

    /** The direction a run leaves or arrives in at one of its ends, read from the curve's own value. */
    private fun tangentOf(
        el: Element,
        atEnd: Boolean,
    ): Vec3 {
        val p = runOf(el)
        return assertNotNull(Curves3.tangentAt(if (atEnd) p.elements.last() else p.elements.first(), if (atEnd) 1.0 else 0.0))
    }

    // ---- 1. the gesture, and what it makes ----

    /**
     * **Two clicks and nothing else**: a bend from the end of one run to the start of the other, leaving each
     * of them the way it was going.
     */
    @Test
    fun twoClicksNearTwoEndsMakeTheJoiningPiece() {
        val r = routed()
        val join = runOf(r.join)
        assertEquals(1, join.elements.size, "a G1 join is one cubic")
        assertTrue(join.elements.single() is Curve3Element.Bezier3)
        assertJoinsSmoothly(r, Vec3(100.0, 0.0, 0.0), Vec3.X, Vec3(200.0, 80.0, 0.0), Vec3.Y)
        assertEquals(r.first.space, r.join.space, "the join belongs to the first pick's space")
        assertTrue(r.ed.statusHint.contains("G1"), "and the note names the continuity: ${r.ed.statusHint}")
        assertTrue(r.ed.statusHint.contains("end of"), "…which end of the first run: ${r.ed.statusHint}")
        assertTrue(r.ed.statusHint.contains("start of"), "…and of the second: ${r.ed.statusHint}")
    }

    /** **The other two ends give a different piece**, from the same two curves and the same tool. */
    @Test
    fun clickingTheOtherEndsGivesADifferentPiece() {
        val near = routed()
        val far = routed(nearA = Vec2(5.0, 0.0), nearB = Vec2(200.0, 175.0))
        assertJoinsSmoothly(far, Vec3.ZERO, -Vec3.X, Vec3(200.0, 180.0, 0.0), -Vec3.Y)
        assertTrue(runOf(near.join) != runOf(far.join), "two different joins from two different pairs of ends")
        assertTrue(far.ed.statusHint.contains("start of"), "and the note says which ends: ${far.ed.statusHint}")
    }

    // ---- 2. the end each click chose is a stored choice ----

    /** The two chosen ends are written into the step as an ordinary `signs=`, and the file round-trips. */
    @Test
    fun theChosenEndsArePersistedAsSignsAndTheFileRoundTrips() {
        val r = routed()
        val once = DocumentFormat.save(r.ed.doc)
        assertTrue(
            once.lines().any { it.startsWith("tool ${Tools.CONNECT}") && it.contains("signs=1;0") },
            "the step records the tool id and the two ends it joined (OP-1/OP-18): $once",
        )
        val doc = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(doc), "save -> load -> save is byte-equal")
        val back = doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        assertEquals(runOf(r.join), runOf(back), "and the join reloads piece for piece")
    }

    /**
     * **The stored end holds when the geometry has moved past the click** — sessions 41, 43 and 45's own
     * probe, one feature along.
     *
     * The first run's end was chosen by a click at `x = 95`. Its far point is then dragged to (100, −200), so
     * the recorded click is now much nearer that run's **start**: a load that re-scored would join the other
     * end and give a different curve. The probe is only worth anything if re-scoring would differ, so that is
     * checked with the same arithmetic the scoring uses.
     */
    @Test
    fun aReloadKeepsTheChosenEndAfterTheRunHasMoved() {
        val r = routed()
        r.ed.setTool(Tools.SELECT)
        r.ed.drag(Vec2(100.0, 0.0), Vec2(100.0, -200.0))
        val moved = runOf(r.first)
        assertVec3(assertNotNull(moved.end), Vec3(100.0, -200.0, 0.0), 1e-9, "the run's far end followed the drag")

        // a fresh scoring of the recorded click would now prefer the *start*
        val click = Vec2(95.0, 0.0)
        val toStart = (Vec2(0.0, 0.0) - click).length()
        val toEnd = (Vec2(100.0, -200.0) - click).length()
        assertTrue(toStart < toEnd, "the recorded click is now nearest the start: $toStart vs $toEnd")

        val doc = DocumentFormat.load(DocumentFormat.save(r.ed.doc))
        val back = doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        assertVec3(
            assertNotNull(runOf(back).start),
            Vec3(100.0, -200.0, 0.0),
            1e-9,
            "the reload keeps the end the user chose, and does not re-decide it",
        )
        assertEquals(runOf(r.join), runOf(back), "…so the reloaded join is the same curve, bit for bit")
    }

    // ---- 3. it rides both runs ----

    /**
     * **Drag either run and the join follows it, still smooth** — asserted by the defining property against
     * the runs' new values rather than against a coordinate.
     */
    @Test
    fun theJoinRidesBothRunsAndStaysSmooth() {
        val r = routed()
        r.ed.setTool(Tools.SELECT)
        r.ed.drag(Vec2(100.0, 0.0), Vec2(120.0, 40.0))
        r.ed.drag(Vec2(200.0, 80.0), Vec2(240.0, 90.0))
        assertJoinsSmoothly(
            r,
            Vec3(120.0, 40.0, 0.0),
            tangentOf(r.first, atEnd = true),
            Vec3(240.0, 90.0, 0.0),
            tangentOf(r.second, atEnd = false),
        )
        // …and the run either curve stands on moves it too: drag the *other* end of the first run, which
        // changes the direction it arrives in without moving the point being joined
        r.ed.drag(Vec2(0.0, 0.0), Vec2(0.0, 90.0))
        assertJoinsSmoothly(
            r,
            Vec3(120.0, 40.0, 0.0),
            tangentOf(r.first, atEnd = true),
            Vec3(240.0, 90.0, 0.0),
            tangentOf(r.second, atEnd = false),
        )
    }

    /** **Ends dragged onto each other go invalid by name, and it heals** (OP-3) — a property of values. */
    @Test
    fun endsDraggedTogetherGoInvalidByNameAndHeal() {
        val r = routed()
        r.ed.setTool(Tools.SELECT)
        r.ed.drag(Vec2(100.0, 0.0), Vec2(200.0, 80.0))
        val why = assertNotNull(reasonOf(r.join), "the two ends now stand in one place")
        assertTrue(why.contains("same place"), "and it says so: $why")
        assertEquals(2, Scene3.extract(r.ed.doc).curves.size, "an invalid join draws nothing; the two runs stay")
        // dropping a point onto another **welds** it (OP-5: binding removes a degree of freedom), so the two
        // ends are now one node and no further drag can separate them — what takes the drawing back is undo,
        // and the join heals the moment it does
        assertTrue(r.ed.undo(), "the drag is taken back")
        val healed = r.ed.doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        assertEquals(null, reasonOf(healed), "and it heals when the two ends are apart again")
        assertJoinsSmoothly(healed, Vec3(100.0, 0.0, 0.0), Vec3.X, Vec3(200.0, 80.0, 0.0), Vec3.Y)
    }

    // ---- 4. the tensions are ordinary parameters ----

    /**
     * **A typed tension is a panel parameter**, so it can be retyped afterwards and the join moves — and the
     * default is 1, which is the value at which the join is the straight segment when the ends face each other.
     */
    @Test
    fun theTensionIsATypedParameterThatCanBeRetyped() {
        val ed = Editor()
        runThrough(ed, Vec2(0.0, 0.0), Vec2(100.0, 0.0))
        runThrough(ed, Vec2(200.0, 80.0), Vec2(200.0, 180.0))
        ed.setTool(Tools.CONNECT)
        ed.type("2")
        ed.click(Vec2(95.0, 0.0))
        ed.click(Vec2(200.0, 85.0))
        val join = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)
        val entry = assertNotNull(ed.doc.scalars.lastOrNull { it.name == "tension" }, "the typed number is a panel row")

        // the gap is 100 mm along +X and 80 along +Y, so a tension of T puts the first control point
        // T·gap/3 along the first run's direction — a number this test can state rather than trust
        val end = Vec3(100.0, 0.0, 0.0)
        val gap = (Vec3(200.0, 80.0, 0.0) - end).length()
        assertClose((runOf(join).elements.single() as Curve3Element.Bezier3).p1.let { (it - end).length() }, 2.0 * gap / 3.0, 1e-9, "as typed")
        ed.doc.setParameter(entry, Quantity.number(4.0))
        assertClose((runOf(join).elements.single() as Curve3Element.Bezier3).p1.let { (it - end).length() }, 4.0 * gap / 3.0, 1e-9, "as retyped")

        // a tension of nothing is the node's own refusal, by name, and it heals
        ed.doc.setParameter(entry, Quantity.number(0.0))
        val why = assertNotNull(reasonOf(join), "zero is refused")
        assertTrue(why.contains("tension"), "by name: $why")
        ed.doc.setParameter(entry, Quantity.number(1.0))
        assertEquals(null, reasonOf(join), "and it heals")
    }

    // ---- 5. it composes: a join is a curve like any other ----

    /** **A tube swept along the join is an ordinary watertight solid.** */
    @Test
    fun aTubeAlongTheJoinIsWatertight() {
        val r = routed()
        r.ed.setTool(Tools.TUBE)
        r.ed.type("6")
        r.ed.click(midOfJoin(r))
        val tube = assertNotNull(r.ed.solids().lastOrNull(), "the tube was built: ${r.ed.statusHint}")

        @Suppress("UNCHECKED_CAST")
        assertManifold(Evaluator().solid(tube.ref as SolidRef).mesh, "a tube along a join")
    }

    /**
     * **The three tubes of a routed run fuse into one body** — which is how a route is swept today, *without*
     * composite paths: joining several `Path3`s into one is OP-26's own to-be-discussed item 4, so the run is
     * three sweeps that meet tangentially and are unioned like any other solids.
     */
    @Test
    fun theThreeTubesOfARoutedRunFuseIntoOneWatertightBody() {
        assumeTrue(MeshBool.available, "the general boolean engine is not available here: ${MeshBool.status}")
        val r = routed()
        val tubes =
            listOf(Vec2(50.0, 0.0), midOfJoin(r), Vec2(200.0, 130.0)).map { at ->
                r.ed.setTool(Tools.TUBE)
                r.ed.type("6")
                r.ed.click(at)
                assertNotNull(r.ed.solids().lastOrNull(), "a tube at $at: ${r.ed.statusHint}")
            }
        assertEquals(3, tubes.distinct().size, "three separate tubes, one per piece of the route")
        val one = assertNotNull(r.ed.doc.combineSolids(tubes[0], tubes[1], BoolOp.UNION), r.ed.doc.takeNote())
        val whole = assertNotNull(r.ed.doc.combineSolids(one, tubes[2], BoolOp.UNION), r.ed.doc.takeNote())

        @Suppress("UNCHECKED_CAST")
        assertManifold(Evaluator().solid(whole.ref as SolidRef).mesh, "the whole route")
    }

    /** **Drawn in the 3D view and in the 2D canvas, and clickable in both.** */
    @Test
    fun theJoinIsDrawnAndPickableInBothViews() {
        val r = routed()
        assertEquals(3, Scene3.extract(r.ed.doc).curves.size, "two runs and the join between them")

        val vp = view(r.ed)
        r.ed.setTool(Tools.SELECT)
        val mid = Curves3.bezierPointAt(runOf(r.join).elements.single() as Curve3Element.Bezier3, 0.5)
        // the whole fixture lies in the plan, so the plan's own (u, v) of a point on the join is its (x, y)
        val plan = Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)
        val at = assertNotNull(assertNotNull(vp.projection()).toScreenLifted(plan.toLocal(mid), 0.0))
        vp.pointerMove(at)
        vp.pointerDown(at)
        vp.pointerUp(at)
        assertEquals(r.join, r.ed.selection, "the 3D view took the click on the join: ${r.ed.statusHint}")

        vp.shown = false
        r.ed.click(Vec2(600.0, 600.0))
        assertEquals(null, r.ed.selection, "empty space clears the selection first")
        r.ed.click(plan.toLocal(mid))
        assertEquals(r.join, r.ed.selection, "and the plan reached its projection: ${r.ed.statusHint}")
    }

    /** **One gesture, one undo** — and both runs stay. */
    @Test
    fun oneUndoTakesTheGestureBack() {
        val r = routed()
        assertEquals(3, r.ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE })
        assertTrue(r.ed.undo(), "the join is taken back")
        assertEquals(2, r.ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE }, "one checkpoint covered the gesture")
        assertTrue(r.ed.redo(), "and it comes back")
        assertEquals(3, r.ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE })
    }

    // ---- 6. the two gesture refusals, both structural ----

    /**
     * **A drawn curve can be one end of a join** — the lift (OP-26, step 1's missing source): the second pick
     * is a plain segment in the plan, read as the run it already is, and the bend joins the two.
     *
     * This test used to assert that such a pick was refused by name. The refusal that remains is the one about
     * the geometry rather than the vocabulary: a **line** runs on for ever, so it has no end to join.
     */
    @Test
    fun aDrawnCurveIsJoinableAndALineIsRefusedByName() {
        val ed = Editor()
        val a = runThrough(ed, Vec2(0.0, 0.0), Vec2(100.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 200.0))
        ed.click(Vec2(100.0, 200.0))
        val seg = ed.doc.elements.last { it.kind == ElementKind.SEGMENT }
        val join = assertNotNull(ed.doc.connectCurves(a, seg, null, null, emptyList(), emptyList(), Continuity.G1), ed.doc.note)
        assertEquals(ElementKind.SPACE_CURVE, join.kind, "the join is a run like any other")
        assertNull((Evaluator().eval(join.ref.node) as? EvalResult.Invalid)?.reason, "and it is valid: ${ed.doc.note}")

        // **and which end of the drawing was clicked is scored against the lifted run's own ends**, not
        // defaulted: a click by the segment's start joins there, and the piece therefore reaches (0, 200)
        val toStart =
            assertNotNull(
                ed.doc.connectCurves(a, seg, null, null, listOf(Vec2(100.0, 0.0), Vec2(2.0, 200.0)), emptyList(), Continuity.G1),
                ed.doc.note,
            )
        val piece = ((Evaluator().eval(toStart.ref.node) as EvalResult.Ok).value as constructit.core.Path3Value).path
        assertTrue(
            (assertNotNull(piece.end) - Vec3(0.0, 200.0, 0.0)).length() < 1e-6,
            "the join reaches the end the click was nearest: ${piece.end}",
        )

        val line = ed.doc.line(ed.doc.freePoint(0.0.mm, 300.0.mm), ed.doc.freePoint(100.0.mm, 300.0.mm))
        assertEquals(null, ed.doc.connectCurves(a, line, null, null, emptyList(), emptyList(), Continuity.G1))
        val why = assertNotNull(ed.doc.takeNote())
        assertTrue(why.contains("runs on for ever"), "and it says what it wanted: $why")
    }

    /**
     * **The same end of the same curve twice states no gap and cannot heal**, because both halves of it are
     * structure — so it is one of the two gesture refusals. Its **other** end is an ordinary join, which is
     * how a run is closed into a loop.
     */
    @Test
    fun theSameEndTwiceIsRefusedWhileTheOtherEndClosesTheRun() {
        val ed = Editor()
        runThrough(ed, Vec2(0.0, 0.0), Vec2(100.0, 0.0), Vec2(100.0, 100.0))
        ed.setTool(Tools.CONNECT)
        ed.click(Vec2(100.0, 95.0))
        ed.click(Vec2(100.0, 90.0))
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE }, "nothing was built")
        assertTrue(ed.statusHint.contains("clicked twice"), "and it says why: ${ed.statusHint}")

        ed.setTool(Tools.CONNECT)
        ed.click(Vec2(100.0, 95.0))
        ed.click(Vec2(5.0, 0.0))
        val loop = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE }, "the closing bend was built")
        assertVec3(assertNotNull(runOf(loop).start), Vec3(100.0, 100.0, 0.0), 0.0, "from the run's own end…")
        assertVec3(assertNotNull(runOf(loop).end), Vec3.ZERO, 0.0, "…back to its own start")
    }

    // ---- 7. the G2 mode, and every kind of run on either side ----

    /**
     * **The curvature mode is its own tool id**, so the file records which one was used, and what it builds
     * matches each run's curvature as well as its direction.
     */
    @Test
    fun theCurvatureModeIsItsOwnToolIdAndMatchesCurvature() {
        val ed = Editor()
        val a = runThrough(ed, Vec2(0.0, 0.0), Vec2(60.0, 40.0), Vec2(140.0, 30.0), smooth = true)
        val b = runThrough(ed, Vec2(300.0, 120.0), Vec2(360.0, 60.0), Vec2(420.0, 120.0), smooth = true)
        ed.setTool(Tools.CONNECT_G2)
        ed.click(Vec2(138.0, 30.0))
        ed.click(Vec2(302.0, 118.0))
        val join = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)
        val path = runOf(join)
        assertEquals(3, path.elements.size, "three cubics")

        val ka = assertNotNull(Curves3.curvatureVectorAt(runOf(a).elements.last(), 1.0))
        val kb = assertNotNull(Curves3.curvatureVectorAt(runOf(b).elements.first(), 0.0))
        assertVec3(assertNotNull(Curves3.curvatureVectorAt(path.elements.first(), 0.0)), ka, 1e-12, "the first run's curvature")
        assertVec3(assertNotNull(Curves3.curvatureVectorAt(path.elements.last(), 1.0)), kb, 1e-12, "and the second's")
        assertTrue(ka.length() > 1e-4 && kb.length() > 1e-4, "and both are curvatures worth matching")

        val saved = DocumentFormat.save(ed.doc)
        assertTrue(saved.lines().any { it.startsWith("tool ${Tools.CONNECT_G2}") }, "the mode is the tool id: $saved")
        val doc = DocumentFormat.load(saved)
        assertEquals(saved, DocumentFormat.save(doc), "save -> load -> save is byte-equal")
        assertEquals(path, runOf(doc.elements.last { it.kind == ElementKind.SPACE_CURVE }), "and it reloads piece for piece")
    }

    /** A run of segments joined to a **helix** — the first of the three combinations `tangentAt` has to get right. */
    @Test
    fun aSegmentRunIsJoinedToAHelix() {
        val ed = Editor()
        val a = runThrough(ed, Vec2(0.0, 0.0), Vec2(100.0, 0.0))
        ed.setTool(Tools.POINT)
        ed.click(Vec2(300.0, 0.0))
        ed.setTool(Tools.HELIX)
        ed.type("20")
        ed.type("30")
        // one and a half turns, so the coil's two ends are on opposite sides and a click in the plan says
        // plainly which of them it means — an integer number of turns would put them on one another
        ed.type("1.5")
        ed.click(Vec2(300.0, 0.0))
        val coil = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)
        ed.setTool(Tools.CONNECT)
        ed.click(Vec2(95.0, 0.0))
        ed.click(Vec2(320.0, 1.0))
        val join = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)
        assertTrue(join !== coil, "a third curve was built: ${ed.statusHint}")
        val path = runOf(join)
        assertVec3(assertNotNull(path.start), assertNotNull(runOf(a).end), 0.0, "on the run's end")
        assertVec3(assertNotNull(path.end), assertNotNull(runOf(coil).start), 0.0, "and on the coil's start")
        assertVec3(
            assertNotNull(Curves3.tangentAt(path.elements.last(), 1.0)),
            tangentOf(coil, atEnd = false),
            1e-12,
            "arriving along the helix's own tangent, which no polyline could have guessed",
        )
    }

    /** A **combined run** (step 5) joined to a helix — a derived curve on one side, a constructed one on the other. */
    @Test
    fun aCombinedRunIsJoinedToAHelix() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(200.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 40.0))
        ed.click(Vec2(200.0, 40.0))
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("90")
        ed.click(Vec2(100.0, 0.0))
        val elevation = ed.activeSpace.name
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(200.0, 80.0))
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.setTool(Tools.COMBINE_VIEWS)
        ed.click(Vec2(100.0, 40.0))
        assertTrue(ed.setActiveSpace(elevation))
        ed.click(Vec2(100.0, 40.0))
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        val run = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)

        ed.setTool(Tools.POINT)
        ed.click(Vec2(400.0, 40.0))
        ed.setTool(Tools.HELIX_LEFT)
        ed.type("25")
        ed.type("40")
        ed.type("1.5")
        ed.click(Vec2(400.0, 40.0))
        val coil = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)

        ed.setTool(Tools.CONNECT)
        ed.click(Vec2(195.0, 40.0))
        ed.click(Vec2(425.0, 41.0))
        val join = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)
        assertTrue(join !== coil && join !== run)
        val path = runOf(join)
        assertVec3(assertNotNull(path.start), assertNotNull(runOf(run).end), 0.0, "on the combined run's far end")
        assertVec3(
            assertNotNull(Curves3.tangentAt(path.elements.first(), 0.0)),
            tangentOf(run, atEnd = true),
            1e-12,
            "leaving along the grade the two views state",
        )
        assertVec3(assertNotNull(path.end), assertNotNull(runOf(coil).start), 0.0, "and on the coil")
    }

    /**
     * **An intersection curve of a closed body is a loop, and a loop has no end to join** — so the gesture
     * builds the node and the *node* says why it cannot be a join, which is the doctrinal answer: closure is a
     * **value** for a derived curve (slide the plane and the same cut can be an open run), so refusing the
     * gesture would make replay depend on one. An *open* intersection run joins like any other, which is
     * [ConnectTest]'s `anOpenIntersectionRunIsJoinedLikeAnyOther`.
     */
    @Test
    fun aClosedIntersectionLoopHasNoEndToJoinAndTheNodeSaysSo() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 60.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("30")
        ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(50.0, -40.0))
        ed.click(Vec2(50.0, 100.0))
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("90")
        ed.click(Vec2(50.0, 30.0))
        val datum = ed.activeSpace.name
        ed.setTool(Tools.INTERSECTION_CURVE)
        ed.click(Vec2(30.0, 0.0))
        val cut = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)
        assertTrue(runOf(cut).closed, "the cut of a plate is a closed loop, which has no end to join")

        // …so the refusal is by name, and it is the node's, because closure is a value for a derived curve
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        val a = runThrough(ed, Vec2(200.0, 0.0), Vec2(300.0, 0.0))
        ed.setTool(Tools.CONNECT)
        // a curve in space is addressable in the space it belongs to, so the loop is clicked on its own datum
        // and the run in the plan — which is what the gesture's `crossSpace` is for
        assertTrue(ed.setActiveSpace(datum))
        ed.click(Vec2(30.0, 0.0))
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.click(Vec2(205.0, 0.0))
        val join = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)
        assertTrue(join !== cut && join !== a, "the join was built even though its inputs are unjoinable right now")
        val why = assertNotNull(reasonOf(join), "and it is invalid, by name")
        assertTrue(why.contains("closed run"), "…because a loop has no end: $why")
    }
}
