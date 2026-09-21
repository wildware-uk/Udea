package dev.wildware.hollow.net

import com.github.quillraven.fleks.World
import dev.wildware.hollow.HollowMovement
import dev.wildware.hollow.Player
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.spatial.Animator
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.generated.Human
import dev.wildware.udea.net.transport.NetConditions
import dev.wildware.udea.net.transport.NetEndpoint
import dev.wildware.udea.net.transport.NetHarness
import dev.wildware.udea.net.transport.PeerId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #250 with a server and two clients: what a player does to their character agrees on every
 * machine, and the local one answers the key press on the tick it happened.
 *
 * One process, one thread, no socket and no sleep - a headless authoritative [HollowServer] and two
 * headless [HollowClient]s over `udea-net`'s simulated network, at 150ms each way (nine ticks), the
 * shape `ClearingReplicationTest` established for issue #249. Every number asserted here crossed
 * that link.
 *
 * ## What "agrees" means, and what it deliberately does not
 *
 * The server is the authority on where a character is and which clip it is playing, and a client
 * holds a copy of a tick the server has already left. So agreement is asserted **after the drive has
 * stopped and the link has drained**, where a correct system must converge exactly - and it is
 * asserted on every replicated field of `Transform3D` and `Animator`, not on a position alone.
 *
 * While a character is moving, a client can only agree with a tick the server has already left, so
 * the one test that compares during a walk compares each client with the server's record of **the
 * tick that client was told**, not with the server's present (issue #251). And the local character
 * is predicted, so it is *ahead* of what the server has managed to tell its client - the last test
 * here. A test that demanded agreement with the server's present during the drive would be asserting
 * a link with no delay.
 */
class PlayerReplicationTest {

    private val harness = NetHarness(clients = 2, initialConditions = NetConditions(latencyTicks = LATENCY))
    // No fox waves (issue #251): these are the players' tests, and a fox that reached a character
    // standing still would push it.
    private val server = HollowServer(harness.transport(PeerId.SERVER), waves = null)
    private val characters = ArrayList<NetId>()
    private val clients = harness.clientPeers().map { peer ->
        characters += server.addClient(peer)
        HollowClient(peer, harness.transport(peer))
    }

    /** What each client's player is holding this tick, by index. Written by a test, read per tick. */
    private val hands = Array(2) { Hand() }

    init {
        harness.register(
            object : NetEndpoint {
                override val peer: PeerId = PeerId.SERVER

                override fun onReceive(from: PeerId, buffer: ByteArray, offset: Int, length: Int) {
                    server.onPacket(from, buffer, offset, length)
                }

                override fun onTick(tick: Tick) {
                    server.tick()
                    record()
                }
            },
        )
        for ((index, client) in clients.withIndex()) {
            harness.register(
                object : NetEndpoint {
                    override val peer: PeerId = client.peer

                    override fun onReceive(from: PeerId, buffer: ByteArray, offset: Int, length: Int) {
                        client.onPacket(buffer, offset, length)
                    }

                    override fun onTick(tick: Tick) {
                        val hand = hands[index]
                        client.tick(tick, client.command(tick, hand.moveX, hand.moveY, hand.running))
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
    fun `each client is told which character is its own, and they are different characters`() {
        harness.step(SETTLE)

        for ((index, client) in clients.withIndex()) {
            assertEquals(characters[index], client.character, "${client.peer} took the wrong character")
            assertEquals(server.characterOf(client.peer), client.character, "${client.peer} disagrees with the server")
        }
        assertNotEquals(characters[0], characters[1], "both clients were given the same character")
    }

    @Test
    fun `what a player does to their character is the same on the server and on both clients`() {
        harness.step(SETTLE)

        // Two players, two directions, both walking at once: a character that agreed only because
        // nothing else was happening would prove nothing about routing. Character one stands east
        // of character zero - `HollowServer.SPACING` - so zero is driven north, away from it, and
        // what happens when they do meet has a test of its own below.
        hands[0].walk(0f, 1f)
        hands[1].walk(1f, 0f, running = true)
        harness.step(DRIVE)
        hands.forEach(Hand::stop)
        harness.step(SETTLE)

        val authority = server.host
        for (character in characters) {
            val expected = placement(authority.world, authority.ctx[CoreModule.NET_IDS], character)
            for (client in clients) {
                val seen = placement(client.host.world, client.host.ctx[CoreModule.NET_IDS], character)
                assertEquals(expected, seen, "${client.peer} disagrees about $character")
            }
        }

        // And they went where they were driven, so the agreement is about movement rather than
        // about two copies of a character that never moved.
        val walker = transform(authority.world, authority.ctx[CoreModule.NET_IDS], characters[0])
        val runner = transform(authority.world, authority.ctx[CoreModule.NET_IDS], characters[1])
        val walked = walker.y - HollowServer.SPAWN_Y
        val ran = runner.x - (HollowServer.SPAWN_X + HollowServer.SPACING)
        assertTrue(walked > MOVED, "the walking character only covered $walked")
        assertTrue(ran > walked * RUN_IS_FASTER_BY, "the running character covered $ran against a walk of $walked")
    }

    @Test
    fun `the clip a character is playing is the same on the server and on both clients`() {
        harness.step(SETTLE)

        hands[0].walk(0f, 1f)
        harness.step(DRIVE)

        // Mid-walk: the server has been playing the walk for a second, which is longer than any
        // round trip, so a client that had not been told would be visibly wrong here.
        assertClip(Human.Clips.Walk.index, characters[0], "while walking")

        hands[0].walk(0f, 1f, running = true)
        harness.step(DRIVE)
        assertClip(Human.Clips.Run.index, characters[0], "while running")

        hands[0].stop()
        harness.step(SETTLE)
        assertClip(Human.Clips.Idle.index, characters[0], "after stopping")

        // The whole component, not the clip index alone: a crossfade is five more replicated
        // numbers, and a client that held the right clip from the wrong tick would be playing the
        // same animation out of phase with everybody else.
        val expected = animatorFields(server.host.world, server.host.ctx[CoreModule.NET_IDS], characters[0])
        for (client in clients) {
            val seen = animatorFields(client.host.world, client.host.ctx[CoreModule.NET_IDS], characters[0])
            assertEquals(expected, seen, "${client.peer}'s animator for ${characters[0]}")
        }
    }

    @Test
    fun `a character nobody is driving stays put on every machine`() {
        harness.step(SETTLE)
        hands[0].walk(0f, 1f)
        harness.step(DRIVE)
        hands[0].stop()
        harness.step(SETTLE)

        val idle = characters[1]
        for (client in clients) {
            val seen = transform(client.host.world, client.host.ctx[CoreModule.NET_IDS], idle)
            assertEquals(HollowServer.SPAWN_X + HollowServer.SPACING, seen.x, TOLERANCE, "${client.peer}: $seen")
            assertEquals(HollowServer.SPAWN_Y, seen.y, TOLERANCE, "${client.peer}: $seen")
        }
    }

    @Test
    fun `a character walked into another pushes it, and every machine sees the same push`() {
        harness.step(SETTLE)

        // Straight at the other character, which stands `SPACING` east holding nothing.
        hands[0].walk(1f, 0f, running = true)
        harness.step(DRIVE)
        hands[0].stop()
        harness.step(SETTLE)

        val ids = server.host.ctx[CoreModule.NET_IDS]
        val pusher = transform(server.host.world, ids, characters[0])
        val pushed = transform(server.host.world, ids, characters[1])

        assertTrue(
            pushed.x > HollowServer.SPAWN_X + HollowServer.SPACING + MOVED,
            "the character that was walked into did not move: $pushed",
        )
        assertTrue(
            pushed.x - pusher.x > TOUCHING,
            "the two characters ended up inside each other: $pusher and $pushed",
        )

        // Both of them, on every machine, to the last replicated number. Two bodies in contact are
        // the hardest thing in this game for three worlds to agree about.
        for (character in characters) {
            val expected = placement(server.host.world, ids, character)
            for (client in clients) {
                val seen = placement(client.host.world, client.host.ctx[CoreModule.NET_IDS], character)
                assertEquals(expected, seen, "${client.peer} disagrees about $character after the push")
            }
        }
    }

    @Test
    fun `the local character answers on the tick the key goes down, and converges when it stops`() {
        harness.step(SETTLE)

        val driver = clients[0]
        val prediction = assertNotNull(driver.prediction, "the client never started predicting its own character")
        assertTrue(prediction.started, "the prediction never took the server's position")
        val before = driver.predictedPose.y

        hands[0].walk(0f, 1f)
        harness.step(1)

        assertTrue(
            driver.predictedPose.y > before,
            "the drawn character must move on the tick the key went down; it moved " +
                "${driver.predictedPose.y - before}",
        )

        harness.step(DRIVE)
        val lead = prediction.settledY - replicatedY(driver, characters[0])
        assertTrue(
            prediction.pendingCount >= MINIMUM_IN_FLIGHT,
            "at $LATENCY ticks each way there must be at least $MINIMUM_IN_FLIGHT commands in " +
                "flight; there were ${prediction.pendingCount}",
        )
        assertTrue(
            lead >= MINIMUM_LEAD,
            "the prediction must lead the position the server has managed to send; it led by $lead",
        )
        assertEquals(0L, prediction.snaps, "the prediction snapped, which is a divergence rather than a correction")

        // Let go and let the link drain: the predicted character is the server's character again.
        hands[0].stop()
        harness.step(SETTLE)
        val settled = transform(server.host.world, server.host.ctx[CoreModule.NET_IDS], characters[0])
        assertEquals(settled.x, driver.predictedPose.x, TOLERANCE, "the prediction never came back to the server's")
        assertEquals(settled.y, driver.predictedPose.y, TOLERANCE, "the prediction never came back to the server's")

        // The other client predicts its own character, not this one.
        assertNotEquals(driver.character, clients[1].character, "both clients predicted the same character")
    }

    @Test
    fun `a walking character is where the server had it on the very tick each client was told`() {
        harness.step(SETTLE)
        hands[0].walk(0f, 1f)

        // Six seconds of walking, compared every quarter of a second, which is long enough to span
        // the moments the server re-sends the whole clearing (issue #251): with the props weighted
        // like the characters those moments held the walker back, on every client, for ticks.
        var compared = 0
        repeat(WALK_CHECKS) {
            harness.step(QUARTER_SECOND)
            for (client in clients) {
                client.applier.apply(client.replication.world)
                val told = client.serverTick.value
                val expected = checkNotNull(walked[told]) { "the server never recorded tick $told" }
                val seen = placement(client.host.world, client.host.ctx[CoreModule.NET_IDS], characters[0])
                assertEquals(expected, seen, "${client.peer} was told tick $told and holds the walker somewhere else")
                compared++
            }
        }
        val moved = walked.getValue(server.tick.value)[1] - HollowServer.SPAWN_Y
        assertTrue(moved > MOVED, "the walker only covered $moved, so the comparison was of a character standing still")
        assertEquals(WALK_CHECKS * clients.size, compared)
    }

    /** Where character zero stood at the end of each server tick, by tick. */
    private val walked = HashMap<Long, List<Float>>()

    private fun record() {
        val ids = server.host.ctx[CoreModule.NET_IDS]
        if (ids.resolveOrNull(characters.firstOrNull() ?: return) == null) return
        walked[server.tick.value] = placement(server.host.world, ids, characters[0])
    }

    /** Asserts every machine has [character] playing clip [clip]. */
    private fun assertClip(clip: Int, character: NetId, moment: String) {
        val authority = clipOf(server.host.world, server.host.ctx[CoreModule.NET_IDS], character)
        assertEquals(clip, authority, "the server played $authority $moment")
        for (client in clients) {
            val seen = clipOf(client.host.world, client.host.ctx[CoreModule.NET_IDS], character)
            assertEquals(clip, seen, "${client.peer} played $seen $moment")
        }
    }

    private fun replicatedY(client: HollowClient, character: NetId): Float =
        transform(client.host.world, client.host.ctx[CoreModule.NET_IDS], character).y

    private fun transform(world: World, ids: NetIdIndex, id: NetId): Transform3D {
        val entity = checkNotNull(ids.resolveOrNull(id)) { "$id is not in this world" }
        return with(world) { entity[Transform3D] }
    }

    /** Every replicated number of [id]'s `Transform3D`, so agreement is about all of it. */
    private fun placement(world: World, ids: NetIdIndex, id: NetId): List<Float> {
        val at = transform(world, ids, id)
        return listOf(at.x, at.y, at.z, at.rotationX, at.rotationY, at.rotationZ, at.scaleX, at.scaleY, at.scaleZ)
    }

    private fun clipOf(world: World, ids: NetIdIndex, id: NetId): Int {
        val entity = checkNotNull(ids.resolveOrNull(id)) { "$id is not in this world" }
        return with(world) { entity[Animator].current.clip }
    }

    /** Every replicated number of [id]'s `Animator`: both playbacks and the fade. */
    private fun animatorFields(world: World, ids: NetIdIndex, id: NetId): List<String> {
        val entity = checkNotNull(ids.resolveOrNull(id)) { "$id is not in this world" }
        val animator = with(world) { entity[Animator] }
        return listOf(
            animator.current.toString(),
            animator.previous.toString(),
            "fade=${animator.fadeLength} from ${animator.fadeStart}",
        )
    }

    /** One player's hands: what they are holding, read once a tick by that client's endpoint. */
    private class Hand {
        var moveX: Float = 0f
        var moveY: Float = 0f
        var running: Boolean = false

        fun walk(x: Float, y: Float, running: Boolean = false) {
            moveX = x
            moveY = y
            this.running = running
        }

        fun stop() {
            moveX = 0f
            moveY = 0f
            running = false
        }
    }

    private companion object {
        /** Nine ticks each way: 150ms, the link `moba`'s prediction proof is written against. */
        const val LATENCY = 9

        /** Ticks for a change to cross the link, be acknowledged and stop moving. */
        const val SETTLE = 120

        /** A second of walking. */
        const val DRIVE = 60

        const val QUARTER_SECOND = 15

        /** Twenty-four quarter-seconds: six seconds of walking. */
        const val WALK_CHECKS = 24

        /** Floats that crossed a link and came back: a `Transform3D` field is not quantised. */
        const val TOLERANCE = 1e-3f

        /** Further than any settling could account for. */
        const val MOVED = 1f

        /** Two characters in contact are two radii apart, less what Box2D lets a contact sink. */
        const val TOUCHING = 2f * Player.RADIUS - 0.05f

        const val RUN_IS_FASTER_BY = 1.5f

        /** A round trip at [LATENCY], less the ticks the jitter buffer holds a command for. */
        const val MINIMUM_IN_FLIGHT = LATENCY

        /**
         * How far ahead the prediction must be, in world units.
         *
         * A round trip is at least eighteen ticks, and a walking character covers
         * `WALK_SPEED / 60` of a unit a tick, so half of that is a floor with room in it rather
         * than a number tuned to what the run happened to produce.
         */
        const val MINIMUM_LEAD = HollowMovement.WALK_SPEED * LATENCY / 60f
    }
}
