package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.Path3Ref
import constructit.dsl.SolidRef
import constructit.dsl.circle
import constructit.dsl.path3
import constructit.dsl.plane
import constructit.dsl.solid
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Curve3Element
import constructit.geom.Handedness
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Quantity
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The coil that says where it starts** (OP-26, step 3, extended) — a helix stated by a centre and a
 * *start point*, which is the user's own design for the one degree of freedom the first spelling could not
 * state: the **phase**.
 *
 * What these ask is the claim the second spelling exists for. The curve begins **at the point that was
 * clicked**, whichever way that point lies and whatever space it was drawn in; the radius is what the two
 * points say and follows either of them; and because the start point is an ordinary pick, clicking one that
 * something else already uses *shares its node*, so a spring can come off the edge of a hole and follow it.
 * Beside that, the things every gesture owes: the old spelling untouched and its files unchanged, one undo,
 * `save → load → save` byte-equal, a watertight spring, and a degenerate radius that refuses by name and
 * heals.
 */
class HelixStartPointTest {
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
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    private fun Editor.hover(world: Vec2) = pointerMove(camera.worldToScreen(world))

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun Editor.pointAt(at: Vec2): Element {
        setTool(Tools.POINT)
        click(at)
        return doc.elements.last { it.kind == ElementKind.POINT }
    }

    /**
     * The fixture: two plain points in the active space, then the two-point helix — a pitch, a turn count
     * and two clicks. Both points are ordinary 2D ones, lifted by a **zero** height onto their own space's
     * plane, which is the `POINT3` slot's own rule.
     */
    private fun coil(
        ed: Editor,
        centre: Vec2,
        start: Vec2,
        pitch: String = "12",
        turns: String? = "3",
        left: Boolean = false,
    ): Element {
        ed.pointAt(centre)
        ed.pointAt(start)
        ed.setTool(if (left) Tools.HELIX_PT_LEFT else Tools.HELIX_PT)
        ed.type(pitch)
        if (turns != null) ed.type(turns)
        ed.click(centre)
        ed.click(start)
        return assertNotNull(
            ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE },
            "the coil was built: ${ed.statusHint}",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun helixOf(el: Element): Curve3Element.Helix3 =
        Evaluator().path3(el.ref as Path3Ref).elements.single() as Curve3Element.Helix3

    /** Where sketch point [p] of space [space] stands in the world — what a click there aims at. */
    private fun worldOf(
        ed: Editor,
        space: String,
        p: Vec2,
    ): Vec3 {
        val plane = ed.doc.spaceNamed(space)?.plane ?: return Vec3(p.x, p.y, 0.0)
        return Evaluator().plane(plane).toWorld(p)
    }

    /**
     * The default tolerance is **1e-14 mm** — a couple of ulps, and deliberately not a geometric tolerance:
     * the phase is carried from the two points as a vector through one normalization, so "the coil starts at
     * the start point" is arithmetic rather than an approximation. The cases that pass a looser one say so
     * where they do, and each time it is a *plane's* arithmetic being asked as well.
     */
    private fun assertVec(
        actual: Vec3,
        expected: Vec3,
        msg: String,
        tol: Double = 1e-14,
    ) {
        assertTrue((actual - expected).length() <= tol, "$msg (was $actual, wanted $expected)")
    }

    // ---- the phase is what the start point says ----

    /**
     * **The coil begins at the point that was clicked.** Six start directions round the centre, both
     * handednesses, and each time the curve's own `t = 0` point *is* the start point — including the
     * directions the space's `u` is nothing like, which is the whole of what the first spelling could not
     * say.
     *
     * The tolerance is the one normalization the phase passes through and nothing else: the direction is
     * carried as a **vector** from the centre to the start point, never as an angle, so there is no
     * `atan2`/`cos` round trip to lose digits in.
     */
    @Test
    fun theCoilStartsExactlyAtTheStartPointForEveryBearing() {
        for (left in listOf(false, true)) {
            for (deg in listOf(0, 37, 90, 143, 216, 305)) {
                val ed = Editor()
                val a = deg * PI / 180.0
                val centre = Vec2(15.0, -7.0)
                val start = Vec2(centre.x + 24.0 * cos(a), centre.y + 24.0 * sin(a))
                val el = coil(ed, centre, start, left = left)
                val h = helixOf(el)
                assertVec(h.at(0.0), Vec3(start.x, start.y, 0.0), "a coil at $deg° starts where it was told to")
                assertClose(h.radius, 24.0, 1e-9, "and its radius is the distance between the two points")
                assertEquals(if (left) Handedness.LEFT else Handedness.RIGHT, h.hand, "the tool says which way it turns")
                assertVec(h.origin, Vec3(centre.x, centre.y, 0.0), "and it stands on the centre")
            }
        }
    }

    /**
     * **…and on a datum plane too**, where the space's own `u` runs somewhere else entirely: the coil starts
     * at the start point, in the world, and rises along that plane's normal.
     */
    @Test
    fun theStartPointIsThePhaseOnADatumPlaneAsWell() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 60.0))
        ed.click(Vec2(200.0, 60.0))
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("55")
        ed.click(Vec2(100.0, 60.0))
        assertTrue(ed.doc.activeSpace.isDatum, "a datum plane was opened")
        val space = ed.doc.activeSpace.name

        val centre = Vec2(40.0, 30.0)
        val start = Vec2(40.0 - 18.0, 30.0 + 26.0)
        val el = coil(ed, centre, start, pitch = "20")
        val h = helixOf(el)
        assertVec(h.at(0.0), worldOf(ed, space, start), "the coil starts at the point, in the world", 1e-8)
        assertVec(h.origin, worldOf(ed, space, centre), "and stands on the centre", 1e-8)

        val n = Evaluator().plane(assertNotNull(ed.doc.spaceNamed(space)?.plane)).normal.normalized()
        assertVec(h.axis, n, "and rises along the plane's own normal", 1e-9)
        assertTrue(n.z < 0.999, "which is not the plan's, or this would prove nothing: $n")
    }

    // ---- the radius is what the two points say, and it follows both ----

    /** **Two points state the radius**, and dragging *either* of them restates it. */
    @Test
    fun theRadiusFollowsBothPoints() {
        val ed = Editor()
        val el = coil(ed, Vec2(0.0, 0.0), Vec2(30.0, 0.0))
        assertClose(helixOf(el).radius, 30.0, 1e-9, "the two points said 30 mm")

        // drag the start point out along +y: a new radius *and* a new phase, both by construction
        ed.drag(Vec2(30.0, 0.0), Vec2(0.0, 50.0))
        assertClose(helixOf(el).radius, 50.0, 1e-9, "the start point moved, so the radius did")
        assertVec(helixOf(el).at(0.0), Vec3(0.0, 50.0, 0.0), "and the coil now starts there")

        // …and dragging the centre moves the coil and restates the radius from where it now stands
        ed.drag(Vec2(0.0, 0.0), Vec2(-20.0, 50.0))
        assertVec(helixOf(el).origin, Vec3(-20.0, 50.0, 0.0), "the coil stands on the centre")
        assertClose(helixOf(el).radius, 20.0, 1e-9, "and the radius is what the two points now say")
        assertVec(helixOf(el).at(0.0), Vec3(0.0, 50.0, 0.0), "still starting at the start point")
    }

    // ---- sharing: the claim this spelling exists for ----

    /**
     * **A coil can begin at a point something else already uses.** The start point is an ordinary pick, so
     * clicking a point that is already a circle's on-circle point *shares that node* — and one drag moves
     * the circle and the coil's phase together. That is what "by construction" buys over a typed angle: the
     * thread starts at the hole's edge because it *is* the hole's edge, not because two numbers agree.
     */
    @Test
    fun aCoilBegunAtAPointSomethingElseUsesFollowsIt() {
        val ed = Editor()
        val centre = ed.pointAt(Vec2(0.0, 0.0))
        assertNotNull(centre)
        // a circle through a point on it — the point is the circle's own input, and now the coil's as well
        ed.pointAt(Vec2(26.0, 0.0))
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(26.0, 0.0))
        val circle = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.CIRCLE }, "a circle: ${ed.statusHint}")

        val before = ed.doc.elements.count { it.isPoint }
        ed.setTool(Tools.HELIX_PT)
        ed.type("10")
        ed.type("2")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(26.0, 0.0))
        val el = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, "the coil: ${ed.statusHint}")
        assertEquals(before, ed.doc.elements.count { it.isPoint }, "no point was placed — the picks shared what was there")

        val r0 = Evaluator().circleOfElement(circle).radius
        assertClose(r0, 26.0, 1e-9, "the circle passes through the shared point")
        assertClose(helixOf(el).radius, 26.0, 1e-9, "and so does the coil's start")

        // one drag of the shared point: both followed, because there is only one node to follow
        ed.drag(Vec2(26.0, 0.0), Vec2(0.0, 40.0))
        assertClose(Evaluator().circleOfElement(circle).radius, 40.0, 1e-9, "the circle grew")
        assertClose(helixOf(el).radius, 40.0, 1e-9, "the coil grew with it")
        assertVec(helixOf(el).at(0.0), Vec3(0.0, 40.0, 0.0), "and starts where that point now is — the phase turned too")
    }

    @Suppress("UNCHECKED_CAST")
    private fun Evaluator.circleOfElement(el: Element) = circle(el.ref as constructit.dsl.CircleRef)

    // ---- the first spelling is untouched, and so are its files ----

    /**
     * **A file written by the old tool loads unchanged.** The two ids are frozen — they are what files
     * record — so this script, written before the second spelling existed, must still mean a coil that
     * starts along the plan's own x, and must still save back byte for byte.
     */
    @Test
    fun aFileWrittenByTheOldToolStillMeansWhatItMeant() {
        val text =
            """
            constructit 2
            point 0,0 -> e1
            param "radius" = 20mm
            param "pitch" = 12mm
            param "turns" = 3
            tool helix els=e1 clicks=0,0 scalar="radius","pitch","turns" -> e2
            """.trimIndent() + "\n"
        val doc = DocumentFormat.load(text)
        val el = assertNotNull(doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, "the old file still builds a coil")
        val h = helixOf(el)
        assertEquals(20.0, h.radius, "the radius it recorded")
        assertEquals(12.0, h.pitch)
        assertEquals(3.0, h.turns)
        assertEquals(Handedness.RIGHT, h.hand, "and the handedness its tool id states")
        assertVec(h.at(0.0), Vec3(20.0, 0.0, 0.0), "and it still starts along the space's own x — the phase-free spelling")
        assertEquals(atThisVersion(text), DocumentFormat.save(doc), "and it saves back byte for byte")
    }

    /** The old gesture builds exactly the same curve it always did, beside the new one in one drawing. */
    @Test
    fun bothSpellingsLiveInOneDrawingAndTheOldOneIsUnchanged() {
        val ed = Editor()
        ed.pointAt(Vec2(0.0, 0.0))
        ed.setTool(Tools.HELIX)
        ed.type("20")
        ed.type("12")
        ed.type("3")
        ed.click(Vec2(0.0, 0.0))
        val old = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, "${ed.statusHint}")
        assertVec(helixOf(old).at(0.0), Vec3(20.0, 0.0, 0.0), "the old spelling starts along x")

        val new = coil(ed, Vec2(100.0, 0.0), Vec2(100.0, 20.0))
        assertVec(helixOf(new).at(0.0), Vec3(100.0, 20.0, 0.0), "the new one starts where it was told")
        assertVec(helixOf(old).at(0.0), Vec3(20.0, 0.0, 0.0), "and the old one did not move")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save → load → save is byte-equal")
    }

    // ---- the file, the undo, the spring ----

    /**
     * **`save → load → save` is byte-equal, the handedness is the tool id, and the coil still sweeps to a
     * watertight spring** — the four things every curve in space owes, asked of the new spelling.
     */
    @Test
    fun theTwoPointCoilRoundTripsAndSweepsToAWatertightSpring() {
        val ed = Editor()
        val el = coil(ed, Vec2(0.0, 0.0), Vec2(0.0, -30.0), pitch = "20", turns = "3", left = true)
        assertVec(helixOf(el).at(0.0), Vec3(0.0, -30.0, 0.0), "it starts at −y, which no convention would have chosen")

        ed.setTool(Tools.TUBE)
        ed.type("4")
        ed.click(Vec2(0.0, -30.0))
        val spring = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID }, "the spring: ${ed.statusHint}")

        @Suppress("UNCHECKED_CAST")
        val mesh = Evaluator().solid(spring.ref as SolidRef).mesh
        assertManifold(mesh, "a spring about a stated start point")

        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.lines().any { it.startsWith("tool ${Tools.HELIX_PT_LEFT}") }, "the step records the tool id: $once")
        val doc = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(doc), "the script round-trips byte for byte")

        val back = doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        val h = helixOf(back)
        assertEquals(Handedness.LEFT, h.hand, "left-handed, from the tool id alone")
        assertClose(h.radius, 30.0, 1e-9, "the radius the two points state")
        assertVec(h.at(0.0), Vec3(0.0, -30.0, 0.0), "and the phase they state, which is recorded by nothing but the picks")

        @Suppress("UNCHECKED_CAST")
        val after = Evaluator().solid(doc.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef).mesh
        assertEquals(mesh.vertices, after.vertices, "and the spring reloads vertex for vertex")
        assertEquals(mesh.triangles, after.triangles, "and triangle for triangle")
    }

    /** **One gesture, one undo** — and both points it was stated from stay, because neither was part of it. */
    @Test
    fun oneUndoTakesTheTwoPointGestureBack() {
        val ed = Editor()
        coil(ed, Vec2(0.0, 0.0), Vec2(30.0, 10.0))
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE })
        assertTrue(ed.undo(), "the coil is taken back")
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE }, "one checkpoint covered the gesture")
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.POINT }, "and both points it was stated from stay")
        assertTrue(ed.redo(), "and it comes back")
        assertVec(helixOf(ed.doc.elements.last { it.kind == ElementKind.SPACE_CURVE }).at(0.0), Vec3(30.0, 10.0, 0.0), "starting where it did")
    }

    // ---- the refusals ----

    /**
     * **A start point on the axis states no coil, and it says so in this spelling's own words** (OP-3): the
     * radius here is the distance out to the start point, so dragging that point onto the centre leaves
     * nothing for the coil to turn about — and everything swept along it hides until it is a coil again.
     *
     * Dragging one point *onto* another **welds** them (OP-19), which is the sharpest form of the fault: the
     * two ends of the radius have become one node, so no drag can separate them again and the way back is to
     * take the gesture back. Which is what the undo asserts, spring and all.
     */
    @Test
    fun aStartPointDraggedOntoTheCentreRefusesByName() {
        val ed = Editor()
        val el = coil(ed, Vec2(0.0, 0.0), Vec2(30.0, 0.0))
        ed.setTool(Tools.TUBE)
        ed.type("3")
        ed.click(Vec2(30.0, 0.0))
        val spring = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID }, "the spring: ${ed.statusHint}")

        ed.drag(Vec2(30.0, 0.0), Vec2(0.0, 0.0))
        val bad = Evaluator().eval(el.ref.node)
        assertTrue(bad is EvalResult.Invalid, "a coil of no radius is invalid: $bad")
        val why = (bad as EvalResult.Invalid).reason
        assertTrue(why.contains("start point"), "and the reason is this spelling's own: $why")
        assertTrue(why.contains("stands on the axis"), "naming what the fault is: $why")
        assertTrue(why.contains("move it off the centre"), "and the cure: $why")
        assertTrue(Evaluator().eval(spring.ref.node) is EvalResult.Invalid, "and the spring hides with it")

        // the drag is one checkpoint; taking it back is what puts the two ends of the radius apart again
        assertTrue(ed.undo(), "the drag is taken back")
        val back = ed.doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        val backSpring = ed.doc.elements.last { it.kind == ElementKind.SOLID }
        assertTrue(Evaluator().eval(back.ref.node) is EvalResult.Ok, "and the coil heals")
        assertClose(helixOf(back).radius, 30.0, 1e-9, "with the radius the two points say")
        assertTrue(Evaluator().eval(backSpring.ref.node) is EvalResult.Ok, "and the spring is back")
    }

    /** One point clicked for **both** ends of the radius is structural — no edit could heal it — so it is refused by name. */
    @Test
    fun onePointClickedForBothIsRefusedByName() {
        val ed = Editor()
        val p = ed.pointAt(Vec2(0.0, 0.0))
        ed.setTool(Tools.HELIX_PT)
        ed.type("10")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(0.0, 0.0))
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE }, "nothing was built: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains(ed.doc.nameOf(p)), "and it named the point: ${ed.statusHint}")

        // …and the build refuses a pick that is not a point at all, by name
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        val seg = ed.doc.elements.last { it.kind == ElementKind.SEGMENT }
        assertEquals(null, ed.doc.helixThrough(seg, p, ed.doc.newParameter("p1", 5.0.mm).ref, null, Handedness.RIGHT))
        assertTrue(ed.doc.note?.contains("not a point") == true, "the build names it: ${ed.doc.note}")
    }

    /**
     * **The pitch refusals are the same refusals**, whichever spelling built the coil — one statement of one
     * doctrine, rather than a message that drifts with the tool that was used.
     */
    @Test
    fun theRiseRefusalsReadTheSameInBothSpellings() {
        val ed = Editor()
        val el = coil(ed, Vec2(0.0, 0.0), Vec2(25.0, 0.0))
        val pitch = assertNotNull(ed.doc.scalars.lastOrNull { it.name == "pitch" }, "the pitch is a panel row")

        ed.doc.setParameter(pitch, 0.0.mm)
        val flat = Evaluator().eval(el.ref.node)
        assertTrue(flat is EvalResult.Invalid && flat.reason.contains("circle"), "a coil that does not rise is a circle: $flat")

        ed.doc.setParameter(pitch, (-8.0).mm)
        val down = Evaluator().eval(el.ref.node)
        assertTrue(down is EvalResult.Invalid && down.reason.contains("left-hand"), "a descending right-hand coil is the other one: $down")

        ed.doc.setParameter(pitch, 12.0.mm)
        assertTrue(Evaluator().eval(el.ref.node) is EvalResult.Ok, "and it heals")
    }

    // ---- the preview ----

    /**
     * **The base circle is drawn before the second click** — the radius *and* the bearing, which is what
     * makes a stated phase something the user can aim rather than compute.
     */
    @Test
    fun theBaseCircleIsPreviewedWhileTheStartPointIsBeingChosen() {
        val ed = Editor()
        ed.pointAt(Vec2(0.0, 0.0))
        ed.setTool(Tools.HELIX_PT)
        ed.type("10")
        assertTrue(ed.previewShapes.isEmpty(), "nothing is drawn before the centre is picked")
        ed.click(Vec2(0.0, 0.0))
        ed.hover(Vec2(0.0, 40.0))
        assertEquals(1, ed.previewShapes.size, "the base circle is drawn: ${ed.previewShapes}")
        val nodes = ed.doc.cx.nodesCreated
        ed.hover(Vec2(20.0, 20.0))
        assertEquals(nodes, ed.doc.cx.nodesCreated, "and hovering touched no node")
    }

    // ---- the plan projection is still the round shadow ----

    /** The coil's plan projection is the circle its two points state, wherever on it the coil begins. */
    @Test
    fun thePlanProjectionIsTheCircleTheTwoPointsState() {
        val ed = Editor()
        val el = coil(ed, Vec2(0.0, 0.0), Vec2(0.0, 22.0))
        val pts = constructit.geom.Curves3.polyline(Evaluator().path3(el.ref as Path3Ref))
        for (p in pts) {
            assertClose(Vec2(p.x, p.y).length(), 22.0, 1e-6, "every point of the coil is 22 mm from the axis")
        }
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE), "the plan is where it was drawn")
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(0.0, 22.0))
        assertNotNull(ed.selection, "and its shadow is clickable: ${ed.statusHint}")
    }

    /** The turn count is still optional, and one turn is what an untyped count means. */
    @Test
    fun theTurnCountStaysOptional() {
        val ed = Editor()
        val el = coil(ed, Vec2(0.0, 0.0), Vec2(12.0, 5.0), turns = null)
        assertEquals(1.0, helixOf(el).turns, "one turn, because the count was not typed")
        assertClose(helixOf(el).radius, Vec2(12.0, 5.0).length(), 1e-9)
        assertTrue(ed.statusHint.contains("right-hand helix"), "and the tool said what it made: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("13 mm radius"), "including the radius the picks bought: ${ed.statusHint}")
    }

    /** A start point that has been **lifted** says the same thing as one that has not: only its offset across the axis counts. */
    @Test
    fun aLiftedStartPointStatesTheSamePhase() {
        val ed = Editor()
        val flat = coil(ed, Vec2(0.0, 0.0), Vec2(0.0, 30.0))
        val h = helixOf(flat)

        // the same two places, but the start point lifted 25 mm out of the plane
        val ed2 = Editor()
        ed2.pointAt(Vec2(200.0, 0.0))
        ed2.pointAt(Vec2(200.0, 30.0))
        ed2.setTool(Tools.HEIGHT_POINT)
        ed2.type("25")
        ed2.click(Vec2(200.0, 30.0))
        assertNotNull(ed2.doc.elements.lastOrNull { it.kind == ElementKind.HEIGHT_POINT }, "the lifted point: ${ed2.statusHint}")
        val centre = ed2.doc.elements.first { it.kind == ElementKind.POINT }
        val lifted = ed2.doc.elements.last { it.kind == ElementKind.HEIGHT_POINT }
        val el =
            assertNotNull(
                ed2.doc.helixThrough(centre, lifted, ed2.doc.newParameter("p2", 12.0.mm).ref, null, Handedness.RIGHT),
                "${ed2.doc.note}",
            )
        val h2 = helixOf(el)
        assertClose(h2.radius, h.radius, 1e-9, "the axial part of the offset states no radius")
        assertVec(h2.at(0.0), Vec3(200.0, 30.0, 0.0), "and the coil still begins level with its centre, on that bearing")
        assertVec(h2.axis, Vec3.Z, "rising along the space's normal")
    }

    /**
     * A start point standing **directly over** the centre states no direction at all, and says so — the one
     * degenerate case the editor's own gesture cannot reach (two clicks at one place are one point), so it
     * is asked of the construction itself.
     */
    @Test
    fun aStartPointDirectlyOverTheCentreStatesNoPhase() {
        val cx = Construction()
        val plane = cx.planeXY()
        val base = cx.freePoint("s", 0.0.mm, 0.0.mm)
        val centre = cx.heightPoint(plane, cx.freePoint("c", 0.0.mm, 0.0.mm), cx.const(0.0.mm))
        val over = cx.heightPoint(plane, base, cx.const(40.0.mm))
        val path = cx.helixThrough(plane, centre, over, cx.const(10.0.mm), cx.const(Quantity.number(1.0)), Handedness.RIGHT)

        val bad = Evaluator().eval(path.node)
        assertTrue(bad is EvalResult.Invalid, "a start point on the axis is no start point: $bad")
        assertTrue((bad as EvalResult.Invalid).reason.contains("stands on the axis"), "and says exactly that: ${bad.reason}")

        // …and it heals the moment that point's base moves off the axis, lift and all
        cx.set(base, 20.0.mm, 0.0.mm)
        val ok = Evaluator().eval(path.node)
        assertTrue(ok is EvalResult.Ok, "it heals when the base moves off the axis: $ok")
        val h = Evaluator().path3(path).elements.single() as Curve3Element.Helix3
        assertClose(h.radius, 20.0, 1e-12, "the lift states no radius — only the offset across the axis does")
        assertVec(h.at(0.0), Vec3(20.0, 0.0, 0.0), "and the coil begins level with its centre, on that bearing")
    }

    /** A coil about a start point 45° round is exactly where trigonometry says, to the last digit it can be. */
    @Test
    fun theWholeCoilIsThePlainRotationOfTheOldOne() {
        val ed = Editor()
        val a = PI / 4.0
        val el = coil(ed, Vec2(0.0, 0.0), Vec2(20.0 * cos(a), 20.0 * sin(a)), pitch = "12", turns = "1")
        val h = helixOf(el)
        for (i in 0..8) {
            val t = i / 8.0
            val theta = a + 2.0 * PI * t
            val want = Vec3(20.0 * cos(theta), 20.0 * sin(theta), 12.0 * t)
            assertVec(h.at(t), want, "the coil is the phase-free one turned by 45°", 1e-9)
        }
    }
}
