package dev.wildware.moba

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The shipped game resolves no Kotlin script compiler and no reflection.
 *
 * ## What this proves that the 800ms gate does not
 *
 * `udeaBenchStartup` measures the *symptom* — a boot that pays a compiler's price is slow. This
 * names the *cause*, and the two are worth having separately: a wall-clock budget on a fast
 * machine can absorb a script host that only compiles on a cache miss, and would then go green
 * on every run that hit the cache. The old path is exactly that shape:
 * `common/.../assets/dsl/script/scriptHost.kt` constructs a `BasicJvmScriptingHost` and caches
 * compiled scripts as jars under `./scripts/cache`, so a warm developer machine sees almost none
 * of the cost that a cold CI run pays.
 *
 * `kotlin-reflect` is in the same assertion because it is the other thing the old asset loader
 * needed: `createObject` in `common/.../assets/dsl/assetBuilder.kt:31-39` instantiated component
 * classes reflectively. Neither is a dependency `moba` declares; the point of asserting it is
 * that neither may arrive *transitively* either, which is how a scripting jar actually returns.
 *
 * ## Why it reads a system property instead of walking a directory
 *
 * The runtime classpath is a Gradle concept and a test JVM cannot see it: this test's own
 * classpath is `testRuntimeClasspath`, a superset that legitimately contains test-only
 * artifacts. `moba`'s build script resolves `runtimeClasspath` and hands over the file names, so
 * what is asserted is the configuration that actually ships.
 */
class StartupClasspathTest {

    /** Artifact name prefixes that must not appear on the shipped runtime classpath. */
    private val banned = listOf("kotlin-scripting-", "kotlin-reflect")

    @Test
    fun `the moba runtime classpath resolves no script host and no reflection`() {
        val names = classpathNames()
        assertTrue(names.isNotEmpty(), "the build script handed over an empty runtime classpath")
        val offenders = names.filter { name -> banned.any { name.startsWith(it) } }
        assertTrue(
            offenders.isEmpty(),
            "moba's runtime classpath resolves ${offenders.sorted()}. Spec D4 deletes the " +
                "runtime script host; a scripting or reflection artifact here means some module " +
                "on the graph pulled one back in transitively. Run " +
                "`./gradlew :moba:dependencies --configuration runtimeClasspath` and find the " +
                "path to it.\nResolved: ${names.sorted()}",
        )
    }

    private fun classpathNames(): List<String> {
        val raw = checkNotNull(System.getProperty(CLASSPATH_PROPERTY)) {
            "system property '$CLASSPATH_PROPERTY' is not set; moba's build script sets it on " +
                "the test task from the resolved runtimeClasspath"
        }
        return raw.split(File.pathSeparatorChar).filter { it.isNotBlank() }
    }

    private companion object {
        const val CLASSPATH_PROPERTY = "udea.moba.runtimeClasspathNames"
    }
}
