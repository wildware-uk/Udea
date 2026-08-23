package dev.wildware.udea.render.capture

import dev.wildware.udea.core.SimClock
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.host.CaptureOutcome
import dev.wildware.udea.core.host.FrameCapture
import dev.wildware.udea.core.host.RenderUnavailable
import dev.wildware.udea.render.OffscreenTarget
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The request slot the render pipeline drains at the capture point.
 *
 * ## Why a slot and not a method call
 *
 * A capture request arrives on the agent host's thread and can only be served on the render
 * thread, at one exact moment in the frame: after the last [dev.wildware.udea.render.RenderSystem]
 * has drawn and before the offscreen surface is unbound. `glReadPixels` reads the *bound*
 * framebuffer, so a capture served anywhere else reads either a half-drawn frame or the window
 * the agent activity overlay is about to draw on (spec 3.7). Making the request a queued value
 * rather than a call means the moment is enforced by the pipeline once, instead of being a rule
 * every caller has to know.
 *
 * ## What it does not do
 *
 * It does not name files, hold a `lastPath`, or announce anything. The old `ScreenCapture` did
 * all three and callers had to parse an event-log line to find their own screenshot. Bytes go
 * back to the caller that asked for them; the artifact store is the agent host's.
 *
 * ## Threading
 *
 * [capture] blocks the calling thread; [drain] runs on the render thread. Both are guarded by
 * one lock, and a waiter is always woken by exactly one of: its request being fulfilled, its
 * request failing, or [close]. A waiter that is never woken would hang an agent's command loop,
 * so [capture] also has a deadline and reports crossing it rather than waiting forever.
 */
public class FrameCaptureSlot internal constructor(
    private val pixels: PixelSource,
    private val clock: SimClock,
) : FrameCapture {

    private val lock = ReentrantLock()
    private val settled = lock.newCondition()
    private val queue = ArrayDeque<Pending>()
    private var closed = false

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
     * Requests a frame and waits for it.
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
        val pending = Pending(request)

        val outcome = lock.withLock {
            if (closed) throw CaptureStalledException("$request: the render pipeline is closed")
            queue.addLast(pending)

            var remaining = timeoutMillis * NANOS_PER_MILLI
            var settledOutcome = pending.outcome
            while (settledOutcome == null) {
                if (remaining <= 0L) {
                    queue.remove(pending)
                    throw CaptureStalledException(
                        "$request was not served within ${timeoutMillis}ms: the render thread " +
                            "drew no frame that satisfied it",
                    )
                }
                remaining = settled.awaitNanos(remaining)
                settledOutcome = pending.outcome
            }
            settledOutcome
        }

        return when (outcome) {
            is Outcome.Done -> outcome.result
            is Outcome.Failed -> throw outcome.cause
        }
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
            // a field another thread is writing in `capture`.
            if (queue.isEmpty()) return

            val tick = clock.tick
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                val pending = iterator.next()
                if (!isDue(pending.request.afterTick, tick)) continue
                iterator.remove()
                pending.outcome = serve(pending.request, target, tick)
                if (pending.outcome is Outcome.Done) completedCaptures++
            }
            settled.signalAll()
        }
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
            }
            queue.clear()
            settled.signalAll()
        }
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

    private class Pending(val request: CaptureRequest) {
        /** Written under the lock by the render thread, read under it by the waiter. */
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
