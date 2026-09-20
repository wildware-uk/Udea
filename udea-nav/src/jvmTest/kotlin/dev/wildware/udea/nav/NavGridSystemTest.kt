package dev.wildware.udea.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The grid follows the buildings: placed, moved, destroyed - and a unit re-routes around what has
 * changed without being told.
 */
class NavGridSystemTest {

    private fun scene(): NavScene = NavScene(
        NavGridLayout(originX = -16f, originY = -16f, cellSize = 0.5f, width = 64, height = 64),
    )

    @Test
    fun `a building placed this tick blocks the ground it stands on`() {
        val scene = scene()
        val grid = scene.navigation.grid
        val middle = grid.cellAt(0f, 0f)
        scene.step()
        assertFalse(scene.navigation.grid.isBlocked(middle), "open ground before anything was built")

        scene.spawnBuilding(x = 0f, y = 0f, halfWidth = 1f, halfDepth = 1f)
        scene.step()

        assertTrue(scene.navigation.grid.isBlocked(middle))
    }

    @Test
    fun `a building destroyed opens the ground again`() {
        val scene = scene()
        val building = scene.spawnBuilding(x = 0f, y = 0f, halfWidth = 1f, halfDepth = 1f)
        scene.step()
        val middle = scene.navigation.grid.cellAt(0f, 0f)
        assertTrue(scene.navigation.grid.isBlocked(middle))

        scene.destroy(building)
        scene.step()

        assertFalse(scene.navigation.grid.isBlocked(middle))
    }

    @Test
    fun `a tick that changes no building leaves the grid alone`() {
        // The grid is rebuilt by comparing what is standing against what it was built from, so a
        // tick where nothing was built must not rebuild - a rebuild throws away every cached route.
        val scene = scene()
        scene.spawnBuilding(x = 0f, y = 0f, halfWidth = 1f, halfDepth = 1f)
        scene.step()
        val built = scene.navigation.grid

        repeat(10) { scene.step() }

        assertTrue(built === scene.navigation.grid, "the grid was rebuilt on a tick nothing was built")
    }

    @Test
    fun `a wall built across a unit's route sends it round the other way`() {
        val scene = scene()
        val unit = scene.spawnUnit(x = -10f, y = 0f)
        scene.order(unit, x = 10f, y = 0f)
        repeat(120) { scene.step() }
        val halfway = scene.transformOf(unit).x
        assertTrue(halfway > -10f, "it set off")

        // A wall from the bottom of the map up to y = 2, so the only way past is over the top.
        for (index in 0 until 36) {
            scene.spawnBuilding(x = 2f, y = -16f + index * 0.5f + 0.25f, halfWidth = 0.5f, halfDepth = 0.25f)
        }
        repeat(600) { scene.step() }

        val transform = scene.transformOf(unit)
        assertEquals(NavState.Arrived, scene.agentOf(unit).state, "it found the way round")
        assertTrue(transform.x > 9f, "it got past the wall, ending at x = ${transform.x}")
    }

    @Test
    fun `a unit a building is put on top of walks out from under it`() {
        val scene = scene()
        val unit = scene.spawnUnit(x = 0f, y = 0f)
        scene.order(unit, x = 0.2f, y = 0f)
        scene.step()

        scene.spawnBuilding(x = 0f, y = 0f, halfWidth = 1.5f, halfDepth = 1.5f)
        repeat(240) { scene.step() }

        val transform = scene.transformOf(unit)
        val grid = scene.navigation.grid
        assertFalse(
            grid.isBlocked(grid.cellAt(transform.x, transform.y)),
            "it is still under the building, at (${transform.x}, ${transform.y})",
        )
    }
}
