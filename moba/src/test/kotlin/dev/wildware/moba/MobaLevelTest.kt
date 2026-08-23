package dev.wildware.moba

import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.moba.level.GameUnit
import dev.wildware.moba.level.Team
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.CoreModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The level, driven headlessly: the roster arrives, it is reproducible, and it fights.
 *
 * ## Why this is a test and not only the HTTP demo
 *
 * The proof this port is measured by is an agent reading `world.query_entities` before and after
 * a `time.step(600)`. That demo needs a process, a port and (in `Offscreen`) a GL driver, so a
 * machine without one stops checking the claim entirely - the "silently skipped gate" this
 * repository has been caught by before. Everything here runs anywhere: `RenderMode.Headless` is
 * the same `Simulation` with no context attached, which is the property `MobaGame` exists to make
 * true.
 */
class MobaLevelTest {

    /** A headless host with the level loaded and the swap tick already applied. */
    private fun booted(): GameHost {
        val host = MobaGame.host(RenderMode.Headless)
        MobaEntry.seed(host)
        return host
    }

    /** Units per team id, counted from the world rather than from a spawn-side counter. */
    private fun census(host: GameHost): IntArray {
        val counts = IntArray(TEAMS)
        val units = host.world.family { all(GameUnit) }
        with(host.world) {
            units.forEach { entity ->
                val team = entity[GameUnit].team
                if (team in counts.indices) counts[team]++
            }
        }
        return counts
    }

    /**
     * The old game's roster, spawned by loading the scene and nothing else.
     *
     * 5 orcs, 12 on the soldiers' side (the player's soldier, ten more and the priest) and 10
     * skeletons - the counts `example/.../level/test_level.udea.kts` declared.
     */
    @Test
    fun `the scene spawns the old roster across three teams`() {
        val host = booted()
        val counts = census(host)
        assertEquals(5, counts[Team.ORC], "orcs")
        assertEquals(12, counts[Team.SOLDIER], "the eleven soldiers and the priest")
        assertEquals(10, counts[Team.UNDEAD], "skeletons")
        assertEquals(27, counts.sum(), "the whole roster")
    }

    /**
     * The same seed lays the same field out, and the layout comes from the `Spawn` stream.
     *
     * Two independent hosts, so nothing is shared but the seed in `EngineConfig`. This is what
     * `kotlin.random.Random` in the old level made impossible - and the reason the scatter is
     * drawn from a *named* stream is that a change to how combat rolls must not move it. That
     * half cannot be asserted here without a combat roll to change; what can be, and is, is that
     * the layout is a function of the seed alone.
     */
    @Test
    fun `the layout is reproducible across boots`() {
        val first = positions(booted())
        val second = positions(booted())
        assertEquals(first, second, "two boots of the same seed laid the field out differently")
        assertTrue(first.size == 27, "expected 27 placed units, got ${first.size}")
        // Not all in one place: a scatter that always returned zero would satisfy the equality
        // above and put twenty-seven sprites on four points.
        assertTrue(first.distinct().size == first.size, "two units share a position: $first")
    }

    /**
     * Six hundred ticks - ten seconds - and the sides are not the size they started.
     *
     * This is the behavioural claim of the whole port, and the one the HTTP demo repeats from
     * outside the process. It is deliberately written as "fewer than it started with" rather than
     * a fixed number: pinning the exact survivors would make every balance edit a test failure,
     * while a side that does not shrink at all means the units never reached each other or never
     * swung.
     */
    @Test
    fun `six hundred ticks change the team counts`() {
        val host = booted()
        val before = census(host)
        host.run(TICKS)
        val after = census(host)
        assertNotEquals(before.toList(), after.toList(), "nothing died in $TICKS ticks")
        assertTrue(after.sum() < before.sum(), "the world grew: ${before.toList()} -> ${after.toList()}")
        for (team in before.indices) {
            assertTrue(
                after[team] <= before[team],
                "team ${Team.nameOf(team)} gained units: ${before.toList()} -> ${after.toList()}",
            )
        }
    }

    /**
     * A unit that dies gives its identity back, so the id space does not leak a battle at a time.
     *
     * `UnitDeathSystem` frees the net id before removing the entity; without the free, a
     * long-running server would exhaust the 65536-id space after enough fights and every later
     * spawn would fail with a message about capacity rather than about the leak.
     */
    @Test
    fun `dead units release their net ids`() {
        val host = booted()
        host.run(TICKS)
        val live = host.world.family { all(GameUnit) }.entities.size
        assertTrue(live < 27, "nothing died, so this test proves nothing about the id space")
        var reachable = 0
        host.ctx[CoreModule.NET_IDS].forEachLive { _, _ -> reachable++ }
        assertEquals(
            host.world.numEntities,
            reachable,
            "the net id index holds ids no entity answers to any more",
        )
    }

    private fun positions(host: GameHost): List<String> {
        val units = host.world.family { all(GameUnit) }
        val out = ArrayList<String>(units.entities.size)
        with(host.world) {
            units.forEach { entity ->
                val position = entity[Position]
                out += "${entity[GameUnit].kind}@${position.x},${position.y}"
            }
        }
        return out
    }

    private companion object {

        /** `Team.ORC`, `Team.SOLDIER`, `Team.UNDEAD`. */
        const val TEAMS: Int = 3

        /** Ten seconds at the default tick rate: the step the HTTP demo takes. */
        const val TICKS: Int = 600
    }
}
