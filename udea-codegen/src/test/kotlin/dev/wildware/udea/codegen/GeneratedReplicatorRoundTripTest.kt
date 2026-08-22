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
import dev.wildware.udea.core.replication.FieldMask
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.core.replication.NoSuchFieldIndexException
import dev.wildware.udea.core.replication.Replicator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Drives every generated `Replicator` through `capture` → `diff` → `write` → `read` → `apply`.
 *
 * These are the assertions `udea-core`'s `ReplicatorContractTest` makes against the hand-written
 * `TransformReplicator`, restated against generated code: the hand-written replicator is the
 * executable specification, so anything generated has to behave the same way or the
 * specification is not one. The `FieldStore` and `BitWriter`/`BitReader` come from `udea-core`'s
 * published test fixtures for the same reason — the reference implementation, not a second one.
 */
class GeneratedReplicatorRoundTripTest {

    // --- the shape of the generated declarations -------------------------------------------

    @Test
    fun `bit indices follow property name order, not declaration order`() {
        // Health declares maximum, current, invulnerable, lastDamageTick — deliberately not
        // alphabetical. FieldOrder sorts by name, so this is what the wire format is.
        assertContentEquals(
            listOf("current", "invulnerable", "lastDamageTick", "maximum"),
            HealthReplicator.fieldNames,
        )
        assertContentEquals(
            listOf("groundedTicks", "jumpsRemaining", "speed", "stance"),
            MovementReplicator.fieldNames,
        )

        // And the literal mapping, pinned once and directly. Every other assertion in this file
        // routes through the FIELD_ constants, so it survives any *consistent* relabelling of
        // them — a generator that emitted fieldNames name-sorted while numbering the constants
        // some other way would pass all of them. The frozen contract
        // (docs/contracts/replicator.md, "Index alignment") calls fieldNames[i] == mask bit i ==
        // FieldStore index i load-bearing, because desync_report(tick) names fieldNames[i] for
        // each differing bit. So the numbers themselves are asserted.
        assertEquals(0, HealthReplicator.FIELD_CURRENT)
        assertEquals(1, HealthReplicator.FIELD_INVULNERABLE)
        assertEquals(2, HealthReplicator.FIELD_LAST_DAMAGE_TICK)
        assertEquals(3, HealthReplicator.FIELD_MAXIMUM)
    }

    @Test
    fun `netMask holds the @Net fields and allMask holds every field`() {
        // Name-mediated, not constant-mediated: MaskOps.of(FIELD_CURRENT, ...) on the expected
        // side is the same expression the generated initializer uses, so it would hold for any
        // numbering of those constants. Going through fieldNames asserts what the mask is
        // actually *for* — which fields reach a client — and it is the assertion udea-core's
        // ReplicatorContractTest makes about the hand-written TransformReplicator.
        assertEquals(
            listOf("current", "invulnerable", "maximum"),
            buildList {
                MaskOps.forEachSetBit(HealthReplicator.netMask) { add(HealthReplicator.fieldNames[it]) }
            },
            "netMask bits must name the @Net fields through fieldNames (spec 3.1)",
        )
        assertEquals(MaskOps.lowest(4), HealthReplicator.allMask)
        assertTrue(
            MaskOps.containsAll(HealthReplicator.allMask, HealthReplicator.netMask),
            "netMask must always be a subset of allMask (spec 3.1)",
        )
    }

    @Test
    fun `a @Sim-only component has an empty netMask and a full allMask`() {
        assertEquals(MaskOps.EMPTY, AiBlackboardReplicator.netMask)
        assertEquals(MaskOps.lowest(4), AiBlackboardReplicator.allMask)
        assertEquals(4, AiBlackboardReplicator.fieldNames.size)
    }

    @Test
    fun `every generated replicator has a distinct non-negative placeholder typeId`() {
        val ids = listOf(
            HealthReplicator.typeId,
            MovementReplicator.typeId,
            AiBlackboardReplicator.typeId,
        )
        assertTrue(ids.all { it.raw >= 0 }, "typeIds must be non-negative, were $ids")
        assertEquals(ids.size, ids.toSet().size, "placeholder typeIds collided: $ids")
    }

    // --- capture and diff -------------------------------------------------------------------

    @Test
    fun `diff reports exactly the fields that changed, across both masks`() {
        val store = ArrayFieldStore(slotCount = 2, fieldCount = HealthReplicator.FIELD_COUNT)
        HealthReplicator.capture(Health(maximum = 100f, current = 100f), store, 0)
        HealthReplicator.capture(
            Health(maximum = 100f, current = 40f, lastDamageTick = 12L),
            store,
            1,
        )

        assertEquals(
            MaskOps.of(HealthReplicator.FIELD_CURRENT, HealthReplicator.FIELD_LAST_DAMAGE_TICK),
            HealthReplicator.diff(store, 0, 1),
            "diff spans allMask: the @Sim field must show up too",
        )
    }

    @Test
    fun `diff of a slot against itself is empty`() {
        val store = ArrayFieldStore(2, MovementReplicator.FIELD_COUNT)
        MovementReplicator.capture(Movement(stance = Stance.Sprinting, speed = 7.5f), store, 0)
        store.copySlot(0, 1)

        assertEquals(MaskOps.EMPTY, MovementReplicator.diff(store, 0, 1))
    }

    @Test
    fun `generated diff agrees with the store's type-agnostic comparison`() {
        // desync_report walks FieldStore.fieldEquals; a Replicator uses typed getters. Spec 3.1:
        // both must agree, or a desync report names a field that did not actually differ.
        val store = ArrayFieldStore(2, MovementReplicator.FIELD_COUNT)
        MovementReplicator.capture(Movement(), store, 0)
        MovementReplicator.capture(
            Movement(stance = Stance.Crouching, speed = 3f, jumpsRemaining = 0, groundedTicks = 9L),
            store,
            1,
        )

        val fromReplicator = MovementReplicator.diff(store, 0, 1)
        var fromStore = MaskOps.EMPTY
        for (field in 0 until MovementReplicator.FIELD_COUNT) {
            if (!store.fieldEquals(0, 1, field)) fromStore = MaskOps.set(fromStore, field)
        }
        assertEquals(fromStore, fromReplicator)
    }

    @Test
    fun `generated diff agrees with the store on NaN and on negative zero`() {
        // The two values where IEEE 754 and "same stored bits" disagree, and the only ones that
        // can tell a `!=`-based diff apart from a toRawBits one. FieldStore.fieldEquals compares
        // stored representations, so NaN equals itself and -0.0f differs from 0.0f; `!=` says
        // the opposite on both counts. A diff written with `!=` would resend a NaN field every
        // tick for ever and would silently drop a sign flip through zero.
        val store = ArrayFieldStore(2, HealthReplicator.FIELD_COUNT)
        HealthReplicator.capture(Health(maximum = Float.NaN, current = 0f), store, 0)
        HealthReplicator.capture(Health(maximum = Float.NaN, current = -0f), store, 1)

        var fromStore = MaskOps.EMPTY
        for (field in 0 until HealthReplicator.FIELD_COUNT) {
            if (!store.fieldEquals(0, 1, field)) fromStore = MaskOps.set(fromStore, field)
        }

        assertEquals(
            MaskOps.single(HealthReplicator.FIELD_CURRENT),
            fromStore,
            "the store must see -0.0f as a change and NaN as no change",
        )
        assertEquals(
            fromStore,
            HealthReplicator.diff(store, 0, 1),
            "a generated diff must compare floats bit-identically, as FieldStore.fieldEquals does",
        )
    }

    // --- the trust boundary -------------------------------------------------------------------

    @Test
    fun `read rejects an enum ordinal that names no constant, naming component, field and value`() {
        // read() is the only entry point that puts bytes it did not produce into a FieldStore.
        // A corrupt datagram — or ordinary version skew between two builds whose enum has a
        // different number of constants — arrives here as an unconstrained 32-bit int.
        val payload = ArrayBitWriter()
        MaskOps.writeTo(
            MaskOps.single(MovementReplicator.FIELD_STANCE),
            payload,
            MovementReplicator.FIELD_COUNT,
        )
        payload.writeInt(99)

        val store = ArrayFieldStore(1, MovementReplicator.FIELD_COUNT)
        store.setInt(0, MovementReplicator.FIELD_STANCE, Stance.Crouching.ordinal)

        val failure = assertFailsWith<IllegalArgumentException> {
            MovementReplicator.read(payload.toReader(), store, 0)
        }
        val message = failure.message.orEmpty()
        assertTrue("Movement.stance" in message, "must name the component and the field: $message")
        assertTrue("99" in message, "must name the offending ordinal: $message")
        assertTrue("Stance" in message, "must name the enum: $message")
        assertTrue("0 until 3" in message, "must give the valid range: $message")

        // And the point of validating in read() rather than in apply(): the bad value never
        // entered the store, so this slot — which a snapshot ring would also hold — is intact.
        assertEquals(
            Stance.Crouching.ordinal,
            store.getInt(0, MovementReplicator.FIELD_STANCE),
            "the rejected ordinal must not have reached the FieldStore",
        )
    }

    @Test
    fun `read rejects every out-of-range ordinal and accepts every in-range one`() {
        for (ordinal in listOf(-1, Stance.entries.size, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val payload = ArrayBitWriter()
            MaskOps.writeTo(
                MaskOps.single(MovementReplicator.FIELD_STANCE),
                payload,
                MovementReplicator.FIELD_COUNT,
            )
            payload.writeInt(ordinal)
            assertFailsWith<IllegalArgumentException>("ordinal $ordinal was accepted") {
                MovementReplicator.read(payload.toReader(), ArrayFieldStore(1, MovementReplicator.FIELD_COUNT), 0)
            }
        }

        // The boundaries on the good side, so the check is not simply rejecting everything.
        for (stance in Stance.entries) {
            val payload = ArrayBitWriter()
            MaskOps.writeTo(
                MaskOps.single(MovementReplicator.FIELD_STANCE),
                payload,
                MovementReplicator.FIELD_COUNT,
            )
            payload.writeInt(stance.ordinal)

            val store = ArrayFieldStore(1, MovementReplicator.FIELD_COUNT)
            MovementReplicator.read(payload.toReader(), store, 0)
            val restored = Movement()
            MovementReplicator.apply(store, 0, restored, MaskOps.single(MovementReplicator.FIELD_STANCE))
            assertEquals(stance, restored.stance)
        }
    }

    // --- the whole pipeline -----------------------------------------------------------------

    @Test
    fun `a full write restores every field of Health`() {
        assertFullRoundTrip(
            HealthReplicator,
            Health(maximum = 250f, current = 17.5f, invulnerable = true, lastDamageTick = 4242L),
            { Health() },
        )
    }

    @Test
    fun `a full write restores every field of Movement, enum included`() {
        assertFullRoundTrip(
            MovementReplicator,
            Movement(
                stance = Stance.Sprinting,
                speed = -12.25f,
                jumpsRemaining = 3,
                groundedTicks = Long.MAX_VALUE,
            ),
            { Movement() },
        )
    }

    @Test
    fun `a full write restores every field of the @Sim-only component`() {
        assertFullRoundTrip(
            AiBlackboardReplicator,
            AiBlackboard(patrolIndex = 7, aggression = 0.5f, alerted = true, lastSeenTick = -3L),
            { AiBlackboard() },
        )
    }

    @Test
    fun `a delta write carries only the changed @Net fields and leaves the rest alone`() {
        val baseline = Health(maximum = 100f, current = 100f, lastDamageTick = 1L)
        val changed = Health(maximum = 100f, current = 60f, invulnerable = true, lastDamageTick = 9L)

        val sender = ArrayFieldStore(2, HealthReplicator.FIELD_COUNT)
        HealthReplicator.capture(baseline, sender, 0)
        HealthReplicator.capture(changed, sender, 1)
        val delta = MaskOps.and(HealthReplicator.diff(sender, 0, 1), HealthReplicator.netMask)

        val writer = ArrayBitWriter()
        HealthReplicator.write(sender, 1, delta, writer)

        // The receiver already holds the baseline; that is what makes a delta a delta.
        val receiver = ArrayFieldStore(1, HealthReplicator.FIELD_COUNT)
        HealthReplicator.capture(baseline, receiver, 0)
        val readMask = HealthReplicator.read(writer.toReader(), receiver, 0)
        assertEquals(delta, readMask)

        val target = Health(maximum = 100f, current = 100f, lastDamageTick = 1L)
        HealthReplicator.apply(receiver, 0, target, readMask)

        assertEquals(60f, target.current)
        assertEquals(true, target.invulnerable)
        assertEquals(100f, target.maximum)
        assertEquals(
            1L,
            target.lastDamageTick,
            "@Sim fields are outside netMask and must never cross the wire",
        )
    }

    @Test
    fun `an empty mask writes zero bits`() {
        val store = ArrayFieldStore(1, AiBlackboardReplicator.FIELD_COUNT)
        AiBlackboardReplicator.capture(AiBlackboard(), store, 0)
        val writer = ArrayBitWriter()

        // A @Sim-only component intersected with netMask is always empty, which is precisely
        // the "costs nothing" case the contract requires.
        AiBlackboardReplicator.write(store, 0, AiBlackboardReplicator.netMask, writer)

        assertEquals(0L, writer.bitPosition)
    }

    @Test
    fun `a delta mask costs its own width in mask bits and nothing more`() {
        val store = ArrayFieldStore(1, MovementReplicator.FIELD_COUNT)
        MovementReplicator.capture(Movement(jumpsRemaining = 1), store, 0)
        val writer = ArrayBitWriter()

        MovementReplicator.write(
            store,
            0,
            MaskOps.single(MovementReplicator.FIELD_JUMPS_REMAINING),
            writer,
        )

        // 4 mask bits (FIELD_COUNT), then one 32-bit Int. Not a 64-bit mask.
        assertEquals(4L + 32L, writer.bitPosition)
    }

    // --- the agent's field access -----------------------------------------------------------

    @Test
    fun `getField returns each field by index`() {
        val movement = Movement(stance = Stance.Crouching, speed = 2f, jumpsRemaining = 1, groundedTicks = 5L)

        assertEquals(5L, MovementReplicator.getField(movement, MovementReplicator.FIELD_GROUNDED_TICKS))
        assertEquals(1, MovementReplicator.getField(movement, MovementReplicator.FIELD_JUMPS_REMAINING))
        assertEquals(2f, MovementReplicator.getField(movement, MovementReplicator.FIELD_SPEED))
        assertEquals(Stance.Crouching, MovementReplicator.getField(movement, MovementReplicator.FIELD_STANCE))
    }

    @Test
    fun `setField writes each field by index`() {
        val movement = Movement()

        MovementReplicator.setField(movement, MovementReplicator.FIELD_STANCE, Stance.Sprinting)
        MovementReplicator.setField(movement, MovementReplicator.FIELD_SPEED, 9f)
        MovementReplicator.setField(movement, MovementReplicator.FIELD_JUMPS_REMAINING, 0)
        MovementReplicator.setField(movement, MovementReplicator.FIELD_GROUNDED_TICKS, 11L)

        assertEquals(Stance.Sprinting, movement.stance)
        assertEquals(9f, movement.speed)
        assertEquals(0, movement.jumpsRemaining)
        assertEquals(11L, movement.groundedTicks)
    }

    @Test
    fun `setField rejects a value of the wrong type by name`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            MovementReplicator.setField(Movement(), MovementReplicator.FIELD_SPEED, "fast")
        }
        assertTrue("Movement.speed" in failure.message.orEmpty(), failure.message.orEmpty())
        assertTrue("Float" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `setField rejects null for a non-null field`() {
        assertFailsWith<IllegalArgumentException> {
            MovementReplicator.setField(Movement(), MovementReplicator.FIELD_SPEED, null)
        }
    }

    @Test
    fun `an out-of-range field index is a typed exception naming the type and the range`() {
        val failure = assertFailsWith<NoSuchFieldIndexException> {
            HealthReplicator.getField(Health(), HealthReplicator.FIELD_COUNT)
        }
        assertEquals("Health", failure.typeName)
        assertEquals(HealthReplicator.FIELD_COUNT, failure.fieldIndex)
        assertEquals(HealthReplicator.FIELD_COUNT, failure.fieldCount)

        assertFailsWith<NoSuchFieldIndexException> {
            HealthReplicator.setField(Health(), -1, 0f)
        }
    }

    // --- helpers ----------------------------------------------------------------------------

    /**
     * `capture` → `write(allMask)` → `read` → `apply` onto a default-constructed component, then
     * asserts every field of the restored component equals the source, field by field through
     * `getField` so the assertion does not depend on the component having `equals`.
     */
    private fun <T> assertFullRoundTrip(replicator: Replicator<T>, source: T, fresh: () -> T) {
        val store = ArrayFieldStore(1, replicator.fieldNames.size)
        replicator.capture(source, store, 0)

        val writer = ArrayBitWriter()
        replicator.write(store, 0, replicator.allMask, writer)

        val receiver = ArrayFieldStore(1, replicator.fieldNames.size)
        val readMask: FieldMask = replicator.read(writer.toReader(), receiver, 0)
        assertEquals(replicator.allMask, readMask)

        val restored = fresh()
        replicator.apply(receiver, 0, restored, readMask)

        for (index in replicator.fieldNames.indices) {
            assertEquals(
                replicator.getField(source, index),
                replicator.getField(restored, index),
                "field ${replicator.fieldNames[index]} did not survive the round trip",
            )
        }
    }
}
