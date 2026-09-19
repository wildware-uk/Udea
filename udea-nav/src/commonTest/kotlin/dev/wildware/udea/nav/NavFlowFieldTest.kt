package dev.wildware.udea.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One integration field per goal: what a hundred units ordered to the same place all read.
 */
class NavFlowFieldTest {

    /** A 12x12 grid with a wall down column 5, open at row 0. */
    private fun walled(): NavGrid {
        val builder = NavGridBuilder(NavGridLayout(originX = 0f, originY = 0f, cellSize = 1f, width = 12, height = 12))
        for (row in 1 until 12) builder.blockCells(5, row, 5, row)
        return builder.build()
    }

    @Test
    fun `following the next hops from anywhere arrives at the goal`() {
        val grid = walled()
        val goal = grid.cellOf(11, 11)
        val field = NavFlowField(grid, goal, clearanceCells = 1)

        for (index in 0 until grid.cellCount) {
            val start = NavCell(index)
            if (!field.reaches(start)) continue
            var cursor = start
            var steps = 0
            while (cursor != goal) {
                val next = field.nextHop(cursor)
                assertTrue(next.isValid, "no hop out of $cursor")
                assertTrue(field.cost(next) < field.cost(cursor), "hop out of $cursor does not descend")
                cursor = next
                steps++
                assertTrue(steps <= grid.cellCount, "walk from $start did not terminate")
            }
        }
    }

    @Test
    fun `a cell the goal cannot be reached from has no cost and no hop`() {
        // A goal boxed in by footprints on all four sides.
        val builder = NavGridBuilder(NavGridLayout(originX = 0f, originY = 0f, cellSize = 1f, width = 9, height = 9))
        for (offset in 3..5) {
            builder.blockCells(offset, 3, offset, 3)
            builder.blockCells(offset, 5, offset, 5)
        }
        builder.blockCells(3, 4, 3, 4)
        builder.blockCells(5, 4, 5, 4)
        val grid = builder.build()
        val field = NavFlowField(grid, grid.cellOf(4, 4), clearanceCells = 1)

        assertFalse(field.reaches(grid.cellOf(0, 0)))
        assertEquals(NavCell.NONE, field.nextHop(grid.cellOf(0, 0)))
        assertTrue(field.reaches(grid.cellOf(4, 4)))
    }

    @Test
    fun `the field agrees with A* about what a route costs`() {
        val grid = walled()
        val goal = grid.cellOf(11, 11)
        val field = NavFlowField(grid, goal, clearanceCells = 1)
        val pathfinder = NavPathfinder(grid)
        val path = NavPath()

        for (column in 0 until 12 step 3) {
            for (row in 0 until 12 step 3) {
                val start = grid.cellOf(column, row)
                if (!grid.fits(start, 1)) continue
                assertEquals(
                    pathfinder.findPath(start, goal, clearanceCells = 1, out = path),
                    field.reaches(start),
                    "A* and the field disagree about whether $start can reach the goal",
                )
                if (field.reaches(start)) {
                    assertEquals(path.cost, field.cost(start), "A* and the field disagree about $start")
                }
            }
        }
    }

    @Test
    fun `a wide unit's field does not route through a narrow gap`() {
        // A wall down column 4 with one open cell: a unit needing two cells of room cannot pass.
        val builder = NavGridBuilder(NavGridLayout(originX = 0f, originY = 0f, cellSize = 1f, width = 9, height = 9))
        for (row in 0 until 9) if (row != 4) builder.blockCells(4, row, 4, row)
        val grid = builder.build()

        assertTrue(NavFlowField(grid, grid.cellOf(8, 4), clearanceCells = 1).reaches(grid.cellOf(0, 4)))
        assertFalse(NavFlowField(grid, grid.cellOf(8, 4), clearanceCells = 2).reaches(grid.cellOf(0, 4)))
    }

    @Test
    fun `a goal a unit cannot stand on reaches nothing`() {
        val grid = NavGridBuilder(NavGridLayout(originX = 0f, originY = 0f, cellSize = 1f, width = 7, height = 7))
            .blockCells(3, 3, 3, 3)
            .build()
        val field = NavFlowField(grid, grid.cellOf(3, 3), clearanceCells = 1)

        assertFalse(field.reaches(grid.cellOf(0, 0)))
        assertFalse(field.reaches(grid.cellOf(3, 3)))
    }
}
