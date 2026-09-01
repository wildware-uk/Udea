package dev.wildware.udea.core.physics

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex

/**
 * A world of [bodyCount] entities whose components depend only on their [NetId] index.
 *
 * [reverseSpawnOrder] changes the order the entities are *created* in while leaving the
 * id-to-component mapping identical, which is what makes "spawn order must not leak into the
 * rebuild order" a checkable claim rather than a restatement of the loop.
 *
 * Lifted out of `PhysicsRebuildTest` by issue #182, which split the 2ms rebuild budget into
 * [PhysicsRebuildBudgetTest] so that a stopwatch stops being read inside `./gradlew build`. Two
 * classes need the same world, and building it twice is how the thing being measured stops being
 * the thing being asserted about.
 */
internal class PhysicsRebuildFixture(val bodyCount: Int, reverseSpawnOrder: Boolean = false) {
    val netIds = NetIdIndex(capacity = 1024, entityCapacity = 1024)
    val world: World = configureWorld(1024) {}
    val ids: List<NetId> = (0 until bodyCount).map { NetId.of(it, 0) }

    init {
        val order = if (reverseSpawnOrder) (bodyCount - 1) downTo 0 else 0 until bodyCount
        for (index in order) {
            val entity = world.entity {
                it += PhysicsBody(
                    kind = if (index % 3 == 0) BodyKind.Static else BodyKind.Dynamic,
                    x = index * 1.5f,
                    y = index * -0.25f,
                    angle = index * 0.01f,
                    linearX = index.toFloat(),
                    linearY = -index.toFloat(),
                    angularVelocity = index * 0.5f,
                    awake = index % 5 != 0,
                )
                // Added in an order that is deliberately not shapeOrder, so a rebuild that
                // trusted component-add order would produce the wrong fixture sequence.
                if (index % 2 == 0) it += Circle(radius = 1f)
                it += Box(halfWidth = 1f, halfHeight = 2f)
                if (index % 4 == 0) it += Capsule()
            }
            netIds.bind(entity, ids[index])
        }
    }

    fun bodyOf(id: NetId): PhysicsBody =
        with(world) { checkNotNull(netIds.resolveOrNull(id)) { "$id is not live" }[PhysicsBody] }
}
