package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.Json
import dev.wildware.udea.agent.state.DigestBudgets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A list answer reaches the agent, whatever the list is.
 *
 * ## The defect this pins
 *
 * `AgentBridge.renderCommandResults` counts backwards from the newest result and stops at the
 * first that does not fit [DigestBudgets.RESULT_CEILING]. So a result larger than the room left
 * in the state document is *dropped*, not shortened: the caller polling for its own answer sees
 * `commandResultsTruncated: true` and nothing else, and calling again does not help.
 * `time.list_snapshots` over a full ring was in exactly that position - a tool that could not
 * answer at all - and the settlement was pagination rather than a handle into an artifact
 * store, for the reasons in [ResultPage]'s KDoc.
 *
 * The claim that matters is therefore not "the tool returns a page". It is "the page fits", and
 * the way to check it is to render one into a real digest and read it back out.
 */
class ResultPageTest {

    @Test
    fun `a page fits the bytes a command result is guaranteed, whatever the list length`() {
        // Ten times the ceiling's worth of entries. An unpaged answer over this is several
        // kilobytes and cannot reach /state at any size of document.
        val page = ResultPage.render("rows", offset = 0, limit = Int.MAX_VALUE, total = 4_000) { json, index ->
            json.obj {
                put("tick", index.toLong())
                put("kind", "Full")
                put("sizeBytes", 98_765)
            }
        }

        val json = (page as AgentResult.Ok).json
        assertTrue(
            json.length <= DigestBudgets.RESULT_MIN_BYTES,
            "a page is ${json.length} chars against a ${DigestBudgets.RESULT_MIN_BYTES}-byte " +
                "guarantee, so it can still be dropped: $json",
        )
        assertTrue(json.contains("\"hasMore\":true"), json)
        assertTrue(json.contains("\"nextOffset\":"), json)
    }

    /**
     * The end-to-end form of the same claim, through the machinery that does the dropping.
     *
     * A page rendered into a real [AgentBridge] result ring and then through the digest's own
     * `renderCommandResults` comes out the far side *present*, and the truncation flag is
     * absent. Before paging, this is the assertion that failed.
     */
    @Test
    fun `a paged answer survives the digest's command-result ceiling`() {
        val bridge = AgentBridge()
        val page = ResultPage.render("rows", offset = 0, limit = Int.MAX_VALUE, total = 4_000) { json, index ->
            json.obj {
                put("tick", index.toLong())
                put("kind", "Full")
                put("sizeBytes", 98_765)
            }
        }
        bridge.complete(1L, page)

        val document = Json()
        document.beginObject()
        val truncated = bridge.renderCommandResults(
            document,
            "commandResults",
            DigestBudgets.RESULT_LIMIT,
            DigestBudgets.RESULT_CEILING,
        )
        document.endObject()

        assertTrue(!truncated, "the paged answer was still dropped: $document")
        assertTrue(document.toString().contains("\"nextOffset\":"), document.toString())
    }

    /**
     * An unpaged answer of the same data is dropped, which is what makes the test above mean
     * something.
     *
     * Without this the paged result could be passing because the data happened to be small. It
     * renders the *whole* list the old way and asserts the exact failure the settlement is
     * about: nothing in `commandResults`, and `commandResultsTruncated` as the only signal.
     */
    @Test
    fun `the unpaged form of the same answer is dropped, which is the defect`() {
        val bridge = AgentBridge()
        bridge.complete(
            1L,
            AgentResult.ok {
                arr("rows") {
                    repeat(4_000) { index ->
                        element {
                            put("tick", index.toLong())
                            put("kind", "Full")
                            put("sizeBytes", 98_765)
                        }
                    }
                }
            },
        )

        val document = Json()
        document.beginObject()
        val truncated = bridge.renderCommandResults(
            document,
            "commandResults",
            DigestBudgets.RESULT_LIMIT,
            DigestBudgets.RESULT_CEILING,
        )
        document.endObject()

        assertTrue(truncated, "an oversized answer must report itself truncated")
        assertTrue(
            document.toString().contains("\"commandResults\":[]"),
            "the whole answer should have been dropped: $document",
        )
    }

    /** Following `nextOffset` enumerates everything, with no entry seen twice and none skipped. */
    @Test
    fun `following nextOffset walks the whole list exactly once`() {
        val total = 500
        val seen = ArrayList<Int>(total)
        var offset = 0
        var pages = 0

        while (true) {
            val json = (
                ResultPage.render("rows", offset, limit = Int.MAX_VALUE, total = total) { out, index ->
                    out.obj { put("tick", index.toLong()) }
                } as AgentResult.Ok
                ).json
            pages++
            OFFSET_TICK.findAll(json).forEach { seen += it.groupValues[1].toInt() }
            val next = NEXT_OFFSET.find(json)?.groupValues?.get(1)?.toInt() ?: break
            check(next > offset) { "nextOffset did not advance past $offset: $json" }
            offset = next
            check(pages < total) { "pagination is not terminating" }
        }

        assertEquals((0 until total).toList(), seen)
    }

    /**
     * A single entry too large for the guarantee is committed and *named*, not dropped.
     *
     * The alternative is a page of nothing, which is a tool that cannot answer - the state this
     * whole file exists to leave behind. `entryTooLarge` is what lets an agent tell "there is
     * one enormous row here" from "the page ended normally" and ask a narrower question.
     */
    @Test
    fun `an entry larger than a whole page is returned and reported as oversized`() {
        val page = ResultPage.render("rows", offset = 0, limit = 5, total = 3) { json, index ->
            json.obj { put("blob", "x".repeat(ResultPage.MAX_PAGE_BYTES + 10) + index) }
        }

        val json = (page as AgentResult.Ok).json
        assertTrue(json.contains("\"returned\":1"), json)
        assertTrue(json.contains("\"entryTooLarge\":true"), json)
        assertTrue(json.contains("\"hasMore\":true"), json)
    }

    @Test
    fun `an empty list is still a page, and says there is no more`() {
        val json = (
            ResultPage.render("rows", offset = 0, limit = 10, total = 0) { out, _ -> out.obj { } }
                as AgentResult.Ok
            ).json

        assertEquals("""{"total":0,"offset":0,"rows":[],"returned":0,"hasMore":false}""", json)
    }

    private companion object {
        val NEXT_OFFSET = Regex("\"nextOffset\":(\\d+)")
        val OFFSET_TICK = Regex("\"tick\":(\\d+)")
    }
}
