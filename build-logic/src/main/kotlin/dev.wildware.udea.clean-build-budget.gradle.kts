import dev.wildware.udea.build.UdeaCleanBuildVerdictTask

/**
 * Registers `udeaCleanBuildVerdict`, the judging half of the `clean-build-budget` CI job
 * (issue #181).
 *
 * Applied to the **root** project, because the question - did this commit make a clean build of
 * the rewrite tree slower - is about `udeaAssemble` as a whole. Not on `check`: it reads samples a
 * CI job spent minutes timing, and there is nothing for an ordinary build to judge.
 *
 * `-Pudea.cleanBuild.samples=<file>` names the samples.
 */

plugins {
    base
}

tasks.register<UdeaCleanBuildVerdictTask>("udeaCleanBuildVerdict") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails if the timed head clean builds are slower than their base beyond the " +
        "tolerance. Run by the clean-build-budget CI job with -Pudea.cleanBuild.samples=<file>."
    // No default and no fallback: without the property Gradle refuses the task for having no
    // `samples` value, which names the missing input - and is better than a gate judging a stale
    // file from an earlier run, which would be answering about another commit.
    samples.set(layout.projectDirectory.file(providers.gradleProperty("udea.cleanBuild.samples")))
    summary.set(layout.buildDirectory.file("reports/udea/clean-build-verdict.md"))
    outputs.upToDateWhen { false }
}
