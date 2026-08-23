package dev.wildware.udea.core.module

import java.util.PriorityQueue

/** A `from must run before to` edge between two nodes, by node index. */
internal data class OrderEdge(val from: Int, val to: Int)

/**
 * The deterministic topological sort behind [SimRegistry].
 *
 * A pure function over indices rather than a method on the registry, for the same reason
 * `udea-render`'s `RenderOrder` is: the interesting failure mode is a cycle, and a pure
 * function over indices can be driven straight into it by a test, whereas a sort tangled up
 * with instantiating systems cannot.
 *
 * ## Determinism
 *
 * Kahn's algorithm with the ready set in a min-heap on **registration index**. That is the
 * tie-break the design promises: two systems with no constraint between them run in the order
 * they were registered, on every machine and every run. A `HashSet` here would make tick order
 * depend on hash iteration order — nondeterminism that presents as a desync nobody can
 * reproduce.
 *
 * The exact rule, since two reasonable ones exist: **a node runs as soon as its own
 * constraints allow**, and among nodes whose constraints are all satisfied the lowest
 * registration index wins. So a constraint delays the node it constrains and everything
 * downstream of it, and an unconstrained node may overtake a delayed one.
 *
 * ## Duplication with `udea-render`
 *
 * `udea-render.RenderOrder` is the same algorithm, and its own KDoc records that it should
 * move here and be shared once `SimRegistry` exists. Collapsing the two means editing
 * `udea-render`, which this change deliberately does not touch; until then this stays
 * `internal` so the duplicate is a known, single-owner cleanup rather than a second public
 * API to keep in step.
 */
internal object SystemOrder {

    /**
     * Node indices `0 until nodeCount` in execution order.
     *
     * @param edges `from` runs before `to`. Duplicates are harmless; a self-edge is a cycle.
     * @param describe names a node for the failure message.
     * @throws SystemOrderException if the constraints contain a cycle. The message contains
     *   the cycle itself — `A -> B -> A` — because "there is a cycle somewhere among your
     *   forty systems" is not an actionable failure.
     */
    fun sort(nodeCount: Int, edges: List<OrderEdge>, describe: (Int) -> String): List<Int> {
        require(nodeCount >= 0) { "nodeCount must not be negative, was $nodeCount" }

        val successors = Array(nodeCount) { ArrayList<Int>() }
        val inDegree = IntArray(nodeCount)
        for (edge in edges) {
            require(edge.from in 0 until nodeCount && edge.to in 0 until nodeCount) {
                "edge $edge is outside 0 until $nodeCount"
            }
            successors[edge.from] += edge.to
            inDegree[edge.to]++
        }

        val ready = PriorityQueue<Int>()
        for (node in 0 until nodeCount) {
            if (inDegree[node] == 0) ready += node
        }

        val ordered = ArrayList<Int>(nodeCount)
        while (ready.isNotEmpty()) {
            val node = ready.poll()
            ordered += node
            for (next in successors[node]) {
                if (--inDegree[next] == 0) ready += next
            }
        }

        if (ordered.size != nodeCount) {
            val unresolved = (0 until nodeCount).filter { inDegree[it] > 0 }.toSet()
            throw SystemOrderException(
                "system order constraints contain a cycle: " +
                    findCycle(unresolved, successors).joinToString(" -> ", transform = describe),
            )
        }
        return ordered
    }

    /**
     * A concrete cycle among [candidates], as a node list whose first element repeats at the
     * end. Depth-first, iterative, so a pathological graph cannot blow the stack.
     */
    private fun findCycle(candidates: Set<Int>, successors: Array<ArrayList<Int>>): List<Int> {
        val onPath = LinkedHashSet<Int>()
        val exhausted = HashSet<Int>()

        for (start in candidates.sorted()) {
            val cycle = walk(start, candidates, successors, onPath, exhausted)
            if (cycle != null) return cycle
        }
        // Unreachable: Kahn only leaves nodes behind when at least one cycle exists among
        // them, and every such node is in `candidates`. Failing loudly beats returning a
        // misleading empty cycle if that reasoning is ever wrong.
        error("system order left ${candidates.size} node(s) unresolved but no cycle was found")
    }

    private fun walk(
        start: Int,
        candidates: Set<Int>,
        successors: Array<ArrayList<Int>>,
        onPath: LinkedHashSet<Int>,
        exhausted: MutableSet<Int>,
    ): List<Int>? {
        if (start in exhausted) return null
        val stack = ArrayList<Iterator<Int>>()
        val path = ArrayList<Int>()

        fun push(node: Int) {
            path += node
            onPath += node
            stack += successors[node].filter { it in candidates }.sorted().iterator()
        }

        push(start)
        while (stack.isNotEmpty()) {
            val top = stack.last()
            if (!top.hasNext()) {
                val done = path.removeAt(path.lastIndex)
                onPath.remove(done)
                exhausted += done
                stack.removeAt(stack.lastIndex)
                continue
            }
            val next = top.next()
            if (next in onPath) return path.subList(path.indexOf(next), path.size) + next
            if (next !in exhausted) push(next)
        }
        return null
    }
}

/**
 * A [SimRegistry] whose declared order cannot be realised.
 *
 * Thrown from world construction — a cycle, a constraint naming a system nobody registered, or
 * a constraint that contradicts the [SimPhase] ordinals — and never from a tick. An
 * unorderable system list fails the run rather than simulating in an arbitrary order and
 * desyncing against a peer that resolved it differently.
 */
public class SystemOrderException internal constructor(message: String) : IllegalStateException(message)
