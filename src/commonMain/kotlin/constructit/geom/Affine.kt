package constructit.geom

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A 2D affine map: (x,y) -> (a*x + c*y + e, b*x + d*y + f).
 * Used to reflect/rotate/scale/translate whole geometry values uniformly.
 */
data class Affine(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val e: Double,
    val f: Double,
) {
    fun apply(p: Vec2) = Vec2(a * p.x + c * p.y + e, b * p.x + d * p.y + f)

    fun linear(v: Vec2) = Vec2(a * v.x + c * v.y, b * v.x + d * v.y)

    val det: Double get() = a * d - b * c

    /** Uniform scale factor (valid for similarity transforms: rotation/reflection/uniform scale). */
    val scale: Double get() = sqrt(abs(det))

    companion object {
        fun translation(t: Vec2) = Affine(1.0, 0.0, 0.0, 1.0, t.x, t.y)

        fun rotation(
            center: Vec2,
            theta: Double,
        ): Affine {
            val co = cos(theta)
            val si = sin(theta)
            // A = [[co,-si],[si,co]]; t = center - A*center
            val tx = center.x - (co * center.x - si * center.y)
            val ty = center.y - (si * center.x + co * center.y)
            return Affine(co, si, -si, co, tx, ty)
        }

        fun reflection(line: Line): Affine {
            val u = line.dir.normalized()
            val a = 2 * u.x * u.x - 1
            val bc = 2 * u.x * u.y
            val d = 2 * u.y * u.y - 1
            val o = line.origin
            // t = o - A*o
            val tx = o.x - (a * o.x + bc * o.y)
            val ty = o.y - (bc * o.x + d * o.y)
            return Affine(a, bc, bc, d, tx, ty)
        }

        fun scaling(
            center: Vec2,
            k: Double,
        ) =
            Affine(k, 0.0, 0.0, k, center.x * (1 - k), center.y * (1 - k))

        /**
         * The **point reflection** through [center]: every point lands exactly as far the other side of it,
         * at `2·center − p`.
         *
         * The same map [rotation] gives at π, written as its own constant rather than derived from one —
         * which buys exactness as well as a name: `sin(π)` is 1.2e-16 rather than 0, so a half turn spelled
         * as a rotation shears its image by that fraction of its distance from the centre, and this one does
         * not. In the plane it is *proper* (det = +1, a rotation); one dimension up the same idea is not
         * (see the note in `core/Transform.kt`).
         */
        fun pointReflection(center: Vec2) = Affine(-1.0, 0.0, 0.0, -1.0, 2 * center.x, 2 * center.y)
    }
}
