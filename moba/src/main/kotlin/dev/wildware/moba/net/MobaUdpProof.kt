package dev.wildware.moba.net

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.rng.SimRandom
import dev.wildware.udea.net.transport.ConnectionSecret
import dev.wildware.udea.net.transport.DatagramSink
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.transport.TransportStats
import dev.wildware.udea.net.transport.NetConditions
import dev.wildware.udea.net.transport.PacketLog
import dev.wildware.udea.net.transport.SimulatedTransport
import dev.wildware.udea.net.transport.ManualClock
import dev.wildware.udea.net.transport.Transport
import dev.wildware.udea.net.transport.UdpConfig
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * What the `moba` UDP proof processes agree on without talking to each other first.
 *
 * The point of a two-process demo is that the socket is the **only** channel between them. So
 * the component registry, the protocol hash derived from it, the connect key and the tick rate
 * all have to be things each side computes or holds independently, exactly as a shipped client
 * and a shipped server would. Nothing here is passed between the processes except a port number
 * on a command line.
 */
public object MobaUdpProof {

    /**
     * Datagram ceiling, and the packer's budget.
     *
     * Larger than a link's 1200-byte MTU on purpose: `UdpTransport` fragments anything over the
     * wire MTU and reassembles it, and a whole 27-unit tick has to fit in one *message* for a
     * peer-to-peer hash to be a question with a yes-or-no answer (see `MobaNetProof`). So this
     * exercises the fragment path as well as the replication path, and a lost fragment loses the
     * whole message - which is exactly what a real link does to a big snapshot.
     */
    public const val MTU: Int = 16384

    /** 60Hz, the engine's simulation rate. */
    public const val TICK_NANOS: Long = 16_666_667L

    /** Root seed for the impairment draws, so a failure at 5% loss is a seed and not a story. */
    public const val SEED: Long = 20_260_823L

    /**
     * The server's connect key.
     *
     * Fixed bytes in a proof harness, and nowhere near a shipped server: the whole token scheme
     * rests on this being unguessable. `ConnectionSecret` takes its key from the caller precisely
     * so that decision belongs to a deployment rather than to a transport.
     */
    public fun secret(): ConnectionSecret =
        ConnectionSecret(ByteArray(ConnectionSecret.MIN_KEY_BYTES) { (it * 7 + 3).toByte() })

    /** Short timeouts, so "kill one end and watch the other notice" is seconds and not minutes. */
    public fun config(): UdpConfig = UdpConfig(
        maxClients = 4,
        timeoutTicks = 180L,
        keepAliveIntervalTicks = 6L,
        connectTimeoutTicks = 600L,
    )

    public fun loopback(port: Int): InetSocketAddress =
        InetSocketAddress(InetAddress.getLoopbackAddress(), port)

    /**
     * Conditions named on the command line: `perfect`, or `lossy` for 150ms and 5% loss.
     *
     * Impairment is applied by a [SimulatedTransport] wrapped **around** the real
     * [dev.wildware.udea.net.transport.UdpTransport], not instead of it. Every byte still crosses
     * a real socket between two operating-system processes; what the wrapper adds is the delay
     * and the drops that a loopback interface will not produce on demand. Saying so plainly
     * matters: this is real UDP with a reproducible impairment in front of it, and it is not a
     * claim about a real wide-area link.
     */
    public fun conditionsOf(name: String): NetConditions = when (name) {
        "perfect" -> NetConditions.PERFECT
        "lossy" -> NetConditions(latencyTicks = 9, lossChance = 0.05f)
        else -> throw IllegalArgumentException("unknown link name '$name'; use perfect or lossy")
    }

    /** Wraps [transport] in the impairment for [conditions], or hands it back unchanged. */
    public fun impair(
        transport: Transport,
        clock: ManualClock,
        conditions: NetConditions,
        seed: Long,
    ): Transport = if (conditions == NetConditions.PERFECT) {
        transport
    } else {
        SimulatedTransport(transport, clock, SimRandom(seed), PacketLog(), MTU, conditions)
    }

    /** Releases whatever the impairment is holding, if there is one. */
    public fun flushImpairment(transport: Transport) {
        (transport as? SimulatedTransport)?.flush()
    }

    /**
     * Writes one line of a process's report and flushes it.
     *
     * The flush is not optional. A child's `System.out` is a pipe rather than a console, so it
     * buffers, and a parent waiting on a line sitting in a buffer waits for ever.
     */
    public fun say(line: String) {
        println(line)
        System.out.flush()
    }

    /**
     * The two components a client is **not** required to match the server on, and why.
     *
     * Both are named engine defects with a measurement behind them, not conveniences. Every other
     * replicated component is required to agree field for field, and `MobaUdpTwoProcessTest`
     * fails if one of them ever stops.
     *
     * `Combatant` - **the wire has no per-component removal op.** `moba` drops `Combatant` when a
     * unit dies and adds it back on respawn. `SnapshotSection.writeEntity` only ever walks the
     * components an entity *currently* has, so a component present in the baseline and absent
     * now produces no bytes at all. The client keeps it for ever. This one accumulates
     * monotonically: over 480 ticks of the real battle it grows from nothing to most of the
     * roster, and it is identical on a perfect link and a lossy one, which is what rules loss out.
     *
     * `CharacterView` - **a field that changed and changed back inside one acknowledgement window
     * is lost.** `ReplicationServer` delta-encodes each entity against the newest packet the
     * client has *acknowledged*; `ReplicationClient` merges the delta into its *current* state,
     * which is newer than the acked one for as long as an ack is in flight - which is always. A
     * field equal at the baseline tick and at the send tick, but different in between, is absent
     * from the mask, so the client keeps a value the server never held. Only oscillating fields
     * are affected, which in `moba` is `CharacterView.state` and `.flipX`; positions, health,
     * attributes and match state move monotonically inside a window and are always in the mask.
     * Re-pointing the server's baseline at the last *sent* tick makes every one of these
     * disappear on a clean link, which is the diagnosis - not the fix, since a lost packet then
     * makes the baseline a state the client never received. The fix is a per-tick client history
     * and a per-entity baseline tick on the wire.
     *
     * Neither exclusion may become permanent. This is the honest boundary of what today's
     * replication design can promise, written where it will be deleted when that changes.
     */
    public val EXCUSED_COMPONENTS: Set<String> = setOf("Combatant", "CharacterView")

    /**
     * One hash per replicated component, folded over the `GameUnit` roster only.
     *
     * A single whole-roster number answers "do they agree" and nothing else; when the answer is
     * no, this says *which component* disagrees, from two processes that cannot see each other's
     * heaps. It is the cross-process equivalent of [NetStateProbe.differences], and it is what
     * turned "replication is broken under loss" into three separately named defects.
     */
    public fun componentHashes(
        fields: dev.wildware.udea.core.snapshot.WorldFieldStore,
    ): Map<String, Long> = NetStateProbe.coveredComponents(fields.registry)
        .associateWith { name -> NetStateProbe.unitHash(fields) { it == name } }

    /** [componentHashes] as one transcript line: `name=0x...` pairs a parent process can parse. */
    public fun componentLine(fields: dev.wildware.udea.core.snapshot.WorldFieldStore): String =
        componentHashes(fields).entries.joinToString(" ") { "${it.key}=${hex(it.value)}" }

    /** Hex, sixteen digits, so two hashes line up in a transcript. */
    public fun hex(value: Long): String = "0x" + java.lang.Long.toHexString(value).padStart(16, '0')

    /** A slow left-right walk: the client's own input, going the only direction a client may send. */
    public fun walk(tick: Tick): Float = if ((tick.value / 30L) % 2L == 0L) 1f else -1f
}

/**
 * A [Transport] whose real destination is supplied after construction.
 *
 * Untying one knot and nothing else. `UdpTransport` must be told this build's `protoHash`, which
 * is derived from the component registry, which lives on the session - and the session is
 * constructed around a `Transport`. Rather than widen [MobaHostSession] to accept a transport
 * late, the session is handed this, and this is pointed at the socket once both exist. Nothing
 * is sent between the two moments: the loop has not started.
 *
 * Every method throws until [target] is set, so a send that happened before wiring would be a
 * crash rather than a silently dropped datagram.
 */
public class LateTransport : Transport {

    /** The real link. Set exactly once, before the loop runs. */
    public var target: Transport? = null

    private val live: Transport get() = checkNotNull(target) { "LateTransport was used before it was pointed at a link" }

    override val localPeer: PeerId get() = live.localPeer

    override fun send(peer: PeerId, bytes: ByteArray, offset: Int, length: Int): Unit =
        live.send(peer, bytes, offset, length)

    override fun poll(sink: DatagramSink): Int = live.poll(sink)

    override fun stats(peer: PeerId): TransportStats = live.stats(peer)

    override fun close() {
        target?.close()
    }
}
