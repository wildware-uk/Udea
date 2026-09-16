package dev.wildware.udea.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Reads the samples the `clean-build-budget` job timed and fails when [CleanBuildComparison]
 * says the head regressed.
 *
 * A task rather than arithmetic in `ci.yml`, so the rule that decides the verdict is the one
 * `CleanBuildComparisonTest` executes. The job does the timing, because a Gradle task cannot time
 * a clean build of the build it is running in; this does the judging.
 */
public abstract class UdeaCleanBuildVerdictTask : DefaultTask() {

    /** One `<base|head> <milliseconds>` row per timed build, as the job writes them. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val samples: RegularFileProperty

    /**
     * The markdown table the job appends to its step summary. Written before the verdict is
     * enforced, so a red run shows the numbers that made it red.
     */
    @get:OutputFile
    public abstract val summary: RegularFileProperty

    /** Judges the samples, writes [summary], and fails the build on a regression. */
    @TaskAction
    public fun judge() {
        val verdict = CleanBuildComparison.judge(CleanBuildComparison.parse(samples.get().asFile.readText()))
        val table = CleanBuildComparison.summary(verdict)
        summary.get().asFile.apply {
            parentFile.mkdirs()
            writeText(table)
        }
        logger.lifecycle(table)
        if (verdict is CleanBuildComparison.Verdict.Regressed) {
            throw GradleException(
                "a clean build of this commit took ${verdict.headEstimate.inWholeMilliseconds} ms " +
                    "against ${verdict.baseEstimate.inWholeMilliseconds} ms for its base on the same " +
                    "runner, a ratio of ${"%.3f".format(java.util.Locale.ROOT, verdict.ratio)} over the " +
                    "${CleanBuildComparison.TOLERANCE} tolerance. Both are the fastest of their samples, " +
                    "so this is the commit and not the machine: find what it added to udeaAssemble.",
            )
        }
    }
}
