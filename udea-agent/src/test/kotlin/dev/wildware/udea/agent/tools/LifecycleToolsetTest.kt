package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.core.loop.barrier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `close` is a tool that runs, answers, and tears the game down once.
 *
 * ## The state this replaces
 *
 * There was no `close` tool at all. `game-bridge-mcp` publishes one for every conforming game
 * and sends it as a bare `?cmd=close`; this engine answered `no_such_tool`, so `stop_instance`
 * fell through to killing the process tree and the CI conformance step's "the port went quiet"
 * assertion was a fact about `kill(2)`. Everything below is therefore about the tool existing
 * and being reachable **by that exact name**, through the bridge, with no harness shortcut -
 * `ToolsetHarness.call` submits and pumps exactly as an HTTP handler would.
 */
class LifecycleToolsetTest {

    @Test
    fun `close is dispatchable by the bare name the bridge sends`() {
        val harness = ToolsetHarness()

        val result = harness.callOk("close")

        assertTrue(result.contains("\"closing\":true"), result)
        assertTrue(result.contains("\"alreadyClosing\":false"), result)
        assertEquals(LifecycleToolset.DEFAULT_REASON, harness.closedWith)
    }

    @Test
    fun `teardown runs outside the barrier drain the tool was called in`() {
        val harness = ToolsetHarness()
        // A falsifiable version of "it is deferred". `SimBarrier.drain` refuses to re-enter -
        // `check(!draining)` - because a nested drain swaps the batch the outer one is walking
        // and destroys every action queued since it started. So a teardown that can itself
        // drain the barrier is provably *not* running inside one, and one that cannot throws
        // here with the barrier's own message. Nothing else about the call distinguishes the
        // two, which is why this and not a flag.
        var drained: Int? = null
        var refusal: Throwable? = null
        harness.onShutdown = {
            try {
                drained = harness.host.ctx.barrier.drain(harness.world, harness.host.ctx)
            } catch (failure: IllegalStateException) {
                refusal = failure
            }
        }

        harness.callOk("close", "reason" to "ordering probe")

        assertNull(refusal, "close ran its teardown inside the drain it was dispatched in: $refusal")
        assertNotNull(drained, "the teardown never ran at all")
        assertEquals("ordering probe", harness.closedWith)
    }

    @Test
    fun `a second close is refused as a no-op rather than running teardown twice`() {
        val harness = ToolsetHarness()
        harness.callOk("close", "reason" to "first")

        val second = harness.callOk("close", "reason" to "second")

        assertTrue(second.contains("\"alreadyClosing\":true"), second)
        assertEquals(
            "first",
            harness.closedWith,
            "the second close must not re-run teardown; a host that stopped twice would " +
                "unbind a port it had already unbound and stop a loop somebody else restarted",
        )
    }

    @Test
    fun `close records why, so the last thing in the ring says who ended the session`() {
        val harness = ToolsetHarness()

        harness.callOk("close", "reason" to "conformance run finished")

        assertTrue(
            harness.bridge.events.toList().any {
                it == "${LifecycleToolset.EVENT_PREFIX}conformance run finished"
            },
            harness.bridge.events.toList().toString(),
        )
    }

    @Test
    fun `close is in the manifest under the default toolset, spelled the way the bridge sends it`() {
        assertEquals(
            listOf("close"),
            EngineToolModules.Lifecycle.tools.map { it.name },
            "the bridge sends GET /command?cmd=close; any other spelling is a tool it never calls",
        )
    }

    private fun ToolsetHarness.callOk(name: String, vararg args: Pair<String, String>): String {
        val result = call(name, *args)
        return assertIs<AgentResult.Ok>(result, "$name failed: $result").json
    }
}
