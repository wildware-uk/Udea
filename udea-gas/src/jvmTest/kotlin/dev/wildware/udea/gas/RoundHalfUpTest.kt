package dev.wildware.udea.gas

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [roundHalfUp] computes what `java.lang.Math.round(Float)` computed, for the floats where the two
 * could differ.
 *
 * The cooldown reduction used `Math.round` until issue #204 made this module multiplatform, and the
 * JVM server is the authoritative simulation (spec D3), so a port that moved a cooldown by one tick
 * on a half would be a desync with no visible cause. JVM-only because `Math.round` is the oracle.
 */
class RoundHalfUpTest {

    @Test
    fun `agrees with Math round on every half and its neighbours`() {
        // Every `n + 0.5` a float can hold exactly, and the float either side of it: the only
        // places half-up, half-even and half-away-from-zero disagree, in both signs.
        var n = -(1 shl 22)
        while (n <= 1 shl 22) {
            val half = n + 0.5f
            assertAgrees(half)
            assertAgrees(Math.nextUp(half))
            assertAgrees(Math.nextDown(half))
            n++
        }
    }

    @Test
    fun `agrees with Math round at the edges of the Float and Int ranges`() {
        val edges = listOf(
            0f, -0f, 0.5f, -0.5f, 0.49999997f, -0.49999997f, Float.MIN_VALUE, -Float.MIN_VALUE,
            Float.MAX_VALUE, -Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN,
            Int.MAX_VALUE.toFloat(), Int.MIN_VALUE.toFloat(), 8_388_608.5f, 16_777_216f, -16_777_217f,
        )
        edges.forEach { assertAgrees(it) }
    }

    @Test
    fun `agrees with Math round on ten million seeded bit patterns`() {
        val random = Random(20_260_916)
        repeat(10_000_000) { assertAgrees(Float.fromBits(random.nextInt())) }
    }

    private fun assertAgrees(value: Float) {
        val expected = Math.round(value)
        val actual = roundHalfUp(value)
        if (actual != expected) {
            assertEquals(expected, actual, "roundHalfUp($value) [bits ${value.toRawBits()}]")
        }
    }
}
