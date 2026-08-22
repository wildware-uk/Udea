package dev.wildware.udea.codegen

import com.google.devtools.ksp.impl.KotlinSymbolProcessing
import com.google.devtools.ksp.processing.KSPJvmConfig
import com.google.devtools.ksp.processing.KSPLogger
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
     */
    fun run(workDir: File, sources: Map<String, String>): Run {
        val sourceRoot = File(workDir, "sources")
        for ((name, text) in sources) {
            val file = File(sourceRoot, name)
            file.parentFile.mkdirs()
            file.writeText(text)
        }
        val outputBase = File(workDir, "out")
        val kotlinOut = File(outputBase, "kotlin")

        val config = KSPJvmConfig.Builder().apply {
            moduleName = "harness"
            sourceRoots = listOf(sourceRoot)
            projectBaseDir = workDir
            this.outputBaseDir = outputBase
            cachesDir = File(workDir, "caches")
            classOutputDir = File(outputBase, "classes")
            kotlinOutputDir = kotlinOut
            resourceOutputDir = File(outputBase, "resources")
            javaOutputDir = File(outputBase, "java")
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
        return Run(exitCode, generated, logger)
    }

    /** The outcome of one processor run. */
    internal class Run(
        val exitCode: KotlinSymbolProcessing.ExitCode,
        val generatedFiles: List<File>,
        private val logger: RecordingLogger,
    ) {
        val errors: List<String> get() = logger.errors
        val warnings: List<String> get() = logger.warnings
        val infos: List<String> get() = logger.infos

        val succeeded: Boolean get() = exitCode == KotlinSymbolProcessing.ExitCode.OK

        fun generatedSource(simpleFileName: String): String =
            generatedFiles.single { it.name == simpleFileName }.readText()
    }

    /** A [KSPLogger] that records instead of printing, so a test can assert on what was said. */
    internal class RecordingLogger : KSPLogger {
        val errors: MutableList<String> = mutableListOf()
        val warnings: MutableList<String> = mutableListOf()
        val infos: MutableList<String> = mutableListOf()
        val loggings: MutableList<String> = mutableListOf()

        override fun logging(message: String, symbol: KSNode?) {
            loggings += message
        }

        override fun info(message: String, symbol: KSNode?) {
            infos += message
        }

        override fun warn(message: String, symbol: KSNode?) {
            warnings += message
        }

        override fun error(message: String, symbol: KSNode?) {
            errors += message
        }

        override fun exception(e: Throwable) {
            errors += "exception: ${e.message}"
        }
    }
}
