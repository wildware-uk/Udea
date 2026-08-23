package dev.wildware.udea.assets.compiler.worker

import dev.wildware.udea.assets.compiler.AssetCompiler
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * The worker JVM's entry point (issue #86).
 *
 * Reads a [WorkerRequest], runs the ordinary [AssetCompiler], writes a [WorkerResponse].
 * That is the whole of it, and deliberately so: the isolation is a *process boundary*, not a
 * second implementation. Whatever the daemon and CI disagree about, it cannot be which
 * compiler ran.
 *
 * It writes the response file **last**, after the compile has fully succeeded, so the file's
 * existence is the signal that the worker completed — a half-written response cannot be
 * mistaken for a result.
 *
 * Nothing here catches `OutOfMemoryError`. The worker is launched with
 * `-XX:+ExitOnOutOfMemoryError`, so an exhausted heap ends this process before any handler
 * runs, and [IsolatedAssetCompiler] turns the exit code into an [AssetWorkerFailure]. Catching
 * it would produce the failure mode the worker exists to prevent: a JVM that survives an OOM
 * badly and reports something else.
 */
public object AssetCompilerWorker {

    @JvmStatic
    public fun main(args: Array<String>) {
        if (args.size != 2) {
            System.err.println("usage: ${AssetCompilerWorker::class.java.name} <request> <response>")
            exitProcess(2)
        }
        val request: WorkerRequest = readObject(Path.of(args[0]))
        val response = run(request)
        writeObject(Path.of(args[1]), response)
    }

    /** The compile itself, factored out so a test can run it without forking. */
    public fun run(request: WorkerRequest): WorkerResponse {
        val compiler = AssetCompiler(
            repoRoot = Path.of(request.repoRoot),
            assetRoot = Path.of(request.assetRoot),
            scriptClasspath = request.scriptClasspath.map(Path::of),
            cacheDirectory = Path.of(request.cacheDirectory),
        )
        val result = compiler.compile(
            files = request.files.map(Path::of),
            spanIndex = request.referenceSpans.takeIf { it.isNotEmpty() }?.toReferenceSpanIndex(),
            captureOrigins = request.captureOrigins,
        )
        return WorkerResponse(
            assets = result.graph.assets.values.map { it.toRecord() },
            diagnostics = result.diagnostics.map { it.toRecord() },
            cacheHits = result.cacheHits,
        )
    }
}
