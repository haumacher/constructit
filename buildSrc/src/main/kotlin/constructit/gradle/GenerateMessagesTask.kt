package constructit.gradle

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * OP-29: compile the ARB bundles into one typed Kotlin accessor per key.
 *
 * The English ARB is the source of truth — every key, its `description` (which is also the context DeepL
 * translates against) and its typed `placeholders` — and every `app_<lang>.arb` beside it supplies that
 * language's patterns. What comes out is a single `constructit.l10n.Messages` object holding the pattern of
 * every key in every locale, plus a function per key whose parameters are that key's placeholders: a call
 * site that passes the wrong arguments does not compile, and nothing parses JSON at runtime.
 *
 * Two properties this task owes the build and the design:
 *
 * - it is a **build input**, so editing an ARB regenerates and recompiles (the ARB files are `@InputFiles`);
 * - it is **deterministic** — keys are emitted in sorted order, so the same bundles produce byte-identical
 *   Kotlin however the JSON happened to be ordered. `MessagesGeneratorTest` generates twice and compares.
 *
 * Formatting itself is *not* here: a pattern with placeholders is handed to `formatMessage`, whose actuals
 * are ICU4J on the JVM and `intl-messageformat` in the browser. There is no message parser in this build.
 */
@CacheableTask
abstract class GenerateMessagesTask : DefaultTask() {
    /** `l10n/app_en.arb` and every `app_<lang>.arb` beside it. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bundles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val target = File(out, "constructit/l10n/Messages.kt")
        target.parentFile.mkdirs()
        target.writeText(renderBundles(bundles.files))
    }

    companion object {
        /** The whole generator as one pure function of the input files, so a test can call it twice. */
        fun renderBundles(files: Collection<File>): String {
            val byLocale = files.associate { localeOf(it) to readArb(it) }
            val english = byLocale["en"] ?: throw GradleException("no l10n/app_en.arb among $files")
            val locales = listOf("en") + byLocale.keys.filter { it != "en" }.sorted()
            checkPlaceholders(locales, byLocale, english)
            return render(locales, byLocale, english)
        }

        /**
         * **A translation may reword a message; it may not rename the holes in it.**
         *
         * DeepL translated `{reason}` into `{Grund}` on the first German pass (session 82), which no
         * formatter can bind — the message would have rendered the word `{Grund}` at the user. It is caught
         * *here*, in the generator, so a bundle carrying it does not compile: a defect that only a test
         * catches is a defect that reaches a branch someone forgot to run tests on.
         *
         * Sound rather than clever: it looks only for the names the English message declares, so text
         * inside a plural or select branch can never be mistaken for an argument. The reverse direction —
         * a target that invents an argument the English does not have — needs a real ICU parse and is
         * asserted by `MessageBundleTest`, which has ICU4J on its classpath.
         */
        private fun checkPlaceholders(
            locales: List<String>,
            byLocale: Map<String, Map<String, Any?>>,
            english: Map<String, Any?>,
        ) {
            val broken = ArrayList<String>()
            for (key in keysOf(english)) {
                val names = placeholdersOf(english, key).map { it.first }
                if (names.isEmpty()) continue
                for (locale in locales.filter { it != "en" }) {
                    val pattern = byLocale[locale]?.get(key) as? String ?: continue
                    for (name in names) {
                        if (!Regex("\\{\\s*" + Regex.escape(name) + "\\b").containsMatchIn(pattern)) {
                            broken.add("$locale/$key does not bind {$name}: $pattern")
                        }
                    }
                }
            }
            if (broken.isNotEmpty()) {
                throw GradleException(
                    "a translated ARB renamed or dropped a placeholder (OP-29):\n" + broken.joinToString("\n"),
                )
            }
        }

        private fun localeOf(file: File): String {
            val name = file.name.removeSuffix(".arb")
            val cut = name.lastIndexOf('_')
            if (cut < 0) throw GradleException("an ARB file is named basename_lang.arb, not ${file.name}")
            return name.substring(cut + 1)
        }

        @Suppress("UNCHECKED_CAST")
        private fun readArb(file: File): Map<String, Any?> = JsonSlurper().parse(file, "UTF-8") as Map<String, Any?>

        /** The declared keys of the English bundle, in sorted order — metadata and globals dropped. */
        private fun keysOf(english: Map<String, Any?>): List<String> =
            english.keys.filter { !it.startsWith("@") }.sorted()

        @Suppress("UNCHECKED_CAST")
        private fun metaOf(
            english: Map<String, Any?>,
            key: String,
        ): Map<String, Any?> = english["@$key"] as? Map<String, Any?> ?: emptyMap()

        /**
         * The declared placeholders of [key], **in the order the English message first mentions them**.
         *
         * Not the order the JSON happens to list them in: an ARB is a map, and the reader is free to hand
         * one back in any order it likes. Reading the order off the *message* makes the generated signature
         * both deterministic and the one a reader would guess — `uiDialogTitle(title, count)` for
         * `"{title} — {count, plural, …}"`. A declared name the message never mentions goes last, sorted,
         * which is a mistake `MessageBundleTest` fails on anyway.
         */
        @Suppress("UNCHECKED_CAST")
        private fun placeholdersOf(
            english: Map<String, Any?>,
            key: String,
        ): List<Pair<String, String>> {
            val declared = metaOf(english, key)["placeholders"] as? Map<String, Any?> ?: return emptyList()
            val types =
                declared.entries.associate { (name, spec) ->
                    name to kotlinTypeOf(((spec as? Map<String, Any?>)?.get("type") as? String) ?: "String")
                }
            val pattern = english[key] as? String ?: ""
            val mentioned = LinkedHashSet<String>()
            for (match in ARGUMENT.findAll(pattern)) {
                val name = match.groupValues[1]
                if (name in types) mentioned.add(name)
            }
            mentioned.addAll(types.keys.sorted())
            return mentioned.map { it to types.getValue(it) }
        }

        /** `{name` — an argument's opening, whatever follows it (a plural, a select, or nothing). */
        private val ARGUMENT = Regex("\\{\\s*([A-Za-z_][A-Za-z0-9_]*)")

        private fun kotlinTypeOf(arbType: String): String =
            when (arbType) {
                "int" -> "Int"
                "num", "double" -> "Double"
                else -> "String"
            }

        /** `tool.filletedge.title` → `toolFilletedgeTitle`; the one rule, so a key names its accessor. */
        fun accessorName(key: String): String {
            val parts = key.split('.', '_', '-').filter { it.isNotEmpty() }
            val head = parts.first().replaceFirstChar { it.lowercaseChar() }
            val tail =
                parts.drop(1).joinToString("") { part ->
                    part.replaceFirstChar { it.uppercaseChar() }
                }
            return head + tail
        }

        private fun quote(text: String): String {
            val sb = StringBuilder("\"")
            for (c in text) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '$' -> sb.append("\\$")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> sb.append(c)
                }
            }
            return sb.append('"').toString()
        }

        private fun render(
            locales: List<String>,
            byLocale: Map<String, Map<String, Any?>>,
            english: Map<String, Any?>,
        ): String {
            val keys = keysOf(english)
            val sb = StringBuilder()
            sb.append("// Generated from l10n/app_*.arb by :generateMessages (OP-29). Do not edit.\n")
            sb.append("@file:Suppress(\"ktlint\", \"unused\", \"RedundantVisibilityModifier\", \"LongMethod\")\n\n")
            sb.append("package constructit.l10n\n\n")
            sb.append("/**\n")
            sb.append(" * Every user-visible message of the chrome, in every bundled language (OP-29).\n")
            sb.append(" *\n")
            sb.append(" * One function per ARB key, its parameters the key's declared placeholders. A pattern with\n")
            sb.append(" * placeholders goes through [formatMessage] (ICU4J on the JVM, `intl-messageformat` in the\n")
            sb.append(" * browser); a plain one is returned as it stands. A key a target bundle does not carry falls\n")
            sb.append(" * back to English, which is why [locales] starts with `en`.\n")
            sb.append(" */\n")
            sb.append("public object Messages {\n")
            sb.append("    public val locales: List<String> = listOf(${locales.joinToString(", ") { quote(it) }})\n\n")
            sb.append("    private val patterns: MutableMap<String, Array<String?>> = HashMap(${keys.size * 2})\n\n")

            // The table is filled in chunks: one initializer holding six hundred keys would be a single
            // enormous method, and the JVM caps a method at 64 KB of bytecode.
            val chunks = keys.chunked(60)
            sb.append("    init {\n")
            for (i in chunks.indices) sb.append("        fill$i()\n")
            sb.append("    }\n\n")
            for ((i, chunk) in chunks.withIndex()) {
                sb.append("    private fun fill$i() {\n")
                for (key in chunk) {
                    val row =
                        locales.joinToString(", ") { locale ->
                            val value = byLocale[locale]?.get(key) as? String
                            if (locale == "en" || value != null) quote(value ?: "") else "null"
                        }
                    sb.append("        patterns[${quote(key)}] = arrayOf($row)\n")
                }
                sb.append("    }\n\n")
            }

            sb.append("    /** Which slot of a pattern row [locale] reads — its own, its language's, else English. */\n")
            sb.append("    public fun indexOf(locale: String): Int {\n")
            sb.append("        val exact = locales.indexOf(locale)\n")
            sb.append("        if (exact >= 0) return exact\n")
            sb.append("        val language = locale.substringBefore('-').substringBefore('_')\n")
            sb.append("        val byLanguage = locales.indexOf(language)\n")
            sb.append("        return if (byLanguage >= 0) byLanguage else 0\n")
            sb.append("    }\n\n")
            sb.append("    /** The pattern for [key] in [locale], English where that language does not carry it. */\n")
            sb.append("    public fun patternOrNull(\n")
            sb.append("        key: String,\n")
            sb.append("        locale: String = L10n.locale,\n")
            sb.append("    ): String? {\n")
            sb.append("        val row = patterns[key] ?: return null\n")
            sb.append("        return row.getOrNull(indexOf(locale)) ?: row[0]\n")
            sb.append("    }\n\n")
            sb.append("    /** [patternOrNull], with the key itself as the last resort — a missing key is visible, never blank. */\n")
            sb.append("    public fun text(\n")
            sb.append("        key: String,\n")
            sb.append("        locale: String = L10n.locale,\n")
            sb.append("    ): String = patternOrNull(key, locale) ?: key\n\n")

            for (key in keys) {
                val placeholders = placeholdersOf(english, key)
                val name = accessorName(key)
                val description = metaOf(english, key)["description"] as? String
                if (description != null) sb.append("    /** ${description.replace("*/", "* /")} */\n")
                if (placeholders.isEmpty()) {
                    sb.append("    public fun $name(locale: String = L10n.locale): String = text(${quote(key)}, locale)\n\n")
                } else {
                    val params = placeholders.joinToString("") { (n, t) -> "        $n: $t,\n" }
                    val args = placeholders.joinToString(", ") { (n, _) -> "${quote(n)} to $n" }
                    sb.append("    public fun $name(\n")
                    sb.append(params)
                    sb.append("        locale: String = L10n.locale,\n")
                    sb.append("    ): String = formatMessage(locale, text(${quote(key)}, locale), mapOf($args))\n\n")
                }
            }
            sb.append("}\n")
            return sb.toString()
        }
    }
}
