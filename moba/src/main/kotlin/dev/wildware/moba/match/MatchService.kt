package dev.wildware.moba.match

import dev.wildware.udea.core.ServiceKey
import dev.wildware.udea.core.serviceKey
import dev.wildware.moba.level.Team

/**
 * The match, as a value, for a caller that is not holding a Fleks world.
 *
 * An entry point, a test and an HTTP handler all want the same six numbers and none of them
 * should have to know that the truth is a component on a singleton entity. Allocated on demand
 * by [MatchService.report] and never on a tick.
 */
public class MatchReport(
    /** Which match of this session. One-based. */
    public val matchNumber: Int,
    public val phase: MatchPhase,
    /** A [Team] constant, or [Team.NONE] for "not decided" or "a draw". */
    public val winner: Int,
    public val orcAlive: Int,
    public val soldierAlive: Int,
    public val undeadAlive: Int,
    /** The tick this match's units were placed on. */
    public val startedTick: Long,
    /** The tick the result was decided on, or `0` while fighting. */
    public val endedTick: Long,
    /** What `RngStream.Spawn` was seeded with before this match's level was laid out. */
    public val seed: Long,
) {

    /** Whether a result is standing. False while fighting. */
    public val isDecided: Boolean get() = phase != MatchPhase.Fighting

    /** How many ticks this match has been decided for, or `0` while it is still on. */
    public fun ticksSinceResult(now: Long): Long = if (isDecided) now - endedTick else 0L

    override fun toString(): String =
        "MatchReport(#" + matchNumber + " " + phase + " winner=" + Team.nameOf(winner) +
            " orc=" + orcAlive + " soldier=" + soldierAlive + " undead=" + undeadAlive +
            " seed=" + seed + ")"
}

/**
 * The match, readable from outside the simulation, without a screenshot and without Fleks.
 *
 * ## Why this exists beside the component
 *
 * [MatchState] is the truth and this is a **mirror**, in exactly the sense `Position.hp` mirrors
 * the `health` attribute. That is stated first because it is the thing a reader has to get
 * right: writing to this object changes nothing, and [MatchSystem] overwrites every field of it
 * on the next tick.
 *
 * It exists because the alternative for a caller outside the world is walking a Fleks family,
 * which needs the `World`, the component type and a `with(world)` block - three things an entry
 * point, an audio probe or a test harness has no business holding just to ask who is winning.
 *
 * The agent surface reaches the same numbers by the other door and does not need this one: with
 * `MatchState` registered as an `AgentComponentType`, `world.query_entities with=MatchState`
 * and `world.get_component_field` read the component itself, so an agent's answer comes from the
 * authoritative state rather than from a copy that could be a tick stale.
 *
 * ## Allocation
 *
 * [publish] writes nine primitives into fields and allocates nothing, because it runs once per
 * tick. [report] allocates one [MatchReport] and is called by whoever asked, which is never a
 * per-tick path.
 */
public class MatchService {

    /** Which match of this session. `0` before the first one has been created. */
    public var matchNumber: Int = 0
        private set

    /** Where the match is, or [MatchPhase.Fighting] before there is one. See [hasMatch]. */
    public var phase: MatchPhase = MatchPhase.Fighting
        private set

    /** A [Team] constant, or [Team.NONE] while undecided or on a draw. */
    public var winner: Int = Team.NONE
        private set

    /** Living orcs, as of the last tick. */
    public var orcAlive: Int = 0
        private set

    /** @see orcAlive */
    public var soldierAlive: Int = 0
        private set

    /** @see orcAlive */
    public var undeadAlive: Int = 0
        private set

    /** The tick this match's units were placed on. */
    public var startedTick: Long = 0L
        private set

    /** The tick the result was decided on, or `0` while fighting. */
    public var endedTick: Long = 0L
        private set

    /** What `RngStream.Spawn` was seeded with before this match's level was laid out. */
    public var seed: Long = 0L
        private set

    /** Matches that have been decided in this process. What a session's history is counted by. */
    public var decidedCount: Long = 0L
        private set

    /**
     * Whether a match has ever been published into this mirror.
     *
     * The distinction a `matchNumber` of `0` cannot carry on its own: a world that has not
     * ticked yet and a world whose match was somehow destroyed both read as zero, and a caller
     * polling for a result has to be able to tell "no match" from "match one".
     */
    public var hasMatch: Boolean = false
        private set

    /** Copies [state] into this mirror. Called once per tick by [MatchSystem]; allocates nothing. */
    internal fun publish(state: MatchState) {
        matchNumber = state.matchNumber
        phase = state.phase
        winner = state.winner
        orcAlive = state.orcAlive
        soldierAlive = state.soldierAlive
        undeadAlive = state.undeadAlive
        startedTick = state.startedTick
        endedTick = state.endedTick
        seed = state.seed
        hasMatch = true
    }

    /** Counts a result. Called by [MatchSystem] on the tick a match is decided, and only then. */
    internal fun countDecision() {
        decidedCount++
    }

    /**
     * A snapshot of the mirror as one object, or `null` when no match has been published.
     *
     * `null` and not an all-zero report, because "there is no match" and "the match is match
     * zero with nobody alive" are different states and a caller polling for a winner has to be
     * able to tell them apart.
     */
    public fun report(): MatchReport? {
        if (!hasMatch) return null
        return MatchReport(
            matchNumber = matchNumber,
            phase = phase,
            winner = winner,
            orcAlive = orcAlive,
            soldierAlive = soldierAlive,
            undeadAlive = undeadAlive,
            startedTick = startedTick,
            endedTick = endedTick,
            seed = seed,
        )
    }

    override fun toString(): String = report()?.toString() ?: "MatchService(no match yet)"

    public companion object {

        /**
         * How the match reaches a system or an entry point that did not build it.
         *
         * A [ServiceKey] and not a field on `GameContext`: the context holds a small fixed set of
         * engine-wide services, and a game's scoreboard is not one of them.
         */
        public val KEY: ServiceKey<MatchService> = serviceKey("MatchService")
    }
}
