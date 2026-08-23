package dev.wildware.udea.assets.compiler

import dev.wildware.udea.assets.compiler.validate.AssetValidationRules
import dev.wildware.udea.diagnostics.UdeaRules
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The three contracts this module has to keep whatever else changes in it.
 */
class ModuleContractTest {

    /**
     * `udea-assets-compiler` holds zero Gradle types (spec 4, `UDEA-MG-003`).
     *
     * The build gate `udeaVerifyModuleGraph` checks the *dependency graph*; this checks the
     * thing the graph is a proxy for — that no Gradle class is loadable from here at all,
     * including through a file dependency like `gradleApi()`, which reaches a classpath as
     * loose jars under one display name and is invisible to a component-graph scan.
     */
    @Test
    fun `no Gradle type is reachable from this module`() {
        for (gradleClass in listOf("org.gradle.api.Project", "org.gradle.api.Task", "org.gradle.workers.WorkAction")) {
            try {
                Class.forName(gradleClass, false, javaClass.classLoader)
                fail(
                    "$gradleClass is on udea-assets-compiler's classpath. The five-pass compiler " +
                        "is one implementation behind both the Gradle task and the dev daemon; a " +
                        "Gradle type here makes the daemon path a second implementation.",
                )
            } catch (expected: ClassNotFoundException) {
                assertTrue(gradleClass in expected.message.orEmpty())
            }
        }
        assertTrue(
            TestPaths.compilerClasspath.none { "gradle" in it.fileName.toString().lowercase() },
            "a Gradle artefact reached the classpath: " +
                TestPaths.compilerClasspath.filter { "gradle" in it.fileName.toString().lowercase() },
        )
    }

    /**
     * `kotlin-compiler-embeddable` is the exact project Kotlin version (spec 7, issue #86).
     *
     * Compared against the version the *build* declares, handed in as a system property, and
     * read out of the compiler jar itself rather than from a constant in this module — so this
     * asserts what will actually run instead of comparing a constant to itself. A skew here is
     * not a warning: a scripting host built against one compiler and loaded next to another
     * fails at class-load time, in a worker, with a message about a missing method.
     */
    @Test
    fun `the embedded Kotlin compiler is the project Kotlin version`() {
        val declared = checkNotNull(System.getProperty("udea.pinnedKotlinVersion")) {
            "udea-assets-compiler's build script must pass the pinned Kotlin version to tests"
        }
        assertEquals(
            declared,
            AssetCompiler.KOTLIN_VERSION,
            "kotlin-compiler-embeddable resolved to ${AssetCompiler.KOTLIN_VERSION} but the build " +
                "is pinned to $declared; update gradle/libs.versions.toml and UdeaVersions.KOTLIN together",
        )
    }

    /**
     * The locally minted rule ids do not collide with the shared registry.
     *
     * `UdeaRules` is right that a producer-local id is not an id, and these four belong there.
     * They are minted here because this wave was scoped to one module; see
     * [AssetCompilerRules] for the full argument. What makes the compromise safe is that the
     * two id spaces are provably disjoint and that these ids are already in the registry's
     * format, so moving them later is a cut and paste with no renumbering.
     */
    @Test
    fun `asset compiler rule ids are well formed and disjoint from UdeaRules`() {
        val shared = UdeaRules.all.map { it.id }.toSet()
        val bands = mapOf(
            "AssetCompilerRules" to (AssetCompilerRules.all to 20..29),
            "AssetValidationRules" to (AssetValidationRules.all to AssetValidationRules.BAND),
        )

        val everyLocal = mutableSetOf<String>()
        for ((owner, entry) in bands) {
            val (rules, band) = entry
            val local = rules.map { it.id }.toSet()

            assertEquals(rules.size, local.size, "a duplicated id in $owner")
            assertEquals(emptySet(), shared intersect local, "an id is claimed by $owner and UdeaRules")
            assertEquals(emptySet(), everyLocal intersect local, "two local registries claim one id")
            assertTrue(local.all { UdeaRules.ID_FORMAT.matches(it) }, "every id must match UdeaRules.ID_FORMAT")
            assertTrue(
                local.all { it.removePrefix("UDEA").toInt() in band },
                "$owner reserved UDEA00${band.first}..UDEA00${band.last}; $local escapes it",
            )
            assertTrue(
                shared.none { it.removePrefix("UDEA").toInt() in band },
                "UdeaRules has grown into the band $owner reserved; move those ids in now",
            )
            everyLocal += local
        }
    }
}
