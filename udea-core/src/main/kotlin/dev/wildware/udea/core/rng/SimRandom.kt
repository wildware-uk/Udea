package dev.wildware.udea.core.rng

/**
 * xoshiro256** with explicit, capturable state — the only generator simulation may use.
 *
 * ## Why not `kotlin.random.Random`
 *
 * The snapshot-equivalence gate (spec 7) says: capture at tick T, restore at tick T, re-run,
 * get identical output. That is only meaningful if *every* source of simulation randomness is
 * capturable. `Random.Default` is process-global and unseeded; a single call to it anywhere
 * in a system makes restore-and-rerun diverge for a reason no later test can localise. So the
 * state here is four longs, and [save]/[load] move them straight into and out of a snapshot
 * array.
 *
 * ## Why xoshiro256**
 *
 * Blackman & Vigna's generator: 256 bits of state, passes BigCrush, and — the part that
 * matters — its state is four plain longs with no hidden fields, no `AtomicLong`, and no
 * table. Capturing it is `save`, not serialization.
 *
 * Every method is allocation-free and non-synchronised. A [SimRandom] belongs to one stream
 * on one simulation thread; sharing one across threads breaks determinism long before it
 * breaks correctness.
 */
public class SimRandom {

    private var s0: Long = 0L
    private var s1: Long = 0L
    private var s2: Long = 0L
    private var s3: Long = 0L

    /**
     * Seeds the four-long state from one long via SplitMix64, as the reference
     * implementation prescribes: a raw seed left-aligned into the state would start the
     * generator in a low-entropy corner and take thousands of draws to escape it.
     */
    public constructor(seed: Long) {
        seed(seed)
    }

    /** Restores state directly. Used by snapshot restore and by the golden-vector test. */
    public constructor(state: LongArray, offset: Int = 0) {
        load(state, offset)
    }

    /** Re-seeds in place, discarding the current state. */
    public fun seed(seed: Long) {
        var mixer = seed
        mixer += GOLDEN_GAMMA
        s0 = splitMix64(mixer)
        mixer += GOLDEN_GAMMA
        s1 = splitMix64(mixer)
        mixer += GOLDEN_GAMMA
        s2 = splitMix64(mixer)
        mixer += GOLDEN_GAMMA
        s3 = splitMix64(mixer)
        // splitMix64 emits zero exactly once per 2^64 inputs; four in a row is not a thing
        // that happens, but the absorbing state is fatal enough to be worth one branch.
        if (isDead()) s0 = GOLDEN_GAMMA
    }

    /** The next 64 uniformly distributed bits. Every other draw is derived from this one. */
    public fun nextLong(): Long {
        val result = rotl(s1 * 5, 7) * 9

        val t = s1 shl 17
        s2 = s2 xor s0
        s3 = s3 xor s1
        s1 = s1 xor s2
        s0 = s0 xor s3
        s2 = s2 xor t
        s3 = rotl(s3, 45)

        return result
    }

    /**
     * Uniform in `[0, 1)`.
     *
     * Built from the top 24 bits, which is exactly the precision a `Float` has: taking the
     * low bits instead would hand out values the mantissa cannot distinguish and quietly bias
     * the low end.
     */
    public fun nextFloat(): Float = (nextLong() ushr 40).toFloat() * FLOAT_UNIT

    /**
     * Uniform in `0 until bound`.
     *
     * Rejection-sampled rather than `% bound`, which is biased toward small values whenever
     * `bound` does not divide 2^31 — a bias that shows up as loot tables that are subtly
     * wrong and crit rolls that are subtly generous.
     *
     * @throws IllegalArgumentException if [bound] is not positive.
     */
    public fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive, was $bound" }

        if (bound and -bound == bound) {
            // Power of two: the top bits are already uniform, so no rejection is needed.
            return ((bound.toLong() * (nextLong() ushr 33)) ushr 31).toInt()
        }

        while (true) {
            val bits = (nextLong() ushr 33).toInt()
            val value = bits % bound
            if (bits - value + (bound - 1) >= 0) return value
        }
    }

    /** Uniform in `origin until bound`. */
    public fun nextInt(origin: Int, bound: Int): Int {
        require(bound > origin) { "bound $bound must exceed origin $origin" }
        return origin + nextInt(bound - origin)
    }

    /** A fair coin, taken from the sign bit. */
    public fun nextBoolean(): Boolean = nextLong() < 0L

    /**
     * Writes the four state words into [into] at [offset].
     *
     * @throws IllegalArgumentException if [into] cannot hold [STATE_WORDS] words there.
     */
    public fun save(into: LongArray, offset: Int = 0) {
        requireRoom(into.size, offset, "save")
        into[offset] = s0
        into[offset + 1] = s1
        into[offset + 2] = s2
        into[offset + 3] = s3
    }

    /**
     * Reads the four state words from [from] at [offset].
     *
     * @throws IllegalArgumentException if [from] is too short, or if the four words are all
     *   zero — xoshiro's one absorbing state, from which it emits nothing but zeroes forever.
     *   A snapshot carrying it is corrupt, and failing here beats a silently dead generator.
     */
    public fun load(from: LongArray, offset: Int = 0) {
        requireRoom(from.size, offset, "load")
        s0 = from[offset]
        s1 = from[offset + 1]
        s2 = from[offset + 2]
        s3 = from[offset + 3]
        require(!isDead()) {
            "xoshiro256** state at offset $offset is all zero, which is its absorbing state"
        }
    }

    private fun isDead(): Boolean = s0 == 0L && s1 == 0L && s2 == 0L && s3 == 0L

    private fun requireRoom(size: Int, offset: Int, what: String) {
        require(offset >= 0 && size - offset >= STATE_WORDS) {
            "cannot $what $STATE_WORDS state words at offset $offset of a LongArray($size)"
        }
    }

    override fun toString(): String = "SimRandom(xoshiro256**)"

    public companion object {

        /** Longs of state per generator. The snapshot layout depends on this. */
        public const val STATE_WORDS: Int = 4

        /** 2^-24: one unit in the last place of a `Float` mantissa. */
        private const val FLOAT_UNIT: Float = 1.0f / (1 shl 24).toFloat()

        /** The odd 64-bit approximation of the golden ratio SplitMix64 advances by. */
        internal const val GOLDEN_GAMMA: Long = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15

        internal fun rotl(x: Long, k: Int): Long = (x shl k) or (x ushr (64 - k))

        /**
         * SplitMix64's finalising mix. Used for seeding only, never to produce draws: it is
         * a fine mixer and a poor generator.
         */
        internal fun splitMix64(z: Long): Long {
            var x = z
            x = (x xor (x ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
            x = (x xor (x ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
            return x xor (x ushr 31)
        }
    }
}
