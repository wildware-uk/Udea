package dev.wildware.moba.render

/**
 * `java.util.Random`'s linear congruential generator, written out so it exists in common code.
 *
 * ## Why a copy of the JDK's generator rather than any other
 *
 * The ground tile `BackgroundRenderSystem` draws is *generated*, once, from a fixed seed, and the
 * reference frames issue #212 compares against were produced by the LibGDX build - where that
 * generator was `java.util.Random`. A different stream would be a different, equally valid, patch
 * of grass, and every pixel of the background would differ between the two pictures for a reason
 * that has nothing to do with the port. So the stream is kept, and the parity comparison measures
 * the renderer instead of measuring the choice of generator.
 *
 * The algorithm is specified rather than implementation-defined: `java.util.Random`'s class
 * documentation gives the 48-bit LCG, the multiplier, the addend and `next(bits)`, and states the
 * exact `nextInt(bound)` rejection loop. This is that text as Kotlin, so the two agree by
 * construction on every platform rather than on the JVM by luck.
 *
 * ## What it is not
 *
 * Not simulation randomness. `RngService` and its named streams are what a tick draws from, and
 * `docs/engineering-standards.md` forbids anything else there. This runs on the render thread,
 * once, before the first frame, and a `time.rewind` cannot reach it - which is the property that
 * matters, because a background that differed between two captures of the same paused world would
 * put noise into every `render.compare_artifacts` diff in the game.
 */
internal class JdkRandom(seed: Long) {

    private var state: Long = (seed xor MULTIPLIER) and MASK

    /** The top [bits] bits of the next state, as `java.util.Random.next` defines it. */
    private fun next(bits: Int): Int {
        state = (state * MULTIPLIER + ADDEND) and MASK
        return (state ushr (STATE_BITS - bits)).toInt()
    }

    /**
     * A uniform value in `0 until bound`, by `java.util.Random.nextInt(int)`'s own algorithm.
     *
     * The power-of-two case and the rejection loop are both the JDK's: a plain modulo would be
     * subtly non-uniform, and - more to the point here - would consume a different number of
     * values and so produce a different tile.
     */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive, was $bound" }
        if (bound and -bound == bound) {
            return ((bound.toLong() * next(INT_BITS - 1).toLong()) shr (INT_BITS - 1)).toInt()
        }
        while (true) {
            val bits = next(INT_BITS - 1)
            val value = bits % bound
            // Rejects the tail that would make the low values likelier than the high ones.
            if (bits - value + (bound - 1) >= 0) return value
        }
    }

    private companion object {
        const val MULTIPLIER = 0x5DEECE66DL
        const val ADDEND = 0xBL
        const val STATE_BITS = 48
        const val MASK = (1L shl STATE_BITS) - 1
        const val INT_BITS = 32
    }
}

/** `Math.floorMod` for two `Int`s: the remainder with the sign of the divisor. */
internal fun floorMod(value: Int, modulus: Int): Int = ((value % modulus) + modulus) % modulus
