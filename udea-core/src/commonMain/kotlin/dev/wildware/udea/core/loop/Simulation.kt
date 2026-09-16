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
     * system runs, the clock advances last, and any snapshot the tick owes is taken after
     * that — so a captured tick names the state the *next* tick will simulate from.
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
 *    so it moves only once that tick is finished;
 * 4. **capture, if the tick is due one** — [TimeTravel.captureIfDue], at the cadence
 *    `EngineConfig.snapshotIntervalTicks` names.
 *
 * ## Why capture is here and not a [BarrierAction]
 *
 * The barrier exists to order *mutations* so that no system sees a torn world. A capture is a
 * read, and putting a read in the mutation queue would give it two properties it must not
 * have: it would land one tick late, and it would be ordered arbitrarily against whatever
 * tool call or scene swap happened to be queued beside it, so which side of a scene swap a
 * keyframe recorded would depend on submission order. Worse, `SnapshotTimeTravel` forces a
 * barrier drain from inside a rewind, so a queued capture would fire in the middle of a
 * restore.
 *
 * The property the barrier was wanted for is had directly here instead, and had more
 * strongly: this line runs on the simulation thread, after the last system of the tick has
 * returned and after the clock has advanced. There is no iteration in flight, no drain in
 * flight and no half-applied mutation — that *is* the tick boundary, and it is the same
 * boundary a drain runs at.
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
    /**
     * The snapshot ring this simulation records into, or `null` for one that keeps no history.
     *
     * A constructor argument, so a simulation either records for its whole life or never
     * records at all. `UdeaGameDef` builds it from the [TimeTravelFactory] it was given, which
     * is the only reason the factory exists — the ring needs the [World] and the ctx, and
     * neither is available before the world is configured.
     *
     * `null` is not a degraded mode. It is a dedicated server with no observer: no ring is
     * allocated, the per-tick cost is one null check, and `TimeControl` answers every
     * time-travel call with [RewindFailure.NoSnapshotRing].
     */
    public val travel: TimeTravel? = null,
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
        // A safe call and not an `if`, so the no-ring case is one null check and the ring case
        // is one virtual call. Neither allocates: `captureIfDue` returns a primitive precisely
        // so this line can sit on the path `TickLoopBudgetTest` gates at zero bytes.
        travel?.captureIfDue()
    }
}
