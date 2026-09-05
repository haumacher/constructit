package constructit.l10n

import com.ibm.icu.text.NumberFormat
import com.ibm.icu.util.ULocale

/**
 * ICU4J's `NumberFormat` (OP-29 slice 3) — the JVM half of the [formatNumber] seam, and the same engine
 * that renders the messages around the number. See the expect declaration in `commonMain` for why the
 * separator is a reference implementation's answer and not this repository's.
 *
 * The formatters are cached by locale and digit count because a status line re-renders on every repaint and
 * building one costs a CLDR lookup; there are two languages and four digit counts in play, so the map is
 * tiny and bounded.
 */
public actual fun formatNumber(
    locale: String,
    value: Double,
    fractionDigits: Int,
): String = formats.getOrPut(locale + "/" + fractionDigits) { make(locale, fractionDigits) }.format(value)

private val formats = HashMap<String, NumberFormat>()

private fun make(
    locale: String,
    fractionDigits: Int,
): NumberFormat =
    NumberFormat.getInstance(ULocale.forLanguageTag(locale.replace('_', '-'))).apply {
        isGroupingUsed = false
        minimumFractionDigits = 0
        maximumFractionDigits = fractionDigits
    }
