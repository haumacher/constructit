package constructit.editor

import constructit.units.Dimension
import constructit.units.Quantity
import kotlin.math.abs
import kotlin.math.round

/** Human-readable, unit-aware formatting of quantities for the properties panel. */
object Format {

    fun quantity(q: Quantity): String = when (q.dim) {
        Dimension.LENGTH -> num(q.mm) + " mm"
        Dimension.ANGLE -> num(q.deg) + "°"
        Dimension.NONE -> num(q.value)
        Dimension.AREA -> num(q.base) + " mm²"
        Dimension.VOLUME -> num(q.base) + " mm³"
        else -> num(q.base) + " [" + q.dim + "]"
    }

    /** Round to 3 decimals, trimming trailing zeros; deterministic across platforms. */
    fun num(x: Double): String {
        val scaled = round(abs(x) * 1000.0).toLong()
        val i = scaled / 1000
        val f = (scaled % 1000).toString().padStart(3, '0').trimEnd('0')
        val s = if (f.isEmpty()) "$i" else "$i.$f"
        return if (x < 0 && scaled != 0L) "-$s" else s
    }
}
