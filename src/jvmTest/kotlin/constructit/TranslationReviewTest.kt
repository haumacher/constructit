package constructit

import constructit.l10n.Messages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The review loop, run** (OP-29 slice 4) — every class of error the two German hand reviews found,
 * checked against every bundle in `l10n/`, on every build.
 *
 * Each test here is two assertions rather than one, and the second is the point. The first says the
 * shipped bundles are clean, which is what a golden says and which tells you nothing about the checker:
 * a check that always passes and a check that cannot fail look identical from outside. So the second
 * takes the very same bundle, **breaks one entry the way the review saw it broken** — renames a
 * placeholder, translates a plural keyword, quotes a brace out of existence, writes `klicke` at a reader
 * the chrome addresses as `Sie` — and requires the checker to name *that key*. The failure message a
 * reviewer will one day read is therefore a thing this test has already read.
 *
 * What each language brings with it is data ([TranslationReview.Review]): `l10n/review/review-<lang>.tsv`
 * carries its register words, its one-word-per-concept decisions and the argued exceptions, and
 * `l10n/glossary/en-<lang>.tsv` carries the terms DeepL is given. A new language that arrives without
 * them does not quietly skip the review — the checks say so by name.
 */
class TranslationReviewTest {
    private val english = TranslationReview.read(TranslationReview.bundleFiles().first { TranslationReview.localeOf(it) == "en" })

    private val targets: List<Pair<String, Map<String, String>>> =
        TranslationReview
            .bundleFiles()
            .filter { TranslationReview.localeOf(it) != "en" }
            .map { TranslationReview.localeOf(it) to TranslationReview.read(it) }

    private fun eachTarget(check: (String, Map<String, String>) -> List<String>) {
        assertTrue(targets.isNotEmpty(), "expected at least one translated bundle beside l10n/app_en.arb")
        for ((locale, bundle) in targets) assertEquals(emptyList(), check(locale, bundle), "$locale is reviewed")
    }

    /** [bundle] with one entry rewritten — the scratch copy every check below is proved against. */
    private fun broken(
        bundle: Map<String, String>,
        key: String,
        spoil: (String) -> String,
    ): Map<String, String> {
        val was = bundle[key] ?: throw AssertionError("no such key to break: $key")
        val now = spoil(was)
        assertTrue(now != was, "the seeded defect must actually change $key")
        return bundle + (key to now)
    }

    private fun caught(
        found: List<String>,
        key: String,
    ) {
        assertTrue(found.any { key in it }, "the seeded defect in $key was not caught; found $found")
    }

    /** The first language with a plural to break, and the key of that plural. */
    private fun aPluralKey(bundle: Map<String, String>): String =
        bundle.keys.first { key ->
            english[key]?.let { TranslationReview.argsOf(it).values.any { a -> a.kind == "PLURAL" } } == true &&
                TranslationReview.argsOf(bundle.getValue(key)).values.any { a -> a.kind == "PLURAL" }
        }

    private fun aSelectKey(bundle: Map<String, String>): String =
        bundle.keys.first { key ->
            english[key]?.let { TranslationReview.argsOf(it).values.any { a -> a.kind == "SELECT" } } == true &&
                TranslationReview.argsOf(bundle.getValue(key)).values.any { a -> a.kind == "SELECT" }
        }

    private fun anArgumentKey(bundle: Map<String, String>): String =
        bundle.keys.first { key ->
            english[key]?.let { TranslationReview.argsOf(it).size == 1 && TranslationReview.argsOf(it).values.first().kind == "NONE" } == true &&
                TranslationReview.argsOf(bundle.getValue(key)).size == 1
        }

    /**
     * **Every bundle in the folder is one the generator read.**
     *
     * The generator carries the placeholder guard (`:generateMessages` fails the build on a target that
     * renamed a hole), and a guard is only worth the set it runs over. That set is `l10n/app_*.arb` as a
     * file tree, and *this* is the assertion that the set has not silently shrunk to the languages someone
     * remembered: what the folder holds and what `Messages.locales` names are the same list.
     */
    @Test
    fun everyBundleInTheFolderIsOneTheGeneratorRead() {
        val onDisk = TranslationReview.bundleFiles().map { TranslationReview.localeOf(it) }.sorted()
        assertEquals(onDisk, Messages.locales.sorted(), "the generator reads every ARB in l10n/, and only those")
    }

    /** …and every language brings the review data the checks below are made of. */
    @Test
    fun everyLanguageBringsItsOwnReview() {
        for ((locale, _) in targets) {
            val review = TranslationReview.reviewOf(locale)
            assertTrue(review.register.isNotEmpty(), "$locale declares no register words in ${review.path}")
            assertTrue(review.concepts.isNotEmpty(), "$locale records no terminology decisions in ${review.path}")
            assertTrue(review.glossary.isNotEmpty(), "$locale has no l10n/glossary/en-$locale.tsv")
            assertEquals(emptyList(), TranslationReview.glossaryDrift(locale, review), "$locale: glossary and review agree")
        }
    }

    @Test
    fun noTranslationRenamesAPlaceholder() {
        eachTarget { locale, bundle -> TranslationReview.placeholderDrift(english, bundle, locale) }
        val (locale, bundle) = targets.first()
        val key = anArgumentKey(bundle)
        val name = TranslationReview.argsOf(bundle.getValue(key)).keys.first()
        // what DeepL did to `{reason}` on the first German pass: translated the hole, not the words
        val seeded = broken(bundle, key) { it.replace("{$name", "{Grund") }
        caught(TranslationReview.placeholderDrift(english, seeded, locale), key)
    }

    @Test
    fun noTranslationTranslatesAnIcuKeyword() {
        eachTarget { locale, bundle -> TranslationReview.keywordDrift(english, bundle, locale) }
        val (locale, bundle) = targets.first()
        val key = aPluralKey(bundle)
        // two patterns came back as `{count, Plural, ein{…} weitere{…}}`, which is not a plural at all:
        // ICU reads the type case-insensitively, so what actually broke was the *branch* keywords, and the
        // pattern then stops parsing altogether. Both halves are this class of error, and this seeds the
        // one that is otherwise an exception rather than a finding.
        val seeded = broken(bundle, key) { it.replace(Regex("(?<![\\p{L}])(one|other)\\s*\\{"), "einzahl{") }
        caught(TranslationReview.keywordDrift(english, seeded, locale), key)
    }

    @Test
    fun everyPluralCarriesItsOwnLanguagesCategories() {
        eachTarget { locale, bundle -> TranslationReview.branchDrift(english, bundle, locale) }
        val (locale, bundle) = targets.first()
        val key = aPluralKey(bundle)
        // German distinguishes `one` from `other`, so a plural that has lost `one` is broken *for German*
        assertTrue("one" in TranslationReview.pluralCategories(locale), "$locale distinguishes a singular")
        val seeded = broken(bundle, key) { it.replace(Regex("one\\s*\\{"), "ein{") }
        caught(TranslationReview.branchDrift(english, seeded, locale), key)
    }

    @Test
    fun everySelectKeepsTheBranchesTheCodePassesIn() {
        val (locale, bundle) = targets.first()
        val key = aSelectKey(bundle)
        val branch = TranslationReview.argsOf(bundle.getValue(key)).values.first { it.kind == "SELECT" }.branches.first { it != "other" }
        // `one{it}` left in English, `top{Nach oben}` for the top *face*: a renamed branch is never chosen
        val seeded = broken(bundle, key) { it.replace(Regex("(?<![\\p{L}])" + Regex.escape(branch) + "\\s*\\{"), "übersetzt{") }
        caught(TranslationReview.branchDrift(english, seeded, locale), key)
    }

    @Test
    fun nothingRendersABraceAtTheReader() {
        eachTarget { locale, bundle -> TranslationReview.bracesLeft(bundle, locale) }
        val (locale, bundle) = targets.first()
        val key = anArgumentKey(bundle)
        val name = TranslationReview.argsOf(bundle.getValue(key)).keys.first()
        // the apostrophe trap: `'{n}'` quotes the *brace*, so the reader is shown the text `{n}`
        val seeded = broken(bundle, key) { it.replace("{$name}", "'{$name}'") }
        caught(TranslationReview.bracesLeft(seeded, locale), key)
    }

    @Test
    fun nothingAddressesTheReaderInTheWrongRegister() {
        eachTarget { locale, bundle -> TranslationReview.registerBreaches(bundle, locale, TranslationReview.reviewOf(locale)) }
        val (locale, bundle) = targets.first()
        val review = TranslationReview.reviewOf(locale)
        val key = bundle.keys.first { bundle.getValue(it).length > 40 }
        val seeded = broken(bundle, key) { review.register.first() + " " + it }
        caught(TranslationReview.registerBreaches(seeded, locale, review), key)
    }

    @Test
    fun everyTermOfArtArrivesInEveryLanguage() {
        eachTarget { locale, bundle -> TranslationReview.terminologyBreaches(english, bundle, locale, TranslationReview.reviewOf(locale)) }
        val (locale, bundle) = targets.first()
        val review = TranslationReview.reviewOf(locale)
        val concept = review.concepts.first { c -> english.values.any { TranslationReview.mentions(it, c.term) } }
        val key = english.keys.first { k -> k in bundle && TranslationReview.mentions(english.getValue(k), concept.term) && !review.excused(k, concept.term) }
        // a term of art paraphrased away: the sentence still reads, and the vocabulary has come apart
        val seeded = broken(bundle, key) { concept.approved.fold(it) { text, form -> text.replace(Regex(Regex.escape(form), RegexOption.IGNORE_CASE), "Dingsbums") } }
        caught(TranslationReview.terminologyBreaches(english, seeded, locale, review), key)
    }

    @Test
    fun oneConceptIsRenderedByOneWord() {
        eachTarget { locale, bundle -> TranslationReview.conceptBreaches(english, bundle, locale, TranslationReview.reviewOf(locale)) }
        val (locale, bundle) = targets.first()
        val review = TranslationReview.reviewOf(locale)
        val concept = review.concepts.first { c -> c.rejected.isNotEmpty() && english.values.any { TranslationReview.mentions(it, c.term) } }
        val key = english.keys.first { k -> k in bundle && TranslationReview.mentions(english.getValue(k), concept.term) && !review.excused(k, concept.term) }
        // the second word for the one concept — *Abrundung* beside *Verrundung*, which is what the
        // slice-2 sweep spent 190 corrections removing
        val seeded = broken(bundle, key) { "${concept.rejected.first()}: $it" }
        caught(TranslationReview.conceptBreaches(english, seeded, locale, review), key)
    }
}
