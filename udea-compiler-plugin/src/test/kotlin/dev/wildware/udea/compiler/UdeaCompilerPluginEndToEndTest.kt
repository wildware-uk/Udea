package dev.wildware.udea.compiler

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The scaffold's proof of life, and the proof of the kill switch.
 *
 * These run a real `K2JVMCompiler` over a real source file, with the plugin supplied the
 * way Gradle supplies it: `-Xplugin=<jar>` plus `-P plugin:<id>:<option>=<value>`. That
 * exercises the whole load path - the `META-INF/services` entries, the command line
 * processor, the registrar and the FIR checker - rather than calling the plugin classes
 * directly.
 */
class UdeaCompilerPluginEndToEndTest {

    @Test
    fun `the plugin is built against the exact Kotlin version it will be loaded by`() {
        // A K2 plugin compiled against a different compiler than the one loading it fails
        // at class-load time, which is why spec 7 pins these together.
        assertEquals(pinnedKotlinVersion, KotlinCompilerVersion.VERSION)
    }

    @Test
    fun `the service files name the classes the compiler has to find`() {
        assertEquals(
            "dev.wildware.udea.compiler.UdeaCompilerPluginRegistrar",
            serviceEntry("org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar"),
        )
        assertEquals(
            "dev.wildware.udea.compiler.UdeaCommandLineProcessor",
            serviceEntry("org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor"),
        )
    }

    @Test
    fun `with the plugin applied the checker reports on the probe class`() {
        val result = compileProbe(applyPlugin = true)

        assertEquals(ExitCode.OK, result.exitCode, result.output)
        val warning = result.output.lineSequence().firstOrNull { PROBE_MESSAGE in it }
            ?: fail("expected the probe diagnostic in:\n" + result.output)
        assertTrue(
            "warning" in warning.lowercase(),
            "the probe must be a warning, not an error: " + warning,
        )
        assertTrue(
            (PROBE_FILE_NAME + ":3:") in warning,
            "the diagnostic must land on the probe declaration, was: " + warning,
        )
        assertEquals(
            1,
            result.output.split(PROBE_MESSAGE).size - 1,
            "the checker must fire on the probe class only, not on SomeOtherClass:\n" +
                result.output,
        )
    }

    @Test
    fun `the inner kill switch loads the plugin and reports nothing`() {
        val result = compileProbe(applyPlugin = true, pluginOptions = listOf("enabled=false"))

        assertEquals(ExitCode.OK, result.exitCode, result.output)
        assertFalse(
            PROBE_MESSAGE in result.output,
            "enabled=false must register zero extensions, but the checker ran:\n" + result.output,
        )
    }

    @Test
    fun `checkers=false keeps the plugin loaded but silences the checkers`() {
        val result = compileProbe(applyPlugin = true, pluginOptions = listOf("checkers=false"))

        assertEquals(ExitCode.OK, result.exitCode, result.output)
        assertFalse(PROBE_MESSAGE in result.output, result.output)
    }

    @Test
    fun `the same sources compile identically with no -Xplugin argument at all`() {
        // This is the shape the Gradle kill switch produces: the argument is never added,
        // so a plugin broken by a Kotlin release cannot fail the build (spec 7).
        val without = compileProbe(applyPlugin = false)
        val applied = compileProbe(applyPlugin = true)

        assertEquals(ExitCode.OK, without.exitCode, without.output)
        assertFalse(PROBE_MESSAGE in without.output, without.output)
        assertTrue(
            without.classFiles.isNotEmpty(),
            "the probe should actually have been compiled",
        )
        assertEquals(
            applied.classFiles,
            without.classFiles,
            "the plugin must not change what is emitted - it only adds diagnostics. " +
                "This compares the SHA-256 of every class file, not just its name: the " +
                "kill switch is only worth anything if a plugin-off build produces the " +
                "same bytes, and a name-set comparison would pass an IR transform (spec 7).",
        )
    }

    @Test
    fun `an unparseable option value fails the compilation loudly`() {
        val result = compileProbe(applyPlugin = true, pluginOptions = listOf("enabled=maybe"))

        assertNotEquals(
            ExitCode.OK,
            result.exitCode,
            "a bad kill-switch value must not be read as 'off':\n" + result.output,
        )
        assertTrue("maybe" in result.output, result.output)
    }

    // --- harness -------------------------------------------------------------------

    private data class CompileResult(
        val exitCode: ExitCode,
        val output: String,
        /** Relative path to the SHA-256 of that class file's bytes. */
        val classFiles: Map<String, String>,
    )

    private fun compileProbe(
        applyPlugin: Boolean,
        pluginOptions: List<String> = emptyList(),
    ): CompileResult {
        val work = Files.createTempDirectory("udea-plugin-e2e").toFile()
        val source = File(work, PROBE_FILE_NAME)
        source.writeText(PROBE_SOURCE)
        val out = File(work, "out")
        out.mkdirs()

        val args = buildList {
            add("-no-stdlib")
            add("-no-reflect")
            add("-classpath")
            add(kotlinStdlibJar.absolutePath)
            add("-d")
            add(out.absolutePath)
            if (applyPlugin) {
                // The whole runtime classpath, not just the jar: the plugin reads its rule ids
                // from udea-diagnostics, and Gradle's KotlinCompilerPluginSupportPlugin passes
                // the same set. A bare jar would load and then die on the first diagnostic.
                for (entry in pluginClasspath) {
                    add("-Xplugin=" + entry)
                }
                for (option in pluginOptions) {
                    add("-P")
                    add("plugin:" + UdeaCompilerPlugin.PLUGIN_ID + ":" + option)
                }
            }
            add(source.absolutePath)
        }

        val captured = ByteArrayOutputStream()
        val exitCode = PrintStream(captured, true, "UTF-8").use { stream ->
            K2JVMCompiler().exec(stream, *args.toTypedArray())
        }
        // Hashing the bytes, not listing the names: two compilations that emit the same
        // file names can still differ in every byte. Stable across the two temp
        // directories because a class file records `SourceFile "Probe.kt"` by name only,
        // the module name defaults identically, and @Metadata carries no path.
        val classFiles = out.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .associate { it.relativeTo(out).invariantSeparatorsPath to sha256(it.readBytes()) }
            .toSortedMap()

        work.deleteOnExit()
        return CompileResult(exitCode, captured.toString("UTF-8"), classFiles)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun serviceEntry(serviceName: String): String {
        val resource = checkNotNull(
            javaClass.classLoader.getResource("META-INF/services/" + serviceName),
        ) { "no META-INF/services/" + serviceName + " on the classpath" }
        return resource.readText().trim()
    }

    private companion object {
        const val PROBE_FILE_NAME = "Probe.kt"
        // The compiler lower-cases the first character of a rendered message, so match from the second word.
        const val PROBE_MESSAGE = "Udea K2 compiler plugin is loaded"

        /** Line 3 is the probe class; the second class proves the checker is selective. */
        val PROBE_SOURCE: String = listOf(
            "package udea.probe",
            "",
            "class " + UdeaCompilerPlugin.PROBE_CLASS_NAME + "(val id: Int)",
            "",
            "class SomeOtherClass(val id: Int)",
            "",
        ).joinToString("\n")

        val pinnedKotlinVersion: String =
            requireNotNull(System.getProperty("udea.pinnedKotlinVersion")) {
                "udea.pinnedKotlinVersion is set by udea-compiler-plugin/build.gradle.kts"
            }

        val pluginJar: File =
            File(
                requireNotNull(System.getProperty("udea.pluginJar")) {
                    "udea.pluginJar is set by udea-compiler-plugin/build.gradle.kts"
                },
            ).also { check(it.isFile) { "the compiler plugin jar is missing at " + it } }

        /** The plugin jar plus its runtime dependencies, exactly as Gradle assembles them. */
        val pluginClasspath: List<String> =
            requireNotNull(System.getProperty("udea.pluginClasspath")) {
                "udea.pluginClasspath is set by udea-compiler-plugin/build.gradle.kts"
            }.split(File.pathSeparator).filter { File(it).exists() }

        /** The compiled probe needs a stdlib; the test runtime already has exactly one. */
        val kotlinStdlibJar: File =
            System.getProperty("java.class.path")
                .split(File.pathSeparator)
                .map(::File)
                .firstOrNull { it.name.startsWith("kotlin-stdlib-") && it.extension == "jar" }
                ?: error("no kotlin-stdlib jar on the test runtime classpath")
    }
}
