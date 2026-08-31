package dev.wildware.udea.replay.equality

import dev.wildware.udea.replay.equality.fixture.DriftDigestMain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The half of issue #169 that is about a path, and the half that is about saying so out loud.
 *
 * A digest that lands somewhere nothing looks is invisible twice over: the process that wrote it
 * exits 0, and the step that misses it names a glob rather than a path. These pin both ends -
 * where a relative `--out` goes, and what a run does when the stream is not there afterwards.
 */
class ReplayEqualityPathsTest {

    private val dir: Path = createTempDirectory("replay-equality-paths")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `a relative out is resolved against the workspace, not against the process directory`() {
        // The defect, in one assertion. A `JavaExec` runs in the *project* directory, so before
        // this resolution existed `digests/leg.udeaeq` meant `udea-replay/digests/leg.udeaeq`
        // while every Actions step meant `<workspace>/digests/leg.udeaeq`.
        val workspace = dir.resolve("workspace")
        val options = DriftDigestMain.parse(
            arrayOf(
                "--workspace", workspace.toString(),
                "--label", "leg",
                "--out", "digests/leg.udeaeq",
            ),
        )

        assertEquals(workspace.resolve("digests/leg.udeaeq"), options.out)
        assertTrue(options.out.isAbsolute, "a resolved --out must be absolute: ${options.out}")
        assertEquals("digests/leg.udeaeq", options.requestedOut)
    }

    @Test
    fun `a relative timing path is resolved the same way as the digest`() {
        // The second path on the same command line. One resolved and one not would put a leg's
        // wall time somewhere its own digest is not, which is the same defect with a smaller blast
        // radius and no `if-no-files-found` to catch it.
        val workspace = dir.resolve("workspace")
        val options = DriftDigestMain.parse(
            arrayOf(
                "--workspace", workspace.toString(),
                "--label", "leg",
                "--out", "digests/leg.udeaeq",
                "--timing", "digests/leg.timing.txt",
            ),
        )

        assertEquals(workspace.resolve("digests/leg.timing.txt"), options.timing)
    }

    @Test
    fun `an absolute out is left exactly where the caller put it`() {
        // The local default is absolute already - `layout.buildDirectory` - so the workspace must
        // not be prepended to it. Resolving an absolute path against a base is the other way to
        // get this wrong.
        val absolute = dir.resolve("elsewhere/leg.udeaeq").toAbsolutePath()
        val options = DriftDigestMain.parse(
            arrayOf(
                "--workspace", dir.resolve("workspace").toString(),
                "--label", "leg",
                "--out", absolute.toString(),
            ),
        )

        assertEquals(absolute, options.out)
    }

    @Test
    fun `without a workspace a relative out falls back to the process directory`() {
        // Stated rather than assumed: a hand-run from the repository root behaves as CI does,
        // and it is the tasks in the build script - not this default - that make a CI leg right.
        val options = DriftDigestMain.parse(arrayOf("--label", "leg", "--out", "digests/leg.udeaeq"))

        assertEquals(
            Path.of("").toAbsolutePath().normalize().resolve("digests/leg.udeaeq"),
            options.out,
        )
    }

    @Test
    fun `a digest run that wrote no stream fails naming the path it expected`() {
        val workspace = dir.resolve("workspace")
        val expected = workspace.resolve("digests/leg.udeaeq")

        val failure = assertFailsWith<IllegalStateException> {
            ReplayEqualityPaths.requireStreamWritten("digests/leg.udeaeq", workspace, expected)
        }

        // The three facts nothing downstream can recover on its own: what was asked for, where it
        // ended up, and what the relative half was measured from.
        assertContains(failure.message!!, expected.toString())
        assertContains(failure.message!!, "digests/leg.udeaeq")
        assertContains(failure.message!!, workspace.toString())
    }

    @Test
    fun `a digest run that left an empty stream fails naming the path it expected`() {
        val workspace = dir.resolve("workspace")
        val expected = workspace.resolve("digests/leg.udeaeq")
        Files.createDirectories(expected.parent)
        Files.createFile(expected)

        val failure = assertFailsWith<IllegalStateException> {
            ReplayEqualityPaths.requireStreamWritten("digests/leg.udeaeq", workspace, expected)
        }

        assertContains(failure.message!!, expected.toString())
        assertContains(failure.message!!, "empty")
    }

    @Test
    fun `a stream with bytes in it passes and reports its size`() {
        // The control. A post-condition that fired on a healthy run would be no better than one
        // that never fires at all.
        val workspace = dir.resolve("workspace")
        val written = workspace.resolve("digests/leg.udeaeq")
        Files.createDirectories(written.parent)
        Files.writeString(written, "not a real digest, but it is not nothing either")

        assertEquals(
            Files.size(written),
            ReplayEqualityPaths.requireStreamWritten("digests/leg.udeaeq", workspace, written),
        )
    }

    @Test
    fun `the digest entry point still asks whether it wrote anything`() {
        // The one fact here that no behaviour can reach. Nothing a caller can hand today's
        // `ReplayDigestRecorder` makes it return having written nothing - the paths that could
        // go wrong throw an `IOException` from inside the write instead - so the post-condition
        // guards against a future change, and its removal would be caught by nothing else. Its
        // *behaviour* is the four tests above; this is only that `main` still calls it.
        val source = Files.readString(
            Path.of(System.getProperty("udea.projectDir")!!)
                .resolve("src/testFixtures/kotlin/dev/wildware/udea/replay/equality/fixture/DriftDigestMain.kt"),
        )

        assertContains(
            source, "ReplayEqualityPaths.requireStreamWritten(",
            message = "DriftDigestMain no longer checks that it wrote a stream, so a leg that " +
                "produced nothing would exit 0 and leave the upload step to report a glob two " +
                "lines later",
        )
    }

    @Test
    fun `a real leg writes a readable stream at the resolved path and says where it went`() {
        // End to end through the entry point CI runs, with the relative `--out` the workflow
        // passes: the 3600-tick fixture, a real headless replay, and a digest another leg could
        // be compared against - at `<workspace>/digests/`, which is where the upload step looks.
        val workspace = dir.resolve("workspace")
        Files.createDirectories(workspace)

        DriftDigestMain.main(
            arrayOf(
                "--workspace", workspace.toString(),
                "--label", "ubuntu-latest/temurin-17",
                "--out", "digests/ubuntu-latest-temurin.udeaeq",
            ),
        )

        val written = workspace.resolve("digests/ubuntu-latest-temurin.udeaeq")
        assertTrue(Files.isRegularFile(written), "no digest stream at $written")
        val digest = ReplayDigestIo.read(written)
        assertEquals("ubuntu-latest/temurin-17", digest.header.label)
        assertTrue(digest.tickCount > 0, "a digest of no ticks compares nothing")
    }
}
