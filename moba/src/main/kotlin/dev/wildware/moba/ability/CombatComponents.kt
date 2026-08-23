package dev.wildware.moba.ability

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.annotations.Lifetime
import dev.wildware.udea.annotations.Net
import dev.wildware.udea.annotations.Replicated
import dev.wildware.udea.annotations.Sim
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
@Replicated
public class Combatant(
    /**
     * Which side. Two units with the same value never damage each other.
     *
     * `@Net` because a client colours a unit by its side before it can draw one, and because it
     * is what a restore needs to make a rebuilt entity a combatant again rather than a shell.
     *
     * `lifetime = OnCreate` (issue #114) because a unit's side is decided at spawn and never
     * changes: `UnitBlueprint.dress` and `RespawnSystem` are the only writers in this game and
     * both write it at construction. Before the generator read the argument, the declaration
     * was decorative and this field rode a delta on every tick capture-and-diff saw it move -
     * which, for a value that never moves, is a bug that costs nothing until a rewind writes
     * the whole world back and every unit's team looks like a change. It now rides the Create
     * and every full resend, and no Update, which `CombatantLifetimeTest` proves against the
     * generated `CombatantReplicator` rather than against a hand-written stand-in.
     */
    @Net(lifetime = Lifetime.OnCreate) public var teamId: Int = Teams.NEUTRAL,
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
@Replicated
public class Motion(
    /**
     * World units per tick along x.
     *
     * `@Sim`: a knockback lasts about a fifth of a second and a client sees it as the position
     * moving, which is already replicated. Snapshotted, because a rewind that dropped an
     * in-flight knockback would leave the unit standing where the push had already taken it.
     */
    @Sim public var vx: Float = 0f,
    /** World units per tick along y. `@Sim`, for the reason [vx] carries. */
    @Sim public var vy: Float = 0f,
    /**
     * Fraction of velocity kept each tick.
     *
     * `1.0` is a projectile, which never slows; `0.85` is a unit absorbing a knockback over about
     * a fifth of a second. Zero is a legitimate value and means "this impulse lasts one tick".
     */
    @Sim public var damping: Float = UNIT_DAMPING,
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
@Replicated
public class Projectile(
    /** Who fired it. Never hit; credited as the damage source. */
    @Net public var owner: NetId = NetId.NONE,
    /** The firer's team at the moment of firing, so an owner who changes sides mid-flight is honest. */
    @Net public var teamId: Int = Teams.NEUTRAL,
    /** Health removed on contact. Positive; applied as a negative magnitude. */
    @Sim public var damage: Float = 0f,
    /** How long the target is stunned on contact, in ticks. */
    @Sim public var stunTicks: Int = 0,
    /** How hard the target is pushed, in world units per tick. */
    @Sim public var knockback: Float = 0f,
    /** How close the centres have to be for a hit. */
    @Sim public var hitRadius: Float = DEFAULT_HIT_RADIUS,
    /** Ticks left before it expires. Counted down; zero despawns it. */
    @Sim public var lifeTicks: Int = DEFAULT_LIFE_TICKS,
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
