package dev.wildware.udea.render.gl

import dev.wildware.udea.core.Tick
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pixel path, against a real Kool context: the alpha stomp, the ordering, and `afterTick`.
 *
 * The alpha test is the one that has already cost this engine's ancestor eight rounds of
 * visual review — see `KoolPixelSource.topRowFirst`'s alpha stomp. It is asserted here by
 * decoding the PNG that actually came back, rather than by inspecting a buffer before encoding,
 * because the bug was that the *shipped bytes* carried junk alpha.
 */
class GlCaptureTest {

    @Test
    fun `every alpha byte of a captured frame is 255`() {
        GlAvailability.require()
        // A **translucent** quad, deliberately. Kool's `BLEND_MULTIPLY_ALPHA` mode writes a
        // product into destination alpha wherever anything is drawn, so a frame with nothing
        // drawn on it comes back opaque whether or not the stomp runs, and a test over an empty
        // scene could not fail.
        withHost(sentinel = true, quadAlpha = 0.5f) { backend, _ ->
            val slot = backend.pipeline!!.capture!!

            val result = slot.capture(CaptureRequest())

            val image = decode(result.bytes)
            assertEquals(RENDER_WIDTH, image.width)
            assertEquals(RENDER_HEIGHT, image.height)

            var lowest = 255
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    lowest = minOf(lowest, (image.getRGB(x, y) ushr 24) and 0xFF)
                }
            }
            assertEquals(
                255,
                lowest,
                "the read-back hands back destination alpha and the blend mode writes " +
                    "nonsense into it; the stomp in KoolPixelSource must overwrite every byte",
            )
        }
    }

    @Test
    fun `a capture contains what the last renderer drew`() {
        GlAvailability.require()
        // The sentinel: a full-frame red quad drawn by the last system in RenderPhase.Debug. If
        // the capture were drained before the renderers, or after the offscreen surface had been
        // unbound, this frame would come back as the clear colour.
        withHost(sentinel = true) { backend, _ ->
            val result = backend.pipeline!!.capture!!.capture(CaptureRequest())

            val image = decode(result.bytes)
            val centre = image.getRGB(image.width / 2, image.height / 2)
            assertEquals(0xFF, (centre ushr 16) and 0xFF, "the sentinel red quad is missing")
        }
    }

    @Test
    fun `a capture requested afterTick comes back stamped at or past that tick`() {
        GlAvailability.require()
        withHost { backend, host ->
            backend.drive(host)
            val target = Tick(30)

            val result = backend.pipeline!!.capture!!.capture(CaptureRequest(afterTick = target))

            assertTrue(
                result.tick > target,
                "capture was stamped ${result.tick.value}, which is not after ${target.value}",
            )
        }
    }

    @Test
    fun `a capture does not perturb the simulation`() {
        GlAvailability.require()
        withHost { backend, host ->
            val slot = backend.pipeline!!.capture!!
            // Paused, not stopped: frames keep being drawn — which is what makes a capture
            // possible at all — while no tick runs. Anything the tick count does from here is
            // the capture's doing.
            host.loop.paused = true
            // One capture is one full frame boundary, so any tick already in flight when the
            // pause was set has finished by the time this returns.
            slot.capture(CaptureRequest())
            val ticksBefore = host.totalTicks

            repeat(3) { slot.capture(CaptureRequest()) }

            assertEquals(ticksBefore, host.totalTicks, "a capture advanced the simulation")
        }
    }

    @Test
    fun `a region capture returns exactly that region`() {
        GlAvailability.require()
        withHost { backend, _ ->
            val result = backend.pipeline!!.capture!!
                .capture(CaptureRequest(region = CaptureRegion(0, 0, 16, 8)))

            val image = decode(result.bytes)
            assertEquals(16, image.width)
            assertEquals(8, image.height)
        }
    }

    // --- fixture -------------------------------------------------------------------------

    private fun withHost(
        sentinel: Boolean = false,
        quadAlpha: Float = 1f,
        block: (KoolBackend, GameHost) -> Unit,
    ) {
        val registry = RenderRegistry()
        if (sentinel) {
            registry.register(RenderPhase.Debug, { resources -> RedQuadSystem(resources, quadAlpha) })
        }
        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-capture-test",
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
            block(backend, host)
        } finally {
            backend.close()
        }
    }

    private fun decode(png: ByteArray) = ImageIO.read(ByteArrayInputStream(png))
        ?: error("the captured bytes are not a decodable image")

    /**
     * Fills the offscreen target with red at [alpha].
     *
     * Its own one-pixel texture rather than an asset, because `udea-assets` has no pipeline
     * yet and this test is about the pixel path, not about loading.
     */
    private class RedQuadSystem(
        private val resources: RenderResources,
        alpha: Float,
    ) : RenderSystem {

        private val pixel = SpriteRegion(
            resources.own(SpriteTexture.fromRgba(1, 1, byteArrayOf(-1, 0, 0, -1), "red-quad")),
        )

        private val tint = Rgba.of(1f, 1f, 1f, alpha)

        override fun render(target: OffscreenTarget, alpha: Float) {
            val batch = resources.batch
            batch.beginPixels()
            batch.draw(pixel, 0f, 0f, target.width.toFloat(), target.height.toFloat(), tint)
            batch.end()
        }
    }

    private companion object {
        const val RENDER_WIDTH = 64
        const val RENDER_HEIGHT = 32
    }
}
