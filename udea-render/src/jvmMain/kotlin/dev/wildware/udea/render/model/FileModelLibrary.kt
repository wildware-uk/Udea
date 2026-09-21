package dev.wildware.udea.render.model

import dev.wildware.udea.assets.AssetIndex
import dev.wildware.udea.assets.AssetRegistry
import dev.wildware.udea.assets.Model
import java.nio.file.Path

/**
 * The desktop [ModelLibrary]: a `Drawn` slot, read out of the packed graph and off disk, once
 * (issue #270).
 *
 * ```
 * val models = FileModelLibrary(assetRoot, assets.registry)
 * // The factory is the second argument, not a trailing lambda: the trailing one is `constrain`.
 * registry.register(RenderPhase.World, { ModelRenderSystem(it, camera, light, models = models) })
 * ```
 *
 * That is the whole of what a game writes to make `Drawn(GameAssets.models.chassis, registry)`
 * draw. The asset root is the game's to name because only the game knows where its files are -
 * the packed bundle carries the model *record* and not the file's bytes, exactly as
 * [loadModel] and `koolAudioDevice` already have it.
 *
 * ## Once per model, not once per frame
 *
 * A slot is loaded the first time it is asked for and kept, so a hundred parts drawn with the
 * same model parse one file. Loading is blocking and touches no GL, which is why it is safe on
 * the render thread [ModelRenderSystem] asks from; the textures decode afterwards on Kool's own
 * loader threads.
 *
 * Not thread-safe, and it does not need to be: the only caller is the render thread.
 */
public class FileModelLibrary(
    private val assetRoot: Path,
    private val assets: AssetRegistry,
) : ModelLibrary {

    /** Indexed by slot; `null` where that slot has not been asked for. Slots are dense and small. */
    private val loaded = ArrayList<ImportedModel?>()

    override fun modelAt(slot: AssetIndex): ModelSource {
        while (loaded.size <= slot.value) loaded.add(null)
        loaded[slot.value]?.let { return it }
        val asset = assets.at(slot)
        val model = asset as? Model ?: throw ModelLoadException(
            asset.id,
            "asset slot $slot is a ${asset::class.simpleName}, not a model; a Drawn names a model",
        )
        return loadModel(assetRoot, model).also { loaded[slot.value] = it }
    }

    override fun toString(): String = "FileModelLibrary($assetRoot, ${loaded.count { it != null }} loaded)"
}
