package dev.wildware.udea.annotations

/**
 * Quantises a floating-point property to [bits] bits over the closed range [min]..[max]
 * before it goes on the wire.
 *
 * Consumed by the **`udea-codegen` KSP2 processor**, which emits the pack/unpack pair
 * into `Replicator<T>`'s network write and read paths. Snapshot capture is unaffected:
 * the snapshot ring stores the full-precision value, so quantisation never degrades a
 * rewind. The **`udea-compiler-plugin` K2 FIR checkers** reject `@Q` on a non-float
 * property, reject `bits` outside `1..32`, and reject `min >= max`.
 *
 * @param bits how many bits the quantised value occupies on the wire.
 * @param min inclusive lower bound of the represented range.
 * @param max inclusive upper bound of the represented range. Values outside the range
 *   are clamped by the generated packer.
 *
 * [min] and [max] deliberately have **no defaults**. A defaulted range is a magic number
 * that no checker can catch: `min >= max` is the only range error FIR can see, so a
 * defaulted `0f..1f` on a rotation, a health pool or a world coordinate would pass every
 * check and clamp the value on every packet, silently and forever. Spelling the range is
 * the one thing the author knows and the generator cannot infer (issue-19 declares this
 * annotation as `@Q(bits: Int, min: Float, max: Float)`).
 *
 * Retention is [AnnotationRetention.BINARY]: the quantisation is compiled into the
 * generated codec, so no runtime reader ever needs the annotation itself.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Q(
    val bits: Int,
    val min: Float,
    val max: Float,
)
