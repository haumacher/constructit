package constructit.l10n

/**
 * The **active language** (OP-29), and nothing else.
 *
 * A message is a value with a key and arguments; it becomes a sentence only at the edge, and this is the
 * one fact that edge needs. The shell writes it — from `navigator.language` on the first load, from
 * `localStorage` afterwards, from the language picker when the user says otherwise — and every generated
 * accessor in [Messages] reads it as its default argument. Tests and the JVM leave it at `en`, so every
 * existing substring assertion still reads the English bundle.
 *
 * Deliberately a plain mutable global rather than a parameter threaded through the editor: the alternative
 * is a locale argument on several hundred call sites for a value that changes at most once in a session,
 * and the generated accessors all take an explicit `locale` anyway — which is what a test that renders two
 * languages side by side uses (`Messages.toolPointTitle("de")`).
 */
public object L10n {
    /**
     * The language the chrome renders in — a BCP-47 tag, or just its language subtag. A tag no bundle
     * carries falls back to its language, and then to English ([Messages.indexOf]).
     */
    public var locale: String = "en"
}

/**
 * Render one ICU MessageFormat [pattern] in [locale] with [args].
 *
 * **The reference engines do this, not us** (OP-29): the JVM actual is ICU4J's `MessageFormat` — the same
 * code Flutter's and Android's tooling are checked against — and the browser actual is FormatJS's
 * `intl-messageformat`. Both read the same syntax, so a plural or a select written once in the ARB reads
 * the same in a headless test and in the shell; `MessageFormatTest` and the browser E2E assert exactly that.
 */
public expect fun formatMessage(
    locale: String,
    pattern: String,
    args: Map<String, Any?>,
): String
