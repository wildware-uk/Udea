package dev.wildware.udea.nav

/**
 * The open set both searches use: a binary min-heap of cells under a **total** order.
 *
 * One heap rather than one in [NavPathfinder] and another in [NavFlowField], because the two
 * would have been the same code under two names, and the ordering is the part that has to be the
 * same - it is what decides which of several equally good routes a search returns, on every
 * machine that runs it.
 *
 * A slot is `(primary, secondary, cell)` and they are compared in that order. The cell index is
 * the last term, so no two slots ever compare equal: A* pushes `(f, h, cell)`, and a Dijkstra
 * sweep with nothing to break a tie on pushes `(cost, 0, cell)` and still gets one.
 *
 * Entries are never removed by key. A cell reached again more cheaply is pushed again and the
 * stale entry is skipped by the caller when it comes off, which is why the heap grows with pushes
 * rather than with cells.
 */
internal class NavCellHeap(initialCapacity: Int) {

    private var cells = IntArray(initialCapacity + 1)

    private var primary = IntArray(initialCapacity + 1)

    private var secondary = IntArray(initialCapacity + 1)

    /** How many entries are in it. */
    var size: Int = 0
        private set

    /** Empties it without releasing what it has grown to. */
    fun clear() {
        size = 0
    }

    /** Adds [cell] under the key `(primary, secondary, cell)`. */
    fun push(cell: Int, primaryKey: Int, secondaryKey: Int) {
        if (size + 1 >= cells.size) {
            cells = cells.copyOf(cells.size * 2)
            primary = primary.copyOf(primary.size * 2)
            secondary = secondary.copyOf(secondary.size * 2)
        }
        var index = ++size
        cells[index] = cell
        primary[index] = primaryKey
        secondary[index] = secondaryKey
        while (index > 1) {
            val parent = index / 2
            if (!precedes(index, parent)) break
            swap(index, parent)
            index = parent
        }
    }

    /** Removes and returns the least cell. The caller checks [size] first. */
    fun pop(): Int {
        val top = cells[1]
        swap(1, size)
        size--
        var index = 1
        while (true) {
            val left = index * 2
            if (left > size) break
            val right = left + 1
            val child = if (right <= size && precedes(right, left)) right else left
            if (!precedes(child, index)) break
            swap(index, child)
            index = child
        }
        return top
    }

    private fun precedes(a: Int, b: Int): Boolean = when {
        primary[a] != primary[b] -> primary[a] < primary[b]
        secondary[a] != secondary[b] -> secondary[a] < secondary[b]
        else -> cells[a] < cells[b]
    }

    private fun swap(a: Int, b: Int) {
        val cell = cells[a]
        val first = primary[a]
        val second = secondary[a]
        cells[a] = cells[b]
        primary[a] = primary[b]
        secondary[a] = secondary[b]
        cells[b] = cell
        primary[b] = first
        secondary[b] = second
    }
}
