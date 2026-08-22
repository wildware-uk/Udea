package dev.wildware.udea.compiler

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `udeaVerifyPluginOptional`, run over the real repository.
 *
 * Issue #39 asks for a demonstration that adding a production reference fails the check, kept
 * as a fixture rather than done once by hand and reverted. `a production reference anywhere in
 * the tree fails the check` is that fixture: it builds a module tree in a temp directory, adds
 * the offending import, points the same scanner at it and asserts the failure - so the
 * demonstration is repeated on every build instead of remembered.
 */
class PluginOptionalTest {

    private val repoRoot = File(
        requireNotNull(System.getProperty("udea.repoRoot")) {
            "udea.repoRoot is set by udea-compiler-plugin/build.gradle.kts"
        },
    )

    @Test
    fun `no production source in the repository references a compiler-plugin type`() {
        val sources = PluginOptionalRule.productionSources(repoRoot)
        val references = PluginOptionalRule.scan(repoRoot, sources)

        assertNull(PluginOptionalRule.violation(sources.size, references))
    }

    @Test
    fun `the scan actually walks the repository's production modules`() {
        // The check above passes vacuously if the walk finds nothing, so the walk is asserted
        // separately rather than trusted.
        val sources = PluginOptionalRule.productionSources(repoRoot)

        assertTrue(
            sources.size > 10,
            "expected the udea-* production tree, found ${sources.size} file(s)",
        )
        assertTrue(
            sources.none { PluginOptionalRule.OWNING_MODULE in it.invariantSeparatorsPath },
            "the plugin's own module must be excluded, or the check fails on itself",
        )
        assertTrue(
            sources.none { "/src/test/" in it.invariantSeparatorsPath },
            "test sources are not production sources",
        )
    }

    @Test
    fun `a production reference anywhere in the tree fails the check`() {
        val fake = File.createTempFile("udea-plugin-optional", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }
        val offending = File(fake, "udea-core/src/main/kotlin/Loadbearing.kt")
        offending.parentFile.mkdirs()
        offending.writeText(
            "package dev.wildware.udea.core\n\n" +
                "import ${PluginOptionalRule.FORBIDDEN_PREFIX}UdeaCompilerPlugin\n\n" +
                "internal val id = UdeaCompilerPlugin.PLUGIN_ID\n",
        )

        val sources = PluginOptionalRule.productionSources(fake)
        val references = PluginOptionalRule.scan(fake, sources)

        assertEquals(1, sources.size)
        assertEquals(
            listOf("udea-core/src/main/kotlin/Loadbearing.kt"),
            references.map { it.path },
        )
        assertEquals(listOf(3), references.map { it.line })
        val failure = assertNotNull(PluginOptionalRule.violation(sources.size, references))
        assertTrue("udea-core/src/main/kotlin/Loadbearing.kt:3" in failure, failure)
        assertTrue("spec 7" in failure, failure)

        fake.deleteRecursively()
    }

    @Test
    fun `a scan that walks nothing is a broken check, not a clean tree`() {
        val failure = assertNotNull(PluginOptionalRule.violation(scannedFiles = 0, references = emptyList()))

        assertTrue("the check is broken, not the tree" in failure, failure)
    }

    @Test
    fun `the plugin id on its own is not a type reference`() {
        // `udea-gradle` has to carry the plugin id and the artifact coordinates as strings to
        // produce the -Xplugin argument at all. Flagging those would make the check
        // unsatisfiable rather than useful.
        val fake = File.createTempFile("udea-plugin-optional-id", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }
        val benign = File(fake, "udea-gradle/src/main/kotlin/Subplugin.kt")
        benign.parentFile.mkdirs()
        benign.writeText(
            "package dev.wildware.udea.gradle\n\n" +
                "internal const val PLUGIN_ID = \"dev.wildware.udea\"\n" +
                "internal const val ARTIFACT = \"udea-compiler-plugin\"\n",
        )

        val sources = PluginOptionalRule.productionSources(fake)

        assertEquals(emptyList(), PluginOptionalRule.scan(fake, sources))

        fake.deleteRecursively()
    }
}
