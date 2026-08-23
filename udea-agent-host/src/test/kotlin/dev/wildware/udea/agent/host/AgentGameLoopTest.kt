package dev.wildware.udea.agent.host

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentSubmission
import dev.wildware.udea.agent.dispatch.AgentRuntime
import dev.wildware.udea.agent.dispatch.ToolIndex
import dev.wildware.udea.agent.state.DigestSources
import dev.wildware.udea.agent.state.LoopStatus
import dev.wildware.udea.agent.state.StateDigest
import dev.wildware.udea.agent.tools.EngineToolModules
import dev.wildware.udea.agent.tools.TimeToolset
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The property the whole Phase 1 demo rests on: **pausing stops the simulation, not the surface.**
 *
 * Every step of the spec's demo — pause, spawn, step, screenshot, rewind, inspect — is issued to a
 * game whose tick is not moving. A loop that only drained the barrier inside a tick would hang all
 * six: the command would sit on the bridge queue, `completedCommandId` would never advance, and
 * the caller polling `/state` would report a perfectly healthy game as frozen.
 *
 * `GameHost.run()` is exactly such a loop — it calls `GameLoop.tickIfRunning()` and neither half of
 * the [AgentRuntime] pair — which is why [AgentGameLoop] exists and why this test is here rather
 * than in `udea-core`.
 *
 * Driven by hand through [AgentGameLoop.pump] with a zero delta: no threads, no sleeps, and no
 * wall clock, so a failure is reproducible rather than a flake.
 */
class AgentGameLoopTest {

    private val bridge = AgentBridge()

    private val host = GameHost(RenderMode.Headless, UdeaGameDef(modules = emptyList()))

    private val digest = StateDigest(
        bridge = bridge,
        sources = DigestSources(loop = HostLoopStatus(host)),
    )

    private val runtime = AgentRuntime(
        bridge = bridge,
        tools = EngineToolModules
            .wireAll(ToolIndex.builder(), TimeToolset(host.time, host.ctx.clock, bridge))
            .build(),
        world = host.world,
        ctx = host.ctx,
        digest = digest,
    )

    private val loop = AgentGameLoop(host, runtime)

    @Test
    fun `a command issued to a paused game still completes`() {
        host.loop.paused = true
        val tickBefore = host.tick

        val submission = assertIs<AgentSubmission.Accepted>(
            bridge.submit(AgentCommand("time.snapshot")),
        )

        // One iteration. `beforeFrame` posts to the barrier, `host.frame` runs no tick because
        // the loop is paused, and `afterFrame(0)` is what drains the barrier anyway.
        loop.pump(ZERO_DELTA)

        assertEquals(
            submission.commandId,
            bridge.completedCommandId(),
            "a paused game must still answer; a loop that drains only inside a tick hangs here",
        )
        assertEquals(tickBefore, host.tick, "delivering a command must not move the clock")
    }

    @Test
    fun `the published document keeps moving while the tick does not`() {
        host.loop.paused = true
        loop.pump(ZERO_DELTA)
        val first = bridge.snapshot()

        bridge.submit(AgentCommand("time.snapshot"))
        loop.pump(ZERO_DELTA)
        val second = bridge.snapshot()

        assertTrue(second.contains(""""paused":true"""), "the document reports the pause: $second")
        assertTrue(
            second.contains(""""completedCommandId":1"""),
            "the answer has to reach a document, not just the counter: $second",
        )
        assertTrue(first != second, "a frozen document is what a caller reads as a dead game")
    }

    @Test
    fun `an unpaused iteration runs ticks and still publishes`() {
        // The negative control: the drain has to work in both states, and a test that only ever
        // ran paused could not tell a working loop from one that never ticks at all.
        host.loop.paused = false
        val tickBefore = host.tick

        // A tenth of a second at 60Hz is six ticks, capped by `maxCatchUp`.
        loop.pump(0.1f)

        assertTrue(host.tick.value > tickBefore.value, "an unpaused iteration advances the clock")
        assertTrue(host.loop.lastFrameTicks > 0, "and reports the ticks it ran")
    }

    private companion object {
        /** No wall time at all: the pause path must not depend on any having elapsed. */
        const val ZERO_DELTA: Float = 0f
    }
}

/** The loop's own pause state, so the digest reports what the loop actually did. */
private class HostLoopStatus(private val host: GameHost) : LoopStatus {
    override val paused: Boolean get() = host.loop.paused
    override val timeScale: Float get() = host.loop.timeScale
    override val fps: Float get() = 0f
}
