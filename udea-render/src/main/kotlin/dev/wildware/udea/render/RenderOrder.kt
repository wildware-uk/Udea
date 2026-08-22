package dev.wildware.udea.render

import java.util.PriorityQueue

/** A `from must run before to` edge between two nodes, by node index. */
internal data class OrderEdge(val from: Int, val to: Int)

/**
 * The deterministic topological sort behind [RenderRegistry].
 *
 * Pulled out of the registry as a pure function over indices for two reasons. It is the part
 * with the interesting failure mode -- a cycle -- and a pure function over indices can be
 * driven straight into that failure by a test, whereas a sort tangled up with instantiating
 * systems cannot. And `SimRegistry` (kernel epic, not yet landed) needs exactly this
 * algorithm; when it arrives, this is the file that moves to `udea-core` and gets shared,
 * rather than a second implementation that drifts.
 *
 * ## Determinism
 *
 * Kahn's algorithm, with the ready set kept in a min-heap on **registration index**. That is
 * the tie-break the design promises: two systems with no constraint between them run in the
 * order they were registered, on every machine and every run. A `HashSet` here would make
 * frame order depend on hash iteration order, which is the class of bug that shows up as a
 * screenshot diff nobody can reproduce.
 *
 * The exact rule, since two reasonable ones exist: **a node runs as soon as its own
 * constraints allow**, and among nodes whose constraints are all satisfied the lowest index
 * wins. So a constraint delays the node it constrains and everything downstream of it, and
 * an unconstrained node can overtake a delayed one. The alternative -- "perturb registration
 * order as little as possible" -- reads more naturally in a three-system example and is
 * ambiguous in a ten-system one, which is the wrong trade for something a frame's appearance
 * depends on.
 */
internal object RenderOrder {

    /**
     * Node indices `0 until nodeCount` in execution order.
     *
     * @param edges `from` runs before `to`. Duplicates are harmless; a self-edge is a cycle.
     * @param describe names a node for the failure message.
     * @throws RenderOrderException if the constraints contain a cycle. The message contains
     *   the cycle itself -- `A -> B -> C -> A` -- because "there is a cycle somewhere in your
     *   twelve renderers" is not an actionable failure.
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
            val unresolved = (0 until nodeCount).filter { inDegree[it] > 0 }
            throw RenderOrderException(
                "render order constraints contain a cycle: " +
                    describeCycle(findCycle(unresolved.toSet(), successors), describe),
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
        error("render order left ${candidates.size} node(s) unresolved but no cycle was found")
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

    private fun describeCycle(cycle: List<Int>, describe: (Int) -> String): String =
        cycle.joinToString(" -> ") { describe(it) }
}

/**
 * A [RenderRegistry] whose `before`/`after` constraints cannot be satisfied.
 *
 * Thrown from [RenderRegistry.build], which is called while the world is being built and
 * long before a frame is drawn: an unorderable pipeline fails the run, it does not draw a
 * plausible-looking frame in an arbitrary order.
 */
public class RenderOrderException internal constructor(message: String) : IllegalStateException(message)
