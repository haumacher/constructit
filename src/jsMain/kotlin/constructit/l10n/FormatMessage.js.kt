package constructit.l10n

/**
 * Compiled patterns, by locale and pattern (OP-29): a plural in the element tree is re-rendered on every
 * repaint, and parsing an ICU pattern is the expensive half of formatting one. Bounded by the number of
 * distinct messages the bundle carries, which is a few hundred.
 */
private val compiled = HashMap<String, IntlMessageFormat>()

/**
 * `intl-messageformat` (OP-29). The browser half of the [formatMessage] seam — see the expect declaration
 * in `commonMain` for why formatting is a reference library's job and not this repository's.
 */
public actual fun formatMessage(
    locale: String,
    pattern: String,
    args: Map<String, Any?>,
): String {
    val format = compiled.getOrPut(locale + " " + pattern) { IntlMessageFormat(pattern, locale) }
    val values: dynamic = js("({})")
    for ((name, value) in args) values[name] = value
    return format.format(values)
}
