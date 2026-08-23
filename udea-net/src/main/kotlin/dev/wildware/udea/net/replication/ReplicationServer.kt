package dev.wildware.udea.net.replication

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.replication.BitWriter
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.SnapshotRing
import dev.wildware.udea.core.snapshot.WorldFieldStore
import dev.wildware.udea.core.snapshot.WorldSnapshot
import dev.wildware.udea.net.bits.BitBufferOverflow
import dev.wildware.udea.net.bits.BitBufferReader
import dev.wildware.udea.net.bits.BitBufferWriter
import dev.wildware.udea.net.input.JitterBuffer
import dev.wildware.udea.net.transport.LoopbackNetwork
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.transport.Transport
import dev.wildware.udea.net.wire.EntityOp
import dev.wildware.udea.net.wire.FrameReader
import dev.wildware.udea.net.wire.FrameWriter
import dev.wildware.udea.net.wire.MessageType
import dev.wildware.udea.net.wire.PacketHeader
import dev.wildware.udea.net.wire.ProtocolDescriptor
import dev.wildware.udea.net.wire.SnapshotWriter

/**
 * One datagram per client per tick, assembled from the shared snapshot ring.
 *
 * ## What it replaces
 *
 * `ecs/system/NetworkServerSystem.kt:110-113` called `sendToAllUDP` from inside an
 * `IteratingSystem` body. That is one full-state datagram **per entity per client per tick**,
 * with no dirty tracking, no delta, no relevancy and no batching, and it leaked every
 * `EntityUpdate` it took from `EntityUpdatePool` (`PacketUtil.kt:167`). At 300 entities and four
 * clients that is 72 000 datagrams a second where this sends 240.
 *
 * ## The one structure
 *
 * The baseline store is the [SnapshotRing] (spec 3.1) — the same ring that backs time travel.
 * Per client this holds a `LongArray` of acked ticks and a `FloatArray` of priorities, and
 * nothing else; an entity's baseline is a *tick number naming a ring slot*. When that tick has
 * fallen out of the ring the entity is written in full instead, which is the whole of
 * baseline-loss recovery.
 *
 * ## Not a Fleks system
 *
 * Issue #107 names a `ReplicationServerSystem`. This is the plain-Kotlin engine underneath one:
 * it takes a captured [WorldSnapshot] and a [Transport] and owes nothing to a `World`. The
 * `SimSystem` that calls [broadcast] once per tick is not written here, because scheduling it
 * belongs with the module that owns the system manifest.
 */
public class ReplicationServer(

    /** The component registry the whole session shares. */
    public val registry: ComponentRegistry,

    /** This build's protocol, for the header and the handshake. */
    public val protocol: ProtocolDescriptor,

    /** Where datagrams go. */
    private val transport: Transport,

    /** The baseline store. Also the rewind buffer: one structure, two features (spec 3.1). */
    private val ring: SnapshotRing,

    /** Payload ceiling per datagram. */
    public val budget: BandwidthBudget = BandwidthBudget(),

    /** Who may see what. All-visible until the relevancy issue lands. */
    public val relevancy: RelevancySet = RelevancySet.ALL_VISIBLE,

    /** Priority growth. */
    private val accumulator: PriorityAccumulator = PriorityAccumulator(),

    /** How deep each client's input jitter buffer may get before it drops the oldest command. */
    private val jitterCapacity: Int = JitterBuffer.DEFAULT_CAPACITY,

    mtu: Int = LoopbackNetwork.DEFAULT_MTU,
) {

    private val buffer = ByteArray(mtu)
    private val writer = BitBufferWriter(buffer)
    private val frames = FrameWriter(writer)
    private val section = SnapshotWriter(registry)
    private val selector = PrioritySelector()
    private val states = LinkedHashMap<Int, ClientReplicationState>()
    private val jitterBuffers = LinkedHashMap<Int, JitterBuffer>()

    /** Entities that were written in full because their baseline had aged out of the ring. */
    public var baselineRecoveries: Long = 0L
        private set

    /** Entities dropped from a datagram because the budget was spent. They win the next tick. */
    public var budgetDeferrals: Long = 0L
        private set

    /** Registers [peer] and returns its state. Idempotent. */
    public fun addClient(peer: PeerId): ClientReplicationState {
        jitterBuffers.getOrPut(peer.raw) { JitterBuffer(capacity = jitterCapacity) }
        return states.getOrPut(peer.raw) { ClientReplicationState(peer) }
    }

    /** The replication state for [peer]. */
    public fun stateOf(peer: PeerId): ClientReplicationState =
        states[peer.raw] ?: error("$peer is not a registered client")

    /** The input jitter buffer for [peer]. */
    public fun jitterOf(peer: PeerId): JitterBuffer =
        jitterBuffers[peer.raw] ?: error("$peer is not a registered client")

    /** Every registered client, in registration order. */
    public fun clients(): List<PeerId> = states.keys.map(::PeerId)

    /**
     * Consumes a datagram from a client: its acks, and its input commands.
     *
     * Nothing else is accepted. There is no branch here that could write a replicated component
     * field, which is what makes "a client cannot own its own position" structural rather than a
     * rule somebody has to remember (issue #108).
     */
    public fun onPacket(from: PeerId, source: ByteArray, offset: Int, length: Int) {
        val state = states[from.raw] ?: return
        val src = BitBufferReader(source, offset, length)
        val header = PacketHeader.read(src)
        if (header.protoHash != protocol.protoHash) return
        state.onReceived(header.seq)
        state.applyAck(header.ack, header.ackBits)

        val walker = FrameReader(source, offset, length, src.bitPosition)
        while (true) {
            val frame = walker.next() ?: break
            when (frame.type) {
                MessageType.Input -> jitterOf(from).readInto(walker.readerFor(frame))
                // A client sending a snapshot section is either a bug or an attack. Ignoring it
                // is the only correct answer: there is no code path that would apply it.
                else -> Unit
            }
        }
    }

    /** Sends one datagram to every registered client. */
    public fun broadcast(current: WorldSnapshot) {
        for (peer in states.keys) send(PeerId(peer), current)
    }

    /**
     * Builds and sends [client]'s datagram for [current].
     *
     * @return the payload length in bytes.
     */
    public fun send(client: PeerId, current: WorldSnapshot): Int {
        val state = stateOf(client)
        val fields = current.fields
        val seq = state.beginPacket(current.tick)
        val hasBaseline = state.ackedPackets > 0L

        writer.reset()
        PacketHeader(
            protoHash = protocol.protoHash,
            seq = seq,
            ack = if (state.remoteSeq < 0) 0 else state.remoteSeq,
            ackBits = state.remoteAckBits,
            serverTick = current.tick,
            baselineTick = state.lastAckedTick,
            hasBaseline = hasBaseline,
        ).write(writer)

        val payload = frames.beginMessage(MessageType.Snapshot)
        section.begin()
        val budgetBytes = budget.bytesPerPacket

        writeRemovals(payload, state, fields, seq, current.tick)
        accumulateAndSelect(state, fields, current.tick)
        packSelected(payload, state, current, fields, seq, budgetBytes)

        section.end(payload)
        frames.endMessage()
        val length = frames.byteLength
        transport.send(client, buffer, 0, length)
        return length
    }

    /**
     * Emits a `Destroy` for every entity this client believes in that the world no longer has.
     *
     * Walks the client's own baseline generations rather than a separate roster: the roster
     * already exists, as the set of indices the client has acked, and a second one is a second
     * thing that can disagree.
     */
    private fun writeRemovals(
        out: BitWriter,
        state: ClientReplicationState,
        fields: WorldFieldStore,
        seq: Int,
        tick: Tick,
    ) {
        for (index in 0 until state.trackedIndices) {
            val generation = state.trackedGeneration(index)
            if (generation < 0) continue
            val netId = NetId.of(index, generation)
            if (fields.rowOf(netId) != WorldFieldStore.NO_ROW) continue
            section.writeRemoval(out, netId, EntityOp.Destroy)
            state.markDestroyPending(netId)
            // Recorded like any other entity in the packet, so the ack that confirms the datagram
            // is what retires the id — and an unacked Destroy is simply written again next tick.
            state.recordSent(netId, seq, tick)
        }
    }

    private fun accumulateAndSelect(state: ClientReplicationState, fields: WorldFieldStore, tick: Tick) {
        selector.clear()
        for (row in 0 until fields.rowCount) {
            val netId = fields.netIdAt(row)
            if (!relevancy.isRelevant(state.peer, netId)) continue
            // An index whose Destroy is still unacknowledged cannot also carry its new occupant:
            // one section addresses each index once, and a client that saw the create before the
            // destroy would delete the entity it had just been given. It waits one ack.
            if (state.isDestroyPending(netId.index)) continue
            val priority = accumulator.accumulate(state, netId, tick, relevancy.weightOf(state.peer, netId))
            selector.add(netId, priority)
        }
        selector.heapify()
    }

    private fun packSelected(
        out: BitWriter,
        state: ClientReplicationState,
        current: WorldSnapshot,
        fields: WorldFieldStore,
        seq: Int,
        budgetBytes: Int,
    ) {
        while (selector.size > 0) {
            val netId = selector.poll()
            val row = fields.rowOf(netId)
            if (row == WorldFieldStore.NO_ROW) continue

            val mark = writer.bitPosition
            val cursor = section.cursor()
            val baselineTick = state.baselineTickOf(netId)
            val baseline = baselineFor(baselineTick)
            val written = try {
                if (baseline == null) {
                    if (baselineTick != ClientReplicationState.NO_BASELINE) baselineRecoveries++
                    section.writeCreate(out, fields, row)
                } else {
                    section.writeUpdate(out, fields, row, baseline.fields, baseline.fields.rowOf(netId))
                }
            } catch (overflow: BitBufferOverflow) {
                // The datagram filled mid-entity. Both rollbacks together: the bytes, and the
                // delta chain that the discarded entity advanced. Everything already written
                // stays a valid, sendable prefix — which is the property BitBufferWriter
                // promises and the reason the packer can be this simple.
                writer.truncateTo(mark)
                section.rewindTo(cursor)
                budgetDeferrals++
                return
            }

            if (written == 0) {
                // Nothing to say about this entity: it already matches the client's baseline.
                state.clearPriority(netId)
                continue
            }
            if (writer.byteLength > budgetBytes) {
                writer.truncateTo(mark)
                section.rewindTo(cursor)
                budgetDeferrals++
                return
            }
            state.recordSent(netId, seq, current.tick)
        }
    }

    /**
     * The ring slot for [baselineTick], or null when there is no usable baseline.
     *
     * Null means "write it in full": either the client has never acked this entity, or the tick
     * it acked has aged out of the ring. Both are the same recovery, and neither needs a message.
     */
    private fun baselineFor(baselineTick: Long): WorldSnapshot? {
        if (baselineTick == ClientReplicationState.NO_BASELINE) return null
        val tick = Tick(baselineTick)
        if (!ring.holds(tick)) return null
        return ring.nearestAtOrBefore(tick)?.takeIf { it.tick == tick }
    }
}
