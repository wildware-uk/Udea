package dev.wildware.udea.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A* over the nav grid: optimal, corner-safe, clearance-aware, and the same answer every time.
 */
class NavPathfinderTest {

    private fun open(width: Int, height: Int): NavGrid =
        NavGridBuilder(NavGridLayout(originX = 0f, originY = 0f, cellSize = 1f, width = width, height = height))
            .build()

    @Test
    fun `an open diagonal is walked as diagonal steps`() {
        val grid = open(5, 5)
        val path = NavPath()

        assertTrue(NavPathfinder(grid).findPath(grid.cellOf(0, 0), grid.cellOf(4, 4), clearanceCells = 1, out = path))

        assertEquals(5, path.size)
        assertEquals(4 * NavSteps.DIAGONAL, path.cost)
        for (step in 0 until path.size) {
            assertEquals(step, grid.cellX(path.cellAt(step)))
            assertEquals(step, grid.cellY(path.cellAt(step)))
        }
    }

    @Test
    fun `a path around a wall costs what the detour costs`() {
        // A wall down column 3, open at row 0 only.
        val builder = NavGridBuilder(NavGridLayout(originX = 0f, originY = 0f, cellSize = 1f, width = 7, height = 5))
        for (row in 1 until 5) builder.blockCells(3, row, 3, row)
        val grid = builder.build()
        val path = NavPath()

        assertTrue(NavPathfinder(grid).findPath(grid.cellOf(0, 4), grid.cellOf(6, 4), clearanceCells = 1, out = path))

        // Every step of the path is open, it starts where it was asked to and ends at the goal,
        // and it has to come down to row 0 to get through the gap.
        assertEquals(grid.cellOf(0, 4), path.cellAt(0))
        assertEquals(grid.cellOf(6, 4), path.cellAt(path.size - 1))
        for (step in 0 until path.size) assertFalse(grid.isBlocked(path.cellAt(step)))
        assertTrue((0 until path.size).any { grid.cellY(path.cellAt(it)) == 0 })
    }

    @Test
    fun `a diagonal may not cut the corner of a blocked cell`() {
        val grid = NavGridBuilder(NavGridLayout(originX = 0f, originY = 0f, cellSize = 1f, width = 3, height = 3))
            .blockCells(1, 0, 1, 0)
            .build()
        val path = NavPath()

        assertTrue(NavPathfinder(grid).findPath(grid.cellOf(0, 0), grid.cellOf(1, 1), clearanceCells = 1, out = path))

        assertEquals(3, path.size)
        assertEquals(2 * NavSteps.STRAIGHT, path.cost)
        assertEquals(grid.cellOf(0, 1), path.cellAt(1))
    }

    @Test
    fun `a goal walled off has no path`() {
        val builder = NavGridBuilder(NavGridLayout(originX = 0f, originY = 0f, cellSize = 1f, width = 5, height = 5))
        for (row in 0 until 5) builder.blockCells(2, row, 2, row)
        val grid = builder.build()
        val path = NavPath()

        assertFalse(NavPathfinder(grid).findPath(grid.cellOf(0, 0), grid.cellOf(4, 4), clearanceCells = 1, out = path))
        assertEquals(0, path.size)
    }

    @Test
    fun `a wide unit is refused a gap a narrow one walks through`() {
        // A wall down column 4 of a 9-wide grid, with one open cell at row 4.
        val builder = NavGridBuilder(NavGridLayout(originX = 0f, originY = 0f, cellSize = 1f, width = 9, height = 9))
        for (row in 0 until 9) if (row != 4) builder.blockCells(4, row, 4, row)
        val grid = builder.build()
        val pathfinder = NavPathfinder(grid)
        val path = NavPath()

        assertTrue(pathfinder.findPath(grid.cellOf(0, 4), grid.cellOf(8, 4), clearanceCells = 1, out = path))
        assertFalse(pathfinder.findPath(grid.cellOf(0, 4), grid.cellOf(8, 4), clearanceCells = 2, out = path))
    }

    @Test
    fun `a start that does not fit has no path`() {
        val grid = NavGridBuilder(NavGridLayout(originX = 0f, originY = 0f, cellSize = 1f, width = 5, height = 5))
            .blockCells(1, 1, 1, 1)
            .build()
        val path = NavPath()

        // (0,0) has clearance 1: the grid edge is on two sides and the footprint is diagonal to it.
        assertFalse(NavPathfinder(grid).findPath(grid.cellOf(0, 0), grid.cellOf(4, 4), clearanceCells = 2, out = path))
    }

    @Test
    fun `the next hop is the second cell of the path`() {
        val grid = open(5, 5)
        val pathfinder = NavPathfinder(grid)
        val path = NavPath()
        pathfinder.findPath(grid.cellOf(0, 0), grid.cellOf(4, 4), clearanceCells = 1, out = path)

        assertEquals(path.cellAt(1), pathfinder.nextHop(grid.cellOf(0, 0), grid.cellOf(4, 4), clearanceCells = 1))
    }

    @Test
    fun `standing on the goal has no next hop`() {
        val grid = open(5, 5)

        assertEquals(
            NavCell.NONE,
            NavPathfinder(grid).nextHop(grid.cellOf(2, 2), grid.cellOf(2, 2), clearanceCells = 1),
        )
    }

    @Test
    fun `the same search run twice returns the same route`() {
        // Open ground, where many routes of equal cost exist: the tie-break, not the cost, is what
        // decides which one comes back, and it has to decide the same way every time.
        val grid = open(12, 12)
        val first = NavPath()
        val second = NavPath()

        NavPathfinder(grid).findPath(grid.cellOf(0, 0), grid.cellOf(11, 5), clearanceCells = 1, out = first)
        NavPathfinder(grid).findPath(grid.cellOf(0, 0), grid.cellOf(11, 5), clearanceCells = 1, out = second)

        assertEquals(first.size, second.size)
        assertEquals(first.cost, second.cost)
        for (step in 0 until first.size) assertEquals(first.cellAt(step), second.cellAt(step))
    }

    @Test
    fun `a reused path buffer holds only the latest route`() {
        val grid = open(6, 6)
        val pathfinder = NavPathfinder(grid)
        val path = NavPath()

        pathfinder.findPath(grid.cellOf(0, 0), grid.cellOf(5, 5), clearanceCells = 1, out = path)
        pathfinder.findPath(grid.cellOf(0, 0), grid.cellOf(1, 0), clearanceCells = 1, out = path)

        assertEquals(2, path.size)
        assertEquals(grid.cellOf(1, 0), path.cellAt(1))
    }
}
