package dev.wildware.udea.net.transport

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.net.harness.MoverReplicator
import dev.wildware.udea.net.harness.NetTestComponents
import dev.wildware.udea.net.replication.ReplicationClient
import dev.wildware.udea.net.wire.ProtocolDescriptor
import dev.wildware.udea.net.wire.ReplicaStore

/**
 * What a WebSocket snapshot server sends and what a client on any target checks it received
 * (issue #209).
 *
 * In common test code because the two ends of the headline test run on different targets: the
 * server is a JVM process and the client is Wasm on Node. They share no heap and no clock, so the
 * only way the client can know what the server sent is for both to compute it from the same
 * function of the server tick. A replica that matches [moverX] and [moverY] at the tick the client
 * holds can only have come off the socket.
 */
internal object WebSocketSnapshotScenario {

    /** Entities the server spawns, holding net id indices `0 until ENTITIES` on a fresh index. */
    const val ENTITIES: Int = 3

    /**
     * Snapshots a client must have applied before its replica counts as a stream rather than one
     * lucky full state. Well past the first full send, so later ones are deltas against baselines
     * the client acknowledged back over the same socket.
     */
    const val MIN_APPLIED: Long = 30L

    /** The environment variable the Wasm test reads the server's URL from. */
    const val URL_ENVIRONMENT_VARIABLE: String = "UDEA_WS_SNAPSHOT_URL"

    /** The path the snapshot server serves its WebSocket endpoint on. */
    const val PATH: String = "/udea"

    /** The component set both ends speak. */
    fun registry(): ComponentRegistry = NetTestComponents.registry()

    /** This scenario's protocol, as both ends derive it from [registry]. */
    fun protocol(): ProtocolDescriptor = ProtocolDescriptor.of(registry())

    /** Where entity [index] is on the x axis in the snapshot captured at [tick]. */
    fun moverX(index: Int, tick: Tick): Float = index * 10f + tick.value * 0.5f

    /** Where entity [index] is on the y axis. Constant, so a y that moved is a wrong field. */
    fun moverY(index: Int): Float = -4f * (index + 1)

    /**
     * Why [client]'s replica does not yet show the scenario at the tick it holds, or null when it
     * does.
     *
     * A reason rather than a Boolean so a test that times out says which entity or which field
     * was wrong.
     */
    fun mismatch(client: ReplicationClient): String? {
        if (client.applied < MIN_APPLIED) return "only ${client.applied} snapshot(s) applied"
        val tick = client.serverTick
        val live = client.world.liveNetIds().associateBy { it.index }
        if (live.size != ENTITIES) return "the replica holds ${live.size} entities at $tick, expected $ENTITIES"
        val moverColumn = client.registry.indexOf(MoverReplicator.typeId)
        val store = client.world.storeAt(moverColumn)
        for (index in 0 until ENTITIES) {
            val netId = live[index] ?: return "no entity with net id index $index at $tick"
            val slot = client.world.slotOf(client.world.rowOf(netId), moverColumn)
            if (slot == ReplicaStore.ABSENT) return "$netId has no Mover at $tick"
            val x = store.getFloat(slot, MoverReplicator.X)
            val y = store.getFloat(slot, MoverReplicator.Y)
            if (x != moverX(index, tick) || y != moverY(index)) {
                return "$netId is at ($x, $y) at $tick, expected (${moverX(index, tick)}, ${moverY(index)})"
            }
        }
        return null
    }
}
