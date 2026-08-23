package dev.wildware.udea.agent.harness

import com.github.quillraven.fleks.World
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentSubmission
import dev.wildware.udea.agent.dispatch.AgentRuntime
import dev.wildware.udea.agent.dispatch.DigestPublisher
import dev.wildware.udea.agent.dispatch.ToolRegistry
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.host.CaptureOutcome
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.loop.TimeControl

/**
 * Drives a headless game through **exactly** the entry point an HTTP command uses.
 *
 * ## The identity that is the point of the whole thing
 *
 * Spec 4 describes `udea-agent` as "MCP surface + test harness - *same code path*". So [call]
 * does not reach into the world, does not call a tool function, and does not know what a
 * toolset is. It does what the HTTP handler does and nothing else:
 *
 * ```
 * bridge.submit(command)          // the only way in, from any thread
 *   -> AgentRuntime.beforeFrame   // drain onto the SimBarrier
 *     -> SimBarrier.drain         // at a tick boundary, before any system
 *       -> AgentDispatcher        // contains every throw as ok:false
 *         -> ToolRegistry.invoke  // the generated or hand-written tool
 * ```
 *
 * There is no harness-only shortcut, and there is nowhere to add one: this class holds a
 * [AgentBridge] and an [AgentRuntime] and has no reference to the tool index at all. A
 * scenario an agent found is therefore a test you can check in, and a test that passes is
 * evidence about the agent surface rather than about a parallel path that resembles it.
 *
 * ## Synchronous, with no sleeps and no threads
 *
 * [call] returns the command's own typed result. It gets there by *pumping the host loop* - the
 * same `beforeFrame` / `afterFrame` pair a render backend calls - until
 * [AgentBridge.completedCommandId] covers the id it submitted. Nothing sleeps, nothing polls a
 * clock, and this class creates no thread: the simulation runs on the caller's thread, so
 * `Thread.activeCount()` is the same before and after a whole session.
 *
 * ## A pumped iteration does not advance the tick
 *
 * [call] pumps with `afterFrame(0)`, which is the paused-world drain: the barrier is drained
 * directly and no tick is run. That is deliberate and it is what makes an assertion about time
 * possible at all - a tool call must not move the clock as a side effect of being delivered, or
 * `time.step(200)` would advance by 200 plus however many commands were in flight. The
 * simulation advances when, and only when, something asks it to: [step], or `time.step`.
 *
 * ## Headless, and GL tools say so
 *
 * The mode is [RenderMode.Headless], which is also what the dedicated server, CI and
 * fast-forward run in. [screenshot] returns a typed [NO_RENDER_CONTEXT] naming the mode rather
 * than throwing or handing back a blank image - an agent diffing screenshots would read a blank
 * one as a black screen and act on it.
 */
public class SimHarness(
    /** The game under test. Must be [RenderMode.Headless]; see the class KDoc. */
    public val host: GameHost,
    /** The bridge every command crosses. Shared with whatever else is watching this game. */
    public val bridge: AgentBridge,
    tools: ToolRegistry,
    /** Publishes the Tier-0 document. `StateDigest` in a real host. */
    digest: DigestPublisher,
) {

    init {
        require(host.mode == RenderMode.Headless) {
            "SimHarness runs a game with no GL context and got ${host.mode}; construct the " +
                "GameHost with RenderMode.Headless"
        }
    }

    private val runtime = AgentRuntime(
        bridge = bridge,
        tools = tools,
        world = host.world,
        ctx = host.ctx,
        digest = digest,
    )

    /** The world under test. For a test that wants to assert on it directly. */
    public val world: World get() = host.world

    /** Engine services: the clock, the RNG, the barrier. */
    public val ctx: GameContext get() = host.ctx

    /** Pause, step, rewind. The same instance `time.*` mutates. */
    public val time: TimeControl get() = host.time

    /** The tick about to be simulated. */
    public val tick: Tick get() = host.tick

    /** Host iterations pumped since construction. What `frame` in the digest counts. */
    public var iterations: Long = 0L
        private set

    /**
     * Runs [ticks] simulation ticks, delivering any queued commands first.
     *
     * The ticks are ticks. Not frames, not seconds, not "about 200" - the defect at
     * `DebugBridge.kt:40` is a harness that waited on render frames and made `step(n)`
     * approximate, which silently made every determinism measurement compare runs of different
     * lengths.
     */
    public fun step(ticks: Int = 1): SimHarness = apply {
        require(ticks >= 0) { "tick count must not be negative, was $ticks" }
        runtime.beforeFrame()
        host.run(ticks)
        runtime.afterFrame(ticks)
        iterations++
    }

    /**
     * Submits [name] with [args] and returns that command's own typed result.
     *
     * @throws IllegalStateException if the command does not complete within [MAX_PUMPS]
     *   iterations. A tool runs to completion inside one barrier drain, so one pump is always
     *   enough and the ceiling exists to turn a wiring mistake - a harness whose runtime is not
     *   the one the bridge feeds - into a failure with a message rather than a hang. It is
     *   deliberately not a *timeout*: nothing here is timed, so the failure is reproducible.
     */
    public fun call(name: String, args: Map<String, String> = emptyMap()): AgentResult {
        val command = AgentCommand(name, args)
        return when (val submission = bridge.submit(command)) {
            is AgentSubmission.Rejected -> AgentResult.Failed(submission.error)
            is AgentSubmission.Accepted -> pumpUntilComplete(submission.commandId, name)
        }
    }

    /** [call] with the arguments written as pairs, which is how a test reads best. */
    public fun call(name: String, vararg args: Pair<String, String>): AgentResult =
        call(name, args.toMap())

    /** The most recently published Tier-0 document, and marks it read. */
    public fun digest(): String = bridge.snapshot()

    /** Recent game events, oldest first. Non-destructive. */
    public fun events(): List<String> = bridge.events.toList()

    /**
     * The captured frame, or a typed refusal naming the mode.
     *
     * Always the refusal today: a [SimHarness] is headless by construction. It exists here
     * rather than only in the render toolset so that "a GL tool under Headless answers
     * `no_render_context`" is a property this module can assert without a GL context on the
     * test machine.
     */
    public fun screenshot(): AgentResult = when (val outcome = host.screenshot()) {
        is CaptureOutcome.Captured -> AgentResult.ok { put("bytes", outcome.image.size) }
        is CaptureOutcome.Unavailable -> AgentResult.failed(
            AgentErrorKind(outcome.reason.code),
            "this game is running in ${host.mode}, which has no render context, so nothing can " +
                "be captured; start it in ${RenderMode.Offscreen} or ${RenderMode.Windowed} to " +
                "take pictures",
        )
    }

    private fun pumpUntilComplete(commandId: Long, name: String): AgentResult {
        var pumps = 0
        while (bridge.completedCommandId() < commandId) {
            check(pumps < MAX_PUMPS) {
                "$name (command #$commandId) did not complete after $MAX_PUMPS pumped " +
                    "iterations; completedCommandId is ${bridge.completedCommandId()}. A tool " +
                    "runs to completion inside one barrier drain, so this means the bridge this " +
                    "harness submits to is not the one its AgentRuntime drains"
            }
            // No tick: delivering a command must not move the clock. See the class KDoc.
            runtime.beforeFrame()
            runtime.afterFrame(0)
            iterations++
            pumps++
        }
        return bridge.commandResults().lastOrNull { it.id == commandId }?.result
            ?: error(
                "$name (command #$commandId) completed but its result is no longer in the ring; " +
                    "raise AgentBridge's resultCapacity above ${bridge.commandResults().size}",
            )
    }

    override fun toString(): String =
        "SimHarness(tick=${tick.value}, iterations=$iterations, paused=${host.time.paused})"

    public companion object {

        /** A GL-requiring tool called in a mode with no render context. */
        public val NO_RENDER_CONTEXT: AgentErrorKind = AgentErrorKind("no_render_context")

        /**
         * Iterations [call] will pump before it gives up.
         *
         * Two, and one of those is slack. A command is drained onto the barrier by
         * `beforeFrame` and applied by the `afterFrame(0)` in the same iteration, so a healthy
         * call completes on the first. A ceiling rather than a wait because a harness that
         * hangs is a harness whose failure nobody can read.
         */
        public const val MAX_PUMPS: Int = 2
    }
}
