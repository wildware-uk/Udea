package dev.wildware.udea.assets.pack

/**
 * The on-disk shape of a `.udeapak`, shared by the writer in `udea-assets-compiler` and the
 * reader here (spec 3.6, issue #89).
 *
 * ## Why this is not CBOR
 *
 * Issue #89 asked for CBOR with generated `kotlinx.serialization` serialisers. That is not
 * available to this module: `UDEA-MG-006` (`ModuleGraphRules.ASSETS_MODEL_IS_A_LEAF`) allows
 * `udea-assets` to resolve `udea-annotations`, `udea-diagnostics` and the stdlib and nothing
 * else, and `kotlinx-serialization-cbor` is none of those. The rule is load-bearing rather
 * than bureaucratic — this module is on the classpath of the game, the engine and the agent
 * harness — so the format is hand-rolled instead.
 *
 * Generated serialisers would not have worked anyway. [dev.wildware.udea.assets.AssetData] is
 * deliberately **not** sealed ("a game declares its own kinds"), so a closed polymorphic
 * hierarchy cannot be enumerated at compile time. What replaces it is the same thing CBOR's
 * self-description would have given: every record carries the fully qualified name of its kind
 * and a tagged field tree, and [AssetCodecs] turns that into a typed value — with a game's own
 * kinds landing in [OpaqueAsset] rather than failing the read.
 *
 * ## Every field is fixed-width little-endian
 *
 * No varints. A varint encoder has a choice of encodings for some values, and a choice is
 * exactly what determinism cannot have.
 */
public object BundleFormat {

    /** `UDEA`, the first four bytes of every bundle. */
    public val MAGIC: ByteArray
        get() = byteArrayOf(0x55, 0x44, 0x45, 0x41)

    /**
     * The format version.
     *
     * Bumped whenever a reader of the previous version would misread these bytes. A reader that
     * sees a different number reports [BundleVersionException] before parsing anything, which
     * is the acceptance criterion issue #89 states as "a typed error, not an exception from
     * mid-parse".
     */
    public const val VERSION: Int = 1

    /** Length of the sha256 written into the header. */
    public const val CONTENT_HASH_SIZE: Int = 32

    /** Bytes before the section table: magic, version, flags, and the sha256 [CONTENT_HASH_SIZE]. */
    public const val HEADER_SIZE: Int = 4 + 4 + 4 + CONTENT_HASH_SIZE

    /** The one section every bundle has: the interned asset graph. */
    public const val GRAPH_SECTION: String = "graph"

    /** The atlas region table. Present even when a bundle packs no sprites; then it is empty. */
    public const val ATLAS_SECTION: String = "atlas-index"

    /** Name of atlas page [page]'s PNG section. Zero-padded so section order is name order. */
    public fun atlasPageSection(page: Int): String {
        require(page in 0..MAX_ATLAS_PAGES) { "atlas page $page is outside 0..$MAX_ATLAS_PAGES" }
        return "atlas/page" + page.toString().padStart(4, '0') + ".png"
    }

    /** Four zero-padded digits of page number, so a bundle cannot hold more than this. */
    public const val MAX_ATLAS_PAGES: Int = 9999
}

/** What a section holds. The reader treats each differently; the TOC says which is which. */
public enum class SectionKind {
    /** The interned asset graph. Exactly one per bundle. */
    GRAPH,

    /** The atlas region table. Exactly one per bundle. */
    ATLAS_INDEX,

    /** One packed atlas page, PNG-encoded. */
    ATLAS_PAGE,

    /** One audio file, bytes untouched. */
    AUDIO,

    /** Anything else the graph names by [dev.wildware.udea.assets.ResPath]. */
    BLOB,

    ;

    public companion object {
        /** [SectionKind] with ordinal [code], or null — an unknown kind is data, not a crash. */
        public fun ofCode(code: Int): SectionKind? = entries.getOrNull(code)
    }
}

/**
 * Whether a section is read during the load screen or on demand.
 *
 * Computed at pack time from the reachability set rooted at the [dev.wildware.udea.assets.GameConfig]
 * (issue #89): what the first level needs is [EAGER], the rest is [STREAMED]. The distinction is
 * what lets a loading bar be denominated in *bytes* — the eager sections' lengths are known from
 * the TOC before a single one is read — rather than in file count, which is what the old
 * `GameAssetLoader` counted.
 */
public enum class EntryClass {
    /** Read in full when the bundle is opened. */
    EAGER,

    /** Left on disk; read when something asks for it. */
    STREAMED,

    ;

    public companion object {
        public fun ofCode(code: Int): EntryClass? = entries.getOrNull(code)
    }
}

/**
 * One row of the table of contents: `(kind, id, offset, length, EAGER|STREAMED)`.
 *
 * [name] is the section's id — a bundle-relative name like `atlas/page0000.png`, never a path
 * on the machine that packed it. Nothing in a bundle names a directory that existed at build
 * time; that is the whole reason two checkouts can produce the same bytes.
 */
public data class TocEntry(
    public val kind: SectionKind,
    public val name: String,
    public val offset: Long,
    public val length: Int,
    public val entryClass: EntryClass,
) {
    init {
        require(name.isNotEmpty()) { "a section must be named; the TOC is keyed by the name" }
        require(offset >= 0) { "section '$name' starts at $offset" }
        require(length >= 0) { "section '$name' is $length bytes long" }
    }

    /** Last byte of this section, exclusive. */
    public val end: Long get() = offset + length
}
