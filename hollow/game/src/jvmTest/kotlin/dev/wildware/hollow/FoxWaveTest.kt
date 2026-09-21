package dev.wildware.hollow

import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.Ticks
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.spatial.Transform3D
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Issue #251's second acceptance criterion: waves are deterministic for a seed.
 *
 * "Deterministic" is two claims, and a test of one alone passes an implementation that ignores the
 * seed altogether: the **same** seed must send the same foxes to the same places, and a **different**
 * seed must not. Both are asserted here, on every fox of the first two waves, over the real bundled
 * clearing - a fox that arrives inside a tree is pushed out by the solver, and that has to be the
 * same push on every run too.
 *
 * What is compared is where each fox stands on the tick its wave arrived, as the world holds it, and
 * which fox that is by `NetId`: a run that spawned the same positions in another order would be a
 * different game to a client.
 */
class FoxWaveTest {

    @Test
    fun `the same seed sends the same foxes to the same places`() {
        val first = arrivals(SEED)
        val second = arrivals(SEED)

        assertEquals(WAVE_ZERO + WAVE_ONE, first.size, "the first two waves did not both arrive: $first")
        assertEquals(first, second, "two matches on seed $SEED sent different waves")
    }

    @Test
    fun `a different seed sends its foxes somewhere else`() {
        val one = arrivals(SEED)
        val other = arrivals(OTHER_SEED)

        assertEquals(one.size, other.size, "the two seeds sent different numbers of foxes, which the schedule decides alone")
        assertNotEquals(one, other, "seeds $SEED and $OTHER_SEED sent identical waves: the seed is being ignored")
        // Not merely one fox a hair apart: every fox of the first wave is somewhere else.
        for (index in 0 until WAVE_ZERO) {
            assertNotEquals(one[index].place, other[index].place, "fox $index stood in the same place under both seeds")
        }
    }

    @Test
    fun `each wave is bigger than the one before, and arrives at the clearing's edge`() {
        val arrived = arrivals(SEED)

        val first = arrived.filter { it.wave == 0 }
        val second = arrived.filter { it.wave == 1 }
        assertEquals(WAVE_ZERO, first.size, "the first wave: $first")
        assertEquals(WAVE_ONE, second.size, "the second wave: $second")
        assertTrue(second.size > first.size, "the second wave was no bigger than the first")

        for (fox in arrived) {
            val (x, y) = fox.place
            val out = sqrt(x * x + y * y)
            assertTrue(
                out in EDGE_NEAREST..EDGE_FURTHEST,
                "a fox arrived $out from the middle, not at the edge (${FoxWaves.EDGE} +/- the spread): $fox",
            )
        }
    }

    @Test
    fun `no wave arrives on a tick the schedule does not name`() {
        val schedule = FoxWaves(first = Tick(10L), every = Ticks(7L))

        assertEquals(FoxWaves.NO_WAVE, schedule.waveAt(Tick(9L)))
        assertEquals(0L, schedule.waveAt(Tick(10L)))
        assertEquals(FoxWaves.NO_WAVE, schedule.waveAt(Tick(16L)))
        assertEquals(1L, schedule.waveAt(Tick(17L)))
        assertEquals(schedule.size + schedule.growth, schedule.sizeOf(1L))
    }

    /** One fox as it stood on the tick it arrived: which wave, which fox, and where. */
    private data class Arrival(val wave: Int, val id: Int, val place: Pair<Float, Float>)

    /**
     * Plays a match on [seed] through the first two waves of [SCHEDULE] and hands back every fox, in
     * `NetId` order, where it stood at the end of the tick its wave arrived.
     */
    private fun arrivals(seed: Long): List<Arrival> {
        val game = HollowGame.build(RenderMode.Headless, waves = SCHEDULE)
        try {
            HollowGame.seed(game.host, matchSeed = seed)
            val host = game.host
            val netIds = host.ctx[CoreModule.NET_IDS]
            val foxes = host.world.family { all(Fox, Transform3D) }
            val seen = HashSet<Int>()
            val arrived = ArrayList<Arrival>()
            val last = SCHEDULE.first + SCHEDULE.every.count
            while (host.tick <= last) {
                // The tick a step runs is the clock before it: the clock advances at the step's end.
                val stepped = host.tick
                host.run(1)
                val wave = SCHEDULE.waveAt(stepped)
                foxes.forEach { entity ->
                    val id = netIds.netIdOf(entity).raw
                    if (seen.add(id)) {
                        check(wave != FoxWaves.NO_WAVE) { "a fox arrived on $stepped, which is not a wave's tick" }
                        val at = entity[Transform3D]
                        arrived += Arrival(wave.toInt(), id, at.x to at.y)
                    }
                }
            }
            return arrived.sortedBy { it.id }
        } finally {
            game.close()
        }
    }

    private companion object {
        const val SEED = 251L
        const val OTHER_SEED = 252L

        /** Two short waves, close together, so a test plays them in a few hundred ticks. */
        val SCHEDULE = FoxWaves(first = Tick(30L), every = Ticks(120L), size = 3, growth = 2, cap = 24)

        const val WAVE_ZERO = 3
        const val WAVE_ONE = 5

        /**
         * The nearest and furthest a fox can stand from the middle on its first tick: the edge, less
         * or more the diagonal of the spread, and a tick of walking or a push from the solver.
         */
        val EDGE_NEAREST = FoxWaves.EDGE - FoxWaves.SPREAD * 1.415f - 0.5f
        val EDGE_FURTHEST = FoxWaves.EDGE + FoxWaves.SPREAD * 1.415f + 0.5f
    }
}
