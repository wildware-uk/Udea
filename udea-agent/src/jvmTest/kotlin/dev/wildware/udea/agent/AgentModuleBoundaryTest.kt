package dev.wildware.udea.agent

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What `udea-agent` is not allowed to carry, checked as a gate rather than as a convention.
 *
 * This module is compiled into every Udea game, so anything on its classpath is on the game's.
 * Three things must never arrive:
 *
 * - **`common`**, the old tree, which the rewrite exists to replace;
 * - **a GL backend or a native platform artifact**, because the agent surface has to run in a
 *   headless test JVM and on a dedicated server;
 * - **an HTTP server or a JSON serialiser**, because the server belongs to `udea-agent-host` one
 *   module up, and the writer in this module exists precisely so no serialiser is needed.
 *
 * ## Why this is a test and not a `ModuleGraphRules` coordinate rule
 *
 * The first two *are* covered by coordinate rules already - `udeaVerifyNoLegacyDependencies` and
 * `UDEA-MG-002`, which lists `:udea-agent` - and this restates them one level down as a
 * belt-and-braces check that costs nothing. The third cannot be a coordinate rule at all: the
 * HTTP server this module must not use is `com.sun.net.httpserver`, which ships **inside the
 * JDK**. There is no coordinate to ban, so the only thing that can catch it is a source scan.
 *
 * The classpath half reads the test JVM's own classpath, which is this module's runtime
 * classpath plus its test dependencies. That needs no build wiring, works under the
 * configuration cache, and fails the moment somebody adds a banned dependency in any
 * configuration - which is the direction that matters, since a serialiser added "just for tests"
 * is a serialiser somebody moves to `implementation` a week later.
 *
 * ## One serialiser is already there, and it is not ours
 *
 * `kotlinx-serialization-json` **is** on this classpath, transitively, because
 * `io.github.quillraven.fleks:Fleks:2.14` depends on it - so it reaches every module that depends
 * on `udea-core`, which is all of them, and it was there before this module existed. Banning it
 * by jar name here would fail a gate for a pre-existing condition in somebody else's module and
 * teach the next reader to delete the rule.
 *
 * So the rule this module can honestly enforce is the source-level one below - `udea-agent`
 * writes JSON with [Json] and imports no serialiser - plus a scan of this module's own build
 * script, which is where a *new* serialiser would be declared. The Fleks transitive is recorded
 * here rather than hidden: it is worth someone deciding about at the kernel, not worth this test
 * pretending it is absent.
 */
class AgentModuleBoundaryTest {

    @Test
    fun `no banned artifact is on this module's classpath`() {
        val entries = System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .map { File(it).name.lowercase() }
            .filter { it.isNotEmpty() }

        assertTrue(entries.size > 3, "the classpath scan found nothing to scan: $entries")

        val offenders = entries.filter { entry -> BANNED_ARTIFACTS.any { entry.contains(it) } }

        assertEquals(
            emptyList(),
            offenders,
            "udea-agent is compiled into every game; these must not be on its classpath",
        )
    }

    @Test
    fun `the classpath scan can see this module, so it is scanning the right one`() {
        val entries = System.getProperty("java.class.path").split(File.pathSeparator)

        // Without this, the test above would pass just as happily against an empty classpath.
        assertTrue(
            entries.any { it.replace('\\', '/').contains("udea-agent") },
            "expected udea-agent on the test classpath, got $entries",
        )
    }

    @Test
    fun `no source imports an HTTP server, a socket or a serialiser`() {
        val offenders = ArrayList<String>()
        for (file in ModuleSources.mainSources) {
            val source = KotlinSourceText.stripCommentsAndStrings(file.readText())
            source.lineSequence().forEachIndexed { index, text ->
                if (BANNED_IMPORT.containsMatchIn(text)) {
                    offenders += "${ModuleSources.relativePath(file)}:${index + 1}  ${text.trim()}"
                }
            }
        }

        assertEquals(
            emptyList(),
            offenders,
            "the HTTP surface is udea-agent-host; this module must stay a pure JVM library, and " +
                "com.sun.net.httpserver ships in the JDK so no dependency rule can catch it",
        )
    }

    @Test
    fun `no source imports a GL type`() {
        val offenders = ArrayList<String>()
        for (file in ModuleSources.mainSources) {
            val source = KotlinSourceText.stripCommentsAndStrings(file.readText())
            source.lineSequence().forEachIndexed { index, text ->
                if (GL_IMPORT.containsMatchIn(text)) {
                    offenders += "${ModuleSources.relativePath(file)}:${index + 1}  ${text.trim()}"
                }
            }
        }

        assertEquals(emptyList(), offenders, "the agent surface must run with no display")
    }

    @Test
    fun `this module declares no serialiser of its own`() {
        val script = ModuleSources.moduleDir.resolve("build.gradle.kts")
        assertTrue(script.isFile, "expected ${script.path}")

        val offenders = script.readLines()
            .withIndex()
            .filterNot { (_, line) -> line.trimStart().startsWith("//") }
            .filter { (_, line) -> DECLARED_SERIALISER.containsMatchIn(line) }
            .map { (index, line) -> "build.gradle.kts:${index + 1}  ${line.trim()}" }

        // The half of the serialiser rule this module can own: Fleks already puts
        // kotlinx-serialization on every classpath in the tree, but nothing here may declare one.
        assertEquals(
            emptyList(),
            offenders,
            "udea-agent writes JSON with its own writer so no serialiser reaches a game through it",
        )
    }

    @Test
    fun `the source scan reads the whole module`() {
        // The three scans above are worth nothing if `mainSources` is empty.
        assertTrue(
            ModuleSources.mainSources.size >= 15,
            "expected the whole module, found ${ModuleSources.mainSources.size} files",
        )
    }

    private companion object {
        val BANNED_ARTIFACTS: List<String> = listOf(
            "common-1.0-snapshot",
            "lwjgl",
            "gdx-backend",
            "natives-desktop",
            "jetty",
            "netty",
            "undertow",
            "javalin",
            "nanohttpd",
            "tomcat",
            "servlet",
            "ktor",
            "jackson",
            "gson",
            "moshi",
            "reflections",
            "kotlin-reflect",
        )

        val BANNED_IMPORT = Regex(
            """^\s*import\s+(com\.sun\.net\.httpserver|java\.net\.(ServerSocket|Socket|http)|""" +
                """javax\.servlet|io\.ktor|com\.fasterxml\.jackson|kotlinx\.serialization|com\.google\.gson)""",
        )

        val GL_IMPORT = Regex("""^\s*import\s+(com\.badlogic\.gdx|org\.lwjgl)""")

        val DECLARED_SERIALISER =
            Regex("""(kotlinx[.-]serialization|jackson|gson|moshi|org\.reflections|kotlin\("reflect"\))""")
    }
}
