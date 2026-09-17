package dev.wildware.udea.agent.dispatch

import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentToolException
import dev.wildware.udea.core.Tick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Command dispatch, against the four promises spec 6 makes about it.
 *
 * Every test here drives the real host sequence through [DispatchHarness] rather than calling
 * the dispatcher, because three of the four promises are about *when* a command lands rather
 * than whether it runs.
 */
class AgentDispatchTest {

    @Test
    fun `a throwing tool lands as ok false and the loop keeps ticking`() {
        val harness = DispatchHarness()
        harness.tools.register("explode") { error("the tool broke") }
        val id = harness.submit("explode")

        harness.hostIteration()

        val result = harness.bridge.commandResults().single { it.id == id }.result
        val failed = assertIs<AgentResult.Failed>(result)
        assertEquals(AgentErrorKind.TOOL_THREW, failed.error.kind)
        assertTrue(failed.error.message.contains("explode"), failed.error.message)

        // Advancing for a failure is the whole point: a caller polling for its answer must be
        // released by the command finishing, not by it succeeding.
        assertEquals(id, harness.bridge.completedCommandId())

        harness.run(100)

        assertEquals(101, harness.first.entityCountPerTick.size)
        assertEquals(Tick(101), harness.ctx.clock.tick)
    }

    @Test
    fun `a tool that throws a typed failure keeps its kind`() {
        val harness = DispatchHarness()
        harness.tools.register("inspect") {
            throw AgentToolException(AgentErrorKind.NO_SUCH_ENTITY, "no entity 412")
        }
        val id = harness.submit("inspect")

        harness.hostIteration()

        val failed = assertIs<AgentResult.Failed>(
            harness.bridge.commandResults().single { it.id == id }.result,
        )
        assertEquals(AgentErrorKind.NO_SUCH_ENTITY, failed.error.kind)
        assertEquals("no entity 412", failed.error.message)
    }

    @Test
    fun `an unknown tool is one answer and never reaches the registry`() {
        val harness = DispatchHarness()
        val id = harness.submit("no_such_thing")

        harness.hostIteration()

        val failed = assertIs<AgentResult.Failed>(
            harness.bridge.commandResults().single { it.id == id }.result,
        )
        assertEquals(AgentErrorKind.NO_SUCH_TOOL, failed.error.kind)
        assertEquals(emptyList(), harness.tools.calls)
        assertEquals(id, harness.bridge.completedCommandId())
    }

    @Test
    fun `a mutation is seen by every system on the following tick and by none on the current one`() {
        val harness = DispatchHarness()
        harness.tools.register("spawn") { context ->
            context.world.entity { }
            AgentResult.EMPTY
        }

        // Submitted from inside tick 0, which is the interleaving the barrier exists for: an
        // HTTP thread submits while a tick is half-run.
        harness.first.duringTick = {
            if (harness.ctx.clock.tick == Tick.ZERO) harness.submit("spawn")
        }

        harness.run(3)

        // Tick 0: submitted mid-tick, so nothing sees it. Tick 1: the host iteration moved it
        // onto the barrier and the barrier applied it before phase zero, so both systems do.
        assertEquals(listOf(0, 1, 1), harness.first.entityCountPerTick)
        assertEquals(listOf(0, 1, 1), harness.second.entityCountPerTick)
    }

    @Test
    fun `no system sees a half-applied batch`() {
        val harness = DispatchHarness()
        harness.tools.register("spawn_many") { context ->
            repeat(5) { context.world.entity { } }
            AgentResult.EMPTY
        }
        harness.submit("spawn_many")
        harness.submit("spawn_many")

        harness.run(2)

        // Ten or none, on every tick and in every system. Never four.
        assertEquals(listOf(10, 10), harness.first.entityCountPerTick)
        assertEquals(listOf(10, 10), harness.second.entityCountPerTick)
    }

    @Test
    fun `a paused simulation still drains commands and resume takes effect`() {
        val harness = DispatchHarness()
        harness.paused = true
        harness.tools.register("resume") {
            harness.paused = false
            AgentResult.EMPTY
        }
        val id = harness.submit("resume")

        harness.hostIteration()

        // Without the barrier-only drain in afterFrame(0) this is the wedge: the loop takes no
        // steps, so nothing drains the queue, so resume can never arrive - and /health answers
        // cheerfully throughout.
        assertEquals(id, harness.bridge.completedCommandId())
        assertEquals(listOf("resume"), harness.tools.calls)
        assertEquals(false, harness.paused)
        assertEquals(0, harness.first.entityCountPerTick.size, "a paused loop must not tick")

        harness.hostIteration()

        assertEquals(1, harness.first.entityCountPerTick.size)
    }

    @Test
    fun `deferred work runs after every system and before the digest is published`() {
        val harness = DispatchHarness()
        harness.tools.register("swap_scene") { context ->
            harness.digest.order += "tool"
            context.defer { harness.digest.order += "deferred" }
            AgentResult.EMPTY
        }
        harness.first.duringTick = { harness.digest.order += "system" }
        harness.submit("swap_scene")

        harness.hostIteration()

        // Publishing before the deferred work would show an agent a world one step stale from
        // its own mutation, which is indistinguishable from a command that silently failed.
        assertEquals(listOf("tool", "system", "deferred", "publish"), harness.digest.order)
    }

    @Test
    fun `deferred work runs once, not on every subsequent frame`() {
        val harness = DispatchHarness()
        harness.tools.register("once") { context ->
            context.defer { harness.digest.order += "deferred" }
            AgentResult.EMPTY
        }
        harness.submit("once")

        harness.run(3)

        assertEquals(1, harness.digest.order.count { it == "deferred" })
    }

    @Test
    fun `a throwing deferred item does not strand the rest or the loop`() {
        val harness = DispatchHarness()
        harness.tools.register("two_deferred") { context ->
            context.defer { error("deferred boom") }
            context.defer { harness.digest.order += "second" }
            AgentResult.EMPTY
        }
        harness.submit("two_deferred")

        harness.run(2)

        assertTrue(harness.digest.order.contains("second"), harness.digest.order.toString())
        assertTrue(
            harness.bridge.events.toList().any { it.startsWith("deferred_failed:two_deferred") },
            harness.bridge.events.toList().toString(),
        )
        assertEquals(2, harness.first.entityCountPerTick.size)
    }

    @Test
    fun `a tool over its budget emits exactly one slow_tool event naming the tool`() {
        val harness = DispatchHarness()
        harness.clock.advancePerCall = 5_000_000L
        harness.tools.register("slow_query", budgetMs = 1L) { AgentResult.EMPTY }
        harness.submit("slow_query")

        harness.hostIteration()

        val slow = harness.bridge.events.toList().filter { it.startsWith("slow_tool:") }
        assertEquals(1, slow.size, "expected exactly one slow_tool event, got $slow")
        assertTrue(slow.single().startsWith("slow_tool:slow_query:5ms"), slow.single())
    }

    @Test
    fun `a tool inside its budget emits nothing`() {
        val harness = DispatchHarness()
        harness.clock.advancePerCall = 100_000L
        harness.tools.register("quick", budgetMs = 1L) { AgentResult.EMPTY }
        harness.submit("quick")

        harness.hostIteration()

        assertEquals(emptyList(), harness.bridge.events.toList())
    }

    @Test
    fun `a tool with no declared budget is never reported slow`() {
        val harness = DispatchHarness()
        harness.clock.advancePerCall = 500_000_000L
        harness.tools.register("unbudgeted") { AgentResult.EMPTY }
        harness.submit("unbudgeted")

        harness.hostIteration()

        assertEquals(emptyList(), harness.bridge.events.toList())
    }

    @Test
    fun `a queue-full rejection is a value the submitter receives, not an exception`() {
        val harness = DispatchHarness()
        harness.tools.register("noop") { AgentResult.EMPTY }
        repeat(dev.wildware.udea.agent.AgentBridge.DEFAULT_QUEUE_CAPACITY) { harness.submit("noop") }

        val rejected = harness.bridge.submit(dev.wildware.udea.agent.AgentCommand("noop"))

        assertIs<dev.wildware.udea.agent.AgentSubmission.Rejected>(rejected)
        assertEquals(AgentErrorKind.QUEUE_FULL, rejected.error.kind)

        harness.hostIteration()

        // The accepted ones still ran; the rejection cost the caller nothing but a retry.
        assertEquals(
            dev.wildware.udea.agent.AgentBridge.DEFAULT_QUEUE_CAPACITY,
            harness.tools.calls.size,
        )
    }

    @Test
    fun `the barrier action is labelled with the tool that mutated the world`() {
        val harness = DispatchHarness()
        harness.tools.register("boom") { error("no") }
        harness.submit("boom")

        harness.hostIteration()

        // The dispatcher contains the failure, so the barrier must never see one: a failed
        // action there would mean the command never completed.
        assertEquals(0L, harness.barrier.failedActions)
        assertEquals(1L, harness.barrier.totalDrained)
    }

    @Test
    fun `the frame counter advances once per host iteration, paused or not`() {
        val harness = DispatchHarness()
        harness.run(2)
        harness.paused = true
        harness.run(3)

        assertEquals(5L, harness.bridge.frame)
        assertEquals(2, harness.first.entityCountPerTick.size)
    }

    @Test
    fun `the published tick follows the clock`() {
        val harness = DispatchHarness()

        harness.run(4)

        assertEquals(4L, harness.bridge.tick)
    }
}
