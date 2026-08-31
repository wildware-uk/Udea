package dev.wildware.udea.net.wire

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.net.bits.BitBufferWriter
import dev.wildware.udea.net.harness.LoadoutReplicator
import dev.wildware.udea.net.harness.MoverReplicator
import dev.wildware.udea.net.harness.NetTestComponents
import dev.wildware.udea.net.harness.NetTestWorld
import dev.wildware.udea.net.harness.ReplicationSession
import dev.wildware.udea.net.harness.VitalsReplicator
import dev.wildware.udea.net.replication.Desync
import dev.wildware.udea.net.replication.DesyncReport
import dev.wildware.udea.net.replication.ReplicationClient
import dev.wildware.udea.net.rpc.RpcOwnership
import dev.wildware.udea.net.transport.PeerId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Issue #167: `@Net(visibility = OwnerOnly)` fields reach the entity's owner and nobody else.
 *
 * ## What was wrong
 *
 * `Visibility` has been in `udea-annotations` since Phase 0 and the only references to
 * `OwnerOnly` in the tree were the enum case and the test asserting the vocabulary exists.
 * `ComponentModelBuilder` never read the argument and `SnapshotWriter` had no per-recipient
 * stripping, so a component author who wrote `OwnerOnly` on an inventory got a declaration that
 * read as a guarantee and sent the field to every client the entity was relevant to.
 *
 * ## What is proven here
 *
 * The session below is a real [ReplicationSession]: the real `SnapshotService` captures into the
 * real `SnapshotRing`, the real [dev.wildware.udea.net.replication.ReplicationServer] packs a
 * datagram per client per tick out of that ring, and two real
 * [ReplicationClient]s decode them. What each client ends up holding is what a
 * connected process would hold, not a unit of the mask.
 *
 * The generator half — that `udea-codegen` emits the mask at all — is `GeneratedVisibilityTest`,
 * and the game half is `moba`'s `InventoryVisibilityTest`.
 */
class OwnerOnlyVisibilityTest {

    private val registry = NetTestComponents.registryWithLoadout()
    private val loadoutIndex = registry.indexOf(LoadoutReplicator.typeId)

    /** The entity [PeerId.client] 1 owns. Set once the world has spawned it. */
    private var owned: NetId? = null

    /**
     * Client 1 owns [owned]; everything else is server-owned, which is every other entity and
     * every id that does not exist.
     *
     * The same shape `moba`'s `ChampionOwnership` has, and deliberately the same *type*: the
     * packer and the RPC guard read one registry of who owns what, so they cannot disagree.
     */
    private val ownership = RpcOwnership { entity -> if (entity == owned) PeerId.client(1) else PeerId.SERVER }

    private fun session(): ReplicationSession = ReplicationSession(
        clientCount = 2,
        registry = registry,
        ownership = ownership,
    )

    private fun ReplicationClient.gold(netId: NetId): Int = loadoutField(netId, LoadoutReplicator.GOLD)

    private fun ReplicationClient.loadoutField(netId: NetId, field: Int): Int {
        val row = world.rowOf(netId)
        assertNotEquals(ReplicaStore.ABSENT, row, "$peer holds no row for $netId at all")
        val slot = world.slotOf(row, loadoutIndex)
        assertNotEquals(ReplicaStore.ABSENT, slot, "$peer holds no Loadout for $netId")
        return world.storeAt(loadoutIndex).getInt(slot, field)
    }

    @Test
    fun `the owner only mask is a subset of the net mask and is what the visible mask removes`() {
        for (index in 0 until registry.size) {
            val replicator = registry.typeAt(index).replicator
            val ownerOnly = VisibilityPolicy.ownerOnlyMask(replicator)
            val name = registry.schemaAt(index).typeName

            assertTrue(
                MaskOps.containsAll(replicator.netMask, ownerOnly),
                "$name declares an owner-only field outside its netMask",
            )
            assertEquals(
                replicator.netMask,
                VisibilityPolicy.visibleMask(replicator, recipientOwnsEntity = true),
                "$name hides something from its own owner",
            )
            assertTrue(
                MaskOps.isEmpty(
                    MaskOps.and(ownerOnly, VisibilityPolicy.visibleMask(replicator, recipientOwnsEntity = false)),
                ),
                "$name has an owner-only field a non-owner's packet could still carry",
            )
        }
    }

    @Test
    fun `the fixture actually declares an owner only field`() {
        // Without this the loop above passes vacuously for a build where nothing is owner-only,
        // which is precisely the state this issue is about: an enforcement that enforces nothing.
        assertTrue(
            MaskOps.test(VisibilityPolicy.ownerOnlyMask(LoadoutReplicator), LoadoutReplicator.GOLD),
            "gold is the declared visibility = OwnerOnly field",
        )
        assertFalse(
            MaskOps.test(VisibilityPolicy.ownerOnlyMask(LoadoutReplicator), LoadoutReplicator.LEVEL),
            "level is public and must not be in the owner-only mask",
        )
        assertTrue(
            MaskOps.isEmpty(VisibilityPolicy.ownerOnlyMask(MoverReplicator)),
            "a replicator that declares no owner-only field must report an empty mask",
        )
    }

    @Test
    fun `the owner receives an owner only field and a non-owner never does`() {
        val session = session()
        val netId = session.world.spawn(1f, 2f, teamId = 3, withLoadout = true)
        owned = netId
        with(session.world.loadout(netId)) {
            gold = 1234
            level = 7
            weapon = 9
        }
        session.step(20)

        // The field changes mid-session, so the `Update` path is exercised and not only the
        // `Create`: an implementation that stripped the full write and forgot the delta would
        // leak the new value the first time the owner spent anything.
        session.world.loadout(netId).gold = 4321
        session.step(20)

        val owner = session.clients.single { it.peer == PeerId.client(1) }
        val other = session.clients.single { it.peer == PeerId.client(2) }

        assertEquals(4321, owner.gold(netId), "the owner was not sent its own owner-only field")
        assertEquals(
            0,
            other.gold(netId),
            "a non-owner was sent an owner-only field; 0 is this store's untouched value and " +
                "the server has never held 0 in it",
        )

        // Both clients must still be told everything else about the same component, or the
        // "stripping" is really "dropping the component" and the test above would pass for the
        // wrong reason.
        for (client in listOf(owner, other)) {
            assertEquals(7, client.loadoutField(netId, LoadoutReplicator.LEVEL), "${client.peer} lost level")
            assertEquals(9, client.loadoutField(netId, LoadoutReplicator.WEAPON), "${client.peer} lost weapon")
        }
    }

    @Test
    fun `stripping clears a bit and never renumbers one`() {
        // The frozen contract makes `fieldNames[i]`, FieldMask bit i and FieldStore index i the
        // same i, and `DesyncReport` names a differing field by indexing `fieldNames` with a bit
        // of a mask diff. An implementation that compacted a recipient's surviving fields down
        // would decode cleanly and report the wrong name for the rest of the session — it would
        // not fail, it would lie. So the assertion is on the *name* the real reporter produces.
        val session = session()
        val netId = session.world.spawn(1f, 2f, teamId = 3, withLoadout = true)
        owned = netId
        with(session.world.loadout(netId)) {
            gold = 1234
            level = 7
            weapon = 9
        }
        session.step(20)

        val owner = session.clients.single { it.peer == PeerId.client(1) }
        val other = session.clients.single { it.peer == PeerId.client(2) }

        val ownerReport = DesyncReport.compare(registry, session.serverStateAt(owner.serverTick).fields, owner.world)
        assertEquals(
            emptyList(),
            ownerReport,
            "the owner disagrees with the server about something, so nothing below is about visibility",
        )

        val otherReport = DesyncReport.compare(registry, session.serverStateAt(other.serverTick).fields, other.world)
        assertEquals(
            listOf("gold"),
            otherReport.map(Desync::fieldName),
            "the non-owner's only difference from the server must be the owner-only field, named " +
                "as `gold`; a different name here is the alignment being broken rather than a " +
                "second field going missing",
        )
        assertEquals(1234, otherReport.single().serverValue)
        assertEquals(0, otherReport.single().clientValue)

        // And the same claim read straight off the indices, because `DesyncReport` and the store
        // would agree with each other even if both were shifted.
        assertEquals(7, other.loadoutField(netId, LoadoutReplicator.LEVEL))
        assertEquals(9, other.loadoutField(netId, LoadoutReplicator.WEAPON))
        assertEquals(
            listOf("gold", "level", "weapon"),
            LoadoutReplicator.fieldNames,
            "the fixture's field order is what the two assertions above are indexing",
        )
    }

    @Test
    fun `a component declaring no owner only field costs a non-owner nothing`() {
        // Acceptance: "the default costs nothing". Not "the mask is empty" — the same bytes.
        val plain = NetTestComponents.registry()
        assertContentEquals(
            createBytes(plain, recipientOwnsEntity = true, withLoadout = false),
            createBytes(plain, recipientOwnsEntity = false, withLoadout = false),
            "a non-owner's packet differs from the owner's for a world whose components declare " +
                "no owner-only field at all",
        )

        // The control. Without it the assertion above is satisfied by a build that strips
        // nothing, anywhere, which is the state this issue is filed about.
        val owner = createBytes(registry, recipientOwnsEntity = true, withLoadout = true)
        val nonOwner = createBytes(registry, recipientOwnsEntity = false, withLoadout = true)
        assertFalse(
            owner.contentEquals(nonOwner),
            "an owner-only field was declared and the two recipients got identical bytes",
        )
        assertTrue(
            nonOwner.size < owner.size,
            "the non-owner's packet (${nonOwner.size} B) was not smaller than the owner's (${owner.size} B)",
        )
    }

    /** One `Create` for one freshly spawned entity, as bytes. */
    private fun createBytes(
        registry: dev.wildware.udea.core.snapshot.ComponentRegistry,
        recipientOwnsEntity: Boolean,
        withLoadout: Boolean,
    ): ByteArray {
        val world = NetTestWorld(registry = registry)
        val netId = world.spawn(1f, 2f, teamId = 3, withLoadout = withLoadout)
        if (withLoadout) {
            with(world.loadout(netId)) {
                gold = 1234
                level = 7
                weapon = 9
            }
        }
        world.vitals(netId).hp = 55
        val snapshot = world.captureTick()

        val buffer = ByteArray(512)
        val out = BitBufferWriter(buffer)
        val writer = SnapshotWriter(registry)
        writer.begin()
        writer.writeCreate(out, snapshot.fields, snapshot.fields.rowOf(netId), recipientOwnsEntity)
        writer.end(out)
        return buffer.copyOf(out.byteLength)
    }

    @Test
    fun `the plain fixture components declare nothing owner-only`() {
        // The first assertion of the test above is only about "costs nothing" if this holds.
        assertTrue(MaskOps.isEmpty(VisibilityPolicy.ownerOnlyMask(MoverReplicator)))
        assertTrue(MaskOps.isEmpty(VisibilityPolicy.ownerOnlyMask(VitalsReplicator)))
    }
}
