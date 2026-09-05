package constructit.l10n

/**
 * Write [value] with at most [fractionDigits] decimals, **in [locale]'s own notation** (OP-29 slice 3).
 *
 * The reference engines do this, exactly as they do the messages: the JVM actual is ICU4J's `NumberFormat`
 * and the browser actual is the platform's own `Intl.NumberFormat`. There is no number formatter in this
 * repository, which is the same promise `formatMessage` makes one layer up — a decimal comma is not the
 * only thing a language changes about a number, and the moment a third language arrives (Indian digit
 * grouping, Arabic-Indic digits) a hand-written rule would be wrong in a way nobody here could review.
 *
 * Two things it is asked to *not* do, because both would change what English already says:
 *
 * - **no grouping**: `12345.5` stays `12345.5`, never `12,345.5`. The drawing's own figures are read
 *   against the file and against each other, and this is what keeps every existing English assertion in
 *   the suite reading the same sentence it read before the slice;
 * - **no padding**: [fractionDigits] is a *maximum*, so `5` stays `5` rather than becoming `5.000`.
 *
 * Rounding is therefore never this function's job: what reaches it has already been rounded by
 * `Format.num`'s platform-independent rule, and [fractionDigits] is the number of decimals that rule
 * actually produced. The locale changes the separator, never the precision.
 */
public expect fun formatNumber(
    locale: String,
    value: Double,
    fractionDigits: Int,
): String

/**
 * The character [locale] writes a decimal point with — asked of the **engine itself** rather than tabulated.
 *
 * `formatNumber(locale, 1.5, 1)` is `1.5`, `1,5` or `١٫٥`; whichever it is, the one character in it that is
 * not a digit is that locale's decimal separator. So the same reference implementation that writes a number
 * is what says how to read one back, and there is no table of separators in this repository to fall behind
 * CLDR.
 */
public fun decimalSeparator(locale: String): Char =
    separators.getOrPut(locale) {
        formatNumber(locale, 1.5, 1).firstOrNull { !it.isDigit() } ?: '.'
    }

private val separators = HashMap<String, Char>()

/**
 * **Read a decimal number this application itself wrote**, given the separator it wrote it with — the one
 * place OP-29 states a rule instead of calling a library, and the reason is that there is no library to
 * call.
 *
 * `Intl.NumberFormat` and ICU4J's `NumberFormat` both *write*; only ICU4J parses, and it exists on the JVM
 * alone, so the browser — where the typing actually happens — has no reference parser at all. Rather than
 * have the two platforms disagree about what a reader typed, both use this, and it is deliberately the
 * narrowest rule that closes the loop the panel opens:
 *
 * - the character [decimalSeparator] (this locale's, from the engine above) becomes a `.`;
 * - any *other* `.` or `,` makes the text **not a number** — so a German session refuses `1.234`, which is
 *   the one input that is genuinely ambiguous (1234, or 1.234?). The writer never emits a grouping
 *   separator, so nothing this application produced is ever refused by this rule;
 * - everything else — sign, digits, an exponent — is handed to Kotlin's own `toDoubleOrNull`.
 *
 * It is the exact inverse of [formatNumber] as this repository asks for it, and [Num] uses it in the other
 * direction too: to re-read the figure a refusal states in the file's spelling (`.`) before respelling it
 * in the reader's. One rule, both directions, both platforms.
 */
public fun readDecimal(
    text: String,
    decimalSeparator: Char = '.',
): Double? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val canonical = StringBuilder(trimmed.length)
    for (c in trimmed) {
        when {
            c == decimalSeparator -> canonical.append('.')
            c == '.' || c == ',' -> return null
            else -> canonical.append(c)
        }
    }
    return canonical.toString().toDoubleOrNull()
}

/**
 * A **measured figure inside a message** (OP-29 slice 3): a length in millimetres, an angle in degrees, a
 * factor — carried as a value and spelled at the edge, exactly as the sentence around it is.
 *
 * Slice 2 left every such figure a pre-formatted `String`, which is why `{closingGap}` read `0.3` in a
 * German session as readily as in an English one. A `Num` closes that: it holds the figure in the **file's
 * own spelling** — a decimal point, the rounding `Format.num`/`Frames3.mm` already applied (OP-18) — and
 * [render]s it through the reader's own `NumberFormat`.
 *
 * Holding the canonical *text* rather than a bare `Double` is what makes the precision rule survive the
 * move: the digits are the ones the engine chose (three decimals, trailing zeros dropped), and the locale
 * changes only how they are punctuated. It also means nothing at a call site had to move — the engine goes
 * on stating figures the way it always did, and it is the generated factory that turns one into a value.
 */
public class Num private constructor(
    /** The figure as the file would spell it: a decimal point, no grouping (OP-18). */
    public val canonical: String,
    private val value: Double?,
    private val fractionDigits: Int,
) {
    /** This figure in [locale]'s notation — `0.3` in English, `0,3` in German, off the one value. */
    public fun render(locale: String): String = if (value == null) canonical else formatNumber(locale, value, fractionDigits)

    override fun toString(): String = render(L10n.locale)

    override fun equals(other: Any?): Boolean = other is Num && canonical == other.canonical

    override fun hashCode(): Int = canonical.hashCode()

    public companion object {
        /**
         * The figure [canonical] states, as a value. Text that is not a number at all — a `"?"` where a
         * size is unknown — is carried through untouched rather than refused, because a message argument
         * must never be the thing that fails.
         */
        public fun of(canonical: String): Num {
            val value = readDecimal(canonical)
            val point = canonical.indexOf('.')
            val digits = if (point < 0) 0 else canonical.length - point - 1
            return Num(canonical, value, digits)
        }
    }
}
