package dev.wildware.udea.build.determinism

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where `udeaVerifyDeterminism` looks for a scope's bytecode and sources, for both module layouts
 * (issue #203).
 *
 * The failure this guards is silent. A module converted to multiplatform keeps its old
 * `build/classes/kotlin/main` until somebody cleans, so a scan still pointed at the JVM layout
 * finds classes, reports them clean and passes - on bytecode the build no longer produces.
 */
class DeterminismLayoutTest {

    @TempDir
    lateinit var repo: File

    private val scope = SimScope(project = ":udea-core", sourceSet = "main", packagePrefixes = emptyList(), why = "test")

    private fun dirs(vararg paths: String) = paths.forEach { repo.resolve(it).mkdirs() }

    private fun relative(files: List<File>) = files.map { it.relativeTo(repo).invariantSeparatorsPath }

    @Test
    fun `a JVM module is read from its language directories`() {
        dirs("udea-core/src/main/kotlin")

        val input = DeterminismLayout.scopeInput(repo, scope)

        assertEquals(
            listOf("udea-core/build/classes/kotlin/main", "udea-core/build/classes/java/main"),
            relative(input.classRoots),
        )
        assertEquals(listOf("udea-core/src/main/kotlin", "udea-core/src/main/java"), relative(input.sourceRoots))
    }

    @Test
    fun `a multiplatform module is read from the bytecode of its JVM and Android targets only`() {
        // The stale JVM-layout output a converted module leaves behind until a clean.
        dirs(
            "udea-core/src/commonMain/kotlin",
            "udea-core/src/jvmAndAndroidMain/kotlin",
            "udea-core/src/wasmJsMain/kotlin",
            "udea-core/src/jvmTest/kotlin",
            "udea-core/build/classes/kotlin/main",
        )

        val input = DeterminismLayout.scopeInput(repo, scope)

        assertEquals(
            listOf(
                "udea-core/build/classes/kotlin/jvm/main",
                "udea-core/build/classes/kotlin/android/main",
            ),
            relative(input.classRoots),
        )
        // Sources are only ever looked up, never scanned, so a source set whose output is a klib
        // costs nothing here - and the test source set is not a `main` one.
        assertEquals(
            listOf(
                "udea-core/src/commonMain/kotlin",
                "udea-core/src/jvmAndAndroidMain/kotlin",
                "udea-core/src/wasmJsMain/kotlin",
            ),
            relative(input.sourceRoots),
        )
    }
}
