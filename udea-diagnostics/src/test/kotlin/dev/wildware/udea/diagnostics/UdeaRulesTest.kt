package dev.wildware.udea.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UdeaRulesTest {

    @Test
    fun `every rule id matches the UDEA format`() {
        assertTrue(UdeaRules.all.isNotEmpty())
        for (rule in UdeaRules.all) {
            assertTrue(
                UdeaRules.ID_FORMAT.matches(rule.id),
                "rule id '${rule.id}' does not match ${UdeaRules.ID_FORMAT.pattern}",
            )
        }
    }

    @Test
    fun `every rule id is unique`() {
        val ids = UdeaRules.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate rule ids in $ids")
    }

    @Test
    fun `the registry is sorted by id and every rule is looked up by its own id`() {
        assertEquals(UdeaRules.all.map { it.id }.sorted(), UdeaRules.all.map { it.id })
        for (rule in UdeaRules.all) {
            assertSame(rule, UdeaRules.byId(rule.id))
        }
        assertNull(UdeaRules.byId("UDEA9999"))
    }

    @Test
    fun `every rule carries a one-line description and a default severity`() {
        for (rule in UdeaRules.all) {
            assertTrue(rule.description.isNotBlank(), "${rule.id} has no description")
            assertTrue('\n' !in rule.description, "${rule.id} description is not one line")
        }
    }

    /**
     * These ids are the seed set spec sections 3.2 and 6 demand plus the two `udea-codegen`
     * raises, and the stability contract says an id never changes meaning. Pinning them here
     * means renumbering a rule fails a test rather than silently breaking every suppression
     * file downstream.
     *
     * The size assertion is what stops a new rule from being declared and then left out of
     * [UdeaRules.all], which would make it invisible to [UdeaRules.byId] and to every CI
     * filter that enumerates the registry.
     */
    @Test
    fun `the registered rule ids are pinned`() {
        assertEquals("UDEA0001", UdeaRules.NET_ON_VAL.id)
        assertEquals("UDEA0002", UdeaRules.COMPONENT_FIELD_LIMIT.id)
        assertEquals("UDEA0003", UdeaRules.QUANTIZED_NON_FLOAT.id)
        assertEquals("UDEA0004", UdeaRules.UNRESOLVED_REFERENCE.id)
        assertEquals("UDEA0005", UdeaRules.SIM_ON_VAL.id)
        assertEquals("UDEA0006", UdeaRules.UNSUPPORTED_FIELD_TYPE.id)
        assertEquals("UDEA0007", UdeaRules.MALFORMED_QUANTIZATION.id)
        assertEquals("UDEA0008", UdeaRules.AGENT_TOOL_DESCRIPTION.id)
        assertEquals("UDEA0009", UdeaRules.AGENT_ARG_DESCRIPTION.id)
        assertEquals("UDEA0010", UdeaRules.AGENT_TOOL_UNSUPPORTED_TYPE.id)
        assertEquals("UDEA0011", UdeaRules.AGENT_STATE_NON_SCALAR.id)
        assertEquals("UDEA0012", UdeaRules.AGENT_NAME_COLLISION.id)
        assertEquals(12, UdeaRules.all.size)
        assertTrue(UdeaRules.all.all { it.defaultSeverity == Severity.Error })
    }

    /**
     * A rule declared on the object but missing from [UdeaRules.all] is unreachable through
     * [UdeaRules.byId], so a suppression file naming its id would silently never match. The
     * count in the test above only catches that if whoever adds a rule also updates the count;
     * this catches it either way, by reading the declarations back off the object.
     */
    @Test
    fun `every declared rule is registered in all`() {
        val declared = UdeaRules::class.java.methods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 }
            .filter { UdeaRule::class.java.isAssignableFrom(it.returnType) }
            .map { it.invoke(UdeaRules) as UdeaRule }
            .toSet()

        assertTrue(declared.isNotEmpty(), "reflection found no UdeaRule properties at all")
        assertEquals(
            declared.map { it.id }.sorted(),
            UdeaRules.all.map { it.id }.sorted(),
            "a rule is declared on UdeaRules but missing from UdeaRules.all",
        )
    }

    @Test
    fun `a malformed rule id cannot be declared`() {
        assertFailsWith<IllegalArgumentException> { UdeaRule("UDEA1", Severity.Error, "too short") }
        assertFailsWith<IllegalArgumentException> { UdeaRule("UDEA00012", Severity.Error, "too long") }
        assertFailsWith<IllegalArgumentException> { UdeaRule("udea0001", Severity.Error, "wrong case") }
        assertFailsWith<IllegalArgumentException> { UdeaRule("XXXX0001", Severity.Error, "wrong prefix") }
        assertFailsWith<IllegalArgumentException> { UdeaRule("UDEA0001", Severity.Error, " ") }
    }

    @Test
    fun `diagnostic takes the id and the default severity from the rule`() {
        val diagnostic = UdeaRules.NET_ON_VAL.diagnostic("@Net on val health")

        assertEquals("UDEA0001", diagnostic.ruleId)
        assertEquals(Severity.Error, diagnostic.severity)
        assertNull(diagnostic.span)
        assertEquals(false, diagnostic.isDerived)
    }

    @Test
    fun `diagnostic can downgrade the severity without changing the rule id`() {
        val diagnostic = UdeaRules.NET_ON_VAL.diagnostic("@Net on val health", severity = Severity.Warning)

        assertEquals("UDEA0001", diagnostic.ruleId)
        assertEquals(Severity.Warning, diagnostic.severity)
        assertEquals(Severity.Error, UdeaRules.NET_ON_VAL.defaultSeverity)
    }

    @Test
    fun `severity wire names are the stable lowercase forms`() {
        assertEquals(listOf("error", "warning", "info"), Severity.entries.map { it.wireName })
    }
}
