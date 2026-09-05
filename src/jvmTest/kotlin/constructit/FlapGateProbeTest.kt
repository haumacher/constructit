package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Camera3
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.Geom3
import constructit.geom.Loop
import constructit.geom.MeshBool
import constructit.geom.MeshCanon
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Region
import constructit.geom.Segment
import constructit.geom.Sketch3
import constructit.geom.Skin3
import constructit.geom.SkinMatch
import constructit.geom.SkinRow
import constructit.geom.SkinSection
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of "the flap becomes the gate"** on fixtures the delivery never saw: a drill into two
 * different slanted faces of one pyramid, a rounded plate rasped and bevelled with the corner radius itself (the
 * ball standing still on all four corners at once), and skins whose twist folds or does not.
 */
class FlapGateProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(text: String) {
        for (c in text) key(c.toString())
        key("Enter")
    }

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    private fun meshOf(el: Element) = Evaluator().solid(el.ref as SolidRef).mesh

    private fun reasonOf(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    private fun requireEngine() = assumeTrue(MeshBool.available, "needs the general boolean engine: ${MeshBool.status}")

    // ---- 1. two drills into two slanted faces of one pyramid ----

    @Test
    fun drillsIntoTwoSlantedFacesOfAPyramidLeaveNoFold() {
        requireEngine()
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.type("90")
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(50.0, 50.0))
        assertEquals(1, ed.solids().size, "the pyramid: ${ed.statusHint}")
        val before = Geom3.volume(meshOf(ed.solids().single()))
        var expectedRemoved = 0.0
        for ((edgeAt, at) in listOf(Vec2(30.0, 0.0) to Vec2(10.0, 30.0), Vec2(100.0, 60.0) to Vec2(-20.0, 45.0))) {
            ed.setActiveSpace("plan")
            ed.setTool(Tools.SKETCH_ON_FACE)
            ed.click(edgeAt)
            assertTrue(ed.activeSpace.isFace, "a slanted face: ${ed.statusHint}")
            ed.setTool(Tools.CIRCLE_R)
            ed.type("4")
            ed.click(at)
            ed.setTool(Tools.CUT)
            ed.type("6")
            ed.click(at + Vec2(4.0, 0.0))
            val part = ed.solids().last()
            assertNull(reasonOf(part), "the cut body is valid")
            val mesh = meshOf(part)
            assertManifold(mesh, "the pyramid drilled at $at")
            assertNull(MeshCanon.fault(mesh), "the production gate is silent")
            expectedRemoved += PI * 16.0 * 6.0
        }
        val removed = before - Geom3.volume(meshOf(ed.solids().last()))
        assertTrue(removed < expectedRemoved && removed > expectedRemoved * 0.97, "two ⌀8 bores 6 deep: $removed of $expectedRemoved")
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "a fixed point")
    }

    // ---- 2. a rounded plate rasped with its own corner radius: four balls standing still ----

    private fun view(
        ed: Editor,
        cam: Camera3,
    ): Viewport3 {
        val vp = Viewport3(camera = cam, widthPx = 800.0, heightPx = 600.0)
        vp.editor = ed
        vp.shown = true
        return vp
    }

    private fun Viewport3.clickWorld(p: Vec3) {
        val s = assertNotNull(camera.project(p, widthPx, heightPx), "$p has an image on screen")
        pointerDown(s)
        pointerUp(s)
    }

    private val w = 80.0
    private val hgt = 50.0
    private val r = 8.0
    private val depth = 20.0

    /** A rounded plate whose corner radius is one parameter, and that parameter armed for the next tool. */
    private fun roundedPlate(): Editor {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("r", r.mm)
        ed.setTool(Tools.ROUNDED_RECT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(w, hgt))
        ed.activeScalar = ed.doc.newParameter("d", depth.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(w / 2, 0.0))
        assertEquals(1, ed.solids().size, "the plate: ${ed.statusHint}")
        ed.activeScalar = ed.doc.scalars.first { it.name == "r" }
        return ed
    }

    private fun plateVolume(): Double = (w * hgt - (4 - PI) * r * r) * depth

    private fun straightRun(): Double = 2 * (w - 2 * r) + 2 * (hgt - 2 * r)

    private fun raspTop(
        ed: Editor,
        tool: String,
    ): Element {
        ed.setTool(tool)
        val t = Vec3(w / 2, hgt / 2, depth / 2)
        view(ed, Camera3(target = t, distance = 300.0, yaw = -1.0, pitch = 1.1)).clickWorld(Vec3(w / 2, hgt / 2, depth))
        assertEquals(2, ed.solids().size, "the rasped plate: ${ed.statusHint}")
        val el = ed.solids().last()
        assertNull(reasonOf(el), "valid")
        return el
    }

    private fun assertPoles(
        mesh: constructit.geom.Mesh3,
        what: String,
    ) {
        for (cx in listOf(r, w - r)) for (cy in listOf(r, hgt - r)) {
            val pole = mesh.vertices.count { abs(it.x - cx) < 1e-6 && abs(it.y - cy) < 1e-6 && abs(it.z - depth) < 1e-6 }
            assertTrue(pole >= 1, "$what: a pole at the arc's centre ($cx, $cy) on the top face")
        }
    }

    @Test
    fun aRoundedPlateRaspedWithItsCornerRadiusHasFourBallsStandingStill() {
        val ed = roundedPlate()
        val rasped = raspTop(ed, Tools.BLEND_FACE)
        val mesh = meshOf(rasped)
        assertManifold(mesh, "the rasped plate")
        assertNull(MeshCanon.fault(mesh))
        assertPoles(mesh, "fillet")
        // straight bands plus four quarter-balls, no joints: the run is tangent all the way round
        val exact = plateVolume() - (r * r * (1 - PI / 4) * straightRun() + 4 * (PI / 2) * r * r * r / 6)
        val v = Geom3.volume(mesh)
        assertTrue(v <= exact + 1e-6 && v >= exact * 0.995, "the rasped plate: $v vs $exact")
        // every vertex of a ball patch is its own radius from the ball's centre
        for (cx in listOf(r, w - r)) for (cy in listOf(r, hgt - r)) {
            val centre = Vec3(cx, cy, depth - r)
            val onBall =
                mesh.vertices.filter { v3 ->
                    val dx = v3.x - cx
                    val dy = v3.y - cy
                    // over the corner's own quadrant, above the band's foot
                    (if (cx < w / 2) dx <= 1e-9 else dx >= -1e-9) && (if (cy < hgt / 2) dy <= 1e-9 else dy >= -1e-9) &&
                        v3.z > depth - r + 1e-6 && v3.z < depth - 1e-6 && (dx * dx + dy * dy) > 1e-12
                }
            assertTrue(onBall.isNotEmpty(), "the corner at ($cx, $cy) has a patch")
            // the general engine hands the body back in float32, so a vertex sits within a few microns of the ball
            for (p in onBall) assertClose((p - centre).length(), r, 1e-4, "on the ball at ($cx, $cy): $p")
        }
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "a fixed point")
    }

    @Test
    fun aRoundedPlateBevelledWithItsCornerRadiusHasFourConesStandingStill() {
        val ed = roundedPlate()
        val bevelled = raspTop(ed, Tools.CHAMFER_FACE)
        val mesh = meshOf(bevelled)
        assertManifold(mesh, "the bevelled plate")
        assertNull(MeshCanon.fault(mesh))
        assertPoles(mesh, "chamfer")
        // the bevel's triangle swept straight, and revolved a quarter about each arc: quarter cylinder minus quarter cone
        val exact = plateVolume() - (r * r / 2 * straightRun() + 4 * PI * r * r * r / 6)
        val v = Geom3.volume(mesh)
        assertTrue(v <= exact + 1e-6 && v >= exact * 0.995, "the bevelled plate: $v vs $exact")
    }

    // ---- 3. skins: a twist that folds is refused, one that does not builds ----

    private fun polygon(pts: List<Vec2>): Loop = Loop(pts.indices.map { ProfileElement.Seg(Segment(pts[it], pts[(it + 1) % pts.size])) })

    private fun regular(
        n: Int,
        radius: Double,
    ): Loop = polygon((0 until n).map { Vec2(radius * cos(2 * PI * it / n), radius * sin(2 * PI * it / n)) })

    private fun section(
        z: Double,
        loop: Loop,
    ) = SkinSection(Sketch3(Plane3(Vec3(0.0, 0.0, z), Vec3.X, Vec3.Y), listOf(Region(loop, emptyList()))), z)

    @Test
    fun aTwistThatFoldsIsRefusedAndOneThatDoesNotBuilds() {
        // a 30 x 10 rectangle turned one corner round: a quarter turn of a four-piece correspondence folds the band
        val rect = polygon(listOf(Vec2(-15.0, -5.0), Vec2(15.0, -5.0), Vec2(15.0, 5.0), Vec2(-15.0, 5.0)))
        val (folded, why) = Skin3.skin(listOf(section(0.0, rect), section(40.0, rect)), SkinRow.RULED, listOf(SkinMatch(0, 0, 1)))
        assertNull(folded, "a quarter turn of a rectangle folds the band")
        assertTrue("fold" in assertNotNull(why), "and the refusal says so: $why")
        // a triangle one corner round is a third of a turn: whatever it is, it passes the gate or it is refused by name
        val (tri, whyTri) = Skin3.skin(listOf(section(0.0, regular(3, 20.0)), section(40.0, regular(3, 20.0))), SkinRow.RULED, listOf(SkinMatch(0, 0, 1)))
        if (tri != null) {
            assertManifold(tri.mesh, "the twisted triangle")
            assertNull(MeshCanon.fault(tri.mesh))
            val prism = 3 * 0.5 * 20.0 * 20.0 * sin(2 * PI / 3) * 40.0
            val v = Geom3.volume(tri.mesh)
            assertTrue(v < prism && v > prism * 0.5, "a twisted triangular prism holds less than the straight one: $v vs $prism")
        } else {
            assertTrue("fold" in assertNotNull(whyTri), "refused by name: $whyTri")
        }
        val (oct, whyOct) = Skin3.skin(listOf(section(0.0, regular(8, 20.0)), section(40.0, regular(8, 20.0))), SkinRow.RULED, listOf(SkinMatch(0, 0, 1)))
        val body = assertNotNull(oct, "an eighth of a turn between two octagons is a body: $whyOct")
        assertManifold(body.mesh, "the twisted octagon")
        assertNull(MeshCanon.fault(body.mesh))
        // a ruled band is the polyhedron of its stated split (SkinTest's own decision): each warped quad is cut
        // from its lower rail, which sits a tetrahedron below the bilinear patch, so the twisted prism holds
        // measurably less than the straight one and never more
        val prism = 8 * 0.5 * 20.0 * 20.0 * sin(2 * PI / 8) * 40.0
        val v = Geom3.volume(body.mesh)
        assertTrue(v < prism && v > prism * 0.75, "a twisted octagonal prism: $v vs the straight $prism")
    }
}
