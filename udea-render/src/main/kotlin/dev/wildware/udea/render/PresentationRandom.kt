package dev.wildware.udea.render

import kotlin.random.Random

/**
 * Randomness for things that are drawn and never simulated: screen shake, particle jitter,
 * which of four idle flourishes a portrait plays.
 *
 * ## Why it is a separate type in a separate module
 *
 * Spec 5 gives simulation `RngService` with named streams, xoshiro256** state captured in
 * every snapshot, and a build gate that fails if simulation code reads an unseeded generator.
 * All of that exists so a replay reproduces bit-for-bit. One `Math.random()` in a system
 * defeats it, and nothing fails until a replay diverges weeks later for a reason no test can
 * localise.
 *
 * So presentation randomness is not a *policy*, it is a type that simulation cannot name:
 *
 * - it is declared in `udea-render`, which is **downstream** of `udea-core`, so a `SimSystem`
 *   cannot import it -- the name does not resolve there, and `udea-core`'s own
 *   `PresentationRandomIsolationTest` fails if that stops being true;
 * - it does not implement `RngService`, so it cannot be passed where seeded randomness is
 *   expected;
 * - it is not on `GameContext`, so the one injectable does not lead to it either.
 *
 * ## Wall-seeded on purpose
 *
 * The default seed is `System.nanoTime()`. Two runs of the same replay produce different
 * screen shake, and that is correct: presentation output is not part of the thing being
 * reproduced. [seeded] exists for tests and for reproducible screenshots, which is the one
 * case where a frame does have to come out the same twice.
 */
public class PresentationRandom private constructor(private val random: Random) {

    /** A generator seeded from the wall clock. Two instances diverge, deliberately. */
    public constructor() : this(Random(System.nanoTime()))

    /** Uniform in `[0, 1)`. */
    public fun nextFloat(): Float = random.nextFloat()

    /**
     * Uniform in `0 until bound`.
     *
     * @throws IllegalArgumentException if [bound] is not positive.
     */
    public fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive, was $bound" }
        return random.nextInt(bound)
    }

    /** Uniform in `from until until`. */
    public fun nextInt(from: Int, until: Int): Int {
        require(until > from) { "until $until must exceed from $from" }
        return random.nextInt(from, until)
    }

    /** Uniform in `[from, until)`. Jitter offsets, angles, lifetimes. */
    public fun nextFloat(from: Float, until: Float): Float {
        require(until > from) { "until $until must exceed from $from" }
        return from + random.nextFloat() * (until - from)
    }

    /** A fair coin. */
    public fun nextBoolean(): Boolean = random.nextBoolean()

    override fun toString(): String = "PresentationRandom(wall-seeded, presentation only)"

    public companion object {

        /**
         * A generator with an explicit seed, for tests and reproducible screenshots.
         *
         * Deliberately *not* the primary constructor: reaching for a fixed seed should be a
         * decision with a reason, and the reason is never "so the simulation matches", which
         * this generator can never do.
         */
        public fun seeded(seed: Long): PresentationRandom = PresentationRandom(Random(seed))
    }
}
