package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.dispatch.AgentContext
import dev.wildware.udea.annotations.AgentTool
import dev.wildware.udea.annotations.Arg
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What `close` runs. Implemented by whatever owns the loop and the HTTP surface.
 *
 * Declared here rather than in `udea-agent-host` because the tool is here and a tool may not
 * name a module that depends on this one. A host implements it over its own teardown -
 * `dev.wildware.udea.agent.host.HostShutdown` is the engine's implementation - and a
 * `SimHarness` run implements it with a flag, which is what makes the tool testable without an
 * HTTP server.
 */
public fun interface GameShutdown {

    /**
     * Tears the game down, normally.
     *
     * Called **once**, on the simulation thread, from [AgentContext.defer] - so after every
     * system of the tick and outside the `SimBarrier` drain the tool call was inside. That is
     * the only point at which stopping the loop and unbinding the port is safe: a drain that
     * lost its world half-way through is the torn state the barrier exists to prevent.
     *
     * It must not throw and must not block for long: the caller is the frame loop, and the port
     * going quiet is the only signal the bridge is waiting on.
     */
    public fun shutdown(reason: String)
}

/**
 * `close`: the command that ends an instance without anybody killing a process.
 *
 * ## Why the name has no toolset in front of it
 *
 * Every other tool on this surface is `<toolset>.<function>`, and [SayToolset] carries the
 * argument for why. `close` is the exception because it is not ours to name: `game-bridge-mcp`
 * publishes `close` in its own toolset for every conforming game and sends it as a bare
 * `GET /command?cmd=close`, and `docs/contracts/replicator.md`'s sibling - the bridge README -
 * is frozen. A tool called `lifecycle.close` would be a tool the bridge never calls.
 * [dev.wildware.udea.agent.host.ToolManifest.toolsetOf] already groups an unqualified name
 * under the default toolset, so the manifest is well-formed with it in.
 *
 * ## Why the answer is returned and will usually never arrive
 *
 * `close` is the one command with no confirmation to wait for: the port is gone before the
 * digest carrying `completedCommandId` can be read, which is why the bridge waits for silence
 * instead. The result is still built, and honestly - `AgentDispatcher` completes every command
 * or the caller reports a healthy game as frozen, and a `SimHarness` caller (no HTTP, no port to
 * go quiet) reads this answer and nothing else.
 *
 * ## Idempotent, because two `close`s are one shutdown
 *
 * A bridge that retries, or two agents driving one instance, must not run teardown twice: the
 * second call answers `alreadyClosing` and defers nothing.
 */
public class LifecycleToolset(
    private val bridge: AgentBridge,
    private val shutdown: GameShutdown,
) {

    private val requested = AtomicBoolean(false)

    /** Whether `close` has been accepted. Read by a host that wants to know why its loop ended. */
    public val closeRequested: Boolean get() = requested.get()

    /**
     * Accepts the close, records it, and defers the teardown to the end of this tick.
     *
     * The event is recorded **before** the teardown runs, so a `SimHarness` transcript and a
     * `/state` that beat the shutdown both show who asked and why. It is the last thing the ring
     * will hold.
     */
    @AgentTool(
        name = "close",
        description = "Shut this instance down through its normal teardown and release the " +
            "port, instead of killing the process. Call it when you are finished with an " +
            "instance you started. There is no completion to wait for: the game is on its " +
            "way out before any answer could be read, so treat the port going quiet as the " +
            "confirmation.",
    )
    public fun close(
        context: AgentContext,
        @Arg(
            description = "One line saying why, recorded as the last event in the ring.",
            required = false,
            default = DEFAULT_REASON,
        )
        reason: String,
    ): AgentResult {
        val why = reason.takeIf { it.isNotBlank() } ?: DEFAULT_REASON
        val first = requested.compareAndSet(false, true)
        if (first) {
            bridge.events.record("$EVENT_PREFIX$why", context.tick.value)
            context.defer { shutdown.shutdown(why) }
        }
        return AgentResult.ok {
            put("closing", true)
            put("alreadyClosing", !first)
            put("reason", why)
            put("tick", context.tick.value)
        }
    }

    override fun toString(): String = "LifecycleToolset(closeRequested=$closeRequested)"

    public companion object {

        /** What `reason` is when the caller gives none. */
        public const val DEFAULT_REASON: String = "agent asked to close"

        /** The event this tool records, so `events.assert_event` can be pointed at it. */
        public const val EVENT_PREFIX: String = "close:"
    }
}
