package dev.wildware.moba.item

import dev.wildware.moba.net.MobaLoopbackSession
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.wire.ReplicaStore
import dev.wildware.udea.net.wire.VisibilityPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Issue #167 in the game: a champion's inventory reaches its own player and nobody else.
 *
 * ## What was decorative
 *
 * `Inventory` has declared `@Net(visibility = OwnerOnly)` on all seven of its slots since issue
 * #132 shipped, and that class' own KDoc said what the declaration was worth: "**Nothing
 * enforces it today.** ... every client holding this entity is sent every field of this
 * component." Two players in a match were each sent the other's items on every packet that
 * carried the component.
 *
 * ## Why this and not only the `udea-net` test
 *
 * `OwnerOnlyVisibilityTest` proves the mechanism against a hand-written fixture replicator. This
 * is the same claim against a **generated** one, in a real game, over a real session:
 * `InventoryReplicator` is written by KSP from the declarations on `Inventory`, `ChampionOwnership`
 * is the registry the RPC guard already reads, and the two clients below decode datagrams the
 * same `ReplicationServer` a connected process uses put on a link.
 *
 * `Inventory` is also the shape the fixture cannot show: **every** field of it is owner-only, so
 * a non-owner is not sent a partial record for it - it is not sent the component at all.
 */
class InventoryVisibilityTest {

    @Test
    fun `the generated replicator declares every inventory slot owner-only`() {
        // Vacuous-pass guard. Every assertion in the session test below would hold for a build
        // where the generator still reported an empty mask and the client simply had not been
        // sent the component yet.
        val ownerOnly = VisibilityPolicy.ownerOnlyMask(InventoryReplicator)
        assertEquals(
            InventoryReplicator.netMask,
            ownerOnly,
            "every field of Inventory is declared OwnerOnly, so the two masks must be the same",
        )
        assertTrue(
            MaskOps.isEmpty(VisibilityPolicy.visibleMask(InventoryReplicator, recipientOwnsEntity = false)),
            "a non-owner can still see something of an inventory",
        )
        assertEquals(
            InventoryReplicator.netMask,
            VisibilityPolicy.visibleMask(InventoryReplicator, recipientOwnsEntity = true),
            "the owner cannot see its own inventory",
        )
    }

    @Test
    fun `a player is sent its own inventory and never another player's`() {
        MobaLoopbackSession(clientCount = 2).use { session ->
            // Long enough for `InventoryGrantSystem` to have given both champions an inventory
            // and for the packer to have offered each champion to each client several times.
            session.step(TICKS)

            val first = session.server.championOf(PeerId.client(1))!!
            val second = session.server.championOf(PeerId.client(2))!!
            assertNotEquals(first, second, "both seats were given the same champion")

            // Two different items, so an assertion cannot pass by reading the other one.
            serverInventory(session, first).slot0 = FIRST_ITEM
            serverInventory(session, second).slot0 = SECOND_ITEM
            session.step(TICKS)

            assertEquals(
                FIRST_ITEM,
                clientSlot0(session, clientIndex = 0, champion = first),
                "client 1 was not sent its own champion's inventory",
            )
            assertEquals(
                SECOND_ITEM,
                clientSlot0(session, clientIndex = 1, champion = second),
                "client 2 was not sent its own champion's inventory",
            )

            assertEquals(
                ABSENT,
                clientSlot0(session, clientIndex = 0, champion = second),
                "client 1 holds the other player's inventory",
            )
            assertEquals(
                ABSENT,
                clientSlot0(session, clientIndex = 1, champion = first),
                "client 2 holds the other player's inventory",
            )

            // Each client must still hold the other champion itself, or the assertions above are
            // satisfied by relevancy having hidden the whole entity and say nothing about fields.
            assertTrue(
                holdsEntity(session, clientIndex = 0, netId = second),
                "client 1 does not hold the other champion at all, so nothing above is about " +
                    "field visibility",
            )
            assertTrue(
                holdsEntity(session, clientIndex = 1, netId = first),
                "client 2 does not hold the other champion at all, so nothing above is about " +
                    "field visibility",
            )
        }
    }

    /** The server's live [Inventory] for [champion]. */
    private fun serverInventory(session: MobaLoopbackSession, champion: NetId): Inventory {
        val entity = session.server.host.ctx[CoreModule.NET_IDS].resolveOrNull(champion)
            ?: error("the server holds no entity for $champion")
        return with(session.server.host.world) {
            entity.getOrNull(Inventory) ?: error("$champion has no Inventory; the grant system has not run")
        }
    }

    /**
     * `slot0` in client [clientIndex]'s replica of [champion], or [ABSENT] when that client was
     * not sent the component.
     *
     * Read off the [ReplicaStore] rather than off the client's Fleks world, because the store is
     * what the packet actually wrote: a Fleks component could be present for a reason other than
     * replication, and "absent" is the whole claim.
     */
    private fun clientSlot0(session: MobaLoopbackSession, clientIndex: Int, champion: NetId): Int {
        val store = session.clients[clientIndex].replication.world
        val row = store.rowOf(champion)
        if (row == ReplicaStore.ABSENT) return ABSENT
        val component = inventoryIndex(session)
        val slot = store.slotOf(row, component)
        if (slot == ReplicaStore.ABSENT) return ABSENT
        return store.storeAt(component).getInt(slot, InventoryReplicator.FIELD_SLOT0)
    }

    private fun holdsEntity(session: MobaLoopbackSession, clientIndex: Int, netId: NetId): Boolean =
        session.clients[clientIndex].replication.world.rowOf(netId) != ReplicaStore.ABSENT

    private fun inventoryIndex(session: MobaLoopbackSession): Int =
        session.clients[0].registry.indexOf(InventoryReplicator.typeId)

    private companion object {

        /** Long enough for every champion to have been offered to every client many times over. */
        const val TICKS: Int = 120

        /** What [clientSlot0] answers when the client was sent no inventory at all. */
        const val ABSENT: Int = -1

        /**
         * Two distinct raw `AssetIndex` values, neither of them [Inventory.EMPTY] and neither of
         * them [ABSENT], so every assertion above discriminates.
         */
        const val FIRST_ITEM: Int = 4242

        /** @see FIRST_ITEM */
        const val SECOND_ITEM: Int = 5353
    }
}
