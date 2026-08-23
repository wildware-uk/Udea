package dev.wildware.udea.assets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The contract `TimeControl.rewind` consumes (issue #64).
 *
 * A snapshot records the graph version it was captured at; a rewind asks what changed since. The
 * two answers that matter are "values changed, here they are" - rewind succeeds and the restored
 * world reads the *new* data - and "the shape changed" - rewind refuses, because the `AssetIndex`
 * ints in that snapshot no longer name the same assets.
 */
class AssetGraphVersionsTest {

    private val fireball = AssetId("ability/fireball")
    private val frostbolt = AssetId("ability/frostbolt")
    private val orc = AssetId("character/orc_elite")

    @Test
    fun `a graph that has never reloaded is at version zero and reports nothing`() {
        val log = AssetGraphLog()

        assertEquals(0, log.current())
        assertEquals(AssetChangeSet.None, log.changesSince(0))
        assertFalse(AssetChangeSet.None.changed)
    }

    @Test
    fun `changesSince names exactly what changed after the version asked about`() {
        val log = AssetGraphLog()
        val afterFirst = log.record(listOf(fireball))
        val afterSecond = log.record(listOf(orc))

        assertEquals(setOf(fireball, orc), log.changesSince(0).changedIds)
        assertEquals(setOf(orc), log.changesSince(afterFirst).changedIds)
        assertEquals(emptySet(), log.changesSince(afterSecond).changedIds)
    }

    @Test
    fun `an asset that changed twice is reported once, at its latest version`() {
        val log = AssetGraphLog()
        log.record(listOf(fireball))
        val afterSecond = log.record(listOf(fireball))

        assertEquals(setOf(fireball), log.changesSince(0).changedIds)
        assertEquals(emptySet(), log.changesSince(afterSecond).changedIds)
    }

    @Test
    fun `changed ids are ordered, so two runs report them the same way`() {
        val log = AssetGraphLog()
        log.record(listOf(orc))
        log.record(listOf(frostbolt))
        log.record(listOf(fireball))

        assertEquals(listOf(fireball, frostbolt, orc), log.changesSince(0).changedIds.toList())
    }

    @Test
    fun `a value-only reload does not require a restart`() {
        val log = AssetGraphLog()
        log.record(listOf(fireball))

        val change = log.changesSince(0)

        assertFalse(change.requiresRestart)
        assertTrue(change.changed)
    }

    @Test
    fun `a replaced graph requires a restart for every version before it, and none after`() {
        val log = AssetGraphLog()
        log.record(listOf(fireball))
        val afterReplacement = log.recordReplacement(listOf(frostbolt))
        log.record(listOf(fireball))

        assertTrue(log.changesSince(0).requiresRestart)
        assertTrue(log.changesSince(1).requiresRestart)
        assertFalse(
            log.changesSince(afterReplacement).requiresRestart,
            "a snapshot taken after the restart-requiring reload is on the new graph and is fine",
        )
    }

    @Test
    fun `a version this graph never issued is a programming error, not a guess`() {
        val log = AssetGraphLog()
        log.record(listOf(fireball))

        assertFailsWith<IllegalArgumentException> { log.changesSince(2) }
        assertFailsWith<IllegalArgumentException> { log.changesSince(-1) }
    }

    @Test
    fun `recording nothing would burn a version for nothing`() {
        assertFailsWith<IllegalArgumentException> { AssetGraphLog().record(emptyList()) }
    }

    @Test
    fun `a registry reports through the log it was given, so a swap keeps one history`() {
        // What a shape-changing reload actually does: the host builds a *new* registry over the
        // new pack, and hands it the same log. Without that, the new registry would report version
        // 0 and a rewind into the pre-reload world would look safe.
        val log = AssetGraphLog()
        val before = registryOf(Ability(fireball, uClass("moba.FireballExec")), log = log)
        before.applyDelta(
            GraphDelta(listOf(ChangedAsset(fireball, Ability(fireball, uClass("moba.FireballExec"))))),
        )
        val versionBeforeSwap = before.current()

        log.recordReplacement(listOf(frostbolt))
        val after = registryOf(
            Ability(fireball, uClass("moba.FireballExec")),
            Ability(frostbolt, uClass("moba.FrostboltExec")),
            log = log,
        )

        assertEquals(versionBeforeSwap + 1, after.current())
        assertTrue(after.changesSince(versionBeforeSwap).requiresRestart)
        assertEquals(setOf(frostbolt), after.changesSince(versionBeforeSwap).changedIds)
    }

    @Test
    fun `the static graph is complete rather than a stub`() {
        val versions: AssetGraphVersions = AssetGraphVersions.Static

        assertEquals(0, versions.current())
        assertSame(AssetChangeSet.None, versions.changesSince(0))
        assertFailsWith<IllegalArgumentException> { versions.changesSince(1) }
    }
}
