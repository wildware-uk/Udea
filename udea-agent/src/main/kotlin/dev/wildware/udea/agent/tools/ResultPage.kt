package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.Json
import dev.wildware.udea.agent.state.DigestBudgets

/**
 * A list answer, cut into pages small enough that every page actually reaches the agent.
 *
 * ## The hole this closes
 *
 * A tool's answer is delivered through the Tier-0 digest's `commandResults` array, and that
 * array has a byte ceiling ([DigestBudgets.RESULT_CEILING]) which
 * `AgentBridge.renderCommandResults` enforces by counting backwards from the newest entry and
 * stopping at the first that does not fit. So an answer larger than the room left in the
 * document is not shortened - it is **dropped**, and the caller polling for its own result
 * reads `commandResultsTruncated: true` and nothing else. It never sees the answer, it cannot
 * ask for less, and the next call has the same problem. `time.list_snapshots` over a full
 * snapshot ring was exactly that: a tool that could not answer at all.
 *
 * ## Pagination, and why not a handle into an artifact store
 *
 * The two ways out are paginating and spilling the answer to a store that hands back a handle.
 * This is pagination, for three reasons:
 *
 * 1. **The store is in the wrong module.** `AgentArtifacts` lives in `udea-agent-host`, which
 *    depends on this module and not the other way round. A handle produced here would be a
 *    handle to something this module cannot write, so the tool would answer with a promise
 *    the engine could not keep in a headless test, in `SimHarness`, or in any host that binds
 *    no HTTP surface at all.
 * 2. **A handle needs a second round trip and a lifetime.** An agent that gets `{"handle":...}`
 *    has to fetch it, and something has to decide when the artifact is evictable. Pagination
 *    needs neither: the next page is the same tool with a different `offset`.
 * 3. **The page is measured, not estimated.** Each entry is rendered before it is committed,
 *    so [MAX_PAGE_BYTES] is a fact about the emitted text rather than a guess at its size.
 *
 * What pagination does not buy is a large page. [MAX_PAGE_BYTES] is derived from
 * [DigestBudgets.RESULT_MIN_BYTES] - the bytes `commandResults` is *guaranteed* whatever the
 * sections before it spent - and not from [DigestBudgets.RESULT_CEILING], which is only what
 * is left over on a quiet frame. A page sized to the ceiling would usually arrive and
 * occasionally vanish, which is the failure this exists to remove rather than to make rarer.
 * So pages are small and every one of them lands.
 *
 * ## What a page publishes
 *
 * `offset`, `returned`, `total` and either `nextOffset` or `hasMore: false`. All four, because
 * an agent handed only a truncated array cannot tell "that is everything" from "there is more
 * and you were not told" - and being unable to tell those apart is the whole defect.
 */
public object ResultPage {

    /**
     * The envelope one result costs inside `commandResults`, before its payload.
     *
     * `,{"id":9007199254740991,"ok":true,"result":}` is 43 characters at the widest id, plus
     * the enclosing object's own `{}` and the four page fields this writer adds around the
     * array. Rounded up, because a bound that is tight is a bound that stops holding the first
     * time somebody adds a field.
     */
    public const val ENVELOPE_BYTES: Int = 128

    /**
     * What one page's rendered entries may total.
     *
     * From [DigestBudgets.RESULT_MIN_BYTES], the guarantee, and never from
     * [DigestBudgets.RESULT_CEILING], the leftovers. See the class KDoc.
     */
    public const val MAX_PAGE_BYTES: Int = DigestBudgets.RESULT_MIN_BYTES - ENVELOPE_BYTES

    /** One entry of a paged answer, rendered into the [Json] it will be committed to. */
    public fun interface Entry {
        /** Writes the entry at [index] as one JSON value. */
        public fun render(json: Json, index: Int)
    }

    /**
     * Renders `[offset, offset+limit)` of [total] entries, stopping early at [MAX_PAGE_BYTES].
     *
     * Each entry is rendered into a scratch buffer and measured before it is committed, so an
     * over-large page is impossible rather than unlikely. Allocating per entry, and that is
     * correct here: this runs once per tool call on the simulation thread, never per tick, and
     * the alternative is a size estimate that is wrong exactly when it matters.
     *
     * **The first entry is always committed**, even when it alone exceeds the budget. A page
     * that returned nothing would be a tool that cannot answer, which is the state this file
     * exists to leave behind; the overrun is reported as `entryTooLarge: true` so the caller
     * knows this one answer may still be dropped from the digest and why.
     *
     * @param name the array's member name, e.g. `snapshots`.
     * @param offset how many entries to skip. Clamped into `0..total`.
     * @param limit the caller's own cap on the page, applied before the byte budget.
     * @param prelude members written before the array - whole-collection facts like a total
     *   size in bytes, which is a different number from anything on this page.
     * @param entry last so it is the trailing lambda, because it is what a reader of a call
     *   site is looking for.
     */
    public fun render(
        name: String,
        offset: Int,
        limit: Int,
        total: Int,
        prelude: Json.() -> Unit = {},
        entry: Entry,
    ): AgentResult {
        require(total >= 0) { "a page cannot be cut from $total entries" }
        require(limit >= 0) { "a page limit must not be negative, was $limit" }
        val from = offset.coerceIn(0, total)
        val scratch = Json()
        var written = 0
        var spent = 0
        var tooLarge = false

        return AgentResult.ok {
            prelude()
            put("total", total)
            put("offset", from)
            key(name)
            beginArray()
            var index = from
            while (index < total && written < limit) {
                scratch.reset()
                entry.render(scratch, index)
                val text = scratch.toString()
                // `+ 1` for the comma this entry will need. Checked before committing, and
                // never for the first entry: see the KDoc.
                val cost = text.length + 1
                if (written > 0 && spent + cost > MAX_PAGE_BYTES) break
                if (written == 0 && cost > MAX_PAGE_BYTES) tooLarge = true
                raw(text)
                spent += cost
                written++
                index++
            }
            endArray()
            put("returned", written)
            val next = from + written
            // Both spellings, because `hasMore` is what an agent branches on and `nextOffset`
            // is what it puts in the next call. Publishing only one makes every caller derive
            // the other, and a caller deriving `next = offset + returned` gets it wrong the
            // moment a byte budget - rather than the limit - ended the page.
            put("hasMore", next < total)
            if (next < total) put("nextOffset", next)
            if (tooLarge) {
                // Named rather than implied: this is the one case where the answer may still
                // not reach `/state`, and an agent that is told which entry is oversized can
                // ask a narrower question instead of retrying the same one.
                put("entryTooLarge", true)
            }
        }
    }
}
