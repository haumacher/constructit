package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Path3Ref
import constructit.dsl.SolidRef
import constructit.dsl.path3
import constructit.dsl.solid
import constructit.editor.Camera3
import constructit.editor.DocumentFormat
import constructit.editor.DrawTarget
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.PlanePerspective
import constructit.editor.Scene3
import constructit.editor.Scene3Sync
import constructit.editor.Style
import constructit.editor.SvgDrawTarget
import constructit.editor.TextAnchor
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.Curve3Element
import constructit.geom.Handedness
import constructit.geom.Plane3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The helix as a gesture, and as an ordinary curve in space** (OP-26, step 3).
 *
 * The geometry is [HelixTest]'s; this is the other half of the claim — that what comes out of two typed
 * numbers and one click is a curve like the ones step 1 built. It is drawn in the 3D view and projected into
 * the plan, it can be clicked in both, it can be swept along, it hides, it renames, one undo takes the
 * gesture back, and `save → load → save` is byte-equal *including its handedness*, which is recorded by
 * nothing but the tool id. Adding it cost the tool table two rows and the controller nothing.
 */
class HelixToolTest {
    private val wPx = 800.0
    private val hPx = 600.0

    /** A target that only counts what it was given — what a claim about *work* is asserted against. */
    private class Counting : DrawTarget {
        val runs = ArrayList<List<Vec2>>()

        override fun begin(
            widthPx: Double,
            heightPx: Double,
        ) = Unit

        override fun polyline(
            points: List<Vec2>,
            style: Style,
        ) {
            runs.add(points)
        }

        override fun polygon(
            points: List<Vec2>,
            style: Style,
        ) {
            runs.add(points)
        }

        override fun circle(
            center: Vec2,
            radiusPx: Double,
            style: Style,
        ) = Unit

        override fun dot(
            center: Vec2,
            radiusPx: Double,
            color: String,
        ) = Unit

        override fun text(
            at: Vec2,
            text: String,
            style: Style,
            anchor: TextAnchor,
        ) = Unit

        override fun end() = Unit

        val points: Int get() = runs.sumOf { it.size }
    }

    // ---- driving the editor ----

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

    private fun Viewport3.clickAt(screen: Vec2) {
        pointerMove(screen)
        pointerDown(screen)
        pointerUp(screen)
    }

    /** Where the point [lift] mm over plane point [base] is seen in this view — what a click there aims at. */
    private fun Viewport3.atLifted(
        base: Vec2,
        lift: Double,
    ): Vec2 = assertNotNull(assertNotNull(projection()).toScreenLifted(base, lift), "the lifted point has an image")

    /** Hand the editor back to the 2D canvas, which is what the shell's view switch does. */
    private fun toPlan(vp: Viewport3) {
        vp.shown = false
    }

    private fun view(ed: Editor): Viewport3 {
        val vp =
            Viewport3(
                camera = Camera3(target = Vec3(0.0, 0.0, 20.0), distance = 320.0, yaw = -0.9, pitch = 0.55),
                widthPx = wPx,
                heightPx = hPx,
            )
        vp.editor = ed
        vp.shown = true
        return vp
    }

    /**
     * The fixture: a plain plan point, then the helix tool — a radius, a pitch, a turn count and one click.
     *
     * The point is an ordinary 2D one, lifted by a **zero** height onto its own space's plane, which is step
     * 1's own rule for a `POINT3` slot: a coil can be placed in the plan with no height gesture at all.
     */
    private fun coilAt(
        ed: Editor,
        at: Vec2,
        radius: String = "20",
        pitch: String = "12",
        turns: String? = "3",
        left: Boolean = false,
    ): Element {
        ed.setTool(Tools.POINT)
        ed.click(at)
        ed.setTool(if (left) Tools.HELIX_LEFT else Tools.HELIX)
        ed.type(radius)
        ed.type(pitch)
        if (turns != null) ed.type(turns)
        ed.click(at)
        return assertNotNull(
            ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE },
            "the coil was built: ${ed.statusHint}",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun helixOf(el: Element): Curve3Element.Helix3 =
        Evaluator().path3(el.ref as Path3Ref).elements.single() as Curve3Element.Helix3

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    // ---- the gesture ----

    /**
     * **Two numbers and a click, with the third number optional.** The tool waits for the radius and the
     * pitch — a coil is neither without them — and takes one turn unless a count is typed, which is
     * `requiredScalars` doing exactly the job step 2 built it for.
     */
    @Test
    fun aRadiusAPitchAndOneClickBuildACoilAboutThatPoint() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.HELIX)
        ed.type("20")
        ed.type("12")
        ed.click(Vec2(0.0, 0.0))
        val coil = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, "built: ${ed.statusHint}")

        val h = helixOf(coil)
        assertEquals(20.0, h.radius, "the radius that was typed")
        assertEquals(12.0, h.pitch, "and the pitch")
        assertEquals(1.0, h.turns, "and one turn, because the count was not typed")
        assertEquals(Handedness.RIGHT, h.hand, "and the tool that was used says which way it turns")
        assertVec(h.axis, Vec3.Z, "the axis is the sketch plane's own normal")
        assertVec(h.origin, Vec3.ZERO, "standing on the point that was clicked")
        assertTrue(ed.statusHint.contains("right-hand helix"), "and the tool said what it made: ${ed.statusHint}")
    }

    /** **The other tool is the other handedness**, and that is the only difference between them. */
    @Test
    fun theLeftHandToolBuildsTheMirrorCoilAndNothingElseChanges() {
        val ed = Editor()
        val right = coilAt(ed, Vec2(0.0, 0.0))
        val left = coilAt(ed, Vec2(80.0, 0.0), left = true)
        assertEquals(Handedness.RIGHT, helixOf(right).hand)
        assertEquals(Handedness.LEFT, helixOf(left).hand)
        assertEquals(helixOf(right).radius, helixOf(left).radius, "the same radius")
        assertEquals(helixOf(right).pitch, helixOf(left).pitch, "the same pitch")
        // the two coils are mirror images: the same rise, the opposite turn
        assertClose(helixOf(right).at(0.25).z, helixOf(left).at(0.25).z, 1e-12, "both climb the same way")
        assertClose(
            (helixOf(right).at(0.25) - helixOf(right).origin).dot(helixOf(right).bi),
            -(helixOf(left).at(0.25) - helixOf(left).origin).dot(helixOf(left).bi),
            1e-12,
            "and turn opposite ways",
        )
    }

    /**
     * **The coil rides its parents** (OP-26's parenting rule) — the point it stands on and the space whose
     * normal is its axis. Drag the point in the plan and the whole spring moves; the axis stays that space's.
     */
    @Test
    fun draggingTheAxisPointMovesTheWholeCoil() {
        val ed = Editor()
        val coil = coilAt(ed, Vec2(10.0, 20.0))
        assertVec(helixOf(coil).origin, Vec3(10.0, 20.0, 0.0), "it stands where the point does")

        val pt = ed.doc.elements.first { it.kind == ElementKind.POINT }
        ed.setTool(Tools.SELECT)
        val from = ed.camera.worldToScreen(Vec2(10.0, 20.0))
        ed.pointerMove(from)
        ed.pointerDown(from)
        val to = ed.camera.worldToScreen(Vec2(45.0, -15.0))
        ed.pointerMove(to)
        ed.pointerUp(to)
        assertNotNull(pt, "the base point is an ordinary point")
        assertVec(helixOf(coil).origin, Vec3(45.0, -15.0, 0.0), "and the coil followed it, in one recompute")
        assertVec(helixOf(coil).axis, Vec3.Z, "with its axis still the space's normal")
    }

    /**
     * **A coil about a height point stands where that point stands** — the `POINT3` slot takes an existing
     * point in space as it is and **shares its node**, so retyping the height raises the whole spring.
     */
    @Test
    fun aCoilAboutAHeightPointRisesWithIt() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.HEIGHT_POINT)
        ed.type("40")
        ed.click(Vec2(0.0, 0.0))
        assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.HEIGHT_POINT }, "the height point was made")

        ed.setTool(Tools.HELIX)
        ed.type("15")
        ed.type("10")
        ed.type("2")
        val vp = view(ed)
        vp.clickAt(vp.atLifted(Vec2(0.0, 0.0), 40.0))
        val coil = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, "built: ${ed.statusHint}")
        assertVec(helixOf(coil).origin, Vec3(0.0, 0.0, 40.0), "it stands on the lifted point")

        val height = assertNotNull(ed.doc.scalars.lastOrNull { it.name == "height" }, "the height is a panel row")
        ed.doc.setParameter(height, 70.0.mm)
        assertVec(helixOf(coil).origin, Vec3(0.0, 0.0, 70.0), "and the whole coil rose with it — one shared node")
    }

    // ---- an ordinary curve in space: the views, the picks, the sweep, the file ----

    /**
     * **Drawn in both views, and picked in both.** The 3D view draws the world curve out of the scene; the
     * plan draws its projection — which for a helix about the plan's own normal is the circle it casts, drawn
     * as the chords the two views share.
     */
    @Test
    fun theCoilIsDrawnInBothViewsAndClickableInBoth() {
        val ed = Editor()
        val coil = coilAt(ed, Vec2(0.0, 0.0))
        assertEquals(1, Scene3.extract(ed.doc).curves.size, "the 3D view has the curve, in space")

        // in the 3D view: a click on the curve where it honestly stands in space
        val vp = view(ed)
        ed.setTool(Tools.SELECT)
        val world = helixOf(coil).at(0.35)
        vp.clickAt(vp.atLifted(Vec2(world.x, world.y), world.z))
        assertEquals(coil, ed.selection, "the 3D view took the click: ${ed.statusHint}")

        // …and in the plan, on the round shadow it casts, well away from the point at its centre
        toPlan(vp)
        ed.click(Vec2(300.0, 300.0))
        assertEquals(null, ed.selection, "empty space clears the selection first")
        ed.click(Vec2(20.0, 0.0))
        assertEquals(coil, ed.selection, "and the plan projection took the click too: ${ed.statusHint}")
    }

    /**
     * **A tube swept along it is a spring**, and it is an ordinary solid — which is the whole point of the
     * helix arriving after the sweep rather than before it.
     */
    @Test
    fun aTubeAlongTheCoilIsAnOrdinarySpringSolid() {
        val ed = Editor()
        val coil = coilAt(ed, Vec2(0.0, 0.0))
        ed.setTool(Tools.TUBE)
        ed.type("3")
        ed.click(Vec2(20.0, 0.0))
        val spring = assertNotNull(ed.solids().lastOrNull(), "the spring was built: ${ed.statusHint}")

        @Suppress("UNCHECKED_CAST")
        val solid = Evaluator().solid(spring.ref as SolidRef)
        assertManifold(solid.mesh, "the spring")
        assertTrue(solid.feature.footprint.isNotEmpty(), "and it shows a plan footprint like any other solid")

        // the footprint is the coil's silhouette: it reaches the coil radius plus the wire radius
        val reach =
            solid.feature.footprint.flatMap { r -> r.outer.elements.map { constructit.geom.GeomMath.startOf(it).length() } }
        assertTrue(reach.any { abs(it - 23.0) < 0.3 }, "out to the wire's outside: ${reach.maxOrNull()}")
        assertTrue(ed.doc.nameOf(coil).isNotEmpty(), "and the coil it rides is named like anything else")
    }

    /**
     * **`save → load → save` is byte-equal, and the handedness survives it** — recorded by nothing but the
     * tool id (OP-18), which is exactly the claim two tool rows were chosen to make.
     */
    @Test
    fun theCoilSurvivesSaveAndLoadByteForByteIncludingItsHandedness() {
        val ed = Editor()
        coilAt(ed, Vec2(0.0, 0.0), left = true)
        ed.setTool(Tools.TUBE)
        ed.type("3")
        ed.click(Vec2(20.0, 0.0))
        assertEquals(1, ed.solids().size, "the spring rides it: ${ed.statusHint}")

        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.lines().any { it.startsWith("tool ${Tools.HELIX_LEFT}") }, "the step records the tool id: $once")
        val doc = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(doc), "the script round-trips byte for byte")

        val back = doc.elements.last { it.kind == ElementKind.SPACE_CURVE }

        @Suppress("UNCHECKED_CAST")
        val h = Evaluator().path3(back.ref as Path3Ref).elements.single() as Curve3Element.Helix3
        assertEquals(Handedness.LEFT, h.hand, "and it comes back left-handed, from the tool id alone")
        assertEquals(20.0, h.radius)
        assertEquals(12.0, h.pitch)
        assertEquals(3.0, h.turns)

        val before = Evaluator().solid(ed.solids().single().ref as SolidRef).mesh
        val after = Evaluator().solid(doc.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef).mesh
        assertEquals(before.vertices, after.vertices, "and the spring reloads vertex for vertex")
        assertEquals(before.triangles, after.triangles, "and triangle for triangle")
    }

    /** **One gesture, one undo** — and the point it stands on stays, because it was not part of this gesture. */
    @Test
    fun oneUndoTakesTheWholeHelixGestureBack() {
        val ed = Editor()
        coilAt(ed, Vec2(0.0, 0.0))
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE })

        assertTrue(ed.undo(), "the coil is taken back")
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE }, "one checkpoint covered the gesture")
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.POINT }, "and the point it stood on stays")
        assertTrue(ed.redo(), "and it comes back")
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE })
    }

    /** **It hides**, and hiding it takes it out of the 3D scene like every other element. */
    @Test
    fun theCoilHidesLikeAnythingElse() {
        val ed = Editor()
        val coil = coilAt(ed, Vec2(0.0, 0.0))
        assertEquals(1, Scene3.extract(ed.doc).curves.size)
        assertEquals(1, ed.doc.setElementsVisible(listOf(coil), false), "hide it")
        assertEquals(0, Scene3.extract(ed.doc).curves.size, "and it leaves the 3D view")
        assertEquals(1, ed.doc.setElementsVisible(listOf(coil), true), "show it again")
        assertEquals(1, Scene3.extract(ed.doc).curves.size)
    }

    // ---- the refusals ----

    /** A pick that is not a point is refused **by name**, and nothing is built. */
    @Test
    fun aHelixAboutSomethingThatIsNotAPointIsRefusedByName() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(80.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(80.0, 0.0))
        val seg = ed.doc.elements.last { it.kind == ElementKind.SEGMENT }

        // the slot itself declines a segment, so the click never reaches the build
        ed.setTool(Tools.HELIX)
        ed.type("10")
        ed.type("5")
        ed.click(Vec2(40.0, 0.0))
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE }, "nothing was built")

        // …and the build refuses the same thing by name when it is handed one directly
        assertEquals(
            null,
            ed.doc.helixAbout(seg, ed.doc.newParameter("r", 10.0.mm).ref, ed.doc.newParameter("p", 5.0.mm).ref, null, Handedness.RIGHT),
        )
        assertTrue(ed.doc.note?.contains("not a point") == true, "the build names it: ${ed.doc.note}")
    }

    /**
     * **The node's refusals are the node's, and they heal** (OP-3): a pitch retyped to nothing makes the coil
     * invalid *with the other way of saying it named*, and everything swept along it hides until the number is
     * sane again.
     */
    @Test
    fun aPitchTypedToNothingMakesTheCoilInvalidWithACureNamedAndHeals() {
        val ed = Editor()
        val coil = coilAt(ed, Vec2(0.0, 0.0))
        ed.setTool(Tools.TUBE)
        ed.type("3")
        ed.click(Vec2(20.0, 0.0))
        val spring = assertNotNull(ed.solids().lastOrNull(), "the spring: ${ed.statusHint}")
        assertEquals(1, Scene3.extract(ed.doc).solids.size)

        val pitch = assertNotNull(ed.doc.scalars.lastOrNull { it.name == "pitch" }, "the pitch is a panel row")
        ed.doc.setParameter(pitch, 0.0.mm)
        val bad = Evaluator().eval(coil.ref.node)
        assertTrue(bad is EvalResult.Invalid, "a coil that does not rise is invalid: $bad")
        assertTrue((bad as EvalResult.Invalid).reason.contains("circle"), "and names what it would be: ${bad.reason}")
        assertTrue(Evaluator().eval(spring.ref.node) is EvalResult.Invalid, "and the spring hides with it")
        assertEquals(0, Scene3.extract(ed.doc).curves.size, "an invalid curve draws nothing")

        ed.doc.setParameter(pitch, 12.0.mm)
        assertTrue(Evaluator().eval(coil.ref.node) is EvalResult.Ok, "and it heals")
        assertEquals(1, Scene3.extract(ed.doc).solids.size, "with the spring back in the view")
    }

    // ---- the perf contract, with a coil in the drawing (session 35's gate) ----

    /**
     * **One view-projection matrix per frame, and an orbit and a hover upload nothing** — the session-35
     * contract, asked of a curve whose polyline is hundreds of points rather than three.
     */
    @Test
    fun aDrawingWithACoilStillCostsAnOrbitAndAHoverNothing() {
        val ed = Editor()
        val coil = coilAt(ed, Vec2(0.0, 0.0), turns = "5")
        val vp = view(ed)

        val sync = Scene3Sync()
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(1, sync.uploads, "the first look uploads")
        assertEquals(1, Scene3.extract(ed.doc).curves.size, "and the coil is in the scene")

        repeat(20) { sync.update(Scene3.extract(ed.doc)) { } }
        assertEquals(1, sync.uploads, "an unchanged document is the same path object every time")

        ed.setTool(Tools.SEGMENT)
        for (i in 0 until 30) vp.pointerMove(Vec2(200.0 + i, 300.0 + i))
        assertEquals(1, sync.uploads, "a hover moves no vertex")

        vp.cameraModifier = true
        vp.pointerDown(Vec2(400.0, 300.0))
        for (i in 0 until 20) vp.pointerMove(Vec2(400.0 + i * 3, 300.0))
        vp.pointerUp(Vec2(460.0, 300.0))
        vp.cameraModifier = false
        assertEquals(1, sync.uploads, "an orbit uploads nothing")

        ed.doc.nameElement(coil, "feder")
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(1, sync.uploads, "a name is not vertex data")

        val radius = assertNotNull(ed.doc.scalars.lastOrNull { it.name == "radius" })
        ed.doc.setParameter(radius, 26.0.mm)
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(2, sync.uploads, "a coil that changed is new vertex data")

        // …and the plan of a drawing containing one still builds exactly one matrix per frame. Selected, so
        // that the coil's own **world** polyline goes through the projection as well — the emphasis is the
        // one thing drawn through `toScreenLifted`, a second door into the same camera (session 35).
        toPlan(vp)
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(26.0, 0.0))
        assertEquals(coil, ed.selection, "the coil is the subject: ${ed.statusHint}")

        val proj = PlanePerspective(Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), Viewport3().camera, wPx, hPx)
        assertEquals(0, proj.matrixBuilds, "nothing drawn yet")
        ed.pointing = proj
        val rec = Counting()
        ed.draw(rec, wPx, hPx)
        assertTrue(rec.points > 100, "the coil's own polyline went through it: ${rec.points} points")
        assertEquals(1, proj.matrixBuilds, "one matrix for all of them")
    }

    // ---- the picture ----

    /**
     * **…and it is drawn**: the plan projection of a coil, as an SVG golden.
     *
     * What this pins is the shape the plan shows — a helix about the plan's own normal casts a closed round
     * shadow, drawn as the chords its own sampling states, with the coordinates pinned exactly by the tests
     * above.
     */
    @Test
    fun theCoilsPlanProjectionIsDrawn() {
        val ed = Editor()
        coilAt(ed, Vec2(-30.0, 10.0), radius = "40", pitch = "15", turns = "2")
        ed.setTool(Tools.SELECT)
        val target = SvgDrawTarget()
        ed.render(target)
        Golden.check("helix_plan", target.svg())
    }

    private fun assertVec(
        actual: Vec3,
        expected: Vec3,
        msg: String,
    ) {
        assertTrue((actual - expected).length() <= 1e-9, "$msg (was $actual, wanted $expected)")
    }
}
