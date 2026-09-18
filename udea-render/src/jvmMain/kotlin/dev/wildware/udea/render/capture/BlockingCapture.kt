package dev.wildware.udea.render.capture

import dev.wildware.udea.core.host.CaptureOutcome
import dev.wildware.udea.core.host.FrameCapture
import dev.wildware.udea.core.host.RenderUnavailable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Queues [request] and blocks the calling thread until a frame has served it.
 *
 * For a caller that is **not** the render thread - a test, a tool running on its own thread. On the
 * render thread this waits for a frame that cannot be drawn until it returns, and fails on its
 * deadline; [FrameCaptureSlot.submit] is the non-blocking door for that case.
 *
 * @throws CaptureStalledException if no frame served the request inside [timeoutMillis], or the
 *   pipeline closed first. A timed-out request is withdrawn, so a frame drawn a moment later does
 *   not read pixels for a caller that has already been told it failed.
 * @throws IllegalArgumentException if the region does not fit the capturable target.
 */
public fun FrameCaptureSlot.capture(
    request: CaptureRequest,
    timeoutMillis: Long = DEFAULT_CAPTURE_TIMEOUT_MILLIS,
): CaptureResult {
    require(timeoutMillis > 0) { "timeout must be positive, was $timeoutMillis" }
    val result = submit(request)
    return runBlocking {
        try {
            withTimeout(timeoutMillis) { result.await() }
        } catch (_: TimeoutCancellationException) {
            result.cancel()
            throw CaptureStalledException(
                "$request was not served within ${timeoutMillis}ms: the render thread drew no " +
                    "frame that satisfied it",
            )
        }
    }
}

/** Ten seconds: a cold first frame on a software rasteriser, with room. Only a stall crosses it. */
public const val DEFAULT_CAPTURE_TIMEOUT_MILLIS: Long = 10_000L

/**
 * The [FrameCapture] contract `GameHost` calls - a full frame, now - over a [FrameCaptureSlot].
 *
 * Returns [RenderUnavailable.NoCaptureBackend] rather than throwing once the pipeline is closed,
 * because that is the shutdown race an agent host hits on its way out and it is not worth a stack
 * trace.
 */
internal class BlockingFrameCapture(private val slot: FrameCaptureSlot) : FrameCapture {

    override fun capture(): CaptureOutcome {
        if (slot.isClosed) return CaptureOutcome.Unavailable(RenderUnavailable.NoCaptureBackend)
        return CaptureOutcome.Captured(slot.capture(CaptureRequest()).bytes)
    }

    override fun toString(): String = "BlockingFrameCapture($slot)"
}
