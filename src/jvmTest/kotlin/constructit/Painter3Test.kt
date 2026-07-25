package constructit

import constructit.editor.Camera3
import constructit.editor.Painter3
import constructit.editor.Scene3
import constructit.editor.SolidItem
import constructit.editor.SvgDrawTarget
import constructit.geom.Geom3
import constructit.geom.Plane3
import constructit.geom.Sketch3
import constructit.geom.Vec2
import constructit.geom.Vec3
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The 3D view as an **SVG golden** — the same discipline as the 2D scene tests, which is what the
 * painter's projector exists for: the projection maths and the scene extraction are exercised end to
 * end, headlessly, from a fixed camera, and the artifact is human-inspectable (OP-12).
 *
 * The golden is *not* a claim about pixels in a browser. It is a claim about the shared [Camera3]
 * matrices and the scene: the WebGL path multiplies the very same matrix and shades by the same law, so
 * a change that moves this file moves the browser too.
 */
class Painter3Test {
    private val wPx = 640.0
    private val hPx = 420.0

    /** A fixed three-quarter view, so the golden does not depend on any default the shell may change. */
    private fun fixedCamera() =
        Camera3(target = Vec3(0.0, 15.0, 8.0), distance = 190.0, yaw = -1.05, pitch = 0.5, fovY = PI / 4.0)

    /**
     * A box and a revolved part, side by side on the ground plane: the two feature kinds this slice
     * ships, both drawn from their own mesh.
     */
    private fun scene(): Scene3 {
        val plane = Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)
        val box =
            Geom3.extrude(Sketch3(plane, listOf(Viewport3Test.rectRegion(-55.0, -12.0, -15.0, 28.0))), 14.0).first!!
        // a 10-wide profile 12 out from the axis, spun three-quarters of a turn about a line in the plane
        val turned =
            Geom3.revolve(
                Sketch3(plane, listOf(Viewport3Test.rectRegion(12.0, 0.0, 22.0, 34.0))),
                Vec2(0.0, 0.0),
                Vec2(0.0, 1.0),
                1.5 * PI,
            ).first!!
        val solids =
            listOf(
                SolidItem("e1", box.mesh, Scene3.colorFor("e1")),
                SolidItem("e2", turned.mesh, Scene3.colorFor("e2")),
            )
        return Scene3(solids, Scene3.furniture(Scene3.gridStepFor(Scene3.boundsOf(solids))))
    }

    @Test
    fun boxAndRevolvedPartFromAFixedCamera() {
        val scene = scene()
        assertManifold(scene.solids[0].mesh, "box")
        assertManifold(scene.solids[1].mesh, "turned part")
        val target = SvgDrawTarget()
        Painter3.render(scene, fixedCamera(), target, wPx, hPx)
        Golden.check("scene3-box-and-turned-part", target.svg())
    }

    @Test
    fun thePainterDrawsOnlyTheVisibleSideAndSortsItBackToFront() {
        val scene = scene()
        val cam = fixedCamera()
        val target = SvgDrawTarget()
        Painter3.render(scene, cam, target, wPx, hPx)
        val svg = target.svg()
        val faces = Regex("<polygon").findAll(svg).count()
        val total = scene.solids.sumOf { it.mesh.triangleCount }
        assertTrue(faces in 1 until total, "back faces must be culled: drew $faces of $total triangles")
        // the grid lies on the ground plane and the solids stand on it, so the furniture is painted first
        val firstPolygon = svg.indexOf("<polygon")
        val firstPolyline = svg.indexOf("<polyline")
        assertTrue(firstPolyline in 0 until firstPolygon, "the far grid lines come before the near solids")
    }

    /** The shading law: an exact, reproducible string per intensity, so a golden can contain colours. */
    @Test
    fun shadingIsExactAndClamped() {
        assertEquals("#4e79a7", Painter3.shade("#4e79a7", 1.0))
        assertEquals("#000000", Painter3.shade("#4e79a7", 0.0))
        assertEquals("#273d54", Painter3.shade("#4e79a7", 0.5))
        assertEquals("#4e79a7", Painter3.shade("#4e79a7", 2.0), "out-of-range intensity is clamped, not wrapped")
        assertEquals("not a colour", Painter3.shade("not a colour", 0.5), "an unparseable colour is passed through")
    }

    /** Nothing in front of the camera means nothing drawn — and no exception on the way there. */
    @Test
    fun aSceneBehindTheCameraDrawsNothing() {
        val scene = scene()
        val behind = fixedCamera().copy(target = Vec3(0.0, 100000.0, 0.0))
        val target = SvgDrawTarget()
        Painter3.render(scene, behind, target, wPx, hPx)
        assertEquals(0, Regex("<polygon").findAll(target.svg()).count())
        assertTrue(target.svg().startsWith("<svg"), "the surface is still opened and closed properly")
    }

    @Test
    fun everyMeshVertexProjectsInsideAFramedView() {
        val scene = scene()
        val b = assertNotNull(scene.bounds())
        val cam = Camera3.framing(b.first, b.second)
        val screen = assertNotNull(Painter3.projectedBounds(scene, cam, wPx, hPx))
        assertTrue(screen.first.x >= 0.0 && screen.second.x <= wPx, "framed scene fits horizontally: $screen")
        assertTrue(screen.first.y >= 0.0 && screen.second.y <= hPx, "framed scene fits vertically: $screen")
    }
}
