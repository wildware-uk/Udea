package dev.wildware.udea.agent.query

import dev.wildware.udea.agent.HealthReplicator
import dev.wildware.udea.agent.TransformReplicator
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.replication.MaskOps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tier 2: every component, every field, with the two things an agent needs before it acts - which
 * mask the field is in, and whether it may write it.
 *
 * The masks are checked against the `Replicator`s themselves rather than against literals, so the
 * test says "the dump agrees with the codec" rather than "the dump says what I typed". A literal
 * would keep passing if the dump stopped reading the replicator at all.
 */
class EntityDetailTest {

    private class Field(val name: String, val value: String, val mask: String, val writable: Boolean)

    private fun fieldsOf(document: String, component: String): List<Field> {
        val block = document.substringAfter("\"name\":\"$component\"").substringBefore("]}")
        return Regex(
            """\{"name":"([^"]+)","value":([^,]+),"mask":"([^"]+)","agentWritable":(true|false)}""",
        ).findAll(block).map { Field(it.groupValues[1], it.groupValues[2], it.groupValues[3], it.groupValues[4].toBoolean()) }
            .toList()
    }

    @Test
    fun `every field of every component the entity carries appears`() {
        val harness = QueryHarness()
        val id = harness.spawn(x = 38.2f, y = 20.9f, health = 310f, team = 2)

        val document = harness.detail.render(id)

        assertEquals(
            TransformReplicator.fieldNames,
            fieldsOf(document, "Transform").map { it.name },
        )
        assertEquals(HealthReplicator.fieldNames, fieldsOf(document, "Health").map { it.name })
        assertEquals(listOf("team", "ally"), fieldsOf(document, "Team").map { it.name })
        assertEquals(listOf("level"), fieldsOf(document, "Champion").map { it.name })
    }

    @Test
    fun `a component the entity does not carry is absent`() {
        val harness = QueryHarness()
        val id = harness.spawn(champion = false)

        val document = harness.detail.render(id)

        assertFalse(document.contains("\"Champion\""), document)
        assertTrue(document.contains("\"Health\""), document)
    }

    @Test
    fun `values are rendered in the same form a query renders them`() {
        val harness = QueryHarness()
        val ally = harness.spawn()
        val id = harness.spawn(x = 38.2f, y = 20.9f, health = 310f, team = 2, ally = ally)

        val document = harness.detail.render(id)

        assertEquals("38.2", fieldsOf(document, "Transform").single { it.name == "position.x" }.value)
        assertEquals("310", fieldsOf(document, "Health").single { it.name == "current" }.value)
        assertEquals("2", fieldsOf(document, "Team").single { it.name == "team" }.value)
        // The packed word, so an agent can feed the reference straight back into a tool call.
        assertEquals(ally.raw.toString(), fieldsOf(document, "Team").single { it.name == "ally" }.value)
    }

    @Test
    fun `each field carries the mask its replicator declares`() {
        val harness = QueryHarness()
        val id = harness.spawn()

        val document = harness.detail.render(id)

        for (field in fieldsOf(document, "Transform")) {
            val index = TransformReplicator.fieldNames.indexOf(field.name)
            val expected = if (MaskOps.test(TransformReplicator.netMask, index)) "net" else "sim"
            assertEquals(expected, field.mask, "mask for Transform.${field.name}")
        }
        // Concretely: rotation is @Sim on this component, so it rewinds and is never sent.
        assertEquals("sim", fieldsOf(document, "Transform").single { it.name == "rotation" }.mask)
        assertEquals("net", fieldsOf(document, "Health").single { it.name == "current" }.mask)
    }

    @Test
    fun `agentWritable matches the declared mask and defaults to false`() {
        val harness = QueryHarness()
        val id = harness.spawn()

        val document = harness.detail.render(id)

        assertTrue(fieldsOf(document, "Health").single { it.name == "current" }.writable)
        assertFalse(fieldsOf(document, "Health").single { it.name == "max" }.writable)
        // Team declared no writable mask at all, which is spec 5's default: agent write access
        // is opt-in per field so a debug tool cannot quietly become a gameplay backdoor.
        assertTrue(fieldsOf(document, "Team").none { it.writable })
    }

    @Test
    fun `the id, its index and its generation are all reported`() {
        val harness = QueryHarness()
        val first = harness.spawn()
        harness.destroy(first)
        val recycled = harness.spawn()

        val document = harness.detail.render(recycled)

        assertTrue(document.contains("\"id\":${recycled.raw}"), document)
        assertTrue(document.contains("\"index\":${recycled.index}"), document)
        assertTrue(document.contains("\"generation\":1"), document)
    }

    @Test
    fun `the dump is a single well-formed JSON value`() {
        val harness = QueryHarness()
        val document = harness.detail.render(harness.spawn())

        assertEquals('{', document.first())
        assertEquals('}', document.last())
        assertEquals(
            document.count { it == '{' },
            document.count { it == '}' },
            "unbalanced braces in\n$document",
        )
    }

    @Test
    fun `NetId NONE is refused like any other id that names nothing`() {
        val harness = QueryHarness()

        val failure = kotlin.test.assertFailsWith<dev.wildware.udea.agent.AgentToolException> {
            harness.detail.render(NetId.NONE)
        }

        assertEquals(
            dev.wildware.udea.agent.AgentErrorKind.NO_SUCH_ENTITY,
            failure.error.kind,
        )
    }
}
