package dev.wildware.udea.codegen.agent

import dev.wildware.udea.agent.StateModule
import dev.wildware.udea.agent.ToolModule
import dev.wildware.udea.codegen.fixtures.HealthAgentState
import dev.wildware.udea.codegen.fixtures.MatchClockAgentState
import dev.wildware.udea.codegen.fixtures.PlaygroundSetStanceTool
import dev.wildware.udea.codegen.fixtures.PlaygroundSpawnBlueprintTool
import dev.wildware.udea.codegen.fixtures.PlaygroundTagEntityTool
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The two generated indexes, loaded through a **real** `ServiceLoader` off a real classpath.
 *
 * Nothing less proves this mechanism. An index that is substring-matched in a generated file
 * can name a service that does not exist, or be emitted as a Kotlin `object` whose constructor
 * `ServiceLoader` cannot call — both compile, and both fail on first use at run time with a
 * green build. That exact mistake was found in the `NetModule` half only when a test loaded it;
 * so the agent half is loaded here on day one rather than after it has shipped.
 */
class GeneratedAgentIndexServiceTest {

    @Test
    fun `ServiceLoader finds this module's ToolModule and it names every tool statically`() {
        val modules = ServiceLoader.load(ToolModule::class.java).toList()

        assertEquals(listOf("CodegenFixtures"), modules.map { it.moduleName })
        // Ascending name, which is the order the merged manifest and the dispatch map are both
        // built in, so no consumer has to sort.
        assertEquals(
            listOf(PlaygroundSetStanceTool, PlaygroundSpawnBlueprintTool, PlaygroundTagEntityTool),
            modules.single().tools,
        )
        assertEquals(
            listOf("set_stance", "spawn_blueprint", "tag_entity"),
            modules.single().tools.map { it.name },
        )
    }

    @Test
    fun `ServiceLoader finds this module's StateModule and it names every digest source`() {
        val modules = ServiceLoader.load(StateModule::class.java).toList()

        assertEquals(listOf("CodegenFixtures"), modules.map { it.moduleName })
        assertEquals(listOf(HealthAgentState, MatchClockAgentState), modules.single().states)
    }

    @Test
    fun `the index hands back the same singletons the generated objects are`() {
        // Static naming, not construction: R8 keeps these because they are genuinely referenced,
        // and resolution costs a class-load rather than a reflective lookup.
        val tools = ServiceLoader.load(ToolModule::class.java).single().tools
        assertSame(PlaygroundSpawnBlueprintTool, tools.single { it.name == "spawn_blueprint" })
    }

    @Test
    fun `every tool the index publishes has a description a model can act on`() {
        // The description gate is a build error, so this cannot fail while the gate works - and
        // that is the point: it fails the moment somebody weakens the gate.
        for (tool in ServiceLoader.load(ToolModule::class.java).single().tools) {
            assertTrue(
                tool.description.length >= dev.wildware.udea.diagnostics.UdeaRules.MIN_TOOL_DESCRIPTION,
                "${tool.name} is described in ${tool.description.length} characters",
            )
        }
    }

    @Test
    fun `no digest key is published twice across the module's state sources`() {
        val names = ServiceLoader.load(StateModule::class.java).single().states.flatMap { it.names }

        assertEquals(names.size, names.toSet().size, "duplicate digest keys in $names")
    }
}
