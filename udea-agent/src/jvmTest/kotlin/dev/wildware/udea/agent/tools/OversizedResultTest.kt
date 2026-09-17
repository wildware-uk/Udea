package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.Json
import dev.wildware.udea.agent.state.DigestBudgets
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An answer too large for `/state` reaches the caller as a handle, not as silence.
 *
 * ## The defect
 *
 * `CommandResultRing.renderInto` spends a byte budget newest-first and drops whole entries.
 * An entry above [AgentBridge.MAX_DELIVERABLE_RESULT_CHARS] fits no document at all, and it is not
 * evicted until [AgentBridge.DEFAULT_RESULT_CAPACITY] newer commands arrive - so a caller polling
 * for its id reads `completedCommandId: 4` beside `"commandResults":[]` and
 * `"commandResultsTruncated":true`, indefinitely, with no way to learn even *which* answer it lost.
 *
 * Found by driving the Phase 2 exit demo over HTTP: `assets.write` rejecting one typo'd reference
 * answers with 3490 characters against a 1280-character ceiling, so **every** asset diagnostic an
 * agent asked for was unreachable over HTTP. `Phase2ExitTest` is the end-to-end regression; this
 * is the unit that pins the mechanism.
 */
class OversizedResultTest {

    @Test
    fun `an answer above the ceiling is replaced by a handle and its real size`() {
        val stored = mutableListOf<String>()
        val bridge = AgentBridge(
            resultSpill = { text -> stored += text; "cap_0001" },
        )
        val answer = oversized()
        bridge.complete(1L, answer)

        val rendered = render(bridge)
        assertTrue("\"resultTooLarge\":true" in rendered, rendered)
        assertTrue("\"resultChars\":${answer.json.length}" in rendered, rendered)
        assertTrue("\"resultRef\":\"cap_0001\"" in rendered, rendered)
        // The body is out of the document entirely, which is the point: it never fitted.
        assertTrue("\"filler\"" !in rendered, rendered)
        // And it is intact wherever it went, so the handle is worth following.
        assertEquals(listOf(answer.json), stored)
    }

    @Test
    fun `an answer inside the ceiling is untouched and no spill happens`() {
        var spills = 0
        val bridge = AgentBridge(resultSpill = { spills++; "cap_0001" })
        bridge.complete(1L, AgentResult.ok { put("small", true) })

        val rendered = render(bridge)
        assertTrue("\"small\":true" in rendered, rendered)
        assertTrue("resultTooLarge" !in rendered, rendered)
        assertEquals(0, spills, "an answer that fits must not cost a file write")
    }

    /**
     * A host with no artifact store is told the truth rather than handed a lie.
     *
     * `TextSpill.NONE` is what a unit test, a `SimHarness` and a headless process with no HTTP
     * surface wire. The answer is genuinely unreachable there, and a null `resultRef` says so - a
     * caller that saw no key at all could not distinguish it from a host that had not spilled.
     */
    @Test
    fun `a host with no store still names the answer it could not deliver`() {
        val bridge = AgentBridge()
        bridge.complete(7L, oversized())

        val rendered = render(bridge)
        assertTrue("\"id\":7" in rendered, rendered)
        assertTrue("\"resultRef\":null" in rendered, rendered)
    }

    private fun oversized(): AgentResult.Ok = AgentResult.ok {
        put("filler", "x".repeat(AgentBridge.MAX_DELIVERABLE_RESULT_CHARS + 1))
    }

    private fun render(bridge: AgentBridge): String {
        val json = Json()
        json.beginObject()
        bridge.renderCommandResults(
            json,
            "commandResults",
            DigestBudgets.RESULT_LIMIT,
            DigestBudgets.RESULT_CEILING,
        )
        json.endObject()
        return json.toString()
    }
}
