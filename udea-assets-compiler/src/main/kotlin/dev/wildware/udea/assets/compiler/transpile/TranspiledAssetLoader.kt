package dev.wildware.udea.assets.compiler.transpile

import dev.wildware.udea.assets.compiler.AssetCompileResult
import dev.wildware.udea.assets.compiler.AssetCompilerRules
import dev.wildware.udea.assets.compiler.AssetGraph
import dev.wildware.udea.assets.compiler.AssetScope
import dev.wildware.udea.assets.compiler.AssetSource
import dev.wildware.udea.assets.compiler.DeclaredAsset
import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.SourceSpan
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * The second `AssetGraph` production path (issue #87): compile the transpiled `.kt` with the
 * ordinary Kotlin compiler and discover the sources by `ServiceLoader`.
 *
 * Notice what is *not* here. There is no scripting host, no script definition, no implicit
 * receiver machinery and no compiled-script jar cache — the emitted code is plain Kotlin
 * compiled the way every other file in the repository is compiled. That is the entire value of
 * the escape hatch: if `kotlin-compiler-embeddable`-in-a-worker turns out to be unworkable,
 * this path survives it, because the only Kotlin compilation left is the one the build already
 * does for `.kt`.
 *
 * `ServiceLoader` rather than classpath scanning, for the reason `UDEA-MG-005` bans
 * `org.reflections` from the shipped game: discovery by scanning is a startup cost and a
 * silent-failure mode, and the transpiler already knows every class it emitted, so it writes
 * the service file itself.
 *
 * In a real build the compile step is the module's own `compileKotlin` task and this class's
 * [compile] is not used at all; it exists so the parity test can exercise the whole path
 * end to end without a Gradle build inside a unit test.
 */
public class TranspiledAssetLoader(
    /** Where `.kt` sources are written. */
    private val sourceDirectory: Path,
    /** Where classes are compiled to, and where the service file is written. */
    private val outputDirectory: Path,
    /**
     * The compile classpath for the emitted sources.
     *
     * Must carry `udea-assets-compiler` (for [AssetScope] and [AssetSource]) and the Kotlin
     * stdlib, and needs **no** `kotlin-scripting-*` at all — a property the parity test
     * asserts by filtering those jars out before calling this and still compiling.
     */
    private val compileClasspath: List<Path>,
) {
    /** Writes every [TranspileResult] with code to disk, plus the `ServiceLoader` file. */
    public fun write(results: List<TranspileResult>): List<Path> {
        sourceDirectory.createDirectories()
        outputDirectory.createDirectories()
        val written = results.mapNotNull { result ->
            val code = result.code ?: return@mapNotNull null
            val file = sourceDirectory.resolve("${result.simpleName}.kt")
            file.writeText(code)
            file
        }
        val serviceFile = outputDirectory.resolve(UdeaTranspiler.SERVICE_FILE)
        serviceFile.parent.createDirectories()
        serviceFile.writeText(
            results.filter { it.code != null }.map { it.className }.sorted().joinToString("\n", postfix = "\n"),
        )
        return written
    }

    /** Compiles [sources] to [outputDirectory], returning the compiler's diagnostics. */
    public fun compile(sources: List<Path>): List<UdeaDiagnostic> {
        if (sources.isEmpty()) return emptyList()
        val collected = mutableListOf<UdeaDiagnostic>()
        val arguments = K2JVMCompilerArguments().apply {
            freeArgs = sources.map { it.absolutePathString() }
            destination = outputDirectory.absolutePathString()
            classpath = compileClasspath.joinToString(java.io.File.pathSeparator) { it.absolutePathString() }
            noStdlib = true
            noReflect = true
            jvmTarget = "17"
        }
        val exit = K2JVMCompiler().exec(collector(collected), Services.EMPTY, arguments)
        if (exit != ExitCode.OK && collected.none { it.severity == Severity.Error }) {
            collected += AssetCompilerRules.TRANSPILE_UNSUPPORTED.diagnostic(
                message = "compiling the transpiled sources failed with $exit and no located message",
            )
        }
        return collected
    }

    /**
     * Loads every [AssetSource] from [outputDirectory] and builds the graph.
     *
     * The child loader's **parent is this class's loader**, which is what makes the
     * `AssetSource` the generated classes implement the same `AssetSource` this code refers
     * to. A fully isolated loader would produce instances that are structurally identical and
     * cast-incompatible, and the failure would read as a `ServiceConfigurationError` about a
     * provider not being a subtype of itself.
     */
    public fun load(): AssetCompileResult {
        val urls = (listOf(outputDirectory) + compileClasspath).map { it.toUri().toURL() }.toTypedArray()
        URLClassLoader(urls, javaClass.classLoader).use { loader ->
            val assets = mutableListOf<DeclaredAsset>()
            val diagnostics = mutableListOf<UdeaDiagnostic>()
            for (source in ServiceLoader.load(AssetSource::class.java, loader).sortedBy { it.javaClass.name }) {
                val scope = AssetScope(source.idPrefix, source.defaultName)
                try {
                    source.build(scope)
                } catch (failure: Exception) {
                    diagnostics += AssetCompilerRules.SCRIPT_EVALUATION_FAILED.diagnostic(
                        message = "${source.javaClass.name}.build threw " +
                            "${failure.javaClass.simpleName}: ${failure.message}",
                    )
                    continue
                }
                assets += scope.assets
            }
            return AssetCompileResult(AssetGraph.of(assets), diagnostics)
        }
    }

    /** Maps K2's messages onto [UdeaDiagnostic], dropping the compiler's chatter. */
    private fun collector(into: MutableList<UdeaDiagnostic>) = object : MessageCollector {
        private var errors = false

        override fun clear() {
            into.clear()
            errors = false
        }

        override fun hasErrors(): Boolean = errors

        override fun report(
            severity: CompilerMessageSeverity,
            message: String,
            location: CompilerMessageSourceLocation?,
        ) {
            val mapped = when {
                severity.isError -> Severity.Error
                severity.isWarning -> Severity.Warning
                else -> return
            }
            if (mapped == Severity.Error) errors = true
            into += AssetCompilerRules.TRANSPILE_UNSUPPORTED.diagnostic(
                message = message,
                span = location?.let {
                    // The generated sources are the location here, not the scripts. Reporting
                    // them relative to the generated-source directory is honest: a message
                    // about generated code that pretended to point into a `.udea.kts` would
                    // send an author to a line that does not say what the compiler read.
                    val path = runCatching {
                        SourceSpan.relativize(sourceDirectory.parent.toString(), it.path)
                    }.getOrElse { _ -> java.nio.file.Path.of(it.path).fileName.toString() }
                    SourceSpan(
                        path,
                        it.line.coerceAtLeast(0),
                        it.column.coerceAtLeast(0),
                        it.lineEnd.coerceAtLeast(0),
                        it.columnEnd.coerceAtLeast(0),
                    )
                },
                severity = mapped,
            )
        }
    }
}
