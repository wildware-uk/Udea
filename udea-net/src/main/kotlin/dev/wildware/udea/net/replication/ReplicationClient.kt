package dev.wildware.udea.net.replication

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.net.bits.BitBufferReader
import dev.wildware.udea.net.bits.BitBufferWriter
import dev.wildware.udea.net.input.InputRing
import dev.wildware.udea.net.input.MoveInput
import dev.wildware.udea.net.transport.LoopbackNetwork
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.transport.Transport
import dev.wildware.udea.net.wire.FrameReader
import dev.wildware.udea.net.wire.FrameWriter
import dev.wildware.udea.net.wire.MessageType
import dev.wildware.udea.net.wire.PacketHeader
import dev.wildware.udea.net.wire.ProtocolDescriptor
import dev.wildware.udea.net.wire.ProtocolMismatchException
import dev.wildware.udea.net.wire.ReplicaStore
import dev.wildware.udea.net.wire.SnapshotApplySink
import dev.wildware.udea.net.wire.SnapshotReader

/**
 * The receiving half: applies delta snapshots, acknowledges them, and sends input.
 *
 * ## Applied in place, because baselines are per entity
 *
 * Each entity's delta is encoded against the newest packet containing that entity that this
 * client acknowledged — which is exactly the value sitting in [world]. So a packet is applied
 * straight into the one store, entity by entity, with no whole-world rebuild.
 *
 * ## Out-of-order and duplicate packets are discarded, not applied
 *
 * A packet older than the newest one applied is dropped and **not acknowledged**. Applying it
 * would move entities backwards, and acknowledging it would tell the server the client holds a
 * state it does not. Dropping it costs nothing: an unacknowledged packet leaves every entity's
 * baseline where it was, so the server re-sends the same information next tick. That is the
 * entire recovery protocol — no negative acknowledgements, no resend requests, no timers, and
 * the same mechanism covers a lost create, a lost destroy and a client whose baseline has aged
 * out of the server's ring.
 */
public class ReplicationClient(

    /** Which client this is. */
    public val peer: PeerId,

    /** The component registry the whole session shares. */
    public val registry: ComponentRegistry,

    /** This build's protocol. A mismatch is refused, loudly and by name. */
    public val protocol: ProtocolDescriptor,

    /** Where datagrams go and come from. */
    private val transport: Transport,

    /** Outbound commands, three copies each. */
    public val input: InputRing = InputRing(),

    /** Sim ticks between input sends. Two, so 30Hz input against a 60Hz sim (spec 3.3). */
    public val inputInterval: Int = DEFAULT_INPUT_INTERVAL,

    mtu: Int = LoopbackNetwork.DEFAULT_MTU,
) {

    private val buffer = ByteArray(mtu)
    private val writer = BitBufferWriter(buffer)
    private val frames = FrameWriter(writer)
    private val sectionReader = SnapshotReader(registry)

    /** Highest server sequence applied, for the ack field of outgoing packets. */
    public var remoteSeq: Int = -1
        private set

    /** Bit `n` set means server sequence `remoteSeq - 1 - n` was also applied. */
    public var remoteAckBits: Int = 0
        private set

    /** The next sequence outgoing packets will carry. */
    public var nextSeq: Int = 0
        private set

    /** Newest server tick whose state this client holds. */
    public var serverTick: Tick = Tick.ZERO
        private set

    /** Packets dropped as older than, or a duplicate of, one already applied. */
    public var staleDropped: Long = 0L
        private set

    /** Packets applied. */
    public var applied: Long = 0L
        private set

    /** The client's view of the replicated world. Empty until the first packet lands. */
    public val world: ReplicaStore = ReplicaStore(registry)

    /** Told what each packet changed, so an ECS layer can push it onto live components. */
    public var applySink: SnapshotApplySink = SnapshotApplySink { _, _, _, _ -> }

    /**
     * Applies one datagram from the server.
     *
     * @return true when the packet was applied and will be acknowledged.
     * @throws ProtocolMismatchException when the server is a different build. Thrown rather than
     *   ignored: a client silently discarding every packet looks exactly like a dead connection,
     *   and the one thing the old stack could not do was say *what* differed.
     */
    public fun onPacket(source: ByteArray, offset: Int, length: Int): Boolean {
        val src = BitBufferReader(source, offset, length)
        val header = PacketHeader.read(src)
        if (header.protoHash != protocol.protoHash) {
            throw ProtocolMismatchException(protocol.protoHash, header.protoHash, emptyList())
        }
        if (remoteSeq >= 0 && !PacketHeader.isNewer(header.seq, remoteSeq)) {
            staleDropped++
            return false
        }
        val walker = FrameReader(source, offset, length, src.bitPosition)
        while (true) {
            val frame = walker.next() ?: break
            if (frame.type != MessageType.Snapshot) continue
            sectionReader.read(walker.readerFor(frame), world, applySink)
        }
        serverTick = header.serverTick
        recordApplied(header.seq)
        applied++
        return true
    }

    /** Queues [command] for the next input send. */
    public fun pushInput(command: MoveInput) {
        input.push(command)
    }

    /**
     * Sends this tick's packet, if this is an input tick.
     *
     * Only ever an ack plus input commands. There is no code path here that writes a replicated
     * component field, which is what `NoClientStateUploadTest` asserts against the recorded log.
     *
     * @return the payload length in bytes, or zero when nothing was sent.
     */
    public fun sendTick(tick: Tick): Int {
        if (tick.value % inputInterval != 0L) return 0
        if (input.newest == null && remoteSeq < 0) return 0
        writer.reset()
        val seq = nextSeq
        nextSeq = (nextSeq + 1) and PacketHeader.SEQ_MASK
        PacketHeader(
            protoHash = protocol.protoHash,
            seq = seq,
            ack = if (remoteSeq < 0) 0 else remoteSeq,
            ackBits = remoteAckBits,
            serverTick = serverTick,
            baselineTick = Tick.ZERO,
            hasBaseline = false,
        ).write(writer)
        if (input.newest != null) {
            val payload = frames.beginMessage(MessageType.Input)
            input.write(payload)
            frames.endMessage()
        }
        val length = frames.byteLength
        transport.send(PeerId.SERVER, buffer, 0, length)
        return length
    }

    private fun recordApplied(seq: Int) {
        if (remoteSeq < 0) {
            remoteSeq = seq
            remoteAckBits = 0
            return
        }
        if (PacketHeader.isNewer(seq, remoteSeq)) {
            val shift = ClientReplicationState.sequenceDistance(seq, remoteSeq)
            remoteAckBits = if (shift >= ClientReplicationState.ACK_BITS) {
                0
            } else {
                (remoteAckBits shl shift) or (1 shl (shift - 1))
            }
            remoteSeq = seq
        } else {
            val back = ClientReplicationState.sequenceDistance(remoteSeq, seq)
            if (back in 1..ClientReplicationState.ACK_BITS) remoteAckBits = remoteAckBits or (1 shl (back - 1))
        }
    }

    public companion object {

        /** Two sim ticks per input packet: 30Hz against 60Hz (spec 3.3). */
        public const val DEFAULT_INPUT_INTERVAL: Int = 2
    }
}
