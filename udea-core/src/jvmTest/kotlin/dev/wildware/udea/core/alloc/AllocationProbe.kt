package dev.wildware.udea.core.alloc

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory

/**
 * Counts bytes a block allocates on the calling thread.
 *
 * Two paths in this engine promise zero steady-state allocation and are worth nothing
 * unless that promise is measured: the `SimBarrier` drain, which runs before every tick, and
 * `SimRandom`, which a system may call thousands of times inside one. A GC pause in either is
 * a frame the simulation does not get, and at 60Hz that is a visible hitch.
 *
 * HotSpot's `getCurrentThreadAllocatedBytes` is the measurement, because it counts *this*
 * thread's Java heap allocation and is unaffected by whatever else the test JVM is doing —
 * unlike `Runtime.totalMemory`, which mostly measures the garbage collector's mood.
 */
internal object AllocationProbe {

    private val bean: ThreadMXBean? = (ManagementFactory.getThreadMXBean() as? ThreadMXBean)
        ?.takeIf { it.isThreadAllocatedMemorySupported }

    /** False on a JVM without HotSpot's allocation counters; the tests then skip. */
    val isSupported: Boolean get() = bean != null

    /**
     * Runs [block] [warmups] times, then measures it [attempts] times and returns the
     * smallest result.
     *
     * The warmups exist so the measurement runs against JIT-compiled code: interpreted
     * Kotlin boxes things compiled Kotlin does not, so measuring a cold path measures the
     * interpreter. The minimum is taken because a recompilation or a safepoint landing
     * inside one attempt inflates that attempt only — a path that ever allocates nothing
     * genuinely allocates nothing, whereas one that allocates always does.
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
