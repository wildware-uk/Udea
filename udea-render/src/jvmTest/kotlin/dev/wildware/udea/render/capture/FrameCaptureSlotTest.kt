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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        val result = submit(CaptureRequest())

        val captured = drainUntilSettled(result)

        assertEquals(64, captured.width)
        assertEquals(32, captured.height)
        assertEquals(listOf("0,0,64,32"), pixels.requests)
    }

    /**
     * The property the agent's render toolset is built on: a tool may queue a capture from the
     * very thread that will draw the frame serving it.
     *
     * `submit` returning before any frame is drawn is not a convenience. On an `Offscreen` host
     * the simulation thread *is* the render thread, so a `screenshot` tool that blocked here
     * would be waiting for itself - which is what `ToolRegistry` forbids in as many words.
     */
    @Test
    fun `submit returns before any frame is drawn`() {
        val result = slot.submit(CaptureRequest())

        assertFalse(result.isDone, "submit must not wait for a frame")
        assertEquals(1, slot.queuedRequests)
        assertEquals(emptyList(), pixels.requests, "no pixels may be read before a frame is drawn")

        slot.drain(target)

        assertTrue(result.isDone)
    }

    @Test
    fun `a region is read verbatim rather than replaced by the full frame`() {
        val result = submit(CaptureRequest(region = CaptureRegion(4, 8, 16, 16)))

        val captured = drainUntilSettled(result)

        assertEquals(listOf("4,8,16,16"), pixels.requests)
        assertEquals(16, captured.width)
    }

    @Test
    fun `a region larger than the target is refused rather than read past the end`() {
        val result = slot.submit(CaptureRequest(region = CaptureRegion(0, 0, 128, 32)))

        slot.drain(target)

        val failure = assertFailsWith<CompletionException> { result.join() }.cause
        assertTrue(failure is IllegalArgumentException, "was $failure")
        assertEquals(emptyList(), pixels.requests, "nothing may be read for a refused region")
    }

    @Test
    fun `afterTick holds the request until the clock has moved past that tick`() {
        repeat(5) { sim.step() }
        val result = submit(CaptureRequest(afterTick = Tick(7)))

        slot.drain(target)
        assertFalse(result.isDone, "tick 7 has not been simulated at clock tick 5")

        repeat(2) { sim.step() }
        slot.drain(target)
        assertFalse(
            result.isDone,
            "clock tick 7 means tick 7 is about to run, so it is not finished yet",
        )

        sim.step()
        val captured = drainUntilSettled(result)

        assertEquals(Tick(8), captured.tick)
        assertEquals(1, pixels.requests.size, "the frame must be read exactly once")
    }

    @Test
    fun `a capture is stamped with the tick the frame was drawn at`() {
        repeat(200) { sim.step() }
        val result = submit(CaptureRequest())

        assertEquals(Tick(200), drainUntilSettled(result).tick)
    }

    @Test
    fun `a request that is never satisfied fails loudly instead of hanging`() {
        val failure = assertFailsWith<CaptureStalledException> {
            slot.capture(CaptureRequest(afterTick = Tick(9_000)), timeoutMillis = 60)
        }

        assertTrue("was not served" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    /**
     * A submission to a closed slot answers rather than sitting in a queue nothing will drain.
     *
     * The shutdown race an agent host hits on the way out: the render loop has gone and a command
     * is still in flight.
     */
    @Test
    fun `submitting to a closed slot settles immediately`() {
        slot.close()

        val result = slot.submit(CaptureRequest())

        assertTrue(result.isDone)
        val failure = assertFailsWith<CompletionException> { result.join() }.cause
        assertTrue(failure is CaptureStalledException, "was $failure")
    }

    @Test
    fun `closing the slot settles a queued request rather than leaving it outstanding`() {
        val result = slot.submit(CaptureRequest(afterTick = Tick(9_000)))

        slot.close()

        assertTrue(result.isDone, "the queued request was left outstanding")
        val failure = assertFailsWith<CompletionException> { result.join() }.cause
        assertTrue(failure is CaptureStalledException, "was $failure")
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
        val first = submit(CaptureRequest())
        val second = submit(CaptureRequest(region = CaptureRegion(0, 0, 8, 8)))
        assertEquals(2, slot.queuedRequests)

        slot.drain(target)

        assertEquals(setOf(64, 8), setOfWidths(first, second))
        assertEquals(2L, slot.completedCaptures)
    }

    /**
     * A capture callback runs with the slot's lock **released**.
     *
     * Not hypothetical: the render toolset's adapter chains `thenApply` onto the future, so
     * whatever a caller attaches runs on the render thread inside [FrameCaptureSlot.drain].
     *
     * The assertion has to be made from *another* thread to mean anything. A callback that simply
     * called back into the slot would prove nothing, because a `ReentrantLock` is reentrant and
     * the render thread already holds it. The deadlock this rules out is the one where the
     * callback waits on a thread that is itself waiting for the lock — which is what any callback
     * that hands work to a pool or an HTTP handler does. So the callback here has a second thread
     * submit, and waits for it with a deadline: complete the future while holding the lock and
     * that second submit can never return.
     */
    @Test
    fun `a completion callback does not hold the slot's lock`() {
        val enqueuedFromCallback = CountDownLatch(1)
        val heldTheLock = AtomicReference<Boolean?>(null)
        val chained = slot.submit(CaptureRequest()).thenApply { result ->
            val other = Thread {
                slot.submit(CaptureRequest(afterTick = Tick(9_000)))
                enqueuedFromCallback.countDown()
            }
            other.isDaemon = true
            other.start()
            // Recorded rather than asserted inside the callback: an assertion that throws here is
            // swallowed into the future, and the test would fail as a `CompletionException` with
            // the real message two causes deep.
            heldTheLock.set(!enqueuedFromCallback.await(5, TimeUnit.SECONDS))
            result
        }

        slot.drain(target)

        chained.join()
        assertEquals(
            false,
            heldTheLock.get(),
            "a second thread could not enqueue while a capture callback was running, so drain is " +
                "completing futures with the slot's lock held",
        )
        assertEquals(1, slot.queuedRequests, "the callback's own request was not queued")
    }

    @Test
    fun `draining a frame nobody asked about reads no pixels`() {
        repeat(10) { slot.drain(target) }

        assertEquals(emptyList(), pixels.requests)
        assertEquals(0L, slot.completedCaptures)
    }

    // --- helpers -------------------------------------------------------------------------

    private fun setOfWidths(vararg results: CompletableFuture<CaptureResult>): Set<Int> =
        results.mapNotNull { it.getNow(null)?.width }.toSet()

    /**
     * Submits from this thread and hands back the future, which is the shape a tool uses.
     *
     * No worker thread and no `AtomicReference`: [FrameCaptureSlot.submit] does not block, so the
     * apparatus the blocking form needed - start a thread, wait for it to reach the queue, spin
     * until it publishes a result - is gone, and with it both places this test could flake.
     */
    private fun submit(request: CaptureRequest): CompletableFuture<CaptureResult> =
        slot.submit(request)

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
     * Waits on the slot's own condition rather than polling [FrameCaptureSlot.queuedRequests].
     * This helper used to spin on that property, and it flaked once during the wave with "only
     * 0 of 1 requests were queued" before passing on a re-run — with a five-second deadline,
     * which is not a slow thread. The cause is that reading `queuedRequests` takes the slot's
     * lock, the lock is non-fair, and a thread spinning on `Thread.onSpinWait()` between
     * acquisitions barges ahead of the worker parked trying to enqueue. The poller could
     * therefore starve the thread it was waiting for, for as long as it kept polling. Waiting
     * on a condition cannot: the waiter parks, and the enqueue signals it.
     */
    private fun awaitQueued(expected: Int = 1) {
        val queued = slot.awaitQueued(expected, timeoutMillis = TimeUnit.SECONDS.toMillis(5))
        assertTrue(queued, "only ${slot.queuedRequests} of $expected requests were queued")
    }

    /**
     * Drains one frame and waits for the request it served to be handed back to its caller.
     *
     * The second wait is over an `AtomicReference` the worker writes *after* it has released the
     * slot's lock, so nothing here contends with anything; a bounded spin is the right shape.
     * It fails rather than returning quietly, so a request that is never settled reports itself
     * instead of surfacing as a confusing `null` in whatever the caller asserted next.
     */
    /**
     * Drains one frame and returns what the request settled to.
     *
     * There is nothing to wait for any more. [FrameCaptureSlot.drain] completes the future on the
     * calling thread before it returns, so a request is settled by the time this line finishes -
     * which is also the property the render toolset depends on, since the frame that serves a
     * capture and the code that answers for it run on one thread in one iteration.
     *
     * This helper used to start a worker thread and spin on an `AtomicReference` with a
     * five-second deadline. That spin was the second half of the anti-pattern that made this
     * class flake: a `Thread.onSpinWait()` loop is a core held flat out against the thread it is
     * waiting for, and on a loaded machine it can lose to it for a long time.
     */
    private fun drainUntilSettled(result: CompletableFuture<CaptureResult>): CaptureResult {
        assertTrue(result.isDone || slot.queuedRequests > 0, "the request never reached the queue")
        slot.drain(target)
        assertTrue(result.isDone, "the drained frame never settled the request")
        return result.join()
    }
}
