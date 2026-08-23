package dev.wildware.udea.core.movement

import com.github.quillraven.fleks.Family
import dev.wildware.udea.core.SimSystem

/**
 * The scene's walls, swapped between ticks.
 *
 * A one-field holder rather than a field on `GameContext` (standards section 8 asks for a
 * justification before a new context field, and this has none: only the movement systems read
 * it) and rather than a `var` inside [CharacterMoverSystem], because a scene load has to be able
 * to replace it from outside the tick. It is instance state on a service the composition root
 * builds, so two worlds in one JVM have two of them.
 *
 * ## Written between ticks, never during one
 *
 * Replace it from a `SimBarrier` action, which is exactly what scene loading already does. A
 * write during a tick would give two movers in the same tick two different worlds, and the
 * second one's result would depend on system order rather than on the game.
 */
public class SceneCollision(
    /** The active geometry. [StaticCollision.EMPTY] until a scene loads one. */
    public var geometry: StaticCollision = StaticCollision.EMPTY,
) {
    override fun toString(): String = "SceneCollision($geometry)"
}

/**
 * Runs [CharacterMover] over every entity that has a state, a config and an intent.
 *
 * Registered at [dev.wildware.udea.core.module.SimPhase.Movement] by `CoreModule`, which is
 * before [dev.wildware.udea.core.module.SimPhase.Physics] - the ordering spec 3.4 asks for, and
 * the reason `PhysicsStepSystem` reacts to authoritative movement rather than deciding it.
 *
 * ## What it replaces
 *
 * `CharacterControllerSystem` called `body.applyLinearImpulse(...)` and let Box2D decide the
 * result. This writes [MoverState] directly, and [MoverState] is snapshot state in full - so the
 * tick is replayable and the same code runs on the server and on a predicting client.
 *
 * ## One mover, reused
 *
 * The system holds a single [CharacterMover] and steps every entity through it, because a mover
 * is workspace - a candidate buffer and some accumulators - and not per-entity state. Per-entity
 * state is the component. That is also why the system is not thread-safe and does not pretend to
 * be: one mover, one thread, one tick.
 *
 * The step is [dev.wildware.udea.core.SimClock.dt] - the fixed step - and never
 * `IntervalSystem.deltaTime`, which is a frame duration and would make movement depend on how
 * long the last frame took.
 */
public class CharacterMoverSystem(
    /** Where the walls come from. Read once per entity, so a mid-tick swap is visible to some. */
    private val collision: SceneCollision,
) : SimSystem() {

    /**
     * Resolved once at construction. `world.family { }` on a per-tick path is the lookup-shaped
     * smell the charter names, and it would allocate a family definition every tick.
     */
    private val movers: Family = world.family { all(MoverState, MoverConfig, MoveIntent) }

    /** The shared workspace. Not per-entity state; see the class KDoc. */
    private val mover: CharacterMover = CharacterMover()

    /** How many entities the last tick moved. A signal an agent can read through `diag`. */
    public var lastMovedCount: Int = 0
        private set

    override fun onTick() {
        val geometry = collision.geometry
        val dt = ctx.clock.dt
        var moved = 0
        movers.forEach { entity ->
            mover.move(entity[MoverState], entity[MoveIntent], entity[MoverConfig], geometry, dt)
            moved++
        }
        lastMovedCount = moved
    }
}
