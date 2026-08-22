package dev.wildware.udea.core

import java.io.File

/**
 * Locates this module's sources so architecture tests can read them.
 *
 * Some rules in this module are about *source*, not about types: "no top-level mutable
 * global", "no `Entity` on a replicated field". Those cannot be checked by reflection alone,
 * because the thing being forbidden is a shape a compiler is perfectly happy with. So they
 * are checked by reading the tree, and this is how the tree is found.
 */
internal object ModuleFiles {

    /** The `udea-core` project directory. */
    val moduleDir: File = locateModuleDir()

    /** The repository root. */
    val repoRoot: File = moduleDir.parentFile

    /** Every `.kt` file in `src/main/kotlin`. */
    val mainSources: List<File> = kotlinFilesIn(moduleDir.resolve("src/main/kotlin"))

    /** Every `.kt` file in `src/testFixtures/kotlin`. */
    val testFixtureSources: List<File> = kotlinFilesIn(moduleDir.resolve("src/testFixtures/kotlin"))

    /** Path relative to the repo root, with forward slashes, for readable failure messages. */
    fun relativePath(file: File): String =
        file.relativeTo(repoRoot).invariantSeparatorsPath

    fun kotlinFilesIn(root: File): List<File> =
        if (!root.isDirectory) {
            emptyList()
        } else {
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
        }

    private fun locateModuleDir(): File {
        var candidate: File? = File(System.getProperty("user.dir")).absoluteFile
        while (candidate != null) {
            if (candidate.name == "udea-core" && candidate.resolve("build.gradle.kts").isFile) {
                return candidate
            }
            val nested = candidate.resolve("udea-core")
            if (nested.resolve("build.gradle.kts").isFile) return nested
            candidate = candidate.parentFile
        }
        error(
            "Could not locate the udea-core module directory from working dir " +
                System.getProperty("user.dir"),
        )
    }
}
