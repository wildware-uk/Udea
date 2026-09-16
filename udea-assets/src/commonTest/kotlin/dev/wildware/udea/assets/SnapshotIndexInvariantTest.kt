package dev.wildware.udea.assets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tuning loop of spec 3.6, played out against the only half of it this module owns.
 *
 * `udea-core` stores an asset-typed `@Net`/`@Sim` field as an `AssetIndex` int column and captures
 * that column into a snapshot; here that column is an `IntArray`, because the point being asserted
 * is a property of the *asset graph*, not of the ring: after a value-only reload, an index captured
 * before the reload still names the same asset and now reads the new data.
 *
 * That is what makes "patch the fireball's damage, rewind, watch the same fight with the new
 * numbers" work, and it is why an `AssetData` must never enter a snapshot - a captured object
 * reference would freeze the old numbers into the past and the rewind would show the old fight.
 */
class SnapshotIndexInvariantTest {

    private val fireballId = AssetId("ability/fireball")

    private fun fireball(damage: Float) = Ability(
        fireballId,
        uClass("moba.FireballExec"),
        params = mapOf("damage" to AssetValue.FloatValue(damage)),
    )

    private fun damageOf(asset: AssetData): Float =
        ((asset as Ability).params.getValue("damage") as AssetValue.FloatValue).value

    @Test
    fun `an index captured before a reload reads the new data after it`() {
        val log = AssetGraphLog()
        val registry = registryOf(Blueprint(AssetId("character/orc_elite")), fireball(30F), log = log)

        // "Capture": what a component field and a snapshot column actually hold.
        val capturedColumn = intArrayOf(registry.indexOf(fireballId).value)
        val capturedVersion = registry.current()
        assertEquals(30F, damageOf(registry.at(AssetIndex(capturedColumn[0]))))

        val applied = registry.applyDelta(GraphDelta(listOf(ChangedAsset(fireballId, fireball(40F)))))

        // "Restore": the column goes back into the world untouched, and reads the new value.
        assertTrue(applied is DeltaResult.Applied)
        assertEquals(40F, damageOf(registry.at(AssetIndex(capturedColumn[0]))))

        // And the rewind is told what changed, so an agent is not comparing two different games.
        val change = registry.changesSince(capturedVersion)
        assertEquals(setOf(fireballId), change.changedIds)
        assertEquals(false, change.requiresRestart)
    }

    @Test
    fun `a shape-changing reload is what makes a captured index meaningless`() {
        // The other side of the invariant, and the reason `rewind` has to refuse rather than
        // succeed with a flag: after the graph is rebuilt, slot 1 is a different asset entirely.
        val log = AssetGraphLog()
        val before = registryOf(Blueprint(AssetId("character/orc_elite")), fireball(30F), log = log)
        val capturedSlot = before.indexOf(fireballId)
        val capturedVersion = before.current()

        val rebuilt = registryOf(
            Blueprint(AssetId("character/orc_elite")),
            Ability(AssetId("ability/frostbolt"), uClass("moba.FrostboltExec")),
            fireball(30F),
            log = log,
        )
        log.recordReplacement(listOf(AssetId("ability/frostbolt")))

        assertEquals(AssetId("ability/frostbolt"), rebuilt.at(capturedSlot).id)
        assertTrue(rebuilt.changesSince(capturedVersion).requiresRestart)
    }
}
