package dev.wildware.udea.assets.pack

/**
 * Everything a reader makes of a bundle, as text, so two readers can be compared by a string
 * equality (issue #205).
 *
 * It covers each thing [BundleReader] produces: the header's hash and whether it verifies, the
 * table of contents, the eager/streamed byte split, every section's bytes (as a length and an
 * FNV-1a checksum, which is the same arithmetic on every target), every decoded asset in slot
 * order, and the atlas. Written only against APIs every Kotlin target has, so the same function
 * describes the pre-port JVM reader and each ported target.
 */
internal fun describeBundle(bundle: Bundle, source: BundleSource): String = buildString {
    appendLine("contentHash ${bundle.contentHash.hex()}")
    appendLine("contentHashOf ${BundleReader.contentHashOf(source).hex()}")
    appendLine("verifyContentHash ${BundleReader.verifyContentHash(source)}")
    appendLine("eagerBytes ${bundle.eagerBytes} streamedBytes ${bundle.streamedBytes}")
    bundle.toc.forEach { entry ->
        val bytes = bundle.section(entry.name)
        appendLine(
            "section ${entry.name} ${entry.kind} ${entry.entryClass} offset=${entry.offset} " +
                "length=${entry.length} read=${bytes.size} fnv=${fnv1a(bytes)}",
        )
    }
    val registry = bundle.registry
    appendLine("assets ${registry.size}")
    registry.ids.forEach { id ->
        val asset = registry.find(id)
        appendLine("asset ${registry.indexOf(id)} $id ${asset?.let { it::class.simpleName }} $asset")
    }
    appendLine("atlas pages ${bundle.atlas.pages}")
    bundle.atlas.regions.forEach { appendLine("region $it") }
    bundle.atlas.sheets.forEach { sheet ->
        appendLine(
            "sheet $sheet frames " +
                bundle.atlas.framesOf(dev.wildware.udea.assets.AssetId(sheet)).joinToString(",") { it.name },
        )
    }
}

private fun ByteArray.hex(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** 64-bit FNV-1a, printed unsigned in hex. */
private fun fnv1a(bytes: ByteArray): String {
    var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325, the FNV-1a 64-bit offset basis
    for (byte in bytes) {
        hash = hash xor (byte.toLong() and 0xFF)
        hash *= 0x100000001b3L // the FNV-1a 64-bit prime
    }
    return hash.toULong().toString(16)
}
