package dev.wildware.hollow

import dev.wildware.udea.core.GameContextBuilder
import dev.wildware.udea.core.ServiceKey
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.core.serviceKey

/**
 * Hollow's own module. In H1 it publishes the launch level and contributes no system: the clearing
 * is static. The player and the creatures register their systems here in later tickets.
 */
internal class HollowModule(private val level: LaunchLevel) : UdeaModule {

    override val name: String get() = HollowGame.NAME

    override fun context(builder: GameContextBuilder) {
        builder.service(LaunchLevel.KEY, level)
    }

    override fun toString(): String = "HollowModule($level)"
}

/**
 * The launch level's bytes, published on the context so [HollowGame.seed] loads the level the
 * definition's scene was registered with, rather than being handed it a second time.
 */
internal class LaunchLevel(
    /** The `.udealevel` file's contents. Never written to. */
    val bytes: ByteArray,
) {
    override fun toString(): String = "LaunchLevel(${bytes.size} bytes)"

    companion object {
        val KEY: ServiceKey<LaunchLevel> = serviceKey("hollow.level")
    }
}
