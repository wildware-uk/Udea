package dev.wildware.udea.agent.host

import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.Json
import dev.wildware.udea.agent.dispatch.AgentContext
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * How a capture tool answers: queue the request, wait for a frame without blocking, file the PNG.
 *
 * `render.screenshot`, `render.screenshot_region` and `editor.screenshot` all capture the next frame
 * of some pass and answer with the same artifact. One place for it, so the grace period, the failure
 * wording and the answer's fields cannot drift between them.
 *
 * ## Why the answer is polled
 *
 * A tool runs inside a `SimBarrier` drain, and on an `Offscreen` or `Windowed` host the thread running
 * that drain **is** the render thread. A request is claimed at one frame's capture point and read at
 * the top of the next, so its answer can be assembled only after a frame this thread has not drawn yet.
 * [AgentContext.answerWhenReady] checks again on every host iteration until the frame has settled it
 * or [graceMillis] has passed - never blocking, and still completing the command with a real answer.
 *
 * @param artifacts where a capture is filed; `null` refuses every capture.
 * @param graceMillis how long a queued capture may wait for a frame before the render loop is
 *   reported dead.
 */
internal class CaptureFiling(private val artifacts: AgentArtifacts?, private val graceMillis: Long) {

    /**
     * Queues [request] and answers for it once a frame has settled it.
     *
     * @param describe adds the tool's own fields to a successful answer, after the artifact's.
     * @return `null` once the capture has been queued - the `answerWhenReady` idiom: a value here
     *   would be a second answer to one command id - or the failure that stopped it being queued.
     */
    fun answer(context: AgentContext, request: () -> Future<CaptureFrame>, describe: Json.() -> Unit): AgentResult? {
        val store = artifacts ?: return AgentResult.failed(
            AgentHostErrors.NO_ARTIFACT_STORE,
            "this instance has no artifact store, so a capture has nowhere to go",
        )
        val pending = runCatching(request).getOrElse { failure ->
            return AgentResult.failed(
                AgentHostErrors.CAPTURE_FAILED,
                "the renderer refused the capture request: ${failure.message ?: failure}",
            )
        }
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(graceMillis)
        context.answerWhenReady { poll(pending, store, deadlineNanos, describe) }
        return null
    }

    /**
     * Checks whether [pending] has settled, without blocking for it: `null` - ask again next tick -
     * until the frame that serves it has been drawn or [deadlineNanos] has passed.
     */
    private fun poll(
        pending: Future<CaptureFrame>,
        store: AgentArtifacts,
        deadlineNanos: Long,
        describe: Json.() -> Unit,
    ): AgentResult? {
        if (!pending.isDone) {
            if (System.nanoTime() < deadlineNanos) return null
            pending.cancel(false)
            return AgentResult.failed(
                AgentHostErrors.CAPTURE_FAILED,
                "no frame was drawn for this capture within ${graceMillis}ms; the render loop has stopped drawing",
            )
        }
        return file(pending, store, describe)
    }

    /** Reads a [pending] already known to be settled, and files it. */
    private fun file(pending: Future<CaptureFrame>, store: AgentArtifacts, describe: Json.() -> Unit): AgentResult {
        val frame = try {
            // Already known done by the caller (`poll`): this reads the value, it does not wait.
            pending.get()
        } catch (failed: ExecutionException) {
            val cause = failed.cause ?: failed
            return AgentResult.failed(
                AgentHostErrors.CAPTURE_FAILED,
                "the renderer could not capture a frame: ${cause.message ?: cause}",
            )
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return AgentResult.failed(
                AgentHostErrors.CAPTURE_FAILED,
                "the simulation thread was interrupted while collecting a capture",
            )
        }

        val id = store.put(frame.image, AgentArtifacts.PNG)
            ?: return AgentResult.failed(
                AgentHostErrors.NO_ARTIFACT_STORE,
                "the capture succeeded but could not be written to ${store.root}",
            )
        val artifact = store.get(id)
        // Path first, id second: the path is ~10 tokens and covers the same-machine case, and the id
        // is what a remote agent hands to GET /artifact. Bytes never travel in a digest.
        return AgentResult.ok {
            put("artifactId", id.value)
            put("path", artifact?.path?.toString())
            put("w", frame.width)
            put("h", frame.height)
            put("tick", frame.tick)
            describe()
        }
    }

    override fun toString(): String = "CaptureFiling(artifacts=${artifacts != null}, grace=${graceMillis}ms)"

    companion object {

        /**
         * How long the deferred answer waits for a frame that should already have been drawn.
         *
         * Half a second: long enough to absorb a stalled frame on a loaded machine, short enough
         * that a host which shares its thread between simulation and rendering - where crossing
         * this is a defect, not a delay - reports rather than freezing the game for a human.
         */
        const val DEFAULT_GRACE_MILLIS: Long = 500L
    }
}
