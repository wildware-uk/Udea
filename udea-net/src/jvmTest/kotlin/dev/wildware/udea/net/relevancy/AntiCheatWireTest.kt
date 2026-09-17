package dev.wildware.udea.net.relevancy

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.net.harness.MoverReplicator
import dev.wildware.udea.net.harness.NetTestWorld
import dev.wildware.udea.net.replication.ReplicationClient
import dev.wildware.udea.net.replication.ReplicationServer
import dev.wildware.udea.net.transport.DatagramSink
import dev.wildware.udea.net.transport.LoopbackNetwork
import dev.wildware.udea.net.transport.ManualClock
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.transport.Transport
import dev.wildware.udea.net.transport.TransportStats
import dev.wildware.udea.net.wire.ProtocolDescriptor
import dev.wildware.udea.net.wire.ReplicaStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The checked-in anti-cheat regression: the server never serialises a field the client may not
 * see, so a packet sniffer finds nothing.
 *
 * ## Why this is asserted at the byte level and not against the client's world
 *
 * "The client does not hold the entity" is the weaker claim, and it is the one a client-side fog
 * implementation also satisfies — which is exactly the design spec 3 rejects, because the data
 * is then in the process and a maphack is a UI patch. So this test does both: it decodes the
 * datagrams through the real [ReplicationClient] and asserts the row is absent, *and* it sweeps
 * every raw datagram at every bit alignment for the hidden unit's coordinate word and asserts it
 * is nowhere in the bytes.
 *
 * ## The control arm matters as much as the assertion
 *
 * [theSweepFindsAVisibleUnitsCoordinates] runs the identical sweep for a unit the client *may*
 * see and requires a hit. Without it the hidden-unit sweep could be passing because the sweep is
 * broken, or because nothing at all was sent, which is the classic way an anti-cheat assertion
 * quietly stops asserting.
 */
class AntiCheatWireTest {

    @Test
    fun `a hidden unit never reaches the wire`() {
        val session = FogWireSession()
        session.run(TICKS)

        assertEquals(
            ReplicaStore.ABSENT,
            session.client.world.rowOf(session.enemy),
            "the client decoded a row for an entity its team cannot see",
        )
        for (value in session.enemyCoordinates) {
            assertFalse(
                session.sniff(value),
                "the hidden unit's x of $value was found in a datagram; the server serialised " +
                    "state the client may not have",
            )
        }
        assertTrue(session.sniffedPackets > 0, "nothing was sent at all, so the sweep proves nothing")
    }

    @Test
    fun `the sweep finds a visible unit's coordinates`() {
        val session = FogWireSession()
        session.run(TICKS)

        assertTrue(
            session.allyCoordinates.any { session.sniff(it) },
            "the sweep found none of the visible ally's coordinates either, so it proves nothing",
        )
        assertTrue(
            session.client.world.rowOf(session.ally) != ReplicaStore.ABSENT,
            "the client must hold the ally it is allowed to see",
        )
    }

    @Test
    fun `a unit that walks into vision starts being sent, and only then`() {
        val session = FogWireSession()
        session.run(TICKS)
        assertEquals(ReplicaStore.ABSENT, session.client.world.rowOf(session.enemy))

        session.enemyVisible = true
        session.enemyCoordinates.clear()
        session.run(TICKS)

        assertTrue(
            session.client.world.rowOf(session.enemy) != ReplicaStore.ABSENT,
            "once it is in vision the client must receive it, or fog is just breakage",
        )
        assertTrue(
            session.enemyCoordinates.any { session.sniff(it) },
            "and its coordinates must now be on the wire",
        )
    }

    private companion object {

        /** Long enough for every entity to win the priority sort several times over. */
        const val TICKS: Int = 40
    }
}

/**
 * A real server, a real client, a real ring and a real loopback link, with every server datagram
 * kept for inspection.
 *
 * Nothing here is a double for the replication path: the packets are assembled by the shipped
 * [ReplicationServer] against the same [dev.wildware.udea.core.snapshot.SnapshotRing] that backs
 * rewind, and decoded by the shipped [ReplicationClient]. The only test-owned piece is
 * [SniffedTransport], which copies bytes on their way past and changes nothing.
 */
internal class FogWireSession {

    val world: NetTestWorld = NetTestWorld()
    private val protocol = ProtocolDescriptor.of(world.registry)
    private val network = LoopbackNetwork(ManualClock())
    private val sniffed = SniffedTransport(network.transportFor(PeerId.SERVER))

    /** The viewing client. On blue. */
    val viewer: PeerId = PeerId.client(1)

    val fog: FogOfWar = FogOfWar(
        grid = VisionGrid(originX = 0f, originY = 0f, cellSize = 16f, columns = 64, rows = 64),
        teams = 2,
        capacity = 512,
    )

    val server: ReplicationServer = ReplicationServer(
        registry = world.registry,
        protocol = protocol,
        transport = sniffed,
        ring = world.ring,
        relevancy = fog,
    )

    val client: ReplicationClient =
        ReplicationClient(viewer, world.registry, protocol, network.transportFor(viewer))

    /** Blue's own unit, and the only vision source in the world. */
    val ally: NetId = world.spawn(x = 20f, y = 20f, teamId = BLUE)

    /** Red's unit, parked far outside blue's sight. */
    val enemy: NetId = world.spawn(x = 700f, y = 700f, teamId = RED)

    /** Set true to walk the enemy into sight. */
    var enemyVisible: Boolean = false

    /**
     * Set true to black-hole every server-to-client datagram.
     *
     * The packets are still *built* and still recorded by [SniffedTransport], so the server's
     * state machine advances exactly as it would on a lossy link; they simply never arrive and
     * are therefore never acked. That is the condition a removal has to survive.
     */
    var dropServerToClient: Boolean = false

    /** Every x the ally has held. */
    val allyCoordinates: MutableList<Float> = mutableListOf()

    /** Every x the enemy has held. */
    val enemyCoordinates: MutableList<Float> = mutableListOf()

    private var step = 0

    init {
        server.addClient(viewer)
        fog.assign(viewer, BLUE)
    }

    /** Runs [ticks] whole ticks: move, capture, solve fog, send, deliver, ack. */
    fun run(ticks: Int) {
        repeat(ticks) {
            step++
            move()
            val snapshot = world.captureTick()
            solveFog(snapshot.tick)
            server.send(viewer, snapshot)
            network.transportFor(viewer).poll(DatagramSink { _, buffer, offset, length ->
                if (!dropServerToClient) client.onPacket(buffer, offset, length)
            })
            client.sendTick(snapshot.tick)
            sniffed.poll(DatagramSink { from, buffer, offset, length ->
                server.onPacket(from, buffer, offset, length)
            })
        }
    }

    /**
     * Whether [value]'s 32 bits appear anywhere in any server datagram, at any bit alignment.
     *
     * Alignment-agnostic and bit-order-agnostic on purpose: the section is bit-packed, so a
     * float rarely lands on a byte boundary, and a sweep that only checked aligned bytes would
     * miss almost every real leak while looking thorough.
     */
    fun sniff(value: Float): Boolean {
        val word = value.toRawBits()
        return sniffed.sent.any { containsWord(it, word) }
    }

    /** How many datagrams the server has put on the wire. */
    val sniffedPackets: Int get() = sniffed.sent.size

    /** The x the *client* holds for [netId], decoded out of its own store. */
    fun clientX(netId: NetId): Float {
        val row = client.world.rowOf(netId)
        check(row != ReplicaStore.ABSENT) { "the client holds no row for $netId" }
        val component = client.world.registry.indexOf(MoverReplicator.typeId)
        val slot = client.world.slotOf(row, component)
        check(slot != ReplicaStore.ABSENT) { "the client's row for $netId carries no Mover" }
        return client.world.storeAt(component).getFloat(slot, MoverReplicator.X)
    }

    private fun move() {
        val allyMover = world.mover(ally)
        allyMover.x = 20f + step * ALLY_STEP
        val enemyMover = world.mover(enemy)
        if (enemyVisible) {
            enemyMover.x = allyMover.x + IN_SIGHT_OFFSET
            enemyMover.y = allyMover.y
        } else {
            enemyMover.x = 700f + step * ENEMY_STEP
            enemyMover.y = 700f
        }
        allyCoordinates += allyMover.x
        enemyCoordinates += enemyMover.x
    }

    private fun solveFog(tick: dev.wildware.udea.core.Tick) {
        fog.beginSolve(tick)
        for (netId in listOf(ally, enemy)) {
            val mover = world.mover(netId)
            val sight = if (netId == ally) ALLY_SIGHT else 0f
            fog.observe(netId, mover.x, mover.y, mover.teamId, sight)
        }
        fog.endSolve()
    }

    private companion object {

        const val BLUE: Int = 0
        const val RED: Int = 1

        /** Wide enough to cover the ally's own patch and nothing across the map. */
        const val ALLY_SIGHT: Float = 40f

        const val ALLY_STEP: Float = 0.5f
        const val ENEMY_STEP: Float = 1.5f

        /** Well inside [ALLY_SIGHT], so the visible case is not itself sitting on a boundary. */
        const val IN_SIGHT_OFFSET: Float = 5f

        /** True when [word]'s bits appear at some alignment, read either way round. */
        fun containsWord(bytes: ByteArray, word: Int): Boolean {
            val bits = bytes.size * 8
            for (start in 0..bits - Int.SIZE_BITS) {
                var lsbFirst = 0
                var msbFirst = 0
                for (offset in 0 until Int.SIZE_BITS) {
                    val at = start + offset
                    val bit = (bytes[at ushr 3].toInt() ushr (at and 7)) and 1
                    lsbFirst = lsbFirst or (bit shl offset)
                    msbFirst = (msbFirst shl 1) or bit
                }
                if (lsbFirst == word || msbFirst == word) return true
            }
            return false
        }
    }
}

/** A [Transport] that keeps a copy of everything it sends, and otherwise does nothing at all. */
internal class SniffedTransport(private val inner: Transport) : Transport {

    /** Every datagram this endpoint has put on the wire, in order. */
    val sent: MutableList<ByteArray> = mutableListOf()

    override val localPeer: PeerId get() = inner.localPeer

    override fun send(peer: PeerId, bytes: ByteArray, offset: Int, length: Int) {
        sent += bytes.copyOfRange(offset, offset + length)
        inner.send(peer, bytes, offset, length)
    }

    override fun poll(sink: DatagramSink): Int = inner.poll(sink)

    override fun stats(peer: PeerId): TransportStats = inner.stats(peer)

    override fun close(): Unit = inner.close()
}
