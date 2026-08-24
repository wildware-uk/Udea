package dev.wildware.udea.net.relevancy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The spatial index underneath the fog solve.
 *
 * The interesting properties are the ones that produce *flicker* when they are wrong rather than
 * an obvious failure: a reach that rounds down hides a body only when it stands near a cell
 * boundary, and a bucket order that depends on hashing makes two servers grant vision from
 * different sources on a tie. Both are asserted here rather than left to the fog tests, because
 * from up there they are indistinguishable from a networking fault.
 */
class VisionGridTest {

    @Test
    fun `a point lands in the cell that contains it`() {
        val grid = grid()

        assertEquals(0, grid.cellOf(0f, 0f))
        assertEquals(0, grid.cellOf(9.9f, 9.9f))
        assertEquals(1, grid.cellOf(10f, 0f))
        assertEquals(grid.columns, grid.cellOf(0f, 10f), "one row up")
        assertEquals(grid.cellAt(3, 2), grid.cellOf(35f, 25f))
    }

    @Test
    fun `a point outside the grid clamps instead of failing`() {
        // A body knocked outside the playable area still has to replicate correctly. Clamping is
        // also the conservative direction: it can widen an edge cell and therefore only ever make
        // something visible that was borderline, never hide something that was not.
        val grid = grid()

        assertEquals(0, grid.cellOf(-1000f, -1000f))
        assertEquals(grid.cellCount - 1, grid.cellOf(1e9f, 1e9f))
    }

    @Test
    fun `the reach of a query rounds up`() {
        val grid = grid()

        assertEquals(1, grid.cellReach(1f), "a tenth of a cell still reaches the neighbour")
        assertEquals(1, grid.cellReach(10f), "exactly one cell reaches exactly one cell")
        assertEquals(2, grid.cellReach(10.001f), "and a hair more reaches two")
        assertEquals(0, grid.cellReach(0f))
    }

    @Test
    fun `a query window covers every cell a radius can touch`() {
        val grid = grid()
        // Deliberately at the far corner of its own cell: this is where rounding the reach down
        // silently drops the neighbour a source can genuinely see into.
        val x = 19.999f
        val y = 19.999f

        // The point sits in column 1 and reaches 24.999, which is column 2. The window is
        // deliberately one cell wider on the near side than it strictly needs to be: over-covering
        // costs a bucket walk, under-covering hides a unit, and only one of those is a bug.
        assertEquals(0, grid.minColumn(x, 5f))
        assertEquals(2, grid.maxColumn(x, 5f), "the cell to the right is within five units of this point")
        assertEquals(0, grid.minRow(y, 5f))
        assertEquals(2, grid.maxRow(y, 5f))
    }

    @Test
    fun `buckets are filled in ascending slot order, twice the same way`() {
        val first = fill()
        val second = fill()

        assertEquals(first, second, "two identical builds must produce identical bucket contents")
        assertTrue(first.isNotEmpty())
    }

    @Test
    fun `building twice without clearing is refused`() {
        val grid = grid()
        grid.add(0, 1f, 1f)
        grid.build()

        assertFailsWith<IllegalStateException> { grid.build() }
        assertFailsWith<IllegalStateException> { grid.add(1, 2f, 2f) }
    }

    @Test
    fun `clear returns the grid to empty and it can be rebuilt`() {
        val grid = grid()
        grid.add(0, 1f, 1f)
        grid.build()
        assertEquals(1, grid.size)

        grid.clear()
        assertEquals(0, grid.size)
        grid.add(7, 55f, 55f)
        grid.build()

        val cell = grid.cellOf(55f, 55f)
        assertEquals(1, grid.cellEnd(cell) - grid.cellStart(cell))
        assertEquals(7, grid.slotAt(grid.cellStart(cell)))
    }

    @Test
    fun `a degenerate grid is refused with the offending value named`() {
        val failure = assertFailsWith<IllegalArgumentException> { VisionGrid(0f, 0f, 0f, 4, 4) }
        assertTrue("was 0.0" in failure.message.orEmpty(), "the message must name the value: ${failure.message}")
    }

    private fun grid() = VisionGrid(originX = 0f, originY = 0f, cellSize = 10f, columns = 8, rows = 8)

    /** Buckets a fixed set of points and reads every bucket back as a list. */
    private fun fill(): List<Pair<Int, List<Int>>> {
        val grid = grid()
        val points = listOf(5f to 5f, 55f to 5f, 6f to 6f, 55f to 6f, 71f to 71f)
        points.forEachIndexed { slot, (x, y) -> grid.add(slot, x, y) }
        grid.build()
        return (0 until grid.cellCount)
            .map { cell -> cell to (grid.cellStart(cell) until grid.cellEnd(cell)).map(grid::slotAt) }
            .filter { it.second.isNotEmpty() }
    }
}
