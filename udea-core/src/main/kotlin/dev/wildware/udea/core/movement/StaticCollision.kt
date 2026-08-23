package dev.wildware.udea.core.movement

/**
 * The immutable static collision geometry of one scene: line segments in a uniform grid.
 *
 * ## Why segments and not Box2D fixtures
 *
 * Spec 3.4 demotes Box2D: it serves sensors, debris and server-only projectiles, and is never
 * snapshot state. [CharacterMover] therefore cannot ask the solver where a wall is - the answer
 * would depend on solver state that no snapshot carries, and on a broadphase whose iteration
 * order is not promised to be the same on two machines. So the walls live here, as numbers, in
 * an order fixed at build time, and every query returns segment indices **in ascending order**.
 * Two processes that built the same geometry resolve the same contacts in the same sequence,
 * which is half of why [CharacterMover] is bit-identical across machines.
 *
 * ## Why a flat `FloatArray` and a counting-sorted grid
 *
 * The whole structure is four arrays and no objects. A query walks the overlapped cells and
 * writes segment indices into a caller-owned scratch, so a mover replayed sixty times a frame
 * does not allocate a candidate list sixty times. `List<Segment>` would be one object per wall
 * plus one iterator per query, and the iterator is the one that shows up in a profile.
 *
 * ## Built once, read many
 *
 * There is no `add` after [Builder.build]. A scene's walls do not move; a scene that needs
 * different walls is a different [StaticCollision], swapped between ticks. Immutability is what
 * lets one instance be shared by every mover on the server and by the client replaying them.
 */
public class StaticCollision private constructor(
    /** `x0, y0, x1, y1` per segment, in build order. Never mutated after construction. */
    private val segments: FloatArray,
    /** `cellStart[c] until cellStart[c + 1]` indexes [cellItems] for cell `c`. */
    private val cellStart: IntArray,
    /** Segment indices, grouped by cell, ascending within each cell. */
    private val cellItems: IntArray,
    /** Grid origin: the minimum corner of the geometry's bounding box. */
    private val originX: Float,
    private val originY: Float,
    /** Cell edge length in world units. */
    private val cellSize: Float,
    private val columns: Int,
    private val rows: Int,
) {

    /** How many segments this geometry holds. */
    public val segmentCount: Int get() = segments.size / STRIDE

    /** First endpoint x of segment [index]. */
    public fun startX(index: Int): Float = segments[index * STRIDE]

    /** First endpoint y of segment [index]. */
    public fun startY(index: Int): Float = segments[index * STRIDE + 1]

    /** Second endpoint x of segment [index]. */
    public fun endX(index: Int): Float = segments[index * STRIDE + 2]

    /** Second endpoint y of segment [index]. */
    public fun endY(index: Int): Float = segments[index * STRIDE + 3]

    /**
     * Writes the indices of every segment whose cell overlaps the box into [scratch], ascending.
     *
     * Ascending order is the contract, not an accident of the grid: contacts are resolved in the
     * order they come back, and resolving the same two walls in a different order gives a
     * different final position when they disagree - a corner is exactly that case. The grid
     * yields candidates in cell order, so the scratch sorts them back into index order before
     * this returns.
     *
     * A segment is reported when its *cell* overlaps, not when the segment does: this is a
     * broadphase, it over-reports on purpose, and the exact test is [CharacterMover]'s.
     *
     * @param scratch sized for this geometry - see [CollisionScratch]. It cannot overflow: a
     *   segment is written at most once, so the count never exceeds [segmentCount].
     * @return how many indices were written into [CollisionScratch.indices].
     */
    public fun query(
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
        scratch: CollisionScratch,
    ): Int {
        require(scratch.capacity >= segmentCount) {
            "the scratch holds ${scratch.capacity} candidates and this geometry has " +
                "$segmentCount segments; size the scratch from the geometry"
        }
        if (segmentCount == 0 || columns == 0 || rows == 0) return 0

        val firstColumn = columnOf(minX)
        val lastColumn = columnOf(maxX)
        val firstRow = rowOf(minY)
        val lastRow = rowOf(maxY)

        val stamp = scratch.nextStamp()
        var found = 0
        var row = firstRow
        while (row <= lastRow) {
            var column = firstColumn
            while (column <= lastColumn) {
                val cell = row * columns + column
                var slot = cellStart[cell]
                val end = cellStart[cell + 1]
                while (slot < end) {
                    val segment = cellItems[slot]
                    if (scratch.marks[segment] != stamp) {
                        scratch.marks[segment] = stamp
                        scratch.indices[found] = segment
                        found++
                    }
                    slot++
                }
                column++
            }
            row++
        }
        scratch.sort(found)
        return found
    }

    /** Grid column containing [x], clamped into the grid. */
    private fun columnOf(x: Float): Int {
        val raw = ((x - originX) / cellSize).toInt()
        return if (raw < 0) 0 else if (raw >= columns) columns - 1 else raw
    }

    /** Grid row containing [y], clamped into the grid. */
    private fun rowOf(y: Float): Int {
        val raw = ((y - originY) / cellSize).toInt()
        return if (raw < 0) 0 else if (raw >= rows) rows - 1 else raw
    }

    override fun toString(): String =
        "StaticCollision($segmentCount segments, ${columns}x$rows cells of $cellSize)"

    /**
     * Collects segments and lays them out.
     *
     * Segments keep the order they were added in, and that order is the tie-break for every
     * contact [CharacterMover] resolves. So two processes must add them in the same order - which
     * for a scene loaded from an asset means iterating the asset, and never a hash map.
     */
    public class Builder(
        /** Cell edge length. Roughly the size of a mover is the useful choice. */
        private val cellSize: Float = DEFAULT_CELL_SIZE,
        /** How many segments to reserve for. Grows if exceeded. */
        initialCapacity: Int = DEFAULT_CAPACITY,
    ) {

        init {
            require(cellSize > 0f) { "cellSize must be positive, was $cellSize" }
            require(initialCapacity >= 0) { "initialCapacity must not be negative" }
        }

        private var values = FloatArray(initialCapacity * STRIDE)

        private var count = 0

        /** How many segments have been added. */
        public val size: Int get() = count

        /** Adds the wall from ([x0], [y0]) to ([x1], [y1]). Zero-length segments are refused. */
        public fun segment(x0: Float, y0: Float, x1: Float, y1: Float): Builder {
            require(x0.isFinite() && y0.isFinite() && x1.isFinite() && y1.isFinite()) {
                "segment ($x0, $y0)-($x1, $y1) is not finite"
            }
            require(x0 != x1 || y0 != y1) {
                "a zero-length segment at ($x0, $y0) has no direction, so nothing could be " +
                    "pushed out of it; drop it from the scene instead"
            }
            if ((count + 1) * STRIDE > values.size) {
                val grown = if (values.isEmpty()) STRIDE * DEFAULT_CAPACITY else values.size * 2
                values = values.copyOf(grown)
            }
            val base = count * STRIDE
            values[base] = x0
            values[base + 1] = y0
            values[base + 2] = x1
            values[base + 3] = y1
            count++
            return this
        }

        /** Adds the four walls of an axis-aligned box, anticlockwise from the bottom-left. */
        public fun box(minX: Float, minY: Float, maxX: Float, maxY: Float): Builder {
            require(minX < maxX && minY < maxY) {
                "a box needs min < max, got ($minX, $minY)-($maxX, $maxY)"
            }
            segment(minX, minY, maxX, minY)
            segment(maxX, minY, maxX, maxY)
            segment(maxX, maxY, minX, maxY)
            segment(minX, maxY, minX, minY)
            return this
        }

        /** Lays the segments out into a grid. The builder may be reused afterwards. */
        public fun build(): StaticCollision {
            if (count == 0) return EMPTY
            val segments = values.copyOf(count * STRIDE)

            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (index in 0 until count) {
                val base = index * STRIDE
                minX = minOf(minX, segments[base], segments[base + 2])
                maxX = maxOf(maxX, segments[base], segments[base + 2])
                minY = minOf(minY, segments[base + 1], segments[base + 3])
                maxY = maxOf(maxY, segments[base + 1], segments[base + 3])
            }

            val columns = spanIn(minX, maxX)
            val rows = spanIn(minY, maxY)
            val cells = columns * rows

            // Two-pass counting sort: pass one counts per cell, pass two fills. Segments are
            // visited in ascending index in both passes, so `cellItems` comes out ascending
            // within each cell without a sort - which is what keeps `query` cheap to keep ordered.
            val counts = IntArray(cells + 1)
            forEachCell(segments, count, minX, minY, columns, rows) { _, cell -> counts[cell + 1]++ }
            for (cell in 0 until cells) counts[cell + 1] += counts[cell]

            val start = counts.copyOf()
            val items = IntArray(counts[cells])
            val cursor = counts.copyOf()
            forEachCell(segments, count, minX, minY, columns, rows) { segment, cell ->
                items[cursor[cell]] = segment
                cursor[cell]++
            }

            return StaticCollision(segments, start, items, minX, minY, cellSize, columns, rows)
        }

        private fun spanIn(min: Float, max: Float): Int {
            val span = ((max - min) / cellSize).toInt() + 1
            return if (span < 1) 1 else span
        }

        /** Visits every (segment, cell) pair the segment's AABB touches, segments ascending. */
        private inline fun forEachCell(
            segments: FloatArray,
            count: Int,
            originX: Float,
            originY: Float,
            columns: Int,
            rows: Int,
            body: (Int, Int) -> Unit,
        ) {
            for (index in 0 until count) {
                val base = index * STRIDE
                val x0 = segments[base]
                val y0 = segments[base + 1]
                val x1 = segments[base + 2]
                val y1 = segments[base + 3]
                val firstColumn = clamp(((minOf(x0, x1) - originX) / cellSize).toInt(), columns)
                val lastColumn = clamp(((maxOf(x0, x1) - originX) / cellSize).toInt(), columns)
                val firstRow = clamp(((minOf(y0, y1) - originY) / cellSize).toInt(), rows)
                val lastRow = clamp(((maxOf(y0, y1) - originY) / cellSize).toInt(), rows)
                var row = firstRow
                while (row <= lastRow) {
                    var column = firstColumn
                    while (column <= lastColumn) {
                        body(index, row * columns + column)
                        column++
                    }
                    row++
                }
            }
        }

        private fun clamp(value: Int, limit: Int): Int =
            if (value < 0) 0 else if (value >= limit) limit - 1 else value
    }

    public companion object {

        /** `x0, y0, x1, y1`. */
        private const val STRIDE: Int = 4

        /** A cell a little wider than a typical mover: few cells per query, few segments per cell. */
        public const val DEFAULT_CELL_SIZE: Float = 2f

        private const val DEFAULT_CAPACITY: Int = 64

        /** Geometry with no walls. What a scene has before it loads one. */
        public val EMPTY: StaticCollision = StaticCollision(
            segments = FloatArray(0),
            cellStart = IntArray(1),
            cellItems = IntArray(0),
            originX = 0f,
            originY = 0f,
            cellSize = DEFAULT_CELL_SIZE,
            columns = 0,
            rows = 0,
        )
    }
}

/**
 * A mover's private candidate buffer: the reason a broadphase query allocates nothing.
 *
 * Held by whoever is querying rather than by the geometry, because the geometry is shared by
 * every mover in the scene and a buffer on it would be a data race the moment two movers were
 * stepped on two threads. One scratch per [CharacterMover] keeps the sharing read-only.
 *
 * [marks] is a stamp table, not a boolean table: clearing a boolean array costs O(segments) on
 * every query, whereas bumping the stamp is one increment. The stamp is an `Int` and would wrap
 * at roughly two billion queries; [nextStamp] clears the table on wrap rather than letting a
 * stale mark suppress a real candidate two billion queries later.
 */
public class CollisionScratch(
    /** Sized from [StaticCollision.segmentCount]; a query refuses a scratch that is too small. */
    public val capacity: Int,
) {

    init {
        require(capacity >= 0) { "capacity must not be negative, was $capacity" }
    }

    /** Candidate segment indices, ascending, valid up to the count a query returned. */
    public val indices: IntArray = IntArray(capacity)

    internal val marks: IntArray = IntArray(capacity)

    private var stamp: Int = 0

    internal fun nextStamp(): Int {
        stamp++
        if (stamp == Int.MAX_VALUE) {
            marks.fill(0)
            stamp = 1
        }
        return stamp
    }

    /**
     * Insertion sort over the first [count] entries.
     *
     * Insertion sort rather than `IntArray.sort`: the candidate set for one capsule against one
     * scene is a handful of segments, and the JDK's dual-pivot quicksort has setup of its own.
     * This one is visibly allocation-free and visibly deterministic regardless of input.
     */
    internal fun sort(count: Int) {
        var index = 1
        while (index < count) {
            val value = indices[index]
            var slot = index - 1
            while (slot >= 0 && indices[slot] > value) {
                indices[slot + 1] = indices[slot]
                slot--
            }
            indices[slot + 1] = value
            index++
        }
    }

    override fun toString(): String = "CollisionScratch(capacity=$capacity)"
}
