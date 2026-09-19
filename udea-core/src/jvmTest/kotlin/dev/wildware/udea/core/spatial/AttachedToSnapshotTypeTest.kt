package dev.wildware.udea.core.spatial

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.fixtures.ArrayFieldStore
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.replication.FieldMask
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.core.rng.DefaultRngService
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.SnapshotService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An [AttachedTo] survives a snapshot field for field, every field is on the wire, and each
 * field's name, mask bit and store column are the same index (issue #260).
 *
 * The last one is the `docs/contracts/replicator.md` invariant, checked for this component rather
 * than trusted: `desync_report` names a differing field by indexing `fieldNames` with a set bit of
 * a mask diff, so a misaligned component would not fail - it would name the wrong field. It is
 * worth checking here in particular because the sorted field order interleaves the mount's one
 * `Int` and one `NetId` with sixteen floats, so a kind list written in declaration order would
 * line up for the floats and lie about the two that matter.
 */
class AttachedToSnapshotTypeTest {

    private val ctx = testGameContext(seed = 11L) { rng = DefaultRngService(11L) }
    private val world: World = configureWorld { injectables { gameContext(ctx) } }
    private val netIds = NetIdIndex(capacity = 8, entityCapacity = 8)
    private val registry = ComponentRegistry(listOf(AttachedTo.snapshotType()))
    private val service = SnapshotService(registry, world, ctx, netIds)

    /** Every field set away from its default, and no two fields to the same value. */
    private fun everyFieldSet(): AttachedTo = AttachedTo(
        parent = NetId.of(index = 5, generation = 2),
        node = 7,
        nodeX = 1.5f,
        nodeY = 2.5f,
        nodeZ = 3.5f,
        nodeQx = 0.1f,
        nodeQy = 0.2f,
        nodeQz = 0.3f,
        nodeQw = 0.4f,
        nodeScaleX = 4.5f,
        nodeScaleY = 5.5f,
        nodeScaleZ = 6.5f,
        offsetX = 7.5f,
        offsetY = 8.5f,
        offsetZ = 9.5f,
        offsetRotationX = 10.5f,
        offsetRotationY = 11.5f,
        offsetRotationZ = 12.5f,
    )

    private fun fieldsOf(mount: AttachedTo): List<Any> = listOf(
        mount.parent, mount.node,
        mount.nodeX, mount.nodeY, mount.nodeZ,
        mount.nodeQx, mount.nodeQy, mount.nodeQz, mount.nodeQw,
        mount.nodeScaleX, mount.nodeScaleY, mount.nodeScaleZ,
        mount.offsetX, mount.offsetY, mount.offsetZ,
        mount.offsetRotationX, mount.offsetRotationY, mount.offsetRotationZ,
    )

    @Test
    fun `every field of a mount comes back from a snapshot`() {
        val entity = world.entity { it += everyFieldSet() }
        netIds.allocate(entity)
        val captured = service.capture()

        with(world) {
            val mount = entity[AttachedTo]
            mount.parent = NetId.NONE
            mount.node = ModelNode.NONE
            mount.nodeZ = -100f
            mount.offsetRotationZ = -100f
        }
        service.applyNow(captured)

        assertEquals(fieldsOf(everyFieldSet()), with(world) { fieldsOf(entity[AttachedTo]) })
    }

    @Test
    fun `every mount field is sent to clients, not only snapshotted`() {
        val replicator = registry.typeAt(0).replicator
        for (field in replicator.fieldNames.indices) {
            assertTrue(MaskOps.test(replicator.netMask, field), "${replicator.fieldNames[field]} would not be sent")
        }
    }

    /**
     * For each named field: change that field alone, capture before and after, and the diff must be
     * exactly the bit `fieldNames` puts that name at. A swapped pair of columns, or a name list out
     * of step with the bits, fails here on the field it concerns.
     */
    @Test
    fun `each field's name, mask bit and store column agree`() {
        val replicator = AttachedToReplicator
        val change: Map<String, (AttachedTo) -> Unit> = mapOf(
            "parent" to { it.parent = NetId.of(index = 9, generation = 1) },
            "node" to { it.node = 21 },
            "nodeX" to { it.nodeX = 101f },
            "nodeY" to { it.nodeY = 102f },
            "nodeZ" to { it.nodeZ = 103f },
            "nodeQx" to { it.nodeQx = 104f },
            "nodeQy" to { it.nodeQy = 105f },
            "nodeQz" to { it.nodeQz = 106f },
            "nodeQw" to { it.nodeQw = 107f },
            "nodeScaleX" to { it.nodeScaleX = 108f },
            "nodeScaleY" to { it.nodeScaleY = 109f },
            "nodeScaleZ" to { it.nodeScaleZ = 110f },
            "offsetX" to { it.offsetX = 111f },
            "offsetY" to { it.offsetY = 112f },
            "offsetZ" to { it.offsetZ = 113f },
            "offsetRotationX" to { it.offsetRotationX = 114f },
            "offsetRotationY" to { it.offsetRotationY = 115f },
            "offsetRotationZ" to { it.offsetRotationZ = 116f },
        )
        assertEquals(change.keys.sorted(), replicator.fieldNames.sorted(), "a field this test does not change")

        for ((name, mutate) in change) {
            val store = ArrayFieldStore(slotCount = 2, fieldCount = replicator.fieldNames.size)
            replicator.capture(everyFieldSet(), store, 0)
            replicator.capture(everyFieldSet().also(mutate), store, 1)
            val diff = replicator.diff(store, 0, 1)
            val bit = replicator.fieldNames.indexOf(name)
            assertEquals(MaskOps.single(bit), diff, "changing $name alone set ${bitsOf(diff, replicator.fieldNames)}")
        }
    }

    private fun bitsOf(mask: FieldMask, names: List<String>): List<String> =
        names.indices.filter { MaskOps.test(mask, it) }.map { names[it] }
}
