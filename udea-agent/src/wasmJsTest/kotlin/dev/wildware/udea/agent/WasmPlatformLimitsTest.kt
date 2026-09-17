package dev.wildware.udea.agent

import dev.wildware.udea.agent.query.FieldValues
import dev.wildware.udea.agent.state.EntityCensus
import dev.wildware.udea.agent.tools.DiagToolset
import dev.wildware.udea.core.SimClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two questions Kotlin/Wasm cannot answer, and what the agent surface says instead (issue #208).
 *
 * Each is a refusal a caller can read, never a made-up answer: zeros from `diag.memory` would be
 * read as a measurement, and a guessed enum constant would be written into a component.
 */
class WasmPlatformLimitsTest {

    private enum class Stance { Guard, Charge }

    @Test
    fun `diag memory refuses with a typed error rather than reporting zeros`() {
        val tools = DiagToolset(AgentBridge(), SimClock(), AgentTimings(), EntityCensus.Empty)

        val failed = assertIs<AgentResult.Failed>(tools.memory(), "diag.memory answered on Wasm")

        assertEquals(DiagToolset.MEMORY_UNREPORTED, failed.error.kind)
    }

    @Test
    fun `an enum slot is not written by guessing, and the refusal names the type`() {
        assertNull(FieldValues.parse(Stance.Guard, "Charge"))

        val described = FieldValues.typeNameOf(Stance.Guard)
        assertTrue("Stance" in described && "cannot list" in described, described)
    }
}
