package dev.wildware.udea.compiler.testing

import dev.wildware.udea.compiler.UdeaCompilerPlugin
import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.SourceSpan
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import dev.wildware.udea.diagnostics.UdeaRules
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File
import java.nio.file.Files

/**
 * The plugin's compile-testing harness: compile a snippet with the plugin loaded, get the
 * diagnostics back as [UdeaDiagnostic]s.
 *
 * ### Why not `kotlin-compile-testing`
 *
 * Issue #37 names `dev.zacsweers.kctfork:core`, and the deciding fact against it is its result
 * type: `DiagnosticMessage(severity, message)` carries **no location**. Every position
 * assertion in this suite - the thing issue #37 exists to make possible - would have to come
 * from scraping the rendered message text, which is exactly the string scraping that issue's
 * own notes want avoided. Its second problem is version drift: kctfork 0.8.0 is built against
 * Kotlin 2.2.0 and 0.9.0 against 2.2.20, and this module is pinned to 2.2.10 precisely so that
 * "the suite must pass before any Kotlin upgrade merges" (spec 3.2) means something.
 *
 * kctfork wraps `K2JVMCompiler`. This drives the same compiler directly, at the pinned
 * version, through the `exec(MessageCollector, Services, arguments)` entry point that hands
 * back a [CompilerMessageSourceLocation] per diagnostic - a real path, line and column instead
 * of a parsed one.
 *
 * ### What it exercises
 *
 * The plugin is loaded the way Gradle loads it: `pluginClasspaths` plus
 * `plugin:<id>:<option>=<value>` arguments, resolved from the module's real runtime classpath.
 * So the `META-INF/services` entries, the command line processor, the registrar and the FIR
 * checkers are all on the path under test, not stubbed.
 */
object UdeaCompileTesting {

    /**
     * Compiles [sources] in a fresh directory that stands in for the repository root.
     *
     * @param pluginOptions `option` to `value`, passed as `plugin:<id>:<option>=<value>`.
     * @param applyPlugin false reproduces the Gradle kill switch, where no `-Xplugin` argument
     *   is produced at all.
     * @param workDir the directory that stands in for the repository root. Supply one when the
     *   test needs to name a path inside it - the KDoc harvester's output, say - or when two
     *   compilations must share a root.
     */
    fun compile(
        sources: List<TestSource>,
        pluginOptions: Map<String, String> = emptyMap(),
        applyPlugin: Boolean = true,
        workDir: File = newWorkDir(),
    ): CheckerRun {
        require(sources.isNotEmpty()) { "a compilation needs at least one source" }
        val sourceDir = File(workDir, SOURCE_DIR)
        sourceDir.mkdirs()
        for (source in sources) {
            File(sourceDir, source.name).writeText(source.text)
        }
        val out = File(workDir, "out").also { it.mkdirs() }

        val arguments = K2JVMCompilerArguments().apply {
            freeArgs = listOf(sourceDir.absolutePath)
            destination = out.absolutePath
            classpath = compilationClasspath
            noStdlib = true
            noReflect = true
            moduleName = "udea-checker-test"
            if (applyPlugin) {
                pluginClasspaths = pluginClasspath.toTypedArray()
                this.pluginOptions = pluginOptions
                    .map { (key, value) -> "plugin:${UdeaCompilerPlugin.PLUGIN_ID}:$key=$value" }
                    .toTypedArray()
            }
        }

        val collector = RecordingCollector(workDir.absolutePath)
        val exitCode = K2JVMCompiler().exec(collector, Services.EMPTY, arguments)
        return CheckerRun(
            exitCode = exitCode,
            diagnostics = collector.udeaDiagnostics,
            otherMessages = collector.otherMessages,
            workDir = workDir,
        )
    }

    /**
     * A fresh throwaway directory standing in for the repository root.
     *
     * Deleted when the test JVM exits rather than at the end of each test: a failing test's
     * sources and class output are the first thing worth looking at, and a per-test cleanup
     * would throw them away before anyone could.
     */
    fun newWorkDir(): File = Files.createTempDirectory("udea-checker").toFile().also { dir ->
        Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
    }

    /** Where sources are written, and therefore the leading segment of every span path. */
    const val SOURCE_DIR: String = "src"

    /**
     * `UDEA0001: the rest of the message`, the shape both this plugin and `udea-codegen` print.
     *
     * Parsing it back out here is what makes a rule id a first-class thing in an assertion
     * rather than a substring somebody remembered to check for.
     */
    private val RULE_ID_PREFIX = Regex("""^(UDEA\d{4}): (.*)$""", RegexOption.DOT_MATCHES_ALL)

    /**
     * Everything the fixture sources may reference: the stdlib and `udea-annotations`.
     *
     * Taken from the test runtime classpath, which already carries exactly those.
     */
    private val compilationClasspath: String =
        System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .filter { File(it).exists() }
            .joinToString(File.pathSeparator)

    /**
     * The plugin jar plus its runtime dependencies, as `udea-compiler-plugin/build.gradle.kts`
     * resolves them.
     *
     * The dependencies matter: the plugin reads its rule ids from `udea-diagnostics`, so a
     * bare plugin jar would load and then fail with `NoClassDefFoundError` on the first
     * diagnostic - which is the failure a Gradle build would never see, because Gradle always
     * passes the whole runtime classpath.
     */
    private val pluginClasspath: List<String> =
        requireNotNull(System.getProperty("udea.pluginClasspath")) {
            "udea.pluginClasspath is set by udea-compiler-plugin/build.gradle.kts"
        }.split(File.pathSeparator).filter { File(it).exists() }

    /**
     * Turns the compiler's own messages into [UdeaDiagnostic]s.
     *
     * Normalising here rather than in each test is what issue #37's note asks for: a rule-id
     * parity assertion against another producer becomes a comparison of ids, not of strings.
     */
    private class RecordingCollector(private val repoRoot: String) : MessageCollector {

        val udeaDiagnostics = mutableListOf<UdeaDiagnostic>()
        val otherMessages = mutableListOf<String>()
        private var errors = false

        override fun clear() {
            udeaDiagnostics.clear()
            otherMessages.clear()
        }

        override fun hasErrors(): Boolean = errors

        override fun report(
            severity: CompilerMessageSeverity,
            message: String,
            location: CompilerMessageSourceLocation?,
        ) {
            if (severity.isError) errors = true
            val mapped = when (severity) {
                CompilerMessageSeverity.ERROR, CompilerMessageSeverity.EXCEPTION -> Severity.Error
                CompilerMessageSeverity.WARNING, CompilerMessageSeverity.STRONG_WARNING -> Severity.Warning
                CompilerMessageSeverity.FIXED_WARNING -> Severity.Warning
                CompilerMessageSeverity.INFO -> Severity.Info
                // LOGGING and OUTPUT are the compiler talking to itself: neither is a
                // diagnostic and both would drown a clean-compilation assertion.
                CompilerMessageSeverity.LOGGING, CompilerMessageSeverity.OUTPUT -> return
            }
            val match = RULE_ID_PREFIX.find(message)
            if (match == null) {
                if (mapped != Severity.Info) otherMessages += "$mapped: $message${location.suffix()}"
                return
            }
            val (ruleId, body) = match.destructured
            udeaDiagnostics += UdeaDiagnostic(
                severity = mapped,
                ruleId = ruleId,
                message = body,
                span = location?.toSpan(repoRoot),
            )
        }

        private fun CompilerMessageSourceLocation?.suffix(): String =
            if (this == null) "" else " ($path:$line:$column)"

        /**
         * The span, repo-relative (spec 5), or `null` when the compiler pointed outside the
         * work directory - a classpath jar, say. Checked rather than caught: an exception used
         * as a branch would hide a genuinely mis-rooted span.
         */
        private fun CompilerMessageSourceLocation.toSpan(repoRoot: String): SourceSpan? {
            val normalizedRoot = repoRoot.replace('\\', '/').trimEnd('/')
            val normalizedPath = path.replace('\\', '/')
            if (!normalizedPath.startsWith("$normalizedRoot/", ignoreCase = true)) return null
            return SourceSpan.of(repoRoot, path, line, column, lineEnd, columnEnd)
        }
    }
}

/**
 * One compilation's outcome, with the assertions issue #37 requires.
 *
 * @param diagnostics every message that carried a [UdeaRules] id, normalised.
 * @param otherMessages every other error or warning, so a fixture that fails to compile fails
 *   the test loudly instead of quietly reporting no Udea diagnostics.
 */
class CheckerRun(
    val exitCode: ExitCode,
    val diagnostics: List<UdeaDiagnostic>,
    val otherMessages: List<String>,
    val workDir: File,
) {
    /** A dump of everything the compiler said, for an assertion message. */
    fun describe(): String = buildString {
        append("exit code ").append(exitCode).append('\n')
        for (diagnostic in diagnostics) append("  ").append(diagnostic).append('\n')
        for (message in otherMessages) append("  ").append(message).append('\n')
        if (diagnostics.isEmpty() && otherMessages.isEmpty()) append("  (no diagnostics)\n")
    }
}
