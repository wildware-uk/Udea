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
 * - `dequantise(quantise(v))` is within [maxError] of `v`, for every `v` the quantisation
 *   accepts, with values outside a bounded range clamped to that range first;
 * - the mapping is stable: it is the wire format, and changing it is a protocol break.
 */
public sealed interface Q {

    /** Bits this quantisation occupies on the wire. Always in `1..32`. */
    public val bits: Int

    /**
     * The largest difference between a value in range and its round-trip, in the value's own
     * units. Half a quantisation step for the bounded kinds; zero for [Exact].
     *
     * This is the quantisation error alone. A `Float` result carries its own representation
     * error on top, which matters only when [bits] approaches 32 over a wide range.
     */
    public val maxError: Float

    /** Packs [value] into the low [bits] bits. Out-of-range values are clamped, not wrapped. */
    public fun quantise(value: Float): Int

    /** The inverse of [quantise]. Bits above [bits] in [raw] are ignored. */
    public fun dequantise(raw: Int): Float

    /**
     * No quantisation: all 32 bits of the IEEE-754 encoding.
     *
     * The escape hatch for a field where any loss is wrong, and the only kind that accepts
     * NaN and the infinities — the bounded kinds reject NaN, because there is no bit pattern
     * in a range mapping that could mean it.
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
     * The error is π/65536 ≈ 0.0027°, which is under a pixel of sprite rotation at any
     * sane sprite size. Non-finite angles are rejected: there is no bucket for them.
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
         */
        public fun declared(bits: Int, min: Float = 0f, max: Float = 1f): Fixed {
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
 * four levels and `@Q(bits = 2)` into a 3-bit field — a silent protocol split between two
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

/** Wraps [radians] into one turn and maps it onto 16 buckets-per-turn. */
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
