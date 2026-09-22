package dev.wildware.hollow.net

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.hollow.Fox
import dev.wildware.hollow.FoxMode
import dev.wildware.hollow.FoxWaves
import dev.wildware.hollow.HollowCombat
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.Ticks
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.spatial.Animator
import dev.wildware.udea.core.spatial.Drawn
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.gas.Attributes
import dev.wildware.udea.net.transport.NetConditions
import dev.wildware.udea.net.transport.NetEndpoint
import dev.wildware.udea.net.transport.NetHarness
import dev.wildware.udea.net.transport.PeerId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #251 with a server and two clients: every fox agrees on every machine - where it is, which
 * way it faces, which state it is in, whom it is after, how hurt it is, which clip it plays and
 * which model it draws.
 *
 * One process, one thread, no socket and no sleep - a headless authoritative [HollowServer] and two
 * headless [HollowClient]s over `udea-net`'s simulated network at 150ms each way, the shape
 * `PlayerReplicationTest` uses.
 *
 * ## Agreement at a tick, because foxes never stop
 *
 * `PlayerReplicationTest` asserts agreement once everything has stopped moving. Foxes do not stop:
 * a wandering fox chooses a new goal every few seconds for ever. So this test asserts the stronger
 * thing - **the same tick** - by recording what the server's world held at the end of every tick it
 * ran, and comparing each client's world against the record for the tick that client was last told
 * about. A client holds a copy of a tick the server has already left, and it must be an exact copy
 * of *that* tick, not of now.
 *
 * And it asserts it while something is happening: across the checkpoints the foxes compared must
 * include chasing ones and wandering ones, so agreement cannot be the trivial agreement of a pack
 * standing still at the edge.
 *
 * It is also what caught the server putting the clearing's props ahead of every fox for several
 * ticks at a time, which `HollowRelevancy` now prevents: with the props weighted like anything
 * else, a client told tick `t` held every fox where the server had it a few ticks before `t`.
 */
class FoxReplicationTest {

    private val harness = NetHarness(clients = 2, initialConditions = NetConditions(latencyTicks = LATENCY))
    private val server = HollowServer(harness.transport(PeerId.SERVER), waves = WAVES, matchSeed = SEED)
    private val characters = ArrayList<NetId>()
    private val clients = harness.clientPeers().map { peer ->
        characters += server.addClient(peer)
        HollowClient(peer, harness.transport(peer))
    }

    /** What the server's world held at the end of each tick, by tick. */
    private val history = HashMap<Long, Map<Int, FoxView>>()

    init {
        harness.register(
            object : NetEndpoint {
                override val peer: PeerId = PeerId.SERVER

                override fun onReceive(from: PeerId, buffer: ByteArray, offset: Int, length: Int) {
                    server.onPacket(from, buffer, offset, length)
                }

                override fun onTick(tick: Tick) {
                    server.tick()
                    history[server.tick.value] =
                        foxes(server.host.world, server.host.ctx[CoreModule.NET_IDS], server.host.ctx[HollowCombat.KEY])
                }
            },
        )
        for (client in clients) {
            harness.register(
                object : NetEndpoint {
                    override val peer: PeerId = client.peer

                    override fun onReceive(from: PeerId, buffer: ByteArray, offset: Int, length: Int) {
                        client.onPacket(buffer, offset, length)
                    }

                    // Nobody touches a key: the two players stand where they joined, in the middle
                    // of the clearing, and the foxes come to them.
                    override fun onTick(tick: Tick) {
                        client.tick(tick, client.command(tick))
                    }
                },
            )
        }
    }

    @AfterTest
    fun close() {
        clients.forEach(HollowClient::close)
        server.close()
        harness.close()
    }

    @Test
    fun `every fox is in the same place and the same state on the server and on both clients, tick for tick`() {
        var chasing = 0
        var wandering = 0
        var compared = 0
        for (checkpoint in 0 until CHECKPOINTS) {
            harness.step(BETWEEN)
            for (client in clients) {
                // What this client was last told, on the world, now: the applier is what a frame
                // drawn at this moment would show.
                client.applier.apply(client.replication.world)
                val told = client.serverTick.value
                val expected = checkNotNull(history[told]) { "the server never recorded tick $told" }
                val seen = foxes(client.host.world, client.host.ctx[CoreModule.NET_IDS], client.host.ctx[HollowCombat.KEY])
                assertEquals(expected, seen, "${client.peer} disagrees with the server about the foxes at tick $told")
                compared += seen.size
                chasing += seen.values.count { it.mode == FoxMode.Chase }
                wandering += seen.values.count { it.mode == FoxMode.Wander }
            }
        }

        // What makes the agreement worth having: there were foxes, and they were doing different
        // things - some had reached the players and were chasing, some had not.
        assertTrue(compared >= MINIMUM_COMPARED, "only $compared fox observations were compared across the checkpoints")
        assertTrue(chasing > 0, "no client ever held a chasing fox, so the chase was never compared")
        assertTrue(wandering > 0, "no client ever held a wandering fox, so the wander was never compared")
    }

    @Test
    fun `a fox a client holds chases one of the two players, and the same one the server says`() {
        harness.step(UNTIL_CHASING)

        val ids = server.host.ctx[CoreModule.NET_IDS]
        val serverCombat = server.host.ctx[HollowCombat.KEY]
        val targets = foxes(server.host.world, ids, serverCombat).values.filter { it.mode == FoxMode.Chase }.map { it.target }
        assertTrue(targets.isNotEmpty(), "no fox was chasing after $UNTIL_CHASING ticks")
        assertTrue(targets.all { NetId.ofRaw(it) in characters }, "a fox chased something that is not a player: $targets of $characters")

        for (client in clients) {
            client.applier.apply(client.replication.world)
            val expected = checkNotNull(history[client.serverTick.value])
            val seen = foxes(client.host.world, client.host.ctx[CoreModule.NET_IDS], client.host.ctx[HollowCombat.KEY])
            assertEquals(expected.mapValues { it.value.target }, seen.mapValues { it.value.target }, "${client.peer}'s foxes chase other players")
        }
    }

    /**
     * Every fox in [world] by raw `NetId`, as every replicated number of it.
     *
     * [combat] is that world's own [HollowCombat], because health is an attribute and an attribute
     * is read by an id the table hands out - the server's table and each client's are separate
     * objects built from the same names, so an id read off the wrong one would be a different field.
     */
    private fun foxes(world: World, ids: NetIdIndex, combat: HollowCombat): Map<Int, FoxView> {
        val out = HashMap<Int, FoxView>()
        world.family { all(Fox) }.forEach { entity ->
            val fox = entity[Fox]
            val at = entity[Transform3D]
            val animator = entity[Animator]
            out[ids.netIdOf(entity).raw] = FoxView(
                mode = fox.mode,
                target = fox.target.raw,
                health = entity.getOrNull(Attributes)?.base(combat.health),
                place = listOf(at.x, at.y, at.z, at.rotationX, at.rotationY, at.rotationZ, at.scaleX, at.scaleY, at.scaleZ),
                clip = listOf(animator.current.toString(), animator.previous.toString(), "fade=${animator.fadeLength} from ${animator.fadeStart}"),
                model = entity.getOrNull(Drawn)?.model,
            )
        }
        return out
    }

    /** One fox as a client sees it: the replicated fields, and nothing a client is never sent. */
    private data class FoxView(
        val mode: FoxMode,
        val target: Int,
        val health: Float?,
        val place: List<Float>,
        val clip: List<String>,
        val model: Int?,
    )

    private companion object {
        /** Nine ticks each way: 150ms, `PlayerReplicationTest`'s link. */
        const val LATENCY = 9

        const val SEED = 251L

        /** A first wave a second in, and a second wave five seconds after it. */
        val WAVES = FoxWaves(first = Tick(60L), every = Ticks(300L), size = 4, growth = 2, cap = 24)

        /** Ticks between one comparison and the next: a little over a second. */
        const val BETWEEN = 70

        /** Eleven comparisons, over about thirteen seconds: both waves arrive, walk in, and chase. */
        const val CHECKPOINTS = 11

        /**
         * Ticks until a fox of the first wave has walked from the edge into range of the players.
         *
         * Thirteen metres out, eight of range, at the fox's walk: a little over three seconds, on
         * top of the first wave's second. Eight seconds is room for the pack's spread and a tree.
         */
        const val UNTIL_CHASING = 480

        /** At least one fox of the first wave on each client at most checkpoints. */
        const val MINIMUM_COMPARED = 40
    }
}
