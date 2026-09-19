package dev.wildware.udea.render.model

import de.fabmax.kool.NativeAssetLoader
import de.fabmax.kool.modules.gltf.GltfFile
import de.fabmax.kool.util.toBuffer
import dev.wildware.udea.assets.Model
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads [model]'s glTF file from under [assetRoot], ready to draw (issue #240).
 *
 * A `Model` names a path relative to the asset root (`models/fox/Fox.glb`), and the packed bundle
 * carries the model record but not the file's bytes, so the file is read from the root directory
 * itself - the same arrangement `koolAudioDevice` has for sound. Parsing is Kool's own glTF reader
 * (`de.fabmax.kool.modules.gltf`); a `.gltf` that names a separate `.bin` or image has it read from
 * beside the `.gltf`, through a loader rooted at [assetRoot].
 *
 * Blocking, and safe off the render thread: nothing here touches GL. Textures are decoded later,
 * on Kool's loader threads, when [ModelRenderSystem] first draws the model.
 *
 * @throws ModelLoadException when the file is missing, or Kool's reader refuses it.
 */
public fun loadModel(assetRoot: Path, model: Model): ImportedModel {
    val file = assetRoot.resolve(model.file.value)
    if (!Files.isRegularFile(file)) {
        throw ModelLoadException(model.id, "no model file at '$file' (the asset root is '$assetRoot')")
    }
    val loader = NativeAssetLoader(assetRoot.toAbsolutePath().toString())
    val parsed = runBlocking { GltfFile(Files.readAllBytes(file).toBuffer(), model.file.value, loader) }
    val gltf = parsed.getOrElse { failure ->
        throw ModelLoadException(model.id, "Kool could not read '$file' as glTF: ${failure.message}", failure)
    }
    return ImportedModel(model, gltf, loader)
}
