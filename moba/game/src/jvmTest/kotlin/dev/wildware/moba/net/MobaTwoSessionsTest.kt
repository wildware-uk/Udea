package dev.wildware.moba.net

import dev.wildware.moba.ability.AbilityRpc
import dev.wildware.moba.ability.ActivateAbilityRpc
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.gas.Abilities
import dev.wildware.udea.net.bits.BitBufferWriter
import dev.wildware.udea.net.rpc.RpcOutbox
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.net.wire.FrameWriter
import dev.wildware.udea.net.wire.PacketHeader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Two authoritative sessions in one JVM, each activating on its own world.**
 *
 * ## The defect
 *
 * `AbilityRpc.sink` is process state. An `@Rpc` body is a top-level function - a datagram carries
 * arguments and no receiver - so the generated descriptor calls `activateAbility(self, slot)`,
 * which reaches a world through one process-wide binding. Two [MobaHostSession]s in one JVM both
 * bound it at construction, so the second one to be built silently owned it: a datagram accepted
 * by session A's ownership guard was executed against session B's world. Both worlds seed the
 * same level, so the ids resolve and **the wrong champion swings**, with no error anywhere.
 *
 * That is worth more than tidiness. It blocked exactly this test, it blocks any in-process
 * arrangement with a second server in it, and a silent wrong-world write is the class of defect
 * that only shows up as somebody's ability firing for no reason.
 *
 * ## The fix, and its stated limit
 *
 * `MobaHostSession.dispatchRpcs` rebinds the sink around each `rpc.receive` call and puts back
 * whatever was there, so the global narrows to the dynamic extent of one dispatch. `close` hands
 * it back only if it is still ours, so one session closing cannot deafen another that is still
 * running.
 *
 * It is **not thread-safe**, and this test does not claim it is: two sessions dispatching
 * concurrently on different threads would still interleave. Closing that needs `activateAbility`
 * to be handed a session, which is a change to the generated RPC signature in `udea-codegen`.
 * Every driver in this tree dispatches on one thread.
 */
class MobaTwoSessionsTest {

    @Test
    fun `an activation reaches the session that accepted it, not the last one constructed`() {
        MobaLoopbackSession(clientCount = 1).use { first ->
            MobaLoopbackSession(clientCount = 1).use { second ->
                first.step(WARMUP)
                second.step(WARMUP)
                val mine = assertNotNull(first.server.championOf(PeerId.client(1)))
                val theirs = assertNotNull(second.server.championOf(PeerId.client(1)))
                // Same level, same seeding order, so the two ids are the same number in two
                // different worlds - which is exactly why a misrouted activation resolves cleanly
                // and fires the wrong champion instead of failing.
                assertEquals(mine, theirs, "the two sessions no longer seed identical levels")

                val before = activatedTick(second, theirs)
                assertNull(attack(first, mine), "the owning client was refused its own champion")

                assertTrue(
                    activatedTick(first, mine) > NEVER,
                    "the accepted activation did not reach the session that accepted it; " +
                        "AbilityRpc.lastResult=${AbilityRpc.lastResult}",
                )
                assertEquals(
                    before,
                    activatedTick(second, theirs),
                    "the activation fired in the other session's world",
                )
                assertEquals(1L, first.server.rpc.accepted)
                assertEquals(0L, second.server.rpc.accepted)
            }
        }
    }

    @Test
    fun `closing one session leaves the other one able to fire`() {
        // `AbilityRpc.unbind` used to be unconditional, so the second session's teardown pointed
        // the process at `UNBOUND` and every activation on the first - which is still running and
        // still has players on it - answered `NoAuthority`. A game that quietly stops accepting
        // attacks is worse than one that crashes.
        MobaLoopbackSession(clientCount = 1).use { survivor ->
            MobaLoopbackSession(clientCount = 1).use { doomed -> doomed.step(WARMUP) }
            survivor.step(WARMUP)
            val champion = assertNotNull(survivor.server.championOf(PeerId.client(1)))
            assertNull(attack(survivor, champion), "the surviving session refused its own client")
            assertTrue(activatedTick(survivor, champion) > NEVER, "nothing fired")
            assertTrue(AbilityRpc.lastResult.isActivated, "the activation was refused: ${AbilityRpc.lastResult}")
        }
    }

    /** The tick [champion]'s basic attack was last started in [live]'s world, or [NEVER]. */
    private fun activatedTick(live: MobaLoopbackSession, champion: NetId): Long {
        val entity = assertNotNull(
            live.server.host.ctx[CoreModule.NET_IDS].resolveOrNull(champion),
            "$champion is not in this session's world",
        )
        return with(live.server.host.world) {
            entity.getOrNull(Abilities)?.instanceAt(MELEE)?.activatedTick?.value ?: NEVER
        }
    }

    /** One `activateAbility` datagram from client 1, written and fed in exactly as a client's is. */
    private fun attack(live: MobaLoopbackSession, self: NetId) = run {
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
        ActivateAbilityRpc.send(FrameWriter(writer), RpcOutbox(live.server.rpcRegistry), self, MELEE)
        live.server.onPacket(PeerId.client(1), buffer, 0, writer.byteLength)
    }

    private companion object {

        /**
         * Two ticks: the barrier has drained the level and every champion is fresh.
         *
         * Deliberately short. Thirty ticks into the real battle the champion is mid-swing and the
         * activation is refused `BlockedByTag` for perfectly good gameplay reasons, which would
         * make this test unable to tell "the sink reached the wrong world" from "the ability was
         * busy". What is under test is where the call lands, so the champion is kept idle.
         */
        const val WARMUP = 2

        /** Slot 0, the melee every unit in this game is granted first. */
        const val MELEE = 0

        /** `Tick` an ability that has never been started carries. */
        const val NEVER = 0L

        const val DATAGRAM_BYTES = 512
    }
}
