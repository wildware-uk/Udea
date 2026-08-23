package dev.wildware.udea.render.interp

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World

/**
 * Where an entity is, as far as anything that *draws* or *frames* it is concerned.
 *
 * ## Why this interface exists
 *
 * [Interpolator] is the engine's answer, and it reads `PhysicsBody`. That is right for every
 * entity the physics model owns, and it was the **only** answer - so `CameraRig` took an
 * `Interpolator` by type, and an entity whose position lives in some other component simply had
 * no pose. `moba` was exactly that game: its units carry `Position`, so `render.follow_entity`
 * resolved the id, found no `PhysicsBody`, moved the camera not at all, and answered `ok`
 * anyway. `MobaScene`'s KDoc had "**Following an entity does not work**" written into it as a
 * known lie, and `{"following": 0}` was the reply an agent got while the camera provably never
 * moved.
 *
 * Naming the *capability* instead of the class closes it without giving the kernel a second
 * position component: a game supplies the reader for its own spatial component, `Interpolator`
 * is still the reader for `PhysicsBody`, and `CameraRig` no longer cares which it was handed.
 *
 * ## The contract
 *
 * Called once per followed or drawn entity per frame, on the render thread. **It must not
 * allocate** - that is why the pose is an out-parameter - and it must not write to the world:
 * presentation reads the simulation and never the other way round (spec 3.3).
 */
public fun interface PoseSource {

    /**
     * Writes the pose [entity] should be drawn at into [into].
     *
     * @param alpha the loop's interpolation alpha in `[0, 1]`; `1` means "exactly where the
     *   simulation says".
     * @return `false` when this entity has no pose here and nothing was written, so a caller can
     *   skip it - and, for `render.follow_entity`, so a host can *refuse* rather than accept a
     *   follow that would do nothing.
     */
    public fun poseOf(world: World, entity: Entity, alpha: Float, into: Pose): Boolean
}
