package dev.wildware.udea.diagnostics.bench

import java.lang.management.ManagementFactory

/**
 * The sentence every wall-clock budget in this repository ends its failure message with.
 *
 * ## Why it exists (issue #175)
 *
 * A latency budget has two ways to go red and they need completely different responses. Either
 * the code got slower, which is the regression the gate is for, or the machine was busy while it
 * was measured, which is not a fact about the code at all. The measurements are two-to-four times
 * apart between those two states on this repository's own hardware, so the second case is not a
 * rounding error - it is most of the number.
 *
 * Three waves of developers hit the second case and each worked it out the same way: by
 * re-running the task alone and seeing it pass. Nothing in the failure said to. That rediscovery
 * is the cost this note removes, and it is why the note carries the machine's own state rather
 * than only advice: a load average of 16 across 24 processors is evidence, and "the machine may
 * have been busy" is a shrug.
 *
 * ## Where these budgets are supposed to be measured
 *
 * On `udeaLatencyBudgets`, the root aggregate, run by the `latency-budgets` CI job with
 * `--no-parallel --max-workers=1` so nothing else on the runner is competing for a core. Reaching
 * one of them any other way - through `check`, or beside a `build` - measures the build.
 */
object LatencyBudget {

    /**
     * Why [taskPath] may have missed its budget, and what to do before calling it a regression.
     *
     * Appended to the assertion message rather than printed, because the assertion message is
     * what reaches a test report, a CI annotation and an agent reading the failure - and a
     * `println` is what reaches none of those when `showStandardStreams` is off.
     */
    fun contentionNote(taskPath: String): String = buildString {
        append("This is a wall-clock latency measurement, and a wall-clock measurement taken ")
        append("beside a parallel build measures the build. It is meant to be taken by the ")
        append("`latency-budgets` CI job, which runs `udeaLatencyBudgets` with ")
        append("`--no-parallel --max-workers=1` and has the runner to itself (issue #175). ")
        append(machineState())
        append(" Before recording this as a regression, re-run it alone: ")
        append("`./gradlew $taskPath --rerun-tasks --no-parallel --max-workers=1`. ")
        append("Passing alone means the machine was busy and the code is no slower; ")
        append("failing alone means the code is slower, and the remedy is the one this test's ")
        append("KDoc names, never a wider budget.")
    }

    /**
     * What the machine was doing, as far as this JVM can see it.
     *
     * `getSystemLoadAverage` is the one-minute run-queue average and is exactly the quantity that
     * separates the two cases. It returns a negative value where the operating system does not
     * publish one - Windows, notably, which is half of this repository's CI matrix - and that is
     * reported as unavailable rather than printed as a number, because `-1.0` read as a load
     * average is worse than no load average.
     */
    fun machineState(): String {
        val os = ManagementFactory.getOperatingSystemMXBean()
        val load = os.systemLoadAverage
        val processors = os.availableProcessors
        return if (load < 0) {
            "This machine has $processors processors; it does not report a load average, " +
                "so whether anything else was running is not visible from here."
        } else {
            "This machine has $processors processors and its one-minute load average was " +
                "${"%.2f".format(load)} when the budget was missed."
        }
    }
}
