package dev.wildware.udea.core.spatial

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.Ticks
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
 * An [Animator] survives a snapshot field for field, every field is on the wire, and each field's
 * name, mask bit and store column are the same index.
 *
 * The last one is the `docs/contracts/replicator.md` invariant, checked for this component rather
 * than trusted: `desync_report` names a differing field by indexing `fieldNames` with a set bit of
 * a mask diff, so a misaligned component would not fail - it would name the wrong field.
 */
class AnimatorSnapshotTypeTest {

    private val ctx = testGameContext(seed = 11L) { rng = DefaultRngService(11L) }
    private val world: World = configureWorld { injectables { gameContext(ctx) } }
    private val netIds = NetIdIndex(capacity = 8, entityCapacity = 8)
    private val registry = ComponentRegistry(listOf(Animator.snapshotType()))
    private val service = SnapshotService(registry, world, ctx, netIds)

    /** Every field set away from its default, and no two fields to the same value. */
    private fun everyFieldSet(): Animator = Animator(
        current = ClipPlayback(clip = 2, length = 70L, start = Tick(40), speed = 1.5f, loop = Loop.Once),
        previous = ClipPlayback(clip = 1, length = 43L, start = Tick(7), speed = 0.75f, loop = Loop.Once),
        fadeStart = Tick(55),
        fadeLength = 6L,
    )

    private fun fieldsOf(animator: Animator): List<Any> = listOf(
        animator.current.clip, animator.current.length, animator.current.loop, animator.current.speed,
        animator.current.start, animator.fadeLength, animator.fadeStart,
        animator.previous.clip, animator.previous.length, animator.previous.loop, animator.previous.speed,
        animator.previous.start,
    )

    @Test
    fun `every field of an animator comes back from a snapshot`() {
        val entity = world.entity { it += everyFieldSet() }
        netIds.allocate(entity)
        val captured = service.capture()

        with(world) {
            val animator = entity[Animator]
            animator.play(AnimationClip(index = 0, name = "Survey", length = Ticks(205L)), Tick(900))
        }
        service.applyNow(captured)

        assertEquals(fieldsOf(everyFieldSet()), with(world) { fieldsOf(entity[Animator]) })
    }

    @Test
    fun `every animator field is sent to clients, not only snapshotted`() {
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
        val replicator = AnimatorReplicator
        val change: Map<String, (Animator) -> Unit> = mapOf(
            "current.clip" to { it.current.clip = 9 },
            "current.length" to { it.current.length = 999L },
            "current.loop" to { it.current.loop = Loop.Repeat },
            "current.speed" to { it.current.speed = 3f },
            "current.start" to { it.current.start = Tick(4_000) },
            "fadeLength" to { it.fadeLength = 60L },
            "fadeStart" to { it.fadeStart = Tick(5_000) },
            "previous.clip" to { it.previous.clip = 8 },
            "previous.length" to { it.previous.length = 888L },
            "previous.loop" to { it.previous.loop = Loop.Repeat },
            "previous.speed" to { it.previous.speed = 4f },
            "previous.start" to { it.previous.start = Tick(6_000) },
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
