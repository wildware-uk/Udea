package dev.wildware.hollow.net

import dev.wildware.hollow.HollowGame
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.loop.barrier
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.SnapshotTimeTravel
import dev.wildware.udea.net.replication.ReplicationClient
import dev.wildware.udea.net.transport.LoopbackNetwork
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.transport.Transport
import dev.wildware.udea.net.wire.ProtocolDescriptor

/**
 * One Hollow client: the level loaded locally, and the server's world replicated onto it over
 * [transport] (issue #249). `MobaClientSession` is moba's, with prediction and input Hollow does not
 * have yet.
 *
 * The client loads [level] itself - the static clearing every machine ships with, see [HollowNet] -
 * and from then on [HollowReplicaApplier] makes the world agree with what the server sends: an
 * entity the server has and this client does not is created under the server's `NetId`, one the
 * server spawned and then destroyed goes, and every replicated field is written.
 *
 * @param host the client's game. Headless for a test; the window's host for a player, which is then
 *   drawn by the same scene the standalone game uses.
 */
public class HollowClient(
    public val peer: PeerId,
    private val transport: Transport,
    public val host: GameHost = HollowGame.host(RenderMode.Headless),
    mtu: Int = LoopbackNetwork.DEFAULT_MTU,
) : AutoCloseable {

    init {
        HollowGame.seed(host)
    }

    private val travel: SnapshotTimeTravel = checkNotNull(host.game.simulation.travel as? SnapshotTimeTravel) {
        "the client reads its component registry off the definition's snapshot ring, and this host has none"
    }

    /** What replicates: the same registry the server built, from the same sources. */
    public val registry: ComponentRegistry get() = travel.ring.registry

    /** This build's protocol. A server whose protocol differs is refused at connect. */
    public val protocol: ProtocolDescriptor = HollowNet.protocol(registry)

    /** The replication half: the replica store, acknowledgements and the wire. */
    public val replication: ReplicationClient = ReplicationClient(peer, registry, protocol, transport, mtu = mtu)

    /** What puts the replica store onto the world. */
    public val applier: HollowReplicaApplier = HollowReplicaApplier(
        registry = registry,
        world = host.world,
        netIds = host.ctx[CoreModule.NET_IDS],
        barrier = host.ctx.barrier,
        ctx = host.ctx,
    )

    /** The newest server tick this client has a snapshot of. */
    public val serverTick: Tick get() = replication.serverTick

    /** A datagram from the server. */
    public fun onPacket(buffer: ByteArray, offset: Int, length: Int) {
        replication.onPacket(buffer, offset, length)
    }

    /**
     * Applies what has arrived to the world, then sends this tick's acknowledgement.
     *
     * @return datagrams sent.
     */
    public fun tick(tick: Tick): Int {
        applier.apply(replication.world)
        return replication.sendTick(tick)
    }

    override fun close() {
        host.stop()
        transport.close()
    }

    override fun toString(): String = "HollowClient($peer, serverTick=$serverTick, $applier)"
}
