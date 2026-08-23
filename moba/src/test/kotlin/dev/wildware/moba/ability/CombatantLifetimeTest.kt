package dev.wildware.moba.ability

import dev.wildware.moba.MobaGame
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.core.snapshot.SnapshotService
import dev.wildware.udea.core.snapshot.WorldSnapshot
import dev.wildware.udea.net.bits.BitBufferWriter
import dev.wildware.udea.net.wire.LifetimePolicy
import dev.wildware.udea.net.wire.ReplicaStore
import dev.wildware.udea.net.wire.SnapshotReader
import dev.wildware.udea.net.wire.SnapshotWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #114 in the game: `Combatant.teamId` rides the create packet and no update, ever.
 *
 * ## What was decorative
 *
 * `@Net(lifetime = OnCreate)` has been declarable since Phase 0 and `udea-codegen` never read
 * the argument, so `LifetimePolicy` - which had the enforcement, and had it tested - asked every
 * generated replicator for a create-only mask and got an empty one back. `LifetimeMaskTest` in
 * `udea-net` said so in its own KDoc: the enforcement was real and applied to nothing in a
 * shipped build, because its only create-only field was on a hand-written fixture replicator.
 *
 * This is the same claim against a **generated** replicator, in a real game, over a real
 * snapshot capture: `CombatantReplicator` is written by KSP from the declaration on
 * `Combatant.teamId`, and the packets below are written by the same `SnapshotWriter` a
 * connected server uses.
 */
class CombatantLifetimeTest {

    private val fixture = CombatFixture(autopilot = false)

    private val registry = MobaGame.componentRegistry(fixture.module.attributes.table)

    private val snapshots = SnapshotService(
        registry = registry,
        world = fixture.host.world,
        ctx = fixture.host.ctx,
        netIds = fixture.host.ctx[CoreModule.NET_IDS],
    )

    private val combatant = registry.indexOf(CombatantReplicator.typeId)

    private fun capture(): WorldSnapshot = snapshots.capture()

    @Test
    fun `the generated replicator declares teamId create-only and nothing else`() {
        // Vacuous-pass guard: every assertion below would hold for a build where the generator
        // still reported an empty mask and nothing was being stripped.
        assertTrue(
            MaskOps.test(LifetimePolicy.createOnlyMask(CombatantReplicator), CombatantReplicator.FIELD_TEAM_ID),
            "the generator did not emit teamId as create-only",
        )
        assertTrue(
            MaskOps.isEmpty(LifetimePolicy.deltaMask(CombatantReplicator)),
            "a delta could still carry teamId",
        )
        assertTrue(
            MaskOps.test(LifetimePolicy.fullMask(CombatantReplicator), CombatantReplicator.FIELD_TEAM_ID),
            "a Create would not carry teamId, which would leave a client with no team at all",
        )
    }

    @Test
    fun `teamId is in the create packet and absent from every later update`() {
        val orc = fixture.spawn("orc_elite", 0f, 0f)
        fixture.step(1)
        val first = capture()

        // The unit moves and takes damage - ordinary per-tick change - and, to make the claim
        // sharp, its team is changed too. Even a team that *did* move must not reach a delta.
        with(fixture.host.world) { fixture.entityOf(orc)[Combatant].teamId = Teams.UNDEAD }
        fixture.positionOf(orc).x += 1f
        fixture.step(1)
        val second = capture()

        val writer = SnapshotWriter(registry)
        val createOut = BitBufferWriter(ByteArray(BUFFER_BYTES))
        writer.begin()
        writer.writeCreate(createOut, first.fields, first.fields.rowOf(orc))
        writer.end(createOut)

        val updateOut = BitBufferWriter(ByteArray(BUFFER_BYTES))
        writer.begin()
        writer.writeUpdate(
            updateOut,
            second.fields,
            second.fields.rowOf(orc),
            first.fields,
            first.fields.rowOf(orc),
        )
        writer.end(updateOut)

        val replica = ReplicaStore(registry)
        var teamInCreate = false
        SnapshotReader(registry).read(createOut.toReader(), replica) { _, _, component, mask ->
            if (component == combatant && MaskOps.test(mask, CombatantReplicator.FIELD_TEAM_ID)) {
                teamInCreate = true
            }
        }
        assertTrue(teamInCreate, "the create packet did not carry the team, so a client has none")

        var teamInUpdate = false
        var somethingElseInUpdate = false
        SnapshotReader(registry).read(updateOut.toReader(), replica) { _, _, component, mask ->
            if (component == combatant && MaskOps.test(mask, CombatantReplicator.FIELD_TEAM_ID)) {
                teamInUpdate = true
            } else if (!MaskOps.isEmpty(mask)) {
                somethingElseInUpdate = true
            }
        }
        // The second half matters as much as the first: an update that carried *nothing* would
        // satisfy `!teamInUpdate` while proving that the writer had simply stopped working.
        assertTrue(somethingElseInUpdate, "the update carried nothing at all, so nothing was stripped")
        assertFalse(teamInUpdate, "a lifetime = OnCreate field reached an update packet")

        // And the client keeps the value it was created with rather than losing it to a
        // stripped update.
        val row = replica.rowOf(orc)
        assertEquals(
            Teams.ORC,
            replica.storeAt(combatant)
                .getInt(replica.slotOf(row, combatant), CombatantReplicator.FIELD_TEAM_ID),
        )
    }

    private companion object {
        const val BUFFER_BYTES = 4096
    }
}
