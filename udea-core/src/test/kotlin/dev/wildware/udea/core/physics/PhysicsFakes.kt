package dev.wildware.udea.core.physics

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContextBuilder
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.UdeaModule

/**
 * A [PhysicsWorld] that writes down what it was asked to do, in order.
 *
 * The ordering claims in this area are the interesting ones — "the teleport lands before the
 * step, in the same tick", "the rebuild happens after every field is applied" — and a counter
 * cannot express an ordering. So this records an event *sequence*.
 *
 * It delegates the actual bookkeeping to [NoOpPhysicsWorld] rather than reimplementing it,
 * except for [rebuildFrom], which is written out so creations route through this class's own
 * [createBody] and are therefore recorded.
 */
internal class SpyPhysicsWorld(
    private val delegate: NoOpPhysicsWorld = NoOpPhysicsWorld(),
) : PhysicsWorld by delegate {

    /** Every call, in the order it arrived. `step`, `teleport <handle>`, `create <netId>`, ... */
    val events = ArrayList<String>()

    var stepCount: Int = 0
        private set

    var teleportCount: Int = 0
        private set

    var rebuildCount: Int = 0
        private set

    override fun stepOneTick() {
        stepCount++
        events += "step"
        delegate.stepOneTick()
    }

    override fun createBody(def: BodyDef): BodyHandle {
        val handle = delegate.createBody(def)
        events += "create ${def.owner} shapes=${def.shapes.map { it.shapeOrder }}"
        return handle
    }

    override fun teleport(handle: BodyHandle, pose: BodyPose) {
        teleportCount++
        events += "teleport $handle -> (${pose.x}, ${pose.y})"
        delegate.teleport(handle, pose)
    }

    override fun destroyAllBodies(): Int {
        val destroyed = delegate.destroyAllBodies()
        events += "destroyAll $destroyed"
        return destroyed
    }

    override fun rebuildFrom(world: World, netIds: NetIdIndex) {
        rebuildCount++
        events += "rebuild"
        destroyAllBodies()
        for (planned in PhysicsRebuildPlan.of(world, netIds).bodies) {
            planned.component.handle = createBody(planned.def)
        }
    }

    /** Events since the last [clearEvents], so a test can measure one tick of a longer run. */
    fun clearEvents() {
        events.clear()
    }
}

/** A module that replaces the kernel's default physics with [physics] and adds no systems. */
internal class PhysicsOverrideModule(private val physics: PhysicsWorld) : UdeaModule {
    override fun context(builder: GameContextBuilder) {
        builder.physics = physics
    }
}
