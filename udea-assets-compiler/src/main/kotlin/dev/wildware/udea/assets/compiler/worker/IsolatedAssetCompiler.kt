package dev.wildware.udea.assets.compiler.worker

import dev.wildware.udea.assets.compiler.AssetCompileResult
import dev.wildware.udea.assets.compiler.AssetCompiler
import dev.wildware.udea.assets.compiler.scan.ReferenceSpanIndex
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

/**
 * A worker terminated abnormally: it did not write a response.
 *
 * The typed error issue #86 asks for. It is thrown only for abnormal termination — a worker
 * that ran to completion reports script failures as [dev.wildware.udea.diagnostics.UdeaDiagnostic]s
 * and exits zero, because a bad script is an authoring defect and a dead JVM is an
 * infrastructure one, and answering both with the same shape means a caller cannot tell them
 * apart.
 */
public class AssetWorkerFailure(
    public val exitCode: Int,
    /** True when the worker's output names an `OutOfMemoryError`. */
    public val outOfMemory: Boolean,
    /** The tail of what the worker printed, for a human reading a build log. */
    public val output: String,
) : RuntimeException(
    buildString {
        append("the asset compiler worker exited with code ").append(exitCode)
        if (outOfMemory) append(" after exhausting its heap")
        append(". The calling JVM is unaffected.")
        if (output.isNotBlank()) append("\n--- worker output ---\n").append(output)
    },
)

/**
 * Runs [AssetCompiler] in a **separate JVM with a fixed heap** (issue #86).
 *
 * Spec 7's risk table names `kotlin-compiler-embeddable` in a Gradle worker as the highest-risk
 * remaining component, and the specific risk is the one this class contains: the embedded
 * compiler is the largest memory consumer in the build and an OOM inside it is not
 * recoverable in-process. Compiling in the caller means one pathological script can take down
 * the Gradle daemon — and a Gradle daemon that dies mid-build is a failure a developer cannot
 * read, attached to whatever task happened to be running.
 *
 * Here a compiler OOM kills a process whose only job was that compile. The caller gets an
 * [AssetWorkerFailure] naming the exit code and the heap, and carries on.
 *
 * This class holds no Gradle types either: it is `ProcessBuilder`. That is what makes it
 * runnable *as* a Gradle `processIsolation` worker action and equally runnable from the dev
 * daemon — the caller decides who forks, and the behaviour is identical either way.
 */
public class IsolatedAssetCompiler(
    private val repoRoot: Path,
    private val assetRoot: Path,
    private val scriptClasspath: List<Path>,
    private val cacheDirectory: Path,
    /**
     * Where the request and response files go. A caller-supplied directory for the same
     * reason the script cache is: nothing this module writes lands in a working directory.
     */
    private val workDirectory: Path,
    /**
     * The classpath the worker JVM is launched with — this module plus the Kotlin compiler.
     *
     * Defaults to [scriptClasspath], which is right whenever the scripts compile against the
     * same classes the compiler runs from (the normal case, and every case in this repository
     * today). A caller that narrows the script classpath must widen this one to match.
     */
    private val workerClasspath: List<Path> = scriptClasspath,
    /** The worker's maximum heap, as a `-Xmx` value. Fixed, and the caller's decision. */
    private val maxHeap: String = DEFAULT_MAX_HEAP,
    private val javaExecutable: Path = defaultJavaExecutable(),
) {
    /**
     * Compiles [files] in a worker JVM.
     *
     * @throws AssetWorkerFailure when the worker terminates without writing a response.
     */
    public fun compile(
        files: List<Path>,
        spanIndex: ReferenceSpanIndex? = null,
        captureOrigins: Boolean = false,
    ): AssetCompileResult {
        workDirectory.createDirectories()
        cacheDirectory.createDirectories()
        val requestFile = workDirectory.resolve("request-${System.nanoTime()}.bin")
        val responseFile = workDirectory.resolve("response-${System.nanoTime()}.bin")
        responseFile.deleteIfExists()

        val targets = files.map { it.absolutePathString() }
        writeObject(
            requestFile,
            WorkerRequest(
                repoRoot = repoRoot.absolutePathString(),
                assetRoot = assetRoot.absolutePathString(),
                scriptClasspath = scriptClasspath.map { it.absolutePathString() },
                cacheDirectory = cacheDirectory.absolutePathString(),
                files = targets,
                captureOrigins = captureOrigins,
                referenceSpans = spanIndex?.allRecords().orEmpty(),
            ),
        )

        val command = buildList {
            add(javaExecutable.absolutePathString())
            add("-Xmx$maxHeap")
            // Without this the JVM tries to keep limping along after an OOM, which is how a
            // build ends up hanging instead of failing: the compiler retries, allocates,
            // fails again, and nothing ever writes a response.
            add("-XX:+ExitOnOutOfMemoryError")
            addAll(ADD_OPENS)
            add("-cp")
            add(workerClasspath.joinToString(java.io.File.pathSeparator) { it.absolutePathString() })
            add(WORKER_MAIN)
            add(requestFile.absolutePathString())
            add(responseFile.absolutePathString())
        }

        val process = ProcessBuilder(command)
            .directory(workDirectory.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()

        if (exit != 0 || !responseFile.exists()) {
            throw AssetWorkerFailure(
                exitCode = exit,
                outOfMemory = "OutOfMemoryError" in output,
                output = output.takeLast(OUTPUT_TAIL),
            )
        }

        val response: WorkerResponse = readObject(responseFile)
        requestFile.deleteIfExists()
        responseFile.deleteIfExists()
        return AssetCompileResult(response.toGraph(), response.toDiagnostics(), response.cacheHits)
    }

    public companion object {
        /** The class the worker JVM runs. */
        public const val WORKER_MAIN: String = "dev.wildware.udea.assets.compiler.worker.AssetCompilerWorker"

        /**
         * A heap big enough for `kotlin-compiler-embeddable` and small enough to be a limit.
         *
         * The point of a fixed value is that it is fixed: an unbounded worker inherits the
         * machine's default max heap, which on a developer's 64GB laptop is large enough that
         * the OOM path is never exercised until CI hits it.
         */
        public const val DEFAULT_MAX_HEAP: String = "1g"

        /** How much of the worker's output an [AssetWorkerFailure] carries. */
        private const val OUTPUT_TAIL: Int = 4000

        /**
         * The `--add-opens` the embedded Kotlin compiler needs on a JDK 17 module path.
         *
         * The compiler's PSI and its `com.intellij` fork reflect into `java.util` and
         * `java.lang`; without these the worker dies with an `InaccessibleObjectException`
         * that reads like a bug in this module. Listed explicitly rather than inherited from
         * the caller's JVM: the whole value of the worker is that its environment is decided
         * here and not by whatever launched the build.
         */
        public val ADD_OPENS: List<String> = listOf(
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
        )

        /** The `java` binary of the JVM running this code. */
        public fun defaultJavaExecutable(): Path {
            val home = Path.of(System.getProperty("java.home"))
            val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
            return home.resolve("bin").resolve(if (windows) "java.exe" else "java")
        }
    }
}

/** Every span this index holds, in wire form. */
internal fun ReferenceSpanIndex.allRecords(): List<SpanRecord> = targets().flatMap { target ->
    sitesFor(target).map { it.span.toRecord(target, it.from) }
}
