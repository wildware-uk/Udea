package dev.wildware.udea.net.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The gate issue #105 asks for: no wall clock and no unseeded randomness in the send path — and,
 * since issue #182, no `Thread.sleep` either.
 *
 * `System.nanoTime`, `System.currentTimeMillis`, `Math.random` and `kotlin.random.Random` each
 * turn a reproducible failure into a flaky one, and every one of them compiles, runs and passes
 * every other test in this module. `Thread.sleep` is the same shape of defect one step further
 * on: a harness that sleeps still produces the right packets, so nothing here would notice, and
 * `NetHarness` promises in its own KDoc that it does not. So the check is over the source text,
 * which is the only place any of these differences is visible.
 *
 * It scans the packages that decide *what leaves and when* — the transport, the wire format, the
 * replication send loop and the input path. It deliberately does not scan the test sources, where
 * reading a clock to measure a test is a statement about the test rather than about the
 * simulation; `WallClockBudgetCensusTest` is what governs those.
 *
 * This is a source scan rather than a Gradle task on purpose. A `doLast` block is not reachable
 * from any test, so a Gradle-task version of this rule could not itself be tested; here
 * [theScanFindsAPlantedViolation] executes the failure branch on a string it plants, so the rule
 * is proven able to fail.
 */
class NoWallClockInTransportTest {

    @Test
    fun `no non-deterministic time or randomness in the send path`() {
        val offenders = mutableListOf<String>()
        for (file in scannedFiles()) {
            val text = file.readText()
            for (line in text.lineSequence().withIndex()) {
                if (isComment(line.value)) continue
                val found = BANNED.firstOrNull { line.value.contains(it) } ?: continue
                offenders += "${file.name}:${line.index + 1} uses $found"
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "the transport and replication path must be driven by ManualClock and SimRandom " +
                "only, so a failure reproduces from its seed:\n" + offenders.joinToString("\n"),
        )
    }

    @Test
    fun theScanFindsAPlantedViolation() {
        val planted = "    private val now = System.nanoTime()"
        assertTrue(BANNED.any { planted.contains(it) }, "the scan would not notice System.nanoTime")
        assertTrue(isComment(" * System.nanoTime is banned here"), "a KDoc mention would be a false positive")

        // The control for `Thread.sleep`, and it is not decoration: both files this rule is really
        // about - `NetHarness` and `Transport` - already *name* `Thread.sleep` in their KDoc, so a
        // scan that counted a prose mention would have gone red the moment the rule was added and
        // for entirely the wrong reason.
        val sleeping = "        Thread.sleep(16)"
        assertTrue(BANNED.any { sleeping.contains(it) }, "the scan would not notice Thread.sleep")
        assertTrue(
            isComment(" * No sockets, no threads, no `Thread.sleep`, and a manual clock"),
            "the line NetHarness's own KDoc carries must read as a comment, not a violation",
        )
    }

    @Test
    fun `the scan actually found source files to scan`() {
        // Without this the scan passes vacuously the moment the directory layout changes, which
        // is precisely how a build-time gate rots into a green test that checks nothing.
        val files = scannedFiles()
        assertTrue(files.size >= 8, "the scan found only ${files.size} source file(s)")
        assertTrue(files.any { it.name == "SimulatedTransport.kt" }, "the simulation itself was not scanned")
    }

    private fun scannedFiles(): List<File> {
        val projectDir = File(System.getProperty("udea.projectDir") ?: ".")
        val root = File(projectDir, "src/main/kotlin/dev/wildware/udea/net")
        return SCANNED_PACKAGES
            .map { File(root, it) }
            .filter(File::isDirectory)
            .flatMap { it.walkTopDown().filter { file -> file.extension == "kt" }.toList() }
            .ifEmpty { fail("no source found under ${root.absolutePath}") }
    }

    private fun isComment(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
    }

    private companion object {

        val SCANNED_PACKAGES = listOf("transport", "wire", "replication", "input", "bits")

        val BANNED = listOf(
            "System.nanoTime",
            "System.currentTimeMillis",
            "Math.random",
            "kotlin.random.Random",
            "java.util.Random",
            // Added by issue #182. `NetHarness`'s KDoc has always promised "no sockets, no
            // threads, no `Thread.sleep`, and a manual clock", and the only thing holding the
            // last of those was a two-second wall-clock bound in `NetHarnessTest` - a stopwatch
            // read inside `./gradlew build`, and a 40x-headroom proxy for the property it stood
            // in for. This asserts the property itself: exactly, by file and line, on any machine.
            "Thread.sleep",
        )
    }
}
