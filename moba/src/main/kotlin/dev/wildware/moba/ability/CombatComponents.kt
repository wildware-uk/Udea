package dev.wildware.moba.ability

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.core.identity.NetId

/**
 * Marks an entity as a unit that can be hit, healed and targeted, and says whose side it is on.
 *
 * Ported from `example/.../component/Team.kt` and `GameUnit.kt`, which were two components where
 * one will do: nothing in the old game was a `GameUnit` without a `Team`, and the queries that
 * mattered (`getUnitsWithin`, `getNearbyFriendlyUnits`) always asked both questions together.
 *
 * The team is an `Int` rather than the old `Team` enum reference because it is replicated state
 * and a spectator team, a neutral camp or a per-match team count are all ordinary numbers. The
 * three the old game had are on [Teams].
 */
public class Combatant(
    /** Which side. Two units with the same value never damage each other. */
    public var teamId: Int = Teams.NEUTRAL,
) : Component<Combatant> {

    override fun type(): ComponentType<Combatant> = Combatant

    override fun toString(): String = "Combatant(team=$teamId)"

    public companion object : ComponentType<Combatant>()
}

/** The sides the old corpus named, as ids. */
public object Teams {

    /** `OrcTeam`. */
    public const val ORC: Int = 0

    /** `SoldierTeam`. */
    public const val SOLDIER: Int = 1

    /** `UndeadTeam`. */
    public const val UNDEAD: Int = 2

    /** Hostile to nobody and nobody's ally. What a unit spawned with no team is. */
    public const val NEUTRAL: Int = -1

    /** Whether [a] and [b] are on opposing sides. Neutral fights nobody, including itself. */
    public fun areEnemies(a: Int, b: Int): Boolean = a != NEUTRAL && b != NEUTRAL && a != b

    /** Whether [a] and [b] are on the same side. Neutral is nobody's ally. */
    public fun areAllies(a: Int, b: Int): Boolean = a != NEUTRAL && a == b
}

/**
 * Velocity, in world units **per tick**, with per-tick damping.
 *
 * Per tick and not per second, so a knockback is the same push after thirty single steps as it is
 * after one `step(30)`. The old game had no such component: knockback went straight into
 * `Body.applyLinearImpulse` and the answer depended on Box2D's sub-stepping, which is exactly the
 * "the solver decides authoritative movement" arrangement spec 3.4 forbids. `PhysicsWorld` is a
 * no-op in this engine today, so a projectile that needed to move had nothing to move it.
 */
public class Motion(
    /** World units per tick along x. */
    public var vx: Float = 0f,
    /** World units per tick along y. */
    public var vy: Float = 0f,
    /**
     * Fraction of velocity kept each tick.
     *
     * `1.0` is a projectile, which never slows; `0.85` is a unit absorbing a knockback over about
     * a fifth of a second. Zero is a legitimate value and means "this impulse lasts one tick".
     */
    public var damping: Float = UNIT_DAMPING,
) : Component<Motion> {

    override fun type(): ComponentType<Motion> = Motion

    /** Adds an impulse. Impulses accumulate within a tick; two hits push twice. */
    public fun push(x: Float, y: Float) {
        vx += x
        vy += y
    }

    override fun toString(): String = "Motion($vx, $vy)"

    public companion object : ComponentType<Motion>() {

        /** A knockback on a unit is gone in about a fifth of a second. */
        public const val UNIT_DAMPING: Float = 0.85f

        /** A projectile keeps its speed until it hits something or expires. */
        public const val PROJECTILE_DAMPING: Float = 1.0f

        /** Below this, velocity is zeroed so a unit does not creep for ever. */
        public const val REST_SPEED: Float = 0.0005f * MobaScale.WORLD
    }
}

/**
 * An arrow in flight: who fired it, what it does on contact, and how long it lives.
 *
 * Ported from `example/.../component/Projectile.kt`, whose `onHitEffects` were a list of
 * `OnHitEffect` records each carrying an `AssetReference<GameplayEffect>` and a
 * `Map<GameplayTag, Float>` - a map allocated per projectile and read on the hit path. The three
 * effects that list ever held were damage, stun and knockback, so they are three fields, and the
 * hit path allocates nothing.
 */
public class Projectile(
    /** Who fired it. Never hit; credited as the damage source. */
    public var owner: NetId = NetId.NONE,
    /** The firer's team at the moment of firing, so an owner who changes sides mid-flight is honest. */
    public var teamId: Int = Teams.NEUTRAL,
    /** Health removed on contact. Positive; applied as a negative magnitude. */
    public var damage: Float = 0f,
    /** How long the target is stunned on contact, in ticks. */
    public var stunTicks: Int = 0,
    /** How hard the target is pushed, in world units per tick. */
    public var knockback: Float = 0f,
    /** How close the centres have to be for a hit. */
    public var hitRadius: Float = DEFAULT_HIT_RADIUS,
    /** Ticks left before it expires. Counted down; zero despawns it. */
    public var lifeTicks: Int = DEFAULT_LIFE_TICKS,
) : Component<Projectile> {

    override fun type(): ComponentType<Projectile> = Projectile

    override fun toString(): String = "Projectile(owner=$owner, damage=$damage, life=$lifeTicks)"

    public companion object : ComponentType<Projectile>() {

        /** The old arrow's sensor box was 0.2 x 0.1; this is the radius that matches it. */
        public const val DEFAULT_HIT_RADIUS: Float = 0.15f * MobaScale.WORLD

        /**
         * Three seconds at 60Hz.
         *
         * The old arrow had no lifetime at all: `ProjectileSystem` despawned one only on contact,
         * so every miss stayed in the world for ever, drifting, still colliding. A missed shot
         * that leaks an entity is a leak an agent's `list_entities` finds hours later.
         */
        public const val DEFAULT_LIFE_TICKS: Int = 180
    }
}
