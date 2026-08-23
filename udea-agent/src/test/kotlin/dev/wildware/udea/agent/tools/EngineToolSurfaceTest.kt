package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentToolDef
import dev.wildware.udea.agent.ToolModule
import dev.wildware.udea.agent.dispatch.ToolIndex
import dev.wildware.udea.diagnostics.UdeaRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The whole engine tool surface, read the way a model reads it.
 *
 * Issue #67 has two halves. The **aggregation** half is the cross-module merge and the refusal
 * of a name published twice, which no KSP round can make because a round sees one module. The
 * **description** half is the gate on what reaches the model - and it is applied here at index
 * construction as well as in the processor, because the engine's own toolsets and any tool a
 * game writes by hand never pass through a KSP round at all, and a gate with a documented way
 * round it is not a gate.
 */
class EngineToolSurfaceTest {

    @Test
    fun `the four engine modules merge into one index with no name published twice`() {
        val harness = ToolsetHarness()

        val index = ToolIndex.builder()
            .let {
                EngineToolModules.wireAll(
                    it,
                    harness.worldTools,
                    harness.timeTools,
                    harness.eventTools,
                    harness.diagTools,
                )
            }
            .build()

        assertEquals(
            listOf("UdeaAgentDiag", "UdeaAgentEvents", "UdeaAgentTime", "UdeaAgentWorld"),
            index.moduleNames,
        )
        assertEquals(
            listOf(
                "diag.entity_counts",
                "diag.frame_report",
                "diag.memory",
                "diag.system_timings",
                "events.assert_event",
                "events.clear_events",
                "events.recent_events",
                "time.fast_forward",
                "time.list_snapshots",
                "time.pause",
                "time.resume",
                "time.rewind",
                "time.set_time_scale",
                "time.snapshot",
                "time.step",
                "world.describe_entity",
                "world.destroy_entity",
                "world.get_component",
                "world.list_blueprints",
                "world.list_components",
                "world.query_entities",
                "world.set_component_field",
                "world.spawn_blueprint",
            ),
            index.tools.map { it.name },
        )
    }

    @Test
    fun `two modules publishing one tool name are refused, naming both`() {
        // The check no KSP round can make: a round sees one module, so this is the first place
        // the whole classpath is visible at once.
        val harness = ToolsetHarness()
        val shadow = module("ShadowModule", WorldToolset.tools().filter { it.name == "world.query_entities" })

        val failure = assertFailsWith<IllegalStateException> {
            ToolIndex.builder()
                .module(EngineToolModules.World)
                .module(shadow)
                .toolset(harness.worldTools)
                .build()
        }

        assertTrue(failure.message!!.contains("world.query_entities"), failure.message!!)
        assertTrue(failure.message!!.contains("UdeaAgentWorld"), failure.message!!)
        assertTrue(failure.message!!.contains("ShadowModule"), failure.message!!)
    }

    @Test
    fun `a tool whose description is too short is refused under UDEA0008`() {
        val thin = module(
            "ThinModule",
            listOf(
                EngineToolDef<WorldToolset>(
                    name = "world.thin",
                    description = "spawns stuff",
                    owner = WorldToolset::class,
                ) { _, _ -> null },
            ),
        )
        val harness = ToolsetHarness()

        val failure = assertFailsWith<IllegalStateException> {
            ToolIndex.builder().module(thin).toolset(harness.worldTools).build()
        }

        assertTrue(failure.message!!.startsWith(UdeaRules.AGENT_TOOL_DESCRIPTION.id), failure.message!!)
        assertTrue(failure.message!!.contains("world.thin"), failure.message!!)
        assertTrue(
            failure.message!!.contains("when to reach for it"),
            "a rule must say what to write, not only what is wrong: ${failure.message}",
        )
    }

    @Test
    fun `an argument with no description is refused under UDEA0009`() {
        val thin = module(
            "UndescribedArgModule",
            listOf(
                EngineToolDef<WorldToolset>(
                    name = "world.undescribed",
                    description = "Does a thing and says clearly when you would want to do it.",
                    owner = WorldToolset::class,
                    args = listOf(agentArg("id", "integer", "")),
                ) { _, _ -> null },
            ),
        )
        val harness = ToolsetHarness()

        val failure = assertFailsWith<IllegalStateException> {
            ToolIndex.builder().module(thin).toolset(harness.worldTools).build()
        }

        assertTrue(failure.message!!.startsWith(UdeaRules.AGENT_ARG_DESCRIPTION.id), failure.message!!)
    }

    @Test
    fun `every engine description says what the tool does and when to reach for it`() {
        // Read as the model would, and asserted mechanically for the properties prose can be
        // checked for: long enough to carry an intent, and not merely the name in words.
        val all = EngineToolModules.ALL.flatMap { it.tools }

        for (tool in all) {
            assertTrue(
                tool.description.length >= UdeaRules.MIN_TOOL_DESCRIPTION,
                "${tool.name} is described in ${tool.description.length} characters",
            )
            val nameWords = tool.name.substringAfter('.').split('_').toSet()
            val descriptionWords = tool.description.lowercase()
                .split(Regex("[^a-z]+"))
                .filter { it.isNotEmpty() }
                .toSet()
            assertTrue(
                (descriptionWords - nameWords).size >= MIN_NEW_WORDS,
                "${tool.name} only restates its own name: ${tool.description}",
            )
            for (arg in tool.args) {
                assertTrue(arg.description.isNotBlank(), "${tool.name}.${arg.name} has no description")
            }
        }
    }

    @Test
    fun `every tool publishes a schema in the shape the bridge parser accepts`() {
        for (tool in EngineToolModules.ALL.flatMap { it.tools }) {
            val schema = tool.inputSchema
            assertTrue(schema.startsWith("{"), "${tool.name}: inputSchema must be an object")
            assertTrue(schema.contains("\"\$schema\":\"${ToolSchema.DIALECT}\""), "${tool.name}: $schema")
            assertTrue(schema.contains("\"additionalProperties\":false"), "${tool.name}: $schema")
            for (arg in tool.args) {
                assertTrue(schema.contains("\"${arg.name}\":{"), "${tool.name} omits ${arg.name}: $schema")
            }
            // A default is folded into the description and never emitted as `default`, which is
            // what the bridge does when it builds a schema itself.
            assertTrue(!schema.contains("\"default\":"), "${tool.name} emitted a default: $schema")
            for (arg in tool.args.filter { it.default != null }) {
                assertTrue(
                    schema.contains("(default ${arg.default})"),
                    "${tool.name}.${arg.name}'s default is not in its description: $schema",
                )
            }
        }
    }

    @Test
    fun `a contextual tool refuses to run without the context it declared it needs`() {
        val harness = ToolsetHarness()
        val def = EngineToolModules.Time.tools.single { it.name == "time.step" }

        @Suppress("UNCHECKED_CAST")
        val typed = def as AgentToolDef<TimeToolset>

        // Doing half the work would be worse than refusing: the step would never run and the
        // command would confirm anyway.
        assertFailsWith<UnsupportedOperationException> {
            typed.invoke(harness.timeTools, AgentCommand("time.step", mapOf("ticks" to "1")))
        }
    }

    private fun module(name: String, tools: List<AgentToolDef<*>>): ToolModule = object : ToolModule {
        override val moduleName: String = name
        override val tools: List<AgentToolDef<*>> = tools
    }

    private companion object {
        /**
         * Words a description must add beyond the ones already in the tool's own name.
         *
         * The token-overlap rule from issue #67, applied to prose rather than trusted: a
         * description that is the name spelled out tells the model nothing it did not have.
         */
        const val MIN_NEW_WORDS: Int = 10
    }
}
