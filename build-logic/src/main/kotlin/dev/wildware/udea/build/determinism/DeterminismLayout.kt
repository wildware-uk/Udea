package dev.wildware.udea.build.determinism

import java.io.File

/**
 * Where a declared simulation scope's compiled classes and sources are on disk, for a JVM module
 * and for a multiplatform one (issue #203).
 *
 * One layout or the other, chosen from the module's **sources** rather than from what happens to
 * be under `build/`. A module converted to multiplatform keeps its old `build/classes/kotlin/main`
 * until somebody cleans; a scan reading that would pass on bytecode the build no longer produces,
 * which is the same stale-output defect `udea-render`'s `RepoLayout` fixed for the headless scan
 * in issue #201.
 */
internal object DeterminismLayout {

    /** The language directories Gradle's JVM layout uses, in the order sources are looked up. */
    private val LANGUAGES: List<String> = listOf("kotlin", "java")

    /**
     * The multiplatform targets whose output is bytecode. Wasm and iOS compile to klibs, which an
     * ASM scan cannot read; the JVM target is the authoritative simulation (spec D3) and Android
     * runs the same common code.
     */
    private val BYTECODE_TARGETS: List<String> = listOf("jvm", "android")

    /** The source sets a multiplatform module's bytecode targets are compiled from. */
    private val BYTECODE_SOURCE_SETS: List<String> = listOf("common") + BYTECODE_TARGETS

    /** The scan input for [scope], resolved against [repoRoot]. */
    fun scopeInput(repoRoot: File, scope: SimScope): DeterminismScan.ScopeInput {
        val module = moduleDir(repoRoot, scope)
        val sourceSetSuffix = scope.sourceSet.replaceFirstChar { it.uppercase() }
        return if (isMultiplatform(module)) {
            DeterminismScan.ScopeInput(
                scope = scope,
                classRoots = BYTECODE_TARGETS.map { module.resolve("build/classes/kotlin/$it/${scope.sourceSet}") },
                sourceRoots = BYTECODE_SOURCE_SETS.map { module.resolve("src/$it$sourceSetSuffix/kotlin") },
            )
        } else {
            DeterminismScan.ScopeInput(
                scope = scope,
                classRoots = LANGUAGES.map { module.resolve("build/classes/$it/${scope.sourceSet}") },
                sourceRoots = LANGUAGES.map { module.resolve("src/${scope.sourceSet}/$it") },
            )
        }
    }

    /**
     * True when [module] keeps its sources in multiplatform source sets (`src/commonMain`, ...)
     * rather than in `src/main`.
     */
    private fun isMultiplatform(module: File): Boolean =
        module.resolve("src").listFiles().orEmpty()
            .any { it.isDirectory && it.name != "main" && it.name.endsWith("Main") }

    private fun moduleDir(repoRoot: File, scope: SimScope): File =
        repoRoot.resolve(scope.project.removePrefix(":").replace(':', '/'))
}
