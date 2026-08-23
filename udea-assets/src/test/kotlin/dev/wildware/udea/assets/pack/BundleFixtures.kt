package dev.wildware.udea.assets.pack

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * A minimal bundle builder, written independently of `udea-assets-compiler`'s `BundleWriter`.
 *
 * The duplication is the point. `udea-assets` must not depend on the compiler (UDEA-MG-006
 * allows it three dependencies and that is not one of them), so the reader's own tests cannot
 * use the real writer - and if they could, a format bug that both sides shared would be
 * invisible. These bytes are laid out from `BundleFormat`'s documented layout by hand, which is
 * the only way the reader's tests can disagree with the writer.
 *
 * `udea-assets-compiler`'s `BundleRoundTripTest` is the other half: real writer, real reader.
 */
internal object BundleFixtures {

    /** A graph section holding [assets] as `(id, kind, fields)` with only text values. */
    fun graphSection(assets: List<Triple<String, String, Map<String, String>>>): ByteArray {
        val strings = (
            assets.flatMap { (id, kind, fields) ->
                listOf(id, kind) + fields.keys + fields.values
            }
            ).distinct().sorted()
        val index = strings.withIndex().associate { (at, value) -> value to at }
        val out = ByteArrayOutputStream()
        out.int(strings.size)
        strings.forEach {
            val bytes = it.toByteArray(Charsets.UTF_8)
            out.int(bytes.size)
            out.write(bytes)
        }
        out.int(assets.size)
        assets.forEach { (id, kind, fields) ->
            out.int(index.getValue(id))
            out.int(index.getValue(kind))
            out.int(fields.size)
            fields.toSortedMap().forEach { (name, value) ->
                out.int(index.getValue(name))
                out.write(ValueTag.TEXT)
                out.int(index.getValue(value))
            }
        }
        return out.toByteArray()
    }

    /** An empty atlas index section: no strings, no pages, no regions, no sheets. */
    fun emptyAtlasSection(): ByteArray = ByteArrayOutputStream().apply {
        int(0) // strings
        int(0) // pages
        int(0) // regions
        int(0) // sheets
    }.toByteArray()

    /** A whole bundle over [sections], with a correct header and content hash. */
    fun bundle(
        sections: List<Triple<SectionKind, String, ByteArray>>,
        version: Int = BundleFormat.VERSION,
        magic: ByteArray = BundleFormat.MAGIC,
        eager: Set<String> = sections.map { it.second }.toSet(),
    ): ByteArray {
        val tocSize = sections.sumOf { 1 + 1 + 2 + it.second.toByteArray(Charsets.UTF_8).size + 8 + 4 }
        var offset = (BundleFormat.HEADER_SIZE + Int.SIZE_BYTES + tocSize).toLong()

        val body = ByteArrayOutputStream()
        body.int(sections.size)
        sections.forEach { (kind, name, bytes) ->
            body.write(kind.ordinal)
            body.write(if (name in eager) EntryClass.EAGER.ordinal else EntryClass.STREAMED.ordinal)
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            body.write(nameBytes.size and 0xFF)
            body.write((nameBytes.size ushr 8) and 0xFF)
            body.write(nameBytes)
            body.long(offset)
            body.int(bytes.size)
            offset += bytes.size
        }
        sections.forEach { body.write(it.third) }
        val bodyBytes = body.toByteArray()

        val header = ByteArrayOutputStream()
        header.write(magic)
        header.int(version)
        header.int(0)
        header.write(MessageDigest.getInstance("SHA-256").digest(bodyBytes))
        return header.toByteArray() + bodyBytes
    }

    /** A bundle with one graph section holding [assets] and an empty atlas. */
    fun simple(
        assets: List<Triple<String, String, Map<String, String>>>,
        version: Int = BundleFormat.VERSION,
        magic: ByteArray = BundleFormat.MAGIC,
    ): ByteArray = bundle(
        listOf(
            Triple(SectionKind.GRAPH, BundleFormat.GRAPH_SECTION, graphSection(assets)),
            Triple(SectionKind.ATLAS_INDEX, BundleFormat.ATLAS_SECTION, emptyAtlasSection()),
        ),
        version = version,
        magic = magic,
    )

    private fun ByteArrayOutputStream.int(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.long(value: Long) {
        int(value.toInt())
        int((value ushr 32).toInt())
    }
}
