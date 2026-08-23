package dev.wildware.moba.net

import dev.wildware.udea.core.Tick
import dev.wildware.udea.net.input.MoveInput
import dev.wildware.udea.net.replication.BandwidthBudget
import dev.wildware.udea.net.transport.LoopbackNetwork
import dev.wildware.udea.net.transport.NetConditions
import dev.wildware.udea.net.transport.NetEndpoint
import dev.wildware.udea.net.transport.NetHarness
import dev.wildware.udea.net.transport.PeerId

/**
 * A whole `moba` multiplayer session in one process, on one thread, with no sockets.
 *
 * This is Trello #8's host model and it is also the only arrangement in which the claim
 * "two clients agree with the server" can be *checked* rather than watched: [NetHarness] drives
 * the clock, so a 600-tick two-client run at 150ms and 5% loss finishes in milliseconds, has no
 * `Thread.sleep` anywhere in it, and produces the same bytes on every machine from the same
 * seed. A failure at 5% loss is a seed you can re-run, not something that happened once in CI.
 *
 * ## The seam, and why UDP needs nothing from this file
 *
 * [MobaHostSession] and [MobaClientSession] each take a
 * [dev.wildware.udea.net.transport.Transport] and know nothing else about the network. This
 * class is a *driver*: it happens to hand them the harness's simulated loopback links. A UDP
 * transport constructs the identical two classes with its own [dev.wildware.udea.net.transport.Transport]
 * and runs its own loop; not a line of the game or the replication path changes.
 *
 * ## The tick order is the harness's, not this class's
 *
 * ```
 * clock.advance()                   // T
 * every link .flush()               // release everything due at or before T
 * every endpoint .poll -> onReceive // consume what arrived
 * every endpoint .onTick(T)         // simulate and send; this send arrives at T + latency
 * ```
 *
 * Releasing before receiving is what gives latency a meaning, and sending last is what makes the
 * minimum round trip two ticks rather than zero. A driver that polled after sending would deliver
 * a zero-latency datagram inside the tick that produced it, and every agreement measured on it
 * would be measuring a network that cannot exist.
 */
public class MobaLoopbackSession(

    /** How many clients to stand up. Peers are `client(1)` through `client(n)`. */
    public val clientCount: Int,

    /** Root seed. Every link's loss, jitter and reorder draws come from it. */
    seed: Long = NetHarness.DEFAULT_SEED,

    /** Link conditions applied to every peer at construction. */
    conditions: NetConditions = NetConditions.PERFECT,

    /**
     * Largest datagram every link carries, and the packer's ceiling.
     *
     * 1200 - the real one - is the default. Raising it is what makes a peer-to-peer *hash*
     * comparison meaningful: at 1200 the packer defers whatever does not fit to the next tick, so
     * a client's world is a mix of server ticks by design and no two of the three would ever fold
     * to one number however healthy the session was.
     */
    public val mtu: Int = LoopbackNetwork.DEFAULT_MTU,

    /**
     * This tick's command for a client, or null for one that stands still.
     *
     * Returned rather than pushed, so there is exactly one place a command is minted and its
     * sequence number cannot be spent by a caller that then throws the command away.
     */
    private val input: (MobaClientSession, Tick) -> MoveInput? = { _, _ -> null },
) : AutoCloseable {

    init {
        require(clientCount >= 1) { "a session with no clients replicates to nobody" }
    }

    /** The in-memory network. Owns the clock, the packet log and every link. */
    public val harness: NetHarness =
        NetHarness(clientCount, seed, mtu = mtu, initialConditions = conditions)

    /** The authoritative server. */
    public val server: MobaHostSession =
        MobaHostSession(harness.transport(PeerId.SERVER), BandwidthBudget(mtu), mtu)

    /** The clients, in peer order. */
    public val clients: List<MobaClientSession> = (1..clientCount).map { index ->
        val peer = PeerId.client(index)
        server.addClient(peer)
        MobaClientSession(peer, harness.transport(peer), mtu = mtu)
    }

    init {
        check(clients.all { it.protocol.protoHash == server.protocol.protoHash }) {
            "the server and a client built different protocols from the same sources; " +
                "server=${server.protocol.protoHash}, " +
                "clients=${clients.map { it.protocol.protoHash }}"
        }
        harness.register(
            object : NetEndpoint {
                override val peer: PeerId = PeerId.SERVER

                override fun onReceive(from: PeerId, buffer: ByteArray, offset: Int, length: Int) {
                    server.onPacket(from, buffer, offset, length)
                }

                override fun onTick(tick: Tick) {
                    server.tick()
                }
            },
        )
        for (client in clients) {
            harness.register(
                object : NetEndpoint {
                    override val peer: PeerId = client.peer

                    override fun onReceive(
                        from: PeerId,
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {
                        client.onPacket(buffer, offset, length)
                    }

                    override fun onTick(tick: Tick) {
                        client.tick(tick, input(client, tick) ?: client.command(tick))
                    }
                },
            )
        }
    }

    /** The network tick the session sits on. Not the server's simulation tick. */
    public val tick: Tick get() = harness.clock.tick

    /** Runs [ticks] network ticks with the battle running. */
    public fun step(ticks: Int): Tick = harness.step(ticks)

    /**
     * Reshapes every link mid-session, the way `net.set_conditions` does.
     *
     * Applied to the server's link as well as the clients', so 150ms means 150ms each way rather
     * than only on the way back.
     */
    public fun setConditions(conditions: NetConditions) {
        harness.setConditionsForAll(conditions)
    }

    override fun close() {
        for (client in clients) client.close()
        server.close()
        harness.close()
    }

    override fun toString(): String =
        "MobaLoopbackSession(tick=$tick, clients=$clientCount, server=$server)"
}
