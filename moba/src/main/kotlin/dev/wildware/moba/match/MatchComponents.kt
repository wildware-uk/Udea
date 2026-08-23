package dev.wildware.moba.match

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.moba.level.Team
import dev.wildware.udea.annotations.Net
import dev.wildware.udea.annotations.Replicated
import dev.wildware.udea.annotations.Sim

/**
 * Where a match is in its life. The whole loop, as three values.
 *
 * An enum and not three booleans, so a `when` over it is exhaustive at every site that has to
 * handle a phase - which is the property that makes adding a fourth (a countdown, a draft) a
 * compile error at each decision rather than a silently-skipped branch.
 *
 * It is carried on the wire as its ordinal, like every enum this build replicates, so constants
 * are appended and never inserted.
 */
public enum class MatchPhase {

    /** Units are alive on more than one side and nobody has won yet. */
    Fighting,

    /**
     * A side has won, or the clock ran out, and the result is standing.
     *
     * The world is deliberately **not** frozen here - corpses still clear, arrows still land.
     * Freezing would be a second definition of "the simulation runs", and what a player needs
     * is a few seconds to see who won rather than a paused world.
     */
    Ended,

    /**
     * The restart has been queued on the barrier and has not drained yet.
     *
     * Its own phase rather than a flag, because [MatchSystem] runs every tick and a queued
     * restart must not be queued a second time on the next one. The world is torn down and
     * repopulated at the top of the following tick, which destroys the entity carrying this
     * value - so nothing ever observes a [MatchState] in this phase for more than one tick.
     */
    Restarting,
}

/**
 * The match itself: who is left, who won, which match this is, and what it was laid out from.
 *
 * ## Why this is a component on an entity and not a field on a service
 *
 * Because it has to survive a `time.rewind`, and the only things that do are components the
 * snapshot registry names. A previous wave measured what the alternative costs: an unregistered
 * component is not partly captured, it is **invisible** to capture, and an entity a restore has
 * to rebuild comes back without it. Match state held in a plain object beside the world would be
 * worse still - a rewind would restore twenty-seven units to tick 400 and leave the scoreboard
 * still saying the match had been won.
 *
 * There is exactly one of these in a world, on an entity that carries nothing else. A singleton
 * entity rather than a component laid over some unit, because every unit in this game can die
 * and the match has to outlive the last thing standing in it.
 *
 * ## What is `@Net` and what is not
 *
 * The scoreboard - the three alive counts, [winner], [phase] and [matchNumber] - is `@Net`: it
 * is the one piece of state every connected client must agree on, and deriving it on the
 * receiving side would be a second copy of [MatchSystem]'s win rule, free to disagree with the
 * authoritative one about who won.
 *
 * [startedTick], [endedTick] and [seed] are `@Sim`: they are snapshotted, so a rewind restores
 * the clock the match is measured against, and they are never sent, because a client that knows
 * the phase has no use for the tick the phase started on.
 *
 * ## Every field here is a tick or a count. There is no wall clock in it.
 *
 * [startedTick] and [endedTick] are `Tick` values as raw longs - the same widening `Corpse` and
 * `CharacterView` make, and for the same reason: `FieldKind` has `Long` and does not yet have
 * `Tick`. Durations are compared against them in ticks (see [MatchRules]), so a match resolves
 * on the same tick on a 30Hz server and a 144Hz client.
 */
@Replicated
public class MatchState(
    /** Which match of this session this is. One-based; the first is 1. */
    @Net public var matchNumber: Int = 1,
    /** Where the match is. See [MatchPhase]. */
    @Net public var phase: MatchPhase = MatchPhase.Fighting,
    /**
     * The winning side once [phase] has left [MatchPhase.Fighting], else [Team.NONE].
     *
     * [Team.NONE] on a finished match means a genuine draw - the clock ran out with two sides
     * level, or the last two units killed each other on the same tick.
     */
    @Net public var winner: Int = Team.NONE,
    /** Living orcs. Rewritten every tick while fighting, and frozen at the result. */
    @Net public var orcAlive: Int = 0,
    /** @see orcAlive */
    @Net public var soldierAlive: Int = 0,
    /** @see orcAlive */
    @Net public var undeadAlive: Int = 0,
    /** The tick this match's units were placed on. */
    @Sim public var startedTick: Long = 0L,
    /** The tick the result was decided on, or `0` while [phase] is [MatchPhase.Fighting]. */
    @Sim public var endedTick: Long = 0L,
    /**
     * What `RngStream.Spawn` was seeded with before this match's level was populated.
     *
     * Recorded rather than derived, because it is the one number that makes a match
     * reproducible: seeding the spawn stream with it and reloading the scene lays the
     * twenty-seven units out in exactly the same places. See [MatchSystem].
     */
    @Sim public var seed: Long = 0L,
) : Component<MatchState> {

    /** Living units on [team], or `0` for a team this game does not have. */
    public fun aliveOn(team: Int): Int = when (team) {
        Team.ORC -> orcAlive
        Team.SOLDIER -> soldierAlive
        Team.UNDEAD -> undeadAlive
        else -> 0
    }

    override fun type(): ComponentType<MatchState> = MatchState

    override fun toString(): String =
        "MatchState(#" + matchNumber + " " + phase + " winner=" + Team.nameOf(winner) +
            " orc=" + orcAlive + " soldier=" + soldierAlive + " undead=" + undeadAlive + ")"

    /** Fleks' handle for this component. */
    public companion object : ComponentType<MatchState>()
}

/**
 * A unit that comes back after it dies, and where it comes back to.
 *
 * ## Why the dead unit is not deleted, and not special-cased either
 *
 * `dev.wildware.moba.ability.DeathSystem` already retires a unit rather than removing it: the
 * body keeps its `Position`, its `GameUnit` and its `CharacterView`, and loses its `Combatant`,
 * which is what takes it out of every targeting family at once. That is the old game's
 * `GameUnit.isDead` pattern with no flag in it - the *absence* of a component is the flag - and
 * it happens to be exactly what a respawn needs, because the entity, its net id, its abilities
 * and its attribute vector are all still there several seconds later.
 *
 * So respawning is putting back the one component death took away, plus the health, plus the
 * position. This component is the timer that says when, and the record of where.
 *
 * It is on the entity rather than in [MatchState] because it is per-unit state. Nothing here
 * says "player": [RespawnSystem] grants one only to the unit a human drives, and that is a
 * decision in the *system* rather than a limit of this component - Phase 5's creep waves
 * respawn on the same mechanism.
 *
 * ## The costs, stated
 *
 * - **[maxHealth] is captured, not looked up.** A unit's maximum health is its blueprint's, and
 *   a blueprint is not reachable from an entity: `Attributes` holds a dense vector and no
 *   provenance. So the first tick the unit is seen alive records the number. A unit already hurt
 *   on that tick would respawn at the value it had then, which is why the grant happens on the
 *   tick the level places it and never later.
 * - **A respawn does not clear lingering effects.** A damage-over-time applied a moment before
 *   death is still on the entity and resumes ticking against the restored health. Clearing them
 *   means walking `GameplayEffects` and returning handles to the allocator, which belongs to the
 *   effect system and not here.
 */
@Replicated
public class Respawn(
    /** How many times this unit has died. Reset with the match, because the entity is. */
    @Net public var deaths: Int = 0,
    /**
     * The tick this unit may stand up on, or [NOT_SCHEDULED] while it is alive.
     *
     * `@Sim`: a client is told a unit is dead by its `CharacterView.state`, and the tick it may
     * return on is the server's business.
     */
    @Sim public var readyTick: Long = NOT_SCHEDULED,
    /** Health to restore on standing up. Recorded the first tick this unit is seen alive. */
    @Sim public var maxHealth: Float = 0f,
    /** Where to stand up: the position the level placed this unit at. */
    @Sim public var spawnX: Float = 0f,
    /** @see spawnX */
    @Sim public var spawnY: Float = 0f,
) : Component<Respawn> {

    /** Whether a respawn is pending, as opposed to this unit being alive. */
    public val isScheduled: Boolean get() = readyTick != NOT_SCHEDULED

    override fun type(): ComponentType<Respawn> = Respawn

    override fun toString(): String =
        "Respawn(deaths=" + deaths + ", ready=" + (if (isScheduled) readyTick.toString() else "-") + ")"

    /** Fleks' handle for this component. */
    public companion object : ComponentType<Respawn>() {

        /**
         * [readyTick] while the unit is alive.
         *
         * `Long.MIN_VALUE` and not `0`: tick zero is a real tick, and a sentinel the clock can
         * legitimately reach is a unit that stands up on the first tick of the game.
         */
        public const val NOT_SCHEDULED: Long = Long.MIN_VALUE
    }
}
