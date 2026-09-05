package constructit.l10n

/**
 * In the browser a language arrives as a **chunk** and not before (OP-29 slice 3): `Main.kt` adds
 * `l10n/<lang>.js` to the page when the reader picks that language and calls [Bundle.install] with what it
 * assigned. Until then this answers `null`, every key falls back to English, and nothing blocks.
 */
internal actual fun bundledPatterns(locale: String): Map<String, String>? = null
