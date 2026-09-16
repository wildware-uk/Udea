package dev.wildware.udea.gas

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [roundHalfUp]'s ties and edges on every target, as literals.
 *
 * `RoundHalfUpTest` holds the function to `java.lang.Math.round` over millions of floats, but only
 * on the JVM, where that oracle exists. A client on Wasm or Android predicts the same cooldowns
 * the JVM server computes (spec D3), so the cases where rounding rules and `Double.toInt()`
 * conversions differ between implementations are pinned here, with the values `Math.round` gives.
 */
class RoundHalfUpEdgesTest {

    @Test
    fun `ties round towards positive infinity in both signs`() {
        assertEquals(1, roundHalfUp(0.5f))
        assertEquals(3, roundHalfUp(2.5f))
        assertEquals(0, roundHalfUp(-0.5f))
        assertEquals(-2, roundHalfUp(-2.5f))
    }

    @Test
    fun `the float just below a half rounds down`() {
        assertEquals(0, roundHalfUp(0.49999997f))
        assertEquals(-1, roundHalfUp(-0.50000006f))
    }

    @Test
    fun `out-of-range values saturate and NaN is zero`() {
        assertEquals(Int.MAX_VALUE, roundHalfUp(Float.POSITIVE_INFINITY))
        assertEquals(Int.MIN_VALUE, roundHalfUp(Float.NEGATIVE_INFINITY))
        assertEquals(Int.MAX_VALUE, roundHalfUp(1e10f))
        assertEquals(Int.MIN_VALUE, roundHalfUp(-1e10f))
        assertEquals(0, roundHalfUp(Float.NaN))
    }
}
