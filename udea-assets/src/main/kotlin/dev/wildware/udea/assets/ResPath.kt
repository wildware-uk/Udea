package dev.wildware.udea.assets

/**
 * The location of a resource file inside the asset root: `sprites/orc/idle.png`.
 *
 * Distinct from [AssetId] because they are different things that were the same `String` in the
 * old tree, and that is precisely where the two-keys-for-one-file bug lived: a script wrote
 * `spritePath = "/sprites/orc_elite/orc_elite_idle.png"`
 * (`example/src/main/resources/assets/character/orc_elite.udea.kts:122`) while the loader
 * registered the same file under the stripped key `sprites/...`
 * (`common/UdeaGameManager.kt:506`), so the lookup missed and the texture was loaded twice
 * under two names.
 *
 * So a leading slash is **normalised away at construction** rather than rejected: both spellings
 * mean the same file, authors have written both for years, and the fix that works is one that
 * makes the two spellings the same value rather than one that fails the build on a slash. Any
 * run of separators collapses, `\` becomes [AssetId.SEPARATOR], and the result is what every
 * consumer sees — there is no way to hold an un-normalised [ResPath].
 *
 * `..` is rejected: a resource path that escapes the asset root is either an authoring mistake
 * or a way to read a file the pack was never meant to contain, and neither should reach a
 * loader.
 */
@JvmInline
public value class ResPath private constructor(public val value: String) {

    /** The `png` of `sprites/orc/idle.png`, lowercased, or `""` when there is no extension. */
    public val extension: String
        get() = value.substringAfterLast('.', missingDelimiterValue = "").lowercase()

    override fun toString(): String = value

    public companion object {
        /**
         * Normalises [raw] and returns it as a [ResPath].
         *
         * An `operator invoke` rather than a public constructor because a value class cannot
         * rewrite its own value in `init`, and the whole point of this type is that the value it
         * holds is the normalised one. Inside this companion `ResPath(...)` binds to the private
         * constructor, which is what makes the call below the real construction and not a
         * recursion into this function.
         */
        public operator fun invoke(raw: String): ResPath {
            require(raw.isNotBlank()) { "a ResPath must name a file; it was blank" }
            val separated = raw.replace(AssetId.WINDOWS_SEPARATOR, AssetId.SEPARATOR)
            val segments = separated.split(AssetId.SEPARATOR).filter { it.isNotEmpty() }
            require(segments.isNotEmpty()) { "a ResPath must name a file; it was '$raw'" }
            require(segments.none { it == ".." }) {
                "a ResPath must stay inside the asset root, so '..' is not allowed: '$raw'"
            }
            require(segments.none { it == "." }) { "a ResPath must be written without '.': '$raw'" }
            return ResPath(segments.joinToString(AssetId.SEPARATOR.toString()))
        }
    }
}
