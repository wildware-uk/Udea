package dev.wildware.udea.core.physics

import com.github.quillraven.fleks.Family
import dev.wildware.udea.core.SimSystem

/**
 * Advances [PhysicsWorld] by exactly one tick, once per tick.
 *
 * Registered at [dev.wildware.udea.core.module.SimPhase.Physics], after `CharacterMover`'s
 * phase, because Box2D reacts to authoritative movement rather than deciding it (spec 3.4).
 *
 * ## What it replaces
 *
 * `Box2DSystem` was a Fleks `IteratingSystem` that, for every entity, wrote
 * `body.setTransform(transform.position, transform.rotation)` and then called
 * `box2DWorld.step(1 / 60F, 2, 2)` (`Box2DSystem.kt:77-80`) — from a world driven by the
 * render delta, so the hardcoded sixtieth and the actual elapsed time disagreed on every frame
 * that was not exactly 16.67ms. It also drew debug geometry inside the simulation tick and
 * read the `gameScreen` global for its camera.
 *
 * This system iterates nothing, writes no transform and draws nothing. Its whole body is one
 * call, and the fixed step comes from the loop rather than from a literal here: `GameLoop`
 * decides how many ticks a frame contains, and each of them is exactly one step.
 *
 * The dependency is a constructor parameter, not `ctx.physics` read at the call site, and not
 * `system<Box2DSystem>()` from a component hook — which is how bodies used to be reached.
 */
public class PhysicsStepSystem(private val physics: PhysicsWorld) : SimSystem() {

    override fun onTick() {
        physics.stepOneTick()
    }
}

/**
 * Applies queued [Teleport] commands and removes them.
 *
 * Registered at [dev.wildware.udea.core.module.SimPhase.PreSimulation], so a teleport is
 * visible to every later phase of the same tick — including [PhysicsStepSystem], which then
 * integrates from the new pose rather than from the old one.
 *
 * This is the *only* sanctioned writer of [PhysicsWorld.teleport]. A tick on which nobody
 * queued a `Teleport` performs zero transform writes, which is the behaviour the old
 * write-back-every-body-every-tick loop made impossible and which a spying `PhysicsWorld`
 * asserts.
 *
 * The command is removed after it is applied, so it fires once. A teleport left in place would
 * pin the body at its destination and look exactly like the solver refusing to move it.
 */
public class TeleportSystem(private val physics: PhysicsWorld) : SimSystem() {

    /**
     * Resolved once, at construction, from the world this system is being configured into.
     *
     * Not per tick: `world.family { }` is a lookup, and a lookup on a per-tick path with a
     * fresh definition object every time is the shape the charter calls out.
     */
    private val teleporting: Family = world.family { all(Teleport, PhysicsBody) }

    /** Reused across ticks so applying a teleport allocates nothing. */
    private val scratch = BodyPose()

    /** Teleports applied since construction. A health signal an agent can poll. */
    public var appliedCount: Long = 0L
        private set

    override fun onTick() {
        teleporting.forEach { entity ->
            val command = entity[Teleport]
            val body = entity[PhysicsBody]

            body.x = command.x
            body.y = command.y
            body.angle = command.angle
            if (body.handle.isValid) {
                physics.teleport(body.handle, scratch.set(command.x, command.y, command.angle))
            }

            entity.configure { it -= Teleport }
            appliedCount++
        }
    }
}
