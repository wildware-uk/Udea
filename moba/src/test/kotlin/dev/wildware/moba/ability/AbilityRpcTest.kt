package dev.wildware.moba.ability

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.gas.ActivationResult
import dev.wildware.udea.net.bits.BitBufferWriter
import dev.wildware.udea.net.rpc.RpcRateLimiter
import dev.wildware.udea.net.rpc.RpcRefusal
import dev.wildware.udea.net.rpc.RpcOutbox
import dev.wildware.udea.net.rpc.RpcServer
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.wire.FrameReader
import dev.wildware.udea.net.wire.FrameWriter
import dev.wildware.udea.net.wire.MessageType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #109's headline proof, in the game: **a client cannot fire an ability on an entity it
 * does not own.**
 *
 * ## The exact attack the old engine allowed
 *
 * Shaun's old example ran two machines over KryoNet and fired abilities with
 * `AbilityPacket(entityId, abilityIndex)`. `common/.../network/PacketUtil.kt:148` carried the
 * literal comment `// TODO validate the sender!` — the server read the entity id out of the
 * packet, resolved it, and fired, and nothing anywhere asked whether the connection that sent
 * it had any relationship to that entity. Any client could fire any ability on any entity,
 * including the enemy team's ultimates.
 *
 * [aClientCannotFireAnAbilityOnAnEntityItDoesNotOwn] is that packet, sent by that client,
 * against this engine. It is refused by generated code, with a typed error naming the rule that
 * refused it, and the target's ability never starts.
 *
 * ## What is real here and what is not
 *
 * Real: a [CombatFixture] game with real units, real `AbilityActivation`, real ability
 * definitions; the real generated `ActivateAbilityRpc`; the real `FrameWriter`/`FrameReader`
 * framing a datagram uses; the real `RpcServer` ordering of checks.
 *
 * Not real: the datagram never crosses a `Transport`. The session that would carry it —
 * `MobaHostSession` — belongs to another file in this wave. What is proven is the layer that
 * refuses, which is the layer the old engine did not have at all.
 */
class AbilityRpcTest {

    private val fixture = CombatFixture(autopilot = false)
    private val ownership = ChampionOwnership()
    private val registry = AbilityRpc.registry()
    private val outbox = RpcOutbox(registry)
    private val limiter = RpcRateLimiter(ticksPerSecond = TICKS_PER_SECOND, rpcCount = registry.size)
    private val server = RpcServer(registry, ownership, limiter)

    /** Slot 0 is the melee every unit in this game is granted first. */
    private val melee = 0

    init {
        AbilityRpc.bind { self, slot -> fixture.activate(self, slot) }
    }

    @AfterTest
    fun tearDown() {
        // A bound sink is process state. Left behind, the next test in this JVM would activate
        // abilities on a world that has already been torn down.
        AbilityRpc.unbind()
    }

    /**
     * Writes one `activateAbility` call the way a client does, and delivers it the way a server
     * does: through a real length-prefixed frame, read back out of the bytes.
     */
    private fun send(sender: PeerId, self: NetId, slot: Int, at: Tick = fixture.host.tick): RpcRefusal? {
        val buffer = ByteArray(DATAGRAM_BYTES)
        val writer = BitBufferWriter(buffer)
        val frames = FrameWriter(writer)
        ActivateAbilityRpc.send(frames, outbox, self, slot)

        val walker = FrameReader(buffer, 0, writer.byteLength, headerBits = 0L)
        val frame = requireNotNull(walker.next()) { "the client wrote no frame" }
        assertEquals(MessageType.Rpc, frame.type)
        return server.receive(sender, walker.readerFor(frame), at)
    }

    @Test
    fun aClientCannotFireAnAbilityOnAnEntityItDoesNotOwn() {
        val mine = fixture.spawn("soldier", 0f, 0f)
        val theirs = fixture.spawn("orc_elite", 3f, 0f)
        fixture.step(1)
        ownership.assign(mine, PeerId.client(1))
        ownership.assign(theirs, PeerId.client(2))

        val refusal = send(PeerId.client(1), theirs, melee)

        // Typed, and it names the authority rule rather than merely saying no.
        val notOwner = assertIs<RpcRefusal.NotOwner>(refusal, "the old engine's packet was accepted")
        assertEquals("@Rpc(authority = OwnerPredicted)", notOwner.rule)
        assertEquals(PeerId.client(1), notOwner.sender)
        assertEquals(theirs, notOwner.target)
        assertEquals(PeerId.client(2), notOwner.owner)
        assertTrue("dev.wildware.moba.ability.activateAbility" in notOwner.message, notOwner.message)

        // And nothing happened. A refusal that logged and fired anyway would pass every
        // assertion above.
        assertTrue(!fixture.isActive(theirs, melee), "the enemy's ability was started anyway")
        assertEquals(1L, server.refused)
        assertEquals(0L, server.accepted)
    }

    @Test
    fun `an entity no client owns is refused the same way as another client's`() {
        // Every AI unit on the field is this case. It has to be the same refusal and not a
        // fall-through: `RpcOwnership` answers PeerId.SERVER for an entity nobody owns, and a
        // guard comparing against that never matches.
        val creep = fixture.spawn("skeleton", 2f, 0f)
        fixture.step(1)

        val refusal = assertIs<RpcRefusal.NotOwner>(send(PeerId.client(1), creep, melee))
        assertEquals(PeerId.SERVER, refusal.owner)
        assertTrue(!fixture.isActive(creep, melee))
    }

    @Test
    fun `the owning client's own activation is accepted and actually fires`() {
        // Without this the refusals above could be a server that refuses everything, which is
        // secure and not a game.
        val mine = fixture.spawn("soldier", 0f, 0f)
        val target = fixture.spawn("orc", 1f, 0f)
        fixture.step(1)
        ownership.assign(mine, PeerId.client(1))

        assertNull(send(PeerId.client(1), mine, melee))
        assertEquals(ActivationResult.Activated, AbilityRpc.lastResult)
        assertEquals(1L, server.accepted)
        assertTrue(fixture.isActive(mine, melee))
        assertTrue(fixture.isLive(target))
    }

    @Test
    fun `a flood from one connection is rate limited in ticks`() {
        val mine = fixture.spawn("soldier", 0f, 0f)
        fixture.step(1)
        ownership.assign(mine, PeerId.client(1))

        // `burst = 8`, and every call is at the same tick, so the bucket never refills: the
        // ninth is refused. Ownership is not the check that stops this - the sender owns the
        // entity - which is the point of having both.
        val now = fixture.host.tick
        val refusals = (1..BURST + 1).map { send(PeerId.client(1), mine, melee, now) }
        assertTrue(refusals.take(BURST).all { it !is RpcRefusal.RateLimited }, refusals.toString())
        val limited = assertIs<RpcRefusal.RateLimited>(refusals.last())
        assertEquals("@Rpc(ratePerSecond = 20, burst = 8)", limited.rule)

        // A second connection has its own bucket. One client must not be able to mute another.
        val theirs = fixture.spawn("priest", 4f, 0f)
        fixture.step(1)
        ownership.assign(theirs, PeerId.client(2))
        assertTrue(send(PeerId.client(2), theirs, melee, fixture.host.tick) !is RpcRefusal.RateLimited)
    }

    @Test
    fun `an rpc index this build does not know is refused rather than dispatched`() {
        val buffer = ByteArray(DATAGRAM_BYTES)
        val writer = BitBufferWriter(buffer)
        val frames = FrameWriter(writer)
        // A hand-built frame: what a peer built from other sources, or a hostile one, sends.
        val out = frames.beginMessage(MessageType.Rpc)
        out.writeInt(0)
        frames.endMessage()

        val walker = FrameReader(buffer, 0, writer.byteLength, headerBits = 0L)
        val frame = requireNotNull(walker.next())
        // Index 99 is nonsense; the reader above wrote a small varint, so overwrite it.
        val hostile = ByteArray(DATAGRAM_BYTES)
        val hostileWriter = BitBufferWriter(hostile)
        val hostileFrames = FrameWriter(hostileWriter)
        hostileFrames.beginMessage(MessageType.Rpc).writeBits(UNKNOWN_INDEX, VARINT_BITS)
        hostileFrames.endMessage()
        val hostileWalker = FrameReader(hostile, 0, hostileWriter.byteLength, headerBits = 0L)
        val hostileFrame = requireNotNull(hostileWalker.next())

        assertEquals(MessageType.Rpc, frame.type)
        val refusal = assertIs<RpcRefusal.UnknownRpc>(
            server.receive(PeerId.client(1), hostileWalker.readerFor(hostileFrame), Tick.ZERO),
        )
        assertEquals(registry.size, refusal.known)
    }

    private companion object {
        const val TICKS_PER_SECOND = 60
        const val BURST = 8
        const val DATAGRAM_BYTES = 512

        /** A single-byte varint below 0x80, so this is a valid encoding of a nonsense index. */
        const val UNKNOWN_INDEX = 99
        const val VARINT_BITS = 8
    }
}
