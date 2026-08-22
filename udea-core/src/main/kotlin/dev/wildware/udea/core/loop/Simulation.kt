package dev.wildware.udea.core.loop

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SimClock
import dev.wildware.udea.core.Tick

/**
 * One whole simulation, advanced a whole tick at a time.
 *
 * This is the *only* thing [GameLoop] knows how to drive, and it is deliberately narrow: a
 * loop that could reach into the world would grow a second way to mutate it, which is the
 * thing [SimBarrier] exists to prevent.
 *
 * [tickRate] rather than only `dt` because the loop needs an **exact** tick period. `dt` is a
 * `Float`: `1f / 60f` is `0.016666668`, slightly *larger* than a sixtieth, so a loop that
 * subtracted it from a seconds accumulator would drop one tick in every ten seconds of wall
 * time. The tick rate is an integer and divides wall time exactly (see [GameLoop]).
 */
public interface Simulation {

    /** Ticks per simulated second. Constant for the life of the simulation. */
    public val tickRate: Int

    /** Simulated seconds per tick, for the systems and renderers that still speak seconds. */
    public val dt: Float get() = 1f / tickRate

    /**
     * Advances the simulation by exactly one tick.
     *
     * Contract for every implementation: the [SimBarrier] drain happens first, before any
     * system runs, and the clock advances last.
     */
    public fun step()
}

/**
 * The real [Simulation]: a [SimBarrier] drain, a Fleks world update, a clock advance.
 *
 * Replaces `GameScreen.render`'s `world.update(delta)` at `common/UdeaGameManager.kt:235`,
 * which stepped the whole simulation by whatever the GPU happened to hand it. Three defects
 * followed from that and all three die here: effect periods accumulated in float seconds
 * (`AttributeSystem.kt:26`), Box2D stepped a hardcoded `1/60f` no matter how much time had
 * actually passed (`Box2DSystem.kt:80`), and no two machines agreed on how much simulation a
 * given wall second contained.
 *
 * Ordering inside [step] is the whole contract:
 *
 * 1. **drain the barrier** — every queued mutation lands before anything observes the world,
 *    so no system ever sees a torn world (spec 3.3);
 * 2. **run the systems** — `world.update(dt)` is pure simulation *by construction*, because
 *    presentation is not a Fleks system at all;
 * 3. **advance the clock** — [SimClock.tick] names the tick that is *about to be* simulated,
 *    so it moves only once that tick is finished.
 */
public class WorldSimulation(
    /** The context the world was configured with. */
    public val ctx: GameContext,
    private val world: World,
    /**
     * The queue drained at the top of every [step].
     *
     * Defaults to the one registered on [ctx] so that whatever submitted to `ctx.barrier` is
     * what this simulation drains; a context without one gets a private queue.
     */
    public val barrier: SimBarrier = ctx.getOrNull(SimBarrier.KEY) ?: SimBarrier(),
) : Simulation {

    override val tickRate: Int get() = ctx.clock.tickRate

    override val dt: Float get() = ctx.clock.dt

    /** The tick this simulation is about to run. */
    public val tick: Tick get() = ctx.clock.tick

    /** How many ticks [step] has run. */
    public var stepCount: Long = 0L
        private set

    override fun step() {
        barrier.drain(world, ctx)
        world.update(dt)
        ctx.clock.advance()
        stepCount++
    }
}
