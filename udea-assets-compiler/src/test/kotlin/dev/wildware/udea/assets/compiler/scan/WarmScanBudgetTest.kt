package dev.wildware.udea.assets.compiler.scan

import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.diagnostics.bench.LatencyBudget
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The warm-scan budget from issue #85: pass 1 over the whole example tree in under 200ms.
 *
 * Measured over a scanner whose PSI environment is already built, because that is the daemon's
 * steady state: the environment is created once and reused across rescans. A cold measurement here
 * would be measuring `KotlinCoreEnvironment`'s constructor, which no rescan pays for.
 *
 * ## Why it is not in `ExampleScanTest` any more (issue #182)
 *
 * It was, and `ExampleScanTest` runs inside `:udea-assets-compiler:test`, which is on `check`,
 * which is on `build`. So this stopwatch was read while nineteen other modules compiled on the
 * same cores, which is the defect issue #175 exists to remove and which
 * `review-175-r1` found two survivors of. On this repository's box the difference between
 * measuring alone and measuring inside a parallel build is most of the number - a warm daemon
 * reload medians 195ms alone and 646ms inside a full `build` - and 200ms is not a line that
 * survives that.
 *
 * Splitting a whole test method rather than an assertion out of one, which is what made this the
 * cheap half of #182's two: the other assertion in it, that the scan found all nineteen files, is
 * the control that stops the budget timing an empty walk, and it belongs with the measurement it
 * controls. Every other claim `ExampleScanTest` makes about the scan - the golden, the spans, the
 * per-file cache, byte-identical output from two checkouts - is untouched and still on `check`.
 *
 * If it fails, the remedy is what pass 1 does per file, never a wider budget.
 */
class WarmScanBudgetTest {

    @Test
    fun `a warm scan of the whole tree is under 200ms`() {
        LatencyBudget.measuredBy(TASK)

        UdeaDeclarationScanner(TestPaths.repoRoot, TestPaths.exampleAssets).use { scanner ->
            scanner.scanTree() // warm the PSI environment and the JIT
            scanner.clearCache()
            val start = TimeSource.Monotonic.markNow()
            val report = scanner.scanTree()
            val elapsed = start.elapsedNow()

            println("warm scan of the example tree: $elapsed over ${report.files.size} files")
            // The control: a scan that found nothing would come in comfortably under any budget.
            assertEquals(19, report.files.size)
            assertTrue(
                elapsed < BUDGET,
                "warm scan took $elapsed, budget is $BUDGET. " + LatencyBudget.contentionNote(TASK),
            )
        }
    }

    private companion object {

        /** The task that measures this, and the one to re-run alone before believing a red. */
        const val TASK = ":udea-assets-compiler:udeaScanBudget"

        /** Issue #85's number. See the class KDoc before changing it. */
        val BUDGET = 200.milliseconds
    }
}
