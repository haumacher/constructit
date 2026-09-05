package constructit.l10n

/**
 * **The text of one language, loaded when that language is read** (OP-29 slice 3).
 *
 * Slice 2 compiled every language into one table, and the measurement said what that costs: the production
 * bundle grew 84.6 KB gzipped, all of it German patterns that an English session downloads, parses and
 * never looks at. The split is by *target*, because the two targets want opposite things —
 *
 * - **English** stays compiled into `Messages`. It is the fall-back for every key a language does not
 *   carry, and it is what the first paint renders, so it cannot be behind a load;
 * - **every other language** is a chunk: on the JVM a compiled table (`bundledPatterns` hands it over
 *   synchronously, because a headless test renders `de` and `en` in the same expression and nothing there
 *   may wait), in the browser the script `l10n/<lang>.js` that the shell adds to the page the moment the
 *   reader picks that language, and never before.
 *
 * A language whose chunk is not here yet is not an error and never blocks: [patterns] answers `null`, every
 * key falls back to English, and the shell repaints when [install] arrives. That is the whole of the
 * browser's loading story, and it is why the switch cannot leave the page half-translated — one call,
 * one repaint.
 */
public object Bundle {
    private val loaded = HashMap<String, Map<String, String>>()
    private val absent = HashSet<String>()

    /** [locale]'s patterns, or null while its chunk is not here — the caller then falls back to English. */
    public fun patterns(locale: String): Map<String, String>? {
        loaded[locale]?.let { return it }
        if (locale in absent) return null
        val table = bundledPatterns(locale)
        if (table == null) {
            absent += locale
            return null
        }
        loaded[locale] = table
        return table
    }

    /** A chunk has arrived: from here on [locale] reads its own text. Called by the shell, once per language. */
    public fun install(
        locale: String,
        patterns: Map<String, String>,
    ) {
        loaded[locale] = patterns
        absent -= locale
    }

    /** Whether [locale] can be rendered right now — English always can, another language once its chunk is in. */
    public fun isLoaded(locale: String): Boolean = locale == "en" || locale in loaded || patterns(locale) != null
}

/**
 * The platform's own answer to *"do you already have this language?"* — the compiled table on the JVM, and
 * `null` in the browser, where a language arrives through [Bundle.install] and not before.
 */
internal expect fun bundledPatterns(locale: String): Map<String, String>?
