package dev.wildware.udea.agent.query

import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentToolException
import dev.wildware.udea.core.identity.NetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The generation counter, exercised the way it will actually fail.
 *
 * An agent reads a query result, thinks, and comes back with an id some seconds later. In
 * between, entities died and the slots were reused. Without a generation the id resolves to
 * whatever now occupies the slot and the agent mutates the wrong entity - a use-after-free that
 * presents as a gameplay bug on an unrelated unit, with nothing in the transcript pointing at
 * the agent.
 *
 * This has to be green before any tool can mutate through an id, which is why it lands with the
 * engine and not with the world toolset.
 */
class EntityIdentityTest {

    private fun idsIn(document: String): List<Int> =
        Regex("\"id\":(\\d+)").findAll(document).map { it.groupValues[1].toInt() }.toList()

    @Test
    fun `every id in a result resolves back to the same entity after 100 ticks`() {
        val harness = QueryHarness()
        val spawned = List(5) { harness.spawn(health = 100f + it) }
        val document = harness.query(limit = 10)

        harness.tick(100)

        for (raw in idsIn(document)) {
            val netId = NetId.ofRaw(raw)
            val entity = harness.engine.resolve(netId)
            assertSame(harness.entityOf(netId), entity, "id $raw stopped resolving")
        }
        assertEquals(spawned.map { it.raw }, idsIn(document))
    }

    @Test
    fun `an id held across a destroy and a respawn of its slot is detected, not aliased`() {
        val harness = QueryHarness()
        val victim = harness.spawn(health = 42f)
        val stale = NetId.ofRaw(idsIn(harness.query(limit = 10)).single())
        assertEquals(victim.raw, stale.raw)

        harness.tick(50)
        harness.destroy(victim)
        harness.tick(50)
        val respawned = harness.spawn(health = 999f)

        // The FIFO free list hands the same index straight back here, which is precisely the
        // aliasing case: same index, different generation.
        assertEquals(stale.index, respawned.index)
        assertNotEquals(stale.generation, respawned.generation)
        assertNull(harness.engine.resolve(stale), "a stale id must resolve to nothing")
        assertSame(harness.entityOf(respawned), harness.engine.resolve(respawned))
    }

    @Test
    fun `describing a stale id is a typed no_such_entity, not a description of its successor`() {
        val harness = QueryHarness()
        val victim = harness.spawn(health = 42f)
        harness.destroy(victim)
        val respawned = harness.spawn(health = 999f)
        assertEquals(victim.index, respawned.index)

        val failure = assertFailsWith<AgentToolException> { harness.detail.render(victim) }

        assertEquals(AgentErrorKind.NO_SUCH_ENTITY, failure.error.kind)
        assertTrue(failure.error.message.contains("recycled"), failure.error.message)
        // And the live id still works, so the refusal is about staleness and not about the slot.
        assertTrue(
            harness.detail.render(respawned).contains("""{"name":"current","value":999"""),
            harness.detail.render(respawned),
        )
    }

    @Test
    fun `a destroyed entity leaves the result set`() {
        val harness = QueryHarness()
        val keep = harness.spawn()
        val drop = harness.spawn()

        harness.destroy(drop)
        harness.tick(100)

        val document = harness.query(limit = 10)
        assertEquals(listOf(keep.raw), idsIn(document))
        assertTrue(document.contains("\"total\":1"), document)
    }

    @Test
    fun `a rendered id round-trips through the NetId word`() {
        val harness = QueryHarness()
        harness.spawn()
        harness.destroy(NetId.of(0, 0))
        val recycled = harness.spawn()

        val raw = idsIn(harness.query(limit = 10)).single()

        // The rendered number is the packed word, generation included - not the index. Rendering
        // the index would produce 0 for both the dead entity and its successor.
        assertEquals(recycled.raw, raw)
        assertEquals(recycled, NetId.ofRaw(raw))
        assertEquals(1, NetId.ofRaw(raw).generation)
    }
}
