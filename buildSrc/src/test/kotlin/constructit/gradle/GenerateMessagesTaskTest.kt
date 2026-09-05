package constructit.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ARB→Kotlin generator's own tests (OP-29).
 *
 * They run as part of building `buildSrc`, because the root build's `check` cannot reach in here — see the
 * `jar dependsOn test` line in `buildSrc/build.gradle.kts`.
 */
class GenerateMessagesTaskTest {
    private fun bundles(vararg files: Pair<String, String>): List<File> {
        val dir = createTempDir(prefix = "arb")
        return files.map { (name, text) ->
            File(dir, name).apply { writeText(text) }
        }
    }

    private val english =
        """
        {
          "@@locale": "en",
          "ui.undo": "Undo",
          "@ui.undo": { "description": "The button that undoes an edit." },
          "ui.dialog.title": "{title} — {count, plural, one{{count} element} other{{count} elements}}",
          "@ui.dialog.title": {
            "description": "The dialog's heading.",
            "placeholders": { "count": { "type": "int" }, "title": { "type": "String" } }
          }
        }
        """.trimIndent()

    private val german =
        """
        {
          "@@locale": "de",
          "ui.dialog.title": "{title} — {count, plural, one{{count} Element} other{{count} Elemente}}"
        }
        """.trimIndent()

    /** Same input, byte-identical output — the property that makes the generator safe to run every build. */
    @Test
    fun generatingTwiceProducesTheSameBytes() {
        val files = bundles("app_en.arb" to english, "app_de.arb" to german)
        val once = GenerateMessagesTask.renderBundles(files)
        val twice = GenerateMessagesTask.renderBundles(files.reversed())
        assertEquals(once.common, twice.common, "the generated source must not depend on the order the files arrive in")
        assertEquals(once.jvm, twice.jvm, "…nor the JVM tables")
        assertEquals(once.chunks, twice.chunks, "…nor the browser chunks, which are deployed files")
    }

    /**
     * **Only English is in the common source** (OP-29 slice 3), and a key the target bundle does not carry
     * is simply absent from that language's chunk, so the lookup falls back to English at runtime. This is
     * the *shape* of the split; `MessageBundleTest` asserts what it does when it runs.
     */
    @Test
    fun aKeyMissingFromATargetFallsBackToEnglish() {
        val out = GenerateMessagesTask.renderBundles(bundles("app_en.arb" to english, "app_de.arb" to german))
        assertTrue(out.common.contains("""english["ui.undo"] = "Undo""""), out.common)
        assertFalse(out.common.contains("Elemente"), "the German text must not ride in the main bundle")
        assertTrue(out.common.contains("""if (index > 0) Bundle.patterns(locales[index])?.get(key)?.let { return it }"""), out.common)
        // …and English is always slot 0, whatever the other languages are called
        assertTrue(out.common.contains("""listOf("en", "de")"""), out.common)
        // the German table is compiled for the JVM and *only* carries what German actually says
        assertTrue(out.jvm.contains("""tableDe["ui.dialog.title"]"""), out.jvm)
        assertFalse(out.jvm.contains("""tableDe["ui.undo"]"""), "an untranslated key falls back rather than being copied")
    }

    /**
     * **One chunk per language** — a classic script that assigns a flat array, so it loads over `file:` as
     * readily as over http, and the keys are sorted so the deployed file is byte-identical run to run.
     */
    @Test
    fun everyLanguageButEnglishGetsItsOwnBrowserChunk() {
        val out = GenerateMessagesTask.renderBundles(bundles("app_en.arb" to english, "app_de.arb" to german))
        assertEquals(setOf("de"), out.chunks.keys, "English rides in the main bundle, so it has no chunk")
        val chunk = out.chunks.getValue("de")
        assertTrue(chunk.contains("""(g.constructitL10n = g.constructitL10n || {})["de"] = ["""), chunk)
        assertTrue(chunk.contains("""    "ui.dialog.title", """), chunk)
        assertTrue(chunk.contains("Elemente"), chunk)
        assertFalse(chunk.contains("ui.undo"), "a key German does not carry is not in German's chunk")
    }

    /** A placeholder-free key is a plain lookup; a key with placeholders goes through `formatMessage`. */
    @Test
    fun theAccessorsAreTypedAndOrderedByTheMessage() {
        val out = GenerateMessagesTask.renderBundles(bundles("app_en.arb" to english, "app_de.arb" to german)).common
        assertTrue(out.contains("""public fun uiUndo(locale: String = L10n.locale): String = text("ui.undo", locale)"""), out)
        // the ARB lists `count` before `title`; the *message* mentions `title` first, and so does the signature
        val signature = out.substringAfter("public fun uiDialogTitle(").substringBefore(")")
        assertEquals(
            listOf("title: String", "count: Int", "locale: String = L10n.locale"),
            signature.split(",").map { it.trim() }.filter { it.isNotEmpty() },
        )
        assertTrue(out.contains("""): String = Msgs.uiDialogTitle(title, count).render(locale)"""), out)
    }

    /**
     * …and the same keys as **values** (OP-29 slice 2): one `Msgs` factory per key, returning the message
     * rather than the sentence. `Messages` is a rendering face over it, which is why there is one signature.
     */
    @Test
    fun everyKeyIsAlsoAValueFactory() {
        val out = GenerateMessagesTask.renderBundles(bundles("app_en.arb" to english, "app_de.arb" to german)).common
        assertTrue(out.contains("""public object Msgs {"""), out)
        assertTrue(out.contains("""public fun uiUndo(): Msg = Msg("ui.undo")"""), out)
        val signature = out.substringAfter("public fun uiDialogTitle(", "").substringAfter("public fun uiDialogTitle(").substringBefore(")")
        assertEquals(
            listOf("title: String", "count: Int"),
            signature.split(",").map { it.trim() }.filter { it.isNotEmpty() },
        )
        assertTrue(out.contains("""): Msg = Msg("ui.dialog.title", mapOf("title" to title, "count" to count))"""), out)
    }

    /**
     * A placeholder that is **itself a message** (OP-29 slice 2) — a face's name inside a refusal — is
     * declared `"type": "message"` and typed `Msg`, so a call site cannot pass a pre-rendered English word.
     */
    @Test
    fun aMessageTypedPlaceholderIsAMessage() {
        val nested =
            """
            {
              "@@locale": "en",
              "refusal.notAPlane": "{name} is not a plane",
              "@refusal.notAPlane": {
                "description": "Why a face cannot be used.",
                "placeholders": { "name": { "type": "message" } }
              }
            }
            """.trimIndent()
        val out = GenerateMessagesTask.renderBundles(bundles("app_en.arb" to nested)).common
        val factory = out.substringAfter("public object Msgs {").substringAfter("public fun refusalNotAPlane(")
        assertEquals("name: Msg,", factory.lineSequence().first().ifEmpty { factory.lineSequence().drop(1).first() }.trim())
        assertTrue(out.contains("""): Msg = Msg("refusal.notAPlane", mapOf("name" to name))"""), out)
    }

    /**
     * A placeholder that is a **measured figure** (OP-29 slice 3) is declared `"type": "decimal"`. Its
     * parameter stays `String` — the engine states the figure in the file's own spelling, which is where
     * the rounding rule lives (OP-18) — and the *factory* wraps it in `Num`, so the message value carries a
     * number and the reader's own `NumberFormat` spells it at render time.
     */
    @Test
    fun aDecimalPlaceholderBecomesANumberInTheValue() {
        val measured =
            """
            {
              "@@locale": "en",
              "refusal.tooThin": "the wall is {mm} mm",
              "@refusal.tooThin": {
                "description": "Why a shell cannot be built.",
                "placeholders": { "mm": { "type": "decimal" } }
              }
            }
            """.trimIndent()
        val out = GenerateMessagesTask.renderBundles(bundles("app_en.arb" to measured)).common
        assertTrue(out.contains("""        mm: String,"""), out)
        assertTrue(out.contains("""): Msg = Msg("refusal.tooThin", mapOf("mm" to Num.of(mm)))"""), out)
    }

    /**
     * The defect that made this check exist: DeepL translated `{reason}` into `{Grund}`. The generator
     * refuses the bundle, so the mistake cannot reach a build, let alone a reader.
     */
    @Test
    fun aTranslatedPlaceholderNameIsRefused() {
        val mangled =
            """
            {
              "@@locale": "de",
              "ui.dialog.title": "{Titel} — {count, plural, one{{count} Element} other{{count} Elemente}}"
            }
            """.trimIndent()
        val thrown =
            runCatching {
                GenerateMessagesTask.renderBundles(bundles("app_en.arb" to english, "app_de.arb" to mangled))
            }.exceptionOrNull()
        assertTrue(thrown != null, "a renamed placeholder must fail the build")
        assertTrue(thrown!!.message!!.contains("de/ui.dialog.title does not bind {title}"), thrown.message!!)
    }

    /** …and a branch of a plural is text, never an argument: no false alarm on a perfectly good bundle. */
    @Test
    fun textInsideAPluralBranchIsNotMistakenForAnArgument() {
        val wordy =
            """
            {
              "@@locale": "de",
              "ui.dialog.title": "{title} — {count, plural, one{genau {count} Element} other{alle {count} Elemente}}"
            }
            """.trimIndent()
        GenerateMessagesTask.renderBundles(bundles("app_en.arb" to english, "app_de.arb" to wordy))
    }

    /** The one rule that turns a key into a name, so a key names its accessor and nothing else does. */
    @Test
    fun aKeyNamesItsAccessor() {
        assertEquals("toolFilletedgeTitle", GenerateMessagesTask.accessorName("tool.filletedge.title"))
        assertEquals("toolFilletedgeSlot1", GenerateMessagesTask.accessorName("tool.filletedge.slot.1"))
        assertEquals("categorySolids", GenerateMessagesTask.accessorName("category.solids"))
        assertEquals("uiWallsideCentred", GenerateMessagesTask.accessorName("ui.wallside.centred"))
    }
}
