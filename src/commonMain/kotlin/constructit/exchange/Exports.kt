package constructit.exchange

import constructit.core.Evaluator
import constructit.editor.Document

/**
 * The three files this build writes. One entry per shipped format and nothing "for later": a format flag with
 * no writer behind it is a promise the app cannot keep.
 *
 * Deliberately absent, recorded so it is not looked for: **STEP** (the kernel is mesh-based and holds no exact
 * B-rep for solids, so an exact-geometry export would be either dishonest or a compliance project) and **JT**
 * (a separate Kotlin multiplatform library by the user's decision — https://github.com/haumacher/kotlinJT —
 * which will consume the very [ExportScene] this package hands its own writers, so the adapter is a page when
 * the library exists).
 */
enum class ExportFormat(
    val label: String,
    val extension: String,
    /** The MIME type a browser download should carry. */
    val mimeType: String,
    /** What this format is *for*, in the words the button's tooltip uses. */
    val purpose: String,
) {
    GLB("GLB", "glb", "model/gltf-binary", "for viewing: glTF 2.0, one node per solid, PBR materials"),
    THREE_MF("3MF", "3mf", "model/3mf", "for printing: units and manifold orientation are part of the spec"),
    STL("STL", "stl", "model/stl", "the universal fallback: triangles only, millimetres by convention"),
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
                // watertight-or-refused, checked at the boundary (OP-9) and refused **by name**
                ExportFormat.THREE_MF ->
                    ThreeMf.check(scene)?.let { return ExportResult(format, fileName, null, "not exported — $it") }
                        ?: ThreeMf.write(scene)
                ExportFormat.STL -> Stl.write(scene)
            }
        val bodies = "${scene.nodes.size} solid${if (scene.nodes.size == 1) "" else "s"}"
        val notes = if (scene.notes.isEmpty()) "" else " (${scene.notes.joinToString("; ")})"
        return ExportResult(
            format,
            fileName,
            bytes,
            "Exported $fileName — $bodies, ${scene.triangleCount} triangles$notes",
        )
    }
}
