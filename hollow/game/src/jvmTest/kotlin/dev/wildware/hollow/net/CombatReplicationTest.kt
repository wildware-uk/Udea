package dev.wildware.hollow.net

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.hollow.Fox
import dev.wildware.hollow.FoxWaves
import dev.wildware.hollow.HollowCombat
import dev.wildware.hollow.Player
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.Ticks
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.CoreModule
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
 * The owner's added acceptance criterion for issue #252 (see #245): a server and two clients in one
 * process, and **this ticket's feature agrees on every machine** - every fighter's hit points and
 * every player's three cooldowns, on the same tick.
 *
 * ## The shape, and why it is the strong one
 *
 * `FoxReplicationTest`'s, and for its reason. The server records what its world held at the end of
 * every tick; each client is then compared against the record **for the tick that client was last
 * told about**, never against now. A client holds a copy of a tick the server has already left, and
 * it has to be an exact copy of *that* tick.
 *
 * ## A fight, not two people standing still
 *
 * Both clients hold the attack control down for the whole run, so every swing that can fire does.
 * A wave arrives, the foxes close in and bite, the players swing back, foxes die and are taken away.
 * The test refuses to pass unless it saw all of that: hit points that moved on both sides, cooldowns
 * that were counting on the wire, and at least one fighter killed. Agreement between two worlds where
 * nothing happens is agreement about nothing.
 *
 * ## What a client is sent, and what it is not
 *
 * Hit points cross as `udea-gas`'s `Attributes`, whose codec sends `base` alone - `HollowNet` adds
 * it to the registry for exactly this. A cooldown does **not** cross as an effect: `GameplayEffects`
 * sends nothing at all, so `Player.attackReady`, `dashReady` and `healReady` are the `@Net` copies a
 * client's HUD counts down from, and this is what holds them equal to the server's.
 */
class CombatReplicationTest {

    private val harness = NetHarness(clients = 2, initialConditions = NetConditions(latencyTicks = LATENCY))
    private val server = HollowServer(harness.transport(PeerId.SERVER), waves = WAVES, matchSeed = SEED)
    private val characters = ArrayList<NetId>()
    private val clients = harness.clientPeers().map { peer ->
        characters += server.addClient(peer)
        HollowClient(peer, harness.transport(peer))
    }

    /** What the server's world held at the end of each tick, by tick. */
    private val history = HashMap<Long, Map<Int, FighterView>>()

    init {
        harness.register(
            object : NetEndpoint {
                override val peer: PeerId = PeerId.SERVER

                override fun onReceive(from: PeerId, buffer: ByteArray, offset: Int, length: Int) {
                    server.onPacket(from, buffer, offset, length)
                }

                override fun onTick(tick: Tick) {
                    server.tick()
                    history[server.tick.value] = fighters(
                        server.host.world,
                        server.host.ctx[CoreModule.NET_IDS],
                        server.host.ctx[HollowCombat.KEY],
                    )
                }
            },
        )
        for (client in clients) {
            harness.register(
                object : NetEndpoint {
                    override val peer: PeerId = client.peer

                    // Attack held for the whole run: a swing fires every time the cooldown allows.
                    override fun onTick(tick: Tick) {
                        client.tick(tick, client.command(tick, attack = true))
                    }

                    override fun onReceive(from: PeerId, buffer: ByteArray, offset: Int, length: Int) {
                        client.onPacket(buffer, offset, length)
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
    fun `every fighter's health and every cooldown agree on the server and on both clients, tick for tick`() {
        var compared = 0
        var sawFoxHurt = false
        var sawPlayerHurt = false
        var sawCooldownRunning = false
        val everKilled = HashSet<Int>()

        for (checkpoint in 0 until CHECKPOINTS) {
            harness.step(BETWEEN)
            for (client in clients) {
                // What this client was last told, on its world now: the applier is what a frame
                // drawn at this moment would show.
                client.applier.apply(client.replication.world)
                val told = client.serverTick.value
                val expected = checkNotNull(history[told]) { "the server never recorded tick $told" }
                val seen = fighters(
                    client.host.world,
                    client.host.ctx[CoreModule.NET_IDS],
                    client.host.ctx[HollowCombat.KEY],
                )
                assertEquals(expected, seen, "${client.peer} disagrees with the server about the fight at tick $told")
                compared += seen.size
                sawFoxHurt = sawFoxHurt || seen.values.any { it.isFox && it.health < FULL }
                sawPlayerHurt = sawPlayerHurt || seen.values.any { !it.isFox && it.health < FULL }
                sawCooldownRunning = sawCooldownRunning ||
                    seen.values.any { !it.isFox && it.attackReady > told }
            }
            // A fighter that has stopped existing on the server has been killed and taken away.
            val live = history.getValue(server.tick.value).keys
            everKilled += history.values.flatMap { it.keys }.toSet() - live
        }

        assertTrue(compared >= MINIMUM_COMPARED, "only $compared fighter observations were compared")
        assertTrue(sawFoxHurt, "no client ever held a hurt fox, so damage was never compared")
        assertTrue(sawPlayerHurt, "no client ever held a bitten player, so the bite was never compared")
        assertTrue(sawCooldownRunning, "no client ever held a running cooldown, so the ready ticks were never compared")
        assertTrue(everKilled.isNotEmpty(), "nothing was killed, so death was never compared")
    }

    /**
     * Every fighter in [world] by raw `NetId`, as every number of it this ticket puts on the wire.
     *
     * [combat] is that world's own [HollowCombat]: health is an attribute, read by an id its table
     * hands out, and the server's table and each client's are separate objects.
     */
    private fun fighters(world: World, ids: NetIdIndex, combat: HollowCombat): Map<Int, FighterView> {
        val out = HashMap<Int, FighterView>()
        world.family { all(Attributes) }.forEach { entity ->
            val player = entity.getOrNull(Player)
            out[ids.netIdOf(entity).raw] = FighterView(
                isFox = entity has Fox,
                health = entity[Attributes].base(combat.health),
                attackReady = player?.attackReady?.value ?: -1L,
                dashReady = player?.dashReady?.value ?: -1L,
                healReady = player?.healReady?.value ?: -1L,
            )
        }
        return out
    }

    /** One fighter as a client sees it: health and the three ready ticks, `-1` for a fox. */
    private data class FighterView(
        val isFox: Boolean,
        val health: Float,
        val attackReady: Long,
        val dashReady: Long,
        val healReady: Long,
    )

    private companion object {
        /** Nine ticks each way: 150ms, the link `FoxReplicationTest` uses. */
        const val LATENCY = 9

        const val SEED = 252L

        /** Every fighter starts here, players and foxes alike. */
        const val FULL = 100f

        /** A first wave a second in, and another five seconds later: four foxes, then six. */
        val WAVES = FoxWaves(first = Tick(60L), every = Ticks(300L), size = 4, growth = 2, cap = 24)

        /** Ticks between one comparison and the next: a little over a second. */
        const val BETWEEN = 70

        /** Fifteen comparisons, about eighteen seconds: long enough to kill foxes with a 36-tick swing. */
        const val CHECKPOINTS = 15

        /** Two players and at least one fox on each client at most checkpoints. */
        const val MINIMUM_COMPARED = 60
    }
}
