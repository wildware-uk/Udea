package dev.wildware.udea.render.capture

import dev.wildware.udea.core.SimClock
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.host.CaptureOutcome
import dev.wildware.udea.core.host.FrameCapture
import dev.wildware.udea.core.host.RenderUnavailable
import dev.wildware.udea.render.OffscreenTarget
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The request slot the render pipeline drains at the capture point.
 *
 * ## Why a slot and not a method call
 *
 * A capture request arrives on some other thread and can only be served on the render thread, at
 * one exact moment in the frame: after the last [dev.wildware.udea.render.RenderSystem] has drawn
 * and before the offscreen surface is unbound. `glReadPixels` reads the *bound* framebuffer, so a
 * capture served anywhere else reads either a half-drawn frame or the window the agent activity
 * overlay is about to draw on (spec 3.7). Making the request a queued value rather than a call
 * means the moment is enforced by the pipeline once, instead of being a rule every caller has to
 * know.
 *
 * ## Why the answer is a future and not a return value
 *
 * [submit] never blocks, and that is not a convenience — it is what makes the agent's render
 * toolset possible at all. A tool call runs *inside* a `SimBarrier` drain, and on an `Offscreen`
 * or `Windowed` host the thread running that drain **is** the render thread (`Lwjgl3Backend`
 * hands `GameHost.frame` to the GL thread, and `GameLoop.frame` ticks and then renders on it). A
 * `screenshot` tool that blocked waiting for the next frame would be waiting for itself:
 * `ToolRegistry` states the rule in as many words — *it must not block, sleep or wait on another
 * thread: whatever it waits for cannot happen, because the thread that would do it is this one*.
 *
 * So the tool submits, returns, and the pipeline's own drain — later in the same
 * `GameHost.frame` call — completes the future. [capture] is the blocking form, kept for callers
 * that genuinely are on another thread (`GameHost.screenshot`, and the tests that drive a frame
 * by hand); calling it from the render thread deadlocks until its deadline, which is why it is
 * not what the toolset uses.
 *
 * ## What it does not do
 *
 * It does not name files, hold a `lastPath`, or announce anything. The old `ScreenCapture` did all
 * three and callers had to parse an event-log line to find their own screenshot. Bytes go back to
 * the caller that asked for them; the artifact store is the agent host's.
 *
 * ## Threading
 *
 * [submit] and [capture] are safe from any thread; [drain] and [close] run on the render thread.
 * The queue is guarded by one lock, and **no future is completed while that lock is held**: a
 * completion runs whatever dependent stage the caller attached (`thenApply`, `whenComplete`) on
 * the completing thread, and running a caller's code under this class's lock is how a capture
 * callback that touched the slot again would deadlock the render thread.
 */
public class FrameCaptureSlot internal constructor(
    private val pixels: PixelSource,
    private val clock: SimClock,
) : FrameCapture {

    private val lock = ReentrantLock()

    /**
     * Signalled when a request joins [queue].
     *
     * Exists so [awaitQueued] can *wait* rather than poll. A poller spinning on
     * [queuedRequests] takes and releases this same non-fair [ReentrantLock] as fast as it can,
     * and a non-fair lock lets a running thread barge ahead of one that is parked — so on a
     * loaded machine the poller can hold the enqueuing thread out long enough to cross a
     * multi-second deadline while nothing is actually wrong. That is exactly how
     * `FrameCaptureSlotTest` flaked with "only 0 of 1 requests were queued" and then passed on
     * a re-run.
     */
    private val enqueued = lock.newCondition()
    private val queue = ArrayDeque<Pending>()
    private var closed = false

    /**
     * Requests served by the frame being drained, held between the lock being released and their
     * futures being completed.
     *
     * A field rather than a local so the per-frame path allocates nothing: [drain] runs every
     * frame, and the overwhelmingly common case leaves this empty. Only the render thread ever
     * touches it, and only inside one [drain] call.
     */
    private val settling = ArrayList<Pending>(INITIAL_SETTLING_CAPACITY)

    /** Captures completed since construction. A health signal for `/health`, not state. */
    public var completedCaptures: Long = 0L
        private set

    /**
     * Requests waiting to be served.
     *
     * Published so a caller — and a test — can tell "the render thread has not drawn a frame
     * yet" apart from "the request was dropped", which are the same silence otherwise.
     */
    public val queuedRequests: Int get() = lock.withLock { queue.size }

    /**
     * Queues [request] and returns immediately with the future the render thread will settle.
     *
     * The future completes with a [CaptureResult] at the capture point of the first frame that
     * satisfies the request, or completes exceptionally with a [CaptureStalledException] if the
     * pipeline is torn down first or an [IllegalArgumentException] if the region does not fit the
     * surface.
     *
     * It carries no deadline of its own. A caller that needs one applies it to the future
     * ([capture] is that caller); a caller on the render thread must not, because the frame that
     * would settle it is the one it is inside.
     */
    public fun submit(request: CaptureRequest): CompletableFuture<CaptureResult> {
        val future = CompletableFuture<CaptureResult>()
        val refused = lock.withLock {
            if (closed) {
                true
            } else {
                queue.addLast(Pending(request, future))
                enqueued.signalAll()
                false
            }
        }
        // Outside the lock: see the class KDoc on why a completion never runs under it.
        if (refused) {
            future.completeExceptionally(
                CaptureStalledException("$request: the render pipeline is closed"),
            )
        }
        return future
    }

    /**
     * Requests a frame and waits for it. **Never call this from the render thread.**
     *
     * @param request what to read and, optionally, which tick to wait for.
     * @param timeoutMillis how long to wait before giving up. The default is generous
     *   because a paused loop still renders, so the only way to cross it is a render thread
     *   that has stopped drawing — which is a defect worth a loud report rather than a
     *   caller that waits forever.
     * @throws CaptureStalledException if the deadline passes with the request unfulfilled, or
     *   if the pipeline is torn down while it is queued.
     * @throws IllegalArgumentException if the requested region does not fit the surface.
     */
    public fun capture(
        request: CaptureRequest,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): CaptureResult {
        require(timeoutMillis > 0) { "timeout must be positive, was $timeoutMillis" }
        val future = submit(request)
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            // Withdrawn, so a frame drawn a moment later does not read pixels for a caller that
            // has already been told it failed - and so the queue does not grow one dead entry
            // per timed-out capture.
            withdraw(future)
            throw CaptureStalledException(
                "$request was not served within ${timeoutMillis}ms: the render thread " +
                    "drew no frame that satisfied it",
            )
        } catch (failed: ExecutionException) {
            throw failed.cause ?: CaptureStalledException("$request failed without a cause")
        } catch (interrupted: InterruptedException) {
            withdraw(future)
            Thread.currentThread().interrupt()
            throw CaptureStalledException("$request: the waiting thread was interrupted", interrupted)
        }
    }

    /**
     * Waits until at least [count] requests are queued, or [timeoutMillis] passes.
     *
     * Published for the same reason [queuedRequests] is — a caller, and a test, has to be able
     * to tell "the render thread has not drawn a frame yet" apart from "the request was
     * dropped" — but as a *wait* rather than a value to poll. Polling a value guarded by this
     * class's own lock is what made `FrameCaptureSlotTest` flaky: the poller and the enqueuing
     * thread contend for one non-fair lock, and the poller can win it repeatedly.
     *
     * @return true if the count was reached; false if the deadline passed first.
     */
    internal fun awaitQueued(count: Int, timeoutMillis: Long): Boolean = lock.withLock {
        require(count > 0) { "count must be positive, was $count" }
        var remaining = timeoutMillis * NANOS_PER_MILLI
        while (queue.size < count) {
            if (closed) return false
            if (remaining <= 0L) return false
            remaining = enqueued.awaitNanos(remaining)
        }
        true
    }

    /**
     * The [FrameCapture] contract `GameHost` calls: a full frame, right now.
     *
     * Returns [RenderUnavailable.NoCaptureBackend] rather than throwing once the pipeline is
     * closed, because that is the shutdown race an agent host hits on its way out and it is
     * not worth a stack trace.
     */
    override fun capture(): CaptureOutcome {
        if (lock.withLock { closed }) {
            return CaptureOutcome.Unavailable(RenderUnavailable.NoCaptureBackend)
        }
        return CaptureOutcome.Captured(capture(CaptureRequest()).bytes)
    }

    /**
     * Serves every queued request the current frame satisfies. Render thread only.
     *
     * Called by [dev.wildware.udea.render.RenderPipeline] at the capture point. A request
     * naming a future tick stays queued, which is what makes `screenshot(afterTick = n)`
     * deterministic against `step(n)` instead of a race.
     */
    internal fun drain(target: OffscreenTarget) {
        lock.withLock {
            // The overwhelmingly common case, and it must stay inside the lock: `ArrayDeque`
            // is not thread-safe, so an unsynchronised `isEmpty()` fast path would be reading
            // a field another thread is writing in `submit`.
            if (queue.isEmpty()) return

            val tick = clock.tick
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                val pending = iterator.next()
                if (!isDue(pending.request.afterTick, tick)) continue
                iterator.remove()
                pending.outcome = serve(pending.request, target, tick)
                if (pending.outcome is Outcome.Done) completedCaptures++
                settling += pending
            }
        }
        // The pixels are read under the lock -- they have to be, the bound framebuffer is only
        // this frame's inside it -- but the futures are completed out here, because completing
        // one runs whatever the caller chained onto it on this thread. See the class KDoc.
        settle()
    }

    /**
     * Fails everything still queued, so no caller is left waiting on a pipeline that has gone.
     */
    internal fun close() {
        lock.withLock {
            closed = true
            for (pending in queue) {
                pending.outcome = Outcome.Failed(
                    CaptureStalledException("${pending.request}: the render pipeline was closed"),
                )
                settling += pending
            }
            queue.clear()
            // ...and anything waiting for a request to *arrive*, which will now never happen.
            enqueued.signalAll()
        }
        settle()
    }

    /** Completes everything [drain] or [close] settled, with the lock released. */
    private fun settle() {
        for (index in settling.indices) {
            val pending = settling[index]
            when (val outcome = pending.outcome) {
                is Outcome.Done -> pending.future.complete(outcome.result)
                is Outcome.Failed -> pending.future.completeExceptionally(outcome.cause)
                null -> error("a settled request has no outcome: ${pending.request}")
            }
        }
        settling.clear()
    }

    /** Takes a request back off the queue, for a caller that has stopped waiting for it. */
    private fun withdraw(future: CompletableFuture<CaptureResult>) {
        lock.withLock { queue.removeAll { it.future === future } }
    }

    private fun serve(request: CaptureRequest, target: OffscreenTarget, tick: Tick): Outcome =
        try {
            val region = request.region ?: CaptureRegion(0, 0, target.width, target.height)
            require(
                region.x + region.width <= target.width &&
                    region.y + region.height <= target.height,
            ) {
                "$region does not fit $target"
            }
            Outcome.Done(
                CaptureResult(
                    width = region.width,
                    height = region.height,
                    tick = tick,
                    bytes = pixels.readPng(region.x, region.y, region.width, region.height),
                ),
            )
        } catch (failure: IllegalArgumentException) {
            Outcome.Failed(failure)
        } catch (failure: RuntimeException) {
            Outcome.Failed(CaptureStalledException("$request failed while reading pixels", failure))
        }

    private class Pending(
        val request: CaptureRequest,
        val future: CompletableFuture<CaptureResult>,
    ) {
        /** Written under the lock by the render thread; read by it again in `settle`. */
        var outcome: Outcome? = null
    }

    private sealed interface Outcome {
        class Done(val result: CaptureResult) : Outcome
        class Failed(val cause: RuntimeException) : Outcome
    }

    private companion object {

        /**
         * Whether tick [target] has finished simulating by the time the clock reads [now].
         *
         * `SimClock.tick` is the tick *about to be* simulated, so tick `t` is complete exactly
         * when the clock has moved past it. Written as `now >= target` this would serve the
         * frame drawn *before* tick `t` ran — the off-by-one that makes `step(200)` then
         * `screenshot(afterTick = 200)` return a picture of tick 199.
         */
        fun isDue(target: Tick?, now: Tick): Boolean = target == null || now > target

        const val DEFAULT_TIMEOUT_MILLIS: Long = 10_000L

        const val NANOS_PER_MILLI: Long = 1_000_000L

        /** Two overlapping captures is already an unusual frame; four is a generous ceiling. */
        const val INITIAL_SETTLING_CAPACITY: Int = 4
    }
}

/**
 * A capture that could not be served: the render thread stopped drawing, the pipeline closed,
 * or the driver refused the read.
 *
 * A typed exception rather than a blank image or a `null`: an agent doing capture/act/capture
 * would read a blank frame as "the screen went black" and act on it, which is the failure mode
 * [RenderUnavailable] exists to prevent for the mode question and this exists to prevent for
 * the timing one.
 */
public class CaptureStalledException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
