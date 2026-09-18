package dev.wildware.udea.render.gl

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.CaptureStalledException
import dev.wildware.udea.render.capture.FrameCaptureSlot
import dev.wildware.udea.render.capture.capture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A renderer that throws releases every waiting capture instead of stranding it.
 *
 * Split out of `OffscreenBackendTest` (see its KDoc): this claim needs a `KoolBackend` that
 * belongs to no other test in the same JVM, because Kool allows exactly one `KoolContext` per
 * process for the life of the process.
 *
 * The failure this pins: `KoolThread.run` records the exception and exits, and nothing closed
 * the capture slot. Every thread blocked in `capture()` then burned its full timeout and
 * reported "the render thread drew no frame that satisfied it" — a timeout message for what was
 * a renderer exception seconds earlier.
 */
class OffscreenBackendExplodingCaptureTest {

    @Test
    fun `a renderer that throws releases every waiting capture instead of stranding it`() {
        GlAvailability.require()
        val explode = AtomicBoolean(false)
        val registry = RenderRegistry()
        registry.register(RenderPhase.World, { ExplodingRenderSystem(explode) })
        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-test",
                windowWidth = 320,
                windowHeight = 240,
                renderWidth = RENDER_WIDTH,
                renderHeight = RENDER_HEIGHT,
            ),
            registry,
        )
        try {
            val host = GameHost(RenderMode.Offscreen, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()), backend)
            backend.drive(host)
            val slot = backend.pipeline!!.capture!!

            val failure = AtomicReference<Throwable?>(null)
            val done = CountDownLatch(1)
            val worker = Thread {
                try {
                    // A tick far enough out that no ordinary frame will ever serve it, so the
                    // only two ways this returns are the deadline and the pipeline closing.
                    slot.capture(CaptureRequest(afterTick = Tick(9_000_000)), timeoutMillis = 30_000)
                } catch (t: Throwable) {
                    failure.set(t)
                } finally {
                    done.countDown()
                }
            }
            worker.isDaemon = true
            worker.start()
            assertTrue(awaitQueued(slot, count = 1, timeoutMillis = 5_000), "the request never queued")

            explode.set(true)

            assertTrue(
                done.await(15, TimeUnit.SECONDS),
                "the waiter was left on a render loop that had already died",
            )
            val thrown = failure.get()
            assertTrue(thrown is CaptureStalledException, "was $thrown")
            assertTrue(
                "closed" in thrown.message.orEmpty(),
                "the waiter was told it timed out rather than that the pipeline had gone: " +
                    thrown.message,
            )
        } finally {
            backend.close()
        }
    }

    /** Waits until [count] requests are queued on [slot], up to [timeoutMillis]. */
    private fun awaitQueued(slot: FrameCaptureSlot, count: Int, timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (slot.queuedRequests.value < count && System.nanoTime() < deadline) Thread.onSpinWait()
        return slot.queuedRequests.value >= count
    }

    /** Throws out of a frame, standing in for any renderer that hits a bad asset or a null. */
    private class ExplodingRenderSystem(private val armed: AtomicBoolean) : RenderSystem {
        override fun render(target: OffscreenTarget, alpha: Float) {
            if (armed.get()) error("a renderer threw in the middle of a frame")
        }
    }

    private companion object {
        const val RENDER_WIDTH = 128
        const val RENDER_HEIGHT = 64
    }
}
