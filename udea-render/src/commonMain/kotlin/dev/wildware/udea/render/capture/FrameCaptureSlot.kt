package dev.wildware.udea.render.capture

import dev.wildware.udea.core.SimClock
import dev.wildware.udea.core.Tick
import dev.wildware.udea.render.OffscreenTarget
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The request slot the render pipeline serves captures from.
 *
 * ## Why a slot and not a method call
 *
 * A capture request arrives on some other thread and can only be served at one moment in the
 * frame: after the last [dev.wildware.udea.render.RenderSystem] has drawn and before any
 * [dev.wildware.udea.render.OverlaySystem] does. Making the request a queued value rather than a
 * call means the moment is enforced by the pipeline once, instead of being a rule every caller has
 * to know.
 *
 * ## Two steps, because Kool draws after the pipeline records
 *
 * On LibGDX the capture point read pixels out of a bound framebuffer, so the read happened at the
 * capture point. On Kool the pipeline *records* a frame and Kool draws it afterwards, so at the
 * capture point there are no pixels yet. The slot therefore splits the one step in two:
 *
 * - [drain], at the capture point, **claims** every due request for this frame and stamps it with
 *   the tick the frame shows. This is the ordering decision, and it stays exactly where it was.
 * - [collect], at the top of the next frame and before anything draws, reads the pixels that frame
 *   produced and settles the claimed requests. Nothing has touched the pass between the two.
 *
 * ## Why the answer is a `Deferred` and not a return value
 *
 * [submit] never blocks, and that is what makes the agent's render toolset possible at all: a tool
 * call runs inside a `SimBarrier` drain, which on an `Offscreen` or `Windowed` host runs on the
 * render thread itself, so a screenshot tool that waited for the next frame would be waiting for
 * itself. The tool submits and returns; a later frame completes the `Deferred`.
 *
 * ## Threading
 *
 * [submit] is safe from any thread; [drain], [collect] and [close] run on the render thread. One
 * lock guards the queues, and **no `Deferred` is completed while it is held**: completing one
 * resumes whatever the caller attached, and running a caller's code under this class's lock is how
 * a completion that touched the slot again would deadlock the render thread.
 */
public class FrameCaptureSlot internal constructor(
    private val pixels: PixelSource,
    private val clock: SimClock,
) {

    private val lock = SynchronizedObject()

    /** Submitted and not yet claimed by a frame. */
    private val queue = ArrayDeque<Pending>()

    /** Claimed by the frame just recorded; read at the top of the next. */
    private val claimed = ArrayList<Pending>(INITIAL_CAPACITY)

    /**
     * Requests settled by the current [collect] or [close], held between the lock being released
     * and their `Deferred`s being completed. A field, so the per-frame path allocates nothing.
     */
    private val settling = ArrayList<Pending>(INITIAL_CAPACITY)

    private var closed = false

    /** True once the pipeline has closed this slot; every later [submit] fails at once. */
    public val isClosed: Boolean get() = synchronized(lock) { closed }

    private val queuedCount = MutableStateFlow(0)

    /** Captures completed since construction. A health signal, not state. */
    public var completedCaptures: Long = 0L
        private set

    /**
     * How many requests are waiting for a frame to claim them.
     *
     * A flow rather than a number so that a caller - and a test - can *wait* for a request to have
     * arrived instead of polling for it. Published because "the render thread has not drawn a frame
     * yet" and "the request was dropped" are otherwise the same silence.
     */
    public val queuedRequests: StateFlow<Int> get() = queuedCount

    /**
     * Queues [request] and returns at once with the `Deferred` a later frame completes.
     *
     * It completes with a [CaptureResult] once a frame satisfying the request has been drawn and
     * read, exceptionally with a [CaptureStalledException] if the pipeline is torn down first, or
     * with an [IllegalArgumentException] if the region does not fit the surface.
     */
    public fun submit(request: CaptureRequest): Deferred<CaptureResult> {
        val result = CompletableDeferred<CaptureResult>()
        val refused = synchronized(lock) {
            if (closed) {
                true
            } else {
                queue.addLast(Pending(request, result))
                queuedCount.value = queue.size
                false
            }
        }
        // Outside the lock: see the class KDoc.
        if (refused) result.completeExceptionally(CaptureStalledException("$request: the render pipeline is closed"))
        return result
    }

    /**
     * Claims every queued request the frame being recorded satisfies. Render thread only, at the
     * capture point.
     *
     * A request naming a future tick stays queued, which is what makes `screenshot(afterTick = n)`
     * deterministic against `step(n)` instead of a race. A request whose region does not fit is
     * failed here rather than claimed: there is no frame that could ever satisfy it.
     */
    internal fun drain(target: OffscreenTarget) {
        synchronized(lock) {
            if (queue.isEmpty()) return
            val tick = clock.tick
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                val pending = iterator.next()
                if (pending.result.isCancelled) {
                    // The caller gave up - a timed-out wait cancels its `Deferred` - so no frame
                    // reads pixels for it, and the queue does not keep one dead entry per timeout.
                    iterator.remove()
                    continue
                }
                if (!isDue(pending.request.afterTick, tick)) continue
                iterator.remove()
                val region = pending.request.region ?: CaptureRegion(0, 0, target.width, target.height)
                if (region.x + region.width > target.width || region.y + region.height > target.height) {
                    pending.outcome = Outcome.Failed(IllegalArgumentException("$region does not fit $target"))
                    settling += pending
                } else {
                    pending.region = region
                    pending.tick = tick
                    claimed += pending
                }
            }
            queuedCount.value = queue.size
        }
        settle()
    }

    /**
     * Reads the pixels the last recorded frame produced for every request it claimed, and settles
     * them. Render thread only, before anything draws this frame.
     */
    internal fun collect() {
        synchronized(lock) {
            if (claimed.isEmpty()) return
            for (index in claimed.indices) {
                val pending = claimed[index]
                if (pending.result.isCancelled) continue
                pending.outcome = read(pending)
                if (pending.outcome is Outcome.Done) completedCaptures++
                settling += pending
            }
            claimed.clear()
        }
        settle()
    }

    /**
     * Fails everything queued or claimed, so no caller is left waiting on a pipeline that has gone.
     *
     * @param cause what stopped the render loop, when something threw: every failure carries it as its
     *   cause and names it, so a caller is told why the frame never came and not only that it did not
     *   (issue #275). `null` for an orderly close.
     */
    internal fun close(cause: Throwable? = null) {
        synchronized(lock) {
            closed = true
            for (pending in queue) fail(pending, "the render pipeline was closed", cause)
            for (pending in claimed) fail(pending, "the render pipeline was closed before the frame was read", cause)
            queue.clear()
            claimed.clear()
            queuedCount.value = 0
        }
        settle()
    }

    private fun fail(pending: Pending, why: String, cause: Throwable?) {
        val because = if (cause == null) "" else ", because the render loop threw: $cause"
        pending.outcome = Outcome.Failed(CaptureStalledException("${pending.request}: $why$because", cause))
        settling += pending
    }

    /** Completes everything settled, with the lock released. */
    private fun settle() {
        for (index in settling.indices) {
            val pending = settling[index]
            when (val outcome = pending.outcome) {
                is Outcome.Done -> pending.result.complete(outcome.result)
                is Outcome.Failed -> pending.result.completeExceptionally(outcome.cause)
                null -> error("a settled request has no outcome: ${pending.request}")
            }
        }
        settling.clear()
    }

    private fun read(pending: Pending): Outcome {
        val region = checkNotNull(pending.region) { "a claimed request has no region" }
        val tick = checkNotNull(pending.tick) { "a claimed request has no tick" }
        return try {
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
            Outcome.Failed(CaptureStalledException("${pending.request} failed while reading pixels", failure))
        }
    }

    private class Pending(
        val request: CaptureRequest,
        val result: CompletableDeferred<CaptureResult>,
    ) {
        /** Set when a frame claims the request. */
        var region: CaptureRegion? = null

        /** The tick the claiming frame showed. */
        var tick: Tick? = null

        /** Written under the lock; read again in `settle`. */
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
         * `SimClock.tick` is the tick *about to be* simulated, so tick `t` is complete exactly when
         * the clock has moved past it. Written as `now >= target` this would serve the frame drawn
         * *before* tick `t` ran - the off-by-one that makes `step(200)` then
         * `screenshot(afterTick = 200)` return a picture of tick 199.
         */
        fun isDue(target: Tick?, now: Tick): Boolean = target == null || now > target

        /** Two overlapping captures is already an unusual frame; four is a generous ceiling. */
        const val INITIAL_CAPACITY: Int = 4
    }
}

/**
 * A capture that could not be served: the render thread stopped drawing, the pipeline closed, or
 * the driver refused the read.
 *
 * A typed exception rather than a blank image or a `null`: an agent doing capture/act/capture would
 * read a blank frame as "the screen went black" and act on it.
 */
public class CaptureStalledException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
