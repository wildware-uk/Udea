package dev.wildware.udea.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `gradle/libs.versions.toml` is the authoritative version source; [UdeaVersions] mirrors
 * the parts build logic needs as compile-time constants. These tests are what stops the
 * mirror from going stale.
 */
class UdeaVersionsTest {

    private fun catalogVersion(key: String): String? {
        val catalog = catalogFile
        require(catalog.isFile) { "version catalog not found at ${catalog.absolutePath}" }
        var inVersions = false
        for (raw in catalog.readLines()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[")) {
                inVersions = line == "[versions]"
                continue
            }
            if (!inVersions) continue
            val name = line.substringBefore('=').trim()
            if (name == key) {
                return line.substringAfter('=').trim().trim('"')
            }
        }
        return null
    }

    @Test
    fun `UdeaVersions_KOTLIN matches the catalog kotlin version`() {
        val fromCatalog = assertNotNull(
            catalogVersion("kotlin"),
            "gradle/libs.versions.toml has no [versions] kotlin entry",
        )
        assertEquals(
            fromCatalog,
            UdeaVersions.KOTLIN,
            "UdeaVersions.KOTLIN has drifted from gradle/libs.versions.toml. " +
                "The K2 compiler plugin pin is derived from this constant.",
        )
    }

    @Test
    fun `the KSP version is built against the project Kotlin version`() {
        val ksp = assertNotNull(
            catalogVersion("ksp"),
            "gradle/libs.versions.toml has no [versions] ksp entry",
        )
        assertTrue(
            ksp.startsWith(UdeaVersions.KOTLIN + "-"),
            "KSP artifacts are versioned <kotlin>-<ksp>. Catalog ksp='$ksp' is not built " +
                "against Kotlin ${UdeaVersions.KOTLIN}; bumping Kotlin without bumping KSP " +
                "breaks udea-codegen at processor load time.",
        )
    }

    private companion object {
        /** Test working directory is the `build-logic` project directory. */
        val catalogFile: File = File("../gradle/libs.versions.toml").canonicalFile
    }
}
