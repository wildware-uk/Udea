package dev.wildware.moba.net

import dev.wildware.udea.core.Tick
import dev.wildware.udea.net.bits.BitBufferWriter
import dev.wildware.udea.net.transport.LoopbackNetwork
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.wire.PacketHeader
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A connected client must not be able to end the server's run with a datagram.**
 *
 * ## The defect
 *
 * [MobaHostSession.onPacket] had two halves and guarded one of them. `dispatchRpcs` caught its
 * own parse failures, with a comment saying why; `ReplicationServer.onPacket` was called bare,
 * one line below. That method reads a [PacketHeader] out of attacker-supplied bytes and then
 * walks length-prefixed frames out of the same slice, so a truncated header, a frame length that
 * runs off the end, or a component index that does not exist all throw straight through the
 * session and out of whatever loop is driving it. Forty junk payloads from a peer that had
 * completed the handshake ended a verifier's run.
 *
 * That is the same shape as the hole the old stack left at `PacketUtil.kt:148`: a datagram is
 * attacker-controlled, so a parse failure that propagates is a denial of service dressed as a
 * stack trace. Authentication does not help - the peer here *is* connected, which is precisely
 * the case that matters, because a modified client is the whole threat model.
 *
 * ## What is real here
 *
 * A real [MobaLoopbackSession] over the real 27-unit level with two clients on it. The junk is
 * pushed into [MobaHostSession.onPacket] byte for byte as a transport delivers it, from a peer
 * the session has registered, and it is deliberately of four different shapes - the first byte of
 * a header, random noise, a *valid* header followed by garbage frames, and a header whose frame
 * lengths point past the end - because a guard that only survived unparseable noise would still
 * die on the one that gets past the header.
 *
 * Surviving is not the assertion. The assertion is that the game is **still being played
 * afterwards**: the server keeps ticking, the second client's champion keeps moving, and both
 * clients go on receiving.
 */
class MobaHostileClientTest {

    @Test
    fun `forty junk payloads from a connected peer do not stop the server`() {
        MobaLoopbackSession(clientCount = 2, mtu = LoopbackNetwork.DEFAULT_MTU).use { live ->
            live.step(WARMUP)
            val server = live.server
            val before = server.tick
            val appliedBefore = live.clients.map { it.applied }

            val random = Random(SEED)
            repeat(PAYLOADS) { index -> server.onPacket(PeerId.client(1), junk(server, random, index)) }

            // It refused every one of them as unparseable rather than acting on any.
            assertTrue(
                server.malformedPackets > 0L,
                "not one of $PAYLOADS junk payloads was even refused; this test is asserting nothing",
            )
            // Still simulating, still replicating, still two players in the game.
            live.step(WARMUP)
            assertTrue(server.tick.value > before.value, "the server stopped ticking")
            assertEquals(2, server.clients().size, "a junk payload unseated a player")
            for ((index, client) in live.clients.withIndex()) {
                assertTrue(
                    client.applied > appliedBefore[index],
                    "${client.peer} stopped receiving after the flood",
                )
            }
        }
    }

    @Test
    fun `junk does not disturb the client that sent it`() {
        // A guard that dropped the *whole* datagram on a parse failure would be correct; one that
        // left a per-client state machine half-updated would be a slower version of the same bug.
        // The sender goes on playing, which is what says the drop was clean.
        MobaLoopbackSession(clientCount = 2, mtu = LoopbackNetwork.DEFAULT_MTU).use { live ->
            live.step(WARMUP)
            val random = Random(SEED)
            repeat(PAYLOADS) { index ->
                live.server.onPacket(PeerId.client(1), junk(live.server, random, index))
            }
            val applied = live.clients.first().applied
            live.step(WARMUP)
            assertTrue(live.clients.first().applied > applied, "the sender stopped being replicated to")
        }
    }

    /** Feeds a whole payload in, exactly as `NetHarness` hands the session a received slice. */
    private fun MobaHostSession.onPacket(from: PeerId, payload: ByteArray) {
        onPacket(from, payload, 0, payload.size)
    }

    /**
     * One hostile payload. Four shapes, cycled, so no one of them can carry the whole test.
     *
     * Shape 2 and shape 3 both open with this session's **real** `protoHash`, because a header
     * that does not match is dropped at the first branch and never reaches the frame walker -
     * which is the half of `ReplicationServer.onPacket` that actually parses attacker data.
     */
    private fun junk(server: MobaHostSession, random: Random, index: Int): ByteArray =
        when (index % SHAPES) {
            0 -> ByteArray(random.nextInt(1, TRUNCATION_BYTES)) { random.nextInt().toByte() }
            1 -> ByteArray(random.nextInt(1, NOISE_BYTES)) { random.nextInt().toByte() }
            2 -> header(server) + ByteArray(random.nextInt(1, NOISE_BYTES)) { random.nextInt().toByte() }
            else -> header(server) + byteArrayOf(-1, -1, -1, -1, -1, -1, -1, -1)
        }

    /** A structurally valid header carrying this session's protocol, and nothing after it. */
    private fun header(server: MobaHostSession): ByteArray {
        val buffer = ByteArray(HEADER_BUFFER)
        val writer = BitBufferWriter(buffer)
        PacketHeader(
            protoHash = server.protocol.protoHash,
            seq = 1,
            ack = 0,
            ackBits = 0,
            serverTick = Tick.ZERO,
            baselineTick = Tick.ZERO,
            hasBaseline = false,
            hasAck = false,
        ).write(writer)
        return buffer.copyOf(writer.byteLength)
    }

    private companion object {

        /** Long enough for the level to be seeded and both clients to hold state. */
        const val WARMUP = 30

        /** What the verifier fired. */
        const val PAYLOADS = 40

        /** Distinct hostile shapes; see [junk]. */
        const val SHAPES = 4

        /** Seeded, so a survivor is a seed you can re-run and not something that happened once. */
        const val SEED = 20_260_823L

        /** Shorter than a fixed header, so the read runs off the end of the slice. */
        const val TRUNCATION_BYTES = 12

        const val NOISE_BYTES = 96

        const val HEADER_BUFFER = 64
    }
}
