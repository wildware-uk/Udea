package dev.wildware.udea.assets.pack

import dev.wildware.udea.assets.AssetGraphLog
import dev.wildware.udea.assets.AssetRegistry
import java.io.Closeable
import java.io.RandomAccessFile
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Where a bundle's bytes come from.
 *
 * The reason this is an interface rather than a `ByteArray` is the second half of issue #89's
 * reader scope: *"eager set first, remainder streamed"*. Streaming is only a real thing if the
 * streamed sections were never in memory, and that needs a source that can be asked for a range
 * after the eager pass has finished. A `ByteArray` source exists too, for tests and for a
 * bundle that arrived over the wire.
 */
public interface BundleSource : Closeable {

    /** Total bytes. */
    public val size: Long

    /** [length] bytes at [offset]. */
    public fun read(offset: Long, length: Int): ByteArray

    public companion object {

        /** A source over bytes already in memory. Closing it does nothing. */
        public fun of(bytes: ByteArray): BundleSource = ByteArraySource(bytes)

        /** A source over a file on disk. Sections are read on demand; close it when done. */
        public fun of(path: Path): BundleSource = FileSource(path)
    }
}

private class ByteArraySource(private val bytes: ByteArray) : BundleSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > bytes.size) {
            throw BundleCorruptException("$length byte(s) at $offset are outside a ${bytes.size}-byte bundle")
        }
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    override fun close(): Unit = Unit
}

private class FileSource(path: Path) : BundleSource {
    private val file = RandomAccessFile(path.toFile(), "r")

    override val size: Long = file.length()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > size) {
            throw BundleCorruptException("$length byte(s) at $offset are outside a $size-byte bundle")
        }
        val buffer = ByteArray(length)
        file.seek(offset)
        file.readFully(buffer)
        return buffer
    }

    override fun close(): Unit = file.close()
}

/**
 * An opened `.udeapak`.
 *
 * The graph and the atlas table are decoded during [BundleReader.open]; every other section is
 * a lookup in [toc] and a read from the source, so the size of the game's audio has no bearing
 * on how long it takes to reach the first frame.
 */
public class Bundle internal constructor(
    private val source: BundleSource,
    /** Every section, in the order the bundle stores them: eager first, then streamed. */
    public val toc: List<TocEntry>,
    /** The sha256 the writer recorded over everything after the header. */
    public val contentHash: ByteArray,
    /** The decoded graph, with every reference already bound to its index. */
    public val registry: AssetRegistry,
    public val atlas: AtlasIndex,
    private val eager: Map<String, ByteArray>,
) : Closeable {

    private val byName: Map<String, TocEntry> = toc.associateBy { it.name }

    /** Total bytes of the [EntryClass.EAGER] sections: what a byte-denominated load bar counts. */
    public val eagerBytes: Long get() = toc.filter { it.entryClass == EntryClass.EAGER }.sumOf { it.length.toLong() }

    /** Total bytes left on disk until something asks for them. */
    public val streamedBytes: Long
        get() = toc.filter { it.entryClass == EntryClass.STREAMED }.sumOf { it.length.toLong() }

    public fun entry(name: String): TocEntry? = byName[name]

    /** Sections of [kind], in bundle order. */
    public fun sections(kind: SectionKind): List<TocEntry> = toc.filter { it.kind == kind }

    /**
     * The bytes of section [name]: from memory when it was eager, from the source when it was
     * not. A streamed section is re-read on every call rather than cached, because the cache
     * policy belongs to whoever knows the memory budget, and it is not this class.
     */
    public fun section(name: String): ByteArray {
        eager[name]?.let { return it.copyOf() }
        val entry = byName[name] ?: throw UnknownSectionException(name, toc.map { it.name })
        return source.read(entry.offset, entry.length)
    }

    /** PNG bytes of atlas page [page]. */
    public fun atlasPage(page: Int): ByteArray = section(BundleFormat.atlasPageSection(page))

    override fun close(): Unit = source.close()

    override fun toString(): String =
        "Bundle(${toc.size} sections, ${registry.size} assets, ${atlas.pages.size} atlas pages)"
}

/**
 * Reads a `.udeapak` in one linear pass.
 *
 * The order of the checks is the contract, not an implementation detail: magic, then version,
 * then anything else. Issue #89 requires a version mismatch to be *"a typed error, not an
 * exception from mid-parse"*, and the only way to guarantee that is never to have parsed
 * anything when the version is read - so the version sits at a fixed offset in a fixed-size
 * header, ahead of the table of contents whose own encoding a future version may change.
 */
public object BundleReader {

    /** Opens the bundle at [path], streaming what the pack marked streamable. */
    public fun open(path: Path, codecs: AssetCodecs = AssetCodecs.Builtin): Bundle =
        open(BundleSource.of(path), codecs)

    /** Opens a bundle already in memory. */
    public fun open(bytes: ByteArray, codecs: AssetCodecs = AssetCodecs.Builtin): Bundle =
        open(BundleSource.of(bytes), codecs)

    public fun open(source: BundleSource, codecs: AssetCodecs = AssetCodecs.Builtin): Bundle {
        var closeOnFailure = true
        try {
            if (source.size < BundleFormat.HEADER_SIZE) {
                throw BundleCorruptException(
                    "a bundle is at least ${BundleFormat.HEADER_SIZE} bytes; this one is ${source.size}",
                )
            }
            val header = ByteCursor(source.read(0, BundleFormat.HEADER_SIZE), "header")
            val magic = header.bytes(BundleFormat.MAGIC.size)
            if (!magic.contentEquals(BundleFormat.MAGIC)) {
                throw BundleMagicException(magic.joinToString("") { printable(it) })
            }
            val version = header.i32()
            if (version != BundleFormat.VERSION) throw BundleVersionException(version)
            header.i32() // flags, reserved; a reader of version 1 has no bit to read here.
            val contentHash = header.bytes(BundleFormat.CONTENT_HASH_SIZE)

            val toc = readToc(source)
            verifyLayout(toc, source.size)

            val eager = toc.filter { it.entryClass == EntryClass.EAGER }
                .associate { it.name to source.read(it.offset, it.length) }

            val graphBytes = eager[BundleFormat.GRAPH_SECTION]
                ?: throw BundleCorruptException(
                    "no eager '${BundleFormat.GRAPH_SECTION}' section; a bundle whose graph is " +
                        "streamed could not be opened at all",
                )
            val decoded = GraphSection.decode(graphBytes, codecs)
            val registry = AssetRegistry(decoded.values, contentHash, AssetGraphLog())
            registry.bindPacked(decoded.binder)

            val atlas = eager[BundleFormat.ATLAS_SECTION]?.let(AtlasIndex::decode) ?: AtlasIndex.EMPTY
            closeOnFailure = false
            return Bundle(source, toc, contentHash, registry, atlas, eager)
        } finally {
            // A half-opened bundle must not leak the file handle it opened, and the caller has
            // no object to close because `open` did not return one.
            if (closeOnFailure) runCatching { source.close() }
        }
    }

    /**
     * Recomputes the sha256 over everything after the header and compares it to what the
     * header claims.
     *
     * Separate from [open] on purpose. It reads the whole file, which is exactly what streaming
     * exists to avoid, so it is what a hot-reload handshake or an installer calls - not what
     * every launch pays for. Spec 3.6's clients compare *this* number on connect.
     */
    public fun verifyContentHash(source: BundleSource): Boolean {
        val hashAt = (BundleFormat.HEADER_SIZE - BundleFormat.CONTENT_HASH_SIZE).toLong()
        val stored = source.read(hashAt, BundleFormat.CONTENT_HASH_SIZE)
        return contentHashOf(source).contentEquals(stored)
    }

    /** The sha256 of everything after the header, which is what the header stores. */
    public fun contentHashOf(source: BundleSource): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        var at = BundleFormat.HEADER_SIZE.toLong()
        while (at < source.size) {
            val chunk = minOf(HASH_CHUNK.toLong(), source.size - at).toInt()
            digest.update(source.read(at, chunk))
            at += chunk
        }
        return digest.digest()
    }

    private const val HASH_CHUNK = 1 shl 16

    private fun readToc(source: BundleSource): List<TocEntry> {
        val countBytes = source.read(BundleFormat.HEADER_SIZE.toLong(), Int.SIZE_BYTES)
        val count = ByteCursor(countBytes, "toc").count("section")
        // The TOC is variable-length, so its own size is not known before it is read. Reading
        // it in one go needs an upper bound: the largest a `count`-entry table could be, capped
        // at what is actually left in the file.
        val available = (source.size - BundleFormat.HEADER_SIZE - Int.SIZE_BYTES).coerceAtLeast(0)
        val bound = minOf(count.toLong() * MAX_TOC_ENTRY_SIZE, available).toInt()
        val cursor = ByteCursor(
            source.read(BundleFormat.HEADER_SIZE.toLong() + Int.SIZE_BYTES, bound),
            "toc",
        )
        return List(count) {
            val kindCode = cursor.u8()
            val kind = SectionKind.ofCode(kindCode)
                ?: cursor.corrupt("section kind $kindCode is not one this build knows")
            val classCode = cursor.u8()
            val entryClass = EntryClass.ofCode(classCode)
                ?: cursor.corrupt("section class $classCode is not EAGER or STREAMED")
            val name = cursor.utf8(cursor.u16())
            TocEntry(kind, name, cursor.i64(), cursor.count("section '$name' length"), entryClass)
        }
    }

    /** kind + class + u16 name length + a name no longer than u16 + offset + length. */
    private const val MAX_TOC_ENTRY_SIZE = 1 + 1 + 2 + 0xFFFF + 8 + 4

    /**
     * Rejects a TOC that overlaps itself, runs past the end, or names a section twice.
     *
     * Checked before any section is read. Without it a crafted bundle could make two sections
     * alias the same bytes, and the failure would surface as a confusing decode error in
     * whichever one happened to be read second.
     */
    private fun verifyLayout(toc: List<TocEntry>, size: Long) {
        val duplicates = toc.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw BundleCorruptException("these sections are named twice: ${duplicates.sorted()}")
        }
        var previousEnd = 0L
        for (entry in toc.sortedBy { it.offset }) {
            if (entry.offset < previousEnd) {
                throw BundleCorruptException(
                    "section '${entry.name}' starts at ${entry.offset}, inside the section before it",
                )
            }
            if (entry.end > size) {
                throw BundleCorruptException(
                    "section '${entry.name}' ends at ${entry.end}, past the $size-byte end of the bundle",
                )
            }
            previousEnd = entry.end
        }
    }

    private fun printable(byte: Byte): String {
        val code = byte.toInt() and 0xFF
        return if (code in 0x20..0x7E) code.toChar().toString() else "\\x%02x".format(code)
    }
}
