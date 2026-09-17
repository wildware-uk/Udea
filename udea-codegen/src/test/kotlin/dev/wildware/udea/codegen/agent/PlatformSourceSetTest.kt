package dev.wildware.udea.codegen.agent

import dev.wildware.udea.codegen.CodegenOptions
import dev.wildware.udea.codegen.ProcessorHarness
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

/**
 * `udea.sourceSet`: a per-target KSP run beside the common one (issue #208).
 *
 * A multiplatform module that runs the processor over `commonMain` and also has an `@AgentTool`
 * in a platform source set needs a second, platform run - and KSP hands that run the common
 * sources as well. Left alone it re-emits every common tool and the module's registry and
 * manifest a second time, and the platform compilation then fails on redeclarations. The option
 * scopes the run to its own source set and leaves the module-level files to the common run.
 */
class PlatformSourceSetTest {

    private val common = "src/commonMain/kotlin/fixtures/Clock.kt" to """
        package fixtures

        import dev.wildware.udea.annotations.AgentTool

        class Clock {
            @AgentTool(description = "Stop the clock where it stands until it is started again.")
            fun stop() {
            }
        }
    """.trimIndent()

    private val jvm = "src/jvmMain/kotlin/fixtures/Disk.kt" to """
        package fixtures

        import dev.wildware.udea.annotations.AgentTool

        class Disk {
            @AgentTool(description = "Flush every pending write to the disk before returning.")
            fun flush() {
            }
        }
    """.trimIndent()

    private fun run(workDir: File, sourceSet: String?): ProcessorHarness.Run = ProcessorHarness.run(
        workDir,
        mapOf(common, jvm),
        buildMap {
            put(CodegenOptions.MODULE_NAME, "Fixtures")
            put(CodegenOptions.REGISTRY_MODULES, "Fixtures")
            if (sourceSet != null) put(CodegenOptions.SOURCE_SET, sourceSet)
        },
    )

    @Test
    fun `a platform run generates the tools of its own source set and nothing else`(@TempDir workDir: File) {
        val run = run(workDir, sourceSet = "jvmMain")

        assertEquals(emptyList(), run.errors)
        assertEquals(listOf("DiskFlushTool.kt"), run.generatedFiles.map { it.name })
        assertEquals(emptyMap(), run.generatedResources, "the manifest belongs to the common run")
    }

    @Test
    fun `without the option every source is processed and the module files are written`(@TempDir workDir: File) {
        // The control: the same sources, unscoped, are what a common run sees, and they produce
        // both tools, the registries and the manifest. A scoped run that emitted nothing at all
        // would pass the test above; it would not pass this pair.
        val run = run(workDir, sourceSet = null)

        assertEquals(emptyList(), run.errors)
        assertEquals(
            listOf("ClockStopTool.kt", "DiskFlushTool.kt", "FixturesModuleRegistry.kt", "FixturesUdeaRegistry.kt"),
            run.generatedFiles.map { it.name }.sorted(),
        )
        assertEquals(listOf("udea/Fixtures-agent-tools.json"), run.generatedResources.keys.toList())
    }

    @Test
    fun `a malformed source set name is refused rather than matching nothing`(@TempDir workDir: File) {
        // A name that matched no directory would silently generate nothing, and the platform
        // compilation would then fail naming a missing tool object rather than the option.
        val run = run(workDir, sourceSet = "src/jvmMain")

        assertEquals(1, run.errors.size, "${run.errors}")
        assertEquals(true, CodegenOptions.SOURCE_SET in run.errors.single(), run.errors.single())
    }
}
