package dev.wildware.udea.assets.pack

/**
 * The one-byte tag in front of every value in the graph section.
 *
 * This is what "self-describing" means for this format (spec 1). The reader never consults a
 * schema to know what comes next, so a game's own asset kind — whose fields no engine type
 * describes — decodes exactly as far as an engine kind does, and lands in [OpaqueAsset] with
 * its structure intact rather than as an unparsed blob.
 *
 * Values are never re-numbered. A tag added here is a new number and a [BundleFormat.VERSION]
 * bump, because an older reader hitting an unknown tag can only report corruption.
 */
public object ValueTag {

    public const val NULL: Int = 0

    public const val BOOL: Int = 1

    public const val INT: Int = 2

    public const val LONG: Int = 3

    public const val FLOAT: Int = 4

    /** A string, as a `u32` index into the section's string table. */
    public const val TEXT: Int = 5

    /** A [dev.wildware.udea.assets.ResPath], as a `u32` index into the section's string table. */
    public const val PATH: Int = 6

    /**
     * A reference, as the `u32` **asset index** of its target — never the id string.
     *
     * This is the patch issue #89 calls for (Trello #32). Resolving a `Ref` at runtime is an
     * array index, and `RefIndexTest` proves no string hashing happens on that path.
     */
    public const val REF: Int = 7

    /** Two `f32`, x then y. */
    public const val VEC: Int = 8

    /** `u32` element count, then that many tagged values. */
    public const val LIST: Int = 9

    /** `u32` field count, then `(u32 name index, value)` pairs sorted by name. */
    public const val STRUCT: Int = 10

    /** Highest tag this build understands, so the reader can say so rather than mis-parse. */
    public const val MAX: Int = STRUCT
}
