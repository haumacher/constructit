package constructit.exchange

import constructit.geom.Mesh3
import constructit.geom.Tri
import constructit.geom.Vec3

/**
 * **A mesh as one word of the construction script** — the encoding an imported body's step carries.
 *
 * The drawing file is a text journal of the steps that built it, and every other step writes a *description*
 * that replay re-runs. An imported body has no description: its geometry came from outside, so the only
 * honest thing the step can hold is the geometry itself. Which is the whole reason this exists — the step
 * embeds the **extracted mesh** and never the file's bytes, so replaying a drawing never re-runs a reader
 * and a library upgrade cannot silently change a drawing somebody drew a year ago (the queue entry's own
 * reason; the same rule as OP-23's *recorded, never discovered*, one layer down).
 *
 * **The layout**, stated because a stored literal's meaning is frozen the moment a build that could write it
 * ships (OP-18):
 *
 * ```
 * offset  bytes  meaning
 *      0      4  the ASCII magic "CIM1" — the version of *this* layout, not of the file format
 *      4      4  int32   vertex count  V
 *      8      4  int32   triangle count T
 *     12   24*V  float64 x, y, z per vertex, in millimetres, in mesh order
 *  12+24V  12*T  int32   a, b, c per triangle — indices into the vertices, in mesh order
 * ```
 *
 * Everything little-endian, and every number a **float64**, which is what [Mesh3] holds: a narrower field
 * would round somebody's geometry on every save, and a mesh that changes when it is written down is not a
 * literal. The bytes are then Base64 in the standard alphabet with padding, so the whole mesh is one word a
 * space-delimited step can carry and a diff can see the size of.
 *
 * Base64 is spelled out here rather than taken from the standard library on purpose: the stdlib's encoder is
 * still an experimental API in this toolchain, and the *file format* is not the place to depend on one. It
 * is thirty lines and it is frozen.
 *
 * Deliberately **not compressed and not de-duplicated**. Ten instances of one bolt store ten meshes, and a
 * quarter-million triangles is megabytes of `.cit` — which is accepted and recorded rather than optimised
 * away, because every trick that would shrink it (quantise the coordinates, share a mesh between steps,
 * deflate the block) either loses precision or makes one step's meaning depend on another's.
 */
object MeshText {
    private const val MAGIC = "CIM1"

    /** [mesh] as one Base64 word — see the layout in this object's note. */
    fun encode(mesh: Mesh3): String {
        val sink = ByteSink(16 + mesh.vertexCount * 24 + mesh.triangleCount * 12)
        for (c in MAGIC) sink.u8(c.code)
        sink.u32(mesh.vertexCount)
        sink.u32(mesh.triangleCount)
        for (v in mesh.vertices) {
            f64(sink, v.x)
            f64(sink, v.y)
            f64(sink, v.z)
        }
        for (t in mesh.triangles) {
            sink.u32(t.a)
            sink.u32(t.b)
            sink.u32(t.c)
        }
        return base64(sink.toByteArray())
    }

    /**
     * The mesh a step wrote, bit for bit — or null when [text] is not one.
     *
     * Null rather than an exception because the caller is a *loader*, and a loader turns this into the load
     * error that names the line (`DocumentFormat.LoadError`); an exception thrown from here would name the
     * codec instead of the file.
     */
    fun decode(text: String): Mesh3? {
        val bytes = unbase64(text) ?: return null
        if (bytes.size < 12) return null
        for ((i, c) in MAGIC.withIndex()) if (bytes[i].toInt() and 0xff != c.code) return null
        val vertexCount = int32(bytes, 4)
        val triangleCount = int32(bytes, 8)
        if (vertexCount < 0 || triangleCount < 0) return null
        if (bytes.size != 12 + vertexCount * 24 + triangleCount * 12) return null
        val vertices = ArrayList<Vec3>(vertexCount)
        var at = 12
        for (i in 0 until vertexCount) {
            vertices.add(Vec3(float64(bytes, at), float64(bytes, at + 8), float64(bytes, at + 16)))
            at += 24
        }
        val triangles = ArrayList<Tri>(triangleCount)
        for (i in 0 until triangleCount) {
            val a = int32(bytes, at)
            val b = int32(bytes, at + 4)
            val c = int32(bytes, at + 8)
            if (a !in 0 until vertexCount || b !in 0 until vertexCount || c !in 0 until vertexCount) return null
            triangles.add(Tri(a, b, c))
            at += 12
        }
        return Mesh3(vertices, triangles)
    }

    /**
     * One IEEE-754 double, little-endian — [ByteSink] writes 32-bit words, so this is two of them, low
     * half first. Written here rather than added to the sink because it is this format's business: the
     * three binary export writers all store single precision, and a mesh that is a *literal of the drawing*
     * is the one thing that must not be narrowed.
     */
    private fun f64(
        sink: ByteSink,
        v: Double,
    ) {
        val bits = v.toRawBits()
        sink.u32((bits and 0xffffffffL).toInt())
        sink.u32((bits ushr 32).toInt())
    }

    private fun int32(
        b: ByteArray,
        at: Int,
    ): Int =
        (b[at].toInt() and 0xff) or
            ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or
            ((b[at + 3].toInt() and 0xff) shl 24)

    private fun float64(
        b: ByteArray,
        at: Int,
    ): Double {
        var bits = 0L
        for (i in 7 downTo 0) bits = (bits shl 8) or (b[at + i].toLong() and 0xff)
        return Double.fromBits(bits)
    }

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** RFC 4648 Base64, standard alphabet, padded — the one spelling this format writes. */
    fun base64(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xff) shl 16) or ((bytes[i + 1].toInt() and 0xff) shl 8) or (bytes[i + 2].toInt() and 0xff)
            out.append(ALPHABET[(n ushr 18) and 63]).append(ALPHABET[(n ushr 12) and 63])
            out.append(ALPHABET[(n ushr 6) and 63]).append(ALPHABET[n and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xff) shl 16
                out.append(ALPHABET[(n ushr 18) and 63]).append(ALPHABET[(n ushr 12) and 63]).append("==")
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xff) shl 16) or ((bytes[i + 1].toInt() and 0xff) shl 8)
                out.append(ALPHABET[(n ushr 18) and 63]).append(ALPHABET[(n ushr 12) and 63])
                out.append(ALPHABET[(n ushr 6) and 63]).append('=')
            }
        }
        return out.toString()
    }

    /** The inverse of [base64], or null for anything that is not exactly that spelling. */
    fun unbase64(text: String): ByteArray? {
        if (text.length % 4 != 0) return null
        val body = text.trimEnd('=')
        val pad = text.length - body.length
        if (pad > 2) return null
        val out = ByteArray(text.length / 4 * 3 - pad)
        var acc = 0
        var bits = 0
        var at = 0
        for (c in body) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[at++] = ((acc ushr bits) and 0xff).toByte()
            }
        }
        return if (at == out.size) out else null
    }
}
