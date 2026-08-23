package dev.wildware.udea.net.transport

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.rng.DefaultRngService
import dev.wildware.udea.core.rng.SimRandom

/**
 * One participant in a [NetHarness] session.
 *
 * Deliberately tiny. Everything the replication stack needs — receive a datagram, do a tick's
 * work, send — fits here, so the harness can drive a server, a real client and a
 * one-line-of-code test double through the identical loop.
 */
public interface NetEndpoint {

    /** Which endpoint this is. */
    public val peer: PeerId

    /**
     * A datagram arrived. The slice is valid only for the duration of the call: copy anything
     * that must outlive it.
     */
    public fun onReceive(from: PeerId, buffer: ByteArray, offset: Int, length: Int)

    /** Do this tick's work, including any sends. Called once per tick, after [onReceive]. */
    public fun onTick(tick: Tick)
}

/**
 * `net.spawn_session(clients = n)`: a server and n clients in one JVM, on one thread.
 *
 * No sockets, no threads, no `Thread.sleep`, and a manual clock — so a 600-tick four-client
 * run at 150ms latency and 5% loss finishes in milliseconds and produces the same bytes every
 * time. Every later test in the networking epic is written against this rather than against a
 * socket, which is the difference between "converges under loss" being asserted on every
 * commit and being hoped for.
 *
 * ## The tick order, and why it is this one
 *
 * ```
 * clock.advance()                    // T
 * every link .flush()                // release everything whose deadline is <= T
 * every endpoint .poll -> onReceive  // consume what arrived
 * every endpoint .onTick(T)          // simulate, then send; deadline is T + latency
 * ```
 *
 * Releasing before receiving is what gives latency an exact meaning, and sending last is what
 * makes the minimum round trip two ticks rather than zero: a datagram sent during `onTick(T)`
 * on a perfect link is released at the start of `T + 1`. A loop that polled after sending
 * would deliver a zero-latency datagram inside the tick that produced it, and every
 * prediction test above would be validating a network that cannot exist.
 */
public class NetHarness(

    /** How many clients to stand up. Peers are `client(1)` through `client(n)`. */
    public val clients: Int,

    /** Root seed. Every link derives its generator from this, so the session is reproducible. */
    public val seed: Long = DEFAULT_SEED,

    /** Datagram ceiling for every link. */
    public val mtu: Int = LoopbackNetwork.DEFAULT_MTU,

    /** Conditions applied to every link at construction. Change one later with [setConditions]. */
    initialConditions: NetConditions = NetConditions.PERFECT,
) {

    init {
        require(clients >= 0) { "clients must be >= 0, was $clients" }
    }

    /** The one clock the whole session reads. */
    public val clock: ManualClock = ManualClock()

    /** Every send, drop, duplication and delivery, in order. */
    public val log: PacketLog = PacketLog()

    private val network = LoopbackNetwork(clock, mtu, log)
    private val links = LinkedHashMap<Int, SimulatedTransport>()
    private val endpoints = ArrayList<NetEndpoint>()
    private val receivers = ArrayList<DatagramSink>()

    init {
        for (raw in 0..clients) {
            val peer = PeerId(raw)
            links[raw] = SimulatedTransport(
                delegate = network.transportFor(peer),
                clock = clock,
                rng = SimRandom(DefaultRngService.streamSeed(seed, raw)),
                log = log,
                mtu = mtu,
                conditions = initialConditions,
            )
        }
    }

    /** The server peer. Always [PeerId.SERVER]. */
    public val server: PeerId get() = PeerId.SERVER

    /** Every client peer, ascending. */
    public fun clientPeers(): List<PeerId> = (1..clients).map(PeerId::client)

    /** The link for [peer]. This is the [Transport] an endpoint should send on. */
    public fun transport(peer: PeerId): SimulatedTransport =
        links[peer.raw] ?: error("$peer is not part of this session (clients = $clients)")

    /**
     * Registers an endpoint, which will be polled and ticked from the next [step].
     *
     * The returned endpoint is the one passed in, so a caller can register and keep a typed
     * reference in one expression.
     */
    public fun <E : NetEndpoint> register(endpoint: E): E {
        require(endpoint.peer.raw in 0..clients) {
            "${endpoint.peer} is not part of this session (clients = $clients)"
        }
        require(endpoints.none { it.peer == endpoint.peer }) {
            "${endpoint.peer} already has an endpoint registered"
        }
        endpoints += endpoint
        // One sink per endpoint, built once: `poll` is called every tick for the life of the
        // session, and a lambda built per call would allocate once per endpoint per tick.
        receivers += DatagramSink { from, buffer, offset, length ->
            endpoint.onReceive(from, buffer, offset, length)
        }
        return endpoint
    }

    /** `net.set_conditions(...)`: reshapes one link mid-session. */
    public fun setConditions(peer: PeerId, conditions: NetConditions) {
        transport(peer).conditions = conditions
    }

    /** Applies [conditions] to every link, server included. */
    public fun setConditionsForAll(conditions: NetConditions) {
        for (link in links.values) link.conditions = conditions
    }

    /** Runs [ticks] ticks. Returns the tick the session now sits on. */
    public fun step(ticks: Int): Tick {
        require(ticks >= 0) { "ticks must be >= 0, was $ticks" }
        repeat(ticks) {
            clock.advance()
            for (link in links.values) link.flush()
            for (index in endpoints.indices) {
                links.getValue(endpoints[index].peer.raw).poll(receivers[index])
            }
            for (index in endpoints.indices) endpoints[index].onTick(clock.tick)
        }
        return clock.tick
    }

    /** Total datagram buffers allocated across the session. Flat once every link is warm. */
    public fun allocatedDatagrams(): Int =
        network.allocatedDatagrams + links.values.sumOf { it.allocatedDatagrams }

    /** Closes every link and drops undelivered traffic. */
    public fun close() {
        for (link in links.values) link.close()
    }

    public companion object {

        /** An arbitrary but fixed default, so an unseeded harness is still reproducible. */
        public const val DEFAULT_SEED: Long = 0x5EEDL
    }
}
