package dev.wildware.udea.core.physics

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.core.rng.DefaultRngService
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.SnapshotCoverage
import dev.wildware.udea.core.snapshot.SnapshotService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The physics components round-trip through a snapshot, every field, and none of them is sent.
 *
 * The field kinds in [PhysicsSnapshotTypes] are written out by hand, and a kind typed wrong at the
 * right length is caught by nothing but a round trip - a `Bool` read back as a `Float` column, or
 * an enum stored as a float. So every field of every type is set to a value that is not its
 * default, captured, overwritten, restored and read back.
 */
class PhysicsSnapshotTypesTest {

    private val ctx = testGameContext(seed = 7L) { rng = DefaultRngService(7L) }
    private val world: World = configureWorld { injectables { gameContext(ctx) } }
    private val netIds = NetIdIndex(capacity = 16, entityCapacity = 16)
    private val registry = ComponentRegistry(PhysicsSnapshotTypes.all())
    private val service = SnapshotService(registry, world, ctx, netIds)

    @Test
    fun `every field of every physics component comes back from a snapshot`() {
        val entity = world.entity {
            it += PhysicsBody(
                kind = BodyKind.Kinematic,
                x = 1.25f,
                y = -2.5f,
                angle = 0.75f,
                linearX = 3.5f,
                linearY = -4.25f,
                angularVelocity = 5.125f,
                awake = false,
                isSensor = true,
            )
            it += Box(halfWidth = 6.5f, halfHeight = 7.25f)
            it += Circle(radius = 8.5f)
            it += Capsule(radius = 9.75f, halfHeight = 10.5f)
            it += Teleport(x = 11.25f, y = 12.5f, angle = 13.75f)
        }
        netIds.allocate(entity)
        val captured = service.capture()

        with(world) {
            val body = entity[PhysicsBody]
            body.kind = BodyKind.Static
            body.x = 0f
            body.y = 0f
            body.angle = 0f
            body.linearX = 0f
            body.linearY = 0f
            body.angularVelocity = 0f
            body.awake = true
            body.isSensor = false
            entity[Box].halfWidth = 0f
            entity[Box].halfHeight = 0f
            entity[Circle].radius = 0f
            entity[Capsule].radius = 0f
            entity[Capsule].halfHeight = 0f
            entity[Teleport].x = 0f
            entity[Teleport].y = 0f
            entity[Teleport].angle = 0f
        }
        service.applyNow(captured)

        with(world) {
            val body = entity[PhysicsBody]
            assertEquals(
                listOf<Any>(BodyKind.Kinematic, 1.25f, -2.5f, 0.75f, 3.5f, -4.25f, 5.125f, false, true),
                listOf<Any>(
                    body.kind, body.x, body.y, body.angle, body.linearX, body.linearY,
                    body.angularVelocity, body.awake, body.isSensor,
                ),
            )
            assertEquals(listOf(6.5f, 7.25f), listOf(entity[Box].halfWidth, entity[Box].halfHeight))
            assertEquals(8.5f, entity[Circle].radius)
            assertEquals(listOf(9.75f, 10.5f), listOf(entity[Capsule].radius, entity[Capsule].halfHeight))
            assertEquals(
                listOf(11.25f, 12.5f, 13.75f),
                listOf(entity[Teleport].x, entity[Teleport].y, entity[Teleport].angle),
            )
        }
    }

    @Test
    fun `no physics field is in a network mask, and every one is in the snapshot mask`() {
        for (index in 0 until registry.size) {
            val type = registry.typeAt(index)
            val replicator = type.replicator
            assertTrue(MaskOps.isEmpty(replicator.netMask), "${type.componentClass.simpleName} would be sent")
            for (field in replicator.fieldNames.indices) {
                assertTrue(
                    MaskOps.test(replicator.allMask, field),
                    "${type.componentClass.simpleName}.${replicator.fieldNames[field]} would not be captured",
                )
            }
        }
        assertEquals(
            listOf("Box", "Capsule", "Circle", "PhysicsBody", "Teleport"),
            (0 until registry.size).map { registry.typeAt(it).componentClass.simpleName },
        )
    }

    @Test
    fun `Chain is the one physics component a snapshot leaves out`() {
        val entity = world.entity {
            it += PhysicsBody(kind = BodyKind.Static)
            it += Chain(floatArrayOf(0f, 0f, 1f, 0f))
            it += Box()
            it += Circle()
            it += Capsule()
            it += Teleport()
        }
        netIds.allocate(entity)

        assertEquals(
            listOf("dev.wildware.udea.core.physics.Chain"),
            SnapshotCoverage.uncovered(registry, world, netIds),
        )
    }
}
