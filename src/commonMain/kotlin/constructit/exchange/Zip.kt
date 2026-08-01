package constructit.exchange

/**
 * A minimal ZIP writer — **stored entries only, no compression**.
 *
 * Store (method 0) is legal ZIP, legal OPC and legal 3MF: the 3MF core spec inherits OPC's container rules,
 * which allow Store and Deflate, and every unzipper and slicer reads both. Deflate would need either a
 * hand-written compressor in `commonMain` or an `expect/actual` seam over each platform's own — real work for
 * a file that is a few hundred kilobytes of ASCII, and a seam the rest of this package deliberately does not
 * have (every writer here is a pure byte producer, which is why they are all testable on the JVM and all
 * usable in the browser). If a compressed 3MF is ever wanted, the honest shape is that seam, not a
 * `commonMain` inflate/deflate of our own.
 *
 * No ZIP64 and no data descriptors: sizes and CRCs are known before an entry is written, because every entry
 * is built in memory first.
 */
class Zip {
    private class Entry(val name: ByteArray, val data: ByteArray, val crc: Int, val offset: Int)

    private companion object {
        /**
         * A **fixed** modification stamp: 1980-01-01 00:00, the earliest a DOS timestamp can express.
         *
         * A clock in the bytes is a golden that cannot hold, and an export whose bytes differ between two runs
         * of the same model cannot be diffed, cached or checksummed. The date field is valid rather than zero
         * because a zero DOS date means "day 0 of month 0", which some readers convert and some reject.
         */
        const val DOS_TIME = 0
        const val DOS_DATE = 0x21
    }

    private val sink = ByteSink(64 * 1024)
    private val entries = ArrayList<Entry>()

    /** Add one stored entry. [path] is a forward-slash path, as ZIP and OPC both require. */
    fun add(
        path: String,
        content: ByteArray,
    ): Zip {
        val name = ByteSink.ascii(path)
        val crc = Crc32.of(content)
        val at = sink.size
        // local file header
        sink.u32(0x04034b50)
        sink.u16(10) // version needed: 1.0 — store, no features
        sink.u16(0) // no flags: no encryption, no data descriptor, name is not UTF-8-flagged
        sink.u16(0) // method 0 = stored
        sink.u16(DOS_TIME).u16(DOS_DATE)
        sink.u32(crc).u32(content.size).u32(content.size)
        sink.u16(name.size).u16(0)
        sink.bytes(name).bytes(content)
        entries.add(Entry(name, content, crc, at))
        return this
    }

    fun add(
        path: String,
        text: String,
    ): Zip = add(path, ByteSink.ascii(text))

    /** The archive: the entries written so far, plus the central directory and the end record. */
    fun toByteArray(): ByteArray {
        val start = sink.size
        for (e in entries) {
            sink.u32(0x02014b50)
            sink.u16(20).u16(10) // made by / needed
            sink.u16(0).u16(0) // flags, method (stored)
            sink.u16(DOS_TIME).u16(DOS_DATE)
            sink.u32(e.crc).u32(e.data.size).u32(e.data.size)
            sink.u16(e.name.size).u16(0).u16(0)
            sink.u16(0).u16(0).u32(0) // disk, attributes
            sink.u32(e.offset)
            sink.bytes(e.name)
        }
        val dirSize = sink.size - start
        sink.u32(0x06054b50)
        sink.u16(0).u16(0)
        sink.u16(entries.size).u16(entries.size)
        sink.u32(dirSize).u32(start)
        sink.u16(0) // no archive comment
        return sink.toByteArray()
    }
}
