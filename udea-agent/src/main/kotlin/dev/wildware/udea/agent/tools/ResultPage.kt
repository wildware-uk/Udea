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
 * ## What "every page lands" now actually covers
 *
 * It did not used to cover the page. [MAX_PAGE_BYTES] was subtracted from the guarantee and
 * then spent only on the rendered **entries**; the `prelude` this signature invites, the two
 * page numbers, the array's own key and brackets and the three closing fields were all emitted
 * outside the budget, and so was the envelope the digest charges around the whole result. A
 * `time.list_snapshots` page carrying `count` and `totalBytes` therefore rendered a document
 * past the guarantee while reporting itself inside it.
 *
 * Every one of those is inside the budget now, because the budget is checked against the
 * document being built rather than against a running total of entry lengths. The price is page
 * size - see [MAX_PAGE_BYTES] - and it is the right price: a page of one row that arrives beats
 * a page of three that the digest drops, which is this file's own argument applied to itself.
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
     * The digest's own arithmetic rather than a round number with slack in it, because the
     * slack was hiding a hole. `CommandResultRing` charges an entry
     * `result.json.length + ENTRY_OVERHEAD` (40) against a budget already reduced by
     * `ARRAY_OVERHEAD` (4): 44 characters spent around this document before a byte of it is
     * counted. The widest real envelope, `,{"id":9007199254740991,"ok":true,"result":}`, is 44
     * characters too, so the two agree. 48 is that with one round of slack.
     *
     * The previous value was 128 and it looked conservative. It was not: it was subtracted from
     * a budget that then only had to cover the entries, so the apparent headroom paid for
     * nothing while the prelude and the page fields were spent outside the budget entirely.
     */
    public const val ENVELOPE_BYTES: Int = 48

    /**
     * What one **whole rendered page** may total - prelude, array key, entries, page fields.
     *
     * From [DigestBudgets.RESULT_MIN_BYTES], the guarantee, and never from
     * [DigestBudgets.RESULT_CEILING], the leftovers. See the class KDoc.
     *
     * 208 characters, and counting honestly costs page size: a `time.list_snapshots` page holds
     * one or two snapshots where the old accounting claimed three. That is the number the
     * guarantee actually affords. Raising it means raising [DigestBudgets.RESULT_MIN_BYTES],
     * which spends bytes `ui.elements` currently has - a digest-wide trade, made in
     * `DigestBudgets` where a reviewer sees it, and never by loosening this constant alone.
     */
    public const val MAX_PAGE_BYTES: Int = DigestBudgets.RESULT_MIN_BYTES - ENVELOPE_BYTES

    /**
     * The fixed part of the closing text, before the two variable numbers in it.
     *
     * `]` + `,"returned":` + `,"hasMore":true` + `,"nextOffset":` + `}` is 43 characters;
     * [tailBytes] adds the two numbers. [OVERRUN_FLAG_BYTES] is deliberately *not* in here: it
     * is reserved only at the one decision that can write a flag, because reserving 23
     * characters on every row costs a page roughly one entry - and at
     * [DigestBudgets.RESULT_MIN_BYTES] a page only has one or two to give.
     */
    private const val TAIL_FIXED_BYTES: Int = 43

    /**
     * `,"preludeTooLarge":true` - the longer of the two overrun flags, and at most one is ever
     * written.
     *
     * Reserved in exactly two places, and nowhere else. Before the loop, because a prelude that
     * leaves no room for the closing text plus its own complaint is a prelude that has spent the
     * page. And never for a row that fits: a row admitted inside the budget sets no flag, so
     * charging it for one is charging it for text that will not be written.
     */
    private const val OVERRUN_FLAG_BYTES: Int = 23

    /** One entry of a paged answer, rendered into the [Json] it will be committed to. */
    public fun interface Entry {
        /** Writes the entry at [index] as one JSON value. */
        public fun render(json: Json, index: Int)
    }

    /**
     * Renders `[offset, offset+limit)` of [total] entries, stopping early at [MAX_PAGE_BYTES].
     *
     * Each entry is rendered into a scratch buffer and measured before it is committed, and it
     * is measured **against the document being built** - so the prelude, the array key and the
     * closing fields are inside the budget they used to sit outside. Allocating per entry, and
     * that is correct here: this runs once per tool call on the simulation thread, never per
     * tick, and the alternative is a size estimate that is wrong exactly when it matters.
     *
     * **The first entry is always committed**, even when it alone exceeds the budget. A page
     * that returned nothing would be a tool that cannot answer, which is the state this file
     * exists to leave behind; the overrun is reported as `entryTooLarge: true` so the caller
     * knows this one answer may still be dropped from the digest and why. When the *prelude* is
     * what spent the budget, `preludeTooLarge: true` is written instead - a caller can shorten a
     * prelude, and blaming the row would send it looking in the wrong place.
     *
     * @param name the array's member name, e.g. `snapshots`.
     * @param offset how many entries to skip. Clamped into `0..total`.
     * @param limit the caller's own cap on the page, applied before the byte budget.
     * @param prelude members written before the array - whole-collection facts like a total
     *   size in bytes, which is a different number from anything on this page. Inside the byte
     *   budget: a prelude large enough to crowd out every row says so, rather than silently
     *   pushing the document past what the digest guarantees.
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
        val tail = tailBytes(total)
        val json = Json()
        json.beginObject()
        json.prelude()
        json.put("total", total)
        json.put("offset", from)
        json.key(name)
        json.beginArray()
        // Measured once the array is open, so it covers the prelude, both page numbers and the
        // array's own key: every character this writer emits before the first row. The flag's
        // own bytes are in the sum because a page that cannot afford to say what went wrong is
        // not in a position to say anything.
        val preludeTooLarge = json.length + tail + OVERRUN_FLAG_BYTES > MAX_PAGE_BYTES

        val scratch = Json()
        var written = 0
        var entryTooLarge = false
        var index = from
        while (index < total && written < limit) {
            scratch.reset()
            entry.render(scratch, index)
            val text = scratch.toString()
            // `+ 1` for the comma this entry will need, and `tail` for the closing text that
            // must still fit after it. Reserving the tail before committing a row is what stops
            // a page spending its last byte on data and then emitting a truncated document -
            // which fails at the agent's parser rather than here, and is worse than a drop.
            val wouldReach = json.length + text.length + 1 + tail
            if (written > 0 && wouldReach > MAX_PAGE_BYTES) break
            if (written == 0 && wouldReach > MAX_PAGE_BYTES) entryTooLarge = true
            json.raw(text)
            written++
            index++
        }
        json.endArray()
        json.put("returned", written)
        val next = from + written
        // Both spellings, because `hasMore` is what an agent branches on and `nextOffset`
        // is what it puts in the next call. Publishing only one makes every caller derive
        // the other, and a caller deriving `next = offset + returned` gets it wrong the
        // moment a byte budget - rather than the limit - ended the page.
        json.put("hasMore", next < total)
        if (next < total) json.put("nextOffset", next)
        if (preludeTooLarge) {
            json.put("preludeTooLarge", true)
        } else if (entryTooLarge) {
            json.put("entryTooLarge", true)
        }
        json.endObject()
        return AgentResult.Ok(json.toString())
    }

    /**
     * Characters the closing text may still need once [total] entries are on the table.
     *
     * `returned` and `nextOffset` are both bounded by [total], so its decimal width bounds both.
     */
    private fun tailBytes(total: Int): Int = TAIL_FIXED_BYTES + 2 * digits(total)

    /** Digits in [value]'s decimal form, for a value that is never negative here. */

    private fun digits(value: Int): Int {
        var remaining = value / 10
        var width = 1
        while (remaining > 0) {
            width++
            remaining /= 10
        }
        return width
    }
}
