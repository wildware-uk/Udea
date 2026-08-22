package dev.wildware.udea.codegen

import java.io.File

/**
 * Locates the `Replicator` files `kspTest` produced from this module's fixture components.
 *
 * Real files on disk are the point of choosing KSP over an IR plugin (spec 3.2): they are
 * diffable, steppable and — as here — scannable. Tests read them from the build directory
 * rather than from a string the emitter returned, so what is asserted is what a consumer
 * actually compiles.
 */
internal object GeneratedSources {

    val directory: File by lazy {
        File(moduleRoot(), "build/generated/ksp/test/kotlin").also {
            check(it.isDirectory) {
                "no generated sources at ${it.absolutePath}; run :udea-codegen:kspTestKotlin"
            }
        }
    }

    val files: List<File> by lazy {
        directory.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.invariantSeparatorsPath }
            .toList()
            .also { check(it.isNotEmpty()) { "no generated .kt files under ${directory.absolutePath}" } }
    }

    /** The generated files' paths relative to [directory], with `/` separators on every OS. */
    fun relativePaths(): List<String> =
        files.map { it.relativeTo(directory).invariantSeparatorsPath }

    private fun moduleRoot(): File {
        var candidate: File? = File("").absoluteFile
        while (candidate != null) {
            if (candidate.name == "udea-codegen" && File(candidate, "build.gradle.kts").isFile) {
                return candidate
            }
            val child = File(candidate, "udea-codegen")
            if (File(child, "build.gradle.kts").isFile) return child
            candidate = candidate.parentFile
        }
        error("could not locate the udea-codegen module from ${File("").absolutePath}")
    }
}
