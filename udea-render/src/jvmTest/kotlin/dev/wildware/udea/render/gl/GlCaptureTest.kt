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
 * The pixel path, against a real Kool context: the alpha stomp, the ordering, `afterTick`, the
 * no-perturb guarantee and a region capture.
 *
 * ## Why five scenarios share one `@Test` method
 *
 * Kool allows exactly **one `KoolContext` per JVM, for the life of the JVM** (`KoolThread`'s own
 * KDoc), and `forkEvery = 1` on this suite's `Test` task gives each test *class* a fresh JVM —
 * not each test *method*. Five independent `@Test` methods each calling `KoolBackend.start()`
 * therefore raced to create five contexts in one JVM; only the first ever succeeded and the
 * other four failed with `GlContextException` regardless of what they were actually testing.
 * One context, created once by the fixture below and shared by every scenario in order, is what
 * `forkEvery = 1` actually buys this class.
 *
 * The alpha test is the one that has already cost this engine's ancestor eight rounds of
 * visual review — see `KoolPixelSource.topRowFirst`'s alpha stomp. It is asserted here by
 * decoding the PNG that actually came back, rather than by inspecting a buffer before encoding,
 * because the bug was that the *shipped bytes* carried junk alpha.
 */
class GlCaptureTest {

    @Test
    fun `the pixel path - alpha stomp, draw order, afterTick, no-perturb, region`() {
        GlAvailability.require()
        withHost { backend, host, redQuad ->
            val slot = backend.pipeline!!.capture!!

            // 1. A translucent quad, deliberately. Kool's `BLEND_MULTIPLY_ALPHA` mode writes a
            // product into destination alpha wherever anything is drawn, so a frame with nothing
            // drawn on it comes back opaque whether or not the stomp runs, and a test over an
            // empty scene could not fail.
            redQuad.alpha = 0.5f
            val translucent = decode(slot.capture(CaptureRequest()).bytes)
            assertEquals(RENDER_WIDTH, translucent.width)
            assertEquals(RENDER_HEIGHT, translucent.height)
            var lowest = 255
            for (y in 0 until translucent.height) {
                for (x in 0 until translucent.width) {
                    lowest = minOf(lowest, (translucent.getRGB(x, y) ushr 24) and 0xFF)
                }
            }
            assertEquals(
                255,
                lowest,
                "the read-back hands back destination alpha and the blend mode writes " +
                    "nonsense into it; the stomp in KoolPixelSource must overwrite every byte",
            )

            // 2. The sentinel: a full-frame red quad drawn by the last system in
            // RenderPhase.Debug. If the capture were drained before the renderers, or after the
            // offscreen surface had been unbound, this frame would come back as the clear colour.
            redQuad.alpha = 1f
            val opaque = decode(slot.capture(CaptureRequest()).bytes)
            val centre = opaque.getRGB(opaque.width / 2, opaque.height / 2)
            assertEquals(0xFF, (centre ushr 16) and 0xFF, "the sentinel red quad is missing")

            // 3. `afterTick`: the returned frame must be stamped at or past the requested tick.
            backend.drive(host)
            val target = Tick(30)
            val afterTickResult = slot.capture(CaptureRequest(afterTick = target))
            assertTrue(
                afterTickResult.tick > target,
                "capture was stamped ${afterTickResult.tick.value}, which is not after ${target.value}",
            )

            // 4. A capture must not perturb the simulation. Paused, not stopped: frames keep
            // being drawn - which is what makes a capture possible at all - while no tick runs.
            host.loop.paused = true
            // One capture is one full frame boundary, so any tick already in flight when the
            // pause was set has finished by the time this returns.
            slot.capture(CaptureRequest())
            val ticksBefore = host.totalTicks
            repeat(3) { slot.capture(CaptureRequest()) }
            assertEquals(ticksBefore, host.totalTicks, "a capture advanced the simulation")

            // 5. A region capture returns exactly that region.
            val region = decode(
                slot.capture(CaptureRequest(region = CaptureRegion(0, 0, 16, 8))).bytes,
            )
            assertEquals(16, region.width)
            assertEquals(8, region.height)
        }
    }

    // --- fixture -------------------------------------------------------------------------

    private fun withHost(block: (KoolBackend, GameHost, RedQuadSystem) -> Unit) {
        val registry = RenderRegistry()
        lateinit var redQuad: RedQuadSystem
        registry.register(RenderPhase.Debug, { resources ->
            RedQuadSystem(resources, 1f).also { redQuad = it }
        })
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
            block(backend, host, redQuad)
        } finally {
            backend.close()
        }
    }

    private fun decode(png: ByteArray) = ImageIO.read(ByteArrayInputStream(png))
        ?: error("the captured bytes are not a decodable image")

    /**
     * Fills the offscreen target with red at [alpha], mutable so one context can serve every
     * scenario in the merged test above without a second `RenderSystem` and a second context.
     *
     * Its own one-pixel texture rather than an asset, because `udea-assets` has no pipeline yet
     * and this test is about the pixel path, not about loading.
     */
    private class RedQuadSystem(
        private val resources: RenderResources,
        var alpha: Float,
    ) : RenderSystem {

        private val pixel = SpriteRegion(
            resources.own(SpriteTexture.fromRgba(1, 1, byteArrayOf(-1, 0, 0, -1), "red-quad")),
        )

        override fun render(target: OffscreenTarget, alpha: Float) {
            val batch = resources.batch
            batch.beginPixels()
            batch.draw(pixel, 0f, 0f, target.width.toFloat(), target.height.toFloat(), Rgba.of(1f, 1f, 1f, this.alpha))
            batch.end()
        }
    }

    private companion object {
        const val RENDER_WIDTH = 64
        const val RENDER_HEIGHT = 32
    }
}
