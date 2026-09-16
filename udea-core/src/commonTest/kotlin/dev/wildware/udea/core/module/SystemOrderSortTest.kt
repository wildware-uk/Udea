package dev.wildware.udea.core.module

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [SystemOrder.sort]'s rule, checked directly and on every target: among nodes whose constraints
 * are all satisfied, the lowest registration index runs first.
 *
 * The ready set was `java.util.PriorityQueue` until issue #203 and is now a heap of the module's
 * own. `SystemOrderTest` checks the rule through a built world with two or three systems, which a
 * heap that only sifts correctly near its root passes. So this drives the sort over graphs large
 * enough to exercise every level of the heap and compares it with the rule written the slow,
 * obviously-correct way: scan for the lowest ready index, every step.
 */
class SystemOrderSortTest {

    @Test
    fun `unconstrained nodes run in registration order`() {
        assertEquals((0 until 40).toList(), SystemOrder.sort(40, emptyList()) { "n$it" })
    }

    @Test
    fun `a constraint delays only what it constrains - and an unconstrained node may overtake it`() {
        // 0 must follow 3, so 1, 2 and 3 run first; 4 is free and waits only for the index order.
        val order = SystemOrder.sort(5, listOf(OrderEdge(from = 3, to = 0))) { "n$it" }

        assertEquals(listOf(1, 2, 3, 0, 4), order)
    }

    @Test
    fun `random acyclic graphs sort exactly as the lowest-ready-index rule says`() {
        val random = Random(SEED)
        repeat(GRAPHS) {
            val nodeCount = random.nextInt(1, MAX_NODES)
            val edges = randomAcyclicEdges(random, nodeCount)

            assertEquals(
                lowestReadyIndexFirst(nodeCount, edges),
                SystemOrder.sort(nodeCount, edges) { "n$it" },
                "$nodeCount nodes, edges $edges",
            )
        }
    }

    /** Edges that only ever point from a node to one later in a shuffled order, so no cycle. */
    private fun randomAcyclicEdges(random: Random, nodeCount: Int): List<OrderEdge> {
        val rank = (0 until nodeCount).shuffled(random)
        val edges = ArrayList<OrderEdge>()
        repeat(random.nextInt(0, nodeCount * 2 + 1)) {
            val a = random.nextInt(nodeCount)
            val b = random.nextInt(nodeCount)
            if (a != b) edges += if (rank[a] < rank[b]) OrderEdge(a, b) else OrderEdge(b, a)
        }
        return edges
    }

    /** The rule, in quadratic time: at each step, the lowest index whose predecessors have all run. */
    private fun lowestReadyIndexFirst(nodeCount: Int, edges: List<OrderEdge>): List<Int> {
        val done = BooleanArray(nodeCount)
        val order = ArrayList<Int>(nodeCount)
        repeat(nodeCount) {
            val next = (0 until nodeCount).first { node ->
                !done[node] && edges.none { it.to == node && !done[it.from] }
            }
            done[next] = true
            order += next
        }
        return order
    }

    private companion object {
        const val SEED = 203L
        const val GRAPHS = 300
        const val MAX_NODES = 64
    }
}
