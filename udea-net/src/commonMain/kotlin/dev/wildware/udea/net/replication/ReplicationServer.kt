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
import dev.wildware.udea.net.rpc.RpcOwnership
import dev.wildware.udea.net.transport.LoopbackNetwork
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.transport.Transport
import dev.wildware.udea.net.wire.BaselineSet
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

    /**
     * Which connection owns which entity, which is what `@Net(visibility = OwnerOnly)` turns on
     * (issue #167).
     *
     * ## Why the RPC's ownership type and not a second one
     *
     * [RpcOwnership] already answers exactly the question the writer has to ask — "the connection
     * that owns this entity, or [dev.wildware.udea.net.transport.PeerId.SERVER] if none does" —
     * and a game already has one instance of it, because the generated RPC guard reads it to
     * refuse a datagram. Minting a second ownership registry beside it would be a second thing
     * that can disagree with the first, and this class' own `writeRemovals` says why that is the
     * shape to avoid: "the roster already exists ... and a second one is a second thing that can
     * disagree". Here the disagreement would be silent and in the leaking direction — a champion
     * the RPC guard says a peer owns, and the packer says it does not.
     *
     * The default is the safe one: [RpcOwnership.NONE] makes every entity server-owned, so no
     * client is any entity's owner and every owner-only field is stripped from every packet. A
     * session that has not been told who owns what sends nothing private to anybody, which is
     * the failure that loses data rather than the one that leaks it.
     */
    public val ownership: RpcOwnership = RpcOwnership.NONE,

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
    private val baselines = BaselineSet()
    private val states = LinkedHashMap<Int, ClientReplicationState>()
    private val jitterBuffers = LinkedHashMap<Int, JitterBuffer>()

    /** Entities that were written in full because their baseline had aged out of the ring. */
    public var baselineRecoveries: Long = 0L
        private set

    /** Entities dropped from a datagram because the budget was spent. They win the next tick. */
    public var budgetDeferrals: Long = 0L
        private set

    /**
     * `Destroy` records that did not fit this datagram and will be written again next tick.
     *
     * Removals are written before anything else, so this is only ever non-zero when the destroys
     * *alone* exceed the budget - a wave dying at once. It is counted rather than assumed away
     * because the failure it replaces was silent: a truncated section loses destroys, and a
     * client keeps corpses that the server deleted with nothing anywhere saying so.
     */
    public var removalDeferrals: Long = 0L
        private set

    /**
     * `Leave` records written: an entity the client held and [relevancy] has stopped allowing.
     *
     * Counted separately from a `Destroy` because they mean opposite things about the world - a
     * destroy says the entity is gone, a leave says only that this client may no longer see it -
     * and because a fog implementation that thrashes shows up here as a number that climbs with
     * nothing dying.
     */
    public var leaveWrites: Long = 0L
        private set

    /** Entities written in full because the client had confirmed a `Leave` and is being given the entity back. */
    public var reentries: Long = 0L
        private set

    /** Registers [peer] and returns its state. Idempotent. */
    public fun addClient(peer: PeerId): ClientReplicationState {
        jitterBuffers.getOrPut(peer.raw) { JitterBuffer(capacity = jitterCapacity) }
        return states.getOrPut(peer.raw) { ClientReplicationState(peer) }
    }

    /**
     * Drops [peer]: it stops being broadcast to, and its baselines are forgotten.
     *
     * Without this a disconnected client is still packed and sent a datagram every tick for the
     * life of the process, and - worse - [addClient] is a `getOrPut`, so a peer id the transport
     * recycles for the *next* connection inherits the dead one's acked baseline ticks. The new
     * client would then be delta-encoded against state belonging to somebody who has left, which
     * decodes cleanly and is wrong in every field. A transport that reuses slots (`UdpTransport`
     * does) makes that the normal case rather than the rare one.
     *
     * @return true when [peer] was registered.
     */
    public fun removeClient(peer: PeerId): Boolean {
        jitterBuffers.remove(peer.raw)
        return states.remove(peer.raw) != null
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
        // A client that has received nothing yet still sends input, and its ack field is
        // padding. Applying it would acknowledge this server's sequence 0 - promoting the
        // baseline of every entity that packet carried to a state the client may never have
        // received. Under a perfect link that is invisible; under loss it is permanent.
        if (header.hasAck) state.applyAck(header.ack, header.ackBits)

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
            hasAck = state.remoteSeq >= 0,
            // Which *command* this tick was simulated with, so the client can reconcile against
            // the right point in its own history. See `PacketHeader.inputAck`.
            inputAck = jitterBuffers[client.raw]?.lastProcessedInputSeq ?: PacketHeader.NO_INPUT_ACK,
        ).write(writer)

        val payload = frames.beginMessage(MessageType.Snapshot)
        section.begin()
        // The section still has to be closed and the frame still has to be byte-aligned after the
        // last record fits, and neither is free. Packing to the literal end of the datagram and
        // then discovering that the terminator does not fit throws out of `send` with a full
        // buffer and no way to rewind to anything sendable - so the ceiling the packer works to
        // is the buffer minus that tail, never the buffer.
        val budgetBytes = minOf(budget.bytesPerPacket, buffer.size - SECTION_TAIL_BYTES)

        writeRemovals(payload, state, fields, seq, current.tick, budgetBytes)
        accumulateAndSelect(state, fields, current.tick)
        packSelected(payload, state, current, fields, seq, budgetBytes)

        section.end(payload)
        frames.endMessage()
        val length = frames.byteLength
        transport.send(client, buffer, 0, length)
        return length
    }

    /**
     * Emits a removal for every entity this client believes in that it must stop believing in.
     *
     * Walks the client's own baseline generations rather than a separate roster: the roster
     * already exists, as the set of indices the client has acked, and a second one is a second
     * thing that can disagree.
     *
     * ## Two reasons, two ops
     *
     * `Destroy` - the world no longer has the entity. `Leave` - the world still has it, but
     * [relevancy] says this client may not be told about it. The client's *store* treats the two
     * identically (the row goes), and that is correct; the distinction is for the game above it,
     * where a corpse plays a death animation and a unit walking into fog simply stops being
     * drawn. Under [RelevancySet.ALL_VISIBLE] the `Leave` arm is unreachable, which is why
     * turning fog on is the only thing that can change any existing behaviour here.
     *
     * Both are re-written every tick until an ack confirms them, for the same reason: a lost
     * removal that were written once would leave the client holding the entity for ever, and -
     * worse for a `Leave` - the entity could never be given back, because the server would think
     * the client had it. That is what makes this loop walk *state* rather than a per-tick event
     * list: the state is still true next tick, so the record is simply written again.
     */
    private fun writeRemovals(
        out: BitWriter,
        state: ClientReplicationState,
        fields: WorldFieldStore,
        seq: Int,
        tick: Tick,
        budgetBytes: Int,
    ) {
        for (index in 0 until state.trackedIndices) {
            val generation = state.trackedGeneration(index)
            if (generation < 0) continue
            val netId = NetId.of(index, generation)
            val gone = fields.rowOf(netId) == WorldFieldStore.NO_ROW
            val op = when {
                gone -> EntityOp.Destroy
                !relevancy.isRelevant(state.peer, netId) -> EntityOp.Leave
                else -> continue
            }

            // Enough units dying in one tick will fill a datagram with destroys alone. The
            // section must not be truncated mid-record: the same rollback pair the entity packer
            // uses puts the bytes and the delta chain back, and the destroys that did not fit
            // are simply not marked pending, so they are written again next tick.
            val mark = writer.bitPosition
            val cursor = section.cursor()
            try {
                section.writeRemoval(out, netId, op)
            } catch (overflow: BitBufferOverflow) {
                writer.truncateTo(mark)
                section.rewindTo(cursor)
                removalDeferrals++
                return
            }
            if (writer.byteLength > budgetBytes) {
                writer.truncateTo(mark)
                section.rewindTo(cursor)
                removalDeferrals++
                return
            }
            state.markDestroyPending(netId, tick)
            if (op == EntityOp.Leave) leaveWrites++
            // Recorded *as a removal*, so the ack that confirms the datagram retires the id and
            // is never mistaken for the client acknowledging that it holds one — and an unacked
            // removal is simply written again next tick.
            state.recordRemovalSent(netId, seq, tick)
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

            // The client confirmed a removal for this slot and the entity is back in view. The
            // slot must start over, because `RETIRED` is deliberately terminal in the ack path:
            // without this the entity is written in full every tick for ever, since no ack can
            // ever promote it back to a baseline.
            if (state.isRetired(netId.index)) {
                state.forget(netId)
                reentries++
            }

            val mark = writer.bitPosition
            val cursor = section.cursor()
            val baselineTick = state.baselineTickOf(netId)
            val delta = collectBaselines(state, netId, baselineTick)
            // The one place per entity per recipient where ownership is asked. It is the whole of
            // the per-recipient part of the packet: everything else about this section is the same
            // for every client, which is why the ring can stay one shared structure.
            val owns = ownership.ownerOf(netId) == state.peer
            val written = try {
                if (!delta) {
                    if (baselineTick != ClientReplicationState.NO_BASELINE) baselineRecoveries++
                    section.writeCreate(out, fields, row, owns)
                } else {
                    section.writeUpdate(out, fields, row, baselines, owns)
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
     * Fills [baselines] with every state [client] could be holding for [netId].
     *
     * That set is the acked baseline **plus every send since that has not been acknowledged**,
     * and getting it wrong is the whole of the convergence defect this replaces. The server's
     * baseline moves on an ack; the client's store moves on an apply; they are a round trip
     * apart. Diffing against the acked tick alone omits any field that changed and changed back
     * inside that window, and the client keeps the intermediate value permanently - which is
     * what "disagree at tick 204 on Attributes" was.
     *
     * @return true when a delta can be written; false means write the entity in full, because
     *   the client has never acked it, the tracking overflowed, or one of the states it might be
     *   holding has aged out of the ring and cannot be diffed against. All three are the same
     *   recovery and none of them needs a message.
     */
    private fun collectBaselines(
        state: ClientReplicationState,
        netId: NetId,
        baselineTick: Long,
    ): Boolean {
        baselines.clear()
        if (baselineTick == ClientReplicationState.NO_BASELINE) return false
        val pending = state.pendingSendCount(netId)
        if (pending == ClientReplicationState.PENDING_OVERFLOW) return false
        val acked = snapshotAt(baselineTick) ?: return false
        baselines.add(acked.fields, acked.fields.rowOf(netId))
        for (position in 0 until pending) {
            val tick = state.pendingSendTick(netId, position)
            if (tick <= baselineTick) continue
            val snapshot = snapshotAt(tick) ?: return false
            baselines.add(snapshot.fields, snapshot.fields.rowOf(netId))
        }
        return true
    }

    /** The ring slot for exactly [tickValue], or null when the ring no longer holds it. */
    private fun snapshotAt(tickValue: Long): WorldSnapshot? {
        val tick = Tick(tickValue)
        if (!ring.holds(tick)) return null
        return ring.nearestAtOrBefore(tick)?.takeIf { it.tick == tick }
    }

    public companion object {

        /**
         * Bytes held back from the packer for the section terminator and the frame's alignment.
         *
         * Two bytes for the terminating zig-zag, one for aligning the frame to a byte boundary,
         * and one of slack. Reserving four bytes of a datagram is not a measurable cost; running
         * out of room for the terminator is a thrown `BitBufferOverflow` at the top of `send`.
         */
        public const val SECTION_TAIL_BYTES: Int = 4
    }
}
