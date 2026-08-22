package dev.wildware.udea.codegen

import dev.wildware.udea.codegen.fixtures.AiBlackboard
import dev.wildware.udea.codegen.fixtures.AiBlackboardReplicator
import dev.wildware.udea.codegen.fixtures.Health
import dev.wildware.udea.codegen.fixtures.HealthReplicator
import dev.wildware.udea.codegen.fixtures.Movement
import dev.wildware.udea.codegen.fixtures.MovementReplicator
import dev.wildware.udea.codegen.fixtures.Stance
import dev.wildware.udea.core.fixtures.ArrayBitWriter
import dev.wildware.udea.core.fixtures.ArrayFieldStore
import dev.wildware.udea.core.replication.FieldStore
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.core.replication.Replicator
import dev.wildware.udea.net.bits.BitBufferReader
import dev.wildware.udea.net.bits.BitBufferWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Phase 0 load-bearing claim, end to end and across all three wave-2 modules.
 *
 * Every other round-trip test in the repo runs generated code against `udea-core`'s
 * `ArrayBitWriter` test fixture. That proves the generator is self-consistent; it does not
 * prove the engine is. Three modules have to agree here:
 *
 * - `udea-codegen` emits a `Replicator<T>` against the interface frozen in `udea-core`;
 * - `udea-core` owns `FieldMask`, `MaskOps` mask framing and the `BitWriter`/`BitReader`
 *   declarations;
 * - `udea-net` supplies the only *production* `BitWriter`/`BitReader` — over a
 *   caller-supplied, MTU-sized, never-growing `ByteArray`.
 *
 * So this drives `capture` → `diff` → `write` → `read` → `apply` through
 * [BitBufferWriter]/[BitBufferReader] and real bytes, and additionally pins the two
 * `BitWriter` implementations bit-for-bit against each other. If the generator and the bit
 * stream ever disagree about the frozen shapes, this is where it shows up.
 */
class GeneratedReplicatorNetRoundTripTest {

    /** An MTU-ish datagram. Deliberately fixed and never grown, as the transport's would be. */
    private val datagram = ByteArray(1200)

    // --- the full pipeline over real bytes ---------------------------------------------------

    @Test
    fun `a component with @Net and @Sim fields survives capture-diff-write-read-apply over real bytes`() {
        val source = Health(
            maximum = 250f,
            current = 17.5f,
            invulnerable = true,
            lastDamageTick = 4242L,
        )

        val sender = ArrayFieldStore(1, HealthReplicator.FIELD_COUNT)
        HealthReplicator.capture(source, sender, 0)

        val writer = BitBufferWriter(datagram)
        HealthReplicator.write(sender, 0, HealthReplicator.allMask, writer)

        assertTrue(writer.byteLength > 0, "a full write of four fields must produce bytes")
        assertEquals(
            4L + 32L + 1L + 64L + 32L,
            writer.bitsWritten,
            "4 mask bits, then current/maximum as floats, invulnerable as one bit and " +
                "lastDamageTick as a long — the mask costs one bit per field, not a byte",
        )

        // Fresh reader over exactly the bytes the transport would have sent.
        val reader = BitBufferReader(datagram, 0, writer.byteLength)
        val receiver = ArrayFieldStore(1, HealthReplicator.FIELD_COUNT)
        val readMask = HealthReplicator.read(reader, receiver, 0)

        assertEquals(HealthReplicator.allMask, readMask)
        assertEquals(writer.bitsWritten, reader.bitsRead, "the reader must consume exactly the payload")

        val restored = Health()
        HealthReplicator.apply(receiver, 0, restored, readMask)

        assertFieldIdentical(HealthReplicator, source, restored)
        // Named explicitly, because "field-identical" is only meaningful if the fields were
        // not already equal by default construction.
        assertEquals(250f, restored.maximum)
        assertEquals(17.5f, restored.current)
        assertEquals(true, restored.invulnerable)
        assertEquals(4242L, restored.lastDamageTick)
    }

    @Test
    fun `every generated replicator round-trips field-identically through the real bit stream`() {
        assertNetRoundTrip(
            HealthReplicator,
            Health(maximum = 1f, current = -0.5f, invulnerable = true, lastDamageTick = Long.MIN_VALUE),
        ) { Health() }

        assertNetRoundTrip(
            MovementReplicator,
            Movement(
                stance = Stance.Sprinting,
                speed = -12.25f,
                jumpsRemaining = 3,
                groundedTicks = Long.MAX_VALUE,
            ),
        ) { Movement() }

        assertNetRoundTrip(
            AiBlackboardReplicator,
            AiBlackboard(patrolIndex = 7, aggression = 0.5f, alerted = true, lastSeenTick = -3L),
        ) { AiBlackboard() }
    }

    @Test
    fun `a delta over the wire carries only changed @Net fields and never a @Sim field`() {
        val baseline = Movement(stance = Stance.Standing, speed = 0f, jumpsRemaining = 2, groundedTicks = 1L)
        val current = Movement(stance = Stance.Sprinting, speed = 6f, jumpsRemaining = 2, groundedTicks = 99L)

        val sender = ArrayFieldStore(2, MovementReplicator.FIELD_COUNT)
        MovementReplicator.capture(baseline, sender, 0)
        MovementReplicator.capture(current, sender, 1)

        val changed = MovementReplicator.diff(sender, 0, 1)
        assertTrue(
            MaskOps.test(changed, MovementReplicator.FIELD_GROUNDED_TICKS),
            "the @Sim field did change, so the test is actually exercising the stripping",
        )

        val delta = MaskOps.and(changed, MovementReplicator.netMask)
        val writer = BitBufferWriter(datagram)
        MovementReplicator.write(sender, 1, delta, writer)

        // 4 mask bits + speed (32) + stance (32). jumpsRemaining did not change; groundedTicks
        // is @Sim and was stripped before the write.
        assertEquals(4L + 32L + 32L, writer.bitsWritten)

        // The receiver holds the baseline; that is what makes a delta a delta.
        val receiver = ArrayFieldStore(1, MovementReplicator.FIELD_COUNT)
        MovementReplicator.capture(baseline, receiver, 0)
        val readMask = MovementReplicator.read(
            BitBufferReader(datagram, 0, writer.byteLength),
            receiver,
            0,
        )
        assertEquals(delta, readMask)

        val target = Movement(stance = Stance.Standing, speed = 0f, jumpsRemaining = 2, groundedTicks = 1L)
        MovementReplicator.apply(receiver, 0, target, readMask)

        assertEquals(Stance.Sprinting, target.stance)
        assertEquals(6f, target.speed)
        assertEquals(2, target.jumpsRemaining)
        assertEquals(1L, target.groundedTicks, "a @Sim field must never cross the wire")
        assertNotEquals(current.groundedTicks, target.groundedTicks)
    }

    @Test
    fun `a @Sim-only component intersected with netMask writes literally no bytes`() {
        val store = ArrayFieldStore(1, AiBlackboardReplicator.FIELD_COUNT)
        AiBlackboardReplicator.capture(AiBlackboard(patrolIndex = 3), store, 0)

        val writer = BitBufferWriter(datagram)
        AiBlackboardReplicator.write(store, 0, AiBlackboardReplicator.netMask, writer)

        assertEquals(0L, writer.bitsWritten)
        assertEquals(0, writer.byteLength, "a component that must not reach a client costs no bytes")
    }

    // --- several components in one datagram --------------------------------------------------

    @Test
    fun `three components pack into one datagram and unpack in order`() {
        // The framing layer does not exist yet, so this stands in for it: back-to-back
        // payloads with no padding between them, which is only readable if every replicator
        // consumes exactly the bits it wrote.
        val health = Health(maximum = 80f, current = 12f, invulnerable = false, lastDamageTick = 7L)
        val movement = Movement(stance = Stance.Crouching, speed = 1.5f, jumpsRemaining = 0, groundedTicks = 5L)
        val blackboard = AiBlackboard(patrolIndex = 2, aggression = 0.75f, alerted = true, lastSeenTick = 9L)

        val healthStore = ArrayFieldStore(1, HealthReplicator.FIELD_COUNT)
        val movementStore = ArrayFieldStore(1, MovementReplicator.FIELD_COUNT)
        val blackboardStore = ArrayFieldStore(1, AiBlackboardReplicator.FIELD_COUNT)
        HealthReplicator.capture(health, healthStore, 0)
        MovementReplicator.capture(movement, movementStore, 0)
        AiBlackboardReplicator.capture(blackboard, blackboardStore, 0)

        val writer = BitBufferWriter(datagram)
        HealthReplicator.write(healthStore, 0, HealthReplicator.allMask, writer)
        MovementReplicator.write(movementStore, 0, MovementReplicator.allMask, writer)
        AiBlackboardReplicator.write(blackboardStore, 0, AiBlackboardReplicator.allMask, writer)

        val reader = BitBufferReader(datagram, 0, writer.byteLength)
        val outHealth = ArrayFieldStore(1, HealthReplicator.FIELD_COUNT)
        val outMovement = ArrayFieldStore(1, MovementReplicator.FIELD_COUNT)
        val outBlackboard = ArrayFieldStore(1, AiBlackboardReplicator.FIELD_COUNT)

        val restoredHealth = Health()
        val restoredMovement = Movement()
        val restoredBlackboard = AiBlackboard()
        HealthReplicator.apply(outHealth, 0, restoredHealth, HealthReplicator.read(reader, outHealth, 0))
        MovementReplicator.apply(outMovement, 0, restoredMovement, MovementReplicator.read(reader, outMovement, 0))
        AiBlackboardReplicator.apply(
            outBlackboard,
            0,
            restoredBlackboard,
            AiBlackboardReplicator.read(reader, outBlackboard, 0),
        )

        assertEquals(writer.bitsWritten, reader.bitsRead, "the three payloads must tile exactly")
        assertFieldIdentical(HealthReplicator, health, restoredHealth)
        assertFieldIdentical(MovementReplicator, movement, restoredMovement)
        assertFieldIdentical(AiBlackboardReplicator, blackboard, restoredBlackboard)
    }

    @Test
    fun `two hundred entities restore field-identically from one datagram`() {
        val sources = List(200) { i ->
            Movement(
                stance = Stance.entries[i % Stance.entries.size],
                speed = i * 0.25f,
                jumpsRemaining = i % 4,
                groundedTicks = i.toLong() * 3L,
            )
        }
        val store = ArrayFieldStore(sources.size, MovementReplicator.FIELD_COUNT)
        sources.forEachIndexed { slot, movement -> MovementReplicator.capture(movement, store, slot) }

        // 200 * (4 + 64 + 32 + 32 + 32) bits = 4100 bytes, so this needs its own buffer.
        val buffer = ByteArray(8 * 1024)
        val writer = BitBufferWriter(buffer)
        for (slot in sources.indices) {
            MovementReplicator.write(store, slot, MovementReplicator.allMask, writer)
        }

        val reader = BitBufferReader(buffer, 0, writer.byteLength)
        val received = ArrayFieldStore(sources.size, MovementReplicator.FIELD_COUNT)
        for (slot in sources.indices) {
            val mask = MovementReplicator.read(reader, received, slot)
            assertEquals(MovementReplicator.allMask, mask, "entity $slot")
            val restored = Movement()
            MovementReplicator.apply(received, slot, restored, mask)
            assertFieldIdentical(MovementReplicator, sources[slot], restored, "entity $slot: ")
        }
        assertEquals(writer.bitsWritten, reader.bitsRead)

        // And the round trip must be lossless at the store level too, since the snapshot ring
        // is the same structure as the replication baseline (spec 3.1).
        for (slot in sources.indices) {
            assertEquals(
                MaskOps.EMPTY,
                MovementReplicator.diff(storeCopyOf(store, received, slot), 0, 1),
                "captured and received slots must be indistinguishable for entity $slot",
            )
        }
    }

    // --- the two BitWriter implementations agree bit for bit ---------------------------------

    @Test
    fun `udea-net and udea-core's fixture writer produce identical bits for generated payloads`() {
        // udea-core declares BitWriter and ships ArrayBitWriter as the executable reference;
        // udea-net ships the production implementation. Generated code is written against the
        // interface, so if the two ever diverge — bit order within a byte, mask framing, float
        // encoding — every golden test in the repo would still pass while the wire broke.
        val movement = Movement(stance = Stance.Sprinting, speed = -0.125f, jumpsRemaining = 1, groundedTicks = -7L)
        val store = ArrayFieldStore(1, MovementReplicator.FIELD_COUNT)
        MovementReplicator.capture(movement, store, 0)

        val netWriter = BitBufferWriter(datagram)
        val coreWriter = ArrayBitWriter()
        MovementReplicator.write(store, 0, MovementReplicator.allMask, netWriter)
        MovementReplicator.write(store, 0, MovementReplicator.allMask, coreWriter)

        assertEquals(coreWriter.bitPosition, netWriter.bitsWritten, "the two writers disagree on length")
        assertTrue(netWriter.bitsWritten > 0)

        val netReader = BitBufferReader(datagram, 0, netWriter.byteLength)
        val coreReader = coreWriter.toReader()
        for (bit in 0 until netWriter.bitsWritten) {
            assertEquals(
                coreReader.readBits(1),
                netReader.readBits(1),
                "bit $bit differs between udea-core's reference writer and udea-net's",
            )
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun <T> assertNetRoundTrip(replicator: Replicator<T>, source: T, fresh: () -> T) {
        val store = ArrayFieldStore(1, replicator.fieldNames.size)
        replicator.capture(source, store, 0)

        val writer = BitBufferWriter(datagram)
        replicator.write(store, 0, replicator.allMask, writer)

        val received = ArrayFieldStore(1, replicator.fieldNames.size)
        val mask = replicator.read(BitBufferReader(datagram, 0, writer.byteLength), received, 0)
        assertEquals(replicator.allMask, mask)

        val restored = fresh()
        replicator.apply(received, 0, restored, mask)
        assertFieldIdentical(replicator, source, restored)
    }

    /**
     * Field-by-field through `getField`, so the assertion does not depend on the component
     * having `equals` — the components under test are plain classes and deliberately do not.
     */
    private fun <T> assertFieldIdentical(
        replicator: Replicator<T>,
        source: T,
        restored: T,
        prefix: String = "",
    ) {
        for (index in replicator.fieldNames.indices) {
            assertEquals(
                replicator.getField(source, index),
                replicator.getField(restored, index),
                "$prefix${replicator.fieldNames[index]} did not survive the round trip",
            )
        }
    }

    /** A two-slot store holding `captured[slot]` at 0 and `received[slot]` at 1. */
    private fun storeCopyOf(captured: FieldStore, received: FieldStore, slot: Int): ArrayFieldStore {
        val pair = ArrayFieldStore(2, MovementReplicator.FIELD_COUNT)
        pair.setLong(0, MovementReplicator.FIELD_GROUNDED_TICKS, captured.getLong(slot, MovementReplicator.FIELD_GROUNDED_TICKS))
        pair.setInt(0, MovementReplicator.FIELD_JUMPS_REMAINING, captured.getInt(slot, MovementReplicator.FIELD_JUMPS_REMAINING))
        pair.setFloat(0, MovementReplicator.FIELD_SPEED, captured.getFloat(slot, MovementReplicator.FIELD_SPEED))
        pair.setInt(0, MovementReplicator.FIELD_STANCE, captured.getInt(slot, MovementReplicator.FIELD_STANCE))
        pair.setLong(1, MovementReplicator.FIELD_GROUNDED_TICKS, received.getLong(slot, MovementReplicator.FIELD_GROUNDED_TICKS))
        pair.setInt(1, MovementReplicator.FIELD_JUMPS_REMAINING, received.getInt(slot, MovementReplicator.FIELD_JUMPS_REMAINING))
        pair.setFloat(1, MovementReplicator.FIELD_SPEED, received.getFloat(slot, MovementReplicator.FIELD_SPEED))
        pair.setInt(1, MovementReplicator.FIELD_STANCE, received.getInt(slot, MovementReplicator.FIELD_STANCE))
        return pair
    }
}
