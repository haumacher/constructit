package constructit.l10n

/**
 * On the JVM every language is compiled in (OP-29 slice 3): a headless test renders German and English in
 * one expression, so nothing here may be asynchronous and the 231 KB of German costs a test run nothing.
 * The browser's half of the same seam answers `null` and waits for its chunk.
 */
internal actual fun bundledPatterns(locale: String): Map<String, String>? = MessageTables.of(locale)
