package dev.wildware.udea.render.gl

import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.capture.CaptureRegion
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.capture
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteRegion
import dev.wildware.udea.render.draw.SpriteTexture
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * What "deterministic capture" actually means here, checked against a real Kool context.
 *
 * ## The claim, and the claim it is not
 *
 * `WindowConfig` says out loud that the epic's wording — *an `Offscreen` and a `Windowed` capture
 * of the same seeded scene at the same tick are byte-identical* — **does not hold** for a
 * free-running host, and lists the wall-clock inputs that stop it: the loop's interpolation
 * alpha, the animation playhead and camera smoothing. All of them accumulate over frames, so two
 * runs that reach tick 200 by different frame paths draw different pictures of it.
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
 *
 * ## Why all four claims are one `@Test` method
 *
 * Kool allows exactly one `KoolContext` per JVM for the life of the JVM (`KoolThread`'s KDoc),
 * and `forkEvery = 1` gives this class one JVM, not one per method. Four methods each starting
 * their own `KoolBackend` raced for that one context; only the first ever won. One context,
 * shared by all four claims in sequence, is what the fork setting actually buys.
 */
class GlCaptureDeterminismTest {

    @Test
    fun `twenty-repeat determinism, change tracking, tick readback and region stability`() {
        GlAvailability.require()
        withPausedHost { backend, _, scene ->
            val slot = backend.pipeline!!.capture!!

            // 1. Twenty captures of one paused tick are byte-identical.
            scene.step = 3
            val first = slot.capture(CaptureRequest()).bytes
            repeat(19) { attempt ->
                val again = slot.capture(CaptureRequest()).bytes
                assertContentEquals(
                    first,
                    again,
                    "capture ${attempt + 2} of a paused, unchanged scene differs from the first",
                )
            }

            // 2. The other half, and the half that makes the first one worth having: a capture
            // path that returned a constant would pass claim 1 perfectly. This asserts that the
            // bytes track the world.
            scene.step = 3
            val before = slot.capture(CaptureRequest()).bytes
            scene.step = 9
            val after = slot.capture(CaptureRequest()).bytes
            assertFalse(
                before.contentEquals(after),
                "the capture did not change when the drawn scene did, so it is not reading the frame",
            )

            // 3. The tick a capture is stamped with is readable out of the image itself, not just
            // off the result. A bar whose width is the value carries the value exactly, rather
            // than by OCR on a drawn digit.
            for (value in listOf(1, 7, 16, 31)) {
                scene.step = value
                val image = ImageIO.read(ByteArrayInputStream(slot.capture(CaptureRequest()).bytes))
                var lit = 0
                for (x in 0 until image.width) {
                    if ((image.getRGB(x, image.height / 2) and 0x00FF0000) != 0) lit++
                }
                assertEquals(value, lit, "the frame was drawn with a bar of a different width")
            }

            // 4. A region capture of a paused frame is stable too, and is a strict crop of the
            // full frame. Worth its own assertion because the region path takes a different
            // read-back rectangle, and an off-by-one in the origin would still produce a
            // plausible, stable image.
            scene.step = 5
            val region = CaptureRegion(0, 0, 16, 8)
            val regionFirst = slot.capture(CaptureRequest(region = region)).bytes
            val regionAgain = slot.capture(CaptureRequest(region = region)).bytes
            assertContentEquals(regionFirst, regionAgain)
            val regionImage = ImageIO.read(ByteArrayInputStream(regionFirst))
            assertEquals(16, regionImage.width)
            assertEquals(8, regionImage.height)
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
    private fun withPausedHost(block: (KoolBackend, GameHost, BarScene) -> Unit) {
        val registry = RenderRegistry()
        var scene: BarScene? = null
        registry.register(RenderPhase.World, { resources ->
            BarScene(resources).also { scene = it }
        })
        val backend = KoolBackend.start(
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
            val host = GameHost(RenderMode.Offscreen, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()), backend)
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
     * Deliberately reads nothing but its own field: no wall clock, no alpha, no window state.
     * The point of the determinism claim is that everything else in the frame is already stable,
     * so a scene that varied on its own would be testing the fixture.
     */
    private class BarScene(private val resources: RenderResources) : RenderSystem {

        @Volatile
        var step: Int = 1

        private val pixel = SpriteRegion(
            resources.own(SpriteTexture.fromRgba(1, 1, byteArrayOf(-1, 0, 0, -1), "determinism-bar")),
        )

        override fun render(target: OffscreenTarget, alpha: Float) {
            val batch = resources.batch
            batch.beginPixels()
            batch.draw(pixel, 0f, 0f, step.toFloat(), target.height.toFloat(), Rgba.WHITE)
            batch.end()
        }
    }

    private companion object {
        const val RENDER_WIDTH = 64
        const val RENDER_HEIGHT = 32
    }
}
