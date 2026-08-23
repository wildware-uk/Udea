package dev.wildware.moba.net

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.loop.barrier
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.SnapshotService
import dev.wildware.udea.core.snapshot.SnapshotTimeTravel
import dev.wildware.udea.core.snapshot.WorldSnapshot
import dev.wildware.udea.net.input.MoveInput
import dev.wildware.udea.net.replication.ReplicationClient
import dev.wildware.udea.net.transport.LoopbackNetwork
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.transport.Transport
import dev.wildware.udea.net.wire.ProtocolDescriptor
import dev.wildware.moba.MobaGame

/**
 * A connected `moba` client: it sends input, and it renders a world it did not simulate.
 *
 * ## What it holds
 *
 * A real [GameHost] over the identical [MobaGame.definition] the server runs, and therefore a
 * real Fleks world, the real `NetIdIndex`, and every renderer `MobaScene` registers. What it
 * does **not** hold is a copy of the level: the client seeds no scene at all. Every entity in
 * its world arrived over the wire and was put there by [ReplicaApplier]. That is the honest
 * demonstration - if replication stopped, the client's screen would be empty rather than
 * quietly running its own game.
 *
 * ## Why the client does not step the simulation
 *
 * `moba` has no authority gating anywhere in it: no system in the game asks
 * `GameContext.role.isAuthoritative`. Stepping the shipped module list on a client therefore
 * runs `UnitBattleSystem`, the ability execs, `RespawnSystem` **and** `MatchSystem` - and
 * `MatchSystem.restart` calls `ctx.scenes.requestScene`, which tears the world down and
 * repopulates it from the level on the client's own schedule. A client that rebuilt the level
 * out from under the state it was being sent is worse than one that renders a little less.
 *
 * So the loop here is receive, apply, send, and the world is a pure replicated view. Two things
 * follow and both are real costs, not oversights:
 *
 *  - **the client's `SimClock` never advances.** `MobaScene` computes an animation playhead as
 *    `clock.tick - CharacterView.startTick`, so poses do not play on a client. `SimClock.moveTo`
 *    is `internal` to `udea-core`, so a client cannot follow the server's tick without either
 *    stepping or a widening of that API. [serverTick] is the value it would be given.
 *  - **there is no prediction and no reconciliation.** The client's own input is not applied
 *    locally, so a player sees their own movement a round trip late. The pieces prediction needs
 *    - a command ring with sequence numbers, a dense snapshot ring, a per-tick baseline - are all
 *    present and none of them is joined up here.
 *
 * Closing either one means a client-side module list that omits the authoritative systems, which
 * is a change to `MobaGame.definition`'s shape and not to this file.
 */
public class MobaClientSession(

    /** Which client this is, from the server's point of view. */
    public val peer: PeerId,

    /** Where datagrams go and come from. Any [Transport]. */
    private val transport: Transport,

    /**
     * The host this client renders through.
     *
     * Defaulted to a headless one so a proof or a test needs no GL context; `MobaClient` passes
     * the windowed host its LWJGL3 backend built, and nothing else about this class changes.
     */
    public val host: GameHost = MobaGame.host(RenderMode.Headless),

    /** Largest datagram this client will build. Must match the server's. */
    private val mtu: Int = LoopbackNetwork.DEFAULT_MTU,
) : AutoCloseable {

    private val travel: SnapshotTimeTravel = checkNotNull(
        host.game.simulation.travel as? SnapshotTimeTravel,
    ) {
        "the client reads its component registry off the definition's snapshot ring, and this " +
            "host was built without one"
    }

    /** The component types this client speaks. The same list the server built its protocol from. */
    public val registry: ComponentRegistry get() = travel.ring.registry

    /** This build's protocol. A server on another build is refused by name, not ignored. */
    public val protocol: ProtocolDescriptor = MobaNet.protocol(registry)

    /** The receiving half: applies deltas, acknowledges them, sends input. */
    public val replication: ReplicationClient =
        ReplicationClient(peer, registry, protocol, transport, mtu = mtu)

    private val netIds: NetIdIndex = host.ctx[CoreModule.NET_IDS]

    /** Moves what arrived off the wire onto live Fleks components. */
    public val applier: ReplicaApplier = ReplicaApplier(
        registry = registry,
        world = host.world,
        netIds = netIds,
        barrier = host.ctx.barrier,
        ctx = host.ctx,
    )

    /**
     * Captures this client's own world, for an agreement check against the server's.
     *
     * Built over the client's world with the same registry the server captures with, so the two
     * captures are directly comparable - see [NetStateProbe] for why the comparison is over the
     * `@Net` set rather than the whole capture.
     */
    private val capture: SnapshotService =
        SnapshotService(registry, host.world, host.ctx, netIds)

    /** The client's Fleks world. What the renderer draws. */
    public val world: World get() = host.world

    /** Newest server tick this client holds state for. */
    public val serverTick: Tick get() = replication.serverTick

    /** Packets applied. Zero means nothing has arrived yet, not that nothing changed. */
    public val applied: Long get() = replication.applied

    /** Packets discarded as older than, or a duplicate of, one already applied. */
    public val staleDropped: Long get() = replication.staleDropped

    /** Commands this client has produced. Also the next sequence number. */
    private var producedSeq: Int = 0

    /** Feeds one received datagram in. */
    public fun onPacket(buffer: ByteArray, offset: Int, length: Int) {
        replication.onPacket(buffer, offset, length)
    }

    /**
     * Applies everything received since the last call, then sends this tick's input.
     *
     * Apply before send, so the ack that rides the outgoing packet names state this client
     * genuinely holds. `ReplicationClient` will only send on an input tick (30Hz against a 60Hz
     * simulation, spec 3.3), and answers zero on the others.
     *
     * @return the bytes sent, or zero on a tick that sends nothing.
     */
    public fun tick(tick: Tick, command: MoveInput? = null): Int {
        applier.apply(replication.world)
        if (command != null) replication.pushInput(command)
        return replication.sendTick(tick)
    }

    /** Mints the next command for [tick] from an axis and a button field. */
    public fun command(
        tick: Tick,
        moveX: Float = 0f,
        moveY: Float = 0f,
        aim: Float = 0f,
        buttons: Int = 0,
    ): MoveInput {
        val seq = producedSeq
        producedSeq = (producedSeq + 1) and SEQ_MASK
        return MoveInput(seq, tick, moveX, moveY, aim, buttons)
    }

    /** This client's world, captured. Comparable with `MobaHostSession.stateAt`. */
    public fun state(): WorldSnapshot = capture.capture()

    /** How many `GameUnit`s this client can see. */
    public fun unitCount(): Int = NetStateProbe.unitCount(host.world)

    override fun close() {
        host.stop()
        transport.close()
    }

    override fun toString(): String =
        "MobaClientSession($peer, serverTick=$serverTick, applied=$applied, $applier)"

    private companion object {

        /** 16-bit command sequence, matching `MoveInput`'s wire width. */
        const val SEQ_MASK: Int = 0xFFFF
    }
}
