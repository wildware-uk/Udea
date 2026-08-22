package dev.wildware.udea.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The sort itself, driven directly.
 *
 * [RenderRegistry] tests prove the pipeline comes out in the right order; these prove the
 * two properties the order *rests* on, which are much easier to assert on indices than on
 * renderers: the tie-break is registration index and nothing else, and a cycle produces the
 * cycle rather than "constraints unsatisfiable".
 */
class RenderOrderTest {

    @Test
    fun `with no constraints the order is registration order`() {
        assertEquals(listOf(0, 1, 2, 3), RenderOrder.sort(4, emptyList(), ::name))
    }

    @Test
    fun `an edge overrides registration order`() {
        // 3 must precede 0; everything else keeps its registration position.
        val order = RenderOrder.sort(4, listOf(OrderEdge(3, 0)), ::name)

        assertEquals(listOf(1, 2, 3, 0), order)
    }

    @Test
    fun `unconstrained nodes keep registration order around a constrained pair`() {
        // The tie-break is the whole reason frame order is reproducible: with a HashSet as
        // the ready set this assertion would pass or fail depending on hash iteration order.
        val order = RenderOrder.sort(5, listOf(OrderEdge(4, 1)), ::name)

        assertEquals(listOf(0, 2, 3, 4, 1), order)
    }

    @Test
    fun `a chain is fully ordered`() {
        val order = RenderOrder.sort(3, listOf(OrderEdge(2, 1), OrderEdge(1, 0)), ::name)

        assertEquals(listOf(2, 1, 0), order)
    }

    @Test
    fun `a duplicated edge is not a cycle`() {
        val order = RenderOrder.sort(2, listOf(OrderEdge(1, 0), OrderEdge(1, 0)), ::name)

        assertEquals(listOf(1, 0), order)
    }

    @Test
    fun `a two node cycle names both nodes and closes the loop`() {
        val failure = assertFailsWith<RenderOrderException> {
            RenderOrder.sort(2, listOf(OrderEdge(0, 1), OrderEdge(1, 0)), ::name)
        }

        val message = failure.message.orEmpty()
        assertTrue("node0 -> node1 -> node0" in message, message)
    }

    @Test
    fun `a longer cycle is printed in full`() {
        val failure = assertFailsWith<RenderOrderException> {
            RenderOrder.sort(
                4,
                listOf(OrderEdge(1, 2), OrderEdge(2, 3), OrderEdge(3, 1)),
                ::name,
            )
        }

        val message = failure.message.orEmpty()
        assertTrue("node1 -> node2 -> node3 -> node1" in message, message)
    }

    @Test
    fun `a self edge is reported as a cycle rather than silently satisfied`() {
        val failure = assertFailsWith<RenderOrderException> {
            RenderOrder.sort(1, listOf(OrderEdge(0, 0)), ::name)
        }

        assertTrue("node0 -> node0" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `nodes outside the graph are rejected rather than silently dropped`() {
        assertFailsWith<IllegalArgumentException> {
            RenderOrder.sort(2, listOf(OrderEdge(0, 5)), ::name)
        }
    }

    private fun name(index: Int): String = "node$index"
}
