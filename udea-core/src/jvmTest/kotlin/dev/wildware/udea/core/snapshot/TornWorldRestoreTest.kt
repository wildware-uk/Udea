package dev.wildware.udea.core.snapshot

import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.loop.BarrierAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A restore is a between-tick mutation, and no system ever sees half of one.
 *
 * The old engine had the opposite shape: level entities were spawned inside a
 * `Gdx.app.postRunnable` from `GameScreen`'s init (`common/UdeaGameManager.kt:191`), guarded by
 * a `started` flag, so the world was silently empty for a frame or more and nothing in the type
 * system said so. `SimBarrier` exists to make that unrepresentable, and a snapshot restore is
 * the largest mutation there is, so it is the one worth proving.
 *
 * Every case here stamps a value onto every entity, because "torn" is only observable as *two*
 * generations of state alive at once. A single-entity test could not tell a torn world from a
 * whole one.
 */
class TornWorldRestoreTest {

    @Test
    fun `submitting a restore changes nothing until the next step`() {
        val sim = SnapshotWorld()
        val ids = sim.spawn(8)
        stampAll(sim, ids, CAPTURED)
        val captured = sim.service.capture()
        stampAll(sim, ids, MUTATED)

        sim.service.restore(captured, sim.barrier)

        assertEquals(1, sim.barrier.pendingCount(), "the restore must be queued, not applied")
        assertTrue(
            ids.all { stampOf(sim, it) == MUTATED },
            "the caller's thread must not have mutated the world",
        )

        sim.step()

        assertEquals(0, sim.barrier.pendingCount())
        assertTrue(
            ids.all { stampOf(sim, it) == CAPTURED },
            "the restore should have landed at the top of the step",
        )
    }

    @Test
    fun `a probe system running every tick never observes a half-applied world`() {
        val sim = SnapshotWorld(withProbe = true)
        val probe = checkNotNull(sim.probe)
        val ids = sim.spawn(24)

        stampAll(sim, ids, CAPTURED)
        val captured = sim.service.capture()
        stampAll(sim, ids, MUTATED)

        sim.step()
        sim.service.restore(captured, sim.barrier)
        repeat(3) { sim.step() }

        assertEquals(4, probe.observations.size, "the probe must have run on every tick")
        for (observation in probe.observations) {
            assertEquals(
                1,
                observation.size,
                "a system observed a torn world: stamps $observation were live at once",
            )
        }
        assertEquals(
            listOf(setOf(MUTATED), setOf(CAPTURED), setOf(CAPTURED), setOf(CAPTURED)),
            probe.observations,
            "the restore must be visible from the first tick after it was queued, and not before",
        )
    }

    @Test
    fun `a probe never sees a partly rebuilt entity set either`() {
        val sim = SnapshotWorld(withProbe = true)
        val probe = checkNotNull(sim.probe)
        val ids = sim.spawn(10)
        val captured = sim.service.capture()

        sim.destroy(ids[3])
        sim.destroy(ids[7])
        repeat(4) { sim.spawnMovementOnly(x = 1f) }

        sim.step()
        sim.service.restore(captured, sim.barrier)
        sim.step()

        // 12 entities carry Vitals before the restore (10 spawned, 2 destroyed, 4 added without
        // Vitals), and exactly 10 after it. Never 11, which is what a torn rebuild would show.
        assertEquals(listOf(8, 10), probe.vitalsCounts)
    }

    @Test
    fun `a restore submitted from inside a barrier action lands on the following tick`() {
        // The barrier's own rule: work submitted during a drain goes to the next tick's queue,
        // so a restore can never re-enter the drain that is running.
        val sim = SnapshotWorld()
        val ids = sim.spawn(4)
        stampAll(sim, ids, CAPTURED)
        val captured = sim.service.capture()
        stampAll(sim, ids, MUTATED)

        sim.barrier.submit(object : BarrierAction {
            override val label: String get() = "queue a restore"

            override fun apply(world: World, ctx: GameContext) {
                sim.service.restore(captured, sim.barrier)
            }
        })

        sim.step()
        assertTrue(
            ids.all { stampOf(sim, it) == MUTATED },
            "the inner restore must not have run inside the same drain",
        )

        sim.step()
        assertTrue(ids.all { stampOf(sim, it) == CAPTURED })
    }

    private fun stampAll(sim: SnapshotWorld, ids: List<NetId>, stamp: Int) {
        for (id in ids) {
            with(sim.world) { checkNotNull(sim.netIds.resolveOrNull(id))[Vitals] }.shieldCharges = stamp
        }
    }

    private fun stampOf(sim: SnapshotWorld, id: NetId): Int =
        with(sim.world) { checkNotNull(sim.netIds.resolveOrNull(id))[Vitals] }.shieldCharges

    private companion object {
        const val CAPTURED: Int = 1
        const val MUTATED: Int = 7
    }
}

/** Records what the world looked like on each tick it ran: distinct stamps, and how many entities. */
internal class StampProbe : SimSystem() {

    private val family: Family = world.family { all(Vitals) }

    /** The set of distinct stamps live at each tick. More than one means a torn world. */
    val observations: MutableList<Set<Int>> = ArrayList()

    /** How many entities carried [Vitals] at each tick. */
    val vitalsCounts: MutableList<Int> = ArrayList()

    override fun onTick() {
        val seen = LinkedHashSet<Int>()
        val entities = family.entities
        var index = 0
        while (index < entities.size) {
            seen += entities[index][Vitals].shieldCharges
            index++
        }
        observations += seen
        vitalsCounts += entities.size
    }
}
