package dev.wildware.udea.assets

/**
 * The stable name of one asset: `character/orc_elite`, `ui/main_menu`.
 *
 * A value class because the old tree proved what a bare `String` costs here. `object Assets`
 * was keyed by a string built as `"$path/$name"` and *derived* by string surgery
 * (`substringAfterLast("assets/")`), so the same file reached the map under two different keys
 * depending on which call site got there first, and `Assets["charater/orc"]` was a runtime
 * error with the whole map interpolated into the message. An [AssetId] cannot be produced by
 * accident from an arbitrary string: the shape is checked here, once, at the point the name is
 * made.
 *
 * ## What is legal
 *
 * Lowercase-ish `segment/segment/...`: at least one segment, no empty segment, no leading or
 * trailing [SEPARATOR], no backslash and no whitespace. The alphabet of a segment is not
 * policed — a game may name an asset `orc_elite`, `orcElite` or `orc-elite`; that is a house
 * style a validator rule can enforce, not a correctness property this type can claim.
 *
 * A leading slash is *rejected* rather than normalised away, which is the opposite of what
 * [ResPath] does with one, and deliberately so. A path is a file location the author typed in
 * two different ways; an id is a name the pipeline assigned, so a leading slash on one means a
 * defect somewhere upstream and silently trimming it would hide that.
 *
 * This is not typo detection. `AssetId("charater/orc")` is a perfectly legal id for an asset
 * that does not exist, and the did-you-mean diagnostic for that comes from build-time
 * validation and from [UnknownAssetException].
 */
@JvmInline
public value class AssetId(public val value: String) {

    init {
        require(value.isNotEmpty()) { "an AssetId must name something; it was empty" }
        require(value.none { it.isWhitespace() }) { "an AssetId must not contain whitespace: '$value'" }
        require(WINDOWS_SEPARATOR !in value) {
            "an AssetId separator is '$SEPARATOR', not a backslash: '$value'"
        }
        require(!value.startsWith(SEPARATOR)) {
            "an AssetId must not start with '$SEPARATOR' (that is a ResPath); it was '$value'"
        }
        require(!value.endsWith(SEPARATOR)) { "an AssetId must not end with '$SEPARATOR': '$value'" }
        require("$SEPARATOR$SEPARATOR" !in value) { "an AssetId must not contain an empty segment: '$value'" }
    }

    /** The `character` of `character/orc_elite`, or `""` for a single-segment id. */
    public val folder: String get() = value.substringBeforeLast(SEPARATOR, missingDelimiterValue = "")

    /** The `orc_elite` of `character/orc_elite`. */
    public val name: String get() = value.substringAfterLast(SEPARATOR)

    override fun toString(): String = value

    public companion object {
        /** The one separator. Ids are not platform paths and never see a backslash. */
        public const val SEPARATOR: Char = '/'

        /**
         * The separator an id must *not* use. A Windows-authored path that reached an id is a
         * defect upstream, and one that got through would make the same asset two ids on two
         * machines - the platform-dependent version of the bug [ResPath] exists to kill.
         */
        public const val WINDOWS_SEPARATOR: Char = '\\'
    }
}
