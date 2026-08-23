package dev.wildware.udea.net.transport

import dev.wildware.udea.core.Tick

/**
 * What happened to one datagram, at the moment it happened.
 *
 * [checksum] is an FNV-1a over the payload rather than the payload itself: a 600-tick
 * four-client run records tens of thousands of entries, and the property under test is
 * "identical seed produces an identical packet log", for which a 32-bit digest of the bytes
 * is as strong an assertion as the bytes and costs a fraction of the memory.
 */
public data class PacketEvent(

    /** Tick at which the event was recorded. */
    public val tick: Tick,

    /** What the transport did with the datagram. */
    public val kind: PacketEventKind,

    /** Sending endpoint. */
    public val from: PeerId,

    /** Receiving endpoint. */
    public val to: PeerId,

    /** Payload length in bytes. */
    public val length: Int,

    /** FNV-1a 32 of the payload. */
    public val checksum: Int,
) {
    override fun toString(): String =
        "${tick.value} $kind $from->$to $length ${checksum.toUInt().toString(16).padStart(8, '0')}"
}

/** The dispositions a datagram can reach. */
public enum class PacketEventKind {

    /** Accepted by the sending transport and now in flight. */
    Sent,

    /** Discarded by the loss simulation. Never reaches a sink. */
    Dropped,

    /** Emitted an extra time by the duplication simulation. */
    Duplicated,

    /** Handed to a [DatagramSink]. */
    Delivered,
}

/**
 * The recorded history of a session, and the thing determinism is asserted against.
 *
 * "Two runs of the same seeded scenario behave the same" is not testable against timing or
 * against final world state alone — a difference can cancel out. It is testable against the
 * ordered list of every send, drop, duplication and delivery, which is what this is.
 *
 * Recording is opt-in ([enabled]) because it allocates one entry per event: a benchmark or a
 * production session runs with it off, a test runs with it on.
 */
public class PacketLog(public var enabled: Boolean = true) {

    private val entries = ArrayList<PacketEvent>()

    /** Everything recorded so far, oldest first. */
    public val events: List<PacketEvent> get() = entries

    /** How many events are recorded. */
    public val size: Int get() = entries.size

    internal fun record(
        tick: Tick,
        kind: PacketEventKind,
        from: PeerId,
        to: PeerId,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (!enabled) return
        entries += PacketEvent(tick, kind, from, to, length, checksum(buffer, offset, length))
    }

    /** One line per event, for a golden comparison or a failure message. */
    public fun render(): String = entries.joinToString(separator = "\n") { it.toString() }

    public companion object {

        private const val FNV_OFFSET_BASIS: Int = -2128831035
        private const val FNV_PRIME: Int = 16777619

        /** FNV-1a 32 over a byte slice. Not a hash for security, only for change detection. */
        public fun checksum(buffer: ByteArray, offset: Int, length: Int): Int {
            var hash = FNV_OFFSET_BASIS
            for (i in offset until offset + length) {
                hash = (hash xor (buffer[i].toInt() and 0xFF)) * FNV_PRIME
            }
            return hash
        }
    }
}
