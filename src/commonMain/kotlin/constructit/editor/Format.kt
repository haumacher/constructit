package constructit.editor

import constructit.l10n.L10n
import constructit.l10n.Msg
import constructit.l10n.Msgs
import constructit.l10n.decimalSeparator
import constructit.l10n.formatNumber
import constructit.l10n.readDecimal
import constructit.units.Dimension
import constructit.units.Quantity
import kotlin.math.abs
import kotlin.math.round

/**
 * Human-readable, unit-aware formatting of quantities — for the properties panel, the status line and the
 * dimension annotations on the drawing.
 *
 * **Two halves, and the split is the point** (OP-29 slice 3). [num] is *format*: the one canonical spelling
 * of a figure, a decimal point and three decimals, identical on every platform and in every language, which
 * is what the file is written with (OP-18) and what a golden compares. [quantityMsg] is *UI*: a message
 * value that pairs that figure with its unit and is spelled — separator included — only when something at
 * the edge renders it, in the language that reader is using. A German session reads `5,5 mm` off the very
 * same `Quantity` an English one reads `5.5 mm` off, and neither reading changes the file.
 */
object Format {
    /** The value with its unit, as a **message value** — rendered wherever, and whenever, it is read. */
    fun quantityMsg(q: Quantity): Msg =
        when (q.dim) {
            Dimension.LENGTH -> Msgs.formatLength(num(q.mm))
            Dimension.ANGLE -> Msgs.formatAngle(num(q.deg))
            Dimension.NONE -> Msgs.formatPlain(num(q.value))
            Dimension.AREA -> Msgs.formatArea(num(q.base))
            Dimension.VOLUME -> Msgs.formatVolume(num(q.base))
            else -> Msgs.formatOther(num(q.base), q.dim.toString())
        }

    /** [quantityMsg] read in the language now active — the shape every caller before slice 3 asked for. */
    fun quantity(q: Quantity): String = quantityMsg(q).render()

    /**
     * Round to 3 decimals, trimming trailing zeros; **deterministic across platforms and languages.**
     *
     * This is the figure's canonical spelling — a decimal point, no grouping — and it is deliberately still
     * hand-rolled arithmetic rather than a `NumberFormat`: the *rounding* must be the same number of digits
     * on the JVM and in the browser, in every locale, because it is what a golden compares and what the
     * writer puts in the file (OP-18). The locale enters one step later, in [display] and in
     * `Num`, and it changes the separator and nothing else.
     */
    fun num(x: Double): String {
        val scaled = round(abs(x) * 1000.0).toLong()
        val i = scaled / 1000
        val f = (scaled % 1000).toString().padStart(3, '0').trimEnd('0')
        val s = if (f.isEmpty()) "$i" else "$i.$f"
        return if (x < 0 && scaled != 0L) "-$s" else s
    }

    /**
     * The figure [x] as a **reader of [locale] would type it** — `5.5` in English, `5,5` in German.
     *
     * What the parameter panel puts in a value field, and the exact inverse of [read]: what this writes,
     * that reads back to the same double. The digits are [num]'s, so switching the language moves the
     * separator and never the value.
     */
    fun display(
        x: Double,
        locale: String = L10n.locale,
    ): String {
        val canonical = num(x)
        val value = canonical.toDoubleOrNull() ?: return canonical
        val point = canonical.indexOf('.')
        return formatNumber(locale, value, if (point < 0) 0 else canonical.length - point - 1)
    }

    /**
     * What a reader of [locale] typed, as a number — or null while the field is empty or half-typed.
     *
     * The one written rule of OP-29 (`readDecimal`), applied with the separator the reference engine itself
     * reports for this language. There is no reference *parser* in either target — `Intl.NumberFormat` and
     * ICU4J's `NumberFormat` both only write, and only one of them is in the browser at all — so this is the
     * single place the project states a rule rather than calling a library, and it is stated once for both
     * platforms so a German session on the JVM and in Chrome cannot disagree about what `5,5` means.
     */
    fun read(
        text: String,
        locale: String = L10n.locale,
    ): Double? = readDecimal(text, decimalSeparator(locale))

    /** The unit symbol a value field is labelled with; the dimensionless ones are labelled with nothing. */
    fun unitLabel(
        dim: Dimension,
        locale: String = L10n.locale,
    ): String =
        when (dim) {
            Dimension.LENGTH -> Msgs.unitMm().render(locale)
            Dimension.ANGLE -> Msgs.unitDeg().render(locale)
            else -> ""
        }
}
