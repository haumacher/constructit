package constructit.exchange

import constructit.geom.Curve3Element
import constructit.geom.Curves3
import constructit.geom.Path3
import constructit.geom.Vec3

/**
 * **A run as one word of the construction script** — the encoding an imported *curve*'s step carries, and
 * the exact twin of [MeshText] (OP-26, step 9).
 *
 * The reason is [MeshText]'s own, word for word: an imported curve has no construction. The file said
 * points, so the only honest thing the step can hold is those points — and it holds the **extracted
 * polyline**, never the file's bytes, so replaying a drawing never re-runs a reader and a library upgrade
 * cannot silently change a drawing somebody drew a year ago.
 *
 * **The layout**, stated because a stored literal's meaning is frozen the moment a build that could write it
 * ships (OP-18):
 *
 * ```
 * offset  bytes  meaning
 *      0      4  the ASCII magic "CIP1" — the version of *this* layout, not of the file format
 *      4      4  int32   point count P
 *      8      4  int32   1 when the run closes back onto its first point, 0 when it is open
 *     12   24*P  float64 x, y, z per point, in millimetres, in run order
 * ```
 *
 * Little-endian, float64, Base64 in the standard alphabet — the same three decisions [MeshText] argues, for
 * the same reasons, and through the same two functions ([MeshText.base64]), so the two literals can never
 * disagree about what a byte is.
 *
 * **Points, not pieces**, and that is the one thing this format states which the mesh's does not. A wireframe
 * body is a *polyline*: the file lists positions and says which of them are joined, so a chain of
 * [Curve3Element.Seg3] is exactly what it said and nothing has been fitted, smoothed or recognised on the way
 * in. Storing the pieces instead of the points would store each shared endpoint twice and let a reader write
 * a chain whose pieces do not meet — which a `Path3` may not be. A piece kind an import cannot produce is
 * therefore not encodable here at all, by construction rather than by omission: [encode] refuses anything but
 * a segment chain, and the one caller only ever hands it one.
 */
object PathText {
    private const val MAGIC = "CIP1"

    /**
     * [path] as one Base64 word, or null when it is not a polyline — see the layout in this object's note.
     *
     * Null rather than an exception for [MeshText.decode]'s reason on the other side: the caller is building a
     * *step*, and a step that cannot be written is the document's error to name, not this codec's.
     */
    fun encode(path: Path3): String? {
        val pts = pointsOf(path) ?: return null
        val sink = ByteSink(12 + pts.size * 24)
        for (c in MAGIC) sink.u8(c.code)
        sink.u32(pts.size)
        sink.u32(if (path.closed) 1 else 0)
        for (p in pts) {
            f64(sink, p.x)
            f64(sink, p.y)
            f64(sink, p.z)
        }
        return MeshText.base64(sink.toByteArray())
    }

    /** The run a step wrote, bit for bit — or null when [text] is not one. */
    fun decode(text: String): Path3? {
        val bytes = MeshText.unbase64(text) ?: return null
        if (bytes.size < 12) return null
        for ((i, c) in MAGIC.withIndex()) if (bytes[i].toInt() and 0xff != c.code) return null
        val count = int32(bytes, 4)
        val closed = int32(bytes, 8)
        if (count < 2 || closed !in 0..1) return null
        if (bytes.size != 12 + count * 24) return null
        val pts = ArrayList<Vec3>(count)
        var at = 12
        for (i in 0 until count) {
            pts.add(Vec3(float64(bytes, at), float64(bytes, at + 8), float64(bytes, at + 16)))
            at += 24
        }
        val elements = Curves3.straightThrough(pts, closed == 1)
        if (elements.isEmpty()) return null
        return Path3(elements, closed == 1)
    }

    /**
     * The points [path] is stated by — its pieces' shared endpoints — or null when a piece is not a segment.
     *
     * The inverse of [Curves3.straightThrough], and it asserts what that function guarantees: consecutive
     * pieces hand over the *identical* value, so the point list is the first piece's start followed by every
     * piece's end (with a closed run's last piece handing back to the first point, which is therefore not
     * written twice).
     */
    fun pointsOf(path: Path3): List<Vec3>? {
        val segs = path.elements.map { it as? Curve3Element.Seg3 ?: return null }
        if (segs.isEmpty()) return null
        val pts = ArrayList<Vec3>(segs.size + 1)
        pts.add(segs[0].start)
        for (s in segs) pts.add(s.end)
        if (path.closed) pts.removeAt(pts.size - 1)
        return pts
    }

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
}
