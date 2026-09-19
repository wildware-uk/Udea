package dev.wildware.udea.render

import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.core.physics.TeleportSystem
import dev.wildware.udea.render.interp.Interp3DSnapshotSystem
import dev.wildware.udea.render.interp.InterpSnapshotSystem

/**
 * The simulation systems presentation needs, registered the way every other module registers
 * its systems: [InterpSnapshotSystem] for `PhysicsBody` and [Interp3DSnapshotSystem] for
 * `Transform3D` (issue #246).
 *
 * ## Why a renderer contributes *simulation* systems at all
 *
 * Each records where an entity stood at a tick boundary - [InterpSnapshotSystem] at the **start**
 * of a tick, [Interp3DSnapshotSystem] at the **end** - and that value only exists at that moment.
 * It cannot be gathered from the render thread: by the time a frame is drawn the tick has already
 * run and the previous pose is gone.
 *
 * Neither is a hole in "presentation is not a Fleks system" (spec 3.3), because each is the
 * opposite kind of thing: it draws nothing, holds no GL type, and writes only its own
 * presentation component ([dev.wildware.udea.render.interp.Interp], `Interp3D`), which nothing
 * simulated reads. `InterpSnapshotPurityTest` and `Interpolation3DTest` pin that - two worlds whose
 * *only* difference is the system, ticked in step, come out value-for-value identical. (The 2D
 * claim used to be credited to `CameraRigTest`, which puts that system in both of its fixtures and
 * so says nothing about it.)
 *
 * A game that never renders can leave this module out and pay nothing for it. A
 * `RenderMode.Headless` server that includes it pays for the records every tick: a family scan for
 * [InterpSnapshotSystem] that is empty unless something spawned, and four floats per `Transform3D`
 * for [Interp3DSnapshotSystem].
 */
public class RenderModule : UdeaModule {

    override val name: String get() = "udea-render"

    override fun simulation(registry: SimRegistry) {
        registry.add(SimPhase.PreSimulation, { InterpSnapshotSystem() }) {
            // Before the teleport is applied *and consumed*. `TeleportSystem` removes the
            // `Teleport` component as it applies it, so running after it would leave
            // InterpSnapshotSystem unable to tell a teleport from a very fast walk — and the
            // entity would then be drawn sweeping across the map over the following frames.
            before(TeleportSystem::class)
        }
        // Last, so the pose it records is the one the whole tick produced - a client's snapshot
        // applied through the barrier included, which a start-of-tick record would miss (#246).
        registry.add(SimPhase.Cleanup, { Interp3DSnapshotSystem() })
    }
}
