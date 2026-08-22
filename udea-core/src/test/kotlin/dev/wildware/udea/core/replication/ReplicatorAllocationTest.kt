package dev.wildware.udea.core.replication

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.alloc.AllocationProbe
import dev.wildware.udea.core.fixtures.ArrayBitWriter
import dev.wildware.udea.core.fixtures.ArrayFieldStore
import dev.wildware.udea.core.fixtures.Transform
import dev.wildware.udea.core.fixtures.TransformReplicator
import dev.wildware.udea.core.fixtures.Vec2
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `Replicator.capture` says "Must not allocate", and until now that was prose.
 *
 * capture-diff-write runs once per replicated component per entity per tick at 60Hz — the
 * hottest path in the engine, and the one spec 7 names: "if capture allocates, three features
 * degrade at once". The neighbouring subsystems already gate this (`SimBarrierAllocationTest`,
 * `RngAllocationTest`, `udea-net`'s `BitStreamAllocationTest`); replication, which needs it
 * most, did not.
 *
 * What this pins is the **contract reference**, not the production store: `ArrayFieldStore` is
 * a fixture by its own KDoc, and the pooled snapshot-ring `FieldStore` that the spec's real
 * gate ("capture of 1000 entities <1ms and allocation-free") measures does not exist yet. So
 * this catches the failure that is live today — a `Replicator` implementation that boxes a
 * value class, routes a field through `setObject`, or grows a buffer — and leaves the ring's
 * own budget to the ring.
 */
class ReplicatorAllocationTest {

    @Test
    fun `capture, diff and write allocate nothing in steady state`() {
        assumeTrue(AllocationProbe.isSupported, "HotSpot thread allocation counters required")

        // Everything the loop touches is built once, outside the measurement: the component,
        // the store, the writer. Anything allocated inside is the Replicator's doing.
        val component = Transform(Vec2(1f, 2f), 3f, Tick(4))
        val store = ArrayFieldStore(slotCount = 2, fieldCount = TransformReplicator.FIELD_COUNT)
        val writer = ArrayBitWriter(initialWords = 64)
        val replicator = TransformReplicator

        val load = {
            var index = 0
            while (index < ROUNDS) {
                // Move the component so the diff is non-empty and `write` really writes:
                // measuring the empty-mask early return would measure nothing.
                component.position.x = index.toFloat()
                replicator.capture(component, store, SLOT_CAPTURED)
                val delta = MaskOps.and(
                    replicator.diff(store, SLOT_CAPTURED, SLOT_BASELINE),
                    replicator.netMask,
                )
                writer.reset()
                replicator.write(store, SLOT_CAPTURED, delta, writer)
                store.copySlot(SLOT_CAPTURED, SLOT_BASELINE)
                index++
            }
        }

        val bytes = AllocationProbe.bytesAllocated(block = load)

        assertEquals(
            0L,
            bytes,
            "capture/diff/write allocated $bytes bytes over $ROUNDS rounds; at 60Hz across " +
                "every replicated component that is a GC pause with a metronome behind it",
        )
    }

    @Test
    fun `apply allocates nothing when restoring in place`() {
        assumeTrue(AllocationProbe.isSupported, "HotSpot thread allocation counters required")

        // Restoring 1000 entities must not allocate 1000 components: `apply` mutates the
        // caller's component, and the Vec2 identity survives.
        val source = Transform(Vec2(7f, 8f), 9f, Tick(10))
        val destination = Transform()
        val store = ArrayFieldStore(slotCount = 2, fieldCount = TransformReplicator.FIELD_COUNT)
        val replicator = TransformReplicator
        replicator.capture(source, store, SLOT_CAPTURED)

        val load = {
            var index = 0
            while (index < ROUNDS) {
                replicator.apply(store, SLOT_CAPTURED, destination, replicator.allMask)
                index++
            }
        }

        val bytes = AllocationProbe.bytesAllocated(block = load)

        assertEquals(
            0L,
            bytes,
            "apply allocated $bytes bytes over $ROUNDS restores; a snapshot restore of the " +
                "whole world would multiply that by the entity count",
        )
    }

    private companion object {
        const val SLOT_BASELINE: Int = 0
        const val SLOT_CAPTURED: Int = 1
        const val ROUNDS: Int = 10_000
    }
}
