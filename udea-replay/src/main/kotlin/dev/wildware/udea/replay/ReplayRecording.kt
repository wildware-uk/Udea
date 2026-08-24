package dev.wildware.udea.replay

import dev.wildware.udea.core.Tick
import java.nio.file.Files
import java.nio.file.Path

/**
 * A whole match's input, server-ordered, with the hash the world had at the end of every tick.
 *
 * ## What it is for
 *
 * Two things, and the second is why the hashes are in here at all:
 *
 * 1. **Reproduce the match.** [samplesInto] hands a replay the input every peer sent on a given
 *    tick, so a fresh world driven by it takes the same path the recorded one took.
 * 2. **Prove it did.** [hashAt] is `WorldHasher.hash(snapshot)` at the end of that tick in the
 *    *recording* run. A replay that hashes its own world every tick and compares is a
 *    bit-exactness proof rather than a claim, and the first index where the two streams differ
 *    is the first tick the two runs stopped being the same simulation. `WorldHasher`'s snapshot
 *    overload folds the RNG state and the id allocator as well as the fields, so a run that
 *    reached the same world by drawing a different number of random values is caught on the tick
 *    it drew them and not several seconds later when the difference finally became visible.
 *
 * ## Random access, which a bisect needs
 *
 * Frames are variable-length - an idle tick is one byte per peer - so [frameOffsets] indexes
 * them: `frameOffsets[i]` is where tick `firstTick + i`'s block of peer samples begins. Built
 * once, at decode or at [ReplayRecorder.seal], never by scanning. `replay.seek(tick)` is
 * therefore an array read plus one world rebuild, not a linear walk of the file.
 */
public class ReplayRecording internal constructor(
    /** Who made it, of what, and how much of it there is. */
    public val header: ReplayHeader,
    private val frameBytes: ByteArray,
    private val frameOffsets: IntArray,
    private val hashes: LongArray,
) {

    init {
        require(frameOffsets.size == header.tickCount + 1) {
            "the frame index must have one entry per tick plus a terminator: expected " +
                "${header.tickCount + 1}, got ${frameOffsets.size}"
        }
        require(hashes.size == header.tickCount) {
            "the hash stream must have exactly one entry per tick: expected " +
                "${header.tickCount}, got ${hashes.size}"
        }
    }

    /** The vocabulary the samples are written in. */
    public val schema: InputSchema get() = header.schema

    /** The first recorded tick. */
    public val firstTick: Tick get() = header.firstTick

    /** One past the last recorded tick. */
    public val endTick: Tick get() = header.endTick

    /** How many ticks are recorded. */
    public val tickCount: Int get() = header.tickCount

    /** How many peers every tick carries a sample for. */
    public val peerCount: Int get() = header.peerCount

    /** The whole recorded hash stream, copied. What a verifier compares against. */
    public fun hashStream(): LongArray = hashes.copyOf()

    /**
     * The world hash at the end of [tick], as the recording run measured it.
     *
     * @throws IllegalArgumentException when [tick] is outside the recording. Not a sentinel:
     *   `0L` is a legal FNV hash, and returning it for "no such tick" would make a verifier
     *   report a divergence on a tick that was never recorded.
     */
    public fun hashAt(tick: Tick): Long = hashes[checkedIndex(tick)]

    /**
     * Reads every peer's sample for [tick] into [into], which must be one per peer.
     *
     * The samples are overwritten in place and nothing is allocated, so a replay may call this
     * once per tick for a whole match without producing garbage.
     */
    public fun samplesInto(tick: Tick, into: Array<InputSample>) {
        require(into.size == peerCount) {
            "this recording has $peerCount peer(s) and was handed ${into.size} sample slot(s)"
        }
        val index = checkedIndex(tick)
        val source = ByteSource(frameBytes, frameOffsets[index])
        for (peer in 0 until peerCount) {
            require(into[peer].schema == schema) {
                "sample slot $peer is over ${into[peer].schema}, not this recording's $schema"
            }
            into[peer].readFrom(source)
        }
        val expectedEnd = frameOffsets[index + 1]
        if (source.position != expectedEnd) {
            throw ReplayFormatException(
                "tick $tick's frame block ended at byte ${source.position} but the index says " +
                    "$expectedEnd; the frame table and the frame bytes disagree",
            )
        }
    }

    /** One peer's sample for [tick]. Convenience; [samplesInto] is what a replay loop uses. */
    public fun sampleInto(tick: Tick, peer: PeerId, into: InputSample) {
        require(peer.value < peerCount) {
            "this recording has $peerCount peer(s); there is no $peer in it"
        }
        val index = checkedIndex(tick)
        val source = ByteSource(frameBytes, frameOffsets[index])
        val scratch = InputSample(schema)
        for (current in 0 until peer.value) scratch.readFrom(source)
        into.readFrom(source)
    }

    /** Fresh sample slots, one per peer. A replay allocates these once and reuses them. */
    public fun newSampleSlots(): Array<InputSample> = Array(peerCount) { InputSample(schema) }

    /** The whole file, ready to write. Deterministic: the same recording encodes to the same bytes. */
    public fun encode(): ByteArray {
        val sink = ByteSink(ReplayFormat.PREAMBLE_BYTES + frameBytes.size + hashes.size * 8 + 512)
        sink.raw(ReplayFormat.MAGIC)
        sink.u16(ReplayFormat.FORMAT_VERSION)
        val lengthAt = sink.size
        sink.i32(0)
        val headerStart = sink.size
        writeHeader(sink)
        sink.patchI32(lengthAt, sink.size - headerStart)
        sink.raw(frameBytes)
        for (hash in hashes) sink.i64(hash)
        sink.i32(ReplayFormat.crc32(sink.backing(), sink.size).toInt())
        return sink.toByteArray()
    }

    /** Writes [encode] to [path], creating parent directories. */
    public fun writeTo(path: Path) {
        path.parent?.let(Files::createDirectories)
        Files.write(path, encode())
    }

    override fun toString(): String = "ReplayRecording($header)"

    private fun checkedIndex(tick: Tick): Int {
        val index = header.indexOf(tick)
        require(index >= 0) {
            "$tick is outside this recording, which runs $firstTick until $endTick"
        }
        return index
    }

    private fun writeHeader(sink: ByteSink) {
        val identity = header.identity
        sink.i64(identity.rootSeed)
        sink.i32(identity.protoHash)
        sink.u8(identity.assetGraphHash.size)
        sink.raw(identity.assetGraphHash)
        sink.i64(identity.inputSchemaHash)
        sink.i32(header.tickRateHz)
        sink.i64(header.firstTick.value)
        sink.i32(header.tickCount)
        sink.i32(header.peerCount)
        sink.string(header.gameId)
        sink.string(header.gameVersion)
        sink.u16(schema.axisCount)
        for (name in schema.axes) sink.string(name)
        sink.u16(schema.actionCount)
        for (name in schema.actions) sink.string(name)
    }

    public companion object {

        /** Reads a `.udearep` from [path]. */
        public fun readFrom(path: Path): ReplayRecording = decode(Files.readAllBytes(path))

        /**
         * Parses a whole `.udearep`.
         *
         * Order of checks is the order a wrong file goes wrong in: magic, then version, then the
         * CRC, then the contents. Checking the CRC before parsing means a corrupt length prefix
         * is reported as corruption rather than as a bizarre header, which is the difference
         * between "this file is damaged" and an hour spent wondering why a recording claims
         * eleven million peers.
         *
         * @throws ReplayFormatException if this is not a well-formed `.udearep`.
         */
        public fun decode(bytes: ByteArray): ReplayRecording {
            val source = ByteSource(bytes)
            val magic = source.raw(ReplayFormat.MAGIC.size)
            if (!magic.contentEquals(ReplayFormat.MAGIC)) {
                throw ReplayFormatException(
                    "this is not a .udearep: it opens with " +
                        magic.joinToString("") { "%02x".format(it) } +
                        " and every recording opens with " +
                        ReplayFormat.MAGIC.joinToString("") { "%02x".format(it) },
                )
            }
            val version = source.u16()
            if (version != ReplayFormat.FORMAT_VERSION) {
                throw ReplayFormatException(
                    "this recording is .udearep format $version and this build reads format " +
                        "${ReplayFormat.FORMAT_VERSION}; no field of it can be trusted, so it " +
                        "is refused before any other check",
                )
            }
            verifyCrc(bytes)

            val headerBytes = source.i32()
            if (headerBytes < 0 || headerBytes > source.remaining) {
                throw ReplayFormatException(
                    "the header declares $headerBytes bytes and only ${source.remaining} remain",
                )
            }
            val headerEnd = source.position + headerBytes
            val header = readHeader(source)
            if (source.position != headerEnd) {
                throw ReplayFormatException(
                    "the header declared it ends at byte $headerEnd and decoding it stopped at " +
                        "byte ${source.position}; this build's header layout does not match the " +
                        "file's",
                )
            }

            val frameStart = source.position
            val offsets = IntArray(header.tickCount + 1)
            val scratch = InputSample(header.schema)
            for (index in 0 until header.tickCount) {
                offsets[index] = source.position - frameStart
                repeat(header.peerCount) { scratch.readFrom(source) }
            }
            offsets[header.tickCount] = source.position - frameStart
            val frameBytes = bytes.copyOfRange(frameStart, source.position)

            val hashes = LongArray(header.tickCount) { source.i64() }
            source.expectRemaining(CRC_BYTES, "after the hash stream")
            return ReplayRecording(header, frameBytes, offsets, hashes)
        }

        /** Bytes the trailing CRC32 occupies. */
        public const val CRC_BYTES: Int = 4

        private fun verifyCrc(bytes: ByteArray) {
            if (bytes.size < ReplayFormat.PREAMBLE_BYTES + CRC_BYTES) {
                throw ReplayFormatException(
                    "a .udearep is at least ${ReplayFormat.PREAMBLE_BYTES + CRC_BYTES} bytes and " +
                        "this one is ${bytes.size}",
                )
            }
            val end = bytes.size - CRC_BYTES
            val expected = ReplayFormat.crc32(bytes, end).toInt()
            val stored = ByteSource(bytes, end).i32()
            if (expected != stored) {
                throw ReplayFormatException(
                    "this recording's CRC32 is 0x${"%08x".format(stored)} and its $end bytes " +
                        "hash to 0x${"%08x".format(expected)}; the file is damaged",
                )
            }
        }

        private fun readHeader(source: ByteSource): ReplayHeader {
            val rootSeed = source.i64()
            val protoHash = source.i32()
            val assetHashLength = source.u8()
            val assetGraphHash = source.raw(assetHashLength)
            val schemaHash = source.i64()
            val tickRateHz = source.i32()
            val firstTick = Tick(source.i64())
            val tickCount = source.i32()
            val peerCount = source.i32()
            val gameId = source.string()
            val gameVersion = source.string()
            val axes = readNames(source, "axes")
            val actions = readNames(source, "actions")
            val schema = InputSchema(axes, actions)
            if (schema.hash != schemaHash) {
                throw ReplayFormatException(
                    "the header stores input schema hash $schemaHash and the names beside it " +
                        "hash to ${schema.hash}; the file is damaged or was written by a build " +
                        "whose schema hashing differs",
                )
            }
            if (tickCount < 0 || tickCount > ReplayFormat.MAX_TICKS) {
                throw ReplayFormatException(
                    "the header declares $tickCount ticks, outside 0..${ReplayFormat.MAX_TICKS}",
                )
            }
            if (peerCount < 1 || peerCount > ReplayFormat.MAX_PEERS) {
                throw ReplayFormatException(
                    "the header declares $peerCount peers, outside 1..${ReplayFormat.MAX_PEERS}",
                )
            }
            if (tickRateHz <= 0) {
                throw ReplayFormatException("the header declares a tick rate of $tickRateHz")
            }
            return ReplayHeader(
                identity = BuildIdentity(rootSeed, protoHash, assetGraphHash, schemaHash),
                schema = schema,
                tickRateHz = tickRateHz,
                firstTick = firstTick,
                tickCount = tickCount,
                peerCount = peerCount,
                gameId = gameId,
                gameVersion = gameVersion,
            )
        }

        private fun readNames(source: ByteSource, what: String): List<String> {
            val count = source.u16()
            if (count > ReplayFormat.MAX_NAMES) {
                throw ReplayFormatException(
                    "the header declares $count $what, past the ${ReplayFormat.MAX_NAMES} cap",
                )
            }
            return List(count) { source.string() }
        }
    }
}
