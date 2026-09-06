package constructit.exchange

import constructit.core.Evaluator
import constructit.editor.Document
import constructit.geom.Watertight
import constructit.l10n.Messages
import constructit.l10n.Msg
import constructit.l10n.Msgs
import de.haumacher.kotlinjt.write.JtWriteException

/**
 * The four files this build writes. One entry per shipped format and nothing "for later": a format flag with
 * no writer behind it is a promise the app cannot keep.
 *
 * **JT** was the fourth entry's promise and is now the fourth entry: the sibling Kotlin multiplatform library
 * (https://github.com/haumacher/kotlinJT, a Gradle composite build) consumes the very [ExportScene] this
 * package hands its own writers, so the adapter — [Jt] — is the page it was said to be, and the bytes are the
 * library's.
 *
 * Deliberately absent, recorded so it is not looked for: **STEP** — the kernel is mesh-based and holds no exact
 * B-rep for solids, so an exact-geometry export would be either dishonest or a compliance project. (JT does not
 * share that problem: tessellation-plus-structure is the format's own majority use, not a lossy shortcut
 * through it.)
 */
enum class ExportFormat(
    val label: String,
    val extension: String,
    /** The MIME type a browser download should carry. */
    val mimeType: String,
    /**
     * What this format is *for*, in the words the button's tooltip uses (OP-29's exchange slice).
     *
     * A `Msg` rather than a pre-rendered `String`, even though every constant sits in a table built once per
     * session: it is this enum's own render-at-the-edge promise, since a tooltip wired to it later must still
     * read in whatever language is active *then*, not in the language active when the JVM loaded this class.
     */
    val purpose: Msg,
) {
    GLB("GLB", "glb", "model/gltf-binary", Msgs.msgExportFormatPurposeGlb()),
    THREE_MF("3MF", "3mf", "model/3mf", Msgs.msgExportFormatPurposeThreeMf()),
    STL("STL", "stl", "model/stl", Msgs.msgExportFormatPurposeStl()),

    /**
     * JT has **no registered media type**: `model/jt` is passed around in the wild but names no registry
     * entry, and minting a `model` subtype nobody can look up is a claim the bytes cannot back. So the
     * download carries the truthful one — "a byte stream this transport does not name" — and the extension
     * says the rest, which is what every consumer of a JT file keys on anyway.
     */
    JT("JT", "jt", "application/octet-stream", Msgs.msgExportFormatPurposeJt()),
}

/**
 * What an export produced: the [bytes] to write, the [fileName] to write them under, and the one line the
 * status bar says about it — or, when nothing could be exported, [bytes] null and [message] the refusal.
 */
class ExportResult(
    val format: ExportFormat,
    val fileName: String,
    val bytes: ByteArray?,
    val message: String,
) {
    val ok: Boolean get() = bytes != null
}

/**
 * The one entry point both the browser shell and the tests call: **document in, bytes out**.
 *
 * Everything a caller could get wrong lives here rather than in the shell — which scene is exported, what the
 * file is called, what the status line says, and every refusal. The browser's whole contribution is triggering
 * a download with the bytes it is handed, which is why the export flow is headlessly testable end to end.
 */
object Exports {
    fun export(
        doc: Document,
        docName: String,
        format: ExportFormat,
        ev: Evaluator = Evaluator(),
    ): ExportResult {
        val scene = ExportScene.extract(doc, docName, ev)
        val fileName = "$docName.${format.extension}"
        scene.refusal?.let { return ExportResult(format, fileName, null, it) }
        val bytes =
            when (format) {
                ExportFormat.GLB -> Glb.write(scene)
                // **The two print formats refuse an open shell, by name and with the way out** (OP-9). The
                // check is the same one for both ([ThreeMf.check], over `Watertight`), and it refuses the
                // *whole* export rather than skipping the body: a print file quietly missing a part is the
                // kind of surprise that is discovered on the print bed. Every other format writes it — see
                // [openShellNote].
                ExportFormat.THREE_MF ->
                    ThreeMf.check(scene)?.let {
                        return ExportResult(format, fileName, null, Messages.refusalExportNotExported(reason = it))
                    } ?: ThreeMf.write(scene)
                ExportFormat.STL ->
                    Stl.check(scene)?.let {
                        return ExportResult(format, fileName, null, Messages.refusalExportNotExported(reason = it))
                    } ?: Stl.write(scene)
                // The sibling library refuses, by name, any scene its own reader would hand back differently
                // (a node with geometry *and* children, an undeclared unit, a child its collapse would splice
                // out). The adapter is built so none of those is reachable — but a refusal that escapes as a
                // crash is a refusal that does not speak, so it is caught here and relayed in the same words
                // the 3MF check is relayed in (OP-9).
                ExportFormat.JT ->
                    try {
                        Jt.write(scene)
                    } catch (e: JtWriteException) {
                        return ExportResult(
                            format,
                            fileName,
                            null,
                            Messages.refusalExportNotExported(reason = e.message ?: ""),
                        )
                    }
            }
        val said = scene.notes + openShellNote(scene)
        val notesArg =
            if (said.isEmpty()) {
                Msg.EMPTY
            } else {
                Msgs.phraseParenNote(notes = Msg.joined(said.map { Msg.text(it) }, "; "))
            }
        return ExportResult(
            format,
            fileName,
            bytes,
            Messages.msgExportExported(
                fileName = fileName,
                bodyCount = scene.nodes.size,
                triangleCount = scene.triangleCount,
                notes = notesArg,
            ),
        )
    }

    /**
     * What a format that **can** carry an open shell has to say about having carried one — nothing when the
     * scene holds none.
     *
     * GLB and JT are viewing and interchange formats: an open shell displays, orbits, and travels to another
     * CAD system perfectly well, so refusing it would be the wrong answer (the user's own framing —
     * *"too restrictive, if the goal is only arranging and displaying"*). What would be wrong is writing one
     * **silently**, since the file then claims to be a solid to a reader who cannot tell. So the result says
     * it, by name, the way every other export note is said: silence still means every body in the file is a
     * closed solid.
     */
    private fun openShellNote(scene: ExportScene): List<String> {
        val open = scene.nodes.filter { Watertight.defect(it.mesh) != null }.map { it.name }
        if (open.isEmpty()) return emptyList()
        return listOf(Messages.noteExportOpenShell(count = open.size, names = open.joinToString(", ")))
    }
}
