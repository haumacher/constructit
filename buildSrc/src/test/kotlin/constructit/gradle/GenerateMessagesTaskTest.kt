package constructit.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals(once, twice, "the generated source must not depend on the order the files arrive in")
    }

    /**
     * A key the target bundle does not carry becomes a `null` slot, and the lookup falls back to English.
     * This is the *shape* of the fallback; `MessageBundleTest` asserts what it does at runtime.
     */
    @Test
    fun aKeyMissingFromATargetFallsBackToEnglish() {
        val out = GenerateMessagesTask.renderBundles(bundles("app_en.arb" to english, "app_de.arb" to german))
        assertTrue(out.contains("""patterns["ui.undo"] = arrayOf("Undo", null)"""), out)
        assertTrue(out.contains("""return row.getOrNull(indexOf(locale)) ?: row[0]"""), out)
        // …and English is always slot 0, whatever the other languages are called
        assertTrue(out.contains("""listOf("en", "de")"""), out)
    }

    /** A placeholder-free key is a plain lookup; a key with placeholders goes through `formatMessage`. */
    @Test
    fun theAccessorsAreTypedAndOrderedByTheMessage() {
        val out = GenerateMessagesTask.renderBundles(bundles("app_en.arb" to english, "app_de.arb" to german))
        assertTrue(out.contains("""public fun uiUndo(locale: String = L10n.locale): String = text("ui.undo", locale)"""), out)
        // the ARB lists `count` before `title`; the *message* mentions `title` first, and so does the signature
        val signature = out.substringAfter("public fun uiDialogTitle(").substringBefore(")")
        assertEquals(
            listOf("title: String", "count: Int", "locale: String = L10n.locale"),
            signature.split(",").map { it.trim() }.filter { it.isNotEmpty() },
        )
        assertTrue(out.contains("""formatMessage(locale, text("ui.dialog.title", locale), mapOf("title" to title, "count" to count))"""), out)
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
