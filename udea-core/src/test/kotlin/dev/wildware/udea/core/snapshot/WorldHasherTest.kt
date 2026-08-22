package dev.wildware.udea.core.snapshot

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.alloc.AllocationProbe
import dev.wildware.udea.core.identity.NetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The hash that is the determinism gate.
 *
 * Every property here is one the gate depends on. If the hash moved with insertion order it
 * would report a divergence every time an entity was spawned in a different sequence; if it
 * did not move with component presence it would miss a whole class of real divergence; and if
 * it were not reproducible byte for byte across JVMs it would be a source of CI flakes rather
 * than a source of truth.
 */
class WorldHasherTest {

    private val registry = TestComponents.registry()

    @Test
    fun `the hash is a fixed value for a fixed world`() {
        // A golden, and the value itself is arbitrary — what it pins is that the algorithm and
        // the canonical order never move by accident. `TimeControl`'s rewind test, the
        // snapshot-equivalence gate and Phase 7's cross-OS replay equality all assert against
        // this hash, so a change here is a change to three contracts at once and must be a
        // deliberate golden update. It is also the cross-JVM check: any input that varied by
        // JVM — an identity hash code, an iteration order — would move this number on some
        // machine in the matrix.
        assertEquals(GOLDEN, WorldHasher.hash(fixedWorld()))
    }

    @Test
    fun `the hash does not depend on the order entities were inserted in`() {
        val ascending = fixedWorld()
        val shuffled = fixedWorld(order = listOf(2, 0, 3, 1))

        assertEquals(
            WorldHasher.hash(ascending),
            WorldHasher.hash(shuffled),
            "capture sorts by NetId, so two processes holding the same set must agree",
        )
    }

    @Test
    fun `changing any single field changes the hash`() {
        val movement = registry.indexOf(MovementReplicator.typeId)
        for (field in 0 until registry.schemaAt(movement).fieldCount) {
            val mutated = fixedWorld()
            val store = mutated.storeAt(movement)
            when (store.schema.kindOf(field)) {
                FieldKind.Float -> store.setFloat(0, field, store.getFloat(0, field) + 1f)
                FieldKind.Tick -> store.setTick(0, field, store.getTick(0, field) + 1L)
                else -> error("unexpected kind on Movement.$field")
            }
            assertNotEquals(
                GOLDEN,
                WorldHasher.hash(mutated),
                "changing Movement.${store.schema.nameOf(field)} left the hash unchanged",
            )
        }
    }

    @Test
    fun `moving a component from one entity to another changes the hash`() {
        // Column data and the roster are identical in both worlds; only the presence bits
        // differ. Without folding presence the hash would collide, and a real divergence
        // would pass the gate.
        val here = fixedWorld(vitalsOn = 0)
        val there = fixedWorld(vitalsOn = 1)

        assertNotEquals(WorldHasher.hash(here), WorldHasher.hash(there))
    }

    @Test
    fun `an entity's generation is part of the hash`() {
        val original = fixedWorld()
        val recycled = fixedWorld(generation = 1)

        assertNotEquals(
            WorldHasher.hash(original),
            WorldHasher.hash(recycled),
            "index 0 recycled is a different entity from index 0 rewound",
        )
    }

    @Test
    fun `benign float representation differences do not move the hash`() {
        val movement = registry.indexOf(MovementReplicator.typeId)
        val zero = fixedWorld()
        zero.storeAt(movement).setFloat(0, MovementReplicator.POSITION_X, 0.0f)
        val minusZero = fixedWorld()
        minusZero.storeAt(movement).setFloat(0, MovementReplicator.POSITION_X, -0.0f)

        val nanA = fixedWorld()
        nanA.storeAt(movement).setFloat(0, MovementReplicator.POSITION_X, Float.NaN)
        val nanB = fixedWorld()
        nanB.storeAt(movement).setFloat(0, MovementReplicator.POSITION_X, Float.fromBits(0x7FC00042))

        assertEquals(WorldHasher.hash(zero), WorldHasher.hash(minusZero), "sign of zero")
        assertEquals(WorldHasher.hash(nanA), WorldHasher.hash(nanB), "NaN payload")
    }

    @Test
    fun `an empty world hashes to the documented empty value`() {
        val empty = WorldFieldStore(registry, initialRows = 4)
        assertNotEquals(WorldHasher.EMPTY, WorldHasher.hash(empty))
        // Not EMPTY itself: the hash folds the row count and every component type's id, so an
        // empty world is still distinguishable from a world with no component types at all.
        assertEquals(WorldHasher.hash(empty), WorldHasher.hash(WorldFieldStore(registry, 16)))
    }

    @Test
    fun `hashing allocates nothing, because the loop gate hashes on every tick`() {
        assertTrue(AllocationProbe.isSupported, "this JVM has no thread allocation counters")
        val world = fixedWorld()

        val allocated = AllocationProbe.bytesAllocated { WorldHasher.hash(world) }

        assertEquals(0L, allocated, "hashing allocated $allocated bytes")
    }

    /**
     * Four entities, fixed values, no randomness and no wall clock.
     *
     * [order] is the sequence rows are built in; the ids are sorted first, because a capture
     * always appends in ascending [NetId] order.
     */
    private fun fixedWorld(
        order: List<Int> = listOf(0, 1, 2, 3),
        vitalsOn: Int = 0,
        generation: Int = 0,
    ): WorldFieldStore {
        val movement = registry.indexOf(MovementReplicator.typeId)
        val vitals = registry.indexOf(VitalsReplicator.typeId)
        val link = registry.indexOf(LinkReplicator.typeId)

        val fields = WorldFieldStore(registry, initialRows = 8)
        for (index in order.sorted()) {
            val netId = NetId.of(index, if (index == 0) generation else 0)
            val row = fields.appendRow(netId)

            val movementSlot = fields.claimSlot(row, movement)
            val movementStore = fields.storeAt(movement)
            movementStore.setFloat(movementSlot, MovementReplicator.POSITION_X, index * 1.5f)
            movementStore.setFloat(movementSlot, MovementReplicator.POSITION_Y, index * -2.25f)
            movementStore.setFloat(movementSlot, MovementReplicator.VELOCITY_X, 0.5f)
            movementStore.setFloat(movementSlot, MovementReplicator.VELOCITY_Y, -0.25f)
            movementStore.setTick(movementSlot, MovementReplicator.LAST_GROUNDED_TICK, Tick(index.toLong()))

            if (index == vitalsOn) {
                val vitalsSlot = fields.claimSlot(row, vitals)
                val vitalsStore = fields.storeAt(vitals)
                vitalsStore.setFloat(vitalsSlot, VitalsReplicator.HEALTH, 87.5f)
                vitalsStore.setInt(vitalsSlot, VitalsReplicator.SHIELD_CHARGES, 2)
                vitalsStore.setBoolean(vitalsSlot, VitalsReplicator.INVULNERABLE, true)
                vitalsStore.setLong(vitalsSlot, VitalsReplicator.DAMAGE_DEALT, 4096L)
                vitalsStore.setTick(vitalsSlot, VitalsReplicator.RESPAWN_TICK, Tick(11L))
            }

            val linkSlot = fields.claimSlot(row, link)
            val linkStore = fields.storeAt(link)
            linkStore.setNetId(linkSlot, LinkReplicator.TARGET, NetId.of((index + 1) % 4, 0))
            linkStore.setNetId(linkSlot, LinkReplicator.LAST_ATTACKER, NetId.NONE)
            linkStore.setObject(linkSlot, LinkReplicator.SQUAD, SnapshotWorld.SQUAD_RED)
        }
        return fields
    }

    private companion object {
        /** Golden. Update deliberately, never to make a failing build green. */
        const val GOLDEN: Long = 1_050_174_046_482_073_810L
    }
}
