package dev.wildware.udea.core.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A shape component's preconditions hold for the whole life of the component, not just its
 * construction.
 *
 * A `var` on a component is written by three parties: game code, a scene's `populate`, and
 * `Replicator.apply`, which restores a snapshot by assigning fields in place. A precondition
 * enforced only in `init` is enforced against exactly one of them.
 */
class ShapeComponentTest {

    @Test
    fun `a chain rejects an odd vertex array on assignment, not only in the constructor`() {
        val chain = Chain(floatArrayOf(0f, 0f, 1f, 1f))
        assertEquals(2, chain.pointCount)

        val failure = assertFailsWith<IllegalArgumentException> {
            // The route a snapshot restore takes: Replicator.apply writes component fields in
            // place by assignment, so an `init` check never sees this value. Without a setter
            // check, pointCount computes 3 / 2 = 1, the backend builds a one-point chain and
            // the trailing coordinate vanishes with nothing reported anywhere.
            chain.vertices = floatArrayOf(0f, 0f, 1f)
        }

        assertTrue("even number of floats" in failure.message.orEmpty(), "${failure.message}")
        assertEquals(
            2,
            chain.pointCount,
            "a rejected assignment must leave the component as it was, not half applied",
        )
        assertEquals(listOf(0f, 0f, 1f, 1f), chain.vertices.toList())
    }

    @Test
    fun `a chain still rejects an odd vertex array at construction`() {
        val failure = assertFailsWith<IllegalArgumentException> { Chain(floatArrayOf(0f, 0f, 1f)) }

        assertTrue("got 3" in failure.message.orEmpty(), "${failure.message}")
    }

    @Test
    fun `an even assignment is accepted and moves pointCount`() {
        val chain = Chain()
        assertEquals(0, chain.pointCount)

        chain.vertices = FloatArray(10)

        assertEquals(5, chain.pointCount)
    }
}
