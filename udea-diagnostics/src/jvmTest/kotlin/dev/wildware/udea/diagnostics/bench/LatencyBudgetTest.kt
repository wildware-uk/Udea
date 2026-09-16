package dev.wildware.udea.diagnostics.bench

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The note is a diagnostic an agent acts on, so it is asserted like one.
 *
 * What has to hold: it names the task it is about, it hands over a command that re-runs *that*
 * task alone, and it states both conclusions rather than only the convenient one. A note that
 * said "the machine may have been busy" and stopped there would leave the reader exactly where
 * three waves of developers already were.
 */
class LatencyBudgetTest {

    @Test
    fun `the note names the task and a command that re-runs it alone`() {
        val note = LatencyBudget.contentionNote(":udea-assets-compiler:udeaDaemonBudget")
        assertTrue(":udea-assets-compiler:udeaDaemonBudget --rerun-tasks" in note, note)
        assertTrue("--no-parallel" in note && "--max-workers=1" in note, note)
    }

    @Test
    fun `the note states both outcomes of the re-run, not only the exonerating one`() {
        val note = LatencyBudget.contentionNote(":udea-core:udeaBenchTickLoop")
        assertTrue("Passing alone" in note, note)
        assertTrue("failing alone" in note, note)
        // And it does not offer the remedy the budgets' own KDoc forbids.
        assertTrue("never a wider budget" in note, note)
    }

    @Test
    fun `the machine state reports a load average as a number or says it has none`() {
        val state = LatencyBudget.machineState()
        val processors = ManagementFactory.getOperatingSystemMXBean().availableProcessors
        assertTrue("$processors processors" in state, state)

        val load = ManagementFactory.getOperatingSystemMXBean().systemLoadAverage
        if (load < 0) {
            assertTrue("does not report a load average" in state, state)
        } else {
            assertTrue("load average was" in state, state)
        }
        // The failure this guards is a negative sentinel printed as a measurement. Whichever
        // branch this machine took, a `-1.00` must never appear.
        assertFalse("-1.00" in state, state)
    }

    /**
     * [LatencyBudget.measuredBy] in all three of its states, because a guard nobody has watched
     * refuse is a guard nobody knows refuses.
     *
     * The property is set and cleared around each case rather than read from whatever task is
     * running this one: this test is not itself a latency budget, so the ambient value is
     * `:udea-diagnostics:test` and asserting against it would be asserting about the harness.
     */
    @Test
    fun `measuredBy refuses a budget run by the wrong task and allows one run by no task`() {
        val previous: String? = System.getProperty(LatencyBudget.TEST_TASK_PROPERTY)
        try {
            System.setProperty(LatencyBudget.TEST_TASK_PROPERTY, ":udea-core:udeaSnapshotBudget")
            LatencyBudget.measuredBy(":udea-core:udeaSnapshotBudget")

            System.setProperty(LatencyBudget.TEST_TASK_PROPERTY, ":udea-core:test")
            val refused = assertFailsWith<IllegalStateException> {
                LatencyBudget.measuredBy(":udea-core:udeaSnapshotBudget")
            }
            assertTrue(":udea-core:test" in refused.message.orEmpty(), refused.message.orEmpty())
            assertTrue(
                "excludeTestsMatching" in refused.message.orEmpty(),
                "the refusal must name the one line that puts a budget back where it belongs: " +
                    refused.message.orEmpty(),
            )

            // No property at all is an IDE run or a plain `java` invocation, and is allowed - the
            // guard is about the wrong Gradle task, not about making the class unrunnable.
            System.clearProperty(LatencyBudget.TEST_TASK_PROPERTY)
            LatencyBudget.measuredBy(":udea-core:udeaSnapshotBudget")
        } finally {
            if (previous == null) {
                System.clearProperty(LatencyBudget.TEST_TASK_PROPERTY)
            } else {
                System.setProperty(LatencyBudget.TEST_TASK_PROPERTY, previous)
            }
        }
    }
}
