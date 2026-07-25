package constructit

import constructit.editor.Camera3
import constructit.editor.Mat4
import constructit.geom.Vec3
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OP-12's viewport half: the 3D camera is plain matrix arithmetic in `commonMain`, so the projection is
 * pinned by known answers here rather than by looking at a browser.
 *
 * The numbers are chosen so they can be worked out by hand: a 90° vertical field of view at distance
 * 100 puts the half-height of the view *exactly* 100 mm from the axis, so the screen edges are round
 * numbers and nothing is asserted to within a fudge factor.
 */
class Camera3Test {
    private val wPx = 800.0
    private val hPx = 600.0

    /** Looking down −X from (100,0,0) with a 90° vertical field of view. Screen right is +Y, up is +Z. */
    private fun sideOn() = Camera3(target = Vec3.ZERO, distance = 100.0, yaw = 0.0, pitch = 0.0, fovY = PI / 2.0)

    @Test
    fun theEyeAndItsFrameFollowTheFourNumbers() {
        val cam = sideOn()
        val e = cam.eye
        assertClose(e.x, 100.0)
        assertClose(e.y, 0.0)
        assertClose(e.z, 0.0)
        // forward is towards the target; right = forward x up; up completes the right-handed frame
        assertClose(cam.forward().x, -1.0)
        assertClose(cam.right().y, 1.0)
        assertClose(cam.up().z, 1.0)
    }

    @Test
    fun knownPointsProjectToKnownPixels() {
        val cam = sideOn()
        // the target is the centre of the screen, always
        val c = assertNotNull(cam.project(Vec3.ZERO, wPx, hPx))
        assertClose(c.x, 400.0)
        assertClose(c.y, 300.0)

        // fovY = 90 deg at distance 100 -> the half-height of the view is 100 mm, so +100 in Z is the
        // top edge of the viewport and half of that is a quarter of the way down from the centre
        val top = assertNotNull(cam.project(Vec3(0.0, 0.0, 100.0), wPx, hPx))
        assertClose(top.x, 400.0)
        assertClose(top.y, 0.0)
        val halfUp = assertNotNull(cam.project(Vec3(0.0, 0.0, 50.0), wPx, hPx))
        assertClose(halfUp.y, 150.0)

        // the half-width follows the 4:3 aspect: 100 * 800/600 = 133.333 mm, so +100 in Y (screen right)
        // lands at 0.75 of the way to the right edge
        val right = assertNotNull(cam.project(Vec3(0.0, 100.0, 0.0), wPx, hPx))
        assertClose(right.x, 700.0)
        assertClose(right.y, 300.0)
    }

    @Test
    fun perspectiveShrinksWithDistanceAndDropsWhatIsBehindTheEye() {
        val cam = sideOn()
        // the same 50 mm offset, twice as far from the eye, subtends half the angle
        val near = assertNotNull(cam.project(Vec3(0.0, 0.0, 50.0), wPx, hPx))
        val far = assertNotNull(cam.project(Vec3(-100.0, 0.0, 50.0), wPx, hPx))
        assertClose(300.0 - near.y, 150.0)
        assertClose(300.0 - far.y, 75.0)
        // at or behind the eye plane there is no image at all, rather than a mirrored one
        assertNull(cam.project(cam.eye, wPx, hPx), "the eye itself has no image")
        assertNull(cam.project(Vec3(200.0, 0.0, 0.0), wPx, hPx), "a point behind the eye has no image")
    }

    /** [Camera3.project] must be exactly [Camera3.projectWith] over the shared matrix — the GPU's path. */
    @Test
    fun theCachedMatrixIsTheSameProjection() {
        val cam = sideOn().orbit(0.7, -0.2)
        val vp = cam.viewProjection(wPx, hPx)
        for (p in listOf(Vec3(10.0, 20.0, 30.0), Vec3(-40.0, 5.0, 0.0), Vec3(0.0, 0.0, 60.0))) {
            val a = assertNotNull(cam.project(p, wPx, hPx))
            val b = assertNotNull(cam.projectWith(vp, p, wPx, hPx))
            assertClose(a.x, b.x, tol = 1e-12)
            assertClose(a.y, b.y, tol = 1e-12)
        }
    }

    @Test
    fun orbitKeepsTheTargetCentredAndTheDistanceUnchanged() {
        var cam = Camera3(target = Vec3(12.0, -7.0, 3.0), distance = 250.0)
        val d0 = cam.distance
        for (i in 0..11) {
            cam = cam.orbit(0.5, if (i % 2 == 0) 0.2 else -0.3)
            assertClose(cam.distance, d0, msg = "orbit must not change the distance")
            assertClose((cam.eye - cam.target).length(), d0, msg = "the eye stays on the sphere")
            val c = assertNotNull(cam.project(cam.target, wPx, hPx))
            assertClose(c.x, wPx / 2, tol = 1e-9, msg = "the target stays centred")
            assertClose(c.y, hPx / 2, tol = 1e-9, msg = "the target stays centred")
        }
    }

    @Test
    fun pitchIsClampedShortOfStraightDown() {
        val up = Camera3().orbit(0.0, 100.0)
        val down = Camera3().orbit(0.0, -100.0)
        assertClose(up.pitch, Camera3.MAX_PITCH)
        assertClose(down.pitch, -Camera3.MAX_PITCH)
        // the interesting part: the frame is still well defined at the clamp, so nothing degenerates
        assertClose(up.right().length(), 1.0)
        assertClose(up.up().length(), 1.0)
        assertTrue(up.project(Vec3.ZERO, wPx, hPx) != null)
    }

    @Test
    fun zoomIsAPureDistanceChangeAndIsBounded() {
        val cam = Camera3(distance = 200.0, yaw = 0.3, pitch = 0.4)
        val inn = cam.zoom(0.5)
        assertClose(inn.distance, 100.0)
        assertEquals(cam.yaw, inn.yaw)
        assertEquals(cam.pitch, inn.pitch)
        assertEquals(cam.target, inn.target)
        var tiny = cam
        for (i in 0..200) tiny = tiny.zoom(0.5)
        assertClose(tiny.distance, Camera3.MIN_DISTANCE)
    }

    /**
     * A pan is a world displacement of the target across the view. At distance 100 with a 90° field of
     * view over 600 px, one pixel is exactly 1/3 mm, so a 300 px drag moves the target 100 mm — and
     * *only* the target: the viewing direction and distance are untouched.
     */
    @Test
    fun panMovesTheTargetAcrossTheViewByAKnownAmount() {
        val cam = sideOn()
        val panned = cam.panBy(300.0, -150.0, hPx)
        assertClose(panned.distance, cam.distance)
        assertClose(panned.yaw, cam.yaw)
        assertClose(panned.pitch, cam.pitch)
        // dragging right pulls the model right, so the target goes left: -100 along screen-right (+Y)
        assertClose(panned.target.y, -100.0)
        // dragging up (negative dy) moves the target down: -50 along screen-up (+Z)
        assertClose(panned.target.z, -50.0)
        assertClose(panned.target.x, 0.0)
    }

    @Test
    fun framingABoxPutsAllOfItOnScreen() {
        val lo = Vec3(-40.0, -25.0, 0.0)
        val hi = Vec3(40.0, 25.0, 6.0)
        val cam = Camera3.framing(lo, hi)
        val corners =
            listOf(lo.x, hi.x).flatMap { x -> listOf(lo.y, hi.y).flatMap { y -> listOf(lo.z, hi.z).map { z -> Vec3(x, y, z) } } }
        for (p in corners) {
            val s = assertNotNull(cam.project(p, wPx, hPx), "corner $p must be in front of the camera")
            assertTrue(s.x in 0.0..wPx && s.y in 0.0..hPx, "corner $p projected off screen at $s")
        }
        // the box's centre is what it looks at
        assertClose(cam.target.x, 0.0)
        assertClose(cam.target.z, 3.0)
    }

    @Test
    fun matrixProductAgreesWithApplyingTheFactorsInTurn() {
        val a = Mat4.perspective(PI / 3.0, 1.5, 1.0, 500.0)
        val b = Mat4.lookAt(Vec3(30.0, 40.0, 50.0), Vec3(1.0, 2.0, 3.0), Vec3.Z)
        val p = Vec3(7.0, -3.0, 11.0)
        val direct = (a * b).transform(p)
        val viaB = b.transform(p)
        val stepwise =
            a.transform(Vec3(viaB.x / viaB.w, viaB.y / viaB.w, viaB.z / viaB.w))
        // the view matrix leaves w = 1, so the two routes must give the same clip point
        assertClose(viaB.w, 1.0, tol = 1e-12)
        assertClose(direct.x, stepwise.x, tol = 1e-9)
        assertClose(direct.y, stepwise.y, tol = 1e-9)
        assertClose(direct.w, stepwise.w, tol = 1e-9)
        // and the identity is an identity
        assertClose((Mat4.IDENTITY * a).m.zip(a.m).sumOf { (x, y) -> kotlin.math.abs(x - y) }, 0.0, tol = 1e-12)
    }
}
