package dev.wildware.udea.net.bits

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln

/**
 * A resolved quantisation: how many bits a float costs on the wire, and the exact error
 * that buys.
 *
 * This is the runtime form of `dev.wildware.udea.annotations.Q`, whose retention is BINARY
 * precisely so that nothing reads it reflectively. `udea-codegen` reads `@Q(bits, min, max)`
 * at compile time and emits a reference to one of these — [declared] for the general case,
 * or one of the presets below when the declaration matches. The generated `Replicator` then
 * calls [writeQ]/[readQ], and the whole path is virtual dispatch on a
 * singleton or an immutable value: no boxing, no allocation, nothing per tick.
 *
 * Every implementation guarantees:
 *
 * - [quantise] returns a value that fits in [bits] bits (as an unsigned field when
 *   `bits == 32`), so `writeBits(quantise(v), bits)` is always in range;
 * - [quantise] is deterministic and total over the values it accepts: the same `v` gives the
 *   same field on both ends of the wire, which is the only reason a desync is detectable;
 * - [dequantise] returns a value inside the kind's represented domain, and
 *   `dequantise(quantise(v))` is within [maxError] of `v` **once `v` has been reduced into
 *   that domain**;
 * - the mapping is stable: it is the wire format, and changing it is a protocol break.
 *
 * The reduction is per-kind and is part of the wire format, so it is stated on each kind
 * rather than assumed here. There are two shapes of it, and they are not interchangeable:
 *
 * - **Bounded** ([Norm8], [Pos], [Fixed]) — the domain is an interval and out-of-range values
 *   **clamp** to the bound they ran past, the infinities included. Round-trip error is
 *   `abs(decoded - v)` after that clamp.
 * - **Periodic** ([Angle16]) — the domain is a circle and out-of-range values **wrap**. There
 *   is no bound to clamp to: 7 radians is not "too large", it is 0.717 radians written
 *   differently. Round-trip error is angular and must be measured with [angleDistance];
 *   `abs(decoded - v)` reports 6.28 for a perfect round trip and is a measurement bug, not a
 *   quantisation one.
 *
 * [Exact] reduces nothing and has zero error. Code that reports quantisation error over an
 * arbitrary `Q` must therefore branch on the kind — with an exhaustive `when`, so that a
 * kind added later fails to compile rather than being silently measured as a bounded float.
 */
public sealed interface Q {

    /** Bits this quantisation occupies on the wire. Always in `1..32`. */
    public val bits: Int

    /**
     * The largest difference between a value in the represented domain and its round-trip,
     * in the value's own units. Half a quantisation step for the bounded kinds, half a bucket
     * for [Angle16] (where the difference is angular), zero for [Exact].
     *
     * This is the quantisation error alone. A `Float` result carries its own representation
     * error on top, which matters only when [bits] approaches 32 over a wide range, or when
     * the caller's angle is large enough that one ulp of it exceeds a bucket.
     */
    public val maxError: Float

    /**
     * Packs [value] into the low [bits] bits.
     *
     * A value outside the represented domain is reduced into it, never rejected, and how it
     * is reduced is the kind's own decision: the bounded kinds clamp, [Angle16] wraps. See
     * the kind, and the two shapes described on [Q] itself.
     *
     * @throws IllegalArgumentException for a value no bit pattern of this kind could mean:
     *   NaN for every kind but [Exact], and the infinities for [Angle16], which has no bound
     *   for them to clamp to. Bounded kinds do accept the infinities, and clamp them.
     */
    public fun quantise(value: Float): Int

    /**
     * The inverse of [quantise]. Bits above [bits] in [raw] are ignored, so a field read out
     * of a wider word needs no masking of its own.
     *
     * The result is always inside the represented domain: within `[min, max]` for a bounded
     * kind, within `[0, 2π)` for [Angle16].
     */
    public fun dequantise(raw: Int): Float

    /**
     * No quantisation: all 32 bits of the IEEE-754 encoding.
     *
     * The escape hatch for a field where any loss is wrong, and the only kind that accepts
     * NaN: no other kind has a bit pattern that could mean it. It is also the only kind that
     * carries an infinity through — the bounded kinds clamp one to the bound it ran past, and
     * [Angle16] rejects it outright.
     */
    public data object Exact : Q {
        override val bits: Int get() = 32
        override val maxError: Float get() = 0f
        override fun quantise(value: Float): Int = value.toRawBits()
        override fun dequantise(raw: Int): Float = Float.fromBits(raw)
    }

    /**
     * 8 bits over `0..1`: health fractions, alphas, cooldown progress, blend weights.
     *
     * A step of 1/255, so the error is under 0.2% — below what a health bar can render.
     */
    public data object Norm8 : Q {
        override val bits: Int get() = NORM8_BITS
        override val maxError: Float = ((1.0 / levelsFor(NORM8_BITS)) / 2.0).toFloat()
        override fun quantise(value: Float): Int = quantiseFixed(value, 0f, 1f, NORM8_BITS)
        override fun dequantise(raw: Int): Float = dequantiseFixed(raw, 0f, 1f, NORM8_BITS)
    }

    /**
     * 16 bits over one full turn, **wrapping**.
     *
     * An angle is not a bounded float and must not be quantised like one. There is no
     * meaningful clamp — a facing of 7 radians is a facing of 0.717 radians, not "too
     * large" — so the range is divided into `2^16` buckets over a period rather than
     * `2^16 - 1` steps between two endpoints, and 0 and 2π are the same bucket rather than
     * two. That also means [dequantise] always returns a value in `[0, 2π)`, so a
     * round-tripped angle equals the input modulo a turn, not bit for bit.
     *
     * The wrap is by `floor`, not by remainder, so a negative angle lands on the facing it
     * names rather than in a negative bucket a mask would have to rescue: -1 radian is the
     * facing 2π - 1 ≈ 5.2832 radians, and both quantise to bucket 55106. (Not to be confused
     * with 5.28 radians, which is a different facing 0.0032 radians away and lands 34 buckets
     * off, at 55072 — the wrap is exact, not approximate.)
     *
     * The error is π/65536 ≈ 0.0027°, which is under a pixel of sprite rotation at any
     * sane sprite size. Non-finite angles are rejected: there is no bucket for them.
     *
     * The wrap is exact to within [maxError] only while the caller's `Float` still resolves
     * an angle that finely — one ulp of 800 radians is already a whole bucket, so an
     * unwrapped rotation accumulator that has run to thousands of radians loses precision in
     * its own representation before it reaches here. That loss is the caller's, not the
     * wire's: [quantise] stays deterministic at every magnitude, so both ends still agree on
     * the bucket and no desync can come of it.
     */
    public data object Angle16 : Q {
        override val bits: Int get() = ANGLE16_BITS
        override val maxError: Float = (TWO_PI / ANGLE16_LEVELS / 2.0).toFloat()
        override fun quantise(value: Float): Int = quantiseAngle16(value)
        override fun dequantise(raw: Int): Float = dequantiseAngle16(raw)
    }

    /**
     * 16 bits per axis over `-1024..1024` world units — the default for a position
     * component on a map that fits in that box.
     *
     * This is the number the whole bit layer exists for: a lane-walking unit's position
     * costs 32 bits per axis per tick as a raw float and 16 here, at a resolution of
     * 3.1 cm, which is finer than the unit's own collision radius. A delta against the
     * previous tick shrinks it further, but that is the framing layer's job, not this one's.
     *
     * A game whose world is not this size declares [Fixed] with its own extent rather than
     * stretching this one — the preset exists to be the same on both ends of the wire.
     */
    public data object Pos : Q {
        /** Inclusive lower bound of the represented world extent, per axis. */
        public const val MIN: Float = -1024f

        /** Inclusive upper bound of the represented world extent, per axis. */
        public const val MAX: Float = 1024f

        override val bits: Int get() = POS_BITS
        override val maxError: Float =
            (((MAX.toDouble() - MIN) / levelsFor(POS_BITS)) / 2.0).toFloat()

        override fun quantise(value: Float): Int = quantiseFixed(value, MIN, MAX, POS_BITS)
        override fun dequantise(raw: Int): Float = dequantiseFixed(raw, MIN, MAX, POS_BITS)
    }

    /**
     * A bounded float declared by the precision it needs rather than by a bit count:
     * `Fixed(0f, 5000f, step = 1f)` is "hit points to the nearest point", which is 13 bits.
     *
     * [bits] is the smallest count whose `2^bits - 1` steps are at least as fine as [step],
     * so [actualStep] is always `<= step`: asking for 1 cm never silently gets 2 cm. Both
     * [min] and [max] are exactly representable, which is what makes clamping honest — a
     * value at either end round-trips to that end, not to one step inside it.
     *
     * @throws IllegalArgumentException if the bounds are not finite and ordered, if [step]
     *   is not positive and finite, or if [step] is so fine it would need more than 32 bits.
     */
    public class Fixed(
        public val min: Float,
        public val max: Float,
        public val step: Float,
    ) : Q {

        override val bits: Int

        /** The step actually used, which is [step] or finer. */
        public val actualStep: Float

        override val maxError: Float

        init {
            require(min.isFinite() && max.isFinite()) {
                "quantisation bounds must be finite, were [$min, $max]"
            }
            require(min < max) { "quantisation requires min < max, were [$min, $max]" }
            require(step > 0f && step.isFinite()) { "step must be positive and finite, was $step" }
            bits = bitsForStep(min, max, step)
            actualStep = ((max.toDouble() - min) / levelsFor(bits)).toFloat()
            maxError = (((max.toDouble() - min) / levelsFor(bits)) / 2.0).toFloat()
        }

        override fun quantise(value: Float): Int = quantiseFixed(value, min, max, bits)

        override fun dequantise(raw: Int): Float = dequantiseFixed(raw, min, max, bits)

        override fun toString(): String = "Q.Fixed(min=$min, max=$max, step=$actualStep, bits=$bits)"
    }

    public companion object {

        /**
         * The quantisation an `@Q(bits = ..., min = ..., max = ...)` declaration resolves to.
         *
         * `udea-codegen` calls this with the annotation's literal arguments, which is why
         * the parameter order matches the annotation rather than [Fixed]'s. The result's
         * [Q.bits] is exactly the [bits] asked for.
         *
         * [min] and [max] have **no defaults**, and must not be given any, for the same reason
         * `@Q` itself has none: a default range is a wrong range that compiles. `[0, 1]` is the
         * one range that looks harmless and is almost never what a field wants — a rotation in
         * `[-π, π]` written as `declared(12)` would clamp every negative angle to 0 and every
         * angle over 1 radian to 1, quantise the survivors 6x finer than asked, and produce a
         * stream that decodes without error into a world where nothing faces left. There is no
         * range this function can guess that is safer than making the caller state one.
         */
        public fun declared(bits: Int, min: Float, max: Float): Fixed {
            require(bits in 1..32) { "bits must be in 1..32, was $bits" }
            require(min.isFinite() && max.isFinite()) {
                "quantisation bounds must be finite, were [$min, $max]"
            }
            require(min < max) { "quantisation requires min < max, were [$min, $max]" }
            val step = ((max.toDouble() - min) / levelsFor(bits)).toFloat()
            val resolved = Fixed(min, max, if (step > 0f) step else Float.MIN_VALUE)
            check(resolved.bits == bits) {
                "declared @Q(bits = $bits, min = $min, max = $max) resolved to ${resolved.bits} bits"
            }
            return resolved
        }
    }
}

internal const val NORM8_BITS: Int = 8
internal const val ANGLE16_BITS: Int = 16
internal const val ANGLE16_LEVELS: Int = 1 shl ANGLE16_BITS
internal const val POS_BITS: Int = 16
internal const val TWO_PI: Double = 2.0 * Math.PI

/** `2^bits - 1`: the number of steps between [Q.Fixed.min] and [Q.Fixed.max] inclusive. */
internal fun levelsFor(bits: Int): Long = (1L shl bits) - 1L

/**
 * The smallest bit count whose steps over `[min, max]` are no coarser than [step].
 *
 * Both slacks absorb the float rounding in [step] itself, and both are load-bearing. A step
 * derived from a bit count must resolve back to *that* bit count: `(max - min) / 3` rounded
 * to a `Float` divides the range 3.0000000086 times, and a naive `ceil` turns that into
 * four levels and a `@Q(bits = 2, ...)` field into a 3-bit one — a silent protocol split between two
 * builds. [SLACK] is relative, so it scales with the ratio; the second one is on the log,
 * for the same reason at a power-of-two boundary.
 *
 * The cost is that a step is honoured to within a relative 1e-6, which is four orders of
 * magnitude finer than a `Float` step can express anyway.
 */
private fun bitsForStep(min: Float, max: Float, step: Float): Int {
    val range = max.toDouble() - min
    val levels = ceil((range / step) * (1.0 - SLACK)).coerceAtLeast(1.0)
    val bits = ceil(ln(levels + 1.0) / LN_2 - SLACK).toInt()
    require(bits <= 32) {
        "step $step over [$min, $max] needs $bits bits; the wire format allows at most 32"
    }
    return if (bits < 1) 1 else bits
}

private val LN_2: Double = ln(2.0)

/** Relative tolerance for a step that came back from a `Float`. See [bitsForStep]. */
private const val SLACK: Double = 1e-6

/**
 * Clamps [value] into `[min, max]` and maps it onto `bits` bits.
 *
 * Returns the level as an unsigned field: at `bits == 32` the result is the two's
 * complement pattern of a value up to `2^32 - 1`, which is what [writeQ]
 * puts on the wire and what [dequantiseFixed] reads back.
 */
internal fun quantiseFixed(value: Float, min: Float, max: Float, bits: Int): Int {
    requireFixedRange(min, max, bits)
    require(!value.isNaN()) {
        "cannot quantise NaN over [$min, $max]; declare Q.Exact if a field must carry NaN"
    }
    val levels = levelsFor(bits)
    val t = ((value.toDouble() - min) / (max.toDouble() - min)).coerceIn(0.0, 1.0)
    return Math.round(t * levels).toInt()
}

/** The inverse of [quantiseFixed]. Always returns a value in `[min, max]`. */
internal fun dequantiseFixed(raw: Int, min: Float, max: Float, bits: Int): Float {
    requireFixedRange(min, max, bits)
    val levels = levelsFor(bits)
    val level = raw.toLong() and levels
    return (min + (max.toDouble() - min) * level / levels).toFloat()
}

/**
 * Wraps [radians] into one turn and maps it onto `2^16` buckets-per-turn.
 *
 * `floor`, not `%`: the remainder of a negative angle is negative, and a negative bucket is
 * only ever right by accident of two's complement.
 *
 * The mask on the way out is **load-bearing, not defensive**. `floor` leaves `wrapped` in
 * `[0, 1)`, but `Math.round` rounds half up, so any `wrapped` above `1 - 0.5/2^16` — the last
 * half-bucket of the turn, which every angle just short of a full turn lands in — rounds to
 * `2^16`, one past the last bucket. Without the mask this function returns a value that does
 * not fit in the 16 bits [Q.Angle16] advertises, breaking `Q`'s own width guarantee and
 * corrupting the field after it in the packet. The mask folds `2^16` back to bucket 0, which
 * is the same angle. Deleting it turns two tests in `QuantisationBoundaryTest` red.
 */
internal fun quantiseAngle16(radians: Float): Int {
    require(radians.isFinite()) {
        "cannot quantise a non-finite angle ($radians); there is no bucket for it"
    }
    val turns = radians.toDouble() / TWO_PI
    val wrapped = turns - floor(turns)
    return Math.round(wrapped * ANGLE16_LEVELS).toInt() and (ANGLE16_LEVELS - 1)
}

/** The inverse of [quantiseAngle16]. Always returns a value in `[0, 2π)`. */
internal fun dequantiseAngle16(raw: Int): Float =
    ((raw and (ANGLE16_LEVELS - 1)) * (TWO_PI / ANGLE16_LEVELS)).toFloat()

/**
 * Shortest signed difference between two angles, in `(-π, π]`.
 *
 * Exposed because it is the only correct way to state an angular error: `|7.0 - 0.717|`
 * is 6.28 and also zero, depending on whether you remember that angles wrap.
 */
public fun angleDifference(a: Float, b: Float): Float {
    val d = a.toDouble() - b
    val wrapped = d - Math.round(d / TWO_PI) * TWO_PI
    return wrapped.toFloat()
}

/** Magnitude of [angleDifference]. */
public fun angleDistance(a: Float, b: Float): Float = abs(angleDifference(a, b))

private fun requireFixedRange(min: Float, max: Float, bits: Int) {
    require(bits in 1..32) { "bits must be in 1..32, was $bits" }
    require(min.isFinite() && max.isFinite()) {
        "quantisation bounds must be finite, were [$min, $max]"
    }
    require(min < max) { "quantisation requires min < max, were [$min, $max]" }
}
