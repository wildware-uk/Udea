import dev.wildware.udea.build.UdeaLeafCheck
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    id("udea.kotlin-library")
}

/**
 * Everything a game, the engine, the KSP processor and the K2 plugin may all be made to
 * carry by depending on this module. The Kotlin stdlib and the `org.jetbrains:annotations`
 * artifact it drags in are the entire budget (spec 4).
 */
val leafAllowList = setOf(
    "org.jetbrains.kotlin:kotlin-stdlib",
    "org.jetbrains:annotations",
)

/**
 * `group:module` for every artifact on `runtimeClasspath`, resolved lazily. Mapped to
 * plain strings at the provider level so the task holds nothing the configuration cache
 * cannot serialise.
 */
val runtimeModuleIds: Provider<List<String>> =
    configurations.named("runtimeClasspath").flatMap { configuration ->
        configuration.incoming.artifacts.resolvedArtifacts.map { artifacts ->
            artifacts.map { artifact ->
                when (val id = artifact.id.componentIdentifier) {
                    is ModuleComponentIdentifier -> "${id.group}:${id.module}"
                    else -> id.displayName
                }
            }.distinct().sorted()
        }
    }

/**
 * Fails the build if anything outside [leafAllowList] resolves on `runtimeClasspath`, or
 * if nothing resolves at all.
 *
 * This module is on the compile classpath of the engine, the game, the processor and the
 * compiler plugin at once, so anything it drags in is dragged everywhere. Wired into
 * `check` so a stray dependency cannot survive a normal build.
 *
 * The rule itself lives in `UdeaLeafCheck` in `build-logic`, where `UdeaLeafCheckTest`
 * executes its failure paths. A `doLast` block is not reachable from any test, so a gate
 * whose logic lived only here would be enforcement nobody has ever watched fail — and its
 * worst failure is silent: an empty `runtimeClasspath` has no offenders either.
 */
val udeaVerifyAnnotationsLeaf by tasks.registering {
    group = "verification"
    description = "Fails if anything but the Kotlin stdlib resolves on udea-annotations' runtimeClasspath."

    val moduleIds = runtimeModuleIds
    val allowed = leafAllowList
    val report = layout.buildDirectory.file("reports/udea/annotations-leaf.txt")

    inputs.property("allowList", allowed.sorted())
    inputs.property("runtimeModuleIds", moduleIds)
    outputs.file(report)

    val projectPath = project.path

    doLast {
        val resolved = moduleIds.get().toSortedSet()
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(resolved.joinToString(separator = "\n", postfix = "\n"))
        }
        UdeaLeafCheck.violation(projectPath, resolved, allowed)?.let { throw GradleException(it) }
    }
}

tasks.named("check") {
    dependsOn(udeaVerifyAnnotationsLeaf)
}
