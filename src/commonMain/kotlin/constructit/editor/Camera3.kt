package constructit.editor

import constructit.geom.Vec2
import constructit.geom.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A 4x4 matrix in **column-major** order (element `(row, col)` lives at `m[col * 4 + row]`) — the
 * layout WebGL's `uniformMatrix4fv` expects untransposed, so [toFloatArray] hands the same numbers
 * straight to the GPU that [Camera3.project] used on the CPU.
 *
 * Plain arithmetic on a `DoubleArray`: `commonMain` must stay free of platform APIs (OP-12), and a
 * matrix is small enough that "no library" costs nothing. There is exactly *one* projection pipeline
 * in this engine — the painter's projector of [Painter3] and the browser's WebGL renderer both go
 * through here, which is what makes the SVG goldens evidence about what the browser draws.
 */
class Mat4(val m: DoubleArray) {
    init {
        require(m.size == 16) { "a 4x4 matrix has 16 elements, not ${m.size}" }
    }

    operator fun times(o: Mat4): Mat4 {
        val r = DoubleArray(16)
        for (c in 0..3) {
            for (row in 0..3) {
                var s = 0.0
                for (k in 0..3) s += m[k * 4 + row] * o.m[c * 4 + k]
                r[c * 4 + row] = s
            }
        }
        return Mat4(r)
    }

    /** [p] as a homogeneous point through this matrix — the clip-space coordinates. */
    fun transform(p: Vec3): Clip4 =
        Clip4(
            m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12],
            m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13],
            m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14],
            m[3] * p.x + m[7] * p.y + m[11] * p.z + m[15],
        )

    /** The same numbers as 32-bit floats, still column-major — what a GL uniform takes. */
    fun toFloatArray(): FloatArray = FloatArray(16) { m[it].toFloat() }

    companion object {
        val IDENTITY = Mat4(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0))

        /**
         * A right-handed perspective projection looking down −Z, mapping [near]..[far] onto −1..1.
         * [fovY] is the *vertical* field of view in radians, so the horizontal one follows [aspect].
         */
        fun perspective(
            fovY: Double,
            aspect: Double,
            near: Double,
            far: Double,
        ): Mat4 {
            val f = 1.0 / tan(fovY / 2.0)
            val d = near - far
            val r = DoubleArray(16)
            r[0] = f / aspect
            r[5] = f
            r[10] = (far + near) / d
            r[11] = -1.0
            r[14] = 2.0 * far * near / d
            return Mat4(r)
        }

        /**
         * The view matrix of an eye at [eye] looking at [target] with [up] roughly upward: world
         * coordinates into a camera space whose −Z axis is the viewing direction.
         */
        fun lookAt(
            eye: Vec3,
            target: Vec3,
            up: Vec3,
        ): Mat4 {
            val f = (target - eye).normalized()
            val s = f.cross(up).normalized()
            val u = s.cross(f)
            val r = DoubleArray(16)
            r[0] = s.x
            r[4] = s.y
            r[8] = s.z
            r[12] = -s.dot(eye)
            r[1] = u.x
            r[5] = u.y
            r[9] = u.z
            r[13] = -u.dot(eye)
            r[2] = -f.x
            r[6] = -f.y
            r[10] = -f.z
            r[14] = f.dot(eye)
            r[15] = 1.0
            return Mat4(r)
        }
    }
}

/** A point in clip space — [w] is what a perspective divide divides by. */
data class Clip4(val x: Double, val y: Double, val z: Double, val w: Double)

/**
 * The 3D view's camera: an **orbit** camera, described by what the gesture actually writes — where it
 * looks ([target]), how far away it is ([distance]) and from which direction ([yaw], [pitch]).
 *
 * Deliberately *not* a free 6-DOF camera. An orbit camera is the same "the state is the parameters"
 * discipline the model itself follows (OP-5): every gesture is a write of one of these four numbers, so
 * a view is reproducible, and a headless gesture test can assert exactly what a drag did (OP-12's
 * testability rule applied to the viewport). There is no accumulated matrix anywhere to drift.
 *
 * World **+Z is up**: 2D sketches live on the world XY plane and extrude along its normal (OP-17), so
 * the drawing plane is the ground plane and a part rises out of it.
 */
data class Camera3(
    val target: Vec3 = Vec3.ZERO,
    val distance: Double = 260.0,
    /** Rotation about the world Z axis, in radians: where in the XY plane the eye sits. */
    val yaw: Double = -PI / 3.0,
    /** Elevation above the XY plane, in radians, clamped short of straight down ([MAX_PITCH]). */
    val pitch: Double = PI / 6.0,
    /** Vertical field of view in radians. */
    val fovY: Double = PI / 4.0,
) {
    /**
     * The near and far planes **follow the distance** rather than being knobs of their own: a fixed
     * pair would either clip a part the camera has zoomed into or waste the depth buffer's precision on
     * empty space, and no caller has a reason to choose differently.
     */
    val near: Double get() = max(MIN_NEAR, distance * 0.01)

    val far: Double get() = max(MIN_NEAR * 1000.0, distance * 100.0)

    /** Where the eye is — derived, never stored, so the four numbers stay the whole state. */
    val eye: Vec3 get() = target + offset()

    private fun offset(): Vec3 {
        val cp = cos(pitch)
        return Vec3(cp * cos(yaw), cp * sin(yaw), sin(pitch)) * distance
    }

    /** The unit viewing direction, from the eye towards the target. */
    fun forward(): Vec3 = (target - eye).normalized()

    /** The unit screen-right direction in world space. */
    fun right(): Vec3 = forward().cross(UP).normalized()

    /** The unit screen-up direction in world space (exactly perpendicular to [forward]). */
    fun up(): Vec3 = right().cross(forward())

    fun view(): Mat4 = Mat4.lookAt(eye, target, UP)

    fun projection(
        wPx: Double,
        hPx: Double,
    ): Mat4 = Mat4.perspective(fovY, if (hPx <= 0.0) 1.0 else wPx / hPx, near, far)

    /** The one matrix both renderers use: world -> clip. */
    fun viewProjection(
        wPx: Double,
        hPx: Double,
    ): Mat4 = projection(wPx, hPx) * view()

    /**
     * World point [p] as a screen position in pixels (y down, like every other [DrawTarget]
     * coordinate), or **null** when it is at or behind the eye plane and therefore has no image.
     */
    fun project(
        p: Vec3,
        wPx: Double,
        hPx: Double,
    ): Vec2? = projectWith(viewProjection(wPx, hPx), p, wPx, hPx)

    /** [project] with a matrix computed once — what a whole scene's worth of vertices goes through. */
    fun projectWith(
        vp: Mat4,
        p: Vec3,
        wPx: Double,
        hPx: Double,
    ): Vec2? {
        val c = vp.transform(p)
        if (c.w <= CLIP_EPS) return null
        val nx = c.x / c.w
        val ny = c.y / c.w
        return Vec2((nx * 0.5 + 0.5) * wPx, (0.5 - ny * 0.5) * hPx)
    }

    /** How far [p] lies along the viewing direction — the painter's algorithm's sort key. */
    fun viewDepth(p: Vec3): Double = (p - eye).dot(forward())

    // ---- gestures: each one writes exactly one of the four numbers ----

    /** Turn the eye around the target. Pitch is clamped, so the up vector can never degenerate. */
    fun orbit(
        dYaw: Double,
        dPitch: Double,
    ): Camera3 = copy(yaw = yaw + dYaw, pitch = (pitch + dPitch).coerceIn(-MAX_PITCH, MAX_PITCH))

    /** Move the eye towards ([factor] < 1) or away from ([factor] > 1) the target. */
    fun zoom(factor: Double): Camera3 = copy(distance = (distance * factor).coerceIn(MIN_DISTANCE, MAX_DISTANCE))

    /**
     * Slide the target across the view by a screen displacement, so the model follows the cursor: the
     * scale is taken **at the target's depth**, which is what makes a pan feel like dragging the part
     * rather than the sky.
     *
     * Only the height is needed — pixels are square, so the vertical field of view fixes the world
     * distance a pixel covers in *both* directions, and taking the width as well would let a wrong
     * aspect ratio show up as a pan that drifts sideways.
     */
    fun panBy(
        dxPx: Double,
        dyPx: Double,
        hPx: Double,
    ): Camera3 {
        if (hPx <= 0.0) return this
        val worldPerPx = 2.0 * distance * tan(fovY / 2.0) / hPx
        val shift = right() * (-dxPx * worldPerPx) + up() * (dyPx * worldPerPx)
        return copy(target = target + shift)
    }

    companion object {
        val UP = Vec3.Z

        /** Just short of looking straight down, where the up vector would collapse. */
        val MAX_PITCH = PI / 2.0 - 1e-3

        const val MIN_DISTANCE = 0.1
        const val MAX_DISTANCE = 1e7
        const val MIN_NEAR = 0.01

        /** Points closer to the eye plane than this have no image (a division by ~zero). */
        const val CLIP_EPS = 1e-9

        /**
         * A camera framing the axis-aligned box [lo]..[hi]: looking at its centre from far enough away
         * that its bounding sphere fits the vertical field of view, with [margin] to spare.
         */
        fun framing(
            lo: Vec3,
            hi: Vec3,
            yaw: Double = -PI / 3.0,
            pitch: Double = PI / 6.0,
            margin: Double = 1.25,
        ): Camera3 {
            val centre = (lo + hi) * 0.5
            val d = hi - lo
            val radius = max(sqrt(d.x * d.x + d.y * d.y + d.z * d.z) / 2.0, 1.0)
            val fov = PI / 4.0
            val dist = (radius / sin(min(fov, PI / 2.0) / 2.0) * margin).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
            return Camera3(target = centre, distance = dist, yaw = yaw, pitch = pitch, fovY = fov)
        }
    }
}
