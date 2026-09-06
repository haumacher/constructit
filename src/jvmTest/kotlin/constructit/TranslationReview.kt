package constructit

import com.ibm.icu.text.MessagePattern
import com.ibm.icu.text.PluralRules
import com.ibm.icu.util.ULocale
import constructit.l10n.formatMessage
import java.io.File

/**
 * **The German review, turned into a machine** (OP-29 slice 4).
 *
 * Slices 1 and 2 each ended with a hand review of the German bundle — 85 corrections, then 366 — and the
 * findings fell into classes rather than being one-offs: a placeholder DeepL renamed, an ICU keyword it
 * translated, an apostrophe that quoted a brace out of existence, the informal `klicke` against the
 * chrome's `Sie`, a term of art rendered three different ways in three keys. A review is a person reading
 * 2,200 sentences once; a *class of error* is something a build can check for ever, on every bundle, on
 * every run. This is that translation — one function per class, each answering the offending keys by name
 * so the message a failure prints is the work list.
 *
 * Two properties every checker here has, and both are what makes it a loop rather than a golden:
 *
 * - it takes the bundles as **arguments**, so `TranslationReviewTest` can hand it a copy with one entry
 *   deliberately broken and prove the check catches that entry. A check nobody has seen fail is a check
 *   nobody knows the shape of;
 * - what it knows about a *language* is data, in `l10n/review/review-<lang>.tsv`, never a list in Kotlin.
 *   A language arrives with its own register words, its own one-word-per-concept decisions and its own
 *   argued exceptions, or it does not pass — which is the whole of "the next language is one line" being
 *   true about the **review** and not only about the translation.
 */
object TranslationReview {
    /** `l10n/app_en.arb` and every translated sibling, English first. */
    fun bundleFiles(): List<File> {
        val dir = File("l10n")
        val files = dir.listFiles { f: File -> f.name.matches(Regex("app_.+\\.arb")) }?.toList().orEmpty()
        return files.sortedBy { if (localeOf(it) == "en") "" else localeOf(it) }
    }

    fun localeOf(file: File): String = file.name.removePrefix("app_").removeSuffix(".arb")

    /**
     * One ARB as `key → pattern`, metadata skipped.
     *
     * The subset this project writes is flat — `"key": "value"` plus a `"@key": { … }` object — so a small
     * scanner is enough and the test source needs no JSON dependency of its own. It reads the *file*
     * rather than the generated table on purpose: the file is what a translator wrote and what a reviewer
     * corrects, and a check that ran on the compiled form could not be handed a broken copy.
     */
    fun read(file: File): Map<String, String> = parse(file.readText())

    fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var depth = 0
        var pendingKey: String? = null
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '{' -> {
                    depth++
                    i++
                }
                '}' -> {
                    depth--
                    i++
                }
                '"' -> {
                    val sb = StringBuilder()
                    var j = i + 1
                    while (j < text.length && text[j] != '"') {
                        if (text[j] == '\\') {
                            sb.append(
                                when (text[j + 1]) {
                                    'n' -> '\n'
                                    't' -> '\t'
                                    'u' -> text.substring(j + 2, j + 6).toInt(16).toChar()
                                    else -> text[j + 1]
                                },
                            )
                            j += if (text[j + 1] == 'u') 6 else 2
                        } else {
                            sb.append(text[j])
                            j++
                        }
                    }
                    i = j + 1
                    if (depth != 1) continue
                    if (pendingKey == null) {
                        pendingKey = sb.toString()
                    } else {
                        if (!pendingKey.startsWith("@")) out[pendingKey] = sb.toString()
                        pendingKey = null
                    }
                }
                ',' -> {
                    if (depth == 1) pendingKey = null
                    i++
                }
                else -> i++
            }
        }
        return out
    }

    // ---- what an ICU pattern is made of, read by ICU4J's own parser rather than by a regex ----

    /**
     * One argument of a pattern: its name, what kind of argument it is, and — for a `plural` or a `select`
     * — the selectors it branches on.
     *
     * [kind] is `ArgType`'s own name (`NONE`, `SIMPLE`, `PLURAL`, `SELECT`, …), which is what makes the
     * translated-keyword check structural: `{count, Plural, ein{…}}` parses as a `SIMPLE` argument of type
     * `Plural`, not as a plural at all, and the comparison against the English says so by name.
     */
    data class Arg(
        val name: String,
        val kind: String,
        val branches: Set<String>,
    )

    /** Every argument of [pattern], nesting included — an empty map for a pattern that binds nothing. */
    fun argsOf(pattern: String): Map<String, Arg> {
        val parsed = MessagePattern(pattern)
        val out = LinkedHashMap<String, Arg>()
        val open = ArrayDeque<String>()
        var i = 0
        while (i < parsed.countParts()) {
            val part = parsed.getPart(i)
            when (part.type) {
                MessagePattern.Part.Type.ARG_START -> {
                    val name = parsed.getSubstring(parsed.getPart(i + 1))
                    // **The same name may open twice**, and the outer opening is the one that matters:
                    // `{count, plural, one{{count} edge} other{{count} edges}}` mentions `count` three
                    // times, once as the plural and twice as a plain number inside its own branches. Merging
                    // rather than overwriting is what keeps this a plural — the first version of this
                    // function reported *no plurals at all in either bundle*, which is the shape a checker
                    // that silently passes actually has.
                    val was = out[name]
                    val kind = if (part.argType.name != "NONE") part.argType.name else was?.kind ?: "NONE"
                    out[name] = Arg(name, kind, was?.branches.orEmpty())
                    open.addLast(name)
                }
                MessagePattern.Part.Type.ARG_LIMIT -> open.removeLastOrNull()
                MessagePattern.Part.Type.ARG_SELECTOR -> {
                    open.lastOrNull()?.let { out[it] = out.getValue(it).plus(parsed.getSubstring(part)) }
                }
                // an explicit `=0` / `=1` branch of a plural, which ICU stores as the number itself
                MessagePattern.Part.Type.ARG_INT, MessagePattern.Part.Type.ARG_DOUBLE -> {
                    open.lastOrNull()?.let { out[it] = out.getValue(it).plus("=" + parsed.getSubstring(part)) }
                }
                else -> {}
            }
            i++
        }
        return out
    }

    private fun Arg.plus(branch: String): Arg = copy(branches = branches + branch)

    /**
     * [argsOf], or null where the pattern is not ICU MessageFormat at all.
     *
     * That is not a hypothetical: a translated keyword is *how* a pattern stops being ICU. DeepL returned
     * `{count, Plural, ein{…} weitere{…}}`, and ICU4J refuses it outright, because a plural with no
     * `other` branch has no total meaning. So an unparseable target is a finding of the keyword class
     * rather than an exception out of the middle of the loop.
     */
    fun argsOrNull(pattern: String): Map<String, Arg>? =
        try {
            argsOf(pattern)
        } catch (_: IllegalArgumentException) {
            null
        }

    /**
     * The plural categories [locale] actually distinguishes, **asked of ICU4J** rather than tabulated.
     *
     * Not `PluralRules.keywords` verbatim: that set names every category the language has anywhere in
     * CLDR, including ones no count this application produces can ever fall into (French's `many` is for
     * compact millions). So the rule is the honest one — run the language's own rules over the counts a
     * drawing can state and keep the categories that come back. German gives `one, other`; Polish would
     * give `one, few, many, other`, and the loop would then demand all four of a Polish bundle without a
     * line of this file changing.
     */
    fun pluralCategories(locale: String): Set<String> =
        categories.getOrPut(locale) {
            val rules = PluralRules.forLocale(ULocale.forLanguageTag(locale))
            (0..10000).mapTo(LinkedHashSet()) { rules.select(it.toDouble()) }
        }

    private val categories = HashMap<String, Set<String>>()

    // ---- the seven classes of error the two hand reviews found ----

    /**
     * **A translation may reword a message; it may not rename the holes in it.** The class that produced
     * `{Grund}` for `{reason}` and `{nom}` for `{name}` — 11 keys on the slice-2 pass.
     */
    fun placeholderDrift(
        english: Map<String, String>,
        target: Map<String, String>,
        locale: String,
    ): List<String> {
        val out = ArrayList<String>()
        for ((key, pattern) in target) {
            val source = english[key] ?: continue
            val want = argsOf(source).keys
            val got = argsOrNull(pattern)?.keys ?: continue
            if (want != got) out.add("$locale/$key binds $got, the English binds $want")
        }
        return out
    }

    /**
     * **An ICU keyword is syntax, not a word.** DeepL translated two patterns' `plural` into `Plural` and
     * their `one`/`other` into `ein`/`weitere`, which is not a plural at all any more. Structural, through
     * ICU4J: the argument that is a `PLURAL` in the English must be a `PLURAL` in the target too.
     */
    fun keywordDrift(
        english: Map<String, String>,
        target: Map<String, String>,
        locale: String,
    ): List<String> {
        val out = ArrayList<String>()
        for ((key, pattern) in target) {
            val source = english[key] ?: continue
            val want = argsOf(source)
            val got = argsOrNull(pattern)
            if (got == null) {
                out.add("$locale/$key is not ICU MessageFormat at all — a keyword was translated: $pattern")
                continue
            }
            for ((name, arg) in want) {
                val mine = got[name] ?: continue
                if (mine.kind != arg.kind) {
                    out.add("$locale/$key: {$name} is a ${arg.kind} in English and a ${mine.kind} here — an ICU keyword was translated")
                }
            }
        }
        return out
    }

    /**
     * **Every branch the source has, and every branch the language has.**
     *
     * A `select` is a closed set of cases the code passes in, so the target's selectors must be *exactly*
     * the source's — a translated branch name silently falls into `other` and the sentence is wrong for
     * one case only, which is the hardest kind of defect to notice. A `plural` is the other way round: the
     * source's explicit `=n` branches must survive, and on top of them the target must carry its **own**
     * language's categories, which English cannot know ([pluralCategories]).
     */
    fun branchDrift(
        english: Map<String, String>,
        target: Map<String, String>,
        locale: String,
    ): List<String> {
        val out = ArrayList<String>()
        val wanted = pluralCategories(locale.substringBefore('-'))
        for ((key, pattern) in target) {
            val source = english[key] ?: continue
            val want = argsOf(source)
            val got = argsOrNull(pattern) ?: continue
            for ((name, arg) in want) {
                val mine = got[name] ?: continue
                if (mine.kind != arg.kind) continue
                when (arg.kind) {
                    "SELECT" ->
                        if (mine.branches != arg.branches) {
                            out.add("$locale/$key: {$name} selects on ${mine.branches}, the English on ${arg.branches}")
                        }
                    "PLURAL", "SELECTORDINAL" -> {
                        val explicit = arg.branches.filter { it.startsWith("=") }
                        val missing = (explicit + wanted).filter { it !in mine.branches }
                        if (missing.isNotEmpty()) {
                            out.add("$locale/$key: the plural {$name} has no ${missing.joinToString(", ")} branch (it has ${mine.branches})")
                        }
                    }
                    else -> {}
                }
            }
        }
        return out
    }

    /**
     * **No brace reaches the reader.** ICU's apostrophe rule is the trap: `'{n}'` does not put `{n}` in
     * quotes, it *quotes the brace*, and the message then renders the literal text `{n}` and binds
     * nothing. Every key rendered with dummy arguments, in every language, and a surviving `{` is the
     * whole test — which also catches a pattern that quotes one occurrence of a placeholder it uses
     * elsewhere, where the argument-set check above sees nothing wrong.
     */
    fun bracesLeft(
        bundle: Map<String, String>,
        locale: String,
    ): List<String> {
        val out = ArrayList<String>()
        for ((key, pattern) in bundle) {
            val args = (argsOrNull(pattern) ?: continue).keys.associateWith { 1 as Any? }
            val text =
                try {
                    if (args.isEmpty()) pattern else formatMessage(locale, pattern, args)
                } catch (e: IllegalArgumentException) {
                    out.add("$locale/$key does not render: $pattern (${e.message})")
                    continue
                }
            if ('{' in text || '}' in text) out.add("$locale/$key renders a brace: $text")
        }
        return out
    }

    /**
     * **The register the chrome settled on.** Slice 1's German chrome addresses the reader as `Sie`;
     * DeepL, given a sentence in the imperative, writes `klicke` about half the time — 96 corrections on
     * the slice-2 pass, every one of them the same decision made again. The words are the language's own
     * ([Review.register]), and an occurrence inside a **quoted example** does not count, because a
     * message that quotes what a user typed is quoting, not addressing.
     */
    fun registerBreaches(
        target: Map<String, String>,
        locale: String,
        review: Review,
    ): List<String> {
        if (review.register.isEmpty()) return listOf("$locale: no register words are declared in ${review.path}")
        val out = ArrayList<String>()
        for ((key, pattern) in target) {
            val text = withoutQuotedExamples(prose(pattern))
            for (word in review.register) {
                if (Regex("(?<![\\p{L}])" + Regex.escape(word) + "(?![\\p{L}])", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                    out.add("$locale/$key addresses the reader as \"$word\": $pattern")
                }
            }
        }
        return out
    }

    /** What a message *quotes* rather than says — an example the reader typed, a name, a key. */
    private fun withoutQuotedExamples(text: String): String =
        text
            .replace(Regex("„[^“]*“"), " ")
            .replace(Regex("“[^”]*”"), " ")
            .replace(Regex("«[^»]*»"), " ")
            .replace(Regex("\"[^\"]*\""), " ")
            .replace(Regex("'[^']*'"), " ")

    /**
     * **A term of art is the same word every time it is used.** The glossary DeepL is given (`en-<lang>`)
     * states the pair; this asserts it *arrived* — for every key whose English says the term, the target
     * must say the term's own word. 190 corrections came out of this class by hand on the slice-2 pass.
     *
     * What counts as "the word" is the review's own list of **surface forms** ([Concept.approved]), not the
     * glossary's lemma, and that is not a loophole but the thing an inflected language makes necessary:
     * German renders *extrude* as *Extrusion*, *extrudiert* and *Extrudiertiefe*, and a checker that
     * demanded the infinitive would be measuring grammar rather than terminology. The list is short — a
     * stem is usually one entry — and it is *data a reviewer edits*, which is what keeps the rule
     * inspectable.
     *
     * The exceptions are the record of the arguments the review made for them: a key is listed with the
     * term it is excused from and the reason, in `l10n/review/review-<lang>.tsv`, and an exception nobody
     * can state is a correction waiting to be made.
     */
    fun terminologyBreaches(
        english: Map<String, String>,
        target: Map<String, String>,
        locale: String,
        review: Review,
    ): List<String> {
        if (review.concepts.isEmpty()) return listOf("$locale: no terminology decisions are recorded in ${review.path}")
        val out = ArrayList<String>()
        for ((key, pattern) in target) {
            val source = english[key] ?: continue
            for (concept in review.concepts) {
                if (!mentions(source, concept.term)) continue
                if (review.excused(key, concept.term)) continue
                if (concept.approved.none { prose(pattern).contains(it, ignoreCase = true) }) {
                    out.add("$locale/$key says \"${concept.term}\" and not ${concept.approved.joinToString("/")}: $pattern")
                }
            }
        }
        return out
    }

    /**
     * **The glossary and the review table say the same thing.** The glossary is what DeepL is given; the
     * table is what the build asserts arrived. If a term of art can be pinned for the translator it can be
     * checked for afterwards, so every line of `en-<lang>.tsv` must have a concept line here — otherwise
     * the two records drift and the second one silently stops covering the first.
     */
    fun glossaryDrift(
        locale: String,
        review: Review,
    ): List<String> {
        val covered = review.concepts.map { it.term.lowercase() }.toSet()
        return review.glossary
            .map { it.first }
            .filter { it.lowercase() !in covered }
            .map { "$locale: the glossary pins \"$it\" and ${review.path} does not say what it must read as" }
    }

    /**
     * **One word per concept.** The slice-2 review swept a dozen concepts the machine had rendered two or
     * three ways — *solid* as both *Volumenkörper* and *Körper*, *rail* as *Leitkurve* and *Schiene* — and
     * the value of that sweep is not the 190 edits it made but the invariant it established. So the
     * decisions are data: each concept names the one word that was chosen **and the ones that were
     * rejected**, and a rejected word anywhere in a key that is about that concept fails the build. That
     * is the *set of renderings has size one* assertion, stated in the form a reviewer can add to.
     */
    fun conceptBreaches(
        english: Map<String, String>,
        target: Map<String, String>,
        locale: String,
        review: Review,
    ): List<String> {
        if (review.concepts.isEmpty()) return listOf("$locale: no one-word-per-concept decisions are recorded in ${review.path}")
        val out = ArrayList<String>()
        for ((key, pattern) in target) {
            val source = english[key] ?: continue
            for (concept in review.concepts) {
                if (concept.rejected.isEmpty()) continue
                if (!mentions(source, concept.term)) continue
                if (review.excused(key, concept.term)) continue
                val used = concept.rejected.filter { usesWord(prose(pattern), it) }
                if (used.isNotEmpty()) {
                    out.add("$locale/$key renders \"${concept.term}\" as ${used.joinToString(", ")} where the review settled on ${concept.approved.joinToString("/")}: $pattern")
                }
            }
        }
        return out
    }

    /**
     * Whether [text] uses [form] — how a **rejected** rendering is looked for, and deliberately not how an
     * approved one is.
     *
     * An *approved* form is a plain substring, because German buries the term inside a compound
     * (*Kreisbogen* carries *Bogen* in its middle) and a missed hit there would be a false alarm about a
     * translation that is right. A *rejected* one is matched as a **whole word**, because the rejected
     * rendering is usually a prefix or a relative of the approved one — *Rundung* inside *Verrundung*,
     * *Kehle* inside *Hohlkehle*, *Wisch* inside *zwischen* — and a plain substring there would condemn
     * the very word the review chose. Where the rejected word does compound or inflect, the reviewer says
     * so with a trailing `*`: `Abrundung*` catches *Abrundungsband*, and French's bare `arrondi` leaves
     * the ordinary adjective *arrondies* alone while catching the noun.
     */
    fun usesWord(
        text: String,
        form: String,
    ): Boolean =
        words.getOrPut("!" + form) {
            val stem = form.removeSuffix("*")
            val tail = if (form.endsWith("*")) "" else "(?![\\p{L}])"
            Regex("(?<![\\p{L}])" + Regex.escape(stem) + tail, RegexOption.IGNORE_CASE)
        }.containsMatchIn(text)

    /**
     * Whether [text] uses [term] as a word — the English side of a glossary line, never a substring, and
     * never a piece of **ICU syntax**: an argument called `{upright}` and a `select` branch called
     * `chamfer{…}` are the code's own vocabulary, not a sentence saying the word, so they are cut away
     * before the question is asked.
     */
    fun mentions(
        text: String,
        term: String,
    ): Boolean = word(term).containsMatchIn(prose(text))

    private val words = HashMap<String, Regex>()

    private fun word(term: String): Regex =
        words.getOrPut(term) {
            Regex("(?<![\\p{L}])" + Regex.escape(term) + "(?![\\p{L}])", RegexOption.IGNORE_CASE)
        }

    /**
     * What a pattern actually **says** — its literal text, with every argument name, argument type and
     * branch selector blanked out. Parsed by ICU4J rather than by a regular expression, and that is not
     * fastidiousness: `{upright}` is an argument named after the thing it holds and `DRESSING{…}` is a
     * `select` branch named after an enum constant, so both must go — while `FILLET{Verrundung}` is a
     * branch whose *body* is one word and looks character-for-character like a placeholder. Nothing short
     * of the real grammar tells those two apart, and the first two attempts at this by regex each got one
     * of them wrong in a way that reported a dozen perfectly good translations.
     */
    fun prose(pattern: String): String {
        val parsed =
            try {
                MessagePattern(pattern)
            } catch (_: IllegalArgumentException) {
                return pattern
            }
        val out = StringBuilder(pattern)
        for (i in 0 until parsed.countParts()) {
            val part = parsed.getPart(i)
            when (part.type) {
                MessagePattern.Part.Type.ARG_NAME,
                MessagePattern.Part.Type.ARG_NUMBER,
                MessagePattern.Part.Type.ARG_TYPE,
                MessagePattern.Part.Type.ARG_SELECTOR,
                ->
                    for (c in part.index until minOf(part.index + part.length, out.length)) out[c] = ' '
                else -> {}
            }
        }
        return out.toString()
    }

    // ---- the review's own data, one file per language ----

    /**
     * One `concept` line: the English term of art, every surface form the review accepts for it in this
     * language, and the renderings it rejected — the two halves of *one word per concept*, as data.
     */
    data class Concept(
        val term: String,
        val approved: List<String>,
        val rejected: List<String>,
    )

    /**
     * What a review knows about one language, read from `l10n/review/review-<lang>.tsv`.
     *
     * Three record kinds, one per line, tab-separated, `#` comments and blank lines ignored:
     *
     * ```
     * register  klicke                                  why the informal form is wrong here
     * concept   solid     Volumenkörper                 Körper|Festkörper
     * concept   extrude   Extrusion|extrudier           Auspress|Strangpress
     * except    tool.x.title  wall                      why this key may say something else
     * ```
     *
     * A `concept`'s third cell is every surface form that counts as the term arriving (a stem is usually
     * enough), and its fourth is every rendering the review rejected. An `except` excuses **one key** from
     * **one term**, in both directions, and its last cell is the argument for doing so.
     */
    class Review(
        val locale: String,
        val path: String,
        val register: List<String>,
        val concepts: List<Concept>,
        private val exceptions: Set<Pair<String, String>>,
        val glossary: List<Pair<String, String>>,
    ) {
        fun excused(
            key: String,
            term: String,
        ): Boolean = (key to term.lowercase()) in exceptions
    }

    fun reviewOf(locale: String): Review {
        val file = File("l10n/review/review-$locale.tsv")
        val register = ArrayList<String>()
        val concepts = ArrayList<Concept>()
        val exceptions = HashSet<Pair<String, String>>()
        if (file.exists()) {
            for (raw in file.readLines()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val cells = line.split('\t').map { it.trim() }.filter { it.isNotEmpty() }
                when (cells.firstOrNull()) {
                    "register" -> register.add(cells[1])
                    "concept" ->
                        concepts.add(
                            Concept(
                                cells[1],
                                cells[2].split('|').filter { it.isNotBlank() },
                                cells.getOrNull(3)?.split('|').orEmpty().filter { it.isNotBlank() },
                            ),
                        )
                    "except" -> exceptions.add(cells[1] to cells[2].lowercase())
                    else -> throw AssertionError("${file.path}: unknown record kind in '$raw'")
                }
            }
        }
        return Review(locale, file.path, register, concepts, exceptions, glossaryOf(locale))
    }

    /** The DeepL glossary for `en-<lang>`, which is also the terminology the review asserts arrived. */
    fun glossaryOf(locale: String): List<Pair<String, String>> {
        val file = File("l10n/glossary/en-$locale.tsv")
        if (!file.exists()) return emptyList()
        return file
            .readLines()
            .mapNotNull { line ->
                val cells = line.split('\t')
                if (cells.size < 2 || cells[0].isBlank() || line.startsWith("#")) null else cells[0].trim() to cells[1].trim()
            }
    }
}
