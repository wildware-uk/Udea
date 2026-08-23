package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentEventRing
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentToolDef
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

    private fun recentEvents(command: AgentCommand): AgentResult {
        val limit = command.int("limit", DEFAULT_LIMIT).coerceIn(1, bridge.events.capacity)
        val contains = command.args["contains"]
        val collected = collect(limit, contains, sinceTick = Long.MIN_VALUE)
        return AgentResult.ok {
            put("tick", clock.tick.value)
            put("held", bridge.events.size)
            // The difference between `held` and `totalRecorded` is how much the agent has
            // missed, which is the only way to tell "nothing happened" from "the ring wrapped".
            put("totalRecorded", bridge.events.totalRecorded)
            put("returned", collected.size)
            arr("events") {
                collected.forEach { entry ->
                    element {
                        put("tick", entry.tick)
                        put("message", entry.message)
                    }
                }
            }
        }
    }

    private fun clearEvents(): AgentResult {
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
     * Matches [AgentCommand] `contains` within a tick window, and answers either way.
     *
     * A miss is [AgentErrorKind] `event_not_found` and not an exception, because "it did not
     * happen" is the answer to a legitimate question - half of the assertions an agent makes
     * are that something did *not* occur - and an exception would reach it as `tool_threw`,
     * which reads like the engine broke.
     */
    private fun assertEvent(command: AgentCommand): AgentResult {
        val contains = command.str("contains")
        val now = clock.tick.value
        val within = command.int("within", DEFAULT_WINDOW)
        if (within < 0) {
            return AgentResult.failed(
                AgentErrorKind.BAD_ARGUMENT,
                "events.assert_event got within=$within; a window is a count of ticks back from now",
            )
        }
        val since = command.long("since", now - within)
        val matches = collect(bridge.events.capacity, contains, since)
        val matched = matches.lastOrNull() ?: return AgentResult.failed(
            EVENT_NOT_FOUND,
            "no event containing \"$contains\" was recorded between tick $since and tick $now; " +
                "${bridge.events.size} event(s) are held and " +
                "${bridge.events.totalRecorded} have been recorded in total",
        )
        return AgentResult.ok {
            put("matched", true)
            put("tick", matched.tick)
            put("message", matched.message)
            put("occurrences", matches.size)
            put("searchedFromTick", since)
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

        /** Entries `recent_events` returns when the caller names no limit. */
        public const val DEFAULT_LIMIT: Int = 40

        /** Ticks back from now that `assert_event` searches when the caller names no window. */
        public const val DEFAULT_WINDOW: Int = 60

        /** The three tools, ascending by name. Registered by [engineToolModule]. */
        public fun tools(): List<AgentToolDef<EventsToolset>> = listOf(
            EngineToolDef<EventsToolset>(
                name = "events.assert_event",
                description = "Check whether an event containing this text was recorded inside a " +
                    "tick window, and return the matching entry or a typed miss. This is how a " +
                    "session asserts that something happened without string-matching a state dump.",
                owner = EventsToolset::class,
                args = listOf(
                    agentArg("contains", "string", "Substring the event message must contain."),
                    agentArg(
                        "within", "integer",
                        "How many ticks back from now to search.",
                        required = false,
                        default = DEFAULT_WINDOW.toString(),
                    ),
                    agentArg(
                        "since", "integer",
                        "Absolute tick to search from, overriding within. Use it to assert " +
                            "against a tick a previous tool call returned.",
                        required = false,
                    ),
                ),
            ) { toolset, command -> toolset.assertEvent(command) },

            EngineToolDef<EventsToolset>(
                name = "events.clear_events",
                description = "Empty the event ring so the next read shows only what happened " +
                    "after this call. Reach for it before running a scenario you want to assert " +
                    "against cleanly.",
                owner = EventsToolset::class,
            ) { toolset, _ -> toolset.clearEvents() },

            EngineToolDef<EventsToolset>(
                name = "events.recent_events",
                description = "Read recent game events with the tick each happened on, without " +
                    "consuming them. Use it to find out what the game thinks just happened, " +
                    "including the audit line every agent mutation writes.",
                owner = EventsToolset::class,
                args = listOf(
                    agentArg(
                        "limit", "integer",
                        "How many of the newest entries to return.",
                        required = false,
                        default = DEFAULT_LIMIT.toString(),
                    ),
                    agentArg(
                        "contains", "string",
                        "Return only entries whose message contains this text.",
                        required = false,
                    ),
                ),
            ) { toolset, command -> toolset.recentEvents(command) },
        )
    }
}
