package dev.wildware.udea.diagnostics

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `udea-diagnostics` sits on the compile classpath of the K2 compiler plugin, the KSP
 * processor, `udea-assets-compiler` and the runtime at once, and those four have mutually
 * incompatible version constraints. A single third-party dependency here would have to satisfy
 * all four, so the module is required to have none: Kotlin stdlib and nothing else.
 *
 * That is a build-graph property, and a unit test cannot query the Gradle model without
 * dragging in the very Gradle types this module must not see. So it is asserted at the two
 * places a dependency could actually enter: the build script that would declare one, and the
 * imports that would use one.
 */
class ModuleGraphTest {

    private val moduleRoot: File = locateModuleRoot()

    @Test
    fun `the build script declares no project and no third-party dependency`() {
        val buildScript = File(moduleRoot, "build.gradle.kts")
        assertTrue(buildScript.isFile, "missing ${buildScript.absolutePath}")

        val offenders = buildScript.readLines()
            .map { it.substringBefore("//").trim() }
            .filter { line -> PRODUCTION_CONFIGURATIONS.any { line.startsWith("$it(") } }

        assertEquals(
            emptyList(),
            offenders,
            "udea-diagnostics must declare no production dependency; found $offenders",
        )
    }

    @Test
    fun `no main source imports anything outside the Kotlin stdlib`() {
        val offenders = mainSources().flatMap { file ->
            file.readLines()
                .mapNotNull { IMPORT.find(it.trim())?.groupValues?.get(1) }
                .filterNot { it.startsWith("kotlin.") || it.startsWith("dev.wildware.udea.diagnostics.") }
                .map { "${file.name}: $it" }
        }

        assertEquals(emptyList(), offenders, "unexpected imports in main sources: $offenders")
    }

    @Test
    fun `no main source reaches for a JDK or third-party type by qualified name`() {
        val offenders = mainSources().flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> FORBIDDEN_QUALIFIED.containsMatchIn(line) }
                .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
        }

        assertEquals(emptyList(), offenders, "unexpected qualified references: $offenders")
    }

    @Test
    fun `the module has main sources to check in the first place`() {
        // Guards the three tests above against silently passing on an empty file list.
        assertTrue(mainSources().size >= 5, "found only ${mainSources().size} main sources")
    }

    private fun mainSources(): List<File> =
        File(moduleRoot, "src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()

    private companion object {
        val PRODUCTION_CONFIGURATIONS = listOf(
            "api", "implementation", "compileOnly", "compileOnlyApi", "runtimeOnly",
        )
        val IMPORT = Regex("^import\\s+([\\w.]+)")
        val FORBIDDEN_QUALIFIED = Regex("\\b(java|javax|kotlinx|org|com|io)\\.[a-z]\\w*\\.")

        fun locateModuleRoot(): File {
            var candidate: File? = File("").absoluteFile
            while (candidate != null) {
                if (File(File(candidate, "udea-diagnostics"), "build.gradle.kts").isFile) {
                    return File(candidate, "udea-diagnostics")
                }
                if (candidate.name == "udea-diagnostics" && File(candidate, "build.gradle.kts").isFile) {
                    return candidate
                }
                candidate = candidate.parentFile
            }
            error("could not locate the udea-diagnostics module from ${File("").absolutePath}")
        }
    }
}
