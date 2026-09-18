package dev.wildware.udea.render.support

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory

/**
 * Counts bytes a block allocates on the calling thread.
 *
 * A copy of `udea-core`'s probe rather than a shared one, following the precedent `udea-agent`
 * already set: it lives in that module's `test` source set, not its published test fixtures,
 * and promoting it would edit a module this work does not own. The measurement is the same and
 * the reason is the same - `RenderAllocationTest` measures the per-frame drawing path, and
 * garbage generated there is a GC pause that lands in the middle of a frame.
 *
 * HotSpot's `getCurrentThreadAllocatedBytes` is used because it counts *this* thread's Java heap
 * allocation and is unaffected by whatever else the test JVM is doing, unlike
 * `Runtime.totalMemory`, which mostly measures the garbage collector's mood.
 */
internal object AllocationProbe {

    private val bean: ThreadMXBean? = (ManagementFactory.getThreadMXBean() as? ThreadMXBean)
        ?.takeIf { it.isThreadAllocatedMemorySupported }

    /** False on a JVM without HotSpot's allocation counters; the tests then skip. */
    val isSupported: Boolean get() = bean != null

    /**
     * Runs [block] [warmups] times, then measures it [attempts] times and returns the smallest
     * result.
     *
     * The warmups exist so the measurement runs against JIT-compiled code: interpreted Kotlin
     * boxes things compiled Kotlin does not. The minimum is taken because a recompilation or a
     * safepoint inside one attempt inflates that attempt only - a path that ever allocates
     * nothing genuinely allocates nothing, whereas one that allocates always does.
     */
    fun bytesAllocated(warmups: Int = 3, attempts: Int = 5, block: () -> Unit): Long {
        val counter = checkNotNull(bean) { "thread allocation counters are unavailable" }
        if (!counter.isThreadAllocatedMemoryEnabled) counter.isThreadAllocatedMemoryEnabled = true

        repeat(warmups) { block() }

        var smallest = Long.MAX_VALUE
        repeat(attempts) {
            val before = counter.currentThreadAllocatedBytes
            block()
            val after = counter.currentThreadAllocatedBytes
            val used = after - before
            if (used < smallest) smallest = used
        }
        return smallest
    }
}
