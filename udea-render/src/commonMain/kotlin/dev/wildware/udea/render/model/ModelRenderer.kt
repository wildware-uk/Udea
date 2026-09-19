package dev.wildware.udea.render.model

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType

/**
 * A 3D model on an entity: what to draw.
 *
 * The 3D counterpart of `SpriteRenderer`, and here for the same reason: what it draws holds a
 * texture, and `udea-core` has no GL. **Never snapshotted or replicated.**
 *
 * [model] is either shape the engine can draw: a built-in [MeshModel] - a box, sphere or plane in a
 * material - or an [ImportedModel] read from a glTF file, drawn with the file's own materials and
 * textures.
 *
 * Where the model is drawn is not here. [ModelRenderSystem] reads the entity's `Transform3D`, or,
 * for an entity that has only a 2D position, the pose its `PoseSource` gives, lifted onto the
 * ground plane.
 */
public class ModelRenderer(
    public var model: ModelSource,
) : Component<ModelRenderer> {

    /** A built-in [mesh] in [material]: `ModelRenderer(ModelMesh.box(1f, 1f, 1f), crate)`. */
    public constructor(mesh: ModelMesh, material: ModelMaterial) : this(MeshModel(mesh, material))

    override fun type(): ComponentType<ModelRenderer> = ModelRenderer

    override fun toString(): String = "ModelRenderer($model)"

    public companion object : ComponentType<ModelRenderer>()
}

/** What a [ModelRenderer] draws: a [MeshModel] or an [ImportedModel]. */
public sealed interface ModelSource

/** One of the engine's built-in shapes, [mesh], in [material]. */
public data class MeshModel(
    public val mesh: ModelMesh,
    public val material: ModelMaterial,
) : ModelSource
