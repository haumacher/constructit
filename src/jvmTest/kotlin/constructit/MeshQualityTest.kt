package constructit

import constructit.core.Evaluator
import constructit.core.Node
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Camera3
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.PlanePerspective
import constructit.editor.Scene3
import constructit.editor.SvgDrawTarget
import constructit.editor.Tools
import constructit.exchange.ExportFormat
import constructit.exchange.Exports
import constructit.geom.BoolOp
import constructit.geom.Circle
import constructit.geom.Feature3
import constructit.geom.Frames3
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Loop
import constructit.geom.MeshQuality
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Region
import constructit.geom.Section3
import constructit.geom.Segment
import constructit.geom.Sketch3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.geom.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * **Quality is a property of the picture and never of a number the drawing reports** — slice B of the
 * responsiveness item, as an instrument.
 *
 * Slice A stopped a body meshing for a picture nobody was looking at; this is the other half of the same
 * report — the body somebody *is* looking at, while they drag it. A heavy solid meshed once per frame is the
 * residual cost, and what the 3D view needs during a gesture is a *picture* of where the body is, not its
 * surface. So [Solid3.meshAt] keeps two memoized levels, the view asks for the coarse one while an
 * interaction is live ([Editor.viewQuality]) and for the fine one the moment it settles, and everything
 * else — every volume, extent, section, boolean, export and watertightness check — reads fine by not asking.
 *
 * The acceptance is [Node.meshCount] and its coarse twin, on the queue entry's own body: a tube on a
 * three-turn coil. The law's half is asserted as *numbers*, not as "no crash".
 */
class MeshQualityTest {
    // ---- the fixture: the report's own body, and the two ways of painting it ----

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    /** A tube of radius 3 swept along a **three-turn** coil — the body the queue entry is about. */
    private fun spring(ed: Editor): Element {
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.HELIX)
        ed.type("20")
        ed.type("12")
        ed.type("3")
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.TUBE)
        ed.type("3")
        ed.click(Vec2(20.0, 0.0))
        val tube = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID }, "the tube: ${ed.statusHint}")
        ed.setTool(Tools.SELECT)
        return tube
    }

    private fun nodes(ed: Editor): List<Node> {
        val seen = LinkedHashMap<Node, Unit>()

        fun walk(n: Node) {
            if (seen.put(n, Unit) != null) return
            n.inputs.forEach { walk(it) }
        }
        ed.doc.elements.forEach { walk(it.ref.node) }
        ed.doc.scalars.forEach { walk(it.ref.node) }
        return seen.keys.toList()
    }

    /** Fine meshes derived over the whole document — the meshes every number is read from. */
    private fun fineMeshes(ed: Editor): Int = nodes(ed).sumOf { it.meshCount }

    /** Coarse meshes derived over the whole document — the pictures. */
    private fun coarseMeshes(ed: Editor): Int = nodes(ed).sumOf { it.coarseMeshCount }

    /**
     * One paint of the shell's 3D view: the plan's own canvas, then the scene at whatever quality the policy
     * says right now. This is exactly the pair of calls `Main.kt` makes, with `requestAnimationFrame` and
     * `requestIdleCallback` left out — those are the shell's, and what they schedule is this.
     */
    private fun paintWith3d(ed: Editor) {
        ed.render(SvgDrawTarget())
        Scene3.extract(ed.doc, ghosts = ed.ghostElements(), quality = ed.viewQuality)
    }

    private fun paintPlan(ed: Editor) {
        ed.render(SvgDrawTarget())
    }

    /**
     * The drag the report is about, driven the way the shell drives it: every move is a *streaming* event, so
     * the interaction is live throughout and settles once, after the last of them.
     */
    private fun dragCentre(
        ed: Editor,
        from: Vec2,
        to: Vec2,
        frames: Int = 20,
        paint: (Editor) -> Unit,
    ) {
        ed.pointerDown(ed.camera.worldToScreen(from))
        for (i in 1..frames) {
            val t = i.toDouble() / frames
            ed.interacting = true
            ed.pointerMove(ed.camera.worldToScreen(Vec2(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)))
            paint(ed)
        }
        ed.pointerUp(ed.camera.worldToScreen(to))
        paint(ed)
    }

    /** What the shell's idle callback does: the interaction has settled, so the picture is redrawn fine. */
    private fun settle(
        ed: Editor,
        paint: (Editor) -> Unit,
    ) {
        ed.interacting = false
        paint(ed)
    }

    @Suppress("UNCHECKED_CAST")
    private fun solidOf(el: Element): Solid3 = Evaluator().solid(el.ref as SolidRef)

    // ---- the instrument, on the queue entry's own body ----

    /**
     * **A 3D drag builds one coarse mesh per moved frame and not one fine one; the settle builds exactly one
     * fine mesh.** The headline number of the slice.
     *
     * Twenty moves, each of which moves the body, so each costs one *coarse* mesh — and the release, which
     * moves nothing new, costs none, because the value memo serves it (OP-5 at work at both levels). Then the
     * interaction settles and the fine mesh is built, once, for the picture that stays on screen.
     */
    @Test
    fun aThreeDDragBuildsOneCoarseMeshPerMovedFrameAndOneFineMeshOnRelease() {
        val ed = Editor()
        spring(ed)
        paintWith3d(ed)
        assertEquals(1, fineMeshes(ed), "the settled view before the drag is the fine one")
        assertEquals(0, coarseMeshes(ed), "…and nothing coarse has been asked for yet")

        dragCentre(ed, Vec2(0.0, 0.0), Vec2(60.0, 0.0)) { paintWith3d(it) }

        assertEquals(20, coarseMeshes(ed), "one coarse mesh per frame that moved the body, none for the release")
        assertEquals(1, fineMeshes(ed), "and not one fine mesh was built while the pointer was moving")

        settle(ed) { paintWith3d(it) }

        assertEquals(2, fineMeshes(ed), "the settle builds the fine mesh — once")
        assertEquals(20, coarseMeshes(ed), "…and asks for no more coarse ones")
    }

    /** **A static redraw builds nothing at either level** — the memo, one level further on than OP-5's. */
    @Test
    fun aHundredStaticRedrawsBuildNothingAtEitherLevel() {
        val ed = Editor()
        spring(ed)
        ed.interacting = true
        paintWith3d(ed)
        assertEquals(1, coarseMeshes(ed), "one live look, one coarse mesh")
        repeat(100) { paintWith3d(ed) }
        assertEquals(1, coarseMeshes(ed), "100 live frames of an untouched drawing mesh nothing more")

        settle(ed) { paintWith3d(it) }
        assertEquals(1, fineMeshes(ed), "settling builds the fine one")
        repeat(100) { paintWith3d(ed) }
        assertEquals(1, fineMeshes(ed), "…and 100 settled frames build nothing either")
        assertEquals(1, coarseMeshes(ed), "the fine ask never disturbed the coarse memo")
    }

    /** **A plan-only drag still builds zero of both** — slice A's promise, unmoved by slice B. */
    @Test
    fun aPlanOnlyDragStillBuildsNothingAtAll() {
        val ed = Editor()
        spring(ed)
        paintPlan(ed)

        dragCentre(ed, Vec2(0.0, 0.0), Vec2(60.0, 0.0)) { paintPlan(it) }
        settle(ed) { paintPlan(it) }

        assertEquals(0, fineMeshes(ed), "the plan never wanted triangles")
        assertEquals(0, coarseMeshes(ed), "…and a quality knob does not give it a reason to")
    }

    /** **Asking twice builds once, per level, and neither level evicts the other.** */
    @Test
    fun eachLevelIsMemoizedOnceAndIndependently() {
        val ed = Editor()
        val tube = spring(ed)
        val solid = solidOf(tube)
        assertTrue(!solid.meshBuiltAt(MeshQuality.FINE), "nothing is built until somebody asks")
        assertTrue(!solid.meshBuiltAt(MeshQuality.COARSE), "at either level")

        val coarse1 = solid.meshAt(MeshQuality.COARSE)
        val coarse2 = solid.meshAt(MeshQuality.COARSE)
        assertSame(coarse1, coarse2, "one coarse mesh, handed out twice")
        assertEquals(1, coarseMeshes(ed), "and built once")
        assertTrue(!solid.meshBuiltAt(MeshQuality.FINE), "asking for a picture does not build the fine mesh")

        val fine1 = solid.mesh
        val fine2 = solid.meshAt(MeshQuality.FINE)
        assertSame(fine1, fine2, "one fine mesh, handed out twice")
        assertEquals(1, fineMeshes(ed), "and built once")
        assertSame(coarse1, solid.meshAt(MeshQuality.COARSE), "…while the coarse memo is exactly where it was")
        assertEquals(1, coarseMeshes(ed), "no second coarse build")
    }

    /**
     * **Coarse is genuinely coarser, and it is still a solid.** A picture that were not watertight would be a
     * picture of a body this tool refuses to make (OP-9), so both levels are asserted manifold.
     */
    @Test
    fun theCoarseMeshIsCoarserAndStillWatertight() {
        val ed = Editor()
        val tube = spring(ed)
        val solid = solidOf(tube)
        val fine = solid.mesh
        val coarse = solid.meshAt(MeshQuality.COARSE)

        assertTrue(fine.triangles.size > 5000, "the fine mesh is the heavy one: ${fine.triangles.size}")
        assertTrue(
            coarse.triangles.size * 3 < fine.triangles.size,
            "the coarse one is at least three times lighter: ${coarse.triangles.size} against ${fine.triangles.size}",
        )
        assertManifold(fine, "the fine spring")
        assertManifold(coarse, "the coarse spring")
    }

    // ---- the law: every number the drawing reports reads fine ----

    /**
     * **A volume, an extent and a section read the fine mesh even while an interaction is live.**
     *
     * The interaction flag is up throughout — the very state a coarse picture is drawn under — and not one
     * coarse triangle is built, because none of these consumers has a way to ask for one. The numbers are the
     * numbers: 24000 mm³ exactly for a 40 × 60 × 10 plate, and 40 × 60 × 10 for its extent.
     */
    @Test
    fun aVolumeAnExtentAndASectionAreNeverCoarseEvenMidGesture() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 60.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("10")
        ed.click(Vec2(0.0, 0.0))
        val plate = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID }, "the plate: ${ed.statusHint}")
        ed.setTool(Tools.SELECT)
        ed.interacting = true

        val solid = solidOf(plate)
        assertClose(Geom3.volume(solid.mesh), 24000.0, tol = 1e-9, msg = "40 x 60 x 10 mid-gesture")
        val (lo, hi) = assertNotNull(Geom3.bounds(solid.mesh))
        assertClose(hi.x - lo.x, 40.0, tol = 1e-9, msg = "extent in x")
        assertClose(hi.y - lo.y, 60.0, tol = 1e-9, msg = "extent in y")
        assertClose(hi.z - lo.z, 10.0, tol = 1e-9, msg = "extent in z")
        Section3.sectionOf(solid, Plane3(Vec3(0.0, 0.0, 5.0), Vec3.X, Vec3.Y))
        assertManifold(solid.mesh, "the plate mid-gesture")

        assertEquals(0, coarseMeshes(ed), "not one of those consumers can ask for a coarse triangle")
        assertTrue(fineMeshes(ed) > 0, "…while every one of them did demand the fine mesh")
    }

    /**
     * **An export written while a gesture is live is byte-identical to one written at rest.** The strongest
     * statement of the law available: an exported file is the drawing's report about itself, and it may not
     * depend on whether somebody happened to be holding the mouse down.
     */
    @Test
    fun anExportMidDragIsByteIdenticalToOneAtRest() {
        val ed = Editor()
        spring(ed)
        val atRest = Exports.export(ed.doc, "spring", ExportFormat.STL)
        assertTrue(atRest.ok, "the STL at rest: ${atRest.message}")

        ed.interacting = true
        paintWith3d(ed)
        assertTrue(coarseMeshes(ed) > 0, "the picture really is coarse right now")
        val midDrag = Exports.export(ed.doc, "spring", ExportFormat.STL)
        assertTrue(midDrag.ok, "the STL mid-drag: ${midDrag.message}")

        assertEquals(atRest.bytes!!.size, midDrag.bytes!!.size, "the same file length")
        assertTrue(atRest.bytes!!.contentEquals(midDrag.bytes!!), "…and the same bytes, to the last one")
    }

    // ---- the boundary: what a quality may not move ----

    /**
     * **The station count is the same at both qualities, and so therefore is the plan hint** — the constraint
     * slice A wrote down, kept structurally rather than by audit.
     *
     * A swept body's plan outline is read off its *run* ([constructit.geom.Silhouette.ofSwept]) and the run is
     * computed from the feature, at evaluation time, before any triangle exists — so nothing a picture asks
     * for can reach it. The assertion is the corner-for-corner one, before and after a coarse mesh has been
     * built and read.
     */
    @Test
    fun thePlanHintAndTheRunsStationsAreIdenticalAtBothQualities() {
        val ed = Editor()
        val tube = spring(ed)
        val solid = solidOf(tube)
        val feature = solid.feature as Feature3.Sweep
        val before = feature.plan.flatMap { r -> r.outer.elements.map { GeomMath.startOf(it) } }
        assertTrue(before.isNotEmpty(), "there is a hint to compare")

        val stationsBefore = stationsOf(feature)
        solid.meshAt(MeshQuality.COARSE)
        solid.meshAt(MeshQuality.FINE)

        val after = (solidOf(tube).feature as Feature3.Sweep).plan.flatMap { r -> r.outer.elements.map { GeomMath.startOf(it) } }
        assertEquals(before.size, after.size, "the same outline, corner for corner")
        for (i in before.indices) {
            assertClose((after[i] - before[i]).length(), 0.0, tol = 1e-12, msg = "corner $i of the plan hint")
        }
        assertEquals(stationsBefore, stationsOf(feature), "and the run has exactly as many stations as it had")
    }

    /**
     * **A coarse tube's rings ride the fine run**, which is what "the same body at two fineness" means: every
     * coarse vertex stands on a station of the one run, so the two pictures are the same curve differently
     * chorded and not two different curves.
     */
    @Test
    fun theCoarseTubeRidesExactlyTheSameRun() {
        val ed = Editor()
        val tube = spring(ed)
        val solid = solidOf(tube)
        val feature = solid.feature as Feature3.Sweep
        val stations = stationsOf(feature)
        val coarse = solid.meshAt(MeshQuality.COARSE)
        val fine = solid.mesh

        // a ring per station at each level, plus the two caps' own copies — so the vertex counts are
        // the one run's station count times each level's own ring size
        assertTrue(stations > 100, "a three-turn coil is a long run: $stations stations")
        assertEquals(0, fine.vertices.size % stations, "the fine mesh is a whole number of rings on that run")
        assertEquals(0, coarse.vertices.size % stations, "and so is the coarse one, on the very same run")
        assertTrue(
            fine.vertices.size / stations > coarse.vertices.size / stations,
            "with fewer corners round the section: ${coarse.vertices.size / stations} against ${fine.vertices.size / stations}",
        )
    }

    /** How many stations the run of [feature] is cut into — rebuilt from the feature, as everything is. */
    private fun stationsOf(feature: Feature3.Sweep): Int {
        val (tess, _) = Geom3.tessellateRegion(feature.profile.region, GeomMath.TESS_TOL_MM)
        val reach = assertNotNull(tess, "the profile tessellates").outer.maxOf { it.length() }
        val (frame, _) = Frames3.along(feature.path, feature.up, feature.roll, feature.twist, reach)
        return assertNotNull(frame, "the run rebuilds from the feature").stations.size
    }

    /**
     * **A pick reads the fine mesh, and the record of why is at [Editor.solidUnderRay].**
     *
     * A press is not one of the two streaming events, so picking happens where the interaction has settled and
     * the fine mesh is what is on screen anyway — and a pick is a choice the fine body must agree with, not a
     * picture. The instrument is the whole assertion: a 3D pick made after nothing but coarse pictures builds
     * the *fine* mesh, which is the observable difference between the two answers.
     */
    @Test
    fun aThreeDPickBuildsAndReadsTheFineMesh() {
        val ed = Editor()
        solidPlate(ed)
        ed.interacting = true
        paintWith3d(ed)
        assertEquals(1, coarseMeshes(ed), "the live picture is the coarse one")
        assertEquals(0, fineMeshes(ed), "and nothing fine has been built")

        // the 3D view is driving: a perspective over the plan, and a press in the middle of it
        val cam = Camera3(target = Vec3(0.0, 0.0, 5.0), distance = 200.0, pitch = 0.9)
        ed.pointing = PlanePerspective(Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), cam, 800.0, 600.0)
        ed.pointerDown(Vec2(400.0, 300.0))
        ed.pointerUp(Vec2(400.0, 300.0))

        assertEquals(1, fineMeshes(ed), "the ray consulted the fine mesh, and built it: ${ed.statusHint}")
        assertEquals(1, coarseMeshes(ed), "…and asked for no further picture while doing it")
    }

    private fun solidPlate(ed: Editor): Element {
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(-20.0, -20.0))
        ed.click(Vec2(20.0, 20.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("10")
        ed.click(Vec2(-20.0, -20.0))
        val el = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID }, "the plate: ${ed.statusHint}")
        ed.setTool(Tools.SELECT)
        return el
    }

    // ---- the bodies that have one level, stated rather than silently fine ----

    /**
     * **A prism has one level and it costs nothing**: a coarse ask hands back the very same mesh object, so
     * there is no second build to charge and nothing for `SceneSync`'s identity swap to notice.
     *
     * The body is the analytic boolean's own result, which is what a `Feature3.Prism` is — its cost is the
     * region algebra and the one global corner set every ring is conformed to, not its chords.
     */
    @Test
    fun aPrismHasOneLevelAndACoarseAskBuildsNothingExtra() {
        val plate = box(40.0, 60.0, 10.0, Vec2(0.0, 0.0))
        val plug = box(20.0, 20.0, 10.0, Vec2(10.0, 10.0))
        val (cut, why) = Geom3.boolean(BoolOp.SUBTRACT, plate, plug)
        val prism = assertNotNull(cut, "the analytic boolean was refused: $why")
        assertTrue(prism.feature is Feature3.Prism, "the analytic boolean's result is a prism")
        assertTrue(!prism.coarsens, "a prism's cost is its region algebra, not its chords")
        assertTrue(!prism.meshBuiltAt(MeshQuality.COARSE), "and nothing is built until it is asked for")

        val coarse = prism.meshAt(MeshQuality.COARSE)
        assertSame(coarse, prism.mesh, "one object, so the identity swap sees no change")
        assertClose(Geom3.volume(coarse), 20000.0, tol = 1e-9, msg = "24000 - 4000, at the only quality there is")
    }

    /** A rectangular box, built the way the deferral's own tests build one. */
    private fun box(
        w: Double,
        h: Double,
        depth: Double,
        at: Vec2,
    ): Solid3 {
        val loop =
            Loop(
                listOf(
                    ProfileElement.Seg(Segment(Vec2(at.x, at.y), Vec2(at.x + w, at.y))),
                    ProfileElement.Seg(Segment(Vec2(at.x + w, at.y), Vec2(at.x + w, at.y + h))),
                    ProfileElement.Seg(Segment(Vec2(at.x + w, at.y + h), Vec2(at.x, at.y + h))),
                    ProfileElement.Seg(Segment(Vec2(at.x, at.y + h), Vec2(at.x, at.y))),
                ),
            )
        val sketch = Sketch3(Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), listOf(Region(loop, emptyList())))
        val (solid, why) = Geom3.extrude(sketch, depth)
        return assertNotNull(solid, "the box was refused: $why")
    }

    /** **…and a body that does coarsen says so**, which is what makes the distinction readable. */
    @Test
    fun aSweptBodySaysThatItCoarsens() {
        val ed = Editor()
        val tube = spring(ed)
        assertTrue(solidOf(tube).coarsens, "a tube's rings are exactly what a picture can afford to lose")
    }

    /**
     * **A revolution coarsens on both of its axes** — the rings round the axis *and* the profile's own
     * chords. Nothing outside the mesh reads either count (a revolution's plan hint is its sketch, and its
     * section is cut from a mesh, which is the fine one by law), which is exactly what makes it free to
     * coarsen where a sweep's run stations are not.
     */
    @Test
    fun aRevolutionCoarsensItsRingsAndItsProfile() {
        // a torus: a circle of radius 8 at 40 from the axis, taken the whole way round — arcs on both axes
        val loop = Loop(listOf(ProfileElement.CircleE(Circle(Vec2(40.0, 0.0), 8.0))))
        val sketch = Sketch3(Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), listOf(Region(loop, emptyList())))
        val (body, why) = Geom3.revolveFull(sketch, Vec2(0.0, 0.0), Vec2(0.0, 1.0))
        val solid = assertNotNull(body, "the torus was refused: $why")
        assertTrue(solid.coarsens, "a revolution coarsens")

        val fine = solid.mesh.triangles.size
        val coarse = solid.meshAt(MeshQuality.COARSE).triangles.size
        // one order of magnitude of tolerance is about a third of the chords on each axis, so about a tenth
        // of the triangles on a body curved in two directions — the number MeshQuality.COARSE is pinned by
        assertTrue(coarse * 8 < fine, "both axes coarsen: $coarse against $fine")
        assertManifold(solid.mesh, "the fine torus")
        assertManifold(solid.meshAt(MeshQuality.COARSE), "the coarse torus")
    }

    /**
     * **An extrusion coarsens its profile's chords** — the one axis it has — and stays exactly as deep.
     * A coarse picture is a coarser *outline* of the same prism, never a shorter one.
     */
    @Test
    fun anExtrusionCoarsensItsProfileAndKeepsItsDepth() {
        val loop = Loop(listOf(ProfileElement.CircleE(Circle(Vec2(0.0, 0.0), 20.0))))
        val sketch = Sketch3(Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), listOf(Region(loop, emptyList())))
        val (body, why) = Geom3.extrude(sketch, 10.0)
        val solid = assertNotNull(body, "the cylinder was refused: $why")

        val fine = solid.mesh
        val coarse = solid.meshAt(MeshQuality.COARSE)
        assertTrue(coarse.triangles.size * 2 < fine.triangles.size, "${coarse.triangles.size} against ${fine.triangles.size}")
        val fineBounds = assertNotNull(Geom3.bounds(fine))
        val coarseBounds = assertNotNull(Geom3.bounds(coarse))
        assertClose(coarseBounds.second.z - coarseBounds.first.z, 10.0, tol = 1e-9, msg = "the coarse prism is as deep")
        assertClose(fineBounds.second.z - fineBounds.first.z, 10.0, tol = 1e-9, msg = "…and so is the fine one")
        // the coarse shell is inscribed in the fine one, which is why a coarse ray could only ever be wrong
        assertTrue(Geom3.volume(coarse) < Geom3.volume(fine), "chords cut the corner inward")
        assertManifold(coarse, "the coarse cylinder")
    }
}
