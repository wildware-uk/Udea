package dev.wildware.udea.net.transport

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.rng.SimRandom

/**
 * The shape of a link: how bad the network is allowed to be.
 *
 * Everything is expressed in ticks and in probabilities, never in milliseconds and never in
 * wall time, so a condition set means the same thing on a fast machine and a slow one. At the
 * engine's 60Hz sim tick, `latencyTicks = 9` is 150ms — the figure Trello #8 names.
 *
 * Loss is a first-class configured property rather than something that happens to you in
 * production. A test that runs the whole replication stack at 5% loss on every commit is the
 * only way "converges under loss" stays true; the old stack had no way to express it at all.
 */
public data class NetConditions(

    /** One-way delay, in ticks, before jitter. */
    public val latencyTicks: Int = 0,

    /** Maximum extra delay added on top of [latencyTicks], drawn uniformly in `0..jitterTicks`. */
    public val jitterTicks: Int = 0,

    /** Probability in `0f..1f` that a datagram is discarded outright. */
    public val lossChance: Float = 0f,

    /**
     * Probability that a datagram is pulled *earlier* than its scheduled slot, which is how a
     * reorder is produced without a second delivery model: jitter alone can only ever delay.
     */
    public val reorderChance: Float = 0f,

    /** Probability that a datagram is delivered twice, the second copy with its own jitter. */
    public val duplicateChance: Float = 0f,

    /** Bytes this link will release per tick. Excess waits, it is not dropped. */
    public val bytesPerTick: Int = Int.MAX_VALUE,
) {

    init {
        require(latencyTicks >= 0) { "latencyTicks must be >= 0, was $latencyTicks" }
        require(jitterTicks >= 0) { "jitterTicks must be >= 0, was $jitterTicks" }
        require(lossChance in 0f..1f) { "lossChance must be in 0..1, was $lossChance" }
        require(reorderChance in 0f..1f) { "reorderChance must be in 0..1, was $reorderChance" }
        require(duplicateChance in 0f..1f) { "duplicateChance must be in 0..1, was $duplicateChance" }
        require(bytesPerTick > 0) { "bytesPerTick must be positive, was $bytesPerTick" }
    }

    public companion object {

        /** No delay, no loss, no cap. What a listen server's local client gets. */
        public val PERFECT: NetConditions = NetConditions()

        /** 150ms at 60Hz with 5% loss: the condition set Trello #8 names for the harness. */
        public val TRELLO_8: NetConditions =
            NetConditions(latencyTicks = 9, jitterTicks = 2, lossChance = 0.05f, reorderChance = 0.02f)
    }
}

/**
 * A [Transport] decorator that makes the network as bad as [conditions] say, reproducibly.
 *
 * ## Why this is a decorator and not a mode on [LoopbackTransport]
 *
 * The zero-latency path has to stay honestly zero-latency: if loss and delay were branches
 * inside the loopback, every "no network" test would be running the simulation code with its
 * probabilities set to zero, and a bug in the scheduler would be invisible in exactly the
 * tests most likely to be run. Layering keeps the two testable apart.
 *
 * ## Determinism
 *
 * Every random draw comes from [rng], a [SimRandom] seeded from the session seed, and every
 * deadline is a [Tick] read from a [ManualClock]. There is no wall clock and no
 * `kotlin.random.Random` anywhere in the path, which is what makes a failure at 5% loss
 * reproducible from its seed rather than a thing that happened once in CI.
 *
 * Datagrams released in one [flush] go out ordered by `(deliverAt, sendOrder)`, so two runs
 * with the same seed produce byte-identical packet logs.
 */
public class SimulatedTransport(

    /** The link the simulation ultimately writes to; usually a [LoopbackTransport]. */
    private val delegate: Transport,

    /** The clock all deadlines are measured against. */
    private val clock: ManualClock,

    /** The seeded generator every loss, jitter, reorder and duplication draw comes from. */
    private val rng: SimRandom,

    /** Where drops and duplications are recorded alongside the delegate's sends. */
    private val log: PacketLog,

    /** Largest datagram this link carries, for the pending buffer pool. */
    mtu: Int = LoopbackNetwork.DEFAULT_MTU,

    /** How bad the link is. Mutable so `net.set_conditions` can change it mid-session. */
    public var conditions: NetConditions = NetConditions.PERFECT,
) : Transport {

    override val localPeer: PeerId get() = delegate.localPeer

    private val pool = DatagramPool(mtu)

    /** Scheduled datagrams, kept sorted ascending by `(deliverAt, sendOrder)`. */
    private val pending = ArrayList<Datagram>()

    private val statsByPeer = HashMap<Int, TransportStats>()
    private var sendOrder = 0L
    private var budgetTick: Tick = Tick(Long.MIN_VALUE)
    private var budgetRemaining = 0
    private var receiveSink: DatagramSink? = null

    /** Recorded receives, forwarded to whatever sink [poll] was given. */
    private val countingSink = DatagramSink { from, buffer, offset, length ->
        stats(from).recordReceived(length)
        receiveSink?.receive(from, buffer, offset, length)
    }

    /** Datagrams scheduled but not yet released. A test asserts this drains. */
    public val inFlight: Int get() = pending.size

    /** How many pending buffers have ever been allocated. Flat once the link is warm. */
    public val allocatedDatagrams: Int get() = pool.created

    override fun send(peer: PeerId, bytes: ByteArray, offset: Int, length: Int) {
        val stats = stats(peer)
        if (rolls(conditions.lossChance)) {
            stats.packetsDropped++
            log.record(clock.tick, PacketEventKind.Dropped, localPeer, peer, bytes, offset, length)
            return
        }
        schedule(peer, bytes, offset, length)
        if (rolls(conditions.duplicateChance)) {
            log.record(clock.tick, PacketEventKind.Duplicated, localPeer, peer, bytes, offset, length)
            schedule(peer, bytes, offset, length)
        }
    }

    /**
     * Releases every scheduled datagram whose deadline has passed, up to the per-tick cap.
     *
     * Called once per tick by [NetHarness] after the endpoints have run. Splitting release
     * from [send] is what gives latency a meaning: a datagram sent this tick is not visible to
     * the receiver until a later tick's flush hands it to the delegate.
     */
    public fun flush() {
        val now = clock.tick
        if (budgetTick != now) {
            budgetTick = now
            budgetRemaining = conditions.bytesPerTick
        }
        // Strictly head of line: `pending` is sorted, so the first datagram that is not due, or
        // that does not fit the remaining budget, stops the flush. Skipping past it to release a
        // smaller one behind it would be reordering invented by the cap rather than by the link.
        while (pending.isNotEmpty()) {
            val datagram = pending[0]
            if (datagram.deliverAt > now) break
            if (datagram.length > budgetRemaining) break
            budgetRemaining -= datagram.length
            pending.removeAt(0)
            stats(datagram.to).recordSent(datagram.length)
            delegate.send(datagram.to, datagram.buffer, 0, datagram.length)
            pool.give(datagram)
        }
    }

    override fun poll(sink: DatagramSink): Int {
        receiveSink = sink
        try {
            return delegate.poll(countingSink)
        } finally {
            receiveSink = null
        }
    }

    /**
     * This link's counters, which are the authoritative ones.
     *
     * The delegate keeps its own set covering only what actually left; these cover what the
     * caller asked for, including [TransportStats.packetsDropped], which the delegate can
     * never see because a dropped datagram is never handed to it.
     */
    override fun stats(peer: PeerId): TransportStats =
        statsByPeer.getOrPut(peer.raw) { TransportStats(peer) }

    override fun close() {
        while (pending.isNotEmpty()) pool.give(pending.removeLast())
        delegate.close()
    }

    private fun schedule(peer: PeerId, bytes: ByteArray, offset: Int, length: Int) {
        val datagram = pool.take()
        datagram.fill(localPeer, peer, bytes, offset, length)
        datagram.deliverAt = clock.tick + delayTicks()
        datagram.sendOrder = sendOrder++
        insertSorted(datagram)
    }

    private fun delayTicks(): Long {
        var delay = conditions.latencyTicks.toLong()
        if (conditions.jitterTicks > 0) delay += rng.nextInt(conditions.jitterTicks + 1).toLong()
        // A reorder pulls a datagram forward past its neighbours. Without it, jitter alone can
        // only ever delay, so a link with latency 0 could never deliver out of order at all.
        if (delay > 0L && rolls(conditions.reorderChance)) delay = 0L
        return delay
    }

    private fun insertSorted(datagram: Datagram) {
        var index = pending.size
        while (index > 0) {
            val previous = pending[index - 1]
            val earlier = previous.deliverAt < datagram.deliverAt ||
                (previous.deliverAt == datagram.deliverAt && previous.sendOrder < datagram.sendOrder)
            if (earlier) break
            index--
        }
        pending.add(index, datagram)
    }

    /**
     * Whether a `0f..1f` probability fires.
     *
     * A zero chance draws nothing at all rather than drawing and comparing. That is not an
     * optimisation: it keeps a link configured with loss off consuming exactly the same
     * generator stream as one with no loss configured, so turning a knob on one client cannot
     * shift the draws of a feature that client never uses.
     */
    private fun rolls(chance: Float): Boolean = chance > 0f && rng.nextFloat() < chance
}
