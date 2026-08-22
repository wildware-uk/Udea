package dev.wildware.udea.render

import dev.wildware.udea.render.bytecode.ClassRefScanner
import dev.wildware.udea.render.headless.HeadlessScan
import dev.wildware.udea.render.support.RepoLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The arrows around this module, in the direction they are allowed to point.
 *
 * `udea-render` is downstream of everything simulated. That is what makes the GL ban, the
 * `PresentationRandom` isolation and the "presentation is not a Fleks system" split hold at
 * all: they are all consequences of the kernel being unable to name anything in here.
 */
class RenderModuleGraphTest {

    @Test
    fun `udea-core declares no dependency on udea-render`() {
        val buildScript = RepoLayout.moduleDir("udea-core").resolve("build.gradle.kts").readText()

        assertTrue(
            "\":udea-render\"" !in buildScript,
            "udea-core must stay upstream of presentation:\n$buildScript",
        )
    }

    @Test
    fun `no compiled class in a headless module names a udea-render type`() {
        // Stronger than reading a build script: this catches the arrow arriving through a
        // transitive dependency or a test fixture, and it is the same visitor the headless GL
        // gate uses, pointed at this module's own package instead.
        for (module in HeadlessScan.HEADLESS_MODULES) {
            val classFiles = RepoLayout.classFiles(module)
            check(classFiles.isNotEmpty()) { "$module contributed no classes; this check is vacuous" }

            val offenders = classFiles
                .flatMap { ClassRefScanner.scan(it) }
                .filter { it.owner.startsWith("dev/wildware/udea/render/") }
                .map { "$module: ${it.className}.${it.member} -> ${it.owner}" }

            assertEquals(emptyList(), offenders)
        }
    }

    @Test
    fun `udea-render is the only module on the GL convention`() {
        val offenders = RepoLayout.repoRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory && (it.name.startsWith("udea-") || it.name == "moba") }
            .map { it to it.resolve("build.gradle.kts") }
            .filter { (_, script) -> script.isFile && "udea.kotlin-library-gl" in script.readText() }
            .map { (module, _) -> module.name }

        assertEquals(listOf("udea-render"), offenders)
    }

    @Test
    fun `udea-render depends only on the modules its brief allows`() {
        val script = RepoLayout.moduleDir("udea-render").resolve("build.gradle.kts").readText()
        val declared = Regex("""project\("(:[a-z-]+)"\)""").findAll(script)
            .map { it.groupValues[1] }
            .distinct()
            .sorted()
            .toList()

        // udea-diagnostics is test-only: the bytecode gate reports through UdeaDiagnostic, and
        // a rule that invented its own report shape would drift from every other producer.
        assertEquals(listOf(":udea-assets", ":udea-core", ":udea-diagnostics"), declared)
        assertTrue(
            "testImplementation(project(\":udea-diagnostics\"))" in script,
            "udea-diagnostics must not reach udea-render's runtime classpath",
        )
    }
}
