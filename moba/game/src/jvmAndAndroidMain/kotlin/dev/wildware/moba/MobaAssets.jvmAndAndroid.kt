package dev.wildware.moba

/**
 * The bundle as a classpath resource, which is what both JVM-family targets have.
 *
 * On the desktop it is inside `:moba:game`'s jar, put there by `udeaBundleResources` and
 * `jvmProcessResources`; on Android it is inside the APK's java resources, put there by the same
 * task through `androidMain`. One `actual` for both, because `ClassLoader.getResourceAsStream` is
 * the same call on each.
 */
internal actual fun readBundleBytes(): ByteArray {
    val loader = MobaAssets::class.java.classLoader
        ?: error("no class loader for MobaAssets, so ${MobaAssets.RESOURCE} cannot be looked up")
    val stream = loader.getResourceAsStream(MobaAssets.RESOURCE)
        ?: error(
            "${MobaAssets.RESOURCE} is not on the classpath. It is written by " +
                "`:moba:game:udeaPackBundle`, laid out by `:moba:game:udeaBundleResources` and " +
                "copied into the resources by the target's `processResources`; a run that skipped " +
                "any of the three has no assets at all. There is deliberately no fallback - see " +
                "MobaAssets.",
        )
    return stream.use { it.readBytes() }
}
