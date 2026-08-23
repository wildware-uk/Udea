package dev.wildware.udea.core.movement

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType

/**
 * What a controller - a player, a bot, or a replayed input buffer - is asking for this tick.
 *
 * Written at [dev.wildware.udea.core.module.SimPhase.Intent] and read at
 * [dev.wildware.udea.core.module.SimPhase.Movement]. Nothing else may write it, which is what
 * makes a tick's movement a pure function of (state, intent, geometry): the whole of the input
 * is in this component, so replaying a tick means restoring the state and re-supplying this.
 *
 * `move` is an axis in `-1..1` and **not** a velocity: a velocity here would let a client decide
 * how fast it goes, and the server would have nothing to check it against. [MoverConfig.maxSpeed]
 * turns the axis into a speed, and the config is the server's.
 */
public class MoveIntent(
    /** Horizontal axis, clamped to `-1..1` on read. Negative is left. */
    public var move: Float = 0f,
    /** Whether a jump is being held this tick. Consumed only while grounded. */
    public var jump: Boolean = false,
) : Component<MoveIntent> {

    /** [move], clamped. Clamped on read so a hostile client cannot ask for `move = 1000`. */
    public val axis: Float get() = if (move < -1f) -1f else if (move > 1f) 1f else move

    override fun type(): ComponentType<MoveIntent> = MoveIntent

    override fun toString(): String = "MoveIntent(move=$move, jump=$jump)"

    public companion object : ComponentType<MoveIntent>()
}

/**
 * The shape and the limits of one mover: data, never a global.
 *
 * `CharacterControllerSystem` read its speed from the `gameScreen` global at construction
 * (`common/.../CharacterControllerSystem.kt:16`), so two characters could not differ and no
 * snapshot could carry the difference. Here it is a component: a hero and a minion are two
 * values, a buff that raises `maxSpeed` is a field write, and a snapshot restores both.
 *
 * The capsule is **vertical**: the segment from `(x, y - halfHeight)` to `(x, y + halfHeight)`,
 * inflated by [radius]. That is the same shape as
 * [dev.wildware.udea.core.physics.Capsule], deliberately, so a game does not describe its
 * character twice.
 */
public class MoverConfig(
    /** Capsule radius in world units. Also bounds the substep length, so it must be positive. */
    public var radius: Float = 0.4f,
    /** Half the length of the capsule's core segment. `0` is a circle. */
    public var halfHeight: Float = 0.5f,
    /** Horizontal speed at full axis deflection, world units per second. */
    public var maxSpeed: Float = 6f,
    /** How fast horizontal velocity converges on the requested speed, units per second squared. */
    public var acceleration: Float = 40f,
    /** Downward acceleration, units per second squared. Positive. */
    public var gravity: Float = 24f,
    /** Upward velocity a grounded jump sets, units per second. */
    public var jumpSpeed: Float = 9f,
    /**
     * How far below its feet a mover that *was* grounded will reach to stay grounded.
     *
     * This is a step **down**, and only a step down: it stops a mover walking off the top of a
     * staircase and arcing over the next three treads. It does not climb: a mover meets a step up
     * as a wall, and slopes are climbed by the ordinary contact resolution as long as they are
     * within [minGroundNormalY]. Naming it `stepHeight` would promise the other half.
     */
    public var stepDownHeight: Float = 0.3f,
    /**
     * The smallest contact-normal `y` that counts as ground - `cos` of the steepest walkable
     * slope. `0.7071` is 45 degrees; `1` means only perfectly flat floors are ground.
     */
    public var minGroundNormalY: Float = 0.7071068f,
) : Component<MoverConfig> {

    override fun type(): ComponentType<MoverConfig> = MoverConfig

    override fun toString(): String =
        "MoverConfig(r=$radius, hh=$halfHeight, maxSpeed=$maxSpeed, gravity=$gravity)"

    public companion object : ComponentType<MoverConfig>()
}

/**
 * Everything [CharacterMover] carries from one tick to the next. **Snapshot state, in full.**
 *
 * Every field is a `Float` or a `Boolean` and there is no handle, no solver reference and no
 * object identity anywhere in it, which is the whole point: a snapshot restores this by writing
 * eight primitives, and the mover is then in exactly the state it was in - not a plausible one.
 * `PhysicsBody` cannot make that claim, because Box2D keeps warm-start impulses the component
 * never saw (spec 3.4).
 *
 * That also makes [sameAs] meaningful, and it is the comparison the parity and replay tests make:
 * field by field on the raw bits, so `-0f` and `0f` are the divergence they actually are.
 */
public class MoverState(
    /** Capsule centre x. */
    public var x: Float = 0f,
    /** Capsule centre y. */
    public var y: Float = 0f,
    /** World-space velocity x, units per second. */
    public var velocityX: Float = 0f,
    /** World-space velocity y, units per second. */
    public var velocityY: Float = 0f,
    /** Whether the last [CharacterMover.move] ended standing on something walkable. */
    public var grounded: Boolean = false,
    /** Contact normal x of the ground under it, or `0` when [grounded] is false. */
    public var groundNormalX: Float = 0f,
    /** Contact normal y of the ground under it, or `0` when [grounded] is false. */
    public var groundNormalY: Float = 0f,
) : Component<MoverState> {

    /** Copies every field of [other] into this one. Allocates nothing; used to save and restore. */
    public fun set(other: MoverState): MoverState {
        x = other.x
        y = other.y
        velocityX = other.velocityX
        velocityY = other.velocityY
        grounded = other.grounded
        groundNormalX = other.groundNormalX
        groundNormalY = other.groundNormalY
        return this
    }

    /**
     * Bit-exact field-by-field equality.
     *
     * `toRawBits` and not `==`: `0f == -0f` is true and `Float.NaN == Float.NaN` is false, and
     * both of those are exactly the divergences a determinism test exists to catch. A test that
     * compared with `==` would pass over a mover that had drifted to `-0f` on one machine.
     */
    public fun sameAs(other: MoverState): Boolean =
        x.toRawBits() == other.x.toRawBits() &&
            y.toRawBits() == other.y.toRawBits() &&
            velocityX.toRawBits() == other.velocityX.toRawBits() &&
            velocityY.toRawBits() == other.velocityY.toRawBits() &&
            grounded == other.grounded &&
            groundNormalX.toRawBits() == other.groundNormalX.toRawBits() &&
            groundNormalY.toRawBits() == other.groundNormalY.toRawBits()

    override fun type(): ComponentType<MoverState> = MoverState

    override fun toString(): String =
        "MoverState(($x, $y) v=($velocityX, $velocityY) grounded=$grounded " +
            "n=($groundNormalX, $groundNormalY))"

    public companion object : ComponentType<MoverState>()
}
