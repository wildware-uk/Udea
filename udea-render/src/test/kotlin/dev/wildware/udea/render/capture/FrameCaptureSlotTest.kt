package dev.wildware.udea.render.capture

import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.SimClock
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.loop.WorldSimulation
import dev.wildware.udea.core.host.CaptureOutcome
import dev.wildware.udea.core.host.RenderUnavailable
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.support.FakePixelSource
import dev.wildware.udea.render.support.testTargets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The request slot: when a capture is served, when it is not, and what a caller is told.
 *
 * All of it with no GL context — the pixel read is behind [PixelSource] precisely so the timing
 * rules, which are the part that is easy to get subtly wrong, can be checked in a plain JVM.
 */
class FrameCaptureSlotTest {

    // A real context and a real simulation, because SimClock only moves for the kernel: the
    // `afterTick` rule is about ticks actually being simulated, and a clock a test could poke
    // would be asserting against a stand-in for the thing under test.
    private val ctx = testGameContext(seed = 1L)

    private val sim = WorldSimulation(ctx, configureWorld { injectables { gameContext(ctx) } })

    private val clock: SimClock = ctx.clock

    private val pixels = FakePixelSource()

    private val slot = FrameCaptureSlot(pixels, clock)

    private val target: OffscreenTarget = testTargets(width = 64, height = 32).offscreen

    @Test
    fun `a request with no afterTick is served by the next drained frame`() {
        val result = captureOnAnotherThread(CaptureRequest())

        drainUntilSettled(result)

        val captured = result.get()
        assertEquals(64, captured?.width)
        assertEquals(32, captured?.height)
        assertEquals(listOf("0,0,64,32"), pixels.requests)
    }

    @Test
    fun `a region is read verbatim rather than replaced by the full frame`() {
        val result = captureOnAnotherThread(CaptureRequest(region = CaptureRegion(4, 8, 16, 16)))

        drainUntilSettled(result)

        assertEquals(listOf("4,8,16,16"), pixels.requests)
        assertEquals(16, result.get()?.width)
    }

    @Test
    fun `a region larger than the target is refused rather than read past the end`() {
        val request = CaptureRequest(region = CaptureRegion(0, 0, 128, 32))
        val failure = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(1)
        thread(failure, done) { slot.capture(request, timeoutMillis = 2_000) }

        // The request has to reach the queue before a drain can refuse it.
        awaitQueued()
        slot.drain(target)
        done.await(5, TimeUnit.SECONDS)

        assertTrue(failure.get() is IllegalArgumentException, "was ${failure.get()}")
        assertEquals(emptyList(), pixels.requests, "nothing may be read for a refused region")
    }

    @Test
    fun `afterTick holds the request until the clock has moved past that tick`() {
        repeat(5) { sim.step() }
        val result = captureOnAnotherThread(CaptureRequest(afterTick = Tick(7)))

        awaitQueued()
        slot.drain(target)
        assertNull(result.get(), "tick 7 has not been simulated at clock tick 5")

        repeat(2) { sim.step() }
        slot.drain(target)
        assertNull(
            result.get(),
            "clock tick 7 means tick 7 is about to run, so it is not finished yet",
        )

        sim.step()
        drainUntilSettled(result)

        assertEquals(Tick(8), result.get()?.tick)
        assertEquals(1, pixels.requests.size, "the frame must be read exactly once")
    }

    @Test
    fun `a capture is stamped with the tick the frame was drawn at`() {
        repeat(200) { sim.step() }
        val result = captureOnAnotherThread(CaptureRequest())

        drainUntilSettled(result)

        assertEquals(Tick(200), result.get()?.tick)
    }

    @Test
    fun `a request that is never satisfied fails loudly instead of hanging`() {
        val failure = assertFailsWith<CaptureStalledException> {
            slot.capture(CaptureRequest(afterTick = Tick(9_000)), timeoutMillis = 60)
        }

        assertTrue("was not served" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `closing the slot wakes a waiter rather than leaving it to time out`() {
        val failure = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(1)
        thread(failure, done) {
            slot.capture(CaptureRequest(afterTick = Tick(9_000)), timeoutMillis = 30_000)
        }
        awaitQueued()

        slot.close()

        assertTrue(done.await(5, TimeUnit.SECONDS), "the waiter was not woken")
        assertTrue(failure.get() is CaptureStalledException, "was ${failure.get()}")
    }

    @Test
    fun `the FrameCapture contract reports no_capture_backend once closed`() {
        slot.close()

        val outcome = slot.capture()

        val unavailable = outcome as? CaptureOutcome.Unavailable
        assertEquals(RenderUnavailable.NoCaptureBackend, unavailable?.reason)
    }

    @Test
    fun `two waiting requests are both served by one frame`() {
        val first = captureOnAnotherThread(CaptureRequest())
        val second = captureOnAnotherThread(CaptureRequest(region = CaptureRegion(0, 0, 8, 8)))
        awaitQueued(expected = 2)

        slot.drain(target)

        assertEquals(setOf(64, 8), setOfWidths(first, second))
        assertEquals(2L, slot.completedCaptures)
    }

    @Test
    fun `draining a frame nobody asked about reads no pixels`() {
        repeat(10) { slot.drain(target) }

        assertEquals(emptyList(), pixels.requests)
        assertEquals(0L, slot.completedCaptures)
    }

    // --- helpers -------------------------------------------------------------------------

    private fun setOfWidths(vararg results: AtomicReference<CaptureResult?>): Set<Int> =
        results.mapNotNull { it.get()?.width }.toSet()

    private fun captureOnAnotherThread(request: CaptureRequest): AtomicReference<CaptureResult?> {
        val result = AtomicReference<CaptureResult?>(null)
        val worker = Thread { result.set(slot.capture(request, timeoutMillis = 10_000)) }
        worker.isDaemon = true
        worker.start()
        return result
    }

    private fun thread(
        failure: AtomicReference<Throwable?>,
        done: CountDownLatch,
        block: () -> Unit,
    ) {
        val worker = Thread {
            try {
                block()
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                done.countDown()
            }
        }
        worker.isDaemon = true
        worker.start()
    }

    /**
     * Waits until [expected] requests are queued.
     *
     * Polls a state the slot publishes rather than sleeping: a fixed sleep is the flake this
     * whole class of test is famous for, and the standards ban wall-clock waits in tests
     * outright.
     */
    private fun awaitQueued(expected: Int = 1) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (slot.queuedRequests >= expected) return
            Thread.onSpinWait()
        }
        error("only ${slot.queuedRequests} of $expected requests were queued")
    }

    private fun drainUntilSettled(result: AtomicReference<CaptureResult?>) {
        awaitQueued()
        slot.drain(target)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (result.get() == null && System.nanoTime() < deadline) Thread.onSpinWait()
    }
}
