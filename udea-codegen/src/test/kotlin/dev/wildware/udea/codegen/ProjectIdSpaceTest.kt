package dev.wildware.udea.codegen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * **One component type id means one component across the whole build.**
 *
 * A KSP run sees one Gradle module. A processor that assigns ids from the symbols in front of
 * it therefore hands out `0, 1, 2, …` *per module*, so `udea-gas`'s first component and
 * `moba`'s first component are both `ComponentTypeId(0)` — and two peers decode each other's
 * packets as the wrong component type, silently, with the connect-time `protoHash` reporting
 * agreement. That is the same class of defect as the ordering dependence in the generator
 * being retired, and spec 5 answers it with one sorted-FQN assignment for the whole project.
 *
 * The list reaches the processor as `udea.projectComponents`, computed by the build from
 * resolved artifacts and passed in as data — never discovered by a classpath scan, which is
 * the rule `udea.netModuleService` already follows.
 *
 * Every test here runs the real processor twice, once per simulated module, and compares what
 * the two runs minted.
 */
class ProjectIdSpaceTest {

    private val gas = "Gas.kt" to """
        package gas

        import dev.wildware.udea.annotations.Net
        import dev.wildware.udea.annotations.Replicated

        @Replicated
        class Shield(@Net var charges: Int = 0)

        @Replicated
        class Stun(@Net var remaining: Int = 0)
    """.trimIndent()

    private val moba = "Moba.kt" to """
        package moba

        import dev.wildware.udea.annotations.Net
        import dev.wildware.udea.annotations.Replicated

        @Replicated
        class Health(@Net var current: Float = 0f)

        @Replicated
        class Wave(@Net var index: Int = 0)
    """.trimIndent()

    /** The whole project, sorted — what the Gradle plugin computes from resolved artifacts. */
    private val projectComponents = listOf(
        "gas.Shield",
        "gas.Stun",
        "moba.Health",
        "moba.Wave",
    )

    private fun run(
        workDir: File,
        source: Pair<String, String>,
        moduleName: String,
        project: List<String>?,
    ): ProcessorHarness.Run = ProcessorHarness.run(
        workDir,
        mapOf(source),
        buildMap {
            put(CodegenOptions.MODULE_NAME, moduleName)
            if (project != null) {
                put(CodegenOptions.PROJECT_COMPONENTS, project.joinToString(","))
            }
        },
    )

    /** `component <id> <fqn>` lines from a module's generated lock. */
    private fun lockedIds(run: ProcessorHarness.Run, moduleName: String): Map<String, Int> {
        val lock = run.generatedResources.getValue("udea/$moduleName-net-protocol.lock")
        return lock.lines()
            .map(String::trim)
            .filter { it.startsWith("component ") }
            .associate { line ->
                val parts = line.split(' ')
                parts[2] to parts[1].toInt()
            }
    }

    @Test
    fun `two modules given the project component list mint disjoint ids`(
        @TempDir first: File,
        @TempDir second: File,
    ) {
        val gasRun = run(first, gas, "Gas", projectComponents)
        val mobaRun = run(second, moba, "Moba", projectComponents)

        assertEquals(emptyList(), gasRun.errors)
        assertEquals(emptyList(), mobaRun.errors)
        assertEquals(mapOf("gas.Shield" to 0, "gas.Stun" to 1), lockedIds(gasRun, "Gas"))
        assertEquals(mapOf("moba.Health" to 2, "moba.Wave" to 3), lockedIds(mobaRun, "Moba"))
    }

    @Test
    fun `a module emitting a protocol without the project list is refused, not numbered locally`(
        @TempDir first: File,
        @TempDir second: File,
    ) {
        // The finding this test exists for: falling back to local numbering here is silent and
        // internally consistent. Each module's lock would be self-consistent, each protoHash
        // would agree with a peer built the same way, and gas.Shield and moba.Health would
        // both be ComponentTypeId(0) on the wire. So the combination "I emit a protocol" and
        // "nobody told me the id space" is a build failure, not a default.
        val gasRun = run(first, gas, "Gas", project = null)
        val mobaRun = run(second, moba, "Moba", project = null)

        for (run in listOf(gasRun, mobaRun)) {
            assertFalse(run.succeeded)
            val message = run.errors.single()
            assertTrue(CodegenOptions.PROJECT_COMPONENTS in message, message)
            assertTrue("net-components.lock" in message, message)
            assertTrue(run.generatedFiles.isEmpty(), "nothing may be emitted with no id space")
            assertTrue(run.generatedResources.isEmpty(), "no lock may be emitted with no id space")
        }
    }

    @Test
    fun `a module that emits no protocol may still be numbered from its own components`(
        @TempDir workDir: File,
    ) {
        // The one configuration where module-local ids are legal, and the reason the refusal
        // above is keyed on `udea.moduleName` rather than on there being components at all:
        // with no module name there is no lock, no protoHash and no ServiceLoader index, so
        // the module contributes nothing to any protocol and its ids reach no wire.
        val run = ProcessorHarness.run(workDir, mapOf(gas))

        assertEquals(emptyList(), run.errors)
        assertEquals(
            listOf("ShieldReplicator.kt", "StunReplicator.kt"),
            run.generatedFiles.map { it.name },
        )
        assertEquals(emptyMap(), run.generatedResources)
    }

    @Test
    fun `the ids do not depend on which module was processed first`(
        @TempDir gasFirst: File,
        @TempDir mobaSecond: File,
        @TempDir mobaFirst: File,
        @TempDir gasSecond: File,
    ) {
        // Gradle evaluates projects in whatever order it pleases, and a CI machine need not
        // agree with a developer's laptop. If build order could move an id, `net-protocol.lock`
        // would differ between two builds of identical sources.
        val forwards = listOf(
            lockedIds(run(gasFirst, gas, "Gas", projectComponents), "Gas"),
            lockedIds(run(mobaSecond, moba, "Moba", projectComponents), "Moba"),
        )
        val backwards = listOf(
            lockedIds(run(gasSecond, gas, "Gas", projectComponents), "Gas"),
            lockedIds(run(mobaFirst, moba, "Moba", projectComponents), "Moba"),
        )

        assertEquals(forwards, backwards)
    }

    @Test
    fun `a component missing from the project list is a located error, not a private id`(
        @TempDir workDir: File,
    ) {
        // A stale list is the realistic accident: a component is added and the list is not
        // recomputed. Falling back to local numbering there is precisely the silent divergence
        // the option exists to prevent, so it is refused at the symbol instead.
        val run = run(workDir, gas, "Gas", projectComponents - "gas.Stun")

        assertFalse(run.succeeded)
        val diagnostic = run.errorDiagnostics.single()
        assertTrue("gas.Stun" in diagnostic.message, diagnostic.message)
        assertTrue(CodegenOptions.PROJECT_COMPONENTS in diagnostic.message, diagnostic.message)
        assertEquals("Gas.kt", diagnostic.file, "the error must point at the component")
        assertTrue(run.generatedFiles.isEmpty(), "nothing may be emitted under a wrong id space")
    }

    @Test
    fun `a project list naming components this module does not compile still numbers correctly`(
        @TempDir workDir: File,
    ) {
        // The ordinary case for every module but the last: the id space is bigger than the
        // module, and the module's own components sit at their project-wide positions.
        val run = run(workDir, moba, "Moba", projectComponents)

        assertEquals(emptyList(), run.errors)
        val protocol = run.generatedSource("MobaNetProtocol.kt")
        assertTrue("const val COMPONENT_COUNT: Int = 2" in protocol, protocol)
        assertTrue(
            "ComponentTypeId(2)" in run.generatedSource("HealthReplicator.kt"),
            "moba.Health is third in the project sort, so it is id 2 even in a module of two",
        )
    }
}
