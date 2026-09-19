package dev.wildware.udea.core.spatial

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.fixtures.ArrayFieldStore
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
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
 * A [Transform3D] survives a snapshot field for field, every field is on the wire (issue #246),
 * and each field's name, mask bit and store column are the same index.
 *
 * The last one is the `docs/contracts/replicator.md` invariant, checked for this component rather
 * than trusted: `desync_report` names a differing field by indexing `fieldNames` with a set bit of
 * a mask diff, so a misaligned component would not fail - it would name the wrong field.
 */
class Transform3DSnapshotTypeTest {

    private val ctx = testGameContext(seed = 13L) { rng = DefaultRngService(13L) }
    private val world: World = configureWorld { injectables { gameContext(ctx) } }
    private val netIds = NetIdIndex(capacity = 8, entityCapacity = 8)
    private val registry = ComponentRegistry(listOf(Transform3D.snapshotType()))
    private val service = SnapshotService(registry, world, ctx, netIds)

    /** Every field set away from its default, and no two fields to the same value. */
    private fun everyFieldSet(): Transform3D = Transform3D(
        x = 1.5f, y = -2.25f, z = 3.125f,
        rotationX = 0.25f, rotationY = -0.5f, rotationZ = 2.75f,
        scaleX = 1.75f, scaleY = 0.625f, scaleZ = 2.5f,
    )

    private fun fieldsOf(t: Transform3D): List<Float> =
        listOf(t.x, t.y, t.z, t.rotationX, t.rotationY, t.rotationZ, t.scaleX, t.scaleY, t.scaleZ)

    @Test
    fun `every field of a transform comes back from a snapshot`() {
        val entity = world.entity { it += everyFieldSet() }
        netIds.allocate(entity)
        val captured = service.capture()

        with(world) {
            val t = entity[Transform3D]
            t.x = 0f; t.y = 0f; t.z = 0f
            t.rotationX = 0f; t.rotationY = 0f; t.rotationZ = 0f
            t.scaleX = 9f; t.scaleY = 9f; t.scaleZ = 9f
        }
        service.applyNow(captured)

        assertEquals(fieldsOf(everyFieldSet()), with(world) { fieldsOf(entity[Transform3D]) })
    }

    /**
     * Position and heading are what issue #246 asked for. The rest are on the wire as well, and the
     * reason is a client's `applyOnto`, which writes a component through `allMask`: a field that
     * never reaches the client is written from a column the client never filled, so a `@Sim`
     * `scaleX` arrives as `0` and every model a client draws is invisible. `Transform3DReplicationTest`
     * in `udea-net` shows that end to end. A delta carries only the fields that changed, so a scale
     * that never changes costs its bits once, in the entity's create record.
     */
    @Test
    fun `every transform field is sent to clients, not only snapshotted`() {
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
        val replicator = Transform3DReplicator
        val change: Map<String, (Transform3D) -> Unit> = mapOf(
            "x" to { it.x = 40f },
            "y" to { it.y = 41f },
            "z" to { it.z = 42f },
            "rotationX" to { it.rotationX = 1.1f },
            "rotationY" to { it.rotationY = 1.2f },
            "rotationZ" to { it.rotationZ = 1.3f },
            "scaleX" to { it.scaleX = 5f },
            "scaleY" to { it.scaleY = 6f },
            "scaleZ" to { it.scaleZ = 7f },
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
