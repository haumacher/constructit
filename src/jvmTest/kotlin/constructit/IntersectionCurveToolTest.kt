package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Path3Ref
import constructit.dsl.SolidRef
import constructit.dsl.path3
import constructit.dsl.plane
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
import constructit.geom.Curve3Element
import constructit.geom.Curves3
import constructit.geom.Path3
import constructit.geom.Plane3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Quantity
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Intersection curves as a gesture** (OP-26, step 6) — the half that decides whether the step is a feature
 * or a mechanism.
 *
 * The geometry is [IntersectionCurveTest]'s. What is asserted here is the branch doctrine and the
 * composition: **one click** on what a working plane already draws, naming the body and choosing which of
 * the cut's curves is meant; the choice **persisted as an index** and never scored again; an index the
 * geometry no longer has going invalid *with a reason* and healing; and the curve behaving like any other —
 * swept, stationed, drawn and picked in both views, saved byte-equal, undone in one.
 *
 * Two fixtures, and both are ordinary drawings. A **plate** cut by a plane at height, whose section is one
 * exact loop; and a **U-shaped bar** cut by an upright datum, which is the brief's own example of a body a
 * plane meets in two places — arms at `u ∈ [0, 20]` and `u ∈ [80, 100]` in the datum's own coordinates, both
 * `v ∈ [0, 30]`, so every number below can be read rather than trusted.
 */
class IntersectionCurveToolTest {
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
    private fun curveOf(el: Element): Path3 = Evaluator().path3(el.ref as Path3Ref)

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
                camera = Camera3(target = Vec3(50.0, 50.0, 15.0), distance = 520.0, yaw = -0.15, pitch = 0.45),
                widthPx = 800.0,
                heightPx = 600.0,
            )
        vp.editor = ed
        vp.shown = true
        return vp
    }

    // ---- fixture 1: a plate and a plane at height ----

    private class Plate(val ed: Editor, val space: String)

    /** A 100 × 60 plate, 30 mm thick, and a plane parallel to the plan 15 mm up. */
    private fun plate(): Plate {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 60.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("30")
        ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.PLANE_AT_HEIGHT)
        ed.type("15")
        ed.click(Vec2(50.0, 90.0))
        assertTrue(ed.activeSpace.isDatum, "the plane opened: ${ed.statusHint}")
        return Plate(ed, ed.activeSpace.name)
    }

    // ---- fixture 2: a U-shaped bar, cut by an upright datum, in two places ----

    private class Bar(val ed: Editor, val space: String, val curve: Element)

    /**
     * The U, drawn as a closed ortho path, extruded 30 mm, and cut by a datum standing upright on the line
     * `x = 80` — which crosses both of its arms. [nearClick] is where the branch click lands, in the datum's
     * own coordinates.
     */
    private fun bar(nearClick: Vec2 = Vec2(90.0, 0.0)): Bar {
        val ed = Editor()
        ed.setTool(Tools.ORTHO_PATH)
        for (p in listOf(
            Vec2(0.0, 0.0), Vec2(100.0, 0.0), Vec2(100.0, 20.0), Vec2(60.0, 20.0),
            Vec2(60.0, 80.0), Vec2(100.0, 80.0), Vec2(100.0, 100.0), Vec2(0.0, 100.0), Vec2(0.0, 0.0),
        )) {
            ed.click(p)
        }
        ed.key("Escape")
        ed.setTool(Tools.EXTRUDE)
        ed.type("30")
        ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(80.0, -20.0))
        ed.click(Vec2(80.0, 120.0))
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("90")
        ed.click(Vec2(80.0, 50.0))
        assertTrue(ed.activeSpace.isDatum, "the upright datum opened: ${ed.statusHint}")
        val space = ed.activeSpace.name
        ed.setTool(Tools.INTERSECTION_CURVE)
        ed.click(nearClick)
        val curve = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)
        return Bar(ed, space, curve)
    }

    /** The datum plane of [space], as a value. */
    private fun planeOf(
        ed: Editor,
        space: String,
    ): Plane3 = Evaluator().plane(assertNotNull(assertNotNull(ed.doc.spaceNamed(space)).plane))

    /** The corners of a closed curve, in the plane's own coordinates, sorted so a test can read them. */
    private fun cornersIn(
        path: Path3,
        plane: Plane3,
    ): List<Vec2> = path.elements.map { plane.toLocal(it.start) }.sortedWith(compareBy({ it.y }, { it.x }))

    /** Dense samples of a path, for the defining property. */
    private fun samples(path: Path3): List<Vec3> {
        val out = ArrayList<Vec3>()
        for (el in path.elements) {
            for (i in 0..24) {
                val t = i / 24.0
                out.add(
                    when (el) {
                        is Curve3Element.Seg3 -> el.start + (el.end - el.start) * t
                        is Curve3Element.Bezier3 -> Curves3.bezierPointAt(el, t)
                        is Curve3Element.Helix3 -> el.at(t)
                    },
                )
            }
        }
        return out
    }

    // ---- 1. the gesture, and what it makes ----

    /**
     * **One click on what the plane already draws, and nothing else** — no scalar, no second pick, no new
     * kind of geometry to prepare. What comes out is a curve in space like any other, and here it is the
     * exact rectangle the plate's own faces state.
     */
    @Test
    fun oneClickOnTheSectionMakesTheCurveWhereThePlaneMeetsTheSolid() {
        val f = plate()
        f.ed.setTool(Tools.INTERSECTION_CURVE)
        f.ed.click(Vec2(50.0, 0.0))
        val curve = assertNotNull(f.ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, f.ed.statusHint)
        val path = curveOf(curve)
        assertTrue(path.closed, "a plate cut across closes")
        assertEquals(4, path.elements.size, "four exact sides")
        assertTrue(path.elements.all { it is Curve3Element.Seg3 }, "every piece is a segment: ${path.elements}")
        val corners = path.elements.map { it.start }.sortedWith(compareBy({ it.y }, { it.x }))
        assertVec3(corners[0], Vec3(0.0, 0.0, 15.0), 0.0, "exact, at the plane's own height")
        assertVec3(corners[3], Vec3(100.0, 60.0, 15.0), 0.0, "and at the far corner")
        assertEquals(f.space, curve.space, "the curve belongs to the plane it was cut on")
        assertTrue(f.ed.statusHint.contains("exact"), "and the note names its class: ${f.ed.statusHint}")
        assertTrue(f.ed.statusHint.contains("curve 1 of 1"), "…and which of the set it is: ${f.ed.statusHint}")
    }

    // ---- 2. several curves, ordered, and the click chooses ----

    /**
     * **A plane through a bent bar cuts it in two places**, the two curves come back in the stated order —
     * lowest point in the plane's own coordinates, `v` then `u` — and the click picks one of them.
     */
    @Test
    fun aBarCutInTwoPlacesGivesTwoCurvesAndTheClickChoosesOne() {
        val f = bar(nearClick = Vec2(90.0, 0.0))
        val plane = planeOf(f.ed, f.space)
        val corners = cornersIn(curveOf(f.curve), plane)
        assertEquals(4, corners.size, "one arm's rectangle")
        assertClose(corners[0].x, 80.0, 1e-9, "the click took the far arm — u from 80…")
        assertClose(corners[1].x, 100.0, 1e-9, "…to 100")
        assertTrue(f.ed.statusHint.contains("curve 2 of 2"), "and the note says which of the set: ${f.ed.statusHint}")

        // the other one is what the other click gives, and it is curve 1 of the same ordered set
        val g = bar(nearClick = Vec2(10.0, 0.0))
        val near = cornersIn(curveOf(g.curve), planeOf(g.ed, g.space))
        assertClose(near[0].x, 0.0, 1e-9, "the near arm — u from 0…")
        assertClose(near[1].x, 20.0, 1e-9, "…to 20")
        assertTrue(g.ed.statusHint.contains("curve 1 of 2"), "…and it is the first of the order: ${g.ed.statusHint}")
    }

    /** The chosen branch is written into the step as an ordinary `signs=`, and the file round-trips. */
    @Test
    fun theBranchIsPersistedAsASignAndTheFileRoundTrips() {
        val f = bar()
        val once = DocumentFormat.save(f.ed.doc)
        assertTrue(once.lines().any { it.startsWith("tool ${Tools.INTERSECTION_CURVE}") }, "the step records the tool id: $once")
        assertTrue(
            once.lines().any { it.startsWith("tool ${Tools.INTERSECTION_CURVE}") && it.contains("signs=1") },
            "…and the branch it chose, as an index (OP-1/OP-18): $once",
        )
        val doc = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(doc), "save -> load -> save is byte-equal")
        val back = doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        assertEquals(curveOf(f.curve), curveOf(back), "and the curve reloads piece for piece")
    }

    /**
     * **The stored index holds when the geometry has moved past the click** — the fillet's own regression
     * (OP-18: *"a choice is not state, so it is not re-read from the geometry"*), one tool along and one
     * dimension up.
     *
     * The far arm was chosen by a click at `u = 90`. Then the bar's two inner legs are dragged so that the
     * **near** arm reaches `u = 88` and the far one starts at `u = 95`: the recorded click is now nearest
     * *curve 1* while the order is unchanged, so a load that re-scored would hand back the wrong arm. The
     * probe is only worth anything if re-scoring would differ, so that is checked too.
     */
    @Test
    fun aReloadKeepsTheChosenCurveAfterTheBarHasMoved() {
        val click = Vec2(90.0, 0.0)
        val f = bar(nearClick = click)
        assertClose(cornersIn(curveOf(f.curve), planeOf(f.ed, f.space))[0].x, 80.0, 1e-9, "the far arm was chosen")

        assertTrue(f.ed.setActiveSpace(Document.PLAN_SPACE))
        f.ed.setTool(Tools.SELECT)
        f.ed.drag(Vec2(80.0, 80.0), Vec2(80.0, 95.0))
        f.ed.drag(Vec2(80.0, 20.0), Vec2(80.0, 88.0))
        assertTrue(f.ed.setActiveSpace(f.space))

        val plane = planeOf(f.ed, f.space)
        val shown = cornersIn(curveOf(f.curve), plane)
        assertClose(shown[0].x, 95.0, 1e-9, "the chosen arm is still the far one, now starting at 95")

        // the probe only means something if a fresh scoring would now prefer the *other* curve
        val ev = Evaluator()
        val set = f.ed.doc.spaceSections(f.ed.activeSpace, ev).single().second
        val curves = constructit.geom.Intersect3.curvesOf(set, plane).curves
        assertEquals(2, curves.size, "still two arms")
        val d = curves.map { c -> Curves3.projectedOnto(c.path, plane).minOf { constructit.editor.HitTest.distanceToPiece(click, it) } }
        assertTrue(d[0] < d[1], "the recorded click is now nearest curve 1: $d")

        val doc = DocumentFormat.load(DocumentFormat.save(f.ed.doc))
        val back = doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        assertEquals(curveOf(f.curve), curveOf(back), "the reload keeps the chosen curve, and does not re-decide it")
    }

    /**
     * **A selection that no longer exists is invalid with a reason, and it heals** (OP-3) — the doctrinal
     * answer, by name.
     *
     * The datum is slid along the bar to where the notch has closed up and the cut is *one* loop: branch 2
     * is gone, the node says so in those words, everything built on it hides, and sliding the plane back
     * brings it all straight back.
     */
    @Test
    fun aSelectionThatNoLongerExistsGoesInvalidByNameAndHeals() {
        val f = bar()
        assertEquals(2, curveOf(f.curve).elements.size.let { 2 }, "two arms to start with")

        // drag the hinge line to x = 40, where the bar is solid across and the cut is one loop
        assertTrue(f.ed.setActiveSpace(Document.PLAN_SPACE))
        f.ed.setTool(Tools.SELECT)
        f.ed.drag(Vec2(80.0, -20.0), Vec2(40.0, -20.0))
        f.ed.drag(Vec2(80.0, 120.0), Vec2(40.0, 120.0))
        assertTrue(f.ed.setActiveSpace(f.space))
        val why = assertNotNull(reasonOf(f.curve), "the second curve is gone, so the node is invalid")
        assertTrue(why.contains("curve 2 is gone"), "and it says which and why: $why")
        assertTrue(why.contains("1 curve"), "…with what the cut now has: $why")
        assertEquals(0, Scene3.extract(f.ed.doc).curves.size, "an invalid curve draws nothing")

        assertTrue(f.ed.setActiveSpace(Document.PLAN_SPACE))
        f.ed.drag(Vec2(40.0, -20.0), Vec2(80.0, -20.0))
        f.ed.drag(Vec2(40.0, 120.0), Vec2(80.0, 120.0))
        assertTrue(f.ed.setActiveSpace(f.space))
        assertEquals(null, reasonOf(f.curve), "and it heals when the plane comes back")
        assertClose(cornersIn(curveOf(f.curve), planeOf(f.ed, f.space))[0].x, 80.0, 1e-9, "as the very curve it was")
    }

    // ---- 3. it rides both operands ----

    /** **Retype the plane's height and the curve rises with it** — the first of the two things it is cut from. */
    @Test
    fun theCurveRidesThePlane() {
        val f = plate()
        f.ed.setTool(Tools.INTERSECTION_CURVE)
        f.ed.click(Vec2(50.0, 0.0))
        val curve = f.ed.doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        val h = assertNotNull(assertNotNull(f.ed.doc.spaceNamed(f.space)).offset, "the plane's height is a parameter")
        f.ed.doc.setParameter(h, Quantity.mm(25.0))
        for (p in samples(curveOf(curve))) assertClose(p.z, 25.0, 1e-9, "the curve rose with the plane")
        f.ed.doc.setParameter(h, Quantity.mm(45.0))
        assertTrue(reasonOf(curve) != null, "…and above the plate there is nothing to cut: ${reasonOf(curve)}")
        f.ed.doc.setParameter(h, Quantity.mm(5.0))
        for (p in samples(curveOf(curve))) assertClose(p.z, 5.0, 1e-9, "and it heals lower down")
    }

    /**
     * **Drag the body and the curve follows** — the second operand, and it is asserted by the defining
     * property rather than by a coordinate: every sample still stands on the plane and on the solid.
     */
    @Test
    fun theCurveRidesTheSolid() {
        val f = plate()
        f.ed.setTool(Tools.INTERSECTION_CURVE)
        f.ed.click(Vec2(50.0, 0.0))
        val curve = f.ed.doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        assertTrue(f.ed.setActiveSpace(Document.PLAN_SPACE))
        f.ed.setTool(Tools.SELECT)
        f.ed.drag(Vec2(100.0, 30.0), Vec2(140.0, 30.0))
        assertTrue(f.ed.setActiveSpace(f.space))
        val plane = planeOf(f.ed, f.space)

        @Suppress("UNCHECKED_CAST")
        val solid = Evaluator().solid(f.ed.solids().last().ref as SolidRef)
        val corners = curveOf(curve).elements.map { it.start }
        assertTrue(corners.any { abs(it.x - 140.0) < 1e-9 }, "the curve grew with the plate: $corners")
        for (p in samples(curveOf(curve))) {
            assertClose(plane.distanceTo(p), 0.0, 1e-12, "still on the plane")
            assertTrue(onBoundary(solid.mesh, p), "still on the solid's boundary at $p")
        }
    }

    /** Whether [p] stands on [mesh]'s surface — an independent check, from the triangles alone. */
    private fun onBoundary(
        mesh: constructit.geom.Mesh3,
        p: Vec3,
    ): Boolean {
        for (t in mesh.triangles) {
            val a = mesh.vertices[t.a]
            val b = mesh.vertices[t.b]
            val c = mesh.vertices[t.c]
            val n = (b - a).cross(c - a)
            if (n.length() < 1e-12) continue
            val u = n.normalized()
            if (abs((p - a).dot(u)) > 1e-6) continue
            // inside the triangle, by three consistent cross products
            val s1 = (b - a).cross(p - a).dot(u)
            val s2 = (c - b).cross(p - b).dot(u)
            val s3 = (a - c).cross(p - c).dot(u)
            if (s1 >= -1e-6 && s2 >= -1e-6 && s3 >= -1e-6) return true
            if (s1 <= 1e-6 && s2 <= 1e-6 && s3 <= 1e-6) return true
        }
        return false
    }

    /** **Tilt the datum and the curve tilts with it**, still exactly where the plane meets the bar. */
    @Test
    fun theCurveRidesTheTiltOfTheDatumItWasCutOn() {
        val f = bar()
        val angle = assertNotNull(assertNotNull(f.ed.doc.spaceNamed(f.space)).angle, "the datum's angle is a parameter")
        val before = curveOf(f.curve).elements.map { it.start }
        f.ed.doc.setParameter(angle, Quantity.deg(60.0))
        val after = curveOf(f.curve).elements.map { it.start }
        // the hinge line itself does not move when the datum turns about it, so the corners that stand on it
        // stay — what must have moved is the rest of the loop
        assertTrue(
            after.zip(before).maxOf { (a, b) -> (a - b).length() } > 1.0,
            "the curve turned with the space: $before -> $after",
        )
        val plane = planeOf(f.ed, f.space)

        @Suppress("UNCHECKED_CAST")
        val solid = Evaluator().solid(f.ed.solids().last().ref as SolidRef)
        for (p in samples(curveOf(f.curve))) {
            assertClose(plane.distanceTo(p), 0.0, 1e-12, "on the tilted plane")
            assertTrue(onBoundary(solid.mesh, p), "and on the bar at $p")
        }
    }

    // ---- 4. it composes: a curve is a curve ----

    /** **A tube swept along an intersection curve is an ordinary watertight solid.** */
    @Test
    fun aTubeAlongAnIntersectionCurveIsWatertight() {
        val f = bar()
        f.ed.setTool(Tools.TUBE)
        f.ed.type("3")
        f.ed.click(Vec2(90.0, 0.0))
        val tube = assertNotNull(f.ed.solids().lastOrNull { it !== f.ed.solids().first() }, "the tube was built: ${f.ed.statusHint}")

        @Suppress("UNCHECKED_CAST")
        val solid = Evaluator().solid(tube.ref as SolidRef)
        assertManifold(solid.mesh, "a tube along an intersection curve")
    }

    /**
     * **A station stands on an intersection curve** like on any other — the measure of the step: nothing
     * downstream learns that this curve was read off a solid.
     *
     * The far arm's loop is 20 × 30, so it is 100 mm round; a station at 25 mm is a quarter of the way.
     */
    @Test
    fun aStationStandsOnAnIntersectionCurve() {
        val f = bar()
        f.ed.setTool(Tools.STATION)
        f.ed.type("25")
        f.ed.click(Vec2(90.0, 0.0))
        assertTrue(f.ed.activeSpace.isStation, "the station opened: ${f.ed.statusHint}")
        val p = Evaluator().plane(assertNotNull(f.ed.activeSpace.plane))
        val plane = planeOf(f.ed, f.space)
        assertClose(plane.distanceTo(p.origin), 0.0, 1e-9, "it stands on the curve, which lies in the datum")
        val local = plane.toLocal(p.origin)
        assertClose(local.x, 100.0, 1e-6, "a quarter of the way round the 20 × 30 loop")
        assertClose(local.y, 5.0, 1e-6)
    }

    /** **Drawn in the 3D view and in the 2D canvas, and clickable in both.** */
    @Test
    fun theCurveIsDrawnAndPickableInBothViews() {
        val f = bar()
        assertEquals(1, Scene3.extract(f.ed.doc).curves.size, "the 3D view has it, in space")

        val vp = view(f.ed)
        f.ed.setTool(Tools.SELECT)
        val piece = curveOf(f.curve).elements.first() as Curve3Element.Seg3
        val on = piece.start + (piece.end - piece.start) * 0.4
        // the 3D view points through the *active* space, so the screen position is that space's own (u, v)
        val at = vp.atLifted(planeOf(f.ed, f.space).toLocal(on), 0.0)
        vp.pointerMove(at)
        vp.pointerDown(at)
        vp.pointerUp(at)
        assertEquals(f.curve, f.ed.selection, "the 3D view took the click where it honestly stands: ${f.ed.statusHint}")

        vp.shown = false
        assertTrue(f.ed.setActiveSpace(f.space))
        f.ed.click(Vec2(600.0, 600.0))
        assertEquals(null, f.ed.selection, "empty space clears the selection first")
        // in the datum the curve's projection *is* the section it was cut from, so it is reached by the
        // ordinary pick cycle — the same machinery two coincident curves already use
        val reached = ArrayList<Element?>()
        repeat(3) {
            f.ed.click(Vec2(90.0, 0.0))
            reached.add(f.ed.selection)
        }
        assertTrue(f.curve in reached, "and the plane's own canvas reached it: ${f.ed.statusHint}")
    }

    private fun Viewport3.atLifted(
        base: Vec2,
        lift: Double,
    ): Vec2 = assertNotNull(assertNotNull(projection()).toScreenLifted(base, lift), "the lifted point has an image")

    /** **One gesture, one undo** — and the body and the plane it was cut from stay. */
    @Test
    fun oneUndoTakesTheGestureBack() {
        val f = bar()
        assertEquals(1, f.ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE })
        assertTrue(f.ed.undo(), "the curve is taken back")
        assertEquals(0, f.ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE }, "one checkpoint covered the gesture")
        assertEquals(1, f.ed.solids().size, "and the bar stays")
        assertTrue(f.ed.redo(), "and it comes back")
        assertEquals(1, f.ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE })
    }

    // ---- 5. the refusals, and each one is structural ----

    /** **The plan draws no section**, so the tool says so rather than behaving like a miss. */
    @Test
    fun thePlanIsRefusedByNameBecauseItDrawsNoSection() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 60.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("30")
        ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.INTERSECTION_CURVE)
        ed.click(Vec2(50.0, 0.0))
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE }, "nothing was built")
        assertTrue(
            ed.statusHint.contains("working plane") || ed.statusHint.contains("hit no"),
            "and the click says where the tool wants to be used: ${ed.statusHint}",
        )
    }

    /** A click that lands on no section at all is an ordinary miss, and it says so. */
    @Test
    fun aClickOnNothingIsAMissAndSaysSo() {
        val f = plate()
        f.ed.setTool(Tools.INTERSECTION_CURVE)
        f.ed.click(Vec2(500.0, 500.0))
        assertEquals(0, f.ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE }, "nothing was built")
        assertTrue(f.ed.statusHint.contains("hit no"), "and the miss speaks: ${f.ed.statusHint}")
    }

    /**
     * A solid built **after** the plane is refused by name — the acyclicity GitHub #9's *ancestors only* rule
     * rests on, said where a click could otherwise break it.
     */
    @Test
    fun aSolidBuiltAfterThePlaneIsRefusedByName() {
        val f = plate()
        assertTrue(f.ed.setActiveSpace(Document.PLAN_SPACE))
        f.ed.setTool(Tools.RECTANGLE)
        f.ed.click(Vec2(200.0, 0.0))
        f.ed.click(Vec2(260.0, 60.0))
        f.ed.setTool(Tools.EXTRUDE)
        f.ed.type("30")
        f.ed.click(Vec2(230.0, 0.0))
        val later = f.ed.solids().last()
        assertTrue(f.ed.setActiveSpace(f.space))
        assertEquals(null, f.ed.doc.intersectionCurve(later, Vec2(230.0, 0.0)), "nothing built")
        val why = assertNotNull(f.ed.doc.takeNote())
        assertTrue(why.contains("built after"), "and it says why: $why")
    }

    // ---- 6. the mesh route, and it says what it is ----

    /**
     * A body with no analytic pedigree — here the exact prismatic boolean's stack of slabs — still yields a
     * curve, from the section's own chords, and the note **says** that is what it is (OP-15: never degrade
     * silently).
     */
    @Test
    fun aBodyWithNoNamedFacesStillGivesACurveAndSaysItIsChords() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 40.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("30")
        ed.click(Vec2(20.0, 0.0))
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(80.0, 0.0))
        ed.click(Vec2(120.0, 40.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("30")
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.UNION)
        ed.click(Vec2(20.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.PLANE_AT_HEIGHT)
        ed.type("15")
        ed.click(Vec2(60.0, 80.0))
        ed.setTool(Tools.INTERSECTION_CURVE)
        ed.click(Vec2(100.0, 0.0))
        val curve = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)
        assertTrue(ed.statusHint.contains("chords"), "the note names the class: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("curve 2 of 2"), "…and which of the two it is: ${ed.statusHint}")
        val corners = curveOf(curve).elements.map { it.start }
        assertTrue(corners.all { abs(it.z - 15.0) < 1e-12 }, "every point is exactly on the plane all the same")
        assertTrue(corners.all { it.x > 70.0 }, "and it is the far block's ring: $corners")
    }
}
