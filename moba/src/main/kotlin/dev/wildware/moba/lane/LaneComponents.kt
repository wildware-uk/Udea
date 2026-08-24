package dev.wildware.moba.lane

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.moba.level.Team
import dev.wildware.udea.annotations.Lifetime
import dev.wildware.udea.annotations.Net
import dev.wildware.udea.annotations.Replicated
import dev.wildware.udea.annotations.Sim
import dev.wildware.udea.core.identity.NetId

/*
 * The five components a lane adds to this game.
 *
 * Every one of them is `@Replicated`, named in `net-components.lock`, and registered in
 * `MobaGame.componentRegistry`. That is not three places to remember: it is one contract stated
 * three times because each half enforces a different thing. The lock assigns the wire id, the
 * annotation generates the codec, and the registry is what `SnapshotService` walks - and an
 * unregistered component is not partly captured, it is INVISIBLE to capture. This repository has
 * already shipped that bug once: a rewind brought back twenty-seven units, five of them bare
 * shells, because seven of a unit's nine components were not in the registry.
 *
 * A wave timer that a rewind did not restore would be the same class of defect wearing a
 * different hat: rewind 600 ticks and the next wave would arrive on a schedule from a future
 * that no longer exists. `LaneRewindTest` rewinds across a wave and checks the timer, the
 * waypoints and the gold all came back.
 */

/**
 * A unit that belongs to a creep wave: which way it walks, what it is worth, and whether it paid.
 *
 * ## Why a creep is an ordinary level unit plus this
 *
 * A creep is spawned from `MobaBlueprints.soldier` or `.skeleton` - the *same* code blueprint
 * `level/test_level` spawns its line units from - with this component added by a
 * `SpawnOverrides`. It therefore has the `GameUnit`, the `Combatant`, the `Attributes`, the
 * `Abilities` and the `CharacterView` every other unit has, which means it is targeted by
 * `UnitBattleSystem`, swung at by `AbilityAutopilotSystem`, animated by `CharacterStateSystem`,
 * given a bar by `HealthbarRenderSystem` and retired by `DeathSystem` with no new code in any of
 * them. A bespoke creep entity would have been six re-implementations of things that already
 * work, and six chances for a creep to be the one unit in the game that does not animate.
 *
 * What this component adds is the three things a creep has that a line unit does not: a place in
 * the lane, a bounty, and a record of whether the bounty has been paid.
 *
 * ## The one thing it deliberately does not do
 *
 * It does not make a creep invisible to the match. `MatchSystem` skips a `LaneCreep` when it
 * counts the sides, and that skip is in `MatchSystem` rather than expressed as an absent team
 * here - because a creep with `Team.NONE` on its `GameUnit` and a real team on its `Combatant`
 * would break the invariant `MobaIntegrationTest` pins, and a unit that walks toward one enemy
 * and swings at another is a worse bug than a foreign one-line edit.
 */
@Replicated
public class LaneCreep(
    /**
     * Gold for the killing blow. Zero for every blow that is not the killing one.
     *
     * On the component and not read from [LaneGeometry] at payout, so a balance change reaches
     * creeps spawned after it and not creeps already walking - which is the property that makes
     * a mid-match rebalance observable rather than retroactive.
     */
    @Sim public var goldBounty: Int = LaneGeometry.CREEP_GOLD,
    /**
     * `+1` walking toward the undead end, `-1` toward the soldier end.
     *
     * `lifetime = OnCreate`: a creep never turns round, so this rides the Create packet and every
     * full resend and no Update. `Combatant.teamId` made the same trade for the same reason -
     * before the generator read the argument, a value that never moves rode a delta on every
     * capture-and-diff, and a rewind then looked like every creep changing direction at once.
     */
    @Net(lifetime = Lifetime.OnCreate) public var heading: Int = 1,
    /**
     * Whether this creep's bounty has been paid out.
     *
     * `@Sim`, so it is snapshotted and never sent. It has to be snapshotted or a rewind across a
     * kill would pay the same creep twice - which is the difference between a farm score and a
     * random number generator with a positive drift.
     */
    @Sim public var paid: Boolean = false,
    /** Which wave this creep came in with. One-based; a signal for a test and a log line. */
    @Sim public var waveNumber: Int = 0,
    /** The waypoint it is walking toward, `0 until` [LaneGeometry.WAYPOINTS]. */
    @Sim public var waypoint: Int = 0,
    /** Experience for the death, shared by every enemy champion in range. */
    @Sim public var xpBounty: Int = LaneGeometry.CREEP_XP,
) : Component<LaneCreep> {

    override fun type(): ComponentType<LaneCreep> = LaneCreep

    override fun toString(): String =
        "LaneCreep(wave=$waveNumber, waypoint=$waypoint, heading=$heading, paid=$paid)"

    /** Fleks' handle for this component. */
    public companion object : ComponentType<LaneCreep>()
}

/**
 * A tower: whose it is, what it is pointing at, and when it may shoot next.
 *
 * ## What a tower is made of, and what it is honestly not
 *
 * A tower entity is a [dev.wildware.moba.Position] and this. It is **not** a `Combatant`, which
 * has one consequence worth stating plainly rather than burying: **towers in this wave cannot be
 * destroyed.** Nothing can target one, because every targeting path in this game - the spatial
 * one in `UnitBattleSystem` and the ability one through `CombatIndex` - is keyed on `GameUnit`
 * or `Combatant`, and it has neither. Making a tower killable means giving it an `Attributes`, a
 * `GameplayEffects` and a `Combatant`, at which point `UnitBattleSystem` walks every creep into
 * it and `MatchSystem` counts it as a living unit - a wave of its own, not a field on this class.
 *
 * So what issue #130 asks for is here - a structure that hits whoever comes close, prioritises
 * creeps over champions, and switches to a champion that attacks an ally in range - and what it
 * does not ask for, a destructible objective, is not.
 *
 * ## Why it applies `ability/damage` rather than owning an ability
 *
 * A tower has no `Abilities` and no ability slots, so `AbilitySystem` will never run anything for
 * it. [TowerSystem] instead opens an application on the same `EffectApplier` every exec in the
 * game uses, with `ability/damage` and the tower's own `NetId` as the source. The consequence is
 * the one that matters for issue #131: the damage emits `MobaCues.DAMAGE` carrying that source,
 * so [LastHitSystem] records a tower kill as a tower kill and the champion standing next to it
 * gets nothing. A tower stealing your last hit is a real thing that happens in this game.
 */
@Replicated
public class Tower(
    /**
     * The tick this tower may fire on. Never a wall clock; see [LaneGeometry].
     *
     * `@Sim`, so a rewind restores a half-cooled tower as half-cooled rather than as ready.
     */
    @Sim public var readyTick: Long = Long.MIN_VALUE,
    /** Shots fired. A signal for a test and for a log line, and it rewinds with everything else. */
    @Sim public var shots: Int = 0,
    /**
     * What it is shooting at, as a raw [NetId], or [NetId.NONE].
     *
     * `Int` and not `NetId`, following `GameUnit.targetRaw`: the raw form is what
     * `MobaGame.componentRegistry` names with `FieldKind.Int` today, and widening the schema to
     * carry a `NetId` field is worth doing on its own rather than inside a lane.
     */
    @Sim public var targetRaw: Int = NetId.NONE.raw,
    /** Whose tower. A [Team] constant. `@Net` because a client colours it before it draws it. */
    @Net(lifetime = Lifetime.OnCreate) public var team: Int = Team.NONE,
) : Component<Tower> {

    /** Whether this tower is holding a target. */
    public val hasTarget: Boolean get() = targetRaw != NetId.NONE.raw

    override fun type(): ComponentType<Tower> = Tower

    override fun toString(): String =
        "Tower(" + Team.nameOf(team) + ", shots=" + shots + ", target=" + targetRaw + ")"

    /** Fleks' handle for this component. */
    public companion object : ComponentType<Tower>()
}

/**
 * A champion's gold, experience and level.
 *
 * ## Why the champion carries it and the level does not
 *
 * It is granted by [ChampionSystem] to every entity with a `Player` on it, on the first tick that
 * entity is seen, and it dies with the entity when a match restarts. That is deliberate: a MOBA
 * match starts everybody at level one with a starting purse, and a wallet that outlived the match
 * would carry the last game's farm into this one.
 *
 * Everything on it is `@Net`. A client cannot derive gold - it never sees the killing blow's
 * attribution, which is a server-side reading of a cue queue that is not replicated - so this is
 * a case where the wire is the only way the number can be right on both ends.
 */
@Replicated
public class Wallet(
    /** Gold. Earned on last hits and on nothing else in this wave: no passive income yet. */
    @Net public var gold: Int = 0,
    /** Creeps this champion landed the killing blow on. The number a laning phase is graded on. */
    @Net public var lastHits: Int = 0,
    /** One-based, capped at [LaneGeometry.MAX_LEVEL]. */
    @Net public var level: Int = 1,
    /** Total experience. Compared against [LaneGeometry.xpForLevel]. */
    @Net public var xp: Int = 0,
) : Component<Wallet> {

    override fun type(): ComponentType<Wallet> = Wallet

    override fun toString(): String = "Wallet(gold=$gold, xp=$xp, level=$level, cs=$lastHits)"

    /** Fleks' handle for this component. */
    public companion object : ComponentType<Wallet>()
}

/**
 * Who last damaged this unit, and when.
 *
 * ## Why this exists at all, and why it is read off a cue queue
 *
 * Gold on the killing blow needs an answer to "who struck it", and nothing in this game recorded
 * one. `DeathSystem` sees a health attribute at or below zero and knows only *that* the unit
 * died. The damage itself is an `ability/damage` application, and `EffectApplier.applyTo` carries
 * the attacker as `source` - into `GameplayEffects` (which an instant effect does not keep) and
 * into a `MobaCues.DAMAGE` cue on the GAS cue queue (which lives exactly one tick).
 *
 * So [LastHitSystem] reads that queue before `GasCueForwardSystem` drains it and writes the pair
 * down here. The alternative - a `lastAttacker` field threaded through `CombatRules.damage`,
 * `MeleeAttackExec`, `OrcSpinExec` and `ProjectileSystem` - is four edits to files this wave does
 * not own, and it would have to be repeated by every future damage source. Reading the cue is one
 * system, and it is correct for a damage source that does not exist yet.
 *
 * ## The cost, stated
 *
 * A cue can be dropped: `GasCueQueue` has a capacity and counts `droppedCount` when nobody
 * drains. A dropped `DAMAGE` cue is a killing blow this component never learns about, and the
 * creep then pays nobody. That is a *lost* payout and never a misattributed one, which is the
 * right way round for a currency. `LaneEconomyTest` asserts the queue is not dropping over a real
 * wave rather than assuming it.
 */
@Replicated
public class LastHit(
    /** The attacker, as a raw [NetId], or [NetId.NONE]. See `Tower.targetRaw` on the raw form. */
    @Sim public var attackerRaw: Int = NetId.NONE.raw,
    /** The tick that blow landed on, or [NEVER]. */
    @Sim public var tick: Long = NEVER,
) : Component<LastHit> {

    /** Whether anything has ever hit this unit. */
    public val known: Boolean get() = tick != NEVER

    override fun type(): ComponentType<LastHit> = LastHit

    override fun toString(): String = "LastHit(by=$attackerRaw at $tick)"

    /** Fleks' handle for this component. */
    public companion object : ComponentType<LastHit>() {

        /**
         * [tick] before anything has hit this unit.
         *
         * `Long.MIN_VALUE` and not zero, for the reason `Respawn.NOT_SCHEDULED` is not zero: tick
         * zero is a real tick, and a sentinel the clock can reach is a unit that was hit at boot
         * by whoever holds net id zero.
         */
        public const val NEVER: Long = Long.MIN_VALUE
    }
}

/**
 * The lane itself: which wave is next, when it arrives, and how many creeps are walking.
 *
 * One per world, on a singleton entity that carries nothing else - the same shape `MatchState`
 * uses, and for the same reason: every unit in this game can die and the lane has to outlive the
 * last creep in it. [LaneSystem] mints it over a populated world and a scene swap destroys it,
 * so a match restart re-opens the lane from wave zero without a reset path of its own.
 */
@Replicated
public class LaneState(
    /** Creeps currently walking, both sides. Rewritten every tick. */
    @Net public var creepsAlive: Int = 0,
    /** The tick the next wave spawns on. */
    @Sim public var nextWaveTick: Long = 0L,
    /** The tick the lane opened, which is the tick this entity was minted. */
    @Sim public var startedTick: Long = 0L,
    /** Whether the towers have been queued. Guards the spawn against running twice. */
    @Sim public var towersPlaced: Boolean = false,
    /** Waves spawned so far. One-based once the first has gone out. */
    @Net public var waveNumber: Int = 0,
) : Component<LaneState> {

    override fun type(): ComponentType<LaneState> = LaneState

    override fun toString(): String =
        "LaneState(wave=$waveNumber, next=$nextWaveTick, creeps=$creepsAlive)"

    /** Fleks' handle for this component. */
    public companion object : ComponentType<LaneState>()
}
