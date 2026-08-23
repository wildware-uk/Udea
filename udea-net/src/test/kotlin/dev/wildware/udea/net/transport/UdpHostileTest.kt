package dev.wildware.udea.net.transport

import dev.wildware.udea.core.Tick
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a hostile peer gets, which in every case here is nothing.
 *
 * The shared shape of every test: send the server something it must refuse, assert the specific
 * counter that names the refusal, then assert the server still serves a legitimate peer. The
 * second half is the important one. A server that refuses a malformed packet by dying has
 * refused it, technically.
 */
class UdpHostileTest {

    @Test
    fun `a datagram with an unknown type is refused and the server keeps serving`() {
        UdpServerOnly().use { fixture ->
            RawPeer(fixture.address).use { attacker ->
                attacker.send(byteArrayOf(0x7F, 1, 2, 3))
                attacker.send(ByteArray(0))
                fixture.step(4)

                assertTrue(fixture.server.counters.malformed >= 1, "the garbage was not counted")
                assertTrue(attacker.drain().isEmpty(), "the server answered garbage")
            }
            RawPeer(fixture.address).use { honest ->
                assertTrue(honest.handshake(clientSalt = 9L, pump = { fixture.step() }), "the server stopped serving")
            }
        }
    }

    @Test
    fun `a payload truncated inside its own header is refused`() {
        UdpServerOnly().use { fixture ->
            RawPeer(fixture.address).use { peer ->
                assertTrue(peer.handshake(clientSalt = 11L, pump = { fixture.step() }))
                val before = fixture.server.counters.malformed

                val whole = peer.payload("hello".toByteArray())
                peer.send(whole.copyOfRange(0, UdpLayout.PAYLOAD_HEADER_BYTES - 1))
                fixture.step(4)

                assertEquals(before + 1, fixture.server.counters.malformed)
                assertTrue(fixture.sink.messages.isEmpty(), "a truncated datagram was delivered")

                peer.send(whole)
                fixture.step(4)
                assertContentEquals("hello".toByteArray(), fixture.sink.messages.single())
            }
        }
    }

    @Test
    fun `a payload replayed verbatim is delivered once and refused thereafter`() {
        UdpServerOnly().use { fixture ->
            RawPeer(fixture.address).use { peer ->
                assertTrue(peer.handshake(clientSalt = 13L, pump = { fixture.step() }))
                val recorded = peer.payload("fire the ultimate".toByteArray(), seq = 4)

                peer.send(recorded)
                fixture.step(4)
                assertEquals(1, fixture.sink.messages.size, "the original was not delivered")

                repeat(5) { peer.send(recorded) }
                fixture.step(4)

                assertEquals(1, fixture.sink.messages.size, "a replayed datagram was delivered again")
                assertEquals(5L, fixture.server.counters.replayed)
                assertTrue(fixture.server.isConnected, "the replay took the connection down")

                // And the connection is still usable: refusing a replay must not poison the window.
                peer.send(peer.payload("still here".toByteArray(), seq = 5))
                fixture.step(4)
                assertEquals(2, fixture.sink.messages.size)
            }
        }
    }

    @Test
    fun `an oversized datagram is refused rather than silently truncated`() {
        UdpServerOnly().use { fixture ->
            RawPeer(fixture.address).use { peer ->
                assertTrue(peer.handshake(clientSalt = 17L, pump = { fixture.step() }))

                val oversized = ByteArray(UdpConfig().mtu + 400)
                val view = ByteBuffer.wrap(oversized)
                view.put(UdpLayout.TYPE, UdpPacketType.Payload.id.toByte())
                view.putLong(UdpLayout.PAYLOAD_SALT, peer.salt)
                view.putShort(UdpLayout.PAYLOAD_SEQ, 100)
                peer.send(oversized)
                fixture.step(4)

                assertEquals(1L, fixture.server.counters.oversized)
                assertTrue(fixture.sink.messages.isEmpty(), "an oversized datagram was delivered")
                assertTrue(fixture.server.isConnected)
            }
        }
    }

    @Test
    fun `a peer that never answers the challenge costs the server no state at all`() {
        val config = UdpConfig(connectTimeoutTicks = 20L, timeoutTicks = 30L)
        UdpServerOnly(config).use { fixture ->
            RawPeer(fixture.address).use { abandoner ->
                repeat(ABANDONED_ATTEMPTS) {
                    abandoner.send(abandoner.connectionRequest(clientSalt = it.toLong()))
                    fixture.step()
                }
                fixture.step(config.timeoutTicks.toInt() * 3)

                assertTrue(fixture.server.connections().isEmpty(), "an unanswered challenge took a slot")
                assertEquals(emptyList(), fixture.events.connected)
                assertEquals(0L, fixture.server.counters.handshakesCompleted)
                // The challenges the rate limiter did let through were answered, which is the
                // point: the server replied and then forgot, so there is nothing to exhaust.
                assertTrue(abandoner.drain().isNotEmpty(), "the server never issued a challenge at all")
            }
            RawPeer(fixture.address).use { honest ->
                assertTrue(honest.handshake(clientSalt = 99L, pump = { fixture.step() }), "the server stopped serving")
                assertEquals(1, honest.assignedPeer)
            }
        }
    }

    @Test
    fun `a token minted for one address does not work from another`() {
        UdpServerOnly().use { fixture ->
            RawPeer(fixture.address).use { victim ->
                victim.send(victim.connectionRequest(clientSalt = 21L))
                fixture.step(4)
                val challenge = victim.drain().single { it[UdpLayout.TYPE].toInt() == UdpPacketType.ConnectionChallenge.id }
                val view = ByteBuffer.wrap(challenge)

                RawPeer(fixture.address).use { thief ->
                    val stolen = ByteArray(UdpConfig().mtu)
                    val out = ByteBuffer.wrap(stolen)
                    out.put(UdpLayout.TYPE, UdpPacketType.ConnectionResponse.id.toByte())
                    out.putShort(UdpLayout.RESPONSE_PROTO_HASH, TEST_PROTO_HASH.toShort())
                    out.putLong(UdpLayout.RESPONSE_CLIENT_SALT, 21L)
                    out.putLong(UdpLayout.RESPONSE_TOKEN, view.getLong(UdpLayout.CHALLENGE_TOKEN))
                    out.putLong(UdpLayout.RESPONSE_EXPIRY, view.getLong(UdpLayout.CHALLENGE_EXPIRY))
                    thief.send(stolen)
                    fixture.step(4)

                    assertEquals(1L, fixture.server.counters.tokenRejected)
                    assertTrue(fixture.server.connections().isEmpty(), "a stolen token opened a connection")
                    assertTrue(thief.drain().isEmpty(), "the server told the thief anything at all")
                }
            }
        }
    }

    @Test
    fun `a token that has outlived its lifetime is refused`() {
        val config = UdpConfig(tokenLifetimeTicks = 10L)
        UdpServerOnly(config).use { fixture ->
            RawPeer(fixture.address).use { peer ->
                peer.send(peer.connectionRequest(clientSalt = 31L))
                fixture.step(4)
                val challenge = peer.drain().single { it[UdpLayout.TYPE].toInt() == UdpPacketType.ConnectionChallenge.id }
                val view = ByteBuffer.wrap(challenge)

                // Sit on the token until it is stale, then present it.
                fixture.step(config.tokenLifetimeTicks.toInt() + 5)
                val late = ByteArray(config.mtu)
                val out = ByteBuffer.wrap(late)
                out.put(UdpLayout.TYPE, UdpPacketType.ConnectionResponse.id.toByte())
                out.putShort(UdpLayout.RESPONSE_PROTO_HASH, TEST_PROTO_HASH.toShort())
                out.putLong(UdpLayout.RESPONSE_CLIENT_SALT, 31L)
                out.putLong(UdpLayout.RESPONSE_TOKEN, view.getLong(UdpLayout.CHALLENGE_TOKEN))
                out.putLong(UdpLayout.RESPONSE_EXPIRY, view.getLong(UdpLayout.CHALLENGE_EXPIRY))
                peer.send(late)
                fixture.step(4)

                assertEquals(1L, fixture.server.counters.tokenExpired)
                assertTrue(fixture.server.connections().isEmpty(), "an expired token opened a connection")
            }
        }
    }

    @Test
    fun `an unpadded connect request buys no reply, so the server cannot amplify`() {
        UdpServerOnly().use { fixture ->
            RawPeer(fixture.address).use { attacker ->
                // A 20-byte request is all an amplification attack would ever spend. The reply
                // would be 25 bytes, so the whole scheme depends on this being refused.
                attacker.send(attacker.connectionRequest(clientSalt = 41L, length = SHORT_REQUEST_BYTES))
                fixture.step(8)

                assertTrue(attacker.drain().isEmpty(), "the server replied to an unpadded request")
                assertEquals(1L, fixture.server.counters.malformed)
                assertEquals(0L, fixture.server.counters.handshakesCompleted)
            }
        }
    }

    @Test
    fun `every reply the server sends an unverified peer is smaller than what arrived`() {
        UdpServerOnly().use { fixture ->
            RawPeer(fixture.address).use { peer ->
                val request = peer.connectionRequest(clientSalt = 43L)
                peer.send(request)
                fixture.step(4)

                val replies = peer.drain()
                assertTrue(replies.isNotEmpty(), "the handshake never started")
                for (reply in replies) {
                    assertTrue(
                        reply.size <= request.size,
                        "a ${reply.size} byte reply to a ${request.size} byte request amplifies",
                    )
                }
                assertEquals(0L, fixture.server.counters.amplificationBlocked)
            }
        }
    }

    @Test
    fun `one address cannot spend the whole handshake budget`() {
        UdpServerOnly().use { fixture ->
            RawPeer(fixture.address).use { flood ->
                repeat(FLOOD_REQUESTS) { flood.send(flood.connectionRequest(clientSalt = it.toLong())) }
                fixture.step()

                val challenges = flood.drain().count {
                    it[UdpLayout.TYPE].toInt() == UdpPacketType.ConnectionChallenge.id
                }
                assertTrue(
                    challenges <= HandshakeRateLimiter.DEFAULT_BURST.toInt(),
                    "the limiter let $challenges challenges out of one address in one tick",
                )
                assertTrue(
                    fixture.server.counters.rateLimited >= FLOOD_REQUESTS - HandshakeRateLimiter.DEFAULT_BURST.toInt() - 1,
                    "only ${fixture.server.counters.rateLimited} of $FLOOD_REQUESTS were limited",
                )
            }
            // Every peer in this test is on 127.0.0.1, and the limiter keys on the address
            // without the port precisely so that walking the source port buys nothing. So the
            // property left to assert here is that the bucket refills and the address is not
            // locked out for good; that a *different* address keeps its own bucket is asserted
            // in `UdpGuardTest`, where the peers can have different addresses.
            fixture.step(REFILL_TICKS)
            RawPeer(fixture.address).use { honest ->
                assertTrue(honest.handshake(clientSalt = 51L, pump = { fixture.step() }), "the bucket never refilled")
            }
        }
    }

    @Test
    fun `a payload carrying the wrong salt is refused even from a known address`() {
        UdpServerOnly().use { fixture ->
            RawPeer(fixture.address).use { peer ->
                assertTrue(peer.handshake(clientSalt = 61L, pump = { fixture.step() }))

                val forged = peer.payload("guessed".toByteArray(), seq = 200)
                ByteBuffer.wrap(forged).putLong(UdpLayout.PAYLOAD_SALT, peer.salt xor 1L)
                peer.send(forged)
                fixture.step(4)

                assertEquals(1L, fixture.server.counters.unknownConnection)
                assertTrue(fixture.sink.messages.isEmpty(), "a forged salt was delivered")
            }
        }
    }

    @Test
    fun `the connect token is bound to address, salt and expiry`() {
        val secret = testSecret()
        val here = loopback(40_000)
        val elsewhere = loopback(40_001)
        val token = secret.token(here, clientSalt = 5L, expiry = Tick(100))

        assertTrue(secret.verifies(here, 5L, Tick(100), token), "the token does not verify against itself")
        assertTrue(!secret.verifies(elsewhere, 5L, Tick(100), token), "the address is not in the token")
        assertTrue(!secret.verifies(here, 6L, Tick(100), token), "the client salt is not in the token")
        assertTrue(!secret.verifies(here, 5L, Tick(101), token), "the expiry is not in the token")

        val otherKey = ConnectionSecret(ByteArray(ConnectionSecret.MIN_KEY_BYTES) { (it + 1).toByte() })
        assertTrue(!otherKey.verifies(here, 5L, Tick(100), token), "a different key mints the same token")
    }

    private companion object {

        /** Enough attempts to prove nothing accumulates, few enough to finish instantly. */
        const val ABANDONED_ATTEMPTS: Int = 64

        /** A request too short to pay for its own challenge. */
        const val SHORT_REQUEST_BYTES: Int = 20

        /** Well past the per-address burst, so the limiter has to bite. */
        const val FLOOD_REQUESTS: Int = 64

        /** Ticks to earn the whole burst back at the default refill rate. */
        val REFILL_TICKS: Int =
            (HandshakeRateLimiter.DEFAULT_BURST / HandshakeRateLimiter.DEFAULT_REFILL_PER_TICK).toInt()
    }
}
