package dev.wildware.udea.assets.compiler.pack

import dev.wildware.udea.assets.pack.AtlasIndex
import dev.wildware.udea.assets.pack.BundleFormat
import dev.wildware.udea.assets.pack.EntryClass
import dev.wildware.udea.assets.pack.SectionKind
import dev.wildware.udea.assets.pack.TocEntry
import java.security.MessageDigest

/** One section on its way into a bundle. */
public data class BundleSection(
    public val kind: SectionKind,
    public val name: String,
    public val entryClass: EntryClass,
    public val bytes: ByteArray,
) {
    // A data class over a ByteArray needs these written out; the generated ones compare by
    // identity, which would make two byte-identical sections unequal and quietly break any
    // test that compares them.
    override fun equals(other: Any?): Boolean = this === other || (
        other is BundleSection &&
            kind == other.kind &&
            name == other.name &&
            entryClass == other.entryClass &&
            bytes.contentEquals(other.bytes)
        )

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + entryClass.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }

    override fun toString(): String = "BundleSection($name, $kind, $entryClass, ${bytes.size} bytes)"
}

/** What the packer produced, before it is a file. */
public data class BundleContent(
    public val assets: List<PackedAsset>,
    public val atlas: PackedAtlas = PackedAtlas.EMPTY,
    /** Audio and anything else the graph names, keyed by the section name. */
    public val blobs: List<BundleSection> = emptyList(),
    /** Section names whose bytes belong in the eager set. See [reachable]. */
    public val eagerBlobs: Set<String> = emptySet(),
) {
    public companion object {

        /**
         * [BundleContent] whose eager set is [Reachability.fromGameConfig] over [assets].
         *
         * The default constructor leaves `eagerBlobs` empty - everything streams - because a
         * caller packing a fragment (a test, a hot-reload delta) has no root to compute from
         * and should not silently get a wrong answer. This is the factory a whole-game pack
         * uses, and it is the only place the reachability set becomes an [EntryClass].
         */
        public fun reachable(
            assets: List<PackedAsset>,
            atlas: PackedAtlas = PackedAtlas.EMPTY,
            blobs: List<BundleSection> = emptyList(),
        ): BundleContent {
            val reached = Reachability.fromGameConfig(assets).paths.toSet()
            return BundleContent(
                assets = assets,
                atlas = atlas,
                blobs = blobs,
                // A blob's section name is its `ResPath` prefixed by its kind's folder, so the
                // match is on the suffix rather than on equality - the packer names sections,
                // the graph names paths, and neither should have to know the other's spelling.
                eagerBlobs = blobs.map { it.name }
                    .filter { name -> reached.any { name == it || name.endsWith("/$it") } }
                    .toSet(),
            )
        }
    }
}

/**
 * Writes a `.udeapak`.
 *
 * ## What determinism costs here, concretely
 *
 * Every one of these is a place a plausible implementation would have been non-reproducible:
 *
 * - **assets** are written in sorted id order, never `graph.assets.values` order;
 * - **strings** are one sorted, deduplicated table per section, so a value's encoding does not
 *   depend on which record met it first;
 * - **struct fields** are sorted by name, so a `Map` literal in a script does not leak its
 *   insertion order into the artifact;
 * - **sections** are ordered eager-first and then by name, never by a directory listing;
 * - **nothing is timestamped**, and nothing records a path from the machine that packed it.
 *   The only absolute-path-shaped strings in a bundle are `ResPath`s, which are asset-root
 *   relative by construction.
 *
 * The last one is why `ReproducibilityTest` packs from two different checkout directories
 * rather than twice from one: a second pack in the same directory would not catch a path that
 * leaked, and a path leaking is the failure this criterion is actually about.
 */
public object BundleWriter {

    /** Serialises [content] into the bytes of a `.udeapak`. */
    public fun write(content: BundleContent): ByteArray {
        val sections = buildList {
            add(
                BundleSection(
                    SectionKind.GRAPH,
                    BundleFormat.GRAPH_SECTION,
                    EntryClass.EAGER,
                    graphSection(content.assets),
                ),
            )
            add(
                BundleSection(
                    SectionKind.ATLAS_INDEX,
                    BundleFormat.ATLAS_SECTION,
                    EntryClass.EAGER,
                    atlasSection(content.atlas),
                ),
            )
            content.atlas.pages.forEachIndexed { page, bytes ->
                add(
                    BundleSection(
                        SectionKind.ATLAS_PAGE,
                        BundleFormat.atlasPageSection(page),
                        // A page is eager when anything eager is drawn from it. Which pages
                        // those are is decided by Reachability, not here.
                        if (page in content.atlas.eagerPages) EntryClass.EAGER else EntryClass.STREAMED,
                        bytes,
                    ),
                )
            }
            addAll(
                content.blobs.map {
                    if (it.name in content.eagerBlobs) it.copy(entryClass = EntryClass.EAGER) else it
                },
            )
        }
        return assemble(sections)
    }

    /**
     * Lays sections out, computes the TOC, and stamps the content hash.
     *
     * Eager sections come first so that opening a bundle reads one contiguous run of the file
     * rather than seeking between the eager and streamed sets. Within each half, name order -
     * which is a total order over a set of names, and therefore stable.
     */
    private fun assemble(sections: List<BundleSection>): ByteArray {
        val ordered = sections.sortedWith(
            compareBy({ if (it.entryClass == EntryClass.EAGER) 0 else 1 }, { it.name }),
        )
        val duplicates = ordered.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "two sections would be named $duplicates" }

        var offset = (BundleFormat.HEADER_SIZE + Int.SIZE_BYTES + tocSize(ordered)).toLong()
        val toc = ordered.map { section ->
            val entry = TocEntry(section.kind, section.name, offset, section.bytes.size, section.entryClass)
            offset += section.bytes.size
            entry
        }

        val body = ByteSink(offset.toInt())
        body.i32(toc.size)
        toc.forEach { entry ->
            body.u8(entry.kind.ordinal)
            body.u8(entry.entryClass.ordinal)
            val name = entry.name.toByteArray(Charsets.UTF_8)
            require(name.size <= 0xFFFF) { "section name '${entry.name}' is longer than a u16 length" }
            body.u16(name.size)
            body.raw(name)
            body.i64(entry.offset)
            body.i32(entry.length)
        }
        ordered.forEach { body.raw(it.bytes) }
        val bodyBytes = body.toByteArray()

        val header = ByteSink(BundleFormat.HEADER_SIZE)
        header.raw(BundleFormat.MAGIC)
        header.i32(BundleFormat.VERSION)
        header.i32(0) // flags: reserved, and written as a constant so it cannot vary.
        header.raw(MessageDigest.getInstance("SHA-256").digest(bodyBytes))
        check(header.size == BundleFormat.HEADER_SIZE) {
            "the header is ${header.size} bytes but BundleFormat.HEADER_SIZE says " +
                "${BundleFormat.HEADER_SIZE}; the reader seeks by that constant"
        }
        return header.toByteArray() + bodyBytes
    }

    private fun tocSize(sections: List<BundleSection>): Int = sections.sumOf {
        1 + 1 + 2 + it.name.toByteArray(Charsets.UTF_8).size + 8 + 4
    }

    /** The graph section: string table, then the asset table in ascending id order. */
    internal fun graphSection(assets: List<PackedAsset>): ByteArray {
        require(assets.map { it.id } == assets.map { it.id }.sorted()) {
            "assets must be written in sorted id order; the sort position is the AssetIndex"
        }
        val strings = StringTable(assets.flatMap { it.strings() })
        val sink = ByteSink()
        strings.writeTo(sink)
        sink.i32(assets.size)
        assets.forEach { asset ->
            sink.i32(strings[asset.id])
            sink.i32(strings[asset.kind])
            writeFields(sink, strings, asset.fields)
        }
        return sink.toByteArray()
    }

    private fun writeFields(sink: ByteSink, strings: StringTable, fields: PackValue.Fields) {
        sink.i32(fields.values.size)
        fields.values.forEach { (name, value) ->
            sink.i32(strings[name])
            writeValue(sink, strings, value)
        }
    }

    private fun writeValue(sink: ByteSink, strings: StringTable, value: PackValue) {
        sink.u8(value.tag)
        when (value) {
            PackValue.Null -> Unit
            is PackValue.Bool -> sink.bool(value.value)
            is PackValue.I32 -> sink.i32(value.value)
            is PackValue.I64 -> sink.i64(value.value)
            is PackValue.F32 -> sink.f32(value.value)
            is PackValue.Text -> sink.i32(strings[value.value])
            is PackValue.Path -> sink.i32(strings[value.value])
            is PackValue.Ref -> sink.i32(value.index)
            is PackValue.Vec -> {
                sink.f32(value.x)
                sink.f32(value.y)
            }
            is PackValue.Items -> {
                sink.i32(value.values.size)
                value.values.forEach { writeValue(sink, strings, it) }
            }
            // The tag is already written, so the field count and pairs are all that is left;
            // writeFields would write its own tag.
            is PackValue.Fields -> {
                sink.i32(value.values.size)
                value.values.forEach { (name, child) ->
                    sink.i32(strings[name])
                    writeValue(sink, strings, child)
                }
            }
        }
    }

    /** The atlas region table. Its own string table: nothing here is a graph string. */
    internal fun atlasSection(atlas: PackedAtlas): ByteArray {
        val regions = atlas.regions
        require(regions.map { it.name } == regions.map { it.name }.sorted()) {
            "atlas regions must be written in sorted name order"
        }
        val sheets = atlas.sheetRanges
        val strings = StringTable(regions.map { it.name } + sheets.keys)
        val sink = ByteSink()
        strings.writeTo(sink)

        sink.i32(atlas.pageSizes.size)
        atlas.pageSizes.forEach { (width, height) ->
            sink.i32(width)
            sink.i32(height)
        }
        sink.i32(regions.size)
        regions.forEach { region ->
            sink.i32(strings[region.name])
            sink.u16(region.page)
            sink.u16(region.x)
            sink.u16(region.y)
            sink.u16(region.width)
            sink.u16(region.height)
        }
        sink.i32(sheets.size)
        sheets.entries.sortedBy { it.key }.forEach { (sheet, range) ->
            sink.i32(strings[sheet])
            sink.i32(range.first)
            sink.i32(range.last - range.first + 1)
        }
        return sink.toByteArray()
    }

    /** `AtlasIndex.regionName`, re-exported so a caller need not import the runtime module. */
    public fun regionName(sheet: String, frame: Int): String = AtlasIndex.regionName(sheet, frame)
}
