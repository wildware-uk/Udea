package dev.wildware.udea.replay

import java.util.zip.CRC32

/**
 * The `.udearep` container: what the bytes are, and the two readers that turn them back.
 *
 * ## Why a format at all, when `InputRing` exists
 *
 * `dev.wildware.udea.net.input.InputRing` is a **fixed-capacity per-connection** buffer a client
 * keeps so a mispredicted tick can be replayed against a server correction. It holds 128
 * commands, it overwrites the oldest without being asked, it knows about exactly one peer, and
 * it dies with the process. Every one of those properties is right for prediction and wrong for
 * a recording, which is append-only, whole-match, ordered across **all** peers by the server's
 * own tick, and has to outlive the process that produced it. The two are not one mechanism with
 * different numbers; sharing one would mean a recording that silently forgot its opening minute.
 *
 * ## Self-describing and length-prefixed, which standards section 1 requires
 *
 * The old tree's `PacketUtil` streamed components in bag order with no type tag, so the two ends
 * agreed only as long as nobody touched declaration order. Everything here carries its own
 * length: the header declares its byte count before any field of it is read, every string is
 * length-prefixed, the frame section declares its tick count, and the file ends with a CRC32
 * over every byte before it. A truncated recording is a named refusal rather than a replay that
 * runs off the end of its input and diverges for a reason nobody can find.
 *
 * ## Layout
 *
 * ```text
 * MAGIC          8   "UDEAREP" + 0x1A
 * version        u16 FORMAT_VERSION
 * headerBytes    i32 length of the header section that follows
 * --- header (see ReplayHeader) ---------------------------------------------------
 * rootSeed       i64   RngService.seed at record time
 * protoHash      i32   the build's wire-protocol hash
 * assetHash      u8 length, then that many bytes: AssetRegistry.contentHash
 * schemaHash     i64   the input schema's hash - see InputSchema
 * tickRateHz     i32
 * firstTick      i64   the tick the first frame belongs to
 * tickCount      i32
 * peerCount      i32
 * gameId         string
 * gameVersion    string
 * axisNames      u16 count, then that many strings
 * actionNames    u16 count, then that many strings
 * --- frames ----------------------------------------------------------------------
 * tickCount * peerCount samples, tick-major, peers ascending. See InputSample.
 * --- hashes ----------------------------------------------------------------------
 * tickCount * i64  WorldHasher.hash(snapshot) at the END of each recorded tick
 * --- trailer ---------------------------------------------------------------------
 * crc32          i32 over every byte from MAGIC to the last hash
 * ```
 *
 * Little-endian throughout, because a format that mixed the two would be read wrong exactly
 * once, in the field.
 */
public object ReplayFormat {

    /** `UDEAREP` plus `0x1A`, which is what stops a text editor pasting one into a diff. */
    public val MAGIC: ByteArray = byteArrayOf(
        'U'.code.toByte(), 'D'.code.toByte(), 'E'.code.toByte(), 'A'.code.toByte(),
        'R'.code.toByte(), 'E'.code.toByte(), 'P'.code.toByte(), 0x1A,
    )

    /**
     * The version every reader checks first.
     *
     * Before any other field, because a header laid out differently would make every *other*
     * mismatch report a garbage value - "seed 7318349312 does not match 0" is a worse message
     * than "this file is format 2 and this build reads 1".
     */
    public const val FORMAT_VERSION: Int = 1

    /** The extension. One place, so a tool and a test cannot disagree about it. */
    public const val EXTENSION: String = ".udearep"

    /**
     * Longest string this format will decode, in UTF-8 bytes.
     *
     * A length prefix read from a corrupt file is an attacker-controlled allocation in every
     * format without one of these; standards section 1 bans the unbounded buffer for that
     * reason. 1 KiB is far past any game name or action name in this tree.
     */
    public const val MAX_STRING_BYTES: Int = 1024

    /** Most named axes or actions one schema may carry. */
    public const val MAX_NAMES: Int = 4096

    /** Most peers one recording may carry. A `.udearep` is one match, not a season. */
    public const val MAX_PEERS: Int = 256

    /**
     * Most ticks one recording may carry: 24 hours of simulation at 60Hz.
     *
     * Named rather than `Int.MAX_VALUE` because the decoder multiplies it by `peerCount` to
     * size the frame table, and a length prefix trusted without a bound is the unbounded-buffer
     * smell with extra steps.
     */
    public const val MAX_TICKS: Int = 60 * 60 * 60 * 24

    /** Bytes of fixed preamble: [MAGIC], the u16 version and the i32 header length. */
    public const val PREAMBLE_BYTES: Int = 8 + 2 + 4

    /** The CRC32 of `bytes[0, end)`, as an unsigned value widened into a `Long`. */
    public fun crc32(bytes: ByteArray, end: Int): Long {
        val crc = CRC32()
        crc.update(bytes, 0, end)
        return crc.value
    }
}

/**
 * A growable little-endian byte sink. The append-only half of a recording.
 *
 * Deliberately not `java.io.DataOutputStream`: that is big-endian, and it cannot patch a value
 * it has already written, which the header's own length prefix needs.
 */
public class ByteSink(initialCapacity: Int = DEFAULT_CAPACITY) {

    private var bytes: ByteArray = ByteArray(initialCapacity)

    /** How many bytes have been written. Also the offset the next write lands at. */
    public var size: Int = 0
        private set

    /** The written bytes, copied. */
    public fun toByteArray(): ByteArray = bytes.copyOf(size)

    /** The backing array, so a CRC over a whole recording does not have to copy it first. */
    internal fun backing(): ByteArray = bytes

    /** One unsigned byte. */
    public fun u8(value: Int) {
        require(value in 0..0xFF) { "u8 out of range: $value" }
        ensure(1)
        bytes[size++] = value.toByte()
    }

    /** Two bytes, least significant first. */
    public fun u16(value: Int) {
        require(value in 0..0xFFFF) { "u16 out of range: $value" }
        ensure(2)
        bytes[size++] = value.toByte()
        bytes[size++] = (value ushr 8).toByte()
    }

    /** Four bytes, least significant first. */
    public fun i32(value: Int) {
        ensure(4)
        var shift = 0
        while (shift < 32) {
            bytes[size++] = (value ushr shift).toByte()
            shift += 8
        }
    }

    /** Eight bytes, least significant first. */
    public fun i64(value: Long) {
        ensure(8)
        var shift = 0
        while (shift < 64) {
            bytes[size++] = (value ushr shift).toByte()
            shift += 8
        }
    }

    /** A float by its raw bits, so `-0.0f` and every `NaN` payload survive the round trip. */
    public fun f32(value: Float): Unit = i32(value.toRawBits())

    /** Raw bytes with no length prefix of their own. The caller writes one if it needs one. */
    public fun raw(value: ByteArray) {
        ensure(value.size)
        value.copyInto(bytes, size)
        size += value.size
    }

    /** A u16-length-prefixed UTF-8 string, refused past [ReplayFormat.MAX_STRING_BYTES]. */
    public fun string(value: String) {
        val encoded = value.encodeToByteArray()
        require(encoded.size <= ReplayFormat.MAX_STRING_BYTES) {
            "'$value' is ${encoded.size} UTF-8 bytes; a .udearep string is capped at " +
                "${ReplayFormat.MAX_STRING_BYTES}"
        }
        u16(encoded.size)
        raw(encoded)
    }

    /** Overwrites the `i32` at [offset]. Used once, for the header's own length. */
    public fun patchI32(offset: Int, value: Int) {
        require(offset >= 0 && offset + 4 <= size) { "cannot patch 4 bytes at $offset of $size" }
        for (index in 0 until 4) bytes[offset + index] = (value ushr (index * 8)).toByte()
    }

    private fun ensure(extra: Int) {
        val needed = size + extra
        if (needed <= bytes.size) return
        var capacity = bytes.size
        while (capacity < needed) capacity += (capacity shr 1) + 1
        bytes = bytes.copyOf(capacity)
    }

    public companion object {

        /** 64 KiB: a couple of thousand ticks of one peer's input before the first regrow. */
        public const val DEFAULT_CAPACITY: Int = 64 * 1024
    }
}

/**
 * The reading half. Every method fails with [ReplayFormatException] rather than an
 * `ArrayIndexOutOfBoundsException`, so a truncated file is a sentence and not a stack trace.
 */
public class ByteSource(private val bytes: ByteArray, private var at: Int = 0) {

    /** The offset the next read starts at. */
    public val position: Int get() = at

    /** Bytes not yet read. */
    public val remaining: Int get() = bytes.size - at

    /** One unsigned byte. */
    public fun u8(): Int = bytes[claim(1)].toInt() and 0xFF

    /** Two bytes, least significant first. */
    public fun u16(): Int {
        val start = claim(2)
        return (bytes[start].toInt() and 0xFF) or ((bytes[start + 1].toInt() and 0xFF) shl 8)
    }

    /** Four bytes, least significant first. */
    public fun i32(): Int {
        val start = claim(4)
        var value = 0
        for (index in 0 until 4) {
            value = value or ((bytes[start + index].toInt() and 0xFF) shl (index * 8))
        }
        return value
    }

    /** Eight bytes, least significant first. */
    public fun i64(): Long {
        val start = claim(8)
        var value = 0L
        for (index in 0 until 8) {
            value = value or ((bytes[start + index].toLong() and 0xFFL) shl (index * 8))
        }
        return value
    }

    /** A float from its raw bits. */
    public fun f32(): Float = Float.fromBits(i32())

    /** [length] raw bytes, copied. */
    public fun raw(length: Int): ByteArray {
        val start = claim(length)
        return bytes.copyOfRange(start, start + length)
    }

    /** A u16-length-prefixed UTF-8 string. */
    public fun string(): String {
        val length = u16()
        if (length > ReplayFormat.MAX_STRING_BYTES) {
            throw ReplayFormatException(
                "a string declares $length bytes, past the ${ReplayFormat.MAX_STRING_BYTES}-byte " +
                    "cap; this file is corrupt or is not a .udearep",
            )
        }
        return raw(length).decodeToString()
    }

    /** Refuses unless exactly [expected] bytes are left. What the trailer check is written with. */
    public fun expectRemaining(expected: Int, what: String) {
        if (remaining != expected) {
            throw ReplayFormatException(
                "$what: expected $expected byte(s) left at offset $at, found $remaining",
            )
        }
    }

    private fun claim(length: Int): Int {
        if (length < 0) throw ReplayFormatException("negative read length $length at offset $at")
        if (at + length > bytes.size) {
            throw ReplayFormatException(
                "the file ends after ${bytes.size} bytes but $length more were needed at " +
                    "offset $at; it is truncated",
            )
        }
        val start = at
        at += length
        return start
    }
}

/**
 * This is not a `.udearep`, or it is one that has been damaged.
 *
 * Separate from [ReplayRefusedException], which is what a *valid* recording gets when it cannot
 * be replayed by **this build**. That distinction is the whole of issue #147's requirement: one
 * of them means "fix the file", the other means "you are on the wrong build", and a single
 * exception type would make an agent guess which.
 */
public class ReplayFormatException(message: String) : IllegalArgumentException(message)
