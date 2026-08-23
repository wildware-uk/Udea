package dev.wildware.udea.assets.compiler.worker

import dev.wildware.udea.assets.compiler.AssetCompiler
import dev.wildware.udea.assets.compiler.Fixtures
import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.assets.compiler.scan.UdeaDeclarationScanner
import dev.wildware.udea.diagnostics.Severity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The `processIsolation` half of issue #86: a compiler OOM fails the call, not the daemon.
 */
class WorkerTest {

    private fun isolated(
        maxHeap: String = IsolatedAssetCompiler.DEFAULT_MAX_HEAP,
        assetRoot: Path = Fixtures.assetRoot,
        repoRoot: Path = TestPaths.repoRoot,
        cache: Path,
        work: Path,
    ) = IsolatedAssetCompiler(
        repoRoot = repoRoot,
        assetRoot = assetRoot,
        scriptClasspath = TestPaths.compilerClasspath,
        cacheDirectory = cache,
        workDirectory = work,
        maxHeap = maxHeap,
    )

    @Test
    fun `the worker produces the same graph as an in-process compile`() {
        val cache = TestPaths.scratch("worker-cache")
        val work = TestPaths.scratch("worker-work")
        val scripts = Fixtures.scripts()

        val start = TimeSource.Monotonic.markNow()
        val viaWorker = isolated(cache = cache, work = work).compile(scripts)
        println("IsolatedAssetCompiler cold (fork + compile): ${start.elapsedNow()} for ${scripts.size} scripts")

        val inProcess = AssetCompiler(
            TestPaths.repoRoot,
            Fixtures.assetRoot,
            TestPaths.compilerClasspath,
            TestPaths.scratch("worker-inprocess-cache"),
        ).compile(scripts)

        assertEquals(emptyList(), viaWorker.diagnostics.filter { it.severity == Severity.Error })
        assertEquals(Fixtures.EXPECTED_IDS, viaWorker.graph.ids)
        assertTrue(
            inProcess.graph.sameContentAs(viaWorker.graph),
            "process isolation must not change the graph: ${inProcess.graph.contentDiff(viaWorker.graph)}",
        )
    }

    /** Reference origins survive the process boundary, spans and all. */
    @Test
    fun `pass 1 spans cross into the worker and come back attached`() {
        val cache = TestPaths.scratch("worker-span-cache")
        val work = TestPaths.scratch("worker-span-work")
        val scripts = Fixtures.scripts()
        val index = UdeaDeclarationScanner(TestPaths.repoRoot, Fixtures.assetRoot)
            .use { it.scanFiles(scripts) }
            .referenceSpanIndex()

        val result = isolated(cache = cache, work = work).compile(scripts, spanIndex = index)

        val config = assertNotNull(result.graph.assets["config"])
        val ref = assertNotNull(config.fields["defaultCharacter"] as? dev.wildware.udea.assets.compiler.Ref)
        assertEquals("character/orc", ref.id)
        val origin = assertNotNull(ref.origin, "the worker must return the span pass 1 supplied")
        assertEquals("udea-assets-compiler/src/test/resources/assets/config.udea.kts", origin.path)
    }

    /**
     * The reason this class exists (issue #86).
     *
     * A script that exhausts the worker's heap kills the worker and nothing else. The
     * assertion is in two halves and both matter: the call fails with a typed
     * [AssetWorkerFailure] naming the heap, **and** this JVM is still able to run a normal
     * compile afterwards. The second half is what "the parent survives" actually means; a
     * test that only asserted the exception would pass just as happily if the parent were
     * left in a broken state.
     */
    @Test
    fun `a script that exhausts the worker heap fails the call and the parent survives`(
        @TempDir root: Path,
    ) {
        val cache = TestPaths.scratch("worker-oom-cache")
        val work = TestPaths.scratch("worker-oom-work")
        val assets = root.resolve("assets")
        assets.createDirectories()
        assets.resolve("hog.udea.kts").writeText(
            """
            val hog = mutableListOf<ByteArray>()
            while (true) {
                hog.add(ByteArray(4 * 1024 * 1024))
            }
            """.trimIndent(),
        )

        val failure = assertFailsWith<AssetWorkerFailure> {
            isolated(maxHeap = "64m", assetRoot = assets, repoRoot = root, cache = cache, work = work)
                .compile(AssetCompiler.scriptsUnder(assets))
        }
        assertTrue(failure.exitCode != 0, "a dead worker must not report success")
        assertTrue(failure.outOfMemory, "the failure should name the heap; output was:\n${failure.output}")

        // The parent is unharmed: a normal compile still works in this JVM.
        val survivor = AssetCompiler(
            TestPaths.repoRoot,
            Fixtures.assetRoot,
            TestPaths.compilerClasspath,
            TestPaths.scratch("worker-oom-survivor-cache"),
        ).compile(Fixtures.scripts())
        assertEquals(Fixtures.EXPECTED_IDS, survivor.graph.ids)
    }

    /** A worker that runs to completion reports a bad script as a diagnostic, not a crash. */
    @Test
    fun `a compile error inside the worker comes back as a diagnostic`(@TempDir root: Path) {
        val cache = TestPaths.scratch("worker-broken-cache")
        val work = TestPaths.scratch("worker-broken-work")
        val assets = root.resolve("assets")
        assets.createDirectories()
        assets.resolve("broken.udea.kts").writeText("""spriteSheet(name = )""")

        val result = isolated(assetRoot = assets, repoRoot = root, cache = cache, work = work)
            .compile(AssetCompiler.scriptsUnder(assets))

        assertTrue(result.hasErrors)
        assertEquals("assets/broken.udea.kts", assertNotNull(result.diagnostics.first().span).path)
    }

    /**
     * The wire format refuses a value it cannot carry, by name.
     *
     * Without this the failure would be a `NotSerializableException` naming an anonymous
     * class, in a worker, with no asset id attached.
     */
    @Test
    fun `an asset field holding an unencodable value is a typed error`() {
        val asset = dev.wildware.udea.assets.compiler.DeclaredAsset(
            kind = "character",
            kindFqn = null,
            id = "character/orc",
            fields = mapOf("sheet" to Thread.currentThread()),
        )
        val failure = assertFailsWith<UnencodableAssetValue> { asset.toRecord() }
        assertEquals("character/orc", failure.assetId)
        assertEquals("sheet", failure.field)
        assertTrue("java.lang.Thread" in failure.message.orEmpty())
    }
}
