package dev.wildware.udea.render.interp

import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.spatial.Transform3D

/**
 * Records every `Transform3D`'s position and heading into its [Interp3D] at the end of each tick
 * (issue #246).
 *
 * Registered by `RenderModule` in `SimPhase.Cleanup`, the last phase, so the pose it records is
 * the one the whole tick produced. [Interp3D] says why the end of a tick and not the start.
 *
 * ## It is a simulation system, and it does not simulate
 *
 * The same concession [InterpSnapshotSystem] makes: it reads `Transform3D` and writes only
 * [Interp3D], which nothing simulated reads, so removing it changes no simulated value - asserted
 * by `Interpolation3DTest`, not in a comment. Nothing is allocated per tick except the one
 * [Interp3D] an entity is given on the first tick it is seen.
 */
internal class Interp3DSnapshotSystem : SimSystem(), PoseHistory {

    private val tracked: Family = world.family { all(Transform3D, Interp3D) }

    /**
     * Transforms that have no [Interp3D] yet. Given one with both ends at the current pose, so the
     * first frame draws the entity where it is rather than sliding in from the origin.
     */
    private val uninitialised: Family = world.family { all(Transform3D).none(Interp3D) }

    /** The tick this system last recorded for; `Tick(-1)` before its first run, as [InterpSnapshotSystem]. */
    override var lastTick: Tick = Tick(-1)
        private set

    override fun onTick() {
        uninitialised.forEach { entity ->
            val t = entity[Transform3D]
            entity.configure { it += Interp3D(t.x, t.y, t.z, t.rotationZ) }
        }
        tracked.forEach { entity ->
            val t = entity[Transform3D]
            entity[Interp3D].record(t.x, t.y, t.z, t.rotationZ)
        }
        lastTick = tick
    }
}
