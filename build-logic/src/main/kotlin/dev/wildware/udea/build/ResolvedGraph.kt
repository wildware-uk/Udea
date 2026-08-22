package dev.wildware.udea.build

import java.io.Serializable

/** A single resolved edge, `from` depends on `to`, in normalised coordinates. */
public data class DependencyEdge(
    public val from: String,
    public val to: String,
) : Serializable

/**
 * A resolved dependency graph flattened to plain strings.
 *
 * The Gradle-typed `ResolutionResult` is turned into this at provider-evaluation time so
 * that the verification tasks hold nothing the configuration cache cannot serialise, and so
 * that every interesting decision — reachability, the path from the root — is made by code
 * a unit test can call directly.
 *
 * Coordinates are normalised: `group:module` for an external module (the version is
 * deliberately dropped, because no rule here is version-sensitive and including it would
 * make rule patterns brittle), the Gradle path for a project.
 */
public data class ResolvedGraph(
    public val root: String,
    public val edges: List<DependencyEdge>,
) : Serializable {

    /** Every coordinate reachable from [root], the root itself included. */
    public fun components(): Set<String> =
        (sequenceOf(root) + edges.asSequence().flatMap { sequenceOf(it.from, it.to) }).toSet()

    /**
     * The shortest path from [root] to [coordinate], inclusive of both, or an empty list if
     * [coordinate] is not reachable.
     *
     * Shortest rather than any path on purpose: it is the one a reader can act on. Breadth
     * first, so a cycle in the graph terminates rather than being followed forever.
     */
    public fun pathFromRoot(coordinate: String): List<String> {
        if (coordinate == root) return listOf(root)
        val outgoing = edges.groupBy({ it.from }, { it.to })
        val cameFrom = HashMap<String, String>()
        val visited = hashSetOf(root)
        val queue = ArrayDeque<String>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (next in outgoing[current].orEmpty()) {
                if (!visited.add(next)) continue
                cameFrom[next] = current
                if (next == coordinate) return reconstruct(cameFrom, coordinate)
                queue.add(next)
            }
        }
        return emptyList()
    }

    private fun reconstruct(cameFrom: Map<String, String>, target: String): List<String> {
        val reversed = ArrayList<String>()
        var cursor: String? = target
        while (cursor != null) {
            reversed.add(cursor)
            cursor = cameFrom[cursor]
        }
        return reversed.asReversed().toList()
    }
}
