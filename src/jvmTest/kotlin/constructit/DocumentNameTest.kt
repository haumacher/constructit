package constructit

import constructit.editor.DocumentName
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The drawing's name (OP-18): **shell state, not part of the file.** The arithmetic is shared code, so it
 * is tested here; the browser half — a download name, or a File System Access handle — is exercised by the
 * Playwright E2E, which is the only place that has either.
 */
class DocumentNameTest {
    @Test
    fun anUnnamedDrawingIsCalledDrawing() {
        assertEquals("drawing", DocumentName.DEFAULT)
        assertEquals("drawing", DocumentName.normalize(""))
        assertEquals("drawing", DocumentName.normalize("   "))
        assertEquals("drawing.cit", DocumentName.fileName(""))
    }

    @Test
    fun aTypedNameIsTrimmedAndKeptFileSafe() {
        assertEquals("house", DocumentName.normalize("  house  "))
        // spaces are kept: the "one word" rule belongs to the file *format*, not to a file name
        assertEquals("west wing", DocumentName.normalize("west wing"))
        // a separator reads as a path, whatever it separates: only the base name survives
        assertEquals("wing", DocumentName.normalize("west/wing"))
        assertEquals("a-b-c", DocumentName.normalize("a:b|c"))
        assertEquals("house", DocumentName.normalize("plans/house"), "a pasted path keeps only its base name")
        assertEquals("house", DocumentName.normalize("C:\\drawings\\house"))
    }

    @Test
    fun typingTheExtensionDoesNotDoubleIt() {
        assertEquals("house", DocumentName.normalize("house.cit"))
        assertEquals("house", DocumentName.normalize("house.CIT"))
        assertEquals("house.cit", DocumentName.fileName("house.cit"))
        // a name that *is* the extension is not an empty name
        assertEquals(".cit", DocumentName.normalize(".cit"))
    }

    @Test
    fun aPickedFilesNameLosesItsDirectoryAndItsExtension() {
        assertEquals("house", DocumentName.fromFileName("house.cit"))
        assertEquals("house", DocumentName.fromFileName("/home/me/plans/house.cit"))
        assertEquals("house", DocumentName.fromFileName("C:\\plans\\house.txt"))
        assertEquals("house.rev2", DocumentName.fromFileName("house.rev2.cit"), "only the last extension goes")
        assertEquals("sketch", DocumentName.fromFileName("sketch"), "a file without one keeps its name")
        assertEquals(".hidden", DocumentName.fromFileName(".hidden"), "a leading dot is not an extension")
        assertEquals("drawing", DocumentName.fromFileName(""))
    }

    @Test
    fun aNameIsBoundedSoItStaysAUsableFileName() {
        val long = "x".repeat(200)
        assertEquals(64, DocumentName.normalize(long).length)
        assertEquals(64 + 4, DocumentName.fileName(long).length)
    }
}
