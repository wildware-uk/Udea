package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentEventRing
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.annotations.AgentTool
import dev.wildware.udea.annotations.Arg
import dev.wildware.udea.core.SimClock

/**
 * The event ring as an assertion surface.
 *
 * ## Why `assert_event` is a tool and not a convention
 *
 * "Did the thing happen?" is the question every automated session actually asks, and the way it
 * used to be answered was string-matching a racy shared ring for a formatted line - which is
 * the defect [dev.wildware.udea.agent.AgentResult] exists to fix for *command answers*. The
 * same fix applies here: the match is made on the simulation side, inside one tick boundary,
 * against a **tick window** rather than against the ring's tail, and a miss is a typed answer
 * rather than an exception or an empty string.
 *
 * The window is what makes it an assertion at all. A busy game writes a hundred events a tick,
 * so "somewhere in the last 200 entries" is a different question with a different answer;
 * `since` and `within` are tick counts, like every other duration on this surface.
 *
 * ## Reading is non-destructive, and that is load-bearing
 *
 * The bridge polls `/state` while it waits for `completedCommandId`, so a read that consumed
 * the ring would delete the events an agent was waiting to see, on a schedule set by the
 * polling loop rather than by anything in the game. `recent_events` therefore only reads;
 * `clear_events` is the one that empties it, and it is a separate tool so that emptying the
 * ring is always something somebody asked for.
 */
public class EventsToolset(
    private val bridge: AgentBridge,
    private val clock: SimClock,
) {

    /**
     * The event ring, newest first, one page at a time.
     *
     * ## Why this is paged, when it was the tool least in need of a page
     *
     * It was the tool most in need of one and nobody had measured it. An unpaged read of a
     * populated ring rendered **3384 characters**, and a command answer reaches the agent only
     * through the digest's `commandResults` array, which `CommandResultRing.fitting` costs
     * against [dev.wildware.udea.agent.state.DigestBudgets.RESULT_CEILING] - 1280. So a
     * `recent_events` answer never fitted, was dropped rather than shortened on **every** poll,
     * and the caller waiting on its own command read `commandResultsTruncated: true` for as long
     * as it was willing to wait. The tool advertised in the manifest, dispatched, ran, wrote its
     * answer into the ring, and could not deliver a single event. See [ResultPage] for the
     * settlement and for why it is pagination rather than an artifact handle.
     *
     * ## Newest first, which is the opposite of the ring's own order
     *
     * `offset = 0` is the **newest** event and `nextOffset` walks backwards in time. Events are
     * held oldest-first and the digest renders them that way, but "what just happened" is the
     * question this tool is asked, and a caller that had to page to the end of the ring before
     * reading the answer it was waiting for would be back where the drop left it.
     *
     * `total` counts the entries matching [contains], not the ring, so following `nextOffset`
     * enumerates the matches and nothing else.
     */
    @AgentTool(
        name = "events.recent_events",
        description = "Read recent game events with the tick each happened on, newest first " +
            "and one page at a time, without consuming them. Use it to find out what the " +
            "game thinks just happened, including the audit line every agent mutation " +
            "writes; follow nextOffset backwards in time for the rest.",
    )
    public fun recentEvents(
        @Arg(
            description = "Most entries to return. A page also stops early when it runs out " +
                "of the bytes a command result is guaranteed in the state document.",
            required = false,
            default = "8",
        )
        limit: Int,
        @Arg(
            description = "How many entries to skip, counting back from the newest.",
            required = false,
            default = "0",
        )
        offset: Int,
        @Arg(description = "Return only entries whose message contains this text.", required = false)
        contains: String?,
    ): AgentResult {
        val matched = collect(bridge.events.capacity, contains, sinceTick = Long.MIN_VALUE)
        return ResultPage.render(
            name = "events",
            offset = offset,
            limit = limit.coerceAtLeast(0),
            total = matched.size,
            prelude = {
                put("tick", clock.tick.value)
                // `totalRecorded` minus what the ring holds is how much the agent has missed,
                // which is the only way to tell "nothing happened" from "the ring wrapped".
                put("totalRecorded", bridge.events.totalRecorded)
                // `held` used to sit here and is gone on purpose. A page is measured against
                // the bytes a command answer is *guaranteed* - see ResultPage - and at that size
                // a redundant prelude field costs a whole event: `total` already reports how
                // many entries this call matched, and with no `contains` filter that number is
                // `held`. A caller that wants the ring's own size when it *is* filtering asks
                // once without the filter.
            },
        ) { json, index ->
            // `collect` hands back oldest-first; index 0 must be the newest. See the KDoc.
            val entry = matched[matched.size - 1 - index]
            json.obj {
                put("tick", entry.tick)
                put("message", entry.message)
            }
        }
    }

    @AgentTool(
        name = "events.clear_events",
        description = "Empty the event ring so the next read shows only what happened " +
            "after this call. Reach for it before running a scenario you want to assert " +
            "against cleanly.",
    )
    public fun clearEvents(): AgentResult {
        val dropped = bridge.events.size
        bridge.events.clear()
        return AgentResult.ok {
            put("dropped", dropped)
            // Deliberately not reset by `clear`: history happened, and the counter is what says
            // so after the evidence has been thrown away.
            put("totalRecorded", bridge.events.totalRecorded)
        }
    }

    /**
     * Matches [contains] within a tick window, and answers either way.
     *
     * A miss is [EVENT_NOT_FOUND] and not an exception, because "it did not
     * happen" is the answer to a legitimate question - half of the assertions an agent makes
     * are that something did *not* occur - and an exception would reach it as `tool_threw`,
     * which reads like the engine broke.
     */
    @AgentTool(
        name = "events.assert_event",
        description = "Check whether an event containing this text was recorded inside a " +
            "tick window, and return the matching entry or a typed miss. This is how a " +
            "session asserts that something happened without string-matching a state dump.",
    )
    public fun assertEvent(
        @Arg(description = "Substring the event message must contain.")
        contains: String,
        @Arg(description = "How many ticks back from now to search.", required = false, default = "60")
        within: Int,
        // Nullable, so "not given" and "tick 0" stay different things. A default of `now -
        // within` cannot be written as an @Arg default: it is a value only the call knows.
        @Arg(
            description = "Absolute tick to search from, overriding within. Use it to assert " +
                "against a tick a previous tool call returned.",
            required = false,
        )
        since: Long?,
    ): AgentResult {
        val now = clock.tick.value
        if (within < 0) {
            return AgentResult.failed(
                AgentErrorKind.BAD_ARGUMENT,
                "events.assert_event got within=$within; a window is a count of ticks back from now",
            )
        }
        val from = since ?: (now - within)
        val matches = collect(bridge.events.capacity, contains, from)
        val matched = matches.lastOrNull() ?: return AgentResult.failed(
            EVENT_NOT_FOUND,
            "no event containing \"$contains\" was recorded between tick $from and tick $now; " +
                "${bridge.events.size} event(s) are held and " +
                "${bridge.events.totalRecorded} have been recorded in total",
        )
        return AgentResult.ok {
            put("matched", true)
            put("tick", matched.tick)
            put("message", matched.message)
            put("occurrences", matches.size)
            put("searchedFromTick", from)
        }
    }

    /**
     * The newest [limit] entries matching [contains] at or after [sinceTick], oldest first.
     *
     * Allocating, and that is correct here: this runs once per tool call on the simulation
     * thread, not once per tick, and the digest's own walk over the same ring stays on the
     * allocation-free [AgentEventRing.forEachRecent].
     *
     * An [AgentEventRing.UNSTAMPED] entry never satisfies a window. That is the honest outcome
     * for an event nobody dated, and it is why the constant is `-1` rather than `0`: tick zero
     * is a real tick.
     */
    private fun collect(limit: Int, contains: String?, sinceTick: Long): List<Entry> {
        val out = ArrayList<Entry>()
        bridge.events.forEachRecentStamped(bridge.events.capacity) { message, tick ->
            if (contains != null && !message.contains(contains)) return@forEachRecentStamped
            if (sinceTick != Long.MIN_VALUE) {
                if (tick == AgentEventRing.UNSTAMPED || tick < sinceTick) return@forEachRecentStamped
            }
            out.add(Entry(message, tick))
        }
        return if (out.size <= limit) out else out.subList(out.size - limit, out.size)
    }

    private class Entry(val message: String, val tick: Long)

    override fun toString(): String = "EventsToolset(${bridge.events.size} held)"

    public companion object {

        /** Nothing matched the pattern inside the window. A typed answer, not a failure of the engine. */
        public val EVENT_NOT_FOUND: AgentErrorKind = AgentErrorKind("event_not_found")

        /**
         * Entries `recent_events` returns when the caller names no limit.
         *
         * Eight rather than forty, and the number is honest about what a page can hold: the
         * byte budget in [ResultPage] usually ends the page first, so this is the cap on a quiet
         * ring with short messages rather than a promise. Forty was a promise the digest could
         * never keep - forty events is roughly 2KB, and the whole state document is 2048 bytes.
         */
        public const val DEFAULT_LIMIT: Int = 8

        /** Ticks back from now that `assert_event` searches when the caller names no window. */
        public const val DEFAULT_WINDOW: Int = 60
    }
}
