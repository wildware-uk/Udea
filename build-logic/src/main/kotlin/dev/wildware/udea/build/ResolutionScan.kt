package dev.wildware.udea.build

import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.Provider

/**
 * Turns a Gradle [Configuration] into a [ResolvedGraph] of plain strings.
 *
 * This is the only file in the dependency-rule machinery that names a Gradle type. Keeping
 * it this thin is what lets every decision the rules make live in code a unit test calls
 * directly, rather than in a `doLast` block nothing can execute.
 */
public object ResolutionScan {

    /**
     * A lazy [ResolvedGraph] for [configuration], safe to hold as a task input under the
     * configuration cache: it is realised at execution time and contains nothing but
     * strings.
     *
     * Two sources are merged, because one is not enough:
     *
     * - the **resolution result**, which is the component graph and therefore the only
     *   thing that can answer "how did this get here" with a path.
     * - the **file-dependency artifacts**, which is the only place a file dependency shows up
     *   at all. `gradleApi()` is a file dependency; a component-graph-only scan would report
     *   `udea-assets-compiler` clean while the whole Gradle API sat on its classpath, which
     *   is precisely the leak `UDEA-MG-003` exists to catch.
     */
    public fun graphOf(configuration: Configuration): Provider<ResolvedGraph> =
        configuration.incoming.resolutionResult.rootComponent.zip(
            fileDependencyArtifacts(configuration),
        ) { root, artifacts ->
            val rootCoordinate = coordinateOf(root.id)
            val edges = LinkedHashSet<DependencyEdge>()
            collectEdges(root, edges, HashSet())
            artifacts.asSequence()
                .map { coordinateOf(it.id.componentIdentifier) }
                .forEach { edges += DependencyEdge(rootCoordinate, it) }
            ResolvedGraph(rootCoordinate, edges.toList())
        }

    /**
     * The artifacts on [configuration] that belong to no component in the resolution result
     * — that is, file dependencies such as `gradleApi()`.
     *
     * The component filter is doing real work, not tidying: an unfiltered `resolvedArtifacts`
     * asks Gradle to *produce* every project artifact on the classpath, which would make a
     * dependency-graph check compile the project it is checking. A gate that cannot run
     * until the code compiles cannot tell you why the code will not compile.
     */
    private fun fileDependencyArtifacts(configuration: Configuration): Provider<Set<ResolvedArtifactResult>> =
        configuration.incoming.artifactView {
            isLenient = true
            componentFilter { id -> id !is ModuleComponentIdentifier && id !is ProjectComponentIdentifier }
        }.artifacts.resolvedArtifacts

    private fun collectEdges(
        component: ResolvedComponentResult,
        edges: MutableSet<DependencyEdge>,
        visited: MutableSet<ComponentIdentifier>,
    ) {
        if (!visited.add(component.id)) return
        val from = coordinateOf(component.id)
        for (dependency in component.dependencies) {
            if (dependency !is ResolvedDependencyResult) continue
            val selected = dependency.selected
            edges += DependencyEdge(from, coordinateOf(selected.id))
            collectEdges(selected, edges, visited)
        }
    }

    /**
     * The normalised coordinate for [id]: the Gradle path for a project, `group:module` for
     * an external module, `file:<display name>` for anything else.
     *
     * The version is dropped on purpose. No rule here is version-sensitive, and a pattern
     * that had to match versions would go stale the first time a dependency was bumped.
     *
     * File dependencies keep Gradle's own display name rather than a filename, because that
     * is what identifies them: `gradleApi()` resolves to several jars under the single name
     * `Gradle API`, and a rule written against filenames would have to guess at the shape of
     * a Gradle distribution's lib directory.
     */
    private fun coordinateOf(id: ComponentIdentifier): String = when (id) {
        is ProjectComponentIdentifier -> id.projectPath
        is ModuleComponentIdentifier -> "${id.group}:${id.module}"
        else -> "file:${id.displayName}"
    }
}
