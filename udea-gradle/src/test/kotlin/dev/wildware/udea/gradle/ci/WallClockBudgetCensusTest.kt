package dev.wildware.udea.gradle.ci

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #182: `udeaLatencyBudgets` says it *"measures every wall-clock latency budget"*, and that
 * sentence has been wrong twice.
 *
 * ## Why a sentence needs a test
 *
 * Issue #175 moved five gates off `check`, found two more while doing it, and left two behind.
 * `review-175-r1` found those two. Working #182 found three the issue had not named either -
 * `AssetCompilerTest`'s one-second warm compile, `PhysicsRebuildTest`'s 2ms rebuild and
 * `NetHarnessTest`'s two-second session bound. Three enumerations, three different answers, and
 * every one of them was written down as complete at the time.
 *
 * That is the failure this file removes. The aggregate's description is a claim about the whole
 * tree, so it is checked against the whole tree rather than trusted: every Kotlin test source in
 * the repository is read, and every one that takes a wall-clock reading has to be **either** a
 * declared latency budget **or** a row in [NOT_A_BUDGET] saying why its clock reading is not one.
 * A new timing test is red until its author decides which, and "I did not know the list existed"
 * stops being available.
 *
 * ## What it can see and what it cannot
 *
 * It reads source text, so it sees a clock read *in a test source file*. It does not see one taken
 * inside production code and handed back as a number - `DaemonLatencyBudgetTest` asserts
 * `report.durationMs`, which this scan cannot find and
 * [every declared budget names a task the aggregate measures] catches instead. The two halves are
 * complementary and neither is complete alone: one starts from the clock and looks for the
 * wiring, the other starts from the wiring and looks for the task.
 *
 * The disposition in a [NOT_A_BUDGET] row is a human judgement. What is machine-checked is that
 * the row exists, that it still names a file that reads a clock, and - the part that catches a
 * row whose prose has gone stale - that the file does not assert an elapsed time after all.
 */
class WallClockBudgetCensusTest {

    @Test
    fun `every wall-clock reading in a test source is a budget or a censused non-budget`() {
        val undecided = candidates()
            .filterKeys { it !in NOT_A_BUDGET }
            .filterValues { declaredTaskPaths(it).isEmpty() }
        assertTrue(
            undecided.isEmpty(),
            "these test sources read a wall clock and are neither a declared latency budget nor a " +
                "row in this file's census:\n" +
                undecided.entries.joinToString("\n") { (path, file) ->
                    "  $path reads ${file.readings.joinToString(", ")}"
                } +
                "\n\nIf the reading is asserted against a number of milliseconds it is a latency " +
                "budget: give it its own `Test` task, add that task to `latencyBudgetTasks` in " +
                "build.gradle.kts, and have it call `LatencyBudget.measuredBy` and " +
                "`LatencyBudget.contentionNote`. If it is a timeout, a seed or a printed figure, " +
                "add a row to `NOT_A_BUDGET` saying which.",
        )
    }

    @Test
    fun `no census row names a file that has stopped reading a clock`() {
        val found = candidates()
        val stale = NOT_A_BUDGET.keys.filter { it !in found }
        assertTrue(
            stale.isEmpty(),
            "these census rows no longer describe anything - the file was deleted, renamed, or " +
                "its clock reading was removed:\n" + stale.joinToString("\n") { "  $it" } +
                "\nDelete the row. A census with rows nobody can check is how a list stops being " +
                "read at all.",
        )
    }

    @Test
    fun `no censused file asserts an elapsed wall-clock time`() {
        val offenders = candidates()
            .filterKeys { it in NOT_A_BUDGET }
            .mapValues { (_, file) -> file.assertedElapsed() }
            .filterValues { it.isNotEmpty() }
        assertTrue(
            offenders.isEmpty(),
            "these files are censused as not being latency budgets, and they assert a value " +
                "derived from a wall-clock reading anyway:\n" +
                offenders.entries.joinToString("\n") { (path, lines) ->
                    "  $path\n" + lines.joinToString("\n") { "      $it" }
                } +
                "\nThe census row is wrong. Either the assertion is a latency budget and belongs " +
                "on `udeaLatencyBudgets`, or it is not about time and should not be comparing a " +
                "stopwatch reading.",
        )
    }

    @Test
    fun `every declared budget names a task the aggregate measures`() {
        val members = LatencyBudgetAggregate.members().toSet()
        val problems = mutableListOf<String>()
        for ((path, file) in declaredBudgets()) {
            val declared = declaredTaskPaths(file)
            val unresolved = declared.filterValues { it == null }.keys
            if (unresolved.isNotEmpty()) {
                problems += "$path names $unresolved, which is not a string literal and not a " +
                    "`val` or `const val` in the same file, so the task it claims to be measured " +
                    "by cannot be read"
                continue
            }
            val paths = declared.values.filterNotNull().toSet()
            (paths - members).forEach {
                problems += "$path says it is measured by `$it`, which is not in " +
                    "`${LatencyBudgetAggregate.MEMBER_LIST}`. A budget outside the aggregate is " +
                    "measured by no CI job, or measured inside `build` - the two failures issue " +
                    "#175 and issue #182 were each filed for."
            }
            CALLS.forEach { call ->
                if (call !in file.withoutComments) {
                    problems += "$path is a latency budget and never calls `$call`"
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun `the root build script tells every test task which task it is`() {
        // `LatencyBudget.measuredBy` compares this property against the budget's own task. Without
        // the property nothing is compared and the guard silently passes - the shape of defect
        // this repository has closed for the GL tests, the atlas tests and the build cache.
        val script = LatencyBudgetAggregate.rootBuildScript.readText()
            .lines().joinToString("\n") { it.substringBefore("//") }
        assertTrue(
            TEST_TASK_PROPERTY in script,
            "build.gradle.kts no longer sets `$TEST_TASK_PROPERTY` on the subprojects' test " +
                "tasks, so `LatencyBudget.measuredBy` has nothing to compare and every budget's " +
                "guard against being run inside `build` passes vacuously.",
        )
    }

    @Test
    fun `the scan is blind to comments and strings and not to code`() {
        // The known negative, run rather than assumed. A source fence that fires on a KDoc
        // mention is as useless as one that misses the call: `DeterminismScannerTest` holds
        // `System.nanoTime()` inside Java fixture text and `NoWallClockInTransportTest` holds it
        // inside a planted string, and neither is a measurement.
        val inACommentOnly = """
            /** Measures with System.nanoTime() and asserts elapsed < 5.milliseconds. */
            class X { // System.currentTimeMillis()
                fun f() = 1
            }
        """.trimIndent()
        val inAStringOnly = """
            class X {
                val fixture = "val t = System.nanoTime()"
                val raw = ${"\"\"\""}
                    val t = System.nanoTime()
                    ${"\"\"\""}
            }
        """.trimIndent()
        val inCode = """
            class X {
                fun f() {
                    val began = System.nanoTime()
                    val elapsedMs = (System.nanoTime() - began) / 1_000_000
                    val samples = listOf(elapsedMs)
                    val worst = samples.max()
                    assertTrue(worst < 500, "too slow")
                }
            }
        """.trimIndent()

        assertEquals(emptyList(), KotlinSource(inACommentOnly).readings, inACommentOnly)
        assertEquals(emptyList(), KotlinSource(inAStringOnly).readings, inAStringOnly)
        assertTrue(KotlinSource(inCode).readings.isNotEmpty(), "the scan missed a real measurement")
        assertEquals(
            1,
            KotlinSource(inCode).assertedElapsed().size,
            "the scan missed an assertion on a value two derivations from the clock: " +
                KotlinSource(inCode).assertedElapsed(),
        )
        assertEquals(emptyList(), KotlinSource(inACommentOnly).assertedElapsed())
    }

    @Test
    fun `the scan actually found sources to scan`() {
        // Without this every assertion above passes over an empty map the moment the layout
        // changes, which is exactly how a source-reading gate rots into a green test.
        val found = candidates()
        assertTrue(found.size >= 20, "only ${found.size} test source(s) read a clock; that is too " +
            "few for a repository this size, so the walk is looking in the wrong place")
        assertTrue(
            declaredBudgets().isNotEmpty(),
            "no declared latency budget was found, so the wiring assertions are vacuous",
        )

        val everySource = testSources().keys
        val goneMissing = DECLARES_THE_CONVENTION.filter { it !in everySource }
        assertTrue(
            goneMissing.isEmpty(),
            "these files are excluded from the budget wiring assertions on the grounds that they " +
                "define the convention, and they no longer exist:\n" +
                goneMissing.joinToString("\n") { "  $it" } +
                "\nAn exclusion for a file nobody can open silently excludes nothing, or worse, " +
                "keeps excluding a path something else later takes.",
        )
    }

    // ---------------------------------------------------------------------------------------

    /** Every Kotlin test source in the repository that reads a wall clock, by repo-relative path. */
    private fun candidates(): Map<String, KotlinSource> =
        testSources().filterValues { it.readings.isNotEmpty() }

    /**
     * Every Kotlin test source that declares itself a latency budget, by repo-relative path.
     *
     * [DECLARES_THE_CONVENTION] is taken out first. Those files name the calls in order to define
     * or to test them, which reads identically to using them - the same false positive
     * `NoWallClockInTransportTest` has to exclude a KDoc mention for, one level up.
     */
    private fun declaredBudgets(): Map<String, KotlinSource> = testSources()
        .filterKeys { it !in DECLARES_THE_CONVENTION }
        .filterValues { declaredTaskPaths(it).isNotEmpty() }

    /**
     * Every Kotlin file under a `src/test` or `src/testFixtures` directory in the repository.
     *
     * A whole-tree walk with the generated and version-control directories pruned, rather than a
     * list of module names: a fence over an enumeration of modules is a fence that stops covering
     * the module somebody adds next, which is the same defect one level up.
     */
    private fun testSources(): Map<String, KotlinSource> {
        val root = LatencyBudgetAggregate.repoRoot
        val sources = root.walkTopDown()
            .onEnter { it.name !in PRUNED }
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') to it }
            .filter { (path, _) -> "/src/test/" in path || "/src/testFixtures/" in path }
            .associate { (path, file) -> path to KotlinSource(file.readText()) }
        check(sources.isNotEmpty()) {
            "no Kotlin test source found under ${root.absolutePath}; the fence would pass over " +
                "nothing"
        }
        return sources
    }

    /**
     * The task path each `LatencyBudget.measuredBy` / `contentionNote` call in [source] names, by
     * the argument as written; `null` where the argument is an identifier the file does not
     * declare.
     *
     * The call sites are read out of [KotlinSource.withoutComments] so a KDoc that merely names
     * the convention is not mistaken for following it, and the `const val` is resolved out of the
     * same text because the answer *is* a string literal.
     */
    private fun declaredTaskPaths(source: KotlinSource): Map<String, String?> {
        val text = source.withoutComments
        return CALLS
            .flatMap { call -> Regex(Regex.escape(call) + "([^)]*)\\)").findAll(text).toList() }
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .associateWith { argument ->
                when {
                    argument.startsWith("\"") -> argument.trim('"')
                    else -> Regex("(?:const )?val $argument(?:: String)? = \"([^\"]+)\"")
                        .find(text)?.groupValues?.get(1)
                }
            }
    }

    private companion object {

        /** The two calls that make a test declare itself a latency budget. */
        val CALLS = listOf("LatencyBudget.measuredBy(", "LatencyBudget.contentionNote(")

        /** Directories a source walk must not descend into. */
        val PRUNED = setOf(".git", ".gradle", ".claude", "build", "node_modules")

        /**
         * The files that define the convention and the one that tests it, rather than following
         * it.
         *
         * Both name `LatencyBudget.measuredBy` and `LatencyBudget.contentionNote` as text, and
         * this file's own control cases contain `System.nanoTime()` inside string literals. A
         * fence that cannot tell a definition from a use would report itself.
         *
         * `no census row names a file that has stopped reading a clock` does not cover this set,
         * so `the scan actually found sources to scan` checks that each entry still exists.
         */
        val DECLARES_THE_CONVENTION = setOf(
            "udea-diagnostics/src/test/kotlin/dev/wildware/udea/diagnostics/bench/LatencyBudgetTest.kt",
            "udea-gradle/src/test/kotlin/dev/wildware/udea/gradle/ci/WallClockBudgetCensusTest.kt",
        )

        /** The system property the root build script puts on every subproject `Test` task. */
        const val TEST_TASK_PROPERTY = "udea.testTaskPath"

        /**
         * Every test source that reads a wall clock without asserting a latency, and why.
         *
         * A row is a decision recorded, not an exemption granted: the file was read, and the
         * reading was found to be one of four things that are not a latency gate.
         *
         * - **a deadline** - the reading bounds how long a poll or a socket read waits for
         *   something to happen. Missing it means the event never arrived, which is a different
         *   failure from "the code got slower", and widening it does not hide a regression.
         * - **a seed** - `Random(System.nanoTime())`, so a proof that must not repeat the same
         *   pilot on every run does not.
         * - **printed, not asserted** - a figure the log carries for a human, with the machine-
         *   independent assertion that actually catches the regression alongside it.
         * - **a ratio** - two readings from the same machine divided by each other, which is a
         *   claim about complexity and cancels the machine out.
         */
        val NOT_A_BUDGET: Map<String, String> = mapOf(
            "moba/src/test/kotlin/dev/wildware/moba/net/MobaUdpTwoProcessTest.kt" to
                "a deadline: how long to wait for a line from a forked process",
            "moba/src/test/kotlin/dev/wildware/moba/replay/MobaReplayProofTest.kt" to
                "a seed: the pilot must differ between runs or the proof repeats one run",
            "udea-agent-host/src/test/kotlin/dev/wildware/udea/agent/host/LiveInstance.kt" to
                "a deadline: how long to poll a live instance for a state change",
            "udea-agent-host/src/test/kotlin/dev/wildware/udea/agent/host/gl/OverlayCaptureIsolationTest.kt" to
                "a deadline: how long to wait for the render thread to reach a frame",
            "udea-agent-host/src/test/kotlin/dev/wildware/udea/agent/host/net/NetSessionEndToEndTest.kt" to
                "a deadline: how long to poll for a session to converge",
            "udea-assets-compiler/src/test/kotlin/dev/wildware/udea/assets/compiler/AssetCompilerTest.kt" to
                "printed, not asserted: the cache-hit count is what catches a cold recompile",
            "udea-assets-compiler/src/test/kotlin/dev/wildware/udea/assets/compiler/transpile/TranspilerParityTest.kt" to
                "printed, not asserted: the go/no-go figures for issue #87",
            "udea-assets-compiler/src/test/kotlin/dev/wildware/udea/assets/compiler/worker/WorkerTest.kt" to
                "printed, not asserted: the cost of forking the worker, for the log",
            "udea-core/src/test/kotlin/dev/wildware/udea/core/host/HeadlessHostTest.kt" to
                "a deadline: how long to wait for the host loop to reach a tick",
            "udea-core/src/test/kotlin/dev/wildware/udea/core/identity/NetIdIndexTest.kt" to
                "a ratio: resolution at 64 000 ids over resolution at 64, which is an O(1) claim",
            "udea-net/src/test/kotlin/dev/wildware/udea/net/proof/UdpProofClient.kt" to
                "a deadline: the socket read timeout in the UDP proof client",
            "udea-net/src/test/kotlin/dev/wildware/udea/net/proof/UdpProofServer.kt" to
                "a deadline: the socket read timeout in the UDP proof server",
            "udea-net/src/test/kotlin/dev/wildware/udea/net/proof/UdpTwoProcessTest.kt" to
                "a deadline: how long to wait for a line from a forked process",
            "udea-render/src/test/kotlin/dev/wildware/udea/render/capture/CaptureOrderingTest.kt" to
                "a deadline: how long to wait for a captured frame",
            "udea-render/src/test/kotlin/dev/wildware/udea/render/gl/GlOverlayIsolationTest.kt" to
                "a deadline: how long to wait for the render thread to reach a frame",
            "udea-render/src/test/kotlin/dev/wildware/udea/render/gl/OffscreenBackendTest.kt" to
                "a deadline: how long to wait for the render thread to reach a frame",
            "udea-replay/src/test/kotlin/dev/wildware/udea/replay/ReplayEngineTest.kt" to
                "a seed: the pilot must differ between runs",
            "udea-replay/src/test/kotlin/dev/wildware/udea/replay/ReplayToolTest.kt" to
                "a seed: the pilot must differ between runs",
        )
    }
}
