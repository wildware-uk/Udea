package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.state.DigestBudgets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The two toolsets that need nothing beyond Phase 1: `events` and `diag`.
 *
 * `gas` and `assets` are not here and are not stubbed - `udea-gas` and `udea-assets` are empty
 * modules, and a toolset written against a module that does not exist would be a manifest entry
 * an agent can call and nothing behind it. See the report on issue #72.
 */
class EventsAndDiagToolsetTest {

    // --- events ---------------------------------------------------------------------------

    @Test
    fun `recent_events reads the ring with tick stamps and does not consume it`() {
        val harness = ToolsetHarness()
        harness.ok("time.pause")
        harness.ok("time.step", "ticks" to "3")
        val netId = harness.place()
        harness.ok(
            "world.set_component_field",
            "id" to netId.raw.toString(),
            "component" to "Health",
            "field" to "current",
            "value" to "5",
        )

        val first = harness.ok("events.recent_events")
        val second = harness.ok("events.recent_events")

        assertTrue(first.contains("agent_mutation:world.set_component_field"), first)
        assertTrue(first.contains("\"tick\":3"), "the entry must carry the tick it happened on: $first")
        // Non-destructive: the bridge polls /state in a loop while it waits for a command, so a
        // read that drained the ring would delete the evidence on the polling loop's schedule.
        assertEquals(
            first.substringAfter("\"events\""),
            second.substringAfter("\"events\""),
            "reading the ring must not consume it",
        )
    }

    @Test
    fun `assert_event matches inside its window and misses outside it`() {
        val harness = ToolsetHarness()
        harness.ok("time.pause")
        harness.ok("time.step", "ticks" to "10")
        val netId = harness.place()
        harness.ok(
            "world.set_component_field",
            "id" to netId.raw.toString(),
            "component" to "Health",
            "field" to "current",
            "value" to "5",
        )

        val matched = harness.ok("events.assert_event", "contains" to "set_component_field", "within" to "5")
        assertTrue(matched.contains("\"matched\":true"), matched)
        assertTrue(matched.contains("\"tick\":10"), matched)

        harness.ok("time.step", "ticks" to "100")
        val missed = harness.failure(
            "events.assert_event",
            "contains" to "set_component_field",
            "within" to "5",
        )

        // A miss is an answer, not an exception: half the assertions an agent makes are that
        // something did *not* happen, and a throw would reach it as tool_threw.
        assertEquals(EventsToolset.EVENT_NOT_FOUND, missed.kind)
        assertTrue(missed.message.contains("set_component_field"), missed.message)
    }

    @Test
    fun `assert_event can be anchored to an absolute tick a previous call returned`() {
        val harness = ToolsetHarness()
        harness.ok("time.pause")
        harness.ok("time.step", "ticks" to "40")
        harness.bridge.event("wave_spawned:3", harness.host.tick.value)
        harness.ok("time.step", "ticks" to "500")

        val matched = harness.ok("events.assert_event", "contains" to "wave_spawned", "since" to "40")

        assertTrue(matched.contains("\"searchedFromTick\":40"), matched)
        assertTrue(matched.contains("\"tick\":40"), matched)
    }

    @Test
    fun `clear_events empties the ring but not the history counter`() {
        val harness = ToolsetHarness()
        harness.bridge.event("a", 0)
        harness.bridge.event("b", 0)

        val cleared = harness.ok("events.clear_events")

        assertTrue(cleared.contains("\"dropped\":2"), cleared)
        assertTrue(cleared.contains("\"totalRecorded\":2"), "history happened: $cleared")
        assertEquals(0, harness.bridge.events.size)
    }

    // --- diag -----------------------------------------------------------------------------

    @Test
    fun `system_timings carries the digest build and says what it does not know`() {
        val harness = ToolsetHarness()
        harness.digest.publish()

        val timings = harness.ok("diag.system_timings")

        // The agent surface's own cost, which is how the 0.3ms budget stays visible on the
        // machine that is actually slow rather than only in CI.
        assertTrue(timings.contains("\"name\":\"${DigestBudgets.TIMING_NAME}\""), timings)
        assertTrue(timings.contains("\"lastNanos\":"), timings)
        // And it is explicit about the per-system breakdown it cannot produce, rather than
        // reporting a confident zero for something nobody measured.
        assertTrue(timings.contains("does not instrument"), timings)
    }

    @Test
    fun `entity_counts sums to the entityCount the digest publishes`() {
        val harness = ToolsetHarness()
        repeat(7) { harness.place() }
        harness.digest.publish()
        val document = harness.bridge.snapshot()

        val counts = harness.ok("diag.entity_counts")

        assertTrue(counts.contains("\"entityCount\":7"), counts)
        assertTrue(document.contains("\"entityCount\":7"), document)
        val archetypeTotal = Regex("\"all\":(\\d+)").find(counts)?.groupValues?.get(1)?.toInt()
        assertEquals(7, assertNotNull(archetypeTotal), counts)
    }

    @Test
    fun `frame_report shows the digest against its budget and the barrier counters`() {
        val harness = ToolsetHarness()
        harness.digest.publish()

        val report = harness.ok("diag.frame_report")

        assertTrue(report.contains("\"budgetNanos\":${DigestBudgets.BUILD_NANOS}"), report)
        assertTrue(report.contains("\"maxBytes\":${DigestBudgets.MAX_BYTES}"), report)
        assertTrue(report.contains("\"failedActions\":0"), report)
        assertTrue(report.contains("\"wired\":true"), report)
    }

    @Test
    fun `memory reports the three numbers that answer is this run about to die`() {
        val harness = ToolsetHarness()

        val memory = harness.ok("diag.memory")

        assertTrue(memory.contains("\"usedBytes\":"), memory)
        assertTrue(memory.contains("\"committedBytes\":"), memory)
        assertTrue(memory.contains("\"maxBytes\":"), memory)
    }

    @Test
    fun `an unknown tool is one typed answer and does not stall the loop`() {
        val harness = ToolsetHarness()

        val error = harness.failure("diag.frame_reprot")

        assertEquals(AgentErrorKind.NO_SUCH_TOOL, error.kind)
        // The simulation kept ticking, which is the property the whole dispatch design exists
        // to make visible.
        harness.ok("diag.memory")
    }
}
