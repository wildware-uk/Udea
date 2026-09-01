package dev.wildware.udea.diagnostics.bench

import java.lang.management.ManagementFactory
import kotlin.test.Test
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
}
