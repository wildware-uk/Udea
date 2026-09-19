package dev.wildware.udea.render.model

import de.fabmax.kool.AssetLoader
import de.fabmax.kool.modules.gltf.GltfFile
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.Model

/**
 * A [Model] asset read from its glTF file and ready to draw: `ModelRenderer(model = fox)` (issue
 * #240).
 *
 * Made by `loadModel` on the desktop, which reads and parses the file once. Everything that draws
 * it - meshes, the file's materials on Kool's PBR shader, its textures - is made by
 * [ModelRenderSystem] on the render thread, so one `ImportedModel` can be shared by any number of
 * entities, and each is drawn with its own transform.
 *
 * Drawn in its **bind pose**: bones and animation clips in the file are not read yet.
 *
 * glTF is Y-up and the world is Z-up (see `Transform3D`), so the file's +Y is drawn along the
 * world's +Z and the file's +Z - the way a glTF model faces - along the world's -Y. A model at the
 * origin with no rotation therefore stands upright and faces a camera placed at negative Y, which
 * is where `ModelCamera` puts one by default. Scale is the file's own units, times the entity's
 * scale.
 *
 * Kool's types stay internal (UDEA-MG-002): a game sees the asset, and nothing else.
 */
public class ImportedModel internal constructor(
    /** The asset this was read from. */
    public val asset: Model,
    /** The parsed file. Kool makes a scene node from it per entity drawn. */
    internal val gltf: GltfFile,
    /** What reads the file's textures and any files it names, rooted at the asset root. */
    internal val loader: AssetLoader,
) : ModelSource {

    override fun toString(): String = "ImportedModel(${asset.id}, ${asset.file})"
}

/**
 * A model file could not be read: it is missing, or Kool's glTF reader refused it.
 *
 * The build checks both before a game runs (`UDEA0032`, `UDEA0038`), so this is for a model that
 * reached the renderer without passing through it - one made in code, or a file changed since.
 * Never a silent substitute: a model that cannot be read is an exception, not an invisible entity.
 */
public class ModelLoadException(
    /** The model that failed. */
    public val model: AssetId,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
