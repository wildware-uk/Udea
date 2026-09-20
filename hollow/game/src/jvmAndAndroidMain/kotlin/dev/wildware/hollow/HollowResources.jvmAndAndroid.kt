package dev.wildware.hollow

internal actual fun readBundledLevel(): ByteArray = resource(HollowLevel.DEFAULT_RESOURCE)

internal actual fun readBundleBytes(): ByteArray = resource(HollowAssets.RESOURCE)

/**
 * One of the resources `hollow/game/build.gradle.kts` packages - the bundle or a level - off this
 * module's class loader. There is no fallback: a process without them was not built by that script.
 */
private fun resource(path: String): ByteArray {
    val loader = HollowAssets::class.java.classLoader
        ?: error("no class loader for HollowAssets, so $path cannot be looked up")
    val stream = loader.getResourceAsStream(path)
        ?: error(
            "$path is not among the resources. `:hollow:game:udeaBundleResources` lays out the packed " +
                "bundle and hollow/game/levels, and the target's processResources copies them in; a " +
                "run that skipped either has neither.",
        )
    return stream.use { it.readBytes() }
}
