package dev.wildware.moba.level

import dev.wildware.udea.core.SceneId
import dev.wildware.udea.core.ServiceKey
import dev.wildware.udea.core.serviceKey

/**
 * The level a `moba` process plays: a `.udealevel` file (issue #192).
 *
 * ## Where it comes from
 *
 * Whoever builds the game says, through `MobaGame.definition`'s `level`. With nothing said, it is
 * [bundledBytes]: `levels/test_level.udealevel`, packaged into the game's resources from
 * `moba/game/levels/` - the world the old `level/test_level.udea.kts` built, saved with #191's
 * `LevelService` before the script was deleted. A desktop launcher names another file with
 * `-Plevel=<path>` (`MobaLaunchLevel` in `:moba:desktop`); the game itself reads no system
 * property, so the choice is made by a launcher and nowhere else. Levels are saved content from
 * #190 on, not scripts, because an editor's Save writes exact values and a script's loop has no
 * single place to write one to.
 *
 * ## How it is played
 *
 * Twice over, deliberately. `MobaEntry.seed` swaps to [SCENE_ID], a `LevelScene` over the bytes,
 * and in the same barrier drain loads the level whole - the clock, the random streams and every
 * module's section as well as the entities. A match restart swaps back to [SCENE_ID] alone, which
 * puts the entities back and lets time and the streams carry on. `LevelScene` says why the two
 * differ.
 */
public object MobaLevel {

    /** The scene the launch level is registered under, and what a match restart swaps back to. */
    public val SCENE_ID: SceneId = SceneId("level")

    /** Where the launch level's bytes are published on a built game's context. */
    internal val KEY: ServiceKey<LaunchLevel> = serviceKey("moba.level")

    /** The bundled default, as a resource path. `moba/game/build.gradle.kts` packages `levels/`. */
    internal const val DEFAULT_RESOURCE: String = "levels/test_level.udealevel"

    /**
     * The bundled test level's bytes.
     *
     * @throws IllegalStateException when it is not among the resources, which means this process
     *   was not built by `moba/game/build.gradle.kts`.
     */
    public fun bundledBytes(): ByteArray = readBundledLevel()
}

/**
 * The launch level's bytes, published on the context under [MobaLevel.KEY] so the entry point that
 * boots a built game loads the same level its restart scene was registered with.
 */
internal class LaunchLevel(
    /** The `.udealevel` file's contents. Never written to. */
    val bytes: ByteArray,
) {
    override fun toString(): String = "LaunchLevel(${bytes.size} bytes)"
}

/** [MobaLevel.DEFAULT_RESOURCE]'s bytes, out of the game's packaged resources. */
internal expect fun readBundledLevel(): ByteArray
