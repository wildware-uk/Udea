package dev.wildware.udea.codegen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * What makes `ksp.incremental=true` safe to turn back on.
 *
 * KSP decides how much to reprocess from two things the processor declares, and nothing else:
 *
 * 1. the **`Dependencies`** each output is written with. An *isolating* output
 *    (`aggregating = false`, one source file) is invalidated only when that one file changes;
 *    an *aggregating* output is invalidated by any change anywhere in the module.
 * 2. the **name** of each output. A name that varies between runs — a timestamp, a hash, a
 *    round number — means the previous build's file is never recognised as this build's, so
 *    every build produces a new file and deletes nothing.
 *
 * The generator this replaces got both wrong: every file was `Dependencies(aggregating = true)`
 * and the module index was named `UdeaSerializerRegistry_${'$'}{System.currentTimeMillis()}`, so
 * `ksp.incremental=false` was not a preference, it was the only setting under which the build
 * worked at all.
 *
 * The naming half is asserted behaviourally, by running the processor. The dependency half
 * cannot be — KSP's own incremental bookkeeping is what consumes it, and the standalone runner
 * the harness drives does not do incremental processing — so it is audited on the source that
 * declares it. That audit is exact rather than approximate: there are only two `Dependencies`
 * constructions in the processor, and this pins which function each belongs to.
 */
class IncrementalProcessingTest {

    private val processorSource: String by lazy {
        ModuleRoot.file("src/main/kotlin/dev/wildware/udea/codegen/UdeaSymbolProcessor.kt")
            .also { assertTrue(it.isFile, "no processor source at ${it.absolutePath}") }
            .readText()
    }

    /** The body of a `fun name(` declaration, up to the next declaration at the same indent. */
    private fun functionBody(name: String): String {
        val header = Regex("""\n {4}(?:private |override )*fun $name\(""").find(processorSource)
            ?: error("no function `$name` in UdeaSymbolProcessor.kt")
        val rest = processorSource.substring(header.range.last)
        val next = Regex("""\n {4}(?:private |override )*fun \w+\(""").find(rest, startIndex = 1)
        return if (next == null) rest else rest.substring(0, next.range.first)
    }

    @Test
    fun `there are exactly two dependency declarations, and each is where it belongs`() {
        assertEquals(
            1,
            Regex("""Dependencies\(aggregating = false""").findAll(processorSource).count(),
            "a second isolating declaration means a second place the rule can be got wrong",
        )
        assertEquals(
            1,
            Regex("""Dependencies\(aggregating = true""").findAll(processorSource).count(),
            "more than one aggregating group per module is what makes every edit a full rebuild",
        )
    }

    @Test
    fun `the per-component output is isolating`() {
        // The whole benefit: editing one component reprocesses one component. Flipping this to
        // aggregating is invisible in every other test in the repository — the generated code
        // is byte-identical — and costs a full module reprocess on every keystroke.
        val body = functionBody("writeIsolating")

        assertTrue("aggregating = false" in body, body)
        assertTrue("createNewFile(" in body, body)
    }

    @Test
    fun `only the module-level outputs are aggregating`() {
        // One construction site, in `aggregating`, and every module-level writer goes through
        // it. A second writer with its own `Dependencies(...)` is a second opinion about what
        // a module-level output depends on, and the one that is wrong costs a full reprocess
        // on every keystroke without changing a byte of output.
        assertTrue("aggregating = true" in functionBody("aggregating"), processorSource)
        for (writer in listOf("writeModuleFiles", "writeAgentModuleFiles")) {
            val body = functionBody(writer)
            assertTrue("aggregating(sourceFiles)" in body, "$writer does not use the one helper: $body")
            assertTrue(
                "aggregating = false" !in body,
                "$writer genuinely depends on every source in the module; it must not claim otherwise",
            )
        }
    }

    @Test
    fun `no output name is derived from a clock, a hash or a random source`() {
        // The specific defect, named: `UdeaSerializerRegistry_${'$'}{System.currentTimeMillis()}`.
        for (forbidden in listOf("currentTimeMillis", "nanoTime", "Random", "Instant.now", "UUID")) {
            assertTrue(
                forbidden !in processorSource,
                "$forbidden in the processor would make a generated name vary between builds",
            )
        }
    }

    @Test
    fun `two runs over identical sources emit an identical file list`(
        @TempDir first: File,
        @TempDir second: File,
    ) {
        val sources = mapOf(
            "Components.kt" to """
                package fixtures

                import dev.wildware.udea.annotations.Net
                import dev.wildware.udea.annotations.Replicated

                @Replicated
                class Alpha(@Net var a: Int = 0)

                @Replicated
                class Beta(@Net var b: Float = 0f)
            """.trimIndent(),
        )
        val options = mapOf(
            CodegenOptions.MODULE_NAME to "Moba",
            // As the build supplies it: a module emitting a protocol is numbered from the
            // project's id space, never from the symbols in front of the processor.
            CodegenOptions.PROJECT_COMPONENTS to "fixtures.Alpha,fixtures.Beta",
        )

        val a = ProcessorHarness.run(first, sources, options)
        val b = ProcessorHarness.run(second, sources, options)

        assertEquals(emptyList(), a.errors)
        assertEquals(
            listOf("AlphaReplicator.kt", "BetaReplicator.kt", "MobaNetProtocol.kt"),
            a.generatedFiles.map { it.name }.sorted(),
        )
        assertEquals(
            a.generatedFiles.map { it.name }.sorted(),
            b.generatedFiles.map { it.name }.sorted(),
        )
        assertEquals(a.generatedResources.keys, b.generatedResources.keys)
    }

    @Test
    fun `a module-level output exists at all, so the audit above is not vacuous`(
        @TempDir workDir: File,
    ) {
        // Both aggregating assertions would pass trivially if nothing aggregating were ever
        // written. This is the run that proves the path is live.
        val run = ProcessorHarness.run(
            workDir,
            mapOf(
                "One.kt" to """
                    package fixtures

                    import dev.wildware.udea.annotations.Net
                    import dev.wildware.udea.annotations.Replicated

                    @Replicated
                    class One(@Net var a: Int = 0)
                """.trimIndent(),
            ),
            mapOf(
                CodegenOptions.MODULE_NAME to "Moba",
                CodegenOptions.PROJECT_COMPONENTS to "fixtures.One",
            ),
        )

        assertTrue(run.generatedFiles.any { it.name == "MobaNetProtocol.kt" })
        assertTrue(
            "udea/Moba-net-protocol.lock" in run.generatedResources,
            "the lock is module-qualified so two modules cannot land on one resource path; " +
                "got ${run.generatedResources.keys}",
        )
    }
}
