package dev.wildware.udea.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What [Navigation] is allowed to remember between ticks, and for how long.
 *
 * Both caches are memoisation of a question about the **grid**: the flow field of routes to a goal,
 * and the next hop of an A* route. So the moment a building goes up or comes down, every entry in
 * them is an answer about ground that is no longer there, and `rebuild` has to drop the lot.
 *
 * This is here because nothing else in the suite could see the difference. A mutation that deleted
 * all four lines of cache-clearing from `rebuild` and left the grid swap in place passed 44 tests:
 * the systems ask about cells they are standing in, and a unit walking a fresh route mostly misses
 * a cache warmed by a different unit. A stale hit is rare, silent, and routes a unit through a wall
 * when it happens.
 */
class NavigationCacheTest {

    private val layout = NavGridLayout(originX = -8f, originY = -8f, cellSize = 0.5f, width = 32, height = 32)

    private fun navigation(vararg walls: FloatArray): Navigation {
        val builder = NavGridBuilder(layout)
        for (wall in walls) builder.block(wall[0], wall[1], wall[2], wall[3])
        return Navigation(builder.build())
    }

    /** A wall from the bottom edge up to y = +1, leaving the way past it above that. */
    private val wall = floatArrayOf(0f, -3.5f, 0.25f, 4.5f)

    @Test
    fun `a hop asked for before a wall went up is not answered from the cache after it`() {
        val navigation = navigation()
        // One cell short of where the wall will be: far enough out and the first step of the
        // detour is the same step as the first step straight at the goal, and the difference the
        // cache hides does not show up until the unit is on top of the wall.
        val from = navigation.grid.cellAt(-0.75f, 0f)
        val goal = navigation.grid.cellAt(4f, 0f)

        val open = navigation.nextHop(from, goal, clearanceCells = 1)
        navigation.rebuild(NavGridBuilder(layout).block(wall[0], wall[1], wall[2], wall[3]).build())
        val walled = navigation.nextHop(from, goal, clearanceCells = 1)

        // Straight at the goal before, and up over the wall after. A stale hit answers the first
        // one twice and the unit walks into the wall it was told about.
        assertEquals(navigation.grid.cellY(from), navigation.grid.cellY(open), "the open route goes straight across")
        assertNotEquals(open, walled, "the hop is still the one found on the grid that has gone")
        assertTrue(
            navigation.grid.cellY(walled) > navigation.grid.cellY(from),
            "the hop after the wall went up climbs to get round it, and answered ${navigation.grid.cellY(walled)}",
        )
    }

    @Test
    fun `a flow field swept before a wall went up is not handed out after it`() {
        val navigation = navigation()
        val goal = navigation.grid.cellAt(4f, 0f)
        val from = navigation.grid.cellAt(-4f, 0f)

        val open = navigation.flowField(goal, clearanceCells = 1)
        val openCost = open.cost(from)
        navigation.rebuild(NavGridBuilder(layout).block(wall[0], wall[1], wall[2], wall[3]).build())
        val walled = navigation.flowField(goal, clearanceCells = 1)

        assertNotEquals(open, walled, "the same field object came back over a grid it was not swept on")
        assertTrue(
            walled.cost(from) > openCost,
            "going round the wall costs ${walled.cost(from)}, which is not more than the $openCost it cost before it",
        )
        assertEquals(1, navigation.fieldSweeps, "the sweep counter is reset with the cache it counts")
    }

    @Test
    fun `a rebuild that changes nothing still drops the caches, because it is the grid that owns them`() {
        // Not an optimisation to be tempted by: `NavGridSystem` decides *whether* to rebuild by
        // comparing footprints, so a `rebuild` call means the grid really has been replaced, and
        // `Navigation` holding entries across one would be holding them against a different object.
        val navigation = navigation(wall)
        val goal = navigation.grid.cellAt(4f, 0f)
        val before = navigation.flowField(goal, clearanceCells = 1)
        assertEquals(1, navigation.fieldSweeps)

        navigation.rebuild(NavGridBuilder(layout).block(wall[0], wall[1], wall[2], wall[3]).build())
        val after = navigation.flowField(goal, clearanceCells = 1)

        // The two fields hold the same numbers, so only their identity can tell them apart: the
        // second is swept over the grid that is standing, and the first is not.
        assertNotEquals(before, after, "the field was handed out from the cache of the previous grid")
        assertEquals(1, navigation.fieldSweeps, "the sweep counter belongs to the grid, and starts again with it")
        assertEquals(before.cost(navigation.grid.cellAt(-4f, 0f)), after.cost(navigation.grid.cellAt(-4f, 0f)))
    }
}
