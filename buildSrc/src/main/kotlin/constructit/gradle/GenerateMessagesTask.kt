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
 * language's patterns. What comes out is a single `constructit.l10n.Messages` object holding the English
 * pattern of every key, plus a function per key whose parameters are that key's placeholders: a call site
 * that passes the wrong arguments does not compile, and nothing parses JSON at runtime.
 *
 * **One chunk per language** (slice 3). Slice 2 put every language's text in one table, so every session
 * downloaded the German whether or not it ever switched — 84.6 KB gzipped of text nobody read. The tables
 * are now split by *target* rather than by locale, because the two targets want opposite things:
 *
 * - **English** is compiled into `commonMain` and rides in the main bundle, which is what a first paint
 *   needs before anything can be fetched;
 * - **every other language** is emitted twice, once as Kotlin into `jvmMain` ([jvmOutputDir]) and once as
 *   a browser chunk into [chunkDir]. The JVM keeps everything in memory because a test renders German
 *   *synchronously* (`Messages.uiPanelDrawing("de")` may not wait for anything); the browser fetches
 *   `l10n/<lang>.js` the moment the reader picks that language, and never otherwise.
 *
 * The chunk is a **script that assigns a flat array**, not JSON fetched with `fetch()`, for one concrete
 * reason: half of `BrowserE2ETest` — and anyone who opens `index.html` from disk — runs the page over
 * `file:`, where a browser refuses an XHR/`fetch`/ESM subresource but still loads a classic `<script>`.
 * A flat `[key, pattern, key, pattern, …]` also reads back without `Object.keys` and gzips slightly
 * smaller than the equivalent object.
 *
 * Three properties this task owes the build and the design:
 *
 * - it is a **build input**, so editing an ARB regenerates and recompiles (the ARB files are `@InputFiles`);
 * - it is **deterministic** — keys are emitted in sorted order in every artifact, chunks included, so the
 *   same bundles produce byte-identical output however the JSON happened to be ordered.
 *   `GenerateMessagesTaskTest` generates twice and compares;
 * - formatting itself is *not* here: a pattern with placeholders is handed to `formatMessage`, whose
 *   actuals are ICU4J on the JVM and `intl-messageformat` in the browser, and a *number* argument is
 *   handed to `formatNumber`, whose actuals are ICU4J's and the browser's own `NumberFormat`. There is no
 *   message parser and no number formatter in this build.
 */
@CacheableTask
abstract class GenerateMessagesTask : DefaultTask() {
    /** `l10n/app_en.arb` and every `app_<lang>.arb` beside it. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bundles: ConfigurableFileCollection

    /** `commonMain`: `Messages`, `Msgs` and the English table. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** `jvmMain`: every other language's table, so a JVM test renders German without waiting. */
    @get:OutputDirectory
    abstract val jvmOutputDir: DirectoryProperty

    /** `jsMain` resources: `l10n/<lang>.js`, one chunk per language, fetched when it is chosen. */
    @get:OutputDirectory
    abstract val chunkDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val generated = renderBundles(bundles.files)

        val common = outputDir.get().asFile
        common.deleteRecursively()
        File(common, "constructit/l10n/Messages.kt").apply {
            parentFile.mkdirs()
            writeText(generated.common)
        }

        val jvm = jvmOutputDir.get().asFile
        jvm.deleteRecursively()
        File(jvm, "constructit/l10n/MessageTables.kt").apply {
            parentFile.mkdirs()
            writeText(generated.jvm)
        }

        val chunks = chunkDir.get().asFile
        chunks.deleteRecursively()
        for ((locale, text) in generated.chunks) {
            File(chunks, "l10n/$locale.js").apply {
                parentFile.mkdirs()
                writeText(text)
            }
        }
    }

    /** What one run produces: the common source, the JVM source, and one browser chunk per language. */
    data class Generated(
        val common: String,
        val jvm: String,
        val chunks: Map<String, String>,
    )

    companion object {
        /** The whole generator as one pure function of the input files, so a test can call it twice. */
        fun renderBundles(files: Collection<File>): Generated {
            val byLocale = files.associate { localeOf(it) to readArb(it) }
            val english = byLocale["en"] ?: throw GradleException("no l10n/app_en.arb among $files")
            val locales = listOf("en") + byLocale.keys.filter { it != "en" }.sorted()
            checkPlaceholders(locales, byLocale, english)
            val keys = keysOf(english)
            return Generated(
                common = render(locales, english),
                jvm = renderTables(locales, byLocale, keys),
                chunks =
                    locales.filter { it != "en" }.associateWith { locale ->
                        renderChunk(locale, byLocale.getValue(locale), keys)
                    },
            )
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
                val names = placeholdersOf(english, key).map { it.name }
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

        /** One declared placeholder: its name, the type the ARB gives it, and the Kotlin type it becomes. */
        data class Placeholder(
            val name: String,
            val arbType: String,
            val kotlinType: String,
        )

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
        ): List<Placeholder> {
            val declared = metaOf(english, key)["placeholders"] as? Map<String, Any?> ?: return emptyList()
            val types =
                declared.entries.associate { (name, spec) ->
                    name to (((spec as? Map<String, Any?>)?.get("type") as? String) ?: "String")
                }
            val pattern = english[key] as? String ?: ""
            val mentioned = LinkedHashSet<String>()
            for (match in ARGUMENT.findAll(pattern)) {
                val name = match.groupValues[1]
                if (name in types) mentioned.add(name)
            }
            mentioned.addAll(types.keys.sorted())
            return mentioned.map { name ->
                val arbType = types.getValue(name)
                Placeholder(name, arbType, kotlinTypeOf(arbType))
            }
        }

        /** `{name` — an argument's opening, whatever follows it (a plural, a select, or nothing). */
        private val ARGUMENT = Regex("\\{\\s*([A-Za-z_][A-Za-z0-9_]*)")

        private fun kotlinTypeOf(arbType: String): String =
            when (arbType) {
                "int" -> "Int"
                "num", "double" -> "Double"
                // OP-29 slice 2: an argument that is itself a message — a face's name inside a refusal, the
                // word for what an element is inside a note. Not an ARB standard type; the ARB spec leaves
                // the vocabulary open and the translator copies `placeholders` through untouched.
                "message" -> "Msg"
                // OP-29 slice 3: a *measured figure* — a length in mm, an angle in degrees, a factor. The
                // engine states it in the file's own spelling (a decimal point, OP-18) and the parameter
                // stays `String` for exactly that reason: `Frames3.mm` and `Format.num` are the two places
                // a figure is rounded, that rounding is platform-independent, and the locale must change
                // the *separator and nothing else*. The factory below wraps it in `Num`, which respells it
                // through the reader's own `NumberFormat` at render time — so `0.3` reads `0,3` in German
                // off the very same message value.
                "decimal" -> "String"
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

        /** A JavaScript string literal — the chunk's own quoting, and deliberately ASCII-safe. */
        private fun jsQuote(text: String): String {
            val sb = StringBuilder("\"")
            for (c in text) {
                when {
                    c == '\\' -> sb.append("\\\\")
                    c == '"' -> sb.append("\\\"")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    // U+2028/U+2029 terminate a line inside a JS string literal; everything else above
                    // ASCII rides as itself, since the chunk is served as UTF-8 like the page.
                    c.code == 0x2028 || c.code == 0x2029 || c.code < 0x20 ->
                        sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    else -> sb.append(c)
                }
            }
            return sb.append('"').toString()
        }

        private fun render(
            locales: List<String>,
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
            sb.append(" * browser); a plain one is returned as it stands.\n")
            sb.append(" *\n")
            sb.append(" * **Only the English table is here** (slice 3): it is the fall-back for every key a language\n")
            sb.append(" * does not carry, so it must be present before anything can be fetched. Every other language\n")
            sb.append(" * comes from [Bundle], which the JVM fills from a compiled table and the browser from the\n")
            sb.append(" * chunk `l10n/<lang>.js` — see [Bundle] for why the main bundle no longer carries the text of\n")
            sb.append(" * a language nobody in this session reads.\n")
            sb.append(" */\n")
            sb.append("public object Messages {\n")
            sb.append("    public val locales: List<String> = listOf(${locales.joinToString(", ") { quote(it) }})\n\n")
            sb.append("    private val english: MutableMap<String, String> = HashMap(${keys.size * 2})\n\n")

            // The table is filled in chunks: one initializer holding six hundred keys would be a single
            // enormous method, and the JVM caps a method at 64 KB of bytecode.
            val chunks = keys.chunked(60)
            sb.append("    init {\n")
            for (i in chunks.indices) sb.append("        fill$i()\n")
            sb.append("    }\n\n")
            for ((i, chunk) in chunks.withIndex()) {
                sb.append("    private fun fill$i() {\n")
                for (key in chunk) {
                    sb.append("        english[${quote(key)}] = ${quote(english[key] as? String ?: "")}\n")
                }
                sb.append("    }\n\n")
            }

            sb.append("    /** Which slot of [locales] reads [locale] — its own, its language's, else English. */\n")
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
            sb.append("        val index = indexOf(locale)\n")
            sb.append("        if (index > 0) Bundle.patterns(locales[index])?.get(key)?.let { return it }\n")
            sb.append("        return english[key]\n")
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
                    val params = placeholders.joinToString("") { "        ${it.name}: ${it.kotlinType},\n" }
                    sb.append("    public fun $name(\n")
                    sb.append(params)
                    sb.append("        locale: String = L10n.locale,\n")
                    val pass = placeholders.joinToString(", ") { it.name }
                    sb.append("    ): String = Msgs.$name($pass).render(locale)\n\n")
                }
            }
            sb.append("}\n\n")
            renderValues(sb, keys, english)
            return sb.toString()
        }

        /**
         * The same keys as **values** (OP-29 slice 2): one factory per key returning a [Msg] rather than a
         * sentence, so the engine and the editor can *carry* what they will say and the shell decides, at
         * the moment it paints, which language it is said in.
         *
         * `Messages` is now a thin rendering face over this: `Messages.foo(a, b, locale)` is
         * `Msgs.foo(a, b).render(locale)`. One table, one signature, two ways to ask.
         *
         * A `decimal` placeholder is wrapped in `Num` here rather than at the call site (slice 3): the
         * caller states the figure once, in the file's own spelling, and *the message value* carries a
         * number from then on — so the very same refusal reads `0.3 mm` in English and `0,3 mm` in German.
         */
        private fun renderValues(
            sb: StringBuilder,
            keys: List<String>,
            english: Map<String, Any?>,
        ) {
            sb.append("/**\n")
            sb.append(" * Every message of ConstructIt as a **value** — a key and its arguments (OP-29).\n")
            sb.append(" *\n")
            sb.append(" * One factory per ARB key, its parameters the key's declared placeholders; a `Msg` parameter is an\n")
            sb.append(" * argument that is itself a message, which is how a refusal names a face in the reader's language,\n")
            sb.append(" * and a `decimal` one becomes a [Num], which is how it states a measurement in the reader's numbers.\n")
            sb.append(" * Nothing here renders: see [Msg.render].\n")
            sb.append(" */\n")
            sb.append("public object Msgs {\n")
            for (key in keys) {
                val placeholders = placeholdersOf(english, key)
                val name = accessorName(key)
                val description = metaOf(english, key)["description"] as? String
                if (description != null) sb.append("    /** ${description.replace("*/", "* /")} */\n")
                if (placeholders.isEmpty()) {
                    sb.append("    public fun $name(): Msg = Msg(${quote(key)})\n\n")
                } else {
                    val params = placeholders.joinToString("") { "        ${it.name}: ${it.kotlinType},\n" }
                    val args =
                        placeholders.joinToString(", ") {
                            val value = if (it.arbType == "decimal") "Num.of(${it.name})" else it.name
                            "${quote(it.name)} to $value"
                        }
                    sb.append("    public fun $name(\n")
                    sb.append(params)
                    sb.append("    ): Msg = Msg(${quote(key)}, mapOf($args))\n\n")
                }
            }
            sb.append("}\n")
        }

        /**
         * The **JVM's** copy of every language but English, as Kotlin (slice 3).
         *
         * The browser fetches a chunk; the JVM cannot, because a test renders two languages side by side in
         * one expression and `Messages.uiPanelDrawing("de")` may not wait for anything. So the whole text
         * stays compiled on the target where 231 KB of German costs nothing and asynchrony would cost the
         * test suite its shape.
         */
        private fun renderTables(
            locales: List<String>,
            byLocale: Map<String, Map<String, Any?>>,
            keys: List<String>,
        ): String {
            val others = locales.filter { it != "en" }
            val sb = StringBuilder()
            sb.append("// Generated from l10n/app_*.arb by :generateMessages (OP-29). Do not edit.\n")
            sb.append("@file:Suppress(\"ktlint\", \"unused\", \"RedundantVisibilityModifier\", \"LongMethod\")\n\n")
            sb.append("package constructit.l10n\n\n")
            sb.append("/**\n")
            sb.append(" * Every language but English, compiled in — the JVM half of OP-29 slice 3's chunking.\n")
            sb.append(" *\n")
            sb.append(" * The browser gets `l10n/<lang>.js` and loads it when the reader asks for that language; the\n")
            sb.append(" * JVM gets this, because a headless test renders `de` and `en` in the same breath and nothing\n")
            sb.append(" * there may be asynchronous. Reached through the `bundledPatterns` actual, never directly.\n")
            sb.append(" */\n")
            sb.append("internal object MessageTables {\n")
            for (locale in others) {
                val table = byLocale.getValue(locale)
                val present = keys.filter { table[it] is String }
                val name = tableName(locale)
                sb.append("    private val $name: MutableMap<String, String> = HashMap(${present.size * 2})\n\n")
                val chunks = present.chunked(60)
                sb.append("    private fun load$name() {\n")
                for (i in chunks.indices) sb.append("        ${name}Fill$i()\n")
                sb.append("    }\n\n")
                for ((i, chunk) in chunks.withIndex()) {
                    sb.append("    private fun ${name}Fill$i() {\n")
                    for (key in chunk) {
                        sb.append("        $name[${quote(key)}] = ${quote(table[key] as String)}\n")
                    }
                    sb.append("    }\n\n")
                }
                sb.append("    init {\n        load$name()\n    }\n\n")
            }
            sb.append("    fun of(locale: String): Map<String, String>? =\n")
            sb.append("        when (locale) {\n")
            for (locale in others) sb.append("            ${quote(locale)} -> ${tableName(locale)}\n")
            sb.append("            else -> null\n")
            sb.append("        }\n")
            sb.append("}\n")
            return sb.toString()
        }

        /** `de` → `tableDe`, `pt-BR` → `tablePtBR`: a locale tag as one Kotlin identifier. */
        private fun tableName(locale: String): String =
            "table" +
                locale
                    .split('-', '_')
                    .filter { it.isNotEmpty() }
                    .joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }

        /**
         * One language as a **browser chunk**: a classic script that assigns a flat `[key, pattern, …]`
         * array under `window.constructitL10n`.
         *
         * Deliberately not JSON over `fetch`: the page runs from `file:` in half of `BrowserE2ETest` and
         * whenever a reader opens the distribution from disk, and a browser refuses a `fetch` there while
         * still loading a `<script src>`. Only the keys the language actually carries are emitted, because
         * the rest fall back to English anyway (`Messages.patternOrNull`).
         */
        private fun renderChunk(
            locale: String,
            table: Map<String, Any?>,
            keys: List<String>,
        ): String {
            val present = keys.filter { table[it] is String }
            val sb = StringBuilder()
            sb.append("// Generated from l10n/app_$locale.arb by :generateMessages (OP-29 slice 3). Do not edit.\n")
            sb.append("(function (g) {\n")
            sb.append("  (g.constructitL10n = g.constructitL10n || {})[${jsQuote(locale)}] = [\n")
            for (key in present) {
                sb.append("    ${jsQuote(key)}, ${jsQuote(table[key] as String)},\n")
            }
            sb.append("  ];\n")
            sb.append("})(typeof globalThis !== \"undefined\" ? globalThis : window);\n")
            return sb.toString()
        }
    }
}
