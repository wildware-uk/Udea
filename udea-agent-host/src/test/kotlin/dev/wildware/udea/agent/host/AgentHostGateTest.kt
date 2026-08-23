package dev.wildware.udea.agent.host

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The two conditions, and the refusals. Pure decisions, so every branch is reachable here. */
class AgentHostGateTest {

    @Test
    fun `both conditions are required`() {
        assertTrue(AgentHostGate.decide(agentAllowed = false, portProperty = "7820") is AgentHostGate.Decision.Refuse)
        assertTrue(AgentHostGate.decide(agentAllowed = true, portProperty = null) is AgentHostGate.Decision.Refuse)

        val bind = AgentHostGate.decide(agentAllowed = true, portProperty = "7820")
        assertTrue(bind is AgentHostGate.Decision.Bind)
        assertEquals(7820, bind.port)
    }

    /**
     * The release-variant refusal has to say *why*, because the remedy is a build change and the
     * symptom is a bridge reporting the port as dead.
     */
    @Test
    fun `a forbidden build says so`() {
        val refused = AgentHostGate.decide(agentAllowed = false, portProperty = "7820")
        assertTrue(refused is AgentHostGate.Decision.Refuse)
        assertContains(refused.reason, "AGENT_ALLOWED=false")
    }

    @Test
    fun `port zero is legitimate and asks the OS for one`() {
        val bind = AgentHostGate.decide(agentAllowed = true, portProperty = "0")
        assertTrue(bind is AgentHostGate.Decision.Bind)
        assertEquals(0, bind.port)
    }

    @Test
    fun `a malformed or out of range port is refused rather than defaulted`() {
        listOf("seven", "", "70000", "-1", "78 20").forEach { value ->
            val decision = AgentHostGate.decide(agentAllowed = true, portProperty = value)
            assertTrue(decision is AgentHostGate.Decision.Refuse, "'$value' must not bind")
        }
        // Surrounding whitespace is a launcher artefact, not an operator error.
        assertTrue(AgentHostGate.decide(true, " 7820 ") is AgentHostGate.Decision.Bind)
    }

    @Test
    fun `there is no environment variable fallback`() {
        // The gate reads one thing, and it is a system property. An env fallback would be settable
        // by an end user's launcher script, which is how a debug surface ends up on in the wild.
        assertTrue(AgentHostGate.decide(agentAllowed = true, portProperty = null) is AgentHostGate.Decision.Refuse)
        assertEquals("udea.agent.port", BuildFlags.PORT_PROPERTY)
    }
}
