package constructit

import constructit.core.Evaluator
import constructit.core.Node
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Scene3
import constructit.editor.SvgDrawTarget
import constructit.editor.Tools
import constructit.exchange.ExportFormat
import constructit.exchange.Exports
import constructit.geom.BoolOp
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Loop
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Region
import constructit.geom.Section3
import constructit.geom.Segment
import constructit.geom.Sketch3
import constructit.geom.Solid3
import constructit.geom.SweepProfile
import constructit.geom.Vec2
import constructit.geom.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The mesh is derived on demand** — the instrument, and the report it answers.
 *
 * Reported as *"a tube along a helix produces plenty of triangles … when moving points that change the
 * helix, the UI becomes incredibly laggy — even in 2D"*. Three things were true of that, and this file is
 * about the first: the mesh was **part of the value**, built inside every `compute`, so a drag in the plan
 * rebuilt tens of thousands of triangles per mouse move for a picture the plan never draws. It now waits
 * inside the value until somebody asks for triangles ([Solid3]).
 *
 * The acceptance is an **instrument, not a stopwatch** — [Node.meshCount] beside [Node.computeCount], per
 * node, document-scoped, no shared static. It has to show both halves of the claim: a plan drag of the
 * report's own body builds **zero** triangles, and the 3D view still gets every one of them the moment it
 * asks. And what must *not* change is any number the drawing reports — a volume, an extent, a boolean, a
 * section, an export, watertightness — so those are asserted as numbers, not as "no crash".
 *
 * The [constructit.geom.Silhouette] half is here too, because it is what made the deferral possible at all: a
 * swept body's plan hint used to be the silhouette of its own mesh, which is exactly the consumer that would
 * have kept the tube meshing on every frame of a plan drag.
 */
class DeferredMeshTest {
    // ---- the fixture: the report's own body, and the two ways of painting it ----

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    /** A number typed into the tool's scalar slot, the way a person types it. */
    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    /** A tube of radius 3 swept along a **three-turn** coil about a plain plan point — the report's body. */
    private fun spring(ed: Editor): Element {
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.HELIX)
        ed.type("20")
        ed.type("12")
        ed.type("3")
        ed.click(Vec2(0.0, 0.0))
        assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, "the coil: ${ed.statusHint}")
        ed.setTool(Tools.TUBE)
        ed.type("3")
        // the coil's own plan is the circle of radius 20 about the centre, so this click is on the route
        ed.click(Vec2(20.0, 0.0))
        val tube = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID }, "the tube: ${ed.statusHint}")
        ed.setTool(Tools.SELECT)
        return tube
    }

    /** Every node the document holds, once — the same walk the recompute counters are read over. */
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

    /** Triangles derived, over the whole document — the instrument. */
    private fun meshes(ed: Editor): Int = nodes(ed).sumOf { it.meshCount }

    private fun computes(ed: Editor): Int = nodes(ed).sumOf { it.computeCount }

    /** A paint of the **2D view**, which is what the shell does while the plan is on screen. */
    private fun paintPlan(ed: Editor) {
        ed.render(SvgDrawTarget())
    }

    /** A paint that includes the **3D view** — the same document, one more consumer. */
    private fun paintWith3d(ed: Editor) {
        ed.render(SvgDrawTarget())
        Scene3.extract(ed.doc)
    }

    /** The drag the report is about: the coil's centre point, moved across the plan in twenty moves. */
    private fun dragCentre(
        ed: Editor,
        from: Vec2,
        to: Vec2,
        paint: (Editor) -> Unit,
    ) {
        ed.pointerDown(ed.camera.worldToScreen(from))
        for (i in 1..20) {
            val t = i.toDouble() / 20.0
            ed.pointerMove(ed.camera.worldToScreen(Vec2(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)))
            paint(ed)
        }
        ed.pointerUp(ed.camera.worldToScreen(to))
        paint(ed)
    }

    @Suppress("UNCHECKED_CAST")
    private fun solidOf(el: Element): Solid3 = Evaluator().solid(el.ref as SolidRef)

    private fun planOf(el: Element): List<Region> = (solidOf(el).feature as Feature3.Sweep).plan

    private fun corners(plan: List<Region>): List<Vec2> = plan.flatMap { r -> r.outer.elements.map { GeomMath.startOf(it) } }

    // ---- the report's own case ----

    /**
     * **A plan drag of the coil's centre builds zero triangles** — the report, as a number.
     *
     * The tube recomputes (its feature is a function of the point that moved, and its plan hint moves with
     * it), the plan is redrawn twenty-one times, and not one triangle is built: the only consumer that wanted
     * them was the 3D view, and it is not looking.
     */
    @Test
    fun aPlanDragOfTheCoilsCentreBuildsNoTriangles() {
        val ed = Editor()
        val tube = spring(ed)
        paintPlan(ed)
        assertEquals(0, meshes(ed), "the plan alone never needed triangles")
        val before = computes(ed)

        dragCentre(ed, Vec2(0.0, 0.0), Vec2(60.0, 0.0)) { paintPlan(it) }

        assertEquals(0, meshes(ed), "a drag in the plan must not mesh the tube")
        assertTrue(computes(ed) > before, "…while the drawing really did recompute")
        assertTrue(planOf(tube).isNotEmpty(), "and the plan hint is there to be drawn and picked")
        val xs = corners(planOf(tube)).map { it.x }
        assertTrue(xs.max() > 60.0, "the hint followed the point: it reaches past x=60 (max ${xs.max()})")
    }

    /**
     * **…and the same drag with the 3D view open builds them**, one per body per frame.
     *
     * Deferral is not "never": the picture must be right whenever it is asked for. A frame that extracts the
     * 3D scene costs exactly one mesh for the one body that changed — which is what a 3D drag *is* — and the
     * mesh it gets is the fine one, with the triangles a three-turn coil actually has.
     */
    @Test
    fun theSameDragWithTheThreeDSceneExtractedDoesBuildTheMesh() {
        val ed = Editor()
        val tube = spring(ed)
        paintWith3d(ed)
        assertEquals(1, meshes(ed), "one look at the 3D scene, one mesh")
        val fine = solidOf(tube).mesh.triangles.size
        assertTrue(fine > 5000, "and it is the fine mesh: $fine triangles")

        dragCentre(ed, Vec2(0.0, 0.0), Vec2(60.0, 0.0)) { paintWith3d(it) }

        // Twenty moves, each of which moved the tube and therefore cost one mesh — and the release, which
        // moved nothing new, so its frame is served by the memo and costs none (OP-5 at work in both halves).
        assertEquals(21, meshes(ed), "one mesh per frame that changed the body, and none for the one that did not")
        assertManifold(solidOf(tube).mesh, "the dragged spring")
    }

    /** **A solid compared, copied, printed or put in a collection builds nothing** — identity, not equality. */
    @Test
    fun comparingCopyingOrCollectingASolidBuildsNothing() {
        val ed = Editor()
        val tube = spring(ed)
        paintPlan(ed)

        val solid = solidOf(tube)
        val same = solidOf(tube)
        assertTrue(solid === same, "the memo hands out one value object (OP-5)")
        assertEquals(solid, same, "…so equality is identity, and reads nothing")
        assertEquals(1, hashSetOf(solid, same).size, "one solid, one entry in a set")
        assertEquals(solid.hashCode(), same.hashCode(), "and hashing it is the identity's")
        assertTrue(solid.toString().contains("Sweep"), "printing it names the feature: $solid")

        assertTrue(!solid.meshBuilt, "none of that is a reason to build triangles")
        assertEquals(0, meshes(ed), "and the instrument agrees")
    }

    /**
     * **One repaint with nothing upstream changed leaves both counters where they were** — OP-5's own
     * acceptance, one axis further on. The mesh memo lives in the value, so it survives a hundred frames
     * exactly as the value memo does.
     */
    @Test
    fun aHundredRepaintsWithNothingChangedLeaveBothCountersAlone() {
        val ed = Editor()
        spring(ed)
        paintWith3d(ed)
        val computesBefore = computes(ed)
        val meshesBefore = meshes(ed)
        assertEquals(1, meshesBefore, "one body, one mesh")

        repeat(100) { paintWith3d(ed) }

        assertEquals(computesBefore, computes(ed), "100 frames of an untouched drawing recompute nothing")
        assertEquals(meshesBefore, meshes(ed), "…and mesh nothing")
    }

    // ---- what the deferral must not have changed: every number the drawing reports ----

    /** A 40 × 60 plate 10 deep, and a 20 × 20 plug through it — both exact by arithmetic. */
    private fun plate(
        w: Double,
        h: Double,
        depth: Double,
        at: Vec2 = Vec2(0.0, 0.0),
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
        return assertNotNull(solid, "the plate was refused: $why")
    }

    /**
     * **Volume, extent, the analytic boolean and watertightness all get the fine mesh, and the numbers are
     * the numbers.** 40 × 60 × 10 is 24000 mm³ exactly; a 20 × 20 × 10 plug taken out of it leaves 20000 mm³
     * exactly; the extent is exactly 40 by 60 by 10. Each of these demands the mesh, which is what makes them
     * right — quality is a property of the picture and never of a number the drawing reports.
     */
    @Test
    fun volumeExtentAndTheAnalyticBooleanStillGetTheFineMeshAndTheExactNumbers() {
        val plate = plate(40.0, 60.0, 10.0)
        assertTrue(!plate.meshBuilt, "an extrusion is a sketch and a depth until somebody wants triangles")

        assertClose(Geom3.volume(plate.mesh), 24000.0, tol = 1e-9, msg = "40 x 60 x 10")
        assertTrue(plate.meshBuilt, "and asking for a volume is asking for the mesh")
        assertManifold(plate.mesh, "the plate")
        val (lo, hi) = assertNotNull(Geom3.bounds(plate.mesh))
        assertClose(hi.x - lo.x, 40.0, tol = 1e-9)
        assertClose(hi.y - lo.y, 60.0, tol = 1e-9)
        assertClose(hi.z - lo.z, 10.0, tol = 1e-9)

        val plug = plate(20.0, 20.0, 10.0, at = Vec2(10.0, 10.0))
        val (cut, why) = Geom3.boolean(BoolOp.SUBTRACT, plate, plug)
        val result = assertNotNull(cut, "the analytic boolean was refused: $why")
        assertTrue(!result.meshBuilt, "the result of an analytic boolean is a prism, and waits like one")
        assertManifold(result.mesh, "the plate with the plug taken out")
        assertClose(Geom3.volume(result.mesh), 20000.0, tol = 1e-9, msg = "24000 - 4000")
    }

    /**
     * **A section, an export and `assertManifold` demand the mesh too** — and a *prism's* section does not,
     * because it is exact and structural (OP-15). Both halves stated, since the point of the instrument is
     * that it says which consumer wanted what.
     */
    @Test
    fun aMeshSectionAndAnExportDemandTheMeshWhileAPrismsSectionDoesNot() {
        val plate = plate(40.0, 60.0, 10.0)
        val flat = Section3.sectionOf(plate, Plane3(Vec3(0.0, 0.0, 5.0), Vec3.X, Vec3.Y))
        assertEquals(6, flat.edges.size, "a box names six faces, cut or not — the ordering is structural")
        assertEquals(4, flat.drawn.size, "and four of them are what a cut halfway up draws")
        assertTrue(!flat.approximated, "exactly, in segments")
        assertTrue(!plate.meshBuilt, "…and naming faces is not meshing them")

        val (tube, why) = Geom3.sweep(pathAlongX(120.0), Vec3.Z, SweepProfile.Round(5.0))
        val swept = assertNotNull(tube, "the tube was refused: $why")
        val cut = Section3.sectionOf(swept, Plane3(Vec3(60.0, 0.0, 0.0), Vec3.Y, Vec3.Z))
        assertTrue(cut.pieces.isNotEmpty(), "a sweep has no named faces, so its section is drawn from the mesh")
        assertTrue(swept.meshBuilt, "…which is a demand for the triangles, and gets them")

        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 60.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("10")
        ed.click(Vec2(0.0, 0.0))
        assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID }, "the plate: ${ed.statusHint}")
        paintPlan(ed)
        assertEquals(0, meshes(ed), "a prism's plan is its own sketch, so the plan view needs no triangles")

        val stl = Exports.export(ed.doc, "plate", ExportFormat.STL)
        assertTrue(stl.ok, "the STL was written: ${stl.message}")
        assertEquals(1, meshes(ed), "and an export is one demand for the mesh")
    }

    /** A straight run along x of the given length, as a path in space. */
    private fun pathAlongX(len: Double) =
        constructit.geom.Path3(listOf(constructit.geom.Curve3Element.Seg3(Vec3.ZERO, Vec3(len, 0.0, 0.0))))

    // ---- the plan hint the deferral needed ----

    /**
     * **A tube's plan hint is exact, and it is arithmetic rather than triangles.**
     *
     * The outline of a tube seen in a plane touches its section at the two points across the run, and those
     * are at exactly the stated radius however the run is inclined
     * ([constructit.geom.Silhouette.ofSwept]) — so a tube of radius 10 along the x axis has its hint at
     * exactly y = ±10, where the mesh silhouette had the chords of a tessellated circle.
     */
    @Test
    fun aTubesPlanHintSitsExactlyAtItsRadius() {
        val (tube, why) = Geom3.sweep(pathAlongX(140.0), Vec3.Z, SweepProfile.Round(10.0), plan = Plane3(Vec3.ZERO, Vec3.X, Vec3.Y))
        val swept = assertNotNull(tube, "the tube was refused: $why")
        assertTrue(!swept.meshBuilt, "the plan came off the run, not off the triangles")

        val ys = (swept.feature as Feature3.Sweep).plan.flatMap { r -> r.outer.elements.map { GeomMath.startOf(it).y } }
        assertTrue(ys.any { abs(it - 10.0) < 1e-12 }, "one rail at exactly +10: $ys")
        assertTrue(ys.any { abs(it + 10.0) < 1e-12 }, "and one at exactly -10: $ys")
    }

    /**
     * **The pick target survives a save and a reload**, corner for corner — it has to, since it is a pure
     * function of the feature and the plane and nothing about a rendering choice enters it.
     */
    @Test
    fun theSweptPlanHintComesBackIdenticalAfterAReload() {
        val ed = Editor()
        val tube = spring(ed)
        val before = corners(planOf(tube))
        assertTrue(before.isNotEmpty(), "there is a hint to compare")

        val script = DocumentFormat.save(ed.doc)
        val back = DocumentFormat.load(script)
        val reloaded = assertNotNull(back.elements.lastOrNull { it.kind == ElementKind.SOLID })
        val after = corners(planOf(reloaded))

        assertEquals(before.size, after.size, "the same outline")
        for (i in before.indices) {
            assertClose((after[i] - before[i]).length(), 0.0, tol = 1e-12, msg = "corner $i of the reloaded hint")
        }
    }
}
