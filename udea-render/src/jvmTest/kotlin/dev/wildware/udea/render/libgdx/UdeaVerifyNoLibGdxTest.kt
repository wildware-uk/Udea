package dev.wildware.udea.render.libgdx

import dev.wildware.udea.render.support.RepoLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `udeaVerifyNoLibGdx`: no module's shipped bytecode names LibGDX, scene2d above all (issue #189).
 *
 * The class the `udeaVerifyNoLibGdx` Gradle task runs, and the one the ordinary `jvmTest` task
 * skips, so the gate is a task you can name. See [LibGdxScan] for what it catches that
 * `UDEA-MG-009` cannot.
 */
class UdeaVerifyNoLibGdxTest {

    @Test
    fun `no module references a LibGDX type`() {
        val report = LibGdxScan.run()

        assertEquals(
            emptyList(),
            report.diagnostics.map { it.toString() },
            "LibGDX reached a module's bytecode; the UI layer is ComposeGL and rendering is Kool",
        )
        assertEquals(0, report.suppressedCount, "violations were capped or collapsed away")
    }

    @Test
    fun `every designated module was actually scanned`() {
        for (module in LibGdxScan.MODULES) {
            assertTrue(RepoLayout.classFiles(module).isNotEmpty(), "$module contributed no compiled classes")
        }
    }

    @Test
    fun `the designated list is every project settings includes, the GL-allowed ones and the game included`() {
        // Asserted against `settings.gradle.kts`, which neither the build script nor the scan
        // derives the list from, so this is not a copy checked against itself. "Everywhere" is
        // the whole of the rule (issue #189): a module left off is a module scene2d can return to.
        val settings = RepoLayout.repoRoot.resolve("settings.gradle.kts")
        assertTrue(settings.isFile, "settings.gradle.kts not found at $settings")
        val included = Regex("""^include\("((?:udea-|moba:)[a-z0-9:-]+)"\)""", RegexOption.MULTILINE)
            .findAll(settings.readText())
            .map { it.groupValues[1].replace(':', '/') }
            .toSortedSet()
        assertTrue(
            included.containsAll(listOf("udea-core", "udea-render", "moba/game", "moba/android")),
            "the settings scan found only $included - the regex has stopped matching, so this " +
                "test would be comparing against nothing",
        )

        assertEquals(included.toList(), LibGdxScan.MODULES.sorted())
    }
}
