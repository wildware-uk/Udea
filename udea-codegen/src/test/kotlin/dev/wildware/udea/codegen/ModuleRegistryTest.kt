package dev.wildware.udea.codegen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The generated registries that replaced `ServiceLoader` discovery (issue #202, spec D7).
 *
 * A module gets one `<Module>ModuleRegistry` object, and it lists what the module contributes by
 * implementing the facet interface for each kind: `NetModule`, `ToolModule`, `StateModule`,
 * `LevelComponentModule`. A launcher gets one `<Module>UdeaRegistry` naming every module registry
 * on its classpath as a static reference.
 *
 * What these guard is the property the change exists for: **a module missing from a launcher's
 * registry is a build error**, where a missing `META-INF/services` line was a module that was
 * silently not there at run time. They run the real processor over throwaway sources, against
 * this module's test classpath - on which `udea-core`'s own generated `CoreModuleRegistry` really
 * exists, so a listed module that resolves and one that does not are both real cases.
 */
class ModuleRegistryTest {

    private val component = "Components.kt" to """
        package fixtures

        import dev.wildware.udea.annotations.Net
        import dev.wildware.udea.annotations.Replicated

        @Replicated
        class Zebra(@Net var stripes: Int = 0)

        @Replicated
        class Aardvark(@Net var snout: Float = 0f)
    """.trimIndent()

    private val everything = "Everything.kt" to """
        package fixtures

        import com.github.quillraven.fleks.Component
        import com.github.quillraven.fleks.ComponentType
        import dev.wildware.udea.annotations.AgentState
        import dev.wildware.udea.annotations.AgentTool
        import dev.wildware.udea.annotations.Net
        import dev.wildware.udea.annotations.Replicated
        import kotlinx.serialization.Serializable

        @Replicated
        class Aardvark(@Net var snout: Float = 0f)

        @Serializable
        class Saved(var value: Int = 0) : Component<Saved> {
            override fun type(): ComponentType<Saved> = Saved
            companion object : ComponentType<Saved>()
        }

        class Scoreboard {
            @AgentState(name = "kills")
            var kills: Int = 0

            @AgentTool(description = "Reset every counter on the scoreboard back to zero before a run.")
            fun reset() {
            }
        }
    """.trimIndent()

    private val nothing = "Nothing.kt" to """
        package fixtures

        class Plain
    """.trimIndent()

    private val allFacets = mapOf(
        CodegenOptions.NET_MODULE_SERVICE to "dev.wildware.udea.net.NetModule",
        CodegenOptions.TOOL_MODULE_SERVICE to "dev.wildware.udea.agent.ToolModule",
        CodegenOptions.STATE_MODULE_SERVICE to "dev.wildware.udea.agent.StateModule",
    )

    private fun run(
        workDir: File,
        source: Pair<String, String>,
        registryModules: String? = "Moba",
        extra: Map<String, String> = emptyMap(),
        components: String = "fixtures.Aardvark,fixtures.Zebra",
    ): ProcessorHarness.Run = ProcessorHarness.run(
        workDir,
        mapOf(source),
        buildMap {
            put(CodegenOptions.MODULE_NAME, "Moba")
            put(CodegenOptions.PROJECT_COMPONENTS, components)
            if (registryModules != null) put(CodegenOptions.REGISTRY_MODULES, registryModules)
            putAll(extra)
        },
    )

    /**
     * [source] with every whitespace run folded to one space.
     *
     * KotlinPoet wraps a long supertype list or initializer at a column it chooses, and where it
     * breaks a line is not what any assertion here is about.
     */
    private fun flattened(source: String): String = source.replace(Regex("""\s+"""), " ")

    // --- the module registry ------------------------------------------------------------------

    @Test
    fun `a module emits one registry object naming its replicators statically`(@TempDir workDir: File) {
        val run = run(workDir, component, extra = mapOf(CodegenOptions.NET_MODULE_SERVICE to "dev.wildware.udea.net.NetModule"))

        assertEquals(emptyList(), run.errors)
        val registry = flattened(run.generatedSource("MobaModuleRegistry.kt"))
        // An object, now that nothing constructs it reflectively: a launcher names it.
        assertTrue("public object MobaModuleRegistry : ModuleRegistry, NetModule" in registry, registry)
        assertTrue("moduleName: String = \"Moba\"" in registry, registry)
        // Ascending type id, which is ascending name: Aardvark is 0 and Zebra is 1.
        assertTrue("listOf(AardvarkReplicator, ZebraReplicator)" in registry, registry)
    }

    @Test
    fun `a registry implements the facet for every kind its module contributes`(@TempDir workDir: File) {
        val run = run(workDir, everything, extra = allFacets, components = "fixtures.Aardvark")

        assertEquals(emptyList(), run.errors)
        val registry = flattened(run.generatedSource("MobaModuleRegistry.kt"))
        assertTrue(
            "public object MobaModuleRegistry : ModuleRegistry, NetModule, ToolModule, StateModule, " +
                "LevelComponentModule" in registry,
            registry,
        )
        assertTrue("listOf(ScoreboardResetTool)" in registry, registry)
        assertTrue("listOf(ScoreboardAgentState)" in registry, registry)
        assertTrue("listOf(LevelComponent(Saved::class, Saved.serializer()))" in registry, registry)
    }

    @Test
    fun `a facet whose interface the build did not name is left off, not emitted against nothing`(
        @TempDir workDir: File,
    ) {
        // Generated code may only implement an interface that exists. `udea-agent` and
        // `udea-replay` declare tools and deliberately set no tool option, because their toolsets
        // need instances only a host can build.
        val run = run(workDir, everything, components = "fixtures.Aardvark")

        assertEquals(emptyList(), run.errors)
        val registry = flattened(run.generatedSource("MobaModuleRegistry.kt"))
        assertTrue("public object MobaModuleRegistry : ModuleRegistry, LevelComponentModule" in registry, registry)
        assertFalse("ToolModule" in registry || "StateModule" in registry || "NetModule" in registry, registry)
    }

    @Test
    fun `a module with nothing to list still emits its registry, so a launcher naming it compiles`(
        @TempDir workDir: File,
    ) {
        val run = run(workDir, nothing)

        assertEquals(emptyList(), run.errors)
        assertTrue(
            "public object MobaModuleRegistry : ModuleRegistry" in flattened(run.generatedSource("MobaModuleRegistry.kt")),
        )
        assertTrue(run.generatedFiles.any { it.name == "MobaUdeaRegistry.kt" }, "${run.generatedFiles}")
    }

    @Test
    fun `nothing is written where ServiceLoader would look`(@TempDir workDir: File) {
        val run = run(workDir, everything, extra = allFacets, components = "fixtures.Aardvark")

        assertEquals(emptyList(), run.errors)
        assertTrue(
            run.generatedResources.keys.none { it.startsWith("META-INF/") },
            "generated resources were ${run.generatedResources.keys}",
        )
    }

    // --- the launcher registry ----------------------------------------------------------------

    @Test
    fun `the launcher registry names every module registry statically, in sorted FQN order`(
        @TempDir workDir: File,
    ) {
        // Handed over unsorted, as nothing about an option string promises an order.
        val run = run(workDir, component, registryModules = "Moba,Core")

        assertEquals(emptyList(), run.errors)
        val launcher = flattened(run.generatedSource("MobaUdeaRegistry.kt"))
        assertTrue("public object MobaUdeaRegistry : UdeaRegistry" in launcher, launcher)
        assertTrue("listOf(CoreModuleRegistry, MobaModuleRegistry)" in launcher, launcher)
    }

    @Test
    fun `a listed module whose registry does not exist fails the build, naming the module`(
        @TempDir workDir: File,
    ) {
        // The case the whole change is for. Under ServiceLoader this module's tools, replicators
        // and level components were simply absent at run time, with a green build.
        val run = run(workDir, component, registryModules = "Core,Ghost,Moba")

        assertFalse(run.succeeded)
        val message = run.errors.single()
        assertTrue("dev.wildware.udea.generated.GhostModuleRegistry" in message, message)
        assertTrue("Ghost" in message && CodegenOptions.REGISTRY_MODULES in message, message)
        assertTrue(run.generatedFiles.none { it.name == "MobaUdeaRegistry.kt" }, "${run.generatedFiles}")
    }

    @Test
    fun `a module name with no launcher list is a build error rather than a module no launcher lists`(
        @TempDir workDir: File,
    ) {
        // A build script that sets `udea.moduleName` by hand, bypassing `udeaModule`, publishes no
        // module attribute - so no launcher would list it, and its registry would be generated and
        // never reached.
        val run = run(workDir, component, registryModules = null)

        assertFalse(run.succeeded)
        val message = run.errors.single()
        assertTrue(CodegenOptions.REGISTRY_MODULES in message, message)
        assertTrue("udeaModule" in message, message)
    }

    @Test
    fun `a launcher list that leaves out its own module is refused`(@TempDir workDir: File) {
        val run = run(workDir, component, registryModules = "Core")

        assertFalse(run.succeeded)
        val message = run.errors.single()
        assertTrue("Moba" in message && CodegenOptions.REGISTRY_MODULES in message, message)
    }
}
