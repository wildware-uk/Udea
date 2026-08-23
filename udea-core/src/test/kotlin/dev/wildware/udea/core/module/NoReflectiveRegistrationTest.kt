package dev.wildware.udea.core.module

import dev.wildware.udea.core.KotlinSource
import dev.wildware.udea.core.ModuleFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rule that keeps registration explicit: no reflective instantiation, no `kotlin-reflect`.
 *
 * `UdeaGameManager.kt:281` built systems with `kClass.createInstance()`. That is what forced
 * every system to have a no-arg constructor, which is what pushed every system to reach for a
 * global instead of taking its dependency, which is the smell this whole epic exists to kill.
 * Deleting the call is not enough — the next convenient shortcut has the same shape — so the
 * absence is checked, by source and by build script.
 */
class NoReflectiveRegistrationTest {

    @Test
    fun `no main source reflectively constructs anything`() {
        val offenders = scanMainSources { path, line, text ->
            REFLECTIVE_CONSTRUCTION.find(text)?.let { "$path:$line  ${text.trim()}" }
        }

        assertEquals(
            emptyList(),
            offenders,
            "a system or service built reflectively is one whose dependencies the compiler " +
                "never checked; register a factory on SimRegistry instead",
        )
    }

    @Test
    fun `no main source imports the kotlin-reflect API`() {
        val offenders = scanMainSources { path, line, text ->
            if (REFLECT_IMPORT.containsMatchIn(text)) "$path:$line  ${text.trim()}" else null
        }

        assertEquals(
            emptyList(),
            offenders,
            "kotlin.reflect.full is the kotlin-reflect artifact; KClass identity and " +
                "`::class.java.name` come from the stdlib and are all the registry needs",
        )
    }

    @Test
    fun `udea-core does not depend on kotlin-reflect outside test configurations`() {
        val script = ModuleFiles.moduleDir.resolve("build.gradle.kts")
        assertTrue(script.isFile, "expected ${script.path}")

        val offenders = script.readLines()
            .withIndex()
            .filter { (_, line) -> KOTLIN_REFLECT.containsMatchIn(line) }
            .filterNot { (_, line) -> line.trimStart().startsWith("//") }
            .filterNot { (_, line) -> TEST_CONFIGURATION.containsMatchIn(line) }
            .map { (index, line) -> "build.gradle.kts:${index + 1}  ${line.trim()}" }

        assertEquals(
            emptyList(),
            offenders,
            "kotlin-reflect must not reach udea-core's runtime classpath; the only sanctioned " +
                "use is testImplementation, for the API-shape tests",
        )
    }

    @Test
    fun `the registry resolves a system name without kotlin-reflect`() {
        // `KClass.qualifiedName` is one of the members that can need the reflect artifact.
        // The manifest is built from `java.name`, which never does — asserted here rather than
        // trusted, because the two read identically at a call site.
        val manifest = UdeaGameDef(emptyList()).build().manifest
        assertTrue(manifest.size > 0, "CoreModule registers systems")
        for (entry in manifest.entries) {
            assertEquals(
                Class.forName(entry.name, false, javaClass.classLoader).name,
                entry.name,
                "a manifest name must be a loadable JVM class name",
            )
        }
    }

    private fun scanMainSources(
        inspect: (path: String, line: Int, text: String) -> String?,
    ): List<String> {
        val offenders = ArrayList<String>()
        for (file in ModuleFiles.mainSources) {
            val path = ModuleFiles.relativePath(file)
            // Comments are stripped so this module's KDoc, which discusses `createInstance()`
            // at length, does not count as a use of it.
            val code = KotlinSource.stripCommentsAndStrings(file.readText())
            code.lineSequence().forEachIndexed { index, text ->
                inspect(path, index + 1, text)?.let(offenders::add)
            }
        }
        return offenders
    }

    private companion object {
        /** `createInstance()`, `Class.forName(...)` and `newInstance()`: all three build by name. */
        val REFLECTIVE_CONSTRUCTION =
            Regex("""\b(createInstance\s*\(|newInstance\s*\(|Class\s*\.\s*forName\s*\()""")

        /** The kotlin-reflect artifact's package. `kotlin.reflect.KClass` is stdlib and allowed. */
        val REFLECT_IMPORT = Regex("""^import\s+kotlin\.reflect\.(full|jvm)\b""")

        val KOTLIN_REFLECT = Regex("""kotlin\s*\(\s*"reflect"\s*\)|kotlin-reflect""")

        val TEST_CONFIGURATION = Regex("""^\s*(testImplementation|testRuntimeOnly|testCompileOnly|testFixtures\w*)\s*\(""")
    }
}
