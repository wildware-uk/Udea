package dev.wildware.udea.physics2d

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.identity.NetIdVisitor
import dev.wildware.udea.core.physics.BodyKind
import dev.wildware.udea.core.physics.BodyPose
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.core.spatial.Transform3D

// Ground-plane physics for a 3D entity (issue #247).
//
// An entity carrying both a `PhysicsBody` and a `Transform3D` has two poses on the ground plane, and
// these three systems keep exactly one of them the truth:
//
// - a **dynamic** body is moved by the solver, and its pose is copied into `Transform3D.x/y/rotationZ`
//   after every step ([Transform3DFromBodySystem]);
// - a **kinematic** body follows `Transform3D`: before the step it is given the velocity that lands it
//   on `Transform3D` at the end of this tick, so it sweeps there and pushes what is in its way
//   ([BodyFollowsTransform3DSystem]);
// - a **static** body sits where `Transform3D` says, and is moved there when `Transform3D` moves.
//
// And every body with a `Transform3D` is *built* where `Transform3D` says ([Transform3DSeedSystem]),
// so a game spawning a 3D entity places it once, in `Transform3D`.
//
// `z`, `rotationX`, `rotationY` and the scale are never read or written here: they are the game's.
// An entity with a `PhysicsBody` and no `Transform3D` is not visited by any of the three.
//
// None of them keeps state between ticks, so a rewind restores nothing of theirs: after a restore
// `PhysicsWorld.rebuildFrom` builds every body from its restored `PhysicsBody`, and both poses were
// captured together at the tick boundary, when they agree.

/**
 * Builds every new body where its `Transform3D` says.
 *
 * Runs before [PhysicsReconcileSystem], which builds the body from `PhysicsBody`: a body that has
 * not been built yet (its handle is invalid) has `PhysicsBody.x/y/angle` overwritten from
 * `Transform3D.x/y/rotationZ` first. Only bodies not yet built: a rewind's `rebuildFrom` gives every
 * body a handle, so a restore is always rebuilt from the restored `PhysicsBody`, never from here.
 */
internal class Transform3DSeedSystem : SimSystem() {

    private val placed: Family = world.family { all(PhysicsBody, Transform3D) }

    override fun onTick() {
        placed.forEach { entity ->
            val body = entity[PhysicsBody]
            if (body.handle.isValid) return@forEach
            val transform = entity[Transform3D]
            body.x = transform.x
            body.y = transform.y
            body.angle = transform.rotationZ
        }
    }
}

/**
 * Drives every kinematic and static body with a `Transform3D` to it, before the solver steps.
 *
 * A kinematic body is given the velocity that carries it to `Transform3D` in exactly one step,
 * rather than being teleported there, so the solver sweeps it and pushes a dynamic body in its way
 * instead of materialising inside it. A static body cannot move under the solver, so one whose
 * `Transform3D` has moved is teleported, and its `PhysicsBody` pose updated to match.
 *
 * Visits entities in ascending `NetId`, as reconciliation does: a static teleport moves the body in
 * Box2D's broad phase, and the order of those moves is part of what decides contact order.
 */
internal class BodyFollowsTransform3DSystem(
    private val backend: SolverBackend,
    private val netIds: NetIdIndex,
) : SimSystem() {

    private val pose = BodyPose()

    private val visitor = object : NetIdVisitor {
        override fun visit(netId: NetId, entity: Entity) {
            follow(entity)
        }
    }

    override fun onTick() {
        netIds.forEachLive(visitor)
    }

    private fun follow(entity: Entity) {
        val body = with(world) { entity.getOrNull(PhysicsBody) } ?: return
        if (body.kind == BodyKind.Dynamic || !body.handle.isValid) return
        val transform = with(world) { entity.getOrNull(Transform3D) } ?: return
        when (body.kind) {
            BodyKind.Kinematic -> backend.moveKinematicTo(body.handle, transform.x, transform.y, transform.rotationZ)
            BodyKind.Static -> if (
                body.x.toRawBits() != transform.x.toRawBits() ||
                body.y.toRawBits() != transform.y.toRawBits() ||
                body.angle.toRawBits() != transform.rotationZ.toRawBits()
            ) {
                body.x = transform.x
                body.y = transform.y
                body.angle = transform.rotationZ
                backend.teleport(body.handle, pose.set(transform.x, transform.y, transform.rotationZ))
            }
            BodyKind.Dynamic -> Unit
        }
    }
}

/**
 * Copies every dynamic body's solved pose into its `Transform3D`, after the solver steps.
 *
 * From `PhysicsBody`, which the step has just written, rather than from the solver: the two are the
 * same floats, and reading the component keeps this system free of anything a rewind rebuilds.
 */
internal class Transform3DFromBodySystem : SimSystem() {

    private val placed: Family = world.family { all(PhysicsBody, Transform3D) }

    override fun onTick() {
        placed.forEach { entity ->
            val body = entity[PhysicsBody]
            if (body.kind != BodyKind.Dynamic) return@forEach
            val transform = entity[Transform3D]
            transform.x = body.x
            transform.y = body.y
            transform.rotationZ = body.angle
        }
    }
}
