package dev.wildware.udea.core.module

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `CoreModule`'s resolved system order, pinned to a checked-in file.
 *
 * The order systems run in is behaviour, and it is behaviour with no natural assertion: nothing
 * throws when a system quietly moves from `PreSimulation` to `PostPhysics`, the simulation just
 * produces different numbers. A golden file turns that into a diff on a reviewable artefact.
 *
 * Regenerate deliberately with `./gradlew :udea-core:test -Dupdate.goldens=true`, and treat a
 * diff as a question — "did I mean to change when this runs?" — rather than as noise to clear.
 */
class CoreModuleManifestGoldenTest {

    @Test
    fun `the CoreModule manifest matches the golden file`() {
        val rendered = UdeaGameDef(emptyList()).build().manifest.render()
        val golden = goldenFile()

        if (System.getProperty("update.goldens") == "true") {
            golden.parentFile.mkdirs()
            golden.writeText(rendered)
            return
        }

        assertTrue(
            golden.isFile,
            "missing golden ${golden.path}; regenerate with " +
                "./gradlew :udea-core:test -Dupdate.goldens=true",
        )
        assertEquals(
            golden.readText().replace("\r\n", "\n"),
            rendered,
            "CoreModule's system order changed; regenerate with " +
                "./gradlew :udea-core:test -Dupdate.goldens=true if that was deliberate",
        )
    }

    @Test
    fun `the golden file describes systems that exist and phases that are declared`() {
        // A golden nobody can misread: every line has to name a real phase and a class that is
        // actually on the classpath, so a stale golden cannot survive a rename.
        val lines = goldenFile().readText().trim().lines().filter { it.isNotBlank() }
        assertTrue(lines.isNotEmpty(), "the golden is empty; CoreModule registers systems")

        for (line in lines) {
            val phase = line.substringBefore(' ')
            val name = line.split(' ')[1]
            assertTrue(
                SimPhase.entries.any { it.name == phase },
                "$phase is not a SimPhase (line: $line)",
            )
            Class.forName(name, false, javaClass.classLoader)
        }
    }

    private fun goldenFile(): File =
        File(requireNotNull(System.getProperty("udea.projectDir")) {
            "udea.projectDir is not set; the test task must pass it (see build.gradle.kts)"
        }).resolve(GOLDEN_PATH)

    private companion object {
        const val GOLDEN_PATH = "src/test/resources/golden/core-module-systems.txt"
    }
}
