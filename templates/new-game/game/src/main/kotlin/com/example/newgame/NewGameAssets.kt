package com.example.newgame

import dev.wildware.udea.assets.AssetRegistry
import dev.wildware.udea.assets.pack.Bundle
import dev.wildware.udea.assets.pack.BundleReader

/**
 * This game's packed assets, read off the classpath once.
 *
 * `dev.wildware.udea.assets` compiles `game/assets/` into one `.udeapak` and puts it in the jar
 * at [RESOURCE]. What comes out is an [AssetRegistry]: the records of every model and shader this
 * game declared, looked up by the accessors the build generated - `GameAssets.models.rover`,
 * `GameAssets.shaders.scanlines` - so no path to an asset is written in the Kotlin.
 *
 * The simulation reads it too, and needs no window to: a dedicated server resolves
 * `GameAssets.models.rover` to the slot a client will draw, and never opens the model file.
 */
public object NewGameAssets {

    /** Where the build puts the bundle: `udea/<bundle name>.udeapak`, and the name is `assets`. */
    private const val RESOURCE: String = "/udea/assets.udeapak"

    /** The opened bundle. Lazy, so nothing is decoded until something asks for an asset. */
    public val bundle: Bundle by lazy {
        val bytes = checkNotNull(NewGameAssets::class.java.getResourceAsStream(RESOURCE)) {
            "no $RESOURCE on the classpath; `dev.wildware.udea.assets` packs it into the jar, so " +
                "this process was started without the game's own resources"
        }.use { it.readBytes() }
        BundleReader.open(bytes)
    }

    /** The decoded asset graph. */
    public val registry: AssetRegistry get() = bundle.registry
}
