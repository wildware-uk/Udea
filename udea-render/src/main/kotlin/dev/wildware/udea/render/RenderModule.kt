package dev.wildware.udea.render

import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.core.physics.TeleportSystem
import dev.wildware.udea.render.interp.InterpSnapshotSystem

/**
 * The one simulation system presentation needs, registered the way every other module
 * registers its systems.
 *
 * ## Why a renderer contributes a *simulation* system at all
 *
 * [InterpSnapshotSystem] records where each body stood at the **start** of a tick, and that
 * value only exists at the start of a tick. It cannot be gathered from the render thread: by
 * the time a frame is drawn the tick has already run and the previous pose is gone.
 *
 * It is not a hole in "presentation is not a Fleks system" (spec 3.3), because it is the
 * opposite kind of thing: it draws nothing, holds no GL type, and writes only
 * [dev.wildware.udea.render.interp.Interp], which nothing simulated reads. `CameraRigTest`
 * pins that — a world ticked with the interpolation machinery present is value-for-value
 * identical to one ticked without it.
 *
 * A game that never renders can leave this module out and pay nothing for it; a
 * `RenderMode.Headless` server that includes it pays one family scan per tick over a family
 * that is empty unless something spawned.
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
    }
}
