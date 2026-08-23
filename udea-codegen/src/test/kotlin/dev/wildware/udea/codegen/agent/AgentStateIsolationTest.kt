package dev.wildware.udea.codegen.agent

import dev.wildware.udea.agent.state.GameStateSink
import dev.wildware.udea.codegen.GeneratedSources
import dev.wildware.udea.codegen.fixtures.Health
import dev.wildware.udea.codegen.fixtures.HealthAgentState
import dev.wildware.udea.codegen.fixtures.HealthReplicator
import dev.wildware.udea.codegen.fixtures.MatchClock
import dev.wildware.udea.codegen.fixtures.MatchClockAgentState
import dev.wildware.udea.codegen.fixtures.MatchPhase
import dev.wildware.udea.core.replication.MaskOps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `@AgentState` is outside the `Replicator` field space, and this is what holds it there.
 *
 * The frozen contract makes `fieldNames[i]`, `FieldMask` bit `i` and `FieldStore` index `i`
 * **the same index**, and `desync_report(tick)` depends on it: it walks the set bits of the
 * difference between two slots and indexes `fieldNames` with each one. A property that had a
 * `fieldNames` entry but no bit and no slot would shift every name past it, so every divergence
 * after that index would be reported against the wrong field — a wrong answer, not a missing
 * one, and the hardest kind to notice.
 *
 * `Health` is the fixture that makes the claim testable: `current` carries `@Net` *and*
 * `@AgentState`, and `deaths` carries `@AgentState` alone.
 */
class AgentStateIsolationTest {

    /** A [GameStateSink] that records what was written, and what type it was written as. */
    private class Recorder : GameStateSink {
        val written: MutableList<Pair<String, Any?>> = mutableListOf()
        override fun put(name: String, value: Int) { written += name to value }
        override fun put(name: String, value: Long) { written += name to value }
        override fun put(name: String, value: Float) { written += name to value }
        override fun put(name: String, value: Boolean) { written += name to value }
        override fun put(name: String, value: String?) { written += name to value }
    }

    @Test
    fun `an AgentState-only property takes no field index in the replicator`() {
        assertEquals(
            listOf("current", "invulnerable", "lastDamageTick", "maximum"),
            HealthReplicator.fieldNames,
            "`deaths` is @AgentState only and must own no fieldNames entry",
        )
        assertFalse("deaths" in HealthReplicator.fieldNames)
    }

    @Test
    fun `the digest key is not the field name, and neither renames the other`() {
        // `current` is published to the agent as `health`. If @AgentState reached the field
        // space, the rename would land in `fieldNames` and desync_report would start reporting
        // a field name no component declares.
        assertTrue("health" in HealthAgentState.names)
        assertFalse("health" in HealthReplicator.fieldNames)
        assertTrue("current" in HealthReplicator.fieldNames)
    }

    @Test
    fun `index alignment still holds on a component that also publishes digest scalars`() {
        // The invariant itself, re-asserted on this fixture: every fieldNames index is a mask
        // bit and a store slot, with nothing in between for an @AgentState property to occupy.
        val all = HealthReplicator.allMask
        for (index in HealthReplicator.fieldNames.indices) {
            assertTrue(MaskOps.test(all, index), "field $index is not in allMask")
        }
        assertEquals(
            HealthReplicator.fieldNames.size,
            MaskOps.cardinality(all),
            "allMask has a bit that no fieldNames entry addresses, or the other way round",
        )
    }

    @Test
    fun `the generated replicator never mentions an AgentState-only property`() {
        // The text assertion the behavioural ones cannot make: a replicator that read `deaths`
        // into a scratch variable it then discarded would pass every test above.
        val source = GeneratedSources.files.single { it.name == "HealthReplicator.kt" }.readText()
        assertFalse("deaths" in source, "HealthReplicator touches an @AgentState-only property")
    }

    @Test
    fun `writing the digest emits one scalar per published key, in key order`() {
        val recorder = Recorder()

        HealthAgentState.write(Health(current = 42f).also { it.deaths = 3 }, recorder)

        assertEquals(
            listOf<Pair<String, Any?>>("deaths" to 3, "health" to 42f),
            recorder.written.toList(),
        )
    }

    @Test
    fun `every published value is a JSON scalar, never a nested object or an array`() {
        // The bridge contract for the `game` block: "scalar fields are included in the digest.
        // Nested objects and arrays are not." A non-scalar here would vanish silently, so the
        // restriction is enforced at build time and asserted on the values at run time.
        val recorder = Recorder()

        MatchClockAgentState.write(MatchClock(), recorder)

        assertEquals(
            listOf("elapsedTicks", "elapsed_ms", "match_name", "meanFrameMillis", "paused", "phase", "timeScale"),
            recorder.written.map { it.first },
        )
        for ((name, value) in recorder.written) {
            assertTrue(
                value is Number || value is Boolean || value is String,
                "$name published a ${value?.javaClass?.simpleName}, which the digest would drop",
            )
        }
    }

    @Test
    fun `an enum is published by constant name, so reordering the enum cannot change a digest`() {
        val recorder = Recorder()

        MatchClockAgentState.write(MatchClock().also { it.phase = MatchPhase.Finished }, recorder)

        assertEquals("Finished", recorder.written.single { it.first == "phase" }.second)
    }

    @Test
    fun `the default digest key is the property name and an explicit name overrides it`() {
        assertTrue("elapsedTicks" in MatchClockAgentState.names, "the default is the property name")
        assertTrue("elapsed_ms" in MatchClockAgentState.names, "@AgentState(name) overrides it")
        assertFalse("elapsedMillis" in MatchClockAgentState.names, "the property name must not also appear")
    }
}
