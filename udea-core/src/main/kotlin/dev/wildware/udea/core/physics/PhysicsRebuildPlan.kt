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
) {

    public val size: Int get() = bodies.size

    override fun toString(): String = "PhysicsRebuildPlan(${bodies.size} bodies)"

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
         */
        public fun of(world: World, netIds: NetIdIndex): PhysicsRebuildPlan {
            val planned = ArrayList<PlannedBody>(netIds.liveCount)
            netIds.forEachLive { netId, entity ->
                val body = with(world) { entity.getOrNull(PhysicsBody) }
                if (body != null) {
                    planned += PlannedBody(netId, entity, body, BodyDef(body, netId, shapesOf(world, entity)))
                }
            }
            return PhysicsRebuildPlan(planned)
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
