package dev.wildware.udea.agent.harness

import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentSubmission
import dev.wildware.udea.agent.Health
import dev.wildware.udea.agent.tools.ToolsetHarness
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * "MCP surface + test harness - **same code path**", asserted rather than claimed.
 *
 * The identity is what makes a scenario an agent found into a test you can check in, and what
 * makes a passing test evidence about the agent surface rather than about a parallel path that
 * resembles it. So the first test here drives the same tool both ways and compares.
 */
class SimHarnessTest {

    @Test
    fun `call and a direct bridge submission produce identical results and digests`() {
        val viaCall = ToolsetHarness()
        val viaBridge = ToolsetHarness()
        viaCall.place(health = 60f)
        viaBridge.place(health = 60f)

        val called = assertIs<AgentResult.Ok>(
            viaCall.call("world.query_entities", "with" to "Health", "fields" to "health.current"),
        )

        // The other one goes through AgentBridge.submit by hand and is pumped by the same
        // runtime, which is the only thing `call` does.
        val submission = viaBridge.bridge.submit(
            AgentCommand(
                "world.query_entities",
                mapOf("with" to "Health", "fields" to "health.current"),
            ),
        )
        assertIs<AgentSubmission.Accepted>(submission)
        viaBridge.sim.step(0)
        val submitted = assertIs<AgentResult.Ok>(
            viaBridge.bridge.commandResults().last { it.id == submission.commandId }.result,
        )

        assertEquals(called.json, submitted.json)

        viaCall.digest.publish()
        viaBridge.digest.publish()
        // From `simFrame` to `completedCommandId`: the simulation's own half of the document.
        // `frame` counts host iterations and the command id is process-global, so neither is a
        // property of the world either path produced.
        fun simulationHalf(document: String) =
            document.substringAfter("\"simFrame\"").substringBefore("\"completedCommandId\"")
        assertEquals(
            simulationHalf(viaCall.bridge.snapshot()),
            simulationHalf(viaBridge.bridge.snapshot()),
            "the two paths must leave the world in the same state",
        )
    }

    @Test
    fun `the harness creates no thread of its own`() {
        val before = Thread.activeCount()
        val harness = ToolsetHarness()

        harness.ok("world.spawn_blueprint", "blueprint" to "grunt")
        harness.ok("time.pause")
        harness.ok("time.step", "ticks" to "200")
        harness.ok("diag.frame_report")

        // No sleeps and no threads: the simulation runs on the caller's thread, so a harness
        // test is as deterministic as the simulation is.
        assertEquals(before, Thread.activeCount(), "the harness must not start a thread")
    }

    @Test
    fun `spawn twenty, step 200, rewind 100 and find the entity whose health changed`() {
        // The Phase 1 workflow, end to end, entirely through call().
        val harness = ToolsetHarness()
        harness.ok("time.pause")
        val ids = List(20) { index ->
            val spawned = harness.ok(
                "world.spawn_blueprint",
                "blueprint" to "grunt",
                "x" to index.toString(),
                "y" to "0",
            )
            NetId.ofRaw(Regex("\"id\":(-?\\d+)").find(spawned)!!.groupValues[1].toInt())
        }
        harness.ok("time.step", "ticks" to "200")
        val marked = ids[7]
        harness.ok("time.snapshot")
        val tickAtSnapshot = harness.host.tick.value

        harness.ok(
            "world.set_component_field",
            "id" to marked.raw.toString(),
            "component" to "Health",
            "field" to "current",
            "value" to "3",
        )
        harness.ok("time.step", "ticks" to "100")

        // The question the demo ends on: which entity's health is not what a grunt spawns with?
        val changed = harness.ok(
            "world.query_entities",
            "with" to "Health",
            "where" to "health.current<40",
            "fields" to "health.current",
        )
        assertTrue(changed.contains("\"total\":1"), changed)
        assertTrue(changed.contains("\"id\":${marked.raw}"), changed)

        harness.ok("time.rewind", "ticks" to "100")

        assertEquals(tickAtSnapshot, harness.host.tick.value)
        val afterRewind = harness.ok(
            "world.query_entities",
            "with" to "Health",
            "where" to "health.current<40",
        )
        assertTrue(afterRewind.contains("\"total\":0"), "the change must have been rewound: $afterRewind")
        val restored = assertNotNull(harness.netIds.resolveOrNull(marked))
        with(harness.world) { assertEquals(40f, restored[Health].current) }
    }

    @Test
    fun `a GL tool under Headless answers no_render_context naming the mode`() {
        val harness = ToolsetHarness()

        val refusal = assertIs<AgentResult.Failed>(harness.sim.screenshot())

        assertEquals(SimHarness.NO_RENDER_CONTEXT, refusal.error.kind)
        assertTrue(refusal.error.message.contains(RenderMode.Headless.name), refusal.error.message)
        assertTrue(
            refusal.error.message.contains(RenderMode.Offscreen.name),
            "the refusal must name the mode that would work: ${refusal.error.message}",
        )
    }

    @Test
    fun `a rejected submission comes back as the command's own answer`() {
        val harness = ToolsetHarness()
        // Fill the queue without draining it, so the next submission is refused.
        repeat(harness.bridge.queueCapacity) { harness.bridge.submit(AgentCommand("diag.memory")) }

        val refused = assertIs<AgentResult.Failed>(harness.call("diag.memory"))

        assertEquals(dev.wildware.udea.agent.AgentErrorKind.QUEUE_FULL, refused.error.kind)
    }

    @Test
    fun `a tool that throws lands as ok false without stalling the loop`() {
        val harness = ToolsetHarness()

        // A malformed NetId reaches the query path and throws there, not in the dispatcher.
        val failed = assertIs<AgentResult.Failed>(
            harness.call("world.describe_entity", "id" to "not-a-number"),
        )

        assertEquals(dev.wildware.udea.agent.AgentErrorKind.BAD_ARGUMENT, failed.error.kind)
        // Still ticking, and the next command confirms normally.
        harness.ok("time.pause")
        harness.ok("time.step", "ticks" to "1")
    }
}
