package dev.wildware.udea.core.physics

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex

/**
 * The one deterministic walk every [PhysicsWorld.rebuildFrom] uses.
 *
 * Shared rather than reimplemented per backend, because the *order* is the contract. Two
 * processes rebuilding the same world must create the same bodies in the same sequence with
 * the same fixtures in the same sequence; if each backend walked the world its own way, a
 * server and a client would diverge in exactly the place nobody would look, since physics is
 * supposedly non-authoritative.
 *
 * Two orderings, both explicit:
 *
 * - **bodies** in ascending [NetId] — `NetIdIndex.forEachLive` walks its dense index array in
 *   order, which is a property of the id space rather than of insertion order, so two worlds
 *   holding the same live set agree no matter what order they spawned things in;
 * - **fixtures** by [ShapeComponent.shapeOrder] — Fleks promises no order over component
 *   types, and Box2D's fixture list is creation-ordered, so the key has to be declared.
 *
 * Allocation-tolerant on purpose: this runs once per restore, not per tick, and is outside the
 * capture budget gate (spec 7).
 */
public class PhysicsRebuildPlan private constructor(
    /** Every body to create, in creation order. */
    public val bodies: List<PlannedBody>,
    /**
     * Every [PhysicsBody] in the world, including the ones no body is created for.
     *
     * The skipped ones are the whole reason this list exists: see [rebuild].
     */
    private val allComponents: List<PhysicsBody>,
) {

    public val size: Int get() = bodies.size

    /** [PhysicsBody] components in the world, whether or not a body is created for them. */
    public val componentCount: Int get() = allComponents.size

    /**
     * Invalidates every handle in the world, then creates this plan's bodies with [createBody]
     * and writes the new handles back onto their components.
     *
     * The second half is what a backend obviously has to do. The first half is the half a
     * backend forgets: `rebuildFrom` destroys **every** body, but a plan only covers entities
     * with a live [NetId], so an entity carrying a `PhysicsBody` and no `NetId` — a server-only
     * projectile, debris, exactly what spec 3.4 keeps a solver for — would keep the handle it
     * had before the rebuild. That handle is `isValid` and names nothing: `TeleportSystem` gates
     * on `isValid` and would call `teleport` with it, which throws `NoSuchBodyException` out of
     * `onTick` and kills the tick loop. On a backend that recycles body indices it does not even
     * throw — the stale handle aliases some *other* entity's new body, and one entity's
     * teleports silently move another's debris.
     *
     * Both halves live here, in the walk every backend already has to call, so a backend
     * inherits the invalidation rather than remembering it. Order is fixed: clear everything
     * first, then create, so an entity that is both in the plan and in the world ends up with
     * its new handle and not with `NONE`.
     *
     * Allocation-tolerant, like the rest of this class: once per restore, not per tick.
     */
    public fun rebuild(createBody: (BodyDef) -> BodyHandle) {
        for (component in allComponents) component.handle = BodyHandle.NONE
        for (planned in bodies) planned.component.handle = createBody(planned.def)
    }

    override fun toString(): String =
        "PhysicsRebuildPlan(${bodies.size} bodies, ${allComponents.size} components)"

    /** One entity's body, with its shapes already ordered. */
    public class PlannedBody(
        public val netId: NetId,
        public val entity: Entity,
        /** The live component. [PhysicsWorld.rebuildFrom] writes the new handle back onto it. */
        public val component: PhysicsBody,
        public val def: BodyDef,
    ) {
        override fun toString(): String = "PlannedBody($netId, $def)"
    }

    public companion object {

        /**
         * The plan for every entity in [world] that carries a [PhysicsBody] and has a live
         * [NetId].
         *
         * An entity with a `PhysicsBody` and no NetId is skipped rather than appended at the
         * end: it has no stable identity, so no two processes could agree on where in the
         * order it belongs, and a body whose creation position depends on local spawn order is
         * precisely the nondeterminism this class exists to remove.
         *
         * Skipped is not ignored. The plan also records every `PhysicsBody` in the world, so
         * [rebuild] can invalidate the handles of the entities it is not building bodies for —
         * without that they hold `isValid` handles to bodies `rebuildFrom` destroyed.
         */
        public fun of(world: World, netIds: NetIdIndex): PhysicsRebuildPlan {
            val planned = ArrayList<PlannedBody>(netIds.liveCount)
            netIds.forEachLive { netId, entity ->
                val body = with(world) { entity.getOrNull(PhysicsBody) }
                if (body != null) {
                    planned += PlannedBody(netId, entity, body, BodyDef(body, netId, shapesOf(world, entity)))
                }
            }
            // Every body-carrying entity, not only the planned ones: the skipped ones are the
            // ones whose handles [rebuild] has to invalidate.
            val all = ArrayList<PhysicsBody>(planned.size)
            world.family { all(PhysicsBody) }.forEach { entity -> all += entity[PhysicsBody] }
            return PhysicsRebuildPlan(planned, all)
        }

        /** [entity]'s shape components, sorted by [ShapeComponent.shapeOrder]. */
        public fun shapesOf(world: World, entity: Entity): List<ShapeComponent> {
            val shapes = ArrayList<ShapeComponent>(SHAPE_TYPES)
            with(world) {
                entity.getOrNull(Box)?.let(shapes::add)
                entity.getOrNull(Circle)?.let(shapes::add)
                entity.getOrNull(Capsule)?.let(shapes::add)
                entity.getOrNull(Chain)?.let(shapes::add)
            }
            // The reads above are already in shapeOrder; sorting anyway means adding a shape
            // type in the wrong place above cannot silently reorder anyone's fixtures.
            shapes.sortBy { it.shapeOrder }
            return shapes
        }

        /** How many [ShapeComponent] types exist, so the list above is sized without a regrow. */
        private const val SHAPE_TYPES: Int = 4
    }
}
