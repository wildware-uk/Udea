package dev.wildware.udea.assets.compiler

import dev.wildware.udea.assets.compiler.scan.ReferenceSpanIndex
import dev.wildware.udea.assets.compiler.scan.UdeaDeclarationScanner
import dev.wildware.udea.assets.compiler.script.UdeaAssetScript
import dev.wildware.udea.diagnostics.SourceSpan
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.constructorArgs
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.api.valueOrNull
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.api.hostConfiguration
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.compilationCache
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.CompiledScriptJarsCache
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

/**
 * What one [AssetCompiler.compile] produced.
 *
 * A graph *and* diagnostics, never one or the other: a corpus where one script fails to
 * compile still yields every asset the other eighteen declared, which is what lets the daemon
 * keep serving a working game while an author fixes a typo.
 */
public data class AssetCompileResult(
    public val graph: AssetGraph,
    public val diagnostics: List<UdeaDiagnostic>,
    /** How many scripts were answered from the compiled-script jar cache. */
    public val cacheHits: Int = 0,
) {
    public val hasErrors: Boolean
        get() = diagnostics.any { it.severity == dev.wildware.udea.diagnostics.Severity.Error }
}

/**
 * Pass 2 of spec 3.6: compile and evaluate `.udea.kts` into an [AssetGraph].
 *
 * A **plain JVM API** — source files in, a graph and diagnostics out. There is not a Gradle
 * type in this file or anywhere else in the module (`UDEA-MG-003` enforces it), because this
 * one implementation stands behind both the Gradle task and the dev daemon. A Gradle type
 * here would make the daemon path either impossible or a second implementation, and a second
 * implementation is how CI and the IDE come to disagree about whether an asset is valid.
 *
 * ### What it deletes
 *
 * The runtime script host (`common/.../assets/dsl/script/scriptHost.kt`) ran
 * `BasicJvmScriptingHost` once per asset file **at every game launch**, with
 * `dependenciesFromClassContext(wholeClasspath = true)` making the whole app classpath a
 * compile input, into a jar cache at `./scripts/cache` — in the *process working directory*,
 * keyed on MD5 of the script text plus `notTransientData`. That cache was unshared between
 * checkouts, never cleaned, and silently stale whenever the classpath changed but the text
 * did not.
 *
 * Here: the cache directory is an argument, the key is sha256 over the text **and** a
 * fingerprint of the script classpath **and** the Kotlin version, and nothing is written
 * outside it (`NoStrayWritesTest`).
 *
 * ### Diagnostics, not stack traces
 *
 * A compiler message becomes a [UdeaDiagnostic] with a repo-relative [SourceSpan]. The code
 * this replaces answered a syntax error with
 * `error("Failed to compile ${'$'}{file.name} ... ${'$'}{e.stackTraceToString()}")`.
 *
 * ### Isolation
 *
 * This class compiles **in the calling JVM**. Running it where a compiler OOM cannot take the
 * caller down is [dev.wildware.udea.assets.compiler.worker.IsolatedAssetCompiler]'s job; this
 * class is what that worker runs.
 */
public class AssetCompiler(
    /** Absolute repository root; every emitted span is relative to it. */
    repoRoot: Path,
    /** Absolute asset root; every emitted id is relative to it. */
    assetRoot: Path,
    /**
     * The classpath scripts compile against.
     *
     * Explicit, and explicitly **not** the whole application classpath. It needs
     * `udea-assets-compiler` (for [AssetScope]) plus whatever game types the scripts name.
     * It must never contain the generated `GameAssets` accessors: scripts use
     * `reference("id")`, so that an asset rename does not invalidate every script's compile
     * classpath and blow the asset-edit budget (spec 3.6).
     */
    private val scriptClasspath: List<Path>,
    /**
     * Where compiled-script jars are cached. Supplied by the caller — `build/udea/script-cache`
     * from Gradle — and never the process working directory.
     */
    private val cacheDirectory: Path,
) {
    private val repoRoot: Path = repoRoot.toAbsolutePath().normalize()
    private val assetRoot: Path = assetRoot.toAbsolutePath().normalize()

    /**
     * sha256 over the script classpath's contents plus the Kotlin version.
     *
     * Part of every cache key. The old host keyed on script text alone, so a jar cached
     * against one version of the game's classes was served against a later, incompatible one
     * — a stale cache that produced a `NoSuchMethodError` at *runtime*, in the game, with
     * nothing pointing at the cache.
     */
    private val classpathFingerprint: String by lazy {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(KotlinCompilerVersion.VERSION.toByteArray())
        for (entry in scriptClasspath.sortedBy { it.absolutePathString() }) {
            digest.update(entry.name.toByteArray())
            val file = entry.toFile()
            digest.update(if (file.isFile) file.length().toString().toByteArray() else ByteArray(0))
            digest.update(file.lastModified().toString().toByteArray())
        }
        UdeaDeclarationScanner.sha256Hex(digest.digest())
    }

    private var hits = 0

    /**
     * Compiles and evaluates every file in [files] against a fresh [AssetScope] each.
     *
     * [spanIndex] is pass 1's output and is the guaranteed fallback for locating a reference
     * whose origin could not be captured; [captureOrigins] turns on the per-reference stack
     * capture that fills in the rest.
     */
    public fun compile(
        files: List<Path>,
        spanIndex: ReferenceSpanIndex? = null,
        captureOrigins: Boolean = false,
    ): AssetCompileResult {
        cacheDirectory.createDirectories()
        hits = 0
        val host = BasicJvmScriptingHost()
        val assets = mutableListOf<DeclaredAsset>()
        val diagnostics = mutableListOf<UdeaDiagnostic>()

        for (file in files.sortedBy { it.absolutePathString() }) {
            evaluate(host, file, spanIndex, captureOrigins, assets, diagnostics)
        }
        return AssetCompileResult(AssetGraph.of(assets), diagnostics, hits)
    }

    private fun evaluate(
        host: BasicJvmScriptingHost,
        file: Path,
        spanIndex: ReferenceSpanIndex?,
        captureOrigins: Boolean,
        assets: MutableList<DeclaredAsset>,
        diagnostics: MutableList<UdeaDiagnostic>,
    ) {
        val relative = SourceSpan.relativize(repoRoot.toString(), file.toAbsolutePath().normalize().toString())
        val scope = AssetScope(idPrefixOf(file), file.name.removeSuffix(UdeaDeclarationScanner.SCRIPT_SUFFIX))
        val source = file.toFile().toScriptSource()

        val previousCapture = UdeaBuildContext.captureOrigins
        val previousScript = UdeaBuildContext.currentScript
        UdeaBuildContext.captureOrigins = captureOrigins
        UdeaBuildContext.currentScript = relative

        val result = try {
            host.eval(source, compilationConfiguration(file), evaluationConfiguration(scope))
        } catch (failure: Throwable) {
            // A script that threw takes its file down, not the corpus. `Throwable` and not
            // `Exception` on purpose: an OOM inside an in-process compile is precisely the
            // case the isolated worker exists for, and swallowing it here would hide the
            // reason the worker died.
            diagnostics += AssetCompilerRules.SCRIPT_EVALUATION_FAILED.diagnostic(
                message = "evaluating ${file.name} threw ${failure.javaClass.simpleName}: ${failure.message}",
                span = SourceSpan(relative, 0, 0, 0, 0),
            )
            null
        } finally {
            UdeaBuildContext.captureOrigins = previousCapture
            UdeaBuildContext.currentScript = previousScript
        }

        if (result != null) {
            diagnostics += result.reports.mapNotNull { it.toUdeaDiagnostic(relative) }
            if (result.valueOrNull() == null) return
        }
        assets += scope.assets.map { it.withFallbackOrigins(relative, spanIndex) }
    }

    /**
     * Fills in what the runtime could not see.
     *
     * Origin capture reads a stack frame, which is empty for a `reference` written inside an
     * inlined lambda or in a helper declared in game source. Pass 1's index has a span for
     * every reference *literally present* in a `.udea.kts`, so it fills the gap. When neither
     * knows, the value keeps a null origin and a diagnostic about it degrades to the file and
     * the asset id — issue #85's rule is that a build never fails for want of a line number.
     */
    private fun DeclaredAsset.withFallbackOrigins(
        file: String,
        spanIndex: ReferenceSpanIndex?,
    ): DeclaredAsset {
        if (spanIndex == null) return this
        fun fill(value: Any?): Any? = when (value) {
            is Ref -> if (value.origin != null) value else value.copy(origin = spanIndex.spanFor(value.id, file))
            is List<*> -> value.map(::fill)
            is Map<*, *> -> value.mapValues { fill(it.value) }
            else -> value
        }
        return copy(fields = fields.mapValues { fill(it.value) })
    }

    private fun idPrefixOf(file: Path): String {
        val parent = file.toAbsolutePath().normalize().parent ?: return ""
        if (parent == assetRoot) return ""
        return parent.relativeTo(assetRoot).toString().replace('\\', '/')
    }

    private fun compilationConfiguration(file: Path) =
        createJvmCompilationConfigurationFromTemplate<UdeaAssetScript> {
            jvm {
                updateClasspath(scriptClasspath.map { it.toFile() })
                hostConfiguration(
                    ScriptingHostConfiguration {
                        jvm {
                            compilationCache(
                                CompiledScriptJarsCache { script, _ -> jarFor(script, file) },
                            )
                        }
                    },
                )
            }
        }

    private fun evaluationConfiguration(scope: AssetScope) = ScriptEvaluationConfiguration {
        implicitReceivers(scope)
        constructorArgs()
        jvm {
            baseClassLoader(AssetScope::class.java.classLoader)
        }
    }

    /**
     * The cache file for one script.
     *
     * Keyed on sha256 of the script text, the classpath fingerprint and the Kotlin version —
     * the three things that can change what compiling this text produces. The file name keeps
     * the script's own name in front of the hash so that a human looking at the cache
     * directory can tell what is in it; correctness comes entirely from the hash.
     */
    private fun jarFor(script: SourceCode, file: Path): File {
        val text = script.text.toByteArray()
        val key = UdeaDeclarationScanner.sha256Hex(text + classpathFingerprint.toByteArray())
        val jar = cacheDirectory.resolve("${file.name.removeSuffix(".udea.kts")}-$key.jar").toFile()
        if (jar.isFile) hits++
        jar.parentFile.mkdirs()
        return jar
    }

    /**
     * A Kotlin script compiler report as a [UdeaDiagnostic] with a repo-relative span.
     *
     * Debug and info reports are dropped: they are the compiler talking to itself, and spec 5
     * caps what an agent is shown at twenty-five diagnostics — spending any of them on
     * "compiling script..." is spending them on nothing.
     */
    private fun ScriptDiagnostic.toUdeaDiagnostic(file: String): UdeaDiagnostic? {
        val severity = when (this.severity) {
            ScriptDiagnostic.Severity.FATAL, ScriptDiagnostic.Severity.ERROR ->
                dev.wildware.udea.diagnostics.Severity.Error
            ScriptDiagnostic.Severity.WARNING -> dev.wildware.udea.diagnostics.Severity.Warning
            else -> return null
        }
        val location = location?.start
        return AssetCompilerRules.SCRIPT_COMPILATION_FAILED.diagnostic(
            message = message,
            span = SourceSpan(
                file,
                location?.line ?: 0,
                location?.col ?: 0,
                location?.line ?: 0,
                location?.col ?: 0,
            ),
            severity = severity,
        )
    }

    public companion object {
        /**
         * The Kotlin version this module's embedded compiler actually is.
         *
         * Read from `kotlin-compiler-embeddable` itself rather than from a constant, so
         * `KotlinVersionPinTest` compares the jar that will run against the version the build
         * declares instead of comparing a constant to itself.
         */
        public val KOTLIN_VERSION: String = KotlinCompilerVersion.VERSION ?: "unknown"

        /** Every `.udea.kts` under [root], sorted. */
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        public fun scriptsUnder(root: Path): List<Path> =
            root.walk()
                .filter { it.toFile().isFile && it.name.endsWith(UdeaDeclarationScanner.SCRIPT_SUFFIX) }
                .sortedBy { it.absolutePathString() }
                .toList()

        /** sha256 of a file's bytes, hex; the unit of the cache key. */
        public fun hashOf(file: Path): String = UdeaDeclarationScanner.sha256Hex(file.readBytes())
    }
}
