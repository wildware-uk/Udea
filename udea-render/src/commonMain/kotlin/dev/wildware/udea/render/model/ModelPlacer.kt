package dev.wildware.udea.render.model

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.SimClock
import dev.wildware.udea.core.Tick
import dev.wildware.udea.render.interp.Interp3DSnapshotSystem
import dev.wildware.udea.render.interp.Interpolator3D
import dev.wildware.udea.render.interp.Pose
import dev.wildware.udea.render.interp.Pose3D
import dev.wildware.udea.render.interp.PoseHistory
import dev.wildware.udea.render.interp.PoseSource

/**
 * Where [ModelRenderSystem] draws a model, kept apart from it because this half needs no GL and
 * can be tested without a context.
 *
 * - With a `Transform3D`: between where the last two ticks left it, at the render alpha
 *   ([Interpolator3D], issue #246), when the world runs `RenderModule`'s [Interp3DSnapshotSystem];
 *   as it stands when it does not, because then there is nothing recorded to draw from.
 * - Without one, from [lift]: a 2D pose `(x, y, angle)` on the ground plane at scale 1.
 * - With neither, nowhere, and [place] answers false.
 */
internal class ModelPlacer(private val lift: PoseSource?) {

    /** Where the entity [place] was last asked about stands. Reused, like [Pose]. */
    val placed: Pose3D = Pose3D()

    private val pose = Pose()

    private var transforms: Interpolator3D? = null

    /**
     * Finds [world]'s [Interp3DSnapshotSystem], once. A world without one - a pipeline built for a
     * test, or a game that left `RenderModule` out - gets a history that never matches, which is
     * harmless: no entity in it carries the `Interp3D` the history would be consulted for.
     */
    fun bind(world: World, clock: SimClock) {
        val history: PoseHistory = world.systems.firstOrNull { it is Interp3DSnapshotSystem } as? Interp3DSnapshotSystem
            ?: NoHistory
        transforms = Interpolator3D(clock, history)
    }

    /** Writes where [entity] stands at [alpha] into [placed]; false when it has no place. */
    fun place(world: World, entity: Entity, alpha: Float): Boolean {
        val transforms = checkNotNull(transforms) { "ModelPlacer.place before bind" }
        if (transforms.interpolate(world, entity, alpha, placed)) return true
        val lift = lift ?: return false
        if (!lift.poseOf(world, entity, alpha, pose)) return false
        placed.set(pose.x, pose.y, 0f, 0f, 0f, pose.angle, 1f, 1f, 1f)
        return true
    }

    private object NoHistory : PoseHistory {
        override val lastTick: Tick = Tick(-1)
    }
}
