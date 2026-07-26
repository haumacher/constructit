package constructit.editor

/**
 * The drawing's **name** — and the one decision recorded about it: it is *shell* state and is
 * deliberately **not part of the document format** (OP-18).
 *
 * The file is a construction script: the sequence of steps that built the drawing. A name is not a step
 * and it constructs nothing, so writing it into the file would put a second, non-constructive kind of
 * fact in there — and one that the filesystem already holds, better: rename the file and a name inside it
 * is a lie, copy the file and the copy claims to be the original. So the name belongs where the bytes
 * live, and what a `save → load → save` round trip must reproduce is unaffected by it.
 *
 * What *is* shared code is the naming arithmetic, because it is the same in every shell and it is worth
 * testing headlessly: the default, what a typed name normalizes to, and how a name is read back off a file
 * the user picked. The platform half — a download, or the File System Access API's handle — stays in
 * `jsMain`, which is the only place that can have it.
 */
object DocumentName {
    /** What an unnamed drawing is called. */
    const val DEFAULT = "drawing"

    /** The construction-script extension; a name never carries it, a file name always does. */
    const val EXTENSION = ".cit"

    /** Long enough for a sentence-like name, short enough to stay a usable file name. */
    private const val MAX = 64

    /** Characters no file name can carry (and a path separator is handled before this). */
    private const val ILLEGAL = "/\\:*?\"<>|"

    /**
     * The name a typed string means: trimmed, stripped of any directory part and of a trailing
     * [EXTENSION] (so typing `plan.cit` does not save `plan.cit.cit`), with characters a file name cannot
     * hold replaced by `-`, and [DEFAULT] when nothing is left. Spaces are kept — a file name may hold
     * them, and a group's "one word" rule comes from the *file format* (OP-18), which this is not part of.
     */
    fun normalize(raw: String): String {
        val base = raw.trim().substringAfterLast('/').substringAfterLast('\\')
        val stem = if (base.length > EXTENSION.length && base.endsWith(EXTENSION, ignoreCase = true)) base.dropLast(EXTENSION.length) else base
        val cleaned =
            stem.map { c -> if (c in ILLEGAL || c.code < 0x20) '-' else c }
                .joinToString("")
                .trim()
                .take(MAX)
                .trim()
        return cleaned.ifEmpty { DEFAULT }
    }

    /**
     * The name to take from a file the user picked: its base name without the extension. Only the **last**
     * extension goes, so `house.rev2.cit` is called `house.rev2`; a leading dot is not an extension.
     */
    fun fromFileName(fileName: String): String {
        val base = fileName.trim().substringAfterLast('/').substringAfterLast('\\')
        val dot = base.lastIndexOf('.')
        return normalize(if (dot > 0) base.substring(0, dot) else base)
    }

    /** The file [name] is saved as. */
    fun fileName(name: String): String = normalize(name) + EXTENSION
}
