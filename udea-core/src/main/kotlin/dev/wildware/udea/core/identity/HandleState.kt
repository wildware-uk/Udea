package dev.wildware.udea.core.identity

/**
 * The allocator half of a [NetIdIndex], captured so a restore can hand out the same ids again.
 *
 * A snapshot already carries which entities were alive, as the roster of [NetId]s its rows are
 * keyed by. What that roster does *not* carry is the state of the allocator: which recycled
 * indices were queued, in what order, and what generation each of them had reached. Without
 * it, restoring to tick 100 and re-running would allocate different ids for the entities
 * spawned after it — so the re-run would diverge from the original run on entity identity
 * alone, and the snapshot-equivalence gate would fail for a reason that has nothing to do with
 * gameplay.
 *
 * ## Why it is not just a copy of the index's arrays
 *
 * A [NetIdIndex] is 65 536 indices wide by default. Copying its `generations` array into every
 * ring slot would be 256KB per snapshot — 180MB across a full ring, against a 64MB budget for
 * the whole thing. So this records only what the roster cannot reconstruct:
 *
 * - a **live** index's generation is already in its [NetId], in the snapshot's rows;
 * - a **free** index's generation is not, so it is recorded here alongside the index;
 * - an index at or above [nextFresh] has never been allocated, so its generation is zero.
 *
 * Which makes the size proportional to churn rather than to capacity.
 *
 * ## Invariant
 *
 * [freeIndices] never contains a live index. `NetIdIndex.saveInto` cannot produce one, and
 * `NetIdIndex.bind` relies on it: after a `restoreFrom` the roster's indices are exactly the
 * ones that are neither free nor fresh, so binding them never has to search the free ring.
 */
public class HandleState(initialFreeCapacity: Int = DEFAULT_FREE_CAPACITY) {

    init {
        require(initialFreeCapacity > 0) {
            "initialFreeCapacity must be positive, was $initialFreeCapacity"
        }
    }

    /** Free indices in FIFO order: [0] is the one that will be handed out next. */
    internal var freeIndices: IntArray = IntArray(initialFreeCapacity)

    /** The generation each entry of [freeIndices] had reached when it was freed. */
    internal var freeGenerations: IntArray = IntArray(initialFreeCapacity)

    /** How many entries of [freeIndices] are meaningful. */
    public var freeCount: Int = 0
        internal set

    /** The lowest index never yet handed out. */
    public var nextFresh: Int = 0
        internal set

    /** One past the highest index ever handed out. Bounds a live scan after restore. */
    public var highWater: Int = 0
        internal set

    /** The index queued at FIFO position [position]. */
    public fun freeIndexAt(position: Int): Int = freeIndices[checked(position)]

    /** The generation of the index queued at FIFO position [position]. */
    public fun freeGenerationAt(position: Int): Int = freeGenerations[checked(position)]

    /** Bytes of backing array, for the ring's budget accounting. */
    public fun sizeBytes(): Long =
        (freeIndices.size + freeGenerations.size).toLong() * Int.SIZE_BYTES

    /** Empties the record, keeping the buffers. */
    public fun reset() {
        freeCount = 0
        nextFresh = 0
        highWater = 0
    }

    /** Appends one freed index, growing by doubling if it has to. */
    internal fun addFree(index: Int, generation: Int) {
        if (freeCount == freeIndices.size) {
            val capacity = freeIndices.size * 2
            freeIndices = freeIndices.copyOf(capacity)
            freeGenerations = freeGenerations.copyOf(capacity)
        }
        freeIndices[freeCount] = index
        freeGenerations[freeCount] = generation
        freeCount++
    }

    private fun checked(position: Int): Int {
        require(position in 0 until freeCount) {
            "free-queue position out of range: $position (0 until $freeCount)"
        }
        return position
    }

    override fun toString(): String =
        "HandleState(free=$freeCount, nextFresh=$nextFresh, highWater=$highWater)"

    private companion object {
        /** Churn in a busy scene without a regrow. */
        const val DEFAULT_FREE_CAPACITY: Int = 64
    }
}
