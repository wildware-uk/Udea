package dev.wildware.udea.assets.pack

/**
 * A `.udeapak` this build cannot read.
 *
 * Sealed on purpose, unlike [dev.wildware.udea.assets.AssetData]: the set of ways a *format*
 * can be wrong is closed and owned by this module, so a caller may exhaustively `when` on it.
 */
public sealed class BundleException(message: String) : RuntimeException(message)

/**
 * The first four bytes were not `UDEA`.
 *
 * Reported before the version is even looked at, because a file that is not a bundle has no
 * version field — reading one would be reading whatever bytes happen to sit at offset 4.
 */
public class BundleMagicException internal constructor(
    public val found: String,
) : BundleException("not a udea bundle: expected magic 'UDEA', found '$found'")

/**
 * The bundle's format version is not [BundleFormat.VERSION].
 *
 * Issue #89's acceptance criterion: *"the reader rejects a bundle whose format version does not
 * match with a typed error, not an exception from mid-parse"*. Nothing past the header is read
 * when this is thrown, so the message can never be an `ArrayIndexOutOfBoundsException` from a
 * field that moved between versions.
 */
public class BundleVersionException internal constructor(
    public val found: Int,
    public val expected: Int = BundleFormat.VERSION,
) : BundleException(
    "this build reads .udeapak format version $expected; the bundle is version $found. " +
        "Repack it with the matching udea-assets-compiler.",
)

/**
 * The header parsed, but the bytes after it do not describe what they claim to.
 *
 * Distinct from [BundleVersionException] because the two mean opposite things to whoever reads
 * the message: a version mismatch is a stale artifact and is fixed by repacking, while this is
 * a corrupt or truncated file and is fixed by redownloading.
 */
public class BundleCorruptException internal constructor(
    public val reason: String,
) : BundleException("this .udeapak is corrupt: $reason")

/** The bundle is intact, but names a section nobody wrote. */
public class UnknownSectionException internal constructor(
    public val name: String,
    public val available: List<String>,
) : BundleException(
    "no section '$name' in this bundle; it holds ${available.size}: " +
        available.take(SECTIONS_IN_MESSAGE).joinToString() +
        if (available.size > SECTIONS_IN_MESSAGE) ", ..." else "",
)

private const val SECTIONS_IN_MESSAGE = 8

/**
 * A decoded record whose field does not hold what the kind's codec needs.
 *
 * A [BundleException] rather than an `AssetException`: `AssetException` is sealed in the model
 * package and describes a graph that is *readable but wrong* (unknown id, kind mismatch).
 * This one means the bytes did not deserialise, which no caller can recover from by fixing a
 * reference.
 */
public class AssetDecodeException internal constructor(
    public val assetId: String,
    public val detail: String,
) : BundleException("asset '$assetId' cannot be decoded from the bundle: $detail")
