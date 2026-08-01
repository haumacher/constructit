package constructit.exchange

/**
 * A growable little-endian byte buffer — the whole platform layer the three export writers need.
 *
 * All three formats in this package are **little-endian binary or text in a binary container**, and none of
 * them needs anything a platform provides: no file system, no compression, no character set beyond ASCII. So
 * the writers live in `commonMain` and produce a `ByteArray`, and the only thing either platform contributes
 * is what to *do* with those bytes (a download in the browser, a file in a test). That is the seam the export
 * package is built on, and this class is its floor.
 *
 * `Float.toRawBits`/`Double.toRawBits` are common stdlib, so IEEE-754 encoding needs no `expect/actual`
 * either.
 */
class ByteSink(initial: Int = 1024) {
    private var buf = ByteArray(initial.coerceAtLeast(16))
    private var len = 0

    val size: Int get() = len

    private fun room(extra: Int) {
        if (len + extra <= buf.size) return
        var n = buf.size * 2
        while (n < len + extra) n *= 2
        buf = buf.copyOf(n)
    }

    fun u8(v: Int): ByteSink {
        room(1)
        buf[len++] = (v and 0xff).toByte()
        return this
    }

    fun u16(v: Int): ByteSink {
        u8(v)
        return u8(v ushr 8)
    }

    /** A 32-bit little-endian word. Unsigned by intent; [Int] carries the bits either way. */
    fun u32(v: Int): ByteSink {
        u16(v)
        return u16(v ushr 16)
    }

    fun f32(v: Double): ByteSink = u32(v.toFloat().toRawBits())

    fun bytes(b: ByteArray): ByteSink {
        room(b.size)
        b.copyInto(buf, len)
        len += b.size
        return this
    }

    /** ASCII text. Every byte this package writes as text is ASCII by construction — see [ascii]. */
    fun text(s: String): ByteSink = bytes(ascii(s))

    /** Pad to a multiple of [align] with [filler]. */
    fun pad(
        align: Int,
        filler: Int = 0,
    ): ByteSink {
        while (len % align != 0) u8(filler)
        return this
    }

    fun toByteArray(): ByteArray = buf.copyOf(len)

    companion object {
        /**
         * [s] as bytes, **UTF-8**, hand-encoded because `String.encodeToByteArray` is what common Kotlin
         * offers and it is exactly UTF-8 — used rather than assumed, so a name with a non-ASCII character in
         * it (a part called `Gehäuse`) lands in the file as the JSON and XML specs require.
         */
        fun ascii(s: String): ByteArray = s.encodeToByteArray()
    }
}

/**
 * CRC-32 (IEEE 802.3, the ZIP/PNG polynomial), in twenty lines — because a ZIP entry's header must carry
 * one and `commonMain` has no `java.util.zip`.
 *
 * The table is built once on first use from the reflected polynomial `0xEDB88320`; that is the same table
 * every implementation of this checksum uses, so a 3MF this package writes opens in any unzipper.
 */
object Crc32 {
    private val table =
        IntArray(256) { i ->
            var c = i
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor -0x12477ce0 else c ushr 1 }
            c
        }

    fun of(data: ByteArray): Int {
        var c = -1
        for (b in data) c = table[(c xor b.toInt()) and 0xff] xor (c ushr 8)
        return c.inv()
    }
}
