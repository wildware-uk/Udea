package com.example.newgame.sim

import com.example.newgame.NewGame
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The game's rules, tested the way every Udea game's are: a real world, a real tick loop, no
 * window and no GL context.
 *
 * This is the test a new game starts from. It needs no harness of its own because `GameHost` in
 * `RenderMode.Headless` *is* the harness - the same simulation a player's client runs, with
 * nothing drawing it.
 */
class RoverSystemTest {

    @Test
    fun `a rover moves east by its own speed, once per tick`() {
        val host = GameHost(RenderMode.Headless, NewGame.definition())

        host.run(TICKS)

        val rovers = host.world.system<RoverSystem>()
        assertEquals(RoverSystem.ROVERS, rovers.positions().size, "every rover is still there")
        assertEquals(
            RoverSystem.ROVERS.toLong() * TICKS,
            rovers.updates,
            "every rover was updated on every tick",
        )
        // 60Hz, so a tick is 1/60s: the slowest rover covers TICKS/60 metres and the next one
        // twice that. Asserted as an ordering rather than an exact float, because the point is
        // that speed decides distance.
        val distances = rovers.positions()
        assertTrue(distances[0] < distances[1], "a faster rover has gone further: $distances")
        assertTrue(distances[0] > 0f, "the first rover moved at all: $distances")
    }

    private companion object {
        const val TICKS: Int = 120
    }
}
