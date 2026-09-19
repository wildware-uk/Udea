package dev.wildware.udea.core.spatial

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.Ticks
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `(now - start) * speed` is computed exactly, so it cannot differ between two machines.
 *
 * Reproducible is not enough of a claim for a number a server, its clients and a replay all
 * compute separately: "the JVM gets the same answer twice" says nothing about a wasm client. What
 * does is that the answer is the mathematically exact floor of the product, because an exact
 * answer has only one value to agree on. So each case is checked against the same floor done in
 * integer arithmetic, which has no rounding at all: a `Float` speed is `significand * 2^exponent`
 * exactly, and the product with the elapsed count is a `Long` multiply and a shift.
 *
 * This is common code, so it runs on every target the build runs tests for, and each of them
 * must reach the integer answer independently.
 */
class ClipTimeExactnessTest {

    /** A clip so long that [Loop.Once] never holds, so [Animator.clipTime] is the bare product. */
    private val endless = AnimationClip(index = 0, name = "Endless", length = Ticks(Long.MAX_VALUE))

    private val start = Tick(123_456)

    @Test
    fun `clip time is the exact floor of elapsed times speed`() {
        val speeds = sampleSpeeds()
        val elapsedCounts = sampleElapsed()
        var checked = 0
        for (speed in speeds) {
            val animator = Animator()
            animator.play(endless, start, loop = Loop.Once, speed = speed)
            for (elapsed in elapsedCounts) {
                val expected = exactFloorProduct(elapsed, speed)
                assertEquals(
                    Ticks(expected),
                    animator.clipTime(start + elapsed),
                    "elapsed $elapsed x speed $speed (bits 0x${speed.toRawBits().toString(16)})",
                )
                checked++
            }
        }
        assertEquals(speeds.size * elapsedCounts.size, checked)
    }

    /** `floor(elapsed * speed)` for a finite, non-negative [speed], with no floating point at all. */
    private fun exactFloorProduct(elapsed: Long, speed: Float): Long {
        val bits = speed.toRawBits()
        val biased = (bits ushr SIGNIFICAND_BITS) and EXPONENT_MASK
        val fraction = (bits and FRACTION_MASK).toLong()
        if (biased == 0 && fraction == 0L) return 0L
        // A normal float is 1.fraction x 2^(biased - 127); a subnormal is 0.fraction x 2^-126.
        val significand = if (biased == 0) fraction else fraction or (1L shl SIGNIFICAND_BITS)
        val exponent = (if (biased == 0) 1 else biased) - EXPONENT_BIAS - SIGNIFICAND_BITS
        val product = elapsed * significand
        return when {
            exponent >= 0 -> product shl exponent
            exponent <= -Long.SIZE_BITS + 1 -> 0L
            else -> product shr -exponent
        }
    }

    /**
     * Speeds that are not tidy binary fractions - `1.1f`, `0.3f` and a spread from a fixed linear
     * congruential sequence - beside the ones that are, and the edges: zero, the smallest
     * subnormal, and the largest speed below 4.
     */
    private fun sampleSpeeds(): List<Float> {
        val fixed = listOf(0f, Float.MIN_VALUE, 0.3f, 0.5f, 1f, 1.1f, 1.5f, 2f, 2.37f, Float.fromBits(0x407FFFFF))
        var state = SEED
        val spread = List(SPREAD_SPEEDS) {
            state = state * LCG_MULTIPLIER + LCG_INCREMENT
            // 24 bits of the state, as a fraction of 4: every significand bit is exercised.
            ((state ushr 40) and 0xFFFFFFL).toFloat() / (1 shl 24).toFloat() * 4f
        }
        return fixed + spread
    }

    /** Every count up to a few seconds, then the far end of the exact range, up to 2^29 - 1. */
    private fun sampleElapsed(): List<Long> {
        var state = SEED xor 0x5DEECE66DL
        val far = List(FAR_COUNTS) {
            state = state * LCG_MULTIPLIER + LCG_INCREMENT
            (state ushr 35) and EXACT_ELAPSED_MASK
        }
        return (1L..NEAR_COUNTS) + far + EXACT_ELAPSED_MASK
    }

    private companion object {
        const val SIGNIFICAND_BITS = 23
        const val EXPONENT_MASK = 0xFF
        const val FRACTION_MASK = 0x7FFFFF
        const val EXPONENT_BIAS = 127

        /** 2^29 - 1: the largest elapsed count `Animator`'s KDoc promises an exact product for. */
        const val EXACT_ELAPSED_MASK = (1L shl 29) - 1

        const val NEAR_COUNTS = 400L
        const val FAR_COUNTS = 200
        const val SPREAD_SPEEDS = 60
        const val SEED = 0x2410_2410L
        const val LCG_MULTIPLIER = 6364136223846793005L
        const val LCG_INCREMENT = 1442695040888963407L
    }
}
