@file:Suppress("ktlint:standard:filename")

package constructit.l10n

/**
 * The `Intl` namespace, as much of it as this repository asks for (OP-29 slice 3).
 *
 * `Intl.NumberFormat` is ECMA-402's own implementation of CLDR number formatting — the browser's copy of
 * the very data ICU4J carries on the JVM — so the two `formatNumber` actuals are two implementations of one
 * specification, exactly as the two `formatMessage` actuals are.
 */
public external object Intl {
    public class NumberFormat(
        locales: String,
        options: dynamic,
    ) {
        public fun format(value: Double): String
    }
}
