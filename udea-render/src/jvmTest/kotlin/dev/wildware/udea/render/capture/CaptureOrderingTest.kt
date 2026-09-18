package dev.wildware.udea.render.capture

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.support.FakePixelSource
import dev.wildware.udea.render.support.FrameLog
import dev.wildware.udea.render.support.ManualFrameClock
import dev.wildware.udea.render.support.RecordingSurface
import dev.wildware.udea.render.support.overlayScene
import dev.wildware.udea.render.support.scene
import dev.wildware.udea.render.support.testTargets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where in a frame the capture happens, which is the load-bearing half of spec 3.7.
 *
 * Two claims, and both have to hold or the agent activity overlay leaks into an agent's
 * screenshots:
 *
 * 1. the capture is drained **after** the last capturable [dev.wildware.udea.render.RenderSystem]
 *    — including [RenderPhase.Debug] — so everything the agent is meant to see is in the frame;
 * 2. the capture is drained **before** the offscreen surface is unbound and before any
 *    `OverlaySystem` draws, so nothing the agent is not meant to see can be.
 */
class CaptureOrderingTest {

    private val log = FrameLog()

    @Test
    fun `the capture is claimed after every renderer, and read at the top of the next frame`() {
        // On Kool the pipeline records a frame and Kool draws it afterwards, so `drain` (the
        // capture point) only claims a request against the frame just recorded, and `collect`
        // - at the top of the *next* `render` call, before that frame's own surface:begin -
        // reads the pixels and settles it (see `FrameCaptureSlot`'s KDoc). So the two claims
        // spec 3.7 makes now span two frames rather than one:
        //
        // 1. everything the agent is meant to see is in the claiming frame (world, then debug);
        // 2. nothing the agent is not meant to see is: the read happens before the collecting
        //    frame has drawn anything of its own, so no overlay pixels from any frame can be in
        //    it, and this frame's own surface is not yet even begun.
        val pixels = FakePixelSource(log)
        val registry = RenderRegistry(ManualFrameClock())
        registry.scene(RenderPhase.World, "world", log)
        // The sentinel: the last capturable renderer in the frame. If the capture ran before
        // this, an agent's screenshot would be missing every debug shape.
        registry.scene(RenderPhase.Debug, "debug", log)
        registry.overlayScene("agentPanel", log)

        val pipeline = registry.build(
            world(),
            ctx,
            testTargets(surface = RecordingSurface(log), pixels = pixels),
        )
        val result = requestOnAnotherThread(pipeline.capture!!)
        awaitQueued(pipeline.capture!!)
        // Binding happens in `build`, and this test is about the order *within* and *across*
        // frames.
        log.clear()

        pipeline.render(0f)

        assertEquals(
            listOf(
                "surface:begin",
                "draw:world@0.0",
                "draw:debug@0.0",
                "surface:endAndPresent",
                "overlay:agentPanel@0.0",
            ),
            log.calls,
            "the claiming frame must not read pixels itself",
        )
        assertNull(result.get(), "the request settled before any frame had read it")
        log.clear()

        pipeline.render(0f)

        assertEquals(
            listOf(
                "capture:read",
                "surface:begin",
                "draw:world@0.0",
                "draw:debug@0.0",
                "surface:endAndPresent",
                "overlay:agentPanel@0.0",
            ),
            log.calls,
            "the collecting frame must read pixels before it draws anything of its own",
        )
        assertTrue(awaitSettled(result), "the capture was never served")
    }

    @Test
    fun `a pipeline with no pixel source has no capture slot at all`() {
        val registry = RenderRegistry(ManualFrameClock())
        registry.scene(RenderPhase.World, "world", log)

        val pipeline = registry.build(world(), ctx, testTargets())

        // Not "a slot that fails": no slot, so GameHost reports no_capture_backend, which names
        // the wiring fault instead of looking like a render thread that has stopped drawing.
        assertNull(pipeline.capture)
    }

    @Test
    fun `disposing the pipeline releases a caller waiting on a capture`() {
        val registry = RenderRegistry(ManualFrameClock())
        val pipeline = registry.build(
            world(),
            ctx,
            testTargets(surface = RecordingSurface(log), pixels = FakePixelSource(log)),
        )
        val slot = pipeline.capture!!
        val failure = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try {
                slot.capture(CaptureRequest(afterTick = dev.wildware.udea.core.Tick(9_000)))
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        worker.isDaemon = true
        worker.start()
        awaitQueued(slot)

        pipeline.dispose()

        worker.join(TimeUnit.SECONDS.toMillis(5))
        assertTrue(failure.get() is CaptureStalledException, "was ${failure.get()}")
    }

    @Test
    fun `a renderer that throws still unbinds the surface, and serves no half-drawn frame`() {
        // Two claims in one frame, and they pull in opposite directions on purpose.
        //
        // The surface must be unbound: without a `finally` the offscreen framebuffer stays
        // bound for the rest of the process, the window is never presented again, and the
        // exception goes on to kill the render loop with the FBO still live.
        //
        // The capture must *not* be served: a frame that threw part-way through drawing is a
        // half-drawn frame, and handing it back would give an agent a picture of a partial
        // world to reason about. Waiters are released by `FrameCaptureSlot.close`, which
        // `KoolBackend` wires to the render loop's exit -- see `OffscreenBackendTest`.
        val pixels = FakePixelSource(log)
        val registry = RenderRegistry(ManualFrameClock())
        registry.scene(RenderPhase.World, "world", log)
        registry.register(RenderPhase.Debug, { ThrowingRenderSystem() })
        registry.overlayScene("agentPanel", log)
        val pipeline = registry.build(
            world(),
            ctx,
            testTargets(surface = RecordingSurface(log), pixels = pixels),
        )
        val result = requestOnAnotherThread(pipeline.capture!!)
        awaitQueued(pipeline.capture!!)
        log.clear()

        assertFailsWith<IllegalStateException> { pipeline.render(0f) }

        assertEquals(
            listOf("surface:begin", "draw:world@0.0", "surface:endAndPresent"),
            log.calls,
            "the offscreen surface was left bound by a renderer that threw",
        )
        assertEquals(emptyList(), pixels.requests, "a half-drawn frame was served to a capture")
        assertNull(result.get())
    }

    /** Throws out of a frame, standing in for any renderer that hits a bad asset or a null. */
    private class ThrowingRenderSystem : dev.wildware.udea.render.RenderSystem {
        override fun render(
            target: dev.wildware.udea.render.OffscreenTarget,
            alpha: Float,
        ): Unit = error("a renderer threw in the middle of a frame")
    }

    // --- helpers -------------------------------------------------------------------------

    private fun requestOnAnotherThread(slot: FrameCaptureSlot): AtomicReference<CaptureResult?> {
        val result = AtomicReference<CaptureResult?>(null)
        val worker = Thread { result.set(slot.capture(CaptureRequest(), timeoutMillis = 10_000)) }
        worker.isDaemon = true
        worker.start()
        return result
    }

    /**
     * Waits on the slot's own condition rather than polling `queuedRequests`.
     *
     * Same reasoning as `FrameCaptureSlotTest.awaitQueued`: reading that property takes the
     * slot's non-fair lock, and a thread spinning on it can barge ahead of the worker parked
     * trying to enqueue — so the poller starves the thread it is waiting for.
     */
    private fun awaitQueued(slot: FrameCaptureSlot) {
        val queued = runBlocking {
            withTimeoutOrNull(TimeUnit.SECONDS.toMillis(5)) {
                slot.queuedRequests.first { it >= 1 }
                true
            }
        }
        assertTrue(queued == true, "no capture request was queued")
    }

    private fun awaitSettled(result: AtomicReference<CaptureResult?>): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (result.get() != null) return true
            Thread.onSpinWait()
        }
        return false
    }

    private val ctx: GameContext = testGameContext(seed = 3L)

    private fun world(): World = configureWorld { injectables { gameContext(ctx) } }
}
