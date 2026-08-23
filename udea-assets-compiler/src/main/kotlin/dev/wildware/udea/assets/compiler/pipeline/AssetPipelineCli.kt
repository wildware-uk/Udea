package dev.wildware.udea.assets.compiler.pipeline

import dev.wildware.udea.assets.compiler.gen.AccessorGenerator
import dev.wildware.udea.assets.compiler.gen.AssetIndexWriter
import dev.wildware.udea.assets.compiler.scan.DeclarationsJson
import dev.wildware.udea.diagnostics.DiagnosticsJson
import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * The process `:udea-gradle`'s asset tasks fork, one subcommand per task.
 *
 * ## Why a process and not a Gradle `WorkAction`
 *
 * Two reasons, and both were paid for before they were understood.
 * [dev.wildware.udea.assets.compiler.AssetCompiler] holds a Kotlin script compiler and
 * [dev.wildware.udea.assets.compiler.scan.KtParser] holds a `KotlinCoreEnvironment`; either one
 * living longer than a single invocation inside a long-lived Gradle daemon makes a *later,
 * unrelated* script compile fail for no visible reason. And `udea-assets-compiler` carries
 * `kotlin-compiler-embeddable`, which must not be on the Gradle plugin's own classpath -
 * `:udea-gradle`'s sources are compiled a second time inside `build-logic` (see its build
 * script), and anything they *name* has to resolve there too.
 *
 * So the plugin knows this class only by its name in a string, hands it a classpath it resolved
 * from a configuration, and reads the files it writes. That is also what keeps `UDEA-MG-003` -
 * no Gradle type anywhere in this module - true of the shipped pipeline and not only of a demo.
 *
 * ## Arguments
 *
 * `--key=value`, order-independent, because these are written by a task that sets some of them
 * conditionally and a positional list is how the wrong directory ends up as the output.
 *
 * ```
 * scan       --repoRoot= --assetRoot= --out=<declarations.json>
 * accessors  --declarations=<declarations.json> --srcOut=<dir> --resourceOut=<dir>
 * validate   --repoRoot= --assetRoot= --cache=<dir> --out=<diagnostics.json>
 * pack       --repoRoot= --assetRoot= --cache=<dir> --out=<file.udeapak> --diagnostics=<file>
 * ```
 *
 * The script compile classpath comes from `-Dudea.assetsCompiler.classpath`, the spelling the
 * daemon, the tests and the pack CLI already use.
 *
 * ## Exit codes
 *
 * `0` clean, `1` the asset tree has an error, `2` this command line is wrong. `validate` and
 * `pack` **always write their diagnostics file first**, so a failing build leaves a document
 * behind rather than only a console message - which is what makes `diagnostics.json` usable
 * after a failure rather than only after a success.
 */
public object AssetPipelineCli {

    @JvmStatic
    public fun main(args: Array<String>) {
        if (args.isEmpty()) usage("no subcommand")
        val options = parse(args.drop(1))
        when (args[0]) {
            "scan" -> scan(options)
            "accessors" -> accessors(options)
            "validate" -> validate(options)
            "pack" -> pack(options)
            else -> usage("unknown subcommand " + args[0])
        }
    }

    private fun scan(options: Map<String, String>) {
        val report = AssetPipeline.scan(options.path("repoRoot"), options.path("assetRoot"))
        options.path("out").write(DeclarationsJson.write(report))
        println("[udeaScanAssets] ${report.files.size} script(s), ${report.declarations.size} declaration(s)")
        failOn(report.diagnostics, "udeaScanAssets")
    }

    /**
     * Pass 5: `GameAssets`, and `META-INF/udea/asset-index.json`.
     *
     * Both output directories are **emptied first**. A generated source tree that keeps a file
     * for an asset group somebody deleted still compiles, and then fails at runtime on a
     * reference to an id nothing declares - the exact defect the accessors exist to make
     * impossible.
     */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun accessors(options: Map<String, String>) {
        val declarations = DeclarationsJsonReader.read(options.path("declarations"))
        val srcOut = options.path("srcOut")
        val resourceOut = options.path("resourceOut")
        srcOut.deleteRecursively()
        resourceOut.deleteRecursively()
        val files = AccessorGenerator.generate(declarations)
        for (file in files) srcOut.resolve(file.path).write(file.text)
        resourceOut.resolve(AssetIndexWriter.RESOURCE_PATH).write(AssetIndexWriter.fromScan(declarations))
        println("[udeaGenerateAccessors] ${files.size} file(s) from ${declarations.size} declaration(s)")
    }

    private fun validate(options: Map<String, String>) {
        val compiled = AssetPipeline.compileAndValidate(
            repoRoot = options.path("repoRoot"),
            assetRoot = options.path("assetRoot"),
            scriptClasspath = scriptClasspath(),
            cacheDirectory = options.path("cache"),
        )
        options.path("out").write(DiagnosticsJson.encode(compiled.report))
        println(
            "[udeaValidateAssets] ${compiled.graph.assets.size} asset(s), " +
                "${compiled.report.diagnostics.size} diagnostic(s)",
        )
        failOn(compiled.report.diagnostics, "udeaValidateAssets")
    }

    private fun pack(options: Map<String, String>) {
        val assetRoot = options.path("assetRoot")
        val compiled = AssetPipeline.compileAndValidate(
            repoRoot = options.path("repoRoot"),
            assetRoot = assetRoot,
            scriptClasspath = scriptClasspath(),
            cacheDirectory = options.path("cache"),
        )
        val packed = if (compiled.hasErrors) null else AssetPipeline.pack(assetRoot, compiled.graph)
        val diagnostics = compiled.report.diagnostics + packed?.diagnostics.orEmpty()
        options.path("diagnostics").write(DiagnosticsJson.encode(compiled.report.copy(diagnostics = diagnostics)))
        failOn(diagnostics, "udeaPackBundle")
        val bundle = checkNotNull(packed) { "the graph had no errors, so it was packed" }
        val out = options.path("out")
        out.parent?.createDirectories()
        out.writeBytes(bundle.bytes)
        println(
            "[udeaPackBundle] ${out.fileName}: ${bundle.assets} asset(s), ${bundle.sheets} sheet(s), " +
                "${bundle.pages} atlas page(s), ${bundle.bytes.size} bytes",
        )
    }

    /**
     * Prints every diagnostic and exits `1` when any of them is an error, or returns.
     *
     * Printed to stderr in the `ruleId file:line:column message` shape a build log and an IDE
     * output filter both already parse. The task that forked this does not re-render them: one
     * renderer, so the console and `diagnostics.json` cannot disagree about what went wrong.
     */
    private fun failOn(diagnostics: List<UdeaDiagnostic>, task: String) {
        if (diagnostics.none { it.severity == Severity.Error }) return
        for (diagnostic in diagnostics) System.err.println("[$task] " + render(diagnostic))
        exitProcess(1)
    }

    private fun render(diagnostic: UdeaDiagnostic): String {
        val span = diagnostic.span
        val where = if (span == null) "" else "${span.path}:${span.startLine}:${span.startColumn} "
        return diagnostic.severity.name.lowercase() + " " + diagnostic.ruleId + " " + where + diagnostic.message
    }

    /** The classpath `.udea.kts` compile against, from the property every other host uses. */
    private fun scriptClasspath(): List<Path> {
        val raw = System.getProperty(CLASSPATH_PROPERTY).orEmpty()
        val entries = raw.split(File.pathSeparatorChar).filter { it.isNotBlank() }.map { Path.of(it) }
        check(entries.isNotEmpty()) {
            "system property '" + CLASSPATH_PROPERTY + "' is empty; the asset tasks set it to " +
                "the classpath the .udea.kts are compiled against"
        }
        return entries
    }

    /** Where the script compile classpath is handed over. One spelling, every host. */
    public const val CLASSPATH_PROPERTY: String = "udea.assetsCompiler.classpath"

    private fun parse(args: List<String>): Map<String, String> = args.associate { argument ->
        val at = argument.indexOf('=')
        if (!argument.startsWith("--") || at < 0) usage(argument + " is not --key=value")
        argument.substring(2, at) to argument.substring(at + 1)
    }

    private fun Map<String, String>.path(key: String): Path =
        Path.of(this[key] ?: usage("--" + key + " is required")).toAbsolutePath().normalize()

    private fun Path.write(text: String) {
        parent?.createDirectories()
        // Explicit UTF-8 and explicit LF: the default charset and the line separator both differ
        // between a developer's machine and CI, and a generated source file whose bytes depend on
        // either is a build-cache miss nobody can explain.
        writeText(text.replace("\r\n", "\n"), Charsets.UTF_8)
    }

    private fun usage(problem: String): Nothing {
        System.err.println("[udea-assets] " + problem)
        System.err.println("usage: <scan|accessors|validate|pack> --key=value ...")
        exitProcess(2)
    }
}
