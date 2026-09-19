package dev.wildware.moba.net

import com.github.quillraven.fleks.World.Companion.family
import com.github.quillraven.fleks.Entity
import dev.wildware.moba.Player
import dev.wildware.moba.Position
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.net.transport.PeerId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Two humans, two champions, one server - and each window sees the other one move.**
 *
 * ## The defect
 *
 * `MobaHostSession` had one `Player` entity, one `IntentState` and a `drainInput` that consumed
 * every client's jitter buffer and kept exactly one command: the controlling peer's. Everybody
 * else's axis was read off the wire and dropped. A second person could connect, was replicated
 * to, and could do nothing at all - which is a spectator, not a second player, and it made the
 * whole multiplayer stack a demo of one person playing while somebody watched.
 *
 * Worse, it could not have been fixed by spawning a second champion alone: one `IntentState` read
 * by a family of every `Player` in the world means two champions driven by *one* pair of hands,
 * moving in lockstep. The routing had to change with the roster, which is what `PlayerIntents` is.
 *
 * ## What is real here
 *
 * A real [MobaLoopbackSession]: the authoritative server over the real 27-unit level, two clients
 * on real transports, and `NetHarness` driving release-then-receive-then-send so the minimum
 * round trip is two ticks. The two clients hold **opposite** axes, which is the whole point - a
 * bug that fed both champions one command would move them together, and a test that walked both
 * players the same way could not tell that apart from working.
 *
 * Every assertion about what a client *sees* is read out of that client's own Fleks world, which
 * seeded no level and simulated no tick. Every entity in it arrived as a datagram.
 */
class MobaTwoPlayerTest {

    @Test
    fun `each connected peer drives its own champion and both clients see both`() {
        session().use { live ->
            val server = live.server
            val mine = assertNotNull(server.championOf(PeerId.client(1)), "client 1 got no champion")
            val theirs = assertNotNull(server.championOf(PeerId.client(2)), "client 2 got no champion")
            assertTrue(mine != theirs, "both clients were seated on the same champion")

            live.step(TICKS)

            // --- the server: two champions, walked apart by two different pairs of hands ------
            val left = serverPosition(live, mine)
            val right = serverPosition(live, theirs)
            assertTrue(
                left.first > Player.SPAWN_X + MOVED,
                "client 1 held +x for $TICKS ticks and its champion is at ${left.first}",
            )
            assertTrue(
                right.first < left.first - MOVED,
                "both champions ended at ${left.first} and ${right.first}: one command drove both",
            )

            // --- each client: both champions, at the server's coordinates --------------------
            for (client in live.clients) {
                val seen = champions(client)
                assertEquals(
                    2,
                    seen.size,
                    "${client.peer} sees ${seen.size} champion(s): ${seen.values}",
                )
                // Which one is mine, answered by the server over the wire. Without
                // `Player.owner` a window has no way at all to tell, because a `NetId` is
                // allocation order and a client cannot predict it.
                val ownIds = seen.keys
                assertTrue(1 in ownIds && 2 in ownIds, "champion owners on the wire were $ownIds")
                val one = assertNotNull(seen[1])
                val two = assertNotNull(seen[2])
                // Walked apart, as this window received it. Not the server's numbers copied
                // across: this is the replicated world's own answer to "where are the two
                // players", and a session that fed both champions one command would put both of
                // these on the same side of the spawn.
                assertTrue(one.first > Player.SPAWN_X + MOVED, "${client.peer} has champion 1 at $one")
                assertTrue(two.first < Player.SPAWN_X - MOVED, "${client.peer} has champion 2 at $two")
                // And it is a *live* view rather than a stale one. A client can only ever hold a
                // tick the server has already left - one tick of a soldier's walk is about 0.6
                // world units - so this is bounded rather than equal, and the bound is what would
                // catch replication that had stopped a second ago.
                assertNear(left, one, "${client.peer} on client 1's champion")
                assertNear(right, two, "${client.peer} on client 2's champion")
            }
        }
    }

    @Test
    fun `a champion nobody is driving stands still rather than repeating the last axis`() {
        // The other half of the routing. A peer that leaves must not leave its soldier walking:
        // `PlayerControlSystem` zeroes an unrouted champion, and a champion that kept the last
        // axis would walk off the level for the rest of the match.
        session().use { live ->
            live.step(TICKS)
            val theirs = assertNotNull(live.server.championOf(PeerId.client(2)))
            live.server.removeClient(PeerId.client(2))
            val whenLeft = serverPosition(live, theirs)
            live.step(TICKS)
            assertClose(whenLeft, serverPosition(live, theirs), "a released champion kept walking")
        }
    }

    /** Two clients holding opposite axes for the whole run. */
    private fun session(): MobaLoopbackSession = MobaLoopbackSession(
        clientCount = 2,
        mtu = MTU,
        input = { client, tick ->
            client.command(tick, moveX = if (client.peer == PeerId.client(1)) 1f else -1f)
        },
    )

    /** Where the server has [champion], read out of the authoritative world. */
    private fun serverPosition(live: MobaLoopbackSession, champion: NetId): Pair<Float, Float> {
        val entity = assertNotNull(
            live.server.host.ctx[CoreModule.NET_IDS].resolveOrNull(champion),
            "the server lost champion $champion",
        )
        return with(live.server.host.world) {
            val position = assertNotNull(entity.getOrNull(Position))
            position.x to position.y
        }
    }

    /** Every champion this client can see, by the peer id the server stamped on it. */
    private fun champions(client: MobaClientSession): Map<Int, Pair<Float, Float>> {
        val entities = client.world.family { all(Player) }.entities
        val seen = LinkedHashMap<Int, Pair<Float, Float>>()
        with(client.world) {
            for (index in 0 until entities.size) {
                val entity: Entity = entities[index]
                val position = entity.getOrNull(Position) ?: continue
                seen[entity[Player].owner] = position.x to position.y
            }
        }
        return seen
    }

    private fun assertClose(expected: Pair<Float, Float>, actual: Pair<Float, Float>, why: String) =
        assertWithin(EPSILON, expected, actual, why)

    /** The same comparison, with a few ticks of replication lag allowed for. */
    private fun assertNear(expected: Pair<Float, Float>, actual: Pair<Float, Float>, why: String) =
        assertWithin(LAG_TOLERANCE, expected, actual, why)

    private fun assertWithin(
        tolerance: Float,
        expected: Pair<Float, Float>,
        actual: Pair<Float, Float>,
        why: String,
    ) {
        assertTrue(
            abs(expected.first - actual.first) <= tolerance &&
                abs(expected.second - actual.second) <= tolerance,
            "$why: expected $expected within $tolerance, was $actual",
        )
    }

    private companion object {

        /** Four seconds at 60Hz: long enough for two soldiers to be unmistakably apart. */
        const val TICKS = 240

        /**
         * A whole tick in one message, so a client's world is one server tick rather than a mix.
         *
         * At the real 1200 the packer defers what does not fit and a position can legitimately be
         * a tick or two old, which would make the coordinate comparison below flaky rather than
         * wrong. The transport fragments and reassembles the difference.
         */
        const val MTU = 16384

        /** World units a champion must have covered for "it walked" to be a statement. */
        const val MOVED = 1.0f

        /** Float compare across two captures of the same value. */
        const val EPSILON = 0.001f

        /**
         * How far a client's copy of a position may trail the server's.
         *
         * About six ticks of a soldier's walk. A client holds a tick the server has already left,
         * always, so an exact comparison here would be asserting that replication is
         * instantaneous rather than that it is correct - and a tolerance of one whole second of
         * walking would stop noticing a client that had gone quiet.
         */
        const val LAG_TOLERANCE = 4.0f
    }
}
