package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.Health
import dev.wildware.udea.agent.Transform
import dev.wildware.udea.core.identity.NetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The toolset every Udea game gets for free, exercised only through the bridge.
 *
 * Nothing here calls a function on [WorldToolset]. Every assertion goes through
 * `SimHarness.call`, which submits to `AgentBridge` and lets `AgentRuntime` post it to the
 * `SimBarrier` - so what is under test is the surface an agent actually reaches, not a Kotlin
 * method that happens to sit behind it.
 */
class WorldToolsetTest {

    @Test
    fun `spawn_blueprint returns a NetId and the entity is immediately queryable`() {
        val harness = ToolsetHarness()

        val spawned = harness.ok("world.spawn_blueprint", "blueprint" to "grunt", "x" to "12", "y" to "-4")

        val raw = Regex("\"id\":(-?\\d+)").find(spawned)?.groupValues?.get(1)?.toInt()
        val netId = NetId.ofRaw(assertNotNull(raw, "spawn_blueprint returned no id: $spawned"))
        assertTrue(spawned.contains("\"blueprint\":\"grunt\""), spawned)

        // A typed result, not an event-log string: the id came back in the answer, and the
        // entity is live now rather than one tick later.
        val entity = assertNotNull(harness.netIds.resolveOrNull(netId), "the NetId does not resolve")
        with(harness.world) {
            assertEquals(40f, entity[Health].current)
            assertEquals(12f, entity[Transform].position.x)
            assertEquals(-4f, entity[Transform].position.y)
        }

        val found = harness.ok("world.query_entities", "with" to "Health", "fields" to "health.current")
        assertTrue(found.contains("\"id\":${netId.raw}"), found)
    }

    @Test
    fun `spawn_blueprint on an unknown name is a typed error with a did-you-mean`() {
        val harness = ToolsetHarness()

        val error = harness.failure("world.spawn_blueprint", "blueprint" to "grubt")

        assertEquals(WorldToolset.NO_SUCH_BLUEPRINT, error.kind)
        assertTrue(error.message.contains("did you mean grunt?"), error.message)
    }

    @Test
    fun `set_component_field writes a writable field and reports both values`() {
        val harness = ToolsetHarness()
        val netId = harness.place(health = 100f)

        val result = harness.ok(
            "world.set_component_field",
            "id" to netId.raw.toString(),
            "component" to "Health",
            "field" to "current",
            "value" to "37.5",
        )

        assertTrue(result.contains("\"previous\":100"), result)
        assertTrue(result.contains("\"value\":37.5"), result)
        with(harness.world) {
            assertEquals(37.5f, assertNotNull(harness.netIds.resolveOrNull(netId))[Health].current)
        }
    }

    @Test
    fun `set_component_field refuses a field that is not agent-writable and names the alternative`() {
        val harness = ToolsetHarness()
        val netId = harness.place(team = 3)
        val before = harness.ok("world.describe_entity", "id" to netId.raw.toString())

        // `agentWritable = false` is the default on @Net (spec 5), so Team is read-only.
        val error = harness.failure(
            "world.set_component_field",
            "id" to netId.raw.toString(),
            "component" to "Team",
            "field" to "team",
            "value" to "1",
        )

        assertEquals(WorldToolset.FIELD_NOT_WRITABLE, error.kind)
        assertTrue(error.message.contains("Team.team"), error.message)
        assertTrue(
            error.message.contains("agentWritable = true"),
            "a refusal must say what to do next: ${error.message}",
        )
        // And nothing moved: a refusal is a refusal, not a partial write.
        assertEquals(before, harness.ok("world.describe_entity", "id" to netId.raw.toString()))
        assertEquals(0L, harness.worldTools.mutations, "a refused write must not be audited")
    }

    @Test
    fun `a refusal on the GAS-owned component names the gas tool`() {
        // The engine has no Attributes component in this fixture, so the mapping is asserted on
        // the message the refusal builds - which is the part an agent reads.
        val harness = ToolsetHarness()
        val netId = harness.place()

        val error = harness.failure(
            "world.set_component_field",
            "id" to netId.raw.toString(),
            "component" to "Champion",
            "field" to "level",
            "value" to "9",
        )

        assertEquals(WorldToolset.FIELD_NOT_WRITABLE, error.kind)
    }

    @Test
    fun `every successful mutation writes exactly one audit entry`() {
        val harness = ToolsetHarness()
        val netId = harness.place()

        harness.ok(
            "world.set_component_field",
            "id" to netId.raw.toString(),
            "component" to "Health",
            "field" to "current",
            "value" to "12",
        )

        val audits = harness.bridge.events.toList().filter { it.startsWith("agent_mutation:") }
        assertEquals(1, audits.size, "expected exactly one audit entry, got $audits")
        val entry = audits.single()
        assertTrue(entry.contains("world.set_component_field"), entry)
        assertTrue(entry.contains("#${netId.index}@${netId.generation}"), entry)
        assertTrue(entry.contains("Health.current=12"), entry)
    }

    @Test
    fun `destroy_entity removes it and a second destroy answers no_such_entity`() {
        val harness = ToolsetHarness()
        val netId = harness.place()

        harness.ok("world.destroy_entity", "id" to netId.raw.toString())

        assertEquals(null, harness.netIds.resolveOrNull(netId))
        val error = harness.failure("world.destroy_entity", "id" to netId.raw.toString())
        assertEquals(AgentErrorKind.NO_SUCH_ENTITY, error.kind)
    }

    @Test
    fun `a stale generation is refused rather than addressing the recycled slot`() {
        val harness = ToolsetHarness()
        val first = harness.place(health = 10f)
        harness.ok("world.destroy_entity", "id" to first.raw.toString())
        val second = harness.place(health = 90f)

        val error = harness.failure("world.describe_entity", "id" to first.raw.toString())

        assertEquals(AgentErrorKind.NO_SUCH_ENTITY, error.kind)
        // The slot really was recycled, which is what makes the refusal meaningful.
        assertEquals(first.index, second.index)
        assertTrue(harness.ok("world.describe_entity", "id" to second.raw.toString()).contains("\"value\":90"))
    }

    @Test
    fun `list_components publishes the schema an agent needs to write a query`() {
        val harness = ToolsetHarness()

        val listed = harness.ok("world.list_components")

        assertTrue(listed.contains("\"name\":\"Health\""), listed)
        assertTrue(listed.contains("\"name\":\"position.x\""), listed)
        // The mask and the writability sit beside every field, so an agent knows before it asks.
        assertTrue(listed.contains("\"mask\":\"net\""), listed)
        assertTrue(listed.contains("\"mask\":\"sim\""), listed)
        assertTrue(listed.contains("\"agentWritable\":true"), listed)
        assertTrue(listed.contains("\"agentWritable\":false"), listed)
    }

    @Test
    fun `list_blueprints names what can be spawned`() {
        val harness = ToolsetHarness()

        val listed = harness.ok("world.list_blueprints")

        assertEquals("""{"spawnable":true,"blueprints":["champion","grunt"]}""", listed)
    }

    @Test
    fun `get_component refuses a component the entity does not carry`() {
        val harness = ToolsetHarness()
        val netId = harness.ok("world.spawn_blueprint", "blueprint" to "grunt")
            .let { Regex("\"id\":(-?\\d+)").find(it)!!.groupValues[1].toInt() }

        val error = harness.failure(
            "world.get_component",
            "id" to netId.toString(),
            "component" to "Champion",
        )

        assertEquals(AgentErrorKind.NO_SUCH_FIELD, error.kind)
        assertTrue(error.message.contains("describe_entity"), error.message)
    }

    @Test
    fun `a mutation is invisible to a system on the tick it was submitted on`() {
        // The SimBarrier guarantee, observed through the agent surface: the command is drained
        // onto the barrier and applied at the top of the *next* step, so nothing that ran
        // during the submitting tick could have seen it.
        val harness = ToolsetHarness()
        val netId = harness.place(health = 100f)
        val entity = assertNotNull(harness.netIds.resolveOrNull(netId))

        val submission = harness.bridge.submit(
            dev.wildware.udea.agent.AgentCommand(
                "world.set_component_field",
                mapOf(
                    "id" to netId.raw.toString(),
                    "component" to "Health",
                    "field" to "current",
                    "value" to "1",
                ),
            ),
        )

        // Submitted and not yet drained: the world has not changed.
        with(harness.world) { assertEquals(100f, entity[Health].current) }
        assertFalse(harness.bridge.completedCommandId() >= submission.commandId)

        harness.sim.step(1)

        with(harness.world) { assertEquals(1f, entity[Health].current) }
        assertTrue(harness.bridge.completedCommandId() >= submission.commandId)
    }

    @Test
    fun `a bad value for a typed field is a typed bad_argument naming the field`() {
        val harness = ToolsetHarness()
        val netId = harness.place()

        val error = harness.failure(
            "world.set_component_field",
            "id" to netId.raw.toString(),
            "component" to "Health",
            "field" to "current",
            "value" to "4o",
        )

        assertEquals(AgentErrorKind.BAD_ARGUMENT, error.kind)
        assertTrue(error.message.contains("Health.current"), error.message)
        assertTrue(error.message.contains("Float"), error.message)
    }
}
