package dev.wildware.moba.level

/**
 * The bundled level as a class-loader resource: inside `:moba:game`'s jar on the desktop and inside
 * the APK's java resources on Android, the same way `MobaAssets` finds the bundle.
 */
internal actual fun readBundledLevel(): ByteArray {
    val loader = MobaLevel::class.java.classLoader
        ?: error("no class loader for MobaLevel, so ${MobaLevel.DEFAULT_RESOURCE} cannot be looked up")
    val stream = loader.getResourceAsStream(MobaLevel.DEFAULT_RESOURCE)
        ?: error(
            "${MobaLevel.DEFAULT_RESOURCE} is not among the resources; moba/game/build.gradle.kts " +
                "packages moba/game/levels, so this process was not built by it",
        )
    return stream.use { it.readBytes() }
}
