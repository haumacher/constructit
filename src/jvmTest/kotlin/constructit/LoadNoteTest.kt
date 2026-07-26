package constructit

import constructit.editor.DocumentFormat
import constructit.editor.Editor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **What a load had to decide is said where the user is looking** (OP-18, *Versioning & migration*).
 *
 * A migration finding — a stored literal whose meaning the file cannot settle, so the reader keeps today's
 * reading and *says which element it could not decide about* (`Document.loadNotes`) — was published into the
 * document's one-shot note, which only a *tool* run reads. Opening the file then cleared the status line as its
 * last act, so the finding reached nobody: the one thing a migration owes the user was written and thrown away.
 */
class LoadNoteTest {
    /** A format-1 drawing whose rider sits 5 mm off its carrier, so its stored distance cannot be arbitrated. */
    private val ambiguous =
        """
constructit 1
point 0,0 -> e1
point 100,0 -> e2
tool segment pts=e1,e2 clicks=0,0;100,0 -> e3
pointoncurve e3 30,5 dofs=30mm -> e4
""".trimStart()

    private val clean =
        """
constructit 2
point 0,0 -> e1
point 100,0 -> e2
tool segment pts=e1,e2 clicks=0,0;100,0 -> e3
""".trimStart()

    @Test
    fun openingAFileWithAMigrationFindingShowsIt() {
        val doc = DocumentFormat.load(ambiguous)
        assertTrue(doc.loadNotes.isNotEmpty(), "the fixture must produce a finding")
        val ed = Editor()
        ed.replaceDocument(doc)
        assertTrue(ed.statusHint.startsWith("Loaded with a note:"), "got: '${ed.statusHint}'")
        assertTrue(ed.statusHint.contains("e4"), "and it names the element the way the file does: ${ed.statusHint}")
        // it stays until the user does something else — the ordinary status-line lifecycle
        assertTrue(ed.statusHint.isNotEmpty())
    }

    @Test
    fun openingAFileWithNothingToReportSaysNothing() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(clean))
        assertEquals("", ed.statusHint, "no finding, no banner")
    }

    /** Two findings are one line: the first, and how many follow — a status line is one sentence. */
    @Test
    fun severalFindingsAreOneLineThatCountsThem() {
        val two = ambiguous + "pointoncurve e3 60,5 dofs=60mm -> e5\n"
        val doc = DocumentFormat.load(two)
        assertEquals(2, doc.loadNotes.size)
        val ed = Editor()
        ed.replaceDocument(doc)
        assertTrue(ed.statusHint.contains("(and 1 more)"), "got: ${ed.statusHint}")
    }
}
