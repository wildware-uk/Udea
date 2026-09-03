package dev.wildware.udea.agent.host

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory

/**
 * Counts bytes a block allocates on the calling thread.
 *
 * A copy of the probe `udea-core`, `udea-agent`, `udea-gas` and `udea-render` each hold in their
 * own `test` source set, rather than a shared one. None of those is published as a test fixture,
 * so sharing would mean promoting one of them and editing a module this issue does not own. The
 * measurement is identical and the reason is the one every copy gives: a path that runs every
 * frame and allocates is a GC pause the human pays for.
 *
 * HotSpot's `getCurrentThreadAllocatedBytes` is used because it counts *this* thread's Java heap
 * allocation and is unaffected by whatever else the test JVM is doing, unlike
 * `Runtime.totalMemory`, which mostly measures the garbage collector's mood.
 *
 * ## What this cannot see
 *
 * C2's escape analysis scalar-replaces allocations that do not escape the frame they are made
 * in, and this counts heap bytes. So a measurement of zero says *"nothing the JIT could not
 * eliminate"* - the statement that matters operationally, because a scalar-replaced object costs
 * no GC - and it is narrower than "no object is written anywhere on this path".
 * `RenderAllocationTest` in `udea-render` reached the same conclusion by mutation and states it
 * the same way; [dev.wildware.udea.agent.host.overlay.OverlayAllocationTest] records which of
 * its own mutations escape and which do not.
 */
internal object AllocationProbe {

    private val bean: ThreadMXBean? = (ManagementFactory.getThreadMXBean() as? ThreadMXBean)
        ?.takeIf { it.isThreadAllocatedMemorySupported }

    /** False on a JVM without HotSpot's allocation counters; the tests then return early. */
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
    fun bytesAllocated(warmups: Int, attempts: Int, block: () -> Unit): Long {
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

    /**
     * How many times [bytesAllocated] invokes its block, for these settings.
     *
     * A caller that wants to assert the measured region really did the work has to know this:
     * the warmups are not a preamble it can ignore, they run the same block and they draw the
     * same frames.
     */
    fun invocations(warmups: Int, attempts: Int): Long = (warmups + attempts).toLong()
}
