package dev.wildware.moba.net

import dev.wildware.moba.Position
import dev.wildware.moba.level.GameUnit
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.net.wire.ReplicaStore
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fog of war **in the game**, not in the engine's own harness.
 *
 * `udea-net` proves the mechanism: the grid, the hysteresis band, the linger, and an anti-cheat
 * sweep over every raw datagram. What none of that can say is whether a *game* can turn it on -
 * whether `moba`'s own components carry a team and a position the solve can read, whether the
 * solve happens at a point in the tick where the positions are the ones about to be sent, and
 * whether a client whose champion is the only vision source ends up holding a plausible slice of
 * the roster rather than nothing or everything.
 *
 * ## What is asserted, and why each arm is needed
 *
 * The **on** arm alone proves nothing: a client that received zero entities because replication
 * was broken would pass "the far camp is absent" perfectly. So the two arms run the identical
 * session and differ only in the radius:
 *
 *  - **off** - the client holds the whole 28-unit roster, which is what every other proof asserts
 *    and what says this session is healthy at all.
 *  - **on** - the client holds strictly fewer, holds *something*, and every unit it does hold is
 *    within the sight radius (plus the hysteresis band and the linger, both of which legitimately
 *    keep a unit a little past the edge - so the bound is generous on purpose; a leak is an order
 *    of magnitude, not a rounding error).
 */
class MobaFogTest {

    @Test
    fun `with no fog a client holds the whole roster`() {
        MobaLoopbackSession(clientCount = 1).use { session ->
            session.step(TICKS)
            assertEquals(
                serverUnits(session),
                clientUnits(session),
                "the control arm must hold the whole roster, or the fog arm proves nothing",
            )
        }
    }

    @Test
    fun `with fog on a client holds only what its champion can see`() {
        MobaLoopbackSession(clientCount = 1, fogSight = SIGHT).use { session ->
            session.step(TICKS)

            val held = clientUnits(session)
            val all = serverUnits(session)
            assertTrue(held > 0, "the client received nothing at all, so this proves only breakage")
            assertTrue(
                held < all,
                "fog is on and the client still holds all $all units: the relevancy set is not " +
                    "reaching the packer",
            )

            val champion = session.server.playerId
            val eye = positionOf(session, champion)
            for (netId in session.clients[0].replication.world.liveNetIds()) {
                // The *server's* position, deliberately: the question is whether the client was
                // told about something far away, and the client's own copy of a leaked entity is
                // whatever stale value it happens to hold.
                val here = runCatching { positionOf(session, netId) }.getOrNull() ?: continue
                val distance = hypot(here.first - eye.first, here.second - eye.second)
                assertTrue(
                    distance <= SIGHT * LEEWAY,
                    "the client holds $netId, which the server has $distance units from its only " +
                        "vision source; the radius is $SIGHT, so state it may not see reached " +
                        "its own store",
                )
            }
        }
    }

    /** `GameUnit`s the server's world holds. */
    private fun serverUnits(session: MobaLoopbackSession): Int =
        NetStateProbe.unitCount(session.server.host.world)

    /** `GameUnit` rows the client decoded. Counted off the store, not off a Fleks world. */
    private fun clientUnits(session: MobaLoopbackSession): Int {
        val store = session.clients[0].replication.world
        val component = componentIndex(session, GameUnit::class.java)
        var held = 0
        for (netId in store.liveNetIds()) {
            val row = store.rowOf(netId)
            if (store.slotOf(row, component) != ReplicaStore.ABSENT) held++
        }
        return held
    }

    /** The server's position for [netId]. */
    private fun positionOf(session: MobaLoopbackSession, netId: dev.wildware.udea.core.identity.NetId): Pair<Float, Float> {
        val world = session.server.host.world
        val entity = session.server.host.ctx[CoreModule.NET_IDS].resolveOrNull(netId)
            ?: error("the server holds no entity for $netId")
        return with(world) { entity[Position].let { it.x to it.y } }
    }

    /** The registry index of [componentClass]. The registry is the same list on both peers. */
    private fun componentIndex(session: MobaLoopbackSession, componentClass: Class<*>): Int {
        val registry = session.clients[0].registry
        for (index in 0 until registry.size) {
            if (registry.typeAt(index).componentClass.java == componentClass) return index
        }
        error("this session's registry has no ${componentClass.simpleName}")
    }

    private companion object {

        /** Long enough for the priority accumulator to have offered every unit several times. */
        const val TICKS: Int = 120

        /** Tight enough that the far camp at `(100, 0)` is well outside it. */
        const val SIGHT: Float = 40f

        /**
         * How far past the radius a unit may legitimately still be held.
         *
         * `FogSettings` leaves at `1.1 * radius` and lingers six ticks after that, and a unit
         * walks while it lingers. Doubling the radius is deliberately loose: this assertion is
         * hunting a leak, which is a unit across the map, not a unit a step past the edge.
         */
        const val LEEWAY: Float = 2f
    }
}
