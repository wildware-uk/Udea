package dev.wildware.udea.build

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.TaskProvider

/**
 * Registers a task that resolves this project's classpaths and fails on any [DependencyRule]
 * they break.
 *
 * Used by `dev.wildware.udea.module-graph-check`, whose rules are data so that this function is the only
 * thing that resolves a classpath and decides what on it is a violation.
 *
 * @param taskName the registered task name, quoted in the failure heading.
 * @param description shown by `gradlew tasks`.
 * @param configurationNames the classpaths to scan; anything absent from the project is
 *   skipped rather than being an error, since `testFixturesRuntimeClasspath` only exists
 *   where `java-test-fixtures` is applied. On a multiplatform module each target's copy of one
 *   of these is scanned in its place and governed as the classpath it stands for
 *   ([UdeaMultiplatform.jvmRole], issue #201).
 * @param rules evaluated against every scanned classpath.
 * @param reportFileName written under `build/reports/udea/`, so a passing run still leaves
 *   behind what it actually looked at.
 */
public fun Project.registerDependencyVerification(
    taskName: String,
    description: String,
    configurationNames: Set<String>,
    rules: List<DependencyRule>,
    reportFileName: String,
): TaskProvider<Task> {
    val graphs: MapProperty<String, ResolvedGraph> =
        objects.mapProperty(String::class.java, ResolvedGraph::class.java)

    configurations.matching { roleOf(it.name) in configurationNames && it.isCanBeResolved }.all {
        graphs.put(name, ResolutionScan.graphOf(this))
    }

    val projectPath = path
    val report = layout.buildDirectory.file("reports/udea/$reportFileName")

    val task = tasks.register(taskName) {
        this.group = "verification"
        this.description = description

        inputs.property("resolvedGraphs", graphs)
        inputs.property("rules", rules.map { it.toString() })
        outputs.file(report)

        doLast {
            val scanned = graphs.get()
            if (scanned.isEmpty()) {
                throw GradleException(
                    "$projectPath matched none of the configurations $configurationNames, so " +
                        "$taskName inspected nothing. A gate with no input passes forever; fix " +
                        "the configuration list rather than the module.",
                )
            }
            val vacuous = scanned.entries.sortedBy { it.key }
                .firstNotNullOfOrNull { (configuration, graph) ->
                    DependencyRules.vacuity(projectPath, configuration, graph, rules, roleOf(configuration))
                }
            if (vacuous != null) throw GradleException(vacuous)
            val violations = scanned.entries.sortedBy { it.key }
                .flatMap { (configuration, graph) ->
                    DependencyRules.violations(projectPath, configuration, graph, rules, roleOf(configuration))
                }
            report.get().asFile.apply {
                parentFile.mkdirs()
                writeText(
                    scanned.entries.sortedBy { it.key }.joinToString(separator = "\n", postfix = "\n") { (name, graph) ->
                        "$name: ${graph.components().sorted().joinToString()}"
                    },
                )
            }
            DependencyRules.report(taskName, violations)?.let { throw GradleException(it) }
        }
    }

    tasks.named("check") { dependsOn(task) }
    return task
}

/** The JVM classpath name [configurationName] is governed as: itself, or a target's JVM role. */
private fun roleOf(configurationName: String): String =
    UdeaMultiplatform.jvmRole(configurationName) ?: configurationName
