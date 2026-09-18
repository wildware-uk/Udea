package dev.wildware.udea.render.gl

import de.fabmax.kool.KoolSystem
import dev.wildware.udea.core.host.CaptureOutcome
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
import dev.wildware.udea.render.capture.capture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `Offscreen` backend's ordinary path, against a real Kool context on OpenGL: that a context
 * exists, is hidden, drives frames, and hands back pixels through both the direct capture path
 * and `GameHost.screenshot()`. `Headless` being refused rides along here too, because it creates
 * no context and is free to share a JVM with whatever else is in this file.
 *
 * This class's four other lifecycle claims — a second `create` refused, a renderer's exception
 * releasing waiters, and `close()` stopping the render thread — each need their *own* fresh
 * `KoolBackend`, and Kool allows only one `KoolContext` per JVM ever. `forkEvery = 1` gives one
 * fresh JVM per test *class*, so each of those claims now has its own file:
 * [OffscreenBackendSecondCreateTest], [OffscreenBackendExplodingCaptureTest],
 * [OffscreenBackendShutdownTest].
 */
class OffscreenBackendTest {

    @Test
    fun `an Offscreen host boots, drives frames, and hands back pixels through both paths`() {
        GlAvailability.require()
        val drawn = AtomicInteger()
        withBackend(counting = drawn) { backend, host ->
            // 1. A real context behind a window nobody can see.
            val report = backend.pipeline
            assertNotNull(report, "the pipeline was never built")
            val state = backend.probeContext()
            assertTrue(state.hasGl, "the render backend was not OpenGL")
            assertTrue(state.width > 0 && state.height > 0, "backbuffer was ${state.width}x${state.height}")
            assertTrue(!state.visible, "an Offscreen window must not be visible")
            assertEquals(RenderMode.Offscreen, host.mode)

            // 2. Sixty driven frames reach the renderers.
            backend.drive(host)
            awaitAtLeast(drawn, 60)
            assertTrue(drawn.get() >= 60, "only ${drawn.get()} frames were drawn")
            assertTrue(host.totalTicks > 0, "the loop never ticked")

            // 3. A capture comes back as PNG bytes stamped with the tick.
            val slot = backend.pipeline!!.capture!!
            val result = slot.capture(CaptureRequest())
            assertEquals(RENDER_WIDTH, result.width)
            assertEquals(RENDER_HEIGHT, result.height)
            assertTrue(result.bytes.size > 8, "no image came back")
            assertEquals(
                listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()),
                result.bytes.take(4),
                "the bytes are not a PNG",
            )

            // 4. GameHost.screenshot() goes through the same pixel path.
            val outcome = host.screenshot()
            val captured = outcome as? CaptureOutcome.Captured
            assertNotNull(captured, "screenshot returned $outcome")
            assertTrue(captured.image.isNotEmpty())
        }
    }

    @Test
    fun `Headless is refused rather than quietly opening a window`() {
        assertFailsWith<IllegalArgumentException> {
            KoolBackend.start(RenderMode.Headless, WindowConfig(), RenderRegistry())
        }
    }

    // --- fixture -------------------------------------------------------------------------

    private fun withBackend(
        counting: AtomicInteger? = null,
        block: (KoolBackend, GameHost) -> Unit,
    ) {
        val registry = RenderRegistry()
        registry.register(RenderPhase.World, { CountingRenderSystem(counting ?: AtomicInteger()) })
        val backend = startBackend(registry)
        try {
            val host = GameHost(RenderMode.Offscreen, definition(), backend)
            block(backend, host)
        } finally {
            backend.close()
        }
    }

    private fun startBackend(registry: RenderRegistry): KoolBackend = KoolBackend.start(
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

    private fun definition() = UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList())

    private fun awaitAtLeast(counter: AtomicInteger, target: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (counter.get() < target && System.nanoTime() < deadline) Thread.onSpinWait()
    }

    private class CountingRenderSystem(private val frames: AtomicInteger) : RenderSystem {
        override fun render(target: OffscreenTarget, alpha: Float) {
            frames.incrementAndGet()
        }
    }

    private companion object {
        const val RENDER_WIDTH = 128
        const val RENDER_HEIGHT = 64
    }
}

/** What the render thread can see about its own context. */
internal class ContextState(
    val hasGl: Boolean,
    val width: Int,
    val height: Int,
    val visible: Boolean,
)

/**
 * Asks the render thread about its own context.
 *
 * On the render thread, because the context is only meaningful there: read from the caller's
 * thread it is a static the render thread owns.
 *
 * Visibility is asked of Kool's own [de.fabmax.kool.KoolWindow.flags] rather than through a
 * platform-specific window handle, because that is the one place "is this window visible" is
 * published on every Kool backend, and the assertion that matters -- `showWindowOnStart = false`
 * actually produced a hidden window -- is precisely the one a lower-level check would not answer
 * any more directly.
 */
internal fun KoolBackend.probeContext(): ContextState = onRenderThread {
    val ctx = KoolSystem.requireContext()
    val size = ctx.window.framebufferSize
    ContextState(
        hasGl = ctx.backend.name.contains("OpenGL", ignoreCase = true),
        width = size.x,
        height = size.y,
        visible = ctx.window.flags.isVisible,
    )
}
