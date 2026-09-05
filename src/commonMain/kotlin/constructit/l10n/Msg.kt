package constructit.l10n

/**
 * **A message is a value** (OP-29, slice 2): a [key] into the bundles plus the [args] that fill its holes,
 * and *not* a sentence. It becomes a sentence only when something at the edge renders it, in whatever
 * language is active then.
 *
 * This is what lets the engine keep OP-3's promise — *refusals speak: no route may decline silently; name
 * the element and the alternative* — without knowing a word of any language. `geom/` builds a `Msg`,
 * `EvalResult.Invalid` carries one, `Document.noteMsg` and `Editor.statusMsg` hold one, and the shell asks
 * for its text. A drawing loaded in a German session says its load notes in German; the very same document
 * object, read after the language picker moves, says them in English. Nothing is re-computed for that,
 * because nothing was ever a sentence.
 *
 * Three shapes, one class, because a message argument is itself a message and a class hierarchy here would
 * buy nothing:
 *
 * - **a bundle message** — [key] and [args], the ordinary case, rendered through [Messages] and
 *   [formatMessage] (ICU4J on the JVM, `intl-messageformat` in the browser);
 * - **literal text** ([text]) — an element's name (`e14`), a number the caller already formatted, a
 *   user-typed macro name, an exception's own message. Locale-neutral by OP-18: it is *format*, not UI,
 *   and it must survive a language switch unchanged;
 * - **a joined list** ([joined]) — several notes on one line. The separator is punctuation (`" · "`), not
 *   prose, and each part renders on its own.
 *
 * **[toString] renders**, in [L10n.locale], and that is a deliberate choice rather than an oversight. The
 * alternative — `toString` giving the key back — is only "safe" for code that compares keys, and this
 * repository has none; what it would actually do is put `refusal.blend.tooSharp` on a user's screen the
 * moment any surviving `"…$msg…"` composed a message into a sentence. Rendering makes that same composition
 * *correct in English and correct in German*, which is what made this slice possible to land in areas: a
 * refusal converted in `geom/` reads exactly as it did while the note in `Document` that quotes it is still
 * a string, and the note converts later without the refusal being touched again. The guard against relying
 * on it for ever is `MessageValueTest` plus `EngineBundleTest.noEnglishSentenceIsWrittenIntoTheEngine`,
 * which fails the build on a sentence left in Kotlin.
 */
public class Msg private constructor(
    public val key: String,
    public val args: Map<String, Any?>,
    private val literal: String?,
    private val parts: List<Msg>?,
    private val separator: String,
) {
    /** A bundle message: [key] into `app_<lang>.arb`, [args] filling its declared placeholders. */
    public constructor(key: String, args: Map<String, Any?> = emptyMap()) : this(key, args, null, null, "")

    /** True for [text] — locale-neutral text that renders as itself whatever the language (OP-18). */
    public val isLiteral: Boolean get() = literal != null

    /**
     * This message as a sentence in [locale] — the one operation the edge performs.
     *
     * A message argument is rendered first, in the same locale, which is how *"the face over boundary edge
     * #3 is not a plane"* is one German sentence with one German name inside it rather than three fragments.
     */
    public fun render(locale: String = L10n.locale): String {
        literal?.let { return it }
        parts?.let { list -> return list.joinToString(separator) { it.render(locale) } }
        val pattern = Messages.text(key, locale)
        if (args.isEmpty()) return pattern
        return formatMessage(locale, pattern, args.mapValues { renderArg(it.value, locale) })
    }

    /** Rendered in the active language — see the class note on why this renders rather than naming the key. */
    override fun toString(): String = render()

    /**
     * This message with every occurrence of the message [from] among its arguments replaced by [to].
     *
     * What a string-substitution would have done, done **structurally** — and therefore in every language at
     * once. The shell restates the cavity's own refusal in the shell's words (`Section3.cutFace`): the
     * sentence is the cavity's, but the *name* in it must be the shell's face, and that name is an argument,
     * not a substring. A `replace()` on rendered English would have worked only in English.
     */
    public fun substituting(
        from: Msg,
        to: Msg,
    ): Msg {
        if (this == from) return to
        val newParts = parts?.map { it.substituting(from, to) }
        val newArgs = args.mapValues { (_, v) -> if (v is Msg) v.substituting(from, to) else v }
        return Msg(key, newArgs, literal, newParts, separator)
    }

    /** `"…" in msg`: the substring test every gesture test in this repository is written with. */
    public operator fun contains(other: CharSequence): Boolean = render().contains(other)

    override fun equals(other: Any?): Boolean =
        other is Msg &&
            key == other.key &&
            literal == other.literal &&
            separator == other.separator &&
            parts == other.parts &&
            args == other.args

    override fun hashCode(): Int {
        var h = key.hashCode()
        h = 31 * h + (literal?.hashCode() ?: 0)
        h = 31 * h + (parts?.hashCode() ?: 0)
        h = 31 * h + args.hashCode()
        return h
    }

    public companion object {
        /** The empty message — renders to `""` in every language. */
        public val EMPTY: Msg = Msg("", emptyMap(), "", null, "")

        /**
         * Locale-neutral [text]: an element's name, a formatted number, a name the user typed, the message
         * of an exception. It is *format* and never UI (OP-18), so it reads the same in every language.
         */
        public fun text(text: String): Msg = Msg("", emptyMap(), text, null, "")

        /**
         * A list a sentence reads out: *"edge #1, edge #2 and edge #3"*. The commas are punctuation; the
         * final *and* is a word, so it comes out of the bundle (`list.and`) and a language that puts it
         * elsewhere can move it.
         */
        public fun andList(parts: List<Msg>): Msg =
            when (parts.size) {
                0 -> EMPTY
                1 -> parts[0]
                else -> Msgs.listAnd(head = joined(parts.dropLast(1), ", "), last = parts.last())
            }

        /** Several messages on one line, [separator] being punctuation rather than prose. */
        public fun joined(
            parts: List<Msg>,
            separator: String = " · ",
        ): Msg =
            when (parts.size) {
                0 -> EMPTY
                1 -> parts[0]
                else -> Msg("", emptyMap(), null, parts.toList(), separator)
            }
    }
}

/**
 * An exception whose text is a **message value** (OP-29 slice 2).
 *
 * The evaluator turns a thrown exception into `EvalResult.Invalid` (OP-3), and a refusal that travelled that
 * way used to arrive as an English string. It now arrives as a [Msg]: `Evaluator` unwraps this and keeps the
 * value, so a dimension refusal reads in the reader's language exactly as a returned one does.
 */
public open class MsgError(
    public val why: Msg,
) : RuntimeException(why.render())

/**
 * `"…" in msg` where the message may be absent — a refusal that is null says nothing, and nothing contains
 * nothing. An extension on the nullable type, so a gesture test written before OP-29 reads unchanged.
 */
public operator fun Msg?.contains(other: CharSequence): Boolean = this != null && other in this.render()

/**
 * One argument, ready for [formatMessage]: a nested [Msg] becomes its own sentence in the same [locale],
 * everything else is handed to the formatter as it stands (a number stays a number, so a plural can count
 * it).
 */
public fun renderArg(
    value: Any?,
    locale: String,
): Any? =
    when (value) {
        is Msg -> value.render(locale)
        else -> value
    }
