package constructit

import constructit.editor.Camera3
import constructit.editor.PointerButton
import constructit.editor.Scene3
import constructit.editor.SolidItem
import constructit.editor.Viewport3
import constructit.geom.Geom3
import constructit.geom.Plane3
import constructit.geom.Region
import constructit.geom.Sketch3
import constructit.geom.Vec2
import constructit.geom.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The 3D view's gestures, headless. [Viewport3] mirrors [constructit.editor.Editor]'s pointer API for
 * exactly this reason (OP-12): orbiting, zooming and panning are decisions, so they are asserted here
 * rather than eyeballed in a browser — the shell only forwards events.
 */
class Viewport3Test {
    private fun vp() = Viewport3(camera = Camera3(distance = 200.0, yaw = 0.0, pitch = 0.0), widthPx = 800.0, heightPx = 600.0)

    private fun Viewport3.drag(
        from: Vec2,
        to: Vec2,
        button: PointerButton = PointerButton.PRIMARY,
    ) {
        pointerDown(from, button)
        pointerMove(to)
        pointerUp(to)
    }

    @Test
    fun dragOrbitsByTheDraggedPixels() {
        val v = vp()
        v.drag(Vec2(100.0, 100.0), Vec2(200.0, 150.0))
        // dragging right turns the model right, so the eye goes the other way round the target
        assertClose(v.camera.yaw, -100.0 * Viewport3.ORBIT_RAD_PER_PX)
        assertClose(v.camera.pitch, 50.0 * Viewport3.ORBIT_RAD_PER_PX)
        // an orbit changes nothing else
        assertClose(v.camera.distance, 200.0)
        assertEquals(Vec3.ZERO, v.camera.target)
    }

    @Test
    fun anOrbitIsCumulativeAndAMoveWithoutAPressDoesNothing() {
        val v = vp()
        v.pointerMove(Vec2(500.0, 500.0)) // no press: not a gesture
        assertClose(v.camera.yaw, 0.0)
        v.pointerDown(Vec2(0.0, 0.0))
        v.pointerMove(Vec2(50.0, 0.0))
        v.pointerMove(Vec2(100.0, 0.0))
        assertTrue(v.dragging)
        v.pointerUp(Vec2(100.0, 0.0))
        assertFalse(v.dragging)
        assertClose(v.camera.yaw, -100.0 * Viewport3.ORBIT_RAD_PER_PX)
        // and once released, further moves are inert again
        v.pointerMove(Vec2(400.0, 0.0))
        assertClose(v.camera.yaw, -100.0 * Viewport3.ORBIT_RAD_PER_PX)
    }

    @Test
    fun wheelZooms() {
        val v = vp()
        v.wheel(Vec2(400.0, 300.0), -1.0)
        assertClose(v.camera.distance, 200.0 / Viewport3.ZOOM_STEP)
        v.wheel(Vec2(400.0, 300.0), 1.0)
        assertClose(v.camera.distance, 200.0)
        // zooming is towards the target, so it moves nothing else (there is no 3D picking to zoom at)
        assertEquals(Vec3.ZERO, v.camera.target)
        assertClose(v.camera.yaw, 0.0)
    }

    @Test
    fun middleDragAndSpaceDragBothPanTheTarget() {
        val byButton = vp()
        byButton.drag(Vec2(100.0, 100.0), Vec2(400.0, 100.0), PointerButton.MIDDLE)
        assertTrue(byButton.camera.target.length() > 0.0, "a middle drag must move the target")
        assertClose(byButton.camera.yaw, 0.0, msg = "a pan must not orbit")

        val bySpace = vp()
        bySpace.panMode = true
        bySpace.drag(Vec2(100.0, 100.0), Vec2(400.0, 100.0))
        assertClose(bySpace.camera.target.x, byButton.camera.target.x)
        assertClose(bySpace.camera.target.y, byButton.camera.target.y)
        assertClose(bySpace.camera.target.z, byButton.camera.target.z)
    }

    /** Panning is what an orbit is not: it moves the point looked at, and only that. */
    @Test
    fun panMovesTheTargetOnlyAndByAKnownAmount() {
        val v = Viewport3(camera = Camera3(distance = 100.0, yaw = 0.0, pitch = 0.0, fovY = kotlin.math.PI / 2.0), heightPx = 600.0)
        v.drag(Vec2(0.0, 0.0), Vec2(300.0, 0.0), PointerButton.MIDDLE)
        // one pixel is 1/3 mm here (see Camera3Test), so 300 px is 100 mm along screen-left = -Y
        assertClose(v.camera.target.y, -100.0)
        assertClose(v.camera.distance, 100.0)
        assertClose(v.camera.pitch, 0.0)
    }

    @Test
    fun everyGestureReportsAChangeExactlyOnce() {
        val v = vp()
        var n = 0
        v.onChange = { n++ }
        v.pointerDown(Vec2(0.0, 0.0)) // a press alone changes no camera, so it reports nothing
        assertEquals(0, n)
        v.pointerMove(Vec2(10.0, 0.0))
        v.pointerUp(Vec2(10.0, 0.0))
        v.wheel(Vec2(0.0, 0.0), -1.0)
        assertEquals(3, n)
        v.pointerUp(Vec2(10.0, 0.0)) // a release with no press pending is not a gesture
        assertEquals(3, n)
    }

    @Test
    fun framingKeepsTheViewingDirectionAndFitsTheSolids() {
        val v = vp()
        v.drag(Vec2(0.0, 0.0), Vec2(120.0, 40.0)) // some arbitrary viewing direction
        val yaw = v.camera.yaw
        val pitch = v.camera.pitch
        v.frame(box(60.0, 40.0, 10.0))
        assertClose(v.camera.yaw, yaw, msg = "framing re-aims, it does not re-orient")
        assertClose(v.camera.pitch, pitch)
        assertClose(v.camera.target.x, 30.0)
        assertClose(v.camera.target.y, 20.0)
        assertClose(v.camera.target.z, 5.0)
        // an empty scene leaves the camera exactly as it was — there is nothing to frame
        val before = v.camera
        v.frame(Scene3(emptyList(), emptyList()))
        assertEquals(before, v.camera)
    }

    @Test
    fun renderingPutsTheSolidOnScreen() {
        val v = vp()
        val scene = box(60.0, 40.0, 10.0)
        v.frame(scene)
        val target = constructit.editor.SvgDrawTarget()
        v.render(scene, target)
        val svg = target.svg()
        assertTrue(svg.contains("<polygon"), "a shaded solid is drawn as filled polygons")
        val b = assertNotNull(constructit.editor.Painter3.projectedBounds(scene, v.camera, v.widthPx, v.heightPx))
        assertTrue(b.first.x >= 0.0 && b.second.x <= v.widthPx, "the framed box fits horizontally: $b")
        assertTrue(b.first.y >= 0.0 && b.second.y <= v.heightPx, "the framed box fits vertically: $b")
    }

    /** A [w] x [h] x [d] box at the origin, as a one-solid scene — the smallest thing worth looking at. */
    private fun box(
        w: Double,
        h: Double,
        d: Double,
    ): Scene3 {
        val region = rectRegion(0.0, 0.0, w, h)
        val solid = Geom3.extrude(Sketch3(Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), listOf(region)), d).first!!
        return Scene3(listOf(SolidItem("e1", solid.mesh, Scene3.PALETTE[1])), Scene3.furniture(10.0))
    }

    companion object {
        /** An axis-aligned rectangular [Region] — the plainest profile there is. */
        fun rectRegion(
            x0: Double,
            y0: Double,
            x1: Double,
            y1: Double,
        ): Region {
            val pts = listOf(Vec2(x0, y0), Vec2(x1, y0), Vec2(x1, y1), Vec2(x0, y1))
            val segs =
                pts.indices.map { i ->
                    constructit.geom.ProfileElement.Seg(constructit.geom.Segment(pts[i], pts[(i + 1) % pts.size]))
                }
            return Region(constructit.geom.Loop(segs), emptyList())
        }
    }
}
