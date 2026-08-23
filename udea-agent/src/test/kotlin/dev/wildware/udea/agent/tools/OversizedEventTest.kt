package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.Json
import dev.wildware.udea.agent.state.DigestBudgets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A single oversized event reaches the caller, which is the case pagination could not fix.
 *
 * ## What was still broken after the page landed
 *
 * `ResultPage` made the *list* deliverable and left the *row*. `AgentBridge.renderCommandResults`
 * drops any result past the digest's guarantee rather than shortening it, `ResultPage` commits
 * the first row of a page whatever its size, and a row is `{"tick":N,"message":"..."}` - so an
 * event message longer than about seventy characters produced an answer the digest dropped, at
 * **every** value of `limit`. Reducing the default limit from forty to eight changed how many
 * short events fit and did nothing at all to this: a page of one oversized row is still a page
 * of one oversized row. The tool was undeliverable for anything a game logged in more than a
 * line, which is most of what is worth logging.
 *
 * ## What the tests below actually assert
 *
 * Not "the tool returns something". Three separate facts:
 *
 * 1. the rendered answer fits [DigestBudgets.RESULT_MIN_BYTES] *including* the envelope the
 *    digest charges around it - the number `ResultPage.ENVELOPE_BYTES` exists for;
 * 2. the answer survives a real round trip through the digest and comes back out of `/state`;
 * 3. the full 4KB is recoverable - handed to the spill verbatim, so the handle in the answer
 *    names the whole message and not a copy of the truncated one.
 *
 * [oversizedIsGenuinelyOversized] states the premise as a test, so that a future change to the
 * budgets cannot leave these passing for the wrong reason.
 */
class OversizedEventTest {

    /** A recording [TextSpill], standing where `AgentArtifacts.textSpill()` stands in a host. */
    private class Recorder : TextSpill {
        val filed: MutableList<String> = ArrayList()

        override fun spill(text: String): String? {
            filed.add(text)
            return "cap_%04d".format(filed.size)
        }
    }

    @Test
    fun oversizedIsGenuinelyOversized() {
        // The premise: the shape this test is about cannot fit, so a pass below is a pass about
        // the fix rather than about a message that was small enough all along.
        val naive = Json.render {
            put("tick", 7L)
            put("message", MESSAGE)
        }
        assertTrue(
            naive.length + ResultPage.ENVELOPE_BYTES > DigestBudgets.RESULT_MIN_BYTES,
            "a ${MESSAGE.length}-character message rendered whole is ${naive.length} characters, " +
                "which would have to be under ${DigestBudgets.RESULT_MIN_BYTES} for this test " +
                "to be about nothing",
        )
    }

    @Test
    fun `a 4KB event fits the guarantee with no spill wired, and says what it cut`() {
        val harness = ToolsetHarness()
        harness.bridge.events.record(MESSAGE, tick = 3L)

        val json = harness.recentEvents()

        assertTrue(
            json.length + ResultPage.ENVELOPE_BYTES <= DigestBudgets.RESULT_MIN_BYTES,
            "the page is ${json.length} characters plus a ${ResultPage.ENVELOPE_BYTES}-character " +
                "envelope, past the ${DigestBudgets.RESULT_MIN_BYTES} a command result is " +
                "guaranteed - the digest would drop it: $json",
        )
        assertTrue(json.contains("\"returned\":1"), json)
        assertTrue(json.contains("\"truncated\":true"), json)
        assertTrue(
            json.contains("\"messageChars\":${MESSAGE.length}"),
            "the real length has to be published or a caller cannot tell how much it is " +
                "missing: $json",
        )
        assertTrue(
            !json.contains("messageRef"),
            "no store was wired, so there is nothing to hand back a handle to: $json",
        )
    }

    @Test
    fun `the answer survives the digest it is delivered through`() {
        val harness = ToolsetHarness()
        harness.bridge.events.record(MESSAGE, tick = 3L)
        harness.recentEvents()

        // The claim is end to end and not about this writer's arithmetic: the result goes into
        // the ring, the ring is rendered into the Tier-0 document, and the document is what an
        // agent reads. A dropped result shows up here as `commandResultsTruncated`.
        val digest = harness.sim.digest()
        assertTrue(
            digest.contains("\"messageChars\":${MESSAGE.length}"),
            "the answer did not reach /state; this is exactly the drop the fix is for: $digest",
        )
        assertTrue(
            !digest.contains("\"commandResultsTruncated\":true"),
            "the digest reports it dropped a result: $digest",
        )
    }

    @Test
    fun `with a spill wired the whole 4KB is filed and the answer names it`() {
        val recorder = Recorder()
        val harness = ToolsetHarness(spill = recorder)
        harness.bridge.events.record(MESSAGE, tick = 3L)

        val json = harness.recentEvents()

        assertTrue(json.contains("\"messageRef\":\"cap_0001\""), json)
        assertEquals(1, recorder.filed.size, "one oversized entry should file exactly one artifact")
        assertEquals(
            MESSAGE,
            recorder.filed.single(),
            "the spill must receive the message verbatim; filing the truncated text would make " +
                "the handle a second copy of the answer the caller already has",
        )
        assertTrue(
            json.length + ResultPage.ENVELOPE_BYTES <= DigestBudgets.RESULT_MIN_BYTES,
            "the handle has to fit the same guarantee the truncated answer does: $json",
        )
    }

    @Test
    fun `reading twice does not file the message twice`() {
        val recorder = Recorder()
        val harness = ToolsetHarness(spill = recorder)
        harness.bridge.events.record(MESSAGE, tick = 3L)

        repeat(4) { harness.recentEvents() }

        assertEquals(
            1,
            recorder.filed.size,
            "a caller polling recent_events would otherwise write one artifact per poll, and " +
                "the store evicts oldest-accessed-first - so the loop would delete the " +
                "screenshots somebody else was comparing",
        )
    }

    @Test
    fun `a short message is still rendered whole and unmarked`() {
        val harness = ToolsetHarness(spill = Recorder())
        harness.bridge.events.record("spawn:grunt", tick = 3L)

        val json = harness.recentEvents()

        assertTrue(json.contains("\"message\":\"spawn:grunt\""), json)
        assertTrue(!json.contains("truncated"), "nothing was cut, so nothing may claim it was: $json")
    }

    private fun ToolsetHarness.recentEvents(): String {
        val result = call("events.recent_events", "limit" to "1")
        return assertNotNull(assertIs<AgentResult.Ok>(result, "recent_events failed: $result")).json
    }

    private companion object {
        /**
         * 4096 characters, which is what the brief names and is a realistic size for the thing
         * that actually breaks this: a stack trace, a serialised command, a validation report.
         */
        val MESSAGE: String = "validation failed: ".let { head ->
            head + "x".repeat(4096 - head.length)
        }
    }
}
