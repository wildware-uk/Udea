package dev.wildware.udea.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ground plane as cells: where a world point lands, what a footprint blocks, and how much
 * room a cell has.
 */
class NavGridTest {

    @Test
    fun `a world point lands in the cell that contains it`() {
        val grid = NavGridBuilder(NavGridLayout(originX = -5f, originY = -5f, cellSize = 1f, width = 10, height = 10)).build()

        val cell = grid.cellAt(-4.5f, -4.5f)

        assertEquals(0, grid.cellX(cell))
        assertEquals(0, grid.cellY(cell))
        assertEquals(-4.5f, grid.centreX(cell))
        assertEquals(-4.5f, grid.centreY(cell))
    }

    @Test
    fun `a point outside the grid has no cell`() {
        val grid = NavGridBuilder(NavGridLayout(originX = 0f, originY = 0f, cellSize = 1f, width = 4, height = 4)).build()

        assertFalse(grid.cellAt(-0.5f, 2f).isValid)
        assertFalse(grid.cellAt(4.5f, 2f).isValid)
        assertFalse(grid.cellAt(2f, 4.5f).isValid)
    }

    @Test
    fun `a footprint blocks every cell it overlaps`() {
        val grid = NavGridBuilder(NavGridLayout(originX = 0f, originY = 0f, cellSize = 1f, width = 6, height = 6))
            .block(centreX = 3f, centreY = 2.5f, halfWidth = 1f, halfDepth = 0.5f)
            .build()

        // The 2m x 1m footprint covers x in [2,4] and y in [2,3]: columns 2 and 3 of row 2. The
        // edges land exactly on cell boundaries, and a footprint does not block the cell beyond
        // the one its edge stops at - column 4 and row 3 stay open.
        assertTrue(grid.isBlocked(grid.cellOf(2, 2)))
        assertTrue(grid.isBlocked(grid.cellOf(3, 2)))
        assertFalse(grid.isBlocked(grid.cellOf(1, 2)))
        assertFalse(grid.isBlocked(grid.cellOf(4, 2)))
        assertFalse(grid.isBlocked(grid.cellOf(2, 1)))
        assertFalse(grid.isBlocked(grid.cellOf(2, 3)))
    }

    @Test
    fun `clearance counts the cells between a cell and the nearest blocked one`() {
        val grid = NavGridBuilder(NavGridLayout(originX = 0f, originY = 0f, cellSize = 1f, width = 7, height = 7))
            .block(centreX = 3.5f, centreY = 3.5f, halfWidth = 0.5f, halfDepth = 0.5f)
            .build()

        assertEquals(0, grid.clearance(grid.cellOf(3, 3)))
        assertEquals(1, grid.clearance(grid.cellOf(2, 3)))
        assertEquals(2, grid.clearance(grid.cellOf(1, 3)))
        assertEquals(1, grid.clearance(grid.cellOf(2, 2)))
    }

    @Test
    fun `the edge of the grid is as solid as a building`() {
        val grid = NavGridBuilder(NavGridLayout(originX = 0f, originY = 0f, cellSize = 1f, width = 5, height = 5)).build()

        assertEquals(1, grid.clearance(grid.cellOf(0, 0)))
        assertEquals(2, grid.clearance(grid.cellOf(1, 1)))
        assertEquals(3, grid.clearance(grid.cellOf(2, 2)))
    }

    @Test
    fun `a unit needs as many cells of clearance as its radius covers`() {
        val grid = NavGridBuilder(NavGridLayout(originX = 0f, originY = 0f, cellSize = 0.5f, width = 8, height = 8)).build()

        assertEquals(1, grid.clearanceCellsFor(radius = 0.25f))
        assertEquals(1, grid.clearanceCellsFor(radius = 0.5f))
        assertEquals(2, grid.clearanceCellsFor(radius = 0.75f))
        assertEquals(2, grid.clearanceCellsFor(radius = 1f))
    }
}
