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
 * Fails the build if anything outside [leafAllowList] resolves on `runtimeClasspath`.
 *
 * This module is on the compile classpath of the engine, the game, the processor and the
 * compiler plugin at once, so anything it drags in is dragged everywhere. Wired into
 * `check` so a stray dependency cannot survive a normal build.
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

    doLast {
        val resolved = moduleIds.get().toSortedSet()
        val offenders = resolved - allowed
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(resolved.joinToString(separator = "\n", postfix = "\n"))
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "udea-annotations must stay a zero-dependency leaf (spec 4), but its runtimeClasspath " +
                    "resolves ${offenders.size} disallowed dependency/dependencies: " +
                    offenders.joinToString() + ". Allowed: " + allowed.sorted().joinToString() + ".",
            )
        }
    }
}

tasks.named("check") {
    dependsOn(udeaVerifyAnnotationsLeaf)
}
