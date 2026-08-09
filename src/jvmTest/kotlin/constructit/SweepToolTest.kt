package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
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
import constructit.exchange.ExportFormat
import constructit.exchange.ExportScene
import constructit.exchange.Exports
import constructit.geom.BoolOp
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.MeshBool
import constructit.geom.Plane3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The sweep as a gesture, and as an ordinary solid** (OP-26, step 2).
 *
 * The geometry is [SweepTest]'s; this is the other half of the claim — that what comes out of two clicks is
 * a solid like every other one. It draws a plan footprint and can be clicked by it, it is a legal boolean
 * operand, it exports through all four writers, it hides, it renames, one undo takes the gesture back, and
 * `save → load → save` is byte-equal. Adding it cost the tool table two rows and the controller nothing,
 * which is the property the table exists to have.
 */
class SweepToolTest {
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

    private fun view(ed: Editor): Viewport3 {
        val vp =
            Viewport3(
                camera = Camera3(target = Vec3(40.0, 30.0, 10.0), distance = 420.0, yaw = -0.9, pitch = 0.55),
                widthPx = wPx,
                heightPx = hPx,
            )
        vp.editor = ed
        vp.shown = true
        return vp
    }

    /**
     * The fixture: three plain plan points and a **curve in space** through them, drawn with no height
     * gesture at all (a plain point is lifted by nothing onto its own space's plane — OP-26 step 1).
     *
     * Deliberately the flat case, because it is the one whose plan projection is exactly the polyline the
     * clicks describe, so the tests below can aim at the route by the coordinates they typed.
     */
    private fun routeThroughPlan(
        ed: Editor,
        vararg at: Vec2,
        smooth: Boolean = false,
    ): Element {
        ed.setTool(Tools.POINT)
        for (p in at) ed.click(p)
        ed.setTool(if (smooth) Tools.CURVE3_SMOOTH else Tools.CURVE3)
        for (p in at) ed.click(p)
        ed.key("Enter")
        return assertNotNull(
            ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE },
            "the route was drawn: ${ed.statusHint}",
        )
    }

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    private fun meshOf(el: Element): constructit.geom.Mesh3 {
        @Suppress("UNCHECKED_CAST")
        return Evaluator().solid(el.ref as SolidRef).mesh
    }

    /** The everyday gesture: a radius typed, then one click on the route. */
    private fun tubeAlong(
        ed: Editor,
        route: Element,
        radius: String,
        at: Vec2,
    ): Element {
        ed.setTool(Tools.TUBE)
        ed.type(radius)
        ed.click(at)
        return assertNotNull(ed.solids().lastOrNull(), "the tube was built: ${ed.statusHint}")
    }

    // ---- the gesture ----

    /**
     * **One number and one click.** The tube tool waits for the radius it cannot do without and for nothing
     * else: its roll and its twist are defaulted, so the gesture completes on the click that names the route.
     */
    @Test
    fun aTypedRadiusAndOneClickBuildATubeAlongTheRoute() {
        val ed = Editor()
        val route = routeThroughPlan(ed, Vec2(0.0, 0.0), Vec2(100.0, 0.0), Vec2(100.0, 80.0))
        val tube = tubeAlong(ed, route, "6", Vec2(50.0, 0.0))

        val mesh = meshOf(tube)
        assertManifold(mesh, "the tube")
        val feature = assertNotNull(Evaluator().solid(tube.ref as SolidRef).feature as? Feature3.Sweep)
        assertEquals(6.0, (feature.profile as constructit.geom.SweepProfile.Round).radius, "the radius that was typed")
        assertEquals(0.0, feature.roll, "and no roll, because none was stated")
        assertEquals(0.0, feature.twist, "and no twist, because none was stated")
        assertTrue(ed.statusHint.contains("a solid, shown in the 3D view"), "and the tool said what it made: ${ed.statusHint}")
    }

    /**
     * **The general form takes a profile picked in the drawing**: a curve in space, then any closed area, and
     * the area is read in the moving frame with its own origin on the path.
     *
     * The rectangle here is drawn **off** the origin, and the assertion is that the sweep is off the route by
     * exactly that much — which is the whole of "the profile's own coordinates are read in the frame", said as
     * a number rather than as a sentence.
     */
    @Test
    fun anAreaPickedInTheDrawingIsCarriedAlongTheRouteInTheFrame() {
        val ed = Editor()
        val route = routeThroughPlan(ed, Vec2(0.0, 0.0), Vec2(120.0, 0.0))
        // a 20 x 10 rectangle whose own centre stands 30 mm along the space's x axis
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(20.0, -105.0))
        ed.click(Vec2(40.0, -95.0))
        assertNotNull(ed.doc.elements.lastOrNull { it.isCurve }, "the rectangle was drawn")

        ed.setTool(Tools.SWEEP)
        ed.click(Vec2(60.0, 0.0))
        ed.click(Vec2(30.0, -105.0))
        val solid = assertNotNull(ed.solids().lastOrNull(), "the sweep was built: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("swept along ${ed.doc.nameOf(route)}"), "the tool said what it carried and along what: ${ed.statusHint}")

        val mesh = meshOf(solid)
        assertManifold(mesh, "the swept bar")
        // the frame at the start is (ref = +Z, bi = tangent x ref = X x Z = -Y), so the profile's own (x, y)
        // maps to world (+Z, -Y): the section runs 20 mm along -Y and 10 mm along +Z, centred 30 mm up in x
        // — i.e. 30 mm along world +Z, which is the eccentricity the drawing states.
        val zs = mesh.vertices.map { it.z }
        assertClose(zs.min(), 20.0, 1e-9, "the section's own coordinates put its lower edge 20 mm off the route")
        assertClose(zs.max(), 40.0, 1e-9, "and its upper edge 40 mm off it")
        assertClose(Geom3.volume(mesh), 20.0 * 10.0 * 120.0, 1e-6, "and it is exactly a bar of the rectangle's area")
    }

    /**
     * **A roll typed for the sweep reaches the node** — and with two defaulted angle slots the gesture is
     * still just the two clicks, because a defaulted slot is never something the tool waits for.
     */
    @Test
    fun aTypedRollReachesTheNodeAndTurnsTheSection() {
        val ed = Editor()
        routeThroughPlan(ed, Vec2(0.0, 0.0), Vec2(120.0, 0.0))
        // an off-centre circle, so the roll is visible in the solid at all
        ed.setTool(Tools.CIRCLE_R)
        ed.type("6")
        ed.click(Vec2(0.0, -80.0))
        ed.setTool(Tools.SWEEP)
        ed.type("90")
        ed.click(Vec2(60.0, 0.0))
        ed.click(Vec2(6.0, -80.0))
        val solid = assertNotNull(ed.solids().lastOrNull(), "the rolled sweep was built: ${ed.statusHint}")

        val f = assertNotNull(Evaluator().solid(solid.ref as SolidRef).feature as? Feature3.Sweep)
        assertClose(f.roll, PI / 2.0, 1e-12, "the typed 90 degrees reached the node as a right angle in radians")
        assertEquals(0.0, f.twist, "and the slot that was not typed for took its default")
        assertManifold(meshOf(solid), "the rolled sweep")
    }

    // ---- an ordinary solid: the plan, the boolean, the file, the undo, the exports ----

    /**
     * **It draws a plan footprint and can be clicked by it.** A sweep has no prismatic reading, so its plan is
     * its **silhouette** in the space it belongs to ([constructit.geom.Silhouette], the answer OP-9 and OP-17
     * both parked) — which is what makes it fill a `SOLID` slot at all.
     */
    @Test
    fun aSweptSolidShowsAPlanFootprintAndIsPickedByIt() {
        val ed = Editor()
        val route = routeThroughPlan(ed, Vec2(0.0, 0.0), Vec2(140.0, 0.0))
        val tube = tubeAlong(ed, route, "10", Vec2(70.0, 0.0))

        val feature = assertNotNull(Evaluator().solid(tube.ref as SolidRef).feature as? Feature3.Sweep)
        assertTrue(feature.footprint.isNotEmpty(), "the sweep shows a plan")
        val xs = feature.footprint.flatMap { r -> r.outer.elements.map { constructit.geom.GeomMath.startOf(it) } }
        assertTrue(xs.any { abs(it.y - 10.0) < 0.2 }, "the outline reaches the tube's own radius off the route: $xs")

        // …and a click on it, well away from the curve itself, reaches the solid
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(70.0, 9.0))
        assertEquals(tube, ed.selection, "the footprint took the click: ${ed.statusHint}")
    }

    /**
     * **A swept solid is a legal boolean operand**, through the general engine (OP-9/OP-22): its axis is a
     * curve, so it has no prismatic reading and no common axis with anything, and the dispatch says so in the
     * value it produces.
     */
    @Test
    fun aSweptSolidUnionsAndSubtractsWithAConstructedOne() {
        assumeTrue(MeshBool.available, "the general boolean engine (Manifold, OP-9) is not available here: ${MeshBool.status}")
        val ed = Editor()
        val route = routeThroughPlan(ed, Vec2(0.0, 0.0), Vec2(120.0, 0.0))
        val tube = tubeAlong(ed, route, "10", Vec2(60.0, 0.0))

        // a block the tube runs through, extruded from the plan
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(40.0, -30.0))
        ed.click(Vec2(80.0, 30.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("40")
        ed.click(Vec2(60.0, -30.0))
        val block = assertNotNull(ed.solids().lastOrNull { it !== tube }, "the block was built: ${ed.statusHint}")

        val cut = assertNotNull(ed.doc.combineSolids(block, tube, BoolOp.SUBTRACT), "the tube cuts the block: ${ed.doc.note}")
        assertManifold(meshOf(cut), "the block with the tube subtracted")
        assertTrue(
            Evaluator().solid(cut.ref as SolidRef).feature is Feature3.MeshBoolean,
            "a sweep has no common axis with a prism, so the general engine ran",
        )
        assertTrue(Geom3.volume(meshOf(cut)) < Geom3.volume(meshOf(block)), "and it removed material")

        val fused = assertNotNull(ed.doc.combineSolids(block, tube, BoolOp.UNION), "and it fuses too: ${ed.doc.note}")
        assertManifold(meshOf(fused), "the block fused with the tube")
    }

    /** **All four writers take it**, because a sweep is a solid and the export seam knows nothing else. */
    @Test
    fun aSweptSolidExportsThroughAllFourWriters() {
        val ed = Editor()
        val route = routeThroughPlan(ed, Vec2(0.0, 0.0), Vec2(100.0, 0.0), Vec2(100.0, 60.0))
        val tube = tubeAlong(ed, route, "8", Vec2(50.0, 0.0))

        val scene = ExportScene.extract(ed.doc, "sweep")
        assertEquals(1, scene.nodes.size, "one body, and the route is not one: ${scene.notes}")
        assertTrue(scene.notes.isEmpty(), "silence means success: ${scene.notes}")
        for (format in ExportFormat.entries) {
            val out = Exports.export(ed.doc, "sweep", format)
            assertNotNull(out.bytes, "${format.name} wrote nothing: ${out.message}")
            assertTrue(out.bytes!!.isNotEmpty(), "${format.name} wrote an empty file")
        }
        // hiding it takes it out of every one of them, and the export says so rather than going quiet
        assertEquals(1, ed.doc.setElementsVisible(listOf(tube), false), "hide it")
        val hidden = ExportScene.extract(ed.doc, "sweep")
        assertTrue(hidden.isEmpty, "a hidden body is not exported")
        assertTrue(hidden.refusal?.contains("hidden") == true, "and the refusal names it: ${hidden.refusal}")
    }

    /** **`save → load → save` is byte-equal**, and the reloaded sweep is the same solid. */
    @Test
    fun aSweptSolidSurvivesSaveAndLoadByteForByte() {
        val ed = Editor()
        val route = routeThroughPlan(ed, Vec2(0.0, 0.0), Vec2(90.0, 0.0), Vec2(90.0, 70.0))
        val tube = tubeAlong(ed, route, "7", Vec2(45.0, 0.0))

        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.lines().any { it.startsWith("tool tube") }, "the step records the tool id: $once")
        val doc = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(doc), "the script round-trips byte for byte")

        val back = doc.elements.last { it.kind == ElementKind.SOLID }
        val before = meshOf(tube)

        @Suppress("UNCHECKED_CAST")
        val after = Evaluator().solid(back.ref as SolidRef).mesh
        assertEquals(before.vertices, after.vertices, "and the mesh reloads vertex for vertex, in the same order")
        assertEquals(before.triangles, after.triangles, "and triangle for triangle")
    }

    /** **One gesture, one undo** — and the route it rode stays, because it was not part of this gesture. */
    @Test
    fun oneUndoTakesTheWholeSweepGestureBack() {
        val ed = Editor()
        val route = routeThroughPlan(ed, Vec2(0.0, 0.0), Vec2(110.0, 0.0))
        tubeAlong(ed, route, "5", Vec2(55.0, 0.0))
        assertEquals(1, ed.solids().size)

        assertTrue(ed.undo(), "the tube is taken back")
        assertEquals(0, ed.solids().size, "one checkpoint covered the whole gesture")
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE }, "and the route it rode stays")
        assertTrue(ed.redo(), "and it comes back")
        assertEquals(1, ed.solids().size)
    }

    // ---- the refusals a gesture makes, and the ones the node makes ----

    /**
     * **A drawn curve is a route** — the lift (OP-26, step 1's missing source): the tube's slot takes a plain
     * segment and reads it as the run it already is, lying where it is drawn.
     *
     * This test used to assert the opposite, and the reversal is the package: *"the slot itself declines a
     * plain segment, so the click never reaches the build"* was a gap rather than a rule, since a curve's
     * construction is always parented and a drawing in a space therefore already *is* geometry in the world.
     * The refusal that remains is the one that is about the geometry rather than about the vocabulary — a
     * **line** runs on for ever, so it states no length of run — and it still speaks.
     */
    @Test
    fun sweepingAlongADrawnCurveReadsItAsTheRunItIs() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(80.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(80.0, 0.0))
        val seg = ed.doc.elements.last { it.kind == ElementKind.SEGMENT }

        // the slot takes the drawn segment, and what comes out is the tube along where it is drawn
        ed.setTool(Tools.TUBE)
        ed.type("5")
        ed.click(Vec2(40.0, 0.0))
        assertEquals(1, ed.solids().size, "the drawn segment is the route: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("reading it as the run it already is"), "and the reading speaks: ${ed.statusHint}")
        val mesh = meshOf(ed.solids().last())
        assertManifold(mesh, "a tube along a drawn segment")
        assertClose(mesh.vertices.minOf { it.x }, 0.0, 1e-9, "it runs from where the segment starts")
        assertClose(mesh.vertices.maxOf { it.x }, 80.0, 1e-9, "…to where it ends")
        assertClose(mesh.vertices.maxOf { it.z }, 5.0, 1e-9, "…as a 5 mm tube about it")

        // …and a **line** still states no length of run, so it is refused by name
        val line = ed.doc.line(ed.doc.freePoint(0.0.mm, 40.0.mm), ed.doc.freePoint(80.0.mm, 40.0.mm))
        assertEquals(null, ed.doc.tubeAlongCurve(line, ed.doc.newParameter("r", 5.0.mm).ref))
        assertTrue(ed.doc.note?.contains("runs on for ever") == true, "the build names it: ${ed.doc.note}")
        assertNotNull(seg)
    }

    /**
     * **The node's refusals are the node's**, and they heal (OP-3): a radius retyped past the route's own bend
     * makes the solid invalid *with the station named*, and typing a smaller one brings it straight back.
     */
    @Test
    fun aRadiusTooBigForTheRouteMakesTheSolidInvalidWithTheStationNamedAndHeals() {
        val ed = Editor()
        // a smooth route with a hard bend in it
        ed.setTool(Tools.POINT)
        for (p in listOf(Vec2(0.0, 0.0), Vec2(30.0, 22.0), Vec2(60.0, 0.0))) ed.click(p)
        ed.setTool(Tools.CURVE3_SMOOTH)
        for (p in listOf(Vec2(0.0, 0.0), Vec2(30.0, 22.0), Vec2(60.0, 0.0))) ed.click(p)
        ed.key("Enter")
        val route = ed.doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        val tube = tubeAlong(ed, route, "2", Vec2(30.0, 22.0))
        assertTrue(Evaluator().eval(tube.ref.node) is EvalResult.Ok, "a section that fits is a solid")

        val radius = assertNotNull(ed.doc.scalars.lastOrNull { it.name == "radius" }, "the radius is a panel row")
        ed.doc.setParameter(radius, 40.0.mm)
        val bad = Evaluator().eval(tube.ref.node)
        assertTrue(bad is EvalResult.Invalid, "a section that will not go round the bend is invalid: $bad")
        assertTrue((bad as EvalResult.Invalid).reason.contains("mm along the path"), "and it names the station: ${bad.reason}")
        assertTrue(Scene3.extract(ed.doc).solids.isEmpty(), "an invalid solid draws nothing")

        ed.doc.setParameter(radius, 2.0.mm)
        assertTrue(Evaluator().eval(tube.ref.node) is EvalResult.Ok, "and it heals")
        assertEquals(1, Scene3.extract(ed.doc).solids.size, "and comes back into the view")
    }

    // ---- the perf contract, with a swept solid in the drawing (session 35's gate) ----

    /**
     * **One view-projection matrix per frame, and an orbit and a hover upload nothing** — the session-35
     * contract, asked of the newest carrier. A sweep puts a mesh with thousands of triangles in the scene, so
     * it is exactly the body an identity-keyed cache must not re-upload for a mouse move.
     */
    @Test
    fun aDrawingWithASweptSolidStillCostsAnOrbitAndAHoverNothing() {
        val ed = Editor()
        val route = routeThroughPlan(ed, Vec2(0.0, 0.0), Vec2(120.0, 0.0), Vec2(120.0, 90.0), smooth = true)
        val tube = tubeAlong(ed, route, "9", Vec2(120.0, 0.0))
        val vp = view(ed)

        val sync = Scene3Sync()
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(1, sync.uploads, "the first look uploads")
        assertEquals(1, Scene3.extract(ed.doc).solids.size, "and the swept body is in the scene")

        repeat(20) { sync.update(Scene3.extract(ed.doc)) { } }
        assertEquals(1, sync.uploads, "an unchanged document is the same mesh object every time")

        ed.setTool(Tools.SEGMENT)
        for (i in 0 until 30) vp.pointerMove(Vec2(200.0 + i, 300.0 + i))
        assertEquals(1, sync.uploads, "a hover moves no vertex")

        vp.cameraModifier = true
        vp.pointerDown(Vec2(400.0, 300.0))
        for (i in 0 until 20) vp.pointerMove(Vec2(400.0 + i * 3, 300.0))
        vp.pointerUp(Vec2(460.0, 300.0))
        vp.cameraModifier = false
        assertEquals(1, sync.uploads, "an orbit uploads nothing")

        ed.doc.nameElement(tube, "kabelrohr")
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(1, sync.uploads, "a name is not vertex data")

        val radius = assertNotNull(ed.doc.scalars.lastOrNull { it.name == "radius" })
        ed.doc.setParameter(radius, 6.0.mm)
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(2, sync.uploads, "a sweep that changed is new vertex data")

        // …and the plan of a drawing containing one still builds exactly one matrix per frame
        vp.shown = false
        ed.setTool(Tools.SELECT)
        val proj = PlanePerspective(Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), Viewport3().camera, wPx, hPx)
        assertEquals(0, proj.matrixBuilds, "nothing drawn yet")
        ed.pointing = proj
        val rec = Counting()
        ed.draw(rec, wPx, hPx)
        // the footprint of a tube along a curved route is a silhouette of many chords, and every one of them
        // goes through the one projection — which is the claim, and it is worth nothing on a short outline
        assertTrue(rec.points > 50, "the swept body's footprint went through it: ${rec.points} points")
        assertEquals(1, proj.matrixBuilds, "one matrix for all of them")
    }

    // ---- the picture ----

    /**
     * **…and it is drawn**: the plan of a tube along a route through three points, as an SVG golden.
     *
     * The coordinates are pinned exactly by the tests above; what this pins is that the silhouette reaches the
     * canvas at all, in the solid's own style, as the footprint hint every other solid draws.
     */
    @Test
    fun theSweptSolidsPlanFootprintIsDrawn() {
        val ed = Editor()
        val route = routeThroughPlan(ed, Vec2(-70.0, -40.0), Vec2(30.0, -40.0), Vec2(30.0, 50.0))
        tubeAlong(ed, route, "9", Vec2(-20.0, -40.0))
        ed.setTool(Tools.SELECT)
        val target = SvgDrawTarget()
        ed.render(target)
        Golden.check("sweep_tube_plan", target.svg())
    }
}
