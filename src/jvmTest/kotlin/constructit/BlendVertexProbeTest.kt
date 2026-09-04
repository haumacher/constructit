package constructit

import constructit.core.Evaluator
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.editor.Camera3
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of the vertex blend (GitHub #31/#32)** on a fixture the delivery never saw: a box with
 * **all twelve** edges rounded by four face gestures — eight three-band vertices — against the closed-form
 * volume of a rounded box, then the *Section* tool through the ball patches, then the file.
 */
class BlendVertexProbeTest {
    private val a = 40.0
    private val b = 30.0
    private val c = 20.0
    private val r = 4.0

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    private fun Editor.areas(): List<Element> = doc.elements.filter { it.kind == ElementKind.AREA }

    @Suppress("UNCHECKED_CAST")
    private fun Editor.meshOf(el: Element) = Evaluator().solid(el.ref as SolidRef).mesh

    @Suppress("UNCHECKED_CAST")
    private fun Editor.areaOf(el: Element) = Evaluator().scalar(doc.cx.regionArea(el.ref as RegionRef)).base

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

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

    /** A 40 x 30 x 20 box with parameter r armed. */
    private fun box(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(a, b))
        ed.activeScalar = ed.doc.newParameter("depth", c.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(a / 2, 0.0))
        assertEquals(1, ed.solids().size, "the box: ${ed.statusHint}")
        ed.activeScalar = ed.doc.newParameter("r", r.mm)
        return ed
    }

    /** Round a whole face through the 3D view, aimed at [at] from [cam]; asserts a new solid and how many edges it took. */
    private fun roundFace(
        ed: Editor,
        cam: Camera3,
        at: Vec3,
        edges: String?,
    ) {
        ed.activeScalar = ed.doc.scalars.first { it.name == "r" }
        ed.setTool(Tools.BLEND_FACE)
        val before = ed.solids().size
        view(ed, cam).clickWorld(at)
        assertEquals(before + 1, ed.solids().size, "face at $at from $cam: ${ed.statusHint}")
        if (edges != null) assertTrue(edges in ed.statusHint, "$edges: ${ed.statusHint}")
        assertManifold(ed.meshOf(ed.solids().last()), "after rounding the face at $at")
    }

    /** All twelve edges: the top face from above, the bottom from below, the two x-faces from either side. */
    private fun fullyRounded(): Editor {
        val ed = box()
        val t = Vec3(a / 2, b / 2, c / 2)
        roundFace(ed, Camera3(target = t, distance = 220.0, yaw = -1.0, pitch = 1.1), Vec3(a / 2, b / 2, c), "(4 edges)")
        roundFace(ed, Camera3(target = t, distance = 220.0, yaw = -1.0, pitch = -1.1), Vec3(a / 2, b / 2, 0.0), "(4 edges)")
        // the two side faces: two of their four edges are already round — a face gesture takes what is still sharp
        roundFace(ed, Camera3(target = t, distance = 220.0, yaw = PI, pitch = 0.3), Vec3(0.0, b / 2, c / 2), null)
        roundFace(ed, Camera3(target = t, distance = 220.0, yaw = 0.0, pitch = 0.3), Vec3(a, b / 2, c / 2), null)
        return ed
    }

    /** The rounded box: the inner box, the six slabs, the twelve quarter-cylinders and the eight octants of one ball. */
    private fun exactRounded(): Double {
        val x = a - 2 * r
        val y = b - 2 * r
        val z = c - 2 * r
        return x * y * z + 2 * r * (x * y + x * z + y * z) + PI * r * r * (x + y + z) + 4.0 / 3.0 * PI * r * r * r
    }

    @Test
    fun aBoxRoundedOnAllTwelveEdgesIsTheRoundedBox() {
        val ed = fullyRounded()
        val mesh = ed.meshOf(ed.solids().last())
        val v = Geom3.volume(mesh)
        val exact = exactRounded()
        // the arcs arrive as inscribed chords, so the mesh never holds more than the exact body and never much less
        assertTrue(v <= exact + 1e-6, "never above the exact rounded box: $v vs $exact")
        assertTrue(v >= exact * 0.995, "and within the chords of it: $v vs $exact")
        // the ball's octants are where three cylinders alone would have left a point: the eight vertices are round
        assertTrue(v < exactRounded() + 8 * (2 - 7 * PI / 12) * r * r * r * 0.5, "the vertices lost their tips: $v")
    }

    @Test
    fun aSectionThroughTheBallPatchesIsTheRoundedRectangleTheBallsSay() {
        val ed = fullyRounded()
        val z = c - r / 2 // half-way up the top band, cutting eight ball patches
        ed.activeScalar = ed.doc.newParameter("cut", z.mm)
        ed.setTool(Tools.SECTION)
        ed.click(Vec2(a / 2, 0.0))
        val section = assertNotNull(ed.areas().lastOrNull(), "the section was made: ${ed.statusHint}")
        // at height z within the top band the outline is inset by δ and its corners have radius ρ = r − δ
        val dz = z - (c - r)
        val rho = sqrt(r * r - dz * dz)
        val delta = r - rho
        val exact = (a - 2 * delta) * (b - 2 * delta) - (4 - PI) * rho * rho
        val area = ed.areaOf(section)
        assertClose(area / exact, 1.0, tol = 2e-3, msg = "the section of the balls and bands at z = $z: $area vs $exact")
    }

    @Test
    fun theFourGesturesRoundTripAndRebuildTheSameBody() {
        val ed = fullyRounded()
        val v = Geom3.volume(ed.meshOf(ed.solids().last()))
        val saved = DocumentFormat.save(ed.doc)
        assertEquals(4, saved.lines().count { it.trim().startsWith("tool filletfaceedges") }, "four recorded gestures:\n$saved")
        val again = DocumentFormat.load(saved)
        assertEquals(saved, DocumentFormat.save(again), "byte-equal round trip")
        val back = again.elements.last { it.kind == ElementKind.SOLID }

        @Suppress("UNCHECKED_CAST")
        val mesh = Evaluator().solid(back.ref as SolidRef).mesh
        assertManifold(mesh, "reloaded rounded box")
        assertClose(Geom3.volume(mesh), v, tol = 1e-9, msg = "the same body")
    }
}
