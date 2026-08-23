package dev.wildware.moba.net

import dev.wildware.moba.ability.ActivateAbilityRpc
import dev.wildware.moba.ability.AbilityRpc
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.net.bits.BitBufferWriter
import dev.wildware.udea.net.rpc.RpcOutbox
import dev.wildware.udea.net.rpc.RpcRefusal
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.wire.FrameWriter
import dev.wildware.udea.net.wire.PacketHeader
import dev.wildware.udea.core.Tick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The hole the old engine had, closed and measured, on a datagram that crossed a `Transport`.**
 *
 * ## The exact defect
 *
 * `common/.../network/PacketUtil.kt:148` carried the literal comment `// TODO validate the
 * sender!`. The old server read an entity id out of an `AbilityPacket`, resolved it and fired.
 * Nothing asked whether the connection that sent the packet had any relationship to that entity,
 * so any client could fire any ability on any entity - the enemy team's ultimate included.
 *
 * ## What is real here, and how this differs from `AbilityRpcTest`
 *
 * `AbilityRpcTest` proves the *guard*: it builds a frame and hands it straight to an `RpcServer`.
 * Its own KDoc admits "the datagram never crosses a `Transport`", because the session that would
 * carry it belonged to another file in the same wave.
 *
 * This is that session. The server is a real [MobaHostSession] over the real 27-unit level, the
 * clients are real peers on a real [dev.wildware.udea.net.transport.Transport], and the hostile
 * packet is written as a **complete datagram** - `PacketHeader` and a length-prefixed
 * `MessageType.Rpc` frame - and pushed into the client's own transport, exactly as a modified
 * client would. It arrives through `NetHarness`, is polled by the server endpoint and reaches
 * [MobaHostSession.onPacket] with no test-only shortcut anywhere in the path.
 *
 * A modified client is the whole threat model, which is why the attack is *sent* rather than
 * called: nothing on the sending side can be trusted to have run a check.
 */
class NetAntiCheatTest {

    @Test
    fun `a client cannot fire an ability on an entity it does not own`() {
        MobaLoopbackSession(clientCount = 2).use { live ->
            live.step(WARMUP)
            val server = live.server
            val champion = server.playerId

            // Client 1 joined first, so it drives the level's one `Player`. Client 2 is a
            // spectator: it owns nothing at all.
            assertEquals(PeerId.client(1), server.controllingPeer())
            assertEquals(PeerId.client(1), server.ownership.ownerOf(champion))
            assertEquals(PeerId.SERVER, server.ownership.ownerOf(anAiUnit(live, champion)))

            // --- the attack: client 2 fires client 1's champion ---
            val refusal = attack(live, from = PeerId.client(2), self = champion, slot = MELEE)

            val notOwner = assertIs<RpcRefusal.NotOwner>(
                refusal,
                "the old engine accepted this packet; see PacketUtil.kt:148",
            )
            assertEquals("@Rpc(authority = OwnerPredicted)", notOwner.rule)
            assertEquals(PeerId.client(2), notOwner.sender)
            assertEquals(champion, notOwner.target)
            assertEquals(PeerId.client(1), notOwner.owner)
            assertTrue("activateAbility" in notOwner.message, notOwner.message)

            // Refused, and nothing ran. A guard that logged and fired anyway would satisfy every
            // assertion above and none of this one.
            assertEquals(1L, server.rpc.refused)
            assertEquals(0L, server.rpc.accepted)
        }
    }

    @Test
    fun `an ai unit belongs to nobody, so no client may fire its abilities`() {
        MobaLoopbackSession(clientCount = 2).use { live ->
            live.step(WARMUP)
            val creep = anAiUnit(live, live.server.playerId)

            for (peer in listOf(PeerId.client(1), PeerId.client(2))) {
                val refusal = assertIs<RpcRefusal.NotOwner>(attack(live, peer, creep, MELEE))
                assertEquals(PeerId.SERVER, refusal.owner, "an unowned entity must not fall through")
            }
            assertEquals(2L, live.server.rpc.refused)
            assertEquals(0L, live.server.rpc.accepted)
        }
    }

    @Test
    fun `the owning client's own activation crosses the wire and is accepted`() {
        // Without this, the two refusals above are equally satisfied by a server that refuses
        // everything - which is secure, and is not a game.
        MobaLoopbackSession(clientCount = 2).use { live ->
            live.step(WARMUP)
            val refusal = attack(live, PeerId.client(1), live.server.playerId, MELEE)
            assertNull(refusal, "the owning client was refused its own champion")
            assertEquals(1L, live.server.rpc.accepted)
            assertEquals(0L, live.server.rpc.refused)
        }
    }

    @Test
    fun `a spectator promoted to controller may then fire, and the departed peer may not`() {
        // Ownership follows the controller, or a reconnect leaves a game nobody can play.
        MobaLoopbackSession(clientCount = 2).use { live ->
            live.step(WARMUP)
            val champion = live.server.playerId
            live.server.removeClient(PeerId.client(1))

            assertEquals(PeerId.client(2), live.server.controllingPeer())
            assertNull(attack(live, PeerId.client(2), champion, MELEE), "the new controller was refused")
            assertIs<RpcRefusal.NotOwner>(
                attack(live, PeerId.client(1), champion, MELEE),
                "a peer that left still owned its champion",
            )
        }
    }

    /**
     * Writes one `activateAbility` call as a whole datagram and sends it from [from]'s transport.
     *
     * The header is the real [PacketHeader] with this session's `protoHash`, so a packet from a
     * different build is dropped before the frame is even looked at; the frame is the real
     * length-prefixed `MessageType.Rpc` one. Nothing here is a shortcut into the server: the
     * bytes go through `NetHarness`, which is what the server polls.
     */
    private fun attack(live: MobaLoopbackSession, from: PeerId, self: NetId, slot: Int): RpcRefusal? {
        val buffer = ByteArray(DATAGRAM_BYTES)
        val writer = BitBufferWriter(buffer)
        PacketHeader(
            protoHash = live.server.protocol.protoHash,
            seq = 0,
            ack = 0,
            ackBits = 0,
            serverTick = Tick.ZERO,
            baselineTick = Tick.ZERO,
            hasBaseline = false,
            hasAck = false,
        ).write(writer)
        val frames = FrameWriter(writer)
        ActivateAbilityRpc.send(frames, RpcOutbox(live.server.rpcRegistry), self, slot)

        // Straight into the server's own receive path, byte for byte as the transport delivers
        // it. `NetHarness` hands the server exactly this slice.
        return live.server.onPacket(from, buffer, 0, writer.byteLength)
    }

    /** Any `GameUnit` that is not the champion: every one of them is driven by the server. */
    private fun anAiUnit(live: MobaLoopbackSession, champion: NetId): NetId {
        val fields = live.server.state().fields
        for (row in 0 until fields.rowCount) {
            val id = fields.netIdAt(row)
            if (id != champion) return id
        }
        error("the level spawned nothing but the champion")
    }

    private companion object {

        /** Long enough for the level to be seeded, both clients to be registered and units to act. */
        const val WARMUP = 30

        /** Slot 0 is the melee every unit in this game is granted first. */
        const val MELEE = 0

        const val DATAGRAM_BYTES = 512
    }
}
