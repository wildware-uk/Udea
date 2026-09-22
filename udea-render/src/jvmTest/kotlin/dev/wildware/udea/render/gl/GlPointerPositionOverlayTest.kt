package dev.wildware.udea.render.gl

import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.OverlaySystem
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.ScreenTarget
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteBatch2D
import dev.wildware.udea.render.input.PointerPosition
import dev.wildware.udea.render.kool.KoolPointer
import org.lwjgl.opengl.GL11
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #275 part 1: **a game reads where the mouse is through public API alone, with no interface
 * layer, and what it reads is where the cursor really is on the window.**
 *
 * The public way is `PointerPosition` (issue #262), which `KoolPointer()` implements with no
 * `UiLayer`. robot-game did not find it and integrated Kool's own pointer by hand. This drives the
 * whole path a game would take. A `KoolPointer()` is built with no interface. The cursor is moved
 * through Kool's own GLFW callback, as a real mouse moves it. An overlay draws a crosshair where
 * `pointerX`/`pointerY` say, turning the window's y-down pixels into the screen batch's y-up ones. The
 * window is then read back and the crosshair has to be on the pixel the cursor was sent to.
 *
 * It moves the cursor twice. A reading that stuck at its first value, or one never taken from the
 * cursor at all, fails the second move. The first frame asks [PointerPosition.isPointerOver] before
 * any move, so a position reported for a mouse that has never been over the window fails as well.
 */
class GlPointerPositionOverlayTest {

    @Test
    fun `an overlay draws a crosshair where the cursor is, read through PointerPosition alone`() {
        GlAvailability.require()

        val pointer = AtomicReference<PointerPosition>(PointerPosition.NONE)
        val registry = RenderRegistry()
        registry.overlay({ resources -> Crosshair(resources.batch) { pointer.get() } })
        val probe = CentreProbe()
        registry.overlay({ probe })

        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(title = "udea-pointer-position", windowWidth = WIDTH, windowHeight = HEIGHT),
            registry,
        )
        try {
            val host = GameHost(RenderMode.Offscreen, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()), backend)
            backend.drive(host)
            // The public constructor, with no interface: the way a game with no UiLayer builds one.
            // On the render thread, because it joins Kool's input stack.
            val mouse = backend.onRenderThread { KoolPointer() }
            pointer.set(mouse)
            awaitFrames(probe, probe.frames.get() + 3)
            assertFalse(
                backend.onRenderThread { mouse.isPointerOver },
                "the pointer reported a position before the cursor was ever over the window",
            )

            for ((x, y) in listOf(60 to 50, 250 to 170)) {
                backend.moveMouseTo(x.toDouble(), y.toDouble())
                awaitFrames(probe, probe.frames.get() + 3)
                val read = backend.onRenderThread { Triple(mouse.isPointerOver, mouse.pointerX, mouse.pointerY) }
                assertTrue(read.first, "the cursor was moved onto the window and isPointerOver says it is not")
                assertEquals(x.toFloat(), read.second, "pointerX after moving the cursor to ($x, $y)")
                assertEquals(y.toFloat(), read.third, "pointerY after moving the cursor to ($x, $y)")

                // The pixel under the cursor, in GL's bottom-up rows, is the crosshair's colour.
                probe.at.set(x to HEIGHT - 1 - y)
                val before = probe.frames.get()
                awaitFrames(probe, before + 3)
                assertEquals(
                    CROSSHAIR_RGB,
                    probe.rgb.get(),
                    "the window pixel under the cursor at ($x, $y) is not the crosshair drawn from PointerPosition",
                )
            }
        } finally {
            backend.close()
        }
    }

    private fun awaitFrames(probe: CentreProbe, target: Int) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
        while (probe.frames.get() < target && System.nanoTime() < deadline) Thread.onSpinWait()
        assertTrue(probe.frames.get() >= target, "the render thread stopped drawing")
    }

    /** Where a game would draw a cursor of its own: at the pointer, in the screen batch's y-up pixels. */
    private class Crosshair(private val batch: SpriteBatch2D, private val pointer: () -> PointerPosition) : OverlaySystem {
        private val colour = Rgba.of(1f, 0f, 1f, 1f)

        override fun render(target: ScreenTarget, dtSeconds: Float) {
            val at = pointer()
            if (!at.isPointerOver) return
            // Window pixels run down from the top; the screen batch's run up from the bottom.
            val x = at.pointerX
            val y = target.height - at.pointerY
            batch.beginPixels()
            batch.fill(x - ARM, y - 1f, ARM * 2, 3f, colour)
            batch.fill(x - 1f, y - ARM, 3f, ARM * 2, colour)
            batch.end()
        }
    }

    /** Reads one window pixel after every overlay has drawn: [at] in GL's bottom-up pixels. */
    private class CentreProbe : OverlaySystem {
        val frames = AtomicInteger()
        val at = AtomicReference(0 to 0)
        val rgb = AtomicInteger()
        private val buffer: ByteBuffer = ByteBuffer.allocateDirect(4)

        override fun render(target: ScreenTarget, dtSeconds: Float) {
            val (x, y) = at.get()
            buffer.clear()
            GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer)
            rgb.set(((buffer.get(0).toInt() and 0xFF) shl 16) or ((buffer.get(1).toInt() and 0xFF) shl 8) or (buffer.get(2).toInt() and 0xFF))
            frames.incrementAndGet()
        }
    }

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 240
        const val ARM = 8f
        const val CROSSHAIR_RGB = 0xFF00FF
    }
}
