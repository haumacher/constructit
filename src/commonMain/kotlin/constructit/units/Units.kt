package constructit.units

import constructit.l10n.Msg
import constructit.l10n.MsgError
import constructit.l10n.Msgs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Physical dimension as integer exponents of the base dimensions.
 * Base units: length in millimetres, angle in radians (OP-7).
 */
data class Dimension(val length: Int, val angle: Int) {
    operator fun times(o: Dimension) = Dimension(length + o.length, angle + o.angle)

    operator fun div(o: Dimension) = Dimension(length - o.length, angle - o.angle)

    override fun toString(): String =
        when (this) {
            NONE -> "1"
            LENGTH -> "L"
            AREA -> "L^2"
            VOLUME -> "L^3"
            ANGLE -> "A"
            else -> "L^$length A^$angle"
        }

    companion object {
        val NONE = Dimension(0, 0)
        val LENGTH = Dimension(1, 0)
        val AREA = Dimension(2, 0)
        val VOLUME = Dimension(3, 0)
        val ANGLE = Dimension(0, 1)
    }
}

/** Raised when an operation combines incompatible dimensions (caught -> node invalid, OP-3). */
class DimensionError(why: Msg) : MsgError(why) {
    constructor(message: String) : this(Msg.text(message))
}

/**
 * A dimensioned quantity stored in canonical base units (mm, rad).
 * Dimensional analysis (OP-7): + and - require equal dimension; * and / combine dimensions.
 */
data class Quantity(val base: Double, val dim: Dimension) {
    operator fun plus(o: Quantity): Quantity {
        if (dim != o.dim) throw DimensionError("cannot add $dim and ${o.dim}")
        return Quantity(base + o.base, dim)
    }

    operator fun minus(o: Quantity): Quantity {
        if (dim != o.dim) throw DimensionError("cannot subtract ${o.dim} from $dim")
        return Quantity(base - o.base, dim)
    }

    operator fun times(o: Quantity) = Quantity(base * o.base, dim * o.dim)

    operator fun div(o: Quantity) = Quantity(base / o.base, dim / o.dim)

    operator fun times(factor: Double) = Quantity(base * factor, dim)

    operator fun unaryMinus() = Quantity(-base, dim)

    fun requireDim(
        expected: Dimension,
        what: Msg,
    ): Quantity {
        if (dim != expected) {
            throw DimensionError(Msgs.refusalDimensionRequires(what = what, expected = expected.toString(), got = dim.toString()))
        }
        return this
    }

    /** Value expressed in millimetres (length only). */
    val mm: Double get() = requireDim(Dimension.LENGTH, Msg.text("millimetres")).base

    /** Value expressed in degrees (angle only). */
    val deg: Double get() = requireDim(Dimension.ANGLE, Msg.text("degrees")).base * 180.0 / PI

    /** Raw dimensionless value. */
    val value: Double get() = base

    companion object {
        fun mm(v: Double) = Quantity(v, Dimension.LENGTH)

        fun cm(v: Double) = Quantity(v * 10.0, Dimension.LENGTH)

        fun deg(v: Double) = Quantity(v * PI / 180.0, Dimension.ANGLE)

        fun rad(v: Double) = Quantity(v, Dimension.ANGLE)

        fun number(v: Double) = Quantity(v, Dimension.NONE)
    }
}

// Convenience constructors.
val Double.mm get() = Quantity.mm(this)
val Double.cm get() = Quantity.cm(this)
val Double.deg get() = Quantity.deg(this)
val Double.rad get() = Quantity.rad(this)
val Int.mm get() = Quantity.mm(this.toDouble())
val Int.deg get() = Quantity.deg(this.toDouble())

// Dimension-aware transcendental helpers (angle -> dimensionless).
fun sin(q: Quantity): Quantity = Quantity(sin(q.requireDim(Dimension.ANGLE, Msg.text("sin")).base), Dimension.NONE)

fun cos(q: Quantity): Quantity = Quantity(cos(q.requireDim(Dimension.ANGLE, Msg.text("cos")).base), Dimension.NONE)

fun tan(q: Quantity): Quantity = Quantity(tan(q.requireDim(Dimension.ANGLE, Msg.text("tan")).base), Dimension.NONE)
