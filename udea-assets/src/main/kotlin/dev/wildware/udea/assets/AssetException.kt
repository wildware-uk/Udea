package dev.wildware.udea.assets

/**
 * Something was asked of the asset graph that the graph cannot answer.
 *
 * Typed and sealed rather than `error("Asset $path does not exist ${debugAssets()}")`
 * (`common/.../assets.kt:93`), which interpolated the entire asset map into a message thrown
 * mid-frame. Every subclass names the one thing that went wrong and, where there is one, the
 * fix.
 */
public sealed class AssetException(message: String) : RuntimeException(message)

/**
 * No asset in the graph is called [id].
 *
 * Carries a did-you-mean suggestion when one is within edit distance, because an agent that
 * gets `unknown asset 'character/orc_attack_cue'` spends a turn listing the asset tree, and one
 * that gets `did you mean 'character/orc_attack_sound_cue'?` fixes it in the turn it is in
 * (spec 5).
 */
public class UnknownAssetException internal constructor(
    public val id: AssetId,
    /** The closest known id, or `null` when nothing was close enough to be worth printing. */
    public val suggestion: AssetId?,
    graphSize: Int,
) : AssetException(
    "no asset '$id' in a graph of $graphSize assets" +
        (suggestion?.let { "; did you mean '$it'?" } ?: ""),
)

/**
 * The asset called [id] exists but is not the kind the reference expected.
 *
 * The defect this replaces is `AssetRefImpl` carrying no type token
 * (`common/.../assets.kt:36-43`): a mistyped `reference("...")` surfaced as a
 * `ClassCastException` in whatever unrelated code first touched `.value`, with nothing in the
 * stack trace naming the asset or the reference. [Ref.expected] makes the check happen at
 * resolution, and this message names both types and the id.
 */
public class AssetTypeMismatchException internal constructor(
    public val id: AssetId,
    public val expected: String,
    public val actual: String,
) : AssetException("asset '$id' is a $actual, but the reference expects a $expected")

/**
 * Two assets in one graph claim the same [AssetId].
 *
 * Thrown when the registry is built, not when a lookup happens to hit the collision: a graph
 * with a duplicated id has no single answer for that id, and the pack that produced it is
 * broken however few callers notice.
 */
public class DuplicateAssetIdException internal constructor(
    public val id: AssetId,
    public val firstIndex: Int,
    public val secondIndex: Int,
) : AssetException("asset '$id' is declared twice in one graph, at slots $firstIndex and $secondIndex")
