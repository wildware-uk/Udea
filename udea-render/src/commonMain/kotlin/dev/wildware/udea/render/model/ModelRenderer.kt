package dev.wildware.udea.render.model

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType

/**
 * A 3D model on an entity: which shape, and which material.
 *
 * The 3D counterpart of `SpriteRenderer`, and here for the same reason: it holds a texture, and
 * `udea-core` has no GL. **Never snapshotted or replicated.**
 *
 * Where the model is drawn is not here. [ModelRenderSystem] reads the entity's `Transform3D`, or,
 * for an entity that has only a 2D position, the pose its `PoseSource` gives, lifted onto the
 * ground plane.
 */
public class ModelRenderer(
    public var mesh: ModelMesh,
    public var material: ModelMaterial,
) : Component<ModelRenderer> {

    override fun type(): ComponentType<ModelRenderer> = ModelRenderer

    override fun toString(): String = "ModelRenderer($mesh, $material)"

    public companion object : ComponentType<ModelRenderer>()
}
