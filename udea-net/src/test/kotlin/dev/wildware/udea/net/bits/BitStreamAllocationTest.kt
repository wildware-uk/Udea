package dev.wildware.udea.net.bits

import com.sun.management.ThreadMXBean
import dev.wildware.udea.core.replication.FieldMask
import dev.wildware.udea.core.replication.MaskOps
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The steady state allocates nothing.
 *
 * This is the reason the buffer is caller-supplied and the reason nothing here returns a
 * boxed value. At 64 ticks a second with hundreds of replicated entities, a per-field
 * allocation is a GC pause in the middle of a match, and the old path's answer — a fresh
 * 2048-byte `ByteBuffer` per `EntityUpdate` — is exactly that.
 *
 * Measured with `ThreadMXBean.getThreadAllocatedBytes`, which counts real TLAB bytes on
 * this thread. Best of three runs, because a deoptimisation during a measured run shows up
 * as allocation that the code under test did not do.
 */
class BitStreamAllocationTest {

    @Test
    fun `a hundred thousand write-read cycles allocate nothing`() {
        val platformBean = ManagementFactory.getThreadMXBean()
        assumeTrue(platformBean is ThreadMXBean, "allocation measurement needs a HotSpot ThreadMXBean")
        val bean = platformBean as ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported, "thread allocation counting unsupported")
        bean.isThreadAllocatedMemoryEnabled = true

        val threadId = Thread.currentThread().id
        // A counter that is not actually running reports -1 forever, and every delta below
        // would be a vacuous zero. Prove it moves before trusting it to stay still.
        val idle = bean.getThreadAllocatedBytes(threadId)
        assertTrue(idle > 0, "the allocation counter is not running: reported $idle")
        val megabyte = ByteArray(1 shl 20)
        assertEquals(1 shl 20, megabyte.size)
        assertTrue(
            bean.getThreadAllocatedBytes(threadId) - idle >= (1 shl 20),
            "allocating a megabyte must move the counter, or the measurement below proves nothing",
        )
        val buffer = ByteArray(64)
        val writer = BitBufferWriter(buffer)
        val reader = BitBufferReader(buffer)
        val hitPoints = Q.Fixed(0f, 5000f, step = 1f)

        // Warm up until C2 has compiled the whole cycle, so the measured run is steady state.
        var sink = 0f
        repeat(WARMUP) { sink += cycle(writer, reader, hitPoints, it) }
        assertTrue(sink.isFinite(), "the cycle must actually have run")

        var allocated = -1L
        repeat(3) {
            val before = bean.getThreadAllocatedBytes(threadId)
            var measured = 0f
            repeat(CYCLES) { i -> measured += cycle(writer, reader, hitPoints, i) }
            allocated = bean.getThreadAllocatedBytes(threadId) - before
            assertTrue(measured.isFinite())
            if (allocated == 0L) return
        }
        assertEquals(
            0L,
            allocated,
            "$CYCLES write/read cycles allocated $allocated bytes; the steady state must be " +
                "allocation-free",
        )
    }

    /**
     * One tick's worth of a small component: a mask, a position pair, a facing, a health
     * fraction, a delta and an exact float, written and read back.
     *
     * Returns a value derived from every read so nothing can be optimised away, and takes
     * no assertions: an assertion in the measured loop would box.
     */
    private fun cycle(
        writer: BitBufferWriter,
        reader: BitBufferReader,
        hitPoints: Q,
        seed: Int,
    ): Float {
        val f = seed * 0.001f

        writer.reset()
        writer.writeMask(MASK_8, 8)
        writer.writeBoolean(seed and 1 == 0)
        writer.writeBits(seed, 7)
        writer.writeVarInt(seed)
        writer.writeZigZag(-seed)
        writer.writeQ(Q.Pos, f - 500f)
        writer.writeQ(Q.Pos, 500f - f)
        writer.writeQ(Q.Angle16, f)
        writer.writeQ(Q.Norm8, f - seed / 1000)
        writer.writeQ(hitPoints, f * 3f)
        writer.writeQ(Q.Exact, f)
        writer.writeFixed(f, -1000f, 1000f, 20)
        writer.writeNorm8(0.25f)
        writer.writeAngle16(-f)
        writer.alignToByte()
        writer.writeInt(seed)
        writer.writeLong(seed.toLong())
        writer.writeFloat(f)

        reader.reset()
        var total = MaskOps.cardinality(reader.readMask(8)).toFloat()
        total += if (reader.readBoolean()) 1f else 0f
        total += reader.readBits(7).toFloat()
        total += reader.readVarInt().toFloat()
        total += reader.readZigZag().toFloat()
        total += reader.readQ(Q.Pos)
        total += reader.readQ(Q.Pos)
        total += reader.readQ(Q.Angle16)
        total += reader.readQ(Q.Norm8)
        total += reader.readQ(hitPoints)
        total += reader.readQ(Q.Exact)
        total += reader.readFixed(-1000f, 1000f, 20)
        total += reader.readNorm8()
        total += reader.readAngle16()
        reader.alignToByte()
        total += reader.readInt().toFloat()
        total += reader.readLong().toFloat()
        total += reader.readFloat()
        return total
    }

    private companion object {
        /**
         * Hoisted: building a mask is not what is being measured, but reading and writing
         * one through `MaskOps` is — a value class must not box on the way in or out.
         */
        val MASK_8: FieldMask = MaskOps.fromWords(longArrayOf(0b1011_0110L))

        const val WARMUP = 200_000
        const val CYCLES = 100_000
    }
}
