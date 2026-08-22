package dev.wildware.udea.core

import com.github.quillraven.fleks.IntervalSystem
import com.github.quillraven.fleks.World

/**
 * Base class for every simulation system.
 *
 * Two things it buys, both structural rather than cosmetic:
 *
 * - [ctx] is resolved once, from the world being configured, so a system never reaches for a
 *   global and two worlds in one JVM cannot see each other's state;
 * - a system that extends this is, by its type, part of the simulation. Presentation is not
 *   a Fleks system at all — it implements `RenderSystem` in `udea-render` — so
 *   `world.update()` is pure simulation *by construction*, not by convention (spec 3.3).
 *
 * Prefer [tick] over `IntervalSystem.deltaTime`. `deltaTime` is seconds, and seconds are a
 * presentation unit: a duration measured in seconds is a duration that will not survive a
 * rewind or agree across two machines.
 */
public abstract class SimSystem : IntervalSystem() {

    /** The one injectable. Resolved from the world this system is being configured into. */
    public val ctx: GameContext = World.inject(GameContext.INJECT_NAME)

    /** The tick being simulated. */
    public val tick: Tick get() = ctx.clock.tick
}
