package dev.wildware.udea.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `UdeaVersions_JVM_TOOLCHAIN matches the jvmTarget the root build sets`() {
        val root = File("../build.gradle.kts").canonicalFile
        require(root.isFile) { "root build script not found at ${root.absolutePath}" }
        val text = root.readText()
        assertTrue(
            text.contains("JvmTarget.JVM_${UdeaVersions.JVM_TOOLCHAIN}"),
            "the root build's allprojects block sets jvmTarget for the whole tree, including " +
                "the old tree, which is not on the udea.kotlin-library convention. It must " +
                "agree with UdeaVersions.JVM_TOOLCHAIN=${UdeaVersions.JVM_TOOLCHAIN}, or " +
                "udea-render compiles to one bytecode level and declares another - which is a " +
                "dependency-resolution failure against ComposeGL's jvm.version=21, not a " +
                "compile error.",
        )
        assertTrue(
            text.contains("JavaVersion.VERSION_${UdeaVersions.JVM_TOOLCHAIN}"),
            "the same block sets sourceCompatibility/targetCompatibility and must agree too",
        )
    }

    /**
     * The catalog's `ksp` entry must not name a Kotlin compiler other than this project's.
     *
     * This replaces an assertion that KSP is versioned `<kotlin>-<ksp>`, which was true until
     * KSP 2.3.0 and is now true of nothing that is published. [KspVersionRule] carries the
     * reasoning and names where the *executable* proof that KSP works with this compiler lives,
     * because no string can carry it.
     */
    @Test
    fun `the catalog KSP does not name a Kotlin compiler other than the project's`() {
        val ksp = assertNotNull(
            catalogVersion("ksp"),
            "gradle/libs.versions.toml has no [versions] ksp entry",
        )
        assertNull(
            KspVersionRule.mismatch(ksp, UdeaVersions.KOTLIN),
            "catalog ksp='$ksp' is not usable with Kotlin ${UdeaVersions.KOTLIN}",
        )
    }

    /**
     * [KspVersionRule.mismatch]'s own branches, so the check above is a check rather than a
     * sentence that happens to be true of today's catalog.
     *
     * The first two rows are the failure path. The third is the release the catalog is on. The
     * fourth is the old scheme used correctly, which stays legal because the rule is about
     * disagreement rather than about which scheme is in fashion.
     */
    @Test
    fun `KspVersionRule rejects a KSP pinned to another compiler and accepts the rest`() {
        assertNotNull(
            KspVersionRule.mismatch("2.2.10-2.0.2", "2.4.20"),
            "a KSP naming Kotlin 2.2.10 must be rejected under a 2.4.20 compiler",
        )
        assertNotNull(
            KspVersionRule.mismatch("2.2.20-RC2-2.0.2", "2.4.20"),
            "a qualifier in the Kotlin part must not smuggle a mismatch through",
        )
        assertNull(
            KspVersionRule.mismatch("2.3.12", "2.4.20"),
            "a decoupled KSP names no compiler, so the string cannot contradict one",
        )
        assertNull(
            KspVersionRule.mismatch("2.4.20-2.0.2", "2.4.20"),
            "the old scheme naming this project's own Kotlin is not a mismatch",
        )

        assertEquals("2.2.21", KspVersionRule.kotlinVersionNamedBy("2.2.21-2.0.5"))
        assertEquals("2.2.20-RC2", KspVersionRule.kotlinVersionNamedBy("2.2.20-RC2-2.0.2"))
        assertNull(
            KspVersionRule.kotlinVersionNamedBy("2.3.12"),
            "2.3.12 has one dotted triple, not two, so it names no Kotlin version",
        )
    }

    private companion object {
        /** Test working directory is the `build-logic` project directory. */
        val catalogFile: File = File("../gradle/libs.versions.toml").canonicalFile
    }
}
