package dev.wildware.udea.agent.dispatch

import com.github.quillraven.fleks.World
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentClock
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.loop.BarrierAction
import dev.wildware.udea.core.loop.SimBarrier
import dev.wildware.udea.core.loop.barrier

/**
 * Drives the agent surface from the host loop: commands in, deferred work out, digest published.
 *
 * ## Where agent mutations happen, and why there is only one answer
 *
 * Spec 3.3 unified four independently invented "apply between ticks" queues into one
 * [SimBarrier], drained at the top of `Simulation.step()` before any system runs. Agent tool
 * calls are one of the four. So this class does **not** apply a command; it posts one as a
 * [BarrierAction] and lets the barrier apply it at the boundary the barrier exists to define.
 * The consequence the agent can observe is the one the barrier promises: a command submitted
 * while tick 412 is running is seen by *every* system on tick 413 and by *none* on tick 412.
 * No system ever sees a torn world.
 *
 * ## The order of one host iteration
 *
 * ```
 * runtime.beforeFrame()      // drain the bridge queue onto the barrier
 * loop.frame(wallDelta)      // 0..n x Simulation.step(): barrier drain, systems, clock
 * runtime.afterFrame(ticks)  // deferred work, then publish, then advance the frame counter
 * ```
 *
 * Two details in that order are load-bearing.
 *
 * **Deferred work runs before the digest is published.** Publishing first would show an agent
 * a world one step stale from its own mutation, which is indistinguishable from a command that
 * silently failed - and an agent that cannot distinguish those two spends its context window
 * on the wrong one.
 *
 * **`afterFrame(0)` drains the barrier itself.** With the simulation paused the loop takes no
 * steps, so nothing drains the barrier, so `resume` can never arrive and the instance is
 * wedged with a bridge that answers `/health` cheerfully. A paused world still drains.
 */
public class AgentRuntime(
    private val bridge: AgentBridge,
    tools: ToolRegistry,
    private val world: World,
    private val ctx: GameContext,
    /** Rebuilds and publishes the state document. `StateDigest` in a real host. */
    private val digest: DigestPublisher,
    clock: AgentClock = AgentClock.System,
) {

    private val barrier: SimBarrier = ctx.barrier

    private val deferred = DeferredQueue()

    private val dispatcher = AgentDispatcher(bridge, tools, deferred, clock)

    /** Reused across frames: draining allocated an `ArrayList` per frame in the reference. */
    private val drained = ArrayList<AgentCommand>(INITIAL_DRAIN_CAPACITY)

    /** Commands posted to the barrier since construction. */
    public var totalDispatched: Long = 0L
        private set

    /**
     * Moves every queued command onto the barrier. Call before the simulation steps.
     *
     * @return how many commands were posted.
     */
    public fun beforeFrame(): Int {
        val count = bridge.drain(drained)
        if (count == 0) return 0
        var index = 0
        while (index < count) {
            barrier.submit(ToolCall(drained[index], dispatcher))
            index++
        }
        drained.clear()
        totalDispatched += count
        return count
    }

    /**
     * Runs deferred work, publishes the state document and advances the frame counter.
     *
     * @param ticksStepped how many ticks the loop actually ran this iteration.
     *   `GameLoop.lastFrameTicks` is exactly this number. Zero means nothing drained the
     *   barrier, so this method drains it - see the class KDoc.
     */
    public fun afterFrame(ticksStepped: Int) {
        require(ticksStepped >= 0) { "ticksStepped must not be negative, was $ticksStepped" }
        if (ticksStepped == 0) barrier.drain(world, ctx)

        deferred.runAll { label, failure ->
            bridge.event("deferred_failed:$label:${failure.javaClass.simpleName}")
            ctx.log.error("deferred agent work for $label failed at ${ctx.clock.tick}", failure)
        }

        bridge.publishTick(ctx.clock.tick.value)
        digest.publishIfDue()
        bridge.advanceFrame()
    }

    private companion object {
        /** A busy frame with several tool calls, without a resize. */
        const val INITIAL_DRAIN_CAPACITY: Int = 16
    }
}

/**
 * Rebuilds and publishes the state document, if it is due.
 *
 * An interface rather than a direct call on `StateDigest` so that this file carries no opinion
 * about what the document contains, and so a host that publishes something else - a test
 * double asserting publication order, a headless harness - is a legitimate implementation
 * rather than a mock of one.
 */
public fun interface DigestPublisher {
    /** Called once per host iteration, after deferred work. May decide to do nothing. */
    public fun publishIfDue()
}

/**
 * One queued tool call, as the barrier sees it.
 *
 * [label] names the tool, so a barrier failure log, a desync triage and `describe` all answer
 * "why did the world change between tick 412 and 413" with the tool that changed it.
 */
private class ToolCall(
    private val command: AgentCommand,
    private val dispatcher: AgentDispatcher,
) : BarrierAction {

    override val label: String get() = "agent:${command.name}"

    override fun apply(world: World, ctx: GameContext) {
        dispatcher.run(command, world, ctx)
    }
}
