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
    /**
     * Whether this entity goes into the object mask a screen shader reads (issues #259, #266).
     *
     * The engine draws the marked models a second time, depth-only, into a picture a screen
     * effect samples as `uMask` - which is how an outline finds the edge of a unit without the
     * game rendering the scene twice and comparing. A game marks the things it wants outlined and
     * leaves the ground, the sky and the scenery alone; with nothing marked the mask is empty and
     * an outline effect is a no-op, which is the honest answer for a game that has not said what
     * its objects are.
     *
     * A `var`, so a unit can leave the mask when it dies or is picked up, and back on the next
     * frame. It costs nothing when it is false: the marked models are the only ones the mask pass
     * draws.
     */
    public var mask: Boolean = false,
) : Component<ModelRenderer> {

    /** A built-in [mesh] in [material]: `ModelRenderer(ModelMesh.box(1f, 1f, 1f), crate)`. */
    public constructor(mesh: ModelMesh, material: ModelMaterial) : this(MeshModel(mesh, material))

    override fun type(): ComponentType<ModelRenderer> = ModelRenderer

    override fun toString(): String = "ModelRenderer($model, mask=$mask)"

    public companion object : ComponentType<ModelRenderer>()
}

/** What a [ModelRenderer] draws: a [MeshModel] or an [ImportedModel]. */
public sealed interface ModelSource

/** One of the engine's built-in shapes, [mesh], in [material]. */
public data class MeshModel(
    public val mesh: ModelMesh,
    public val material: ModelMaterial,
) : ModelSource
