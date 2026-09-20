package dev.wildware.udea.replay

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
 * --- edits (format 2 and later) ---------------------------------------------------
 * editCount      i32   calls made between ticks, in the order they were applied
 * each edit      tick i64, author string, tool string, argCount u16, then argCount
 *                pairs of name string and value text, names ascending. See ReplayEdit.
 * --- trailer ---------------------------------------------------------------------
 * crc32          i32 over every byte from MAGIC to the last hash, or the last edit
 * ```
 *
 * A `string` is a u16 length and at most [MAX_STRING_BYTES] of UTF-8; a `text` is an i32 length
 * and at most [MAX_EDIT_TEXT_BYTES], for an argument value that can be long - an `update_edit`
 * writing every entity in a big selection.
 *
 * ## Three versions, and which one a file is
 *
 * Format 2 is format 1 with the edits section (issue #232). Format 3 is format 2 with a pointer in
 * a sample (issue #262) - **inside** the frame section, behind `InputSample`'s presence mask, so the
 * layout above is unchanged and only what a sample may contain has grown.
 *
 * A recording is written as **the lowest version that can express it**, which is what keeps older
 * builds able to read ordinary files:
 *
 * - no edits and no pointer: format 1, byte for byte what every earlier build wrote;
 * - edits but no pointer: format 2, which a build from before #232 refuses by its version number
 *   rather than misreading - correctly, because replaying it without its edits would diverge on the
 *   first edited tick;
 * - a pointer on any tick: format 3, refused the same way by a build from before #262, and for the
 *   same reason - an RTS replayed without its pointer is a match in which nobody gave an order.
 *
 * This build reads all three. Note what that rule is *not*: it is not "the newest version this build
 * knows", which would have made every recording ever made unreadable by yesterday's build for the
 * sake of a section it does not contain.
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
    public const val FORMAT_VERSION: Int = 3

    /**
     * The version a recording with edits but no pointer is written as.
     *
     * See "Three versions" in the class KDoc.
     */
    public const val EDITS_FORMAT_VERSION: Int = 2

    /**
     * The version a recording with no edits and no pointer is written as, and the oldest this build
     * reads.
     *
     * See "Three versions" in the class KDoc: a later format without its later sections is this one.
     */
    public const val EDITLESS_FORMAT_VERSION: Int = 1

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

    /** Most edits one recording may carry: more than an hour of a drag updating every frame at 60Hz. */
    public const val MAX_EDITS: Int = 1 shl 18

    /** Most arguments one recorded edit may carry. */
    public const val MAX_EDIT_ARGS: Int = 64

    /** Longest argument value a recorded edit may carry, in UTF-8 bytes. */
    public const val MAX_EDIT_TEXT_BYTES: Int = 64 * 1024

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

    /**
     * The CRC32 of `bytes[0, end)`, as an unsigned value widened into a `Long`.
     *
     * CRC-32 as zip computes it - reflected polynomial `0xEDB88320`, all-ones start and final
     * complement - because that is `java.util.zip.CRC32`, and every `.udearep` recorded before
     * issue #206 carries its value in the trailer. Written out here rather than taken from
     * `java.util.zip` so that Wasm and Android write the same trailer the JVM does; the format has
     * no compression to replace, only this checksum. `Crc32ParityTest` holds it to the JDK's.
     */
    public fun crc32(bytes: ByteArray, end: Int): Long {
        var crc = -1
        for (index in 0 until end) {
            crc = CRC_TABLE[(crc xor bytes[index].toInt()) and 0xFF] xor (crc ushr 8)
        }
        return crc.inv().toLong() and 0xFFFF_FFFFL
    }

    /** The byte-at-a-time table for [crc32]: entry `n` is the remainder of `n` shifted through eight bits. */
    private val CRC_TABLE: IntArray = IntArray(256) { entry ->
        var remainder = entry
        repeat(8) {
            remainder = if (remainder and 1 != 0) (remainder ushr 1) xor CRC_POLYNOMIAL else remainder ushr 1
        }
        remainder
    }

    /** CRC-32's generator polynomial, bit-reversed, as the table-driven form uses it. */
    private const val CRC_POLYNOMIAL: Int = 0xEDB88320.toInt()
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

    /** An i32-length-prefixed UTF-8 text, refused past [ReplayFormat.MAX_EDIT_TEXT_BYTES]. */
    public fun text(value: String) {
        val encoded = value.encodeToByteArray()
        require(encoded.size <= ReplayFormat.MAX_EDIT_TEXT_BYTES) {
            "a ${encoded.size}-byte value is past the ${ReplayFormat.MAX_EDIT_TEXT_BYTES} bytes a " +
                ".udearep edit argument may carry"
        }
        i32(encoded.size)
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

    /** An i32-length-prefixed UTF-8 text. */
    public fun text(): String {
        val length = i32()
        if (length < 0 || length > ReplayFormat.MAX_EDIT_TEXT_BYTES) {
            throw ReplayFormatException(
                "a text declares $length bytes, outside 0..${ReplayFormat.MAX_EDIT_TEXT_BYTES}; " +
                    "this file is corrupt or is not a .udearep",
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
