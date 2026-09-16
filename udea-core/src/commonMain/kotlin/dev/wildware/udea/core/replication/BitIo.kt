package dev.wildware.udea.core.replication

/**
 * The minimum a [Replicator] needs to serialise a field.
 *
 * Declared here and **implemented in `udea-net`**, along with framing, buffer management,
 * `@Q` quantisation and the packet header. `udea-core` declares it so the frozen
 * `Replicator` signature compiles without core depending on the network module; `udea-net`
 * must implement this interface rather than declare its own, or the engine ends up with two
 * incompatible bit writers.
 */
public interface BitWriter {
    /** Bits written so far. The contract test uses this to prove an empty mask costs zero. */
    public val bitPosition: Long

    /** Writes the low [bitCount] bits of [value]. `bitCount` is in `1..32`. */
    public fun writeBits(value: Int, bitCount: Int)

    public fun writeBoolean(value: Boolean)

    public fun writeInt(value: Int)

    public fun writeLong(value: Long)

    public fun writeFloat(value: Float)
}

/**
 * The read side of [BitWriter]. Same ownership: declared here, implemented in `udea-net`.
 *
 * A reader is always positioned by the framing layer before a `Replicator.read` call; the
 * replicator reads its own mask and its own fields and nothing else.
 */
public interface BitReader {
    /** Bits consumed so far. */
    public val bitPosition: Long

    /** Reads [bitCount] bits into the low bits of the result. `bitCount` is in `1..32`. */
    public fun readBits(bitCount: Int): Int

    public fun readBoolean(): Boolean

    public fun readInt(): Int

    public fun readLong(): Long

    public fun readFloat(): Float
}
