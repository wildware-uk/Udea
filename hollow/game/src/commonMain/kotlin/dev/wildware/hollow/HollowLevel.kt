package dev.wildware.hollow

import dev.wildware.udea.core.SceneId

/**
 * The level a Hollow process plays: a `.udealevel` file (issue #191), `moba`'s arrangement.
 *
 * With nothing else named it is [bundledBytes]: `levels/clearing.udealevel`, packaged into the
 * game's resources from `hollow/game/levels/`. A desktop launcher names another file with
 * `-Plevel=<path>`; the game itself reads no system property.
 */
public object HollowLevel {

    /** The scene the launch level is registered under. */
    public val SCENE_ID: SceneId = SceneId("level")

    /** The bundled clearing, as a resource path. `hollow/game/build.gradle.kts` packages `levels/`. */
    internal const val DEFAULT_RESOURCE: String = "levels/clearing.udealevel"

    /**
     * The bundled clearing's bytes.
     *
     * @throws IllegalStateException when it is not among the resources, which means this process
     *   was not built by `hollow/game/build.gradle.kts`.
     */
    public fun bundledBytes(): ByteArray = readBundledLevel()
}

/** [HollowLevel.DEFAULT_RESOURCE]'s bytes, out of the game's packaged resources. */
internal expect fun readBundledLevel(): ByteArray
