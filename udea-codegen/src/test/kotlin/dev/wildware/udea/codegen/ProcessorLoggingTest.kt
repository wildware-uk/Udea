package dev.wildware.udea.codegen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * A successful run is **silent**.
 *
 * The generator this replaces announced every step at `logger.warn` — a banner on entry, a line
 * per discovered serializer, a line per generated file, a banner on exit — so an ordinary build
 * printed dozens of warnings and a real warning was invisible among them. Warning level is the
 * build's error budget; spending it on progress reporting spends it on nothing.
 */
class ProcessorLoggingTest {

    @Test
    fun `a valid component generates a file and logs nothing at all`(@TempDir workDir: File) {
        val run = ProcessorHarness.run(workDir, mapOf("Valid.kt" to VALID_COMPONENT))

        assertEquals(emptyList(), run.warnings, "the processor must not warn on a successful run")
        assertEquals(emptyList(), run.errors, "the processor must not error on a valid component")
        assertEquals(
            emptyList(),
            run.processorInfos,
            "the processor must not chatter at info either",
        )

        // The other half of that assertion, and what keeps it honest (issue #186). KSP 2.3 says
        // one thing at `info` on every run - that the processor has not taken the "upcoming
        // features" opt-in, which nothing in `symbol-processing-api` 2.3.12 exposes - so
        // `processorInfos` excludes it. If KSP ever stops saying it, or renames it, this goes red
        // and the exclusion should be deleted rather than widened.
        assertEquals(
            1,
            run.infos.count { ProcessorHarness.UPCOMING_FEATURES_NOTICE in it },
            "expected exactly KSP's own opt-in notice at info, got ${run.infos}",
        )
        assertTrue(run.succeeded, "expected a clean run, got ${run.exitCode}")
        assertEquals(
            listOf("PowerCellReplicator.kt"),
            run.generatedFiles.map { it.name },
            "one @Replicated component must produce exactly one Replicator file",
        )
    }

    @Test
    fun `several components in one file each get their own Replicator`(@TempDir workDir: File) {
        val run = ProcessorHarness.run(
            workDir,
            mapOf("Two.kt" to VALID_COMPONENT + "\n" + SECOND_COMPONENT),
        )

        assertEquals(emptyList(), run.errors)
        assertEquals(emptyList(), run.warnings)
        assertEquals(
            listOf("FuseReplicator.kt", "PowerCellReplicator.kt"),
            run.generatedFiles.map { it.name }.sorted(),
        )
    }

    private companion object {
        val VALID_COMPONENT = """
            package fixtures

            import dev.wildware.udea.annotations.Net
            import dev.wildware.udea.annotations.Replicated
            import dev.wildware.udea.annotations.Sim

            @Replicated
            class PowerCell(
                @Net var charge: Float = 1f,
                @Sim var lastDrainTick: Long = 0L,
            )
        """.trimIndent()

        val SECOND_COMPONENT = """
            @Replicated
            class Fuse(
                @Net var blown: Boolean = false,
            )
        """.trimIndent()
    }
}
