package dev.wildware.udea.codegen

import com.google.devtools.ksp.impl.KotlinSymbolProcessing
import com.google.devtools.ksp.processing.KSPJvmConfig
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSNode
import java.io.File

/**
 * Runs [UdeaSymbolProcessor] over throwaway sources through KSP2's own standalone runner.
 *
 * The fixture components in `src/test/kotlin/.../fixtures` cover the success path, but they
 * cannot cover the failure path: a source that must make the build fail cannot live in a source
 * set that has to compile. So the diagnostics are exercised here instead, against the real
 * processor, the real `Resolver` and the real `KSPLogger` — not a mock of any of them.
 *
 * This is not the golden-file / `kotlin-compile-testing` harness; that is issue #30. It runs
 * KSP only, and never compiles the result.
 */
internal object ProcessorHarness {

    /** The project's Kotlin version, minus the patch: `udea-codegen` is pinned to it (spec 7). */
    private const val KOTLIN_LANGUAGE_VERSION = "2.2"

    /**
     * @param sources file name to Kotlin source text.
     * @param workDir a fresh directory, normally a JUnit `@TempDir`.
     * @param options the KSP processor options the Udea Gradle plugin would have set. Empty
     *   by default, which is the "generate Replicators only" configuration; a test that wants
     *   the module-level index passes `udea.moduleName` here exactly as the plugin would.
     * @param javaSources file name to Java source text, compiled into the same module. Field
     *   lowering claims to cover LibGDX's `Vector2`/`Vector3`, and those are Java classes with
     *   public *fields* and public static constants — a shape no Kotlin fixture reproduces,
     *   because KSP's view of a Java field is not its view of a Kotlin property. Without this,
     *   the headline widening is only ever asserted against a Kotlin stand-in.
     */
    fun run(
        workDir: File,
        sources: Map<String, String>,
        options: Map<String, String> = emptyMap(),
        javaSources: Map<String, String> = emptyMap(),
    ): Run {
        val sourceRoot = File(workDir, "sources")
        for ((name, text) in sources) {
            val file = File(sourceRoot, name)
            file.parentFile.mkdirs()
            file.writeText(text)
        }
        val javaRoot = File(workDir, "java")
        for ((name, text) in javaSources) {
            val file = File(javaRoot, name)
            file.parentFile.mkdirs()
            file.writeText(text)
        }
        val outputBase = File(workDir, "out")
        val kotlinOut = File(outputBase, "kotlin")

        val config = KSPJvmConfig.Builder().apply {
            moduleName = "harness"
            sourceRoots = listOf(sourceRoot)
            if (javaSources.isNotEmpty()) javaSourceRoots = listOf(javaRoot)
            projectBaseDir = workDir
            this.outputBaseDir = outputBase
            cachesDir = File(workDir, "caches")
            classOutputDir = File(outputBase, "classes")
            kotlinOutputDir = kotlinOut
            resourceOutputDir = File(outputBase, "resources")
            javaOutputDir = File(outputBase, "java")
            processorOptions = options
            jvmTarget = "17"
            jdkHome = File(System.getProperty("java.home"))
            languageVersion = KOTLIN_LANGUAGE_VERSION
            apiVersion = KOTLIN_LANGUAGE_VERSION
            // The test runtime classpath already carries udea-annotations and the stdlib, which
            // is everything a fixture source can reference.
            libraries = System.getProperty("java.class.path")
                .split(File.pathSeparator)
                .map(::File)
                .filter(File::exists)
        }.build()

        val logger = RecordingLogger()
        val exitCode = KotlinSymbolProcessing(
            config,
            listOf(UdeaSymbolProcessorProvider()),
            logger,
        ).execute()

        val generated = if (kotlinOut.isDirectory) {
            kotlinOut.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
        } else {
            emptyList()
        }
        val resources = File(outputBase, "resources")
        val generatedResources = if (resources.isDirectory) {
            resources.walkTopDown().filter(File::isFile).sortedBy { it.path }
                .associate { it.relativeTo(resources).invariantSeparatorsPath to it.readText() }
        } else {
            emptyMap()
        }
        return Run(exitCode, generated, generatedResources, logger)
    }

    /** The outcome of one processor run. */
    internal class Run(
        val exitCode: KotlinSymbolProcessing.ExitCode,
        val generatedFiles: List<File>,
        /**
         * Generated resources by path relative to the resource output root, e.g.
         * `META-INF/services/dev.wildware.udea.net.NetModule`. Kept as text because the path
         * *is* the assertion for a `ServiceLoader` file: put it one directory out and nothing
         * loads, with no error anywhere.
         */
        val generatedResources: Map<String, String>,
        private val logger: RecordingLogger,
    ) {
        val errors: List<String> get() = logger.errors.map { it.message }
        val warnings: List<String> get() = logger.warnings.map { it.message }
        val infos: List<String> get() = logger.infos.map { it.message }

        /**
         * The errors with the source position each was reported at.
         *
         * "Failures are loud and located" is the module's headline claim over the generator it
         * replaces (charter section 1), and [errors] can only check the loud half: a processor
         * that reported the `@Net`-on-a-val error at the *class*, or passed `null` for the
         * symbol so the compiler prints no file and no line at all, produces byte-identical
         * message text. Located is only testable if the position is kept.
         */
        val errorDiagnostics: List<RecordingLogger.Diagnostic> get() = logger.errors

        val succeeded: Boolean get() = exitCode == KotlinSymbolProcessing.ExitCode.OK

        fun generatedSource(simpleFileName: String): String =
            generatedFiles.single { it.name == simpleFileName }.readText()
    }

    /**
     * A [KSPLogger] that records instead of printing, so a test can assert on what was said
     * **and where**.
     *
     * The `KSNode` is kept, not dropped: it is the only thing that carries the position the
     * Kotlin compiler prints in front of the message, so discarding it would make every failure
     * test an assertion about text alone.
     */
    internal class RecordingLogger : KSPLogger {
        val errors: MutableList<Diagnostic> = mutableListOf()
        val warnings: MutableList<Diagnostic> = mutableListOf()
        val infos: MutableList<Diagnostic> = mutableListOf()
        val loggings: MutableList<Diagnostic> = mutableListOf()

        override fun logging(message: String, symbol: KSNode?) {
            loggings += Diagnostic.of(message, symbol)
        }

        override fun info(message: String, symbol: KSNode?) {
            infos += Diagnostic.of(message, symbol)
        }

        override fun warn(message: String, symbol: KSNode?) {
            warnings += Diagnostic.of(message, symbol)
        }

        override fun error(message: String, symbol: KSNode?) {
            errors += Diagnostic.of(message, symbol)
        }

        override fun exception(e: Throwable) {
            errors += Diagnostic("exception: ${e.message}", file = null, line = null)
        }

        /**
         * One recorded diagnostic: what was said, and the position it was said at.
         *
         * [file] and [line] are `null` when the processor passed no symbol, or one with no
         * source position — which is exactly the regression the position assertions exist to
         * catch, so it is represented rather than papered over. KSP2's `FileLocation` carries
         * a file path and a line and **no column**, so file:line is as precise as this gets.
         */
        internal data class Diagnostic(val message: String, val file: String?, val line: Int?) {

            /** `Shield.kt:8`, or `<no location>` when the symbol carried none. */
            val position: String get() = if (file == null) "<no location>" else "$file:$line"

            companion object {
                fun of(message: String, symbol: KSNode?): Diagnostic {
                    val location = symbol?.location as? FileLocation
                        ?: return Diagnostic(message, file = null, line = null)
                    return Diagnostic(message, File(location.filePath).name, location.lineNumber)
                }
            }
        }
    }
}
