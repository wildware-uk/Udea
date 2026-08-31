package dev.wildware.udea.net.wire

import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.net.harness.MoverReplicator
import dev.wildware.udea.net.harness.NetTestComponents
import dev.wildware.udea.net.harness.NetTestWorld
import dev.wildware.udea.net.harness.VitalsReplicator
import dev.wildware.udea.net.bits.BitBufferWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #114: `@Net(lifetime = OnCreate)` fields ride creates and full resends, never deltas.
 *
 * ## What is and is not proven here
 *
 * Proven: `writeDelta` cannot emit a create-only field however loudly capture-and-diff reports it
 * changed; `writeFull` does emit it; a full resend after a baseline loss re-includes it; the mask
 * is a subset of `netMask`.
 *
 * Not proven *here*: that `udea-codegen` emits the mask. It now does - `ComponentModelBuilder`
 * reads the `@Net(lifetime = ...)` argument and `ReplicatorEmitter` adds `CreateOnlyFields` to
 * any component that declares one - so this enforcement applies to shipped code rather than only
 * to the hand-written [CreateOnlyFields] on the fixture replicator below. The generator side is
 * covered by `udea-codegen`'s `GeneratedLifetimeTest`, and the game side by `moba`'s
 * `CombatantLifetimeTest`, which pins `Combatant.teamId` to a create.
 */
class LifetimeMaskTest {

    private val registry = NetTestComponents.registry()

    @Test
    fun `the create only mask is a subset of the net mask and disjoint from the delta mask`() {
        for (index in 0 until registry.size) {
            val replicator = registry.typeAt(index).replicator
            val createOnly = LifetimePolicy.createOnlyMask(replicator)
            val delta = LifetimePolicy.deltaMask(replicator)
            val name = registry.schemaAt(index).typeName

            assertTrue(
                MaskOps.containsAll(replicator.netMask, createOnly),
                "$name declares a create-only field outside its netMask",
            )
            assertTrue(
                MaskOps.isEmpty(MaskOps.and(createOnly, delta)),
                "$name has a create-only field that a delta could still carry",
            )
            assertTrue(
                MaskOps.containsAll(LifetimePolicy.fullMask(replicator), createOnly),
                "$name has a create-only field a full write would not carry",
            )
        }
    }

    @Test
    fun `the fixture actually declares a create only field`() {
        // Without this, the subset assertions above pass vacuously for a build where nothing is
        // create-only — a test that cannot fail.
        assertFalse(
            MaskOps.isEmpty(LifetimePolicy.createOnlyMask(MoverReplicator)),
            "MoverReplicator declares no create-only field, so nothing above is being enforced",
        )
        assertTrue(
            MaskOps.test(LifetimePolicy.createOnlyMask(MoverReplicator), MoverReplicator.TEAM_ID),
            "teamId is the declared lifetime = OnCreate field",
        )
        assertTrue(
            MaskOps.isEmpty(LifetimePolicy.createOnlyMask(VitalsReplicator)),
            "a replicator that declares no create-only fields must report an empty mask",
        )
    }

    @Test
    fun `an OnCreate field is in the create packet and absent from a delta that changed it`() {
        val world = NetTestWorld(registry = registry)
        val netId = world.spawn(1f, 2f, teamId = 3)
        val first = world.captureTick()

        world.mover(netId).teamId = 9
        world.mover(netId).x = 5f
        val second = world.captureTick()

        val writer = SnapshotWriter(registry)
        val buffer = ByteArray(512)

        val createOut = BitBufferWriter(buffer)
        writer.begin()
        writer.writeCreate(createOut, first.fields, first.fields.rowOf(netId), recipientOwnsEntity = true)
        writer.end(createOut)

        val deltaOut = BitBufferWriter(ByteArray(512))
        writer.begin()
        writer.writeUpdate(
            deltaOut,
            second.fields,
            second.fields.rowOf(netId),
            first.fields,
            first.fields.rowOf(netId),
            recipientOwnsEntity = true,
        )
        writer.end(deltaOut)

        val created = ReplicaStore(registry)
        SnapshotReader(registry).read(createOut.toReader(), created)
        val moverIndex = registry.indexOf(MoverReplicator.typeId)
        val createdRow = created.rowOf(netId)
        assertEquals(3, created.storeAt(moverIndex).getInt(created.slotOf(createdRow, moverIndex), MoverReplicator.TEAM_ID))

        // The delta must carry x, and must not carry teamId even though teamId changed by 6.
        var teamIdSeen = false
        var xSeen = false
        SnapshotReader(registry).read(deltaOut.toReader(), created) { _, _, component, mask ->
            if (component != moverIndex) return@read
            if (MaskOps.test(mask, MoverReplicator.TEAM_ID)) teamIdSeen = true
            if (MaskOps.test(mask, MoverReplicator.X)) xSeen = true
        }
        assertTrue(xSeen, "the delta did not carry the ordinary field that changed")
        assertFalse(teamIdSeen, "a lifetime = OnCreate field reached a delta packet")
        assertEquals(
            3,
            created.storeAt(moverIndex).getInt(created.slotOf(createdRow, moverIndex), MoverReplicator.TEAM_ID),
            "the client's create-only field was overwritten by an update",
        )
    }

    @Test
    fun onCreateFieldSurvivesBaselineLoss() {
        val world = NetTestWorld(registry = registry)
        val netId = world.spawn(1f, 2f, teamId = 7)
        world.captureTick()
        world.mover(netId).x = 4f
        val current = world.captureTick()

        // A client whose baseline is gone gets a full write, not a delta. The create-only field
        // must be in it, or that client is left with an undefined team for the rest of the match.
        val out = BitBufferWriter(ByteArray(512))
        val writer = SnapshotWriter(registry)
        writer.begin()
        writer.writeCreate(out, current.fields, current.fields.rowOf(netId), recipientOwnsEntity = true)
        writer.end(out)

        val recovered = ReplicaStore(registry)
        SnapshotReader(registry).read(out.toReader(), recovered)
        val moverIndex = registry.indexOf(MoverReplicator.typeId)
        val row = recovered.rowOf(netId)
        assertEquals(7, recovered.storeAt(moverIndex).getInt(recovered.slotOf(row, moverIndex), MoverReplicator.TEAM_ID))
        assertEquals(4f, recovered.storeAt(moverIndex).getFloat(recovered.slotOf(row, moverIndex), MoverReplicator.X))
    }

    @Test
    fun `a Sim field never reaches the wire at all`() {
        val world = NetTestWorld(registry = registry)
        val netId = world.spawn(1f, 2f, teamId = 1)
        val snapshot = world.captureTick()

        val out = BitBufferWriter(ByteArray(512))
        val writer = SnapshotWriter(registry)
        writer.begin()
        writer.writeCreate(out, snapshot.fields, snapshot.fields.rowOf(netId), recipientOwnsEntity = true)
        writer.end(out)

        val moverIndex = registry.indexOf(MoverReplicator.typeId)
        var spawnTickSeen = false
        SnapshotReader(registry).read(out.toReader(), ReplicaStore(registry)) { _, _, component, mask ->
            if (component == moverIndex && MaskOps.test(mask, MoverReplicator.SPAWN_TICK)) spawnTickSeen = true
        }
        assertFalse(spawnTickSeen, "a @Sim field was replicated; it must rewind and never be sent")
    }
}
