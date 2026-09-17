package dev.wildware.udea.gas

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory

/**
 * Counts bytes a block allocates on the calling thread.
 *
 * A near-copy of `udea-core`'s probe of the same name, and deliberately so: that one lives in
 * `udea-core`'s *test* source set, which is not a published variant, so it is not reachable from
 * here. The alternative — promoting it to `testFixtures` — is a change to a module this issue does
 * not own, and `testFixtures` is `udea-core`'s published specification of the `Replicator`
 * contract rather than a utility drawer. Twenty lines duplicated in a second module's tests is the
 * cheaper of the two.
 *
 * HotSpot's `getCurrentThreadAllocatedBytes` is the measurement because it counts *this* thread's
 * Java heap allocation and is unaffected by whatever else the test JVM is doing.
 */
internal object AllocationProbe {

    private val bean: ThreadMXBean? = (ManagementFactory.getThreadMXBean() as? ThreadMXBean)
        ?.takeIf { it.isThreadAllocatedMemorySupported }

    /** False on a JVM without HotSpot's allocation counters. */
    val isSupported: Boolean get() = bean != null

    /**
     * Runs [block] [warmups] times, then measures it [attempts] times and returns the smallest.
     *
     * The warmups exist so the measurement runs against JIT-compiled code: interpreted Kotlin boxes
     * things compiled Kotlin does not. The minimum is taken because a safepoint landing inside one
     * attempt inflates that attempt only.
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
