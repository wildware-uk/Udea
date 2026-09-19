package dev.wildware.udea.render.view

import dev.wildware.udea.core.identity.NetId

/**
 * Implemented by a render system that can say where each entity it draws is, so an editor can pick
 * that entity with the pointer (epic #231, issue #235).
 *
 * Only the system that draws an entity knows where it is on screen: a sprite's rectangle is its
 * region's size times its scale about an interpolated pose, a model's box is its mesh times its
 * transform. So picking asks the render systems rather than guessing from a position component, and
 * a game's own render system is pickable the moment it implements this - the engine's sprite and
 * model systems have no private way in.
 *
 * The editor asks every [dev.wildware.udea.render.RenderSystem] a Scene view draws that implements
 * this, in the order the Scene view draws them, whenever it needs to know what is under the pointer
 * or which entities a selection box touches. A system drawn later is in front of one drawn earlier.
 *
 * Presentation only: reporting reads the world and writes nothing, like drawing.
 */
public interface PickBounds {

    /**
     * Reports into [out] every entity this system draws, **in the order it draws them, back to
     * front**: of two entities under the pointer the one reported later is picked first. An entity
     * with no `NetId` cannot be selected and is left out. Render thread, between frames.
     */
    public fun reportPickBounds(out: PickSink)
}

/**
 * Where a [PickBounds] reports its entities: one call per entity, in world units, through the camera
 * the entity is really seen through.
 */
public interface PickSink {

    /**
     * [entity] is drawn over the ground-plane rectangle from ([minX], [minY]) to ([maxX], [maxY]),
     * seen through the Scene view's 2D camera: a sprite, or anything else a `SpriteBatch2D` draws in
     * world units.
     */
    public fun rect(entity: NetId, minX: Float, minY: Float, maxX: Float, maxY: Float)

    /**
     * [entity] is drawn inside the world box from ([minX], [minY], [minZ]) to ([maxX], [maxY],
     * [maxZ]), seen through the Scene view's 3D camera: a model. Z is up.
     */
    @Suppress("LongParameterList") // A box is six numbers; a vector type here would be the only one in the API.
    public fun box(entity: NetId, minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float)
}
