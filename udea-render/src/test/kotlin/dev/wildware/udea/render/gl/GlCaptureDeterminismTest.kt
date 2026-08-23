package dev.wildware.udea.render.gl

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.Matrix4
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.backend.Lwjgl3Backend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.capture.CaptureRequest
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What "deterministic capture" actually means here, checked against a real driver.
 *
 * ## The claim, and the claim it is not
 *
 * `WindowConfig` says out loud that the epic's wording — *an `Offscreen` and a `Windowed` capture
 * of the same seeded scene at the same tick are byte-identical* — **does not hold** for a
 * free-running host, and lists the four wall-clock inputs that stop it: the loop's interpolation
 * alpha, the animation playhead, particle emitters and camera smoothing. All four accumulate over
 * frames, so two runs that reach tick 200 by different frame paths draw different pictures of it.
 *
 * The narrower claim this test pins is the one the agent's workflow depends on, and it is a
 * property of the *paused* loop rather than of the capture path:
 *
 * > While the loop is paused, the accumulator does not move, so `GameLoop.alpha` is constant;
 * > with a scene that is a function of the simulated state alone, every frame drawn is the same
 * > frame, and two captures of it are byte-identical.
 *
 * That is exactly the shape of the Phase 1 demo — pause, step, screenshot, rewind, screenshot,
 * diff — and it is what makes a diff between two captures mean "the world changed" rather than
 * "time passed". A test over an unpaused host could not make the claim and would flake trying.
 *
 * Twenty repeats, as issue #77 asks for, because a once-in-fifty non-determinism is exactly the
 * kind that survives a single-shot test and then ruins an agent's afternoon.
 */
class GlCaptureDeterminismTest {

    @Test
    fun `twenty captures of one paused tick are byte-identical`() {
        GlAvailability.require()
        withPausedHost { backend, _, scene ->
            scene.step = 3
            val slot = backend.pipeline!!.capture!!
            val first = slot.capture(CaptureRequest()).bytes

            repeat(19) { attempt ->
                val again = slot.capture(CaptureRequest()).bytes
                assertContentEquals(
                    first,
                    again,
                    "capture ${attempt + 2} of a paused, unchanged scene differs from the first",
                )
            }
        }
    }

    /**
     * The other half, and the half that makes the first one worth having.
     *
     * A capture path that returned a constant would pass the test above perfectly. This asserts
     * that the bytes track the world: move the scene by one step and the picture changes.
     */
    @Test
    fun `a capture changes when the scene does`() {
        GlAvailability.require()
        withPausedHost { backend, _, scene ->
            val slot = backend.pipeline!!.capture!!
            scene.step = 3
            val before = slot.capture(CaptureRequest()).bytes

            scene.step = 9
            val after = slot.capture(CaptureRequest()).bytes

            assertFalse(
                before.contentEquals(after),
                "the capture did not change when the drawn scene did, so it is not reading the frame",
            )
        }
    }

    /**
     * The tick a capture is stamped with is readable *out of the image*, not just off the result.
     *
     * Issue #77 asks for a tick counter rendered into the frame and read back from the decoded
     * PNG. A drawn digit would need a font and an asset pipeline; a bar whose width is the value
     * carries the same information and is read exactly rather than by OCR. This is what rules out
     * the whole class of "the capture came back, but it was the previous frame" defect.
     */
    @Test
    fun `the decoded image carries the value the scene was drawn with`() {
        GlAvailability.require()
        withPausedHost { backend, _, scene ->
            val slot = backend.pipeline!!.capture!!
            for (value in listOf(1, 7, 16, 31)) {
                scene.step = value

                val image = ImageIO.read(ByteArrayInputStream(slot.capture(CaptureRequest()).bytes))

                var lit = 0
                for (x in 0 until image.width) {
                    if ((image.getRGB(x, image.height / 2) and 0x00FF0000) != 0) lit++
                }
                assertEquals(value, lit, "the frame was drawn with a bar of a different width")
            }
        }
    }

    /**
     * A region capture of a paused frame is stable too, and is a strict crop of the full frame.
     *
     * Worth its own assertion because the region path takes a different `glReadPixels` rectangle,
     * and an off-by-one in the origin would still produce a plausible, stable image.
     */
    @Test
    fun `a region capture is the same crop every time`() {
        GlAvailability.require()
        withPausedHost { backend, _, scene ->
            scene.step = 5
            val slot = backend.pipeline!!.capture!!
            val region = dev.wildware.udea.render.capture.CaptureRegion(0, 0, 16, 8)

            val first = slot.capture(CaptureRequest(region = region)).bytes
            val again = slot.capture(CaptureRequest(region = region)).bytes

            assertContentEquals(first, again)
            val image = ImageIO.read(ByteArrayInputStream(first))
            assertEquals(16, image.width)
            assertEquals(8, image.height)
        }
    }

    // --- fixture -----------------------------------------------------------------------------

    /**
     * A paused `Offscreen` host with a scene whose picture is a function of one integer.
     *
     * Paused before any capture, because the claim under test is about a paused loop. The loop
     * still renders while paused — that is what makes a capture possible at all — and
     * `GameLoop.frame` only touches the accumulator when it is running, which is precisely why
     * `alpha` stops moving.
     */
    private fun withPausedHost(block: (Lwjgl3Backend, GameHost, BarScene) -> Unit) {
        val registry = RenderRegistry()
        var scene: BarScene? = null
        registry.register(RenderPhase.World, { resources ->
            BarScene(resources).also { scene = it }
        })
        val backend = Lwjgl3Backend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-determinism-test",
                windowWidth = 160,
                windowHeight = 120,
                renderWidth = RENDER_WIDTH,
                renderHeight = RENDER_HEIGHT,
            ),
            registry,
        )
        try {
            val host = GameHost(RenderMode.Offscreen, UdeaGameDef(modules = emptyList()), backend)
            host.loop.paused = true
            backend.drive(host)
            block(backend, host, checkNotNull(scene) { "the scene was never constructed" })
        } finally {
            backend.close()
        }
    }

    /**
     * Draws a red bar [step] pixels wide across the middle of the frame.
     *
     * Deliberately reads nothing but its own field: no wall clock, no alpha, no `Gdx.graphics`.
     * The point of the determinism claim is that everything else in the frame is already stable,
     * so a scene that varied on its own would be testing the fixture.
     */
    private class BarScene(private val resources: RenderResources) : RenderSystem {

        @Volatile
        var step: Int = 1

        private val projection = Matrix4()

        private val pixel: TextureRegion = resources.own(
            Texture(
                Pixmap(1, 1, Pixmap.Format.RGBA8888).apply {
                    setColor(Color.RED)
                    fill()
                },
            ),
        ).let(::TextureRegion)

        override fun render(target: OffscreenTarget, alpha: Float) {
            projection.setToOrtho2D(0f, 0f, target.width.toFloat(), target.height.toFloat())
            val batch = resources.batch
            batch.projectionMatrix = projection
            batch.begin()
            batch.draw(pixel, 0f, 0f, step.toFloat(), target.height.toFloat())
            batch.end()
        }
    }

    private companion object {
        const val RENDER_WIDTH = 64
        const val RENDER_HEIGHT = 32
    }
}
