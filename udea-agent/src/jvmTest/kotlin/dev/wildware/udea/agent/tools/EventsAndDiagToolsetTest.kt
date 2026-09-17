package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.Json
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

    /**
     * A `recent_events` answer reaches the agent, which it previously never did.
     *
     * ## The defect this pins
     *
     * A command answer is delivered only through the digest's `commandResults` array, and
     * `CommandResultRing.fitting` costs each entry against
     * [DigestBudgets.RESULT_CEILING] - 1280 - dropping any that does not fit rather than
     * shortening it. An unpaged `recent_events` over a populated ring renders **thousands** of
     * characters, so it never fitted: on every single poll it was dropped, and the caller
     * waiting on its own command id read `commandResultsTruncated: true` and no events, forever.
     * The tool was in the manifest, dispatched, ran, and could not deliver one line.
     *
     * ## Measured at the guarantee, not at the ceiling
     *
     * The document is padded so that exactly [DigestBudgets.RESULT_MIN_BYTES] is left, because
     * 1280 is only what is available on a quiet frame and 256 is what `commandResults` is
     * promised once `ui.elements` has spent its own ceiling. Testing against the leftovers is
     * how a paging test passes while the tool still disappears on a busy frame.
     */
    @Test
    fun `a recent_events answer survives the bytes a command result is guaranteed`() {
        val harness = ToolsetHarness()
        // A populated ring of realistic messages - the state in which the tool used to be
        // undeliverable, and the state any game past its first minute is in.
        repeat(200) { harness.bridge.event("champion_died:blue_team_carry:$it:killed_by_tower_2_dive") }

        val answer = harness.eventTools.recentEvents(
            limit = EventsToolset.DEFAULT_LIMIT,
            offset = 0,
            contains = null,
        )
        harness.bridge.complete(1L, answer)

        val document = paddedTo(DigestBudgets.RESULT_CEILING - DigestBudgets.RESULT_MIN_BYTES)
        val truncated = harness.bridge.renderCommandResults(
            document,
            "commandResults",
            DigestBudgets.RESULT_LIMIT,
            DigestBudgets.RESULT_CEILING,
        )
        document.endObject()
        val rendered = document.toString()

        assertTrue(
            !truncated,
            "the recent_events answer was dropped from the digest, which is the whole defect: $rendered",
        )
        assertTrue(
            rendered.contains("champion_died"),
            "an answer that carries no event is not an answer: $rendered",
        )
        // Newest first, and the ring says so: the last message recorded is index 199.
        assertTrue(
            rendered.contains("champion_died:blue_team_carry:199:killed_by_tower_2_dive"),
            "offset 0 must be the newest event: $rendered",
        )
        assertTrue(rendered.contains("\"hasMore\":true"), rendered)
        assertTrue(rendered.contains("\"nextOffset\":"), rendered)
    }

    /** Following `nextOffset` walks the ring backwards in time, seeing each entry once. */
    @Test
    fun `paging recent_events walks the whole ring exactly once, newest first`() {
        val harness = ToolsetHarness()
        repeat(30) { harness.bridge.event("wave_spawned:$it") }

        val seen = ArrayList<Int>()
        var offset = 0
        var pages = 0
        while (true) {
            val json = (
                harness.eventTools.recentEvents(
                    limit = EventsToolset.DEFAULT_LIMIT,
                    offset = offset,
                    contains = null,
                ) as dev.wildware.udea.agent.AgentResult.Ok
                ).json
            pages++
            Regex("wave_spawned:(\\d+)").findAll(json).forEach { seen += it.groupValues[1].toInt() }
            val next = Regex("\"nextOffset\":(\\d+)").find(json)?.groupValues?.get(1)?.toInt() ?: break
            check(next > offset) { "nextOffset did not advance past $offset: $json" }
            offset = next
            check(pages < 100) { "pagination is not terminating" }
        }

        assertEquals((0 until 30).reversed().toList(), seen)
    }

    /** `contains` narrows what is paged, so `total` and `nextOffset` are about the matches. */
    @Test
    fun `contains narrows the pages rather than the page`() {
        val harness = ToolsetHarness()
        repeat(20) { harness.bridge.event("wave_spawned:$it") }
        repeat(5) { harness.bridge.event("tower_destroyed:$it") }

        val json = (
            harness.eventTools.recentEvents(limit = 100, offset = 0, contains = "tower_destroyed")
                as dev.wildware.udea.agent.AgentResult.Ok
            ).json

        assertTrue(json.contains("\"total\":5"), "total must count the matches, not the ring: $json")
        assertTrue(!json.contains("wave_spawned"), "a non-matching event reached the page: $json")
    }

    /**
     * An open JSON object of exactly [target] characters, self-calibrating so it stays exact if
     * [Json] ever changes its spacing.
     */
    private fun paddedTo(target: Int): Json {
        val probe = Json()
        probe.beginObject()
        probe.put("filler", "")
        val json = Json()
        json.beginObject()
        json.put("filler", "x".repeat(target - probe.length))
        check(json.length == target) { "padding produced ${json.length} characters, not $target" }
        return json
    }

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
