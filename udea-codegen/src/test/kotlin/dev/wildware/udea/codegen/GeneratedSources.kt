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
        ModuleRoot.file("build/generated/ksp/test/kotlin").also {
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

    /**
     * Where `kspTest` puts the generated **resources**: the wire-protocol lock, the tool
     * manifest, and the component manifest of issue #274.
     *
     * A deliberately separate collection from [directory] rather than a widening of it, and the
     * reason is worth stating. [relativePaths] is what `expected-generated-hashes.txt` is keyed
     * on, so folding resources into [files] would add rows to that checked-in fixture - a
     * regeneration of a file another branch owns, to buy a check that can be had without it.
     * What is *not* traded away is coverage: [resources] is walked by the same determinism
     * scans, so it protects the next generated resource as well as this one.
     */
    val resourceDirectory: File by lazy {
        ModuleRoot.file("build/generated/ksp/test/resources").also {
            check(it.isDirectory) {
                "no generated resources at ${it.absolutePath}; run :udea-codegen:kspTestKotlin"
            }
        }
    }

    /** Every generated resource on disk, ascending by path. */
    val resources: List<File> by lazy {
        resourceDirectory.walkTopDown()
            .filter(File::isFile)
            .sortedBy { it.invariantSeparatorsPath }
            .toList()
            .also { check(it.isNotEmpty()) { "no generated resources under ${resourceDirectory.absolutePath}" } }
    }
}
