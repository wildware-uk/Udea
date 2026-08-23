package dev.wildware.udea.agent.host

import dev.wildware.udea.agent.state.DigestBudgets
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A 4KB event message reaches a caller, over HTTP, through the shipped path and nothing else.
 *
 * ## What was undeliverable, and why paging did not settle it
 *
 * A tool answer reaches an agent only through the Tier-0 digest's `commandResults`, and
 * `AgentBridge.renderCommandResults` **drops** a result that does not fit rather than shortening
 * it. `ResultPage` cut the list into pages small enough to land, which fixed `list_snapshots`
 * and left `recent_events` broken for a different reason: a page can always hold fewer rows, and
 * it can never hold a smaller row. One event whose message ran past about seventy characters was
 * an answer no `limit` could deliver. The previous attempt lowered the default limit from forty
 * to eight, which is a fix to the count and not to the shape.
 *
 * ## What this test does not do
 *
 * It does not call the toolset. Every step is what an agent does: `GET /command?cmd=...`, poll
 * `GET /state` for `completedCommandId`, read the answer out of the digest, and fetch the handle
 * with `GET /artifact?id=...` - the same endpoint, the same store and the same lifetime
 * `render.screenshot` uses for a PNG. The final assertion compares 4096 characters byte for byte.
 */
class EventSpillEndToEndTest {

    @Test
    fun `the whole message is fetchable through GET artifact`() {
        val artifacts = LiveInstance.scratchArtifacts()
        val instance = LiveInstance(artifacts)
        try {
            instance.bridge.events.record(MESSAGE, tick = 11L)

            val answer = instance.callAndRead("events.recent_events", "limit" to "1")

            assertTrue(answer.contains("\"truncated\":true"), answer)
            assertTrue(answer.contains("\"messageChars\":${MESSAGE.length}"), answer)
            val handle = HANDLE.find(answer)?.groupValues?.get(1)
            assertNotNull(
                handle,
                "no messageRef in the answer, so the 4KB is gone rather than fetchable: $answer",
            )

            val fetched = instance.bytesOf("/artifact?id=$handle")
            assertEquals(
                MESSAGE,
                String(fetched, StandardCharsets.UTF_8),
                "GET /artifact?id=$handle did not return the message verbatim",
            )
            assertEquals(MESSAGE.length, fetched.size, "the whole 4096 characters, not a prefix")
        } finally {
            instance.close()
            artifacts.clear()
        }
    }

    @Test
    fun `the answer that carries the handle is itself inside the guarantee`() {
        val artifacts = LiveInstance.scratchArtifacts()
        val instance = LiveInstance(artifacts)
        try {
            instance.bridge.events.record(MESSAGE, tick = 11L)

            val answer = instance.callAndRead("events.recent_events", "limit" to "1")

            // The whole point: a handle that does not fit is the same failure with extra steps.
            assertTrue(
                answer.length <= DigestBudgets.RESULT_MIN_BYTES,
                "the answer is ${answer.length} characters against a " +
                    "${DigestBudgets.RESULT_MIN_BYTES}-character guarantee: $answer",
            )
        } finally {
            instance.close()
            artifacts.clear()
        }
    }

    @Test
    fun `with no artifact store the caller is still told the size it is missing`() {
        // Same event, no store. The message cannot arrive whole - nowhere to put it - and the
        // honest answer is a marked cut, not a shorter message pretending to be the whole one.
        val instance = LiveInstance()
        try {
            instance.bridge.events.record(MESSAGE, tick = 11L)

            val answer = instance.callAndRead("events.recent_events", "limit" to "1")

            assertTrue(answer.contains("\"truncated\":true"), answer)
            assertTrue(answer.contains("\"messageChars\":${MESSAGE.length}"), answer)
            assertTrue(!answer.contains("messageRef"), "there is no store to hand back: $answer")
        } finally {
            instance.close()
        }
    }

    /**
     * Submits [tool] and returns its `result` out of the digest, exactly as the bridge does.
     *
     * Polls `/state` for `completedCommandId >= commandId` and then reads the entry for that id
     * out of `commandResults`. A `commandResultsTruncated: true` with no entry for this id is the
     * defect under test, and it fails here with that word in the message.
     */
    private fun LiveInstance.callAndRead(tool: String, vararg args: Pair<String, String>): String {
        val query = args.joinToString("") { (k, v) -> "&$k=$v" }
        val accepted = get("/command?cmd=$tool$query").body()
        val id = assertNotNull(COMMAND_ID.find(accepted), "not accepted: $accepted").groupValues[1]

        var digest = ""
        val arrived = await(WAIT_MILLIS) {
            digest = get("/state").body()
            digest.contains("\"id\":$id")
        }
        assertTrue(
            arrived,
            "the answer to command $id never reached /state. " +
                "commandResultsTruncated=${digest.contains("\"commandResultsTruncated\":true")}. " +
                "Digest: $digest",
        )
        val entry = assertNotNull(
            RESULT.find(digest.substringAfter("\"id\":$id")),
            "no result payload for command $id in $digest",
        )
        return entry.groupValues[1]
    }

    private fun LiveInstance.bytesOf(path: String): ByteArray =
        get(path).body().toByteArray(StandardCharsets.UTF_8)

    private companion object {
        const val WAIT_MILLIS: Long = 5_000L

        /** 4096 characters: a stack trace, a serialised command, a validation report. */
        val MESSAGE: String = "validation failed: ".let { it + "x".repeat(4096 - it.length) }

        val COMMAND_ID = Regex("\"commandId\":(\\d+)")

        /** `"result":{...}` for the entry that follows the id already matched. */
        val RESULT = Regex("\"result\":(\\{.*?\\})[,}]")

        val HANDLE = Regex("\"messageRef\":\"(cap_[0-9]+)\"")
    }
}
