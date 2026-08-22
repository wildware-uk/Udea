import dev.wildware.udea.build.ReleaseRules
import dev.wildware.udea.build.ResolutionScan
import dev.wildware.udea.build.ResolvedGraph
import dev.wildware.udea.build.UdeaVerifyReleaseTask
import org.gradle.api.tasks.bundling.AbstractArchiveTask

/**
 * Registers `udeaVerifyRelease` on the project that ships — `:moba` — and finalises
 * `assemble` with it so it cannot be forgotten.
 *
 * TODO: move to `udea-gradle` once that module has content. It lives in `build-logic` now
 * because the gate has to exist before `udea-agent` does; a gate added after the thing it
 * guards is a gate that was once absent.
 */

plugins {
    base
}

/** This project's Gradle path, captured outside the task block where `path` is the task's. */
val modulePath: String = path

/** A release build is `-Pudea.release=true`. Anything else is a development build. */
val isReleaseBuild: Boolean =
    providers.gradleProperty("udea.release").map { it == "true" }.getOrElse(false)

/**
 * Every archive this project produces — the jar today, `distZip`/`distTar` the day a
 * distribution is added. Selecting by task type rather than by name is what stops the gate
 * silently narrowing when the packaging changes.
 */
val packagedArtifacts = files(tasks.withType(AbstractArchiveTask::class.java))

/** `runtimeClasspath` as a resolved graph, for the model half of the check. */
val releaseClasspaths = objects.mapProperty(String::class.java, ResolvedGraph::class.java)

configurations.matching { it.name in ReleaseRules.CLASSPATH_RULE.configurations && it.isCanBeResolved }.all {
    releaseClasspaths.put(name, ResolutionScan.graphOf(this))
}

val udeaVerifyRelease by tasks.registering(UdeaVerifyReleaseTask::class) {
    group = "verification"
    description = "Fails a release build if an agent class is in the packaged artifact or on the runtime classpath."

    archives.from(packagedArtifacts)
    bannedPrefixes.convention(ReleaseRules.DEFAULT_BANNED_PREFIXES)
    projectPath.set(modulePath)
    classpaths.set(releaseClasspaths)
    report.set(layout.buildDirectory.file("reports/udea/release-scan.txt"))

    // Release-only by design: a development build is *supposed* to carry the agent surface,
    // and a gate that failed on it would be a gate people learn to pass -Pudea.release=false
    // around. The `-Pudea.release=true` build is the one that ships.
    //
    // Copied into a local first so the spec closes over a boolean rather than over this
    // script: a `Spec` holding a script reference cannot be stored in the configuration
    // cache, and the build fails at storage time rather than at the gate.
    val releaseBuild = isReleaseBuild
    onlyIf("udea.release=true") { releaseBuild }
}

tasks.named("assemble") {
    finalizedBy(udeaVerifyRelease)
}
