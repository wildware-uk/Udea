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

    /**
     * The scan input for [scope], whose module directory is [moduleDir].
     *
     * The directory is passed in rather than derived from the Gradle path (issue #265): the build
     * knows where a project is and a path does not, and a game outside this repository may lay
     * its projects out however it likes. [moduleDirectoryUnder] is the default a build with no
     * answer falls back on, and is what this repository's own layout gives.
     */
    fun scopeInput(repoRoot: File, scope: SimScope, moduleDir: File): DeterminismScan.ScopeInput {
        val module = moduleDir
        val suffix = scope.sourceSet.replaceFirstChar { it.uppercase() }
        return if (isMultiplatform(module)) {
            DeterminismScan.ScopeInput(
                scope = scope,
                classRoots = BYTECODE_TARGETS.map { module.resolve("build/classes/kotlin/$it/${scope.sourceSet}") },
                // Every `<name>Main` source set on disk rather than a fixed list: a shared one such
                // as `jvmAndAndroidMain` feeds the bytecode too. A span is looked up by file name,
                // and each platform's `actual` file has a name of its own.
                sourceRoots = multiplatformSourceSets(module, suffix).map { it.resolve("kotlin") },
            )
        } else {
            DeterminismScan.ScopeInput(
                scope = scope,
                classRoots = LANGUAGES.map { module.resolve("build/classes/$it/${scope.sourceSet}") },
                sourceRoots = LANGUAGES.map { module.resolve("src/${scope.sourceSet}/$it") },
            )
        }
    }

    /** Where a project at [projectPath] sits under [repoRoot] when nothing says otherwise. */
    fun moduleDirectoryUnder(repoRoot: File, projectPath: String): File =
        repoRoot.resolve(projectPath.removePrefix(":").replace(':', '/'))

    /**
     * True when [module] keeps its sources in multiplatform source sets (`src/commonMain`, ...)
     * rather than in `src/main`.
     */
    private fun isMultiplatform(module: File): Boolean = multiplatformSourceSets(module, "Main").isNotEmpty()

    /** `src/<target><suffix>` directories of [module] by name, which the JVM layout's `src/main` is not. */
    private fun multiplatformSourceSets(module: File, suffix: String): List<File> =
        module.resolve("src").listFiles().orEmpty()
            .filter { it.isDirectory && it.name.endsWith(suffix) && it.name.length > suffix.length }
            .sortedBy { it.name }
}
