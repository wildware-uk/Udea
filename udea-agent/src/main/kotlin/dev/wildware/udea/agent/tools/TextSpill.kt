package dev.wildware.udea.agent.tools

/**
 * Somewhere to put text that will not fit the answer a tool is guaranteed.
 *
 * ## Why this interface exists rather than a direct call to the artifact store
 *
 * `AgentArtifacts` lives in `udea-agent-host`, which depends on this module and not the other
 * way round, so a tool here cannot name it. [ResultPage] made that the argument for pagination
 * over handles and it was right for a *list*: the next page is the same tool with a different
 * `offset`, and no store has to exist.
 *
 * It is not right for one **oversized entry**. A page can be cut smaller until it fits; a single
 * 4KB event message cannot, and no amount of paging over entries makes it deliverable, which is
 * the hole this closes. So the store is inverted instead of imported: a host that has one hands
 * one in, `render.screenshot` already answers with exactly this kind of handle for exactly this
 * reason, and the caller fetches it with `GET /artifact?id=cap_0007`.
 *
 * ## A host that has no store is not a broken host
 *
 * `SimHarness`, a unit test and a headless process with no HTTP surface all have nowhere to put
 * bytes, and each of them still has to be able to read its events. [NONE] is what they wire, and
 * the caller is told the truth in-band: the message is cut to what fits and marked `truncated`
 * with its real length beside it, so nothing silently reports a short message where a long one
 * was recorded.
 */
public fun interface TextSpill {

    /**
     * Stores [text] and returns the handle a caller fetches it by, or `null` when it could not.
     *
     * `null` rather than a throw for the same reason `AgentArtifacts.put` returns one: a message
     * that cannot be filed is a degraded answer, not a reason to fail the tick the tool call is
     * inside. The caller falls back to truncation and says so.
     *
     * Called on the simulation thread, once per oversized entry per tool call - never per tick.
     */
    public fun spill(text: String): String?

    public companion object {

        /** Stores nothing and hands back `null`. What a host with no artifact store wires. */
        public val NONE: TextSpill = TextSpill { null }
    }
}
