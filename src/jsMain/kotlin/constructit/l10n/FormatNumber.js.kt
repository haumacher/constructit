package constructit.l10n

/**
 * The browser's own `Intl.NumberFormat` (OP-29 slice 3) — the JS half of the [formatNumber] seam.
 *
 * Declared by hand, as `intl-messageformat` and the three.js surface beside it are: one constructor and one
 * method is the whole of what this repository calls. Nothing is bundled for it — number formatting for every
 * language a browser knows is already in the browser, which is the other half of why slice 3 could take the
 * text out of the main bundle without putting a formatter back in.
 */
public actual fun formatNumber(
    locale: String,
    value: Double,
    fractionDigits: Int,
): String = formats.getOrPut(locale + "/" + fractionDigits) { make(locale, fractionDigits) }.format(value)

private val formats = HashMap<String, Intl.NumberFormat>()

private fun make(
    locale: String,
    fractionDigits: Int,
): Intl.NumberFormat {
    val options: dynamic = js("({})")
    options.useGrouping = false
    options.minimumFractionDigits = 0
    options.maximumFractionDigits = fractionDigits
    return Intl.NumberFormat(locale, options)
}
