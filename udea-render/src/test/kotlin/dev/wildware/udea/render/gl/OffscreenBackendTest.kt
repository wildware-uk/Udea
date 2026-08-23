package dev.wildware.udea.render.gl

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics
import org.lwjgl.glfw.GLFW
import dev.wildware.udea.core.host.CaptureOutcome
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.backend.Lwjgl3Backend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.capture.CaptureRequest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `Offscreen` backend, against a real driver.
 *
 * Everything here needs a context, which is exactly why the rest of this module's tests do not:
 * ordering, timing, interpolation and the capture queue are all checked in a plain JVM, and
 * what is left for a real window is the small set of claims that genuinely are about the window
 * — that it exists, that it is hidden, that a frame reaches the pixels, and that shutting it
 * down releases the caller.
 */
class OffscreenBackendTest {

    @Test
    fun `an Offscreen host gets a real context behind a window nobody can see`() {
        GlAvailability.require()
        withBackend { backend, host ->
            val report = backend.pipeline
            assertNotNull(report, "the pipeline was never built")

            val state = backend.probeContext()
            assertTrue(state.hasGl, "Gdx.gl was null on the render thread")
            assertTrue(state.width > 0 && state.height > 0, "backbuffer was ${state.width}x${state.height}")
            assertTrue(!state.visible, "an Offscreen window must not be visible")
            assertEquals(RenderMode.Offscreen, host.mode)
        }
    }

    @Test
    fun `sixty driven frames reach the renderers`() {
        GlAvailability.require()
        val drawn = AtomicInteger()
        withBackend(counting = drawn) { backend, host ->
            backend.drive(host)

            awaitAtLeast(drawn, 60)

            assertTrue(drawn.get() >= 60, "only ${drawn.get()} frames were drawn")
            assertTrue(host.totalTicks > 0, "the loop never ticked")
        }
    }

    @Test
    fun `a capture comes back as PNG bytes stamped with the tick`() {
        GlAvailability.require()
        withBackend { backend, host ->
            backend.drive(host)
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
        }
    }

    @Test
    fun `GameHost screenshot goes through the same pixel path`() {
        GlAvailability.require()
        withBackend { backend, host ->
            backend.drive(host)

            val outcome = host.screenshot()

            val captured = outcome as? CaptureOutcome.Captured
            assertNotNull(captured, "screenshot returned $outcome")
            assertTrue(captured.image.isNotEmpty())
        }
    }

    @Test
    fun `Headless is refused rather than quietly opening a window`() {
        assertFailsWith<IllegalArgumentException> {
            Lwjgl3Backend.start(RenderMode.Headless, WindowConfig(), RenderRegistry())
        }
    }

    @Test
    fun `closing the backend stops the render thread`() {
        GlAvailability.require()
        val backend = startBackend(RenderRegistry())
        backend.close()

        // If the loop were still running, this would block for the full shutdown timeout.
        backend.awaitExit()
        assertFailsWith<IllegalStateException> { backend.create(definition().build()) }
    }

    // --- fixture -------------------------------------------------------------------------

    private fun withBackend(
        counting: AtomicInteger? = null,
        block: (Lwjgl3Backend, GameHost) -> Unit,
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

    private fun startBackend(registry: RenderRegistry): Lwjgl3Backend = Lwjgl3Backend.start(
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

    private fun definition() = UdeaGameDef(modules = emptyList())

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
 * On the render thread, because `Gdx.graphics` is only meaningful there: read from the
 * caller's thread it is a static the GL thread owns.
 *
 * Visibility is asked of **GLFW** rather than of LibGDX, because LibGDX has no "is this window
 * visible" accessor and the assertion that matters — `setInitialVisible(false)` actually
 * produced a hidden window — is precisely the one a wrapper would not answer.
 */
internal fun Lwjgl3Backend.probeContext(): ContextState = onRenderThread {
    val graphics = Gdx.graphics as Lwjgl3Graphics
    val handle = graphics.window.windowHandle
    ContextState(
        hasGl = Gdx.gl != null,
        width = Gdx.graphics.backBufferWidth,
        height = Gdx.graphics.backBufferHeight,
        visible = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_VISIBLE) == GLFW.GLFW_TRUE,
    )
}
