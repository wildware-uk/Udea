package com.example.newgame.sim

import com.example.newgame.NewGame
import com.example.newgame.NewGameAssets
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.spatial.Drawn
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.generated.GameAssets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the window draws is decided here, in the simulation, with no window open.
 *
 * A rover says which model it is drawn with (`Drawn`) and where it stands (`Transform3D`); the
 * renderer only reads those. So a dedicated server, a client and an agent's headless instance all
 * agree about what a rover looks like - and this test can check it without a GL context.
 */
class RoverDrawnTest {

    @Test
    fun `every rover names the rover model, and stands where the rover is`() {
        val host = GameHost(RenderMode.Headless, NewGame.definition())
        host.run(TICKS)

        val slot = NewGameAssets.registry.indexOf(GameAssets.models.rover.id).value
        val world = host.world
        val rovers = world.family { all(Rover) }
        var seen = 0
        with(world) {
            rovers.forEach { entity ->
                val rover = entity[Rover]
                val drawn = entity[Drawn]
                val at = entity[Transform3D]
                assertEquals(slot, drawn.model, "a rover draws the model declared as models/rover")
                assertEquals(rover.x, at.x, "the model stands where the rover is, east")
                assertEquals(rover.y, at.y, "and north")
                seen++
            }
        }
        assertEquals(RoverSystem.ROVERS, seen, "every rover was checked")
    }

    @Test
    fun `a rover that drives off the east edge of the field comes back in at the west`() {
        val host = GameHost(RenderMode.Headless, NewGame.definition())
        // Long enough for the fastest rover to cross the whole field more than once.
        host.run(LONG_RUN)

        val positions = host.world.system<RoverSystem>().positions()
        assertTrue(
            positions.all { it >= -RoverSystem.FIELD_HALF_WIDTH && it <= RoverSystem.FIELD_HALF_WIDTH },
            "every rover is still on the field a window shows: $positions",
        )
    }

    private companion object {
        const val TICKS: Int = 90
        const val LONG_RUN: Int = 60 * 30
    }
}
