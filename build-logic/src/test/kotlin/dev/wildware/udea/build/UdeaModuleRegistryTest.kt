package dev.wildware.udea.build

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The launcher list `udea-codegen` builds a `UdeaRegistry` from (issue #202), computed by a real
 * Gradle resolution.
 *
 * The list is the only thing standing between a module a game depends on and that module being
 * absent from the game's registry, and it is read off a resolved graph. A hand-built
 * `ResolvedComponentResult` would test the walk and not the part that matters: that the
 * attribute a module stamps on its variants is really there in the consumer's resolution.
 */
class UdeaModuleRegistryTest {

    /** A project that declares itself a Udea module and prints what its launcher list would be. */
    private fun module(name: String, dependencies: String): String =
        """
        import dev.wildware.udea.build.udeaModule

        plugins {
            `java-library`
            // Only so the build-logic classes are on this script's classpath.
            id("dev.wildware.udea.module-graph-check")
        }
        dependencies { $dependencies }
        val options = udeaModule("$name")
        tasks.register("printRegistryModules") {
            val modules = options.registryModules
            doLast { println("registryModules=" + modules.get()) }
        }
        """

    private fun printed(output: String): String =
        output.lineSequence().single { it.startsWith("registryModules=") }.removePrefix("registryModules=")

    @Test
    fun `a launcher lists itself and every module on its runtime classpath, transitively, and nothing else`(
        @TempDir root: File,
    ) {
        val fixture = GradleFixture(root)
            .project("core", module("Core", ""))
            .project("gas", module("Gas", "api(project(\":core\"))"))
            // On the classpath but not a Udea module: it must not appear, and must not break the walk.
            .project("plain", "plugins { `java-library` }\ndependencies { api(project(\":gas\")) }")
            .project("game", module("Game", "implementation(project(\":plain\"))"))

        val result = fixture.build(":game:printRegistryModules")

        // Gas and Core are reached only through `plain`, which declares nothing: two hops.
        assertEquals("Core,Game,Gas", printed(result.output))
    }

    @Test
    fun `a module on the compile classpath only is not in the launcher's registry`(@TempDir root: File) {
        // `udea-replay` takes `udea-agent` as compileOnly, so a game built on it does not ship the
        // agent surface. A registry that listed UdeaAgent would name a class the game's runtime
        // classpath does not have.
        val fixture = GradleFixture(root)
            .project("agent", module("Agent", ""))
            .project("replay", module("Replay", "compileOnly(project(\":agent\"))"))

        val result = fixture.build(":replay:printRegistryModules")

        assertEquals("Replay", printed(result.output))
    }

    @Test
    fun `the list survives the configuration cache being reused`(@TempDir root: File) {
        val fixture = GradleFixture(root)
            .project("core", module("Core", ""))
            .project("game", module("Game", "implementation(project(\":core\"))"))

        fixture.build(":game:printRegistryModules")
        val reused = fixture.build(":game:printRegistryModules")

        assertTrue("Reusing configuration cache" in reused.output, reused.output)
        assertEquals("Core,Game", printed(reused.output))
    }
}
