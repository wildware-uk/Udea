package dev.wildware.moba.net

import dev.wildware.moba.ability.Combatant
import dev.wildware.moba.level.GameUnit
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import com.github.quillraven.fleks.World.Companion.family
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A component the server takes **off** an entity comes off the client's entity too.
 *
 * ## The defect this closes, in two halves
 *
 * `moba` drops `Combatant` when a unit dies and adds it back on respawn. That was one of the two
 * entries in `MobaUdpProof.EXCUSED_COMPONENTS`, and it took two independent fixes:
 *
 *  1. **The wire had no removal op.** `SnapshotSection.writeEntity` only ever walked the
 *     components an entity *currently* carried, so a component in the baseline and absent now
 *     produced no bytes at all. `udea-net`'s `ComponentRemovalTest` covers that half: an
 *     all-zero field mask is now a removal record.
 *  2. **`ReplicaApplier` ignored it anyway.** `if (slot == ABSENT) continue` - a store row that
 *     had stopped holding a component simply skipped it, so nothing ever called
 *     `ReplicatedComponentType.removeFrom` and the Fleks component stayed attached for ever.
 *     Fixing (1) alone would have changed nothing observable in this game.
 *
 * This is the assertion over both halves at once, in the real game: server world, real wire, and
 * the *client's Fleks world* rather than its decode buffer - which is the only place half (2)
 * shows up.
 *
 * The vacuous-pass guard matters as much as the assertion. If nothing had died in the run, every
 * check below would hold trivially, so the count of dead units is asserted to be non-zero first.
 */
class MobaComponentRemovalTest {

    @Test
    fun `a Combatant dropped on death is dropped on the client too`() {
        MobaLoopbackSession(clientCount = 1).use { session ->
            session.step(TICKS)

            val server = session.server.host
            val serverIds = server.ctx[CoreModule.NET_IDS]
            val dead = ArrayList<NetId>()
            val alive = ArrayList<NetId>()
            with(server.world) {
                val units = server.world.family { all(GameUnit) }.entities
                for (index in 0 until units.size) {
                    val entity = units[index]
                    val netId = serverIds.netIdOf(entity)
                    if (entity.getOrNull(Combatant) == null) dead += netId else alive += netId
                }
            }

            assertTrue(
                dead.isNotEmpty(),
                "no unit lost its Combatant in $TICKS ticks, so this test asserted nothing; the " +
                    "battle either did not start or nobody died",
            )
            assertTrue(alive.isNotEmpty(), "every unit is dead, which is not the case being tested")

            val client = session.clients[0].host
            val clientIds = client.ctx[CoreModule.NET_IDS]
            var checkedDead = 0
            var checkedAlive = 0
            with(client.world) {
                for (netId in dead) {
                    val entity = clientIds.resolveOrNull(netId) ?: continue
                    checkedDead++
                    assertEquals(
                        null,
                        entity.getOrNull(Combatant),
                        "$netId lost its Combatant on the server and still carries one on the " +
                            "client: a component removal reached the store and stopped there",
                    )
                }
                for (netId in alive) {
                    val entity = clientIds.resolveOrNull(netId) ?: continue
                    checkedAlive++
                    assertTrue(
                        entity.getOrNull(Combatant) != null,
                        "$netId still has its Combatant on the server and lost it on the client: " +
                            "the removal path is removing more than it was told to",
                    )
                }
            }
            assertTrue(checkedDead > 0, "the client held none of the dead units, so nothing was checked")
            assertTrue(checkedAlive > 0, "the client held none of the live units, so nothing was checked")
            assertTrue(
                session.clients[0].applier.componentsRemoved > 0L,
                "the applier never removed a component, so the assertions above passed because " +
                    "the client never held one",
            )
        }
    }

    private companion object {

        /** Long enough for the battle to have killed somebody. Measured, not guessed. */
        const val TICKS: Int = 400
    }
}
