package dev.wildware.udea.codegen.agent

import dev.wildware.udea.agent.StateModule
import dev.wildware.udea.agent.ToolModule
import dev.wildware.udea.codegen.fixtures.HealthAgentState
import dev.wildware.udea.codegen.fixtures.MatchClockAgentState
import dev.wildware.udea.codegen.fixtures.PlaygroundSetOverlaysTool
import dev.wildware.udea.codegen.fixtures.PlaygroundSetStanceTool
import dev.wildware.udea.codegen.fixtures.PlaygroundSpawnBlueprintTool
import dev.wildware.udea.codegen.fixtures.PlaygroundTagEntityTool
import dev.wildware.udea.codegen.fixtures.TimelineAdvanceTool
import dev.wildware.udea.codegen.fixtures.TimelineDescribeTool
import dev.wildware.udea.diagnostics.UdeaRules
import dev.wildware.udea.generated.CodegenFixturesUdeaRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The two agent facets, reached through the **generated** launcher registry (issue #202).
 *
 * Nothing less proves this mechanism. A facet that is substring-matched in a generated file can
 * name an interface that does not exist, or a member that does not implement it - both are
 * caught only by compiling the registry and reading it back, which is what this does.
 *
 * `udea-agent` is on this module's test classpath too, and its own registry deliberately has no
 * `ToolModule` facet (see `EngineToolModules`), so the fixtures are the only tool module here.
 */
class GeneratedAgentRegistryTest {

    private val toolModules: List<ToolModule> = CodegenFixturesUdeaRegistry.modules.filterIsInstance<ToolModule>()

    private val stateModules: List<StateModule> = CodegenFixturesUdeaRegistry.modules.filterIsInstance<StateModule>()

    @Test
    fun `the registry lists this module's ToolModule and it names every tool statically`() {
        assertEquals(listOf("CodegenFixtures"), toolModules.map { it.moduleName })
        // Ascending name, which is the order the merged manifest and the dispatch map are both
        // built in, so no consumer has to sort.
        assertEquals(
            listOf(
                PlaygroundSetOverlaysTool,
                PlaygroundSetStanceTool,
                // A toolset-qualified name sorts as the whole string, dot included, so `sim.*`
                // lands between `set_stance` and `spawn_blueprint` rather than in a block of
                // its own. The index is ordered, not grouped; the *manifest* is what groups.
                TimelineAdvanceTool,
                TimelineDescribeTool,
                PlaygroundSpawnBlueprintTool,
                PlaygroundTagEntityTool,
            ),
            toolModules.single().tools,
        )
        assertEquals(
            listOf(
                "set_overlays", "set_stance", "sim.advance", "sim.describe",
                "spawn_blueprint", "tag_entity",
            ),
            toolModules.single().tools.map { it.name },
        )
    }

    @Test
    fun `the registry lists this module's StateModule and it names every digest source`() {
        assertEquals(listOf("CodegenFixtures"), stateModules.map { it.moduleName })
        assertEquals(listOf(HealthAgentState, MatchClockAgentState), stateModules.single().states)
    }

    @Test
    fun `the facet hands back the same singletons the generated objects are`() {
        // Static naming, not construction: R8 keeps these because they are genuinely referenced,
        // and resolution costs a class-load rather than a reflective lookup.
        val tools = toolModules.single().tools
        assertSame(PlaygroundSpawnBlueprintTool, tools.single { it.name == "spawn_blueprint" })
    }

    @Test
    fun `every tool the facet publishes has a description a model can act on`() {
        // The description gate is a build error, so this cannot fail while the gate works - and
        // that is the point: it fails the moment somebody weakens the gate.
        for (tool in toolModules.single().tools) {
            assertTrue(
                tool.description.length >= UdeaRules.MIN_TOOL_DESCRIPTION,
                "${tool.name} is described in ${tool.description.length} characters",
            )
        }
    }

    @Test
    fun `no digest key is published twice across the module's state sources`() {
        val names = stateModules.single().states.flatMap { it.names }

        assertEquals(names.size, names.toSet().size, "duplicate digest keys in $names")
    }
}
