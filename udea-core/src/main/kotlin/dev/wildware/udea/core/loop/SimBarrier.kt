package dev.wildware.udea.core.loop

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.GameContextBuilder
import dev.wildware.udea.core.ServiceKey
import dev.wildware.udea.core.serviceKey

/**
 * A world mutation that must not happen in the middle of a tick.
 *
 * A named interface rather than a bare lambda, and the name is the point: when an agent asks
 * why the world changed between tick 412 and tick 413, or when a desync is being triaged
 * against a replay, [label] is the only thing that answers. A `() -> Unit` in a queue is
 * anonymous the moment it is enqueued.
 *
 * Implementations are supplied by the subsystem that owns the mutation — scene swaps, asset
 * hot-reload deltas, agent tool calls, inbound network snapshot application. This signature
 * is a frozen cross-module contract (spec 5, "Between-tick mutation"); changing it is a
 * breaking change for all four.
 */
public interface BarrierAction {

    /** Short, stable, human-readable. Shows up in logs, `describe` and desync triage. */
    public val label: String

    /**
     * Applies the mutation. Runs at the top of a [Simulation.step], before any system.
     *
     * Anything submitted from here lands in the *next* tick's queue, never in the drain that
     * is running — so an action may safely enqueue follow-up work without risking an
     * unbounded drain.
     */
    public fun apply(world: World, ctx: GameContext)
}

/**
 * The one place the world is mutated from outside a system.
 *
 * ## Why there is exactly one
 *
 * Three subsystem analyses independently invented an "apply between ticks" queue — core for
 * scene swaps, assets for hot-reload `GraphDelta`, MCP for mutating tool calls — and inbound
 * network snapshot application is a fourth. Four queues means four different answers to "can
 * a system see half of this change?". One queue, drained at the top of [Simulation.step]
 * before phase zero runs, means the guarantee is stated once: **no system ever observes a
 * torn world.**
 *
 * The old engine had the opposite. Level entities were spawned inside a
 * `Gdx.app.postRunnable` from `GameScreen`'s `init` (`common/UdeaGameManager.kt:191`) with a
 * `started` flag (`:91`) guarding `render()` (`:224`), so the world was silently empty for a
 * frame or more and nothing in the type system said so.
 *
 * ## Threading
 *
 * [submit] is thread-safe because MCP tool calls arrive on an HTTP thread and asset deltas on
 * the daemon's. Everything else — the drain, the systems, the whole simulation — stays
 * single-threaded. The lock is held only for a queue swap or an append, never while an action
 * runs, so a slow action cannot block a submitter.
 *
 * ## Allocation
 *
 * The drain swaps two pooled lists and walks the batch by index. In steady state that is zero
 * allocation: no iterator, no boxing, no new queue. `SimBarrierAllocationTest` measures it.
 */
public class SimBarrier(initialCapacity: Int = DEFAULT_CAPACITY) {

    /** Guards [inbox] only. Never held while a [BarrierAction] runs. */
    private val lock = Any()

    /** Where [submit] appends. Swapped with [batch] at the top of a drain. */
    private var inbox = ArrayList<BarrierAction>(initialCapacity)

    /** The batch being applied. Empty between drains, so the swap is always safe. */
    private var batch = ArrayList<BarrierAction>(initialCapacity)

    /** Actions applied by the most recent [drain]. The metric an agent polls. */
    public var drainedThisTick: Int = 0
        private set

    /** Actions applied since construction, successful or throwing. */
    public var totalDrained: Long = 0L
        private set

    /** Actions whose [BarrierAction.apply] threw. Non-zero means look at the log. */
    public var failedActions: Long = 0L
        private set

    /**
     * Queues [action] for the top of the next [Simulation.step]. Thread-safe.
     *
     * Submission order is preserved: a drain applies actions FIFO. There is no ordering
     * guarantee *between* submitters beyond that, and deliberately so — a total order across
     * threads would need a global sequencer nobody has asked for.
     */
    public fun submit(action: BarrierAction) {
        synchronized(lock) { inbox.add(action) }
    }

    /** Actions queued and not yet applied. */
    public fun pendingCount(): Int = synchronized(lock) { inbox.size }

    /**
     * Applies every queued action, in submission order, and returns how many ran.
     *
     * Called by [Simulation.step] and by nothing else. Actions submitted *while* this runs go
     * to the next tick's queue: the batch is detached under the lock before the first action
     * runs, so the drain is bounded by the queue length at entry no matter what the actions
     * do.
     *
     * A throwing action is caught and logged with its [BarrierAction.label] and the tick it
     * failed on, and the drain carries on. One bad mutation must not strand the remaining
     * ones or stall the loop — the alternative is a tool call that leaves the queue
     * half-applied, which is exactly the torn state this class exists to prevent.
     */
    public fun drain(world: World, ctx: GameContext): Int {
        synchronized(lock) {
            val swap = batch
            batch = inbox
            inbox = swap
        }

        val running = batch
        val size = running.size
        var index = 0
        while (index < size) {
            val action = running[index]
            try {
                action.apply(world, ctx)
            } catch (failure: Throwable) {
                failedActions++
                ctx.log.error(
                    "SimBarrier action '${action.label}' failed at ${ctx.clock.tick}", failure,
                )
            }
            index++
        }
        running.clear()

        drainedThisTick = size
        totalDrained += size
        return size
    }

    override fun toString(): String =
        "SimBarrier(pending=${pendingCount()}, drainedThisTick=$drainedThisTick, failed=$failedActions)"

    public companion object {
        /**
         * The key [GameContext] exposes the barrier under.
         *
         * A [ServiceKey] rather than a field on `GameContext`, because `GameContext` is a
         * frozen Wave 1 contract and its documented extension point is exactly this.
         */
        public val KEY: ServiceKey<SimBarrier> = serviceKey("SimBarrier")

        /** Enough for a busy tick without resizing; the lists then stay at their high water. */
        private const val DEFAULT_CAPACITY: Int = 32
    }
}

/**
 * The barrier this context's simulation drains.
 *
 * @throws dev.wildware.udea.core.MissingServiceException if none was registered — which
 *   means something built a context without [simBarrier] and its mutations would silently
 *   never land.
 */
public val GameContext.barrier: SimBarrier get() = this[SimBarrier.KEY]

/** The barrier, or `null` for a context that was built without one. */
public fun GameContext.barrierOrNull(): SimBarrier? = getOrNull(SimBarrier.KEY)

/** Registers [barrier] on the context being built. */
public fun GameContextBuilder.simBarrier(barrier: SimBarrier = SimBarrier()): SimBarrier {
    service(SimBarrier.KEY, barrier)
    return barrier
}
