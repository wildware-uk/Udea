package dev.wildware.hollow

import dev.wildware.udea.assets.AssetRegistry
import dev.wildware.udea.assets.Model
import dev.wildware.udea.assets.Ref
import dev.wildware.udea.assets.pack.Bundle
import dev.wildware.udea.assets.pack.BundleReader
import dev.wildware.udea.generated.GameAssets

/**
 * Hollow's packed assets, read off the classpath once: `MobaAssets`, for this game.
 */
public object HollowAssets {

    /** Where `processResources` puts the bundle: `UdeaAssetsPlugin`'s resource directory and bundle name. */
    internal const val RESOURCE: String = "udea/assets.udeapak"

    /** The opened bundle. Lazy, so a test that never draws never decodes it. */
    public val bundle: Bundle by lazy { BundleReader.open(readBundleBytes()) }

    /** The decoded graph. */
    public val registry: AssetRegistry get() = bundle.registry

    /** The model asset [prop] is drawn with. */
    public fun modelOf(prop: Prop): Model = registry[prop.asset()]
}

/**
 * The model asset each [Prop] is drawn with, one line each, so a prop added without a model is a
 * compile error here rather than an invisible entity.
 */
internal fun Prop.asset(): Ref<Model> = with(GameAssets.models) {
    when (this@asset) {
        Prop.GROUND -> ground
        Prop.PINE_TALL_A -> pineTallA
        Prop.PINE_TALL_B -> pineTallB
        Prop.PINE_ROUND_C -> pineRoundC
        Prop.PINE_ROUND_E -> pineRoundE
        Prop.OAK -> oak
        Prop.TREE_DEFAULT -> treeDefault
        Prop.TREE_DETAILED -> treeDetailed
        Prop.TREE_FAT -> treeFat
        Prop.STONE_LARGE_A -> stoneLargeA
        Prop.STONE_LARGE_C -> stoneLargeC
        Prop.STONE_TALL_A -> stoneTallA
        Prop.STONE_TALL_E -> stoneTallE
        Prop.STONE_SMALL_A -> stoneSmallA
        Prop.STONE_SMALL_C -> stoneSmallC
        Prop.STUMP -> stump
        Prop.LOG -> log
        Prop.FENCE -> fence
        Prop.GRASS -> grass
        Prop.GRASS_LARGE -> grassLarge
        Prop.BUSH -> bush
        Prop.BUSH_LARGE -> bushLarge
        Prop.MUSHROOMS -> mushrooms
        Prop.FLOWER_YELLOW -> flowerYellow
        Prop.FLOWER_PURPLE -> flowerPurple
    }
}

/** [HollowAssets.RESOURCE]'s bytes, off the classpath. */
internal expect fun readBundleBytes(): ByteArray
