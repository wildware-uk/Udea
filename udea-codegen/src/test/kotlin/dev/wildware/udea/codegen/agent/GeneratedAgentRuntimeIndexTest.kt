package dev.wildware.udea.codegen.agent

import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.dispatch.ToolIndex
import dev.wildware.udea.agent.state.AgentStateIndex
import dev.wildware.udea.agent.state.GameStateSink
import dev.wildware.udea.codegen.fixtures.Health
import dev.wildware.udea.codegen.fixtures.MatchClock
import dev.wildware.udea.codegen.fixtures.MatchPhase
import dev.wildware.udea.codegen.fixtures.Playground
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The whole agent seam, end to end, with nothing hand-written in the middle.
 *
 * `GeneratedAgentIndexServiceTest` proves `ServiceLoader` finds the generated indexes;
 * `GeneratedToolDispatchTest` proves a generated dispatcher converts its arguments. Neither
 * proves the two halves *join*: until `udea-agent` shipped `ToolIndex` and `AgentStateIndex`,
 * generated tools compiled, loaded, and could not be called by any shipped code, and generated
 * `@AgentState` writers could not reach a digest.
 *
 * So this test does what a host does and nothing else: discover through `ServiceLoader`, register
 * the instances, build, and call. Every type in the path is either generated here or declared in
 * `udea-agent`'s `src/main`.
 */
class GeneratedAgentRuntimeIndexTest {

    @Test
    fun `a discovered tool module dispatches to the toolset a host registered`() {
        val playground = Playground()
        val index = ToolIndex.builder().discover().toolset(playground).build()

        assertEquals(listOf("CodegenFixtures"), index.moduleNames)
        assertEquals(listOf("set_stance", "spawn_blueprint", "tag_entity"), index.tools.map { it.name })

        val result = index.invoke(
            AgentCommand("spawn_blueprint", mapOf("blueprint" to "creep_melee", "count" to "3")),
        )

        // 3 is `count * (scale ?: 1)`, so it can only have come through the generated coercion.
        assertEquals("3", assertIs<AgentResult.Ok>(result).json)
        assertEquals(listOf("creep_melee", "creep_melee", "creep_melee"), playground.spawned)
    }

    @Test
    fun `the owner a host binds on is the one the emitter wrote`() {
        // Not an identity check for its own sake: `owner` is what lets an index hold
        // `AgentToolDef<*>` and still find the right receiver, and an emitter that wrote the
        // wrong class here would fail only at a call, with a ClassCastException.
        val tools = ToolIndex.builder().discover().toolset(Playground()).build().tools

        assertTrue(tools.isNotEmpty(), "nothing was discovered, so nothing below is checked")
        assertEquals(
            listOf(Playground::class),
            tools.map { it.owner }.distinct(),
            "every fixture tool is declared on Playground",
        )
    }

    @Test
    fun `a discovered state module publishes the game's scalars from the live instances`() {
        val health = Health()
        health.deaths = 4
        health.current = 62.5f
        val clock = MatchClock()
        clock.phase = MatchPhase.Running
        clock.elapsedTicks = 91
        val index = AgentStateIndex.builder().discover().source(health).source(clock).build()

        val sink = RecordingSink()
        index.publish(sink)

        assertEquals(index.names, sink.written.map { it.substringBefore('=') }.sorted())
        assertTrue("deaths=4" in sink.written, sink.written.toString())
        assertTrue("health=62.5" in sink.written, sink.written.toString())
        // By constant name, not by ordinal: `Running` is ordinal 1 and would print as `1`.
        assertTrue("phase=Running" in sink.written, sink.written.toString())
        assertTrue("elapsedTicks=91" in sink.written, sink.written.toString())
    }

    @Test
    fun `the digest keys the index reports are the ones the generated sources declare`() {
        val index = AgentStateIndex.builder()
            .discover()
            .source(Health())
            .source(MatchClock())
            .build()

        // `deaths` is @AgentState only and `health` is @Net *and* @AgentState: both reach the
        // digest, and neither is in the replicated field space (AgentStateIsolationTest).
        assertTrue(
            listOf("deaths", "health").all { it in index.names },
            "expected the Health keys in ${index.names}",
        )
    }

    private class RecordingSink : GameStateSink {
        val written: MutableList<String> = ArrayList()

        override fun put(name: String, value: Int) {
            written += "$name=$value"
        }

        override fun put(name: String, value: Long) {
            written += "$name=$value"
        }

        override fun put(name: String, value: Float) {
            written += "$name=$value"
        }

        override fun put(name: String, value: Boolean) {
            written += "$name=$value"
        }

        override fun put(name: String, value: String?) {
            written += "$name=$value"
        }
    }
}
