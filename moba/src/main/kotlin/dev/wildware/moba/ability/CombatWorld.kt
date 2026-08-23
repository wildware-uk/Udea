package dev.wildware.moba.ability

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.gas.Attributes
import dev.wildware.udea.gas.GameplayEffects

/**
 * What an ability can ask about the units around it, and do to them.
 *
 * ## Why this interface exists at all
 *
 * `AbilityContext` hands an exec its **own** attributes, its own effects and its own [NetId], and
 * nothing else - which is correct: `udea-gas` has no world, no components beyond its three, and
 * no idea what a "unit" or a "team" is. But every ability in this game is about somebody else:
 * the melee attack needs the nearest enemy, the spin needs all of them, the heal needs damaged
 * allies. The old code answered that with `world.system<Box2DSystem>().box2DWorld.query(...)` and
 * a `mutableSetOf<Entity>` per call, from inside the ability, on the simulation's hot path.
 *
 * This is the seam that replaces it. It is a game-side interface, implemented by [CombatIndex]
 * over the Fleks world, and an exec holds it rather than a `World` - so an exec is testable
 * against a fake, and a `Family` lookup never appears inside an ability.
 *
 * ## Every query writes into a caller-owned buffer
 *
 * [query] fills a [NetIdBuffer] the caller owns and reuses. No `Set`, no `List`, no `filter`, no
 * `minByOrNull` - the four allocations the old `getUnitsWithin(...).filter { }.minByOrNull { }`
 * chain made per swing, per unit, per tick.
 */
public interface CombatWorld {

    /** Whether [id] is a live unit this index knows about. */
    public fun contains(id: NetId): Boolean

    /** [id]'s world x, or `0` when it is not a live unit. Check [contains] first. */
    public fun x(id: NetId): Float

    /** [id]'s world y, or `0` when it is not a live unit. */
    public fun y(id: NetId): Float

    /** [id]'s team, or [Teams.NEUTRAL]. */
    public fun teamOf(id: NetId): Int

    /** [id]'s attributes, or `null` when it is not a live unit. */
    public fun attributesOf(id: NetId): Attributes?

    /** [id]'s applied effects, or `null` when it is not a live unit. */
    public fun effectsOf(id: NetId): GameplayEffects?

    /** Adds an impulse to [id], in world units per tick. A no-op on an unknown id. */
    public fun impulse(id: NetId, x: Float, y: Float)

    /**
     * Fills [into] with every live unit within [radius] of ([centreX], [centreY]) that stands in
     * [relation] to [viewerTeam], excluding [exclude].
     *
     * Results are ordered by [NetId], not by distance, so the answer does not depend on the order
     * the Fleks world happens to hold entities in. [nearest] is the distance-ordered question.
     *
     * @return how many were written, which is also `into.size`. A buffer that fills up stops
     *   taking entries; it does not grow and does not throw, because an ability that finds nine
     *   targets where it can hold eight has still hit eight.
     */
    public fun query(
        centreX: Float,
        centreY: Float,
        radius: Float,
        relation: TeamRelation,
        viewerTeam: Int,
        exclude: NetId,
        into: NetIdBuffer,
    ): Int

    /**
     * The closest unit within [radius] standing in [relation] to [viewerTeam], or [NetId.NONE].
     *
     * Ties break on the lower [NetId], so two enemies at exactly the same distance resolve the
     * same way on every machine.
     */
    public fun nearest(
        centreX: Float,
        centreY: Float,
        radius: Float,
        relation: TeamRelation,
        viewerTeam: Int,
        exclude: NetId,
    ): NetId

    /**
     * Queues an arrow, and returns the [NetId] it will have.
     *
     * Queued rather than created: a spawn is a `SimBarrier` command, so the arrow exists at the
     * start of the next tick and every observer of this tick sees the same world. The velocity is
     * world units **per tick**.
     */
    public fun fireArrow(
        owner: NetId,
        team: Int,
        x: Float,
        y: Float,
        vx: Float,
        vy: Float,
        damage: Float,
    ): NetId

    public companion object {

        /**
         * A world with no units in it, and the value a [CombatWorldRef] holds before binding.
         *
         * Deliberately a working empty world rather than a throwing stub: an exec that runs one
         * tick before the index is built should find nobody, not take the game down.
         */
        public val Empty: CombatWorld = object : CombatWorld {
            override fun contains(id: NetId): Boolean = false
            override fun x(id: NetId): Float = 0f
            override fun y(id: NetId): Float = 0f
            override fun teamOf(id: NetId): Int = Teams.NEUTRAL
            override fun attributesOf(id: NetId): Attributes? = null
            override fun effectsOf(id: NetId): GameplayEffects? = null
            override fun impulse(id: NetId, x: Float, y: Float) = Unit

            override fun query(
                centreX: Float,
                centreY: Float,
                radius: Float,
                relation: TeamRelation,
                viewerTeam: Int,
                exclude: NetId,
                into: NetIdBuffer,
            ): Int {
                into.clear()
                return 0
            }

            override fun nearest(
                centreX: Float,
                centreY: Float,
                radius: Float,
                relation: TeamRelation,
                viewerTeam: Int,
                exclude: NetId,
            ): NetId = NetId.NONE

            override fun fireArrow(
                owner: NetId,
                team: Int,
                x: Float,
                y: Float,
                vx: Float,
                vy: Float,
                damage: Float,
            ): NetId = NetId.NONE

            override fun toString(): String = "CombatWorld.Empty"
        }
    }
}

/** Which units a query wants, relative to the asking unit's team. */
public enum class TeamRelation {

    /** Somebody to hit. */
    Enemy,

    /** Somebody to help. Includes the asking unit unless it is excluded. */
    Friendly,

    /** Anybody at all. */
    Any,
    ;

    /** Whether [other] stands in this relation to [viewer]. */
    public fun matches(viewer: Int, other: Int): Boolean = when (this) {
        Enemy -> Teams.areEnemies(viewer, other)
        Friendly -> Teams.areAllies(viewer, other)
        Any -> true
    }
}

/**
 * A fixed-capacity list of [NetId]s that a query fills and a caller reuses.
 *
 * An `IntArray` of raw ids, because a `NetId` is a value class over `Int` and an `Array<NetId>`
 * would box every entry. One of these lives on each exec and is cleared per use, so a whole
 * game's worth of area queries allocates nothing after construction.
 */
public class NetIdBuffer(
    /** How many ids it can hold. */
    public val capacity: Int,
) {

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val raw = IntArray(capacity) { NetId.NONE.raw }

    /** How many ids are in it. */
    public var size: Int = 0
        private set

    /** Whether the last fill ran out of room. Diagnostic, not an error. */
    public var overflowed: Boolean = false
        private set

    /** The id at [index]. */
    public operator fun get(index: Int): NetId {
        require(index in 0 until size) { "no id at $index; the buffer holds $size" }
        return NetId.ofRaw(raw[index])
    }

    /** Appends [id], or records an overflow when full. @return whether it was appended. */
    public fun add(id: NetId): Boolean {
        if (size == capacity) {
            overflowed = true
            return false
        }
        raw[size] = id.raw
        size++
        return true
    }

    /** Empties it. */
    public fun clear() {
        size = 0
        overflowed = false
    }

    override fun toString(): String = "NetIdBuffer($size/$capacity)"
}

/**
 * The mutable box an exec reads its [CombatWorld] out of.
 *
 * An exec is a singleton, built when the ability table is built - which is before a Fleks world
 * exists, because the world is built from the module list the table is part of. So the exec
 * cannot be handed the index in its constructor. It is handed this, and [CombatIndex] binds
 * itself into it when the world builds it.
 *
 * One box per game instance: two games in one JVM each get their own, which is why this is a
 * class and not an object.
 */
public class CombatWorldRef {

    /** The bound world, or [CombatWorld.Empty] before a [CombatIndex] has bound one. */
    public var world: CombatWorld = CombatWorld.Empty
        private set

    /** Binds [index]. Called once, by the system the world constructs. */
    public fun bind(index: CombatWorld) {
        world = index
    }

    override fun toString(): String = "CombatWorldRef($world)"
}
