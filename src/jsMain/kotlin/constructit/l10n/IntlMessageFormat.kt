@file:JsModule("intl-messageformat")
@file:JsNonModule
@file:Suppress("ktlint:standard:filename")

package constructit.l10n

/**
 * FormatJS's `intl-messageformat` (OP-29) — the browser half of the [formatMessage] seam.
 *
 * Declared by hand, as the three.js surface beside it is and for the same reason: one class and one method
 * is the whole of what the shell calls, so an upstream rename breaks the build rather than the page. It is
 * a **named export** of the package, which is what the file-level `@JsModule` states.
 *
 * ICU MessageFormat is one syntax with two reference implementations, and this is the second: the pattern a
 * plural or a select is written with in `app_en.arb` is read here exactly as ICU4J reads it in the tests.
 */
@JsName("IntlMessageFormat")
external class IntlMessageFormat(
    message: String,
    locales: String,
) {
    fun format(values: dynamic): String
}
