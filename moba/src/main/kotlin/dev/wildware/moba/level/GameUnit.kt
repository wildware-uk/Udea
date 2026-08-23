package dev.wildware.moba.level

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.annotations.Net
import dev.wildware.udea.annotations.Replicated
import dev.wildware.udea.annotations.Sim
import dev.wildware.udea.core.identity.NetId

/**
 * A fighting unit: whose side it is on, what it is, and what it is currently doing about it.
 *
 * ## Why this component exists at all
 *
 * `moba` had one component, [dev.wildware.moba.Position], and one blueprint called `grunt`, and
 * the whole simulation was a unit sliding sideways. The old example game - the one this port
 * brings back - split the same idea across `Team`, `GameUnit` and `AIUnit`: a team id, a
 * liveness flag, and a marker saying "an AI drives this". Three components for three booleans'
 * worth of state made every query a three-way join, and the marker was read by nothing except
 * the family it named.
 *
 * So it is one component here. [team] is what the old `Team.teamId` was, [kind] carries the
 * per-unit numbers the old `character/<name>.udea.kts` attribute blocks held, and liveness is not
 * stored at all: a dead unit is a removed entity (see [UnitDeathSystem]), which is the
 * difference between a world an agent can count and a world where `world.query_entities` returns
 * corpses a caller has to know to filter out.
 *
 * ## Health lives in `udea-gas`, and `Position.hp` is its window
 *
 * The truth is the `health` attribute on the entity's `Attributes` component, written by every
 * `ability/damage` and `ability/heal_over_time` this game applies.
 * `dev.wildware.moba.ability.DeathSystem` copies it onto `Position.hp` once a tick, and that copy
 * is what the snapshot ring records, what `world.describe_entity` shows and what the healthbar
 * draws - because `Attributes` has no replicator and no snapshot schema entry, so none of those
 * three can see it directly.
 *
 * The cost is stated rather than defended: writing `hp` from outside changes the number for one
 * tick and is overwritten on the next, and a `time.rewind` restores the window without restoring
 * what is behind it. Closing that means an `Attributes` replicator, which is a `udea-codegen`
 * change and not a level's.
 */
@Replicated
public class GameUnit(
    /**
     * Which side. One of [Team]'s constants; [Team.NONE] means "fights nobody and nobody fights
     * it", which is what an entity spawned without a team would otherwise silently be.
     *
     * `@Net` because a client has to know a side before it can colour a unit, and because it is
     * the field an agent filters on: `world.query_entities with=GameUnit where team=1`.
     */
    @Net public var team: Int = Team.NONE,
    /**
     * Which [UnitKind] this is, as an ordinal, because a wire field is a number and an enum on
     * the wire is its ordinal in every replicator this build generates.
     *
     * The stats behind it - health, damage, reach, speed - are deliberately **not** copied onto
     * the entity. They are read out of [UnitKind] by ordinal when a system needs one, so an edit
     * to a unit's damage reaches units that already exist rather than only ones spawned later.
     */
    @Net public var kind: Int = 0,
    /**
     * Who this unit is hitting, as a raw [NetId], or [NetId.NONE].
     *
     * `Int` and not `NetId`: the raw form is what `MobaGame.componentRegistry` can name today
     * with `FieldKind.Int`, and a `NetId`-typed field would be the first in the build to exercise
     * that codegen path - a widening worth doing on its own rather than inside a level port.
     *
     * `@Sim`, so it rewinds and never reaches the wire: a client has no use for another unit's
     * target, and every packet is cheaper without it.
     */
    @Sim public var targetRaw: Int = NetId.NONE.raw,
) : Component<GameUnit> {

    /**
     * The last tick [UnitBattleSystem] actually walked this unit, or `Long.MIN_VALUE`.
     *
     * Not annotated, so it is neither replicated nor snapshotted, and that is correct rather than
     * lazy: it is a *derived* fact about the tick that just ran, recomputed from scratch every
     * tick by the system that does the walking, and a restored snapshot recomputes it on its first
     * tick. Putting it on the wire would spend a field per unit per packet on something the
     * receiver can see for itself by watching the position move.
     *
     * It exists because [dev.wildware.moba.CharacterStateSystem] has to tell walking from standing
     * still, and "the position differs from last tick" needs somewhere to keep last tick's
     * position - a per-entity scratch component whose only reader would be the animation. One
     * `Long` written by the one system that moves a unit is cheaper and cannot disagree with it.
     */
    @JvmField public var movingTick: Long = Long.MIN_VALUE

    /** The kind, resolved. Out of range means a blueprint wrote a `kind` no constant has. */
    public val unitKind: UnitKind get() = UnitKind.of(kind)

    override fun type(): ComponentType<GameUnit> = GameUnit

    override fun toString(): String = "GameUnit(${UnitKind.nameOf(kind)}, team=$team)"

    /** Fleks' handle for this component. */
    public companion object : ComponentType<GameUnit>()
}

/**
 * The three sides of the ported level, as the old `Team` companion spelled them.
 *
 * Constants and not an enum, because [GameUnit.team] is a replicated `Int` and a caller
 * comparing `team=1` over HTTP has an integer in its hand, not a Kotlin symbol. The old file
 * carried a `// TODO you know this should be assets!` above the same three numbers. It still
 * should be; the level asset naming each entity's blueprint is the step that gets there.
 */
public object Team {

    /** No side. A unit with this fights nobody, and [isHostile] is false for every pairing. */
    public const val NONE: Int = -1

    /** The orcs. Five of them, in the same clearing the player's soldier lands in. */
    public const val ORC: Int = 0

    /** The soldiers and the priest with them: eleven and one. */
    public const val SOLDIER: Int = 1

    /** The skeletons, ten, across the field. */
    public const val UNDEAD: Int = 2

    /** Whether [a] fights [b]. Two units on no team are not enemies; that is what [NONE] means. */
    public fun isHostile(a: Int, b: Int): Boolean = a != b && a != NONE && b != NONE

    /** A short label for a team id, for a log line or a report. */
    public fun nameOf(team: Int): String = when (team) {
        ORC -> "orc"
        SOLDIER -> "soldier"
        UNDEAD -> "undead"
        else -> "none"
    }
}

/**
 * What a unit is, and every number that makes it fight differently from the next one.
 *
 * Ported from the old `character/<name>.udea.kts` attribute blocks - `orc` really did declare
 * `health = 150F` - and from `UnitAISystem`'s hard-coded reach and ranges, rescaled to this
 * game's world units (a unit sprite is about fifty of them across; the old one was about one).
 *
 * They are Kotlin constants rather than authored asset fields for one honest reason: the
 * authored `character` kind is `AssetKind.Unpublishable`, so a character's attribute map cannot
 * be packed into a `.udeapak` yet and a game that read its stats from one would not boot. The
 * level *roster* is authored and load-bearing (see [TestLevelScene]); the stats are not, yet.
 *
 * ## What it no longer carries
 *
 * `maxHealth`, `damage` and `attackCooldownTicks` were here, and they are not any more: health,
 * damage and cooldowns belong to `udea-gas` now. A level unit is dressed by
 * `dev.wildware.moba.ability.UnitBlueprint.dress`, so its health is the `health` attribute, its
 * damage is `strength` read by [dev.wildware.moba.ability.MeleeAttackExec], and its cooldown is a
 * real `ability/cooldown` effect with a handle on its ability instance. Keeping a second set of
 * numbers here would have meant two answers to "how hard does an orc hit", with only one of them
 * reaching the fight. What is left is the half GAS has no opinion about, because `udea-gas` has
 * no space in it: how fast a unit walks and how close it walks before it stops.
 *
 * @property character the name shared by `blueprint/<name>`, `character/<name>_animation_set` and
 *   `MobaUnits.kinds`' `<name>` - the one key the level, the art and the ability table agree on.
 * @property moveSpeed world units per tick while closing on a target.
 * @property reach how close a unit walks before it stops and lets its abilities do the rest. It
 *   must be **inside** [dev.wildware.moba.ability.MeleeAttackExec.RANGE], or a unit closes to a
 *   distance at which its own swing finds nobody and the two stand and stare;
 *   `MobaIntegrationTest` pins that relationship for every kind.
 */
public enum class UnitKind(
    public val character: String,
    public val moveSpeed: Float,
    public val reach: Float,
) {

    /** The player's unit and the ten flanking it. The baseline every other kind reads against. */
    Soldier(character = "soldier", moveSpeed = 0.75f, reach = 24f),

    /** Fragile, and the only thing on the field that heals. `ability/priest_heal`. */
    Priest(character = "priest", moveSpeed = 0.65f, reach = 24f),

    /** `character/orc.udea.kts` declared `health = 150F`, and it is why five hold against eleven. */
    Orc(character = "orc", moveSpeed = 0.7f, reach = 26f),

    /** Cheap and quick, and the most numerous thing on the field. */
    Skeleton(character = "skeleton", moveSpeed = 0.8f, reach = 22f),
    ;

    /** This kind's ordinal, which is what [GameUnit.kind] stores. */
    public val id: Int get() = ordinal

    public companion object {

        private val VALUES: Array<UnitKind> = entries.toTypedArray()

        /**
         * The kind with ordinal [id].
         *
         * Throws rather than defaulting: a `kind` outside the enum means a blueprint and this
         * file disagree, and a unit silently becoming a [Soldier] because of it is a bug that
         * reads as a balance problem.
         */
        public fun of(id: Int): UnitKind {
            require(id in VALUES.indices) { "no UnitKind with ordinal $id; there are ${VALUES.size}" }
            return VALUES[id]
        }

        /** [id]'s name, or a legible placeholder. Safe on a value [of] would refuse. */
        public fun nameOf(id: Int): String = VALUES.getOrNull(id)?.name ?: "kind#$id"
    }
}
